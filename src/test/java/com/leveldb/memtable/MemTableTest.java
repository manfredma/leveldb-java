package com.leveldb.memtable;

import com.leveldb.common.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class MemTableTest {
    private MemTable newMemTable() {
        return new MemTable(new InternalKeyComparator(BytewiseComparator.INSTANCE));
    }

    @Test
    public void testAddAndGetValue() {
        MemTable mem = newMemTable();
        mem.add(1L, ValueType.VALUE, Slice.from("key"), Slice.from("value"));
        MemTable.GetResult r = mem.get(new LookupKey(Slice.from("key"), 2L));
        assertNotNull(r);
        assertTrue(r.found);
        assertFalse(r.deleted);
        assertEquals("value", r.value.toString());
    }

    @Test
    public void testGetNonExistentKey() {
        MemTable mem = newMemTable();
        MemTable.GetResult r = mem.get(new LookupKey(Slice.from("missing"), 1L));
        assertNull(r);
    }

    @Test
    public void testDeleteTombstone() {
        MemTable mem = newMemTable();
        mem.add(1L, ValueType.VALUE, Slice.from("k"), Slice.from("v"));
        mem.add(2L, ValueType.DELETION, Slice.from("k"), Slice.from(""));
        MemTable.GetResult r = mem.get(new LookupKey(Slice.from("k"), 3L));
        assertNotNull(r);
        assertTrue(r.found);
        assertTrue(r.deleted);
    }

    @Test
    public void testSequenceNumberIsolation() {
        MemTable mem = newMemTable();
        mem.add(1L, ValueType.VALUE, Slice.from("k"), Slice.from("v1"));
        mem.add(2L, ValueType.VALUE, Slice.from("k"), Slice.from("v2"));
        MemTable.GetResult r1 = mem.get(new LookupKey(Slice.from("k"), 1L));
        assertEquals("v1", r1.value.toString());
        MemTable.GetResult r2 = mem.get(new LookupKey(Slice.from("k"), 2L));
        assertEquals("v2", r2.value.toString());
    }

    @Test
    public void testMemoryUsageIncreases() {
        MemTable mem = newMemTable();
        long before = mem.approximateMemoryUsage();
        mem.add(1L, ValueType.VALUE, Slice.from("key"), Slice.from("value123456789"));
        long after = mem.approximateMemoryUsage();
        assertTrue("memory should increase after add", after > before);
    }

    @Test
    public void testIteratorForwardScan() {
        MemTable mem = newMemTable();
        mem.add(3L, ValueType.VALUE, Slice.from("c"), Slice.from("vc"));
        mem.add(1L, ValueType.VALUE, Slice.from("a"), Slice.from("va"));
        mem.add(2L, ValueType.VALUE, Slice.from("b"), Slice.from("vb"));

        com.leveldb.iterator.Iterator it = mem.newIterator();
        it.seekToFirst();
        assertTrue(it.valid());
        assertEquals("a", InternalKey.extractUserKey(it.key().getBytes()).toString());
        it.next();
        assertEquals("b", InternalKey.extractUserKey(it.key().getBytes()).toString());
        it.next();
        assertEquals("c", InternalKey.extractUserKey(it.key().getBytes()).toString());
        it.next();
        assertFalse(it.valid());
    }

    @Test
    public void testMultipleVersionsSameKey() {
        MemTable mem = newMemTable();
        mem.add(1L, ValueType.VALUE, Slice.from("k"), Slice.from("v1"));
        mem.add(2L, ValueType.VALUE, Slice.from("k"), Slice.from("v2"));
        mem.add(3L, ValueType.VALUE, Slice.from("k"), Slice.from("v3"));
        com.leveldb.iterator.Iterator it = mem.newIterator();
        it.seekToFirst();
        assertTrue(it.valid());
        assertEquals(3L, InternalKey.extractSequenceNumber(it.key().getBytes()));
        it.next();
        assertEquals(2L, InternalKey.extractSequenceNumber(it.key().getBytes()));
    }
}
