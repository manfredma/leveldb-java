package com.leveldb.table;

import com.leveldb.common.*;
import com.leveldb.iterator.Iterator;
import org.junit.Test;
import static org.junit.Assert.*;

public class BlockBuilderTest {
    @Test
    public void testEmptyBlock() {
        BlockBuilder b = new BlockBuilder(16);
        Slice data = b.finish();
        // 空 block = 1 个 restart point（restart[0]=0，4字节）+ num_restarts=1（4字节）= 8 字节
        assertEquals(8, data.length());
        assertTrue(data.length() >= 4);
    }

    @Test
    public void testSingleEntry() {
        BlockBuilder b = new BlockBuilder(16);
        b.add(Slice.from("key"), Slice.from("value"));
        Slice data = b.finish();
        Block block = new Block(new BlockContents(data, false, false));
        Iterator it = block.newIterator(BytewiseComparator.INSTANCE);
        it.seekToFirst();
        assertTrue(it.valid());
        assertEquals("key", it.key().toString());
        assertEquals("value", it.value().toString());
        it.next();
        assertFalse(it.valid());
    }

    @Test
    public void testPrefixCompression() {
        BlockBuilder b = new BlockBuilder(4);
        b.add(Slice.from("apple"), Slice.from("v1"));
        b.add(Slice.from("apply"), Slice.from("v2"));
        b.add(Slice.from("application"), Slice.from("v3"));
        Slice data = b.finish();
        Block block = new Block(new BlockContents(data, false, false));
        Iterator it = block.newIterator(BytewiseComparator.INSTANCE);
        it.seekToFirst();
        assertEquals("apple", it.key().toString());
        it.next(); assertEquals("apply", it.key().toString());
        it.next(); assertEquals("application", it.key().toString());
        it.next(); assertFalse(it.valid());
    }

    @Test
    public void testRestartPoints() {
        BlockBuilder b = new BlockBuilder(3);
        for (int i = 0; i < 9; i++) b.add(Slice.from(String.format("key%03d", i)), Slice.from("v"));
        Slice data = b.finish();
        Block block = new Block(new BlockContents(data, false, false));
        assertEquals(3, block.numRestarts());
    }

    @Test
    public void testSeekBinarySearch() {
        BlockBuilder b = new BlockBuilder(4);
        for (int i = 0; i < 20; i++) b.add(Slice.from(String.format("key%03d", i)), Slice.from("v" + i));
        Slice data = b.finish();
        Block block = new Block(new BlockContents(data, false, false));
        Iterator it = block.newIterator(BytewiseComparator.INSTANCE);
        it.seek(Slice.from("key010"));
        assertTrue(it.valid());
        assertEquals("key010", it.key().toString());
        assertEquals("v10", it.value().toString());
    }

    @Test
    public void testForwardIteration100Keys() {
        BlockBuilder b = new BlockBuilder(16);
        for (int i = 0; i < 100; i++) b.add(Slice.from(String.format("%05d", i)), Slice.from("val" + i));
        Slice data = b.finish();
        Block block = new Block(new BlockContents(data, false, false));
        Iterator it = block.newIterator(BytewiseComparator.INSTANCE);
        it.seekToFirst();
        int count = 0;
        String prev = null;
        while (it.valid()) {
            String cur = it.key().toString();
            if (prev != null) assertTrue(prev.compareTo(cur) < 0);
            prev = cur;
            count++;
            it.next();
        }
        assertEquals(100, count);
    }
}
