# Redis 深度源码解析（写给初学者的架构全景）

> **本文基于的源码**：`D:\code\3rd\redis`，版本 **8.10.2**（2026-09-16 发布的最新稳定 tag，Git tag `8.10.2`，所属 8.10 线于 2026-07-29 GA）。文中所有【源码证据】的文件路径与行号均为对该 tag 实际读取所得，源码位于 `src/` 目录。
>
> **版本取舍说明**：Redis 从 3.0 到 8.x 的核心骨架高度稳定——`main()` → `initServer()` → `aeMain()` 的事件循环、`dict` 渐进式 rehash、`redisObject` 的 type/encoding 双维度、RDB/AOF 双持久化、PSYNC 复制协议，这些主干十年间几乎未变，因此本文内容对使用 6.x / 7.x 的读者同样适用；8.x 的三项大变化（kvobj 重构、IO 线程独立模块、集群原子槽位迁移 ASM）单独立节标注。3.0 → 8.10 的特性演进对比见 1.6 节，所有"某特性属于哪个版本"的结论均经本地 git 标签逐项实证。
>
> **阅读约定**：每章"先白话、后源码"——先用一两句人话讲清"这是什么、为什么需要它"，再给出源码证据链（`src/文件名.c` + 函数名 + 行号 + 代码片段）。行号只对 8.10.2 精确，读者用编辑器打开源码按类名 + 函数名定位即可。

## 如何读这份文档

如果你是 Redis 初学者，推荐两遍读法：

- **第一遍（建立地图，1~2 小时）**：只读第一章（总览）每一节的开头白话段、各章的"小结"节、以及第十一章（贯通视图）。目标是能回答：一条 SET 命令从 socket 到落库经历哪几步？数据为什么"记得住"（持久化）又"丢得起"（缓存）？主从切换靠什么自动完成？
- **第二遍（深入源码）**：对照每一章的【源码证据】，打开源码逐行读。顺序建议：第四章（事件循环与命令执行，一切的主干）→ 第二章（地基数据结构）→ 第三章（对象系统）→ 第五章（持久化）→ 第六章（过期与淘汰）→ 第七章（复制与哨兵）→ 第八章（集群）→ 第九章（扩展机制，可跳读）→ 第十章（高可靠保障，运维向，按需先读）。

---

# 一、总览：Redis 的定位、设计哲学与整体架构

## 1.1 一句话定位

**Redis 是一个以内存为主要存储介质、以单线程事件循环执行命令、以"数据结构服务器"自我定位的键值数据库**。它不是"把哈希表搬进内存"那么简单——REmote DIctionary Server 这个名字本身就在说：它把 String、List、Hash、Set、Sorted Set、Stream 这些数据结构以**原子命令**的形式暴露给网络，让应用层不必再自己加锁、自己维护结构一致性。仓库根目录的 `MANIFESTO`（宣言）开篇第一条即自述 "A DSL for Abstract Data Types"（操作抽象数据类型的 DSL，以 TCP daemon 实现），第二条 "Memory storage is #1"——内存存储是第一原则。

它解决的核心问题有三层：

1. **快**——把热数据放内存、用 O(1)/O(logN) 的结构响应命令，单实例轻松十万级 QPS；
2. **稳**——用 RDB/AOF 把内存状态落到磁盘（第五章），用主从复制 + 哨兵/集群实现高可用（第七、八章）；
3. **通用**——同一套键值语义上长出分布式锁（SET NX）、消息队列（Stream）、排行榜（ZSet）、客户端缓存（tracking）、可编程（Lua/模块）等一整片生态。

## 1.2 设计哲学：读源码前先记住五句话

1. **内存是主存，磁盘只是副本**。所有读写都在内存结构上直接发生，磁盘（RDB/AOF）只负责"别忘了"。这个前提决定了后续一切设计：淘汰策略、fork 快照、惰性释放，全是"内存宝贵"四个字的展开。
2. **单线程执行命令，把并发的复杂度降到零**。命令之间天然串行，不需要行级锁、不需要事务隔离级别。代价是**任何命令都不能阻塞事件循环**——这条铁律催生了 Redis 源码里最精彩的一批机制：渐进式 rehash（2.3 节）、惰性删除 + 后台线程（6.2/6.4 节）、有子进程在跑时暂停 LRU 时钟更新（3.4 节 `hasActiveChildProcess()` 判断）。8.x 引入 IO 线程只分担网络读写与协议解析，**命令执行仍单线程**（4.6 节）。
3. **一切数据结构都是"编码自适应"**。用户视角的 Hash，底层可能是 listpack 也可能是哈希表——由元素个数与单元素大小决定，超过阈值自动转换（3.2 节）。小数据用紧凑编码省内存，大数据用标准结构保性能，对用户完全透明。
4. **事件驱动 + 定时器，一切皆回调**。`ae.c` 是 epoll/kqueue/select 之上的薄封装（第四章），文件事件（socket 可读/可写）与时间事件（`serverCron` 心跳）是仅有的两种驱动源。Redis 没有线程池、没有协程，理解了事件循环就理解了它的一半。
5. **简单性优先于完备性**。能采样解决的就不精确计算（近似 LRU，6.3 节）；能复用代码的就不抽象层次（`server.h` 单头文件 + `redisCommandTable` 大表）；能推迟的就推迟（渐进式 rehash、惰性删除、惰性释放）。读源码时遇到"看起来不精确/不彻底"的实现，先默认它是性能与简单性的折中，再去找注释验证——Redis 的注释密度极高，且常常直接把设计权衡写在代码上方。

## 1.3 源码分层全景

Redis 是单进程 C 工程，核心代码集中在 `src/`（8.10.2 实测 210 个 `.c/.h` 文件）+ `deps/`（捆绑的第三方库）+ `modules/`（随内核分发的内置模块）。按"用户感知"分层如下：

```
┌───────────────────────────── 扩展层 ─────────────────────────────┐
│  modules/vector-sets（向量集，HNSW，8.0 内置）                     │
│  module.c（RedisModule_* 模块 API）  functions.c/eval.c（Lua 脚本）│
├───────────────────────────── 分布式层 ──────────────────────────┤
│  cluster.c/cluster_legacy.c/cluster_asm.c（集群：分片+故障转移）    │
│  sentinel.c（哨兵）   replication.c（主从复制）                    │
├──────────────────────────── 数据结构层 ────────────────────────┤
│  t_string.c t_list.c t_set.c t_zset.c t_hash.c t_stream.c        │
│  （五大类型 + Stream 的命令实现与编码转换）                          │
├────────────────────────── 内核数据结构 ─────────────────────────┤
│  sds.c dict.c listpack.c quicklist.c intset.c rax.c (stream.h)   │
│  object.c（redisObject） db.c/kvstore.c（键空间）                  │
├────────────────────────── 生存期管理 ──────────────────────────┤
│  expire.c（过期） evict.c（淘汰） lazyfree.c（惰性释放）            │
│  defrag.c（碎片整理） zmalloc.c（内存统计封装 jemalloc）            │
├────────────────────────── 持久化层 ────────────────────────────┤
│  rdb.c（快照） aof.c（写后日志） bio.c（后台线程）                  │
├────────────────────────── 网络与调度 ──────────────────────────┤
│  ae.c（事件循环） networking.c/iothread.c（协议解析/IO 线程）       │
│  server.c（main/serverCron/processCommand/call 总装配）           │
├──────────────────────────── 地基 ─────────────────────────────┤
│  deps/jemalloc（内存分配器） deps/lua（Lua 5.1） deps/hiredis      │
└──────────────────────────────────────────────────────────────────┘
```

三点读法提示：

- **`server.c` + `server.h` 是全仓库的十字路口**：`main()`、`serverCron`、`processCommand`、`call`、`redisServer` 全局结构体全在这里，读任何机制前先在这两个文件里建立坐标。
- **`t_*.c` 是"命令层"，`dict/listpack/...` 是"结构层"**，两者通过 `redisObject` 的 encoding 字段解耦（第三章）——这是 Redis 内部最重要的分层线。
- **`deps/` 不是"第三方可选组件"而是"发行时必须捆绑的零件"**：换掉 jemalloc（如用 libc/tcmalloc 编译）会直接改变碎片整理（6.5 节）的行为。

## 1.4 分层依赖图（以头文件包含与函数调用关系实证）

```
                 deps/jemalloc（zmalloc.c 之下的一切内存分配）
                 ▲
             zmalloc.c（内存统计封装）
                 ▲
     ┌───────────┼──────────────┐
   sds.c      dict.c ◄── kvstore.c   listpack.c quicklist.c intset.c rax.c
     ▲    ▲        ▲          ▲          ▲
     └────object.c──┘          │          │
              ▲                │          │
              ├── db.c（键空间：redisDb = kvstore *keys + kvstore *expires）
              │
   t_string/t_hash/t_zset/... （命令实现，调用结构层 + object.c）
              ▲
          server.c processCommand/call（命令分发与执行）
              ▲
   networking.c（协议解析/回包） ◄── iothread.c（读写卸载）
              ▲
          ae.c（事件循环，epoll/kqueue 封装）
              ▲
   ┌──────────┼──────────────┐
 replication.c          rdb.c / aof.c / expire.c / evict.c / lazyfree.c
（复制：复用 networking 的 client 机制）   （生存期与持久化，全部挂在
              ▲                        serverCron 与事件循环上）
 cluster.c / sentinel.c（以普通 client 的身份说 RESP 协议）
```

这张图的关键结论：**所有子系统最终都收敛到 `server.c` 的事件循环与 `serverCron` 心跳上**。哨兵是一个"跑在 Redis 进程里的 Redis 客户端"；复制让从节点把自己配置成"主节点的 client"；AOF/RDB 由 `serverCron` 里的条件判断触发 fork。没有独立的"管理进程"，只有一个精心调度的单线程。

## 1.5 关键问题 → Redis 方案映射（全文导览）

| 使用 Redis 时的关键问题 | Redis 的方案 | 详见 |
|---|---|---|
| 内存数据怕断电 | RDB 快照（fork + 写时复制）+ AOF 写后日志（appendfsync 三档）+ 4.1 混合持久化 | 第五章 |
| 内存满了怎么办 | 过期删除（惰性 + 采样）→ 淘汰策略（近似 LRU/LFU/LRM 池）→ noeviction 拒写 | 第六章 |
| 键值对太多、结构太大浪费内存 | 编码自适应：listpack/intset 紧凑编码，超阈值转标准结构 | 第三章 |
| 单线程怎么扛高并发连接 | IO 线程分担读写解析（默认关），命令执行仍单线程 | 第四章 |
| 主库挂了数据怎么办 | PSYNC 增量复制（backlog 环形缓冲），哨兵自动故障转移 | 第七章 |
| 数据量超单机内存 | 集群 16384 slots 分片 + MOVED/ASK 重定向 + 原子槽位迁移 | 第八章 |
| 多条命令的原子性 | MULTI/EXEC 事务、Lua 脚本/函数（服务器端原子执行） | 第九章 |
| 削峰、任务队列 | List 阻塞弹出（BLPOP）与 Stream 消费组（XREADGROUP + PEL） | 第九章 |
| 缓存击穿/一致性 | client tracking（RESP3 push 推送失效通知） | 第九章 |
| 定制数据结构 | Redis Modules API（C ABI），vector-sets 即官方内置范例 | 第九章 |
| 结构突变导致卡顿 | 渐进式 rehash、渐进式碎片整理、惰性释放全部"分期付款" | 第二、四、六章 |

## 1.6 版本演进：3.0 → 8.10 关键变化对比

写作时（2026 年 10 月）的版本格局：6.2/7.0/7.2/7.4 已陆续停止 OSS 维护，8.2 ~ 8.10 为当前维护线，8.12 处于里程碑阶段（`8.12-m02-int` tag）。8.x 自 8.4 起采用"每约 3 个月一个 minor"的快速节奏。本节所有"特性属于哪个版本"的结论都经过双重验证：**① 用本地仓库的 git 标签（3.0.0 ~ 8.10.2）对文件/代码逐项 grep 实证；② GA 日期取自各发布 tag 的提交时间**。

### 1.6.1 版本时间线

| 版本 | GA 时间 | 一句话主题 |
|---|---|---|
| 3.0 | 2015-04-01 | 集群正式 GA（16384 slots + gossip） |
| 3.2 | 2016-05-06 | 位图/地理支持打磨，ziplist 时代编码优化 |
| 4.0 | 2017-07-14 | **Modules API**、lazyfree/UNLINK、**LFU 淘汰**、PSYNC2 |
| 5.0 | 2018-10-17 | **Stream**（消息队列原语）、新 LRU 实现 |
| 6.0 | 2020-04-30 | **ACL 权限**、**RESP3**、**IO 线程**、客户端缓存（tracking） |
| 6.2 | 2021-02-22 | 新命令打磨（COPY、SMISMEMBER 等），稳定版 |
| 7.0 | 2022-04-27 | **Functions**（服务端函数库）、**多部分 AOF**（manifest）、**Sharded Pub/Sub**、listpack 全面替代 ziplist |
| 7.2 | 2023-08-15 | debug/可观测增强 |
| 7.4 | 2024-07-28 | **Hash Field TTL**（字段级过期）、许可证从 BSD 转为 RSALv2/SSPLv1 双许可 |
| 8.0 | 2025-05-02 | **AGPLv3 回归开源三许可**、**向量集 Vector Sets**（HNSW）、IO 线程重构为 `iothread.c` |
| 8.2 | 2025-08-04 | **kvobj 重构**（键元数据并入对象） |
| 8.4 | 2025-11-18 | **集群原子槽位迁移 ASM**（cluster_asm.c） |
| 8.6 | 2026-02-08 | 迭代增强 |
| 8.8 | 2026-05-25 | **LRM 淘汰策略**（least recently modified） |
| 8.10 | 2026-07-29 | 迭代增强（本文基线） |

### 1.6.2 特性引入版本对照表（git 实证，可复现）

每行都可用 `git ls-tree <tag> --name-only -- <路径>` 或 `git grep -c "<关键字>" <tag> -- <文件>` 复现：

| 特性 | 引入版本 | 实证（旧 tag → 新 tag） | 详见 |
|---|---|---|---|
| Modules API | 4.0 | 3.2.0 无 `src/module.c` → 4.0.0 有 | 九.9.3 |
| lazyfree（UNLINK/FLUSHALL ASYNC） | 4.0 | 3.2.0 无 `src/lazyfree.c` → 4.0.0 有 | 六.6.4 |
| LFU 淘汰 | 4.0 | 4.0.0 `server.h` 出现 LFU 相关定义 | 六.6.3 |
| Stream（XADD/XREADGROUP） | 5.0 | 4.0.0 无 `src/t_stream.c` → 5.0.0 有 | 九.9.5 |
| ACL 权限 | 6.0 | 5.0.0 无 `src/acl.c` → 6.0.0 有 | 九.9.6 |
| IO 线程（io-threads 配置） | 6.0 | 6.0.0 `config.c` 出现 `io-threads` | 四.4.6 |
| RESP3（addReplyPushLen 等） | 6.0 | 5.0.0 `server.h` 0 命中 → 6.0.0 `networking.c` 出现 | 九.9.4 |
| 客户端缓存 tracking | 6.0 | 6.0.0 有 `src/tracking.c` | 九.9.6 |
| Functions / 服务端函数库 | 7.0 | 6.2.0 无 `src/functions.c` → 7.0.0 有 | 九.9.2 |
| listpack 全面替代 ziplist | 7.0 | 6.2.0 `list-max-ziplist-size` → 7.0.0 起以 `list-max-listpack-size` 为主名（config.c:3511 保留旧名别名） | 二.2.5 |
| 多部分 AOF（manifest） | 7.0 | 7.0.0 `aof.c` 出现 `AOF_MANIFEST`（10 处） | 五.5.4 |
| Sharded Pub/Sub（SPUBLISH） | 7.0 | 6.2.0 无 `src/commands/spublish.json` → 7.0.0 有 | 九.9.4 |
| Hash Field TTL（HEXPIRE） | 7.4 | 7.2.0 无 `src/commands/hexpire.json` → 7.4.0 有 | 六.6.2 |
| 集群代码拆分 cluster_legacy.c | 7.4 | 6.2.0/7.2.0 无 → 7.4.0 有 | 八 |
| 向量集 Vector Sets（HNSW） | 8.0 | 7.4.0 无 `modules/vector-sets` → 8.0.0 有 | 九.9.7 |
| IO 线程独立模块 iothread.c | 8.0 | 7.0.0 无 → 8.0.0 有 | 四.4.6 |
| kvobj 重构（iskvobj/metabits） | 8.2 | `iskvobj` 首次提交 2025-05-12（8.0 GA 之后） | 三.3.4 |
| 集群原子槽位迁移 ASM | 8.4 | `cluster_asm.c` 首次提交 2025-10-22 | 八.8.5 |
| LRM 淘汰策略（volatile-lrm/allkeys-lrm） | 8.8 | 2026-01-06 提交 0cb1ee0dc "New eviction policies - least recently modified" | 六.6.3 |

三个容易搞错的点，特别提醒：

- **IO 线程不是 8.x 才有**：6.0 就引入 `io-threads` 配置；8.0 做的是把实现从 `networking.c` 抽出为独立的 `iothread.c` 并重写为"客户端任务队列"模型；
- **listpack 也不是 7.0 才诞生**（`listpack.c` 文件 2017-08-30 已创建），但直到 7.0 才全面接管 hash/zset/list 的紧凑编码并让 ziplist 退居兼容代码（8.x 的 `object.h:80` 注释明确标注 `OBJ_ENCODING_ZIPLIST` "No longer used"）；
- **Stream 的阻塞读取（XREAD BLOCK）与消费组是 5.0 同批引入的**，不是后来补的。

