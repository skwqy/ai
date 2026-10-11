# Spring Data JPA 深度源码解析（含 Hibernate ORM 原理与发展史）

> **本文基于的源码**（均为本地实际读取）：
>
> | 仓库 | 位置 | 版本基线 |
> |---|---|---|
> | spring-data-jpa | `D:\code\3rd\spring-data-jpa` | **4.2.0-SNAPSHOT**（main，commit `effece10c`，已升级到 Hibernate 8.0.0.Beta3） |
> | spring-data-commons | `D:\code\3rd\spring-data-commons` | 4.2.0-SNAPSHOT（main） |
> | spring-framework（spring-orm） | `D:\code\3rd\spring-framework` | 7.1.0-SNAPSHOT |
> | hibernate-orm | `D:\code\3rd\hibernate-orm` | **main 分支**（commit `c1b397b935`；changelog 首条为 8.0.0.Beta1，migration-guide 已指向 8.1 开发期，依赖 Jakarta Persistence **4.0.0-M7**） |
>
> **版本取舍说明**：Spring Data JPA 的核心架构（FactoryBean → Factory → 代理 → SimpleJpaRepository + 查询派生）自 1.x 以来骨架稳定，`PartTree`、`QueryLookupStrategy`、`SharedEntityManagerCreator` 这些核心类在 2.x/3.x/4.x 之间的职责划分几乎一致，因此本文对使用 Spring Boot 3.x（Spring Data JPA 3.x）的读者同样适用。4.x 有两处值得注意的演进会在文中专门标注：① 派生查询的生成目标从 **Criteria API 迁移到 JPQL 字符串**（`JpaQueryCreator` 输出类型变为 `String`）；② 查询增强器体系引入了自研 JPQL/HQL 解析（`JpqlQueryBuilder`、`HqlQueryIntrospector` 等）。
>
> **阅读约定**：与《Spring Framework.md》一致——每章"先白话、后源码"，【源码证据】给出 `模块路径/类名.java` + 方法名 + 行号 + 代码片段。行号只对上述快照精确，读者按类名 + 方法名定位即可。

## 如何读这份文档

如果你是初学者，推荐两遍读法：

- **第一遍（建立地图，1~2 小时）**：只读第一章（总览）每节的开头白话段、各章"小结"、以及第七章（缓存全景）与第八章（贯通视图）。目标是能回答：Spring Data JPA 在整个技术栈里是哪一层？`findByLastname("张")` 这个方法谁提供实现、SQL 从哪来？`save()` 到底是 insert 还是 update？事务边界在哪里？一级/二级缓存各缓存什么？
- **第二遍（深入源码）**：对照【源码证据】逐行读。顺序建议：第四章（Repository 抽象地基）→ 第五章（Spring Data JPA 详解）→ 第六章（Hibernate 原理）→ 第二章/第三章（历史与 JPA 规范，随用随查）。

---

# 一、总览：从"SQL 拼装工"到"领域模型驱动"

## 1.1 一句话定位

**Spring Data JPA 是"Repository 抽象"在 JPA 之上的具体实现**；JPA（Jakarta Persistence）是一份"对象-关系映射"的规范；Hibernate 是这份规范事实上的标准实现。三层各司其职：

- **JPA** 定义"怎么声明实体（`@Entity`）、怎么拿会话（`EntityManager`）、怎么写查询（JPQL）"的**词汇表**——它只有接口，没有实现（`jakarta.persistence-api` jar 里连一行数据库代码都没有）。
- **Hibernate** 是干活的**引擎**：把实体映射编译成 DDL/SQL，把 `EntityManager` 的操作翻译成 JDBC 调用，并在内存里维护"实体 ↔ 数据库行"的一致性（持久化上下文、脏检查、缓存）。
- **Spring Data JPA** 是**人体工学层**：让你连 JPQL 都少写——定义一个 `interface UserRepository extends JpaRepository<User, Long>`，再写一个方法名 `findByLastnameAndAgeGreaterThan(String lastname, int age)`，它就把这个方法名解析成 JPQL、交给 Hibernate 执行，并把 CRUD 的默认实现（`SimpleJpaRepository`）、事务边界、分页排序、审计全部替你装配好。

官方对 Spring Data 的自述："Spring Data's mission is to provide a familiar and consistent, Spring-based programming model for data access while still retaining the special traits of the underlying data store."

一句话总结依赖方向：**你的业务代码 → Spring Data JPA（生成实现/派生查询）→ JPA 标准接口 → Hibernate（实现）→ JDBC 驱动 → 数据库**。本文要回答的核心问题就是：这条链上每一跳**在源码里长什么样**。

## 1.2 设计哲学：读源码前先记住四句话

1. **接口先行，实现是"装配"出来的**。你写的 Repository 只有接口；真正的对象是 `JpaRepositoryFactory` 用 JDK 动态代理在运行时造出来的——目标对象是默认实现 `SimpleJpaRepository`，查询方法由代理拦截后路由到"查询对象"（`AbstractJpaQuery`）或片段实现（见 4.4、5.3 节）。
2. **约定优于配置，但不堵死配置**。方法名能派生查询（`findByLastnameOrderByAgeDesc`），`@Query`/`@NamedQuery` 能显式指定，`QueryLookupStrategy` 三种策略决定优先级（见 5.5 节）；所有约定都有对应的扩展点（`RepositoryFragments`、`QueryEnhancerSelector`、`QueryRewriter`）。
3. **抽象复用是产品**。方法名解析引擎（`PartTree`）、分页抽象（`Pageable/Page`）、属性路径（`PropertyPath`）、CDI/AOT 支持全部住在 spring-data-commons，MongoDB/Redis/Cassandra 等 20+ 兄弟模块共享同一套"Repository 编程模型"。学一次 Spring Data JPA，等于学了大半个 Spring Data 家族。
4. **与容器的事务/异常/资源体系打通**。Repository 代理自动带事务语义（`SimpleJpaRepository` 类级 `@Transactional(readOnly=true)`）；注入的 `EntityManager` 是线程安全的共享代理（`SharedEntityManagerCreator`）；事务由 `JpaTransactionManager` 统一调度，`@Transactional` 对 JPA 和 JDBC 是同一个注解、同一套传播语义（见 5.8 节）。

## 1.3 技术栈分层全景

```
┌───────────────────────────────────────────────────────────────┐
│ 你的代码        interface UserRepository extends JpaRepository │
│                findByLastname(String) / save(user)             │
├───────────────────────────────────────────────────────────────┤
│ spring-data-jpa (4.2)                                          │
│  ├ repository.config   @EnableJpaRepositories → 注册 FactoryBean│
│  ├ repository.support  JpaRepositoryFactory → 代理 + SimpleJpaRepository│
│  ├ repository.query    PartTreeJpaQuery / JpaQueryCreator /    │
│  │                     SimpleJpaQuery(@Query) / JpaQueryExecution│
│  └ domain.support      AuditingEntityListener（审计填充）         │
├───────────────────────────────────────────────────────────────┤
│ spring-data-commons (4.2)  通用引擎（与存储无关）                  │
│  ├ repository.core.support RepositoryFactorySupport.getRepository│
│  ├ repository.query.parser  PartTree / Part / PropertyPath      │
│  └ repository.query         QueryLookupStrategy / QueryMethod   │
├───────────────────────────────────────────────────────────────┤
│ spring-orm (framework 7.1)  JPA 与 Spring 的"插座"               │
│  ├ LocalContainerEntityManagerFactoryBean（EMF 接进容器）         │
│  ├ SharedEntityManagerCreator（线程安全的 EM 共享代理）            │
│  └ JpaTransactionManager（@Transactional 的 JPA 执行者）          │
├───────────────────────────────────────────────────────────────┤
│ jakarta.persistence-api  规范接口（无实现）                       │
│  EntityManager / EntityManagerFactory / CriteriaBuilder / JPQL  │
├───────────────────────────────────────────────────────────────┤
│ hibernate-core (8.0)  真正的 ORM 引擎                            │
│  ├ boot        MetadataSources → SessionFactory（启动引导）      │
│  ├ engine.internal  StatefulPersistenceContext / ActionQueue /  │
│  │             脏检查（dirtyCheck）/ 一级缓存                     │
│  ├ query.sqm   HQL/JPQL → ANTLR4 → SQM → SQL AST → SQL 字符串   │
│  ├ proxy       ByteBuddy 懒加载代理                              │
│  └ persister   实体 ↔ 表 的读写器（insert/update SQL 装配）       │
├───────────────────────────────────────────────────────────────┤
│ JDBC 驱动 → 数据库                                              │
└───────────────────────────────────────────────────────────────┘
```

依赖关系同样可以从构建脚本实证：spring-data-jpa 依赖 spring-data-commons、spring-orm、jakarta.persistence-api（`spring-data-jpa/pom.xml` 的 `${spring}`、`${hibernate}`、`${jakarta-persistence-api}` 属性）；hibernate-core 依赖 `jakarta.persistence-api`（`hibernate-orm/gradle/libs.versions.toml`：`jpa = "4.0.0-M7"`）与 ANTLR4（`antlr = "4.13.2"`）。

## 1.4 关键问题 → 方案映射（全文导览）

| 初学者的关键疑问 | 答案（一句话版） | 详见 |
|---|---|---|
| 我只写了接口，实现在哪？ | `JpaRepositoryFactory` 用 JDK 动态代理 + `SimpleJpaRepository` 目标对象 + 查询拦截器装配出来 | 4.4、5.3 |
| `findByLastname()` 没写 SQL，SQL 从哪来？ | 方法名 → `PartTree` 语法树 → `JpaQueryCreator` 生成 JPQL → Hibernate 编译成 SQL | 4.5、5.5.1、6.8 |
| `save()` 是 insert 还是 update？ | 看 `isNew()`：有 `@Version` 看 version 是否为 null，否则看 ID 是否为 null → `persist` 或 `merge` | 5.4.2 |
| 事务边界在哪，为什么我不用写开启/提交？ | `SimpleJpaRepository` 类级 `@Transactional(readOnly=true)` + Spring AOP 代理 + `JpaTransactionManager` | 5.8 |
| 事务里怎么拿到同一个 `EntityManager`？ | `JpaTransactionManager.doBegin` 把 EM 绑到 ThreadLocal，共享代理每次取线程绑定的那个 | 5.8.2 |
| 一级缓存是什么？两次 `findById` 只查一次库吗？ | JPA 持久化上下文 = IdentityMap，命中即直接返回；由 Hibernate `StatefulPersistenceContext` 实现 | 6.4、6.6、第七章 |
| 二级缓存 / 查询缓存怎么开、存什么、何时失效？ | JPA 规范只定开关（@Cacheable/Cache API/读写模式），存储与并发策略是 Hibernate 的 RegionFactory 插件；查询缓存默认关 | 第七章 |
| 改了实体的字段没调用 save，为什么数据库也变了？ | flush 时脏检查：快照 vs 当前值 diff，自动生成 update | 6.5 |
| `LazyInitializationException` 是谁抛的？ | Hibernate 的 `LazyInitializer` 发现 Session 已关闭且数据未加载 | 6.7 |
| 为什么 `findAll(Pageable)` 会多发一条 count？ | `PagedExecution` 需要总数构造 `Page`；count 查询可从原查询派生或显式 `@Query(countQuery=…)` | 5.6 |
| 审计字段 `@CreatedDate` 谁填的？ | JPA 生命周期回调：`AuditingEntityListener` 的 `@PrePersist/@PreUpdate` | 5.9 |

## 1.5 版本演进：三张时间线（git 实证 + 官方发布记录）

### 1.5.1 JPA（Jakarta Persistence）规范时间线

| 版本 | JSR / 出处 | 发布时间 | 关键内容 |
|---|---|---|---|
| JPA 1.0 | JSR-220（EJB 3.0 的一部分） | 2006-05 | 首个标准 ORM API：`@Entity`、JPQL、`EntityManager`；**规范负责人（spec lead）正是 Hibernate 作者 Gavin King** |
| JPA 2.0 | JSR-317 | 2009-12 | Criteria API（类型安全）、Metamodel、悲观锁、二级缓存标准化 |
| JPA 2.1 | JSR-338 | 2013-04 | 存储过程、Converter（`@Converter`）、EntityGraph、DDL 生成 |
| JPA 2.2 | JSR-338 维护版 | 2017 | `@Repeatable`、Java 8 日期时间、`getResultStream()` |
| Jakarta Persistence 2.2 | Eclipse Foundation | 2019 | Java EE → Jakarta EE 迁移第一步（重授权） |
| Jakarta Persistence 3.0 | Eclipse | 2020 | 命名空间 `javax.persistence` → `jakarta.persistence` |
| Jakarta Persistence 3.1 | Eclipse | 2022 | 小幅更新（Jakarta EE 10） |
| Jakarta Persistence 3.2 | Eclipse | 2024 | record 作为 embeddable、`union/intersect/except`、查询增强（Jakarta EE 11） |
| Jakarta Persistence 4.0 | Eclipse | 2026（目标） | 下一轮大版本；Hibernate 8 主分支已按 `4.0.0-M7` 里程碑开发（`gradle/libs.versions.toml: jpa = "4.0.0-M7"`） |

### 1.5.2 Hibernate ORM 时间线

| 版本 | 时间 | 主题 |
|---|---|---|
| 诞生 | 2001 | Gavin King 与 Cirrus Technologies 同事因不满 EJB 2.x Entity Bean 而写的 ORM 工具 |
| 1.x / 2.x | 2002~2003 | 社区快速成长；2003 年 Gavin King 加入 JBoss，Hibernate 成为 JBoss 项目 |
| 3.x | 2005 | 架构大成：注解支持、`EntityManager` 实现（hibernate-entitymanager）、过滤/继承映射完善；**直接影响了 EJB 3.0 / JPA 1.0 规范的走向** |
| 3.5 | 2010 | hibernate-annotations、hibernate-entitymanager 并回核心 |
| 4.x | 2011~2012 | 新 ServiceRegistry 服务体系（SPI 化）、多租户 |
| 5.x | 2015~ | 5.0：全新 bootstrap（`MetadataSources`/`StandardServiceRegistry`）、Javassist 为默认字节码库；5.2：Java 8；5.3：JPA 2.2 + **Byte Buddy 取代 Javassist**；5.6：Jakarta 迁移过渡 |
| 6.x | 2022~2024 | 6.0（2022-04）：**Jakarta Persistence 3.0、Java 11 基线、HQL 解析从 ANTLR2 重写为 ANTLR4、新类型系统、移除旧 `org.hibernate.Criteria`**；6.1~6.6 持续打磨 SQM/SQL AST |
| 7.x | 2025 | 7.0（2025-05-20）：**Jakarta Persistence 3.2、Java 17 基线、许可证由 LGPL 改为 Apache 2.0**；7.1（2025-08）：资源扫描与锁改进；7.2（2025-12） |
| 8.0 | 2026，Beta 阶段 | 主线对接 **Jakarta Persistence 4.0**（本仓库快照 changelog 首条为 8.0.0.Beta1；migration-guide 已指向 8.1 开发期） |

> 字节码库的演进是一条值得记住的支线：3.x 用 CGLIB（增强用 Javassist 可选）→ 5.0 默认 Javassist → **5.3 起默认 Byte Buddy**（作者 Rafael Winterhalter 亲自推动），详见 6.7 节。

### 1.5.3 Spring Data JPA 时间线（本地 git tag 实证）

