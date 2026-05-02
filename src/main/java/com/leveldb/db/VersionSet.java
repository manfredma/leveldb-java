package com.leveldb.db;

import com.leveldb.common.*;
import com.leveldb.log.*;
import com.leveldb.table.TableCache;
import java.io.*;
import java.util.*;

/**
 * 管理 Version 链表和 MANIFEST 文件。对应 C++ db/version_set.h + version_set.cc VersionSet。
 */
public class VersionSet {
    private final String dbname;
    private final Options options;
    private final TableCache tableCache;
    private final InternalKeyComparator icmp;

    private long nextFileNumber = 2;
    private long manifestFileNumber;
    private long lastSequence;
    private long logNumber;
    private long prevLogNumber;

    private LogWriter descriptorLog;
    private final Version dummyVersions;
    private Version current;
    private final Slice[] compactPointer = new Slice[DbConfig.NUM_LEVELS];

    public VersionSet(String dbname, Options options, TableCache tableCache,
                      InternalKeyComparator icmp) {
        this.dbname = dbname;
        this.options = options;
        this.tableCache = tableCache;
        this.icmp = icmp;
        this.dummyVersions = new Version(this);
        this.current = new Version(this);
        appendVersion(current);
    }

    public TableCache tableCache()                      { return tableCache; }
    public InternalKeyComparator internalKeyComparator(){ return icmp; }
    public Version current()                            { return current; }
    public long lastSequence()                          { return lastSequence; }
    public void setLastSequence(long s)                 { lastSequence = s; }
    public long newFileNumber()                         { return nextFileNumber++; }
    public long manifestFileNumber()                    { return manifestFileNumber; }
    public long logNumber()                             { return logNumber; }
    public long prevLogNumber()                         { return prevLogNumber; }
    public void setLogNumber(long n)                    { logNumber = n; }
    public void setPrevLogNumber(long n)                { prevLogNumber = n; }
    public int numLevelFiles(int level)                 { return current.files[level].size(); }

    public long numLevelBytes(int level) {
        long total = 0;
        for (FileMetaData f : current.files[level]) total += f.fileSize;
        return total;
    }

    public synchronized void logAndApply(VersionEdit edit) throws IOException {
        if (edit.logNumber() != null) logNumber = edit.logNumber();
        if (edit.nextFileNumber() != null)
            nextFileNumber = Math.max(nextFileNumber, edit.nextFileNumber() + 1);
        if (edit.lastSequence() != null) lastSequence = edit.lastSequence();

        Version v = new Version(this);
        for (int level = 0; level < DbConfig.NUM_LEVELS; level++) {
            v.files[level] = new ArrayList<>(current.files[level]);
        }
        // 删除文件
        for (long[] df : edit.deletedFiles()) {
            int level = (int) df[0]; long number = df[1];
            v.files[level].removeIf(f -> f.number == number);
        }
        // 添加文件
        List<long[]> newMeta = edit.newFilesMeta();
        List<Slice[]> newKeys = edit.newFilesKeys();
        for (int i = 0; i < newMeta.size(); i++) {
            long[] meta = newMeta.get(i);
            Slice[] keys = newKeys.get(i);
            int level = (int) meta[0];
            FileMetaData fm = new FileMetaData();
            fm.number    = meta[1];
            fm.fileSize  = meta[2];
            fm.smallest  = keys[0];
            fm.largest   = keys[1];
            fm.allowedSeeks = (int) Math.max(1, fm.fileSize / (16 * 1024));
            v.files[level].add(fm);
        }
        // Level-1+ 排序
        for (int level = 1; level < DbConfig.NUM_LEVELS; level++) {
            v.files[level].sort((a, b) -> icmp.compare(a.smallest, b.smallest));
        }
        finalizeVersion(v);

        // 写 MANIFEST
        byte[] encoded = edit.encodeTo();
        if (descriptorLog == null) {
            manifestFileNumber = newFileNumber();
            File mf = new File(FileName.manifestFileName(dbname, manifestFileNumber));
            descriptorLog = new LogWriter(mf);
            writeSnapshot();
        }
        descriptorLog.addRecord(encoded);
        descriptorLog.sync();
        writeCurrentFile(manifestFileNumber);
        appendVersion(v);
    }

