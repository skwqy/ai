# Kafka 深度源码解析（写给初学者的架构全景）

> **本文基于的源码**：`D:\code\3rd\kafka`，版本 **4.5.0-SNAPSHOT**（main 分支，2026-10-05 克隆，Git commit `c2fbf6f52f78ecc1c11c6511242aade6a2c50b71`）。文中所有【源码证据】的文件路径与行号均为对该快照实际读取所得。
>
> **版本取舍说明**：Kafka 4.x 已**彻底移除 ZooKeeper**（3.x 为 KRaft 过渡期），组协调、事务协调均已迁移为 Java 实现（group-coordinator / transaction-coordinator 模块），消费端支持 KIP-848 新再平衡协议（但 `group.protocol` 默认仍为 `classic`）。Kafka 的核心机制——**分区日志、多副本 ISR、幂等生产者、事务、消费组位点管理**——自 0.11/2.x 以来骨架高度稳定，因此本文的机制分析对使用 Kafka 3.x / Spring Boot 3.x（spring-kafka 3.x）的读者同样适用；仅当涉及"代码在哪个模块哪个类"时，以本文的 4.x 布局为准。版本演进见 1.6 节。
>
> **阅读约定**：每章"先白话、后源码"——先用一两句人话讲清"这是什么、为什么需要它"，再给出源码证据链（`模块/src/main/.../类名` + 方法名 + 行号 + 代码片段）。行号只对该快照精确，读者用 IDEA 打开源码按类名 + 方法名定位即可。

## 如何读这份文档

如果你是 Kafka 初学者，推荐两遍读法：

- **第一遍（建立地图，1~2 小时）**：只读第一章（总览）每节的白话段、各章"本章小结"、第八章（可靠性专题）的 8.1 节和三张配置表。目标是能回答：一条消息从 `send()` 到被消费经过哪几步？Kafka 靠什么不丢消息？"重复消费"和"重复写"分别是谁的锅？
- **第二遍（深入源码）**：对照【源码证据】逐行读。顺序建议：第三章（生产者）→ 第四章（broker 写入与存储）→ 第八章（可靠性专题，把前两章的知识点串成答案）→ 第六章（消费者与消费组）→ 第七章（事务）→ 第二章（消息格式，随用随查）→ 第五章（KRaft，架构视角即可）。

**最快路径**：如果你只有一个问题——"客户端怎么配置才能保证消息不丢、不重复处理"——直接读第八章，那里把全文所有机制收拢成了配置清单和反模式清单。

---

# 一、总览：Kafka 的定位、设计哲学与整体架构

## 1.1 一句话定位

**Kafka 是一个分布式的、分区的、多副本的提交日志（commit log）系统**，在它之上长出了三个用途：消息系统（解耦与削峰）、存储系统（可回放的历史数据流）、流处理平台（Streams）。读懂本文只需抓住一个词——**日志（log）**：消息数据是日志，元数据是日志（KRaft），消费位点是个特殊的 topic（`__consumer_offsets`），事务状态也是日志（`__transaction_state`），连消费组/事务协调器的内存状态机都能用日志+快照重建。**Kafka 的所有"聪明事"，本质上都是"把状态放进日志，再从日志推导出结果"。**

## 1.2 设计哲学：读源码前先记住六句话

1. **磁盘顺序写 + 页缓存，就是数据库**。Kafka 不用 B+ 树、不维护堆内索引，写入就是"追加到文件末尾"，读取靠稀疏索引 + 零拷贝（sendfile）。它把"随机读写很慢"这个磁盘短板彻底绕开了。
2. **不依赖 fsync 的可靠性**。Kafka 默认**不刷盘**（依赖操作系统的页缓存 + 副本冗余）：单机挂了靠副本补，因此"不丢"的前提是**多副本 + acks=all**，而不是"配置刷盘"。
3. **一切状态放日志**。元数据（KRaft 的 `@metadata`）、消费位点（`__consumer_offsets`）、事务状态（`__transaction_state`）、新消费组状态机（group-coordinator 用快照+日志持久化）——全部复用同一套"日志 + 回放"的思路。
4. **客户端聪明、broker 哑**。分区选择、批量、重试、幂等序列号、再平衡的"订阅与分配"大部分逻辑在客户端；broker 只做"写入日志 + 副本同步 + 按位点读"。这让 broker 水平扩展极其容易。
5. **以吞吐为先的取舍**。毫秒级延迟可以做到，但设计的重心是"单个 broker 每秒几十万条、集群 TB 级数据"：微批（batch + linger.ms）、压缩在批次级做、拉模型（pull）让消费端按自己的节奏取数。
6. **多副本换高可用**。每个分区一个 leader + N 个 follower，写入由 leader 统一编排（**写入单调递增的 offset**），ISR（In-Sync Replicas）集合定义"哪些副本够格参与仲裁"。这是"不丢消息"的物理基础（详见 4.5、8.3 节）。

## 1.3 模块分层全景

以根目录 `settings.gradle` 的 include 列表为证（`settings.gradle:75`），4.x 的源码布局分为五层：

| 层 | 模块 | 职责（一句话） |
|----|------|----------------|
| **客户端层** | `clients` | Java 客户端：KafkaProducer / KafkaConsumer / AdminClient、网络层 NetworkClient、协议消息（common.message）、记录格式（common.record） |
| **核心服务层** | `core` | Broker 主体（Scala）：KafkaApis（全部 RPC 入口）、ReplicaManager（副本/写入/读取）、KafkaConfig、KafkaRaftServer |
| | `server` / `server-common` | Broker 侧 Java 抽象：BrokerServer、配置模型与公共工具 |
| **存储层** | `storage` | 日志引擎：UnifiedLog / LocalLog / LogSegment / LogManager / LogCleaner / ProducerStateManager（producer 幂等状态） |
| **共识与元数据层** | `raft` | KRaft 共识协议实现（KafkaRaftClient、QuorumState） |
| | `metadata` | 控制器与元数据镜像：QuorumController、MetadataDelta / MetadataImage（`org.apache.kafka.image`） |
| **协调器层** | `group-coordinator` | 消费组协调（新 Java 实现）：ClassicGroup（经典协议）、modern.ConsumerGroup（KIP-848 新协议）、OffsetMetadataManager（位点） |
| | `transaction-coordinator` | 事务协调（Java 化中的配置/状态类；主逻辑仍在 core 的 Scala `TransactionCoordinator`） |
| | `coordinator-common` / `share-coordinator` | 协调器公共运行时（CoordinatorRuntime：日志+快照的通用框架）；KIP-932 共享消费组的协调器 |
| **生态层** | `streams` | 流处理库（KafkaStreams、StreamsBuilder、状态存储） |
| | `connect` | 数据集成框架（Source/Sink 连接器，含 mirror-maker-2） |
| **工具层** | `tools` / `shell` / `trogdor` / `jmh-benchmarks` | CLI 工具、Kafka Shell（KIP-814）、测试框架、基准 |

**读源码的动线**：`clients`（你怎么用）→ `core`（请求怎么处理）→ `storage`（数据怎么落盘）→ `raft`/`metadata`（集群怎么协调）→ `group-coordinator`/`transaction-coordinator`（消费组与事务怎么协调）。

## 1.4 模块依赖图（以 imports 与模块布局实证）

依赖方向（箭头 = "依赖"）：

```
                       ┌────────────────────────────────────────────┐
                       │                streams / connect            │  生态层：只依赖 clients（+core 提供 broker）
                       └───────────────┬────────────────────────────┘
                                       │
   ┌───────────────────────────────────▼─────────────────────────────────────┐
   │                                core (Scala)                             │
   │  KafkaApis ──► ReplicaManager ──► storage (UnifiedLog/LogSegment)        │
   │      │               │                                                  │
   │      │               └──► group-coordinator / transaction-coordinator  │
   │      │                       └──► coordinator-common                  │
   │      └──► raft (KafkaRaftManager) ──► metadata (QuorumController)        │
   └─────────────────────────────────────────────────────────────────────────┘
            │                                      │
   ┌────────▼─────────┐                  ┌─────────▼────────┐
   │  server-common    │                  │ server (Java)    │
   └──────────────────┘                  └──────────────────┘

   ┌──────────────────────────────────────────────────────────────────────────┐
   │                                clients                                   │
   │  KafkaProducer / KafkaConsumer / AdminClient ──► NetworkClient ──► 协议   │
   │  （不依赖任何 Kafka 服务端模块；这是"客户端聪明"哲学的物理体现）          │
   └──────────────────────────────────────────────────────────────────────────┘
```

【源码证据】`core` 的 `KafkaApis.scala` 顶部同时 import 了 `org.apache.kafka.coordinator.group.*`（新组协调器）、`org.apache.kafka.storage.internals.log.*`（存储）、`org.apache.kafka.raft.*`、`org.apache.kafka.image.*`（元数据镜像），说明 core 是把上述模块"拼装"成 broker 的装配层；而 `clients` 模块的 import 里没有任何服务端模块——**客户端与服务端只通过协议（字节流）耦合**。

## 1.5 关键问题 → Kafka 方案映射（全文导览）

| 你关心的问题 | Kafka 的答案 | 本文位置 |
|---|---|---|
| 消息为什么快？ | 顺序追加 + 微批 + 稀疏索引 + 零拷贝 | 4.3、4.7 |
| 一条消息存在哪？ | 分区日志的 LogSegment 文件（.log/.index/.timeindex） | 2.1、4.3 |
| 生产端怎么不丢？ | acks=all + 重试 + 幂等（PID+序列号） | 3.6、8.2 |
| broker 端怎么不丢？ | 多副本 ISR + min.insync.replicas + 禁 unclean 选举 | 4.5、8.3 |
| 消费端怎么不丢？ | 先处理后提交位点（手动位移提交） | 6.5、8.4 |
| 为什么会重复？ | 重试必然带来重复；幂等/事务/业务去重逐层解决 | 3.7、7.1、8.5-8.7 |
| 跨分区原子写？ | 生产者事务（2PC + WriteTxnMarkers） | 第七章 |
| 消费-处理-生产的原子性？ | `sendOffsetsToTransaction` + `read_committed` | 7.1、8.6 |
| 消费组怎么扩容？ | 再平衡协议（经典 / KIP-848 新协议） | 6.3 |
| 集群元数据谁管？ | KRaft：控制器 + 元数据日志 | 第五章 |
| 顺序性怎么保证？ | 单分区内有序 + max.in.flight ≤ 5 + 幂等 | 3.4、8.2 |
| "落库"和"发消息"怎么原子？ | Outbox/本地消息表（"事务消息"是 RocketMQ 的特性，Kafka 没有） | 8.2.5 |

## 1.6 版本演进：从 ZooKeeper 到纯 KRaft

| 版本 | 关键变化 | 对本文的影响 |
|---|---|---|
| 0.8 (2013) | 引入**多副本机制**、消费组协议（基于 ZK 协调） | "ISR/acks"概念的起点 |
| 0.9 (2015) | **新 Java 消费端**（取代 Scala 旧消费者），消费组协调从 ZK 迁到 broker（GroupCoordinator），安全认证 | 第六章的骨架成形 |
| 0.10 (2016) | 消息带 timestamp；Kafka Streams 发布 | 2.2 的消息头结构 |
| **0.11 (2017)** | **幂等生产者（KIP-129）+ 事务（KIP-98）**；消息格式 v2（批次化） | 3.7、7、8 章的全部基石 |
| 2.3 (2019) | 粘性分区器（减少小批次） | 3.4 的 BuiltInPartitioner |
| 2.4 (2019) | **协作式再平衡**（KIP-429，增量迁移分区） | 6.3 |
| 2.8 (2021) | KRaft（KIP-500）早期预览：**去 ZooKeeper** | 第五章 |
| 3.3 (2022) | KRaft 生产可用（GA） | 同上 |
| 3.5~3.9 | ZK 模式标记废弃；分层存储（KIP-405）推进 | — |
| **4.0 (2025)** | **移除 ZooKeeper**；组协调器/事务协调器默认走 Java 新实现（KIP-848 新消费组协议 GA，`group.protocol=consumer`）；支持两阶段提交（KIP-939）；broker 与工具链最低 JDK 17（客户端 JDK 11） | 本文源码的形态；6.3 的双协议 |
| 4.1+ (2025~) | 共享消费组/队列语义（KIP-932，share groups）持续演进（`share-coordinator` 模块） | 1.3 的 share-coordinator |

> 4.x 源码中仍可见的双轨并存（截至本快照）：`ConsumerConfig.DEFAULT_GROUP_PROTOCOL = "classic"`（`clients/.../consumer/ConsumerConfig.java:121`），`KafkaConsumer` 按 `group.protocol` 委托给 `ClassicKafkaConsumer` 或 `AsyncKafkaConsumer`；服务端 `group-coordinator` 同时实现经典协议（JoinGroup/SyncGroup）与新协议（ConsumerGroupHeartbeat）。

## 1.7 全文章节地图

```
第一章 总览（你在这里）
第二章 数据模型与消息格式        —— 消息长什么样（RecordBatch v2）
第三章 生产者                    —— send() 的一生：拦截器→序列化→分区→累加器→Sender→网络
第四章 Broker 写入与存储         —— 请求进来之后：append→ISR→HW→落盘→读取（零拷贝）
第五章 KRaft 与元数据            —— 没有 ZooKeeper 之后：共识日志 + 控制器
第六章 消费者与消费组            —— poll() 的一生：fetch→消费→提交位点；再平衡双协议
第七章 事务与精确一次            —— 事务协调器、两阶段提交、read_committed
第八章 可靠性专题（重点）        —— Client 如何与 Kafka 机制配合：不丢、不重
第九章 贯通视图                  —— 三条时间线串起全部机制
第十章 附录                      —— 概念详解（ISR 等）、KRaft 原理、配置速查、类速查、源码入口清单
```

---

# 二、数据模型与消息格式（clients：common.record 包）

## 2.1 先说白话：Kafka 的一切建立在"分区日志"上

- **Topic** 只是逻辑名，**Partition（分区）** 才是数据的物理容器。每个分区是一串**只追加、不可变**的日志，消息在其中按写入顺序获得**单调递增的 offset**（分区内唯一）。
- 每个分区的日志物理上切成多个 **LogSegment**：一个 `.log` 文件 + 两个稀疏索引（`.index` 按位移、`.timeindex` 按时间戳）。
- 每个分区有 **1 个 leader 副本 + N 个 follower 副本**，读写都走 leader；follower 只做一件事——从 leader 拉数据追平。
- 消费以**消费组**为单位：组内成员**分摊**分区（任一时刻一个分区只归组内一个成员消费，每分区一个 committed offset），组与组之间**广播**（各自维护独立位点，互不影响地拿到全量数据）。分配机制详见 6.3。

这一章讲"消息"本身长什么样，因为**幂等、事务、压缩全部编码在消息批次结构里**，不懂批次格式就看不懂后面的去重逻辑。

## 2.2 RecordBatch v2：批次是第一公民

Kafka 网络上传输的从来不是单条消息，而是**批次（RecordBatch）**。v2 格式（`magic=2`，0.11 引入）的批次头：

【源码证据】`clients/src/main/java/org/apache/kafka/common/record/internal/DefaultRecordBatch.java:104-130`（常量按字段长度累加计算，注释为展开后的字节偏移）：

