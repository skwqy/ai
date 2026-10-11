# Spring Data Elasticsearch 深度源码解析（写给初学者的架构全景）

> **本文基于的源码**：`D:\code\3rd\spring-data-elasticsearch`，版本 **6.2.0-SNAPSHOT**（main 分支快照，Git commit `42363a6f`，2026-10-05 读取）。写作时最新稳定版为 **6.1.0**（2026-06-09 发布），依赖基线：spring-data-commons **4.2.0-SNAPSHOT**、Elasticsearch Java Client（`co.elastic.clients:elasticsearch-java`）**9.5.4**、Spring Framework 7.x。文中所有【源码证据】的文件路径与行号均为对该快照实际读取所得（主模块为单 Maven 模块，源码路径 `src/main/java/org/springframework/data/elasticsearch/...`，下文引用时省略此前缀）。
>
> **版本取舍说明**：spring-data-elasticsearch 自 **4.0 引入 Elasticsearch 新 Java 客户端（client/elc 包）并把操作接口按 REST API 结构拆分**之后，核心骨架（Operations 模板 / MappingElasticsearchConverter / Repository 工厂链 / Criteria→Query DSL 编译）在 4.x/5.x/6.x 之间保持稳定；5.2 移除了 RestHighLevelClient 与 WebClient 反应式客户端两套旧栈，6.0 把默认传输切换为 ES 9 的 Rest5Client，6.1 引入 Observation 与 Jackson 3。因此本文内容对使用 Spring Boot 3.x（spring-data-elasticsearch 5.x）的读者同样适用，新旧差异集中在 1.6 节与第五、九章单独标注。所有"某特性属于哪个版本"的结论均经本地 git 标签（4.4.0 / 5.0.0 / 5.1.0 / 5.2.0 / 5.5.0 / 6.0.0 / 6.1.0 / 6.2.0-M2）逐项实证。
>
> **阅读约定**：与同系列文档（《Spring Framework.md》《Spring for Apache Kafka.md》）一致，每章"先白话、后源码"——先用一两句人话讲清"这是什么、为什么需要它"，再给出源码证据链（`包/类名.java` + 方法名 + 行号 + 代码片段）。行号只对该快照精确，读者用 IDEA 打开源码按类名 + 方法名定位即可。

## 如何读这份文档

如果你是 Spring Data Elasticsearch（下文按官方习惯简称 **spring-data-elasticsearch** 或 **SD-ES**）初学者，推荐两遍读法：

- **第一遍（建立地图，1~2 小时）**：只读第一章（总览）每一节的开头白话段、第二章（上手与概念）、各章的"小结"节、第十一章（贯通视图）。目标是能回答：`repository.save(entity)` 到 Elasticsearch 集群经过了哪几步？`findByLastName("Smith")` 这种方法名是谁、怎么翻译成查询 DSL 的？`@Document`、`@Field` 注解被谁在什么时机消费？"启动时自动建索引"发生在哪行代码？
- **第二遍（深入源码）**：对照每一章的【源码证据】逐行读。顺序建议：第三章（commons 地基，Repository 机制的通用骨架）→ 第四章（实体映射，回答"对象怎么变成文档"）→ 第六章（Operations 模板，回答"模板怎么调用客户端"）→ 第八章（Repository 链路，回答"方法名怎么长成查询"）→ 第七章（查询体系，随用随查）→ 第五章（连接层，随用随查）→ 第九章（响应式栈）→ 第十章（扩展件，随用随查）。

---

# 一、总览：spring-data-elasticsearch 的定位、设计哲学与整体架构

## 1.1 一句话定位

**spring-data-elasticsearch 是 Spring Data 家族中针对 Elasticsearch 的实现：它用 spring-data-commons 的 Repository 抽象把"方法名 + 注解"变成 Elasticsearch 查询，用一套对象-文档映射（ODM）在 POJO 与 JSON 文档之间双向翻译，再把"连接、索引管理、集群管理"包成与容器无缝衔接的模板 Bean。**它**不发明任何 Elasticsearch 语义**——查询 DSL、分片、路由、refresh、scroll/PIT 全部原样来自 Elasticsearch 本身，框架做的是"翻译"与"接线"。官方 README 自述其定位："This project provides Spring Data repository abstractions for Elasticsearch."

三个容易混淆的点先说清楚：

1. **它不是 Elasticsearch 客户端的替代品，而是客户端的上层**。底层网络与协议就是 Elastic 官方的 `co.elastic.clients:elasticsearch-java`（`ElasticsearchClient` 及其 `Rest5ClientTransport`）；SD-ES 的所有 `RequestConverter` 最终产出的都是这个客户端的标准请求对象。想绕过 Spring 直接写原生 DSL？`NativeQuery` 就是给这个口子的。
2. **它本身没有自动配置**。`spring.elasticsearch.*` 属性、`ElasticsearchRestClientAutoConfiguration`、`spring-boot-starter-data-elasticsearch` 的 Bean 装配都住在 Spring Boot 的数据模块里。裸用 spring-data-elasticsearch 时，要么继承 `ElasticsearchConfiguration` 自己声明 Bean（5.4 节），要么用 Boot 自动配置——但无论哪条路，最终都汇聚到同一组核心 Bean（`ClientConfiguration → Rest5Client → ElasticsearchClient → ElasticsearchTemplate → Repository`）。
3. **它与 Spring Data JPA/MongoDB 共享同一套"上层协议"**。`CrudRepository`、`@Enable*Repositories`、方法名派生查询、`Query by Example`、实体回调、审计——这些能力由 spring-data-commons 提供，各存储模块只是填入自己的"查询翻译器"与"对象映射器"。读完第三章你会发现 spring-data-elasticsearch 的 Repository 链路和 spring-data-mongodb 几乎逐类对应。

## 1.2 设计哲学：读源码前先记住四句话

1. **接口按 Elasticsearch REST API 的结构切分，一个 API 组对应一个接口**。4.0 起模板层不再是一个几百方法的大接口：文档 CRUD 是 `DocumentOperations`、搜索是 `SearchOperations`、脚本与 SQL 各有 `ScriptOperations`/`SqlOperations`、索引管理与集群管理分别是 `IndexOperations`/`ClusterOperations`。接口的 javadoc 直接链接到 ES 官方文档对应章节（见 `core/ElasticsearchOperations.java:26-30` 的自述）。学过 ES REST API 的人看接口名就知道方法在哪。
2. **实体回填是显式的，不是魔法**。保存后 `id`、`version`、`seq_no/primary_term`、`_index` 为什么自动出现在实体字段里？因为保存链路的最后一步必然经过 `EntityOperations.updateIndexedObject`（`core/EntityOperations.java:108`），它从 ES 响应里取出这四个值写回实体属性；读取链路的对应物是 `MappingElasticsearchConverter$Reader.readEntity`（`core/convert/MappingElasticsearchConverter.java:364-394`）。没有反射巫术，只有两条看得见的回填路径。
3. **每种查询写法都是"一座编译器"，目标语言是 ES 的 Query DSL**。方法名（`findByNameAndAge`）由 spring-data-commons 的 `PartTree` 解析成"谓词树"，再由 `ElasticsearchQueryCreator` 翻译成 `Criteria` 树（第八章）；`Criteria` 树由 `CriteriaQueryProcessor` 翻译成 ES 的 `bool` 查询（第七章）；`NativeQuery` 则允许直接写 ES Java 客户端的类型安全 DSL。三层写法，同一个执行出口（`ElasticsearchTemplate.doSearch`）。
4. **模板对象是不可变定制的（copy-on-customize）**。想按请求切路由或 refresh 策略？`operations.withRouting(...)`、`operations.withRefreshPolicy(...)` 返回的是当前模板的**副本**（`AbstractElasticsearchTemplate.copy()`，`core/AbstractElasticsearchTemplate.java:113-127`），原模板不动。这让"同一个模板 Bean、多种运行时变体"成为可能的同时不引入共享可变状态。

## 1.3 包分层全景

spring-data-elasticsearch 是单 Maven 模块工程（`pom.xml` + `src/main/java`，实测 414 个源码文件），**包即模块**。8 个顶级包按"用户感知"分层如下：

```
┌──────────────────────────── Repository 层（写业务代码时直接面对） ────────────────────────────┐
│ repository.ElasticsearchRepository / ReactiveElasticsearchRepository（仓库根接口）             │
│ repository.config（@EnableElasticsearchRepositories、Registrar、ConfigurationExtension）       │
│ repository.support（RepositoryFactory、FactoryBean、SimpleElasticsearchRepository、           │
│                     EntityInformation、QueryStringProcessor、querybyexample 子包）             │
│ repository.query（ElasticsearchQueryMethod、AbstractElasticsearchRepositoryQuery、            │
│                   RepositoryPartQuery/RepositoryStringQuery/RepositorySearchTemplateQuery、   │
│                   parser/ElasticsearchQueryCreator）                                          │
├──────────────────────────── Operations 模板层（编程式 API） ────────────────────────────────┤
│ core.ElasticsearchOperations（= Document + Search + Script + Sql Operations）                 │
│ core.AbstractElasticsearchTemplate（回调编排、IndexQuery 组装、SearchHit 映射的公共骨架）      │
│ core.IndexOperations / ClusterOperations / Reactive* 同构体系                                  │
├──────────────────────────── 查询与结果模型层 ────────────────────────────────────────────────┤
│ core.query（Query/BaseQuery/CriteriaQuery/StringQuery/NativeQuery/UpdateQuery/IndexQuery…）    │
│ core.SearchHit(s)/SearchScrollHits/SearchHitsIterator/StreamQueries（结果容器）                │
├──────────────────────────── 对象-文档映射层 ─────────────────────────────────────────────────┤
│ core.mapping（SimpleElasticsearchMappingContext/PersistentEntity/PersistentProperty、          │
│               IndexCoordinates、PropertyValueConverter）                                       │
│ core.convert（MappingElasticsearchConverter、日期/Range/Geo 值转换器、CustomConversions）      │
│ core.index.MappingBuilder（@Field/@MultiField/@Setting → ES mapping JSON）                     │
│ annotations（@Document/@Field/@Setting/@MultiField/@Routing/@JoinTypeRelation…45 个）          │
├──────────────────────────── 连接层 ─────────────────────────────────────────────────────────┤
│ client.ClientConfiguration（主机、超时、代理、SSL、请求头——与具体客户端无关）                   │
│ client.elc（ElasticsearchClients 工厂、ElasticsearchTemplate、ReactiveElasticsearchTemplate、   │
│             RequestConverter/ResponseConverter、Criteria/Native 查询处理器、IndicesTemplate、   │
│             rest5_client/rest_client 两个传输子包）                                             │
├──────────────────────────── 配套设施层 ──────────────────────────────────────────────────────┤
│ core.event（BeforeConvert/AfterSave/AfterLoad/AfterConvert 回调 + 审计回调）                    │
│ core.cluster / core.routing / core.join / core.geo / core.script / core.sql / core.suggest /   │
│ core.reindex（各垂直能力的领域模型与操作接口）                                                  │
│ config（ElasticsearchConfigurationSupport、@EnableElasticsearchAuditing、XML 命名空间）        │
│ aot（GraalVM RuntimeHints）、utils（SpEL 工具、geohash）、support（与 commons 对齐的支撑）      │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
```

包间依赖方向与上图一致：repository 依赖 core（仓库的每个查询方法最终调 `ElasticsearchOperations`）；core 依赖 client.elc（模板持有 `ElasticsearchClient`）与 core.mapping/convert（模板持有 `ElasticsearchConverter`）；annotations 只被 mapping/convert 消费。**唯一"倒挂"的是 `NativeQuery` 放在 client.elc 包**——因为它直接引用 ES Java 客户端的类型（`co.elastic.clients.elasticsearch._types.query_dsl.Query`），不能上浮到与客户端无关的 core.query 包。

## 1.4 依赖图（以主模块 pom.xml 实证）

`pom.xml:135-179` 依赖块实测（groupId 均为默认省略，仅列 artifactId 与 scope）：

| 依赖 | scope | 角色 |
|---|---|---|
| spring-context / spring-tx | compile | 容器接线（FactoryBean/BPP/事件）、`@Transactional` 元数据 |
| spring-data-commons（4.2.0-SNAPSHOT） | compile | Repository 机制、映射地基、类型系统——整个上层协议 |
| elasticsearch-java（9.5.4） | compile | **唯一中间件客户端**：`ElasticsearchClient`、Query DSL 模型、Rest5Client |
| elasticsearch-rest-client（9.5.4） | **optional** | 旧版 Apache RestClient，仅 Legacy 配置类需要（6.0 起降级为可选） |
| spring-webflux | **optional** | 响应式签名与响应式仓库支持（6.x 中只余少量响应式类型桥接） |
| tools.jackson（Jackson 3） | compile | JsonpMapper 默认实现（6.1 起，见 1.6） |
| com.fasterxml.jackson（Jackson 2） | provided/optional | 仅供旧 RestClient 栈使用 |
| querydsl-core / kotlin-stdlib / kotlinx-coroutines / CDI / micrometer-observation | optional | Querydsl 预留（未实现，见 8.3）、Kotlin 协程扩展、CDI 集成、观测 API |

```
            Elasticsearch 集群（REST API, :9200）
                        ▲
        co.elastic.clients:elasticsearch-java（ElasticsearchClient + Rest5Client/Transport）
                        ▲
              spring-data-elasticsearch（唯一主角）
              ▲                    ▲                  ▲
     spring-data-commons      spring-context       spring-tx
   （Repository/映射/类型）     （容器/FactoryBean）  （事务元数据）
                        ▲
        Spring Boot data-elasticsearch 模块（自动配置：
        spring.elasticsearch.* → ClientConfiguration → 模板/仓库 Bean）
```

这张图的要害：**中间件语义 100% 来自 elasticsearch-java**（SD-ES 没有自定义任何协议）；**spring-data-commons 是 compile 依赖里的"宪法"**（没有它 Repository 体系无从谈起）；**optional 依赖全可缺**（不引 webflux 则响应式 API 不可用，不引旧 rest-client 则 Legacy 类退化为编译期条件）。

## 1.5 关键问题 → spring-data-elasticsearch 方案映射（全文导览）

