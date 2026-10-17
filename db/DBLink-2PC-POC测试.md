# Oracle DB Link 2PC POC 测试方案（对比 Seata XA）

> 整理日期：2026-10-17
>
> 适用版本：Oracle Database 19c / 23ai（分布式事务机制同源）；Seata 2.x；JDK 17；Spring Boot 3.x。参数默认值以 Oracle Database 26 文档与 Seata 2.0 参数表核对，落地时以实验室 `SHOW PARAMETER` 与 Seata 发行版 `application.yml` 为准。
>
> 阅读对象：要在两个 Oracle 之间做强一致、正在 DB Link 2PC 与 Seata XA 之间选型的架构师、DBA、负责压测的开发。
>
> 前置阅读：[Oracle DBLink 2PC vs Seata.md](Oracle%20DBLink%202PC%20vs%20Seata.md)（原理与四维对比）、[Oracle原理分析.md](Oracle原理分析.md)（SCN、REDO、UNDO、RECO）。
>
> 本文是**测试设计与选型分析**，不含实测数字。文中所有性能排序都是待验证假设。选型结论只能源自第 7 章的填表结果。

---

## 如何读这份文档

按角色选读，不必通读后再动手：

- **做选型的人（约 40 分钟）**：第一章总览、第二章实践与红线、第三章对比口径、第七章判定门。第二章已经能否决一批不该用 DB Link 的场景；第三章规定什么样的数字才算数。
- **做测试的人**：第三章口径 → 第四章环境 → 第五章、第六章用例 → 第七章记录模板。用例按编号执行，先功能、再性能、最后故障注入。
- **做复盘的人**：只看每条用例的「通过标准」列和第七章空表。把实验室数字填进去，对照红线给出结论。

全文约定：

- **A 路径** = Oracle DB Link 内部 2PC。应用只连接发起库，远程 DML 写成 `schema.对象@dblink`，本地 `COMMIT` 由 Oracle 升级成跨库两阶段提交。
- **B 路径** = Seata XA。应用用两个 `OracleXADataSource` 直连两库，`@GlobalTransactional` 把全局决议交给独立的 `seata-server`（TC）。
- 两条路径都是悲观两阶段提交，prepare 之后都持有行锁。POC 比的是协调位置、故障面和会话模型，不是“谁能提供另一种隔离级别”。

---

# 一、总览

## 1.1 一句话定位

这份 POC 用**同一笔跨库转账、同一套数据、同一类故障**，测量 DB Link 2PC 与 Seata XA 的端到端延迟、吞吐、会话放大和崩溃恢复，并用 Oracle 官方分布式数据库实践把“跑得快”翻译成“能不能上生产”。

## 1.2 设计原则

1. **业务语义对齐，协调路径保持原样。** 两边都是“A 库扣款 + B 库加款 + 同一请求号只生效一次”。应用多出来的 TC 往返、库内多出来的 DB Link 会话，都是方案本身，不允许为了好看把它优化掉，也不允许给对手额外加一跳微服务。
2. **正确性先于速度。** 任一用例只要余额总和变化、同一 `request_id` 入账两次、或恢复后仍有行锁，整条用例失败。吞吐数字不参与评分。
3. **故障可复现。** 可靠性用例用“先关掉自动恢复 → 轮询到 prepared → 再注入故障 → 再打开恢复”的控制器制造 in-doubt，避免靠手速去撞提交窗口。
4. **红线不靠压测翻案。** 跨安全域、两库分属不同服务、需要跨库外键、事务里要带 MQ，这些在第二章就已经出局。POC 只回答红线之内的性能和可靠性。
5. **数字来自实验室，结论写成填表规则。** 本文给出判定门，不预填 TPS。

## 1.3 测试分层

```
┌────────────────────────── 判定层 ──────────────────────────┐
│  红线（第二章） + 性能门 / 可靠性门（第七章） → 选型结论      │
├────────────────────────── 负载层 ──────────────────────────┐
│  Java 压测夹具：固定并发、HDR 直方图、同一 request 协议       │
├────────────────────────── 业务层 ──────────────────────────┐
│  转账：借记发起库 + 贷记参与库 + biz_journal 幂等            │
├─────────────── A 路径 ──────────┬────────── B 路径 ────────┤
│  本地事务 + 私有 DB Link         │  @GlobalTransactional    │
│  Oracle 内部 prepare/commit      │  TC + 两路 XA 分支        │
├────────────────────────── 数据层 ──────────────────────────┐
│  Oracle A（发起库 / CPS）          Oracle B（参与库）        │
│  同版本、同容量、同 redo/undo 布局、库间 RTT 单独记录         │
└────────────────────────────────────────────────────────────┘
故障注入从负载层外侧进入：断网、SHUTDOWN ABORT、kill TC、收回 XA 授权。
```

## 1.4 两条被测链路

一次成功转账，应用能看见的协调差异如下。远程业务 SQL 都是 2 条更新，差在提交阶段谁在说话。

```
A. DB Link 2PC

  压测线程
     │  一条 JDBC 连接（普通连接池）
     ▼
  Oracle A  ──SQL*Net──►  Oracle B
     │                      │
     └── 内部 PREPARE / CPS 提交 / 通知 B 提交
  应用只等待最后一次 COMMIT 返回

B. Seata XA

  压测线程
     │  全局开始（TM → TC）
     ├─ JDBC/XA ──────────► Oracle A   分支注册（RM → TC）→ XA START/END/PREPARE
     └─ JDBC/XA ──────────► Oracle B   分支注册（RM → TC）→ XA START/END/PREPARE
     │  全局提交（TM → TC）
     ▼
  TC 再驱动两条分支 XA COMMIT
```

Seata 官方对 XA 的时序要求是：BranchId 在分支注册时由 TC 生成，因此 **XA START 之前必须先向 TC 注册分支**。完成阶段才是 `XA COMMIT` / `XA ROLLBACK`。这条多出来的注册 RPC 计入 B 路径的延迟，不计入 A 路径。

## 1.5 关键问题 → 用例

| 选型时要回答的问题 | 谁先回答 | 用例 |
|---|---|---|
| 两个库是否允许库级直连、同一账号写对方的表 | 第二章红线，不测 | — |
| 跨库对象能否做外键 | Oracle 限制，不测 | 见 2.3 |
| 同样并发下谁更快、尾延迟谁更长 | POC | P0–P4、P7 |
| 热点行上锁窗口谁更宽 | POC | P3 |
| 库间 RTT 放大后谁掉得更狠 | POC | P4 |
| 连接池会不会把参与库会话打满 | POC | P5 |
| 提交窗口断网，能否自己收敛 | POC | R4–R7 |
| 恢复期间业务是否被 ORA-01591 堵住，堵住多久 | POC | R7 |
| 应用超时重试会不会记两笔账 | POC | R11 |
| TC 挂了和发起库挂了，业务差异是什么 | POC | R6、R10 |
| 全局超时行为是否一样 | POC | R13 |
| DBA 强制提交会不会造成两边结局不同 | POC | R8、R9 |

## 1.6 版本与运行基线

| 组件 | POC 基线 | 原因 |
|---|---|---|
| Oracle | 两库同一版本，19c 或 23ai | 混用大版本会把优化器和 Net 差异算进事务方案 |
| Seata | 2.x，XA 模式 | 2.0 起提供 Raft 存储；性能主项用单 TC + `store.mode=file`，高可用另测 |
| JDK | 17 | 与 Spring Boot 3 对齐 |
| 驱动 | 两路径同一 `ojdbc` 版本 | 驱动差异会伪装成方案差异 |
| 压测机 | 独立于两台数据库 | 避免压测进程和 LGWR 抢 CPU |

性能主项故意只用 **1 个 TC、file 存储**。Raft 复制的磁盘延迟和 XA 协议本身的 RPC 不是同一件事，放进同一条吞吐曲线会无法解释。TC 高可用放在 R10b，用 3 节点 Raft，只看故障切换，不看极限 TPS。

## 1.7 章节地图

| 章 | 内容 | 产出 |
|---|---|---|
| 二 | DB Link 的官方实践与不适用边界 | 还没压测就能否决的红线 |
| 三 | 公平对比口径、指标、判定门 | 双方都认可的记分规则 |
| 四 | 拓扑、参数、账号、表、观测 SQL | 可复现的实验室 |
| 五 | 性能用例 P0–P8 | 延迟、吞吐、会话、RTT |
| 六 | 可靠性用例 R1–R15 | in-doubt、恢复时延、幂等 |
| 七 | 执行顺序与结论填表 | 实测之后的选型结论 |

## 本章小结

POC 回答两件事：红线之内 DB Link 是否真的更快，以及提交窗口出事时两边能否自己回到同一结局。红线之外的场景不进入压测。性能主项排除 Raft 和额外微服务，避免把无关成本算到 Seata XA 头上，也避免把 DB Link 的会话放大隐藏掉。

---

