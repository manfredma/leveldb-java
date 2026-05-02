package com.leveldb.common;

/**
 * LevelDB 全局常量。对应 C++ dbformat.h 中的 config namespace。
 */
public final class DbConfig {
    private DbConfig() {}

    public static final int NUM_LEVELS = 7;
    public static final int L0_COMPACTION_TRIGGER = 4;
    public static final int L0_SLOWDOWN_WRITES_TRIGGER = 8;
    public static final int L0_STOP_WRITES_TRIGGER = 12;
    public static final int MAX_MEM_COMPACT_LEVEL = 2;
    public static final long MAX_SEQUENCE_NUMBER = (1L << 56) - 1;

    public static long maxBytesForLevel(int level) {
        double result = 10.0 * 1048576.0;
        while (level > 1) {
            result *= 10;
            level--;
        }
        return (long) result;
    }

    public static final long TARGET_FILE_SIZE = 2 * 1048576L;
    public static final long MAX_GRANDPARENT_OVERLAP_BYTES = 10 * TARGET_FILE_SIZE;
}
