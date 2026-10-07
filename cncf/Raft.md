# Raft 深度解析：共识算法、工业实现与组件实战

> **本文性质**：算法原理 + 主流 Raft 实现源码对照 + 组件实战分析。它是同目录《ACID-CAP-BASE.md》的姊妹篇：那份文档回答"一致性理论各自回答什么问题、各组件站在 CP 还是 AP"；本文把其中反复出现的 **Raft** 一词展开到底——算法怎么运转、工业库怎么实现、Kafka/etcd/TiKV/RocketMQ/Nacos 这些组件拿它解决了什么问题，以及哪些组件其实只是"借了 Raft 的壳"。
>
> **源码基线**（下表所有仓库均在本地 `D:\code\3rd`，文中【源码证据】的路径与行号均实测于以下快照）：
>
> | 仓库 | 本地路径 | commit | 版本 | 在本文中的角色 |
> |---|---|---|---|---|
> | etcd-io/raft | `D:\code\3rd\raft-etcd` | `1c0011d` | main（3.6 线） | Raft 算法库参考实现（三~七章） |
> | etcd | `D:\code\3rd\etcd` | `76d58e3` | main（3.6+） | etcd server（8.2 节） |
> | hashicorp/raft | `D:\code\3rd\raft` | `871f108` | main | 一站式 Go 库（第七章、Consul） |
> | tikv/raft-rs | `D:\code\3rd\raft-rs` | `ad13f3d` | main | Rust 移植（第七章、TiKV） |
> | kafka | `D:\code\3rd\kafka` | `c2fbf6f` | 4.5.0-SNAPSHOT | KRaft（8.1 节） |
> | redis | `D:\code\3rd\redis` | `498ecd0d6` | 8.10.2 | Sentinel/Cluster 选举（8.4 节） |
> | rocketmq | `D:\code\3rd\rocketmq` | `37af143` | 5.5.1 | DLedger / Controller（8.5 节） |
> | nacos | `D:\code\3rd\nacos` | `1b6309f` | 3.3.0-RC | JRaft（8.6 节） |
>
> ClickHouse Keeper（基于 eBay nuRaft）、MongoDB、ZooKeeper 未做本地源码核对，对应小节仅基于官方文档与公开资料描述，并在文中显式标注。
>
> **准确性说明**：Raft 的算法骨架自 2014 年论文发布以来没有变过，本文对"算法层"的描述适用于任何版本；"工程增强"（PreVote、CheckQuorum、单步成员变更、快照、读优化）按各实现引入的版本显式标注；组件行为以表中快照为准，涉及版本差异处单独说明。
>
> **阅读约定**：每节"先白话、后机制/源码"。【源码证据】统一写成 `路径 → 类#方法（行号）` 的形式；行号只对该快照精确，读者用 IDE 按类名 + 方法名定位即可。Raft 论文本身也可以当作"源码"来读——本文把论文图 2 的规则当作规格说明，把 etcd-io/raft 与 Kafka raft 当作两套互相印证的"参考实现"。

## 如何读这份文档

开读之前，先泼三盆冷水——下面三个流行说法都是错的，读完本文你应当能自己解释为什么：

1. **"Kafka 用 KRaft 存消息"——错。** KRaft（Kafka Raft）只复制**集群元数据**（topic/partition/配置/副本分配），它替代的是 ZooKeeper 这个"元数据协调者"，不是替代 ISR 数据复制协议；数据面消息副本的同步依然走 Leader/Follower ISR 那一套（见 8.1）。
2. **"Redis 用 Raft 保证一致性"——不准确。** Redis 数据面的主从复制是**异步**的、最终一致的（AP 定位）；Sentinel 与 Redis Cluster 只是**借用了 Raft 的选举思想**（任期 epoch + 一任期一票 + 多数派授权）来做故障切换，从未实现 Raft 的日志复制（见 8.4）。
3. **"上了 Raft 就是线性一致读"——错。** Raft 天生保证的是**写入的全序**（进入日志且被提交的命令顺序唯一）；读请求默认不写日志、直接读状态机，可能读到旧值，必须用 ReadIndex 或 Lease Read 补齐（见 5.2~5.5）。

推荐两遍读法：

- **第一遍（建立地图，约 1.5 小时）**：读第一章全部 + 二~六章每章的"本章小结" + 8.0 节总览表 + 8.1（Kafka KRaft）/ 8.2（etcd）/ 8.4（Redis）三节。读完应能回答：Raft 把共识拆成哪三件事？选主靠什么规则避免双主？为什么"读"需要特殊处理？KRaft 到底替代了 ZooKeeper 的什么？
- **第二遍（按需深入）**：做基础架构读第三~六章（算法与工程化）+ 第七章（选库）；使用某个具体组件的读第八章对应小节；自己要造一个带共识的小系统，读第九章实践清单。

前置知识：知道"主从复制"是什么，最好经历过"主从延迟"或"切主丢数据"任一现场；理解 CAP 的取舍（《ACID-CAP-BASE.md》第三章）；知道"日志/顺序刷盘"是什么。

---

# 一、总览：Raft 的定位、设计哲学与问题地图

## 1.1 一句话定位

**Raft 是一个为"可理解性"而设计的分布式共识（Consensus）算法**：它让 2f+1 台机器对**同一条操作日志的顺序与内容**达成一致，使得部分机器宕机、网络分区、消息乱序时，集群仍能像"一台永不宕机、永不撒谎的机器"一样对外提供**复制状态机**（Replicated State Machine, RSM）服务。

- 论文：Diego Ongaro & John Ousterhout, *In Search of an Understandable Consensus Algorithm*, USENIX ATC 2014（算法骨架，14 页）。
- 完整规格：Ongaro 博士论文 *Consensus: Bridging Theory and Practice*（2014，成员变更、快照、客户端交互、PreVote/Lease 等工程增强都在这里）。
- 配套：raft.github.io 收录全部实现与可视化；官方 TLA+ 规约可用于模型检验。

一句话说清它与 Paxos 的关系：**Multi-Paxos 是设计意图，Raft 是施工图**。Paxos 只回答"如何对一个值达成一致"，Multi-Paxos 给出"连续多个值"的骨架，但 Leader 选举、成员变更、日志压缩、客户端交互全部留白——导致每一家的 Multi-Paxos 实现互不兼容、无法对拍。Raft 把这些空白全部填上：伪代码精确到可编译，成员变更/快照给出了可对比的候选方案，于是 2014 年之后开源界的新共识实现几乎清一色选择 Raft（1.5 节清单为证）。

## 1.2 设计哲学：读后面章节前先记住四句话

1. **把难题拆成三个独立的小难题**（Problem Decomposition，论文第 1 节的设计目标）。共识 = **Leader 选举**（谁来写）+ **日志复制**（怎么写一致）+ **安全性**（写错了怎么办）。三块可以独立理解、独立讲解、独立测试——本文第三、四、五章就按这个顺序展开。
2. **强 Leader 模型**。所有写入决策收敛到唯一 Leader；日志只从 Leader 流向 Follower（AppendEntries 单向流动）；Leader 永不覆盖、永不删除自己日志中的条目。把 n 方博弈压缩成"1 个决策者 + n-1 个执行者"，状态空间大幅缩减。
3. **任期（Term）是全局逻辑时钟**。一切分歧——谁是 Leader、哪条日志更新、配置是哪一版——都拿 term 比大小。term 单调递增；一个节点一旦看到更高的 term，立即放弃自己的旧身份。旧 term 的一切让位于新 term。
4. **多数派（Quorum）是唯一的真理尺度**。2f+1 台容 f 台故障；选举要过半票、提交要过半确认、成员变更要新旧配置各自过半。**任意两个多数派必然相交**——这个交集是整个算法安全性的支点：提交过的条目至少存在于一个会参与下一次选举的节点上。

## 1.3 全景分层：从问题域到组件

```
┌─────────────────────────── 问题域 ───────────────────────────────┐
│  复制状态机 RSM：多台机器执行同一份命令日志 → 对外像一台机器          │
│  （共识模块的职责边界：只保证"日志一致"，状态机执行是业务自己的事）    │
├─────────────────────────── 算法三件套（论文本体）───────────────────┤
│  ① Leader 选举：term + 随机超时 + RequestVote + 一任期一票          │
│  ② 日志复制：AppendEntries + 一致性检查 + 提交规则（过半 + 当前任期） │
│  ③ 安全性：五大性质（Election Safety / Leader Append-Only /         │
│     Log Matching / Leader Completeness / State Machine Safety）    │
├─────────────────────────── 工程增强（论文/工业界补丁）───────────────┤
│  PreVote（预投票）· CheckQuorum（领导权巡检）· 单步成员变更          │
│  快照与日志压缩 · ReadIndex / Lease Read（线性一致读）· 批/流水线    │
├─────────────────────────── 实现库（拿现成的，别手写）────────────────┤
│  Go：etcd-io/raft（算法库）、hashicorp/raft（一站式）               │
│  Rust：tikv/raft-rs    Java：SOFAJRaft、DLedger    C++：nuRaft     │
│  自研：Kafka KRaft、ClickHouse Keeper、etcd server、Consul server  │
├─────────────────────────── 组件（第八章逐一展开）───────────────────┤
│  元数据共识：Kafka KRaft、etcd(K8s)、ClickHouse Keeper、RocketMQ     │
│              Controller、Consul                                    │
│  存储共识：  TiKV/TiDB、CockroachDB、YugabyteDB（Multi-Raft）       │
│  借选举不借日志：Redis Sentinel、Redis Cluster                      │
└──────────────────────────────────────────────────────────────────┘
```

读这张图的方法：**从上往下是"抽象层级递减"**——论文只定义前三层；第四层（工程增强）论文里给出了方案但细节在各实现里；第五、六层是你要真正打交道的代码。本文的章节顺序与这张图一一对应。

## 1.4 关键问题 → Raft 方案映射（全文导览）

| 分布式系统的关键问题 | Raft 的方案 | 详见 |
|---|---|---|
| 谁来主导写入？ | Leader 选举：随机超时 + RequestVote + 一任期一票 | 第三章 |
| 两台机器同时认为自己是 Leader（脑裂/双主） | term 单调 + 一任期一票 + "日志不落后才能当选"的投票约束 | 3.4、5.1 |
| 副本日志长歪了（缺失/多余/冲突条目） | AppendEntries 一致性检查 + nextIndex 回退修复 + Log Matching | 4.2、4.3 |
| 旧任期的日志能不能直接提交？ | 不能。只直接提交当前任期条目（Figure 8），旧任期日志被"顺带"提交 | 4.4 |
| 读到旧数据（读不走日志） | ReadIndex / Lease Read；先向多数派确认 Leader 仍然有效再读 | 第五章 |
| 集群成员要增减节点 | 单步成员变更（工业标准）/ Joint Consensus（论文方案） | 6.1 |
| 日志无限增长、重启回放太慢 | 快照 + InstallSnapshot 日志压缩 | 6.2 |
| 被隔离的旧 Leader 继续接写请求 | PreVote + CheckQuorum：联系不上多数派就退位 | 3.5、3.6 |
| 客户端超时重试导致命令重复执行 | 会话 + 去重表 / 记录级幂等（各组件方案不同） | 4.5 |
| 吞吐不够 | 批处理、流水线（pipeline）、流控 | 4.6 |

## 1.5 版本演进与"谁在用 Raft"

### 1.5.1 时间线

| 年份 | 事件 |
|---|---|
| 1998 / 2001 | Lamport 发表 Paxos（Part-Time Parliament / Paxos Made Simple）：正确但难懂，此后十余年没有统一工业实现 |
| 2013~2014 | Ongaro 完成 Raft 博士论文与 ATC'14 论文；**etcd 内置自研 raft 库**（此后十年成为 Go 生态的"标准件"）；**Consul 诞生即以 hashicorp/raft 为核心** |
| 2015 | MongoDB 3.2 发布 PV1 复制协议（Raft 衍生，选举与提交规则同源，工程细节大改） |
| 2016 | TiKV 立项（Multi-Raft 分片存储）；CockroachDB 同期采用 Multi-Raft |
| 2018 | TiKV 把 Raft 引擎抽出为独立 Rust 库 **tikv/raft-rs**（etcd/raft 的 Rust 移植 + 批处理优化） |
| 2019 | **KIP-500 提出"用内置 Raft 替代 ZooKeeper"**；RocketMQ 4.5 引入 **DLedger**（Raft 多副本 CommitLog） |
| 2020 | Nacos 1.3 用 **SOFAJRaft** 重写配置中心持久化层（CP 侧） |
| 2021 | Kafka 2.8：**KRaft 早期访问** |
| 2022 | Kafka 3.3：KRaft **生产可用**；ClickHouse 22.3：**clickhouse-keeper 生产可用**（nuRaft） |
| 2023~2024 | Kafka 3.6：ZK→KRaft 迁移 GA；3.7：ZooKeeper 模式标记废弃 |
| 2025 | **Kafka 4.0：彻底移除 ZooKeeper**，KIP-853 动态 quorum（voter 可在线增删改）；etcd 3.6 将 raft 库拆分为独立仓库 **go.etcd.io/raft** |

### 1.5.2 组件花名册：谁在用、用在哪一层

