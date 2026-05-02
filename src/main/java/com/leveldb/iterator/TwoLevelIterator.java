package com.leveldb.iterator;

import com.leveldb.common.Slice;
import com.leveldb.common.Status;
import com.leveldb.db.ReadOptions;

/**
 * 两级迭代器：第一级是 Index Block 迭代器，第二级是 Data Block 迭代器。
 * 对应 C++ table/two_level_iterator.cc。
 */
public class TwoLevelIterator implements Iterator {
    public interface BlockFunction {
        Iterator open(ReadOptions opts, Slice indexValue) throws Exception;
    }

    private final Iterator indexIter;
    private Iterator dataIter;
    private final BlockFunction blockFunction;
    private final ReadOptions readOptions;

    public TwoLevelIterator(Iterator indexIter, BlockFunction blockFunction, ReadOptions opts) {
        this.indexIter = indexIter;
        this.blockFunction = blockFunction;
        this.readOptions = opts;
    }

    @Override public boolean valid() { return dataIter != null && dataIter.valid(); }
    @Override public Slice key()     { return dataIter.key(); }
    @Override public Slice value()   { return dataIter.value(); }
    @Override public Status status() { return Status.ok(); }

    @Override
    public void seekToFirst() {
        indexIter.seekToFirst();
        initDataBlock();
        if (dataIter != null) dataIter.seekToFirst();
        skipEmptyDataBlocksForward();
    }

    @Override
    public void seekToLast() {
        indexIter.seekToLast();
        initDataBlock();
        if (dataIter != null) dataIter.seekToLast();
        skipEmptyDataBlocksBackward();
    }

    @Override
    public void seek(Slice target) {
        indexIter.seek(target);
        initDataBlock();
        if (dataIter != null) dataIter.seek(target);
        skipEmptyDataBlocksForward();
    }

    @Override
    public void next() {
        assert valid();
        dataIter.next();
        skipEmptyDataBlocksForward();
    }

    @Override
    public void prev() {
        assert valid();
        dataIter.prev();
        skipEmptyDataBlocksBackward();
    }

    @Override
    public void close() {
        indexIter.close();
        if (dataIter != null) dataIter.close();
    }

    private void initDataBlock() {
        if (!indexIter.valid()) { dataIter = null; return; }
        Slice handle = indexIter.value();
        try { dataIter = blockFunction.open(readOptions, handle); }
        catch (Exception e) { dataIter = null; }
    }

    private void skipEmptyDataBlocksForward() {
        while (dataIter == null || !dataIter.valid()) {
            if (!indexIter.valid()) { dataIter = null; return; }
            indexIter.next();
            initDataBlock();
            if (dataIter != null) dataIter.seekToFirst();
        }
    }

    private void skipEmptyDataBlocksBackward() {
        while (dataIter == null || !dataIter.valid()) {
            if (!indexIter.valid()) { dataIter = null; return; }
            indexIter.prev();
            initDataBlock();
            if (dataIter != null) dataIter.seekToLast();
        }
    }
}
