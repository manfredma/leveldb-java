package com.leveldb.log;

import com.leveldb.util.Coding;
import com.leveldb.util.Crc32c;
import java.io.*;

/**
 * WAL 日志写入器。对应 C++ db/log_writer.h + log_writer.cc。
 * 自动处理跨 block 分片：单条记录超过块剩余空间时，分成 FIRST/MIDDLE/LAST 片段。
 */
public class LogWriter implements Closeable {
    private final OutputStream dest;
    private int blockOffset;

    public LogWriter(File file) throws IOException {
        this.dest = new BufferedOutputStream(new FileOutputStream(file, true));
        this.blockOffset = 0;
    }

    public LogWriter(OutputStream out) {
        this.dest = out;
        this.blockOffset = 0;
    }

    public void addRecord(byte[] data) throws IOException {
        addRecord(data, 0, data.length);
    }

    public void addRecord(byte[] data, int offset, int length) throws IOException {
        boolean begin = true;
        int left = length;
        do {
            int leftover = LogFormat.BLOCK_SIZE - blockOffset;
            assert leftover >= 0;

            if (leftover < LogFormat.HEADER_SIZE) {
                if (leftover > 0) {
                    dest.write(new byte[leftover]);
                }
                blockOffset = 0;
                leftover = LogFormat.BLOCK_SIZE;
            }

            int avail = leftover - LogFormat.HEADER_SIZE;
            int fragmentLength = Math.min(left, avail);

            LogFormat.RecordType type;
            boolean end = (left == fragmentLength);
            if (begin && end)   type = LogFormat.RecordType.FULL;
            else if (begin)     type = LogFormat.RecordType.FIRST;
            else if (end)       type = LogFormat.RecordType.LAST;
            else                type = LogFormat.RecordType.MIDDLE;

            emitPhysicalRecord(type, data, offset, fragmentLength);
            offset += fragmentLength;
            left   -= fragmentLength;
            begin   = false;
        } while (left > 0);
    }

    public void sync() throws IOException { dest.flush(); }

    @Override
    public void close() throws IOException { dest.close(); }

    private void emitPhysicalRecord(LogFormat.RecordType type, byte[] data,
                                    int dataOffset, int length) throws IOException {
        assert length <= 0xFFFF;
        assert blockOffset + LogFormat.HEADER_SIZE + length <= LogFormat.BLOCK_SIZE;

        byte[] typeByte = {(byte) type.code};
        int crc = Crc32c.value(typeByte, 0, 1);
        crc = Crc32c.extend(crc, data, dataOffset, length);
        crc = Crc32c.mask(crc);

        byte[] header = new byte[LogFormat.HEADER_SIZE];
        Coding.encodeFixed32(header, 0, crc);
        header[4] = (byte)(length & 0xFF);
        header[5] = (byte)((length >>> 8) & 0xFF);
        header[6] = (byte) type.code;
        dest.write(header);
        dest.write(data, dataOffset, length);
        blockOffset += LogFormat.HEADER_SIZE + length;
    }
}