```java
// DefaultRecordBatch.java —— 批次头的语义字段
static final int BASE_OFFSET_OFFSET = 0;                 // baseOffset：该批次首条的基准位移
static final int LENGTH_OFFSET = 12;                     // 批次长度
static final int PARTITION_LEADER_EPOCH_OFFSET = 24;     // 写入时的 leader epoch
static final int MAGIC_OFFSET = 32;                      // 消息格式版本（v2 = 2）
public static final int CRC_OFFSET = 36;
static final int ATTRIBUTES_OFFSET = 40;                 // 压缩类型、时间戳类型、是否事务/控制记录
public static final int LAST_OFFSET_DELTA_OFFSET = 43;   // 批内最后一条的相对位移（Δ）
static final int BASE_TIMESTAMP_OFFSET = 47;
static final int MAX_TIMESTAMP_OFFSET = 55;
static final int PRODUCER_ID_OFFSET = 63;                // ★ PID：幂等/事务的身份证
static final int PRODUCER_EPOCH_OFFSET = 71;             // ★ epoch：会话代次（防僵尸）
static final int BASE_SEQUENCE_OFFSET = 73;              // ★ baseSequence：幂等去重的序列号
public static final int RECORDS_COUNT_OFFSET = 77;
```

三个关键字段（★）把"幂等"直接写进了消息格式：

1. **producerId（PID）**：生产者实例的身份。开启幂等/事务后由 broker 的事务协调器分配，进程内唯一。
2. **producerEpoch**：同一 transactional.id 的"会话代次"。老生产者被新实例顶替时 epoch 递增，旧 epoch 的写入会被拒绝（**僵尸防护**）。
3. **baseSequence**：批次内第一条消息的序列号。broker 靠它判断"这是重发的旧批次"还是"乱序的新批次"（4.x 的去重实现见 3.7 与 4.2 的 `checkSequence`）。

批次内的单条消息（`DefaultRecord`）只存**相对位移（offsetDelta）**、**相对时间戳（timestampDelta）**、变长的 key/value/headers。压缩作用于整批：**压缩率取决于批量，这也是 `batch.size`/`linger.ms` 存在的根本原因**（3.4）。

## 2.3 为什么消息格式长这样（设计取舍）

- **offset 是 broker 分配的**：客户端发送时批次头 baseOffset 填 0，broker 追加时统一改写（见 4.3 的 `append`，顺序写时顺手校正），消费端拿到的 offset 才是真实存储位置。这样把"全局排序"职责收归 leader，客户端无需任何协调。
- **PID/epoch/sequence 放在批次头而不是每条消息里**：去重以批次为粒度做（broker 保存每 PID 每分区的"最后 5 个批次的 (baseSequence, lastSeq, offset)" 元数据，`storage/.../log/BatchMetadata.java`），内存开销 O(1)。
- **控制记录（Control Record）**：事务的 COMMIT/ABORT 标记也是普通批次（`ATTRIBUTES` 中 `isControlBatch` 位），写进**数据日志本身**。这是"事务标记"能与数据同分区内序的关键（第七章）。

## 2.4 本章小结

消息格式 v2 = **批次化的日志条目**，它用三个字段（PID、epoch、baseSequence）把"可靠性去重"下沉到协议层；用"相对位移/相对时间戳 + 批级压缩"把存储与网络开销压到最低。后面所有章节的机制，都是围绕"怎么高效地把批次写进日志、怎么可靠地复制它、怎么去重、怎么原子地跨越多个分区"展开。

---

# 三、生产者客户端（clients：producer 包）——一次 `send()` 的一生

## 3.1 模块定位与包结构

【源码证据】`clients/src/main/java/org/apache/kafka/clients/producer/`：

| 类 | 角色 |
|---|---|
| `KafkaProducer` | 门面：装配所有组件，暴露 send/beginTransaction 等 API（单线程安全，内部即"用户线程 + Sender 线程"两套并发域） |
| `internals/RecordAccumulator` | 批量累加器：每个 TopicPartition 一个双端队列 `Deque<ProducerBatch>`，用户线程往里塞，Sender 从里取 |
| `internals/Sender` | 后台 I/O 线程：把就绪批次发出去、处理响应与重试 |
| `internals/TransactionManager` | 幂等/事务状态机：PID、epoch、每分区序列号、事务请求队列 |
| `internals/BuiltInPartitioner` | 粘性分区器（2.3+）：无 key 消息粘住一个分区直到批次切换 |
| `ProducerConfig` | 全部配置项与默认值 |

## 3.2 构造器装配：new 一个 KafkaProducer 发生了什么

【源码证据】`KafkaProducer.java:380-560`（构造器）：

1. 解析配置 → 构建 `Serializer`、`Partitioner`、重试 Backoff；
2. `this.transactionManager = configureTransactionState(config, logContext);`（`KafkaProducer.java:449`）——**只要 `enable.idempotence=true`（4.x 默认 true）或配置了 `transactional.id`，TransactionManager 就存在**；
3. 创建 `RecordAccumulator`（buffer 池 `BufferPool` 按 `buffer.memory` 32MB 切块，块大小 = `batch.size` 16KB）；
4. 创建 `Sender`（Runnable）→ 包成 `KafkaThread` 启动：`ioThread.start()`——从此它永续轮询；
5. `Metadata`（集群元数据缓存）与 `NetworkClient` 挂到同一个 `Selector` 上。

**要点**：用户调用线程只做"序列化 + 入队"，网络与重试全部是 Sender 的职责——这两套域靠 `RecordAccumulator` 的队列解耦，靠 `Future`/`Callback` 回传结果。

## 3.3 `send()` 主流程（doSend 逐段）

【源码证据】`KafkaProducer.java:1143`（send 入口）→ `1176`（doSend）：

```java
// KafkaProducer.java:1143
public Future<RecordMetadata> send(ProducerRecord<K, V> record, Callback callback) {
    // 拦截器先行（onSend 可修改记录）
    ProducerRecord<K, V> interceptedRecord = this.interceptors.onSend(record);
    return doSend(interceptedRecord, callback);
}
```

doSend 的骨架（`KafkaProducer.java:1176-1260`）：

```java
private Future<RecordMetadata> doSend(ProducerRecord<K, V> record, Callback callback) {
    AppendCallbacks appendCallbacks = new AppendCallbacks(callback, this.interceptors, record);
    try {
        throwIfProducerClosed();
        throwIfInPreparedState();                                  // ① 2PC prepared 态禁止再发（:1163）

        // ② 等 topic 元数据就绪（首次发往该 topic 时最多阻塞 max.block.ms）
        clusterAndWaitTime = waitOnMetadata(record.topic(), record.partition(), nowMs, maxBlockTimeMs);

        // ③ 序列化 key / value
        serializedKey   = keySerializerPlugin.get().serialize(...);
        serializedValue = valueSerializerPlugin.get().serialize(...);

        // ④ 选分区（显式分区 > key hash > 粘性分区）
        int partition = partition(record, serializedKey, serializedValue, cluster);

        // ⑤ 预检大小（超 buffer.memory/max.request.size 直接抛 RecordTooLargeException）
        ensureValidRecordSize(serializedSize);

        // ⑥ 追加进累加器（真正决定"这条消息放进哪个批次"）
        RecordAccumulator.RecordAppendResult result =
            accumulator.append(record.topic(), partition, timestamp, serializedKey,
                               serializedValue, headers, appendCallbacks, remainingWaitMs, nowMs, cluster);

        // ⑦ 事务进行中：把分区登记进事务（Sender 不会发未登记分区的批次）
        if (transactionManager != null) {
            transactionManager.maybeAddPartition(appendCallbacks.topicPartition());
        }

        // ⑧ 批满或新批：唤醒 Sender 立刻工作
        if (result.batchIsFull || result.newBatchCreated) {
            this.sender.wakeup();
        }
        return result.future;
    } ...
}
```

【源码证据】`RecordAccumulator.java:285`（append）：

- 每个 `(topic, partition)` 一条 `Deque<ProducerBatch>`；加锁后先 `tryAppend`——**能塞进队尾的旧批次就直接塞**（零拷贝语义上的复用同一块 ByteBuffer）；
- 塞不下就 `free.allocate(size, maxTimeToBlock)` 从缓冲池拿内存（不够时**阻塞调用线程**，最多 `max.block.ms`）→ `appendNewBatch` 新建批次；
- 无 key 消息的分区由 `BuiltInPartitioner` 的**粘性分区**决定：粘住一个分区直到该批次发送，避免"每条消息一个 16KB 小批次"的碎片化。

## 3.4 Sender 线程：ready → drain → 发送 → 处理响应

【源码证据】`Sender.java:310`（runOnce）：

```java
void runOnce() {
    if (transactionManager != null) {
        transactionManager.maybeResolveSequences();       // 排队的事务请求逐个发
        ...
        transactionManager.bumpIdempotentEpochAndResetIdIfNeeded(); // 需要时发 InitProducerId
        if (maybeSendAndPollTransactionalRequest()) return;         // 事务请求独占网络
    }
    long pollTimeout = sendProducerData(currentTimeMs);  // 常规数据发送
    client.poll(pollTimeout, currentTimeMs);             // 网络轮询：读响应、跑定时器
}
```

`sendProducerData`（`Sender.java:380`）的判定链：

1. `accumulator.ready(...)`（`RecordAccumulator.java:892`）：哪些分区"该发了"？——`linger.ms`（默认 5ms）到期、或批次满、或内存紧张/关闭中；
2. leader 未知 → 强制刷元数据；节点未连接 → 等连接；
3. `accumulator.drain(...)`（`:1083`）：按节点把就绪批次打包成 ProduceRequest（**单请求最大 `max.request.size`**），同时 `addToInflightBatches`（`Sender.java:219`）——**进入 InFlight 集合，即"已发送未确认"**；
4. `guaranteeMessageOrder`（`Sender.java:93`，构造时传 `maxInflightRequests == 1`，`KafkaProducer.java:593`）为真时把已 drain 的分区 **mute**，直到前批确认才 unmute——这只在 `max.in.flight=1` 时启用。**开幂等时即使 `max.in.flight ≤ 5` 也有序，靠的是另一套机制**：每批带序列号，乱序批次被 broker 以 `OUT_OF_ORDER_SEQUENCE_NUMBER` 拒绝，失败批次经 `reenqueue` 回到队列**头部**（`RecordAccumulator.reenqueue`），后续批次重试时自然恢复顺序（`ProducerConfig.java:300-305` 的文档明确说明：幂等开或重试关时顺序保持）；
5. 过期批次清理：`failExpiredBatches`（`Sender.java:362`）——批次创建超过 `delivery.timeout.ms`（默认 120s）就按 TimeoutException 失败并回调用户；
6. `sendProduceRequests(batches, now)` → `NetworkClient` 写 socket。

**响应处理与重试**（`Sender.java:671` completeBatch）：

```java
if (error != Errors.NONE) {
    if (canRetry(batch, response, now)) {          // 可重试错误（如 NOT_LEADER、超时、磁盘抖动）
        reenqueueBatch(batch, now);                // ★ 批次原样放回队列头部重发
    } else if (error == Errors.DUPLICATE_SEQUENCE_NUMBER) {
        // broker 说"这条我已经收过了"→ 静默当作成功，只是没有有效 offset 可回
        completeBatch(batch, response);
    } else {
        failBatch(batch, response, ...);           // 重试耗尽/不可重试 → 用户 Callback 收到异常
    }
}
```

`canRetry`（`Sender.java:876`）的两个判据：错误可重试（`AbstractResponse` 的 retriable 位）且 `attempts < retries`；事务场景还要问 `transactionManager.canRetry`（`TransactionManager.java:1043`）。

## 3.5 元数据：`waitOnMetadata` 与 `Metadata` 缓存

首次发往某 topic 时，`doSend` 会同步等待元数据（`Metadata.java` 缓存 `Cluster`，由 Sender 的 `client.poll` 顺带回包刷新）。topic 不存在时若 `allow.auto.create.topics=true`（消费端/新生产端自动建 topic 各有开关），元数据请求会触发 broker 自动建 topic。**注意 `max.block.ms`（默认 60s）耗尽直接抛 TimeoutException——"发不出去"在 Kafka 是会阻塞用户线程的少数场景之一**。

## 3.6 幂等生产者：PID + epoch + 序列号

**白话**：网络重试必然带来"broker 到底收没收到"的不确定性（请求丢了和响应丢了在客户端看起来一样）。Kafka 0.11 的答案是给每个生产者发一张"身份证"（PID+epoch），每个批次带序列号，broker 端缓存"最近收到的序列号"，**重复的批次直接拒绝**。

客户端职责：

- 开幂等（`enable.idempotence=true`，`ProducerConfig.java:574-578`，**4.x 默认开启**）时，Sender 第一件事是发 `InitProducerId` 拿 PID；
- 每个分区维护单调递增的 `sequenceNumber`（`TransactionManager.java:721` incrementSequenceNumber），出队时给批次盖上 `baseSequence`；
- 收到 `UNKNOWN_PRODUCER_ID`/乱序错误时按 `canRetry`（`TransactionManager.java:1043`）决定：回退序列、bump epoch、或放弃。

broker 职责（详见 4.2 的存储侧去重）：`ProducerStateManager` 为每个 PID 每分区保存最近批次的序列号窗口，新批次必须**恰好接在上一批之后**（`inSequence`：`nextSeq == lastSeq + 1 || (nextSeq == 0 && lastSeq == Integer.MAX_VALUE)`，`ProducerAppendInfo.java:208`），重复的（已在窗口内）→ 返回成功但不落盘；乱序的 → `OutOfOrderSequenceException`。

**局限**（这就是为什么还需要事务和业务幂等）：

1. 幂等只在**单分区**上有效（序列号按分区独立），跨分区不原子；
2. PID 是**进程实例级**的：应用重启拿到新 PID，"上次发了一半"这件事 broker 不知情——**跨会话没有去重**；
3. broker 只保留每 PID 最近 5 个批次的元数据窗口，更久的重复探测不到（`max.in.flight` 上限为 5 的根因，`ProducerConfig.java:296`）。

## 3.7 事务 API：客户端视角

【源码证据】`KafkaProducer.java`：

| API | 行号 | 做了什么 |
|---|---|---|
| `initTransactions()` | 730 → `TransactionManager.initializeTransactions`（`TransactionManager.java:321`） | 发 `InitProducerId(transactionalId)`：**同一 transactional.id 的旧实例被 fence（epoch 递增）**；idempotent 生产者这里也拿 PID |
| `beginTransaction()` | 757 | 本地进入 IN_TRANSACTION 状态（不发请求，纯客户端标记） |
| `send()` | 1143 | 与平时相同，但 Sender 会把批次所属分区先 `AddPartitionsToTxn` 登记给协调器 |
| `sendOffsetsToTransaction(offsets, groupMetadata)` | 818 | 把"消费组位点提交"也纳入本事务（发给 `AddOffsetsToTxn` + `TxnOffsetCommit`） |
| `commitTransaction()` / `abortTransaction()` | 938 / 972 | 触发 `EndTxn` 请求 → 协调器两阶段提交（第七章） |

事务状态机（`TransactionManager.java:1142` transitionTo）覆盖 `UNINITIALIZED → INITIALIZING → READY/IN_TRANSACTION → COMMITTING/ABORTING → ...`，**任何致命错误会把状态机打进 FATAL，此后 Sender 拒绝发送一切**（`Sender.java:311-319`）。

## 3.8 本章小结

`send()` = **拦截器 → 序列化 → 分区 → 入队（可能阻塞拿内存）**，返回的 Future 在"批次被 broker 确认（或失败）"时完成；**Sender 线程负责一切网络事务**：凑批、InFlight 管理、超时、重试、幂等序列号、事务请求。可靠性相关最核心的三件事都发生在这里：**acks 判定（响应到没到）、重试（reenqueueBatch）、幂等（序列号盖章）**。这三件事如何在配置层组合成"不丢不重"，见第八章。

