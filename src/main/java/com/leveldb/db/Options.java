package com.leveldb.db;

import com.leveldb.common.BytewiseComparator;
import com.leveldb.common.Comparator;
import com.leveldb.util.BloomFilter;

/**
 * 数据库配置选项。对应 C++ include/leveldb/options.h Options。
 */
public class Options {
    public Comparator comparator         = BytewiseComparator.INSTANCE;
    public boolean createIfMissing       = false;
    public boolean errorIfExists         = false;
    public boolean paranoidChecks        = false;
    public int writeBufferSize           = 4 * 1024 * 1024;
    public int maxOpenFiles              = 1000;
    public int blockCacheSize            = 8 * 1024 * 1024;
    public int blockSize                 = 4 * 1024;
    public int blockRestartInterval      = 16;
    public long maxFileSize              = 2 * 1024 * 1024L;
    public BloomFilter filterPolicy      = new BloomFilter(10);
}
