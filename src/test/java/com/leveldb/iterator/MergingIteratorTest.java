package com.leveldb.iterator;

import com.leveldb.common.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class MergingIteratorTest {

    private Iterator fromList(List<Slice> keys) {
        return new Iterator() {
            int idx = -1;
            final List<Slice> sorted;
            { sorted = new ArrayList<>(keys); Collections.sort(sorted); }
            public void seekToFirst() { idx = sorted.isEmpty() ? -1 : 0; }
            public void seekToLast()  { idx = sorted.isEmpty() ? -1 : sorted.size() - 1; }
            public void seek(Slice t) {
                for (idx = 0; idx < sorted.size(); idx++) {
                    if (sorted.get(idx).compareTo(t) >= 0) return;
                }
                idx = -1;
            }
            public void next() { idx = (idx + 1 < sorted.size()) ? idx + 1 : -1; }
            public void prev() { idx = (idx > 0) ? idx - 1 : -1; }
            public boolean valid() { return idx >= 0 && idx < sorted.size(); }
            public Slice key()   { return sorted.get(idx); }
            public Slice value() { return Slice.from("v"); }
            public Status status() { return Status.ok(); }
            public void close() {}
        };
    }

    @Test
    public void testMergeTwo() {
        Iterator a = fromList(Arrays.asList(Slice.from("a"), Slice.from("c"), Slice.from("e")));
        Iterator b = fromList(Arrays.asList(Slice.from("b"), Slice.from("d"), Slice.from("f")));
        MergingIterator merged = new MergingIterator(BytewiseComparator.INSTANCE, new Iterator[]{a, b});
        merged.seekToFirst();
        String[] expected = {"a","b","c","d","e","f"};
        for (String exp : expected) {
            assertTrue(merged.valid());
            assertEquals(exp, merged.key().toString());
            merged.next();
        }
        assertFalse(merged.valid());
    }

    @Test
    public void testMergeThreeWithDuplicates() {
        Iterator a = fromList(Arrays.asList(Slice.from("a"), Slice.from("b")));
        Iterator b = fromList(Arrays.asList(Slice.from("b"), Slice.from("c")));
        Iterator c = fromList(Arrays.asList(Slice.from("a"), Slice.from("c")));
        MergingIterator merged = new MergingIterator(BytewiseComparator.INSTANCE, new Iterator[]{a, b, c});
        merged.seekToFirst();
        List<String> result = new ArrayList<>();
        while (merged.valid()) { result.add(merged.key().toString()); merged.next(); }
        assertEquals(6, result.size());
        for (int i = 0; i < result.size() - 1; i++) {
            assertTrue(result.get(i).compareTo(result.get(i+1)) <= 0);
        }
    }

    @Test
    public void testSeek() {
        Iterator a = fromList(Arrays.asList(Slice.from("aaa"), Slice.from("ccc")));
        Iterator b = fromList(Arrays.asList(Slice.from("bbb"), Slice.from("ddd")));
        MergingIterator merged = new MergingIterator(BytewiseComparator.INSTANCE, new Iterator[]{a, b});
        merged.seek(Slice.from("bbb"));
        assertTrue(merged.valid());
        assertEquals("bbb", merged.key().toString());
    }

    @Test
    public void testEmptyIterators() {
        MergingIterator merged = new MergingIterator(BytewiseComparator.INSTANCE, new Iterator[0]);
        merged.seekToFirst();
        assertFalse(merged.valid());
    }
}
