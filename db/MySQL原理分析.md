# MySQL 原理分析（面向小白的整体梳理）

> 整理日期：2026-10-17
>
> 适用版本：以 **MySQL 8.4 LTS / 9.7 LTS** 为准（核心原理对 5.7/8.0/8.4/9.x 通用；版本相关差异单独标注）。
>
> 阅读对象：MySQL 初学者。本文是姊妹篇 [Oracle原理分析.md](Oracle原理分析.md) 的 **MySQL 镜像版**，沿用同一套章节骨架（概念与部署形态 → 内存 → 后台线程 → MVCC 一致性读 → 一条 DML 旅程 → 复制与高可用 → “池”的问题与 SQL 规范 → 存储层次 → 数据页内部结构）。读过 Oracle 版的读者可以按“同名小节对照”读；没读过的读者按适合你自己的阅读顺序读。
>
> 文档版本：v0.1
>
> 深入阅读入口：本文第 5 节 MVCC 的底层机制，在姊妹篇 [MVCC原理.md](MVCC原理.md)（MySQL vs Oracle MVCC 逐机制对比）和 [DB事务隔离级别.md](DB事务隔离级别.md)（四库隔离级别实现对照）里有更系统的展开，建议读完本文再深入。

---

## 目录

> 📌 **建议阅读顺序（小白路线）**：`第 2 节（概念）` → `第 6 节（DML 旅程）` → `第 5 节（MVCC / 一致性读）` → `第 3、4 节（内存 / 线程）` → 其余按需查阅。
> 本文镜像 Oracle 版结构，所有标 **「深入：」** 的小节是对应主题的专项拆解，可单独查阅，不影响主线阅读。