### 1.6.3 许可证风波与版本选择建议

- **≤7.2**：BSD-3-Clause，纯开源。
- **7.4 起**（2024-03 宣布）：转为 **RSALv2 / SSPLv1 双许可**——自由使用受限（不再满足 OSI 开源定义），Debian/Fedora 等发行版随即将 Redis 移出仓库，社区在 Valkey（Linux 基金会，fork 自 7.2.4）上续写 BSD 分支。
- **8.0 起**（2025-05）：**加入 AGPLv3 成为三许可**（RSALv2 / SSPLv1 / AGPLv3 任选），源码头文件可证——本文引用的 `iothread.c` 头部注释原文即为："Licensed under your choice of (a) the Redis Source Available License 2.0 (RSALv2); or (b) the Server Side Public License v1 (SSPLv1); or (c) the GNU Affero General Public License v3 (AGPLv3)."【源码证据：`src/iothread.c` 第 6-9 行】
- **选型建议**：学习/自用任意版本皆可；商业产品集成建议法务评估三许可差异；追求纯 BSD 生态可关注 Valkey（协议兼容，本文绝大多数机制分析同样适用，两边的公共主干仍是同一套 7.2 血统代码）。

### 1.6.4 对初学者的意义：哪些知识过时了，哪些永远有效

- **新项目避免依赖**：`ziplist` 相关旧编码知识（8.x 中 `OBJ_ENCODING_ZIPLIST` 已标注 no longer used，仅 RDB 兼容保留）、RESP2-only 客户端假设（服务端 6.0 起支持 RESP3）、"Redis 绝对不丢数据"的口口相传（异步复制 + everysec 刷盘默认是秒级窗口）。
- **老教程里永久有效的部分**：SDS/dict/跳表/ziplist→listpack 的内存思想、单线程命令模型、RDB/AOF 原理、PSYNC 协议、哨兵与集群的仲裁思想——这些核心从 3.x 到 8.x 基本未变，本文第二~九章正是按这条主线写的。
- **版本与生态对应**（选教材时对号入座）：大多数中文教材基于 5.x/6.x；Spring Data Redis / Jedis / Lettuce 客户端对 7.x/8.x 全兼容；集群槽位迁移命令在 8.4+ 出现 ASM 新实现但旧 `CLUSTER SETSLOT` 流程仍保留（cluster_legacy.c 与 cluster_asm.c 并存）。

## 1.7 全文章节地图

- **第二章 地基数据结构**：SDS（为什么不用 C 字符串）、dict 渐进式 rehash、跳表、listpack/quicklist、intset/rax——Redis 一切内存效率的来源。
- **第三章 对象系统与键空间**：redisObject 的 type/encoding 双维度、编码转换阈值、共享对象、redisDb 与 8.x kvobj 重构。
- **第四章 事件循环与命令执行**：ae 事件循环、serverCron 心跳、一条命令从 socket 到执行完的完整链路、客户端管理与 IO 线程。
- **第五章 持久化**：RDB 的 fork 快照、AOF 的写后日志与重写、7.0 多部分 AOF、混合持久化、bio 后台线程。
- **第六章 过期、淘汰与内存管理**：惰性 + 采样过期、近似 LRU 池/LFU/LRM、lazyfree、activedefrag。
- **第七章 复制与哨兵**：FULLRESYNC/PSYNC 握手、backlog 增量复制、哨兵的主客观下线与 Leader 选举。
- **第八章 集群**：16384 槽位、gossip 协议、MOVED/ASK、从节点竞选、8.4 原子槽位迁移。
- **第九章 扩展机制**：事务/MULTI、Lua 脚本与函数、模块 API、Pub/Sub、Stream、客户端缓存与 ACL。
- **第十章 高可靠保障**：面向"把 Redis 当高可用基础设施"的读者——可靠性模型与丢数据窗口、部署形态铁律、四组关键配置（含防脑裂的 min-replicas 与淘汰策略警告）、运维四件事，以及在 Redis 上构建分布式并发原语（锁/信号量/队列）的可靠性契约；末节整理一套三层防御实战方案（业务分级/兜底防护/架构优化）并给出两处机制勘误。
- **第十一章 贯通视图**：把"服务器启动、一条 SET 命令、一次故障转移"三条时间线叠成全景图。
- **第十二章 附录**：关键结构体速查表、学习路线、30 个源码入口。

# 二、地基数据结构（sds / dict / listpack / quicklist / intset / rax / 跳表）

> 本章对应源码：`src/sds.c`、`src/dict.c`、`src/listpack.c`、`src/quicklist.c`、`src/intset.c`、`src/rax.c`、`src/t_zset.c`（跳表实现位于其中）。以下所有【源码证据】行号均为 8.10.2 实际读取所得。

## 2.1 历史背景：Redis 要解决什么问题

2009 年，antirez（Salvatore Sanfilippo）在做一个实时访问统计工具时，发现关系数据库 + Memcached 的组合既不够快也不够表达力：Memcached 只能存字符串，MySQL 又要自己写排序和计数。于是他写了 Redis：**把常用的内存数据结构直接做成网络服务**。从一开始它就不是"更快的缓存"，而是"内存里的数据结构服务器"——这个定位决定了底层结构的质量就是产品质量：字符串怎么省内存、哈希表怎么扩容不卡顿、有序集合怎么同时支持排名和范围查询，这些正是本章的内容。

## 2.2 SDS：让字符串记住长度

**白话**：C 语言字符串以 `\0` 结尾，想知道长度必须 `strlen()` 扫一遍，拼接连开 N 次内存，还装不下二进制数据。Redis 自己造了 SDS（Simple Dynamic Strings）：**头部带长度字段的字节数组**。所有键、值、参数缓冲区都是 SDS。

【源码证据】`src/sds.h:28-35`——SDS 的头部结构按字符串大小分五档（sdshdr5/8/16/32/64），sdshdr8 长这样：

```c
struct __attribute__ ((__packed__)) sdshdr8 {
    uint8_t len;    /* used */
    uint8_t alloc;  /* excluding the header and null terminator */
    unsigned char flags; /* 3 lsb of type, 5 unused bits */
    char buf[];
};
```

三个设计点：

1. **`__packed__` + `flags` 低 3 位存类型**：从 `char *` 指针回退 1 字节即可读到 flags，知道头部是哪一档——短字符串用 3 字节头部（sdshdr8 是 3 字节，sdshdr5 更省），几十亿个键累计省下的是 GB 级内存。
2. **`len` 与 `alloc` 分离**：`sdslen()` 是 O(1)；`alloc - len` 是剩余空间，实现"预分配"式拼接。
3. **空间预分配**：`sdsMakeRoomFor`（`src/sds.c:341`）扩容时，若新长度小于 1MB 就翻倍预分配，超过 1MB 则每次多给 1MB——把 N 次拼接的 realloc 摊薄成 O(N) 总量。

## 2.3 dict：两个哈希表与渐进式 rehash

**白话**：哈希表最怕扩容——表里几千万个键时把所有 entry 重新搬家，单线程的 Redis 就卡死了。Redis 的方案是：**扩容时同时持有新旧两张表，此后每次增删改查顺手搬一个桶**，直到搬完。这就是渐进式 rehash。

【源码证据】`src/dict.h:165-168`——dict 内部是两张表：

```c
    dictEntry **ht_table[2];
    /* ... */
    long rehashidx; /* rehashing not in progress if rehashidx == -1 */
```

【源码证据】`src/dict.c:406-438`——`dictRehash(d, n)` 做 n 步搬迁：

```c
int dictRehash(dict *d, int n) {
    int empty_visits = n*10; /* Max number of empty buckets to visit. */
    /* ... */
    if (can_resize == DICT_RESIZE_AVOID &&
        ((s1 > s0 && s1 < dict_force_resize_ratio * s0) ||
         (s1 < s0 && s0 < HASHTABLE_MIN_FILL * dict_force_resize_ratio * s1)))
    {
        return 0;
    }
    while(n-- && d->ht_used[0] != 0) {
        while(d->ht_table[0][d->rehashidx] == NULL) {
            d->rehashidx++;
            if (--empty_visits == 0) return 1;
        }
        /* Move all the keys in this bucket from the old to the new hash HT */
        rehashEntriesInBucketAtIndex(d, d->rehashidx);
        d->rehashidx++;
    }
    return !dictCheckRehashingCompleted(d);
}
```

要点逐条对应白话：

- `rehashidx == -1` 表示没在搬迁（`dict.h:232` 的 `dictIsRehashing`）；搬迁中，所有读操作查两张表、写操作只写新表；
- `empty_visits = n*10` 限制空桶扫描次数，保证单步时间有上界（注释原文写明 "otherwise the amount of work it does would be unbound"）；
- `DICT_RESIZE_FORBID / DICT_RESIZE_AVOID`：有子进程在跑（RDB/AOF 落盘）时，避免扩容（否则写时复制会放大内存翻倍），倍率超 4 倍才允许强制扩（`dict_force_resize_ratio`）——**内存效率与后台落盘的联动**，这是初学者最常忽略的一条。
- 服务端真正的"定时搬迁"在 `_dictRehashStep`（被 `serverCron`/数据库操作调用）和 `dictRehashMicroseconds`（按微秒预算搬迁，`src/dict.c:449`）里完成。

## 2.4 跳表：zset 的索引骨架

**白话**：有序集合要同时支持 O(logN) 的范围查询和排名，平衡树实现复杂且范围遍历不便。Redis 选择跳表：多层链表 + 每层随机晋升，实现简单、范围查询天然友好（底层双向链表 `backward` 指针）。

【源码证据】`src/server.h:656-657`——两个魔数：

```c
#define ZSKIPLIST_MAXLEVEL 32 /* Should be enough for 2^64 elements */
#define ZSKIPLIST_P 0.25      /* Skiplist P = 1/4 */
```

`P = 1/4` 意味着每个节点平均只有 1.33 个前向指针——**空间换时间的比例压到最低**（对比红黑树的两个指针，跳表反而更省）。`src/server.h:1797-1807` 的 `zskiplistNode` 定义里能看到 `level[]` 柔性数组、`backward` 指针与 `span`（跨度，用于 O(logN) 求排名 ZRANK）；注释还说明 8.x 把节点元素 sds 嵌在 `level[]` 数组之后以减少一次指针寻址。具体插入逻辑在 `src/t_zset.c` 的 `zslInsert`，而"层高怎么随机"在 `zslRandomLevel`——每层以 1/4 概率晋升，期望层高 1/(1−P)=1.33（原理与完整推导见附录 12.4 专题）。

## 2.5 listpack 与 quicklist：紧凑编码与链式压缩

**白话**：一个只装 10 个小字段的 Hash，如果老老实实用哈希表，指针、桶、对齐开销比数据本身还多。Redis 的答案是小数据用**连续内存的字节序列**（listpack）：每个元素自带长度头，顺序读写、二分靠元素级元数据。数据变大后再整体换成哈希表/跳表。列表（List）则把 listpack 串成双向链表，每个节点是一段 listpack，中间段可选 LZF 压缩——这就是 quicklist。

【源码证据】`src/listpack.c:224`——`lpNew(capacity)` 分配一块连续内存；`src/quicklist.h:107-117` 与 `:47-59`：

```c
typedef struct quicklist {
    quicklistNode *head;
    quicklistNode *tail;
    unsigned long count;        /* total count of all entries in all listpacks */
    unsigned long len;          /* number of quicklistNodes */
    /* ... */
    signed int fill : QL_FILL_BITS;       /* fill factor for individual nodes */
    unsigned int compress : QL_COMP_BITS; /* depth of end nodes not to compress;0=off */
    /* ... */
} quicklist;

typedef struct quicklistNode {
    struct quicklistNode *prev;
    struct quicklistNode *next;
    unsigned char *entry;
    size_t sz;             /* entry size in bytes */
    unsigned int count : 16;     /* count of items in listpack */
    unsigned int encoding : 2;   /* RAW==1 or LZF==2 */
    unsigned int container : 2;  /* PLAIN==1 or PACKED==2 */
    /* ... */
} quicklistNode;
```

配置联动（`src/config.c:3511、:3533`）：`list-max-listpack-size` 默认 `-2`（每节点 listpack 目标 8KB，负数表示按字节，正数表示按元素个数），`list-compress-depth` 默认 0（不压缩，两端各留 `compress` 个节点不压）。历史脉络：7.0 起 listpack 全面接管 ziplist，8.10 中 `object.h:80` 将 `OBJ_ENCODING_ZIPLIST` 标注为 "No longer used: old list/hash/zset encoding"，`ziplist.c` 仅剩 RDB 兼容加载用途。

## 2.6 intset 与 rax：两个专用结构

- **intset**：集合全是整数且数量不大时的编码，`src/intset.c:35-39` 的结构只有 `encoding/length/contents[]` 三件套，内容按元素位宽（16/32/64 位）统一存储，插入超范围整数时**整体升级位宽**（`intsetUpgradeAndAdd`）。它同时是 8.x 中集合在"全整数小集合"场景下的省内存主力。
- **rax（Radix tree，基数树）**：Stream 的骨架（`stream.h:36` 的 `stream` 结构第一个字段就是 `rax *rax`），也是集群模式下保存槽位归属等的通用前缀树。共享前缀 + 每节点可变长度，适合"ID 递增的消息流"这类键分布。

## 2.7 本章小结

- SDS 用"头部记长度 + 分档 + 预分配"解决了 C 字符串的三大缺陷，是所有键值的最小载体；
- dict 的渐进式 rehash 是"单线程不能阻塞"这条铁律的第一个教科书案例，且与 RDB/AOF 子进程存在内存联动；
- 跳表以 P=1/4 的随机晋升拿到了接近平衡树的效果和更低的内存开销；
- listpack/quicklist/intset 体现了同一个思想：**小数据用连续内存，大数据才升级标准结构**——阈值由 config 控制（第三章展开）；
- rax 是 Stream 与集群元数据的共享骨架。

---

# 三、对象系统与键空间（object.c / db.c / kvstore）

> 本章对应源码：`src/object.h/object.c`、`src/server.h`（redisDb）、`src/db.c`、`src/kvstore.c`、`src/t_hash.c`、`src/t_zset.c`。

## 3.1 redisObject：type/encoding 双维度的统一包装

**白话**：用户看到的 LPUSH 是"往列表塞东西"，底层可能是 listpack、quicklist 甚至别的。把"逻辑类型（type）"和"物理编码（encoding）"拆成两个独立维度，就是 Redis 对象系统的全部秘密——**同一个命令、多种实现、运行时择优**。

【源码证据】`src/object.h:102-117`——8.10.2 的对象头（注意 8.x 新增的 `iskvobj`/`metabits` 位，3.4 节展开）：

```c
struct redisObject {
    unsigned type:4;
    unsigned encoding:4;
    unsigned refcount : OBJ_REFCOUNT_BITS;
    unsigned iskvobj : 1;   /* 1 if this struct serves as a kvobj base */

    /* metabits and lru are Relevant only when iskvobj is set: */
    unsigned metabits :8;  /* Bitmap of metadata (+expiry) attached to this kvobj */
    unsigned lru:LRU_BITS; /* LRU time (relative to global lru_clock) or
                            * LFU data (least significant 8 bits frequency
                            * and most significant 16 bits access time). */
    void *ptr;
};
/* robj - General purpose redis object */
typedef struct redisObject robj;
```

`type` 的取值在 `src/server.h:875` 起（OBJ_STRING/OBJ_LIST/OBJ_SET/OBJ_ZSET/OBJ_HASH/OBJ_STREAM/OBJ_MODULE/OBJ_VECTOR...），`encoding` 的取值在 `src/object.h:75-90`——这张表本身就是一部编码演进史：

```c
#define OBJ_ENCODING_RAW 0     /* Raw representation */
#define OBJ_ENCODING_INT 1     /* Encoded as integer */
#define OBJ_ENCODING_HT 2      /* Encoded as hash table */
#define OBJ_ENCODING_ZIPMAP 3  /* No longer used: old hash encoding. */
#define OBJ_ENCODING_LINKEDLIST 4 /* No longer used: old list encoding. */
#define OBJ_ENCODING_ZIPLIST 5 /* No longer used: old list/hash/zset encoding. */
/* ... INTSET 6 / SKIPLIST 7 / EMBSTR 8 / QUICKLIST 9 / STREAM 10 / LISTPACK 11 ... */
#define OBJ_ENCODING_LISTPACK_EX 12 /* Encoded as listpack, extended with metadata */
#define OBJ_ENCODING_TMPL_LP 14 /* Hash with shared template, values in listpack */
```

对象头总计 16 字节，`lru` 字段一鱼三吃：LRU 模式存 24 位时钟（`object.h:92` `#define LRU_BITS 24`），LFU 模式低 8 位存对数计数器、高 16 位存衰减时间（第六章）。

## 3.2 编码矩阵与转换阈值

**白话**：什么时候用紧凑编码、什么时候升级？答案是一组可调阈值，写命令时检查、超限即整体转换。

| 逻辑类型 | 紧凑编码 | 升级条件（默认值） | 标准编码 | 阈值配置（config.c 行号） |
|---|---|---|---|---|
| Hash | listpack（8.x 起另有 TMPL_* 模板编码） | 字段数 > 512 或单值长度 > 64 字节 | hashtable | `hash-max-listpack-entries` config.c:3593 / `hash-max-listpack-value` config.c:3605 |
| Set | intset（全整数）/ listpack | 元素数 > 512（intset）/ > 128（listpack）或含非整数/超长 | hashtable | `set-max-intset-entries` config.c:3600 / `set-max-listpack-entries` config.c:3601 |
| ZSet | listpack | 元素数 > 128 或 member 长度超限 | skiplist + dict 双结构 | `zset-max-listpack-entries` config.c:3603 |
| List | quicklist（内含 listpack） | `list-max-listpack-size` 控制每节点 | quicklist 常驻 | config.c:3511 |

