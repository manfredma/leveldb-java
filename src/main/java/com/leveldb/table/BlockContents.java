package com.leveldb.table;

import com.leveldb.common.Slice;

/**
 * 从文件读取的块内容载体。对应 C++ table/format.h BlockContents。
 */
public class BlockContents {
    public final Slice data;
    public final boolean cachable;
    public final boolean heapAllocated;

    public BlockContents(Slice data, boolean cachable, boolean heapAllocated) {
        this.data = data;
        this.cachable = cachable;
        this.heapAllocated = heapAllocated;
    }
}
