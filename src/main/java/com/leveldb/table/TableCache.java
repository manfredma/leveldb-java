package com.leveldb.table;

import com.leveldb.common.Slice;
import com.leveldb.db.Options;
import com.leveldb.db.ReadOptions;
import com.leveldb.iterator.Iterator;
import com.leveldb.util.LRUCache;
import java.io.*;

/**
 * SSTable 的 LRU 缓存，避免重复打开同一文件。对应 C++ db/table_cache.h。
 */
public class TableCache {
    private final String dbname;
    private final Options options;
    private final LRUCache<Long, Table> cache;

    public TableCache(String dbname, Options options, int entries) {
        this.dbname = dbname;
        this.options = options;
        this.cache = new LRUCache<>(entries);
    }

    public Table getTable(long fileNumber, long fileSize) throws IOException {
        Table t = cache.get(fileNumber);
        if (t == null) {
            File f = new File(dbname, String.format("%06d.ldb", fileNumber));
            if (!f.exists()) f = new File(dbname, String.format("%06d.sst", fileNumber));
            t = Table.open(options, f, fileSize);
            cache.put(fileNumber, t);
        }
        return t;
    }

    public void get(ReadOptions opts, long fileNumber, long fileSize,
                    Slice key, Table.GetCallback callback) throws IOException {
        Table t = getTable(fileNumber, fileSize);
        t.internalGet(opts, key, callback);
    }

    public Iterator newIterator(ReadOptions opts, long fileNumber, long fileSize) throws IOException {
        Table t = getTable(fileNumber, fileSize);
        return t.newIterator(opts);
    }

    public void evict(long fileNumber) {
        Table t = cache.get(fileNumber);
        if (t != null) {
            try { t.close(); } catch (IOException ignored) {}
            cache.invalidate(fileNumber);
        }
    }
}
