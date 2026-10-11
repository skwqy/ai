# Spring Data Commons 深度源码解析（写给初学者的架构全景）

> **本文基于的源码**：`D:\code\3rd\spring-data-commons`，版本 **4.2.0-SNAPSHOT**（main 分支快照，Git commit `0721eddc1c`，2026-10-06）。它随发布列车 **2026.1** 开发，预计 2026-11 GA；文中所有【源码证据】的文件路径与行号均为对该快照实际读取所得。
>
> **版本取舍说明**：Spring Data Commons 的核心骨架（`Repository` 接口族、`RepositoryFactorySupport` 代理工厂、`PartTree` 方法名解析器、`MappingContext` 映射上下文）自 2.x（2017）以来高度稳定，3.0（2022-11）完成 Jakarta EE 迁移，4.0（2025-11）完成表达式体系重构。因此本文内容对使用 Spring Boot 3.x（Data 3.x）的读者同样适用——差异集中在第九、十一章的"4.0 变化"提示框中；5.x 时代的演进对比见 1.6 节，所有"特性属于哪个版本"的结论均经本地 git 标签（v1.0.0.RELEASE ~ v4.1.1，共 406 个标签）逐项实证。
>
> **阅读约定**：每章"先白话、后源码"——先用一两句人话讲清"这是什么、为什么需要它"，再给出源码证据链（`src/main/java/.../类名.java` + 方法名 + 行号 + 代码片段）。行号只对该快照精确，读者用 IDEA 打开源码按类名 + 方法名定位即可。

## 如何读这份文档

如果你是 Spring Data 初学者，推荐两遍读法：

- **第一遍（建立地图，1~2 小时）**：只读第一章（总览）每一节的开头白话段、各章的"小结"节、以及第十二章（贯通视图）。目标是能回答：`extends Repository` 的空接口为什么能 `save()`？`findByName` 是谁解析的？自定义实现片段如何混入代理？
- **第二遍（深入源码）**：对照每一章的【源码证据】，打开源码逐行读。顺序建议：第二章（Repository 抽象）→ 第三章（查询方法）→ 第十一章（配置引导，理解"接口如何变成 Bean"）→ 第四章（分页游标）→ 第五、六章（映射与转换）→ 第七、八、九、十章（随用随查）。

---

# 一、总览：Spring Data Commons 的定位、设计哲学与整体架构

## 1.1 一句话定位

**Spring Data Commons 是整个 Spring Data 家族的"公共内核"——它自己不连接任何数据库，却定义了所有 Spring Data 模块共享的公共语言**：Repository 接口族、方法名推导查询的解析引擎、分页/排序/游标抽象、对象映射元数据、审计、实体回调、类型转换与投影。你天天写的 `JpaRepository`、`MongoRepository`、`RedisRepository`，它们"看起来一样"的部分全部来自这个模块。

一个容易被名字误导的事实：**这个 Maven 工件的 artifactId 叫 `spring-data-commons`，但 pom 里声明的正式名称是 "Spring Data Core"**。

【源码证据】`pom.xml` 第 6-9 行：

```xml
<groupId>org.springframework.data</groupId>
<artifactId>spring-data-commons</artifactId>
<version>4.2.0-SNAPSHOT</version>

<name>Spring Data Core</name>
<description>Core Spring concepts underpinning every Spring Data module.</description>
```

它解决的问题不是"如何访问某个数据库"（那是 JPA/MongoDB/Redis 等子模块的事），而是"**如何让访问一百种不同的数据库时，开发者看到的是同一套 API**"。用官方 README 的话说，它是"every Spring Data module's central abstractions"——每个 Spring Data 模块脚下共同的那块地皮。

## 1.2 设计哲学：读源码前先记住五句话

1. **空接口即契约，标记即元数据**。`Repository<T, ID>` 本身一个方法都没有（见 2.3 节），但它承载了两件事：① 泛型参数 `<T, ID>` 告诉框架"我管理什么实体、主键是什么类型"；②"继承了我"这个事实本身告诉组件扫描器"把我注册成 Bean"。一个空接口同时是类型系统、扫描标记和 API 契约。
2. **接口是门面，组合是实现**。你调用的 repository 方法并不"在接口里被实现"，而是由若干 **fragment（片段）** 组合路由出去：查询方法交给各存储的查询执行器，CRUD 方法交给基类实例，你手写的自定义实现作为又一个 fragment 插进队列（见 2.7 节）。`RepositoryComposition` 是整个框架的"方法分发总线"。
3. **存储差异关在 SPI 笼子里**。Commons 只定义抽象方法（"给我一个查询策略 `QueryLookupStrategy`""给我目标基类 `getRepositoryBaseClass`"），每种存储通过实现几个 SPI 类入驻——这解释了为什么新存储模块（如 Elasticsearch、Couchbase）能不断加入而内核不动（见 11.2 节）。
4. **元数据先行**。任何实体在被读写之前，都会先被解析成 `PersistentEntity`（实体的"户口本"：哪个是 ID、哪个是版本号、哪些属性可持久化），缓存在 `MappingContext` 里供所有模块共享（见第五章）。审计、映射、投影、QBE 全部消费这份元数据，而不是各自反射。
5. **所有"魔法"都是代理 + 拦截器链**。Repository 接口没有实现类，运行期由 JDK 动态代理生成实例；方法调用依次穿过 Nullness 校验 → 事务拦截器 → 查询执行器/片段路由 → 结果转换（见 2.6、12.2 节）。理解了"接口方法被拦截"这件事，Spring Data 的所有行为都变得可解释。

## 1.3 包结构全景

模块只有一个（`spring-data-commons`），但 `src/main/java/org/springframework/data` 下实测有 **21 个顶层包（44 个含子包）、597 个类**（不含 package-info；4.2.0-SNAPSHOT）。按"用户感知"分层如下：

```
┌──────────────────────── Web 支撑层（可选依赖 spring-web/webmvc） ────────────────────────┐
│  web/           Pageable/Sort 参数解析器、DomainClassConverter、投影式消息转换器          │
│  web/config/    @EnableSpringDataWebSupport、SpringDataWebConfiguration、Jackson 2/3 模块 │
├──────────────────────── 领域能力层（与存储无关的"业务性"能力） ──────────────────────────┤
│  domain/        Pageable、Sort、Page、Slice、Window/ScrollPosition、Example(QBE)、        │
│                 AuditorAware、AbstractAggregateRoot、Vector/SearchResult（向量检索）      │
│  auditing/      @CreatedDate 等注解的落地引擎 AuditingHandler、DateTimeProvider           │
│  history/       Revision 修订版本对象族                                                   │
│  geo/           Point、Distance、Circle、Polygon 等地理类型                               │
├──────────────────────── 查询与映射层（Commons 的"心脏地带"） ────────────────────────────┤
│  repository.query/        QueryMethod、QueryLookupStrategy、PartTree 解析入口、QBE 执行器 │
│  repository.query.parser/ PartTree、Part、OrderBySource、AbstractQueryCreator（解析引擎）│
│  mapping/ + context/ + model/   PersistentEntity/PersistentProperty/MappingContext、     │
│                 实例化器（EntityInstantiators）、字段命名策略、属性访问器                  │
│  mapping.callback/        EntityCallbacks 实体回调管道                                   │
│  convert/       CustomConversions、PropertyValueConverter、TypeMapper（多态类型映射）     │
│  projection/    接口投影/DTO 投影工厂（ProxyProjectionFactory…）                          │
│  core/          TypeInformation、PropertyPath（泛型与属性路径的地基）                      │
│  expression/    ValueExpressionParser 值表达式抽象（3.3+，4.0 表达式重构的主角）           │
│  spel/          EvaluationContextProvider（SpEL 求值上下文扩展）                          │
├──────────────────────── Repository 框架层（"容器集成 + 代理工厂"） ──────────────────────┤
│  repository/              Repository、CrudRepository、ListCrudRepository、标记注解        │
│  repository.core/         RepositoryMetadata/RepositoryInformation/EntityInformation     │
│  repository.core.support/ RepositoryFactorySupport（代理工厂）、RepositoryComposition、  │
│                           事务/事件/异常转译等 ProxyPostProcessor                         │
│  repository.config/       @EnableXxxRepositories 的公共骨架（扫描→注册 BeanDefinition）   │
│  repository.support/      Repositories 门面、RepositoryInvoker（供 Data REST 使用）       │
│  repository.reactive/     ReactiveCrudRepository、RxJava3CrudRepository                  │
│  repository.cdi/          CDI 容器扩展；repository.init/ 数据填充器；repository.history/  │
│                           RevisionRepository                                             │
├──────────────────────── 配置与引导层 ───────────────────────────────────────────────────┤
│  aot/           ManagedTypes、GraalVM AOT 处理器；config/ 通用配置小件                    │
│  transaction/   MultiTransactionStatus、ChainedTransactionManager（已废弃）               │
├──────────────────────── 地基 ──────────────────────────────────────────────────────────┤
│  annotation/    @Id、@Version、@Transient、@AccessType、@CreatedBy…（16 个通用注解）      │
│  util/          Streamable、Lazy、Pair、QueryExecutionConverters 等 38 个工具类           │
│  javapoet/      重打包的 JavaPoet（AOT 代码生成用）                                       │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

【源码证据】`src/main/java/org/springframework/data/repository/core/support/RepositoryFactorySupport.java`——整个"Repository 框架层"的中枢类，包级 Javadoc（第 17-31 行）自述职责：

```java
 * Factory to create {@link Repository} instances. ……
 * 该工厂负责：元数据解析 → 查询策略装配 → 代理创建 → 片段组合校验
```

> 注：Spring Data 各子模块（JPA、MongoDB…）会把这些公共类型"重新导出"为更顺手的子类（如 `JpaRepository extends PagingAndSortingRepository`），所以你在 JPA 里见到的类型名，多数能在这里找到"母版"。

## 1.4 模块依赖关系（以 pom.xml 实证）

Spring Data Commons 是单模块工程，它的"分层"体现在**依赖的可选性**上——必选依赖只有 Spring 基础四件，其余全是 optional（给需要该能力的模块按需开启）：

| 依赖 | 必选/可选 | 作用 |
|---|---|---|
| spring-core / spring-beans / spring-context / spring-expression | **必选** | 类型转换、Bean 注册、事件与 SpEL |
| spring-tx | 必选 | 事务抽象（`TransactionInterceptor` 接入仓库代理，见 9.1 节） |
| jakarta.annotation-api / jakarta.transaction-api | 必选 | @PostConstruct、@Transactional 等注解 |
| reactor-core | 可选 | 响应式仓库（`ReactiveCrudRepository`，见 2.3.4 节） |
| jakarta.enterprise.cdi-api | 可选 | CDI 容器扩展（见 11.5 节） |
| spring-web / spring-webmvc / spring-webflux | 可选 | Web 参数解析器、消息转换器（见第十章） |
| spring-hateoas | 可选 | PagedModel 超媒体模型（见 10.4 节） |
| jakarta.xml.bind-api、xmlbeam、scala-library、querydsl | 可选 | JAXB 适配、XML 投影、QueryDSL 绑定 |
| kotlin-stdlib、kotlin-coroutines | 可选 | Kotlin 实体实例化、协程仓库支持 |

【源码证据】`pom.xml` 第 40 行起——`spring-web`、`reactor-core`、`jakarta.enterprise.cdi-api` 等依赖的声明均带 `<optional>true</optional>`（实测 60 行上下各依赖块）：

```xml
<dependency>
	<groupId>org.springframework</groupId>
	<artifactId>spring-web</artifactId>
	<optional>true</optional>
</dependency>
```

这张表本身就是架构说明：**commons 是"低血量高权限"的公共内核**——没有它，所有 Data 模块瘫痪；但它对上层（web/hateoas/reactor）的依赖全部可缺席，让 Redis 模块不必背着 web 容器跑。依赖方向上，它是所有 `spring-data-*` 模块的唯一公共祖先（任何子模块 pom 必有 `spring-data-commons` 依赖），且自身只依赖 spring-core 家族——处于依赖图的最底层。

## 1.5 关键问题 → Spring Data Commons 方案映射（全文导览）

| 使用 Spring Data 时的关键问题 | Commons 的方案 | 详见 |
|---|---|---|
| `UserRepository` 只是个接口，`save()` 从哪来？ | RepositoryFactorySupport 生成 JDK 动态代理，方法按"查询方法/片段方法/基类方法"路由 | 第二章 |
| `findByNameAndAgeLessThan` 没写 SQL，为什么能查？ | PartTree 方法名解析器 + 各存储 AbstractQueryCreator 翻译 | 第三章 |
| 分页为什么全生态一个写法（`PageRequest.of`）？ | Pageable/Sort/Page/Slice 统一抽象 + PageableExecutionUtils | 第四章 |
| 实体字段的读写映射、ID/版本号识别 | MappingContext + PersistentEntity + TypeInformation | 第五章 |
| 自定义类型怎么存（如枚举、金额、日期）？ | CustomConversions + PropertyValueConverter | 第六章 |
| 接口投影（只取部分字段）为什么不写实现？ | ProxyProjectionFactory 动态代理 + SpEL 求值 | 6.4 节 |
| 创建人/创建时间自动填充 | @CreatedBy/@CreatedDate + AuditingHandler | 第七章 |
| save 前后挂自定义逻辑（加密、清理缓存） | EntityCallback 回调体系 | 第八章 |
| 聚合根发布领域事件 | AbstractAggregateRoot + @DomainEvents | 8.2 节 |
| Repository 方法上的 @Transactional 如何生效 | TransactionalRepositoryProxyPostProcessor（复用 Spring 事务拦截器） | 第九章 |
| Controller 方法签名直接写 `Pageable`/`Sort` | HandlerMethodArgumentResolver 族 + DomainClassConverter | 第十章 |
| `@EnableJpaRepositories` 做了什么 | RepositoryConfigurationDelegate 扫描注册（所有存储共用） | 第十一章 |
| 多存储并存/原生 GraalVM | 多仓库模式 + ManagedTypes AOT | 11.6 节 |

## 1.6 版本演进：1.0 → 4.x 关键变化对比

写作时（2026 年 10 月）的版本格局：4.1（列车 2026.0）2026-06-09 GA，main 分支正在开发 4.2（列车 2026.1，预计 2026-11 GA——正是本文分析的这份快照）。本节所有"特性属于哪个版本"的结论均经本地仓库 git 标签对源码逐项 grep 实证（`git cat-file -p <tag>:<file>` / `git ls-tree <tag>`），GA 日期取自各 release tag 的提交信息。

### 1.6.1 版本时间线与运行基线

| 版本 | GA 时间（git tag 实证） | 对应 Framework / Boot | 发布列车 | 一句话主题 |
|---|---|---|---|---|
| 1.0 | 2011-04-18 | Spring 3.0 | — | Repository 基座诞生（1.0.0.RELEASE tag） |
| 2.0（Kay） | 2017-10-02 | Spring 5.0 | Kay | Java 8 + 响应式起点：`ReactiveCrudRepository`（Flux/Mono）登场、`Streamable` 引入 |
| 3.0（Turing） | 2022-11-18 | Spring 6.0 / Boot 3.0 | 2022.0 | **Jakarta EE 9 迁移（javax.→jakarta.）+ JDK 17 基线**；`ListCrudRepository` 补齐 List 返回类型 |
| 3.1 | 2023-05-12 | Spring 6.0 / Boot 3.1 | 2023.0 | **Scroll API**（Window/ScrollPosition/WindowIterator） |
| 3.2 | 2023-11-17 | Spring 6.1 / Boot 3.2 | 2023.1 | `Limit` 限流参数（与方法名 findTopN 合并，`PartTree#getResultLimit`） |
| 3.3 | 2024-05-17 | Spring 6.1 / Boot 3.3 | 2024.0 | **Value Expressions**（`org.springframework.data.expression`，SpEL 之外的轻量表达式） |
| 3.4 | 2024-11-15 | Spring 6.2 / Boot 3.4 | 2024.1 | `RepositoryMethodContext`（方法级元数据 ThreadLocal 暴露） |
| 3.5 | 2025-05-16 | Spring 6.2 / Boot 3.5 | 2025.0 | **Vector/Score/SearchResult 向量检索抽象** |
| 4.0 | 2025-11-14 | Spring 7.0 / Boot 4.0 | 2025.1 | **表达式体系重构**（SpelQueryContext→ValueExpressionDelegate）、`org.springframework.data.core` 包整编、Jackson 3 支持、空安全 jspecify 标准化 |
| 4.1 | 2026-06-09 | Spring 7.1.x / Boot 4.1 | 2026.0 | `TypedPropertyPath`（lambda 类型安全属性路径）、lambda 内省 |
| 4.2（本文快照） | 预计 2026-11 | Spring 7.1 | 2026.1 | 迭代增强 |

基线与迁移的源码证据（本地 git 标签实证）：

