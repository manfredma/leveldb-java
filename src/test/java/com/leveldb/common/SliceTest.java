package com.leveldb.common;

import org.junit.Test;
import static org.junit.Assert.*;

public class SliceTest {
    @Test
    public void testBasicConstruction() {
        Slice s = new Slice(new byte[]{'h','e','l','l','o'});
        assertEquals(5, s.length());
        assertEquals('h', s.getByte(0));
        assertEquals('o', s.getByte(4));
    }

    @Test
    public void testFromString() {
        Slice s = Slice.from("hello");
        assertEquals(5, s.length());
        assertEquals("hello", s.toString());
    }

    @Test
    public void testSliceSubrange() {
        Slice s = Slice.from("hello world");
        Slice sub = s.slice(6, 5);
        assertEquals("world", sub.toString());
    }

    @Test
    public void testCompare() {
        Slice a = Slice.from("apple");
        Slice b = Slice.from("banana");
        Slice c = Slice.from("apple");
        assertTrue(a.compareTo(b) < 0);
        assertTrue(b.compareTo(a) > 0);
        assertEquals(0, a.compareTo(c));
    }

    @Test
    public void testGetBytes() {
        Slice s = Slice.from("abc");
        byte[] bytes = s.getBytes();
        assertArrayEquals(new byte[]{'a','b','c'}, bytes);
    }

    @Test
    public void testStartsWith() {
        Slice s = Slice.from("hello");
        assertTrue(s.startsWith(Slice.from("hel")));
        assertFalse(s.startsWith(Slice.from("world")));
        assertTrue(s.startsWith(Slice.from("")));
    }
}
