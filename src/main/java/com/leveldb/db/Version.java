package com.leveldb.db;

import com.leveldb.common.*;
import com.leveldb.table.Table;
import java.io.IOException;
import java.util.*;

/**
 * 数据库某时刻所有 Level 的文件集合快照（不可变）。
 * 对应 C++ db/version_set.h Version。
 */
public class Version {
    final VersionSet vset;
    Version next, prev;
    int refs;

    @SuppressWarnings("unchecked")
    final List<FileMetaData>[] files = new List[DbConfig.NUM_LEVELS];

    FileMetaData fileToCompact;
    int fileToCompactLevel = -1;
    double compactionScore = -1;
    int compactionLevel = -1;

    Version(VersionSet vset) {
        this.vset = vset;
        this.next = this;
        this.prev = this;
        for (int i = 0; i < DbConfig.NUM_LEVELS; i++) files[i] = new ArrayList<>();
    }

    public void ref()   { refs++; }
    public void unref() { if (--refs <= 0) vset.removeVersion(this); }

    public byte[] get(ReadOptions opts, LookupKey key, GetStats stats) throws IOException {
        Slice ikey = key.internalKey();
        Slice ukey = key.userKey();
        InternalKeyComparator icmp = vset.internalKeyComparator();

        // Level-0：可能重叠，按文件号降序
        List<FileMetaData> level0Candidates = new ArrayList<>();
        for (FileMetaData f : files[0]) {
            if (icmp.userComparator().compare(ukey, InternalKey.extractUserKey(f.smallest.getBytes())) >= 0 &&
                icmp.userComparator().compare(ukey, InternalKey.extractUserKey(f.largest.getBytes()))  <= 0) {
                level0Candidates.add(f);
            }
        }
        level0Candidates.sort((a, b) -> Long.compare(b.number, a.number));

        for (FileMetaData f : level0Candidates) {
            byte[] val = seekInFile(opts, f, ikey, stats);
            if (val != null) return val;
        }

        // Level-1+：不重叠，二分查找
        for (int level = 1; level < DbConfig.NUM_LEVELS; level++) {
            List<FileMetaData> levelFiles = files[level];
            if (levelFiles.isEmpty()) continue;
            int idx = findFile(icmp, levelFiles, ikey);
            if (idx < levelFiles.size()) {
                FileMetaData f = levelFiles.get(idx);
                if (icmp.userComparator().compare(ukey, InternalKey.extractUserKey(f.smallest.getBytes())) >= 0) {
                    byte[] val = seekInFile(opts, f, ikey, stats);
                    if (val != null) return val;
                }
            }
        }
        return null;
    }

    private byte[] seekInFile(ReadOptions opts, FileMetaData f, Slice ikey, GetStats stats)
            throws IOException {
        byte[][] result = {null};
        vset.tableCache().get(opts, f.number, f.fileSize, ikey, new Table.GetCallback() {
            public void got(Slice key, Slice value) { result[0] = value.getBytes(); }
            public void notFound() {}
        });
        if (stats != null && stats.seekFile == null) {
            stats.seekFile = f;
            stats.seekFileLevel = 0;
        }
        return result[0];
    }

    public static int findFile(InternalKeyComparator icmp, List<FileMetaData> files, Slice ikey) {
        int left = 0, right = files.size();
        while (left < right) {
            int mid = (left + right) / 2;
            if (icmp.compare(files.get(mid).largest, ikey) < 0) left = mid + 1;
            else right = mid;
        }
        return left;
    }

    public boolean updateStats(GetStats stats) {
        if (stats != null && stats.seekFile != null) {
            stats.seekFile.allowedSeeks--;
            if (stats.seekFile.allowedSeeks <= 0 && fileToCompact == null) {
                fileToCompact = stats.seekFile;
                fileToCompactLevel = stats.seekFileLevel;
                return true;
            }
        }
        return false;
    }

    public int pickLevelForMemTableOutput(Slice smallestKey, Slice largestKey) {
        int level = 0;
        InternalKeyComparator icmp = vset.internalKeyComparator();
        while (level < DbConfig.MAX_MEM_COMPACT_LEVEL) {
            if (overlapInLevel(level + 1, smallestKey, largestKey, icmp)) break;
            if (level + 2 < DbConfig.NUM_LEVELS) {
                long overlappingBytes = overlappingBytesInLevel(level + 2, smallestKey, largestKey, icmp);
                if (overlappingBytes > DbConfig.MAX_GRANDPARENT_OVERLAP_BYTES) break;
            }
            level++;
        }
        return level;
    }

    private boolean overlapInLevel(int level, Slice small, Slice large, InternalKeyComparator icmp) {
        for (FileMetaData f : files[level]) {
            if (icmp.userComparator().compare(InternalKey.extractUserKey(f.largest.getBytes()), small) < 0) continue;
            if (icmp.userComparator().compare(InternalKey.extractUserKey(f.smallest.getBytes()), large) > 0) continue;
            return true;
        }
        return false;
    }

    private long overlappingBytesInLevel(int level, Slice small, Slice large, InternalKeyComparator icmp) {
        long total = 0;
        for (FileMetaData f : files[level]) {
            if (icmp.userComparator().compare(InternalKey.extractUserKey(f.largest.getBytes()), small) < 0) continue;
            if (icmp.userComparator().compare(InternalKey.extractUserKey(f.smallest.getBytes()), large) > 0) continue;
            total += f.fileSize;
        }
        return total;
    }

    public void addIterators(ReadOptions opts, List<com.leveldb.iterator.Iterator> iters) throws IOException {
        for (FileMetaData f : files[0]) {
            iters.add(vset.tableCache().newIterator(opts, f.number, f.fileSize));
        }
        for (int level = 1; level < DbConfig.NUM_LEVELS; level++) {
            if (!files[level].isEmpty()) {
                iters.add(new LevelFileIterator(level, files[level], vset, opts));
            }
        }
    }

    public static class GetStats {
        public FileMetaData seekFile;
        public int seekFileLevel;
    }
}