| 结论 | 证据 |
|---|---|
| 3.0 完成 javax.→jakarta. | 3.0.0 标签 `pom.xml:91-102` 依赖改用 `jakarta.servlet-api`/`jakarta.xml.bind-api`/`jakarta.annotation`；源码实测出现 `import jakarta.xml.bind...`（domain/jaxb 包） |
| 2.0 仍用 javax | 2.0.0.RELEASE 标签 pom 依赖为 `javax.servlet:javax.servlet-api` |
| 2.0 响应式起点 | `ReactiveCrudRepository` 在 2.0.0.RELEASE 已存在且 import `reactor.core.publisher.Flux/Mono`；1.13.0.RELEASE 无此类 |
| 4.0 JDK/框架基线 | 4.0.0 GA（2025-11-14）与 Spring Framework 7.0 GA（2025-11-13）、Boot 4.0 同列车发布（train 2025.1） |

### 1.6.2 特性引入版本对照表（git 实证，可复现）

对每个特性，检查其在各 GA 标签上对应源码文件是否存在（`git cat-file -e <tag>:<path>`）：

| 特性 | 代表类型 | 2.0 | 3.0 | 3.1 | 3.3 | 3.4 | 3.5 | 4.0 |
|---|---|---|---|---|---|---|---|---|
| Repository CRUD 族 | `CrudRepository` | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ |
| List 返回类型 CRUD | `ListCrudRepository` | ✘ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ |
| Scroll 游标 API | `Window`/`ScrollPosition` | ✘ | ✘ | ✔ | ✔ | ✔ | ✔ | ✔ |
| 属性级转换器 | `PropertyValueConverter` | ✘ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ |
| 值表达式 | `ValueExpressionParser`（expression 包） | ✘ | ✘ | ✘ | ✔ | ✔ | ✔ | ✔ |
| 方法级元数据 | `RepositoryMethodContext` | ✘ | ✘ | ✘ | ✘ | ✔ | ✔ | ✔ |
| 向量检索 | `Vector`/`SearchResult` | ✘ | ✘ | ✘ | ✘ | ✘ | ✔ | ✔ |
| 4.0 表达式重构 | `ValueExpressionDelegate`（query 包） | ✘ | ✘ | ✘ | ✘ | ✘ | ✘ | ✔ |

（✔ = 该标签源码中已存在，✘ = 不存在；文件数随版本增长：2.0 共 421 个 java 文件 → 3.0 共 539 → 3.5 共 596 → 4.0 共 632。）

4.0 的"破坏性整编"值得单独一提（3.5.0 与 4.0.0 标签源码树 diff 实证）：`PropertyPath`、`TypeInformation`、`ReactiveWrappers`、`NullableWrapperConverters` 从 `mapping`/`util` 等包**集中迁入 `org.springframework.data.core`**；`SpelEvaluator`/`SpelQueryContext`/`QueryMethodEvaluationContextProvider` 家族被**删除**，由 `ValueExpressionDelegate` + `QueryMethodValueEvaluationContextAccessor` 接替；`@PersistenceConstructor` 注解移除（由构造器发现机制自动处理）；`MethodsMetadataReader`（类型/类读取器）整体移除。

## 1.7 全文章节地图

- **第二章 Repository 抽象（org.springframework.data.repository）**：从"空接口为什么能 save"讲起，走完 FactoryBean 接线 → 元数据解析 → 片段组合 → 代理装配 → 拦截器链的完整生成流程。
- **第三章 查询方法体系**：`findDistinctTop3ByLastnameIgnoreCaseOrderByFirstnameDesc` 是如何被正则切开、变成 PartTree、再交给存储模块翻译的；参数绑定与 SpEL。
- **第四章 分页、排序与游标**：Pageable/Page/Slice 家族、总数计算优化，以及 3.1 的 Scroll API（keyset 分页）。
- **第五章 映射元数据**：TypeInformation 驯服泛型擦除、MappingContext 缓存、实体实例化与属性访问器。
- **第六章 类型转换与投影**：CustomConversions 类型字典、PropertyValueConverter 属性级转换、接口投影的动态代理。
- **第七章 审计**：四个审计注解如何被 AuditingHandler 兑现。
- **第八章 实体回调与领域事件**：EntityCallback 管道与聚合根事件发布。
- **第九章 事务支持**：仓库代理上的 @Transactional 全链路。
- **第十章 Web 层支持**：Pageable 参数解析、DomainClassConverter、超媒体与投影式消息转换。
- **第十一章 配置、引导与扩展机制**：@EnableXxxRepositories 公共骨架、自定义实现检测、CDI、AOT、Revision、数据填充。
- **第十二章 贯通视图**：把"启动时间线"（接口→Bean→代理）与"调用时间线"（方法→拦截器→存储→转换）叠加成全景。
- **第十三章 附录**：接口速查表、学习路线、源码阅读入口清单。


---

# 二、Repository 抽象：接口族与代理工厂（org.springframework.data.repository）

> 本章对应源码：`src/main/java/org/springframework/data/repository` 及其 `core/support` 子包。所有【源码证据】行号均基于 4.2.0-SNAPSHOT 快照。

## 2.1 先说白话：一个接口，没有任何方法，凭什么能工作？

每个 Spring Data 用户的第一行数据访问代码几乎都是：

```java
public interface UserRepository extends JpaRepository<User, Long> {
	Optional<User> findByEmail(String email);
}
```

注入 `UserRepository` 后直接 `save()`、`findByEmail()` 就能用——没有实现类。这是怎么做到的？答案分两半：

1. **谁发现这个接口、谁把它变成 Bean？** —— `@EnableJpaRepositories` 触发的组件扫描（第十一章）会扫描所有继承 `Repository` 的接口，为每一个注册一个 **FactoryBean**（`RepositoryFactoryBeanSupport` 的子类）。FactoryBean 的 `getObject()` 返回一个 **JDK 动态代理**。
2. **代理收到方法调用后做什么？** —— 方法被两层拦截器处理：是"查询方法"（返回值/名字符合查询方法特征）就交给查询执行器（第三章）；否则交给"片段组合"（composition）按注册顺序路由——先自定义实现片段、再存储基类片段（`SimpleJpaRepository` 等）。

本章按"接口族 → 元数据 → FactoryBean → 代理工厂 → 片段组合 → 查询拦截器"的顺序，把这六步拆开。

## 2.2 模块定位与包结构

用大白话说：**`org.springframework.data.repository` 就是"Repository 世界的 spring-beans"**——定义配方（接口族）、登记配方（组件扫描）、按配方造物（代理工厂）。它内部再分六个子包：

```
org.springframework.data.repository
├── Repository.java          # 一切的起点：空标记接口
├── CrudRepository.java      # CRUD 能力集（Optional 风格返回 Iterable）
├── ListCrudRepository.java  # CRUD 能力集（List 风格，3.0 新增）
├── PagingAndSortingRepository.java / ListPagingAndSortingRepository.java
├── NoRepositoryBean.java / RepositoryDefinition.java   # 扫描器控制注解
├── core/                    # RepositoryMetadata/RepositoryInformation/EntityInformation
│   └── support/             # ★ 代理工厂与一切拦截器都住这里（40+ 类）
├── query/                   # 查询方法体系（第三章）
├── config/                  # @EnableXxxRepositories 公共骨架（第十一章）
├── reactive/                # 响应式仓库接口族
├── cdi/ init/ history/ support/ util/   # CDI、数据填充、修订版本、门面、工具
```

【源码证据】`repository/core/support/package-info.java` 第 1-3 行——"support 子包 = 支撑工厂实现的骨架"：

```java
/**
 * Support classes to implement repositories for specific data stores.
 * Contains base classes for {@link org.springframework.data.repository.RepositoryFactory} implementations.
 */
```

## 2.3 Repository 接口族：一个空接口 + 两层能力集

### 2.3.1 顶层标记：Repository

【源码证据】`repository/Repository.java` 第 20-36 行（全文核心就这么几行）：

```java
/**
 * Central repository marker interface. Captures the domain type to manage as well as
 * the domain type's id type. ……
 */
@Indexed
public interface Repository<T, ID> {

}
```

三个细节：① `@Indexed` 让它进入 Spring 的编译期组件索引（AOT 优化，见 `spring-context-indexer`）；② 泛型 `<T, ID>` 是框架提取元数据的唯一入口——`RepositoryFactorySupport` 会解析出"管理 User、主键 Long"并存进 `RepositoryMetadata`（2.4 节）；③ 它**没有声明任何方法**，你 `extends Repository` 时可以自由选择暴露哪些方法（甚至一个都不暴露），这正是 Javadoc 说的 "can selectively expose CRUD methods by simply declaring methods of the same signature"。

### 2.3.2 CRUD 能力集：CrudRepository 与 ListCrudRepository

【源码证据】`repository/CrudRepository.java` 第 32-33、46、60、69、78 行：

```java
@NoRepositoryBean
public interface CrudRepository<T, ID> extends Repository<T, ID> {

	<S extends T> S save(S entity);                          // L46
	<S extends T> Iterable<S> saveAll(Iterable<S> entities); // L60
	Optional<T> findById(ID id);                             // L69
	boolean existsById(ID id);                               // L78
	……
```

`@NoRepositoryBean` 的作用是告诉扫描器"**别把我自己注册成仓库 Bean**"——它是能力集，不是某个具体实体仓库（第 35-38 行）：

```java
/**
 * Flags a repository interface to be excluded from repository creation
 * which basically is of side-effect for deriving intermediate repository interfaces.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface NoRepositoryBean {
```

为什么 3.0 又造了一个 `ListCrudRepository`？因为 `CrudRepository#findAll` 返回 `Iterable<T>`，用户拿到后总要 `.stream().collect(toList())`。3.0 用接口默认方法的"协变返回类型覆盖"把返回值收紧为 `List`：

【源码证据】`repository/ListCrudRepository.java` 第 30-54 行：

```java
@NoRepositoryBean
public interface ListCrudRepository<T, ID> extends CrudRepository<T, ID> {

	@Override
	<S extends T> List<S> saveAll(Iterable<S> entities);     // L45-46

	@Override
	List<T> findAll();                                       // L53-54

	@Override
	List<T> findAllById(Iterable<ID> ids);                   // L68-69
```

于是 3.0 之后各存储模块的"全家桶"习惯是：`JpaRepository<T, ID> extends ListCrudRepository<T, ID>, ListPagingAndSortingRepository<T, ID>`——调用侧永远拿到 `List`/`Page` 这类精确类型。

### 2.3.3 分页排序能力集：PagingAndSortingRepository

【源码证据】`repository/PagingAndSortingRepository.java` 第 35、44、53 行：

```java
@NoRepositoryBean
public interface PagingAndSortingRepository<T, ID> extends Repository<T, ID> {

	Iterable<T> findAll(Sort sort);          // L44
	Page<T> findAll(Pageable pageable);      // L53
```

注意它**不含** save/findById——CRUD 与分页是两块可独立选装的"积木"，这正是空接口 + 选择性暴露设计的兑现。

### 2.3.4 响应式能力集：ReactiveCrudRepository 与 RxJava3

【源码证据】`repository/reactive/ReactiveCrudRepository.java` 第 48-62 行：

```java
@NoRepositoryBean
public interface ReactiveCrudRepository<T, ID> extends Repository<T, ID> {

	<S extends T> Mono<S> save(S entity);
	<S extends T> Flux<S> saveAll(Iterable<S> entities);
	……
```

同目录的 `ReactiveSortingRepository`（供 Reactive 栈排序查询）、`RxJava3CrudRepository`/`RxJava3SortingRepository`（RxJava 风格 `Observable`/`Single`）构成完整的响应式接口族。它们与命令式接口族**共享同一个代理工厂**（工厂侧通过 `RepositoryMetadata#isReactiveRepository()` 识别，见 2.6 节）。

### 2.3.5 接口族全景

```
Repository<T, ID>（空标记 + @Indexed）
├── CrudRepository（save/findById/findAll/existsById/count/delete…）
│   ├── ListCrudRepository（3.0：List 收紧返回类型）
│   ├── PagingAndSortingRepository（findAll(Sort) / findAll(Pageable)）
│   │   └── ListPagingAndSortingRepository（3.0）
│   └── ReactiveCrudRepository（Mono/Flux）… RxJava3CrudRepository
├── RevisionRepository<T, ID, N>（修订版本，11.4 节）
└── QueryByExampleExecutor<T> / ReactiveQueryByExampleExecutor<T>（QBE，3.8 节）

标记注解：@NoRepositoryBean（能力集接口排除扫描）
         @RepositoryDefinition（不用继承 Repository 也能声明仓库，见下）
```

【源码证据】`repository/RepositoryDefinition.java` 第 39-55 行——少数派的替代姿势：

```java
/**
 * Annotation to demarcate a custom repository. ……
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RepositoryDefinition {   // L39
	Class<?> domainClass();                // L47
	Class<?> idClass();                    // L55
```

即"不想继承 `Repository` 时，在接口上标 `@RepositoryDefinition(domainClass = User.class, idClass = Long.class)`"——扫描器识别效果等价。

## 2.4 RepositoryMetadata / RepositoryInformation：接口的"元数据卡"

代理工厂在创建仓库前要回答一批问题：这个接口管理什么实体？主键类型？是响应式仓库吗？哪些方法是查询方法、哪些应由片段实现？这些答案被统一收进两个接口。

【源码证据】`repository/core/RepositoryMetadata.java` 第 33 行起（节选）：

```java
public interface RepositoryMetadata {

	Class<?> getRepositoryInterface();      // L76：仓库接口本身
	Class<?> getDomainType();               // 实体类型（T）
	Class<?> getIdType();                   // 主键类型（ID）
	boolean isReactiveRepository();         // 是否响应式仓库
	……
```

而 `RepositoryInformation` 再往上叠一层"**方法级**"信息——每个方法有没有被基类/片段实现：

【源码证据】`repository/core/RepositoryInformation.java` 第 28-40 行（节选）：

```java
public interface RepositoryInformation extends RepositoryMetadata {   // L28

	boolean isBaseClassMethod(Method method);        // L37：该方法是否由存储基类实现
	Method getBaseClassMethod(Method method);        // 取基类上对应的 Method 对象
	……
```

"哪些算查询方法"的判定在父接口 `RepositoryInformationSupport` 里：**所有未被自定义片段声明、且不是 `Object` 方法的方法**都算查询方法候选：

【源码证据】`repository/core/RepositoryInformationSupport.java` 第 63、143 行：

```java
	public List<Method> getQueryMethods() {          // L63

	public boolean hasQueryMethods() {               // L143
```

默认实现 `DefaultRepositoryInformation`（`repository/core/support/`）把泛型对解析、方法匹配与"基类方法回写"（`isBaseClassMethod`，第 104 行）全部落地。运行期，工厂对这套元数据做了**进程级缓存**（见 2.6 节 `RepositoryInformationCacheKey`），同一接口的元数据只解析一次。

## 2.5 RepositoryFactoryBeanSupport：FactoryBean 模式接入 IoC

**为什么要经过 FactoryBean？** 因为要注册进容器的对象是"代理"而不是普通类：容器启动时只知道接口名，代理的类型、行为要到 `afterPropertiesSet()` 才能确定。FactoryBean 模式（Spring 框架第七章的经典口子）正好兜住这种"造出来的对象"。

【源码证据】`repository/core/support/RepositoryFactoryBeanSupport.java` 第 102-106 行——构造时必须指明仓库接口：

```java
	protected RepositoryFactoryBeanSupport(Class<? extends T> repositoryInterface) {

		Assert.notNull(repositoryInterface, "Repository interface must not be null");
		this.repositoryInterface = repositoryInterface;
	}
```

它的 `afterPropertiesSet()`（Bean 生命周期回调）就是"仓库诞生地"，六步清晰地写在源码里：

【源码证据】`RepositoryFactoryBeanSupport.java` 第 324-372 行：

```java
	@Override
	public void afterPropertiesSet() {

		this.factory = createRepositoryFactory();                    // ① 子类创建存储专属工厂
		this.factory.setExposeMetadata(exposeMetadata);
		this.factory.setQueryLookupStrategyKey(queryLookupStrategyKey);
		this.factory.setNamedQueries(namedQueries);
		this.factory.setEvaluationContextProvider(...);              // ② 委托工厂配置
		this.factory.setBeanClassLoader(classLoader);
		……
		if (this.publisher != null) {
			this.factory.addRepositoryProxyPostProcessor(
				new EventPublishingRepositoryProxyPostProcessor(publisher)); // ③ 挂事件发布钩子
		}
		……
		this.repositoryFactoryCustomizers.forEach(customizer -> customizer.customize(this.factory));

		RepositoryMetadata metadata = this.factory.getRepositoryMetadata(repositoryInterface); // ④ 元数据
		RepositoryFragments repositoryFragments = getRepositoryFragments(metadata);            // ⑤ 片段

		this.cachedFragments = repositoryFragments;
		this.repositoryMetadata = metadata;
		this.repository = Lazy.of(() -> getRequiredFactory().getRepository(repositoryInterface, repositoryFragments));

		if (this.mappingContext != null) {
			this.mappingContext.getPersistentEntity(repositoryMetadata.getDomainType()); // ⑥ 预热映射上下文
		}

		if (!lazyInit) {
			this.repository.get();                                   // 默认立即实例化代理
		}
	}
```

