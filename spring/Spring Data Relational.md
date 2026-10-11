# Spring Data Relational 深度源码解析（写给初学者的架构全景）

> **本文基于的源码**：`D:\code\3rd\spring-data-relational`，版本 **4.2.0-SNAPSHOT**（main 分支快照，Git commit `9deb11cc1e`，2026-10-05，即 4.2.0-M2 之后的第 9 个提交）。它随发布列车 **2026.1** 开发，预计 2026-11 GA；文中所有【源码证据】的文件路径与行号均为对该快照实际读取所得。
>
> **版本取舍说明**：Spring Data Relational 的核心骨架（`RelationalPersistentEntity` 元模型、`DbAction` 中间表示、`JdbcAggregateTemplate` 聚合模板、`Dialect` 方言接口）自 2.x（2020）以来高度稳定；3.0（2022-11）完成 Jakarta 迁移并把 spring-data-r2dbc 并回本仓库；3.2（2023-11）引入 RowDocument 与单查询装载；4.0（2025-11）完成表达式体系与仓库工厂重构。因此本文内容对使用 Spring Boot 3.x（Data 3.x）的读者同样适用——差异集中在 1.6 节的版本对照中；所有"特性属于哪个版本"的结论均经本地 git 标签（v1.0.0.RELEASE ~ v4.1.1，共 270 个标签）逐项实证。
>
> **阅读约定**：每章"先白话、后源码"——先用一两句人话讲清"这是什么、为什么需要它"，再给出源码证据链（`模块/src/main/java/.../类名.java` + 方法名 + 行号 + 代码片段）。行号只对该快照精确，读者用 IDEA 打开源码按类名 + 方法名定位即可。与 Spring Data 全家共享的公共机制（Repository 代理工厂、PartTree 解析器、MappingContext 三件套、审计、实体回调）在《Spring Data Commons 深度源码解析》中已逐行拆解，本文不重复展开，只在衔接处指路。

## 如何读这份文档

如果你是 Spring Data 初学者，推荐两遍读法：

- **第一遍（建立地图，1~2 小时）**：只读第一章（总览）每一节的开头白话段、各章的"小结"节、以及第八章（贯通视图）。目标是能回答：为什么这个项目自称"固执己见的 ORM"？一个聚合（聚合根 + 子实体集合）`save()` 时到底发生了什么？JDBC 版和 R2DBC 版共享什么、差异在哪？
- **第二遍（深入源码）**：对照每一章的【源码证据】，打开源码逐行读。顺序建议：第二章（映射模型）→ 第四章（DbAction 中间表示，理解"聚合怎么变成 SQL"的钥匙）→ 第五章（JDBC 聚合模板与 SQL 生成）→ 第六章（Repository 与查询推导）→ 第三章（SQL 抽象与方言，随用随查）→ 第七章（R2DBC）。

---

# 一、总览：Spring Data Relational 的定位、设计哲学与整体架构

## 1.1 一句话定位

**Spring Data Relational 是 Spring Data 家族中负责"关系型数据库"的那一支——它把 DDD（领域驱动设计）的"聚合"直接映射到关系表上，用 JDBC 和 R2DBC 两种驱动姿势提供同一套 Repository 编程模型**。一个仓库根目录的三个模块各司其职：

- `spring-data-relational`：**共享内核**。映射元模型（`@Table`/`@Column`/`@MappedCollection`）、SQL 语句抽象模型、方言体系、`AggregateChange`/`DbAction` 中间表示、转换器骨架——所有"与具体驱动无关"的东西都在这里。
- `spring-data-jdbc`：**命令式实现**。`JdbcAggregateTemplate`、`SqlGenerator`、`DataAccessStrategy`、`SimpleJdbcRepository`、MyBatis 集成、schema 工具。
- `spring-data-r2dbc`：**响应式实现**。`R2dbcEntityTemplate`、`DatabaseClient` 集成、`ReactiveDataAccessStrategy`、响应式 Repository。

它的设计取向，仓库 README 说得比任何分析都直白：

【源码证据】`README.adoc` 第 9-11 行：

```adoc
It aims at being conceptually easy.
In order to achieve this it does NOT offer caching, lazy loading, write behind
or many other features of JPA.
This makes Spring Data JDBC and Spring Data R2DBC a simple, limited, opinionated ORM.
```

"简单、有限、固执己见的 ORM"——不提供缓存、不提供懒加载、不提供写后刷新、不提供脏检查。这份"减法清单"不是缺陷列表，而是设计宣言：JPA/Hibernate 的大多数使用难题（一级缓存与数据库不一致、懒加载 N+1、脏检查的性能开销、merge 语义的困惑）都源于"对象图与数据库之间的长期会话"。Spring Data Relational 直接把会话概念砍掉——**每次操作都是"加载整个聚合"或"保存整个聚合"**，用粗粒度的一致性换取概念上的简单。

## 1.2 设计哲学：读源码前先记住五句话

1. **聚合是持久化的原子单位**。聚合根 + 其内部引用的所有实体（一对多/一对一/Map）作为一个整体读取和写入；跨聚合只能通过**主键引用**（属性声明为 `AggregateReference<ID, T>`，落库只存 ID，不存对象图，见 2.4 节）。这直接来自 DDD 对"聚合边界"的定义（Vernon《Implementing Domain-Driven Design》），官方文档专门有一章 "Domain Driven Design and Relational Databases" 论证它。
2. **没有会话，就没有懒加载与脏检查**。因为每次都是整聚合读写，框架不需要在"你访问 `order.getItems()` 时才去查库"（懒加载），也不需要"比对快照找出改了哪个字段"（脏检查）。代价是：改聚合里的一行也要整聚合保存（但见 5.2 节——保存是按 DbAction 精确到表的，不是整表重写）。
3. **一份元模型，两种驱动**。JDBC（阻塞）与 R2DBC（响应式）的差异被压缩到最薄的一层：表/列怎么映射、SQL 长什么样、事件怎么发，全部在共享内核；两个实现模块只负责"如何执行这条 SQL"（`NamedParameterJdbcOperations` vs `DatabaseClient`）。这正是 1.3 节模块分层的第一原则。
4. **每个环节留口子**。命名（`NamingStrategy`）、方言（`Dialect`）、SQL 生成（`SqlGenerator`/`StatementMapper`）、数据访问（`DataAccessStrategy`）、主键生成（`IdValueSource` + 回调）、类型转换（`CustomConversions`/`PropertyValueConverter`）、生命周期（事件 + `EntityCallback`）——每一层都是接口 + 默认实现，可整体替换。这和《Spring Framework 深度源码解析》1.2 节总结的"面向接口 + 适配而非重造"是同一基因。
5. **SQL 是一等公民，先建模后渲染**。框架内部从不手拼 SQL 字符串：先把 SELECT/INSERT/UPDATE/DELETE/UPSERT 建模成不可变的 AST（152 个类），再由 `SqlRenderer` 按 `Dialect` 给出的渲染规则输出字符串。列名引号、分页语法、锁语法、LIKE 转义符全部由方言接管。

## 1.3 模块分层全景

仓库根目录实测共 3 个功能模块 + 1 个发布模块（`spring-data-jdbc-distribution`）。按"用户感知"分层如下：

```
┌────────────────────────────── 使用者入口 ──────────────────────────────┐
│  @EnableJdbcRepositories / @EnableR2dbcAuditing                        │
│  Repository 接口（CrudRepository / ListCrudRepository / PagingAndSorting│
│  / ReactiveCrudRepository / ReactiveSortingRepository）                │
├───────────────────── 实现层：spring-data-jdbc ─────────────────────────┤
│  repository/support: JdbcRepositoryFactoryBean → JdbcRepositoryFactory │
│                      → SimpleJdbcRepository（基类）+ 查询策略           │
│  repository/query:   PartTreeJdbcQuery / StringBasedJdbcQuery / @Query │
│                      /@Modifying / RowMapperFactory                    │
│  core:               JdbcAggregateTemplate（聚合 CRUD 编排）            │
│                      + AggregateChangeExecutor（DbAction 执行器）       │
│  core/convert:       DataAccessStrategy 策略族 + SqlGenerator          │
│                      + MappingJdbcConverter + 单查询装载器              │
│  core/dialect:       JdbcDialect + 8 家数据库实现                       │
│  core/mapping/schema: Tables/SchemaDiff → DDL 或 Liquibase 变更集       │
│  mybatis:            MyBatisDataAccessStrategy（可替换数据访问层）      │
├───────────────────── 实现层：spring-data-r2dbc ────────────────────────┤
│  repository/support: R2dbcRepositoryFactory → SimpleR2dbcRepository    │
│  core:               R2dbcEntityTemplate（实体级 CRUD + Fluent API）    │
│                      + StatementMapper（语句建模到渲染）                │
│  convert/mapping:    R2dbcConverter、OutboundRow（出站行）、事件        │
│  dialect:            R2dbcDialect + 5 家数据库实现                      │
├───────────────────── 共享内核：spring-data-relational ─────────────────┤
│  core/mapping:  RelationalPersistentEntity/Property、@Table 注解族、   │
│                 NamingStrategy、AggregatePath（聚合路径）、事件定义      │
│  core/sql:      SQL 语句 AST（152 个类）+ SqlRenderer 渲染器           │
│  core/dialect:  Dialect 接口 + LimitClause/LockClause/IdGeneration 族  │
│  core/query:    Query/Criteria/Update（面向方法名推导与 Fluent API）    │
│  core/conversion: RelationalConverter、AggregateChange、DbAction、     │
│                 四个聚合写入器、IdValueSource、RelationalEntityVersionUtils│
│  core/dialect+domain+repository: Escaper、RowDocument、共享查询推导基类  │
└────────────────────────────────────────────────────────────────────────┘
```

【源码证据】`pom.xml` 第 61-64 行——父 POM 的 `<modules>`：

```xml
<module>spring-data-relational</module>
<module>spring-data-jdbc</module>
<module>spring-data-r2dbc</module>
<module>spring-data-jdbc-distribution</module>
```

注意父 POM 的 `artifactId` 是 `spring-data-relational-parent`，而 `dist.id` 属性是 `spring-data-jdbc`（`pom.xml` 第 21-22 行）——历史痕迹：这个仓库最初就叫 spring-data-jdbc，2018-10-17 才在 DATAJDBC-272 提交中拆分成多模块结构（`git log --reverse -- spring-data-relational` 的首条提交可证）。

## 1.4 模块依赖图（以各模块 pom.xml 的 dependencies 实证）

```
              spring-data-commons（公共内核：Repository 抽象/映射元数据/转换）
                ▲                ▲                  ▲
                │                │                  │
        spring-data-relational     │                  │
          ▲            ▲           │                  │
          │            │           │                  │
   spring-data-jdbc  spring-data-r2dbc                │
     ▲                                        │
     └── spring-jdbc（NamedParameterJdbcOperations）
                        spring-data-r2dbc 还依赖：
                        spring-r2dbc（DatabaseClient/ReactiveTransactionManager）
                        r2dbc-spi、reactor-core、kotlinx-coroutines（可选）
```

真实依赖声明（摘自各模块 `pom.xml` 的首个 `<dependencies>` 段，范围 compile，已逐个验证）：

| 模块 | 直接依赖（compile/optional 另注） |
|---|---|
| spring-data-relational | spring-data-commons、spring-tx、spring-context、spring-beans、spring-core |
| spring-data-jdbc | spring-data-relational、spring-data-commons、spring-tx、spring-context、spring-beans、**spring-jdbc**、spring-core；optional：mybatis-spring + mybatis、liquibase-core |
| spring-data-r2dbc | spring-data-relational、spring-data-commons、spring-tx、spring-context、spring-beans、**spring-jdbc**、spring-core、**spring-r2dbc**、**r2dbc-spi**、**reactor-core**；optional：kotlin-stdlib/kotlinx-coroutines-* |

三件事值得注意：

- **spring-data-relational 不依赖 spring-jdbc 也不依赖 reactor**：共享内核是纯"建模层"，两个驱动世界各自在实现模块里接入。
- **spring-data-r2dbc 依赖 spring-jdbc**：主要用于 schema 脚本解析工具与部分类型工具的复用（`spring-data-r2dbc/pom.xml` 第 76 行），不是阻塞访问。
- **spring-data-commons 是唯一的家族依赖**：Repository 接口族、`RepositoryFactorySupport` 代理工厂、`PartTree` 方法名解析、`MappingContext` 三件套、`EntityCallback`、审计——这些公共机制本文直接引用《Spring Data Commons 深度源码解析》对应章节。

## 1.5 关键问题 → Spring Data Relational 方案映射（全文导览）

| 用 JDBC/R2DBC 开发的关键问题 | Spring Data Relational 的方案 | 详见 |
|---|---|---|
| 表/列名怎么来的？驼峰转下划线？ | `NamingStrategy`（默认 SNAKE_CASE）+ `@Table`/`@Column` 注解覆盖 + Value Expression 动态表名 | 第二章 |
| 一对多/一对一/Map 属性怎么落库？ | 聚合内：子表 + 反向外键列；跨聚合：`AggregateReference` 存 ID；`@MappedCollection` 定制 | 第二章、第五章 |
| 数据库方言差异（分页/锁/自增/数组） | `Dialect` 接口 + 每家数据库一个实现 + 启动时按产品名自动探测 | 第三章 |
| 保存一个聚合到底执行哪些 SQL？ | 聚合 → `AggregateChange`（一组 `DbAction`）→ 执行器按依赖序执行并回填 ID | 第四章、第五章 |
| 新增还是修改怎么判断？ | `isNew()`（id 为 null/0 视为新增）+ `@Version` 乐观锁 | 第四章、第五章 |
| JPA 太重，想要"方法名生成 SQL" | Repository 方法名推导（PartTree → Criteria → SQL AST） | 第六章 |
| 想手写 SQL 又不想拼参数？ | `@Query` + 命名参数 + SpEL/Value Expression + `@Modifying` + 自定义 RowMapper | 第六章 |
| 保存前后想插一脚（审计、事件外发） | 聚合根领域事件（6 个 RelationalEvent）+ EntityCallback 体系 | 第六章 |
| 响应式栈怎么访问数据库？ | spring-data-r2dbc：R2dbcEntityTemplate + DatabaseClient + 响应式 Repository（实体中心，无聚合级联） | 第七章 |
| 建表脚本与实体漂移 | `Tables`/`SchemaDiff`（映射模型 → 差异 → DDL/Liquibase 变更集） | 第六章 |
| GraalVM 原生镜像 | AOT：`JdbcRepositoryContributor` 把派生查询编译为常量 SQL | 第六章 |

