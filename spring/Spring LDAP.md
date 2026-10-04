# Spring LDAP 深度源码解析（写给初学者的架构全景）

> **本文基于的源码**：`D:\code\3rd\spring-ldap`，版本 **4.2.0-SNAPSHOT**（main 分支，Git commit `b4ba1a34`，`git describe` 为 `4.1.0-145-gb4ba1a34`，即 4.1.0 发布后的第 145 个提交，已包含 4.2.0-M1 的特性）。文中所有【源码证据】的文件路径与行号均为对该快照实际读取所得。
>
> **版本取舍说明**：Spring LDAP 的核心骨架（ContextSource → LdapTemplate → Mapper/ODM）自 2.0（2014 年）以来高度稳定；3.0（2022）只是换了 Spring Framework 6 基线并清理废弃 API，4.0（2025）换到 Framework 7 / Boot 4 基线。因此本文内容对使用 Spring Boot 3.x（LDAP 3.x）的读者同样适用。1.x → 4.x 的演进对比见 1.6 节，所有"某特性属于哪个版本"的结论均经本地 git 标签（`1.3.2.RELEASE` ~ `4.2.0-M1`）逐项实证。
>
> **阅读约定**：每章"先白话、后源码"——先用一两句人话讲清"这是什么、为什么需要它"，再给出源码证据链（`模块/src/main/java/.../类名.java` + 方法名 + 行号 + 代码片段）。行号只对该快照精确，读者用 IDEA 打开源码按类名 + 方法名定位即可。

## 如何读这份文档

如果你是 Spring LDAP 初学者，推荐两遍读法：

- **第一遍（建立地图，1~2 小时）**：只读第一章（总览）每节的开头白话段、各章"本章小结"、以及第十章（贯通视图）。目标是能回答：JNDI 原生 API 有什么痛点？`ContextSource` 和 `LdapTemplate` 各管什么？`DirContextAdapter` 的 updateMode 是干嘛的？LDAP 没有 事务，Spring LDAP 怎么"回滚"？
- **第二遍（深入源码）**：对照每章【源码证据】逐行读。顺序建议：第三章（ContextSource，连接从哪来）→ 第四章（LdapTemplate，搜索怎么跑）→ 第五章（异常转译与 Filter）→ 第六章（DirContextAdapter，读取结果怎么变对象）→ 第七章（ODM）→ 第八章（分页/事务/测试，随用随查）。

---

# 一、总览：Spring LDAP 的定位、设计哲学与整体架构

## 1.1 一句话定位

**Spring LDAP 是一个把"笨重的 JNDI LDAP API"包装成"Spring 风格数据访问层"的轻量框架**：它用 `ContextSource` 统一连接/认证的获取与释放，用 `LdapTemplate` 收敛"取上下文 → 执行 → 关上下文"的模板代码，用 unchecked 异常层次替换 JNDI 的受检异常，再用可选的对象映射（Mapper 手动映射 / ODM 注解映射）把 LDAP 条目变成 Java 对象。

它解决的不是"LDAP 协议"本身的问题，而是**使用 JNDI 访问 LDAP 目录时的工程问题**：资源泄漏（Context/Enumeration 忘关）、受检异常污染业务代码、DN（Distinguished Name，可分辨名）的转义与解析陷阱、Active Directory 的 referral 坑、以及"LDAP 操作天生不可回滚"的事务难题。

Spring LDAP 与 JNDI 的关系，一句话概括（官方参考文档 `modules/ROOT/pages/introduction.adoc` 的口径）："The Spring LDAP framework provides a thin java wrapper around JNDI"——它是**包装层，不是替代层**：所有操作最终仍然落在 `javax.naming.directory.DirContext` 上，理解 JNDI 仍然是理解 Spring LDAP 的前提。

## 1.2 设计哲学：读源码前先记住五句话

1. **JNDI 是底层货币，Spring LDAP 不发行新货币**。框架的公共 API 大量出现 `DirContext`、`Attributes`、`Name` 这些 `javax.naming` 原生类型——`ContextSource` 的三个方法返回的就是 `DirContext`（见 3.1 节）。学会了 JNDI 的数据模型，Spring LDAP 的 API 一眼看懂；这与 Spring Data 系"发明自己的对象模型"的路线完全不同。
2. **模板方法收敛资源管理**。LDAP 客户端每操作一次就要"创建上下文 → 使用 → 关闭"，漏关就是连接泄漏。`LdapTemplate` 把"获取、关闭"放进自己手里，把"做什么"留给回调：`executeReadOnly(ContextExecutor)` / `search(..., ContextMapper)`（见 4.2、4.3 节）。
3. **受检异常一律转译成 unchecked**。JNDI 的 `javax.naming.NamingException` 是受检异常，每个调用点都要 `try/catch`。Spring LDAP 定义了自己的 `org.springframework.ldap.NamingException extends NestedRuntimeException`（非受检），并提供了从 javax 层次到 Spring 层次的**逐类镜像转译表**（见 5.1、5.2 节）。
4. **对象映射是可选件，不是世界观**。你可以全程用 `AttributesMapper`/`ContextMapper` 手动映射，也可以给类打上 `@Entry`/`@Id`/`@Attribute` 注解走 ODM（Object-Directory Mapping）。但 ODM 刻意不做 ORM：没有关联关系、没有一级缓存、没有延迟加载——LDAP 目录里没有外键，只有 DN 引用（见第七章）。
5. **不可回滚的操作，用补偿模拟回滚**。LDAP 协议没有事务。Spring LDAP 的答案是 `ContextSourceTransactionManager`：每个写操作在真正执行前先把"原始状态"备份（比如把原条目 rename 到临时 DN），回滚时反向执行补偿动作（见 8.2 节）。这是 Spring 全家桶里独一无二的设计。

## 1.3 模块分层全景

仓库根 `settings.gradle`（settings.gradle:10-20）声明了 7 个真正的构建模块（其余是集成测试与沙箱）：

| 模块（构件名） | 内容 | 规模（4.2.0-SNAPSHOT） |
|---|---|---|
| `spring-ldap-core` | **全部主干**：ContextSource、LdapTemplate、LdapClient、异常体系、filter/query、ODM 注解与映射器、连接池、补偿事务、Controls、AOT | 285 个 Java 文件 |
| `spring-ldap-odm` | 遗留的 `OdmManager` API（2.0 起被 core 内的 `ObjectDirectoryMapper` 取代，已 @Deprecated） | 10 个 Java 文件 |
| `spring-ldap-ldif-core` | LDIF 文件解析器（`LdifParser`）与 schema 校验 | 若干 |
| `spring-ldap-test` | 测试支撑：UnboundID 内嵌 LDAP 服务器、`LdapTestUtils`、`LdifPopulator` | 若干 |
| `spring-ldap-dependencies` | BOM 平台（`java-platform`），统一锁 Framework/Micrometer/pool 版本 | 1 个 build.gradle |
| `spring-ldap-sandbox` | 实验性代码 | — |
| `test/integration-tests{,-openldap,-sunone,-ad}` | 对接真实 LDAP 服务器的集成测试（OpenLDAP / Sun ONE / Active Directory） | — |

> **历史注**：1.x 时代还有 `core-tiger`（JDK 5 泛型版，2.x 已并入 core）、`ldif/ldif-batch`（Spring Batch 集成，3.0 移除）、`test-support-unboundid`（2.3.x 独立存在，3.0 并回 test-support）、`samples/*`（后整体迁出到独立仓库 spring-ldap-samples，commit `995e4c20`）。core-tiger 的移除可由 git 实证：`git log --oneline --follow 3.0.0 -- core-tiger/build.gradle` 显示 commit `7a3e8558 "Remove core-tiger"`。

再看 core 模块内部的**包分层**（`core/src/main/java/org/springframework/ldap/` 下）：

```
org.springframework.ldap                     ← 异常体系（30+ 个 NamingException 子类）
org.springframework.ldap.core                ← LdapTemplate / LdapClient / DirContextAdapter / Mapper 接口 / DN 解析器
org.springframework.ldap.core.support        ← ContextSource 体系、认证策略、DirObjectFactory、ObservationContextSource
org.springframework.ldap.filter              ← 过滤器对象树（AndFilter/EqualsFilter/...）
org.springframework.ldap.query               ← LdapQueryBuilder 流式查询 DSL
org.springframework.ldap.control             ← LDAP Controls：分页/排序（DirContextProcessor 体系）
org.springframework.ldap.odm.*               ← ODM：annotations / core(impl) / typeconversion
org.springframework.ldap.pool / pool2        ← commons-pool 1.x 与 2.x 两代连接池适配
org.springframework.ldap.transaction.compensating ← 补偿事务（含 org.springframework.transaction.compensating 通用骨架）
org.springframework.ldap.config              ← XML 命名空间 <ldap:context-source> 解析器
org.springframework.ldap.aot.hint            ← GraalVM Native 运行时提示
org.springframework.ldap.support             ← LdapUtils / LdapEncoder 等工具
org.springframework.ldap.authentication      ← AuthenticationSource 装饰器
```

## 1.4 模块依赖图（以 core/build.gradle 的依赖声明实证）

`core/build.gradle:6-23`：

```groovy
api "org.springframework:spring-core"
api "org.springframework:spring-beans"
api "org.springframework:spring-context"
api "org.springframework:spring-tx"
api "io.micrometer:micrometer-core"

implementation "org.slf4j:slf4j-api"
provided "com.sun:ldapbp"
optional "org.springframework:spring-jdbc"
optional "org.springframework:spring-orm"
optional "com.querydsl:querydsl-core"
optional "com.querydsl:querydsl-apt"
optional "commons-pool:commons-pool"
optional "org.apache.commons:commons-pool2"
```

依赖图（自上而下为"被依赖"）：

```
                spring-core / spring-beans / spring-context / spring-tx
                     ↑（api，强依赖；spring-tx 提供事务骨架与 org.springframework.dao 异常）
              spring-ldap-core ←—— micrometer-core（api，ObservationContextSource 直接用）
                ↑      ↑
   commons-pool(1.x)/pool2（optional，池化 ContextSource）   querydsl（optional，Spring Data LDAP 仓库的 QueryDSL 支持）
                ↑
   com.sun:ldapbp（provided：老 JDK 缺Controls实现时的兜底类库，见 8.1）
```

两个值得注意的细节：

- **`micrometer-core` 是 api 级依赖**——Spring LDAP 3.3 起把可观测性（`ObservationContextSource`，见 8.5 节）当成了"一等公民"而不是 optional 附加品，这与多数 Spring 模块把 micrometer 声明为 optional 的做法不同。
- **`spring-tx` 是强依赖**：不仅因为补偿事务继承了 `AbstractPlatformTransactionManager`（`org.springframework.transaction`），还因为 `LdapTemplate` 的 `authenticate` 直接复用了 `org.springframework.dao` 包的 `EmptyResultDataAccessException`/`IncorrectResultSizeDataAccessException`（见 4.6 节）——这两类异常就定义在 spring-tx 里。

## 1.5 关键问题 → Spring LDAP 方案映射（全文导览）

| 你关心的问题 | Spring LDAP 的答案 | 本文位置 |
|---|---|---|
| JNDI 模板代码太多、Context 忘关泄漏 | `LdapTemplate`：`executeWithContext` 统一取/还连接 | 4.2 |
| NamingException 是受检异常，代码被 try/catch 淹没 | unchecked 的 `org.springframework.ldap.NamingException` 层次 + `LdapUtils.convertLdapException` 镜像转译表 | 5.1、5.2 |
| 搜索结果怎么变成 Java 对象 | `AttributesMapper` / `ContextMapper` 回调；`DefaultDirObjectFactory` 直接把 JNDI 返回值变成 `DirContextAdapter` | 4.3、6.3 |
| filter 字符串拼接容易写错、被注入 | `filter` 包对象树 + `LdapQueryBuilder`；`LdapEncoder.filterEncode` 转义 | 5.3、5.4、5.5 |
| Active Directory 搜索总抛 PartialResultException | `setIgnorePartialResultException(true)` 开关 | 4.4 |
| 用户登录怎么校验（LDAP 认证） | `LdapTemplate.authenticate`：先搜条目，再拿"条目 DN + 用户密码"开一条新连接 | 4.6 |
| 增删改对象要手拼 Attributes 太繁琐 | ODM：`@Entry`/`@Id`/`@Attribute` 注解 + `DefaultObjectDirectoryMapper` 双向映射 | 第七章 |
| LDAP 搜索结果太多，要分页 | `PagedResultsDirContextProcessor`（RFC 2696 控件）+ cookie 翻页 | 8.1 |
| LDAP 操作没有事务，怎么回滚 | 补偿事务：`ContextSourceTransactionManager` + "先把原条目改名挪走"策略 | 8.2 |
| 单元测试不想起真实 LDAP 服务器 | `spring-ldap-test`：UnboundID 内嵌服务器 | 8.4 |
| 想接入 Micrometer 观测 | `ObservationContextSource`：把每个 DirContext 操作包成 Observation | 8.5 |
| 与 Spring Boot / Spring Data 怎么配合 | Boot 4 的 `spring-boot-ldap` / `spring-boot-data-ldap` 模块；Spring Data LDAP 仓库抽象 | 第九章 |

