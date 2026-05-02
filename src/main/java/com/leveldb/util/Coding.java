package com.leveldb.util;

import com.leveldb.common.Slice;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * LevelDB 编解码工具。对应 C++ util/coding.h + util/coding.cc。
 * Fixed: Little-Endian 定长编码。
 * Varint: 变长整数，每字节低 7 位存数据，最高位为延续标志。
 */
public final class Coding {
    private Coding() {}

    public static void encodeFixed32(byte[] buf, int offset, int value) {
        buf[offset]     = (byte)(value);
        buf[offset + 1] = (byte)(value >>> 8);
        buf[offset + 2] = (byte)(value >>> 16);
        buf[offset + 3] = (byte)(value >>> 24);
    }

    public static int decodeFixed32(byte[] buf, int offset) {
        return ((buf[offset]     & 0xFF))
             | ((buf[offset + 1] & 0xFF) << 8)
             | ((buf[offset + 2] & 0xFF) << 16)
             | ((buf[offset + 3] & 0xFF) << 24);
    }

    public static void encodeFixed64(byte[] buf, int offset, long value) {
        for (int i = 0; i < 8; i++) {
            buf[offset + i] = (byte)(value >>> (i * 8));
        }
    }

    public static long decodeFixed64(byte[] buf, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value |= (long)(buf[offset + i] & 0xFF) << (i * 8);
        }
        return value;
    }

    public static void encodeVarint32(ByteArrayOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value & 0x7F);
    }

    public static void encodeVarint64(ByteArrayOutputStream out, long value) throws IOException {
        while ((value & ~0x7FL) != 0) {
            out.write((int)((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int)(value & 0x7F));
    }

    public static int decodeVarint32(byte[] buf, int[] offsetRef) {
        int result = 0, shift = 0;
        int pos = offsetRef[0];
        while (shift < 35) {
            byte b = buf[pos++];
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        offsetRef[0] = pos;
        return result;
    }

    public static long decodeVarint64(byte[] buf, int[] offsetRef) {
        long result = 0;
        int shift = 0;
        int pos = offsetRef[0];
        while (shift < 64) {
            byte b = buf[pos++];
            result |= (long)(b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        offsetRef[0] = pos;
        return result;
    }

    public static int varintLength(long value) {
        int len = 1;
        while (value >= 128) {
            value >>>= 7;
            len++;
        }
        return len;
    }

    public static void encodeLengthPrefixedSlice(ByteArrayOutputStream out, Slice s) throws IOException {
        encodeVarint32(out, s.length());
        out.write(s.getBytes());
    }

    public static Slice decodeLengthPrefixedSlice(byte[] buf, int[] offsetRef) {
        int len = decodeVarint32(buf, offsetRef);
        Slice result = new Slice(buf, offsetRef[0], len);
        offsetRef[0] += len;
        return result;
    }
}
