package com.leveldb.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 简单 LRU 缓存，基于 LinkedHashMap（accessOrder=true）。
 * 对应 C++ util/cache.cc 中的 ShardedLRUCache（简化版）。
 */
public class LRUCache<K, V> {
    private final int capacity;
    private final LinkedHashMap<K, V> map;

    public LRUCache(int capacity) {
        this.capacity = capacity;
        this.map = new LinkedHashMap<K, V>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > capacity;
            }
        };
    }

    public synchronized V get(K key) { return map.get(key); }
    public synchronized void put(K key, V value) { map.put(key, value); }
    public synchronized void invalidate(K key) { map.remove(key); }
    public synchronized int size() { return map.size(); }
}