| 组件 | Raft 用在哪 | 被复制的数据 | 形态 |
|---|---|---|---|
| **Kafka（KRaft）** | Controller Quorum（控制面） | 集群元数据（topic/分区/配置/副本分配） | 自研 raft（KIP-595/853） |
| **etcd（Kubernetes）** | 整个存储层 | 全部 API 对象 | etcd-io/raft |
| **TiKV / TiDB** | 每个 Region 一个 Raft 组（Multi-Raft） | 分片 KV（TiKV RocksDB 之上的数据） | raft-rs |
| **CockroachDB** | 每个 Range 一个 Raft 组（Multi-Raft） | SQL 层之下的 KV | 自研 Go |
| **Consul** | Server 集群 | catalog/KV/ACL/session | hashicorp/raft |
| **RocketMQ（DLedger 模式 / Controller 模式）** | Broker 多副本组 / Controller 元数据 | CommitLog / 主从切换编排元数据 | DLedger（Java） |
| **Nacos** | 配置中心持久化（CP 半边天） | 配置内容与历史 | SOFAJRaft |
| **ClickHouse Keeper** | Keeper 集群（替代 ZK） | 复制表协调元数据（去重/合并/DDL） | nuRaft（C++） |
| **Redis Sentinel / Cluster** | **只借选举思想，无日志复制** | ——（数据面仍异步复制） | 内置 C 实现 |
| MongoDB（对照） | 副本集复制与选举 | oplog | PV1（Raft 衍生） |

这张表也回答了一个常见困惑：**同样是"用了 Raft"，用法完全不同**——有拿它当"元数据大脑"的（KRaft、Keeper、Nacos），有拿它当"存储引擎的地基"的（TiKV、CRDB），也有只借"选举那一半"的（Redis）。第八章逐个拆解。

## 1.6 全文章节地图

| 章节 | 内容 | 适合谁读 |
|---|---|---|
| 一、总览 | 定位、设计哲学、问题映射、版本演进 | 所有人 |
| 二、共识问题与复制状态机 | Raft 要解决什么、RSM 合同、FLP 与超时 | 所有人（概念地基） |
| 三、Leader 选举 | term、三角色状态机、投票约束、PreVote/CheckQuorum | 基础架构 |
| 四、日志复制 | AppendEntries、一致性检查、Figure 8 提交规则、幂等、批/流水线 | 基础架构 |
| 五、安全性与线性一致读 | 五大性质、为什么读需要特殊处理、ReadIndex/Lease | 基础架构、所有人 |
| 六、工程化三大件 | 成员变更、快照压缩、存储/网络工程 | 基础架构 |
| 七、主流实现库盘点 | etcd-io/raft、hashicorp/raft、raft-rs、JRaft…选型 | 选库的人 |
| 八、组件实战 | KRaft/etcd/TiKV/Redis/RocketMQ/Nacos/Keeper/Consul 逐个拆 | **所有人（重点）** |
| 九、构建自己的 Raft 应用 | 选型决策、状态机设计十条、运维监控、常见坑 | 动手的人 |
| 十、附录 | RPC 字段速查、源码阅读入口、术语表、参考资料 | 随用随查 |

---
# 二、共识问题与复制状态机：Raft 的前情提要

## 2.1 从主从复制说起：共识到底在解决什么

白话版：单机数据库不可靠，于是做三个副本。接下来立刻冒出三个问题——

1. **谁来收写入？** 三台都收，各自排各自的顺序，副本间顺序就乱了（同一批命令，A 机是 `x=1; x=2`，B 机是 `x=2; x=1`，结果不一致）。所以通常选一个"主"。
2. **主挂了怎么办？** 要从两个从里面选一个新主。可网络分区时，两边都以为自己能选——**可能出现两个主**（split brain），各自接受写入，数据分叉。
3. **怎么保证选出来的新主不丢数据？** 新主必须"知道所有已被确认的写入"，否则客户端刚收到"写入成功"的确认，新主上台，这条写入没了——**比宕机更糟糕：对客户端撒了谎**。

这三个问题的总和就是**共识问题**：一组节点，对**一系列命令的顺序**达成不可反悔的一致，并且在部分节点故障、网络分区、消息延迟乱序的情况下依然能达成。Raft 就是这个问题的工程答案。

## 2.2 复制状态机（RSM）：共识模块的"合同"

Raft 论文把目标系统抽象成**复制状态机**。它的"合同"只有三条：

1. 每台机器维护一条**日志**（log），日志里是客户端命令；
2. **状态机是日志的确定性投影**：从相同的初始状态出发，按相同顺序执行相同命令，必然得到相同状态（所以日志一致 = 状态一致）；
3. 对外表现为"一台机器"：客户端把命令交给集群，集群保证这条命令**要么被所有副本按同一位置执行，要么不执行**。

```
            ┌───────────────────────── 单台节点 ─────────────────────────┐
 客户端 ──► │  共识模块（Raft）                状态机（业务逻辑，确定性）    │
            │  ┌──────────────────────┐   apply    ┌──────────────────┐  │
            │  │ 日志 log：            │ ─────────► │ x=1              │  │
            │  │ [1] SET x=1          │            │ y=x+1            │  │
            │  │ [2] SET y=x+1        │            │ （业务状态）       │  │
            │  └──────────┬───────────┘            └──────────────────┘  │
            └─────────────┼───────────────────────────────────────────────┘
                          │ AppendEntries（只从 Leader 流向 Follower）
            ┌─────────────▼─────────────┐   ×(2f+1) 台互为副本
            │ Follower 的同一条日志       │   任意 f 台坏掉，集群仍可用
            └───────────────────────────┘
```

这个"合同"划定了 Raft 的职责边界，也是理解所有工业实现差异的钥匙：

- **Raft 只保证"日志一致 + 提交点之前的内容永不改变"**。状态机怎么执行、有没有副作用、重放是否幂等，全是状态机（业务代码）的责任（4.5 节会看到几种工业做法）。
- **"提交"（committed）是日志层面的概念**：一条日志被多数派持久化后即提交，提交后的条目**永不会被覆盖**（这是 Raft 对外承诺的持久性）。"应用"（applied）是状态机层面的概念，可以滞后于提交。
- 日志是**唯一真源**。etcd 的 WAL、Kafka 的 `__cluster_metadata`、DLedger 的 CommitLog，本质都是"这条日志"在各自系统里的化身。

## 2.3 安全性、活性与 2f+1

- **安全性（safety）**：错误的事情绝不发生——不存在两个 Leader 同一任期；已提交的日志永不被覆盖；每个副本按相同顺序 apply。Raft 在**异步网络 + 非拜占庭**（消息可丢、可迟、可乱序，但节点不撒谎）模型下保证安全性，**不依赖任何时钟同步**（term 只是本地逻辑时钟）。
- **活性（liveness）**：对的事情终会发生——多数派存活时，最终能选出 Leader、能提交写入。活性**依赖时钟推进**（选举超时必须真的会触发），这是 FLP 不可能定理（FLP, 1985：异步系统 + 一个故障节点，确定性共识算法不存在"必然终止"的解）给出的理论底线：**超时机制是共识的地基**。这也是为什么 Leader 所在节点的长 GC、磁盘 hang 会引发重新选举——时钟推进被卡住了。
- **为什么是 2f+1**：提交与选举都要求"多数派"，2f+1 台的多数派是 f+1 台，任意两个多数派的交集至少 1 台——这台交集机器保证"已提交的日志"能延续到新任期。2f+1 容 f 台**完全故障**（宕机），但**不是**容 f 台慢——大量副本拖慢多数派确认，吞吐照样塌（这是 8.3 节 Multi-Raft 的动机之一）。
- **与 CAP 的关系**：分区发生时，少数派一侧的 Raft 集群会因选不出 Leader / 无法过半提交而**拒绝服务**——这正是 CP 的机制化表达（呼应《ACID-CAP-BASE.md》3.4 节的 etcd/Consul 条目）。

## 2.4 本章小结

- 共识 = 多副本对**命令顺序**达成不可反悔的一致；主从复制引出的"选主、双主、丢数据"三问是它的动机。
- **RSM 合同**：日志是唯一真源，状态机是确定性投影；Raft 只管日志，"提交"≠"应用"。
- 2f+1 容 f；**多数派相交**是安全性的支点；FLP 决定了超时不可避免，因此 Raft 属于 crash-recovery 模型、依赖时钟推进。

---

# 三、Leader 选举：从心跳到多数派授权

## 3.1 白话：哪台机器有权写日志？

Raft 的答案朴素而强硬：**任何时刻至多一个 Leader，且只有 Leader 能决定日志顺序**。没有 Leader 时集群只读不可写（等待选出新 Leader）。选举要做的事：在 Leader 挂掉/联系不上之后，**快速**、**安全**地（不出现双主、新主日志不落后）推举出继任者。

## 3.2 任期 Term：分布式的"届"

- term 是一个单调递增的整数，逻辑上把时间切成一段一段的"届"。每届至多一个 Leader。
- 三个动作会推进 term：选举超时后发起选举（term+1）；收到比自己高的 term（立刻采纳）；与 leader 心跳失联后（先采纳更高的 term 再行动）。
- **term 是仲裁一切的尺子**：谁 term 大谁说了算——旧 Leader 收到更高 term 立即退位为 Follower；旧任期日志让位于新任期日志（4.4 节）。
- 对照记忆：ZooKeeper ZAB 的 zxid 高 32 位 epoch、Kafka 数据面的 leader epoch、Redis 的 currentEpoch/configEpoch——**大家不约而同用"逻辑时钟 + 单调递增"解决同一类问题**，Raft 只是把这个思想用到极致并写进了规格。

## 3.3 三种角色与状态机

```
       选举超时（随机化 150~300ms，论文建议；etcd 默认 1s）
   ┌──────────┐ ─────────────────────────► ┌─────────────┐
   │ Follower │                             │ Candidate   │
   └──────────┘ ◄─────────────────────────  └─────────────┘
        ▲        收到新 Leader 的 AppendEntries       │
        │        或更高 term 的 RequestVote           │ 获得多数派选票
        │                                            ▼
        │        发现更高 term 的 Leader         ┌───────────┐
        └──────────────────────────────────────  │  Leader   │
                                                 └───────────┘
   Leader 在职期间：周期性发心跳（空的 AppendEntries），阻止任何人超时
```

- **Follower**：被动。只响应来自 Leader/Candidate 的请求；多久没听到 Leader 心跳（**选举超时，随机化**）就自己参选。
- **Candidate**：参选者。term+1、给自己投票、并行向所有人发 RequestVote；拿到**多数派**票 → 当选 Leader；等票期间收到别人合法的 AppendEntries → 承认对方、退回 Follower；本轮没人过半（**选票分裂**）→ 随机超时后再来一轮（3.5 节）。
- **Leader**：周期心跳；接收全部写入（4 章）。

**为什么选举超时要随机化**：若所有节点超时相同，首轮会全员同时参选、平分选票、全员落选，下一轮又同时参选——活锁。随机化让"最幸运"的节点先醒来拉到多数派票。工业实现默认值：etcd `heartbeat-interval=100ms`、`election-timeout=1000ms`（`server/embed/etcd.go → TickMs/ElectionTicks，10 个 tick`）；论文建议 150~300ms 区间；Kafka KRaft 由 `quorum-election-timeout-ms` 等参数控制并在基础上随机抖动。

## 3.4 选举全流程与投票约束（核心中的核心）

RequestVote（v2 版含 clusterId、voter key 等，此处列论文核心字段）：

| 字段 | 含义 |
|---|---|
| term | 候选人的任期（+1 后的新 term） |
| candidateId | 谁在竞选 |
| lastLogIndex / lastLogTerm | 候选人日志**最后一条**的下标与任期（日志新旧凭证） |

投票方（Follower）的处理规则，**三条约束缺一不可**：

1. **任期检查**：`request.term < myTerm` → 拒绝，并把自己的更高 term 捎回去（候选人收到后立即退位）。
2. **一任期一票**：本 term 内已投过票（且不是同一候选人）→ 拒绝。**先到先得**——这条是"防双主"的第一道锁：同一 term 不可能产生两个 Leader（Election Safety 性质，5.1 节）。
3. **日志完整性检查**：候选人的 `(lastLogTerm, lastLogIndex)` **字典序不落后于**投票人自己的最后一条日志 → 才可投票。这条是"防丢数据"的锁：**日志不够新的人根本没有当选资格**（Leader Completeness 性质的来源，5.1 节）。

【源码证据·Kafka】`raft/src/main/java/org/apache/kafka/raft/KafkaRaftClient.java → handleVoteRequest（835）：`

- 854 行：请求携带 `preVote` 标志（3.5 节）；867 行：标准投票要求 `候选 lastEpoch < replicaEpoch`，PreVote 允许相等——因为 PreVote 不提升任期；
- 925~929 行：`quorum.canGrantVote(replicaKey, 候选OffsetAndEpoch >= 本地endOffset(), preVote)`——日志完整性检查就是 `(offset, epoch)` 的字典序比较；
- 931~937 行：授予标准选票且本任期未投过 → 持久化 `votedFor`（`unattachedAddVotedState / prospectiveAddVotedState`），**先落盘再回复**，重启后不能改口。

【源码证据·etcd-io/raft】`raft.go → campaign（1025）/ becomeLeader（933）/ stepCandidate（1673）`：角色切换与计票全部在 `raft` 结构内完成，网络与磁盘由调用方驱动（第七章详述这种"算法库"形态）。

当选之后：Leader 立即向所有节点发心跳（空 AppendEntries）宣示权威——收到心跳的节点若 term 更低，无条件退位为 Follower。

## 3.5 选票分裂与 PreVote：把"骚扰"挡在门外

**选票分裂**：三个候选人各得一票，无人过半 → 各自随机超时（150~300ms 内随机）再战。随机化保证几轮内必收敛；若持续分裂，通常是故障不止一半——集群本来就不可用，安全不破。

