# 关系型数据库事务隔离级别规范与实现分析（DB2 / Oracle / MySQL / PostgreSQL）

> 整理日期：2026-10-04
>
> 内容范围：SQL 标准隔离级别规范 → 并发异常 → 各隔离级别的使用场景与示例 → DB2 / Oracle / MySQL / PostgreSQL 四大数据库的实现机制与行为差异 → 选型建议。

---

## 目录

1. [背景：事务与并发的本质矛盾](#1-背景事务与并发的本质矛盾)
2. [并发异常清单](#2-并发异常清单)
3. [SQL 标准隔离级别规范](#3-sql-标准隔离级别规范)
4. [各隔离级别的使用场景与示例](#4-各隔离级别的使用场景与示例)
5. [四大数据库的实现对比](#5-四大数据库的实现对比)
6. [选型建议与最佳实践](#6-选型建议与最佳实践)
7. [参考资料](#7-参考资料)

---

## 1. 背景：事务与并发的本质矛盾

事务的 ACID 四性中，本文聚焦 **I（Isolation，隔离性）**。

完全的隔离性意味着并发事务的执行效果等价于**逐个串行执行**（serial execution）——这是最安全的，但吞吐量不可接受。现实中的数据库必须在「正确性」和「并发度」之间做权衡：

- 一端是**串行执行**：零并发异常，性能最差；
- 另一端是**完全不隔离**：性能最好，数据一致性无法保证。

**隔离级别（Isolation Level）** 就是这条权衡线上的档位：由应用声明"我能容忍哪些并发异常"，数据库据此选择加锁 / 多版本 / 冲突检测策略。

从并发调度的角度看，读-读操作互不冲突，冲突只来自三种组合：

| 冲突类型 | 含义 | 可能的处理方式 |
|---------|------|--------------|
| 读-写（R-W） | T2 读到 T1 正在写的数据 | 不让读（加锁阻塞）/ 读旧版本（MVCC）/ 读未提交数据 |
| 写-读（W-R） | T1 读 T2 已写但未提交的数据 | 同上 |
| 写-写（W-W） | T1、T2 同时改同一数据 | 排队加锁 / 后提交者中止（first-committer-wins）/ 后写覆盖前写 |

**不同隔离级别的差异，本质上就是对这三类冲突采取不同策略的结果。** 后文每个数据库的实现分析都可以用这个框架来理解。

---

## 2. 并发异常清单

标准隔离级别是用"允许出现哪些异常"来定义的，所以先把异常现象本身列清楚。每个异常给出最小时间线（T1、T2 表示两个并发事务，时间自上而下）。

### 2.1 脏读（Dirty Read，P1）

T1 修改了数据但**未提交**，T2 读到了这份"未提交"的数据；之后 T1 回滚——T2 读到的值**从未真实存在过**。

```
T1: UPDATE account SET balance = balance - 100 WHERE id = 1;   -- 未提交
T2: SELECT balance FROM account WHERE id = 1;                  -- 读到已扣 100 的值
T1: ROLLBACK;                                                  -- 回滚！
（T2 基于"从未存在过"的余额做了业务决策）
```

**危害**：如果 T2 是"提现"操作，银行凭空损失 100；脏读读到的数据随时可能被撤销，一切基于它的决策都是空中楼阁。

### 2.2 不可重复读（Non-Repeatable Read，P2）

T1 先后两次读取**同一行**，期间 T2 修改（或删除）该行并提交 → 两次读到的值不一样。

```
T1: SELECT price FROM goods WHERE id = 100;      -- 读到 199.00
T2: UPDATE goods SET price = 189.00 WHERE id = 100;  COMMIT;
T1: SELECT price FROM goods WHERE id = 100;      -- 读到 189.00，与第一次不同
```

**危害**：同一事务内基于同一数据的两次判断互相矛盾。例如下单流程先读价格做预算、再读价格生成订单，中间价格被改，校验失效。

### 2.3 幻读（Phantom Read，P3）

T1 按条件查询了**一组行**，期间 T2 插入了满足条件的新行并提交，T1 再次按相同条件查询 → 多出了"幻影行"。

```
T1: SELECT COUNT(*) FROM orders WHERE status = 'ABNORMAL' AND day = '2026-10-04';  -- 结果 5
T2: INSERT INTO orders (..., status, day) VALUES (..., 'ABNORMAL', '2026-10-04');  COMMIT;
T1: SELECT COUNT(*) FROM orders WHERE status = 'ABNORMAL' AND day = '2026-10-04';  -- 结果 6
```

**与不可重复读的区别**：不可重复读针对**同一行**内容变化；幻读针对**结果集**（集合成员）变化。这个区别决定了实现手段——前者靠锁住行/读旧版本，后者必须锁住**范围**（间隙锁/谓词锁）或用快照。

### 2.4 丢失更新（Lost Update，P4）

两个事务都基于**同一个旧值**计算并更新，后提交者把先提交者的修改覆盖掉。

```
T1: SELECT likes FROM post WHERE id = 1;    -- 99
T2: SELECT likes FROM post WHERE id = 1;    -- 99
T1: UPDATE post SET likes = 100 WHERE id = 1;   COMMIT;   -- 99+1
T2: UPDATE post SET likes = 100 WHERE id = 1;   COMMIT;   -- 又是 99+1
最终结果 100，正确应为 101 —— T1 的更新被 T2 "丢失"
```

> **注意**：SQL-92 标准的四个级别定义**并不包含**丢失更新——这是标准最大的缺陷之一（详见 §3.2）。一个声称满足 SERIALIZABLE 异常矩阵（不发生 P1/P2/P3）的实现，仍可能发生丢失更新，除非它是真正的"可串行化调度"。

### 2.5 读偏斜（Read Skew，A5A）

T1 先后读取两个相关数据，两次读取之间其他事务修改并提交，导致 T1 读到**不一致的一对值**。

```
初始：账户 A = 400，账户 B = 600（总数 1000，一致状态）
T1: SELECT balance FROM a;        -- 400
T2: UPDATE a SET balance = 300;   -- 转出 100
T2: UPDATE b SET balance = 700;   COMMIT;
T1: SELECT balance FROM b;        -- 700
（T1 看到 A=400、B=700，总数 1100 —— 一个从未存在过的不一致状态）
```

### 2.6 写偏斜（Write Skew，A5B）

两个事务**读取同一组数据**（用读到的结果做判断），然后**各自写不同的行**。单独看每个事务都合法，合在一起却破坏了业务不变量。经典例子见 §4.4 的"值班医生"和多账户总余额约束。

写偏斜是**快照隔离（SI）**级别的固有缺陷：只防"写了同一行"的冲突，防不住"读了同一批数据、写了不同行"的冲突。**只有真正的可串行化（SERIALIZABLE）才能防住它。**

---

## 3. SQL 标准隔离级别规范

### 3.1 SQL-92 的定义：用"允许的异常"反推级别

SQL-92（以及后续 SQL:1999/SQL:2016 等版本）定义了四个隔离级别。值得注意的是，**标准并没有规定实现方式**（没有规定必须用什么锁），而是用"该级别下允许出现哪些异常现象"来反推定义：

| 隔离级别 | 脏读 P1 | 不可重复读 P2 | 幻读 P3 |
|---------|:------:|:------------:|:------:|
| **READ UNCOMMITTED**（读未提交） | 可能 | 可能 | 可能 |
| **READ COMMITTED**（读已提交） | 不可能 | 可能 | 可能 |
| **REPEATABLE READ**（可重复读） | 不可能 | 不可能 | 可能 |
| **SERIALIZABLE**（可串行化） | 不可能 | 不可能 | 不可能 |

四个级别的语义：

- **READ UNCOMMITTED**：只保证"读到的数据至少是某个事务写过的"，连未提交都允许——最低档。
- **READ COMMITTED**：任何读取只能看到**已提交**的数据（以及本事务自己的修改）。但同一事务内两次读同一数据，中间若有他人提交，结果可以不同。
- **REPEATABLE READ**：同一事务内，第一次读之后，**已读过的数据**保证不再变（他人修改提交也不可见）。标准允许"新插入的幻影行"出现。
- **SERIALIZABLE**：并发的净效果**等价于某个串行执行顺序**。注意这有两层含义：①三大异常都不发生（现象层面）；②调度可串行化（形式化层面）。现象层面达标 ≠ 形式化达标（见下节批评）。

### 3.2 标准的局限（Berenson 等人的著名批评）

1995 年 Berenson 等人的论文《A Critique of ANSI SQL Isolation Levels》指出了标准定义的多处问题，这些问题直接影响今天的工程实践：

1. **异常覆盖不全**：只定义了 P1/P2/P3，完全没有覆盖**丢失更新（P4）**、**读偏斜（A5A）**、**写偏斜（A5B）**。按标准字面要求实现"防住 P1/P2/P3"的数据库，仍可能丢更新、破坏跨行不变量。
2. **P3（幻读）定义过窄**：只考虑了"新插入满足谓词的行"，没有考虑"更新已有行使其满足谓词"等场景。
3. **以锁实现为前提**：现象定义隐含假设了加锁实现。对 MVCC（多版本并发控制）数据库，"不发生 P2"和"每条语句一个新快照"是两回事——同一现象可以由完全不同的机制达成，行为细节差异很大。
4. **SERIALIZABLE 的现象定义 ≠ 可串行化**：只检查"三大异常不发生"是不够的（写偏斜就是反例）。真正值得信任的只有"可串行化调度"这一定义。
5. **各数据库实际行为与级别名不完全对齐**：同名级别在不同数据库语义强弱不同（后文 §5 有大量例证），**选型时不能只看名字，必须看具体数据库的文档语义**。

> **工程结论**：标准给出的是"最低保证"的语言，实际系统的隔离语义 = 标准级别名 + 具体数据库实现细节 + 应用重试/加锁逻辑。这也是本文 §5 逐库分析的意义。

### 3.3 标准之外的重要补充：快照隔离（Snapshot Isolation, SI）

快照隔离**不在 SQL 标准内**，但它是现代主流数据库实现高级别隔离的事实基础（Berenson 1995 论文中正式提出，Adya 的博士论文给出形式化）：

- **读**：事务开始（或第一条语句）时取一个**一致性快照**，整个事务的读操作都基于这个快照——读不加锁、读写互不阻塞；
- **写**：仍基于**最新版本**加行锁执行；
- **提交时**检测写-写冲突：如果要写的行已被其他事务改过且已提交，本事务**中止**（first-committer-wins，先提交者赢）。

SI 能防住：脏读、不可重复读、幻读（对快照读而言）、丢失更新、读偏斜。

SI 防不住：**写偏斜**（两个事务写的是不同行，不触发写-写冲突检测）。

> **记住这个映射关系**，后面会反复用到：
> - PostgreSQL 的 REPEATABLE READ **就是** SI；
> - Oracle 的 SERIALIZABLE **其实是** SI（Oracle 自己的文档也承认）；
> - MySQL 的 REPEATABLE READ 是 "SI + 当前读加锁" 的混合体；
> - PostgreSQL 的 SERIALIZABLE（9.1+）才是主流开源库中唯一的"真·可串行化"。

---

## 4. 各隔离级别的使用场景与示例

### 4.0 选型总原则

先给结论性的原则，后面逐级展开：

1. **默认从 READ COMMITTED 出发**：它简单、并发度高，绝大多数 OLTP 场景够用（Oracle、PostgreSQL、SQL Server、Db2 的默认值都是它这一档）；
2. **需要"同一时点的一致视图"时升级到 RR/SI**：对账、报表、导出、备份；
3. **业务不变量无法用约束/锁表达时才用 SERIALIZABLE**：且必须配套重试逻辑；
4. **能精确表达就不要整事务提级**：一个 `SELECT ... FOR UPDATE`、一条原子 `UPDATE`、一个唯一约束、一个乐观锁版本号，往往比把整个事务提到 SERIALIZABLE 代价小得多。

### 4.1 READ UNCOMMITTED（读未提交）

**语义**：允许脏读，最低保证。

**适用场景**：

- **近似统计、监控大盘、热度榜**：允许 ±1% 的误差，换取完全不阻塞的读性能；
- 现实中**极少有场景真正需要它**：PostgreSQL 和 Oracle 根本不提供这个级别（它们的多版本机制天然不会产生脏读，也没有必要提供"故意读脏"的档位）；Db2 提供名为 UR 的级别；MySQL/InnoDB 支持设置但价值很小。

**示例：大促实时大屏**

```
-- 双十一运营大屏：展示"当前累计下单金额"，允许秒级误差
SET TRANSACTION ISOLATION LEVEL READ UNCOMMITTED;  -- 仅 Db2/MySQL 支持
SELECT SUM(amount) FROM orders WHERE day = '2026-11-11';
```

极少量脏读（个别订单即将回滚）对大屏显示毫无影响，但读端零阻塞。

**为什么不推荐日常使用**：

1. 脏读的值可能被回滚——如果下游是"扣库存""放行提现"这类决策，错误是实质性的；
2. 现代数据库的 READ COMMITTED 在 MVCC 实现下**读同样不加锁、同样不阻塞**，RU 的性能收益几乎为零，却要多承担脏读风险。**性能上一分钱不省，正确性上倒贴**。

### 4.2 READ COMMITTED（读已提交）

**语义**：只读已提交数据；同一事务内两次读之间，别人提交的修改对我是可见的。每条语句看到的是"当下最新的已提交状态"。

**适用场景（绝大多数 OLTP 的默认选择）**：

- 订单创建、支付回调、消息入库、内容编辑、工单流转——每条语句基于最新状态独立决策的业务；
- 写冲突少的负载：不同用户打不同数据，冲突靠行锁排队即可。

**示例 1：电商下单**

```
-- RC 事务：创建订单
BEGIN;
  SELECT price, stock FROM goods WHERE id = 100;      -- 看到最新已提交价格
  INSERT INTO orders (goods_id, price, qty) VALUES (100, 189.00, 1);
  UPDATE goods SET stock = stock - 1 WHERE id = 100 AND stock > 0;
COMMIT;
```

时间线演示"两次读可能不同"：

```
T1: SELECT price FROM goods WHERE id=100;    -- 199.00
T2: UPDATE goods SET price=189 WHERE id=100; COMMIT;
T1: UPDATE goods SET stock=stock-1 WHERE id=100;  -- 基于最新状态执行
T1: COMMIT;
```

T1 事务内两次读价格不同（199 → 189），但对"下单"这个业务通常无伤大雅——真正要保证的是 `stock = stock - 1` 这条**原子 UPDATE**（注意它不依赖读到的旧值，由数据库在行锁保护下基于最新值计算）。

**示例 2：check-then-insert 的竞态坑（RC 的经典问题）**

```
-- 两个会话并发注册同一用户名：
T1: SELECT * FROM user WHERE name='tom';   -- 无结果
T2: SELECT * FROM user WHERE name='tom';   -- 无结果（T1 还没提交）
T1: INSERT INTO user (name) VALUES ('tom'); COMMIT;
T2: INSERT INTO user (name) VALUES ('tom'); COMMIT;   -- 重复数据！
```

RC 下"先查后插"不是原子的。**正确做法**：给 `name` 加唯一约束，把不变量下沉给数据库，应用捕获重复键异常后转为"查询已有记录"。这是 RC 时代的标准姿势：**检查交给约束，而不是靠隔离级别**。

**适用特征小结**：单语句自洽、冲突少、跨语句一致性需求靠约束/原子 UPDATE/显式锁补齐。

### 4.3 REPEATABLE READ（可重复读，含 SI 语义）

**语义**：同一事务内多次读同一数据集，结果一致（基于同一个快照）。标准版本允许幻读，但主流 MVCC 实现对快照读大多把幻读也防掉了。

**适用场景**：

- **对账**：跨表、跨多次查询的金额必须来自同一时点；
- **报表 / 数据导出 / 备份**：如 `mysqldump --single-transaction` 的原理就是在 RR 下做一致性快照导出；
- **长事务批处理**：游标第二轮重扫数据时必须和第一轮看到同样的数据。

**示例 1：银行对账**

```
-- 月度对账：核对"账户余额汇总"与"流水汇总"是否一致
BEGIN;  -- RR：从这里定格快照
  SELECT SUM(balance) FROM account;            -- 假设 1,000,000
  -- …… 对账计算耗时 30 秒，期间用户持续转账 ……
  SELECT SUM(amount) FROM ledger WHERE month='2026-09';  -- 必须还是快照时点的数据
COMMIT;
```

- **RR 下**：全程看到 9:00 的快照，借贷必平；
- **RC 下**：后半段查询混入了新提交的转账，"钱从汇总里凭空消失"，对账永远不平。

**示例 2：写偏斜演示（RR/SI 依然防不住的场景）——值班医生**

```
-- 表 doctors(name, on_call)，业务不变量：至少一名医生在岗
T1: SELECT COUNT(*) FROM doctors WHERE on_call = true;   -- 2（Alice、Bob）
T2: SELECT COUNT(*) FROM doctors WHERE on_call = true;   -- 2（快照相同）
T1: UPDATE doctors SET on_call=false WHERE name='Alice'; COMMIT;
T2: UPDATE doctors SET on_call=false WHERE name='Bob';   COMMIT;
最终 0 名医生在岗 —— 两个事务写的是不同的行，SI 的写-写冲突检测毫无察觉
```

RR 防的是"同一数据被改"，防不住"基于同一判断各自改不同数据"。这类跨行不变量需要 SERIALIZABLE（或显式锁/约束）。

**各实现的坑**（详见 §5）：

- MySQL RR 的 UPDATE 是**当前读**：快照里看到的值和 UPDATE 实际作用的值可能不同（快照 500，实际已是 400，UPDATE 直接基于 400 加锁执行）——MVCC 读和 DML 语义割裂；
- PostgreSQL RR 的 UPDATE 遇到并发修改会**直接报错**（40001）要求重试——同样叫 RR，行为完全不同。

### 4.4 SERIALIZABLE（可串行化）

**语义**：并发执行的效果等价于某个串行顺序，一切并发异常（包括写偏斜）都不存在。

**适用场景**：

- **跨行/跨集合的业务不变量**，且无法用唯一约束、外键、触发器、原子 UPDATE 表达；
- 低并发、高价值的操作：资金组合约束、席位/资源独占、排班规则、配额分配。

**示例 1：多账户总余额约束（写偏斜的经典变体）**

业务规则：A、B 两个账户**总余额不得为负**（允许单账户临时透支）。

```
初始：A = 500，B = 500，总和 1000
T1: SELECT SUM(balance) FROM account WHERE id IN (1,2);  -- 1000 ≥ 600，允许转出
T2: SELECT SUM(balance) FROM account WHERE id IN (1,2);  -- 1000 ≥ 600，允许转出
T1: UPDATE account SET balance = -100 WHERE id = 1; COMMIT;
T2: UPDATE account SET balance = -100 WHERE id = 2; COMMIT;
最终总和 = -200 —— 业务不变量被破坏
```

RC / RR / SI 全部防不住（写的是不同的行）。出路：

1. **SERIALIZABLE**：数据库检测到读写依赖环，中止一方（PG 的 SSI 方案）；
2. **显式锁**：事务开始就 `SELECT ... FOR UPDATE` 锁住 1、2 两行，第二个事务的判断读会等待；
3. **约束下沉**：改成"单账户不得透支"（CHECK 约束），业务上放弃"组合透支"的灵活性。

**示例 2：医院值班排班**——见 §4.3 示例 2，同一个写偏斜问题，在 SERIALIZABLE 下 T2 的更新会被数据库中止（报序列化失败），应用重试后重新判断，就能保住"至少一人在岗"。

**代价与配套要求**：

1. **并发度最低**：冲突时中止一方（乐观式，如 PG SSI、Oracle SI），或大量加锁互斥（悲观式，如 Db2 RR、MySQL SERIALIZABLE）；
2. **应用必须实现重试**：序列化失败不是错误，是"请重来"的信号——
   - PostgreSQL：SQLSTATE `40001`（serialization_failure）；
   - Oracle：`ORA-08177 can't serialize access for this transaction`；
   - MySQL/InnoDB：死锁回滚 `ER_LOCK_DEADLOCK (1213)` 也要重试；
3. 事务要短、访问范围要小，否则中止率飙升。

### 4.5 场景速查表

| 业务场景 | 推荐隔离级别 | 关键手段 |
|---------|-------------|---------|
| 普通 OLTP 写事务（下单、回调、入库） | READ COMMITTED | 每条语句自洽，冲突靠行锁排队 |
| 库存/余额扣减 | READ COMMITTED | **原子 UPDATE**（`SET stock=stock-1 WHERE stock>0`），不读旧值 |
| check-then-act（重名检查、抢注） | READ COMMITTED | 唯一约束兜底 + 捕获重复键 |
| 内容编辑防覆盖 | READ COMMITTED | 乐观锁版本号（`WHERE version=?`） |
| 对账 / 报表 / 导出 / 备份 | REPEATABLE READ / SI | 一致性快照，读不加锁 |
| ETL 初始化、全量迁移 | REPEATABLE READ / SI | 快照 + 长事务，注意 undo/vacuum 压力 |
| 单行资金操作 | READ COMMITTED | `SELECT ... FOR UPDATE` 行锁足够 |
| 跨行不变量（组合余额、排班、席位、配额） | SERIALIZABLE + 重试；或显式锁；或约束下沉 | 防写偏斜 |
| 监控大盘、热度榜 | READ COMMITTED 足够（Db2 可用 UR） | 容忍误差，要吞吐 |

---

## 5. 四大数据库的实现对比

### 5.0 总览矩阵

| 数据库 | 对外支持的级别 | 默认级别 | 实现路线 | READ UNCOMMITTED |
|--------|--------------|---------|---------|------------------|
| **Db2** | UR / CS / RS / RR（命名自成体系） | **CS**（Cursor Stability） | **锁为主**；9.7+ 借日志实现"当前已提交"读 | 有（UR） |
| **Oracle** | READ COMMITTED / SERIALIZABLE（实为 SI）/ READ ONLY | **READ COMMITTED** | **MVCC + UNDO 回滚段**重构一致性读 | **无**（多版本天然不脏读） |
| **MySQL (InnoDB)** | 四档全支持 | **REPEATABLE READ** | **MVCC（undo 版本链 + ReadView）+ 锁**（RR 用 Next-Key Lock） | 有 |
| **PostgreSQL** | RC / RR（=SI）/ SERIALIZABLE（=SSI） | **READ COMMITTED** | **MVCC（元组多版本）+ VACUUM**；SSI 真·可串行化 | 无（接受设置但按 RC 处理） |

四个库的"同名级别"语义强弱并不一致，映射关系大致如下：

| ANSI 标准级别 | Db2 | Oracle | MySQL (InnoDB) | PostgreSQL |
|--------------|-----|--------|----------------|------------|
| READ UNCOMMITTED | UR | —（无此档） | READ UNCOMMITTED | —（接受但=RC） |
| READ COMMITTED | CS | READ COMMITTED | READ COMMITTED | READ COMMITTED |
| REPEATABLE READ | RS（有幻读） | —（无此档） | REPEATABLE READ（快照读无幻读） | REPEATABLE READ（=SI，无幻读但报错式写冲突） |
| SERIALIZABLE | RR（无幻读，真·串行化） | SERIALIZABLE（**=SI，非真串行化**） | SERIALIZABLE（锁读，真·串行化） | SERIALIZABLE（**=SSI，真·可串行化**） |

> 三个最容易踩坑的"名不副实"：
> ① Db2 的 **RR** 远强于 ANSI RR（实际对应 ANSI SERIALIZABLE）；
> ② Oracle 的 **SERIALIZABLE** 其实是快照隔离（防不了写偏斜）；
> ③ PostgreSQL 的 **REPEATABLE READ** 是 SI，与 MySQL 的 RR 在写冲突时的行为完全不同（报错重试 vs 锁等待）。

---

### 5.1 Db2

#### 级别体系

Db2 没有照搬 ANSI 命名，用自己的四级体系（LUW 与 z/OS 基本一致，默认均为 CS）：

| Db2 级别 | 全称 | 行为 |
|---------|------|------|
| **UR** | Uncommitted Read（未提交读） | 允许脏读：读操作不加锁、不等待，直接读其他事务正在修改的数据 |
| **CS** | Cursor Stability（游标稳定性） | 只锁游标**当前所在的那一行**；默认级别 |
| **RS** | Read Stability（读稳定性） | 事务内读过的所有行加锁保持到事务结束；这些行可重复读，但**新插入的行（幻影）仍可能出现** |
| **RR** | Repeatable Read（可重复读） | 所有访问过的行/页加锁到事务结束，无合适索引时升级为表锁，**彻底阻止并发插入**——无幻读，实际等价 ANSI SERIALIZABLE |

#### 实现机制：悲观锁为主

- 典型的**锁驱动**实现：行锁/页锁/表锁 + 意图锁（IS/IX/SIX），锁模式有 S（共享）、U（更新）、X（排他）、NS 等；
- 锁资源紧张时发生**锁升级**（lock escalation，行锁收敛为页锁/表锁，由 `LOCKLIST`/`MAXLOCKS` 控制）——这与 MVCC 数据库"读不占锁"的风格截然不同；
- **读写互斥**：CS/RS/RR 下，读者会被未提交的写者阻塞（9.7 之前）。

#### CS（游标稳定性）的精确定义

CS 只保证"**游标当前处理的那一行**不被并发修改"：

- 读某行时对该行加 U/S 锁，游标移到下一行就释放（该行若被本事务更新过，则 X 锁保持到事务结束）；
- 结果集整体不稳定——重扫时前面的行可能已变，等价于 ANSI READ COMMITTED。

#### 9.7+ 的"Currently Committed"（当前已提交）语义

DB2 9.7（LUW）为 CS/RS 引入了重要改进，缩小了与传统 MVCC 的差距：

- 读-写冲突**默认不再等待**：读者要读的行如果正被其他事务修改，数据库**从事务日志中回溯出该行最近已提交的版本**返回给读者（或跳过未提交的新插入行）；
- 效果类似 Oracle 式的读已提交：**读者不阻塞写者，写者不阻塞读者**；
- 但这**不是完整的 MVCC**：没有全链路版本链，写-写仍然互斥，一致性读是"按需从日志重建"而非"版本链可见性判断"。z/OS 上对应能力在 DB2 10 引入。

#### 用法

```sql
-- 语句级：WITH 子句
SELECT * FROM orders WITH UR;
-- 会话级
SET CURRENT ISOLATION = RS;
-- 包级：绑定（bind）时指定 ISOLATION 参数；JDBC: setTransactionIsolation()
```

---

### 5.2 Oracle

#### 级别体系：两档半

Oracle 是四大库中对外暴露级别最少的一个：

| 级别 | 说明 |
|------|------|
| **READ COMMITTED** | 默认；语句级一致性 |
| **SERIALIZABLE** | **实际是快照隔离（SI）**——Oracle 文档明确使用 snapshot isolation 术语；事务级一致性 |
| **READ ONLY** | SERIALIZABLE 的只读变体：同样的事务级一致性快照，但禁止 DML（长报表的理想选择） |

- **没有 READ UNCOMMITTED**：多版本机制天然不会读到未提交数据，没有提供"故意读脏"的档位；
- **没有 ANSI REPEATABLE READ**：要么语句级（RC），要么事务级（SERIALIZABLE/READ ONLY），没有中间档。

#### 实现机制：MVCC + UNDO 回滚段

Oracle 的多版本实现是"**从 UNDO 重构旧版本**"路线（与 PostgreSQL 的"堆里保留多版本"路线、MySQL 的"undo 版本链"路线并列）：

1. 数据块头部有 ITL（Interested Transaction List），记录哪些事务动过这个块；行头记录锁字节与事务标识；
2. 每条语句（RC）或每个事务（SERIALIZABLE）开始时取一个 **SCN（系统变更号）** 作为一致性读的目标时点；
3. 查询遇到"比目标时点新"的数据块时，**沿 UNDO 链反向应用撤销记录**，在内存中重构出目标时点的 CR（Consistent Read）块；
4. 结果：**读不加锁，读者不阻塞写者，写者不阻塞读者**；写-写仍靠行级锁排队。

#### 各级别行为

**READ COMMITTED**：

- **语句级一致性**：每条语句独立取 SCN 快照，语句执行期间看到的是"语句开始时已提交"的数据；
- DML 遇到行锁冲突会等待；对方提交后，Oracle 会在**新提交的行版本上重新评估 WHERE 条件**（必要时语句重启），不会基于过期数据盲目更新。

**SERIALIZABLE（= SI）**：

- **事务级一致性**：事务开始时定格 SCN，全程读这个快照；
- 提交时冲突检测：本事务要修改的行，若已被其他事务修改且提交，本事务的 DML 立即报错 **`ORA-08177: can't serialize access for this transaction`**（不等待、不阻塞，乐观式失败）——应用必须捕获后重试整个事务；
- **防不了写偏斜**（它只是 SI）：§4.3 值班医生、§4.4 组合余额的例子在 Oracle SERIALIZABLE 下依然成立。真需要跨行不变量时，用 `SELECT ... FOR UPDATE` 显式锁行。

**READ ONLY**：与 SERIALIZABLE 相同的一致性快照，禁止任何 DML。长事务报表不会阻塞业务写入，自身也不会 ORA-08177（不写就没有写冲突）。

#### 特有的坑

- **`ORA-01555: snapshot too old`**：一致性读需要回溯 UNDO，如果 UNDO 保留时间不足以覆盖长查询（UNDO 表空间循环复用、`undo_retention` 不足），查询直接失败。长事务/长查询是头号风险；
- SERIALIZABLE **不支持分布式事务**；
- 高级别事务对回滚段压力大，批量任务要分批提交。

#### 顺带一提：闪回查询

Oracle 的 Flashback Query（`SELECT ... AS OF TIMESTAMP/SCN`）是同一套 UNDO 机制的直接延伸——既然任意一致性读都是"沿 UNDO 回溯"，那把目标时点指到过去任意时刻，就得到了时间旅行查询。这佐证了 Oracle 一致性读的底层本质。

---

### 5.3 MySQL（InnoDB）

#### 级别体系：四档全支持，默认 REPEATABLE READ

MySQL/InnoDB 是四库中唯一完整提供四个标准级别且默认 RR 的。

**为什么默认 RR**：历史原因是**基于语句的复制（STATEMENT binlog）**——主库上并发交错执行的语句序列，在从库重放时必须产生同样的结果，这要求主库事务内部看到一致的数据视图（RR 保证），否则主从不一致。RR 下配合 Next-Key Lock 才能保证 STATEMENT 格式的复制安全。

#### 实现机制：MVCC（undo 版本链 + ReadView）+ 锁

1. 每行记录有隐藏列：`DB_TRX_ID`（最后修改它的事务 ID）、`DB_ROLL_PTR`（回滚指针，指向 undo log 中的上一版本）→ 形成**版本链**；
2. **ReadView**：事务做快照读时生成，包含"生成时刻所有活跃（未提交）事务 ID 列表"、低水位 `min_trx_id`、高水位 `max_trx_id`、创建者 ID。对版本链上每个版本判断可见性：trx_id < 低水位 → 可见；≥ 高水位 → 不可见；在活跃列表里 → 不可见，沿 roll_ptr 找上一个版本；
3. **ReadView 的生成时机决定 RC 与 RR 的全部区别**：
   - **READ COMMITTED**：**每条语句**生成新的 ReadView → 每次都能看到别人刚提交的数据 → 不可重复读；
   - **REPEATABLE READ**：事务**第一次快照读**时生成 ReadView，之后整个事务复用 → 全程可重复；
4. **READ UNCOMMITTED**：不做可见性过滤，永远读版本链最新版本（哪怕未提交）→ 脏读；
5. **SERIALIZABLE**：普通 SELECT 被隐式转换为锁读（`FOR SHARE`）——从 MVCC 路线切换到锁路线（autocommit=1 时每条 SELECT 是独立事务，仍按一致性读处理、不加锁）。

#### RR 级别防幻读的"两条腿"

MySQL RR 的幻读防护是分读法讨论的，这是它最有特色的地方：

1. **快照读**（普通 `SELECT`）：MVCC 天然看不见其他事务新插入的行（不在快照里）→ **无幻读**；
2. **当前读**（`UPDATE` / `DELETE` / `SELECT ... FOR UPDATE/FOR SHARE`）：要基于最新版本加锁执行，InnoDB 用 **Next-Key Lock = 记录锁 + 记录前的间隙锁（Gap Lock）** 锁住扫描到的索引记录**及其间隙**，阻止其他事务在范围内插入 → **无幻读**；
   - 唯一索引等值查询且命中已有记录时，退化为纯记录锁（间隙锁无意义）；
   - 配套还有插入意向锁（Insert Intention Lock）协调并发插入。
3. 由此产生一个经典"幻读复现题"：**事务内先快照读、再当前读**（如 `SELECT` 后紧跟 `UPDATE`），两次读法走不同机制，就可能"看见幻影"——这是面试高频题，也是真实业务的坑：**快照读和当前读在同一事务内混用是 RR 下的常见错误来源**。

另一个 RC 特有行为：**半一致性读（semi-consistent read）**——RC 下 UPDATE 遇行锁冲突时，可以先读该行的最新已提交版本判断 WHERE 是否匹配，不匹配就提前放锁，减少无谓的锁等待与死锁。

#### 关键行为差异：MySQL RR 的 DML 是"当前读"

这一点让 MySQL RR 与 PostgreSQL RR（=SI）同名不同命：

- MySQL RR 下，UPDATE 永远作用于**最新已提交版本**并加行锁：另一个事务若已改了这行且未提交，我等待；已提交，我基于**它提交后的新值**继续执行。因此写成**相对表达式**的更新（`SET balance = balance - 200`）天然防丢失更新（后到者在锁下基于最新值重算）；
- 但**字面量回写**（应用先快照读出 500、算好 300、再 `SET balance = 300`）防不住——计算发生在快照上，UPDATE 只是照抄覆盖，仍会丢更新。要么把写改成相对表达式，要么改用 `SELECT ... FOR UPDATE` 锁读，要么捕获 `WHERE balance = <旧值>` 更新失败后重试（乐观锁）；
- PostgreSQL RR 下，同样的场景直接报 `40001` 错误要求重试（first-committer-wins），无论写法如何；
- 但写偏斜（写不同的行）MySQL RR 一样防不住——行锁只锁被写的行，快照读不产生锁。

#### 用法

```sql
SET GLOBAL  TRANSACTION ISOLATION LEVEL READ COMMITTED;  -- 新连接默认
SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;
SET TRANSACTION ISOLATION LEVEL SERIALIZABLE;            -- 仅当前事务
-- 8.0 系统变量：transaction_isolation（旧名 tx_isolation 已废弃）
START TRANSACTION WITH CONSISTENT SNAPSHOT;  -- 立即定快照，不等第一条读语句
```

其他要点：MyISAM 等非事务引擎没有隔离级别可言；死锁自动检测并回滚代价小的一方（`ER_LOCK_DEADLOCK 1213`，应用需重试），锁等待超时 `ER_LOCK_WAIT_TIMEOUT 1205`。

---

### 5.4 PostgreSQL

#### 级别体系：三档，且语义最"较真"

| 级别 | 实际语义 |
|------|---------|
| READ COMMITTED（默认） | 每条语句一个新快照 |
| REPEATABLE READ | **快照隔离（SI）**（9.1+） |
| SERIALIZABLE | **可串行化快照隔离（SSI）**（9.1+）——主流开源库中唯一的**真·可串行化** |

- READ UNCOMMITTED：语法接受，实际按 READ COMMITTED 处理——MVCC 实现从机制上就不可能产生脏读，干脆不提供假档位；
- **9.1 是分水岭**：9.1 之前的 REPEATABLE READ 是"弱 RR"（行可重复读但仍可能丢失更新、幻读）；9.1 起升级为标准 SI，并新增了 SSI 实现 SERIALIZABLE。

#### 实现机制：元组级 MVCC + VACUUM

PostgreSQL 的多版本是"**新旧版本共存于堆表中**"路线：

1. 每行（元组）头部有 `xmin`（插入它的事务 ID）和 `xmax`（删除/锁定它的事务 ID）；
2. 可见性判断：结合元组的 xmin/xmax 与**快照**（事务开始时的活跃事务 ID 列表 + 边界）判断这个版本的行对当前事务是否可见；
3. UPDATE = 插入新版本元组 + 把旧版本标为已死；**旧版本（死元组）由 VACUUM 进程回收**；
4. 随之而来的运维特征：**长事务阻碍 VACUUM**（它还引用着旧版本）→ 表膨胀；事务 ID 只有 32 位，需要 freeze 机制防回卷——这些是 PG 特有的运维负担。

#### 各级别行为

**READ COMMITTED**：

- 每条语句开始时取新快照；
- UPDATE 撞上未提交的并发修改 → 等待对方；对方**提交后**，用 **EvalPlanQual** 机制在行的最新版本上**重新评估 WHERE 条件**：条件仍满足就直接基于新版本更新，不满足就跳过。简单高效、永不报错，适合绝大多数 OLTP。

**REPEATABLE READ（= SI）**：

- 事务**第一条语句**时定格快照，全程不变；
- UPDATE/DELETE 的目标行若被并发事务修改**且已提交** → 直接报错 **`40001: could not serialize access due to concurrent update`**（first-committer-wins，后提交者让路）；
- 读永远不阻塞、快照读无幻读、无丢失更新；但**写偏斜防不住**（它就是 SI）；
- 应用必须捕获 40001 重试。

**SERIALIZABLE（= SSI，真·可串行化）**：

- 基于 Cahill 2008 年的 **Serializable Snapshot Isolation** 理论：在 SI 之上，把**所有快照读**也纳入监控——用**谓词锁（SIREAD 锁）**记录"我读过哪些数据"（读过的元组/页/索引范围，粗粒度化以省内存）；
- 当一个事务"写了一个被并发 SERIALIZABLE 事务读过的数据"时，形成一条 **rw-反依赖边**；冲突检测器持续监控依赖图，一旦发现**环上出现两条连续的 rw 边**（写偏斜的拓扑特征），就**主动中止其中一个事务**（报 `40001: could not serialize access due to read/write dependencies among transactions`）；
- 特点：**乐观式**——不加读锁、不阻塞，把冲突留到提交前后检测；代价是谓词锁内存开销（`max_pred_locks_per_transaction` 等）和冲突高发时的中止率；
- PG 9.1 用 SSI 证明了"MVCC 架构上也能做真串行化"，打破了"可串行化必须靠两段锁"的传统认知。

#### 用法与补充

```sql
SET default_transaction_isolation = 'read committed';   -- postgresql.conf / 会话级
BEGIN ISOLATION LEVEL SERIALIZABLE;
SET TRANSACTION ISOLATION LEVEL REPEATABLE READ;
```

显式锁工具箱：`SELECT ... FOR UPDATE / FOR SHARE`（可带 `NOWAIT`、`SKIP LOCKED`）、9.3+ 的 `FOR NO KEY UPDATE` / `FOR KEY SHARE`（细分锁强度减少外键场景冲突）、咨询锁（advisory lock）。不同事务可以混用不同隔离级别。

---

### 5.5 同一组并发探针，四个库的行为对照

用三个探针事务横向验证（"—"表示该库无此档位；✅ 防住 / ❌ 出现 / ⚠️ 报错或重试）：

| 探针 | Db2（CS → RR） | Oracle（RC → SERIALIZABLE） | MySQL（RC → RR → SER） | PostgreSQL（RC → RR → SERIALIZABLE） |
|------|----------------|------------------------------|------------------------|---------------------------------------|
| **脏读** | CS ✅；UR ❌（提供脏读档位） | 全级别 ✅（无脏读档位） | RC ✅；RU ❌ | 全级别 ✅（RU 映射 RC） |
| **不可重复读** | CS ❌；RS/RR ✅ | RC ❌；SER ✅ | RC ❌；RR ✅ | RC ❌；RR/SER ✅ |
| **幻读**（快照读） | CS ❌；RS ❌；RR ✅ | RC ❌；SER ✅ | RC ❌；RR ✅ | RC ❌；RR/SER ✅ |
| **丢失更新**（"读-算-写"字面量回写） | CS ❌；RS/RR ✅（读即加锁，后到者读到的是最新已提交值） | RC ❌；SER ⚠️（ORA-08177 中止后到者） | RC ❌；RR ⚠️（`SET x=x-1` 相对表达式安全——UPDATE 当前读基于最新值；先快照读再字面量回写仍会丢）；SER ✅（锁读 + 死锁中止） | RC ❌；RR ⚠️（40001 中止后到者）；SER ⚠️ |
| **写偏斜**（各写不同行） | CS ❌；RS/RR ✅（读过的行加锁到提交，对方更新被阻塞） | RC ❌；SER ❌（**=SI，防不住**） | RC ❌；RR ❌；SER ✅（普通 SELECT 变锁读） | RC ❌；RR ❌（=SI）；SER ✅（**SSI 检测中止**） |

从这个矩阵能读出四库的"性格"：

- **Db2**：靠锁把一切串行化，RS/RR 档"读也加锁"所以连写偏斜都防住，代价是并发度；
- **Oracle**：最激进的 MVCC（读写互不阻塞），但 SERIALIZABLE 档位名不副实（只是 SI），跨行不变量必须自己 `FOR UPDATE`；
- **MySQL**：MVCC 与锁混搭（快照读走 MVCC、DML 走锁），RR 下"相对表达式写"防丢更新、字面量回写不防，防写偏斜要上 SERIALIZABLE；
- **PostgreSQL**：语义最严格——RR 明说是 SI，SERIALIZABLE 用 SSI 做到真可串行化，但代价是"报错 + 应用重试"的编程模型。

### 5.6 实现路线小结

| 路线 | 代表 | 核心思路 | 优缺点 |
|------|------|---------|--------|
| **纯锁（悲观 2PL）** | Db2 经典模式；MySQL SERIALIZABLE | 读也加锁并保持到提交 | 语义直白、真串行化；并发度低、易死锁 |
| **MVCC + 锁** | Oracle、MySQL、PostgreSQL（RR 及以下） | 读走快照不加锁，写加行锁 | 读写互不阻塞、吞吐高；写偏斜防不住，写冲突要处理 |
| **MVCC + 依赖检测（SSI）** | PostgreSQL SERIALIZABLE | 快照读也跟踪（谓词锁），提交期检测读写依赖环 | 真·可串行化且高并发；有误杀/中止率，必须重试 |

---

## 6. 选型建议与最佳实践

1. **先明确业务不变量，再反推隔离级别**，而不是"默认开最严"。大多数应用的真实不变量靠这些就够：唯一约束、外键、CHECK 约束、原子 UPDATE、乐观锁版本号、`FOR UPDATE` 行锁——它们都比提级便宜得多。

2. **主战场是 READ COMMITTED**：把每条语句写成自洽的（尤其 DML 用原子表达式而非"读出来算好再写回"），跨语句一致性需求用约束和显式锁补。

3. **快照需求升级到 RR/SI**：对账、报表、导出、备份。同时注意各库的"长事务税"：
   - Oracle：UNDO 压力与 ORA-01555；
   - PostgreSQL：表膨胀（阻碍 VACUUM）；
   - MySQL：undo 链增长、history list；
   - Db2：锁内存与锁升级。

4. **跨行不变量三选一**：SERIALIZABLE + 重试 / 事务头显式锁住全部相关行 / 把不变量改写成单行或可约束形式。三者中约束下沉通常是最优解。

5. **重试是高级别的标配，不是可选项**：统一捕获 `40001`（PG）、`ORA-08177`（Oracle）、死锁与序列化失败（MySQL 1213），做有限次指数退避重试。没有重试逻辑就不该开 PG SERIALIZABLE / Oracle SERIALIZABLE / PG RR。

6. **事务尽量短**：隔离级别再高也救不了长事务——锁持有时间、undo/快照保留、VACUUM/回滚段压力、死锁概率全部随事务时长恶化。批量任务分批提交。

7. **验证隔离行为用探针脚本**：针对 §5.5 的五类异常写并发探针（两个会话、固定时间线），在新库/新配置上跑一遍，比背文档可靠。线上则监控：锁等待、死锁率、serialization 失败率、ORA-01555 次数。

8. **框架接入**：JDBC `Connection.setTransactionIsolation()`；Spring `@Transactional(isolation = Isolation.REPEATABLE_READ)`。注意框架注解只负责"设置"，隔离语义仍由底层数据库决定——**Spring 里写的 REPEATABLE READ，在 Oracle 上会因为没有该档位而落到数据库的报错或最近似行为**。

---

## 7. 参考资料

1. ISO/IEC 9075（SQL-92 及后续 SQL:1999/SQL:2016），Isolation levels 定义
2. H. Berenson, P. Bernstein, et al., *A Critique of ANSI SQL Isolation Levels*, SIGMOD 1995 —— P4/A5A/A5B 异常与 Snapshot Isolation 的出处
3. A. Adya, *Weak Consistency: A Generalized Theory and Optimistic Implementations for Distributed Transactions*, MIT PhD Thesis, 1999 —— 隔离级别的形式化
4. M. Cahill, U. Röhm, A. Fekete, *Serializable Isolation for Snapshot Databases*, SIGMOD 2008 —— SSI 的出处（PostgreSQL 9.1 实现）
5. IBM Db2 11.5 / 12.1 Knowledge Center —— Isolation levels、Currently committed semantics
6. Oracle Database Concepts —— Data Concurrency and Consistency（MVCC、ORA-08177、ORA-01555）
7. MySQL 8.0 Reference Manual —— InnoDB Transaction Isolation Levels、Next-Key Locking、Consistent Nonlocking Reads
8. PostgreSQL 官方文档 Chapter 13 —— Concurrency Control（MVCC、SSI）
