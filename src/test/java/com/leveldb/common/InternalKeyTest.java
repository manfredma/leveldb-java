package com.leveldb.common;

import org.junit.Test;
import static org.junit.Assert.*;

public class InternalKeyTest {
    @Test
    public void testEncodeAndExtractUserKey() {
        Slice userKey = Slice.from("mykey");
        byte[] encoded = InternalKey.encode(userKey, 42L, ValueType.VALUE);
        assertEquals(userKey.length() + 8, encoded.length);
        Slice extracted = InternalKey.extractUserKey(encoded);
        assertEquals(userKey, extracted);
    }

    @Test
    public void testExtractSequenceNumber() {
        Slice userKey = Slice.from("k");
        byte[] encoded = InternalKey.encode(userKey, 12345L, ValueType.VALUE);
        assertEquals(12345L, InternalKey.extractSequenceNumber(encoded));
    }

    @Test
    public void testExtractValueType() {
        Slice userKey = Slice.from("k");
        byte[] del = InternalKey.encode(userKey, 1L, ValueType.DELETION);
        byte[] val = InternalKey.encode(userKey, 1L, ValueType.VALUE);
        assertEquals(ValueType.DELETION, InternalKey.extractValueType(del));
        assertEquals(ValueType.VALUE, InternalKey.extractValueType(val));
    }

    @Test
    public void testComparatorOrderingByUserKey() {
        InternalKeyComparator cmp = new InternalKeyComparator(BytewiseComparator.INSTANCE);
        byte[] a = InternalKey.encode(Slice.from("apple"), 1L, ValueType.VALUE);
        byte[] b = InternalKey.encode(Slice.from("banana"), 1L, ValueType.VALUE);
        Slice sa = new Slice(a), sb = new Slice(b);
        assertTrue(cmp.compare(sa, sb) < 0);
    }

    @Test
    public void testComparatorOrderingBySeq() {
        InternalKeyComparator cmp = new InternalKeyComparator(BytewiseComparator.INSTANCE);
        byte[] old = InternalKey.encode(Slice.from("k"), 1L, ValueType.VALUE);
        byte[] newer = InternalKey.encode(Slice.from("k"), 2L, ValueType.VALUE);
        Slice sOld = new Slice(old), sNewer = new Slice(newer);
        assertTrue(cmp.compare(sNewer, sOld) < 0);
    }

    @Test
    public void testLookupKeyViews() {
        Slice userKey = Slice.from("hello");
        LookupKey lk = new LookupKey(userKey, 100L);
        assertEquals(userKey, lk.userKey());
        assertEquals(userKey.length() + 8, lk.internalKey().length());
        assertTrue(lk.memtableKey().length() > lk.internalKey().length());
    }
}
