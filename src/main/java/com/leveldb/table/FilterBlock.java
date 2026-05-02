package com.leveldb.table;

import com.leveldb.common.Slice;
import com.leveldb.util.BloomFilter;
import com.leveldb.util.Coding;

/**
 * 读取 Filter Block，支持按 block offset 查询 key 是否存在。
 * 对应 C++ table/filter_block.cc FilterBlockReader。
 */
public class FilterBlock {
    private final BloomFilter policy;
    private final byte[] data;
    private final int offsetsStart;
    private final int numFilters;
    private final int baseLg;

    public FilterBlock(BloomFilter policy, Slice contents) {
        this.policy = policy;
        this.data = contents.getBytes();
        if (data.length < 5) {
            this.offsetsStart = 0;
            this.numFilters = 0;
            this.baseLg = 11;
            return;
        }
        this.baseLg = data[data.length - 1] & 0xFF;
        this.offsetsStart = Coding.decodeFixed32(data, data.length - 5);
        int numBytes = data.length - 5 - offsetsStart;
        this.numFilters = numBytes > 0 ? numBytes / 4 : 0;
    }

    public boolean keyMayMatch(long blockOffset, Slice key) {
        if (numFilters == 0) return true;
        int filterIndex = (int)(blockOffset >> baseLg);
        if (filterIndex >= numFilters) return true;
        int filterStart = Coding.decodeFixed32(data, offsetsStart + filterIndex * 4);
        int filterEnd;
        if (filterIndex + 1 < numFilters) {
            filterEnd = Coding.decodeFixed32(data, offsetsStart + (filterIndex + 1) * 4);
        } else {
            filterEnd = offsetsStart;
        }
        if (filterStart > filterEnd || filterEnd > offsetsStart) return true;
        byte[] filter = new byte[filterEnd - filterStart];
        System.arraycopy(data, filterStart, filter, 0, filter.length);
        return policy.keyMayMatch(key, filter);
    }
}
