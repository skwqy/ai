# Spring Data LDAP 深度源码解析（写给初学者的架构全景）

> **本文基于的源码**：`D:\code\3rd\spring-data-ldap`，版本 **4.2.0-SNAPSHOT**（main 分支，Git commit `c187e57`，2026-10-05），构建目标 **Spring LDAP 4.2.0-M1**、**Spring Data Commons 4.2.0-SNAPSHOT**（均见 `pom.xml` 实证）。对照阅读的 Spring LDAP 源码在 `D:\code\3rd\spring-ldap`（main，4.1.0 之后快照 `b4ba1a34`）。文中所有【源码证据】均为对上述快照实际读取所得。
>
> **姊妹篇**：本文是 `Spring LDAP.md` 的续篇。那篇讲"引擎"——`LdapTemplate`、`ContextSource`、ODM 映射内核；这篇讲"编程模型"——Spring Data 仓库抽象如何架在引擎之上。两篇合起来才是 LDAP 访问的完整图景。
>
> **阅读约定**：每章"先白话、后源码"——先用一两句人话讲清"这是什么、为什么需要它"，再给出源码证据链（`路径/类名.java` + 方法名 + 行号 + 代码片段）。行号只对上述快照精确，读者用 IDEA 打开源码按类名 + 方法名定位即可。

## 如何读这份文档

如果你是初学者，推荐两遍读法：

- **第一遍（建立地图，1 小时）**：只读第一章（总览）每节开头白话段、第二章（与 Spring LDAP 的关系——本文的核心问题）、各章"小结"、第十一章（贯通视图）。目标是能回答：Spring Data LDAP 和 Spring LDAP 是什么关系？`PersonRepository extends CrudRepository` 这个空接口凭什么能干活？
- **第二遍（深入源码）**：对照【源码证据】逐行读。顺序建议：第二章（关系）→ 第三章（装配链）→ 第五章（查询方法）→ 第六章（@Query）→ 第七章（执行层）→ 第八章（Querydsl，可跳）→ 第四章（CRUD 委派，随用随查）→ 第九章（映射层）→ 第十章（生态集成）。

---

# 一、总览：Spring Data LDAP 的定位、设计哲学与整体架构

## 1.1 一句话定位

**Spring Data LDAP 是架在 Spring LDAP 引擎之上的"仓库编程模型"**：它把 `PersonRepository extends CrudRepository<Person, Name>` 这种"只写接口、不写实现"的 Spring Data 风格带到了 LDAP 目录上。官方自述（`pom.xml`）：*"Spring Data integration for LDAP"*。

它和 Spring LDAP 的关系一句话说清（第二章展开全部证据）：

> **Spring LDAP（spring-ldap-core）是引擎：连接、认证、搜索、异常转译、对象-目录映射（ODM）；Spring Data LDAP 是驾驶舱：仓库接口、方法名派生查询、@Query、Querydsl、CDI/Boot 集成。驾驶舱的每一个动作最终都转成对引擎的一次调用。**

类比：Spring LDAP 之于 Spring Data LDAP，约等于 Hibernate/JPA 实现引擎（配合 spring-orm）之于 Spring Data JPA——引擎做脏活，仓库模型给你"接口即实现"的体验。

## 1.2 设计哲学：读源码前先记住五句话

1. **薄，非常薄**。整个项目主源码只有 **42 个 Java 文件**（实测 `find src/main/java -name "*.java" | wc -l`），没有一个类超过 500 行。它不重新发明任何映射或访问逻辑，一切下沉给 spring-ldap-core。
2. **注解是别人的，抽象是自己的**。实体注解直接复用 Spring LDAP 的 `@Entry/@Id/@Attribute/@DnAttribute/@Transient`（`org.springframework.ldap.odm.annotations` 包）；Spring Data LDAP 自己只贡献 `@Query`、`@LdapEncode` 两个注解。识别一个类是不是"LDAP 实体"，看的就是 Spring LDAP 的 `@Entry`（`LdapRepositoryConfigurationExtension#getIdentifyingAnnotations` 返回 `Entry.class`）。
3. **一切汇入 LdapOperations**。仓库基类 `SimpleLdapRepository` 持有两个字段：`LdapOperations`（即 `LdapTemplate` 的接口）和 `ObjectDirectoryMapper`（ODM 引擎接口）。save 是 `create/update`，findById 是 `findByDn`，deleteById 是 `unbind`——没有任何绕开引擎的旁路。
4. **查询有两副面孔，出口只有一个**。方法名派生（PartTree）和 `@Query` 字符串是两副面孔，最终都编译成 Spring LDAP 的 `LdapQuery`（`LdapQueryBuilder` 构建、`ContainerCriteria`/`Filter` 树表达），交给 `LdapOperations.find/findOne` 执行。
5. **LDAP 的天花板就是仓库的天花板**。目录服务没有服务端排序、没有连接池外的分页游标抽象、没有关联关系、没有响应式 API——Spring Data LDAP 也不假装有：`Sort` 被忽略、深分页抛 `UnsupportedOperationException`、`createAssociation()` 直接抛异常、响应式仓库在装配期就被拒绝。

## 1.3 项目分层全景

Maven 单模块工程（`pom.xml`），42 个主源码文件按包分布如下（实测）：

```
org.springframework.data.ldap
├── config/                          [2 文件] XML 命名空间：<ldap:repositories/>
│    └── LdapNamespaceHandler                     （XML 时代的装配入口，沿用至今）
├── core/mapping/                    [6 文件] Spring Data 侧的映射模型（注意：不是 ODM 引擎！）
│    ├── LdapMappingContext                       AbstractMappingContext 实现
│    ├── BasicLdapPersistentEntity / LdapPersistentEntity
│    ├── LdapPersistentProperty                   @Id 识别、拒绝关联
│    └── LdapSimpleTypes                          简单类型集（Name）
├── repository/                      [5 文件] 对外 SPI 面
│    ├── LdapRepository                           仓库顶层接口（CRUD + LdapQuery 查询）
│    ├── Query                                    @Query 注解（value/base/scope/limits）
│    └── LdapEncode / LdapEncoder                 参数定制编码注解与编码器接口
├── repository/config/               [4 文件] 注解装配：@EnableLdapRepositories
│    ├── EnableLdapRepositories / LdapRepositoriesRegistrar
│    └── LdapRepositoryConfigurationExtension     模块识别规则（@Entry、拒绝响应式）
├── repository/support/              [11 文件] 工厂与实现
│    ├── LdapRepositoryFactoryBean                FactoryBean 接入 IoC
│    ├── LdapRepositoryFactory                    RepositoryFactorySupport 实现（代理装配）
│    ├── SimpleLdapRepository                     仓库基类：所有 CRUD 的委派实现
│    ├── LdapEntityInformation                    ID 类型 = Name
│    ├── QuerydslLdapPredicateExecutor / QuerydslLdapQuery / LdapSerializer
│    ├── QuerydslLdapRepository（@Deprecated 旧基类形态）
│    └── LdapAnnotationProcessor                  编译期 Q 类生成（APT）
├── repository/query/                [11 文件] 查询体系
│    ├── LdapQueryMethod / LdapParameters(+Parameter) / LdapParameterAccessor
│    ├── AbstractLdapRepositoryQuery              execute() 主流程
│    ├── PartTreeLdapRepositoryQuery + LdapQueryCreator   方法名 → LdapQuery
│    ├── AnnotatedLdapRepositoryQuery + StringBasedQuery  @Query → LdapQuery
│    └── LdapQueryExecution                       三种执行策略 + 投影包装
└── repository/cdi/                  [3 文件] CDI 环境支持（非 Spring 容器）
```

资源文件只有 5 个（`src/main/resources`），其中最有历史味的是 `META-INF/spring.factories`：

```properties
org.springframework.data.repository.core.support.RepositoryFactorySupport=org.springframework.data.ldap.repository.support.LdapRepositoryFactory
```

以及 `META-INF/spring.handlers`/`spring.schemas`（XML 命名空间注册）、`spring-ldap-1.0.xsd`（注意：这个 XSD 文件名叫 spring-ldap，却是 Spring Data LDAP 自己的 `<ldap:repositories/>` schema——历史化石之一）。

## 1.4 依赖图（pom.xml 实证）

```
        你的业务代码（@Entry 实体 + PersonRepository 接口）
                        │
              Spring Data LDAP（本项目，42 文件）
               │        │            │          │
    spring-data-commons   │    querydsl-core    jakarta.enterprise.cdi-api
    （Repository 抽象、    │    （optional，        （optional，CDI 环境）
      PartTree、投影）     │     Querydsl 支持）
                         │
              spring-ldap-core  ←—— 唯一的"引擎"依赖（pom.xml:66）
               │      │      │
        spring-core  spring-tx  (JNDI/sun LDAP Provider)
```

真实依赖声明（摘自 `pom.xml`，已逐项验证）：

| 依赖 | scope | 用途 |
|---|---|---|
| `spring-ldap-core` `${spring-ldap}`=4.2.0-M1 | compile | **引擎**：LdapOperations/LdapTemplate、ObjectDirectoryMapper、@Entry 注解、LdapQueryBuilder/Filter |
| `spring-data-commons` 4.2.0-SNAPSHOT | compile | **编程模型**：Repository 接口族、RepositoryFactorySupport、PartTree、投影、ValueExpressionDelegate |
| `spring-context` / `spring-tx` | compile | IoC 装配基础设施（经 spring-data-parent 传递声明） |
| `querydsl-core` / `querydsl-apt` | optional / provided | Querydsl 谓词支持与编译期 Q 类生成 |
| `jakarta.enterprise.cdi-api` | provided | CDI 扩展 |
| `spring-ldap-test` + `unboundid-ldapsdk` 7.0.4 | test | 内嵌 LDAP 服务器跑集成测试 |

**这张依赖表就是本文第二章的论纲**：compile 依赖里只有一个引擎（spring-ldap-core），一个编程模型底座（spring-data-commons）——Spring Data LDAP 本质上是把后者接到前者上面的一座桥。

## 1.5 关键问题 → Spring Data LDAP 方案映射（全文导览）