**PreVote（预投票，Ongaro 博士论文的扩展，etcd 3.4+ 默认开启）**：解决一个经典伤害场景——

> 一个网络分区的**少数派节点**反复自己超时、自己 term+1 拉票。等分区恢复，它带着一个**虚高的 term** 回来：活着的 Leader 收到更高 term 被迫退位，集群平白经历一次无意义的切主（抖动）。

PreVote 的做法：正式参选前先以**当前任期**发一轮"预拉票"（不 term+1、不落盘），只有**多数派**预投票通过（对方也联系不上 Leader、且候选人日志不落后）才进入正式选举。这样被隔离的少数派永远攒不起 term，恢复后安静地追日志即可。

【源码证据·Kafka 4.x】KRaft 直接把 PreVote 做进了角色状态机（KIP-853 两阶段选举）：

- `raft/.../raft/QuorumState.java → transitionToProspective（605）`：选举超时先进入 **Prospective**（预选）状态；621 行注释明确"**不需要持久化**，因为持久化的 electionState 没变"；
- `transitionToCandidate（638）`：预选拿到多数派票后，才 `epoch+1` 并 `durableTransitionTo` 落盘为 Candidate；
- 668 行注释："**只有 Prospective 允许转 Candidate**"；
- 兼容旧版本：`KafkaRaftClient.java → handleVoteResponse（953）`，960~967 行：PreVote 响应若返回 `UNSUPPORTED_VERSION`（对方是老版本、不认识 PreVote），立即降级走正式 Candidate 流程。

这套"Prospective → Candidate → Leader"是本文所有组件里把 PreVote 机制化最彻底的一个。

## 3.6 CheckQuorum：在职领导的"合法性巡检"

PreVote 管"上场前"，CheckQuorum 管"在职中"：Leader 周期性检查**最近一个选举超时内是否与多数派保持联系**（收到过它们的响应）；联系不上多数派 → 主动退位，避免变成"僵尸 Leader"（分区中的旧 Leader 若继续接受写入，只是这些写入永远无法提交，还会干扰客户端）。

【源码证据·etcd-io/raft】`raft.go → Config.CheckQuorum（224）/ Config.PreVote（228）`：两者都是库级开关；243 行注释强调一条硬约束——**`ReadOnlyLeaseBased`（Lease Read）必须开启 CheckQuorum**（Lease 的前提是 Leader 确认过多数派存活，见 5.4）。Kafka 侧的对应物是 Leader 的 `quorum-election-timeout` 内 majority check + Leader 主动退位逻辑（`LeaderState` 的存活集合跟踪）。

## 3.7 本章小结

| 问题 | 答案 |
|---|---|
| 谁有权写？ | 同一任期至多一个 Leader；只有 Leader 追加日志 |
| 怎么防双主？ | term 单调 + **一任期一票**（先到先得、先落盘） |
| 怎么防"旧日志当选"？ | 投票约束 3：候选日志 `(lastLogTerm, lastLogIndex)` 字典序不落后于自己 |
| 拉票拉不动怎么办？ | 随机化超时重试；PreVote 先探路，防止被隔离节点用虚高 term 骚扰集群 |
| Leader 失联怎么办？ | 心跳超时触发选举；CheckQuorum 巡检让失联 Leader 自觉退位 |

---

# 四、日志复制：让"一条日志"成为唯一真源

## 4.1 白话：一个写请求的一生

```
 客户端        Leader                          Follower（需要多数派）
   │ ①命令        │                                │
   │────────────►│ ②本地追加日志（必须先持久化）      │
   │             │ ③并行 AppendEntries ───────────►│ ④写日志、按需 fsync
   │             │ ◄───────────────────────────────│ ⑤返回 (matchIndex)
   │             │ ⑥多数派确认 → commitIndex 前移    │
   │             │ ⑦apply：按序执行命令到状态机       │
   │ ◄───────────│ ⑧响应成功                        │
   │             │ 下一轮心跳捎带 leaderCommit        │
   │             │   → Follower 补齐 commit/apply  │
```

要点：

- **②与④必须先落盘**（raft log 的持久化）再应答——"提交"的物理基础是多数派磁盘；
- ③是**并行**扇出、⑧只等**过半**（不等全部）——慢副本只影响自己被追平，不拖累整体（但有流控，见 4.6）；
- Follower 的提交是**被动**的：靠下一轮心跳里的 `leaderCommit` 得知"原来我已经提交到这了"，再应用到状态机；
- 客户端超时重试怎么办：命令可能已提交。**重试必须安全**——见 4.5。

## 4.2 AppendEntries 与一致性检查

AppendEntries（论文核心字段）：

| 字段 | 含义 |
|---|---|
| term / leaderId | 领导权凭证 |
| prevLogIndex / prevLogTerm | **紧邻本批日志之前**那条的下标与任期（"暗号"） |
| entries[] | 本批日志（可为空=心跳） |
| leaderCommit | Leader 当前 commitIndex（Follower 用来补提交） |

**一致性检查**：Follower 收到后校验"我位置 `prevLogIndex` 上的日志任期 == `prevLogTerm`"，不匹配 → 拒绝。Leader 收到拒绝就 `nextIndex--` 重发，直到暗号对上。这个朴素机制保证了一个重要性质——

**Log Matching（日志匹配性质）**：若两台机器在 `index=i` 上的日志任期相同，则从日志开头到 i 的**所有**内容都相同。证明思路是归纳：i 处相同 ⇒ 附着在上面的 prev 暗号合法 ⇒ i-1 处也相同，一路归纳到开头。**这是"Leader 日志即真理"的全部底气**：Leader 只要用 AppendEntries 从某个对齐点往后覆盖，就能把所有 Follower 的日志统一成自己的。

【源码证据·Kafka】KRaft 的复制不走 nextIndex 逐条探测，而是**沿用 Kafka 数据面的 Leader Epoch 机制**（KIP-595 的刻意设计）：Follower 在 FETCH 中携带 `(followerLeaderEpoch, fetchOffset)`，Leader 用 `ValidOffsetAndEpoch` 校验后，要么照发，要么直接告知"从某 epoch 的某 offset 截断重传"——一次往返对齐，替代逐条回退。相关代码：`raft/src/main/java/org/apache/kafka/raft/KafkaRaftClient.java → handleFetchRequest（1487）`、`RaftLog → endOffset()/waitForValidOffsetAndEpoch`（返回 `ValidOffsetAndEpoch`）。

## 4.3 日志修复：怎么把长歪的日志掰回来

- **论文做法**：Leader 为每个 Follower 维护 `nextIndex[i]`（下次发送的起点）与 `matchIndex[i]`（已确认复制到的位置）。Follower 拒绝后 `nextIndex--` 重试；优化：把拒绝响应里的冲突信息（conflict index / conflict term）捎回，**一次跳过整段**（例如"我比你短"→ 直接从 Follower 日志长度发；"那个位置是 term X 的条目"→ 跳过整个 term X）。
- **修复是"覆盖式"的**：Follower 上与 Leader 冲突的条目**直接删除**，换上 Leader 的。所以必须先证明被删的没提交过——这正是 3.4 节日志完整性投票约束 + 4.4 节提交规则合力的结果。

## 4.4 提交规则与 Figure 8：本节是全文最不能跳的一节

**提交规则**：`commitIndex = max(N)`，其中 N 满足：`N > commitIndex` 且 **多数派**的 `matchIndex ≥ N` 且 `log[N].term == currentTerm`。

前两个条件好懂（过半确认），第三个条件——**只直接提交当前任期条目**——是整篇论文最反直觉的一条，来自论文图 8（Figure 8）的反例：

| 时刻 | 发生的事 |
|---|---|
| t=2 | S1 当选，把 term2 的日志条目 e(2) 复制到 S2 后宕机（**未提交**，客户端未收到确认） |
| t=3 | S5 拉票（S3、S4、S5 投它），成为 term3 Leader，写入 e(3) 后宕机 |
| t=4 | S1 再次当选（term4），**继续把 e(2) 复制到了 S3**——此时 e(2) 已在 S1/S2/S3 三台上，构成多数派 |
| 危险 | 如果此时 S1 以"过半了"为由**提交并响应客户端**，随后宕机；S5 仍可能当选（term5）并用 e(3) **覆盖** e(2)——**已确认的写入消失**，违反安全性 |

结论：**"多数派持有" ≠ "已提交"**。旧任期的条目，多数派拿着也可能被新 Leader 覆盖（对方不知道它提交没提交）。唯一安全的做法：**Leader 只能通过"当前任期的新条目被过半复制"来推进提交点**——一旦 t=4 的 S1 写入一条 term4 的条目并过半，则它之前的**所有**条目（包括 e(2)）随之安全提交。这就是为什么很多实现给新 Leader 发一条 **no-op 空条目**：不为别的，就为尽快"过一次闸"，把前任的未决日志钉死。（论文 8 节：新 Leader 上任先提交 no-op，避免无关客户端请求被意外阻塞。）

【源码证据·Kafka】KRaft 把这条规则写成了显式的不等式：`raft/.../raft/LeaderState.java → updateHighWatermark（666~710）`——

- 69/140 行：Leader 记录自己的 `epochStartOffset`（当选时的日志末尾）；
- **680 行：`if (highWatermarkUpdateOffset > epochStartOffset)`**——高水位（= 提交点）只允许越过"当前任期起点"，即必须由**当前任期**新写入并被多数派复制的日志推动；多数派offset 若停在旧任期区间，水位纹丝不动；
- 683~699 行：高水位**只增不减**，回调 Listener 通知"又有一段可以安全暴露了"。

（KRaft 里"高水位 HW"就是论文的 commitIndex；Broker/消费者只暴露 HW 之前的内容，与数据面 ISR 语义同构。）

## 4.5 客户端重试与幂等：共识不管的半边天

客户端超时重发，命令可能已提交并执行 → **重复执行**。Raft 论文的标准答案：**客户端会话 + 去重表**——每个客户端一个 sessionId， Leader 为每个 session 维护"最近一条命令的编号与结果"，重试的命令直接返回缓存结果。注意两点：去重表本身要进快照（6.2）；Leader 更换后去重表随日志延续，才能跨任期去重。

工业实现两条路线：

1. **记录级幂等**（KRaft 的选择）：共识层不做会话，把幂等责任上移到**记录本身**——QuorumController 为每条元数据记录携带业务 key（如 topic id），回放天然幂等，重放日志不产生副作用差异。
2. **状态机自幂等**（etcd 的选择）：业务层用"请求 ID + 版本号"设计（如 lease 的 Grant/Revoke、事务 if 条件），即使重复应用，结果一致。

## 4.6 工程提速：批、流水线、流控

论文第 10 节明确给了三个正交优化，所有工业实现都做了：

- **批处理**：攒一批请求一个 AppendEntries；etcd-io/raft 的 `BatchAccumulator`（Kafka 侧同名类在 `raft/.../raft/internals/BatchAccumulator.java`，攒够条数或超时才切 Batch）；
- **流水线**：不等上一条 ACK 就发下一条，靠一致性检查兜底乱序；etcd 的 `tracker/Progress` 为每个 Follower 维护在途窗口与流控（发太快会被 Follower 拒绝并降速）；
- **读路径分离**：Leader 只写本地 + 复制，不阻塞在读上（读见第五章）。

存储侧还有两个常见招：**顺序追加写 + mmap**（RocketMQ DLedger 的 `DLedgerMmapFileStore`，`rocketmq/store/.../dledger/DLedgerCommitLog.java:109` 直接把 raft 日志映射进内存页）、**组提交**（多个请求合并一次 fsync）。

## 4.7 本章小结

- 写入流程 8 步：本地落盘 → 并行复制 → **过半确认即提交** → apply → 应答；Follower 的提交靠心跳捎带的 `leaderCommit` 被动补齐。
- **一致性检查（prevLogIndex/prevLogTerm）+ Log Matching** ⇒ Leader 的日志是全集群唯一模板，修复就是覆盖。
- **提交规则的三条件**中"仅当前任期"最反直觉也最关键（Figure 8）；Kafka 的 `highWatermarkUpdateOffset > epochStartOffset` 是它的代码化。
- 幂等是状态机/记录层的责任；批、流水线、流控是吞吐三板斧。

---
# 五、安全性与线性一致读

## 5.1 五大安全性质：Raft 的"永不"清单

| 性质 | 一句话含义 | 由什么保证 |
|---|---|---|
| **Election Safety** | 一个任期至多一个 Leader | 一任期一票 + 先落盘再回复（3.4） |
| **Leader Append-Only** | Leader 只追加、不删除不覆盖自己的日志 | 算法不变式（4 章） |
| **Log Matching** | 两副本同下标同任期 ⇒ 之前全部相同 | AppendEntries 一致性检查 + 归纳（4.2） |
| **Leader Completeness** | 已提交的日志条目，必然存在于所有未来 Leader 的日志中 | 投票约束 3（日志不落后才配当选）（3.4） |
| **State Machine Safety** | 每台机器只按**相同顺序** apply 相同日志；某机在 index i 应用了某条目，其他机在 i 不可能应用别的条目 | Leader Completeness + Log Matching + 提交规则（4.4） |

推理链：**投票约束 3** ⇒ 新 Leader 必然带着所有已提交条目上台 ⇒ 它上任后用一致性检查把大家的日志统一成自己的 ⇒ 旧任期的"未决条目"要么被覆盖（它们从未提交，覆盖无害）、要么被钉死提交（4.4 当前任期规则）⇒ 状态机安全。**安全性不需要任何时钟假设**；活性才需要。

## 5.2 一个致命细节：为什么"直接读"会读到旧数据

