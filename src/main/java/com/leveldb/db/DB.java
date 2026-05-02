package com.leveldb.db;

import com.leveldb.common.Slice;
import com.leveldb.iterator.Iterator;
import java.io.Closeable;
import java.io.IOException;

/**
 * LevelDB 对外公开 API。对应 C++ include/leveldb/db.h。
 */
public interface DB extends Closeable {
    static DB open(Options options, String name) throws IOException {
        return DBImpl.open(options, name);
    }

    void put(WriteOptions options, Slice key, Slice value) throws IOException;
    void delete(WriteOptions options, Slice key) throws IOException;
    void write(WriteOptions options, WriteBatch updates) throws IOException;
    byte[] get(ReadOptions options, Slice key) throws IOException;
    Iterator newIterator(ReadOptions options);
    Snapshot getSnapshot();
    void releaseSnapshot(Snapshot snapshot);
    String getProperty(String property);
    void compactRange(Slice begin, Slice end) throws IOException;
    @Override void close() throws IOException;
}