---

# 四、Broker 写入路径与存储引擎（core + storage）

## 4.1 模块定位：一次 ProduceRequest 的完整旅程

【源码证据】调用链（含行号）：

```
KafkaApis.handleProduceRequest                 core/.../KafkaApis.scala:401
  └─► ReplicaManager.handleProduceAppend       core/.../ReplicaManager.scala:697
        └─► appendRecords(requiredAcks=...)    ReplicaManager.scala:638
              └─► Partition.appendRecordsToLeader  core/.../Partition.scala:1221
                    └─► UnifiedLog.appendAsLeader   storage/.../log/UnifiedLog.java:1033
                          └─► UnifiedLog.append     UnifiedLog.java:1128
                                ├─► LogValidator.validateMessagesAndAssignOffsets  （校验/压缩/分配 offset）
                                └─► LocalLog.append → LogSegment.append            （真正写文件）
```

## 4.2 leader 写入的两道闸门：min.insync.replicas 与写入校验

【源码证据】`Partition.scala:1221-1252`（appendRecordsToLeader）：

```scala
def appendRecordsToLeader(records, origin, requiredAcks, ...): LogAppendInfo = {
  val (info, leaderHWIncremented) = inReadLock(leaderIsrUpdateLock, () => {
    leaderLogIfLocal match {
      case Some(leaderLog) =>
        val minIsr = effectiveMinIsr(leaderLog)
        val inSyncSize = partitionState.isr.size
        // ★ acks=-1 时，ISR 数量不足 min.insync.replicas → 直接拒绝写入
        if (inSyncSize < minIsr && requiredAcks == -1) {
          throw new NotEnoughReplicasException(...)
        }
        val info = leaderLog.appendAsLeader(records, this.leaderEpoch, origin, ...)
        (info, maybeIncrementLeaderHW(leaderLog))     // 写入后尝试推进高水位
      ...
```

- **闸门一**：`acks=all(-1)` 时 ISR 小于 `min.insync.replicas` → `NotEnoughReplicasException`，生产端收到失败可重试。**这就是"宁可拒绝写入也不丢数据"的实现**（配置建议见 8.3）。
- **闸门二**：`UnifiedLog.append`（`UnifiedLog.java:1128`）内做三件事——有效性分析（`analyzeAndValidateRecords`：裁掉坏字节/空批次）、`LogValidator` 校验与压缩（CRC、时间戳类型、重压缩）、**为每批分配最终 offset 并写本地日志**。
- 追加后 leader 顺手 `maybeIncrementLeaderHW`（见 4.5）。

## 4.3 顺序写与稀疏索引：LogSegment

【源码证据】`storage/.../log/LogSegment.java:250`（append）：

```java
public void append(long largestOffset, MemoryRecords records) throws IOException {
    int physicalPosition = log.sizeInBytes();          // 当前文件物理位置
    ...
    long appendedBytes = log.append(records);          // ★ FileRecords：ByteBuffer + FileChannel 顺序追加
    for (RecordBatch batch : records.batches()) {
        ...
        if (bytesSinceLastIndexEntry > indexIntervalBytes) {      // 每 4KB（log.index.interval.bytes）
            offsetIndex().append(batchLastOffset, physicalPosition);   // 稀疏索引：offset → 物理位置
            timeIndex().maybeAppend(maxTimestampSoFar(), ...);          // 时间戳索引
            bytesSinceLastIndexEntry = 0;
        }
    }
}
```

三个性能事实：

1. **写入 = 顺序追加**。没有随机写，没有原地更新——这是 Kafka 吞吐的物理基础（设计哲学 1）。
2. **索引是稀疏的**（每 `index.interval.bytes` 字节一条），定位消息 = 二分索引找到"≤目标 offset 的最近条目"再顺序扫。省索引空间，代价是少量扫描。
3. **页缓存即缓存**：写进 `FileChannel` 后由 OS 决定何时落盘（`LogSegment.flush()`，`LogSegment.java:628`，仅在显式配置时被调用）。**Kafka 的持久性主张是"副本冗余"而非"刷盘"**——这是 8.3 节配置观的前提。

## 4.4 acks 的 broker 侧语义：立即回 or 等副本

【源码证据】`KafkaApis.scala:401`（handleProduceRequest）→ `512-533`：

- `acks=0`：broker **不回响应**（出错只能靠主动断连让客户端感知，`KafkaApis.scala:514-526`）——最快但等于"只写页缓存，且不告诉你成没成"；
- `acks=1`：写入 leader 本地日志即回（**此时 follower 还没来得及拉走**，leader 挂掉就可能丢——8.3 详述）;
- `acks=all`：本地写入完成 → `ReplicaManager.appendRecords` 把响应包进 **DelayedProduce**（`ReplicaManager.scala:638` 中的 `maybeAddDelayedProduce`），挂到 Purgatory 定时器上，**等待 ISR 中足够多的副本拉走该批次后才回包**（配合 4.2 的 min.isr 闸门）。follower 追上了（`updateFollowerFetchState`）或超时（`request.timeout.ms`，默认 30s），响应才放行。

## 4.5 高水位（HW）：可读边界与"已提交"的边界

**白话**：LEO（Log End Offset）= 每个副本"下一条待写位置"；**高水位 HW = leader 眼中"所有 ISR 都已复制到"的最大位移**。HW 之前的消息才算"可见/已提交"，之后的（可能只存在于 leader）不允许消费者读——这是"消费端读到的消息不会因 leader 切换而消失"的保证。

【源码证据】两处：

1. follower 的进度怎么进 leader 的账本：`Partition.scala:769`（updateFollowerFetchState，follower 每次 Fetch 上报自己的 LEO）→ 触发 `maybeIncrementLeaderHW`（`Partition.scala:1012`）：

```scala
private def maybeIncrementLeaderHW(leaderLog: UnifiedLog, currentTimeMs: Long = ...): Boolean = {
    if (isUnderMinIsr) { return false }          // ISR 不足 min.isr 时不动 HW
    var newHighWatermark = leaderLog.logEndOffsetMetadata
    // HW = ISR（含"快追上但还没正式进 ISR"的副本）中 LEO 的最小值
    remoteReplicasMap.forEach { (_, replica) =>
        if (replicaState.logEndOffsetMetadata.messageOffset < newHighWatermark.messageOffset && ...)
            newHighWatermark = replicaState.logEndOffsetMetadata
    }
    leaderLog.maybeIncrementHighWatermark(newHighWatermark)
    ...
}
```

2. 消费端读取时被夹在 HW 之下：`ReplicaManager.fetchMessages`（`ReplicaManager.scala:1665`）→ `Partition.readRecords`（`Partition.scala:1369`），返回的 `FetchPartitionData` 带 `highWatermark` 与 `lastStableOffset`（`ReplicaManager.scala:1831/1855`——`read_committed` 消费者只能读到 LSO 之前的数据，见 7.4）。

## 4.6 副本同步：follower 是"只会拉日志的哑客户端"

【源码证据】`core/.../server/AbstractFetcherThread.scala:112`（doWork）/ `:318`（processFetchRequest）：每个 follower broker 对每个 leader broker 维持一条 `ReplicaFetcherThread`，循环发 Fetch 请求（带自己的 LEO）。leader 回包后 follower 追加本地日志（`Partition.appendRecordsToFollowerOrFutureReplica`，`Partition.scala:1191`）。

ISR 的动态调整：

- **扩张**：follower 追平后由 `maybeExpandIsr`（`Partition.scala:878`）在 leader 的下一次 fetch 处理中纳入 ISR（恢复副本只需追上 LEO，**不需要重启或人工干预**——这是"挂一台 broker 拉走流量、修好再挂回来"的日常运维基础）；
- **收缩**：落后超过 `replica.lag.time.max.ms`（默认 30s）的副本被踢出 ISR（`Partition.maybeShrinkIsr`，`Partition.scala:1091`；调度入口 `ReplicaManager.maybeShrinkIsr`，`ReplicaManager.scala:2109`）。

**关键设计**：ISR 是"已确认能持续跟上的副本"集合，而非"配置的副本列表"。`acks=all` 的 "all" 指的是**当前 ISR**，不是全部副本——所以**副本数多 ≠ 更安全，min.insync.replicas 才是安全底线**（8.3）。

## 4.7 读取路径：零拷贝与"按位点读"

【源码证据】`KafkaApis.handleFetchRequest`（`KafkaApis.scala:568`）→ `ReplicaManager.fetchMessages`（`:1665`）→ `Partition.readRecords`（`Partition.scala:1369`）→ `LogSegment.read`（`LogSegment.java:435`）→ 返回 `FileRecords` 切片。`FetchResponse` 直接把 `FileRecords` 引用塞进响应对象，网络发送时走 `FileRecords.writeTo(TransferableChannel...)`（`clients/.../common/record/internal/FileRecords.java:280`）——**数据从页缓存直接进网卡（sendfile），不经过 JVM 堆**。这就是"Kafka 消费者几乎不占 broker CPU"的由来。

TLS 与压缩的例外：加密（SSL）与"服务端重压缩"场景无法 sendfile，退化为普通拷贝——吞吐会显著下降，这是 8.9 反模式里"别乱开 broker 端压缩转换"的出处。

## 4.8 日志保留与压实

- **按时间/大小删除**：`LogManager` 定期调度 `UnifiedLog.deleteOldSegments`，删除起点由 `retention.ms`/`retention.bytes` 决定，只删除"整个段都可删"的段（避免段内部分删除）；删除会推进 log start offset——它也是幂等生产者状态失效的边界之一（`TransactionManager.canRetry` 中的 `logStartOffset` 判断，`TransactionManager.java:1043`）。
- **按 key 压实（compaction）**：`LogCleaner`（`storage/.../log/LogCleaner.java`）为 `cleanup.policy=compact` 的 topic 工作：`Cleaner.buildOffsetMap`（`Cleaner.java:714`）把"每个 key 的最新 offset"建进内存去重表，`cleanSegments`（`:228`）把旧段重写为"每 key 只留最新一条"。`__consumer_offsets`、`__transaction_state`、新组协调器的快照 topic 都靠它维持"每 key 最新值"的语义。

## 4.9 本章小结

写入链路 = **闸门（min.isr）→ 顺序追加（含校验/压缩/offset 分配）→ 副本拉取 → HW 推进**；读取链路 = **稀疏索引定位 → HW/LSO 裁剪 → 零拷贝发送**。Kafka 的"可靠性"不是靠刷盘，而是靠**"leader 统一编序 + ISR 复制 + HW 定义可读边界"**这套复制协议；"高性能"则来自**顺序写、微批、稀疏索引、零拷贝**四个物理事实。理解了 HW，才能理解为什么 acks=all + min.insync.replicas=2 能同时给出"不丢"与"可读的一致性"。

---

# 五、KRaft 与元数据（raft + metadata）

## 5.1 为什么去掉 ZooKeeper

旧架构里，controller 选举、broker 注册、topic 元数据都放在 ZK，broker 与 controller 通过 ZK watch 感知变化。问题：**两套一致性系统**（ZK 的 ZAB + Kafka 的 ISR 协议），元数据在"ZK ↔ controller ↔ broker"之间传播，故障切换分钟级、扩容到百万分区时 watch 风暴。KRaft（KIP-500）的答案：**Kafka 自己维护一个 Raft 共识日志（`@metadata` topic），控制器集群（quorum）就是它的选举组，broker 是它的只读追随者**。

## 5.2 KafkaRaftManager：共识即日志

【源码证据】`core/.../kafka/raft/KafkaRaftManager.scala:87`（class KafkaRaftManager）封装了：

- `raft/.../KafkaRaftClient.java:170`（class KafkaRaftClient）：Raft 协议主体——选举（RequestVote，响应处理 `handleVoteResponse`，`KafkaRaftClient.java:953`）、复制（ leader 给 voter 发 Fetch/RecordBatch）、线性化读；`QuorumState.java` 维护 Voter/Follower/Candidate/Leader 状态机（`CandidateState`/`FollowerState`/`ProspectiveState` 等）；
- 元数据日志本身就是一个 `KafkaMetadataLog`（复用普通分区日志的追加/索引机制——**共识日志与数据日志是同一套存储引擎**）。

## 5.3 QuorumController：事件驱动的状态机 + 日志回放

【源码证据】`metadata/.../controller/QuorumController.java:177`（class QuorumController implements Controller）：

- **单线程事件模型**：所有控制器操作都是 `ControllerEvent`，经 `eventQueue` 串行执行（`handleEventEnd`，`QuorumController.java:546`）——天然免锁；
- **写入 = 追加元数据日志**：控制器要改元数据（建 topic、换 leader、挂 broker），先把"变更记录"追加到 KRaft 日志，等日志提交后回放到内存；
- **内存 = MetadataImage**：`metadata/.../image/MetadataImage.java` 与各 `*Delta` 类（ClusterImage、ConfigurationImage…）构成"快照 + 增量"的树，每次回放 `MetadataDelta → MetadataImage` 生成新快照。**broker 通过 KRaft 的 Fetch 拉元数据日志在本地构建同样的 MetadataImage（BrokerMetadataPublisher）**——因此 broker 处理请求不再需要回 controller 问"这个 topic 的 leader 是谁"，本地缓存即全量视图。

## 5.4 对使用者的影响

- 故障切换从"分钟级（ZK+controller 注册）"到"秒级（Raft 选举）"；
- 控制器与 broker 合并部署（`process.roles=broker,controller`）或分离部署；
- **配置面**：`controller.quorum.voters`（或 4.1 的动态 quorum）替代了 ZK 的全部连接配置；`zookeeper.connect` 在 4.0 已删除。

## 5.5 本章小结

KRaft 把"集群一致性"统一到了 Kafka 最擅长的领域：**日志**。共识是日志（Raft 复制）、元数据是日志（`@metadata`）、控制器的内存是日志的回放缓存（MetadataImage）。对应用开发者而言，KRaft 不改变客户端协议，但让集群运维与分区规模上限（百万级分区）跨了一个数量级。（原理的完整展开——Raft 入门、角色模型、选举与快照、与 ZK 模式对比——见附录 10.6。）

---

# 六、消费者与消费组（clients consumer 包 + group-coordinator）

## 6.1 模块定位：一个门面、两套实现

【源码证据】`clients/.../consumer/KafkaConsumer.java:556`（`private final ConsumerDelegate<K, V> delegate;`）：

```java
// KafkaConsumer 按 group.protocol 委托：
//   classic → ClassicKafkaConsumer（经典协议：JoinGroup/SyncGroup，客户端做分配协商）
//   consumer → AsyncKafkaConsumer（KIP-848：服务端分配，后台线程心跳+增量确认）
public ConsumerRecords<K, V> poll(final Duration timeout) {   // KafkaConsumer.java:987
    return delegate.poll(timeout);
}
```

`group.protocol` 默认仍为 `classic`（`ConsumerConfig.java:121`，DEFAULT_GROUP_PROTOCOL = "classic"）。**本章 6.2-6.5 的机制两套实现通用；6.3 讲双协议的差异。**

## 6.2 poll 主循环：消费是"拉"出来的

【源码证据】`AsyncKafkaConsumer.java:935`（poll；classic 实现骨架相同）：

