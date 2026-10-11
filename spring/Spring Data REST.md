# Spring Data REST 深度源码解析（写给初学者的架构全景）

> **本文基于的源码**：`D:\code\3rd\spring-data-rest`，版本 **5.2.0-SNAPSHOT**（main 分支，Git commit `36f12fd`，2026-10 快照）。文中所有【源码证据】的文件路径与行号均为对该快照实际读取所得，行号只对该快照精确。
>
> **版本基线**：5.x 构建在 **Spring Boot 4.x / Spring Framework 7.x / Spring Data Commons 4.2 / Spring HATEOAS / Jackson 3（`tools.jackson.*` 命名空间）** 之上，且**只支持 Servlet MVC**（没有 WebFlux 版本）。对使用 Spring Boot 3.x（Spring Data REST 4.x，Jackson 2）的读者，本文的架构与机制完全一致，仅 Jackson 包名（`com.fasterxml.jackson` → `tools.jackson`）与个别 API 名（`ObjectMapper` → `JsonMapper`/`MapperBuilder`）不同。
>
> **阅读约定**：与《Spring Framework 深度源码解析》一致——每章"先白话、后源码"，先讲清"这是什么、为什么需要它"，再给出证据链（`模块/src/main/java/.../类名.java` + 方法名 + 行号 + 代码片段）。文件路径省略前缀 `spring-data-rest-`，如"core 的 `mapping/RepositoryResourceMappings.java`"即 `spring-data-rest-core/src/main/java/org/springframework/data/rest/core/mapping/RepositoryResourceMappings.java`。

## 如何读这份文档

Spring Data REST（下称 SDR）做的事一句话能说完：**把 Spring Data 仓库（Repository）自动导出成一套超媒体驱动的 REST API，一行业务代码都不用写**。但它背后的机制——仓库如何被发现、URL 如何映射、HAL 的 `_links`/`_embedded` 如何渲染、PUT 为什么"不传的字段会被清空"而 PATCH 不会、事件钩子在哪触发——藏在 240 个类、两层 HandlerMapping、一个深度定制的 Jackson 模块里。

推荐两遍读法：

- **第一遍（建立地图，1~2 小时）**：只读第一章（总览）每节开头的白话段、各章"本章小结"、以及第六章（贯通视图的三条时间线）。目标是能回答：`GET /orders/1` 从进 Tomcat 到吐出 HAL JSON 经过了哪些组件？PUT 和 PATCH 的合并语义差在哪？
- **第二遍（深入源码）**：对照【源码证据】逐行读。顺序建议：第二章（装配与导出：谁决定暴露什么）→ 第三章（控制器：一次请求的一生）→ 第四章（超媒体渲染与 JSON 合并：SDR 最有含金量的部分）→ 第五章（事件与扩展点）→ 附录（动手实验清单）。

> **一个需要先破除的误解**：SDR 不是"自动生成 CRUD Controller 的代码生成器"。它**没有任何代码生成**——它是一套**运行时元模型驱动的通用控制器**：全应用只有 6 个 Controller，用 `/{repository}`、`/{id}` 这样的通配映射吃下所有导出的仓库，靠 Spring Data 的 `PersistentEntity` 元模型在运行时"认识"每一个领域类型。理解了这一点，后面所有设计都会变得顺理成章。

---

# 一、总览：SDR 的定位、设计哲学与整体架构

## 1.1 一句话定位

**SDR 把"Repository 接口"这个数据访问抽象，原样搬到 HTTP 上，变成"资源（Resource）"抽象**：你定义好实体和 `CrudRepository`，SDR 就把它们导出为带分页、排序、查询方法、关联导航、元数据（ALPS/JSON Schema）、超媒体（HAL/HAL-FORMS）与生命周期事件的完整 REST API。

从架构上看，它站在三个项目的肩膀上：

```
        你的应用：实体 + Repository 接口 +（可选）配置
                        │ 读取（运行时反射元模型）
    ┌───────────────────┴────────────────────────────┐
    │  Spring Data Commons：Repositories（仓库注册表）、   │
    │  PersistentEntity（实体元模型）、RepositoryInvoker      │
    │ （统一调用 find/save/delete）、投影（Projection）       │
    ├────────────────────────────────────────────────┤
    │  Spring HATEOAS：Link / RepresentationModel /      │
    │  HAL & HAL-FORMS Jackson 模块 / Affordance          │
    ├────────────────────────────────────────────────┤
    │  Spring MVC：HandlerMapping / HandlerAdapter /     │
    │  参数解析器 / HttpMessageConverter / 异常解析        │
    └────────────────────────────────────────────────┘
                        │ 输出
            HTTP：HAL JSON / ALPS / JSON Schema / uri-list
```

SDR 自己只做一件事：**把这三层的"点"连成一条线**——用元模型决定"暴露什么、URL 叫什么"，用 MVC 的扩展点"接管请求"，用 HATEOAS"渲染响应"。所以读它的源码，本质上是读"如何用扩展点在别人的框架上长出一个子系统"。

## 1.2 设计哲学：读源码前先记住四句话

1. **仓库即资源（Repository as Resource）**。Repository 接口就是 HTTP 资源的唯一声明处。`findByName` 这样的查询方法自动变成 `GET /people/search/findByName?name=...`。没有 Controller，没有 DTO 转换层——**URI 空间与仓库空间一一映射**是整个项目的第一公理。
2. **超媒体优先（Hypermedia first）**。响应默认是 HAL：客户端不拼 URL，只跟随 `_links`。为此 SDR 默认把 rel 复数化（`Person` → `people`）、为每个关联属性生成导航链接、为整个系统生成 ALPS 语义描述——**"可发现的 API"优先于"紧凑的 API"**。
3. **约定全可覆盖，但永远有约定**。从资源路径（`@RepositoryRestResource(path=...)`）、rel 名（`rel=...`）、暴露开关（`exported=false`）、HTTP 方法集合（`ExposureConfiguration`）到 ID 的 URL 表示（`BackendIdConverter`），每一层都有默认值 + 注解覆盖 + 编程式配置三条路。**读源码时永远先问：这个默认值在哪定义，覆盖点在哪**。
4. **不动领域模型**。SDR 对实体零侵入：不加注解（可选注解只做覆盖）、不改字节码、不要求继承。它只通过 Spring Data 的 `PersistentEntity` **只读元模型**观察你的实体——这也意味着它的能力边界就是元模型的边界（比如它天生理解 JPA/MongoDB/Keyvalue 等任何注册了 `MappingContext` 的后端）。

## 1.3 模块分层全景

项目只有两个核心模块 + 两个辅助模块（`pom.xml` 的 `<modules>` 实证）：

| 模块 | 类数* | 一句话职责 | 内部包结构（core/webmvc） |
|---|---|---|---|
| **spring-data-rest-core** | 99 | 与 Web 无关的"规则层"：配置、资源映射元数据、事件、注解 | `annotation`（@RepositoryRestResource 等 11 个注解）· `config`（RepositoryRestConfiguration/投影/元数据开关）· `event`（10 个 RepositoryEvent + 处理器调用器）· `mapping`（ResourceMetadata 元数据族 + 导出策略）· `support`（SelfLinkProvider/EntityLookup/RelProvider）· 根包（UriToEntityConverter/AggregateReference）· `projection` · `util` |
| **spring-data-rest-webmvc** | 140 | 与 Servlet MVC 绑定的"执行层"：6 个通用控制器 + 两层 HandlerMapping + 参数解析器 + Jackson 魔改 + JSON Patch | 根包（控制器/PersistentEntityResource/HandlerMapping/Adapter/异常处理）· `config`（装配总入口 RepositoryRestMvcConfiguration + 9 个参数解析器 + Configurer）· `json`（PersistentEntityJacksonModule/DomainObjectReader/JsonSchema/排序翻译）· `json.patch`（RFC 6902 实现）· `mapping`（Associations/LinkCollector）· `alps` · `aot`（GraalVM 原生镜像提示）· `spi`（BackendIdConverter）· `support`（ETag/投影器/RepositoryEntityLinks）· `convert`（uri-list 转换器） |
| spring-data-rest-hal-explorer | 2 | 内嵌 HAL 浏览器（静态页面），通过 `spring.factories` 的 `StaticResourceProvider` 挂到 `/hal-explorer` | `halexplorer`（HalExplorer 重定向控制器 + HalExplorerConfiguration） |
| spring-data-rest-distribution | — | 发布打包 | — |
| spring-data-rest-tests | — | 五个后端集成测试工程：core（MockMvc）/jpa/mongodb/security/shop | — |

\* 类数为 `find … -name "*.java" | wc -l` 实证（含 package-info）。

**依赖方向只有一条：webmvc → core →（Spring Data Commons + Spring HATEOAS）**。core 里连 `javax.servlet`/`jakarta.servlet` 都不出现——"资源映射规则"与"HTTP 执行"严格分层。

## 1.4 模块依赖图（以各模块 pom.xml 实证）

```
                    ┌──────────────────────────────────────────┐
                    │        spring-data-rest-core             │
                    │  规则：配置 / 资源映射元数据 / 事件 / 注解      │
                    └───┬──────────────┬───────────┬───────────┘
                        │              │           │
        spring-data-commons     spring-hateoas   spring-plugin
        （Repositories/PersistentEntity （Link/RepresentationModel （PluginRegistry，
          /RepositoryInvoker/投影/          /HAL&HAL-FORMS 模块）    BackendIdConverter、
          spring-data-web 的 Pageable 解析）                     EntityLookup 的注册表）
                        │              │           │
                    ┌───┴──────────────┴───────────┴───────────┐
                    │       spring-data-rest-webmvc            │
                    │  执行：控制器 / HandlerMapping&Adapter /     │
                    │  Jackson 定制 / JSON Patch / ALPS          │
                    └───┬──────────────┬───────────┬───────────┘
                        │              │           │
                  spring-webmvc    tools.jackson  （可选）spring-orm/jakarta
                  （DispatcherServlet   （Jackson 3）    .persistence/querydsl/
                   及全部 MVC 扩展点）                  hibernate6 模块/jMolecules
                        │
        后端实现（任选，提供 MappingContext + Repository）：
        spring-data-jpa / spring-data-mongodb / spring-data-keyvalue / …
                        │
        ┌───────────────┴────────────────┐
        │ spring-data-rest-hal-explorer  │（可选：HAL 浏览器静态资源）
        └────────────────────────────────┘
```

真实依赖声明（摘自 `spring-data-rest-webmvc/pom.xml`，已验证）：

| 依赖 | 性质 | 在 SDR 中的角色 |
|---|---|---|
| spring-data-rest-core | 必须 | 规则层 |
| spring-webmvc | 必须 | 承接请求；SDR 大量使用其扩展点 |
| jackson-databind / jackson-annotations | 必须 | Jackson 3（`tools.jackson`），渲染与反序列化 |
| jakarta.servlet-api | provided | Servlet 环境 |
| spring-orm / jakarta.persistence-api | 可选 | `JpaHelper`（注册 OpenEntityManagerInViewInterceptor）与 `@EnableJpaRepositories` 场景 |
| querydsl-core | 可选 | `@QuerydslPredicate` 动态查询支持 |
| jackson-datatype-hibernate6 | 可选 | 序列化时处理 Hibernate 延迟加载代理 |
| jmolecules-ddd / jmolecules-spring | 可选 | jMolecules DDD 类型（`Association` 等）与 URI 互转（classpath 存在即激活，见 `RestControllerImportSelector.java:33-52`） |
| spring-data-keyvalue | 可选 | Map/Key-Value 仓库的测试支持 |

而 **core 的 pom** 只依赖 spring-data-commons、spring-hateoas、spring-plugin（`PluginRegistry`）与 validation-api——再次印证"规则层不碰 Web"。

## 1.5 关键问题 → SDR 方案映射（全文导览）

| 初学者的关键问题 | SDR 的方案 | 详见 |
|---|---|---|
| 不写 Controller，Repository 怎么变成 REST API？ | 仓库扫描 + ResourceMappings 元数据 + 6 个通配控制器 + 专属 HandlerMapping | 第二章、第三章 |
| URL 里的复数名 `people` 从哪来？ | `EvoInflectorLinkRelationProvider` 复数化 rel，`@RepositoryRestResource(path/rel)` 覆盖 | 2.5 |
| 哪些仓库导出、哪些不导出？ | `RepositoryDetectionStrategy` 四策略 + `@RestResource(exported=false)` 三层级 | 2.4、5.4 |
| HAL 的 `_links`/`_embedded` 怎么生成的？ | `PersistentEntityResource` 信封 + `LinkCollector` + 定制 Jackson 模块 | 4.2~4.5 |
| 响应里的 id、关联字段怎么"消失"了？ | `AssociationOmittingSerializerModifier`：关联渲染成链接、id/version 默认隐藏 | 4.5 |
| PUT 没传的字段为什么被清空，PATCH 却不会？ | `DomainObjectReader` 两套合并语义（PUT 全量覆盖 / PATCH 递归合并） | 4.7 |
| JSON Patch / Merge Patch 怎么落到实体上？ | `JsonPatchHandler` + `json.patch` 包（SpEL 路径直接绑定实体，非 JSON 树） | 4.8 |
| 排序参数写 JSON 字段名还是 Java 属性名？ | `JacksonMappingAwareSortTranslator` 做"渲染名 → 持久化名"翻译 | 4.9 |
| 想只返回部分字段（列表瘦身）？ | `@Projection` 接口投影 + `?projection=` 参数 + excerpt 摘要 | 4.3 |
| 业务钩子（保存前后做点什么）？ | 10 个 `RepositoryEvent` + `@RepositoryEventHandler` / `ApplicationListener` / 校验监听器 | 5.1~5.2 |
| 并发冲突怎么防？ | `ETag`（取自 `@Version` 属性）+ `If-Match` → 412；`If-None-Match` → 304 | 3.3、4.x |
| 多对多关联怎么改？ | `/{repo}/{id}/{property}` 关联资源 + `text/uri-list` 请求体 | 3.5 |
| 前端想要 API 文档？ | `/profile`（ALPS 语义描述 + JSON Schema） | 3.6 |
| 404/405/409/412 是谁抛的？ | `RootResourceInformation.verifySupportedMethod` + `RepositoryRestExceptionHandler` | 3.2、3.8 |
| 怎么插自己的控制器？ | `@RepositoryRestController`（进入仓库 URI 空间）vs `@BasePathAwareController`（只共享 basePath） | 5.6 |
| URI 里不想暴露数据库自增 ID？ | `BackendIdConverter` SPI（URI ID 与存储 ID 解耦） | 2.7 |

## 1.6 版本演进：1.x → 5.x 关键变化对比

### 1.6.1 版本时间线（git tag 实证 + 官方发布记录）

`git ls-remote --tags origin` 可复现全部标签。关键节点：