Raft 天生保证的是**写入全序**。但读请求通常不写日志（写一条"READ"进日志再等过半，性能不能接受）——于是 Leader 直接读自己的状态机回答。问题来了：

> 分区场景：S1 是 term5 的 Leader，但被隔离在少数侧（比如 3/5 集群中只剩 S1 和 S2 联系得上）。S1 自己不知道自己已经失联，状态机里是**旧数据**（新 Leader 上台后把 S3/S4/S5 的日志推进了）。客户端请求打到 S1，S1 满脸真诚地返回了**过期结果**——线性一致性被破坏（读到了"不存在过的现在"）。

结论：**读必须先确认"我此刻仍是多数派认可的 Leader"（或消息足够新）**。这就是 ReadIndex 与 Lease Read 的全部动机。注意这与 ZooKeeper 的取舍相同（读走本地、`sync()` 才线性一致），Raft 生态只是把"强读"做成了库选项。

## 5.3 解法一：ReadIndex（安全、默认推荐）

流程（etcd 称 `ReadOnlySafe`）：

1. Leader 把**当前 commitIndex** 记为 readIndex（readIndex 之前的数据保证已提交）；
2. **先用一轮心跳向多数派确认**自己仍是 Leader（清空在途的旧任期AppendEntries 的干扰）；
3. Leader **等待自己的状态机 applied ≥ readIndex**（提交 ≠ 已应用，2.2 节）；
4. 读状态机、返回。Follower 也可服务读：把 ReadIndex 请求转发给 Leader，拿到 readIndex 后等本地 applied 追平再读。

代价：一次心跳往返 + 等待 apply 追平，**不写日志、不过半提交**——比"读也走日志"便宜一个数量级。

【源码证据·etcd-io/raft】`raft.go → Config.ReadOnlyOption（58，ReadOnlySafe=63 / ReadOnlyLeaseBased=69）`；消息流：`stepLeader` 处理 `pb.MsgReadIndex`（1354 → `sendMsgReadIndexResponse` 1370，实现上把待确认的读请求挂在 `pendingReadIndexMessages`（430），借下一轮心跳的多数派 ACK 确认领导权），Follower 收到 MsgReadIndex 直接**转发给 Leader**（538~542 行注释）；确认后以 `MsgReadIndexResp`（1764/1771）下发 readIndex。etcd server 侧的消费循环：`server/etcdserver/read/read.go → linearizableReadLoop（109）`——收到请求 → 请求 ReadIndex → 等多数派 ACK → 等 `appliedIndex ≥ readIndex` → 读 MVCC 返回。

## 5.4 解法二：Lease Read（更快，但要赌时钟）

既然 3.6 的 CheckQuorum 保证 Leader 每个"选举超时"内都确认过多数派存活，那么 Leader 可以推断：**"距上次确认不足一个选举超时 ⇒ 不可能有新 Leader 当选"**——在这个租约窗口内直接读本地状态机，零网络往返。

- 【源码证据·etcd-io/raft】`raft.go:243`："CheckQuorum MUST be enabled if ReadOnlyOption is ReadOnlyLeaseBased"——库级强制配对，因为**租约的有效性完全建立在 CheckQuorum 的巡检上**。
- 代价：正确性依赖**各节点时钟漂移有界**（我方认为租约未过期、对方已经超时重选，就可能读旧）。 Raft 论文的安全论证不含时钟假设，Lease Read 是明确的"用假设换延迟"，跨机房、时钟源抖动的环境慎用。
- 对照：Consul KV 的 `default` 读模式就是"Leader 前置 + lease 保护"（Server 若在租约内未与多数派联系则拒绝服务，见《ACID-CAP-BASE.md》3.4 节 Consul 条目）；`consistent` 模式则走"到 Leader + verifyLeader 多数派确认"——【源码证据·hashicorp/raft】`raft.go → verifyLeader（966）`。

## 5.5 工业对照：各组件的读语义

| 组件/库 | 默认读 | 强一致读 | 说明 |
|---|---|---|---|
| etcd | **linearizable**（ReadIndex） | 同默认 | `serializable=true` 换本地读（不确认领导权，可能旧）；K8s 大量读走 serializable 提性能 |
| Consul | default（Leader+lease） | consistent（verifyLeader） | stale 模式=任意 Server 本地读 |
| TiKV | leader 本地读 | Follower Read（ReadIndex）/ leader ReadIndex | TiDB 层默认要求强读（8.3） |
| Kafka KRaft | Controller 读走 Raft 日志+HW | —— | Broker 对外读元数据镜像（MetadataCache），**弱一致容忍**：元数据传播延迟是明确的设计取舍（8.1） |
| ZooKeeper（对照） | 本地读（顺序一致） | `sync()` 后读 | 语义同 Raft 生态的 serializable vs linearizable |

## 5.6 本章小结

- 五大性质构成"永不"清单：**同任期单 Leader、已提交永不丢、日志可对齐、apply 全序一致**。
- **读不走日志 ⇒ 默认读不线性一致**。ReadIndex 用"一轮心跳确认 + 等 apply 追平"换安全；Lease Read 用"时钟漂移有界"的假设换零往返；etcd 用 `ReadOnlyOption` 把两种模式做成库选项并用配置强制 CheckQuorum 配对。
- 拿这套结论去审读任何组件的"强一致读"宣传词：看它是 ReadIndex、Lease 还是本地读，一眼见底。

---

# 六、工程化三大件：成员变更、快照、存储/网络工程

## 6.1 成员变更：集群不是铁板一块

### 6.1.1 为什么不能"一把换配置"

直接把 `C_old={S1,S2,S3}` 改成 `C_new={S3,S4,S5}`：各节点切换配置的时机不可能原子对齐。分区瞬间，S1/S2 按 C_old 选出 Leader A（多数派 2/3），S4/S5 按 C_new 选出 Leader B（多数派 2/3）——**两个多数派不相交，双主成立**。安全性的支点（任意两个多数派相交）被配置切换亲手拆掉了。

### 6.1.2 论文的两个方案

- **Joint Consensus（联合共识）**：两阶段过渡——先切到中间配置 `C_old,new`（新旧**两套都要过半**），再切到 C_new。数学上安全，但状态机复杂（三种配置状态、两阶段提交），**工业界几乎没人用**。
- **单步变更（Single-Server Changes）**：每次只增/删**一台**。数学上可证：|C| 与 |C±1| 的任意多数派必然相交，安全性天然成立。工业标准做法——etcd（`member add/remove`）、Consul（hashicorp/raft 的 `AddVoter/RemoveServer`）、TiKV（conf change v2）、Kafka 4.0（KIP-853）全部采用。

### 6.1.3 单步变更的工程约束

- **变更本身要走日志**：AddMember/RemoveMember 作为一条特殊日志提交，各节点 apply 到它时切换本地配置视图——变更与数据共用同一套多数派/提交机制，不引入新的共识；
- **一次只动一台，且必须等上一台生效**（新成员追平日志前不算有效多数派成员，避免"纸面多数派"）；连删两台可能一次性砍穿多数派；
- **约束变更是配置成员发起**：Candidate 状态禁止变更；新节点首次加入常以 **Learner**（只复制不投票）身份先追平日志再转正（etcd 3.4+、TiKV 均支持），防止日志严重落后的新节点拉低可用性；
- 删除 Leader 时 Leader 先退位（leadership transfer，hashicorp/raft `api.go → LeadershipTransfer（1309）` 提供显式 API）。

【源码证据】`hashicorp/raft/api.go → AddVoter（994）/ RemoveServer（1028）`：单步变更的直接 API；`raft-etcd/confchange/`：etcd 把配置变更算法拆成独立包（v2 支持 learner 与批量变更的严格校验）；Kafka 见 8.1.4 的 KIP-853。

## 6.2 快照与日志压缩

### 6.2.1 为什么必须做

日志只增不减：重启回放要重放全部历史（分钟级不可用）；磁盘被历史吃穿；Follower 落后几百万条日志，追平遥遥无期。解法：**把"已应用前缀"折叠成状态机镜像**，日志只留未应用部分。

### 6.2.2 快照里必须有什么（漏一样都致命）

1. **状态机镜像**（业务数据）；
2. **`lastIncludedIndex` / `lastIncludedTerm`**：快照覆盖到的日志位置——它是后续 AppendEntries 的对齐锚点，也是"快照点之前日志已提交"的凭证；
3. **当前集群配置**（成员变更走了日志，配置状态在日志里；快照丢配置 = 重启后不知自己属于哪个集群）；
4. **去重表/会话状态**（如果状态机有，4.5 节）。

### 6.2.3 InstallSnapshot：给落后太多的副本"发硬盘"

Follower 落后于 Leader 的快照点（`nextIndex < lastIncludedIndex`），逐条补日志已无意义 → Leader 直接传快照；Follower 校验（含 `lastIncludedTerm`）后**丢弃本地与之冲突的全部旧日志**、写入快照、加载状态机，从快照点继续跟随。此时要么本地日志与快照有重叠段（保留重叠），要么全丢（从头跟随）。

【源码证据】etcd：WAL 与快照**分目录**（`server/storage/wal/`（decoder/encoder/file_pipeline/repair.go…）与 `storage/snap/`）——预写日志保"重启前的未快照增量"，快照保"折叠后的全量"；Kafka KRaft：元数据快照由 `metadata/.../image/publisher/SnapshotEmitter.java / SnapshotGenerator.java / RaftSnapshotWriter.java` 生成，Follower 通过 FETCH 协议的 **snapshot fetch** 拉取（`metadata/.../util/SnapshotFileReader.java` 侧读取校验）；Nacos：`core/.../distributed/raft/JSnapshotOperation.java` 封装 SOFAJRaft 的快照钩子；TiKV：Region 切分时"子 Region 从父 Region 的当前状态出发"，配合 raft log 定期压缩，快照负担被 Multi-Raft 天然摊薄（8.3）。

## 6.3 存储与网络工程清单（实现库各自的做法）

| 维度 | 手段 | 典型实现 |
|---|---|---|
| 日志写 | 顺序追加 + 组提交 + mmap | DLedger `DLedgerMmapFileStore`；Kafka 分段日志 |
| 强制持久化点 | 每次 HardState(term/votedFor) 与日志 append 必须 fsync 才可应答 | etcd WAL；` votedFor 丢失 = 可能双投 = 双主` |
| 网络扇出 | Leader → 全体并行；Follower 只与 Leader 通信 | 所有实现 |
| 流控 | 每个 Follower 的在途窗口（Progress/tracker） | etcd-io/raft `tracker/`、raft-rs `tracker.rs` |
| 快照传输 | 分块 + 校验 + 断点续传（工程增强） | etcd、TiKV |
| 时钟 | tick 驱动（etcd 100ms tick）；选举超时随机化 | 见 3.3 |

## 6.4 本章小结

- **成员变更**：不要自己发明——单步变更（一次一台、走日志、Learner 过渡）是工业标准；Joint Consensus 是论文的备选答案。
- **快照**：四件套（镜像 + lastIncludedIndex/Term + 配置 + 去重表）缺一不可；InstallSnapshot 解决"落后太多"；各组件都把快照当作"重启 SLA"的杠杆。
- 存储与网络的每一招（mmap、组提交、流控）都在与同一敌人搏斗：**fsync 延迟决定共识延迟的下限**。

---

# 七、主流 Raft 实现库盘点

## 7.1 三种形态

```
① 算法库（嵌入你的进程，你自己接网络/磁盘）        ② 内置共识引擎（组件自研，黑盒）
   etcd-io/raft (Go)                               Kafka KRaft
   hashicorp/raft (Go, 较"一站式")                 ClickHouse Keeper
   tikv/raft-rs (Rust)                             RocketMQ DLedger
   SOFAJRaft/braft (Java/C++, 较"一站式")           etcd server / Consul server
              │                                              │
              └──────────── ③ 托管共识服务（独立部署，当中间件用）────────────┘
                     直接跑一个 etcd/ZK(对照) 集群，业务走它的 KV 接口
```

形态决定工作量：**算法库**给你状态机自由度（任何数据结构都能复制），但要自己写 WAL、网络、快照调度；**内置引擎**零成本但复制内容固定；**托管服务**连状态机都替你定死（KV），换来的是开箱即用 + 完整运维工具。

## 7.2 etcd-io/raft：算法库的天花板

设计立场：**只做算法，不碰网络与磁盘**——RPC、WAL、快照存储全部由调用方（etcd server、K8s 生态的无数嵌入用户、CockroachDB 早期、TiDB 的 PD 等）实现。换来的是可做**确定性模拟测试**（rafttest、`interaction_test.go`）：网络与磁盘都是可注入的假件，能在单进程里重放任意故障序列。

使用范式（伪代码骨架）：

```go
rc := raft.StartNode(config, peers)          // raft.go / node.go
for {
    select {
    case <-ticker.C:  rc.Tick()               // ① 推进逻辑时钟（心跳/选举超时）
    case rd := <-rc.Ready():                  // ② 取一批"待办"（Ready 结构）
        saveToWAL(rd.HardState, rd.Entries)   //    先持久化
        send(rd.Messages)                     //    再发网络消息（可异步）
        apply(rd.CommittedEntries)            //    应用已提交日志/快照
        rc.Advance()                          // ③ 汇报"处理完了"，进入下一轮
    case m := <-propCh: rc.Propose(ctx, m)    // ④ 业务写入
    }
}
```