第 ⑥ 步有注释直言目的（第 363 行）：`// Make sure the aggregate root type is present in the MappingContext (e.g. for auditing)`——仓库创建顺手把实体元数据预热好，审计等能力开局可用。而 `getObject()` 只是把缓存的 `Lazy<T>` 交出去（第 297-302 行），`getObjectType()` 返回接口本身（第 305-307 行）——容器做类型匹配时看到的就是 `UserRepository`。

> **4.0 提示**：本类新增了 `setRepositoryFragmentsFunction(RepositoryFragmentsFunction)`（第 174 行，`@since 4.0`），片段来源从"单个列表"升级为"可多次叠加的函数"，为各存储模块向仓库注入附加片段（如 JPA 的 QueryDSL 支持）留出统一口子。

## 2.6 RepositoryFactorySupport：代理装配全流程（本章核心）

`RepositoryFactorySupport` 是所有存储模块工厂（`JpaRepositoryFactory`、`MongoRepositoryFactory`…）的公共父类，`getRepository()` 一次调用完成元数据→组合→目标→代理四步。

【源码证据】`RepositoryFactorySupport.java` 第 308-429 行（关键段）：

```java
	public <T> T getRepository(Class<T> repositoryInterface, RepositoryFragments fragments) {   // L308

		……
		RepositoryMetadata metadata = getRepositoryMetadata(repositoryInterface);              // L327 ①

		RepositoryStub stub = getRepositoryStub(metadata, fragments);                          // L334 ②
		RepositoryComposition composition = stub.composition();
		RepositoryInformation information = stub.information();
		……
		Object target = getTargetRepository(information);                                      // L359 ③

		RepositoryComposition compositionToUse = composition.append(RepositoryFragment.implemented(target));
		validate(information, compositionToUse);                                               // L364-365 ④

		// Create proxy
		ProxyFactory result = new ProxyFactory();                                              // L369 ⑤
		result.setTarget(target);
		result.setInterfaces(repositoryInterface, Repository.class, TransactionalProxy.class); // L371
		……
		if (NullnessMethodInvocationValidator.supports(repositoryInterface)) {
			result.addAdvice(new MethodInvocationValidator());                                 // L373-378 空参数校验
		}
		……
		if (!postProcessors.isEmpty()) {
			postProcessors.forEach(processor -> processor.postProcess(result, information));   // L388-400 事务/事件钩子
		}
		……
		result.addAdvice(new QueryExecutorMethodInterceptor(information, getProjectionFactory(),
				queryLookupStrategy.orElse(null), namedQueries, queryPostProcessors,
				methodInvocationListeners));                                                   // L412-414 ⑥ 查询拦截器
		result.addAdvice(new ImplementationMethodExecutionInterceptor(information,
				compositionToUse, methodInvocationListeners));                                 // L416-417 ⑦ 片段路由拦截器

		T repository = (T) result.getProxy(classLoader);                                       // L419
```

逐条拆解：

- **① 元数据**：`AbstractRepositoryMetadata.getMetadata()` 解析泛型对（L465-467）。
- **② 缓存的元数据+组合**：`getRepositoryStub()`（L488-506）以 `RepositoryMetadata + fragments 哈希` 为 key 缓存 `DefaultRepositoryInformation(metadata, baseClass, composition)`——这就是 2.4 节说的缓存。
- **③ 目标实例**：`getTargetRepository()` 是**抽象方法**（L554），各存储模块实现之——JPA 返回 `SimpleJpaRepository` 实例，Mongo 返回 `SimpleMongoRepository`。`getRepositoryBaseClass()`（L563）同样抽象，告诉框架"基类长什么样"，供方法匹配用。反射辅助方法 `getTargetRepositoryViaReflection()`（L596-601）把"构造器参数猜测"从子类手里拿走。
- **④ 校验**：`RepositoryValidator.validate()` 确认接口里每个方法都有人接得住（查询方法能解析或片段可实现），否则启动期报错而不是运行期。
- **⑤ 代理接口三元组**（L371）：仓库接口之外再挂 `Repository.class`（让代理可被识别为仓库）和 `TransactionalProxy.class`——后者来自 **spring-tx**（import 见 L80，`org.springframework.transaction.interceptor.TransactionalProxy`），是 Spring 6.1 引入的标记接口，事务基础设施靠它识别"这是一个事务代理"，不必再反射翻找。
- **⑥ 查询拦截器**：见 2.8 节。
- **⑦ 片段路由拦截器**：`ImplementationMethodExecutionInterceptor`（L671-700）把调用交给 `composition.invoke(...)`（L690），并把 `InvocationTargetException` 解包成真实异常（L693-695）。

两条环境装饰同样重要：

```java
	// L380-386：exposeMetadata 时挂 ExposeMetadataInterceptor——
	// 它把 RepositoryMetadata + 当前 Method 放进 ThreadLocal，供 RepositoryMethodContext 静态获取
	if (this.exposeMetadata || shouldExposeMetadata(fragments)) {
		result.addAdvice(new ExposeMetadataInterceptor(metadata));
		result.addAdvisor(ExposeInvocationInterceptor.ADVISOR);
	}

	// L402-408：接口含 default 方法时挂 DefaultMethodInvokingMethodInterceptor，
	// 让 default 方法按真实 MethodHandle 调用（支持 Kotlin 接口方法等）
```

`ExposeMetadataInterceptor` 的实现（L705-728）就是典型的 ThreadLocal 保存-恢复模式，最终由 `RepositoryMethodContext` 静态读取——这是 3.4 新增的能力（`setExposeMetadata` 的 `@since 3.4` 见 L154）。

最后，**工厂的可插拔点**在类字段一览中集中体现（L111-126）：`repositoryBaseClass`（替换基类）、`queryLookupStrategyKey`（查询策略）、`queryPostProcessors`（查询创建后回调 `QueryCreationListener`）、`methodInvocationListeners`（方法调用监听 `RepositoryMethodInvocationListener`）、`postProcessors`（`RepositoryProxyPostProcessor`，代理创建前的最后修饰口子）。`postProcessors` 列表在代理创建时的调用顺序有明确注释（L254-257）：**先跑用户 PostProcessor，最后才挂查询拦截器**——保证用户有机会在最外层织入逻辑。

## 2.7 Fragment 组合模型：自定义实现如何"混入"代理

### 2.7.1 白话：从"一个实现"到"一叠实现"

1.x 时代，一个仓库只能有一个自定义实现类（`setCustomImplementation`）。真实需求很快升级：多个团队给同一个仓库各加一块能力（缓存片段、审计片段……）。于是 2.0 引入 **Fragment（片段）** 模型：仓库实例 = 一叠 fragment，每个 fragment 声明"我负责哪些方法、由哪个对象实现"。调度者就是 `RepositoryComposition`。

【源码证据】`repository/core/support/RepositoryComposition.java` 第 50-68 行（类 Javadoc）：

```java
 * Composition of {@link RepositoryFragment fragments} that has each fragment contribute repository
 * interface. {@link #withMethodLookup(MethodLookup) MethodLookup} selects the first matching method for
 * invocation. A composition also supports argument conversion between the repository method signature and
 * fragment implementation method through {@link #withArgumentConverter(BiFunction)}. ……
 * Composition objects are immutable and thread-safe.
```

### 2.7.2 组合的构造：元数据感知的 MethodLookup

【源码证据】`RepositoryComposition.java` 第 139-148 行：

```java
	public static RepositoryComposition fromMetadata(RepositoryMetadata metadata) {

		if (metadata.isReactiveRepository()) {
			return new RepositoryComposition(metadata, RepositoryFragments.empty(),
					MethodLookups.forReactiveTypes(metadata), REACTIVE_ARGS_CONVERTER);
		}

		return new RepositoryComposition(metadata, RepositoryFragments.empty(),
				MethodLookups.forRepositoryTypes(metadata), PASSTHRU_ARG_CONVERTER);
	}
```

这里藏着两个精巧设计：① **方法查找策略按命令式/响应式分叉**（`MethodLookups.forRepositoryTypes` / `forReactiveTypes`，`MethodLookups.java` L79-84——响应式场景允许 `Flux` 参数与 `Iterable` 形参互通，direct 匹配优先）；② **参数转换器**（L71-101）：命令式直通（PASSTHRU），响应式则用 `ReactiveWrapperConverters` 把参数在 Reactor/RxJava 包装类型间自动转换。

### 2.7.3 调用路由：按注册序"先到先得"

【源码证据】`RepositoryComposition.java` 第 272-284 行：

```java
	public @Nullable Object invoke(Method method, Object... args) throws Throwable {
		return invoke(NoOpRepositoryInvocationMulticaster.INSTANCE, method, args);
	}

	@Nullable
	Object invoke(RepositoryInvocationMulticaster listener, Method method, Object[] args) throws Throwable {

		Method methodToCall = getMethod(method);      // 用 MethodLookup 找到第一个能接单的 fragment 方法
		……
```

`getMethod` 按 lookups 列表逐个尝试，**第一个匹配者胜出**。这就是"自定义实现优先于基类实现"的机制根源：`getRepository()` 在挂载顺序上把用户片段排在前面（`composition.append(fragments).append(repositoryAspects)`，L499），最后再 append 基类 target（L364）。

### 2.7.4 片段的"声明面"与"实现面"

`RepositoryFragment` 把每个片段拆成两面：`getSignatureContributor()`（声明了哪些接口方法）与 `getImplementation()`（由谁实现）。两者可以只有一面——只有实现没有签名的片段不影响方法查找，只是"实现共享池"。校验器据此报两类错：签名有人认领但没人实现（`FragmentNotImplementedException`），或同一签名被实现两次（`IncompleteRepositoryCompositionException` 所在包的姊妹类 `UnsupportedFragmentException`）——所有校验都在启动期完成（2.6 节 ④）。

> **实践含义**：写自定义实现时，只需创建 `UserRepositoryCustom` 接口 + `UserRepositoryCustomImpl` 类，Commons 的扫描器（11.3 节）会把 Impl 找出来装进 fragment；顺序、查找、校验全部自动。

## 2.8 QueryExecutorMethodInterceptor：查询方法的调度台

代理 advice 链的最内一层是查询拦截器。构造期它就把"方法 → 查询"的映射**一次性建好**（这也是 Spring Data 启动慢的著名来源——每个查询方法都要在启动期解析成可执行查询）：

【源码证据】`repository/core/support/QueryExecutorMethodInterceptor.java` 第 70-92 行：

```java
	public QueryExecutorMethodInterceptor(RepositoryInformation repositoryInformation,
			ProjectionFactory projectionFactory, @Nullable QueryLookupStrategy queryLookupStrategy, ……) {

		……
		if (queryLookupStrategy == null && repositoryInformation.hasQueryMethods()) {
			throw new IllegalStateException(
					"You have defined query methods in the repository but do not have any query lookup strategy defined." +
					" The infrastructure apparently does not support query methods");   // L83-88
		}
		this.queries = queryLookupStrategy != null
				? mapMethodsToQuery(repositoryInformation, queryLookupStrategy, projectionFactory)
				: Collections.emptyMap();                                               // L89-91
	}
```

`mapMethodsToQuery`（L94-109）对每个查询方法调用 `strategy.resolveQuery(...)`（L114），解析失败统一包装成 `QueryCreationException` 并带上"接口 + 方法"定位（L117-119）；成功后触发 `QueryCreationListener` 回调（L123-134，按监听器泛型匹配后转发）。运行期 `invoke` 很薄：

【源码证据】`QueryExecutorMethodInterceptor.java` 第 136-172 行：

```java
	@Override
	public @Nullable Object invoke(MethodInvocation invocation) throws Throwable {

		Method method = invocation.getMethod();
		MethodParameter returnType = returnTypeMap.computeIfAbsent(method, it -> new MethodParameter(it, -1));

		QueryExecutionConverters.ExecutionAdapter executionAdapter =
				QueryExecutionConverters.getExecutionAdapter(returnType.getParameterType());

		if (executionAdapter == null) {
			return resultHandler.postProcessInvocationResult(doInvoke(invocation), returnType);
		}

		return executionAdapter.apply(() -> resultHandler.postProcessInvocationResult(doInvoke(invocation), returnType));
	}
```

两个关键点：① `QueryExecutionConverters`（`org.springframework.data.util`，仓库级工具）识别"包装型返回值"（`Optional`/`Future`/`Flux`/`Stream`…），executionAdapter 负责把底层结果适配成声明类型；② `QueryExecutionResultHandler.postProcessInvocationResult`（`QueryExecutionResultHandler.java` L85、L132）再做一次**投影/类型收尾转换**——把查询结果转成接口声明的投影类型。真正的执行在 `doInvoke` 里经 `RepositoryMethodInvoker` 发生（L154-172），它会包上方法调用监听器（计时、Metrics 钩子）。

## 2.9 本章小结

本章把"空接口为什么能工作"还原成一条可复现的流水线：

| 环节 | 类 | 一句话 |
|---|---|---|
| 发现与登记 | `RepositoryConfigurationDelegate`（11.1 节） | 扫描 `extends Repository` 的接口 → 注册 FactoryBean |
| 接线容器 | `RepositoryFactoryBeanSupport` | `afterPropertiesSet()` 建工厂、攒片段、按需饿汉初始化 |
| 元数据 | `RepositoryMetadata`/`RepositoryInformation` | 泛型对解析 + 方法归属判定，进程级缓存 |
| 造物 | `RepositoryFactorySupport#getRepository` | 组合校验 → 目标基类 → ProxyFactory 七连挂 |
| 路由 | `RepositoryComposition` + `MethodLookups` | 片段按序接单，自定义实现先于基类 |
| 查询 | `QueryExecutorMethodInterceptor` | 启动期方法→查询映射，运行期薄调度 + 结果转换 |

下一章进入整条流水线上最"魔法"的部分：方法名是怎么变成查询的。


---

# 三、查询方法体系：从方法名到查询（repository.query + query.parser）

> 本章对应源码：`org.springframework.data.repository.query` 与 `org.springframework.data.repository.query.parser` 两个包。

## 3.1 先说白话：`findByNameAndAgeLessThan` 的旅程

你写下方法名的那一刻，一个四层流水线已经开始运转：

1. **QueryMethod**（3.2 节）：给方法建一张"元数据卡"——返回类型、参数表、是分页还是切片；
2. **QueryLookupStrategy**（3.3 节）：按配置决定"从哪找查询"——创建式（解析方法名）还是声明式（@Query/命名查询）；
3. **PartTree**（3.4 节）：把方法名当一门"小语言"解析成条件树；
4. **AbstractQueryCreator**（3.5 节）：各存储模块拿到条件树，翻译成 JPQL/Criteria/MongoDB Query……
5. 运行期由 **Parameters/ParameterAccessor**（3.6 节）绑定实参、**ValueExpressionDelegate**（3.7 节）求值 SpEL 表达式。

整条流水线在**启动期**跑完（2.8 节的 `mapMethodsToQuery`），运行期只剩参数绑定与执行——这是 Spring Data 启动慢但调用快的根源。

## 3.2 QueryMethod：方法的"元数据卡"

【源码证据】`repository/query/QueryMethod.java` 第 60-70 行：

```java
public class QueryMethod {

	private final Method method;
	private final RepositoryMetadata metadata;
	private final @Nullable ProjectionFactory factory;
	……
```

构造器（L82-128）做的三件事值得点名：

- 校验返回类型/参数合法性（不合法直接抛 `IllegalStateException`，让错误在启动期暴露）；
- 创建 **Parameters** 参数表（L114，`createParameters` L221）——第 3.6 节的主角；
- 用 `Lazy` 惰性计算 `isCollectionQuery`（L68、L128），因为该判定要解析泛型，代价不小。

对外输出的判定族（每个存储模块的查询创建器都会问）：

【源码证据】`QueryMethod.java` 第 271-301 行：

```java
	public boolean isCollectionQuery() {…}   // L271
	public boolean isScrollQuery() {…}       // L281  Scroll API 查询（3.1+）
	public boolean isSliceQuery() {…}        // L291  !page 且 Slice 子类
	public final boolean isPageQuery() {…}   // L300  Page 子类（连带要求参数有 Pageable）
```

另一个常用出口是**命名查询名**——约定为"实体简名.方法名"：

【源码证据】`QueryMethod.java` 第 244-245 行：

```java
	public String getNamedQueryName() {
		return String.format("%s.%s", getDomainClass().getSimpleName(), method.getName());
	}
```

（`User.findByEmail` 这类键会在 XML/properties 命名查询里被 `NamedQueries` 查表命中——声明式查询的归属，见 3.3 节。）

## 3.3 QueryLookupStrategy：查询的"查找策略"

策略接口只有一个方法，外加三种策略枚举——对应 `@EnableJpaRepositories(queryLookupStrategy = …)` 的三个取值：

【源码证据】`repository/query/QueryLookupStrategy.java` 第 33-68 行：

