package com.leveldb.iterator;

import com.leveldb.common.Slice;
import com.leveldb.common.Status;

/**
 * LevelDB 迭代器接口。对应 C++ leveldb::Iterator（include/leveldb/iterator.h）。
 * 所有有序数据结构（MemTable, SSTable, MergingIterator）均实现此接口。
 */
public interface Iterator extends AutoCloseable {
    void seekToFirst();
    void seekToLast();
    void seek(Slice target);
    void next();
    void prev();
    boolean valid();
    Slice key();
    Slice value();
    Status status();
    @Override void close();
}