| 关键问题 | Spring Data LDAP 的方案 | 详见 |
|---|---|---|
| 和 Spring LDAP 到底什么关系？ | 薄适配层：注解/引擎/过滤器全复用 Spring LDAP，自己只做仓库抽象 | 第二章 |
| 空接口凭什么能注入、能执行？ | `@EnableLdapRepositories` → Registrar → FactoryBean → RepositoryFactorySupport 动态代理 | 第三章 |
| `save/findById/deleteById` 落到什么操作？ | SimpleLdapRepository 逐方法委派 `LdapOperations`（create/update/findByDn/unbind） | 第四章 |
| `findByLastName("张")` 怎么变成 LDAP 过滤器？ | PartTree 解析 → LdapQueryCreator → `ContainerCriteria` → `(sn=张)` | 第五章 |
| 复杂过滤器怎么写？参数注入怎么办？ | `@Query` + StringBasedQuery：`:name`/`?0`/SpEL/属性占位符，绑定值 filterEncode | 第六章 |
| 查询结果怎么变 List/Stream/DTO？ | LdapQueryExecution 三策略 + ResultProcessingConverter 投影 | 第七章 |
| 想要类型安全的查询 API？ | Querydsl：`LdapSerializer` 把 `Predicate` 翻译成 Spring LDAP `Filter` 树 | 第八章 |
| Spring Data 的 PersistentEntity 和 ODM 是什么关系？ | 双映射上下文并存：ODM 管对象↔目录，Spring Data 模型只管投影/ID 识别 | 第九章 |
| 非 Spring 容器怎么用？Boot 怎么自动装配？ | CDI 扩展；Boot `DataLdapRepositoriesAutoConfiguration` | 第十章 |
| 一次完整调用的时间线长什么样？ | 三条时间线：装配期、一次查询、一次 save | 第十一章 |

## 1.6 版本演进：2016 → 2026（git 实证）

### 1.6.1 版本时间线

本地 git 仓库共 **751 个提交**（`git log --oneline | wc -l`）。首提交 `9bbf038`（2016-11-21，"Initial import"，作者 Mattias Hellborg Arthursson——Spring LDAP 的原作者；项目后由 Rob Winch 领衔，Mark Paluch 长期维护，`pom.xml` developers 段实证）。首 issue 编号 DATALDAP-1（JIRA 时代，2016-12），后迁移 GitHub issues。

下表 GA 日期均为本地 git 标签实测（`git log -1 --format=%ad <tag>`）：

| 版本 | GA 日期 | 所属发布火车 | 该版本的大事 |
|---|---|---|---|
| 首提交 | 2016-11-21 | — | Initial import；一个月内完成 XML 命名空间、spring.factories 注册（DATALDAP-1/6，2016-12-06） |
| **2.0.0.RELEASE** | **2017-10-02** | Kay | **首个 GA**。注意：没有 1.x——项目直接按 Commons 2.0/Spring 5 基线开发（DATALDAP-8 "Set up 2.0 development"，2016-12-06） |
| 2.1.0.RELEASE | 2018-09-21 | Lovelace | 常规跟进 |
| 2.2.0.RELEASE | 2019-09-30 | Moore | JIRA（DATALDAP-126 RC3）→ GitHub issues 的过渡期 |
| 2.3.0.RELEASE | 2020-05-12 | Neumann | 常规跟进 |
| 2.4.0 | 2020-10-28 | 2020.0 | 版本号与发布火车日历化 |
| 2.5.0 | 2021-04-14 | 2021.0 | 常规跟进 |
| 2.6.0 | 2021-11-12 | 2021.1 | **Stream 查询与投影**（commit `9c9ef80`，2021-08-31）；**Querydsl 从基类改为 Fragment**（`c9be21a`，2021-10-11）；`LdapQueryExecution`/`LdapAnnotationProcessor` 体系成型 |
| 3.0.0 | 2022-11-18 | 2022.0 | jakarta 命名空间、Spring 6 基线 |
| 3.1.0 ~ 3.4.0 | 2023-05 ~ 2024-11 | 2023.0/2023.1/2024.0/2024.1 | 3.4 起旧构造器 `@Deprecated(since = "3.4")`，为 ValueExpressionDelegate 铺路 |
| 3.5.0 | 2025-05-16 | 2025.0 | **@Query 查询绑定重写**：`StringBasedQuery` 统一 `:name`/`?0`/SpEL/属性占位符（commit `b88ce59`，2024-10-15） |
| 4.0.0 | 2025-11-14 | 2025.1 | **查询方法 Limit 支持**（commit `b137f71`，2025-11-07）；升级 Spring LDAP 4.0；JSpecify 注解 |
| 4.1.0 | 2026-06-09 | 2026.0 | 升级 Spring LDAP 4.1.0 |
| 4.2.0-M2 | 2026-09-21 | 2026.1 | 构建目标 Spring LDAP 4.2.0-M1（本文基线） |

### 1.6.2 特性引入版本对照表（git/源码实证，可复现）

| 特性 | 证据 |
|---|---|
| 仓库抽象 + XML 命名空间 + spring.factories 注册 | 2016-12 DATALDAP-1/2/6（`git log --reverse` 前几个提交） |
| Querydsl 支持（基类形态 `QuerydslLdapRepository`） | 2.0 开发期即存在（DATALDAP-37，2017-05-19，适配 `QuerydslPredicateExecutor.findOne` API 变化） |
| `LdapMappingContext`（Spring Data 侧映射模型） | 类 javadoc `@since 2.0.4` |
| Stream 查询 / DTO 投影 / `LdapQueryExecution` | `9c9ef80`（2021-08-31）；`LdapQueryExecution` javadoc `@since 2.6` |
| Querydsl Fragment 化（`QuerydslLdapPredicateExecutor`） | `c9be21a`（2021-10-11）；javadoc `@since 2.6` |
| `@Query` 值表达式（SpEL/属性占位符，`StringBasedQuery`） | `b88ce59`（2024-10-15）；`StringBasedQuery` javadoc `@since 3.5` |
| 查询方法 Limit（`Limit` 参数 / `findTop3`） | `b137f71`（2025-11-07，4.0 开发期） |
| 兼容旧基类构造器的 `acceptsMappingContext` 垫片 | `LdapRepositoryFactory#acceptsMappingContext`，javadoc 注明 2.6 引入的 4 参构造器 |

### 1.6.3 对初学者的意义：哪些知识过时了，哪些永远有效

- **过时/不必学**：XML 的 `<ldap:repositories/>`（现代 Boot 项目用不到，但源码仍在维护）；`QuerydslLdapRepository` 基类形态（2.6 起 `@Deprecated`，改用 `QuerydslPredicateExecutor` Fragment）；JIRA 编号 DATALDAP-xxx（查历史用 GitHub issues）。
- **永远有效**：第二章的"引擎/驾驶舱"分工；仓库代理装配链（Commons 的 `RepositoryFactorySupport` 家族）；PartTree→Filter 的编译路径；LDAP 协议给查询能力画的天花板（无排序、弱分页、无关联）——这些决定了你写仓库方法时哪些"想当然"会碰壁。

## 1.7 全文章节地图

```
一 总览（本文你正在读）
二 与 Spring LDAP 的关系：引擎与驾驶舱 ←—— 全文核心问题
三 装配链：@EnableLdapRepositories → 仓库代理
四 SimpleLdapRepository：CRUD 委派逐方法实证
五 查询方法体系：方法名 → LDAP 过滤器
六 @Query 与 StringBasedQuery：字符串查询与注入防护
七 执行层与投影：LdapQueryExecution
八 Querydsl：Predicate → Filter 翻译器
九 映射层：双 MappingContext 并存之谜
十 生态集成：XML / CDI / Boot / AOT
十一 贯通视图：三条时间线
十二 附录：速查表、学习路线、源码入口
```

---

# 二、与 Spring LDAP 的关系：引擎与驾驶舱（本章核心）

## 2.1 先说白话：一个双层架构

把两个项目叠起来看：

```
┌────────────────────────────────────────────────────────────┐
│  Spring Data LDAP（驾驶舱，42 个文件）                        │
│  你写的：interface PersonRepository extends CrudRepository  │
│  它给的：动态代理、方法名派生查询、@Query、Querydsl、投影、     │
│          CDI/Boot 集成                                      │
└──────────────────────────┬─────────────────────────────────┘
                           │ 每个动作最终是一次引擎调用
┌──────────────────────────▼─────────────────────────────────┐
│  Spring LDAP / spring-ldap-core（引擎）                      │
│  ContextSource（连接/认证）· LdapTemplate（模板方法）         │
│  异常转译（NamingException→DataAccessException）             │
│  filter 包（过滤器对象树）· LdapQueryBuilder                  │
│  ODM：@Entry/@Id/@Attribute 注解 + DefaultObjectDirectoryMapper│
└──────────────────────────┬─────────────────────────────────┘
                           │ JNDI
                    LDAP 服务器（AD / OpenLDAP / UnboundID…）
```

为什么会有这个分层？**因为 Spring LDAP 3.x 的 `LdapOperations` 接口本身已经内置了 ODM 高层方法**（`create/update/delete/findAll/find/findOne/findByDn`，见 `LdapOperations.java:1597~1744`）。Spring Data LDAP 要做的事，本质上是给这些方法**套上"接口即实现"的仓库外衣**，再把"方法名怎么变查询"这个问题接进 Spring Data Commons 的标准机器。它不需要、也确实没有重复造任何轮子。

## 2.2 依赖实证：spring-ldap-core 是唯一的引擎依赖

`pom.xml`（spring-data-ldap 4.2.0-SNAPSHOT）：

```xml
<properties>
    <spring-ldap>4.2.0-M1</spring-ldap>
    <springdata.commons>4.2.0-SNAPSHOT</springdata.commons>
</properties>
...
<artifactId>spring-ldap-core</artifactId>      <!-- :66，compile -->
<artifactId>spring-data-commons</artifactId>   <!-- 编程模型底座 -->
```

compile 依赖里与"LDAP"相关的**只有一个** spring-ldap-core。测试域还有 `spring-ldap-test`（内嵌服务器测试基建）——连测试都是搭 Spring LDAP 的便车。

## 2.3 对接面全景：Spring Data LDAP 用到的 Spring LDAP"六大件"

把 Spring Data LDAP 主源码里 `import org.springframework.ldap.*` 的引用汇总（实测全量扫描），接触面收敛为六组：

| # | Spring LDAP 侧类型 | Spring Data LDAP 侧使用者 | 干什么 |
|---|---|---|---|
| 1 | `LdapOperations`（LdapTemplate 的接口） | `SimpleLdapRepository`、`LdapRepositoryFactory`、所有 Query/Execution | 一切 CRUD 与查询的最终执行者 |
| 2 | `ObjectDirectoryMapper`（接口，实现在 core 的 `DefaultObjectDirectoryMapper`） | 同上 + `LdapEntityInformation` + 两个 QueryCreator | 实体↔条目映射、`getId`、`attributeFor`（属性名翻译）、`filterFor`（objectclass 过滤器合成） |
| 3 | `org.springframework.ldap.odm.annotations.*`（@Entry/@Id/@Attribute/@DnAttribute/@Transient） | `LdapRepositoryConfigurationExtension`、`LdapQueryCreator`、`LdapPersistentProperty`、APT 处理器 | **实体注解完全复用，一个都没重新定义** |
| 4 | `LdapQuery` / `LdapQueryBuilder` / `ContainerCriteria` / `SearchScope` | `LdapQueryCreator`、`AnnotatedLdapRepositoryQuery`、`PartTreeLdapRepositoryQuery`、Querydsl | 查询的统一表达与构建 |
| 5 | `org.springframework.ldap.filter.*`（EqualsFilter/AndFilter/…/AbsoluteTrueFilter） | `LdapSerializer`（Querydsl 翻译器）、`SimpleLdapRepository#count` 间接（filterFor 产物） | 过滤器对象树 |
| 6 | `LdapEncoder`（support 包的工具类）与异常层次（`NameNotFoundException` 等已转译异常） | `StringBasedQuery`（filterEncode）、`SimpleLdapRepository#findById`（catch NameNotFoundException） | 注入防护、异常语义消费 |

