package com.leveldb.table;

import com.leveldb.common.Slice;
import com.leveldb.util.BloomFilter;
import com.leveldb.util.Coding;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 构建 Filter Block，存储多个 Bloom Filter 数据。
 * 对应 C++ table/filter_block.h + filter_block.cc。
 */
public class FilterBlockBuilder {
    private static final int FILTER_BASE_LG = 11;
    private static final int FILTER_BASE    = 1 << FILTER_BASE_LG;

    private final BloomFilter policy;
    private final ByteArrayOutputStream result = new ByteArrayOutputStream();
    private final List<Integer> filterOffsets = new ArrayList<>();
    private final List<Slice> keys = new ArrayList<>();

    public FilterBlockBuilder(BloomFilter policy) { this.policy = policy; }

    public void startBlock(long blockOffset) {
        long filterIndex = blockOffset / FILTER_BASE;
        while (filterIndex > filterOffsets.size()) {
            generateFilter();
        }
    }

    public void addKey(Slice key) { keys.add(key); }

    public Slice finish() {
        if (!keys.isEmpty()) generateFilter();
        int arrayOffset = result.size();
        byte[] buf4 = new byte[4];
        for (int offset : filterOffsets) {
            Coding.encodeFixed32(buf4, 0, offset);
            try { result.write(buf4); } catch (IOException e) { throw new RuntimeException(e); }
        }
        Coding.encodeFixed32(buf4, 0, arrayOffset);
        try {
            result.write(buf4);
            result.write(FILTER_BASE_LG);
        } catch (IOException e) { throw new RuntimeException(e); }
        return new Slice(result.toByteArray());
    }

    private void generateFilter() {
        filterOffsets.add(result.size());
        if (!keys.isEmpty()) {
            byte[] filterData = policy.createFilter(keys);
            try { result.write(filterData); } catch (IOException e) { throw new RuntimeException(e); }
            keys.clear();
        }
    }
}