| 版本 | 大约时间 | 基线与主题 |
|---|---|---|
| 1.0.0.M1 → 1.0 GA | 2012-04（M1）→ 2013 | 初版：Servlet 直接暴露 Repository；Jon Brisbin 主创，Oliver Gierke 接棒 |
| 2.0 GA | 2014 | **推倒重写**：以 Spring HATEOAS 为基础，引入 HAL 超媒体、`text/uri-list` 关联管理、`/search`；此后架构骨架稳定至今 |
| 2.5 GA | 2016（Ingalls） | `RepositoryDetectionStrategy`、excerpt 摘要投影（`@since 2.5` 的 javadoc 实证） |
| 3.0 GA | 2017-10（Kay） | 对齐 Spring Framework 5 / Boot 2 / Jackson 2.9；明确只做 Servlet MVC |
| 3.1 GA | 2018-09（Lovelace） | `exposeRepositoryMethodsByDefault`（查询方法默认全导出） |
| 3.4 GA | 2020-10（2020.0） | **hal-explorer 模块**、`RestControllerImportSelector` 按需装配（`@since 3.4`） |
| 3.5 GA | 2021-05（2021.0） | **HAL-FORMS** 媒体类型与 Affordance 支持（`@since 3.5`） |
| 4.0 GA | 2022-11（2022.0） | Spring Boot 3 / Framework 6 / **javax→jakarta**；新增 `aot` 包支持 GraalVM 原生镜像 |
| 5.0 GA | 2025-11（2025.1） | Spring Boot 4 / Framework 7 / **Jackson 2→3（`tools.jackson`）**，`ObjectMapper` 世界切换为 `JsonMapper`/`MapperBuilder` |
| 5.1 GA / 5.2（本文快照） | 2026-05 / 2026-10 | 5.2 新增 PUT 时 `retainIdentifierAndVersion`（见 4.7）：请求体中的 id/版本字段强制回填已存值，防客户端篡改，对 record 等不可变类型同样生效 |

### 1.6.2 对初学者的意义：哪些知识会过时，哪些永远有效

- **会过时的**：媒体类型细节（Jackson 2/3 包名、`application/hal+json` 与 `application/vnd.hal+json` 的协商顺序）、Boot 属性前缀下的个别默认值。
- **永远有效的**：**资源映射元数据模型**（`ResourceMetadata`/`CollectionResourceMapping`）、**两层 HandlerMapping 的路由哲学**、**PUT/PATCH 双语义的合并器**、**事件模型**、**"6 个通配控制器 + 元模型"的运行时导出思想**。这些自 2.0（2014）确立后未变——读 5.x 源码，也是在读 2.x。

## 1.7 全文章节地图

- **第二章 引导与导出机制**：`RepositoryRestMvcConfiguration` 装配全景（30+ Bean 分组解读）、Boot 自动装配、仓库检测四策略、ResourceMappings 元数据族、两层 HandlerMapping 与 `DelegatingHandlerMapping` 的"让路"哲学、BaseUri 与 BackendId。
- **第三章 控制器层**：6 个控制器逐个走读；重点 `RepositoryEntityController` 逐方法源码分析（GET/POST/PUT/PATCH/DELETE 全流程）；`/search` 查询方法导出；关联资源；`/profile` 元数据；异常翻译表。
- **第四章 超媒体与 JSON 渲染管线（全项目含金量最高）**：`PersistentEntityResource` 信封、投影与摘要、定制 Jackson 模块（关联→链接、id 隐藏、嵌套实体）、`LinkCollector`、`DomainObjectReader` 的 PUT/PATCH 双语义（逐段源码）、JSON Patch 的 SpEL 路径绑定、分页排序翻译层。
- **第五章 事件、校验与扩展点**：10 个事件全表与发布点、三种监听方式、`RepositoryRestConfigurer` 八个钩子、暴露控制全家桶、`EntityLookup`、自定义控制器、CORS、AOT。
- **第六章 贯通视图**：应用启动、`GET /people/1`、`PUT /people/1` 三条时间线叠加成全景；四个设计模式视角。
- **第七章 附录**：接口速查表、最小可运行示例（curl + 预期 HAL 输出）、学习路线、源码阅读入口清单、与手写 Controller 的取舍。

---

# 二、引导与导出机制：谁发现了 Repository，谁决定暴露什么

## 2.1 本章定位

这一章回答三个问题：**SDR 的 30 多个 Bean 是怎么装配起来的（2.2~2.3）；一个 Repository 接口怎么被识别并生成资源元数据（2.4~2.5）；`/people/1` 这样的请求怎么被 SDR 的 HandlerMapping 接住、而不导出的请求怎么"让路"给普通 MVC（2.6）**。本章是后面一切的地基。

## 2.2 装配总入口：RepositoryRestMvcConfiguration

【源码证据】webmvc 的 `config/RepositoryRestMvcConfiguration.java:167-173`

```java
@Configuration(proxyBeanMethods = false)
@EnableHypermediaSupport(type = { HypermediaType.HAL, HypermediaType.HAL_FORMS })
@Import({ RestControllerImportSelector.class, //
        SpringDataJackson3Configuration.class, //
        EnableSpringDataWebSupport.QuerydslActivator.class })
public class RepositoryRestMvcConfiguration extends HateoasAwareSpringDataWebConfiguration
        implements BeanClassLoaderAware, EmbeddedValueResolverAware {
```

三行注解各有深意：

- `@EnableHypermediaSupport(HAL, HAL_FORMS)`：引入 Spring HATEOAS 的超媒体基础设施（HAL Jackson 模块、rel 提供者等），SDR 在其上叠加自己的转换器。
- `@Import(RestControllerImportSelector)`：**按 classpath 条件装配**（webmvc 的 `config/RestControllerImportSelector.java:33-52`）——总是导入 `RestControllerConfiguration`（注册 6 个控制器）；classpath 上有 hal-explorer 就导入 `HalExplorerConfiguration`；有 jMolecules 就导入 `JMoleculesConfigurer`。这就是"可选功能零配置"的实现。
- 继承 `HateoasAwareSpringDataWebConfiguration`：免费获得 Spring Data Web 的分页/排序参数解析器（`HateoasPageableHandlerMethodArgumentResolver` 等），SDR 再在其上覆写参数名配置。

### 2.2.1 Bean 全景（按职责分组）

`RepositoryRestMvcConfiguration` 里定义了 30+ 个 Bean。初学者不必逐个背，按六组理解即可（全部为源码实证，行号见表）：

| 分组 | Bean（方法名） | 行号 | 白话职责 |
|---|---|---|---|
| **元数据** | `repositories()` | 310 | `Repositories`（data-commons）：扫描容器中所有 Spring Data 仓库 bean 的注册表，**一切导出的起点** |
| | `persistentEntities()` | 321 | 聚合所有后端的 `MappingContext`，统一暴露 `PersistentEntity` 元模型 |
| | `resourceMappings()` | 714 | `RepositoryResourceMappings`：仓库 → 资源元数据（路径/rel/导出开关/支持的方法），见 2.5 |
| | `repositoryRestConfiguration()` | 374-393 | 全局配置单点：同时扫描实体所在包的 `@Projection` 接口（`getProjections`，977-990，`AnnotatedTypeScanner`）并调用所有 `RepositoryRestConfigurer` |
| **转换** | `defaultConversionService()` | 328-345 | 注册 `UriToEntityConverter`（URI→实体，337）、`StringToAggregateReferenceConverter`、`StringToLdapNameConverter` |
| | `backendIdConverterRegistry()` | 850-858 | 所有 `BackendIdConverter` 组成插件注册表，永远兜底追加 `DefaultIdConverter` |
| **参数解析** | `repoRequestArgumentResolver()` | 421-443 | 组装 `RootResourceInformation`；classpath 有 Querydsl 时换成 `QuerydslAware…` 变体（426-439） |
| | `persistentEntityArgumentResolver()` | 493-505 | 读请求体为实体：内部持 `DomainObjectReader`（500，PUT/PATCH 合并语义）与 `BindContextFactory` |
| | `pageableResolver()/sortResolver()` | 826-848 | 覆写父类：`page`/`size`/`sort` 参数名与默认页大小/最大页大小全部来自 `RepositoryRestConfiguration` |
| | `defaultMethodArgumentResolvers()` | 895-916 | **有序解析器清单**：排序翻译器（`JacksonMappingAwareSortTranslator`，900）打头，随后 RootResourceInformation、PersistentEntityResource、ResourceMetadata、HttpMethod、Assembler、BackendId、ETag——SDR 控制器方法签名里所有"神奇参数"都出自这里 |
| **HTTP 管线** | `restHandlerMapping()` | 679-712 | **两层 HandlerMapping**：`BasePathAwareHandlerMapping`（698）+ `RepositoryRestHandlerMapping`（687），包进 `DelegatingHandlerMapping`（711） |
| | `repositoryExporterHandlerAdapter()` | 638-669 | 专属 `HandlerAdapter`：只服务 SDR 控制器，装上前述参数解析器 + `ResponseBodyAdvice`（HAL-FORMS 降级 660、ALPS 662-664） |
| | `extendHandlerExceptionResolvers()` | 754-773 | 往 MVC 异常解析器链**第 0 位**插入一个绑定 `RepositoryRestExceptionHandler` 的解析器（772） |
| **消息转换** | `defaultMessageConverters()` | 787-818 | ALPS →（HAL/Jackson，顺序取决于默认媒体类型，802-809）→ HAL-FORMS → 兜底 JSON → uri-list，最后交 `configurerDelegate.configureHttpMessageConverters` 追加用户转换器 |
| | `objectMapper()/halObjectMapper()` | 528-530 / 606-620 | 两个 JsonMapper：普通 JSON 与 HAL；都挂 `persistentEntityJackson3Module`（725-735），HAL 版再加 `HalJacksonModule` + rel 提供者 |
| **渲染支持** | `linkCollector()` | 737-743 | `DefaultLinkCollector`：为实体收集 self + 关联链接（可被 `customizeLinkCollector` 换掉） |
| | `excerptProjector()` | 745-752 | `DefaultExcerptProjector`：excerpt 摘要投影器 |
| | `selfLinkProvider()` | 873-878 | `DefaultSelfLinkProvider`：给实体造 self 链接（可经 `EntityLookup` 用业务键） |
| | `entityLinks()` | 471-486 | `RepositoryEntityLinks`：感知资源映射的 `EntityLinks`（`linkToCollectionResource` 等） |
| | `httpHeadersPreparer()` | 868-871 | 准备 ETag/Last-Modified 响应头 |
| **事件校验** | `annotatedEventHandlerInvoker()` | 411-414 | `@RepositoryEventHandler` 注解处理器的收集与调用者 |
| | `validatingRepositoryEventListener()` | 351-359 | 校验器调度器：validator 挂到 beforeCreate 等事件上 |
| **杂项** | `jpaHelper()` | 361-369 | 仅当 JPA 在 classpath（175 行探测 `jakarta.persistence.EntityManager`）时注册，用于挂 OEMIV 拦截器 |
| | `baseUri()` | 406-409 | 全局 basePath 的值对象 |
| | `repositoryInvokerFactory()` | 780-785 | `UnwrappingRepositoryInvokerFactory` 包 `DefaultRepositoryInvokerFactory`（273-274 也有一个）：调用前先经 `EntityLookup` 解包 |

### 2.2.2 一个值得学的工程细节：Lazy 字段破循环

`RepositoryRestMvcConfiguration` 构造器（218-294）里几乎全部依赖都以 `Lazy.of(() -> context.getBean(X.class))` 持有——**不在装配期急切解析，而在首次使用时才取**。原因：SDR 的 Bean 图天然成环（`resourceMappings` 需要 `repositoryRestConfiguration`，后者又要扫描 `repositories`，而 `repositories` 的后处理又会触发 mappings…）。用 `Lazy` + `ObjectProvider` 把"谁先谁后"变成"用到才算"，是把复杂 Bean 图装进一个 `@Configuration` 的实用手法。

## 2.3 Spring Boot 自动装配：DataRestAutoConfiguration

【源码证据】`D:\code\3rd\spring-boot` 的 `module/spring-boot-data-rest/.../DataRestAutoConfiguration.java`

```java
@AutoConfiguration(before = DataWebAutoConfiguration.class, after = JacksonAutoConfiguration.class)
@ConditionalOnWebApplication(type = Type.SERVLET)
@ConditionalOnMissingBean(RepositoryRestMvcConfiguration.class)
@ConditionalOnClass(RepositoryRestMvcConfiguration.class)
@EnableConfigurationProperties(DataRestProperties.class)
@Import(RepositoryRestMvcConfiguration.class)
public final class DataRestAutoConfiguration { … }
```

逐条解读：

- `@ConditionalOnWebApplication(SERVLET)`：再次强调 SDR 只有 Servlet 一条路，WebFlux 应用引了也不会生效。
- `@ConditionalOnMissingBean(RepositoryRestMvcConfiguration.class)`：想完全接管？自己写一个同类型 `@Configuration` 即可让 Boot 的导入让位。
- `@Import(RepositoryRestMvcConfiguration.class)`：**Boot 不重复造轮子**，直接导入 SDR 的装配类——所以"Boot 下的 SDR"与"裸 Spring MVC 下的 SDR"是同一套 Bean。
- `before = DataWebAutoConfiguration`：保证 Spring Data Web 的分页解析器先就位，SDR 才能在其上覆写参数名。

Boot 的另一个 Bean `SpringBootRepositoryRestConfigurer`（`@Order(0)`，同目录）实现 `RepositoryRestConfigurer` 接口，把 `spring.data.rest.*` 属性（`DataRestProperties`：basePath、defaultPageSize、maxPageSize、pageParamName、limitParamName、sortParamName、detectionStrategy、defaultMediaType、returnBodyOnCreate/Update/Delete、enableEnumTranslation）在 `configureRepositoryRestConfiguration` 阶段灌进 `RepositoryRestConfiguration`，并把用户的 `JsonMapperBuilderCustomizer` 应用到 SDR 的 Jackson builder。

**非 Boot 环境**也可以直接用 `RepositoryRestDispatcherServlet`（webmvc 根包，33 行的小类）：它只是一个把 `AnnotationConfigWebApplicationContext` 指向 `RepositoryRestMvcConfiguration` 的 `DispatcherServlet`——SDR 最初就是这么独立运行的。

## 2.4 仓库检测：Repositories 与 RepositoryDetectionStrategy

导出的第一步是**找出所有仓库**。data-commons 的 `Repositories`（`RepositoryRestMvcConfiguration.repositories()`，310-313）遍历容器中所有 `Repository` 类型的 bean。第二个问题是**要不要导出**——答案在 core 的 `mapping/RepositoryDetectionStrategy.java:33-127`：

```java
public static enum RepositoryDetectionStrategies implements RepositoryDetectionStrategy {
    ALL { public boolean isExported(RepositoryMetadata metadata) { return true; } },

    DEFAULT {
        public boolean isExported(RepositoryMetadata metadata) {
            return isExplicitlyExported(metadata.getRepositoryInterface(),
                    isExplicitlyExported(metadata.getDomainType(), VISIBILITY.isExported(metadata)));
        }
    },

    VISIBILITY { … /* 仅 public 仓库接口 */ },

    ANNOTATED { … /* 仅显式注解且 exported != false */ };
```

| 策略 | 导出条件 |
|---|---|
| `ALL` | 全部导出 |
| `DEFAULT`（默认） | 仓库接口 public，或仓库接口/实体类上显式标了 `@RepositoryRestResource`/`@RestResource`；显式标了 `exported=false` 则关闭 |
| `VISIBILITY` | 仅 public 接口 |
| `ANNOTATED` | 仅注解了 `@RepositoryRestResource`/`@RestResource` 的 |

配置入口：`spring.data.rest.detection-strategy` 或 `RepositoryRestConfiguration.setRepositoryDetectionStrategy(...)`。注意**最低一层开关**：无论策略如何，`@RepositoryRestResource(exported = false)` 永远可以关掉单个仓库——策略决定"候选集"，注解决定"最终进出"。