    private void writeSnapshot() throws IOException {
        VersionEdit snap = new VersionEdit();
        snap.setComparatorName(icmp.name());
        for (int level = 0; level < DbConfig.NUM_LEVELS; level++) {
            for (FileMetaData f : current.files[level]) {
                snap.addFile(level, f.number, f.fileSize, f.smallest, f.largest);
            }
        }
        descriptorLog.addRecord(snap.encodeTo());
    }

    private void writeCurrentFile(long manifestNumber) throws IOException {
        String content = "MANIFEST-" + String.format("%06d", manifestNumber) + "\n";
        File currentFile = new File(FileName.currentFileName(dbname));
        try (FileOutputStream fos = new FileOutputStream(currentFile)) {
            fos.write(content.getBytes("UTF-8"));
        }
    }

    public boolean recover() throws IOException {
        File currentFile = new File(FileName.currentFileName(dbname));
        if (!currentFile.exists()) return false;

        byte[] currentBytes = readFile(currentFile);
        String manifestName = new String(currentBytes, "UTF-8").trim();
        File manifestFile = new File(dbname + "/" + manifestName);
        if (!manifestFile.exists()) throw new IOException("Missing MANIFEST: " + manifestName);
        // 记录当前 MANIFEST 编号
        String numStr = manifestName.replace("MANIFEST-", "").trim();
        try { manifestFileNumber = Long.parseLong(numStr); } catch (NumberFormatException ignored) {}

        try (LogReader reader = new LogReader(manifestFile, false, 0)) {
            byte[] record;
            while ((record = reader.readRecord()) != null) {
                VersionEdit edit = VersionEdit.decodeFrom(record);
                if (edit.logNumber() != null)       logNumber       = edit.logNumber();
                if (edit.nextFileNumber() != null)  nextFileNumber  = Math.max(nextFileNumber, edit.nextFileNumber());
                if (edit.lastSequence() != null)    lastSequence    = edit.lastSequence();
                // 应用文件变更到 current
                for (long[] df : edit.deletedFiles()) {
                    current.files[(int) df[0]].removeIf(f -> f.number == df[1]);
                }
                List<long[]> newMeta = edit.newFilesMeta();
                List<Slice[]> newKeys = edit.newFilesKeys();
                for (int i = 0; i < newMeta.size(); i++) {
                    long[] meta = newMeta.get(i);
                    Slice[] keys = newKeys.get(i);
                    int level = (int) meta[0];
                    FileMetaData fm = new FileMetaData();
                    fm.number   = meta[1];
                    fm.fileSize = meta[2];
                    fm.smallest = keys[0];
                    fm.largest  = keys[1];
                    fm.allowedSeeks = (int) Math.max(1, fm.fileSize / (16 * 1024));
                    current.files[level].add(fm);
                }
            }
        }
        finalizeVersion(current);
        return true;
    }

    private byte[] readFile(File f) throws IOException {
        byte[] buf = new byte[(int) f.length()];
        try (FileInputStream fis = new FileInputStream(f)) { fis.read(buf); }
        return buf;
    }

    private void finalizeVersion(Version v) {
        double bestScore = -1;
        int bestLevel = -1;
        for (int level = 0; level < DbConfig.NUM_LEVELS - 1; level++) {
            double score;
            if (level == 0) {
                score = v.files[level].size() / (double) DbConfig.L0_COMPACTION_TRIGGER;
            } else {
                long levelBytes = 0;
                for (FileMetaData f : v.files[level]) levelBytes += f.fileSize;
                score = levelBytes / (double) DbConfig.maxBytesForLevel(level);
            }
            if (score > bestScore) { bestScore = score; bestLevel = level; }
        }
        v.compactionScore = bestScore;
        v.compactionLevel = bestLevel;
    }