```java
public ConsumerRecords<K, V> poll(final Duration timeout) {
    Timer timer = time.timer(timeout);
    ...
    do {
        wakeupTrigger.maybeTriggerWakeup();
        checkInflightPoll(timer, firstPass);
        final Fetch<K, V> fetch = pollForFetches(timer);      // 拉一批
        if (!fetch.isEmpty()) {
            sendPrefetches(timer);                            // ★ 提前发下一轮 Fetch（流水线化）
            return interceptors.onConsume(new ConsumerRecords<>(fetch.records(), fetch.nextOffsets()));
        }
    } while (timer.notExpired());
    return ConsumerRecords.empty();
}
```

要点：

- **poll() 返回前就已经更新了 position**（fetch 到手即 advance）——这直接决定"重复消费"的窗口：**poll 返回 → 你处理 → 提交位点**之间任何一次崩溃，都会让这批消息被重发一遍（8.4 的起点）；
- Fetch 由后台网络线程执行（AsyncKafkaConsumer 的 `applicationEventQueue`/`backgroundEventQueue`，`AsyncKafkaConsumer.java:374/511`；事件处理器 `ApplicationEventProcessor`，`:555`），用户线程只等结果——**KafkaConsumer 仍是非线程安全的单线程门面**（`acquireAndEnsureOpen` 用引用计数锁保证）；
- 拉取量参数：`fetch.min.bytes`（1）、`fetch.max.wait.ms`（500）、`max.partition.fetch.bytes`（1MB，`ConsumerConfig.java:212/235/250`）——延迟与吞吐的杠杆。

## 6.3 消费组与再平衡：双协议对比

**白话**：消费组解决"谁来消费哪些分区"。组内每个分区同一时刻只归一个成员；成员上下线时重新分配 = **再平衡（rebalance）**。

### 分配关系速览：成员、线程与"组内分摊、组间广播"

在读两种协议之前，先把三个最容易混淆的关系钉死：

**① 组的基本单位是"消费者实例"，不是线程。** 组里占坑的 member id 对应一个运行中的 `KafkaConsumer` 对象；实践中通常一个线程跑一个实例（`KafkaConsumer` 非线程安全，见 6.2）——一个 JVM 起 3 个实例就是组里 3 个成员（spring-kafka 的 `ConcurrentMessageListenerContainer` 配 `concurrency=3` 即此效果）。

**② 组内 = 分摊，组间 = 广播。** `subscribe()` 模式下，协调器把 topic 的分区**平均分配**给组内成员（分配策略 `partition.assignment.strategy`：Range/RoundRobin/Sticky/Cooperative），**任一时刻一个分区只归组内一个成员**：

```
topic: orders (P0 P1 P2 P3 P4 P5)

组 A（2 个成员）——组内"分摊"（队列语义：一条消息只被组内一个成员处理一次）
  consumer-a1: P0 P1 P2
  consumer-a2: P3 P4 P5

组 B、组 C——组间"广播"（每组一份独立读游标，各自拿到全量）
  组 B 从自己的位点读 P0..P5 全量
  组 C 从自己的位点读 P0..P5 全量
```

位点按 (group, partition) 记录，数据本身不按组隔离——组 A 消费到 offset 100 不影响组 B 从 0 读全量。两个边界条件：新组第一次读从哪起步由 `auto.offset.reset` 决定（`earliest` 从头 / `latest` 只读新数据，之后仍全量推进）；"全量"受 `retention.ms` 约束（过期的段任何组都读不到）。对比：`assign()` 手动指定分区则**不走组协调**（不参与分配与再平衡，`group.id` 仅用于提交位点）。

**③ 分区数 = 消费并行度的上限。** 成员数 ≠ 分区数时的取舍：成员多于分区 → 多出的成员**空闲**（100 个线程订阅 6 分区的 topic 是经典浪费）；成员少于分区 → 一个成员扛多个分区（poll 返回的 `ConsumerRecords` 按分区分组，单线程内顺序处理）。想要"一个分区一个线程"，先加分区数。组内独占换来的正是**单分区内有序**（8.9 反模式 7 的根源与正确做法见彼处）。

下面的协议只回答一个问题：**这套分配是怎么达成、怎么在成员变化时安全重排的。**

### 经典协议（classic）：两阶段 + 服务端协调

- 请求入口：`KafkaApis.handleJoinGroupRequest`（`KafkaApis.scala:1390`）/ `handleSyncGroupRequest`（`:1414`）；
- 流程：JoinGroup（全员到齐，选 leader 成员）→ **leader 成员在客户端执行分配策略** → SyncGroup（把分配结果经 coordinator 发回大家）→ 之后靠 Heartbeat 保活；
- 服务端实现：`group-coordinator/.../group/GroupCoordinatorService.java`（classic 分支）+ `classic/ClassicGroup.java`；
- 分配策略在 `group-coordinator/.../assignor/`（RangeAssignor、UniformAssignor=RoundRobin/Sticky、以及 cooperative 变体）；
- 三把超时锁死死约束消费者：`session.timeout.ms`（默认 45s，`ConsumerConfig.java:467`，心跳失联判定）、`heartbeat.interval.ms`（3s，`:472`）、`max.poll.interval.ms`（默认 300s=5min，`:658`，**两次 poll 间隔超时 → 被踢出组**——处理太慢也会触发再平衡）。

### 新协议（KIP-848，`group.protocol=consumer`）：服务端算分配、增量生效

- 请求入口：`GroupCoordinatorService.consumerGroupHeartbeat`（`GroupCoordinatorService.java:515`）——**一个心跳请求包办全部**：成员进组/退组、分配方案下发（服务端 `GroupMetadataManager` + `modern/ConsumerGroup.java` 状态机直接算）、分区增量增减；
- **消除了"全员停止消费"的同步屏障**：rebalance 期间其余分区照常消费，只迁移涉及的分区；
- 客户端由 `AsyncKafkaConsumer` + 后台 `ConsumerGroupHeartbeatRequestManager` 驱动。

### 两种协议共同的不变量

**同一消费组内，一个分区任一时刻只有一个 owner。** 所有"重复消费/消费竞争"问题几乎都源于对这个不变量的破坏（见 8.9 反模式 7）。

## 6.4 位点管理：`__consumer_offsets` 与提交

**白话**：committed offset（已提交位点）决定"下次再平衡/重启后从哪读"。它存在内部 topic `__consumer_offsets`（默认 50 分区、RF=3：`GroupCoordinatorConfig.java:121/130`，`offsets.topic.num.partitions`/`offsets.topic.replication.factor`；`cleanup.policy=compact`）。

【源码证据】服务端：`KafkaApis.handleOffsetCommitRequest`（`KafkaApis.scala:280`）→ 组协调器 `OffsetMetadataManager.commitOffset`（`group-coordinator/.../OffsetMetadataManager.java:620`）→ 写入对应分区的日志并等待 HW（位点提交是**持久化写**，不是内存记号）。客户端经典协议：`ConsumerCoordinator.commitOffsetsSync`（`ConsumerCoordinator.java:1146`）→ `sendOffsetCommitRequest`（`:1276`）；异步自动提交：`maybeAutoCommitOffsetsAsync`（`:1202`，默认每 `auto.commit.interval.ms`=5s 一次，`ConsumerConfig.java:494`）。

**提交位点与消费进度的错位**（这是第八章全部戏法的根基）：

```
fetch position：poll 拿到了哪（内存态，poll 即推进）
committed position："这些我处理完了"（持久态，要靠 commit 声明）
```

- **提交得早**（处理前提交）→ 崩溃后从提交位点继续 → **丢消息**（at-most-once）；
- **提交得晚**（处理后提交）→ 崩溃后从旧位点重读 → **重复处理**（at-least-once）；
- **事务提交**（位点和输出消息同事务）→ 要么都生效要么都不生效（exactly-once，第七章）。

## 6.5 再平衡时位点的交接：ConsumerRebalanceListener

再平衡把分区从你手里拿走的那一刻，你**未提交的处理结果**就悬空了。经典协议下 `ConsumerCoordinator.onJoinComplete`（`ConsumerCoordinator.java:379`）生效新分配，而 `onJoinPrepare`（对应撤销阶段）会调用用户的 `ConsumerRebalanceListener.onPartitionsRevoked`——**在这里同步 commitSync 一次**，是手写消费者"不丢不重"的关键动作（完整讨论与代码在 8.4）。`close()` 也会触发同样的撤销流程。

## 6.6 本章小结

消费端 = **poll 拉取（fetch 即 advance position）+ 位点提交（commit 才是进度真相）+ 再平衡（分区所有权迁移）**三件事的组合；其上的组织规则一句话：**分区在组内分摊（独占保证有序与不重处理），数据在组间广播（独立位点保证各取全量）**（6.3 速览）。KIP-848 让再平衡从"停服式两阶段"变成"服务端调度 + 增量迁移"，但**"position 与 committed 之间的缝隙"永远存在**——它不是 bug，而是 at-least-once 语义的定义本身。怎么按业务需求把这条缝隙缝小或缝没，就是第八章。

---

# 七、事务与精确一次（transaction-coordinator + core + clients）

## 7.1 事务解决什么问题

幂等生产者（3.6）解决的是"单分区、单会话内的重试重复"。还有两类问题它无能为力：

1. **跨分区原子写**：一次业务动作要写 3 个分区，写到第 2 个挂了——重试后第 1 个分区已留下脏数据；
2. **消费-处理-生产循环的原子性**：读一批 → 处理 → 写结果 + **提交输入位点**。结果写成了、位点没提交（或反之），都会破坏语义。

Kafka 事务（0.11，KIP-98）的答案：**给生产者的所有输出 + 消费者的位点提交打成一个原子包**，并且**让下游能选择性地"只读已提交"**。

## 7.2 事务的存储：`__transaction_state` 与协调器

事务状态存在内部 topic `__transaction_state`（`transaction.state.log.num.partitions` 默认 50，RF=3，`transaction.state.log.min.isr` 默认 2——`TransactionLogConfig.java:44-46`），按 `transactional.id` 的 hash 路由到分区，**每个分区的事务协调器就是该分区的"状态机 + 日志回放"**（与组协调器同一套 CoordinatorRuntime 思想）。

【源码证据】主逻辑仍在 core（Scala）：`core/.../coordinator/transaction/TransactionCoordinator.scala`：

| 方法 | 行号 | 职责 |
|---|---|---|
| `handleInitProducerId` | 118 | 分配/续用 PID；**同一 transactional.id 再次 init → 旧 epoch 被 fence**（僵尸防护的 server 侧） |
| `handleAddPartitionsToTransaction` | 417 | 生产者把分区登记进当前事务（客户端 doSend 第 ⑦ 步的对应物） |
| `handleEndTransaction` | 516 | `commitTransaction()/abortTransaction()` 的入口 |
| `endTransaction` | 767 | 两阶段推进：Prepare(PrepareCommit/PrepareAbort) → **WriteTxnMarkers** → Complete |

## 7.3 两阶段提交与控制记录：怎么"原子"起来

`commitTransaction()` 之后发生的事：

1. 协调器把事务状态推到 `PrepareCommit`（写 `__transaction_state` 日志）；
2. 给**该事务涉及的每个分区的 leader** 发 WriteTxnMarkers（`TransactionMarker`），leader 把一条 **COMMIT 控制记录追加进数据日志**（批次头的 control 位，见 2.3）——**提交标记与数据在同一个分区日志里按序共存**，不需要任何外部"提交文件"；
3. 协调器收到全部 marker 确认后推进 `CompleteCommit`。

**LSO（Last Stable Offset）**：分区内"所有未决事务（无论 commit/abort）都已落定"的最大位移。`ReplicaManager` 在 Fetch 时返回它（`ReplicaManager.scala:1831/1855/1969`）。**`read_committed` 消费者只能读到 LSO 之前**——这就是为什么长事务会"堵住"下游 read_committed 消费（8.9 反模式 9）。

## 7.4 消费端配合：isolation.level

`isolation.level`（`ConsumerConfig.java:373`）默认 `read_uncommitted`（`:382`）。设为 `read_committed` 后：

- broker 在 Fetch 响应里给出 LSO，客户端把"事务中/被 abort"的批次截掉；
- 客户端还会缓存各 PID 的"进行中事务"信息，abort 的批次整批丢弃——**aborted 批次不用读也知道要扔**，因为 broker 会把"该 PID 在 [firstSeq, lastSeq] 被 abort"的元数据放进 Fetch 响应。

## 7.5 两阶段提交 API（KIP-939，4.0+）

4.x 的 `KafkaProducer.initTransactions(boolean keepPreparedTxn)`（`KafkaProducer.java:730`）与 `throwIfInPreparedState`（`:1163`）支持"事务**预备**（prepared）后，由外部协调者（如 Kafka Streams 或 TC 恢复流程）决定最终 commit/abort"——这是把 Kafka 事务接入更大 XA 式场景的口子，多数应用用不到，知道存在即可。

## 7.6 本章小结

事务 = **输出消息 + 输入位点提交 + 控制（COMMIT/ABORT）记录，全部写进同一批分区日志**，靠日志的有序性天然原子；协调器只是这个原子包的"记账员"。它的成本是：多一组内部 topic、`transaction.timeout.ms` 的长事务风险、read_committed 的 LSO 等待。**如果业务能在消费端做幂等（8.7），多数场景可以不用事务**——这是工程上最常见也最经济的取舍。

---

# 八、可靠性专题（重点）：Client 如何与 Kafka 机制配合，保证消息不丢、不重复处理

## 8.1 先建立世界观：消息的旅程与"丢失/重复"的全部可能

一条消息的旅程分三段，**每一段都有一个"确认边界"，丢失和重复都发生在边界两侧**：

```
[生产者] ──①发送──► [Broker 集群（leader+ISR 副本）] ──②可见──► [消费者] ──③处理──► [业务副作用+提交位点]
   │                        │                                    │
 丢失窗口A                 丢失窗口B                            丢失窗口C
 客户端没等确认就           leader 挂、ISR 里没人有               "处理了但没提交位点"
 说"成功"/进程崩了         这条消息却当选了                       /提交了但没处理完
```

**丢失的本质**：在错误的边界宣布了成功。
- **窗口 0（更早、易被忽略）**："业务数据落库"与"消息发出"本身不原子——订单提交了消息没发出去，或消息发出去了订单回滚了（8.2.5 的双写问题）；
- 窗口 A：`send()` 返回 Future 就当成功、acks=0/1、重试被关、发送缓冲区未发完进程退出；
- 窗口 B：acks=1 时 leader 挂、`min.insync.replicas` 放水、unclean 选举把落后副本扶正；
- 窗口 C：**先提交位点后处理**（或自动提交跑到处理中间）、再平衡时没交接位点。

**重复的本质**：**"确认丢失"只能靠重试弥补，而重试 = 可能重复**——这是分布式系统的信息论级约束（不确定"收到没收到"，重发是唯一理性选择）。所以工程上从不追求"零重复"，而是**分层消灭重复**：

| 层 | 机制 | 消灭的重复 | 残余 |
|---|---|---|---|
| 生产端 | 幂等生产者（PID+seq） | broker 端的同会话重试重复 | 跨会话/跨分区 |
| 生产端 | 事务 | 跨分区原子写 + 消费-处理-生产循环重复 | — |
| 消费端 | 业务幂等（唯一键/去重表/状态机） | 一切残余（包括上游没防住的） | 幂等键的选取要业务保证 |

下面三节（8.2-8.4）逐一封堵丢失窗口，8.5-8.7 逐一消灭重复。**每一节都给出：机制原理 → 源码证据 → 配置/代码**。

## 8.2 不丢失之一：生产端——"没收到 ack 就不算发送过"

### 8.2.1 机制：acks 与确认的语义

