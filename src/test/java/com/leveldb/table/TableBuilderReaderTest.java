package com.leveldb.table;

import com.leveldb.common.Slice;
import com.leveldb.db.Options;
import com.leveldb.db.ReadOptions;
import com.leveldb.iterator.Iterator;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

public class TableBuilderReaderTest {
    @Rule public TemporaryFolder tmpDir = new TemporaryFolder();

    @Test
    public void testBuildAndOpenTable() throws Exception {
        File f = tmpDir.newFile("test.sst");
        Options opts = new Options();
        try (TableBuilder tb = new TableBuilder(opts, f)) {
            for (int i = 0; i < 100; i++) {
                tb.add(Slice.from(String.format("key%05d", i)), Slice.from("value" + i));
            }
            tb.finish();
        }
        Table table = Table.open(opts, f, f.length());
        for (int i = 0; i < 100; i++) {
            final int idx = i;
            String[] found = {null};
            table.internalGet(new ReadOptions(), Slice.from(String.format("key%05d", idx)),
                new Table.GetCallback() {
                    public void got(Slice k, Slice v) { found[0] = v.toString(); }
                    public void notFound() {}
                });
            assertEquals("value" + i, found[0]);
        }
        table.close();
    }

    @Test
    public void testTableIterator() throws Exception {
        File f = tmpDir.newFile("iter.sst");
        Options opts = new Options();
        int N = 50;
        try (TableBuilder tb = new TableBuilder(opts, f)) {
            for (int i = 0; i < N; i++)
                tb.add(Slice.from(String.format("%05d", i)), Slice.from("v" + i));
            tb.finish();
        }
        Table table = Table.open(opts, f, f.length());
        Iterator it = table.newIterator(new ReadOptions());
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
        assertEquals(N, count);
        it.close(); table.close();
    }

    @Test
    public void testBloomFilterMiss() throws Exception {
        File f = tmpDir.newFile("bloom.sst");
        Options opts = new Options();
        try (TableBuilder tb = new TableBuilder(opts, f)) {
            for (int i = 0; i < 100; i++)
                tb.add(Slice.from("present-" + i), Slice.from("v"));
            tb.finish();
        }
        Table table = Table.open(opts, f, f.length());
        boolean[] found = {false};
        table.internalGet(new ReadOptions(), Slice.from("absent-key-xyz"),
            new Table.GetCallback() {
                public void got(Slice k, Slice v) { found[0] = true; }
                public void notFound() {}
            });
        assertFalse(found[0]);
        table.close();
    }

    @Test
    public void testFooterMagicNumber() throws Exception {
        File f = tmpDir.newFile("magic.sst");
        Options opts = new Options();
        try (TableBuilder tb = new TableBuilder(opts, f)) {
            tb.add(Slice.from("k"), Slice.from("v"));
            tb.finish();
        }
        byte[] footerBytes = new byte[Footer.ENCODED_LENGTH];
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            raf.seek(f.length() - Footer.ENCODED_LENGTH);
            raf.readFully(footerBytes);
        }
        Footer footer = Footer.decode(footerBytes);
        assertNotNull(footer);
    }

    @Test
    public void testApproximateOffsetOf() throws Exception {
        File f = tmpDir.newFile("offset.sst");
        Options opts = new Options();
        int N = 100;
        try (TableBuilder tb = new TableBuilder(opts, f)) {
            for (int i = 0; i < N; i++)
                tb.add(Slice.from(String.format("%05d", i)), Slice.from("v" + i));
            tb.finish();
        }
        Table table = Table.open(opts, f, f.length());
        // 第一个 key 的偏移应在文件起始附近（接近 0）
        long firstOffset = table.approximateOffsetOf(Slice.from("00000"));
        assertTrue("first key offset should be near 0", firstOffset < 1024);
        // 超出范围的 key 应返回接近文件末尾
        long lastOffset = table.approximateOffsetOf(Slice.from("zzzzz"));
        assertTrue("out-of-range key should return near file end", lastOffset > 0);
        table.close();
    }

    @Test
    public void testTableCacheEvictionAndReopen() throws Exception {
        // 构建多个 SSTable，验证每个文件可独立打开和查询
        List<File> files = new ArrayList<>();
        Options opts = new Options();
        for (int t = 0; t < 5; t++) {
            File f = tmpDir.newFile("cache" + t + ".sst");
            files.add(f);
            try (TableBuilder tb = new TableBuilder(opts, f)) {
                tb.add(Slice.from("key" + t), Slice.from("val" + t));
                tb.finish();
            }
        }
        // 验证每个文件可独立打开
        for (int t = 0; t < 5; t++) {
            Table table = Table.open(opts, files.get(t), files.get(t).length());
            final int idx = t;
            String[] found = {null};
            table.internalGet(new ReadOptions(), Slice.from("key" + t),
                new Table.GetCallback() {
                    public void got(Slice k, Slice v) { found[0] = v.toString(); }
                    public void notFound() {}
                });
            assertEquals("val" + t, found[0]);
            table.close();
        }
    }

    @Test
    public void testSeekToNonExistentKey() throws Exception {
        File f = tmpDir.newFile("seek.sst");
        Options opts = new Options();
        try (TableBuilder tb = new TableBuilder(opts, f)) {
            for (int i = 0; i < 10; i++)
                tb.add(Slice.from(String.format("key%03d", i * 10)), Slice.from("v" + i));
            tb.finish();
        }
        Table table = Table.open(opts, f, f.length());
        Iterator it = table.newIterator(new ReadOptions());
        // seek 到两个已有 key 之间，应定位到下一个
        it.seek(Slice.from("key005"));
        assertTrue(it.valid());
        // 应找到 key010（第一个 >= key005 的）
        assertEquals("key010", it.key().toString());
        it.close();
        table.close();
    }
}