## 1.6 版本演进：1.x → 2.x → 3.x → 4.x 关键变化对比

### 1.6.1 版本时间线与运行基线

以下日期均由本地 git tag 提交时间实证（`git log -1 --format=%ci <tag>`）：

| 版本 | 发布日期 | 基线 | 一句话主题 |
|---|---|---|---|
| 1.3.2.RELEASE | 2013-08-26 | Spring 3.x / Java 5（core-tiger） | 1.x 终章：`DistinguishedName` 时代 |
| 2.0.0.RELEASE | 2014-01-09 | Spring 3/4 | **大重写**：`Name`/`LdapName` 取代 `DistinguishedName`，ODM 注解并入 LdapTemplate，`LdapQueryBuilder`，pool2 支持，`test-support` 模块出现 |
| 2.1.0.RELEASE | 2016-05-16 | Spring 4 | 增量属性映射（ranged attributes）等 |
| 2.3.3.RELEASE | 2020-05-05 | Spring 4.3/5 | 2.x 终章 |
| 3.0.0 | 2022-11-18 | **Spring Framework 6 / Java 17** | 移除 core-tiger、ldif-batch 与全部 2.0 前废弃 API；`searchForStream`（@since 3.0） |
| 3.1.0 | 2023-05-11 | Framework 6 | **`LdapClient` 流式 API**（@since 3.1，对标 RestClient 风格） |
| 3.2.0 | 2023-11-16 | Framework 6.1 | 常规迭代 |
| 3.3.0 | 2025-05-15 | Framework 6.2 | **`ObservationContextSource`**（@since 3.3，micrometer-core 转 api） |
| 4.0.0 | 2025-11-13 | **Spring Framework 7 / Boot 4** | JSpecify 空安全注解（`ldap-nullability` 校验插件）、4.x 基线 |
| 4.1.0 | 2026-06-08 | Framework 7.0.8（dependencies/build.gradle:21 实证） | `LdapClient.SearchSpec#single/optional`、`ControlExchange` 体系（均 @since 4.1） |
| 4.2.0-M1 | 2026-09-20 | Framework 7 | `DirContextAdapter#setMayRemoveByValue`、`AttributeSchema` 名称校验（modules/ROOT/pages/whats-new.adoc 实证） |

### 1.6.2 特性引入版本对照表（git/源码实证，可复现）

| 特性 | 引入版本 | 实证 |
|---|---|---|
| `LdapTemplate` 模板内核 | 1.0（2006 起） | 类 javadoc "Executes core LDAP functionality..."（LdapTemplate.java:69-71） |
| ODM（第一代 `OdmManager`） | 1.3.1（2010） | changelog.txt："Added an object-directory mapping framework (ODM). Contributed by Paul Harvey."；odm 模块现存类已 @Deprecated |
| ODM 注解 + `ObjectDirectoryMapper`（第二代） | 2.0 | `LdapQueryBuilder.java:54 "@since 2.0"`、`PooledContextSource.java:81 "@since 2.0"`、LdapTemplate 的 `getObjectDirectoryMapper` javadoc `@since 2.0`（LdapTemplate.java:144） |
| `authenticate(LdapQuery, password)` 系 API | 2.0 | LdapTemplate.java:1888 "@since 2.0" |
| pool2 版 `PooledContextSource` | 2.0 | `PooledContextSource.java:81` |
| `searchForStream`（Stream API） | 3.0 | `LdapOperations.java:1550 "@since 3.0"` |
| `LdapClient` / `DefaultLdapClient` / Builder | 3.1 | `LdapClient.java:50`、`DefaultLdapClient.java:64` 均标 `@since 3.1` |
| filter 值的**调用方显式编码**（弃用自动编码构造器） | 3.3 | `CompareFilter.java:36 @Deprecated(since = "3.3")`、`CompareFilter.java:66 @Deprecated(forRemoval = true, since = "3.3")` |
| `ObservationContextSource` 可观测 | 3.3 | `ObservationContextSource.java:65 "@since 3.3"` |
| JSpecify 空安全 | 4.0 | 全库 `@Nullable` 来自 `org.jspecify.annotations`；buildSrc 有 `ldap-nullability.gradle` 校验插件 |
| `ControlExchange` 系列（Controls 的新姿势） | 4.1 | `control/ControlExchange.java:39 "@since 4.1"` 等 |
| `SearchSpec#single()/optional()` | 4.1 | `LdapClient.java:388-421 "@since 4.1"` |
| `DirContextAdapter#setMayRemoveByValue` | 4.2（main） | `DirContextAdapter.java:242-247 "@since 4.2"` |

### 1.6.3 对初学者的意义：哪些知识过时了，哪些永远有效

- **过时**：`DistinguishedName` 类（`core/DistinguishedName.java:101 @Deprecated`，1.x 时代的核心，JavaCC 生成的 `DnParserImpl` 至今还在为它解析字符串）——新代码一律用 `javax.naming.ldap.LdapName` + `LdapUtils.newLdapName()`；XML 命名空间配置（`org.springframework.ldap.config` 包，`<ldap:context-source>`）——已被 Boot 自动配置取代；`OdmManager`——被 `ObjectDirectoryMapper` 取代。
- **永远有效**：LDAP 数据模型（条目/DN/属性/objectClass）、`ContextSource` 的三方法契约、`LdapTemplate` 的回调映射模型、异常转译表——这些从 1.x 到 4.x 骨架未变。

## 1.7 全文章节地图

- **第二章 背景与分层答案**：LDAP 数据模型 5 分钟速成；JNDI 的三宗罪；Spring LDAP 的四层解法。
- **第三章 ContextSource 连接内核**：三方法契约、环境表组装、认证策略插槽、两代连接池。
- **第四章 LdapTemplate 模板内核**：executeWithContext、search 全链路逐行、三个 ignore 开关、写操作、authenticate、LdapClient 新 API。
- **第五章 异常转译与查询构建**：镜像异常层次、转译表、Filter 对象树、LdapQueryBuilder、注入防护与 3.3 的 API 变化。
- **第六章 DirContextAdapter 与对象工厂**：条目包装器、updateMode 差量计算、`DefaultDirObjectFactory` 的 JNDI SPI 钩子、DN 处理的兴衰。
- **第七章 ODM**：五个注解、元数据缓存、双向映射、类型转换、Spring Data LDAP。
- **第八章 进阶机制**：分页 Controls、补偿事务、LDIF、内嵌服务器、可观测、AOT。
- **第九章 Spring Boot 集成**：Boot 4 的 spring-boot-ldap / spring-boot-data-ldap。
- **第十章 贯通视图**：三条时间线 + 设计模式视角。
- **第十一章 附录**：速查表、学习路线、源码入口清单。

---

# 二、背景：LDAP、JNDI 与 Spring LDAP 要解决的问题

## 2.1 LDAP 是什么：目录服务的最小数据模型

**LDAP（Lightweight Directory Access Protocol，轻量目录访问协议）**是一个为"读多写少、层次化"数据设计的网络协议，典型用途是组织人员/设备清单与统一登录（企业里"域账号"背后基本就是 LDAP 目录，如 OpenLDAP、Active Directory、Apache DS）。协议标准在 RFC 2251~2256（v3），分页控件在 RFC 2696——spring-ldap 仓库根的 changelog.txt 头部就列着这份 RFC 清单。

理解 Spring LDAP 只需要四个概念：

1. **条目（Entry）**：目录树上的一个节点，比如一个人、一台打印机。
2. **DN（Distinguished Name，可分辨名）**：条目的"全路径主键"，如 `uid=jdoe,ou=people,dc=springframework,dc=org`——从左到右是"从叶子到根"。DN 的每一段叫 **RDN**（Relative DN）。DN 里的逗号、加号、反斜杠等字符必须转义（这就是 `LdapEncoder.nameEncode` 存在的理由，见 5.5 节）。
3. **属性（Attribute）**：条目的键值对，且**同一个属性可以有多个值**（multi-valued），属性名大小写不敏感（所以 ODM 内部有个 `CaseIgnoreString` 包装类，`core/odm/core/impl/CaseIgnoreString.java`）。
4. **objectClass**：特殊的属性，声明条目"是什么类型"，决定它必须/可以有哪些属性——类似关系库的表结构，但一个条目可以同时属于多个 objectClass。

**搜索（search）是 LDAP 的灵魂操作**：给它一个基 DN（从哪棵子树开始）、一个搜索范围（OBJECT=单条目 / ONELEVEL=一层 / SUBTREE=整棵子树）、一个**过滤器（filter）**——形如 `(&(objectClass=person)(uid=jdoe))` 的表达式，`&` 是与、`|` 是或、`!` 是非。过滤器注入（在 filter 里拼用户输入而不转义）是 LDAP 世界里的"SQL 注入"，5.5 节会讲 Spring LDAP 的防护。

## 2.2 JNDI：Java 世界访问 LDAP 的官方姿势，以及它的三宗罪

Java 访问 LDAP 的官方 API 是 **JNDI（Java Naming and Directory Interface）**（JDK 内置的 `javax.naming.*`，在模块化后位于 `java.naming` 模块）。原生写一个搜索长这样：

```java
Hashtable<String, Object> env = new Hashtable<>();
env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
env.put(Context.PROVIDER_URL, "ldap://localhost:389");
env.put(Context.SECURITY_AUTHENTICATION, "simple");
env.put(Context.SECURITY_PRINCIPAL, "cn=admin,dc=example,dc=org");   // 绑定的用户 DN
env.put(Context.SECURITY_CREDENTIALS, "password");
DirContext ctx = null;
try {
    ctx = new InitialDirContext(env);                     // ① 建连接（此时才真正认证）
    SearchControls controls = new SearchControls();
    controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
    controls.setReturningObjFlag(true);
    NamingEnumeration<SearchResult> results =
            ctx.search("ou=people", "(objectClass=person)", controls);  // ② 搜索
    while (results.hasMore()) {
        SearchResult sr = results.next();
        Attributes attrs = sr.getAttributes();           // ③ 手工读属性
    }
} catch (NamingException e) {                             // ④ 受检异常
    ...
} finally {
    try { if (ctx != null) ctx.close(); } catch (NamingException ignored) {}  // ⑤ 忘关就泄漏
}
```

**三宗罪**：

1. **环境表 Hashtable 手工拼装 + 每次新建连接**：`DirContext` 的创建就是一次 TCP+BIND，忘了复用/关闭就是性能与泄漏双重问题。
2. **受检异常 `NamingException`**：每个调用点都得 catch，而且 30 多个子类的语义（名字不存在？密码错？超限？）全靠 catch 里看类型。
3. **结果处理繁琐且陷阱多**：`NamingEnumeration` 也要关闭；`CompositeName.toString()` 会搞坏 DN 转义（`DefaultDirObjectFactory` 源码注释原话："CompositeName.toString() completely screws up the formatting in some cases"，见 6.3 节）；AD 服务器的 referral 处理会抛 `PartialResultException`。

Spring LDAP 的每一层，都精确对应其中一宗罪（外加"对象映射"这块加强板）。

## 2.3 Spring LDAP 的分层答案

```
┌────────────────────────────────────────────────────────────────┐
│  ODM / Spring Data LDAP   @Entry 注解 → 对象（可选，第七章）      │
├────────────────────────────────────────────────────────────────┤
│  Mapper / DirContextAdapter   搜索结果 → Java 对象（第六章）      │
├────────────────────────────────────────────────────────────────┤
│  LdapTemplate / LdapClient   模板方法：取连接→执行→关闭+异常转译   │
│                              filter/query/controls（第四、五章）  │
├────────────────────────────────────────────────────────────────┤
│  ContextSource                连接的获取/认证/池化（第三章）        │
├────────────────────────────────────────────────────────────────┤
│  javax.naming DirContext      JNDI（JDK 自带，java.naming 模块）  │
└────────────────────────────────────────────────────────────────┘
```

每层只依赖下一层的接口：`LdapTemplate` 只认识 `ContextSource`，`ContextSource` 只生产 `DirContext`。这个分层在类图上的锚点是 `LdapTemplate` 的两个字段（LdapTemplate.java:94、108）：

```java
private ContextSource contextSource = new NullContextSource();
private ObjectDirectoryMapper odm = new DefaultObjectDirectoryMapper();
```

`NullContextSource` 是内部兜底实现（LdapTemplate.java:1968）——`afterPropertiesSet()` 里会检查并拒绝它（LdapTemplate.java:1135：`Assert.isTrue(!(this.contextSource instanceof NullContextSource), "Property 'contextSource' must be set.")`），保证"忘了配置 ContextSource"在启动期就失败，而不是第一次搜索时才炸。

## 2.4 本章小结