【源码证据】`src/t_hash.c:1561-1601`——写 Hash 时 `hashTypeTryConversion` 检查插入值的长度/数量，超限即 `hashTypeConvert` 整体转换；`src/t_zset.c:1439` 的 `zsetConvert` 与 `t_zset.c:1601` 的 `zsetAdd` 完成 ZSet 的同类分派。**要点：转换是单向整体迁移**（listpack→hashtable），内部一致后查询路径不再分叉判断，这就是"每次转换贵一点、之后所有操作都快一点"的一次性成本。

## 3.3 共享对象与引用计数

【源码证据】`src/object.c:107-116`——`createObject` 只置基本字段；随后 `initObjectLRUOrLFU`（同文件）根据 `maxmemory_policy` 决定 `lru` 字段装载 LRU 时钟还是 LFU 初值。整数 0~9999 在服务器初始化时预建为共享对象（`object.c` 中 `shared.integers`），读命令直接复用、`refcount` 永不回收——注意 `MAXMEMORY_FLAG_NO_SHARED_INTEGERS`（`server.h:707`）：LRU/LFU 模式下不能共享，因为每个对象需要独立的访问时间字段。

## 3.4 键空间：redisDb、kvstore 与 8.x kvobj 重构

**白话**：Redis 的"数据库"不是表也不是命名空间，就是 16 个独立的 dict（`SELECT n` 切换）。每个 db 有两张大表：键空间（key → value）与过期字典（key → 毫秒时间戳）。8.x 正在把后者逐步并入前者。

【源码证据】`src/server.h:1226-1241`——8.10.2 的 redisDb：

```c
typedef struct redisDb {
    kvstore *keys;              /* The keyspace for this DB. As metadata, holds keysizes histogram */
    kvstore *expires;           /* Timeout of keys with a timeout set */
    estore *subexpires;         /* Timeout of sub-keys with a timeout set. (Currently only used for hashes) */
    dict *blocking_keys;        /* Keys with clients waiting for data (BLPOP)*/
    dict *watched_keys;         /* WATCHED keys for MULTI/EXEC CAS */
    int id;                     /* Database ID */
    long long avg_ttl;          /* Average TTL, just for stats */
    unsigned long expires_cursor; /* Cursor of the active expire cycle. */
} redisDb;
```

三个演进点：

1. **kvstore（7.0 引入）**：`kvstore.h:32` 中 `typedef struct _kvstore kvstore;`（实现 opaque）。它是"dict 的 dict"——集群模式下按 16384 个槽位拆成 16384 张小 dict，非集群退化为 1 张。好处：扩容、过期、淘汰都可以按槽分治，迁移数据时不用全表扫描。
2. **subexpires（7.4 Hash Field TTL 的载体）**：字段级过期时间存在独立的 `estore` 里，`HEXPIRE`/`HGETEX` 等命令（`commands/hexpire.json`，7.4 起存在）直接操作它。
3. **kvobj 重构（8.2 起逐步落地）**：`object.h:106-109` 的 `iskvobj` 位与 `metabits` 说明 `redisObject` 正在兼任"键值对元数据容器"——过期等元数据从 expires dict 的 entry 迁移进对象本身，减少一次 dict 查找与指针跳转。阅读 8.x 源码时会看到 `kvobj *dbFind(redisDb *db, sds key)`（`server.h:3914`）这类新 API 与旧 `robj` API 并存，这是重构进行中的正常形态。

【源码证据】`src/db.c:279-330`——所有读写的必经之路 `lookupKey`：

```c
kvobj *lookupKey(redisDb *db, robj *key, int flags, dictEntryLink *link) {
    kvobj *val = dbFindByLink(db, key->ptr, link);
    if (val) {
        /* Forcing deletion of expired keys on a replica makes the replica
         * inconsistent with the master. ... */
        /* ... flags 到 expire_flags 的翻译 ... */
        if (expireIfNeeded(db, key, val, expire_flags) != KEY_VALID) {
            /* The key is no longer valid. */
            val = NULL;
        }
    }
    if (val) {
        /* Update the access time for the ageing algorithm.
         * Don't do it if we have a saving child, as this will trigger
         * a copy on write madness. */
        if (((flags & LOOKUP_NOTOUCH) == 0) && /* ... */) flags |= LOOKUP_NOTOUCH;
        if (!hasActiveChildProcess() && !(flags & LOOKUP_NOTOUCH)){
            if (server.maxmemory_policy & MAXMEMORY_FLAG_LFU) {
                updateLFU(val);
            } else if (!(server.maxmemory_policy & MAXMEMORY_FLAG_LRM)) {
                /* LRM policy should NOT update timestamp on reads. */
                val->lru = LRU_CLOCK();
            }
        }
        /* ... hits/misses 统计 ... */
```

这 30 行浓缩了四条核心机制：读路径上的**惰性过期检查**（`expireIfNeeded`，实现于 `db.c:2999`）、**LRU/LFU/LRM 访问时间更新**（第六章）、**有子进程时暂停触碰**（防写时复制放大，注释原文 "copy on write madness"）、**命中率统计**（INFO keyspace 的 hits/misses 出口）。

## 3.5 本章小结

- type/encoding 双维度 + 阈值转换 = "一个命令、多种实现"的运行时择优；
- redisObject 16 字节对象头挤进了类型、编码、引用计数、LRU/LFU、（8.x）kv 元数据位；
- redisDb = kvstore 键空间 + expires 过期字典 +（7.4+）subexpires 字段级过期；
- lookupKey 是所有命令的十字路口：过期检查、访问时间更新、统计全在这一个函数里。

---

# 四、事件循环与命令执行（ae.c / server.c / networking.c）

> 本章对应源码：`src/ae.c`（及 `ae_epoll.c` 等后端）、`src/server.c`、`src/networking.c`、`src/iothread.c`。

## 4.1 先说白话：单线程为什么快，为什么敢单线程

三个原因：数据全在内存（网络和内存才是瓶颈，不是 CPU）；单线程意味着**零锁、零上下文切换、天然原子**；事件驱动让一个线程就能伺候上万个连接。反过来，它的死穴是"任何一个操作慢了全体排队"，所以你会看到 Redis 到处"分期付款"：渐进式 rehash（2.3）、activeExpireCycle 限时（6.2）、lazyfree 异步释放（6.4）、defrag 按预算挪内存（6.5）、IO 线程分担网络（4.6）。**理解 Redis，本质上是理解一个单线程如何把重活全都碎成小步。**

## 4.2 ae 事件循环：epoll 的薄封装

【源码证据】`src/ae.c:365`（`aeProcessEvents`）与 `src/ae.c:497`（`aeMain`）：

```c
void aeMain(aeEventLoop *eventLoop) {
    eventLoop->stop = 0;
    while (!eventLoop->stop) {
        /* ... beforeSleep / aeProcessEvents ... */
    }
}
```

`aeMain` 就是主线程的一生：循环调用 `aeProcessEvents`，其中先执行 `beforeSleep` 回调（写回包、刷 AOF、处理惰性释放，注册于 `server.c:3216` 的 `aeSetBeforeSleepProc`），再 `aeApiPoll`（按编译平台落到 `ae_epoll.c`/`ae_kqueue.c`/`ae_select.c`）等待文件事件就绪，逐个执行读/写回调，最后处理到期的时间事件。**没有第二层调度**——所有"并发"都是这个循环里排队的事件。

## 4.3 serverCron：后台心跳

**白话**：事件循环里除了 socket，还有一个每秒跑 `hz` 次的定时器，一切"周期性家务"都挂在这里。

【源码证据】`src/server.c:1562`（`serverCron`）注册于 `src/server.c:3201`：

```c
    if (aeCreateTimeEvent(server.el, 1, serverCron, NULL, NULL) == AE_ERR) ...
```

`hz` 默认 10（`config.c:3537`，`CONFIG_DEFAULT_HZ`，可调 1~500）。serverCron 的职责清单（按 8.10.2 函数体顺序）：更新时间缓存与内存统计、`clientsCron` 遍历客户端（超时断开、缓冲区回收）、`databasesCron`（过期采样 + 渐进式 rehash 的推动者）、尝试解除阻塞的客户端、`replicationCron`（第七章）、`clusterCron`/哨兵心跳（第八章）、触发 RDB/AOF 落盘条件检查。

## 4.4 一条命令的一生：readQueryFromClient → processCommand → call

这是全文最重要的一条主线，先给全景：

```
socket 可读
  └→ readQueryFromClient (networking.c:3893)        [IO线程可分担读]
       └→ 解析 RESP：processMultibulkBuffer / processInlineBuffer
            (networking.c:3278 / :3127)
       └→ processCommand (server.c:4467)             [十余道闸门]
            ├→ lookupCommand 查命令表 (server.c:4510)
            ├→ ACL 权限 / 只读从库拒绝写 / maxmemory 淘汰检查 (:4649-4673)
            └→ call (server.c:3992)                  [真正执行]
                 ├→ cmd->proc(c)  ← 例如 t_string.c 里的 setCommand
                 ├→ propagateNow (server.c:3744)     [写命令传播给 AOF/从库]
                 └→ slowlog / 延迟采样 / monitors
       └→ beforeSleep：addReply 累积的回包 writeToClient 写回 socket
```

【源码证据】RESP 协议解析入口 `src/networking.c:3277-3278` 的注释与函数：

```c
 * to be '*'. Otherwise for inline commands processInlineBuffer() is called. */
static int processMultibulkBuffer(client *c, pendingCommand *pcmd) {
```

RESP2 的 `*<N>\r\n` 多批量格式在此逐行解析成 `argv[]`；8.10 引入 `pendingCommand` 结构以支撑 IO 线程模型（4.6）。`processCommand`（`server.c:4467`）里的闸门按序包括：命令存在性、参数个数、AUTH 检查、ACL 权限（`ACLCheckAllPerm`）、只读 replica 拒写、`CMD_DENYOOM` 命令在内存不足时的拒绝（`:4561`），以及关键的 maxmemory 检查：

【源码证据】`src/server.c:4649-4656`：

```c
    /* Handle the maxmemory directive.
     * ... */
    if (server.maxmemory && !isInsideYieldingLongCommand()) {
        int out_of_memory = (performEvictions() == EVICT_FAIL);
```

**淘汰检查是每条写命令的前置动作**，不是后台任务（第六章展开）。`call`（`server.c:3992`）则负责执行前后的横切面：嵌套调用不计慢日志（注释 "from module, exec or LUA to go into the slowlog"）、`cmd->proc(c)` 执行、写命令经 `propagateNow`（`server.c:3744`）传播到 AOF 与所有从库、延迟样本 `latencyAddSampleIfNeeded`、慢日志 `slowlogPushCurrentCommand`。

## 4.5 客户端管理与输出缓冲

每个连接一个 `client` 结构（`src/server.h:1501` 起）：输入侧 `querybuf` 动态增长（有 `client-query-buffer-limit` 上限防恶意大包），输出侧回包先累积在 `c->buf`/`obuf` 再由 beforeSleep 统一 `writeToClient`（`networking.c:2726` 起的 `_writeToClientNonSlave`）——**攒一批再写**，把系统调用次数压到最低。从库与普通客户端的输出缓冲区分开限流（`client-output-buffer-limit` 三段配置：normal/replica/pubsub），从库缓冲区超限会触发断连重同步（第七章）。

## 4.6 IO 线程：从单线程到多线程 IO（6.0 → 8.10）

**白话**：网络收发（syscall + 协议解析）在高速网卡上能吃掉单线程相当比例的时间。6.0 引入 IO 线程：主线程把一批客户端的读/写分派给 N 个线程并行处理，**命令执行仍然全部回到主线程**——并发安全毫不打折。

【源码证据】`src/config.c:3507`：

```c
    createIntConfig("io-threads", NULL, DEBUG_CONFIG | IMMUTABLE_CONFIG, 1, 128, server.io_threads_num, 1, INTEGER_CONFIG, NULL, NULL), /* Single threaded by default */
```

默认 1 = 关闭。8.0 起实现抽为独立模块 `src/iothread.c`（文件头注释自述 "The threaded io implementation"），线程主函数 `IOThreadMain`（`iothread.c:975`）、线程创建（`iothread.c:1041`）。8.10 的模型是"任务队列 + 唤醒"：`iothread.c` 顶部的数组注释直接画出了结构——

```c
/* For main thread */
static list *mainThreadPendingClientsToIOThreads[IO_THREADS_MAX_NUM]; /* Clients to IO threads */
static list *mainThreadProcessingClients[IO_THREADS_MAX_NUM]; /* Clients in processing */
static list *mainThreadPendingClients[IO_THREADS_MAX_NUM];    /* Pending clients from IO threads */
```

即：主线程把 client 派给 IO 线程做协议解析，解析完的命令排队送回主线程执行；解析与执行解耦，但**执行顺序仍由主线程串行保证**。使用建议：4 核以上、QPS 打满、瓶颈在网络收发时才开（`io-threads 4` + `io-threads-do-reads yes`），内存型小实例开了反而增加上下文切换。

## 4.7 本章小结

- ae 事件循环 + beforeSleep + serverCron 三件套，构成 Redis 全部调度；理解了它们，任何子系统都能按"挂在哪个回调上"定位。
- 一条命令的一生 = 读缓冲 → RESP 解析 → processCommand 闸门 → call 执行 → 传播（AOF/从库）→ 攒包写回。
- maxmemory 淘汰发生在每条写命令之前，这是"淘汰是同步代价"的关键认知。
- IO 线程只并行"搬运与解析"，不并行"执行"——这是 Redis 在多核时代保住语义简单性的核心取舍。

# 五、持久化（rdb.c / aof.c / bio.c）

> 本章对应源码：`src/rdb.c`、`src/rdb.h`、`src/aof.c`、`src/bio.c`、`src/config.c`（相关配置项）。

## 5.1 先说白话：快照与日志

两种经典思路：**快照**（某时刻全量状态，恢复快但两次快照之间会丢）与**日志**（每笔操作记下来，重放即可恢复，丢得少但文件大、重放慢）。Redis 两种都给你：RDB 是快照，AOF 是日志，4.1 起还能混搭（AOF 文件头放一段 RDB）。选型口诀：纯缓存开 RDB 就够；当数据库用 RDB+AOF（`aof-use-rdb-preamble yes` 默认已开）。

## 5.2 RDB：fork + 写时复制的快照

**白话**：为什么 SAVE 不卡顿？因为 `BGSAVE` 不是复制数据，而是 `fork()` 一个子进程——操作系统写时复制（COW）让父子进程共享内存页，子进程把"fork 那一瞬间"的内存序列化成文件；此后主进程修改的页会被内核复制，代价只是被改的页。

【源码证据】`src/rdb.c:2223-2232`：

```c
int rdbSaveBackground(int req, char *filename, rdbSaveInfo *rsi, int rdbflags) {
    /* ... */
    if ((childpid = redisFork(CHILD_TYPE_RDB)) == 0) {
```

序列化格式由一组 opcode 组织成流：

【源码证据】`src/rdb.h:101-114`（节选）：

```c
#define RDB_OPCODE_SLOT_INFO  244   /* Individual slot info, such as slot id and size (cluster mode only). */
#define RDB_OPCODE_FUNCTION2  245   /* function library data */
#define RDB_OPCODE_MODULE_AUX 247   /* Module auxiliary data. */
#define RDB_OPCODE_IDLE       248   /* LRU idle time. */
#define RDB_OPCODE_FREQ       249   /* LFU frequency. */
#define RDB_OPCODE_AUX        250   /* RDB aux field. */
#define RDB_OPCODE_RESIZEDB   251   /* Hash table resize hint. */
#define RDB_OPCODE_EXPIRETIME_MS 252    /* Expire time in milliseconds. */
#define RDB_OPCODE_SELECTDB   254   /* DB number of the following keys. */
#define RDB_OPCODE_EOF        255   /* End of the RDB file. */
```

也就是说 RDB 里每条键记录 = `EXPIRETIME? + SELECTDB + type/encoding + key + value`，LRU/LFU 信息随键写入（IDLE/FREQ opcode），文件尾 8 字节 CRC64 校验。全量写入的主循环在 `rdbSaveRio`（`src/rdb.c:2027`），遍历的是 kvstore（集群模式下逐槽写，`SLOT_INFO` opcode 即为此服务）。触发方式三选一：手动 BGSAVE/SAVE、`save <seconds> <changes>` 配置（serverCron 里检查 `saveparams`）、主从全量同步时主库 fork（第七章）。

## 5.3 AOF：写后日志、fsync 策略与重写

**白话**：AOF 与"先写日志再改数据"的 WAL 相反，是**先执行命令、成功后才把命令追加到日志**——好处是不用记录"改了什么"只记录"做了什么"，坏处是执行后崩溃会丢最后一笔。所以丢多少取决于刷盘策略：

【源码证据】`src/server.h:661-663` 与 `src/config.c:3492`：

```c
#define AOF_FSYNC_NO 0
#define AOF_FSYNC_ALWAYS 1
#define AOF_FSYNC_EVERYSEC 2
/* config.c:3492: 默认 AOF_FSYNC_EVERYSEC */
```

- **always**：每条命令都 `fsync`，最多丢 1 条，吞吐最低；
- **everysec**（默认）：后台线程每秒 fsync 一次，最多丢 1 秒——由 bio 线程执行（5.5 节）；
- **no**：交给操作系统，最快，丢失窗口不可控。

写命令进 AOF 的入口在 `call` 的传播链里：`propagateNow`（`server.c:3744`）→ `feedAppendOnlyFile`（`src/aof.c:1661`，把命令还原成 RESP 文本追加进 `aof_buf`）。注意两点：**AOF 记录的是"逻辑命令"**（SPOP 弹出 3 个元素会记成一条带具体元素的 SREM 或原命令 + 结果，保证重放幂等）；**事务/脚本按整体效果记录**（第九章）。