```java
public interface QueryLookupStrategy {

	enum Key {
		CREATE, USE_DECLARED_QUERY, CREATE_IF_NOT_FOUND;   // L40
		……
	}

	RepositoryQuery resolveQuery(Method method, RepositoryMetadata metadata,
			ProjectionFactory factory, NamedQueries namedQueries);   // L67-68
}
```

三种策略的行为（各存储模块提供 `DefaultJpaQueryLookupStrategy` 等实现）：

| 策略 | 行为 |
|---|---|
| `CREATE` | 一律按方法名解析（PartTree），忽略注解/命名查询 |
| `USE_DECLARED_QUERY` | 只找声明式查询（@Query / named query），找不到直接抛异常 |
| `CREATE_IF_NOT_FOUND` | **默认**：先找声明式，没有再按方法名创建 |

产出物是 `RepositoryQuery`——"一个可执行的查询"的抽象（`execute(Object[] parameters)` + `getQueryMethod()`）。谁持有 `QueryLookupStrategy`？2.6 节的 `getRepository()` 中 `getQueryLookupStrategy(queryLookupStrategyKey, getValueExpressionDelegate())`（L410-411）——工厂把策略、表达式委托一路传到代理装配现场。注意基类默认返回 `Optional.empty()`（L573-576）：**不支持查询方法的存储（如纯 CRUD 扩展）可以完全不提供策略**，此时若接口里还声明了查询方法，启动期就会撞上 2.8 节那段 `IllegalStateException`。

## 3.4 PartTree：方法名解析器（Commons 的"编译器前端"）

### 3.4.1 主干：前缀正则与 Subject/Predicate

【源码证据】`repository/query/parser/PartTree.java` 第 61-67 行——全部魔法始于四个常量：

```java
	private static final String KEYWORD_TEMPLATE = "(%s)(?=(\\p{Lu}|\\P{InBASIC_LATIN}))";
	private static final String QUERY_PATTERN = "find|read|get|query|search|stream";
	private static final String COUNT_PATTERN = "count";
	private static final String EXISTS_PATTERN = "exists";
	private static final String DELETE_PATTERN = "delete|remove";
	private static final Pattern PREFIX_TEMPLATE = Pattern.compile( //
			"^(" + QUERY_PATTERN + "|" + COUNT_PATTERN + "|" + EXISTS_PATTERN + "|" + DELETE_PATTERN
					+ ")((\\p{Lu}.*?))??By");
```

前缀正则读作："以 find/read/get/query/search/stream/count/exists/delete|remove 开头，中间可夹一段大写开头的描述词（如 `DistinctTop10By`），然后必须出现 `By`"。`KEYWORD_TEMPLATE` 则规定**关键词后面必须跟大写字母（或非基本拉丁字母，如中文）**才算命中——这是"方法名里的 `And` 是关键词，而 `android` 是属性名"的区分机制。

构造器把方法名劈成两半：

【源码证据】`PartTree.java` 第 86-106 行：

```java
	public PartTree(String source, Class<?> domainClass) {

		Assert.notNull(source, "Source must not be null");
		Assert.notNull(domainClass, "Domain class must not be null");

		// Kotlin name mangling, @JvmName cannot be used with interfaces
		int dash = source.indexOf('-');
		if (dash > -1) {
			source = source.substring(0, dash);       // 剥掉 Kotlin 的 -hash 后缀
		}

		Matcher matcher = PREFIX_TEMPLATE.matcher(source);

		if (!matcher.find()) {
			this.subject = new Subject(Optional.empty());
			this.predicate = new Predicate(source, domainClass);
		} else {
			this.subject = new Subject(Optional.of(matcher.group(0)));                  // find…By
			this.predicate = new Predicate(source.substring(matcher.group().length()),  // By 之后
					domainClass);
		}
	}
```

以 `findDistinctTop10UsersByLastnameIgnoreCaseOrderByFirstnameDesc` 为例：**Subject = `findDistinctTop10UsersBy`**（去重吗？前 10 条吗？返回什么投影？），**Predicate = `LastnameIgnoreCaseOrderByFirstnameDesc`**（条件 + 排序）。Javadoc 里举的例子（L70-77）正是这个格式。

Subject 解析出的布尔/数量结果以一组 getter 暴露（历史沿革清晰可见）：

【源码证据】`PartTree.java` 第 118-188 行（节选）：

```java
	public Sort getSort() { return predicate.getOrderBySource().toSort(); }   // L118-119
	public boolean isDistinct() {…}        // L127
	public boolean isCountProjection() {…} // L136
	public boolean isExistsProjection() {…}// L146  @since 1.13
	public boolean isDelete() {…}          // L156  @since 1.8
	public boolean isLimiting() {…}        // L166  @since 1.9（findTop10 / findFirst3）
	public @Nullable Integer getMaxResults() {…}  // L176
	public Limit getResultLimit() {…}      // L186  @since 3.2（配合 Limit 参数类型）
```

### 3.4.2 谓词树：OrPart 与 Part

Predicate 的解析规则（L371-382 起的内部类）：先按 `OrderBy` 切掉排序尾巴，剩下的字符串按**大小写敏感的 `Or`** 切成若干 `OrPart`，每个 OrPart 内再按 `And` 切成若干 `Part`——`PartTree` 因此是一棵两层树：**OrPart 之间 OR，Part 之间 AND**。`toString()`（L219-223）把它打印成 `lastname = ?1 or firstname = ?2` 的可读形态。

每个 Part 落到一个"关键字类型"上。完整的类型表（关键词 → 语义）是所有存储翻译器的公共词汇表：

【源码证据】`repository/query/parser/Part.java` 第 175-210 行（节选）：

```java
	public enum Type {

		BETWEEN(2, "IsBetween", "Between"),               // L177（两参数）
		IS_NOT_NULL(0, "IsNotNull", "NotNull"),           // L178（零参数）
		……
		GREATER_THAN("IsGreaterThan", "GreaterThan"),     // L182
		LESS_THAN("IsLessThan", "LessThan"),              // L180
		BEFORE("IsBefore", "Before"),                     // L184
		AFTER("IsAfter", "After"),                        // L185
		NOT_LIKE("IsNotLike", "NotLike"),                 // L186
		LIKE("IsLike", "Like"),                           // L187
		NOT_CONTAINING("IsNotContaining", "NotContaining", "NotContains"),  // L192
		CONTAINING("IsContaining", "Containing", "Contains"),               // L193
		NOT_IN("IsNotIn", "NotIn"),                       // L194
		IN("IsIn", "In"),                                 // L195
		NEAR("IsNear", "Near"), WITHIN("IsWithin", "Within"),   // 地理，geo 包联动
		REGEX("MatchesRegex", "Matches", "Regex"),
		EXISTS("Exists"),
		TRUE("IsTrue", "True"), FALSE("IsFalse", "False"),
		NEGATING_SIMPLE_PROPERTY("IsNot", "Not"),          // L202
		SIMPLE_PROPERTY("Is", "Equals");                   // L203

		// L207-210：ALL 列表固定了关键词的**优先匹配顺序**——
		// 更长的关键词先试（"IsNot" 必须先于 "Is" 被识别）
		private static final List<Part.Type> ALL = List.of(IS_NOT_NULL, IS_NULL, BETWEEN, LESS_THAN, …);
```

注意 `BETWEEN(2, …)` / `IS_NOT_NULL(0, …)` 的第一参：**关键字声明的参数个数**——`findByAgeBetween(18, 30)` 要吃两个实参，解析器据此推进参数游标（这正是 3.6 节 ParameterAccessor 的按序消费语义）。

### 3.4.3 最后一公里：AbstractQueryCreator

PartTree 是存储无关的；把它翻译成具体查询是每个存储模块的事，公共骨架就是模板方法的"条件累加器"：

【源码证据】`repository/query/parser/AbstractQueryCreator.java` 第 39、81-93、138-166 行：

```java
public abstract class AbstractQueryCreator<T, S> {

	public AbstractQueryCreator(PartTree tree) {…}                    // L51
	public AbstractQueryCreator(PartTree tree, ParameterAccessor parameters) {…}  // L63

	public T createQuery() {…}                                        // L81
	public T createQuery(Sort dynamicSort) {…}                        // L93

	protected abstract S create(Part part, Iterator<Object> iterator);          // L138
	protected abstract S and(Part part, S base, Iterator<Object> iterator);     // L148
	protected abstract S or(S base, S criteria);                                // L157
	protected abstract T complete(@Nullable S criteria, Sort sort);             // L166
```

四个抽象方法就是"每个 Part 怎么变谓词片段、怎么 AND/OR 起来、最后怎么收尾成完整查询"——JPA 用 Criteria API 实现，Mongo 用 `Criteria`/`Query` 对象实现，Redis 用……总之**解析只发生一次，翻译各有方言**。

## 3.5 Parameters：参数表与"特殊参数"

方法名谓词之外，方法签名还可能混进分页、排序、限制等"结构性参数"。Commons 把它们列为固定白名单：

【源码证据】`repository/query/Parameters.java` 第 46-55 行：

```java
public abstract class Parameters<S extends Parameters<S, T>, T extends Parameter>
		implements Streamable<T> {

	public static final List<Class<?>> TYPES = List.of(ScrollPosition.class, Pageable.class, Sort.class,
			Limit.class);

	private static final List<String> TYPES_ALLOWING_SPATIAL = List.of(
			Param.class.getSimpleName(), Pageable.class.getSimpleName(), Sort.class.getSimpleName());
	……
```

（`TYPES` 里的 `ScrollPosition` 是 3.1 Scroll API 加入的，第四章展开。）运行期，`ParametersParameterAccessor` 按位绑定实参：Part.Type 声明的参数从前往后消费，`Pageable/Sort/Limit/ScrollPosition` 则按类型识别——这就是"为什么参数顺序不影响 `Pageable` 的位置，但影响普通谓词参数"。

## 3.6 表达式求值：@Query 里的 SpEL 与 ValueExpression 体系

@Query 注解查询在各存储模块定义，但**注解里的 SpEL 表达式求值是 Commons 的事**。这条线在 3.3（`ValueExpressionParser`）与 4.0（表达式重构）间发生过一次大迁移：

- **旧体系（≤3.5）**：`SpelQueryContext` + `SpelEvaluator` + `QueryMethodEvaluationContextProvider` —— 全部是 SpEL 专属类型；
- **新体系（4.0）**：统一收敛到 `ValueExpressionDelegate`。

【源码证据】`repository/query/ValueExpressionDelegate.java` 第 31-40 行：

```java
public class ValueExpressionDelegate implements ValueExpressionParser {

	private final ValueExpressionParser parser;
	private final QueryMethodValueEvaluationContextAccessor accessor;
	……
```

它把两件事合而为一：**表达式解析**（`parse()`，来自 `org.springframework.data.expression.ValueExpressionParser`，L28/66）与**求值上下文装配**（`QueryMethodValueEvaluationContextAccessor`——把 Environment、BeanFactory、SpEL 扩展（`spel` 包的 `EvaluationContextProvider`）打包进上下文）。工厂侧的用法已在 2.6 节见过（L431-434：`new ValueExpressionDelegate(new QueryMethodValueEvaluationContextAccessor(getEnvironment(), evaluationContextProvider), VALUE_PARSER)`）。

`org.springframework.data.expression` 包还提供了比 SpEL 更轻的表达式形态：`LiteralValueExpression`（纯字面量，不求值）、`CompositeValueExpression`、`PlaceholderExpression`（`${…}` 占位）——@Query 里写 `#{…}` 还是 `${…}`，走的是不同实现。

## 3.7 Query by Example 与 FluentQuery（速览）

**QBE（Query by Example）**：拿一个"探针"对象 + 匹配器描述查询，全无字符串：

【源码证据】`domain/Example.java` 第 30-49 行、`repository/query/QueryByExampleExecutor.java` 第 38-79 行（节选）：

```java
public interface Example<T> {
	static <T> Example<T> of(T probe) {…}                    // Example.java L38
	static <T> Example<T> of(T probe, ExampleMatcher matcher) {…}  // L49
}

public interface QueryByExampleExecutor<T> {
	<S extends T> Iterable<S> findAll(Example<S> example);         // L56
	<S extends T> Page<S> findAll(Example<S> example, Pageable pageable);  // L79
```

`ExampleMatcher`（`domain/ExampleMatcher.java` L49）用链式 API 描述"哪些字段忽略、字符串怎么匹配（contains/startsWith/regex…）、null 怎么处理"；`FluentQuery`（`repository/query/FluentQuery.java` L48）则是 2.7 加入的流式查询面（`sortBy/limit/project/all()/firstValue()…`，L60/73/99），供 `findBy(Example)` 这类方法返回"查询构造器"而不是裸结果——把查询的"最后一公里"延迟到用户点下 `all()` 的那一刻。

## 3.8 本章小结

| 问题 | 答案 |
|---|---|
| 方法名谁能解析？ | PartTree（正则切前缀 → Subject/Predicate → OrPart/Part 两层树） |
| 关键词谁定义？ | `Part.Type` 枚举（含参数个数与优先序） |
| 谁翻译成 SQL/文档查询？ | 各存储的 `AbstractQueryCreator` 子类（模板方法四钩子） |
| @Query/named query 谁管？ | `QueryLookupStrategy`（三策略）+ `NamedQueries` 查表 |
| SpEL 谁求值？ | 4.0 起统一为 `ValueExpressionDelegate`（旧 SpelEvaluator 家族已删） |
| 错误什么时候暴露？ | 启动期——查询解析、参数合法性、片段校验全部提前 |


---

# 四、分页、排序与游标（domain 包）

> 本章对应源码：`org.springframework.data.domain`（+ `org.springframework.data.support` 两个执行工具）。

## 4.1 白话：为什么需要"可携带的查询形状"

"第 2 页每页 20 条按创建时间倒序"这种查询形状（shape），在不同存储里写法千差万别（LIMIT/OFFSET、$skip/$limit、RowNumber…）。Spring Data 把形状抽成值对象让**接口签名与存储方言解耦**：仓库方法只认 `Pageable`，方言转换交给各模块。

## 4.2 分页与排序家族

【源码证据】核心类型的声明行（`domain/` 包实测）：

```java
public interface Pageable {                     // Pageable.java L31
	int getPageNumber();
	int getPageSize();
	long getOffset();
	Sort getSort();
	boolean isPaged();                          // Unpaged 场景下 false
	boolean isUnpaged();
	boolean hasPrevious();
	Pageable next() / previousOrFirst() / first();
}

public class PageRequest extends AbstractPageRequest {   // PageRequest.java L35
	public static PageRequest of(int page, int size) {…}
	public static PageRequest of(int page, int size, Sort sort) {…}
	public static PageRequest ofSize(int pageSize) {…}
}

public abstract class AbstractPageRequest implements Pageable, Serializable {  // L32

public class Sort implements Streamable<Sort.Order>, Serializable {            // Sort.java L53
	public static final Direction DEFAULT_DIRECTION = Direction.ASC;
	public static Sort by(String... properties) {…}     // by(String)/by(Order)/by(List<Order>)
}
```

家族关系（一张图记住）：

```
Pageable（分页请求）
└── AbstractPageRequest（page/size 校验）
    └── PageRequest（值对象工厂：of/ofSize）
        （特殊值 Unpaged：只排序不分页）

Streamable<T>
├── Slice<T>（Slice.java L31：知道有没有下一页，不数总数）
│   └── Page<T>（Page.java L28：多了 totalElements/totalPages）
│       └── PageImpl / GeoPage（实现）
│   Chunk<T>（Slice 的骨架实现，L37）
```

**`Slice` 与 `Page` 的分界是面试与生产都高频的点**：`Slice` 只多做一次 `limit+1` 查询判断"还有没有下一页"；`Page` 要额外发 count 语句。当结果集大、总数没必要时用 `Slice` 省掉 count。

### 4.2.1 总数计算的官方优化：PageableExecutionUtils

count 语句能不能不发？能——条件满足"结果不满一页"时，总数可以直接由内容推出：

【源码证据】`support/PageableExecutionUtils.java` 第 37-75 行（核心逻辑）：

```java
public abstract class PageableExecutionUtils {

	public static <T> Page<T> getPage(List<T> content, Pageable pageable, LongSupplier totalSupplier) {  // L52

		Assert.notNull(content, "Content must not be null");
		……
		if (!pageable.isPaged() || !content.hasContent()) ……
		// 当前页内容数量 < 页大小 ⇒ 没有下一页 ⇒ 总数 = offset + content.size()
		if (pageable.getPageSize() > content.size()) {          // L75
			return new PageImpl<>(content, pageable, pageable.getOffset() + content.size());
		}

		return new PageImpl<>(content, pageable, totalSupplier.getAsLong());  // 否则才调 count
	}
```

这就是 JPA/Mongo 等模块 `PageImpl` 工厂背后的公共"省 count"算法——首页满页才付出 count 代价。

## 4.3 Scroll API：游标分页（3.1 新增，git 标签实证）