| 版本 | tag 日期（`git log -1 --format=%ci`） | 主题 |
|---|---|---|
| 1.0.0.RELEASE | **2011-07-21** | Spring Data 伞形项目首批子项目之一（与 MongoDB 同期），Oliver Gierke 主导；把"Repository 抽象 + 方法名派生查询"带给 JPA |
| 1.11.0.RELEASE | 2017-01-26 | Ingalls 发布列车（1.x 最后一列车） |
| 2.0.0.RELEASE | **2017-10-02** | 配合 Spring Framework 5 / Java 8；API 清理、`CrudRepository` 分裂出 `PagingAndSortingRepository` 层级 |
| 2.7.0 | 2022-05-13 | 2.x 末代（Spring Boot 2.7 对应） |
| 3.0.0 | **2022-11-18** | Jakarta EE 9+ 命名空间、Framework 6；`CrudRepository` → `ListCrudRepository`；Specification 接口全面函数化 |
| 3.5.0 | 2025-05-16 | 3.x 末代（Spring Boot 3.5 对应） |
| 4.0.0 | **2025-11-14** | Framework 7 / 基线 Java 17+；派生查询改产出 JPQL；AOT 支持（`JpaRepositoryContributor`） |
| 4.2.0-SNAPSHOT | 本文基线 | main 分支 |

## 1.6 全文章节地图

```
一 总览          ←你在这一章
二 背景与历史    JDBC→EJB Entity Bean 之痛 → Hibernate 诞生 → JPA 标准化 → Spring Data
三 JPA 规范速览  EntityManager/PersistenceContext/实体四态/JPQL（初学者地基）
四 Spring Data Commons  Repository 抽象、代理装配、PartTree 引擎
五 Spring Data JPA 详解  配置接线→工厂→SimpleJpaRepository→查询三条路→事务→审计
六 Hibernate 原理  发展史→架构→启动→持久化上下文→脏检查→加载→代理→HQL 编译→事务→缓存 SPI
七 缓存全景      JPA 规范缓存 / 一级缓存机制 / 二级缓存机制 / 查询缓存 / 选型
八 贯通视图      启动时间线 / save() 全程 / findByLastname() 全程 / 设计模式视角
九 附录          速查表 / 常见坑 / 学习路线
```

---

# 二、背景与历史：ORM 的二十年

**白话**：理解一项技术的最好方式是理解它解决了什么历史问题。这条主线只有一句话：**数据访问从"手工拼 SQL"到"以领域模型为中心"，中间经历了 Entity Bean 的失败与 Hibernate 的成功，最终被 JPA 收编成标准，又被 Spring Data 推到"连查询都帮你写"的 ergonomic 极致。**

## 2.1 JDBC 的两难

JDBC 给了 Java 一个统一的 SQL 执行接口，但把所有"脏活"留给开发者：手动组装 SQL 字符串、手动从 `ResultSet` 一列一列抠出字段塞进对象、手动处理连接/事务/异常。对象和表是两套模型（对象的引用、继承、集合 ↔ 表的主外键、JOIN、行集），这个鸿沟被称为 **object-relational impedance mismatch（对象-关系阻抗失配）**。企业应用的表动辄上百张，手工映射的样板代码淹没了业务。

## 2.2 EJB 2.x Entity Bean 的失败与 Hibernate 的诞生（2001）

2000 年前后的 J2EE 世界，"官方"答案是基于 JNDI + 容器托管持久化的 **Entity Bean**：必须实现 `EntityBean` 接口、部署描述符里配映射、依赖容器回调——对象被容器绑架，开发/测试体验极差。

2001 年，就职于澳大利亚 Cirrus Technologies 的 **Gavin King** 对 Entity Bean 的失望催生了 Hibernate：一个把 POJO 映射到表、由框架负责增删改查的库。它成立的前提恰恰是**不侵入领域模型**——你的类不需要实现任何接口。这与后来 Spring 的哲学同源。2003 年 Gavin King 加入 JBoss（2006 年 JBoss 被 Red Hat 收购），Hibernate 成为主流开源 ORM；Gavin King 本人则被 Sun 邀请主持 **EJB 3.0** 专家组——可谓"最大的讽刺，也是最大的正名"：EJB 3.0（2006）彻底废除了 Entity Bean，代之以基于注解、POJO 化的 **JPA**，其规范草案几乎就是 Hibernate 的镜像（`@Entity`、`EntityManager`、JPQL 等术语全部沿用 Hibernate 先例）。

从此，JPA 是标准、Hibernate 是参考实现与事实标准；EclipseLink、OpenJPA、后来的 EclipseLink 5.x 等也实现了 JPA，这是"规范-实现分离"的红利。

## 2.3 Spring 的介入：从 spring-orm 到 Spring Data（2010~2011）

Spring 早期通过 `spring-orm` 模块解决"Hibernate/JPA 如何进容器"的问题：`LocalContainerEntityManagerFactoryBean` 把 `EntityManagerFactory` 变成普通 Bean，`JpaTransactionManager` 让 `@Transactional` 管理 JPA 事务（第六章 6.11 节会展开）。但开发者仍要手写 DAO：注入 EM、拼 JPQL、处理分页。

2010 年前后，Spring 团队（Rod Johnson 定方向、**Oliver Gierke** 领衔实现）启动 **Spring Data** 项目：为"各种存储"提供一致的 Repository 编程模型。2011 年 7 月 21 日，Spring Data JPA 1.0.0.RELEASE 发布（本文 1.5.3 节 tag 实证），其标志性创新——**方法名派生查询**——后来被 JpaSpecificationExecutor、Querydsl、Query By Example、声明式 AOT 等一层层加厚，成为影响整个 Java 生态的范式（MyBatis 的 @Select 自动生成、Micronaut Data、Jimmer 等都有它的影子）。

## 2.4 今天的位置

到 2026 年，这条技术栈的状态是：Jakarta Persistence 3.2 已发布、4.0 在路上；Hibernate 7.x/8.0 完成 Jakarta 化、换 Apache 2.0 许可证、HQL 引擎全面重写；Spring Data JPA 4.x 进入 AOT/原生镜像时代。三个项目各守一层、彼此独立发布——这正是 1.2 节"接口先行"哲学在工程组织上的胜利。

## 2.5 本章小结

- Hibernate 诞生于对 Entity Bean 的反叛（2001，Gavin King），成功反向定义了 JPA 标准（2006，Gavin King 任 spec lead）。
- Spring Data JPA（2011）是 Spring 对"数据访问样板代码"的最后一击：Repository 接口 + 方法名派生。
- 三层结构（Spring Data JPA / JPA / Hibernate）各自独立演进，版本时间线见 1.5 节三表。

---

# 三、JPA 规范速览：读后续章节的最低限度地基

**白话**：如果你从没用过 JPA，这一章用最少的篇幅建立心智模型；已熟悉的读者可跳到第四章。

## 3.1 规范与实现分离

`jakarta.persistence-api` 这个 jar 只含接口与注解（`jakarta.persistence.EntityManager`、`@Entity`、`@Id`……），没有任何 SQL 逻辑。谁来实现？由 `persistence.xml`（或 Spring 配置）指定的 **persistence provider**（Hibernate、EclipseLink 等）实现——在 Spring Boot 里加 `spring-boot-starter-data-jpa` 就默认带上 hibernate-core，并自动把 `EntityManagerFactory` 的实现指到 Hibernate。

## 3.2 核心对象与两本"说明书"

- **`EntityManagerFactory`（EMF）**：整个应用一份，重量级，持有全部实体映射元数据。对应 Hibernate 的 `SessionFactory`（8.0 里 `SessionFactoryImpl` 同时实现 JPA 的 EMF 接口，见 6.2）。
- **`EntityManager`（EM）**：轻量级会话对象，对应 Hibernate 的 `Session`。它有两个职责：① 执行操作（`find/persist/merge/remove/createQuery`）；② 维护**持久化上下文（Persistence Context）**——一个"本会话已管理的实体集合"，也就是 JPA 规范里的一级缓存。
- **`PersistenceUnit`**：`persistence.xml` 里的一组配置（数据源、实体列表、属性），一个 EMF 对应一个 persistence unit。

**JPA 的事务模型**：resource-local（应用自己管，`EntityTransaction`）或 JTA（容器管）。在 Spring 环境里，这两个都会被 `JpaTransactionManager` 统一接管（5.8 节）。

## 3.3 实体的四个生命周期状态（第六章反复用到）

| 状态 | 含义 | 谁在管它 |
|---|---|---|
| **new / transient** | 刚 `new` 出来的普通对象，数据库无对应行，持久化上下文不认识它 | 无人 |
| **managed / persistent** | 已进持久化上下文且与数据库行关联；**字段变化会被自动同步到库**（脏检查） | EM |
| **detached** | 曾被管理，但 EM 已关闭或调用了 `detach/clear`；改字段不再生效 | 无人 |
| **removed** | 已标记删除，flush/commit 时发出 delete | EM |

四个状态转换由规范 API 驱动：`persist()`（new → managed）、`merge()`（detached → managed，返回新 managed 副本）、`remove()`（managed → removed）、`refresh()`（用数据库值覆盖 managed）、`find()`/查询（直接产出 managed）。

## 3.4 JPQL 与 Criteria

JPQL 是面向实体的查询语言（`select u from User u where u.lastname = :ln`，操作的是实体与属性名，不是表名列名）。Criteria API 是它的类型安全 DSL（`cb.equal(root.get("lastname"), ln)`）。规范还支持原生 SQL（`createNativeQuery`）。Hibernate 在 6.0 起把两者统一编译到自己的 SQM 语义模型再生成 SQL（6.8 节），Spring Data JPA 4.x 也内置了 JPQL 解析器做查询改写（5.6 节）。

## 3.5 本章小结

- JPA 的全部价值浓缩为一个对象：`EntityManager` = 操作 API + 持久化上下文。
- 实体四态（new/managed/detached/removed）是理解 `save()`、脏检查、懒加载异常的钥匙。
- 后续章节所有 Spring Data 行为最终都落在"规范 API + Hibernate 实现"上。

# 四、Spring Data Commons：Repository 抽象的地基

**白话**：Spring Data JPA 里真正"通用"的部分——Repository 标记接口、把接口变成代理的工厂、把方法名解析成查询条件的 `PartTree` 引擎——都不在 spring-data-jpa 模块里，而在 spring-data-commons。JPA/Mongo/Redis 等 20+ 模块只是这个通用引擎的"方言适配器"。本章读三个东西：接口族、代理装配、方法名解析。

## 4.1 模块定位与接口族

【源码证据】`spring-data-commons/src/main/java/org/springframework/data/repository/Repository.java`——一个**空接口**，唯一作用是让框架在类型层面识别"这是仓库"，并能携带泛型信息 `Repository<T, ID>`（T=实体、ID=主键类型）供反射读取。

其上按能力叠加（节选自 spring-data-commons 同目录）：

```
Repository<T, ID>                        空标记
 ├─ CrudRepository<T, ID>                save/findById/findAll/delete…
 │   └─ ListCrudRepository<T, ID>        返回 List 的版本（3.0 引入）
 ├─ PagingAndSortingRepository<T, ID>    findAll(Pageable/Sort)
 │   └─ ListPagingAndSortingRepository
 ├─ QueryByExampleExecutor<T>            按 Example 探针查询
 └─ ReactiveCrudRepository…（响应式分支）
```

Spring Data JPA 在此之上定义 `JpaRepository`：

【源码证据】`spring-data-jpa/.../jpa/repository/JpaRepository.java:40-42`：

```java
@NoRepositoryBean
public interface JpaRepository<T, ID>
        extends ListCrudRepository<T, ID>, ListPagingAndSortingRepository<T, ID>, QueryByExampleExecutor<T> {
    void flush();
    <S extends T> S saveAndFlush(S entity);
    ...
}
```

`@NoRepositoryBean` 告诉扫描器"别为这个中间接口生成实现 Bean"。注意 3.0 的一个代表性变化：原来 `findAll()` 等方法签名在 `CrudRepository` 返回 `Iterable`，新父接口 `ListCrudRepository` 直接返回 `List`——用户少写一次强转/包装。

## 4.2 @EnableJpaRepositories 如何注册仓库

【源码证据】`spring-data-jpa/.../jpa/repository/config/JpaRepositoriesRegistrar.java:29-37`：

```java
class JpaRepositoriesRegistrar extends RepositoryBeanDefinitionRegistrarSupport {
    protected Class<? extends Annotation> getAnnotation() { return EnableJpaRepositories.class; }
    protected RepositoryConfigurationExtension getExtension() { return new JpaRepositoryConfigExtension(); }
```

它是一个 `ImportBeanDefinitionRegistrar`（在《Spring Framework.md》7.5 节的 @Import 四用法之列）：容器启动解析 `@Configuration` 时被回调，扫描 basePackages 下的 Repository 接口，为**每一个接口**注册一个 `JpaRepositoryFactoryBean` 类型的 BeanDefinition。扫描注册的实际干活者是 spring-data-commons 的 `RepositoryConfigurationDelegate`（`RepositoryConfigurationDelegate.java:141` 的 `registerRepositoriesIn`）。

`JpaRepositoryConfigExtension` 负责方言细节：FactoryBean 用哪个类（`JpaRepositoryFactoryBean`）、需要哪些关联 Bean（如 `entityManagerFactory`、`transactionManager` 的自动匹配）。

## 4.3 FactoryBean 模式：接口变对象的容器接入口

【源码证据】`spring-data-jpa/.../jpa/repository/support/JpaRepositoryFactoryBean.java:52-53、77-80、178-205、207-213`：

```java
public class JpaRepositoryFactoryBean<T extends Repository<S, ID>, S, ID>
        extends TransactionalRepositoryFactoryBeanSupport<T, S, ID> {

    private @Nullable EntityManager entityManager;

    @PersistenceContext
    public void setEntityManager(EntityManager entityManager) { this.entityManager = entityManager; }

    @Override
    protected RepositoryFactorySupport doCreateRepositoryFactory() {
        Assert.state(entityManager != null, "EntityManager must not be null");
        return createRepositoryFactory(entityManager);
    }
    ...
    @Override
    public void afterPropertiesSet() {
        Assert.state(entityManager != null, "EntityManager must not be null");
        super.afterPropertiesSet();   // 父类里会调用 createRepositoryFactory + getRepository(接口)
    }
}
```

两点关键：

1. **`@PersistenceContext` 注入的 `EntityManager` 不是真对象而是共享代理**（详见 5.8.2）。FactoryBean 拿到它转手塞给工厂，于是每个仓库实现都通过这个代理拿 EM——这正是"事务来临时大家用的是同一个 EM"的前提。
2. FactoryBean 是 Spring 接入第三方对象的经典口子（《Spring Framework.md》7.4 节）：容器里 `userRepository` 这个 Bean 名字对应的是 FactoryBean，而 `getObject()` 返回的才是那个动态代理。

## 4.4 getRepository()：一次读完的代理装配线（核心）

【源码证据】`spring-data-commons/.../core/support/RepositoryFactorySupport.java:308-429`（`getRepository(Class, RepositoryFragments)`）：

```java
RepositoryMetadata metadata = getRepositoryMetadata(repositoryInterface);      // 327：读接口泛型 T/ID
RepositoryStub stub = getRepositoryStub(metadata, fragments);                  // 334：组装"片段组合"
RepositoryInformation information = stub.information();                        // 336：接口方法分类账本
Object target = getTargetRepository(information);                              // 359：造目标对象（JPA 里=SimpleJpaRepository）
RepositoryComposition compositionToUse = composition.append(RepositoryFragment.implemented(target)); // 364

ProxyFactory result = new ProxyFactory();                                      // 369
result.setTarget(target);
result.setInterfaces(repositoryInterface, Repository.class, TransactionalProxy.class); // 371
...
result.addAdvice(new QueryExecutorMethodInterceptor(information, ...,          // 412
        queryLookupStrategy.orElse(null), namedQueries, queryPostProcessors, ...));
result.addAdvice(new ImplementationMethodExecutionInterceptor(information,     // 416
        compositionToUse, methodInvocationListeners));
T repository = (T) result.getProxy(classLoader);                               // 419：JDK 动态代理
```

这条流水线的分工：

