package com.leveldb.util;

import com.leveldb.common.Slice;

/**
 * LevelDB 内部哈希函数。对应 C++ util/hash.cc。
 * 基于 MurmurHash 变体。
 */
public final class Hash {
    private Hash() {}

    public static int hash(byte[] data, int offset, int length, int seed) {
        final int m = 0xc6a4a793;
        final int r = 24;
        int h = seed ^ (length * m);

        int pos = offset;
        int limit = offset + length;

        while (pos + 4 <= limit) {
            int w = ((data[pos] & 0xFF))
                  | ((data[pos+1] & 0xFF) << 8)
                  | ((data[pos+2] & 0xFF) << 16)
                  | ((data[pos+3] & 0xFF) << 24);
            pos += 4;
            h += w;
            h *= m;
            h ^= (h >>> 16);
        }

        switch (limit - pos) {
            case 3: h += (data[pos+2] & 0xFF) << 16;
            case 2: h += (data[pos+1] & 0xFF) << 8;
            case 1: h += (data[pos] & 0xFF); h *= m; h ^= (h >>> r); break;
        }
        return h;
    }

    public static int bloomHash(Slice key) {
        return hash(key.getBytes(), 0, key.length(), 0xbc9f1d34);
    }
}