    private void appendVersion(Version v) {
        v.refs = 1;
        v.prev = dummyVersions.prev;
        v.next = dummyVersions;
        v.prev.next = v;
        v.next.prev = v;
        current = v;
    }

    void removeVersion(Version v) {
        v.prev.next = v.next;
        v.next.prev = v.prev;
    }

    public boolean needsCompaction() {
        return current.compactionScore >= 1 || current.fileToCompact != null;
    }

    public Compaction pickCompaction() {
        int level;
        if (current.compactionScore >= 1) {
            level = current.compactionLevel;
        } else if (current.fileToCompact != null) {
            level = current.fileToCompactLevel;
        } else {
            return null;
        }
        if (level < 0 || level >= DbConfig.NUM_LEVELS - 1) return null;

        Compaction c = new Compaction(level, options.maxFileSize, current);

        if (current.fileToCompact != null && level == current.fileToCompactLevel) {
            c.inputs[0].add(current.fileToCompact);
        } else {
            List<FileMetaData> levelFiles = current.files[level];
            if (levelFiles.isEmpty()) return null;
            FileMetaData selected = levelFiles.get(0);
            if (compactPointer[level] != null) {
                for (FileMetaData f : levelFiles) {
                    if (icmp.compare(f.largest, compactPointer[level]) > 0) { selected = f; break; }
                }
            }
            c.inputs[0].add(selected);
        }

        if (level == 0) expandLevel0Inputs(c);
        addOverlappingInputs(level + 1, getRange(c.inputs[0])[0], getRange(c.inputs[0])[1], c.inputs[1]);
        if (level + 2 < DbConfig.NUM_LEVELS) {
            Slice[] range2 = getRange2(c);
            if (range2[0] != null) addOverlappingInputs(level + 2, range2[0], range2[1], c.grandparents);
        }
        return c;
    }

    private void expandLevel0Inputs(Compaction c) {
        Slice[] range = getRange(c.inputs[0]);
        if (range[0] == null) return;
        boolean changed;
        do {
            changed = false;
            for (FileMetaData f : current.files[0]) {
                if (!c.inputs[0].contains(f) &&
                    icmp.userComparator().compare(InternalKey.extractUserKey(f.largest.getBytes()), range[0]) >= 0 &&
                    icmp.userComparator().compare(InternalKey.extractUserKey(f.smallest.getBytes()), range[1]) <= 0) {
                    c.inputs[0].add(f);
                    range = getRange(c.inputs[0]);
                    changed = true;
                }
            }
        } while (changed);
    }

    private void addOverlappingInputs(int level, Slice smallUser, Slice largeUser,
                                       List<FileMetaData> result) {
        if (level >= DbConfig.NUM_LEVELS || smallUser == null) return;
        for (FileMetaData f : current.files[level]) {
            if (icmp.userComparator().compare(InternalKey.extractUserKey(f.largest.getBytes()), smallUser) < 0) continue;
            if (icmp.userComparator().compare(InternalKey.extractUserKey(f.smallest.getBytes()), largeUser) > 0) continue;
            result.add(f);
        }
    }

    private Slice[] getRange(List<FileMetaData> files) {
        Slice small = null, large = null;
        for (FileMetaData f : files) {
            Slice s = InternalKey.extractUserKey(f.smallest.getBytes());
            Slice l = InternalKey.extractUserKey(f.largest.getBytes());
            if (small == null || icmp.userComparator().compare(s, small) < 0) small = s;
            if (large == null || icmp.userComparator().compare(l, large) > 0) large = l;
        }
        return new Slice[]{small, large};
    }

    private Slice[] getRange2(Compaction c) {
        List<FileMetaData> all = new ArrayList<>(c.inputs[0]);
        all.addAll(c.inputs[1]);
        return getRange(all);
    }

    public void addLiveFiles(Set<Long> live) {
        Version v = dummyVersions.next;
        while (v != dummyVersions) {
            for (List<FileMetaData> levelFiles : v.files) {
                for (FileMetaData f : levelFiles) live.add(f.number);
            }
            v = v.next;
        }
    }
}