- **target（目标对象）**：`getRepositoryBaseClass()` 返回 `SimpleJpaRepository.class`（JPA 工厂在 `JpaRepositoryFactory.java:237-240` 决定），通过 `getTargetRepositoryViaReflection(information, entityInformation, entityManager)`（`JpaRepositoryFactory.java:230`）以构造器注入实体元信息与 EM 实例化。CRUD 方法的真正干活者就是它。
- **`QueryExecutorMethodInterceptor`（第一个拦截器）**：构造时就为接口上**每个查询方法**一次性解析出 `RepositoryQuery`（`QueryExecutorMethodInterceptor.java:94-109` 的 `mapMethodsToQuery`：`strategy.resolveQuery(method, information, projectionFactory, namedQueries)`），调用时直接查表分发（`doInvoke`：`hasQueryFor(method)` 命中则执行 `RepositoryQuery.execute`，`:154-159`）。"哪些方法算查询方法"由 `DefaultRepositoryInformation` 判定（`DefaultRepositoryInformation.java:99、111-113`：不在基类、不属于自定义实现的方法即为查询方法候选）。
- **`ImplementationMethodExecutionInterceptor`（第二个拦截器）**：查询方法之外的调用路由到**片段**（fragment）——自定义实现类或 Querydsl 等附加实现。
- **`TransactionalProxy` 标记接口**（371 行）与 `SurroundingTransactionDetectorMethodInterceptor`：让 Spring 的事务基础设施能把"代理对象上的方法调用"识别为受事务管理的调用——`SimpleJpaRepository` 上的 `@Transactional` 才能被 AOP 命中。
- **`RepositoryProxyPostProcessor`**：JPA 工厂在构造器里注册的 `CrudMethodMetadataPostProcessor`（`JpaRepositoryFactory.java:100`）会往代理里塞"当前调用的锁模式/查询提示"——5.7 节会用它。

> **一句话总结**：`userRepository.findById(1L)` 的调用链 = 代理 → `QueryExecutorMethodInterceptor`/`ImplementationMethodExecutionInterceptor` → 目标对象 `SimpleJpaRepository`（CRUD）或 `RepositoryQuery`（查询）。

## 4.5 PartTree：方法名解析引擎（与存储无关）

**白话**：`findByLastnameAndAgeGreaterThanOrderByAgeDesc` 这串"哑巴英语"如何变成结构化条件？spring-data-commons 的 `PartTree` 负责切词、`Part` 负责单词含义，JPA 模块的 `JpaQueryCreator` 负责"翻译"（5.5.1）。

【源码证据】`spring-data-commons/.../query/parser/PartTree.java:61、66、70-75`：

```java
private static final String KEYWORD_TEMPLATE = "(%s)(?=(\\p{Lu}|\\P{InBASIC_LATIN}))"; // 关键词后必须跟大写/非ASCII字母
// 方法名被 PREFIX_TEMPLATE 切成两半：
//  subject = "DistinctUser"   （find...By 之前：动作+投影）
//  predicate = "NameOrderByAge"（By 之后：条件+排序）
```

解析规则一图流：

```
findByLastnameAndAgeGreaterThanOrderByAgeDesc
└─ predicate: "LastnameAndAgeGreaterThanOrderByAgeDesc"
    ├─ OrPart #1: "LastnameAndAgeGreaterThan"  ← And 连接的原语切分（按 AND 分）
    │    ├─ Part(Lastname,            SIMPLE_PROPERTY)
    │    └─ Part(AgeGreaterThan,      GREATER_THAN)
    └─ OrderBySource: "AgeDesc" → Sort.age.desc()
```

`Part.Type` 枚举定义了全部支持的关键字及各自需要的参数个数：

【源码证据】`spring-data-commons/.../query/parser/Part.java:175-210`（节选）：

```java
public enum Type {
    BETWEEN(2, "IsBetween", "Between"), ...
    IS_NOT_NULL(0, ...), IS_NULL(0, ...),
    SIMPLE_PROPERTY("Is", "Equals");           // 兜底：没有任何关键字时按等于处理
```

subject 部分的动作动词同样由正则识别：`countBy…`、`existsBy…`、`deleteBy…`（`PartTree.java:286-287` 的 `COUNT_BY_TEMPLATE`/`EXISTS_BY_TEMPLATE`）以及 find/…By 的通用模板。切词失败（属性名拼错）会在启动时抛 `PropertyReferenceException`——**方法名派生查询的拼写错误是启动期错误而不是运行期错误**，这是它相对手写 SQL 的一大安全收益。

## 4.6 本章小结

- Repository 是"空接口 + 泛型元数据"的契约；实现由 FactoryBean → Factory → 动态代理在运行时装配。
- 代理 = 目标对象（`SimpleJpaRepository`）+ 查询分发拦截器 + 片段路由拦截器 + 事务探针。
- `PartTree` 是存储无关的方法名语法树；JPA 方言在下一章把它"翻译"成 JPQL。

---

# 五、Spring Data JPA 详解：从方法名到 SQL（重点 A）

**白话**：本章沿着"启动 → CRUD → 查询 → 事务 → 审计"五个场景，把 4.4 节的装配线在 JPA 方言里的具体实现全部展开。读完本章，你应该能对着任何一段 Repository 代码说出"它在源码里的路径"。

## 5.1 模块定位与包结构

【源码证据】`spring-data-jpa/spring-data-jpa/src/main/java/org/springframework/data/jpa/` 下五个主包：

| 包 | 职责 | 代表类 |
|---|---|---|
| `repository.config` | 启动接线 | `EnableJpaRepositories`、`JpaRepositoriesRegistrar` |
| `repository.support` | 工厂与默认实现 | `JpaRepositoryFactoryBean`、`JpaRepositoryFactory`、`SimpleJpaRepository`、`JpaMetamodelEntityInformation` |
| `repository.query` | 查询体系 | `JpaQueryLookupStrategy`、`PartTreeJpaQuery`、`JpaQueryCreator`、`SimpleJpaQuery`、`JpaQueryExecution` |
| `domain.support` | 实体相关 | `AuditingEntityListener` |
| `repository.aot` | AOT/原生镜像 | `JpaRepositoryContributor`（4.0 新增） |

## 5.2 配置接线：三个 Bean 的接力

启动期发生三件事（呼应 4.2/4.3）：

1. `@EnableJpaRepositories` → `JpaRepositoriesRegistrar`（`JpaRepositoriesRegistrar.java:29`）扫描并注册 `JpaRepositoryFactoryBean` BeanDefinition。
2. `LocalContainerEntityManagerFactoryBean`（spring-orm，6.11 节展开）产出真正的 Hibernate `EntityManagerFactory`。
3. 每个 FactoryBean `afterPropertiesSet()`（`JpaRepositoryFactoryBean.java:207-213`）→ `createRepositoryFactory(entityManager)`（`:189-205`）→ 父类完成 4.4 节的代理装配。

`JpaRepositoryFactory` 构造器（`JpaRepositoryFactory.java:89-113`）里还有两处值得圈点的接线：

```java
PersistenceProvider extractor = PersistenceProvider.fromEntityManager(entityManager); // :94 识别 Hibernate/EclipseLink
...
addRepositoryProxyPostProcessor(crudMethodMetadataPostProcessor);                     // :100 锁/提示传播
addRepositoryProxyPostProcessor((factory, repositoryInformation) -> {                 // :101
    if (isTransactionNeeded(repositoryInformation.getRepositoryInterface())) {
        factory.addAdvice(SurroundingTransactionDetectorMethodInterceptor.INSTANCE);  // :104 返回 Stream/存储过程需保证有事务
    }
});
```

## 5.3 片段组合：SimpleJpaRepository + 自定义实现 + Querydsl

【源码证据】`JpaRepositoryFactory.java:279-308`：

```java
protected RepositoryFragments getRepositoryFragments(RepositoryMetadata metadata, EntityManager entityManager,
        EntityPathResolver resolver, CrudMethodMetadata crudMethodMetadata) {
    RepositoryFragments fragments = this.fragmentsContributor.contribute(metadata,        // :299
            getEntityInformation(metadata.getDomainType()), entityManager, resolver);
    ...
}
```

`JpaRepositoryFragmentsContributor.DEFAULT = QuerydslContributor.INSTANCE`（`JpaRepositoryFragmentsContributor.java:41`）：若你的接口继承了 `QuerydslPredicateExecutor`，自动追加一个 `QuerydslJpaPredicateExecutor` 片段。你在 `@Repository` 接口上写自定义方法 + 同名实现类，也是走 `RepositoryFragments.just(customImplementation)` 进组合（4.4 节 295 行）。**"目标对象 + 若干片段"共同满足接口的全部方法**，这是 Spring Data 2.x 引入、4.x 定型的组合机制。

## 5.4 SimpleJpaRepository：CRUD 的默认实现

【源码证据】`spring-data-jpa/.../jpa/repository/support/SimpleJpaRepository.java:110-112`：

```java
@Repository
@Transactional(readOnly = true)
public class SimpleJpaRepository<T, ID> implements JpaRepositoryImplementation<T, ID> {
```

**类级 `@Transactional(readOnly=true)`**：所有方法默认跑在只读事务里（驱动可据此做读优化）；写方法再单独标注 `@Transactional` 覆盖（`:196、205、238…`）——这就是"Repository 方法天生有事务"的全部来源，没有任何魔法。

### 5.4.1 save()：insert 还是 update？

【源码证据】`SimpleJpaRepository.java:658-670`：

```java
@Transactional
public <S extends T> S save(S entity) {
    Assert.notNull(entity, ENTITY_MUST_NOT_BE_NULL);
    if (entityInformation.isNew(entity)) {
        entityManager.persist(entity);      // 新实体 → INSERT
    } else {
        return entityManager.merge(entity); // 旧实体 → MERGE（按主键加载出受管副本再复制属性）
    }
}
```

`isNew` 的判定在 `JpaMetamodelEntityInformation.java:254-263`：

```java
public boolean isNew(T entity) {
    if (versionAttribute.isEmpty()
            || versionAttribute.map(Attribute::getJavaType).map(Class::isPrimitive).orElse(false)) {
        return super.isNew(entity);         // 无 @Version → 看 @Id 是否为 null（primitive 恒非新）
    }
    return versionAttribute.map(it -> wrapper.getPropertyValue(it.getName()) == null).orElse(true);
}
```

**实践含义**：给实体加 `@Version` 能让 `save()` 的判断更可靠（ID 由应用赋值的场景必须用 version 判断），否则ID 非空即 `merge`，可能多发一次 select。

### 5.4.2 delete 家族与批量操作

- `deleteById`（`:195-202`）= `findById` + `delete`；`delete(T)` → `doDelete`（`:213-235`）：新实体直接跳过，受管实体 `remove`，游离实体先 `merge` 再 `remove`，且**先确认库里有这行**（`:227` `entityManager.find`），不存在则 NOOP。
- `deleteAllInBatch(Iterable)`（`:296-306`）与 `deleteAllByIdInBatch`（`:248-281`）：生成**单条 delete 语句**（`applyAndBind` 拼 `delete from User where id in ?`）直接 `executeUpdate()`——绕过持久化上下文，不触发级联与生命周期回调（接口 javadoc 明确警告，`JpaRepository.java` 的 `deleteAllInBatch` 注释）。

### 5.4.3 findById / findAllById / getReferenceById

- `findById`（`:328-344`）= `entityManager.find(domainType, id, [lockMode,] hints)`——直通 JPA 规范 API，锁与提示从 `CrudMethodMetadata` 取（5.7）。
- `findAllById`（`:416-446`）：非复合主键时生成 `path.in(idCollection)` 的 Criteria 查询（`:438-445`），**一次 SQL 批量加载**。
- `getReferenceById`（`:362-367`）= `entityManager.getReference`：返回不触发 SQL 的懒代理（6.7 节）。

### 5.4.4 Specification / Query By Example / FluentQuery

`SimpleJpaRepository` 直接实现了 `JpaSpecificationExecutor` 全部方法（`:459-577`）。核心模式只有一个：把 `Specification`（函数式 `(root, query, cb) -> Predicate`）应用到 Criteria 查询（`applySpecificationToCriteria`，`:963-976`）：

```java
Root<U> root = query.from(domainClass);
Predicate predicate = spec.toPredicate(root, query, builder);
if (predicate != null) query.where(predicate);
```

`count(Specification)`（`:927-946`）把 select 换成 `count/countDistinct` 并**清空排序**（`:943`）；`exists`（`:489-502`）用 `select 1 ... maxResults(1)` 避免全行传输；4.x 新增 `update(UpdateSpecification)`/`delete(DeleteSpecification)`（`:506-520`）走 `CriteriaUpdate/CriteriaDelete`。Query By Example 则被适配成内部 `ExampleSpecification`（`:580-641`）——两种"动态条件"最终殊途同归于 Criteria。

## 5.5 查询的三条路：LookupStrategy

【源码证据】`spring-data-jpa/.../query/JpaQueryLookupStrategy.java:316-330`：

```java
return switch (key != null ? key : Key.CREATE_IF_NOT_FOUND) {
    case CREATE -> new CreateQueryLookupStrategy(...);
    case USE_DECLARED_QUERY -> new DeclaredQueryLookupStrategy(...);
    case CREATE_IF_NOT_FOUND -> new CreateIfNotFoundQueryLookupStrategy(em, queryMethodFactory,
            new CreateQueryLookupStrategy(...), new DeclaredQueryLookupStrategy(...), configuration);
```

默认策略 `CREATE_IF_NOT_FOUND` 的合成交互（`:294-305`）：**先问"声明了吗"（`@Query`/`@NamedQuery`/`@Procedure`），没有就按方法名派生**：

```java
RepositoryQuery lookupQuery = lookupStrategy.resolveQuery(method, configuration, em, namedQueries);
if (lookupQuery != NO_QUERY) return lookupQuery;
return createStrategy.resolveQuery(method, configuration, em, namedQueries);
```

策略产出的是 `AbstractJpaQuery` 家族；所有查询对象在**启动期构造完成**并缓存在 `QueryExecutorMethodInterceptor` 的 map 里（4.4 节），运行期零解析开销。

### 5.5.1 方法名派生：PartTreeJpaQuery → JpaQueryCreator

构造期把 4.5 节的 `PartTree` 语法树接上：

【源码证据】`spring-data-jpa/.../query/PartTreeJpaQuery.java:108-118`：

```java
this.tree = new PartTree(method.getName(), domainClass);
validate(tree, parameters);                       // 参数个数与关键字需要量必须匹配（:153-170）
this.countQuery = new CountQueryPreparer();
this.queryPreparer = tree.isCountProjection() ? countQuery : new QueryPreparer();
```

执行期才真正生成查询字符串（惰性绑定参数值）。翻译官是 `JpaQueryCreator`：

【源码证据】`spring-data-jpa/.../query/JpaQueryCreator.java:74`：

```java
public class JpaQueryCreator extends AbstractQueryCreator<String, JpqlQueryBuilder.Predicate>
        implements JpqlQueryCreator {
```

> **版本注**：泛型的第二个参数是"累积中的谓词"、返回值是 `String`（JPQL 文本）。在 3.x 及以前这里是 `AbstractQueryCreator<CriteriaQuery, Predicate>`——**4.0 起派生查询改为直接生成 JPQL**，好处是产出的 JPQL 可再被 Hibernate 的 SQM 引擎与 Spring Data 自己的查询改写器（`QueryEnhancer`，5.6）统一处理，也规避了 Criteria 生成 JOIN 的历史怪癖。父类模板方法 `create/and/or/complete` 的骨架未变（`JpaQueryCreator.java:186-210`）。

关键字翻译表（同一 switch，`JpaQueryCreator.java:458-535`）节选：

```java
switch (type) {
    case BETWEEN:      ... where.between(placeholder(first), placeholder(second));
    case AFTER:
    case GREATER_THAN: return where.gt(placeholder(provider.next(part)));
    case GREATER_THAN_EQUAL: return where.gte(...);
    case BEFORE:
    case LESS_THAN:    return where.lt(...);
    case IS_NULL:      return where.isNull();
    case NOT_IN:       return whereIgnoreCase.notIn(...);
    case LIKE: case NOT_LIKE:  ... whereIgnoreCase.like(parameterExpression, escapeChar);
    case TRUE:         return where.isTrue();
    case SIMPLE_PROPERTY:
    case NEGATING_SIMPLE_PROPERTY: ... whereIgnoreCase.eq/neq(expression);
    case IS_EMPTY: case IS_NOT_EMPTY: ... where.isEmpty/isNotEmpty();
```

