package com.leveldb.common;

/**
 * 字典序字节比较器。对应 C++ BytewiseComparatorImpl（util/comparator.cc）。
 */
public final class BytewiseComparator implements Comparator {
    public static final BytewiseComparator INSTANCE = new BytewiseComparator();

    private BytewiseComparator() {}

    @Override
    public String name() { return "leveldb.BytewiseComparator"; }

    @Override
    public int compare(Slice a, Slice b) { return a.compareTo(b); }

    @Override
    public Slice findShortestSeparator(Slice start, Slice limit) {
        int minLen = Math.min(start.length(), limit.length());
        int diffIndex = 0;
        while (diffIndex < minLen && start.getByte(diffIndex) == limit.getByte(diffIndex)) {
            diffIndex++;
        }
        if (diffIndex < minLen) {
            byte diffByte = start.getByte(diffIndex);
            if ((diffByte & 0xFF) < 0xFF && (diffByte & 0xFF) + 1 < (limit.getByte(diffIndex) & 0xFF)) {
                byte[] result = new byte[diffIndex + 1];
                System.arraycopy(start.getBytes(), 0, result, 0, diffIndex + 1);
                result[diffIndex] = (byte)(diffByte + 1);
                return new Slice(result);
            }
        }
        return start;
    }

    @Override
    public Slice findShortSuccessor(Slice key) {
        byte[] bytes = key.getBytes();
        for (int i = 0; i < bytes.length; i++) {
            if ((bytes[i] & 0xFF) != 0xFF) {
                byte[] result = new byte[i + 1];
                System.arraycopy(bytes, 0, result, 0, i + 1);
                result[i]++;
                return new Slice(result);
            }
        }
        return key;
    }
}