## 2.5 ResourceMappings：仓库 → 资源元数据的缓存层

检测过的仓库要变成"资源"，需要回答：URL 路径叫什么？rel 叫什么？支持哪些 HTTP 方法？集合资源/单项资源/搜索资源分别是什么？这些答案全部缓存在 `ResourceMappings` 体系里：

【源码证据】core 的 `mapping/RepositoryResourceMappings.java:67` 起（构造器 57-66 调用 `populateCache`）

```java
private void populateCache(PersistentEntities entities, RepositoryRestConfiguration configuration) {

    for (PersistentEntity<?, ? extends PersistentProperty<?>> entity : entities) {
        Class<?> type = entity.getType();
        if (!repositories.hasRepositoryFor(type)) { continue; }        // 没仓库的实体不导出
        RepositoryInformation repositoryInformation = repositories.getRequiredRepositoryInformation(type);

        RepositoryDetectionStrategy strategy = configuration.getRepositoryDetectionStrategy();
        LinkRelationProvider provider = configuration.getLinkRelationProvider();

        CollectionResourceMapping mapping = new RepositoryCollectionResourceMapping(repositoryInformation, strategy, provider);
        RepositoryAwareResourceMetadata information = new RepositoryAwareResourceMetadata(entity, mapping, this, repositoryInformation);
        addToCache(repositoryInterface, information);
        if (!hasMetadataFor(type) || information.isPrimary()) {        // 同一实体多个仓库时保留主仓库
            addToCache(type, information);
        }
    }
}
```

要点：

1. **遍历的是 `PersistentEntity` 而不是仓库**——以实体为中心建元数据，仓库只是"有没有"与"主次"的判定条件。
2. **rel 的复数化**：`RepositoryCollectionResourceMapping` 默认 rel/路径由 `LinkRelationProvider` 决定，Spring HATEOAS 的 `EvoInflectorLinkRelationProvider` 用 Evo Inflector 词形变化算法把 `Person` 变成 `people`、`Box` 变成 `boxes`。`@RepositoryRestResource(path = "members", rel = "members")` 在此覆盖。
3. **属性级与方法级元数据**：父类 `PersistentEntitiesResourceMappings`（同目录，162 行）还维护 `mappingCache`（实体 → `MappingResourceMetadata`，逐属性判定导出）与 `propertyCache`（`PersistentPropertyResourceMapping`）——**关联属性要不要导出成链接，就查这里**。
4. **查询方法元数据**：`getSearchResourceMappings(domainType)`（97 起）遍历 `repositoryInformation.getQueryMethods()` 建 `RepositoryMethodResourceMapping`（`@RestResource` 标在方法上可覆盖 path/rel；`exposeRepositoryMethodsByDefault` 默认 `true`，即**所有查询方法默认导出**，`@RestResource(exported=false)` 可逐个关闭），缓存进 `searchCache`。

这一层缓存好后，**控制器、HandlerMapping、链接生成、ALPS/JSON Schema 全都查同一份元数据**——"单一事实来源"是 SDR 各组件不打架的关键。

## 2.6 路由接管：两层 HandlerMapping 与 DelegatingHandlerMapping

SDR 的请求路由是整个项目最"Spring MVC 功底"的部分。装配处见 `RepositoryRestMvcConfiguration.restHandlerMapping()`（679-712）：先建 `BasePathAwareHandlerMapping`，再建其子类 `RepositoryRestHandlerMapping`，最后包进 `DelegatingHandlerMapping`。

### 2.6.1 BasePathAwareHandlerMapping：加前缀 + 改 produces

【源码证据】webmvc 的 `BasePathAwareHandlerMapping.java:106-140`

```java
@Override
protected @Nullable RequestMappingInfo getMappingForMethod(Method method, Class<?> handlerType) {

    RequestMappingInfo info = super.getMappingForMethod(method, handlerType);
    if (info == null) { return null; }

    ProducesRequestCondition producesCondition = customize(info.getProducesCondition());
    Set<MediaType> mediaTypes = producesCondition.getProducibleMediaTypes();
    String[] customPrefixes = getBasePathedPrefixes(handlerType);
    Builder builder = info.mutate();

    if (customPrefixes.length != 0 || StringUtils.hasText(baseUri)) {
        builder = builder.paths(resolveEmbeddedValuesInPatterns(customPrefixes));  // 把 basePath 拼进路径
    }

    return builder.produces(…).build().combine(info);
}
```

它对**所有 `@BasePathAwareController`/`@RepositoryRestController` 标注的类**做两件事：把全局 `basePath`（默认空）拼到映射前；改写 `produces` 条件。子类 `RepositoryRestHandlerMapping.customize()`（188-202）把空的 produces 条件替换为默认媒体类型集合（配置的 defaultMediaType、HAL、VND_HAL、application/json、HAL-FORMS）——这就是"控制器上不用写 produces 也能按 Accept 协商"的原因。

另外它的 `lookupHandlerMethod`（73 行起）还有个巧妙兜底：请求的 `Accept` 里没带 SDR 默认媒体类型时，**包装 request 悄悄补一个**再匹配——保证 `Accept: */*` 的 curl 也能命中 `produces=application/hal+json` 的映射。

### 2.6.2 RepositoryRestHandlerMapping：导出校验的守门人

【源码证据】webmvc 的 `RepositoryRestHandlerMapping.java:141-202`

```java
@Override
protected @Nullable HandlerMethod lookupHandlerMethod(String lookupPath, HttpServletRequest request) throws Exception {

    HandlerMethod handlerMethod = super.lookupHandlerMethod(lookupPath, request);
    if (handlerMethod == null) { return null; }

    String repositoryLookupPath = new BaseUri(configuration.getBasePath()).getRepositoryLookupPath(lookupPath);

    // Repository root resource —— 已经是仓库根（如 "/people"）或 basePath 本身
    if (!StringUtils.hasText(repositoryLookupPath)) { return handlerMethod; }

    String repositoryBasePath = getRepositoryBasePath(repositoryLookupPath);   // 取第一段：people

    if (!mappings.exportsTopLevelResourceFor(repositoryBasePath)) { return null; }  // ★ 不导出 → 不接

    exposeEffectiveLookupPathKey(handlerMethod, request, repositoryBasePath);   // 供观测/后续解析使用
    return handlerMethod;
}

@Override
protected @Nullable HandlerMethod handleNoMatch(…) { return null; }             // 匹配不上也交还
```

三个关键行为：

1. **`exportsTopLevelResourceFor`（159 行）**：拿 URL 第一段去 `ResourceMappings` 里查"有没有导出的资源用这个路径"。没有 → 返回 `null`。HandlerMapping 返回 null 的语义是"我不处理"，`DispatcherServlet` 会继续问下一个 HandlerMapping——**"不导出的仓库自然落回 404 或你的其他控制器"，不需要任何 if/else**。
2. **`handleNoMatch` 直接返回 null（169-172）**：即使 URL 形似仓库资源但方法/媒体类型对不上，也让位，而不是抢着抛 405/406。这正是与普通 `@RequestMapping` 控制器共存的关键。
3. **`isHandlerInternal`（175-177）只认 `@RepositoryRestController`**：所以 SDR 的 6 个控制器不会被 Boot 的普通 MVC HandlerMapping 重复注册。

另有三个附属行为：

- **OEMIV**：`extendInterceptors`（180-185）在 JPA 在场时注册 `OpenEntityManagerInViewInterceptor`——序列化 HAL 时会触碰懒加载关联，必须有打开的 `EntityManager`，否则渲染阶段 `LazyInitializationException`。
- **CORS**：仓库接口上标 `@CrossOrigin` 即可按仓库配 CORS（内部类 `RepositoryCorsConfigurationAccessor`，301-419），与全局 CORS 配置 `combine`（205-215）。
- **观测**：`exposeEffectiveLookupPathKey`（238-258）把 `/people/search/xx` 这类"实际路径"写进 request 属性与 `ServerHttpObservationFilter` 的 observation context，让 Micrometer 观测看到真实路径而不是 `/{repository}/search/{search}` 模板。

### 2.6.3 DelegatingHandlerMapping：order 让位 + 异常穿透

【源码证据】webmvc 的 `config/DelegatingHandlerMapping.java:73-80, 89-116`

```java
@Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE - 100; }

@Override public @Nullable HandlerExecutionChain getHandler(HttpServletRequest request) throws Exception {
    return HandlerSelectionResult.from(request, delegates).resultOrException();
}
```

两个设计点：

1. **`getOrder()` = LOWEST_PRECEDENCE - 100**：`DispatcherServlet` 按 order 遍历 HandlerMapping，你的普通 `@RequestMapping` 控制器（order 0）**永远先被问到**。SDR 只接"没有人认领"的请求。想覆盖某个仓库路径？写个同路径的普通控制器即可，无需关掉导出。
2. **逐 delegate 尝试 + 吞掉媒体类型/方法类异常继续**（`HandlerSelectionResult.from`，89-116）：先试 `BasePathAwareHandlerMapping`（服务 `@BasePathAwareController`，如 `/profile`），它没接住或抛 `HttpMediaTypeNotSupportedException`/`HttpRequestMethodNotSupportedException` 等"竞争性异常"时，**记住异常、继续问** `RepositoryRestHandlerMapping`。这就是注释里提到的 DATAREST-490 的解法：自定义的媒体类型专属控制器不会把通用导出"挡死"。

## 2.7 BaseUri 与后台 ID：URI 里的 ID 不必是数据库 ID

- **BaseUri**（webmvc 根包，120 行小类）：把配置的 `basePath`（如 `/api`）变成值对象，提供 `getRepositoryLookupPath()` 从任意 lookupPath 中剥掉前缀——前文两层 HandlerMapping 的路径剥离都靠它。
- **BackendIdConverter SPI**（webmvc 的 `spi/BackendIdConverter.java`）：`Serializable fromRequestId(String id, Class<?> type)` / `String toRequestId(Serializable id, Class<?> type)` + `supports(Class)` 插件接口。SDR 生成链接、解析 `@BackendId` 参数时统一经它转换（`RepositoryRestMvcConfiguration:850-858` 保证 `DefaultIdConverter` 永远兜底）。**想让 `/people/7` 变成 `/people/xG9k…`（混淆 ID）或业务键，实现这个 SPI 即可**，全链路（链接生成、参数解析、`RepositoryEntityLinks`）自动生效。

## 2.8 本章小结

- SDR 的装配核心是 `RepositoryRestMvcConfiguration`（30+ Bean，六组职责），Boot 只做"导入 + 属性灌入"（`DataRestAutoConfiguration`），裸 Servlet 有 `RepositoryRestDispatcherServlet`。
- 导出判定两步走：`RepositoryDetectionStrategy` 定候选集（2.4），`@RepositoryRestResource/@RestResource` 定最终进出（2.5）。
- `RepositoryResourceMappings` 在启动期把"实体 + 仓库"翻译成资源元数据（路径/rel/方法/搜索），是全系统唯一事实来源；rel 默认由 Evo Inflector 复数化。
- 路由靠"两层 HandlerMapping + DelegatingHandlerMapping"：**order 让位**（用户控制器优先）、**导出校验不过就返回 null**（自然 404）、**竞争性异常穿透**（自定义控制器与通用导出共存）、**OEMIV 自动注册**（JPA 懒加载序列化保命）。
- URI ID 与存储 ID 可经 `BackendIdConverter` 解耦；basePath 由 `BaseUri` 统一剥离。

# 三、控制器层：一次 HTTP 请求的一生

## 3.1 模块定位与控制器全景

SDR 全系统只有 **6 个控制器类 + 1 个 HAL 浏览器控制器**，且全部使用通配映射。它们不是普通 MVC 控制器——方法的参数（`RootResourceInformation`、`PersistentEntityResource`、`PersistentEntityResourceAssembler`、`ETag`、`@BackendId`）全靠 SDR 自带的参数解析器解析（见 3.2）。

| 控制器 | 映射 | 注解 | 职责 |
|---|---|---|---|
| `RepositoryController` | `/` | （注册于 `RestControllerConfiguration`） | 根资源：列出所有导出仓库的链接 |
| **`RepositoryEntityController`** | `/{repository}`、`/{repository}/{id}` | `@RepositoryRestController` | **主力**：集合与单项资源的 OPTIONS/HEAD/GET/POST/PUT/PATCH/DELETE |
| `RepositorySearchController` | `/{repository}/search`、`/{repository}/search/{search}` | 同上 | 查询方法导出与执行 |
| `RepositoryPropertyReferenceController` | `/{repository}/{id}/{property}`（+ `/{propertyId}`） | 同上 | 关联属性导航与增删改（uri-list） |
| `ProfileController` | `/profile`、`/profile/{repository}` | `@BasePathAwareController` | 元数据目录（ALPS/Schema 的入口链接） |
| `RepositorySchemaController` | `/profile/{repository}`（produces `application/schema+json`） | 同上 | JSON Schema |
| `AlpsController` | `/profile/{repository}`（produces `application/alps+json`） | 同上 | ALPS 语义描述 |
| `HalExplorer`（hal-explorer 模块） | `/hal-explorer` | `@BasePathAwareController` | 浏览器页面重定向 |

两类注解的区别（详解见 5.6）：`@RepositoryRestController` 的映射走 `RepositoryRestHandlerMapping`，**路径可含 `{repository}` 占位符**；`@BasePathAwareController` 走 `BasePathAwareHandlerMapping`，只共享 basePath 前缀。

## 3.2 RootResourceInformation 与参数解析器链

### 3.2.1 第一步：URL 段 → ResourceMetadata

控制器方法的每个"神奇参数"都由 `RepositoryRestMvcConfiguration.defaultMethodArgumentResolvers()`（895-916）里的解析器供给。链条第一环：

【源码证据】webmvc 的 `config/ResourceMetadataHandlerMethodArgumentResolver.java:76` 起

```java
public @Nullable ResourceMetadata resolveArgument(…) throws Exception {

    String lookupPath = baseUri.getRepositoryLookupPath(webRequest);         // 剥掉 basePath
    String repositoryKey = UriUtils.findMappingVariable("repository", parameter.getMethod(), lookupPath);

    if (!hasText(repositoryKey)) { return null; }                            // 根资源请求，无 {repository}

    for (Class<?> domainType : repositories) {
        ResourceMetadata mapping = mappings.getMetadataFor(domainType);
        if (mapping != null && mapping.getPath().matches(repositoryKey) && mapping.isExported()) {
            return mapping;
        }
    }
    throw new HttpClientErrorException(HttpStatus.NOT_FOUND);                // 路径对不上任何导出资源 → 404
}
```

**注意这段代码每次请求都线性遍历所有仓库**（不是 Map 查找）——因为 `ResourceMetadata.getPath()` 是可自定义的模式匹配而非精确串。仓库数量很大时这是一个小热点（一般应用十个八个仓库无感）。

### 3.2.2 第二步：三元组打包成 RootResourceInformation

【源码证据】webmvc 的 `config/RootResourceInformationHandlerMethodArgumentResolver.java:74` 起

```java
ResourceMetadata resourceMetadata = resourceMetadataResolver.resolveArgument(…);
Class<?> domainType = resourceMetadata.getDomainType();
RepositoryInvoker repositoryInvoker = invokerFactory.getInvokerFor(domainType);
PersistentEntity<?, ?> persistentEntity = repositories.getPersistentEntity(domainType);
return new RootResourceInformation(resourceMetadata, persistentEntity,
        postProcess(parameter, repositoryInvoker, domainType, webRequest.getParameterMap()));
```

