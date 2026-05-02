package com.leveldb.common;

/**
 * LevelDB 内部 key 的值类型。对应 C++ dbformat.h 中的 ValueType 枚举。
 * kTypeDeletion=0 表示墓碑标记；kTypeValue=1 表示有效值。
 */
public enum ValueType {
    DELETION(0),
    VALUE(1);

    public final int code;

    ValueType(int code) { this.code = code; }

    public static ValueType fromCode(int code) {
        for (ValueType t : values()) {
            if (t.code == code) return t;
        }
        throw new IllegalArgumentException("Unknown ValueType code: " + code);
    }

    public static final ValueType VALUE_FOR_SEEK = VALUE;
}
