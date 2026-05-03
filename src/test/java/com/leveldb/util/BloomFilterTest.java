package com.leveldb.util;

import com.leveldb.common.Slice;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;

public class BloomFilterTest {
    private final BloomFilter filter = new BloomFilter(10);

    @Test
    public void testEmptyFilter() {
        byte[] f = filter.createFilter(new ArrayList<>());
        assertTrue(f.length >= 1);
        assertFalse(filter.keyMayMatch(Slice.from("anything"), f));
    }

    @Test
    public void testInsertedKeysMustMatch() {
        List<Slice> keys = new ArrayList<>();
        for (int i = 0; i < 10; i++) keys.add(Slice.from("key" + i));
        byte[] f = filter.createFilter(keys);
        for (Slice key : keys) {
            assertTrue("key should match: " + key, filter.keyMayMatch(key, f));
        }
    }

    @Test
    public void testFalsePositiveRate() {
        List<Slice> keys = new ArrayList<>();
        for (int i = 0; i < 1000; i++) keys.add(Slice.from("insert-" + i));
        byte[] f = filter.createFilter(keys);
        int falsePositives = 0;
        for (int i = 0; i < 10000; i++) {
            if (filter.keyMayMatch(Slice.from("query-" + i), f)) falsePositives++;
        }
        assertTrue("false positive rate too high: " + falsePositives, falsePositives < 200);
    }

    @Test
    public void testKValueStoredInLastByte() {
        List<Slice> keys = new ArrayList<>();
        keys.add(Slice.from("k"));
        byte[] f = filter.createFilter(keys);
        int k = f[f.length - 1] & 0xFF;
        assertTrue("k should be in [1,30]: " + k, k >= 1 && k <= 30);
    }

    @Test
    public void testDifferentBitsPerKey() {
        BloomFilter f5 = new BloomFilter(5);
        BloomFilter f20 = new BloomFilter(20);
        List<Slice> keys = new ArrayList<>();
        for (int i = 0; i < 100; i++) keys.add(Slice.from("k" + i));
        byte[] filter5 = f5.createFilter(keys);
        byte[] filter20 = f20.createFilter(keys);
        assertTrue(filter20.length > filter5.length);
    }

    @Test
    public void testVaryKeySizes() {
        List<Slice> keys = new ArrayList<>();
        keys.add(Slice.from(""));           // 空串
        keys.add(Slice.from("a"));          // 1字节
        keys.add(new Slice(new byte[100])); // 100字节
        byte[] f = filter.createFilter(keys);
        for (Slice key : keys) {
            assertTrue("key of length " + key.length() + " should match", filter.keyMayMatch(key, f));
        }
    }
}