细节：`LIKE` 系自动套上 `EscapeCharacter`（`%`/`_` 的转义字符，`:504`）；`SIMPLE_PROPERTY` 且参数为 `null` 时自动降级为 `IS NULL`（`:517-519`）——这就是"传 null 不用改查询"的由来。排序在 `buildQuery(Sort)`（`:229-275`）里逐个 `select.orderBy(...)`；`@Safe` 之外的非法排序属性同样启动期报错。

`PartTreeJpaQuery.getExecution()` 还会按方法名前缀换执行器（`PartTreeJpaQuery.java:139-151`）：`deleteBy…` → `DeleteExecution`、`existsBy…` → `ExistsExecution`、滚动接口 → `ScrollExecution`。

### 5.5.2 @Query 与 NamedQuery：字符串查询

【源码证据】`JpaQueryMethod.java:429` + `NamedQuery.java:96-110`：

```java
// @Query(nativeQuery=true) 分叉
return isNativeQuery() ? DeclaredQuery.nativeQuery(query) : DeclaredQuery.jpqlQuery(query);
```

`@Query` 产出 `AbstractStringBasedJpaQuery` 的两个子类：`SimpleJpaQuery`（JPQL，`SimpleJpaQuery.java:39`）与 `NativeJpaQuery`（原生 SQL，`NativeJpaQuery.java:45`）。NamedQuery 的查找顺序：**实体类 `@NamedQuery`/`@NamedQueries` 注解 → orm.xml → 约定名**，约定名即 `JpaQueryMethod.getNamedQueryName()`（`:433-437`）= `EntityName.方法名`，count 变体加 `.count` 后缀（`:440-447`）。

`@Query` 的强大还来自三个注解联动：`@Modifying`（配 `ModifyingExecution`，clearAutomatically/flushAutomatically 两个开关）、`@QueryHints`、`@EntityGraph`。

### 5.5.3 EntityGraph：一次查询抓取关联图

【源码证据】`AbstractJpaQuery.java:265-266、282`：

```java
protected Query createQuery(JpaParametersParameterAccessor parameters) {
    return applyLockMode(applyEntityGraphConfiguration(applyHints(doCreateQuery(parameters), method), method), method);
}
// applyEntityGraphConfiguration 内部：
QueryHints hints = Jpa21Utils.getFetchGraphHint(em, entityGraph, ...);   // :282
```

`@EntityGraph(attributePaths={"roles","department"})` 被翻译为 JPA 规范的 `jakarta.persistence.fetchgraph` 提示交给 Hibernate——效果是**本次查询 LEFT JOIN 抓取指定关联**，是对 N+1 问题最直接的武器。

## 5.6 分页与 count：Pageable 如何变成 LIMIT/OFFSET + COUNT

【源码证据】`spring-data-jpa/.../query/JpaQueryExecution.java:279-324`：

```java
static class PagedExecution extends JpaQueryExecution {
    protected Object doExecute(AbstractJpaQuery repositoryQuery, JpaParametersParameterAccessor accessor) {
        Query query = repositoryQuery.createQuery(accessor);
        return PageableExecutionUtils.getPage(query.getResultList(), accessor.getPageable(),
                () -> count(query, repositoryQuery, accessor));            // :293
    }
    private long count(Query resultQuery, AbstractJpaQuery repositoryQuery, JpaParametersParameterAccessor accessor) {
        if (repositoryQuery.hasDeclaredCountQuery()) return doCount(repositoryQuery, accessor);
        return provider.getResultCount(resultQuery, () -> doCount(repositoryQuery, accessor)); // :303
    }
```

三个关键设计：

1. **LIMIT/OFFSET**：`Pageable` 在 `createQuery` 路径里转成 `setFirstResult/setMaxResults`（`PageableExecutionUtils`/`AbstractJpaQuery` 家族处理），与数据库方言无关（SQL 由 Hibernate 翻译）。
2. **count 从哪来**：优先用声明式 count（`@Query(countQuery=…)`，`hasDeclaredCountQuery()`）；否则由 `PersistenceProvider.getResultCount` 走 Hibernate 的**优化路径**——能用同一条 SQL 的窗口函数/直接取总行数就不发第二条；最保守回退是 `doCount` 执行派生 count 查询。
3. **count 怎么派生**：方法名派生查询在构造期就备好 count 版本（`PartTreeJpaQuery` 的 `CountQueryPreparer`，`:112-113`）；字符串查询则由 `QueryEnhancer` 派生——4.x 里这层已升级为自研 JPQL/HQL 解析器（`JpqlQueryIntrospector`/`HqlQueryIntrospector` + `JpqlCountQueryTransformer`/`HqlCountQueryTransformer`，见 `repository/query` 目录），旧版 JSqlParser 仍以 `JSqlParserQueryEnhancer` 兜底，选择器可替换（`QueryEnhancerSelector`，`JpaRepositoryFactoryBean.java:130-159`）。
4. `PageableExecutionUtils.getPage(content, pageable, totalSupplier)`（spring-data-commons）：当 `page==0 且 content.size()<pageSize` 时**跳过 count**——首页不满一页时少发一条 SQL。

## 5.7 @Lock 与 @QueryHints：CrudMethodMetadata 的传播

仓库方法上标 `@Lock(LockModeType.PESSIMISTIC_WRITE)` 或 `@QueryHints` 时，`CrudMethodMetadataPostProcessor`（`CrudMethodMetadataPostProcessor.java:61`）通过 ThreadLocal（`:100`）把"当前正在调用的方法"暴露给 `SimpleJpaRepository`：`findById` 里 `metadata.getLockModeType()/getHints()`（`:335-343`）、查询路径里 `applyRepositoryMethodMetadata` 消费它们。这套机制把"声明在接口方法上的元数据"传给了"实现在另一个类里的目标对象"。

## 5.8 事务打通：三个角色的闭合回路（重点）

**白话**：谁开事务？`SimpleJpaRepository` 上的 `@Transactional`。谁执行它？Spring AOP 的 `TransactionInterceptor` + `JpaTransactionManager`。事务里的 `save` 怎么拿到"事务那个 EM"？`SharedEntityManagerCreator` 的共享代理从 ThreadLocal 取。

### 5.8.1 JpaTransactionManager.doBegin：EM 上线程

【源码证据】`spring-framework/spring-orm/.../orm/jpa/JpaTransactionManager.java:386-459`（doBegin 核心行）：

```java
if (!txObject.hasEntityManagerHolder() || txObject.getEntityManagerHolder().isSynchronizedWithTransaction()) {
    EntityManager newEm = createEntityManagerForTransaction();                 // :400 从 EMF 新建 EM
    txObject.setEntityManagerHolder(new EntityManagerHolder(newEm), true);
}
EntityManager em = txObject.getEntityManagerHolder().getEntityManager();       // :407
Object transactionData = getJpaDialect().beginTransaction(em, ...);            // :411 HibernateJpaDialect 调 em.getTransaction().begin()
...
if (getDataSource() != null) {                                                 // :422 把 JPA 事务的 JDBC 连接"曝光"给 DataSource
    ConnectionHandle conHandle = getJpaDialect().getJdbcConnection(em, definition.isReadOnly());
    if (conHandle != null) TransactionSynchronizationManager.bindResource(getDataSource(), conHolder); // :432
}
if (txObject.isNewEntityManagerHolder()) {
    TransactionSynchronizationManager.bindResource(obtainEntityManagerFactory(),
            txObject.getEntityManagerHolder());                                // :444-447 EM 绑到线程！
}
```

最后一行是整条链的锚点：**EMF 为 key、EntityManagerHolder 为 value 绑进 `TransactionSynchronizationManager` 的 ThreadLocal map**。`doGetTransaction`（`:357-368`）先查这个 map 判断是否有既有事务（决定 `PROPAGATION_REQUIRED` 语义）。

### 5.8.2 SharedEntityManagerCreator：每个注入点拿到"对"的 EM

【源码证据】`spring-framework/spring-orm/.../orm/jpa/SharedEntityManagerCreator.java:302、317-371`：

```java
private static class SharedEntityManagerInvocationHandler ... {
    public @Nullable Object invoke(Object proxy, Method method, @Nullable Object[] args) throws Throwable {
        ... // equals/hashCode/isOpen/close/getTransaction 特殊处理
        case "getTransaction" -> throw new IllegalStateException(
            "Not allowed to create transaction on shared EntityManager - use Spring transactions ..."); // :361-364
        // 真正的分发：
        EntityManager target = EntityManagerFactoryUtils.doGetTransactionalEntityManager(
                this.targetFactory, this.properties, ...);                     // :370-371 先取线程绑定的 EM
        ... // 没有事务时：新建临时 EM，用完即关（finally closeEntityManager）
```

于是：**事务内**所有 `@PersistenceContext` 注入点（包括所有 Repository）拿到的是同一个 EM——持久化上下文、一级缓存、flush 时机全在同一个会话语义里；**无事务**时每次调用临时开一个 EM 并及时归还连接（这就是"只读查询不开事务也能跑"的原因）。

### 5.8.3 与 JdbcTemplate 共存

`JpaTransactionManager.doBegin` 的 422-434 行把 JPA 事务底层的 JDBC 连接也绑定到 DataSource 资源键上，使同一事务里的 `JdbcTemplate` 用**同一条连接**（Hibernate 的 `getJdbcConnection` 实现由 `HibernateJpaDialect` 提供，6.11 节）。这是《Spring Framework.md》6.5.6 节"ThreadLocal 资源绑定"思想在 JPA 方言下的完整复刻。

## 5.9 审计：@CreatedDate 是谁填的

【源码证据】`spring-data-jpa/.../domain/support/AuditingEntityListener.java:63、84-105`：

```java
public class AuditingEntityListener {
    public void setAuditingHandler(ObjectFactory<AuditingHandler> auditingHandler) { ... }   // :72
    @PrePersist
    public void touchForCreate(Object target) { ... }   // :84-85 INSERT 前填充 @CreatedDate/@CreatedBy
    @PreUpdate
    public void touchForUpdate(Object target) { ... }   // :104-105 UPDATE 前填充 @LastModifiedDate/@LastModifiedBy
}
```

它就是一个标准的 JPA 实体监听器（回调由 Hibernate 在 flush 时触发，见 6.5）。`@EnableJpaAuditing` → `JpaAuditingRegistrar`（`JpaAuditingRegistrar.java:46、61-84`）注册 `jpaAuditingHandler` Bean，并把监听器 Bean 挂到 `EntityManagerFactory` 的配置里（`registerAuditListenerBeanDefinition`，`:74-84`）。`AuditorAware<T>` 决定"当前是谁"（通常从 SecurityContext 取）。

## 5.10 本章小结

- 启动：`@EnableJpaRepositories` → 每接口一个 FactoryBean → 工厂装配"目标对象 + 片段 + 查询拦截器"的代理。
- CRUD：`SimpleJpaRepository` 直通 `EntityManager`；`save()` 由 `isNew()` 分叉 `persist/merge`；批量变体绕开持久化上下文。
- 查询：`CREATE_IF_NOT_FOUND` 先声明后派生；派生查询 4.x 起产出 JPQL；EntityGraph/锁/提示经 `AbstractJpaQuery.createQuery` 三连注入。
- 事务：类级 `@Transactional(readOnly=true)` + `JpaTransactionManager` 绑 EM 上线程 + 共享 EM 代理取线程绑定——三件套闭环。
- 审计：JPA 生命周期回调 + `AuditingHandler`。

# 六、Hibernate ORM 原理：JPA 默认实现的内幕（重点 B）

> 本章源码路径均相对 `hibernate-orm/hibernate-core/src/main/java/`（除非注明根目录其它文件）。基于 main 分支（8.0 Beta 之后的 8.1 开发期、Jakarta Persistence 4.0.0-M7）——即最前沿的 Hibernate：**JPA 接口已从"7.x 的适配桥接"变为"直接继承"**（`SessionFactory.java:146` 直接 `extends EntityManagerFactory`，`Session.java:188` 直接 `extends EntityManager`）。对使用 Hibernate 6.x/7.x 的读者，本章描述的分层（事件监听、持久化上下文、SQM）完全一致，个别类名/包名（如 ActionQueue 所在包）有差异，文中会标注。

## 6.1 发展史：从个人项目到事实标准（2001~2026）

1.1.5.2 节的表格给出了时间线，这里补三条"改变生态格局"的历史脉络：

- **规范反向输入**：Hibernate 3（2005）的注解映射与查询语言直接塑造了 EJB 3.0 / JPA 1.0 规范，作者 Gavin King 担任规范负责人——开源实现反哺标准的经典案例。此后 Hibernate 成为 JPA 的"参考实现"（RI）。
- **字节码库三次更替**：3.x 时代 CGLIB（增强可用 Javassist）→ 5.0 起默认 Javassist → **5.3 起 Byte Buddy**（当时其作者 Rafael Winterhalter 主动提案，解决 Javassist 对新 JDK 的跟进问题）。今天 8.x 的 `gradle/libs.versions.toml:9` 里 `byteBuddy = "1.18.14"`。懒加载代理与字节码增强都建立在这个库上（6.7 节）。
- **许可证变更**：7.0（2025）起由 LGPL 改为 **Apache License 2.0**——对企业采用是一次"去顾虑"。
- **8.x 三件大事**（本仓库 changelog.md/migration-guide.adoc 实证）：① flush 动作队列**图化调度**（`GraphBasedActionQueue`/`FlushCoordinator`，6.5 节）；② 面向 **Jakarta Persistence 4.0** 合规（移除 Hibernate 自有 `FlushModeType`、统一 JPA 枚举，changelog HHH-20429）；③ 查询栈两级缓存化（HQL 解释缓存 + `JdbcSelect` 缓存，6.8 节）。

## 6.2 架构总览：两层接口、三张门面

**白话**：对上，Hibernate 要"扮演" JPA 的 `EntityManagerFactory/EntityManager`；对下，它要管理 JDBC、缓存、事务、事件。8.x 的做法是让核心接口**直接继承** JPA 接口，同时保留一套内部 SPI 供框架内部高速通道使用。

【源码证据】`org/hibernate/SessionFactory.java:146`、`org/hibernate/Session.java:188`：

```java
public interface SessionFactory extends EntityManagerFactory, Referenceable, Serializable { ... }
public interface Session extends SharedSessionContract, EntityManager { ... }
```

内部双轨 SPI：

```java
// engine/spi/SharedSessionContractImplementor.java:80-81 —— Session/StatelessSession 共用底层契约
public interface SharedSessionContractImplementor
        extends SharedSessionContract, JdbcSessionOwner, Options, LobCreationContext, WrapperOptions {
    SessionFactoryImplementor getFactory();       // L88
    PersistenceContext getPersistenceContext();   // L143 持久化上下文入口
    JdbcCoordinator getJdbcCoordinator();         // L149 JDBC 协调器
}
```

唯一实现类是重量级的 `SessionImpl`（`org/hibernate/internal/SessionImpl.java:155-158`，同时实现 `SharedSessionContractImplementor`、`EventSource` 等 9 个接口）。**Spring 注入给业务代码的"EntityManager"，绝大多数时候就是 `SessionImpl`（或其共享/事务代理后面的它）**。

包地图（一屏版）：

```
org.hibernate
 ├ boot/            启动引导（MetadataSources、SessionFactoryBuilder、命名策略）
 ├ engine/          引擎核心（持久化上下文 internal/、事务资源 resource/、SPI spi/）
 ├ action/queue/    8.x：flush 动作队列（GraphBasedActionQueue/FlushCoordinator）
 ├ event/           事件体系（persist/merge/delete/flush/load 监听器）
 ├ persister/       持久化器（实体↔表的读写器、SQL 生成、脏检查 DirtyHelper）
 ├ loader/          加载器（CacheLoadHelper 等）
 ├ query/           HQL 解析（hql/）+ SQM 语义模型（sqm/）+ Criteria（criteria/）
 ├ sql/ast/         SQL AST 与方言翻译器（spi/SqlAstTranslatorFactory）
 ├ proxy/           懒加载代理（pojo/bytebuddy/）
 ├ collection/spi/  PersistentBag 等集合包装
 ├ cache/           二级缓存（spi/RegionFactory）
 └ internal/        SessionImpl / SessionFactoryImpl 两大实现
```

