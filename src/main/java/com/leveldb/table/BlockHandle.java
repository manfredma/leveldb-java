package com.leveldb.table;

import com.leveldb.util.Coding;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 描述文件中一个 Block 的位置和大小。对应 C++ table/format.h BlockHandle。
 * 编码：offset(varint64) + size(varint64)，最多 20 字节。
 */
public class BlockHandle {
    public static final int MAX_ENCODED_LENGTH = 20;

    private long offset;
    private long size;

    public BlockHandle() { this.offset = 0; this.size = 0; }
    public BlockHandle(long offset, long size) { this.offset = offset; this.size = size; }

    public long offset() { return offset; }
    public long size()   { return size; }
    public void setOffset(long o) { this.offset = o; }
    public void setSize(long s)   { this.size = s; }

    public byte[] encodeTo() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(20);
        Coding.encodeVarint64(out, offset);
        Coding.encodeVarint64(out, size);
        return out.toByteArray();
    }

    public static BlockHandle decodeFrom(byte[] buf, int[] offsetRef) {
        long off  = Coding.decodeVarint64(buf, offsetRef);
        long size = Coding.decodeVarint64(buf, offsetRef);
        return new BlockHandle(off, size);
    }
}