`RootResourceInformation`（webmvc 根包，183 行）是控制器方法的第一参数：把 **ResourceMetadata（规则）+ PersistentEntity（元模型）+ RepositoryInvoker（执行器）** 打包，并内置安全阀：

【源码证据】`RootResourceInformation.java` 的 `verifySupportedMethod`

```java
public void verifySupportedMethod(HttpMethod httpMethod, ResourceType resourceType)
        throws HttpRequestMethodNotSupportedException, ResourceNotFoundException {

    if (!getResourceMetadata().isExported()) { throw new ResourceNotFoundException(); }

    SupportedHttpMethods httpMethods = getResourceMetadata().getSupportedHttpMethods();
    HttpMethods supportedMethods = httpMethods.getMethodsFor(resourceType);
    if (!supportedMethods.contains(httpMethod)) { reject(httpMethod, supportedMethods); }
}
```

两层防线：仓库整体没导出 → `ResourceNotFoundException`（→404）；方法不支持 → `HttpRequestMethodNotSupportedException`（→405，且响应的 `Allow` 会带上实际支持的方法集）。`SupportedHttpMethods` 的默认值来自仓库接口**有没有相应方法**（没有 `delete` 方法的仓库，DELETE 自然 405——**用仓库接口的表达力控制 HTTP 面**），再叠加 `ExposureConfiguration` 过滤（5.4）。另有一个特例 `verifyPutForCreation()`：PUT 建新实体受独立开关控制（`supportedHttpMethods.allowsPutForCreation()`）。

> 顺带一提：`RepositoryRestHandlerMapping` 里定义的 `DEFAULT_ALLOWED_METHODS = 全部方法除 TRACE`（`RepositoryRestHandlerMapping.java:68-70`）是 CORS 场景的默认方法集。

### 3.2.3 第三步：读请求体——POST/PUT/PATCH 的分岔口

【源码证据】webmvc 的 `config/PersistentEntityResourceHandlerMethodArgumentResolver.java`（260 行，`resolveArgument` 105-171 行）

```java
Optional<Serializable> id = Optional.ofNullable(idResolver.resolveArgument(…));   // @BackendId：URI 里的 id
Optional<Object> objectToUpdate = id.flatMap(it -> resourceInformation.getRequiredInvoker().invokeFindById(it));
Object newObject = read(resourceInformation, incoming, converter, objectToUpdate);
```

`read()`（187-211 行）的分岔逻辑，是理解 SDR 更新语义的钥匙：

```java
private Object read(RootResourceInformation information, IncomingRequest request,
        HttpMessageConverter<Object> converter, Optional<Object> objectToUpdate) {

    // JSON + PATCH 请求
    if (request.isPatchRequest() && converter instanceof JacksonJsonHttpMessageConverter c) {
        return objectToUpdate.map(it -> {
            ObjectMapper mapper = c.getMapper();
            return readPatch(request, mapper, it);                       // JsonPatchHandler：打补丁到已存对象
        }).orElseThrow(() -> new ResourceNotFoundException());           // PATCH 必须针对已存在资源

    // JSON + PUT 请求
    } else if (converter instanceof AbstractJacksonHttpMessageConverter c) {
        ObjectMapper mapper = c.getMapper();
        return objectToUpdate.map(it -> readPutForUpdate(request, mapper, it))   // PUT 更新：走 DomainObjectReader
                .orElseGet(() -> read(request, converter, information));          // PUT 建新：普通反序列化
    }

    return read(request, converter, information);                        // 兜底：直接反序列化
}
```

| 请求 | 已有资源 | 走的路径 | 语义 |
|---|---|---|---|
| POST（无 id） | — | `converter.read` 直接反序列化 | 新建 |
| PUT + URI 有 id，库里有 | 有 | `readPutForUpdate` → `DomainObjectReader.readPut` | **全量替换式合并**（4.7） |
| PUT + URI 有 id，库里没有 | 无 | `read` 普通反序列化 | PUT-for-creation（需 `allowsPutForCreation`） |
| PATCH（JSON Patch/Merge Patch） | 有 | `JsonPatchHandler.apply` | **补丁式合并**（4.8） |
| PATCH 但库里没有 | 无 | `ResourceNotFoundException` | PATCH 不能创建 |

解析器还会做两件收尾（148-171 行）：把已存实体的标识符**强制回填**到反序列化出的新对象上（客户端无法通过 body 改 id）；PUT-for-creation 时用 `EntityLookup` 的查找属性（或 id 属性）把 URI 里的 id 设到新对象上。最后 `toResource(bean, entity, forUpdate)`——更新场景 `build()`、新建场景 `forCreation()`（`PersistentEntityResource.isNew()` 由此而来，决定控制器走 create 还是 save）。

## 3.3 RepositoryEntityController：主力控制器逐方法走读

【源码证据】webmvc 的 `RepositoryEntityController.java`（525 行，`@RepositoryRestController`）。映射只有两条：`BASE_MAPPING = "/{repository}"`（81 行）与 `/{repository}/{id}`。

### 3.3.1 GET 集合：分页 + 链接组装

```java
// L180-199
@ResponseBody
@RequestMapping(value = BASE_MAPPING, method = RequestMethod.GET)
public CollectionModel<?> getCollectionResource(@QuerydslPredicate RootResourceInformation resourceInformation,
        DefaultedPageable pageable, Sort sort, RepresentationModelAssemblers assemblers) {

    resourceInformation.verifySupportedMethod(HttpMethod.GET, ResourceType.COLLECTION);
    RepositoryInvoker invoker = resourceInformation.getInvoker();
    if (null == invoker) { throw new ResourceNotFoundException(); }

    Iterable<?> results = invoker.invokeFindAll(pageable.getPageable());      // ① 执行查询
    ResourceMetadata metadata = resourceInformation.getResourceMetadata();

    return assemblers.toCollectionModel(results, metadata.getDomainType())    // ② 逐项包成 PersistentEntityResource
            .add(getCollectionResourceLinks(resourceInformation, pageable));  // ③ 追加 profile/search 链接
}
```

- ① `RepositoryInvoker`（data-commons）是仓库接口的反射统一门面：无论 `findAll(Pageable)` 还是 `findByLastname`，统一 `invokeXxx` 调用；`@QuerydslPredicate` 参数由 Querydsl 变体解析器把请求参数动态绑成谓词（classpath 有 Querydsl 才有）。
- ② `DefaultedPageable` 由 `MappingAwareDefaultedPageableArgumentResolver` 供给：没有分页参数时给默认页（20 条，`RepositoryRestMvcConfiguration:826-838`）；`Sort` 经排序翻译器把"JSON 字段名"翻译成"持久化属性名"（4.9）。
- ③ `getCollectionResourceLinks`（201-213）追加两条链：`profile`（指向 `/profile/people`，207）与导出的 `search`（209-212）。
- 集合渲染的"瘦身"默认就发生在这一步：`assemblers.toCollectionModel` 内部对每项调用的是 `PersistentEntityResourceAssembler.toModel()`（走 **excerpt 投影**），而单项 GET 走 `toFullResource()`（4.3 详解）。

同路径还有一个 compact 变体（216-235，produces `application/x-spring-data-compact+json` / `text/uri-list`）：响应体为空集合 + 把每项的 item 链接压平到顶层 `_links`——这是给旧客户端/HAL 浏览器的大集合优化。

### 3.3.2 GET 单项：ETag 与条件请求

```java
// L313-327
@RequestMapping(value = BASE_MAPPING + "/{id}", method = RequestMethod.GET)
public ResponseEntity<EntityModel<?>> getItemResource(RootResourceInformation resourceInformation,
        @BackendId Serializable id, final PersistentEntityResourceAssembler assembler,
        @RequestHeader HttpHeaders headers) {

    return getItemResource(resourceInformation, id).map(it -> {
        PersistentEntity<?, ?> entity = resourceInformation.getPersistentEntity();
        return resourceStatus.getStatusAndHeaders(headers, it, entity).toResponseEntity(//
                () -> assembler.toFullResource(it));
    }).orElseThrow(() -> new ResourceNotFoundException());
}
```

核心在 `ResourceStatus.getStatusAndHeaders`（`ResourceStatus.java` 约 55-75 行）：

```java
List<String> ifNoneMatch = requestHeaders.getIfNoneMatch();
ETag eTag = ifNoneMatch.isEmpty() ? ETag.NO_ETAG : ETag.from(ifNoneMatch.get(0));
HttpHeaders responseHeaders = preparer.prepareHeaders(entity, domainObject);   // ETag + Last-Modified

return eTag.matches(entity, domainObject) || preparer.isObjectStillValid(domainObject, requestHeaders)
        ? StatusAndHeaders.notModified(responseHeaders)                        // 304
        : StatusAndHeaders.modified(responseHeaders);                          // 200 + body
```

- **ETag 从哪来**：`ETag.from(entity, bean)` 取实体的 **`@Version` 版本属性值**（没有版本属性的实体就没有 ETag）。
- **Last-Modified 从哪来**：`HttpHeadersPreparer`（`HttpHeadersPreparer.java` 约 66-105 行）经 `AuditableBeanWrapperFactory` 取审计属性 `lastModifiedDate`。
- `If-None-Match` 命中 ETag，或 `If-Modified-Since` 晚于最后修改时间 → **304 Not Modified**（不带 body）。

### 3.3.3 POST / PUT / PATCH / DELETE：事件 + 保存三兄弟

```java
// createAndReturn, L473-487
private ResponseEntity<RepresentationModel<?>> createAndReturn(Object domainObject, RepositoryInvoker invoker,
        PersistentEntityResourceAssembler assembler, boolean returnBody) {

    publishEvent(new BeforeCreateEvent(domainObject));                        // ① 事件
    Object savedObject = invoker.invokeSave(domainObject);                    // ② 保存
    publishEvent(new AfterCreateEvent(savedObject));                          // ③ 事件

    Optional<PersistentEntityResource> resource =
            Optional.ofNullable(returnBody ? assembler.toFullResource(savedObject) : null);

    HttpHeaders headers = headersPreparer.prepareHeaders(resource);           // ETag/Last-Modified
    addLocationHeader(headers, assembler, savedObject);                       // Location: self 链接展开
    return ControllerUtils.toResponseEntity(HttpStatus.CREATED, headers, resource);
}
```

`saveAndReturn`（444-464）同构：`BeforeSaveEvent → invokeSave → AfterSaveEvent`，PUT 场景追加 `Location` 头（455-457），返回 200（带体）或 204。`deleteItemResource`（407-434）则是 `findById → eTag.verify → BeforeDeleteEvent → invokeDeleteById → AfterDeleteEvent → 204/200`。

三个值得记住的细节：

1. **returnBody 的智能默认**：`config.returnBodyOnCreate(acceptHeader)`（core 的 `RepositoryRestConfiguration.java:330-333`）——未显式配置时，**请求带了具体 `Accept` 头就返回 body，`curl` 裸调（无 Accept 或 `*/*`）则 201/204 无体**。这是很多"为什么 curl 看不到返回值"问题的答案。
2. **Location 头来自模板展开**：`addLocationHeader`（497-501）用 `UriTemplate.of(selfLink).expand()`——self 链接若带投影模板变量（`{?projection}`）也能正确展开。
3. **PUT 的创建/更新分岔**（341-364）：`payload.isNew()`（由 3.2.3 的 `forCreation()` 决定）→ 先 `verifyPutForCreation()` → 走 `createAndReturn` 或 `saveAndReturn`。即 **`PUT /people/4711` 对不存在的 4711 是"在指定 URI 创建"**，这是 REST 语义的正确实现。

### 3.3.4 HEAD/OPTIONS：自描述

`OPTIONS /{repository}`（128-137）返回集合资源支持的 `Allow` 方法集；`OPTIONS /{repository}/{id}`（267-277）额外返回 `Accept-Patch: application/merge-patch+json, application/json-patch+json, application/json`——**客户端可以由此发现"这个资源支持哪种补丁格式"**。

## 3.4 /search：查询方法即资源

【源码证据】webmvc 的 `RepositorySearchController.java`（398 行）。

- `GET /{repository}/search`（`listSearches`，141 起）→ `RepositorySearchesResource`：`entityLinks.linksToSearchResources(domainType)` 为每个导出的查询方法生成链接（rel 即方法 rel）。
- `GET /{repository}/search/{search}`（`executeSearch`，170 起）执行三步：

```java
Method method = checkExecutability(resourceInformation, search);            // SearchResourceMappings 找方法，找不到 404
Optional<Object> result = executeQueryMethod(resourceInformation.getRequiredInvoker(),
        parameters, method, pageable, sort);                                // 反射调用仓库查询方法
return toModel(result, methodMapping.getReturnedDomainType(), headers, resourceInformation, assemblers);
```

`executeQueryMethod`（330 起）有两处细节：

1. **参数按名绑定**：`MethodParameters(method, new AnnotationAttribute(Param.class))`——JPA 的 `@Param("name")` 或 Spring Data 的参数名即 URL 参数名。
2. **关联参数支持传 URI**：`prepareUris`（379 起）尝试把每个参数值 `new URI(...)`，转换成功后走 `UriToEntityConverter`（URI → 实体）。于是 `findByAuthor` 可以直接 `?author=http://localhost:8080/authors/42`，**关联作为参数时使用资源 URI 而非裸 ID**，与超媒体原则自洽。

`toModel`（201 起）按返回类型分派：`Iterable` → 集合模型；基本类型 → 直接返回；**不是实体类型的实例（投影/DTO）→ 直接返回**；实体 → 走 ETag/304 逻辑。

## 3.5 关联资源：/{repository}/{id}/{property}

【源码证据】webmvc 的 `RepositoryPropertyReferenceController.java`（506 行）。这是初学者最容易忽略、但多对多场景必用的控制器。以 `Order.products`（集合关联）为例，方法矩阵：

| 请求 | 方法（行号） | 行为 |
|---|---|---|
| `GET /orders/1/products` | `followPropertyReference`（~103） | 集合 → HAL 集合；Map → MapModel；单值 → 资源 + `Content-Location` 头 |
| `GET /orders/1/products/9` | `followPropertyReference`（~160） | 集合中定位单个关联实体 |
| `GET /orders/1/products`（Accept `text/uri-list`） | `followPropertyReferenceCompact`（~208） | 只返回 `text/uri-list`（每行一个 URI） |
| `PUT` 同路径，body=uri-list | `createPropertyReference`（~240） | **替换**集合（`AUGMENTING_METHODS` 不含 PUT，82 行定义为 `PATCH, POST`） |
| `POST` / `PATCH` 同路径，body=uri-list | 同上 | **追加**到现有集合 |
| `DELETE /orders/1/products` | `deletePropertyReference`（~133） | 单值属性：`wipeValue()` 解除引用；集合/Map → 405 |
| `DELETE /orders/1/products/9` | `deletePropertyReferenceId`（~314） | 从集合移除单个关联 |

body 是 `text/uri-list`（由 `UriListHttpMessageConverter` 读取，每行一个资源 URI）或 `application/x-spring-data-compact+json`（`_links` 里的链接）。`loadPropertyValue` 把每个 URI 经 `UriToEntityConverter` 加载成实体再赋给属性。**注意这套操作改的是"关系的拥有方"，保存的是父实体**——`Before/AfterLinkSaveEvent`、`Before/AfterLinkDeleteEvent` 就发布在这里（父实体与被关联实体两个参数）。

## 3.6 /profile：API 的自我描述