## 6.3 启动引导：MetadataSources → SessionFactoryImpl

**白话**：启动就是"读映射（注解/XML）→ 编译成运行时元模型 → 组装一台工厂"的过程。Spring 的 `LocalContainerEntityManagerFactoryBean` 最终就是调这条链（6.11 节）。

构建链（【源码证据】均为实际读取）：

1. `MetadataSources.buildMetadata()`（`boot/MetadataSources.java:221-223`）：收集 `@Entity` 类/`orm.xml`/`persistence.xml` 来源，产出 `MetadataImpl`。
2. `MetadataImpl.getSessionFactoryBuilder()`（`boot/internal/MetadataImpl.java:163-187`，经 `SessionFactoryBuilderFactory` SPI 可替换）。
3. `SessionFactoryBuilderImpl.build()`（`boot/internal/SessionFactoryBuilderImpl.java:435-437`）→ `SessionFactoryImpl` 构造器。

【源码证据】`org/hibernate/internal/SessionFactoryImpl.java:240-379`（构造器初始化序列，节选）：

```java
serviceRegistry = getServiceRegistry( options, this );      // L253 服务注册表（连接、事务、缓存…全是 Service）
eventEngine = new EventEngine( options, serviceRegistry );  // L254 事件系统（监听器注册表）
bootMetamodel.initSessionFactory( this );                   // L258 引导元数据回接工厂
cacheAccess = serviceRegistry.getService( CacheImplementor.class ); // L274 二级缓存
...
queryEngine = new QueryEngineImpl( ... );                   // L319 HQL 解释引擎
// L324-333 构建 MappingMetamodelImpl 与 JPA Metamodel
// L377-379 actionQueueFactory = ...requireService( ActionQueueFactoryService.class ).buildActionQueueFactory( this )
```

`SessionFactoryImpl` 是一台"服务容器"：ServiceRegistry（策略模式 + 依赖注入的自研实现）、EventEngine（所有 JPA 操作都是事件，6.4 节）、QueryEngine（6.8 节）、CacheImplementor（6.10 节）、ActionQueueFactory（6.5 节）。**理解 Hibernate 的钥匙之一：一切都是 Service，一切都可以替换**（连接池、缓存、字节码库、命名策略……）。

## 6.4 持久化上下文：一级缓存与实体状态机（核心）

### 6.4.1 StatefulPersistenceContext：一本总账

【源码证据】`org/hibernate/engine/internal/StatefulPersistenceContext.java:117-143`：

```java
private HashMap<EntityKey, EntityHolderImpl> entitiesByKey;   // L118 已加载实体（按 EntityKey）
private HashMap<EntityKey, Object> entitySnapshotsByKey;      // L128 数据库快照（脏检查用）
private IdentityHashMap<Object, PersistentCollection<?>> arrayHolders; // L131
private InstanceIdentityMap<PersistentCollection<?>, CollectionEntry> collectionEntries; // L134 集合台账
```

- `entitiesByKey` 就是**一级缓存（Identity Map）**：同一次会话内，同一主键只会有一份对象实例——`findById` 两次返回 `==` 相同的引用，根源在这。
- `entitySnapshotsByKey` 是**脏检查的"底片"**：实体装载时拍下的属性值快照（6.5 节 diff 的右操作数）。
- 8.x 细节：实体表已从 IdentityHashMap 改为按 `EntityKey` 相等语义的 HashMap；字段全部懒初始化（注释 L113-115："null checks are free…"）。

### 6.4.2 persist/merge/remove：事件驱动状态机

`SessionImpl.persist`（`org/hibernate/internal/SessionImpl.java:701-707`）把操作转成事件、广播给监听器：

```java
eventListenerGroups.eventListenerGroup_PERSIST
        .fireEventOnEachListener( persistEvent, PersistEventListener::onPersist );
```

【源码证据】`org/hibernate/event/internal/DefaultPersistEventListener.java:77-92`——persist 的完整状态机：

```java
switch ( getEntityState( entity, entityName, entityEntry, source, true ) ) {
    case DETACHED:  throw new PersistentObjectException( "Detached entity passed to persist: "... );
    case PERSISTENT: entityIsPersistent( event, createCache ); break;
    case TRANSIENT:  entityIsTransient( event, createCache ); break;      // L85 → saveWithGeneratedId → ActionQueue
    case DELETED:    entityEntry.setStatus( Status.MANAGED );             // L88 "复活"已删除实体
                     source.getActionQueue().unScheduleDeletion( entityEntry, event.getObject() );
```

对应关系：`entityIsTransient` → `AbstractSaveEventListener.saveWithGeneratedId`（`event/internal/AbstractSaveEventListener.java:87`）→ `addInsertAction`（L386-416，把 `EntityIdentityInsertAction`/`EntityAddAction` 排进 ActionQueue）→ `makeEntityManaged()` 注册进持久化上下文。**注意：persist 只"排动作"不"发 SQL"——SQL 在 flush 时才统一执行**，这是 JPA"写操作延迟"语义的源码根源。

`merge`（`DefaultMergeEventListener.java:142-218`）对 DETACHED 实体做"按 id 加载受管副本 → 深拷贝属性"（`copyCompositeTypeId` L221-235），返回的是**受管副本**而非原对象——这就是 5.4.1 节 `save()` 走 merge 分支时"传进去的对象和返回的对象不是同一个"的根源。`remove`（`DefaultDeleteEventListener.java:135-145、397`）最终 `persistenceContext.setEntryStatus(entityEntry, Status.DELETED)`。

## 6.5 脏检查与 flush：SQL 是什么时候、按什么顺序发的（核心）

### 6.5.1 flush 全流程

```
事务提交（或 flush()/查询触发 auto-flush）
 → SessionImpl.flush()（SessionImpl.java:1448-1451）→ fireFlush() 发 FlushEvent
 → DefaultFlushEventListener.onFlush（event/internal/DefaultFlushEventListener.java:21-49）
    ├ flushEverythingToExecutions：对每个受管实体触发 FlushEntityEvent —— 脏检查（6.5.2）
    └ performExecutions（AbstractFlushingEventListener.java:363-383）
        └ actionQueue.executeActions()（L377）—— 真正发 SQL
```

### 6.5.2 脏检查：快照 diff

【源码证据】`org/hibernate/event/internal/DefaultFlushEntityEventListener.java:551-557`：

```java
final Object[] values = event.getPropertyValues();
final Object[] loadedState = entry.getLoadedState();
if ( loadedState != null ) {
    // dirty check against the usual snapshot of the entity
    dirtyProperties = persister.findDirty( values, loadedState, entity, session );
    dirtyCheckPossible = true;
}
```

diff 的实现逐属性交给各 `Type`（`org/hibernate/persister/entity/DirtyHelper.java:60-69`：`propertyType.isDirty(previousState[i], currentState[i], ...)`；惰性未取值属性 `UNFETCHED_PROPERTY` 被跳过）。**这解释了"改了字段不调 save 也生效"与"没改字段不产生 update"两件事**。

三条加速通道（`DefaultFlushEntityEventListener.java:605-650`）：`Interceptor.findDirty`、字节码增强实体的 `SelfDirtinessTracker`（增强过的实体自己记账，连 diff 都省了）、`CustomEntityDirtinessStrategy`。

快照从哪来？装载时 `EntityInitializerImpl`（`sql/results/graph/entity/internal/EntityInitializerImpl.java:1759-1770`）把解析出的状态**同时**用作实体初值与 `EntityEntry.loadedState`，并 `takeSnapshot`（L1782）；`deepCopy` 保证快照与实体不共享可变引用（`AbstractEntityPersister.java:2043-2046` 惰性属性补载时回写快照）。

### 6.5.3 ActionQueue：flush 的 SQL 编排器（8.x 图调度）

**白话**：flush 时攒下的所有动作要按"不违反外键约束"的顺序执行。传统实现是固定阶段顺序；8.x 默认换成"依赖图 + 环打破"的调度器。

【源码证据】`org/hibernate/action/queue/internal/GraphBasedActionQueue.java:49-72`：

```java
/// ActionQueue implementation using FlushCoordinator for graph-based flush scheduling.
/// This implementation replaces the traditional action queue execution with graph-based
/// dependency ordering and cycle breaking.
public class GraphBasedActionQueue implements ActionQueue {
    private final List<OrphanRemovalAction> orphanRemovals;   // L65
    private final List<AbstractEntityInsertAction> insertions; // L66
    private final List<EntityUpdateAction> updates;            // L67
    ...
    private final List<EntityDeleteAction> deletions;          // L72
```

【源码证据】`org/hibernate/action/queue/internal/FlushCoordinator.java:490-499`——阶段顺序（与历代文档一致，值得背）：

```
孤儿集合移除 → 孤儿实体移除 → INSERT → UPDATE → 集合(排队操作/移除/更新/创建) → DELETE
```

图调度的价值：同阶段内按跨表依赖重排（如先插父表再插子表），自引用外键表按 ordinal 拆组避免"假环"（`FlushCoordinator.java:527-544` 的 `groupOperations` 按 `StatementShapeKey`——表+类型+SQL 形状——分组，顺带**为 JDBC 批量做好准备**）。执行末端 `session.getJdbcCoordinator().executeBatch()`（`GraphBasedActionQueue.java:485`）统一冲刷 JDBC 批。

乐观锁的最后一道防线也在这个队列：`EntityVerifyVersionProcess`（`action/internal/EntityVerifyVersionProcess.java:29-67`）作为事务完成前进程，读库比对版本（L55-56），不符抛 `OptimisticEntityLockException`。

## 6.6 加载：DefaultLoadEventListener 的三级漏斗

【源码证据】`org/hibernate/event/internal/DefaultLoadEventListener.java:553-576`（doLoad）：

```java
if ( session.getPersistenceContextInternal().containsDeletedUnloadedEntityKey( keyToLoad ) ) return null;
else if ( session.getCacheMode() != CacheMode.REFRESH_SESSION ) {
    final var persistenceContextEntry =
            loadFromSessionCache( keyToLoad, event.getLockOptions(), options, session ); // ① 一级缓存
    final Object entity = persistenceContextEntry.entity();
    if ( entity != null && persistenceContextEntry.isManaged() ) {
        initializeIfNecessary( entity );
        return entity;                                    // ② 命中即返回（同一实例！）
    }
    ...
    else return load( event, persister, keyToLoad );      // ③ 未命中 → L2 → DB
```

- ①②：一级缓存命中逻辑在 `loader/internal/CacheLoadHelper.java:74-93`（检查 EntityEntry 状态：REMOVED/类型不符/Managed）。
- ③ `loadFromCacheOrDatasource`（`DefaultLoadEventListener.java:601-618`）：先试二级缓存（`loadFromSecondLevelCache`），miss 则 `loadFromDatasource` → `persister.load(...)`（L636-642）。
- `persister` 是 `SingleTableEntityPersister extends AbstractEntityPersister`（`persister/entity/SingleTableEntityPersister.java:59`）——实体持久化器是"SQL 生成 + 结果映射 + 快照 + 锁"的集大成者，每个实体类一个。

## 6.7 懒加载：Byte Buddy 代理与 LazyInitializationException

**白话**：`@ManyToOne(fetch=LAZY)` 与集合默认给你的是"空壳"——单值关联是 Byte Buddy 生成的实体子类代理，集合是 `PersistentBag` 包装。第一次真正读它时才发 SQL；如果那时 Session 已关闭，就抛最著名的 `LazyInitializationException`。

【源码证据】`org/hibernate/proxy/pojo/bytebuddy/ByteBuddyProxyFactory.java:36-51、58-80`：

```java
// postInstantiate：每个实体类只生成一次代理类并缓存
proxyClass = byteBuddyProxyHelper.buildProxy( persistentClass, this.interfaces );
...
public HibernateProxy getProxy( Object id, SharedSessionContractImplementor session) {
    final var interceptor = new ByteBuddyInterceptor( entityName, persistentClass, interfaces, id, ...);
    ...
    proxyConfiguration.$$_hibernate_set_interceptor( interceptor ); // L78 每个实例配一个拦截器
```

集合端：`PersistentBag`（`collection/spi/PersistentBag.java:36`）所有读方法先过 `AbstractPersistentCollection.read()` → `initialize(false)`（`AbstractPersistentCollection.java:632-654`：`session.initializeCollection(this, writing)`，L648）；Session 不可用时抛 `LazyInitializationException`（`:665-675`）。

单值代理端的抛出点更有解释力（【源码证据】`org/hibernate/proxy/AbstractLazyInitializer.java:168-190`）：

```java
if ( allowLoadOutsideTransaction ) { permissiveInitialization(); }
else if ( session == null ) {
    throw new LazyInitializationException( "Could not initialize proxy [" + entityName + "#" + id
            + "] - no session" );                                  // L175
}
else if ( !session.isOpenOrWaitingForAutoClose() ) {
    throw new LazyInitializationException( "... - the owning session was closed" ); // L179
}
```

异常消息直接说明两种典型成因：`no session`（压根不在事务里访问懒属性）与 `the owning session was closed`（OSIV 关闭后仍在渲染视图时访问）。`hibernate.enable_lazy_load_no_trans=true` 会走 `permissiveInitialization`（L203-262，临时 Session 救急，但每属性一次连接往返，不建议生产使用）。

## 6.8 HQL/JPQL → SQL：两段翻译编译链（核心）

**白话**：6.0 把查询引擎彻底重写：先解析成 SQM（Semantic Query Model，"查询的语义 AST"），再翻译成 SQL AST，最后由**数据库方言的翻译器**生成 SQL 文本。JPQL 和 HQL 走同一条路；Criteria API 则直接产 SQM 节点——三种查询语言殊途同归。

```
HQL/JPQL 字符串
  → ANTLR4 解析（hibernate-core/src/main/antlr/org/hibernate/grammars/hql/HqlLexer.g4 / HqlParser.g4）
  → StandardHqlTranslator（query/hql/internal/StandardHqlTranslator.java:50-68；先 SLL 再回退 LL，L92-119）
  → SemanticQueryBuilder（query/hql/internal/SemanticQueryBuilder.java:288——6300 行 ANTLR 访问者）
  → SQM 树（SqmSelectStatement，query/sqm/tree/spi/select/SqmSelectStatement.java:49）
  → StandardSqmTranslator（query/sqm/sql/internal/StandardSqmTranslator.java:12）→ SQL AST
  → SqlAstTranslatorFactory.buildTranslator（org/hibernate/sql/ast/spi，按方言）→ JdbcSelect（可缓存）
```

从 `em.createQuery(jpql, Class)` 出发的完整调用路径（【源码证据】实际读取）：

1. `AbstractSharedSessionContract.createQuery`（`internal/AbstractSharedSessionContract.java:1717-1731`）→ `interpretHql`（L2079-2080）→ `QueryEngine.interpretHql`（`query/spi/QueryEngine.java:73-75`，**带 HQL 解释缓存**）。
2. `buildHqlSelectionQuery`（`AbstractSharedSessionContract.java:1748-1762`）→ `SelectionQueryImpl`（`query/internal/SelectionQueryImpl.java:112`）。
3. 执行 `list()` 时 `buildSelectQueryPlan`（`SelectionQueryImpl.java:1222-1228`）→ `ConcreteSqmSelectQueryPlan`。
4. 两段翻译（【源码证据】`query/sqm/internal/ConcreteSqmSelectQueryPlan.java:534-573`）：

```java
final var sqmInterpretation = sqmTranslator.translate();     // SQM → SQL AST
...
return new CacheableSqmInterpretation<>( sqmInterpretation.getSqlAst(),
        selectTranslator.translate( jdbcParameterBindings, queryOptions ) ); // SQL AST → JdbcSelect
```