## 1.6 版本演进：1.0 → 4.x 关键变化对比

写作时（2026 年 10 月）的版本格局：3.5.x 维护线、4.0.x/4.1.x 并行维护，4.2 尚在里程碑阶段（4.2.0-M2 于 2026-09-21 发布，GA 预计 2026-11——正是本文分析的这份 main 分支快照）。本节所有"特性属于哪个版本"的结论都经过双重验证：**① 用本地仓库的 git 标签（1.0.0.RELEASE ~ 4.1.1，共 270 个）对源码逐项 grep 实证；② GA 日期取自各发布 tag 的 git 提交时间**。

### 1.6.1 版本时间线与运行基线

| 版本 | GA 时间（tag 提交日期） | 对应发布列车 / Boot | 一句话主题 |
|---|---|---|---|
| 1.0.0.RELEASE | 2018-09-21 | Lovelace / Boot 2.0~2.1 | 项目以 spring-data-jdbc 之名首次 GA：聚合映射 + Repository |
| 1.1.0.RELEASE | 2019-09-30 | Moore / Boot 2.2 | `@MappedCollection`/`@Embedded`、SQL 语句 AST（core.sql）登场 |
| 2.0.0.RELEASE | 2020-05-12 | Ockham / Boot 2.3 | `JdbcQueryCreator`（2.0 起源码注释可证）、DbAction 体系成型 |
| 2.1.0 / 2.2.0 / 2.3.0 / 2.4.0 | 2020-10-28 / 2021-04-14 / 2021-11-12 / 2022-05-13 | Boot 2.4~2.7 | 批量插入（BatchInsert 2.4）、`InsertRenderContext`（2.4）、Query by Example、`@EnableJdbcRepositories` 增强 |
| 3.0.0 | 2022-11-18 | 2022.0 / Boot 3.0 | **Jakarta EE 迁移**；**spring-data-r2dbc 并入本仓库**（2022-02-21 导入提交）；默认命名策略改为 SNAKE_CASE；`@InsertOnlyProperty`、`Lock` 注解进 relational 模块 |
| 3.1.0 | 2023-05-12 | 2023.0 / Boot 3.1 | 迭代增强（Upsert 铺垫） |
| 3.2.0 | 2023-11-17 | 2023.1 / Boot 3.2 | **`AggregatePath` 聚合路径体系**、**`RowDocument` + 单查询装载**（`SingleQueryDataAccessStrategy`）、投影内省 `introspectProjection` |
| 3.3~3.5 | 2024-05 ~ 2025-05 | 2024.0~2025.0 | 迭代增强；`@Sequence` 与序列主键回调（3.5） |
| 4.0.0 | 2025-11-14 | 2025.1 / Boot 4.0 | **Value Expression 体系**（commons 4.0 `ValueExpressionDelegate`）；`JdbcRepositoryFactory(JdbcAggregateOperations)` 新构造器，旧构造器废弃；`Page` 系 API 进入废弃周期；`getRowMapper` 开放 |
| 4.1.0 | 2026-06-09 | 2026.0 / Boot 4.1 | **UPSERT**：`DbAction.UpsertRoot` + `RelationalEntityUpsertWriter` + `Dialect.getUpsertRenderContext` + `ReactiveUpsertOperation`；`Dialect.getName` |
| 4.2（本文快照） | 预计 2026-11 | 2026.1 / Boot 4.2（预计） | 按查询删除（`deleteAllByQuery` → `DeleteRootByQuery`/`DeleteByQuery`）、按查询加锁（`AcquireLockAllRootByQuery`） |

### 1.6.2 特性引入版本对照表（git 实证，可复现）

每行都可用 `git tag --contains <引入提交的哈希> | sort -V | head -1` 复现：

| 特性 | 引入版本 | 实证 |
|---|---|---|
| `DbAction` 中间表示 | 1.1.0.M1 | DbAction.java 的引入提交最早出现在 1.1.0.M1 标签 |
| `@MappedCollection`/`@Embedded` | 1.1 | 两注解源码 `@since 1.1`（MappedCollection.java:32、Embedded.java:37） |
| SQL 语句 AST（`SQL` 工厂、`SqlRenderer`） | 1.1 | SQL.java `@since 1.1`（L31）；Dialect.java `@since 1.1`（L44） |
| `JdbcQueryCreator` 方法名推导 | 2.0 | JdbcQueryCreator.java `@since 2.0`（L52） |
| 批量 DbAction（`BatchInsert`/`BatchInsertRoot`/`BatchDelete`） | 2.4 / 3.0 | DbAction.java L457 `@since 2.4`、L469/L482/L495 `@since 3.0` |
| spring-data-r2dbc 并回本仓库 | 3.0（2022-02-21 导入） | `git log --diff-filter=D -- spring-data-r2dbc`：2018-10-17 曾短暂创建后移出，2022-02-21 "Import Spring Data R2DBC module" 正式回归 |
| 默认命名策略 SNAKE_CASE（`DefaultNamingStrategy`） | 3.0 | DefaultNamingStrategy.java `@since 3.0`（L24） |
| `Lock` 注解与 `LockMode` | 3.0（自 r2dbc 迁入） | 引入提交 "Move Lock to relational module"（2022-02-22），首个包含标签 3.0.0 |
| `@InsertOnlyProperty` | 3.0 | InsertOnlyProperty.java `@since 3.0`（L28） |
| `AggregatePath` / `RowDocument` / `AggregateReader` / `SingleQueryDataAccessStrategy` | 3.2.0 | 三者的引入提交首个包含标签均为 3.2.0；AggregatePath.java `@since 3.2`（L50） |
| `@Sequence` + `IdGeneratingEntityCallback` | 3.5 | Sequence.java `@since 3.5`（L15）、IdGeneratingEntityCallback.java `@since 3.5`（L34） |
| Value Expression 动态表名/列名（`@Table` 里写表达式） | 4.0 | BasicRelationalPersistentEntity 用 commons 4.0 的 `ValueExpressionParser`（L23、L47） |
| `JdbcRepositoryFactory(JdbcAggregateOperations)`、`StatementFactory`、`getRowMapper` | 4.0 | JdbcRepositoryFactory.java `@since 4.0`（L70）；`findAll(Pageable)` 废弃 `@Deprecated(since = "4.0")`（JdbcAggregateTemplate.java:363） |
| UPSERT 全链路 | 4.1 | DbAction.UpsertRoot `@since 4.1`（DbAction.java:124）、RelationalEntityUpsertWriter 引入提交首个包含标签 4.1.0、Dialect.getUpsertRenderContext `@since 4.1`（Dialect.java:180） |
| `deleteAllByQuery`、按查询加锁 | 4.2（本快照） | DbAction.DeleteRootByQuery/DeleteByQuery/AcquireLockAllRootByQuery `@since 4.2`（DbAction.java:260、292、374） |

三个容易搞错的点，特别提醒：

- **spring-data-r2dbc 不是"3.0 才诞生"**：它 2018 年就在本仓库短暂出现过（DATAJDBC-272 拆分时含 DatabaseClient），随后移出为独立仓库（spring-data-r2dbc 1.x/2.x 独立发布），2022-02-21 才并入本仓库随 3.0 发布。
- **单查询装载不是 4.x 特性**：3.2 就落地了（`SingleQueryDataAccessStrategy` 的 `@since 3.2`），且默认关闭、需显式开启（见 5.4 节）。
- **`@Version` 不属于本项目**：它定义在 spring-data-commons 的 `org.springframework.data.annotation` 包，本项目只是消费它。

### 1.6.3 对初学者的意义：哪些知识过时了，哪些永远有效

- **新项目避免使用**：`Page`/`Pageable` 版查询方法（4.0 起废弃，改用 `Window`/Scroll API，见《Spring Data Commons 深度源码解析》4.3 节）；`JdbcRepositoryFactory` 六参旧构造器；`NamingStrategy` 的旧 camelCase 假设（3.0 起默认 SNAKE_CASE——从 Boot 2.x 升 3.x 时表名映射会变，务必核对）。
- **2.x 老教程里永久有效的部分**：聚合映射注解、`isNew` 判定、事件监听、方法名推导语义——这些核心从 2.x 到 4.2 基本未变。
- **版本与 Boot 的对应关系**（选教材时对号入座）：Boot 2.2~2.7 ↔ spring-data-jdbc 1.1~2.7；Boot 3.0~3.5 ↔ 3.0~3.5；Boot 4.0/4.1/4.2 ↔ 4.0/4.1/4.2。从 Boot 3.x 入手的读者，实际用的就是 3.x 的内核，本文第二~七章的主线对它完全适用。

## 1.7 全文章节地图

- **第二章 核心映射模型（spring-data-relational: mapping 包）**：从"没有 XML/没有 ORM 注解轰炸"讲起，走完 `RelationalPersistentEntity` → 注解族 → `NamingStrategy` → `AggregatePath` 的元模型链路。
- **第三章 SQL 抽象与方言（core.sql + core.dialect）**：152 个类的语句 AST 如何建模、`SqlRenderer` 如何渲染、`Dialect` 的 14 个口子如何吸收数据库差异。
- **第四章 转换器与中间表示（core.conversion + domain）**：`RowDocument`、`MappingRelationalConverter`、`AggregateChange`/`DbAction`、四个聚合写入器——"聚合怎么变成一组数据库动作"。
- **第五章 spring-data-jdbc：聚合模板与数据访问（重点 A）**：`JdbcAggregateTemplate.save` 全流程、`AggregateChangeExecutor` 执行与 ID 回填、`DataAccessStrategy` 策略族、`SqlGenerator` 的 JOIN 与列收集策略、单查询装载、主键生成与乐观锁。
- **第六章 spring-data-jdbc：Repository 与查询方法（重点 B）**：引导装配、`SimpleJdbcRepository`、方法名推导、`@Query` 与参数绑定、事件与回调、schema 工具、AOT。
- **第七章 spring-data-r2dbc：响应式数据访问（重点 C）**：与 JDBC 的关键架构差异（实体中心 vs 聚合级联）、`R2dbcEntityTemplate` 的 insert/update 流水线、`StatementMapper`、响应式 Repository 与事务、Kotlin 协程。
- **第八章 贯通视图：一个聚合的生与死**：启动装配、save、findById 三条时间线叠加成一张全景图。
- **第九章 附录**：关键接口速查表、学习路线、源码阅读入口清单。

---

# 二、核心映射模型（spring-data-relational：mapping 包）

> 本章对应源码：`spring-data-relational/src/main/java/org/springframework/data/relational/core/mapping/`（26 个类，约 4000 行）。以下所有【源码证据】中的行号均为实际读取 4.2.0-SNAPSHOT 源码所得。

## 2.1 先说白话：没有 XML，注解也极少，框架凭什么知道表和列？

你写一个普通 Java 类，只加一个 `@Table`、一个 `@Id`，框架就能：推出表名（类名蛇形化）、推出列名（属性名蛇形化）、认出一对多集合（建子表外键）、认出一对一引用（LEFT JOIN）、处理 `@Version` 乐观锁。这套推断的全部依据就是本章的**元模型**：`RelationalMappingContext` 为每个类构建 `RelationalPersistentEntity`，其中每个属性包装成 `RelationalPersistentProperty`——命名策略决定"叫什么"，注解决定"覆盖成什么"，`AggregatePath` 决定"在聚合的哪一层"。

与 JPA 的对照可以一句话说清：JPA 的默认映射策略是"字段全量持久化 + 关联对象引用"，本项目的是"**简单字段持久化 + 集合/实体属性构成聚合结构**"。所以它的注解族只有 8 个左右，一页纸背得完。

## 2.2 模块定位与包结构

```
org.springframework.data.relational.core.mapping
├── RelationalMappingContext.java        # 映射上下文（Entry Point）
├── RelationalPersistentEntity.java      # 实体元数据接口（表名、id 列）
├── BasicRelationalPersistentEntity.java # 默认实现（表名推导 + 表达式求值）
├── RelationalPersistentProperty.java    # 属性元数据接口（列名、键列、序列）
├── BasicRelationalPersistentProperty.java
├── Table/Column/MappedCollection/Embedded/Sequence/InsertOnlyProperty.java  # 注解族
├── NamingStrategy/DefaultNamingStrategy/CachingNamingStrategy.java  # 命名
├── AggregatePath/DefaultAggregatePath/AggregatePathTableUtils.java  # 聚合路径（3.2 新）
├── EmbeddedContext/EmbeddedRelationalPersistentEntity/Property.java # 嵌入值对象
├── ForeignKeyNaming/OptimisticLockingUtils/PersistentPropertyTranslator.java
├── SqlIdentifierExpressionEvaluator/SqlIdentifierSanitizer.java     # 表达式动态命名
└── event/    # RelationalEvent 族（BeforeConvert/BeforeSave/AfterSave/...）
```

`RelationalMappingContext` 继承自 commons 的 `AbstractMappingContext`——`getPersistentEntity(Class)` 缓存、`PersistentProperty` 的创建时机、`addPersistentEntity` 等机制见《Spring Data Commons 深度源码解析》5.3 节。本项目只叠加了三件事：命名策略包装、聚合路径缓存、单查询装载开关。

【源码证据】`RelationalMappingContext.java` 第 45-55 行：

```java
public class RelationalMappingContext
		extends AbstractMappingContext<RelationalPersistentEntity<?>, RelationalPersistentProperty> {

	private final NamingStrategy namingStrategy;
	private final Map<AggregatePathCacheKey, AggregatePath> aggregatePathCache = new ConcurrentHashMap<>();

	private boolean forceQuote = true;
	……
	private boolean singleQueryLoadingEnabled = false;   // 3.2 引入，默认关（L55）
```

- `aggregatePathCache`：`AggregatePath` 是高频查询对象（SQL 生成的每一步都要问"这个路径对应哪张表哪列"），从 3.2 起按 key 缓存。
- `forceQuote = true`：默认给所有表名/列名加引号（避免与关键字冲突；副作用是 Postgres 里大小写敏感——很多"升 3.x 后列名对不上"的问题源于此，可 `setForceQuote(false)` 关闭）。
- 构造器把外部传入的 `NamingStrategy` 用 `CachingNamingStrategy` 包一层（L69-76），因为命名推导在实体扫描期被调用成千上万次。