| 使用 Elasticsearch 的关键问题 | spring-data-elasticsearch 的方案 | 详见 |
|---|---|---|
| 每个 POJO 都要手写 mapping JSON 与文档序列化 | `@Document/@Field` 注解 → `MappingBuilder` 生成 mapping，`MappingElasticsearchConverter` 双向读写 | 第四章 |
| 方法名派生查询、注解查询、原生 DSL 想混着用 | `Query` 体系三兄弟：CriteriaQuery（类型安全树）/StringQuery（原生 JSON）/NativeQuery（客户端 DSL） | 第七章 |
| 接口方法 `findByXxx` 如何变成一次 HTTP 搜索 | PartTree → ElasticsearchQueryCreator → Criteria → Processor → ES bool 查询，Repository 代理驱动 | 第三章、第八章 |
| 保存后想要 ES 生成的 id/version 回填实体 | `updateIndexedObject`（写路径）与 `readEntity`（读路径）两条显式回填链 | 第四章、第六章 |
| 建索引、别名、mapping 更新、索引模板谁来管 | `IndexOperations`/`IndicesTemplate`：`createWithMapping()` 在仓库 Bean 构造时自动触发 | 6.7 节、8.4 |
| 不想等 refresh 就能搜到刚写的文档 | `RefreshPolicy`（NONE/IMMEDIATE/WAIT_UNTIL）+ `withRefreshPolicy` 副本定制 + 仓库 `save(entity, policy)` 重载 | 6.6 节 |
| 深度翻页（scroll 已过时） | `searchForStream`（scroll 封装）/响应式侧 **PIT + search_after** 自动循环 | 6.5、9.3 |
| parent-child、按字段路由 | `@JoinTypeRelation`/`JoinField`、`@Routing`（支持 SpEL）+ `RoutingResolver` 副本 | 6.6、第十章 |
| 聚合结果反序列化成 Java | `AggregationsContainer` + `ElasticsearchAggregations` 适配 co.elastic.clients 的 `Aggregate` | 7.6 节 |
| ES 报错语义（404/409/version conflict）翻译成 Spring 异常 | `ElasticsearchExceptionTranslator` → `DataAccessException` 体系（NoSuchIndexException/OptimisticLockingFailureException…） | 5.6 节 |
| 响应式/流式读取大量文档 | `ReactiveElasticsearchClient`（`Mono.fromFuture` 桥接）+ 模板 PIT+search_after 无界 Flux | 第九章 |
| 实体生命周期横切逻辑（审计、事件回填） | `EntityCallbacks` 四对回调 + `@EnableElasticsearchAuditing` + 仓库 ApplicationEvent | 第十章 |

## 1.6 版本演进：4.0 → 6.2 关键变化对比

写作时（2026 年 10 月）的版本格局：5.5.x 是 5.x 收官线（随 Spring Data 2025.0 维护），6.0（2025-11-14 GA，随 Boot 4.0）与 6.1（2026-06-09 GA）并行，main 即 6.2.0-SNAPSHOT。本节所有"特性属于哪个版本"的结论都经本地 git 标签实证，每行可复现。

### 1.6.1 版本时间线与运行基线

| 版本 | GA 时间 | 基线 | 客户端格局 | 一句话主题 |
|---|---|---|---|---|
| 3.2 | 2019-10 | JDK 8 / Boot 2.2 | TransportClient（弃用中）+ RestHighLevelClient | 注解体系与 ConfigurationSupport 定型（随 Moore 版本列车） |
| 4.0 | 2020-05-27 | JDK 8+ / Boot 2.3 | **新增 co.elastic.clients 栈（client/elc）**，RHLC 为主 | 按 REST API 拆分操作接口；移除 TransportClient；升级到 ES 7 |
| 4.4 | 2022-05-13 | JDK 8+ | elc 栈成熟，RHLC/WebClient 双旧栈并存 | `ElasticsearchConfiguration` 配置基类登场 |
| 5.0 | 2022-11-18 | **JDK 17 / Boot 3 / jakarta** | RHLC 移入弃用包 client/erhlc | 基座大迁移 |
| **5.2** | 2023-11-17 | JDK 17 | **RHLC 栈与 WebClient 反应式栈整体移除** | 从"三栈并存"收敛为"elc 单栈" |
| 5.5 | 2025-05-16 | JDK 17 | 单栈 | 5.x 收官（随 Spring Data 2025.0） |
| **6.0** | 2025-11-14 | JDK 17 / Boot 4 / Framework 7 | **默认传输切换为 ES 9 的 Rest5Client**；旧 RestClient → `ElasticsearchLegacyRestClientConfiguration` | Rest5Client 化 |
| 6.1 | 2026-06-09 | 同上 | 同上 | **Observation 可观测 + Jackson 3（tools.jackson）JsonpMapper** |
| 6.2（main/M2） | 2026 下半年 | 同上 | 同上 | `ElasticsearchServerType`（SERVERLESS 感知）、`@Field.customIndexOptions` |

关键迁移节点的 git 实证（每行都可用 `git ls-tree` / `git grep <tag>` 复现）：

| 结论 | 证据 |
|---|---|
| 4.0 移除 TransportClient | v4.4.0 全部主源码 `grep TransportClient` = 0 命中（官方升级指南 "Upgrading from 3.2.x to 4.0.x" 确认 TransportClient 支持在 4.0 移除） |
| 4.0 引入 co.elastic.clients 栈 | v4.4.0 已存在 `client/elc/`（ElasticsearchTemplate/ReactiveElasticsearchClient 等 44 文件）；同时 `client/reactive/`（WebClient 版）与顶层 RHLC 类并存 |
| 5.0 弃用 RHLC（进入 client/erhlc） | v5.0.0 `client/erhlc/` 存在 41 文件（AbstractElasticsearchConfiguration、ElasticsearchRestTemplate 等），v5.1.0 仍有 41 文件 |
| **5.2 移除 RHLC + WebClient 反应式** | v5.2.0 `client/erhlc/` = 0 文件；`git grep -l WebClient v5.1.0` = 10 个文件 → v5.2.0 = **0** |
| 6.0 切换 Rest5Client | v5.5.0 `grep -l Rest5Client` = 0 → v6.0.0 = 9 个文件；`ElasticsearchConfiguration.java:40-42` javadoc："From Version 6.0 on, this class uses the new Rest5Client from Elasticsearch 9" |
| 6.1 引入 Observation | v6.0.0 `grep -l ObservationRegistry` = 0 → v6.1.0 = 4 个文件（两个 Template + 两个抽象父类），且 `client/elc/ElasticsearchObservation.java` 等 6 个新类标 `@since 6.1` |
| 6.1 引入 Jackson 3 | v6.0.0 `grep -l "tools.jackson"` = 0 → v6.1.0 = 9 个文件（jsonpMapper 改用 `Jackson3JsonpMapper`，`ElasticsearchConfiguration.java:129-133`） |

### 1.6.2 对初学者的意义：哪些知识过时了，哪些永远有效

- **新项目避免使用**：任何 `RestHighLevelClient` 相关类与 `org.elasticsearch.client.RestClient` 直连（6.x 中仅 Legacy 类还认它们）、WebClient 版 `DefaultReactiveElasticsearchClient`、`@Document(type=…)`（早已删除）、把 scroll 当默认翻页方案（响应式侧已默认 PIT + search_after）。
- **5.x 老教程里永久有效的部分**：`@Document/@Field/@Query` 注解体系、`ElasticsearchOperations` 三段式（构造 Query → 执行 → 读 SearchHits）、方法名派生查询规则、`MappingElasticsearchConverter` 读写语义、`IndexOperations` 建索引——这些从 4.x 到 6.x 骨架未变，本文第四~八章正是按这条主线写的。
- **版本与 Boot 的对应关系**：Boot 2.x ↔ SD-ES 4.x；Boot 3.0~3.1 ↔ 5.0~5.1；Boot 3.2 ↔ 5.2（移除 RHLC 的版本）；Boot 3.5 ↔ 5.5；Boot 4.0/4.1 ↔ 6.0/6.1。从 Boot 3.x 入手的读者，实际用的就是"elc 单栈"架构，与本文第六~八章的描述一致。

## 1.7 全文章节地图

- **第二章 上手与核心概念**：二十行代码看懂编程模型；一张"名词 → 源码位置"对照表。
- **第三章 地基：spring-data-commons**：Repository 标记接口族、工厂代理、QueryMethod/PartTree/ResultProcessor——所有 Spring Data 模块共享的上层协议。
- **第四章 实体映射（重点 A）**：MappingContext 元数据、@Document/@Field 全解、MappingBuilder 生成 mapping、Converter 双向读写与回填。
- **第五章 连接层（重点 B）**：ClientConfiguration → Rest5Client → Transport → ElasticsearchClient 的 Bean 链、异常翻译。
- **第六章 Operations 模板层（重点 C）**：save/search 的一生、bulk、scroll/PIT、IndexOperations 与 ClusterOperations、RefreshPolicy/路由的副本定制。
- **第七章 查询体系（重点 D）**：Query 三兄弟、Criteria→bool 编译器、高亮/聚合/runtime fields。
- **第八章 Repository 链路（重点 E）**：从 @Enable 到代理 Bean 的注册链、QueryLookupStrategy 四分支、PartTree→Criteria 对照表、SpEL 与占位符。
- **第九章 响应式栈（重点 F）**：Mono.fromFuture 桥接、有界/无界搜索（PIT + search_after + Flux.expand）。
- **第十章 扩展件与配套**：回调/审计/事件、Query by Example、join/geo/script/sql/reindex、AOT。
- **第十一章 贯通视图**：启动、一次 save、一次 findByXxx 三条时间线叠加成全景图。
- **附录**：接口速查表、学习路线、20 个关键源码文件入口。

---

# 二、上手与核心概念：先建立整体画面

## 2.1 二十行代码看懂编程模型

**白话**：SD-ES 的日常使用只涉及三样东西——一个带注解的实体、一个继承 `ElasticsearchRepository` 的接口、（可选的）一个 `ElasticsearchOperations` 模板。下面是一个能跑的最小切片：

```java
@Document(indexName = "products")
public class Product {
    @Id private String id;                       // ES 的 _id
    @Field(type = FieldType.Text) private String name;
    @Field(type = FieldType.Keyword) private String category;
    @Field(type = FieldType.Double) private Double price;
    // getter/setter 略
}

public interface ProductRepository extends ElasticsearchRepository<Product, String> {
    List<Product> findByCategoryAndPriceLessThan(String category, Double price); // 方法名派生查询
}

@SpringBootApplication
@EnableElasticsearchRepositories            // 扫描并代理上面的接口
public class App { /* Boot + spring-boot-starter-data-elasticsearch */ }

// 使用：
List<Product> hits = productRepository.findByCategoryAndPriceLessThan("laptop", 9999.0);
Product saved = productRepository.save(new Product(...));   // id/version 自动回填
```

没有 XML、没有 SQL、没有 DSL 字符串。背后发生的事（后续章节逐一展开）：启动时 `@EnableElasticsearchRepositories` 为每个仓库接口注册 `ElasticsearchRepositoryFactoryBean`（FactoryBean 产代理），**代理 Bean 的构造函数里顺手检查并创建了 `products` 索引**（8.4 节）；调用 `findByCategoryAndPriceLessThan` 时，代理把方法交给 `RepositoryPartQuery`，后者把方法名解析成 `PartTree`，翻译成 `Criteria` 树，再编译成 ES 的 `bool` 查询，经 `ElasticsearchTemplate.search` 发往集群，最后把 `SearchHits` 解包成 `List<Product>`。

## 2.2 十个名词：框架的每个机制都对应 ES 的一个语义

| 名词 | ES 语义 | SD-ES 中的落点 |
|---|---|---|
| `@Document(indexName)` | 索引（index） | 实体 → 索引的绑定，元数据存于 `SimpleElasticsearchPersistentEntity` |
| `@Field(type=…)` | 字段类型（mapping） | `MappingBuilder` 生成 mapping JSON；`FieldType` 枚举覆盖 ES 全部类型 |
| `@Id` | `_id` 元字段 | spring-data-commons 注解；保存/回填见第六章 |
| `IndexCoordinates` | 索引名（可多个、可通配） | 一次操作的"目标索引"值对象（`core/mapping/IndexCoordinates.java:31`） |
| `Query` | search 请求体 | 顶层接口（`core/query/Query.java:46`），三实现见第七章 |
| `SearchHit<T>` / `SearchHits<T>` | 命中数组 + `_score`/`_index`/highlight | 结果包装（`core/SearchHit.java`、`SearchHitsImpl.java`），实体藏在 hit 内 |
| `RefreshPolicy` | `refresh` 参数 | NONE/IMMEDIATE/WAIT_UNTIL 三值枚举（`core/RefreshPolicy.java:24`） |
| `SeqNoPrimaryTerm` | `_seq_no`/`_primary_term`（乐观锁） | 实体可选字段类型，写路径自动带上做并发控制 |
| `NativeQuery` | 原生 DSL | 直接持有 ES 客户端 `Query` 类型（第七章） |
| `IndexOperations` | 索引管理 API 组 | 建删索引、exists、mapping/setting/别名、模板（6.7 节） |

## 2.3 本章小结

- 编程模型 = 注解实体 + 仓库接口 + 模板，三者分别由映射层、Repository 层、Operations 层支撑。
- SD-ES 的每个抽象都能在 ES REST API 里找到对应物；反过来，ES 的每个常用 API 组都能在 `core` 包里找到对应接口——这是"翻译器"而非"发明者"的架构。

---

# 三、地基：spring-data-commons 的 Repository 机制

> 本章对应源码：`D:\code\3rd\spring-data-commons`（本地克隆，4.2.0-SNAPSHOT，与 spring-data-elasticsearch 的 pom 依赖版本一致）。SD-ES 仓库里几乎所有 `repository` 包类都是这套机制的"填空题"，先看懂骨架再读 SD-ES 会事半功倍。

## 3.1 为什么先讲 commons