`Page` 有个结构性缺陷：**深分页**（OFFSET 100000）在数据库侧要扫描并丢弃前 10 万行。Scroll API（1.6.2 节实证：`Window` 于 3.1.0 首次出现）提供两种游标：

【源码证据】`domain/ScrollPosition.java` 第 27-91 行（接口与工厂方法）：

```java
public interface ScrollPosition {

	boolean isInitial();                                     // L34

	static KeysetScrollPosition keyset() { return KeysetScrollPosition.initial(); }   // 键集游标
	static OffsetScrollPosition offset() { return OffsetScrollPosition.initial(); }   // 偏移游标
	static OffsetScrollPosition offset(long offset) {…}     // 直接跳转到某偏移
	static KeysetScrollPosition forward(Map<String, ?> keys) {…}   // 用"上一批的排序键"继续向前
	static KeysetScrollPosition backward(Map<String, ?> keys) {…}
}
```

两个实现类（`KeysetScrollPosition.java` L36、`OffsetScrollPosition.java` L37）分别是"**按最后一批记录的排序键值定位**"（keyset：`WHERE (a,b) > (:lastA,:lastB) ORDER BY a,b LIMIT n`——每次都是索引 seek）与"传统 OFFSET"。消费面：

【源码证据】`domain/Window.java` 第 36-56 行（节选）：

```java
public interface Window<T> extends Streamable<T> {

	List<T> getContent();
	ScrollPosition positionAt(int index);       // 记录每条内容的位置，天然支持"从任意一条继续"
	Window<T> reverse();
	boolean isEmpty();
}
```

**关键交互**：仓库方法签名 `Window<User> findFirst100ByOrderByLastnameAsc(ScrollPosition position)`——上一步 `window.positionAt(n)` 返回的游标直接喂回下一步。想用迭代器风格滚完全库，`support` 包备好了驱动器：

【源码证据】`support/WindowIterator.java` 第 49-66 行：

```java
public class WindowIterator<T> implements Iterator<T> {

	public static <T> WindowIteratorBuilder<T> of(Function<ScrollPosition, Window<T>> windowFunction) {…}  // L66
```

`WindowIterator.of(repo::findFirst100ByOrderByLastnameAsc).startingAt(ScrollPosition.keyset()).toIterator()` 即可流式遍历百万行——每步都是索引 seek，无 OFFSET 退化。

4.1 又给 Sort 补上 lambda 版属性路径（`Sort.by(TypedPropertyPath)`，Sort.java 的 `by(TypedPropertyPath...)` 已在 4.0→4.1 diff 中实证新增），把"排序属性写错字符串"提升为编译期错误。

## 4.4 Limit：方法名之外的第三种限流（3.2 新增）

方法名限流（`findTop10By…`）是"写死在名字里"的。3.2 起可以把上限做成参数：

```java
List<User> findByLastname(String lastname, Limit limit);   // Limit.of(10) / Limit.unlimited()
```

【源码证据】`domain/Limit.java` 第 48-58 行：

```java
	static Limit unlimited() {…}   // L48
	static Limit of(int max) {…}   // L58
```

它同时被纳入 `Parameters.TYPES` 白名单（3.5 节）与 PartTree 的 `getResultLimit()`（`@since 3.2`，PartTree.java L186）——方法名与参数两条限流通道在执行器里合并。

## 4.5 本章小结

| 需求 | 用什么 | 底层代价 |
|---|---|---|
| 精确总数 + 总页数 | `Page<T>` + `Pageable` | 一次 count（首次满页后由 PageableExecutionUtils 优化） |
| 只要"还有没有下一页" | `Slice<T>` | limit+1 |
| 全量/超大结果流式消费 | `Window<T>` + `ScrollPosition`（keyset） | 索引 seek，每批一次 |
| 动态上限 | `Limit` 参数 | 与 findTopN 同价 |
| 只排序不分页 | `Sort` 参数 / `Unpaged` | 无 |

domain 包这组值对象被 Spring 生态广泛借用（Spring Data REST、Pageable 参数解析器、社区库返回 `Page`），它早已超出"Data 家族内部约定"，成为 Java 分页的事实标准之一。


---

# 五、映射元数据（mapping + core.TypeInformation）

> 本章对应源码：`org.springframework.data.mapping`（接口）、`mapping.context`（MappingContext）、`mapping.model`（模型实现与实例化）、`org.springframework.data.core`（TypeInformation/PropertyPath）、`org.springframework.data.annotation`（通用注解）。

## 5.1 先说白话：没有 ORM 注解，框架怎么知道你的实体？

JPA 实体靠 `@Entity`/`@Column`，Mongo 文档靠 `@Document`/`@Field`……但"这个字段是 ID""这个字段该忽略""用哪个构造器实例化""字段名转成列名怎么转"这些**存储无关**的常识，Commons 必须自己先有一套答案——否则每个模块都要重写一遍反射逻辑。这套答案就是：**TypeInformation（泛型情报）+ PersistentEntity/PersistentProperty（实体与属性的户口本）+ MappingContext（户口本缓存仓库）**。各存储模块在这套元数据上"加菜"（JPA 加 @Column 语义、Mongo 加 @Document 语义），而不必"另起炉灶"。

## 5.2 TypeInformation：驯服泛型擦除

`Page<User>` 反射后只剩 `Page`；想创建一个 `List<Address>` 属性的实例、想知道 `Map<String, List<Order>>` 里 value 的元素类型，就必须绕开擦除。`org.springframework.data.core.TypeInformation` 是全家族共享的"泛型情报站"：

【源码证据】`core/TypeInformation.java` 第 36-44、138、279 行：

```java
 * you can traverse properties using {@link #getProperty(String)} to access type information.
public interface TypeInformation<S> {

	Class<S> getType();
	TypeInformation<?> getProperty(String property);   // L138：沿属性路径下钻，边走边收集泛型
	……
	default TypeInformation<?> getRequiredActualType() {…}  // L279：剥掉 Optional/集合包装后的元素类型
```

`getProperty("user.addresses.city")` 链式下钻时每一步都返回**携带该位置泛型的** TypeInformation——这正是 3.4 节 PartTree 能校验"属性路径合法"（配合 `core/PropertyPath`）、Mongo/JPA 能正确处理集合嵌套映射的底层原因。历史注记：该类 4.0 前住在 `org.springframework.data.mapping`，4.0 整编入 `core` 包（1.6.2 节 diff 实证）。

## 5.3 三件套：MappingContext / PersistentEntity / PersistentProperty

### 5.3.1 PersistentEntity：实体的"户口本"

【源码证据】`mapping/PersistentEntity.java` 第 101-389 行（节选关键能力）：

```java
	default P getRequiredIdProperty() {…}        // L101：ID 属性（无 ID 抛 MappingException）
	default P getRequiredVersionProperty() {…}   // L129：乐观锁版本属性
	default P getRequiredPersistentProperty(String name) {…}  // L156
	boolean hasIdProperty();                     // L217
	void doWithProperties(PropertyHandler<P> handler);   // L255：遍历可持久化属性
	boolean requiresPropertyPopulation();        // L389：有没有必须填充的属性（无参构造+无属性 ⇒ false）
	<B> PersistentPropertyAccessor<B> getPropertyAccessor(B bean);  // L342：属性读写器
	IdentifierAccessor getIdentifierAccessor(Object bean);          // L360：ID 读写器
```

`doWithProperties` 是所有映射器循环的核心：只回调"可持久化"的属性（@Transient、静态、transient 已被滤掉）。默认实现 `BasicPersistentEntity`（`mapping/model/BasicPersistentEntity.java` L67）内部用 `TreeMap` 存属性（保证按名字稳定排序，构造时可选 Comparator），`addPersistentProperty`（L190）做登记与冲突处理。

### 5.3.2 MappingContext：带缓存的"户籍科"

【源码证据】`mapping/context/MappingContext.java` 第 43-56 行：

```java
public interface MappingContext<E extends PersistentEntity<?, P>, P extends PersistentProperty<P>> {

	Collection<E> getPersistentEntities();
	@Nullable E getPersistentEntity(Class<?> type);
	default E getRequiredPersistentEntity(Class<?> type) throws MappingException {…}
	boolean hasPersistentEntityFor(Class<?> type);
	……
```

默认实现 `AbstractMappingContext`（L99）是线程安全 + 惰性 + 可监听的经典样板：

【源码证据】`mapping/context/AbstractMappingContext.java` 第 288-320 行：

```java
	@Nullable
	@Override
	public E getPersistentEntity(TypeInformation<?> type) {

		Assert.notNull(type, "Type must not be null");

		try {
			read.lock();                                     // ① 读锁查缓存
			Optional<E> entity = persistentEntities.get(type);
			if (entity != null) {
				return entity.orElse(null);
			}
		} finally {
			read.unlock();
		}

		if (!shouldCreatePersistentEntityFor(type)) {        // ② 非实体类型：记 NONE 哨兵防重复试探
			try {
				write.lock();
				persistentEntities.put(type, NONE);
			} finally {
				write.unlock();
			}
			return null;
		}

		if (strict) {                                        // ③ 严格模式直接报错
			throw new MappingException("Unknown persistent entity " + type);
		}

		return addPersistentEntity(type).orElse(null);       // ④ 写锁下解析并缓存（L395）
	}
```

三个设计点：① **读写锁**让高并发读（每次 save 都查元数据）几乎无争用；② **NONE 哨兵**把"确定不是实体"（如 String、集合）也缓存起来，避免反复试探；③ **`strict` 模式**（构造器可开）把"未注册类型"从"静默返回 null"变成"抛异常"——供 AOT/静态化场景使用。每注册一个实体还会发布 `MappingContextEvent`（`mapping/context/MappingContextEvent.java`，构造器 L47），8.3 节会看到索引器靠它记账。

### 5.3.3 PersistentProperty 与注解基线

`PersistentProperty` 暴露 `isAssociation()`（关联 vs 值）、`getActualType()`、`isIdProperty()/isVersionProperty()` 等判定；其语义锚点来自 `org.springframework.data.annotation` 的 16 个通用注解（2.2 节包清单实测）：

| 注解 | 作用 |
|---|---|
| `@Id` | 标识属性（各存储可另加自己的 ID 注解，如 @MongoId 元注解它） |
| `@Version` | 乐观锁版本 |
| `@Transient` | 排除出映射 |
| `@AccessType(Type.FIELD/PROPERTY)` | 字段访问 vs getter/setter 访问 |
| `@Persistent` / `@TypeAlias` | 实体标记 / 多态存储的类型别名（6.3 节） |
| `@ReadOnlyProperty` / `@Reference` / `@Immutable` | 只读 / 关联引用 / 不可变（跳过脏检查） |
| `@CreatedBy` `@CreatedDate` `@LastModifiedBy` `@LastModifiedDate` | 审计四件套（第七章） |
| `@PersistenceCreator`（4.0 新形态，见下） | 指定实体创建构造器 |

> **4.0 提示**：1.6.2 节 diff 实证 `annotation/PersistenceConstructor.java` 在 4.0 被移除——创建器发现逻辑改为统一走 `PreferredConstructorDiscoverer`（构造器约定：无参构造优先，Kotlin 默认参数次之，显式注解最后），减少了一个"注解摆设"。

## 5.4 实例化：EntityInstantiators 与 PreferredConstructor

实体可能没有无参构造（Kotlin data class、不可变对象、带业务构造器）。Commons 的答案：先找**首选创建器**，再按需生成**字节码实例化器**。

【源码证据】`mapping/model/EntityInstantiator.java` 第 27-31 行、`EntityInstantiators.java` 第 34-77 行（节选）：

```java
public interface EntityInstantiator {
	<T, E extends PersistentEntity<? extends P, P>, P extends PersistentProperty<P>> T createInstance(
			E entity, ParameterValueProvider<P> provider);
}

public class EntityInstantiators implements EntityInstantiatorSource {
	public EntityInstantiators() {…}                          // L42：默认反射 + Kotlin 特化
	public EntityInstantiators(EntityInstantiator fallback) {…}  // L51
	public EntityInstantiators(Map<Class<?>, EntityInstantiator> customInstantiators) {…}  // L60
```

`createInstance` 的分发规则（`EntityInstantiators` 内部）：按"实体类型 → 特化实例化器"查表，未命中走 fallback。内置两个实现：

- `ReflectionEntityInstantiator`：`PreferredConstructorDiscoverer` 找到 `PreferredConstructor`（首选构造器），用 `PersistentEntityParameterValueProvider`（`mapping/model/`，从实体元数据/SpEL/表达式喂参数）填参后反射构造；
- `ClassGeneratingEntityInstantiator`：运行期生成字节码直构造（不走反射 API），比反射快数倍；Kotlin 场景由 `KotlinClassGeneratingEntityInstantiator` 处理默认参数掩码（`KotlinDefaultMask`）。

`PreferredConstructor`（`mapping/PreferredConstructor.java`）与 `ParameterValueProvider`（`mapping/model/`）组合成"参数从哪来"的策略链：普通字段值、@Value/@SpEL 表达式值（4.0 后走 `ValueExpressionParameterValueProvider` + `ValueExpressionEvaluator`）、上下文值——这就是 @Query 投影 DTO 构造、聚合根重建时"构造器参数也能填"的机关。

## 5.5 属性访问：PersistentPropertyAccessor 与字节码加速

读写属性看似 `field.set()` 一行，Commons 却做成了体系：`PersistentPropertyAccessor`（`mapping/`）统一 getter/setter 与字段直读（`BeanWrapper` / `ClassGeneratingPropertyAccessorFactory`），并支持 `AccessOptions` 声明"访问到 null 的中间属性时怎么办"（置 null、跳过、报错）。`ConvertingPropertyAccessor` 在外面包一层 ConversionService（配合第六章），映射器拿到手的就是"已转换"的读写视图。工厂 `BeanWrapperPropertyAccessorFactory` / `InstantiationAwarePropertyAccessorFactory`（`mapping/model/`）按实体特性选用。

字段名 → 存储名的翻译同样在此包：`FieldNamingStrategy` 接口 + `PropertyNameFieldNamingStrategy`（原名）、`SnakeCaseFieldNamingStrategy`（下划线）、`CamelCaseAbbreviatingFieldNamingStrategy`（首字母缩写，对应 Spring Data MongoDB 的 "abbreviated field names" 特性）。各存储模块在 `@EnableXxx` 配置里挑选（11.1 节的 postProcess 口子）。

## 5.6 本章小结

| 问题 | 答案 |
|---|---|
| 泛型实体怎么内省？ | `TypeInformation`（沿属性路径携带泛型下钻） |
| 实体元数据谁缓存？ | `MappingContext`（读写锁 + NONE 哨兵 + 事件） |
| ID/版本/可持久化属性怎么识别？ | `PersistentEntity` + `annotation` 包通用注解 |
| 无无参构造怎么实例化？ | `PreferredConstructor` + 反射/字节码双实例化器 |
| 字段访问策略？ | `PersistentPropertyAccessor` + @AccessType + AccessOptions |
| 属性名怎么映射？ | `FieldNamingStrategy` 族 |

元数据的终点是"读写转换"——下一章看值转换与投影。


---

# 六、类型转换与投影（convert / projection）

> 本章对应源码：`org.springframework.data.convert`、`org.springframework.data.projection`。

## 6.1 CustomConversions：各存储的"类型字典"

【源码证据】`convert/CustomConversions.java` 第 71、241-246、396 行：

```java
public class CustomConversions {

	……
	public void registerConvertersIn(ConverterRegistry conversionService) {   // L241

		CustomCollections.registerConvertersIn(conversionService);            // L246
		……
	}

	public Optional<Class<?>> getCustomWriteTarget(Class<?> sourceType) {…}   // L396
	public boolean isSimpleType(Class<?> type) {…}                            // L228
```

它统一回答三个问题：① **什么算"简单类型"**（`SimpleTypeHolder`，模块可声明自家简单类型，如 Mongo 的 ObjectId）——映射器遇简单类型直接存、遇复合类型递归映射；② **写库前怎么转**（`getCustomWriteTarget`，如 `LocalDateTime → Date`）；③ **读库后怎么转回**。构造时传入的 `Converter` 集合会被 `registerConvertersIn` 全量灌进 ConversionService。内置字典 `Jsr310Converters`（L47 起）就提供 `LocalDateTimeToDateConverter` 等十几个 JSR-310 桥接转换器（L61、L109）。

`ConverterBuilder`（`convert/`）用类型安全的 DSL 声明转换对：`converter(String.class, Email.class).reading(…).writing(…)`——各存储的 CustomConversions 子类（如 `JpaCustomConversions`）全靠它装配。

## 6.2 PropertyValueConverter：属性级读写转换（2.7 新增）

6.1 的转换粒度是"类型对"；2.7 加入**属性粒度**——同一个 `String` 类型，`User.email` 要加密、`User.name` 不用：

【源码证据】`convert/PropertyValueConverter.java` 第 44-61 行：