# 二、业界实践，以及它们怎样约束选型

本章只采用三类依据：Oracle 官方分布式数据库文档、Oracle JDBC/OCI 对 XA 与连接池的说明、Seata 2.x 对 XA 模式的说明。不引用无法核对的“某银行案例”。行业里反复出现的用法，都能在这些文档里找到对应条款。

官方入口：

- [Managing a Distributed Database](https://docs.oracle.com/en/database/oracle/oracle-database/26/admin/managing-a-distributed-database.html)
- [Managing Distributed Transactions](https://docs.oracle.com/en/database/oracle/oracle-database/26/admin/managing-distributed-transactions.html)
- [Developing Applications for a Distributed Database System](https://docs.oracle.com/en/database/oracle/oracle-database/26/admin/developing-applications-for-a-distributed-database-system.html)
- [CREATE DATABASE LINK](https://docs.oracle.com/en/database/oracle/oracle-database/26/sqlrf/CREATE-DATABASE-LINK.html)
- [Seata XA Mode](https://seata.apache.org/docs/dev/mode/xa-mode/)

## 2.1 DB Link 2PC 在什么前提下成立

DB Link 是发起库数据字典里的一条远程路径。链接是单向的：A 上的链接只能让 A 的会话访问 B；B 上的用户要访问 A，必须在 B 上另建链接。

当一个事务同时改本地表和 `@dblink` 上的远程表时，Oracle 把这个本地事务升级成分分布式事务。发起库是 Global Coordinator。`COMMIT_POINT_STRENGTH`（0–255，默认值随操作系统）最高的库成为 Commit Point Site，最先提交，作为全局结局的锚点。网络或实例在两阶段中间断开时，事务变成 in-doubt，行锁继续持有；RECO 在通信恢复后按指数退避重连，把各库对齐到同一结局，并把 `DBA_2PC_PENDING` 中已解决的行清掉。

这套机制适合同时满足下面四条的系统：

1. 两个库都是 Oracle，网络策略允许 SQL*Net 直连。
2. 两个库在同一信任域，发起方账号可以被授权写入参与库的业务表。
3. 事务短。锁从第一条远程 DML 持有到两阶段结束，库间 RTT 和锁等待会直接加进这个窗口。
4. 数据归属允许“一个发起库代表业务写远程表”。链接背后的远程权限由远程库控制，发起库不能通过链接把远程对象的权限再授出去。

对应的生产形态通常是：同一套核心里的两个库（主账与分户、订单库与库存库），应用只连接发起库，远程写入通过私有链接完成，跨库查询报表改走物化视图或同步，不走在线分布式大查询。

## 2.2 八条落地实践

这些实践决定 POC 的默认配置。不按它们搭建的环境，测出来的数字不能代表可上线的 DB Link。

### 实践 1：私有链接，生产关闭公开链接

公开链接对库内所有用户可见，权限边界最大。POC 与生产推荐都用**私有链接**。

账号类型按安全级别选择：

| 类型 | 定义 | 字典里是否保存远程口令 | 适用 |
|---|---|---|---|
| Current user | `CONNECT TO CURRENT_USER` | 否 | 两边都是 global user / enterprise user。依赖 Oracle Advanced Security，要求当前用户在两端都是全局用户 |
| Connected user | 不写 `CONNECT TO` | 否。使用当前会话用户的口令登录远程库 | 连接池用户在两端同名、口令一致。POC 默认用这种 |
| Fixed user | `CONNECT TO user IDENTIFIED BY password` | 是。口令写在数据字典链接定义里，并会发到远程库 | 远程必须落到另一个 schema 时再用。远程账号单独授权，禁止用 SYS |

`SYS` 和 `PUBLIC` 不能作为链接的目标用户。

POC 默认用 connected user：应用用户 `APP` 在 A、B 都存在且口令相同，A 上的私有链接不保存第二套口令。若实验室不便同步口令，可以改用 fixed user，但报告里要注明，生产回切到 connected user 或 current user。

### 实践 2：`GLOBAL_NAMES=TRUE`，链接名等于远程全局名

`GLOBAL_NAMES=TRUE` 时，链接名必须等于它所连接数据库的全局名。这样链接不会在全局名变更后静默指向错误目标。

```sql
-- 在 B 上确认
SELECT * FROM global_name;

-- 在 A 上，以 APP 创建。链接名替换成 B 的全局名
CREATE DATABASE LINK "BDB.EXAMPLE.COM"
  USING 'bdb_primary';
```

`USING` 写服务名，不写 SID。RAC 用 SCAN。若将来有 Data Guard，服务要做成**只在主库注册的 role-based service**，切换后链接仍指向可读写的主库。没有 Data Guard 的实验室不做切换演练，报告里把这一项标成“未覆盖”。

### 实践 3：远程账号最小权限，跨库外键不要设计进去

远程 `APP` 只授予：

- `CREATE SESSION`
- 业务表的 `SELECT` / `INSERT` / `UPDATE` / `DELETE`

不授予 `DBA`、`ANY` 类系统权限、`FORCE ANY TRANSACTION`。强制提交只留给 DBA 演练账号。

Oracle 明确不允许通过数据库链接：

- 在远程对象上授权
- 定义或强制引用完整性
- 分析远程对象
- 让链接用户拿到远程的非默认角色

因此“A 库的订单行外键指向 B 库的账户”这种模型不能靠 DB Link 实现。应用要在事务里自己做存在性检查。这条是选型红线，不是调优项。

位置透明用**同义词**完成，权限仍在远程库生效：

```sql
CREATE SYNONYM remote_acct FOR app.acct@"BDB.EXAMPLE.COM";
```

同义词改名不影响远程授权。发起库也不能把这个同义词的对象权限再授给别人。

### 实践 4：把会话放大当成容量指标，而不是事后再调

官方行为：会话第一次碰到某个链接时，发起库会在远程库为它建立会话。这个远程会话**一直留到本地会话结束**，或执行 `ALTER SESSION CLOSE DATABASE LINK`。只做远程 `SELECT` 也会在 undo 段上留下事务锁，必须 `COMMIT` 或 `ROLLBACK` 才释放。

因此在专用服务器 + 应用连接池的常见形态下：

```
参与库上的 APP 会话数 ≈ 已经碰过该链接、且尚未关闭链接的发起库会话数
```

连接池 100、应用实例 4、每条连接都做过跨库事务时，参与库要准备大约 400 个远程会话，再加 DBA、RECO 和余量。OCI 连接池文档写明：后端若使用 dedicated database link，会话会绑在物理连接上，中间层连接池无法再共享这条后端连接。

共享链接（`SHARED` + `AUTHENTICATED BY`）是官方给出的收敛手段，但有边界：

- 只在“使用链接的用户数远大于发起库 shared server 进程数”时减少连接。单用户场景下，共享链接可能比普通链接占用更多网络连接。
- 远程侧要走 shared server，认证用户至少要有 `CREATE SESSION`。
- 多层链接时，下游链接也必须可迁移（shared server 或另一条共享链接）。

POC 默认用**非共享私有链接**，因为这是专用服务器 OLTP 的真实形态。P5 同时测量“不关链接”和“提交后关闭链接”的会话数与延迟。只有 P5 证明参与库会话超过容量时，才另开一轮共享链接实验，不把共享链接的结果和主项吞吐混在一张图里。

`OPEN_LINKS` 默认 4，表示单个会话可以同时打开的远程连接数。设为 0 则禁止分布式事务。两库场景保持默认即可。改这个参数需要重启实例或重新打开 PDB。

### 实践 5：事务要短，集合化写入，避免无意升级

每条跨库 DML 都是一次 SQL*Net 往返。逐行循环远程更新会把往返次数放大到行数。POC 的语句固定为每个事务 2 条集合化 `UPDATE`（每条命中 1 行），另有 P2 把单事务行数提高到 10，用来看往返斜率。

无意升级是 DB Link 最常见的生产事故：视图、同义词、触发器里藏着 `@dblink`，开发以为自己在做本地事务，Oracle 已经在跑两阶段提交。POC 的业务 SQL 显式写远程对象，并在 A 库审计 `V$DBLINK.UPDATE_SENT`，确认只有转账事务把链接推进了分布式更新。

`DISTRIBUTED_LOCK_TIMEOUT` 默认 60 秒，可用 `ALTER SYSTEM` 修改。分布式事务等锁超过该值时报 `ORA-02049`。它管的是**等锁**，不是 prepare 之后的全局超时。DB Link 没有与 Seata `defaultGlobalTransactionTimeout` 对等的“全局事务 60 秒到点回滚”。挂起的远程调用会一直占着本地事务，直到 SQL*Net 失败或语句结束。这个行为差异由 R13 单独记录。

### 实践 6：CPS 放在更不可能失联的库上，RECO 生产环境保持开启

`COMMIT_POINT_STRENGTH` 只用于选择提交点站点。官方要求把强度设在“故障后其他节点最需要向它查询结局”的那个库上，也就是更关键、更不易失联的库。

本 POC 把发起库设为 200、参与库设为 50，使 CPS 稳定在发起库。R6b 对调这两个值，确认 CPS 落在参与库时恢复仍然收敛。

```sql
ALTER SYSTEM SET COMMIT_POINT_STRENGTH = 200 SCOPE = SPFILE;  -- 需重启
```

`DISTRIBUTED_TRANSACTIONS=0` 会禁止分布式 SQL，并且实例启动时不拉起 RECO。生产与 POC 都保持非 0。

RECO 可用下面的语句临时关闭，官方用途就是在演练中制造无法自动结束的两阶段提交：

```sql
ALTER SYSTEM DISABLE DISTRIBUTED RECOVERY;
ALTER SYSTEM ENABLE DISTRIBUTED RECOVERY;
```

可靠性用例用它制造可观察的 in-doubt。性能用例开始前必须确认已经重新 ENABLE，并且 `DBA_2PC_PENDING` 为空。

### 实践 7：in-doubt 默认交给 RECO，人工 FORCE 只在三种情况下使用

官方规定，出现 in-doubt 时管理员默认什么都不做，等 RECO 在网络或实例恢复后自动对齐。只有下面三种情况才本地强制：

1. `ORA-01591`（in-doubt 事务持有的锁挡住了其他业务）。
2. in-doubt 占着的 undo 段阻止其他事务使用这些区。本地事务号的第一段就是 undo 段号，对应 `DBA_2PC_PENDING.LOCAL_TRAN_ID`。
3. 故障在可接受时间内修不好（链路物理损坏、库要做长时间恢复）。

强制之前要和其他节点的 DBA 对齐。强制错了会产生 mixed outcome：行会留在各库的 pending 表里，直到人工执行清除。清除前先在目标版本确认包名：

```sql
SELECT owner, object_name, procedure_name
FROM   all_procedures
WHERE  procedure_name IN ('PURGE_MIXED', 'PURGE_LOST_DB_ENTRY');
```

常用的是 `DBMS_TRANSACTION.PURGE_MIXED` 与 `DBMS_TRANSACTION.PURGE_LOST_DB_ENTRY`。R8 在隔离环境里故意强制错一次，用来演练发现和修复，不作为“方案能否自愈”的评分项。

事务注释能让 pending 表直接看出业务含义。POC 在提交前执行：

```sql
COMMIT COMMENT 'poc transfer request_id=...';
```

### 实践 8：监控面收敛到四张视图

| 视图 | 看什么 |
|---|---|
| `V$DBLINK` / `GV$DBLINK` | 当前会话的链接是否登录、是否还在事务中、是否发送过更新、打开的游标数、CPS 强度 |
| 参与库 `GV$SESSION` | 远程 `APP` 会话总数，即会话放大 |
| `DBA_2PC_PENDING` | in-doubt 的 `LOCAL_TRAN_ID`、`GLOBAL_TRAN_ID`、`STATE`、`MIXED`、`ADVICE`、`TRAN_COMMENT` |
| `DBA_2PC_NEIGHBORS` | 本节点是入边还是出边、是不是提交点 |

`STATE` 常见值：`collecting`、`prepared`、`committed`、`forced commit`、`forced rollback`。prepared 且未收到 commit 时，节点继续持有行锁。

排障时同时看等待事件：`SQL*Net message from dblink`、`SQL*Net more data from dblink`、`log file sync`、`enq: TX - row lock contention`。`ORA-01591`、`ORA-02049`、`ORA-02050`、`ORA-02054` 是本 POC 必须留样的四个错误号。

## 2.3 还没压测就可以否决 DB Link 的红线

| 编号 | 条件 | 依据 | 选型结果 |
|---|---|---|---|
| G1 | 另一端不是 Oracle，或同一事务要纳入 MQ / Redis | DB Link 只连接 Oracle 数据库 | 用 Seata XA（或其他 XA TM） |
| G2 | 安全域禁止两库之间的 SQL*Net，或禁止发起库账号写远程表 | 链接的工作方式就是库级直连 + 远程登录 | 用 Seata XA，应用各自连自己的库 |
| G3 | 两库分属两个服务、两个团队，存储不能互相写 | 远程 DML 权限必须授给发起方账号 | 用 Seata XA |
| G4 | 数据模型依赖跨库外键 | 官方限制：不能通过链接定义或强制引用完整性 | 改模型，或放弃 DB Link |
| G5 | 单笔事务包含人工等待、外部调用，持续时间以秒甚至分钟计 | 两阶段在 prepare 之后持锁；DB Link 没有全局事务超时 | 缩短事务；长流程改补偿，不进本 POC |
| G6 | 主要流量是跨库大查询、大 JOIN | 官方调优方向是把谓词和执行下推到数据所在库（`DRIVING_SITE`），而不是用两阶段提交搬数据 | 查询走物化视图或同步；本 POC 只测短更新 |

G1–G4 任一成立，就不要因为后续压测里 DB Link 更快而改选它。G5、G6 成立时，先改业务形态再决定要不要测。

## 2.4 实践对应到 POC 的默认姿态

| 实践 | POC 里的落实 | 违反时哪条用例作废 |
|---|---|---|
| 私有 connected user 链接 | 第四章建链脚本 | 全部 A 路径 |
| `GLOBAL_NAMES=TRUE` | 建链前检查 | 全部 A 路径 |
| 最小权限 | 远程只有业务 DML | 全部 |
| 会话放大可观测 | P5 必做 | 性能门不能通过 |
| 短事务、2 条更新 | P0 主路径；P2 做斜率 | P0 |
| CPS 在发起库，RECO 默认开 | 性能用例开始前检查 | P 系列 |
| 不在性能用例里 FORCE | FORCE 只出现在 R8 | P 系列 |
| 四张视图都有采样脚本 | 第四章 | 没有采样的用例不计入结论 |

## 本章小结

DB Link 2PC 的优势建立在“库级直连、短事务、RECO 负责收敛、远程会话被容量规划覆盖”。官方文档同时把跨库外键、公开高权限链接、把远程会话留到连接池生命周期结束，写成了明确的限制或成本。这些条款里，G1–G4 直接结束选型；会话数、恢复时间和尾延迟留给第五、六章。

---

# 三、对比口径

## 3.1 两边必须相同

| 项 | 要求 |
|---|---|
| 数据库版本与补丁 | A、B 两库相同 |
| 实例容量 | CPU、内存、`PROCESSES`、redo 大小、undo 表空间相同 |
| 表 | 结构、索引、统计信息、初始数据相同 |
| 业务 | 借记 A、贷记 B、金额相同、`request_id` 幂等 |
| 并发模型 | 同一夹具、同一线程数、思考时间 0 |
| 预热与采样 | 预热 120 秒，正式采样 600 秒（RTT 矩阵可缩短到 180 秒，但两边相同） |
| 驱动与超时 | 同一 ojdbc；socket 读超时 120 秒，避免先撞上驱动超时 |
| 日志 | 性能用例 Seata 日志 WARN，Oracle 不打开 10046。另有一次 20 笔事务的 10046 诊断，不进入 TPS 表 |
| 干扰 | 采样窗口内没有备份、统计信息收集、其他业务 |

库间 RTT、应用↔发起库 RTT、应用↔TC RTT 在每轮开始时用 ping 记录。两轮对比期间 RTT 漂移超过 20% 则重跑。

## 3.2 两边应当保持不同的部分

这些差异是被测对象，拉平它们等于换了一道题。

| 项 | A 路径 | B 路径 |
|---|---|---|
| 应用连接 | 只连 Oracle A，普通连接池 | 连 Oracle A 和 Oracle B，各一个 XA 数据源 |
| 事务 API | 本地事务，提交即 2PC | `@GlobalTransactional` |
| 协调者 | Oracle A | 独立 TC，1 节点，`store.mode=file` |
| 远程会话 | 由链接按发起库会话建立 | 没有 DB Link。B 库会话来自应用 XA 池，规模由池大小决定 |
| 全局超时 | 无对等参数。等锁看 `DISTRIBUTED_LOCK_TIMEOUT=60` | `client.tm.defaultGlobalTransactionTimeout=60000` |

Seata 的 XA 数据源按官方要求包装 `XADataSource`，分支注册发生在 `XA START` 之前。关闭自动代理，避免普通数据源被再包一层：

```yaml
seata:
  enabled: true
  enable-auto-data-source-proxy: false
  data-source-proxy-mode: XA
```

应用代码里显式 `new DataSourceProxyXA(oracleXADataSource)`，A、B 各一个。

TC 使用 file 存储时没有高可用，这是性能主项的已知条件。R10b 单独测 3 节点 Raft。注册中心在性能主项用直连 file，避免把 Nacos 的抖动算进事务延迟；R10b 再使用真实注册中心。

## 3.3 不纳入本 POC 的东西

- Seata AT、TCC、SAGA。它们不是悲观两阶段提交，和 DB Link 不是同一类一致性。
- Narayana、Atomikos。原理对比见姊妹篇，本 POC 只回答“DB Link 对 Seata XA”。
- 跨服务调用链上的 XID 传播。主项是单进程双库。若生产拓扑确实是“服务 A 调服务 B，各写各库”，在 R 系列之外单列附录用例，不与 P0 的 TPS 比较。
- 共享链接的吞吐，除非 P5 触发了容量门，另开一轮。
- 把 TC 和数据库装在同一台机器上的结果。TC 至少与两台数据库分机。

## 3.4 指标

每一轮都记录下面的集合。缺少任一“正确性”指标，该轮作废。

**正确性**

- 两库 `SUM(balance)` 之和相对初始值的差值 = 0
- 不存在 `balance < 0`
- 成功响应数 = `biz_journal` 行数 = A 库扣款净额 / 单笔金额的笔数
- 同一 `request_id` 在 journal 中只有 1 行

**性能**

- 成功 TPS
- 延迟 P50 / P95 / P99 / P999 / Max
- 错误率，按错误号分组（业务余额不足单独计数，不算故障）
- A、B 的 `DB time`、`log file sync`、`enq: TX - row lock contention`
- A 路径额外：`SQL*Net message from dblink` 的平均等待、参与库 `APP` 会话峰值
- B 路径额外：TC 的分支注册耗时、全局提交耗时、TC 进程 CPU

**可靠性**

- 从故障注入到 `DBA_2PC_PENDING` 为空、且被测行上无残留锁的秒数
- 恢复后不变量是否成立
- 是否需要人工 `COMMIT FORCE` / `ROLLBACK FORCE`（自动恢复类用例里，需要人工即失败）
- 堵住其他会话的 `ORA-01591` 持续秒数

## 3.5 判定门

门是规则，不是预设的倍数。实验室把数字填进第七章。

**性能门（P 系列全部有效轮次）**

1. 正确性指标全部成立。
2. 目标并发（取 P1 曲线上业务要求的那个并发，例如 100）下，A 路径参与库 `APP` 会话峰值 < `PROCESSES` 的 70%。达到或超过，则 A 路径必须改用“提交后关闭链接”再测一轮；关闭链接后若 P99 劣于 B 路径，性能门按关闭链接后的数字判定。
3. 给出 A、B 的 TPS 与 P99。A 的 TPS 更高且 P99 更低，记为“性能支持 A”。二者 TPS 差距在 15% 以内且 P99 差距在 15% 以内，记为“性能持平”，选型改由红线和可靠性决定。
4. P4 中 RTT = 5 ms 与 20 ms 两档，锁等待占比要单独列表。RTT 上去之后两边一起变慢，是两阶段提交的共同成本，不单独判负。

**可靠性门（R4、R5、R6、R7、R11、R14、R15）**

1. 不变量成立，journal 不重复。
2. 网络或实例恢复后，不使用 FORCE，pending 清空、行锁释放。
3. 记录恢复秒数。本 POC 不预设“必须小于 N 秒”的通用 SLO；业务方在填表时写入自己的上限。超过该上限则可靠性门失败。
4. R13 不比快慢，只核对行为：B 路径在约 60 秒全局超时后回滚；A 路径没有同款全局超时，事务随远程调用结束或 SQL*Net 失败而结束。两边最终都不变量破坏。

**人工演练（R8、R9）不进入可靠性门。** 它们验证操作手册：能否发现 mixed outcome、能否修平。

## 本章小结

公平性体现在数据、SQL 语义、并发和超时对齐；不公平的拉平（去掉 TC、预先把远程会话限制到看不出来）会使结论失效。判定门把“正确、会话装得下、恢复不用人工”放在 TPS 前面。性能差距小于 15% 时，本章要求改由红线和可靠性做决定。

---

# 四、环境、数据与被测实现

## 4.1 拓扑

```
                ┌──────────── 压测机 ────────────┐
                │  同一 JAR，开关选择 A 或 B 路径   │
                └───┬────────────────────┬───────┘
                    │ 普通 JDBC           │ XA + TM/RM
                    ▼                    ▼
              ┌──────────┐         ┌──────────┐
              │ Oracle A │         │   TC     │  仅 B 路径
              │ 发起库    │         │ file/Raft│
              │ CPS=200  │         └────┬─────┘
              └────┬─────┘              │ Netty
                   │ SQL*Net            │
                   ▼                    ▼
              ┌──────────┐         压测机再以 XA 连接 A 与 B
              │ Oracle B │
              │ 参与库    │
              │ CPS=50   │
              └──────────┘

A 路径的数据面只有压测机 → A → B。
B 路径的数据面是压测机 → A、压测机 → B，控制面是压测机 ↔ TC。
```

四台机器（或四台 VM）分开：压测机、TC、Oracle A、Oracle B。记录四者之间的 RTT。操作系统建议 Linux，故障注入用 `iptables` 和 `tc netem`。Windows 实验室没有等价的 netem 时，RTT 用例改到 Linux VM 上做，不在 Windows 防火墙里近似。

## 4.2 参数基线

性能用例开始前执行并归档：

```sql
SHOW PARAMETER open_links;
SHOW PARAMETER distributed_lock_timeout;
SHOW PARAMETER distributed_transactions;
SHOW PARAMETER commit_point_strength;
SHOW PARAMETER global_names;
SHOW PARAMETER processes;
SHOW PARAMETER sessions;
SHOW PARAMETER transactions;
```

| 参数 | POC 取值 | 说明 |
|---|---|---|
| `GLOBAL_NAMES` | `TRUE` | 链接名等于远程全局名 |
| `OPEN_LINKS` | 默认 4 | 0 会禁止分布式事务 |
| `DISTRIBUTED_LOCK_TIMEOUT` | 60 | 默认值。等锁超时即 `ORA-02049` |
| `DISTRIBUTED_TRANSACTIONS` | 保持默认且非 0 | 0 会禁止分布式 SQL 且不启动 RECO |
| A 的 `COMMIT_POINT_STRENGTH` | 200 | 重启生效。CPS 固定在发起库 |
| B 的 `COMMIT_POINT_STRENGTH` | 50 | R6b 与 A 对调 |
| `PROCESSES` / `SESSIONS` | 两库相同，且大于连接池上限 × 应用实例数 × 1.3 | P5 用来验证这个估算 |
| Seata 全局超时 | 60000 ms | 与等锁超时同量级 |
| Seata `server.xaerNotaRetryTimeout` | 默认 60000 ms | XA 分支悬挂重试上限，1.5.1 起 |
| Seata RPC 超时 | 默认 30 s | `transport.rpcRmRequestTimeout` / `rpcTmRequestTimeout` / `rpcTcRequestTimeout` |
| ojdbc 读超时 | 120 s | 长于 60 s 的锁等待和全局超时，避免驱动先断开 |

`COMMIT_POINT_STRENGTH` 修改后需要重启，放在环境准备阶段，不要放进单次用例。

## 4.3 账号、链接与 XA 授权

两库都执行：

```sql
CREATE USER app IDENTIFIED BY "poc_pwd" QUOTA UNLIMITED ON users;
GRANT CREATE SESSION, CREATE TABLE, CREATE SYNONYM, CREATE DATABASE LINK TO app;
-- B 上不需要 CREATE DATABASE LINK。A 上需要。
```

表建在 `APP` schema 下之后，收回 A 上多余的 `CREATE TABLE` 也可。远程侧最终只留会话权限和表级 DML。

A 上以 `APP` 建链接（全局名按 `SELECT * FROM global_name` 的实际结果替换）：

```sql
CREATE DATABASE LINK "BDB.EXAMPLE.COM"
  USING 'bdb_primary';

CREATE SYNONYM remote_acct    FOR app.acct@"BDB.EXAMPLE.COM";
CREATE SYNONYM remote_journal FOR app.biz_journal@"BDB.EXAMPLE.COM";
```

连通性与分布式事务自检：

```sql
SELECT * FROM global_name@"BDB.EXAMPLE.COM";

UPDATE app.acct SET balance = balance WHERE id = 1;
UPDATE app.remote_acct SET balance = balance WHERE id = 1;
COMMIT COMMENT 'poc dblink self-test';

SELECT db_link, logged_on, in_transaction, update_sent
FROM   v$dblink;

ALTER SESSION CLOSE DATABASE LINK "BDB.EXAMPLE.COM";
```

自检后 `DBA_2PC_PENDING` 应为空。`UPDATE_SENT=YES` 说明这条事务确实升级成了分布式更新。

B 路径的 XA 恢复授权授给两端的 `APP`。DB Link 路径不需要这些权限。授权缺失时 `XAResource.recover()` 拿不到 in-doubt 分支，行锁会留下，所以 R-AUTH 专门证伪它：

```sql
GRANT SELECT  ON sys.dba_pending_transactions TO app;
GRANT SELECT  ON sys.pending_trans$           TO app;
GRANT SELECT  ON sys.dba_2pc_pending          TO app;
GRANT SELECT  ON sys.dba_2pc_neighbors        TO app;
GRANT EXECUTE ON sys.dbms_xa                  TO app;
```

以目标版本的 Oracle JDBC 文档为准。R-AUTH 先测“有授权能够恢复”，再回收授权复跑，后者应当失败。不要把 `FORCE ANY TRANSACTION` 授给 `APP`。

## 4.4 数据模型与不变量

每个库 20 万行账户。A 库是资金转出方，B 库是转入方。主路径每笔事务借记 A 的一行、贷记 B 的一行。

```sql
CREATE TABLE app.acct (
  id         NUMBER        PRIMARY KEY,
  balance    NUMBER(18,2)  NOT NULL,
  updated_at TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL
);

CREATE TABLE app.biz_journal (
  request_id VARCHAR2(64)  PRIMARY KEY,
  from_id    NUMBER        NOT NULL,
  to_id      NUMBER        NOT NULL,
  amount     NUMBER(18,2)  NOT NULL,
  created_at TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL
);

-- 两库各插入 id = 1..200000，balance = 1000000
```

收集统计信息后再压测。初始总和：

```sql
SELECT SUM(balance) FROM app.acct;  -- 每库 2e11，两库合计 4e11
```

单笔金额固定 100（P3 热点除外，仍用 100）。不变量：

```text
sumA(t) + sumB(t) = sumA(0) + sumB(0)
成功笔数 = A.biz_journal 的行数
A 的减少额 = 成功笔数 × 100
B 的增加额 = 成功笔数 × 100
全部 balance >= 0
```

journal 放在发起库，和两笔账务更新在同一个事务里。A 路径由 DB Link 2PC 保证三者同进退。B 路径由 Seata XA 保证。重试时先按主键读 journal，已存在则直接返回成功，不再做第二次转账。

均匀流量：`from_id`、`to_id` 在 1..200000 上均匀选择，且单笔事务内互不相关。热点流量：80% 的事务借记 `id=1`，其余均匀。转入方始终均匀，避免两边同时人为制造热点。

## 4.5 两条业务实现

语义相同，协调不同。余额不足时更新行数为 0，事务回滚，返回业务拒绝，不重试。

A 路径，一个本地事务：

```sql
INSERT INTO app.biz_journal(request_id, from_id, to_id, amount)
VALUES (:request_id, :from_id, :to_id, :amount);

UPDATE app.acct
   SET balance = balance - :amount, updated_at = SYSTIMESTAMP
 WHERE id = :from_id AND balance >= :amount;

-- 上一句 rowcount = 0 则 ROLLBACK，返回业务拒绝

UPDATE app.remote_acct
   SET balance = balance + :amount, updated_at = SYSTIMESTAMP
 WHERE id = :to_id;

COMMIT COMMENT :request_id;
```

B 路径不使用链接。`INSERT` 与第一条 `UPDATE` 走数据源 A，第二条 `UPDATE` 走数据源 B，方法上是 `@GlobalTransactional`。两条 SQL 的文本与 A 路径相同（远程更新没有 `@dblink`）。

幂等插入撞主键时：

- 若 journal 已存在且金额、账户一致，返回成功。
- 若主键冲突但载荷不一致，返回失败，人工核对。这表示调用方复用了 `request_id`。

性能用例的请求号用雪花或 UUID，不触发幂等冲突。R11 固定重复同一个 `request_id`。

## 4.6 观测脚本

每轮目录固定为 `run-id/{env,awr,app,oracle,seata,invariant}`。

环境头：

```sql
SELECT instance_name, host_name, version_full, startup_time FROM v$instance;
SELECT * FROM global_name;
SELECT name, value FROM v$parameter
 WHERE name IN ('open_links','distributed_lock_timeout','distributed_transactions',
                'commit_point_strength','global_names','processes','sessions');
```

采样期间每 10 秒：

```sql
-- 在 B 上：A 路径的会话放大
SELECT COUNT(*) sessions
FROM   gv$session
WHERE  username = 'APP';

-- 在 A 上，抽一条业务会话
SELECT db_link, logged_on, in_transaction, update_sent, open_cursors
FROM   v$dblink;

-- 两库
SELECT local_tran_id, global_tran_id, state, mixed, advice, tran_comment
FROM   dba_2pc_pending;

SELECT COUNT(*) blocked
FROM   gv$session
WHERE  event = 'enq: TX - row lock contention';
```

不变量在每轮结束后、以及每个可靠性用例恢复后各跑一次。性能长跑中间每 5 分钟跑一次轻量检查（只查 `balance < 0` 的计数和 pending 行数），全量 `SUM` 放在结束时，避免汇总扫描本身变成负载。

AWR 在预热结束后打一个快照，采样结束再打一个，对比等待事件。单次诊断用 `DBMS_MONITOR.SESSION_TRACE_ENABLE(waits=>TRUE)` 抓 20 笔，看 `SQL*Net message from dblink` 与 `log file sync` 的比例，然后关闭。

应用侧用 HdrHistogram 记录端到端延迟。B 路径额外打点：`begin`、`branchRegister`（从 Seata 度量或 debug 采样）、`globalCommit`。性能主项不要开 Seata debug。

## 4.7 压测夹具

一个可执行 JAR，两个模式：

```text
--mode=dblink|seata
--threads=1|10|50|100|200
--duration=600s
--warmup=120s
--pattern=uniform|hotspot
--amount=100
--close-dblink=false|true
--rtt-label=0.2ms|1ms|5ms|20ms
```

线程内无思考时间。每个请求生成 `request_id`，调用对应模式的转账方法，记录成功、业务拒绝、系统错误。系统错误保留 Oracle/Seata 错误码。

故障控制器是另一个进程，不放在业务线程里：

1. 在 A、B 执行 `ALTER SYSTEM DISABLE DISTRIBUTED RECOVERY`。
2. 触发一笔转账（可靠性用例可以在远程更新上加仅用于排障的 `DBMS_LOCK.SLEEP`，由 `--fault-profile` 打开；性能 JAR 不包含这段睡眠）。
3. 每 10 ms 查询 `DBA_2PC_PENDING` 或 `V$GLOBAL_TRANSACTION`。
4. 出现 `prepared` 后立刻执行本用例的故障动作，记下时间戳。
5. 按用例要求恢复网络或重新打开实例。
6. `ALTER SYSTEM ENABLE DISTRIBUTED RECOVERY`。
7. 轮询到 pending 为空且目标行无锁，记下恢复秒数，跑不变量。

B 路径的控制器轮询 TC 全局事务状态，在分支已 `PhaseOne_Done`、全局尚未完成时切断 TC 与应用之间的端口，或 `kill -9` TC。

若窗口窄到 30 次都抓不到 `prepared`，只允许在可靠性 profile 里把远程更新前的睡眠调到 2 秒再抓。抓到之后的恢复时间从故障动作起算，不包含这段睡眠。

## 本章小结

环境的关键不是机器多大，而是：CPS 固定、RECO 状态已知、链接类型已知、XA 授权已知、不变量可以在每轮结束时重算。会话放大用参与库的 `GV$SESSION` 计数，不用应用连接池的配置值代替。

---

# 五、性能用例

执行顺序：P0 → P1 → P3 → P4 → P5 → P2 → P7 → P8。P6 是 P5 的判定分支，不单独占一轮，除非 P5 失败。全部 P 用例开始前：RECO 启用、pending 为空、无 trace、Seata 日志 WARN、`--close-dblink=false`（P5 除外）。

采样 600 秒，预热 120 秒。下表的「通过标准」里，正确性都包含 3.4 节的四条，不再重复。

## P0 单线程基线

| 项 | 内容 |
|---|---|
| 目的 | 拆开一笔事务的端到端时间，确认主项比的是协调开销 |
| 步骤 | `--threads=1 --pattern=uniform`，A、B 各跑一轮。然后各抓 20 笔 10046 |
| 观测 | P50；AWR 中 `log file sync`、`SQL*Net message from dblink`；B 的 begin / register / commit 分段 |
| 通过标准 | 正确性成立。报告给出分段时间。此用例不比较高低，只作为后续曲线的 1 并发锚点 |
| 作废条件 | `UPDATE_SENT` 不是 `YES`（A 路径没升级成分布式事务）；或 B 路径只有一个 XA 分支 |

## P1 并发吞吐曲线

| 项 | 内容 |
|---|---|
| 目的 | 找到目标并发下的 TPS 与尾延迟 |
| 步骤 | 线程数 1、10、50、100、200，均匀流量，A、B 各 5 轮。线程数不超过连接池 |
| 观测 | 成功 TPS，P50/P95/P99/P999，错误码，行锁等待会话数，B 库 APP 会话峰值 |
| 通过标准 | 每个线程数正确性成立；错误率（不含余额不足）< 0.1%。画出两条曲线。按 3.5 的 15% 规则在业务选定的并发上判定 |
| 作废条件 | 出现 pending 残留；或采样中 RTT 漂移 > 20% |

连接池大小固定为 200，两路径相同。A 路径这个池只对发起库。B 路径对 A、B 两个 XA 池都是 200。这会让 B 路径在数据库侧拥有更多会话，是方案差异，报告里同时列出两边的会话峰值，避免只看 TPS。

## P2 单事务往返斜率

| 项 | 内容 |
|---|---|
| 目的 | 看远程 DML 条数增加时延迟怎么涨 |
| 步骤 | 在 P0 的 SQL 上，把贷记从 1 行改为同一事务内 10 行（10 个不同 `to_id`）。线程数取 P1 里未饱和的一档，建议 50 |
| 观测 | P50 相对 P0/P1 同并发的增量 |
| 通过标准 | 正确性成立。增量按“每多一条远程更新”列出来。不设胜负，供容量评估 |
| 作废条件 | 10 行被拆成 10 个事务 |

## P3 热点行

| 项 | 内容 |
|---|---|
| 目的 | 锁窗口越长，热点 TPS 越接近 `1 / 持锁时间` |
| 步骤 | `--pattern=hotspot --threads=50` 和 `100`。80% 借记 A 库 `id=1` |
| 观测 | TPS、P99、`enq: TX - row lock contention` 的总等待时间、`ORA-02049` 次数 |
| 通过标准 | 正确性成立，`id=1` 的余额与 journal 中该行借记笔数一致。比较两边的热点 TPS 和 P99 |
| 作废条件 | 热点账户被打成余额不足，导致大量业务拒绝。若发生，把初始余额提高到 `1e12` 后重跑 |

均匀流量下的差距来自协调开销；热点下的差距来自持锁时间。两个数字都要进第七章，不能只用均匀流量做结论。

## P4 库间 RTT

| 项 | 内容 |
|---|---|
| 目的 | 两阶段提交对 RTT 的敏感度 |
| 步骤 | 在 A↔B 的网卡上用 `tc netem delay` 制造额外 1 ms、5 ms、20 ms。基线是实验室原始 RTT（记为实测值，例如 0.2 ms）。并发固定 50，均匀流量。A 路径延迟加在两库之间。B 路径除了 A↔B，还要加一轮“只增加压测机↔TC 1 ms 和 5 ms、库间不变”，用来分离 TC 跳数 |
| 观测 | P50、P99、TPS、`SQL*Net message from dblink` |
| 通过标准 | 正确性成立。四档 RTT 各有一对 TPS/P99。库间延迟对两边都应近似线性变差 |
| 作废条件 | netem 加到了错误的网卡（用 ping 复核再开跑） |

B 路径应用本来就直连 B，库间 RTT 对 B 路径的业务 SQL 影响小，对 A 路径的每条远程 SQL 影响大。这个差异要在报告里写清楚，它是架构差异，不是 netem 配错。TC 那一轮单独说明“控制面 RTT”。

## P5 会话放大与关闭链接

| 项 | 内容 |
|---|---|
| 目的 | 验证实践 4 的容量公式，并量化 `CLOSE DATABASE LINK` 的延迟成本 |
| 步骤 | 并发 100，均匀，A 路径两轮：`--close-dblink=false` 与 `true`。B 路径一轮，池大小 100。每轮采样 B 库（以及 B 路径的 A 库）`username='APP'` 的会话峰值 |
| 观测 | 会话峰值、P50、P99、TPS、`PROCESSES` 使用率 |
| 通过标准 | `close=false` 时，B 库 APP 会话峰值与发起库忙会话数同量级（允许 ±15%）。`close=true` 时峰值明显回落。两条延迟都记录 |
| 判定 | 峰值 ≥ `PROCESSES` 的 70%：性能门改用 `close=true` 的数字，并在结论里写“上线必须关闭链接或扩容”。峰值低于 70%：性能门用 `close=false`，同时把关闭链接的延迟成本写成运维可选项 |
| 作废条件 | 连接池泄漏导致会话持续增长。先用池大小 1 复现并修复再跑 |

P5c（可选，不进入主表）：仅当 `close=true` 的 P99 不能接受时，按官方条件配置 shared server 与 `CREATE SHARED DATABASE LINK ... AUTHENTICATED BY`，单独跑并发 100。报告与主表分开。单用户预检若显示共享链接连接数反而更多，终止 P5c，与官方说明一致。

## P6 连接池打满

不单独制造。若 P1 在 200 线程出现获取连接超时，把该档标成“池上限”而不是方案 TPS，并在池增至 400 后补一轮。两路径池上限始终设成同一个数。

## P7 尾延迟与错误分布

| 项 | 内容 |
|---|---|
| 目的 | 确认 P99 不是由少量重试或日志刷盘造成的假峰值 |
| 步骤 | 取 P1 的 100 线程那一轮，输出延迟直方图和 Top 5 错误码。对照 AWR 的 top wait |
| 观测 | P99/P50 比值，Max 是否由单笔 in-doubt 或 GC 造成 |
| 通过标准 | 能指出 P99 的主要等待事件。若 Max 来自一次故障残留，该轮作废重跑 |
| 作废条件 | 采样窗口内出现 pending |

## P8 稳定性长跑

| 项 | 内容 |
|---|---|
| 目的 | 看会话、undo、延迟是否随时间爬升 |
| 步骤 | 并发取 P1 曲线上 TPS 最高档的 70%，均匀流量，持续 2 小时。A、B 各一轮 |
| 观测 | 每 5 分钟的 TPS、P99、B 库 APP 会话、undo 使用、pending 行数 |
| 通过标准 | pending 始终为 0；P99 后 1 小时相对前 1 小时上升不超过 20%；会话数平台化，不随时间单调上涨 |
| 作废条件 | 中途改过参数或发生过与用例无关的断连 |

## 本章小结

性能主项是 P1 的均匀曲线、P3 的热点和 P5 的会话峰值。P0 负责解释时间花在哪，P4 负责说明 RTT 是共同成本还是单方成本。P8 不过，前面的 TPS 不能作为选型依据。

---

# 六、可靠性用例

每个可靠性用例之前：

```sql
SELECT COUNT(*) FROM dba_2pc_pending;          -- 必须为 0
SELECT COUNT(*) FROM gv$session
 WHERE event LIKE 'enq: TX%' OR event LIKE '%in-doubt%';
```

用例之间把被修改的账户余额重新置为 1,000,000，或直接使用专用 id 段 `900001..900100`，避免和性能数据缠在一起。可靠性用例默认并发 1，R15 除外。

自动恢复类失败的定义：需要 DBA 执行 `COMMIT FORCE` 或 `ROLLBACK FORCE` 才能继续，或者恢复后不变量不成立。

## R1 正常提交

| 项 | 内容 |
|---|---|
| 步骤 | 单笔转账，金额 100，`from_id=900001`，`to_id=900001` |
| 观测 | 两库余额、journal 1 行、pending 为空 |
| 通过标准 | A 减少 100，B 增加 100，总和不变 |

## R2 业务回滚

| 项 | 内容 |
|---|---|
| 步骤 | 把 `900002` 余额改为 50，转账 100 |
| 观测 | 返回业务拒绝；两库余额不变；journal 无此 `request_id`；pending 为空 |
| 通过标准 | 无分布式残留。A 路径确认链接事务已结束（`V$DBLINK.IN_TRANSACTION=NO`） |

## R3 提交前失败

| 项 | 内容 |
|---|---|
| 步骤 | 在第二条更新之前杀应用进程（`kill -9`）。A、B 各做 |
| 观测 | 重启应用后查余额、journal、pending、行锁 |
| 通过标准 | 两库都回到转账前。没有 journal。没有残留锁。A 路径由 Oracle 回滚未提交事务；B 路径由 TC 超时回滚未完成的全局事务 |

这一步还没进入 prepared，不应出现需要 RECO 的 in-doubt。若出现，记录为缺陷。

## R4 提交窗口断网，然后愈合

| 项 | 内容 |
|---|---|
| 目的 | 两阶段中间失去联系后的自动收敛 |
| 步骤 | 控制器按 4.7 操作。抓到 `prepared` 后，用 iptables 丢弃 A↔B 的 1521（A 路径），或丢弃压测机↔TC 的 Seata 端口（B 路径）。保持 60 秒，再恢复通信，打开 RECO / 等 TC 重试 |
| 观测 | `DBA_2PC_PENDING.STATE` 变化、恢复秒数、行锁释放时刻、不变量 |
| 通过标准 | 不使用 FORCE。愈合后 pending 清空，两库同为提交或同为回滚，journal 与余额一致 |
| 记录 | 断网期间另一会话更新同一行，应出现锁等待；愈合后等待结束 |

A 路径的故障点在两库之间。B 路径的故障点在 TC 控制面。两次都要做。不要只断一边就宣称方案等价。

## R5 参与者在 prepared 后崩溃

| 项 | 内容 |
|---|---|
| 步骤 | 抓到 B 库（A 路径的参与者，或 B 路径的一个 XA 分支所在库）处于 prepared 后，对该库 `SHUTDOWN ABORT`，再 startup。A 路径随后 ENABLE RECO。B 路径等待 TC 重放 `XA COMMIT` 或 `XA ROLLBACK` |
| 观测 | 实例恢复时间与事务恢复时间分开记。后者从实例 OPEN 开始，到 pending 为空 |
| 通过标准 | 自动收敛，不变量成立，`ORA-01591` 在恢复后消失 |

## R6 协调者在 prepared 后崩溃

| 项 | 内容 |
|---|---|
| 步骤 | R6a：CPS 在 A（强度 200/50）。抓到 prepared 后对 Oracle A `SHUTDOWN ABORT` 再 startup。R6b：对调 `COMMIT_POINT_STRENGTH` 后重复。B 路径对应动作为 `kill -9` TC 进程再拉起（file 存储，同一节点） |
| 观测 | 谁先有结局、RECO 是否向 CPS 对齐、B 路径 TC 重启后是否按事务日志补发 XA 决议 |
| 通过标准 | 自动收敛。R6b 的恢复秒数单独列表，不和要求与 R6a 相同。B 路径 file 存储允许“单 TC 宕机期间业务不可用”，但重启后必须收敛 |

## R7 in-doubt 阻塞业务

| 项 | 内容 |
|---|---|
| 步骤 | 制造 R4 的断网且先不愈合。另一会话更新同一 `from_id`。确认阻塞后愈合 |
| 观测 | 阻塞会话的等待事件、是否报 `ORA-01591`、从断网到业务会话拿到结果的秒数 |
| 通过标准 | 能复现阻塞；愈合后业务会话完成；无 FORCE；不变量成立。两边都记录阻塞秒数 |

`ORA-01591` 的官方含义就是 in-doubt 分布式事务占着锁。本用例预期能看到锁等待；是否在 60 秒等锁超时里变成 `ORA-02049`，取决于第二会话的等待是否超过 `DISTRIBUTED_LOCK_TIMEOUT`。两种结局都记录，只要愈合后数据一致。

## R8 错误的人工 FORCE（演练，不进可靠性门）

| 项 | 内容 |
|---|---|
| 步骤 | 在隔离环境制造 in-doubt。查 `DBA_2PC_NEIGHBORS` 确认提交点。故意在非提交点上执行与对端相反的 `ROLLBACK FORCE` 或 `COMMIT FORCE` |
| 观测 | `DBA_2PC_PENDING.MIXED`、两侧余额 |
| 通过标准 | 操作手册能够发现 mixed，并用 `PURGE_MIXED`（以 4.3 节查到的包名为准）清理 pending，再用补偿 SQL 把余额修回不变量。修平之前不得开始下一用例 |

这个用例的价值是证明 FORCE 会制造不一致，从而约束生产权限：应用账号没有 `FORCE ANY TRANSACTION`。

## R9 Seata 与 Oracle 双决议（演练，不进可靠性门）

| 项 | 内容 |
|---|---|
| 目的 | 展示 B 路径独有的启发式分歧：TC 要回滚，DBA 或一端已经把 XA 分支强制提交 |
| 步骤 | B 路径进入 prepared 后暂停 TC 的完成阶段（断 TC→应用端口）。DBA 在其中一个 Oracle 上对应该分支 `COMMIT FORCE`。再恢复 TC，让它按全局回滚驱动分支 |
| 观测 | Seata 日志中的 heuristic / `XAER_NOTA`、Oracle `DBA_2PC_PENDING.MIXED`、两侧余额 |
| 通过标准 | 演练记录写明检测点和修复 SQL。A 路径没有第二个决议者，本用例不对 A 路径复制 |
| 对照 | A 路径的结局以 CPS 为准，RECO 向它对齐。把这段对照写进报告，作为可靠性差异的定性结论，不换算成分数 |

## R10 TC 高可用

| 项 | 内容 |
|---|---|
| 步骤 | R10a 已包含在 R6 的 file 单节点里。R10b：TC 改为 3 节点 Raft（`store.mode=raft`，`server.raft.sync=true`），业务并发 20，`kill -9` 当前 leader |
| 观测 | 选举期间失败的请求数、选举完成后新事务能否成功、旧事务是否收敛 |
| 通过标准 | leader 切换后新事务恢复成功；切换前已 prepared 的事务最终与 journal 一致。本用例不产出可与 P1 比较的 TPS |

A 路径没有对应组件。报告里写成“A 路径的协调高可用等于 Oracle A 的实例高可用”，不要给 A 路径虚构一个中间件切换时间。

## R11 不确定结果后的重试

| 项 | 内容 |
|---|---|
| 步骤 | 使用固定 `request_id=R11-1`。在 `COMMIT` 发出后、响应回到客户端之前杀应用（可用防火墙丢弃响应包，库侧事务继续）。客户端按超时重试同一 `request_id`，最多 3 次，间隔 1 秒 |
| 观测 | journal 行数、余额变化次数、重试请求是“幂等返回”还是“锁等待” |
| 通过标准 | 最终 journal 只有 1 行，余额只移动一次。若重试撞上尚未结束的 in-doubt，允许等待或超时，但不允许第二次入账 |
| 作废条件 | 重试生成了新的 `request_id` |

这是强一致方案最容易假通过的用例。只看“没有报错”不算通过，必须核对笔数。

## R12 主库角色切换（有 Data Guard 才做）

| 项 | 内容 |
|---|---|
| 步骤 | 链接的 TNS 指向只在主库注册的服务。执行 switchover。切换后立刻跑 R1 |
| 观测 | 链接是否连到新主库、切换期间 in-doubt 是否被 RECO 消化 |
| 通过标准 | 切换后 R1 通过；pending 为空 |
| 未覆盖 | 实验室没有 Data Guard 时，在结论表写“未覆盖”，不记为通过 |

## R13 超时语义

| 项 | 内容 |
|---|---|
| 步骤 | 可靠性 profile 让远程更新先 `DBMS_LOCK.SLEEP(70)`。B 路径全局超时 60 秒。A 路径不设置额外语句超时，SQL*Net 读超时仍为 120 秒 |
| 观测 | B 是否在约 60 秒回滚；A 的事务何时结束；两库余额 |
| 通过标准 | 不变量成立。报告如实写：B 有全局超时；A 没有同款机制，本轮会等到睡眠结束并提交，除非另行配置 SQL*Net 超时。这是行为差异，不是 A 的失败 |
| 后续动作 | 若业务要求 A 路径也必须在 60 秒内放弃，把 `SQLNET.RECV_TIMEOUT` 的取值和副作用另开一轮，不改写本轮结果 |

## R14 恢复后无残留

| 项 | 内容 |
|---|---|
| 步骤 | R4、R5、R6 每个用例恢复后执行 |
| 观测 | `DBA_2PC_PENDING` 行数、目标行的锁、`V$TRANSACTION` 中对应事务、B 路径 TC 中该 xid 的状态 |
| 通过标准 | pending 为 0，行锁为 0，TC 中该全局事务处于终态 |

## R15 浸泡与随机断网

| 项 | 内容 |
|---|---|
| 步骤 | 并发 20，均匀流量，30 分钟。每分钟有 10% 的概率对 A↔B（A 路径）或压测机↔TC（B 路径）丢包 5 秒，然后恢复。RECO 保持开启 |
| 观测 | 成功 TPS、系统错误数、pending 是否在断网结束后 2 分钟内归零、最终不变量 |
| 通过标准 | 最终不变量成立；每次愈合后 2 分钟内 pending 为 0；无手工 FORCE。允许断网窗口内的请求失败，失败请求不得入账 |

2 分钟是本 POC 的观察窗，不是 Oracle RECO 的承诺值。RECO 按指数退避重试，若 2 分钟不够，把实际清空时间写进表里，由业务方的 SLO 判定，不改原始数据。

## R-AUTH XA 授权负例（仅 B 路径）

| 项 | 内容 |
|---|---|
| 步骤 | 先用完整授权跑通 R5。再回收 `DBA_PENDING_TRANSACTIONS` 等授权，重复 R5 |
| 通过标准 | 有授权时 R5 通过。无授权时恢复失败或 pending 残留，作为授权清单的证据。测完立即恢复授权 |

## 本章小结

可靠性门只包含能自动收敛的用例：R4、R5、R6、R7、R11、R14、R15，外加环境项 R-AUTH。R8 和 R9 证明人工强制和双决议者会把系统带进 mixed outcome，用来写操作手册和权限边界。R13 固定记录“DB Link 没有全局事务超时”这一事实，避免上线后用 Seata 的超时直觉去期待 Oracle。

---

# 七、执行顺序、记录模板与选型回填

## 7.1 轮次

| 阶段 | 内容 | 退出条件 |
|---|---|---|
| 0 | 第二章红线 G1–G6 | 任一 G1–G4 成立则停止，不出性能数字 |
| 1 | 第四章环境自检、R1、R2 | 自检 SQL 与两笔功能通过 |
| 2 | P0、P1、P3、P5、P7 | 正确性成立，曲线完整 |
| 3 | P4、P2、P8 | P8 的会话和 P99 不爬升 |
| 4 | R3–R7、R11、R13、R14、R-AUTH | 自动恢复类不用 FORCE |
| 5 | R8、R9、R10b、R15、可选 R12 | 演练记录写完，浸泡不变量成立 |
| 6 | 填写 7.3 | 每格都有数字或“未覆盖” |

阶段 4 开始前确认性能用例没有把 RECO 留在 DISABLE。阶段 2 与阶段 4 使用不同的账户 id 段。

## 7.2 单轮记录

```text
run_id:
date:
mode:            dblink | seata
case_id:
git / jar:
oracle version:
seata version:
ojdbc version:

rtt_app_A_ms:
rtt_app_B_ms:
rtt_app_tc_ms:
rtt_A_B_ms:

threads:
pool_size:
close_dblink:
pattern:
duration_s:

success:
business_reject:
system_error:
tps:
p50_ms:
p95_ms:
p99_ms:
p999_ms:
max_ms:

sessions_app_on_B_peak:
processes_limit:
pending_after:
sum_delta:
journal_rows:
balance_negative:

recovery_s:          # 可靠性用例
manual_force:        # yes | no
top_wait_event:
notes:
```

原始 AWR、直方图 CSV、`DBA_2PC_PENDING` 采样和 Seata 日志按 `run_id` 归档。结论表只引用 `run_id`，不手抄未经归档的数字。

## 7.3 结论表（实测后填写）

### 红线

| 项 | 是否成立 | 证据 |
|---|---|---|
| G1 异构或含非 DB 资源 |  |  |
| G2 禁止库级直连 |  |  |
| G3 两库分属不同服务所有权 |  |  |
| G4 依赖跨库外键 |  |  |
| G5 事务持续时间超过数秒 |  |  |
| G6 主流量是跨库大查询 |  |  |

G1–G4 任一为“是”，结论写“不选 DB Link”，性能表仅作档案。

### 性能

| 并发 | A TPS | A P99 | B TPS | B P99 | A 会话峰值 / PROCESSES | 判定 |
|---|---|---|---|---|---|---|
| 50 |  |  |  |  |  |  |
| 100（或业务目标并发） |  |  |  |  |  |  |
| 热点 100 |  |  |  |  |  |  |
| RTT +5 ms @ 50 |  |  |  |  |  |  |
| RTT +20 ms @ 50 |  |  |  |  |  |  |
| close dblink = true @ 100 |  |  |  |  |  |  |

判定用第三章的 15% 规则填写：支持 A / 持平 / 支持 B。会话使用率 ≥ 70% 时，主判定改看 `close dblink = true` 那一行。

### 可靠性

| 用例 | A 恢复秒数 | A 人工介入 | B 恢复秒数 | B 人工介入 | 不变量 |
|---|---|---|---|---|---|
| R4 断网愈合 |  |  |  |  |  |
| R5 参与者崩溃 |  |  |  |  |  |
| R6 协调者崩溃 |  |  |  |  |  |
| R7 阻塞秒数 |  |  |  |  |  |
| R11 重试笔数 |  |  |  |  |  |
| R15 浸泡 |  |  |  |  |  |
| R13 超时行为 | 描述： |  | 描述： |  |  |

业务 SLO（恢复秒数上限）：________。超过上限记可靠性门失败。

### 选型结论

同时满足下面四条，才写“POC 支持采用 DB Link 2PC”：

1. G1–G4 全部为否，G5、G6 已通过缩短事务或拆分查询处理。
2. 性能门按 3.5 判定为“支持 A”或“持平”。
3. 可靠性门通过，且恢复秒数满足业务 SLO。
4. P5 的会话方案（保持链接或提交后关闭或扩容）已经写进容量方案，参与库 `PROCESSES` 按实测峰值配置。

性能持平且可靠性都通过时，默认倾向 DB Link，因为少一个 TC 组件；若团队已经运行 Seata、且 12 个月内要接入非 Oracle 资源，在结论里改选 Seata XA，并引用 G1 的预期，而不是引用本轮 TPS。

任一自动恢复用例需要 FORCE，结论写“该路径未通过可靠性门”，列出 `run_id`。不要用 P1 的吞吐弥补。

## 7.4 设计阶段已经能确定的分析

这些判断不依赖压测，来自第二章的机制差异。压测只能量化，不能推翻：

1. **A 路径的协调者就是发起库。** 少一个独立 TC，也就少一类“决议日志在库外”的故障。代价是应用账号必须能写远程表，并且参与库要承担与发起库连接池同量级的会话。
2. **B 路径的分支持久性仍在 Oracle，全局决议在 TC。** 所以 XA 授权、TC 存储和 `recover()` 是 B 路径的可靠性组成部分。R-AUTH 就是用来把这一点变成可见失败的。
3. **两边在 prepared 之后都持锁。** 热点与高 RTT 会同时伤害两边。P3、P4 若显示差距主要来自锁等待，选型应先缩短事务，而不是更换协调者。
4. **A 路径没有 Seata 那种全局事务超时。** 上线时要用 SQL*Net 超时和连接池废弃连接策略做背板，不能假设 60 秒后 Oracle 会主动回滚 in-doubt。
5. **人工 FORCE 对两边都是危险操作。** A 路径的默认修复者是 RECO；B 路径还要防止 TC 决议和 DBA FORCE 相反。应用账号不授予强制提交权限。

## 本章小结

执行顺序把红线放在第一道门，把 2 小时长跑放在故障注入之前，避免在一个会爬升的环境里解释 in-doubt。结论表的每一格要么是归档过的 `run_id`，要么是“未覆盖”。设计阶段能确定的五条分析已经写死，实测只填写数字和最终是否跨过四条采用条件。

---

# 八、参考资料

1. 工作区姊妹篇：[Oracle DBLink 2PC vs Seata.md](Oracle%20DBLink%202PC%20vs%20Seata.md)、[Oracle原理分析.md](Oracle原理分析.md)。
2. Oracle Database Administrator’s Guide，*Managing a Distributed Database*：共享链接、`OPEN_LINKS`、`V$DBLINK`、同义词与位置透明。https://docs.oracle.com/en/database/oracle/oracle-database/26/admin/managing-a-distributed-database.html
3. Oracle Database Administrator’s Guide，*Managing Distributed Transactions*：`COMMIT_POINT_STRENGTH`、`DBA_2PC_PENDING`、`DBA_2PC_NEIGHBORS`、RECO、`ALTER SYSTEM ENABLE/DISABLE DISTRIBUTED RECOVERY`、FORCE 的三种前提、mixed outcome。https://docs.oracle.com/en/database/oracle/oracle-database/26/admin/managing-distributed-transactions.html
4. Oracle Database Administrator’s Guide，*Developing Applications for a Distributed Database System*：远程会话生命周期、`ALTER SESSION CLOSE DATABASE LINK`、远程查询在 undo 上的事务锁。https://docs.oracle.com/en/database/oracle/oracle-database/26/admin/developing-applications-for-a-distributed-database-system.html
5. Oracle Database SQL Language Reference，`CREATE DATABASE LINK`：`SHARED`、`AUTHENTICATED BY`、current user / connected user / fixed user、`GLOBAL_NAMES`。https://docs.oracle.com/en/database/oracle/oracle-database/26/sqlrf/CREATE-DATABASE-LINK.html
6. Oracle Database Reference：`OPEN_LINKS`（默认 4，0 禁止分布式事务）、`DISTRIBUTED_LOCK_TIMEOUT`（默认 60 秒）。
7. Oracle Database Reference，*Recoverer Process (RECO)*。
8. Oracle Call Interface Developer’s Guide，*Session Pooling and Connection Pooling*：dedicated database link 会把会话绑在物理连接上。
9. Oracle Database JDBC Developer’s Guide，*Distributed Transactions*：`OracleXAResource.prepare/commit`，单分支才允许 one-phase commit。
10. Apache Seata 文档，*XA Mode*：执行阶段 `XA START/END/PREPARE` 与分支注册的顺序、完成阶段 `XA COMMIT/ROLLBACK`。https://seata.apache.org/docs/dev/mode/xa-mode/
11. Apache Seata 2.0 参数：`client.tm.defaultGlobalTransactionTimeout` 默认 60 秒，`server.xaerNotaRetryTimeout` 默认 60000 ms，`store.mode` 含 file 与 raft，RPC 超时默认 30 秒。