**白话**：你在 JPA 里写过 `interface UserRepository extends JpaRepository<User, Long>`，在 MongoDB 里写过 `MongoRepository`，在 ES 里写 `ElasticsearchRepository`——它们用法一致，是因为背后有一套**与存储无关的协议**：接口怎么被扫描、代理 Bean 怎么生成、方法名怎么解析、参数怎么绑定、返回值怎么包装。这套协议的实现就在 spring-data-commons。SD-ES 的 `ElasticsearchRepositoryFactory`、`ElasticsearchQueryMethod` 等类全部是对 commons 抽象的 override（工厂方法模式的填空）。

## 3.2 仓库接口族：三层能力递进

【源码证据】`org.springframework.data.repository`：

```java
public interface Repository<T, ID> { }                    // 标记接口：只用于限定泛型与触发扫描
public interface CrudRepository<T, ID> extends Repository<T, ID> {   // save/findById/findAll/delete…
public interface PagingAndSortingRepository<T, ID> extends CrudRepository<T, ID> { // findAll(Pageable/Sort)
```

SD-ES 的仓库根接口（`repository/ElasticsearchRepository.java:37`）：

```java
@NoRepositoryBean        // 告诉扫描器：别给我也造一个代理 Bean
public interface ElasticsearchRepository<T, ID> extends PagingAndSortingRepository<T, ID>, CrudRepository<T, ID> {
	Page<T> searchSimilar(T entity, String @Nullable [] fields, Pageable pageable);  // more_like_this
	<S extends T> S save(S entity, @Nullable RefreshPolicy refreshPolicy);           // @since 5.2
	<S extends T> Iterable<S> saveAll(Iterable<S> entities, @Nullable RefreshPolicy refreshPolicy);
	// deleteById/deleteAll 的 RefreshPolicy 重载同理
}
```

`@NoRepositoryBean` 的作用在 8.2 节扫描逻辑里能看到对应判断：commons 的 `RepositoryConfigurationExtensionSupport.isStrictRepositoryCandidate` 会跳过带该注解的中间接口，避免每个中间层接口都长出一个代理。

## 3.3 代理 Bean 的诞生：FactoryBean + RepositoryFactorySupport

**白话**：Spring 容器不认识你的 `ProductRepository` 接口，能注入的"实现"是一个 JDK 动态代理。生产这个代理的流水线是：扫描器为每个仓库接口注册一个 `FactoryBean` → FactoryBean 在 `afterPropertiesSet()` 里创建 `RepositoryFactorySupport` → 后者为接口织入"目标仓库实现 + 每个查询方法的拦截器" → `getObject()` 返回代理。

commons 侧的关键类（`repository/core/support/`）：

- `RepositoryFactoryBeanSupport`：实现了 `FactoryBean` + `InitializingBean`，SD-ES 的 `ElasticsearchRepositoryFactoryBean` 直接继承它（`repository/support/ElasticsearchRepositoryFactoryBean.java:36-37`），只覆写 `createRepositoryFactory()` 返回 SD-ES 工厂。
- `RepositoryFactorySupport`：`getRepository()`（`RepositoryFactorySupport.java:282`）是装配中心；三个抽象方法 `getTargetRepository()`、`getRepositoryBaseClass()`、`getQueryLookupStrategy()` 由各存储模块填空（SD-ES 的填空见 8.3）。
- `RepositoryFragments`：把"仓库接口"与"碎片实现"组合。SD-ES 用它挂接 Query by Example（8.3 节 `getRepositoryFragments`）。

## 3.4 查询方法解析的三块积木

1. **`QueryMethod`**：一个查询方法的元数据（方法、参数、返回类型、领域类型）。SD-ES 子类 `ElasticsearchQueryMethod` 额外解析 `@Query`/`@Highlight`/`@SourceFilters` 注解（8.6 节）。
2. **`PartTree`**（`repository/query/parser/PartTree.java:49`）：把 `findByLastNameAndAgeLessThan` 按 `And/Or` 切成 `OrPart`，每个 Part 再按关键字（Containing、GreaterThan、In、OrderBy…）切成 `Part`，`Part.Type` 枚举穷举了全部支持的关系运算符——第八章的对照表就是它和 ES 翻译器的"对照字典"。
3. **`ParametersParameterAccessor` / `ResultProcessor`**：前者把方法实参按声明绑定（分页/排序/动态投影参数有专门通道），后者把执行结果按方法声明的返回类型包装（`List<T>`、`Page<T>`、`SearchHit<T>`、`Flux<T>` 各走各的转换器）。SD-ES 在 `AbstractElasticsearchRepositoryQuery.execute` 里的第一步就是 `queryMethod.getResultProcessor().withDynamicProjection(parameterAccessor)`（`repository/query/AbstractElasticsearchRepositoryQuery.java:94`）。

另有两件常被忽略的公共设施：

- **`ValueExpressionDelegate`**：SpEL 与占位符求值的统一入口，SD-ES 工厂在解析每个查询方法时都会持有它（`ElasticsearchRepositoryFactory.java:100`），传给 `RepositoryStringQuery` 等实现。
- **`EntityCallbacks`**：实体生命周期回调的分发器，SD-ES 定义了四对回调（第十章）。

## 3.5 本章小结

- Repository 机制 = "标记接口 + 扫描注册（ImportBeanDefinitionRegistrar）+ FactoryBean/工厂代理 + QueryMethod/PartTree/ResultProcessor"五件套，全部住在 commons。
- SD-ES 的角色：填三个工厂空（目标实现、基类、查询策略）+ 提供一个"Criteria → ES DSL"的翻译器。理解了这个分工，第八章的每一环都有了坐标系。

---

# 四、实体映射：从 POJO 到 _source 与 mapping（重点 A）

> 本章对应源码：`core/mapping`、`core/convert`、`core/index/MappingBuilder`、`annotations`。

## 4.1 模块定位

**白话**：把一个 Java 对象存进 Elasticsearch 要解决两个方向的翻译——**写方向**：对象 → JSON 文档（`_source`）；**建索引方向**：对象结构 → mapping 定义（字段类型、分析器、格式）。SD-ES 用同一套元数据（`MappingContext` 持有的 PersistentEntity/PersistentProperty）驱动两个方向：写方向由 `MappingElasticsearchConverter$Writer` 完成，建索引方向由 `MappingBuilder` 完成。第三个隐藏方向是**读方向**：JSON 文档 → 对象，由 `$Reader` 完成，并负责把 ES 响应元数据（`_id`/`_version`/`_seq_no`）回填进实体。

## 4.2 元数据层：SimpleElasticsearchPersistentEntity / PersistentProperty

【源码证据】`core/mapping/SimpleElasticsearchMappingContext.java:40`：

```java
private static final FieldNamingStrategy DEFAULT_NAMING_STRATEGY = PropertyNameFieldNamingStrategy.INSTANCE;
```

字段命名默认**保留属性名**（不像 JPA 的驼峰转下划线；想要 kebab-case 可注入 `KebabCaseFieldNamingStrategy`，同目录下有现成实现）。

`ElasticsearchPersistentEntity`（接口，`core/mapping/ElasticsearchPersistentEntity.java`）在 commons 的 `PersistentEntity` 之上增加了 ES 特有的元数据问题，实现类 `SimpleElasticsearchPersistentEntity`（715 行）逐一回答：

| 元数据问题 | 来源注解/字段 | 用在哪 |
|---|---|---|
| 索引叫什么 | `@Document.indexName`（支持 SpEL 与日期模式，如 `logs-${#environ…}`，解析见 `resolveSpelExpression`） | `getIndexCoordinates()`：模板与仓库的每次操作都用它定位目标索引 |
| 是否启动时建索引 | `@Document.createIndex` / `alwaysWriteMapping` | 仓库 Bean 构造（8.4 节） |
| 路由值怎么取 | `@Routing("userId")` 或 `@Routing("#{'a-' + id}")` | `resolveRouting(bean)`（`SimpleElasticsearchPersistentEntity.java:411-452`）：先按属性名取属性值，再按 SpEL 求值 |
| 版本字段是谁 | `@Version`（commons 注解） | 乐观锁：保存时带 `version`，冲突时被翻译成异常（5.6 节） |
| seq_no/primary_term 字段 | 类型为 `SeqNoPrimaryTerm` 的属性 | 写路径自动携带、读路径自动回填 |
| `@IndexedIndexName` 属性 | 6.x 注解 | 保存后把**实际写入的索引名**回填到该属性（对带日期后缀的索引名特别有用） |
| 隐藏的 `_class` 类型提示 | `@Document.writeTypeHint` | 多态集合读回时定位子类（`MappingBuilder.writeTypeHintMapping`，`core/index/MappingBuilder.java:200`） |

属性侧 `SimpleElasticsearchPersistentProperty`（397 行）回答"这个属性怎么存"：是 `@Id`？是 `@Version`？是 `@Transient`？带 `@Field` 吗？字段名用注解值还是命名策略？`isSeqNoPrimaryTermProperty()` / `isIndexedIndexNameProperty()` 这类谓词供读写两方向分流。

## 4.3 注解速览：@Document 与 @Field

**@Document**（`annotations/Document.java:41`，逐属性摘录）：

| 属性 | 默认 | 语义 |
|---|---|---|
| `indexName` | 必填 | 索引名（小写；支持 SpEL） |
| `createIndex` | true | 仓库启动时是否建索引 |
| `alwaysWriteMapping` | false（5.2 引入，git 实证：5.1.0 无此属性、5.2.0 出现） | 索引已存在时也重写 mapping（只加不改） |
| `versionType` | EXTERNAL | 版本管理方式 |
| `writeTypeHint` | DEFAULT | 是否写 `_class` 提示 |
| `dynamic` | INHERIT | ES 动态 mapping 策略（TRUE/FALSE/STRICT/RUNTIME） |
| `storeIdInSource` / `storeVersionInSource` | true（5.1） | `_id`/`_version` 是否冗余存进 `_source`（读文档时可少一次元数据合并） |
| `aliases` | {}（5.4） | 建索引时顺带创建别名 |

**@Field**（`annotations/Field.java:47`，35+ 属性几乎 1:1 映射 ES 字段参数）。常用的有：`type`（FieldType 枚举）、`name`、`analyzer/searchAnalyzer`、`format/pattern`（日期）、`store`、`copyTo`、`ignoreAbove`、`dims/knnSimilarity`（向量检索）、`mappedTypeName`（5.4，直接指定映射类型名，绕过枚举）、`customIndexOptions`（**6.2**）。`FieldType` 枚举（`annotations/FieldType.java:28-88`）覆盖 text/keyword/数值/日期/范围/object/nested/ip/flattened/wildcard/dense_vector/rank_feature/version/match_only_text/annotated_text 等 30+ 种——这个枚举的成员清单本身就是一张"SD-ES 支持的 ES 类型表"。

配套注解一图流：`@MultiField`（主字段 + 多个子字段）、`@Setting`（分片数/副本数/刷新间隔/自定义 analysis，或 `settingPath` 指向 JSON 文件）、`@Mapping(mappingPath=…)`（整个 mapping 用现成 JSON）、`@JoinTypeRelation`/`@JoinTypeRelations`（parent-child join 字段）、`@CompletionField`、`@GeoPointField/@GeoShapeField`、`@Highlight`/`@HighlightField`（方法级查询高亮）、`@ValueConverter`（commons：指定属性值转换器类型）、`@IndexedIndexName`。

## 4.4 MappingBuilder：注解 → mapping JSON 的编译器

**白话**：`IndexOperations.createMapping()` 返回的那个 `Document`（就是 JSON 的 Map 表示）由 `MappingBuilder` 生成。它的主循环非常直白：遍历实体的每个属性 → 跳过 `@Transient` 与 SeqNoPrimaryTerm → 按注解决定节点形状 → 把 `@Field` 的每个非默认值写成 JSON 键。

【源码证据】`core/index/MappingBuilder.java`：

```java
public String buildPropertyMapping(Class<?> clazz) {          // :130 对外入口
	return buildPropertyMapping(...getRequiredPersistentEntity(clazz), ...); // :138
}
private void mapEntity(ObjectNode objectNode, ...) {           // :220-313
	var mappingAnnotation = entity.findAnnotation(Mapping.class);   // @Mapping 全局参数
	...
	ObjectNode propertiesNode = objectNode.putObject(FIELD_PROPERTIES); // :277 "properties":
	writeTypeHintMapping(propertiesNode);                        // :279 写 _class 提示
	entity.doWithProperties(property -> {                        // :282 遍历属性
		if (property.isAnnotationPresent(Transient.class) || isInIgnoreFields(...)) return;  // :284
		if (property.isSeqNoPrimaryTermProperty()) { ...return; }  // :288 seq_no 永不入 mapping
		buildPropertyMapping(propertiesNode, isRootObject, property);  // :297
	});
}
```

`buildPropertyMapping(属性级)`（:315 起）的分派顺序：`@Mapping(mappingPath)` 直接注入 JSON → `@Field.type` 为 GeoPoint/GeoShape 走专门分支 → Completion 走 `applyCompletionFieldMapping` → 禁用字段（`@Mapping(enabled=false)`）→ 默认路径 `addSingleFieldMapping`（:509，用 `MappingParameters` 把注解属性逐个写出）→ `@MultiField` 走 `addMultiFieldMapping`（:565，`fields` 子节点）→ join 字段走 `addJoinFieldMapping`（:531，`relations` 节点）。类级收尾是 `addDynamicTemplatesMapping`（:609，处理 `@DynamicTemplates`）。

**要点**：这个类**不解析任何 ES 响应、不发任何请求**——它是纯函数式的"注解 → JSON 编译器"，测试友好；`ReactiveMappingBuilder`（83 行）只是包了一层 Mono。

## 4.5 MappingElasticsearchConverter：读、写两个方向的装配工

**白话**：Converter 实现 `EntityReader<Object, Document>` 与 `EntityWriter<Object, Document>`。它不直接生成 JSON 字符串，而是把对象写成 `Map<String,Object>`（`Document` 就是这个 Map 的接口，`core/document/Document.java:43`：`interface Document extends StringObjectMap<Document>`），序列化交给 ES 客户端的 JsonpMapper。

**写方向**（`$Writer`，入口 `write(Object, Document)`，`MappingElasticsearchConverter.java:189`）：

