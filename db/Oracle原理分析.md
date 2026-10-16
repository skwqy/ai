# Oracle 原理分析（面向小白的整体梳理）

> 整理日期：2026-10-16
>
> 适用版本：以 **Oracle Database 23ai / 26ai** 为准（核心原理对 11g/12c/19c/23ai/26ai 通用；26ai 专属能力单独标注）。
>
> 阅读对象：Oracle 初学者。本文用“先建立整体心智模型 → 再拆解每个部件 → 最后串成一条完整请求链路”的方式，把你最常听到的名词（实例、SGA、PGA、LGWR、DBWn、SCN、UNDO、Data Guard、True Cache、Shared Pool）一次性讲清楚。
>
> 文档版本：v0.2（结构调整版）｜上一版存档：[Oracle原理分析_v0.1.md](Oracle原理分析_v0.1.md)
>
> 深入阅读入口：本文第 5 节“读不阻塞写”的底层机制，在姊妹篇 [MVCC原理.md](MVCC原理.md)（Oracle vs MySQL MVCC 逐机制对比）和 [DB事务隔离级别.md](DB事务隔离级别.md)（四库隔离级别实现对照）里有更系统的展开，建议读完本文再深入。

---

## 目录

> 📌 **建议阅读顺序（小白路线）**：`第 2 节（概念）` → `第 6 节（DML 旅程）` → `第 5 节（一致性读 / UNDO）` → `第 3、4 节（内存 / 进程）` → 其余按需查阅。
> 本文是「先建心智模型 → 拆部件 → 串成一条请求链路」的递进结构。所有标 **「深入：」** 的小节是对应主题的专项拆解，可单独查阅，不影响主线阅读。