【源码证据】webmvc 的 `ProfileController.java` / `RepositorySchemaController.java` / `alps/AlpsController.java`。

- `GET /profile`（`listAllFormsOfMetadata`，~88 行）：为每个导出仓库生成一条 `/{rel}` 链接（Link rel 即资源 rel）。同时 `ProfileResourceProcessor` 会把这条链接挂到根资源上（这就是每个 HAL 响应里都有的 `profile` 链接的来源）。
- `GET /profile/{repository}`，**按 Accept 分岔到两个控制器**：
  - `Accept: application/schema+json` → `RepositorySchemaController.schema()`（60-66）→ `PersistentEntityToJsonSchemaConverter`：把 `PersistentEntity` 的属性翻译成 JSON Schema（`@Description` 注解 → description；`JsonSchemaFormat` 支持把特定属性声明为 `email`/`uri` 等 format；关联属性 → 指向关联资源的 schema 引用）。
  - `Accept: application/alps+json` → `AlpsController.descriptor()`（76-84）→ `RootResourceInformationToAlpsDescriptorConverter`：生成 ALPS 描述符（每个属性一个 descriptor；每个支持的操作一个带 `id` 与 HTTP 动词的 descriptor）。ALPS 可被前端工具消费，生成"语义可读"的 API 文档。
- 元数据开关：`MetadataConfiguration.alpsEnabled()` 默认 `true`（core 的 `config/MetadataConfiguration.java:30`），关闭后 ALPS 相关转换器与响应建议都不再注册。

## 3.7 根资源与异常翻译

**根资源**：`GET /`（`RepositoryController.listRepositories`，~88-104 行）返回 `RepositoryLinksResource`，遍历 `Repositories`，对每个导出实体追加 `entityLinks.linkToCollectionResource(domainType)`——你第一次启动应用时看到的那个链接列表就出自这 15 行代码。

**异常翻译**：`RepositoryRestExceptionHandler`（210 行）经 `extendHandlerExceptionResolvers`（`RepositoryRestMvcConfiguration:754-773`）插到解析器链第 0 位。方法 → 状态码映射：

| 异常 | 状态 | 备注 |
|---|---|---|
| `ResourceNotFoundException` | 404 | 未导出/资源不存在 |
| `HttpMessageNotReadableException` | 400 | body 读不了 |
| `InvalidStateTransitionRequest` | 400 | 如给单值关联发多个链接 |
| `RepositoryConstraintViolationException` | 400 | 校验失败，body 为 `RepositoryConstraintViolationExceptionMessage`（字段级 errors 数组，前端可直接渲染） |
| `HttpRequestMethodNotSupportedException` | 405 | `Allow` 头带支持的方法 |
| `OptimisticLockingFailureException` / `DataIntegrityViolationException` | 409 | 数据库层冲突 |
| `ETagDoesntMatchException` | 412 | `If-Match` 不匹配 |
| 其余（`IllegalArgumentException` 等） | 500 | |

## 3.8 本章小结

- 6 个通配控制器 + `RootResourceInformation`（元数据/元模型/调用器三元组）是控制层的骨架；所有"神奇参数"由 9 个自定义解析器供给。
- `ResourceMetadataHandlerMethodArgumentResolver` 把 URL 段翻成资源元数据（找不到 → 404）；`RootResourceInformation.verifySupportedMethod` 用"仓库接口方法存在性 + ExposureConfiguration"决定 405 面。
- 请求体的读取在 `PersistentEntityResourceHandlerMethodArgumentResolver` 分岔：POST 直读；PUT 有旧对象走 `readPut`（全量合并）；PATCH 走 `JsonPatchHandler`（补丁合并）且**不允许创建**；PUT 无旧对象即 PUT-for-creation。
- 主力控制器的方法骨架高度一致：`verifySupportedMethod → 事件(before) → invoker 调用 → 事件(after) → 头准备（ETag/Last-Modified/Location）→ 200/201/204/304`。
- 关联资源用 `text/uri-list` 增删关系（POST/PATCH 追加、PUT 替换、DELETE 解除）；`/profile` 按 Accept 分岔出 JSON Schema 与 ALPS。

# 四、超媒体与 JSON 渲染管线：信封、投影与合并

## 4.1 本章定位

上一章结束时，控制器手里拿着的是**实体对象**；客户端收到的却是 **HAL JSON**。中间发生了什么？本章是 SDR 源码含金量最高的部分，回答四个问题：

1. 实体怎么变成"带链接的信封"（`PersistentEntityResource` + `PersistentEntityResourceAssembler`，4.2~4.3）；
2. 关联字段怎么"变成链接"、id/version 怎么"消失"（定制 Jackson 模块 + `LinkCollector`，4.5~4.6）；
3. 请求体怎么"合并"进已有实体（`DomainObjectReader` 的 PUT/PATCH 双语义，4.7）；
4. JSON Patch 怎么直接操作 Java 对象（4.8），分页排序参数怎么翻译（4.9）。

渲染管线全景：

```
                     写路径（响应）
  实体 ──PersistentEntityResourceAssembler──▶ PersistentEntityResource（信封）
         │（可经 Projector 套投影）                  │
         │                                   HttpMessageConverter（按 Accept 选）
         │                                   ├─ HAL：halObjectMapper = base + PersistentEntityJacksonModule + HalJacksonModule
         │                                   └─ JSON：objectMapper = base + PersistentEntityJacksonModule
         │                                              │
         │                              PersistentEntityResourceSerializer（收链接）
         │                              + AssociationOmittingSerializerModifier（删关联/id/版本属性）
         ▼                                              ▼
  集合：CollectionModel/PagedModel  ─────────▶  {"_links":{…},"_embedded":{…},"page":{…}}

                     读路径（请求）
  body(JSON) ─▶ PersistentEntityResourceHandlerMethodArgumentResolver
                 ├─ POST   ─▶ converter.read 反序列化
                 ├─ PUT    ─▶ DomainObjectReader.readPut  （retainIdentifierAndVersion → mergeForPut）
                 └─ PATCH  ─▶ JsonPatchHandler（JSON Patch: SpEL 打补丁 / Merge Patch: doMerge）
                                     ▼
                              修改后的实体
```

## 4.2 PersistentEntityResource：实体的"信封"

【源码证据】webmvc 根包 `PersistentEntityResource.java`（244 行）。它继承 Spring HATEOAS 的 `EntityModel<Object>`，额外携带四样东西：

```java
private final PersistentEntity<?, ?> entity;              // 元模型引用（渲染时要知道"这是谁"）
private final Iterable<EmbeddedWrapper> embeddeds;        // 将渲染进 _embedded 的关联摘要
private final boolean isNew, nested;                      // forCreation 标记 / 嵌套渲染标记
```

`isNew` 由 3.2.3 的 `forCreation()` 设置——控制器据此决定 create 还是 save；`nested` 由 `buildNested()` 设置——嵌套实体渲染时只收链接、不再展开。`getEmbeddeds()` 的内容来自 `EmbeddedResourcesAssembler`（4.4）。

**装配器**：

【源码证据】webmvc 根包 `PersistentEntityResourceAssembler.java:66-100`

```java
@Override
public PersistentEntityResource toModel(Object instance) {                 // 集合项用：先套"摘要"投影
    return wrap(projector.projectExcerpt(instance), instance).build();
}

public PersistentEntityResource toFullResource(Object instance) {          // 单项资源用：全量
    return wrap(projector.project(instance), instance).build();
}

private Builder wrap(Object instance, Object source) {
    PersistentEntity<?, ?> entity = entities.getRequiredPersistentEntity(source.getClass());
    return PersistentEntityResource.build(instance, entity).//
            withEmbedded(getEmbeddedResources(source)).                    // _embedded 的关联摘要
            withLink(getExpandedSelfLink(source)).                         // 展开后的 self
            withLink(linkProvider.createSelfLinkFor(source));              // 可能带模板变量的 self
}
```

关键差异在 `toModel` vs `toFullResource`：**集合项默认渲染成 excerpt 摘要**（`projectExcerpt`），单项资源渲染全量（`project`）。这就是"为什么列表里每项字段比单个详情少"——前提是你给实体配了 excerpt 投影，没配则两者相同（`DefaultExcerptProjector.projectExcerpt` 直接返回原对象）。

`PersistentEntityResourceAssembler` 本身**每请求新建**（`PersistentEntityResourceAssemblerArgumentResolver`，`config/PersistentEntityResourceAssemblerArgumentResolver.java:74-87`）：

```java
String projectionParameter = webRequest.getParameter(projectionDefinitions.getParameterName()); // 默认 "projection"
PersistentEntityProjector projector = new PersistentEntityProjector(projectionDefinitions,
        projectionFactory, projectionParameter, associations.getMappings());
return new PersistentEntityResourceAssembler(entities, projector, associations, linkProvider);
```

`PersistentEntityProjector`（`support/PersistentEntityProjector.java`）继承 `DefaultExcerptProjector`：`?projection=name` 有值时优先套指定投影，否则回落 excerpt/原对象。**所以投影的选择是每请求动态的**，同一个端点 `GET /people?projection=summary` 与不传参数返回不同形状。

## 4.3 投影与摘要：@Projection、?projection= 与 excerpt

三件事别混淆：

| 机制 | 声明方式 | 生效范围 | 实现 |
|---|---|---|---|
| 接口投影 | `@Projection(name="summary", types=Person.class) interface PersonSummary { String getName(); }` | `?projection=summary` 请求参数 | `SpelAwareProxyProjectionFactory` 为实体生成动态代理，只暴露接口方法映射的属性 |
| excerpt 摘要 | `@RepositoryRestResource(excerptProjection = PersonSummary.class)` | ① 集合项默认形状 ② 关联值进 `_embedded` | `DefaultExcerptProjector.projectExcerpt`（`support/DefaultExcerptProjector.java:47` 起）+ `EmbeddedResourcesAssembler` |
| 配置式注册 | `ProjectionDefinitionRegistar` | 把 excerpt 投影补登记进 `ProjectionDefinitionConfiguration` | BPP：监听 `ResourceMappings` bean 初始化完成后，把每个 metadata 的 excerptProjection 按 `@Projection(types)` 或域类型注册（`config/ProjectionDefinitionRegistar.java`，BPP 入口 `postProcessAfterInitialization` 59 起） |

两个实现细节：

- **`@Projection` 的自动发现**：`RepositoryRestMvcConfiguration.getProjections()`（977-990）用 `AnnotatedTypeScanner` 扫描**所有实体所在包**。所以投影接口放在实体同包（或子包）即可被发现，不用额外配置。
- **excerpt 进 `_embedded` 的路径**：`EmbeddedResourcesAssembler.getEmbeddedResources()`（webmvc 根包 `EmbeddedResourcesAssembler.java:65` 起）遍历实体的所有 linkable 关联，**仅当关联目标类型注册了 excerpt 投影**时，把关联值（单个或集合）投影后以属性 rel 为名放进 `_embedded`。例如 `Order` 关联的 `Customer` 有 excerpt 投影时，`GET /orders/1` 的 `_embedded.customer` 直接带客户摘要——**为嵌套资源场景省一次 HTTP 往返**。

## 4.4 消息转换器与两个 ObjectMapper

【源码证据】`RepositoryRestMvcConfiguration.defaultMessageConverters()`（787-818）确定的顺序：

1. `AlpsJacksonJsonHttpMessageConverter`（若 ALPS 开启）；
2. HAL 或普通 JSON 转换器——**顺序由 `defaultMediaType`（默认 `application/hal+json`）决定**（802-809）；
3. 另一个（HAL/Jackson 对调）；
4. `HalFormsHttpMessageConverter`；
5. fallback `JacksonJsonHttpMessageConverter`（裸 JsonMapper，不带实体模块）；
6. `UriListHttpMessageConverter`（读 `text/uri-list`）。

普通 JSON 转换器是否接管 `application/json` 由 `useHalAsDefaultJsonMediaType`（默认 true）控制：默认时 HAL 转换器把 `application/json` 也纳入自己的 produces 且 order 更靠前（`jacksonHttpMessageConverter` 538-556 与 `halJacksonHttpMessageConverter` 562-579 的 order 互斥逻辑）。**这意味着：裸 `Accept: application/json` 默认也返回 HAL**；设 `useHalAsDefaultJsonMediaType(false)` 后才返回"纯净实体 JSON"。

两个 mapper（`halObjectMapper`，606-620）都从 `basicObjectMapperBuilder()`（942-961）出发，后者做了几件固定的事：`INDENT_OUTPUT`（美化的缩进输出——SDR 响应都是带换行缩进的）、`FAIL_ON_UNKNOWN_PROPERTIES=false`（未知字段不炸）、追加 geo 模块、`AggregateReferenceResolvingModule`、（可选）枚举翻译模块；HAL 版再叠加 `HalJacksonModule` 与 `HalHandlerInstantiator`（rel 复数化、curie、消息解析）。

## 4.5 PersistentEntityJacksonModule：把实体"改写"成资源的 Jackson 模块

【源码证据】webmvc 的 `json/PersistentEntityJacksonModule.java`（895 行）。这是 SDR 对 Jackson 做的三处"外科手术"，注册进两个 mapper（`RepositoryRestMvcConfiguration.persistentEntityJackson3Module`，725-735）。

### 4.5.1 手术一：PersistentEntityResourceSerializer（决定信封）

`PersistentEntityResourceSerializer extends StdSerializer<PersistentEntityResource>`（155-225 行）：序列化信封时不再走默认 Bean 序列化，而是——

```java
Links links = getLinks(resource);                       // LinkCollector 收集 self + 关联链接
if (TargetAware.class.isInstance(content)) {            // 投影（代理）内容
    provider.writeValue(jgen, new ProjectionResource(targetAware, links));   // 专用包装
    return;
}
EntityModel<Object> resourceToRender = new EntityModel<Object>(resource.getContent(), links) {
    @JsonUnwrapped
    public Iterable<?> getEmbedded() { return resource.getEmbeddeds(); }     // _embedded 打平进 HAL
};
provider.writeValue(jgen, resourceToRender);
```

即：**链接在序列化期收集**（`getLinksFor` / `getLinksForNested`，按 nested 标记区分），投影内容走专用 `ProjectionResource`（能对 `TargetAware` 代理正确取值），`_embedded` 用 `@JsonUnwrapped` 融入 HAL 渲染。

### 4.5.2 手术二：AssociationOmittingSerializerModifier（决定"哪些字段消失"）

【源码证据】`PersistentEntityJacksonModule.java:229` 起（`AssociationOmittingSerializerModifier` 类；核心方法 `changeProperties` 251 起）。对每个实体的 BeanPropertyWriter 列表逐个判定：

```java
findProperty.flatMap(it -> {
    if (associations.isLookupType(it)) {                          // EntityLookup 类型
        writer.assignSerializer(lookupObjectSerializer); return Optional.of(writer);
    }
    if (associations.isLinkableAssociation(it)) {                 // ★ 关联属性 → 删除（改由链接表达）
        return Optional.empty();
    }
    if (it.isIdProperty() && !associations.isIdExposed(entity)) { // id：默认隐藏（exposeIdsFor 可开）
        return Optional.empty();
    }
    if (it.isVersionProperty()) {                                 // @Version：永远隐藏
        return Optional.empty();
    }
    if (it.isEntity() && !writer.isUnwrapping()) {                // 非关联的嵌套实体
        writer.assignSerializer(nestedEntitySerializer);          // → 包一层 EntityModel + 收链接
    }
    return Optional.of(writer);
})
```