LDAP 是一棵"以 DN 为主键、以多值属性为内容"的目录树，搜索是核心操作。JNDI 是官方但笨重的访问层；Spring LDAP 用 ContextSource 管**连接**、LdapTemplate 管**流程与异常**、Mapper/ODM 管**对象映射**、补偿事务管**回滚**，四层各司其职，而底层永远是 `DirContext`。

---

# 三、ContextSource：连接与认证内核

## 3.1 ContextSource 接口：三种取连接的方式

**白话**：ContextSource 就是"LDAP 连接工厂"——但注意它生产的不是 Socket，而是**已经完成绑定（认证）的 `DirContext`**。也就是说，"用谁的身份连"这个问题，在 `get` 调用那一刻就定死了。

【源码证据】`core/src/main/java/org/springframework/ldap/core/ContextSource.java:33-62`：

```java
public interface ContextSource {

	DirContext getReadOnlyContext() throws NamingException;      // :41

	DirContext getReadWriteContext() throws NamingException;     // :48

	DirContext getContext(String principal, String credentials) throws NamingException;  // :62
}
```

三个方法的分工：

- **getReadOnlyContext()**：读操作用的连接。注意这里的"只读"**不是**协议层面的只读——LDAP 没有"只读会话"的概念——它只是约定：实现可以用"匿名/低权限/可池化"的方式给一条连接，用于 search/list 这类读。
- **getReadWriteContext()**：写操作（bind/unbind/modify/rename）用的连接，必须用配置的主账号。
- **getContext(principal, credentials)**：**用任意用户名密码**换取一条连接——这正是 `authenticate()` 的底层依赖（4.6 节），也是"用用户密码去 BIND 一次来验证密码"的认证思路的落点。

继承自它的 `BaseLdapPathContextSource`（`core/support/BaseLdapPathContextSource.java`）额外暴露"这个服务器配置的 base DN 是什么"（`getBaseLdapPath()`），供 `BaseLdapNameAware` Bean 在运行时拿到相对 DN 的基准（`core/support/BaseLdapPathBeanPostProcessor.java:~45` 负责注入）。

## 3.2 AbstractContextSource：环境表（Hashtable）的组装流水线

**白话**：JNDI 建连接要传一张 `Hashtable<String,Object>` 环境表（URL、工厂类、账号密码……）。`AbstractContextSource` 的全部工作就是**把 Spring 的配置属性（urls/base/userDn/password/pooled/...）翻译成这张表**，然后调 JNDI 工厂造出 `DirContext`。

【源码证据】`core/src/main/java/org/springframework/ldap/core/support/AbstractContextSource.java:75`：

```java
public abstract class AbstractContextSource implements BaseLdapPathContextSource, InitializingBean {
```

关键字段（同文件）：

| 字段 | 行号 | 作用 |
|---|---|---|
| `base`（LdapName） | :91 | 服务器 base DN；之后所有相对 DN 都以它为根 |
| `baseEnv` | :111 | 公共环境表（URL、工厂、referral 策略……） |
| `anonymousEnv` | :113 | 匿名只读时用的环境表（**启动期组装一次并缓存**，见 afterPropertiesSet） |
| `authenticationSource` | :115 | 提供 principal/credentials 的策略接口（`core/AuthenticationSource.java:26`） |
| `authenticationStrategy` | :127 | 认证方式策略插槽，默认 `SimpleDirContextAuthenticationStrategy` |

**核心流程 `doGetContext`**（同文件:145-164）——每次取连接的三步：

```java
private DirContext doGetContext(String principal, String credentials, boolean explicitlyDisablePooling) {
	Hashtable<String, Object> env = getAuthenticatedEnv(principal, credentials);
	if (explicitlyDisablePooling) {
		env.remove(SUN_LDAP_POOLING_FLAG);            // :148 —— 关键一笔，见 3.4
	}

	DirContext ctx = createContext(env);              // :152 —— 真正调 JNDI 工厂

	try {
		DirContext processedDirContext = this.authenticationStrategy
				.processContextAfterCreation(ctx, principal, credentials);   // :155
		return processedDirContext;
	}
	catch (NamingException ex) {
		closeContext(ctx);                            // 失败也必须归还/关闭
		throw LdapUtils.convertLdapException(ex);     // 受检 → 非受检
	}
}
```

三个取连接方法都汇到它（同文件:138-196）：`getContext(principal, credentials)` 显式禁池化（`:142` 传 `EXPLICITLY_DISABLE_POOLING=true`）；`getReadOnlyContext()`/`getReadWriteContext()` 走 `AuthenticationSource` 提供的主账号，且当 `anonymousReadOnly=true` 时读操作直接用匿名环境表 `createContext(getAnonymousEnv())`（`:169-182`）。

还有两个模板方法留给子类：

- `createContext(Hashtable)`（:350）：默认实现即 `new InitialDirContext(environment)`；**唯一抽象方法**是 `getDirContextInstance(Hashtable)`（:667），`LdapContextSource`（`core/support/LdapContextSource.java:36`）就是标准 JNDI 实现，测试用的子类（如 `test-support` 的 `TestContextSourceFactoryBean`）可以在这里换成内嵌服务器。
- `afterPropertiesSet()`（:413）：校验 url/base 等必填项，把 `baseEnv` 组装好（`assembleProviderUrlString` :230 负责把多个 `ldap://host:port` 拼成 PROVIDER_URL——多 URL 即 JNDI 原生的**故障转移列表**），并把 base DN 存入 `baseLdapPath` 供 `BaseLdapPathBeanPostProcessor` 分发。

## 3.3 DirContextAuthenticationStrategy：认证方式的策略插槽

**白话**：LDAP 的认证不止"用户名+密码"（simple）一种，还有 DIGEST-MD5 挑战应答、以及"先明文连上、再 StartTLS 升级加密后再绑定"。这些差异落在**环境表的哪个 key、什么时机设置**上。Spring LDAP 把这个差异抽成两段式策略接口。

【源码证据】`core/src/main/java/org/springframework/ldap/core/support/DirContextAuthenticationStrategy.java:38`：

```java
public interface DirContextAuthenticationStrategy {

	void setupEnvironment(Hashtable<String, Object> env, String principal, String credentials) throws NamingException;

	DirContext processContextAfterCreation(DirContext ctx, String principal, String credentials)
			throws NamingException;
}
```

第一段在**连接创建前**改环境表，第二段在**连接创建后**加工 `DirContext`（TLS 必须在"连接已建立但未绑定"的窗口期做 StartTLS 握手，所以必须有第二段）。内置实现三个：

| 实现 | 机制 |
|---|---|
| `SimpleDirContextAuthenticationStrategy` | 设置 `SECURITY_AUTHENTICATION=simple` + `SECURITY_PRINCIPAL/SECURITY_CREDENTIALS`（`SimpleDirContextAuthenticationStrategy.java:45-52`）；有趣的细节：设置了 userDn 但密码为空时直接抛 `AuthenticationException`（:46-49）——因为"空密码绑定"在部分服务器上会被当成**匿名认证成功**，是著名的安全坑 |
| `DigestMd5DirContextAuthenticationStrategy` | `SECURITY_AUTHENTICATION=digest-md5`，密码在连接创建后经 `rebind` 完成挑战应答 |
| `AbstractTlsDirContextAuthenticationStrategy` 及其子类 | 创建后拿 `(LdapContext) ctx` 做 `startTls()`，握手成功后再绑定；两个子类：`DefaultTlsDirContextAuthenticationStrategy`（StartTLS 后继续 simple 绑定，并用 `ctx.lookup("")` 强制一次服务器调用使环境生效——注释点名 gh-430/gh-502）与 `ExternalTlsDirContextAuthenticationStrategy`（客户端证书场景，改设 `SECURITY_AUTHENTICATION=EXTERNAL`） |

`AbstractContextSource.setupAuthenticatedEnvironment`（:200-211）是策略的调用点，javadoc 明说："any customization to the authentication mechanism should be managed by setting a different DirContextAuthenticationStrategy"——**加新认证方式 = 加一个策略实现，不改 AbstractContextSource 一行**。

## 3.4 连接池：两代实现与 LDAP-183 的教训

**白话**：JDK 自带的 `LdapCtxFactory` 其实**内置了连接池**——只要在环境表里放一个 `com.sun.jndi.ldap.connect.pool` 标志（`AbstractContextSource.java:125 SUN_LDAP_POOLING_FLAG`），底层 Socket 就会被池化复用。Spring LDAP 的 `pooled=true` 默认就是开这个开关。除此之外，框架还提供两套**显式池**（池的是整个 `DirContext` 对象），以及一个针对认证场景的特殊处理。

【源码证据】三个层次：

1. **JNDI 内置池**（默认路径）：`pooled=true` 时 `SUN_LDAP_POOLING_FLAG` 进入环境表。关键陷阱在 `getContext(principal, credentials)`：**认证场景必须显式禁用这个池**（doGetContext:148 `env.remove(SUN_LDAP_POOLING_FLAG)`），注释点名 LDAP-183——因为池会按"用户 DN"复用连接，用户改密码后，池里旧连接仍然有效，**改密后的旧密码还能登录**。这是"上下文池 + 认证"组合的经典安全坑，Spring LDAP 用"认证专用连接永远不走池"一刀切掉。
2. **commons-pool 1.x 版**：`org.springframework.ldap.pool.factory.PoolingContextSource`（`pool/factory/PoolingContextSource.java:146`，基于 `GenericKeyedObjectPool`，key 是"读/写"两种用途）+ `DirContextPoolableObjectFactory`（:79）负责 passivate 时校验连接有效性。
3. **commons-pool2 版（2.0 起）**：`org.springframework.ldap.pool2.factory.PooledContextSource`（`pool2/factory/PooledContextSource.java:83`，`@since 2.0`）+ `DirContextPooledObjectFactory`；`PoolConfig`（`pool2/factory/PoolConfig.java`）把 pool2 的全部参数（maxTotal/maxIdle/testWhileIdle...）做成可绑定属性。两套实现**并存至今**——因为历史用户分别绑定了两代 commons-pool。

另外还有个测试神器 `SingleContextSource`（`core/support/SingleContextSource.java:43`）：永远返回**同一条**连接且 `close()` 变成空操作——专治"集成测试想断言同一线程复用连接"或"内嵌服务器只接受一条连接"的场景。

## 3.5 本章小结

ContextSource 的世界只有三件事：**三方法契约**（读/写/指定账号）、**环境表流水线**（配置 → Hashtable → InitialDirContext，`afterPropertiesSet` 一次性组装、匿名环境表启动期缓存）、**认证策略插槽**（simple/DIGEST-MD5/TLS 两段式）。池化有三种姿势（JNDI 内置池默认、pool/pool2 显式池可选），而"认证连接禁池化"是必须记住的安全细节（LDAP-183）。

---

# 四、LdapTemplate：模板方法内核

## 4.1 白话：为什么需要模板

回到 2.2 节的 JNDI 五步样板代码，真正"因业务而异"的只有一步——② 里"执行什么操作"。`LdapTemplate`（`core/LdapTemplate.java:82`，1987 行，实现 `LdapOperations` 接口 + `InitializingBean`）把其余四步（取连接、转换异常、关闭 Context、关闭 Enumeration）全部收进框架，暴露三个层次的 API：

1. **裸模板**：`executeReadOnly/executeReadWrite(ContextExecutor)`——你拿到 DirContext，随便干什么；
2. **操作族**：`search/lookup/bind/unbind/rebind/modifyAttributes/rename/list/listBindings`——最常用；
3. **ODM 族**：`find/findByDn/create/update/delete`——传注解对象（第七章）。

外加 3.1 起的第四种：`LdapClient` 流式 API（4.7 节）。

## 4.2 executeWithContext：所有模板的最终汇合点

【源码证据】`core/LdapTemplate.java:801-825`：

```java
public <T extends @Nullable Object> T executeReadOnly(ContextExecutor<T> ce) {
	DirContext ctx = this.contextSource.getReadOnlyContext();   // :802
	return executeWithContext(ce, ctx);
}

public <T extends @Nullable Object> T executeReadWrite(ContextExecutor<T> ce) {
	DirContext ctx = this.contextSource.getReadWriteContext();
	return executeWithContext(ce, ctx);
}

private <T extends @Nullable Object> T executeWithContext(ContextExecutor<T> ce, DirContext ctx) {
	try {
		return ce.executeWithContext(ctx);
	}
	catch (javax.naming.NamingException ex) {
		throw LdapUtils.convertLdapException(ex);        // :819 受检 → 非受检
	}
	finally {
		closeContext(ctx);                               // :822 永远归还
	}
}
```

五步样板代码被压缩到 `ce.executeWithContext(ctx)` 一行业务。`ContextExecutor` 接口（`core/ContextExecutor.java:41`）就是那颗"业务塞子"。

## 4.3 search 全链路：SearchExecutor + CallbackHandler + DirContextProcessor（逐行）

