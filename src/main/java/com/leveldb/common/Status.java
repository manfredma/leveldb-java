package com.leveldb.common;

/**
 * 操作结果，不可变。对应 C++ leveldb::Status（include/leveldb/status.h）。
 */
public final class Status {
    public enum Code { OK, NOT_FOUND, CORRUPTION, IO_ERROR, INVALID_ARGUMENT, NOT_SUPPORTED }

    private final Code code;
    private final String message;

    private Status(Code code, String message) {
        this.code = code;
        this.message = message;
    }

    public static Status ok() { return new Status(Code.OK, null); }
    public static Status notFound(String msg) { return new Status(Code.NOT_FOUND, msg); }
    public static Status corruption(String msg) { return new Status(Code.CORRUPTION, msg); }
    public static Status ioError(String msg) { return new Status(Code.IO_ERROR, msg); }
    public static Status invalidArgument(String msg) { return new Status(Code.INVALID_ARGUMENT, msg); }
    public static Status notSupported(String msg) { return new Status(Code.NOT_SUPPORTED, msg); }

    public boolean isOk() { return code == Code.OK; }
    public boolean isNotFound() { return code == Code.NOT_FOUND; }
    public boolean isCorruption() { return code == Code.CORRUPTION; }
    public boolean isIoError() { return code == Code.IO_ERROR; }
    public Code code() { return code; }
    public String message() { return message; }

    @Override
    public String toString() {
        return code == Code.OK ? "OK" : code.name() + ": " + message;
    }
}
