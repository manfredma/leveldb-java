package com.leveldb.db;

import com.leveldb.common.Slice;
import com.leveldb.common.ValueType;
import com.leveldb.util.Coding;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 批量写单元，保证原子性。对应 C++ include/leveldb/write_batch.h + db/write_batch.cc。
 *
 * 内部编码：sequence_number(8字节 LE) + count(4字节 LE) + records
 * 每条 Record:
 *   kTypeValue:    [0x01][key_len(varint32)][key][value_len(varint32)][value]
 *   kTypeDeletion: [0x00][key_len(varint32)][key]
 */
public class WriteBatch {
    private static final int HEADER_SIZE = 12;

    private byte[] rep;
    private int count;

    public WriteBatch() {
        rep = new byte[HEADER_SIZE];
        count = 0;
    }

    public WriteBatch put(Slice key, Slice value) {
        count++;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            out.write(rep);
            out.write(ValueType.VALUE.code);
            Coding.encodeLengthPrefixedSlice(out, key);
            Coding.encodeLengthPrefixedSlice(out, value);
        } catch (IOException e) { throw new RuntimeException(e); }
        rep = out.toByteArray();
        updateCount();
        return this;
    }

    public WriteBatch delete(Slice key) {
        count++;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            out.write(rep);
            out.write(ValueType.DELETION.code);
            Coding.encodeLengthPrefixedSlice(out, key);
        } catch (IOException e) { throw new RuntimeException(e); }
        rep = out.toByteArray();
        updateCount();
        return this;
    }

    public void clear() {
        rep = new byte[HEADER_SIZE];
        count = 0;
    }

    public int count() { return count; }

    public long getSequenceNumber() {
        return Coding.decodeFixed64(rep, 0);
    }

    public void setSequenceNumber(long seq) {
        Coding.encodeFixed64(rep, 0, seq);
    }

    public byte[] getContents() { return rep.clone(); }

    public static WriteBatch fromBytes(byte[] data) {
        WriteBatch b = new WriteBatch();
        b.rep = data.clone();
        b.count = Coding.decodeFixed32(data, 8);
        return b;
    }

    public void forEach(Handler handler) {
        int[] offset = {HEADER_SIZE};
        while (offset[0] < rep.length) {
            int type = rep[offset[0]++] & 0xFF;
            Slice key = Coding.decodeLengthPrefixedSlice(rep, offset);
            if (type == ValueType.VALUE.code) {
                Slice value = Coding.decodeLengthPrefixedSlice(rep, offset);
                handler.put(key, value);
            } else {
                handler.delete(key);
            }
        }
    }

    private void updateCount() {
        Coding.encodeFixed32(rep, 8, count);
    }

    public interface Handler {
        void put(Slice key, Slice value);
        void delete(Slice key);
    }
}
