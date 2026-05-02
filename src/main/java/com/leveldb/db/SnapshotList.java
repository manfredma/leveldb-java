package com.leveldb.db;

/**
 * 快照双向链表（按 sequenceNumber 升序）。对应 C++ db/snapshot.h SnapshotList。
 */
public class SnapshotList {
    private final Snapshot head;

    public SnapshotList() {
        head = new Snapshot(0);
        head.prev = head;
        head.next = head;
    }

    public boolean isEmpty() { return head.next == head; }
    public Snapshot oldest() { assert !isEmpty(); return head.next; }
    public Snapshot newest() { assert !isEmpty(); return head.prev; }

    public Snapshot newSnapshot(long seq) {
        Snapshot s = new Snapshot(seq);
        s.next = head;
        s.prev = head.prev;
        s.prev.next = s;
        s.next.prev = s;
        return s;
    }

    public void delete(Snapshot s) {
        s.prev.next = s.next;
        s.next.prev = s.prev;
    }
}
