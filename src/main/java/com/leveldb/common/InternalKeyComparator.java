package com.leveldb.common;

/**
 * InternalKey 的比较器。
 * 规则：先按 user_key 升序；user_key 相同时按 sequence_number 降序；
 * 对应 C++ InternalKeyComparator（db/dbformat.cc）。
 */
public final class InternalKeyComparator implements Comparator {
    private final Comparator userComparator;

    public InternalKeyComparator(Comparator userComparator) {
        this.userComparator = userComparator;
    }

    public Comparator userComparator() { return userComparator; }

    @Override
    public String name() { return "leveldb.InternalKeyComparator"; }

    @Override
    public int compare(Slice a, Slice b) {
        Slice ua = new Slice(a.getBytes(), 0, a.length() - 8);
        Slice ub = new Slice(b.getBytes(), 0, b.length() - 8);
        int r = userComparator.compare(ua, ub);
        if (r != 0) return r;
        long tagA = readTag(a);
        long tagB = readTag(b);
        if (tagA > tagB) return -1;
        if (tagA < tagB) return 1;
        return 0;
    }

    private long readTag(Slice s) {
        int offset = s.length() - 8;
        long tag = 0;
        byte[] bytes = s.getBytes();
        for (int i = 0; i < 8; i++) {
            tag |= (long)(bytes[offset + i] & 0xFF) << (i * 8);
        }
        return tag;
    }

    @Override
    public Slice findShortestSeparator(Slice start, Slice limit) {
        Slice uStart = InternalKey.extractUserKey(start.getBytes());
        Slice uLimit = InternalKey.extractUserKey(limit.getBytes());
        Slice tmp = userComparator.findShortestSeparator(uStart, uLimit);
        if (tmp.length() < uStart.length() && userComparator.compare(uStart, tmp) < 0) {
            byte[] encoded = InternalKey.encode(tmp, DbConfig.MAX_SEQUENCE_NUMBER, ValueType.VALUE_FOR_SEEK);
            return new Slice(encoded);
        }
        return start;
    }

    @Override
    public Slice findShortSuccessor(Slice key) {
        Slice uKey = InternalKey.extractUserKey(key.getBytes());
        Slice tmp = userComparator.findShortSuccessor(uKey);
        if (tmp.length() < uKey.length() && userComparator.compare(uKey, tmp) < 0) {
            byte[] encoded = InternalKey.encode(tmp, DbConfig.MAX_SEQUENCE_NUMBER, ValueType.VALUE_FOR_SEEK);
            return new Slice(encoded);
        }
        return key;
    }
}
