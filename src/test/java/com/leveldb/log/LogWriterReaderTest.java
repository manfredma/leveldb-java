package com.leveldb.log;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.*;
import java.util.*;

public class LogWriterReaderTest {
    @Rule public TemporaryFolder tmpDir = new TemporaryFolder();

    @Test
    public void testWriteAndReadSingleRecord() throws IOException {
        File f = tmpDir.newFile("test.log");
        byte[] data = "hello world".getBytes();
        try (LogWriter w = new LogWriter(f)) { w.addRecord(data); }
        try (LogReader r = new LogReader(f, false, 0)) {
            byte[] rec = r.readRecord();
            assertArrayEquals(data, rec);
            assertNull(r.readRecord());
        }
    }

    @Test
    public void testWriteAndReadMultipleRecords() throws IOException {
        File f = tmpDir.newFile("test.log");
        List<byte[]> records = new ArrayList<>();
        for (int i = 0; i < 10; i++) records.add(("record-" + i).getBytes());
        try (LogWriter w = new LogWriter(f)) {
            for (byte[] r : records) w.addRecord(r);
        }
        try (LogReader r = new LogReader(f, false, 0)) {
            for (byte[] expected : records) {
                byte[] got = r.readRecord();
                assertArrayEquals(expected, got);
            }
            assertNull(r.readRecord());
        }
    }

    @Test
    public void testEmptyRecord() throws IOException {
        File f = tmpDir.newFile("test.log");
        try (LogWriter w = new LogWriter(f)) { w.addRecord(new byte[0]); }
        try (LogReader r = new LogReader(f, false, 0)) {
            byte[] rec = r.readRecord();
            assertNotNull(rec);
            assertEquals(0, rec.length);
        }
    }

    @Test
    public void testRecordSpanningMultipleBlocks() throws IOException {
        File f = tmpDir.newFile("big.log");
        byte[] big = new byte[100 * 1024];
        new Random(42).nextBytes(big);
        try (LogWriter w = new LogWriter(f)) { w.addRecord(big); }
        try (LogReader r = new LogReader(f, false, 0)) {
            byte[] got = r.readRecord();
            assertArrayEquals(big, got);
        }
    }

    @Test
    public void testManySmallRecords() throws IOException {
        File f = tmpDir.newFile("many.log");
        Random rnd = new Random(123);
        List<byte[]> records = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            byte[] data = new byte[rnd.nextInt(100) + 1];
            rnd.nextBytes(data);
            records.add(data);
        }
        try (LogWriter w = new LogWriter(f)) {
            for (byte[] r : records) w.addRecord(r);
        }
        try (LogReader r = new LogReader(f, false, 0)) {
            for (byte[] expected : records) {
                byte[] got = r.readRecord();
                assertArrayEquals(expected, got);
            }
            assertNull(r.readRecord());
        }
    }

    @Test
    public void testRecordAtBlockBoundary() throws IOException {
        File f = tmpDir.newFile("boundary.log");
        byte[] fill = new byte[LogFormat.BLOCK_SIZE - LogFormat.HEADER_SIZE];
        Arrays.fill(fill, (byte)'A');
        byte[] second = "second".getBytes();
        try (LogWriter w = new LogWriter(f)) {
            w.addRecord(fill);
            w.addRecord(second);
        }
        try (LogReader r = new LogReader(f, false, 0)) {
            byte[] got1 = r.readRecord();
            assertArrayEquals(fill, got1);
            byte[] got2 = r.readRecord();
            assertArrayEquals(second, got2);
        }
    }
}