acks 的取值（`ProducerConfig.java:132`，可选 `all`/`-1`/`0`/`1`，**4.x 默认 `all`**，`ProducerConfig.java:430-435`）：

- `acks=0`：不等待任何确认（broker 侧甚至不回响应，`KafkaApis.scala:512-526`）。进程一崩，缓冲区里的消息全部蒸发——**除非业务能容忍丢数据（如埋点采样），禁用**。
- `acks=1`：leader 写入本地（页缓存）即确认。leader 确认后、follower 拉走前 leader 挂了 → 该分区新 leader（原 follower）没有这条消息 → **丢**。
- `acks=all`：等待**当前 ISR 全部**副本确认（配合 min.insync.replicas，见 8.3）。从机制上等价于"这条消息已复制到足够多的副本，任何 ISR 成员当选 leader 它都在"。

> **"默认配置不可信"要分版本和分端说**：生产端自 **3.0（KIP-679）** 起默认已是 `acks=all` + 幂等开启 + 无限重试——"生产端默认会丢"是 2.x 时代的印象；但**消费端 `enable.auto.commit` 默认仍是 `true`**（`ConsumerConfig.java:491`），**broker 端 `default.replication.factor` 默认仍是 1**、`min.insync.replicas` 默认 1——"用默认配置直接上线"的风险在**服务端与消费端**依然成立（见 8.3、8.4.1）。

### 8.2.2 机制：重试与超时的组合拳

确认可能"没发生"（网络抖动、leader 换人、请求超时）。生产端的补救是**自动重试**，四个参数共同决定"重试多久、重试到什么程度"：

| 参数 | 默认值（4.x） | 作用 |
|---|---|---|
| `retries` | `Integer.MAX_VALUE`（`ProducerConfig.java:429`） | 重试次数上限（幂等开启时默认已是无限） |
| `delivery.timeout.ms` | 120000（`:446`） | **总预算**：从 send() 到最终成功/失败的整个生命周期，含重试。超时即失败回调 |
| `request.timeout.ms` | 30000（`:488`） | 单次请求超时 |
| `retry.backoff.ms` | 100 | 重试间隔（配合避免抖动放大） |

【源码证据】重试回路在 Sender：`completeBatch` 收到可重试错误 → `reenqueueBatch`（`Sender.java:671-746`，批次原样回队重发）；批次从创建起超过 `delivery.timeout.ms` → `failExpiredBatches`（`Sender.java:362`）按超时失败并回调用户。**这意味着：只要还在预算内，客户端会一直替你重试；用户唯一要做的是在回调里正确处理最终的失败。**

### 8.2.3 机制：幂等必须开着（它同时保护"顺序"）

`enable.idempotence=true`（**默认已开**，`ProducerConfig.java:574-578`）不只是防重复（8.5），**它还把"重试导致的乱序"修好了**：重试批次回队首、后续批次按序列号重新排队（3.4 第 4 点），broker 端 `checkSequence` 兜底拒绝乱序（8.5.1）——**"单分区内有序"在重试下依然成立**，这也是幂等开启时 `max.in.flight.requests.per.connection` 允许到 5（上限值 `ProducerConfig.java:296`）的前提。

### 8.2.4 客户端代码：把"确认"真的接进业务

```java
Properties props = new Properties();
props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "b1:9092,b2:9092,b3:9092");
props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
// —— 以下三项 4.x 默认已是安全值，显式写出以自文档化（3.0/KIP-679 之前需要显式设置）——
props.put(ProducerConfig.ACKS_CONFIG, "all");                     // 等 ISR 全员确认
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");      // PID+seq，防重防乱序
props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "120000");   // 总预算兜底

try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
    for (String line : lines) {
        // ★ 要点一：send() 返回就当成功 = 窗口 A 全开。必须用回调或 get() 接住最终结果
        producer.send(new ProducerRecord<>("orders", orderId, line), (metadata, exception) -> {
            if (exception != null) {
                // 重试耗尽/超预算/不可恢复错误才会走到这里
                // → 落库标记失败、告警、或转本地补偿队列；绝不能吞掉
                failedLog.write(line, exception);
            }
        });
    }
    // ★ 要点二：close() 会把缓冲区里未发的批次发完再退（生产环境要么 close，要么保证生命周期覆盖）
    producer.close();
}
```

**要点三（易漏）**：`send()` 本身**可能阻塞**——等元数据（首次发往新 topic，最多 `max.block.ms` 默认 60s）或等缓冲池内存（`buffer.memory` 32MB 打满时）。把 KafkaProducer 塞进"处理线程池"时要想清楚：**缓冲区满 = 生产速度 > broker 确认速度**，此时阻塞是背压，吞异常继续发才是灾难。

**要点四（事务使用者）**：`send()` 之后、`commitTransaction()` 之前进程崩溃，事务会被协调器超时回滚（`transaction.timeout.ms` 默认 60s，`ProducerConfig.java:579-582`）——**输出与位点一起消失，这正是"不丢"语义的一部分**（宁可重来，不可半途）。

### 8.2.5 更早的一层："业务数据落库"与"消息发出"的原子性（Outbox/本地消息表）

**本节前四小节解决的是"消息从进程到 broker"的传输可靠性，但还有半个层次更早的问题**：业务要求"订单写进数据库"和"订单事件发到 Kafka"**要么都发生、要么都不发生**。这两步横跨 DB 事务与网络调用，**无法用任何一个技术做成原子**（先发后提交：发送成功、DB 回滚 → 多发了"不存在"的订单；先提交后发送：提交成功、发送失败/进程崩 → 少发）。这不是配置能解决的，是**双写（dual-write）问题的结构性缺陷**，必须靠模式：

| 方案 | 机制 | 适用 |
|---|---|---|
| **事务消息（RocketMQ 独有）** | 半消息（对消费者不可见）→ 执行本地事务 → commit/rollback 半消息；broker 对"未知状态"的半消息**定时回查**生产者 | RocketMQ 原生支持；**Kafka 没有这个特性** |
| **Outbox / 本地消息表（Kafka 的标准答案）** | 业务记录与 outbox 消息记录在**同一个 DB 事务**里落库 → 异步中继（轮询或 CDC，如 Debezium）把 outbox 投递到 Kafka → 投递成功后标记/清理 | 任何 MQ；代价是"最终一致 + 投递至少一次"（下游仍需幂等，8.7） |
| Kafka 事务（KIP-98） | 只原子绑定 **Kafka 输出 + Kafka 位点**，**绑不了外部 DB 事务**——不要指望 `beginTransaction()` 解决双写 | 消费-处理-生产循环（8.6） |

【Outbox 最小实现】：

```sql
-- 与业务表同一个本地事务
BEGIN;
  INSERT INTO orders(id, amount, ...) VALUES (...);                  -- 业务数据
  INSERT INTO outbox(id, aggregate_id, topic, payload, created_at)   -- 待发消息，同一事务
       VALUES (uuid(), 'order-123', 'orders', '{...}', now());
COMMIT;
```

```java
// 独立中继任务：轮询 outbox → 发 Kafka → 标记已发（崩溃重发由下游幂等兜底）
List<Outbox> batch = outboxRepo.lockAndFetchPending(100);   // FOR UPDATE SKIP LOCKED
for (Outbox o : batch) {
    producer.send(new ProducerRecord<>(o.topic, o.aggregateId, o.payload),
                  (m, e) -> { if (e == null) outboxRepo.markSent(o.id()); });  // ★ 失败留在表里，下轮再投
}
```

三个工程要点：①**payload 里带业务唯一键**，下游按它幂等（outbox 必然 at-least-once）；②中继要**有序**（按 aggregate 分组串行，或按 key 投递保证分区内序）；③中继的两种实现——轮询与 CDC——见下。

【中继的两种实现：轮询 vs CDC（Change Data Capture，变更数据捕获）】

上面的伪代码是**轮询式**：定时任务主动去问数据库"有没有没发的行"。**CDC 是另一条路**：数据库本来就会把每个已提交的行变更顺序写进自己的事务日志（MySQL binlog / PostgreSQL WAL），CDC **伪装成从库去订阅这份日志**，把表上的每条 INSERT/UPDATE/DELETE 变成事件流。Debezium（事实标准，跑在 Kafka Connect 里）读 binlog，经"Outbox Event Router"把 `outbox` 表的 INSERT 事件组装成 Kafka 消息（列 → key/value/目标 topic）发出：

```
业务事务（同库同事务写两笔）
  INSERT orders ... ──┐
  INSERT outbox ... ──┴─► binlog（顺序日志，本来就有）
                             │ 伪装成从库订阅
                             ▼
                       Debezium (Kafka Connect)
                             │ 过滤 outbox 表 INSERT → 组装消息
                             ▼
                        Kafka topic
```

| | 轮询（自写中继任务） | CDC（Debezium 读 binlog） |
|---|---|---|
| 延迟 | 一个轮询周期（秒级），可调 | 毫秒级（提交即捕获） |
| 对业务库的压力 | 周期性查询 + `FOR UPDATE SKIP LOCKED`，与业务查询抢资源 | 仅一条 binlog 复制流，几乎无感 |
| 开发/运维成本 | 几十行代码即可用，无新组件 | 引入 Kafka Connect + Debezium；DB 开 ROW 格式 binlog、复制权限账号；**连接器宕机期间 binlog 不能被清理**（`expire_logs_days` 留足余量） |

这就是"免轮询、对业务库侵入最小"的含义——省掉查询负载、延迟降到毫秒级；但有一个特有代价：**CDC 是纯读日志、不回写数据库**，outbox 行发出去了也不会被标记，表默认只增不删、无限膨胀，所以必须配独立清理任务（按时间删 N 天前的行，或按时间分区 drop partition），把"outbox 别撑爆业务库"变成显式运维职责。Debezium 的惯例做法是事件发出后**补发一条 DELETE 事件（tombstone）**，配合下游 topic 的 log compaction 按键去重——那是另一种"清理"，清的是 topic 而不是表。

一句话收拢：**轮询是"你主动去问数据库"，CDC 是"数据库的提交日志推给你"；CDC 省查询、快延迟，换来一套连接器的运维和一张需要定期清的表。** 小规模先轮询，规模上来或延迟敏感再上 CDC。

## 8.3 不丢失之二：Broker 端——"副本是唯一的持久性来源"

### 8.3.1 为什么 Kafka 不靠 fsync

设计哲学 2（1.2）：Kafka 把 `log.flush.interval.messages/bytes`（`LogSegment.flush()`，`LogSegment.java:628`）留着但不推荐使用——**fsync 每条消息的代价是吞吐塌方，而它只解决"单机持久"**：机器整个挂掉时页缓存里的数据仍会丢。Kafka 的答案是**把"丢数据"转化为"副本同步问题"**：只要确认时消息已在 ≥2 台机器的日志里，任何单机故障都不丢。**副本才是 Kafka 的 fsync。**

### 8.3.2 三层配置：副本数、ISR 底线、选举纪律

| 配置 | 建议值 | 机制依据 |
|---|---|---|
| `replication.factor`（topic 级） | **≥ 3** | 允许"1 台在维护 + 1 台挂了"仍有两个 ISR 成员可仲裁。acks=all 的 "all" 指当前 ISR——**副本多才有仲裁余地** |
| `min.insync.replicas` | **≥ 2**（且 < replication.factor） | `Partition.appendRecordsToLeader` 的闸门一（`Partition.scala:1236-1240`）：ISR 少于该值 → `NotEnoughReplicasException` 拒写。**设为 1 等于给 acks=all 开后门**（只剩 leader 也能写，leader 挂即丢） |
| `unclean.leader.election.enable` | **false**（默认即 false，`LogConfig.java:138`） | 禁止"落后的副本（不在 ISR 里）当选 leader"。开了它 = 接受"为了可用性主动丢数据"；leader 也可能在已提交水位之前 |

这三个配置**联动成一个闭环**：`acks=all` 保证"确认过 = ISR 全员都有"；`min.insync.replicas≥2` 保证"确认时至少两台有"；`unclean=false` 保证"新 leader 一定从确认过的那批里选"。**任何一环松动，另两环全部白搭**——这就是为什么丢了消息的公司复盘时总能找到"有人把 min.insync.replicas 改成了 1"。

**一个常被忽视的漏洞：自动建 topic**。客户端 `allow.auto.create.topics=true`（消费端默认 true，`ConsumerConfig.java:389`）+ broker `auto.create.topics.enable=true` 时，**拼错的 topic 名也会被"顺手"创建出来**，且副本数走 broker 端 `default.replication.factor`（默认 **1**）——业务 topic 以 RF=1 上线，8.3.2 的闭环整体失效。生产环境要么关闭 broker 的自动建 topic，要么确保 topic 由运维/AdminClient 预建（显式 RF≥3、min.isr=2）。

### 8.3.3 内部 topic 的可靠性即业务的可靠性

- `__consumer_offsets`：`offsets.topic.replication.factor` **默认 3**（`GroupCoordinatorConfig.java:130`）。它丢了 = 全部消费组位点丢失 = 全线重复消费，务必让它的 RF 跟业务 topic 对齐；
- `__transaction_state`：`transaction.state.log.replication.factor` 默认 3、`transaction.state.log.min.isr` 默认 2（`TransactionLogConfig.java:44-46`）——事务型应用的命脉同理。

### 8.3.4 集群级兜底

- 挂掉的 broker 会把分区 leadership 移交（ISR 协议自动处理，4.6），**但目录盘坏了**（`log.dirs` 所在磁盘故障）需要 `LogDirFailureChannel` 隔离 + 副本重建——多块盘（`log.dirs` 多目录）+ 每盘独立监控是标配；
- 大规模集群考虑**机架感知**（`broker.rack`）让副本跨机架/可用区分布，否则一个交换机故障可以同时带走 leader 和所有 follower。

## 8.4 不丢失之三：消费端——"先处理完，再提交位点"

### 8.4.1 机制：自动提交的"位置陷阱"

`enable.auto.commit=true`（**默认 true**，`ConsumerConfig.java:489-492`）+ `auto.commit.interval.ms=5000`（`:494`）。自动提交在 **poll 循环里**顺带进行（经典协议 `ConsumerCoordinator.maybeAutoCommitOffsetsAsync`，`ConsumerCoordinator.java:1202`），提交的是**上次 poll 返回的那批的末尾 + 1**——即"**你已经拿到但可能还没处理完**"的进度。

于是产生窗口 C 的标准事故：

```
poll 拿到 [100..200) → 自动提交线程在 t=5s 提交了 200 → 处理到 150 时进程崩
→ 重启后从 200 开始 → [100..150) 永远丢了
```

反过来（先提交后处理）则重复——**自动提交的默认位置偏向前者（丢），手写代码常见的错误偏向后者（重）**。正确姿势只有一个：**关闭自动提交，处理完一批再显式提交**。

### 8.4.2 代码：at-least-once 的标准骨架（不丢的代价是可能重复，8.7 消灭）

