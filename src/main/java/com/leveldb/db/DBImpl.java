package com.leveldb.db;

import com.leveldb.common.*;
import com.leveldb.iterator.*;
import com.leveldb.log.*;
import com.leveldb.memtable.*;
import com.leveldb.table.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

/**
 * DB 接口的完整实现。对应 C++ db/db_impl.h + db_impl.cc。
 *
 * 并发模型：
 *   - mutex 保护所有内存状态
 *   - 后台单线程（bgExecutor）执行 flush 和 compaction
 *   - 写操作使用队列，等待前一个 writer 完成
 */
public class DBImpl implements DB {
    private final Options options;
    private final String dbname;
    private final TableCache tableCache;
    private final InternalKeyComparator internalComparator;
    private final ReentrantLock mutex = new ReentrantLock();
    private final Condition bgCondition = mutex.newCondition();

    private MemTable mem;
    private MemTable imm;
    private volatile boolean hasImm;

    private long logFileNumber;
    private LogWriter log;

    private VersionSet versions;
    private final SnapshotList snapshots = new SnapshotList();
    private final Set<Long> pendingOutputs = new HashSet<>();

    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "leveldb-compaction");
        t.setDaemon(true);
        return t;
    });
    private boolean bgCompactionScheduled;
    private volatile IOException bgError;

    // 写操作队列
    private final Deque<Writer> writers = new ArrayDeque<>();

    private DBImpl(Options options, String dbname) throws IOException {
        this.options = options;
        this.dbname = dbname;
        this.internalComparator = new InternalKeyComparator(options.comparator);
        new File(dbname).mkdirs();
        this.tableCache = new TableCache(dbname, options, options.maxOpenFiles - 10);
        this.versions = new VersionSet(dbname, options, tableCache, internalComparator);
    }

    public static DB open(Options options, String name) throws IOException {
        DBImpl db = new DBImpl(options, name);
        db.recoverDB();
        return db;
    }

    private void recoverDB() throws IOException {
        mutex.lock();
        try {
            File dbDir = new File(dbname);
            boolean hasCurrentFile = new File(FileName.currentFileName(dbname)).exists();
            if (!hasCurrentFile) {
                if (!options.createIfMissing) throw new IOException("DB does not exist: " + dbname);
                VersionEdit edit = new VersionEdit();
                edit.setComparatorName(internalComparator.name());
                edit.setLogNumber(0L);
                edit.setNextFileNumber(2L);
                edit.setLastSequence(0L);
                versions.logAndApply(edit);
            } else {
                versions.recover();
            }

            long minLogNumber = versions.logNumber();
            List<Long> logNumbers = new ArrayList<>();
            File[] files = dbDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    long[] num = {0};
                    if (FileName.parseFileName(f.getName(), num) == FileName.FileType.LOG
                        && num[0] >= minLogNumber) {
                        logNumbers.add(num[0]);
                    }
                }
            }
            Collections.sort(logNumbers);
            for (long logNum : logNumbers) {
                recoverLogFile(logNum);
            }

            long newLogNum = versions.newFileNumber();
            logFileNumber = newLogNum;
            log = new LogWriter(new File(FileName.logFileName(dbname, newLogNum)));

            if (mem == null) {
                mem = new MemTable(internalComparator);
            }
        } finally {
            mutex.unlock();
        }
    }

    private void recoverLogFile(long logNumber) throws IOException {
        File logFile = new File(FileName.logFileName(dbname, logNumber));
        if (!logFile.exists()) return;
        MemTable recoverMem = new MemTable(internalComparator);
        try (LogReader reader = new LogReader(logFile, false, 0)) {
            byte[] record;
            while ((record = reader.readRecord()) != null) {
                WriteBatch batch = WriteBatch.fromBytes(record);
                long seq = batch.getSequenceNumber();
                applyBatchToMemTable(batch, seq, recoverMem);
                if (seq > versions.lastSequence()) versions.setLastSequence(seq);
                if (recoverMem.approximateMemoryUsage() > options.writeBufferSize) {
                    flushMemTable(recoverMem);
                    recoverMem = new MemTable(internalComparator);
                }
            }
        }
        if (recoverMem.approximateMemoryUsage() > 0) {
            flushMemTable(recoverMem);
        }
    }

    private void flushMemTable(MemTable memToFlush) throws IOException {
        VersionEdit edit = new VersionEdit();
        Version base = versions.current();
        base.ref();
        try {
            writeLevel0Table(memToFlush, edit, base);
        } finally {
            base.unref();
        }
        edit.setLogNumber(logFileNumber);
        versions.logAndApply(edit);
        deleteObsoleteFiles();
    }

    private void applyBatchToMemTable(WriteBatch batch, long baseSeq, MemTable target) {
        long[] seq = {baseSeq};
        batch.forEach(new WriteBatch.Handler() {
            public void put(Slice key, Slice value) {
                target.add(seq[0]++, ValueType.VALUE, key, value);
            }
            public void delete(Slice key) {
                target.add(seq[0]++, ValueType.DELETION, key, Slice.from(""));
            }
        });
    }

    @Override
    public void put(WriteOptions opts, Slice key, Slice value) throws IOException {
        WriteBatch batch = new WriteBatch();
        batch.put(key, value);
        write(opts, batch);
    }

    @Override
    public void delete(WriteOptions opts, Slice key) throws IOException {
        WriteBatch batch = new WriteBatch();
        batch.delete(key);
        write(opts, batch);
    }

    @Override
    public void write(WriteOptions opts, WriteBatch updates) throws IOException {
        if (updates == null) return;
        Writer w = new Writer(updates, opts.sync);
        mutex.lock();
        try {
            writers.addLast(w);
            // 等待成为队列头
            while (!w.done && writers.peekFirst() != w) {
                bgCondition.await();
            }
            if (w.done) return;

            makeRoomForWrite(false);

            // 本 writer 是 leader，取出所有等待中的 batch 合并
            long lastSeq = versions.lastSequence();
            List<Writer> group = drainWriters();
            WriteBatch merged = mergeBatches(group);
            long seqStart = lastSeq + 1;
            merged.setSequenceNumber(seqStart);
            versions.setLastSequence(seqStart + merged.count() - 1);

            // 释放锁写 WAL（IO 操作）
            mutex.unlock();
            try {
                log.addRecord(merged.getContents());
                if (opts.sync) log.sync();
            } finally {
                mutex.lock();
            }

            // 写 MemTable
            applyBatchToMemTable(merged, seqStart, mem);

            // 唤醒所有已完成的 writers
            for (Writer done : group) {
                done.done = true;
                writers.remove(done);
            }
            bgCondition.signalAll();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        } finally {
            mutex.unlock();
        }
    }

    private List<Writer> drainWriters() {
        List<Writer> group = new ArrayList<>();
        long sizeLimit = 1024 * 1024L;
        long size = 0;
        for (Writer w : writers) {
            if (w.batch == null) break;
            int wSize = w.batch.getContents().length;
            if (!group.isEmpty() && size + wSize > sizeLimit) break;
            size += wSize;
            group.add(w);
        }
        return group;
    }

    private WriteBatch mergeBatches(List<Writer> group) {
        if (group.size() == 1) return group.get(0).batch;
        WriteBatch merged = new WriteBatch();
        for (Writer w : group) {
            w.batch.forEach(new WriteBatch.Handler() {
                public void put(Slice key, Slice value) { merged.put(key, value); }
                public void delete(Slice key) { merged.delete(key); }
            });
        }
        return merged;
    }

    private void makeRoomForWrite(boolean force) throws IOException, InterruptedException {
        boolean allowDelay = !force;
        while (true) {
            if (bgError != null) throw bgError;
            if (allowDelay && versions.numLevelFiles(0) >= DbConfig.L0_SLOWDOWN_WRITES_TRIGGER) {
                mutex.unlock();
                try {
                    Thread.sleep(1);
                } finally {
                    mutex.lock();
                }
                allowDelay = false;
            } else if (!force && mem.approximateMemoryUsage() <= options.writeBufferSize) {
                break;
            } else if (imm != null) {
                bgCondition.await();
            } else if (versions.numLevelFiles(0) >= DbConfig.L0_STOP_WRITES_TRIGGER) {
                bgCondition.await();
            } else {
                // 将当前 MemTable 变为 immutable，创建新的
                long newLogNum = versions.newFileNumber();
                try {
                    log.close();
                } catch (IOException ignored) {}
                log = new LogWriter(new File(FileName.logFileName(dbname, newLogNum)));
                logFileNumber = newLogNum;
                imm = mem;
                hasImm = true;
                mem = new MemTable(internalComparator);
                force = false;
                maybeScheduleCompaction();
            }
        }
    }

    @Override
    public byte[] get(ReadOptions opts, Slice key) throws IOException {
        long snapshotSeq;
        MemTable memSnapshot, immSnapshot;
        Version currentVersion;

        mutex.lock();
        try {
            snapshotSeq = opts.snapshot != null ? opts.snapshot.sequenceNumber : versions.lastSequence();
            memSnapshot = mem;
            immSnapshot = imm;
            currentVersion = versions.current();
            currentVersion.ref();
        } finally {
            mutex.unlock();
        }

        try {
            LookupKey lk = new LookupKey(key, snapshotSeq);
            MemTable.GetResult r = memSnapshot.get(lk);
            if (r != null) return r.deleted ? null : r.value.getBytes();
            if (immSnapshot != null) {
                r = immSnapshot.get(lk);
                if (r != null) return r.deleted ? null : r.value.getBytes();
            }
            Version.GetStats stats = new Version.GetStats();
            byte[] val = currentVersion.get(opts, lk, stats);
            mutex.lock();
            try {
                if (currentVersion.updateStats(stats)) maybeScheduleCompaction();
            } finally {
                mutex.unlock();
            }
            return val;
        } finally {
            mutex.lock();
            try {
                currentVersion.unref();
            } finally {
                mutex.unlock();
            }
        }
    }

    @Override
    public com.leveldb.iterator.Iterator newIterator(ReadOptions opts) {
        mutex.lock();
        long seq;
        List<com.leveldb.iterator.Iterator> list = new ArrayList<>();
        try {
            seq = opts.snapshot != null ? opts.snapshot.sequenceNumber : versions.lastSequence();
            list.add(mem.newIterator());
            if (imm != null) list.add(imm.newIterator());
            try {
                versions.current().addIterators(opts, list);
            } catch (IOException ignored) {}
        } finally {
            mutex.unlock();
        }
        MergingIterator merged = new MergingIterator(internalComparator,
            list.toArray(new com.leveldb.iterator.Iterator[0]));
        return new DBIterator(merged, seq, internalComparator);
    }

    @Override
    public Snapshot getSnapshot() {
        mutex.lock();
        try {
            return snapshots.newSnapshot(versions.lastSequence());
        } finally {
            mutex.unlock();
        }
    }

    @Override
    public void releaseSnapshot(Snapshot snapshot) {
        mutex.lock();
        try {
            snapshots.delete(snapshot);
        } finally {
            mutex.unlock();
        }
    }

    @Override
    public String getProperty(String property) {
        mutex.lock();
        try {
            if (property.startsWith("leveldb.num-files-at-level")) {
                try {
                    int level = Integer.parseInt(property.substring("leveldb.num-files-at-level".length()));
                    return String.valueOf(versions.numLevelFiles(level));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            if (property.equals("leveldb.stats")) {
                StringBuilder sb = new StringBuilder("Level  Files  Size(MB)\n");
                for (int i = 0; i < DbConfig.NUM_LEVELS; i++) {
                    sb.append(String.format("  %d     %d     %.1f\n", i,
                        versions.numLevelFiles(i), versions.numLevelBytes(i) / 1048576.0));
                }
                return sb.toString();
            }
            return null;
        } finally {
            mutex.unlock();
        }
    }

    @Override
    public void compactRange(Slice begin, Slice end) throws IOException {
        mutex.lock();
        try {
            maybeScheduleCompaction();
        } finally {
            mutex.unlock();
        }
        // 等待一下让后台执行
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() throws IOException {
        mutex.lock();
        try {
            while (bgCompactionScheduled) {
                try {
                    bgCondition.await(500, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    break;
                }
            }
        } finally {
            mutex.unlock();
        }
        bgExecutor.shutdown();
        try {
            bgExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {}
        if (log != null) {
            try {
                log.close();
            } catch (IOException ignored) {}
        }
        mem = null;
        imm = null;
    }

    // ─── 后台 Compaction ─────────────────────────────────────────────────────

    private void maybeScheduleCompaction() {
        if (bgCompactionScheduled) return;
        if (imm != null || versions.needsCompaction()) {
            bgCompactionScheduled = true;
            bgExecutor.submit(this::backgroundCall);
        }
    }

    private void backgroundCall() {
        mutex.lock();
        try {
            try {
                backgroundCompaction();
            } catch (IOException e) {
                bgError = e;
            }
        } finally {
            bgCompactionScheduled = false;
            bgCondition.signalAll();
            maybeScheduleCompaction();
            mutex.unlock();
        }
    }

    private void backgroundCompaction() throws IOException {
        if (imm != null) {
            compactMemTable();
            return;
        }
        Compaction c = versions.pickCompaction();
        if (c == null) return;
        if (c.isTrivialMove()) {
            FileMetaData f = c.input(0, 0);
            c.edit().removeFile(c.level(), f.number);
            c.edit().addFile(c.level() + 1, f.number, f.fileSize, f.smallest, f.largest);
            versions.logAndApply(c.edit());
        } else {
            doCompactionWork(c);
        }
        deleteObsoleteFiles();
    }

    private void compactMemTable() throws IOException {
        VersionEdit edit = new VersionEdit();
        Version base = versions.current();
        base.ref();
        try {
            writeLevel0Table(imm, edit, base);
        } finally {
            base.unref();
        }
        edit.setLogNumber(logFileNumber);
        versions.logAndApply(edit);
        imm = null;
        hasImm = false;
        bgCondition.signalAll();
        deleteObsoleteFiles();
    }

    private void writeLevel0Table(MemTable memTable, VersionEdit edit, Version base)
            throws IOException {
        long fileNum = versions.newFileNumber();
        pendingOutputs.add(fileNum);
        File tableFile = new File(FileName.tableFileName(dbname, fileNum));

        com.leveldb.iterator.Iterator it = memTable.newIterator();
        it.seekToFirst();
        if (!it.valid()) {
            pendingOutputs.remove(fileNum);
            return;
        }

        Slice firstKey = null, lastKey = null;
        try (TableBuilder builder = new TableBuilder(options, tableFile)) {
            while (it.valid()) {
                Slice ikey = it.key();
                if (firstKey == null) firstKey = new Slice(ikey.getBytes());
                lastKey = new Slice(ikey.getBytes());
                builder.add(ikey, it.value());
                it.next();
            }
            builder.finish();
        }
        it.close();

        if (firstKey == null) {
            pendingOutputs.remove(fileNum);
            return;
        }

        FileMetaData meta = new FileMetaData();
        meta.number = fileNum;
        meta.fileSize = tableFile.length();
        meta.smallest = firstKey;
        meta.largest = lastKey;
        meta.allowedSeeks = (int) Math.max(1, meta.fileSize / (16 * 1024));

        int level = base.pickLevelForMemTableOutput(
            InternalKey.extractUserKey(firstKey.getBytes()),
            InternalKey.extractUserKey(lastKey.getBytes()));
        edit.addFile(level, meta.number, meta.fileSize, meta.smallest, meta.largest);
        pendingOutputs.remove(fileNum);
    }

    private void doCompactionWork(Compaction c) throws IOException {
        List<com.leveldb.iterator.Iterator> inputIters = new ArrayList<>();
        for (int w = 0; w < 2; w++) {
            for (FileMetaData f : c.inputs[w]) {
                inputIters.add(tableCache.newIterator(new ReadOptions(), f.number, f.fileSize));
            }
        }
        MergingIterator input = new MergingIterator(internalComparator,
            inputIters.toArray(new com.leveldb.iterator.Iterator[0]));
        input.seekToFirst();

        Slice currentUserKey = null;
        boolean hasCurrentUserKey = false;
        long lastSeqForKey = DbConfig.MAX_SEQUENCE_NUMBER;

        TableBuilder currentOutput = null;
        FileMetaData currentMeta = null;
        long currentOutputFileNum = 0;

        while (input.valid()) {
            // 优先处理 imm flush（持有锁时检查）
            if (imm != null) {
                mutex.unlock();
                try {
                    compactMemTable();
                } finally {
                    mutex.lock();
                }
            }

            Slice ikey = input.key();
            byte[] ikBytes = ikey.getBytes();
            Slice userKey = InternalKey.extractUserKey(ikBytes);
            long seq = InternalKey.extractSequenceNumber(ikBytes);
            ValueType vtype = InternalKey.extractValueType(ikBytes);

            boolean drop = false;
            if (!hasCurrentUserKey ||
                internalComparator.userComparator().compare(userKey, currentUserKey) != 0) {
                currentUserKey = userKey;
                hasCurrentUserKey = true;
                lastSeqForKey = DbConfig.MAX_SEQUENCE_NUMBER;
            }
            long snapshotSeq = snapshots.isEmpty()
                ? versions.lastSequence()
                : snapshots.oldest().sequenceNumber;
            if (seq <= snapshotSeq) {
                if (vtype == ValueType.DELETION
                    && seq <= snapshotSeq
                    && c.isBaseLevelForKey(userKey)) {
                    drop = true;
                } else if (lastSeqForKey != DbConfig.MAX_SEQUENCE_NUMBER && seq < lastSeqForKey) {
                    drop = true;
                }
            }
            lastSeqForKey = seq;

            if (!drop) {
                boolean needNewOutput = (currentOutput == null);
                if (!needNewOutput && c.shouldStopBefore(ikey)) needNewOutput = true;
                if (!needNewOutput && currentMeta != null
                    && currentMeta.fileSize > c.maxOutputFileSize()) {
                    needNewOutput = true;
                }
                if (needNewOutput) {
                    if (currentOutput != null) {
                        currentOutput.finish();
                        currentOutput.close();
                        c.edit().addFile(c.level() + 1, currentOutputFileNum,
                            currentMeta.fileSize, currentMeta.smallest, currentMeta.largest);
                        pendingOutputs.remove(currentOutputFileNum);
                    }
                    currentOutputFileNum = versions.newFileNumber();
                    pendingOutputs.add(currentOutputFileNum);
                    currentMeta = new FileMetaData();
                    currentMeta.number = currentOutputFileNum;
                    currentMeta.smallest = new Slice(ikey.getBytes());
                    currentOutput = new TableBuilder(options,
                        new File(FileName.tableFileName(dbname, currentOutputFileNum)));
                }
                currentMeta.largest = new Slice(ikey.getBytes());
                currentOutput.add(ikey, input.value());
                try {
                    currentMeta.fileSize = currentOutput.fileSize();
                } catch (IOException ignored) {}
            }
            input.next();
        }

        if (currentOutput != null) {
            currentOutput.finish();
            currentOutput.close();
            c.edit().addFile(c.level() + 1, currentOutputFileNum,
                currentMeta.fileSize, currentMeta.smallest, currentMeta.largest);
            pendingOutputs.remove(currentOutputFileNum);
        }
        input.close();
        c.addInputDeletions();
        versions.logAndApply(c.edit());
    }

    private void deleteObsoleteFiles() throws IOException {
        Set<Long> live = new HashSet<>(pendingOutputs);
        versions.addLiveFiles(live);
        File dir = new File(dbname);
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            long[] num = {0};
            FileName.FileType type = FileName.parseFileName(f.getName(), num);
            boolean keep = true;
            switch (type) {
                case LOG:      keep = (num[0] >= versions.logNumber()); break;
                case MANIFEST: keep = (num[0] >= versions.manifestFileNumber()); break;
                case TABLE:    keep = live.contains(num[0]); break;
                case TEMP:     keep = live.contains(num[0]); break;
                default:       keep = true; break;
            }
            if (!keep) {
                tableCache.evict(num[0]);
                f.delete();
            }
        }
    }

    // ─── DBIterator ──────────────────────────────────────────────────────────

    /**
     * 封装 MergingIterator，过滤 InternalKey，向上暴露 user_key + value。
     * 跳过比快照更新的版本，跳过墓碑标记。
     */
    private static class DBIterator implements com.leveldb.iterator.Iterator {
        private final com.leveldb.iterator.Iterator inner;
        private final long snapshotSeq;
        private final InternalKeyComparator icmp;
        private Slice savedKey, savedValue;
        private boolean valid;

        DBIterator(com.leveldb.iterator.Iterator inner, long snapshotSeq,
                   InternalKeyComparator icmp) {
            this.inner = inner;
            this.snapshotSeq = snapshotSeq;
            this.icmp = icmp;
        }

        @Override public boolean valid()    { return valid; }
        @Override public Slice key()        { return savedKey; }
        @Override public Slice value()      { return savedValue; }
        @Override public Status status()    { return Status.ok(); }

        @Override
        public void seekToFirst() {
            inner.seekToFirst();
            findNextUserEntry(false, null);
        }

        @Override
        public void seekToLast() {
            inner.seekToLast();
            findPrevUserEntry();
        }

        @Override
        public void seek(Slice target) {
            byte[] ik = InternalKey.encode(target, snapshotSeq, ValueType.VALUE_FOR_SEEK);
            inner.seek(new Slice(ik));
            findNextUserEntry(false, null);
        }

        @Override
        public void next() {
            inner.next();
            findNextUserEntry(false, savedKey);
        }

        @Override
        public void prev() {
            inner.prev();
            findPrevUserEntry();
        }

        @Override
        public void close() {
            inner.close();
        }

        private void findNextUserEntry(boolean skipping, Slice skipKey) {
            valid = false;
            while (inner.valid()) {
                byte[] ikBytes = inner.key().getBytes();
                long seq = InternalKey.extractSequenceNumber(ikBytes);
                if (seq > snapshotSeq) {
                    inner.next();
                    continue;
                }
                ValueType type = InternalKey.extractValueType(ikBytes);
                Slice ukey = InternalKey.extractUserKey(ikBytes);
                if (skipping && skipKey != null
                    && icmp.userComparator().compare(ukey, skipKey) <= 0) {
                    inner.next();
                    continue;
                }
                if (type == ValueType.DELETION) {
                    skipKey = ukey;
                    skipping = true;
                    inner.next();
                } else {
                    savedKey = ukey;
                    savedValue = inner.value();
                    valid = true;
                    return;
                }
            }
        }

        private void findPrevUserEntry() {
            valid = false;
            while (inner.valid()) {
                byte[] ikBytes = inner.key().getBytes();
                long seq = InternalKey.extractSequenceNumber(ikBytes);
                if (seq <= snapshotSeq
                    && InternalKey.extractValueType(ikBytes) == ValueType.VALUE) {
                    savedKey = InternalKey.extractUserKey(ikBytes);
                    savedValue = inner.value();
                    valid = true;
                    return;
                }
                inner.prev();
            }
        }
    }

    // ─── Writer ──────────────────────────────────────────────────────────────

    private static class Writer {
        final WriteBatch batch;
        final boolean sync;
        volatile boolean done;

        Writer(WriteBatch batch, boolean sync) {
            this.batch = batch;
            this.sync = sync;
            this.done = false;
        }
    }
}