【源码证据】`rawnode.go → RawNode/Ready`：一个 Ready 携带 `SoftState(角色) / HardState(term,votedFor,commit) / Entries / CommittedEntries / Messages / Snapshot / ReadStates`；**Ready 的字段顺序即最佳实践顺序**（先 WAL 后网络再 apply）。扩展点全部集中在 `raft.go → Config（224~244）`：`PreVote / CheckQuorum / ReadOnlyOption / MaxSizePerMsg(批大小) / MaxInflightMsgs(流控窗口)`。quorum 计算与联合配置独立成 `quorum/` 包（Joint quorum 支持），成员变更独立成 `confchange/` 包——这两块是后来者最容易写错的地方，etcd 直接抽成了可独立测试的库。

## 7.3 hashicorp/raft：一站式（Consul 的地基）

与 etcd-io/raft 相反：内置 TCP transport、日志/稳定存储接口（`LogStore/StableStore`）、快照存储（`file_snapshot.go`）、成员变更 API（`AddVoter/RemoveServer`）、Barrier（`api.go:907`，"之前写入已全部 apply"的同步原语）、LeadershipTransfer（1309）、verifyLeader（`raft.go:966`，强一致读的路由确认）。业务只需要实现 `FSM` 接口（`fsm.go`）——`Apply(log)` 与 `Snapshot()`。Consul 的 catalog/KV/ACL 全部跑在这一个 FSM 上（8.8）。适合"想快速拥有一个共识集群、不想碰底层"的 Go 项目。

## 7.4 raft-rs：Rust 移植与 TiKV 的批处理

`tikv/raft-rs`（raft.rs / raw_node.rs / storage.rs / read_only.rs）是 etcd/raft 的忠实移植，保留 Ready 事件模型与 tracker 流控。TiKV 在它之上做的是**调度工程**：成千上万个 Region 的 raft 组不能一组合一个线程，于是用 `BatchSystem/Poller` 框架把"每个 Region 的一次状态机推进"作为任务批量收割（架构全景与用户视角见 8.3）。

## 7.5 其余实现速览

| 库 | 语言 | 形态 | 备注 |
|---|---|---|---|
| SOFAJRaft | Java | 较一站式（内置 RPC） | 蚂蚁开源；Nacos 采用（8.6）；提供 RheaKV 分布式 KV 骨架 |
| braft | C++ | 较一站式（brpc） | 百度开源 |
| DLedger | Java | 库 + 存储 | OpenMessaging 项目；RocketMQ 采用（8.5） |
| nuRaft | C++ | 算法库 | eBay 开源；ClickHouse Keeper 采用（8.7） |
| CockroachDB 内置 | Go | 内置引擎 | Multi-Raft + quiescence 空闲静默优化 |

## 7.6 选型对比

| 维度 | etcd-io/raft | hashicorp/raft | raft-rs | SOFAJRaft |
|---|---|---|---|---|
| 语言 | Go | Go | Rust | Java |
| 网络/磁盘 | **调用方负责** | 内置（可替换接口） | 调用方负责 | 内置可插拔 |
| PreVote/CheckQuorum | 均支持（Config 开关） | 支持（LeaderLeaseTimeout 等） | 均支持 | 支持 |
| 成员变更 | confchange v2（单步 + learner） | 单步（AddVoter/RemoveServer） | confchange 移植 | 单步 |
| 快照 | 调用方实现 | file snapshot 内置 | 调用方实现 | 内置快照钩子 |
| 读优化 | ReadIndex/Lease（ReadOnlyOption） | verifyLeader/lease | ReadIndex（read_only.rs） | ReadIndex |
| 典型用户 | etcd、PD、无数嵌入 | Consul、Nomad | TiKV | Nacos 等 |

选型一句话：**Go 造轮子选 etcd-io/raft（可控性最强），Go 求快选 hashicorp/raft，Rust 选 raft-rs，Java 选 SOFAJRaft/DLedger；不想写任何共识代码就直接用 etcd 托管（形态③）。** 永远不要手写 Raft：算法库的价值一半在实现，一半在它被 Jepsen/模拟测试磨过的历史。

## 7.7 本章小结

- 三种形态对应三种自由度：算法库（自由度最高）→ 内置引擎（零成本）→ 托管服务（零代码）。
- etcd-io/raft 的 **Ready 事件模型**是所有现代实现的母版：状态机推进与 IO 解耦，字段顺序即最佳实践；确定性模拟测试是它的隐形护城河。
- hashicorp/raft 证明"一站式"路线可行（Consul 用它扛了十年）；raft-rs 证明算法库可以跨语言移植并叠加调度层工程。

---
# 八、组件实战：它们用 Raft 解决什么问题（本章是全文重心）

## 8.0 总览表：先看全貌再逐个拆

| 组件 | Raft 复制什么（谁是那条日志） | Leader 是谁 | 解决的核心问题 | 与标准 Raft 的关系 |
|---|---|---|---|---|
| Kafka KRaft | `__cluster_metadata` 单分区元数据日志 | 主 Controller | 去 ZooKeeper：元数据自管理 | 自研，差异显式（KIP-595/853） |
| etcd | 全部 KV 写入的 MVCC 提交日志 | etcd leader | K8s 的"永不撒谎的 KV" | etcd-io/raft，最标准 |
| TiKV | 每个 Region 一条 raft log | Region leader | 把强一致做到 PB 级可扩展 | raft-rs，Multi-Raft |
| Redis Sentinel/Cluster | **无**（只选举，不复制日志） | ——（failover 协调者） | 故障自动切换 | 只借选举思想 |
| RocketMQ DLedger | CommitLog 本身 | DLedger leader | 主从切换自动化 + 多数派不丢 | DLedger 库 |
| RocketMQ Controller | Controller 元数据 | Controller leader | 普通主从架构的自动切主编排 | DLedger 库 |
| Nacos | 配置变更日志（embedded 存储） | 配置集群 leader | 配置分发的强一致 | SOFAJRaft |
| ClickHouse Keeper | 协调元数据（类 znode 操作） | Keeper leader | 替代 ZK，去掉 JVM | nuRaft |
| Consul | catalog/KV/ACL 的日志 | Consul server leader | 服务发现数据的强一致 | hashicorp/raft |

---

## 8.1 Kafka KRaft：让元数据自己管自己（KIP-500 的完整答案）

### 8.1.1 ZooKeeper 时代的问题

Kafka 0.8~2.x 把"集群元数据 + 控制器选举"外包给 ZooKeeper，带来了四类长期病灶：

1. **元数据双源**：真源在 ZK，但生效要靠 Controller 监听 znode、再全量/增量同步给 Broker——两个系统、两条传播路径，一致性窗口与排查成本都翻倍；
2. **Controller 故障切换慢且脆**：Controller 挂掉后，新 Controller 要重新 watch 全部 znode、重新拉取全量元数据，大集群（百万分区）分钟级；期间集群"群龙无首"；
3. **两套心智模型**：Kafka 的日志/ISR/epoch 一套，ZAB/znode/watch 一套，运维要同时精通；
4. **规模天花板**：百万分区的 watch 通知风暴与 ZK 写吞吐，成为 Kafka 扩容的硬瓶颈。

KIP-500（2019）的判断：**Kafka 已经有全世界最成熟的日志复制工程（数据面），为什么元数据要外包给别人？**——把元数据也变成一条 Kafka 式的日志，用 Raft 复制，问题清单瞬间清空。

### 8.1.2 核心思想：元数据即日志（metadata as log）

```
              ┌──────────────── 控制面（Control Plane）────────────────┐
              │  Controller Quorum（3~5 台 voter，Raft 复制组）          │
              │   ┌─────────────────────────────────────────────┐     │
              │   │ __cluster_metadata 单分区 Raft 日志           │     │
              │   │ [RegisterBroker][CreateTopic][PartitionChange]│     │
              │   │ [ConfigRecord][RemoveTopic]...               │     │
              │   └─────────────────────────────────────────────┘     │
              │   Leader = 主 Controller（执行全部元数据变更）            │
              │   Follower = 备 Controller（回放日志，随时接班）          │
              └───────────────▲───────────────────────┬───────────────┘
               VOTE/FETCH      │ 复制（voter 之间）      │ FETCH（observer 只拉不投）
               （投票/复制）     │                       ▼
              ┌────────────────┴───────────────────────────────────────┐
              │  Broker 集群（全部是 observer：拉取元数据日志）             │
              │  每台 Broker 回放 metadata log → 内存 MetadataCache/镜像   │
              │  数据面：Topic 分区副本仍是 ISR 复制（与 Raft 无关！）       │
              └────────────────────────────────────────────────────────┘
```

- **Raft 组 = Controller 集群**：voter 就是配置里的 Controller 节点（`controller.quorum.voters`，3.x 静态指定；4.0 起可动态变更，见 8.1.4）；
- **日志 = `__cluster_metadata`**：一个内部单分区 topic，承载全部元数据**变更记录**（不是全量快照，是 oplog）；
- **状态机 = 内存元数据镜像**：每个 Controller/Broker 回放日志构建 `MetadataImage/MetadataCache`——这就是 2.2 节"日志是唯一真源、状态机是确定性投影"的教科书落地；
- **Broker = observer**：只 FETCH 日志维护镜像，不投票不竞选——Raft 的 observer 角色被用来表达"复制但不决策"的从属关系；
- **主 Controller = Raft Leader**：Controller 选举与元数据复制**合并成同一件事**——选主即 Controller 切换，切换后新主回放日志即可接管，**故障恢复时间从 ZK 时代的分钟级降到秒级**，且元数据真源只有一个（那条日志）。

### 8.1.3 源码走读（基线 kafka 4.5.0-SNAPSHOT）

**① 角色状态机**：`raft/src/main/java/org/apache/kafka/raft/QuorumState.java`——

- 五种持久态：`Follower / Candidate / Leader / Unattached（知道更高任期但还没收到日志）/ Resigned（前 Leader 退位过渡）`；4.x 起多出 `Prospective`（3.5 节的两阶段选举）；
- 迁移方法带持久化语义：`durableTransitionTo（729）` 先写 quorum-state 文件（`FileQuorumStateStore`：term/votedFor/voterSet），`memoryTransitionTo` 只动内存——**"先落盘再行动"**（3.4 节投票规则）在代码层的直接体现；
- 单 voter 短路：`KafkaRaftClient.java:569~575`——只有一个 voter 时启动直接 Prospective→Candidate→Leader 三连。

**② 协议消息面**：`KafkaRaftClient.java:2825~2832` 的分发表即协议清单——

| 消息 | 对应 Raft 原语 | 作用 |
|---|---|---|
| `VOTE` | RequestVote | 选举（含 PreVote 变体，854 行 `preVote` 标志） |
| `FETCH` | AppendEntries（反向：pull） | 日志复制与心跳（4.1 的"推"改成"拉"） |
| `DESCRIBE_QUORUM` | ——（KIP-596） | 观测：各 voter 的水位/日志进度（运维 API） |
| `ADD_RAFT_VOTER` / `REMOVE_RAFT_VOTER` / `UPDATE_RAFT_VOTER` | 成员变更（KIP-853） | voter 在线增删改 |

**③ 提交规则**：`raft/.../raft/LeaderState.java:680`——`highWatermarkUpdateOffset > epochStartOffset` 才推进高水位（4.4 节 Figure 8 规则的代码化；新 Leader 上台后水位要先被"当前任期"的新日志推过起点，前任的未决日志才会被钉死提交）。

**④ 复制走 Kafka 网络与存储**：`core/src/main/scala/kafka/raft/KafkaRaftManager.scala` 把 raft 组伪装成一个普通 Kafka 分区——日志就是分段日志文件（页缓存、顺序写全套复用），消息就是 Kafka RecordBatch（BatchAccumulator 攒批，`internals/BatchAccumulator.java`）。

**⑤ 控制器事件机**：`metadata/src/main/java/org/apache/kafka/controller/QuorumController.java`——主 Controller 把"raft 提交某条元数据记录"转成事件执行（建 topic、改副本、重分配……），产出**下一条待写入日志的记录**或对 Broker 的广播结果；它本身完全单线程事件驱动——**Leader 写日志，日志回放驱动一切**。

### 8.1.4 与标准 Raft 的差异清单（面试高频）

| 维度 | 标准 Raft（论文） | Kafka KRaft |
|---|---|---|
| 复制方向 | Leader 推（AppendEntries） | Follower 拉（FETCH 复用数据面网络栈；KIP-595） |
| 日志对齐 | nextIndex 逐条探测/回退 | Leader Epoch + offset 一次对齐（4.3 节） |
| 选举 | term+1 直接竞选 | **Prospective（预选，不+1）→ Candidate（+1 落盘）→ Leader** 两阶段；旧版本自动降级（960~967 行 UNSUPPORTED_VERSION 回退） |
| 成员变更 | 论文方案/单步 | **KIP-853（4.0）**：AddVoter/RemoveVoter/UpdateVoter；变更记录作为**控制记录写入日志本身**（`internals/KRaftControlRecordStateMachine.java`），voter 集合成为日志状态的一部分；引入 directory id 解决节点 id 复用场景 |
| 去重 | 客户端会话 + 去重表 | 无会话；元数据记录自带业务 key，**回放幂等**（4.5 路线 1） |
| 快照 | InstallSnapshot | 元数据快照（SnapshotEmitter/Generator），FETCH 支持 snapshot 模式拉取 |
| 被复制的对象 | 通用命令 | 专用于元数据；**消息数据面是 ISR 不是 Raft** |

### 8.1.5 效果与边界

