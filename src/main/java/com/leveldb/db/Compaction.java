package com.leveldb.db;

import com.leveldb.common.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 描述一次 compaction 操作。对应 C++ db/version_set.h Compaction。
 */
public class Compaction {
    private final int level;
    private final long maxOutputFileSize;
    final Version inputVersion;
    final VersionEdit edit = new VersionEdit();

    @SuppressWarnings("unchecked")
    final List<FileMetaData>[] inputs = new List[]{new ArrayList<>(), new ArrayList<>()};
    List<FileMetaData> grandparents = new ArrayList<>();
    int grandparentIndex;
    boolean seenKey;
    long overlappedBytes;

    Compaction(int level, long maxOutputFileSize, Version inputVersion) {
        this.level = level;
        this.maxOutputFileSize = maxOutputFileSize;
        this.inputVersion = inputVersion;
    }

    public int level()              { return level; }
    public VersionEdit edit()       { return edit; }
    public int numInputFiles(int w) { return inputs[w].size(); }
    public FileMetaData input(int which, int i) { return inputs[which].get(i); }
    public long maxOutputFileSize() { return maxOutputFileSize; }

    public boolean isTrivialMove() {
        return inputs[0].size() == 1 && inputs[1].isEmpty() && grandparents.isEmpty();
    }

    public void addInputDeletions() {
        for (int w = 0; w < 2; w++) {
            for (FileMetaData f : inputs[w]) {
                edit.removeFile(level + w, f.number);
            }
        }
    }

    public boolean isBaseLevelForKey(Slice userKey) {
        InternalKeyComparator icmp = inputVersion.vset.internalKeyComparator();
        for (int lvl = level + 2; lvl < DbConfig.NUM_LEVELS; lvl++) {
            for (FileMetaData f : inputVersion.files[lvl]) {
                if (icmp.userComparator().compare(
                        InternalKey.extractUserKey(f.largest.getBytes()), userKey) >= 0) {
                    if (icmp.userComparator().compare(
                            InternalKey.extractUserKey(f.smallest.getBytes()), userKey) <= 0) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public boolean shouldStopBefore(Slice internalKey) {
        InternalKeyComparator icmp = inputVersion.vset.internalKeyComparator();
        while (grandparentIndex < grandparents.size() &&
               icmp.compare(internalKey, grandparents.get(grandparentIndex).largest) > 0) {
            if (seenKey) overlappedBytes += grandparents.get(grandparentIndex).fileSize;
            grandparentIndex++;
        }
        seenKey = true;
        if (overlappedBytes > DbConfig.MAX_GRANDPARENT_OVERLAP_BYTES) {
            overlappedBytes = 0;
            return true;
        }
        return false;
    }
}