1. [版本说明：从 8.0 到 9.7](#1-版本说明从-80-到-97)
2. [核心概念与部署形态：数据库 / 实例 / Schema](#2-核心概念与部署形态数据库--实例--schema)
   - 2.1–2.3 三个核心概念（Server / 实例 / database=schema）
   - 2.4 三者关系图
   - 2.5 部署形态①：单机多 db（schema 当租户）vs 独立实例
   - 2.6 部署形态②：复制的多机形态 vs 单实例（与 Oracle RAC 的本质不同）
3. [MySQL 的内存结构：Buffer Pool 与各类缓冲区](#3-mysql-的内存结构buffer-pool-与各类缓冲区里到底放了什么)
4. [MySQL 的后台线程与请求处理组件](#4-mysql-的后台线程与请求处理组件每个线程组件是干什么的)
5. [读不阻塞写、写不阻塞读：MySQL 的一致性读与多版本（含 §5.5 UNDO 版本链专项）](#5-读不阻塞写写不阻塞读mysql-的一致性读与多版本)
6. [一条 DML 语句的完整旅程（含 §6.3 REDO 记录、§6.4 Redo/Binlog 物理结构与 LSN 坐标）](#6-一条-dml-语句的完整旅程各线程如何配合)
7. [复制、半同步、Group Replication 与 InnoDB Cluster：它们底层用了什么、区别在哪](#7-复制半同步group-replication-与-innodb-cluster它们底层用了什么区别在哪)
8. [Buffer Pool 为什么“不给力” + 日常写 SQL 的正确姿势](#8-buffer-pool-为什么不给力--日常写-sql-的正确姿势)
9. [整体串讲：全景图](#9-整体串讲把上面所有东西连成一张全景图)
10. [数据存储层次：表空间 / 段 / 区 / 页（逻辑存储结构）](#10-数据存储层次表空间--段--区--页逻辑存储结构)
11. [深入：MySQL 16KB 数据页内部结构](#11-深入mysql-16kb-数据页innodb-index-page内部结构)
12. [参考资料](#12-参考资料)

---

## 1. 版本说明：从 8.0 到 9.7

关于版本，先给一条准确的时间线（本文整理于 2026-10，已按最新事实校正）：

- **MySQL 8.0（LTS）**：2018-04 GA，长期主力版本；官方已于 **2026-04 结束支持（EOL）**。
- **MySQL 8.4 LTS**：**2024-04-30 发布**，8.x 系列的长期支持版本（Premier 支持到 2029-04，Extended 到 2032-04）。8.4 不是“大改版”，而是“去掉遗留负担”：默认值更安全（如 `innodb_change_buffering` 默认改为 `none`、移除旧版 `mysql_native_password` 默认启用等），主干机制与 8.0 一致。
- **Innovation（创新版）序列 9.0~9.6**：按季度快速演进（9.0 2024-07 → 9.6 2026-01），创新版支持到下一个 minor 发布为止，适合尝鲜与跟随上游。
- **MySQL 9.7 LTS**：**2026-04-21 发布**，9.x 系列的长期支持版本（最新 patch 已到 9.7.2）。Oracle 对 MySQL 采用 **“Innovation（季更）+ LTS（约每 2 年一个）”双轨模型**，LTS 版有 5 年 premier + 3 年 extended 支持。
- 因此“装哪个版本”的建议很简单：**新项目直接上 8.4 LTS 或 9.7 LTS**；8.0 已 EOL，不要再新建。

好消息：本文讲的全部核心原理（Buffer Pool/WAL、undo 版本链 + ReadView、redo/binlog、两阶段提交、复制与 Group Replication、B+ 树页结构）**从 5.7 到 9.7 几乎不变**，不必担心“版本对不上”。下文以 InnoDB 通用原理为主，版本专属行为（如 8.4 默认值变化、8.0.30 redo 文件形态变化、5.7 的 frm 文件）会单独标注。

**还有一条与 Oracle 不同的“版本观”要先立住**：MySQL 是 **Server 层（SQL、事务接口、binlog、复制）+ 存储引擎层（InnoDB 负责数据和 MVCC）** 的分层结构。所以“MySQL 的原理”其实是两条：Server 层是一套，InnoDB 是一套；理解了“哪些事归谁管”，一半的困惑就消失了。

---

## 2. 核心概念与部署形态：数据库 / 实例 / Schema

本节先讲三个最容易被混的概念（Server / 实例 / database=schema 及其关系图），再展开两种**部署形态**——**单机多 db（§2.5）** 与 **复制/集群多机形态（§2.6）**。

### 2.1 数据库（Database）—— 落在磁盘上的 datadir + 一组文件

MySQL（到“实例”为止就一个进程）的数据都在**数据目录 `datadir`** 下，主要有这几类文件：

- **数据与索引文件（`.ibd`）**：真正存表数据的地方。InnoDB 的每个表（8.0 起默认独立表空间 `innodb_file_per_table=ON`）对应自己的 `.ibd` 文件。数据在“表空间→段→区→页”里的组织方式，见第 10 节。
- **重做日志（redo log）**：记录 InnoDB 所有数据页的物理变更，是崩溃恢复的命脉（8.0.30+ 位于 `#innodb_redo` 目录的一组文件；旧版本是 `ib_logfile0/1` 两个）。
- **二进制日志（binlog）**：Server 层的逻辑日志（不是 InnoDB 的），记录“改了什么”的语句或行事件，用于**复制与按时间点恢复**——这是 MySQL 独有的一层，Oracle 里没有对应物。
- **undo 表空间**：存放“修改前的前像”，支撑回滚与 MVCC（`innodb_undo_001/002`）。
- **系统表空间（`ibdata1`）**：存数据字典（8.0 起改为事务性字典 `mysql.ibd`）、Change Buffer、Doublewrite 等公共部分。

与 Oracle 对照：**Oracle 的“Database”= 一个庞大的物理库（数据文件+控制文件+redo）；MySQL 的“database”只是 datadir 里一个“逻辑命名空间（目录）”**，物理文件都平等地躺在同一个实例的磁盘里，没有 Oracle 那种“一个 database 是一套独立文件集合”的边界感。真正的边界是“**实例（mysqld 进程）**”。

### 2.2 实例（Instance）—— 运行中的“单进程多线程”

MySQL 的实例 = **一个 `mysqld` 进程**（内部是几十条线程）+ **它管理的内存（Buffer Pool 等）**。

- 一个实例管理着 datadir 下**所有 database 的所有表**——这一点与 Oracle 完全不同：Oracle 里“一个实例打开一个数据库”，而 MySQL 里没有那个“库”层，**实例即数据库，database 只是实例内部的命名空间**。
- 每个客户端连接对应一条**服务线程**（处理 SQL、持有各自的会话内存），连接断开线程退出（复用 `thread_cache_size` 池化线程）。
- 相似之处：实例是运行时，关机重启后 datadir 文件还在，内存和线程被重建——“书还在书架上，但读书的人（进程）重新上岗”。

> 一个形象比喻：**datadir 是“书架”（所有 database 的书都放一个书架上），mysqld 是“图书馆管理员”**——一个管理员看管整个书架，读者（连接）各拿各的草稿纸（sort/join buffer）在管理员领到的工作台上翻书（各用的 table cache/Buffer Pool）。

### 2.3 Schema（模式）—— 和 database 是一个词

MySQL 没有 Oracle 那种“schema=用户”的概念：

- **`CREATE DATABASE` 和 `CREATE SCHEMA` 是同义词**，两者的对象集合完全一样。
- 一个“database（=schema）”只是一组表/视图/存储过程/函数的命名空间，物理上对应 datadir 下的一个目录，目录里放该 db 的 `.ibd` 文件。
- 权限按“db 级/表级”授予（`GRANT ... ON mydb.* TO ...`），和“用户”没有归属绑定关系。

**三个概念的对照表：**

| 概念 | 本质 | 是否持久 | 计数关系 |
|------|------|---------|---------|
| **Server 实例** | 一个 mysqld 进程 + 它的内存和线程 | 否（进程级） | 一套 datadir 一个实例（MHA/主从架构里每台机器各一个实例） |
| **database / schema** | datadir 下的一个目录命名空间 | 是 | 一个实例下 0~N 个 |
| **表 / 视图** | database 里的对象 | 是 | 一个 database 多张表 |

> 与 Oracle 的对比（易踩坑）：**MySQL 里 `database ≈ schema ≈ 目录名`，一个实例装 N 个 database；Oracle 里 `schema = 用户`、`database = 整个物理库`、`instance` 单独一层**。Spring 等框架里配的 `jdbc url .../mydb` 是“连到实例里的一个 database”；而 Oracle 的 TNS 是“连到某个 PDB/服务”。

### 2.4 三者关系图

```
┌──────────────────────────────────────────────────────────────┐
│                     MySQL 实例 (mysqld 进程)                    │
│    内存: Buffer Pool / Redo Buffer / Binlog Cache / ...        │
│    线程: listener/connection handler/服务线程/后台线程            │
│                                                                │
│    datadir（磁盘上）下都是 mysqld 管的：                         │
│        ├── mydb（database=schema，一个业务租户）                 │
│        │     ├── orders.ibd   ← mydb.orders 表+索引            │
│        │     └── users.ibd    ← mydb.users                     │
│        └── report_db（另一个 database）                         │
│              └── ...                                           │
│    （ibdata1 / #innodb_redo / ibundo... 是全实例共享的公共部分）   │
└──────────────────────────────────────────────────────────────┘
```

### 2.5 部署形态①：单机多 db（“schema 当租户”）vs 一个业务一个实例

上一节图里就有“一个实例下 N 个 database”的样子。MySQL 大多数生产部署有两种主流姿势，各有明显的取舍：

#### 2.5.1 一句话定位 + 两个常见误解

- **MySQL 没有 Oracle CDB/PDB 那种“容器/插件”级别的纯粹多租户**，这**不是 Oracle 那边说的“法律/技术选件”**，而是“产品结构设计就是单机集中式、以一个物理库承载所有业务”。
- **误解 1**：“一个 database = 一个 MySQL 服务器”。其实一个 mysqld 管一张大表上所有 database 的表，`mydb` 和 `report_db` 是共享同一个 Buffer Pool、同一个 redo、同一批线程的**弱隔离邻居**。
- **误解 2**：“MySQL 不会因为一个 database 疯了拖垮别的 database”。会——**Buffer Pool 是全实例共享的，undo 是全实例共享的（直到 9.7 也一样），一个慢查询/全表扫描会把全实例的内存池冲垮**，没有 per-database 资源墙。

#### 2.5.2 两种姿势的差别（重点表）

| 维度 | 一实例多 database（共享实例） | 一业务一实例（垂直拆分） |
|---|---|---|
| **隔离** | 逻辑隔离，**共享内存/undo/redo/连接池**（一家失火全楼担责） | **进程级隔离**（一个实例挂不影响另一个） |
| **资源管控** | 只能靠超参（`performance schema/资源组/用户级限流`）没有硬墙 | 每实例独立配参（Buffer Pool/连接数/调度）天然物防 |
| **运维** | 一套 datadir 一份备份，备份/恢复整体进行 | 每套实例独立备份/恢复/升级 |
| **复制** | 一套主从复制覆盖所有 database | 每业务独立的主从对拓扑 |
| **粒度 Agriculture** | 大一统，适合业务少| 更适合多业务、资源/合规各异的组织 |
| **升级** | 一次升级影响所有 database | 每实例可分批滚动升级 |
| **授权/成本** | 低，多 database 共享硬件 | 成本高（重复配置），国有/合规场景常见 |
| **版本演进** | 单点（一个 database 的表结构演进难独立） | 各 database 对应的实例可独立演进 |

#### 2.5.3 深入：其实无机多 database 也有“软租户感”

一些能让“共享实例”更接近“隔离”的手段（属工况优化而非结构变革）：

- **`innodb_undo_tablespaces` 多表空间 + 拆表空间/redo 分盘**（缓解 I/O 争用）
- **`resource group` + `SET RESOURCE GROUP`**（把某个 database 的连接绑定到一组 CPU 上）
- **`performance_schema` 事件过滤器**（按 database 分监控与告警）
- **Proxy 类中间件（MySQL Router / ProxySQL）**（让“逻辑上感觉是一个库、物理上按 db 路由到不同实例”）

 这些手段都在**共享 Buffer Pool / 共享崩溃恢复边界**之上 operation——**真正的进程级隔离在 MySQL 里只能靠“拆多实例”**。

#### 2.5.4 一句话记忆

> **MySQL 的“多租户” = 一个 mysqld 进程里多个 database 目录，共享一个 Buffer Pool/redo/undo 连接池，靠权限和运维手段做“逻辑隔离”；真正在乎故障半径/资源隔离就拆多实例。**

### 2.6 部署形态②：主从复制形态 vs 单实例（与 Oracle RAC 的本质不同）

前面 §2.5 讲了“一实例 vs 多实例”是**纵向**的部署形态问题。这一节讲正交的横切维度：**“单机”还是“多机复制”**——也是初学者最需要点破的一个维度。

> 最大类比陷阱：很多人以为 MySQL 的“主从” ≈ Oracle 的“RAC”，其实**完全不是一回事**：

| | Oracle RAC | MySQL 主从复制 /(Group Replication) |
|---|---|---|
| 存储共享 | **共享一份磁盘数据**（SAN/ASM，所有节点读同一份数据文件） | **每台机器一份完整数据副本**（本地盘，不共享） |
| 访问/写 | **N 个实例同时读写同一份数据块**（Cache Fusion 协调） | **只有主库可写，从库只读**（或 Group Replication 选主） |
| 数据一致性 | “单库多实例”，强一致共享内存缓存 | “一主多副本”，**异步副本 / 弱一致读**（半同步有窗口） |
| 扩展方向 | 写可扩（多写节点） | **写不扩（只有主写），读可扩（加从库）** |
| 容灾 | 不是容灾（共享存储） | **就是容灾**（副本在别的机器，跨机房 PagerDuty） |
| 故障切换 | 不需要（宕一个实例还有 N-1 个实例连着同一份数据） | **需要主从切换**（ 要么手动 promote，要么 MGR 自动选主） |

一句话总结：**Oracle RAC 是“多机器融为一体共享一份存储”；MySQL 主从是“同构的多份存储彼此复制同步”。** 前者让 N 个进程像同一个库，后者的每个节点都是完整的另一个库。

#### 2.6.1 认知纠偏（呼应 §2.4）

- 单实例：一个 mysqld 管**一个 datadir 里的所有 database**，高可用靠备份+手动恢复。
- 主从复制：一个主库（Primary）+ N 个从库（Replica），**每台机器都有完整的一份数据文件和 redo**，主库把 binlog 流发给从库，从库重放来保持（近）实时一致。
- 这也解释了为什么 **MySQL 没有 Oracle RAC 那种“Cache Fusion”**：各副本的内存池各管各的数据，副本之间不共享内存——唯一共享的是“**binlog 流**”这条逻辑关系。

#### 2.6.2 主从复制强制多出三类基础设施

1. **复制链路**：**从库的 IO_THREAD 从主库拉 binlog** → 写本地 **relay log** → **SQL_THREAD（或 SQL worker，并行重放）重放 relay log**。
2. **故障切换支持**：MHA / Orchestrator / MySQL Router（8.4+/9.x InnoDB Cluster 自带）等外围工具做 promote、DNS 切流。
3. **网络连通与 binlog 保留**：`binlog` 为 row 格式，`binlog_expire_logs_seconds` 保留时长必须大于你的复制延迟窗口，否则`Got fatal error 1236`从库丢 binlog 就要重建。

**`binlog_format` 的三种（重点）**：STATEMENT（语句，小文件但非确定性函数会错）、**ROW（默认，记录“行改前/改后”，最安全、最通用）**、MIXED（自动切换）。Oracle 没有这层概念——它的 redo 就是“块级物理日志”；**MySQL 的 binlog 是“逻辑日志”，而 InnoDB 的 redo 是“物理日志”，两者用途不同：redo 管崩溃恢复，binlog 管复制和时间点恢复**（§6.4 详拆）。

#### 2.6.3 高可用与扩展（复制解决什么、不解决什么）

- **单点故障**：主库宕机 → 从库把它们晋升为主库，其他从库重新指向新主库——这是 MySQL 世界里最基本的“高可用”。
- **读扩展**：写走主库、读走从库（`read_only=ON`/`super_read_only` 强制只读）。**但读从库有“复制延迟”问题**：主库写完立刻去从库读，可能读到旧数据——处理思路是“读写分离 + 会话内一致性保证”（GTID 或者 ProxySQL 加 sticky hint）。
- **写不扩展**：不论加多少从库，**只有主库可写**（Group Replication 单主模式同理；多主模式虽然多节点可写，但冲突检测和回滚几乎必然需要应用理解）。所以 **MySQL 的“写扩展”要走“分库分表（Sharding）”**，不是“加节点”。
- **容灾是免费送的**：从库天然是“另一台机器的完整副本”，跨机房/跨地域部署一个从库即“异地容灾”——对比 Oracle 需要 Data Guard，MySQL 这层原生就有。
- **⚠️ 重大澄清：半同步 ≠ 零丢失**。Semisync（§7.4 详解）只保证“binlog **送到**了至少一个从库的内存”，不保证“**重放**到从库数据文件”——如果主库在“从库收到但没执行”的瞬间挂了，被晋升的从库**可能没有那条事务**。所以金融级零丢失仍要靠 **Group Replication + 强一致约束/多数派协议**。

#### 2.6.4 和本文前面概念的关系（地图拼起来）

- **redo/binlog（呼应 §6.4）**：redo 是**每实例独立**（每个 mysqld 自己的 `#innodb_redo`）；binlog 也是**每实例独立**（自己的 `binlog.000001...`）。主从复制的本质是“从库用主库的 binlog 流重放到自己的数据上”——**没有 Oracle 那种 redo thread 概念，binlog 没有“thread#”**，一个实例一条流。
- **Buffer Pool（呼应 §3）**：每个 mysqld 一份自己的 Buffer Pool，副本各自的内存各管各的数据；不存在跨节点的全局缓存。
- **进程模型（呼应 §4）**：`IO_THREAD/SQL_THREAD` 是**从库专属**线程，主库侧对应的是 **binlog dump 线程**；主从两侧线程名对上，才能定位复制延迟到底卡在哪一段。
- **容灾 vs 读写分离 vs 多源写入**：这是三个正交目标——容灾走“主从/半同步”，读扩展走“从库+读写分离”，写扩展走“分库分表”或“Group Replication 多主（付出冲突代价）”。

#### 2.6.5 一句话记忆

> **MySQL 主从 = N 份完整数据副本 + 一条 binlog 流（一主多从，读可扩、写不扩、天然异地容灾）；Oracle RAC = N 个实例共享一份存储（共享内存缓存，读写都可扩、无异地容灾能力）。两者只共享“高可用”这个词，不共享原理。**

---

## 3. MySQL 的内存结构：Buffer Pool 与各类缓冲区里到底放了什么

mysqld 启动时按配置参数申请一大块共享内存（主要是 **InnoDB Buffer Pool**），加上每条连接私有的内存。整体对比 Oracle 的 SGA/PGA 分类：**Buffer Pool 是“共享的公共内存”，threads buffer 是“各自的草稿纸”**。

### 3.1 总览图

```
                       mysqld 进程的内存
   ┌───────────────────────────────────────────────────────────────┐
   │              InnoDB Buffer Pool（默认 128MB 起，生产按需放大）      │
   │        所有服务线程共享的“数据页缓存”，InnoDB 大部分内存都在这        │
   │                                                                 │
   │  ┌─────────────────┐  ┌──────────────┐  ┌──────────────────┐    │
   │  │ 数据页 + 索引页   │  │ Change Buffer │  │ AHI (Adaptive    │    │
   │  │ （脏页也在这里）  │  │ (写缓冲，8.4  │  │  Hash Index)     │    │
   │  │                 │  │  默认关闭)    │  │                  │    │
   │  └─────────────────┘  └──────────────┘  └──────────────────┘    │
   │  ┌─────────────────┐  ┌──────────────────────────────────┐     │
   │  │ Log Buffer      │  │ 附加缓存（数据字典缓存，cached 毕竟在  │     │
   │  │ (redo 先写这里)  │  │ Buffer Pool 外，8.0 事务字典内存结构）   │     │
   │  └─────────────────┘  └──────────────────────────────────┘     │
   └───────────────────────────────────────────────────────────────┘

   Server 层的其他共享内存（较小）：
   ┌───────────────────────────────────────────────────────────────┐
   │ Binlog Cache（每事务一份，写满临时落盘 binlog cache 文件）           │
   │ Table Cache（表定义缓存，`table_open_cache` 控制）                  │
   │ performance_schema 内部内存                                       │
   └───────────────────────────────────────────────────────────────┘

   每个会话/服务线程 独占的私有内存（“草稿纸”）：
   ┌───────────────────────────────────────────────────────────────┐
   │ Sort Buffer（ORDER BY/GROUP BY 排序）                            │
   │ Join Buffer（嵌套循环连接的缓存）                                 │
   │ Read Buffer(BNL/BKA)、Read rnd Buffer（MRR）、                   │
   │ Transaction 私有小对象、savepoint 列表 …                          │
   └───────────────────────────────────────────────────────────────┘
```

### 3.2 Buffer Pool 与各类区域职责

| 内存区域 | 里面放什么 | 一句话作用 |
|---------|-----------|-----------|
| **Buffer Pool（数据页缓存）** | 从磁盘读进来的 **16KB InnoDB 页**的副本，含被改脏的页 | 读写都先在这里发生。读命中就不碰磁盘；改也先在内存改，之后异步刷盘（16KB 页的内部结构见第 11 节） |
| **Log Buffer（redo 缓冲）** | 每个 DML/DDL 生成的**重做记录**（页级物理变更） | 提交时由后台 redo 线程刷到 redo log 文件，保证宕机可恢复（redo 记录内部到底装了什么，见第 6.3 节） |
| **Binlog Cache（每事务）** | 本事务尚未写满/未提交的 binlog 事件 | 提交时把这份 cache 整体写入 binlog 文件，“事务在 binlog 中原子般连续”（两阶段提交见 §6.1） |
| **Change Buffer** | 二级索引页“本次不在 Buffer Pool”时的写缓冲（8.4 默认 `innodb_change_buffering=none`，需显式开启） | 把随机写合并，减少磁盘 I/O；代价是“读的时候要 merge 回去” |
| **AHI（自适应哈希）** | 热点页的查询关键词→页内记录的哈希索引 | 自动为热点模式加速点查；对负载敏感，有时是双刃剑 |
| **Table Cache / 数据字典缓存** | 打开的表定义、frm 的内存映像（8.0 起是事务性表结构） | 减少“每次查询重新解析表结构”的开销 |

> 9.7 起还有 **HGC/Lazy Buffer Pool 等新论文/新特性**逐步进入主干（如 9.4 引入的 async`(recover-related) 线程族调整、I/O 路径优化`），初学可暂不深究。

### 3.3 每线程私有内存放什么

每个客户端连接对应一个服务线程，它持有自己私有的“会话内存”：

- **Sort Buffer**：`ORDER BY` / `GROUP BY` 的排序，不够用就**落盘临时文件**（慢一个数量级）。
- **Join Buffer**：无索引的连接（Block Nested Loop / Hash Join in 8.0.18+）用。
- **Read 缓冲**：全表扫描的顺序读缓冲、MRR 的随机读缓冲。
- **业务层的绑定变量值**:存储在会话的 COMPILE 后的 prepared statement 上下文里（PS 的执行计划在 Server 层，和 Oracle 一样“计划共享、数据私有”的设计）。

> 记忆口诀：**Buffer Pool 是“公共的白板”，所有线程都能看；每线程的 sort/join buffer 是“你自己的草稿纸”，只有你自己用。** 但 MySQL 和 Oracle 在“计划”上有个微妙差别：**prepared statement 的计划是共享的（Server 层），普通 SQL 兜兜转转每次执行都从 Server 层重新起计划（8.0 有计划缓存，但生命周期较短）**——这也是“为什么要用预编译语句”的底层原理（第 8 节展开）。

---

## 4. MySQL 的后台线程与请求处理组件：每个线程/组件是干什么的

mysqld 是**单进程多线程**模型。没有 Oracle 那样“每连接一个专属服务器进程”，而是**少量线程 + 线程池/每连接一线程**的组合。记清每类线程的“人设”，对理解第 6 节 DML 流程至关重要。

### 4.1 连接与请求处理组件（“前台”）

| 组件 | 人设 / 职责 | 关键动作 |
|------|------------|---------|
| **监听器 / Connection Handler** | 门卫 | 接收 TCP 连接、做 TLS 握手、认证、把连接分配给一个服务线程（或有 thread pool 则入队） |
| **服务线程（per-connection thread / thread pool worker）** | 管家 | 解析 SQL、执行计划、调用 InnoDB 读写 Buffer Pool、返回结果；线程退出时内存归还 `thread_cache_size` 池 |
| **Thread Pool**（企业版/Percona；社区 9.x 有限实现） | 调度员 | 海量短连接时避免“每连接一线程”的崩塌，把请求排队到固定 worker 池 |

### 4.2 InnoDB 后台线程（“数据库自己人”）

| 线程 | 人设 / 职责 | 关键动作 |
|------|------------|---------|
| **Master Thread** | 总管家 | 主循环里调度其他工作：合并 Change Buffer、刷新脏页、推进 checkpoint、做 idle 期维护 |
| **Page Cleaner Threads**（`innodb_page_cleaners`，可多个） | 刷盘工 | 把 Buffer Pool 里的**脏页**写回磁盘。**它是“懒”的**（按 flush list try flush + checkpoint 目标推进），提交时不一定写你的页 |
| **Log Writer / Log Flusher / Log Checkpointer / Log Write notifier 等日志线程（8.0.30+ 重构的“dedicated log threads”）** | 持久化守门员 | 把 Log Buffer 的 redo 写入文件缓冲并 fsync 到 redo log；推进 check-point LSN；唤醒等待的事务 |
| **Doublewrite Buffer**（共享表空间 / 独立 `#ib_16384_0.dblwr`，8.0.20+ 文件化） | 断页防护 | 刷盘前先把页写到 doublewrite 区域，防止“半个页写坏”（对应 Oracle 的块尾校验，机制不同见 §11.7） |
| **Purge Threads**（`innodb_purge_threads`，默认 4） | 清道夫 | 提交后，把“不再被任何事务需要”的 undo 链收缩掉；同时清理 killed 事务、落地删除标记（purge 处理 delete 的“延迟真删”） |
| **Read/Write IO Threads**（`innodb_read_io_threads/write_io_threads`） | I/O 电梯 | 异步预读、页读写请求与 aio 收发 |
| **Change Buffer Merge / dict 统计等线程** | 打杂 | Change Buffer 回合合并、dict_stats 统计采样 |

### 4.3 复制与恢复相关线程（呼应 §2.6）

| 线程 | 位置 | 职责 |
|------|------|------|
| **dump thread（binlog 发送线程）** | 主库 | 每个连接上的从库一个，从 binlog 文件按 GTID/位点把事件推出去 |
| **IO_THREAD（receiver）** | 从库 | 接收 binlog 事件，写成本地 relay log |
| **SQL_THREAD / applier worker** | 从库 | 重放 relay log：MLR（Multithreaded Applier，`replica_parallel_workers`）按事务依赖并行重放 |
| **Coordinator** | 从库 | 分派 relay log 事务给 applier worker，维持 (server_id, gtid) 依赖图 |
| **GTID/恢复辅助进程** | 主库 | CENTRAL: binlog 归档、`mysqlbinlog` 备份恢复、clone plugin 数据搬运等 |

**一条 SQL 的执行，就是“你的服务线程”借 Buffer Pool 干活、叫后台线程配合的过程** —— 第 6 节会完整演示。

---

## 5. 读不阻塞写、写不阻塞读：MySQL 的一致性读与多版本

这是 MySQL（InnoDB）最核心的并发设计思想，也是“读不影响写，写不影响读”的答案。注意本节只讲**读的部分**——**InnoDB 的 MVCC 只支持“快照读”（普通 SELECT）；“当前读”（`SELECT ... FOR UPDATE` / `LOCK IN SHARE MODE` / UPDATE / DELETE）不走 MVCC，直接读最新版本并加锁**，这是它和 Oracle“所有读都是一致性读”最大的行为差异（深入对比见 [MVCC原理.md](MVCC原理.md)）。

### 5.1 两条基本性质（快照读层面成立，当前读层面依然走锁）

- **读不阻塞写**：普通 SELECT 不需要等写者释放锁，它读“自己该看到的那个版本”。
- **写不阻塞读**：写者改数据时，不需要把读者挡在门外（读者看旧版本）。

注意：**MVCC 只解决“读-写互不阻塞”，不解决“写-写冲突”**。两个人同时改同一行，仍要靠**行锁（记录锁）+ 间隙锁/临键锁排队**，谁先改谁持有锁。另外 InnoDB 里“当前读”也会绕过 MVCC 直接加锁——所以严格说：**InnoDB 是“Select 快照读不阻塞写；改数据的当前读仍互斥”**。

### 5.2 InnoDB 靠什么实现？事务 ID + ReadView + undo 版本链

InnoDB 的底层设计哲学，先记住这句关键的话：

> **InnoDB 把“行的多个版本”直接存在表里（行的旁边），靠 undo log 把“旧版本”链起来；每个事务带着一个 ReadView（可见性判定器），读时沿着版本链往回挑“我该看见的版本”。**

三个核心部件：

1. **trx_id（事务 ID）**：InnoDB 全局递增的“事务编号”，每行藏着**最后一次改自己的那个事务的 trx_id**（记在行头的 `DB_TRX_ID` 列）。它既标记“这行被谁改的”，也用来与 ReadView 里的边界比较。
2. **ReadView（可见性视图）**：一个内存判定器，含四个核心值——`m_ids`（创建视图时**所有活跃事务的 ID 列表**）、`min_trx_id`（其中最小的）、`max_trx_id`（下一个将分配的位置）、`creator_trx_id`（视图创建者自己）。**它的判定逻辑是“这行的修改者，在我创建视图时是否已经提交”**。
3. **undo log（版本链）**：每行后面跟着 `DB_ROLL_PTR` 回滚指针，指向它的**上一个版本**（存在 undo 页里），undo 页里的旧版本又指向前一个，串成一条链。**一条 undo 记录里到底装了哪些字段、为什么是“链”、三种 DML 的前像有何不同，见第 5.5 节。**

> 术语澄清：Oracle 说“SCN”、InnoDB 说“trx_id + ReadView”。它们都是“给时间打标记”的方式，但粒度不同——**Oracle 是全局单调时钟，InnoDB 是“事务列表 + 边界”**；这正是“Oracle 语句级一致性天然廉价、InnoDB 需要 RR 级别才能一事务一个视图”的根源。

### 5.3 一致性读（Consistent Read，快照读）的过程

以默认的 **REPEATABLE READ（事务级一致性）** 为例：

```
① 一条 SELECT 开始。取本事务的 ReadView（RR 下第一个快照读时建立，事务内复用；
   RC 下每条语句新建一个 ReadView）。

② 要去读某个数据页 P 上的行 R：
     └─ 看行头 DB_TRX_ID，与 ReadView 比较：
          ├─ 改它的 trx_id < min_trx_id        → 活动事务早就结束，直接 可见 ✅
          ├─ 改它的 trx_id ≥ max_trx_id        → 视图建立之后才开始的事务，不可见 ❌
          ├─ 改它的 trx_id 在 [min, max) 且不在 m_ids → 已提交，可见 ✅
          ├─ 改它的 trx_id 在 [min, max) 且在 m_ids   → 未提交，不可见 ❌
          └─ 改它的 trx_id == creator_trx_id   → 我自己改的，可见 ✅

③ 若不可见：沿 DB_ROLL_PTR 指向的 undo 版本链往回走，检查每个历史版本的
   trx_id，直到找到第一个“可见”的版本，返回它。

④ 在 Buffer Pool 中把这条“回放出来的旧版本”读出并返回。（虚像的旧记录
   是临时构造的，不写盘。）
```

由此得到三个关键结论（这就是“读不阻塞写、写不阻塞读”的机制来源）：

- **未提交的修改会被跳过**：所以 InnoDB 快照读**天然没有脏读**——读者看见的是“可见的最后一个版本”，根本不理会没提交的修改；读者完全不需要等写者。
- **只有“快照读”走 MVCC**：普通 `SELECT` 走；而 `SELECT ... FOR UPDATE`、`UPDATE`、`DELETE` 是**当前读**——它们必须读最新版本并加行锁（所以当前读会阻塞写者、也可能被 gap lock 扩大）。**这是 InnoDB 和 Oracle 最容易踩行为差坑的地方**：Oracle 的任何 SELECT 一律一致性读；InnoDB 的 UPDATE 会读“现在的事实”。
- **版本链是虚空构造的**：旧版本存在 undo 页里，**构造出来的是内存临时产物不写盘**；但链太长时逐版本回溯会慢，这就是“长事务把 undo tablespace 顶爆 + 查询变慢”的根因（§5.5.6 讲 purge 的约束）。

### 5.4 隔离级别与 ReadView 的关系

InnoDB 对外暴露“四档”隔离级别（比 Oracle 多，详见 [DB事务隔离级别.md](DB事务隔离级别.md)）：

| 级别 | ReadView 粒度 | 效果 | 备注 |
|------|---------|------|------|
| **READ UNCOMMITTED** | 不用 ReadView，直接读最新 | 脏读、不可重复读、幻读全开 | 生产基本不用 |
| **READ COMMITTED（RC，PG/Oracle 的默认档位）** | **每条语句**新建一个 ReadView | 单条语句内部一致；语句之间能看到别人新提交的数据 | 无脏读，可能不可重复读/幻读 |
| **REPEATABLE READ（默认）** | **事务第一个快照读时**建立，事务内沿用 | 整个事务看到同一个时点数据 | **InnoDB 不只“可重复读”，还靠 Next-Key Lock 挡住了幻读**（对快照读靠 MVCC、对当前读靠间隙锁） |
| **SERIALIZABLE** | 同 RR 但所有普通 SELECT 自动升级为“当前读+共享锁” | 读写全互斥，等于串行 | 性能差，极少用 |

> 深入：很多人说“RR 下没有幻读”，其实要说准——**快照读**靠 MVCC 已防住幻读；**当前读**界面上仍可能“幻”，InnoDB 用 **Next-Key Lock（记录锁 + 间隙锁）** 封住扫描用的范围，才把幻读也挡住。这也是 InnoDB RR 比 Oracle SERIALIZABLE“名实更相符”的地方（Oracle 的 SERIALIZABLE 其实是快照隔离，防不住写偏斜，§5.4 Oracle 版有拆解）。写偏斜在 InnoDB RR 下同样存在——**要绝对严格，只有 SERIALIZABLE 或显式 `SELECT ... FOR UPDATE` 锁住相关行**。

---

### 5.5 深入：UNDO 记录（Undo Log）到底包含什么

前面几节把 undo 当成“黑盒”在用（一致性读靠它、回滚靠它）。这一节把它拆开，看清**一条 UNDO 记录里到底装了哪些东西**。理解了它，第 5.2 节的“沿 DB_ROLL_PTR 找 undo”和第 6 节的“记 UNDO 前像”就不再是空话。

#### 5.5.1 先澄清：UNDO 存在哪、它是什么

- **存在哪**：undo 存在独立的 **undo 表空间**（`innodb_undo_001/002`，默认 2 个）里的 undo 页。8.0 起支持 truncate 自动收缩（`innodb_undo_log_truncate=ON`）。
- **是什么**：undo 记录的是**“修改前的值（before image）”**。**InnoDB 的“版本链”就是把这些 compound 数组串起来的产物**——严格说 InnoDB 有两类 undo：
  - **insert undo**：只在**本事务回滚时**自己用（别的读事务不可能“看见”一个新插入的行，因为插入的事务还没提交、修改者它自己可见）；事务提交即可回收。
  - **update undo**：要支撑**其他事务的一致性读**（ReadView 回溯需要它），提交后要等到“不再有任何事务需要它”才被 purge 线程回收（Purge Threads，§4.2）。
- **一条 DML 不止一条 undo**：一次 `UPDATE` 改了 3 列 → 具体记法取决于 undo 类型（§5.5.3）；同一事务对同一行改多次 → 多条 undo 串成链。

#### 5.5.2 UNDO 记录里具体包含哪些内容

一条 UNDO 记录（存在 undo 页里，通过 `TRX_UNDO_` 系字段串接）大致由以下内容组成：

| 组成部分 | 含义 |
|---------|------|
| **trx_id** | 这条 undo 属于哪笔事务（与行头 `DB_TRX_ID` 同源） |
| **undo 类型 / 目标** | insert / update（update 又分“改主键”与“不改主键”两种记法，见 §5.5.4） |
| **回滚指针 roll_ptr** | **指向本行上一版本**的指针（`roll_ptr` 编码 undo 表空间号 + 页号 + 偏移），靠它把版本链串起来 |
| **表 ID / 索引 ID** | 这条 undo 属于哪张表的哪个索引（purge 与回滚时定位） |
| **旧值** | insert undo 是“**没有旧值**”（回滚=删）；update undo 存“改前那一行的列值”（但**主键不重复存**，见 5.5.4） |
| **信息位 / 前像附加段** | 例如被删行的 delete mark、被更新列的位图、虚拟列/外键信息等 |

> 关键点：**undo 本身也是“数据”，修改 undo 页同样要写 redo**。所以 redo 同样保护 undo。崩溃恢复时，InnoDB 先 redo 前滚（包括把 undo 页也重做），再用 undo 把未提交事务回滚。一句话：**redo 管“不丢”，undo 管“能撤”，undo 被 redo 管着——这一点 MySQL 和 Oracle 一样。**

#### 5.5.3 版本链：undo 为什么是“链”

同一个行被同一个事务多次修改，或被多个事务先后修改，都产生多条 undo，它们通过 **roll_ptr** 串成一条链：

```
行 R 的最新版本（Buffer Pool 页内）
      │  DB_ROLL_PTR
      ▼
 undo 记录 #3（最近一次改：balance 500→400）── roll_ptr ──▶
 undo 记录 #2（上一次改：balance 600→500）── roll_ptr ──▶
 undo 记录 #1（最早一次改：balance 700→600）── roll_ptr ──▶ NULL（链尾）
```

- **回滚（ROLLBACK）**：从事务“当前”undo 出发，沿 roll_ptr **从新到旧** 依次反向操作，把每次修改都撤销掉，直到链头。
- **一致性读（CR）**：§5.3 讲过，ReadView 判定“不可见”时沿 roll_ptr 往回走，挑第一个可见的版本；如果一次还不够（那一版也在视图之外），就继续往前找。

#### 5.5.4 三种 DML 的 undo 前像对比（InnoDB 相对于 Oracle 有个巧妙优化）

| DML | 产生的 undo 前像 | 回滚时怎么反向 |
|-----|----------------|---------------|
| **INSERT** | 只记**主键值**（改的是哪个 PK、加了哪行） | 反向 = **DELETE** 掉刚插入的那行（按主键定位） |
| **UPDATE（不更新 PK）** | 记“这一行**被改的列**的旧值”（列按需记，不必整行） | 反向 = 把列**改回旧值**（原地 update 回去） |
| **UPDATE（更新 PK）** | 拆两条 undo：**delete mark + insert** 两条记录 | 反向 = 先按新 PK 删掉新行，再把旧 PK 的行 delete-mark 反标记回来 |
| **DELETE** | **整行旧值都要记**（因为要能“复原整行”） | 反向 = 把 delete-mark 的行**重新 unmark 复活**（如果还被引用）或真删 |

> 与 Oracle 的一个小差异：**InnoDB DELETE 只做“delete mark（打删除标记）”，物理删除延迟给后台 purge 线程**——所以“删不改 Buffer Pool 原页物理数据、MVCC 还要利用这条 delete-mark 版本当‘我该看见的那个版本’”；Oracle 是“标记删除后 SMON 清理 + 索引叶子不回收（留给新插入复用）”。结果是两边“删除都是两段式”，但各自不同：**Oracle 索引叶子标记 Delete 后留给复用；InnoDB 的聚簇索引一行的删除由 purge 真删**。
> 注意 **DELETE 的 undo 最“重”**——它要保留整行数据，所以大批量删除时 undo 消耗最大、purge 也最慢。这也是“删数据慢 / 大事务回滚久”的底层原因之一。

#### 5.5.5 UNDO 被谁消费：四个“买家”

UNDO 不是写完就没人看，它有几个主要消费者：

1. **事务回滚（ROLLBACK）**：主动 `ROLLBACK`，或用 §5.5.3 的链把改动还原；InnoDB 还有**部分回滚到 savepoint** 的能力（Oracle 同样支持）。
2. **一致性读（CR）**：§5.3 查询构造旧版本，靠的就是 undo 版本链。
3. **崩溃恢复（redo 前滚后）**：InnoDB 崩溃重放完 redo 后，遍历 undo 找“未提交事务”并回滚（相应状态存在 undo 页的事务表里）。
4. **purge 线程的“真删”**：DELETE 打标记的行，要等“没有任何存活 ReadView 需要看它”时，由 purge 线程真正物理删除并回收 undo 链。

> Oracle 有个 **Flashback Query**（基于 undo 的 `AS OF` 历史读）——**InnoDB 没有等价物**。它只把 undo 当“并发控制的燃料”，不对外提供“闪回查询”SQL（想看历史要靠 binlog/延迟从库）。

#### 5.5.6 经典问题：长事务吃掉 undo（对应 Oracle 的 ORA-01555）

- **原因**：一条 SELECT 或一个事务跑得很久，它的 ReadView 都还活着 → 它“需要看见的旧版本”对应的 **update undo 不能被 purge**，undo 表空间持续膨胀；同时这条 SELECT 沿版本链回溯很多层，越来越慢。
- **缓解**：让长事务尽快结束；调大 `innodb_max_undo_log_size`；`innodb_undo_log_truncate=ON`；监控 `information_schema.INNODB_TRX` 里的最大事务时长；业务上避免“开事务读一下就不管了”。
- 更系统的对比（Oracle 的 ORA-01555 vs MySQL 的“历史链过长”）在 [MVCC原理.md](MVCC原理.md) 第 4 节有专门展开。

#### 5.5.7 实操：怎么观察 UNDO

```sql
-- 1) 历史 undo 使用与“快照过旧”风险：看历史链长
SELECT name, count
  FROM information_schema.INNODB_METRICS
 WHERE name LIKE 'trx_rseg_history%';

-- 2) 当前活跃事务及其运行时间（揪出“长事务”）
SELECT trx_id, trx_started,
         TIMESTAMPDIFF(SECOND, trx_started, NOW()) AS running_sec,
         trx_state, trx_rows_locked, trx_rows_modified, trx_query
  FROM information_schema.INNODB_TRX
 ORDER BY trx_started;

-- 3) undo 空间使用（undo 表空间大小与状态）
SELECT file_name, tablespace_name, engine,
       ROUND(total_extents * extent_size / 1024 / 1024) AS mb
  FROM information_schema.FILES
 WHERE tablespace_name LIKE 'innodb_undo%';

-- 4) 当前表上有多少 purge 待办（pending truncate/limit）
SELECT name, count
  FROM information_schema.INNODB_METRICS
 WHERE name IN ('trx_purge_trunc_history', 'trx_purge_truncate_use_count');
```

#### 5.5.8 小结（与全文呼应）

- UNDO 记录 = **trx_id + roll_ptr + 表/索引 ID + 旧值（按 DML 类型细分）+ 提交/purge 状态**，是“怎么撤销改动”的说明书，也是“给 ReadView 挑旧版本”的链。
- 它是**指针桥**：行头 `DB_ROLL_PTR` → undo 记录 →（roll_ptr）→ 上一条 undo，同时支撑“回滚链”与“一致性读链”。
- UNDO 被 **redo 保护**，被 **回滚 / 一致性读 / 恢复 / purge** 四类场景消费。
- 它和 §5.3 一致性读、§6 的 DML 流程、§10.3 的 Undo 表空间、§11 的行头三列是同一套机制的五个切面。

---

## 6. 一条 DML 语句的完整旅程：各线程如何配合

以一条 `UPDATE accounts SET balance = balance - 100 WHERE id = 1;` 为例，把第 3、4、5 节的所有部件串起来。这是理解 MySQL 全貌最关键的一节。

### 6.1 时序全景

```
客户端 App
   │  ① 发送 SQL（携带绑定变量 ?id=1, ?amt=100）
   ▼
【服务线程】（代表该会话干活，独占一份 sort/join buffer）
   │  ② 词法/语法解析（Server 层）→ 预处理器 → 优化器（选索引/join 顺序）→ 执行器
   │  ③ 执行器调 InnoDB 接口：去 Buffer Pool 找 id=1 所在的数据页
   │       ├─ 命中 → 直接用
   │       └─ 未命中 → 从 .ibd 物理读入 Buffer Pool
   │  ④ 定位要改的那一行并读最新版本（UPDATE 是“当前读”，不走 MVCC 版本链）
   │  ⑤ 真正修改 Buffer Pool 中的页（此时还没落盘）：
   │       a. 在 undo 表空间分配 undo，记录 balance 的“前像”（比如原来是 500；
   │          undo 记录里具体装了什么，见第 5.5 节）
   │       b. 把页里的 balance 改成 400，行头 DB_TRX_ID 改成本事务、
   │          DB_ROLL_PTR 指向刚写的 undo
   │       c. 生成 redo：① undo 页的改动 ② 数据页的改动 → 顺序写入 Log Buffer
   │       d. 同步生成 binlog 事件（row 格式：记“改前/改后的行”）缓存在 Binlog Cache
   │       e. 在该行上行锁（别人改/当前读同一行要等；RR 下还可能加间隙锁）
   ▼
【COMMIT】用户提交（两阶段提交！这是 MySQL 的独门动作）
   │  ⑦ prepare：InnoDB 把 redo（含 XID）同步刷到 redo log（fsync），事务标记为 prepare
   │  ⑧ binlog：Server 层把 Binlog Cache 里本事务的事件写入 binlog 文件（并 fsync）
   │  ⑨ commit：InnoDB 写入 commit 标记，释放行锁
   │     —— 顺序：redo prepare → binlog → redo commit，崩溃恢复“二者对齐”才认账（§6.2）
   ▼
【后台线程异步收尾】
   │  ⑩ Page Cleaner 在合适时机（脏页过多/ checkpoint 推进）把脏页异步写回 .ibd
   │        写盘前：先把页写到 Doublewrite Buffer，再写到真正的位置（防断页）
   │  ⑪ Log Flusher 持续把 Log Buffer 的 redo 追加刷盘，checkpoint 相应推进
   │  ⑫ 主库的 dump 线程把 binlog 推给从库 IO_THREAD → relay log → applier 重放
```

### 6.2 几个必须理解的设计要点

1. **提交 ≠ 立即写数据文件**。COMMIT 只强制“redo prepare + binlog 落盘”（保证“已提交就不会丢”），脏页由 Page Cleaner 懒写。它和 Oracle 是同一个思想：**把“随机写数据页”和“顺序写日志”解耦**。

2. **两阶段提交（2PC，MySQL 独有的一道工序）**：redo 与 binlog **必须原子一致**——否则一有崩溃就“redo 说改了、binlog 说没改”，主从立刻分叉。InnoDB 与 Server 层用内部 XA：
   - **崩溃恢复规则：redo 处于 prepare 且 binlog 里已有完整事务 → 提交；binlog 里没有 → 回滚。**
   - 所以“redo prepare → 写 binlog → redo commit”的**顺序**不可打乱，`sync_binlog` 与 `innodb_flush_log_at_trx_commit` 各管一段持久性（§8.2）。
   - Oracle 没有 binlog 这一层（Data Guard 同步的载体就是 redo 本身），所以 Oracle 不需要这道工序。

3. **redo 与 undo 的关系**（与 Oracle 一致）：
   - **redo** 保证“改动能恢复/不丢”——宕机后用 redo 把数据页重做到提交状态。
   - **undo** 保证“能回滚/能读旧版本”——事务回滚、一致性读都靠它。
   - **undo 的写入本身也被 redo 保护**（步骤 ⑤c 的 ①），所以 undo 也不会丢。

4. **线程协作一句话总结**：**服务线程负责“算和改”，Log 系列线程负责“保持久”，Page Cleaner 负责异步落盘（Doublewrite 兜底断页），Master/Purge 负责“清理与回收”，binlog dump/IO/applier 线程负责“多机复制”。**

---

### 6.3 深入：REDO 记录（Redo Log Record）到底包含什么

上一节（§6.2）反复提到“redo 保持久、undo 管回滚、redo 还保护 undo”。这一节把 **REDO 记录**拆开，看清它到底装了什么——它是 InnoDB “崩溃不丢数据”的根基。

#### 6.3.1 先澄清：REDO 记的是“物理页上的改动”，不是 SQL

- **记的是什么**：InnoDB redo 记的是**“对哪个页（space id, page no）的哪个位置做了什么改动”**，是**页级物理日志**，并用 **MTR（mini-transaction）** 打包成原子单元。它描述“把页 5 内偏移 38 起的若干字节改成 X”——不是“把 SQL 再执行一遍”。
- **为什么是页级**：物理日志可以“直接贴回页面”，崩溃恢复无需重演 SQL 语义、不受触发器/约束干扰，恢复很快——这一点与 Oracle 的块级 redo 完全同构（Oracle 的粒度是 Data Block，InnoDB 的粒度是 16KB Page 内的字节段）。
- **存在哪**：redo 写在 `#innodb_redo` 目录下的一组环形文件（8.0.30+；旧版本为 `ib_logfile0/1`），由日志线程顺序写。**它不参与复制**——主从同步靠 binlog，redo 只属于本实例。
- **一条 DML 产生多个 redo 记录**：改数据的同时也要改 undo 页、可能改页头/段头/FSP，多条记录打包在一个个 **MTR** 里原子提交。

#### 6.3.2 REDO 记录里具体包含哪些内容

一条 redo 记录的结构大致如下：

```
Redo Record（一条物理 redo 记录）
 ├─ type (MLOG_*)            ← 操作类型
 ├─ space id + page no       ← 哪个表空间的哪一页（目标页地址，类比 Oracle 的 DBA）
 ├─ 页内 offset / 长度        ← 改这一页的哪个位置、改多少字节
 ├─ 改后的新值                ← after image（页物理内容）
 └─ （MLOG_UNDO_* 类记录会内嵌 undo 记录的内容本身）
```

核心字段：

| 字段 | 含义 |
|------|------|
| **type (MLOG_\*)** | 操作类型：`MLOG_WRITE`（写字节串）、`MLOG_COMP_REC_INSERT/MLOG_REC_UPDATE_IN_PLACE`（插入/原地更新记录）、`MLOG_UNDO_INSERT`（写 undo）、页初始化/截断等几十种 |
| **space id + page no** | 改的是哪个表空间、哪一页（等价于 Oracle DBA“文件号.块号”的二段定位） |
| **页内 offset / 长度** | 页内偏移量 + 改动字节数，精确定位“页的哪一处被改” |
| **新值（after image）** | 改动**之后**这几个字节是什么 |
| **MTR 边界** | 一个 mini-transaction 内的多条记录要么整组重放、要么整组跳过（原子性） |

> 关键点：**一次 UPDATE 的 redo 通常含多个记录**——① 改 undo 页（登记事务）② 改数据页（真正改数据）③ 可能改页头/段头 ④ 提交标记。这就是“**undo 的写入本身也被 redo 保护**”这个 Oracle 结论在 MySQL 中的呈现。

#### 6.3.3 LSN：redo 日志流里的“坐标”

- **LSN（Log Sequence Number）** 是整个日志流上**单调递增的逻辑字节偏移**（8 字节整数）。它是 InnoDB 的“全局时钟”——这一点对齐 Oracle 的 SCN，但**InnoDB 的 MVCC 不直接用 LSN 做可见性**（用 trx_id + ReadView，见 §5.2），所以它更像 Oracle 里“Checkpoint RBA”一族的身份。
- 实际会见到好几个“LSN 变量”，理解它们的分工是看懂运行状态的第一步：

| 派生 LSN | 含义 | 谁维护 / 在哪看 |
|---|---|---|
| **current_lsn（log sequence）** | 下一条 redo 要写入的位置（日志流的“水线”） | `SHOW ENGINE INNODB STATUS` 的 LOG 段 |
| **flushed_to_disk_lsn** | 已 fsync 到 redo 文件的最远点（对应 Oracle “On-disk RBA”） | `SHOW ENGINE INNODB STATUS` / INNODB_METRICS（`log_flushed_to_disk`） |
| **checkpoint_lsn** | **改动 ≤ 该 LSN 的脏页都已刷到数据文件** = 崩溃恢复的起点（对应 Oracle “Checkpoint RBA”） | `SHOW ENGINE INNODB STATUS` 的 Last checkpoint |
| **last_checkpoint / oldest modification** | Buffer Pool 里最老脏页的修改 LSN，决定 checkpoint 推进速度 | INNODB_METRICS |

- **Checkpoint 的本质**：**脏页刷盘进度与日志复用进度“握手”**——checkpoint LSN 推进到“相关脏页全部落盘”的位置，之前的 redo 即可环形覆盖复用。redo 文件写满 → 回卷覆盖最旧的（与 Oracle 日志切换同构）。

> 关键约束（理解“为什么提交必须先写 redo”）：**Page Cleaner 不允许把脏页写到数据文件，除非该页所有改动对应的 redo 已经 fsync 到磁盘**（即脏页最老 LSN ≤ flushed_to_disk_lsn）。这就是 WAL（Write-Ahead Log）“**先写日志、后写数据**”，与 Oracle“DBWn 禁止 high RBA 超过 On-disk RBA”完全同构。BINLOG 一般不承担这个物理一致性角色，但马上要讲的 2PC 保证它和 redo 最终一致。

#### 6.3.4 REDO 不记录什么（澄清误区）

- **redo 不记录 SELECT**：纯读不产生 redo（InnoDB 读既不触发延迟清理写、也不需要回滚保护）。
- **redo 不存“逻辑语义”**：WHERE 条件、行格式、索引结构等归 Server 层和表定义管；redo 只认“页 + 字节段”。
- **物理 redo ≠ binlog**：redo 是**页级物理**的（崩溃恢复专用、只属于本实例）；binlog 是**语句/行级逻辑**的（复制与时间点恢复专用，可搬到从库重放）。**两个日志各司其职，由 §6.2 的 2PC 保持相互一致**——MySQL 学习者最大的坑就是混用这两个 log。

#### 6.3.5 redo 与 undo：一对“好搭档”（呼应 §5.5）

一次 DML，InnoDB **同时生成两类记录**：

| | REDO | UNDO（§5.5） |
|---|------|------|
| 记的内容 | **改之后**的样子（页级/物理：哪个页哪个字节改成什么） | **改之前**的样子（行级逻辑：那一行改前是什么） |
| 作用 | 崩溃后**前滚重放**、 checkpoint 推进 | 事务**回滚**、一致性读、purge |
| 被谁保护 | redo 本身是“持久化的载体”，最底层 | **undo 被 redo 保护**（undo 页的改动也在 redo 里） |

一句话记忆：**undo 告诉你“怎么撤”，redo 告诉你“怎么重做”；redo 先保护 undo，再保护数据——MySQL 和 Oracle 在这一点上完全同构。**

#### 6.3.6 REDO 被谁消费：三个“买家”

1. **崩溃恢复（Crash Recovery）**：重启时从 **checkpoint_lsn** 开始重放 redo 到 flushed_to_disk_lsn（前滚：包括把 undo 页也重做），再用 undo 把未提交事务回滚；恢复期间还执行 **二阶段提交协调**（§6.2 第 2 条）处理 prepare 状态事务。
2. **Checkpoint 机制本身**：Page Cleaner 依据 redo 增长与脏页水位推进 checkpoint，让环形 redo 总有可复用空间（对齐 Oracle CKPT 的职责）。
3. **（间接）物理备份恢复**：XtraBackup / MySQL Enterprise Backup 在拷贝数据页的同时“追着 tail redo”，恢复时 apply-log 把页滚到备份完成时刻——与 Oracle RMAN 备份 + recover 的原理相同。

> 注意：**redo 不是复制的载体**。Data Guard 之于 Oracle = binlog 之于 MySQL（§7），两者不要搞混。

#### 6.3.7 物理层呼应：Redo 文件的“组 / 环形写”

- 8.0.30+ 的 redo 文件在 `datadir/#innodb_redo/` 下，是**一组环形文件**（32 个固定槽位；旧版本是固定的 `ib_logfile0/1`）。
- 总容量由 `innodb_redo_log_capacity`（8.0.30+ 新参数，取代 `innodb_log_file_size`/`innodb_log_files_in_group`）按需自动伸缩；写满 → 环形回卷覆盖 → 由 checkpoint 保证“被覆盖的 redo 已不再需要”。
- redo 的物理写入单位是 **512B 的 redo 块（log block）**，块头带 LSN 与校验信息；**这与 §11 的 16KB 数据页是两个不同层级的“块/页”概念**，也与 binlog 的事件块无关。
- 这组机制与第 10 节“逻辑存储层次”正交：redo 是**独立的顺序写文件**，不归表空间/段/区/页管理。

#### 6.3.8 实操：怎么观察 REDO

```sql
-- 1) LSN 三点进度（当前写到/已刷盘/checkpoint 起点）
SHOW ENGINE INNODB STATUS\G   -- 看 LOG 段：Log sequence / flushed_to_disk /
                              --     Last checkpoint 字段

-- 2) redo 写入与刷盘速率（自上次启动累计）
SELECT name, count
  FROM information_schema.INNODB_METRICS
 WHERE name IN ('log_writer_writes','log_flusher_flush',
                'log_total_log_written','log_flush_total_bigsec',
                'log_max_modified_age_sync','checkpoint_lsn');

-- 3) redo 的容量配置与当前文件列表
SELECT @@innodb_redo_log_capacity;
SELECT name, total_size, state
  FROM information_schema.INNODB_REDO_LOG_FILES;

-- 4) Buffer Pool 脏页水位（Page Cleaner 的压力）
SELECT POOL_ID, POOL_SIZE, DATABASE_PAGES, MODIFIED_DATABASE_PAGES
  FROM information_schema.INNODB_BUFFER_POOL_STATS;
```

#### 6.3.9 小结（与全文呼应）

- REDO 记录 = **type(MLOG_\*) + (space, page) + offset/len + after image（+MTR 原子边界）**，是“页级改完后样子”的说明书，不是 SQL 重放。
- 一条 DML 的 redo 含多条记录：**改 undo 页 + 改数据页 +（可能）改管理页 + 提交标记**；因此 **undo 也被 redo 保护**。
- redo 靠 **LSN** 定位、由 **checkpoint** 划“恢复起点”；被**崩溃恢复 / checkpoint / 物理备份** 三类场景消费。
- 它与 §3 的 Log Buffer、§4 的日志线程、§5.5 的 UNDO、§6.2 的 2PC、§7 的 binlog 复制、§11 的页结构是同一套机制的六个切面。

### 6.4 深入：Binlog 的物理结构与“复制坐标体系”

§6.3 拆了 **redo（物理日志）**。这一节拆 **binlog（Server 层逻辑日志）**——它是复制的载体、时间点恢复（PITR）的原料，也是 MySQL 与 Oracle 差异最大的一块。理解 binlog 的物理结构，才能理解 §7 的复制到底在“搬动”什么。

#### 6.4.1 binlog 文件在磁盘上的样子

binlog 由一组**编号递增的文件**组成（`binlog.000001`、`binlog.000002`…），每个文件内部是**一串 Event（事件）**：

```
┌───────────────────── 一个 binlog 文件 (binlog.NNNNNN) ──────────────────────┐
│ [Format_Description Event]  ← 文件头：binlog 版本、Server 版本、事件头长度等   │
│ [Previous_GTIDs Event]      ← 本文件之前所有文件的 GTID 集合（GTID 模式下）      │
│ [Query_Event / XID_Event / Rows_Event / Gtid_Event / ...]                   │
│     每个事件 = Event Header (约 19-35 字节) + Event Body + CRC32 校验         │
│ ...（以 Rotate_Event 或 Stop_Event 收尾）                                    │
└─────────────────────────────────────────────────────────────────────────────┘
```

两类**极易混淆**的点先点破：

- **binlog ≠ redo**。binlog 是**逻辑日志**（语句/行语义），redo 是**物理日志**（页字节）。binlog 走 Server 层、跨引擎；redo 只属于 InnoDB。二者由 2PC 绑定一致（§6.2）。
- **binlog 文件内事件大小不一，事件头里带“this event 的长度”字段**——重放工具靠“读头 → 知道长度 → 跳到下一个事件”的方式线性扫描，不需要索引。

#### 6.4.2 Event 类型与“一个事务在 binlog 里长什么样”

以 ROW 格式为例，一个 UPDATE 事务在 binlog 里是这样一串：

```
GTID_Event          ← 这个事务的全局唯一编号（e.g. 3E11-...-12）
Query_Event("BEGIN")← 事务开始（row 格式下实际 DML 是行事件）
  ┌ Table_map_Event  ← “接下来涉及哪张表、各列的类型”（只记一次）
  ├ Rows_Event       ← 行变更，ROW 格式下记的是：
  │     UPDATE_ROWS: 每个“改前整行(before image) + 改后整行(after image)”
  │     WRITE_ROWS: 新行；DELETE_ROWS: 被删整行
  └ ...（同一事务改多张表会有多组 Table_map + Rows）
XID_Event           ← 事务提交（配合 2PC，在 redo 里也写 commit 标记）
```

- **STATEMENT / MIXED 格式**则把 DML 记为 SQL 语句（Query_Event 塞 SQL 文本），省空间但依赖“可确定性重放”（`NOW()`/`UUID()` 会附加设置保证结果一致）。**ROW 是默认且最安全**，可以精确地在从库产生同样的行变化，也支撑“只重放指定行”的应急修数。
- **表结构变化（DDL）**一行搞定：DDL 本身记为 Query_Event（`ALTER TABLE ...` 文本），且 DDL 是“**自动提交**”的，重放时按当前表结构语义执行——这也是“**从库 DDL 慢会卡复制**”的根因。

#### 6.4.3 坐标体系（重点）：binlog 里用什么给“事务”定位

MySQL 给复制/恢复提供了**两套互相映射的坐标**：

**A. (file, position) —— 物理坐标：在 binlog.N 文件的第几个字节**

```
mysql> SHOW BINLOG EVENTS IN 'binlog.000004' FROM 1234 LIMIT 5;
mysql> SHOW MASTER STATUS;   -- 当前写到的 (file, position)
```

- 每个事件在文件里有一个字节偏移（position），传统复制用 `(MASTER_LOG_FILE, MASTER_LOG_POS)` 对定位“从哪里接着搬”。
- 缺点：**文件改名/轮转后坐标失效**；每个从库要各自记；重建从库要重新对位。

**B. GTID（Global Transaction Identifier）—— 逻辑坐标：每个事务一个“身份证号”**

```
GTID = server_uuid : transaction_id
例：  3E11fa47-71ca-11e1-9e33-c80aa9429562:23
```

- `server_uuid` 是实例的身份（首次启动生成于 `datadir/auto.cnf`），`transaction_id` 是该实例内单调递增的序号。
- **GTID 让“这个事务在这个集群里被执行过没有”变成全局可判定**：从库申请 binlog 时直接说“我欠哪些 GTID”（`Retrieved_Gtid_Set` / `Executed_Gtid_Set`），主库按集合差集补发——这就是“一键换主、故障切换不再对位点”的根基。
- **GTID 的容器：`mysql.gtid_executed` 表 + binlog 里的 `Previous_GTIDs` 头**，一起构成“全局已执行集合”的记忆（不用每次翻全部 binlog）。
- **与 Oracle 对照**：Oracle 的 redo stream 坐标是 “**SCN（逻辑时间）+ RBA（物理字节）**”，复制的“断点续传”靠 **FAL 按 sequence# 补归档日志**；MySQL 的对应物是 “**GTID（逻辑身份）+ (file,position)（物理坐标）**”，断档靠 **从库按 GTID 集合主动拉缺失**。结构同构、命名不同。

派生坐标速查（对应 Oracle 的 Low/High/Checkpoint RBA 那张表）：

| MySQL 坐标 | 对应 Oracle 概念 | 含义 | 在哪看 |
|---|---|---|---|
| `Master_Log_Pos`（从库视角 Received） | RBA / FAL 点 | 已从主库收到 binlog 的位置 | `SHOW REPLICA STATUS\G` |
| `Exec_Master_Log_Pos` / Retrieved_Gtid_Set | Apply 进度 | 已重放到哪个位置/GTID | `SHOW REPLICA STATUS\G` |
| `binlog.00000N` 文件序号 | log sequence# | 当前文件在第几代 | `SHOW BINARY LOGS` |
| `GTID:transaction_id` | SCN | 全局唯一的事务身份 | `mysql.gtid_executed` |

#### 6.4.4 这些坐标怎么被用于“复制 + PITR + 切换”

- **常规复制**：从库 dump 线程按 (file, pos) 或 GTID 流向主库请求，主库逐事件推送。
- **时间点恢复（PITR）**：全量备份 + `mysqlbinlog --start-position/--stop-position`（或 `--start-datetime`）重放到“指定时间点之前最后一个事务”；GTID 模式下可用 `--exclude-gtids` 跳过误操作事务——这是 MySQL 版的“不完全恢复”。
- **主从切换（failover）**：选“数据最新”的从库提升为主库，其他从库对齐新主库的 GTID 集合继续复制；`FAIL OVER` 时 Oracle 对应的是 Data Guard switchover/failover 的 sequence 补齐机制。
- **延迟从库（delayed replica, `REPLICA_DELAY`）**：人为延迟 N 小时的从库 => 误删表可以从它身上“把那个时间点前的数据导回来”。Oracle 对应 Flashback / RMAN 时间点恢复。

#### 6.4.5 实操：怎么观察 binlog 的结构与坐标

```sql
-- 1) 当前 binlog 文件与 position（“写到哪了”）
SHOW MASTER STATUS;

-- 2) 列出所有 binlog 文件与大小
SHOW BINARY LOGS;

-- 3) 看一段 binlog 事件的明细（含每个事件的字节坐标）
SHOW BINLOG EVENTS IN 'binlog.000004' FROM 4 LIMIT 30;

-- 4) 用 mysqlbinlog 工具解析为可读/可重放 SQL（服务器本地执行）
--    shell> mysqlbinlog --start-datetime='2026-10-17 08:00:00' \
--                       --stop-datetime='2026-10-17 09:00:00' \
--                       binlog.000004 > pitr.sql

-- 5) 当前 GTID 执行集合（从库对齐用的“账本”）
SELECT @@GLOBAL.GTID_EXECUTED, @@GLOBAL.GTID_PURGED;
SHOW REPLICA STATUS\G   -- Retrieved_Gtid_Set / Executed_Gtid_Set
```

小结：**binlog 文件 = Format_Description 头 + GTID 头 + 一串 Event；每个事务在 ROW 格式下是 “GTID_Event → Table_map → Rows(before/after) → XID” 的原子串。坐标体系有 (file,pos) 与 GTID 双轨，GTID 让“全局有无此事务”可判定，从而换主、补拉、PITR 都能自动化。**

---

## 7. 复制、半同步、Group Replication 与 InnoDB Cluster：它们底层用了什么、区别在哪

这一节对应 Oracle 版的 §7（Data Guard 与 True Cache）：讲 MySQL 的“高可用 / 读扩展”家族。它们都建立在你已经学过的 binlog 与 2PC 机制之上。先分别介绍（7.1/7.2），再深入**主库把 binlog 发到从库的同步策略（7.3–7.5）**，最后对比与选型（7.6–7.8）。

### 7.1 异步复制（Asynchronous Replication）—— 解决“读扩展 + 容灾底座”

**底层功能**：**binlog 传输 + relay 重放**。

- 主库把事务提交时写入的 binlog，由 **dump 线程**推给每个从库连接；从库 **IO_THREAD** 接收写入 **relay log**，**applier（SQL_THREAD / 多线程 worker）** 重放到自己的数据页。
- 结果：从库与主库**最终一致**（通常毫秒级，高写压力下会有延迟堆积）。
- **特点**：主库**不等**任何从库确认就交还客户端——**性能零损耗**；代价是“主库挂掉的瞬间，可能有最后几个事务还在路上”（RPO > 0）。
- 配 امام `binlog_format=ROW` + GTID 时，从库还能做**过滤复制**（只复制某几个 database/表），适合“从主库抽一路到分析库”的场景。

### 7.2 半同步复制（Semisynchronous Replication）—— 解决“主从至少一路有账”

**底层功能**：在异步复制的提交路径上加一道**确认回执**。

- 主库提交时，先做 redo prepare + binlog 写盘（§6.2 的 2PC），然后**等至少 1 个（`rpl_semi_sync_master_wait_for_slave_count` 可调）从库**回执“**已收到该事务的 binlog**（已写入 relay log）”才把 commit 完成。
- 结果：**只要不是主从同时挂，数据就有一份落在别的地方**。
- **重要澄清（防误解）**：
  - 半同步**只确认“binlog 已收到”，不保证“已重放”**——所以从库读到的数据**仍可能滞后**（它与 Oracle Data Guard 的“SYNC=已接收 + 实时 Apply”仍然不是一个强度）。
  - 半同步有**降级开关**（`rpl_semi_sync_master_timeout`）：超过时限还等不到回执就**退化成异步**继续提交（保可用性），之后一旦从库追上还会自动升级回半同步。
- **与 Oracle 的对照**：半同步 ≈ Data Guard 的“**最大可用模式（Maximum Availability）**”——健康时等确认、故障时临时降级、恢复后自动回到强主从；而不是“最大保护”（那个靠 Group Replication 才像）。

### 7.3 Group Replication（组复制，MySQL 5.7+ 官方原生）—— 解决“多节点数据零分叉”

**底层功能**：**基于 Paxos 的成员管理 + 基于认证（certification）的并发写冲突淘汰 + 组通信（XCom）**。这是 MySQL 从“复制”跨进“分布式一致性”的关键一步。

- **组通信层（XCom，Paxos 实现）**：所有节点组成一个组（建议 3~9 节点为奇数），写请求先在组内**以 Paxos 广播并达成多数派同意**，之后才在本地 apply。
- **认证阶段（Certification）**：每个写事务携带“**我读过的行版本（write set）**”，广播给全组**并行认证**——如果与别的事务**写同一行**且**提交顺序有争议** → 本地回滚，客户端收到错误由应用重试。
- **两种模式**：
  - **单主模式（默认，single-primary）**：组内选主（primary），只有主接受写；主挂了组**自动选举**新主（比 MHA 快、比半同步一致的“ morally 同步”）。
  - **多主模式（multi-primary）**：任意节点皆可写；写冲突靠认证检测并 ROLLBACK（所以必要求应用能处理写冲突重试）。
- **与 Oracle 对照**：Group Replication 更像“**MySQL 版的 RAC（写也可以多节点）+ 更强的容灾保证（多数派）**”，但它**不共享存储、不共享内存缓存**，靠“**认证 + 多数派**”来保证一致性——和 Oracle RAC 的 Cache Fusion 完全是两种思路。
- **约束**：**必须主键**（无主键表写冲突判定会失败）、最好用 ROW 格式 binlog、GTID 必须、对事务大小有上限（`group_replication_transaction_size_limit`）。

### 7.4 主库的同步策略：三个可调“旋钮”

无论是异步、半同步还是 Group Replication，**同步的单位都是 binlog 事务事件**；差异来自三个可以独立调整的维度（与 Oracle 的“谁发/SYNC-ASYNC/AFFIRM”完全互映）：

**旋钮一：谁发（binlog 事件从哪送出）**

| 方式 | 谁把 binlog 发给从库 | 特点 |
|------|-------------------|------|
| **dump 线程实时推** | 主库为每个从库连接开一条 dump 线程实时 sendfile | 滞后最小（近实时）；**半同步/GR 也走这条路** |
| **异步：不等任何人** | 从库 IO_THREAD 主动拉、dump 只管发 | 主库性能零影响（默认） |

**旋钮二：要不要等确认（同步 / 半同步 / 异步）**

- **异步（默认）**：主库不等任何人，binlog 后台发。QPS 最高，但主库挂掉可能丢最后一截。
- **半同步**：主库**等至少 1 个从库“binlog 落 relay log”**的回执。多了一个网络往返（≈2×RTT），换来“副本至少有账”。
- **Group Replication（“近同步”）**：主库**等多数派节点把事务广播复制完成**（还没重放也算数，但要“多数派收到”），保证不会分叉。

**旋钮三：确认到哪一步算数（对齐 Oracle 的 AFFIRM/NOAFFIRM）**

- **`sync_binlog=1` + `innodb_flush_log_at_trx_commit=1`（“双 1”，即持久化到磁盘）**：binlog 与 redo 都是 **fsync 到磁盘**才算提交成功——最稳，主机崩溃也不丢。
- **`sync_binlog=100`/`innodb_flush_log_at_trx_commit=2`**：写 **OS 页缓存**就返回，由 OS 异步刷盘——更快，但**整机断电有丢失窗口**（对应 Oracle 侧“NOAFFIRM：落到内存就算数”的精神）。

**缺口补齐（对应 Oracle 的 FAL）**：从库断线或丢一段 binlog，靠 **GTID 集合差集**自动向主库补拉；若主库上那段 binlog 已被清理，只能**用备份重建从库**（或用 `mysqldump`/Clone Plugin 搬数据）。**没有归档日志那种“留底待补”概念——binlog 是有保留期（`binlog_expire_logs_seconds`）的，过期就物理删除**，这一点和 Oracle 把 redo 归档成永不删除的 archive log 不同。

> 记忆法：**“谁发”（dump 实时推）+ “等多个”（异步/半同步/GR 多数派）+ “等哪一步算数”（OS cache / fsync 落盘），三者组合决定 RPO 与主库提交延迟。**

### 7.5 InnoDB Cluster / ClusterSet：Oracle 家族产品的对应物

上面 7.1–7.3 是“机制”；这一节是“产品/方案”（对应 Oracle 版 §7 的 Data Guard / True Cache 产品段）：

| 产品/方案 | 组成 | 对应 Oracle 的什么 |
|------|------|-------------------|
| **InnoDB Cluster（MySQL Shell）** | Group Replication + MySQL Router + Shell 管理 | “一主两从自动选主”的官方打包方案，≈ Oracle RAC/Fast-Start Failover 的运维化 |
| **MySQL Router** | 中间件，感知 primary 并把写路由到主、读转发到从 | ≈ Oracle Client 级 FAN/TAC / Service 的路由 |
| **InnoDB ReplicaSet** | 传统异步/半同步主从 + Shell 管理 | ≈ Data Guard（broker 化的普通主从） |
| **InnoDB ClusterSet** | 一个 InnoDB Cluster 另�一个 standby cluster 异步同步（跨地域容灾） | ≈ “本地高可用（Cluster）+ 异地容灾（ReplicaSet）”双层的官方形态 |
| **Group Replication 自身** | 机制层 | 机制层对应（多数派协议），≈ Oracle 里没有的“集群一致性协议” |
| **Clone Plugin（8.0.17+）** | 一键把主库数据拷到新从库（物理克隆） | ≈ Production Clone / Data Guard 的“用主库 seed 建备库” |

### 7.6 对比表：异步 vs 半同步 vs Group Replication

| 维度 | 异步复制 | 半同步 | Group Replication（单主） |
|------|---------------------|-----------|--------------------------|
| 主库提交要不要等 | **不等** | 等 ≥1 从库“收到 binlog” | 等 **多数派节点**“收到并认证该事务” |
| 数据丢失风险（RPO） | 秒级（事务未到从库就挂） | 主从**同时挂**才可能丢 | **不会分叉/丢失**（多数派协议保证） |
| 一致性 | 从库可能滞后、读旧数据 | 从库可能滞后、读旧数据 | **强一致**（认证后的提交顺序全组一致） |
| 性能影响 | 无 | 每事务多 1 个网络往返 | 高（每事务一个组内广播 + 认证） |
| 主库挂掉之后 | 需要外部工具切主（MHA/Orchestrator） | 同上 + 半 "__由从库追平" | **自动选举新主**（秒~级） |
| 写冲突 | 无检测（单主写） | 无检测（单主写） | **行级认证检测**，冲突事务自动回滚 |
| 复用 Oracle 概念 | Maximum Performance | Maximum Availability | （≈）多节点么 without shared storage的一致集群 |
| 典型场景 | 读扩展/容灾、可容忍秒延迟 | 主流企业生产，低 RPO | 金融/关键业务，自动 failover + 强一致 |

### 7.7 优缺点

**异步复制的优点**：主库性能零损耗；部署最简单；读扩展天然（N 个从库分担读）；跨机房容灾免费。
**异步复制的缺点**：主库挂掉丢最近几个事务；读写分离读到旧数据；故障切换要外部工具配合。

**半同步的优点**：比异步安全一档（副本至少有账）；对应用透明。
**半同步的缺点**：提交多一个网络往返；超时降级后静默变成异步（**要监控**）；**确认的是“收到”不是“重放”**，语义要搞清。

**Group Replication 的优点**：真正强一致 + 自动选主 + 行冲突检测；解决“异步副本分叉最坏情形”。
**Group Replication 的缺点**：性能损耗最高；强制主键/GTID/ROW；事务大小受限；多主模式会把冲突处理责任推回应用。

### 7.8 使用场景怎么选

- **读多写少、可以容忍从库延迟、要求最简单** → **异步复制**（加从库做读写分离 + 报表）。
- **不能容忍“主库挂了就丢事务”，但能接受小延迟** → **半同步复制**。
- **金融/关键业务，要求零丢失、自动 failover、防脑裂** → **Group Replication 单主模式（搬 InnoDB Cluster）**。
- **本地高可用 + 异地容灾** → **InnoDB Cluster + ClusterSet**。
- **想给中间层拿个“低延迟查询缓存”**（对应 Oracle True Cache 的定位）—— MySQL 世界里**没有原生 True Cache 对应物**，一般靠“**只读从库 + 应用侧缓存**”实现，InnoDB 没有内存版“只读缓存副本”。
- 简单判断：**异步是“免费送保险”，半同步是“花钱多买一份”，Group Replication 是“买整份全员赔付”**。

---

## 8. Buffer Pool 为什么“不给力” + 日常写 SQL 的正确姿势

针对 Oracle 版 §8 的“Shared Pool 满了”主题，MySQL 的对应角色有两个：**Buffer Pool（数据页缓存/效率担当）** 与 **Server 层解析与计划（预编译语句/PS 共享）**。日常最常见的“不给力”不是“内存不够”，而是**命中率掉坑 + 全表扫描冲垮缓存**。先讲 Buffer Pool，再讲 SQL 规范。

### 8.1 Buffer Pool 的 LRCharacteristic：LRU 的“分代改造”

InnoDB Buffer Pool 默认按 LRU 管理页，但**朴素 LRU 会被一次全表扫描打爆**（扫描读入的页把热点页全部挤出去）。所以 InnoDB 把链表**分两段**（5/8 是 young 尾，头是 young）：新页先进 **old 区**（`innodb_old_blocks_pct=37`，即 37% 位置），在 old 区**停留超过 `innodb_old_blocks_time`（默认 1000ms）**才升入 young 区：

- **一次性扫描（大报表/全表扫）**：页进 old 区后 1 秒内就被淘汰，**不污染 young 区**。
- **真的热页**：被反复访问，从 old 区挺进而进 young 区，留得住。
- 这与 Oracle 触碰计数的“低触点防污染”设计（touch count）同构，但实现了**两个明确参数**可直接调节。

### 8.2 “Buffer Pool 不给力” 的典型原因与排查

| 现象 | 根因 | 缓解 |
|------|------|------|
| 命中率低、IOPS 高 | Pool 太小，工作集装不下 | 调大 `innodb_buffer_pool_size`（专用服务器推荐 50~75% 内存），先量 working set |
| 命中率低但内存够 | **一次大查询全表扫**冲掉了 old 区 | 拆分查询、加索引；调大/调小 `innodb_old_blocks_time` 实验 |
| 脏页堆积、写抖动 | `innodb_max_dirty_pages_pct` 太高、check point 来不及推进 | 调低脏页水位、调大 `innodb_redo_log_capacity` 让日志撑得久一些（flush 更从容） |
| `SHOW ENGINE INNODB STATUS` 的“waiting for”刷屏 | 刷盘跟不上写入 | 加 IOPS/吞吐（NVMe）、增加 `innodb_page_cleaners`、开 Doublewrite 分盘 |
| 大量“临时文件/磁盘临时表” | 排序/分组的会话内存不够（`sort_buffer_size`） | 调大参数或减少“大 Group By/Order By” 数量（优化 SQL 更根本） |

### 8.3 日常写 SQL 应该怎么写（✅ 正确姿势）

| 做法 | 说明 |
|------|------|
| ✅ **用预编译语句 / 绑定变量** | `UPDATE orders SET status=? WHERE id=?`——语义/计划依 Server 层 HEAD 复用，防注入+减少硬解析 |
| ✅ **避免对列包函数** | `WHERE DATE(created)='2026-10-17'` 会**让索引失效** → 改成“`created >= x AND created < y`” 范围条件 |
| ✅ **避免隐式类型转换** | 列是 VARCHAR 却传数字，走不了索引（反之 CHAR 列传 int 可能更糟） |
| ✅ **别用 `SELECT *`** | 减少网络/Memory；利于“覆盖索引”（索引存了所需列则不需回表） |
| ✅ **批量写入** | `INSERT ... VALUES (...),(...),...` 一次提交，省 redo/binlog 封装开销 |
| ✅ **大事务拆小** | 一个 UPDATE 改 100 万行的“大事务”会长期持盖锁 + 膨胀 undo；改成分批提交 |
| ✅ **JOIN 洗牌看执行计划** | 小表驱动大表；关心 `Extra: Using filesort/Using temporary` 这类信号 |
| ✅ **`EXPLAIN`/`EXPLAIN ANALYZE` 常态化** | 上线前看计划（8.0.18+ 有真实执行数据版本） |

### 8.4 不应该写成什么样子（❌ 禁忌）

- ❌ **拼接 SQL 字符串**（防注入也是硬解析炸弹）。
- ❌ **WHERE 列上套函数/运算**：索引失效 → 全表扫，还可能拖垮 Buffer Pool。
- ❌ **`SELECT ... without LIMIT` 的巨量返回**：net_buffer/o_buffer 被冲爆，应用也会 OOM。
- ❌ **把大 TEXT/JSON 塞主表**：行溢出到 off-page 巨增 I/O；要拆“主表+详情表”。
- ❌ **长事务开着不提交**：undo 链不能 purge、锁不释放、MVCC 对后来的事务不友好（§5.5.6）。
- ❌ **ORM 框架默认 `SELECT *` 全表刷 Buffer Pool**：热点页被一次性填出去，命中率跌。
- ❌ **滥用 `SELECT ... FOR UPDATE`**：升级成当前读＋行锁＋间隙锁；能不加锁就别加（把并发还回来）。

### 8.5 已经“不给力”了怎么应急排查

```sql
-- 1) Buffer Pool 命中率、脏页等待（InnoDB 自己的计分板）
SHOW ENGINE INNODB STATUS\G    -- 看 BUFFER POOL AND MEMORY 段 / 等待事件
SELECT POOL_ID, hit_rate, pages_made_young, pages_not_made_young, modified_pages
  FROM information_schema.INNODB_BUFFER_POOL_STATS;

-- 2) 系统层最大压力来源（执行耗时/扫描行数最多的语句）
SELECT DIGEST_TEXT, COUNT_STAR, SUM_TIMER_WAIT/1e12 AS total_sec,
       SUM_ROWS_EXAMINED/SUM_ROWS_SENT AS examine_return_ratio
  FROM performance_schema.events_statements_summary_by_digest
 ORDER BY SUM_TIMER_WAIT DESC LIMIT 10;

-- 3) Buffer Pool 具体的页、谁最热 / LRU 分布（定位全表扫冲洗）
SELECT TABLE_NAME, INDEX_NAME, COUNT(*) AS pages, SUM(COMPRESSED) AS comp_pages
  FROM information_schema.INNODB_BUFFER_PAGE
 GROUP BY TABLE_NAME, INDEX_NAME ORDER BY pages DESC LIMIT 15;

-- 4) 临时表溢出情况（“内存不够用”的一种）
SELECT variable_value
  FROM performance_schema.global_status
 WHERE variable_name IN ('Created_tmp_disk_tables','Created_tmp_tables');

-- 5) 应急手段治标：预热/强制驱逐（会瞬间丢所有缓存页，生产慎用）
--    shell:  mysql> SET GLOBAL innodb_buffer_pool_dump_now=ON;  -- 先把页列表
--            mysql> SET GLOBAL innodb_buffer_pool_load_now=ON;  -- 再预热回来
```

**根本解决永远是：先用 P_S 找到真凶 SQL，改 SQL/索引/数据结构，而不是一味调参。**

---

## 9. 整体串讲：把上面所有东西连成一张全景图

把全文浓缩成“一个请求的一生”，你就拥有了对 MySQL 的整体心智模型：

```
1. 客户端连上来 → 监听器分配一条【服务线程】，分配会话私有内存（sort/join buffer）。
2. Server 层：词法/语法/语义解析 → 优化器产生计划 → 执行器调 InnoDB 接口。
3. 执行时去【Buffer Pool】取 16KB 数据页（没有就从 .ibd 物理读入）。
4. 用【trx_id + ReadView + undo 版本链】做一致性读（快照读），
   保证“读不阻塞写、写不阻塞读”，绝无脏读。
5. 修改在内存里进行：① 记【UNDO 前像】② 改数据页 ③ 写【REDO 物理记录】到 Log Buffer
   ④ 同步生成【binlog 事件】到 Binlog Cache ⑤ 加行锁（RR 下还有间隙锁）。
6. 提交走【两阶段提交】：redo prepare(fsync) → binlog 写盘 → redo commit 标记。
7. 【Page Cleaner】之后异步把脏页写回数据文件（经 Doublewrite 防断页）；
   checkpoint 相应推进；【Purge】回收旧版本 undo。
8. 主库的【dump 线程】把 binlog 推给从库【IO_THREAD → relay log → applier】，
   实现读扩展（异步）或更安全的半同步 / Group Replication 多数派同步。
9. 全程【Master Thread / 双缓冲（change buffer）】给性能兜底；
   崩溃恢复“redo 前滚 + undo 回滚 + 2PC 对齐”一套走完。
```

**一句话设计哲学**：MySQL（InnoDB）用“**内存缓存 + redo 顺序写保持久 + undo 版本链支持回滚与多版本 + binlog 承担复制与时间点恢复 + 2PC 把两者绑定**”这套组合，把“随机写盘”和“并发一致性”这两件最难的事解耦，再用后台线程异步收尾——与 Oracle 相比，**多了一层“逻辑日志（binlog）”与两阶段提交，换来更轻量的复制与更大胆的读写分离生态**。

作为初学者，你只要记住五句话就抓住了主干：
1. **mysqld 是一个进程管所有库，database 只是目录命名空间。**
2. **Buffer Pool 是公共黑板，每线程的 sort/join buffer 是你自己的草稿纸。**
3. **redo 保持久（先写日志才允许改数据页），Page Cleaner 懒落盘，Doublewrite 防半页。**
4. **快照读靠 trx_id + ReadView + undo 链，当前读还是老老实实加锁。**
5. **SQL 一定要用预编译/绑定变量 + 覆盖索引，否则 Buffer Pool 和 Server 层都会被冲垮。**

---

## 10. 数据存储层次：表空间 / 段 / 区 / 页（逻辑存储结构）

> 镜像 Oracle 版 §10：InnoDB 的数据存储也有一套“**表空间 → 段 → 区 → 页**”逻辑结构，“行”坐在页里。与 Oracle 的同名四层高度相似，但命名和默认参数不同——**页（Page）= InnoDB 的 I/O 单位 16KB**，对应 Oracle 的 Block 8KB。

### 10.1 先给一张“从大到小”的全景图

```
mysqld 实例（§2）           ← 管理着所有表空间（物理文件都在 datadir）
 └─ Tablespace 表空间        ← 逻辑“空间容器”（独立表空间 xxx.ibd / 系统表空间 ibdata1）
      └─ 由 文件（.ibd）承载  ← 物理映射（innodb_file_per_table=ON 时一表一文件）
           └─ Segment 段     ← 一个对象一个段（一张表 = 1 聚簇索引段 + N 个二级索引段）
                └─ Extent 区  ← 一组“连续的页”（64 页 × 16KB = 1MB 常见）
                     └─ Page 页 ← 16KB，I/O 最小单位（内部结构见第 11 节）
                          └─ Row 行 ← 一条记录（聚簇索引叶子页里按主键序排列）
                               └─ Column 列
```

一句话记忆：**“实例里有空间，空间里装段，段里分了区，区里排满页，页里塞着行。”**

### 10.2 第 1 层：表空间（Tablespace）—— 逻辑空间的“容器”

**（1）它里面包含什么 / 是什么**
- 逻辑上：数据库里最大的空间单位。**InnoDB 默认有**：系统表空间 `ibdata1`（数据字典外的公共结构）、undo 表空间 `innodb_undo_001/002`、临时表空间 `ibtmp1`；业务表默认各自独立表空间 `mydb/t.ibd`。
- 物理上：一个表空间对应一个（或一对，双写/临时除外）`.ibd` 文件。
- 关键属性：文件大小是否自动增长（`AUTOEXTEND`）、页大小（`innodb_page_size`）、加密/压缩属性。

**（2）类型分类（重点）**
- **按内容分**：`system`（ibdata1，装数据字典/Change Buffer/双写等）、`file-per-table`（业务表现代默认）、`general`（`CREATE TABLESPACE ts ADD DATAFILE ...` 自建共享）、`undo`、`temporary`。
- **按文件组织分**：独立表空间（`innodb_file_per_table=ON`，默认）、共享系统表空间。
- 按压缩形态：常规、`ROW_FORMAT=COMPRESSED`、透明页压缩（`COMPRESSION='zlib|lz4'`）。

**（3）专项：UNDO 表空间**
- 定位：专放 undo 页（§5.5），`innodb_undo_001/002` 两个、可 `TRUNCATE` 自动收缩。
- 参数：`innodb_undo_tablespaces`（8.0.14+ 固定 2）、`innodb_max_undo_log_size`、`innodb_undo_log_truncate`。
- 与 redo 的协同：undo 页的改动在 redo 里（§6.3），所以有 redo 兜底，undo 不怕崩溃。

**（4）作用**
- **空间/IO 隔离**：独立表空间让“删表/搬表/监控单表”变得容易（`TRUNCATE`、`innodb_file_per_table` 的直接后果）。
- **备份/恢复**：file-per-table 支持“只搬某张表”（transportable tablespace，`FLUSH TABLES t FOR EXPORT` → 拷 `.ibd` + `.cfg` → `ALTER TABLE ... DISCARD/IMPORT TABLESPACE`）。
- **与 database（schema）正交**：表空间是“存储层”、database 是“命名空间层”，一个 database 的表可以分布在多个表空间（即多个 .ibd），一个表空间也可能放多张表（general/system）。

> 关键关系：**8.0 默认 `innodb_file_per_table=ON`：一张表一个 .ibd；`OFF` 时所有表挤 ibdata1，删表不往外释放磁盘空间（文件不收缩）** —— 历史上不少人把 ibdata1 撑爆，这是“为什么现代默认 ON”的原因。

### 10.3 第 2 层：段（Segment）—— “一个索引一个段”

**（1）它里面包含什么**
- InnoDB 的“段”跟随**B+ 树索引**存在：**一张聚簇索引表 = 聚簇索引段（叶子页存整行）；每个二级索引 =独立的二级索引段（叶子页存“索引列+主键值”）**。
- 段头信息在页类型 `FSP_HDR` / `XDES` / `IBUF_BITMAP` 之类的管理页里；叶子段 + 非叶子段分开（`leaf / non-leaf` segment），加上页类型 `FSEG` 的簿记页。
- 8.0 数据字典（`mysql.ibd`）本身也是一套 InnoDB 表空间中的表。

**（2）类型分类（重点）**
| 段类型 | 说明 |
|---|---|
| **聚簇索引段（表段）** | 一张表的主索引（行数据真身所在），叶子页存整行 |
| **二级索引段** | 每个二级索引一段；叶子页存“索引列+PK” |
| **undo 段** | 存 undo 记录，支撑回滚/一致性读（§5.5） |
| **临时段** | 排序/临时表使用（`ibtmp1` 的页） |
| **Change Buffer 相关** | 8.4 默认关闭时的遗留结构 |
| **LOB 段** | TEXT/BLOB 行溢出页（行的“off-page”部分）；每个大字段列可能多页 |

**（3）作用**
- **对象级空间边界**：B+ 树按段申请页/区；删除时（如 DROP/TRUNCATE 表）按段释放。
- **澄清“1 张表 ≠ 1 个段”**：一张带 3 个二级索引 + 2 个大字段的表 = 聚簇段 + 3 个二级段 + LOB 段，可能十几段。
- **增长**：B+ 树叶子页放不下 → 段申请新页/新区，同时可能**页分裂**（分裂和其 redo 见 §6.3）。

> **Undo 段**最特殊：它在 undo 表空间里，是“版本链的家”（§5.5、§10.2 专项）。这与 Oracle “回滚段是段类型之一”完全同构（Oracle 在 `TYPE2 UNDO`，InnoDB 在 fixture 的 undo 表空间里）。

### 10.4 第 3 层：区（Extent）—— “连续的页组”

**（1）它里面包含什么**
- 一个区 = **64 个连续的 16KB 页 = 1MB**。（注意：若 `innodb_page_size=4K/8K/32K/64K`，一区的页数会变，但**区的大小始终是 1MB**。）

**（2）类型分类**
- **空闲区（FREE）**：不属于任何段。
- **该段满区（FULL_FRAG）**：属于某段、页全满。
- **非满区（NOT_FULL / fragmented）**：段还能用其中若干页。
- **区级管理**：“碎片区/ 区内部分配”由**XDES 页（Extent Descriptor）**记录——这对应 Oracle LMT 的“位图管理块”。

**（3）作用**
- **降低分配开销**：B+ 树不是每插一行都申请页，而是按“区/页批次”申请。
- **物理连续性**：区内 64 页连续，全表扫描的顺序读 I/O 友好（与 Oracle 区内连续同构）。
- **空间回收**：`DROP/TRUNCATE` 表、删除大量数据后，按区级回收空闲页/区。

> 易混点：**“区”是空间分配单位，“页”是 I/O 单位**——与 Oracle “区=分配单位、块=I/O 单位” 完全一格。**“区内页连续，段内区零散”**：InnoDB 也存在这个现象，只不过 B+ 树页之间用链表串逻辑顺序，物理上可以散。

### 10.5 第 4 层：页（Page）—— “I/O 最小单位”，行坐在页里

页是 InnoDB **读写缓存与磁盘 I/O 的最小单位**，默认 **16KB**（`innodb_page_size` 可 4K/8K/16K/32K/64K；桶内含管理页/叶子页/undo 页等类型）。内部结构见第 11 节。

**（1）它里面包含什么**
- 页头（页类型/所属索引/LSN/校验值）、页目录（记录定位的二分查找目录）、行记录区（聚簇索引页存整行，二级索引页存“索引列+PK”）、空闲空间、页尾校验。
- 行格式决定列在页内的排布（Compact/Redundant/Dynamic/Compressed，8.0 默认 DYNAMIC）。

**（2）类型分类（不同 typ 的页分工）**
| 页类型 | 用途 |
|---|---|
| **INDEX 页** | B+ 树的叶子/非叶子页，真正存表/索引数据 |
| **UNDO LOG 页** | 存 undo 记录（§5.5） |
| **系统管理页（FSP_HDR/XDES/IBUF_BITMAP）** | 表空间/段/区的簿记 |
| **TRX_SYS / RSEG HDR** | 事务系统/回滚段头页 |
| **BLOB 页** | 大字段的 off-page 内容 |
| **临时页** | 临时表空间的页 |

**（3）作用**
- **I/O 单位**：Buffer Pool 缓存的就是页（§3）；**页大小影响 I/O 次数、页分裂频率、缓存效率权衡**（常见 16K）。
- **行的容器**：行坐在页里，行头藏了 `DB_TRX_ID/DB_ROLL_PTR/DB_ROW_ID`（§11.6 详拆）。
- **页分裂/合并（对应 Oracle 的行迁移&PCTFREE）**：插入导致页满 → 页分裂（一半记录搬新页，父子页更新）；大量删除 → 页变空 → **合并或标记免费**。这是一把双刃剑：“**多的填充因子可以减少分裂**”，InnoDB 没有像 Oracle PCTFREE 那样 per-table 参数，但可以通过**页大小与逐渐填充（bulk insert 排序** *+ “增加填充” `innodb_fill_factor`）来整形。

### 10.6 四者之间的关系 + 物理对照

**（1）层级关系全景（谁包含谁）**

```
mysqld 实例（§2）
 └─ Tablespace 表空间（.ibd，一表一文件 or 共享 ibdata1）
      └─ Segment 段（一个索引/undo → 一组区）
           └─ Extent 区（64 页 = 1MB，区内页物理连续）
                └─ Page 页（16KB，I/O 最小单位）
                     └─ Row 行（聚簇索引页里的整行，或二级索引页里的“索引列+PK”）
```

**（2）五个必须记住的“关系”澄清**
- **表空间 = 逻辑，文件 = 物理承载**：file-per-table 时 1 表空间 : 1 文件。
- **一个索引 = 一个段**：不是“一张表一个段”——N 个二级索引就有 N+1 个段。
- **“区内页连续，段内区零散”**：与 Oracle 同构。
- **区是“分配单位”，页是“I/O 单位”**。
- **行是页的子结构**，行头三列（DB_TRX_ID/DB_ROLL_PTR/DB_ROW_ID）是 MVCC 的载体（§11.6）。

**（3）逻辑 ↔ 物理对照表**

| 逻辑结构 | 物理结构 |
|---|---|
| 表空间 Tablespace | `.ibd` 文件（或 ibdata1 共享） |
| 段 Segment | （不单独对应文件，散落在所属表空间内） |
| 区 Extent | 文件里连续的 1MB（64 页×16KB） |
| 页 Page（16KB） | OS 块/扇区的上层封装，I/O 单位 |
| 行 Row | 页内偏移（页目录定位） |

**（4）一条记录的落点路径**：`database 对象 → 段 → 某个区 → 某个页 → 页内某行`。被修改时走第 6 节（服务线程改 Buffer Pool 页 + 写 redo + 写 binlog）；被查询时走第 5 节（trx_id/ReadView 一致性读）。

### 10.7 和本文其他章节的呼应

- **第 2 节**：表空间是“存储层”，database 是“命名空间层”，正交。
- **第 3 节** Buffer Pool：缓存里放的就是这里的**最底层 Page（16KB）**。
- **第 5 节** 一致性读：行头三列（DB_TRX_ID/DB_ROLL_PTR）+ undo 链是“行 → 事务 → undo”的指针桥（§11.6 细拆）。
- **第 6 节** DML 旅程：页的修改经 redo 保护、事务经 2PC 与 binlog 一致。

### 10.8 常用排查 SQL（看清“段→区→页”的占用）

```sql
-- 1) 各表空间大小（.ibd 文件粒度）
SELECT NAME, FILE_SIZE/1024/1024 AS allocated_mb, ALLOCATED/1024/1024 AS used_mb
  FROM information_schema.INNODB_TABLESPACES ORDER BY FILE_SIZE DESC LIMIT 15;

-- 2) 某数据库下每张表占多大（含索引）
SELECT NAME, FILE_SIZE/1024/1024 AS mb
  FROM information_schema.INNODB_TABLESPACES
 WHERE NAME LIKE 'mydb/%' ORDER BY FILE_SIZE DESC;

-- 3) 表的索引各占多少页/多少空间
SELECT index_name, stat_name, stat_value/1024/1024 AS mb
  FROM mysql.innodb_index_stats
 WHERE table_name='orders' AND stat_name IN ('size')

-- 4) Buffer Pool 里哪些表/索引占页最多（内存工作集分布）
SELECT TABLE_NAME, INDEX_NAME, COUNT(*) AS pages
  FROM information_schema.INNODB_BUFFER_PAGE
 GROUP BY TABLE_NAME, INDEX_NAME ORDER BY pages DESC LIMIT 15;
```

### 10.9 速查总结表：四层逻辑结构一图对照

| 层级 | 一句话定义 | 管理/分配单位 | 物理对应 | 常见类型 | 核心作用 | 主要观察视图 |
|---|---|---|---|---|---|---|
| **表空间 Tablespace** | 空间分配的最大逻辑容器 | DBA/自动 | `xxx.ibd`（或 ibdata1） | system / file-per-table / general / **undo** / temp | 空间与 IO 隔离、搬表/删表方便 | `INNODB_TABLESPACES` |
| **段 Segment** | 一个索引拥有的一组区 | 对象级 | 散落在所属表空间内 | 聚簇段 / 二级索引段 / **undo 段** / temp / LOB | 对象级空间边界，B+ 树扩容 | `INNODB_SYS_SEGMENTS` |
| **区 Extent** | 64 个连续页 = 1MB | 空间分配/回收最小单位 | 文件中连续的一段页 | FREE / FULL / NOT_FULL | 降低分配开销、顺序 IO 友好 | `INNODB_SYS_DATAFILES` |
| **页 Page** | I/O 最小单位（16KB） | I/O 单位 | OS 块上层封装 | INDEX / **UNDO** / 系统 / BLOB / 临时 | 行容器、缓存与读写最小粒度 | `INNODB_BUFFER_PAGE` |
| （页内）**Row 行** | 真实的一条记录 | — | 页内偏移 | — | 承载业务数据 | 表查询 |

---

## 11. 深入：MySQL 16KB 数据页（InnoDB Index Page）内部结构

> 本节是进阶内容。前面提到“Buffer Pool 里放的是 16KB 数据页”，这一节把“一个 16KB 页内部到底长什么样、页头里记录了哪些信息”彻底拆开。理解了页结构，第 5 节的“一致性读靠 trx_id + ReadView + undo 链”就不再抽象。

### 11.1 先澄清：16KB 是“页大小”，不是“头部大小”

- InnoDB 以**页（Page）**为最小 I/O 单位，大小由 `innodb_page_size` 决定，**默认 16384 字节（16KB）**。
- 一个 16KB 页 = **一小段页头开销（几十~一两百字节）+ 页头 + 行目录 + 大量行数据与空闲空间**。
- 所以“16KB 数据页头部”指**这个 16KB 页的头部（Page Header）**，只占页开头一小部分；主体是行数据，页头存“元数据”。

### 11.2 一个 16KB 页从外到内的分层

```
┌──────────────────────────────────────────────────────────────────┐
│  File Header（38B）  ← 所有页共有的“外头”                          │
│    · 页类型（INDEX/UNDO/…） · 所属 space id+page no               │
│    · 前驱/后继页指针 · 本页 LSN · 校验相关                          │
├──────────────────────────────────────────────────────────────────┤
│  Page Header（56B，INDEX 页专属）                                  │
│    · 堆中记录数 · 垃圾链表 · 最后插入位置 · PAGE_LEVEL（B+树层）       │
│    · 索引 ID · B+ 树“分段”（叶子/非叶子段） 信息                     │
├──────────────────────────────────────────────────────────────────┤
│  Infimum / Supremum（两条虚拟记录，界定页内记录范围）                 │
├──────────────────────────────────────────────────────────────────┤
│  User Records（行记录区，从“下”往上长）                             │
│    · 每条记录：Record Header（变长，含 delete_mark/事务ID/回滚指针）  │
│                + 各列数据（VarLen 长度列表 + 列值）                  │
├──────────────────────────────────────────────────────────────────┤
│  Free Space（空闲空间，可用尽则触发页分裂）                          │
├──────────────────────────────────────────────────────────────────┤
│  Page Directory（页目录，从“上”向下生长——稀疏槽位表，供二分定位）      │
├──────────────────────────────────────────────────────────────────┤
│  File Trailer（8B） ← 页尾校验（与 File Header 的 LSN 对应防断页）    │
└──────────────────────────────────────────────────────────────────┘
```

（**注意与 Oracle 相反的方向感**：Oracle 是“行数据从块底向上长、行目录从顶向下长”；InnoDB 是**行记录从“头”方向顺序追加、Page Directory 从“尾”向上倒着装**，两者只是坐标系的画法不同，思想同构——都是“元数据一头、数据一头、空闲空间在中间、尾上放校验”。）

### 11.3 页头里的信息清单（File Header + Page Header）

| 字段 | 含义 | 用途 |
|------|------|------|
| **FIL_PAGE_TYPE（页类型）** | INDEX / UNDO / FSP_HDR / XDES / BLOB 等 | 解析/校验页合法性 |
| **FIL_PAGE_LSN（本页 LSN）** | 本页最近一次被改动的 LSN | 崩溃恢复判断“这页要不要重放 redo”（对齐 Oracle 块头里的 SCN） |
| **space id + page no** | 页的“门牌号”（表空间号+页号） | 定位、校验 |
| **PAGE_LEVEL / 索引 ID** | 该页在 B+ 树的哪一层、属于哪个索引 | 区分叶子页/非叶子页（叶子存整行，非叶子存“键+子页指针”） |
| **prev/next page** | 同层相邻页指针（双向链表） | B+ 树叶子是**双向链表** → 支撑范围扫描 |
| **PAGE_N_RECS / 垃圾链表** | 行数、被删记录的空闲槽链 | 空间复用 |
| **PAGE_LAST_INSERT / 方向** | 最近插入位置/方向 | 优化顺序插入（减少分裂判断） |

> 重点：**页头的 LSN 就是崩溃恢复的“时钟”**。第 6.3 节讲“checkpoint LSN 是恢复起点”——恢复时就是对每个页面比对：**页头 LSN ≥ redo 记录 LSN → 跳过；页头 LSN < redo LSN → 重放**。这与 Oracle“块头 SCN vs redo SCN 判断是否要重放”完全同构。

### 11.4 行记录（User Record）的头部：MVCC 的三个“隐藏列”（最该记住的部分）

聚簇索引页里的每条行记录，头部除了变长字段长度列表、NULL 标记位之外，还有**三个隐藏列**——它们是 MVCC 的指针骨架：

| 隐藏列 | 含义 |
|---------|------|
| **DB_TRX_ID（6B）** | 最近一次**插入或更新该行**的事务 ID → 与 ReadView 比较（§5.3 的可见性判定） |
| **DB_ROLL_PTR（7B）** | 回滚指针 → **指向该行的上一个版本**（写入 undo log），串成版本链（§5.5.3） |
| **DB_ROW_ID（6B，条件性）** | 若表没有主键、也没有非空 UNIQUE，InnoDB 用它当“暗藏的主键”建聚簇索引 |

**Record Header 里与并发相关的位段**：

```
删标位 delete_mark  ← DELETE 的“两段式删除”第一步：打标记，真删除延迟给 purge（§5.5.4）
记录类型 rec_type   ← 普通行 / B+ 树非叶子项 / 虚拟记录 infimum/supremum
own/heap_no 等位    ← 记录在页内堆位置的定位信息
```

**和一致性读串起来看**（呼应第 5 节）：
```
查询遇到行 R：
  ① 读行头 DB_TRX_ID，与 ReadView 比较（§5.3 的四象限判定）
  ② 不可见 → 沿 DB_ROLL_PTR 找到 undo 里的旧版本，重复 ①
  ③ 直至找到“可见”的那一版，返回
```
所以**行头三列 + undo 链**是“行 → 事务 → undo”的指针桥——它与第 6 节 redo 流程里“记 undo 前像 → 改行头指针”一一对应。

### 11.5 页目录（Page Directory）与行定位

- **Page Directory**：页尾方向的**稀疏槽位表**——每若干条记录一个槽，槽里放“这一组里最大记录的偏移”。查找时**先二分槽位 → 再在槽内顺序扫**（这正是“页内查找低开销”的关键）。
- **B+ 树查找路径**：根页 → 内部页（存“键 + 子页 no”）→ 叶子页（存整行）→ 页目录二分定位 → 返回行。**一次点查通常需要 3~4 次“页读”**（根/内部/叶子常驻 Buffer Pool 后就是内存操作）。
- **行格式影响页内布局**：DYNAMIC（默认）把长字段 off-page 存 BLOB 页；COMPACT/REDUNDANT 是老格式；COMPRESSED 带页压缩。大字段溢出会让“一行占多页”，扫描它要多一次 I/O。

### 11.6 校验与断页保护：File Trailer 与 Doublewrite

- 每个 16KB 页**尾部的 8 字节 File Trailer** 存`记录“本页最高 LSN”的旧值 + chksum`；读页时**先比对尾部与头部的 LSN/校验**，对不上就视为**断页（torn page）**——这与 Oracle 的 Tailchk 块尾校验完全同构。
- **Doublewrite Buffer** 是 InnoDB 解决断页的**写入侧兜底**（Oracle 不需要它，因为 Oracle 块头/块尾校验是由 CKPT+DBWn 保证写块次序 + 恢复机制兜住；InnoDB 页 16KB 大于常见 4KB 原子写单位，才会有断页风险）：
  1. Page Cleaner 要刷脏页时，**先把整页 memcpy 到共享表空间的 doublewrite 区（2×16KB 顺序写）**；
  2. 然后 fsync 这个 doublewrite 区；**再**把脏页写到真正的 .ibd 位置。
  3. 崩溃恢复时若发现某页断页 → 从 doublewrite 区里把完整副本拷回来。
- 8.0.20+ 允许“doublewrite 独立文件”（`#ib_16384_0.dblwr`）；启用**支持原子写的文件系统/硬件**时可以关 Doublewrite 提性能。

### 11.7 怎么实地看一个真实页（实操）

```sql
-- 1) 表的页级别信息：行数/页数/行均长度
SELECT NAME, NUM_ROWS, CLUSTER_INDEX_SIZE AS pages
  FROM information_schema.INNODB_TABLESTATS
 WHERE NAME='mydb/orders';

-- 2) 看每个索引的页数/深度（B+ 树高度估算）
SELECT index_name, stat_name, stat_value
  FROM mysql.innodb_index_stats
 WHERE table_name='orders' AND stat_name IN ('size','n_leaf_pages');

-- 3) 观察一个具体页（服务器本地，调试手段）：
--    shell> 点查特定行后，从 INNODB_BUFFER_PAGE 查它的 (space, page)
SELECT PAGE_NUMBER, PAGE_TYPE, TABLE_NAME, INDEX_NAME
  FROM information_schema.INNODB_BUFFER_PAGE
 WHERE TABLE_NAME='mydb/orders' LIMIT 10;

-- 4) 用 innochecksum 检查 .ibd 文件里的页校验（DBA 工具）
--    shell> innochecksum /var/lib/mysql/mydb/orders.ibd
```

> 提示：Buffer Pool 里还有一份“**缓冲池控制块**（Buffer Control Block，在 Buffer Pool 头部 64 结构数组里）”——它描述的是“该页在内存里的状态（是否脏/pin/LRU 位置）”，**和磁盘页的 Page Header 不是一回事**，别混淆（对应 Oracle 点破 Buffer Header vs Block Header 的那段）。

### 11.8 小结与前后呼应

- 一个 16KB 页 = File Header（类型/LSN/双向指针）+ Page Header（树层/索引/统计）+ 虚拟边界 + 行记录/空闲 + Page Directory + File Trailer。
- **页头 LSN 是恢复时钟，行头三列是 MVCC 指针桥，Trailer+Doublewrite 是写安全兜底**——三者共同支撑第 5 节“读不阻塞写、写不阻塞读”和第 6 节“崩溃不丢数据”。
- 想看真实结构：`INNODB_BUFFER_PAGE` 定位 + `innochecksum`/`ibd2sdi`（8.0 表结构抽取）落地检查。

---

## 12. 参考资料

1. 工作区姊妹篇：[MVCC原理.md](MVCC原理.md) —— MySQL vs Oracle 的 MVCC 逐机制对比（trx_id+ReadView+undo 版本链 vs SCN+UNDO 一致性读、长事务与历史链、purge 与 ORA-01555）。
2. 工作区姊妹篇：[DB事务隔离级别.md](DB事务隔离级别.md) —— InnoDB 四档隔离级别（RU/RC/RR/SERIALIZABLE）与四库对照，幻读与 Next-Key Lock 的深入讲解。
3. MySQL 参考手册（官方文档）—— *InnoDB Memory Architecture*（Buffer Pool/Log Buffer/Change Buffer）、*InnoDB Disk Architecture*（Tablespaces/Doublewrite/redo/undo）、*MVCC and ReadView*、*Locking and Transaction Model*、*Replication / Group Replication*、*InnoDB Cluster*。
4. MySQL 系统数据库 `performance_schema` / `information_schema` —— `INNODB_METRICS`、`INNODB_BUFFER_POOL_STATS`、`INNODB_BUFFER_PAGE`、`INNODB_TRX`、`events_statements_summary_by_digest`、`SHOW ENGINE INNODB STATUS`。
5. MySQL Release Notes（9.x Innovation / 9.7 LTS）—— redo 文件结构（8.0.30+ `#innodb_redo`）、`innodb_redo_log_capacity`、8.4 默认值变更、Group Replication/InnoDB Cluster 演进。
6. Jeremy Cole 博客 —— *B+Tree index structures in InnoDB*、*The physical structure of InnoDB index pages*（页结构与 Page Directory 的经典拆解）。
7. 阿里云/美团技术团队博客 —— InnoDB Buffer Pool LRU 分代、两阶段提交、redo 刷盘策略的工程实践 achtergrond（中文语境最容易读的一手拆解）。