```java
private void writeInternal(Object source, Map<String,Object> sink, TypeInformation<?> typeInformation) {  // :816
	Optional<Class<?>> customTarget = conversions.getCustomWriteTarget(entityType, Map.class); // 注册过的自定义转换器优先
	if (Map.class.isAssignableFrom(entityType)) { writeMapInternal(...); return; }   // Map 直写
	if (Collection.class.isAssignableFrom(entityType)) { writeCollectionInternal(...); return; }
	ElasticsearchPersistentEntity<?> entity = mappingContext.getRequiredPersistentEntity(entityType);
	addCustomTypeKeyIfNecessary(source, sink, typeInformation);   // 需要时写 _class 提示
	writeInternal(source, sink, entity);                          // :857 逐属性写入
}
```

写过程中每个属性先问 PersistentProperty：有 `PropertyValueConverter` 吗（日期/Range/Temporal 自定义格式）？是简单类型吗（走 `ConversionService`）？是嵌套对象/集合吗（递归）？是 null 吗（`@Field.storeNullValue`/`storeEmptyValue` 决定留不留）？

**读方向**（`$Reader.readEntity`，`MappingElasticsearchConverter.java:337-404`）——这是"回填"的第一个现场：

```java
R instance = instantiator.createInstance(targetEntity, propertyValueProvider);   // :352 不可变对象构造器注入
R result = readProperties(targetEntity, instance, valueProvider);                // :362 逐属性填充
if (document.hasId()) { propertyAccessor.setProperty(idProperty, document.getId()); }        // :365-373 回填 _id
if (document.hasVersion()) { ...Assert version != -1... setProperty(versionProperty, version); } // :375-385 回填 _version
if (targetEntity.hasSeqNoPrimaryTermProperty() && document.hasSeqNo() ...) { ... }           // :387-393 回填 SeqNoPrimaryTerm
if (source instanceof SearchDocument searchDocument) { populateScriptedFields(...); }        // :396 脚本字段
```

三个值得注意的细节：① 支持不可变实体——构造器参数通过 `PersistentEntityParameterValueProvider` 注入，还支持参数上的 SpEL（`ConverterAwareValueExpressionParameterValueProvider`，:746 起）；② 类型提示 `_class` 让 `computeClosestEntity`（:688）在多态场景下按文档里的实际类型实例化；③ 任何转换失败都会带上文档 id 包成 `MappingConversionException`（:400-403），报错时能定位到具体文档。

## 4.6 值转换器与自定义转换

- **`PropertyValueConverter` 体系**（`core/convert/`）：`DatePropertyValueConverter`（按 `@Field.format` 把 `java.util.Date`/`LocalDateTime` 与 ES 字符串互转，底层是 `ElasticsearchDateConverter`，362 行的纯日期工具）、`DateRangePropertyValueConverter`/`NumberRangePropertyValueConverter`（把 `Range<T>` 对象转成 ES range 字段的 GT/LT 键值对）、`GeoConverters`（`GeoPoint` ↔ `{lat,lon}`）。
- **`ElasticsearchCustomConversions`**（155 行）：用户注册自定义 `Converter` 的入口；注册后该类型成为"简单类型"（不再生成 `_class` 提示、直接走 ConversionService），这是把第三方值对象接进映射层的正门。

## 4.7 本章小结

- 元数据（PersistentEntity/Property）是映射层的单点事实来源：MappingBuilder 用它生成 mapping，Converter 用它双向读写，模板用它取索引名/路由。
- 两条回填链是理解"实体里怎么会有 id 和 version"的钥匙：写路径在 `EntityOperations.updateIndexedObject`（6.2 节），读路径在 `readEntity`。
- `@Field` 属性集 ≈ ES mapping 参数集；遇到不认识的 ES 参数，先查 `MappingParameters`/`FieldType` 再考虑 `mappedTypeName` 直写。

---

# 五、连接层：ClientConfiguration → Rest5Client → Transport → ElasticsearchClient（重点 B）

> 本章对应源码：`client`（ClientConfiguration 族）、`client/elc`（工厂与配置基类）、`client/elc/rest5_client`、`client/elc/rest_client`（传输工厂）。

## 5.1 模块定位

**白话**：这一层回答"HTTP 连接从哪来"。ES Java 客户端 9.x 的分层是：`Rest5Client`（低层 HTTP，Apache HttpClient 5）→ `ElasticsearchTransport`（请求编解码 + 异步管线）→ `ElasticsearchClient`（各 REST API 的类型安全方法）。SD-ES 在其上加了两样东西：一个**与具体客户端解耦的连接配置**（`ClientConfiguration`），和一组**工厂/配置基类**（`ElasticsearchClients`、`ElasticsearchConfiguration`）把上面的分层装成 Spring Bean。

```
ClientConfiguration（主机列表/超时/代理/SSL/默认头/路径前缀）
        │  ElasticsearchClients.getRest5Client(...)
        ▼
co.elastic.clients Rest5Client（低层 HTTP，可被 Rest5Clients 的回调深度定制）
        │  getElasticsearchTransport(...)  → new Rest5ClientTransport(rest5Client, jsonpMapper, options)
        ▼
ElasticsearchTransport
        │  createImperative(transport) / new ReactiveElasticsearchClient(transport)
        ▼
ElasticsearchClient（阻塞式，ElasticsearchTemplate 持有）
ReactiveElasticsearchClient（Mono/Flux 签名，ReactiveElasticsearchTemplate 持有）
```

## 5.2 ClientConfiguration：与具体客户端无关的"连接说明书"

【源码证据】`client/ClientConfiguration.java`（接口，396 行）——方法即配置项：`getEndpoints()`（主机+端口+协议的 `ElasticsearchHost` 列表）、`getConnectTimeout()`/`getSocketTimeout()`（:154）、`getPathPrefix()`（:163，反代场景）、`getProxy()`（:171）、`getHeadersSupplier()`（:182，每次请求动态加头，如 `X-Request-Id`）、SSL Bundle（Boot 提供的 `SslBundle`，可选）。构建走 `ClientConfiguration.builder().connectedTo("localhost:9200").build()`（`ClientConfigurationBuilder.java`）。

它**不含**任何 ES 客户端类型——这就是它存在的意义：连接配置可以在"Legacy RestClient 配置类"与"Rest5Client 配置类"之间无损复用。

## 5.3 ElasticsearchClients：五个静态工厂装配所有客户端

【源码证据】`client/elc/ElasticsearchClients.java:55`（final 工厂类）：

- `createImperative(ClientConfiguration)`（:189）：一步到位造阻塞客户端，内部 = `getRest5Client(config)` → `getElasticsearchTransport(rest5Client, IMPERATIVE_CLIENT, options, jsonpMapper)` → `new ElasticsearchClient(transport)`。
- `createReactive(ClientConfiguration)`（:72）：同上，最后 `new ReactiveElasticsearchClient(transport)`（:172-177）。
- `getElasticsearchTransport(Rest5Client, ...)`（:336-356）：组装传输，两个细节值得看——

```java
rest5ClientOptionsBuilder.addHeader(X_SPRING_DATA_ELASTICSEARCH_CLIENT,      // :351
		VersionInfo.clientVersions() + " / " + clientType);   // 值形如"<版本信息> / imperative"
return new Rest5ClientTransport(rest5Client, jsonpMapper, rest5ClientOptionsBuilder.build());  // :356
```

每个请求都会带上 `X-SpringDataElasticsearch-Client` 头（值含客户端类型 imperative/reactive），服务端日志与 APM 可以据此区分流量来源；这也解释了 `ElasticsearchClients.java:59-61` 常量 `IMPERATIVE_CLIENT`/`REACTIVE_CLIENT` 的用途。

- 需要深度定制 HTTP 层（连接池、鉴权、重试）时，用 `client/elc/rest5_client/Rest5Clients.java:41` 拿 `Rest5Client`，再通过它的四个回调接口（`ElasticsearchHttpClientConfigurationCallback`/`ElasticsearchConnectionConfigurationCallback`/`ElasticsearchConnectionManagerCallback`/`ElasticsearchRequestConfigCallback`，:208-281）以 `builder -> builder.apply(...)` 风格直接改 HttpClient5 配置。旧 RestClient 的对应物是 `client/elc/rest_client/RestClients.java:36`。

## 5.4 配置基类：一个抽象类声明整条 Bean 链

**白话**：不使用 Boot 自动配置时的推荐姿势——继承 `ElasticsearchConfiguration`（`client/elc/ElasticsearchConfiguration.java:47`，`@since 4.4`），只实现 `clientConfiguration()`，父类的 `@Bean` 方法自动把五层对象装进容器：

```java
@Bean public abstract ClientConfiguration clientConfiguration();        // :54 你来实现
@Bean public Rest5Client elasticsearchRest5Client(ClientConfiguration c) // :63 → Rest5Clients.getRest5Client(c)
@Bean public ElasticsearchTransport elasticsearchTransport(Rest5Client c, JsonpMapper m) // :78 → getElasticsearchTransport
@Bean public ElasticsearchClient elasticsearchClient(ElasticsearchTransport t) // :94
@Bean(name = {"elasticsearchOperations", "elasticsearchTemplate"})
public ElasticsearchOperations elasticsearchOperations(ElasticsearchConverter conv, ElasticsearchClient client) { // :108
	ElasticsearchTemplate template = new ElasticsearchTemplate(client, conv);
	template.setRefreshPolicy(refreshPolicy());           // 默认 null → 使用集群默认（ES 默认 IMMEDIATE 写可见性策略视场景）
	return template;
}
@Bean public JsonpMapper jsonpMapper() {                    // :124 自定义 ObjectMapper，保留 null 值
	return new Jackson3JsonpMapper(JsonMapper.builder()
			.enable(JsonNodeFeature.WRITE_NULL_PROPERTIES) ... .build());   // :129-133 支持 @Field.storeNullValue
}
```

家族其余成员：`ReactiveElasticsearchConfiguration`（:45）装响应式三件套；`ElasticsearchLegacyRestClientConfiguration`（:51）/`ReactiveElasticsearchLegacyRestClientConfiguration`（:49）是 6.0 留给旧 RestClient 的过桥类；公共的 Bean（Converter、MappingContext、CustomConversions）在更上层的 `config/ElasticsearchConfigurationSupport.java:49`（`@Configuration(proxyBeanMethods = false)`，`elasticsearchEntityMapper` :51、`elasticsearchMappingContext` :68 并扫描 `@Document` 实体、`refreshPolicy()` :164 默认 null）。

## 5.5 异常翻译：ES 错误码 → DataAccessException

**白话**：REST API 报 404（index_not_found）不该让业务代码收到一个语焉不详的 `ElasticsearchException`。SD-ES 的答案与 spring-jdbc 的 `SQLExceptionTranslator` 同构：`ElasticsearchExceptionTranslator`（`client/elc/ElasticsearchExceptionTranslator.java:48`，实现 commons 的 `PersistenceExceptionTranslator`）。

```java
public @Nullable DataAccessException translateExceptionIfPossible(RuntimeException ex) {   // :76
	if (ex instanceof ElasticsearchException elasticsearchException) {
		ErrorResponse response = elasticsearchException.response();
		if (response.status() == 404) {
			if ("index_not_found_exception".equals(errorType)) return new NoSuchIndexException(index);  // :86-97
			return new ResourceNotFoundException(errorReason);          // :99
		}
		if (errorType != null && errorType.contains("validation_exception"))
			return new DataIntegrityViolationException(errorReason);    // :107-109
		return new UncategorizedElasticsearchException(...);            // :111
	}
	if (cause instanceof IOException) return new DataAccessResourceFailureException(...);  // :114-117
}
```

`checkForConflictException`（:122-147）专门处理 409：`version conflict, required seqNo` → **`OptimisticLockingFailureException`**（`@Version` 乐观锁冲突的标准 Spring 语义），`current version [` → `VersionConflictException`。模板的每次调用都被 `execute()`（6.2 节）包住，异常翻译对仓库与模板用户透明生效。

## 5.6 本章小结

- 连接层是一条五级装配链，每级都能替换：配置（`ClientConfiguration`）→ 低层 HTTP（Rest5Client，可回调定制）→ 传输（Transport）→ 客户端（阻塞/响应式）→ 模板。
- `X-SpringDataElasticsearch-Client` 请求头是识别"谁在发请求"的捷径。
- 异常翻译把 ES 的 status+error 翻成 `DataAccessException` 语义族，`@Version` 冲突会被正确翻译成 `OptimisticLockingFailureException`。

---

# 六、Operations 模板层：save 的一生与 search 的一生（重点 C）

> 本章对应源码：`core`（Operations 接口与抽象模板）、`client/elc/ElasticsearchTemplate`、`client/elc/RequestConverter`、`client/elc/ResponseConverter`、`client/elc/SearchDocumentResponseBuilder`、`client/elc/IndicesTemplate`。

## 6.1 模块定位与接口体系

【源码证据】`core/ElasticsearchOperations.java:39`：

```java
public interface ElasticsearchOperations extends DocumentOperations, SearchOperations, ScriptOperations, SqlOperations {
	IndexOperations indexOps(Class<?> clazz);      // :46 绑定到实体的索引管理入口
	IndexOperations indexOps(IndexCoordinates index);
	ClusterOperations cluster();                   // :62（@since 4.2）
	ElasticsearchConverter getElasticsearchConverter();
	IndexCoordinates getIndexCoordinatesFor(Class<?> clazz);   // :66 从 @Document 算索引名
	ElasticsearchOperations withRouting(RoutingResolver routingResolver);      // :102 副本定制
	ElasticsearchOperations withRefreshPolicy(RefreshPolicy refreshPolicy);    // :111（@since 5.2）
}
```

`DocumentOperations`（357 行）：save/index/get/multiGet/exists/bulkIndex/bulkUpdate/delete/updateByQuery/reindex；`SearchOperations`（276 行）：count/search/searchOne/multiSearch/searchForStream/matchAllQuery/idsQuery/openPointInTime。**接口划分 = ES REST API 组划分**（javadoc 直接引用 ES 文档章节），`@since 4.0` 标注说明这是 4.0 的接口重构。

抽象模板 `AbstractElasticsearchTemplate`（875 行）实现所有"跨实现可复用"的逻辑；**阻塞实现只有 `client/elc/ElasticsearchTemplate` 一个**（845 行），持有：