```java
public interface PropertyValueConverter<DV, SV, C extends ValueConversionContext<? extends PersistentProperty<?>>> {

	@Nullable
	DV read(SV value, C context);      // L56：库值 → 领域值

	@Nullable
	SV write(DV value, C context);     // 领域值 → 库值
	……
```

配套 SPI：`PropertyValueConverterFactory`（按属性创建转换器）、`SimplePropertyValueConverterRegistry`（按"实体.属性"注册）、`@ValueConverter(EncryptedStringConverter.class)` 注解直接钉在属性上、`PropertyValueConversionService`（6.1 ConversionService 与 6.2 属性转换的统一入口）。JPA/Mongo 的属性加密、枚举存码等需求自此有了官方路径。

> 这也是 Commons 版本节奏的缩影：**接口在 2.7（2022-05，train 2021.1）就绪，1.6.2 节实证 3.0.0 标签已有该文件**——公共内核先立契约，各模块随后（MongoDB 4.x 中后期）逐步跟进消费。

## 6.3 TypeMapper / TypeAlias：多态持久化的类型标签

把 `List<Payment>`（内含 CashPay/CardPay 子类）存进文档库/键值库，读回来时怎么知道该 new 谁？答案是**写库时附带类型元数据**。`convert/` 里的三件套：

- `TypeMapper<S>`：读写"类型标签"字段（如 Mongo 的 `_class`）；
- `TypeInformationMapper`：类型 ↔ 标签的映射策略（`SimpleTypeInformationMapper` 默认写全限定名；`ConfigurableTypeInformationMapper` 按 `@TypeAlias("cashPay")` 别名映射）；
- `TypeAliasAccessor`：标签在存储值里的载体（键名、位置）。

这套机制解释了 Redis/JSON/文档存储里 `_class` 字段的来历，以及为什么跨语言/跨服务读写同一批数据时要注意类型元数据的兼容性。

## 6.4 投影：接口投影的动态代理

"查询用户但只要 email 一个字段"——定义投影接口，Spring Data 在**结果转换期**（2.8 节 `QueryExecutionResultHandler`）把实体包装成接口实现：

【源码证据】`projection/ProjectionFactory.java` 第 27-65 行（节选）：

```java
public interface ProjectionFactory {

	<T> T createProjection(Class<T> projectionType, @Nullable Object source);   // L37
	<T> T createProjection(Class<T> projectionType);                            // L56
	ProjectionInformation getProjectionInformation(Class<?> projectionType);    // L65
```

工厂族分工：

- **`ProxyProjectionFactory`**：JDK 动态代理实现投影接口，属性访问拦截后委托给**目标对象的同名属性**（`PropertyAccessingMethodInterceptor`）；嵌套投影、Map 目标（`MapAccessingMethodInterceptor`）同此路；
- **`SpelAwareProxyProjectionFactory`**（L47）：在父类之上支持 **open projection**——接口方法上标 `@Value("#{target.firstname + ' ' + lastname}")`，由 `SpelEvaluatingMethodInterceptor` 求值（构造器接收 `ValueExpressionParser`，见 `RepositoryFactorySupport#getProjectionFactory` L443-457 的装配现场）；
- **`EntityProjectionIntrospector`**（`projection/`）：3.2+ 新增，反向回答"这个投影接口**需要**实体树的哪些路径"——驱动各模块在查询阶段就把投影写进查询（Mongo 的 `$project`、JPA 的 select 子句裁剪），而不只是结果裁剪。

> **DTO 投影**与接口投影的分工：DTO 投影是普通类 + 全参构造，由 5.4 节的实例化器在构造期填充（`DtoInstantiatingConverter`，`convert/` 包）；接口投影零实现类，全靠代理。前者快（无反射代理），后者省（一个接口走天下）。

## 6.5 本章小结

| 层次 | 机制 | 典型需求 |
|---|---|---|
| 类型级 | CustomConversions + SimpleTypeHolder | LocalDate↔Date、枚举↔码 |
| 属性级 | PropertyValueConverter + @ValueConverter | 指定属性加密、脱敏 |
| 存储结构级 | TypeMapper/TypeAlias | 多态集合读回、_class 字段 |
| 结果形状级 | ProjectionFactory 族 + EntityProjectionIntrospector | 接口投影、@Value 开放投影、DTO 构造 |

至此，"实体如何被理解、值如何被转换、结果如何被整形"全部闭环。下面三章是三个"横切"能力：审计、回调事件、事务。


---

# 七、审计（auditing 包）

> 本章对应源码：`org.springframework.data.auditing`（引擎）+ `auditing.config`（配置骨架）+ `org.springframework.data.annotation`（四个审计注解）。

## 7.1 先说白话：@CreatedDate 背后站着谁

```java
@CreatedBy @LastModifiedBy private String operator;
@CreatedDate @LastModifiedDate private Instant time;
```

四个注解 + 一个开关（如 JPA 的 `@EnableJpaAuditing`），保存时字段自动填充。背后角色表：**注解**标注位置（`annotation` 包）→ **AuditorAware/DateTimeProvider** 提供"谁/何时"（用户态 SPI）→ **AnnotationAuditingMetadata** 反射建立字段索引 → **AuditingHandler** 在 save 钩子里填充 → **auditing.config** 把 Handler 注册成 Bean。

【源码证据】`domain/AuditorAware.java` 第 26-31 行与 `auditing/DateTimeProvider.java` 第 27-30 行：

```java
public interface AuditorAware<T> {
	Optional<T> getCurrentAuditor();     // 从 SecurityContext 等取当前操作人
}

public interface DateTimeProvider {
	Optional<TemporalAccessor> getNow(); // 可替换的"当前时间"，测试可冻结
}
```

（注意分工：`AuditorAware` 住在 `domain` 包——它同时服务响应式侧 `ReactiveAuditorAware`；`DateTimeProvider` 在 `auditing` 包，内置 `CurrentDateTimeProvider` 直接 `Instant.now()`。）

## 7.2 AnnotationAuditingMetadata：反射元数据的建立与缓存

【源码证据】`auditing/AnnotationAuditingMetadata.java` 第 48、85、131-145 行：

```java
	private static final AnnotationFieldFilter CREATED_BY_FILTER = new AnnotationFieldFilter(CreatedBy.class);  // L48
	……
	public AnnotationAuditingMetadata(Class<?> type) {
		this.createdByField = Optional.ofNullable(ReflectionUtils.findField(type, CREATED_BY_FILTER));  // L85
		……
	public boolean isAuditable() {…}          // L131：四个字段全无 ⇒ false
	public Optional<Field> getCreatedByField() {…}   // L138
	public Optional<Field> getCreatedDateField() {…} // L145
```

构造即扫描（每个实体类一次，Handler 内按类型缓存），运行期零反射注解查找。

## 7.3 AuditingHandler：填充引擎

【源码证据】`auditing/AuditingHandler.java` 第 37、73-98 行：

```java
public class AuditingHandler extends AuditingHandlerSupport implements InitializingBean {

	public void setAuditorAware(@Nullable AuditorAware<?> auditorAware) {…}   // L73

	public <T> T markCreated(T source) {     // L82：新建实体
		return markCreated(getAuditor(), source);
	}

	public <T> T markModified(T source) {    // L94：修改实体
		return markModified(getAuditor(), source);
	}
```

基类 `AuditingHandlerSupport`（L87/110/123）持有 `DateTimeProvider` 并真正写字段：`markCreated` 通过 `MappingAuditableBeanWrapperFactory`（把 5.5 节的 `PersistentPropertyAccessor` 包成 `AuditableBeanWrapper`）设置 `@CreatedBy/@CreatedDate`；`markModified` 设置 `@LastModifiedBy/@LastModifiedDate`——**填充走的是映射元数据而非原始反射**，所以 @AccessType、字节码加速、投影包装全都自然兼容。

两个增强子类：

- **`IsNewAwareAuditingHandler`**：用 5.4 节 `EntityInformation.isNew()` 判定该走 markCreated 还是 markModified——"新实体补齐创建四件套，旧实体只动 last-modified"；
- **`ReactiveAuditingHandler` / `ReactiveIsNewAwareAuditingHandler`**：`AuditorAware` 换成响应式发布者，适配响应式仓库链路。

## 7.4 auditing.config：所有 @EnableXxxAuditing 的公共骨架

各存储模块的 `@EnableJpaAuditing`/`@EnableMongoAuditing` 都是薄壳，公共逻辑在 Commons：

【源码证据】`auditing/config/AuditingBeanDefinitionRegistrarSupport.java` 第 53、63-78、94-105 行（节选）：

```java
public abstract class AuditingBeanDefinitionRegistrarSupport
		implements ImportBeanDefinitionRegistrar, BeanFactoryAware {

	public void registerBeanDefinitions(AnnotationMetadata annotationMetadata, BeanDefinitionRegistry registry) {…}  // L53

	/** Registers an appropriate BeanDefinition for an {@link AuditingHandler}. */
	private void registerAuditingHandlerBeanDefinition(…) {
		registry.registerBeanDefinition(getAuditingHandlerBeanName(), ahbd);   // L78：注册 jpaAuditingHandler 等
	}

	/** Creates a {@link BeanDefinitionBuilder} to ease the definition of store specific {@link AuditingHandler}. */
	protected BeanDefinitionBuilder getAuditHandlerBeanDefinitionBuilder(AuditingConfiguration configuration) {…}  // L94-105
```

同包的 `AuditingHandlerBeanDefinitionParser` / `IsNewAwareAuditingHandlerBeanDefinitionParser` 服务 XML 配置时代；`AnnotationAuditingConfiguration`/`AuditingConfiguration` 承载 `@EnableJpaAuditing(modifyOnCreate = …, dateTimeProviderRef = …)` 的属性透传。**Handler 注册成 Bean 之后由谁调用？** 各存储模块把 `markCreated/markModified` 挂进保存钩子（JPA 是实体监听器，Mongo/JDBC 是 EntityCallback——见 8.1 节）。

## 7.5 本章小结

审计是"注解 + 两个 SPI + 一个填充引擎"的最小闭环，也是理解 Commons 定位的绝佳样本：**注解在 Commons、机制在 Commons、触发点在各模块**——各模块只回答"save 的时候调一下 Handler"这一个问题。


---

# 八、实体回调与领域事件（mapping.callback + domain 事件）

> 本章对应源码：`org.springframework.data.mapping.callback`、`org.springframework.data.domain`（AbstractAggregateRoot/DomainEvents）、`repository/core/support/EventPublishingRepositoryProxyPostProcessor`。

## 8.1 EntityCallback：实体生命周期回调体系

"save 前把密码加密、save 后清缓存"——回调比事件更适合（同步、强类型、可改写实体）。Commons 定义根接口与调度器，**具体回调类型由各模块定义**（如 MongoDB 的 `BeforeConvertCallback<T>`/`AfterSaveCallback<T>`、JPA 的 `BeforeConvertCallback` 对齐 Hibernate 事件）：

【源码证据】`mapping/callback/EntityCallback.java` 第 66-71 行与 `EntityCallbacks.java` 第 29-83 行（节选）：

```java
public interface EntityCallback<T> {     // 空标记接口：泛型 T = 实体类型
}

public interface EntityCallbacks {

	static EntityCallbacks create(BeanFactory beanFactory) {…}   // L83：从容器发现所有回调 Bean

	<T> T callback(Class<? extends EntityCallback> callbackType, T entity, Object... args);  // 按类型触发
	void addEntityCallback(EntityCallback<?> callback);
```

调度实现 `DefaultEntityCallbacks`（L41）的核心循环：

【源码证据】`mapping/callback/DefaultEntityCallbacks.java` 第 67-92 行：

```java
	public <T> T callback(Class<? extends EntityCallback> callbackType, T entity, Object... args) {

		Assert.notNull(entity, "Entity must not be null");

		Class<T> entityType = (Class<T>) ClassUtils.getUserClass(entity.getClass());   // 剥掉代理壳

		Method callbackMethod = callbackMethodCache.computeIfAbsent(callbackType, it -> {
			Method method = EntityCallbackDiscoverer.lookupCallbackMethod(it, entityType, args);
			ReflectionUtils.makeAccessible(method);
			return method;
		});

		T value = entity;

		for (EntityCallback<T> callback : callbackDiscoverer.getEntityCallbacks(entityType,
				ResolvableType.forClass(callbackType))) {    // ① 按实体类型筛出全部匹配回调

			BiFunction<EntityCallback<T>, T, Object> callbackFunction =
					EntityCallbackDiscoverer.computeCallbackInvokerFunction(callback, callbackMethod, args);

			value = callbackInvoker.invokeCallback(callback, value, callbackFunction);   // ② 链式传递
		}

		return value;
	}
```

三个工程细节：① 回调方法签名**自动匹配**（`EntityCallbackDiscoverer` 按泛型实参 + 参数类型解析回调方法，缓存之）；② **链式语义**：`value` 一路被回调返回值覆盖——前一个回调改完的实体是下一个回调的输入；③ `callbackMethodCache` 避免每次保存都解析方法。响应式镜像 `ReactiveEntityCallbacks`（`ReactiveEntityCallbackInvoker` 用 `Mono/Flux` 链）完全对偶。

## 8.2 聚合根领域事件：AbstractAggregateRoot + @DomainEvents

DDD 的聚合根要"在自己身上攒事件、持久化时发出去"。Commons 用三件套兑现：

【源码证据】`domain/AbstractAggregateRoot.java` 第 48、103 行，`domain/DomainEvents.java` 第 30 行：

```java
// AbstractAggregateRoot.java
	protected <T> T registerEvent(T event) {…}      // L48：事件进内部列表，返回事件本身（链式）

	// registerAndReturnEvent(...)：registerEvent 的返回聚合版（L103 调用 registerEvent）

// DomainEvents.java —— 标注在"事件 getter"上
public @interface DomainEvents {      // L38
```

配合 `@AfterDomainEventPublication`（事件发布后清理）与 `AbstractAggregateRoot` 的发布钩子。完整链路：聚合根 `registerEvent(new UserCreated(id))` → 仓库 save 成功后由模块桥接（Mongo/JDBC 在 `AbstractAggregateRoot` 机制上调用 `publishEventsFrom`，JPA 版本配合 Spring 事件发布器）→ 事件被 `ApplicationEventPublisher` 发出 → 内部列表清空。

**为什么这套能力放 Commons？** 因为"聚合根攒事件"与存储无关，而"何时发"依赖各模块的保存事务边界——又是"机制在 Commons、触发在各模块"的分工。

## 8.3 桥接：仓库代理把事件接进容器

2.5 节看到 `afterPropertiesSet()` 会挂 `EventPublishingRepositoryProxyPostProcessor`（L339）。它做什么？给仓库代理织入一个拦截器：**每次仓库方法调用发布一个方法级事件**（调用前 `BeforeRepositoryInvokeEvent`、调用后 `AfterRepositoryInvokeEvent`），把仓库活动接入 Spring 事件总线——审计切面、监控都可监听。而 `MappingContextEvent`（5.3.2 节）则让"实体元数据首次就绪"可被订阅。

## 8.4 本章小结

| 需求 | 机制 | 触发位置 |
|---|---|---|
| save 前后改实体（同步强类型） | EntityCallback 族（各模块定义具体类型） | 模块保存钩子调用 `EntityCallbacks.callback()` |
| 聚合根攒事件、持久化后发布 | AbstractAggregateRoot + @DomainEvents/@AfterDomainEventPublication | 模块 save 流程尾部 |
| 仓库方法级事件 | EventPublishingRepositoryProxyPostProcessor | 仓库代理拦截器 |
| 元数据就绪事件 | MappingContextEvent | MappingContext.addPersistentEntity |


---

# 九、事务支持（transaction + repository.core.support）

> 本章对应源码：`org.springframework.data.transaction`、`repository/core/support/Transactional*`。

## 9.1 白话：Repository 的 @Transactional 到底是谁在管

SimpleJpaRepository 的 `save()` 标了 `@Transactional`，你在仓库方法上也标 `@Transactional(readOnly = true)`——这些怎么生效？答案：**Commons 把 Spring 自己的 `TransactionInterceptor` 织进仓库代理**，而非自研事务体系。

【源码证据】`repository/core/support/TransactionalRepositoryProxyPostProcessor.java` 第 46、72-88 行：

```java
class TransactionalRepositoryProxyPostProcessor implements RepositoryProxyPostProcessor {   // L46（包私有）

	@Override
	public void postProcess(ProxyFactory factory, RepositoryInformation repositoryInformation) {   // L72

		TransactionInterceptor transactionInterceptor = new TransactionInterceptor();
		transactionInterceptor.setTransactionAttributeSource(
				new RepositoryAnnotationTransactionAttributeSource(repositoryInformation,
						enableDefaultTransactions));
		transactionInterceptor.setTransactionManagerBeanName(transactionManagerName);
		transactionInterceptor.setBeanFactory(beanFactory);
		transactionInterceptor.afterPropertiesSet();

		factory.addAdvice(transactionInterceptor);
	}
```

它就是 Spring AOP 事务的标准配方（Framework.md 第六章 6.5 节的 `BeanFactoryTransactionAttributeSourceAdvisor` 同款拦截器），只是特化了两点：

