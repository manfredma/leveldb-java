package com.leveldb.table;

import com.leveldb.common.Slice;
import com.leveldb.util.Coding;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 构建一个有序 key-value Block，支持前缀压缩。
 * 对应 C++ table/block_builder.h + block_builder.cc。
 *
 * Block 格式：
 *   [Entry: shared(varint) unshared(varint) value_len(varint) key_suffix value] ...
 *   [Restart Offsets: offset0(4字节) offset1(4字节) ...]
 *   [num_restarts(4字节 LE)]
 */
public class BlockBuilder {
    private final int restartInterval;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final List<Integer> restarts = new ArrayList<>();
    private int counter;
    private byte[] lastKey = new byte[0];
    private boolean finished;

    public BlockBuilder(int restartInterval) {
        this.restartInterval = restartInterval;
        restarts.add(0);
        this.counter = 0;
        this.finished = false;
    }

    public void add(Slice key, Slice value) {
        assert !finished;
        byte[] keyBytes = key.getBytes();
        byte[] valBytes = value.getBytes();

        int shared = 0;
        if (counter < restartInterval) {
            int minLen = Math.min(lastKey.length, keyBytes.length);
            while (shared < minLen && lastKey[shared] == keyBytes[shared]) shared++;
        } else {
            restarts.add(buffer.size());
            counter = 0;
        }

        int unshared = keyBytes.length - shared;
        try {
            Coding.encodeVarint32(buffer, shared);
            Coding.encodeVarint32(buffer, unshared);
            Coding.encodeVarint32(buffer, valBytes.length);
            buffer.write(keyBytes, shared, unshared);
            buffer.write(valBytes);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        lastKey = keyBytes;
        counter++;
    }

    public Slice finish() {
        byte[] restartBuf = new byte[4];
        for (int r : restarts) {
            Coding.encodeFixed32(restartBuf, 0, r);
            try { buffer.write(restartBuf); } catch (IOException e) { throw new RuntimeException(e); }
        }
        Coding.encodeFixed32(restartBuf, 0, restarts.size());
        try { buffer.write(restartBuf); } catch (IOException e) { throw new RuntimeException(e); }
        finished = true;
        return new Slice(buffer.toByteArray());
    }

    public void reset() {
        buffer.reset();
        restarts.clear();
        restarts.add(0);
        counter = 0;
        lastKey = new byte[0];
        finished = false;
    }

    public int currentSizeEstimate() {
        return buffer.size() + restarts.size() * 4 + 4;
    }

    public boolean isEmpty() { return buffer.size() == 0; }
}
