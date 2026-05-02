package com.leveldb.log;

import com.leveldb.util.Coding;
import com.leveldb.util.Crc32c;
import java.io.*;

/**
 * WAL 日志读取器。对应 C++ db/log_reader.h + log_reader.cc。
 * 自动重组 FIRST/MIDDLE/LAST 片段为完整记录。
 */
public class LogReader implements Closeable {
    private final RandomAccessFile src;
    private final boolean verifyChecksums;
    private final long startOffset;

    // 当前读取位置（文件绝对偏移）
    private long filePos;
    // 当前 block 缓冲
    private final byte[] blockBuf = new byte[LogFormat.BLOCK_SIZE];
    private int blockStart;   // blockBuf 对应文件的起始偏移
    private int blockFilled;  // blockBuf 中有效字节数
    private boolean eof;

    public LogReader(File file, boolean verifyChecksums, long initialOffset) throws IOException {
        this.src = new RandomAccessFile(file, "r");
        this.verifyChecksums = verifyChecksums;
        // 对齐到 block 边界
        long blockStart = (initialOffset / LogFormat.BLOCK_SIZE) * LogFormat.BLOCK_SIZE;
        this.startOffset = blockStart;
        this.filePos = blockStart;
        this.blockStart = -1;
        this.blockFilled = 0;
        this.eof = false;
    }

    public LogReader(InputStream in, boolean verifyChecksums) throws IOException {
        // 为兼容性保留，将 InputStream 转为临时文件（不推荐生产使用）
        File tmp = File.createTempFile("logreader", ".log");
        tmp.deleteOnExit();
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
        }
        this.src = new RandomAccessFile(tmp, "r");
        this.verifyChecksums = verifyChecksums;
        this.startOffset = 0;
        this.filePos = 0;
        this.blockStart = -1;
        this.blockFilled = 0;
        this.eof = false;
    }

    /**
     * 读取下一条逻辑记录，返回 null 表示 EOF。
     */
    public byte[] readRecord() throws IOException {
        ByteArrayOutputStream scratch = null;

        while (true) {
            // 确保 blockBuf 已加载当前 filePos 所在的 block
            int blockIndex = (int)(filePos / LogFormat.BLOCK_SIZE);
            int currentBlockStart = blockIndex * LogFormat.BLOCK_SIZE;

            if (this.blockStart != currentBlockStart) {
                this.blockStart = currentBlockStart;
                src.seek(currentBlockStart);
                int read = src.read(blockBuf, 0, LogFormat.BLOCK_SIZE);
                if (read <= 0) { eof = true; return null; }
                blockFilled = read;
            }

            int posInBlock = (int)(filePos - blockStart);

            // block 内剩余不足 HEADER_SIZE：填充字节，跳到下一个 block
            if (posInBlock + LogFormat.HEADER_SIZE > blockFilled) {
                filePos = currentBlockStart + LogFormat.BLOCK_SIZE;
                this.blockStart = -1; // 强制重新加载
                continue;
            }

            // 读 header
            int crc    = Coding.decodeFixed32(blockBuf, posInBlock);
            int length = (blockBuf[posInBlock+4] & 0xFF) | ((blockBuf[posInBlock+5] & 0xFF) << 8);
            int type   = blockBuf[posInBlock+6] & 0xFF;
            int dataStart = posInBlock + LogFormat.HEADER_SIZE;

            // 零填充
            if (type == LogFormat.RecordType.ZERO_TYPE.code && length == 0) {
                filePos = currentBlockStart + LogFormat.BLOCK_SIZE;
                this.blockStart = -1;
                continue;
            }

            // 数据可能跨 block：先读当前 block 能提供的部分
            byte[] fragment;
            if (dataStart + length <= blockFilled) {
                // 数据完全在当前 block 内
                fragment = new byte[length];
                System.arraycopy(blockBuf, dataStart, fragment, 0, length);
                filePos += LogFormat.HEADER_SIZE + length;
            } else {
                // 数据跨 block：逐字节组装（简化，教学用）
                fragment = readCrossBlockData(dataStart, length, currentBlockStart);
                if (fragment == null) return null;
                filePos = alignedBlockPos(dataStart + length);
            }

            // 处理记录类型
            switch (LogFormat.RecordType.fromCode(type)) {
                case FULL:
                    return fragment;
                case FIRST:
                    scratch = new ByteArrayOutputStream();
                    scratch.write(fragment);
                    break;
                case MIDDLE:
                    if (scratch != null) scratch.write(fragment);
                    break;
                case LAST:
                    if (scratch != null) {
                        scratch.write(fragment);
                        return scratch.toByteArray();
                    }
                    break;
                case ZERO_TYPE:
                    break;
            }
        }
    }

    private byte[] readCrossBlockData(int dataStartInBlock, int length, int blockStartAbs) throws IOException {
        byte[] result = new byte[length];
        int written = 0;
        // 当前 block 的部分
        int availableInBlock = blockFilled - dataStartInBlock;
        if (availableInBlock > 0) {
            System.arraycopy(blockBuf, dataStartInBlock, result, 0, availableInBlock);
            written = availableInBlock;
        }
        // 继续读后续 block
        long nextBlockStart = blockStartAbs + LogFormat.BLOCK_SIZE;
        while (written < length) {
            src.seek(nextBlockStart);
            int read = src.read(blockBuf, 0, LogFormat.BLOCK_SIZE);
            if (read <= 0) return null;
            // 注意：跨 block 的 MIDDLE/LAST 片段也有各自 header，但这里处理的是
            // 单个物理记录的 data 字段跨 block（实际 LevelDB 不会出现，因为 header 保证）
            // 此分支实际上处理的是：同一 data 在 block 边界被截断（这不会发生，LevelDB 设计保证了这一点）
            // 此处作为防御性代码
            int toCopy = Math.min(length - written, read);
            System.arraycopy(blockBuf, 0, result, written, toCopy);
            written += toCopy;
            nextBlockStart += LogFormat.BLOCK_SIZE;
        }
        return result;
    }

    private long alignedBlockPos(int endPosInBlock) {
        long absEnd = blockStart + endPosInBlock;
        return absEnd;
    }

    @Override
    public void close() throws IOException { src.close(); }
}
