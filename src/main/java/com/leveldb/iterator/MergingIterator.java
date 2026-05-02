package com.leveldb.iterator;

import com.leveldb.common.Comparator;
import com.leveldb.common.Slice;
import com.leveldb.common.Status;

/**
 * 多路归并迭代器：合并多个有序 Iterator 为全局有序序列。
 * 对应 C++ table/merger.cc NewMergingIterator。
 * 用于 compaction（合并多个 SSTable）和全库迭代（合并所有 level 的迭代器）。
 */
public class MergingIterator implements Iterator {
    private final Comparator comparator;
    private final Iterator[] children;
    private Iterator current;

    public MergingIterator(Comparator comparator, Iterator[] children) {
        this.comparator = comparator;
        this.children = children;
    }

    @Override
    public void seekToFirst() {
        for (Iterator child : children) child.seekToFirst();
        findSmallest();
    }

    @Override
    public void seekToLast() {
        for (Iterator child : children) child.seekToLast();
        findLargest();
    }

    @Override
    public void seek(Slice target) {
        for (Iterator child : children) child.seek(target);
        findSmallest();
    }

    @Override
    public void next() {
        assert valid();
        current.next();
        findSmallest();
    }

    @Override
    public void prev() {
        assert valid();
        current.prev();
        findLargest();
    }

    @Override public boolean valid() { return current != null && current.valid(); }
    @Override public Slice key()     { return current.key(); }
    @Override public Slice value()   { return current.value(); }
    @Override public Status status() { return Status.ok(); }
    @Override public void close()    { for (Iterator c : children) c.close(); }

    private void findSmallest() {
        Iterator smallest = null;
        for (Iterator child : children) {
            if (child.valid()) {
                if (smallest == null || comparator.compare(child.key(), smallest.key()) < 0) {
                    smallest = child;
                }
            }
        }
        current = smallest;
    }

    private void findLargest() {
        Iterator largest = null;
        for (Iterator child : children) {
            if (child.valid()) {
                if (largest == null || comparator.compare(child.key(), largest.key()) > 0) {
                    largest = child;
                }
            }
        }
        current = largest;
    }
}