这张判定表就是"SDR 的 HAL 输出长什么样"的完整答案：

| 属性 | 默认输出 |
|---|---|
| 普通标量 | 原样输出 |
| linkable 关联（指向导出实体的关联） | **不输出属性**，改为 `_links` 里的链接（rel=属性 rel） |
| id 属性 | 隐藏（`RepositoryRestConfiguration.exposeIdsFor(...)` 可开） |
| @Version 属性 | 隐藏（但进 ETag 响应头） |
| 指向未导出类型的嵌套实体 | `NestedEntitySerializer` 包装输出（内含其自身的链接） |
| EntityLookup 支持的类型 | `LookupObjectSerializer` 输出业务键（`EntityLookup.identifierFrom`） |

### 4.5.3 手术三：反序列化侧（URI → 实体）

`AssociationUriResolvingDeserializerModifier`（415 行起）：反序列化时，关联属性收到的 JSON 值如果是资源 URI 字符串，用 `UriStringDeserializer`（583 行）**立即经 `UriToEntityConverter` + `RepositoryInvoker` 加载成实体**（配套 `ValueInstantiatorCustomizer` 处理不可变类型的构造器参数注入，496 行起）。所以 `POST /orders` 的 body 里可以直接写 `"customer": "http://localhost:8080/customers/42"`——**关联在 JSON 里的表示与在链接里的一致**。

## 4.6 LinkCollector 与 Associations：链接从哪来

【源码证据】webmvc 的 `mapping/DefaultLinkCollector.java`（247 行）：

```java
// getLinksFor, L90 起
Link selfLink = createSelfLink(object, existingLinks);          // DefaultSelfLinkProvider 生成
Path path = new Path(selfLink.expand().getHref());

LinkCollectingAssociationHandler handler = new LinkCollectingAssociationHandler(path, associationLinks);
entities.getRequiredPersistentEntity(object.getClass()).doWithAssociations(handler);  // 遍历所有关联

return addSelfLinkIfNecessary(object, existingLinks.and(handler.getLinks()));
```

`LinkCollectingAssociationHandler.doWithAssociation`（165 起）对每个 linkable 关联生成链接：`href = {self}/.{propertyPath}`（如 `/orders/1/products`），rel 取属性的 `ResourceMapping.getRel()`（`@RestResource` 可改）。**若两个关联产出同名 rel，抛 `MappingException: Detected multiple association links with same relation type; Disambiguate association … using @RestResource`**——错误信息直接告诉你解法。

`Associations.isLinkableAssociation`（`mapping/Associations.java`）判定"这个关联要不要变成链接"：属性行的导出开关（`@RestResource(exported=false)` 标在**属性 getter 上**）+ 实体是否被导出。self 链接本身由 `DefaultSelfLinkProvider`（core 的 `support/DefaultSelfLinkProvider.java:66` 起）生成：优先 `EntityLookup` 提供的标识，否则实体 id，经 `RepositoryEntityLinks.linkToItemResource` + `BackendIdConverter` 拼出 URI。

## 4.7 DomainObjectReader：PUT 与 PATCH 的双语义（本章核心）

【源码证据】webmvc 的 `json/DomainObjectReader.java`（777 行）。javadoc 一句话定位："Jackson 无法把（部分）JSON 文档深度应用到既有对象上，这是对它的尽力修补——手动检测嵌套对象，取出原值，递归合并。"

### 4.7.1 PATCH 语义：doMerge（递归部分合并）

`doMerge`（260-342 行）逐字段处理请求 JSON 树：

```java
for (Iterator<Entry<String, JsonNode>> i = root.properties().iterator(); i.hasNext();) {
    Entry<String, JsonNode> entry = i.next();
    if (!mappedProperties.isWritableField(fieldName)) { i.remove(); continue; }   // ① 不可写/隐藏字段：从树里剔除

    PersistentProperty<?> property = mappedProperties.getPersistentProperty(fieldName);
    Optional<Object> rawValue = Optional.ofNullable(accessor.getProperty(property));   // 实体当前值

    if (!rawValue.isPresent() || associationLinks.isLinkableAssociation(property)) { continue; }  // ② 关联：不动（由链接管）

    if (child.isArray()) {
        if (handleArray(child, it, mapper, …, rawValues)) { i.remove(); }         // ③ 集合：按位置递归合并
        return;
    }
    if (child.isObject()) {
        if (property.isMap()) { … doMergeNestedMap(…) … }                          // ④ Map：按键递归合并
        if (property.isEntity()) { i.remove(); doMerge(objectNode, it, mapper); }  // ⑤ 嵌套实体：取出原值递归合并
    }
}
return mapper.readerForUpdating(target).readValue(root);                           // ⑥ 剩余标量字段：合并进既有对象
```

- ① `isWritableField` 同时挡掉 Jackson 标记不可写的字段（id/version 通常被 Jackson 配置忽略）——**PATCH 无法通过 body 改 id/version**。
- ② **linkable 关联在 PATCH 的 body 里被忽略**：想改关系请用关联资源（3.5）。这与 POST/PUT 不同（POST 时关联 URI 会被反序列化加载，见 4.5.3）——因为部分更新场景"没传 = 不动"才是正确语义。
- ③ 集合的合并（`handleArray` 360 起 / `handleArrayNode` 382 起）非常讲究：**按位置配对**——JSON 数组第 i 个元素与集合第 i 个元素配对；嵌套实体逐个 `readPut` 递归合并；JSON 比集合长 → 新元素反序列化后追加；JSON 比集合短 → 尾部多出的集合元素**删除**；非实体集合（标量/枚举）不合并、整体反序列化替换。
- ⑥ 最后 `readerForUpdating(target).readValue(root)`：Jackson 的 update-reader 只设置树里还剩的字段——**没出现的标量字段保持原值**。

**一句话：PATCH = 深度部分更新，"没传 = 不变"，嵌套结构逐层进入原对象。**

### 4.7.2 PUT 语义：mergeForPut（全量覆盖式合并）

PUT 走 `readPut`（115-125 行）两步：

```java
retainIdentifierAndVersion(source, target, mapper);                     // ① 防篡改（5.x 新增）
Object intermediate = mapper.readerFor(target.getClass()).readValue(source);  // ② 整体反序列化成新对象
return (T) mergeForPut(intermediate, target, mapper);                   // ③ 把新对象状态"搬"到旧对象
```

① `retainIdentifierAndVersion`（138 行起）在反序列化**之前**把请求 JSON 树里的 id/version 字段值直接替换成已存实体的值（javadoc 明确说明：保证无论目标可变还是 record 之类的不可变类型，客户端都改不了标识与版本）。

③ `mergeForPut`（163 行起）的判定树：

- **不可变目标类型**（record、无 setter）或类型变化：`copyRemainingProperties` 把 Jackson 没映射到的属性从 source 拷到 target 后，**直接返回 source**（整体替换）。
- **可变目标**：`MergingPropertyHandler`（681-748 行）逐属性把 source 状态搬到 target：

```java
// doWithPersistentProperty, L716-748（节选）
if (property.isIdProperty() || property.isVersionProperty() || !property.isWritable()) { return; }
if (!properties.isMappedProperty(property)) { return; }                  // Jackson 不可见的属性跳过（后面补拷贝）

Optional<Object> sourceValue = Optional.ofNullable(sourceAccessor.getProperty(property));
if (property.isImmutable()) { targetAccessor.setProperty(property, sourceValue.orElse(null)); return; }

if (property.isMap())              { result = mergeMaps(property, sourceValue, targetValue, mapper); }      // 按键递归
else if (property.isCollectionLike()) { result = mergeCollections(property, sourceValue, targetValue, mapper); } // 按位递归
else if (property.isEntity())      { result = targetValue.isEmpty() ? sourceValue
                                        : targetValue.flatMap(t -> sourceValue.map(s -> mergeForPut(s, t, mapper))); } // 嵌套递归
else                               { result = sourceValue; }                    // ★ 标量：source 为 null 就置 null

targetAccessor.setProperty(property, result.orElse(null));
```

**注意最后一行**：标量属性 source 缺失（Jackson 反序列化后是 null）时，`result = sourceValue`，target 被置为 **null**。且非 linkable 的关联属性走 `LinkedAssociationSkippingAssociationHandler`（651 行起）——**只有 linkable 关联被跳过，非导出关联照样被覆盖**。

**一句话：PUT = "我提交的就是资源的完整新状态"。body 里没写的标量字段会被 null 覆盖（集合则按位合并、多余元素删除）——这是符合 HTTP 语义的行为，但也是新手丢数据的第一大坑：部分更新请用 PATCH。**

### 4.7.3 两条路径的对照表

| | PATCH（doMerge） | PUT（readPut → mergeForPut） |
|---|---|---|
| 输入形态 | JSON 树逐字段 | 整体反序列化为中间对象 |
| 没传的标量 | **保持原值** | **置 null** |
| 没传的嵌套实体 | 递归进原对象合并 | 新对象里是 null → target 置 null |
| 集合 | 按位置与原集合合并（长删短加） | 按位置与新对象合并（等价替换） |
| linkable 关联 | 忽略（走关联资源） | 忽略（走关联资源） |
| id/version | 剔除 + `retainIdentifierAndVersion` 双保险 | 同左 |
| 不可变类型（record） | 走 `readerForUpdating`（Jackson 无法合并则整体替换） | `copyRemainingProperties` 后整体替换 |

## 4.8 JSON Patch：把 RFC 6902 操作直接落到 Java 对象上

【源码证据】webmvc 的 `config/JsonPatchHandler.java`（123 行）+ `json/patch/` 包（14 个类）。

```java
public <T> T apply(IncomingRequest request, T target, ObjectMapper mapper) throws Exception {
    if (request.isJsonPatchRequest()) {          // Content-Type: application/json-patch+json
        return applyPatch(request.getBody(), target, mapper);
    } else {                                     // Content-Type: application/merge-patch+json
        return applyMergePatch(request.getBody(), target, mapper);   // → DomainObjectReader.read（4.7.1）
    }
}
```

JSON Patch 的实现有两个独特点：

1. **路径是 SpEL，不是 JSON 指针遍历**：`JsonPatchPatchConverter.convert` 把 `{"op":"replace","path":"/customer/name","value":"…"}` 转成 `PatchOperation` 列表；`PatchOperation` 用 `SpelPath`（`json/patch/SpelPath.java`）把 JSON 指针翻译成 SpEL 表达式并**绑定到真实 Java 对象图**（`bindForWrite(type, context)` 经 `BindContext` 知道每个路径段的属性类型），`perform()` 直接改实体。`add`/`replace` 的值还能是 `LateObjectEvaluator`——**先按目标属性的实际类型再反序列化**（解决"值类型取决于路径"的问题）。
2. **`remove` 的落点**：`RemoveOperation.perform` → `path.bindForWrite(type, context).removeFrom(target)`——集合路径删元素，可空标量置 null，不可空路径抛 `PatchException`。

对照 `OPTIONS /{repository}/{id}` 返回的 `Accept-Patch`（3.3.4）：客户端可以在 **JSON Patch（精确指令）**、**Merge Patch（4.7.1 的 doMerge）**、**裸 JSON** 三种补丁形态间自由选择。

## 4.9 分页与排序的翻译层

参数解析在 `RepositoryRestMvcConfiguration` 被覆写（826-848）：`page`/`size`/`sort` 三个参数名（`?page=0&size=20&sort=name,asc`）来自配置，默认页 20、最大页 1000。但真正有意思的是**排序翻译**：

【源码证据】webmvc 的 `json/JacksonMappingAwareSortTranslator.java` + `SortTranslator.java`（Jackson 2 时代的 `Jackson2MappingAwareSortTranslator` 已 `@Deprecated(since = "5.0", forRemoval = true)`，勿混淆）。问题是：实体属性叫 `lastname`，但 Jackson 渲染成了 `lastName`（或 `@JsonProperty("surname")`）——客户端拿着渲染出来的字段名来排序，直接传给仓库会炸。翻译器在参数解析阶段把 `Sort` 里的属性名经 Jackson 元数据（`JacksonMappingAwareSortTranslator` 持有 SDR 的 mapper）映射回**持久化属性名**，再交给 `HateoasSortHandlerMethodArgumentResolver` 的结果。这就是 `defaultMethodArgumentResolvers()` 里 `MappingAwareSortArgumentResolver` / `MappingAwarePageableArgumentResolver`（905-907）取代原生的原因——**"渲染出的 API 形状"与"存储的属性名"解耦**。

集合响应的 `page` 元数据（`page.size/totalElements/totalPages/number`）由 Spring HATEOAS 的 `PagedResourcesAssembler` 从 `Page` 生成（`RepresentationModelAssemblers.toCollectionModel`，webmvc 根包）。

## 4.10 其余渲染细节

- **枚举翻译**：`enableEnumTranslation`（默认 false）开启后挂 `JacksonSerializers`，枚举不再输出 name 而是经 `EnumTranslator` 用 MessageResolver 查本地化消息（配合 `@Description`/资源文件），ALPS 文档同步翻译。
- **HAL-FORMS**：`halFormsJacksonHttpMessageConverter`（589-604）+ `HalFormsAdaptingResponseBodyAdvice`（`config/HalFormsAdaptingResponseBodyAdvice.java:38-75`）：客户端请求 `application/prs.hal-forms+json` 但响应模型上没有任何 Affordance 时，**降级改写 Content-Type 为 HAL 或 JSON** 并打日志——HAL-FORMS 的价值在表单模板（affordances），没有模板硬渲染只是浪费。
- **AggregateReference**：core 的 `AggregateReference`/`AssociationAggregateReference` 是 jMolecules 风格的"引用值对象"，`AggregateReferenceResolvingModule` 让 JSON 里的 URI 与引用互转（`RepositoryRestMvcConfiguration:953`）。

## 4.11 本章小结

- 渲染管线 = **信封**（`PersistentEntityResource`：元模型 + embeddeds + isNew）→ **装配**（`PersistentEntityResourceAssembler`，集合走 excerpt、单项走全量、`?projection=` 每请求可选）→ **Jackson 魔改**（`PersistentEntityJacksonModule`：关联→链接、id/version 隐藏、嵌套包装、URI→实体反序列化）。
- 投影三件套：`@Projection` 接口（`?projection=`）、excerpt（`excerptProjection`，影响列表形状与 `_embedded`）、`ProjectionDefinitionRegistar`（打破配置循环）。
- 合并双语义是面试与实战的高频考点：**PATCH = 递归部分合并（没传不动，关联忽略）；PUT = 全量覆盖（标量没传置 null，仅 linkable 关联忽略）；PUT-for-creation 合法；id/version 双重防篡改**。
- JSON Patch 用 SpEL 路径直接操作对象图，值类型延迟解析；Merge Patch 即 doMerge。
- 排序参数经 Jackson 元数据翻译回持久化属性名——渲染名与存储名解耦。

# 五、事件、校验与扩展点：不改框架源码的全部手段

## 5.1 本章定位

SDR 的控制器是通用的，业务钩子必然要走"事件 + 配置接口"路线。本章盘点全部扩展手段，并给出各自适合的场景。

## 5.2 十个 RepositoryEvent 与发布点

【源码证据】core 的 `event/` 包。事件类型与发布位置：