反过来看 Spring Data LDAP 贡献给关系的东西：`LdapRepository` 接口、`@Query`/`@LdapEncode` 注解、`LdapEncoder` 编码器接口（注意与 Spring LDAP 的 `LdapEncoder` 工具类**重名**，一个是接口一个是工具类）、装配三件套（注解/Registrar/Extension）、Querydsl 翻译器、CDI 扩展。

## 2.4 逐方法实证：SimpleLdapRepository 的每个方法落到哪里

`repository/support/SimpleLdapRepository.java` 全文只有两个业务字段（:55-57）：

```java
private final LdapOperations ldapOperations;
private final ObjectDirectoryMapper odm;
```

逐方法委派表（行号为该文件实际行号）：

| 仓库方法 | 源码位置 | 落到 Spring LDAP 的哪个调用 |
|---|---|---|
| `save(S)` | :103-116 | `odm.getId(entity)` 判新 → `ldapOperations.create(entity)` 或 `update(entity)`（ODM 高层方法） |
| `findById(Name)` | :127-136 | `ldapOperations.findByDn(name, entityType)`；catch `NameNotFoundException` → `Optional.empty()` |
| `findAll()` | :147-149 | `ldapOperations.findAll(entityType)` |
| `findAllById` | :152-158 | 循环逐个 `findById`（N+1，直通引擎，无批量优化——LDAP 本也没有批量 get） |
| `count()` | :161-169 | `odm.filterFor(entityType, null)` 造 objectclass 过滤器 → `query().attributes("objectclass").filter(...)` → `search` + `CountNameClassPairCallbackHandler` 数行数 |
| `deleteById(Name)` | :172-177 | `ldapOperations.unbind(name)`——**直接删 DN，无级联** |
| `delete(T)` | :180-185 | `ldapOperations.delete(entity)`（内部同样是 unbind） |
| `findOne(LdapQuery)` | :213-222 | `ldapOperations.findOne(query, entityType)`；catch `EmptyResultDataAccessException` → empty |
| `findAll(LdapQuery)` | :225-229 | `ldapOperations.find(query, entityType)` |

**这就是"关系"的最硬证据**：仓库基类不含一行 JNDI 代码、不含一行属性映射代码。它做的工作只有三类：判新（`isNew`，:231-233，`Persistable` 接口优先，否则 id 为 null 即新）、异常语义微调（NotFound→空 Optional）、委派。

引擎侧接住这些调用的地方（spring-ldap `LdapTemplate.java`，行号实测）：

- `find(base, filter, controls, clazz)` :1715：先 `this.odm.filterFor(clazz, filter)` 把**实体的 objectclass 过滤器与查询过滤器 AND 合并**，再用 `odm.manageClass(clazz)` 补上 returningAttributes，搜索后逐条 `odm.mapFromLdapDataEntry` 成对象。
- `findOne(query, clazz)` :1768：调 `find`，0 条抛 `EmptyResultDataAccessException(1)`，>1 条抛 `IncorrectResultSizeDataAccessException(1, n)`。
- `create` / `update` / `delete`：ODM 方法族（见姊妹篇 Spring LDAP.md 第六章、第七章的 updateMode 差量与 DN 变更"先删后绑"逻辑）。

## 2.5 历史脉络：2013 年埋的伏笔，2016 年结的果

git 实证（spring-ldap 仓库）：

- **2013-10-10，LDAP-266**："Initial effort for automatic repository support"——从这次提交起，`LdapOperations` 接口出现 `getObjectDirectoryMapper()`，ODM 方法族（create/update/find/findOne/findAll）进入接口（tag `2.0.0.RELEASE` 的该文件实测已有这批方法）。**引擎在接口层为"仓库支持"预留了位置**。
- **2016-11-21**：Spring Data LDAP "Initial import"，直接消费这套预留接口。
- 换句话说：Spring LDAP 2.0（2016 年 GA）到 Spring Data LDAP 2.0（2017 年 GA）是一次**有预谋的两步走**——先把能力提升为接口方法，再在上面架编程模型。

姊妹篇 `Spring LDAP.md` 7.5 节的结论与此互为印证：1.3.1 的 `OdmManager` 门面在 2.0 被 `@Deprecated`，其能力并入 `LdapOperations`，"Spring LDAP 的 ODM 提供映射引擎，Spring Data LDAP 提供仓库编程模型"。

## 2.6 职责边界：Spring Data LDAP 不做什么

读源码时容易高估它，先划清边界（全部有源码依据）：

| 它不做 | 证据 |
|---|---|
| 不做响应式仓库 | `LdapRepositoryConfigurationExtension#useRepositoryConfiguration`（:112-114）：`!metadata.isReactiveRepository()` 直接拒绝；接口族里也没有 `ReactiveLdapRepository` |
| 不做事务 | 自己没有任何事务设施。LDAP 的补偿式事务在**引擎侧**（Spring LDAP 的 `ContextSourceTransactionManager`，见姊妹篇 8.2 节），配合使用即可 |
| 不做关联映射 | `LdapPersistentProperty#createAssociation`：`throw new UnsupportedOperationException("LDAP does not support associations")` |
| 不做服务端排序/深分页 | `LdapQueryCreator#complete` 无视 `Sort`；Querydsl 分页只支持"第一页+无排序"，其余抛 `UnsupportedOperationException` |
| 不做真流式搜索 | `StreamExecution`（`LdapQueryExecution.java:100-117`）是 `find(...).stream().map(...)`——结果已全部载入内存的"假流式"；引擎其实有真流式的 `LdapTemplate.findForStream`（NamingEnumeration + onClose 关闭），Spring Data LDAP **没有用它** |
| 不做缓存 | 无一级/二级缓存概念，每次查询都打服务器（目录服务场景合理） |
| 不重定义实体注解 | 见 2.3 表第 3 行 |

## 2.7 放进 Spring Data 全家福里看

| 模块 | 引擎 | 关系相似度 |
|---|---|---|
| Spring Data JPA | JPA 实现（Hibernate 等）+ spring-orm 桥 | ★★★★★ 几乎同构：引擎实现高层 API，仓库模型架在上面 |
| Spring Data Redis / MongoDB | spring-data-redis / mongodb 自带驱动封装 | 这两个模块把引擎也自己做了，比 Spring Data LDAP "厚"得多 |
| Spring Data LDAP | **spring-ldap-core（独立项目，独立版本节奏）** | 42 个文件，是 Spring Data 家族里最薄的模块之一 |

一个有意思的对照：JPA 那边 Spring 团队要做 `spring-orm` 桥接层；LDAP 这边因为引擎本来就是 Spring 家的（`LdapTemplate` 全套 Spring 风格异常转译），桥接层薄到几乎不存在——**引擎与仓库模型的"血缘一致性"正是 Spring Data LDAP 如此之薄的根源**。

## 2.8 本章小结

- 关系一句话：**Spring LDAP 是引擎（连接/模板/异常/ODM），Spring Data LDAP 是仓库编程模型，后者 100% 架在前者的 `LdapOperations` + `ObjectDirectoryMapper` 两大接口之上**。
- 三个证据链：pom 依赖（唯一引擎依赖 spring-ldap-core）、装配识别（实体注解用 Spring LDAP 的 `@Entry`）、基类实现（SimpleLdapRepository 每个方法都是引擎调用）。
- 历史上这是一次两步走：LDAP-266（2013）先把 ODM 能力提升为 `LdapOperations` 接口方法，DATALDAP（2016）再在上面架仓库。
- 边界：无响应式、无事务、无关联、无排序、假流式、无缓存——LDAP 协议的天花板就是仓库的天花板。

---

# 三、装配链：从 @EnableLdapRepositories 到可注入的仓库代理

## 3.1 先说白话：一个接口凭什么能注入？

你只写了 `interface PersonRepository extends CrudRepository<Person, Name>`，容器里却有了一个能干活的 bean。魔法链条是 Spring Data Commons 的标准五步（本文只讲 LDAP 特有环节，通用机器见姊妹篇 `Spring Data Commons.md` 第二章）：

```
@EnableLdapRepositories → LdapRepositoriesRegistrar（Import 进容器）
  → 扫描 basePackages，按识别规则挑出仓库接口
  → 每个接口注册一个 LdapRepositoryFactoryBean（FactoryBean）
  → FactoryBean 创建 LdapRepositoryFactory（RepositoryFactorySupport 子类）
  → Factory 生成 JDK 动态代理：基类实现(SimpleLdapRepository) + 自定义 Fragment
     + 查询方法拦截器(Annotated/PartTree…)
```

## 3.2 模块定位与配置入口

本章对应包：`org.springframework.data.ldap.repository.config`（4 个文件）+ `support` 包里的两个 Factory 类。

入口注解 `EnableLdapRepositories.java`（节选属性，全部实测）：

```java
@Import(LdapRepositoriesRegistrar.class)
public @interface EnableLdapRepositories {
    String[] basePackages() default {};
    Key queryLookupStrategy() default Key.CREATE_IF_NOT_FOUND;   // 查询策略
    Class<?> repositoryFactoryBeanClass() default LdapRepositoryFactoryBean.class;
    Class<?> repositoryBaseClass() default DefaultRepositoryBaseClass.class;
    String ldapTemplateRef() default "ldapTemplate";             // ← LDAP 特有！
    boolean considerNestedRepositories() default false;
}
```

注意 **`ldapTemplateRef`**：这是 LDAP 模块独有的属性——仓库工厂需要的引擎（`LdapOperations`）不归 Spring Data 管，由 Spring LDAP 的配置（Boot 的 `LdapAutoConfiguration` 或手工 `ContextSource` + `LdapTemplate`）提供，这里按名字引用，默认 `"ldapTemplate"`。

## 3.3 LdapRepositoriesRegistrar 与 LdapRepositoryConfigurationExtension

`LdapRepositoriesRegistrar` 只有 10 行：继承 Commons 的 `RepositoryBeanDefinitionRegistrarSupport`，指定注解类型与 Extension 即可。