- 效果：Kafka 4.0 起单二进制、无 ZK；Controller 切换秒级；元数据真源唯一；`DESCRIBE_QUORUM` 给了运维直接观测 Raft 内部的窗口；
- 边界 1：**数据面可靠性模型没变**——`acks=all + min.insync.replicas` 的 ISR 三件套照旧（KRaft 不解决消息不丢，见《ACID-CAP-BASE.md》3.4 Kafka 条目）；
- 边界 2：元数据传播是异步拉取，Broker 看到的元数据镜像**弱一致**——Kafka 明确接受这一点（元数据最终一致 + epoch 校验兜底），所以"KRaft=强一致元数据读"这种说法同样要打折扣；
- 边界 3：单分区 Raft 组意味着控制面吞吐封顶在"单分区写"量级——但元数据变更本就低频，这是**正确的量级选择**（与 8.3 的 Multi-Raft 相反方向）。

### 8.1.6 小结

KRaft 的本质是：**用一条 Raft 日志替代 ZooKeeper 的"存储 + 通知 + 选举"三件套**，把 Kafka 变成"一套日志体系管天下"的系统。它对标准 Raft 的每处偏离（拉模式、epoch 对齐、两阶段选举、动态 quorum）都有 KIP 编号与理由，是"如何正确地魔改 Raft"的最佳范文。

---

## 8.2 etcd：Kubernetes 的大脑，标准 Raft 的样板间

### 8.2.1 它的问题：一个"从不撒谎的 KV"

Kubernetes 把全部 API 对象（Pod/Node/Secret/…）放进去，且到处依赖"读到即生效"：调度器、控制器全部基于 watch 增量决策。一旦存储说谎（读到旧值/丢写入），控制循环就会做出错误动作。所以 etcd 必须提供：**线性一致写（默认）+ 线性一致读（默认）+ 全序 watch + 租约**。Raft 是地基，MVCC/watch/lease 是在"线性一致 KV"之上叠的服务（呼应《ACID-CAP-BASE.md》3.4 etcd 条目）。

### 8.2.2 分层：算法库与 server 的边界

```
etcd server（D:\code\3rd\etcd）                    etcd-io/raft（D:\code\3rd\raft-etcd）
┌────────────────────────────────────┐
│ API 层 gRPC（KV/Watch/Lease/Auth…） │
│ etcdserver：请求仲裁、读循环          │   ┌──────────────────────────┐
│   read/read.go → linearizableReadLoop│  │ raft.go：角色机/消息处理    │
│ MVCC：revision → 多版本树 + watcher  │◄─┤ rawnode.go：Ready 事件     │
│ raftNode：驱动 raft 库（tick/ready）  │  │ confchange/quorum/tracker │
│ WAL：预写日志（storage/wal/）         │  └──────────────────────────┘
│ snap：快照（storage/snap/）           │   纯算法：无 IO、无网络
└────────────────────────────────────┘
```

- **写入路径**：client → raftPropose 进日志 → 多数派提交 → apply 层执行到 MVCC（bump 全局 revision）→ 响应。revision 与 raft index 同源单调——**watch 的全序保证就是 raft 日志的全序**；
- **读取路径**：默认线性一致（`read/read.go:109 linearizableReadLoop`：ReadIndex + 等 applied 追平，5.3 节）；`serializable` 读直接查本地树（K8s 对部分读路径用它换性能）；
- **tick 配置**：`server/embed/etcd.go → TickMs/ElectionTicks`——默认心跳 100ms、选举超时 10 tick = 1s（3.3 节）。

### 8.2.3 Kubernetes 怎么用 etcd

- **apiserver 是唯一写入者**：所有组件（scheduler/controller/kubelet 汇报）都不直连 etcd，全部经 apiserver 串行化——etcd 面对的是"单客户端多路复用"，规避了大量并发语义问题；
- **resourceVersion = revision**：乐观并发（对象的 resourceVersion 做 Compare-And-Swap 条件）+ watch 从任意历史 revision 回放——**把 raft 日志的全序直接暴露成编程模型**；
- **Node 存活 = Lease**：kubelet 每 10s KeepAlive 一个 etcd lease，lease 过期 → Node Ready 条件被置 NotReady → Pod 驱逐——**租约的权威性来自 Raft 复制的 lease 记录**（谁持有、何时过期，都是线性一致的）；
- **选主/锁的"用户侧共识"**：etcd 的 `concurrency` 包（Election/Mutex）展示了一个范本——**在 Raft 保证的线性一致 KV 之上，用事务（if + revision）实现业务共识原语**。你的系统若有类似需求，抄它的作业而不是自己发明。

### 8.2.4 运维与边界

- 数据规模硬顶：默认 2GB 配额（建议 ≤8GB）、单 value 1.5MB——**它是协调元数据存储，不是数据库**；
- 成员运维 = 单步变更的标准动作：`etcdctl member add/remove`（新成员先 Learner 追日志，6.1.3）；
- K8s 大集群的经典调优：compaction 频率、watch 事件风暴、quota 告警——都是"raft 日志无限增长"在 MVCC 上的投影（6.2）。

### 8.2.5 小结

etcd 是**距离论文最近的工业实现**（etcd-io/raft 的注释里直接引用论文小节），也是"把共识层与业务层切开"的模范：Raft 管日志序，MVCC 管版本语义，lease 管活性，watch 管推送。看懂 etcd，等于看懂了"如何用 Raft 正确地造一个 KV"。

---

## 8.3 TiKV / TiDB：Multi-Raft，把 Raft 从"单点共识"变成"可扩展架构"

### 8.3.1 单 Raft 组的天花板

etcd/KRaft 式的单 Raft 组有两个天花板：**容量**（全部数据一个日志，无法水平扩展）与**吞吐**（写入串行过单 Leader + 多数派）。TiKV 的回答：**把数据按 key range 切成海量 Region（默认百 MiB 量级，96~256MiB 随版本调整），每个 Region 一个独立 Raft 组**——组与组之间完全独立选举、独立复制、互不阻塞。

```
   TiDB Server（SQL 层，无状态，可水平扩）
        │
   PD（Placement Driver：全局调度/元数据中心——它自己就是一个 etcd/Raft 集群！）
        │  Region→peer 路由、leader 均衡、split/merge、副本放置
        ▼
 ┌─ TiKV Node 1 ─┐   ┌─ TiKV Node 2 ─┐   ┌─ TiKV Node 3 ─┐
 │ R1(leader)     │   │ R1(follower)  │   │ R1(follower)  │
 │ R2(follower)   │   │ R2(leader)    │   │ R2(follower)  │
 │ R3(follower)   │   │ R3(follower)  │   │ R3(leader)    │
 │ …十万级 Region │   │               │   │               │
 └────────────────┘   └───────────────┘   └───────────────┘
   每个 Region = [startKey, endKey) 的一片数据 + 独立 raft 组（3/5 副本）
```

注意这个架构里 Raft 出现了三次：**Region 组**（数据）、**PD 的 etcd**（调度元数据）、（TiFlash 见下）——"共识套共识"是分布式数据库的常态。

### 8.3.2 raft-rs 与调度工程

- 算法层：`D:\code\3rd\raft-rs`（raft.rs/raw_node.rs/read_only.rs），Ready 模型与 etcd 同源；
- 调度层：单机十万 Region 不能一组合一个线程，TiKV 用 BatchSystem/Poller 把各 Region 的"一次 tick/一次 Ready 消化"批量收割执行；
- **Region 生命周期与 Raft 的互动**：
  - **split**：Region 过大 → 父组"写一条分裂记录进日志 → 过半提交 → 两个子组以父组当前状态为起点各自开张"——**切分本身也走 Raft，保证任何副本看到的切分点一致**；
  - **merge**：两组合一，两阶段（PrepareMerge/CommitMerge）+ PD 协调，难度远高于 split；
  - **副本增删**：conf change v2（单步 + learner），新副本先以 learner 身份追日志；
  - **leader transfer**：负载均衡靠"把 Region leader 迁走"而不是搬数据（raft 的 TransferLeader 扩展）；
- **请求路由与 epoch 校验**：客户端带 Region epoch（version/confVersion）请求任意副本；副本非 leader 回 `NotLeader` 告知新 leader；epoch 过期回 `StaleEpoch` 要求重新路由——**"元数据可以旧，但校验必须严"**（与 KRaft 的弱一致镜像同一哲学）。

### 8.3.3 读与 learner

- **Follower Read**：TiDB 的强一致读默认打 leader；热点场景允许 follower 用 **ReadIndex**（5.3）服务——不写日志、确认领导权后等本地 applied 追平；
- **TiFlash = Raft Learner 的工业级应用**：列存分析副本以 learner 身份异步追 Region 日志，**不参与投票**——用 6.1.3 的 learner 机制给"分析流量"开了不拖累事务的旁路；
- **事务**：TiDB 的分布式事务（Percolator 系）在"单 Region 线性一致"之上用 2PC 拼跨 Region 原子性（详见《ACID-CAP-BASE.md》2.6.3）——**Raft 保证每个分片的正确，2PC 负责把分片粘起来**。

### 8.3.4 小结

Multi-Raft 的本质：**共识的"正确性"不变，把"共识的粒度"从集群降到数据分片**。代价是调度复杂度（PD、split/merge、路由 epoch），收益是容量与吞吐的近线性扩展。CockroachDB 同构（Range + Multi-Raft + 空闲静默 quiescence）。读 TiKV/TiDB 材料时，把"Region""Range""Shard"对上号即可。

---

## 8.4 Redis：借了 Raft 的"选举"，没借"日志"（澄清章）

### 8.4.1 先把流行说法掰正

"Redis 用了 Raft"这个说法流传极广，**但 Redis（无论 Sentinel 还是 Cluster）从未实现 Raft**。准确的表述是：

- **数据面**：Redis 主从复制是**异步**的（master 写完即答，slave 背后追 repllog），定位是 AP/最终一致（`WAIT` 命令可要求指定副本数确认，属可选增强）——**没有多数派提交这回事**；
- **控制面**：Sentinel 的 leader 选举、Cluster 的 failover 选举，借用了 Raft 的三件思想武器——**单调递增的 epoch（term）、一任期一票（先到先得）、多数派授权**。但被选举的对象只是"故障切换的协调者"，**没有任何日志被这条机制复制**。

Redis 官方文档对 Sentinel 的描述也是"选举方式与 Raft 相似"（而非"实现了 Raft"）。有趣的是 Sentinel（2012）早于 Raft 论文（2014）发表——两者是同一思想的独立收敛（对比 3.2 节：ZAB 的 epoch、Kafka 的 epoch 同理）。

### 8.4.2 Sentinel 的"Raft 式"选主（源码走读，redis 8.10.2）

场景：master 客观下线（ODOWN：≥`quorum` 个 Sentinel 判主观下线），需要推一个 Sentinel 当 leader 执行 failover。

1. **拉票**：候选 Sentinel 把自己的 epoch+1，向其他 Sentinel 发送 `SENTINEL is-master-down-by-addr <master> <epoch> * <runid>`（带自己 runid 即拉票，`sentinel.c:3963` 命令处理、4734 `sentinelAskMasterStateToOtherSentinels`）；
2. **投票**：`sentinel.c → sentinelVoteLeader（4792）`——
   ```c
   if (req_epoch > sentinel.current_epoch)      // 采纳更高 epoch
       sentinel.current_epoch = req_epoch;
   if (master->leader_epoch < req_epoch &&
       sentinel.current_epoch <= req_epoch) {   // 本 epoch 尚未投过票
       master->leader = req_runid;              // 一任期一票、先到先得
       master->leader_epoch = sentinel.current_epoch;
       sentinelFlushConfig();                   // 先落盘再回复（同 Raft！）
   }
   ```
3. **计票**：`sentinelGetLeader（4848）` 统计各候选得票，**超过 Sentinel 集群多数派**者当选（4.1 节的"过半"）；没过半 → 随机延迟后下一轮；
4. 当选者执行 failover：`SLAVEOF NO ONE` 提升从库、通知其余 Sentinel/客户端。

**与 Raft 逐项对比**：

| 机制 | Raft | Sentinel |
|---|---|---|
| 任期 | term | epoch（+1 后竞选，同款） |
| 一任期一票 | votedFor 持久化 | `leader_epoch` 持久化（同款） |
| 多数派 | 必需（写与选） | **只用于选 leader**，无日志复制 |
| 日志完整性约束 | 候选日志不得落后 | **无**（无日志可约束；当选者是"协调者"不是"数据 leader"） |
| 复制 | AppendEntries | **无** |
| 失败重试 | 随机超时 | 随机 failover-start 延迟（同款防活锁） |

### 8.4.3 Redis Cluster 的 failover 选举（源码走读）

Cluster 里从库提升为主，也要"竞选 + 投票"，代码在 `src/cluster_legacy.c`：

1. 从库发现 master FAIL → `clusterHandleSlaveFailover（4387）`：等待一个**随机化的选举延迟**（rank 越靠前（复制越新）延迟越短——** implicitly 偏向日志最新的从库**，是对 Raft"日志完整性约束"的松散模仿）；
2. 拉票：`clusterRequestFailoverAuth（4102）`广播 FAILOVER_AUTH_REQUEST（带 currentEpoch）；
3. 主库投票：`clusterSendFailoverAuthIfNeeded（4137）`的条件清单（源码逐行可读）：
   - 投票者必须是**至少负责一个 slot 的 master**（"cluster 的多数派 = 有 slot 的 master 数"）；
   - `requestCurrentEpoch >= 我 currentEpoch`；本 epoch **未投过票**（`lastVoteEpoch` 检查，同款一任期一票）；
   - 请求者是**其 master 已 FAIL 的从库**（或 FORCEACK 手工切换）；
4. 拿到**多数 master** 授权 → 从库自增 configEpoch 当选，广播 PONG 宣示 slot 归属。

