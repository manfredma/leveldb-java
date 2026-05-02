package com.leveldb.db;

import com.leveldb.common.Slice;

/**
 * SSTable 文件的元数据。对应 C++ db/version_edit.h FileMetaData。
 */
public class FileMetaData {
    public int refs;
    public int allowedSeeks;
    public long number;
    public long fileSize;
    public Slice smallest;
    public Slice largest;

    public FileMetaData() {}
}