**重写**：日志会无限膨胀（同一个键 INCR 一万次就是一万行），所以定期把当前内存状态"反写"成最短命令序列。`rewriteAppendOnlyFileBackground`（`src/aof.c:3197`）fork 子进程执行 `rewriteAppendOnlyFile`（`src/aof.c:3117`，直接把数据库遍历写成 RDB 编码前导 + 命令流）。重写期间主进程的新写入怎么办？**7.0 多部分 AOF 的答案是"不需要管道"**：新命令照常追加进 `aof_buf` 并刷入当前的 INCR 文件（`feedAppendOnlyFile` 无条件写 `server.aof_buf`，见 `aof.c:1692-1696`），子进程只负责产出新 BASE 文件；子进程完成后父进程原子更新 manifest——新 BASE + 全新空 INCR 上岗，旧 INCR 按 seq 归档。（4.x~6.x 的旧实现是父进程把增量经管道实时喂给重写子进程，那正是 7.0 被多部分 AOF 替换掉的复杂度来源。）

## 5.4 混合持久化与多部分 AOF（7.0）

- **混合持久化**：`aof-use-rdb-preamble` 默认开启（`config.c:3423`）——AOF 重写后的"基础文件"用 RDB 二进制编码（加载快、体积小），之后的增量用 AOF 文本。
- **多部分 AOF（7.0）**：以前重写是"先写 tmp 再原子 rename 单个大文件"，重写中途宕机或磁盘紧张容易翻车。7.0 起拆成 **manifest（清单）+ base（RDB 编码基础文件）+ incr（若干增量文件）**：

【源码证据】`src/aof.c:83-87`——manifest 的键定义：

```c
#define AOF_MANIFEST_KEY_FILE_NAME   "file"
#define AOF_MANIFEST_KEY_FILE_SEQ    "seq"
#define AOF_MANIFEST_KEY_FILE_TYPE   "type"
#define AOF_MANIFEST_KEY_FILE_STARTOFFSET "startoffset"
#define AOF_MANIFEST_KEY_FILE_ENDOFFSET   "endoffset"
```

恢复时按 manifest 顺序先读 base 再逐个读 incr；每执行一轮 `BGREWRITEAOF` 就开启新的 base+incr 序列（seq 递增），旧文件由后台安全删除。这解决了"重写期间半成品文件污染单一大 AOF"的老问题。

## 5.5 bio 后台线程

**白话**：`close(大文件)`、`fsync`、释放巨型对象（如几百万成员的 ZSet）都可能阻塞毫秒级，单线程等不得——所以有 bio（Background I/O）线程池专门干这三类脏活。

【源码证据】`src/bio.c:60-63`——三类任务：

```c
    [BIO_CLOSE_FILE] = 0,
    [BIO_AOF_FSYNC] = 1,
    [BIO_LAZY_FREE] = 2,
```

任务由 `bioSubmitJob` 投递（`bio.c:239/:258/:207` 分别对应关文件/刷盘/惰性释放），消费循环在 `bioProcessBackgroundJobs`（`bio.c:261`），每类任务一条线程（`BIO_WORKER_NUM`，线程句柄数组 `bio.c:69`，创建于 `bio.c:172` 的 `pthread_create`）。**fsync 线程卡住不影响释放线程**。

## 5.6 本章小结

- RDB = fork + COW 快照：恢复快、省事、窗口期丢数据；opcode 流里连 LRU/LFU/槽位信息都有。
- AOF = 写后日志：always/everysec/no 三档权衡；重写 + 管道增量 = 自我瘦身。
- 7.0 多部分 AOF 把"单文件重写"变成"manifest 编排的多文件滚动"，可靠性上一个台阶。
- bio 三线程把 close/fsync/大对象释放移出主线程——与 lazyfree（6.4）共同构成"单线程不干重活"的另一半拼图。

---

# 六、过期、淘汰与内存管理（expire.c / evict.c / lazyfree.c / defrag.c）

> 本章对应源码：`src/expire.c`、`src/db.c`（expireIfNeeded 实现）、`src/evict.c`、`src/lazyfree.c`、`src/defrag.c`。

## 6.1 先说白话：内存满了怎么办

Redis 对"键活多久"分两套机制，作用完全不同：

- **过期（EXPIRE/TTL）**：业务语义——"这个键 60 秒后没有存在意义"。到期就该删，但删除不能卡主线程。
- **淘汰（maxmemory-policy）**：容量保护——内存逼近 `maxmemory` 时腾地方。牺牲的是"没被淘汰的键"来保命，与 TTL 无关。

面试最爱问的"过期键会立刻删吗？内存满了怎么办？"，正确答案分别是"不会，靠惰性+采样"（6.2）和"看策略，大概率采样淘汰近似 LRU/LFU"（6.3）。

## 6.2 过期删除：惰性 + 周期采样

**惰性删除**：读写任何键时先检查过期（`lookupKey` → `expireIfNeeded`，实现 `db.c:2999`），到期即删——不访问就永远不删，所以需要第二只手。

**周期采样**：`serverCron → databasesCron → activeExpireCycle`（`src/expire.c:287`），分两档：

- **SLOW 模式**：随 serverCron 每 100ms/hz 跑一次，预算死死卡住——`ACTIVE_EXPIRE_CYCLE_SLOW_TIME_PERC 25`（`expire.c:98`，注释写明 "Max % of CPU to use"），即最多用掉 25% CPU 时间片；
- 算法：每次随机抽 `ACTIVE_EXPIRE_CYCLE_KEYS_PER_LOOP`（默认 20，`expire.c:96`）个带 TTL 的键，过期即删；**若过期比例 > 10% 就立刻再来一轮**（`ACTIVE_EXPIRE_CYCLE_ACCEPTABLE_STALE 10`，`expire.c:99`）——过期键堆积时会自动加速，反之快速收手。

**从库不主动删**：过期判定权在主库，从库只在读时"标记过期不真删"（`lookupKey` 中那段 "Forcing deletion of expired keys on a replica makes the replica inconsistent with the master" 注释，`db.c:285-289`）——主库删完发 DEL 传播过来才删，保证两边时序一致。7.4 的字段级过期（HEXPIRE）复用同一套思想，元数据挂在 3.4 节介绍的 `subexpires` 结构里。

## 6.3 淘汰：近似 LRU 池、LFU、LRM

**白话**：真 LRU 要维护全局双向链表，每次访问都要 O(1) 挪链表——对亿级键来说链表指针本身就是几 GB。Redis 的方案：**robj 里塞 24 位时钟（LRU）或 8 位对数计数器（LFU），淘汰时随机采样 N 个键、维护一个 16 大小的"最久未用候选池"，从池底挑受害者**。

【源码证据】`src/evict.c:100-125`——著名的"LRU approximation algorithm"注释：

```c
/* LRU approximation algorithm
 *
 * Redis uses an approximation of the LRU algorithm that runs in constant
 * memory. Every time there is a key to expire, we sample N keys (with
 * N very small, usually in around 5) to populate a pool of best keys to
 * evict of M keys (the pool size is defined by EVPOOL_SIZE).
 *
 * The N keys sampled are added in the pool of good keys to expire (the one
 * with an old access time) if they are better than one of the current keys
 * in the pool. ...
 */
```

实现三件套：采样入池 `evictionPoolPopulate`（`src/evict.c:134`）、池结构 `EVPOOL_SIZE 16`（`evict.c:36`）、淘汰主流程 `performEvictions`（`evict.c:532`，就是 4.4 节里每条写命令前调用的那个函数）。时钟基准 `LRU_CLOCK()`（`evict.c:63`）——`server.lruclock` 以 `LRU_CLOCK_RESOLUTION`（毫秒常量 1000，即时钟每秒走一格）缓存更新，访问时间只精确到秒级，换来的是 3 字节/键的存储。

**LFU**（4.0）：`lru` 字段高 16 位存分钟级时间、低 8 位存**对数计数器**（0~255，用概率递增模拟大计数），并带分钟级衰减防"历史热点永久占坑"。初始值 `LFU_INIT_VAL 5`（`server.h:4471`）——新键不给 0，防止刚创建就被淘汰。

**LRM**（8.8 新增）：`volatile-lrm`/`allkeys-lrm`——"最久未修改"优先（`MAXMEMORY_VOLATILE_LRM` 定义于 `server.h:718-719`；引入提交 0cb1ee0dc，2026-01-06）。与 LRU 的唯一差别在 `lookupKey` 里那行注释（3.4 节源码）：**LRM 读命中不更新时间戳**，适合"读多写少、按写入新鲜度淘汰"的场景（配置表、字典类缓存）。

**策略全集**（`config.c:39-50` 的 `maxmemory_policy_enum`）：volatile-lru/lfu/random/ttl/lrm、allkeys-lru/lfu/random/lrm、noeviction（默认，内存满时写命令报 OOM 错误）。`MAXMEMORY_FLAG_*` 位定义于 `server.h:703-719`。

## 6.4 惰性释放 lazyfree

**白话**：DEL 一个百万元素的 ZSet，释放内存本身就要几十毫秒——主线程等不得。`UNLINK`（4.0）先从键空间摘除引用（O(1)），真正的释放丢给 bio 线程；`FLUSHALL ASYNC`、`LAZY FREE` 系列同理。是否值得异步由"释放工作量估算"决定：

【源码证据】`src/lazyfree.c:168` 的 `lazyfreeGetFreeEffort`——小对象同步删（异步反而亏），list/set/zset/hash 大对象按元素计数，**工作量超过 `LAZYFREE_THRESHOLD 64`（`lazyfree.c:226`）才走异步**。另有 `lazyfree-lazy-expire/lazyfree-lazy-user-del` 等配置（`config.c:3410-3414`）可把过期删除、用户 DEL 也异步化（默认关闭）。

## 6.5 碎片整理 activedefrag

**白话**：频繁改写会让 jemalloc 页内出现空洞（分配器统计 `mem_fragmentation_ratio` > 1.5 就该关心）。4.0 起的 `activedefrag` 借助 jemalloc 的 arena 接口，**把老分配挪到新地方再释放旧的**，且严格按 CPU 预算渐进执行——`activeDefragCycle`（`src/defrag.c:2146`）由 serverCron 驱动，每轮限时限量。依赖两个编译/运行条件：jemalloc 分配器（`deps/jemalloc`）与 `activedefrag yes`。

## 6.6 本章小结

- 过期 = 惰性检查（读路径）+ 采样循环（25% CPU 预算，25% 过期率加速）；从库只跟随不主动。
- 淘汰 = 采样 + 16 元素候选池 + 对象头 3 字节元数据：近似 LRU/LFU/LRM 全是"用精度换内存"。
- 释放与整理都渐进化：lazyfree 按"工作量"决定同步/异步，defrag 按预算挪内存。
- 生存期三件套（expire/evict/lazyfree）全部围绕 3.4 节的 lookupKey 与 4.3 节的 serverCron 展开——把第四、六两章连起来读，Redis 的"内存世界观"就完整了。

# 七、复制与哨兵（replication.c / sentinel.c）

> 本章对应源码：`src/replication.c`、`src/sentinel.c`。

## 7.1 先说白话：热备与自动切换

复制解决"数据单点"：从库连上主库，主库把写命令实时转发过来，从库就是一份准实时热备。哨兵（sentinel）解决"主库挂了谁顶上"：几个哨兵进程盯着主从拓扑，多数派确认主库下线后，自动从从库里选一个提为新主，并让其余节点改挂新主。**哨兵不是集群**——它只管高可用，不管数据分片（那是第八章）。

一个关键架构事实：**从库与主库之间、哨兵与被监控节点之间，全都是普通 RESP 客户端/服务端关系**。`SYNC`/`PSYNC` 不过是几条特殊命令，哨兵靠 `INFO`、`PUBLISH`（`__sentinel__:hello` 频道）、`PING` 三板斧工作——所以源码里没有"复制协议框架"，只有普通的 client 与命令实现。

## 7.2 复制建立：FULLRESYNC vs PSYNC 增量

**白话**：从库第一次连线（或断线太久）只能拿全量——主库 BGSAVE 生成 RDB 发过去；断线不久则可以"续传"：主库把断线期间的写命令从环形缓冲区（backlog）补发即可。

握手全流程（2.8 的 PSYNC 起，7.0 PSYNC2 完善化）：

1. 从库发 `PSYNC <replid> <offset>`；`? -1` 表示"我是新来的，给我全量"；
2. 主库核对 replid 与 offset，若自己的 backlog 还留着那段历史 → 回 `+CONTINUE <offset>`，**增量续传**；
3. 否则回 `+FULLRESYNC <replid> <offset>`，走全量：主库 fork RDB 子进程（复用 5.2 的 `rdbSaveBackground`，无盘模式下直接把 RDB 流写到 socket）→ 从库清空自己的库、加载 RDB → 之后主库把 backlog 中的增量命令流补上。

【源码证据】`src/replication.c:1209`（`syncCommand`，即主库侧处理 SYNC/PSYNC 的命令实现）、`:1007`（`masterTryPartialResynchronization`，判定能否 +CONTINUE）。replication ID 存于 server 全局结构（`server.h:2423` `char replid[CONFIG_RUN_ID_SIZE+1];`）。

【源码证据】`src/config.c:3585`——backlog 大小（决定断线容忍窗口）：

```c
    createLongLongConfig("repl-backlog-size", NULL, MODIFIABLE_CONFIG, 1, LLONG_MAX, server.repl_backlog_size, 1024*1024, MEMORY_CONFIG, NULL, updateReplBacklogSize), /* Default: 1mb */
```

**1MB 默认 backlog 是最常被低估的配置**：主库写入速率 × 期望容忍的断线时长 > backlog 大小，就会在从库重连时被迫全量同步。

## 7.3 命令传播与心跳

主库每执行一条写命令，`call → propagateNow`（`server.c:3744`）会把它喂给两个消费者：AOF 缓冲（第五章）与所有从库的输出缓冲——`replicationFeedSlaves`（`src/replication.c:652`）。从库回传的数据流则统一走 `replicationFeedStreamFromMasterStream`（`replication.c:787`）按 RESP 流重组。心跳有两条：主库默认每 10 秒 PING 从库（`repl-ping-replica-period`），从库每秒回报 `REPLCONF ACK <offset>`——主库用它计算每个从库的落后字节数（INFO replication 的 `lag`），也是 `WAIT` 命令（同步复制语义，等待 N 个从库确认到某 offset）的实现基础。周期性驱动统一收口在 `replicationCron`（`src/replication.c:5078`，serverCron 每轮调用）。

## 7.4 哨兵：S_DOWN/O_DOWN、Leader 选举与故障转移状态机

哨兵是独立进程（`redis-server --sentinel` 启动，代码同仓库 `sentinel.c`），每个哨兵监控一组主从。判定与切换全流程：

1. **主观下线（S_DOWN）**：超过 `down-after-milliseconds` 没收到有效 PING 回复，单个哨兵认为该实例挂了——`sentinelCheckSubjectivelyDown`（`src/sentinel.c:4580`）。
2. **客观下线（O_DOWN）**：该哨兵询问其他哨兵（`SENTINEL is-master-down-by-addr`），**达到 quorum 票数**才升级为"客观下线"——`sentinelCheckObjectivelyDown`（`sentinel.c:4654`）；状态位 `SRI_S_DOWN/SRI_O_DOWN`（`sentinel.c:47-49`）。
3. **哨兵 Leader 选举**：O_DOWN 后哨兵们互相投票选出一个 Leader 来执行切换——`sentinelVoteLeader`（`sentinel.c:4792`），协议是简化版 Raft（先到先得 + 任期 epoch，多数派确认）。
4. **选新主**：按 从库优先级（`slave-priority`，默认 100，`sentinel.c:80`）→ 复制 offset 最大 → runid 字典序 依次择优。
5. **执行切换**：向新主发 `SLAVEOF NO ONE`、其余从库改挂新主、通知客户端（发布到 `+switch-master` 频道）、老主回归后自动降为从库——整条流水线是显式状态机 `sentinelFailoverStateMachine`（`sentinel.c:5374`）。

【源码证据】默认参数一瞥（`sentinel.c:80-87`）：`SENTINEL_DEFAULT_SLAVE_PRIORITY 100`、`SENTINEL_DEFAULT_PARALLEL_SYNCS 1`（切换时同时向新主发起重同步的从库数，避免压垮新主）等。

**实践提醒**：哨兵至少 3 个且奇数部署（脑裂防护靠多数派）；`down-after-milliseconds` 过小会误判网络抖动；quorum 只决定"确认下线"，切换仍需哨兵 Leader 多数派——两个多数派含义不同。

## 7.5 本章小结

- 复制 = 全量（RDB）打底 + 增量（backlog 续传）+ 命令流传播；replid/offset 两个坐标决定续传成败。
- 所有复制流量都复用普通客户端管道——没有专用协议栈，只有专用命令。
- 哨兵把"监控→仲裁→选主→改拓扑"做成显式状态机，两个多数派（quorum 与 Leader 选举）分别防误报与脑裂。
- PSYNC2 之后从库级联（slaveof 从库）也能续传，故障转移后客户端凭 `+switch-master` 通知重定向。

---

# 八、集群（cluster.c / cluster_legacy.c / cluster_asm.c）

> 本章对应源码：`src/cluster.h`、`src/cluster_legacy.c`（经典实现，7.4 拆分自 cluster.c）、`src/cluster_asm.c`（8.4 起新实现）。

## 8.1 先说白话：数据分片 + 内置高可用

