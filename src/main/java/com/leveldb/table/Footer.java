package com.leveldb.table;

import com.leveldb.util.Coding;
import java.io.IOException;

/**
 * SSTable 文件末尾 48 字节固定结构。对应 C++ table/format.h Footer。
 * 格式：metaindex_handle(≤20字节) + index_handle(≤20字节) + padding + magic(8字节)
 */
public class Footer {
    public static final int ENCODED_LENGTH = 48;
    public static final long TABLE_MAGIC_NUMBER = 0xdb4775248b80fb57L;

    private BlockHandle metaindexHandle;
    private BlockHandle indexHandle;

    public Footer() {}
    public Footer(BlockHandle metaindexHandle, BlockHandle indexHandle) {
        this.metaindexHandle = metaindexHandle;
        this.indexHandle = indexHandle;
    }

    public BlockHandle metaindexHandle() { return metaindexHandle; }
    public BlockHandle indexHandle()     { return indexHandle; }

    public byte[] encode() throws IOException {
        byte[] metaBytes  = metaindexHandle.encodeTo();
        byte[] indexBytes = indexHandle.encodeTo();
        byte[] result = new byte[ENCODED_LENGTH];
        System.arraycopy(metaBytes,  0, result, 0, metaBytes.length);
        System.arraycopy(indexBytes, 0, result, metaBytes.length, indexBytes.length);
        Coding.encodeFixed64(result, ENCODED_LENGTH - 8, TABLE_MAGIC_NUMBER);
        return result;
    }

    public static Footer decode(byte[] buf) throws IOException {
        if (buf.length < ENCODED_LENGTH) throw new IOException("Footer too short");
        long magic = Coding.decodeFixed64(buf, ENCODED_LENGTH - 8);
        if (magic != TABLE_MAGIC_NUMBER) {
            throw new IOException("Invalid table magic number: 0x" + Long.toHexString(magic));
        }
        int[] offset = {0};
        BlockHandle metaindex = BlockHandle.decodeFrom(buf, offset);
        BlockHandle index     = BlockHandle.decodeFrom(buf, offset);
        return new Footer(metaindex, index);
    }
}
