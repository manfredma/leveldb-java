# LevelDB Java 完整实现设计文档

> **版本**: 1.0  
> **日期**: 2026-05-02  
> **目的**: 学习/教学，完全按照 Google LevelDB C++ 原版设计  
> **技术栈**: Maven + Java 8，JUnit 4 单元测试  
> **参考来源**: [google/leveldb](https://github.com/google/leveldb) C++ 源码

---

## 目录

1. [背景与目标](#1-背景与目标)
2. [整体架构](#2-整体架构)
3. [项目结构](#3-项目结构)
4. [核心数据格式](#4-核心数据格式)
5. [组件详细设计](#5-组件详细设计)
   - 5.1 [common — 公共基础层](#51-common--公共基础层)
   - 5.2 [util — 工具层](#52-util--工具层)
   - 5.3 [memtable — 内存层](#53-memtable--内存层)
   - 5.4 [log — WAL 日志层](#54-log--wal-日志层)
   - 5.5 [table — SSTable 文件层](#55-table--sstable-文件层)
   - 5.6 [iterator — 迭代器层](#56-iterator--迭代器层)
   - 5.7 [db — 主控层](#57-db--主控层)
6. [Compaction 策略](#6-compaction-策略)
7. [崩溃恢复流程](#7-崩溃恢复流程)
8. [测试计划](#8-测试计划)
9. [实现阶段划分](#9-实现阶段划分)

---

## 1. 背景与目标

### 1.1 LevelDB 简介

LevelDB 是 Google 开发的单机嵌入式键值存储引擎，基于 **LSM Tree（Log-Structured Merge Tree）** 数据结构。它的核心设计思想是：将随机写转化为顺序写，以牺牲部分读性能换取极高的写吞吐量。

**核心特性：**
- 有序的 key-value 存储（字典序）
- 支持批量原子写（WriteBatch）
- 支持快照读（Snapshot）
- 支持范围迭代（Iterator）
- 数据持久化，崩溃后可恢复

### 1.2 本项目目标

用 Java 8 完整实现 LevelDB 的核心存储引擎，目标是：

1. **代码清晰**：每个类的职责单一明确，注释详尽
2. **忠实原版**：数据格式、算法逻辑完全对标 C++ 原版
3. **可测试性**：每个核心组件都有独立的单元测试
4. **教学价值**：通过代码和注释完整展示 LSM Tree 的工作原理

### 1.3 版本说明

对应 C++ LevelDB v1.23，**简化点**（不影响核心原理的部分）：
- 不实现 Snappy/Zstd 压缩（默认 kNoCompression）
- 不实现 `RepairDB`（修复损坏数据库）
- 不实现 C API（`leveldb/c.h`）
- Env 层简化，直接使用 Java File API（不抽象 Env 接口）

---

## 2. 整体架构

### 2.1 LSM Tree 写入路径

```
用户调用 put(key, value)
        │
        ▼
  [WAL LogWriter]          ← 顺序写磁盘（持久化保证）
  写入 WAL 日志
        │
        ▼
  [MemTable]               ← 写入内存跳表（快速）
  (容量 < 4MB)
        │
        │ 达到 write_buffer_size（默认 4MB）
        ▼
  [Immutable MemTable]     ← 冻结，等待 flush
        │
        │ 后台线程 flush
        ▼
  [Level-0 SSTable]        ← 新建有序文件（磁盘）
        │
        │ Level-0 文件数 >= 4 时触发 compaction
        ▼
  [Level-1 SSTable]        ← 归并到下层（有序且无重叠）
        │
        │ Level-N 总大小超阈值时继续压缩
        ▼
  [Level-2 ~ Level-6]      ← 最深 6 层，逐层容量 10 倍增长
```

### 2.2 读取路径

```
用户调用 get(key)
        │
        ▼
  1. MemTable.get()        ← 最新数据，命中即返回
        │
        ▼
  2. Immutable MemTable    ← 次新数据
        │
        ▼
  3. Level-0 SSTable       ← 按文件新旧顺序查（Level-0 文件 key 可重叠）
        │
        ▼
  4. Level-1 SSTable       ← 二分查找文件（Level-1+ 文件 key 不重叠）
        │
        ▼
  5. Level-2 ~ Level-6     ← 逐层查找，直到找到或确认不存在
```

### 2.3 版本管理

每次 compaction 产生新的 `Version`（版本），版本之间通过 `VersionEdit` 描述变更（新增/删除哪些文件），MANIFEST 文件记录所有 VersionEdit，用于崩溃恢复。

---

## 3. 项目结构

### 3.1 Maven 项目结构

```
leveldb-java/
├── pom.xml
└── src/
    ├── main/java/com/leveldb/
    │   ├── common/          # 公共基础类型
    │   │   ├── Slice.java           # 字节串（对应 C++ Slice）
    │   │   ├── Status.java          # 操作结果状态
    │   │   ├── Comparator.java      # 比较器接口
    │   │   ├── InternalKey.java     # 内部键（含序列号和类型）
    │   │   ├── InternalKeyComparator.java
    │   │   ├── LookupKey.java       # 查询用辅助键
    │   │   ├── ValueType.java       # 值类型枚举
    │   │   └── DbConfig.java        # 全局常量
    │   │
    │   ├── util/            # 工具层
    │   │   ├── Coding.java          # varint/fixed 编解码
    │   │   ├── Crc32c.java          # CRC32C 校验
    │   │   ├── BloomFilter.java     # Bloom 过滤器
    │   │   ├── Hash.java            # 内部哈希函数
    │   │   └── LRUCache.java        # LRU 缓存（块缓存）
    │   │
    │   ├── memtable/        # 内存层
    │   │   ├── SkipList.java        # 并发跳表
    │   │   └── MemTable.java        # 内存表
    │   │
    │   ├── log/             # WAL 日志层
    │   │   ├── LogFormat.java       # 日志格式常量
    │   │   ├── LogWriter.java       # 日志写入器
    │   │   └── LogReader.java       # 日志读取器
    │   │
    │   ├── table/           # SSTable 文件层
    │   │   ├── BlockHandle.java     # 块位置描述（offset + size）
    │   │   ├── BlockContents.java   # 块内容载体
    │   │   ├── BlockBuilder.java    # 块构造器（前缀压缩）
    │   │   ├── Block.java           # 块读取器
    │   │   ├── FilterBlock.java     # Filter 块（存储 Bloom Filter）
    │   │   ├── FilterBlockBuilder.java
    │   │   ├── Footer.java          # 文件尾部（48字节）
    │   │   ├── TableBuilder.java    # SSTable 写入器
    │   │   ├── Table.java           # SSTable 读取器
    │   │   └── TableCache.java      # Table 的 LRU 缓存
    │   │
    │   ├── iterator/        # 迭代器层
    │   │   ├── Iterator.java        # 迭代器接口
    │   │   ├── MergingIterator.java # 多路归并迭代器
    │   │   └── TwoLevelIterator.java # 两级迭代器（IndexBlock → DataBlock）
    │   │
    │   └── db/              # 主控层
    │       ├── DB.java              # 对外公开 API 接口
    │       ├── Options.java         # 数据库配置选项
    │       ├── ReadOptions.java
    │       ├── WriteOptions.java
    │       ├── WriteBatch.java      # 批量写单元
    │       ├── Snapshot.java        # 快照
    │       ├── SnapshotList.java    # 快照链表
    │       ├── FileMetaData.java    # SSTable 文件元数据
    │       ├── VersionEdit.java     # 版本变更记录
    │       ├── Version.java         # 数据库某时刻的文件集合快照
    │       ├── VersionSet.java      # 版本集管理器
    │       ├── Compaction.java      # 压缩操作
    │       ├── FileName.java        # 文件命名规则
    │       └── DBImpl.java          # DB 实现（核心）
    │
    └── test/java/com/leveldb/
        ├── common/
        │   └── SliceTest.java
        ├── util/
        │   ├── CodingTest.java
        │   ├── BloomFilterTest.java
        │   └── LRUCacheTest.java
        ├── memtable/
        │   ├── SkipListTest.java
        │   └── MemTableTest.java
        ├── log/
        │   └── LogWriterReaderTest.java
        ├── table/
        │   ├── BlockBuilderTest.java
        │   ├── FilterBlockTest.java
        │   └── TableBuilderReaderTest.java
        ├── iterator/
        │   └── MergingIteratorTest.java
        └── db/
            ├── WriteBatchTest.java
            ├── VersionEditTest.java
            └── DBImplTest.java
```

### 3.2 包间依赖关系

```
db ──────────────────────────────────┐
 │                                   │
 ├──► iterator ──────────────────┐   │
 │                               │   │
 ├──► table ──────────────────┐  │   │
 │                            │  │   │
 ├──► log                     │  │   │
 │                            │  │   │
 ├──► memtable                │  │   │
 │       └──────────────────► │  │   │
 │                            ▼  ▼   ▼
 └──────────────────────── common + util
```

**依赖规则（禁止循环依赖）：**
- `common`、`util`：不依赖任何其他包
- `memtable`：依赖 `common`
- `log`：依赖 `common`、`util`
- `table`：依赖 `common`、`util`、`iterator`
- `iterator`：依赖 `common`
- `db`：依赖所有其他包

---

## 4. 核心数据格式

### 4.1 InternalKey 格式

LevelDB 内部不直接存储用户键，而是将用户键包装成 InternalKey：

```
┌─────────────────────────┬──────────────────────────────┐
│    user_key (变长字节)    │         tag (8字节)           │
│                         │  sequence_number(56位) │ type(8位) │
└─────────────────────────┴──────────────────────────────┘
```

- **sequence_number**（56 位）：单调递增的序列号，每次写操作递增
- **type**（8 位）：`kTypeValue=1`（有效值）或 `kTypeDeletion=0`（删除墓碑）
- **tag 组合**：`(sequence_number << 8) | type`，存储为 Little-Endian uint64

**设计原因**：相同 user_key 按 sequence_number 降序排列（新数据在前），确保 Get 操作总能找到最新版本。

### 4.2 LookupKey 格式

用于 MemTable 查询的辅助结构：

```
┌───────────────────────────┬──────────────────────┬────────────┐
│  klength (varint32)        │   user_key (变长)     │  tag(8字节) │
│  = len(user_key) + 8      │                       │ seq<<8|type │
└───────────────────────────┴──────────────────────┴────────────┘
                             ↑
                    internal_key() 从这里开始
↑
memtable_key() 从这里开始
```

### 4.3 WAL Record 格式

WAL 文件以 **32KB block** 为单位组织，每个物理记录格式：

```
┌──────────┬──────────┬────────┬──────────────────┐
│ CRC32(4) │Length(2) │ Type(1)│   Data (变长)     │
└──────────┴──────────┴────────┴──────────────────┘
```

- **CRC32**：对 `Type + Data` 的 CRC32C 校验值（Little-Endian）
- **Length**：Data 字节数（Little-Endian uint16）
- **Type**：`kFullType=1`、`kFirstType=2`、`kMiddleType=3`、`kLastType=4`

**跨 block 分片规则**：
- 单条记录超过 block 剩余空间时，分成多个物理记录
- block 末尾剩余 < 7 字节时，用 `\x00` 填充到 block 末尾

```
Block 0 (32KB):
  [Header][FIRST 片段数据...]
  [Header][MIDDLE 片段数据...]

Block 1 (32KB):
  [Header][LAST 片段数据...]
  [Header][FULL 记录...]
```

### 4.4 WriteBatch 格式

```
┌─────────────────────────┬─────────────────┬─────────────────────────────────┐
│  sequence_number (8字节) │  count (4字节)  │  Records...                     │
└─────────────────────────┴─────────────────┴─────────────────────────────────┘

每条 Record:
  kTypeValue:    [0x01][key_len(varint)][key][value_len(varint)][value]
  kTypeDeletion: [0x00][key_len(varint)][key]
```

### 4.5 Block 格式（Data Block / Index Block）

```
┌─────────────────────────────────────────────────────────┐
│  Entry 0: shared(varint) + unshared(varint) + value_len │
│           + unshared_key_data + value_data              │
│  Entry 1: ... (前缀压缩，shared > 0)                    │
│  ...                                                    │
│  Entry 15: shared=0 (Restart Point)                     │
│  ...                                                    │
│  Restart Point Offsets: [offset0][offset1]...           │
│  num_restarts (4字节 fixed32)                            │
└─────────────────────────────────────────────────────────┘

块尾部 Trailer（附加在 Block 数据之后）:
┌────────────┬──────────┐
│  type (1)  │ crc32(4) │
└────────────┴──────────┘
type: kNoCompression=0, kSnappyCompression=1
```

**前缀压缩示例**：
```
key0 = "apple"     → shared=0, unshared=5, data="apple"
key1 = "apply"     → shared=4, unshared=1, data="y"
key2 = "application" → shared=4, unshared=7, data="ication"
key3 = "banana"    → shared=0, unshared=6, data="banana"  ← Restart Point
```

### 4.6 SSTable 文件格式

```
┌──────────────────────────────────────────┐
│  Data Block 0                            │
│  [Block Trailer: type(1) + crc32(4)]     │
├──────────────────────────────────────────┤
│  Data Block 1 + Trailer                  │
├──────────────────────────────────────────┤
│  ...                                     │
├──────────────────────────────────────────┤
│  Filter Block (存储 Bloom Filter 数据)    │
│  Trailer                                 │
├──────────────────────────────────────────┤
│  Meta Index Block (指向 Filter Block)    │
│  Trailer                                 │
├──────────────────────────────────────────┤
│  Index Block (每个 Data Block 一条索引)   │
│  key = 该 Block 最大 key 的上界           │
│  value = BlockHandle(offset + size)      │
│  Trailer                                 │
├──────────────────────────────────────────┤
│  Footer (固定 48 字节)                   │
│  metaindex_handle (BlockHandle ≤20字节)  │
│  index_handle    (BlockHandle ≤20字节)   │
│  padding                                  │
│  magic (8字节) = 0xdb4775248b80fb57      │
└──────────────────────────────────────────┘
```

### 4.7 MANIFEST 文件格式

MANIFEST 文件是一个 WAL 格式的日志文件，记录 VersionEdit 序列：

```
[WAL Record: VersionEdit 0 (初始状态)]
[WAL Record: VersionEdit 1 (flush Level-0 文件)]
[WAL Record: VersionEdit 2 (compaction 结果)]
...
```

**VersionEdit 编码格式**（key-value 对序列）：

```
Tag 1:  Comparator name (string)
Tag 2:  Log number (varint64)
Tag 3:  Next file number (varint64)
Tag 4:  Last sequence (varint64)
Tag 5:  Compact pointer: level(varint32) + InternalKey
Tag 6:  Deleted file: level(varint32) + file_number(varint64)
Tag 7:  New file: level(varint32) + number + file_size + smallest + largest
```

---

## 5. 组件详细设计

### 5.1 common — 公共基础层

#### 5.1.1 Slice

对 `byte[]` 的轻量封装，避免频繁拷贝。

```java
public class Slice {
    private final byte[] data;
    private final int offset;  // 起始偏移
    private final int length;  // 长度

    // 构造方法
    public Slice(byte[] data)
    public Slice(byte[] data, int offset, int length)
    public static Slice from(String s)  // UTF-8 编码

    // 核心方法
    public int length()
    public byte getByte(int index)
    public byte[] getBytes()           // 返回 [offset, offset+length) 的副本
    public byte[] getRawArray()        // 返回底层数组（不拷贝，慎用）
    public Slice slice(int offset, int length)  // 子切片（零拷贝）
    public int compareTo(Slice other)  // 字典序比较
    public boolean startsWith(Slice prefix)
    public String toString()           // UTF-8 解码
}
```

#### 5.1.2 Status

操作结果，包含状态码和错误信息。**不可变**。

```java
public class Status {
    public enum Code { OK, NOT_FOUND, CORRUPTION, IO_ERROR, INVALID_ARGUMENT, NOT_SUPPORTED }

    private final Code code;
    private final String message;  // 错误时非空

    // 工厂方法
    public static Status ok()
    public static Status notFound(String msg)
    public static Status corruption(String msg)
    public static Status ioError(String msg)
    public static Status invalidArgument(String msg)

    // 查询方法
    public boolean isOk()
    public boolean isNotFound()
    public boolean isCorruption()
    public Code code()
    public String message()
    public String toString()
}
```

#### 5.1.3 ValueType

```java
public enum ValueType {
    DELETION(0),   // 墓碑标记
    VALUE(1);      // 有效值

    public final int code;

    // 用于 seek 的类型（seek 时用最大值，确保找到最新版本）
    public static final ValueType VALUE_FOR_SEEK = VALUE;
}
```

#### 5.1.4 DbConfig

```java
public final class DbConfig {
    public static final int NUM_LEVELS = 7;
    public static final int L0_COMPACTION_TRIGGER = 4;   // Level-0 触发压缩的文件数
    public static final int L0_SLOWDOWN_WRITES_TRIGGER = 8;
    public static final int L0_STOP_WRITES_TRIGGER = 12;
    public static final int MAX_MEM_COMPACT_LEVEL = 2;   // MemTable 最多压缩到的 Level
    public static final long MAX_SEQUENCE_NUMBER = (1L << 56) - 1;
}
```

#### 5.1.5 InternalKey

```java
public class InternalKey {
    // 内部存储格式: user_key + tag(8字节 LE)
    // tag = (sequence_number << 8) | value_type.code

    public static byte[] encode(Slice userKey, long sequenceNumber, ValueType type)
    public static Slice extractUserKey(byte[] internalKey)  // 去掉末 8 字节
    public static long extractSequenceNumber(byte[] internalKey)
    public static ValueType extractValueType(byte[] internalKey)
}
```

#### 5.1.6 InternalKeyComparator

```java
public class InternalKeyComparator implements Comparator {
    private final Comparator userComparator;  // 用户提供的键比较器（默认字典序）

    // 比较规则：
    // 1. 先按 user_key 升序排列
    // 2. user_key 相同时，按 sequence_number 降序（越新越靠前）
    // 3. sequence_number 相同时，按 value_type 降序
    public int compare(Slice a, Slice b)
    public String name()  // "leveldb.InternalKeyComparator"
    public Slice findShortestSeparator(Slice start, Slice limit)
    public Slice findShortSuccessor(Slice key)
}
```

#### 5.1.7 LookupKey

```java
public class LookupKey {
    // 构造时自动编码 memtable_key 格式
    public LookupKey(Slice userKey, long sequenceNumber)

    // 三种视图（零拷贝，都指向同一底层 byte[]）
    public Slice memtableKey()  // 含长度前缀的完整 memtable 键
    public Slice internalKey()  // user_key + tag
    public Slice userKey()      // 纯用户键
}
```

---

### 5.2 util — 工具层

#### 5.2.1 Coding

参考 C++ `util/coding.h`，实现 LevelDB 编码规范。

```java
public final class Coding {
    // Fixed-length 编码（Little-Endian）
    public static void encodeFixed32(byte[] buf, int offset, int value)
    public static void encodeFixed64(byte[] buf, int offset, long value)
    public static int decodeFixed32(byte[] buf, int offset)
    public static long decodeFixed64(byte[] buf, int offset)

    // Varint 编码（每7位一组，最高位为延续标志）
    public static void encodeVarint32(ByteArrayOutputStream out, int value)
    public static void encodeVarint64(ByteArrayOutputStream out, long value)
    public static int decodeVarint32(byte[] buf, int[] offsetRef)    // offsetRef[0] 传入/传出偏移
    public static long decodeVarint64(byte[] buf, int[] offsetRef)
    public static int varintLength(long value)  // 计算 varint 编码长度

    // 带长度前缀的字节串
    public static void encodeLengthPrefixedSlice(ByteArrayOutputStream out, Slice s)
    public static Slice decodeLengthPrefixedSlice(byte[] buf, int[] offsetRef)
}
```

**Varint 算法说明**：
```
编码 300 (0x0000_012C):
  第1字节: 0xAC = 0b10101100 (低7位=0b0101100=44, 最高位1=有后续字节)
  第2字节: 0x02 = 0b00000010 (低7位=0b0000010=2, 最高位0=最后字节)
  值 = 44 + (2 << 7) = 44 + 256 = 300 ✓
```

#### 5.2.2 Hash

LevelDB 内部哈希函数（用于 Bloom Filter）：

```java
public final class Hash {
    // 参考 C++ util/hash.cc，MurmurHash 变体
    public static int hash(byte[] data, int offset, int length, int seed)
    public static int bloomHash(Slice key)  // seed=0xbc9f1d34
}
```

#### 5.2.3 BloomFilter

```java
public class BloomFilter {
    private final int bitsPerKey;  // 每个 key 占用的 bit 数，推荐 10
    private final int k;           // 哈希函数数量 = bitsPerKey * 0.69, 范围 [1, 30]

    public BloomFilter(int bitsPerKey)

    // 构建 Bloom Filter 字节数组
    // 末尾 1 字节存储 k 值
    public byte[] createFilter(List<Slice> keys)

    // 判断 key 是否可能存在（false 表示肯定不存在）
    public boolean keyMayMatch(Slice key, byte[] filter)
}
```

**算法说明（对应 C++ `util/bloom.cc`）**：
```
k = max(1, min(30, (int)(bitsPerKey * 0.69)))

createFilter(keys):
  bits = max(64, keys.size() * bitsPerKey)
  bytes = (bits + 7) / 8
  bits = bytes * 8  // 对齐到字节
  byte[] result = new byte[bytes + 1]
  for each key:
    h = bloomHash(key)
    delta = (h >>> 17) | (h << 15)  // 循环右移 17 位
    for j in [0, k):
      bitPos = (h & 0xFFFFFFFFL) % bits
      result[bitPos / 8] |= (1 << (bitPos % 8))
      h += delta
  result[bytes] = (byte) k  // 存储 k 值
  return result
```

#### 5.2.4 Crc32c

```java
public final class Crc32c {
    public static int value(byte[] data, int offset, int length)
    public static int extend(int crc, byte[] data, int offset, int length)
    public static int mask(int crc)    // 用于存储时加扰（避免与数据混淆）
    public static int unmask(int crc)  // 读取时还原
}
```

#### 5.2.5 LRUCache

用于 TableCache（缓存打开的 SSTable）和 Block Cache。

```java
public class LRUCache<K, V> {
    private final int capacity;
    // 内部使用 LinkedHashMap（accessOrder=true）实现 LRU

    public LRUCache(int capacity)
    public V get(K key)
    public void put(K key, V value)
    public void invalidate(K key)
    public int size()
}
```

---

### 5.3 memtable — 内存层

#### 5.3.1 SkipList

跳表是 MemTable 的核心数据结构。支持并发读（无锁）和单线程写（需外部同步）。

```java
public class SkipList<K> {
    // 常量
    private static final int MAX_HEIGHT = 12;
    private static final int BRANCHING = 4;  // 每层增高概率 1/4

    private final Comparator<K> comparator;
    private final Node<K> head;           // 哑头节点（不存数据）
    private volatile int maxHeight;       // 当前最大高度（volatile 保证可见性）
    private final Random random;

    // 节点内部类
    private static class Node<K> {
        final K key;
        // next[i] 是第 i 层的下一个节点，volatile 保证读可见性
        final AtomicReferenceArray<Node<K>> next;
        Node(K key, int height) { ... }
    }

    public SkipList(Comparator<K> comparator)

    // 插入 key（不允许重复插入相同 key）
    public void insert(K key)

    // 查询 key 是否存在
    public boolean contains(K key)

    // 返回迭代器
    public SkipListIterator iterator()

    // 内部方法
    private int randomHeight()
    private Node<K> findGreaterOrEqual(K key, Node<K>[] prev)  // prev 保存各层前驱

    // 迭代器
    public class SkipListIterator {
        public boolean valid()
        public K key()
        public void next()
        public void prev()
        public void seek(K target)
        public void seekToFirst()
        public void seekToLast()
    }
}
```

**并发安全模型**：
- 写操作（insert）需要调用方持有外部锁
- 读操作（contains、iterator）无需锁，利用 `volatile` 和 `AtomicReferenceArray` 保证内存可见性
- 节点一旦插入永不删除（LevelDB 通过墓碑标记实现逻辑删除）

**randomHeight() 算法**：
```java
int randomHeight() {
    int height = 1;
    while (height < MAX_HEIGHT && (random.nextInt() & (BRANCHING - 1)) == 0) {
        height++;
    }
    return height;
}
// 高度=1 概率: 3/4
// 高度=2 概率: 3/16
// 高度=k 概率: (1/4)^(k-1) * 3/4
```

#### 5.3.2 MemTable

```java
public class MemTable {
    private final SkipList<byte[]> table;     // 存储 InternalKey 编码后的 byte[]
    private final InternalKeyComparator comparator;
    private final AtomicLong memoryUsage;     // 近似内存占用（字节）
    private int refs;                         // 引用计数

    public MemTable(InternalKeyComparator comparator)

    // 引用计数管理
    public void ref()
    public void unref()  // refs == 0 时可被 GC

    // 添加一条记录
    // 内部编码格式: klength(varint32) + user_key + tag(8字节 LE)
    //              + vlength(varint32) + value
    public void add(long sequenceNumber, ValueType type, Slice key, Slice value)

    // 查询（使用 LookupKey）
    // 返回 null 表示不存在，返回 Status.notFound() 表示已删除
    public GetResult get(LookupKey lookupKey)

    // 内存占用估计
    public long approximateMemoryUsage()

    // 创建迭代器
    public Iterator newIterator()

    // 内部数据类
    public static class GetResult {
        public final boolean found;
        public final Slice value;     // found=true 且非删除标记时有值
        public final boolean deleted; // 找到了但是删除标记
    }
}
```

**add() 编码格式（对应 C++ memtable.cc）**：
```
MemTable Entry:
  klength  (varint32)  = len(user_key) + 8
  user_key (字节串)
  tag      (uint64 LE) = sequence_number << 8 | value_type.code
  vlength  (varint32)  = len(value)
  value    (字节串)
```

---

### 5.4 log — WAL 日志层

#### 5.4.1 LogFormat

```java
public final class LogFormat {
    public static final int BLOCK_SIZE = 32768;  // 32KB
    public static final int HEADER_SIZE = 7;      // CRC(4) + Length(2) + Type(1)

    public enum RecordType {
        ZERO_TYPE(0),    // 零填充（块尾部不足 7 字节时使用）
        FULL(1),         // 完整记录（最常见）
        FIRST(2),        // 多片段记录的第一片
        MIDDLE(3),       // 多片段记录的中间片
        LAST(4);         // 多片段记录的最后一片

        public final int code;
    }
}
```

#### 5.4.2 LogWriter

```java
public class LogWriter implements Closeable {
    private final OutputStream dest;     // 目标文件输出流
    private int blockOffset;             // 当前 block 内的写入位置

    public LogWriter(File file) throws IOException
    public LogWriter(OutputStream out)   // 测试用

    // 写入一条逻辑记录（自动处理跨 block 分片）
    public void addRecord(byte[] data) throws IOException
    public void addRecord(Slice data) throws IOException

    // 刷新到磁盘
    public void sync() throws IOException

    @Override
    public void close() throws IOException

    // 私有：写入一个物理记录片段
    private void emitPhysicalRecord(LogFormat.RecordType type, byte[] data,
                                    int offset, int length) throws IOException
}
```

**addRecord() 算法**：
```
int left = data.length
boolean begin = true
do:
    int leftover = BLOCK_SIZE - blockOffset
    if leftover < HEADER_SIZE:
        // 用零填充剩余空间，开始新 block
        dest.write(zeros, leftover bytes)
        blockOffset = 0
        leftover = BLOCK_SIZE

    int avail = leftover - HEADER_SIZE
    int fragmentLength = min(left, avail)
    RecordType type = 决定 FULL/FIRST/MIDDLE/LAST
    emitPhysicalRecord(type, data, offset, fragmentLength)
    offset += fragmentLength
    left -= fragmentLength
    begin = false
while left > 0
```

#### 5.4.3 LogReader

```java
public class LogReader implements Closeable {
    private final InputStream src;
    private final boolean verifyChecksums;
    private final long initialOffset;    // 从哪个偏移开始读（0 表示从头）

    private byte[] backingStore;         // 32KB 读缓冲区
    private int bufferOffset;            // 缓冲区当前位置
    private int bufferLength;            // 缓冲区有效长度
    private boolean eof;

    public LogReader(File file, boolean verifyChecksums, long initialOffset)
    public LogReader(InputStream in, boolean verifyChecksums)

    // 读取下一条逻辑记录，返回 null 表示 EOF
    // 自动处理 FIRST/MIDDLE/LAST 分片重组
    public byte[] readRecord() throws IOException

    // 报告器接口（可选，用于报告数据损坏）
    public interface Reporter {
        void corruption(int bytes, String reason);
    }

    @Override
    public void close() throws IOException
}
```

---

### 5.5 table — SSTable 文件层

#### 5.5.1 BlockHandle

```java
public class BlockHandle {
    public static final int MAX_ENCODED_LENGTH = 20;  // 两个 varint64 最大长度

    private long offset;  // 块在文件中的起始偏移
    private long size;    // 块数据字节数（不含 trailer）

    public BlockHandle()
    public BlockHandle(long offset, long size)

    public long offset()
    public long size()

    public void encodeTo(ByteArrayOutputStream out)
    public void decodeFrom(byte[] buf, int[] offsetRef)
}
```

#### 5.5.2 BlockBuilder

```java
public class BlockBuilder {
    private final int blockRestartInterval;   // 默认 16
    private final ByteArrayOutputStream buffer;
    private final List<Integer> restarts;     // restart point 偏移数组
    private int counter;                      // 自上个 restart point 以来的 entry 数
    private byte[] lastKey;                   // 上一个写入的 key（用于前缀压缩）
    private boolean finished;

    public BlockBuilder(int blockRestartInterval)

    public void add(Slice key, Slice value)
    // 注意：key 必须 >= 上一次 add 的 key（保证有序）

    // 完成构建，返回块内容（含 restart point 数组）
    // 调用后不能再 add()，可以 reset()
    public Slice finish()

    public void reset()

    public int currentSizeEstimate()  // 当前估计大小（用于决定何时切块）
    public boolean isEmpty()
}
```

**add() 实现要点**：
```java
// 计算与 lastKey 的公共前缀长度
int shared = 0;
if (counter < blockRestartInterval) {
    // 前缀压缩
    int minLen = min(lastKey.length, key.length);
    while (shared < minLen && lastKey[shared] == key[shared]) shared++;
} else {
    // 新的 restart point，不压缩
    restarts.add(buffer.size());
    counter = 0;
}
int unshared = key.length() - shared;

// 写入: shared(varint) + unshared(varint) + value_len(varint) + key[shared:] + value
```

#### 5.5.3 Block

```java
public class Block {
    private final byte[] data;
    private final int restartOffset;   // restart point 数组在 data 中的偏移
    private final int numRestarts;     // restart point 数量

    public Block(BlockContents contents)

    public int size()

    public Iterator newIterator(Comparator comparator)

    // 内部 BlockIterator 实现：
    // 1. seek(target): 二分查找 restart point，再线性扫描
    // 2. next(): 解码下一个 entry，利用前缀压缩还原完整 key
    // 3. prev(): 从最近的 restart point 重新扫描到前一个 key
}
```

#### 5.5.4 Footer

```java
public class Footer {
    public static final int ENCODED_LENGTH = 48;  // 固定 48 字节
    public static final long TABLE_MAGIC_NUMBER = 0xdb4775248b80fb57L;

    private BlockHandle metaindexHandle;
    private BlockHandle indexHandle;

    public Footer()
    public Footer(BlockHandle metaindexHandle, BlockHandle indexHandle)

    public BlockHandle metaindexHandle()
    public BlockHandle indexHandle()

    // 编码到固定 48 字节（两个 BlockHandle + padding + magic）
    public byte[] encode()

    // 从 48 字节解码
    public static Footer decode(byte[] buf) throws IOException
}
```

#### 5.5.5 FilterBlock 与 FilterBlockBuilder

```java
// Filter Block 格式:
// [filter 0 数据][filter 1 数据]...[filter N 数据]
// [filter 0 offset(4字节)][filter 1 offset]...[filter N offset]
// [filter offsets start offset(4字节)]
// [base_lg(1字节) = 11]  // 每 2^11 = 2KB 数据对应一个 filter
public class FilterBlockBuilder {
    private final BloomFilter policy;
    private final ByteArrayOutputStream result;
    private final List<Integer> filterOffsets;
    private final List<Slice> keys;     // 当前 filter 收集的 keys
    private long blockOffset;           // 上一次 startBlock 的偏移

    public FilterBlockBuilder(BloomFilter policy)

    // 当一个新的 Data Block 开始时调用（传入该 block 的起始偏移）
    public void startBlock(long blockOffset)

    // 添加一个 key 到当前 filter
    public void addKey(Slice key)

    // 完成构建
    public Slice finish()
}

public class FilterBlock {
    // 从字节数据中读取 filter，用于 KeyMayMatch 查询
    public FilterBlock(BloomFilter policy, Slice contents)

    // 判断 blockOffset 对应的 filter 中是否可能含有 key
    public boolean keyMayMatch(long blockOffset, Slice key)
}
```

#### 5.5.6 TableBuilder

```java
public class TableBuilder implements Closeable {
    private final Options options;
    private final FileOutputStream file;
    private final BlockBuilder dataBlockBuilder;
    private final BlockBuilder indexBlockBuilder;
    private final FilterBlockBuilder filterBlockBuilder;  // 可选
    private BlockHandle pendingHandle;     // 待写入 index 的 handle
    private boolean pendingIndexEntry;     // 是否有待写入 index 的 entry
    private long offset;                   // 当前写入偏移
    private long numEntries;               // 已写入 entry 数
    private boolean closed;
    private byte[] lastKey;

    public TableBuilder(Options options, File file) throws IOException

    // 顺序添加 key-value（key 必须严格递增）
    public void add(Slice key, Slice value) throws IOException

    // 强制 flush 当前 data block（可选调用）
    public void flush() throws IOException

    // 完成文件写入：flush + 写 filter block + 写 meta index block + 写 index block + 写 footer
    public Status finish() throws IOException

    // 放弃写入（文件保留但数据不完整）
    public void abandon()

    public long numEntries()
    public long fileSize()

    @Override
    public void close() throws IOException
}
```

**finish() 流程**：
```
1. flush() 当前 data block
2. 写 filter block（如果有 filter policy）
3. 写 meta index block（记录 filter block 位置，key="filter.leveldb.BuiltinBloomFilter2"）
4. 写 index block（每个 data block 对应一条 entry）
5. 写 footer（48字节）
6. sync() 文件
```

#### 5.5.7 Table（SSTable 读取器）

```java
public class Table implements Closeable {
    private final Options options;
    private final RandomAccessFile file;
    private final Footer footer;
    private final Block indexBlock;
    private final FilterBlock filterBlock;   // 可能为 null
    private final LRUCache<Long, Block> blockCache;  // block offset -> Block

    // 打开已有 SSTable 文件（读取 footer + index block）
    public static Table open(Options options, File file, long fileSize) throws IOException

    // 查询 key（先 Bloom Filter 判断，再 index block 定位，再读 data block）
    public void internalGet(ReadOptions options, Slice key,
                            GetCallback callback) throws IOException

    // 创建双层迭代器（IndexBlock -> DataBlock）
    public Iterator newIterator(ReadOptions options)

    // 估算 key 在文件中的近似偏移量
    public long approximateOffsetOf(Slice key)

    @Override
    public void close() throws IOException

    // 回调接口（避免在读 data block 时 hold 锁）
    public interface GetCallback {
        void got(Slice key, Slice value);
        void notFound();
    }
}
```

#### 5.5.8 TableCache

```java
public class TableCache {
    private final String dbname;
    private final Options options;
    private final LRUCache<Long, Table> cache;  // file_number -> Table

    public TableCache(String dbname, Options options, int entries)

    // 获取 Table（自动打开并缓存）
    public Table get(long fileNumber, long fileSize) throws IOException

    // 查询 key（通过 Table.internalGet）
    public void get(ReadOptions options, long fileNumber, long fileSize,
                    Slice key, Table.GetCallback callback) throws IOException

    // 创建迭代器
    public Iterator newIterator(ReadOptions options, long fileNumber,
                                long fileSize) throws IOException

    // 驱逐缓存（compaction 删除文件后调用）
    public void evict(long fileNumber)
}
```

---

### 5.6 iterator — 迭代器层

#### 5.6.1 Iterator（接口）

```java
public interface Iterator extends Closeable {
    // 定位到第一个 key
    void seekToFirst();

    // 定位到最后一个 key
    void seekToLast();

    // 定位到第一个 >= target 的 key
    void seek(Slice target);

    // 移动到下一个 key（必须先 valid()）
    void next();

    // 移动到上一个 key（必须先 valid()）
    void prev();

    // 当前是否指向有效 entry
    boolean valid();

    // 当前 key（valid() 为 true 时才可调用）
    Slice key();

    // 当前 value（valid() 为 true 时才可调用）
    Slice value();

    // 错误状态
    Status status();

    @Override
    void close();
}
```

#### 5.6.2 MergingIterator

合并多个有序 Iterator，输出全局有序序列（用于 compaction 和全库迭代）。

```java
public class MergingIterator implements Iterator {
    private final Iterator[] children;
    private final Comparator comparator;
    private Iterator current;  // 当前指向最小 key 的子迭代器

    public MergingIterator(Comparator comparator, Iterator[] children)

    // seek/seekToFirst/seekToLast: 对所有子迭代器 seek，找最小/最大
    // next(): 推进 current，重新找最小
    // prev(): 所有子迭代器 seek 到 <= current.key，找最大
}
```

#### 5.6.3 TwoLevelIterator

用于 SSTable 内部的两级索引（Index Block → Data Block）。

```java
public class TwoLevelIterator implements Iterator {
    private final Iterator indexIter;      // Index Block 的迭代器
    private Iterator dataIter;             // 当前 Data Block 的迭代器
    private final BlockFunction blockFunction;  // 根据 index value 打开 data block

    // 函数接口：从 index block 的 value（即 BlockHandle）打开对应 Data Block
    public interface BlockFunction {
        Iterator open(ReadOptions options, Slice indexValue) throws IOException;
    }

    public TwoLevelIterator(Iterator indexIter, BlockFunction blockFunction, ReadOptions options)
}
```

---

### 5.7 db — 主控层

#### 5.7.1 Options

```java
public class Options {
    // 键比较器（默认字典序）
    public Comparator comparator = BytewiseComparator.INSTANCE;

    // 打开行为
    public boolean createIfMissing = false;
    public boolean errorIfExists = false;
    public boolean paranoidChecks = false;

    // 内存配置
    public int writeBufferSize = 4 * 1024 * 1024;   // 4MB
    public int maxOpenFiles = 1000;
    public int blockCacheSize = 8 * 1024 * 1024;     // 8MB（null 时使用默认）

    // 块配置
    public int blockSize = 4 * 1024;                 // 4KB
    public int blockRestartInterval = 16;

    // 文件配置
    public long maxFileSize = 2 * 1024 * 1024;       // 2MB

    // Filter 策略（null 表示不使用 Bloom Filter）
    public BloomFilter filterPolicy = new BloomFilter(10);  // 默认 10 bits/key
}
```

#### 5.7.2 WriteBatch

```java
public class WriteBatch {
    // 内部格式: sequence(8字节) + count(4字节) + records
    private final ByteArrayOutputStream rep;
    private int count;  // 记录条数

    public WriteBatch()

    // 添加 put 操作
    public WriteBatch put(Slice key, Slice value)
    public WriteBatch put(String key, String value)

    // 添加 delete 操作
    public WriteBatch delete(Slice key)
    public WriteBatch delete(String key)

    // 清空
    public void clear()

    // 获取条数
    public int count()

    // 将 batch 内容 apply 到 MemTable
    // （由 DBImpl 在写入时调用）
    public void forEach(Handler handler)

    public interface Handler {
        void put(Slice key, Slice value);
        void delete(Slice key);
    }

    // 内部方法（供 DBImpl 使用）
    byte[] getContents()
    void setSequenceNumber(long seq)
    long getSequenceNumber()
    static WriteBatch fromBytes(byte[] data)
}
```

#### 5.7.3 Snapshot

```java
public class Snapshot {
    final long sequenceNumber;  // 快照对应的序列号

    // 链表指针（供 SnapshotList 维护双向链表使用）
    Snapshot prev;
    Snapshot next;

    Snapshot(long sequenceNumber) { ... }
}

public class SnapshotList {
    private final Snapshot head;  // 哑头节点

    public SnapshotList()
    public boolean isEmpty()
    public Snapshot oldest()
    public Snapshot newest()
    public Snapshot newSnapshot(long sequenceNumber)
    public void delete(Snapshot snapshot)
}
```

#### 5.7.4 FileMetaData

```java
public class FileMetaData {
    public int refs;           // 引用计数
    public int allowedSeeks;   // 允许寻道次数（达到后触发 seek compaction）
    public long number;        // 文件编号（用于文件命名：number.ldb）
    public long fileSize;      // 文件字节数
    public InternalKey smallest;  // 文件内最小 InternalKey
    public InternalKey largest;   // 文件内最大 InternalKey

    public FileMetaData() {}
}
```

#### 5.7.5 VersionEdit

```java
public class VersionEdit {
    private String comparatorName;
    private Long logNumber;
    private Long prevLogNumber;
    private Long nextFileNumber;
    private Long lastSequence;

    // compact_pointers_[level] = 上次压缩到该 level 的哪个 key
    private final Map<Integer, InternalKey> compactPointers = new HashMap<>();

    // 删除的文件：(level, file_number) 对
    private final Set<Pair<Integer, Long>> deletedFiles = new HashSet<>();

    // 新增文件列表
    private final List<Pair<Integer, FileMetaData>> newFiles = new ArrayList<>();

    // setters
    public void setComparatorName(String name)
    public void setLogNumber(long num)
    public void setNextFileNumber(long num)
    public void setLastSequence(long seq)
    public void setCompactPointer(int level, InternalKey key)
    public void addFile(int level, long fileNumber, long fileSize,
                        InternalKey smallest, InternalKey largest)
    public void removeFile(int level, long fileNumber)

    // 序列化 / 反序列化（对应 C++ version_edit.cc）
    public byte[] encodeTo()
    public static VersionEdit decodeFrom(byte[] data) throws IOException
}
```

#### 5.7.6 Version

```java
public class Version {
    private final VersionSet vset;
    private Version next;    // 链表指针
    private Version prev;
    private int refs;

    // 每层的文件列表（每层内按 smallest key 排序）
    final List<FileMetaData>[] files;  // files[NUM_LEVELS]

    // Seek compaction 信息
    FileMetaData fileToCompact;
    int fileToCompactLevel;

    // Size compaction 信息
    double compactionScore;   // 最高评分的 level 的压缩分数
    int compactionLevel;      // 对应的 level（-1 表示无需压缩）

    Version(VersionSet vset)

    public void ref()
    public void unref()

    // 在该 Version 上执行 Get 操作
    public Status get(ReadOptions options, LookupKey key, byte[] value[],
                      GetStats stats) throws IOException

    // 创建所有 level 的迭代器（供 MergingIterator 使用）
    public void addIterators(ReadOptions options, List<Iterator> iters) throws IOException

    // 更新 seek 统计信息，如需要 compaction 返回 true
    public boolean updateStats(GetStats stats)

    // 为 MemTable flush 选择最合适的输出 Level（最多 MAX_MEM_COMPACT_LEVEL）
    public int pickLevelForMemTableOutput(Slice smallestUserKey, Slice largestUserKey)

    // 内嵌数据类
    public static class GetStats {
        public FileMetaData seekFile;
        public int seekFileLevel;
    }
}
```

#### 5.7.7 VersionSet

```java
public class VersionSet {
    private final String dbname;
    private final Options options;
    private final TableCache tableCache;
    private final InternalKeyComparator icmp;

    private long nextFileNumber;
    private long manifestFileNumber;
    private long lastSequence;
    private long logNumber;
    private long prevLogNumber;

    private LogWriter descriptorLog;  // 写 MANIFEST 文件
    private Version dummyVersions;    // 版本链表哑头
    private Version current;          // 当前版本

    // 每层压缩指针（记录上次压缩到哪个 key）
    private final InternalKey[] compactPointer;

    public VersionSet(String dbname, Options options, TableCache tableCache,
                      InternalKeyComparator icmp)

    // 应用一个 VersionEdit，生成新 Version，写 MANIFEST
    public void logAndApply(VersionEdit edit) throws IOException

    // 从 MANIFEST 文件恢复 VersionSet 状态
    public boolean recover() throws IOException  // 返回是否需要保存新 MANIFEST

    public Version current()
    public long lastSequence()
    public void setLastSequence(long s)
    public long newFileNumber()
    public long manifestFileNumber()
    public long logNumber()
    public long prevLogNumber()

    // 选择下一次 compaction（返回 null 表示无需 compaction）
    public Compaction pickCompaction()

    // 手动触发范围 compaction
    public Compaction compactRange(int level, InternalKey begin, InternalKey end)

    // 统计信息
    public int numLevelFiles(int level)
    public long numLevelBytes(int level)

    // 添加所有活跃文件编号到 live 集合（用于垃圾回收）
    public void addLiveFiles(Set<Long> live)

    // 计算某 key 在 version 中的近似文件偏移
    public long approximateOffsetOf(Version v, InternalKey key)
}
```

#### 5.7.8 Compaction

```java
public class Compaction {
    public static final long MAX_GRANDPARENT_OVERLAP_BYTES = 10 * 1024 * 1024; // 10MB

    private final int level;
    private long maxOutputFileSize;
    private Version inputVersion;
    private final VersionEdit edit;

    // inputs[0]: level 层参与 compaction 的文件
    // inputs[1]: level+1 层参与 compaction 的文件
    private final List<FileMetaData>[] inputs;

    // grandparents: level+2 层与输入范围重叠的文件（用于限制输出文件大小）
    private List<FileMetaData> grandparents;
    private int grandparentIndex;
    private boolean seenKey;
    private long overlappedBytes;

    public Compaction(int level, long maxOutputFileSize)

    public int level()
    public VersionEdit edit()
    public int numInputFiles(int which)
    public FileMetaData input(int which, int i)

    // 是否是平凡移动（仅一个文件，无需归并，直接 move）
    public boolean isTrivialMove()

    // 将输入文件标记为删除（添加到 edit.deletedFiles）
    public void addInputDeletions(VersionEdit edit)

    // 检查 user_key 在 level+2 及以下是否不存在（用于决定是否可以 drop 删除标记）
    public boolean isBaseLevelForKey(Slice userKey)

    // 检查是否应该切割输出文件（避免与 grandparent 层重叠过多）
    public boolean shouldStopBefore(Slice internalKey)

    public void releaseInputs()
}
```

#### 5.7.9 FileName

```java
public final class FileName {
    // 文件命名规则（对应 C++ db/filename.h）

    public static String logFileName(String dbname, long number)
        // → dbname/000023.log

    public static String tableFileName(String dbname, long number)
        // → dbname/000023.ldb

    public static String sstTableFileName(String dbname, long number)
        // → dbname/000023.sst（兼容旧版本）

    public static String manifestFileName(String dbname, long number)
        // → dbname/MANIFEST-000023

    public static String currentFileName(String dbname)
        // → dbname/CURRENT（内容：当前 MANIFEST 文件名）

    public static String lockFileName(String dbname)
        // → dbname/LOCK

    public static String tempFileName(String dbname, long number)
        // → dbname/000023.dbtmp

    public static String infoLogFileName(String dbname)
        // → dbname/LOG

    // 解析文件类型
    public static FileType parseFileName(String filename, long[] number)

    public enum FileType {
        LOG, DB_LOCK, TABLE, MANIFEST, CURRENT, TEMP, INFO_LOG
    }
}
```

#### 5.7.10 DB（接口）

```java
public interface DB extends Closeable {
    // 打开数据库（静态工厂方法在 DBImpl 中）
    static DB open(Options options, String name) throws IOException { ... }

    void put(WriteOptions options, Slice key, Slice value) throws IOException;
    void delete(WriteOptions options, Slice key) throws IOException;
    void write(WriteOptions options, WriteBatch updates) throws IOException;
    byte[] get(ReadOptions options, Slice key) throws IOException;

    Iterator newIterator(ReadOptions options);

    Snapshot getSnapshot();
    void releaseSnapshot(Snapshot snapshot);

    // 查询内部属性
    // "leveldb.num-files-at-level0" ~ "leveldb.num-files-at-level6"
    // "leveldb.stats"
    // "leveldb.approximate-memory-usage"
    String getProperty(String property);

    // 手动触发范围 compaction（null 表示全范围）
    void compactRange(Slice begin, Slice end) throws IOException;

    @Override
    void close() throws IOException;
}
```

#### 5.7.11 DBImpl（核心实现）

```java
public class DBImpl implements DB {
    private final Options options;
    private final String dbname;
    private final TableCache tableCache;
    private final InternalKeyComparator internalComparator;

    // 锁（保护以下所有可变状态）
    private final ReentrantLock mutex = new ReentrantLock();

    // 内存层
    private MemTable mem;       // 当前 MemTable（可写）
    private MemTable imm;       // Immutable MemTable（等待 flush），可为 null
    private volatile boolean hasImm; // volatile 供后台线程检测

    // WAL
    private long logFileNumber;
    private LogWriter log;

    // 版本管理
    private VersionSet versions;

    // 后台 compaction 线程
    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();
    private boolean bgCompactionScheduled;

    // 写操作同步（写队列）
    private final Deque<Writer> writers = new ArrayDeque<>();
    private WriteBatch tmpBatch;  // 批量合并时使用的临时 batch

    // 快照管理
    private final SnapshotList snapshots = new SnapshotList();

    // 错误状态
    private Status bgError;

    // 挂起的输出文件（compaction 中产生但还未写入 MANIFEST）
    private final Set<Long> pendingOutputs = new HashSet<>();

    // ──────────────── 公共 API ────────────────

    public static DB open(Options options, String name) throws IOException

    @Override
    public void put(WriteOptions options, Slice key, Slice value) throws IOException

    @Override
    public void delete(WriteOptions options, Slice key) throws IOException

    @Override
    public void write(WriteOptions options, WriteBatch updates) throws IOException

    @Override
    public byte[] get(ReadOptions options, Slice key) throws IOException

    @Override
    public Iterator newIterator(ReadOptions options)

    @Override
    public Snapshot getSnapshot()

    @Override
    public void releaseSnapshot(Snapshot snapshot)

    @Override
    public String getProperty(String property)

    @Override
    public void compactRange(Slice begin, Slice end) throws IOException

    @Override
    public void close() throws IOException

    // ──────────────── 内部方法 ────────────────

    // 打开数据库（recover + 确认 MemTable + 启动后台任务）
    private void open(Options options, String name) throws IOException

    // 崩溃恢复
    private void recover() throws IOException

    // 写核心实现（含写队列合并）
    private Status makeRoomForWrite(boolean force) throws IOException
    private WriteBatch buildBatchGroup(Writer[] lastWriter)

    // 触发后台 compaction
    private void maybeScheduleCompaction()
    private void backgroundCall()
    private void backgroundCompaction() throws IOException

    // Flush immutable MemTable 到 Level-0
    private void compactMemTable() throws IOException
    private Status writeLevel0Table(MemTable mem, VersionEdit edit,
                                    Version base) throws IOException

    // SSTable Compaction
    private void doCompactionWork(Compaction c) throws IOException
    private boolean isBaseLevelForKey(Slice userKey)

    // 删除过期文件（compaction 后清理不再需要的文件）
    private void deleteObsoleteFiles() throws IOException

    // 内部写操作辅助类
    private static class Writer {
        WriteBatch batch;
        boolean sync;
        boolean done;
        // 用于等待写操作完成
        final Object cond = new Object();
    }
}
```

**write() 实现要点（写队列合并）**：

```
DBImpl 采用写队列合并（Group Commit）优化写吞吐量：
1. 写操作将 Writer 加入队列后等待
2. 队列头的写操作成为 "leader"
3. Leader 将队列中尽可能多的 WriteBatch 合并成一个大 batch
4. Leader 统一写 WAL 和 MemTable
5. Leader 唤醒所有已完成的 Writer
```

---

## 6. Compaction 策略

### 6.1 触发条件

| 层级 | 触发条件 | 触发原因 |
|------|---------|---------|
| Level-0 | 文件数 ≥ 4 | Level-0 文件 key 可重叠，文件多严重影响读性能 |
| Level-1 | 总大小 > 10MB | |
| Level-2 | 总大小 > 100MB | 每层容量 10 倍增长 |
| Level-N | 总大小 > 10^N MB | |
| Seek | 某文件被 seek 次数过多 | 文件 key 范围太大导致无效 IO |

### 6.2 Compaction 评分计算

```java
// 对每个 level 计算 score
for (int level = 0; level < NUM_LEVELS - 1; level++) {
    double score;
    if (level == 0) {
        // Level-0 按文件数评分
        score = files[level].size() / (double) L0_COMPACTION_TRIGGER;
    } else {
        // 其他层按总大小评分
        long levelBytes = totalBytes(level);
        score = levelBytes / (double) maxBytesForLevel(level);
    }
    if (score > bestScore) {
        bestScore = score;
        bestLevel = level;
    }
}
```

### 6.3 Compaction 执行流程

```
1. pickCompaction()
   - 优先 size compaction（评分最高的 level）
   - 其次 seek compaction（seek 次数超限的文件）

2. 选取参与 compaction 的文件
   - Level-N：从 compact_pointer[N] 之后选一个文件
   - Level-N+1：找出与 Level-N 文件 key 范围重叠的所有文件
   - 扩展：如果扩展 Level-N+1 的文件范围不会增加 Level-N 的文件数，则扩展

3. 判断是否平凡移动（isTrivialMove）
   - Level-N 只有 1 个文件，Level-N+1 没有重叠文件
   - 直接将文件移至 Level-N+1，无需归并
   - 重量级操作变为 O(1)

4. 归并排序
   - 用 MergingIterator 对所有输入文件做归并
   - 遇到相同 user_key 保留最新版本（sequence_number 最大的）
   - 删除标记（kTypeDeletion）：仅当 key 在更高 level 不存在时才可丢弃
   - 每 2MB 切割一个输出文件（避免输出文件过大）

5. 更新版本
   - 创建 VersionEdit（记录删除的旧文件 + 新增的输出文件）
   - 调用 VersionSet.logAndApply(edit)
   - 删除旧文件
```

---

## 7. 崩溃恢复流程

```
DBImpl.open():
  1. 读取 CURRENT 文件 → 获取当前 MANIFEST 文件名
  2. 读取 MANIFEST → 重建 VersionSet（所有 Level 的文件列表）
  3. 找出所有比 log_number 新的 .log 文件（未 flush 的 WAL）
  4. 按序号顺序 replay 每个 WAL 文件：
     a. 读取所有 WAL Record → WriteBatch
     b. 将 WriteBatch apply 到新的 MemTable
     c. 当 MemTable 超过 write_buffer_size 时 flush 为 Level-0 SSTable
  5. 恢复完成，将最后一个 MemTable（如果非空）flush 为 Level-0
  6. 启动新的 WAL 文件
```

---

## 8. 测试计划

### 8.1 CodingTest

```java
// 测试用例列表（详细）
@Test public void testEncodeDecodeFixed32()
    // 验证 0, 1, 0x7FFFFFFF, 0x80000000, 0xFFFFFFFF 的编解码
@Test public void testEncodeDecodeFixed64()
    // 验证 0, 1, Long.MAX_VALUE, Long.MIN_VALUE, 0xFFFFFFFFFFFFFFFFL 的编解码
@Test public void testFixed32IsLittleEndian()
    // 验证 0x01020304 编码为 [04, 03, 02, 01]
@Test public void testEncodeDecodeVarint32()
    // 验证 1(1字节), 127(1字节), 128(2字节), 16383(2字节), 16384(3字节)
@Test public void testEncodeDecodeVarint64()
    // 验证小值和大值（包括跨5字节边界）
@Test public void testVarintLength()
    // 验证各值的编码字节数
@Test public void testLengthPrefixedSliceRoundtrip()
    // 编解码空 Slice 和非空 Slice
@Test public void testBatchVarintDecoding()
    // 连续写多个 varint 后依次解码，验证偏移正确推进
```

### 8.2 BloomFilterTest

```java
@Test public void testEmptyFilter()
    // 空 filter 对任意 key 应返回 false（不存在）
@Test public void testSmallFilter()
    // 插入 10 个 key，验证所有 key 都 match
@Test public void testFalsePositiveRate()
    // 插入 1000 个 key，查询 10000 个不在集合中的 key
    // 误判率应 < 2%（10 bits/key 理论误判率约 1%）
@Test public void testMultipleFilters()
    // 分批构建 filter，验证每批独立工作正确
@Test public void testVaryKeySizes()
    // 测试不同长度的 key（空串、1字节、100字节）
@Test public void testKValueStoredCorrectly()
    // 验证 filter 末尾字节存储的 k 值正确
```

### 8.3 SkipListTest

```java
@Test public void testEmptySkipList()
    // 空跳表迭代器 valid() 应为 false
@Test public void testInsertAndContains()
    // 插入 100 个 key，Contains 全部命中
@Test public void testInsertOrdering()
    // 插入乱序 key，迭代时应按升序输出
@Test public void testSeek()
    // seek("d") 应定位到第一个 >= "d" 的 key
@Test public void testSeekToFirst()
    // seekToFirst 定位到最小 key
@Test public void testSeekToLast()
    // seekToLast 定位到最大 key
@Test public void testPrev()
    // 验证 prev() 方向遍历正确
@Test public void testConcurrentReadsWhileWriting()
    // 10 个读线程持续迭代，1 个写线程持续插入
    // 验证读线程不崩溃（不要求读到全部最新数据）
@Test public void testLargeInsert()
    // 插入 100,000 个 key，验证迭代器输出有序
```

### 8.4 MemTableTest

```java
@Test public void testAddAndGetValue()
    // add(seq=1, VALUE, "key", "value")，get 应返回 "value"
@Test public void testGetAfterDelete()
    // add(seq=1, VALUE, "k", "v")，再 add(seq=2, DELETION, "k", "")
    // get(seq=3, "k") 应返回 deleted=true
@Test public void testSequenceNumberIsolation()
    // add(seq=1, VALUE, "k", "v1")，add(seq=2, VALUE, "k", "v2")
    // get(seq=1, "k") 应返回 "v1"（快照隔离）
    // get(seq=2, "k") 应返回 "v2"
@Test public void testGetNonExistentKey()
    // get 不存在的 key 应返回 found=false
@Test public void testMemoryUsageIncrease()
    // 添加数据后 approximateMemoryUsage() 应增加
@Test public void testIteratorForwardScan()
    // 插入多个 key-value，迭代器正向扫描应按 InternalKey 顺序输出
@Test public void testAddMultipleVersionsSameKey()
    // 同一 user_key 的多个版本在迭代器中按 seq 降序排列
```

### 8.5 LogWriterReaderTest

```java
@Test public void testWriteAndReadSingleRecord()
    // 写一条记录，读回来内容相同
@Test public void testWriteAndReadMultipleRecords()
    // 写 10 条不同长度的记录，按序读回验证
@Test public void testEmptyRecord()
    // 写空记录，读回应得到长度为 0 的数据
@Test public void testRecordSpanningMultipleBlocks()
    // 写一条 100KB 的记录（超过 32KB block）
    // 验证分片写入后能正确读回
@Test public void testRecordAtBlockBoundary()
    // 精心构造数据使记录恰好填满 block 末尾，验证下一条记录从新 block 开始
@Test public void testManySmallRecords()
    // 写 10000 条 1-100 字节的随机记录，全部读回验证内容
@Test public void testReadFromOffset()
    // 写 5 条记录，从第 3 条的 offset 开始读，应只读到后 3 条
@Test public void testChecksumVerification()
    // 写记录后手动破坏 CRC，读时应报告 corruption
```

### 8.6 BlockBuilderTest

```java
@Test public void testEmptyBlock()
    // 空 BlockBuilder finish() 应只包含 num_restarts=0
@Test public void testSingleEntry()
    // 写一个 entry，读回 key 和 value 正确
@Test public void testPrefixCompression()
    // 写 ["apple","v1"],["apply","v2"],["application","v3"]
    // 验证 "appl" 前缀被压缩，编码比三个完整 key 短
@Test public void testRestartPoints()
    // blockRestartInterval=3，写 9 个 key，验证有 3 个 restart point
@Test public void testSeekAfterDecode()
    // 构建 block 后用 Block.newIterator() 做 seek，验证定位正确
@Test public void testForwardIteration()
    // 顺序写 100 个 key，迭代器正向扫描输出顺序正确
@Test public void testLargeBlock()
    // 写 1000 个 key-value（value 各 100 字节），verify 所有能正确读回
```

### 8.7 TableBuilderReaderTest

```java
@Test public void testBuildAndOpenTable()
    // 构建含 100 个 entry 的 SSTable，用 Table.open() 打开，全部 Get 命中
@Test public void testTableIterator()
    // 打开 SSTable，迭代所有 entry，验证有序且内容正确
@Test public void testBloomFilterMiss()
    // 构建带 Bloom Filter 的 SSTable，查询不存在的 key
    // 验证 Bloom Filter 判定为 miss（避免磁盘 IO）
@Test public void testTableFooterMagicNumber()
    // 读取 SSTable 文件末尾，验证 magic number = 0xdb4775248b80fb57
@Test public void testMultipleDataBlocks()
    // 写入超过 4KB 数据，触发多个 Data Block，验证都能正确读取
@Test public void testTableCacheEviction()
    // 打开 100 个 SSTable（cache 容量 10），验证最老的被驱逐
@Test public void testApproximateOffsetOf()
    // 验证 approximateOffsetOf() 返回值在合理范围内
```

### 8.8 WriteBatchTest

```java
@Test public void testPutAndIterate()
    // batch.put("k1","v1").put("k2","v2")，forEach 验证两条记录
@Test public void testDeleteAndIterate()
    // batch.delete("k1")，forEach 验证 type=DELETION
@Test public void testMixedBatch()
    // put + delete 混合，验证顺序和内容
@Test public void testEncodeDecodeRoundtrip()
    // 编码后解码，count 和各条记录不变
@Test public void testSequenceNumberPersistence()
    // setSequenceNumber(100)，getSequenceNumber() = 100
@Test public void testApplyToMemTable()
    // batch 包含 put 和 delete，apply 到 MemTable 后用 Get 验证结果
@Test public void testClearBatch()
    // 写入后 clear()，count = 0，forEach 无回调
```

### 8.9 VersionEditTest

```java
@Test public void testEncodeDecodeRoundtrip()
    // 设置所有字段，encodeTo 后 decodeFrom，验证字段值一致
@Test public void testAddAndRemoveFiles()
    // addFile 两个，removeFile 一个，编解码后验证 newFiles 和 deletedFiles 集合
@Test public void testEmptyVersionEdit()
    // 空 VersionEdit 编解码不出错
@Test public void testComparatorNamePersistence()
    // setComparatorName("leveldb.BytewiseComparator")，编解码后一致
```

### 8.10 DBImplTest

```java
@Test public void testOpenAndClose()
    // Options.createIfMissing=true，open + close 不报错

@Test public void testPutAndGet()
    // put("k","v")，get("k") = "v"

@Test public void testDeleteAndGet()
    // put("k","v")，delete("k")，get("k") = null

@Test public void testOverwrite()
    // put("k","v1")，put("k","v2")，get("k") = "v2"

@Test public void testWriteBatch()
    // batch: put("a","1") + put("b","2") + delete("c")
    // write(batch)，验证 a=1, b=2, c=null

@Test public void testSnapshot()
    // put("k","v1")，取快照 snap
    // put("k","v2")
    // get(snap,"k") = "v1"（快照隔离）
    // get(null,"k") = "v2"（最新版本）

@Test public void testIterator()
    // put 若干 key，newIterator().seekToFirst() 开始正向扫描
    // 验证输出按字典序排列，内容正确

@Test public void testPersistenceAfterReopen()
    // put("k","v")，close，重新 open，get("k") = "v"（WAL 恢复）

@Test public void testLargeWrite()
    // 写入 100,000 个 key-value，触发 flush 和 compaction
    // 全部 get 命中，验证 compaction 后数据不丢失

@Test public void testConcurrentReadWrite()
    // 5 个写线程 + 5 个读线程并发操作 1 秒
    // 验证没有异常，读到的值总是有效值（不是损坏数据）

@Test public void testGetProperty()
    // 写入后查询 "leveldb.num-files-at-level0"，值 >= 0
    // 查询 "leveldb.stats"，返回非空字符串

@Test public void testCompactRange()
    // 写入大量数据触发多层，compactRange(null, null)
    // 验证 compaction 后 Level-0 文件数减少
```

---

## 9. 实现阶段划分

### Phase 1：公共基础 + 工具层（约 3-4 天）

**目标**：搭建 Maven 项目骨架，实现所有基础类。

实现内容：
- `pom.xml`（Java 8，JUnit 4）
- `common` 包：Slice、Status、ValueType、DbConfig、InternalKey、InternalKeyComparator、LookupKey
- `util` 包：Coding、Hash、BloomFilter、Crc32c、LRUCache

测试：CodingTest、BloomFilterTest

### Phase 2：内存层（约 2-3 天）

**目标**：实现 SkipList 和 MemTable。

实现内容：
- `memtable` 包：SkipList、MemTable

测试：SkipListTest、MemTableTest

### Phase 3：WAL 日志层（约 2 天）

**目标**：实现 WAL 的读写。

实现内容：
- `log` 包：LogFormat、LogWriter、LogReader

测试：LogWriterReaderTest

### Phase 4：SSTable 文件层（约 4-5 天）

**目标**：实现 SSTable 的完整读写。

实现内容：
- `table` 包：BlockHandle、BlockContents、BlockBuilder、Block、FilterBlock、FilterBlockBuilder、Footer、TableBuilder、Table、TableCache
- `iterator` 包：Iterator 接口、TwoLevelIterator

测试：BlockBuilderTest、FilterBlockTest、TableBuilderReaderTest

### Phase 5：迭代器扩展（约 1 天）

实现内容：
- `iterator` 包：MergingIterator

测试：MergingIteratorTest

### Phase 6：版本管理层（约 3-4 天）

**目标**：实现版本控制核心。

实现内容：
- `db` 包：FileName、FileMetaData、VersionEdit、Version、VersionSet、Compaction

测试：VersionEditTest

### Phase 7：主控层 DBImpl（约 4-5 天）

**目标**：实现完整的 DB API，包括 compaction 和崩溃恢复。

实现内容：
- `db` 包：Options、ReadOptions、WriteOptions、WriteBatch、Snapshot、SnapshotList、DB 接口、DBImpl

测试：WriteBatchTest、DBImplTest

---

*文档完毕。本文档作为 Java 实现的完整参考规范，所有实现细节均来自 google/leveldb C++ 源码（v1.23）。*
