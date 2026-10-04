# 数据库实现共享锁方案分析（读读共享 / 读写互斥 / 写写互斥）

> 整理日期：2026-10-04
>
> 内容范围：为什么需要"逻辑资源共享锁" → S/X 锁语义与兼容矩阵 → DB2 / Oracle / MySQL / PostgreSQL 四大数据库的能力分析与实现方案（含可运行脚本）→ 方案对照与工程化建议。

---

## 目录

1. [需求分析：什么是"应用级共享锁"](#1-需求分析什么是应用级共享锁)
2. [S/X 锁语义与兼容矩阵](#2-sx-锁语义与兼容矩阵)
3. [四大数据库能力总览](#3-四大数据库能力总览)
4. [PostgreSQL：Advisory Lock（原生最完整）](#4-postgresqladvisory-lock原生最完整)
5. [Oracle：DBMS_LOCK（题目指定方向）](#5-oracledbms_lock题目指定方向)
6. [MySQL：锁表 + FOR SHARE 模拟](#6-mysql锁表--for-share-模拟)
7. [DB2：锁表 + USE AND KEEP 锁子句模拟](#7-db2锁表--use-and-keep-锁子句模拟)
8. [四库方案对照与选型](#8-四库方案对照与选型)
9. [工程化建议](#9-工程化建议)
10. [参考资料](#10-参考资料)

---

## 1. 需求分析：什么是"应用级共享锁"

### 1.1 行锁为什么不够用

数据库自带的行锁（`SELECT ... FOR UPDATE` 等）绑定在**物理表的物理行**上，而工程上经常需要锁的是**逻辑资源**：

- 逻辑资源可能不是任何表的一行：如"月度对账任务"、"缓存重建流程"、"配置发布通道"、"外部接口调用配额"；
- 锁的持有者可能是**进程/任务**而非"正在改某行数据的事务"：如多个报表任务并发运行、一个数据修复任务要独占数据集；
- 期望的语义是：**多个"读方"任务可以同时进行，"写方"任务（重建/修复/发布）必须独占**——这正是"读读共享、读写互斥、写写互斥"。

这类锁需要数据库提供**命名锁（named lock）/ 咨询锁（advisory lock）**能力：按名字（逻辑资源标识）申请锁，由数据库锁管理器维护 S/X 模式与等待队列。

### 1.2 典型场景

| 场景 | 读方（S） | 写方（X） |
|------|----------|----------|
| 月末数据集 | 多个对账/报表任务并发跑 | 数据修复/补录任务独占该月数据 |
| 缓存/物化视图 | 大量查询任务并发读 | 缓存重建进程独占期间重建 |
| 配置发布 | 多个服务实例并发读配置 | 发布程序独占期间更新 |
| 外部系统对接 | 多个查询任务并发调用 | 批量同步任务独占期间调用 |

### 1.3 关键性质：协作式锁

所有这类锁都是**协作式（cooperative）**的：数据库只负责"按名字 + 模式"排队和互斥，**不阻止**绕过约定直接改数据的会话。要靠团队约定/统一封装保证所有访问方都先取锁。

另一个共同性质：**单实例作用域**。四库的锁管理器都是实例/库级别的，跨实例（多主、分库、异构库）的互斥需要 ZooKeeper/Redis 等外部协调者，本文只讨论单库内。

---

## 2. S/X 锁语义与兼容矩阵

实现"读读共享、读写互斥、写写互斥"，等价于提供两种锁模式并保证如下兼容关系：

| 已持有 \ 新请求 | **S（共享）** | **X（排他）** |
|----------------|:------------:|:------------:|
| **S（共享）** | ✅ 兼容 —— 读读共享 | ❌ 冲突 —— 读写互斥 |
| **X（排他）** | ❌ 冲突 —— 读写互斥 | ❌ 冲突 —— 写写互斥 |

由此派生的行为要求：

1. **读读共享**：多个会话同时持有同一资源的 S 锁，互不阻塞；
2. **读写互斥**：持有 S 时 X 请求等待；持有 X 时 S 请求等待；
3. **写写互斥**：X 与 X 冲突，排队；
4. **释放保证**：会话结束或事务提交/回滚时锁必须自动释放（否则进程崩溃导致死锁）；
5. （理想）**等待队列公平性**：避免"新来的 S 不断插队导致 X 饿死"。

> 一个容易混淆的点：本文的"共享锁"指**逻辑资源的命名锁**，不是表上的普通行 S 锁。但两者底层机制同源——第 6、7 章的方案正是"借行 S 锁的语义，来承载逻辑资源的 S/X"。

---

## 3. 四大数据库能力总览

| 能力 | Oracle | PostgreSQL | MySQL (InnoDB) | Db2 |
|------|--------|-----------|----------------|-----|
| 原生命名锁 API | ✅ **DBMS_LOCK**（题目指定） | ✅ **Advisory Locks** | ⚠️ GET_LOCK（**仅排他，无共享模式**） | ❌ 无 |
| 命名锁支持 S 模式 | ✅（S_MODE） | ✅（`_shared` 系列） | ❌ | ❌ |
| 会话级锁 | ✅（release_on_commit=FALSE） | ✅（`pg_advisory_lock`） | ✅（仅排他） | ❌（锁全部随事务结束释放） |
| 事务级锁 | ✅（release_on_commit=TRUE） | ✅（`pg_advisory_xact_lock`） | ✅ | ✅ |
| 非阻塞尝试 | ✅（REQUEST timeout=0） | ✅（`pg_try_*`） | ⚠️（timeout=0，仅排他） | ❌（靠数据库 LOCKTIMEOUT） |
| 死锁检测 | ✅（REQUEST 返回码 2） | ✅（统一死锁检测器） | ⚠️（行锁有，GET_LOCK 无） | ✅（SQLCODE -911） |
| 锁可见性/监控 | ✅ V$LOCK（TYPE='UL'） | ✅ pg_locks（locktype='advisory'） | ✅ performance_schema.data_locks | ✅ MON_GET_* 表函数 |

**结论先行**：

- **PostgreSQL**：Advisory Lock 原生支持共享/排他 × 会话级/事务级，是四库中最完整的方案，**首选**；
- **Oracle**：DBMS_LOCK 提供完整的锁管理器 API（六种模式、等待队列、事务/会话两种持有级别），**首选**；
- **MySQL**：没有共享模式的命名锁，但可以用**锁表 + 行级 S 锁（FOR SHARE）**完整模拟 S/X 语义，方案可靠；
- **Db2**：没有命名锁 API，但锁子句 `WITH RS USE AND KEEP SHARE/EXCLUSIVE LOCKS` 可以在锁表上取**保持到提交的行级 S/X 锁**，同样能完整模拟。

---

## 4. PostgreSQL：Advisory Lock（原生最完整）

PostgreSQL 的咨询锁（advisory lock）由标准的锁管理器（与表锁、行锁同一套 heavyweight lock 基础设施）实现：**按 64 位整数 key（或两个 32 位 key）命名，支持 S/X 两种模式、会话级与事务级两种持有级别**。

### 4.1 API 全家福

| 函数 | 模式 | 持有级别 | 说明 |
|------|------|---------|------|
| `pg_advisory_lock(key)` | X | **会话级** | 阻塞直到获取；须显式 `pg_advisory_unlock` |
| `pg_advisory_lock_shared(key)` | **S** | 会话级 | **读读共享的关键** |
| `pg_advisory_xact_lock(key)` | X | **事务级** | COMMIT/ROLLBACK 自动释放 |
| `pg_advisory_xact_lock_shared(key)` | **S** | 事务级 | |
| `pg_try_advisory_lock(key)` / `_shared` | X/S | 会话级 | 非阻塞，立即返回 bool |
| `pg_try_advisory_xact_lock(key)` / `_shared` | X/S | 事务级 | 非阻塞 |
| `pg_advisory_unlock(key)` / `_shared` | — | 会话级 | 显式释放 |
| `pg_advisory_unlock_all()` | — | 会话级 | 释放本会话全部咨询锁 |

- key 形式：单个 `bigint`，或两个 `int`（`pg_advisory_lock(int, int)`，第一个 int 常用作命名空间/类别）；
- 作用域：**每个数据库独立**（同集群不同库的同一个 key 互不冲突）。

### 4.2 完整实现方案（覆盖三种互斥语义）

```sql
-- 资源命名约定：用 hashtext 把业务资源名映射为 bigint key
--   （也可在应用侧维护 res_name -> key 的注册表，避免哈希碰撞歧义）

-- ── 读方（可多个并发）：会话级共享锁 ──────────────────────
SELECT pg_advisory_lock_shared(hashtext('resource:monthly_report'));
-- ... 执行只读任务 ...
SELECT pg_advisory_unlock_shared(hashtext('resource:monthly_report'));

-- ── 写方（独占）：事务级排他锁，提交自动释放，最省心 ────────
BEGIN;
SET LOCAL lock_timeout = '10s';                 -- 等锁超过 10s 报错，避免无限等待
SELECT pg_advisory_xact_lock(hashtext('resource:monthly_report'));
-- ... 执行修复/重建 ...
COMMIT;   -- 锁自动释放
```

并发行为验证（两个会话同时执行）：

```
会话A: SELECT pg_advisory_lock_shared(hashtext('r1'));    -- 成功
会话B: SELECT pg_advisory_lock_shared(hashtext('r1'));    -- 成功   → 读读共享 ✓
会话C: SELECT pg_advisory_lock(hashtext('r1'));           -- 阻塞   → 读写互斥 ✓
（A、B 释放后）C 获得锁
会话D: SELECT pg_advisory_lock(hashtext('r1'));           -- 阻塞   → 写写互斥 ✓
```

### 4.3 特性与坑

1. **等待队列公平**：咨询锁走统一锁管理器队列，X 等待时后续 S 请求不会无限插队，写方不会饿死；
2. **死锁检测**：与其他锁共用死锁检测器（`deadlock_timeout` 默认 1s），检测到环状等待自动中止一方（SQLSTATE `40P01`）；
3. **锁等待无原生超时参数**：`pg_advisory_lock` 会无限等（可被取消）。用 `SET LOCAL lock_timeout`（对咨询锁等待同样生效）或 `statement_timeout` 兜底，或改用 `pg_try_*` + 应用轮询；
4. **连接池是大坑**：会话级锁绑定**连接**。PgBouncer 的 transaction pooling 模式下同一会话的多条语句可能落到不同连接 → 锁丢失/错释放。**池化环境一律用 `_xact_` 事务级版本**；
5. 释放匹配：`pg_advisory_unlock` 只能释放本会话持有的锁，加/放锁必须同一连接、同一会话内配对（try-finally）。

---

## 5. Oracle：DBMS_LOCK（题目指定方向）

`DBMS_LOCK` 是 Oracle 提供给应用的**锁管理器 API**，与数据库内部锁（TM/TX enqueue）使用同一套 enqueue 基础设施：按名字申请锁句柄，以六种模式之一请求，由锁管理器维护兼容矩阵与等待队列。**这是四库中唯一"开箱即用"的命名共享锁能力。**

### 5.1 核心 API

| 过程/函数 | 作用 |
|-----------|------|
| `ALLOCATE_UNIQUE(lockname, lockhandle OUT, expiration_secs)` | 业务锁名 → 唯一句柄（同名返回同句柄，幂等） |
| `REQUEST(lockhandle, lockmode, timeout, release_on_commit)` | 请求锁，**返回 0=成功、1=超时、2=死锁**、3=参数错误 |
| `RELEASE(lockhandle)` | 释放（会话级锁必须显式释放） |
| `CONVERT(lockhandle, newmode)` | 已持锁模式间转换（如 S→X） |
| `SLEEP(seconds)` | 睡眠（12c 起推荐用 `DBMS_SESSION.SLEEP`） |

**锁模式常量**：`NL_MODE(1)`、`SS_MODE(2)`、`SX_MODE(3)`、`S_MODE(4)`、`SSX_MODE(5)`、`X_MODE(6)`。与 Oracle 内部表锁（TM）六级模式对应；本文只用 **S_MODE** 与 **X_MODE** 即可满足三种互斥语义，其余模式用于更精细的行级锁协调场景。

S/X 之间的兼容性与第 2 章矩阵一致：S+S 兼容、S-X 冲突、X-X 冲突（完整六级矩阵见 Oracle 文档）。

**两个关键参数**：

- `timeout`：等待秒数；`DBMS_LOCK.maxwait`（32767）表示无限等；**0 = 非阻塞尝试**；
- `release_on_commit`：`TRUE` → 事务级（COMMIT/ROLLBACK 自动释放）；`FALSE` → **会话级**（跨事务持有，须显式 RELEASE 或随会话结束释放）。

### 5.2 完整实现方案

```sql
-- ── 读方（可多个并发）：共享锁 ─────────────────────────────
DECLARE
  v_handle VARCHAR2(128);
  v_status INTEGER;
BEGIN
  DBMS_LOCK.ALLOCATE_UNIQUE('RESOURCE.MONTHLY_REPORT', v_handle);  -- 名字自动转大写
  v_status := DBMS_LOCK.REQUEST(
                lockhandle       => v_handle,
                lockmode         => DBMS_LOCK.S_MODE,   -- 共享模式
                timeout          => 10,                 -- 等 10 秒
                release_on_commit => FALSE);            -- 会话级
  IF v_status = 0 THEN
    -- ... 执行只读任务 ...
    DBMS_LOCK.RELEASE(v_handle);                        -- 用完显式释放
  ELSIF v_status = 1 THEN
    raise_application_error(-20001, '获取共享锁超时');
  ELSE
    raise_application_error(-20002, '获取共享锁失败, status=' || v_status);
  END IF;
END;
/

-- ── 写方（独占）：排他锁，事务级持有 ───────────────────────
DECLARE
  v_handle VARCHAR2(128);
  v_status INTEGER;
BEGIN
  DBMS_LOCK.ALLOCATE_UNIQUE('RESOURCE.MONTHLY_REPORT', v_handle);
  v_status := DBMS_LOCK.REQUEST(v_handle, DBMS_LOCK.X_MODE, 10, TRUE);  -- release_on_commit=TRUE
  IF v_status = 0 THEN
    -- ... 执行修复/重建 ...
    COMMIT;   -- 锁随事务提交自动释放；显式 RELEASE 也可以
  ELSE
    ROLLBACK;
    raise_application_error(-20003, '获取排他锁失败, status=' || v_status);
  END IF;
END;
/
```

JDBC 侧通过 `CallableStatement` 调用同样的 PL/SQL 块即可；建议把上述块封装成两个存储过程 `app_lock_s(name, timeout)` / `app_lock_x(name, timeout)`，应用只调用封装。

### 5.3 特性与坑

1. **权限**：普通用户默认可能没有 `EXECUTE ON DBMS_LOCK`，需 DBA 授予。生产惯例是建一个封装包（声明 AUTHID / 白名单锁名前缀）再按需授权，避免应用直接操作底层句柄；
2. **锁名全局唯一于实例**：`ALLOCATE_UNIQUE` 的名字全库共享，务必用"模块.资源"命名规范防撞名；
3. **监控**：应用锁在 `V$LOCK` 中以 `TYPE='UL'`（User Lock）出现，可据此排查"谁持锁、谁等待"；
4. **死锁返回码**：REQUEST 返回 2 表示死锁（锁管理器检测），应用按"可重试"处理；
5. **无权限时的替代方案**（粒度粗但零依赖）：
   ```sql
   LOCK TABLE app_lock_tab IN SHARE MODE;      -- 事务级表级共享锁（读读共享）
   LOCK TABLE app_lock_tab IN EXCLUSIVE MODE;  -- 事务级表级排他锁
   ```
   表级锁无法按逻辑资源细分，只适合资源种类很少的场景。另外 Oracle 行锁只有排他语义（`SELECT ... FOR UPDATE`），**没有 FOR SHARE**，行级共享锁不可行——这正是 DBMS_LOCK 的价值所在。

---

## 6. MySQL：锁表 + FOR SHARE 模拟

### 6.1 能力现状

- `GET_LOCK(name, timeout)` / `RELEASE_LOCK(name)`：**会话级命名锁，但只有排他语义**——两个会话不能同时 `GET_LOCK` 同名锁，**无法表达"读读共享"**。可用于纯互斥场景，但不能作为共享锁方案；
- InnoDB 行锁本身具备完整的 S/X 兼容语义：**S+S 兼容、S-X 冲突、X-X 冲突**。因此思路是：**建一张"锁表"，为每个逻辑资源预置一行，用行级 S 锁（FOR SHARE）表达读方、行级 X 锁（FOR UPDATE）表达写方**。锁的等待、排队、死锁检测全部复用 InnoDB 锁系统。

### 6.2 完整实现方案

```sql
-- ① 锁表与资源预注册（一次性）
CREATE TABLE app_lock (
  res        VARCHAR(128) NOT NULL,
  remark     VARCHAR(255) NOT NULL DEFAULT '',
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (res)
) ENGINE=InnoDB;

INSERT INTO app_lock (res, remark) VALUES
  ('monthly_report', '月度对账/修复互斥资源');
```

```sql
-- ② 读方（可多个并发）：事务级行 S 锁 —— 读读共享
SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED;  -- 建议本次会话 RC，减少间隙锁干扰
START TRANSACTION;
SELECT 1 FROM app_lock WHERE res = 'monthly_report' FOR SHARE;   -- 5.7 写法: LOCK IN SHARE MODE
-- ... 执行只读任务 ...
COMMIT;    -- 锁释放

-- ③ 写方（独占）：事务级行 X 锁 —— 读写互斥、写写互斥
SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED;
START TRANSACTION;
SELECT 1 FROM app_lock WHERE res = 'monthly_report' FOR UPDATE;
-- ... 执行修复/重建 ...
COMMIT;

-- ④ 非阻塞尝试（8.0+）
START TRANSACTION;
SELECT 1 FROM app_lock WHERE res = 'monthly_report' FOR UPDATE NOWAIT;      -- 抢不到立即报错
-- 或 FOR SHARE SKIP LOCKED —— 跳过被锁行，用于"拿得到就干，拿不到就放弃"
ROLLBACK;
```

并发行为与验证结果：两个读会话同时 `FOR SHARE` 均成功（S+S）；期间写会话 `FOR UPDATE` 等待（S-X）；写会话持锁期间另一写会话等待（X-X）——三种语义全部达成。锁在 COMMIT/ROLLBACK/连接断开时自动释放，**进程崩溃不会遗留死锁**。

### 6.3 特性与坑

1. **仅事务级**：InnoDB 行锁随事务结束释放，没有会话级选项。需要跨事务持有的锁只能靠"长事务"（不推荐，见上一篇文档 §6）或把任务改成事务内完成；
2. **autocommit 陷阱**：autocommit=1 时单条 `SELECT ... FOR SHARE` 的锁语句结束即释放，锁等于没加！**必须显式 `START TRANSACTION`（或 JDBC setAutoCommit(false)）**；
3. **资源行必须预先存在**：`WHERE res=?` 不命中就等于没锁。资源动态化时用 `INSERT IGNORE` 预注册（注意：注册动作本身在并发下靠主键冲突兜底）；
4. **避免 S→X 升级死锁**：同一事务先 `FOR SHARE` 再 `UPDATE` 同一行，两个 S 持有者同时升级会互相等待 → 死锁（1213，一方被回滚）。**进事务前就确定用 S 还是 X，不要中途升级**；
5. **RC 隔离级别更省事**：RR 下锁表扫描可能带间隙锁（对不存在的资源键尤甚），RC 下只有记录锁，行为更贴合"命名锁"预期；
6. **监控**：行锁状态在 `performance_schema.data_locks`（8.0）/ `information_schema.innodb_locks`（5.7），等待关系看 `sys.innodb_lock_waits`；
7. **写方饥饿**：InnoDB 对"新 S 请求 vs 排队中的 X"没有公平性保证，读方高频持续进入时写方可能饥饿——写方用 `NOWAIT` 轮询 + 退避，或在业务低峰执行。

---

## 7. Db2：锁表 + USE AND KEEP 锁子句模拟

### 7.1 能力现状

Db2 没有类似 DBMS_LOCK / advisory lock 的命名锁 API，**且所有锁都是事务级的**（提交/回滚即释放）。但 Db2 的锁子句体系可以让我们在一张锁表上精确取到"保持到提交"的行级 S/X 锁：

- `WITH RS USE AND KEEP SHARE LOCKS`：读取行时**立即取 S 锁并保持到提交**——多个会话可同时持有 → 读读共享；
- `WITH RS USE AND KEEP EXCLUSIVE LOCKS`：读取行时**立即取 X 锁并保持到提交** → 读写互斥、写写互斥；
- `USE AND KEEP` 子句要求配合 RS 或 RR 隔离级别（锁保持到提交正是 RS/RR 的天然属性，子句把"读时取 S 锁"显式升级为"取 S/X 锁"）。

### 7.2 完整实现方案

```sql
-- ① 锁表与资源预注册（一次性）
CREATE TABLE APP_LOCK (
  RES  VARCHAR(128) NOT NULL,
  PRIMARY KEY (RES)
);
INSERT INTO APP_LOCK (RES) VALUES ('MONTHLY_REPORT');
```

```sql
-- ② 读方（可多个并发）：行 S 锁保持到提交 —— 读读共享
--    （前置：关闭自动提交，JDBC setAutoCommit(false)）
SELECT 1 FROM APP_LOCK
 WHERE RES = 'MONTHLY_REPORT'
   WITH RS USE AND KEEP SHARE LOCKS;
-- ... 执行只读任务 ...
COMMIT;    -- 锁释放

-- ③ 写方（独占）：行 X 锁保持到提交 —— 读写互斥、写写互斥
SELECT 1 FROM APP_LOCK
 WHERE RES = 'MONTHLY_REPORT'
   WITH RS USE AND KEEP EXCLUSIVE LOCKS;
-- ... 执行修复/重建 ...
COMMIT;

-- ④ 粗粒度替代：表级 S/X（资源种类少时够用）
LOCK TABLE APP_LOCK IN SHARE MODE;
LOCK TABLE APP_LOCK IN EXCLUSIVE MODE;
```

并发行为：两个读会话同时执行 SHARE 版本均成功（S 锁兼容）；写会话执行 EXCLUSIVE 版本等待所有 S 释放；写持锁期间其余读写均等待——三种语义达成。锁随 COMMIT/ROLLBACK/连接断开自动释放。

### 7.3 特性与坑

1. **必须显式开事务**：与 MySQL 同理，autocommit 下单条 SELECT 的锁随语句结束释放，锁形同虚设；
2. **等待超时用库级配置**：Db2 没有语句级锁等待超时参数，靠数据库配置参数 `LOCKTIMEOUT`（按秒，超时报 SQLCODE -911/原因码 68）兜底；死锁自动检测回滚（SQLCODE -911/原因码 2）；
3. **"当前已提交"不影响显式锁请求**：Db2 9.7+ 的 CS/RS 读者默认读"当前已提交版本"以避免等待，但 `USE AND KEEP SHARE LOCKS` 是**真实的 S 锁请求**，遇到对方的 X 锁仍会按锁协议等待——互斥语义不受影响；
4. **锁升级监控**：虽然锁表通常只有少量行，仍建议关注 `LOCKLIST`/`MAXLOCKS`（锁升级）与锁等待事件（`MON_GET_APPL_LOCKWAIT` 表函数）；
5. **没有会话级锁**：需要跨事务持有时只能拉长事务——Db2 的锁是悲观锁体系，长事务直接放大锁冲突，务必把持锁期内的业务做短。

---

## 8. 四库方案对照与选型

| 维度 | PostgreSQL | Oracle | MySQL | Db2 |
|------|-----------|--------|-------|-----|
| **实现方式** | Advisory Lock（原生） | DBMS_LOCK（原生） | 锁表 + `FOR SHARE`/`FOR UPDATE` | 锁表 + `WITH RS USE AND KEEP SHARE/EXCLUSIVE LOCKS` |
| **读读共享** | ✅ `_shared` | ✅ `S_MODE` | ✅ 行 S 锁 | ✅ 行 S 锁 |
| **读写互斥** | ✅ | ✅ | ✅ | ✅ |
| **写写互斥** | ✅ | ✅ | ✅ | ✅ |
| **会话级持有** | ✅ | ✅ | ❌ | ❌ |
| **事务级持有** | ✅（推荐） | ✅（推荐） | ✅（唯一选择） | ✅（唯一选择） |
| **非阻塞尝试** | ✅ `pg_try_*` | ✅ timeout=0 | ✅ `NOWAIT`/`SKIP LOCKED`(8.0) | ❌（靠 LOCKTIMEOUT） |
| **等待超时** | `lock_timeout` | REQUEST 的 timeout 参数 | `innodb_lock_wait_timeout` | 库级 `LOCKTIMEOUT` |
| **死锁处理** | 自动检测，40P01 | REQUEST 返回 2 | 1213 回滚一方 | -911 回滚一方 |
| **锁监控** | `pg_locks WHERE locktype='advisory'` | `V$LOCK TYPE='UL'` | `performance_schema.data_locks` | `MON_GET_APPL_LOCKWAIT` |
| **进程崩溃安全** | ✅ 自动释放 | ✅ 自动释放 | ✅ 自动释放 | ✅ 自动释放 |

**选型建议**：

1. **PostgreSQL**：直接用 Advisory Lock，池化环境用 `_xact_` 版本——这是四库中语义最完整、实现成本最低的方案；
2. **Oracle**：直接用 DBMS_LOCK，封装成存储过程暴露给应用；注意先解决 EXECUTE 授权；
3. **MySQL**：锁表方案可靠性足够（复用 InnoDB 锁系统与死锁检测），但务必遵守"显式事务 + RC + 预注册资源行 + 不中途升级"四条纪律；
4. **Db2**：锁表方案同理，注意"显式事务 + LOCKTIMEOUT 配置"；锁表方案在 MySQL/Db2 上的所有坑都源于"用行锁承载命名锁"，**建议统一封装成框架层的 LockManager**（接口：`lockS(name, timeout)` / `lockX(name, timeout)` / `unlock()`），把事务边界、重试、命名规范收口在一处；
5. **跨实例需求**：以上方案全部是**单库实例内**有效。分库分表、多活场景请使用 Redis（Redlock）/ ZooKeeper / etcd 做分布式锁，数据库方案仅适合单库内的任务协同。

---

## 9. 工程化建议

1. **封装 + 收口**：不管哪种方案，都封装成统一的 LockManager 组件，禁止业务代码散写裸 SQL 取锁。封装层负责：锁名规范（`模块.资源`）、超时、重试、日志；
2. **加锁/放锁必须 try-finally 配对**：会话级锁（Oracle release_on_commit=FALSE、PG session advisory、MySQL GET_LOCK）尤其如此；事务级锁随事务边界自动释放，天然安全；
3. **超时与重试**：永远带超时（`lock_timeout` / timeout 参数 / NOWAIT 轮询），拿到锁失败要明确报错而不是无限挂起；重试加退避；
4. **先定模式再进事务**：S 和 X 的选择在事务开始前确定，避免 S→X 升级死锁；同一个资源上的多把锁按**全局固定顺序**获取，防死锁环；
5. **资源注册表**：MySQL/Db2 的锁表资源行要预注册，用主键/唯一约束兜底并发注册；锁名→锁键（PG 的 bigint key）的映射表可以顺带记录"谁在何时持有"，便于排障；
6. **可观测性**：把持锁时长、等待次数、超时/死锁次数打点监控；四库各自的锁视图（见 §8 表）纳入巡检；
7. **记住协作式本质**：锁只对"遵守约定"的会话生效，写路径必须走统一入口；同时它是实例内的，跨库协同交给分布式锁中间件。

---

## 10. 参考资料

1. PostgreSQL 官方文档 —— Chapter 13.4 Advisory Locks、Chapter 13.3 Transaction Isolation、`lock_timeout` 参数
2. Oracle Database PL/SQL Packages and Types Reference —— DBMS_LOCK（ALLOCATE_UNIQUE / REQUEST / RELEASE / CONVERT）、Oracle Database Concepts（enqueue/TM 锁模式兼容矩阵）
3. MySQL 8.0 Reference Manual —— Locking Functions（GET_LOCK）、15.7.3 InnoDB Lock Types（S/X/记录锁）、SELECT 语法（FOR SHARE NOWAIT / SKIP LOCKED）
4. IBM Db2 11.5 Knowledge Center —— Isolation levels 与 USE AND KEEP SHARE/EXCLUSIVE LOCKS 子句、LOCK TABLE statement、LOCKTIMEOUT 配置参数
5. 上一篇姊妹篇：[DB事务隔离级别.md](DB事务隔离级别.md)（本文锁表方案依赖的 S/X 行锁语义、各库隔离级别行为）