| 事件 | 发布点（源码位置） | 时机 |
|---|---|---|
| `BeforeCreateEvent` / `AfterCreateEvent` | `RepositoryEntityController.createAndReturn`（476/478 行） | POST / PUT 创建，`invokeSave` 前/后 |
| `BeforeSaveEvent` / `AfterSaveEvent` | `RepositoryEntityController.saveAndReturn`（447/450 行） | PUT / PATCH 更新，`invokeSave` 前/后 |
| `BeforeDeleteEvent` / `AfterDeleteEvent` | `RepositoryEntityController.deleteItemResource`（424/427 行） | DELETE，`invokeDeleteById` 前/后 |
| `BeforeLinkSaveEvent` / `AfterLinkSaveEvent` | `RepositoryPropertyReferenceController.createPropertyReference`（~301-303 行） | 关联资源 PUT/POST/PATCH 保存父实体前/后（携带**父实体 + 被关联实体**两个参数） |
| `BeforeLinkDeleteEvent` / `AfterLinkDeleteEvent` | `RepositoryPropertyReferenceController` 的 DELETE 处理（~148-150 行） | 解除关联前/后 |

要点：

- 事件在**控制器层**发布，不在 Repository/持久层——所以 `saveAll`、事件监听器里手动 `invokeSave` 等路径不会触发。业务级生命周期请配合 Spring Data 的实体回调（`@EntityCallbacks`/JPA `@PrePersist`）。
- Before 事件的监听器**可以改实体**（拿到的是可变引用），且抛异常即中止（事件同步发布，异常直接上抛进 `RepositoryRestExceptionHandler`）。
- core 包里还有一个 `ExceptionEvent`——当前导出器没有发布它的代码路径，属于保留类型，不要依赖。

## 5.3 监听事件的三种方式

【源码证据】core 的 `event/AnnotatedEventHandlerInvoker.java`（224 行）+ `event/AbstractRepositoryEventListener.java`。

**方式一：注解处理器（最常用）**

```java
@RepositoryEventHandler                            // 标在组件上，由容器扫描
public class PersonEventHandler {

    @HandleBeforeCreate                            // 对应 BeforeCreateEvent
    public void handlePersonCreate(Person p) {     // 第一个参数的类型决定监听哪个实体
        // 校验 / 填默认值 / 打日志
    }

    @HandleBeforeLinkSave
    public void handleLinkSave(Person parent, Address linked) { … }   // 关联事件是双参数
}
```

机制：`AnnotatedEventHandlerInvoker` 同时是 **`ApplicationListener<RepositoryEvent>` 与 `BeanPostProcessor`**——`postProcessAfterInitialization`（95-119 行）发现 bean 上有 `@RepositoryEventHandler` 后，用 `ReflectionUtils.getUniqueDeclaredMethods` 扫描方法，对 10 对 `@HandleXxx` 注解逐一 `inspect` 注册（103-114 行）；事件到达时按"事件类型 → 处理器 → 首参数类型是否匹配实体"三级过滤（58-88 行）。**泛型实体类型经 `ResolvableType.forMethodParameter` 解析**，所以处理器方法直接声明实体参数即可。

**方式二：Spring 标准监听器**（无需注解，天然类型安全）：

```java
@Component
public class MyListener implements ApplicationListener<BeforeCreateEvent> {
    @Override public void onApplicationEvent(BeforeCreateEvent event) {
        Object entity = event.getSource();   // 自行 instanceof 判断
    }
}
```

**方式三：继承 `AbstractRepositoryEventListener<T>`**（回调式，覆写 `onBeforeCreate` 等模板方法，泛型定实体）。

## 5.4 校验：ValidatingRepositoryEventListener

【源码证据】core 的 `event/ValidatingRepositoryEventListener.java`（约 150 行）。

```java
@Bean
public ValidatingRepositoryEventListener validatingRepositoryEventListener(ObjectFactory<PersistentEntities> entities) {
    ValidatingRepositoryEventListener listener = new ValidatingRepositoryEventListener(entities);
    configurerDelegate.get().configureValidatingRepositoryEventListener(listener);   // ← 挂 validator 的入口
    return listener;
}
```

它监听全部 8 个生命周期事件，映射到 8 个字符串键（`beforeCreate`/`afterCreate`/`beforeSave`/`afterSave`/`beforeLinkSave`/`afterLinkSave`/`beforeDelete`/`afterDelete`），逐个调用 `validator.supports(entity.getClass())` 匹配的 Spring `Validator`；任何错误 → 抛 `RepositoryConstraintViolationException` → 3.8 的异常表翻译为 **400 + 字段级 errors JSON**（`RepositoryConstraintViolationExceptionMessage`，每个错误含 entity/property/message——message 已按 MessageSource 解析，可直接国际化）。注意它触发的是 **Spring `Validator` 体系**，JPA 层 Bean Validation（`@NotNull` 等）由持久层自己触发，两条链互不替代。

## 5.5 RepositoryRestConfigurer：八个全局钩子

【源码证据】webmvc 的 `config/RepositoryRestConfigurer.java`（155 行，全部 default 方法）。注册为 Bean 即被 `RepositoryRestConfigurerDelegate` 依次调用：

| 钩子 | 用途 |
|---|---|
| `configureRepositoryRestConfiguration(config, cors)` | 全局配置（basePath、分页、returnBody…）+ CORS 注册 |
| `configureConversionService(cs)` | 加自定义 Converter/Formatter |
| `configureValidatingRepositoryEventListener(listener)` | 挂 Validator（5.4） |
| `configureExceptionHandlerExceptionResolver(er)` | 定制异常解析 |
| `configureHttpMessageConverters(list)` | 追加/替换消息转换器 |
| `configureJacksonObjectMapper(builder)` | 定制两个 ObjectMapper（注意 5.x 是 `MapperBuilder`，Jackson 3 风格） |
| `customizeAuditableBeanWrapperFactory(factory)` | 换审计包装工厂（影响 Last-Modified 等） |
| `customizeLinkCollector(collector)` | **替换链接收集器**（对链接生成做全局改写） |

一个 Configurer 即可完成 90% 的定制，无需碰 `RepositoryRestMvcConfiguration` 本体（Boot 场景甚至不必 `@ConditionalOnMissingBean` 抢占）。

## 5.6 暴露控制全家桶

从粗到细的四层阀门：

1. **仓库级开关**：`@RepositoryRestResource(exported = false)` 或 `RepositoryDetectionStrategy`（2.4）。
2. **属性级开关**：`@RestResource(exported = false)` 标在属性 getter 上——关联不再生成链接、`isLinkableAssociation` 返回 false（`Associations`），PUT/PATCH 也不再触碰该属性。
3. **方法级开关**：`@RestResource(exported = false)` 标在查询方法上（或全局 `exposeRepositoryMethodsByDefault(false)`，2.5）。
4. **HTTP 方法级过滤**：`ExposureConfiguration`（core 的 `mapping/ExposureConfiguration.java`）——编程式按域类型禁用方法：

```java
repositoryRestConfigurer.configureRepositoryRestConfiguration((config, cors) -> {
    config.getExposureConfiguration()
          .forDomainType(Person.class)
          .withItemExposure((metadata, httpMethods) -> httpMethods.disable(HttpMethod.DELETE))
          .disablePutForCreation();                    // 禁止 PUT 创建
});
```

底层是 `ExposureConfigurer.AggregateResourceHttpMethodsFilter` 函数式过滤器，叠加在 `CrudMethodsSupportedHttpMethods`（按仓库接口有无 save/delete 方法推导的默认集）之上，最终被 `RootResourceInformation.verifySupportedMethod` 消费（3.2.2）。

## 5.7 EntityLookup：用业务键当资源标识

【源码证据】core 的 `support/EntityLookup.java`、`EntityLookupSupport.java`、`config/EntityLookupRegistrar.java`。

默认情况下资源标识 = 实体 id。`EntityLookup<T>` 接口（`supports`/`getLookupProperty`/`lookupEntity(id)`/`identifierFrom(entity)`）允许换成**业务键**（如 `Person` 的 `email`）。两种注册方式：

```java
// 方式一：配置 API（RepositoryRestConfiguration.getEntityLookupConfigurator()）
config.getEntityLookupConfigurator()
      .withLookup(Person.class).withIdMapping(Person::getEmail)   // URI 与 _links 用 email
      .withLookup((Class<Person>) null, person -> personRepository.findByEmail((String) null)); // 自定义查找

// 方式二：声明 EntityLookupSupport 子类 Bean（泛型解析支持类型，EntityLookupSupport.java:31-42）
```

生效点散布全链路（这就是"元数据驱动"的好处）：`DefaultSelfLinkProvider` 生成 self 用业务键、`LookupObjectSerializer` 渲染关联值为业务键（4.5.2）、`UnwrappingRepositoryInvokerFactory.invokeFindById` 优先走 `lookupEntity`（core 的 `support/UnwrappingRepositoryInvokerFactory.java:85-92`）、PUT-for-creation 回填 id 时取 `getLookupProperty`（3.2.3）。

## 5.8 自定义控制器：@RepositoryRestController vs @BasePathAwareController

| | `@RepositoryRestController` | `@BasePathAwareController` |
|---|---|---|
| HandlerMapping | `RepositoryRestHandlerMapping` | `BasePathAwareHandlerMapping` |
| 路径语义 | 可用 `/{repository}` 占位符，匹配进仓库 URI 空间 | 普通路径，只自动加 basePath 前缀 |
| 参数解析器 | `RepositoryRestHandlerAdapter` 全套（`RootResourceInformation` 等可用） | 同样可用（适配器是同一个） |
| 典型用途 | 增强仓库资源（如给 `/people/search` 加自定义搜索） | 与仓库无关的业务端点，但想共享 basePath |

两条红线：

1. **类级 `@RequestMapping` 禁止**：`BasePathAwareHandlerMapping` 检测到会直接抛 `IllegalStateException("Spring Data REST controller %s must not use @RequestMapping on class level as this would cause double registration with Spring MVC")`（常量 `AT_REQUEST_MAPPING_ON_TYPE`）——因为控制器同时会被普通 MVC 的 `RequestMappingHandlerMapping` 注册，造成双重映射。
2. **被普通 Controller 覆盖的路径归普通 Controller**（`DelegatingHandlerMapping` 的 order 让位，2.6.3）——不需要也不应该为"覆盖"去关 SDR。

## 5.9 CORS 与 AOT

- **CORS 三层**：全局 MVC 配置；`configureRepositoryRestConfiguration(config, cors)` 里 `cors.addMapping("/people/**").allowedOrigins("…")`（`RepositoryCorsRegistry`）；仓库接口上 `@CrossOrigin`（`RepositoryCorsConfigurationAccessor` 单独解析，2.6.2），最终 `combine` 合并。
- **GraalVM 原生镜像**：`aot` 包（`BasePathAwareControllerAotProcessor` / `ProjectionAotProcessor` / `RestMessagesResourcesRuntimeHints` / `ValueInstantiatorCustomizerRuntimeHints`）在构建期为主控制器与投影注册反射/资源提示——投影接口是运行时代理，原生镜像下必须提前注册。

## 5.10 本章小结

- 10 个生命周期事件全部在**控制器层**发布（create/save/delete × before/after + link 两对）；三种监听方式里 `@RepositoryEventHandler` 由 `AnnotatedEventHandlerInvoker` 的 BPP 扫描注册。
- 校验走 `ValidatingRepositoryEventListener` + Spring `Validator`，失败 → 400 + 字段级 errors；与持久层 Bean Validation 并行不悖。
- `RepositoryRestConfigurer` 八个钩子覆盖绝大多数定制；暴露控制四层阀门（仓库/属性/方法/HTTP 方法）自上而下收窄。
- `EntityLookup` 把资源标识与存储 id 解耦，一处注册全链路生效。
- 自定义控制器二选一：进仓库空间的 `@RepositoryRestController`，只共享前缀的 `@BasePathAwareController`；都禁止类级 `@RequestMapping`。

# 六、贯通视图：三条时间线看懂 SDR 全貌

## 6.1 时间线一：应用启动（Spring Boot 场景）

```
①  Spring Boot 启动，自动配置评估
    └─ DataRestAutoConfiguration：@ConditionalOnClass(RepositoryRestMvcConfiguration)
       且 Web 应用为 SERVLET → @Import(RepositoryRestMvcConfiguration)
       （@ConditionalOnMissingBean：你自定义同类型配置则 Boot 让位）

②  RepositoryRestMvcConfiguration 刷新，Bean 图就绪（Lazy 破环，2.2.2）
    ├─ repositories()：扫描容器内全部 Spring Data 仓库 → Repositories 注册表
    ├─ persistentEntities()：聚合各后端 MappingContext → 元模型全集
    ├─ repositoryRestConfiguration()：
    │    ├─ getProjections()：AnnotatedTypeScanner 扫实体包下的 @Projection
    │    └─ RepositoryRestConfigurerDelegate 依次调用所有 RepositoryRestConfigurer
    │       （含 Boot 的 SpringBootRepositoryRestConfigurer：灌入 spring.data.rest.*，@Order(0)）
    └─ resourceMappings()：RepositoryResourceMappings 构造期 populateCache
         遍历 PersistentEntity × RepositoryInformation
         → RepositoryDetectionStrategy 判导出 → RepositoryCollectionResourceMapping（rel 复数化）
         → RepositoryAwareResourceMetadata 入缓存      ★ 全系统的"资源户口本"建好

③  restHandlerMapping()：
    ├─ BasePathAwareHandlerMapping.init：收集 @BasePathAwareController/@RepositoryRestController
    │   的方法映射，拼 basePath、改写 produces
    ├─ RepositoryRestHandlerMapping.init：同上 + 导出校验逻辑 + OEMIV 拦截器（JPA 在场时）
    └─ 包成 DelegatingHandlerMapping（order = LOWEST-100）

④  repositoryExporterHandlerAdapter()：装 9 个参数解析器 + ResponseBodyAdvice
⑤  extendHandlerExceptionResolvers()：RepositoryRestExceptionHandler 插到链头
⑥  defaultMessageConverters()：6 个转换器按默认媒体类型排序就位
⑦  DispatcherServlet 就绪 —— 此后每个请求：MVC mapping(order 0) 先问 → 没人接 → Delegating 接
```

## 6.2 时间线二：GET /people/1 的完整链路

```
GET /people/1   Accept: application/hal+json   If-None-Match: "1"
  │
  ├─① DispatcherServlet.getHandler：RequestMappingHandlerMapping(order 0) 不认识 /people/1 → null
  │   DelegatingHandlerMapping（LOWEST-100）接手：
  │     delegate1 BasePathAwareHandlerMapping：/people/1 不匹配任何 @BasePathAwareController → null
  │     delegate2 RepositoryRestHandlerMapping：匹配 /{repository}/{id} 模板
  │       → lookupHandlerMethod：剥 basePath → 第一段 "people"
  │       → mappings.exportsTopLevelResourceFor("people") ✓（ResourceMappings 里查得到且 exported）
  │       → 返回 HandlerMethod（RepositoryEntityController#getItemResource）
  │
  ├─② HandlerAdapter（RepositoryRestHandlerAdapter）按序解析参数：
  │     ResourceMetadataResolver：{repository}="people" → 遍历仓库找到 metadata（否则 404）
  │     RootResourceInformationResolver：metadata + PersistentEntity(Person) + invoker 打包
  │     BackendIdResolver：@BackendId → BackendIdConverter.fromRequestId("1") → id=1
  │
  ├─③ 控制器 getItemResource：
  │     verifySupportedMethod(GET, ITEM)     // 未导出→404 / 不支持→405
  │     invoker.invokeFindById(1)            // RepositoryInvoker 反射调 findById
  │     ResourceStatus.getStatusAndHeaders：  // ETag("1") vs If-None-Match → 命中则 304
  │     assembler.toFullResource(it)         // 全量（不套 excerpt）
  │
  ├─④ 渲染（Accept=hal+json → halJacksonHttpMessageConverter + halObjectMapper）：
  │     PersistentEntityResourceSerializer：LinkCollector 收 self + linkable 关联链接
  │     AssociationOmittingSerializerModifier：删关联属性/id（未 expose）/version
  │     HalJacksonModule：_links/_embedded 结构化、rel 复数化
  │
  └─⑤ 响应 200（或命中条件请求 304）：
        ETag: "1"        Last-Modified: <audit 时间>
        {
          "name" : "Alice",                       // 普通属性
          "_links" : {
            "self"  : { "href" : "http://…/people/1" },
            "person" : { "href" : "http://…/people/1{?projection}" },   // 模板 self
            "orders" : { "href" : "http://…/people/1/orders" }          // 关联导航
          }
        }
```

