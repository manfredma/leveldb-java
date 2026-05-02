package com.leveldb.db;

import com.leveldb.common.Slice;
import com.leveldb.util.Coding;
import java.io.*;
import java.util.*;

/**
 * 描述两个 Version 之间的变更。对应 C++ db/version_edit.h。
 * 用 tag-value 格式序列化到 MANIFEST 日志中。
 */
public class VersionEdit {
    private static final int TAG_COMPARATOR        = 1;
    private static final int TAG_LOG_NUMBER        = 2;
    private static final int TAG_NEXT_FILE_NUMBER  = 3;
    private static final int TAG_LAST_SEQUENCE     = 4;
    private static final int TAG_COMPACT_POINTER   = 5;
    private static final int TAG_DELETED_FILE      = 6;
    private static final int TAG_NEW_FILE          = 7;

    private String comparatorName;
    private Long logNumber;
    private Long prevLogNumber;
    private Long nextFileNumber;
    private Long lastSequence;
    private final Map<Integer, Slice> compactPointers = new HashMap<>();
    private final List<long[]> deletedFilesList = new ArrayList<>();      // [level, fileNumber]
    private final List<long[]> newFilesMeta = new ArrayList<>();          // [level, number, fileSize]
    private final List<Slice[]> newFilesKeys = new ArrayList<>();         // [smallest, largest]

    public void setComparatorName(String name)  { this.comparatorName = name; }
    public void setLogNumber(long num)           { this.logNumber = num; }
    public void setPrevLogNumber(long num)       { this.prevLogNumber = num; }
    public void setNextFileNumber(long num)      { this.nextFileNumber = num; }
    public void setLastSequence(long seq)        { this.lastSequence = seq; }
    public void setCompactPointer(int level, Slice key) { compactPointers.put(level, key); }

    public void addFile(int level, long number, long fileSize, Slice smallest, Slice largest) {
        newFilesMeta.add(new long[]{level, number, fileSize});
        newFilesKeys.add(new Slice[]{smallest, largest});
    }

    public void removeFile(int level, long number) {
        deletedFilesList.add(new long[]{level, number});
    }

    public String comparatorName()      { return comparatorName; }
    public Long logNumber()             { return logNumber; }
    public Long nextFileNumber()        { return nextFileNumber; }
    public Long lastSequence()          { return lastSequence; }
    public List<long[]> newFilesMeta()  { return newFilesMeta; }
    public List<Slice[]> newFilesKeys() { return newFilesKeys; }
    public List<long[]> deletedFiles()  { return deletedFilesList; }

    public byte[] encodeTo() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (comparatorName != null) {
            Coding.encodeVarint32(out, TAG_COMPARATOR);
            byte[] b = comparatorName.getBytes("UTF-8");
            Coding.encodeVarint32(out, b.length);
            out.write(b);
        }
        if (logNumber != null) {
            Coding.encodeVarint32(out, TAG_LOG_NUMBER);
            Coding.encodeVarint64(out, logNumber);
        }
        if (nextFileNumber != null) {
            Coding.encodeVarint32(out, TAG_NEXT_FILE_NUMBER);
            Coding.encodeVarint64(out, nextFileNumber);
        }
        if (lastSequence != null) {
            Coding.encodeVarint32(out, TAG_LAST_SEQUENCE);
            Coding.encodeVarint64(out, lastSequence);
        }
        for (Map.Entry<Integer, Slice> e : compactPointers.entrySet()) {
            Coding.encodeVarint32(out, TAG_COMPACT_POINTER);
            Coding.encodeVarint32(out, e.getKey());
            Coding.encodeLengthPrefixedSlice(out, e.getValue());
        }
        for (long[] df : deletedFilesList) {
            Coding.encodeVarint32(out, TAG_DELETED_FILE);
            Coding.encodeVarint32(out, (int) df[0]);
            Coding.encodeVarint64(out, df[1]);
        }
        for (int i = 0; i < newFilesMeta.size(); i++) {
            long[] meta = newFilesMeta.get(i);
            Slice[] keys = newFilesKeys.get(i);
            Coding.encodeVarint32(out, TAG_NEW_FILE);
            Coding.encodeVarint32(out, (int) meta[0]);
            Coding.encodeVarint64(out, meta[1]);
            Coding.encodeVarint64(out, meta[2]);
            Coding.encodeLengthPrefixedSlice(out, keys[0]);
            Coding.encodeLengthPrefixedSlice(out, keys[1]);
        }
        return out.toByteArray();
    }

    public static VersionEdit decodeFrom(byte[] data) throws IOException {
        VersionEdit edit = new VersionEdit();
        int[] offset = {0};
        while (offset[0] < data.length) {
            int tag = Coding.decodeVarint32(data, offset);
            switch (tag) {
                case TAG_COMPARATOR: {
                    int len = Coding.decodeVarint32(data, offset);
                    edit.comparatorName = new String(data, offset[0], len, "UTF-8");
                    offset[0] += len;
                    break;
                }
                case TAG_LOG_NUMBER:        edit.logNumber       = Coding.decodeVarint64(data, offset); break;
                case TAG_NEXT_FILE_NUMBER:  edit.nextFileNumber  = Coding.decodeVarint64(data, offset); break;
                case TAG_LAST_SEQUENCE:     edit.lastSequence    = Coding.decodeVarint64(data, offset); break;
                case TAG_COMPACT_POINTER: {
                    int level = Coding.decodeVarint32(data, offset);
                    Slice key = Coding.decodeLengthPrefixedSlice(data, offset);
                    edit.compactPointers.put(level, key);
                    break;
                }
                case TAG_DELETED_FILE: {
                    int level = Coding.decodeVarint32(data, offset);
                    long num  = Coding.decodeVarint64(data, offset);
                    edit.deletedFilesList.add(new long[]{level, num});
                    break;
                }
                case TAG_NEW_FILE: {
                    int level    = Coding.decodeVarint32(data, offset);
                    long number  = Coding.decodeVarint64(data, offset);
                    long size    = Coding.decodeVarint64(data, offset);
                    Slice small  = Coding.decodeLengthPrefixedSlice(data, offset);
                    Slice large  = Coding.decodeLengthPrefixedSlice(data, offset);
                    edit.newFilesMeta.add(new long[]{level, number, size});
                    edit.newFilesKeys.add(new Slice[]{small, large});
                    break;
                }
                default:
                    // 未知 tag：停止解析（容错）
                    offset[0] = data.length;
                    break;
            }
        }
        return edit;
    }
}
