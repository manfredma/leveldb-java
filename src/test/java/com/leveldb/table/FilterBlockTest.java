package com.leveldb.table;

import com.leveldb.common.Slice;
import com.leveldb.util.BloomFilter;
import org.junit.Test;
import static org.junit.Assert.*;

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
}
