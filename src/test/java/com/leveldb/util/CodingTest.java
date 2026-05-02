package com.leveldb.util;

import com.leveldb.common.Slice;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class CodingTest {
    @Test
    public void testFixed32LittleEndian() {
        byte[] buf = new byte[4];
        Coding.encodeFixed32(buf, 0, 0x01020304);
        assertArrayEquals(new byte[]{0x04, 0x03, 0x02, 0x01}, buf);
        assertEquals(0x01020304, Coding.decodeFixed32(buf, 0));
    }

    @Test
    public void testFixed32Boundaries() {
        int[] vals = {0, 1, 0x7FFFFFFF, 0x80000000, 0xFFFFFFFF};
        byte[] buf = new byte[4];
        for (int v : vals) {
            Coding.encodeFixed32(buf, 0, v);
            assertEquals(v, Coding.decodeFixed32(buf, 0));
        }
    }

    @Test
    public void testFixed64Boundaries() {
        long[] vals = {0L, 1L, Long.MAX_VALUE, Long.MIN_VALUE, -1L};
        byte[] buf = new byte[8];
        for (long v : vals) {
            Coding.encodeFixed64(buf, 0, v);
            assertEquals(v, Coding.decodeFixed64(buf, 0));
        }
    }

    @Test
    public void testVarint32RoundTrip() throws IOException {
        int[] vals = {0, 1, 127, 128, 16383, 16384, Integer.MAX_VALUE};
        for (int v : vals) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Coding.encodeVarint32(out, v);
            byte[] bytes = out.toByteArray();
            int[] offset = {0};
            int decoded = Coding.decodeVarint32(bytes, offset);
            assertEquals("value=" + v, v, decoded);
            assertEquals(bytes.length, offset[0]);
        }
    }

    @Test
    public void testVarint64RoundTrip() throws IOException {
        long[] vals = {0L, 1L, 127L, 128L, Long.MAX_VALUE};
        for (long v : vals) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Coding.encodeVarint64(out, v);
            byte[] bytes = out.toByteArray();
            int[] offset = {0};
            long decoded = Coding.decodeVarint64(bytes, offset);
            assertEquals("value=" + v, v, decoded);
        }
    }

    @Test
    public void testVarintLength() {
        assertEquals(1, Coding.varintLength(0));
        assertEquals(1, Coding.varintLength(127));
        assertEquals(2, Coding.varintLength(128));
        assertEquals(2, Coding.varintLength(16383));
        assertEquals(3, Coding.varintLength(16384));
        assertEquals(5, Coding.varintLength(0xFFFFFFFFL));
        assertEquals(9, Coding.varintLength(Long.MAX_VALUE));
    }

    @Test
    public void testLengthPrefixedSlice() throws IOException {
        Slice s = Slice.from("hello");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Coding.encodeLengthPrefixedSlice(out, s);
        byte[] bytes = out.toByteArray();
        int[] offset = {0};
        Slice decoded = Coding.decodeLengthPrefixedSlice(bytes, offset);
        assertEquals(s, decoded);
    }
}