```java
Properties props = new Properties();
props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "b1:9092,b2:9092,b3:9092");
props.put(ConsumerConfig.GROUP_ID_CONFIG, "order-processor");
props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");     // ★ 关自动提交：丢失窗口 C 的关闭开关
props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, "300000");  // 处理一批的最大时间，超时被踢出组
props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "500");         // 单次 poll 的量 = "处理+提交"的事务粒度

Map<TopicPartition, OffsetAndMetadata> currentOffsets = new HashMap<>();

KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
ConsumerRebalanceListener listener = new ConsumerRebalanceListener() {
    @Override public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
        // ★ 再平衡要拿走分区：先把已处理的进度提交掉（同步、阻塞直到成功）
        consumer.commitSync(currentOffsets);
        currentOffsets.clear();
    }
    @Override public void onPartitionsAssigned(Collection<TopicPartition> partitions) { }
};

try {
    consumer.subscribe(List.of("orders"), listener);
    while (running) {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
        for (ConsumerRecord<String, String> r : records) {
            process(r);                          // ★ 先做业务副作用（落库/调下游）
            currentOffsets.put(new TopicPartition(r.topic(), r.partition()),
                    new OffsetAndMetadata(r.offset() + 1));   // 注意 +1：提交的是"下一条要从哪读"
        }
        if (!currentOffsets.isEmpty()) {
            consumer.commitSync(currentOffsets); // ★ 后提交：成功 = 整批"安全落地"
        }
    }
} finally {
    consumer.close();                            // close 也会触发 onPartitionsRevoked 交接
}
```

四个细节决定成败：

1. **`offset()+1`**：提交语义是"下一条待读位点"（`__consumer_offsets` 里存的就是 next-position）；
2. **`commitSync` vs `commitAsync`**：`commitSync`（`KafkaConsumer.java:1034`）阻塞直到成功或异常（`CommitFailedException` = 组已变/分区易主，需要处理）；`commitAsync`（`:1206`）不阻塞但可能失败。**批量循环的终点用 commitSync 保进度；循环中想提速可以 commitAsync 打点，但"批末必 sync"**；
3. **`max.poll.interval.ms`（默认 5 分钟）**：两次 poll 之间处理太久 → 被协调器踢出组 → 提交位点报 `CommitFailedException`。**处理慢的正确解法是减小 `max.poll.records` 或下游并行化，而不是调大这个值装作没事**（调大会让真死的消费者占用分区更久）；
4. **再平衡交接**：`onPartitionsRevoked` 里的 commitSync（如上）+ 处理流程对"处理到一半被 revoke"的容忍（要么 revoke 前处理完，要么把"未处理完"的记录重新入队/回退事务）。

### 8.4.3 "先处理还是先提交"的语义对照表

| 提交时机 | 崩溃后果 | 语义 | 适用 |
|---|---|---|---|
| 提交在前（先 commit 后处理） | 从提交位点继续，未处理完的**丢** | at-most-once | 丢得起（监控告警、日志流采样） |
| **处理在前（先处理后 commit）** | 从旧位点继续，已处理的**重** | **at-least-once（本文推荐）** | 绝大多数业务 |
| 位点与输出同事务提交 | 都生效或都不生效 | exactly-once（7 章） | 流式 join/聚合（Streams）、金融台账 |

## 8.5 不重复之一：生产端幂等——broker 帮你挡住"重试重复"

### 8.5.1 机制回顾（串联 3.6 与 4.2）

发送侧：每批盖 `baseSequence`（`TransactionManager` 按分区单调递增，`TransactionManager.java:721`）。
接收侧：broker 的 `ProducerStateManager` 记住每个 PID/分区最近的批次序列号窗口；新批次到达时 `checkSequence`（`storage/.../log/ProducerAppendInfo.java:162`）：

```java
// ProducerAppendInfo.java:162-208
private void checkSequence(short producerEpoch, int appendFirstSeq, long offset) {
    ...
    if (!(currentEntry.producerEpoch() == RecordBatch.NO_PRODUCER_EPOCH || inSequence(currentLastSeq, appendFirstSeq))) {
        throw new OutOfOrderSequenceException(...);   // 跳号/回退 → 拒绝
    }
}
private boolean inSequence(int lastSeq, int nextSeq) {
    return nextSeq == lastSeq + 1L || (nextSeq == 0 && lastSeq == Integer.MAX_VALUE);
}
```

已在窗口内的重复批次 → 返回 `DUPLICATE_SEQUENCE_NUMBER`，客户端 `completeBatch` 把它**当成功**处理（`Sender.java:692-699`，注释明说"batch 已经在日志里"）。

### 8.5.2 它防不住什么（决定下一层是否存在）

| 场景 | 幂等是否有效 | 原因 |
|---|---|---|
| 同进程重试（网络超时重发） | ✅ | 同 PID 同序列号 |
| 同进程跨分区原子性 | ❌ | 序列号按分区独立 |
| **应用重启后重发**（消费端重放、上游重推） | ❌ | 新实例新 PID，broker 看到的是"新生产者的新消息" |
| 同一逻辑消息发多次（业务层重复调用） | ❌ | 序列号不同，broker 无从去重 |

**结论：幂等是"免费"的（默认开），但它只解决传输层重复。业务语义的重复必须靠事务（下一节）或消费端幂等（8.7）。**

## 8.6 不重复之二：事务——"消费-处理-生产"循环的原子化

### 8.6.1 机制：把位点提交和输出写进同一个原子包

at-least-once 消费端（8.4）的残余问题：**处理成功但位点提交失败**（崩溃/再平衡）→ 重放 → **输出被写两遍**。事务的解法（7.1-7.3）：

```
consumer.poll()
producer.beginTransaction()
  → producer.send(输出消息...)            // 输出暂不可见（未提交事务）
  → producer.sendOffsetsToTransaction(offsets, consumer.groupMetadata())  // ★ 位点入伙
producer.commitTransaction()               // 两阶段：EndTxn → WriteTxnMarkers → 数据日志里的 COMMIT 记录
```

`sendOffsetsToTransaction`（`KafkaProducer.java:818`）是**唯一能让"提交位点"具有原子性**的动作：位点作为 `TxnOffsetCommit` 写进 `__consumer_offsets`（`KafkaApis.handleTxnOffsetCommitRequest`，`KafkaApis.scala:2062`），标记为"挂在该事务下"——事务 abort 则位点回滚。**崩溃重启后，输出和位点要么都在（COMMIT 记录之后），要么都不在**。

### 8.6.2 下游配合：`isolation.level=read_committed`

链式场景（A 消费 topic1 写 topic2，B 消费 topic2）中，B 必须 `read_committed`（`ConsumerConfig.java:373-382`），否则 A 未提交/已回滚的输出会被 B 读走——**事务只对愿意"只读已提交"的读者有效**。

### 8.6.3 transactional.id 的选取：僵尸防护的钥匙

`initTransactions`（`TransactionManager.java:321` → 协调器 `handleInitProducerId`，`TransactionCoordinator.scala:118`）用 **transactional.id 做互斥**：同 id 的旧生产者实例被新 epoch fence——老实例后续一切写入被 broker 拒绝（epoch 校验）。因此：

- **同一逻辑流（同一"计算任务"）必须用固定的 transactional.id**（如 `"order-etl-shard-3"`），实例重启后才能顶掉僵尸；
- 不同任务绝不能共用 id（会互相 fence，表现为随机 `ProducerFencedException`）。

### 8.6.4 事务的标准模板与成本

```java
props.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, "order-etl-shard-3");  // 逻辑流唯一且稳定
try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
    producer.initTransactions();                          // fence 僵尸 + 拿 PID（每次启动必调）
    while (running) {
        ConsumerRecords<String, String> in = consumer.poll(Duration.ofMillis(500));
        if (in.isEmpty()) continue;
        producer.beginTransaction();
        try {
            for (ConsumerRecord<String, String> r : in) {
                producer.send(transform(r));              // 输出
            }
            producer.sendOffsetsToTransaction(offsets(in), consumer.groupMetadata());
            producer.commitTransaction();                 // 原子生效
        } catch (ProducerFencedException | OutOfOrderSequenceException | AuthorizationException e) {
            producer.close();  // 致命：实例已被顶替/无权，直接退出
        } catch (KafkaException e) {
            producer.abortTransaction();                  // 可恢复：回滚本批，poll 重来
        }
    }
}
```

成本清单：内部 topic 一组（`__transaction_state`，默认 50 分区 RF3）；`transaction.timeout.ms`（默认 60s）内必须 commit，否则整个事务被 abort；read_committed 下游要等 LSO（长事务堵下游，8.9 反模式 9）；吞吐比幂等略低（多两阶段 + marker）。

**工程判断**：事务适用于"**输出本身也写回 Kafka**"的流式链路（Streams 内置 EOS）。若输出是外部系统（DB/ES），Kafka 事务帮不上忙——用 8.7 的业务幂等，或 8.2.5 的 Outbox/本地消息表模式（把外部输出与位点提交放进同一个 DB 事务）。

## 8.7 不重复之三：消费端业务幂等——最后兜底，也是最通用的一层

### 8.7.1 为什么它永远需要

at-least-once（8.4）允许重复；幂等生产者防不了跨会话（8.5）；事务防不了输出到外部系统（8.6）。**只要链路里有一环不做幂等，端到端就不是 exactly-once。** 所以把幂等下沉到"业务副作用"发生的那一点，是覆盖面最大的做法：

**核心思想：给每条消息一个业务幂等键，副作用操作写成"幂等操作"。**

### 8.7.2 四种工程模式

1. **唯一约束 + upsert（DB 落地）**：消息带唯一业务键（订单号/事件 id），写 DB 用 `INSERT ... ON CONFLICT DO NOTHING/UPDATE` 或先 `INSERT` 捕获唯一冲突。重复消息第二次插入被数据库挡住——**天然幂等，推荐默认选型**。
2. **去重表（幂等台账）**：副作用与"已处理记录"放**同一个本地事务**里：
   ```sql
   BEGIN;
     INSERT INTO processed_messages(msg_key) VALUES (?);   -- 唯一键冲突 = 已处理过，直接 COMMIT 返回
     UPDATE accounts SET balance = balance - ? WHERE id = ?;
   COMMIT;
   ```
   重复消息在第一步就短路。**这是"消费 Kafka + 写 DB"场景的标准答案**，因为位点提交与 DB 副作用无法原子，就让"副作用本身"具备幂等。
3. **状态机/条件更新**：副作用有天然的状态单调性时，用条件写防重复：`UPDATE orders SET status='PAID' WHERE id=? AND status='CREATED'`——重复消息第二次影响行数为 0，业务无感知。退款、发货、审批流都用这个。
4. **缓存去重（Redis SETNX / 布隆过滤器）**：高吞吐无 DB 场景用 `SET key value NX EX` 做近线去重。**注意**：缓存不是可靠存储（过期/淘汰），只能作为"降低重复概率"的第一道网，不能作为唯一防线。

### 8.7.3 幂等键怎么选

- 首选**业务自然键**（订单号、支付流水号）——重复的判定天然正确；
- 无自然键时用 **producer 端生成的 UUID** 写进消息头（注意：UUID 要在"生成消息意图"时确定，不能在"发送重试"时重新生成）；
- **不要**用 Kafka 的 offset 做 idempotency key 来防上游重放——offset 会随"从哪开始消费"变化，不同消费组/重置位点后同一逻辑消息 offset 不同。

### 8.7.4 消费端代码形态

```java
while (running) {
    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
    for (ConsumerRecord<String, String> r : records) {
        String msgKey = r.key();                       // 业务幂等键
        try {
            bizDao.applyIdempotent(msgKey, () -> businessLogic(r));  // 幂等包装：先插去重记录再执行
        } catch (DuplicateMessageException ignore) {
            // 重复消息：正常跳过（记个 DEBUG 日志即可——重放是 Kafka 语义的一部分，不是错误）
        }
        pendingOffsets.put(tp(r), new OffsetAndMetadata(r.offset() + 1));
    }
    consumer.commitSync(pendingOffsets);               // 照常手动提交
}
```

**与 8.4 的关系**：8.4 保证"不丢"（先处理后提交），本节保证"重了也没事"。两者叠加 = **effectively-once（端到端效果上的精确一次）**——这是绝大多数生产系统达到的语义等级，也是比"开事务"更便宜、更鲁棒的道路。

## 8.8 三种语义的实现配方（速查）

| 目标语义 | 生产端 | Broker/Topic | 消费端 | 残余风险 |
|---|---|---|---|---|
| **不丢（at-least-once）** | `acks=all` + 幂等开启 + `delivery.timeout.ms` 合理 + **回调必处理失败**；涉及 DB 双写再加 Outbox（8.2.5） | RF≥3，`min.insync.replicas≥2`，`unclean.leader.election.enable=false` | `enable.auto.commit=false`，**先处理后 commitSync**，rebalance listener 交接 | 会重复（见下） |
| **不重（生产侧）** | 在上者基础上 `enable.idempotence=true`（默认） | — | — | 跨会话/跨分区仍可能重 |
| **不重不丢（effectively-once）** | 跨分区/链式流：`transactional.id` + `initTransactions` + `sendOffsetsToTransaction` + `commitTransaction`；输出到外部系统则用 Outbox | `__transaction_state` RF3 min.isr2；下游 `isolation.level=read_committed` | **业务幂等**（去重表/唯一约束/状态机） | 幂等键选取错误 |

## 8.9 反模式清单（每一条都对应一次真实事故）

1. **`enable.auto.commit=true` + 异步线程池处理**：poll 循环把记录丢进线程池就继续 poll/自动提交——任务还在队列里，位点已提交。崩溃 = 队列里的全丢。✔ 改法：提交与"任务全部完成"绑定（批末 commitSync）。
2. **`send()` 后不处理回调**：Future/Callback 的失败被吞，业务以为发成功了。✔ 8.2.4 要点一。
3. **`acks=1` + 分区副本重分布期间写入**：leader 迁移窗口里的确认丢失。✔ 8.2.1。
4. **`min.insync.replicas=1`（或未设置，默认 1）**：acks=all 的名存实亡。✔ 8.3.2。
5. **`unclean.leader.election.enable=true`**"为了可用性"：恢复服务后历史数据静默丢失，下游比对时才发现。✔ 8.3.2。
6. **消费失败立即 commit**（跳过毒丸消息时把**整批**位点提交）：同一批后面的消息被连带跳过。✔ 用"逐条提交/按成功进度提交"或死信队列（DLQ）承载毒丸。
7. **消费线程里再起 N 个线程各自 poll/各自提交**：分区所有权与提交者错位，`CommitFailedException` 或重复。✔ 分区固定交付给固定线程（按 partition 分组），提交由单点统一做。
8. **`max.poll.interval.ms` 调大当 "慢处理" 的解药**：真卡死的消费者占住分区更久。✔ 缩批（`max.poll.records`）+ 下游并行。
9. **长事务 + 下游 `read_committed`**：LSO 停在事务开始处，下游空转。✔ 控制事务粒度（一批一事务），监控 `transaction.timeout.ms`。
10. **transactional.id 每次重启随机生成**：僵尸防护失效（老实例永远 fence 不掉）或互踩。✔ 8.6.3。
11. **把"幂等生产者"当成端到端 exactly-once**：它只是传输层去重（8.5.2）。
12. **重置位点/换消费组 group.id 后期望业务无感**：历史重放是合法操作，业务侧幂等（8.7）才是重放的缓冲垫。
13. **框架自动提交/自动应答下"吞异常"（隐性丢失）**：spring-kafka `AckMode.RECORD`/`BATCH` 下容器在 **listener 正常返回后**提交位点——listener 内 `try-catch` 把异常吞掉并正常返回，容器就当"处理成功"提交了；JMS `AUTO_ACKNOWLEDGE` 同理。表现为"没有报错，消息也没了"，比显式丢消息更难发现。✔ 异常要么抛给容器（走错误处理器/DLT），要么换 `MANUAL` 手动应答并保证"处理成功才 ack"。
14. **业务 topic 被自动创建成 RF=1**：拼错 topic 名 + `auto.create.topics.enable=true` → 以 broker 默认副本数（1）上线，可靠性配置全部落空（见 8.3.2 末尾）。✔ 关闭自动建 topic 或预建。