**白话**：search 是最复杂的操作——除了取/还连接，还要**流式消费**一个 `NamingEnumeration`（结果可能非常大，不能一次装进内存），并且要在搜索前后给 LDAP Controls 留钩子（分页/排序都靠它）。所以 search 的模板比 executeWithContext 多两个插槽。

LdapOperations 里 search 有 **20+ 个重载**（`core/LdapOperations.java`，接口本身 1746 行），但**全部殊途同归**到这一个方法：

【源码证据】`core/LdapTemplate.java:368-448`（`search(SearchExecutor, NameClassPairCallbackHandler, DirContextProcessor)`）：

```java
public void search(SearchExecutor se, NameClassPairCallbackHandler handler, DirContextProcessor processor) {
	DirContext ctx = this.contextSource.getReadOnlyContext();      // ① 取读连接

	NamingEnumeration<?> results = null;
	RuntimeException exception = null;
	try {
		processor.preProcess(ctx);                                 // ② 前钩子（分页：挂 Controls）
		results = se.executeSearch(ctx);                           // ③ 执行真正的搜索

		while (results.hasMore()) {                                // ④ 流式逐条消费
			NameClassPair result = (NameClassPair) results.next();
			handler.handleNameClassPair(result);                   //    逐条喂给回调
		}
	}
	catch (NameNotFoundException ex) {
		if (this.ignoreNameNotFoundException) { ... }              // ⑤ 三个可忽略开关
		else { exception = LdapUtils.convertLdapException(ex); }
	}
	catch (PartialResultException ex) {
		if (this.ignorePartialResultException) { ... }             //    AD referral 专用
		else { exception = LdapUtils.convertLdapException(ex); }
	}
	catch (SizeLimitExceededException ex) {
		if (this.ignoreSizeLimitExceededException) { ... }
		else { exception = LdapUtils.convertLdapException(ex); }
	}
	catch (javax.naming.NamingException ex) {
		exception = LdapUtils.convertLdapException(ex);
	}
	finally {
		try {
			processor.postProcess(ctx);                            // ⑥ 后钩子（分页：取回 cookie）
		}
		catch (javax.naming.NamingException ex) { ... }            //    主异常优先，后钩子异常只记日志
		closeContextAndNamingEnumeration(ctx, results);            // ⑦ 连接+枚举双关闭（:1138）
		if (exception != null) {
			throw exception;                                       // ⑧ finally 里补抛主异常
		}
	}
}
```

三个插槽的接口定义（都在 `org.springframework.ldap.core`）：

| 插槽 | 接口 | 行号 | 职责 |
|---|---|---|---|
| "搜什么" | `SearchExecutor` | SearchExecutor.java:39 | `NamingEnumeration<?> executeSearch(DirContext ctx)`——把 ctx.search 的各种参数组合封装掉 |
| "每条结果怎么处理" | `NameClassPairCallbackHandler` | NameClassPairCallbackHandler.java:31 | 逐条回调；两个现成收集器：`AttributesMapperCallbackHandler`（:34，喂 `AttributesMapper`）与 `ContextMapperCallbackHandler`（:34，喂 `ContextMapper`） |
| "前后钩子" | `DirContextProcessor` | DirContextProcessor.java:30 | `preProcess/postProcess`——分页、排序控件的挂载点（8.1 节） |

两个 Mapper 是"结果变对象"的用户侧接口：

```java
@FunctionalInterface
public interface AttributesMapper<T> {          // AttributesMapper.java:40
	T mapFromAttributes(Attributes attributes) throws NamingException;
}

@FunctionalInterface
public interface ContextMapper<T> {             // ContextMapper.java:50
	T mapWithContext(Object ctx) throws NamingException;
}
```

`ContextMapper` 拿到的 `ctx` 实际上就是第六章的 `DirContextAdapter`（见 6.3 节的对象工厂钩子），因此日常代码通常长这样：

```java
List<String> uids = ldapTemplate.search(
		"ou=people", "(objectClass=person)",
		(NameClassPair name, ...) -> ...);   // 或 ContextMapper
List<Person> people = ldapTemplate.search(
		"ou=people", "(objectClass=person)",
		(ContextMapper<Person>) ctx -> new Person(
				ctx.getStringAttribute("cn"),
				ctx.getStringAttribute("mail")));
```

**为什么枚举也要关闭？** `closeContextAndNamingEnumeration`（LdapTemplate.java:1138-1147）先关 `NamingEnumeration` 再关 `DirContext`——JNDI 的实现在枚举未消费完时关闭连接，Socket 可能挂起在等待状态，顺序有讲究。

## 4.4 三个 ignore 开关与 Active Directory 的 PartialResultException

LdapTemplate.java:170-236 有三个 setter：

- `setIgnoreNameNotFoundException`（:170，@since 1.3）——base DN 不存在时当作"查无结果"；
- `setIgnorePartialResultException`（:190，@since 1.3）——**AD 专用**。类 javadoc（LdapTemplate.java:67-74）原文直言："AD servers are apparently unable to handle referrals automatically, which causes a PartialResultException to be thrown whenever a referral is encountered in a search"——开了这个开关，框架在 search 主干里直接把该异常吞掉记 debug 日志（见 4.3 的 ⑤）。代价是：referral 指向的其它服务器数据真的拿不到——javadoc 也明说"either you get the exception (and your results are lost) or all referrals are ignored"。
- `setIgnoreSizeLimitExceededException`（:206）——服务端返回条数达到 `countLimit` 时不算错误。

另有三个默认值 setter：`setDefaultSearchScope`（:222）、`setDefaultTimeLimit`（:236）、`setDefaultCountLimit`（:250），最终喂给 `getDefaultSearchControls`（:1176-1190）——所有没显式传 `SearchControls` 的重载都用它，且 `assureReturnObjFlagSet`（:1192）强制把 returningObjFlag 置 true：**不返回条目对象本身、只返回属性的话，`ContextMapper` 拿不到 `DirContextAdapter`，DN 也会退化为相对名**。

## 4.5 写操作：lookup/bind/unbind/rename 与递归删除

- `lookup(Name dn)`（:831）家族：最常用的是 `lookupContext(dn)`（:1205-1219）——返回 `DirContextOperations`（即 `DirContextAdapter`），以及 `lookup(dn, ContextMapper)`（:870）。
- `bind(Name dn, Object obj, Attributes attrs)`（:961）：创建条目；`rebind`（:1090）：不存在则建、存在则整体替换。
- `unbind(Name dn)`（:983）/ `unbind(Name dn, boolean recursive)`（:999）：递归版先 `list` 子树再自底向上删——实现在 `deleteRecursively`（:1055-1088）：先把每个子节点的子树删掉，最后删自己（后序遍历）。
- `rename(oldDn, newDn)`（:1112）：LDAP 的"移动/改名"，也是补偿事务的基石（8.2 节）。

`modifyAttributes(DirContextOperations ctx)`（:1221）值得单独一提：它接收一个**处于 updateMode 的 DirContextAdapter**，从里面取 `getModificationItems()`（差量修改集，6.2 节）提交给 JNDI——这就是"改对象 → 提交差量"链路的最后一跳。

## 4.6 authenticate：LDAP 认证的两步走

**白话**：LDAP 校验密码没有"verifyPassword"这种 API——**密码对不对，只有"用这个 DN + 这个密码成功 BIND 一次"才能证明**。所以认证流程天然是两步：① 用主账号搜索出用户条目的 DN；② 用"该 DN + 用户输入的密码"调 `ContextSource.getContext(principal, credentials)` 开一条新连接，成功了即认证通过。

【源码证据】`core/LdapTemplate.java:1336-1370`（私有主干）：

```java
private AuthenticationStatus authenticate(Name base, String filter, String password,
		SearchControls searchControls, AuthenticatedLdapEntryContextCallback callback,
		AuthenticationErrorCallback errorCallback) {

	List<LdapEntryIdentification> result = search(base, filter, searchControls,
			new LdapEntryIdentificationContextMapper());          // ① 搜条目
	if (result.isEmpty()) {
		return AuthenticationStatus.EMPTYRESULT;
	}
	else if (result.size() > 1) {
		throw new IncorrectResultSizeDataAccessException(msg, 1, result.size());  // ② 歧义即错
	}

	final LdapEntryIdentification entryIdentification = result.get(0);

	try {
		DirContext ctx = this.contextSource
				.getContext(entryIdentification.getAbsoluteName().toString(), password);  // ③ 关键一步
		executeWithContext((ctx1) -> {
			callback.executeWithContext(ctx1, entryIdentification);
			return null;
		}, ctx);
		return AuthenticationStatus.SUCCESS;
	}
	catch (Exception ex) {
		errorCallback.execute(ex);
		return AuthenticationStatus.UNDEFINED_FAILURE;
	}
}
```

三个细节：

- **② 的异常来自 spring-dao**：搜出多于一条说明 filter 写宽了，抛 `IncorrectResultSizeDataAccessException`——Spring LDAP 把"数据访问语义"统一到了 Spring 的异常词汇表。
- **③ 正是 3.1 节 `getContext(principal, credentials)` 的存在意义**，且由于该方法显式禁池化（LDAP-183），**认证不会因上下文池而误判旧密码有效**——两处设计互为闭环。
- 错误回调 `AuthenticationErrorCallback` 让调用方能拿到原始异常做诊断（比如区分"密码错"与"服务器不可达"），但默认重载（`authenticate(query, password)`，:1404）只抛 `AuthenticationException`，**不区分**——需要区分失败原因时必须用带 mapper/callback 的重载（:1372-1402）。

## 4.7 LdapClient（3.1+）：流式 Spec API 的另一种打开方式

**白话**：`LdapTemplate` 的 20+ 个 search 重载是"参数爆炸"的老毛病。3.1 引入的 `LdapClient`（`core/LdapClient.java:52`，`@since 3.1`）用"先选动词、再配参数、最后收割"的流式链解决了它——风格与 Spring 6 的 `RestClient`/`JdbcClient` 一脉相承。

接口骨架（LdapClient.java:88-94 等）：

```java
public interface LdapClient {
	ListSpec list(String name);
	ListBindingsSpec listBindings(String name);
	SearchSpec search();                 // :88
	AuthenticateSpec authenticate();     // :94
	BindSpec bind(String name);
	ModifySpec modify(String name);
	...
}
```

【源码证据】`SearchSpec`（LdapClient.java:348）的用法一图流：

```java
LdapClient client = LdapClient.builder().contextSource(contextSource).build();  // DefaultLdapClientBuilder

Optional<DirContextOperations> user = client.search()
		.query(LdapQueryBuilder.query().where("uid").is("jdoe"))
		.map(ContextMapper.class.cast(...))     // 或直接以 DirContextOperations 收割
		.optional();                            // :398 @since 4.1
```

`SearchSpec` 提供 `name/query(Consumer<LdapQueryBuilder>)/query(LdapQuery)` 定搜索参数，`single()/optional()`（@since 4.1，LdapClient.java:388-407）收割单条，`toList/toStream/map(...)` 收割集合。`DefaultLdapClient`（`core/DefaultLdapClient.java`，`@since 3.1`）内部**直接委托同一个 LdapTemplate 实例**——两条 API 表面平行，底层共用同一套模板机制与异常转译。4.2 又给 `LdapClient.Builder` 加了 `dirContextPostProcessor`（modules/ROOT/pages/whats-new.adoc:9-10）。

**选型建议**：存量代码、需要 ODM 方法族的继续用 `LdapTemplate`；新代码想要类型安全的链式调用、又不需要 ODM 的，`LdapClient` 更清爽。

## 4.8 本章小结

LdapTemplate 的本质是**三层收敛**：`executeWithContext` 收敛"取连接/转异常/关连接"；`search(SearchExecutor, Handler, Processor)` 在此之上收敛"枚举关闭 + 前后钩子 + 三个可忽略异常"；20+ 重载只是 `LdapQuery`/`SearchControls`/`Mapper` 的排列组合。authenticate 的两步走（搜 DN → 换密码 BIND）复用了 ContextSource 的第三方法，并与禁池化设计形成安全闭环。

---

# 五、异常转译与过滤器、查询构建

## 5.1 NamingException 层次：javax.naming 的镜像 + 序列化修补

**白话**：Spring LDAP 在 `org.springframework.ldap` 包下定义了 30+ 个异常类，**名字几乎逐个对应** `javax.naming` 的异常（`NameNotFoundException`→`NameNotFoundException`、`CommunicationException`→`CommunicationException`……），全部继承自非受检的 `org.springframework.ldap.NamingException`。层次不变、名字不变，变的只有"受检→非受检"这一个性质。

【源码证据】`core/src/main/java/org/springframework/ldap/NamingException.java:36-56`：

```java
public abstract class NamingException extends NestedRuntimeException {

	private final @Nullable Throwable cause;

	@Override
	public @Nullable Throwable getCause() {
		// Even if you cannot set the cause of this exception other than through
		// the constructor, we check for the cause being "this" here, ...
		return (this.cause != this) ? this.cause : null;
	}
```