## 2.3 RelationalPersistentEntity / RelationalPersistentProperty：实体的"身份证"

**白话**：`RelationalPersistentEntity` 回答"这个类对应哪张表、主键是谁"；`RelationalPersistentProperty` 回答"这个属性对应哪一列、是不是外键回指列、有没有 Map 键列"。

【源码证据】`RelationalPersistentEntity.java` 第 30-58 行（接口全貌）：

```java
public interface RelationalPersistentEntity<T> extends MutablePersistentEntity<T, RelationalPersistentProperty> {

	SqlIdentifier getTableName();                        // L37 未限定表名

	default SqlIdentifier getQualifiedTableName() {     // L45 带 schema 的全名（@since 3.0）
		return getTableName();
	}

	@Deprecated(forRemoval = true)
	SqlIdentifier getIdColumn();                         // L57 复合主键时代已废弃
}
```

注意 `getIdColumn` 的废弃注释（L53-54）："an entity may have multiple id columns. Use `AggregatePath.getTableInfo().getIdColumnInfos()`"——**复合主键（引用实体的 id 也可能是复合的）是 3.2 之后的主线**，单 id 列的旧 API 让位于聚合路径上的列信息族。

【源码证据】`RelationalPersistentProperty.java` 第 30-113 行（关键方法）：

```java
public interface RelationalPersistentProperty extends PersistentProperty<RelationalPersistentProperty> {

	SqlIdentifier getColumnName();                       // L37 本属性对应的列

	SqlIdentifier getReverseColumnName(RelationalPersistentEntity<?> owner);  // L54 外键回指列！

	@Nullable SqlIdentifier getKeyColumn();              // L57 Map/List 的键列

	boolean isQualified();                               // L63 Map/List 类属性
	boolean isOrdered();                                 // L70 List 需要序号列
	default boolean isEmbedded() { return false; }       // L75 嵌入值对象
	default boolean isInsertOnly() { return false; }     // L96 @InsertOnlyProperty（3.0）
	@Nullable SqlIdentifier getSequence();               // L103 @Sequence（3.5）
}
```

`getReverseColumnName` 是整个聚合映射的**枢纽方法**：子表里指回父表的列（如 `order_item.order_id`）不是声明在子实体上的属性，而是由"拥有这个集合属性的父实体"推导出来的——`NamingStrategy.getReverseColumnName` 默认返回**父表名**（见 2.5 节），`@MappedCollection(idColumn=…)` 可覆盖。理解了"回指列不属于子实体，而属于关系"，就看懂了本项目与 JPA `@JoinColumn` 的本质区别：**没有双向关联，只有"父实体上的集合属性"和它携带的映射信息**。

### 2.3.1 表名推导：BasicRelationalPersistentEntity

【源码证据】`BasicRelationalPersistentEntity.java` 第 61-92 行（构造器）：

```java
	BasicRelationalPersistentEntity(TypeInformation<T> information, NamingStrategy namingStrategy,
			SqlIdentifierExpressionEvaluator sqlIdentifierExpressionEvaluator) {

		super(information);
		……
		if (isAnnotationPresent(Table.class)) {

			Table table = getRequiredAnnotation(Table.class);

			this.tableName = Lazy.of(() -> StringUtils.hasText(table.value()) ? createSqlIdentifier(table.value())
					: createDerivedSqlIdentifier(namingStrategy.getTableName(getType())));   // L76-77
			this.tableNameExpression = detectExpression(table.value());                       // L78

			this.schemaName = StringUtils.hasText(table.schema())
					? Lazy.of(() -> Optional.of(createSqlIdentifier(table.schema())))
					: defaultSchema;                                                          // L80-82
			……
		} else {
			this.tableName = Lazy.of(() -> createDerivedSqlIdentifier(namingStrategy.getTableName(getType())));  // L87
			……
		}
	}
```

表名解析的优先级：`@Table(value)` 显式值 → **Value Expression**（`detectExpression`，L101-109：用 commons 4.0 的 `ValueExpressionParser` 解析，非字面量才保留为表达式）→ `NamingStrategy.getTableName` 推导。表达式的求值发生在 `getTableName()` 每次调用时（L128-135），求值器带 `SqlIdentifierSanitizer` 消毒（`RelationalMappingContext.setSqlIdentifierSanitizer`，3.2 引入）——这支持了 `@Table("#{environment['target.table']}")` 这类按环境切换表名的玩法。

标识符的引号语义由两个类分摊：`SqlIdentifier.quoted/unquoted`（显式）与 `DerivedSqlIdentifier`（推导名，携带 `forceQuote` 标志，L115-117）。`SqlIdentifier` 是 core.sql 包的值对象（第三章），它让"加不加引号、大小写折叠"成为**数据**而不是散落的字符串拼接。

## 2.4 注解族：八个注解讲完映射

| 注解 | 位置 | 作用 | 版本 |
|---|---|---|---|
| `@Table(value, schema)` | 类 | 表名/schema，支持表达式 | 初始 |
| `@Id`（commons） | 属性 | 主键（可复合：多个 `@Id`） | commons |
| `@Version`（commons） | 属性 | 乐观锁版本列 | commons |
| `@Column(value)` | 属性 | 列名覆盖 | 初始 |
| `@MappedCollection(idColumn, keyColumn)` | 集合/Map 属性 | 反向外键列名 + Map/List 键列名 | 1.1 |
| `@Embedded.Nullable/@Embedded.Empty` | 属性 | 值对象平铺进本表（列前缀） | 1.1 |
| `@InsertOnlyProperty` | 属性 | 只在 INSERT 写入，UPDATE 不动（审计/日志列） | 3.0 |
| `@Sequence(name, start, allocationSize)` | id 属性 | 声明序列来源，配合 `IdGeneratingEntityCallback` | 3.5 |

【源码证据】`MappedCollection.java` 第 37-62 行（注解定义与两个属性）：

```java
public @interface MappedCollection {

	String idColumn() default "";    // L48 子表反向外键列名
	String keyColumn() default "";   // L60 Map 键 / List 序号列名
}
```

`AggregateReference<ID, T>`（spring-data-jdbc 的 `core/mapping/AggregateReference.java`）补充跨聚合引用的另一半：属性类型声明为 `AggregateReference<Long, Customer>`，落库存的就是 `Long`，读回来是"只带 id 的引用"（`IdOnlyAggregateReference`）。它显式表达了"跨聚合只传 ID"的 DDD 戒律——你**不能**拿它做 JOIN 查询，这正是设计意图。

## 2.5 NamingStrategy：名字的单一出处

【源码证据】`NamingStrategy.java` 第 42-91 行（默认方法族）：

```java
	default String getSchema() { … }                                        // L42
	default String getTableName(Class<?> type) { … }                        // L50
	default String getColumnName(RelationalPersistentProperty property) { … }  // L61
	default String getReverseColumnName(RelationalPersistentProperty property) { … }  // L74
	default String getReverseColumnName(RelationalPersistentEntity<?> owner) { … }    // L81
	default String getKeyColumn(RelationalPersistentProperty property) { … }          // L91
```

【源码证据】`DefaultNamingStrategy.java` 第 21-68 行：

```java
/**
 * The default naming strategy used by Spring Data Relational. Names are in {@code SNAKE_CASE}.
 * @since 3.0
 */
public class DefaultNamingStrategy implements NamingStrategy {

	public static NamingStrategy INSTANCE = new DefaultNamingStrategy() { … };   // L34 不可变实例

	private ForeignKeyNaming foreignKeyNaming = ForeignKeyNaming.APPLY_RENAMING; // L41

	@Override
	public String getReverseColumnName(RelationalPersistentEntity<?> parent) {   // L57
		return getColumnNameReferencing(parent);
	}

	private String getColumnNameReferencing(RelationalPersistentEntity<?> entity) {  // L61
		if (foreignKeyNaming == ForeignKeyNaming.IGNORE_RENAMING) {
			return getTableName(entity.getType());     // 用物理表名做外键列名
		}
		return entity.getTableName().getReference();   // 用推导表名做外键列名
	}
}
```

要点：① 3.0 起**默认 SNAKE_CASE**（`CustomerOrder` → `customer_order`）；② 外键回指列默认 = **父表推导名**（`Order` 聚合根下的 `items` 集合 → 子表列 `order`，单数化不做——注意是表名直接当列名）；③ `ForeignKeyNaming` 枚举决定父实体上的 `@Column` 重命名是否影响外键列名（`APPLY_RENAMING` 默认影响）；④ `CachingNamingStrategy` 包装后所有推导只算一次。

## 2.6 AggregatePath：聚合里"从根到叶的路径"（3.2 起的核心抽象）

**白话**：SQL 生成时反复需要回答三类问题——"这条属性路径的叶子在**哪张表**？""指回父表的**列**叫什么？""这张表的主键**列**是什么？"。3.2 之前这些答案散落在 `SqlGenerator` 的私有方法里；3.2 起统一抽象成 `AggregatePath`（从聚合根到任意属性的路径），并把答案封装为 `AggregatePath.TableInfo`（表名、id 列信息、回指列信息、qualifier 列信息）。

【源码证据】`AggregatePath.java` 第 41-120 行（类注释与关键方法）：

```java
/**
 * Represents a path within an aggregate starting from the aggregate root.
 * The path can be iterated from the leaf to its root.
 * …
 * @since 3.2
 */
public interface AggregatePath extends Iterable<AggregatePath>, Comparable<AggregatePath> {

	AggregatePath getParentPath();                  // L60 上一段路径
	AggregatePath append(RelationalPersistentProperty property);  // L68 延长路径
	boolean isRoot();                               // L82 是否根路径
	boolean isEntity();                             // L98 叶子是引用实体
	boolean isEmbedded();                           // L105 叶子是嵌入值对象
	boolean isMultiValued();                        // L115 路径上含集合/Map/数组（决定 JOIN 还是子查询）
	……
}
```

`isMultiValued()` 是 SQL 生成策略的分水岭（第五章 5.5 节展开）：**多值路径不参与 JOIN，由单独的查询装载；单值实体路径用 LEFT OUTER JOIN 平铺进主查询**。`RelationalMappingContext.getAggregatePath(PersistentPropertyPath)` 是工厂入口（带 `aggregatePathCache` 缓存），`RelationalPredicates.isRelation` 等谓词辅助"找出所有引用路径"（`WritingContext` 构造器即用它枚举聚合内全部关联路径，见 4.4 节）。

## 2.7 本章小结

- 元模型三件套 `RelationalMappingContext/PersistentEntity/PersistentProperty` 继承自 commons，叠加了表名推导、表达式动态命名、命名策略缓存、聚合路径缓存四件事。
- 命名链路：`@Table`/`@Column` 显式值 → Value Expression → `NamingStrategy`（默认 SNAKE_CASE）；子表回指列由 `getReverseColumnName` 从**父实体**推导。
- 注解族 8 个：`@Table/@Id/@Version/@Column/@MappedCollection/@Embedded/@InsertOnlyProperty/@Sequence`——没有 `@OneToMany`，没有双向关联，聚合结构完全由"父实体上的集合属性"表达。
- `AggregatePath`（3.2）把"路径 → 表/列"的问答集中化，是单查询装载与 SQL 生成新架构的地基。
- 所有标识符都是 `SqlIdentifier` 值对象——引号、大小写、表达式消毒都是类型系统的一部分，而不是字符串约定。


---

# 三、SQL 抽象与方言（core.sql + core.dialect）

> 本章对应源码：`spring-data-relational/src/main/java/org/springframework/data/relational/core/sql/`（152 个类）与 `core/dialect/`（27 个类）。

## 3.1 先说白话：为什么不直接拼字符串？

方法名推导要生成 `SELECT ... LEFT OUTER JOIN ... WHERE name = ? AND age > ? ORDER BY id LIMIT ?`，手拼字符串意味着：列名引号逻辑、分页方言、LIKE 转义散落在几百个 if 里。本项目的做法是把 SQL 做成**不可变对象图（AST）**：`Select`/`Insert`/`Update`/`Delete`/`Upsert` 是语句节点，`Column`/`Table`/`Condition`/`Function`/`BindMarker` 是表达式节点；渲染器 `SqlRenderer` 遍历这棵树，按 `Dialect` 提供的渲染规则输出字符串。这个设计与《Spring Framework 深度源码解析》3.5 节"类型转换双体系"的思路同源：**先把领域问题建模成类型，再用策略吸收变体**。

【源码证据】`sql/SQL.java` 第 22-37 行——AST 的官方入口说明：

```java
/**
 * Utility to create SQL {@link Segment}s. Typically used as entry point to the Statement Builder.
 * Objects and dependent objects created by the Query AST are immutable except for builders.
 * <p>
 * The Statement Builder API is intended for framework usage to produce SQL required
 * for framework operations.
 * @since 1.1
 */
public abstract class SQL { … }
```

注意那句 "intended for framework usage"——**这个 AST 是给框架自己用的**，不是给应用层用的查询 DSL（那是 commons 的 `Criteria`/`Query`，见第六章 6.3 节）。分层是：应用层 `Query/Criteria`（存储无关）→ 各模块翻译成 SQL AST → `SqlRenderer` 渲染成字符串。

## 3.2 语句模型：152 个类的家族结构

152 个类按角色分四组（按文件名前缀实测分组）：

```
语句节点（Standalone Statement）
├── Select / Insert / Update / Delete / Upsert          # 五种语句（Default* 是实现）
├── DefaultSelectBuilder / DefaultInsertBuilder / ...   # 流式构建器（SelectBuilder.SelectWhere…）
表达式节点（Expression）
├── Column / Table / AsteriskFromTable / InlineQuery
├── Literal（NumericLiteral/StringLiteral/BooleanLiteral/…）
├── BindMarker（? 占位 / NamedBindMarker 命名占位）
├── Functions（count/sum/avg/max/min/coalesce/…）
├── SimpleFunction / AnalyticFunction / CaseExpression / Cast
条件节点（Condition）
├── Comparison / In / Between / Like / IsNull / Not
├── AndCondition / OrCondition（MultipleCondition）/ NestedCondition
├── Conditions（工厂：isEqual/isIn/isNotIn/isNotNull/…）
标识符（Identifier）
├── SqlIdentifier（值对象：quoted/unquoted/quotedWith/…）
├── DefaultSqlIdentifier / CompositeSqlIdentifier / DerivedSqlIdentifier
└── IdentifierProcessing（引号风格 + 大小写折叠规则 → 方言提供）
渲染（render 子包）
├── Renderer / SqlRenderer（对外门面）
├── SelectStatementRenderer / InsertStatementRenderer / …（visit 分发）
└── RenderContext / SelectRenderContext / InsertRenderContext / UpsertRenderContext
```