单机内存有上限，数据要水平拆。Redis Cluster 的方案：**把键空间划成 16384 个槽（slot），`CRC16(key) mod 16384` 决定归属**；每个主节点负责一段槽，主节点配从节点做高可用——相当于把第七章的"主从 + 哨兵"内建进了节点本身（集群模式下不需要哨兵）。客户端可以直连任意节点，键不在本节点就拿到 `MOVED` 重定向。

为什么是 16384？antirez 在集群规范里解释过：心跳包要携带槽位 bitmap，16384/8 = 2KB，规模上限约 1000 节点足够；再多槽位数会白白放大心跳成本。

【源码证据】`src/cluster.h:23` 与 `cluster_legacy.h:137`：

```c
#define CLUSTER_SLOTS (1<<CLUSTER_SLOT_MASK_BITS) /* Total number of slots in cluster mode, which is 16384. */
/* cluster_legacy.h:137: */
    unsigned char slots[CLUSTER_SLOTS/8]; /* Slots bitmap. */
```

## 8.2 节点地图与 gossip 协议

集群里每个节点维护一张全集群节点表 `clusterNode`（`cluster_legacy.h:300` 起的 `struct _clusterNode`，含名字 40 字节十六进制、flags、连接信息、槽位 bitmap、主从指针）。节点间通信走**集群总线**（端口 = 客户端端口 + 10000），协议消息类型一瞥：

【源码证据】`src/cluster_legacy.h:96-101`：

```c
#define CLUSTERMSG_TYPE_PING 0          /* Ping */
#define CLUSTERMSG_TYPE_PONG 1          /* Pong (reply to Ping) */
#define CLUSTERMSG_TYPE_MEET 2          /* Meet "let's join" message */
#define CLUSTERMSG_TYPE_FAIL 3          /* Mark node xxx as failing */
#define CLUSTERMSG_TYPE_PUBLISH 4       /* Pub/Sub Publish propagation */
#define CLUSTERMSG_TYPE_FAILOVER_AUTH_REQUEST 5 /* May I failover? */
```

心跳节奏：`clusterCron`（`cluster_legacy.c:4820`）每 100ms 跑一轮，随机挑节点发 PING；每条 PING/PONG 里捎带（gossip）若干其他节点的状态（PFAIL 判断的选票来源）。**故障检测是两级的**：超过 `cluster-node-timeout` 没响应 → 标记 PFAIL（疑似下线）；当**过半主节点**都报 PFAIL → 升级 FAIL——`markNodeAsFailingIfNeeded`（`cluster_legacy.c:1935`）内含多数派计算并广播 `FAIL` 消息。

## 8.3 MOVED/ASK 与智能客户端

键不在本节点时：槽稳定归属他节点 → 回 `-MOVED <slot> <ip:port>`（让客户端永久换节点，`CLUSTER_REDIR_MOVED` 定义于 `cluster.h:36`）；槽正在迁移中、键已搬走 → 回 `-ASK`（本次临时去新节点问，必须先发 ASKING）。哈希标签 `{user1000}.profile` 强制整键进同一槽，是跨键 MULTI/Lua 的唯一合法姿势。生产环境客户端（Lettuce/Jedis/go-redis）都是"smart client"：启动时 `CLUSTER SLOTS` 拉全拓扑缓存本地，MOVED 仅作纠偏。

## 8.4 从节点竞选：故障转移

主节点 FAIL 后，它的从节点们竞争接班：各自广播 `FAILOVER_AUTH_REQUEST`（`cluster_legacy.h:101`），**由其他主节点投票**（每主一票、按 epoch 递增防重复），拿到多数派后在集群内宣布接管槽位——与哨兵的"哨兵选 Leader"不同，这里投票人是**数据节点的主节点们**。竞选有 offset 门槛（数据太旧的从库没资格），整个过程与 gossip 故障检测共用 100ms 心跳节拍，通常秒级完成。

## 8.5 槽位迁移：经典流程与 8.4 原子迁移 ASM

**经典流程（cluster_legacy.c）**：`CLUSTER SETSLOT <slot> MIGRATING <node>` / `IMPORTING` 标记 → `CLUSTER GETKEYSINSLOT` 逐键 `MIGRATE` 原子搬运（源节点阻塞序列化 + 目标 RESTORE）→ 完成后各节点 `SETSLOT NODE` 定格。逐键搬运在负载高时容易超时、出现"半搬槽"状态，这是老牌痛点。

**ASM（Atomic Slot Migration，8.4 新增 `src/cluster_asm.c`，文件头注释直接给出七步流程）**：

```
1. DESTINATION 发起 CLUSTER MIGRATION IMPORT <slots>
2. SOURCE fork 子进程，把槽快照以 RDB/RESTORE 形式发送
3. SOURCE 经主通道转发快照期间的新写入（增量流）
4. DESTINATION 应用快照并缓冲增量
5. DESTINATION 接近追平时，SOURCE 短暂暂停这些槽的写入
6. DESTINATION 清空缓冲、接管槽位
7. 经集群总线原子广播新归属，全集群同步生效
```

本质是把"逐键搬运"升级为"**快照 + 增量追平 + 短暂停写**"的主从复制式搬迁——与 5.2 节的 fork/COW 思路同源。8.10 中 legacy 与 ASM 双实现并存，由集群配置选择。

## 8.6 本章小结

- 16384 槽 + CRC16 取模 = 无中心元数据的分片；键路由错误由 MOVED/ASK 协议自我修复。
- gossip PING/PONG + 两级故障检测（PFAIL→FAIL 多数派）= 去中心化的成员管理与故障发现。
- 故障转移投票人是主节点多数派；槽迁移正在从"逐键 MIGRATE"演进为"复制式快照追平"（ASM）。
- 集群 = 分片 + 内建哨兵语义，是第七章的自然延伸；理解了第七章，集群的高可用部分就是"换了个投票人"。

# 九、扩展机制：事务、脚本与函数、模块、Pub/Sub、Stream（multi.c / eval.c / functions.c / module.c / pubsub.c / t_stream.c）

> 本章对应源码：`src/multi.c`、`src/eval.c`、`src/functions.c`、`src/module.c`、`src/pubsub.c`、`src/t_stream.c`、`src/stream.h`、`src/acl.c`、`src/tracking.c`、`modules/vector-sets/`。

## 9.1 MULTI/EXEC 与 WATCH：没有回滚的"排队事务"

**白话**：MULTI 之后客户端的命令不再立即执行，而是进队列；EXEC 时主线程一口气串行执行完——中间不会插入其他客户端的命令。注意 Redis 事务**没有回滚**：入队时检查语法与权限（命令不存在/参数个数错误会让整个 EXEC 失败），但执行期的类型错误只跳过该条、继续执行后续——官方立场：运行时错误是编程 bug，回滚救不了它，还慢。

【源码证据】`src/multi.c:91`（`multiCommand` 入队开关）、`:127`（`execCommand`，执行队列并清理 mstate）、`:488`（`watchCommand`）。**WATCH 是乐观锁**：把键登记进 `redisDb.watched_keys`（3.4 节 redisDb 结构里那行 `dict *watched_keys;`），任何客户端写该键都会 `touchWatchedKey`（`multi.c:389`）给观察者打上 CLIENT_DIRTY_CAS 标记，EXEC 时发现标记直接返回 nil（放弃）。配合 GET + 修改 + EXEC 的重试循环，就是无锁 CAS。

## 9.2 Lua 脚本与函数库：服务器端原子执行

**白话**：复杂的多步逻辑（读-判断-写）要原子执行，靠事务排队 + WATCH 太绕。把一段 Lua 发给服务器，主线程执行期间不接受其他命令，天然原子；而且脚本在"数据所在地"执行，省了网络往返。7.0 的 Functions（`FUNCTION LOAD`）把"一次性 EVAL"升级为"服务器常驻函数库"，支持按库管理、持久化进 RDB/AOF（RDB 里就有 `RDB_OPCODE_FUNCTION2`，5.2 节）。

【源码证据】命令入口：`src/eval.c:633`（`evalCommand`）与 `src/functions.c:661`（`fcallCommand`，只读变体 `fcallCommandGeneric` 在 `functions.c:619`）。Lua 引擎是 `deps/lua`（5.1 定制版，禁用危险 API、加入随机种子沙箱）。**复制语义**：7.0 起脚本默认按"效果复制"（effect replication）——不再把脚本文本原样发给从库重放，而是把脚本执行期间产生的实际写命令序列传播（依赖 4.4 节的 propagate 通道），从库与 AOF 只看到普通命令，规避了"随机命令（TIME/随机键）在两边结果不同"的经典坑。

## 9.3 模块 API：Redis 的"内核模块"

**白话**：Redis 用 C ABI 暴露了一套 `RedisModule_*` API——模块加载后可以注册新命令、新数据类型（带 RDB/AOF 编码回调）、钩进键空间通知、甚至接管请求处理。官方的 vector-sets（8.0，九.9.7）就是以模块形式内置的，`modules/MODULES.md` 有完整说明。

【源码证据】`src/module.c:13665` 的 `moduleLoad`（dlopen 动态库 → `RedisModule_OnLoad` → 注册命令/类型）。模块数据类型的持久化契约：模块必须实现 RDB 的 save/load 与 AOF 的 rewrite 回调，由核心在持久化时机统一调用——这就是 5.2 节 `RDB_OPCODE_MODULE_AUX` 的消费者。

## 9.4 Pub/Sub 与 Sharded Pub/Sub

**白话**：SUBSCRIBE/PUUBLISH 是"发后即忘"的广播——不落盘、不确认，断线的订阅者就是错过消息；适合信号通知，不适合可靠队列（那是 Stream 的事）。传统 Pub/Sub 的订阅关系全集群广播，7.0 加了按槽分片的 `SSUBSCRIBE/SPUBLISH`：消息只在槽归属节点链路里传播，集群规模下省掉跨节点洪泛。

【源码证据】`src/pubsub.c:542`（`subscribeCommand`）、`:620`（`publishCommand`）、`:721`（`spublishCommand`）；实现核心 `pubsubPublishMessage`（`pubsub.c:533`，sharded 参数分叉）与 `pubsubPublishMessageInternal`（`:469`）。集群模式下 PUBLISH 经总线 `CLUSTERMSG_TYPE_PUBLISH`（8.2 节协议表第 4 号）跨节点传播。RESP3 之后订阅消息改用 Push 类型帧（RESP3 的 Push 帧族，如 `addReplyPushLen`），客户端库不再需要"抢普通响应"。

## 9.5 Stream：内存里的消息队列

**白话**：5.0 的 Stream 是 Redis 对"消息队列"需求的正面回答：消息有单调递增 ID（`毫秒-序号`）、不可变、按序存储；消费组（consumer group）支持组内竞争消费，`PEL`（pending entries list）记录"已投递未确认"，`XACK` 确认后消除——这就是一个带 at-least-once 语义的轻量队列。

【源码证据】`src/stream.h:36-46`——存储骨架是 rax 基数树：

```c
typedef struct stream {
    rax *rax;               /* The radix tree holding the stream. */
    uint64_t length;        /* Current number of elements inside this stream. */
    streamID last_id;       /* Zero if there are yet no items. */
    /* ... */
    rax *cgroups;           /* Consumer groups dictionary: name -> streamCG */
    rax *cgroups_ref;       /* Index mapping message IDs to their consumer groups. */
    /* ... */
} stream;
```

消息本体是 listpack 节点（共享字段压缩：`STREAM_ITEM_FLAG_SAMEFIELDS`，`src/t_stream.c:19-21` 的三个 item flag），`XADD`（`t_stream.c:2537`）入队 + 可选 `streamTrim`（`:851`，MAXLEN/MINID 修剪，近似修剪 `~` 不卡顿）；消费组结构 `streamCG`（`stream.h:93`）持有 last_id 与消费者/PEL；`XREADGROUP`（`t_stream.c:2778` 起 `xreadCommand`）支持 BLOCK 阻塞——阻塞实现走 3.4 节 `blocking_keys` 登记制，新消息到达时唤醒。

## 9.6 客户端缓存 tracking 与 ACL

- **Client tracking（6.0，RESP3）**：客户端 `CLIENT TRACKING on` 后，服务器记住它读过哪些键；这些键被其他连接修改时，服务器用 RESP3 Push 帧主动发 `invalidate` 消息——客户端据此失效本地缓存。这是"进程内缓存 + Redis 失效广播"的官方解法，实现于 `src/tracking.c`（6.0 起存在）。
- **ACL（6.0）**：多用户 + 命令白名单/键模式/密码三重控制。`src/acl.c:1299` 的 `ACLSetUser` 是规则解析核心（`+set -@dangerous ~cache:*` 这类 DSL）；默认 `default` 用户兼容旧世界。每条命令执行前在 `processCommand` 的闸门里校验（4.4 节）。

## 9.7 向量集 Vector Sets（8.0）：官方模块化范例

8.0 内置的向量集（`VADD/VSET/VSIM` 等命令，实现位于 `modules/vector-sets/`，核心是 `hnsw.c` 的 HNSW 近似最近邻索引 + `expr.c` 的过滤表达式）有两个教学价值：一是 Redis 原生有了向量检索能力（AI 时代的基本盘）；二是它是"新数据类型该怎么做"的官方参考答案——以模块实现、随内核分发、命令 JSON 声明进 `src/commands/`（8.10 共 459 个命令 JSON 文件即命令文档的单一事实来源）。

## 9.8 本章小结

- 事务给"排队执行 + 乐观锁"，脚本给"服务器端原子 + 效果复制"，Stream 给"持久化 + 消费组 + 确认"——原子性与可靠性的需求梯度上，Redis 逐级给了工具。
- 模块 API + 内置 vector-sets 证明：数据类型的边界不再是"Redis 官方决定什么"，而是"你能用 C ABI 实现什么"。
- Pub/Sub（信号）与 Stream（队列）的语义分界：不落盘的广播 vs 带确认的有序日志——选错场景是 Stream 最常见的误用。
- tracking/ACL 表明 6.0 之后 Redis 认真回答了两个工程问题：客户端缓存的失效一致性与多租户权限。

# 十、高可靠保障：部署形态、关键配置与运维实践（面向分布式基础设施）

> 本章对应源码：`src/config.c`（配置项定义与默认值）、`src/server.c`（写入闸门）、`src/replication.c`（WAIT/WAITAOF 与副本健康检查）、`src/sentinel.c` 与 `sentinel.conf`（哨兵默认参数）、`redis.conf`（生产默认模板）。行号均为 8.10.2 实测。
>
> **本章定位**：前九章讲"机制是什么"，本章回答"如何把这些机制组织成一个可以托付业务的高可用基础设施"——部署形态怎么选、哪些配置在防丢数据、运维要盯什么，以及最后一节专门写给**要在 Redis 之上构建分布式并发原语（分布式锁、信号量、队列）的读者**：Redis 给你什么保证、不给什么、残余风险怎么兜。

## 10.1 可靠性模型：先算清楚"丢数据窗口"

**白话**："可靠"必须拆成三个具体问题：进程崩了丢多少？机器掉电丢多少？主从切换丢多少？前两个由持久化回答，第三个由复制回答——而**第三个窗口是异步复制架构的固有属性，无论怎么配置都不可能归零**，只能压缩。

三类故障与数据去向：

| 故障场景 | 内存数据 | 已 fsync 的 AOF | 已复制到从库的 | 丢失窗口 |
|---|---|---|---|---|
| 进程崩溃（OOM/kill） | 丢 | 保 | 保 | 持久化配置决定 |
| 机器掉电/内核崩溃 | 丢 | `appendfsync always` 保 / `everysec` 最多丢 1 秒 | 保 | ≤ 1 条或 ≤ 1 秒 |
| **主库宕机触发切换** | — | — | **未同步部分丢** | **= 该从库的复制 lag** |

为什么主从切换必然有窗口？回看 4.4 节的传播链：`propagateNow`（`server.c:3744`）只是把命令**塞进各从库的输出缓冲**，TCP 异步送达；从库每秒才回报一次 `REPLCONF ACK`（7.3 节）。客户端收到 SET 的 `+OK` 时，命令可能还在主库的缓冲里没出网卡。此时主库猝死、哨兵把 lag=800ms 的从库提升为新主——那 800ms 内所有"已确认"的写入凭空消失。

**CAP 定位**：哨兵与集群在分区下都选择**可用性优先（AP）**——分区时照常选举、照常服务，代价是新旧主可能短暂并存（脑裂窗口）与已确认写丢失。把这个契约内化，是后续一切配置与原语设计的出发点。

## 10.2 部署形态：拓扑选型与部署铁律

**白话**：可靠性首先是**故障域的物理安排**，其次才是参数调优。同宿主机的"高可用"是自欺。

| 形态 | 故障转移 | 数据分片 | 适用 | 关键风险 |
|---|---|---|---|---|
| 单机 | 无 | 无 | 开发、纯缓存（可重建） | 单点 |
| 主从 + 手动切换 | 人工 | 无 | 低要求场景 | RTO=人到场时间 |
| **主从 + 哨兵** | 自动（秒~十秒级） | 无 | 数据 ≤ 单机内存；**并发原语首选** | 异步复制窗口 |
| Cluster | 自动（节点内建） | 16384 槽 | 数据/写入超单机上限 | 迁移期限制、多键操作受限 |
| 云托管（multi-AZ） | 服务方代管 | 视产品 | 无专职运维 | 语义差异需核对（如是否异步复制、切换策略） |

**哨兵部署铁律**：

1. **至少 3 个、奇数个哨兵**，且**与主库/从库不同故障域**（不同宿主机、机架、AZ）。2 个哨兵无法形成多数派（第七章两轮投票都要求多数）；哨兵与主库同宿主机等于把裁判和运动员绑在同一颗雷上。
2. `quorum` 只决定"确认主库下线"的票数门槛，**切换执行仍需哨兵 Leader 获多数派**——两个多数派语义不同，配置时都要满足 `≥ N/2 + 1`。
3. 每个哨兵独立配置 `sentinel monitor`，网络分区下不要求哨兵彼此全连通，但要求能与主从通信。

