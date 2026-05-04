# leveldb-java

[LevelDB](https://github.com/google/leveldb)（Google 高性能键值存储引擎）的完整 Java 实现。本项目忠实移植 C++ 源码，保留原始架构与算法。

## 特性

- **LSM-Tree 存储引擎** — 写优化，MemTable + SSTable 分层存储
- **预写日志（WAL）** — 通过日志回放实现崩溃恢复
- **布隆过滤器** — 概率性键查找，减少磁盘 I/O
- **LRU 块缓存** — 可配置大小的内存块缓存
- **多级 Compaction** — 后台压缩保持低读放大
- **快照读取** — 支持时间点一致性读
- **批量写入** — 多键原子写操作
- **完整迭代器 API** — 支持 seek 的正向扫描

## 架构

```
┌─────────────────────────────────────┐
│           DB（公开 API）              │
│       put / get / delete             │
│       write / iterator               │
└──────────────┬──────────────────────┘
               │
       ┌───────▼────────┐
       │    MemTable     │  ← 活跃写入（SkipList）
       │    Immutable    │  ← 等待刷盘
       └───────┬─────────┘
               │ flush
       ┌───────▼─────────────────────┐
       │       SSTable 文件           │
       │  Level 0 → Level 1 → ...     │  ← 有序、不可变
       └──────────────────────────────┘
               ▲
       ┌───────┴────────┐
       │  VersionSet     │  ← 跨 compaction 管理文件元数据
       └─────────────────┘
```

**包结构：**

| 包 | 说明 |
|----|------|
| `com.leveldb.db` | 公开 API（`DB`、`DBImpl`）、选项、版本管理 |
| `com.leveldb.memtable` | `SkipList` + `MemTable`（内存写缓冲） |
| `com.leveldb.table` | SSTable 读写（`Table`、`TableBuilder`、`FilterBlock`） |
| `com.leveldb.log` | 预写日志（`LogWriter`、`LogReader`） |
| `com.leveldb.iterator` | Iterator 接口、`MergingIterator`、`TwoLevelIterator` |
| `com.leveldb.util` | `BloomFilter`、`LRUCache`、`Coding`、`Crc32c` |
| `com.leveldb.common` | `Slice`、`Status`、`InternalKey`、比较器 |

## 环境要求

- Java 8+
- Maven 3.6+

## 构建

```bash
mvn clean package -DskipTests
```

## 运行测试

```bash
mvn clean test
```

共 96 个测试，覆盖所有核心组件。

## 使用示例

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

    // 写入
    db.put(wo, new Slice("hello"), new Slice("world"));

    // 读取
    byte[] value = db.get(ro, new Slice("hello"));
    System.out.println(new String(value)); // "world"

    // 删除
    db.delete(wo, new Slice("hello"));

    // 批量写入
    WriteBatch batch = new WriteBatch();
    batch.put(new Slice("k1"), new Slice("v1"));
    batch.put(new Slice("k2"), new Slice("v2"));
    db.write(wo, batch);

    // 遍历
    try (Iterator it = db.newIterator(ro)) {
        for (it.seekToFirst(); it.valid(); it.next()) {
            System.out.println(it.key() + " = " + it.value());
        }
    }
}
```

## 实现说明

本实现与原 LevelDB C++ 源码高度对应：

- `DBImpl` 对应 `db/db_impl.cc` — 单后台线程执行 compaction/flush
- `SkipList` 使用概率数据结构，MemTable 操作时间复杂度 O(log n)
- SSTable 格式与原版相同（分块存储，支持布隆过滤器）
- WAL 使用基于 Record 的格式，以 CRC32c 校验保证数据完整性
- 版本管理通过 `VersionEdit` 跨 compaction 追踪文件级元数据

## 许可证

Apache License 2.0