两个设计细节值得驻足：

1. **构建器是"渐进式接口"**：`StatementBuilder.select(col).from(t).where(c).orderBy(...)` 每一步返回的类型都不一样（`SelectBuilder.SelectFromAndOrderBy` 等），编译期就杜绝"先 where 后 select"的非法顺序——这是 Builder 模式的类型安全变体。
2. **条件树不可变可复用**：`Condition` 节点一旦创建不能改，`and(...)`/`or(...)` 生成新节点——查询推导（第六章）每解析一个 `Part` 就挂一个条件节点，最后整棵树交给渲染器。

## 3.3 SqlRenderer：从 AST 到字符串

【源码证据】`sql/render/SqlRenderer.java` 第 26-124 行（节选）：

```java
/**
 * Renders {@link Segment} SQL rendering.
 * @author Mark Paluch
 * @author Jens Schauder
 * @since 1.1
 */
public class SqlRenderer implements Renderer {

	private final RenderContext context;

	private SqlRenderer(RenderContext context) { … }

	public static SqlRenderer create(RenderContext context) { … }   // ① 工厂入口（L59）
	……

	public String render(Select select) { … }    // ② 五种语句各一个渲染入口（L115 起）
	public String render(Insert insert) { … }
	public String render(Update update) { … }
	public String render(Delete delete) { … }
	public String render(Upsert upsert) { … }    // 4.1 新增
}
```

门面背后是一组 visit 分发渲染器（`SelectStatementRenderer` 等），遍历逻辑没有魔法：`render(Select)` 依次渲染 `SELECT 列表`、`FROM 表`、`JOIN 子句`、`WHERE`、`GROUP BY`、`HAVING`、`ORDER BY`、`LIMIT/OFFSET`、锁子句。所有"数据库差异"都被提前塞进 `RenderContext`：

- `IdentifierProcessing`：标识符加引号（`quote("`）与大小写折叠（`letterCase`）规则——MySQL 用反引号、SQL Server 用方括号、Postgres 用双引号 + 小写折叠，全由方言给出；
- `SelectRenderContext`：分页 LIMIT/OFFSET 渲染钩子、`exists` 的 COUNT 函数（`Dialect.getExistsFunction()`，3.0 引入——某些库要 `LEAST(COUNT(1), 1)`）、锁子句渲染；
- `InsertRenderContext`（2.4）：批量 INSERT 多 values 组的拼接方式；
- `UpsertRenderContext`（4.1）：单语句 UPSERT 渲染（默认 ANSI `MERGE`，Postgres/MySQL 各自覆盖为 `ON CONFLICT`/`ON DUPLICATE KEY UPDATE`）。

## 3.4 Dialect：14 个口子的方言接口

【源码证据】`dialect/Dialect.java` 第 46-186 行（接口全貌，按 @since 标注）：

```java
public interface Dialect {

	default String getName() { … }                          // L54  4.1
	default IdentifierProcessing getIdentifierProcessing() { return IdentifierProcessing.ANSI; }  // L65  2.0
	default IdGeneration getIdGeneration() { return IdGeneration.DEFAULT; }                        // L74
	LockClause lock();                                      // L83  SELECT ... FOR UPDATE 的渲染
	default ArrayColumns getArraySupport() { return ArrayColumns.unsupported(); }                 // L90
	default Collection<Object> getConverters() { return Collections.emptySet(); }                 // L99
	default Set<Class<?>> simpleTypes() { return Collections.emptySet(); }                        // L109 2.3
	default SimpleFunction getExistsFunction() { return Functions.count(SQL.literalOf(1)); }      // L119 3.0
	default boolean supportsSingleQueryLoading() { return true; }                                 // L128
	default Escaper getLikeEscaper() { return Escaper.DEFAULT; }                                  // L138 2.0
	LimitClause limit();                                    // L147 分页
	default OrderByNullPrecedence orderByNullHandling() { … }                                     // L155 2.4
	SelectRenderContext getSelectContext();                 // L164
	default InsertRenderContext getInsertRenderContext() { return InsertRenderContexts.DEFAULT; } // L171 2.4
	default UpsertRenderContext getUpsertRenderContext() { return UpsertRenderContexts.MERGE; }   // L182 4.1
}
```

实现矩阵（`core/dialect/` 下 8 家 + `AbstractDialect`/`AnsiDialect` 两个基座；r2dbc 模块的 `dialect/` 另有 6 家对应版）：

| 方言 | 分页 LIMIT 渲染 | UPSERT | 备注 |
|---|---|---|---|
| AnsiDialect | ANSI 标准 | MERGE | 抽象基座 |
| H2/HsqlDb | `LIMIT n OFFSET m` | MERGE | |
| MySQL/MariaDB | `LIMIT n OFFSET m` | `INSERT … ON DUPLICATE KEY UPDATE` | 反引号引标识符 |
| PostgreSQL | `LIMIT n OFFSET m` | `INSERT … ON CONFLICT DO …` | 小写折叠 |
| SQL Server | `OFFSET m ROWS FETCH NEXT n ROWS ONLY` | MERGE | 方括号引标识符 |
| Db2/Oracle | 各自语法 | MERGE | Oracle 支持数组列 |

JDBC 侧 8 家；R2DBC 侧另有 5 家对应实现（H2/MySQL/Oracle/Postgres/SQL Server，`R2dbcDialect` 接口额外提供 BindMarkers 等响应式特有口子）。`AbstractDialect`（4.1 引入的基类）把可推导的默认值集中，子类只覆盖差异点。方言间的复用关系以"组合"为主：JDBC 模块的 `JdbcDialect` 在 `Dialect` 之上只追加 JDBC 特有的能力（如 `renderForGeneratedValues` 等，见 `spring-data-jdbc/.../core/dialect/JdbcDialect.java`）。

## 3.5 方言的自动探测

**白话**：应用不该手写 "我是 Postgres"。JDBC 侧用 `DatabaseMetaData.getDatabaseProductName()` 匹配；R2DBC 侧用 `ConnectionFactoryMetadata`。两者都预留 SPI 扩展。

【源码证据】`spring-data-jdbc/.../core/dialect/DialectResolver.java` 第 122-151 行（探测分支）：

```java
		private static JdbcDialect getDialect(Connection connection) throws SQLException {

			DatabaseMetaData metaData = connection.getMetaData();
			String name = metaData.getDatabaseProductName().toLowerCase(Locale.ENGLISH);

			if (name.contains("hsql"))       return JdbcHsqlDbDialect.INSTANCE;
			if (name.contains("h2"))         return JdbcH2Dialect.INSTANCE;
			if (name.contains("mysql"))      return new JdbcMySqlDialect(getIdentifierProcessing(metaData));
			if (name.contains("mariadb"))    return new JdbcMariaDbDialect(getIdentifierProcessing(metaData));
			if (name.contains("postgresql")) return JdbcPostgresDialect.INSTANCE;
			if (name.contains("microsoft"))  return JdbcSqlServerDialect.INSTANCE;
			if (name.contains("db2"))        return JdbcDb2Dialect.INSTANCE;
			if (name.contains("oracle"))     return JdbcOracleDialect.INSTANCE;
			……
			return null;
		}
```

两个细节：① MySQL/MariaDB 的引号风格不是硬编码，而是现场读 `getIdentifierQuoteString()`（L156 起的 `getIdentifierProcessing`）；② `DialectResolver` 实现了 `spring.factories` 风格的 `JdbcDialectProvider` SPI（`getDialect` 先遍历已注册 Provider 再走内置分支，L84-88），第三方数据库可以无侵入接入——这是《Spring Framework 深度源码解析》第七章 "spring.factories 面向生态链的扩展" 模式在数据层的回声。

## 3.6 本章小结

- SQL 是"先建模、后渲染"：应用层 `Query/Criteria`（commons 语义）→ SQL AST（core.sql，框架内部）→ `SqlRenderer` + `RenderContext` 输出字符串。
- `Dialect` 的 15 个方法覆盖标识符引号、分页、锁、UPSERT、LIKE 转义、数组、类型转换器、单查询能力声明；启动时按数据库产品名自动探测，SPI 可扩展。
- 语句 AST 面向框架内部使用；用户侧请使用 `Query`/`Criteria`/`Update`（`core/query/` 包，第六章）。


---

# 四、转换器与中间表示（core.conversion + domain）

> 本章对应源码：`spring-data-relational/.../core/conversion/`（45 个类）与 `core/domain/`。这是全文的"钥匙章"：理解了 `AggregateChange`/`DbAction`，第五章的执行流程就是水到渠成。

## 4.1 先说白话：保存一个聚合 = 生成一组数据库动作

`save(order)` 时，`Order` 聚合根带 5 个 `OrderItem`、3 个删掉的旧 item、1 个一对一 `ShippingInfo`。框架的思路是**两段式**：第一段（写侧）把这个"期望状态"翻译成一串数据库动作——insert 根、delete 旧子行、insert 新子行——每条动作记着自己的依赖（子行的 insert 依赖根的 id）；第二段（执行侧，在 JDBC/R2DBC 模块）按依赖顺序执行这些动作，把生成的 id 回填进实体。**第一段的产物就是 `AggregateChange`（一组 `DbAction`），第二段对它一无所知地执行**——阻塞与非阻塞两个世界的分界线恰好画在这里。

```
                    写侧（spring-data-relational，与驱动无关）
 ┌──────────┐   RelationalEntityInsertWriter   ┌─────────────────────────────┐
 │ save(聚合)│ ───────────────────────────────► │ RootAggregateChange<T>      │
 └──────────┘   RelationalEntityUpdateWriter    │  = InsertRoot + Delete* +   │
                RelationalEntityUpsertWriter    │    Insert*(子行,依赖父id)    │
                RelationalEntityDeleteWriter    └─────────────────────────────┘
                （四个写入器 → WritingContext）              │
                                                            ▼
                    执行侧（spring-data-jdbc 或 spring-data-r2dbc）
                                              AggregateChangeExecutor 逐条执行
                                              → 回填 id → 返回"保存后的聚合"
```

## 4.2 AggregateChange 与 DbAction：中间表示全目录

【源码证据】`conversion/AggregateChange.java` 第 27-65 行：

```java
/**
 * Represents the change happening to the aggregate (as used in the context of
 * Domain Driven Design) as a whole.
 */
public interface AggregateChange<T> {

	Kind getKind();                 // SAVE 或 DELETE（L53-65 枚举）
	Class<T> getEntityType();
	void forEachAction(Consumer<? super DbAction<?>> consumer);   // 执行器的遍历入口
}
```

`DbAction` 是"一次（概念上的）数据库交互"的模型，4.2.0-SNAPSHOT 的完整目录（`DbAction.java`，共 615 行）：

| DbAction | 行号 | 语义 | 版本 |
|---|---|---|---|
| `Insert<T>`（record） | L55 | 非根实体 insert；携带 `propertyPath`（在聚合中的位置）、`dependingOn`（父动作，取父 id）、`qualifiers`（Map 键/List 序号）、`idValueSource` | 初始 |
| `InsertRoot<T>` | L88 | 聚合根 insert；成功后回填生成的 id | 初始 |
| `UpsertRoot<T>` | L126 | 根 UPSERT（要求 id 已知） | **4.1** |
| `UpdateRoot<T>` | L160 | 根 update；携带 `previousVersion`（乐观锁） | 初始 |
| `Delete<T>`（record） | L200 | 按"根 id + 属性路径"删除某层子表 | 初始 |
| `DeleteRoot<T>`（record） | L217 | 按 id 删根（带 `previousVersion`） | 初始 |
| `DeleteAll<T>` / `DeleteAllRoot<T>` | L232 / L249 | 全表删（某层/根） | 初始 |
| `DeleteRootByQuery<T>` / `DeleteByQuery<T>` | L262 / L294 | 按查询删根/删子层 | **4.2** |
| `AcquireLockRoot<T>` / `AcquireLockAllRoot<T>` / `AcquireLockAllRootByQuery<T>` | L324 / L352 / L376 | `SELECT ... FOR UPDATE` 锁根 | 初始 / 初始 / **4.2** |
| `BatchWithValue<T,A,B>` 抽象 | L407 | 批量动作基类：一组动作 + 相同的批值 | 3.0 |
| `BatchInsert<T>` / `BatchInsertRoot<T>` / `BatchDelete<T>` / `BatchDeleteRoot<T>` | L457/L469/L482/L495 | 批量变体 | 2.4 / 3.0 / 3.0 / 3.0 |

设计上最讲究的是 `WithDependingOn` 接口（L507-558）：子行 insert 必须携带 `dependingOn`（父动作）和 `qualifiers`（集合限定值）——**执行器因此能保证"先父后子"，而父 id 可能是数据库刚生成的**。这就是"聚合整体保存"如何在多表间保持引用完整性的全部秘密。

主键来源判定是另一个小而关键的枚举：

【源码证据】`conversion/IdValueSource.java` 第 28-50 行（节选）：

```java
public enum IdValueSource {

	PROVIDED,    // 调用者给了 id
	GENERATED,   // 期望数据库生成（自增/序列）
	NONE;        // 实体没有 id 属性

	public static <T> IdValueSource forInstance(Object instance, RelationalPersistentEntity<T> persistentEntity) {  // L50
		RelationalPersistentProperty idProperty = persistentEntity.getIdProperty();

		if (idProperty != null && idProperty.hasSequence()) {
			return IdValueSource.PROVIDED;      // @Sequence：插入前由回调填值，视为"已提供"
		}
		……
		// id 为 null，或 int/long 的 0 → GENERATED
	}
}
```

## 4.3 RowDocument 与 MappingRelationalConverter：读取侧的"文档化"翻转

**白话**：从 ResultSet 读一个聚合（单查询 JOIN 模式下），行是平的、子行会重复根列。3.2 引入的思路：先把结果集**折叠成嵌套文档** `RowDocument`（类似 Mongo 的 Document：列 → 值，子表 → 嵌套 Document/数组），再用统一的 `RelationalConverter` 从文档"读出"实体。读路径与写路径对称，JDBC 与 R2DBC 共用同一套"文档 → 实体"代码。

【源码证据】`domain/RowDocument.java` 第 31-39 行：

