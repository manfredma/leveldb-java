package com.leveldb.common;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 对 byte[] 的轻量封装，支持零拷贝子切片。
 * 对应 C++ leveldb::Slice（include/leveldb/slice.h）。
 */
public final class Slice implements Comparable<Slice> {
    private final byte[] data;
    private final int offset;
    private final int length;

    public Slice(byte[] data) {
        this(data, 0, data.length);
    }

    public Slice(byte[] data, int offset, int length) {
        this.data = data;
        this.offset = offset;
        this.length = length;
    }

    public static Slice from(String s) {
        return new Slice(s.getBytes(StandardCharsets.UTF_8));
    }

    public int length() { return length; }
    public boolean isEmpty() { return length == 0; }

    public byte getByte(int index) {
        return data[offset + index];
    }

    public byte[] getBytes() {
        return Arrays.copyOfRange(data, offset, offset + length);
    }

    public Slice slice(int sliceOffset, int sliceLength) {
        return new Slice(data, offset + sliceOffset, sliceLength);
    }

    public boolean startsWith(Slice prefix) {
        if (prefix.length > length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[offset + i] != prefix.data[prefix.offset + i]) return false;
        }
        return true;
    }

    @Override
    public int compareTo(Slice other) {
        int minLen = Math.min(length, other.length);
        for (int i = 0; i < minLen; i++) {
            int a = data[offset + i] & 0xFF;
            int b = other.data[other.offset + i] & 0xFF;
            if (a != b) return a - b;
        }
        return length - other.length;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Slice)) return false;
        return compareTo((Slice) obj) == 0;
    }

    @Override
    public int hashCode() {
        int hash = 1;
        for (int i = 0; i < length; i++) hash = 31 * hash + (data[offset + i] & 0xFF);
        return hash;
    }

    @Override
    public String toString() {
        return new String(data, offset, length, StandardCharsets.UTF_8);
    }
}