真正有内容的是 `LdapRepositoryConfigurationExtension`（模块的"识别规则说明书"）：

```java
public String getModuleName()            { return "LDAP"; }              // :52
protected String getModulePrefix()       { return "ldap"; }              // :57
public String getRepositoryFactoryBeanClassName() { ...LdapRepositoryFactoryBean.class; }

protected Collection<Class<? extends Annotation>> getIdentifyingAnnotations() {
    return Collections.singleton(Entry.class);        // :66-68 ← Spring LDAP 的 @Entry！
}
protected Collection<Class<?>> getIdentifyingTypes() {
    return Collections.singleton(LdapRepository.class); // :71-73
}
protected boolean useRepositoryConfiguration(RepositoryMetadata metadata) {
    return !metadata.isReactiveRepository();          // :112-114 ← 拒绝响应式
}
```

三个关键语义：

1. **识别注解是 `@Entry`**（:66-68）：扫描时判断"这个接口的领域类型是不是 LDAP 实体"，看的是 Spring LDAP 的注解。这行代码是第二章结论的直接源码证据。
2. **识别类型是 `LdapRepository`**（:71-73）：显式继承本模块顶层接口的仓库也能被认出（兜底）。
3. **响应式仓库被装配期拒绝**（:112-114）：你写 `ReactiveCrudRepository` 不会得到一个坏 bean，而是根本不装配。

此外 `postProcess`（:76-96）把两个引用塞进 FactoryBean 的 BeanDefinition：

- XML 方式：读 `<ldap:repositories ldap-template-ref="..."/>` 属性，缺省 `"ldapTemplate"`；
- 注解方式：读 `ldapTemplateRef` 属性；
- 两种方式都补一个 `mappingContext` 引用，指向 `registerBeansForRoot`（:99-109）注册的 **`ldapMappingContext`** bean（`ROLE_INFRASTRUCTURE`，基础设施角色，不参与自动注入竞争）。

## 3.4 LdapRepositoryFactoryBean：FactoryBean 模式接入 IoC

`support/LdapRepositoryFactoryBean.java`：

```java
public class LdapRepositoryFactoryBean<T extends Repository<S, Name>, S>
        extends RepositoryFactoryBeanSupport<T, S, Name> {          // ID 泛型固定为 Name

    public void setLdapOperations(LdapOperations ldapOperations) { ... }   // 引擎注入点

    protected RepositoryFactorySupport createRepositoryFactory() {
        Assert.state(ldapOperations != null, "LdapOperations must be set");
        return mappingContext != null
            ? new LdapRepositoryFactory(ldapOperations, mappingContext)
            : new LdapRepositoryFactory(ldapOperations);
    }

    public void afterPropertiesSet() {
        Assert.notNull(ldapOperations, "LdapOperations must be set");   // 引擎必须就位
        super.afterPropertiesSet();
        if (!mappingContextConfigured) setMappingContext(new LdapMappingContext());
    }
}
```

两个 LDAP 特有细节：泛型签名把 ID 类型**钉死为 `javax.naming.Name`**；`afterPropertiesSet` 强校验引擎已注入——没有 `LdapTemplate`，仓库一个都建不出来。

## 3.5 LdapRepositoryFactory：代理装配全流程（本章核心）

`support/LdapRepositoryFactory.java` 继承 Commons 的 `RepositoryFactorySupport`，五个钩子的实现：

### 3.5.1 实体信息、基类与目标仓库

```java
public <T, ID> EntityInformation<T, ID> getEntityInformation(Class<T> domainClass) {
    return new LdapEntityInformation(domainClass, ldapOperations.getObjectDirectoryMapper()); // :93-97
}
protected Class<?> getRepositoryBaseClass(RepositoryMetadata metadata) {
    return SimpleLdapRepository.class;                                                        // :100-102
}
protected Object getTargetRepository(RepositoryInformation information) {                     // :140-151
    // 反射调 SimpleLdapRepository 构造器：
    // 4 参（ldapOperations, mappingContext, odm, entityType）或 3 参旧签名
}
```

`getEntityInformation` 再次直通引擎拿 ODM——`LdapEntityInformation`（:16-33）把 `getId` 委派给 `odm.getId(entity)`、`getIdType` 固定 `Name.class`。`acceptsMappingContext`（:167-184）是给 2.6 之前自定义基类的兼容垫片：检查构造器签名决定传不传 mappingContext。

### 3.5.2 LdapQueryLookupStrategy：查询方法的两条分流

`getQueryLookupStrategy`（:153-158）无条件返回一个 record（:186-205）：

```java
private record LdapQueryLookupStrategy(LdapOperations ldapOperations, ...) implements QueryLookupStrategy {
    public RepositoryQuery resolveQuery(Method method, ...) {
        LdapQueryMethod queryMethod = new LdapQueryMethod(method, metadata, factory);
        if (queryMethod.hasQueryAnnotation()) {          // 有 @Query
            return new AnnotatedLdapRepositoryQuery(queryMethod, domainType, ldapOperations,
                    mappingContext, instantiators, valueExpressionDelegate);
        } else {                                          // 没有 → 方法名派生
            return new PartTreeLdapRepositoryQuery(queryMethod, domainType, ldapOperations,
                    mappingContext, instantiators);
        }
    }
}
```

分流规则极其干脆：**看方法上有没有 `@Query`，有则走字符串查询（第六章），没有则走方法名派生（第五章）**。`CREATE_IF_NOT_FOUND` 语义下也支持"接口里写了个 `Impl` 自定义片段就优先用自定义"（Commons 的 Fragment 机制，见 3.5.3）。

### 3.5.3 Fragment：Querydsl 能力混入

`getRepositoryFragments`（:120-137）：

```java
boolean isQueryDslRepository = QUERY_DSL_PRESENT
        && QuerydslPredicateExecutor.class.isAssignableFrom(metadata.getRepositoryInterface());
if (isQueryDslRepository) {
    if (metadata.isReactiveRepository()) throw new InvalidDataAccessApiUsageException(...);
    return RepositoryFragments.just(new QuerydslLdapPredicateExecutor<>(...));
}
return RepositoryFragments.empty();
```

仓库接口只要声明 `QuerydslPredicateExecutor<Person>`，Querydsl 能力就以 Fragment 形式混入代理（第八章展开）。Querydsl 不在 classpath 时 `QUERY_DSL_PRESENT` 为 false，静默跳过——能力可选，引擎必需。

## 3.6 本章小结

- 装配链是 Commons 标准五步，LDAP 只贡献三个特有环节：**`ldapTemplateRef` 引用引擎、`@Entry` 识别实体、装配期拒绝响应式**。
- `LdapRepositoryFactoryBean` 泛型把 ID 钉死为 `Name`，且强校验 `LdapOperations` 已注入——没有引擎就没有仓库。
- 查询方法分流一句话：**有 `@Query` 走字符串查询，否则走方法名派生**；Querydsl 以可选 Fragment 混入。

---

# 四、SimpleLdapRepository：CRUD 委派实现

## 4.1 模块定位：仓库基类就是 LdapOperations 的"翻译层"

顶层接口 `LdapRepository.java`（全文 52 行）：

```java
public interface LdapRepository<T> extends ListCrudRepository<T, Name> {
    Optional<T> findOne(LdapQuery ldapQuery);
    List<T> findAll(LdapQuery ldapQuery);
}
```

在 Commons 的 CRUD 能力集上只加了两个 `LdapQuery` 查询入口；ID 类型固定 `Name`（LDAP 的 DN，`LdapName` 是常用实现）。逐方法委派表已在 2.4 节给出，本章展开三个有"语义含量"的方法。

## 4.2 save：isNew 判定与 create/update 分叉

`SimpleLdapRepository#save`（:103-116）：

```java
public <S extends T> S save(S entity) {
    Name declaredId = odm.getId(entity);
    if (isNew(entity, declaredId)) {
        ldapOperations.create(entity);      // 新条目 → LDAP bind 操作
    } else {
        ldapOperations.update(entity);      // 已存在 → 差量 modify 或 DN 迁移
    }
    return entity;
}
private <S extends T> boolean isNew(S entity, @Nullable Name id) {
    return entity instanceof Persistable<?> p ? p.isNew() : id == null;
}
```

判新规则：实体实现 Commons 的 `Persistable` 接口就听实体的，否则**"DN 为空 = 新"**。这与 JPA 的"有 ID 不代表新"不同——LDAP 里 DN 就是主键本身，DN 都没有自然无从 update。引擎侧 `LdapTemplate.update` 的完整分支（DN 变更→先 unbind 后 bind；未变更→lookup + `setUpdateMode(true)` + 差量 `modifyAttributes`）见姊妹篇 6.2/7.2 节，这里只强调：**仓库的 save 一分叉，引擎的 update 里还有一次分叉**，两层各管一段。

## 4.3 count：objectclass 过滤器 + 回调数数

`SimpleLdapRepository#count`（:161-169）：

```java
public long count() {
    Filter filter = odm.filterFor(entityType, null);       // 生成 (&(objectclass=person)...)
    CountNameClassPairCallbackHandler callback = new CountNameClassPairCallbackHandler();
    LdapQuery query = query().attributes(OBJECTCLASS_ATTRIBUTE).filter(filter);
    ldapOperations.search(query, callback);
    return callback.getNoOfRows();
}
```

三个细节：`filterFor(clazz, null)` 只返回实体的 objectclass 过滤器（`DefaultObjectDirectoryMapper#filterFor`，spring-ldap :513-522）；`attributes("objectclass")` 限制返回属性最小化——数数不需要其他属性；用的是 `NameClassPairCallbackHandler`（**只要 DN 不取条目**），比拉全量对象再数高效得多。但注意它仍是把搜索结果行数在内存里数完，没有服务端 count 协议——LDAP 本就没有。

## 4.4 delete 家族：unbind 的直通

- `deleteById`（:172-177）：`ldapOperations.unbind(name)`。**不检查存在性、不级联子条目**——要删带子节点的 DN 会得到引擎/JNDI 的 `ContextNotEmptyException`（已转译）。递归删除是引擎侧 `LdapUtils`/手工循环的事。
- `deleteAll()`（:203-206）：`deleteAll(findAll())`——**先全量查出再逐个删**，大目录慎用。
- `deleteAllById`/`deleteAll(Iterable)`：逐个循环，无批量协议（LDAP 没有批量 delete，这是协议限制不是实现偷懒）。

## 4.5 本章小结

- CRUD 的语义全部来自引擎：save 分叉靠"DN 为空即新"，delete 就是 unbind，count 是 objectclass 过滤器数行。
- 仓库层无批量、无级联、无存在性预检——这些"缺失"都是 LDAP 协议本身的形状，不是实现缺陷。