```java
private final ElasticsearchClient client;         // ElasticsearchTemplate.java:87
private final RequestConverter requestConverter;  // :89 实体/查询 → ES 请求对象
private final ResponseConverter responseConverter;// :90 ES 响应 → DTO
private final ElasticsearchExceptionTranslator exceptionTranslator;  // :92
```

## 6.2 save 的一生（单实体）

**白话**：`save(entity)` 不是"发一个 index 请求"那么简单——它前面有回调、后面有回填，中间的 `IndexQuery` 是一次"待写入文档"的完整描述（id、source、version、seq_no、routing、opType）。

【源码证据】`core/AbstractElasticsearchTemplate.java:221-233`：

```java
public <T> T save(T entity, IndexCoordinates index) {
	T entityAfterBeforeConvert = maybeCallbackBeforeConvert(entity, index);   // ① BeforeConvertCallback
	IndexQuery query = getIndexQuery(entityAfterBeforeConvert);               // ② 组装 IndexQuery
	doIndex(query, index);                                                    // ③ 抽象：子类发请求
	return (T) maybeCallbackAfterSave(Objects.requireNonNull(query.getObject()), index); // ④ AfterSaveCallback
}
```

②的 `getIndexQuery()`（:564-594）值得逐行看：取实体 id（`entityOperations.forEntity(...).getId()`）→ id 转 String（`convertId`）→ 若实体有 `SeqNoPrimaryTerm` 属性则带上（:577-580，**与 version 互斥**，ES 语义），否则带 `@Version`（:581-585）→ 取路由（:587-591）。

③在实现类里（`client/elc/ElasticsearchTemplate.java:320-345`）：

```java
public String doIndex(IndexQuery query, IndexCoordinates indexCoordinates) {
	IndexRequest<?> indexRequest = requestConverter.documentIndexRequest(query, indexCoordinates, refreshPolicy); // 实体→请求
	IndexResponse indexResponse = execute(client -> client.index(indexRequest));   // 发请求 + 异常翻译
	if (query.getObject() != null) {
		query.setObject(entityOperations.updateIndexedObject(queryObject,          // ⑤ 回填四件套
				new IndexedObjectInformation(indexResponse.id(), indexResponse.index(),
						indexResponse.seqNo(), indexResponse.primaryTerm(), indexResponse.version()),
				elasticsearchConverter, routingResolver));
	}
	return indexResponse.id();
}
```

⑤的 `updateIndexedObject`（`core/EntityOperations.java:108-152`）按"实体声明了什么属性"回填：id 属性（String 型）← `response.id()`；`SeqNoPrimaryTerm` 属性 ← `seqNo+primaryTerm`；`@Version` 属性 ← `response.version()`；`@IndexedIndexName` 属性 ← `response.index()`。**读方向已在 4.5 节见过它的孪生兄弟。**

`execute()`（`ElasticsearchTemplate.java:788-797`）是所有请求的统一出口：

```java
public <T> T execute(ClientCallback<T> callback) {
	try { return callback.doWithClient(client); }
	catch (IOException | RuntimeException e) { throw exceptionTranslator.translateException(e); }
}
```

## 6.3 save 一批：bulk 与 BulkOptions

批量保存走 `save(Iterable)`（:249-273）：每个实体组一个 `IndexQuery` → `bulkIndex` → `bulkOperation`（:371-384，前后回调 + `doBulkOperation`）→ 实现 `doBulkOperation`（:397-405）把请求交给 `client.bulk(...)`。`BulkOptions`（`core/query/BulkOptions.java:39`）支持 timeout、refreshPolicy、waitForActiveShards、pipeline、routingId 五项。批量失败不会被吞：`checkForBulkOperationFailure`（`ElasticsearchTemplate.java:822-842`）发现 `bulkResponse.errors()` 时，收集每个失败文档的 status+reason 抛 `BulkFailureException`（根包下，`getFailedDocuments()` 可编程读取）。

## 6.4 search 的一生

【源码证据】`ElasticsearchTemplate.java:457-468`：

```java
protected <T> SearchHits<T> doSearch(Query query, Class<T> clazz, IndexCoordinates index) {
	SearchRequest searchRequest = requestConverter.searchRequest(query, routingResolver.getRouting(), clazz, index, false);
	SearchResponse<EntityAsMap> searchResponse = execute(client -> client.search(searchRequest, EntityAsMap.class));
	ReadDocumentCallback<T> readDocumentCallback = new ReadDocumentCallback<>(elasticsearchConverter, clazz, index);
	SearchDocumentResponseCallback<SearchHits<T>> callback = new ReadSearchDocumentResponseCallback<>(clazz, index);
	return callback.doWith(SearchDocumentResponseBuilder.from(searchResponse, getEntityCreator(readDocumentCallback), jsonpMapper));
}
```

四步：**构造 SearchRequest**（约 2000 行的 `RequestConverter.searchRequest/prepareSearchRequest`，`RequestConverter.java:1389-1536`：分页 → from/size、排序 → `getSortOptions`（注意属性名要经映射层转成 ES 字段名）、高亮 → `addHighlight`、runtime fields/rescorer/searchAfter/trackTotalHits/PIT 各就各位——这段就是"Query 对象 → ES 请求"的完整编码表）；**执行**（`EntityAsMap` 是"把 `_source` 反序列化成 Map"的中间表示）；**响应重建**（`SearchDocumentResponseBuilder.from`，`SearchDocumentResponseBuilder.java:75`：hits/shards/scrollId/pitId/aggregations/suggest 拼成 `SearchDocumentResponse`；每个 hit 经 `DocumentAdapters.from`（:75）成 `SearchDocument`）；**映射实体**（`ReadDocumentCallback.doWith`，`AbstractElasticsearchTemplate.java:777-799`：AfterLoad 回调 → converter.read → `updateIndexedObject` 回填 → AfterConvert 回调），最后 `SearchHitMapping.mappingFor(type, converter).mapHits(response, entities)`（:822）包成 `SearchHits`。

`SearchHitMapping`（`core/SearchHitMapping.java`）还要把每个命中的 `_index`、`_score`、highlight 片段（:151 `getHighlightFields`）贴到 `SearchHit` 上——**`SearchHit<T>` 就是"实体 + 命中元数据"的信封**，`unwrapSearchHits` 负责按方法声明的返回类型拆信封（8.7 节）。

## 6.5 深度遍历：searchForStream 与 PointInTime

【源码证据】`core/AbstractElasticsearchTemplate.java:437-449`：

```java
public <T> SearchHitsIterator<T> searchForStream(Query query, Class<T> clazz, IndexCoordinates index) {
	Duration scrollTime = query.getScrollTime() != null ? query.getScrollTime() : Duration.ofMinutes(1);
	int maxCount = query.isLimiting() ? query.getMaxResults() : 0;
	return StreamQueries.streamResults(maxCount,
			searchScrollStart(scrollTimeInMillis, query, clazz, index),        // 第一批 + scrollId
			scrollId -> searchScrollContinue(scrollId, scrollTimeInMillis, clazz, index), // 续批
			this::searchScrollClear);                                          // try-with-resources 关闭时清 scroll
}
```

`StreamQueries.streamResults`（`core/StreamQueries.java:51`）把"滚动批次"包成 `SearchHitsIterator`（实现了 `CloseableIterator`，**必须 try-with-resources**，否则 scroll 上下文泄漏）。阻塞式当前仍用 scroll；响应式侧已默认 PIT + search_after（9.3 节），`openPointInTime/closePointInTime` 接口（`SearchOperations.java:253-275`）也可手动使用。

## 6.6 withRouting / withRefreshPolicy：副本式定制

【源码证据】`AbstractElasticsearchTemplate.java:113-127`（`copy()`）与 `:857-872`：

```java
public ElasticsearchOperations withRouting(RoutingResolver routingResolver) {
	AbstractElasticsearchTemplate copy = copy();      // doCopy() 由子类 new 一个同配置实例
	copy.setRoutingResolver(routingResolver);
	return copy;
}
```

`RoutingResolver` 默认实现 `DefaultRoutingResolver`（`core/routing/DefaultRoutingResolver.java`）：`getRouting(bean)` 委托给实体的 `resolveRouting`（4.2 节的 `@Routing` 解析）。典型用法：父子文档场景里 `operations.withRouting(RoutingResolver.just("parent1")).save(child)`——**副本模板自带固定路由，且不影响容器里的共享 Bean**。`withRefreshPolicy` 同理（`@since 5.2`），仓库接口的 `save(entity, RefreshPolicy)` 重载内部就是这条路（8.4 节 `executeAndRefresh(callback, policy)`，`SimpleElasticsearchRepository.java:458`）。

## 6.7 IndexOperations：索引生命周期管理

**白话**：`operations.indexOps(Product.class)` 返回绑定到 `products` 索引的 `IndexOperations`，实现类 `IndicesTemplate`（`client/elc/IndicesTemplate.java:62`，继承 `ChildTemplate` 拿到 `ElasticsearchIndicesClient`）。常用链路：

```java
indexOps.createWithMapping();       // :134 = doCreate(coords, createSettings()@Setting, createMapping())
indexOps.putMapping();              // mapping 变更（只增字段）
indexOps.delete(); indexOps.exists(); indexOps.refresh();   // :153/:171/:185
indexOps.getMapping(); indexOps.getSettings(); indexOps.getInformation();  // 索引自省
indexOps.alias(...);                // AliasActions 别名原子操作
```

`doCreate`（:138-150）把 settings+mapping+别名打成 `CreateIndexSettings`，经 `requestConverter.indicesCreateRequest` 交给 `client.create(...)`。`createMapping`（:192-208）优先用 `@Mapping(mappingPath)` 指定的 classpath JSON，否则走 4.4 节的 `MappingBuilder`。索引模板/组件模板（`PutIndexTemplateRequest` 等 20 个请求模型，`core/index/` 包）也在这一组接口里。响应式侧 `ReactiveIndicesTemplate`（468 行）同构返回 Mono/Flux。

## 6.8 ClusterOperations 与脚本/SQL

`operations.cluster()` 返回 `ClusterOperations`（`core/cluster/ClusterOperations.java:24`），实现 `ClusterTemplate`（`client/elc/ClusterTemplate.java:33`）：`health()`/`getClusterInfo()`——健康检查与版本探测（模板的 `getClusterVersion()` 就是 `execute(client -> client.info()...)`，`ElasticsearchTemplate.java:410-412`）。`ScriptOperations`（put/get/delete stored scripts）与 `SqlOperations`（ES SQL 查询）也在模板上直接实现，抽象基类里是 `UnsupportedOperationException` 占位（`AbstractElasticsearchTemplate.java:733-750`）——**接口可拆分、能力按实现声明**。

## 6.9 本章小结

- save/search 两条主链路都是"**回调 → Spring 查询模型 → RequestConverter → execute（+异常翻译）→ ResponseConverter/Builder → 实体映射与回填**"六段式；记住这个模板，任意一个方法（deleteByQuery、reindex、mget）都能按图索骥。
- `RequestConverter`（2035 行）是"Spring Query 对象 → ES 请求"的编码总表，读 ES 请求参数语义时它比文档还准。
- 副本式定制（routing/refreshPolicy）与仓库的 RefreshPolicy 重载是同一机制的两个入口。
- 索引创建的自动触发不在启动监听器里，而在每个仓库 Bean 的构造函数里（8.4 节）。

---

# 七、查询体系：Query 三兄弟与 Criteria→DSL 编译器（重点 D）

> 本章对应源码：`core/query`（Query 模型）、`client/elc/NativeQuery`、`client/elc/CriteriaQueryProcessor`、`client/elc/CriteriaFilterProcessor`、`client/elc/HighlightQueryBuilder`、`core/SearchHit*`。

## 7.1 模块定位：一个接口，三种写法

【源码证据】`core/query/Query.java:46`——顶层接口声明了一次搜索的全部可调参数：

```java
public interface Query {
	Pageable getPageable();  void addSort(Sort sort);          // 分页与排序
	void addFields(String... fields);  void addSourceFilter(SourceFilter f);   // 字段裁剪
	float getMinScore();  boolean getTrackScores();            // 相关性控制
	Collection<String> getIds();                               // ids 查询（multiGet 见 :201-208）
	String getRoute();  SearchType getSearchType();            // 路由与搜索类型
	void setHighlightQuery(HighlightQuery q);                  // :275 高亮
	void addRescorerQuery(RescorerQuery q);                    // :389 二次排序
	// 另有 storedFields、indicesOptions、preference、timeout、requestCache、
	// trackTotalHits(UpTo)、searchAfter、runtimeFields、scriptedFields、pointInTime、scrollTime…
}
```

三个实现覆盖三种"写作姿势"，殊途同归于 `ElasticsearchTemplate.doSearch`：

| 实现 | 一句话定位 | 适合场景 |
|---|---|---|
| `CriteriaQuery`（`core/query/CriteriaQuery.java:29`） | 类型安全的链式谓词树，字段名经映射层翻译 | 业务代码动态拼条件（不碰 DSL） |
| `StringQuery`（`core/query/StringQuery.java:28`） | 构造器里放一段原生 JSON DSL | 已有现成查询体、注解 `@Query` |
| `NativeQuery`（`client/elc/NativeQuery.java:44`） | 直接持有 ES 客户端 `co.elastic.clients...Query`，外加聚合/建议/折叠/kNN 等"高级参数" | 需要 ES 高级特性的场景 |

公共骨架 `BaseQuery`（`core/query/BaseQuery.java:49`）实现全部公共字段；三兄弟都继承它。

## 7.2 CriteriaQuery 与 Criteria：一棵树

**白话**：`Criteria`（`core/query/Criteria.java:56`）是"字段 + 运算 + 值"的节点，节点之间按 **and（默认链式）/ or（`.or()` 分叉）/ not（`.not()` 取反）** 组成树；`CriteriaQuery` 就是包着这棵树的 Query。多条件即多节点，嵌套括号即子树（`subCriteria`）：

```java
Criteria criteria = new Criteria("category").is("laptop")
		.and("price").lessThan(9999.0)
		.or(new Criteria("tags").in("gaming", "office").not());
```

树结构与 ES bool 查询的对应关系（must/should/mustNot/filter 四个桶）由下一节的编译器决定。

## 7.3 CriteriaQueryProcessor：Criteria → bool 的翻译器

