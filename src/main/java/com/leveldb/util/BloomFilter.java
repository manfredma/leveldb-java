package com.leveldb.util;

import com.leveldb.common.Slice;
import java.util.List;

/**
 * Bloom Filter 实现。对应 C++ util/bloom.cc（BuiltinFilterPolicy）。
 * 使用双重哈希技巧，参考论文: Kirsch and Mitzenmacher, 2006。
 */
public final class BloomFilter {
    private final int bitsPerKey;
    private final int k;

    public BloomFilter(int bitsPerKey) {
        this.bitsPerKey = bitsPerKey;
        int kk = (int)(bitsPerKey * 0.69);
        if (kk < 1) kk = 1;
        if (kk > 30) kk = 30;
        this.k = kk;
    }

    public byte[] createFilter(List<Slice> keys) {
        int n = keys.size();
        int bits = Math.max(64, n * bitsPerKey);
        int bytes = (bits + 7) / 8;
        bits = bytes * 8;

        byte[] result = new byte[bytes + 1];
        result[bytes] = (byte)k;

        for (Slice key : keys) {
            int h = Hash.bloomHash(key);
            int delta = (h >>> 17) | (h << 15);
            for (int j = 0; j < k; j++) {
                int bitPos = (int)((h & 0xFFFFFFFFL) % bits);
                result[bitPos / 8] |= (1 << (bitPos % 8));
                h += delta;
            }
        }
        return result;
    }

    public boolean keyMayMatch(Slice key, byte[] filter) {
        int len = filter.length;
        if (len < 2) return false;

        int bits = (len - 1) * 8;
        int kk = filter[len - 1] & 0xFF;
        if (kk > 30) return true;

        int h = Hash.bloomHash(key);
        int delta = (h >>> 17) | (h << 15);
        for (int j = 0; j < kk; j++) {
            int bitPos = (int)((h & 0xFFFFFFFFL) % bits);
            if ((filter[bitPos / 8] & (1 << (bitPos % 8))) == 0) {
                return false;
            }
            h += delta;
        }
        return true;
    }
}