## 6.3 时间线三：PUT /people/1 的完整链路（对比 PATCH）

```
PUT /people/1   Content-Type: application/json   If-Match: "1"
   body: {"name":"Alice","email":"a@x.io"}
  │
  ├─①~② 同 6.2 的路由与参数解析，直至 PersistentEntityResourceHandlerMethodArgumentResolver：
  │     id=1 存在 → objectToUpdate = invoker.invokeFindById(1)
  │     readPutForUpdate：DomainObjectReader.readPut
  │       ├─ retainIdentifierAndVersion：body 里的 id/version 字段强制回填已存值   ★ 防篡改
  │       ├─ 整体反序列化为中间对象（body 没写的标量 → null）
  │       └─ mergeForPut：MergingPropertyHandler 逐属性搬状态
  │            标量：source 值覆盖 target（null 也覆盖）→ email 没传就会被清空！
  │            集合：按位递归合并，多余元素删除
  │            linkable 关联：跳过（改关联走 /people/1/orders）
  │     → 返回 forUpdate 的 PersistentEntityResource（isNew=false）
  │
  ├─③ 控制器 putItemResource：
  │     eTag.verify(entity, object)          // If-Match 不符 → ETagDoesntMatchException → 412
  │     verifySupportedMethod(PUT, ITEM)
  │     saveAndReturn：BeforeSaveEvent →（ValidatingRepositoryEventListener 校验）
  │                        invoker.invokeSave → AfterSaveEvent
  │
  ├─④ 渲染同 6.2（或 returnBody=false → 204 无体）
  └─⑤ 若是 PATCH：①中 readPatch → JsonPatchHandler（JSON Patch: SpEL 改实体 / Merge Patch: doMerge
      递归合并），没传的字段保持原值；PATCH 不允许资源不存在（404）；其余流程相同
```

## 6.4 从源码中提炼的四个设计模式视角

- **元数据驱动的模板方法**：6 个通用控制器是"模板"，每个仓库的差异全部压进 `ResourceMetadata`/`PersistentEntity` 元数据——**同一个方法服务 N 个仓库**，扩展全靠数据而不是继承。
- **责任链 + 让位协议**：`DelegatingHandlerMapping`（HandlerMapping 链）、MVC 的参数解析器链、消息转换器链、异常解析器链——SDR 在每条链上都"排序靠后、匹配不上就返回 null/继续"，与宿主框架零冲突地共生。
- **插件注册表**：`BackendIdConverter`、`EntityLookup`、`StaticResourceProvider`、`RepositoryRestConfigurer`、`Validator` 全部经 `PluginRegistry` 或有序 Bean 列表收敛，`supports()` 决定谁接管——SDR 自身的可扩展性就是 Spring `PluginRegistry` 项目的最佳实践样本。
- **信封 + 手术式序列化**：不改领域对象、不用 DTO，而是在 `PersistentEntityResource` 信封 + Jackson `ValueSerializerModifier` 两个层面"外科手术式"改造输出——当"想改第三方对象序列化"时，这是标准答案。

---

# 七、附录

## 7.1 关键接口速查表

| 接口/类 | 所在模块 | 一句话职责 | 高频方法 |
|---|---|---|---|
| `RepositoryRestConfiguration` | core/config | 全局配置单点 | setBasePath / getDefaultMediaType / getExposureConfiguration / getEntityLookupConfigurator |
| `RepositoryRestConfigurer` | webmvc/config | 八个配置钩子（Bean 即注册） | configureRepositoryRestConfiguration |
| `ResourceMappings` / `ResourceMetadata` | core/mapping | 仓库→资源元数据 | getMetadataFor / exportsTopLevelResourceFor / isExported / getPath / getSupportedHttpMethods |
| `RepositoryDetectionStrategy` | core/mapping | 仓库导出候选策略 | RepositoryDetectionStrategies.ALL/DEFAULT/VISIBILITY/ANNOTATED |
| `RootResourceInformation` | webmvc | 请求上下文三元组 | verifySupportedMethod / getRequiredInvoker / getPersistentEntity |
| `PersistentEntityResource(Assembler)` | webmvc | 渲染信封与装配 | toModel（excerpt）/ toFullResource |
| `LinkCollector` | webmvc/mapping | 实体链接收集 | getLinksFor / getLinksForNested |
| `DomainObjectReader` | webmvc/json | PUT/PATCH 合并语义 | readPut / read / mergeForPut |
| `JsonPatchHandler` | webmvc/config | RFC 6902/7386 入口 | apply |
| `ETag` | webmvc/support | 版本属性 → ETag | from / verify / matches |
| `BackendIdConverter` | webmvc/spi | URI ID ↔ 存储 ID | fromRequestId / toRequestId / supports |
| `EntityLookup` | core/support | 业务键资源标识 | lookupEntity / identifierFrom / getLookupProperty |
| `RepositoryEvent` 族 | core/event | 10 个生命周期事件 | getSource / (LinkedEntityEvent) getLinked |
| `RepositoryRestExceptionHandler` | webmvc | 异常→状态码翻译 | @ExceptionHandler 家族 |
| `RepositoryEntityLinks` | webmvc/support | 感知映射的 EntityLinks | linkToCollectionResource / linkToItemResource / linksToSearchResources |

## 7.2 最小可运行示例（动手向）

依赖：`spring-boot-starter-data-rest` + `spring-boot-starter-data-jpa` + H2。全部代码：

```java
// 1. 实体（注意 @Version：没有它就没有 ETag）
@Entity
public class Person {
    @Id @GeneratedValue private Long id;
    private String name;
    private String email;
    @Version private int version;      // 乐观锁 + ETag 来源
    // 构造器/getter/setter 省略
}

// 2. 仓库——到此为止，没有任何 Controller
public interface PersonRepository extends JpaRepository<Person, Long> {
    List<Person> findByNameContaining(@Param("name") String name);   // 自动变成 /people/search/findByNameContaining
}

// 3.（可选）投影与摘要
@Projection(name = "summary", types = Person.class)
public interface PersonSummary { String getName(); }

// 4.（可选）事件钩子
@RepositoryEventHandler
public class PersonEventHandler {
    @HandleBeforeCreate
    public void check(Person p) {
        if (p.getEmail() == null) throw new IllegalArgumentException("email required");
    }
}

// 5.（可选）全局配置
@Bean
RepositoryRestConfigurer restConfigurer() {
    return new RepositoryRestConfigurer() {
        @Override public void configureRepositoryRestConfiguration(RepositoryRestConfiguration config, CorsRegistry cors) {
            config.setBasePath("/api").exposeIdsFor(Person.class);     // basePath=/api，暴露 id
        }
    };
}
```

启动后（`/api` 前缀）：

```bash
# 根资源：发现一切
curl http://localhost:8080/api/
# { "_links" : { "persons" : { "href" : "…/api/persons" }, "profile" : { "href" : "…/api/profile" } } }
#   ↑ rel 默认复数化（Person → persons；想叫 people 就 @RepositoryRestResource(path="people")）

# 新建：注意 curl 不带 Accept 头 → 201 但无体；带 Accept: application/json → 201 + HAL 体
curl -i -X POST http://localhost:8080/api/persons -H "Content-Type: application/json" \
     -d '{"name":"Alice","email":"a@x.io"}'
# Location: http://localhost:8080/api/persons/1     ETag: "0"

# 单查 / 条件请求
curl -i http://localhost:8080/api/persons/1 -H "If-None-Match: \"0\""     # → 304

# 部分更新（PATCH，字段不丢）
curl -X PATCH http://localhost:8080/api/persons/1 -H "Content-Type: application/merge-patch+json" \
     -d '{"email":"new@x.io"}'

# 查询方法 + 投影 + 分页排序
curl "http://localhost:8080/api/persons/search/findByNameContaining?name=Al&projection=summary&page=0&size=10"

# 关联管理（假设 Person.orders 关联 PersonOrder）
curl -X POST http://localhost:8080/api/persons/1/orders \
     -H "Content-Type: text/uri-list" -d "http://localhost:8080/api/personOrders/7"

# 元数据
curl http://localhost:8080/api/profile/persons -H "Accept: application/schema+json"
curl http://localhost:8080/api/profile/persons -H "Accept: application/alps+json"
```

实验清单（建议逐一亲手跑一遍，验证本文结论）：

1. `PUT` 只传 `{"name":"Bob"}`，再 `GET` 看 `email` 是否变 null（验证 4.7.2）；
2. 同样请求改用 PATCH，看 `email` 是否保留（验证 4.7.1）；
3. 给实体加 `@Version` 前后对比 `GET` 响应头（ETag 出现）；
4. `@RepositoryRestResource(exported = false)` 前后对比 `GET /api/`（根资源链接消失，直接访问 404）；
5. 写一个 `@RequestMapping("/persons")` 的普通 Controller，观察它"抢走"请求（验证 2.6.3 order 让位）。

## 7.3 初学者学习路线

1. **先会用**：跑通 7.2 的最小示例，用 HAL Explorer（`/api/hal-explorer`，加 hal-explorer 依赖即可）点完所有链接，建立"资源→链接→资源"的直觉。
2. **再懂规则**：读 1.5 的问题映射表，对照自己的应用逐条找到源码出处（本章号即路标）。
3. **读三段源码**：`RepositoryRestHandlerMapping.lookupHandlerMethod`（40 行，路由哲学）→ `RepositoryEntityController`（525 行，请求骨架）→ `DomainObjectReader`（777 行，合并语义）。
4. **做一次定制**：basePath + 关闭某仓库 + 自定义 Validator + 一个事件处理器 + 一个投影——覆盖 80% 真实需求。
5. **遇到坑回表**：PUT 丢字段→4.7；懒加载炸→2.6.2 OEMIV；405→3.2.2；412→ETag；链接 rel 冲突→4.6。

## 7.4 源码阅读入口清单（22 个关键文件）

按必读度排序，路径相对 `D:\code\3rd\spring-data-rest`：

1. spring-data-rest-webmvc/.../config/RepositoryRestMvcConfiguration.java（装配总入口，1057 行）
2. spring-data-rest-webmvc/.../RepositoryEntityController.java（主力控制器）
3. spring-data-rest-webmvc/.../config/PersistentEntityResourceHandlerMethodArgumentResolver.java（body 读取分岔）
4. spring-data-rest-webmvc/.../json/DomainObjectReader.java（PUT/PATCH 双语义，777 行）
5. spring-data-rest-webmvc/.../json/PersistentEntityJacksonModule.java（序列化魔改，895 行）
6. spring-data-rest-webmvc/.../RepositoryRestHandlerMapping.java（导出守门）
7. spring-data-rest-webmvc/.../config/DelegatingHandlerMapping.java（让位与穿透）
8. spring-data-rest-webmvc/.../PersistentEntityResourceAssembler.java（信封装配）
9. spring-data-rest-webmvc/.../PersistentEntityResource.java（信封本体）
10. spring-data-rest-webmvc/.../mapping/DefaultLinkCollector.java（链接收集）
11. spring-data-rest-webmvc/.../mapping/Associations.java（关联链接与判定）
12. spring-data-rest-webmvc/.../config/JsonPatchHandler.java + json/patch/ 包（补丁体系）
13. spring-data-rest-webmvc/.../RepositorySearchController.java（查询方法导出）
14. spring-data-rest-webmvc/.../RepositoryPropertyReferenceController.java（关联资源）
15. spring-data-rest-core/.../mapping/RepositoryResourceMappings.java（元数据缓存）
16. spring-data-rest-core/.../mapping/RepositoryDetectionStrategy.java（导出策略）
17. spring-data-rest-core/.../config/RepositoryRestConfiguration.java（配置全集，749 行）
18. spring-data-rest-core/.../event/AnnotatedEventHandlerInvoker.java（事件处理器）
19. spring-data-rest-core/.../event/ValidatingRepositoryEventListener.java（校验调度）
20. spring-data-rest-webmvc/.../ResourceStatus.java + HttpHeadersPreparer.java + support/ETag.java（条件请求三件套）
21. spring-data-rest-webmvc/.../RepositoryRestExceptionHandler.java（异常翻译）
22. spring-data-rest-webmvc/.../alps/AlpsController.java + alps/RootResourceInformationToAlpsDescriptorConverter.java（ALPS）

## 7.5 与手写 Controller 的取舍

**适合 SDR 的场景**：领域模型清晰、以聚合为中心的后端服务；内部系统/原型/管理端 API；想快速获得超媒体 API 并让前端按 HATEOAS 消费；配合 Spring Security 的方法级授权做权限收敛。

**慎用的场景与对策**：

| 痛点 | 原因 | 对策 |
|---|---|---|
| 强契约 API（对外 OpenAPI/版本化） | SDR 的响应形状由元模型决定，演进易破坏契约 | 对外手写 Controller + DTO，对内用 SDR |
| N+1 与性能 | excerpt/关联链接会触碰懒加载；集合逐项装配 | 精心设计 fetch join、`EntityGraph`；控制 excerpt 投影范围 |
| 复杂业务动作（非 CRUD） | 资源模型表达"过程"别扭 | `@BasePathAwareController` 写业务端点，CRUD 留给 SDR |
| 安全边界模糊 | 默认导出**所有**仓库 | `detection-strategy=ANNOTATED` + 显式 `@RepositoryRestResource` + 方法级安全 |

---

## 结语

回到开篇的问题：SDR 到底做了什么？

- 它用 **`Repositories` + `PersistentEntity`** 把"仓库"翻译成"资源"，让数据访问抽象与资源抽象天然对齐；
- 用 **两层 HandlerMapping 的让位协议** 在不动宿主框架的情况下接管了一个完整的 URI 空间；
- 用 **6 个通配控制器 + 元数据缓存** 证明了"运行时元模型驱动的通用端点"可以替代代码生成；
- 用 **定制 Jackson 模块与 DomainObjectReader** 在"实体"与"资源表示"两个世界之间架起双向且语义正确的桥梁——PUT/PATCH 的双语义是全书最值得咀嚼的一段；
- 用 **事件 + Configurer + 四层暴露阀门** 让通用系统在业务面前保持可定制。

理解了这些，你就掌握了读 Spring 家族其他"导出器"类项目（Spring Data 的其他 Web 支持、Spring HATEOAS 的 Affordance 体系）的通用钥匙：**它们都在回答同一个问题——如何让一种领域抽象，在 HTTP 上获得符合其语义的表示**。