---

# 五、查询方法体系：从方法名到 LDAP 过滤器

## 5.1 白话：`findByLastName` 的旅程

```java
public interface PersonRepository extends LdapRepository<Person> {
    List<Person> findByLastName(String lastName);
}
```

调用 `findByLastName("张")` 时的完整旅程：

```
代理拦截 → PartTreeLdapRepositoryQuery.execute()
  → PartTree 解析方法名（Subject: find；Predicate: LastName = 参数0，And/Or 树）
  → LdapQueryCreator（Commons AbstractQueryCreator 模板）
      create: 读 @Entry.base() 作搜索基点；属性名 Java 字段→LDAP 属性（odm.attributeFor）
      appendCondition: SIMPLE_PROPERTY → criteria.is("张") → (sn=张)
  → LdapQuery（base=ou=people,dc=example,dc=org, filter=(sn=张)）
  → LdapOperations.find(query, Person.class)
      → odm.filterFor: AND (objectclass=person) → 最终 (&(objectclass=person)(sn=张))
```

官方文档（`query-methods.adoc`）给出的结果示例一致：`findByLastname` 产生 `(&(objectclass=person)(lastname=lastname))`——objectclass 部分来自引擎侧合并（2.4 节），属性部分来自本章。

## 5.2 LdapQueryMethod 与 LdapParameters

`query/LdapQueryMethod.java`：继承 Commons `QueryMethod`，特化两点——构造时传入 `LdapParameters::new` 工厂；提供 `hasQueryAnnotation()/getQueryAnnotation()`（方法级反射查 `@Query`）。

`query/LdapParameters.java`：参数表特化出 `LdapParameter`，构造时检查参数上的 **`@LdapEncode`** 注解：

```java
LdapParameter(MethodParameter parameter, ...) {
    LdapEncode encode = parameter.getParameterAnnotation(LdapEncode.class);
    if (encode != null) this.ldapEncoder = BeanUtils.instantiateClass(encode.value());  // 每参数一个编码器实例
}
```

这为第六章的"逐参数定制编码"提供元数据。`LdapParameterAccessor`/`LdapParametersParameterAccessor` 是 Commons 参数访问器的薄封装，额外暴露 `getBindableParameterValues()`。

## 5.3 PartTree 解析与 LdapQueryCreator（本章核心）

### 5.3.1 入口：PartTreeLdapRepositoryQuery

`query/PartTreeLdapRepositoryQuery.java`（:54-98）：

```java
public PartTreeLdapRepositoryQuery(LdapQueryMethod queryMethod, Class<?> entityType, ...) {
    partTree = new PartTree(queryMethod.getName(), entityType);          // 方法名 → 谓词树
    objectDirectoryMapper = ldapOperations.getObjectDirectoryMapper();   // 又是直通引擎拿 ODM
}

protected LdapQuery createQuery(LdapParameterAccessor parameters) {
    // 1) DTO 投影时收集 inputProperties（回填 SELECT 属性列表）
    LdapQueryCreator queryCreator = new LdapQueryCreator(partTree, getEntityClass(),
            objectDirectoryMapper, parameters, inputProperties);
    LdapQuery query = queryCreator.createQuery();
    // 2) Limit 两来源：findTop3/isFirstN（partTree.isLimiting）或 Limit 参数
    if (getQueryMethod().getParameters().hasLimitParameter() || partTree.isLimiting()) {
        LdapQueryBuilder builder = LdapQueryBuilder.fromQuery(query);    // 已构建的 query 再改
        if (partTree.isLimiting())  builder.countLimit(partTree.getResultLimit().max());
        if (hasLimitParameter)      builder.countLimit(limit.isLimited() ? limit.max() : 0);
        return builder;
    }
    return query;
}
```

`LdapQueryBuilder.fromQuery(query)`（spring-ldap `LdapQueryBuilder.java:105`）是 Spring LDAP 3.0 开发期新增的 API（commit `092b22c2`，2022-07-21，"Add Stream Support"，与 `findForStream` 同期落地）——**把已构建的 LdapQuery 复制回 Builder 继续编辑**，Spring Data LDAP 的 Limit 支持正是建立在这个引擎 API 之上（又一次"引擎先开槽、模型来使用"的协作）。

### 5.3.2 LdapQueryCreator：四步模板

`query/LdapQueryCreator.java` 继承 Commons `AbstractQueryCreator<LdapQuery, ContainerCriteria>`，四步模板对应 LDAP 语义：

```java
protected ContainerCriteria create(Part part, Iterator<Object> iterator) {       // :73-92
    Entry entry = AnnotatedElementUtils.findMergedAnnotation(entityType, Entry.class);
    LdapQueryBuilder query = LdapQueryBuilder.query();
    if (entry != null) query = query.base(entry.base());        // 搜索基点来自实体注解
    if (!inputProperties.isEmpty())                             // DTO 投影 → 只取这些属性
        query.attributes(inputProperties.stream().map(p -> mapper.attributeFor(entityType, p)).toArray(String[]::new));
    ConditionCriteria criteria = query.where(getAttribute(part));
    return appendCondition(part, iterator, criteria);
}
protected ContainerCriteria and(...) { return appendCondition(part, iterator, base.and(getAttribute(part))); }
protected ContainerCriteria or(...)  { return base.or(criteria); }
protected LdapQuery complete(@Nullable ContainerCriteria criteria, Sort sort) {
    return criteria == null ? LdapQueryBuilder.query() : criteria;   // ← sort 被无视！
}
```

`complete` 无视 `Sort` 是刻意的：LDAP 搜索默认无服务端排序（排序要用 LDAP Controls 的 SortControl，本模块未支持）。

### 5.3.3 关键词 → 过滤器对照（appendCondition :94-129 源码实证）

```java
switch (type) {
    case NEGATING_SIMPLE_PROPERTY: return value != null ? criteria.not().is(v) : criteria.isPresent();
    case SIMPLE_PROPERTY:          return value != null ? criteria.is(v)   : criteria.not().isPresent();
    case STARTING_WITH:  return criteria.like(value + "*");
    case ENDING_WITH:    return criteria.like("*" + value);
    case CONTAINING:     return criteria.like("*" + value + "*");
    case LIKE:           return criteria.like(ObjectUtils.nullSafeToString(value));
    case NOT_LIKE:       return criteria.not().like(...);
    case GREATER_THAN_EQUAL: return criteria.gte(...);      // (attr>=v)
    case LESS_THAN_EQUAL:    return criteria.lte(...);      // (attr<=v)
    case IS_NOT_NULL:    return criteria.isPresent();       // (attr=*)
    case IS_NULL:        return criteria.not().isPresent(); // (!(attr=*))
    default: throw new IllegalArgumentException(type + " queries are not supported for LDAP repositories");
}
```

整理成官方文档同款对照表（`query-methods.adoc` 亦载）：

| 关键词 | 方法示例 | 生成的过滤器 |
|---|---|---|
| （无关键词） | `findByFirstname(String)` | `(firstname=value)` |
| `Not` | `findByFirstnameNot` | `(!(firstname=value))` |
| `StartingWith` | `findByFirstnameStartingWith` | `(firstname=value*)` |
| `EndingWith` | `findByFirstnameEndingWith` | `(firstname=*value)` |
| `Containing` | `findByFirstnameContaining` | `(firstname=*value*)` |
| `Like` / `NotLike` | `findByFirstnameLike` | `(firstname=value)`（通配符手写）/ `(!(firstname=...))` |
| `GreaterThanEqual` | `findByAgeGreaterThanEqual` | `(age>=v)` |
| `LessThanEqual` | `findByAgeLessThanEqual` | `(age<=v)` |
| `IsNotNull`/`NotNull` | `findByFirstnameNotNull` | `(firstname=*)` |
| `IsNull`/`Null` | `findByFirstnameNull` | `(!(firstname=*))` |
| 其余（`GreaterThan`/`LessThan` 严格比较、`In`、`Between`、`True/False`、`IgnoreCase`…） | — | **抛 IllegalArgumentException**（default 分支） |

一个容易忽略的细节：`SIMPLE_PROPERTY` 传 null 时生成 `(!(attr=*))`（"属性不存在"）而非 `(attr=)`——null 参数被解释为"非存在"语义（:106-107）。另外**严格比较 `GreaterThan`/`LessThan` 不支持**，因为 LDAP 过滤器只有 `>=` 与 `<=`，没有严格不等号——协议天花板再次显形。

### 5.3.4 属性名翻译：odm.attributeFor

`getAttribute`（:131-139）：**拒绝嵌套属性**（`PropertyPath.hasNext()` → `IllegalArgumentException("Nested properties are not supported")`——LDAP 条目是扁平的），然后 `mapper.attributeFor(entityType, path.getSegment())`。引擎侧实现（`DefaultObjectDirectoryMapper#attributeFor`，spring-ldap :525-535）是**反射取 Java 字段**再查注解元数据：属性名匹配的是**字段名**而非 getter 推导名，找不到字段直接抛 `IllegalArgumentException`——写方法名时必须与实体字段名逐字一致。

## 5.4 本章小结

- 方法名派生 = Commons 的 PartTree 引擎 + LDAP 语义的四个钩子；产物是 Spring LDAP 的 `ContainerCriteria`。
- 支持的关键词是 LDAP 过滤器语法的直接映射：等值/存在/通配符/`>=`/`<=`；严格比较、IN、BETWEEN、排序、嵌套属性统统不支持——协议没有的它不给。
- 搜索基点（base）取自实体 `@Entry.base()`；属性名翻译以 Java 字段名逐字匹配；Limit 经由引擎 4 系新 API `LdapQueryBuilder.fromQuery` 实现。

---

# 六、@Query 与 StringBasedQuery：字符串查询、绑定与注入防护

## 6.1 @Query 的五个属性

`repository/Query.java`：

```java
public @interface Query {
    String value() default "";                     // LDAP 过滤器字符串
    String base() default "";                      // 搜索基点（可含参数占位）
    SearchScope searchScope() default SearchScope.SUBTREE;
    int timeLimit() default 0;                     // 毫秒，0=不限
    int countLimit() default 0;                    // 最大返回条数，0=不限
}
```

除了过滤器本体，还顺带承载 base/scope/limits——一次声明全部搜索参数。

## 6.2 AnnotatedLdapRepositoryQuery.createQuery 全流程

`query/AnnotatedLdapRepositoryQuery.java`（:91-109）：

