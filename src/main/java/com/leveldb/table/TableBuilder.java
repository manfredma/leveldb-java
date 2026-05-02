package com.leveldb.table;

import com.leveldb.common.Slice;
import com.leveldb.common.Status;
import com.leveldb.db.Options;
import com.leveldb.util.Coding;
import com.leveldb.util.Crc32c;
import java.io.*;

/**
 * 顺序写入 key-value 构建 SSTable 文件。对应 C++ table/table_builder.h + table_builder.cc。
 * 调用者必须保证 key 严格递增。
 */
public class TableBuilder implements Closeable {
    private final Options options;
    private final RandomAccessFile file;
    private final BlockBuilder dataBlockBuilder;
    private final BlockBuilder indexBlockBuilder;
    private FilterBlockBuilder filterBlockBuilder;
    private BlockHandle pendingHandle;
    private boolean pendingIndexEntry;
    private long offset;
    private long numEntries;
    private boolean closed;
    private byte[] lastKey = new byte[0];

    public TableBuilder(Options options, File f) throws IOException {
        this.options = options;
        this.file = new RandomAccessFile(f, "rw");
        this.file.setLength(0);
        this.dataBlockBuilder  = new BlockBuilder(options.blockRestartInterval);
        this.indexBlockBuilder = new BlockBuilder(1);
        if (options.filterPolicy != null) {
            this.filterBlockBuilder = new FilterBlockBuilder(options.filterPolicy);
        }
        this.offset = 0;
        this.numEntries = 0;
        this.pendingIndexEntry = false;
        this.closed = false;
    }

    public void add(Slice key, Slice value) throws IOException {
        assert !closed;
        if (pendingIndexEntry) {
            byte[] indexKey = options.comparator.findShortestSeparator(
                new Slice(lastKey), key).getBytes();
            byte[] handleEnc = pendingHandle.encodeTo();
            indexBlockBuilder.add(new Slice(indexKey), new Slice(handleEnc));
            pendingIndexEntry = false;
        }
        if (filterBlockBuilder != null) filterBlockBuilder.addKey(key);
        lastKey = key.getBytes();
        numEntries++;
        dataBlockBuilder.add(key, value);
        if (dataBlockBuilder.currentSizeEstimate() >= options.blockSize) {
            flush();
        }
    }

    public void flush() throws IOException {
        if (dataBlockBuilder.isEmpty()) return;
        assert !pendingIndexEntry;
        pendingHandle = writeBlock(dataBlockBuilder);
        dataBlockBuilder.reset();
        pendingIndexEntry = true;
        if (filterBlockBuilder != null) filterBlockBuilder.startBlock(offset);
    }

    public Status finish() throws IOException {
        flush();
        assert !closed;
        closed = true;

        BlockHandle filterHandle = new BlockHandle(0, 0);

        // 写 filter block
        if (filterBlockBuilder != null) {
            Slice filterData = filterBlockBuilder.finish();
            filterHandle = writeRawBlock(filterData, false);
        }

        // 写 meta index block
        BlockBuilder metaIndexBuilder = new BlockBuilder(1);
        if (filterBlockBuilder != null) {
            try {
                byte[] handleEnc = filterHandle.encodeTo();
                metaIndexBuilder.add(Slice.from("filter.leveldb.BuiltinBloomFilter2"),
                                     new Slice(handleEnc));
            } catch (Exception ignored) {}
        }
        BlockHandle metaIndexHandle = writeBlock(metaIndexBuilder);

        // 写 index block
        if (pendingIndexEntry) {
            byte[] indexKey = options.comparator.findShortSuccessor(new Slice(lastKey)).getBytes();
            byte[] handleEnc = pendingHandle.encodeTo();
            indexBlockBuilder.add(new Slice(indexKey), new Slice(handleEnc));
            pendingIndexEntry = false;
        }
        BlockHandle indexHandle = writeBlock(indexBlockBuilder);

        // 写 footer
        Footer footer = new Footer(metaIndexHandle, indexHandle);
        byte[] footerBytes = footer.encode();
        file.seek(offset);
        file.write(footerBytes);
        offset += footerBytes.length;

        file.getFD().sync();
        return Status.ok();
    }

    public long numEntries() { return numEntries; }

    public long fileSize() throws IOException {
        try { return file.length(); } catch (IOException e) { return offset; }
    }

    @Override
    public void close() throws IOException { file.close(); }

    private BlockHandle writeBlock(BlockBuilder builder) throws IOException {
        Slice data = builder.finish();
        BlockHandle handle = writeRawBlock(data, true);
        builder.reset();
        return handle;
    }

    private BlockHandle writeRawBlock(Slice data, boolean withTrailer) throws IOException {
        BlockHandle handle = new BlockHandle(offset, data.length());
        file.seek(offset);
        file.write(data.getBytes());
        offset += data.length();
        if (withTrailer) {
            byte type = 0; // kNoCompression
            byte[] trailer = new byte[5];
            trailer[0] = type;
            int crc = Crc32c.value(data.getBytes(), 0, data.length());
            crc = Crc32c.extend(crc, new byte[]{type}, 0, 1);
            crc = Crc32c.mask(crc);
            Coding.encodeFixed32(trailer, 1, crc);
            file.write(trailer);
            offset += 5;
        }
        return handle;
    }
}
