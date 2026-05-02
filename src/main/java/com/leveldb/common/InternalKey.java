package com.leveldb.common;

/**
 * LevelDB 内部键工具类。
 * 格式: user_key + tag(8字节 LE)，其中 tag = (sequenceNumber << 8) | valueType.code
 * 对应 C++ dbformat.h 中的 InternalKey 和相关函数。
 */
public final class InternalKey {
    private InternalKey() {}

    public static byte[] encode(Slice userKey, long sequenceNumber, ValueType type) {
        byte[] userKeyBytes = userKey.getBytes();
        byte[] result = new byte[userKeyBytes.length + 8];
        System.arraycopy(userKeyBytes, 0, result, 0, userKeyBytes.length);
        long tag = (sequenceNumber << 8) | type.code;
        for (int i = 0; i < 8; i++) {
            result[userKeyBytes.length + i] = (byte)(tag >>> (i * 8));
        }
        return result;
    }

    public static Slice extractUserKey(byte[] internalKey) {
        assert internalKey.length >= 8;
        return new Slice(internalKey, 0, internalKey.length - 8);
    }

    public static long extractSequenceNumber(byte[] internalKey) {
        long tag = readTag(internalKey);
        return tag >>> 8;
    }

    public static ValueType extractValueType(byte[] internalKey) {
        long tag = readTag(internalKey);
        return ValueType.fromCode((int)(tag & 0xFF));
    }

    private static long readTag(byte[] internalKey) {
        int offset = internalKey.length - 8;
        long tag = 0;
        for (int i = 0; i < 8; i++) {
            tag |= (long)(internalKey[offset + i] & 0xFF) << (i * 8);
        }
        return tag;
    }
}