1. **属性源反转**：内部类 `RepositoryAnnotationTransactionAttributeSource`（L96）的 Javadoc 直言（L90-94）——Spring 原生算法"实现类配置优先于接口"，仓库场景恰好要反过来：**接口上的 @Transactional 必须能覆盖 SimpleJpaRepository 基类实现上的默认 @Transactional**。于是它先查原始调用的接口方法，再回退实现类。
2. **默认事务开关**：`enableDefaultTransactions` 对应各模块 `@EnableXxxRepositories(enableDefaultTransactions=…)`，决定"基类的 save/delete 是否自带事务"。

谁装上它？`TransactionalRepositoryFactoryBeanSupport`（各模块 `JpaRepositoryFactoryBean` 的父类）：

【源码证据】`repository/core/support/TransactionalRepositoryFactoryBeanSupport.java` 第 35、113 行：

```java
public abstract class TransactionalRepositoryFactoryBeanSupport<T extends Repository<S, ID>, S, ID>
		extends RepositoryFactoryBeanSupport<T, S, ID> implements ApplicationEventPublisherAware {   // L35
	……
	this.txPostProcessor = new TransactionalRepositoryProxyPostProcessor(listableBeanFactory,
			transactionManagerName, enableDefaultTransactions);    // L113
```

配套的 `SurroundingTransactionDetectorMethodInterceptor`（L35，枚举单例拦截器）则负责在调用期间用 ThreadLocal 记录"当前是否处于活动事务"——供响应式/延迟加载逻辑判断"我在事务里吗"。

还记得 2.6 节代理接口里的 `TransactionalProxy` 标记吗（`org.springframework.transaction.interceptor.TransactionalProxy`，import 于 RepositoryFactorySupport L80）？仓库代理借它向 Spring 事务基础设施自报家门。

## 9.2 MultiTransactionStatus 与 ChainedTransactionManager：多事务管理器时代

【源码证据】`transaction/MultiTransactionStatus.java` 第 37 行、`transaction/ChainedTransactionManager.java` 第 70-71 行：

```java
// MultiTransactionStatus.java —— 聚合多个 TransactionManager 的状态包
public class MultiTransactionStatus implements TransactionStatus {   // L37

// ChainedTransactionManager.java —— 链式提交（Best Efforts 1PC）
@Deprecated
public class ChainedTransactionManager implements PlatformTransactionManager {   // L70-71
```

`ChainedTransactionManager` 提供"JMS + DB、DB1 + DB2"场景的尽力一致提交（begin 逆序、commit 顺序、中途失败不回滚前序）。它源于 Spring Data 的跨存储实践，但**两阶段间的窗口永远存在**，官方在 3.3 起将其废弃，推荐重构为单资源或消息驱动的最终一致（JtaTransactionManager / 事件溯源）。它留在 Commons 里的价值更多是历史注脚：**跨存储一致性问题，框架层从不假装能"完美解决"**。

## 9.3 本章小结

| 能力 | 落点 |
|---|---|
| 仓库方法事务 | TransactionalRepositoryProxyPostProcessor（复用 Spring TransactionInterceptor） |
| 事务属性优先级反转 | RepositoryAnnotationTransactionAttributeSource（接口优先） |
| 基类默认事务 | enableDefaultTransactions 开关 |
| 事务感知探测 | SurroundingTransactionDetectorMethodInterceptor |
| 多资源尽力提交（已废弃） | ChainedTransactionManager + MultiTransactionStatus |


---

# 十、Web 层支持（web 包）

> 本章对应源码：`org.springframework.data.web` 与 `web/config`。该包整体 optional——web 依赖缺席时整个包不参与（1.4 节）。

## 10.1 先说白话：Controller 方法签名里的 Pageable 是谁填的

```java
@GetMapping("/users")
public Page<User> list(Pageable pageable) { return userRepository.findAll(pageable); }
```

`GET /users?page=1&size=20&sort=name,asc` 就能工作——因为 Commons 向 Spring MVC 注册了三个参数解析器（Pageable/Sort/OffsetScrollPosition）、一个类型转换器（id 字符串 → 实体）和一套投影消息转换。总开关：

【源码证据】`web/config/EnableSpringDataWebSupport.java` 第 77、140-141、169 行（节选）：

```java
@Import({
	SpringDataWebConfiguration.class,
	…
})
public @interface EnableSpringDataWebSupport {

	@ImportRuntimeHints(…)   // AOT/反射提示
	……

	// L140-141：classpath 上有 spring-hateoas 时自动升级为 HateoasAware 版本
	.map(it -> HateoasAwareSpringDataWebConfiguration.class.getName())
	.orElseGet(() -> SpringDataWebConfiguration.class.getName()));

	// L169：有 QueryDSL 时追加 QuerydslWebConfiguration（谓词参数解析）
	return QuerydslUtils.QUERY_DSL_PRESENT ? new String[] { QuerydslWebConfiguration.class.getName() } …
```

## 10.2 SpringDataWebConfiguration：装配清单

【源码证据】`web/config/SpringDataWebConfiguration.java` 第 64、102-156 行（节选）：

```java
public class SpringDataWebConfiguration implements WebMvcConfigurer, BeanClassLoaderAware {   // L64

	@Bean
	public PageableHandlerMethodArgumentResolver pageableResolver() {…}     // L102-107（包 sortResolver）

	@Bean
	public SortHandlerMethodArgumentResolver sortResolver() {…}             // L111-115

	@Bean
	public OffsetScrollPositionHandlerMethodArgumentResolver offsetResolver() {…}  // L119-123（3.1 Scroll API 入 Web）

	@Override
	public void addFormatters(FormatterRegistry registry) {                 // L128-140
		registry.addFormatter(DistanceFormatter.INSTANCE);   // geo 类型格式化
		registry.addFormatter(PointFormatter.INSTANCE);
		……
		DomainClassConverter<FormattingConversionService> converter = new DomainClassConverter<>(conversionService);
		converter.setApplicationContext(context);
	}

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> argumentResolvers) {   // L143-152
		argumentResolvers.add(sortResolver.get());
		argumentResolvers.add(pageableResolver.get());
		ProxyingHandlerMethodArgumentResolver resolver = new ProxyingHandlerMethodArgumentResolver(conversionService, true);
		……
	}

	@Override
	public void configureMessageConverters(ServerBuilder builder) {…}       // L156 起：投影消息转换器
```

`PageableHandlerMethodArgumentResolverCustomizer`/`SortHandlerMethodArgumentResolverCustomizer`（同包）供应用"微调默认值"而不必重造解析器——Boot 的自动配置正是用它暴露 `spring.data.web.*` 属性。

## 10.3 Pageable/Sort/Offset 参数解析器：默认参数与防炸弹

【源码证据】`web/PageableHandlerMethodArgumentResolverSupport.java` 第 53-63 行：

```java
	private static final String DEFAULT_PAGE_PARAMETER = "page";    // L53
	private static final String DEFAULT_SIZE_PARAMETER = "size";    // L54
	private static final String DEFAULT_PREFIX = "";                // L55
	……
	private String pageParameterName = DEFAULT_PAGE_PARAMETER;      // L61
	private String sizeParameterName = DEFAULT_SIZE_PARAMETER;      // L62
```

解析语义的三个要点：① **prefix + qualifier**：同页多个 `Pageable` 参数可用 `@Qualifier("f")` 得到 `f_page`/`f_size`；② **参数防炸弹**：page<0 拒绝、size 上限 2000（`MAX_PAGE_SIZE`，可配置）——防止 `size=10000000` 打爆内存；③ `@PageableDefault(page = 0, size = 20, sort = "name")`（`web/PageableDefault.java`）与 `@SortDefault`（`web/SortDefault.java`）按参数注解放置默认值（无注解时用 `PageRequest.of(0, 20)` 全局默认）。`sort` 参数语法 `name,desc,id,asc` 由 `SortHandlerMethodArgumentResolver` 按"逗号分隔、最后一段可选方向"解析。

`OffsetScrollPositionHandlerMethodArgumentResolver` 把 `?page=5` 直接翻成 4.3 节的 `OffsetScrollPosition` 游标（另有 Reactive 版本），让 Scroll API 无缝进 Controller 签名。

## 10.4 DomainClassConverter：id 字符串 → 实体

```java
@GetMapping("/users/{id}")
public String get(User user) { … }   // {id} 自动 findById 并注入实体
```

【源码证据】`repository/support/DomainClassConverter.java` 第 50、78-79、142、157 行（节选）：

```java
public class DomainClassConverter<T extends ConversionService & ConverterRegistry>
		implements ConditionalGenericConverter {    // L50

	public @Nullable Object convert(@Nullable Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
		return getConverter(targetType).map(it -> it.convert(source, sourceType, targetType)).orElse(null);  // L78-79
	}

	// 内部 ToFriendlyEntityConverter：先 convert(String → ID)，再 repositoryFactory.findById(id)  L142/157
```

判定条件是"目标类型是被 `Repositories`（11.8 节）管理的实体 && 源类型能转成其 ID 类型"——转不了就交给下一个转换器，绝不越界。

## 10.5 超媒体：PagedModel 与 PagedResourcesAssembler

Spring HATEOAS 在 classpath 时（10.1 的 import 切换），`Page<T>` 不直接序列化为 JSON，而是包成带导航链接的超媒体模型：

【源码证据】`web/PagedResourcesAssembler.java` 第 55、110-124 行：

```java
public class PagedResourcesAssembler<T> implements RepresentationModelAssembler<Page<T>, PagedModel<EntityModel<T>>> {  // L55

	public PagedModel<EntityModel<T>> toModel(Page<T> entity) {…}       // L110
	public PagedModel<EntityModel<T>> toModel(Page<T> page, Link selfLink) {…}   // L123
```

`toModel` 自动生成 `prev/next/first/last` 的 URI 模板链接（读当前请求 + Pageable 参数）。作为参数解析器的姊妹件，`PagedResourcesAssemblerArgumentResolver` 让 Controller 方法直接声明 `PagedResourcesAssembler<T>` 参数拿到实例。3.1 起 `Slice` 对应的 `SlicedResourcesAssembler`/`PagedModel`（web 包）补齐——Slice 数不出总数，超媒体只发 prev/next。`web/PagedModel.java` 里的 `PagedModel`/`PageMetadata` 是 HATEOAS 模型的 Data 侧镜像。

## 10.6 投影式 HTTP 消息转换与 Jackson 2/3 双栈

**投影进请求体**：接口投影不仅用于响应整形，还能当 Controller 参数——`@ProjectedPayload` 标注的接口作为 `@RequestBody` 时，`ProjectingJacksonHttpMessageConverter` 读入 JSON、`ProxyingHandlerMethodArgumentResolver` 生成代理，按 JSON 路径取值（`web/JsonPath`）。典型场景：PATCH 局部更新——请求体里有什么字段就更新什么字段。

**Jackson 双栈**是 4.x 时代的现实：Spring Boot 4 / Jackson 3（包名 `tools.jackson`）逐步铺开，Commons 两头兼容：

【源码证据】`SpringDataWebConfiguration.java` 第 156 行起的 `configureMessageConverters`：

```java
		if (ClassUtils.isPresent("tools.jackson.databind.ObjectMapper", context.getClassLoader())) {
			// Jackson 3：JsonMapper + ProjectingJacksonHttpMessageConverter
		} else if (ClassUtils.isPresent("com.fasterxml.jackson.databind.ObjectMapper", context.getClassLoader())) {
			// Jackson 2 兜底
		}
```

`web/config/SpringDataJackson3Configuration`（+ 旧版 `SpringDataJacksonConfiguration`）注册各存储的分页/游标模块（`SpringDataJackson3Modules`），保证 `Page`/`Window`/`Vector` 的 JSON 往返一致。

## 10.7 本章小结

| Controller 需求 | Commons 提供件 |
|---|---|
| `Pageable`/`Sort` 入参 | Pageable/SortHandlerMethodArgumentResolver（page/size/sort 参数、上限保护） |
| Scroll 游标入参 | OffsetScrollPositionHandlerMethodArgumentResolver |
| `{id}` 路径变量转实体 | DomainClassConverter |
| 分页结果带导航链接 | PagedResourcesAssembler + PagedModel |
| 局部更新的请求体投影 | @ProjectedPayload + ProjectingJacksonHttpMessageConverter |


---

# 十一、配置、引导与扩展机制（repository.config / cdi / aot / init / history）

> 本章对应源码：`org.springframework.data.repository.config`（41 个类）、`repository.cdi`、`repository.init`、`repository.history`、`aot`。

## 11.1 先说白话：@EnableJpaRepositories 的 90% 是 Commons 写的

各存储的 `@EnableJpaRepositories`/`@EnableMongoRepositories` 只是**薄注解壳**（包路径、repositoryBaseClass、qualifiedName 等属性），真正的引导引擎——扫描、属性解析、BeanDefinition 注册、懒加载编排——全部在 `repository.config`。这一章就是"接口如何变成 Bean"的完整答案。

## 11.2 RepositoryConfigurationExtension：各模块的"入驻协议"

【源码证据】`repository/config/RepositoryConfigurationExtension.java` 第 37、54、97、106、117 行（节选）：

```java
public interface RepositoryConfigurationExtension {

	String getModuleName();                                  // L54："JPA" / "MongoDB" …

	default @Nullable String getRepositoryBaseClassName() {…}  // L97：默认基类（SimpleJpaRepository…）

	String getRepositoryFactoryBeanClassName();              // L106：FactoryBean 类型

	void registerBeansForRoot(BeanDefinitionRegistry registry, RepositoryConfigurationSource configurationSource);  // L117
	……
	void postProcess(BeanDefinitionBuilder builder, RepositoryConfigurationSource config);  // L125：注册前的最后加工
```

每种存储实现它一次，就获得了全套引导流程——Commons 通过这个 SPI 把"新存储入驻成本"压到最低。

## 11.3 RepositoryConfigurationDelegate：注册主流程

【源码证据】`repository/config/RepositoryConfigurationDelegate.java` 第 72、141-215 行（关键段）：

```java
public class RepositoryConfigurationDelegate {   // L72

	public List<BeanComponentDefinition> registerRepositoriesIn(BeanDefinitionRegistry registry,
			RepositoryConfigurationExtension extension) {     // L141

		……logger.info("Bootstrapping Spring Data {} repositories in {} mode.")
		extension.registerBeansForRoot(registry, configurationSource);   // L149：先注册公共 Bean

		RepositoryBeanDefinitionBuilder builder = new RepositoryBeanDefinitionBuilder(registry, extension,
				configurationSource, resourceLoader, environment);           // L154

		StartupStep repoScan = startup.start("spring.data.repository.scanning");  // L168：启动观测
		……
		Collection<RepositoryConfiguration<RepositoryConfigurationSource>> configurations =
				extension.getRepositoryConfigurations(configurationSource, resourceLoader, inMultiStoreMode);  // L169：扫描

		for (RepositoryConfiguration<? extends RepositoryConfigurationSource> configuration : configurations) {

			BeanDefinitionBuilder definitionBuilder = builder.build(configuration);       // L188：建 BeanDefinition
			extension.postProcess(definitionBuilder, configurationSource);                // L189：模块加工

			RootBeanDefinition beanDefinition = (RootBeanDefinition) definitionBuilder.getBeanDefinition();
			beanDefinition.setTargetType(getRepositoryFactoryBeanType(configuration));    // L194：泛型 FactoryBean 类型
			……
			registry.registerBeanDefinition(beanName, beanDefinition);                    // L206：注册！
		}

		potentiallyLazifyRepositories(configurationsByRepositoryName, registry,
				configurationSource.getBootstrapMode());                                  // L206-207：懒加载编排
		……
```

要点：① 扫描由 `RepositoryComponentProvider`（`ClassPathScanningCandidateComponentProvider` 子类）执行，判定标准正是 2.3 节的接口族与标记注解（排除 @NoRepositoryBean、跳过接口默认实现包）；② `inMultiStoreMode`——**多存储并存**时 bean 名自动带模块前缀防冲突；③ 每个仓库接口注册出一个 **FactoryBean 定义**（2.5 节），`setTargetType` 带全泛型让类型注入精确匹配。

### 11.3.1 BootstrapMode：启动性能的口子

【源码证据】`repository/config/BootstrapMode.java` 第 26-44 行：

```java
public enum BootstrapMode {
	DEFAULT,     // L32：立即初始化
	DEFERRED,    // L38：容器启动线程上异步初始化（Spring Boot 默认用于 JPA 的懒初始化编排）
	LAZY;        // L44：首次注入/使用时才创建代理
```

`potentiallyLazifyRepositories` 按 mode 给 BeanDefinition 打 lazy 标记并注册 `DeferredRepositoryInitializationListener`（同包）——LAZY/DEFERRED 让数百个仓库的启动期查询解析不再阻塞应用起步。

## 11.4 自定义实现片段的发现：CustomRepositoryImplementationDetector

