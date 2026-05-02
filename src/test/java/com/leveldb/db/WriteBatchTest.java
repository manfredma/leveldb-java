package com.leveldb.db;

import com.leveldb.common.Slice;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class WriteBatchTest {
    @Test
    public void testPutAndIterate() {
        WriteBatch batch = new WriteBatch();
        batch.put(Slice.from("k1"), Slice.from("v1"));
        batch.put(Slice.from("k2"), Slice.from("v2"));
        assertEquals(2, batch.count());
        List<String> keys = new ArrayList<>();
        batch.forEach(new WriteBatch.Handler() {
            public void put(Slice key, Slice value) { keys.add(key.toString()); }
            public void delete(Slice key) {}
        });
        assertEquals(Arrays.asList("k1","k2"), keys);
    }

    @Test
    public void testDeleteAndIterate() {
        WriteBatch batch = new WriteBatch();
        batch.delete(Slice.from("k1"));
        assertEquals(1, batch.count());
        boolean[] deleted = {false};
        batch.forEach(new WriteBatch.Handler() {
            public void put(Slice key, Slice value) {}
            public void delete(Slice key) { deleted[0] = true; }
        });
        assertTrue(deleted[0]);
    }

    @Test
    public void testSequenceNumberPersistence() {
        WriteBatch batch = new WriteBatch();
        batch.setSequenceNumber(12345L);
        assertEquals(12345L, batch.getSequenceNumber());
    }

    @Test
    public void testEncodeDecodeRoundtrip() {
        WriteBatch batch = new WriteBatch();
        batch.setSequenceNumber(99L);
        batch.put(Slice.from("a"), Slice.from("1"));
        batch.delete(Slice.from("b"));
        byte[] encoded = batch.getContents();
        WriteBatch decoded = WriteBatch.fromBytes(encoded);
        assertEquals(99L, decoded.getSequenceNumber());
        assertEquals(2, decoded.count());
        List<String> ops = new ArrayList<>();
        decoded.forEach(new WriteBatch.Handler() {
            public void put(Slice key, Slice value) { ops.add("PUT:" + key + "=" + value); }
            public void delete(Slice key) { ops.add("DEL:" + key); }
        });
        assertEquals("PUT:a=1", ops.get(0));
        assertEquals("DEL:b", ops.get(1));
    }

    @Test
    public void testClearBatch() {
        WriteBatch batch = new WriteBatch();
        batch.put(Slice.from("k"), Slice.from("v"));
        batch.clear();
        assertEquals(0, batch.count());
    }
}
