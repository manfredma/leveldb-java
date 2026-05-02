package com.leveldb.db;

import com.leveldb.common.*;
import com.leveldb.iterator.Iterator;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class DBImplTest {
    @Rule public TemporaryFolder tmpDir = new TemporaryFolder();
    private String dbPath;
    private DB db;

    @Before
    public void setUp() throws Exception {
        dbPath = tmpDir.newFolder("testdb").getAbsolutePath();
        Options opts = new Options();
        opts.createIfMissing = true;
        db = DB.open(opts, dbPath);
    }

    @After
    public void tearDown() throws Exception {
        if (db != null) db.close();
    }

    @Test
    public void testOpenAndClose() {
        assertNotNull(db);
    }

    @Test
    public void testPutAndGet() throws Exception {
        db.put(new WriteOptions(), Slice.from("key"), Slice.from("value"));
        byte[] result = db.get(new ReadOptions(), Slice.from("key"));
        assertNotNull(result);
        assertEquals("value", new String(result, "UTF-8"));
    }

    @Test
    public void testGetNonExistent() throws Exception {
        byte[] result = db.get(new ReadOptions(), Slice.from("missing"));
        assertNull(result);
    }

    @Test
    public void testDeleteAndGet() throws Exception {
        db.put(new WriteOptions(), Slice.from("k"), Slice.from("v"));
        db.delete(new WriteOptions(), Slice.from("k"));
        assertNull(db.get(new ReadOptions(), Slice.from("k")));
    }

    @Test
    public void testOverwrite() throws Exception {
        db.put(new WriteOptions(), Slice.from("k"), Slice.from("v1"));
        db.put(new WriteOptions(), Slice.from("k"), Slice.from("v2"));
        byte[] result = db.get(new ReadOptions(), Slice.from("k"));
        assertEquals("v2", new String(result, "UTF-8"));
    }

    @Test
    public void testWriteBatch() throws Exception {
        WriteBatch batch = new WriteBatch();
        batch.put(Slice.from("a"), Slice.from("1"));
        batch.put(Slice.from("b"), Slice.from("2"));
        batch.delete(Slice.from("c"));
        db.put(new WriteOptions(), Slice.from("c"), Slice.from("old"));
        db.write(new WriteOptions(), batch);
        assertEquals("1", new String(db.get(new ReadOptions(), Slice.from("a")), "UTF-8"));
        assertEquals("2", new String(db.get(new ReadOptions(), Slice.from("b")), "UTF-8"));
        assertNull(db.get(new ReadOptions(), Slice.from("c")));
    }

    @Test
    public void testSnapshot() throws Exception {
        db.put(new WriteOptions(), Slice.from("k"), Slice.from("v1"));
        Snapshot snap = db.getSnapshot();
        db.put(new WriteOptions(), Slice.from("k"), Slice.from("v2"));
        ReadOptions snapOpts = new ReadOptions();
        snapOpts.snapshot = snap;
        assertEquals("v1", new String(db.get(snapOpts, Slice.from("k")), "UTF-8"));
        assertEquals("v2", new String(db.get(new ReadOptions(), Slice.from("k")), "UTF-8"));
        db.releaseSnapshot(snap);
    }

    @Test
    public void testIterator() throws Exception {
        String[] keys = {"c", "a", "b", "e", "d"};
        for (String k : keys) db.put(new WriteOptions(), Slice.from(k), Slice.from("v_" + k));
        Iterator it = db.newIterator(new ReadOptions());
        it.seekToFirst();
        String prev = null;
        int count = 0;
        while (it.valid()) {
            String cur = it.key().toString();
            if (prev != null) assertTrue("Expected " + prev + " < " + cur, prev.compareTo(cur) < 0);
            prev = cur;
            count++;
            it.next();
        }
        assertEquals(5, count);
        it.close();
    }

    @Test
    public void testPersistenceAfterReopen() throws Exception {
        db.put(new WriteOptions(), Slice.from("persistent"), Slice.from("data"));
        db.close();
        Options opts = new Options();
        opts.createIfMissing = false;
        db = DB.open(opts, dbPath);
        byte[] result = db.get(new ReadOptions(), Slice.from("persistent"));
        assertNotNull(result);
        assertEquals("data", new String(result, "UTF-8"));
    }

    @Test
    public void testLargeWrite() throws Exception {
        int N = 10000;
        for (int i = 0; i < N; i++) {
            db.put(new WriteOptions(), Slice.from(String.format("%08d", i)), Slice.from("value" + i));
        }
        for (int i = 0; i < N; i += 1000) {
            byte[] v = db.get(new ReadOptions(), Slice.from(String.format("%08d", i)));
            assertNotNull("key " + i + " not found", v);
            assertEquals("value" + i, new String(v, "UTF-8"));
        }
    }

    @Test
    public void testGetProperty() throws Exception {
        db.put(new WriteOptions(), Slice.from("k"), Slice.from("v"));
        String prop = db.getProperty("leveldb.num-files-at-level0");
        assertNotNull(prop);
        assertTrue(Integer.parseInt(prop) >= 0);
        String stats = db.getProperty("leveldb.stats");
        assertNotNull(stats);
    }
}
