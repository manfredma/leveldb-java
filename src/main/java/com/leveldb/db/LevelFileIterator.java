package com.leveldb.db;

import com.leveldb.common.*;
import com.leveldb.iterator.*;
import com.leveldb.util.Coding;
import java.util.List;

/**
 * Level >= 1 层的文件迭代器，将多个不重叠 SSTable 串联为一个迭代器。
 */
public class LevelFileIterator extends TwoLevelIterator {
    public LevelFileIterator(int level, List<FileMetaData> files,
                              VersionSet vset, ReadOptions opts) {
        super(new FileIndexIterator(files, vset.internalKeyComparator()),
              (readOpts, indexValue) -> {
                  long number = Coding.decodeFixed64(indexValue.getBytes(), 0);
                  long size   = Coding.decodeFixed64(indexValue.getBytes(), 8);
                  return vset.tableCache().newIterator(readOpts, number, size);
              }, opts);
    }

    private static class FileIndexIterator implements com.leveldb.iterator.Iterator {
        private final List<FileMetaData> files;
        private final InternalKeyComparator icmp;
        private int idx = -1;

        FileIndexIterator(List<FileMetaData> files, InternalKeyComparator icmp) {
            this.files = files; this.icmp = icmp;
        }
        public void seekToFirst() { idx = files.isEmpty() ? -1 : 0; }
        public void seekToLast()  { idx = files.isEmpty() ? -1 : files.size() - 1; }
        public void seek(Slice t) {
            idx = Version.findFile(icmp, files, t);
            if (idx >= files.size()) idx = -1;
        }
        public void next() { idx = (idx + 1 < files.size()) ? idx + 1 : -1; }
        public void prev() { idx = (idx > 0) ? idx - 1 : -1; }
        public boolean valid() { return idx >= 0 && idx < files.size(); }
        public Slice key() { return files.get(idx).largest; }
        public Slice value() {
            byte[] buf = new byte[16];
            Coding.encodeFixed64(buf, 0, files.get(idx).number);
            Coding.encodeFixed64(buf, 8, files.get(idx).fileSize);
            return new Slice(buf);
        }
        public com.leveldb.common.Status status() { return com.leveldb.common.Status.ok(); }
        public void close() {}
    }
}
