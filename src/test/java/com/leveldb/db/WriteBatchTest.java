package com.leveldb.db;

import com.leveldb.common.Slice;
import com.leveldb.common.InternalKeyComparator;
import com.leveldb.common.BytewiseComparator;
import com.leveldb.common.ValueType;
import com.leveldb.common.LookupKey;
import com.leveldb.memtable.MemTable;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class WriteBatchTest {
    @Test
    public void testPutAndIterate() {
        WriteBatch batch = new WriteBatch();
        batch.put(Slice.from("k1"), Slice.from("v1"));
        batch.put(Slice.from("k2"), Slice.from("v2"));
        assertEquals(2, batch.count());
        List<String> keys = new ArrayList<>();
        batch.forEach(new WriteBatch.Handler() {
            public void put(Slice key, Slice value) { keys.add(key.toString()); }
            public void delete(Slice key) {}
        });
        assertEquals(Arrays.asList("k1","k2"), keys);
    }

    @Test
    public void testDeleteAndIterate() {
        WriteBatch batch = new WriteBatch();
        batch.delete(Slice.from("k1"));
        assertEquals(1, batch.count());
        boolean[] deleted = {false};
        batch.forEach(new WriteBatch.Handler() {
            public void put(Slice key, Slice value) {}
            public void delete(Slice key) { deleted[0] = true; }
        });
        assertTrue(deleted[0]);
    }

    @Test
    public void testSequenceNumberPersistence() {
        WriteBatch batch = new WriteBatch();
        batch.setSequenceNumber(12345L);
        assertEquals(12345L, batch.getSequenceNumber());
    }

    @Test
    public void testEncodeDecodeRoundtrip() {
        WriteBatch batch = new WriteBatch();
        batch.setSequenceNumber(99L);
        batch.put(Slice.from("a"), Slice.from("1"));
        batch.delete(Slice.from("b"));
        byte[] encoded = batch.getContents();
        WriteBatch decoded = WriteBatch.fromBytes(encoded);
        assertEquals(99L, decoded.getSequenceNumber());
        assertEquals(2, decoded.count());
        List<String> ops = new ArrayList<>();
        decoded.forEach(new WriteBatch.Handler() {
            public void put(Slice key, Slice value) { ops.add("PUT:" + key + "=" + value); }
            public void delete(Slice key) { ops.add("DEL:" + key); }
        });
        assertEquals("PUT:a=1", ops.get(0));
        assertEquals("DEL:b", ops.get(1));
    }

    @Test
    public void testClearBatch() {
        WriteBatch batch = new WriteBatch();
        batch.put(Slice.from("k"), Slice.from("v"));
        batch.clear();
        assertEquals(0, batch.count());
    }

    @Test
    public void testApplyToMemTable() {
        // 验证 batch apply 到 MemTable 后读取正确
        WriteBatch batch = new WriteBatch();
        batch.setSequenceNumber(1L);
        batch.put(Slice.from("key1"), Slice.from("val1"));
        batch.put(Slice.from("key2"), Slice.from("val2"));
        batch.delete(Slice.from("key3"));

        MemTable mem = new MemTable(
            new InternalKeyComparator(BytewiseComparator.INSTANCE));
        long[] seq = {1L};
        batch.forEach(new WriteBatch.Handler() {
            public void put(Slice key, Slice value) {
                mem.add(seq[0]++, ValueType.VALUE, key, value);
            }
            public void delete(Slice key) {
                mem.add(seq[0]++, ValueType.DELETION, key, Slice.from(""));
            }
        });

        MemTable.GetResult r1 = mem.get(new LookupKey(Slice.from("key1"), 10L));
        assertNotNull(r1); assertEquals("val1", r1.value.toString());

        MemTable.GetResult r2 = mem.get(new LookupKey(Slice.from("key2"), 10L));
        assertNotNull(r2); assertEquals("val2", r2.value.toString());

        MemTable.GetResult r3 = mem.get(new LookupKey(Slice.from("key3"), 10L));
        assertNotNull(r3); assertTrue(r3.deleted);
    }

    @Test
    public void testLargeBatch() {
        // 写入 1000 条记录的 batch，验证 count 和迭代正确
        WriteBatch batch = new WriteBatch();
        batch.setSequenceNumber(1L);
        int N = 1000;
        for (int i = 0; i < N; i++) {
            batch.put(Slice.from("k" + i), Slice.from("v" + i));
        }
        assertEquals(N, batch.count());

        // 验证解码
        WriteBatch decoded = WriteBatch.fromBytes(batch.getContents());
        assertEquals(N, decoded.count());
        int[] count = {0};
        decoded.forEach(new WriteBatch.Handler() {
            public void put(Slice key, Slice value) { count[0]++; }
            public void delete(Slice key) { count[0]++; }
        });
        assertEquals(N, count[0]);
    }

    @Test
    public void testMixedPutDeleteBatch() {
        // put 和 delete 混合，验证顺序保留
        WriteBatch batch = new WriteBatch();
        batch.put(Slice.from("a"), Slice.from("1"));
        batch.delete(Slice.from("b"));
        batch.put(Slice.from("c"), Slice.from("3"));
        batch.delete(Slice.from("d"));
        assertEquals(4, batch.count());

        List<String> ops = new ArrayList<>();
        batch.forEach(new WriteBatch.Handler() {
            public void put(Slice key, Slice value)  { ops.add("PUT:" + key); }
            public void delete(Slice key)             { ops.add("DEL:" + key); }
        });
        assertEquals(Arrays.asList("PUT:a", "DEL:b", "PUT:c", "DEL:d"), ops);
    }
}