`CacheableSqmInterpretation` 是 `volatile` 缓存字段（`:78`）——同一查询第二次执行**连 SQL AST 都不再重译**，这是 6.x/8.x 查询性能的护城河。

**Criteria 与 SQM**：`NodeBuilder extends HibernateCriteriaBuilder, SqmCreationContext`（`query/sqm/spi/NodeBuilder.java:90`），`HibernateCriteriaBuilder extends CriteriaBuilder`（`query/criteria/HibernateCriteriaBuilder.java:95`）——JPA Criteria 的每次 `cb.equal(...)` 都在直接构造 SQM 节点，后续翻译与 HQL 共享。**这也解释了 5.5.1 节的"版本注"：Spring Data 4.x 让派生查询产出 JPQL 字符串，正是为了进 Hibernate 这条统一的 SQM 流水线（并让 Spring Data 自己的 `QueryEnhancer` 能再加工）。**

## 6.9 事务与批量：resource-local 事务的真实样子

【源码证据】`org/hibernate/internal/AbstractSharedSessionContract.java:1389-1398`：

```java
public Transaction beginTransaction() {
    checkOpen();
    final var transaction = accessTransaction();
    if ( !transaction.isActive() ) transaction.begin();   // 已激活则幂等
    return transaction;
}
```

`TransactionImpl` 包装 `TransactionCoordinator`；resource-local 场景最终落到物理 Connection（【源码证据】`org/hibernate/resource/jdbc/internal/AbstractLogicalConnectionImplementor.java:57-69`）：

```java
public void begin() {
    if ( !doConnectionsFromProviderHaveAutoCommitDisabled() ) {
        getConnectionForTransactionManagement().setAutoCommit( false );  // 就是这么"物理"
    }
    status = TransactionStatus.ACTIVE;
}
```

**commit 与 flush 的钩子关系**（回答"提交时脏数据去哪了"）：`JdbcResourceLocalTransactionCoordinatorImpl.commitNoRollbackOnly()`（`.../jdbc/internal/JdbcResourceLocalTransactionCoordinatorImpl.java:246-296`）先 `beforeCompletionCallback()`（L259-261）→ Session 的 `TransactionObserver.beforeCompletion`（`SessionImpl.java:2072-2079`）：

```java
public void beforeCompletion() {
    if ( isOpen() && getHibernateFlushMode() != FlushMode.MANUAL ) managedFlush(); // 提交前自动 flush
    actionQueue.beforeTransactionCompletion();
    beforeTransactionCompletionEvents();
}
```

然后才 `Connection.commit()`；commit 失败尝试 rollback 并标记 `FAILED_COMMIT`（L91-107）。

**批量写**：`hibernate.jdbc.batch_size` 经 `BatchBuilderImpl`（`engine/jdbc/batch/internal/BatchBuilderImpl.java:40-64`）生成 `BatchImpl`；8.x 里 `FlushCoordinator.groupOperations`（6.5.3 节）按 SQL 形状分组恰好让"同类语句相邻"，最大化 JDBC 批量收益。

## 6.10 二级缓存：RegionFactory SPI

**白话**：一级缓存是 Session 私有，二级缓存是 SessionFactory 级共享（进程/集群）。Hibernate 只定义 SPI，具体存储交给 JCache 等实现。

- SPI 入口：`RegionFactory extends Service, Stoppable`（`cache/spi/RegionFactory.java:43`，`start(SessionFactoryOptions, Map)` L57-63）；实体 region 的读写策略为 `CachedDomainDataAccess`（`cache/spi/access/CachedDomainDataAccess.java:28`）。
- 读取条件（【源码证据】`loader/internal/CacheLoadHelper.java:135-148`）：

```java
final boolean useCache =
        source.getCacheMode().isGetEnabled()               // CacheMode 允许读
                && lockMode.lessThan( LockMode.READ )       // 没在加锁
                && !persister.hasSqlRestrictedAssociations()
                && !persister.isAffectedByEnabledFiltersForCache(...);
```

`CacheMode`（`org/hibernate/CacheMode.java:47-106`）五档（NORMAL/GET/PUT/REFRESH/REFRESH_SESSION）与 JPA 的 `CacheRetrieveMode/CacheStoreMode` 映射。启动时 `SessionFactoryImpl.primeSecondLevelCacheRegions`（`internal/SessionFactoryImpl.java:639-652`）按 `@Cache` 注解聚合预热各区域。Spring Boot 里配 `spring.jpa.properties.hibernate.cache.region.factory_class=org.hibernate.cache.jcache.JCacheRegionFactory` 即启用（hibernate-jcache 模块）。本节只铺 SPI 骨架与读条件；缓存体系的机制全景（JPA 规范缓存、一/二级缓存深读、查询缓存与选型）在**第七章**展开。

## 6.11 Spring 与 Hibernate 的胶水（spring-orm 复盘）

把 5.8 节与本章串起来，spring-orm 的四个关键类各就各位：

| spring-orm 类 | 做什么 | 对接的 Hibernate 侧 |
|---|---|---|
| `LocalContainerEntityManagerFactoryBean` | 扫描实体、构建 persistence unit，产出 EMF Bean；支持后台线程启动（`bootstrapExecutor`） | Hibernate bootstrap 链（6.3）：`MetadataSources → SessionFactoryImpl`（它同时就是 JPA 的 EMF） |
| `SharedEntityManagerCreator` | `@PersistenceContext` 注入点变共享代理（5.8.2） | 线程绑定的真身是 `SessionImpl` |
| `JpaTransactionManager` | doBegin 绑 EM 上线程（5.8.1） | 经 `JpaDialect` 调 `em.getTransaction().begin()` → `JdbcResourceLocalTransactionCoordinatorImpl`（6.9） |
| `HibernateJpaDialect` | ① `beginTransaction`（含 JDBC 连接"曝光"）② 异常转译：`HibernateException`/`ConstraintViolationException` 等 → Spring `DataAccessException` 体系 | 复用《Spring Framework.md》6.6 节的异常翻译思想 |

Hibernate 为容器准备的三个 SPI（由 Spring 实现或配置）：

- **`BeanContainer`**（`resource/beans/container/spi/BeanContainer.java:25-71`，`@JavaServiceLoadable @SPI`）：让 Hibernate 创建实体监听器/转换器等受管组件时"问容器要 Bean"。Spring 提供 `SpringBeanContainer`（spring-orm `orm/jpa/hibernate/SpringBeanContainer.java`），经 `AvailableSettings.BEAN_CONTAINER` 注入。
- **`JtaPlatform`**（`engine/transaction/jta/platform/spi/JtaPlatform.java:28-56`）：JTA 环境下桥接 `UserTransaction/TransactionManager` 与同步回调。
- **`PhysicalNamingStrategy`**（`boot/model/naming/PhysicalNamingStrategy.java:38-46`，`@Incubating(since="6.0")`）：逻辑名 → 物理名的映射（Spring Boot 的驼峰转下划线就靠它）。

**OSIV（Open Session In View）**：Spring 的 `OpenEntityManagerInViewInterceptor/Filter` 在请求开始时提前创建并绑定 EM、请求结束才关闭——让视图渲染阶段也能访问懒属性（把 6.7 节的"session was closed"往后推了一屏）。代价是数据库连接被占用整个请求周期，高并发下是经典争议点；Boot 默认开启并打警告日志，懂原理后按团队约定取舍。

## 6.12 本章小结

- Hibernate 的骨架 = **服务注册表 + 事件监听 + 持久化上下文 + ActionQueue + SQM 编译链**；8.x 的增量是图调度 flush、JPA 4.0 合规、查询缓存。
- 一级缓存 = `StatefulPersistenceContext.entitiesByKey`（Identity Map）+ `entitySnapshotsByKey`（脏检查底片）；脏检查 = 快照 diff（`DirtyHelper`/`Type.isDirty`）。
- flush 顺序（8.x `FlushCoordinator`）：孤儿移除 → INSERT → UPDATE → 集合四类 → DELETE，图调度可在阶段内按外键依赖重排。
- 查询三语言（HQL/JPQL/Criteria）统一编译到 SQM → SQL AST → 方言 SQL，两级缓存（HQL 解释缓存 + JdbcSelect 缓存）。
- 懒加载异常的两种成因（no session / session closed）直接写在 `AbstractLazyInitializer` 的分支里；OSIV 是 Spring 给出的"以连接换便利"的解法。

# 七、缓存全景：JPA 规范的缓存与 Hibernate 一级/二级缓存

**白话**："缓存"是 JPA/Hibernate 里最常被混为一谈的一组概念。本章先把词汇对齐，再分别深读：**JPA 规范定义了什么**（持久化上下文的实例唯一性 + 可选的"共享缓存"四件套）、**一级缓存**（会话缓存，就是持久化上下文的账本，6.4 节的数据结构在这里补全机制视角）、**二级缓存**（SessionFactory 级共享缓存：并发策略、读/写路径、失效）、**查询缓存**（把"一次查询的结果"也缓存起来，默认关闭）。

## 7.1 术语对齐：规范词汇与 Hibernate 词汇

| 概念 | JPA 规范叫法 | Hibernate 叫法 | 缓存内容 | 作用域 | 失效方式 |
|---|---|---|---|---|---|
| 会话级缓存 | **持久化上下文**（必选，规范核心语义之一） | **一级缓存** | 实体实例 + `EntityEntry` + 数据库快照 + 集合台账 | `EntityManager`（Session） | `detach/evict`、`clear`、会话关闭 |
| 共享缓存 | **shared cache**（可选特性，JPA 2.0 起标准化） | **二级缓存**（L2） | 实体的**脱管状态 + 版本号**（不是实例本身） | `EntityManagerFactory`（SessionFactory） | 写路径锁定/失效 + 主动 evict |
| 查询结果缓存 | ✗（规范未定义） | **查询缓存**（扩展） | 一次查询的结果（实体 id 列表等）+ 所属表集合 | SessionFactory | 时间戳区域逐表失效 |
| 自然 id 缓存 | ✗ | **natural-id 缓存**（扩展） | 自然 id → 主键映射 | SessionFactory | 随实体数据失效 |

> 一句话记忆：**规范只管"实例唯一"和"共享缓存的行为开关"，不管存储与并发协议——介质、并发策略、查询缓存都是 Hibernate 的自由发挥，也是它的工程深度所在。**

## 7.2 JPA 规范定义的缓存

### 7.2.1 规范给的两样东西

**① 持久化上下文的实例唯一性**。规范要求：同一持久化上下文中，具有相同持久化身份（同一实体类 + 同一主键）的实体实例**至多一个**。`em.find()` 第二次命中内存、两次 `findById` 返回 `==` 相同引用（Spring Data 场景，事务内），都是这条规范语义的体现——它的一级缓存身份是被"规范要求"的，不是 Hibernate 的额外发明。

**② 可选的共享缓存（shared cache）**。JPA 2.0 把"SessionFactory 级共享缓存"纳入规范，给了一组**声明与开关**：

- **`@Cacheable(true/false)`**（类级注解）：该实体是否进共享缓存；
- **`shared-cache-mode`**（`persistence.xml` 五选一）：`ALL`（全部缓存，除非 `@Cacheable(false)`）、`NONE`（全不缓存）、`ENABLE_SELECTIVE`（默认语义：只缓存 `@Cacheable` 的）、`DISABLE_SELECTIVE`（全缓存，`@Cacheable(false)` 除外）、`UNSPECIFIED`（交由 provider 默认）；
- **`Cache` API**：`emf.getCache()` 拿到（provider 不支持时规范允许抛 `UnsupportedOperationException`），方法只有四个：`contains(cls, id)`、`evict(cls)`、`evict(cls, id)`、`evictAll()`；
- **读写模式**：`CacheRetrieveMode`（`USE`/`BYPASS`，读时查不查缓存）与 `CacheStoreMode`（`USE`/`BYPASS`/`REFRESH`，写时更不更缓存），可经 `em.setCacheRetrieveMode/setCacheStoreMode` 或属性键 `jakarta.persistence.cache.retrieveMode/storeMode` 设定。

注意规范的四件套全部是**行为开关**：存不存（@Cacheable/shared-cache-mode）、删不删（Cache API）、读不读写不写（retrieve/store 模式）。存到哪、多线程怎么同步、脏了怎么办——规范一个字没说。

### 7.2.2 Hibernate 侧的 Cache API 桥接

【源码证据】`org/hibernate/Cache.java:115、120-343`：

```java
public interface Cache extends jakarta.persistence.Cache {          // L115 先满足规范
    SessionFactory getSessionFactory();                              // L120
    boolean containsEntity(@Nonnull Class<?> entityClass, @Nonnull Object identifier);  // L136
    void evictEntityData(@Nonnull Class<?> entityClass, @Nonnull Object identifier);    // L158
    void evictEntityData();                                                              // L181 全部实体
    void evictNaturalIdData(...);                                    // L215-232
    boolean containsCollection(@Nonnull String role, @Nonnull Object ownerIdentifier);  // L250
    void evictCollectionData(...);                                   // L262-281
    void evictQueryRegion(@Nonnull String regionName);               // L308
    void evictRegion(@Nonnull String regionName);                    // L325 按区域名全清
    void evictAll();                                                 // L337 = 规范的 evictAll
```

规范要求的四个方法之外，Hibernate 扩展出**按实体/集合角色/自然 id/查询区域/任意区域名**的精细化逐出（`Cache.java:26` 的 javadoc 开篇即点明受 `jakarta.persistence.Cacheable` 与 `org.hibernate.annotations.Cache` 双注解驱动）。实现落在 `cache/internal/EnabledCaching`（启用时，`SessionFactoryImpl.java:274` 拿到的 `CacheImplementor` 即它），未启用则是 `DisabledCaching`。

### 7.2.3 Hibernate 的 CacheMode：读写模式的五档并集

Hibernate 在规范的两维之前（6.x 就有）定义了五档 `CacheMode`，它就是 retrieve/store 两维的组合枚举：

【源码证据】`org/hibernate/CacheMode.java:47-67`（javadoc L19 另注明：`CacheStoreMode#REFRESH` 必然伴随 `CacheRetrieveMode#BYPASS`）：

```java
NORMAL( CacheStoreMode.USE,     CacheRetrieveMode.USE ),     // 读+写（默认）
IGNORE( CacheStoreMode.BYPASS,  CacheRetrieveMode.BYPASS ),  // 与缓存完全隔绝
GET(    CacheStoreMode.BYPASS,  CacheRetrieveMode.USE ),     // 只读（不回写）
PUT(    CacheStoreMode.USE,     CacheRetrieveMode.BYPASS ),  // 只写（不读旧值）
REFRESH( CacheStoreMode.REFRESH, CacheRetrieveMode.BYPASS ), // 强制回写覆盖
// REFRESH_SESSION：仅作用于当前会话的变体
```

查询级的规范模式在 `SelectionQueryImpl` 与 CacheMode 互转（`query/internal/SelectionQueryImpl.java:539-555`）——也就是说：**用规范 API 写出的 retrieve/store 模式，最终落到 Hibernate 内部就是这个五档枚举**，6.6/6.10 节的 `cacheMode.isGetEnabled()/isPutEnabled()` 分支判断的正是它。

### 7.2.4 规范留白的清单（本章后三节的主角）

存储介质（RegionFactory 插件）、并发协议（四档 AccessType）、集合粒度的缓存与失效、查询缓存、自然 id 缓存、批量失效的时间戳机制——以下全部是 Hibernate 扩展。

## 7.3 一级缓存：持久化上下文的账本（机制深读）

### 7.3.1 三本账（复述 + 补全）

6.4.1 节已给出数据结构，从"缓存"视角重述其角色分工：

| 账本 | 键 | 内容 | 缓存意义 |
|---|---|---|---|
| `entitiesByKey`（`StatefulPersistenceContext.java:118`） | `EntityKey`（实体名+id） | `EntityHolder`（实例 + `EntityEntry` 状态/版本/锁） | **identity map**：同 id 同实例 |
| `entitySnapshotsByKey`（`:128`） | `EntityKey` | 装载时刻的属性值快照 | **脏检查底片**：diff 的右操作数 |
| `collectionEntries`（`:134`） | 集合包装实例 | `CollectionEntry`（所属实体、快照、脏标记） | 集合的增量写编排 |

