package com.leveldb.common;

/**
 * MemTable 查询辅助结构。编码格式：
 *   klength(varint32) + user_key + tag(8字节 LE)
 * 其中 klength = len(user_key) + 8
 * 对应 C++ LookupKey（db/dbformat.h）。
 */
public final class LookupKey {
    private final byte[] space;
    private final int kstart;
    private final int end;

    public LookupKey(Slice userKey, long sequenceNumber) {
        int usize = userKey.length();
        int klength = usize + 8;
        byte[] varint = encodeVarint32(klength);
        space = new byte[varint.length + usize + 8];
        System.arraycopy(varint, 0, space, 0, varint.length);
        kstart = varint.length;
        byte[] ukBytes = userKey.getBytes();
        System.arraycopy(ukBytes, 0, space, kstart, usize);
        long tag = (sequenceNumber << 8) | ValueType.VALUE_FOR_SEEK.code;
        for (int i = 0; i < 8; i++) {
            space[kstart + usize + i] = (byte)(tag >>> (i * 8));
        }
        end = space.length;
    }

    public Slice memtableKey() { return new Slice(space, 0, end); }
    public Slice internalKey() { return new Slice(space, kstart, end - kstart); }
    public Slice userKey() { return new Slice(space, kstart, end - kstart - 8); }

    private static byte[] encodeVarint32(int value) {
        byte[] buf = new byte[5];
        int pos = 0;
        while (value > 0x7F) {
            buf[pos++] = (byte)((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf[pos++] = (byte)(value & 0x7F);
        byte[] result = new byte[pos];
        System.arraycopy(buf, 0, result, 0, pos);
        return result;
    }
}