**与 Raft 的本质差异**：当选后**没有日志对齐**——新主以自己本地数据为准提供服务，可能落后于旧主已确认的写入（异步复制的固有代价，Cluster 用 `CLUSTER FAILOVER` 手工模式提供"先追平再切换"的受控选项）。

### 8.4.4 为什么 Redis 不做完整 Raft？

三点设计判断（也解释了"什么时候不要用 Raft"）：

1. **延迟哲学冲突**：Redis 单线程、亚毫秒响应是其生命线；Raft 要求**每次写多数派同步落盘**，延迟与抖动都不可接受；
2. **定位是缓存/热数据**：丢几条最新写入可接受（回源重建），为此付出全量同步刷盘的代价不划算——这是 AP 定位的自觉（呼应《ACID-CAP-BASE.md》3.5 Redis 条目）；
3. **选主≠共识**：Redis 需要的只是"**别出现两个协调者**"（选举一致性），不需要"**对数据顺序达成一致**"（日志共识）——只买半个算法，付半个价钱，这是聪明的取舍而非缺憾。

真正需要"强一致 Redis 语义"的场景，答案是上 Raft 系 KV（etcd/TiKV）或社区 Raft 版 Redis 实现（如 redis-on-raft 一类实验项目），而不是改造 Redis。

### 8.4.5 小结

Redis 是**"Raft 思想 ≠ Raft 实现"**的最佳标本：epoch + 一任期一票 + 多数派授权 + 随机延迟，四个零件原样照搬；日志复制、完整性约束、快照——一个都没拿。读懂本节，你就能一眼分辨市面上"我们用了 Raft"的宣传里，到底用了几个零件。

---

## 8.5 RocketMQ DLedger：把 CommitLog 变成 Raft 日志

### 8.5.1 问题：主从架构的"不丢"与"切换"

RocketMQ 4.5 之前：Master/Slave 异步拉取复制。"不丢"要靠 `SYNC_MASTER + 同步刷盘` 两台全同步，"切换"要人工/脚本介入。DLedger（OpenMessaging 项目，RocketMQ 4.5 内置，5.x 仍是正式选项）用 Raft 一次解决两件事：**多数派复制（2f+1 容 f，不丢已确认消息）+ 自动选主（秒级切换）**。

### 8.5.2 实现： raft 日志 = CommitLog 本体

- `store/src/main/java/org/apache/rocketmq/store/dledger/DLedgerCommitLog.java:65`：`class DLedgerCommitLog extends CommitLog`——**把消息存储引擎的根类替换成 Raft 日志**。生产端消息 = raft 条目：leader 追加 → 多数派同步刷盘 → 高水位前的消息才对消费可见（consumeQueue 按 raft log offset 构建）——**"已提交才可见"与 Kafka 的 HW 语义同构**；
- 存储：`DLedgerMmapFileStore`（109 行）——mmap 顺序追加，4.6 节的存储三板斧；
- 选举与复制：DLedger 内置（投票/term/mmap 日志/Fetch 修复），角色上 broker 不再有主从概念——**broker 组即 raft 组**。

### 8.5.3 RocketMQ 5.x Controller：Raft 复制"切换编排元数据"

5.x 引入 Controller 模式，让**普通主从架构**也获得自动切换。其中内嵌部署形态又用了一次 Raft：`controller/src/main/java/org/apache/rocketmq/controller/impl/DLedgerController.java` + `DLedgerControllerStateMachine.java` + `manager/RaftReplicasInfoManager.java`——Controller 集群用 DLedger 复制"谁是谁的主从、谁该切换"的编排状态机，Broker 心跳写入这条 raft 日志，多数派确认后编排决策生效。

**同一个库在同一个系统里复制两种完全不同的东西**（消息 CommitLog / 编排元数据）——恰好证明 2.2 节的论断：Raft 复制的是"任意命令日志"，状态机是什么由业务决定。

### 8.5.4 与 Kafka 的对比（面试高频）

| 维度 | Kafka（ISR + min.insync.replicas） | RocketMQ（DLedger 模式） |
|---|---|---|
| 一致性协议 | 主从 + ISR 动态伸缩（**不是 Raft**） | 标准 Raft 多数派 |
| 提交判定 | acks=all 且 ISR≥min.insync | 多数派（2f+1 容 f，语义更"硬"） |
| 可用性 | ISR 缩水即拒绝写（NotEnoughReplicas） | f 台宕机仍可写（多数派在即可） |
| 切主 | 需 Controller（KRaft 后也是 raft 选 controller）+ epoch 机制 | Raft 自动选主 |
| 吞吐 | 更高（ISR 可仅 1 台全同步） | 略低（永远多数派刷盘） |

有趣的历史回环：**RocketMQ 4.5（2019）先在消息队列里用了 Raft，Kafka 的 KRaft（控制面）2019 立项 2022 生产**——但 Kafka 至今没有把数据面换成 Raft，而是继续押注 ISR 的弹性（吞吐优先）；RocketMQ 则同时保留 DLedger（CP 选项）与普通主从（吞吐选项）。两家的取舍差异比"谁先进"更有信息量。

### 8.5.5 小结

DLedger 展示了 Raft 的"硬核用法"：**把业务日志直接当共识日志**（零中间层、零转换），适合金融级"宁可慢不可丢"。Controller 模式则展示同一机制的轻量用法：只复制编排元数据。一个组件两种用法，说明选 Raft 的"深度"是可以分档调节的。

---

## 8.6 Nacos：JRaft 守护配置中心的 CP 半边天

### 8.6.1 一个组件，两种一致性（AP/CP 双协议共存的教科书）

Nacos 同时提供服务发现与配置中心，两者对一致性的要求截然不同，于是采用**双协议分治**（呼应《ACID-CAP-BASE.md》3.5 Nacos 条目）：

- **服务发现（naming）**：临时实例注册敏感、可容忍短暂脏读 → **Distro 协议**（AP：无中心写入 + 异步复制 + 定期校验收敛）。源码：`naming/src/main/java/com/alibaba/nacos/naming/consistency/ephemeral/distro/v2/`；
- **配置中心（config）**：客户端必须按**一致的顺序**看到配置变更（否则灰度发布/回滚在部分机器上乱序生效，事故）→ **JRaft（SOFAJRaft）**，CP。

### 8.6.2 JRaft 落地（nacos 3.3.0-RC）

- 协议封装：`core/src/main/java/com/alibaba/nacos/core/distributed/raft/`——`JRaftServer/JRaftProtocol` 启动与封装 raft Node；**读写全部进共识**：`processor/NacosReadRequestProcessor / NacosWriteRequestProcessor`（读走 ReadIndex，写走日志）；
- 状态机：`NacosStateMachine.java → onApply（95）`——从 raft 迭代器逐条取业务消息，应用到 embedded 存储；
- 存储分层：`config/.../service/repository/embedded/EmbeddedConfigInfoPersistServiceImpl`（内嵌 derby + raft 复制，小集群自足）与 `extrnal/ExternalConfigInfoPersistServiceImpl`（外置 MySQL，单写即可）二选一——**embedded 模式下 raft 日志是配置数据的真源**，快照由 `JSnapshotOperation` 承接（6.2.3）；
- 运维：`JRaftMaintainService` 暴露重置/状态查询，对应 7.5 节 SOFAJRaft 的 maintain API。

### 8.6.3 小结

Nacos 的价值在于回答了一个高频架构问题：**同一产品里 AP 与 CP 如何共存**——按数据的风险等级切协议，而不是给全系统选一个"万能一致性"。配置变更低频但顺序敏感 → Raft；注册心跳高频且可收敛 → Distro。选协议前先给数据分"风险等级"，这个方法论比 JRaft 本身更值得带走。

---

## 8.7 ClickHouse Keeper：用 C++ 重写一个 ZooKeeper

### 8.7.1 问题

ClickHouse 的 `ReplicatedMergeTree` 系列表依赖 ZK 协调：**insert 去重**（同一 block hash 防重复写入）、**merge 任务分配**（哪台后台合并哪个 part）、**副本修复**、**DDL 队列**。但 ZK 的 JVM 运维包袱、watch 语义、版本矩阵让 ClickHouse 团队选择自研替代（此决策与 KIP-500 逻辑一致：**协调服务与其外包，不如内化成自己栈内的一块**）。

### 8.7.2 方案（本节未做本地源码核对，按官方资料描述）

- clickhouse-keeper：**基于 eBay nuRaft（C++17 Raft 库）**的协调服务；21.x 实验性引入，**22.3 起官方标记生产可用**；
- **协议兼容层**：实现 ZK wire protocol + 四字命令 + watch/session 语义——客户端把 ZK 地址换成 Keeper 即可，**迁移零代码**；同时提供 HTTP/Raft 接口；
- 可与 clickhouse-server 同进程内嵌运行（省一组机器），也可独立部署（推荐，避免 OLAP 大查询抖动 Raft 心跳——3.3 节的时钟依赖在这里具象化）；
- 复制内容：znode 树操作日志（类 ZK 的 znode + session + watch），Leader 处理写、多数派提交、Follower 回放。

### 8.7.3 小结

Keeper 与 KRaft、etcd 属于同一象限：**为一个大数据系统自研/内嵌一个"元数据共识层"**。它的独特贡献是"协议兼容"路线——用 Raft 重写引擎、保留 ZK 协议皮肤，把迁移成本压到改一行配置。（ClickHouse 官方亦提供直接用 etcd 的选项，可见这一层抽象在业界的通用性。）

---

## 8.8 Consul：hashicorp/raft 的十年样板工程

- **架构**：Server（3/5 台）组成 Raft 集群，**catalog（服务目录）/ KV / ACL / session 全部塞进一个 FSM**（7.3 节）；Client Agent 是无共识的转发层；多数据中心间用 WAN gossip 联邦（跨 DC 只有最终一致，不阻塞——正确地限定了 Raft 的作用域）；
- **读语义三档**（5.5 节已列）：default（Leader 前置 + lease 保护）/ consistent（RPC 打到 Leader + `verifyLeader` 多数派确认，`hashicorp/raft/raft.go:966`）/ stale（任意 Server 本地读）——一个产品把第五章的读模型做成了可选项；
- **运维**：`consul snapshot save/restore`（快照）、Autopilot（自动清理不健康 voter、倾向提升新版本节点——成员变更的自动化外衣，6.1）、`consul operator raft list-peers` 观测 voter 状态；
- 对照记忆：Consul之于 hashicorp/raft ≈ etcd之于 etcd-io/raft ≈ Consul 证明了"一站式库 + 一个全量 FSM"能支撑十年演进。

---

## 8.9 横向对比与两个"近亲"

### 8.9.1 八组件总对比

| 组件 | 复制粒度 | 数据量级 | 切主速度 | 强一致读 | 失联少数派行为 |
|---|---|---|---|---|---|
| Kafka KRaft | 元数据单日志 | MB~GB | 秒（日志回放） | 弱（镜像） | 拒绝当 controller |
| etcd | 全部写入 | ≤8GB | 秒 | 线性（默认） | 拒绝服务 |
| TiKV | Region×N | PB | 毫秒级（每 Region 独立） | ReadIndex/leader | 单 Region 不可用，其余正常 |
| Redis S/C | 无日志 | —— | 秒 | 无（数据面 AP） | 可能双主（选举层面 epoch 防御） |
| RocketMQ DLedger | CommitLog | TB | 秒 | ——（消息顺序读） | 拒绝写 |
| Nacos config | 配置日志 | MB~GB | 秒 | ReadIndex | 配置集群拒绝写 |
| CH Keeper | 协调日志 | GB | 秒 | 线性（多数派） | 拒绝服务 |
| Consul | 全量 FSM | GB | 秒 | consistent 档 | 拒绝服务 |

读表的三个角度：**粒度**（单组 vs Multi-Raft 决定扩展性）；**数据量级**（共识存储天然有顶，决定它该放元数据还是业务数据）；**少数派行为**（CP 的"拒绝服务"是特性不是缺陷）。

### 8.9.2 两个"近亲"：不是 Raft，但值得一并理解

- **ZooKeeper ZAB**：与 Raft 同构度最高的非 Raft 协议（epoch+zxid 选举、类 2PC 过半广播、崩溃恢复同步）。历史顺序上 ZAB（2008 前后）先于 Raft 成文，Raft 可视为把同类思想"规格化 + 可理解化"的成果。选型差异详见《ACID-CAP-BASE.md》3.4；
- **MongoDB PV1**（3.2+，2015）：官方明确"基于 Raft 的复制协议"——term、多数派提交（`w:majority`）、日志完整性投票约束都在，但叠加了 chained replication（级联复制）、优先级、catchup 机制等大量工程改造。它证明了一个论断：**"Raft 衍生"可以偏离得很远，但骨架（term/多数派/完整性）不能动**。

### 8.9.3 本章小结

- 五种用法光谱：**全量存储共识**（etcd/Consul/Keeper）→ **元数据自管理**（KRaft/Controller/Nacos）→ **存储引擎地基**（TiKV/CRDB/DLedger）→ **只借选举**（Redis）→ **衍生协议**（PV1/ZAB 对照）。
- 判断一个组件的 Raft 用得深不深，看三件事：**日志里复制的是什么？切主靠什么？读的默认语义是什么？**
- 所有"用错 Raft"的案例都败在同一处：以为引入 Raft 就获得了线性一致读（5 章）或者拿它复制高频大 value（2GB 配额一撞即穿）。

---
# 九、基于 Raft 构建应用：选型、设计与运维清单

## 9.1 判断清单：什么时候需要 / 不需要 Raft