## 8.10 Spring 生态视角（spring-kafka）：同一套机制的封装

上文所有机制在 `spring-kafka`（本文对照源码：`D:\code\3rd\spring-kafka`，4.2.0-SNAPSHOT）中的落点：

- 生产端：`DefaultKafkaProducerFactory`（开启 `setTransactionIdPrefix` 即事务模板 `KafkaTemplate.executeInTransaction`）；配置项与原生一一对应（acks/idempotence 直接透传）；
- 消费端：`@KafkaListener` 容器的 `ContainerProperties.AckMode`（`spring-kafka/.../listener/ContainerProperties.java:67`）：
  - `RECORD` / `BATCH`：容器在每条/每批**处理完**后提交（对应 8.4 的"先处理后提交"骨架）；
  - `MANUAL` / `MANUAL_IMMEDIATE`：注入 `Acknowledgment`，业务代码自己 `acknowledge()`（对应手动 commitSync/Async）；
  - `enable.auto.commit=false` 同样是前提；
- 业务幂等：通常仍自建去重表（Spring 侧没有替代品——8.7 的结论在 Spring 里同样成立）；
- 事务消费：`KafkaAwareTransactionManager` 把 Kafka 事务挂进 Spring 事务管理器，`@Transactional` 内"消费位点 + Kafka 输出"原子提交。

**判断标准不变**：无论裸客户端还是 spring-kafka，问三个问题——ack 等了吗（丢 A）？ISR 底线守住了吗（丢 B）？先处理后提交了吗 + 副作用幂等了吗（丢 C / 重）？

## 8.11 本章小结

**不丢失** = 三个窗口各关一道门：生产端"等 ISR 全员确认 + 重试到预算耗尽 + 失败必回调"（外加"业务落库与发送"用 Outbox 原子化，8.2.5）；broker"RF≥3 + min.isr≥2 + 禁 unclean 选举"；消费端"关自动提交、先处理后 commitSync、rebalance 时交接位点"。
**不重复** = 分层设防：幂等生产者（传输层，默认开）→ 事务（跨分区与消费-处理-生产循环，链式流必备）→ 业务幂等（唯一约束/去重表/状态机，永远的最后防线）。
三者都指向同一个源码事实：**Kafka 的确认、重试、去重、原子性全部锚定在"日志 + 位点"这一对原语上**（acks=ISR 复制进度、幂等=序列号比对、事务=日志里的控制记录、消费进度=位点日志）。理解了这一点，配置就不再是咒语，而是每个机制开关的显式声明。

---

# 九、贯通视图：三条时间线看懂 Kafka 全貌

## 9.1 时间线一：一条消息的一生（生产 → 可消费）

```
用户线程                    Sender 线程                      Leader Broker                 Follower Broker
  │ send(record)                                                                  │
  ├─ waitOnMetadata ────────────(首次拉元数据)                                     │
  ├─ 序列化 + 选分区（粘性）                                                       │
  ├─ accumulator.append：进批次 ─► 队列尾  ✔ return Future                        │
  │                          │ ready(during linger.ms/batch.size)                 │
  │                          │ drain → ProduceRequest + InFlightBatches           │
  │                          │ ───────────────────► │ Partition.appendRecordsToLeader
  │                          │                      │  ├─ min.isr 闸门（Partition.scala:1237）
  │                          │                      │  ├─ UnifiedLog.append → LogSegment（顺序写+稀疏索引）
  │                          │                      │  └─ acks=-1 → DelayedProduce 挂起           │
  │                          │                      │ ◄──── Fetch(我的 LEO) ──────────────────────┤
  │                          │                      │ updateFollowerFetchState → maybeIncrementLeaderHW
  │                          │ ◄── ProduceResponse ─┤（HW 推过本批次 = ISR 都有了）                │
  │                          │ completeBatch → batch.done → Future 完成 → 用户 Callback
  │                          │                      │ HW 之前的消息才对消费者可见                    │
  │                                            （消费者 Fetch，带 committed position 判定重读边界）│
```

关键对应：**第①段确认 = acks；第②段可见 = HW/LSO；第③段进度 = committed offset**。可靠性三窗口（8.1）正好卡在这三个原语上。

## 9.2 时间线二：一次再平衡的一生

- **经典协议**：成员加入/离开（JoinGroup）→ coordinator 攒齐全员 → 分配方案由 leader 成员计算 → SyncGroup 下发 → 各成员 `onPartitionsRevoked`（先提交位点！）→ `onPartitionsAssigned`。**期间全组停摆**。
- **新协议（KIP-848）**：成员心跳（ConsumerGroupHeartbeat）里直接带/收增量分配 → 服务端 `ConsumerGroup` 状态机推进目标分配 → 各成员增量撤销/接收分区，**其他分区全程照常消费**。
- 两者的共同不变量与常见破坏方式：见 6.3 与 8.9 反模式 7。

## 9.3 时间线三：一次事务的一生

```
initTransactions ──► InitProducerId ──► 协调器分配 PID/fence 僵尸（TransactionCoordinator.scala:118）
beginTransaction ──► （纯客户端状态）
send(...) × N      ──► 批次照常走 9.1 流程，但分区先 AddPartitionsToTxn 登记进事务（KafkaProducer.java:1240，doSend 第⑦步）
sendOffsetsToTransaction ──► AddOffsetsToTxn + TxnOffsetCommit（位点挂到事务下）
commitTransaction  ──► EndTxn ──► 协调器 PrepareCommit → WriteTxnMarkers 到各分区 leader
                        → 数据日志里出现 COMMIT 控制记录 → LSO 推进 → read_committed 下游"看见"这批输出
```

失败分支：`abortTransaction` 走 ABORT marker；什么都不做则 `transaction.timeout.ms` 后协调器代为 abort——**不会悬置**。

## 9.4 从源码中提炼的四个设计模式视角

1. **生产者-消费者 + 内存队列**：`RecordAccumulator` 的 per-partition Deque 就是"用户线程生产、I/O 线程消费"的教科书实现，Future/Callback 负责回传；
2. **协调器 = 状态机 + 日志**：组协调器、事务协调器、KRaft 控制器共享同一架构（`coordinator-common` 的 CoordinatorRuntime）：内存状态机处理请求，变更写日志，重启回放重建（1.2 哲学 3）；
3. **两阶段提交**：事务（Prepare→Marker→Complete）、Raft 提交（append→quorum 确认→apply）本质同构；
4. **读修复/反熵**：follower 的 Fetch、ISR 的进出、log compaction，都是"用重读与重写收敛副本与状态差异"的思路。

---

# 十、附录

## 10.1 关键配置速查（可靠性视角）

**Producer**（默认值为 4.x 实测，`ProducerConfig.java`）：

| 配置 | 默认 | 说明 |
|---|---|---|
| `acks` | `all`（:432） | 0/1/all，8.2.1 |
| `enable.idempotence` | `true`（:576） | 幂等，8.5 |
| `retries` | `Integer.MAX_VALUE`（:429） | 配合 delivery.timeout |
| `delivery.timeout.ms` | 120000（:446） | 发送总预算 |
| `max.block.ms` | 60000 | send() 阻塞上限（元数据/缓冲） |
| `max.in.flight.requests.per.connection` | 5（幂等上限 :296） | 幂等开：≤5 顺序保持（序列号机制）；幂等关：>1 + 重试有乱序风险 |
| `batch.size` / `linger.ms` | 16384 / 5（:440/:445） | 微批吞吐杠杆 |
| `transactional.id` / `transaction.timeout.ms` | null / 60000 | 事务必填 / 长事务风险 |

**Consumer**（`ConsumerConfig.java`）：

| 配置 | 默认 | 说明 |
|---|---|---|
| `enable.auto.commit` | `true`（:491） | **可靠性场景显式置 false** |
| `auto.commit.interval.ms` | 5000（:496） | 仅自动提交时有效 |
| `max.poll.interval.ms` | 300000（:660） | 处理批上限，超时被踢 |
| `max.poll.records` | 500（:100） | 单 poll 量 = 提交粒度 |
| `session.timeout.ms` / `heartbeat.interval.ms` | 45000 / 3000（:469/:474） | 存活判定 |
| `isolation.level` | `read_uncommitted`（:382） | 事务下游置 `read_committed` |
| `group.protocol` | `classic`（:121） | KIP-848 新协议置 `consumer` |

**Topic/Broker**（`TopicConfig.java` / `ServerLogConfigs.java` / `LogConfig.java`）：

| 配置 | 默认 | 说明 |
|---|---|---|
| `replication.factor` | 1（建 topic 显式给 ≥3） | 副本数 |
| `min.insync.replicas` | 1（`ServerLogConfigs.java:155`）→ **建议 2** | ISR 写入底线（`Partition.scala:1237`） |
| `unclean.leader.election.enable` | `false`（`LogConfig.java:138`）→ **保持 false** | 选举纪律 |
| `offsets.topic.replication.factor` / 分区数 | 3 / 50（`GroupCoordinatorConfig.java:130/121`） | 位点 topic |
| `transaction.state.log.min.isr` / RF | 2 / 3（`TransactionLogConfig.java:45`） | 事务 topic |

## 10.2 关键概念详解：ISR、HW/LEO、LSO、身份四件套、位点、leader epoch

这些概念在正文里是随机制出现的；本节把它们**单独钉死**——每个先给一句话定义，再给机制与常见误解。建议面试前只读本节。

### ISR（In-Sync Replicas，同步副本集合）

**一句话**：ISR 是"**当前有资格参与写入仲裁的副本名单**"（含 leader 本人）。它回答的不是"谁和 leader 一模一样"，而是"**谁最近证明过自己跟得上**"。

机制要点：

- **动态进出**：follower 在 `replica.lag.time.max.ms`（默认 30s，`ReplicationConfigs.java:55`）内追上过 leader 的 LEO → 进 ISR（`Partition.maybeExpandIsr`，`Partition.scala:878`）；超过阈值没追上 → 被踢出（`Partition.maybeShrinkIsr`，`Partition.scala:1091`，调度入口 `ReplicaManager.scala:2109`）。挂掉的副本修好、追平数据后**自动重新入队**，无需人工。
- **AR = ISR ∪ OSR**：AR（Assigned Replicas）是建分区时配置的副本全集；落后出局的副本进 OSR（Out-of-Sync Replicas）。ISR 是 AR 的动态子集。
- **与 acks 的关系**：`acks=all` 的 "all" = **当前 ISR**，不是 AR——ISR 缩到只剩 leader 时，acks=all 退化成 acks=1。所以**副本多 ≠ 更安全，`min.insync.replicas`（ISR 大小的写入门槛，`Partition.scala:1236-1240`）才是底线**。
- **与 HW 的关系**：ISR 成员之间只在 HW **之前**严格一致；HW 之后（未提交的尾部）允许有差异。ISR 的精确含义是"**HW 的推进以他们的进度为准**"（4.5）。

四个常见误解（面试高危）：

1. "ISR 是配置好的静态名单" ✗ 它随副本存活/落后情况实时进出；
2. "acks=all 会等所有副本" ✗ 只等 ISR，OSR 里的落后副本不被等待；
3. "ISR 内副本与 leader 逐字节一致" ✗ 只是"落后时间低于阈值"，尾部可以有未提交差异；
4. "replication.factor=3 就不丢" ✗ ISR 可以缩到 1；要配 `min.insync.replicas=2` + `unclean.leader.election.enable=false` 才闭环（8.3.2）。

### LEO 与 HW：一个数字对，撑起"可见性"与"不丢"

- **LEO（Log End Offset）**：**每个副本各自的**"下一条待写位移"（已有最大 offset + 1）。
- **HW（High Watermark，高水位）**：leader 视角下 **ISR 成员 LEO 的最小值**（精确推进规则见 4.5 的 `maybeIncrementLeaderHW`，`Partition.scala:1012`）。一个数字两用：①**消费可见性边界**——消费者永远读不到 HW 之后的消息；②**副本截断边界**——follower 恢复时把日志截回 HW（配合 leader epoch，见下）。

```
offset:   0 ....... 989 │ 990 ..... 999 │ 1000
          已提交、消费者可见 │ leader 已写，    │ leader 的下一条
          ◄── HW=990 ──►  follower 还没拉到
          leader LEO = 1000，follower LEO = 990 → HW = min = 990
```

为什么这样设计：offset 990..999 已在 leader 日志里，但 follower 还没拉走；此刻 leader 挂了，由 follower 顶上——它没有这 10 条。**正因为消费者永远读不到 HW 之后，"切主后消失的消息"才不可能变成"切主前已被消费的消息"**——HW 是"消费端不丢"语义在服务端的锚点（与 8.3 三环配置联动）。

### LSO（Last Stable Offset）：事务语境的可见性边界

分区内"**所有未决事务（无论最终 commit 还是 abort）都已落定**"的最大位移；无进行中事务时 LSO = HW。`read_committed` 消费者只能读到 LSO（Fetch 响应携带，`ReplicaManager.scala:1831/1855/1969`）。一个长事务会把 LSO 拖在它的起始位移上，下游 read_committed 消费者整体停摆（8.9 反模式 9）。

### 身份四件套：PID、producerEpoch、transactional.id、group.id

四个都叫"ID"，层次完全不同：

| 身份 | 谁分配 | 生命周期 | 用途 |
|---|---|---|---|
| PID（producer id） | broker 事务协调器（InitProducerId） | 进程实例级，**重启即换** | 幂等去重键的前半（去重 = PID + 分区 + 序列号，8.5） |
| producerEpoch | 事务协调器 | 同一 transactional.id 每次被顶替时 +1 | **僵尸隔离**：老 epoch 的一切写入被 broker 拒绝 |
| transactional.id | **用户配置** | 跨重启稳定的**逻辑身份** | fence 的依据（8.6.3）；决定事务路由到哪个协调器分区 |
| group.id | 用户配置 | 消费组身份 | 位点按 (group, partition) 记录（6.4）；与生产端身份无任何关联 |

最容易混的一对：**transactional.id 是你给"逻辑流"起的名字（要稳定），PID 是 broker 发给"当前实例"的工牌（每次启动都换）**——Outbox 重放、上游重推之所以防不住（8.5.2），就是因为新实例拿到新 PID，broker 视其为全新生产者。

### 位点的四种 offset：消费端排错必须先分清

| offset | 含义 | 谁推进 |
|---|---|---|
| log start offset | 日志里最老的一条（清理会推进它） | retention/deleteRecords；比它老的**谁都读不到** |
| LEO | 最新一条 + 1 | 写入；比它新的**还不存在** |
| committed offset | **持久**进度（存 `__consumer_offsets`） | commit；决定"重启/再平衡后从哪读" |
| fetch position | 内存游标 | poll 即推进；决定"下一次 fetch 从哪开始" |

**committed 与 position 之差 = 崩溃后会重复消费的区间**（8.4 的窗口 C）；position 落后 LEO 太多 = 消费积压。

### leader epoch：换主时"该截断到哪"的依据

leader 的"届数"（单调递增整数），每次 leadership 变更 +1（`Partition.makeLeader`，`Partition.scala:590` / `makeFollower`，`:696`）。两个用途：①每个副本维护 (leaderEpoch, endOffset) 检查点，换主时用它精确判断"我的日志哪些段是无效的该截掉"——替代旧版"一律截回 HW"的做法，避免把**已提交**的数据截掉（老版本著名的丢数据场景）；②客户端元数据缓存携带 leader epoch，发现 epoch 过旧立即刷新（3.3 的 `waitOnMetadata`）。