类注释解释了为什么要自存 cause 并重写 getCause（NamingException.java:41-47 的注释）：JNDI 异常的 `resolvedObj` 里可能装着**不可序列化**的对象，而序列化会先序列化父类——把 cause 拷到自己手里，序列化前临时置空，才能保证异常本身可序列化（Remote 场景/日志系统需要）。这是"镜像层次"之外少有人注意的一处工程细节。

## 5.2 LdapUtils.convertLdapException：先子类后父类的翻译表

**白话**：转译表就是一长串 `isAssignableFrom` 判断，把 javax 异常逐个 `new` 成 Spring 异常。**顺序是正确性的关键**：必须先判子类（如 `SizeLimitExceededException`）再判父类（`LimitExceededException`），否则父类分支会拦截一切。

【源码证据】`core/src/main/java/org/springframework/ldap/support/LdapUtils.java:93` 起（方法体约 140 行）：

```java
public static NamingException convertLdapException(javax.naming.NamingException ex) {
	Assert.notNull(ex, "NamingException must not be null");

	if (javax.naming.directory.AttributeInUseException.class.isAssignableFrom(ex.getClass())) {
		return new org.springframework.ldap.AttributeInUseException(...);   // :98
	}
	... // AttributeModification / CannotProceed / Communication / Configuration / ContextNotEmpty ...
	// LimitExceededException hierarchy —— 注释明说先子后父
	if (javax.naming.SizeLimitExceededException.class.isAssignableFrom(ex.getClass())) {
		return new org.springframework.ldap.SizeLimitExceededException(...);   // 子类在前
	}
	if (javax.naming.TimeLimitExceededException.class.isAssignableFrom(ex.getClass())) { ... }
	// this class is the superclass of the two above
	if (javax.naming.LimitExceededException.class.isAssignableFrom(ex.getClass())) {
		return new org.springframework.ldap.LimitExceededException(...);       // 父类在后
	}
	...
	// fallback
	return new org.springframework.ldap.UncategorizedLdapException(ex);        // :231 兜底
}
```

表的覆盖度可由"javax.naming 包异常数 ≈ org.springframework.ldap 包异常数"验证（本章开头列的 30+ 个文件）；无法识别的异常统一进 `UncategorizedLdapException`（:231）——这与 Spring JDBC 的 `UncategorizedSQLException` 是同一设计语法。

## 5.3 filter 包：过滤器对象树与 encode()

**白话**：手拼 filter 字符串 `"(uid=" + username + ")"` 又丑又危险。filter 包把过滤器做成**可组合对象树**：`Filter` 接口只有一个核心方法 `StringBuffer encode(StringBuffer buf)`（`filter/Filter.java`），叶子节点是比较（`EqualsFilter`/`LikeFilter`/`PresentFilter`/`GreaterThanOrEqualsFilter`...），组合节点是逻辑（`AndFilter`/`OrFilter`/`NotFilter`，共同父类 `BinaryLogicalFilter`）。

【源码证据】典型组合：

```java
Filter f = new AndFilter()
		.and(new EqualsFilter("objectClass", "person"))   // 里层不自动编码？——见 5.5 的 3.3 变化
		.and("uid", username);                            // EqualsFilter extends CompareFilter
String filter = f.encode();   // → "(&(objectClass=person)(uid=jdoe))"
```

`AbstractFilter.encode()`（`filter/AbstractFilter.java:30-36`）是递归下降的编码入口：组合节点先输出括号与操作符，再递归 encode 子节点。`toString()` 直接等于 `encode()`（AbstractFilter.java:38-41），日志友好。

## 5.4 LdapQueryBuilder：过滤器的 Builder 外壳

**白话**：`LdapQueryBuilder`（`query/LdapQueryBuilder.java:64`，`@since 2.0`）在 Filter 对象树之上再包一层流式 DSL，把 base/scope/limit/attributes 与 filter 条件**一起**描述成一个不可变的 `LdapQuery`——它是 4.6 节 `authenticate(LdapQuery, ...)` 与 ODM `find(LdapQuery, clazz)` 的参数类型。

【源码证据】API 面（LdapQueryBuilder.java:91-284）：

```java
LdapQuery query = LdapQueryBuilder.query()          // :91 静态工厂（构造器私有）
	.base("ou=people")                              // :128/:140
	.searchScope(SearchScope.SUBTREE)               // :151
	.timeLimit(2000)                                // :179
	.countLimit(100)                                // :162
	.where("objectClass").is("person")              // :192 → ConditionCriteria 链
	.and("sn").like("Doe*");                        // DefaultConditionCriteria 内部组装 EqualsFilter/LikeFilter
```

`where()` 返回 `ConditionCriteria`（query/DefaultConditionCriteria.java），每个 `is/like/gte/lte` 直接 new 对应 Filter。注意 `query()` 返回的 builder **实现了 `LdapQuery` 本身**（类声明 `public final class LdapQueryBuilder implements LdapQuery`），所以构建完直接当参数传；`fromQuery(query)`（:105，`@since 3.0`）可复制改造既有查询。

## 5.5 注入防护：LdapEncoder.filterEncode 与 3.3 的 API 变化

**白话**：filter 里 `* ( ) \` 和 NUL 五类字符是语法字符，用户输入里出现任意一个都能改变过滤器的含义（例如 `*` 会变成通配符）。`LdapEncoder.filterEncode`（`support/LdapEncoder.java:102-122`）用一张查表（`FILTER_ESCAPE_TABLE`）把它们转成 `\XX` 十六进制转义；DN 场景则由同类 `nameEncode` 处理空格/`#`/逗号等另一套转义规则。

**3.3 的破坏性收紧**（这是本系列文档特有的"网上资料不会告诉你的演进"）：`CompareFilter` 旧构造器**在框架内自动编码** value；3.3 起被标注废弃——

【源码证据】`filter/CompareFilter.java:36-40` 与 :64-67：

```java
@Deprecated(since = "3.3")
public CompareFilter(String attribute, String value) {
	...
	this.encodedValue = encodeValue(value);   // 旧路径：构造时自动 filterEncode
}

@Deprecated(forRemoval = true, since = "3.3")
protected String encodeValue(String value) {
	return LdapEncoder.filterEncode(value);
}
```

新姿势是调用方**显式编码后**传入（`new EqualsFilter("uid", LdapEncoder.filterEncode(username))`），框架不再"猜"你给的值是裸的还是已编码的——宁可啰嗦，不可二义。这是个 API 安全治理的好案例：**自动转义看似安全，实际上制造了"双重转义/忘记转义都无法从类型上区分"的灰色地带**。

## 5.6 本章小结

异常转译 = 一张"先子后父"的镜像表 + `UncategorizedLdapException` 兜底 + 自存 cause 保障序列化。查询构建 = Filter 对象树（递归 encode）+ LdapQueryBuilder（DSL 外壳）+ LdapEncoder（转义地基）；3.3 把"自动转义"改成"显式转义"是理解 filter API 现状的钥匙。

---

# 六、DirContextAdapter 与对象工厂：搜索结果怎么变成对象

## 6.1 DirContextAdapter：把 Attributes 变成可读写对象

**白话**：JNDI 的搜索结果是一坨 `Attributes`（键值集合），读个字符串属性要 `attrs.get("cn").get().toString()` 三层拆包。`DirContextAdapter`（`core/DirContextAdapter.java:112`，1309 行）是这份集合的**门面包装**：`getStringAttribute("cn")`、`getStringAttributes("objectClass")`、`setAttributeValues("mail", [...])` 一行到位；它自己实现了 `DirContext` 接口却**不连任何服务器**——就是个带 DN 的本地数据袋。

接口契约是 `DirContextOperations`（`core/DirContextOperations.java:30`）：

```java
public interface DirContextOperations extends DirContext, LdapDataEntry, AttributeModificationsAware { }
```

三重身份：对 JNDI 是 `DirContext`（因此能当 search 的 returningObjFlag 产物）、对映射是 `LdapDataEntry`（读写属性）、对修改是 `AttributeModificationsAware`（能产出差量）。

## 6.2 updateMode 与 ModificationItem 的差量计算

**白话**：LDAP 修改条目的 API 是 `modifyAttributes(dn, ModificationItem[])`——一个"变更清单"（ADD/REPLACE/REMOVE 各属性）。手算"新状态 vs 旧状态"的差量太痛苦。Spring LDAP 的方案：`DirContextAdapter` 有个 **updateMode 开关**——开启后它同时保存"原始属性"和"你正在改的新属性"，`getModificationItems()` 随时能吐出差量清单。

【源码证据】状态机（DirContextAdapter.java）：

```java
private boolean updateMode = false;                      // :130

public void setUpdateMode(boolean mode) {                // :232
	this.updateMode = mode;
	if (this.updateMode) {
		this.updatedAttrs = new NameAwareAttributes();   // :234-237 开启时清空"新值袋"
	}
}

public ModificationItem[] getModificationItems() {       // :286
	if (!this.updateMode) {
		return new ModificationItem[0];                  // 非更新模式永远空
	}
	...
}
```

开启 updateMode 后，所有 `setAttributeValue(s)` 调用不再直接写 `originalAttrs`，而是写进 `updatedAttrs`（写路径分支在 :484-495：`if (!this.updateMode && value != null) {...} else if (this.updateMode) {...}`）。差量判定在 `isChanged(name, values, orderMatters)`（:352-397）与 `isAttributeUpdated`（:398-…）——逐值比较，多值属性要比较"值集合是否相同（顺序是否敏感）"，空属性被视为 REMOVE（`isEmptyAttribute` :329）。

**4.2 的新开关** `setMayRemoveByValue`（DirContextAdapter.java:248，`@since 4.2`）：默认规则是"有序属性（ordered）不允许按值删除，必须整属性替换"（javadoc 原文：remove-by-value 对没有相等匹配能力的属性不可行）——4.2 允许用 `BiPredicate` 按属性自定义该规则，`DefaultModificationItemsCollector`（`core/DefaultModificationItemsCollector.java`）执行判定。

**闭环**：`LdapTemplate.bind(ctx)`（:1235-1247）对 updateMode 的 adapter 自动改走 `modifyAttributes`，`rebind(ctx)`（:1249）则整体替换；而 4.5 节的 `modifyAttributes(DirContextOperations)`（:1221）就是取 `getModificationItems()` 提交的最后一跳。

## 6.3 DefaultDirObjectFactory：JNDI SPI 的钩子（本章核心）

**白话**：前两章说"ContextMapper 拿到的 ctx 就是 DirContextAdapter"——谁把 JNDI 返回的原始对象换成 Adapter 的？答案是 **JNDI 自己**。JNDI 的 SPI 里有 `DirObjectFactory` 钩子：`DirContext.search()` 返回的每个 `SearchResult`，若环境表里注册了 `OBJECT_FACTORIES`，JNDI 在 `getObjectInstance()` 回调里允许第三方"换货"。Spring LDAP 注册的就是 `DefaultDirObjectFactory`。

【源码证据】`core/support/DefaultDirObjectFactory.java:43-167`：

```java
public class DefaultDirObjectFactory implements DirObjectFactory {

	@Override
	public final Object getObjectInstance(Object obj, Name name, Context nameCtx,
			Hashtable<?, ?> environment, Attributes attrs) throws Exception {

		try {
			String nameInNamespace;
			if (nameCtx != null) {
				nameInNamespace = nameCtx.getNameInNamespace();   // 取"带 base 的全 DN"
			}
			else {
				nameInNamespace = "";
			}
			return constructAdapterFromName(attrs, name, nameInNamespace);   // :73
		}
		finally {
			// It seems that the object supplied to the obj parameter is a
			// DirContext instance with reference to the same Ldap connection ... :
			// this one really needs to be closed in order to correctly clean up
			// and return the connection to the pool ...                     // :76-80
			if (obj instanceof Context) {
				((Context) obj).close();
			}
		}
	}
```

三件事，件件都是血泪经验：

1. **修 DN**：`constructAdapterFromName`（:109 起）开头一段注释原话——"CompositeName.toString() completely screws up the formatting in some cases, particularly when backslashes are involved"（:113-116）。JNDI 传进来的 `name` 是 `CompositeName`，直接 toString 会破坏 DN 转义，所以要经 `LdapUtils.convertCompositeNameToString` 特殊处理；名字还可能带 `ldap://host/` 前缀（referral 场景），方法里解析 URI 把协议+主机剥掉（:127-151），最终 `new DirContextAdapter(attrs, LdapName, LdapName(base), referralUrl)` 且**出厂即 `setUpdateMode(true)`**（:155-157）——查出来的 adapter 直接可改可提交。
2. **关多余连接**：finally 里关闭 JNDI 传入的 `obj`（:76-88 注释解释：它引用着与原 context 相同的连接，不关池就回不去）——否则开了池的场景每次 search 都漏一条连接。
3. **带出 base DN**：`nameInNamespace` 让 Adapter 知道"绝对 DN"与"相对 DN"两个视图（`DirContextAdapter.getDn()/getAbsoluteName()` 的区别由此而来）。