【源码证据】`client/elc/CriteriaQueryProcessor.java:60-148`（核心方法 `createQuery(Criteria)`）：

```java
for (Criteria chainedCriteria : criteria.getCriteriaChain()) {
	Query queryFragment = queryForEntries(chainedCriteria);      // 单节点 → 具体查询（match/wildcard/range…）
	if (chainedCriteria.isOr())        shouldQueries.add(queryFragment);      // :82
	else if (chainedCriteria.isNegating()) mustNotQueries.add(queryFragment); // :84
	else                               mustQueries.add(queryFragment);       // :86
}
for (Criteria subCriteria : criteria.getSubCriteria()) { ... 递归入桶 ... }  // :92-103
return new Query.Builder().bool(b -> { b.should(...); b.mustNot(...); b.must(...);
		filterQuery.ifPresent(b::filter); ... });   // :130-147 四桶组装
```

细节三则：① 同一节点上多个 `CriteriaEntry`（如 `is(a).is(b)`）内部编成 `bool.must`（:169-177）；② 字段路径含 `path` 时（nested 对象）整体包成 `nested` 查询（:181-188，`scoreMode(Avg)`）；③ 范围/exists/geo 之类"过滤性"条件由**姊妹处理器** `CriteriaFilterProcessor`（351 行）编入 `filter` 桶——**算分查询进 must、不算分的过滤进 filter**，两处理器协作完成。任何无法识别的 CriteriaEntry 都抛 `CriteriaQueryException`（宁fail-fast 不默默错译）。

## 7.4 NativeQuery：ES 高级特性的直通车

【源码证据】`client/elc/NativeQuery.java:44-56`：

```java
public class NativeQuery extends BaseQuery {
	@Nullable private final Query query;                 // co.elastic.clients 的 Query DSL（类型安全）
	@Nullable private Query filter;
	private final Map<String, Aggregation> aggregations; // 聚合
	@Nullable private Suggester suggester;               // 拼写建议
	@Nullable private FieldCollapse fieldCollapse;       // 字段折叠
	private List<KnnSearch> knnSearches;                 // kNN 向量检索
	...
}
```

builder 用法（lambda 直接写 ES DSL，编译期即校验）：`NativeQuery.builder().withQuery(q -> q.match(m -> m.field("name").query("laptop"))).withAggregation(...).build()`。两个进阶点：① `setSpringDataQuery(...)`（:120-121，`@since 5.1`）允许把一个 `CriteriaQuery`/`StringQuery` **嵌进** NativeQuery——典型场景是 kNN + 附加条件组成的混合检索；② `RuntimeField`（runtime mappings）与 `ScriptedField` 经 `addRuntimeField/addScriptedField` 注入，读取时由 4.5 节的 `populateScriptedFields` 填回实体。

`MoreLikeThisQuery`（"相似文档"）没有专门请求：模板把它翻译成 `NativeQuery` 里的一枚 `moreLikeThis` 子句（`ElasticsearchTemplate.java:489-492`）；仓库接口的 `searchSimilar` 就靠它（8.4 节）。`SearchTemplateQuery` 对接 ES 的 stored search template（`queryMethod.hasAnnotatedSearchTemplateQuery()` 分支，8.5 节）。

## 7.5 高亮、聚合、建议的结果侧

- **高亮**：请求侧 `HighlightQueryBuilder`（`client/elc/HighlightQueryBuilder.java:43`）把 `@Highlight` 注解或 `HighlightQuery` 转成 ES `Highlight` 参数；响应侧每个 `SearchDocument.getHighlightFields()` 携带片段，`SearchHit` 的 `getHighlightFields(...)` 暴露给用户。
- **聚合**：请求侧 `withAggregation`（键 + `co.elastic.clients Aggregation` 定义）；响应侧 `SearchDocumentResponse.getAggregations()` 返回 `AggregationsContainer<?>`，SD-ES 的适配实现 `ElasticsearchAggregations`（`client/elc/ElasticsearchAggregations.java`）把客户端的 `Map<String, Aggregate>` 逐个包成 `ElasticsearchAggregation`——**聚合解析不重建模型，委托给客户端自带的聚合 DTO 树**。
- **建议**：`Suggester` → 响应 `suggest` 部分，completion suggest 的选项同样经 `DocumentAdapters.from(CompletionSuggestOption)`（`DocumentAdapters.java:194`）适配。

## 7.6 本章小结

- 三种 Query = 三种抽象层级，公共参数都长在 `Query` 接口上；`RequestConverter.prepareSearchRequest`（6.4 节）是它们的共同"出口"。
- Criteria 体系是两级编译：`ElasticsearchQueryCreator`（PartTree→Criteria，第八章）与 `CriteriaQueryProcessor`（Criteria→bool）。链路上的任何一环都可注入自定义逻辑。
- ES 新特性（kNN、collapse、rescore、runtime fields）几乎全部"零延迟"可用——因为 NativeQuery 直接暴露客户端模型，SD-ES 不再造一层包装。

---

# 八、Repository 链路：从方法名到查询（重点 E）

> 本章对应源码：`repository/config`、`repository/support`、`repository/query`、`repository/query/parser`。

## 8.1 模块定位

**白话**：这是 SD-ES 最"Spring Data"的一章。目标：讲清一个接口方法从**扫描注册 → 代理生成 → 查询策略选择 → 查询构造 → 执行 → 结果包装**的完整流水线。先给全景：

```
@EnableElasticsearchRepositories
   └─ ElasticsearchRepositoriesRegistrar（ImportBeanDefinitionRegistrar）
        └─ commons 扫描器（ClassPathScanningCandidateComponentProvider）找出仓库接口
             └─ ElasticsearchRepositoryConfigExtension 提供 SD-ES 参数（FactoryBean 类名、模板引用…）
                  └─ 为每个接口注册 ElasticsearchRepositoryFactoryBean（FactoryBean）
                       └─ afterPropertiesSet() → ElasticsearchRepositoryFactory（RepositoryFactorySupport 子类）
                            ├─ getTargetRepository() → SimpleElasticsearchRepository（构造时建索引！）
                            ├─ getQueryLookupStrategy() → resolveQuery() 按方法选四种 RepositoryQuery
                            └─ getRepository() → JDK 代理（拦截查询方法 + 挂接 QBE 碎片）
```

## 8.2 注册链：@EnableElasticsearchRepositories 做了什么

【源码证据】`repository/config/EnableElasticsearchRepositories.java:44-45`：

```java
@Import(ElasticsearchRepositoriesRegistrar.class)
public @interface EnableElasticsearchRepositories { ... }
```

注解本身只是参数包（basePackages、namedQueriesLocation、queryLookupStrategy、repositoryFactoryBeanClass、`elasticsearchTemplateRef` :128）。`ElasticsearchRepositoriesRegistrar` 继承 commons 的 `RepositoryBeanDefinitionRegistrarSupport`，把扫描工作交给 commons，自身通过 `ElasticsearchRepositoryConfigExtension`（`repository/config/ElasticsearchRepositoryConfigExtension.java:47`）回答四个 SD-ES 特有问题：

- 工厂 Bean 是谁：`getRepositoryFactoryBeanClassName()` → `ElasticsearchRepositoryFactoryBean`（:56-58）；
- 模板从哪来：`postProcess()` 把 `elasticsearchTemplateRef`（默认 `elasticsearchTemplate`）注入 FactoryBean 属性（:79-83）；
- **哪些接口算数**：`getIdentifyingAnnotations()` → 只认 `@Document` 实体的仓库（:101-103）——这就是为什么实体忘了加 `@Document` 时仓库 Bean 消失的原因；
- 阻塞还是响应式：`useRepositoryConfiguration()` → 非响应式走本类，响应式由 `ReactiveElasticsearchRepositoryConfigurationExtension` 兄弟处理（:119-121）。

## 8.3 工厂：ElasticsearchRepositoryFactory 的三个填空

【源码证据】`repository/support/ElasticsearchRepositoryFactory.java:60`（`extends RepositoryFactorySupport`）：

```java
protected Object getTargetRepository(RepositoryInformation metadata) {          // :80 目标实现
	return getTargetRepositoryViaReflection(metadata, getEntityInformation(metadata.getDomainType()), elasticsearchOperations);
}
protected Class<?> getRepositoryBaseClass(RepositoryMetadata metadata) {        // :86 基类
	if (isQueryDslRepository(metadata.getRepositoryInterface()))                 // Querydsl 预留但未实现
		throw new IllegalArgumentException("QueryDsl Support has not been implemented yet.");
	return SimpleElasticsearchRepository.class;
}
protected Optional<QueryLookupStrategy> getQueryLookupStrategy(Key key, ...) {  // :99 查询策略
	return Optional.of(new ElasticsearchQueryLookupStrategy(valueExpressionDelegate));
}
protected RepositoryComposition.RepositoryFragments getRepositoryFragments(...) {  // :146 挂接 QBE
	if (QueryByExampleExecutor.class.isAssignableFrom(metadata.getRepositoryInterface()))
		fragments = fragments.append(RepositoryFragment.implemented(QueryByExampleExecutor.class,
				instantiateClass(QueryByExampleElasticsearchExecutor.class, elasticsearchOperations)));
	return fragments;
}
```

最后一行意味着：仓库接口只要同时继承 `QueryByExampleExecutor`，`findByExample(example)` 系方法就自动可用——**能力以"碎片"组合进代理，而不是塞进基类**。

## 8.4 SimpleElasticsearchRepository：目标实现与"启动建索引"

【源码证据】`repository/support/SimpleElasticsearchRepository.java:76-98`：

```java
public SimpleElasticsearchRepository(ElasticsearchEntityInformation<T, ID> metadata, ElasticsearchOperations operations) {
	this.entityInformation = metadata;
	this.indexOperations = operations.indexOps(this.entityClass);
	if (!"true".equals(System.getenv("SPRING_DATA_ELASTICSEARCH_SKIP_REPOSITORY_INIT"))) {   // :86 逃生阀
		createIndexAndMappingIfNeeded();
	}
}
public void createIndexAndMappingIfNeeded() {          // :91
	if (shouldCreateIndexAndMapping() && !indexOperations.exists())
		indexOperations.createWithMapping();           // @Document.createIndex=true 且索引不存在 → 建
	else if (shouldAlwaysWriteMapping())
		indexOperations.putMapping();                  // @Document.alwaysWriteMapping=true → 重写 mapping
}
```

**这就是"Spring Data ES 启动时自动建索引"的真身**：它发生在每个仓库代理 Bean 的目标对象构造时，因此受 `SPRING_DATA_ELASTICSEARCH_SKIP_REPOSITORY_INIT` 环境变量或 `@Document(createIndex=false)` 控制。其余 CRUD 方法都是模板调用的一层皮：`findById` → `operations.get`（:113）、`findAll(Pageable)` → `Query.findAll()` + `operations.search` + `unwrapSearchHits`（:130-140）、`count` → `setMaxResults(0)` 的 count 请求（:179-183）、`searchSimilar` → `MoreLikeThisQuery`（:244-260）。

写入方法的统一骨架是 `executeAndRefresh`（:446-464）：

```java
public <R> R execute(OperationsCallback<R> callback) { return callback.doWithOperations(operations); }
public <R> R executeAndRefresh(OperationsCallback<R> callback) {
	R result = callback.doWithOperations(operations); doRefresh();     // 默认：操作后 refresh 索引
	return result;
}
public <R> R executeAndRefresh(OperationsCallback<R> callback, @Nullable RefreshPolicy refreshPolicy) {
	return callback.doWithOperations(operations.withRefreshPolicy(refreshPolicy));   // 副本定制，不 refresh
}
```

**注意语义**：无参重载在每次 save/delete 后显式 refresh（保证测试可见性）；带 `RefreshPolicy` 参数的重载改为把策略交给请求（`refresh=false&refresh=wait_for` 等）。生产代码建议用后者，避免每写一次就 refresh 一次的段位浪费。

## 8.5 QueryLookupStrategy：四种查询来源的裁决

【源码证据】`ElasticsearchRepositoryFactory$ElasticsearchQueryLookupStrategy.resolveQuery`（:117-137）：

```java
if (namedQueries.hasQuery(namedQueryName))                                  // ① META-INF 命名查询
	return new RepositoryStringQuery(queryMethod, ops, namedQuery, valueExpressionDelegate);
else if (queryMethod.hasAnnotatedQuery())                                   // ② @Query 注解
	return new RepositoryStringQuery(queryMethod, ops, queryMethod.getAnnotatedQuery(), valueExpressionDelegate);
else if (queryMethod.hasAnnotatedSearchTemplateQuery())                     // ③ @SearchTemplateQuery
	return new RepositorySearchTemplateQuery(...);
return new RepositoryPartQuery(queryMethod, elasticsearchOperations, valueExpressionDelegate); // ④ 方法名派生
```

优先级：命名查询 > `@Query` > `@SearchTemplateQuery` > 方法名解析（`queryLookupStrategy` 默认 `CREATE_IF_NOT_FOUND`，注解 `@EnableElasticsearchRepositories.java:102`）。

## 8.6 ElasticsearchQueryMethod：方法元数据的加工厂

`repository/query/ElasticsearchQueryMethod.java`（454 行）在 commons `QueryMethod` 上增加：`@Query`（含 `count=true` 计数查询标记）、`@SearchTemplateQuery`、`@Highlight` 注解解析（`getAnnotatedHighlightQuery` 经 `HighlightConverter` 把注解参数转 `HighlightQuery`）、`@SourceFilters` 字段裁剪（:326-348，含占位符替换）。返回类型处理是它的独门功夫——`potentiallyUnwrapReturnTypeFor`（:385-405）识别 `SearchHit<T>`/`SearchHits<T>` 并**记住"用户声明了带元数据的返回类型"**（`unwrappedReturnTypeFromSearchHit`），执行后是否拆信封全靠这个标记（8.7 节）。`addSpecialMethodParameters`（:407-451）把方法参数中的 `ScriptedField`/`RuntimeField` 转移到 Query 对象上。

## 8.7 执行分派：一个 execute 处理七种方法形态

【源码证据】`AbstractElasticsearchRepositoryQuery.execute`（`repository/query/AbstractElasticsearchRepositoryQuery.java:87-143`）——所有 RepositoryQuery 共用的执行骨架：

