# MySQL 与 Oracle 的 MVCC 实现方案与事务隔离级别对比

> 整理日期：2026-10-16
>
> 内容范围：MVCC 要解决的问题与通用模型 → MySQL InnoDB 的「undo 版本链 + ReadView」实现 → Oracle 的「SCN + UNDO 一致性读」实现 → 两库如何各自用 MVCC 支撑隔离级别 → 逐维度对比 → 工程实践与排障 → 参考资料。
>
> 姊妹篇：[DB事务隔离级别.md](DB事务隔离级别.md)（隔离级别规范与四库对照）、[DB共享锁.md](DB共享锁.md)（应用级 S/X 锁方案）。本文把镜头收窄到 **MySQL 与 Oracle 两家的 MVCC 内部机制**。

---

## 目录

1. [MVCC 要解决的问题与通用模型](#1-mvcc-要解决的问题与通用模型)
2. [MySQL InnoDB 的 MVCC](#2-mysql-innodb-的-mvcc)
3. [Oracle 的 MVCC](#3-oracle-的-mvcc)
4. [两库 MVCC 如何支撑事务隔离级别](#4-两库-mvcc-如何支撑事务隔离级别)
5. [逐维度对比](#5-逐维度对比)
6. [工程实践、观测与常见误区](#6-工程实践观测与常见误区)
7. [参考资料](#7-参考资料)

---

## 1. MVCC 要解决的问题与通用模型

### 1.1 读与写为什么会打架

数据库并发控制的最底层矛盾只有一句话：**读操作和写操作想访问同一份数据**。

用锁解决时只有两条路：

| 策略 | 做法 | 代价 |
|------|------|------|
| 读加共享锁（S） | 读者与读者兼容，读者与写者互斥 | 读阻塞写、写阻塞读；读多写多的系统吞吐崩塌 |
| 读不加锁（读未提交） | 读者直接读"正在被改"的数据 | 脏读、不可重复读、读到中间态 |

两者都不可接受。**MVCC（Multi-Version Concurrency Control，多版本并发控制）提供了第三条路：不给数据上锁，而是给数据保留多个历史版本——读者去读"属于自己的那个版本"，写者去写"最新的那个版本"，双方互不干扰。**

于是得到 MVCC 的两条基本性质（MySQL 与 Oracle 都满足）：

1. **读不阻塞写**：读者永远不需要等待写者释放锁；
2. **写不阻塞读**：写者修改数据时不需要阻止读者（读者看旧版本）。

> 注意措辞的精确性：上面两条说的是**读**。**写-写冲突**在任何 MVCC 实现里都依然要靠锁排队（MySQL 的行锁、Oracle 的 TX enqueue），MVCC 从不解决写-写问题。

### 1.2 核心思路：把"时间"变成数据的一部分

MVCC 的本质是：**给每个数据版本打上"谁写的 / 什么时候写的"标记，让读操作按自己的"快照时间点"挑版本。**

要做到这一点，任何 MVCC 实现都必须回答下面四个问题：

| 必备部件 | 要回答的问题 | MySQL InnoDB 的答案 | Oracle 的答案 |
|---------|-------------|-------------------|--------------|
| **① 版本标识** | 这个版本是谁写的？ | 行内隐藏列 `DB_TRX_ID`（事务 ID） | 数据块头 ITL 槽中的 XID；可见性比较用该事务的 **commit SCN** |
| **② 版本存储** | 旧版本放在哪里？ | undo log（回滚日志）中的 undo 记录 | UNDO 表空间中的 undo 记录（前像） |
| **③ 版本定位** | 怎么从当前版本找到旧版本？ | 行内隐藏列 `DB_ROLL_PTR` 串成**版本链**，逐行回溯 | 行头 `LB` → ITL 槽 → `UBA` → undo 记录，**按块回溯** |
| **④ 可见性规则** | 哪个版本对我可见？ | **ReadView**（活跃事务列表 + 高低水位）做逻辑判断 | **SCN**（查询快照号）与块/事务的 SCN 比较，不满足则**物理回滚块** |

这四件套的差异，就是 MySQL 与 Oracle 两套 MVCC 全部差异的来源。

### 1.3 两条实现路线

把上面的四件套按"③版本定位"和"④可见性规则"的组合归类，业界只有两条主流路线：

**路线 A：按行组织版本链 + 逻辑可见性判断（MySQL InnoDB）**

```
读一行数据：
  取当前版本 → 用 ReadView 判断「写这个版本的事务，在我取快照时提交了吗？」
     ├─ 提交了 → 就用它
     └─ 没提交（或在我快照之后才活跃） → 沿着 roll_ptr 找上一个版本，重新判断
```

特点：判断发生在**逻辑层**（比较事务 ID 集合），旧版本按**行**组织成链表，按需回溯。

**路线 B：按块保留前像 + 物理重构一致性块（Oracle）**

```
读一行数据：
  取所在的数据块 → 判断「这个块上有没有"比我查询时点更新"的变更？」
     ├─ 没有 → 直接读块里的行
     └─ 有   → 沿 undo 把块上过新的变更逐条撤销，在内存里重构出一个"我那个时刻的块"（CR 块），再读它
```

特点：判断与修复发生在**物理层**（块级 undo 应用），快照只是一个数字（SCN），不需要维护"活跃事务列表"。

> **一句话记住两条路线的差别**：
> MySQL 问的是「**写这行的事务，在我取快照时提交了吗**」（判断事务状态）；
> Oracle 问的是「**这个变更，发生在我查询时点之前吗**」（判断时间先后），过了就物理撤掉。

这个差别会连锁地导致：Oracle 必须在 undo 里保留"能被时间比较"的块前像（undo 被覆盖就 `ORA-01555`），而 MySQL 必须维护活跃事务列表与 purge 机制（长事务导致 undo 膨胀但不报错）。详见 §5。

### 1.4 快照的边界 = 隔离级别

MVCC 只提供机制，"读到多旧的版本"由**快照的边界**决定。所以——

**「MySQL 和 Oracle 是如何用 MVCC 实现隔离级别的？」这个问题的答案可以压缩成一句：**

> **隔离级别 = MVCC 的快照粒度（语句级 / 事务级）+ 读操作是否切换到锁路线 + 写冲突的检测时机。**

后面 §4 会把这个公式在两库上逐档展开。

---

## 2. MySQL InnoDB 的 MVCC

### 2.1 三个隐藏列

InnoDB 的每一行记录（聚簇索引叶子节点上的记录）都带若干**用户不可见**的系统列：

| 隐藏列 | 长度 | 作用 |
|--------|------|------|
| `DB_TRX_ID` | 6 字节 | 最近一次**插入或修改/删除**这行记录的事务 ID |
| `DB_ROLL_PTR` | 7 字节 | 回滚指针，指向 undo log 中**该行的上一个版本**（更准确地说，指向 undo 记录中的回滚段地址） |
| `DB_ROW_ID` | 6 字节 | 单调递增行 ID；**仅当表没有主键、也没有非空唯一索引时**才会生成（此时 InnoDB 用它建聚簇索引，即"隐藏主键"） |

此外每条记录的**记录头**中有一个 `delete_flag`（删除标记位）。

所以：**`DB_TRX_ID` 回答了"谁写的"，`DB_ROLL_PTR` 回答了"上一个版本在哪"，两者配合构成版本链。**

### 2.2 undo log：旧版本的家

undo log 是 InnoDB 的逻辑日志，分类与用途：

| undo 类型 | 产生时机 | 用途 | 提交后能否立即丢弃 |
|-----------|---------|------|------------------|
| **insert undo** | `INSERT` | 只用于事务回滚（把插入的行删掉） | **可以**。因为该行的 `trx_id` 晚于所有已存在的 ReadView，没有任何活跃事务需要看到它的"上一个版本" |
| **update undo** | `UPDATE` / `DELETE` | ① 事务回滚；② **一致性读（MVCC）回溯旧版本** | **不可以**，必须保留到"没有任何活跃 ReadView 还需要它"为止 |

undo 记录的常见类型（记录在 undo 页的 record type 中）：

- `TRX_UNDO_INSERT_REC`：插入的回滚信息（主键值）；
- `TRX_UNDO_UPD_EXIST_REC`：更新已存在行的回滚信息（被改列的旧值）；
- `TRX_UNDO_DEL_MARK_REC`：删除标记的回滚信息；
- `TRX_UNDO_UPD_DEL_REC`：把已删除标记的行重新更新。

undo 记录同样是**被 redo 保护的、持久化的数据**（存放在 undo 表空间中，8.0 起可独立配置、可自动 truncate）。

### 2.3 版本链

假设对同一行执行了三次修改，事务 ID 分别为 100、200、300（300 为当前最新版本，尚未提交）：

```
   聚簇索引叶子节点上的当前记录
   ┌──────────────────────────────────────────────┐
   │ 真实列数据 (name='C')                          │
   │ DB_TRX_ID  = 300        ← 最新版本的写者        │
   │ DB_ROLL_PTR ───────────┐                      │
   └────────────────────────┼──────────────────────┘
                            │
                     undo log（update undo）
                            ▼
                    ┌────────────────────┐
                    │ 老版本: name='B'    │
                    │ TRX_ID = 200       │
                    │ ROLL_PTR ──────┐   │
                    └────────────────┼───┘
                                     ▼
                             ┌────────────────────┐
                             │ 老版本: name='A'    │
                             │ TRX_ID = 100       │
                             │ ROLL_PTR = NULL    │  ← 链尾
                             └────────────────────┘
```

**要点**：链头永远在聚簇索引的记录里（当前版本），链尾是"这行的最初形态"。回溯是**单向的**（从新到旧），而且**只有 UPDATE 产生的 undo 才在这条 MVCC 链上**（insert undo 不参与一致性读）。

### 2.4 ReadView：可见性判断的依据

ReadView 是事务做**快照读**时生成的一个"可见性判据快照"，包含四个字段：

| 字段 | 含义 |
|------|------|
| `m_ids` | 生成 ReadView 那一刻，系统中**所有活跃（未提交）事务 ID 的列表** |
| `m_up_limit_id`（低水位） | 活跃列表中的**最小值**；小于它的事务 ID 一定已提交 |
| `m_low_limit_id`（高水位） | **下一个将要分配的事务 ID**；大于等于它的事务在生成 ReadView 时还不存在 |
| `m_creator_trx_id` | 创建这个 ReadView 的事务自己的 ID（用于识别"我自己改的"） |

> 大小顺序恒为：`m_up_limit_id ≤ min(m_ids) ≤ max(m_ids) < m_low_limit_id`。

### 2.5 可见性判断算法

对版本链上从新到旧的每个版本，用以下步骤判断它是否对当前事务可见（以版本的事务 ID 记为 `trx_id`）：

```
对当前版本 V，取 trx_id = V.DB_TRX_ID：

① trx_id == m_creator_trx_id ?
       → 可见（这是本事务自己改的，永远看得见）
② trx_id <  m_up_limit_id ?
       → 可见（写它的事务在取快照前就已提交）
③ trx_id >= m_low_limit_id ?
       → 不可见（写它的事务在取快照时还不存在 —— 后来的插入）
④ 否则（m_up_limit_id <= trx_id < m_low_limit_id，即"取快照时它正活跃或刚提交"）：
       trx_id ∈ m_ids ?  → 不可见（取快照时它还没提交）
       trx_id ∉ m_ids ?  → 可见（取快照时它已提交，只是 ID 落在区间内）

若当前版本不可见：
    若 DB_ROLL_PTR 非空 → 沿版本链取上一个版本，回到 ① 重新判断
    若已到链尾        → 说明这行在我快照时点"不存在"（跳过这一行）
```

此外还需结合记录头的 `delete_flag`：如果某个可见版本上带着删除标记，则这行对该事务而言是"已删除"（对 UPDATE 型删除，链上会有旧版本可供回溯）。

### 2.6 ReadView 的生成时机 = RC 与 RR 的唯一区别

这是 InnoDB MVCC 最需要记牢的一点：**RC 与 RR 在代码路径上没有区别，唯一的差别是 ReadView 什么时候创建、复用多久。**

| 隔离级别 | ReadView 生成时机 | 效果 |
|---------|------------------|------|
| **READ UNCOMMITTED** | 不生成（不做可见性过滤），永远读版本链**最新**版本，哪怕是未提交的 | 脏读 |
| **READ COMMITTED** | **每条语句**（每次快照读）生成一个新的 ReadView | 每条语句都能看到"此刻已提交"的最新状态 → 语句之间数据可变 → 不可重复读、幻读 |
| **REPEATABLE READ** | 事务内**第一次快照读**时生成，之后整个事务**复用同一个 ReadView** | 全程看到同一个时点的数据 → 可重复读、快照读无幻读 |

两个高频误解，必须澄清：

**误解 1：「RR 的快照在 `BEGIN` 那一刻生成」。**
不是。`BEGIN` / `START TRANSACTION` 只开启事务、不取快照。快照在**事务内第一条快照读（普通 SELECT）**时才定格。所以下面的序列在 RR 下**会**读到 T2 已提交的新值：

```sql
T1: BEGIN;                                        -- 只开启事务，未取快照
T2: UPDATE goods SET price = 189 WHERE id=100; COMMIT;
T1: SELECT price FROM goods WHERE id = 100;       -- 读到 189！快照在此刻才定格
```

要想"事务一开始就定格快照"，必须显式写：

```sql
START TRANSACTION WITH CONSISTENT SNAPSHOT;   -- 仅 REPEATABLE READ 下有效
```

**误解 2：「RR 下 ReadView 是"只读"的」。**
不完全。ReadView 只用于**快照读**的可见性判断；事务自己的 **DML（当前读）不受 ReadView 约束**，永远作用于最新版本。因此：

```sql
BEGIN;  -- RR
SELECT balance FROM account WHERE id = 1;   -- 快照读：500（快照定格）
-- 另一事务把 balance 改成 400 并提交
UPDATE account SET balance = 300 WHERE id = 1;  -- 当前读：基于 400 加锁执行，不是 300-200
SELECT balance FROM account WHERE id = 1;   -- 快照读：300（自己改的，可见）
```

第二条 SELECT 看到 300 而不是"快照里的 500"，说明**同一事务内"快照读"和"当前读"混合使用会看到不一致的数据**——这是 InnoDB RR 最著名的坑。

### 2.7 快照读 vs 当前读

InnoDB 的 MVCC **只服务于"快照读"**，这是它与 Oracle「所有读都是一致性读」的关键不同：

| 读法 | 语句 | 读到的版本 | 加锁 | 是否走 MVCC |
|------|------|-----------|------|------------|
| **快照读**（一致性非锁定读） | 普通 `SELECT` | ReadView 判定的可见版本（可能是旧版本） | 不加锁 | ✅ |
| **当前读**（锁定读） | `SELECT ... FOR UPDATE`、`SELECT ... FOR SHARE` | **最新已提交版本** | 加 S/X 行锁 | ❌（无锁语义） |
| **当前读**（隐式） | `UPDATE`、`DELETE`、`INSERT ... ` | **最新已提交版本** | 加 X 行锁 | ❌ |

**推论（工程上极其重要）**：任何"读出来 → 应用计算 → 写回去"的逻辑，读端走的是快照读（旧版本），写端走的是当前读（最新版本）。两者之间的时间差就是丢失更新的产生窗口：

```sql
-- ❌ 字面量回写：读出 500，应用算出 300，写回 300 —— 中间的并发修改被覆盖（丢失更新）
SELECT balance ...;                    -- 快照读 500
UPDATE t SET balance = 300 WHERE id=1; -- 强行覆盖，无论此刻实际是 400 还是别的

-- ✅ 相对表达式：把计算交给数据库，在行锁保护下基于最新值计算
UPDATE t SET balance = balance - 200 WHERE id = 1;
```

### 2.8 四个隔离级别在 InnoDB 中如何落地

InnoDB 是四库中唯一完整提供四个标准级别的（默认 `REPEATABLE READ`）：

| 隔离级别 | 快照读是否 MVCC | ReadView 时机 | DML/锁定读 | 幻读（快照读） | 幻读（当前读） |
|---------|----------------|--------------|-----------|--------------|--------------|
| **READ UNCOMMITTED** | 走版本链但不做可见性判断（读最新版本，含未提交） | 无 | 当前读 | 可能 | 可能 |
| **READ COMMITTED** | ✅ | 每条语句 | 当前读 + 行锁（配合半一致性读） | 可能 | 可能（不加间隙锁） |
| **REPEATABLE READ**（默认） | ✅ | 首次快照读，事务内复用 | 当前读 + 行锁 + **Next-Key Lock** | **不可能** | 用 Next-Key Lock 阻止范围内的插入 |
| **SERIALIZABLE** | 基本不用（普通 SELECT 被隐式转为 `FOR SHARE` 锁定读） | — | 全部走锁路线 | 不可能 | 不可能（但并发度极低、死锁率高） |

> RR 为什么是默认：历史原因是**基于语句的 binlog 复制**。主库上并发交错的语句序列，在从库按顺序重放必须得到相同结果，这要求主库事务内部有一致的数据视图——RR + Next-Key Lock 才让 STATEMENT 格式的复制安全。这也是 MySQL 5.7 之后即便默认 `binlog_format=ROW` 仍保留 RR 默认值的原因（兼容性与历史惯性）。

SERIALIZABLE 的实现方式值得一提：它**不是**给 MVCC 加更强的可见性判断，而是**干脆放弃 MVCC，退化为锁读**——普通 `SELECT` 被隐式改写为 `SELECT ... FOR SHARE`。所以在 InnoDB 里"SERIALIZABLE"的本质是"用悲观锁串行化"，而不是"更强的一致性快照"。

### 2.9 RR 下幻读是怎么被防住的（"两条腿"）

MySQL RR 的幻读防护必须分读法讨论：

1. **快照读**：MVCC 天然防住。其他事务新插入的行，其 `DB_TRX_ID >= m_low_limit_id`（在快照之后才存在），可见性判断直接判为不可见 → 对本次快照读"不存在"。**不需要任何锁。**
2. **当前读**：`UPDATE` / `DELETE` / `FOR UPDATE` / `FOR SHARE` 必须作用于最新版本，MVCC 帮不上忙，必须靠 **Next-Key Lock**：
   - Next-Key Lock = **记录锁（Record Lock）+ 该记录之前的间隙锁（Gap Lock）**；
   - 它对扫描命中的索引记录**及其前方间隙**一并加锁，从而阻止其他事务在查询范围内插入新记录 → 防幻读；
   - 唯一索引等值查询且记录存在时，Next-Key Lock **退化为纯记录锁**（间隙已无意义）；
   - 配套还有**插入意向锁（Insert Intention Lock）**用来协调并发插入。

**由此产生经典的"幻读复现题"**：一个事务内先做快照读、再做当前读，两种机制看到的行集合不同——

```sql
-- RR 下
T1: SELECT COUNT(*) FROM orders WHERE day='2026-10-16';        -- 快照读：5 条
T2: INSERT INTO orders (day, ...) VALUES ('2026-10-16', ...); COMMIT;
T1: UPDATE orders SET status='X' WHERE day='2026-10-16';        -- 当前读：作用于 6 条！
T1: SELECT COUNT(*) FROM orders WHERE day='2026-10-16';        -- 快照读：6 条 ← 幻影出现了
```

最后一条 SELECT 看到了 6 条，因为 **UPDATE（当前读）把 T2 插入的行也改了，改成了本事务的 `trx_id`**，于是这个新版本对本事务可见了。这不是"MVCC 失效"，而是"两条腿混用"的必然结果。

### 2.10 二级索引与 MVCC

一个容易被忽略但面试与实际排障都会用到的点：

**InnoDB 的二级索引记录不含 `DB_TRX_ID` / `DB_ROLL_PTR`**，它只存「索引列值 + 主键值」。因此：

1. 走二级索引的查询，**必须回表（查聚簇索引）**才能拿到 `DB_TRX_ID` 做可见性判断——即使使用覆盖索引（covering index）也躲不掉这一步的可见性判断逻辑；
2. 对二级索引本身，InnoDB 通过 `delete_flag` 和"该二级索引记录所属行的可见性"来处理；被删除标记的索引记录会在 purge 阶段才真正清理；
3. 一个直接后果：**"理论上的覆盖索引仍可能产生聚簇索引访问"**，这是 InnoDB 二级索引扫描比预期慢的常见原因；也因此出现过"索引页记录数与聚簇索引可见行数不一致"的现象（index 里还有已删除标记的记录）。

Oracle 不存在这个问题：它的索引是**独立的段**，索引块的旧版本同样从 UNDO 里重构，索引扫描与表扫描的一致性读机制完全一致。

### 2.11 多版本的回收：purge 与 history list

旧版本不能无限保留，InnoDB 用 **purge 机制**回收：

1. 事务每次修改（写 update undo）都会把"该事务的 undo 记录"挂到**全局 history list** 上；
2. purge 线程（`innodb_purge_threads`，默认 4）沿 history list 从旧到新扫描，判断"commit 后是否还有活跃 ReadView 需要这条 undo 记录"；
3. 若没有任何活跃事务需要（即**不存在比该事务更早开始的活跃 ReadView**），则该 undo 记录及其 undo 页可被安全回收/复用；
4. 关键指标 **`History list length`**（`SHOW ENGINE INNODB STATUS`）：表示"已提交但还不能清理的 undo 数量"。它**直接反映长事务的积压程度**。

**长事务的危害链条**：

```
长事务持有旧 ReadView
   → purge 无法回收它可能需要的所有 undo
   → history list 长度飙升
   → undo 表空间膨胀（且不能 truncate）
   → 版本链变长，后续快照读沿链回溯的开销变大（每次读都要多跳几级）
   → 整体性能雪崩
```

对应地有两个限流参数：`innodb_max_purge_lag`（history list 超过阈值时给 DML 主动加延迟）、`innodb_max_purge_lag_delay`（最大延迟微秒数）。

> **与 Oracle 最重要的行为对比**：InnoDB **永远不会**因为"版本太旧"报错。它选择**保留 undo 直到没人需要**，代价是空间与性能膨胀。Oracle 选择"undo 空间有限、过期就报 `ORA-01555`"。详见 §5.5。

### 2.12 一个完整时间线：RC 与 RR 行为对照

统一场景（表 `goods(id=100, price)`，初值 199）：

```sql
T1: BEGIN;                                          -- 开启事务（两者都不取快照）
T2: UPDATE goods SET price = 189 WHERE id=100;
T2: COMMIT;
T1: SELECT price FROM goods WHERE id = 100;         -- ①
T3: UPDATE goods SET price = 179 WHERE id=100;
T3: COMMIT;
T1: SELECT price FROM goods WHERE id = 100;         -- ②
T1: COMMIT;
```

| 读点 | READ COMMITTED | REPEATABLE READ |
|------|---------------|-----------------|
| ① | **189**（每条语句新建 ReadView，T2 已提交可见） | **189**（此刻才创建 ReadView，快照定格在 T2 提交之后） |
| ② | **179**（新建 ReadView，T3 已提交可见）→ **不可重复读** | **189**（复用 ① 的 ReadView，T3 的 `trx_id` 在活跃列表/高水位之外 → 不可见）→ **可重复读** |

如果还要展现丢失更新，在 T1 的两次读之间插入 `T3: UPDATE ... SET price = <T1读到的值>` 即可——但更值得记住的是：**两库的 RC 都是语句级快照，两库的"写回字面量"都会丢更新**（见 §5.6）。

### 2.13 补充：RC 特有的半一致性读

READ COMMITTED 下，InnoDB 对 `UPDATE`/`DELETE` 实现了**半一致性读（semi-consistent read）**：

- 当一个 UPDATE 扫描到一行、发现该行已被其他未提交事务锁定并正在等待时；
- InnoDB 会先读这行的**最新已提交版本**，判断 WHERE 条件是否还成立；
- **不成立** → 立即释放锁、跳过该行，不再等待；
- **成立** → 继续正常等待行锁。

效果：在"更新条件与并发事务的修改耦合"的批量更新场景下，大幅减少无谓的锁等待和死锁概率。

---

## 3. Oracle 的 MVCC

Oracle 是国内生产环境最常见的"长期稳定运行 + 高并发读"数据库，其 MVCC 机制自 8i 引入自动 UNDO 管理以来二十年没有本质变化。理解它的关键是记住一句话：

> **Oracle 不保存"多个版本的行"，它保存的是"能撤销变更的 undo"，一致性读是"按需现算"出来的块。**

### 3.1 SCN：Oracle 的版本时间戳

**SCN（System Change Number，系统变更号）** 是 Oracle 全局单调递增的逻辑时钟。它在全库只有一把，用来给提交、快照、检查点和恢复排先后。一次修改不会单独领一个 SCN，SCN 也不只在 `COMMIT` 时才存在。

SCN 在这些时机往前走：

- **事务提交**：分配本事务的 **commit SCN**。同一事务的全部修改共用这一个号，从这个号起对其他会话可见；
- **按时间推进**：即使没有用户提交，数据库也会把 SCN 往前推，使逻辑时钟不落后于墙上时间；
- **看到更高的远端 SCN**：数据库链接或分布式事务带来更大的 SCN 时，本地 SCN 被抬到它前面；
- **检查点与恢复**：把当时的 SCN 记进控制文件、数据文件头或重做日志，作为“已经落盘 / 重放到哪里”的界线。检查点记录的是已有的 SCN，本身不等于一次提交。

和 MVCC 直接相关的是两种用法，一个分配、一个读取：

1. **commit SCN（提交号）**：`COMMIT` 时分配。权威副本写在 **undo 段头的事务表**里，并随 commit 记录进入 redo。数据块 ITL 里的 commit SCN 是事后块清除补上的（见 §3.2、§3.5），提交当时改不完所有被修改过的块。
2. **query SCN（快照号）**：一致性读开始时**读取当时的当前 SCN**，当作目标时点。这是读时钟，不给这条查询新分配一个号。

快照取定的粒度对应隔离级别（见 §4）：

- **语句级快照**：每条语句开始时读一次当前 SCN → READ COMMITTED；
- **事务级快照**：事务开始时读一次当前 SCN，全程复用 → SERIALIZABLE / READ ONLY。

未提交的修改没有自己的“变更 SCN”。别的会话要判断它，先看事务表里这个事务是否仍活动；已经提交的，再用 **commit SCN** 和读者的 **query SCN** 比较。

### 3.2 数据块结构与 ITL

Oracle 的存储单位是**数据块（block，默认 8KB）**。与 MVCC 相关的结构有两处：

**① 块头部的 ITL（Interested Transaction List，感兴趣事务列表）**

每个块有一组"事务槽"（受 `INITRANS` / `MAXTRANS` 控制，现代版本 MAXTRANS 已固定为 255，INITRANS 决定预分配数量），每个槽包含：

| ITL 槽字段 | 作用 |
|-----------|------|
| **XID** | 事务标识，三元组 `undo段号.槽号.序列`（`usn.slot.sqn`）。第三段是槽位被反复使用时的序号，用来识别“这格事务表是不是还是当年那笔事务”，它本身不是 SCN |
| **UBA** | **Undo Block Address**，指向该事务在这个块上的 undo 记录的起点 |
| **Flag** | 槽的状态。块转储里常见：`----` 尚未清除；`C---` 已清除且字段里是精确 commit SCN；`--U-` 提交清除写下的 SCN 上界 |
| **Lck** | 该事务在本块上锁住的行数。延迟块清除完成前，这个计数可以仍大于 0 |
| **Scn/Fsc** | **同一个字段的两种身份**。尚未块清除时是 **FSC（Free Space Credit）**：本事务在这个块上释放、尚未入账的空闲字节数。块清除之后，这个字段改写成 **commit SCN**（或提交清除阶段先写下的 SCN 上界） |

**② 行头的 LB**

数据行头部有一个字节 `LB`，指向 ITL 中"最后修改这一行的事务"所对应的槽位（`0` 表示该行锁未生效/已提交）。

**从行到 undo 的定位链**：

```
数据行
  └─ 行头 LB ──► ITL 槽 #k
                   ├─ XID：哪笔事务（再到 undo 段事务表看它提交没有、commit SCN 是多少）
                   ├─ Scn/Fsc：未清除时是空闲额度；清除后是 commit SCN
                   └─ UBA ──► undo 记录（撤销该事务在这个块上的修改所需的前像）
```

注意这条链是**两层**的：行 → ITL 槽 → undo。这与 InnoDB「行 → roll_ptr → 上一个版本」的一层链不同，也解释了为什么 Oracle 的一致性读天然是**块级**的：ITL 是块级的属性，一个块上可能同时有多个事务的变更槽。

### 3.3 UNDO：前像的存放地

- UNDO 存放在 **UNDO 表空间**（9i 之前的"回滚段"由 DBA 手工管理，之后是自动撤销管理 Automatic Undo Management）；
- undo 记录的内容是"**被修改前的前像**"（before image）：比如把 `price` 从 199 改成 189，undo 里记的是"这一列原来是 199"；
- undo 本身是**受 redo 保护的持久化数据**，但它的生命周期是"循环复用"的：UNDO 表空间是一个**环形缓冲**，新事务不断覆盖旧 undo；
- 保留时长由 `UNDO_RETENTION` 参数（目标值，秒）与 UNDO 表空间大小共同决定。设置 `RETENTION GUARANTEE` 可以强制不覆盖未过期的 undo（代价是 DML 可能因空间不足失败）。

**这与 InnoDB 的 undo 有本质区别**：InnoDB 的 undo 是"按需保留、只要有人需要就一直留着"；Oracle 的 undo 是"有保质期，过期就覆盖"。

### 3.4 一致性读（Consistent Read）的执行过程

这是 Oracle MVCC 的核心，完整流程如下（以 READ COMMITTED 为例）：

```
① 语句开始，读取当前 SCN 作为目标时点 query_scn（读取，不分配新号）
② 读数据块 B，逐个看 ITL 槽。槽里的 FSC 不能拿来和 query_scn 比：
   ③ 这个槽代表的修改，对 query_scn 可见吗？
        · Flag 为 C---（精确 commit SCN）：
             commit SCN ≤ query_scn → 可见
             commit SCN > query_scn → 不可见，要撤销
        · Flag 为 --U-（SCN 上界，不小于真实 commit SCN）：
             上界 ≤ query_scn → 可见
             上界 > query_scn → 改查事务表里的精确 commit SCN
        · 尚未清除，字段仍是 FSC：
             用 XID 查 undo 段头的事务表：
                仍活动 → 不可见，要撤销
                已提交 → 用事务表里的精确 commit SCN 比较
        每个槽都可见
        ├─ 是 → 直接使用块中的数据（本次构造不再应用 undo）
        └─ 否 → 进入 ④
   ④ 在内存中构造 CR 块（Consistent Read Block）：
        对每一个不可见的事务槽：
            沿该槽的 UBA 找到 undo 记录
            → 逐条反向应用（撤销该事务在这个块上的全部变更）
            → 若需要，继续沿 undo 链找更早的 undo 记录
        直到这些事务的修改都从块上消失，块呈现 query_scn 当时可见的样子
   ⑤ 在构造出的 CR 块上读取目标行 → 返回
```

块头上另有一个 SCN，记录的是**这块最后一次被修改时的当前 SCN**，不是 commit SCN。未提交的修改也会推进它，所以“块头 SCN ≤ query_scn”不能代替上面的判断。写断裂检测用的是这个块头 SCN（连同 seq）和块尾校验，见姊妹篇 [Oracle原理分析.md](Oracle原理分析.md) 第 11 节。

几个必须理解的细节：

1. **未提交事务的变更也会被撤销**：这正是"Oracle 全级别无脏读"的机制来源——读者不需要等写者，它直接把未提交的变更从自己看到的块里"抹掉"；
2. **CR 块是内存中的临时产物**：它不写回磁盘、不长期驻留缓存（构造出来供本次一致性读使用），所以**同一个块被反复一致性读时可能要反复重构**——这是"读放大"的来源（`consistent gets` 高的根因之一）；
3. **块级粒度**：即使只需要一行，也要把**整个块**还原到目标时点。所以"一个块上历史变更越多"，一致性读越贵；
4. **undo 可能不够用**：如果所需的 undo 记录已被覆盖（UNDO 表空间环形复用），一致性读失败 → **`ORA-01555: snapshot too old`**；
5. Oracle 的一致性读对**所有读**都生效（不像 InnoDB 只对快照读生效）：普通 `SELECT`、索引扫描、`SELECT ... FOR UPDATE`（它是当前读，读最新锁定版本）都有各自的一致性语义。

### 3.5 块清除（block cleanout）与延迟块清除

`COMMIT` 时，commit SCN 先记入 **undo 段头的事务表**，TX 锁随之释放。数据块 ITL 的更新是另一件事，**提交时并不会去修改所有被改动过的块**：

- **提交清除（commit cleanout）**：提交时只处理仍在 buffer cache 中的有限块。ITL 被标成已提交，Scn/Fsc 从空闲额度改成 commit SCN，或先写下不小于真实 commit SCN 的上界（转储里常见 Flag `--U-`）。行上的锁字节可能先留着。
- **延迟块清除（delayed block cleanout）**：其余块仍然带着“看起来像活动事务”的 ITL，直到**下一次有人访问它**（读或写）。这次访问回到事务表取出精确 commit SCN，写入 ITL（Flag 成为 `C---`），并清掉行锁。

延迟块清除带来的两个实际影响：

1. 清除动作**可能生成 redo**，从而"一次纯读操作引发了日志写入"（会让一些人困惑"为什么只读查询产生 redo"）；
2. 历史上它是 **ORA-01555 的经典诱因**：一个长查询在扫描过程中遇到需要做延迟清除的块，清除过程中可能需要 undo，而 UNDO 空间在长查询期间被复用 → 报错。Oracle 后续版本对延迟清除做了大量优化（如清除时尽可能不依赖 undo），但这个现象在"大量 DML 后紧接大批量查询"的场景中仍可能遇到。

### 3.6 语句级 vs 事务级一致性

Oracle 的一致性读有两个档位，正好对应它的两个（半）隔离级别：

| 档位 | SCN 何时取定 | 效果 | 对应级别 |
|------|-------------|------|---------|
| **语句级一致性** | **每条语句**开始时取 SCN | 单条语句内部看到同一时点；语句之间可变 | **READ COMMITTED**（默认） |
| **事务级一致性** | **事务开始时**取 SCN，全程复用 | 整个事务看到同一时点 | **SERIALIZABLE / READ ONLY** |

**RC 下的一个精妙机制——语句重启（statement restart / DML restart）**：

RC 下 `UPDATE` 遇到"目标行被其他事务锁定未提交"时会等待；对方提交后，Oracle **不会**基于过期数据盲目更新，而是：

- 读取该行的**最新已提交版本**；
- 在该版本上**重新评估 WHERE 条件**；
- 条件**仍成立** → 在新版本上执行修改（相当于语句重启）；
- 条件**不再成立** → 直接跳过这行。

这相当于一个"只针对被并发修改那一行"的、不必中止整个语句的乐观重试。它与 InnoDB 的**半一致性读**在设计目标上高度相似（都是"别为了一行不值得等待的行阻塞整个语句"），但 Oracle 是在**提交后**基于新版本重评估，InnoDB 是在**等待前**基于最新已提交版本预判。

### 3.7 Oracle 的三个级别如何落地

Oracle 是主流数据库中对外暴露级别最少的（没有 RU，也没有独立的 RR 档）：

| 级别 | 快照粒度 | MVCC 用法 | 写冲突行为 |
|------|---------|----------|-----------|
| **READ COMMITTED**（默认） | **语句级** SCN | 每条语句独立做一致性读 | 行锁排队；对方提交后在新版本上重评估 WHERE |
| **SERIALIZABLE** | **事务级** SCN | 整个事务复用同一个 SCN 做一致性读 | **`ORA-08177: can't serialize access for this transaction`**——乐观式失败，不等待 |
| **READ ONLY** | **事务级** SCN | 同 SERIALIZABLE 的一致性快照 | 禁止任何 DML，因此不存在写冲突，也永远不会 ORA-08177 |

必须强调的一点（**Oracle 的 SERIALIZABLE 名不副实**）：

> **Oracle 的 SERIALIZABLE 实际上就是快照隔离（Snapshot Isolation）**。Oracle 官方文档在描述该级别时直接使用 snapshot isolation 术语。它能防住脏读、不可重复读、幻读（事务级快照），但**防不住写偏斜（write skew）**，因为它只检测"写-写冲突"，不检测"读-写依赖环"。

写偏斜的反例（两库通用，Oracle SERIALIZABLE 也防不住）：

```sql
-- 表 doctors(name, on_call)，业务不变量：至少一名医生在岗
T1: SELECT COUNT(*) FROM doctors WHERE on_call = 1;   -- 2
T2: SELECT COUNT(*) FROM doctors WHERE on_call = 1;   -- 2（同一事务级快照，一致）
T1: UPDATE doctors SET on_call = 0 WHERE name = 'Alice'; COMMIT;   -- 写的是 Alice 行
T2: UPDATE doctors SET on_call = 0 WHERE name = 'Bob';   COMMIT;   -- 写的是 Bob 行
→ 最终 0 名医生在岗。两个事务写的是不同的行，写-写冲突检测毫无察觉
```

在 Oracle 上要真正保证这类"跨行不变量"，只能靠 **`SELECT ... FOR UPDATE` 显式锁行**（锁住所有参与判断的行），或把不变量下沉成约束/单行原子更新。

### 3.8 写-写冲突：ORA-08177

Oracle SERIALIZABLE 的写冲突是**乐观式**的：

- 提交检测阶段（实际上是在 DML 执行时检测），若本事务要修改的行**已被其他事务修改并提交**，且整个事务的快照时点早于该提交 → 立即报错 `ORA-08177`；
- 报错是**整个事务级**的：当前事务已经做的所有修改都要回滚（`ROLLBACK`），然后由应用**重试整个事务**；
- 不等待、不阻塞——这是"先干后查"的乐观模型，与 Oracle RC 的"耐心等待 + 重评估"完全不同。

配套：**Oracle SERIALIZABLE 不支持分布式事务**（这是它一个常被忽略的限制）。

### 3.9 undo 保留与 ORA-01555

`ORA-01555: snapshot too old: rollback segment number N with name "..." too small` 是 Oracle MVCC 的**头号运行期故障**，成因链条：

```
某个一致性读需要一个"很旧"的 undo 记录
   → 需要的 undo 已被新事务覆盖（UNDO 表空间环形复用）
   → 无法把块还原到目标时点 → 报 ORA-01555
```

常见触发场景：

| 场景 | 原因 |
|------|------|
| 长查询 / 大批量报表 | 查询执行期长，期间 UNDO 被写满覆盖 |
| 查询在"高峰 DML"期间运行 | 提交频繁 → undo 消耗快 |
| 延迟块清除 | 清除本身需要 undo，长查询期间 undo 已过期 |
| `UNDO_RETENTION` 设置过小 / UNDO 表空间过小 | 保留窗口不足 |
| 批量任务在一个大事务里跑很久 | 需要自保的 undo 也是它自己产生的 |

**应对手段**：

1. 调大 `UNDO_RETENTION` 与 UNDO 表空间（必要时 `RETENTION GUARANTEE`）；
2. **把大查询拆小**（分批 / 分区并行 + 短事务），减少单次一致性读的时间跨度；
3. 长报表用 `SET TRANSACTION READ ONLY`（明确只读意图，不产生写冲突，也不容易踩到别的问题）或**闪回查询 + 分批**；
4. 在业务低峰跑大查询；
5. 监控 `V$UNDOSTAT`（undo 使用趋势、`UNEXPIRED`/`EXPIRED` 块、ORA-01555 计数）。

### 3.10 顺带一提：Flashback Query

Oracle 的闪回查询（`SELECT ... AS OF SCN/TIMESTAMP`、`AS OF PERIOD FOR`）是**同一套 UNDO 机制的直接延伸**：

- 普通一致性读 = "把块还原到**若干秒前**（语句/事务开始时刻）的样子"；
- 闪回查询 = "把块还原到**你指定的任意过去时刻**的样子"。

因为机制同源，闪回查询同样受 `ORA-01555` 约束（回得太远、undo 已被覆盖就失败），也依赖 `UNDO_RETENTION`。**这从侧面证明：Oracle 的"多版本"从来不是"保留多份行"，而是"保留能撤销变更的 undo + 按需现算"。**

### 3.11 一个完整的时间线（Oracle 版）

沿用 §2.12 的场景：

```sql
T1: SET TRANSACTION ISOLATION LEVEL READ COMMITTED;   -- 或 SERIALIZABLE
T1: (事务开始)                                         -- RC: 此时不取 SCN；SER: 取事务级 SCN
T2: UPDATE goods SET price = 189 WHERE id=100; COMMIT;
T1: SELECT price FROM goods WHERE id = 100;            -- ①
T3: UPDATE goods SET price = 179 WHERE id=100; COMMIT;
T1: SELECT price FROM goods WHERE id = 100;            -- ②
```

| 读点 | READ COMMITTED（语句级 SCN） | SERIALIZABLE（事务级 SCN） |
|------|------------------------------|---------------------------|
| ① | **189**（本语句取 SCN，T2 已提交） | **189**（事务级 SCN 在 T2 提交之后取定） |
| ② | **179**（本语句重新取 SCN）→ 不可重复读 | **189**（复用事务级 SCN，T3 的变更在 SCN 之后，被 undo 撤销）→ 可重复读 |

对比 §2.12 的 MySQL 时间线可以看到一个有意思的差异：**MySQL RR 的快照在"第一次读"时定格，Oracle SERIALIZABLE 的快照在"事务开始"时定格**。所以下面这个序列在两库上结果不同：

```sql
T1: BEGIN;                                        -- Oracle SER: 此处 SCN 已定格
T2: UPDATE goods SET price = 189 WHERE id=100; COMMIT;
T1: SELECT price FROM goods WHERE id = 100;
   → Oracle SERIALIZABLE: 读到 199（事务开始时的版本）
   → MySQL  REPEATABLE READ: 读到 189（快照在本次读语句才定格）
```

### 3.12 Oracle 特有的现象：ITL 等待

前面提过在 InnoDB 里几乎见不到、在 Oracle 里必须专门调优的现象：**一个块的 ITL 槽数量有限**。

- 若同一时刻有超过 `INITRANS` 个事务要修改同一个块 → 后到的事务**必须等待空闲 ITL 槽**，表现为 **`enq: TX - allocate ITL entry`** 等待事件；
- 典型场景：**热点块**。比如一张按 `id` 顺序插入且被高度并发更新的表（自增主键的"当前最大 ID 页"是经典热点），一个块上挤了几十个并发事务；
- 应对：提高 `INITRANS`（注意"数据块里事务槽被填满后无法扩展"的行为会因为块内空间不足而受 `PCTFREE` 影响），或**从业务上打散热点**（反转主键、加哈希列、`ASSM` 下的分区打散等）。

这是"MVCC 的代价在 Oracle 身上以另一种形式出现"的典型例子：InnoDB 用行级版本链把写者的冲突留在行锁上，Oracle 则把写者的登记压在**块级 ITL**上。

---

## 4. 两库 MVCC 如何支撑事务隔离级别

把 §2 与 §3 的机制汇总成"隔离级别 → 快照边界"的映射，这是本文要回答的核心问题。

### 4.1 一句话公式

> **隔离级别 = 一致性读的快照边界 + 是否切换锁路线 + 写冲突检测时机**

| | MySQL InnoDB | Oracle |
|---|---|---|
| **快照边界的载体** | ReadView（活跃事务 ID 列表 + 高低水位 + 创建者） | SCN（一个数字） |
| **快照边界如何决定级别** | 每条语句新建 vs 事务内复用 → RC vs RR | 每条语句取 SCN vs 事务开始取 SCN → RC vs SERIALIZABLE |
| **是否切换锁路线** | ✅ 高（当前读、`FOR SHARE/FOR UPDATE`、SERIALIZABLE 全走锁）；低级别下 DML 全部走锁 | ❌ 读永远走一致性读（MVCC），DML 走行锁 |
| **写冲突检测时机** | 提交/执行期由**行锁排队 + 死锁检测**决定（悲观），RR 下等待并基于新版本执行 | RC 下等待并在新版本上重评估 WHERE；SERIALIZABLE 下**立即 ORA-08177 失败**（乐观） |

### 4.2 级别映射总表

| 标准级别 | MySQL InnoDB | Oracle |
|---------|--------------|--------|
| **READ UNCOMMITTED** | ✅ 支持：不做可见性判断，直接读版本链最新版本（**脏读**） | ❌ **不提供**：一致性读机制天然不会读到未提交数据，没有"故意读脏"的档位 |
| **READ COMMITTED** | ✅ 支持：每条语句新建 ReadView；RC 下有半一致性读优化；DML 加行锁 | ✅ **默认级别**：每条语句取 SCN；DML 加行锁；对方提交后重评估 WHERE |
| **REPEATABLE READ** | ✅ **默认级别**：事务内复用 ReadView；快照读无幻读；DML 用 Next-Key Lock 防当前读幻读 | ❌ **不提供独立档位**：要么语句级（RC），要么事务级（SERIALIZABLE），没有中间档 |
| **SERIALIZABLE** | ✅ 支持：普通 SELECT 被隐式改为 `FOR SHARE` 锁定读，**放弃 MVCC 退化为悲观锁** | ✅ 支持，但**实为快照隔离（SI）**：事务级 SCN，写冲突报 ORA-08177，**防不住写偏斜** |
| **READ ONLY** | ❌ 无此级别 | ✅ 有：事务级一致性快照 + 禁止 DML（长报表最佳选择） |

两库的"命中面积"正好错开：**MySQL 主推 RR，Oracle 主推 RC；两库的 SERIALIZABLE 走的是完全相反的路线（悲观锁 vs 乐观快照）。**

### 4.3 各级别的机制落地细节

**MySQL READ COMMITTED**
- 每条语句创建 ReadView → 语句之间可见新提交数据；
- 好处：快照短命、undo 易回收、history list 短；
- 代价：不可重复读、RC 下当前读不加间隙锁（幻读可能）；
- 注意：RC 是**唯一**会启用半一致性读的级别。

**MySQL REPEATABLE READ**
- 一个 ReadView 用到底 → 全程可重复读、快照读无幻读；
- 代价：ReadView 生命周期长 → undo 保留长 → purge 延迟；快照读与当前读机制割裂（§2.6 误解 2）；
- 当前读幻读由 Next-Key Lock 兜底，代价是锁范围大、死锁率高（尤其是范围查询与二级索引上的扫描）。

**MySQL SERIALIZABLE**
- 机制上"退级"：所有普通 SELECT 变成 `FOR SHARE`；
- 结果：真·可串行化（能防写偏斜，因为读也加锁、锁保持到提交），但并发度极低、死锁频繁；
- 实践建议：极少使用，几乎总是有更便宜的替代（显式 `FOR UPDATE` 锁住关键行 + 原子 UPDATE + 唯一约束）。

**Oracle READ COMMITTED**
- 语句级 SCN → 单语句内自洽，语句间可变；
- 无脏读（未提交变更被 undo 撤销）、无语句内不可重复读；
- 幻读可能（两次查询之间新提交的插入在第二次可见）；
- 写冲突患者等待，提交后重评估 WHERE（§3.6）。

**Oracle SERIALIZABLE（=SI）**
- 事务级 SCN → 脏读、不可重复读、幻读全防住；
- 写-写冲突 → ORA-08177，**应用必须重试整个事务**；
- **防不住写偏斜**（写不同行）；
- 不支持分布式事务；
- 对 UNDO 压力大（长事务 + 高一致性读需求）。

**Oracle READ ONLY**
- 事务级 SCN + 禁 DML；
- 不会 ORA-08177（没有写）；
- 是"长报表 / 数据导出 / 大批量核对"的最优选择；
- 仍受 ORA-01555 约束。

### 4.4 异常覆盖对照（含 MVCC 的边界）

用 §1.1 那三类冲突视角，整理成"MVCC 能覆盖什么、不能覆盖什么"：

| 并发异常 | MySQL RC | MySQL RR | MySQL SER | Oracle RC | Oracle SER | 靠什么机制防住 |
|---------|:--------:|:--------:|:---------:|:---------:|:----------:|---------------|
| 脏读 | ✅ | ✅ | ✅ | ✅ | ✅ | MVCC 一致性读（InnoDB 靠 ReadView 判未提交；Oracle 靠 undo 撤销未提交） |
| 不可重复读 | ❌ | ✅ | ✅ | ❌ | ✅ | 事务级快照（ReadView 复用 / 事务级 SCN） |
| 幻读（快照读） | ❌ | ✅ | ✅ | ❌ | ✅ | 同上 |
| 幻读（当前读） | ❌ | ✅ | ✅ | ❌ | ✅ | MySQL：Next-Key Lock；Oracle SER：事务级快照（读走一致性读，不加锁） |
| 丢失更新（相对表达式写） | ✅ | ✅ | ✅ | ✅ | ✅ | 两库 DML 都是当前读，在行锁下基于最新值计算 |
| 丢失更新（字面量回写） | ❌ | ❌/⚠️ | ✅ | ❌ | ⚠️ | 需 `FOR UPDATE`、乐观锁版本号或应用重试 |
| 读偏斜 | ❌ | ✅ | ✅ | ❌ | ✅ | 事务级快照 |
| **写偏斜** | ❌ | ❌ | ✅ | ❌ | **❌（=SI，防不住）** | 只有真·可串行化或显式锁 |
| ORA-01555 / undo 膨胀 | — | 膨胀不报错 | — | 默认风险 | 风险更高 | 不属于"异常"，是运维代价 |

> 一行结论：**MVCC 免费送你"读一致性"，但从不免费送你"写一致性"。** 跨行、跨集合的业务不变量，永远要在"真可串行化 / 显式锁 / 约束下沉"三者中做选择。

---

## 5. 逐维度对比

### 5.1 总览大表

| 维度 | **MySQL InnoDB** | **Oracle** |
|------|------------------|-----------|
| **实现路线** | 行级版本链 + 逻辑可见性判断 | 块级前像 + 物理重构一致性块（CR block） |
| **版本标识** | 行内隐藏列 `DB_TRX_ID`（6B） | 行头 LB → ITL 槽 XID；以 **SCN** 作为时间比较依据 |
| **旧版本位置** | undo log（undo 表空间，按行组织） | UNDO 表空间（undo 记录，按块组织） |
| **版本定位链** | 一层：行 → `DB_ROLL_PTR` → 上一版本 | 两层：行 → LB → ITL 槽 → UBA → undo 记录 |
| **快照载体** | **ReadView**（`m_ids` 活跃列表 + 高低水位 + 创建者 ID） | **SCN**（单个数字） |
| **是否需要活跃事务列表** | **需要**（可见性靠事务 ID 集合比较） | **不需要**（靠 SCN 先后 + 物理撤销未提交变更） |
| **可见性判断层次** | 逻辑层：判断"写者是否已提交" | 物理层：事务未提交，或 commit SCN 晚于 query SCN，就把修改从块上撤掉 |
| **判断粒度** | **行级**（只回溯需要的那一行） | **块级**（整块还原，即使只读一行） |
| **默认隔离级别** | **REPEATABLE READ** | **READ COMMITTED** |
| **快照粒度差异** | RC：每语句；RR：事务内首次快照读 | RC：每语句；SERIALIZABLE/READ ONLY：事务开始 |
| **是否支持 RU** | ✅（有实际脏读能力） | ❌（机制上不存在） |
| **SERIALIZABLE 的真实语义** | **真·可串行化**（靠锁读，放弃 MVCC） | **快照隔离（SI）**，防不住写偏斜 |
| **读是否走锁** | **看读法**：快照读不加锁；当前读加锁 | **永远不加锁**（一致性读）；`FOR UPDATE` 才是当前读 |
| **写-写冲突处理** | 行锁排队 + 死锁检测（1213 回滚一方）；RR 下基于最新版本执行 | RC：行锁排队 + 提交后重评估 WHERE；SER：立即 **ORA-08177**（乐观失败） |
| **多版本回收** | **purge 线程**沿 history list 回收，需无活跃 ReadView | **无 purge**；UNDO 环形复用，`UNDO_RETENTION` 决定保留窗口 |
| **长事务的表现** | undo/history list 膨胀、purge 受阻、版本链变长（**不报错，只变慢变胖**） | UNDO 使用率高、易 **ORA-01555**（**直接报错**） |
| **"版本太旧"的失败模式** | 不会失败（宁可膨胀） | **ORA-01555 snapshot too old** |
| **二级索引与一致性读** | 二级索引无版本信息，**必须回表**做可见性判断 | 索引是独立段，索引块同样从 undo 重构，机制统一 |
| **幻读防治** | 快照读靠 MVCC；当前读靠 **Next-Key Lock** | 事务级快照（SER/RO）或接受幻读（RC） |
| **特有优化** | 半一致性读（RC 下的 DML 预判，减少无谓等待） | 语句重启/重评估 WHERE；延迟块清除；提交清除 |
| **特有开销/风险** | 每行 19 字节隐藏列；undo 链回溯开销；purge 压力 | 块级重构的读放大；**ITL 槽等待（热点块）**；延迟块清除产生 redo |
| **观测手段** | `SHOW ENGINE INNODB STATUS`（History list length）、`information_schema.INNODB_TRX`、`performance_schema.data_locks` | `V$UNDOSTAT`、`V$TRANSACTION.USED_UBLK`、`V$SESSION`（等待事件）、autotrace 的 consistent gets |
| **时间旅行能力** | 无（不能查历史版本） | ✅ **Flashback Query**（`AS OF SCN/TIMESTAMP`，同一套 undo） |

### 5.2 机制层：版本链 vs 块重构，各自的代价

| | MySQL InnoDB（行级版本链） | Oracle（块级重构） |
|---|---|---|
| **读一行的成本** | 若当前版本可见 → O(1)；若需回溯 → 沿链跳 N 级，N = 期间被改次数 | 若块干净 → O(1)；若需重构 → 应用该块上**所有**过新变更的 undo，与"我关心的那一行被改了几次"无关 |
| **成本随什么增长** | **这一行被修改的次数**（版本链长度） | **这个块上并发/历史变更的总量** |
| **热点行 vs 热点块** | 热点行 → 版本链长 → 回溯慢 | 热点块（多行并发更新）→ ITL 槽争用 → **ITL 等待** |
| **读放大** | 无（按需回溯单行） | 有（整块重构，只读一行也重构整块） |
| **重复读同一块** | 每次都要走可见性判断（逻辑，便宜） | 每次可能都要重构 CR 块（物理 undo 应用，较贵）→ `consistent gets` 高的根因 |
| **版本保留的驱动** | "**有没有人还需要**"（活跃 ReadView 决定） | "**保质期到没到**"（`UNDO_RETENTION` + 空间决定） |

**一句话总结两套设计的取舍**：

> Oracle 换来了"读的绝对无锁 + 时间旅行能力 + 块级批量的高效"（一次重构服务整块的所有行），代价是"读放大 + undo 有保质期"；
> InnoDB 换来了"按需精确回溯单行 + undo 永不提前失效"，代价是"必须维护活跃事务列表 + 需要 purge 线程 + 长事务导致空间膨胀"。

### 5.3 快照层：ReadView vs SCN

| | ReadView | SCN |
|---|----------|-----|
| 本质 | 一个**集合**（活跃事务 ID 列表 + 高低水位） | 一个**标量**（数字） |
| 判断逻辑 | 「写者 ∈ 活跃列表？→ 不可见」 | 「事务未提交，或 commit SCN > query_scn？→ 撤销」 |
| 内存/维护开销 | 每个活跃事务持有一个 ReadView；活跃事务多则内存与比较开销上升 | 无额外结构；只多一个 SCN 比较 |
| 快照边界的精确性 | 以"事务 ID 分配顺序"近似时间顺序（同秒内多个事务按 ID 排序，边界清晰） | 以"SCN 严格单调"表示，粒度更细 |
| 与"未提交变更"的交互 | **逻辑判定**：未提交 → 不可见 → 回溯上一个版本 | **物理撤销**：直接把变更从块上撤掉 |
| 长事务的连锁影响 | ReadView 长期存活 → 阻塞 purge（需要**逐个**判断"还有没有人需要"） | SCN 长期存活 → 只需要 undo 还在（**保质期**问题） |

这个对比隐含了一个有意思的推论：**Oracle 的一致性读不需要"并发事务列表"，因此在事务数极多、事务极短的高并发场景下，Oracle 的快照机制本身的额外开销比 InnoDB 低**（InnoDB 需要为每个一致性读构建并比较活跃事务列表）。反过来说，**InnoDB 的 undo 管理比 Oracle"更聪明"**——它精确知道"什么时候可以回收"，不需要靠过期时间猜。

### 5.4 隔离级别能力对比（再压缩一次）

| 能力 | MySQL InnoDB | Oracle |
|------|--------------|--------|
| 提供几档 | **4 档齐全**（默认 RR） | **2.5 档**（默认 RC） |
| 有无 RU | 有（且真能脏读） | 无 |
| 有无独立 RR | 有（且是默认） | 无（只有事务级的 SER/RO） |
| SERIALIZABLE 是否真串行化 | ✅ 是（但靠锁，吞吐差） | ❌ 只是 SI（防不住写偏斜） |
| 有无只读一致性事务 | 可模拟（RR + 只读） | ✅ 原生 `READ ONLY` |
| 事务级快照的起算点 | 事务内**第一次快照读** | 事务**开始**时 |
| 快照隔离是否可选 | ❌ 无独立的 SI 档（RR 是"SI + 当前读加锁"的混合体） | ✅ SERIALIZABLE 就是 SI |

> 这是两库最值得记住的"名实"差异：
> **MySQL 的 RR 是"SI + 当前读加锁"的混合体，MySQL 的 SERIALIZABLE 是真串行化（靠锁）；
> Oracle 的 SERIALIZABLE 才是 SI（乐观快照），Oracle 根本没有 RR 档。**

### 5.5 长事务的代价：两库行为完全不同

这是生产排障中最需要条件反射的一点：

| | MySQL InnoDB | Oracle |
|---|---|---|
| **一个 8 小时的 RR 只读事务会怎样** | 它持有的 ReadView 让 purge 无法回收这 8 小时内产生的全部 undo → history list 爆炸 → undo 表空间持续增长（8.0 下 undo 表空间可 truncate 但被阻塞）→ 表数据页里死记录（delete-marked）堆积 → 后续所有事务的 DML 变慢 | 它的事务级 SCN 需要在 8 小时内一直能拿到对应的 undo → UNDO 空间的 `UNEXPIRED` 块无法释放 → 一旦被覆盖，这个查询报 **ORA-01555** |
| **失败模式** | **静默劣化**（不报错，直到磁盘满/性能崩） | **显式失败**（报错，长查询直接挂掉） |
| **谁更容易先出问题** | 长事务 + 高频写的库：空间与 purge 压力 | 长查询 + 高频 DML 的库：ORA-01555 |
| **调优旋钮** | `innodb_purge_threads`、`innodb_max_purge_lag`、事务拆分、监控 history list length | `UNDO_RETENTION`、UNDO 表空间大小、`RETENTION GUARANTEE`、查询拆分、低峰执行 |

**共同结论：两库的 MVCC 都对"长事务"过敏，只是过敏反应不同。** 所以"事务尽量短"是任何 MVCC 数据库的第一纪律。

### 5.6 写路径对比：MVCC 管不了的那一半

两库的 DML 都是"当前读 + 行锁"，但细节差异决定了它在并发冲突下的行为：

| 场景 | MySQL InnoDB | Oracle |
|------|--------------|--------|
| `UPDATE ... SET c = c - 100`（相对表达式） | ✅ 安全：在行锁下基于最新已提交值计算 | ✅ 安全：同 |
| 读出来算好再 `SET c = <字面量>` | ❌ 丢失更新（快照读到旧值） | ❌ 丢失更新（语句级快照读到旧值） |
| 两个事务先后 UPDATE 同一行 | 后到者**等待**，前者提交后基于**新值**继续执行 | 后到者等待；前者提交后**重评估 WHERE**，成立才执行 |
| 冲突检测时机 | 悲观，阻塞等待（只有死锁才回滚） | RC：悲观等待；SER：**乐观，立即 ORA-08177** |
| 死锁 | 自动检测，回滚代价小的一方（1213），应用需重试 | 自动检测，回滚一方（ORA-00060），应用需重试 |
| 防丢失更新的标准姿势 | 原子 UPDATE / `SELECT ... FOR UPDATE` / 乐观锁 `WHERE version = ?` | 完全相同的三招 |

### 5.7 相同点（别被差异掩盖）

两库的 MVCC 在以下方面是完全一致的：

1. **读不加锁、读写互不阻塞**（InnoDB 的快照读 / Oracle 的所有一致性读）；
2. **写-写仍靠行锁排队**，MVCC 不解决写-写；
3. **旧版本都放在 undo 中**（只是组织方式不同）；
4. **事务级快照都能防住脏读/不可重复读/幻读，都防不住写偏斜**（MySQL RR 与 Oracle SERIALIZABLE 在这一点上同病）；
5. **DML 都是当前读**，都不受快照约束；
6. **都对长事务过敏**；
7. **都不提供"跨库/跨实例一致性读"**——多活、分库场景下的一致性要另想办法（GTID / SCN + 全局事务 / 中间件）。

### 5.8 两库的"性格"总结

| | MySQL InnoDB | Oracle |
|---|--------------|--------|
| 设计取向 | **精确、按需、逻辑判断**——只回溯需要的行，只回收没人要的 undo | **简单、批量、物理重构**——整块现算，undo 到期即覆盖 |
| 强项 | 单行/热点的读一致性成本可控；undo 管理精确；可用四级隔离精确选择 | 读的绝对无锁；块级批量的高效；原生 Flashback 时间旅行 |
| 弱点 | 必须维护 ReadView 与 purge；长事务静默劣化；快照读/当前读割裂 | 读放大（consistent gets）；ORA-01555；ITL 热点等待；延迟块清除副作用 |
| 一句话 | 「**留版本，谁要谁拿**」 | 「**留撤销，谁要谁算**」 |

---

## 6. 工程实践、观测与常见误区

### 6.1 观测手段对照

**MySQL**

```sql
-- 1) 长事务与 undo 积压
SELECT * FROM information_schema.INNODB_TRX\G          -- 找出运行中的长事务（trx_started）
SHOW ENGINE INNODB STATUS\G                             -- 关注 TRANSACTIONS 段的 History list length
                                                         -- 以及 ROW OPERATIONS 段的 "undo log entries"
-- 2) 锁与等待（8.0）
SELECT * FROM performance_schema.data_locks;
SELECT * FROM performance_schema.data_lock_waits;
SELECT * FROM sys.innodb_lock_waits;
-- 3) 当前隔离级别
SELECT @@transaction_isolation;                          -- 8.0（5.7 前为 @@tx_isolation）
```

关键指标：**`History list length`**（undo 积压的直观度量）、`trx_started`（最老活跃事务）、undo 表空间使用量、`Innodb_row_lock_waits`。

**Oracle**

```sql
-- 1) undo 使用与 ORA-01555 计数
SELECT BEGIN_TIME, END_TIME, UNDOTSN, UNDOBLKS, TXNCOUNT,
       MAXQUERYLEN, SSOLDERRCNT, NOSPACEERRCNT
  FROM V$UNDOSTAT
 ORDER BY BEGIN_TIME DESC;
-- 2) 事务的 undo 使用量（找大事务）
SELECT s.sid, s.serial#, t.used_ublk, t.used_urec, t.start_time
  FROM V$TRANSACTION t JOIN V$SESSION s ON s.saddr = t.ses_addr
 ORDER BY t.used_ublk DESC;
-- 3) 一致性读开销（autotrace）
SET AUTOTRACE ON STATISTICS;     -- 关注 consistent gets / db block gets 的比值
-- 4) 等待事件（ITL 等待）
SELECT sid, event, p1, p2, seconds_in_wait FROM V$SESSION WHERE wait_class <> 'Idle';
                                 -- 关注 'enq: TX - allocate ITL entry'
```

关键指标：`SSOLDERRCNT`（ORA-01555 次数）、`MAXQUERYLEN`（最长查询时长，与 undo 保留需求直接相关）、`UNEXPIRED` 块比例、`used_ublk`、consistent gets 占比。

### 6.2 常见误区清单

**关于 MySQL**

| 误区 | 事实 |
|------|------|
| 「RR 的快照在 BEGIN 时生成」 | ❌ 在事务内**第一条快照读**时生成；要事务开始即成快照需 `START TRANSACTION WITH CONSISTENT SNAPSHOT` |
| 「MVCC 能防幻读，所以 RR 下没有幻读」 | ⚠️ 只对**快照读**成立；快照读与当前读混用仍会出现幻影（§2.9） |
| 「RR 下所有读都看不到新数据」 | ❌ 只有快照读；`UPDATE`/`FOR UPDATE` 是当前读，永远看最新 |
| 「MVCC 能防丢失更新」 | ❌ 相对表达式写能防，**字面量回写防不住**（读走快照、写走当前读） |
| 「undo 只是日志，可以随便清」 | ❌ update undo 是一致性读的数据来源，清早了就破坏 RR |
| 「长事务只是占点内存」 | ❌ 阻塞 purge → history list 膨胀 → undo 表空间增长 + 后续 DML 全变慢 |
| 「二级索引也能独立做 MVCC 判断」 | ❌ 二级索引无版本信息，必须回表判断可见性 |

**关于 Oracle**

| 误区 | 事实 |
|------|------|
| 「Oracle SERIALIZABLE 是可串行化，能防一切」 | ❌ 它就是**快照隔离**，防不住写偏斜；跨行不变量要 `FOR UPDATE` 或约束 |
| 「Oracle 会保存多份行数据」 | ❌ 它保存的是 **undo（可撤销变更的前像）**，一致性读是每次**现场重构**块 |
| 「一致性读慢是因为数据多」 | ⚠️ 常常是因为**块上历史变更多**（undo 应用量大）+ 反复重构 CR 块（consistent gets 高） |
| 「只读查询不会产生 redo」 | ⚠️ 延迟块清除会在只读路径上产生 redo |
| 「UNDO 表空间够大就不会 ORA-01555」 | ⚠️ 还要看 `UNDO_RETENTION`、DML 强度、单次查询时长；`MAXQUERYLEN` 才是需求侧指标 |
| 「提高 INITRANS 一定能解决 ITL 等待」 | ⚠️ 受块内空间与 `PCTFREE` 制约，且治标；根治要靠打散热点 |

### 6.3 实践建议

**MySQL**

1. **默认就用 RR，但把事务写对**：DML 用原子相对表达式；需要"读最新再判断"的地方显式 `SELECT ... FOR UPDATE`；不要依赖"读出来算好写回去"；
2. **事务尽量短**，避免"事务里调外部接口 / 等待用户输入 / 跑长循环"；
3. **监控 history list length**，把它当成一级告警指标；发现长时间 `trx_started` 的活跃事务立即排查；
4. **不建议为了"更严格"随意上 SERIALIZABLE**：InnoDB 的 SERIALIZABLE 会全量锁读，吞吐崩、死锁多。真正的跨行不变量优先用唯一约束/CHECK + 乐观锁；
5. **批量任务分批提交**，每批之间留出 purge 追赶的时间。

**Oracle**

1. **接受 RC 为默认**，把"跨语句一致"需求交给显式事务级快照（`SET TRANSACTION READ ONLY` 或 SERIALIZABLE）；
2. **长报表优先 `SET TRANSACTION READ ONLY`**：语义明确、不产生写冲突；
3. **为长查询预留 undo**：`UNDO_RETENTION` 至少覆盖 `MAXQUERYLEN` 的量级，并监控 `V$UNDOSTAT`；必要时 `RETENTION GUARANTEE`；
4. **警惕热点块的 ITL 等待**：看到 `enq: TX - allocate ITL entry` 就从业务上打散（反转键索引、哈希分区、调整 `INITRANS`）；
5. **SERIALIZABLE 必须配重试**：统一捕获 `ORA-08177`（以及 `ORA-00060` 死锁），做有限次指数退避重试——没有重试逻辑就不该开 SERIALIZABLE；
6. **闪回查询先评估 undo 保留窗口**，别把它当成"任意时间旅行"，回得太远一定 ORA-01555。

**跨库通用**

1. **把"一致性需求"分级**：单语句自洽（RC 够）→ 单事务一致视图（RR/SI）→ 跨行不变量（真串行化或显式锁）；
2. **约束优先于隔离级别**：唯一约束、外键、CHECK、原子 UPDATE、乐观锁版本号——能下沉给数据库的不变量，都不要靠隔离级别兜；
3. **用并发探针验证**：写两个会话、固定时间线，把 §4.4 那张表的每一行在目标库/目标配置上真实跑一遍——比背文档可靠得多。

---

## 7. 参考资料

1. MySQL 8.0 Reference Manual —— *InnoDB Multi-Versioning*、*Consistent Nonlocking Reads*、*Transaction Isolation Levels*、*Locking Reads*、*Next-Key Locking*
2. MySQL 8.0 源码/Internals —— `ReadView` 结构（`m_ids` / `m_up_limit_id` / `m_low_limit_id` / `m_creator_trx_id`）、`lock_clust_rec_cons_read_sees()` 可见性判断、`TRX_UNDO_INSERT_REC` / `TRX_UNDO_UPD_EXIST_REC` 等 undo 记录类型
3. Oracle Database Concepts —— *Data Concurrency and Consistency*：Consistent Read、SCN、ITL/UBA、Read Consistency、`SET TRANSACTION READ ONLY`
4. Oracle Database Concepts —— *Undo* 章节：Automatic Undo Management、`UNDO_RETENTION`、`ORA-01555 snapshot too old`、Delayed Block Cleanout
5. Oracle Database Error Messages —— `ORA-08177 can't serialize access for this transaction`、`ORA-01555 snapshot too old`、`ORA-00060 deadlock detected`
6. Oracle Database Reference —— `V$UNDOSTAT`、`V$TRANSACTION`、`V$SESSION`（ITL 等待）；*Oracle Database Advanced Application Developer's Guide* —— Flashback Query
7. H. Berenson, P. Bernstein, et al., *A Critique of ANSI SQL Isolation Levels*, SIGMOD 1995 —— 写偏斜（A5B）与 Snapshot Isolation 的出处，是理解"InnoDB RR 与 Oracle SERIALIZABLE 都防不住什么"的理论基础
8. 姊妹篇：[DB事务隔离级别.md](DB事务隔离级别.md) —— 隔离级别规范、四库（Db2/Oracle/MySQL/PG）行为对照与选型；[DB共享锁.md](DB共享锁.md) —— 应用级 S/X 命名锁的四种实现方案