1. [版本说明：从 23ai 到 26ai](#1-版本说明从-23ai-到-26ai)
2. [核心概念与部署形态：数据库 / 实例 / Schema](#2-核心概念与部署形态数据库-实例-schema)
   - 2.1–2.3 三个核心概念（数据库 / 实例 / Schema）
   - 2.4 三者关系图
   - 2.5 部署形态①：CDB/PDB 容器部署 vs 传统非 CDB
   - 2.6 部署形态②：RAC 多实例集群 vs 单实例
3. [Oracle 的内存结构：SGA 与 PGA](#3-oracle-的内存结构sga-与-pga-里到底放了什么)
4. [Oracle 的后台进程](#4-oracle-的后台进程每个进程是干什么的)
5. [一致性读与多版本（含 §5.5 UNDO 记录专项）](#5-读不阻塞写写不阻塞读oracle-的一致性读与多版本)
6. [一条 DML 语句的完整旅程（含 §6.3 REDO 记录、§6.4 Redo Log 物理结构与坐标）](#6-一条-dml-语句的完整旅程各进程如何配合)
7. [Data Guard 与 True Cache](#7-data-guard-与-true-cache它们底层用了什么区别在哪)
8. [Shared Pool 满了 + 写 SQL 的正确姿势](#8-shared-pool-满了是怎么回事--日常写-sql-的正确姿势)
9. [整体串讲：全景图](#9-整体串讲把上面所有东西连成一张全景图)
10. [数据存储层次：表空间 / 段 / 区 / 块](#10-数据存储层次表空间--段--区--块逻辑存储结构)
11. [深入：Oracle 8KB 数据块内部结构](#11-深入oracle-8kb-数据块data-block内部结构)
12. [参考资料](#12-参考资料)

---

## 1. 版本说明：从 23ai 到 26ai

关于版本，先给一条准确的时间线（本文整理于 2026-10，已按最新事实校正）：

- **Oracle Database 23ai**：2024 年发布，首个 “ai” 命名版本，引入 True Cache、JSON 关系二元性等。
- **Oracle AI Database 26ai**：**2025-10-14 发布**，是**取代 23ai 的新一代长期支持（LTS）版本**；本地 Linux x86-64 的 GA 随 **2026 年 1 月季度更新（版本 23.26.1）** 提供。从 23ai 打 2025-10 的 Release Update 即可过渡到 26ai 的现有特性，**无需传统意义上的大版本升级**。
- 因此本文早前“最新是 23ai、26ai 尚未发布”的说法已过时；现以 **23ai / 26ai 通用原理**为准，遇到 26ai 专属能力（如 True Cache 的 `sync`/`async` 传输配置，见第 7.5 节）会单独标注。

好消息：本文讲的全部核心原理（实例/内存/进程/MVCC/重做日志/Data Guard）**从 11g 到 26ai 几乎不变**，不必担心“版本对不上”。下文以通用原理为主，23ai/26ai 专属特性单独标注。

---

## 2. 核心概念与部署形态：数据库 / 实例 / Schema

本节先讲三个最容易被混的核心概念（数据库 / 实例 / Schema 及其关系图），再展开两种**部署形态**——**多租户容器（CDB/PDB，§2.5）** 与 **RAC 多实例集群（§2.6）**。这两节会反复改写前面那张“实例⇄数据库”关系图，建议先读 §2.1–§2.4 打底。

初学者最容易把这三个词当成一个东西。它们其实是“三个不同层次”的概念。

### 2.1 数据库（Database）—— 落在磁盘上的“物理文件”

Oracle 数据库 = 一组持久化在磁盘上的文件：

- **数据文件（Data Files）**：真正存表、索引数据的地方（.dbf）。数据在“表空间→段→区→块”里的组织方式，见第 10 节。
- **控制文件（Control Files）**：记录数据库的物理结构（数据文件位置、SCN、备份信息），通常多份冗余。
- **重做日志文件（Redo Log Files）**：记录所有“数据变更”，是恢复和容灾的命脉。

数据库是“静态、持久”的，关机了它也还在磁盘上。

### 2.2 实例（Instance）—— 运行中的“内存 + 进程”

实例 = **内存结构（SGA + PGA）+ 后台进程** 的组合。

- 数据库是“数据”，实例是“让数据跑起来的运行时环境”。
- 典型关系：**启动一个实例 → 挂载（mount）一个数据库 → 打开（open）让它提供服务**。
- 你关机重启时，磁盘上的数据库文件没变，但“实例”是被重新创建出来的（内存重新分配、进程重新拉起）。

> 一个形象比喻：**数据库是“书”，实例是“读书的人 + 他的书桌 + 他的笔记本”**。书一直放在书架上（磁盘），每次读它需要有人（实例）把书拿下来、在书桌上摊开、在笔记本上记笔记。

### 2.3 Schema（模式）—— 用户同名的“命名空间”

Schema 不是“数据库”，也不是“实例”，它是一组**数据库对象（表、视图、索引、存储过程…）的集合**，并且**在 Oracle 里 Schema 名字 = 用户名**：

- 你建一个用户 `CREATE USER tom IDENTIFIED BY xxx;`，Oracle 就自动为它建了一个同名的 Schema `tom`。
- `tom` 这个 Schema 里放 `tom` 拥有的表、视图等对象。
- 不同 Schema 里的表可以重名（靠 `schema名.表名` 区分，如 `tom.orders` vs `jerry.orders`）。

### 2.4 三者关系图

```
┌──────────────────────────────────────────────────────────────┐
│                      Oracle 数据库 (Database)                  │
│   数据文件 .dbf  +  控制文件  +  重做日志文件  （都在磁盘上）    │
│                                                               │
│   被一个【实例】打开：                                          │
│   ┌───────────────────────────────────────────────────────┐  │
│   │ 实例 (Instance) = 内存(SGA+PGA) + 后台进程               │  │
│   └───────────────────────────────────────────────────────┘  │
│                                                               │
│   数据库内部（12c+ 多租户）层次：                              │
│   CDB (容器数据库)                                            │
│     ├── PDB1 (可插拔数据库，一个租户)                          │
│     │      ├── Schema: TOM   (表/视图/索引...)                │
│     │      └── Schema: JERRY (表/视图/索引...)               │
│     └── PDB2 ...                                              │
└──────────────────────────────────────────────────────────────┘
```

> 上面这幅“CDB→PDB→Schema”的层次图只是开胃菜。Oracle 现在的部署**默认且强制就是这种容器（CDB/PDB）模式**，它和“传统一个库里用多个 Schema 当租户”到底差在哪、有哪些好处和坑，详见第 2.5 节。

关键区分速记：

| 概念 | 本质 | 是否持久 | 数量关系 |
|------|------|---------|---------|
| Database | 磁盘文件集合 | 是 | 一个实例通常打开一个库；RAC 下多个实例打开同一个库 |
| Instance | 内存+进程（运行时） | 否 | 一个库在单实例下对应一个实例；RAC 下多个实例 |
| Schema | 用户拥有的对象集合 | 是（对象存于数据文件） | 一个库/一个 PDB 里有多个 Schema |

> 与 MySQL 的区别（容易踩坑）：MySQL 里 `database` 和 `schema` 基本是同义词；但 **Oracle 里 `schema ≈ 用户`，`database` 是整个物理库**，三个概念层次完全不同。Spring 等框架里写 `REPEATABLE READ` 在 Oracle 上会因为没有该档位而落到默认行为，原因也在于此。

### 2.5 深入：容器数据库（CDB/PDB）—— Oracle 现在的部署模式 vs 传统非 CDB

上一节图里那句“12c+ 多租户”很多人一晃而过。但**这正是 Oracle 现在（21c/23ai/26ai）的默认且唯一部署形态**，理解它，才能把前面学的实例、Schema、内存、redo/undo 在“多租户”下重新对位。

#### 2.5.1 一句话定位 + 两个常见误解

- **“容器部署模式”指的是 Oracle 的多租户架构（Multitenant）**：一个 **CDB（Container Database，容器数据库）** 里装多个 **PDB（Pluggable Database，可插拔数据库）**。每个 PDB 对应用/客户端看起来就是“一个独立的数据库”。
- **事实校正（很重要）**：**从 Oracle 21c 开始，传统的非 CDB（non-CDB）架构已被官方废弃（desupported）**，多租户 CDB 成为**唯一支持的架构**。也就是说，你现在装 21c/23ai/26ai，DBCA 默认就只能建 CDB；升级到 21c+ 也必须先迁到多租户。它不是“可选风格”，而是“现在的必选项”。
- **歧义澄清（避免跑偏）**：Oracle 说的“容器（Container）”是 **CDB/PDB 数据库内部的多租户逻辑架构**，**不是**“把 Oracle 跑在 Docker / Kubernetes 容器里”。后者是**操作系统级部署形态**，和前者**正交**——两者可以叠加（比如“在一个 K8s 容器里跑一个 CDB”）。如果你问的是 Docker/K8s，那是另一篇，本文聚焦数据库内部的容器架构。

#### 2.5.2 一个 CDB 里到底装了什么

一个 CDB 由若干“容器（container）”组成，每个容器有唯一 ID 和名字：

```
CDB（容器数据库，= 一个实例打开的物理文件集合）
 ├─ CDB$ROOT（根容器，系统容器的一部分）
 │     └─ 存 Oracle 自带元数据（数据字典骨架）+ common user（SYS 等所有容器都认识的公共用户）
 │        不存业务数据，官方建议别往里塞业务对象
 ├─ PDB$SEED（种子 PDB，系统模板）
 │     └─ 用来快速克隆出新 PDB；只读，不能改
 ├─ PDB1（用户 PDB，真正的业务库，看起来就是独立数据库）
 │     ├─ 自己的数据字典、SYSTEM/SYSAUX/USERS/TEMP、local undo、数据文件
 │     └─ 内部仍是 多个 Schema（=用户），和传统一样
 ├─ PDB2 …（0~N 个用户 PDB）
 └─ Application Container（应用容器，可选，SaaS 多租户用）
       └─ 一个 Application Root + 若干 Application PDB，共享同一套应用元数据/代码
```

- **系统容器（system container）** = `CDB$ROOT` + 所有 PDB 的整体，是“整个 CDB”的逻辑统称。
- 关键：**所有 PDB 共享同一份 SGA、同一份后台进程、同一组在线重做日志、同一个控制文件**（由那个“一个实例”管理）。隔离发生在“数据库”这一层，而不是“实例”层。

#### 2.5.3 传统非 CDB 部署长什么样

- 一个实例打开一个数据库；库内部靠 **多个 Schema（=用户）** 来区分不同业务/租户。
- 所有 Schema **共享同一套**：数据字典、undo、temp、redo、SGA/PGA、控制文件。
- “隔离”只能靠**权限 / 配额 / 角色**做**逻辑隔离**，没有数据库级的边界——一个 Schema 的失控 SQL 可能拖垮整个库。

#### 2.5.4 核心区别对照表（重点）

| 维度 | 传统非 CDB（单库多 Schema） | 容器 CDB/PDB（多租户） |
|---|---|---|
| **租户单位** | Schema（用户） | PDB（可插拔数据库） |
| **数据字典** | 全局唯一一份 | 每个 PDB 各自一份（root 只存骨架 + 公共元数据） |
| **隔离级别** | 逻辑隔离（共享字典/undo/redo/内存） | **数据库级隔离**（PDB 有独立字典、独立 local undo、独立 temp） |
| **实例/内存** | 一实例一库 | 一实例一 CDB，**多个 PDB 共享同一 SGA/进程**（单 PDB 失控会拖累同 CDB 兄弟，这是共享 SGA 的代价） |
| **资源管控** | Resource Manager 限用户 | 同样共享 SGA，但可用 **PDB 级 Resource Plan** 精细限流 |
| **备份恢复** | 整库备份/恢复 | 可**按 PDB 独立备份/恢复/闪回**（local undo 支撑 PDB 级闪回） |
| **升级/补丁** | 整库停机升级 | PDB 可**独立升级 / 克隆 / 迁移**（unplug 一个 PDB 插到高版本 CDB） |
| **克隆/搬迁** | Data Pump / duplicate | **`CREATE PLUGGABLE DATABASE … FROM`** 秒级克隆；unplug/plug 跨 CDB 搬；relocate 在线迁 |
| **连接方式** | 服务名指到库，靠 Schema 区分 | 服务名/TNS 直接指到**某个 PDB**；客户端视角就是独立库 |
| **关闭语义** | `SHUTDOWN` 关整库 | 在 PDB 内 `SHUTDOWN` **只关该 PDB**（其实是 CLOSE），CDB 其他 PDB 不受影响 |
| **数据字典视图** | `DBA_`/`USER_`/`ALL_` | 多了 **`CDB_` 视图**（在 root 查可见所有 PDB，带 `CON_ID` 列）；`DBA_` 只显示当前容器 |
| **授权** | — | 1~3 个用户 PDB 的 CDB **不需要** Multitenant 选件；**第 4 个起需付费选件** |
| **版本支持** | 12c 前默认；**21c 起已废弃** | **21c / 23ai / 26ai 唯一支持架构** |

#### 2.5.5 和本文前面概念的关系（把地图拼起来）

- **实例层不变（呼应 §4）**：无论 CDB 还是非 CDB，**一个实例 = 一份 SGA + 一组后台进程**。CDB 的本质是“**一个实例里塞了多个 PDB**”，它们共享同一份 SGA、redo、控制文件——这正是“单 PDB 失控会拖垮兄弟”的根因，也是前面 §6.4 讲的 redo 日志是**全 CDB 一份**（实例级）的原因。
- **Schema 含义不变**：PDB 内部仍然是“多个 Schema = 多个用户”，和 §2.3 讲的一模一样；多租户只是**在 Schema 外面多包了一层 PDB 容器**把 Schema 们分组。
- **redo / undo（呼应 §5.5、§6.3）**：12.2+ 默认 **local undo**，每个 PDB 有自己的 undo 表空间；**redo 仍是全 CDB 一份**（因为 redo 是实例级、由 LGWR 写）。local undo 让 PDB 能独立闪回。
- **存储层次（呼应 §10）**：每个 PDB 各有自己的 `SYSTEM/SYSAUX/USERS/TEMP` 表空间（落在各自的数据文件里）——所以“表空间”在 CDB 下是“**每 PDB 各自一套**”。
- **Data Guard（呼应 §7）**：传统 DG 以“库”为单位保护；多租户下可整体保护 CDB，也可做 PDB 级复制（更高级用法）。
- **连接/权限**：`CDB$ROOT` 里建的是 **common user**（所有容器可见，如 `C##xxx` 命名约定），PDB 里建的是 **local user**（只在该 PDB 可见）——这就是多租户下的用户模型。

#### 2.5.6 一句话记忆

> **传统部署 = 一个实例一个库、库里用 Schema 当租户（逻辑隔离）；容器部署 = 一个实例一个 CDB、CDB 里多个 PDB 当租户（数据库级隔离，可插拔/克隆/独立升级）。21c 起 Oracle 只认 CDB，非 CDB 已进历史。**

---

### 2.6 深入：RAC（Real Application Clusters）部署 vs 普通单实例部署

前面 §2.5 讲了“容器 vs 非容器”是 Oracle 在 **21c 起唯一的部署形态**（CDB/PDB），那是**纵向的部署形态**问题。本节讲另一个正交的横切维度：**“单机单实例”还是“多机多实例共享一个库”**——也就是 RAC。

> 维度独立：CDB/PDB 和 RAC 是两个不同的坐标轴。**一个 CDB 既可以跑在单实例上，也可以跑在 RAC 上**（多节点多 PDB）；下面所有讨论都假设是同一代部署形态下的“单实例 vs RAC”。

#### 2.6.1 一句话本质

| | 普通单实例部署 | RAC（真正应用集群）部署 |
|---|---|---|
| 服务器 | 1 台 | **N 台（节点，N≥2）** |
| 实例 | **1 个** | **N 个**（每台 1 个） |
| 数据库 | **1 个**（实例 ⇄ 库 是 1:1） | **还是 1 个**，但被 N 个实例**同时打开**（实例 ⇄ 库 是 **N:1**） |
| 存储 | 本地磁盘即可 | **必须共享存储**（SAN/NAS，或 ASM on 共享盘）——所有节点看到**同一份**数据文件/控制文件/在线 redo |

**最关键的一句话：RAC 是“多实例、单数据库”，不是“多实例、多数据库”。** 所有实例访问的是**同一份磁盘数据**——这直接改写了 §2.4 那张关系图：单实例是「1 实例 → 1 库」，RAC 是「N 实例 → 1 库」，且这 N 个实例分布在 N 台物理机上。

#### 2.6.2 认知纠偏（呼应 §2.4 三者关系图）

- 单实例：一个实例 `mount` + `open` 一个库，实例和库**一一对应**；库在磁盘上，实例在内存/进程里，各一份。
- RAC：同一份库文件被**多个实例**同时 `open`。每个实例各有**自己的 SGA、自己的后台进程、自己的 redo 线程、自己的 undo 表空间**（见 §2.6.4），但它们**共享同一份数据文件**——所以“改了同一行”多个实例都能立刻看到。
- 因此 **“实例”在 RAC 里被复制了 N 份**（呼应 §4：每个实例都有 §4 那套进程，并额外多出全局进程），而“数据库”始终保持一份。

#### 2.6.3 架构组成对比（多出来的东西）

RAC 相比单实例，**强制多出三类基础设施**：

1. **共享存储（Shared Storage）**：所有节点必须能访问同一批磁盘（SAN/NAS，或 ASM 管理的共享 LUN）。单实例用本地盘即可。这是 RAC “share-everything” 的物理基础——也是它**不是容灾**的根源（存储只有一份，机房级灾难会一起没，见 §2.6.5）。
2. **集群软件（Oracle Clusterware / Grid Infrastructure）**：RAC **必需**。它管节点成员关系、VIP、OCR（集群配置仓库）、Voting Disk（投票盘防脑裂）。单实例完全不需要。
3. **高速私网（Interconnect）**：节点间专用网络（InfiniBand / 万兆），**Cache Fusion 走这条线**。单实例无。

另外 ASM 在 RAC 下几乎必备（19c 起只有 Flex ASM，用于把共享盘池化给集群），单实例用不用都行。

#### 2.6.4 核心黑科技：Cache Fusion（缓存融合）

这是 RAC 和单实例**最本质的运行期差异**，理解了它就读懂了 RAC。

- **单实例**：每个数据块只有“一份内存副本”在 Buffer Cache 里，本地读写天然一致，不需要协调。
- **RAC**：N 个实例各有**各自的 Buffer Cache**。同一个块可能同时被多节点缓存，且版本可能不同。**怎么保证“任何节点读到的都是当前最新”**？靠 Cache Fusion：
  - **GCS（Global Cache Service，全局缓存服务）**：跟踪每个块在集群中的**位置与状态**（如 `XCUR` 独占当前、`SCUR` 共享当前、`CR` 一致性读等）。
  - **GES（Global Enqueue Service，全局队列服务）**：管跨节点的锁（行锁、DDL 锁、全局资源）。
  - **读场景**：节点 A 要读某块，若该块的**最新版本在节点 B 的内存里**，GCS 通过私网**直接从 B 的内存把块传给 A 的内存**（diskless，不落盘）——这就是“把各节点 SGA 融合成一个全局缓存”。只有当块不在任何节点内存时，才由持有者从磁盘读入再传。
  - **写场景**：节点 A 要改某行 → 需获得该块的**全局独占锁** → 通过 interconnect **从当前持有者 B 把块所有权转让给 A**（几到几十 ms，比单节点内存操作慢、但远快于落盘）。
  - **GRD（Global Resource Directory，全局资源目录）**：GCS/GES 的状态表，**分布保存在所有节点内存里（无单点）**；某节点失败时，由存活节点据 OCR/Voting 重建。
  - **新增全局后台进程**（呼应 §4：在单实例进程集上多出）：`LMSx`（Cache Fusion 块传输主力，可多组）、`LMON`（全局锁监控/节点成员管理）、`LMD0`（处理全局锁请求）、`LCK0`（非缓存类资源锁）、`DIAG`（诊断）等。

> 一句话：单实例的 Buffer Cache 是“本地私有”；RAC 的 N 个 Buffer Cache 被 Cache Fusion 逻辑“**融合**”成一个全局缓存，代价是跨节点的网络协调开销。

#### 2.6.5 高可用与扩展性（RAC 解决什么、不解决什么）

- **单实例宕机**：整库不可用，应用全部断连，只能等重启，或靠 Data Guard 切到备库（第 7 节）。
- **RAC 节点故障**：其它节点**继续对外服务**。宕机节点的连接在 **TAF（Transparent Application Failover，透明应用故障转移）** 下自动重连到存活节点（未提交事务回滚、已提交数据保留）；**FAN（Fast Application Notification）** 实时把故障事件推给连接池；数据库 **Service** 可在节点间漂移。这是 RAC 的“高可用”卖点。
- **横向扩展**：读/写都能**加节点 scale-out**（share-everything 架构）。**高读场景（如数仓）尤其合适**——只读基本不需要块所有权转让，扩展效率接近线性。
- **⚠️ 重大澄清：RAC ≠ 容灾！** 因为 RAC 共享**同一份存储**，存储损坏、机房级灾难会让所有节点一起完蛋。跨地域容灾仍然是 **Data Guard（第 7 节）** 的职责。**正确的组合是：RAC 管“本地机房多机高可用 + 读写扩展”，Data Guard 管“异地副本容灾”，两者正交、可叠加。** 不要因为上了 RAC 就以为不用做容灾。

#### 2.6.6 和本文前面概念的关系（把地图拼起来）

- **redo 日志坐标（呼应 §6.4）**：单实例只有 **1 个 redo 线程（thread# = 1）**；RAC 下**每个实例有独立的 redo 线程（thread 1、thread 2…）**，所以 §6.4 讲的“redo 日志流”在 RAC 里是“**每个线程各一条流**”。`RESETLOGS` 重置的是“每个 thread 各自的 sequence#”；实例恢复时每个实例恢复自己的 thread，失败节点的 thread 也可由存活节点代为恢复——这正依赖 GRD 的全局知识。
- **undo/redo（呼应 §5.5、§6.3）**：RAC 下**每实例独立 undo 表空间 + 独立 redo 线程**（RAC 强制 local undo）。构造 CR 一致性读块时，若需要的旧版本在别的节点内存/undo 镜像上，会通过 Cache Fusion 跨节点取——把 §5.5 的“沿 undo 回滚”扩展成了“可能跨节点”的全局动作。
- **进程（呼应 §4）**：单实例的那套进程（LGWR/DBWn/CKPT/SMON…）**每个 RAC 实例都各有一套**，再叠加 §2.6.4 的全局进程（LMS/LMON/LMD/LCK/DIAG）。所以 RAC 的“实例 = 一组进程 + SGA”在 §2.5 的基础上被**复制 N 份并加料**。
- **CDB/PDB（呼应 §2.5）**：RAC 与多租户正交——可以“一个 CDB 跑在 RAC 上”（多节点 + 多 PDB），两者维度独立叠加。
- **Data Guard（呼应 §7）**：RAC 是“本地高可用”，DG 是“异地容灾”，True Cache 是“读缓存”——三者解决不同问题，可同时存在于一套体系里。

#### 2.6.7 八维对照表

| 维度 | 普通单实例 | RAC |
|---|---|---|
| 实例数 | 1 | N（≥2） |
| 实例⇄库关系 | 1:1 | N:1（共享同一库） |
| 存储 | 本地磁盘 | 共享存储（SAN/NAS/ASM） |
| 集群软件 | 不需要 | Oracle Clusterware 必需 |
| 私网 interconnect | 无 | 必需（Cache Fusion 走这） |
| 内存协调 | 本地 SGA 自足 | 全局缓存融合（GCS/GES/GRD） |
| redo / undo | 单线程 / 单 undo | 每实例独立线程 / 独立 undo |
| 高可用 | 宕机即不可用 | 节点故障透明切换（TAF/FAN） |
| 扩展性 | 纵向 scale-up | 横向 scale-out（读写均可） |
| 容灾 | 无（需 DG） | **无（需 DG，RAC 非容灾）** |
| 运维复杂度 | 低 | 高（脑裂/GRD/interconnect/节点驱逐） |

#### 2.6.8 一句话记忆

> **单实例 = 1 机 1 实例 1 库；RAC = N 机 N 实例 1 库（共享存储），靠 Cache Fusion 把各节点内存“融合”成全局缓存，换来本地高可用 + 横向扩展；但共享存储意味着它不是容灾——容灾还得靠 Data Guard（第 7 节）。**

---

## 3. Oracle 的内存结构：SGA 与 PGA 里到底放了什么

Oracle 启动时会向操作系统申请一大块共享内存，再加上每个连接私有的内存。整体分两大类：**SGA（共享）** 和 **PGA（私有）**。

### 3.1 总览图

```
                        Oracle 实例 (Instance) 的内存
   ┌───────────────────────────────────────────────────────────────┐
   │                      SGA  （System Global Area 共享全局区）      │
   │        所有后台进程 + 所有服务器进程都能访问的“公共内存”          │
   │                                                                │
   │  ┌─────────────────┐  ┌─────────────────┐  ┌────────────────┐ │
   │  │ Database        │  │ Redo Log        │  │  Shared Pool   │ │
   │  │ Buffer Cache    │  │ Buffer          │  │ ┌────────────┐ │ │
   │  │ （数据块缓存）   │  │ （重做日志缓冲） │  │ │Library Cache│ │ │
   │  │                 │  │                 │  │ │SQL/执行计划 │ │ │
   │  └─────────────────┘  └─────────────────┘  │ └────────────┘ │ │
   │  ┌─────────────────┐  ┌─────────────────┐  │ ┌────────────┐ │ │
   │  │ Large Pool      │  │ Java / Streams  │  │ │Dict Cache  │ │ │
   │  │ (RMAN/并行/UGA) │  │ Pool (可选)     │  │ │数据字典缓存│ │ │
   │  └─────────────────┘  └─────────────────┘  │ └────────────┘ │ │
   │                                             └────────────────┘ │
   └───────────────────────────────────────────────────────────────┘

   每个会话/服务器进程 独占的私有内存：
   ┌───────────────────────────────────────────────────────────────┐
   │  PGA  （Program Global Area 程序全局区，每连接私有）            │
   │   私有 SQL 区（绑定变量值、游标状态） / 排序区 / 哈希区 / UGA   │
   └───────────────────────────────────────────────────────────────┘
```

### 3.2 SGA 各区域的职责

| SGA 区域 | 里面放什么 | 一句话作用 |
|---------|-----------|-----------|
| **Database Buffer Cache（数据缓冲区）** | 从数据文件读进来的**数据块（默认 8KB 一块）**的副本，含已修改的“脏块” | 读写都先在这里发生。读命中就不碰磁盘；改也先在内存改，之后异步刷盘（数据块在“表空间→段→区→块”层次里是最底层，每个 8KB 数据块的内部结构见第 11 节） |
| **Redo Log Buffer（重做日志缓冲）** | 每个 DML/DDL 产生的**重做记录（redo 变更向量）**，描述“哪块、哪行、从什么改成什么” | 提交时由 LGWR 刷到 redo 日志文件，保证宕机可恢复（redo 记录内部到底装了什么，见第 6.3 节） |
| **Shared Pool（共享池）** | ① **Library Cache**：解析后的 SQL/PLSQL 执行计划、解析树；② **Data Dictionary Cache**：数据字典（表结构、权限）缓存 | 让“相同 SQL 第二次执行不用重新解析”（软解析），是性能与第 8 节 Shared Pool 问题的核心 |
| **Large Pool（大池）** | RMAN 备份、并行查询消息缓冲、共享服务器模式的 UGA | 把“大块内存需求”从 Shared Pool 里分流出来，避免冲击共享池 |
| **Java Pool / Streams Pool** | Java 存储过程 / 流复制相关 | 可选组件，一般可忽略 |

> 23ai 起还有 **In-Memory Column Store** 等可选区域（列式内存加速分析），初学可暂不深究。

### 3.3 PGA 里放什么

PGA 是**每个会话私有**的内存（不共享），主要放：

- **私有 SQL 区**：当前游标状态、绑定变量（bind variables）的具体值、执行时的运行时结构。
- **排序区（Sort Area）**：`ORDER BY`、`GROUP BY`、建索引时的内存排序，不够用会溢出到临时表空间（慢）。
- **哈希区（Hash Area）**：哈希连接（hash join）用。
- **UGA**：在“专用服务器模式”下 UGA 在 PGA；在“共享服务器模式”下 UGA 在 SGA 的 Large Pool。

> 记忆口诀：**SGA 是“公共黑板”，所有人都能看；PGA 是“你自己的草稿纸”，只有你自己用。** 你写的绑定变量值存在你的 PGA 草稿纸上，但 SQL 的“解题步骤”（执行计划）会贴到 SGA 的公共黑板上供大家复用。

---

## 4. Oracle 的后台进程：每个进程是干什么的

Oracle 启动实例时会拉起一组**后台进程**（background processes）。记住它们各自的“人设”，对理解第 6 节 DML 流程至关重要。

### 4.1 核心后台进程

| 进程 | 人设 / 职责 | 关键动作 |
|------|------------|---------|
| **PMON**（Process Monitor） | 清道夫 | 某个客户端/服务器进程崩溃后，PMON 负责清理它、回滚它未提交的事务、释放锁和资源 |
| **SMON**（System Monitor） | 系统管家 | 实例崩溃后的**实例恢复**（前滚 redo + 回滚未提交）；合并空闲空间；清理临时段 |
| **DBWn**（Database Writer，n 可多个） | 刷盘工 | 把 Buffer Cache 里的**脏块（被改过但未写盘的数据块）**写回数据文件。注意：**它是“懒”的**，提交时不必立即写盘 |
| **LGWR**（Log Writer） | 持久化守门员 | 把 **Redo Log Buffer** 的内容写入**在线重做日志文件**。**提交（COMMIT）时必须触发它**，这是“已提交数据不会丢”的保证（它写出的 redo 内容结构见第 6.3 节） |
| **CKPT**（Checkpoint） | 同步员 | 触发检查点：通知 DBWn 把某些脏块写盘，并更新**数据文件头和控制文件里的 SCN**，标记“到这里之前的数据都已落盘” |
| **ARCn**（Archiver，n 可多个） | 归档员 | 在线 redo 日志写满后，把它**复制成归档日志文件**。**只有开启归档模式（ARCHIVELOG）才有**；Data Guard 和 True Cache 都依赖它/redo 传输 |
| **RECO**（Recoverer） | 分布式事务修复 | 处理分布式事务（跨库）中失败的一方，保证两阶段提交最终一致 |
| **MMON / MMNL** | 统计员 | 自动收集 AWR 性能快照、产生告警；MMNL 在 MMON 忙时补位 |
| **LREG**（Listener Registration，12c+） | 服务广播 | 把数据库服务名注册到监听器，让客户端能连上来 |

> RAC（多实例集群）下还有 LMON/LMD/LMS 等集群专用进程，初学可先不管。

### 4.2 前台 / 服务器进程

后台进程是“数据库自己人”，真正为你干活的是**服务器进程（Server Process）**：

- **专用服务器模式（Dedicated）**：每个客户端连接独占一个服务器进程 + 一份 PGA。简单、延迟低，最常用。
- **共享服务器模式（Shared）**：多个连接共用一组服务器进程，由 Dispatcher 分配。省 PGA，适合海量短连接。

**一条 SQL 的执行，就是“你的服务器进程”借 SGA 干活、叫后台进程配合的过程** —— 第 6 节会完整演示。

---

## 5. 读不阻塞写、写不阻塞读：Oracle 的一致性读与多版本

这是 Oracle（以及所有现代关系库）最核心的并发设计思想，也是你问的“读不影响写，写不影响读”的答案。

### 5.1 两条基本性质

- **读不阻塞写**：读者不需要等写者释放锁，它直接读“自己该看到的那个版本”。
- **写不阻塞读**：写者改数据时，不需要把读者挡在门外（读者看旧版本）。

注意：**MVCC 只解决“读-写互不阻塞”，不解决“写-写冲突”**。两个人同时改同一行，仍然要靠**行锁（TX 锁）排队**，谁先改谁持有锁，另一个等。

### 5.2 Oracle 靠什么实现？SCN + UNDO + CR 块

Oracle 的底层设计哲学很特别，记住这句关键的话：

> **Oracle 不保存“多个版本的行”，它保存的是“能撤销变更的 UNDO（前像）”。一致性读是“按需把数据块现算回过去某个时刻”得到的。**

三个核心部件：

1. **SCN（System Change Number，系统变更号）**：Oracle 全局单调递增的“逻辑时钟”。任何改变数据的操作都会推进 SCN。它既用来**标记变更发生的时间点**，也用来**标记一次查询想要的“快照时间点”**。

2. **ITL（Interested Transaction List，块头事务槽）**：每个 8KB 数据块头部有一组事务槽，记录“谁（XID）改过这个块、改到了哪个 SCN（FSC）、undo 在哪（UBA）”。行头还有一个字节 `LB` 指向“最后改这一行的事务槽”。

3. **UNDO 表空间**：存放“被修改前的前像（before image）”，比如把 price 从 199 改成 189，undo 里记的是“这一列原来是 199”。UNDO 是环形复用、有保留期限的（`UNDO_RETENTION`）。**一条 UNDO 记录内部到底装了哪些字段、为什么是“链”、三种 DML 的前像有何不同，见第 5.5 节。**

### 5.3 一致性读（Consistent Read，CR）的过程

以默认的 **READ COMMITTED（语句级一致性）** 为例：

```
① 一条 SELECT 语句开始，Oracle 取“当前 SCN”作为本次查询的目标快照点 query_scn。

② 要去读某个数据块 B：
     └─ 判断 B 是否“足够旧且干净”（B 上所有变更 SCN 都 ≤ query_scn，且没有未提交事务）？
          ├─ 是 → 直接读块里的行（这叫 consistent gets，零 undo 应用）
          └─ 否 → 进入 ③

③ 在内存里“现场构造”一个 CR 块（Consistent Read Block）：
     对 B 上每一个“比 query_scn 更新”或“来自未提交事务”的修改：
       沿 ITL → UBA 找到对应的 UNDO 记录，把那次修改撤销掉；
       必要时继续沿 UNDO 链往前撤，直到把整个块还原成 query_scn 时刻的样子。

④ 在构造出的 CR 块上读取目标行并返回。
```

由此得到三个关键结论（这就是“读不阻塞写、写不阻塞读”的机制来源）：

- **未提交的修改也会被撤销**：所以 Oracle **天然没有脏读**——读者根本不理会别人没提交的东西，直接把它从自己看到的块里“抹掉”。读者完全不需要等写者。
- **所有读都走一致性读**（不像 MySQL 只有“快照读”才走）。普通 SELECT、索引扫描都基于 SCN 取一致视图。
- **CR 块是内存临时产物**，不写盘，用完即弃。这也意味着“同一个块被反复一致性读可能要反复重构”，是某些查询 `consistent gets` 偏高的根因。

### 5.4 隔离级别与 SCN 的关系

Oracle 只对外暴露“两档半”隔离级别（比 MySQL/PG 少，详见 [DB事务隔离级别.md](DB事务隔离级别.md)）：

| 级别 | 快照粒度 | 效果 | 备注 |
|------|---------|------|------|
| **READ COMMITTED（默认）** | **每条语句**开始时取 SCN | 单条语句内部看到同一时点；语句之间可见别人新提交的数据 | 无脏读，可能不可重复读/幻读 |
| **SERIALIZABLE** | **事务开始**时取 SCN，全程复用 | 整个事务看到同一个时点的数据 | **实际是“快照隔离(SI)”**，防不住“写偏斜”，冲突时报 `ORA-08177` |
| **READ ONLY** | 事务级 SCN | 同 SERIALIZABLE 的一致性，但禁止 DML | 长报表/导出的最佳选择 |

> 深入：Oracle 的 SERIALIZABLE 名不副实——它就是快照隔离，能防脏读/不可重复读/幻读（对快照读），但**防不住写偏斜**（两个事务改不同行却破坏跨行不变量）。跨行不变量要靠 `SELECT ... FOR UPDATE` 显式锁行，或下沉成约束。这部分在 [MVCC原理.md](MVCC原理.md) 第 3 节有逐机制拆解。

---

### 5.5 深入：UNDO 记录（Undo Record）到底包含什么

前面几节把 UNDO 当成“黑盒”在用（一致性读靠它、回滚靠它、闪回靠它）。这一节把它拆开，看清**一条 UNDO 记录里到底装了哪些东西**。理解了它，第 5.2 节的“沿 ITL→UBA 找 undo 撤销”和第 6 节的“记 UNDO 前像”就不再是空话。

#### 5.5.1 先澄清：UNDO 存在哪、它是什么

- **存在哪**：UNDO 数据存放在专门的 **UNDO 表空间**（如 `UNDOTBS1`），由一组**回滚段（Undo Segment）**承载。从 9i/10g 起 Oracle 默认“自动撤销管理（AUM）”，不再用手工管理的 rollback segment（回滚段的段级概念见第 10.3 节）。
- **是什么**：UNDO 记录的是**“修改前的值（before image）”，即“前像”**。它不是“数据的另一个版本”，而是**“怎么把这次改动撤销掉”的说明书**。
- **一条 DML 不止一条 undo**：一次 `UPDATE` 改了 3 列 → 可能产生 3 条 undo 记录；同一事务对同一行改了多次 → 多条 undo 串成链。

#### 5.5.2 UNDO 记录里具体包含哪些内容

一条 UNDO 记录（存在 undo 段的数据块里）大致由以下字段组成：

| 组成部分 | 含义 |
|---------|------|
| **事务标识 XID** | 这条 undo 属于哪笔事务（`undo段号.槽号.序列`，与第 11 节数据块 ITL 里的 XID 同源） |
| **回滚段地址 UBA** | **指向下一条 undo 记录**的指针，靠它把“同一事务的多次修改”串成回滚链 |
| **操作类型 OPCODE** | INSERT / UPDATE / DELETE —— 决定回滚时该做怎样的反向操作 |
| **目标对象 / 数据块地址 DBA** | 这条 undo 对应哪个表、哪个数据块（哪一块被改了） |
| **行在块内的位置（行号 / slot）** | 精确定位“改的是块里的第几行” |
| **修改前的列值（before image）** | 这是 undo 的核心：这一列 / 这一行**改之前是什么样** |
| **关联的事务提交信息（SCN 等）** | undo 头部记录事务的提交 SCN、状态，用于判断“这事务提交了没” |
| **与数据块 ITL 的双向链接** | 数据块 ITL 的 `UBA` → 指向“本事务对本块最近一次修改的 undo 记录”；而该 undo 记录的 `UBA` → 指向上一条 undo 记录。两者互指，形成“数据块 ↔ undo”闭环 |

> 关键点：**undo 本身也是“数据”，修改 undo 块也要写 redo**。所以 redo 同样保护 undo（呼应第 6.2 节“redo 与 undo 的关系”）。崩溃恢复时，Oracle 先 redo 前滚（包括把 undo 块也重做到崩溃前），再用 undo 把未提交事务回滚掉。一句话记忆：**redo 管“不丢”，undo 管“能撤”，undo 被 redo 管着。**

#### 5.5.3 回滚链 / CR 链：undo 为什么是“链”

同一个事务对同一个数据块改了多次，或对不同块改了多次，都会产生多条 undo 记录，它们通过 **UBA 指针**串成一条链：

```
数据块 B 的 ITL.UBA
      │
      ▼
 undo 记录 #3（最近一次改：balance 500→400）── UBA ──▶
 undo 记录 #2（上一次改：balance 600→500）── UBA ──▶
 undo 记录 #1（最早一次改：balance 700→600）── UBA ──▶ NULL（链尾）
```

- **回滚（ROLLBACK）**：从事务“当前”undo 出发，沿 UBA 链**从新到旧**依次反向操作，把每次修改都撤销掉，直到链尾——这就是“事务回滚”的物理过程。
- **一致性读（CR）**：第 5.3 节讲过，查询沿数据块 ITL 的 UBA 找到 undo，把块“现算回”快照时点；如果一次 undo 还不够（那次修改之前块又被同一事务改过），就继续沿 UBA 链往前回滚，直到达到 `query_scn`。

#### 5.5.4 三种 DML 的 undo 前像对比

| DML | 产生的 undo 前像 | 回滚时怎么反向 |
|-----|----------------|---------------|
| **INSERT** | “这一行原本**不存在**”（只需记下 ROWID / 行位置） | 反向 = **DELETE** 掉刚插入的那行 |
| **UPDATE** | “这一列原本是 X”（记下被改列的旧值） | 反向 = 把列**改回 X**（改了多列则每列各一条 undo） |
| **DELETE** | “整行原本是 Y”（记下**整行所有列 + ROWID**） | 反向 = 重新 **INSERT 整行**，并恢复原 ROWID |

> 注意 **DELETE 的 undo 最“重”**——它要保留整行数据，所以大批量删除时 undo 消耗最大、回滚也最慢。这也是“删数据慢 / 大事务回滚久”的底层原因之一。

#### 5.5.5 UNDO 被谁消费：三个“买家”

UNDO 不是写完就没人看，它有几个主要消费者：

1. **事务回滚（ROLLBACK）**：主动 `ROLLBACK`，或用第 5.5.3 节的链把改动还原。
2. **一致性读（CR）**：第 5.3 节查询构造旧版本，靠的就是 undo。
3. **崩溃 / 事务恢复（SMON）**：实例崩溃后，SMON 先用 redo 前滚，再用 undo 把“已写盘但未提交”的事务回滚。
4. **闪回（Flashback）**：`SELECT ... AS OF TIMESTAMP` 闪回查询、`FLASHBACK TABLE` 都基于 undo 里保留的前像（受 `UNDO_RETENTION` 控制），这是 Oracle 闪回技术的同源根基。

#### 5.5.6 顺带提一个经典错误：ORA-01555（snapshot too old）

- **原因**：一条查询跑得比较久，它所需要的“旧版本数据”对应的 undo 前像，因为**太久没被访问、超过了 `UNDO_RETENTION` 保留期**，已经被 Oracle 覆盖 / 回收，于是无法构造 CR 块。
- **缓解**：调大 `UNDO_RETENTION`、给 undo 表空间设 `AUTOEXTEND`、让长查询跑得快一点（加索引）、或改用物化视图预先算好。
- 更系统的 ORA-01555、闪回、ITL 等待等机制，在姊妹篇 [MVCC原理.md](MVCC原理.md) 第 4 节有专门展开。

#### 5.5.7 实操：怎么观察 UNDO

```sql
-- 1) 历史 undo 使用与“快照过旧”风险（每 10 分钟一行）
SELECT TO_CHAR(begin_time,'HH24:MI') AS time,
       undoblks,        -- 该时段产生的 undo 块数
       txncount,        -- 事务数
       maxquerylen,     -- 最长查询耗时（秒）
       ssolderrcnt      -- ORA-01555 发生次数
  FROM V$UNDOSTAT
 ORDER BY begin_time DESC
 FETCH FIRST 24 ROWS ONLY;

-- 2) undo 段的区状态分布（ACTIVE=事务正用，UNEXPIRED=未过期，EXPIRED=可复用）
SELECT status, COUNT(*) AS extents,
       ROUND(SUM(bytes)/1024/1024) AS mb
  FROM DBA_UNDO_EXTENTS
 GROUP BY status;

-- 3) 当前事务占用了多少 undo、UBA 在哪（定位“谁在吃 undo”）
SELECT s.sid, s.serial#, t.xidusn, t.ubablk, t.used_ublk, t.used_urec
  FROM V$TRANSACTION t
  JOIN V$SESSION s ON s.taddr = t.addr;

-- 4) dump 一条 undo 记录对应的 undo 块（结合第 11 节的 block dump 方法）
--    先从 V$TRANSACTION 拿到 UBA（ubafil/ubablk），再定位 undo 块
ALTER SYSTEM DUMP UNDO BLOCK '<undo_seg_name>' XID '<xid>';
```

#### 5.5.8 小结（与全文呼应）

- UNDO 记录 = **XID + UBA 链 + OPCODE + DBA/行号 + 前像值 + 提交信息**，是“怎么撤销改动”的说明书。
- 它是**双向指针桥**：数据块 ITL.UBA → undo 记录 →（UBA）→ 上一条 undo，同时支撑“回滚链”与“一致性读链”。
- UNDO 被 **redo 保护**，被 **回滚 / 一致性读 / 恢复 / 闪回** 四类场景消费。
- 它和 §5.3 一致性读、§6 的 DML 流程、§10.3 的 Undo Segment、§11 的 ITL 是同一套机制的五个切面。

---

## 6. 一条 DML 语句的完整旅程：各进程如何配合

以一条 `UPDATE accounts SET balance = balance - 100 WHERE id = 1;` 为例，把第 3、4、5 节的所有部件串起来。这是理解 Oracle 全貌最关键的一节。

### 6.1 时序全景

```
客户端 App
   │  ① 发送 SQL（携带绑定变量 :id=1, :amt=100）
   ▼
【服务器进程 / 前台进程】（代表该会话干活，独占一份 PGA）
   │  ② 在 PGA 分配私有 SQL 区，把绑定变量值放进去
   │  ③ 去 SGA 的 Shared Pool → Library Cache 找这条 SQL 是否已有执行计划
   │       ├─ 有（软解析）→ 直接复用
   │       └─ 没有（硬解析）→ 语法/语义检查、权限校验、生成执行计划并缓存
   │  ④ 执行：去 Database Buffer Cache 找 id=1 所在的数据块
   │       ├─ 命中（逻辑读）→ 直接用
   │       └─ 未命中 → 从数据文件物理读入 Buffer Cache
   │  ⑤ 用一致性读（CR，基于 SCN）定位要改的那一行（应用 WHERE 过滤）
   │  ⑥ 真正修改 Buffer Cache 中的块（此时还没落盘）：
   │       a. 在 UNDO 表空间分配 undo，记录 balance 的“前像”（比如原来是 500；undo 记录里具体装了什么，见第 5.5 节）
   │       b. 把块里的 balance 改成 400，行头 LB 指向 ITL 槽，ITL 记 XID + UBA
   │       c. 生成 redo 变更向量（① undo 块的改动 ② 数据块的改动）
   │       d. 把 redo 写入 SGA 的 Redo Log Buffer
   │       e. 在该行上加 TX 行锁（别人改同一行要等）
   ▼
【COMMIT】用户提交
   │  ⑦ LGWR 把 Redo Log Buffer 中本事务的 redo 刷到【在线重做日志文件】（持久化！）
   │  ⑧ 分配 commit SCN，写 commit 记录到 redo
   │  ⑨ 释放 TX 行锁；ITL 标记已提交（常采用“延迟块清除”，见下）
   │     事务结束——注意：脏块此时【不一定】写回数据文件
   ▼
【后台进程异步收尾】
   │  ⑩ DBWn 在合适时机（缓冲满/检查点）把脏块异步写回【数据文件】
   │  ⑪ CKPT 触发检查点，更新数据文件头/控制文件的 SCN，标记“此前已落盘”
   │  ⑫ ARCn 把写满的在线 redo 复制为【归档日志】（开归档才有；供 Data Guard/True Cache）
```

### 6.2 几个必须理解的设计要点

1. **提交 ≠ 立即写数据文件**。COMMIT 只强制 LGWR 把 redo 落盘（保证“已提交就不会丢”），脏块由 DBWn 懒写。这是 Oracle 高吞吐的关键：**把“随机写数据块”和“顺序写 redo”解耦**。

2. **redo 与 undo 的关系**：
   - **redo** 保证“改动能恢复/不丢”——宕机后能用 redo 把数据块重做到提交状态。
   - **undo** 保证“能回滚/能读旧版本”——事务回滚、一致性读都靠它。
   - 注意：**undo 的写入本身也被 redo 保护**（步骤 ⑥c 的 ①），所以 undo 也不会丢。
   - 想看清 redo / undo 各自记录里到底装了什么，分别见第 6.3 节 / 第 5.5 节。

3. **延迟块清除（Delayed Block Cleanout）**：提交时 Oracle 不会去逐个修改所有被改过的块的 ITL（太慢）。那些“看起来还像活跃事务”的块，等到**下一次有人访问它**时才顺手清理。这就是为什么“一条纯 SELECT 有时也会产生一点点 redo”。

4. **进程协作一句话总结**：**服务器进程负责“算和改”，LGWR 负责“保持久”，DBWn 负责“异步落盘”，CKPT 负责“同步账本”，ARCn 负责“留底供容灾”，PMON/SMON 负责“兜底清理与崩溃恢复”**。

---

### 6.3 深入：REDO 记录（Redo Record）到底包含什么

上一节（§6.2）反复提到“redo 保持久、undo 管回滚、redo 还保护 undo”。这一节把 **REDO 记录**拆开，看清它到底装了什么——它是 Oracle “崩溃不丢数据”的根基，也是第 7 节 Data Guard / True Cache 同步的载体。

#### 6.3.1 先澄清：REDO 记的是“改完后的样子”，不是 SQL

- **记的是什么**：REDO 记录的是**“修改后的值（after image）”**，在 Oracle 内部叫**变更向量（Change Vector, CV）**。它直接描述“对哪个数据块的哪个位置做了什么改动”，是**块级**的，不是“把 SQL 再执行一遍”。
- **为什么是块级**：基于 SQL 重放（像 MySQL 的 binlog 行格式回放）很慢且容易受触发器/约束干扰；块级变更向量可以直接“贴”到块上，所以 Oracle 崩溃恢复极快。
- **存在哪**：REDO 写在 **在线重做日志（Online Redo Log）** 里，由 LGWR 顺序写（物理层：多组日志循环写，见第 6.3.8 节）；写满后由 ARCn 归档成**归档日志（Archive Log）**，再被第 7 节的 Data Guard / True Cache 消费。
- **一条 DML 产生多个变更向量**：改数据的同时也要改 undo 块、改 undo 段头，所以一次 `UPDATE` 的 redo 是一组 change vector 的集合，而不是孤立一条。

#### 6.3.2 REDO 记录里具体包含哪些内容

一条 **Redo Record** 由若干 **Change Vector** 组成，结构大致如下：

```
Redo Record 头
 ├─ 事务标识 XID（哪笔事务）
 ├─ 系统变更号 SCN（这条改动对应的“时钟”）
 ├─ RBA（Redo Byte Address：这条 redo 在日志流里的坐标，见 6.3.3）
 ├─ 关联 UBA（指回该事务的 undo，便于必要时回滚定位）
 └─ 由 N 个 Change Vector 组成（N≥1）

Change Vector #1（例如：改 undo 块，分配事务表槽）
 ├─ 目标块地址 DBA / (文件号 + 块号)
 ├─ 操作类型 OPCODE（如“修改 undo 块”“修改数据块”“事务提交”）
 ├─ 块内改动位置（偏移量 / 行号 / 列）
 ├─ 改动后的新值（after image）
 └─ 块类（class，标识是数据块 / undo 块 / 段头 等）
Change Vector #2（例如：改真正的数据块，把 balance 改成 400）
Change Vector #3（例如：改 undo 段头的状态）
 ...
```

每个 **Change Vector** 的核心字段：

| 字段 | 含义 |
|------|------|
| **DBA（块地址）** | 这次改动落在哪个数据文件、哪个块（`文件号.块号`） |
| **OPCODE** | 操作类型：改数据块 / 改 undo 块 / 改 undo 段头 / 事务提交或回滚 等 |
| **改动位置** | 块内偏移 / 行号 / 列号，精确定位“块的哪一处被改” |
| **新值（after image）** | 改动**之后**这个位置的值是什么 |
| **块类（class）** | 标识受影响的是普通数据块、undo 块、还是段头/控制信息 |
| **SCN / 顺序** | 这条变更对应的系统变更号，以及它在 redo 流里的先后顺序 |

> 关键点：**一次 `UPDATE` 的 redo 通常含多个 change vector**——① 改 undo 块（登记事务）② 改数据块（真正改数据）③ 可能改 undo 段头 ④ 提交时改事务状态为“已提交”。这就是为什么第 6.2 节说“**undo 的写入本身也被 redo 保护**”：undo 块的改动同样是 change vector，写进了 redo 流。

#### 6.3.3 RBA（Redo Byte Address）：redo 在日志流里的“坐标”

- **RBA = 日志序列号（sequence#）+ 日志块号 + 块内偏移**，精确定位“这条 redo 在 redo 日志文件的什么位置”。
- 一个事务有 **Low RBA**（它写的第一条 redo 位置）和 **High RBA**（最后一条）。
- 实例恢复 / 介质恢复时，Oracle 按 **RBA 顺序重放** change vector，保证重做的幂等和顺序正确。
- RBA 的三元组二进制构成、派生坐标（Checkpoint/On-disk/Target RBA）、与 SCN 的对照、以及 redo 日志文件的物理块结构，详见第 6.4 节。

#### 6.3.4 REDO 不记录什么（澄清误区）

- **redo 不记录 SELECT**：纯读不产生 redo（唯一例外是 §6.2 提到的“延迟块清除”，SELECT 顺手清理旧块 ITL 时会产生极少量 redo）。
- **redo 不直接存“旧值”**：旧值（前像）在 undo 里；但** undo 块的改动被 redo 记录**，所以旧值通过“redo 保护 undo”间接得到了保护。
- **物理 redo ≠ 逻辑 redo**：默认在线 redo 是**块级物理变更**（最快、最稳）；只有“逻辑备用库（Logical Standby）”才会把改动解析成 SQL 级重放，用于异构同步、滚动升级等场景。

#### 6.3.5 redo 与 undo：一对“好搭档”（呼应 §5.5）

一次 DML，Oracle **同时生成两类记录**：

| | REDO | UNDO（§5.5） |
|---|------|------|
| 记的内容 | **改之后**的样子（after image / change vector） | **改之前**的样子（before image / 前像） |
| 作用 | 崩溃后**前滚重做**、介质恢复、Data Guard 同步 | 事务**回滚**、一致性读、闪回 |
| 被谁保护 | redo 本身就是“持久化的载体”，最底层 | **undo 被 redo 保护**（undo 块的改动也在 redo 里） |

一句话记忆：**undo 告诉你“怎么撤”，redo 告诉你“怎么重做”；redo 先保护 undo，再保护数据本身。**

#### 6.3.6 REDO 被谁消费：三个“买家”

1. **实例恢复（Instance Recovery）**：崩溃重启时，SMON 用 redo 把“已提交但尚未写盘”的改动**前滚**到最新（包括把 undo 块也重做），再用 undo 把“未提交”的事务**回滚**掉。
2. **介质恢复（Media Recovery）**：数据文件损坏后，从备份还原，再用**归档日志 + 在线 redo** 把库恢复到某个时点（不完全恢复 / 时间点恢复）。
3. **Data Guard / True Cache 同步（第 7 节）**：redo 被 LGWR 发往备库 / 缓存节点，经 RFS 接收、apply 到数据块——这是容灾与读扩展的底层燃料。

#### 6.3.7 物理层呼应：在线重做日志的“组 / 成员”

- 在线重做日志由多个 **日志组（Log Group）** 组成，每组有 **1 个或多个成员（Member，镜像冗余防磁盘坏）**，循环写。
- 当前组写满 → 触发 **日志切换（Log Switch）** → ARCn 把写满的组归档 → LGWR 接着写下一组。
- 日志块大小通常为 512 字节或 4KB（取决于平台与磁盘），与第 11 节数据块（8KB）是不同层级的概念。
- 这组机制与第 10 节的“逻辑存储层次”正交：redo 日志是**独立的顺序写文件**，不归表空间/段/区/块管理。

#### 6.3.8 实操：怎么观察 REDO

```sql
-- 1) 日志组与成员、当前状态（CURRENT=正在写，ACTIVE=未归档完，INACTIVE=可覆盖）
SELECT a.group#, a.status, a.sequence#, b.member
  FROM V$LOG a
  JOIN V$LOGFILE b ON b.group# = a.group#
 ORDER BY a.group#;

-- 2) 日志切换历史（看 redo 生成频率，频繁切换可能说明日志太小）
SELECT TO_CHAR(first_time,'YYYY-MM-DD HH24:MI') AS switch_at,
       sequence#
  FROM V$LOG_HISTORY
 ORDER BY first_time DESC
 FETCH FIRST 20 ROWS ONLY;

-- 3) redo 生成量（字节 / 事务数 / 块数）
SELECT name, value
  FROM V$SYSSTAT
 WHERE name IN ('redo size','redo entries','redo blocks written','user commits');

-- 4) 归档情况（Data Guard / True Cache 依赖，见第 7 节）
SELECT sequence#, name, applied, archived
  FROM V$ARCHIVED_LOG
 ORDER BY sequence# DESC
 FETCH FIRST 20 ROWS ONLY;

-- 5) dump 一段 redo 看 change vector 明细（配合第 11 节的 block dump 对照理解）
ALTER SYSTEM DUMP LOGFILE '<redo_file_path>'
  SCN MIN 1000000 SCN MAX 1000100;
```

#### 6.3.9 小结（与全文呼应）

- REDO 记录 = **Redo Record 头（XID/SCN/RBA/UBA）+ 一组 Change Vector（DBA/OPCODE/位置/新值/块类）**，是“块级改完后样子”的说明书，不是 SQL 重放。
- 一条 DML 的 redo 含多个 change vector：**改 undo 块 + 改数据块 +（可能）改段头 + 提交标记**；因此 **undo 也被 redo 保护**。
- redo 靠 **RBA** 定位、按 **SCN 顺序**重放；被**实例恢复 / 介质恢复 / Data Guard·True Cache 同步**三类场景消费。
- 它与 §3 的 Redo Log Buffer、§4 的 LGWR/ARCn、§5.5 的 UNDO、§6 的 DML 流程、§7 的同步策略、§11 的块结构是同一套机制的六个切面。

---

### 6.4 深入：Redo Log 的物理结构与日志流坐标体系

§6.3 讲了 redo **记录里装了什么**（Change Vector）。这一节往下一层，看 **redo 日志文件在磁盘上长什么样、redo 记录是怎么“排”在里面的，以及日志流用什么“坐标”给任意一条 redo 记录定位**——这部分是理解实例恢复、介质恢复、Data Guard 同步的钥匙。

#### 6.4.1 在线重做日志文件在磁盘上的样子

一个在线重做日志文件（Online Redo Log，就是 `*.log` 那几个物理文件）在磁盘上就是**一串连续的二进制块**：

```
┌───────────────────────────── 一个在线重做日志文件 ─────────────────────────────┐
│ [block 0] 文件头（redo log file header）                                          │
│    └─ DBID、thread#、sequence#、low SCN、next/next SCN、resetlogs ID、           │
│       块大小、块总数等“文件级”元数据                                              │
│ [block 1] 真正的 redo 数据块（16 字节块头 + 记录区）                               │
│ [block 2] 真正的 redo 数据块                                                       │
│ ...（循环写满整个文件）                                                            │
└──────────────────────────────────────────────────────────────────────────────────┘
```

两个**极易混淆**的点先点破：

- **redo 块大小 ≠ 数据块大小**。redo 块通常是 **512 字节**（Windows/Linux/Solaris 主流平台；部分平台 1024B/4KB），这个值在 Oracle 代码里基本写死；它**不是** §11 讲的那个 8KB 数据块。LGWR 是按“redo 块”顺序追加写的。
- **重要澄清**：上面图里的 `[block 0]`/`[block 1]`/… 数的是 **redo 块（512B）**，而**不是** §10/§11 那个 8KB 数据块——这是两个完全不同的“block”概念，只是都叫 block。因此 **redo 日志文件头（block 0）本身只占 512 字节（一个 redo 块）**，不是 8KB；§6.4.4 的 **RBA 三元组里的 `block#` 数的是这些 512B redo 块的序号**，与数据文件的 8KB 块编号互不相干。
- **每个 redo 数据块开头有固定的 16 字节块头**（以签名 `0x0122` 开头），里面存了三样东西：①块在文件里的编号 ②本条日志的序列号 ③块内第一条记录起始的字节偏移。**这三个值合起来，正好就是 §6.3.3 说的 RBA 三元组**（详见 6.4.4）。块头末尾还有 2 字节校验和，用来判断“块有没有写坏/写断裂”。

所以你看：`V$LOG` 里一个“日志组”对应物理上的一组成员文件，每个文件内部就是“1 个文件头块 + N 个 redo 数据块”这么朴素的结构。

#### 6.4.2 redo 块内部：一条 redo 记录是怎么存放的

从 block 1 开始，文件里就是一系列 **Redo Record（redo 记录，也叫 redo entry）**，每个记录如此构成：

```
Redo Record（一条 redo 记录）
 ├─ Redo Record Header（记录头）
 │    ├─ 线程号 thread#（RAC 里哪个实例）
 │    ├─ RBA（这条记录自己的坐标，见 6.4.4）
 │    ├─ SCN（这条改动对应的全局时钟，见 6.4.4）
 │    └─ XID 等元信息
 └─ 一个或多个 Change Vector（变更向量，§6.3 的核心）
      ├─ 头：change # / TYP / CLS(块类) / AFN(绝对文件号) / DBA / SCN / SEQ / OP(操作码)
      └─ 体：被改块的具体“新值/新状态”
```

要点：

- **一个 Change Vector 只针对一个数据块、只含一次变化**——它可以是改普通数据块、改 undo 块、改段头、改 undo 段头等（呼应 §5.5、§6.3）。
- **一个 Redo Record 可以横跨多个 redo 块**（一条大记录放不进一个 512B 块时，会拆到后续块）。这时靠每个块头里的 RBA 三元组来“拼接定位”——这正是日志流坐标存在的原因。
- **Redo Record 大小不固定，512B 只是它的“容器”大小（关键澄清）**：一条 redo 记录到底多大，取决于它含**几个 Change Vector**、每个 CV 又多大（CV 大小看改的是数据块还是索引块、行宽多宽、动了哪几列、前后像有多大等），所以 **redo 记录是变长的**。而 **512 字节是 redo 块（写盘/物理单位）的固定大小**，不是记录大小——**小记录装进单个 512B 块（去掉 16B 块头约剩 496B 可用），大记录就跨多个 512B 块靠块头 RBA 拼接**（见上条）。二者的关系是“**内容 vs 盒子**”：record 是内容（变长），redo 块是盒子（固定 512B），一个盒子装不下就多用几个盒子。它与 §11 的 8KB 数据块同样无关。
- 操作码（OP Code）最常见：5.x 撤销相关、5.2 事务开始、5.4 事务提交、11.x 数据行增删改、10.x 索引叶子改动、13.x 空间分配、24.x DDL。

#### 6.4.3 日志流（redo stream）是什么

把所有在线日志、归档日志**按 sequence# 顺序首尾相接**，就得到一条**日志流（redo stream）**：

- 一个 Oracle 实例的 redo 是一个“**线程（thread）**”；RAC 里每个实例各有自己的 thread（thread# 不同）。
- 当前日志组写满 → **日志切换（Log Switch）** → ARCn 把写满的组**归档**成归档日志 → LGWR 接着写下一组。归档日志用更大的 sequence# 续接在在线日志之后，逻辑上仍是同一条流。
- 特征：**只追加、不可改写、物理顺序写**；在线部分循环复用（写满覆盖最旧的），归档部分按保留策略保存。
- **RESETLOGS 例外**：以 `RESETLOGS` 打开库（比如不完全恢复后）会把 sequence# **重置回 1**。所以 RBA 只在“同一 thread + 未 RESETLOGS”的范围内保证唯一——教材原话也强调这点。

#### 6.4.4 坐标体系（重点）：日志流里用什么给 redo 记录“定位”

你问的“日志流提供了什么样的坐标来供 redo 记录定位”，Oracle 给了**两套互相映射的坐标**：一套物理、一套逻辑。

**A. RBA（Redo Byte Address）—— 物理坐标：这条 redo 在“哪个文件的哪块哪字节”**

RBA 是一个 **3 元组**，标准二进制长度是 `4 + 4 + 2` 字节，**全部用 16 进制表示**：

```
RBA = ( log sequence# , redo block# , byte offset in block )
        └─4字节─┘   └──4字节─┘   └──2字节──┘
例如：0x000024.000011bd.0010
      = seq 36   , block 4541 , offset 16
```

- **sequence#**：这是第几个日志文件（在线/归档都按它排）；
- **block#**：在这个日志文件内部的第几个 redo 块；
- **byte offset**：在这个块内部，从第几个字节开始这条记录。

有了这三项，任意一条 redo 记录都能被**精确定位到磁盘上的一个字节起点**——这就是“门牌号”。日志流里几乎一切定位、重放、断点续传都靠它。

RBA 还有几个“派生坐标”，在恢复时各司其职（都在控制文件 / 内存里维护）：

| 派生 RBA | 含义 | 谁维护 / 在哪看 |
|---|---|---|
| **Low RBA**（脏块的） | 某个脏块“自上次干净以来第一条 redo”的地址 | 脏块挂在检查点队列上，按 low RBA 排序；`X$BH` |
| **High RBA**（脏块的） | 该脏块“最近一次改动”的 redo 地址 | `X$BH` |
| **Checkpoint RBA** | 增量检查点推进到的点 = 实例恢复的起点 | CKPT 每 3 秒心跳写入控制文件；`X$KCCCP`、`X$KCCRT` |
| **On-disk RBA** | LGWR 已把 redo 刷到在线日志的**最远点** = 恢复的终点 | `X$KCCCP`、`X$TARGETRBA` |
| **Target RBA** | DBWn 应把检查点推进到的目标（满足恢复时间目标） | `X$TARGETRBA` |
| **Recovery RBA / Sync RBA** | 恢复进度 / LGWR 需同步到的块号（sync 只用到 block#） | PMON / LGWR 内部 |

> 关键约束（理解“为什么提交必须先写 redo”）：**DBWn 不允许把“high RBA 超过 On-disk RBA”的脏块写盘**。因为“重做这次改动所需的 redo”和“撤销这次改动所需的 undo redo”在同一个 redo record 里——如果脏块先落盘、而它对应的 redo 还没被 LGWR 刷到在线日志（即 high RBA > On-disk RBA），一旦此刻崩溃，就没法用 redo 回滚这个脏块了。所以**“脏块落盘”必须晚于“对应 redo 落盘”**，这正是 §4 里 LGWR 在 COMMIT 时必须先把 redo 刷盘的根本原因。

**B. SCN（System Change Number）—— 逻辑坐标：这条 redo 发生在“哪个时点”**

- SCN 是 Oracle 内部的**全局单调递增时钟**，每个 redo 记录都带一个 SCN，标记“这次改动发生在哪个逻辑时点”。
- SCN 与 RBA **一一对应**：每个 RBA 对应一个 SCN，每个 SCN 在日志流里有确定的 RBA 位置。所以你既可以用 RBA 说“到文件哪”，也可以用 SCN 说“到时间哪”。
- 作用差异：**SCN 决定“重放的范围 / 时间边界”**（恢复到哪个 SCN 为止），**RBA 决定“从文件哪个物理位置精确开始重放”**。恢复时两者配合——先用 RBA 定位起点/终点物理位置，再按 SCN 递增顺序逐块重放。

一句话记忆：**RBA 是“门牌号”（物理位置），SCN 是“时间戳”（逻辑时点）；日志流里每个改动都同时带这两样，恢复/同步就靠它们精确导航。**

#### 6.4.5 这些坐标怎么被用于“定位 + 恢复 + 同步”

- **实例恢复（崩溃重启）**：SMON 从控制文件里的 **Checkpoint RBA**（起点）一路重放到 **On-disk RBA**（终点），按 redo 块号顺序、SCN 递增把 change vector 重做到数据块（前滚），再用 undo（本身也被 redo 保护）把未提交事务回滚。因为起点是“最近一次检查点”，所以只需重放一小段日志，恢复极快。
- **介质恢复（数据文件坏了）**：从备份还原，再按 **归档日志 + 在线日志**的 sequence#（即 RBA 的 sequence 维度）顺序重放，一直重放到指定的 **target SCN / target RBA**（时间点恢复）。
- **Data Guard / True Cache 同步（§7）**：备库/缓存节点的 RFS 进程按 **RBA** 接收主库发来的 redo，再按 RBA 顺序 apply 到数据块；Gap（缺口）则由 FAL 按缺失的 sequence# 补拉——本质就是“用 RBA 坐标在另一台机器上重放同一段日志流”。
- **脏块写出（DBWn）**：前面说的“high RBA ≤ On-disk RBA”约束，保证写盘动作永远有对应的已落盘 redo 兜底。

#### 6.4.6 实操：怎么观察日志流坐标

```sql
-- 1) 当前线程的日志流进度（sequence# 已写到哪、当前 SCN）
SELECT thread#, sequence#,
       TO_CHAR(resetlogs_time,'YYYY-MM-DD') AS resetlogs,
       status
  FROM V$THREAD;

-- 2) 当前在线日志组状态与 sequence#（CURRENT=正在写，对应 On-disk RBA 来源）
SELECT group#, sequence#, status, first_change# AS low_scn
  FROM V$LOG
 ORDER BY group#;

-- 3) 检查点推进情况（Checkpoint RBA 相关，DBA 用得最多）
SELECT name, value
  FROM V$SYSSTAT
 WHERE name IN ('checkpoint uptodate','physical writes non checkpoint');

-- 4) 底层坐标视图（CKPT/on-disk/target RBA，需 SYS，仅供深入排查）
SELECT cphbt, cpodr_seq, cpodr_bno, cpodr_bof, cpodr_scn
  FROM X$KCCCP;          -- cpodr_* = on-disk RBA；cphbt = 心跳
SELECT rtckp_scn, rtckp_seq, rtckp_bno, rtckp_bof
  FROM X$KCCRT;          -- 全线程检查点 RBA
```

小结：**Redo Log 文件 = 文件头块 + N 个 512B 重做块；每个重做块头部内嵌 RBA 三元组（sequence#/block#/offset）+ 校验和；一条 redo 记录 = 记录头(RBA/SCN/XID) + 若干 Change Vector；整条流按 sequence# 串成 redo stream；定位靠“RBA 物理门牌号 + SCN 逻辑时间戳”双坐标，二者一一对应，共同驱动实例恢复、介质恢复与 Data Guard 同步。**

---

## 7. Data Guard 与 True Cache：它们底层用了什么、区别在哪

这两个都是 Oracle 的“高可用 / 读扩展”家族成员，经常被一起提到，但**解决的问题完全不同**。它们都建立在你已经学过的 redo 机制之上（redo 记录的内部结构见第 6.3 节）。本节先分别介绍两者（7.1 / 7.2），再深入讲解**主库 LGWR 到它们的数据同步策略**（7.3–7.5），最后给出对比与选型（7.6–7.8）。

### 7.1 Data Guard（数据卫士）—— 解决“容灾”

**底层功能**：**重做日志传输（Redo Transport）+ 重做应用（Redo Apply）**。

- 主库 LGWR 把 redo 发送给备库；备库的 **RFS** 进程接收，写入 **Standby Redo Log**；备库的 **MRP（Managed Recovery Process）** 持续把 redo “应用”到自己的数据块上。
- 结果：备库的数据块与主库保持（近）实时一致。

**形态**：

| 类型 | 说明 |
|------|------|
| 物理备用库（Physical Standby） | 块级 redo 应用，与主库**字节级一致**，最常用 |
| 逻辑备用库（Logical Standby） | 把 redo 转成 SQL 再执行，备库可不同结构（用得少） |
| 快照备用库（Snapshot Standby） | 临时可写，用于测试，之后可回退继续做备库 |

**保护模式（RPO/RTO 权衡）**：Data Guard 有三档保护模式——最大性能（ASYNC）/ 最大可用 / 最大保护（SYNC），分别对应不同的 redo 同步策略、数据丢失风险与主库可用性。完整对比见第 7.4 节。

**Active Data Guard（ADG，11g+ 付费选项）**：物理备库在“只读打开”状态下仍能接收 redo 应用。于是主库专心写，报表/查询走备库读——这就是 Data Guard 顺便提供的“读扩展”能力。

### 7.2 True Cache（23ai 引入，26ai 增强）—— 解决“低延迟读缓存”

**底层功能**：**同样基于 Active Data Guard 的 redo 应用技术**，但定位完全不同——它是一个**内存中、几乎无盘（mostly diskless）的只读副本**，运行在**应用层/中间层、贴近应用**。

工作机制（来自官方文档与 23ai 博客）：

- 主库 LGWR 以 **ASYNC（默认，可切 SYNC，见 7.5）** 方式把 redo 发给 True Cache 节点，写入其 standby redo 日志；redo apply 持续把变更更新到 True Cache 的 **Buffer Cache**。
- 查询命中缓存 → 直接内存返回（极低延迟）；**缓存未命中** → 从主库拉取数据块并“预热”（顺带把周围块一起取来）。
- 缓存只保留**热数据（工作集）**，由 Oracle 自动管理淘汰与一致性，不需要应用写缓存失效逻辑。
- **透明重定向**：23ai 的 JDBC 驱动支持“一个逻辑连接”——应用对连接调用 `setReadOnly(true)`，驱动自动把只读请求路由到 True Cache，写请求仍走主库。传统外部缓存（Redis）需要应用自己管拓扑和失效。

> True Cache 并非完全无盘：它仍需少量存储放配置和 standby redo 日志文件，但**没有完整的数据文件副本**，数据主要在内存。

### 7.3 主库 LGWR 的数据同步策略：三个可调“旋钮”

无论是 Data Guard 备库还是 True Cache，**同步的单位都是 redo**，都由主库的 **LGWR（或归档进程 ARCn）** 把 redo 发出去，目标端用 **RFS** 进程接收、写入 **Standby Redo Log（备用重做日志，SRL）**，再由应用进程把 redo “应用”到数据块上。真正的差异，来自三个可以独立调整的“旋钮”：

**旋钮一：谁来发（传输进程）**

| 方式 | 谁把 redo 发给目标 | 特点 |
|------|-------------------|------|
| **LGWR** | 主库 LGWR 进程实时发（与写本地在线重做日志同一条链路） | 滞后最小（近实时）；**SYNC / 实时 apply 的唯一选择** |
| **ARC** | 在线日志写满/切换后，由归档进程 ARCn 把**已归档的日志文件**发过去 | 实现最简单，但滞后至少“一个日志切换周期”，通常秒级~分钟级 |

> LGWR 传输要求目标端配置了 **Standby Redo Log（SRL）**；ARC 传输不强制要求。

**旋钮二：要不要等确认（网络模式 SYNC / ASYNC）**

- **SYNC（同步）**：主库**必须**收到目标端“redo 已收到”的确认，这次事务才能在本地算提交成功。代价是每次提交多一个网络往返（≈ 2×RTT）。
- **ASYNC（异步）**：主库不等任何人，redo 在后台发出去，本地提交即时成功——性能零损耗，但目标端会滞后。

> True Cache 的 SYNC/ASYNC **不走** `LOG_ARCHIVE_DEST_n` 的 SYNC/ASYNC 属性，而是用 True Cache 节点上的 `TRUE_CACHE_CONFIG='redo_transport={sync|async}'` 设置（默认 async），详见第 7.5 节。

**旋钮三：确认到哪一步算数（AFFIRM / NOAFFIRM）**

- **AFFIRM（同步写盘确认）**：目标端必须把 redo **写到 SRL 磁盘文件**才算确认成功——最稳，目标端崩溃也不丢这段 redo。
- **NOAFFIRM（收到即确认，又称 FASTSYNC）**：目标端 redo **落到内存**就立刻确认，不必等 fsync——更快，但目标端若在“内存→磁盘”之间崩溃，有一丝丢失窗口。

**缺口补齐（FAL，Fetch Archive Log）**
目标端因网络/重启丢了中间几段日志时，会通过 **FAL** 机制按需从主库（或另一个备库）“补拉”缺的归档日志，补齐后自动继续 apply。

> 记忆法：**LGWR/ARC 决定“谁发”，SYNC/ASYNC 决定“要不要等”，AFFIRM/NOAFFIRM 决定“等到什么程度”。** 三者组合 + 主库保护模式，就得到下面 7.4 的三档。

### 7.4 Data Guard 的三种保护模式（三个旋钮的组合结果）

Data Guard 把上面的旋钮固化成三档“保护模式”（`ALTER DATABASE SET STANDBY DATABASE TO MAXIMIZE {PROTECTION | AVAILABILITY | PERFORMANCE}`）：

| 维度 | 最大保护 Maximum Protection | 最大可用 Maximum Availability | 最大性能 Maximum Performance（默认） |
|------|------------------------------|--------------------------------|--------------------------------------|
| 传输进程 | LGWR | LGWR | LGWR 或 ARC |
| 网络模式 | SYNC | SYNC | ASYNC（LGWR 时）/ SYNC（ARC 时） |
| 落盘确认 | AFFIRM | AFFIRM（可 NOAFFIRM） | AFFIRM 或 NOAFFIRM |
| 需要 Standby Redo Log | **必须** | 强烈建议 | 否（建议有） |
| 数据丢失风险（RPO） | **零丢失** | 正常时零丢失；备库/网络故障期间提交的可能丢 | 通常几秒（异步缓冲） |
| 备库不可达时主库行为 | **阻塞提交、最终自动关闭** | **不关闭**，等 `NET_TIMEOUT` 后临时降级为最大性能继续跑 | 完全不受影响 |
| 对主库性能影响 | 大（每次提交等备库确认） | 中（健康时等确认，故障时放开） | 几乎为零 |
| 典型场景 | 金融/监管，强契约零丢失 | 主流企业生产，零丢失 + 保主库可用 | 一般业务，可容忍秒级丢失 |

逐一说明优缺点：

**最大保护（Maximum Protection）**
- ✅ 唯一能“绝对零数据丢失”的模式——主库事务必须等**至少一个同步备库把 redo 写盘确认**才提交。
- ❌ 一旦所有同步备库不可达，主库**拒绝新提交并最终自动关闭**（防止“无保护提交”），可用性让位于数据安全。对网络要求极高，最佳实践是**配 2 个以上同步备库**（或 Far Sync），避免“单备库失联=主库停摆”。

**最大可用（Maximum Availability，最常用）**
- ✅ 健康时同样零数据丢失；备库/网络出问题时主库**不关闭**，最多等 `NET_TIMEOUT` 秒，随后临时降级到最大性能继续服务，故障恢复后自动重新同步。
- ❌ 降级窗口内若主库也故障，那段时间的提交可能丢失（需盯 `protection_level` 告警并尽快恢复备库）。跨 WAN 要零丢失可叠加 **Far Sync** 实例就近做同步确认点。

**最大性能（Maximum Performance，默认）**
- ✅ 主库提交只等**本地在线重做日志写完成**，性能和可用性与“没有备库”时完全一样。
- ❌ 灾难时可能丢失“已提交但还没发到备库”的那几秒数据。可接受秒级 RPO 的绝大多数业务用这个。

配置与观测示例：
```sql
ALTER SYSTEM SET LOG_ARCHIVE_DEST_2='SERVICE=chicago LGWR SYNC AFFIRM
  VALID_FOR=(ONLINE_LOGFILES,PRIMARY_ROLE) DB_UNIQUE_NAME=chicago';
ALTER DATABASE SET STANDBY DATABASE TO MAXIMIZE AVAILABILITY;

-- 查看实际生效的传输模式 / 确认方式 / 保护级别
SELECT dest_id, transmit_mode, affirm FROM V$ARCHIVE_DEST_STATUS;
SELECT database_role, protection_mode, protection_level FROM V$DATABASE;
```

### 7.5 True Cache 的同步策略：默认 ASYNC，但可选 SYNC（26ai 起）

先修正一个容易以讹传讹的说法：**True Cache 的重做传输并非“只有 ASYNC”**。它由主库 LGWR 发送 redo，传输模式可在 **`async`（默认）** 与 **`sync`** 之间选择，通过 True Cache 节点上的**静态**初始化参数 **`TRUE_CACHE_CONFIG`** 配置（DBCA 不能配，改后需重启 True Cache 才生效）：

```sql
-- 在 True Cache 节点上执行（静态参数，需重启生效）
ALTER SYSTEM SET TRUE_CACHE_CONFIG='redo_transport={sync | async}' APPEND SCOPE=SPFILE;
```

- **async（默认）**：主库事务提交**不等** True Cache 收到 redo，提交最快，True Cache 通常 sub-second 滞后。
- **sync**：主库事务提交前**要等** True Cache 确认收到 redo——可**降低读到 True Cache 的滞后**，但**提交会变慢**（每次提交多一次确认往返）。

**为什么默认是 async，什么时候才考虑 sync**
- True Cache 的定位是“低延迟读缓存 / 读扩展”，不是灾备副本。默认 async 让**主库提交零额外延迟、可用性不受缓存节点影响**，这正是它相对自建缓存的核心优势。
- 只有当业务“写完之后要尽快在缓存里读到”、且能接受主库提交变慢时，才值得切 sync。
- ⚠️ 关键区别：即便切到 sync，True Cache **依然是只读、几乎无盘、不可提升为主库**的缓存（`DATABASE_ROLE=TRUE CACHE`）——它**不会**因此变成 Data Guard 那种可故障切换的同步备库。要“零数据丢失的灾备”，仍应靠 Data Guard 的最大可用/最大保护，而不是把 True Cache 设成 sync。

机制与约束：
- 主库 `LOG_ARCHIVE_DEST_n` 指向 True Cache，目标 `ROLE=TRUE_CACHE`；节点 `OPEN_MODE=READ ONLY WITH APPLY`、`DATABASE_ROLE=TRUE CACHE`（可用 `V$DATABASE` 核对）。
- 写会被硬拦截：在 True Cache 上发 DML 直接报 `ORA-16000`（只读），无需应用层改造。
- redo 滞后超阈值会告警（如 `ORA-61877`），运维需前置配置；必要时可切 sync 压低滞后。
- 缓存未命中 → 回主库拉块并“预热”周围块；之后靠 redo apply 持续更新。
- 版本提示：`sync`/`async` 可选及 `TRUE_CACHE_CONFIG` 配置见 **26ai** 官方文档（*Setting Redo Transport Mode in True Cache*）；23ai 资料中普遍只描述 LGWR ASYNC（默认）这一种行为。

**True Cache 同步的优缺点**
- ✅ 传输模式可选：默认 async 不拖累主库，sync 可压低读滞后；天然支持多实例横向扩展读。
- ❌ 只读、不可提升为主库；默认 async 有亚秒滞后，sync 又会拖慢主库提交；只覆盖热数据（工作集），冷数据/全表扫描仍回源主库；需要对应版本 + JDBC 驱动。

### 7.6 对比表：Data Guard vs True Cache

| 维度 | Data Guard（物理备库） | True Cache（23ai/26ai） |
|------|----------------------|-------------------|
| 底层技术 | Redo Transport + Redo Apply（MRP） | 同样的 redo apply，但作用于内存 buffer cache |
| 数据范围 | **整库完整副本**（全部数据文件） | 仅**热数据工作集**（内存能装下的部分） |
| 是否占磁盘 | 是，需要近乎等量的存储 | 几乎无盘，少量配置/redo 日志 |
| 一致性 | 强一致、可零数据丢失（SYNC） | 默认 async：sub-second 滞后；可选 sync 压低滞后（但仍是不可切换的只读缓存） |
| 主要用途 | **灾难恢复 / 高可用**（RPO/RTO） | **读扩展 / 低延迟缓存**（替代应用层 Redis 类方案） |
| 部署位置 | 独立服务器/机房（异地容灾） | 贴近应用的中间层/同机柜 |
| 能否写 | 备库默认只读（ADG 读；DML 重定向可转发主库） | 只读；`ADG_REDIRECT_DML` 可把写转回主库 |
| 实例数量 | 一主 + 若干备 | 一主 + 多个 True Cache 实例横向扩展读 |
| 许可 | 需 Active Data Guard 选项（付费） | 23ai 中随企业版提供（具体以你的许可条款为准，部署前务必确认） |

### 7.7 优缺点

**Data Guard 优点**：
- 真正的灾难恢复，可做到零数据丢失，主库挂了能切备库继续服务。
- ADG 还能顺带做读扩展。

**Data Guard 缺点**：
- 需要近乎等量的第二份存储与独立硬件，成本高。
- 切换（failover/switchover）有 RTO，管理复杂（尤其 SYNC 模式对网络敏感）。
- 它是“一整个物理库”，不能只为“部分热表”省资源。

**True Cache 优点**：
- 无需改应用代码（JDBC 透明重定向），自动保持一致性，不需要手写缓存失效。
- 内存级低延迟，把读流量从主库卸载，主库 buffer cache 不再被报表/查询反复冲刷。
- 可部署多个实例，按读流量横向扩展。

**True Cache 缺点**：
- 主要是内存，只覆盖热数据；冷数据/全量扫描仍会回源主库。
- 只读，且相对主库有 sub-second 滞后（不适合“必须读刚写完的数据”的场景——那种该走主库）。
- 需要 23ai + 对应 JDBC 驱动；仍以你的具体许可为准。

### 7.8 使用场景怎么选

- **怕机房着火、怕主库宕机丢数据、有 RPO/RTO 合规要求** → 上 **Data Guard**（ADG 兼顾容灾+读扩展）。
- **读远多于写、想要低延迟读、正打算用 Redis/memcached 给 Oracle 前面加缓存** → 考虑 **True Cache**（替代自建缓存层，一致性由数据库托管）。
- **两者可叠加**：Data Guard 管容灾，True Cache 管应用层读加速，互不冲突。
- 简单判断：**Data Guard 是“买一份保险”，True Cache 是“装一个加速器”**。

---

## 8. Shared Pool 满了是怎么回事 + 日常写 SQL 的正确姿势

你特别提到“Share Pool 满了是什么原因”。先纠正一个常见误解：**Shared Pool “满”几乎从来不是“内存真的不够”，而是“被不该留的东西塞满 / 碎片化导致分配不出连续内存”**，典型报错是 **`ORA-04031: unable to allocate N bytes of shared memory`**。

### 8.1 Shared Pool 里到底有什么

- **Library Cache**：SQL/PLSQL 的**解析树 + 执行计划**。相同 SQL 文本第二次来，直接复用（软解析），省去昂贵的硬解析。
- **Data Dictionary Cache**：数据字典（表/列/权限定义）的缓存，解析 SQL 时要频繁查它。

### 8.2 “满/报 ORA-04031”的真正原因

头号元凶：**没有使用绑定变量（bind variables），导致大量硬解析**。

```
❌ 反例：每次都用字面量拼 SQL
UPDATE orders SET status='PAID' WHERE id=1001;   -- 一条
UPDATE orders SET status='PAID' WHERE id=1002;   -- 又一条（文本不同！）
UPDATE orders SET status='PAID' WHERE id=1003;   -- 又一条（文本又不同！）
```

对 Oracle 而言，`id=1001`、`id=1002`、`id=1003` 是**三条完全不同的 SQL 文本**，每条都要：
1. 硬解析（语法/语义/权限/生成执行计划）—— 吃 CPU；
2. 在 Library Cache 里**新占一块内存**存它的游标；
3. 这条游标往往只被执行一次就再也用不上，却一直占着共享池。

高并发下成千上万条“一次性游标”堆积 → 共享池被占满/碎片化 → 新的硬解析申请不到连续内存 → **ORA-04031**。同时还会引发 **latch 争用**（shared pool latch / library cache latch），CPU 飙升，系统雪崩。

### 8.3 日常写 SQL 应该怎么写（✅ 正确姿势）

| 做法 | 说明 |
|------|------|
| ✅ **用绑定变量** | `UPDATE orders SET status=:st WHERE id=:id;` 一条 SQL 文本被百万次调用复用，只解析一次 |
| ✅ **写可共享的 SQL** | 相同文本 → 软解析复用执行计划；避免为了“不同值”拼出新 SQL |
| ✅ **避免对列包函数** | `WHERE TRUNC(created)=:d` 会让索引失效 → 改成 `WHERE created >= :d AND created < :d+1`，走索引 |
| ✅ **避免隐式类型转换** | 列是 NUMBER 就传数字，别传字符串让 Oracle 偷偷转，会失效引全表扫 |
| ✅ **别用 `SELECT *`** | 指定需要的列：减少解析/网络/内存，且利于索引覆盖 |
| ✅ **批量操作** | 用 `FORALL` / 数组批量绑定，一次解析多次执行，大幅减少硬解析次数 |
| ✅ **用完关游标 / 合理连接池** | 防止游标泄漏（cursor leak）慢慢吃光共享池 |
| ✅ **用 `RETURNING` 子句** | `UPDATE ... RETURNING balance INTO :b` 一次搞定“改+取结果”，省一次查询 |

### 8.4 不应该写成什么样子（❌ 禁忌）

- ❌ **拼接 SQL 字符串**：`"UPDATE t SET v=" + value`（每参数一条新 SQL，硬解析炸弹）。
- ❌ **在循环里反复执行不同字面量的 SQL**：同上，游标爆炸。
- ❌ **WHERE 列上套函数 / 做运算**：索引失效，全表扫描拖垮整体。
- ❌ **不加条件的全表扫描** + 返回所有列：Buffer Cache 被冲垮，连带影响别人。
- ❌ **超长动态 SQL / 大量 `DBMS_SQL` 反复解析**：同样加重 Library Cache 负担。

### 8.5 已经“满”了怎么应急排查

```sql
-- 1) 找出“硬解析多 / 版本多”的 SQL
SELECT sql_id, executions, parse_calls, version_count, sql_text
  FROM V$SQLAREA
 WHERE parse_calls > 1000
 ORDER BY parse_calls DESC;

-- 2) 看 Library Cache 命中率（应接近 100%）
SELECT * FROM V$LIBRARYCACHE;   -- 关注 get命中率/hit ratio

-- 3) 临时缓解（治标不治本，需配合改 SQL）：
ALTER SYSTEM FLUSH SHARED_POOL;   -- 清空共享池，立刻释放碎片（会瞬间丢失所有执行计划，生产慎用）

-- 4) 应急参数（仅临时过渡，不推荐长期）：
ALTER SYSTEM SET CURSOR_SHARING=FORCE SCOPE=BOTH;  -- 强制把字面量当绑定变量（可能改变执行计划，需评估）
```

**根本解决永远是：改应用、上绑定变量、消灭硬解析炸弹。**

---

## 9. 整体串讲：把上面所有东西连成一张全景图

把全文浓缩成“一个请求的一生”，你就拥有了对 Oracle 的整体心智模型：

```
1. 客户端连上来 → 产生一个【服务器进程】（前台），分配一份私有【PGA】。
2. 服务器进程去【SGA 的 Shared Pool】找 SQL 执行计划（没有就硬解析并缓存）。
3. 执行时去【Database Buffer Cache】取数据块（没有就从数据文件物理读入）。
4. 用【SCN + UNDO】做一致性读，保证“读不阻塞写、写不阻塞读”，绝无脏读。
5. 修改在内存里进行：① 记【UNDO 前像】② 改数据块 ③ 写【REDO 变更向量】到 Redo Log Buffer ④ 加行锁。
6. 提交时【LGWR】把 redo 刷进在线重做日志（持久化保证），释放行锁。
7. 【DBWn】之后异步把脏块写回数据文件；【CKPT】同步 SCN；【ARCn】归档 redo。
8. 主库的 redo 通过【Redo Transport】发给备库（Data Guard 容灾）或【True Cache】（内存只读缓存），实现读扩展。
9. 全程【PMON/SMON】兜底清理失败进程、做崩溃恢复。
```

**一句话设计哲学**：Oracle 用“**内存缓存 + redo 顺序写保持久 + undo 支持回滚与多版本 + SCN 统一时间观**”这套组合，把“随机写盘”和“并发一致性”这两件最难的事解耦，再用后台进程异步收尾，从而同时拿到高并发、高可用和可恢复性。

作为初学者，你只要记住五句话就抓住了主干：
1. **数据库是文件，实例是运行时（内存+进程）。**
2. **SGA 是公共黑板，PGA 是你自己的草稿纸。**
3. **LGWR 保持久（提交必写 redo），DBWn 懒落盘（脏块异步写）。**
4. **读不阻塞写、写不阻塞读，靠的是 SCN + UNDO 的一致性读。**
5. **SQL 一定要用绑定变量，否则 Shared Pool 会被硬解析炸穿。**

---

## 10. 数据存储层次：表空间 / 段 / 区 / 块（逻辑存储结构）

> 你记得没错，但差最上面一层。Oracle 的数据存储是**“4 层逻辑结构”**：**表空间（Tablespace）→ 段（Segment）→ 区（Extent）→ 块（Block）**。你提到的 Segment、Extent、Block 占了其中三层，**最上面那层是“表空间”**；再往下还有“行（Row）”坐在块里（属于块的子结构），最底下再落到物理的数据文件 / 操作系统块。

### 10.1 先给一张“从大到小”的全景图

```
Database 数据库（整个库，见第 2 节）
 └─ Tablespace 表空间       ← 逻辑“空间容器”（如 USERS / UNDOTBS1）
      └─ 由 Data File 数据文件承载（.dbf）   ← 物理映射
           └─ Segment 段       ← 一个对象一个段（一张表=1 表段，一个索引=1 索引段）
                └─ Extent 区    ← 一组“连续的数据块”，空间分配/回收的最小单位
                     └─ Block 块  ← 8KB，I/O 最小单位（内部结构见第 11 节）
                          └─ Row 行   ← 一条记录，含多列（坐在块里）
                               └─ Column 列
```

一句话记忆：**“库里有空间，空间里装段，段里分了区，区里排满块，块里塞着行。”**

严格说，**“4 层逻辑结构（表空间→段→区→块）”管的是“空间怎么分配”，而“行”是块内部的下一级内容，不单独算一层**。论文/考试里说的“Oracle 存储 4 层”就是指这 4 层。

### 10.2 第 1 层：表空间（Tablespace）—— 逻辑空间的“容器”

表空间是 Oracle 里**空间分配的最大逻辑单位**，是“逻辑结构”与“物理结构”的交界面。

**（1）它里面包含什么 / 是什么**
- **逻辑上**：一个数据库包含多个表空间（系统自带的 `SYSTEM`、`SYSAUX`、回滚用的 `UNDO`、排序用的 `TEMP`、用户数据 `USERS` 等）。
- **物理上**：一个表空间由**一个或多个数据文件（Data File，`.dbf`）**承载。
- **关键属性（元数据）**：每个表空间自身记录着——块大小（`BLOCKSIZE`，BIGFILE/多块大小表空间可不同）、区管理方式（DMT / LMT）、段空间管理方式（MSSM / ASSM）、状态（`ONLINE` / `READ ONLY` / `OFFLINE`）、内容类型（`PERMANENT` / `TEMPORARY` / `UNDO`）、保留策略（`RETENTION`）等。这些就是你用 `DBA_TABLESPACES` 能查到的字段。

**（2）类型分类（重点）**
- **按内容类型分**：
  - `PERMANENT`（永久表空间，存业务表/索引等永久对象）—— 绝大多数用户表空间属于此类；
  - `TEMPORARY`（临时表空间，如 `TEMP`，只存排序、哈希、全局临时表产生的**临时段**，不参与 redo/恢复）；
  - `UNDO`（回滚表空间，如 `UNDOTBS1`，**只存回滚/UNDO 段**，见下方专项）。
- **按文件组织分**：
  - `SMALLFILE`（传统，1 个表空间可挂多个数据文件，默认）；
  - `BIGFILE`（单数据文件，容量上限极大，便于管理但备份恢复粒度变粗、对磁盘单点更敏感）。
- **按管理方式分**：
  - 区管理：`LMT` 本地管理（用**位图**记录区使用状况，现代默认）/ `DMT` 字典管理（用数据字典表，已基本弃用）；
  - 段空间管理：`ASSM` 自动（用位图管理块空闲，默认）/ `MSSM` 手工（用 freelist，需设 `PCTUSED`）。
- **系统自带 vs 用户自建**：`SYSTEM`（数据字典、引导核心）、`SYSAUX`（辅助，AWR/组件元数据）、`UNDOTBS1`、`TEMP` 为系统级；`USERS` 及自建表空间放业务数据。

**（3）专项：UNDO 表空间（你特别点名的）**
- **定位**：专用于存放**回滚段（undo 段）**的表空间，由「自动撤销管理 AUM」使用。它**只能放 undo，不能建普通表/索引**。
- **关键参数**：`UNDO_MANAGEMENT=AUTO`、`UNDO_TABLESPACE=UNDOTBS1`、`UNDO_RETENTION`（前像保留时长，影响闪回能力，也直接关系 ORA-01555 是否发生，见 §5.5.6）。
- **与 redo 的协同**：undo 块本身的修改会被 redo 保护（§6.3），所以 undo 不会因崩溃丢失；undo 又被「回滚 / 一致性读 / SMON 崩溃恢复 / 闪回」四类场景消费（§5.5.5）。
- **容量规划**：要能容纳「最长事务」+「最长查询」期间产生的 undo 总量；不足时会出现 `ORA-01555` 或事务因申请不到 undo 而失败。

**（4）作用（它解决什么问题）**
- **空间与 I/O 隔离**：把热点表、索引、undo、临时数据放进不同表空间，避免互相挤占、便于分散到不同磁盘。
- **备份恢复单位**：可做「表空间时间点恢复（TSPITR）」，只恢复单个表空间而不动全库。
- **配额与管控**：对用户/对象授予表空间配额（`QUOTA`），结合 `DEFAULT TABLESPACE` 控制对象落点。
- **与 Schema 正交**（呼应第 2 节）：表空间是“库内部的逻辑存储层”，Schema 是“对象的归属层”。**一个用户的表可以跨多个表空间；一个表空间也容纳多个用户的对象。** 建用户时 `DEFAULT TABLESPACE` 决定对象默认落点。

> 关键关系：**一个表空间可以有多个数据文件；一个数据文件只能属于一个表空间**——所以“表空间 ≠ 数据文件”，前者是逻辑盒子，后者是盒子里真实的磁盘文件。

### 10.3 第 2 层：段（Segment）—— “一个对象一个段”

段是**由某个数据库对象拥有的一组区（extents）**，是**对象级**的空间分配单位。

**（1）它里面包含什么**
- 段 = 一组**区（extent）**的集合；段的“头部”是个特殊的**段头块（Segment Header Block）**，记录了该段的区图（extent map）、空闲空间图等元数据。
- 段的元数据集中在数据字典视图 **`DBA_SEGMENTS`**（段名、类型、所属表空间、区数、字节数等）。

**（2）类型分类（重点，Oracle 的段类型远不止“表/索引”）**
| 段类型（SEGMENT_TYPE） | 说明 |
|---|---|
| **TABLE** | 普通堆表段（一张堆表 = 1 个表段） |
| **INDEX** | 索引段（B-Tree 的叶子+分支都在此段内） |
| **TABLE PARTITION / INDEX PARTITION** | 分区表 / 分区索引的**每个分区是独立段** |
| **CLUSTER** | 簇段（多张表按簇键共享存储，较少用） |
| **LOBSEGMENT** | 大对象（CLOB/BLOB）的**数据主体段** |
| **LOBINDEX** | LOB 的定位索引段（定位 LOB 块） |
| **LOB PARTITION** | 分区 LOB 的各个分区段 |
| **INDEX ORGANIZED TABLE (IOT)** | 索引组织表；溢出数据在 **IOT_OVERFLOW** 段 |
| **TEMPORARY** | 临时段（排序 / 哈希 / 建索引 / 全局临时表时产生，用完即释放） |
| **ROLLBACK / TYPE2 UNDO** | 回滚段 / UNDO 段（存前像，支撑回滚与一致性读，见 §5.5） |
| **NESTED TABLE** | 嵌套表段（对象-关系类型） |
| **MATERIALIZED VIEW / MATERIALIZED VIEW LOG** | 物化视图段 / 物化视图日志段 |
| **BOOTSTRAP / CACHE** | 引导段（数据库自举时最核心的字典对象所在） |

**（3）作用（它解决什么问题）**
- **对象级空间边界**：段是 Oracle “分配 / 回收空间”的边界单位，也是 DBA 管理（扩容、 shrink、move）的抓手。
- **澄清「1 张表 ≠ 1 个段」**：一张普通堆表 = 1 表段，但**表上的每个索引是独立索引段**、**每个分区是独立段**、**LOB 字段额外产生 LOB 数据段 + LOB 索引段**、**IOT 还有溢出段**——所以“建一张带索引和 LOB 的分区表”可能对应十几个段。
- **增长方式**：当现有区放不下新数据时，Oracle 给该段**再分配一个新的区（extent allocation）**（区的分配策略见 §10.4）。
- **空间归属**：段只存在于某个表空间内，但可横跨该表空间下的**多个数据文件**（数据文件都属于同一表空间）。

> **Undo/回滚段**是最特殊的一类：它只存在于 UNDO 表空间（§10.2），其 undo 记录内部字段（XID/UBA/OPCODE/前像值/双向指针）在 §5.5 详拆。

### 10.4 第 3 层：区（Extent）—— “连续的数据块组”

区是**一段逻辑连续的数据块（data blocks）的集合**，是 Oracle **分配和回收空间的最小单位**（一次至少分配/释放一个区）。

**（1）它里面包含什么**
- 一个区 = 若干**逻辑连续的数据块**（块数、总大小由存储参数 / 表空间策略决定）。`DBA_EXTENTS` 能看到某段的每个区落在哪个文件（`FILE_ID`）、从哪个块开始（`BLOCK_ID`）、占多少块（`BLOCKS`）。

**（2）类型分类（重点）**
- **按分配时机分**：
  - **Initial Extent（初始区）**：创建对象（建表/建索引）时首次分配的第一个区，大小由 `INITIAL` 决定。
  - **Next Extent（后续区）**：对象数据增长时，后续每次分配的新区，大小受 `NEXT` / 表空间策略影响。
- **按表空间分配策略分**（由表空间的区管理决定，见 §10.2）：
  - **UNIFORM（统一大小）**：每个区固定大小（如 1MB），好预测、碎片少，适合已知负载；
  - **AUTOALLOCATE（自动，默认）**：Oracle 从小到大按需分配（阶梯 64KB → 1MB → 8MB → 64MB…），更省空间但可能略多碎片。
- **按“使用状态”分**（在 LMT 位图 / 字典中标记）：
  - **FREE（空闲区）**：尚未分配给任何段，可被未来分配复用；
  - **USED（已用区）**：已分配给某段、正在承载数据。

**（3）作用（它解决什么问题）**
- **降低分配开销**：一次给“一整段连续块”，而不是每次来一行就分配一个块，避免频繁向数据字典/位图申请空间。
- **空间管理粒度**：区是“分配/回收”的原子单位；回收空间（如 `TRUNCATE`、段 shrink）也是按区进行的。
- **连续性的好处**：区内块连续 → 全表扫描等顺序读时，I/O 更顺序、更高效（减少磁头跳跃）。

> 易混点：**区（Extent）和块（Block）是两个不同层级**——区是“空间分配单位”（一次给一段连续块），块是“I/O 单位”（一次读写一个块）。Oracle 分配空间时按“区”给，真正读写数据时按“块”走。这也带来一句名句：**“区内块连续，段内区零散”**。

### 10.5 第 4 层：块（Block）—— “I/O 最小单位”，行就坐在块里

块是 Oracle **读写的最小单位**（一次读/写至少操作一个块），默认 **8KB**（由 `DB_BLOCK_SIZE` 决定，可选 2K/4K/16K/32K；不同表空间可用不同 `BLOCKSIZE`）。

**（1）它里面包含什么**（与 §11 衔接）
- 一个块 = 块头（Block Header）+ ITL 事务槽 + 表目录 + 行目录 + **行数据区** + 空闲空间（受 `PCTFREE`/`PCTUSED` 控制）+ 块尾校验（Tailchk）。**块的完整内部结构在第 11 节专门拆解**。
- 块里装的是**行（Row）**：一条记录占用块内一行；块头还有 ITL、行目录等元数据（一致性读/行锁都建立在这一层，见 §5、§11）。

**（2）类型分类（按块的“用途 / 类型字节 typ”分）**
| 块类型（typ） | 用途 |
|---|---|
| **数据块（表数据块）** | 存表行数据（堆表/IOT 叶子等） |
| **索引块** | 存 B-Tree 索引的分支/叶子（typ 与数据块不同，含键+ROWID） |
| **UNDO 块** | 存 undo 记录（typ 对应 undo，内部结构见 §5.5） |
| **段头块（Segment Header）** | 段的“总管”，记录区图/空闲位图（段的第一块） |
| **LMT 管理块** | 本地管理表空间的**区位图块**、**数据文件头** |
| **ASSM 位图块（BMB）** | 自动段空间管理的**块空闲位图**（三级位图） |
| **临时块 / 空闲·格式化块** | 临时段块、尚未使用的空闲块 |

> 第 11 节块头的 `typ` 字段就是上表的“类型字节”，dump 一个块即可看到它是表块、索引块还是 undo 块。

**（3）作用（它解决什么问题）**
- **I/O 单位**：所有“读/写/缓存”都以块为单位（Buffer Cache 缓存的也是块，见 §3）；块大小直接影响 I/O 次数与缓存效率。
- **行的容器**：真实数据（行）坐在块里；块内空间管理靠 `PCTFREE`（预留给将来 `UPDATE`，防行迁移）/`PCTUSED`（空到什么程度才允许再插入）。
- **行迁移（Row Migration）/ 行链接（Row Chaining）**：当一行更新变长塞不进原块，或被 UPDATE 撑大，Oracle 把整行搬到新块（迁移）或拆成多块串起来（链接）——额外一次 I/O，性能上要留意；`PCTFREE` 设得合理可缓解迁移。

> 严格说：**“4 层逻辑结构（表空间→段→区→块）” + “块内的行（子结构）”**。论文/考试里说的“Oracle 存储 4 层”就是指这 4 层；行是块的下一级内容，不单独算一层。

### 10.6 四者之间的关系 + 物理对照

**（1）层级关系全景（谁包含谁）**

```
Database 数据库
 └─ Tablespace 表空间（逻辑容器，1 对 N 数据文件）        ← 第 10.2 层
      └─ 由 Data File 数据文件承载（.dbf，物理）          ← 逻辑↔物理交界
           └─ Segment 段（一个对象 = 一组区）            ← 第 10.3 层
                └─ Extent 区（一组连续块）               ← 第 10.4 层
                     └─ Block 块（8KB，I/O 最小单位）     ← 第 10.5 层
                          └─ Row 行（坐在块里，块子结构） ← 真实数据
```

**（2）五个必须记住的“关系”澄清**
- **表空间 = 逻辑，数据文件 = 物理承载**：1 个表空间 : N 个数据文件；1 个数据文件 **只属 1 个**表空间。所以「段/区/块」最终都住在数据文件里，但只在“表空间”这一层做逻辑管理。
- **段不能跨表空间，但可跨数据文件**：一个段的所有区都属于同一表空间；当该表空间挂了多个数据文件时，这个段的**不同区可以落在不同文件**里。
- **「区内块连续，段内区零散」**：区内部的块在物理上挨着（顺序 I/O 友好）；但一个段的多个区之间不必连续，可以散落各处（甚至跨文件）。这是“区连续、段零散”的由来。
- **区是“分配单位”，块是“I/O 单位”**：两个不同层级——Oracle 按“区”向段给空间，按“块”真正读写数据。
- **行是块的子结构，不单独算一层**：严格说“4 层逻辑（空间/段/区/块）管分配，行坐块里管数据”。

**（3）逻辑 ↔ 物理对照表**

| 逻辑结构（你用 SQL/视图管理） | 物理结构（真正落在磁盘） |
|---|---|
| 表空间 Tablespace | 一个或多个数据文件 Data File（.dbf） |
| 段 Segment | （不单独对应文件，散落在所属表空间的数据文件里） |
| 区 Extent | 数据文件里连续的一段块 |
| 块 Block（8KB） | 操作系统块 / 磁盘扇区的上层封装 |
| 行 Row | 块内偏移 |

**（4）一条记录的落点路径**
`Schema 对象 → 段 → 某个区 → 某个块 → 块内某行`。被修改时走第 6 节（服务器进程在 Buffer Cache 改块 + 写 redo）；被查询时走第 5 节（基于 SCN 的一致性读）。数据文件就是表空间的物理承载（第 2 节：数据库物理文件 = 数据文件 + 控制文件 + 重做日志文件）。

### 10.7 和本文其他章节的呼应

- **第 2 节**（数据库/实例/Schema）：表空间是“库内部的逻辑存储层”，介于“整个数据库”和“具体对象”之间；它与 Schema 正交（见 10.2）。
- **第 3 节** Buffer Cache：缓存里放的就是这里的**最底层 Block（8KB 数据块）**。
- **第 5 节** 一致性读 / ITL：块头里的 ITL 槽、行头的 LB 都建立在“块→行”这一层（第 11 节细拆）。
- **第 11 节**（8KB 块）：把本节的“Block”再往下拆成块头/ITL/行目录/行数据/块尾。

### 10.8 常用排查 SQL（看清“段→区→块”的占用）

```sql
-- 1) 各表空间总大小（来自物理数据文件）
SELECT tablespace_name,
       ROUND(SUM(bytes)/1024/1024) AS total_mb
  FROM DBA_DATA_FILES
 GROUP BY tablespace_name;

-- 2) 某个用户拥有的段，以及它们占了多少区、多少 MB
SELECT segment_name, segment_type,
       COUNT(*)            AS extents,
       ROUND(SUM(bytes)/1024/1024) AS mb
  FROM DBA_SEGMENTS
 WHERE owner = 'TOM'
 GROUP BY segment_name, segment_type;

-- 3) 一个段由哪些区组成（落在哪个文件、从哪个块开始、占几块）
SELECT file_id, block_id, blocks
  FROM DBA_EXTENTS
 WHERE segment_name = 'ORDERS' AND owner = 'TOM'
 ORDER BY block_id;

-- 4) 表空间里还剩多少空闲（空闲区视图）
SELECT tablespace_name,
       ROUND(SUM(bytes)/1024/1024) AS free_mb
  FROM DBA_FREE_SPACE
 GROUP BY tablespace_name;
```

### 10.9 速查总结表：四层逻辑结构一图对照

| 层级 | 一句话定义 | 管理/分配单位 | 物理对应 | 常见类型（§10.x） | 核心作用 | 主要数据字典视图 |
|---|---|---|---|---|---|---|
| **表空间 Tablespace** | 空间分配的最大逻辑容器 | 由 DBA 管理（创建/扩容） | 1 对 N 数据文件 `.dbf` | PERMANENT / TEMPORARY / **UNDO**；SMALLFILE / BIGFILE；LMT / DMT；ASSM / MSSM（§10.2） | 空间与 I/O 隔离、备份恢复单位(TSPITR)、配额管控 | `DBA_TABLESPACES`、`DBA_DATA_FILES` |
| **段 Segment** | 一个对象拥有的一组区 | 对象级（随对象建/删） | 散落在所属表空间的数据文件里 | TABLE / INDEX / 分区段 / LOB 段 / **UNDO 段** / TEMPORARY / IOT_OVERFLOW / 物化视图 等（§10.3） | 对象级空间边界，扩容/shrink 抓手 | `DBA_SEGMENTS` |
| **区 Extent** | 一组连续的数据块 | 空间分配/回收的最小单位 | 数据文件里连续的一段块 | Initial / Next；UNIFORM / AUTOALLOCATE；FREE / USED（§10.4） | 降低分配开销、顺序 I/O 友好 | `DBA_EXTENTS` |
| **块 Block** | 读写的 I/O 最小单位（默认 8KB） | I/O 单位 | OS 块的上层封装 | 数据块 / 索引块 / **UNDO 块** / 段头块 / LMT 管理块 / ASSM 位图块（§10.5） | 行容器、缓存与读写的最小粒度 | `DBA_TABLES`(blocks)、块 dump（§11） |
| （块内）**行 Row** | 真实的一条记录 | — | 块内偏移 | — | 承载业务数据 | `V$`/表查询 |

> **一句话记忆**：“库 → 空间 → 段 → 区 → 块 → 行”：4 层逻辑（空间/段/区/块）管“怎么分配空间”，行坐在块里管“真实数据”；表空间靠数据文件落地，块靠 8KB 落地。

> **与其他章节呼应**：表空间/段/区/块是“库内部的逻辑存储层”（第 2 节），与 Schema 正交；最底层的 **Block** 正是 Buffer Cache 缓存的对象（第 3 节）；块头的 **ITL/行目录** 支撑一致性读与行锁（第 5 节、第 11 节）；块的修改经 redo 保护（第 6.3 节）；**UNDO 表空间 / UNDO 段 / UNDO 块** 串起了 §5.5 的 undo 记录与 §6.3 的 redo 保护。

---

## 11. 深入：Oracle 8KB 数据块（Data Block）内部结构

> 本节是进阶内容。前面提到“Database Buffer Cache 里放的是 8KB 数据块”，这一节把“一个 8KB 块内部到底长什么样、块头里记录了哪些信息”彻底拆开。理解了块结构，第 5 节的“一致性读靠 SCN + UNDO + ITL”就不再抽象。

### 11.1 先澄清：8KB 是“块大小”，不是“头部大小”

- Oracle 的数据以**块（Block，也叫 Page）**为最小 I/O 单位，大小由 `DB_BLOCK_SIZE` 决定，**默认 8192 字节（8KB）**，也可设为 2K/4K/16K/32K。
- 一个 8KB 块 = **一小段块头开销（几十~一百多字节）+ ITL 事务槽 + 表/行目录 + 大量行数据与空闲空间**。
- 所以“8KB 数据块头部”指的是**这个 8KB 块的头部（Block Header）**，它只占块开头的一小部分，主体是行数据；块头存“元数据”，行数据区存“真实记录”。

### 11.2 一个 8KB 块从外到内的分层

```
┌──────────────────────────────────────────────────────────────────┐
│  Block Header（块头 / Cache Header）  ← 本节的焦点                  │
│    · 块类型 typ    · 块 SCN（最近修改的 SCN）                      │
│    · 块地址 DBA    · 序列号 seq    · 格式 fmt    · 标志 flg        │
│    · ITL 数量 itc  +  事务头 ITL（紧邻，常被合称“块头”）           │
├──────────────────────────────────────────────────────────────────┤
│  Transaction Header / ITL（Interested Transaction List，事务槽）   │
│    每条 ITL：XID（谁改的）· UBA（undo 在哪）· Flag（状态）          │
│                · Lck（锁了几行）· Scn/Fsc（提交 SCN）              │
├──────────────────────────────────────────────────────────────────┤
│  Table Directory（表目录）   ← 记录本块属于哪个表/簇、行数统计     │
├──────────────────────────────────────────────────────────────────┤
│  Row Directory（行目录）   ← 每条行一个 2 字节偏移指针（块顶向下） │
├──────────────────────────────┬───────────────────────────────────┤
│   Free Space（空闲空间）     │   Row Data（行数据，块底向上生长）   │
│   （受 PCTFREE / PCTUSED 控制）                                    │
├──────────────────────────────────────────────────────────────────┤
│  Tailchk（块尾校验，最后 4 字节）  ← 用于检测坏块/写断裂           │
└──────────────────────────────────────────────────────────────────┘
```

### 11.3 块头（Block Header）里的信息清单

| 字段 | 含义 | 用途 |
|------|------|------|
| **typ（块类型）** | 标识段的种类，如数据表块、索引块、undo 块、段头块等 | 解析/校验块合法性 |
| **SCN（块最近修改 SCN）** | 拆成 `SCN base + SCN wrap` 两部分记录；即“这个块最后一次被改到哪个时点” | 一致性读判断“块是否比我的查询快照新”；也是延迟块清除的依据 |
| **DBA（块地址）** | 由文件号 + 块号组成，全局唯一标识这块的位置 | 定位、校验、坏块定位 |
| **seq（序列号）** | 块每变更一次自增 | 识别陈旧/过期副本（如介质恢复时的“老版本”检测） |
| **fmt（格式版本）** | 块格式版本号（如 0=9i 前，1=9i 及以后） | 兼容性 |
| **flg（标志位）** | 块状态标志（如是否为临时块、是否脏等） | 内部状态 |
| **itc / 偏移** | ITL 的数量与位置指针 | 找到事务槽 |

> 重点：**块头里的 SCN 就是一致性读的“时钟”**。第 5 节说“查询取一个 SCN 作快照点，读到比快照新的块就沿 UNDO 回滚”——判断“是否比快照新”用的正是块头里这个 SCN。

### 11.4 ITL：多版本并发的“开关”（最该记住的部分）

ITL（Interested Transaction List，感兴趣事务列表）位于块头之后，是**本块上发生过/正在发生的事务的槽位表**。每个 ITL 条目约 24 字节，固定包含：

| ITL 字段 | 含义 |
|---------|------|
| **XID（事务 ID）** | 谁改的：`undo段号.槽号.序列`（如 `0x000a.012.0000abcd`） |
| **UBA（Undo Block Address）** | 该事务对“本块”最近一次修改对应的 **undo 记录地址**，沿它可回滚这一次的改动 |
| **Flag（状态位）** | 事务状态：`----`（活动）/ `C---`（已提交）/ `U---`（上界）等 |
| **Lck（锁计数）** | 该事务在本块上锁住的行数 |
| **Scn/Fsc（提交 SCN）** | 事务**提交时**把 commit SCN 写进这里（Fsc = 提交 SCN 的快路径记录），供延迟块清除与一致性读判断“是否在我快照前已提交” |

ITL 的数量由 **INITRANS**（初始槽位数，表默认 1~2）和 **MAXTRANS**（上限）控制。高并发更新同一块的表，常把 INITRANS 调大以避免“ITL 不足导致等待”。

**和一致性读串起来看**（呼应第 5 节）：
```
查询遇到块中某行：
  ① 看行头 LB（Lock Byte）→ 指向某个 ITL 槽
  ② 该 ITL 的 Flag 是“活动”（未提交）或它的 Scn 晚于我的查询快照 SCN
       → 说明这一行“对我而言是新的/未提交的”
  ③ 沿该 ITL 的 UBA 找到 undo 记录，把这一行回滚到快照时点
  ④ 在内存构造出 CR 块（一致性读块），返回旧值
```
所以 **ITL 是“行 → 事务 → undo”的指针桥**，没有它，Oracle 无法知道“谁改的、改之前什么样、改的时候提交没”。

### 11.5 行目录、行数据与空闲空间

- **Row Directory（行目录）**：块顶向下生长，每个行占 2 字节，存“该行在块内的偏移量”。通过它，Oracle 在块内定位每一行，无需全扫描。
- **Row Data（行数据）**：从块底向上生长；中间是 **Free Space（空闲空间）**。
- **行（Row）自身结构**：行头（含 LB 指向 ITL 槽、列计数、锁标志）+ 各列“长度字节 + 数据”。
- **PCTFREE / PCTUSED**（段的存储参数，不是块头字段，但决定块内空闲）：
  - `PCTFREE`：块中预留多少百分比的空闲给**将来 UPDATE**（防止行变长后放不下而被迫行迁移 row migration）；
  - `PCTUSED`：块的使用率降到该比例以下才允许再插入新行。
  - 二者共同决定一个块何时“满”、何时“可再插”，直接影响行迁移与空间利用率。

### 11.6 块尾校验 Tailchk（最后 4 字节）

块的最后 4 字节叫 **Tailchk（尾校验）**，结构大致为：
```
低 2 字节：块 SCN 的 base 低 16 位
1 字节  ：块类型 typ
1 字节  ：块 SCN 的 wrap 低 8 位
```
**作用——检测“写断裂（fractured block）”**：Oracle 写盘时先写块头（带当前 SCN），最后才写块尾。如果写的过程中实例崩溃/IO 中断，块尾的 SCN/类型就和块头对不上。下次读取该块时，Oracle 比对块头 SCN 与块尾 Tailchk，不一致就判定为坏块（常见报错 `ORA-01578` / 物理或逻辑坏块），从而触发块恢复或告警。**这就是“为什么 Oracle 不怕一半写进去的块”**。

### 11.7 怎么实地看一个真实块（实操）

```sql
-- 1) 用 ROWID 反查出 文件号/块号（以某行数据为例）
SELECT dbms_rowid.rowid_relative_fno(rowid)  AS file_id,
       dbms_rowid.rowid_block_number(rowid) AS block_id,
       rowid
  FROM your_table WHERE rownum = 1;

-- 2) 把一个块 dump 到跟踪文件（需要权限）
ALTER SYSTEM DUMP DATAFILE <file_id> BLOCK <block_id>;
-- 跟踪文件在诊断目录（ADR）下，用文本打开即可

-- 3) dump 里关键片段示例（节选并注解）：
-- Block header dump:  0x00400123
--  Object id on Block? Y
--  seg/obj: 0x12ab  csc: 0x00.0012a400  itc: 2  flg: E  typ: 1 - DATA
--  Itl           Xid                  Uba             Flag  Lck     Scn/Fsc
--  0x01   0x000a.012.0000abcd  0x00c01234.0010.1a   C---    0   scn 0x00.0012a3ff
--  0x02   0x0009.008.0000dcba  0x00c05678.0020.0b   ----    1   fsc 0x0000.00000000
--  tailchk: 0xa4000601
--
-- 解读：
--   typ:1 - DATA      → 这是一张表的数据块
--   itc:2             → 块里有 2 个 ITL 槽
--   csc               → cleanout SCN（块清除用到的最近提交 SCN）
--   Itl 0x01 Flag=C--- → 事务已提交，Scn 记录了提交 SCN（延迟块清除已完成）
--   Itl 0x02 Flag=---- → 还有一个活动事务，锁了 1 行（Lck=1），尚未提交
--   tailchk 应与块头 SCN/类型一致，否则即坏块
```

> 提示：内存中还有一份“缓冲区头”（**Buffer Header，`V$BH` / `X$BH`**），它描述的是“这个块在 Buffer Cache 里的状态（是否被 pin、是否脏、状态位）”，**和磁盘块的 Block Header 不是一回事**，别混淆。

### 11.8 小结与前后呼应

- 一个 8KB 块 = 块头（类型/SCN/DBA/序列/标志 + ITL）+ 表目录 + 行目录 + 行数据/空闲 + 块尾校验。
- **块头 SCN 是时钟，ITL 是“行→事务→undo”的指针桥，Tailchk 是写安全的兜底**——三者共同支撑了第 5 节的“读不阻塞写、写不阻塞读”。
- 想看真实结构：用 `DBMS_ROWID` 定位 + `ALTER SYSTEM DUMP DATAFILE ... BLOCK ...` 落地到跟踪文件。

---

## 12. 参考资料

1. 工作区姊妹篇：[MVCC原理.md](MVCC原理.md) —— Oracle vs MySQL 的 MVCC 逐机制对比（SCN+UNDO 一致性读、ITL、ORA-01555、Flashback 同源机制）。
2. 工作区姊妹篇：[DB事务隔离级别.md](DB事务隔离级别.md) —— Oracle 两档半隔离级别（RC/SERIALIZABLE/READ ONLY）与四库对照，写偏斜与快照隔离的深入讲解。
3. Oracle Database Concepts（官方文档）—— *Memory Architecture*（SGA/PGA）、*Process Architecture*（PMON/SMON/DBWn/LGWR/CKPT/ARCn）、*Data Concurrency and Consistency*（Consistent Read、SCN、ITL）、*Undo*（自动撤销管理、ORA-01555）、*Data Guard*、*True Cache*（23ai）。
4. Oracle Database Reference —— `V$SQLAREA`、`V$LIBRARYCACHE`、`V$UNDOSTAT`、`V$SESSION`（等待事件）等性能/排障视图。
5. Oracle Blogs（23ai）—— *Introducing Oracle True Cache*、*True Cache and Active Data Guard Transparent JDBC Redirection*。