注册点在 `AbstractContextSource.afterPropertiesSet()`：环境表里放 `Context.OBJECT_FACTORIES = DefaultDirObjectFactory.class`——这就是 `DirContextMapperCallbackHandler` 能直接把 `(DirContextOperations) nameClassPair.getObject()` 强转成功的原因（LdapTemplate 的 `OPERATIONS` 常量 :86 正是这么干的）。

## 6.4 DN 处理的兴衰：DistinguishedName → LdapName

- **1.x**：核心类型是自研的 `DistinguishedName`（`core/DistinguishedName.java:103`，实现了 `Name` 接口），配套 JavaCC 语法文件 `core/src/main/javacc/DnParserImpl.jj` 生成的解析器（core/build.gradle:6-13 的 `javacc` 配置至今保留）与 `LdapRdn`/`LdapRdnComponent`（:298/:273）。
- **2.0**：全 API 迁到 JDK 自带的 `javax.naming.ldap.LdapName`（`DistinguishedName.java:101 @Deprecated`），工具类 `LdapUtils.newLdapName/unmodifiableLdapName/emptyLdapName`（`support/LdapUtils.java`）成为新的入口。`DistinguishedName` 保留至今只是为了 1.x 二进制兼容——新代码禁用。
- **如今**：DN 相关剩余工具集中在 `LdapUtils`（DN 与字符串互转 `newLdapName` :353/:389、`emptyLdapName` :472、`convertCompositeNameToString` :329、`prepend` :452、`removeFirst` :419 等），加上 `NameAwareAttribute`/`NameAwareAttributes`（`core/NameAwareAttribute.java`）——在普通 `Attribute` 之上记录"被 set 时用的原始字符串形式"，保证写回时转义不丢真（`DirContextAdapter` 与 ODM 都依赖它）。

## 6.5 本章小结

`DirContextAdapter` 是"条目的 Java 门面"：读属性一行调用，updateMode 开启后自动积累差量（`getModificationItems`），4.2 起连"按值删除"的规则都可定制。而把它**无缝塞进 JNDI 返回值里**的是 `DefaultDirObjectFactory`——一个注册进 JNDI 环境表的 SPI 钩子，顺手修了 CompositeName 的转义坑、关了多余连接、带出了 base DN。DN 世界的王朝更替（DistinguishedName → LdapName）则在 2.0 已完成。

---

# 七、ODM：对象-目录映射（Object-Directory Mapping）

## 7.1 五个注解：@Entry/@Id/@Attribute/@DnAttribute/@Transient

**白话**：ODM 让你像用 JPA 一样定义 LDAP 条目的 Java 类——但记住 1.2 节的话：**ODM 不是 ORM**。没有关联映射、没有缓存、没有懒加载，DN 就是主键，搜索就是查询。

【源码证据】五个注解全部在 `core/src/main/java/org/springframework/ldap/odm/annotations/`：

| 注解 | 位置 | 要点 |
|---|---|---|
| `@Entry` | Entry.java:33 | 类级；`objectClasses()`（必填，对应条目的 objectClass）+ `base()`（可选，该类条目的公共 base DN） |
| `@Id` | Id.java:37 | 字段级；**有且必须只有一个**，类型须为 `javax.naming.Name`（一般 `LdapName`）——DN 即主键 |
| `@Attribute` | Attribute.java:34 | 字段级；`name()`（LDAP 属性名，缺省取字段名）、`type()`（`STRING`/`BINARY`，决定喂给 JNDI 的是 String 还是 byte[]） |
| `@DnAttribute` | DnAttribute.java:41 | 字段级（String）；把 DN 的某一段 RDN 映射到字段，改它等于**移动条目**（DN 变了） |
| `@Transient` | Transient.java:33 | 字段级；不参与映射 |

一个典型映射类：

```java
@Entry(objectClasses = {"inetOrgPerson", "organizationalPerson"}, base = "ou=people")
public class Person {

	@Id
	private Name dn;

	@DnAttribute("uid")
	private String uid;

	@Attribute(name = "cn")
	private String commonName;

	@Attribute(type = Attribute.Type.BINARY)
	private byte[] jpegPhoto;

	@Transient
	private String notPersisted;
}
```

## 7.2 DefaultObjectDirectoryMapper：元数据缓存与双向映射

**白话**：`ObjectDirectoryMapper` 接口（`core/odm/core/ObjectDirectoryMapper.java:36`）定义"注解类 ↔ LDAP 条目"的翻译契约；唯一实现 `DefaultObjectDirectoryMapper`（`core/odm/core/impl/DefaultObjectDirectoryMapper.java:67`，565 行）在第一次见到某 Class 时解析全部注解构建 `ObjectMetaData`（字段 → `AttributeMetaData`），**缓存在 `ConcurrentHashMap`**（`getMetaDataMap()` :560 附近的 `metaDataMap` 字段）——每次映射零反射扫描成本。

**写方向 `mapToLdapDataEntry`**（:211-260）四步：

1. 新条目先写 `objectclass` 属性（:214-229，`OBJECT_CLASS_ATTRIBUTE = "objectclass"` :74——注意全小写，属性名大小写不敏感的 canonical 形式）；
2. 遍历元数据字段，跳过 transient/id/objectclass/readonly（:238-240）；
3. 按 `@Attribute(type)` 决定 JNDI 目标类型（`getJndiClass()`——String 属性喂 String、BINARY 喂 byte[]，:241-243 注释原话："If this is a 'binary' object the JNDI expects a byte[] otherwise a String"）;
4. 单值走 `populateSingleValueAttribute`，集合走 `populateMultiValueAttribute`（:248-251）。

**读方向 `mapFromLdapDataEntry`**（:302-360）先做**身份校验**：把条目的 `objectclass` 属性值与元数据声明的 objectClasses 逐一比对（:327-347），**不匹配返回 null**（而不是抛异常）——上层 `find` 据此过滤掉"命中了 filter 但不是这个类型"的条目（LDAP filter 的 objectClass 条件是"或"语义宽松匹配，见 `filterFor` :513-521：`ocFilter` 是 `(|(objectClass=A)(objectClass=B))`，与用户 filter 用 `AndFilter` 相连）。

`getCalculatedId(entry)`（:488-510）演示了 `@DnAttribute` 的威力：从 `@Entry.base()` 出发，把每个 `@DnAttribute` 字段值拼成 RDN——**改了 uid 字段，算出的 DN 就变了**。这正是 7.3 节 `update` 能自动"移动条目"的依据。

## 7.3 LdapTemplate 里的 ODM 方法：create/update/delete/find

**白话**：2.0 起 ODM 不再是独立门面（第一代 `OdmManager` 在 odm 模块里已 @Deprecated），而是直接长在 `LdapTemplate` 上：`create/update/delete/find/findByDn/findAll/findOne/findForStream`。它们的实现短得出奇——因为全部拼装工作都委托给了 odm 映射器与 4.5 节的写原语。

【源码证据】`core/LdapTemplate.java:1601-1679`：

```java
public void create(Object entry) {
	Name id = this.odm.getId(entry);                    // ① 显式 @Id
	if (id == null) {
		id = this.odm.getCalculatedId(entry);           // ② 否则由 base+@DnAttribute 拼出
		this.odm.setId(entry, id);
	}
	DirContextAdapter context = new DirContextAdapter(id);
	this.odm.mapToLdapDataEntry(entry, context);        // ③ 对象 → 属性袋
	bind(context);                                      // ④ 复用模板的 bind
}

public void update(Object entry) {
	Name originalId = this.odm.getId(entry);
	Name calculatedId = this.odm.getCalculatedId(entry);

	if (originalId != null && calculatedId != null && !originalId.equals(calculatedId)) {
		// The DN has changed - remove the original entry and bind the new one
		unbind(originalId);                             // ⑤ DN 变了 = 移动：删旧 + 建新
		DirContextAdapter context = new DirContextAdapter(calculatedId);
		this.odm.mapToLdapDataEntry(entry, context);
		bind(context);
		this.odm.setId(entry, calculatedId);
	}
	else {
		Name id = originalId != null ? originalId : calculatedId;
		String[] attributes = this.odm.manageClass(entry.getClass());  // ⑥ 只查受管属性
		DirContextAdapter context = lookup(id, attributes, cast());    // ⑦ 查旧值
		context.setUpdateMode(true);                                   // ⑧ 开差量
		this.odm.mapToLdapDataEntry(entry, context);
		modifyAttributes(context);                                     // ⑨ 提交 ModificationItem[]
	}
}
```

`update` 是全章最精妙的 20 行：**DN 变化走"删旧建新"（LDAP rename 语义由用户显式调用 rename 才走 rename），DN 不变走"查旧值 → 开 updateMode → 覆盖新值 → 提交差量"**——第六章的差量机与第七章的元数据机在这里咬合。`delete`（:1679-1693）则只是 `unbind(odm.getId(entry))`。

查询族：`find(base, filter, searchControls, clazz)`（:1715）把 `filterFor(clazz, baseFilter)`（ODM 加 objectClass 过滤）与 `nonNullBase`（:1745，缺省取 `@Entry.base()`）交给普通 search，再用 ODM 双向映射收尾；`findByDn`（:1576）是 `lookup + mapFromLdapDataEntry`；`findForStream`（:1785，配合 `unchecked` :1797 的受检异常搬）是 3.x 的 Stream 化。

## 7.4 类型转换：ConverterManager 与 ConversionService

**白话**：LDAP 属性永远是 String/byte[]，Java 字段却有 int/LocalDate/枚举……ODM 的类型转换由 `ConverterManager`（`core/odm/typeconversion/ConverterManager.java`）承担：`ConverterManagerImpl` 维护"源类型 → 目标类型 → Converter"注册表；2.0 起还提供 `ConversionServiceConverterManager`（`impl/ConversionServiceConverterManager.java`，`@since 2.0`）**把 Spring 的 `ConversionService` 接进来**——现版本该类已 @Deprecated，官方建议直接用 `ConversionService` 配合 `ConverterUtils`。`DefaultObjectDirectoryMapper` 两个注入口（:93 setConverterManager / :103 setConversionService）证明了双轨并存。

## 7.5 OdmManager（odm 模块）与 Spring Data LDAP 仓库

- `spring-ldap-odm` 模块（10 个文件）是 1.3.1 引入的第一代门面 `OdmManager`/`OdmManagerImpl`，2.0 起整体 @Deprecated，仅存续兼容。
- **Spring Data LDAP**（独立项目 spring-data-ldap）在 ODM 注解之上提供 `LdapRepository`、`@EnableLdapRepositories`、QueryDSL 支持（`QueryDslPredicateExecutor`，官方文档 repositories.adoc:25 实证）；core 的 querydsl optional 依赖就是给它用的。Boot 侧由 `spring-boot-data-ldap` 模块自动开启（第九章）。
- 关系一句话：**Spring LDAP 的 ODM 提供映射引擎，Spring Data LDAP 提供仓库编程模型**——类似 spring-orm（引擎）之于 Spring Data JPA（仓库）。

## 7.6 本章小结

ODM 的全部成本集中在"第一次 `manageClass`"（注解 → `ObjectMetaData` → ConcurrentMap 缓存）；映射本身是朴素的字段循环。`update` 的 DN 分叉（删旧建新 vs 差量提交）是理解 ODM 行为的关键；objectClass 身份校验让 `find` 能多态过滤；类型转换双轨兼容 Spring `ConversionService`；仓库模型则外派给 Spring Data LDAP。

---

# 八、进阶机制：Controls 分页、补偿事务、LDIF、测试与可观测

## 8.1 DirContextProcessor 与 LDAP Controls：分页（PagedResults）

**白话**：LDAP 协议的"扩展机制"叫 **Controls**（RFC 2696 分页、排序等）：请求时给服务器挂一个 Control，响应里服务器回一个 Control。JNDI 把这做成 `LdapContext.setRequestControls/getResponseControls`——而 4.3 节 search 模板的 `DirContextProcessor` 前后钩子，恰好是"请求前挂控件、响应后取控件"的完美落点。

【源码证据】分页处理器 `core/control/PagedResultsDirContextProcessor.java:38`：

```java
public class PagedResultsDirContextProcessor
		extends AbstractFallbackRequestAndResponseControlDirContextProcessor {

	private static final String DEFAULT_REQUEST_CONTROL  = "javax.naming.ldap.PagedResultsControl";          // :40
	private static final String FALLBACK_REQUEST_CONTROL = "com.sun.jndi.ldap.ctl.PagedResultsControl";      // :42
	private int pageSize;                        // 每页条数
	private PagedResultsCookie cookie;           // 服务器返回的翻页书签
	private boolean more = true;                 // :54 cookie 为 null 即无更多页

	public Control createRequestControl() {      // :~124
		byte[] actualCookie = (this.cookie != null) ? this.cookie.getCookie() : null;
		return super.createRequestControl(new Class<?>[] { int.class, byte[].class, boolean.class },
				new Object[] { this.pageSize, actualCookie, this.critical });
	}

	protected void handleResponse(Object control) {   // :~145
		byte[] result = (byte[]) invokeMethod("getCookie", this.responseControlClass, control);
		if (result == null) {
			this.more = false;                        // 服务端给空 cookie = 翻完了
		}
		this.cookie = new PagedResultsCookie(result);
		this.resultSize = (Integer) invokeMethod("getResultSize", this.responseControlClass, control);
	}
}
```

