# PostgreSQL 原理分析（面向小白的整体梳理）

> 整理日期：2026-10-17
>
> 适用版本：以 **PostgreSQL 18** 为准（**19 处于 RC 阶段，GA 计划 2026-11-19**；18 是当前稳定主版本）。核心原理（进程模型、共享内存、MVCC、WAL、8KB 页结构、流复制）**从 9.x 到 18 几乎不变**，遇到版本专属能力会单独标注。
>
> 阅读对象：PostgreSQL 初学者，尤其是从 **Oracle / MySQL** 转过来的人。本文沿用姊妹篇 [Oracle原理分析.md](Oracle原理分析.md) 的“**先建整体心智模型 → 再拆每个部件 → 最后串成一条完整请求链路**”骨架，把你最常听到的名词（**postmaster、backend、shared_buffers、WAL、LSN、xmin/xmax、快照、VACUUM、autovacuum、流复制、逻辑复制、8KB Page、Hint Bits、TOAST**）一次性讲清楚，并在关键处**与 Oracle 做对照**，帮你把两套体系的地图叠在一起。
>
> 文档版本：v0.1
>
> 深入阅读入口：本文第 5 节的 MVCC 细节，在姊妹篇 [MVCC原理.md](MVCC原理.md)（Oracle vs MySQL MVCC 逐机制对比）与 [DB事务隔离级别.md](DB事务隔离级别.md)（四库隔离级别实现对照）里有更系统的展开；想对照另一家大厂数据库的全景，见 [Oracle原理分析.md](Oracle原理分析.md)。

---

## 目录

> 📌 **建议阅读顺序（小白路线）**：`第 2 节（概念）` → `第 4 节（进程）` → `第 6 节（DML 旅程）` → `第 5 节（MVCC / VACUUM）` → `第 3 节（内存）` → 其余按需查阅。
> 本文是「先建心智模型 → 拆部件 → 串成一条请求链路」的递进结构。所有标 **「深入：」** 的小节是对应主题的专项拆解，可单独查阅，不影响主线阅读。