```java
protected LdapQuery createQuery(LdapParameterAccessor parameters) {
    String query = bind(parameters, valueContextProvider, this.query);   // 过滤器绑定
    String base  = bind(parameters, valueContextProvider, this.base);    // base 也支持占位符！
    int countLimit = queryAnnotation.countLimit();
    if (getQueryMethod().getParameters().hasLimitParameter()) {          // Limit 参数覆盖注解值
        Limit limit = parameters.getLimit();
        countLimit = limit.isLimited() ? limit.max() : 0;
    }
    return query().base(base)
            .searchScope(queryAnnotation.searchScope())
            .countLimit(countLimit)
            .timeLimit(queryAnnotation.timeLimit())
            .filter(query, parameters.getBindableParameterValues());
}
```

构造器（:76-89）为 value 和 base 各建一个 `StringBasedQuery`，并从 `ValueExpressionDelegate`（Spring Data Commons 3.5 起统一表达式门面）派生值上下文提供器。

## 6.3 StringBasedQuery：六种占位符的解析器（本章核心）

`query/StringBasedQuery.java`（@since 3.5，`b88ce59`）把 `@Query` 字符串一次性编译成"占位符模板 + 绑定列表"。构造器先过 `ParameterBindingParser`（:123-250）：

```java
private static final Pattern INDEX_PARAMETER_BINDING_PATTERN       = Pattern.compile("\\?(\\d+)");   // ?0
private static final Pattern NAMED_PARAMETER_BINDING_PATTERN       = Pattern.compile("\\:(\\w+)");   // :name
private static final Pattern INDEX_BASED_EXPRESSION_PATTERN        = Pattern.compile("\\?\\#\\{");   // ?#{ SpEL }
private static final Pattern NAME_BASED_EXPRESSION_PATTERN         = Pattern.compile("\\:\\#\\{");   // :#{ SpEL }
private static final Pattern INDEX_BASED_PROPERTY_PLACEHOLDER_PATTERN = Pattern.compile("\\?\\$\\{");// ?${ property }
private static final Pattern NAME_BASED_PROPERTY_PLACEHOLDER_PATTERN  = Pattern.compile("\\:\\$\\{");// :${ property }
```

解析算法（`transformQueryAndCollectExpressionParametersIntoBindings`，:161-221）：逐字符扫描，找到最早出现的占位符 → 表达式类占位符要**配对大括号计数**（`?#{...}` 内可嵌套 `{}`）后整段吞入 → 原位置替换为内部哨兵 `?_param_?` → 按类型生成 `ParameterBinding`（indexed/named/expression 三态，:394-445）。表达式绑定还会收集 `ExpressionDependencies`（:72-87），供容器提前知道这个查询依赖哪些 bean 属性。

执行期 `bindQuery`（:105-116）走两条流水线：

1. `BindingContext.getBindingValues()`（:333-347）：按绑定列表取值——表达式绑定走 SpEL 求值，参数绑定按名/按位取。**取值时即编码**（:355-374）：

```java
String toString = value.toString();
LdapParameters.LdapParameter parameter = parameters.getBindableParameter(index);
return parameter.hasLdapEncoder() ? parameter.getLdapEncoder().encode(toString)   // @LdapEncode 定制
                                  : LdapEncoder.filterEncode(toString);          // 默认 RFC2254 转义
```

2. `ParameterBinder.bind`（:262-293）：把编码后的值按序拼回 `?_param_?` 哨兵位，得到最终过滤器字符串。

最后交棒给 `LdapQueryBuilder.filter(String filterFormat, Object... params)`（spring-ldap :244-254）——该重载还会对传入参数再做一次 `filterEncode` 并按 `MessageFormat` 处理格式串。由于绑定阶段占位符已替换完毕，这层是**兜底防线**（也兼容直接在 @Query 里手写 MessageFormat 风格 `{0}` 的老姿势）。

## 6.4 参数编码：filterEncode 默认 + @LdapEncode 定制

注入防护的完整分层（自外向内）：

1. **方法名派生查询**：参数值经 `ContainerCriteria`/`Filter` 对象树 → `encode()` 时由 Spring LDAP 的 `EqualsFilter` 等自动 `filterEncode`——结构化构建，天然免疫注入。
2. **@Query 命名/位置参数**：StringBasedQuery 绑定时 `filterEncode`（RFC2254：`\ * ( ) NUL` 转义）。
3. **@Query 中 `@LdapEncode` 标注的参数**：实例化注解指定的 `LdapEncoder` 实现（Spring Data LDAP 自己的接口，:16-60），内置两个：
   - `NameEncoder`：`org.springframework.ldap.support.LdapEncoder.nameEncode`（DN 编码，用于 base 里的值）；
   - `LikeEncoder`：**保留通配符**——按 `*` 切分后逐段 `filterEncode` 再拼回 `*`（:30-50），让"用户输入当通配符模板"的合法场景不被误转义。
4. **SpEL 表达式的值不转义**——官方文档（`value-expressions.adoc`）明确警告：*"Values provided by SpEL expressions are not escaped according to RFC2254"*，需要自行走 `LdapEncoder` 工具类。这是全文最值得记住的安全注脚：**框架兜不住你用 SpEL 拼出来的字符串**。

## 6.5 本章小结

- `@Query` 一次声明过滤器 + base + scope + 限时限量；base 也走同一套绑定。
- `StringBasedQuery`（3.5+）是六种占位符（`:name`/`?0`/SpEL×2/属性占位符×2）的编译器：解析期产模板与绑定，执行期"取值即编码、编码后拼接"。
- 注入防护是分层的：派生查询天然安全 → 字符串参数默认 filterEncode → `@LdapEncode` 可换编码器（LikeEncoder 保通配符）→ **SpEL 是豁免区，责任回到开发者**。

---

# 七、执行层与投影：LdapQueryExecution

## 7.1 execute() 主流程

`query/AbstractLdapRepositoryQuery.java`（:74-89），所有查询方法的公共入口（`final`，不可覆写）：

```java
public final @Nullable Object execute(Object[] parameters) {
    LdapParametersParameterAccessor parameterAccessor = new LdapParametersParameterAccessor(queryMethod, parameters);
    LdapQuery query = createQuery(parameterAccessor);                 // 子类实现（第五/六章的两条路）
    ResultProcessor processor = queryMethod.getResultProcessor()
            .withDynamicProjection(parameterAccessor);                // 动态投影：Class<T> 泛型参数
    Class<?> typeToRead = processor.getReturnedType().getDomainType();
    ResultProcessingConverter converter = new ResultProcessingConverter(processor, mappingContext, instantiators);
    ResultProcessingExecution execution = new ResultProcessingExecution(
            getLdapQueryExecutionToWrap(typeToRead, converter), converter);
    return execution.execute(query);                                  // → 引擎
}
```

## 7.2 三种执行策略与异常语义

`getLdapQueryExecutionToWrap`（:91-101）按返回类型分流到 `LdapQueryExecution`（@since 2.6）的三个实现（`LdapQueryExecution.java`）：

| 实现 | 行号 | 引擎调用 | 异常/边界语义 |
|---|---|---|---|
| `FindOneExecution` | :52-71 | `operations.findOne(query, typeToRead)` | catch `EmptyResultDataAccessException` → 返回 null；>1 条时引擎抛的 `IncorrectResultSizeDataAccessException` **穿透**（与 `LdapRepository#findOne` javadoc 契约一致） |
| `CollectionExecution` | :78-93 | `operations.find(query, typeToRead)` | 直接返回 List |
| `StreamExecution` | :100-117 | `operations.find(query, typeToRead).stream().map(converter)` | **假流式**：结果已全量载入内存，Stream 只是包装（引擎真流式 `findForStream` 未被采用，见 2.6 节） |

外层 `ResultProcessingExecution`（:122-139）再对结果套一次 `ResultProcessingConverter`——两段式转换：先引擎侧把条目映射成实体，后仓库侧把实体加工成方法返回类型。

## 7.3 投影：ResultProcessingConverter 与"双 MappingContext"之谜

`ResultProcessingConverter`（:146-179）逻辑：

- 返回类型是基本类型或与源类型一致 → 原样返回；
- 接口投影 → 交 `ResultProcessor`（Commons 代理工厂）；
- DTO 投影 → `DtoInstantiatingConverter(returnedType, mappingContext, instantiators)`——**按 Spring Data 的 `PersistentEntity` 元数据实例化 DTO**。

这里出现了本文第九章的伏笔：`mappingContext` 是 Spring Data 侧的 `LdapMappingContext`，而真正的对象↔目录映射在 Spring LDAP 的 ODM 里。**两套元数据模型并存、各司其职**：ODM 管"条目→实体"（引擎职责），Spring Data 模型只管"实体→DTO/接口"（编程模型职责）。谁也没取代谁——这是理解本项目架构最重要的一张牌。

## 7.4 本章小结

- execute() 是模板方法：createQuery 留给两条查询路径，执行/投影统一。
- 三种执行策略对应三种返回形态；空结果的 Optional/ null 化、多结果的异常穿透都发生在这一层——**仓库把引擎的"抛异常语义"翻译成 Spring Data 的"空值语义"**。
- Stream 是假的，DTO/接口投影是真的（由 Spring Data 自己的元数据模型支撑）。

---

# 八、Querydsl 支持：Predicate → Filter 的翻译器

## 8.1 白话：类型安全查询的编译器

Querydsl 让你写 `queryDSLRepository.findAll(QPerson.person.lastName.eq("张"))`，编译期就有类型检查。Spring Data LDAP 的任务只有一个：把 Querydsl 的 `Predicate` 表达式树**翻译成 Spring LDAP 的 `Filter` 对象树**。两棵树的节点一一对应，翻译器自然长成了访问者模式。

## 8.2 QuerydslLdapQuery 与 LdapSerializer（本章核心）

`support/QuerydslLdapQuery.java` 实现 Querydsl 的 `FilteredClause` 接口（与 `JPAQuery` 等同族），内部挂一个 `QueryMixin` 收集 `where(Predicate...)`：

```java
public List<K> list() {
    LdapQuery ldapQuery = buildQuery();
    if (ldapQuery.filter() instanceof AbsoluteTrueFilter) {   // 无 where 条件
        return ldapOperations.findAll(entityType);            // → 引擎的 findAll 捷径
    }
    return ldapOperations.find(ldapQuery, entityType);
}
LdapQuery buildQuery() {
    Predicate where = queryMixin.getMetadata().getWhere();
    LdapQueryBuilder builder = query();
    queryCustomizer.accept(builder);
    return where != null ? builder.filter(filterGenerator.handle(where)) : builder.filter(new AbsoluteTrueFilter());
}
```

翻译器 `LdapSerializer.java` 实现 Querydsl 的 `Visitor<Object, Void>`（:17-83），逐算子映射（全部实测）：