```java
/**
 * Represents a tabular structure as document to enable hierarchical traversal of SQL results.
 * …
 * @since 3.2
 */
public class RowDocument implements Map<String, Object> {

	private final Map<String, Object> delegate;   // LinkedCaseInsensitiveMap（L39）
```

【源码证据】`conversion/MappingRelationalConverter.java` 第 89、186、308 行（核心 API）：

```java
public class MappingRelationalConverter extends AbstractRelationalConverter … {   // L89

	public <M, D> EntityProjection<M, D> introspectProjection(Class<M> resultType, Class<D> entityType) {  // L186 3.2
		…  // 内省"要读出哪些属性、嵌套到什么深度"——投影与属性路径分析
	}

	public <R> R read(Class<R> type, RowDocument source) {   // L308 文档 → 聚合
		…
	}
	// 写方向：write(Object, RowDocument) → OutboundRow（r2dbc）或列参数表（jdbc 由 QueryMapper/SqlParametersFactory 承接）
}
```

`RowDocumentAccessor`（`conversion/RowDocumentAccessor.java`）负责惰性大小写对齐与属性访问；`PropertyValueConverter`（commons 2.7）提供属性级读写转换；`AggregateReader`（spring-data-jdbc，3.2，L55）+ `RowDocumentResultSetExtractor`（3.2，L47）+ `RowDocumentExtractorSupport` 组成 JDBC 侧的"结果集 → 文档"管线——第七章会看到 R2DBC 侧直接用 `Row` → `RowDocument`，两侧在 `RowDocument` 层会师。

## 4.4 四个聚合写入器与 WritingContext：级联的生成规则

**白话**：insert 写入器把聚合翻译成"根 insert + 各层 insert"；update 写入器是"根 update + **先删旧子行** + 插新子行"；upsert 写入器（4.1）不判断 isNew 直接 UPSERT 根；delete 写入器按"从叶到根"生成删除。真正干活的都是共享的 `WritingContext`。

【源码证据】`conversion/RelationalEntityInsertWriter.java` 第 38-42 行：

```java
	@Override
	public void write(T root, RootAggregateChange<T> aggregateChange) {
		new WritingContext<>(context, root, aggregateChange).insert();
	}
```

【源码证据】`conversion/WritingContext.java` 第 57-118 行（构造器 + 四种模式）：

```java
	WritingContext(RelationalMappingContext context, T root, RootAggregateChange<T> aggregateChange) {
		…
		this.rootIdValueSource = IdValueSource.forInstance(root, …);          // L64
		this.paths = context.findPersistentPropertyPaths(entityType, RelationalPredicates::isRelation) // L66
				.filter(ppp -> context.getAggregatePath(ppp).isWritable()).toList();
		// ↑ 一口气找出聚合内所有"引用路径"（集合/实体属性），非可写路径剔除
	}

	void insert() {
		setRootAction(new DbAction.InsertRoot<>(root, rootIdValueSource));    // L77
		insertReferenced().forEach(aggregateChange::addAction);               // L78
	}

	void update() {
		setRootAction(new DbAction.UpdateRoot<>(root, previousVersion));      // L89
		deleteReferenced().forEach(aggregateChange::addAction);               // L90 ① 先删
		insertReferenced().forEach(aggregateChange::addAction);               // L91 ② 再插
	}

	void save() {
		if (isNew(root)) { …同 insert()… } else { …同 update()… }            // L94-106
	}

	void upsert() {                                                          // L113 4.1
		setRootAction(new DbAction.UpsertRoot<>(root));
		deleteReferenced().forEach(aggregateChange::addAction);
		insertReferenced().forEach(aggregateChange::addAction);
	}
```

"先删后插"是本项目 update 语义的关键：**集合属性不做差量比对**（不查旧值、不逐个 merge），而是按"旧的全删、新的全插"重放。概念简单的代价是子表 ID 无法保持稳定（除非子实体自带主键且用 `Delete by id` 之外的策略——默认不是）。这正是"no dirty tracking"哲学在写路径的体现。

子行 insert 的生成在 `insertAll(path)`（L136-169）：遍历该路径上的每个值节点，用 `previousActions` 记录的**父动作**作为 `dependingOn`，集合限定值（List 序号/Map 键）放进 `qualifiers`（L146-161）——执行期这些限定值会被回填为列值。

## 4.5 版本号：RelationalEntityVersionUtils

乐观锁的版本号维护被集中在 `conversion/RelationalEntityVersionUtils`（get/set 版本值，带类型转换）；谁调用它、何时调用，见 5.2 节 `JdbcAggregateTemplate.prepareVersionForInsert/Update`。R2DBC 侧则在更新行数为 0 时抛 `OptimisticLockingFailureException`（7.4 节）。

## 4.6 本章小结

- 两段式持久化：写侧把聚合翻译成 `AggregateChange`（一组带依赖关系的 `DbAction`），执行侧按依赖序执行并回填 id——驱动差异被挡在第二段。
- `DbAction` 目录 17 个（含 4.1 的 Upsert、4.2 的按查询删除/锁），`WithDependingOn` 用"父动作引用"表达外键依赖，`qualifiers` 表达集合限定。
- update 的集合语义是"先删后插"，没有差量比对——与"no dirty tracking"哲学一致。
- 读取侧 3.2 起统一为"结果集 → `RowDocument` → 实体"，`MappingRelationalConverter` 用 `EntityProjection` 内省控制读取深度，JDBC/R2DBC 在文档层会师。


---

# 五、spring-data-jdbc：聚合模板与数据访问（重点 A）

> 本章对应源码：`spring-data-jdbc/src/main/java/org/springframework/data/jdbc/core/`（86 个类）与 `core/convert/`（49 个类）。这是用户感知最强的模块：`JdbcAggregateTemplate` 是所有 CRUD 的编排者。

## 5.1 模块定位

`JdbcAggregateTemplate`（`JdbcAggregateOperations` 接口的唯一实现）持有四件套：`JdbcConverter`（转换）、`DataAccessStrategy`（数据访问）、`RelationalEntityDeleteWriter`（删侧写入器）、`AggregateChangeExecutor`（执行器）；外加 `EntityLifecycleEventDelegate`（事件开关与发布）和 `EntityCallbacks`（回调链）。

【源码证据】`core/JdbcAggregateTemplate.java` 第 74-148 行（关键字段与私有构造器）：

```java
public class JdbcAggregateTemplate implements JdbcAggregateOperations, ApplicationContextAware {

	private final EntityLifecycleEventDelegate eventDelegate = new EntityLifecycleEventDelegate();
	private final RelationalMappingContext context;
	private final RelationalEntityDeleteWriter jdbcEntityDeleteWriter;
	private final DataAccessStrategy accessStrategy;
	private final AggregateChangeExecutor executor;
	private final JdbcConverter converter;
	……
	private JdbcAggregateTemplate(JdbcConverter converter, RelationalMappingContext context,
			DataAccessStrategy dataAccessStrategy) {
		……
		this.jdbcEntityDeleteWriter = new RelationalEntityDeleteWriter(context);   // L146
		this.executor = new AggregateChangeExecutor(converter, accessStrategy);    // L147
	}
```

## 5.2 save 全流程（本章核心）

**白话**：`save(聚合)` 不是一句 SQL，而是一场"事件 → 写入器 → 执行器 → 事件"的编排。全程五步，每步都有源码锚点。

【源码证据】`JdbcAggregateTemplate.java` 第 205-213 行（save 入口）与第 629-634 行（insert/update 分流）：

```java
	@Override
	public <T> T save(T instance) {

		Assert.notNull(instance, "Aggregate instance must not be null");

		verifyIdProperty(instance);                                    // ① 无 @Id 直接报错（L530-533）

		return performSave(new EntityAndChangeCreator<>(instance, changeCreatorSelectorForSave(instance)));
	}

	private <T> AggregateChangeCreator<T> changeCreatorSelectorForSave(T instance) {   // L629

		return context.getRequiredPersistentEntity(instance.getClass()).isNew(instance)
				? entity -> createInsertChange(prepareVersionForInsert(entity))        // 新 → 插
				: entity -> createUpdateChange(prepareVersionForUpdate(entity));       // 旧 → 更
	}
```

**第二步：事件前奏（beforeExecute，L565-578）**。`triggerBeforeConvert` 先发 `BeforeConvertEvent` + 跑 `BeforeConvertCallback`（**实体还没变成动作**，此刻修改实体最安全）；然后 changeCreator 被调用——写入器生成 `RootAggregateChange`；接着 `triggerBeforeSave` 发 `BeforeSaveEvent` + 跑 `BeforeSaveCallback`（拿到的是 `AggregateChange`，可改动作）。

**第三步：版本号（prepareVersionForInsert/Update，L657-687）**。insert 前把版本置 0（原始类型置 1）；update 前读出旧版本 +1 写回，并把**旧版本**传给 `DbAction.UpdateRoot` 作为 WHERE 条件——更新 SQL 带 `WHERE id = ? AND version = ?`（由 `SqlGenerator.createUpdateWithVersionSql` 生成，Lazy 缓存见 L97），更新行数为 0 即并发冲突。

**第四步：执行（performSave → executor.executeSave，L591-603）**。

【源码证据】`core/AggregateChangeExecutor.java` 第 57-65 行与第 86-127 行（分发）：

```java
	<T> List<T> executeSave(AggregateChange<T> aggregateChange) {                  // L57

		JdbcAggregateChangeExecutionContext executionContext = new JdbcAggregateChangeExecutionContext(converter,
				accessStrategy);

		aggregateChange.forEachAction(action -> execute(action, executionContext));  // 逐条执行

		return executionContext.populateIdsIfNecessary();                            // L64 回填 id
	}

	private long execute(DbAction<?> action, JdbcAggregateChangeExecutionContext executionContext) {  // L86

		if (action instanceof DbAction.InsertRoot<?> insertRoot) {
			executionContext.executeInsertRoot(insertRoot);
		} else if (action instanceof DbAction.BatchInsertRoot<?> batchInsertRoot) {
			……  // 17 种动作逐一分发（含 4.2 的 DeleteRootByQuery / AcquireLockAllRootByQuery）
		}
	}
```

执行细节在 `JdbcAggregateChangeExecutionContext`（620 行）：`results`（L65，`LinkedHashMap<DbAction, DbActionExecutionResult>`）记录每个动作的结果（含生成的键）；子行执行前用 `getParentKeys`（L199）/`getParentId`（L229）从**父动作的结果**里取 id——依赖序就是 `WritingContext` 记录的 `dependingOn` 关系；最后 `populateIdsIfNecessary`（L281）把生成的根 id 与级联属性一起写回实体（`setIdAndCascadingProperties`，L332）。

**第五步：事件尾声（afterExecute，L555-563）**。断言根 id 非空后发 `AfterSaveEvent` + 跑 `AfterSaveCallback`。

`saveAll` 走同一条链，但把多个聚合装进一个 `BatchingAggregateChange`（`saveInBatch`，L269-301），执行器遇到 `BatchInsertRoot` 等批量动作时逐表合并执行——这是 3.0 引入的批量优化。`insert/update/upsert` 与 save 的唯一区别是**跳过 isNew 判断、直接指定 change 类型**（L227-267）。

## 5.3 delete 流程

【源码证据】`JdbcAggregateTemplate.java` 第 440-489 行（delete 家族）：

```java
	@Override
	public <S> void delete(S aggregateRoot) {                          // L440

		Class<S> domainType = (Class<S>) aggregateRoot.getClass();
		IdentifierAccessor identifierAccessor = context.getRequiredPersistentEntity(domainType)
				.getIdentifierAccessor(aggregateRoot);

		deleteTree(identifierAccessor.getRequiredIdentifier(), aggregateRoot, domainType);
	}

	private <T> void deleteTree(Object id, @Nullable T entity, Class<T> domainType) {   // L580

		MutableAggregateChange<T> change = createDeletingChange(id, entity, domainType);  // DeleteWriter 生成动作
		entity = triggerBeforeDelete(entity, id, change);
		executor.executeDelete(change);
		triggerAfterDelete(entity, id, change);
	}
```

删除事件的特点：`deleteById` 时实体是 null，事件只携带 id（`RelationalEventWithEntity` 的可空实体设计）；4.2 新增的 `deleteAllByQuery(Query, Class)`（L510-518）生成 `DeleteRootByQuery` 动作——一条 `DELETE WHERE ...` 完事，不再逐 id 删。

## 5.4 DataAccessStrategy：数据访问的策略族

**白话**：模板编排好了动作，"动作 → SQL → JDBC 调用"这一步由 `DataAccessStrategy` 接口完成。它有五个实现，按装饰器组合：

```
CascadingDataAccessStrategy（按序委托：读子表时级联）
   └── 你的自定义实现（可选，如 MyBatisDataAccessStrategy）
        └── DataAccessStrategyFactory.create() 的产物：
              ├── 未开单查询装载：DefaultDataAccessStrategy（经典多查询）
              └── 开了（3.2，mappingContext.setSingleQueryLoadingEnabled(true)）：
                    SingleQueryFallbackDataAccessStrategy
                      ├── 能用单查询的读法 → SingleQueryDataAccessStrategy（AggregateReader）
                      └── 其余全部回退 → DefaultDataAccessStrategy
```

【源码证据】`core/convert/DataAccessStrategyFactory.java` 第 91-97 行：

```java
	public DataAccessStrategy create() {

		DefaultDataAccessStrategy defaultDataAccessStrategy = new DefaultDataAccessStrategy(sqlGeneratorSource,
				this.converter.getMappingContext(), this.converter, this.operations, sqlParametersFactory,
				insertStrategyFactory, queryMappingConfiguration);

		if (this.converter.getMappingContext().isSingleQueryLoadingEnabled()) {
			return new SingleQueryFallbackDataAccessStrategy(sqlGeneratorSource, converter, operations,
					defaultDataAccessStrategy);
		}

		return defaultDataAccessStrategy;
	}
```

