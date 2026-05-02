package com.leveldb.memtable;

import com.leveldb.common.Slice;
import com.leveldb.common.BytewiseComparator;
import com.leveldb.common.Comparator;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class SkipListTest {
    private final Comparator cmp = BytewiseComparator.INSTANCE;

    private SkipList<Slice> newList() {
        return new SkipList<>(cmp);
    }

    @Test
    public void testEmptyIterator() {
        SkipList<Slice> list = newList();
        SkipList<Slice>.SkipListIterator it = list.iterator();
        assertFalse(it.valid());
        it.seekToFirst();
        assertFalse(it.valid());
        it.seekToLast();
        assertFalse(it.valid());
    }

    @Test
    public void testInsertAndContains() {
        SkipList<Slice> list = newList();
        String[] keys = {"banana", "apple", "cherry", "date", "elderberry"};
        for (String k : keys) list.insert(Slice.from(k));
        for (String k : keys) assertTrue("contains " + k, list.contains(Slice.from(k)));
        assertFalse(list.contains(Slice.from("fig")));
    }

    @Test
    public void testForwardIteration() {
        SkipList<Slice> list = newList();
        String[] keys = {"dog", "cat", "ant", "bird", "elephant"};
        for (String k : keys) list.insert(Slice.from(k));
        List<String> sorted = Arrays.asList(keys.clone());
        Collections.sort(sorted);

        SkipList<Slice>.SkipListIterator it = list.iterator();
        it.seekToFirst();
        for (String expected : sorted) {
            assertTrue(it.valid());
            assertEquals(expected, it.key().toString());
            it.next();
        }
        assertFalse(it.valid());
    }

    @Test
    public void testSeek() {
        SkipList<Slice> list = newList();
        for (int i = 0; i < 10; i++) list.insert(Slice.from(String.format("%03d", i)));
        SkipList<Slice>.SkipListIterator it = list.iterator();
        it.seek(Slice.from("005"));
        assertTrue(it.valid());
        assertEquals("005", it.key().toString());
        it.seek(Slice.from("0055"));
        assertEquals("006", it.key().toString());
    }

    @Test
    public void testSeekToLast() {
        SkipList<Slice> list = newList();
        list.insert(Slice.from("aaa"));
        list.insert(Slice.from("zzz"));
        list.insert(Slice.from("mmm"));
        SkipList<Slice>.SkipListIterator it = list.iterator();
        it.seekToLast();
        assertTrue(it.valid());
        assertEquals("zzz", it.key().toString());
    }

    @Test
    public void testPrevIteration() {
        SkipList<Slice> list = newList();
        for (int i = 0; i < 5; i++) list.insert(Slice.from("key" + i));
        SkipList<Slice>.SkipListIterator it = list.iterator();
        it.seekToLast();
        List<String> result = new ArrayList<>();
        while (it.valid()) {
            result.add(it.key().toString());
            it.prev();
        }
        assertEquals(5, result.size());
        for (int i = 0; i < result.size() - 1; i++) {
            assertTrue(result.get(i).compareTo(result.get(i+1)) > 0);
        }
    }

    @Test
    public void testLargeInsert() {
        SkipList<Slice> list = newList();
        int N = 10000;
        for (int i = 0; i < N; i++) list.insert(Slice.from(String.format("%08d", i)));
        SkipList<Slice>.SkipListIterator it = list.iterator();
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
    }

    @Test
    public void testConcurrentReadsWhileWriting() throws InterruptedException {
        SkipList<Slice> list = newList();
        for (int i = 0; i < 1000; i++) list.insert(Slice.from(String.format("%06d", i)));

        AtomicBoolean failed = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            latch.countDown();
            for (int i = 1000; i < 2000; i++) list.insert(Slice.from(String.format("%06d", i)));
        });
        List<Thread> readers = new ArrayList<>();
        for (int r = 0; r < 5; r++) {
            readers.add(new Thread(() -> {
                try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                for (int iter = 0; iter < 100; iter++) {
                    SkipList<Slice>.SkipListIterator it = list.iterator();
                    it.seekToFirst();
                    String prev2 = null;
                    while (it.valid()) {
                        String cur = it.key().toString();
                        if (prev2 != null && prev2.compareTo(cur) >= 0) {
                            failed.set(true);
                        }
                        prev2 = cur;
                        it.next();
                    }
                }
            }));
        }
        writer.start(); readers.forEach(Thread::start);
        writer.join();
        for (Thread t : readers) t.join();
        assertFalse("Concurrent read detected ordering violation", failed.get());
    }
}
