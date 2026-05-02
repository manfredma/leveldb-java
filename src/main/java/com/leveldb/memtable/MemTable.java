package com.leveldb.memtable;

import com.leveldb.common.*;
import com.leveldb.iterator.Iterator;
import com.leveldb.util.Coding;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 SkipList 的内存表。
 * 每条 entry 的编码格式（对应 C++ memtable.cc）：
 *   klength(varint32) + user_key + tag(8字节LE) + vlength(varint32) + value
 * 其中 klength = len(user_key) + 8，tag = (seq << 8) | type.code
 */
public class MemTable {
    private final SkipList<Slice> table;
    private final InternalKeyComparator comparator;
    private final AtomicLong memoryUsage = new AtomicLong(0);

    public MemTable(InternalKeyComparator comparator) {
        this.comparator = comparator;
        this.table = new SkipList<>(new SliceComparator(comparator));
    }

    public void add(long sequenceNumber, ValueType type, Slice key, Slice value) {
        byte[] keyBytes = key.getBytes();
        byte[] valBytes = value.getBytes();
        int klength = keyBytes.length + 8;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            Coding.encodeVarint32(out, klength);
            out.write(keyBytes);
            long tag = ((long) sequenceNumber << 8) | type.code;
            for (int i = 0; i < 8; i++) out.write((int)(tag >>> (i * 8)) & 0xFF);
            Coding.encodeVarint32(out, valBytes.length);
            out.write(valBytes);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        byte[] entry = out.toByteArray();
        table.insert(new Slice(entry));
        memoryUsage.addAndGet(entry.length + 50);
    }

    public GetResult get(LookupKey lookupKey) {
        Slice memKey = lookupKey.memtableKey();
        SkipList<Slice>.SkipListIterator it = table.iterator();
        it.seek(memKey);
        if (!it.valid()) return null;

        Slice entry = it.key();
        byte[] entryBytes = entry.getBytes();
        int[] offset = {0};
        int klength = Coding.decodeVarint32(entryBytes, offset);
        int userKeyLen = klength - 8;
        Slice foundUserKey = new Slice(entryBytes, offset[0], userKeyLen);

        if (comparator.userComparator().compare(foundUserKey, lookupKey.userKey()) != 0) {
            return null;
        }

        int tagOffset = offset[0] + userKeyLen;
        long tag = 0;
        for (int i = 0; i < 8; i++) tag |= (long)(entryBytes[tagOffset + i] & 0xFF) << (i * 8);
        ValueType vtype = ValueType.fromCode((int)(tag & 0xFF));

        if (vtype == ValueType.DELETION) {
            return new GetResult(true, null, true);
        }

        int[] vOffset = {tagOffset + 8};
        int vlen = Coding.decodeVarint32(entryBytes, vOffset);
        Slice value = new Slice(entryBytes, vOffset[0], vlen);
        return new GetResult(true, value, false);
    }

    public long approximateMemoryUsage() { return memoryUsage.get(); }

    public Iterator newIterator() {
        return new MemTableIterator(table.iterator());
    }

    public static class GetResult {
        public final boolean found;
        public final Slice value;
        public final boolean deleted;
        GetResult(boolean found, Slice value, boolean deleted) {
            this.found = found; this.value = value; this.deleted = deleted;
        }
    }

    private static class SliceComparator implements Comparator {
        private final InternalKeyComparator ikc;
        SliceComparator(InternalKeyComparator ikc) { this.ikc = ikc; }

        @Override public String name() { return ikc.name(); }

        @Override
        public int compare(Slice a, Slice b) {
            Slice ia = extractInternalKey(a);
            Slice ib = extractInternalKey(b);
            return ikc.compare(ia, ib);
        }

        private Slice extractInternalKey(Slice entry) {
            byte[] bytes = entry.getBytes();
            int[] offset = {0};
            int klength = Coding.decodeVarint32(bytes, offset);
            return new Slice(bytes, offset[0], klength);
        }

        @Override public Slice findShortestSeparator(Slice s, Slice l) { return s; }
        @Override public Slice findShortSuccessor(Slice k) { return k; }
    }

    private static class MemTableIterator implements Iterator {
        private final SkipList<Slice>.SkipListIterator it;
        private Slice curKey, curValue;

        MemTableIterator(SkipList<Slice>.SkipListIterator it) { this.it = it; }

        private void decode() {
            if (!it.valid()) { curKey = curValue = null; return; }
            byte[] bytes = it.key().getBytes();
            int[] offset = {0};
            int klength = Coding.decodeVarint32(bytes, offset);
            curKey = new Slice(bytes, offset[0], klength);
            int[] vOffset = {offset[0] + klength};
            int vlen = Coding.decodeVarint32(bytes, vOffset);
            curValue = new Slice(bytes, vOffset[0], vlen);
        }

        @Override public void seekToFirst() { it.seekToFirst(); decode(); }
        @Override public void seekToLast()  { it.seekToLast();  decode(); }
        @Override public void seek(Slice t) { it.seek(t);        decode(); }
        @Override public void next()        { it.next();          decode(); }
        @Override public void prev()        { it.prev();          decode(); }
        @Override public boolean valid()    { return it.valid(); }
        @Override public Slice key()        { return curKey; }
        @Override public Slice value()      { return curValue; }
        @Override public Status status()    { return Status.ok(); }
        @Override public void close()       {}
    }
}
