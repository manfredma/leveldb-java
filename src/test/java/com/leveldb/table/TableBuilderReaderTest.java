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
}