父类 `AbstractFallbackRequestAndResponseControlDirContextProcessor`（:90，`loadControlClasses` :109）实现"**双实现回退**"：新 JDK 有 `javax.naming.ldap.PagedResultsControl` 就用它，老 JDK/特殊发行版没有时回退到 `com.sun.jndi.ldap.ctl.*`（由 core 的 provided 依赖 `com.sun:ldapbp`（core/build.gradle:16）提供）——这正是 1.4 节那个奇怪依赖的用处。

**翻页循环的用户侧形态**：

```java
PagedResultsCookie cookie = null;
do {
	PagedResultsDirContextProcessor proc =
			new PagedResultsDirContextProcessor(500, cookie);      // 500 条/页
	List<Person> page = ldapTemplate.search(query, mapper, proc);
	cookie = proc.getCookie();          // 带着书签下一页
} while (proc.hasMore());
```

4.1 起又新增了 `ControlExchange` 家族（`control/ControlExchange.java:39` 等，`@since 4.1`）：把"请求/响应控件"做成**不可变交换对象**（`PagedResultsControlExchange`/`SortControlExchange` + `ControlExchangeDirContextProcessor`），与老 `AbstractRequestControlDirContextProcessor` 的反射调用相比更类型安全。排序同理：`SortControlDirContextProcessor`（RFC 2891）。

> 注意与 8.1 无关但常见的一个坑：分页 cookie 属于**同一条 LDAP 连接的会话状态**——在池化/多连接环境下翻页必须用 `SingleContextSource`（3.4 节）固定连接，或确保 processor 在同一事务内使用。

## 8.2 补偿事务：ContextSourceTransactionManager 与"先改名"哲学

**白话**：LDAP 协议没有 BEGIN/COMMIT——服务器不会替你回滚。Spring LDAP 的补偿事务思路：**每个写操作执行前，先把"被改对象"的原始状态备份；回滚时用备份反向操作**。接口四件套在 `org.springframework.transaction.compensating`（`core/src/main/java/org/springframework/transaction/compensating/`）：

- `CompensatingTransactionOperationRecorder`：**记录**阶段——把"用户想做的操作"翻译成"先备份、再返回执行器"；
- `CompensatingTransactionOperationExecutor`：`performOperation()`（真正干活）/ `commit()`（提交=清理临时备份）/ `rollback()`（用备份复原）。

最烧脑也最能说明设计的是 **rebind**（整体替换条目）：原条目的旧属性可能根本取不全（LDAP 不保证返回所有属性），没法"先读后写"。于是【源码证据】`core/transaction/compensating/RebindOperationExecutor.java`：

```java
// javadoc（:30-36）：performs a <b>rename</b> in {@link #performOperation()},
// a negating rename in {@link #rollback()}, and the {@link #commit()} operation
// unbinds the original entry from its temporary location and binds a new entry ...

public void rollback() {                                   // :85
	this.ldapOperations.unbind(this.originalDn);           // 删掉新写的
	this.ldapOperations.rename(this.temporaryDn, this.originalDn);   // 原条目从临时处搬回来
}

public void commit() {                                     // :102
	this.ldapOperations.unbind(this.temporaryDn);          // 提交：清掉临时备份
}

public void performOperation() {                           // :111
	this.ldapOperations.rename(this.originalDn, this.temporaryDn);   // 先把原条目挪到临时 DN！
	this.ldapOperations.bind(this.originalDn, this.originalObject, this.originalAttributes);
}
```

**"备份 = rename 到临时 DN"**——不依赖读取属性，天然完整。临时 DN 由 `TempEntryRenamingStrategy` 生成：`DefaultTempEntryRenamingStrategy` 在最左 RDN 加后缀（`transaction/compensating/support/DefaultTempEntryRenamingStrategy.java:54 DEFAULT_TEMP_SUFFIX = "_temp"`，例：`cn=john doe` → `cn=john doe_temp`）；`DifferentSubtreeTempEntryRenamingStrategy` 把备份挪到另一棵子树。

事务管理器本体 `ContextSourceTransactionManager`（`transaction/compensating/manager/ContextSourceTransactionManager.java:102`）继承 `AbstractPlatformTransactionManager`，`doBegin/doCommit/doRollback`（:133/:150/:166）全部委托给 `ContextSourceTransactionManagerDelegate`（:42）——后者在 `doBegin` 时把**一个读写 DirContext 绑到 ThreadLocal**（`DirContextHolder`），让事务内所有操作复用同一条连接。配套的 `TransactionAwareContextSourceProxy`（:39）包装 ContextSource，其动态代理 `TransactionAwareDirContextInvocationHandler`（:43，`invoke` :66-91）把事务内的 `getReadOnlyContext()` 也偷换成事务的读写连接，并拦截 `getTargetContext()` 方法（:69）防止穿透。配置入口是 XML 命名空间的 `<ldap:transaction-manager>`（`config/TransactionManagerParser.java`）。

**诚实的历史注**：这套机制只在"**经 LdapTemplate 发起的操作**"范围内有效（Recorder 只注册了 bind/rebind/modify/rename/unbind 五种原语，`LdapCompensatingTransactionOperationFactory` 实证），不是 XA 级别的分布式事务；官方文档 transaction-support.adoc 也直言其局限。但对"LDAP 同步类应用"（HR 系统改了，要写进目录）够用且优雅。

## 8.3 ldif 模块：LdifParser

**LDIF**（LDAP Data Interchange Format，RFC 2849）是 LDAP 的"数据交换文本格式"——初始化数据、备份迁移都用它。`spring-ldap-ldif-core` 的 `LdifParser`（`ldif/ldif-core/src/main/java/org/springframework/ldap/ldif/parser/LdifParser.java:98`）逐行解析 LDIF：`SeparatorPolicy` 负责记录边界、`LineIdentifier` 识别行类型（DN/属性/注释/续行 `base64::`/URL）、`AttributeValidationPolicy` 控制校验强度，解析结果装入 `LdapAttributes`（`core/LdapAttributes.java:183 行`，带 objectClass 归属的属性集合），再由 `schema/BasicSchemaSpecification` 等做 schema 合规校验。典型用途：启动时把 LDIF 导入内嵌服务器（8.4 的 `LdapTestUtils`）。

## 8.4 test-support：内嵌 LDAP 服务器（UnboundID）

**白话**：目录类代码的单测不该依赖真实服务器。`spring-ldap-test` 模块内嵌 **UnboundID LDAP SDK** 的 MemoryBackedServer：`EmbeddedLdapServer`（`test-support/src/main/java/org/springframework/ldap/test/EmbeddedLdapServer.java`）+ `EmbeddedLdapServerFactoryBean`（:30，FactoryBean 形式）一键起一个随机端口的内存 LDAP；`LdapTestUtils` 提供"启动→载入 schema→灌 LDIF→销毁"的编排（内部即 8.3 的 LdifParser + 3.2 的 TestContextSourceFactoryBean:36）。Boot 4 已把"内嵌/容器化 LDAP"升级为官方 Testcontainers/Docker Compose 支持（第九章），但纯 Spring 时代这套仍是经典姿势。

## 8.5 可观测：ObservationContextSource（3.3+）

**白话**：3.3 起，把任意 `BaseLdapPathContextSource` 用 `ObservationContextSource` 包一层，之后**每个 DirContext 方法调用**都会产出一个 Micrometer `Observation`——Timer/MDC/Trace 全自动接入（`core/support/ObservationContextSource.java:67`，`@since 3.3`）。

实现手法是**代理层层包**（同文件）：`getReadOnlyContext()` 等返回的不再是裸 `DirContext`，而是内部类 `ObservationDirContext`（:299，继承 `AbstractDirContextProxy` 动态分发）——每次 `getAttributes/search/...` 调用时 `observation("getAttributes")` 起一个 Observation（:316-328），低基数键 `base/operation/urls`、高基数键 `name/attribute.ids`。观测名常量（:183）：

```java
static final String OBSERVATION_NAME = "spring.ldap.dir.context.operations";
```

官方文档 observability.adoc:34、40 的示例日志可见 contextualName 为 `perform get.attributes`（operation 名以 `.` 分段），且示例中它的 parentObservation 是 `spring.security.authentications`——**LDAP 认证操作自动成为 Security 观测的子观测**，追踪链无需埋点即可贯通。防御性构造（:79-80）：`Assert.isTrue(!(contextSource instanceof ObservationContextSource), "contextSource is already wrapped in an ObservationContextSource")`——拒绝双层包装。Boot 集成见 9.2 节。

## 8.6 AOT 与 GraalVM：LdapCoreRuntimeHints

`core/aot/hint/LdapCoreRuntimeHints.java:39` 实现 `RuntimeHintsRegistrar`，为反射需要（XML 命名空间类、JavaCC 生成的 DN 解析器等）注册 reflection/resource hints，让 Spring LDAP 应用可被 AOT 编译成 Native 镜像。其余模块对 AOT 的主要配合是"弃用反射式解析"的大方向——如 filter 值显式编码（5.5）与 ControlExchange 的类型化控件（8.1）。

## 8.7 本章小结

Controls（分页/排序）落在 DirContextProcessor 的前后钩子上，老接口反射 + 新 ControlExchange 类型化并存；补偿事务以"rename 备份"为核心哲学，配 ThreadLocal 连接与事务感知代理，是"无事务协议上模拟事务"的教科书实现；LDIF 解析与 UnboundID 内嵌服务器支撑测试；ObservationContextSource 用"代理 DirContext"把每个操作变成 Observation；AOT hints 保证 Native 可用。

---

# 九、Spring Boot 集成

## 9.1 Boot 4 的 spring-boot-ldap 模块

Boot 4 把 LDAP 自动配置从单体 `spring-boot-autoconfigure` 拆成独立模块（本地仓库 `D:\code\3rd\spring-boot`，module/ 目录）：`module/spring-boot-ldap`（14 个类）+ `module/spring-boot-data-ldap`（2 个类）+ `spring-boot-data-ldap-test`。

【源码证据】`module/spring-boot-ldap/src/main/java/org/springframework/boot/ldap/` 类清单：

| 类 | 职责 |
|---|---|
| `LdapAutoConfiguration` | 装配 `LdapContextSource`：`spring.ldap.urls/base/userDn/password`（`LdapProperties`）→ AbstractContextSource 属性；存在 `LdapConnectionDetails`（来自 Testcontainers/Docker Compose）时其优先 |
| `EmbeddedLdapAutoConfiguration` | `spring.ldap.embedded.base-dn/ldif` + 内嵌 UnboundID 服务器（`EmbeddedLdapConnectionDetails`），默认 schema 文件 `schema.ldif` |
| `OpenLdapContainerConnectionDetailsFactory` / `LLdapContainerConnectionDetailsFactory`（+ 各自 DockerCompose 版） | Testcontainers `openldap`/`lldap` 镜像 → `LdapConnectionDetails` |
| `LdapSslSocketFactory` | `spring.ldap.urls` 含 `ldaps` 且配置了 SSL bundle 时，注册自定义 SSLSocketFactory 进 JNDI 环境 |
| `LdapHealthContributorAutoConfiguration` + `LdapHealthIndicator` | Actuator 健康检查：执行一次轻量搜索验证连通 |

## 9.2 LdapTemplate 的自动装配与可观测接通

Boot 同时自动装配 `LdapTemplate` Bean（同一个 spring-boot-ldap 模块），并把 8.5 节的观测接上：当 `ObservationRegistry` 存在时，自动配置将 ContextSource 包成 `ObservationContextSource` 再交给 LdapTemplate（与 Spring LDAP 官方 observability.adoc 描述一致）——这就是 observability.adoc 示例里 LDAP 观测自动挂在 Security 观测之下的原因：**框架侧零配置**。

`spring-boot-data-ldap` 的 `DataLdapRepositoriesAutoConfiguration`/`DataLdapRepositoriesRegistrar`（`module/spring-boot-data-ldap/src/main/java/.../`）扫描 `@EnableLdapRepositories` 仓库并注入 `LdapTemplate`——Spring Data LDAP 仓库在 Boot 下开箱即用。

## 9.3 一个最小可用组合

```yaml
spring:
  ldap:
    urls: ldap://localhost:8389
    base: dc=springframework,dc=org
    username: cn=admin,dc=springframework,dc=org   # Boot 属性名，对应 userDn
    password: secret
```