| Querydsl 算子（Ops） | Spring LDAP Filter |
|---|---|
| `EQ` | `EqualsFilter` |
| `AND` / `OR` / `NOT` | `AndFilter` / `OrFilter` / `NotFilter`（递归 handle 子表达式） |
| `LIKE` | `LikeFilter` |
| `STARTS_WITH`（含 IC） | `LikeFilter(attr, v + "*")` |
| `ENDS_WITH`（含 IC） | `LikeFilter(attr, "*" + v)` |
| `STRING_CONTAINS`（含 IC） | `LikeFilter(attr, "*" + v + "*")` |
| `IS_NOT_NULL` / `IS_NULL` | `PresentFilter` / `NotFilter(PresentFilter)` |
| `GOE` / `LOE` | `GreaterThanOrEqualsFilter` / `LessThanOrEqualsFilter` |
| 其余（IN、BETWEEN、数值比较…） | `throw new UnsupportedOperationException` |
| `FactoryExpression`/`Param`/`SubQuery`/`TemplateExpression` | `throw new UnsupportedOperationException`（LDAP 没有子查询！） |

属性名翻译依旧走引擎：`attribute(expr)` → `odm.attributeFor(entityType, path名)`——与第五章同一把尺子。

## 8.3 QuerydslLdapPredicateExecutor 与 FluentQuerydsl

`support/QuerydslLdapPredicateExecutor.java`（@since 2.6，替代 `@Deprecated` 的 `QuerydslLdapRepository` 基类形态）实现 Commons 的 `ListQuerydslPredicateExecutor<T>`：

- `findOne/findAll/count/exists`、`findBy(...)` 流式 API（内部 `FluentQuerydsl` 实现 `FetchableFluentQuery`）；
- **能力天花板原样保留**：`findAll(predicate, Sort)`、`findAll(OrderSpecifier...)` 一律 `throw new UnsupportedOperationException("Sorting is not supported")`；分页只支持"不分页"或"第一页 + 无排序"（用 `countLimit(pageSize)` 截断 + `PageableExecutionUtils` 补 count），其余抛异常；
- `FluentQuerydsl` 的实现细节很有 LDAP 味：`count()` = 全量搜索后 `size()`（:count 内 `search(it -> true).size()`）；`exists()` = `countLimit(1)` 搜索判空；`oneValue()` = `findTop(2)`——**取两条判"多于一条"**，避免拉全量。

## 8.4 编译期：LdapAnnotationProcessor 生成 Q 类

`support/LdapAnnotationProcessor.java` 是 Querydsl APT 处理器的 LDAP 定制：

```java
@SupportedAnnotationTypes("org.springframework.ldap.odm.annotations.*")   // 扫 Spring LDAP 的注解
protected Configuration createConfiguration(RoundEnvironment roundEnv) {
    DefaultConfiguration configuration = new DefaultLdapAnnotationProcessorConfiguration(
        processingEnv, roundEnv, Collections.emptySet(), QueryEntities.class,
        Entry.class,      // ← 实体注解：又是 Spring LDAP 的
        null, null, null, Transient.class);
    configuration.setUseFields(true);   // 按字段生成（LDAP 实体惯例是公有/包内字段）
    configuration.setUseGetters(false);
    ...
}
```

`DefaultLdapAnnotationProcessorConfiguration#isValidField`（:24-30）额外把 `@Id` 字段排除出 Q 类（DN 不是可查询属性）。用法：编译期挂上处理器，`@Entry` 实体自动生成 `QPerson`，配合 `QuerydslPredicateExecutor` 即得类型安全查询。

## 8.5 本章小结

- Querydsl 支持的本质是一个 Visitor 翻译器：`Predicate` 树 → `Filter` 树，节点一一对应，属性名经 ODM 翻译。
- LDAP 没有的（子查询、数值区间、排序、深分页）翻译器直接抛异常——**类型安全也没法超越协议**。
- 能力以 Fragment 注入（3.5.3），APT 处理器在编译期从 `@Entry` 生成 Q 类；`@Id` 字段不参与查询模型。

---

# 九、映射层：Spring Data 的 PersistentEntity 与 Spring LDAP 的 ODM 并存

## 9.1 双映射上下文架构

一个实体（如测试代码 `UnitTestPerson.java`）：

```java
@Entry(objectClasses = { "inetOrgPerson", "organizationalPerson", "person", "top" })
public class UnitTestPerson {
    @Id private Name dn;
    @Attribute(name = "cn") @DnAttribute("cn") private String fullName;
    @Attribute(name = "sn") private String lastName;
    @Attribute(name = "description") private List<String> description;
    @Transient @DnAttribute("c") private String country;    // DN 组成部分但不作为属性存储
    private String telephoneNumber;                          // 无注解 → 同名属性
}
```

它同时被两套元数据模型观察：

| | Spring LDAP 的 ODM（`DefaultObjectDirectoryMapper`） | Spring Data 的模型（`LdapMappingContext`） |
|---|---|---|
| 管什么 | 对象 ↔ 目录条目：`mapToLdapDataEntry`/`mapFromLdapDataEntry`、`filterFor`、`attributeFor`、`manageClass` | DTO 投影实例化、`@Id` 属性识别（供 Commons 通用设施）、Querydsl 执行器构造 |
| 元数据来源 | `@Entry/@Id/@Attribute/@DnAttribute/@Transient` | 同一批注解（只挑关心的看）+ 字段反射 |
| 谁在用 | SimpleLdapRepository、两个 QueryCreator、LdapSerializer、LdapEntityInformation | DtoInstantiatingConverter、LdapRepositoryFactoryBean 默认值、FactoryBean 装配 |

为什么 Spring Data 侧也要建一份模型？因为 **Commons 的机器（投影、ResultProcessor、DtoInstantiatingConverter）只认 `PersistentEntity`/`PersistentProperty` 接口**——不挂进这套体系，DTO 投影和结果加工就没法运转。它是一张"接口适配卡"，不是第二套映射引擎。

## 9.2 LdapPersistentProperty：@Id 与"无关联"

`core/mapping/LdapPersistentProperty.java`（@since 2.0.4）：

```java
private final Lazy<Boolean> isId = Lazy.of(() -> isAnnotationPresent(Id.class));  // Spring LDAP 的 @Id
public boolean isIdProperty() { return isId.get(); }
protected Association<LdapPersistentProperty> createAssociation() {
    throw new UnsupportedOperationException("LDAP does not support associations");
}
```

`LdapSimpleTypes.java` 把 `javax.naming.Name` 注册为简单类型（可直接作为属性值持有）。一个诚实的化石：该类内部变量名叫 **`VAULT_SIMPLE_TYPES`**——从 Spring Data 家族其他模块复制模板时留下的痕迹（同款化石还有 CDI 扩展里 `// Store the EntityManager bean` 的注释、XSD 文件名 `spring-ldap-1.0.xsd`）。读源码时遇到这些名字不必困惑，它们与 Vault/JPA 无关。

## 9.3 LdapEntityInformation：ID 类型是 Name

`support/LdapEntityInformation.java`：`AbstractEntityInformation<T, Name>` 子类，`getId` 委派 `odm.getId(entity)`、`getIdType` 返回 `Name.class`。它把"Spring Data 到处问 ID"的通用设施（分页、判新、结果处理）接到 ODM 的 DN 计算上——又一处双模型握手点。

## 9.4 本章小结

- **两套元数据模型并存且各司其职**：ODM 管对象↔目录（引擎），`LdapMappingContext` 管投影/ID 识别/Querydsl 执行器（编程模型适配卡）。
- Spring Data 侧映射层薄到只有接口骨架：`@Id` 识别 + 拒绝关联 + Name 简单类型。
- ID 语义从上到下贯通：`LdapRepository<T, Name>` → `LdapRepositoryFactoryBean<T, S, Name>` → `LdapEntityInformation.getIdType()==Name` → `odm.getId`。

---

# 十、生态集成：XML、CDI、Spring Boot 与 AOT

## 10.1 XML 命名空间（考古层）

`config/LdapNamespaceHandler.java`（DATALDAP-1，2016-12）：

```java
public void init() {
    registerBeanDefinitionParser("repositories",
            new RepositoryBeanDefinitionParser(new LdapRepositoryConfigurationExtension()));
}
```

配合 `META-INF/spring.handlers`/`spring.schemas` 与 `spring-ldap-1.0.xsd`，支持 `<ldap:repositories ldap-template-ref="ldapTemplate"/>`。与注解方式走同一个 Extension（3.3 节），仅属性读取路径不同（`postProcess` 的 XML 重载 :76-87）。现代项目可忽略，但它是理解"Spring Data 各模块统一 <模块>:repositories schema"惯例的活化石。

## 10.2 CDI 扩展（非 Spring 容器）

`repository/cdi/`：`LdapRepositoryExtension`（`META-INF/services/jakarta.enterprise.inject.spi.Extension` 注册）观察 CDI 容器中所有 `LdapOperations` bean（按 qualifier 记录），在 `AfterBeanDiscovery` 阶段为每个仓库接口注册 `LdapRepositoryBean`——`create` 时 `new LdapRepositoryFactory(ldapOperations)` 复用同一工厂。语义：**在任何 CDI 容器（如 OpenWebBeans）里，只要有一个 `LdapOperations`，仓库就可用**。引擎依赖的注入方向与 Spring 完全一致。

## 10.3 Spring Boot 集成

Boot 4 模块化拆分后（`D:\code\3rd\spring-boot` 实测），LDAP 相关拆成两个模块：

| 模块 | 自动配置 | 管什么 |
|---|---|---|
| `spring-boot-ldap` | `LdapAutoConfiguration`（`LdapTemplate`）、`EmbeddedLdapAutoConfiguration`（UnboundID 内嵌服务器）、`LdapHealthContributorAutoConfiguration` | **引擎**（Spring LDAP 侧） |
| `spring-boot-data-ldap` | `DataLdapRepositoriesAutoConfiguration`（@since Boot 4.0.0） | **仓库**（Spring Data LDAP 侧） |

对应的 starter 也一分为二（`starter/` 目录实测）：`spring-boot-starter-ldap`（引擎）与 `spring-boot-starter-data-ldap`（仓库），各配 `-test` 变体（内嵌服务器测试支持）。

仓库侧自动配置（`DataLdapRepositoriesAutoConfiguration.java:36-43`）：

```java
@AutoConfiguration
@ConditionalOnClass({ LdapContext.class, LdapRepository.class })     // JNDI + 仓库接口都在
@ConditionalOnBooleanProperty(name = "spring.data.ldap.repositories.enabled", matchIfMissing = true)
@ConditionalOnMissingBean(LdapRepositoryFactoryBean.class)           // 用户手工配置则让位
@Import(DataLdapRepositoriesRegistrar.class)
public final class DataLdapRepositoriesAutoConfiguration { }
```