- **DefaultDataAccessStrategy**（552 行）：每种 DbAction 一个方法；读方法按"根一查、每个多值路径一查"分层执行，靠 `RelationResolver` 在行映射时**惰性**拉取子表（还是查询次数多，但都在同一次调用内完成，没有会话所以无懒加载问题——它是一次性预取）。
- **SingleQueryDataAccessStrategy**（3.2）：一条带 LEFT JOIN 的大 SQL 拉回整个聚合的行集，`RowDocumentResultSetExtractor` 折叠成 `RowDocument`，`AggregateReader` 转实体。前提是方言声明 `supportsSingleQueryLoading()` 且结果集列数可控（列爆炸风险：宽表 × 多层 JOIN）。
- **InsertStrategy 族**（`InsertStrategyFactory`）：单条 insert / 批量 insert / 需要回取生成键的 insert，按方言与场景选择；`IdGeneratingBatchInsertStrategy` 用 `GeneratedKeyHolder` 收键（L66）。
- **MyBatisDataAccessStrategy**：把数据访问整体替换为 MyBatis 映射语句——SqlGenerator 生成的 SQL 可以先 dump 出来当 MyBatis XML 的起点（官方 "Dumping the SQL" 文档的来源）。

## 5.5 SqlGenerator：为聚合生成 SQL（1548 行的功臣）

**白话**：每个实体类型一个 `SqlGenerator`（由 `SqlGeneratorSource` 缓存），内部用 `Lazy` 把每种固定语句的 SQL 只生成一次；可变条件（派生查询、分页）走 `createSelectBuilder` 动态构建。

【源码证据】`core/convert/SqlGenerator.java` 第 89-102 行（Lazy 语句缓存）：

```java
	private final Lazy<String> findOneSql = Lazy.of(this::createFindOneSql);
	private final Lazy<String> findAllSql = Lazy.of(this::createFindAllSql);
	private final Lazy<String> findAllInListSql = Lazy.of(this::createFindAllInListSql);
	private final Lazy<String> existsSql = Lazy.of(this::createExistsSql);
	private final Lazy<String> countSql = Lazy.of(this::createCountSql);
	private final Lazy<String> updateSql = Lazy.of(this::createUpdateSql);
	private final Lazy<String> updateWithVersionSql = Lazy.of(this::createUpdateWithVersionSql);
	private final Lazy<String> deleteByIdSql = Lazy.of(this::createDeleteByIdSql);
	……
```

SELECT 语句的组装核心是"**列收集 + JOIN 收集**"双循环（`getProjection`，L792 起）：沿 `mappingContext.findPersistentPropertyPaths` 展开所有 `AggregatePath`，`includeColumnAndJoin`（L852 起）逐路径决定"加列"还是"加 JOIN"：

【源码证据】`SqlGenerator.java` 第 937-1000 行（列与 JOIN 的判定）：

```java
	@Nullable
	Column getColumn(AggregatePath path) {

		// an embedded itself doesn't give a column, its members will though.
		// if there is a collection or map on the path it won't get selected at all,
		// but it will get loaded with a separate select
		if (path.isEmbedded() || path.getParentPath().isMultiValued()) {
			return null;                                   // L911 多值路径不出现在主查询
		}

		if (path.isEntity()) {
			// 一对一实体：无 id 的用回指列当合成 id 区分 null
			if (path.isQualified() || path.isCollectionLike() || path.hasIdProperty()) {
				return null;
			}
			return sqlContext.getAnyReverseColumn(path);   // L920
		}

		return sqlContext.getColumn(path);
	}

	@Nullable
	Join getJoin(AggregatePath path) {                     // L977

		// 非实体/嵌入/多值路径不加 JOIN
		if (!path.isEntity() || path.isEmbedded() || path.isMultiValued()) {
			return null;
		}

		Table currentTable = sqlContext.getTable(path);
		AggregatePath.ColumnInfos backRefColumnInfos = path.getTableInfo().backReferenceColumnInfos();
		AggregatePath idDefiningParentPath = path.getIdDefiningParentPath();
		……
		Condition joinCondition = backRefColumnInfos.reduce(Conditions.unrestricted(), (aggregatePath, columnInfo) -> {
			AggregatePath.ColumnInfo idColumnInfo = idColumnInfos.get(aggregatePath);
			return currentTable.column(columnInfo.name()).isEqualTo(parentTable.column(idColumnInfo.name()));
		}, Condition::and);

		return new Join(currentTable, joinCondition);      // 回指列 = 父表 id 列
	}
```

渲染时统一 `leftOuterJoin`（`addJoins`，L788-790）。**这就是"一对一 JOIN 平铺、集合子查询"策略的源码出处**：`findOne` 一条 SQL 拿到根 + 所有单值引用；集合成员由 `getFindAllByProperty`（L188-197，3.0 起）按"回指列 = 父 id"另查，List/Map 的键列用于 ORDER BY（L229-233）。

INSERT 的生成还有两处讲究：① 复合主键/无主键表的 qualifier 列回填；② UPSERT（4.1，`createUpsertSql`，L436）按方言渲染 `MERGE` 或 `ON CONFLICT`。DELETE 则区分"删根（按 id）"与"删子层（按根 id 子查询 `IN`，见 L1004 起的注释：leaf 之下的层必须用子查询从根 id 导航）"。

## 5.6 主键生成与审计回调

主键三来源的落地：

1. **数据库自增/标识列**：`IdValueSource.GENERATED` → insert 后用 `returnGeneratedValues`（方言渲染列名）+ `GeneratedKeyHolder` 回取，执行上下文回填。
2. **序列（3.5）**：`@Sequence(name=…)` 声明 → `IdValueSource.forInstance` 判为 PROVIDED → **BeforeSave 回调** `IdGeneratingEntityCallback` 先发 `nextval` 填进实体再保存：

【源码证据】`core/convert/IdGeneratingEntityCallback.java` 第 28-73 行：

```java
/**
 * Callback for generating identifier values through a database sequence.
 * @since 3.5
 */
public class IdGeneratingEntityCallback implements BeforeSaveCallback<Object> {   // L36

	@Override
	public Object onBeforeSave(Object aggregate, MutableAggregateChange<Object> aggregateChange) {  // L50
		……
		if (delegate.hasValue(idProperty, accessor)) {
			return aggregate;                       // 已有值就不生成
		}
		delegate.generateSequenceValue(idProperty, accessor);   // SELECT nextval('…') 填值
		return accessor.getBean();
	}
}
```

3. **UUID 等应用侧生成**：应用自己填或用实体回调填。

审计（`@EnableJdbcAuditing`）注册 `RelationalAuditingCallback`（core 模块 `auditing/RelationalAuditingCallback.java`）挂进同一套回调链；回调体系的公共骨架（`EntityCallbacks`、`BeforeSaveCallback` 等 20+ 回调接口）见《Spring Data Commons 深度源码解析》8.1 节。

## 5.7 本章小结

- `JdbcAggregateTemplate.save` 五步曲：verifyId → isNew 分流 + 版本准备 → BeforeConvert 事件/回调 → 写入器生成 `AggregateChange` → BeforeSave → 执行器按依赖序执行 17 种 DbAction 并回填 id → AfterSave。
- `DataAccessStrategy` 策略族：经典多查询（默认）、单查询装载（3.2，显式开启 + 方言声明支持）、MyBatis 替换、级联装饰器——全部通过 `AbstractJdbcConfiguration` 的 `@Bean dataAccessStrategyBean` 一处装配（`repository/config/AbstractJdbcConfiguration.java` L184）。
- `SqlGenerator` 的读策略一句话："单值引用 LEFT JOIN 平铺，多值集合单独查询"；固定语句 Lazy 缓存，可变条件用 builder 动态拼。
- 主键三来源（自增/序列/应用侧）分别落在 GeneratedKeyHolder、BeforeSave 序列回调、用户回调上；`@Version` 乐观锁通过 UpdateRoot.previousVersion 进入 WHERE 子句。


---

# 六、spring-data-jdbc：Repository 与查询方法（重点 B）

> 本章对应源码：`spring-data-jdbc/src/main/java/org/springframework/data/jdbc/repository/`。

## 6.1 先说白话：`extends CrudRepository` 的空接口为什么能工作？

答案在 commons（《Spring Data Commons 深度源码解析》2.5-2.6 节）：`@EnableJdbcRepositories` 触发 `JdbcRepositoriesRegistrar` 扫描接口 → 为每个接口注册 `JdbcRepositoryFactoryBean`（FactoryBean 模式）→ `JdbcRepositoryFactory extends RepositoryFactorySupport` 装配出代理。本项目只负责回答两个问题：**基类是谁**（`SimpleJdbcRepository`）和**查询方法谁解析**（`JdbcQueryLookupStrategy`）。

【源码证据】`repository/support/JdbcRepositoryFactory.java` 第 158-182 行：

```java
	@Override
	protected Object getTargetRepository(RepositoryInformation repositoryInformation) {   // L158

		RelationalPersistentEntity<?> persistentEntity = getMappingContext()
				.getRequiredPersistentEntity(repositoryInformation.getDomainType());

		return getTargetRepositoryViaReflection(repositoryInformation, operations, persistentEntity,
				operations.getConverter());
	}

	@Override
	protected Class<?> getRepositoryBaseClass(RepositoryMetadata repositoryMetadata) {   // L168
		return SimpleJdbcRepository.class;
	}

	@Override
	protected Optional<QueryLookupStrategy> getQueryLookupStrategy(QueryLookupStrategy.@Nullable Key key,
			ValueExpressionDelegate valueExpressionDelegate) {                           // L173

		RowMapperFactory rowMapperFactory = beanFactory != null
				? new BeanFactoryAwareRowMapperFactory(beanFactory, operations, queryMappingConfiguration)
				: new DefaultRowMapperFactory(operations, queryMappingConfiguration);

		return Optional.of(JdbcQueryLookupStrategy.create(key, operations, rowMapperFactory,
				new CachingValueExpressionDelegate(valueExpressionDelegate)));
	}
```

注意 4.0 的重构：工厂只依赖一个 `JdbcAggregateOperations`（构造器 L72，`@since 4.0`）——旧六参构造器（自己拼 template/converter/dialect/publisher）已废弃；`ValueExpressionDelegate` 来自 commons 4.0（SpEL 与 Value Expression 的统一门面，带 `CachingValueExpressionDelegate` 包装）。

配置骨架 `AbstractJdbcConfiguration`（`repository/config/`，标注 "Beans that must be registered for Spring Data JDBC to work"，L51）用一组 `@Bean` 固定了全部装配点：`NamedParameterJdbcOperations` → `JdbcDialect`（L198，按上文 SPI 探测）→ `JdbcMappingContext`（L109）→ `IdGeneratingEntityCallback`（L123，`idGeneratingBeforeSaveCallback`）→ `JdbcConverter`（L137）→ `JdbcAggregateTemplate`（L171）→ `DataAccessStrategy`（L184）→ `JdbcRepositoryFactoryBean`。想替换任何一层，重写对应 `@Bean` 即可。

## 6.2 SimpleJdbcRepository：薄壳基类

【源码证据】`repository/support/SimpleJdbcRepository.java` 第 71-152 行（节选）：

```java
	@Transactional
	@Override
	public <S extends T> S save(S instance) {          // L71
		return entityOperations.save(instance);
	}

	@Transactional
	@Override
	public <S extends T> List<S> saveAll(Iterable<S> entities) { … }   // L77

	@Override
	public Optional<T> findById(ID id) {               // L84 读方法不加 @Transactional
		return Optional.ofNullable(entityOperations.findById(id, entity.getType()));
	}

	@Override
	public Page<T> findAll(Pageable pageable) {        // L144
		Query query = Query.query(CriteriaDefinition.empty()).with(pageable);
		List<T> content = entityOperations.findAll(query, entity.getType());
		return PageableExecutionUtils.getPage(content, pageable, () -> entityOperations.count(entity.getType()));
	}
```

写方法逐个标 `@Transactional`（读方法不标——按 Spring Data 家族惯例，读事务交由应用层或传播中的事务）；QBE（Query by Example）通过 `RelationalExampleMapper` 把 commons 的 `Example` 翻译成 `Query`（L155-206）；流式查询 `findBy(…, FetchableFluentQuery)` 由 `FetchableFluentQueryByExample` 承接（L209-218）。

## 6.3 查询推导：PartTreeJdbcQuery → JdbcQueryCreator

**白话**：`List<Order> findByCustomerNameAndTotalGreaterThan(String name, BigDecimal total)` 的旅程：commons 的 `PartTree` 把方法名解析成"与/或 + 属性路径 + 操作符"的树（解析器见《Spring Data Commons 深度源码解析》3.4 节）→ 本项目共享基类 `RelationalQueryCreator` 把每个 `Part` 翻译成 `Criteria`（`repository/query/RelationalQueryCreator.java` L37：`extends AbstractQueryCreator<T, Criteria>`）→ `JdbcQueryCreator.complete` 把 Criteria + 排序 + 分页 + 锁交给 `StatementFactory` 拼成 SQL AST 并渲染。

【源码证据】`repository/query/JdbcQueryCreator.java` 第 158-193 行（推导的边界校验——教程很少讲但很关键）：

```java
	static void validate(PartTree tree, Parameters<?, ?> parameters, RelationalMappingContext context,
			JdbcConverter converter) {

		RelationalQueryCreator.validate(tree, parameters);

		for (PartTree.OrPart parts : tree) {
			for (Part part : parts) {

				PersistentPropertyPath<? extends RelationalPersistentProperty> propertyPath = context
						.getPersistentPropertyPath(part.getProperty());
				AggregatePath path = context.getAggregatePath(propertyPath);

				path.forEach(pathElement -> validateProperty(pathElement, converter));
			}
		}
	}

	private static void validateProperty(AggregatePath path, JdbcConverter converter) {   // L175

		if (path.isRoot()) {
			return;
		}

		if (path.getLength() > 2 && !path.getParentPath().isEmbedded()) {
			throw new IllegalStateException(String.format("Cannot query by nested property: %s", path.toDotPath()));
		}

		if (path.isMultiValued() || path.isMap()) {
			throw new IllegalStateException(
					String.format("Cannot query by multi-valued property: %s", path.getRequiredLeafProperty().getName()));
		}

		if (path.isEntity() && !path.isEmbedded() && !hasCustomWriteTarget(path, converter)) {
			throw new IllegalStateException(String.format("Cannot query by nested entity: %s", path.toDotPath()));
		}
	}
```

三条红线：**不能跨两层嵌套**（`item.shipping.city` 不行）、**不能按集合属性查询**（`findByItemsProductName` 不行——集合在另一张表）、**不能按关联实体属性查询**（除非该属性有自定义转换器）。红线是聚合模型的必然：**派生查询只查聚合根本表**，跨表需求请手写 `@Query` 或从聚合根 id 开始二段查询。