**Cluster 部署铁律**：每个主库至少 1 个从库且**从库与主库不同故障域**；`cluster-migration-barrier` ≥ 1（默认 1，`config.c:3513`，控制"孤立主库"可被从库迁移救场的门槛）。

**分集群铁律（爆炸半径控制）**：核心业务（价格/库存/订单类强一致缓存）与非核心业务（配置、描述类缓存）**独立部署集群**——单一集群故障不再波及全部业务，也为差异化一致性策略（10.6 节第一层）创造条件：核心集群开写保护与同步确认，非核心集群纯可用性优先。

**典型错误形态清单**（每一条都见过生产事故）：

- ❌ 2 个哨兵（或 3 个但 2 个与主库同宿主机）——分区时切不动或乱切；
- ❌ 主从同机架/同 AZ——机架掉电=全没；
- ❌ 把读写分离的"从库读"当成强一致读——从库 lag 期间读到旧值（7.2 节从库连过期键删除都跟随主库，可见其"旧"是设计使然）；
- ❌ Cluster 配 `cluster-require-full-coverage no`（`config.c:3420`，默认 yes）再把其当关键存储——该选项允许"部分槽无主时其余槽继续服务"，对缓存合理，对一致性敏感场景等于局部静默丢数据；
- ❌ K8s 上没配 PodDisruptionBudget/反亲和——节点滚动时主从一起蒸发。

**K8s/云环境补充**：Pod 反亲和（主从不同节点）、PDB 限制同时驱逐数、PV 的可用区绑定（StatefulSet 重建后卷必须还连着原 AZ 的副本）、托管服务务必核对切换语义（是否等从库追平、切换通知机制）。

## 10.3 关键配置：把丢数据窗口压到最小的旋钮

**白话**：配置分四组——持久化管"重启不丢"，复制管"切换不丢"，哨兵/集群参数管"切得稳"，内存淘汰管"键不被悄悄拿走"。对并发原语底座，后两组比前两组更致命。

### A. 持久化组（防"重启丢"）

| 配置 | 默认 | 建议（当数据库用） | 位置 |
|---|---|---|---|
| `appendonly` | **0（关）** | **yes** | config.c:3435 |
| `appendfsync` | everysec | everysec（每秒）/ always（每条，仅极端正确性要求） | config.c:3492 |
| `aof-use-rdb-preamble` | yes | 保持 | config.c:3423 |
| `stop-writes-on-bgsave-error` | yes | 保持（快照失败即拒写，暴露问题而非静默） | config.c:3407 |
| `save`（RDB 自动快照） | 3600/1、300/100、60/10000 三档 | 保持（AOF 之外的双保险） | server.c:2489-2491 |

【源码证据】RDB 默认三档保存在 `initServerConfig` 中注册（`server.c:2489-2491`）：`save after 1 hour and 1 change / 5 minutes and 100 changes / 1 minute and 10000 changes`。注意 `appendonly` 默认是**关**的（`config.c:3435`）——"当数据库用必须手动打开"是新手最常见的坑。

### B. 复制与脑裂防护组（防"切换丢"，本章最重要的四行配置）

```
min-replicas-to-write 1
min-replicas-max-lag 10
repl-backlog-size <写入速率 × 期望容忍断线秒数>
repl-timeout 60
```

【源码证据】前两项默认值是 **0 / 10（即关闭防护）**（`config.c:3538-3539`，旧名 `min-slaves-to-write`）。生效点在 `processCommand`：每条写命令前调用 `checkGoodReplicasStatus()`（`server.c:4715`，实现于 `replication.c:4818`），不达标直接拒绝：

```c
        "-NOREPLICAS Not enough good replicas to write.\r\n"));
```

（`server.c:2254`）

机制解读：配 `min-replicas-to-write 1 + min-replicas-max-lag 10` 后，主库一旦发现没有任何从库 lag < 10 秒，就**停止接受写入**。于是脑裂时旧主最多"独自服务 10 秒"就自我静默——把双主并存的丢写窗口从"无限"压到"约一个 max-lag"。**代价**：所有从库都慢/断时写入不可用——这是用可用性换一致性，对锁类原语是笔划算的交易。

`repl-backlog-size`（默认 1MB，`config.c:3585`）按 7.2 节公式估算：峰值写入 20MB/s、想容忍 30 秒断线 → 至少 600MB。backlog 不够大，从库网络抖动一次就是一次全量同步（fork + COW 内存翻倍 + 网络风暴）。`repl-diskless-sync` 在 8.x 已默认开启（`config.c:3416`，值为 1）——全量同步直接流式写 socket，不再落盘中间文件。

两点语义边界：① 防护是**常态生效**的——只要新鲜从库不足就拒写，不是"切换期间才开启"，这恰是"从根源防双主双写"的原因；② 分区期间旧主若仍接受了写入，回归后作为从库会**先清空自己**再向新主同步（`replication.c:2214` 的 `emptyData`）——数据最终"自愈"，但业务侧可能已基于脏数据做了决策，所以拒写配置必须与 10.4 的双主/差异监控配套，不能指望事后自愈。

### C. 哨兵与 Cluster 参数组（防"切得乱"）

| 配置 | 默认 | 权衡 | 位置 |
|---|---|---|---|
| `down-after-milliseconds` | 30000 | 调小：切换快但易误判网络抖动；调大反之。锁场景可适当调小（如 5s~10s）缩短双主窗口 | sentinel.conf:133 |
| `parallel-syncs` | 1 | 保持 1：切换后从库逐个重同步，不压垮新主（`SENTINEL_DEFAULT_PARALLEL_SYNCS`，sentinel.c:81） | sentinel.conf |
| `failover-timeout` | 180000 | 故障转移整体超时，一般不动 | sentinel.conf:233 |
| `cluster-node-timeout` | 15000 | 调小故障检测快，但网络抖动误判 PFAIL 增多 | config.c:3574 |
| `cluster-replica-validity-factor` | 10 | 从库数据太旧（lag 超过 timeout×factor）则丧失竞选资格——**这是集群版的数据新鲜度门槛** | config.c:3510 |
| `cluster-require-full-coverage` | yes | 基础设施保持默认 yes | config.c:3420 |

### D. 内存与淘汰组（并发原语的生命线）

```
maxmemory <物理内存 × 0.7~0.8>
maxmemory-policy noeviction
```

【源码证据】`maxmemory` 默认 0（不限，`config.c:3589`），`maxmemory-policy` 默认 noeviction（`config.c:3491`）。

**一段必读的警告**：如果你的 Redis 上有分布式锁/信号量，**永远不要把淘汰策略设成 allkeys-lru/lfu/random 中的任何一个**——任何键（包括正在持有的锁）都可能被驱逐，锁的消失等于瞬间"双持锁"或"信号量超发"。volatile-* 同样危险：锁键为了防持有者崩溃而必然带 TTL，恰好落在 volatile 淘汰的候选集里，volatile-ttl 甚至会优先淘汰"最先到期的锁"。**正确姿势**：noeviction + 容量规划 + `evicted_keys` 指标告警（只要它 >0，锁类业务就该当事故处理）。

## 10.4 运维实践：监控、备份、变更与演练

**白话**：配置是一次性的，运维是每天的事。四件事：盯指标、做备份、管变更、常演练。

**监控清单（建议全部接入告警）**：

| 指标 | 来源 | 告警含义 |
|---|---|---|
| `master_link_status != up` | INFO replication | 从库断连，可靠性降级 |
| 复制 lag（master_repl_offset − slave_repl_offset） | INFO replication | lag 逼近 min-replicas-max-lag 即将拒写 |
| `aof_delayed_fsync` 增长 | INFO persistence（server.c:6660） | everysec 刷盘被阻塞，丢秒级数据风险 |
| `rdb_last_bgsave_status` / `aof_last_write_status` | INFO persistence | 持久化失败（常因磁盘满） |
| `evicted_keys` 增长 | INFO stats | 锁被淘汰的前兆（10.3-D） |
| `mem_fragmentation_ratio` | INFO memory | >1.5 考虑 activedefrag（6.5 节） |
| `rejected_connections`、连接数 | INFO stats | 连接耗尽 |
| LATENCY 事件 / slowlog | LATENCY HISTORY、SLOWLOG GET | 命令级卡顿（4.1 节单线程的敌人） |
| 哨兵事件 +switch-master / +failover | 哨兵发布订阅 | 发生过切换——事后核对丢失窗口 |
| 同主从组内 `role=master` 节点数 >1（双主检测） | 巡检各节点 INFO replication 的 role 字段 | 脑裂正在发生，立即人工介入与数据核对 |

**备份**：RDB 是唯一能防"误操作"的防线（`FLUSHALL`、误删、恶意清库——AOF 会忠实地把这些也重放出来，只有"上一次快照"能救回）。做法：每日 BGSAVE 后把 RDB 拷往对象存储/异地，AOF 按小时归档；**恢复流程必须演练过**（没演练的备份等于没有备份）。

**变更管理**：版本升级走"从库先升 → 手动 failover → 原主以新版本重新加入"；配置变更用 `CONFIG SET` 验证后 `CONFIG REWRITE` 落盘；危险命令用 `rename-command`（`config.c:591`）改名或直接用 ACL 禁掉（9.6 节）——`KEYS *`、`FLUSHALL`、`CONFIG SET maxmemory-policy` 这类命令不该出现在业务账号的权限里。

**故障演练**：定期 kill 主库，实测两个数——**RTO**（从 kill 到新主可写的秒数）与 **RPO**（切换后丢失的最后写入距崩溃的时刻差）。这两个实测值就是你对外承诺的 SLA，比任何文档都可信。演练还要包括：拔网线观察脑裂时 NOREPLICAS 是否如期触发、哨兵被分区一半时是否正确地"切不动"。

## 10.5 在 Redis 上构建分布式并发原语：可靠性契约

**白话**：这是本章的落点。把 Redis 当高可用基础设施，最常见的用途就是在上面造锁、信号量、计数器。先立契约：**Redis 的复制是异步的、仲裁是多数派的、存储是可淘汰的——它给的是"概率极高的互斥"，不是"数学意义上的互斥"**。工程要做的是把残余风险压到业务可接受，并为压不掉的部分设计兜底。

### 最小正确实现（效率锁）

```
加锁：SET lock:order:123 <唯一token> NX PX 30000
释放：Lua 脚本：if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) else return 0 end
```

三个要素缺一不可：**NX**（互斥）、**PX**（持有者崩溃后锁必然过期，防死锁）、**唯一 token + Lua 比较-再删**（防超时后误删别人的锁——直接 DEL 会把新持有者的锁删掉）。

### 两个必须直面的窗口

**窗口一：复制窗口 → 双持锁。** 主库确认加锁但未及同步就崩溃，新主没有这把锁，第二个客户端随即加锁成功——两个"持有者"并存。工程缓解（按力度排序）：

1. **同步复制确认**：加锁后执行 `WAIT 1 0`（`replication.c:4895`）——等待这条写被至少 1 个从库确认（REPLCONF ACK 到达该 offset）。7.2 起还有更强的 `WAITAOF`（`replication.c:4929`）：等待本地与从库的 AOF **fsync 完成**，连"从库确认后其机器掉电"都覆盖了。
2. **写入门槛**：10.3-B 的 `min-replicas-to-write 1 + min-replicas-max-lag 10`——保证"能成功加锁"时至少有一个不太旧的从库存在，切换后的新主大概率包含这把锁。
3. 两者叠加后，双持锁窗口从"未定义"收敛到"秒级边缘场景"（如 WAIT 确认的从库在提升过程中数据异常）——记入风险清单，看业务是否接受。

**窗口二：时序窗口 → 超时后误操作。** 持有者遭遇 GC 停顿/进程冻结超过 TTL，锁过期被 B 获得；A 苏醒后浑然不觉继续执行关键区。工程缓解：

1. **看门狗续期**：持有期间周期性（如每 1/3 TTL）用 Lua 校验 token 后 `PEXPIRE` 续期——业务逻辑阻塞不影响续期（续期走独立线程/协程）；
2. **Fencing token**：加锁时用 `INCR fence:order:123` 生成单调递增令牌并与锁绑定，关键区对下游资源的每次写都携带该令牌，**下游拒绝旧令牌**——这是唯一能同时封死两个窗口的方案，但要求下游资源配合校验（数据库条件更新/版本号）；
3. **业务幂等**：关键区操作幂等设计，双持锁的最坏后果从"数据错乱"降为"重复执行"。

### RedLock 的价值与边界

RedLock（antirez 提出）：向 N 个**完全独立部署**（不共享哨兵/集群）的主库依次加锁，拿到 ≥N/2+1 且总耗时 < 锁 TTL 才算成功，失败则全部释放。它把"单主异步复制"升级为"多主独立仲裁"，显著压低双持锁概率；但 Martin Kleppmann 的批评切中要害：**进程长时间停顿（GC/虚拟机挂起）跨越 TTL 时，RedLock 既不能防双持（停顿的客户端以为自己还持锁），也不提供 fencing**；且它依赖各节点时钟大致同步。结论：RedLock 是**风险缓解**不是**正确性证明**——用它可以，但关键区仍需 fencing token 或幂等兜底。

### 决策树与契约表

```
这把锁失效的最坏后果是什么？
├─ 重复做一次也无害（缓存重建、防重复提交的优化）
│    → 单实例 Redis：SET NX PX + 唯一token + Lua释放 + 看门狗。够用。
├─ 重复做会浪费但可补偿（重复扣库存可回滚/幂等）
│    → 上述实现 + min-replicas-to-write + WAIT/WAITAOF 同步确认 + 业务幂等
└─ 重复做=资损/数据错乱（资金、配额、任务分配的强互斥）
     → Redis 只能缓解、无法保证。改用线性一致存储：
       etcd/ZooKeeper（共识协议+租约+watch），
       或数据库唯一约束/条件更新（用 DB 当锁），或加 fencing token 全链路校验。
```

| 原语 | Redis 提供 | 残余风险 | 工程缓解 |
|---|---|---|---|
| 分布式锁 | NX 原子互斥 + TTL 防死锁 | 复制窗口双持；TTL 误过期 | WAIT/WAITAOF、min-replicas、看门狗、fencing |
| 信号量 | INCR/DECR + Lua 原子计数 | 半释放（持有者崩溃未减计数）→ 泄漏 | 计数项带 TTL 租约、定期对账回收 |
| 计数器/限流 | INCR + EXPIRE 原子组合 | 淘汰策略误删 | noeviction（10.3-D） |
| 阻塞队列 | BLPOP 阻塞原子弹出 | 弹出后消费者崩溃 → 消息丢失 | 用 Stream 消费组（PEL+XACK，9.5 节）替代 |
| leader 选举 | SET NX + TTL + 看门狗 | 双主窗口同锁 | 同锁方案 + 任期内带 fencing 校验 |

## 10.6 分层防御体系：一套可直接落地的三层保障方案（实战方案对照与勘误）

**白话**：本节抽取自 `doc/redis1~3.jpg` 的一套实战方案——按"业务分级 → 兜底防护 → 架构优化"三层组织 Redis 可靠性保障。总体评价：**方向与主流实践一致，三层结构与本文 10.1~10.5 完全可互映；但第二层有两处机制表述与 Redis 原生实现不符，落地前必须勘误**（否则会在"以为有防护"的地方裸奔）。逐层对照如下。

### 10.6.1 第一层：业务分级，差异化一致性策略 ✅

**方案原文**：核心价格、库存、订单这类强一致性缓存数据，开启主节点写保护机制，故障转移期间禁止旧主继续接受写入，从根源避免双主双写；核心写操作强制要求主从复制确认，达到最少从节点同步成功才返回成功，牺牲少量写入性能换数据可靠性。非核心配置、描述类缓存数据，接受故障转移时的少量数据丢失，优先保障可用性。核心业务与非核心业务独立部署集群，避免单一集群故障影响全部业务。

**对照 Redis 机制**：全部正确，且能一一映射——

| 方案措施 | Redis 原生机制 | 本文详见 |
|---|---|---|
| 主节点写保护（防双主双写） | `min-replicas-to-write` + `min-replicas-max-lag` | 10.3-B |
| 核心写强制主从复制确认 | `WAIT` / `WAITAOF`（同步复制） | 10.5 窗口一 |
| 业务分级差异化策略 | 按业务路由到不同实例/策略（核心走同步确认，非核心纯缓存） | 10.5 决策树 |
| 核心/非核心独立集群 | 部署层拆分（爆炸半径控制） | 10.2 分集群铁律 |

**一点补充**：写保护是**常态生效**的——只要新鲜从库不足就拒写，并非只在"故障转移期间"生效；这恰恰是"从根源避免双主双写"的原因，不是副作用。

### 10.6.2 第二层：兜底防护与脑裂检测 ⚠️ 两处勘误

**方案原文**：搭建集群脑裂与数据一致性监控体系，实时监控主节点数量、主从同步偏移、哨兵状态，出现双主、同步延迟超阈值秒级告警。配置哨兵法定人数与脑裂检测机制，旧主节点检测到自身失去**多数哨兵连接**时，自动拒绝写入请求。故障转移后做数据校验与同步，新主承接流量前先核对数据差异、增量同步缺失数据，避免直接切流导致数据回退。

**监控告警部分 ✅**：正确且必要，落地方式见 10.4 监控清单（双主检测行 + 复制 lag 行 + 哨兵事件行）。

**勘误一：旧主拒写的触发条件是"从库"，不是"哨兵"**。主从 + 哨兵模式下，Redis 原生的写保护与哨兵连接数**完全无关**——旧主也不参与哨兵仲裁、不知道哨兵的投票结果。原生机制 `min-replicas-to-write` 的判定依据是**足够新鲜的从库数量**：

