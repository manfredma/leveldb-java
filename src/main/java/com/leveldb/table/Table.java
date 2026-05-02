package com.leveldb.table;

import com.leveldb.common.BytewiseComparator;
import com.leveldb.common.Slice;
import com.leveldb.common.Status;
import com.leveldb.db.Options;
import com.leveldb.db.ReadOptions;
import com.leveldb.iterator.Iterator;
import com.leveldb.iterator.TwoLevelIterator;
import java.io.*;

/**
 * SSTable 读取器。对应 C++ table/table.h + table.cc。
 */
public class Table implements Closeable {
    private final Options options;
    private final RandomAccessFile file;
    private final Footer footer;
    private final Block indexBlock;
    private FilterBlock filterBlock;

    private Table(Options options, RandomAccessFile file, Footer footer,
                  Block indexBlock, FilterBlock filterBlock) {
        this.options = options;
        this.file = file;
        this.footer = footer;
        this.indexBlock = indexBlock;
        this.filterBlock = filterBlock;
    }

    public static Table open(Options options, File f, long fileSize) throws IOException {
        if (fileSize < Footer.ENCODED_LENGTH)
            throw new IOException("File too short to be SSTable: " + f);
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        byte[] footerBytes = new byte[Footer.ENCODED_LENGTH];
        raf.seek(fileSize - Footer.ENCODED_LENGTH);
        raf.readFully(footerBytes);
        Footer footer = Footer.decode(footerBytes);
        Block indexBlock = readBlock(raf, footer.indexHandle());
        FilterBlock filterBlock = null;
        try {
            Block metaBlock = readBlock(raf, footer.metaindexHandle());
            Iterator metaIt = metaBlock.newIterator(BytewiseComparator.INSTANCE);
            Slice filterKey = Slice.from("filter.leveldb.BuiltinBloomFilter2");
            metaIt.seek(filterKey);
            if (metaIt.valid() && metaIt.key().equals(filterKey)) {
                byte[] handleEnc = metaIt.value().getBytes();
                int[] off = {0};
                BlockHandle fh = BlockHandle.decodeFrom(handleEnc, off);
                byte[] filterData = readRawBlock(raf, fh);
                if (options.filterPolicy != null) {
                    filterBlock = new FilterBlock(options.filterPolicy, new Slice(filterData));
                }
            }
        } catch (Exception ignored) {}

        return new Table(options, raf, footer, indexBlock, filterBlock);
    }

    public void internalGet(ReadOptions opts, Slice key, GetCallback callback) throws IOException {
        Iterator iit = indexBlock.newIterator(options.comparator);
        iit.seek(key);
        if (!iit.valid()) { callback.notFound(); return; }

        if (filterBlock != null) {
            byte[] handleEnc = iit.value().getBytes();
            int[] off = {0};
            BlockHandle bh = BlockHandle.decodeFrom(handleEnc, off);
            if (!filterBlock.keyMayMatch(bh.offset(), key)) {
                callback.notFound();
                return;
            }
        }

        byte[] handleEnc = iit.value().getBytes();
        int[] off = {0};
        BlockHandle bh = BlockHandle.decodeFrom(handleEnc, off);
        Block dataBlock = readBlock(file, bh);
        Iterator dit = dataBlock.newIterator(options.comparator);
        dit.seek(key);
        if (dit.valid() && options.comparator.compare(dit.key(), key) == 0) {
            callback.got(dit.key(), dit.value());
        } else {
            callback.notFound();
        }
    }

    public Iterator newIterator(ReadOptions opts) {
        return new TwoLevelIterator(
            indexBlock.newIterator(options.comparator),
            (readOpts, indexValue) -> {
                int[] off2 = {0};
                BlockHandle bh = BlockHandle.decodeFrom(indexValue.getBytes(), off2);
                Block b = readBlock(file, bh);
                return b.newIterator(options.comparator);
            },
            opts
        );
    }

    public long approximateOffsetOf(Slice key) throws IOException {
        Iterator iit = indexBlock.newIterator(options.comparator);
        iit.seek(key);
        if (iit.valid()) {
            int[] off = {0};
            BlockHandle bh = BlockHandle.decodeFrom(iit.value().getBytes(), off);
            return bh.offset();
        }
        return file.length();
    }

    @Override
    public void close() throws IOException { file.close(); }

    public interface GetCallback {
        void got(Slice key, Slice value);
        void notFound();
    }

    private static Block readBlock(RandomAccessFile raf, BlockHandle handle) throws IOException {
        byte[] data = readRawBlock(raf, handle);
        return new Block(new BlockContents(new Slice(data), true, false));
    }

    private static byte[] readRawBlock(RandomAccessFile raf, BlockHandle handle) throws IOException {
        byte[] buf = new byte[(int) handle.size()];
        raf.seek(handle.offset());
        raf.readFully(buf);
        return buf;
    }
}