### 7.3.2 生命周期：账本随会话生灭

- **入账**：`find/查询行解析/persist/merge` 的产物全部注册进持久化上下文（6.4.2、6.6）。
- **出账三个口**：`em.detach(obj)`（Hibernate 同义词 `Session.evict`——注意这是 Hibernate 的方言方法，规范叫 detach）、`em.clear()`（清空整个持久化上下文）、会话关闭。**`flush()` 不出账**——它只是把动作队列执行掉，实体仍是 managed、快照刷新为新值。
- **强引用驻留**：账本持有实体强引用直到出账。一次查询拉十万行，十万个实体全在会话里——这就是"一级缓存内存问题"的根源。

### 7.3.3 三重作用（为什么它不只是"缓存"）

1. **同一性保证**：`DefaultLoadEventListener` 先查持久化上下文（6.6 节 ①②），命中直接返回既有实例——业务层 `==` 语义与"一份数据一份对象"由此成立。
2. **写缓冲（Unit of Work）**：persist/remove 只登记动作（6.4.2），flush 才批量发 SQL——一级缓存同时也是"未落库变更"的缓冲区。
3. **快照账本**：没有 `loadedState` 就没有脏检查（6.5.2）——一级缓存还兼着"数据库最后已知状态"的职责。

### 7.3.4 边界与无缓存形态

- **按 key，不按查询**：一级缓存只对 `find/getReference` 和"以 id 为入口"的加载直接命中；JPQL 查询永远发 SQL（除非查询缓存，7.4.5），只是**结果行会入账**——所以"同会话第二次跑同一 JPQL"仍会命中数据库。
- **StatelessSession：没有账本的会话**。6.2 节的 `SharedSessionContractImplementor` 是 `Session` 与 `StatelessSession` 的共同契约（`engine/spi/SharedSessionContractImplementor.java:80-81`），后者无持久化上下文：不缓存、不脏检查、不级联，每次操作直发 SQL——Hibernate 官方给批量 ETL 留的"裸通道"，对应"一级缓存全关"的形态。

### 7.3.5 实践：大集合写入的周期化

内存驻留的标准解法是"周期化落库"：每 N 条 `flush()` + `clear()`（动作执行掉、账本清空，快照/条目全部释放）。Spring Data 侧对应：`saveAll` 超大集合时自行分段（`saveAll` 本身只是循环 `save`，5.4.1），或改用 JDBC 批量（`JdbcTemplate.batchUpdate`）绕开持久化上下文。读侧同理：大结果集用分页/滚动（`findBy(…, ScrollPosition)`，5.4.4）代替全量 `findAll`。

## 7.4 二级缓存：RegionFactory 插件体系（机制深读）

6.10 节已铺了 SPI 骨架与读条件，本节补全"谁进缓存、怎么进、怎么失效、查询缓存"四个机制面。

### 7.4.1 默认关闭与组件地图

二级缓存**默认不启用**：`RegionFactoryInitiator` 未配置 `hibernate.cache.region.factory_class` 时直接装配 `NoCachingRegionFactory.INSTANCE`（【源码证据】`cache/internal/RegionFactoryInitiator.java:68-73`：注释明说 "We should immediately return NoCachingRegionFactory if either…"，实现类 `NoCachingRegionFactory.java:26`）。启用后组件分四层：

```
CacheImplementor（EnabledCaching）            —— org.hibernate.Cache API 的门面
 ├ RegionFactory（SPI，JCache 等 provider 实现）—— 造 region、给时间戳
 │   ├ DomainDataRegion（实体+集合+自然 id 三合一区域）
 │   ├ QueryResultsRegion（查询结果区域）
 │   └ TimestampsRegion（逐表"最后更新时间"区域）
 ├ CachedDomainDataAccess（每实体的读写策略执行器：EntityDataAccess/CollectionDataAccess/…）
 └ TimestampsCache（查询缓存的失效协调器，7.4.5）
```

### 7.4.2 谁进缓存：双注解与四档并发策略

实体进缓存由两套注解声明（`Cache.java:26` javadoc 点明二者等效驱动）：JPA 的 `@Cacheable`（配合 `shared-cache-mode`）只回答"进不进"；Hibernate 的 `org.hibernate.annotations.Cache(usage=…, region=…)` 还能指定**并发策略与区域名**。

【源码证据】`cache/spi/access/AccessType.java:28-50`（javadoc 语义摘译）：

```java
public enum AccessType {
    /** 只读。数据可增删，不可变更——不可变字典表的最高吞吐选择 */
    READ_ONLY,
    /** 读写。变更期间用"软锁"管理并发；有 @Version 才好用 */
    READ_WRITE,
    /** 读写。变更前后各失效一次，比 READ_WRITE 更易出现短暂不一致，但吞吐更高 */
    NONSTRICT_READ_WRITE,
    /** 读写。配合 JTA 事务维护"硬锁"——缓存与数据库同事务提交 */
    TRANSACTIONAL;
```

四档策略对应 `CachedDomainDataAccess` 的行为差异（锁不锁、何时失效）。读写缓存的"软锁"本质：更新前 `lockItem` 把缓存条目变成"锁对象"（并发读者这段时间 miss 而不是读到脏值），提交后 `afterUpdate` 用新版本解锁回写。

### 7.4.3 读路径与 putFromLoad：从区域里读什么、什么时候写

**读**（6.10 节已引 `CacheLoadHelper.java:135-148` 的四条件）：L2 中存的是 `CacheEntry`——**脱管的属性状态数组 + 版本号**（不是实体实例）；命中后按状态重建受管实体入持久化上下文（并入账、拍快照）。

**写（putFromLoad）**：DB 装载完成后回填 L2。装载器是 6.5.2 节出现过的 `EntityInitializerImpl`：

【源码证据】`sql/results/graph/entity/internal/EntityInitializerImpl.java:1903-1937、1945-1960`：

```java
private void putInCache(...) {
    final var cacheEntry = data.concreteDescriptor.buildCacheEntry(           // 状态+版本 打包
            data.entityInstanceForNotify, resolvedEntityState, version, session);
    final Object cacheKey = cacheAccess.generateCacheKey(                     // 含租户/实体名前缀
            data.entityKey.getIdentifier(), rootEntityDescriptor, session.getFactory(), ...);
    if ( persistenceContext.wasInsertedDuringTransaction( ... ) ) {           // 本事务刚插入的行：特殊处理
        putRecentlyInsertedInCache( ... );                                    // 非事务缓存只能等 afterInsert
    } else { putInCache( data, session, version, cacheAccess, cacheKey, cacheEntry ); }
}
// 内层：minimalPuts 开关（已有条目则不覆盖，减少缓存写）+ putFromLoad
cacheContentChanged = cacheAccess.putFromLoad( session, cacheKey,
        data.concreteDescriptor.getCacheEntryStructure().structure( cacheEntry ),
        version, minimalPutsEnabled );
// 统计：statistics.entityCachePut(...)（:1971-1976）
```

两个工程细节值得圈点：① **同事务刚插入又被读回的实体**单独处理（`:1924-1936` 注释：常见于 `refresh` 或 `clear` 后再 load，避免破坏尚未提交的锁）；② `minimalPuts`（`hibernate.cache.use_minimal_puts`）权衡"少写缓存"与"读到旧值"。

### 7.4.4 写路径：更新/删除如何失效缓存

【源码证据】`action/internal/EntityUpdateAction.java`（flush 时执行的动作，6.5.3 节）：

```java
// 执行 UPDATE SQL 之前：lockItem 上软锁（:457）
lock = cache.lockItem( session, cacheKey, previousVersion );
...
// SQL 之后的缓存收尾（updateCacheItem，:338-364）：
if ( isCacheInvalidationRequired( persister, session ) || entry.getStatus() != Status.MANAGED
        || FilteredAssociationState.hasFilteredAssociations( entry ) ) {
    cache.remove( session, cacheKey );                        // :348 直接逐出
} else if ( session.getCacheMode().isPutEnabled() ) {
    cacheEntry = buildStructuredCacheEntry( ... );            // :352 新状态打包
    final boolean put = updateCache( cache, persister, previousVersion, cacheKey ); // → afterUpdate :591
}
...
cache.unlockItem( session, cacheKey, lock );                  // :565 释放软锁
```

失效判定（`:366-373`）：`persister.isCacheInvalidationRequired() || CacheMode.GET || CacheMode.IGNORE` 任一成立即 `remove`——**"先锁、后更新、再解锁回写"** 是 READ_WRITE 的三拍；NONSTRICT_READ_WRITE 则退化为"更新前后各失效一次"（更脆弱但更快）；TRANSACTIONAL 把锁交给 JTA 资源。删除路径（`EntityDeleteAction`）与集合路径同理；另有一个兜底器 `CollectionCacheInvalidator`（`cache/internal/CollectionCacheInvalidator.java:38-39`，一个 `Integrator` + 三种集合事件监听器）：当集合通过**绕过会话语义的批量操作**增删改元素时，自动逐出受影响实体/集合的缓存条目（`:59-78`）——防"脏缓存"的最后保险。

### 7.4.5 查询缓存：默认关闭的高风险高收益选项

**白话**：实体缓存按 id 存取，查询缓存把"某条 SQL + 某些参数"的结果整包缓存。代价是**失效极敏感**：查询涉及的任何一张表被任何一笔写入（哪怕与结果无关的行）改动，所有依赖该表的查询缓存整体作废。

**读路径**（【源码证据】`sql/exec/internal/JdbcSelectExecutorStandardImpl.java:276-317`）：

```java
final boolean queryCacheEnabled = factory.getSessionFactoryOptions().isQueryCacheEnabled(); // :276 默认 false
final boolean cacheable = queryCacheEnabled
        && canBeCached
        && queryOptions.isResultCachingEnabled() == Boolean.TRUE;    // :281-284 三条件与
if ( cacheable && cacheMode.isGetEnabled() ) {
    final Set<String> querySpaces = jdbcSelect.getAffectedTableNames();  // :291 查询涉及哪些表
    final var queryCache = factory.getCache().getQueryResultsCache( queryOptions.getResultCacheRegionName() );
    queryResultsCacheKey = QueryKey.from( jdbcSelect.getSqlString(),     // :303 键=SQL+limit+参数绑定
            queryOptions.getLimit(), executionContext.getQueryParameterBindings(), session);
    cachedResults = queryCache.get( queryResultsCacheKey, querySpaces, session ); // :310 读+时效校验
```

缓存的内容是**实体 id（与标量值）**，命中后仍要走 6.6 的按 id 装载（二级/一级缓存还能再拦一道）。

**失效机制——时间戳区域**（【源码证据】`cache/internal/TimestampsCacheEnabledImpl.java`）：每张表在 `TimestampsRegion` 里记一个"最后更新时间"；查询结果缓存时附上当时的时间戳，命中前逐表校验：

```java
// isUpToDate（:120-132）→ isSpaceOutOfDate（:134-153）：
final Long lastUpdate = getLastUpdateTimestampForSpace( space, session );
if ( lastUpdate == null ) return false;              // 无记录视为过期（保守）
...
return lastUpdate >= timestamp;                       // :153 表的更新时间 ≥ 结果缓存时间 → 已过期
```

写侧失效在动作执行前登记未来时间戳（`preInvalidate`，`:38-78`：`nextTimestamp() + timeout`，事务回滚则作废）、提交后落定（`invalidate`，`:81-118`）。

**怎么开**：

```yaml
spring.jpa.properties.hibernate.cache.use_query_cache: true   # 全局开关（:276 那道条件）
spring.jpa.properties.hibernate.cache.region.factory_class: org.hibernate.cache.jcache.JCacheRegionFactory
```

查询声明侧，Spring Data JPA 没有专属注解，用 `@QueryHints` 透传 Hibernate 的 cacheable 提示（键常量 `org.hibernate.jpa.HibernateHints.java:134`：`HINT_CACHEABLE = "org.hibernate.cacheable"`）：

```java
@Query("select u from User u where u.active = true")
@QueryHints(@QueryHint(name = "org.hibernate.cacheable", value = "true"))
List<User> findAllActive();
```

（`SelectionQueryImpl.setCacheable`（`:519-526`）即被此 hint 驱动；命名区域用 `org.hibernate.cacheRegion`。）**慎用理由**：失效粒度是"表"，写多读少的表上命中率趋近于零还白付序列化成本；典型适用面是"几乎只读的配置表 + 固定条件的列表页"。

### 7.4.6 实现选型与配置速查

- **Provider**：6.x 起核心不再内置任何缓存实现（旧 Ehcache/Infinispan 集成已移出），统一走 JCache（JSR-107）：`hibernate-jcache` 模块（`hibernate-jcache/src/main/java/org/hibernate/cache/jcache/internal/JCacheRegionFactory.java`），底下再选 Ehcache 3 / Infinispan / Caffeine 的 JCache 封装；也保留自研 SPI 可接 Redis 等分布式实现。
- **常用配置**（`spring.jpa.properties.` 前缀下）：

| 配置 | 作用 | 源码锚点 |
|---|---|---|
| `hibernate.cache.region.factory_class` | 开启 L2 + 指定 provider | `RegionFactoryInitiator`（7.4.1） |
| `hibernate.cache.use_query_cache` | 开启查询缓存（默认 false） | `JdbcSelectExecutorStandardImpl.java:276` |
| `hibernate.cache.use_minimal_puts` | 命中已有条目不覆盖 | `EntityInitializerImpl.java:1945-1947` |
| `hibernate.cache.region_prefix` | 区域名前缀（多应用共库隔离） | `generateCacheKey` 链路 |
| `hibernate.generate_statistics` | 打开命中率统计 | `entityCachePut` 等（`:1971-1976`） |

- **验证闭环**：`Statistics` → `CacheRegionStatistics`（put/hit/miss 计数）+ 日志 `org.hibernate.stat`——**没有命中率数据的二级缓存决策都是猜**。
- Spring Data JPA 侧主动逐出：注入 `jakarta.persistence.Cache`（或 Hibernate 扩展 `org.hibernate.Cache`）调用 7.2.2 的 `evict*`；也可 `Session.getSessionFactory().getCache()`。

### 7.4.7 适用性判断清单（放进决策文档前先过一遍）

1. 读多写少（写频率至少低一个数量级）？ 2. 可容忍"提交后、失效前"的短暂陈旧（或愿意付 TRANSACTIONAL 的事务成本）？ 3. 数据按主键访问为主（二级缓存只加速按 id 的加载与 `find`，JPQL 查询本身不缓存）？ 4. 集合缓存能连带声明（只缓存实体不缓存集合，关联集合仍会发 SQL）？ 5. 有集群失效广播方案（本地缓存多实例会互相陈旧）？ —— 有一条答不上来，先别开。

## 7.5 一级 vs 二级：总表

| 维度 | 一级缓存（持久化上下文） | 二级缓存（共享缓存） |
|---|---|---|
| 规范地位 | 必选（规范语义本身） | 可选（JPA 2.0 起标准化开关） |
| 缓存内容 | **实体实例本身** + 快照 + 集合台账 | **脱管状态 + 版本**（命中后重建实例） |
| 作用域/生命周期 | 一个 `EntityManager`（一个事务） | SessionFactory（应用级），可集群 |
| 一致性 | 严格（单线程账本 + 脏检查） | 最终（并发策略决定强度） |
| 失效 | detach/clear/会话关闭 | 锁定-失效协议 + 主动 evict + 时间戳 |
| 拦截点 | `DefaultLoadEventListener` 第一级（6.6） | 第二级；查询缓存再叠加一层 |
| 典型风险 | 大结果集内存驻留 | 脏读/失效风暴/集群不同步 |

## 7.6 本章小结

