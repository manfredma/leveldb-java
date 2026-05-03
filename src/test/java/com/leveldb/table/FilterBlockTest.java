package com.leveldb.table;

import com.leveldb.common.Slice;
import com.leveldb.util.BloomFilter;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;

public class FilterBlockTest {
    private final BloomFilter policy = new BloomFilter(10);

    @Test
    public void testBuildAndQuery() {
        FilterBlockBuilder builder = new FilterBlockBuilder(policy);
        builder.startBlock(0);
        builder.addKey(Slice.from("foo"));
        builder.addKey(Slice.from("bar"));
        Slice filterData = builder.finish();

        FilterBlock filter = new FilterBlock(policy, filterData);
        assertTrue(filter.keyMayMatch(0, Slice.from("foo")));
        assertTrue(filter.keyMayMatch(0, Slice.from("bar")));
        assertFalse(filter.keyMayMatch(0, Slice.from("missing_key_xyz")));
    }

    @Test
    public void testMultipleBlocks() {
        FilterBlockBuilder builder = new FilterBlockBuilder(policy);
        builder.startBlock(0);
        builder.addKey(Slice.from("key1"));
        builder.startBlock(2048);
        builder.addKey(Slice.from("key2"));
        Slice filterData = builder.finish();

        FilterBlock filter = new FilterBlock(policy, filterData);
        assertTrue(filter.keyMayMatch(0, Slice.from("key1")));
        assertTrue(filter.keyMayMatch(2048, Slice.from("key2")));
    }

    @Test
    public void testEmptyFilterMayMatchAnything() {
        // 空 filter（无任何 key）：generateFilter 写入零长度 filter，
        // BloomFilter.keyMayMatch 对长度 < 2 的 filter 返回 false
        FilterBlockBuilder builder = new FilterBlockBuilder(policy);
        builder.startBlock(0);
        // 不添加任何 key
        Slice filterData = builder.finish();
        FilterBlock filter = new FilterBlock(policy, filterData);
        // 空 filter 无法确认 key 不存在，保守返回 true（避免漏报）
        assertTrue(filter.keyMayMatch(0, Slice.from("any_key")));
    }

    @Test
    public void testManyKeysInOneFilter() {
        // 大量 key 写入同一个 filter，全部应命中
        FilterBlockBuilder builder = new FilterBlockBuilder(policy);
        builder.startBlock(0);
        List<Slice> keys = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            Slice key = Slice.from("key-" + i);
            keys.add(key);
            builder.addKey(key);
        }
        Slice filterData = builder.finish();
        FilterBlock filter = new FilterBlock(policy, filterData);
        for (Slice key : keys) {
            assertTrue("key should match: " + key, filter.keyMayMatch(0, key));
        }
    }

    @Test
    public void testFilterBlockOffsetMapping() {
        // 不同 blockOffset 应映射到各自的 filter
        FilterBlockBuilder builder = new FilterBlockBuilder(policy);
        // block 0: offset 0
        builder.startBlock(0);
        builder.addKey(Slice.from("block0-key"));
        // block 1: offset 2048（第二个 filter 区间）
        builder.startBlock(2048);
        builder.addKey(Slice.from("block1-key"));
        // block 2: offset 4096（第三个 filter 区间）
        builder.startBlock(4096);
        builder.addKey(Slice.from("block2-key"));
        Slice filterData = builder.finish();
        FilterBlock filter = new FilterBlock(policy, filterData);

        assertTrue(filter.keyMayMatch(0,    Slice.from("block0-key")));
        assertTrue(filter.keyMayMatch(2048, Slice.from("block1-key")));
        assertTrue(filter.keyMayMatch(4096, Slice.from("block2-key")));
        // block0 的 filter 不包含 block1 的 key
        assertFalse(filter.keyMayMatch(0,   Slice.from("block1-key")));
    }

    @Test
    public void testFilterDataRoundtrip() {
        // 构建 filter 数据并验证字节结构完整性
        FilterBlockBuilder builder = new FilterBlockBuilder(policy);
        builder.startBlock(0);
        builder.addKey(Slice.from("alpha"));
        builder.addKey(Slice.from("beta"));
        Slice filterData = builder.finish();
        // filter 数据末尾应是 base_lg = 11
        assertEquals(11, filterData.getByte(filterData.length() - 1) & 0xFF);
        // 长度应 > 0
        assertTrue(filterData.length() > 5);
    }
}
