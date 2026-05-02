package com.leveldb.table;

import com.leveldb.common.Comparator;
import com.leveldb.common.Slice;
import com.leveldb.common.Status;
import com.leveldb.iterator.Iterator;
import com.leveldb.util.Coding;

/**
 * 从字节数据中读取有序 key-value Block，支持二分查找。
 * 对应 C++ table/block.h + block.cc。
 */
public class Block {
    private final byte[] data;
    private final int restartOffset;
    private final int numRestarts;

    public Block(BlockContents contents) {
        this.data = contents.data.getBytes();
        this.numRestarts = readNumRestarts(data);
        this.restartOffset = data.length - (numRestarts + 1) * 4;
    }

    public int size() { return data.length; }
    public int numRestarts() { return numRestarts; }

    private static int readNumRestarts(byte[] data) {
        if (data.length < 4) return 0;
        return Coding.decodeFixed32(data, data.length - 4);
    }

    private int getRestartPoint(int index) {
        return Coding.decodeFixed32(data, restartOffset + index * 4);
    }

    public Iterator newIterator(Comparator comparator) {
        return new BlockIterator(comparator);
    }

    private class BlockIterator implements Iterator {
        private final Comparator cmp;
        private int current;
        private Slice curKey, curValue;

        BlockIterator(Comparator cmp) {
            this.cmp = cmp;
            this.current = -1;
        }

        @Override public boolean valid() { return current >= 0 && current < restartOffset; }
        @Override public Slice key()     { assert valid(); return curKey; }
        @Override public Slice value()   { assert valid(); return curValue; }
        @Override public Status status() { return Status.ok(); }

        @Override
        public void seekToFirst() {
            if (restartOffset > 0) {
                current = 0;
                decodeEntry(0, new byte[0]);
            } else {
                current = -1;
            }
        }

        @Override
        public void seekToLast() {
            if (numRestarts == 0) { current = -1; return; }
            // 找最后一个 restart point，然后扫到文件末尾
            current = getRestartPoint(numRestarts - 1);
            byte[] prevKey = new byte[0];
            while (true) {
                int[] next = {0};
                byte[] fk = decodeEntryAt(current, prevKey, next);
                if (fk == null || next[0] >= restartOffset) break;
                prevKey = fk;
                current = next[0];
            }
            if (current < restartOffset) decodeEntry(current, prevKey);
            else current = -1;
        }

        @Override
        public void seek(Slice target) {
            int left = 0, right = numRestarts - 1;
            while (left < right) {
                int mid = (left + right + 1) / 2;
                int rp = getRestartPoint(mid);
                int[] tmpOff = {rp};
                Coding.decodeVarint32(data, tmpOff); // shared = 0
                int unshared = Coding.decodeVarint32(data, tmpOff);
                Coding.decodeVarint32(data, tmpOff); // value_len
                Slice rpKey = new Slice(data, tmpOff[0], unshared);
                if (cmp.compare(rpKey, target) < 0) left = mid;
                else right = mid - 1;
            }

            current = getRestartPoint(left);
            byte[] prevKey = new byte[0];
            while (current < restartOffset) {
                int[] next = {0};
                byte[] fullKey = decodeEntryAt(current, prevKey, next);
                if (fullKey == null) { current = -1; return; }
                Slice sk = new Slice(fullKey);
                if (cmp.compare(sk, target) >= 0) {
                    decodeEntry(current, prevKey);
                    return;
                }
                prevKey = fullKey;
                current = next[0];
            }
            current = -1;
        }

        @Override
        public void next() {
            assert valid();
            byte[] prevKey = curKey.getBytes();
            int[] next = {0};
            decodeEntryAt(current, prevKey, next);
            if (next[0] <= 0 || next[0] >= restartOffset) { current = -1; return; }
            current = next[0];
            decodeEntry(current, prevKey);
        }

        @Override
        public void prev() {
            int target = current;
            int restartIdx = 0;
            for (int i = numRestarts - 1; i >= 0; i--) {
                if (getRestartPoint(i) < target) { restartIdx = i; break; }
            }
            current = getRestartPoint(restartIdx);
            byte[] prevKey = new byte[0];
            int lastValidOffset = -1;
            byte[] lastValidPrev = new byte[0];
            while (current < target) {
                int[] next = {0};
                byte[] fk = decodeEntryAt(current, prevKey, next);
                if (fk == null) break;
                if (next[0] >= target) break;
                lastValidOffset = current;
                lastValidPrev = prevKey;
                prevKey = fk;
                current = next[0];
            }
            if (lastValidOffset >= 0) {
                current = lastValidOffset;
                decodeEntry(current, lastValidPrev);
            } else {
                current = -1;
            }
        }

        @Override public void close() {}

        private void decodeEntry(int offset, byte[] prevKey) {
            int[] pos = {offset};
            int shared   = Coding.decodeVarint32(data, pos);
            int unshared = Coding.decodeVarint32(data, pos);
            int vlen     = Coding.decodeVarint32(data, pos);
            byte[] fullKey = new byte[shared + unshared];
            if (shared > 0) System.arraycopy(prevKey, 0, fullKey, 0, Math.min(shared, prevKey.length));
            System.arraycopy(data, pos[0], fullKey, shared, unshared);
            curKey   = new Slice(fullKey);
            curValue = new Slice(data, pos[0] + unshared, vlen);
        }

        private byte[] decodeEntryAt(int offset, byte[] prevKey, int[] next) {
            if (offset < 0 || offset >= restartOffset) return null;
            int[] pos = {offset};
            int shared   = Coding.decodeVarint32(data, pos);
            int unshared = Coding.decodeVarint32(data, pos);
            int vlen     = Coding.decodeVarint32(data, pos);
            byte[] fullKey = new byte[shared + unshared];
            if (shared > 0) System.arraycopy(prevKey, 0, fullKey, 0, Math.min(shared, prevKey.length));
            System.arraycopy(data, pos[0], fullKey, shared, unshared);
            next[0] = pos[0] + unshared + vlen;
            return fullKey;
        }
    }
}