- 规范只定义两样：持久化上下文的实例唯一性（一级缓存之源）+ 共享缓存的行为开关（`@Cacheable`/`shared-cache-mode`/`Cache` API/retrieve-store 模式）；Hibernate 的 `CacheMode` 五档是规范两维的组合。
- 一级缓存 = 三本账（实例/快照/集合），三重作用（同一性、写缓冲、脏检查底片），随会话生灭，flush 不清账；`StatelessSession` 是它的"关闭形态"。
- 二级缓存默认关（`NoCachingRegionFactory`），开启后经 `RegionFactory` 造区域；L2 存"状态+版本"不存实例；读写三拍 `lockItem → SQL → afterUpdate/unlockItem`，失效判定含 CacheMode 与过滤器；`putFromLoad` 在 DB 装载后回填。
- 查询缓存默认关：键 = SQL+limit+参数，命中前过逐表时间戳校验；失效粒度是表，写多读少场景慎开；Spring Data 里用 `@QueryHints` 传 `org.hibernate.cacheable`。

---

# 八、贯通视图：三条时间线看懂全貌

如果说前六章是"分镜头"，本章把镜头摇起来，看三条完整的旅程——每一步都标注前文的小节号，可回查。

## 8.1 时间线一：应用启动（装配一台查询机器）

```
Spring 容器 refresh
 ① 解析 @Configuration：遇到 @EnableJpaRepositories → ImportBeanDefinitionRegistrar 回调
    └ JpaRepositoriesRegistrar（5.2）→ RepositoryConfigurationDelegate 扫描 basePackages
       → 每个接口注册一个 JpaRepositoryFactoryBean BeanDefinition（4.2）
 ② LocalContainerEntityManagerFactoryBean.afterPropertiesSet（6.11）
    └ Hibernate bootstrap（6.3）：MetadataSources.buildMetadata() → MetadataImpl
       → SessionFactoryBuilderImpl.build() → SessionFactoryImpl（= JPA 的 EntityManagerFactory）
       → SessionFactoryImpl 构造器装配：ServiceRegistry/EventEngine/QueryEngine/CacheImplementor/ActionQueueFactory
 ③ 每个 JpaRepositoryFactoryBean.afterPropertiesSet（5.2）
    ├ createRepositoryFactory(em)（4.3）：JpaRepositoryFactory + 查询元数据工厂 + 代理后置处理器
    ├ getRepository(接口)（4.4）：
    │   ├ RepositoryMetadata：读接口泛型 T/ID
    │   ├ target = new SimpleJpaRepository(entityInformation, em)（5.4）
    │   ├ fragments = Querydsl 贡献者 + 自定义实现（5.3）
    │   ├ ProxyFactory：interfaces + TransactionalProxy 标记
    │   ├ QueryExecutorMethodInterceptor：为每个查询方法 resolveQuery →
    │   │    ├ @Query/@NamedQuery → SimpleJpaQuery / NativeJpaQuery（5.5.2）
    │   │    └ 方法名 → PartTree → PartTreeJpaQuery + JpaQueryCreator（JPQL 字符串，5.5.1）
    │   └ getProxy() → JDK 动态代理
    └ 容器注册：bean 名 = 接口名首字母小写，bean 类型 = 接口
 ④ @EnableJpaAuditing（可选）→ JpaAuditingRegistrar → jpaAuditingHandler + AuditingEntityListener（5.9）
```

启动完成后：**所有 SQL 都还没编译过**（JPQL 字符串就位；SQM/SQL 编译在首次执行并缓存，6.8 节）。

## 8.2 时间线二：一次 `userRepository.save(user)` 的全程

```
业务代码 save(user)（user 是 @Version Integer version = null 的新实体）
 ① AOP：TransactionInterceptor 命中 SimpleJpaRepository.save 上的 @Transactional（类级注解见 5.4 开头）
    └ JpaTransactionManager.doBegin（5.8.1）
       ├ createEntityManagerForTransaction() → emf.createEntityManager() → SessionImpl
       ├ jpaDialect.beginTransaction(em) → em.getTransaction().begin()
       │   └ JdbcResourceLocalTransactionCoordinatorImpl → Connection.setAutoCommit(false)（6.9）
       └ TransactionSynchronizationManager.bindResource(emf, holder)（EM 上线程，5.8.1 :444-447）
 ② 代理分发：QueryExecutorMethodInterceptor 无此方法 → ImplementationMethodExecutionInterceptor
    → target.save() → entityInformation.isNew(user)（@Version==null → true，5.4.1）
    → entityManager.persist(user)
       └ SessionImpl.persist → PersistEvent → DefaultPersistEventListener
          ├ TRANSIENT 分支（6.4.2）→ saveWithGeneratedId
          │   （IDENTITY 主键：此刻排入 ActionQueue 的是 EntityIdentityInsertAction，
          │    INSERT 延迟到 flush 执行，执行后才取回自增键——见下③）
          └ makeEntityManaged：user 进持久化上下文，ActionQueue.insertions += EntityAddAction
 ③ 事务提交：AbstractPlatformTransactionManager.commit → doCommit
    └ Connection.commit 前：TransactionCoordinator.beforeCompletionCallback（6.9）
       └ SessionImpl TransactionObserver.beforeCompletion → managedFlush()（非 MANUAL 时）
          ├ DefaultFlushEventListener → flushEverythingToExecutions
          │   └ 脏检查每个受管实体（6.5.2：当前值 vs loadedState 快照）
          └ GraphBasedActionQueue.executeActions（6.5.3）
              ├ 或无动作则跳过；有动作按 孤儿移除→INSERT→UPDATE→集合→DELETE 编排
              └ EntityAddAction 执行 → persister.generateInsert → PreparedStatement + JDBC batch
                （IDENTITY 策略：执行后 getGeneratedKeys 取回 id 塞回 user）
          └ Connection.commit()（6.9）→ 事务完成回调（@PreUpdate 已在 flush 前触发，审计 5.9）
 ④ 返回：persist 分支返回原对象（managed 状态，此后改字段 flush 时仍会自动 update）
```

对照记忆点：**save() 里没有一条 SQL**；SQL 全部发生在②③之间的 flush（提交前钩子）里；"insert 还是 update"由 `isNew` 决定，"发不发 update"由脏检查决定。

## 8.3 时间线三：一次 `findByLastnameAndAgeGreaterThan("张", 18)` 的全程

```
业务代码调用（假设已存在事务，OSIV 或业务事务已绑定 EM）
 ① 代理：QueryExecutorMethodInterceptor.doInvoke → 启动期缓存好的 PartTreeJpaQuery.execute（4.5）
 ② 绑定参数 → PartTreeJpaQuery.createQuery(accessor)
    └ JpaQueryCreator 完整产 JPQL：
       "select u from User u where u.lastname = ?1 and u.age > ?2 order by u.age desc"（5.5.1）
 ③ JpaQueryExecution（CollectionExecution，5.6 表前）→ query.getResultList()
    └ em.createQuery(jpql, User.class) → SessionImpl/AbstractSharedSessionContract.createQuery（6.8 步骤1）
       ├ interpretHql：ANTLR4 解析 → SemanticQueryBuilder → SQM（缓存命中则跳过）
       └ SelectionQueryImpl.list() → ConcreteSqmSelectQueryPlan 两段翻译（6.8 步骤4）
          ├ StandardSqmTranslator：SQM → SQL AST（join 推导、路径解析）
          └ 方言 SqlAstTranslator：SQL AST → "select ... from user u0_ where u0_.lastname=? and u0_.age>? order by ..."（JdbcSelect 缓存）
 ④ JDBC 执行 → ResultSet
    └ 结果行 → EntityInitializerImpl 逐行装配（6.5.2 末）：
       ├ 每行 new User + 填充标量属性 → addEntry(LOADING, resolvedEntityState) + takeSnapshot（快照=脏检查底片）
       ├ 主键已在持久化上下文？→ 直接复用既有实例（一级缓存身份保证，6.6）
       └ 懒关联字段放占位：单值→ByteBuddy 代理（6.7），集合→PersistentBag
 ⑤ 代理层后处理：PageableExecutionUtils（若分页）/投影转换 → 返回 List<User>
 ⑥ 事务结束（若有）：beforeCompletion → flush 脏检查（本查询只读，无 update）→ Connection 释放
```

对照记忆点：方法名解析与 JPQL 生成发生在**启动期**；每次调用只有 ③④ 的真实开销；SQL 首次执行后连 SQM→SQL 的翻译都有缓存。

## 8.4 设计模式视角（对照《Spring Framework.md》8.4 节的方法论）

| 模式 | 在本技术栈中的体现 |
|---|---|
| FactoryBean | `JpaRepositoryFactoryBean` 把动态代理接入容器（4.3）；`LocalContainerEntityManagerFactoryBean` 接入 EMF（6.11） |
| 模板方法 | `AbstractQueryCreator.create/and/or/complete`（4.5→5.5.1）；`AbstractPlatformTransactionManager` 的事务骨架（5.8） |
| 策略族 | `QueryLookupStrategy` 三策略（5.5）；`QueryEnhancerSelector`（5.6）；Hibernate `ServiceRegistry` 全家（连接池/方言/缓存/字节码，6.3） |
| 责任链/拦截器 | 代理的双拦截器分发（4.4）；Hibernate 事件监听器组（6.4.2）；flush 阶段编排（6.5.3） |
| 组合/装饰 | `RepositoryFragments` 组合（5.3）；`PersistentBag` 装饰集合（6.7） |
| Identity Map / Unit of Work | 持久化上下文（6.4）——Fowler PoEAA 的两个经典模式在这里的 Java 实现 |
| 解释器/编译器 | ANTLR4 → SQM → SQL AST → 方言 SQL 的三段编译（6.8） |

---

# 九、附录

## 9.1 关键类速查表（三仓库各取最常用）

**spring-data-commons**

| 类 | 一句话 |
|---|---|
| `RepositoryFactorySupport` | 仓库代理装配总控（getRepository） |
| `QueryExecutorMethodInterceptor` | 查询方法分发拦截器 |
| `PartTree` / `Part` | 方法名语法树 / 关键字枚举 |
| `CrudRepository` / `ListCrudRepository` | CRUD 契约 |

**spring-data-jpa**

| 类 | 一句话 |
|---|---|
| `JpaRepositoriesRegistrar` | @EnableJpaRepositories 的执行者 |
| `JpaRepositoryFactoryBean` → `JpaRepositoryFactory` | FactoryBean → 工厂接力 |
| `SimpleJpaRepository` | CRUD 默认实现（含 Specification/QBE） |
| `JpaQueryLookupStrategy` | 三种查询来源仲裁 |
| `PartTreeJpaQuery` / `JpaQueryCreator` | 方法名 → JPQL |
| `SimpleJpaQuery` / `NativeJpaQuery` | @Query 的 JPQL / 原生 SQL |
| `JpaQueryExecution`（Paged/Modifying/Exists…） | 执行器族 |
| `AbstractJpaQuery` | createQuery 三连（hints/EntityGraph/lock） |
| `JpaMetamodelEntityInformation` | isNew 判定 |
| `AuditingEntityListener` | 审计填充回调 |

**hibernate-core**（相对 `hibernate-core/src/main/java/`）

| 类 | 一句话 |
|---|---|
| `SessionFactoryImpl` / `SessionImpl` | 两大门面实现 |
| `StatefulPersistenceContext` | 一级缓存 + 快照总账 |
| `DefaultPersist/Merge/Delete/Flush/LoadEventListener` | 事件驱动状态机 |
| `GraphBasedActionQueue` / `FlushCoordinator` | flush 动作编排（8.x 图调度） |
| `DefaultFlushEntityEventListener` / `DirtyHelper` | 脏检查 |
| `ByteBuddyProxyFactory` / `AbstractLazyInitializer` | 懒加载代理 |
| `StandardHqlTranslator` / `SemanticQueryBuilder` | HQL → SQM |
| `ConcreteSqmSelectQueryPlan` / `StandardSqmTranslator` | SQM → SQL AST → JdbcSelect |
| `JdbcResourceLocalTransactionCoordinatorImpl` | resource-local 事务协调 |

## 9.2 常见坑与最佳实践（每条都能在前文找到源码根据）

1. **LazyInitializationException**（6.7）：成因两种（no session / session closed）。正解：在事务内完成访问（`@Transactional(readOnly=true)` 方法或 `JOIN FETCH`/`@EntityGraph` 一次抓取，5.5.3）；OSIV 只是把问题推迟，高并发慎用（6.11）。
2. **N+1**：一个列表查询触发 N 次关联加载。武器：`@EntityGraph`（fetchgraph hint）、`JOIN FETCH`、`@BatchSize`（Hibernate 批量加载）。检测：开 `hibernate.generate_statistics` 或 p6spy 看 SQL 条数。
3. **save() 意外 update**（5.4.1）：ID 由雪花算法/应用赋值时，无 `@Version` 的实体 `save()` 走 merge → 多一次 select 且是"复制到受管副本"。正解：加 `@Version`，或实现 `Persistable<T>` 接口接管 isNew。
4. **merge 与 update 的语义差**（6.4.2）：merge 返回受管副本，原对象仍是游离态；继续改原对象不会生效。需要"就地受管"时应让对象保持 managed（在同事务中先加载再修改）。
5. **deleteAllInBatch 不触发级联/回调**（5.4.2）：绕过持久化上下文的单条 delete；需要级联语义时用逐个 delete。
6. **只读事务的收益**（5.4 开头）：`@Transactional(readOnly=true)` 让 Hibernate 开 FlushMode.MANUAL（跳过 flush 脏检查）、驱动做只读优化——查询方法请保证在只读事务里。
7. **复合主键的 findAllById**（5.4.3）：退化为逐个 findById，数据量大时考虑改单键或手动 in 查询。
8. **二级缓存不是默认开启的万灵药**（7.4）：按 region 精确启用 + 收益评估，注意失效与集群同步。
9. **批量写**（6.9）：`hibernate.jdbc.batch_size`（如 50）+ IDENTITY 主键会让 JDBC batch 失效（IDENTITY 需要逐条取回主键）——批量导入场景选 SEQUENCE/UUID 策略。

## 9.3 学习路线（动手向）

1. **先跑通**：Spring Boot + `spring-boot-starter-data-jpa` + H2，写一个 `UserRepository`，开启 `spring.jpa.show-sql=true` 与 `logging.level.org.hibernate.orm.jdbc.bind=TRACE`（看参数绑定）。
2. **验证 isNew**：分别用自增 ID、手动赋值 ID、`@Version` 三种实体测 save 发出的 SQL（对照 5.4.1）。
3. **验证派生查询**：写 `findByLastnameAndAgeGreaterThan`，观察 SQL（对照 8.3 时间线）；故意拼错属性名看启动期异常。
4. **验证脏检查**：`findById` 后改字段不 save，看事务提交时是否 update（对照 6.5.2）；再在无事务环境重复，观察差别。
5. **复现懒加载异常**：`@Transactional` 方法内取实体、方法外访问懒集合，看 `AbstractLazyInitializer` 的两种消息（6.7）。
6. **打开源码**：按 8.1 速查表在 IDEA 中定位类，从 `JpaRepositoryFactory.getRepository` 与 `DefaultFlushEntityEventListener.performDirtyCheck` 两个入口开始读。

## 9.4 结语

Spring Data JPA 的全部代码量并不大，它真正的价值在于把三层各自最强的部分"焊"在了同一个调用链上：**Spring Data 的抽象能力**（一个接口 + 一个方法名就能驱动整条链）、**JPA 的标准语义**（持久化上下文与事务边界）、**Hibernate 的引擎深度**（脏检查、缓存、编译到 SQL 的全链路）。读完本文再回看 `findByLastname("张")` 这一行代码，它身后站着的是：PartTree 语法树、JPQL 生成器、ANTLR4 解析器、SQM 语义模型、SQL AST、方言翻译器、持久化上下文、快照脏检查、ActionQueue 编排、ThreadLocal 事务资源——**简洁是设计出来的，不是省略出来的**。这正是这一系列文档反复印证的主题：框架为你做的事越多，理解其原理的回报就越大。