```java
Query query = createQuery(parameters);                    // 子类：构造具体查询
if (isDeleteQuery())          result = ...delete(DeleteQuery...)+ refresh;      // :104
else if (isCountQuery())      result = operations.count(query, clazz, index);   // :108
else if (isExistsQuery())     result = operations.count(...) > 0;               // :110
else if (queryMethod.isPageQuery())   { ...search → searchPageFor → unwrap... } // :112
else if (queryMethod.isStreamQuery()) { ...searchForStream → Stream...}         // :120（默认批 500）
else if (queryMethod.isCollectionQuery()){ count 定 size → search → unwrap }    // :124
else                          result = operations.searchOne(query, clazz, index);// :136 单结果
return (queryMethod.isNotSearchHitMethod() && queryMethod.isNotSearchPageMethod())
		? SearchHitSupport.unwrapSearchHits(result) : result;                   // :140 信封拆不拆
```

集合查询会先发一个 `count` 把 `size` 设成命中总数（:128-134）——**接口返回 `List<T>` 时框架会尽力避免分页截断**。最后三行与 8.6 的标记呼应：声明 `SearchHit`/`SearchPage` 返回类型的调用者拿到带分数与高亮的信封，其余人拿到裸实体。

## 8.8 方法名 → 查询：PartTree 与翻译对照表

【源码证据】`repository/query/RepositoryPartQuery.java:38-75`：

```java
public RepositoryPartQuery(...) {
	this.tree = new PartTree(queryMethod.getName(), ...getDomainType());   // 解析方法名成谓词树
}
protected BaseQuery createQuery(ElasticsearchParametersParameterAccessor accessor) {
	BaseQuery query = new ElasticsearchQueryCreator(tree, accessor, mappingContext).createQuery();
	if (tree.getMaxResults() != null) query.setMaxResults(tree.getMaxResults());  // findFirst3By...
	return query;
}
```

`ElasticsearchQueryCreator`（`repository/query/parser/ElasticsearchQueryCreator.java:49`，继承 commons `AbstractQueryCreator<CriteriaQuery, CriteriaQuery>`）把每个 `Part.Type` 翻成一个 `Criteria` 方法。这张对照表建议整表背下（:104-126）：

| 方法名关键字 | Part.Type | Criteria 调用 → 最终 ES 查询 |
|---|---|---|
| `Is`/`Equals`（默认） | SIMPLE_PROPERTY | `.is(v)` → match/term |
| `Not` | NEGATING_SIMPLE_PROPERTY | `.is(v).not()` → mustNot |
| `Like`/`StartingWith`/`EndingWith`/`Containing` | … | startsWith/endsWith/contains → wildcard |
| `Regex` | REGEX | `.expression(re)` → regexp |
| `GreaterThan(Equal)`/`LessThan(Equal)`/`Between` | … | greaterThan(E)/lessThan(E)/between → range |
| `In`/`NotIn` | IN/NOT_IN | `.in(...)`/`.notIn(...)` → terms |
| `True`/`False` | … | `.is(true/false)` |
| `IsNull`/`IsNotNull`/`Exists` | … | exists → mustNot(exists) / exists |
| `IsEmpty`/`IsNotEmpty` | … | empty/notEmpty |
| `Within`/`Near` | … | geo_distance / bounding box（:129-170，含 GeoPoint 精确匹配退化为 1m 距离的巧思） |

组合规则：`And` 串接 criteria（:74-83），`Or` 合并两棵 CriteriaQuery 的根（:86-88），`OrderByXxxDesc` 进 `complete()` 的 `addSort`（:91-98）；字段名经 `QueryPropertyToFieldNameConverter` 从属性名换成 `@Field.name`。

## 8.9 @Query 与 SpEL：RepositoryStringQuery

【源码证据】`repository/support/QueryStringProcessor.java:59-63`：

```java
public String createQuery(ElasticsearchParametersParameterAccessor parameterAccessor) {
	String queryString = new QueryStringPlaceholderReplacer(conversionService)
			.replacePlaceholders(query, parameterAccessor);          // ① ?0 ?1 占位符 → 参数值
	QueryStringSpELEvaluator evaluator = new QueryStringSpELEvaluator(queryString, ...);
	return evaluator.evaluate();                                     // ② ?{#lastName} SpEL 求值
}
```

`RepositoryStringQuery.createQuery` 把处理后的字符串包成 `StringQuery`（`repository/query/RepositoryStringQuery.java:49-56`）——所以 `@Query("{\"bool\":{\"must\":[{\"match\":{\"lastName\":\"?0\"}}]}}")` 里的 `?0` 会先替换成方法实参（值经 ConversionService 序列化），`?{...}` 里可写完整 SpEL。

## 8.10 响应式仓库与 QBE 碎片

- 响应式侧完全同构：`ReactiveElasticsearchRepositoryFactory/FactoryBean/Metadata` + `SimpleReactiveElasticsearchRepository`（395 行，方法返回 Mono/Flux）+ `ReactivePartTreeElasticsearchQuery`。`@EnableReactiveElasticsearchRepositories` 的扩展类声明 `useRepositoryConfiguration` 里 `metadata.isReactiveRepository()` 反向分流（8.2 节）。
- Query by Example：`QueryByExampleElasticsearchExecutor`（`repository/support/querybyexample/QueryByExampleElasticsearchExecutor.java:38`）把 `Example<T>`（探针对象 + 匹配规则）经 `ExampleCriteriaMapper` 翻成 CriteriaQuery——共享 matcher 全部落回 7.2 节的 Criteria 树。

## 8.11 本章小结

- 一条链读三遍就熟：**扫描注册（8.2）→ 工厂填空（8.3）→ 目标实现与建索引（8.4）→ 查询裁决（8.5）→ 方法元数据（8.6）→ 执行分派（8.7）→ 查询构造（8.8/8.9）**。
- "方法名支持哪些关键字"的权威答案不在文档里，在 `ElasticsearchQueryCreator.from()` 的 switch 表里（8.8）。
- 三个常见踩坑的源码出处：实体没加 `@Document` → 仓库 Bean 不注册（8.2）；返回 `List<T>` 被分页截断的感觉 → 实际框架先 count 再放大 size（8.7）；测试中看不到刚保存的文档 → 默认有 executeAndRefresh，但手动用模板 save 时要自己管 RefreshPolicy（8.4/6.6）。

---

# 九、响应式栈：从 WebClient 到 transport.performRequestAsync（重点 F）

> 本章对应源码：`client/elc/ReactiveElasticsearchClient`、`client/elc/ReactiveElasticsearchTemplate`、`core/AbstractReactiveElasticsearchTemplate`。

## 9.1 模块定位与一段迁移史

**白话**：SD-ES 的响应式 API（`ReactiveElasticsearchOperations`、`ReactiveElasticsearchRepository`）是给 Spring WebFlux/Reactor 用户的同构替代品。它的底层实现经历过一次"换心"：4.x 时代响应式客户端是 **WebClient 直连 REST API**（`client/reactive/DefaultReactiveElasticsearchClient`，手工拼请求 JSON）；4.4 起 elc 栈内出现了基于 ES 客户端 transport 的第二个响应式客户端；**5.2 把 WebClient 版整体移除**（1.6 节 git 实证），6.x 的响应式客户端彻底收敛为同一个类。

## 9.2 ReactiveElasticsearchClient：Mono.fromFuture 桥接

【源码证据】`client/elc/ReactiveElasticsearchClient.java:43`：

```java
public class ReactiveElasticsearchClient extends ApiClient<ElasticsearchTransport, ReactiveElasticsearchClient> {
	public <T> Mono<IndexResponse> index(IndexRequest<T> request) {          // :97
		return Mono.fromFuture(transport.performRequestAsync(request, IndexRequest._ENDPOINT, transportOptions));
	}
	public Mono<BulkResponse> bulk(BulkRequest request) { ... 同构 ... }      // :111
	public <T> Mono<GetResponse<T>> get(GetRequest request, Class<T> tClass) { // :125 手动挂文档反序列化器
		JsonEndpoint<GetRequest, GetResponse<T>, ErrorResponse> endpoint = ...;
		endpoint = new EndpointWithResponseMapperAttr<>(endpoint, "co.elastic.clients:Deserializer:_global.get.Response.TDocument", getDeserializer(tClass));
		return Mono.fromFuture(transport.performRequestAsync(request, endpoint, transportOptions));
	}
	public ReactiveElasticsearchIndicesClient indices() { ... }               // :73 子客户端族
}
```

要点：**没有第二套网络栈**——响应式与非响应式共用同一个 `ElasticsearchTransport`，响应式的本质是把 transport 的 `CompletableFuture` 异步管线用 `Mono.fromFuture` 接进 Reactor（每个方法一行，可直接背）。`get/update/mget` 里多出来的 `EndpointWithResponseMapperAttr` 是给泛型文档类型挂反序列化器的胶水。

## 9.3 ReactiveElasticsearchTemplate：有界与无界两种搜索

【源码证据】`client/elc/ReactiveElasticsearchTemplate.java:86`（持有 `ReactiveElasticsearchClient`，方法签名全是 Mono/Flux，镜像 6.2 节的六段式链路，只是 `requestConverter` 复用同一个类）。核心分叉在 `doFind`（:464-478）：

```java
protected Flux<SearchDocument> doFind(Query query, Class<?> clazz, IndexCoordinates index) {
	boolean queryIsUnbounded = !(query.getPageable().isPaged() || query.isLimiting());
	return queryIsUnbounded ? doFindUnbounded(query, clazz, index) : doFindBounded(query, clazz, index);
}
```

**无界搜索**（`doFindUnbounded`，:480-557）是本章的精华——它展示了 ES 官方推荐的深度遍历姿势 **PointInTime + search_after** 的完整实现：

```java
Mono<PitSearchAfter> resourceSupplier = openPointInTime(index, Duration.ofMinutes(5), true)  // 开 PIT
		.map(pit -> new PitSearchAfter(baseQuery, pit));
Flux<ResponseBody<EntityAsMap>> searchResponses = Flux.usingWhen(resourceSupplier, psa -> {
	baseQuery.setPointInTime(new Query.PointInTime(psa.getPit(), pitKeepAlive));
	if (addShardDoc) baseQuery.addSort(Sort.by("_shard_doc"));      // PIT 游标专用排序字段
	SearchRequest firstSearchRequest = requestConverter.searchRequest(baseQuery, ...);
	return Mono.from(execute(client -> client.search(firstSearchRequest, EntityAsMap.class)))
			.expand(response -> {                                    // 递归：以上一批最后一条的 sort 值续拉
				var hits = response.hits().hits();
				if (CollectionUtils.isEmpty(hits)) return Mono.empty();
				baseQuery.setSearchAfter(hits.get(hits.size()-1).sort().stream()...collect(...));
				return Mono.from(execute(...));                      // :543 下一批
			});
}, this::cleanupPit, (psa, ex) -> cleanupPit(psa), psa -> cleanupPit(psa));  // 完成/出错/取消都关 PIT
return searchResponses.flatMapIterable(...).map(hit -> DocumentAdapters.from(hit, jsonpMapper));
```

与 scroll 相比的优势：无服务端游标状态、PIT 保证一致性快照、取消信号（Flux cancel）会触发 `cleanupPit` 释放 PIT——**响应式取消语义与 ES 资源生命周期被 `Flux.usingWhen` 正确缝合**。fieldCollapse 与 `_shard_doc` 排序的冲突处理（:508-523）是一个真实的边界情况修复现场。

**有界搜索**（`doFindBounded`）则按 `BaseQuery.getReactiveBatchSize()`（默认 500，`core/query/BaseQuery.java:53`）分批批量拉取。两者的结果都汇入与阻塞侧相同的 `SearchDocumentResponse` → 实体映射管线（复用 6.4 节的回填与 `SearchHitMapping`）。

## 9.4 响应式仓库

`SimpleReactiveElasticsearchRepository`（395 行）与阻塞版逐方法对应，返回类型换 Mono/Flux；`ReactivePartTreeElasticsearchQuery`/`ReactiveRepositoryStringQuery` 继承 `AbstractReactiveElasticsearchRepositoryQuery`（180 行），执行分派逻辑与 8.7 节一致（多了 `Flux` 流式分支）。响应式侧的批量写入没有"自动 refresh"语义——`AbstractReactiveElasticsearchTemplate` 不做 executeAndRefresh，写后可见性由 RefreshPolicy 显式控制（对比 8.4 节的阻塞差异）。

## 9.5 本章小结

- 6.x 响应式 = **一个 transport、两种签名**：`Mono.fromFuture(transport.performRequestAsync(...))` 一行话讲完底层。
- 无界 Flux 的实现姿势（PIT + search_after + `Flux.usingWhen`/`expand`）值得作为"响应式资源管理"的范本单独研读。
- 从 WebClient 版迁移过来的老代码需要知道：5.2 起 `client/reactive` 包与 `DefaultReactiveElasticsearchClient` 不复存在，配置方式也统一到 `ClientConfiguration`。

---

# 十、扩展件与配套：回调、审计、事件、QBE、垂直能力、AOT

## 10.1 实体回调：四对生命周期钩子

【源码证据】`core/event/`：`BeforeConvertCallback`（转换前，可改实体）、`AfterSaveCallback`（保存后）、`AfterLoadCallback`（文档→实体前，可改 Document）、`AfterConvertCallback`（转换后，可拿到 Document 与实体）；响应式各有一份（返回 Mono）。触发点已在主链路里标注（6.2 节①④、6.4 节 ReadDocumentCallback）。实现 `EntityCallback<T>` 接口并注册为 Bean 即生效，`AbstractElasticsearchTemplate.setApplicationContext` 会收集容器里所有回调（`AbstractElasticsearchTemplate.java:143-152`，`EntityCallbacks.create(applicationContext)`）。审计是回调的头号用户：`@EnableElasticsearchAuditing`（`config/EnableElasticsearchAuditing.java`）注册 `AuditingEntityCallback`，配合 commons 的 `@CreatedDate/@CreatedBy` 填审计字段。

## 10.2 Query by Example 与 SpEL 工具

