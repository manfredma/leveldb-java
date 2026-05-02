package com.leveldb.db;

/**
 * 快照句柄。对应 C++ db/snapshot.h SnapshotImpl。
 */
public class Snapshot {
    public final long sequenceNumber;
    Snapshot prev;
    Snapshot next;

    Snapshot(long sequenceNumber) { this.sequenceNumber = sequenceNumber; }
}