【源码证据】`JdbcQueryCreator.java` 第 212-254 行（complete 与投影选择）：

```java
	@Override
	protected ParametrizedQuery complete(@Nullable Criteria criteria, Sort sort) {   // L212

		RelationalPersistentEntity<?> entity = entityMetadata.getTableEntity();
		MapSqlParameterSource parameterSource = new MapSqlParameterSource();

		StatementFactory.SelectionBuilder selection = getSelection(entity);

		selection.page(accessor.getPageable()).filter(criteria).orderBy(sort);

		if (this.lockMode.isPresent()) {
			selection.lock(this.lockMode.get().value());        // @Lock(LockMode.PESSIMISTIC_READ) → FOR UPDATE
		}

		String sql = selection.build(parameterSource);
		return new ParametrizedQuery(sql, parameterSource, criteria != null ? criteria : Criteria.empty());
	}
```

`StatementFactory`（4.0 新增，`repository/query/StatementFactory.java`）把"exists/count/slice/select + 投影 + 限制 + 分页 + 排序 + 锁"统一成流式构建器，内部最终落到 5.5 节的 `SqlGenerator`/SQL AST。`ParametrizedQuery` 是"SQL 字符串 + 参数源 + Criteria"的三元组（`repository/query/ParametrizedQuery.java`）。`@Lock` 注解（core 模块 `repository/Lock.java`）支持 `PESSIMISTIC_READ/WRITE`，渲染由 `Dialect.lock()` 完成。

## 6.4 @Query 与 StringBasedJdbcQuery：手写 SQL 的安全通道

【源码证据】`repository/query/StringBasedJdbcQuery.java` 第 70-145 行（关键设施）：

```java
public class StringBasedJdbcQuery extends AbstractJdbcQuery {   // L70

	private final ValueExpressionQueryRewriter.ParsedQuery parsedQuery;   // L76
	……
	// L144-145：把查询里的 SpEL / Value Expression 挖出来，换成合成参数 __$synthetic$__N
	ValueExpressionQueryRewriter rewriter = ValueExpressionQueryRewriter.of(delegate,
			(counter, expression) -> String.format("__$synthetic$__%d", counter + 1), String::concat);
```

能力清单：① `:name` 命名参数（`JdbcValueBindUtil` 绑定集合展开）；② SpEL 与 Value Expression——`@Query("SELECT * FROM ORDERS WHERE customer = :customer AND YEAR(created) >= :#{#age}")` 或表名表达式，被改写成合成参数后求值绑定；③ `RowMapperFactory`——`@Query(rowMapperClass=…)` 或从容器按类型找 `RowMapper`/`ResultSetExtractor` Bean；④ `@Modifying` 标注非查询方法（`repository/query/Modifying.java`，运行期删除/更新语句）；⑤ named query（`NamedQueries`，properties 文件里的 `Order.findXxx`）。

查询策略的三态选择（`JdbcQueryLookupStrategy.create`，L193-216）与 JPA 同款：`CREATE`（只方法名）、`USE_DECLARED_QUERY`（只注解/named）、`CREATE_IF_NOT_FOUND`（默认：先找声明，没有再创建——`CreateIfNotFoundQueryLookupStrategy` 用 try-catch 回退，L166-175）。

## 6.5 生命周期事件与回调：两套钩子的分工

事件（6 个，`core/mapping/event/`）是**广播**：`BeforeConvertEvent`/`BeforeSaveEvent`/`AfterSaveEvent`/`AfterConvertEvent`/`BeforeDeleteEvent`/`AfterDeleteEvent`，全部继承 `AbstractRelationalEvent`；监听方式是标准 `ApplicationListener`/`@EventListener`，基类 `AbstractRelationalEventListener`（L30-67）按事件类型分发到六个可覆盖的 `onXxx` 空方法。回调（`EntityCallbacks`）是**链式改写**：`BeforeConvertCallback`/`BeforeSaveCallback`/`AfterSaveCallback`/`AfterConvertCallback`/`BeforeDeleteCallback`/`AfterDeleteCallback`，返回值替换原实体。5.2 节的流程图里两者成对出现——**同一个时机，先广播事件（可旁路观察），再跑回调链（可修改实体）**。

【源码证据】`core/EntityLifecycleEventDelegate.java` 第 44-56 行（事件开关）：

```java
	public void setEventsEnabled(boolean eventsEnabled) {     // L44

	public void publishEvent(Supplier<?> eventSupplier) {     // L53
		if (eventsEnabled) {
			publisher.publishEvent(eventSupplier.get());
		}
	}
```

`JdbcAggregateTemplate.setEntityLifecycleEventsEnabled(false)`（3.0 引入，L183-185）可整体关掉事件发布——高频批量写时事件走容器广播的开销可省；回调不受影响。

## 6.6 schema 工具、AOT 与 MyBatis 速览

- **schema 生成（`core/mapping/schema/`）**：`Tables.from(mappingContext)` 把全部实体映射抽成 `Table`/`Column`/`ForeignKey` 模型（`Tables.java` L44 起，`record Tables(List<Table> tables)`）；与 `DatabaseMetaData` 读出的现有结构做 diff（`SchemaDiff`/`TableDiff`），输出 ALTER 脚本或 Liquibase 变更集（`LiquibaseChangeSetWriter`）。注意它不在启动时自动建表——需要时自己调用或写测试扩展。
- **AOT（`repository/aot/`）**：`JdbcRepositoryContributor` 在 GraalVM 原生镜像构建期把派生查询**编译成常量字符串**（`AotQueries`/`DerivedAotQuery`/`StringAotQuery`），运行期 `AotRepositoryFragmentSupport` 直接取用，绕开运行期反射构建查询。
- **MyBatis（`mybatis/`）**：`MyBatisDataAccessStrategy implements DataAccessStrategy`（含 4 个类）——MyBatis 爱好者可以全接管数据访问层而保留 Repository 代理与聚合映射；`MyBatisContext` 把聚合路径/限定值传给映射语句。

## 6.7 本章小结

- Repository 装配 = commons 代理工厂 + 两个本项目答案：`SimpleJdbcRepository` 基类与 `JdbcQueryLookupStrategy` 查询策略（三态选择，默认声明优先）。
- 派生查询只作用于聚合根本表，三条边界红线在启动期校验（嵌套超两层/多值属性/关联实体属性）。
- `@Query` 提供命名参数 + SpEL/Value Expression + 自定义 RowMapper + `@Modifying`；`@Lock` 提供悲观锁；`StatementFactory`（4.0）统一拼装。
- 事件与回调成对出现：事件是广播（可关），回调是改写链；schema 工具与 AOT 是两个容易忽略的成熟度信号。


---

# 七、spring-data-r2dbc：响应式数据访问（重点 C）

> 本章对应源码：`spring-data-r2dbc/src/main/java/org/springframework/data/r2dbc/`。先立一个最重要的结论：**R2DBC 模块是"实体中心"的，没有聚合级联保存**——`save()` 只写实体自己那一行，`@MappedCollection` 集合在 R2DBC 侧不参与持久化。聚合映射元模型虽然共享，但级联写入是 spring-data-jdbc 独有的能力。

## 7.1 模块定位与架构差异

| 维度 | spring-data-jdbc | spring-data-r2dbc |
|---|---|---|
| 持久化单元 | 聚合（根 + 级联子行） | 单实体（一行） |
| 保存模型 | `AggregateChange`/`DbAction` + 执行器 | `OutboundRow`（列 → Parameter）直插/直更 |
| SQL 生成 | `SqlGenerator`（聚合感知） | `StatementMapper`（语句规格 → `SqlRenderer`） |
| 执行通道 | `NamedParameterJdbcOperations` | `DatabaseClient`（spring-r2dbc） |
| 事务 | `@Transactional`（阻塞 ThreadLocal 绑定） | `@Transactional`（反应式，`ReactiveTransactionManager` + Reactor Context） |
| 主键回取 | `GeneratedKeyHolder` | `statement.returnGeneratedValues(...)` + `populateIdIfNecessary` |
| Repository 基类 | `CrudRepository` 族 | `ReactiveCrudRepository` 族 + Kotlin 协程扩展 |

R2DBC 侧的核心转换抽象是 `R2dbcConverter extends EntityReader<Object, Row>, EntityWriter<Object, OutboundRow>, RelationalConverter`（`convert/R2dbcConverter.java` L41）——**读用 `Row`，写用 `OutboundRow`**，根本没有"子动作"概念。写侧目标类型 `OutboundRow`（`mapping/OutboundRow.java`）就是"列 → `Parameter`"的映射表。

## 7.2 R2dbcEntityTemplate：insert 流水线（本章核心）

【源码证据】`core/R2dbcEntityTemplate.java` 第 506-588 行：

```java
	@Override
	public <T> Mono<T> insert(T entity) throws DataAccessException {        // L507

		return doInsert(entity, getRequiredEntity(entity).getQualifiedTableName());
	}

	<T> Mono<T> doInsert(T entity, SqlIdentifier tableName) {               // L514

		RelationalPersistentEntity<T> persistentEntity = getRequiredEntity(entity);

		return maybeCallBeforeConvert(entity, tableName).flatMap(onBeforeConvert -> {   // ① 回调链

			T initializedEntity = setVersionIfNecessary(persistentEntity, onBeforeConvert);   // ② 版本初始化

			OutboundRow outboundRow = dataAccessStrategy.getOutboundRow(initializedEntity);   // ③ 实体 → 出站行

			potentiallyRemoveId(persistentEntity, outboundRow);           // ④ id=null 或 0 时从行里删掉（L531-544）

			return maybeCallBeforeSave(initializedEntity, outboundRow, tableName)   // ⑤ BeforeSave（可改行！）
					.flatMap(entityToSave -> doInsert(entityToSave, tableName, outboundRow));
		});
	}

	private <T> Mono<T> doInsert(T entity, SqlIdentifier tableName, OutboundRow outboundRow) {  // L559

		StatementMapper mapper = dataAccessStrategy.getStatementMapper();
		StatementMapper.InsertSpec insert = mapper.createInsert(tableName);

		for (SqlIdentifier column : outboundRow.keySet()) {
			Parameter settableValue = outboundRow.get(column);
			if (settableValue.hasValue()) {
				insert = insert.withColumn(column, settableValue);
			}
		}

		PreparedOperation<?> operation = mapper.getMappedObject(insert);   // ⑥ 渲染成 SQL + 绑定

		List<SqlIdentifier> identifierColumns = dataAccessStrategy.getIdentifierColumns(entity.getClass());

		return this.databaseClient.sql(operation)                          // ⑦ DatabaseClient 执行
				.filter(statement -> {
					statement = statementFilterFunction.apply(statement);
					if (identifierColumns.isEmpty()) {
						return statement.returnGeneratedValues();          // ⑧ 回取生成键
					}
					return statement.returnGeneratedValues(
							dataAccessStrategy.renderForGeneratedValues(identifierColumns.get(0)));
				}).map(this.dataAccessStrategy.getConverter().populateIdIfNecessary(entity))  // ⑨ 回填 id
				.all()                                                     // ⑩ 处理多行返回（某些驱动把键当结果集）
				.last(entity).flatMap(saved -> maybeCallAfterSave(saved, outboundRow, tableName));  // ⑪ AfterSave
	}
```

这条流水线与 5.2 节的 JDBC save 五步曲逐点对应，但有两处 R2DBC 特色的精巧：**BeforeSave 回调拿到的是可变的 `OutboundRow`**（不是聚合 change）——列级修改直通 SQL；**id 回填是响应式的**（`populateIdIfNecessary` 返回映射函数，键从 `returnGeneratedValues` 的结果流里来）。

update（L608 起）在出站行外还要构造 `Update` + `Criteria`（id 等值 + **version 等值**），并在受影响行数为 0 时报乐观锁冲突：

【源码证据】`R2dbcEntityTemplate.java` 第 680-699 行（update 尾段）：

```java
		Update update = Update.from((Map) outboundRow);
		StatementMapper.UpdateSpec updateSpec = mapper.createUpdate(tableName, update).withCriteria(criteria);
		PreparedOperation<?> operation = mapper.getMappedObject(updateSpec);
		……
						.updateRows()                          // 受影响行数
						.flatMap(updated -> {
							if (updated == 0) {
								sink.error(OptimisticLockingUtils.updateFailed(entity, version, persistentEntity));  // L697
							}
							……
						}).then(maybeCallAfterSave(entity, outboundRow, tableName));
```

## 7.3 StatementMapper 与命名参数展开

`StatementMapper`（`core/StatementMapper.java`，811 行）是"语句规格"接口：`createSelect/createInsert/createUpdate/createDelete/createUpsert`（`UpsertSpec` L689，4.1）产出 `SelectSpec/InsertSpec/UpdateSpec/DeleteSpec`（L256/L500/L571/L633），`getMappedObject(spec)` 完成翻译——内部同样调用 `SqlRenderer` + `RenderContextFactory(dialect)`，第三章的方言体系在两侧复用。`TypedStatementMapper<T>`（L126）携带实体类型做列名映射。

手写 SQL 场景的命名参数由 `NamedParameterExpander`（`core/NamedParameterExpander.java` L43）处理：`:name` → 原生占位符 + 绑定表，List 参数按元素个数展开占位符（`NamedParameterUtils.parseSqlStatement`，L95）——与 spring-jdbc 的同名工具是姊妹实现。

## 7.4 Fluent API：五种操作的流式 DSL

`R2dbcEntityTemplate` 实现 `ReactiveSelectOperation`/`ReactiveInsertOperation`/`ReactiveUpdateOperation`/`ReactiveDeleteOperation`/`ReactiveUpsertOperation`（4.1 新增 upsert，`core/ReactiveUpsertOperation.java`）：

```java
template.select(Person.class)
    .matching(query(where("name").is("Jones")))
    .as(Employee.class)          // 投影
    .all();                      // Flux<Employee>
```

每个 Operation 由 `core/ReactiveXxxOperationSupport.java` 实现链式收窄（`matchWith/where/as/project/…` 最终调 template 的 doXxx）。这套 Fluent API 与 `Query`/`Criteria` 一起，构成比 Repository 更细粒度的编程式访问面。

## 7.5 响应式 Repository 与事务

【源码证据】`repository/support/SimpleR2dbcRepository.java` 第 68-150 行：

