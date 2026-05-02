package com.leveldb.memtable;

import com.leveldb.common.Comparator;
import com.leveldb.common.Slice;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * 并发跳表。写操作需外部加锁，读操作无锁安全。
 * 对应 C++ db/skiplist.h。
 * - 最大高度 12，增高概率 1/4（BRANCHING=4）
 * - 节点永不删除（删除用墓碑标记）
 * - 通过 volatile + AtomicReferenceArray 保证读可见性
 */
public class SkipList<K> {
    static final int MAX_HEIGHT = 12;
    private static final int BRANCHING = 4;

    private final Comparator comparator;
    private final Node<K> head;
    private final AtomicInteger maxHeight;
    private final Random random;

    @SuppressWarnings("unchecked")
    public SkipList(Comparator comparator) {
        this.comparator = comparator;
        this.head = new Node<>(null, MAX_HEIGHT);
        this.maxHeight = new AtomicInteger(1);
        this.random = new Random(0xdeadbeefL);
    }

    @SuppressWarnings("unchecked")
    public void insert(K key) {
        Node<K>[] prev = new Node[MAX_HEIGHT];
        Node<K> x = findGreaterOrEqual(key, prev);
        assert x == null || !equal(key, x.key);

        int height = randomHeight();
        if (height > maxHeight.get()) {
            for (int i = maxHeight.get(); i < height; i++) prev[i] = head;
            maxHeight.set(height);
        }

        x = new Node<>(key, height);
        for (int i = 0; i < height; i++) {
            x.setNext(i, prev[i].getNext(i));
            prev[i].setNext(i, x);
        }
    }

    public boolean contains(K key) {
        Node<K> x = findGreaterOrEqual(key, null);
        return x != null && equal(key, x.key);
    }

    public SkipListIterator iterator() {
        return new SkipListIterator();
    }

    private int randomHeight() {
        int height = 1;
        while (height < MAX_HEIGHT && (random.nextInt() & (BRANCHING - 1)) == 0) height++;
        return height;
    }

    @SuppressWarnings("unchecked")
    private Node<K> findGreaterOrEqual(K key, Node<K>[] prev) {
        Node<K> x = head;
        int level = maxHeight.get() - 1;
        while (true) {
            Node<K> next = x.getNext(level);
            if (isAfterNode(key, next)) {
                x = next;
            } else {
                if (prev != null) prev[level] = x;
                if (level == 0) {
                    return next;
                } else {
                    level--;
                }
            }
        }
    }

    private Node<K> findLessThan(K key) {
        Node<K> x = head;
        int level = maxHeight.get() - 1;
        while (true) {
            Node<K> next = x.getNext(level);
            if (next == null || compare(next.key, key) >= 0) {
                if (level == 0) return x == head ? null : x;
                level--;
            } else {
                x = next;
            }
        }
    }

    private Node<K> findLast() {
        Node<K> x = head;
        int level = maxHeight.get() - 1;
        while (true) {
            Node<K> next = x.getNext(level);
            if (next == null) {
                if (level == 0) return x == head ? null : x;
                level--;
            } else {
                x = next;
            }
        }
    }

    private boolean isAfterNode(K key, Node<K> n) {
        return n != null && compare(n.key, key) < 0;
    }

    @SuppressWarnings("unchecked")
    private int compare(K a, K b) {
        return comparator.compare((Slice) a, (Slice) b);
    }

    @SuppressWarnings("unchecked")
    private boolean equal(K a, K b) {
        return comparator.compare((Slice) a, (Slice) b) == 0;
    }

    static class Node<K> {
        final K key;
        private final AtomicReferenceArray<Node<K>> next;

        Node(K key, int height) {
            this.key = key;
            this.next = new AtomicReferenceArray<>(height);
        }

        Node<K> getNext(int level) { return next.get(level); }
        void setNext(int level, Node<K> node) { next.set(level, node); }
    }

    public class SkipListIterator {
        private Node<K> node;

        public boolean valid() { return node != null; }
        public K key() { assert valid(); return node.key; }

        public void next() {
            assert valid();
            node = node.getNext(0);
        }

        public void prev() {
            assert valid();
            node = findLessThan(node.key);
        }

        public void seek(K target) {
            node = findGreaterOrEqual(target, null);
        }

        public void seekToFirst() {
            node = head.getNext(0);
        }

        public void seekToLast() {
            node = findLast();
        }
    }
}
