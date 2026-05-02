package com.leveldb.log;

/**
 * WAL 日志格式常量。对应 C++ db/log_format.h。
 * 日志以 32KB block 为单位，每条物理记录格式：
 *   CRC32(4) + Length(2) + Type(1) + Data(Length)
 */
public final class LogFormat {
    private LogFormat() {}

    public static final int BLOCK_SIZE  = 32768;
    public static final int HEADER_SIZE = 7;

    public enum RecordType {
        ZERO_TYPE(0),
        FULL(1),
        FIRST(2),
        MIDDLE(3),
        LAST(4);

        public final int code;
        RecordType(int code) { this.code = code; }

        public static RecordType fromCode(int code) {
            for (RecordType t : values()) if (t.code == code) return t;
            throw new IllegalArgumentException("Unknown RecordType: " + code);
        }
    }
}