"UserRepositoryCustom + UserRepositoryCustomImpl" 这对约定（2.7.4 节）由它兑现：

【源码证据】`repository/config/CustomRepositoryImplementationDetector.java` 第 34-46 行（节选）：

```java
 * the necessary component scan is performed when invoking
 * {@link #detectCustomImplementation(ImplementationLookupConfiguration)}.
public class CustomRepositoryImplementationDetector {   // L46
```

按"仓库接口名 + 后缀（默认 Impl）+ 指定 basePackage"发起二次类路径扫描，命中后以 `RepositoryFragmentConfiguration` 返回，`RepositoryBeanDefinitionBuilder` 把它装配为额外片段（工厂侧 `setCustomImplementation`/`setRepositoryFragments`）。`ImplementationDetectionConfiguration` 还控制探测包与类加载器——多模块/MULTI-STORE 场景的重要旋钮。

## 11.5 Revision API：仓库的"时间机器"

修订版本（Envers、MongoDB revision 集合…）的公共契约同样在 Commons：

【源码证据】`repository/history/RevisionRepository.java` 第 35-63 行（节选）：

```java
public interface RevisionRepository<T, ID, N extends Number & Comparable<N>> extends Repository<T, ID> {

	Optional<Revision<N, T>> findLastChangeRevision(ID id);     // L43
	Revisions<N, T> findRevisions(ID id);                       // L51
	Page<Revision<N, T>> findRevisions(ID id, Pageable pageable);  // L63
```

`org.springframework.data.history` 包供给值对象：`Revision`（修订快照）、`Revisions`（修订序列，支持 nextRevisionAfter）、`RevisionMetadata`（提交号/时间/作者），`RevisionSort` 则让 `findRevisions` 的排序约定统一。

## 11.6 CDI 扩展：Spring Data 出 Spring 容器也能跑

【源码证据】`repository/cdi/CdiRepositoryExtensionSupport.java` 第 56、75-77 行（节选）：

```java
public abstract class CdiRepositoryExtensionSupport implements Extension {   // L56（jakarta.enterprise.spi）

	protected <X> void processAnnotatedType(@Observes ProcessAnnotatedType<X> processAnnotatedType) {   // L75
		AnnotatedType<X> annotatedType = processAnnotatedType.getAnnotatedType();   // L77
		……
```

各存储模块继承它注册 CDI 扩展（`META-INF/services/jakarta.enterprise.inject.spi.Extension`），在 Java EE/Quarkus 类环境里用 CDI 生产者替代 FactoryBean 创建仓库（`CdiRepositoryBean`），`@Eager`（同包）提供启动即初始化。

## 11.7 AOT：从 ManagedTypes 到 GraalVM

云原生时代的两项 AOT 基建都在 Commons 落地：

【源码证据】`domain/ManagedTypes.java` 第 39 行、`aot/ManagedTypesBeanFactoryInitializationAotProcessor.java` 第 46 行：

```java
// ManagedTypes.java —— 应用实体清单的可编程声明（替代"等运行期注册"）
public interface ManagedTypes {     // L39
	static ManagedTypes fromIterable(Iterable<? extends Class<?>> types) {…}

// ManagedTypesBeanFactoryInitializationAotProcessor.java —— BeanFactory 初始化期 AOT 钩子
public class ManagedTypesBeanFactoryInitializationAotProcessor
		implements BeanFactoryInitializationAotProcessor {   // L46
```

链路：`ManagedTypes`（可由各模块 @EnableXxx 注解属性或代码注册）→ AOT 处理器在构建期把每个实体交给 `AotMappingContext`（`aot/`）预解析元数据 → `RepositoryRegistrationAotProcessor`（`repository/config`）为每个仓库生成反射/代理声明与 `RepositoryRegistrationAotContribution`；运行期 `AotRepositoryInformation`（同包）把"启动期解析查询方法"替换为"读构建期产物"。这解释了 Spring Boot 3+ 的 native 镜像里 Spring Data 何以能开工。

## 11.8 数据填充与门面：init / Repositories / RepositoryInvoker

- **repository.init**：`RepositoryPopulator`（L26，接口 `initialize(Object repository)`）+ `ResourceReaderRepositoryPopulator`（读 JSON/XML 资源经 Jackson/Unmarshaller 反序列化后逐条 save——demo/测试数据的好工具）；`RepositoriesPopulatedEvent` 在填充完成后发布。
- **repository.support/Repositories**（L55）：把容器里**所有**仓库收进一个门面（`Iterable<Class<?>>` 实体清单、`getRepositoryInformationFor(Class)` L220），是 Spring Data REST 扫描全应用仓库并导出端点的基座；
- **RepositoryInvoker 族**（同包）：把 `save/findById/findAll(…Pageable…)` 抽象成"可按字符串参数驱动的调用器"（`PagingAndSortingRepositoryInvoker`/`CrudRepositoryInvoker`），Data REST 的请求 → 仓库调用正是经它翻译。

## 11.9 本章小结

| 扩展维度 | 机制 |
|---|---|
| 新存储入驻 | RepositoryConfigurationExtension SPI（模块名/基类/FactoryBean/postProcess） |
| 引导主流程 | RepositoryConfigurationDelegate.registerRepositoriesIn（扫描→注册→懒加载） |
| 启动模式 | BootstrapMode（DEFAULT/DEFERRED/LAZY） |
| 自定义实现 | CustomRepositoryImplementationDetector（Impl 后缀类路径扫描） |
| 修订版本 | RevisionRepository + history 包值对象 |
| 非 Spring 容器 | CDI 扩展（CdiRepositoryExtensionSupport） |
| GraalVM/AOT | ManagedTypes + AotMappingContext + RepositoryRegistrationAotProcessor |
| 全局门面 | Repositories / RepositoryInvoker（Data REST 的底座） |


---

# 十二、贯通视图：一个 Repository 的生与死

## 12.1 时间线一：启动——从 @EnableJpaRepositories 到代理就绪

```
应用启动
└── @EnableJpaRepositories
    └── RepositoryConfigurationDelegate.registerRepositoriesIn        [11.3]
        ├── extension.registerBeansForRoot()：注册模块公共 Bean
        ├── RepositoryComponentProvider 扫描 basePackage：
        │     找出 extends Repository 的接口（跳过 @NoRepositoryBean）
        ├── 对每个接口：
        │     ├── RepositoryBeanDefinitionBuilder.build() → RootBeanDefinition
        │     │     （FactoryBean = JpaRepositoriesFactoryBean，targetType 带泛型）
        │     └── registry.registerBeanDefinition()
        └── potentiallyLazifyRepositories()：按 BootstrapMode 打懒标记   [11.3.1]

容器实例化 FactoryBean（无 lazy 或首次注入时）
└── RepositoryFactoryBeanSupport.afterPropertiesSet()                  [2.5]
    ├── createRepositoryFactory()：JpaRepositoryFactory（含 EntityManager）
    ├── 配置：queryLookupStrategy/namedQueries/evaluationContextProvider
    ├── addRepositoryProxyPostProcessor(EventPublishing…) + 事务 PostProcessor  [8.3][9.1]
    ├── RepositoryMetadata：解析 <User, Long> 泛型对                    [2.4]
    ├── RepositoryFragments：自定义实现片段 + 模块贡献片段
    └── Lazy.of(getRepository(…))；非 lazy 立即执行：

RepositoryFactorySupport.getRepository(interface, fragments)            [2.6]
    ├── getRepositoryStub()：组合 composition + DefaultRepositoryInformation（缓存）
    ├── getTargetRepository()：SimpleJpaRepository 实例（模块实现）      [2.6 ③]
    ├── validate()：每个方法都有人接得住
    ├── ProxyFactory：
    │     interfaces = [UserRepository, Repository, TransactionalProxy]
    │     advice 链 = Nullness 校验 → 事务拦截器 → （方法调用监听）
    │                 → QueryExecutorMethodInterceptor
    │                 → ImplementationMethodExecutionInterceptor（片段路由）
    └── getProxy() → JDK 动态代理实例 ← 注入你的 Service
```

## 12.2 时间线二：调用——userRepository.findByEmail("a@b.c") 的十步

```
① 代理被调用：findByEmail(String) 进拦截器链
② 事务拦截器：读接口方法事务属性（反转属性源，接口优先）           [9.1]
③ QueryExecutorMethodInterceptor.invoke：
     该方法 ∈ 启动期建好的 queries 映射（mapMethodsToQuery）        [2.8]
④ RepositoryMethodInvoker.invoke：包一层调用监听（计时/指标）
⑤ JpaQueryLookupStrategy 启动期已用 PartTree 解析过方法名：
     find → SimpleProperty(email) → JPQL "select u from User u where u.email = ?1"
     此处直接拿到可执行查询对象                                     [3.4]
⑥ ParametersParameterAccessor 绑定实参 "a@b.c" → ?1                [3.5]
⑦ 执行：EntityManager 查询数据库
⑧ 结果转换：实体 → 类型转换/属性填充（MappingContext 元数据）      [5][6]
⑨ QueryExecutionResultHandler 收尾：Optional.of(user)（或投影代理） [2.8][6.4]
⑩ 事务提交；监听器记录；返回调用方
```

若方法落在**自定义片段**上（如 `cacheFind`），③ 改由 ImplementationMethodExecutionInterceptor 路由进 `RepositoryComposition`，按注册序"先到先得"；若落在**基类方法**（save/findAll…）上，target（SimpleJpaRepository）直接接单——查询映射根本不参与。

## 12.3 从源码中提炼的三个设计模式视角

1. **模板方法无处不在**：RepositoryFactorySupport（工厂骨架 + 模块钩子）、AbstractQueryCreator（解析骨架 + 翻译钩子）、AbstractMappingContext（缓存骨架 + 实体构造钩子）——Commons 的每一层都是"公共流程写死、方言留白"。
2. **组合优于继承的极致**：fragment composition 让"一个仓库 = N 个能力片段"成为一等公民，接口继承只负责声明。
3. **标记驱动的元编程**：空接口、@NoRepositoryBean、@Indexed、@TypeAlias、@DomainEvents——没有运行期配置文件的 Spring Data，全靠类型系统本身携带元数据。

---

# 十三、附录

## 13.1 关键接口速查表

| 接口/类 | 所在包 | 一句话 |
|---|---|---|
| Repository | repository | 空标记接口，一切仓库的祖先 |
| CrudRepository / ListCrudRepository | repository | CRUD 能力集（Iterable/List 风格） |
| PagingAndSortingRepository | repository | findAll(Sort)/findAll(Pageable) |
| ReactiveCrudRepository | repository.reactive | Mono/Flux CRUD |
| RepositoryMetadata / RepositoryInformation | repository.core | 接口元数据 / 方法归属 |
| EntityInformation | repository.core | isNew()/getId()——聚合根新增判定 |
| RepositoryFactorySupport | repository.core.support | 代理工厂骨架（抽象 getTargetRepository/getRepositoryBaseClass/getQueryLookupStrategy） |
| RepositoryFactoryBeanSupport | repository.core.support | FactoryBean 接线 |
| RepositoryComposition / RepositoryFragment | repository.core.support | 片段组合与路由 |
| QueryLookupStrategy / QueryMethod / RepositoryQuery | repository.query | 查询策略/方法元数据卡/可执行查询 |
| PartTree / Part / AbstractQueryCreator | repository.query.parser | 方法名解析树 / 翻译骨架 |
| Parameters / ParameterAccessor | repository.query | 参数表（Pageable/Sort/Limit/ScrollPosition 白名单） |
| ValueExpressionDelegate | repository.query | 4.0 表达式统一入口 |
| FluentQuery / QueryByExampleExecutor | repository.query | 流式查询 / QBE |
| Pageable / PageRequest / Sort / Page / Slice | domain | 分页排序值对象族 |
| Window / ScrollPosition / WindowIterator | domain + support | Scroll API |
| Limit / Example / ExampleMatcher / Persistable | domain | 限流 / QBE 探针 / isNew 约定 |
| AbstractAggregateRoot / @DomainEvents | domain | 聚合根事件 |
| AuditorAware / @CreatedBy… / AuditingHandler | domain + auditing + annotation | 审计三件套 |
| EntityCallback / EntityCallbacks | mapping.callback | 实体回调管道 |
| MappingContext / PersistentEntity / PersistentProperty | mapping(+context) | 映射元数据三件套 |
| TypeInformation / PropertyPath | core | 泛型情报站 / 属性路径 |
| CustomConversions / PropertyValueConverter / TypeMapper | convert | 类型字典 / 属性级转换 / 多态标签 |
| ProjectionFactory / SpelAwareProxyProjectionFactory | projection | 接口投影工厂 |
| RevisionRepository / Revision / Revisions | repository.history + history | 修订版本 |
| Repositories / RepositoryInvoker | repository.support | 全局仓库门面（Data REST 底座） |
| PageableHandlerMethodArgumentResolver / DomainClassConverter | web / repository.support | Web 入口两件套 |
| PagedResourcesAssembler / PagedModel | web | HATEOAS 分页模型 |
| RepositoryConfigurationExtension / RepositoryConfigurationDelegate | repository.config | 入驻 SPI / 引导引擎 |
| ManagedTypes / ManagedTypesBeanFactoryInitializationAotProcessor | domain + aot | AOT 实体清单 |

## 13.2 初学者学习路线（动手向）

1. **造一个最小仓库**：任意 Boot 项目里写 `interface UserRepository extends JpaRepository<User, Long>`，跑通 CRUD；打断点在 `RepositoryFactoryBeanSupport#afterPropertiesSet` 与 `RepositoryFactorySupport#getRepository`，观察本章流程。
2. **玩坏方法名**：故意写 `findByFoo`（不存在的属性）看启动异常（PropertyReferenceException）；写 `findDistinctTop3ByLastnameIgnoreCaseOrderByFirstnameDesc` 在 PartTree 断点看树结构。
3. **加一个自定义片段**：`UserRepositoryCustom` + Impl，验证路由顺序（2.7.4）。
4. **换三种分页**：`Page`/`Slice`/`Window`（keyset）各写一遍，开 SQL 日志对比 count 与 OFFSET（第四章）。
5. **审计 + 回调**：@EnableJpaAuditing + AuditorAware；写一个 `BeforeSaveCallback`（MongoDB）或 JPA 实体监听，观察第七、八章的分工。
6. **加一个 Pageable Controller**：用 `/users?page=0&size=10&sort=name,desc` 验证第十章；再用 `PagedResourcesAssembler` 出 HATEOAS。
7. **读一次配置流程**：断点 `RepositoryConfigurationDelegate#registerRepositoriesIn`，数一数你的 basePackage 扫出了几个仓库。

## 13.3 源码阅读入口清单（20 个关键文件）

| 文件 | 看什么 |
|---|---|
| repository/Repository.java | 空接口即契约（36 行） |
| repository/CrudRepository.java、ListCrudRepository.java | 能力集设计 |
| repository/core/support/RepositoryFactorySupport.java | ★ 代理装配全流程（L308-429） |
| repository/core/support/RepositoryFactoryBeanSupport.java | FactoryBean 接线（afterPropertiesSet） |
| repository/core/support/RepositoryComposition.java | 片段路由 |
| repository/core/support/QueryExecutorMethodInterceptor.java | 查询调度台 |
| repository/core/support/TransactionalRepositoryProxyPostProcessor.java | 事务织入 |
| repository/core/RepositoryInformationSupport.java | 查询方法判定 |
| repository/query/QueryLookupStrategy.java | 三策略 |
| repository/query/parser/PartTree.java | ★ 方法名解析器（正则 + Subject/Predicate） |
| repository/query/parser/Part.java | 关键词类型表 |
| repository/query/parser/AbstractQueryCreator.java | 翻译骨架四钩子 |
| repository/query/Parameters.java | 特殊参数白名单 |
| repository/query/ValueExpressionDelegate.java | 4.0 表达式体系 |
| domain/Pageable.java、Sort.java、Window.java、ScrollPosition.java | 分页/游标族 |
| mapping/context/AbstractMappingContext.java | 元数据缓存样板 |
| mapping/model/EntityInstantiators.java | 实例化分发 |
| convert/CustomConversions.java、PropertyValueConverter.java | 类型转换双体系 |
| projection/ProxyProjectionFactory.java、SpelAwareProxyProjectionFactory.java | 投影代理 |
| repository/config/RepositoryConfigurationDelegate.java | ★ 启动注册主流程 |

## 结语

Spring Data Commons 的源码几乎是一份"如何设计框架内核"的公开课：**用类型系统携带元数据（空接口与泛型对）、用组合承接变化（fragment composition）、用 SPI 分隔方言（RepositoryConfigurationExtension/AbstractQueryCreator）、把一切延迟到元数据就绪之后（MappingContext 缓存）、把一切错误提前到启动期（validate + 启动期查询解析）**。读它的回报不止于"看懂 Spring Data"——当你下一次要为自己的团队设计一个"多后端统一抽象"（多种消息中间件、多种对象存储、多种搜索引擎）时，这份源码就是最完整的参照系。

*（完）*






