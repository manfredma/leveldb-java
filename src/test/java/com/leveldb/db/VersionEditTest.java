package com.leveldb.db;

import com.leveldb.common.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class VersionEditTest {
    @Test
    public void testEncodeDecodeEmpty() throws Exception {
        VersionEdit edit = new VersionEdit();
        byte[] encoded = edit.encodeTo();
        VersionEdit decoded = VersionEdit.decodeFrom(encoded);
        assertNotNull(decoded);
    }

    @Test
    public void testComparatorName() throws Exception {
        VersionEdit edit = new VersionEdit();
        edit.setComparatorName("leveldb.BytewiseComparator");
        byte[] enc = edit.encodeTo();
        VersionEdit decoded = VersionEdit.decodeFrom(enc);
        assertEquals("leveldb.BytewiseComparator", decoded.comparatorName());
    }

    @Test
    public void testLogNumbers() throws Exception {
        VersionEdit edit = new VersionEdit();
        edit.setLogNumber(42L);
        edit.setNextFileNumber(100L);
        edit.setLastSequence(999L);
        byte[] enc = edit.encodeTo();
        VersionEdit decoded = VersionEdit.decodeFrom(enc);
        assertEquals(42L, (long) decoded.logNumber());
        assertEquals(100L, (long) decoded.nextFileNumber());
        assertEquals(999L, (long) decoded.lastSequence());
    }

    @Test
    public void testAddAndRemoveFiles() throws Exception {
        VersionEdit edit = new VersionEdit();
        byte[] smallest = InternalKey.encode(Slice.from("aaa"), 1L, ValueType.VALUE);
        byte[] largest  = InternalKey.encode(Slice.from("zzz"), 1L, ValueType.VALUE);
        edit.addFile(1, 42L, 1024L, new Slice(smallest), new Slice(largest));
        edit.removeFile(0, 10L);
        byte[] enc = edit.encodeTo();
        VersionEdit decoded = VersionEdit.decodeFrom(enc);
        assertEquals(1, decoded.newFilesMeta().size());
        assertEquals(1, decoded.deletedFiles().size());
    }
}