```java
@Service
public class PersonService {

	private final LdapTemplate ldapTemplate;

	public PersonService(LdapTemplate ldapTemplate) {
		this.ldapTemplate = ldapTemplate;
	}

	public Person findByUid(String uid) {
		// 找不到时抛 EmptyResultDataAccessException、命中多条抛 IncorrectResultSizeDataAccessException
		// （LdapTemplate.java:1768-1776）——复用 spring-dao 的"结果数"语义
		return ldapTemplate.findOne(
				LdapQueryBuilder.query().where("uid").is(uid), Person.class);
	}
}
```

（注：真实代码请用 `findOne(LdapQueryBuilder.query().where("uid").is(uid), Person.class)`——`DefaultConditionCriteria.is()` 内部构造 `EqualsFilter`（DefaultConditionCriteria.java:49），后者在构造时自动执行 `LdapEncoder.filterEncode`，安全且不用手拼 DN；框架内部路径至今仍走这条"自动编码"通道，5.5 节的显式编码治理只针对用户直接 new Filter 的场景。）

## 9.4 本章小结

Boot 4 时代，LDAP 接入的全部手工活（ContextSource 属性、内嵌服务器、容器连接、SSL、健康检查、Observation 包装、Data 仓库）都收敛进 `spring-boot-ldap`/`spring-boot-data-ldap` 两个模块；应用代码只剩 `LdapTemplate`（或 `LdapClient`）+ ODM 注解。

---

# 十、贯通视图：三条时间线看懂 Spring LDAP 全貌

## 10.1 时间线一：容器启动（装配期）

```
Boot 启动
 └─ LdapAutoConfiguration
     ├─ new LdapContextSource()
     ├─ LdapProperties → setUrls/setBase/setUserDn/setPassword/setPooled
     ├─ afterPropertiesSet()（AbstractContextSource.java:413）
     │    ├─ 校验 urls/base
     │    ├─ assembleProviderUrlString → baseEnv（PROVIDER_URL 等）
     │    ├─ OBJECT_FACTORIES = DefaultDirObjectFactory.class 注册进环境表   ← 6.3
     │    ├─ setupAnonymousEnv()（:442）→ anonymousEnv 启动期缓存
     │    └─ baseLdapPath 就绪（BaseLdapPathBeanPostProcessor 可注入）
     ├─（有 ObservationRegistry 时）wrap → ObservationContextSource        ← 8.5
     └─ new LdapTemplate(contextSource)
          └─ afterPropertiesSet()（LdapTemplate.java:1135）断言 contextSource 已设置
```

## 10.2 时间线二：一次 search 调用的一生

```
ldapTemplate.search("ou=people", "(objectClass=person)", personMapper)
 └─ 重载链收敛 → search(SearchExecutor, handler, processor)（LdapTemplate.java:368）
     ├─ ① contextSource.getReadOnlyContext()
     │     └─ AbstractContextSource.doGetContext（:145）
     │          ├─ getAuthenticatedEnv → authenticationStrategy.setupEnvironment   ← 3.3
     │          ├─ createContext(env) → InitialDirContext（池化则命中 SUN 池）      ← 3.4
     │          └─ strategy.processContextAfterCreation
     ├─ ② processor.preProcess(ctx)（分页：setRequestControls）                     ← 8.1
     ├─ ③ se.executeSearch(ctx) → NamingEnumeration
     ├─ ④ while hasMore: handler.handleNameClassPair
     │     └─ ContextMapperCallbackHandler.getObjectFromNameClassPair
     │          └─ JNDI 已经过 DefaultDirObjectFactory.getObjectInstance 换货       ← 6.3
     │              → ContextMapper.mapWithContext((DirContextOperations) ctx)
     ├─ ⑤ NamingException? → LdapUtils.convertLdapException（镜像表）               ← 5.2
     ├─ ⑥ finally: processor.postProcess(ctx)（分页：取回 cookie）
     └─ ⑦ closeContextAndNamingEnumeration（:1138）→ 归还/关闭
```

## 10.3 时间线三：一次带事务的 ODM 更新（叠加前两条线）

```
@Transactional + ldapTemplate.update(person)
 ├─ ContextSourceTransactionManager.doBegin（:133）
 │    └─ delegate.doBegin：getReadWriteContext() → ThreadLocal(DirContextHolder)
 │       （TransactionAwareContextSourceProxy 让事务内所有取连接都拿到它）         ← 8.2
 ├─ update（LdapTemplate.java:1626）
 │    ├─ odm.getId vs getCalculatedId → DN 变了？
 │    │    ├─ 是：unbind(旧) + bind(新)（每步都先经 Recorder 备份：rename 到 _temp）← 8.2
 │    │    └─ 否：lookup(id, manageClass) → setUpdateMode(true)
 │    │            → mapToLdapDataEntry → modifyAttributes(getModificationItems)  ← 6.2 + 7.3
 │    └─ 每个写调用抛异常？ → doRollback（:166）按记录逆序执行 Executor.rollback()
 │         （rebind 的 rollback = unbind 新 + rename(_temp → 原)）
 └─ commit（:150）→ 各 Executor.commit() 清理临时条目 + 归还 ThreadLocal 连接
```

## 10.4 从源码中提炼的四个设计模式视角

1. **模板方法 + 策略**：`executeWithContext`/`search` 是模板骨架，`SearchExecutor`/`CallbackHandler`/`DirContextProcessor`/`DirContextAuthenticationStrategy` 是四组策略插槽（3.3、4.3）。
2. **工厂 + SPI 钩子**：`ContextSource` 是连接工厂；`DefaultDirObjectFactory` 借 JNDI 自身的 `ObjectFactory` SPI 实现"返回值换货"（6.3）——**不改宿主代码而改变宿主行为的教科书**。
3. **命令 + 补偿**：Recorder/Executor 对（8.2）就是 Command 模式的"记录-执行-补偿"三段式，`AbstractPlatformTransactionManager` 提供事务编排骨架。
4. **装饰器链**：`ContextSource` 可被 `PoolingContextSource`→`ObservationContextSource`→`TransactionAwareContextSourceProxy` 层层包裹——每层只做一件事，接口不变。

---

# 十一、附录

## 11.1 关键接口速查表

| 接口/类 | 位置 | 一句话 |
|---|---|---|
| `ContextSource` | core/ContextSource.java:33 | 连接工厂三方法（读/写/指定账号） |
| `AuthenticationSource` | core/AuthenticationSource.java:26 | "当前该用谁的身份"提供者 |
| `DirContextAuthenticationStrategy` | core/support/DirContextAuthenticationStrategy.java:38 | 认证方式两段式策略 |
| `LdapOperations` | core/LdapOperations.java:47 | LdapTemplate 的接口（1746 行全重载） |
| `LdapTemplate` | core/LdapTemplate.java:82 | 模板内核（本文第四章） |
| `LdapClient` | core/LdapClient.java:52 | 3.1 流式 API |
| `SearchExecutor` | core/SearchExecutor.java:39 | "搜什么"插槽 |
| `NameClassPairCallbackHandler` | core/NameClassPairCallbackHandler.java:31 | "每条结果"插槽 |
| `AttributesMapper` / `ContextMapper` | core/AttributesMapper.java:40 / core/ContextMapper.java:50 | 两个映射回调 |
| `DirContextProcessor` | core/DirContextProcessor.java:30 | search 前后钩子（Controls 载体） |
| `DirContextOperations` | core/DirContextOperations.java:30 | 条目门面（=DirContext+LdapDataEntry+修改感知） |
| `DirContextAdapter` | core/DirContextAdapter.java:112 | 门面实现 + updateMode 差量 |
| `DefaultDirObjectFactory` | core/support/DefaultDirObjectFactory.java:43 | JNDI SPI 换货钩子 |
| `ObjectDirectoryMapper` | core/odm/core/ObjectDirectoryMapper.java:36 | ODM 映射契约 |
| `@Entry/@Id/@Attribute/@DnAttribute/@Transient` | core/odm/annotations/ | ODM 五注解 |
| `LdapQueryBuilder` | query/LdapQueryBuilder.java:64 | 查询 DSL（@since 2.0） |
| `Filter` 及 filter 包 | filter/ | 过滤器对象树 |
| `NamingException`(org.springframework.ldap) | NamingException.java:36 | unchecked 异常根 |
| `LdapUtils` / `LdapEncoder` | support/ | DN/异常/转义工具 |
| `PoolingContextSource` / `PooledContextSource` | pool/factory/:146 / pool2/factory/:83 | 两代显式连接池 |
| `ContextSourceTransactionManager` | transaction/compensating/manager/:102 | 补偿事务入口 |
| `PagedResultsDirContextProcessor` | control/PagedResultsDirContextProcessor.java:38 | RFC 2696 分页 |
| `ObservationContextSource` | core/support/ObservationContextSource.java:67 | Micrometer 观测（@since 3.3） |
| `LdifParser` | ldif/ldif-core/.../parser/LdifParser.java:98 | LDIF 解析 |
| `EmbeddedLdapServer` / `LdapTestUtils` | test-support/.../test/ | 内嵌服务器与测试编排 |

## 11.2 初学者学习路线（动手向）

1. **起一个内嵌 LDAP**：Boot 项目加 `spring.ldap.embedded.base-dn`（或 testcontainers openldap），`LdapAutoConfiguration` 免配置即通。
2. **读 + 映射**：`ldapTemplate.search(LdapQueryBuilder.query().where("objectClass").is("person"), contextMapper)`，体会 ContextMapper 拿到的就是 `DirContextAdapter`（6.3）。
3. **写 + 差量**：`lookupContext(dn)` → `setUpdateMode(true)` → `setAttributeValue` → `modifyAttributes(ctx)`，打印 `getModificationItems()` 看差量（6.2）。
4. **ODM**：定义 `@Entry` 类，跑 `findByDn/create/update`；故意改 `@DnAttribute` 字段观察"删旧建新"分支（7.3 的 ⑤）。
5. **分页**：8.1 的 cookie 循环；用 `SingleContextSource` 保证翻页同连接。
6. **补偿事务**：`<ldap:transaction-manager>`（或 Java Config 注册 `ContextSourceTransactionManager` + `TransactionAwareContextSourceProxy`），事务里故意抛异常，验证 `_temp` 条目被 rename 回来。
7. **观测**：打开 `management.tracing` + Observation，看 `spring.ldap.dir.context.operations` 如何挂在 Security 观测下（8.5）。

## 11.3 源码阅读入口清单（15 个关键文件）

1. `core/src/main/java/org/springframework/ldap/core/LdapTemplate.java` —— 一切起点（:368 search 主干 / :801 execute / :1336 authenticate / :1601 ODM create）
2. `core/src/main/java/org/springframework/ldap/core/support/AbstractContextSource.java` —— 连接与环境表（:145 doGetContext）
3. `core/src/main/java/org/springframework/ldap/core/ContextSource.java` —— 三方法契约
4. `core/src/main/java/org/springframework/ldap/core/support/DefaultDirObjectFactory.java` —— JNDI 换货钩子
5. `core/src/main/java/org/springframework/ldap/core/DirContextAdapter.java` —— updateMode 差量（:232/:286）
6. `core/src/main/java/org/springframework/ldap/support/LdapUtils.java` —— 异常转译表（:93）
7. `core/src/main/java/org/springframework/ldap/NamingException.java` —— unchecked 根类与序列化修补
8. `core/src/main/java/org/springframework/ldap/odm/core/impl/DefaultObjectDirectoryMapper.java` —— ODM 引擎（:119/:211/:302）
9. `core/src/main/java/org/springframework/ldap/query/LdapQueryBuilder.java` —— 查询 DSL
10. `core/src/main/java/org/springframework/ldap/support/LdapEncoder.java` —— filter/DN 转义（:102）
11. `core/src/main/java/org/springframework/ldap/control/PagedResultsDirContextProcessor.java` —— 分页
12. `core/src/main/java/org/springframework/ldap/transaction/compensating/RebindOperationExecutor.java` —— 补偿哲学（:85/:102/:111）
13. `core/src/main/java/org/springframework/ldap/core/LdapClient.java` —— 3.1 流式 API
14. `core/src/main/java/org/springframework/ldap/core/support/ObservationContextSource.java` —— 可观测
15. `core/build.gradle` —— 依赖即架构（micrometer api、pool 双代、ldapbp provided）

## 结语

Spring LDAP 是一份"**如何优雅地包装一个笨重协议 API**"的完整教案：它没有试图发明新数据模型，而是在 JNDI 之上用三层包装（连接工厂 / 模板方法 / 对象映射）逐项消除工程痛点，再以两件独门武器——镜像异常转译表与"rename 备份"补偿事务——解决了受检异常与不可回滚这两个协议级难题。读懂它的 `search` 主干 80 行、`doGetContext` 20 行、`RebindOperationExecutor` 40 行，你对"框架如何驯服协议"的理解，会比读十个抽象设计模式教程更扎实。

下一篇可衔接：Spring Security 的 LDAP 认证集成（`spring-security-ldap` 的 `LdapAuthenticationProvider` 正是构建在本文 4.6 节的 authenticate 两步走之上）。