```java
public class SimpleR2dbcRepository<T, ID> implements R2dbcRepository<T, ID> {   // L68

	@Override
	@Transactional                                                              // L122 反应式事务注解
	public <S extends T> Mono<S> save(S objectToSave) {                         // L123

		if (this.entity.isNew(objectToSave)) {
			return this.entityOperations.insert(objectToSave);
		}

		return this.entityOperations.update(objectToSave);
	}

	@Override
	@Transactional
	public <S extends T> Flux<S> saveAll(Publisher<S> objectsToSave) {          // L145

		return Flux.from(objectsToSave).concatMap(this::save);                  // concatMap：顺序逐个保存
	}
```

`@Transactional` 在响应式栈里由 spring-tx 的反应式事务拦截器处理（`ReactiveTransactionManager` + Reactor Context 传递，见《Spring Framework 深度源码解析》6.5.4 节的 `ReactiveTransactionSupport` 分支）。`R2dbcRepositoryFactory`（`repository/support/R2dbcRepositoryFactory.java` L56）继承 `ReactiveRepositoryFactorySupport`，基类 `SimpleR2dbcRepository`（L100），查询策略 `R2dbcQueryLookupStrategy`（L113）→ `PartTreeR2dbcQuery`（124 行）——与 JDBC 版同构，只是 `complete` 产出 `PreparedOperation` 而非 `ParametrizedQuery`。

配置引导 `AbstractR2dbcConfiguration`（`config/`，5 个类）要求用户提供一个 `ConnectionFactory` `@Bean`（L80-83），自动创建 `DatabaseClient`、`R2dbcDialect`（按 `ConnectionFactoryMetadata` 探测）、转换器与模板。

## 7.6 本章小结

- **最大差异：实体中心 vs 聚合级联**。R2DBC 保存的最小单位是实体一行；`OutboundRow` 是写侧中间表示，没有 DbAction/依赖序。
- insert 流水线 11 步：BeforeConvert 回调 → 版本初始化 → 实体转 OutboundRow → 删空 id → BeforeSave（改行）→ InsertSpec → 渲染 → DatabaseClient 执行 → 回取键 → 回填 id → AfterSave。
- update 带版本等值条件，0 行受影响抛 `OptimisticLockingFailureException`（`OptimisticLockingUtils.updateFailed`）。
- `StatementMapper` 复用共享内核的 SQL AST 与方言；Fluent API 五操作覆盖编程式访问；事务是纯响应式的 Reactor Context 方案；Kotlin 协程扩展（`kotlinx-coroutines`）让 Repository 方法可直接挂起。


---

# 八、贯通视图：一个聚合的生与死

## 8.1 时间线一：启动（从注解到代理就绪）

```
@SpringBootApplication（含 @EnableJdbcRepositories 或自动配置）
  └─ JdbcRepositoriesRegistrar → 扫描 Repository 接口
       └─ 注册 JdbcRepositoryFactoryBean(接口)
            └─ AbstractJdbcConfiguration 依次装配：
                 NamedParameterJdbcOperations ← DataSource
                 JdbcDialect ← DialectResolver（连接一次取 DatabaseProductName）
                 JdbcMappingContext（NamingStrategy 缓存包装）
                 JdbcConverter（MappingRelationalConverter + CustomConversions + 方言）
                 DataAccessStrategy ← DataAccessStrategyFactory.create()
                 IdGeneratingEntityCallback（BeforeSave 回调链）
                 JdbcAggregateTemplate（模板：事件委托 + 执行器）
              → 首次调用时 SqlGenerator 按类型缓存、SQL Lazy 生成
```

## 8.2 时间线二：`orderRepository.save(order)`（聚合带 items 集合）

```
① verifyIdProperty：聚合根必须有 @Id
② isNew(order)?（id 为 null/0 → 是）
   ├─ 是 → prepareVersionForInsert：version=0；→ RelationalEntityInsertWriter
   └─ 否 → prepareVersionForUpdate：读旧版本、写回 +1；→ RelationalEntityUpdateWriter
③ 发布 BeforeConvertEvent + BeforeConvertCallback（此刻改实体最安全）
④ WritingContext 生成动作：
   ├─ InsertRoot(order)（或 UpdateRoot(order, 旧版本)）
   ├─ [update 时] Delete(rootId, items 路径) —— 子表先删旧
   └─ Insert(item, path=items, dependingOn=根动作, qualifiers=[序号], idValueSource=…)
      （每个 item 一条，dependingOn 指向根动作 → 决定执行顺序）
⑤ 发布 BeforeSaveEvent + BeforeSaveCallback（含序列 id 填充回调）
⑥ AggregateChangeExecutor.forEachAction 逐条执行：
   ├─ executeInsertRoot：SqlGenerator.getInsert → INSERT INTO orders …
   │    → GeneratedKeyHolder 收 id → results[根动作] = {id=…}
   └─ executeInsert(item)×N：从 results 取父 id 填回指列 → INSERT INTO order_item …
⑦ populateIdsIfNecessary：根 id 回填 order；级联限定值回填
⑧ 发布 AfterSaveEvent + AfterSaveCallback
（全程在一个数据库事务里：SimpleJdbcRepository.save 的 @Transactional 或外层事务）
```

## 8.3 时间线三：`orderRepository.findById(id)`

```
① DataAccessStrategy.findById：
   ├─ 单查询装载开启且方言支持：AggregateReader 一条大 SQL
   │    SELECT 根列 + 单值引用列（LEFT JOIN 回指列=父id）
   │    → ResultSet → RowDocumentResultSetExtractor 折叠 RowDocument
   │    → MappingRelationalConverter.read(Order, RowDocument)
   └─ 经典模式：DefaultDataAccessStrategy
        SELECT * FROM orders WHERE id=?          ← SqlGenerator.findOneSql（Lazy 缓存）
        → EntityRowMapper 映射根
        对每个多值路径：SELECT * FROM order_item WHERE order=?  ← getFindAllByProperty
        → RelationResolver 预取后挂到聚合
② triggerAfterConvert：AfterConvertEvent + AfterConvertCallback
③ 返回完整聚合（无懒加载代理——集合已是普通 List）
```

## 8.4 从源码中提炼的四个设计模式视角

1. **中间表示（IR）模式**：`AggregateChange`/`DbAction` 之于聚合持久化，如同字节码之于编译器——写侧与执行侧解耦，阻塞/非阻塞两个执行引擎消费同一份 IR。
2. **策略 + 装饰器组合**：`DataAccessStrategy` 一接口五实现，读路径（单查询/多查询/回退）、写路径（批量/单条）、MyBatis 替换全部正交组合。
3. **双栈同核**：JDBC 与 R2DBC 的差异被压缩到"执行通道 + 中间表示的形状"两点；元模型、方言、SQL AST、事件语义全共享——这是"适配而非重造"哲学的教科书案例。
4. **模板 + 管道**：`JdbcAggregateTemplate`/`R2dbcEntityTemplate` 是流程模板，事件与回调是管道上的两排阀门（一个只读旁路、一个可改写载荷）。


---

# 九、附录

## 9.1 关键接口速查表

| 接口/类 | 包 | 一句话职责 |
|---|---|---|
| `RelationalMappingContext` | core.mapping | 元模型入口；聚合路径缓存；单查询开关 |
| `RelationalPersistentEntity/Property` | core.mapping | 表/列元数据；回指列；键列；序列 |
| `NamingStrategy` | core.mapping | 7 个默认方法；默认 SNAKE_CASE |
| `AggregatePath` | core.mapping | 根到叶路径 → 表/列信息（3.2） |
| `SQL` / `Segment` | core.sql | 语句 AST 入口与节点基类型 |
| `SqlRenderer` | core.sql.render | AST → SQL 字符串 |
| `Dialect` | core.dialect | 15 个口子吸收数据库差异 |
| `RowDocument` | core.domain | 行的文档化表示（大小写不敏感 Map） |
| `RelationalConverter` | core.conversion | 聚合 ↔ RowDocument 双向转换 |
| `AggregateChange`/`DbAction` | core.conversion | 持久化中间表示（17 种动作） |
| `IdValueSource` | core.conversion | PROVIDED/GENERATED/NONE |
| `RelationalEntityInsert/Update/Upsert/DeleteWriter` | core.conversion | 聚合 → 动作列表 |
| `RelationalEntityVersionUtils` | core.conversion | 版本号读写 |
| `JdbcAggregateOperations/Template` | jdbc.core | 聚合 CRUD 编排 |
| `AggregateChangeExecutor` | jdbc.core | 动作执行 + id 回填 |
| `DataAccessStrategy` | jdbc.core.convert | "动作/查询 → SQL → JDBC"策略 |
| `SqlGenerator` | jdbc.core.convert | 每类型 SQL 生成（Lazy 缓存） |
| `QueryMapper` | jdbc.core.convert | 属性 → 列（条件/更新绑定） |
| `RelationalQueryCreator` | relational.repository.query | PartTree → Criteria 共享基类 |
| `JdbcQueryCreator` | jdbc.repository.query | Criteria + Sort + Lock → SQL |
| `StringBasedJdbcQuery` | jdbc.repository.query | @Query 执行器 |
| `R2dbcEntityOperations/Template` | r2dbc.core | 实体 CRUD + Fluent API |
| `StatementMapper` | r2dbc.core | 语句规格 → PreparedOperation |
| `ReactiveDataAccessStrategy` | r2dbc.core | R2DBC 侧数据访问门面 |
| `OutboundRow` | r2dbc.mapping | 写侧"列 → Parameter" |
| `Dialect`(r2dbc) | r2dbc.dialect | R2DBC 方言 + BindMarkers |

## 9.2 初学者学习路线（动手向）

1. 用 Spring Initializr 生成带 **Spring Data JDBC** + H2 的工程，写一个两级聚合（`PurchaseOrder` + `List<Item>`），跑通 CRUD，打开 `logging.level.org.springframework.jdbc.core=TRACE` 观察实际 SQL 与顺序。
2. 给聚合根加 `@Version`，开两个线程并发 update，复现 `OptimisticLockingFailureException`。
3. 自定义 `AbstractRelationalEventListener<PurchaseOrder>` 子类，覆盖六个 onXxx，对照 8.2 节验证事件顺序。
4. 写 `findByCustomerNameLikeAndTotalLessThan` 方法，从 TRACE 日志反推 Criteria → SQL 的映射；再故意写 `findByItemsProductName` 触发启动期校验红线。
5. 手写一个 `@Query("SELECT o.* FROM purchase_order o WHERE o.note LIKE :pattern")`，加 `@Modifying` 的 UPDATE 版本，练习 RowMapper Bean 注入。
6. 换成 **Spring Data R2DBC** 同样建模，对比 save 时的 SQL：体会"实体中心"与"聚合级联"的差异。
7. （进阶）实现自定义 `NamingStrategy` 把表名前缀化为 `t_`；（进阶）用 `Tables` 类导出 DDL 与实体对照。

## 9.3 源码阅读入口清单（20 个关键文件）

按"必读度"排序，路径相对 `D:\code\3rd\spring-data-relational`：

1. spring-data-relational/.../core/conversion/DbAction.java（17 种数据库动作——理解聚合持久化的钥匙）
2. spring-data-relational/.../core/conversion/WritingContext.java（级联生成规则：insert/update/save/upsert）
3. spring-data-jdbc/.../core/JdbcAggregateTemplate.java（save 五步曲编排）
4. spring-data-jdbc/.../core/AggregateChangeExecutor.java + JdbcAggregateChangeExecutionContext.java（执行与 id 回填）
5. spring-data-jdbc/.../core/convert/SqlGenerator.java（列收集 + JOIN 策略 + Lazy 缓存）
6. spring-data-relational/.../core/dialect/Dialect.java（15 个方言口子）
7. spring-data-relational/.../core/sql/render/SqlRenderer.java（AST 渲染门面）
8. spring-data-relational/.../core/mapping/BasicRelationalPersistentEntity.java（表名推导 + 表达式）
9. spring-data-relational/.../core/mapping/DefaultNamingStrategy.java（SNAKE_CASE + 外键列名）
10. spring-data-relational/.../core/mapping/AggregatePath.java（3.2 核心抽象）
11. spring-data-relational/.../core/conversion/MappingRelationalConverter.java（RowDocument 读侧）
12. spring-data-jdbc/.../core/convert/DataAccessStrategyFactory.java（策略组合点）
13. spring-data-jdbc/.../core/convert/RowDocumentResultSetExtractor.java（单查询装载，3.2）
14. spring-data-jdbc/.../core/convert/IdGeneratingEntityCallback.java（序列主键回调，3.5）
15. spring-data-jdbc/.../repository/support/JdbcRepositoryFactory.java + JdbcQueryLookupStrategy.java（装配与查询策略）
16. spring-data-jdbc/.../repository/query/JdbcQueryCreator.java（派生查询边界校验）
17. spring-data-jdbc/.../repository/query/StringBasedJdbcQuery.java（@Query + 表达式改写）
18. spring-data-r2dbc/.../core/R2dbcEntityTemplate.java（响应式 insert/update 流水线）
19. spring-data-r2dbc/.../core/StatementMapper.java（语句规格 → 渲染）
20. spring-data-r2dbc/.../repository/support/SimpleR2dbcRepository.java（响应式仓库基类）

---

## 结语

回到开篇的问题：Spring Data Relational 用什么换来"概念简单"？

- 它用**聚合作为持久化原子**回答了"对象图和表之间怎么对齐"——没有会话、没有懒加载、没有脏检查，代价是更新子集合要整组重放（先删后插）。
- 它用 **`AggregateChange`/`DbAction` 中间表示**回答了"一个聚合怎么变成多条 SQL 且保持外键顺序"——写侧与执行侧解耦，JDBC 与 R2DBC 消费同一份 IR 的两个变体。
- 它用 **SQL AST + Dialect** 回答了"八家数据库的语法差异放哪"——建模一次，渲染分家，启动时自动探测。
- 它用 **事件 + 回调双钩子、策略族 + SPI** 回答了"每个环节怎么被替换"——从命名到数据访问再到主键生成，没有一层是焊死的。

理解了这条证据链之后，再去用 Spring Data JDBC 或 R2DBC，那些"为什么 update 会删了重插""为什么派生查询不支持集合属性""为什么单查询装载默认关着"的问题，都不再是需要背诵的条目，而是从架构上必然推论出的设计决策。这份文档与《Spring Data Commons 深度源码解析》《Spring Framework 深度源码解析》一起，覆盖了从 IoC 容器到 Repository 代理、从元模型到 SQL 的完整链路。


---
