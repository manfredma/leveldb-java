package com.leveldb.util;

import java.util.zip.CRC32;

/**
 * CRC32 校验工具。对应 C++ util/crc32c.h。
 * 使用 Java 标准库 CRC32（ISO 多项式，教学用途）。
 */
public final class Crc32c {
    private Crc32c() {}

    public static int value(byte[] data, int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(data, offset, length);
        return (int) crc.getValue();
    }

    public static int extend(int crc, byte[] data, int offset, int length) {
        CRC32 c = new CRC32();
        // 用当前 crc 的各字节初始化（简化实现）
        byte[] seed = new byte[4];
        seed[0] = (byte)(crc);
        seed[1] = (byte)(crc >>> 8);
        seed[2] = (byte)(crc >>> 16);
        seed[3] = (byte)(crc >>> 24);
        c.update(seed);
        c.update(data, offset, length);
        return (int) c.getValue();
    }

    private static final int MASK_DELTA = 0xa282ead8;

    public static int mask(int crc) {
        return ((crc >>> 15) | (crc << 17)) + MASK_DELTA;
    }

    public static int unmask(int maskedCrc) {
        int rot = maskedCrc - MASK_DELTA;
        return (rot >>> 17) | (rot << 15);
    }
}