## 10.3 关键类速查表

| 类 | 模块 | 一句话 |
|---|---|---|
| `KafkaProducer` | clients | 生产门面：doSend 七步（3.3） |
| `RecordAccumulator` / `Sender` | clients | 批量累加器 / I/O 线程与重试（3.4） |
| `TransactionManager` | clients | 幂等与事务状态机（3.6/3.7） |
| `KafkaConsumer` → `ClassicKafkaConsumer` / `AsyncKafkaConsumer` | clients | 消费门面与双协议实现（6.1/6.2） |
| `ConsumerCoordinator` / `CommitRequestManager` | clients | 经典协议组管理、位点提交（6.4） |
| `KafkaApis` | core | 全部 RPC 入口（4.1/6.3/7.2 的行号表） |
| `ReplicaManager` / `Partition` | core | 副本写入、读取、ISR、HW（4.2/4.5/4.6） |
| `UnifiedLog` / `LogSegment` / `LocalLog` / `LogCleaner` | storage | 日志引擎四件套（4.3/4.8） |
| `ProducerStateManager` / `ProducerAppendInfo` | storage | 生产者幂等状态的 broker 侧（8.5） |
| `KafkaRaftManager` / `KafkaRaftClient` / `QuorumState` | core/raft | KRaft 共识（5.2） |
| `QuorumController` / `MetadataImage` | metadata | 控制器与元数据快照（5.3） |
| `GroupCoordinatorService` / `GroupMetadataManager` / `OffsetMetadataManager` | group-coordinator | 新组协调器三件套（6.3/6.4） |
| `TransactionCoordinator`（Scala） | core | 事务两阶段（7.2） |

## 10.4 源码阅读入口清单（20 个关键文件）

1. `clients/.../producer/KafkaProducer.java` —— doSend（:1176）
2. `clients/.../producer/internals/RecordAccumulator.java` —— append（:285）/drain（:1083）
3. `clients/.../producer/internals/Sender.java` —— runOnce（:310）/completeBatch（:671）
4. `clients/.../producer/internals/TransactionManager.java` —— initializeTransactions（:321）
5. `clients/.../producer/ProducerConfig.java` —— 全部默认值
6. `clients/.../common/record/internal/DefaultRecordBatch.java` —— 批次头
7. `clients/.../consumer/KafkaConsumer.java` —— 门面委托（:556）
8. `clients/.../consumer/internals/AsyncKafkaConsumer.java` —— poll（:935）
9. `clients/.../consumer/internals/ConsumerCoordinator.java` —— 位点提交（:1146）
10. `clients/.../common/record/internal/FileRecords.java` —— 零拷贝 writeTo（:280）
11. `core/.../server/KafkaApis.scala` —— handleProduceRequest（:401）/handleFetchRequest（:568）
12. `core/.../server/ReplicaManager.scala` —— appendRecords（:638）/fetchMessages（:1665）
13. `core/.../cluster/Partition.scala` —— appendRecordsToLeader（:1221）/maybeIncrementLeaderHW（:1012）
14. `storage/.../log/UnifiedLog.java` —— append（:1128）
15. `storage/.../log/LogSegment.java` —— append（:252）/read（:435）
16. `storage/.../log/ProducerAppendInfo.java` —— checkSequence（:162）
17. `core/.../coordinator/transaction/TransactionCoordinator.scala` —— handleEndTransaction（:516）
18. `group-coordinator/.../GroupCoordinatorService.java` —— consumerGroupHeartbeat（:515）
19. `metadata/.../controller/QuorumController.java` —— 事件驱动控制器
20. `core/.../kafka/raft/KafkaRaftManager.scala` —— KRaft 装配

## 10.5 学习路线（动手向）

1. **起一个 4.x 单机集群**（KRaft 模式 `process.roles=broker,controller`），`kafka-console-producer/consumer` 走通基础流；
2. **写一个"生产端"实验**：分别用 `acks=0/1/all` + `kill -9` broker，观察回调与 `min.insync.replicas` 拒写（NotEnoughReplicasException）——亲手复现 8.2/8.3；
3. **写一个"消费端"实验**：`enable.auto.commit=false`，处理到一半 `kill -9`，重启后观察重放区间——理解 8.4 的窗口 C；
4. **做幂等实验**：开/关 `enable.idempotence`，用 tc/iptables 注入网络延迟制造重试，比对 broker 日志的 `DUPLICATE_SEQUENCE_NUMBER`/乱序日志；
5. **做事务实验**：transactional producer + `read_committed` consumer，中途 `kill -9`，观察 abort 标记与下游可见性；
6. **源码精读**：按 10.4 顺序，先 1→3（一次 send 的完整回路），再 12→13（acks 的 broker 语义），最后 17（两阶段）。

## 10.6 KRaft 原理详解（KIP-500）

第五章只给了 KRaft 的轮廓；本节按"动机 → Raft 入门 → 角色与集群形态 → 元数据即日志 → 控制器 → broker 侧 → 一次换主的一生 → 对比与影响"展开原理。

### 10.6.1 动机：ZooKeeper 模式的三宗罪

旧架构（2.x 及以前）里，controller 的选举、broker 注册、topic 元数据全放 ZK。三个结构性问题：

1. **两套一致性系统并存**：ZK 的 ZAB 管元数据，Kafka 自己的 ISR 协议管数据——心智、运维、故障模式双倍；
2. **元数据传播链长**：任何变更都要走"controller 写 ZK → broker 的 watch 触发 → 各自拉取"，watch 数量随分区数线性膨胀，百万分区时是灾难（watch 风暴）；
3. **controller 故障切换慢**：新 controller 要等 ZK 会话超时 + 重新注册 + 全量同步元数据，秒级到分钟级，期间集群无主。

KRaft 的答案：**让 Kafka 用自己最擅长的方式——日志——管理自己的元数据**。controller 集群就是一个 Raft 复制组，元数据变更写进一个特殊的分区日志，所有人（controller 副本与 broker）都是这个日志的订阅者。

### 10.6.2 Raft 三分钟入门

Raft 把分布式共识拆成两个问题："谁是 leader"（选举）和"哪些日志算数"（复制与提交），用**任期（term，KRaft 里叫 epoch）** 串联：

- **多数派（quorum）**：3 个投票者里任意 2 个达成一致即有效。所有正确性都建立在"任意两个多数派必有交集"上。
- **选举**：follower 超时收不到 leader 心跳 → 自荐（Prospective/Candidate，逐届加码）→ 向所有人发 Vote → **拿到多数派选票即成为新 leader**，epoch + 1。旧 leader 若复活，看到更高 epoch 立即退位。
- **复制与提交**：所有写请求只进 leader → leader 追加本地日志 → followers 通过 Fetch 拉走 → **多数派落盘即提交** → follower 各自回放。
- 两条安全不变量：**Log Matching**——两个日志在同一 (epoch, offset) 上内容一致，则之前的都一致（选举时选民会拒绝日志落后的候选人，保证当选者日志不输于多数派）；**Leader Completeness**——一旦提交，任何未来的 leader 都必然持有这条记录。

一句话：**Raft 把"谁是对的"之争变成"谁是最新届"之争**——epoch 大者胜，而多数派保证"最新届"包含全部已提交历史。

### 10.6.3 角色与集群形态

| 概念 | 含义 |
|---|---|
| `process.roles` | broker / controller / 两者兼做（combined）。4.0 起只有这两种角色，无 ZK 模式 |
| voter（投票者） | controller quorum 成员，参与选举与元数据日志复制；`__cluster_metadata-0` 的 RF = quorum 大小 |
| observer（观察者） | broker 的本质：**不投票，只订阅元数据日志**（以及 controller 作为非 voter 的降级形态） |
| quorum 管理 | 静态：`controller.quorum.voters` 配死名单；动态（KIP-853，4.x）：运行期增删 voter，扩容控制面不用重启 |

broker 的生命周期与数据面无关地简单：启动 → 向 active controller 注册（心跳，`metadata/.../controller/BrokerHeartbeatManager.java`）→ 超时未心跳被 **fence**（从 ISR/可服务集合里摘除）→ 恢复后重新注册。controller 从不参与消息读写——**控制面与数据面彻底分离**。

### 10.6.4 元数据即日志：`__cluster_metadata`

元数据日志就是一个**普通的单分区 Kafka 日志**（`__cluster_metadata-0`，`clients/.../common/internals/Topic.java:30`），只不过：

- 它的"生产者"是 active controller，"消费者"是所有 controller 副本和全部 broker；
- 它**复用数据日志的整套存储引擎**：`KafkaRaftManager`（`core/.../kafka/raft/KafkaRaftManager.scala:87`）在 `:129` 装配 `raftLog = buildMetadataLog()`（`:196` → `KafkaRaftLog.createLog`），而 `KafkaRaftLog`（`raft/.../raft/internals/KafkaRaftLog.java:79`）就是"分段 + 稀疏索引 + 顺序追加"那一套实现的 `RaftLog` 接口化——**共识日志与业务日志共享同一个存储引擎**（1.2 哲学 3 的极致体现）；
- 日志里的每条记录是控制器操作产出的 `ApiMessageAndVersion`（建 topic、分区变更、ISR 变更、broker 注册/注销、配置变更……）；
- 存储隔离：它只允许出现在 metadata 目录（`KafkaRaftServer.scala:144` 显式校验），与数据目录（`log.dirs`）分开。

### 10.6.5 控制器：事件驱动 + 回放 + 快照

【源码证据】`QuorumController`（`metadata/.../controller/QuorumController.java:177`）是唯一实现，三个设计支柱：

1. **单线程事件循环**：所有 RPC（建 topic、改配置、broker 心跳……）被封装成 `ControllerEvent` 丢进 `eventQueue` 串行执行（`:546` handleEventEnd 记录每个事件的处理耗时）——**免锁，天然串行化**。
2. **写入 = 追加日志**：事件处理器（各 `*ControlManager`，如 `ReplicationControlManager`，`:149`——负责 topic/分区/ISR）**只读当前镜像、产出增量记录**（`ControllerResult`），active controller 把记录 `raftClient.append(...)` 追加进元数据日志；**多数派提交后**才通过 `listener.handleCommit` 回放（`raft/.../raft/KafkaRaftClient.java:4132`）并完成 RPC future——**"写日志成功"与"状态生效"是同一步**。
3. **回放 = Delta → Image**：每条提交记录累积进 `MetadataDelta`，周期性或按需物化为不可变的 `MetadataImage`（`metadata/.../image/MetadataImage.java`，内含 ClusterImage/ConfigurationImage/…子树）。**镜像只是日志的缓存**——任何时候都可以从"快照 + 日志重放"重建。

**快照（snapshot）**：日志会无限增长，新副本（或重启的 controller）若从 offset 0 全量回放太慢，所以 KRaft 周期性把"截至某 offset 的完整镜像"写成快照文件（`raft/.../snapshot/FileRawSnapshotWriter.java`），此后追赶 = 读快照 + 重放快照点之后的日志。这与组协调器/事务协调器的"快照 + 日志"运行时（`coordinator-common`）是同一个思想在不同层的复用。

### 10.6.6 Broker 侧：订阅元数据日志，本地应答一切数据面请求

broker 不向 controller 查询任何"这个 topic 的 leader 是谁"——它自己就是元数据日志的订阅者：

【源码证据】`BrokerMetadataPublisher`（`core/.../server/metadata/BrokerMetadataPublisher.scala:68`）订阅元数据日志的提交流，每批提交后把 Delta 应用到本地 `KRaftMetadataCache`（`:70`），然后触发本地回调（更新 ReplicaManager 的 leader/ISR、日志目录等）。此后 `KafkaApis` 处理 produce/fetch 全靠本地缓存——**controller 挂了，数据面照常读写**（只是无法做拓扑变更和故障转移），这是与 ZK 模式"controller 挂 → 集群半瘫"的本质区别。

### 10.6.7 一次换主的一生

```
active controller 宕机
  → 各 voter 心跳超时，先自荐为 Prospective（QuorumState.java:511，KIP-853 引入的"试探"态）
  → 发 Vote（带自己的日志末端 epoch/offset）；选民按 Log Matching 拒绝日志落后的候选人
  → 拿到多数派选票 → 成为 Leader，epoch+1
  → 读最新快照 + 重放其后日志，回放到"已提交的顶端"
  → 对前任未提交的尾部：epoch 更大的记录保留重放，epoch 相同但未被多数派确认的丢弃截断
  → 开始接受 ControllerApis 写入；各 broker 心跳重新确认注册（被误判死的会重新 unfence）
全程秒级；期间 broker 数据面不受影响（10.6.6）。
```

### 10.6.8 与 ZK 模式对比及对使用者的影响

| 维度 | ZK 模式（≤3.x） | KRaft（4.0+ 唯一） |
|---|---|---|
| 一致性系统 | ZAB + ISR 两套并存 | 仅 ISR 协议一套（元数据也是 Kafka 日志） |
| 元数据传播 | controller ↔ ZK ↔ broker（watch） | 元数据日志单源，controller/broker 都是订阅者 |
| controller 切换 | 会话超时 + 全量同步，秒~分钟 | Raft 选举，秒级 |
| 规模上限 | 百万分区时 watch 风暴 | 元数据日志 + 快照，分区上限提升一个数量级 |
| 部署 | 额外维护 ZK 集群 | `process.roles` 声明角色，combined 或 isolated |
| 配置 | `zookeeper.connect` | `controller.quorum.voters`（或动态 quorum，KIP-853） |

对应用开发者：**客户端协议零变化**（这也是 Kafka 敢切架构的底气——协议即契约）。对运维者：新增两件事——元数据目录（`metadata.log.dir`，建议独立盘）的监控、以及 quorum 成员变更要走动态流程；换来的是删掉一整个 ZK 集群的运维成本。

### 10.6.9 源码地图

| 关注点 | 文件 |
|---|---|
| 装配与元数据日志目录 | `core/.../kafka/raft/KafkaRaftManager.scala:87`（:129/:196） |
| 元数据日志引擎（=普通日志引擎的接口化） | `raft/.../raft/internals/KafkaRaftLog.java:79` |
| Raft 协议主体（选举/复制/提交） | `raft/.../raft/KafkaRaftClient.java:170`（Vote 响应 :953，提交回放 :4132） |
| quorum 状态机（Prospective/Candidate/Leader/Follower） | `raft/.../raft/QuorumState.java:84` |
| 控制器事件循环与各 ControlManager | `metadata/.../controller/QuorumController.java:177`、`ReplicationControlManager.java:149` |
| 元数据镜像（Delta→Image） | `metadata/.../image/MetadataImage.java` |
| 快照 | `raft/.../snapshot/FileRawSnapshotWriter.java` |
| broker 侧订阅与本地缓存 | `core/.../server/metadata/BrokerMetadataPublisher.scala:68` |
| broker 心跳与 fence | `metadata/.../controller/BrokerHeartbeatManager.java` |

## 结语

Kafka 的全部机制可以压缩成一句话：**用一个不可变、有序、可复制的日志，同时解决"存储、复制、协调"三个问题**。生产者的批量与幂等、broker 的 ISR 与高水位、消费者的位点与再平衡、事务的控制记录，都是这对"日志 + 位点"原语在不同问题上的投影。配置只是这些投影的开关——理解了 8.1 的三个确认边界，你就有了在任何故障场景下推导"会丢吗？会重吗？"的坐标系；剩下的，是让业务副作用（8.7）替分布式系统守住最后一厘米的不确定性。