【源码证据】`src/replication.c:4818`：

```c
int checkGoodReplicasStatus(void) {
    return server.masterhost || /* not a primary status should be OK */
           !server.repl_min_slaves_max_lag || /* Min slave max lag not configured */
           !server.repl_min_slaves_to_write || /* Min slave to write not configured */
           server.repl_good_slaves_count >= server.repl_min_slaves_to_write;
}
```

"失去多数派连接就自动拒写"这个语义在 **Cluster 模式下才原生成立**：主节点失联于多数主节点后集群状态置为 `CLUSTER_FAIL`（`src/cluster_legacy.c:5285` 起的 `clusterUpdateState`，FAIL 判定逻辑在 5301-5349 行），命令执行被拒并返回错误（`src/cluster.c:1507` `"-CLUSTERDOWN The cluster is down"`）。若在哨兵模式下想要"失去多数哨兵即拒写"的等效语义，需要外部编排（如哨兵 `notification-script` 联动客户端网关熔断），不是 Redis 内建能力——**落地时直接用 min-replicas 实现同等的防脏写效果即可**（10.3-B），不必造这个轮子。

**勘误二：Redis 原生不做"切流前数据差异核对与增量补齐"**。哨兵故障转移是**自动、立即切流**的：新主提升后哨兵发布 `+switch-master`，客户端随即重连新主——中间没有"先核对差异再放流量"的环节；而旧主恢复回归时，作为从库连上新主，先清空自己的数据再尝试 PSYNC 续传，续传失败就全量同步（清空自愈）：

【源码证据】`src/replication.c:2214`——从库加载新主数据前 `emptyData(-1, ...)` 清空自身。

所以"数据校验与增量同步"是**应用层/平台层**的职责，典型做法：切流窗口内对关键键做只读闸门（客户端侧），事后按数据库回源重建/比对修复。这也正是第三层"多写兜底"的价值：**Redis 不保证切换不丢，数据库才是 source of truth**。

### 10.6.3 第三层：架构层优化，从根源降低脑裂风险 ✅

**方案原文**：核心缓存集群跨机房多副本部署，哨兵节点分散部署在不同机房，避免单机房网络波动导致全部哨兵失联；核心数据采用多写兜底机制，关键写入同时落地缓存与数据库，即使缓存集群脑裂数据丢失也能从数据库回源重建；大促等核心时段前做网络分区与故障转移专项演练，验证脑裂场景下的数据保护能力——宁可牺牲少量故障转移速度，也绝不允许脑裂导致核心数据大量丢失与错乱。

**对照 Redis 机制**：全部正确，且正好补上了第二层勘误二留下的缺口（既然 Redis 不保证切换不丢，就靠"DB 可回源"兜底）。三条分别对应本文：哨兵跨机房 = 10.2 部署铁律 1/2 的故障域要求；多写兜底 = 缓存架构经典原则（cache-aside，Redis 只是加速层）；大促前演练 = 10.4 故障演练的节奏化（补一个"演练即验证脑裂保护能力"的目标定义）。"宁慢不丢"的取舍也和 10.3-C 的参数方向一致：`down-after-milliseconds` 宁可调大减少误切换，写保护/WAIT 宁可牺牲写延迟。

### 10.6.4 三层体系与本文的对应关系总表

| 层次 | 方案要点 | 落地到 Redis 的什么 | 本文 |
|---|---|---|---|
| 一：业务分级 | 核心写保护 + 同步确认 + 独立集群 | min-replicas / WAIT+WAITAOF / 分集群部署 | 10.3-B、10.5、10.2 |
| 二：兜底防护 | 双主与 lag 监控告警；旧主拒写；切换后数据校验 | INFO role 巡检 + lag 告警；min-replicas（勘误一）；应用层数据核对（勘误二） | 10.4、10.6.2 |
| 三：架构优化 | 跨机房哨兵 + 多写兜底 + 大促演练 | 故障域设计 / cache-aside 回源 / 演练节奏 | 10.2、10.4 |

## 10.7 本章小结

- 可靠性三问（崩溃丢多少/掉电丢多少/切换丢多少）中，**主从切换窗口是异步复制架构的固有属性**，只能用 min-replicas、WAIT/WAITAOF、lag 监控去压缩。
- 部署的铁律在物理层：哨兵 ≥3 奇数且跨故障域、主从跨机架/AZ、K8s 上 PDB+反亲和。
- 四组配置各司其职：持久化防重启丢、`min-replicas-to-write` 防脑裂丢、哨兵/集群参数防切得乱、**noeviction 防锁被淘汰**。
- 运维四件事：盯指标（lag/evicted_keys/delayed_fsync）、留备份（防误操作最后防线）、管变更（从库先升）、常演练（实测 RTO/RPO）。
- 在 Redis 上造并发原语：**它给的是"概率互斥"不是"数学互斥"**——效率锁用单实例三件套足够；正确性场景必须叠加同步复制确认与 fencing token，或者诚实地换线性一致存储。
- 纵深防御按"业务分级 → 兜底防护 → 架构优化"三层组织（10.6 节），其中两处常见表述须勘误：旧主拒写的依据是从库数量而非哨兵数量；Redis 原生不做切换后的数据差异核对——回源重建要靠"DB 是 source of truth"的多写兜底。

---



# 十一、贯通视图：三条时间线看懂 Redis 全貌

## 11.1 时间线一：redis-server 启动（main 全程）

```
main (server.c:8051)
 ├→ initServerConfig (8135)      命令表/默认值/共享整数对象池
 ├→ loadServerConfig (8280)      redis.conf 合并命令行参数
 ├→ initServer (8336)            创建 ae 事件循环、监听端口、注册读回调
 │                                ├→ aeCreateTimeEvent(serverCron) (3201)
 │                                └→ aeSetBeforeSleepProc(beforeSleep) (3216)
 ├→ InitServerLast (8356)        启动 bio 后台线程、IO 线程（若启用）
 ├→ loadDataFromDisk (8362)      AOF 优先（manifest→base→incr），否则 RDB
 ├→ setOOMScoreAdj (8408)
 └→ aeMain (8410)                进入事件循环，永不返回（直到 shutdown）
```

【源码证据】以上行号均实测于 `server.c`。注意启动顺序里两个细节：**先有命令表再加载持久化**（AOF 里的命令要经过权限校验重放），**数据加载完才进事件循环**（此期间客户端连上是"等待"状态）。

## 11.2 时间线二：一条 SET name "Redis" EX 60 的一生

```
① socket 可读 → readQueryFromClient (networking.c:3893)     [IO线程可分担]
② RESP 解析：*5\r\n$3\r\nSET... → argv[5] (processMultibulkBuffer :3278)
③ processCommand (server.c:4467)：
     命令表查找 → ACL/权限 → maxmemory：performEvictions 淘汰 (:4656)
④ lookupKeyWrite (db.c:370) → lookupKey (db.c:279)：
     expireIfNeeded 检查旧键是否已过期 → 过期即先删
⑤ setCommand 写入：dbAdd 键空间 + 设置 60s 过期（进 expires/subexpires）
⑥ propagateNow (server.c:3744)：命令追加进 AOF 缓冲 + 转发给所有从库
⑦ addReply("+OK")；beforeSleep 统一 writeToClient 写回 socket
⑧ 后续：serverCron → activeExpireCycle 采样维护；60 秒后键消亡；
   maxmemory 压力下它可能更早被 LRU/LFU/LRM 淘汰（6.3）
```

一条最普通的 SET，走过了事件循环（第四章）、对象系统（第三章）、生存期管理（第六章）、持久化与复制（第五、七章）的全部主干——**这就是为什么第四章值得读三遍**。

## 11.3 时间线三：一次哨兵故障转移

```
t0   主库进程崩溃
t0+down-after  哨兵 A 判 S_DOWN (sentinel.c:4580)
t0+...  A 询问其他哨兵 → quorum 确认 O_DOWN (sentinel.c:4654)
t0+...  哨兵 Leader 选举：A 拿多数派 (sentinel.c:4792)
t0+...  A 执行状态机 (sentinel.c:5374)：
          选新主（priority → offset → runid）
          SLAVEOF NO ONE 新主 → 其余从库改挂 → 老主回归后自动降从
t0+...  集群总线/发布订阅广播 +switch-master，客户端重连
```

若部署的是集群而非哨兵：故障检测走 gossip（PFAIL→FAIL，cluster_legacy.c:1935），投票人是主节点多数派，从节点竞选接班（8.4 节）——**两条时间线共享"多数派仲裁"的同一思想，只是投票人从哨兵换成了数据节点**。

## 11.4 从源码中提炼的四个设计模式视角

1. **一切皆事件**：文件事件、时间事件、before/afterSleep 钩子——任何子系统都能定位到"挂在哪个回调上"。
2. **分期付款**：rehash、过期采样、defrag、渐进式删除，全部把 O(N) 重活切成有预算的小步。
3. **元数据内联**：robj 对象头 16 字节装下 type/encoding/LRU；8.x 的 kvobj 更进一步把过期等元数据搬进对象——省一次间接寻址是 Redis 的执念。
4. **协议即接口**：复制、哨兵、集群总线、模块化命令声明（commands/*.json）全是"把内部机制翻译成协议"——可观测（INFO/CLUSTER/INFO sentinel）与可扩展因此免费获得。

---

# 十二、附录

## 12.1 关键结构体速查表

| 结构体 | 位置 | 一句话职责 |
|---|---|---|
| `sdshdr8/16/32/64` | sds.h:28-56 | 带长度头的字符串，五档按需 |
| `dict`（ht_table[2]/rehashidx） | dict.h:165-168 | 渐进式 rehash 的双表哈希 |
| `zskiplistNode/zskiplist` | server.h:1797-1813 | ZSet 的多层索引，P=1/4 |
| `quicklist/quicklistNode` | quicklist.h:107-117 / :47-59 | listpack 双向链 + LZF 压缩 |
| `intset` | intset.c:35-39 | 全整数紧凑集合，可升级位宽 |
| `stream / streamCG` | stream.h:36-46 / :93 | rax 骨架的消息流与消费组 |
| `redisObject (robj/kvobj)` | object.h:102-117 | type/encoding/lru/iskvobj 对象头 |
| `redisDb` | server.h:1226-1241 | kvstore 键空间 + expires + subexpires |
| `client` | server.h:1501 | 每连接状态：querybuf/obuf/db/mstate |
| `redisServer` | server.h（`struct redisServer`） | 全局单例：配置/统计/复制/集群状态 |
| `clusterNode/_clusterNode` | cluster_legacy.h:300 | 节点元数据 + 16384 位槽图 |
| `bio` 任务 | bio.c:60-63 | close/fsync/lazy-free 三类后台活 |

## 12.2 初学者学习路线（动手向）

1. **跑起来 + 打日志**：源码目录 `make` 编译（或直接用发行版二进制），`src/redis-server` 起服务，用 `redis-cli monitor` 看协议原文——RESP 是肉眼可读的，先建立"命令=文本协议"的直觉。
2. **调试一条命令**：用 IDE/调试器在 `processCommand`（server.c:4467）和 `call`（server.c:3992）下断点，执行 `SET a b`，单步走完 11.2 时间线。
3. **观测内存**：`MEMORY USAGE key`、`OBJECT ENCODING key` 验证第三章编码矩阵——亲手 RPUSH 128 个再 129 个元素，看编码切换。
4. **制造淘汰与过期**：`CONFIG SET maxmemory 100mb; CONFIG SET maxmemory-policy allkeys-lru`，灌数据看 `INFO stats` 的 evicted_keys；`DEBUG SLEEP` 之外用 `EXPIRE` + `INFO keyspace` 观察惰性删除。
5. **拆一份 RDB**：`src/redis-check-rdb dump.rdb` + 按 5.2 节 opcode 表手工 hexdump 对照——一天读懂 RDB。
6. **组一次复制与哨兵**：`utils/` 下有示例脚本；三个端口起主两从，再起三个哨兵，杀主库，全程 `INFO replication` 观察 10.3 时间线。
7. **写一个最小模块**：仿 `modules/` 的 hello 模块实现 `hello.simple` 命令，体会 RedisModule_* ABI（第九.9.3）。

## 12.3 源码阅读入口清单（30 个关键文件/函数）

| # | 文件:函数 | 行号（8.10.2） | 读什么 |
|---|---|---|---|
| 1 | server.c:main | 8051 | 启动全序（10.1） |
| 2 | server.c:serverCron | 1562 | 周期任务总调度 |
| 3 | ae.c:aeMain/aeProcessEvents | 497/365 | 事件循环本体 |
| 4 | server.c:processCommand | 4467 | 命令闸门 |
| 5 | server.c:call | 3992 | 执行与传播 |
| 6 | networking.c:readQueryFromClient | 3893 | 读入口 |
| 7 | networking.c:processMultibulkBuffer | 3278 | RESP 解析 |
| 8 | server.c:propagateNow | 3744 | AOF/从库传播 |
| 9 | db.c:lookupKey | 279 | 读写必经之路 |
| 10 | db.c:expireIfNeeded | 2999 | 惰性过期 |
| 11 | dict.c:dictRehash | 406 | 渐进式 rehash |
| 12 | sds.c:sdsMakeRoomFor | 341 | SDS 预分配 |
| 13 | t_zset.c:zsetAdd | 1601 | 编码分派样板 |
| 14 | t_hash.c:hashTypeTryConversion | 1561 | 阈值转换 |
| 15 | object.c:createObject | 107 | 对象头初始化 |
| 16 | rdb.c:rdbSaveBackground | 2223 | fork 快照 |
| 17 | rdb.h:RDB_OPCODE_* | 101-114 | RDB 格式 |
| 18 | aof.c:feedAppendOnlyFile | 1661 | AOF 追加 |
| 19 | aof.c:rewriteAppendOnlyFileBackground | 3197 | 重写触发 |
| 20 | bio.c:bioProcessBackgroundJobs | 261 | 后台线程 |
| 21 | expire.c:activeExpireCycle | 287 | 采样过期 |
| 22 | evict.c:performEvictions | 532 | 淘汰主流程 |
| 23 | evict.c:evictionPoolPopulate | 134 | 近似 LRU 池 |
| 24 | lazyfree.c:lazyfreeGetFreeEffort | 168 | 异步释放决策 |
| 25 | replication.c:syncCommand | 1209 | 复制建立 |
| 26 | replication.c:masterTryPartialResynchronization | 1007 | PSYNC 判定 |
| 27 | sentinel.c:sentinelFailoverStateMachine | 5374 | 故障转移状态机 |
| 28 | cluster_legacy.c:clusterCron | 4820 | gossip 心跳 |
| 29 | multi.c:execCommand | 127 | 事务执行 |
| 30 | t_stream.c:xaddCommand | 2537 | Stream 入队 |

## 12.4 专题：跳表（Skip List）——从零理解 Redis 的有序索引

> 本专题是对 2.4 节的展开：2.4 回答"跳表在 Redis 里长什么样"，这里回答"跳表为什么可行、Redis 为什么选它、它对经典跳表改了什么"。理解它不需要任何树结构基础。

### 12.4.1 从一个矛盾说起：有序数组和链表各有致命伤

维护一个有序集合（按分数排序的成员），常见的两个候选各有硬伤：

- **有序数组**：二分查找 O(logN)，但中间插入要把后半截全部搬家，O(N)；
- **有序链表**：找到位置后插入 O(1)，但"找位置"只能从头一个个走，O(N)。

能不能让链表也拥有"跳着找"的能力？一个朴素的想法：**提前给链表建索引**——每隔一个节点抽出来建一层"快车道"，再隔一个抽一层……这就是跳表。它用一个地铁系统做类比：

```
L2（特快）：  head ──────────────→ 50 ─────────────────→ NULL
L1（快车）：  head ────→ 20 ──────→ 50 ──────→ 78 ─────→ NULL
L0（站站停）：head → 8 → 20 → 35 → 50 → 63 → 78 → 95 → NULL
```

查找 63：从 L2 出发，50 后面没有更大的可达节点 → 降到 L1；50 → 78 超过 63 → 降到 L0；50 → 63 ✅。**只走了 4 步，而不是 L0 上的 6 步**。层数越高、抽取越稀疏，"跳"得越远——查找代价正比于"层数 × 每层平均步数"，两者相乘恰好是 O(logN)。

### 12.4.2 关键设计：层高不靠"维护"，靠"抛硬币"

上面的"每隔一个抽一层"有个致命问题：插入/删除会打破抽取的均匀性，需要反复调整索引（AVL 树的旋转就是干这个的）。跳表的革命性一步是**把层高变成随机的**：每个新节点插入时"抛硬币"，正面就再升一层，直到反面为止。这样插入只需要改自己的指针，**索引结构永远不需要重平衡**。

【源码证据】`src/t_zset.c:254-261`——Redis 的抛硬币：

```c
static int zslRandomLevel(void) {
    static const int threshold = ZSKIPLIST_P*RAND_MAX;
    int level = 1;
    while (random() < threshold)
        level += 1;
    return (level<ZSKIPLIST_MAXLEVEL) ? level : ZSKIPLIST_MAXLEVEL;
}
```

`ZSKIPLIST_P = 0.25`（`server.h:657`）：每层晋升概率 1/4。这带来两个可直接推导的结论：

1. **平均每节点指针数 = 1/(1−P) = 1.33**。对比红黑树的 2 个指针、B+ 树的多个指针，跳表反而更省内存——这是 Redis 选 P=1/4 而不是论文标准的 1/2 的原因（1/2 → 2.0 个指针/节点）；
2. 层高分布是**幂次定律**：约 75% 的节点只有 1 层、约 18.75% 有 2 层、4.7% 有 3 层……顶层节点稀疏自动成立，不需要任何维护。`ZSKIPLIST_MAXLEVEL 32`（`server.h:656`）是因为 1/4 的 32 次方已经足以覆盖 2^64 个元素（注释原文 "Should be enough for 2^64 elements"）。

### 12.4.3 节点结构：一个节点身兼多层

【源码证据】`src/server.h:1797-1807` 与 `:1809-1814`：

```c
typedef struct zskiplistNode {
    double score;
    struct zskiplistNode *backward;
    struct zskiplistLevel {
        struct zskiplistNode *forward;
        /* Span is the number of elements between this node and the next node at this level.
         * At level 0, span is repurposed to store zskiplistNodeInfo for regular nodes, */
        unsigned long span;
    } level[];
    /* sds ele is embedded after level[] array (assist zslGetNodeElement(node) to access it) */
} zskiplistNode;

