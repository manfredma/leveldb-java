package com.leveldb.common;

/**
 * 键比较器接口。对应 C++ leveldb::Comparator（include/leveldb/comparator.h）。
 */
public interface Comparator {
    String name();
    int compare(Slice a, Slice b);
    Slice findShortestSeparator(Slice start, Slice limit);
    Slice findShortSuccessor(Slice key);
}