QBE 碎片（8.3/8.10 节）+ `ExampleCriteriaMapper`（`repository/support/querybyexample/ExampleCriteriaMapper.java`：把 `ExampleMatcher` 的匹配规则映射成 Criteria，`IGNORE_CASE`→case-insensitive，`StringMatcher.CONTAINING`→contains 等）。`utils/spel/ExpressionUtils.java`（`@since 6.2`）集中了 `@Routing`/`@Document.indexName` 里 SpEL 的探测与求值工具。

## 10.3 垂直能力速览（core 包的十个小包）

| 包 | 能力 | 关键类型 |
|---|---|---|
| core.join | parent-child | `JoinField<T>`（实体的 join 属性类型）+ `@JoinTypeRelation(s)` 生成 mapping `relations` |
| core.geo | 地理 | `GeoPoint`、`GeoJson` 族（Point/Polygon/…自动映射 geo_shape） |
| core.script | 存储脚本 | `Script` + `ScriptOperations`（6.8 节） |
| core.sql | ES SQL | `SqlOperations`/`SqlResponse`，阻塞模板里 `search(SqlQuery)`（`ElasticsearchTemplate.java:758`） |
| core.suggest | 补全建议 | `Completion` 类型 + `@CompletionField` |
| core.reindex | 索引迁移 | `ReindexRequest/Remote`，阻塞版 `reindex/submitReindex`（`DocumentOperations.java:347-356`） |
| core.cluster | 集群 | `ClusterOperations`/`ClusterHealth`（6.8 节） |
| core.routing | 路由 | `RoutingResolver` + `@Routing`（6.6 节） |
| core.document | 文档模型 | `Document/SearchDocument`（4.5 节的中间表示） |
| core.index | 索引域模型 | MappingBuilder、Setting/别名/模板请求族（4.4/6.7 节） |

## 10.4 CDI、XML 与 AOT

- **CDI**：`repository/cdi/ElasticsearchRepositoryExtension`（非 Spring 容器的 Jakarta CDI 环境，同 commons 配方）。
- **XML**：`config/ElasticsearchNamespaceHandler.java` + `ElasticsearchAuditingBeanDefinitionParser`（`<elasticsearch:repositories/>` 时代的遗产，auditing 仍可用）。
- **AOT**：`aot/SpringDataElasticsearchRuntimeHints.java:41`（`RuntimeHintsRegistrar`：回调类型、proxy 方法签名反射注册）；`aot/ElasticsearchAotPredicates.java:26`（判定"实体是否可安全 AOT 处理"）；`repository/aot/RepositoryRuntimeHints.java` 同理。GraalVM 原生镜像下 SD-ES 的反射点（映射、代理、回调）由此声明。

## 10.5 本章小结

- 回调四对钩子覆盖了"存前改实体、存后拿元数据、读前改文档、读后拿原始 JSON"的全部横切需求；审计零成本接入。
- 垂直能力（join/geo/script/sql/reindex）的接入方式高度一致：**领域模型 + 注解 + Operations 接口段 + RequestConverter 分支**，照着 6.2 节的六段式读任何一个都能十五分钟上手。

---

# 十一、贯通视图：三条时间线看懂全貌

## 11.1 时间线一：应用启动

```
@SpringBootApplication
  → @EnableElasticsearchRepositories（或 Boot 自动配置开启）
    → Registrar 扫描出 ProductRepository（实体有 @Document，通过 identifying 检查）
      → 注册 ElasticsearchRepositoryFactoryBean（属性：elasticsearchOperations 引用、实体类型）
  → Boot/自定义配置类装配 Bean 链
    ClientConfiguration → Rest5Client → ElasticsearchTransport → ElasticsearchClient
      → ElasticsearchTemplate（持有 RequestConverter/ResponseConverter/ExceptionTranslator）
  → 容器实例化仓库 FactoryBean → afterPropertiesSet()
    → ElasticsearchRepositoryFactory → getTargetRepository()
      → new SimpleElasticsearchRepository(...)         ← 构造函数里：
         indexOps.exists() ? （@Document.alwaysWriteMapping → putMapping()） : indexOps.createWithMapping()
         （MappingBuilder 现场把 @Field 编译成 mapping JSON）
  → 仓库代理就绪，可注入
```

## 11.2 时间线二：一次 save（叠加了第四、五、六章）

```
productRepository.save(p)
  → 代理拦截 → SimpleElasticsearchRepository.save → executeAndRefresh
    → operations.save(entity, indexCoordinates)          [IndexCoordinates 来自 @Document]
      → maybeCallbackBeforeConvert                        ① BeforeConvertCallback（审计字段）
      → getIndexQuery：id/Version/SeqNoPrimaryTerm/Routing 组装 IndexQuery
      → doIndex（ElasticsearchTemplate）
        → requestConverter.documentIndexRequest           ② 实体→IndexRequest（converter 写 Map→source）
        → execute(client.index(...))                      ③ HTTP PUT /products/_doc/xxx（+异常翻译）
        → updateIndexedObject                             ④ 回填 id/version/seq_no/_index
      → maybeCallbackAfterSave                            ⑤ AfterSaveCallback
    → doRefresh()（或 withRefreshPolicy 副本交给请求参数）
```

## 11.3 时间线三：一次 findByCategoryAndPriceLessThan（叠加了第七、八章）

```
findByCategoryAndPriceLessThan("laptop", 9999.0)
  → 代理拦截 → RepositoryPartQuery.execute（AbstractElasticsearchRepositoryQuery.execute）
    → PartTree("findByCategoryAndPriceLessThan")          ① And 切分：category=… And price<…
    → ElasticsearchQueryCreator.createQuery               ② .is("laptop").and("price").lessThan(9999)
    → CriteriaQuery（含 Pageable/Sort 特殊参数合并）
    → requestConverter.searchRequest                      ③ CriteriaQueryProcessor → bool{must:[match,range]}
                                                            + 字段名翻译 + from/size + sort
    → execute(client.search(req, EntityAsMap.class))      ④ HTTP POST /products/_search
    → SearchDocumentResponseBuilder.from                  ⑤ hits → SearchDocument（hit 元数据完整保留）
    → ReadDocumentCallback：AfterLoad → converter.read（含 _id/_version 回填）→ AfterConvert
    → SearchHitMapping.mapHits → SearchHits<Product>
  → isCollectionQuery 分支：先 count 定 size（避免截断）
  → unwrapSearchHits → List<Product>
```

## 11.4 设计模式视角

| 模式 | 证据 |
|---|---|
| 工厂方法 + 策略 | `RepositoryFactorySupport` 三空由 SD-ES 填；`QueryLookupStrategy` 裁决四种查询来源 |
| 模板方法 | `AbstractElasticsearchTemplate` 定骨架，`doIndex/doSearch/...` 留给子类 |
| 适配器 | `ElasticsearchAggregations` 包客户端 Aggregate；`DocumentAdapters` 包 Hit；`IndexOperationsAdapter` 兼容旧签名 |
| 责任链/管道 | save/search 六段式；回调（EntityCallbacks）是管道上的可插拔环节 |
| 组合/解释器 | `Criteria` 树 + `CriteriaQueryProcessor`（前段）与 `PartTree` + `ElasticsearchQueryCreator`（后段）是同一模式的两次应用 |
| 不可变快照 | `withRouting/withRefreshPolicy` 的 copy 语义 |

---

# 附录

## A.1 关键接口速查表

| 接口/类 | 一句话职责 | 源码位置 |
|---|---|---|
| ElasticsearchOperations | 模板门面 = Document+Search+Script+Sql Operations | `core/ElasticsearchOperations.java:39` |
| DocumentOperations / SearchOperations | 按 REST API 组拆分的操作接口 | `core/DocumentOperations.java:43`、`SearchOperations.java:37` |
| AbstractElasticsearchTemplate | 回调编排/IndexQuery 组装/结果映射的公共骨架 | `core/AbstractElasticsearchTemplate.java:83` |
| ElasticsearchTemplate | elc 客户端的阻塞实现 | `client/elc/ElasticsearchTemplate.java:81` |
| ReactiveElasticsearchClient / Template | 响应式客户端（Mono.fromFuture 桥）/响应式模板 | `client/elc/ReactiveElasticsearchClient.java:43`、`ReactiveElasticsearchTemplate.java:86` |
| IndexOperations / IndicesTemplate | 索引生命周期 | `core/IndexOperations.java`、`client/elc/IndicesTemplate.java:62` |
| ClusterOperations | 集群健康与信息 | `core/cluster/ClusterOperations.java:24` |
| ElasticsearchRepository | 仓库根接口（PagingAndSorting+Crud + ES 增强） | `repository/ElasticsearchRepository.java:37` |
| ElasticsearchRepositoryFactory | 仓库工厂（填 commons 三空） | `repository/support/ElasticsearchRepositoryFactory.java:60` |
| SimpleElasticsearchRepository | 目标实现 + 启动建索引 | `repository/support/SimpleElasticsearchRepository.java:68` |
| ElasticsearchQueryMethod | 方法元数据（返回类型 unwrap/注解解析） | `repository/query/ElasticsearchQueryMethod.java` |
| AbstractElasticsearchRepositoryQuery | 查询执行七形态分派 | `repository/query/AbstractElasticsearchRepositoryQuery.java:49` |
| RepositoryPartQuery / StringQuery / SearchTemplateQuery | 四种 RepositoryQuery 实现（前二） | `repository/query/*.java` |
| ElasticsearchQueryCreator | PartTree → Criteria 翻译（含对照表） | `repository/query/parser/ElasticsearchQueryCreator.java:49` |
| MappingElasticsearchConverter | 对象↔文档双向映射 + 回填 | `core/convert/MappingElasticsearchConverter.java:92` |
| MappingBuilder | 注解 → mapping JSON | `core/index/MappingBuilder.java:130` |
| Query / BaseQuery / CriteriaQuery / StringQuery / NativeQuery | 查询模型三兄弟 | `core/query/Query.java:46` 等 |
| CriteriaQueryProcessor / CriteriaFilterProcessor | Criteria → bool（must/should 与 filter 两桶） | `client/elc/CriteriaQueryProcessor.java:60` |
| ClientConfiguration / ElasticsearchClients / ElasticsearchConfiguration | 连接配置/工厂/配置基类 | `client/ClientConfiguration.java`、`client/elc/…` |
| ElasticsearchExceptionTranslator | ES 错误 → DataAccessException | `client/elc/ElasticsearchExceptionTranslator.java:48` |
| EntityOperations | 实体适配 + updateIndexedObject 回填 | `core/EntityOperations.java:108` |
| SearchHit(s)/SearchHitsImpl/SearchHitMapping | 命中信封与映射 | `core/SearchHit.java`、`core/SearchHitMapping.java` |

## A.2 初学者学习路线（动手向）

1. **跑通最小切片**（2.1 节代码 + Embedded/Testcontainers Elasticsearch），用 `LoggingClientTransportListener`（客户端请求日志）观察每次操作发出的 JSON——把第十一章的三条时间线在日志里逐行找到。
2. **写全三种 Query**：同一个需求（分类 + 价格区间 + 高亮 + 聚合）分别用 CriteriaQuery、@Query、NativeQuery 实现，对照 `RequestConverter` 生成的请求差异。
3. **玩元数据回填**：给实体加 `@Version`、`SeqNoPrimaryTerm`、`@IndexedIndexName` 属性，save 后观察值的出现；再并发保存触发 `OptimisticLockingFailureException`。
4. **读一条完整链路**：断点从 `findByXxx` 打到 `client.search(...)`，逐层单步（8.1 节全景图的每一站），比读十篇博客都有效。
5. **进阶**：响应式无界遍历（9.3）、索引别名与重建（reindex + IndexOperations）、QBE、AOT 原生镜像各做一次。

## A.3 源码阅读入口清单（20 个关键文件）

按建议阅读顺序（路径相对 `src/main/java/org/springframework/data/elasticsearch/`）：

1. `core/ElasticsearchOperations.java` — 模板门面全貌
2. `core/DocumentOperations.java` / 3. `core/SearchOperations.java` — 接口即 API 地图
4. `core/AbstractElasticsearchTemplate.java` — 六段式骨架
5. `client/elc/ElasticsearchTemplate.java` — 具体实现
6. `client/elc/ElasticsearchClients.java` + 7. `client/elc/ElasticsearchConfiguration.java` — Bean 链
8. `core/query/Query.java` → 9. `core/query/BaseQuery.java` → 10. `client/elc/NativeQuery.java` — 查询模型
11. `client/elc/RequestConverter.java` — Query→请求编码总表（重点读 `prepareSearchRequest`）
12. `client/elc/SearchDocumentResponseBuilder.java` + 13. `core/SearchHitMapping.java` — 响应侧
14. `core/mapping/SimpleElasticsearchPersistentEntity.java` — 实体元数据
15. `core/index/MappingBuilder.java` — mapping 生成
16. `core/convert/MappingElasticsearchConverter.java` — 双向映射与回填
17. `repository/config/ElasticsearchRepositoryConfigExtension.java` — 注册参数
18. `repository/support/ElasticsearchRepositoryFactory.java` → 19. `repository/support/SimpleElasticsearchRepository.java` — 工厂与目标实现
20. `repository/query/AbstractElasticsearchRepositoryQuery.java` + `repository/query/parser/ElasticsearchQueryCreator.java` — 执行分派与方法名翻译

配套阅读（依赖仓库）：`spring-data-commons` 的 `repository/core/support/RepositoryFactorySupport.java`、`repository/query/parser/PartTree.java`；ES 客户端 jar 内 `co.elastic.clients.elasticsearch.core.SearchRequest` 的字段清单（`RequestConverter` 的"词典"）。

## 结语

spring-data-elasticsearch 的源码没有隐藏的"魔法时刻"：Repository 代理是 commons 五件套的填空，对象映射是两条显式的回填链，查询是两级编译器的产物，连接是一串可逐级替换的工厂。它的复杂度集中在**对应关系**——ES REST API ↔ 操作接口、mapping 参数 ↔ 注解属性、Query DSL ↔ Criteria 树——而理解对应关系的最好办法，就是带着 2.1 节那二十行代码，把第十一章的三条时间线在源码里走一遍。走完之后，`@Document`、`findByXxx`、`ElasticsearchOperations` 这些名词都会变成地图上的坐标，而不是 API 文档里的黑盒。








