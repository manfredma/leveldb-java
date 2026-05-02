package com.leveldb.db;

/**
 * 读操作选项。对应 C++ include/leveldb/options.h ReadOptions。
 */
public class ReadOptions {
    public boolean verifyChecksums = false;
    public boolean fillCache       = true;
    public Snapshot snapshot       = null;
}