**需要**（满足任意一条就该认真考虑）：
- 多副本数据**一个都不能丢已确认写入**（元数据、配置、账务、调度决策）；
- 需要**自动故障切换且切换后数据不回退**（不接受"提升最旧的从库"）；
- 多个执行体需要**对"谁拥有资源/谁是主"达成不可反悔的一致**（选主、分布式锁的权威版）；
- 需要**全局有序的事件流**（审计、变更记录）。

**不需要 / 慎用**：
- 数据可重建、可丢失最后几条（缓存、热数据）→ 主从异步复制（Redis 路线，8.4）；
- 只需要"不双主"的协调者 → 借选举即可，不必复制日志；
- 高频小写、亚毫秒延迟敏感 → 多数派 fsync 的延迟下限会先到（PACELC 的 else 分支，《ACID-CAP-BASE.md》5.1.4）；
- 大 value / 大容量 → 共识存储天然有顶（etcd 8GB 是经验天花板），业务数据走 8.3 的 Multi-Raft 数据库；
- 已有单写多读的可靠存储（MySQL）→ 直接单写 + 备份，Raft 是过度设计。

## 9.2 库选型决策树

```
你的系统是什么语言？
├─ Go   ─ 要极致可控/嵌入大系统 → etcd-io/raft（自己接 WAL+RPC，抄 etcd server 作业）
│        └ 只想要一个能用的共识组 → hashicorp/raft（FSM 两个方法搞定）
├─ Rust → raft-rs
├─ Java → SOFAJRaft（通用）/ DLedger（日志型工作负载）
└─ 都不想写 → 形态③：直接部署 etcd（或 Keeper 兼容 ZK），业务走 KV 接口
```

再核对三件事：快照方案（6.2.2 四件套是否齐全）、成员变更（单步 + learner）、读优化（ReadIndex 是否支持）——第七章对比表逐项打勾。

## 9.3 状态机设计十条（造轮子前默写）

1. **applied index 持久化**：状态机应用进度落盘，重启后从 applied+1 续放——重复 apply 必须幂等（4.5）；
2. **apply 串行、单线程**：任何"并行 apply"都是在重新发明乱序 bug；
3. **写路径不要碰状态机**：所有读改写必须走日志（先 propose 再 apply 再答复），绕过日志的"优化"都是线性一致性漏洞（5.2）；
4. **读请求明确选型**：ReadIndex（默认）还是 Lease（确认时钟源可靠后）（5.3/5.4）；
5. **客户端请求带唯一 ID + 去重**：会话表进快照（4.5/6.2）；
6. **快照四件套**：镜像 + lastIncludedIndex/Term + 配置 + 去重表（6.2.2）；
7. **快照生成放后台线程**：不能阻塞 tick——否则你在制造选举风暴；
8. **优雅处理 InstallSnapshot**：可能覆盖你全部本地日志，写日志前先想清楚（6.2.3）；
9. **变更走日志、一次一台、新节点先 learner**（6.1.3）；
10. **别在状态机里做慢 IO**：apply 回调要快，重活异步化——否则 commit lag 会拖垮整个组。

## 9.4 运维监控清单（上生产前逐项检查）

| 指标/检查 | 含义 | 危险信号 |
|---|---|---|
| Leader 变更次数 | 稳定期应≈0 | 频繁切换 = 网络抖动/GC 长停/磁盘慢 |
| 选举超时触发次数 | 同上 | PreVote 后仍频繁 = 多数派联通性差 |
| commit lag / applied lag | 复制与应用延迟 | 持续增大 = 慢盘或 apply 阻塞 |
| 慢盘（fsync p99） | 共识延迟的下限（6.3） | p99 > tick 的 1/2 就该报警 |
| 日志大小/快照耗时 | 压缩是否正常（6.2） | 只增不减 = 快照流程卡死 |
| voter 健康（DESCRIBE_QUORUM / etcdctl endpoint status / operator raft list-peers） | 各组件的观测窗口（8.1.3②/8.8） | 某 voter 长期落后 |
| 时钟（NTP 偏差） | 仅 Lease Read 需要 | 偏差 > 心跳间隔即危险（5.4） |

**参数基线**：心跳 100ms、选举超时 1~2s（etcd 默认 10 tick=1s；跨机房适当拉长）；随机化幅度 ≥ 选举超时本身（3.3）。**跨机房部署三副本"两地一中心"而不是三地**——多数派写要过两次机房 RTT，第三地只放 learner/观察者。

## 9.5 常见坑 TOP 7（每一条都是生产事故的形状）

1. **以为默认读线性一致** → 读循环没走 ReadIndex（5.2），分区时读到旧值；
2. **apply 前就答复客户端** → 切主后"已确认"数据消失（4.1 的⑧必须在⑥⑦之后）；
3. **votedFor/term 不落盘或落盘晚于回复** → 重启后重复投票，双主（3.4）；
4. **快照漏了集群配置/去重表** → 重启后节点"失忆"，退出集群或重复执行（6.2.2）；
5. **一次性加/删多台成员** → 多数派被砍穿或"纸面多数派"（6.1.3）；
6. **被隔离的旧 Leader 没有退位机制**（没开 CheckQuorum）→ 僵尸 Leader 长期迷惑客户端（3.6）；
7. **拿共识存储当数据库** → 撞配额（etcd 8GB）、大 value 打爆心跳与快照（1.5 节 etcd 条目、8.2.4）。

## 9.6 本章小结

- 先判断**要不要**（9.1），再选**形态**（9.2），然后按**状态机十条**写代码（9.3），按**监控清单**上线（9.4），用**常见坑**做代码评审 checklist（9.5）。
- 一切清单背后的主线只有一条：**Raft 把"日志一致"做成了可复用的轮子，你唯一要做对的是状态机与读语义**——这两处恰好是轮子管不到的地方。

---

# 十、附录

## 10.1 核心 RPC 字段速查（论文图 2 精编）

**RequestVote（选举）**

| 字段 | 含义 |
|---|---|
| term / candidateId / lastLogIndex / lastLogTerm | 任期 / 竞选人 / 日志新旧凭证 |
| 响应：term / voteGranted | 投票方当前任期（可能更高，用于打脸候选人）/ 是否同意 |

**AppendEntries（复制+心跳）**

| 字段 | 含义 |
|---|---|
| term / leaderId | 领导权凭证 |
| prevLogIndex / prevLogTerm | 一致性检查暗号 |
| entries[] / leaderCommit | 批量日志 / 提交点通告 |

**InstallSnapshot（快照，论文 §7）**

| 字段 | 含义 |
|---|---|
| term / leaderId / lastIncludedIndex / lastIncludedTerm | 领导权 / 快照覆盖点 |
| data[] / offset / done | 分块数据 / 偏移 / 结束标志 |

（工业实现的字段集是超集：clusterId、voter key、PreVote 标志、snapshot id 等，见各章源码证据。）

## 10.2 源码阅读入口清单（本地仓库按图索骥）

**etcd-io/raft**（`D:\code\3rd\raft-etcd`，算法库参考实现）
- `raft.go`：Config 开关（224~244）、campaign（1025）、becomeLeader（933）、stepLeader/Candidate/Follower（1275/1673/1718）、MsgReadIndex 流（1354/1370/1764）
- `rawnode.go`：Ready 事件模型；`read_only.go`：ReadIndex；`confchange/`、`quorum/`、`tracker/`：三大难点独立包
- `design.md`：官方设计说明（先读它再读码）

**Kafka KRaft**（`D:\code\3rd\kafka`，顶层 `raft/` + `metadata/` 模块）
- `raft/.../raft/QuorumState.java`：五态状态机（350/380/529/605/638）
- `raft/.../raft/KafkaRaftClient.java`：协议分发表（2825）、handleVoteRequest（835）、handleFetchRequest（1487）
- `raft/.../raft/LeaderState.java`：高水位与当期任期规则（680）
- `raft/.../raft/internals/`：BatchAccumulator、KRaftControlRecordStateMachine、Add/Remove/UpdateVoterHandler
- `core/src/main/scala/kafka/raft/KafkaRaftManager.scala`：raft 组与 Kafka 日志的对接
- `metadata/.../controller/QuorumController.java`：事件驱动主控制器；`metadata/.../image/publisher/Snapshot*.java`：元数据快照

**etcd server**（`D:\code\3rd\etcd`）
- `server/etcdserver/read/read.go`：linearizableReadLoop（109）
- `server/embed/etcd.go`：tick 配置（200/339）
- `server/storage/wal/`、`server/storage/snap/`：WAL 与快照

**hashicorp/raft**（`D:\code\3rd\raft`）：`api.go`（AddVoter 994 / RemoveServer 1028 / LeadershipTransfer 1309 / Barrier 907）、`raft.go`（verifyLeader 966）、`fsm.go`、`file_snapshot.go`

**Redis**（`D:\code\3rd\redis`）
- `src/sentinel.c`：sentinelVoteLeader（4792）、sentinelGetLeader（4848）、is-master-down-by-addr（3963）
- `src/cluster_legacy.c`：clusterHandleSlaveFailover（4387）、clusterSendFailoverAuthIfNeeded（4137）

**RocketMQ**（`D:\code\3rd\rocketmq`）：`store/.../store/dledger/DLedgerCommitLog.java`、`controller/.../impl/DLedgerController.java`、`docs/cn/dledger/`

**Nacos**（`D:\code\3rd\nacos`）：`core/.../core/distributed/raft/`（JRaftServer、NacosStateMachine:95、NacosRead/WriteRequestProcessor）、`config/.../repository/embedded/`

**raft-rs**（`D:\code\3rd\raft-rs`）：`raft.rs`、`raw_node.rs`、`read_only.rs`、`tracker.rs`

## 10.3 术语速查

| 术语 | 含义 | 首见 |
|---|---|---|
| RSM | 复制状态机：日志一致 ⇒ 状态一致 | 2.2 |
| term / epoch | 逻辑时钟，单调递增，仲裁一切分歧 | 3.2 |
| quorum / 多数派 | ⌈(n+1)/2⌉；选举与提交的及格线 | 2.3 |
| commitIndex / HW | 提交点：多数派已持久化的日志位置 | 4.4 |
| applied index | 状态机应用进度（≤ 提交点） | 2.2 |
| Log Matching | 同下标同任期 ⇒ 之前全部相同 | 4.2 |
| Leader Completeness | 已提交日志必在新 Leader 手里 | 5.1 |
| Figure 8 问题 | 旧任期条目不可直接提交 | 4.4 |
| PreVote / Prospective | 预投票：不升任期先探路 | 3.5 |
| CheckQuorum | Leader 定期验证多数派，失联即退位 | 3.6 |
| ReadIndex / Lease Read | 线性一致读的两种实现 | 5.3/5.4 |
| 单步成员变更 | 一次增删一台，走日志 | 6.1 |
| Joint Consensus | 新旧配置都过半的两阶段变更 | 6.1.2 |
| Learner | 只复制不投票的观察成员 | 6.1.3 |
| InstallSnapshot | 给落后副本直接发快照 | 6.2.3 |
| Multi-Raft | 数据分片 × 每片一个 Raft 组 | 8.3 |
| ISR（对照） | Kafka 数据面的同步副本集合（非 Raft） | 8.1 |

## 10.4 论文与参考资料

- Ongaro & Ousterhout, *In Search of an Understandable Consensus Algorithm*, USENIX ATC 2014（Raft 论文本体，含图 8）
- Ongaro, *Consensus: Bridging Theory and Practice*, Stanford PhD thesis 2014（PreVote/Lease/成员变更/客户端交互的完整规格）
- Howard, Mortier, *Paxos vs Raft: Have we reached consensus on distributed consensus?*(2020)——两者的形式化等价性
- KIP-500 / KIP-595 / KIP-596 / KIP-853（Kafka 官方 KIP 文档：KRaft 的设计理由与每处魔改的说明书）
- etcd 官方文档：learning 目录（raft 设计）、ops 指南（配额/tuning）
- TiDB/TiKV 官方博客：Multi-Raft、PD 调度、Follower Read
- Jepsen 分析（etcd/consul/tidb 各篇）：理论承诺的"机器验收"（《ACID-CAP-BASE.md》5.8）

---

# 结语

回到开头的问题：为什么 2026 年了，一大票新组件还在往 Raft 上靠？

因为 Raft 干的事情从来不是"某个组件的功能"，而是**把"多台机器像一台机器"这个分布式系统最古老的需求，做成了人人可读、处处可抄的标准件**。Kafka 用它回收 ZooKeeper 的地盘（8.1），etcd 用它托住整个 Kubernetes（8.2），TiKV 用它把强一致摊到 PB 级（8.3），Redis 借走它的选举思想却明智地拒绝了它的日志（8.4），RocketMQ 把它焊进消息存储（8.5），Nacos 用它给配置中心上保险（8.6）——同一份 14 页的论文，长出了五种不同的工业形态。

读这份文档的收获，按优先级排：

1. **机制层**（三~六章）：term/多数派/一致性检查/提交规则/ReadIndex——这套词汇是所有共识系统（包括 Paxos 系、ZAB）的通用语法；
2. **实现层**（七章）：Ready 模型、算法库与一站式库的分野——下次造轮子知道从哪里抄；
3. **组件层**（八章）：判断任何系统"Raft 用得深不深"的三问——**日志里是什么？切主靠什么？读的默认语义是什么？**
4. **边界感**（九章）：什么时候不要用 Raft，和什么时候要用同样重要——Redis 的取舍（8.4.4）值得每个架构师背诵。

下一篇建议接续阅读：《ACID-CAP-BASE.md》5.8 节（Jepsen 验证）——看看本文所有"理论承诺"是如何被故障注入逐一考场的。
