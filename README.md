# leveldb-java

A complete Java implementation of [LevelDB](https://github.com/google/leveldb) — Google's fast key-value storage library. This project faithfully ports the C++ source to pure Java, preserving the original architecture and algorithms.

## Features

- **LSM-Tree storage engine** — write-optimized with MemTable + SSTable layering
- **Write-Ahead Log (WAL)** — crash recovery via log replay
- **Bloom filters** — probabilistic key lookup to minimize disk I/O
- **LRU block cache** — configurable in-memory block caching
- **Multi-level compaction** — background compaction keeps read amplification low
- **Snapshot reads** — point-in-time consistent reads
- **Batch writes** — atomic multi-key write operations
- **Full iterator API** — forward scan with seek support

## Architecture

```
┌─────────────────────────────────────┐
│             DB (Public API)          │
│          put / get / delete          │
│          write / iterator            │
└──────────────┬──────────────────────┘
               │
       ┌───────▼────────┐
       │    MemTable     │  ← active writes (SkipList)
       │    Immutable    │  ← flushing to disk
       └───────┬─────────┘
               │ flush
       ┌───────▼─────────────────────┐
       │         SSTable Files        │
       │  Level 0 → Level 1 → ...     │  ← sorted, immutable
       └──────────────────────────────┘
               ▲
       ┌───────┴────────┐
       │  VersionSet     │  ← tracks file metadata across compactions
       └─────────────────┘
```

**Package layout:**

| Package | Description |
|---------|-------------|
| `com.leveldb.db` | Public API (`DB`, `DBImpl`), options, version management |
| `com.leveldb.memtable` | `SkipList` + `MemTable` (in-memory write buffer) |
| `com.leveldb.table` | SSTable read/write (`Table`, `TableBuilder`, `FilterBlock`) |
| `com.leveldb.log` | Write-Ahead Log (`LogWriter`, `LogReader`) |
| `com.leveldb.iterator` | Iterator interface, `MergingIterator`, `TwoLevelIterator` |
| `com.leveldb.util` | `BloomFilter`, `LRUCache`, `Coding`, `Crc32c` |
| `com.leveldb.common` | `Slice`, `Status`, `InternalKey`, comparators |

## Requirements

- Java 8+
- Maven 3.6+

## Build

```bash
mvn clean package -DskipTests
```

## Run Tests

```bash
mvn clean test
```

96 tests covering all major components.

## Usage

```java
import com.leveldb.db.DB;
import com.leveldb.db.Options;
import com.leveldb.db.ReadOptions;
import com.leveldb.db.WriteOptions;
import com.leveldb.common.Slice;

Options options = new Options();
options.createIfMissing = true;

try (DB db = DB.open(options, "/tmp/mydb")) {
    WriteOptions wo = new WriteOptions();
    ReadOptions ro = new ReadOptions();

    // write
    db.put(wo, new Slice("hello"), new Slice("world"));

    // read
    byte[] value = db.get(ro, new Slice("hello"));
    System.out.println(new String(value)); // "world"

    // delete
    db.delete(wo, new Slice("hello"));

    // batch write
    WriteBatch batch = new WriteBatch();
    batch.put(new Slice("k1"), new Slice("v1"));
    batch.put(new Slice("k2"), new Slice("v2"));
    db.write(wo, batch);

    // iterate
    try (Iterator it = db.newIterator(ro)) {
        for (it.seekToFirst(); it.valid(); it.next()) {
            System.out.println(it.key() + " = " + it.value());
        }
    }
}
```

## Design Notes

This implementation follows the original LevelDB C++ source closely:

- `DBImpl` maps to `db/db_impl.cc` — single background thread for compaction/flush
- `SkipList` uses a probabilistic data structure for O(log n) MemTable operations  
- SSTable format is identical to the original (block-based with optional Bloom filter)
- WAL uses a record-based format with CRC32c checksums for integrity
- Version management tracks file-level metadata across compactions via `VersionEdit`

## License

Apache License 2.0