**两个模块拼起来才是完整故事**：`LdapAutoConfiguration` 造出 `LdapTemplate`，`DataLdapRepositoriesAutoConfiguration` 扫描你的仓库接口并注入这个 `LdapTemplate`——引擎与驾驶舱在 Boot 里也保持各自的边界（对照姊妹篇 Spring LDAP.md 第九章）。

## 10.4 spring.factories 与 AOT

`META-INF/spring.factories`：`RepositoryFactorySupport=LdapRepositoryFactory`——向 Spring Data 的基础设施（AOT 仓库元数据生成、Context 缓存探测等）声明"本模块的工厂类"。GraalVM native-image 下，仓库代理所需的反射元数据经 Commons 的 AOT 处理器从这条声明出发收集。本模块自身没有 `RuntimeHints` 类——没有自定义原生设施，也侧面印证了"薄"。

## 10.5 本章小结

- XML/CDI/Boot 三条集成路径最终都汇到同一个 `LdapRepositoryConfigurationExtension` + `LdapRepositoryFactory`。
- Boot 4 里引擎与仓库分属两个 starter 模块，`LdapTemplate` 先行、仓库随后——集成结构复刻了项目间的依赖结构。

---

# 十一、贯通视图：三条时间线看懂 Spring Data LDAP

## 11.1 时间线一：容器启动（装配期）

```
1. Boot: LdapAutoConfiguration            → ContextSource + LdapTemplate bean
2. Boot: DataLdapRepositoriesAutoConfiguration → Import Registrar
3. Registrar + LdapRepositoryConfigurationExtension
   ├─ 扫描 basePackages，识别 @Entry 实体 + LdapRepository 接口
   ├─ 注册 ldapMappingContext bean（ROLE_INFRASTRUCTURE）
   └─ 每接口注册 LdapRepositoryFactoryBean(ldapTemplateRef="ldapTemplate")
4. FactoryBean.afterPropertiesSet
   ├─ 校验 LdapOperations 已注入
   ├─ LdapRepositoryFactory(ldapOperations[, mappingContext])
   ├─ base=SimpleLdapRepository，EntityInformation=(odm.getId)
   ├─ QueryLookupStrategy（@Query? Annotated : PartTree）
   └─ Fragment（Querydsl? QuerydslLdapPredicateExecutor）
5. 生成 JDK 动态代理 → PersonRepository bean 就绪
```

## 11.2 时间线二：一次 findByLastName("张") 的一生

```
1. 代理拦截 → QueryExecutorMethodInterceptor 路由到 PartTreeLdapRepositoryQuery
2. LdapParametersParameterAccessor 包装参数
3. createQuery: PartTree("findByLastName") → LdapQueryCreator
     create: @Entry.base()→base；attributeFor(Person,"lastName")→"sn"
     appendCondition: SIMPLE_PROPERTY → criteria.is("张") → (sn=张)
     complete: 忽略 Sort
4. execute 主流程: 返回类型 List<Person> → CollectionExecution
5. LdapOperations.find(query, Person.class)   [引擎接管]
6. LdapTemplate.find: odm.filterFor → AND (objectclass=person) → (&(objectclass=person)(sn=张))
   manageClass → returningAttributes；search → 逐条 mapFromLdapDataEntry
7. 结果 List<Person> 返回（CollectionExecution 不做转换，ResultProcessingConverter 对实体直通）
8. 代理把 List 交回调用方
```

## 11.3 时间线三：一次 save 的一生

```
1. 代理拦截 save(person) → SimpleLdapRepository.save
2. odm.getId(person) → dn==null? → create 分支
3. LdapOperations.create(person)            [引擎接管]
4. LdapTemplate.create: getCalculatedId（@DnAttribute 拼出 DN）→ setId
   → new DirContextAdapter(id) → odm.mapToLdapDataEntry（对象→Attributes）
   → bind(context)                           （若 dn!=null → update：差量 modify 或 DN 迁移）
5. 返回同一实体实例（LDAP 的 save 没有"回填生成 ID"——DN 本来就在对象里）
```

## 11.4 从源码中提炼的设计模式视角

| 模式 | 落点 |
|---|---|
| 模板方法 | `AbstractQueryCreator` 的 create/and/or/complete 四钩子（LdapQueryCreator）；`AbstractLdapRepositoryQuery#execute` 的 final 模板 |
| 访问者 | `LdapSerializer` 遍历 Querydsl 表达式树生成 Filter 树 |
| 策略 | `LdapQueryExecution` 三执行策略；`QueryLookupStrategy` 两查询路径；`LdapEncoder` 编码策略 |
| 工厂 + 代理 | `RepositoryFactorySupport` 家族（FactoryBean→Factory→JDK 代理） |
| 适配器 | `LdapMappingContext`/`LdapPersistentProperty`：把 Spring LDAP 的注解世界适配进 Spring Data 的 PersistentEntity 世界——**整个项目就是一座适配桥** |
| 委派 | `SimpleLdapRepository` 全部方法 |

---

# 十二、附录

## 12.1 关键接口速查表

| 接口/类 | 所在 | 一句话职责 |
|---|---|---|
| `LdapRepository<T>` | spring-data-ldap | CRUD 能力集（ID=Name）+ `findOne/findAll(LdapQuery)` |
| `@Query` | spring-data-ldap | value/base/searchScope/timeLimit/countLimit |
| `@LdapEncode` / `LdapEncoder` | spring-data-ldap | 逐参数定制编码（Name/Like 内置实现） |
| `SimpleLdapRepository` | spring-data-ldap | 基类：CRUD→LdapOperations 委派 |
| `LdapRepositoryFactory` | spring-data-ldap | 代理装配；查询分流；Querydsl Fragment |
| `LdapQueryMethod` / `LdapParameters` | spring-data-ldap | 方法元数据卡（@Query、@LdapEncode） |
| `PartTreeLdapRepositoryQuery` + `LdapQueryCreator` | spring-data-ldap | 方法名 → ContainerCriteria |
| `AnnotatedLdapRepositoryQuery` + `StringBasedQuery` | spring-data-ldap | @Query 编译与绑定（六种占位符） |
| `LdapQueryExecution` | spring-data-ldap | FindOne/Collection/Stream 三策略 + 投影包装 |
| `QuerydslLdapPredicateExecutor` / `LdapSerializer` | spring-data-ldap | Predicate → Filter 翻译 |
| `LdapMappingContext` / `LdapPersistentProperty` | spring-data-ldap | Spring Data 侧元数据适配卡 |
| `LdapOperations` / `ObjectDirectoryMapper` | **spring-ldap-core** | 引擎两大接口（一切调用的终点） |
| `@Entry` 等五个注解 | **spring-ldap-core** | 实体注解（被复用，非重新定义） |
| `LdapQueryBuilder` / `filter 包` | **spring-ldap-core** | 查询表达（被复用） |

## 12.2 Spring LDAP vs Spring Data LDAP 职责边界一页纸

| 问题 | Spring LDAP（引擎） | Spring Data LDAP（驾驶舱） |
|---|---|---|
| 连接与认证 | ContextSource/池/认证策略 | 不管 |
| 搜索/写操作执行 | LdapTemplate（模板方法） | 声明接口 + 动态代理 |
| 异常转译 | NamingException→DataAccessException | 消费转译结果（NotFound→Optional 等） |
| 对象↔条目映射 | ODM（注解 + DefaultObjectDirectoryMapper） | 复用；自己只做 DTO 投影适配卡 |
| 查询表达 | Filter 树 / LdapQueryBuilder / LdapClient Spec | 方法名派生、@Query、Querydsl |
| 事务 | ContextSourceTransactionManager（补偿式） | 不管 |
| 注入防护 | LdapEncoder 工具 + Filter encode() | 绑定期 filterEncode + @LdapEncode |
| 实体注解 | 定义者 | 消费者 |
| 响应式/关联/排序 | 无 | 明确拒绝/抛异常 |

## 12.3 初学者学习路线（动手向）

1. 起一个 Boot 工程，引入 `spring-boot-starter-data-ldap` + `unboundid-ldapsdk`，配置 `spring.ldap.embedded.*` 跑起内嵌目录（姊妹篇 9.3 节有最小配置）。
2. 定义 `@Entry` 实体 + `extends LdapRepository<Person>`，注入后把 `findAll/findByLastName/save/deleteById` 各调一遍，配合 DEBUG 日志看每次发出的过滤器与 DN 操作。
3. 打开源码，按第三章装配链断点走一遍：Registrar → FactoryBean → Factory → getTargetRepository。
4. 写 `findByLastNameStartingWithAndUidNotNull`，在 `LdapQueryCreator#appendCondition` 断点看 Part.Type 分支与最终过滤器。
5. 把同一查询改写成 `@Query("(&(sn=:sn))")`，在 `StringBasedQuery#bindQuery` 断点看"哨兵替换 + 编码拼接"；再试 `@LdapEncode(LdapEncoder.LikeEncoder.class)`。
6. 试着写 `findByAgeGreaterThan`（严格大于）与 `findByCompanyAndLastName`（嵌套属性），亲眼看两个 `IllegalArgumentException`——把 5.3.3 的"协议天花板"变成肌肉记忆。

## 12.4 源码阅读入口清单（10 个关键文件）

```
1. repository/LdapRepository.java                     顶层接口（52 行，先建立词汇表）
2. repository/config/LdapRepositoryConfigurationExtension.java   识别规则（@Entry、拒绝响应式）
3. repository/support/LdapRepositoryFactory.java      代理装配与查询分流（核心）
4. repository/support/SimpleLdapRepository.java       CRUD 委派（235 行，全文精读）
5. repository/query/AbstractLdapRepositoryQuery.java  execute 模板
6. repository/query/LdapQueryCreator.java             方法名→过滤器（159 行，全文精读）
7. repository/query/AnnotatedLdapRepositoryQuery.java @Query 装配
8. repository/query/StringBasedQuery.java             六种占位符解析器（450 行）
9. repository/query/LdapQueryExecution.java           三执行策略 + 投影（182 行）
10. spring-ldap 侧：core/.../LdapTemplate.java:1715 find 的 filterFor 合并点（跨项目握手处）
```

## 结语

Spring Data LDAP 是一把称手的"短剑"：42 个文件，没有一行越界去碰连接、映射或异常——那些都是 Spring LDAP 的领地。它把力气全部花在一件事上：**让你用"声明仓库接口"的方式获得 LDAP 访问能力**，同时诚实地不掩盖 LDAP 协议的天花板（无排序、无深分页、无关联、无响应式）。理解了"LdapOperations + ObjectDirectoryMapper 两大接口是唯一的握手面"这一事实，两个项目的关系、装配链、查询编译与执行的一切细节都会各归其位。

如果只带走一句话：**Spring LDAP 教你"怎么访问目录"，Spring Data LDAP 让你"不用关心怎么访问目录"——后者的全部魔法，是前者提前十年埋好的接口。**