1. [版本说明：从 PG 16/17 到 PG 18/19](#1-版本说明从-pg-1617-到-pg-1819)
2. [核心概念与部署形态：集群 / 数据库 / Schema / 用户](#2-核心概念与部署形态集群--数据库--schema--用户)
   - 2.1–2.4 四个核心概念（集群 / 数据库 / Schema / 用户）与关系图
   - 2.5 深入：“实例”在 PG 里是什么（对比 Oracle 实例）
   - 2.6 深入：部署形态①：多库单集群 vs Oracle 一实例一库
   - 2.7 深入：部署形态②：主从流复制 / 逻辑复制 / 分布式扩展
3. [PostgreSQL 的内存结构：共享内存 + 每后端私有内存](#3-postgresql-的内存结构共享内存--每后端私有内存)
4. [PostgreSQL 的进程模型：postmaster、backend 与后台进程](#4-postgresql-的进程模型postmasterbackend-与后台进程)
5. [读不阻塞写、写不阻塞读：PostgreSQL 的 MVCC（含 §5.5 元组/快照、§5.6 VACUUM）](#5-读不阻塞写写不阻塞读postgresql-的-mvcc)
6. [一条 DML 的完整旅程（含 §6.3 WAL 记录、§6.4 WAL 物理结构与 LSN、§6.5 检查点与恢复）](#6-一条-dml-的完整旅程各进程如何配合)
7. [高可用与复制：流复制 vs 逻辑复制（含 §7.5 同步复制旋钮）](#7-高可用与复制流复制-vs-逻辑复制)
8. [“共享池满了”在 PostgreSQL 里对应什么 + 日常写 SQL 的正确姿势](#8-共享池满了在-postgresql-里对应什么--日常写-sql-的正确姿势)
9. [整体串讲：把上面所有东西连成一张全景图](#9-整体串讲把上面所有东西连成一张全景图)
10. [数据存储层次：集群 / 表空间 / 数据库 / 关系 / 段文件 / 页 / 元组](#10-数据存储层次集群--表空间--数据库--关系--段文件--页--元组)
11. [深入：PostgreSQL 8KB 页（Page）内部结构](#11-深入postgresql-8kb-页page内部结构)
12. [参考资料](#12-参考资料)

---

## 1. 版本说明：从 PG 16/17 到 PG 18/19

关于版本，先给一条准确的时间线（本文整理于 2026-10，已按最新事实校正）：

- **PostgreSQL 16**：2023-09 发布。逻辑复制支持从备库订阅、并行 vacuum？不，重点：`pg_stat_io`、从 standby 做逻辑解码等。
- **PostgreSQL 17**：2024-09 发布。新增 **WAL Summarizer**（支撑 `pg_basebackup --incremental` 增量备份）、**流式 I/O**（`io_combine_limit`）、逻辑复制槽故障转移（failover slot）、`VACUUM` 内存改进。
- **PostgreSQL 18**：**2025-09-25 发布**，是本文的基准版本。亮点：**异步 I/O（AIO，`io_method`/`io_workers`）**、原生 **`uuidv7()`**、**虚拟生成列（virtual generated columns）**、B-tree **skip scan**、`ALTER TABLE ... ADD COLUMN NOT NULL` 免全表扫描、OAuth 2.0 认证、`pg_stat_io` 扩展等。
- **PostgreSQL 19**：**2026-10-15 发布 RC1**，**GA 计划 2026-11-19**。截至本文写作时仍是 RC，故本文以 18 为准，涉及 19 专属能力会单独标注。

好消息：本文讲的全部核心原理（**进程模型 / 共享内存 / MVCC / WAL / 8KB 页结构 / 流复制**）**从 9.x 到 18 几乎不变**，不必担心“版本对不上”。下文以通用原理为主，版本专属特性单独标注。

> ⚠️ 与 Oracle 的命名差异提醒：PostgreSQL 每年发一个**主版本**（16/17/18…），主版本间**不做二进制兼容**，升级需 `pg_upgrade` 或逻辑复制；每个主版本提供 **5 年**支持。这与 Oracle 动辄十年以上的长期支持节奏不同，务必规划好升级窗口。

---

## 2. 核心概念与部署形态：集群 / 数据库 / Schema / 用户

本节先讲四个最容易被混的核心概念（集群 / 数据库 / Schema / 用户及其关系图），再展开“实例”在 PG 里到底对应什么（§2.5）与两种部署视角（§2.6 多库单集群、§2.7 主从复制与分布式扩展）。

初学者最容易把这几层当成一个东西。它们其实是“**四个不同层次**”的概念，而且和 Oracle 的“数据库 / 实例 / Schema”**对不上号**——这是从 Oracle 转来最容易踩的第一个坑。

### 2.1 集群（Database Cluster）—— 一个 data directory

PostgreSQL 的 **Database Cluster（数据库集群）** 指的是：**一个数据目录（`PGDATA`）下、由同一个 `postmaster` 进程管理的一整套数据库**。

- 它初始化于 `initdb`，目录里至少有 `base/`（各数据库的数据）、`global/`（共享系统表）、`pg_wal/`（WAL）、`pg_xact/`（事务提交状态）、`postgresql.conf`、`pg_control` 等。
- **一个集群里有多个数据库**（`postgres`、`template0`、`template1` 加用户自建的库）；客户端连接**必须指定要连哪个数据库**。
- 集群是 PG 的**管理、备份（`pg_basebackup` 整簇备份）、升级的最小单位**。

> ⚠️ 术语坑：PostgreSQL 说的 “cluster” 是**一个数据目录下的数据库集合**，**不是** Oracle RAC 那种“多机集群”。想要 Oracle RAC 那种多机共享，需要的是 **主从复制 / Citus / Greenplum** 这类方案（见 §2.7）。

### 2.2 数据库（Database）与 Schema —— 两层命名空间

- **数据库（Database）**：集群内的一个独立库，**自带一套系统目录（`pg_catalog`）与自己的数据文件目录**（`base/<dboid>`）。**默认不能跨库查询**——要跨库得用 `dblink`/`postgres_fdw`/外部工具。这一点和 MySQL“一个连接可跨库 `db.table`”不同。
- **Schema（模式 / 命名空间）**：**数据库内部**的对象命名空间，用来给表/视图/函数分组、隔离权限。`search_path` 决定你写 `SELECT * FROM t;` 时去哪几个 schema 找 `t`（默认 `"$user", public`）。
- 层次：**集群 ⊃ 数据库 ⊃ Schema ⊃ 对象（表/索引/视图/函数…）**。

### 2.3 用户 / 角色（Role）—— 与 Schema 解耦（与 Oracle 最大的不同）

- PostgreSQL 的 **用户就是角色（Role）**：`CREATE ROLE`/`CREATE USER` 二选一（`USER` 隐含可登录）。角色是**集群级**的，**所有数据库共享同一套角色**。
- **Schema 与用户没有绑定关系**：`CREATE USER tom;` **不会**自动创建 `tom` schema（与 Oracle“建用户即建同名 Schema”**完全相反**）。你建的表默认落在当前 `search_path` 的首个可写 schema（通常是 `public`）。
- 权限模型：`GRANT ... ON ...`（对象级）+ `GRANT role TO role`（角色继承）+ `ALTER DEFAULT PRIVILEGES`（默认权限）。**模式（Schema）是分组单位，角色是权限主体，两者正交。**

### 2.4 四者关系图

```
┌──────────────────────────────────────────────────────────────────┐
│         PostgreSQL Database Cluster（一个 data directory / 一个 postmaster）│
│  物理文件：base/ + global/ + pg_wal/ + pg_xact/ + pg_control ...    │
│                                                                    │
│  ┌──────────────────────── 共享系统目录 global/ ─────────────────┐ │
│  │  pg_authid（角色/密码） · pg_database · 表空间定义 ...          │ │
│  └───────────────────────────────────────────────────────────────┘ │
│                                                                    │
│  数据库（Database，可多个，各自一套目录与 pg_catalog）              │
│   ├── postgres        （默认维护库）                                │
│   ├── template1       （建库模板，可改）                            │
│   ├── template0       （纯净模板，只读）                            │
│   └── mydb            （业务库）                                     │
│         ├── Schema: public   ← 默认公共命名空间（表/索引/视图…）    │
│         ├── Schema: sales    ← 业务分组命名空间                     │
│         └── Schema: tom      ← 若手动建，则是一个普通命名空间        │
│                                                                    │
│  角色（Role，集群级，跨所有数据库共享）：tom / jerry / app_rw ...  │
└──────────────────────────────────────────────────────────────────┘
```

关键区分速记：

| 概念 | 本质 | 作用域 | 与 Oracle 对照 |
|------|------|---------|---------------|
| Cluster | 一个 data directory + 一个 postmaster | 最大单位 | ≈ Oracle「实例 + 库」合体（单机） |
| Database | 集群内的独立库 | 集群内 | Oracle 里“一个库”更接近 PG 的**整个 Cluster** |
| Schema | 库内命名空间 | 数据库内 | ≈ Oracle Schema，但**与用户解耦** |
| Role | 权限主体（用户） | 集群级（跨库） | ≈ Oracle User，但**不再等于 Schema** |

> 与 Oracle 的核心差异（容易踩坑）：**Oracle 里 `schema ≈ 用户`，`database` 是整个物理库**；**PG 里 `集群` 才是“整个物理库”，`database` 是集群内的一个子库，而 `schema` 只是库内的命名空间，与用户无关**。所以“连接串里写库名”在 PG 里是**必填**的（要连到具体 database），在 Oracle 里服务名指到库、靠 schema 切分。

### 2.5 深入：“实例”在 PostgreSQL 里是什么（对比 Oracle 实例）

Oracle 有清晰的“**数据库（磁盘文件）↔ 实例（内存 + 进程）**”二分。PostgreSQL **没有单独的“实例”概念**，但你可以把它拆成“运行时”来理解：

- PG 的“运行时”= **一个 `postmaster` 主进程 + 一块共享内存 + 一组后台进程 + 每个连接一个后端进程（backend）**。
- 它和“数据目录（PGDATA）”是**一一绑定**的：`postmaster` 启动时打开 `PGDATA`，集群即“被打开”。所以你完全可以把 **`postmaster` + 共享内存 + 后台进程 + backend** 理解成“PG 的实例”，把 **`PGDATA` 目录**理解成“PG 的数据库文件集合”。
- 关键区别：**Oracle 一个实例可以打开多个库（RAC 多实例开一库、或多租户一实例多 PDB）；PG 一个 `postmaster` 只管理一个集群（一个 PGDATA），集群里含多个 database。** 想要“多实例开同一份数据”，PG 原生做不到，只能靠复制（每个副本各是一份数据）。

> 记忆法：**Oracle = 实例 ⇄ 库；PG = 一个 postmaster ⇄ 一个 PGDATA 集群（集群里多库）。**

### 2.6 深入：部署形态①：多库单集群 vs Oracle 一实例一库

| 维度 | PostgreSQL | Oracle |
|---|---|---|
| 顶层单位 | **Cluster**（一个 PGDATA / 一个 postmaster） | 实例（SGA+进程）/ 数据库（文件） |
| 库的数量 | 一个集群内 **多个 database**，连接时指定 | 单实例一库；RAC 多实例一库；CDB 一实例多 PDB |
| 跨库查询 | 默认**不支持**，需 fdw/dblink | 同一库内跨 schema 直接查；跨库靠 DB Link |
| 隔离边界 | 数据库之间**数据字典独立**（各自的 `pg_catalog`） | 非 CDB 靠 Schema 逻辑隔离；CDB 才到数据库级隔离 |
| 建库成本 | `CREATE DATABASE` 相对轻（但每库一套系统目录） | `CREATE PLUGGABLE DATABASE` 可秒级克隆 |
| 连接方式 | `host/dbname` 必须指到具体库 | 服务名指到库；PDB 服务名指到 PDB |

> 实践建议：PG 里**不建议按“租户一库”泛滥建库**（每库一套系统目录、连接需切换、跨库查询麻烦）。常见做法是**一个业务库 + 多 Schema 分组**，或直接多实例（每实例一个集群）。需要“像 PDB 一样按租户隔离 + 可迁移”时，更接近的是 **多个 database 或独立实例**，而不是 Oracle 的 PDB。

### 2.7 深入：部署形态②：主从流复制 / 逻辑复制 / 分布式扩展

PG 的“横向扩展 / 高可用”**不走 Oracle RAC 那条路**（没有共享存储多实例写），而是**复制**：

- **物理流复制（Physical Streaming Replication）**：主库把 WAL 流式发给备库，备库**字节级重放**，得到一份完整副本；备库可 **hot standby 只读**对外提供读服务。这是 PG 最主流的 HA/读扩展方案，**语义最接近 Oracle Data Guard 物理备库**（见第 7 节）。
- **逻辑复制（Logical Replication）**：基于 **逻辑解码（logical decoding）** 把 WAL 解析成**行级变更（INSERT/UPDATE/DELETE）**，按**表**发布到订阅端；订阅端可以是**不同结构、不同主版本**的 PG。用途：跨版本升级、部分表同步、异构接入，**接近 Oracle 逻辑备用库 / CDC**。
- **连接池横向扩展**：因为 PG 是**进程模型**，高并发短连接要靠 **PgBouncer / Pgpool-II** 做连接复用（见第 8 节）。
- **分布式扩展**：需要分片/多机写时用 **Citus / Greenplum**（PG 扩展）或分库分表中间件——这是 PG 生态里最接近“RAC 式扩展”的形态，但**原理与 RAC 的 Cache Fusion 完全不同**。

> 一句话记忆：**PG 的“横向”= 复制（流复制做 HA/读、逻辑复制做迁移/异构、Citus 做分片）；它没有 RAC 那种“多实例共享一份数据、全局缓存融合”的形态。**

---
## 3. PostgreSQL 的内存结构：共享内存 + 每后端私有内存

按 Oracle 的说法，内存分 **SGA（共享）+ PGA（私有）**。PG 是同样的二分，但**命名与粒度不同**，而且**多了一个关键差异：PG 没有共享的 SQL 执行计划池**。

### 3.1 总览图

```
                    PostgreSQL 集群的内存
   ┌───────────────────────────────────────────────────────────────┐
   │          Shared Memory（共享内存，postmaster 启动时分配）        │
   │           所有 backend + 所有后台进程都能访问                    │
   │                                                                │
   │  ┌─────────────────┐  ┌─────────────────┐  ┌────────────────┐ │
   │  │ Shared Buffers  │  │ WAL Buffers     │  │ Lock / LWLock   │ │
   │  │ （8KB 页缓存）   │  │ （WAL 写入缓冲） │  │ 锁表 / 轻量锁   │ │
   │  │  shared_buffers │  │  wal_buffers    │  │                │ │
   │  └─────────────────┘  └─────────────────┘  └────────────────┘ │
   │  ┌─────────────────┐  ┌─────────────────┐  ┌────────────────┐ │
   │  │ 事务状态         │  │ Proc Array      │  │ 统计 / FSM /    │ │
   │  │ pg_xact/subtrans │  │ 活跃会话快照     │  │ VM / SSI 谓词锁 │ │
   │  │ MultiXact 缓冲   │  │ (给并发判断用)   │  │ 复制槽 / 提交时间│ │
   │  └─────────────────┘  └─────────────────┘  └────────────────┘ │
   └───────────────────────────────────────────────────────────────┘

   每个连接 独占的私有内存（在 backend 进程内）：
   ┌───────────────────────────────────────────────────────────────┐
   │  Backend Local Memory（≈ Oracle 的 PGA，但没有“共享 SQL 区”概念）│
   │   work_mem（排序/哈希） / temp_buffers（临时表页）               │
   │   maintenance_work_mem（VACUUM/CREATE INDEX/备份）              │
   │   Catalog Cache / Plan Cache（每个后端私有！） / 局部锁 / 状态   │
   └───────────────────────────────────────────────────────────────┘
```

### 3.2 共享内存各区域的职责

| 共享内存区域 | 里面放什么 | 对应 Oracle | 关键参数 |
|-------------|-----------|-------------|---------|
| **Shared Buffers（共享缓冲区）** | 从数据文件读进来的 **8KB 页（Page）** 的副本，含已改的“脏页” | Database Buffer Cache | `shared_buffers`（默认 128MB，建议内存 25% 左右） |
| **WAL Buffers（WAL 缓冲区）** | 每个 DML 产生的 **WAL 记录**，提交时由 WAL writer/backend 刷入 `pg_wal/` | Redo Log Buffer | `wal_buffers`（默认 `-1` → shared_buffers 的 1/32，≤16MB） |
| **Lock tables / LWLocks** | 表锁/行锁（heavyweight locks）、轻量锁（LWLock，保护共享结构） | 队列/闩锁（Enqueue/Latch） | `max_locks_per_transaction`、`max_pred_locks_per_transaction` |
| **事务提交状态** | 每个事务的**提交/回滚状态**（`pg_xact`，原 clog）+ 子事务 + **MultiXact** | UNDO + 相关元数据 | （自动） |
| **Proc Array** | 当前所有活跃后端的事务状态，**并发扫描时用来判断元组可见性** | Oracle 靠 SCN，不需要活跃事务表 | （自动） |
| **FSM / VM 缓冲** | **空闲空间映射（FSM）** 与 **可见性映射（VM）** 的页缓存 | 段头/位图块 | （自动） |
| **SSI 谓词锁** | Serializable 快照隔离的 **SIREAD 谓词锁** | —（Oracle SERIALIZABLE 不靠谓词锁） | `max_pred_locks_per_transaction` |
| **统计信息** | 15+ 累计统计（`pg_stat_*` 的共享内存版） | AWR/统计 | `track_counts` |
| **异步 I/O 结构（PG18）** | AIO 的提交队列/完成队列 | — | `io_method`、`io_workers` |

> 关键点：**PG 的共享内存是在 `postmaster` 启动时一次性分配的固定大小**（大量结构大小由参数决定，改参数常需重启）。`shared_buffers` 调大要重启；`work_mem` 是“每排序/每哈希节点”的量，不是全局。

### 3.3 每后端私有内存里放什么

每个连接 fork 出的 **backend 进程**有自己私有的一块内存，主要放：

- **`work_mem`**：**每个排序（ORDER BY）、每个哈希表（hash join / hash agg）** 的可用内存；不够就把中间结果**溢出到临时文件（temp files）**（变慢）。⚠️ 注意：一个复杂查询可能同时开多个排序/哈希节点，**每节点各吃一份 `work_mem`**，所以要按“最坏并发节点数”估。
- **`maintenance_work_mem`**：`VACUUM`、`CREATE INDEX`、`ALTER TABLE ADD FOREIGN KEY`、`pg_dump` 等维护操作的内存。
- **`temp_buffers`**：临时表的页缓存。
- **Catalog Cache（系统目录缓存）**：表结构、列类型、权限等元数据的缓存——**这是“每后端私有”的**，不像 Oracle 有共享的 Data Dictionary Cache。
- **Plan Cache（计划缓存）**：**每个后端私有**的计划缓存，只服务本连接；连接断开即消失。⚠️ **PG 没有 Oracle 那种“全局共享的 Library Cache”**——这是第 8 节“共享池满了”在 PG 里不成立的根因。

> 记忆口诀：**Shared Buffers 是“公共黑板”（放数据页与 WAL 记录）；work_mem 等是“你自己的草稿纸”（排序/哈希用完即弃）。而 PG 把“解题步骤（执行计划）”也放在你自己的草稿纸上——所以没有一个会被所有会话共用的“共享池”。**

### 3.4 与 Oracle 内存结构对照（速记）

| 用途 | Oracle | PostgreSQL |
|---|---|---|
| 数据页缓存 | Database Buffer Cache（SGA） | Shared Buffers（共享内存） |
| redo/WAL 缓冲 | Redo Log Buffer（SGA） | WAL Buffers（共享内存） |
| SQL 执行计划缓存 | **Shared Pool → Library Cache（全局共享）** | **每后端私有 Plan Cache（不共享）** |
| 数据字典缓存 | Shared Pool → Data Dictionary Cache | 每后端 Catalog Cache（不共享） |
| 排序/哈希内存 | PGA | **每后端 `work_mem`** |
| 大块内存（备份/并行） | Large Pool | `maintenance_work_mem` 等 |
| 缓存大小调整 | 多数组件可在线调整 | `shared_buffers` 等**固定**，多在启动时分配 |

---

## 4. PostgreSQL 的进程模型：postmaster、backend 与后台进程

这是 PG 与 Oracle 差异**最大、也最该先理解**的一节：**PG 是“一连接一进程（process-per-connection）”，不是“一实例一组固定后台进程 + 共享服务器池”那种模型。** 记住下面这些“人设”，第 6 节的 DML 旅程才读得顺。

### 4.1 主进程与连接进程

| 进程 | 人设 / 职责 | 关键动作 |
|------|------------|---------|
| **postmaster**（父进程） | 总机 + 监工 | 监听端口、`fork` 出 backend；**任何子进程崩溃，它先重置共享内存、杀掉其余子进程，再拉起恢复流程**（防止数据不一致） |
| **backend（后端进程）** | 真正干活的“一连接一进程” | 解析 SQL、规划、执行、读写共享缓冲、写 WAL；**每个连接一个**，断开即退出 |
| **startup process（启动进程）** | 崩溃恢复员 | 启动时若发现上次未干净关闭，先**重放 WAL（崩溃恢复）**，再开放连接 |

> ⚠️ 与 Oracle 的对照：Oracle 里“服务器进程”也有专用/共享两种模式，但**后台进程（PMON/SMON/DBWn/LGWR/CKPT/ARCn）是固定一组、全库共享**；PG 的“worker”大多是**按需 fork 的**（walwriter/checkpointer/bgwriter 是常驻，autovacuum/parallel/replication worker 是按需）。

### 4.2 常驻后台进程

| 进程 | 人设 / 职责 | 对应 Oracle |
|------|------------|-------------|
| **Background Writer（bgwriter）** | 刷盘工（“懒”） | **DBWn** | 循序渐进地把**脏共享缓冲**写回数据文件，平滑 I/O、避免检查点尖峰 |
| **Checkpointer（检查点进程）** | 同步员 | **CKPT** | 周期性做**检查点**：把检查点之前的所有脏页刷盘，写检查点记录到 WAL，更新 `pg_control` |
| **WAL Writer（WAL 写进程）** | 持久化守门员 | **LGWR** | 周期性把 **WAL Buffers** 刷到 `pg_wal/`；**提交时由 backend 自己调用 `XLogFlush` 强制刷盘**（这点与 Oracle“必须 LGWR 落盘”不同，PG 里 backend 可直接落盘） |
| **Autovacuum Launcher + Workers** | 清道夫调度 + 清洁工 | **SMON/PMON 的一部分 + 空间回收** | 自动触发 `VACUUM`/`ANALYZE`，回收死元组、更新统计、防止 XID 回卷 |
| **Logical Replication Launcher** | 逻辑复制调度 | （Oracle 无直接对应） | 启动逻辑复制 apply worker、table sync worker |
| **Archiver（归档进程）** | 归档员 | **ARCn** | WAL 段写满/切换后，按 `archive_command`/`archive_library` 归档；PITR 与流复制的备份基础 |
| **WAL Summarizer（PG17+）** | 摘要员 | — | 生成 WAL 摘要，支撑 `pg_basebackup --incremental` 增量备份 |
| **Background/Parallel Workers** | 临时工 | **并行查询 slave** | 并行查询、并行 vacuum/index build 用的 worker（`max_worker_processes`、`max_parallel_workers`） |
| **walsender / walreceiver** | 复制通道 | **LNS/RFS + MRP** | 主库 walsender 发 WAL；备库 walreceiver 收 WAL、startup 进程重放（第 7 节） |
| **syslogger** | 日志员 | — | `logging_collector=on` 时把日志收进文件 |
| **I/O Workers（PG18）** | 异步 I/O 搬运工 | — | `io_method=worker` 时负责异步读，提升顺序扫描/位图堆扫描/VACUUM 吞吐 |

> 历史变迁：PG 15 之前有个独立的 **stats collector 进程**；PG15 起改为**共享内存累计统计**，去掉了这个进程，`pg_stat_*` 更新更及时。

### 4.3 进程协作一句话总结

> **postmaster 像总机——每个连接 `fork` 一个 backend 亲自干活；backend 自己写 WAL 并能在提交时直接 `fsync`；bgwriter 懒刷脏页、checkpointer 定期拉平账、walwriter 兜底刷 WAL、autovacuum 打扫死元组、archiver 留底供 PITR/复制、startup 负责崩溃后重放。** 崩溃时的“实例恢复”不是靠某个常驻 SMON，而是 **postmaster 杀掉全场 → 重启 → startup process 从最近检查点重放 WAL**。

---

## 5. 读不阻塞写、写不阻塞读：PostgreSQL 的 MVCC

这是所有现代关系库的核心并发设计，也是“读不影响写、写不影响读”的答案。PG 的 MVCC 与 Oracle **目标相同、实现完全相反**——先记住这张“反面照”，后面就顺了：

| | Oracle | PostgreSQL |
|---|---|---|
| 旧版本存哪 | **UNDO 表空间**（前像，可被覆盖） | **就存在原表的数据页里**（整行旧版本） |
| 读旧版本怎么来 | **现算**：用 undo 把块“回滚”成 CR 块 | **直接读**：找到那行旧的物理元组 |
| 谁是“版本” | 只有一份行 + 一串 undo | **每行可能有多个物理元组版本** |
| 旧版本何时消失 | 被 undo 覆盖 / 过期（ORA-01555） | **VACUUM 回收**（否则**表膨胀**） |
| 时间戳 | 全局 **SCN** | **XID（事务号）+ 活跃事务快照** |
| 代价 | 长查询可能 ORA-01555；undo 需管理 | **必须 VACUUM**，否则膨胀；XID 会回卷 |

### 5.1 两条基本性质

- **读不阻塞写**：读者不需要等写者释放锁，它直接读“自己该看到的那个版本”。
- **写不阻塞读**：写者改数据时，不改原地——它**新增一个元组版本**，旧版本留给读者继续读。
- 注意：**MVCC 只解决“读-写互不阻塞”，不解决“写-写冲突”**。两个人同时改同一行，仍然要靠**行锁排队**（第二个 `UPDATE` 会等第一个提交/回滚），这正是 `SELECT ... FOR UPDATE` 与隐式行锁的用武之地。

### 5.2 PG 靠什么实现？XID + 元组版本 + 快照

PostgreSQL 的底层哲学一句话概括：

> **PostgreSQL 不改旧数据，而是“每次修改生成一个新版本元组”；一致性读靠“快照 + 每个元组的 XID 可见性判断”决定你能看到哪个版本。**

三个核心部件：

1. **XID（Transaction ID，事务号）**：每个写事务分配一个 32 位递增号。每个元组头部记 `xmin`（**创建**它的事务）与 `xmax`（**删除/更新**它的事务，0 表示还活着）。**XID 就是 PG 的“逻辑时钟”**（但**没有 Oracle SCN 那种全局单调时钟**，可见性靠“活跃事务集合”比较）。

2. **快照（Snapshot）**：一次查询/事务开始时，取一份“当前活跃事务集合”的快照，主要字段：
   - `xmin`：**最老还在跑的事务号**（在此之前的都已结束）；
   - `xmax`：**下一个待分配的事务号**（在此之后的都还没开始）；
   - `xip[]`：**此刻仍在进行中**的事务号列表；
   - `curcid`：当前命令号（同一事务内语句先后顺序）。
   - 快照类型：**RC 每条语句取一次**；**RR/SI 整个事务取一次**（见 §5.4）。

3. **元组版本 + 提交状态（pg_xact）**：PG 不看“undo”，而是**查该元组的 `xmin/xmax` 对应事务的提交状态**（存于共享内存/`pg_xact/` 的 2 bit 状态：进行中/已提交/已回滚）。可见性规则据此判断（§5.5）。

### 5.3 一致性读（Consistent Read）的过程

以默认的 **READ COMMITTED（语句级一致性）** 为例：

```
① 一条 SELECT 开始，backend 取一份 Snapshot（xmin/xmax/xip[]）。

② 顺序/索引扫描遇到一个元组版本 T：
     └─ 用快照判断 T 是否“对我可见”（见 §5.5 规则）：
          ├─ 可见 → 返回该版本（可能还要看 xmax 是否已提交/是否在我快照之后）
          └─ 不可见 → 跳到下一个版本（同一行的旧/新版本，或下一个元组）

③ 更新（UPDATE）时：不改原地，而是：
     ├─ 把旧版本的 xmax 标成“我”
     └─ 在同页（优先）或新页插入一个新版本元组（xmin = “我”）
        └─ 若没改到任何索引列，且同页有空隙 → HOT 更新（不更新索引）

④ 旧版本不会被立刻删除——留给“还在用旧快照的读者”；由 VACUUM 事后回收。
```

由此得到三个关键结论：

- **未提交的修改对别人不可见**：读者看到的元组若 `xmin` 未提交，直接当作不存在——所以 PG **天然没有脏读**，读者也完全不需要等写者。
- **同一个逻辑行可能有多个物理版本并存**：这是“读不阻塞写”的物理基础，也是“**表会膨胀**”的根源（旧版本占用空间直到 VACUUM）。
- **PG 没有“CR 块现算”这一层**：Oracle 是“一份数据 + undo 现场回滚”，PG 是“多份物理版本 + 快照挑一个”。**PG 读旧版本不需要应用 undo，但需要空间留存旧版本。**

### 5.4 隔离级别与快照的关系

PostgreSQL 对外暴露 **三个可设级别**（另有 1 个“名义上存在”的），比大多数人以为的更强（详见 [DB事务隔离级别.md](DB事务隔离级别.md)）：

| 级别 | 快照粒度 | 效果 | 异常 | 备注 |
|------|---------|------|------|------|
| **READ COMMITTED（默认）** | **每条语句**开始时取快照 | 单条语句内部看到同一时点；语句之间可见别人新提交的数据 | 可能不可重复读/幻读 | PG 里**相当常用**；每个语句重取快照 |
| **REPEATABLE READ** | **事务第一条语句**时取快照，全程复用 | 整个事务看到同一时点（**快照隔离 SI**） | **防不住写偏斜**；冲突时报 `40001 serialization_failure` | ⚠️ **PG 的 RR 比 MySQL 的 RR 强**：**不靠间隙锁也防住幻读**（因为快照隔离），但可能报序列化失败 |
| **SERIALIZABLE** | 事务级快照 + **SSI 冲突检测** | 真·可串行化（**SSI，Serializable Snapshot Isolation**） | 理论上无；冲突时 **abort** 报 `40001` | 靠 **SIREAD 谓词锁**检测“危险的读写冲突”，必要时让一个事务回滚，**是真正的串行化** |
| READ UNCOMMITTED | — | **PG 把它当 RC 处理**（PG 无脏读） | 同 RC | 写了也不报错，静默降级为 RC |

> 关键纠正（很多从 MySQL 来的人会误解）：**PG 的 REPEATABLE READ 默认就防幻读**（它是快照隔离，不靠 Next-Key Lock）；**SERIALIZABLE 才是真串行化**（SSI，靠谓词锁 + 冲突检测，代价是**可能让事务回滚并需要应用重试**）。这与 Oracle 的 SERIALIZABLE（其实是 SI、防不住写偏斜）也不一样——**PG 的 SERIALIZABLE 比 Oracle 的更强，但要求应用能处理 `40001` 重试。**

> 应用侧铁律：**只要用 `REPEATABLE READ` / `SERIALIZABLE`，就必须写“遇到序列化失败（SQLSTATE 40001）就重试整个事务”的逻辑**，否则线上会偶发报错。

---
### 5.5 深入：堆元组（HeapTupleHeader）与快照结构

前面把 MVCC 当“黑盒”用了（快照挑版本、VACUUM 收旧版）。这一节把它拆开，看清**元组头部到底记了什么、可见性到底怎么算**。理解了它，“读不阻塞写”就不再是空话。

#### 5.5.1 元组头（HeapTupleHeaderData）字段清单

每个堆元组（行版本）开头约 **23 字节**的头部，关键字段：

| 字段 | 含义 | 作用 |
|------|------|------|
| **t_xmin** | **创建**这个版本的事务号（插入者 / 更新者） | 判断“这行是谁插的、提交没” |
| **t_xmax** | **删除或锁住**这个版本的事务号（0 = 仍有效） | `DELETE`/`UPDATE` 会设它；**行锁也用 xmax 表示** |
| **t_cid** | 命令号（cmin/cmax，同一事务内第几条命令） | 处理“同一事务内先删后插”等语句内先后 |
| **t_xvac** | 早期 `VACUUM FULL` 用（一般 0） | 兼容字段 |
| **t_ctid** | **版本链指针**（指向自己，或被更新后的**新版本**所在 (block, offset)） | 顺着它能找到同一行的**最新版本** |
| **t_infomask2** | 属性数 + 标志位（如 `HEAP_KEYS_UPDATED`、`HEAP_HOT_UPDATED`） | HOT 更新链、更新类型 |
| **t_infomask** | **状态位**（关键！）：`HEAP_XMIN_COMMITTED`/`HEAP_XMIN_INVALID`、`HEAP_XMAX_COMMITTED`/`HEAP_XMAX_INVALID`、`HEAP_XMAX_IS_MULTI`、`HEAP_ONLY_TUPLE` 等 | **缓存“提交状态”判断，避免每次都查 pg_xact** |
| **t_hoff** | 头部长度（到数据的偏移） | 定位实际列数据 |

> **HOT（Heap-Only Tuple）更新**：若 `UPDATE` 没改到任何被索引的列，且**新版本能塞进同一页**，PG 就不动索引，只把旧版本的 `t_ctid` 指向新版本，并用 `HEAP_HOT_UPDATED`/`HEAP_ONLY_TUPLE` 标记。这样**索引不需要新增条目**，能显著减少索引膨胀——这也是“为什么索引列别乱改”的底层原因。

#### 5.5.2 可见性判断规则（一致性读的核心）

判断一个元组版本 T 对当前快照 S 是否可见（简化版）：

```
可见  ⇔
  ① T.xmin 已提交，且 T.xmin 不在 S.xip[]（不是活跃事务），且 T.xmin < S.xmax
  ② 并且以下之一成立：
       · T.xmax = 0（没被删/改）
       · T.xmax 对应事务已回滚（无效）
       · T.xmax 是本事务自己、且该删除发生在本条命令之后（curcid 判断）
       · T.xmax 对应事务在“我快照之后”才开始（对我没影响）
```

一句话：**“这行是我快照之前已提交的，且没被一个我该看到的、在快照前生效的事务删掉”——只要满足，就返回它。** 注意这套规则**不需要 undo**，只需要“元组头 + 提交状态（pg_xact）+ 当前快照”。

#### 5.5.3 Hint Bits：为什么“SELECT 也会写 WAL”

- 每次判断可见性都要查 `xmin/xmax` 的提交状态（先看元组头的 infomask 缓存，miss 才去查 `pg_xact`）。**第一个判断该元组的事务，会把结果“顺手写回元组头的 infomask”**（置 `HEAP_XMIN_COMMITTED` 等），这叫 **Hint Bits（提示位）**。
- ⚠️ **问题**：写回 Hint Bits 会**弄脏这个页**，于是要**写 WAL**（因为它是数据页的改动）。这就是著名的 **“一条纯 SELECT 有时也会产生 WAL”** 现象——和 Oracle 的**“延迟块清除让 SELECT 产生少量 redo”**异曲同工！
- 影响：只读库若频繁首次访问老数据，会“莫名”产生 WAL、变脏页、被 checkpoint 刷盘。

#### 5.5.4 `pg_xact` / `pg_subtrans` / `pg_multixact`：提交状态存在哪

- **`pg_xact`（原 `pg_clog`）**：每个 XID 存 **2 bit** 提交状态（进行中/已提交/已回滚），按段分文件。
- **`pg_subtrans`**：子事务（savepoint）与其父事务的映射。
- **`pg_multixact`**：**MultiXact**——当一个行锁被多个事务同时“共享”持有（如多个 `SELECT FOR SHARE`）时，`xmax` 放不下单一 XID，就用 MultiXact ID 表示“一组事务”。
- **`pg_commit_ts`**：仅当 `track_commit_timestamp=on` 时记录提交时间戳。

> 与 Oracle 对照：Oracle 用 **SCN + UNDO** 做一致性读；PG 用 **XID + 元组版本 + 提交状态 + 快照**。Oracle 关心“块上有没有比我更新的变更”，PG 关心“这个版本的创建/删除事务对当前快照是否可见”。

#### 5.5.5 快照隔离（SI）与 SSI

- **快照隔离（SI = PG 的 REPEATABLE READ）**：事务全程用一个快照，读写不互相阻塞；**能防脏读/不可重复读/幻读**，但**防不住“写偏斜（write skew）”**——两个事务各自读了彼此没改的旧数据、又改了不同的行，破坏跨行不变量。
- **SSI（可串行化快照隔离 = PG 的 SERIALIZABLE）**：在 SI 之上，额外记录 **SIREAD 谓词锁**，检测“读写依赖环”，一旦发现**可能不可串行化**就**主动 abort 一个事务**（`40001`）。它不是靠锁死，而是靠“检测 + 回滚”，所以**并发不降太多，但应用必须能重试**。

### 5.6 深入：VACUUM / autovacuum / freeze / 表膨胀

PG 的 MVCC “把旧版本留在原表”，所以**必须有人回收**——这就是 VACUUM 的位置，也是 PG 与 Oracle 运维观感差异最大的地方（Oracle 靠 UNDO + `UNDO_RETENTION`，不会“表膨胀”，但会 ORA-01555）。

#### 5.6.1 VACUUM 干三件事

1. **回收死元组**：把“任何快照都不会再看到”的旧版本标记为空闲、可复用（**注意：普通 VACUUM 只把空间标为可复用，不还给操作系统**）。`VACUUM FULL` 会把表重写成新文件并真正缩容，但**加排他锁、代价大**。
2. **更新 FSM / VM**：把空闲空间写进 **FSM（Free Space Map）**，把“整页都是活元组、可见”的页标进 **VM（Visibility Map）**（供 **index-only scan** 用）。
3. **Freeze（冻结）**：把“足够老”的元组的 `xmin` 替换成特殊的 **FrozenXID**，让未来 XID 回卷不至于让老数据“变成未来版本”。这是防 **XID 回卷（wraparound）** 的关键，否则数据库会强制停机。

#### 5.6.2 autovacuum：自动清洁工

- `autovacuum` 默认开启：**launcher 定期唤醒**，按**死元组比例**（`autovacuum_vacuum_scale_factor` 默认 0.2、`autovacuum_analyze_scale_factor` 默认 0.1）触发 `VACUUM`/`ANALYZE`，worker 数量上限 `autovacuum_max_workers`（默认 3），间隔 `autovacuum_naptime`（默认 1min）。
- **表级参数**：`ALTER TABLE t SET (autovacuum_vacuum_scale_factor=0.05);` 对大表很必要（大表按 20% 触发太迟，会先膨胀）。

#### 5.6.3 XID 回卷（wraparound）——PG 特有的“红色警戒”

- XID 是 **32 位循环**（约 42 亿，但安全使用半径约 **20 亿**）。若老数据迟迟不 freeze，新事务的 XID 会“追上并绕过”老 XID，导致老数据**突然对所有人不可见**（数据“消失”）。
- PG 的保护机制：当最老的未冻结 XID 逼近 20 亿，**日志告警**；逼近极限时**强制拒绝新写入甚至停机**（`ERROR: database is not accepting commands to avoid wraparound`）。
- **防它只有一个办法：让 autovacuum 正常把老数据 freeze 掉**。别长期关闭 autovacuum，别让长事务/复制槽一直挂着老快照。

#### 5.6.4 长事务 / 长快照的代价（对应 Oracle 的 ORA-01555）

- **Oracle 长查询**：需要的老版本 undo 被覆盖 → **ORA-01555 snapshot too old**（显式报错）。
- **PG 长事务 / 长快照**：旧版本**无法被 VACUUM 回收** → **表/索引持续膨胀**、autovacuum 追不上，**不报错但静默膨胀**（磁盘涨、扫描变慢、可能击穿 wraparound 警戒）。
- 共同教训：**别开长事务、别让 `idle in transaction` 会话长期挂着、别让复制槽/逻辑解码长期落后**——这会让“最老快照”卡住，两家都要付出代价，只是表现形式不同。

#### 5.6.5 实操：怎么观察 MVCC / VACUUM

```sql
-- 1) 表的膨胀程度（死元组 vs 活元组）
SELECT relname, n_live_tup, n_dead_tup,
       ROUND(n_dead_tup::numeric / NULLIF(n_live_tup + n_dead_tup, 0) * 100, 1) AS dead_pct,
       last_vacuum, last_autovacuum, last_analyze
  FROM pg_stat_user_tables
 ORDER BY n_dead_tup DESC
 LIMIT 20;

-- 2) 最老的未冻结事务（看是否逼近回卷警戒）
SELECT datname, age(datfrozenxid) AS xid_age
  FROM pg_database
 ORDER BY xid_age DESC;

-- 3) 当前活跃事务与它们最老的快照（看“谁在拖住 vacuum”）
SELECT pid, state, xact_start, backend_xmin, query
  FROM pg_stat_activity
 WHERE backend_xmin IS NOT NULL
 ORDER BY age(backend_xmin) DESC;

-- 4) 复制槽是否卡住 vacuum（槽会保留 WAL 与快照）
SELECT slot_name, slot_type, active, restart_lsn,
       pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)) AS retained
  FROM pg_replication_slots;

-- 5) 手工回收与统计、关闭膨胀页
VACUUM (ANALYZE, VERBOSE) big_table;
VACUUM FULL big_table;          -- 真缩容，但加排他锁，慎用

-- 6) 看单个元组的版本链（需 pageinspect 扩展）
CREATE EXTENSION IF NOT EXISTS pageinspect;
SELECT lp, t_xmin, t_xmax, t_ctid, t_infomask
  FROM heap_page_items(get_raw_page('your_table', 0));
```

#### 5.6.6 小结（与全文呼应）

- PG 的 MVCC = **XID + 元组多版本 + 快照 + pg_xact 提交状态**；**没有 undo**，旧版本**留在原表**。
- **元组头 `xmin/xmax/t_ctid/infomask`** 是一致性读的全部依据；**快照 `xmin/xmax/xip[]`** 是“挑版本”的尺子。
- 代价是**必须 VACUUM**：回收死元组、更新 FSM/VM、**freeze 防回卷**；**长事务/长快照导致膨胀**（≈ Oracle 的 ORA-01555 之“镜像代价”）。
- 它和 §5.3 的一致性读、§6 的 DML 流程（改 = 插新版本）、§11 的页/元组结构、§8 的膨胀治理是同一套机制的五个切面。

---
## 6. 一条 DML 的完整旅程：各进程如何配合

以一条 `UPDATE accounts SET balance = balance - 100 WHERE id = 1;` 为例，把第 3、4、5 节的所有部件串起来。这是理解 PG 全貌最关键的一节。

### 6.1 时序全景

```
客户端 App
   │  ① 通过连接（连接池 → backend 进程）发送 SQL（建议带参数 / prepared statement）
   ▼
【backend 进程】（代表该会话干活，私有内存里有 work_mem / plan cache / catalog cache）
   │  ② 解析（parse）→ 语义分析 → 重写 → 规划（plan）→ 生成执行计划
   │       ├─ 命中私有 plan cache → 直接复用（类比软解析，但每后端各存）
   │       └─ 未命中 → 重新规划并缓存（类比硬解析，但无全局共享池）
   │  ③ 执行：通过 Buffer Manager 去 Shared Buffers 找 id=1 所在页
   │       ├─ 命中（shared hit）→ 直接用
   │       └─ 未命中 → 从数据文件读入 Shared Buffers（PG18 可用异步 I/O）
   │  ④ 用 MVCC 快照定位“我该看到的”那个行版本（应用 WHERE 过滤）
   │  ⑤ 修改（此时只在内存里，未落盘）：
   │       a. 把旧版本元组的 t_xmax 标为“我”（相当于标记删除）
   │       b. 在同页（优先）或新页插入一个新版本元组（t_xmin = “我”，新 balance）
   │       c. 生成该页改动的 WAL 记录（含 full-page image，若需要）
   │       d. 把 WAL 写入共享内存的 WAL Buffers
   │       e. 在该行上加行锁（通过 xmax / MultiXact 表达；别人改同一行要等）
   ▼
【COMMIT】用户提交
   │  ⑥ 写“事务提交”WAL 记录，并标记 pg_xact 中本事务为“已提交”（提交是原子的）
   │  ⑦ backend 调用 XLogFlush 把 WAL 强制刷到 pg_wal（持久化！）
   │       默认 synchronous_commit=on 时，刷盘成功才向客户端返回 COMMIT 成功
   │  ⑧ 释放行锁
   │       注意：脏数据页此时【不一定】写回数据文件
   │       可选：下一位读者访问该页时顺手设置 Hint Bits（会再产生少量 WAL）
   ▼
【后台进程异步收尾】
   │  ⑨ bgwriter 在合适时机把脏页异步写回数据文件
   │  ⑩ checkpointer 周期性做检查点：把检查点前的脏页刷盘 + 写检查点记录 + 更新 pg_control
   │  ⑪ archiver 把写满的 WAL 段归档；walsender 把 WAL 流发给备库（第 7 节）
   │  ⑫ autovacuum 事后回收“任何人都看不到”的旧版本元组
```

### 6.2 几个必须理解的设计要点

1. **“提交 ≠ 立即写数据文件”**。COMMIT 只强制 **把 WAL 落盘**（保证“已提交就不会丢”），脏数据页由 bgwriter/checkpointer 懒写。这是 PG 高吞吐的关键：**把“随机写数据页”和“顺序写 WAL”解耦**——与 Oracle 的“LGWR 写 redo、DBWn 懒写数据块”**完全同构**。
2. **WAL 与“undo 的角色”**。PG **没有 undo 表空间**：一致性读靠“元组旧版本 + 快照”，回滚靠“把 `xmin/xmax` 标记为无效 + VACUUM 回收”。**WAL 是唯一的“日志”**，它既保护数据页（重做）、也保护事务提交状态——**PG 用 WAL + 元组版本二件套，替代了 Oracle 的 redo + undo 二件套**。
3. **WAL 先于数据页落盘（Write-Ahead 的本义）**：任何脏页一旦落盘，它对应的 WAL 必须**已经**落盘（`XLogFlush` 保证）。恢复时先用 WAL 前滚、再清理未提交。**这就是 WAL 名字的由来。**
4. **Hint Bits 让“读也写 WAL”**（§5.5.3）：纯 SELECT 首次访问老元组、设置提交提示位时会弄脏页并产生 WAL。
5. **进程协作一句话总结**：**backend 负责“算和改”，自己写 WAL 并在提交时 fsync；bgwriter 负责“懒落盘”，checkpointer 负责“定期拉平账”，walwriter 负责“兜底刷 WAL”，archiver/walsender 负责“留底与复制”，autovacuum 负责“回收旧版本”，startup 负责“崩溃恢复”。**

### 6.3 深入：WAL 记录（Redo Record）到底包含什么

WAL（Write-Ahead Log）就是 PG 的“redo 日志”。这一节把 **WAL 记录**拆开，看清它装了什么——它是“崩溃不丢数据”的根基，也是第 7 节流复制/逻辑解码的燃料。

#### 6.3.1 先澄清：WAL 记的是“页改动的字节级描述”，不是 SQL

- **记什么**：WAL 记录的是**“对某个数据页做了哪些字节级改动”**（redo 描述，physical/physiological logging），**不是把 SQL 再执行一遍**。恢复时按记录直接“贴”到页上，所以重放很快。
- **physiological（生理）日志**：**页内是物理的（记录偏移/长度），跨页是逻辑的（哪一页、哪一行版本）**——既比纯逻辑快，又比纯物理省空间。
- **一条 DML 产生多条 WAL**：改数据页、写提交记录、分配页、更新 FSM/VM……都可能各是独立 WAL 记录。
- **存在哪**：写在 **`pg_wal/` 里的 WAL 段文件**，由 backend/walwriter 顺序追加；写满后由 archiver 归档、由 walsender 发给备库。

#### 6.3.2 WAL 记录里具体包含哪些内容

一条 WAL 记录（`XLogRecord`）结构大致如下：

```
XLogRecord（WAL 记录头）
 ├─ xl_tot_len    记录总长度
 ├─ xl_xid        产生该记录的事务号 XID
 ├─ xl_prev       指向“本事务上一条 WAL 记录”的指针（用于恢复时回溯）
 ├─ xl_info       记录类型 + 标志
 ├─ xl_rmid       资源管理器 ID（rmgr：Heap / Btree / Xact / Clog / Storage ...）
 ├─ xl_crc        校验和
 └─ 数据部分（按 rmid 解释）：
      ├─ 主体数据（例如 heap 插入的行内容、更新后的新值）
      └─ Block References（块引用，0~N 个）：每个可带一个 full-page image
           ├─ 目标页 ID（tablespace/database/relfilenode/block）
           ├─ 应用该改动的偏移信息
           └─ 【可选】full-page image（整页快照，见下）
```

关键字段/概念：

| 字段 | 含义 |
|------|------|
| **xl_xid** | 这条改动属于哪笔事务 |
| **xl_prev** | 同一事务 WAL 链的“上一条”，恢复/解码时可回溯整笔事务 |
| **xl_rmid** | 资源管理器，决定怎么重放（heap 增删改、btree 分裂、事务提交/回滚、CLOG 改动…） |
| **Block Reference** | 这次改动落在哪个页、页内怎么改 |
| **Full-Page Image（FPI）** | **“全页写”**：检查点之后的**每页第一次修改**，把**整页 8KB** 写进 WAL |

#### 6.3.3 `full_page_writes`：PG 版“写断裂”防护（呼应 Oracle 的 Tailchk）

- **为什么需要**：操作系统/磁盘故障可能造成 **“半写页（torn page）”**——8KB 页只写进去一半。若重放时基于半写的页做“增量贴”，结果就错了。
- **PG 的解法**：`full_page_writes=on`（默认）时，**每个检查点之后，每页第一次被修改**，WAL 里先记**整页 8KB 快照**；重放时若页可疑，就**直接用整页镜像覆盖**，而不是在坏页上做增量。这样 torn page 被“整页覆盖”治愈。
- **代价**：检查点后第一次改每页会**放大 WAL**（整页 8KB 进 WAL），所以**检查点太频繁会显著增加 WAL 写入量**。
- 与 Oracle 对照：Oracle 块尾有 **Tailchk** 检测坏块、块级 redo + 检查点后的特殊处理来应对断裂；PG 用 **FPI** 在 WAL 里保存整页。

#### 6.3.4 WAL 不记录什么（澄清误区）

- **WAL 不直接记录 SELECT**（纯读无 WAL，**例外是 Hint Bits 设置**，见 §5.5.3）。
- **WAL 不记录“旧值（前像）”作为独立日志**——PG 没有 undo 日志；旧版本元组**留在数据页里**，靠 MVCC 可见性读，不靠 undo。
- **逻辑解码（logical decoding）**：当 `wal_level=logical` 时，PG 能把 WAL 解析成**行级 INSERT/UPDATE/DELETE**（逻辑复制用它），但**物理 redo 本身仍是页级**，只是附加了可供解码的信息。

#### 6.3.5 WAL 与“元组旧版本”的搭档关系（呼应 §5.5）

| | WAL（redo 侧） | 元组旧版本（undo 侧） |
|---|---|---|
| 记的内容 | 页**改之后**的样子（含 FPI） | 元组**改之前**的整行版本 |
| 作用 | 崩溃后**前滚重做**、流复制、PITR | 一致性读、回滚（靠标记无效） |
| 何时消失 | 检查点 + 归档保留策略后回收 | **VACUUM 回收** |
| 被谁保护 | WAL 自身是持久化载体，最底层 | **旧版本也受 WAL 保护**（它的产生/无效化都有 WAL） |

一句话记忆：**PG 用“WAL 记新样子 + 旧元组留在表里”这一对，替代 Oracle 的“redo 记新样子 + undo 记旧样子”。**

#### 6.3.6 WAL 被谁消费

1. **崩溃恢复（Crash Recovery）**：启动时 startup process 从**最近检查点**的 redo 位置重放 WAL 到最新，再清理未提交事务。因为起点是检查点，只需重放一小段。
2. **PITR / 介质恢复**：从基础备份还原，再用**归档 WAL** 重放到目标时间点/目标 LSN。
3. **流复制**：walsender 把 WAL 发给备库 walreceiver，备库重放（第 7 节）。
4. **逻辑解码**：把 WAL 解析为行变更，供逻辑复制/CDC。

---
### 6.4 深入：WAL 物理结构与 LSN 坐标体系

§6.3 讲了 WAL 记录里装了什么。这一节往下一层，看 **WAL 在磁盘上长什么样、记录怎么排、用什么“坐标”给任意一条 WAL 定位**——这是理解崩溃恢复、PITR、流复制同步的钥匙。

#### 6.4.1 WAL 段文件在磁盘上的样子

- **WAL 段（WAL segment）**：`pg_wal/` 下的一串文件，每个默认 **16MB**（`initdb` 时用 `--wal-segsize` 决定，之后不可改）。文件名形如 `000000010000000000000001`，**由时间线 + 逻辑段号 + 段内序号**编码。
- **只追加、顺序写、循环复用**：写满一个段 → **WAL 段切换（segment switch）** → 归档（开归档时）→ 循环使用；归档/被复制槽保留的段不能覆盖（**复制槽与归档会“钉住”WAL**，这也是复制槽落后会撑爆磁盘的原因）。
- **`pg_control`**：控制文件（单份，无 Oracle 那种多路复用），记录数据库状态、**最近检查点位置（redo start LSN）**、时间线等。**恢复起点就来自它。**

#### 6.4.2 LSN（Log Sequence Number）：WAL 流里的“门牌号”

**LSN 是 PG 的核心坐标**，是一个 **64 位无符号整数**，表示“这条 WAL 记录在 WAL 流中的字节偏移”：

```
LSN（64 位）  = ( 逻辑段号 logid ：高 32 位 )  +  ( 段内偏移 offset ：低 32 位 )
显示格式： X / Y  （两个 16 进制数，如 0/16B3740 → 段 0、偏移 0x16B3740）

├─ 段号 = 全局第几个 16MB 段（配合时间线）
└─ 段内偏移 = 该段内的字节位置
```

- **与 Oracle 对照**：**LSN 同时扮演了 Oracle 的 RBA（物理坐标）与 SCN（逻辑坐标）两个角色**——它既是“文件里的字节位置”，又天然单调递增，可作为“时间点/恢复范围”的边界。
- **一个事务/一条记录都有 LSN**：记录头 `xl_prev` 指向上一条，恢复时按 **LSN 递增顺序**重放。
- **时间线（Timeline）**：当备库提升为主库、或 PITR 后 `resume`，会换一条**新时间线**，避免与旧历史混淆。LSN 全域唯一要配合 **timeline** 才有意义（类似 Oracle RESETLOGS 后 sequence# 重置的注意点）。

#### 6.4.3 WAL 里几个“派生坐标”（恢复/复制时各司其职）

| 派生 LSN / 位置 | 含义 | 在哪看 |
|---|---|---|
| **pg_current_wal_lsn()** | 当前正在写的 WAL 位置（“写到哪了”） | `SELECT pg_current_wal_lsn();` |
| **pg_current_wal_insert_lsn()** | 已“插入”WAL 缓冲的位置（比 write/flush 更超前） | 同名函数 |
| **pg_current_wal_flush_lsn()** | 已经**刷盘（fsync）**到哪 | 同名函数 |
| **redo LSN / redo start** | 最近检查点记录的重做起点（崩溃恢复从此开始） | `pg_control_checkpoint()` |
| **checkpoint LSN** | 最近一次检查点的位置 | `pg_control_checkpoint()` |
| **backup start/stop LSN** | 基础备份的一致点（PITR 起点） | `pg_backup_start/stop`、`pg_control_checkpoint()` |
| **restart_lsn（复制槽）** | 该槽保证“WAL 不会被回收”的最低 LSN | `pg_replication_slots` |
| **replay_lsn / write_lsn / flush_lsn（备库）** | 备库“已重放 / 已写入 / 已刷盘”到哪 | `pg_stat_replication`、备库 `pg_stat_wal_receiver` |

#### 6.4.4 一条重要约束：脏页落盘必须晚于对应 WAL 落盘

**WAL 先于数据页（Write-Ahead）**：任何数据页的改动，其 WAL 必须**先**落盘，数据页才能落盘。恢复时靠 WAL 把数据页“补”到最新。这条铁律就是 **“提交必须先 flush WAL”** 的根本原因（§6.2 要点 3），也是 `synchronous_commit` 影响提交延迟的原因。

#### 6.4.5 这些坐标怎么被用于“定位 + 恢复 + 同步”

- **崩溃恢复**：从 `pg_control` 的 **redo LSN**（最近检查点）一路重放到 WAL 流的**末端**，按 LSN 递增重放记录，最后清理未提交事务。
- **PITR（时间点恢复）**：从基础备份还原，再依次重放**归档 WAL**，重放到 **目标时间 / 目标 LSN / 目标 XID / 目标事务**（`recovery_target_*`）为止。
- **流复制**：备库按 **LSN** 连续接收并重放主库 WAL，用 `sent_lsn / write_lsn / flush_lsn / replay_lsn` 描述进度（本组坐标是第 7 节同步复制的基础）。
- **复制槽**：按 **restart_lsn** 保留 WAL，防止“备库还没收到就被回收”。

#### 6.4.6 实操：怎么观察 WAL 与 LSN

```sql
-- 1) 当前 WAL 位置（write / flush / insert 三种口径）
SELECT pg_current_wal_lsn(),              -- 当前写入位置
       pg_current_wal_flush_lsn(),        -- 已刷盘位置
       pg_walfile_name(pg_current_wal_lsn());  -- 当前段文件名

-- 2) 检查点与 redo 起点（崩溃恢复从 redo 开始）
SELECT redo_lsn, checkpoint_lsn, timeline_id
  FROM pg_control_checkpoint();

-- 3) WAL 生成统计（看写入压力）
SELECT wal_records, wal_fpi, wal_bytes, wal_write, wal_sync
  FROM pg_stat_wal;

-- 4) 复制槽保留了多少 WAL（落后会撑爆磁盘！）
SELECT slot_name, wal_status, safe_wal_size,
       pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)) AS retained
  FROM pg_replication_slots;

-- 5) 手动切换 WAL 段（归档/复制调试用）
SELECT pg_switch_wal();
```

### 6.5 深入：检查点（Checkpoint）与崩溃恢复

- **检查点做什么**：checkpointer 周期性把**检查点之前的脏页全部刷盘**，并在 WAL 里写一条检查点记录、更新 `pg_control` 的 redo LSN。**检查点之后，这部分 WAL 若已归档就不再需要重放。**
- **为什么要检查点**：崩溃恢复只需从**最近检查点**重放一小段 WAL，而不是从头，**恢复时间可控**。检查点太稀疏 → 恢复慢、WAL 多；太频繁 → FPI 放大、刷盘尖峰多。
- **关键参数**：`checkpoint_timeout`（默认 5min）、`max_wal_size`（默认 1GB，达到就强制检查点）、`min_wal_size`、`checkpoint_completion_target`（0.9，摊平刷盘）。
- **崩溃恢复流程**：postmaster 崩溃/重启 → startup process 读 `pg_control` 拿 redo LSN → **前滚**重放 WAL 到末端 → **回滚**未提交事务（靠 pg_xact 状态与元组 xmax）→ 打开数据库接受连接。
- **与 Oracle 对照**：Oracle 的 CKPT 推进检查点、更新数据文件头与控制文件 SCN；PG 的 checkpointer 刷脏页 + 写检查点记录 + 更新 `pg_control` 的 redo LSN。**思路一致（“账本推进 + 恢复起点前移”）。**

---

## 7. 高可用与复制：流复制 vs 逻辑复制

PG 的“高可用 / 读扩展”建立在第 6 节的 WAL 之上。它有两种复制，**解决的问题不同**：**物理流复制**（整簇字节级副本，做 HA/容灾/只读扩展）与**逻辑复制**（表级行变更，做迁移/异构/部分同步）。本节先分别介绍（7.1/7.2），再讲机制与同步策略（7.3–7.5），最后对比选型（7.6–7.8）。

### 7.1 物理流复制（Physical Streaming Replication）—— 解决“高可用 / 容灾 / 只读扩展”

**底层功能**：**WAL 传输 + WAL 重放**。

- 主库的 **walsender** 进程持续把 WAL 发给备库；备库的 **walreceiver** 接收并写入本地 WAL，**startup process** 持续重放（recover）。
- 结果：备库与主库**字节级一致**（同一份数据文件的物理副本）。
- **Hot Standby**：备库在恢复期间可**只读打开**对外提供查询（`hot_standby=on`，默认 on）。这就是 PG 顺便提供的“读扩展”能力，语义**接近 Oracle Active Data Guard**。
- **只读查询与主库的冲突**：备库重放 WAL 时，若遇到与长查询冲突的清理动作，会**延迟重放**（`max_standby_streaming_delay`）或**取消查询**（`max_standby_archive_delay` / `hot_standby_feedback`）——这是 PG 特有的“重放 vs 长查询”权衡。

**形态**：

| 形态 | 说明 |
|------|------|
| 异步流复制（默认） | 性能零损耗，备库可能滞后（秒级），故障时有数据丢失窗口 |
| **同步流复制** | 主库提交等待备库确认（见 §7.5），可做到零丢失 |
| 级联复制（Cascading） | 备库再往下游发 WAL，减轻主库带宽 |
| 延迟备库（`recovery_min_apply_delay`） | 故意延迟重放，防“误删/误改”立即扩散 |

### 7.2 逻辑复制（Logical Replication）—— 解决“跨版本迁移 / 表级同步 / 异构”

**底层功能**：**逻辑解码（Logical Decoding）+ 发布/订阅**。

- 主库（Publisher）把 WAL 交给**逻辑解码插件**（默认 `pgoutput`）解析成**行级变更流**；
- 订阅端（Subscriber）用 **apply worker** 把这些变更**重新执行**到目标表上。
- **粒度是表**（`CREATE PUBLICATION ... FOR TABLE ...` / `FOR ALL TABLES`），可只同步部分表；**订阅端结构可不同、主版本可不同**（可以 16 订 18、用于滚动升级）。
- 关键前提：`wal_level=logical`；被复制的表要有**主键或 REPLICA IDENTITY**（否则 UPDATE/DELETE 无法定位）。
- 用途：**大版本升级**（逻辑复制搬迁）、**部分表分发**、**向异构系统喂数据**（CDC）。**接近 Oracle 逻辑备用库 / GoldenGate 场景**，但不支持 DDL 自动同步（DDL 要手工做）。

### 7.3 复制拓扑与关键机制

- **复制槽（Replication Slot）**：`physical`（流复制用）与 `logical`（逻辑复制用）。**槽会“钉住”WAL 不被回收**，保证备库没收到前不丢；但**槽落后 = 磁盘被 WAL 撑爆**的经典事故，必须监控 `pg_replication_slots` 的 `retained`。
- **时间线（Timeline）**：主备切换/提升会切换时间线，防止混用旧历史 WAL。`pg_rewind` 可把旧主库快速“回滚”成新主库的备库。
- **`pg_basebackup`**：做物理基础备份/搭备库；配合 `pg_wal` + 归档做 **PITR**。
- **复制用户与认证**：`pg_hba.conf`、复制专用角色（`REPLICATION` 权限）。
- **流复制备库 → 逻辑解码（PG16+）**：可以在备库上做逻辑解码，减轻主库负担。

### 7.4 高可用切换（Failover）与工具

- **PG 本身不做自动故障转移**：promote（`pg_ctl promote` / `SELECT pg_promote()`）是手动或靠外部工具。
- 主流 HA 方案：
  - **Patroni**（+ etcd/Consul/ZooKeeper 做分布式选主，最主流）；
  - **repmgr**（社区常用，配合 PostgreSQL）；
  - **pg_auto_failover**；
  - 云托管（RDS/Aurora/Cloud SQL 等自带）。
- 关键：**要配好探活、仲裁、VIP/连接串漂移、以及“脑裂”防护**（没有仲裁的自动切换很危险）。

### 7.5 同步复制的“旋钮”：`synchronous_commit` 与 `synchronous_standby_names`

**旋钮一：主库提交要等到哪一步（`synchronous_commit`，五档）**

| 档位 | 含义 | 风险/性能 |
|------|------|----------|
| `off` | 提交**不等**本地 WAL 落盘，连本地 fsync 都省 | 最快，但**本机崩溃也可能丢最近事务** |
| `local` | 等**本地** WAL 落盘，不等备库 | 本机不丢；备库故障不影响 |
| `remote_write` | 等**备库写完（到 OS 缓冲）** | 备库 OS 崩溃可能丢 |
| `on`（默认） | 等**备库把 WAL 刷盘（flush）** | 常见“同步复制”，可零丢失 |
| `remote_apply` | 等**备库把变更 apply 完** | 最强一致（读到即最新），延迟最大 |

**旋钮二：要等哪几个备库（`synchronous_standby_names` + `ANY/FIRST`）**

```
synchronous_standby_names = 'FIRST 2 (s1, s2, s3)'   -- 前 2 个（按优先级）
synchronous_standby_names = 'ANY 1 (s1, s2, s3)'     -- 任意 1 个（法定人数，PG10+）
```

- **`FIRST k`**：按列表顺序取前 k 个同步；前面挂了才轮后面的。
- **`ANY k`**：只要 k 个中有 k 个确认即可（法定人数，更适合跨机房多地部署）。
- **没有同步备库接活时，主库提交会一直等**（可能挂起）——这类风险在 PG 和 Oracle 的“最大保护模式”下同样存在。

**与 Oracle 对照**：`synchronous_commit` 五档 ≈ Oracle Data Guard 的 **SYNC/ASYNC + AFFIRM/NOAFFIRM** 组合；`synchronous_standby_names` 的 `ANY/FIRST k` ≈ **多同步备库 + 法定人数**。

### 7.6 对比表：流复制 / 逻辑复制 vs Oracle Data Guard / True Cache

| 维度 | PG 物理流复制 | PG 逻辑复制 | Oracle Data Guard | Oracle True Cache |
|------|--------------|------------|-------------------|-------------------|
| 底层技术 | WAL 传输 + 重放 | 逻辑解码 + 行变更重放 | Redo Transport + Redo Apply | redo apply → 内存 buffer cache |
| 粒度 | **整簇**（所有库/所有表） | **表级**（可部分） | **整库** | 热数据工作集 |
| 数据一致性 | 字节级一致 | 行级、可能有序延迟 | 块级一致 | 内存只读近实时 |
| 备/副本可读 | ✅ hot standby 只读 | ✅ 订阅端可读写（默认读） | ✅（ADG） | ✅ 只读 |
| 同步零丢失 | ✅（`remote_apply`/`on` + 同步备库） | 通常异步（不保证零丢失） | ✅（最大保护/最大可用） | async 默认，可 sync |
| 跨版本 | ❌（要同主版本） | ✅（跨主版本） | 需同级/兼容 | 需对应版本 |
| 主要用途 | **HA / 容灾 / 读扩展** | **迁移 / 部分同步 / CDC** | 容灾 / 高可用 | 低延迟读缓存 |
| 自动故障转移 | ❌（靠 Patroni 等外部工具） | 不适用 | ✅（Broker/Fast-Start Failover） | 不适用（只读） |
| 授权 | 开源免费 | 开源免费 | 付费选件（ADG） | 企业版/许可 |

### 7.7 优缺点

**物理流复制优点**：整簇一致、支持只读扩展、可做 PITR、生态成熟（Patroni）；同步模式可零丢失。
**物理流复制缺点**：必须同主版本、整簇复制（粒度粗）、备库只读、自动切换要靠外部工具、复制槽落后会撑爆磁盘。

**逻辑复制优点**：表级细粒度、支持跨版本/异构、订阅端可写（可做双向/重构）；适合不停机升级。
**逻辑复制缺点**：不自动同步 DDL、需主键/REPLICA IDENTITY、初始同步与冲突处理麻烦、默认异步（非零丢失）、大事务/大量变更时解码有开销。

### 7.8 使用场景怎么选

- **怕主库宕机、要容灾、要只读扩展** → **物理流复制 + hot standby**（配 Patroni 做自动切换）。
- **要跨大版本升级、只同步部分表、向异构系统喂数据** → **逻辑复制**。
- **想要“读缓存贴近应用、几乎无盘”** → PG 生态里**没有 True Cache 的等价物**，通常靠 **hot standby / 只读副本 + PgBouncer**，或外部缓存（Redis）；一致性要靠自己保证。
- 简单判断：**物理流复制是“买保险 + 加个只读副本”，逻辑复制是“搬迁/分发管道”。**

---

## 8. “共享池满了”在 PostgreSQL 里对应什么 + 日常写 SQL 的正确姿势

Oracle 有“Shared Pool 满 / ORA-04031（硬解析炸穿）”的经典问题。**在 PostgreSQL 里，这个问题基本不存在**——但会换成另外几种“爆”。这一节先讲清为什么，再给 PG 的 SQL 正确姿势。

### 8.1 先澄清：PG 没有全局共享的 SQL 计划池

- **Oracle**：所有会话共享一个 **Shared Pool → Library Cache**，SQL 文本 + 执行计划集中缓存；不用绑定变量 → 大量一次性游标 → 池被占满/碎片化 → **ORA-04031 + latch 争用**。
- **PostgreSQL**：**执行计划缓存在每个 backend 的私有内存里**（`plancache`），**不跨会话共享**。连接断开，缓存即消失。没有共享的 Library Cache，也**没有 ORA-04031 这种“共享池分配不出连续内存”的报错**。
- 结论：**PG 不会“共享池满”，但会因为“没有全局计划缓存 + 进程模型”省下了 latch 争用，却在别处付出代价**（见 8.2）。

> 但也别走极端：PG 的计划缓存**每连接一份**，所以在**大量短连接**场景，每次新连接都要重新解析+规划（硬解析成本被重复支付），这正是**必须上连接池**的原因之一。

### 8.2 PG 里真正会“满 / 爆”的是什么

| “爆”的现象 | 根因 | 对应 Oracle 里的类似问题 |
|------------|------|------------------------|
| **连接数打满 / 内存暴涨** | **进程模型**：每连接一个 backend 进程（基线几 MB~十几 MB），1000 连接 ≈ 上千进程 | Oracle 用共享服务器模式缓解；PG 靠 **PgBouncer** |
| **临时文件打满磁盘 / 查询变慢** | `work_mem` 不足 → 排序/哈希**溢出到临时文件** | Oracle PGA 不足 → 临时表空间暴涨 |
| **锁表 / 锁等待** | 长事务持锁、DDL 的 `ACCESS EXCLUSIVE` 被长查询挡住 | 与 Oracle 锁等待同类 |
| **表/索引持续膨胀** | 长事务/长快照挡住 VACUUM（§5.6） | **≈ Oracle ORA-01555**（都是“老版本活太久”） |
| **XID 回卷告警/停机** | autovacuum 没跑、复制槽卡住 → 老数据没 freeze | Oracle 无等价问题（SCN 是 48 位且推进机制不同） |
| **WAL 磁盘被打满** | 复制槽/归档落后，WAL 不能回收 | Oracle 归档目录被写满 |
| **`max_locks_per_transaction` 用尽** | 分区表多/锁对象多 | Oracle 队列资源不足 |

> 一句话：**Oracle 的头号问题是“硬解析”，PG 的头号问题是“连接与膨胀”。** 所以我给你两类建议：写 SQL 的规范（8.3）+ 连接/膨胀治理（8.5）。

### 8.3 日常写 SQL 应该怎么写（✅ 正确姿势）

| 做法 | 说明 |
|------|------|
| ✅ **用连接池（PgBouncer）** | PG 进程模型下**第一优先**。transaction 池化模式最省连接；注意 transaction 模式与 session 级特性（prepared statement、`SET`、临时表、advisory lock）的兼容性 |
| ✅ **用参数化 / prepared statement** | `PREPARE` + `EXECUTE`，或驱动侧 `?`/`$1` 占位；减少解析/规划开销，也防 SQL 注入 |
| ✅ **理解 `plan_cache_mode`** | 默认 `auto`：prepared statement **执行 5 次后会从 custom plan 切成 generic plan**，有时会选到坏计划；必要时设 `force_custom_plan`，或用 `SET plan_cache_mode` 对症 |
| ✅ **建对索引** | 主键/唯一、`WHERE`/`JOIN`/`ORDER BY` 列；善用**部分索引**、**表达式索引**、**覆盖索引（INCLUDE）**、**index-only scan（靠 VM）** |
| ✅ **避免对列包函数/隐式类型转换** | `WHERE date_trunc('day', ts) = ...` 或 `WHERE num_col = '123'`（类型不匹配）都可能**失效索引**；改成范围条件或显式类型 |
| ✅ **只取需要的列** | 别 `SELECT *`：减少解析/网络/内存，利于 index-only scan 与 TOAST 处理 |
| ✅ **批量写** | 用 **`COPY`**（最快）、`INSERT ... VALUES (...),(...)`、`execute_values`/`executeBatch`；避免逐行往返 |
| ✅ **`EXPLAIN (ANALYZE, BUFFERS)` 看真计划** | 关注 `Seq Scan`、`Nested Loop` 大表、`Rows Removed by Filter`、`Buffers` 命中、临时文件、以及 **plan 的估计行数 vs 实际行数**（差异大 → 统计信息过期，跑 `ANALYZE`） |
| ✅ **大表 UPDATE/DELETE 分批** | 一批一提交，避免长事务挡 VACUUM、避免一次生成海量死元组 |
| ✅ **合理 `fillfactor`** | 经常被更新的表把 `fillfactor` 调低（如 80~90），给 HOT 更新留页内空间，减少索引膨胀 |
| ✅ **开 `pg_stat_statements`** | 找出最耗时的 SQL（PG 版“Top SQL”），按 `total_exec_time` 排序治理 |

### 8.4 不应该写成什么样子（❌ 禁忌）

- ❌ **大量短连接直连数据库**（不走连接池）：进程/内存/上下文切换爆炸。
- ❌ **`SELECT *` + 无 `LIMIT` 的大表全扫**：Shared Buffers 被冲刷，影响他人。
- ❌ **`WHERE` 列上套函数 / 隐式类型转换**：索引失效，全表扫描。
- ❌ **超长事务 / `idle in transaction`**：挡住 VACUUM → 膨胀，甚至逼近 XID 回卷（§5.6）。
- ❌ **长期开着 `VACUUM` 不跑的配置** / 关掉 autovacuum：迟早回卷告警。
- ❌ **把 `work_mem` 设得极大**：它是“每排序/每哈希节点 × 每连接”，高峰会成倍放大，可能 OOM。
- ❌ **不监控复制槽**：逻辑/物理槽落后会把 WAL 撑满磁盘（经典事故）。

### 8.5 已经“爆”了怎么应急排查

```sql
-- 1) 连接数是否打满（对比 max_connections）
SELECT count(*) AS total,
       count(*) FILTER (WHERE state='active')      AS active,
       count(*) FILTER (WHERE state LIKE 'idle in%') AS idle_in_txn
  FROM pg_stat_activity;
SHOW max_connections;

-- 2) 谁在占时间（需 pg_stat_statements）
SELECT queryid, calls, total_exec_time, mean_exec_time, rows, query
  FROM pg_stat_statements
 ORDER BY total_exec_time DESC
 LIMIT 20;

-- 3) 锁等待与阻塞源头
SELECT blocked.pid AS blocked_pid, blocked.query AS blocked_query,
       blocking.pid AS blocking_pid, blocking.query AS blocking_query
  FROM pg_stat_activity blocked
  JOIN LATERAL (
    SELECT pid, query FROM pg_stat_activity
     WHERE pg_backend_pid() <> pid
       AND cardinality(pg_blocking_pids(blocked.pid)) > 0
  ) blocking ON true;

-- 4) 膨胀最严重的表（死元组占比）
SELECT relname, n_dead_tup,
       ROUND(n_dead_tup::numeric / NULLIF(n_live_tup+n_dead_tup,0)*100,1) AS dead_pct
  FROM pg_stat_user_tables ORDER BY n_dead_tup DESC LIMIT 20;

-- 5) 临时文件使用（work_mem 不足的信号）
SELECT datname, temp_files, pg_size_pretty(temp_bytes) FROM pg_stat_database ORDER BY temp_bytes DESC;

-- 6) 应急：强制断开某会话 / 终止某查询
SELECT pg_cancel_backend(pid);        -- 只取消当前查询（温和）
SELECT pg_terminate_backend(pid);     -- 断开整个连接（强硬）
```

**根本解决永远是：上连接池、批量写、建对索引、治长事务、让 autovacuum 跑起来。** ——这正对应 Oracle 那边“上绑定变量、消灭硬解析”。

---

## 9. 整体串讲：把上面所有东西连成一张全景图

把全文浓缩成“一个请求的一生”，你就拥有了对 PostgreSQL 的整体心智模型：

```
1. 客户端经连接池连上来 → postmaster fork 一个 backend（一连接一进程），
   私有内存里放 work_mem / plan cache / catalog cache。
2. backend 解析、规划 SQL → 生成/复用（每连接私有的）执行计划。
3. 执行时从 Shared Buffers 找 8KB 页（没有就从数据文件读入，PG18 可异步 I/O）。
4. 用 MVCC 快照（xmin/xmax/xip[]）+ 元组版本做一致性读，
   保证“读不阻塞写、写不阻塞读”，绝无脏读。
5. 修改在内存里进行：① 旧版本元组标 t_xmax ② 插入新版本元组
   ③ 生成 WAL 记录（必要时含整页 FPI）写入 WAL Buffers ④ 加行锁。
6. 提交时把“提交记录”写 WAL 并 XLogFlush 强制刷盘（synchronous_commit 控制强度）。
7. bgwriter 之后异步把脏页写回数据文件；checkpointer 定期做检查点推进 redo LSN；
   walwriter 兜底刷 WAL；archiver 归档；autovacuum 回收死元组、freeze 防回卷。
8. 主库 WAL 经 walsender 发给备库（物理流复制做 HA/容灾/只读扩展）
   或经逻辑解码发成行变更（逻辑复制做迁移/异构）。
9. 全程 postmaster 监督：任何子进程崩溃 → 重启 → startup process 从检查点重放 WAL 恢复。
```

**一句话设计哲学**：PostgreSQL 用“**进程隔离（一连接一进程）+ 内存缓存共享数据页 + WAL 顺序写保持久 + 元组多版本 + 快照可见性**”这套组合，把“随机写盘”和“并发一致性”解耦，并用**后台进程异步收尾 + VACUUM 回收旧版本**来维持长期健康，从而同时拿到高并发、高可用与可恢复性。

作为初学者，你只要记住六句话就抓住了主干：

1. **集群 = 一个 PGDATA + 一个 postmaster；库里多 Schema，Schema 与用户解耦。**
2. **一连接一进程：backend 干活，postmaster 监工；shared_buffers 是公共黑板，work_mem 是私有草稿纸。**
3. **PG 没有 undo——旧版本留在表里；一致性读靠“快照 + 元组 xmin/xmax”，代价是必须 VACUUM（否则膨胀/回卷）。**
4. **WAL 是唯一的日志：先写 WAL 再写数据页；提交必须 flush WAL；LSN 是它唯一坐标（兼当 Oracle 的 RBA+SCN）。**
5. **高可用靠物理流复制（hot standby + 外部 HA 工具），迁移/异构靠逻辑复制；没有 RAC/True Cache 的等价物。**
6. **PG 没有“共享池”，真正的头号杀手是“连接数 + 表膨胀”——用连接池、批量写、短事务、盯 autovacuum。**

---

## 10. 数据存储层次：集群 / 表空间 / 数据库 / 关系 / 段文件 / 页 / 元组

Oracle 是 **表空间 → 段 → 区 → 块**四层。PostgreSQL 的层次**不一样**，最需要先纠正的一点是：**PG 没有“区（Extent）”这个概念**——关系（表/索引）的增长不走“区分配”，而是**按 1GB 切成段文件（segment）**，所以“表大了”在 PG 里表现为“多出几个 1GB 的 `.1`、`.2` 文件”，而不是“多分配几个区”。

### 10.1 先给一张“从大到小”的全景图

```
Cluster 集群（一个 PGDATA 目录，见 §2.1）
 └─ Tablespace 表空间        ← 集群级；一个物理目录（默认 pg_default / pg_global）
      └─ Database 数据库     ← 库目录 base/<dboid>
           └─ Schema 模式    ← 库内命名空间（public / sales ...）
                └─ Relation 关系（表 / 索引）  ← 一个对象 = 一个 relfilenode = 一组文件
                     └─ Fork 分叉：main / fsm / vm / init  ← 同一对象的不同用途文件
                          └─ Segment 段文件     ← main 每满 1GB 切一个（.1 / .2 ...）
                               └─ Page 页       ← 8KB，I/O 最小单位（内部结构见第 11 节）
                                    └─ Tuple 元组（行版本）  ← 坐在页里（含行指针 ItemId）
                                         └─ Column 列（大值可能 TOAST 到外部表）
```

一句话记忆：**“集群里有表空间，表空间下有库，库里有 schema，schema 里有表，表按 1GB 切成段文件，段文件里排满 8KB 页，页里坐着元组。”**

### 10.2 第 1 层：集群与表空间（Cluster / Tablespace）—— 物理目录层

- **集群（Cluster）**：一个 `PGDATA` 目录 = 一个 postmaster 管理的全部数据（§2.1）。`initdb` 时确定数据目录与 **WAL 段大小**。
- **表空间（Tablespace）**：**一个集群级的命名目录**，用来把数据分布到不同磁盘，或制定备份策略。
  - 默认两个：**`pg_default`**（→ `PGDATA/base/`）与 **`pg_global`**（→ `PGDATA/global/`，放共享系统表）。
  - 自建：`CREATE TABLESPACE fastspace LOCATION '/ssd/pgdata';` 之后 `CREATE TABLE ... TABLESPACE fastspace;` 或 `SET default_tablespace`。
  - **表空间是“逻辑名 → 物理目录”的映射，本身不直接装对象**；对象按 `relfilenode` 落在其表空间目录下的**库子目录**里。
- **与 Oracle 对照**：Oracle 表空间由**数据文件**承载；PG 表空间就是**一个 OS 目录**，里面的文件由 PG 自己按 OID 命名与切分。**PG 没有数据文件（.dbf）这一层概念。**

### 10.3 第 2 层：数据库与 Schema —— 命名空间层

- **数据库（Database）**：`base/<dboid>/` 目录，**独立一套系统目录（`pg_catalog`）**，跨库默认不可查（需 fdw/dblink）。
- **Schema**：库内命名空间，决定对象名解析（`search_path`）与权限分组；**与用户/角色解耦**（§2.2、§2.3）。
- **物理落点**：`base/<dboid>/<relfilenode>`；若对象指定了表空间，则落在 `pg_tblspc/<ts_oid>/<version>/<dboid>/<relfilenode>`。

> 与 Oracle 对照：Oracle 的“库”内部层是“表空间装段”；PG 的“库”是“集群里的一个目录”，表空间是**跨库共享的目录映射**——两者层次观完全不同，别硬套。

### 10.4 第 3 层：关系（Relation）—— “一个对象一组文件”（对应 Oracle 段）

- **关系（Relation）** = PG 里对“表 / 索引 / 序列 / 物化视图 / TOAST 表”等的统称，每个关系在 `pg_class` 里有一行，对应一个 **`relfilenode`**（物理文件基名）。
- **一张普通表 = 一个关系 = 一组文件**（见 §10.5 的 fork）；**每个索引是独立的关系**（独立文件）；**分区表的每个分区是独立的关系**（本质上是一堆子表）；**TOAST 表也是独立关系**。
- 关系大小记录在 `pg_class.relpages / reltuples`；`pg_total_relation_size()` 给出含索引/TOAST 的总大小。
- **与 Oracle 对照**：PG 的关系 ≈ Oracle 的**段（Segment）**（“一个对象一个段”）；但 PG **不引入“区”**——关系的增长在文件层直接以 1GB 分片。

> 澄清“1 张表 ≠ 1 个文件”：一张带索引、TOAST、分区的表，会对应**很多个关系、很多组文件**——和 Oracle“一张分区表带索引和 LOB 会产生十几个段”是同一个道理。

### 10.5 第 4 层：段文件（Segment）与 Fork —— 1GB 分片 + 辅助文件

PG 的关系文件有两个正交维度：**Fork（用途）** 与 **Segment（大小分片）**。

**（1）Fork：同一对象的几种用途文件**

| Fork | 文件名后缀 | 放什么 | 作用 |
|------|-----------|--------|------|
| **main** | （无） | 真正的数据页 | 表/索引的数据主体 |
| **fsm** | `_fsm` | **Free Space Map** 的页 | 记录“哪些页还有空间”，插入时快速找位 |
| **vm** | `_vm` | **Visibility Map** 的位 | 标记“哪些页全可见/全冻结”，供 index-only scan 与 VACUUM |
| **init** | `_init` | 初始页 | 用于 **unlogged 表**在崩溃后重置 |

**（2）Segment：main 文件按 1GB 切分**

- 单个 OS 文件上限由 PG 限制为 **1GB**（`RELSEG_SIZE`，编译期常量），所以一个关系超过 1GB 时，切成 `<relfilenode>`、`<relfilenode>.1`、`<relfilenode>.2`…
- **这就是“PG 没有区”的直接体现**：增长是**文件级分片**，不是“区分配”；页号在**整个关系**上连续编号（跨段文件也连续），`ctid` 里用的就是全局页号。

**（3）与 Oracle 对照**

| | Oracle | PostgreSQL |
|---|---|---|
| 对象级空间单位 | 段（Segment） | 关系（Relation，一组文件） |
| 分配单位 | **区（Extent，连续块组）** | **无区概念**；按 1GB 段文件切分 |
| I/O 单位 | 块（Block，8KB） | 页（Page，8KB） |
| 辅助结构 | 段头块 / 位图块 / LMT | **fsm / vm fork 文件** |

### 10.6 第 5 层：页（Page）—— 8KB，I/O 最小单位（对应 Oracle 块）

- PG 读写以**页（Page，也叫 Block）**为单位，默认 **8KB**（`BLCKSZ`，**编译期固定**，不像 Oracle 可选 2K/4K/16K/32K；要改必须重编译）。
- **页内部结构在第 11 节专门拆解**（PageHeader 24 字节 + 行指针 + 元组 + 空闲空间 + special 区）。
- **一行（元组）不能跨页**；超长的值由 **TOAST** 拆到外部表（见 §10.7）。
- **页大小 vs 元组大小**：8KB 是“盒子”，元组是“内容”；装不下就走 TOAST/压缩，绝不跨页。

### 10.7 第 6 层：元组（Tuple）与 TOAST

- **元组（Tuple）= 一个行版本**（注意：MVCC 下一行的不同版本是**不同的元组**，见 §5.5）。它坐在页里，由**行指针（ItemId）**定位，`ctid = (页号, 行指针下标)` 就是行的物理地址。
- **TOAST（The Oversized-Attribute Storage Technique）**：当一条元组超过约 **2KB**（`TOAST_TUPLE_THRESHOLD`）时，PG 会：
  1. 尝试**压缩** `varlena` 大字段（如 text/bytea，可 `ALTER TABLE ... ALTER COLUMN ... SET STORAGE`）；
  2. 若仍太大，把大值**切片存到独立的 TOAST 表**里，主元组只留指针。
  - 每个有可变长字段的表**都自带一张 TOAST 表**（如 `pg_toast.pg_toast_<oid>`）与一个 TOAST 索引，**它们是独立的关系/文件**。
- **行迁移 / 行链接在 PG 里对应什么**：PG 没有 Oracle 那种“行迁移”，但**元组不能跨页**；更新变大塞不下同页时，**新版本写到新页**（旧的留给 MVCC），由 HOT 机制或索引指向新版本——本质仍是“多版本 + 指针”，不是 Oracle 的“整行搬家”。

### 10.8 层间关系 + 逻辑 ↔ 物理对照

**（1）五条必须记住的关系**

- **对象 → 关系 → 一组 fork 文件 → 若干 1GB 段 → 页 → 元组**：这是“一次落盘”的完整路径。
- **表空间是目录映射，不是对象容器**：对象靠 `relfilenode` + 表空间目录定位；**表空间在集群级共享**。
- **PG 无区（Extent）**：关系增长 = 段文件数量增加；页号在关系内连续，跨段文件无缝。
- **页是 I/O 单位，元组不能跨页**：超长值走 TOAST。
- **同一行的多个版本是多个元组**：MVCC 的版本就是“物理元组”（§5.5），`ctid` 指向具体版本。

**（2）逻辑 ↔ 物理对照表**

| 逻辑结构（你用 SQL/视图管理） | 物理结构（真正落在磁盘） |
|---|---|
| 集群 Cluster | `PGDATA` 目录 |
| 表空间 Tablespace | 一个 OS 目录（`pg_default` / `pg_global` / 自建） |
| 数据库 Database | `base/<dboid>/` 目录 |
| Schema | （不单独对应文件，是库内命名空间） |
| 关系 Relation（表/索引） | `relfilenode` 命名的一组文件 |
| fork（main/fsm/vm） | 带 `_fsm`/`_vm` 后缀的文件 |
| 段 Segment | `main` 的 1GB 分片（`.1`/`.2`…） |
| 页 Page（8KB） | OS 块/文件页的上层封装 |
| 元组 Tuple | 页内偏移（由 ItemId 指向） |

### 10.9 速查总结表 + 常用排查 SQL

| 层级 | 一句话定义 | 管理/分配单位 | 物理对应 | Oracle 对照 |
|---|---|---|---|---|
| **集群 Cluster** | 一个 PGDATA + 一个 postmaster | `initdb` 创建 | 目录 + `pg_control` | 实例+库（单机） |
| **表空间 Tablespace** | 集群级命名目录 | DBA 创建/挂盘 | OS 目录 | 表空间（由数据文件承载） |
| **数据库 Database** | 库内独立系统目录 | `CREATE DATABASE` | `base/<dboid>` | 更接近整个库 |
| **Schema** | 库内命名空间 | `CREATE SCHEMA` | （无独立文件） | Schema（但≠用户） |
| **关系 Relation** | 一个对象一组文件 | 随对象建/删 | `relfilenode` 文件组 | 段 Segment |
| **段文件 Segment** | main 的 1GB 分片 | 自动增长 | `.1`/`.2`… | （无区概念） |
| **页 Page** | I/O 最小单位（8KB） | I/O 单位 | 文件内 8KB 块 | 块 Block |
| **元组 Tuple** | 一个行版本 | MVCC 版本 | 页内 ItemId 指向 | 行 Row |

```sql
-- 1) 库 / 表空间 / 表的大小
SELECT datname, pg_size_pretty(pg_database_size(datname)) FROM pg_database ORDER BY pg_database_size(datname) DESC;
SELECT spcname, pg_size_pretty(pg_tablespace_size(spcname)) FROM pg_tablespace;

-- 2) 某个表及其附属文件（关系 + 索引 + TOAST）各占多少
SELECT pg_size_pretty(pg_relation_size('accounts'))              AS main_only,
       pg_size_pretty(pg_total_relation_size('accounts'))        AS with_index_and_toast,
       pg_size_pretty(pg_indexes_size('accounts'))               AS indexes;

-- 3) 关系的物理文件与 OID（relfilenode）
SELECT relname, relfilenode, relpages, reltuples,
       pg_relation_filepath('accounts') AS file_path
  FROM pg_class WHERE relname = 'accounts';

-- 4) 表空间/数据库目录下有哪些文件（在 OS 上查看）
--    ls -l $PGDATA/base/<dboid>/        -- 默认表空间
--    ls -l $PGDATA/pg_tblspc/<ts_oid>/<version>/<dboid>/

-- 5) TOAST 表与它的大小
SELECT relname, pg_size_pretty(pg_relation_size(reltoastrelid))
  FROM pg_class WHERE relname = 'accounts';
```

> **一句话记忆**：“集群 → 表空间 → 库 → schema → 关系（表/索引）→ fork 文件 → 1GB 段 → 8KB 页 → 元组”。**PG 把 Oracle 的“区”换成了“1GB 段文件分片”，把“数据文件”换成了“表空间目录里的 OID 文件”，其余层级观可类比。**

---

## 11. 深入：PostgreSQL 8KB 页（Page）内部结构

> 本节是进阶内容。前面提到“Shared Buffers 缓存的是 8KB 页”，这一节把“一个 8KB 页内部到底长什么样、页头记录了哪些信息”彻底拆开。理解了页结构，第 5 节“一致性读靠快照 + 元组 xmin/xmax”和第 6 节“WAL 记页改动”就不再抽象。

### 11.1 先澄清：8KB 是“页大小”，不是“头部大小”

- PG 以**页（Page/Block）**为最小 I/O 单位，默认 **8192 字节**（`BLCKSZ`，编译期固定）。
- 一个 8KB 页 = **24 字节页头 + 行指针数组 + 空闲空间 + 元组数据 + special 区**。
- 所以“页头”指的是**页开头那 24 字节（`PageHeaderData`）**，主体是元组数据；页头存“元数据”，元组区存“真实行版本”。

### 11.2 一个 8KB 页从外到内的分层

```
┌──────────────────────────────────────────────────────────────────┐
│  PageHeader（页头，24 字节）                                        │
│   · pd_lsn（最近修改它的 WAL LSN）  · pd_checksum（校验和）         │
│   · pd_flags（标志）                · pd_lower（行指针数组末尾）     │
│   · pd_upper（元组区起点）          · pd_special（special 区起点）  │
│   · pd_pagesize_version            · pd_prune_xid（可清理的最老 xid）│
├──────────────────────────────────────────────────────────────────┤
│  ItemId / 行指针数组（Line Pointers）  ↓ 从页头向下生长              │
│   [0] 偏移+长度+标志   [1] ...   [2] ...   （每条 4 字节）           │
├──────────────────────────────┬───────────────────────────────────┤
│   Free Space（空闲空间）      │   Tuple Data（元组数据）↑ 从页尾向上生长│
│   （pd_lower 与 pd_upper 之间）                                    │
├──────────────────────────────────────────────────────────────────┤
│  Special Space（特殊区，页尾，pd_special 起）                       │
│   堆表：通常为空；B-tree 索引：存页的兄弟指针/层级等元数据           │
└──────────────────────────────────────────────────────────────────┘
```

关键点：**行指针数组从页头往下长，元组数据从页尾往上长，中间夹着空闲空间**；`pd_lower` 与 `pd_upper` 就是“已用空间的两端”。这与 Oracle 块“行目录从块顶向下、行数据从块底向上”**结构几乎一致**。

### 11.3 `PageHeaderData` 字段清单（24 字节）

| 字段 | 大小 | 含义 | 用途 |
|------|------|------|------|
| **pd_lsn** | 8B | **最近一次修改本页的 WAL 记录的 LSN** | WAL 重放时判断“这页的改动是否已应用”；**幂等重放的关键** |
| **pd_checksum** | 2B | 页校验和（`data_checksums=on` 时启用） | 检测坏页/静默损坏 |
| **pd_flags** | 2B | 标志位（如 `PD_HAS_FREE_LINES`、`PD_PAGE_FULL`） | 页状态 |
| **pd_lower** | 2B | 行指针数组的**末尾偏移**（空闲区起点） | 定位行指针区边界 |
| **pd_upper** | 2B | 元组区的**起点偏移**（空闲区终点） | 定位元组区边界 |
| **pd_special** | 2B | special 区起点 | 索引等特殊数据的边界/长度 |
| **pd_pagesize_version** | 2B | 页大小 + 布局版本号 | 兼容性校验 |
| **pd_prune_xid** | 4B | 本页可能**最早可被清理**的 xid（0 = 无） | VACUUM 的快速线索 |

> 重点：**`pd_lsn` 是 WAL 重放的“锚点”**。重放一条 WAL 记录时，先看目标页的 `pd_lsn` 是否已经 ≥ 记录 LSN——已应用就跳过。这就是**基于 LSN 的幂等重放**，等价于 Oracle 用块 SCN 判断“块是否已应用过该改动”。

### 11.4 行指针（ItemId）与页内寻址 —— `ctid` 的由来

- **ItemId（4 字节）** 是页内的“行目录项”，结构大致为：**偏移（15 bit）+ 标志（2 bit）+ 长度（15 bit）**。
- **`ctid`（也称 ItemPointer）= (页号 block#, 行指针下标 offset)**——即“**表里第几页、页里第几个行指针**”。它是 PG 里行的**物理地址**（类比 Oracle ROWID，但 PG 的 `ctid` **会因 `VACUUM FULL`/更新而变**，不可长期当主键）。
- **行指针的价值**：给元组一个**稳定间接层**——更新时可在原地复用同一行指针指向新版本（配合 HOT），页内整理（compaction）时也不破坏外部引用。

### 11.5 元组头（HeapTupleHeader）在页里的位置

- 每个元组的头部字段（`t_xmin/t_xmax/t_cid/t_ctid/t_infomask...`）**在第 5.5 节已详拆**，这里只强调**页级视角**：
  - `t_ctid` 是**版本链指针**（指向自己或更新后的新版本）；
  - `t_infomask` 里的 Hint Bits 就是**“页级脏”的来源**（设置它会改页、写 WAL）；
  - `t_hoff` 决定列数据的偏移，配合页内 `ItemId` 的偏移即可精确定位每一列。
- 元组在页内**不可跨页**；超长值走 TOAST（§10.7）。

### 11.6 空闲空间：Free Space、FSM 与 VM

- **页内 Free Space**：`pd_lower` 与 `pd_upper` 之间，是插入/更新新元组的空间来源；**没有 Oracle 的 `PCTFREE/PCTUSED`**，PG 用的是**关系级 `fillfactor`**（表默认 100，索引默认 90）——预留一部分页空间给后续更新，减少页分裂与新页分配。
- **FSM（Free Space Map，`_fsm` 文件）**：**“哪些页还有多少空位”的地图**，插入时据此快速找页，避免扫描所有页。
- **VM（Visibility Map，`_vm` 文件）**：**“哪些页的所有元组都可见”的位图**，支撑 **index-only scan（不回表）** 与 **VACUUM 跳过已全冻结的页**。

### 11.7 Special Space（特殊区）

- **堆表页**：special 区一般为空。
- **B-tree 索引页**：special 区存**索引页的元数据**（如 `BTPageOpaqueData`：左右兄弟页指针、页层级、是否叶子），支撑索引扫描与页分裂。
- 不同索引类型（GiST/GIN/BRIN/Hash）有各自的 special 布局——**special 区就是“索引各自的小金库”**。

### 11.8 块尾校验：`pd_checksum` 与“半写页”

- **页头有 `pd_checksum`**（需 `data_checksums=on` 启用）：读取页时校验，能发现**静默损坏**。
- **半写页（torn page）的保护**则靠 WAL 的 **Full-Page Image**（§6.3.3）：检查点后每页第一次修改，WAL 里记整页快照，恢复时整页覆盖。
- **与 Oracle 对照**：Oracle 用块尾 **Tailchk** 检测“写断裂”；PG 用 **页校验和 + WAL 整页镜像**。两者都回答了同一个问题：“**万一写了一半，怎么发现/怎么修**”。

### 11.9 怎么实地看一个真实页（实操）

```sql
-- 1) 打开 pageinspect 扩展
CREATE EXTENSION IF NOT EXISTS pageinspect;

-- 2) 页头（看 pd_lsn / pd_lower / pd_upper / pd_special 等）
SELECT * FROM page_header(get_raw_page('accounts', 0));

-- 3) 页里每个行指针与元组的版本信息（行指针下标 lp、元组头字段、ctid）
SELECT lp, lp_off, lp_flags, lp_len,
       t_xmin, t_xmax, t_ctid, t_infomask, t_data IS NOT NULL AS has_data
  FROM heap_page_items(get_raw_page('accounts', 0));

-- 4) 从 ctid 反查行在哪个页、第几个槽
SELECT ctid, id, balance FROM accounts WHERE id = 1;
--   ctid 形如 (0,3) → 第 0 页、行指针下标 3

-- 5) 看某行是否发生了 HOT 更新（页内版本链）
SELECT lp, t_xmin, t_xmax, t_ctid FROM heap_page_items(get_raw_page('accounts', 0))
 WHERE t_ctid <> '(0,0)' ORDER BY lp;

-- 6) 页校验和与坏页检测（需 data_checksums=on）
CHECKPOINT;                                    -- 确保页已落盘
SELECT * FROM page_header(get_raw_page('accounts', 0));
```

> 提示：内存里还有一份 **Buffer Descriptor（缓冲区描述符）**，记录“这个页在 Shared Buffers 里的状态（是否被 pin、是否脏、引用计数）”，**和磁盘上的 PageHeader 不是一回事**——可用 `pg_buffercache` 扩展观察，别混淆。

### 11.10 小结与前后呼应

- 一个 8KB 页 = **页头（`pd_lsn`/校验和/`pd_lower`/`pd_upper`/`pd_special`）+ 行指针数组 + 空闲空间 + 元组数据 + special 区**。
- **`pd_lsn` 是 WAL 重放的锚点（幂等重放），行指针（ItemId）是 `ctid` 的载体，元组头（xmin/xmax）是可见性判断的依据，`_fsm`/`_vm` 是页级空间的旁路地图**——四者共同支撑第 5 节的“读不阻塞写、写不阻塞读”。
- 想看真实结构：`pageinspect` + `heap_page_items(get_raw_page(...))` + `page_header(...)`。

---

## 12. 参考资料

1. 工作区姊妹篇：[MVCC原理.md](MVCC原理.md) —— 多库 MVCC 逐机制对比（MySQL 行版本链 vs Oracle SCN+UNDO；可与本文 §5 的 PG“元组版本 + 快照”一起看，形成三库对照）。
2. 工作区姊妹篇：[DB事务隔离级别.md](DB事务隔离级别.md) —— 四库隔离级别实现对照（含 PG 的 RC/RR/SERIALIZABLE 与 SSI 冲突回滚）。
3. 工作区姊妹篇：[Oracle原理分析.md](Oracle原理分析.md) —— 本文的对照母本（实例/内存/进程、SCN+UNDO 一致性读、REDO 记录与 RBA、Data Guard/True Cache、表空间-段-区-块、8KB 块结构）。
4. PostgreSQL 官方文档（v18）—— *Chapter 13 Concurrency Control*（MVCC、隔离级别、SSI）、*Chapter 28 Reliability and the Write-Ahead Log*（WAL、检查点、PITR）、*Chapter 30 Logical Replication*、*Chapter 27 High Availability / Warm Standby / Streaming Replication*、*Chapter 66 Database Physical Storage*（Page Layout、TOAST、FSM/VM）。
5. PostgreSQL 官方 Release Notes —— 18（2025-09：AIO、`uuidv7`、虚拟生成列、skip scan）、17（WAL Summarizer、增量备份、逻辑复制槽故障转移）、19（2026-10 RC1，GA 计划 2026-11-19）。
6. 《The Internals of PostgreSQL》（Hironobu Suzuki，interdb.jp）—— 进程与内存结构、页/元组内部、WAL、VACUUM 的经典图解。
7. 扩展与观测：`pg_stat_statements`（Top SQL）、`pg_buffercache`（缓冲区）、`pageinspect`（页/元组 hex 级检查）、`pgstattuple`（膨胀度量）。