typedef struct zskiplist {
    struct zskiplistNode *header, *tail;
    unsigned long length;
    int level;              /* 当前实际最高层 */
    size_t alloc_size;
} zskiplist;
```

三个值得注意的细节：

- **`level[]` 是柔性数组**：层高 3 的节点就分配 3 个 `{forward, span}`，节点自己带着自己的"快车道票"；
- **`forward`（右行指针）构成查找路径，`backward`（左行指针）只有一根**且只在 L0——支持 ZREVRANGE 反向遍历，代价极小；
- **8.x 的内存抠法**：注释说明成员 `ele`（SDS 字符串）紧贴在 `level[]` 数组之后分配（一次 malloc），且 L0 的 `span` 被挪用存放节点元信息（`zskiplistNodeInfo`）——span 在 L0 反正没有排名意义（相邻元素跨度恒为 1），这 8 字节就省下来了。

### 12.4.4 插入如何维护：update[] 记录每层的"前驱"

插入 score=63 的新节点（假设抛硬币得了 2 层）：查找时顺手把"每一层上最后经过的节点"记进 `update[]` 数组，之后只需把每层的前驱 forward 指向新节点、新节点的 forward 指向原来的后继——每层两条指针，没有全局重排。

【源码证据】`src/t_zset.c:326`（`zslInsert`）内部先调 `zslRandomLevel()` 定层高，实际接线在 `zslInsertNode`（`t_zset.c:265` 起），其开头两个数组就是查找路径的存根：

```c
    zskiplistNode *update[ZSKIPLIST_MAXLEVEL];  /* Nodes that will point to the new node at each level */
    unsigned long rank[ZSKIPLIST_MAXLEVEL];     /* Rank (0-based) at each level during traversal */
```

`rank[]` 记录每层经过的跨度累计值，插入后各层前驱的 span 用它做减法修正——这就是排名信息能"顺手维护"的秘密。删除同理（`zslUnlinkNode`，`t_zset.c:345`，把每层前驱跨过被删节点接起来）。

### 12.4.5 span：排名查询 O(logN) 的全部秘密

ZRANK "它排第几"如果靠数节点就是 O(N)。跳表的解法：**每条 forward 指针配一个 span（跨度）= 沿这条指针右行会跨过多少个 L0 元素**。查找路径上把 span 累加起来，到达目标时总和就是排名。

【源码证据】`src/t_zset.c:645` 的 `zslGetRank`——查找过程中 `rank += x->level[i].span`，与 12.4.4 的插入期 span 维护闭环。查排名、查成员、维护排名三项全是 O(logN)，这是"跳表比红黑树更适合排行榜"的直接原因（红黑树求排名需要额外的子树大小字段和更复杂的旋转维护）。

### 12.4.6 Redis 对经典跳表的五个改造点

| 改造点 | 经典论文版 | Redis 版 | 为什么 |
|---|---|---|---|
| 重复分数 | 通常要求 key 唯一 | **允许 score 重复**，同分按成员 SDS 字典序再排（`sdscmplex`，`t_zset.c:826`） | 排行榜天然大量同分；字典序兜底保证全序唯一 |
| 晋升概率 | P=1/2 | P=1/4（`server.h:657`） | 期望指针 1.33/节点，压内存 |
| 反向遍历 | 无 | L0 双向（`backward` 指针） | ZREVRANGE 免走回头路 |
| 排名 | 无 | 每条 forward 带 `span` | ZRANK/ZCOUNT O(logN) |
| 元数据位置 | 独立分配 | `ele` 嵌入节点尾部、L0 span 挪用（8.x） | 少一次 malloc、省 8 字节/节点 |

### 12.4.7 Redis 为什么选跳表，不选红黑树？

antirez 的经典回答可以归纳成三点，每点都能在源码里找到印证：

1. **范围查询天然友好**：ZRANGEBYSCORE 找到起点后沿 L0 的 forward 顺序走即可；红黑树需要中序遍历、跨层上下翻。且 L0 双向链表让反向范围同样便宜。
2. **实现与调试简单**：无旋转、无颜色、无 3-节点/4-节点变换，插入删除都是"记下前驱、接指针"。zslInsert 全函数一屏读完；红黑树删除的"兄弟节点是红色且侄子全黑"这类分支，调过的人都懂。
3. **内存可调**：P 是常量，改成 1/8 就能再省指针；红黑树的形态是固定的，没有这个旋钮。

补充一个完整认知：**zset 并不是"只有跳表"**。它是 dict + skiplist 的双结构（`server.h:1816` 起的 `typedef struct zset`：`dict *dict` + `zskiplist *zsl`）——`ZSCORE O(1)` 走 dict，范围/排名走 skiplist；而小 zset（≤128 个元素且成员不超长，3.2 节阈值表）整体退化为 listpack，连跳表都不建。跳表只在"数据量大 + 需要有序"这一个象限里上岗。

### 12.4.8 复杂度与动手验证

| 操作 | 平均 | 最坏（概率极低） | 依赖的源码函数 |
|---|---|---|---|
| 查找/插入/删除 | O(logN) | O(N)（层高全 1 的极端分布） | zslInsert（t_zset.c:326） |
| ZRANK 排名 | O(logN) | O(N) | zslGetRank（t_zset.c:645） |
| 范围遍历 M 个结果 | O(logN + M) | — | L0 forward 链 |
| 空间 | O(N)，约 1.33 指针/节点 | — | zslRandomLevel（t_zset.c:254） |

动手验证三步：① `OBJECT ENCODING myzset` 先看到 `listpack`，`ZADD` 到 129 个元素（超过 `zset-max-listpack-entries 128`）后再看变成 `skiplist`；② `ZRANK` 一个中间元素，对照 `ZRANGE 0 -1 WITHSCORES` 数位置，理解 span 累加出的排名；③ `CONFIG RESETSTAT` 后循环执行 1000 次 `ZADD`，再看 `INFO commandstats` 里 zadd 的 `usec_per_call`——层高虽是随机的，方差极小，平均耗时应稳定在微秒级。

**一句话总结**：跳表 = 用随机层高把"有序链表"升级成"多层快车道"，以 1.33 个指针/节点的代价换来 O(logN) 的查找/插入/删除/排名，并且**结构永远不需要重平衡**——这正是"单线程、内存敏感、范围查询多"的 Redis 最想要的那种结构。

### 12.4.9 跳表与数据库索引：同一设计原语的两副面孔

**先说结论**：跳表本质上就是"概率化版的数据库索引"，而且现代数据库引擎里真的在用跳表。两者共享三个骨架，只在一处分歧。

**相通一：多层稀疏索引（骨架同源）**。B+ 树的 root → internal → leaf 三层，和跳表的 L2 → L1 → L0，是同一个设计原语的两副面孔：**在一份数据之上逐层建稀疏索引，上层粗筛、下层精确定位**。B+ 树内部节点页就是"快车道"，叶子层是"站站停"；跳表只是把"哪些键进快车道"从确定性规则改成了抛硬币。更妙的对照是 LSM-Tree 引擎（LevelDB/RocksDB/HBase）的 L0→L1→L6 分层 SSTable——每层比下一层稀疏一个数量级——连"逐层稀疏"的比例结构都与跳表同构。Pugh 1990 年论文的副标题 "A Probabilistic Alternative to Balanced Trees" 说的就是这层关系。

**相通二：叶子层都是有序链表（范围扫描同款）**。InnoDB B+ 树的叶子页之间是双向链表，定位到起点后顺序扫描——这正是跳表 L0 的 forward 链加 backward 指针。复杂度公式完全一样：定位起点 O(logN) + 顺序走 M 个结果 O(M)。ZRANGEBYSCORE 与 `WHERE score BETWEEN a AND b` 在索引层面行为一致。

**相通三：一份数据多套索引（维护模型相同）**。zset 的双结构——dict 管 O(1) 点查 + skiplist 管有序扫描——就是数据库"哈希/主键点查 + 有序二级索引"的微缩版。索引项同样是"排序键 + 回表指针"：B+ 树二级索引叶子存 (key, 主键值)，跳表节点存 (score, ele)。连代价都一样：MySQL 每写一行要同步维护所有二级索引，ZADD 每写一个成员要同时维护 dict 与 skiplist——**写放大换读路径**，两边记的是同一条账。

**相通四：真实交汇点——数据库引擎里就有跳表**。① LSM 引擎的 MemTable 就是跳表：LevelDB/RocksDB 内存中的有序缓冲区直接用跳表实现，看中的正是"内存友好、无重平衡、并发读友好"；② 无锁并发有序结构几乎只能选跳表：链表节点可用 CAS 原子接入（Harris 2001 无锁链表为基础），而无锁 B 树极难实现——Java 标准库的高并发有序 Map 就是 `ConcurrentSkipListMap`，Cassandra 等系统在用，RocksDB memtable 的无锁读也受益于此。这是概率结构"永不需要全局重平衡"换来的并发红利，B 树的分裂/旋转给不了。作为对照，B+ 树处理并发用的是 latch 蟹行协议（自顶向下逐层拿子锁、放父锁）——两边解决同一个问题，手段不同。

**关键分歧只有一个：存储介质**。数据库索引住在磁盘上，I/O 以页为单位，所以 B+ 树把扇出做到几百（InnoDB 一个 16KB 页放几百个键），十亿行数据高度也只有 3~4 层——即 3~4 次随机 I/O；跳表是纯指针结构，一次查找要追 logN 次指针，在磁盘上就是 logN 次随机 I/O，且无页局部性。工程结论由此而来：**磁盘索引选 B+ 树（扇出换 I/O），内存索引两者各有胜负，内存高并发有序结构几乎必选跳表**。

| 维度 | B+ 树（InnoDB/MySQL） | 跳表（Redis zset / LSM MemTable） |
|---|---|---|
| 索引骨架 | 多层稀疏索引，确定性分层 | 多层稀疏索引，概率性分层 |
| 平衡方式 | 插入触发分裂/合并，自底向上重平衡 | 随机层高，**永不重平衡** |
| 介质适配 | 磁盘页：高扇出，高度 3~4 | 内存指针：逐层追踪 O(logN) |
| 范围扫描 | 叶子页双向链表，O(logN+M) | L0 forward+backward，O(logN+M) |
| 排名/统计 | 需额外维护（子树计数等） | span 内嵌，ZRANK 直接 O(logN) |
| 并发方案 | latch 蟹行协议 | CAS 无锁（链表节点易原子接入） |
| 写放大 | 每个二级索引同步维护 | dict+skiplist 双结构同步维护 |

**一句话总结**：跳表与数据库索引共享"分层稀疏 + 有序叶子链 + 一份数据多套索引"三个骨架，差别只在平衡策略（概率 vs 分裂重平衡）与介质适配（内存指针 vs 磁盘页）——理解了跳表，B+ 树的索引思想就已经懂了一半，反之亦然。

### 12.4.10 延伸：什么是"回表"——从索引定位到数据的那最后一跳

12.4.9 相通三里提到索引项是"排序键 + 回表指针"，本节把**回表（back-to-table lookup）**这个数据库最常考的概念讲透，并给出它在 Redis 里的对应物与优化映射。

**白话定义**：索引里只存"排序键 + 主键"，不存整行。通过二级索引找到目标行后，若 SELECT 还要别的列，就得**拿着主键回到主键索引（聚簇索引）再查一次**——这多出来的一跳就叫回表。以 InnoDB 为例：数据本身按主键组织成聚簇索引（叶子 = 整行）；`age` 上建二级索引，叶子只存 `(age, id)`。执行 `SELECT * FROM users WHERE age = 25` 时：先在 age 索引树上定位所有 `age=25` 的条目，再对每个 id **逐个回主键树取整行**——匹配 N 行就是 N 次额外的 B+ 树查找，且是按主键散布的**随机 I/O**。这就是为什么"明明有索引"的查询还是很慢：索引只回答"哪些行匹配"，回表才是取数的主体。

**Redis 侧的对应物**。zset 恰好是这套结构的微缩模型：skiplist 是有序二级索引（节点只带 `(score, ele)`），dict 是"主键表"——O(1) 点查的入口：

【源码证据】`src/t_zset.c:1539-1549`——ZSCORE 的实现（skiplist 编码时走 dict 而不是跳表）：

```c
int zsetScore(robj *zobj, sds member, double *score) {
    /* ... */
    } else if (zobj->encoding == OBJ_ENCODING_SKIPLIST) {
        zset *zs = zobj->ptr;
        dictEntry *de = dictFind(zs->dict, member);
        if (de == NULL) return C_ERR;
        zskiplistNode *znode = dictGetKey(de);
        *score = znode->score;
```

（`zscoreCommand` 入口在 `t_zset.c:4092`；范围扫描入口 `zrangebyscoreCommand` 在 `t_zset.c:3633`，沿跳表 L0 链取成员。）于是映射关系很清晰：

| 数据库概念 | InnoDB/MySQL | Redis zset / 应用层 |
|---|---|---|
| 聚簇索引（数据本体） | 主键树，叶子 = 整行 | dict（键 = 成员，O(1) 点查） |
| 二级索引 | (索引列, 主键) 有序树 | skiplist（(score, ele) 有序链） |
| **回表** | 拿主键回聚簇索引取整行 | ZRANGEBYSCORE 拿到 member 后，**再查业务 Hash 取详情**（`HGETALL user:<id>`） |
| 覆盖索引 | 索引叶子里已含所需列，免回表 | zset 节点内嵌 ele（12.4.3），score/member 一次拿全；或业务把热点字段冗余进 member 旁的 Hash/JSON |
| 深分页 `LIMIT 100000, 10` | 扫过并丢弃 10 万行 + 大量回表 | `ZRANGE key 100000 100009` 同样 O(offset)——两边同病 |

**优化手段与它们的 Redis 对应物**（左列是数据库经典方案，右列是同一思想在 Redis 应用层的落地）：

1. **覆盖索引**：让索引叶子里带上所需列，把回表消灭在索引层——`SELECT name WHERE age=25` 建联合索引 `(age, name)` 即可（EXPLAIN 显示 `Using index`）。Redis 对应：把排序需要的"详情字段"直接冗余进有序结构（如用 member 编码 `score|name|level`，或旁挂一个同序 Hash），让范围查询一次拿全、不必二次点查。
2. **索引下推（ICP，MySQL 5.6+）**：把 WHERE 里属于索引列的过滤条件下推到引擎层在索引上先筛，**减少回表次数**。Redis 对应：能用 score 区间表达的过滤条件放进 ZRANGEBYSCORE 的 min/max（甚至 `LIMIT offset count`），先在 zset 内筛掉，不要全量取回客户端再过滤。
3. **MRR（Multi-Range Read）**：把回表主键**排序后批量读**，随机 I/O 顺序化。Redis 侧有个天然的漂亮对应：跳表 L0 是有序链，范围结果**天然按成员有序**——若 member 就是业务主键，"回表"顺序天然有序，配合 pipeline 批量取 Hash 已经是最优形态。
4. **延迟关联**：先在索引上完成过滤/分页只取主键集合，最后一步才回表取整行（子查询只查 id 再 JOIN）。Redis 对应：ZRANGEBYSCORE 只取 id 列表，用 pipeline/Lua 在服务器端合并详情查询，减少网络往返——本质都是"把回表压缩成最后一批"。

**一句话总结**：回表 = "索引告诉你行在哪，取整行还得再跑一趟"的那一趟；它慢在随机 I/O × 匹配行数，而所有优化（覆盖索引/ICP/MRR/延迟关联）都指向同一个方向——**让过滤在索引上完成，把回表压缩到最少、最后、最好按序批量**。Redis zset 把"成员本身"内嵌进跳表节点，相当于把覆盖索引做到了极致；而一旦范围查询之后还要取详情，就轮到你用同样的四个思路来设计应用层的"回表"了。

## 结语

把 Redis 的源码压成一句话：**一个单线程的事件循环，用一批"以内存为先、以简单为美、以渐进为手段"的数据结构与协议，把缓存、数据库与消息中间件的三重角色统一起来。** 它的每个子系统单独看都不复杂——dict、跳表、fork 快照、PSYNC——复杂的是它们如何在"任何一步都不能让事件循环停顿太久"这条约束下互相成全：rehash 让位于 RDB 子进程、过期删除让位于主从一致、淘汰检查内联进每条写命令、大对象释放外包给后台线程。读懂了这些"成全"，再回头看 Redis 对外的每条命令、每个配置项，就都成了源码里某段注释的直接翻译。

与《Spring Framework 深度源码解析》对照着读也别有意味：Spring 把复杂度交给"容器与扩展点"，让业务代码简单；Redis 把复杂度交给"数据结构与协议设计"，让运行时简单。两者殊途同归——**好的架构，是先决定复杂度住在哪里。**

---

*本文基于 `D:\code\3rd\redis`（tag 8.10.2，2026-09-16）撰写，2026-10-06 定稿。源码行号仅对该 tag 精确；升级阅读版本时按函数名重新定位即可。*

