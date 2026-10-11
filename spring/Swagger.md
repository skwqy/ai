# Swagger 深度源码解析（写给初学者的架构全景）——从 OpenAPI 规范到 springdoc-openapi

> **本文基于的源码**（四份基线，均为 2026-10 实际克隆到 `D:\code\3rd`）：
>
> | 仓库 | 版本 / commit | 角色 |
> |---|---|---|
> | `OpenAPI-Specification` | main（规范文本含 3.2.1） | OpenAPI 规范本身（Markdown 法律文本） |
> | `swagger-core` | **2.2.56-SNAPSHOT**（commit `04558121`，2026-09-02） | OpenAPI 的 Java 模型 + 注解 + 解析引擎 |
> | `springdoc-openapi` | **3.1.2-SNAPSHOT**（commit `354c98cbab`，2026-09-06，父 POM `spring-boot-starter-parent 4.1.0`） | Spring 世界的 Swagger 引擎（v3 线 = Boot 4 基线） |
> | `springfox` | 最后提交 `2020-10-13`（tag `3.0.0` = 2020-07-13） | 已死的前一代 Spring Swagger 集成（用于考古与对比） |
>
> 文中所有【源码证据】的文件路径与行号均为对上述快照实际读取所得。规范类结论以 `OpenAPI-Specification/versions/*.md` 的原文（尤其各版 Appendix A: Revision History）为准。
>
> **阅读约定**：延续本系列（参考 `Spring Framework.md`）的体例——每章"先白话、后源码"，`文件路径:行号` 可直接在 IDEA 里定位。本文与《Spring REST Docs.md》第六章（Swagger 与 REST Docs 正面对比）互为呼应，那一章讲"两者怎么选"，本文讲"Swagger 这套东西本身是怎么造出来的"。

## 如何读这份文档

- **第一遍（建立地图，1~2 小时）**：只读第一章（总览）每节的白话段、第十章（springfox 兴亡）、第十一章（贯通视图）。目标是能回答：Swagger 这个词现在到底指什么？一个 Spring Boot 应用加上 springdoc 后，`/v3/api-docs` 是怎么被生产出来的？springfox 为什么死了？
- **第二遍（深入源码）**：顺序建议：第二章（规范演进，理解"契约"长什么样）→ 第三章（swagger-core，一切的地基）→ 第五章（springdoc 扫描管线，全文核心）→ 第六章（模型转换与参数处理）→ 第八/九章（UI 与 MCP）→ 第四章/第七章（装配与定制点，随用随查）。
- **只想快速上手**：直接跳第十二章（实战指南，Boot 4.1.1 小白向）——从依赖到页面可访问只要三分钟，每一步都标注了对应的源码章节，遇到不懂的机制再回溯前文。

---

# 一、总览：Swagger 的定位、设计哲学与生态全景

## 1.1 一句话定位

**"Swagger" 今天同时指三样东西**，读任何教程之前必须先分清：

1. **一份规范**：OpenAPI Specification（OAS）——用一份 JSON/YAML 文档机器可读地描述"这个 REST API 有哪些路径、什么参数、什么响应"。它诞生于 Wordnik 公司的内部项目 Swagger，2015 年捐赠给 Linux 基金会旗下 OpenAPI Initiative 后改姓"OpenAPI"（`versions/2.0.md` 标题至今保留着身世：*"fka Swagger RESTful API Documentation Specification"*）。
2. **一套 Java 工具链**：Swagger 团队（今属 SmartBear）维护的 swagger-core——把 OAS 规范翻译成 Java 注解（`@Operation/@Schema`…）、Java 模型（`io.swagger.v3.oas.models.OpenAPI`）和一套"从代码读出规范"的解析引擎。**它是规范在 JVM 上的化身，与 Spring 无关**。
3. **一个 UI**：Swagger UI——把 OAS 文档渲染成可交互网页（Try it out 直接发请求）的那张脸。

在 Spring Boot 的世界里，用户感知到的"加个 Swagger"实际是：**springdoc-openapi**（把 Spring MVC 的运行时元数据喂给 swagger-core 的适配层）+ swagger-annotations（你写的注解）+ swagger-ui（starter 里带的 webjar）三者咬合的产物。springfox 是上一代适配层，2020 年后已被 springdoc 完全取代（第十章专述）。

它解决的问题是：**API 文档与 API 实现之间的漂移**。文档单独维护（Word/Wiki）必然过期；Swagger 的答案是"文档是代码运行时的投影"——代码改了，下次请求 `/v3/api-docs`，文档自动跟上。

## 1.2 设计哲学：读源码前先记住四句话

1. **契约是数据，不是页面**。OAS 的核心产出是一份可计算的结构化文档（JSON/YAML），页面（Swagger UI）、SDK 生成（OpenAPI Generator）、契约测试（Schemathesis）、mock 服务器都只是同一份数据的不同消费者。所以源码里最核心的类不是"页面"而是 `io.swagger.v3.oas.models.OpenAPI`——一个纯数据结构（3.3 节）。
2. **先推断，后标注**。引擎从代码结构（类型、方法签名、Jackson 注解、Bean Validation）能推出 80% 的 Schema；swagger 注解只负责补"机器猜不出来"的部分（描述、示例、必填性、安全要求）。因此 springdoc 应用**零注解也能跑出可用的文档**——这是它与"必须写满注解"的旧 swagger-springmvc 的分水岭。
3. **扩展点用 x- 与定制器双轨开放**。规范层：所有 `x-` 开头的字段是合法扩展（`OpenAPI.java:357-365` 强制校验前缀）；工具层：springdoc 提供 9 个函数式定制接口（OperationCustomizer/ParameterCustomizer…，7.1 节），把"改文档"的能力像 Filter 链一样交给用户。
4. **分叉共存，而不是原地升级**。javax→jakarta 用 Eclipse Transformer 生成 `-jakarta` 双发 artifact（swagger-core，3.1 节）；Boot 2/3/4 用 v1/v2/v3 三条版本线并行（springdoc，1.6.3 节）；OAS 3.0/3.1 的模型差异用 `SpecVersion` 标记位在同一个 Schema 类里兼容（3.3 节）。**这个生态几乎从不在主干上做破坏性升级，而是开一条新线**——理解了这一点，就理解了它的依赖矩阵为什么长成那样。

## 1.3 生态分层全景

```
┌─────────────────────────── 消费层（谁在用这份契约）────────────────────────────┐
│  Swagger UI（交互网页，webjar 5.32.14）   Scalar UI   Swagger Editor          │
│  OpenAPI Generator（SDK 生成）  Schemathesis（契约测试）                       │
│  springdoc MCP 模块（把 API 暴露成 AI 工具，v3 新增，第九章）                    │
├─────────────────────────── Spring 适配层 ─────────────────────────────────────┤
│  springdoc-openapi（starter-common 引擎 + webmvc/webflux 适配 + UI/Scalar/MCP │
│  装配模块）：扫 RequestMappingHandlerMapping → 调 swagger-core → 暴露端点       │
├─────────────────────────── Java 工具链层（与 Spring 无关）─────────────────────┤
│  swagger-annotations（58 个注解）  swagger-models（OpenAPI 数据结构）           │
│  swagger-core（ModelConverters 解析引擎）  swagger-jaxrs2（JAX-RS 世界参照系）  │
├─────────────────────────── 规范层 ────────────────────────────────────────────┤
│  OpenAPI Specification 2.0 / 3.0.x / 3.1.x / 3.2.x（Markdown + JSON Schema）  │
└──────────────────────────────────────────────────────────────────────────────┘
```

## 1.4 依赖图（以各仓库 POM 实证）

springdoc v3.1.2-SNAPSHOT 各 starter 的关键依赖（读自各模块 `pom.xml`）：

| 模块 | 关键依赖（版本） | 说明 |
|---|---|---|
| starter-common | `spring-boot-starter`、`spring-boot-validation`、**`io.swagger.core.v3:swagger-core-jakarta 2.2.55`**（starter-common/pom.xml:25）、`spring-boot-jackson`、actuator-autoconfigure（optional） | 引擎本体；swagger-annotations/models-jakarta 由 core-jakarta 传递引入 |
| starter-webmvc-api | starter-common、`spring-boot-webmvc`、`spring-boot-web-server`、servlet-api（provided）、actuator（optional） | 只给 `/v3/api-docs`，无 UI |
| starter-webmvc-ui | starter-webmvc-api、**`org.webjars:swagger-ui 5.32.14`**、`webjars-locator-lite`、actuator-autoconfigure | UI = 外部 webjar，springdoc 不内嵌任何前端资源 |
| starter-webmvc-scalar | starter-common、`com.scalar.maven:scalar-webmvc 0.5.55` | 第二 UI（Scalar） |
| starter-common-mcp | starter-common、**`spring-ai-mcp`（Spring AI 2.0.0）**、spring-web、aspectjweaver | MCP 桥（第九章），底层是 `io.modelcontextprotocol` SDK 2.0.0 |

swagger-core 内部模块链（根 pom.xml:402-415 注册，各模块 pom 实证）：**annotations / models → core → integration → jaxrs2 → servlet-initializer(v1/v2)**；`swagger-java17-support` 只含测试（JDK≥17 profile，pom.xml:385-392），不发布运行时 jar。

这张表本身就是架构说明：**springdoc 是纯粹的"胶水"**——它自己只有 190 个左右的类，全部价值在于把 Spring MVC 的运行时事实（HandlerMethod、参数解析器、Converter）翻译给 swagger-core；规范的表达能力完全来自下游的 swagger-annotations/models。

## 1.5 关键问题 → 本文方案映射（全文导览）

| 你的问题 | 答案所在 | 详见 |
|---|---|---|
| `@Operation/@Schema` 这些注解是谁定义的、共多少个 | swagger-annotations | 3.2 |
| 一个 DTO 类怎么变成 JSON Schema 的 | swagger-core ModelConverters→ModelResolver | 3.4、3.5 |
| `/v3/api-docs` 的 JSON 是谁、在什么时机生成的 | springdoc AbstractOpenApiResource 扫描管线 | 5.2 |
| 为什么我的接口没出现在文档里 | 过滤器链（@Hidden/packagesToScan/pathsToMatch…） | 5.4 |
| Pageable/分页对象为什么文档长得怪、@ParameterObject 是干嘛的 | 参数平铺机制 | 6.4 |
| 想改文档（加响应头、隐藏字段、统一描述）用哪个钩子 | 9 个定制点 + 调用位置 | 7.1 |
| 多个 API 分组（/v3/api-docs/{group}）怎么配 | GroupedOpenApi 物化机制 | 7.2、7.3 |
| Swagger UI 页面是哪来的、配置怎么传进去的 | webjar + initializer.js 改写 | 8.2、8.3 |
| 旧项目的 springfox/Docket 怎么迁 | springfox 架构取证 + 迁移对照 | 10.1~10.4 |
| OAS 3.1 的 webhooks、3.2 的 $self 是什么 | 规范演进 | 第二章 |
| AI 时代这份契约还能干嘛 | springdoc MCP 模块 | 第九章 |

## 1.6 版本演进（全部经本地 git tag 实证）

### 1.6.1 规范层：OAS 时间线（`OpenAPI-Specification/versions/*.md` 的 Appendix A: Revision History 逐行核对）

| 版本 | 发布日期 | 一句话主题 |
|---|---|---|
| Swagger 1.0 | 2011-08-10 | Wordnik 内部项目脱胎，多文件（Resource Listing + API Declaration） |
| Swagger 1.2 | 2014-03-14 | 1.x 的收束版（`versions/1.2.md:19-21`） |
| Swagger 2.0 | 2014-09-08 | 单文件规范 + securityDefinitions（`versions/2.0.md:20`） |
| （捐赠 OAI） | 2015-12-31 | Swagger 2.0 捐赠给 OpenAPI Initiative（`versions/3.0.0.md:3438-3439`），规范改姓"OpenAPI" |
| OAS 3.0.0 | 2017-07-26 | `servers`/`components`/`requestBody` 替代 host+basePath、definitions 散置 |
| OAS 3.1.0 | 2021-02-15 | **对齐 JSON Schema Draft 2020-12**、顶层 `webhooks`、`nullable` 字段删除 |
| OAS 3.1.1 / 3.1.2 | 2024-10-24 / 2025-09-19 | patch 勘误 + 增补附录（Appendix B~F） |
| OAS 3.2.0 | 2025-09-19（官方公告口径 2025-09-23，与《Spring REST Docs.md》一致） | `$self`、`in: querystring`、Tag 的 summary/parent/kind、media type range 作键 |
| OAS 3.2.1 | 2026-09-10 | patch 勘误 |

逐版变化在第二章展开。

### 1.6.2 工具链层：swagger-core 版本表（git tag 日期实证）

| 版本 | 日期 | 关键事件 |
|---|---|---|
| v2.0.0 | 2018-03-26 | 注解/模型全套按 OAS 3.0 重写（repo 血统可追到 2011-07-28 首个提交） |
| v2.1.0 | 2019-11-16 | 稳定迭代线 |
| **v2.2.0** | **2022-04-04** | **OAS 3.1 支持**：模型加 `@OpenAPI31` 标记字段（`OpenAPI.java:37` 注释 `@since 2.2.0 (OpenAPI 3.1.0)`）、双单例 `ModelConverters`、`Json31/Yaml31` 门面 |
| v2.2.9 / v2.2.18 / v2.2.25 | 2023-03-20 / 2023-10-25 / 2024-10-02 | 季度节奏 patch |
| v2.2.30 / v2.2.40 | 2025-04-07 / 2025-10-28 | 同上 |
| **v2.2.55** | **2026-08-31** | 最新 GA（springdoc 3.x 锁定此版） |

### 1.6.3 Spring 适配层：springdoc 三条版本线（git tag 日期实证）

| 线 | 配套 Boot | 首版 | 最新（2026-10） | 状态 |
|---|---|---|---|---|
| v1 | Boot 1.x/2.x | 1.0.0（2019-07-23） | 1.8.0（2024-03-12，终版） | 终结 |
| v2 | Boot 3.x / Framework 6 | 2.0.0（2022-11-24） | **2.9.1（2026-09-06）** | 维护线 |
| v3 | **Boot 4 / Framework 7** | 3.0.0（2025-11-21，随 Boot 4 GA） | **3.1.1（2026-09-06；3.1.0 为 2026-07-31）** | 活跃（v3.0.0→v3.1.1 共 207 个提交） |

v3 线的关键台阶（CHANGELOG.md 原文实证）：

- **[3.0.0] - 2025-11-21**：`#2975 - Spring Framework 7 - Initial API versioning support`（自动配置给所有 `RequestMappingHandlerMapping` 包一层 `SpringDocApiVersionStrategy` 豁免自己的端点，5.7 节）；`Upgrade to Spring Boot 4.0.0`。
- **[3.1.0] - 2026-07-31**：`#3269` Kotlin nullable 机制、请求级 UI index transformation；`Upgrade Spring Boot to version 4.1.0`、`Upgrade swagger-core to version 2.2.52`。
- **[3.1.1] - 2026-09-06**：安全修复 **GHSA-rhhx-6j8h-8cvw**（`Accept-Language` 可撑爆按 locale 的 OpenAPI 缓存——修复即 5.6 节的 LRU 缓存 + `springdoc.cache.max-entries`）；`#3321 springdoc.login-endpoint.username-example/password-example`。

### 1.6.4 Spring 适配层的前身：springfox 兴亡时间线

| 时间 | 事件 |
|---|---|
| 2015 前后 | swagger-springmvc（0.1.0~0.8.8，tag 可见）→ springfox 1.x |
| 2.9.2 | 2018-06-23（2.x 线最后广为使用的版本） |
| 2.10.5 | 2020-06-23 |
| **3.0.0** | **2020-07-13 21:54（-0500，即 UTC 2020-07-14）**——同时支持 Swagger 2.0 与 OAS 3.0 输出（`DocumentationType.SWAGGER_2 / OAS_30`） |
| **2020-10-13** | **最后一次提交**（仅 stale-bot 规则更新），项目事实死亡 |
| 死因 | 依赖冻结在 Spring 5.2.7 / Boot 2.3.1 / javax.servlet（`gradle/dependencies.gradle:22/28/18`），永不兼容 Boot 3 的 jakarta 世界；README 至今仍推荐 3.0.0 且无维护声明——"silent EOL" |

### 1.6.5 五个容易搞错的点（本文初稿逐一被 git 实证修正过）

1. **"@Api/@ApiOperation/@ApiParam" 是上个时代的注解**。它们来自 springfox + swagger 1.5.x（Swagger 2.0 文档模型）；OAS 3 时代换成 `@Operation/@Parameter/@Schema`（swagger-annotations 2.x）。老教程两类混用时先看注解包名。
2. **`@EnableSwagger2` 在 springdoc 世界不存在**。springdoc 是零注解、零 Docket 的自动装配——starter 一引，`/v3/api-docs` 就在（5.1 节）。`@EnableSwagger2` 属于 springfox（`EnableSwagger2.java:42 @Import(Swagger2DocumentationConfiguration.class)`）。
3. **swagger-core 主线至今是 javax 包名**（`OpenApiServlet.java:16` import `javax.servlet.http.HttpServlet`）；jakarta 版本是用 Eclipse Transformer 0.5.0 对 javax 字节码整体改名后发布的**独立 artifact**（`swagger-core-jakarta` 等 7 个，见 3.1 节）——Spring Boot 3+ 项目里实际进 classpath 的是后者。
4. **springdoc 对 OAS 3.1 的"支持"与"默认"是两件事**：模型/序列化支持来自 swagger-core 2.2.0（2022-04）；springdoc **v2.8.0（2025-01-03）才把默认值翻转为 OPENAPI_3_1**（v2.6.0 tag 中 `SpringDocConfigProperties` 的 `version` 字段无默认值，v2.8.0 起为 `= OpenApiVersion.OPENAPI_3_1`，git tag 逐项实证）；v3 延续此默认（`SpringDocConfigProperties.java:1431`）。
5. **"SchemaResolver" 既不在 swagger-core 也不在 springdoc**（两仓库 find 均无此文件）——Swagger 的命名/缓存职责在 swagger-core 的 `ModelConverterContextImpl`（modelByType/modelByName 两本账）与 `TypeNameResolver`。网上部分资料把别的库的类张冠李戴到了 swagger-core 头上。

### 1.6.6 版本配套速查（选版本时对号入座）

| Boot | springdoc | swagger-core | swagger-ui（webjar） | 默认输出规范 |
|---|---|---|---|---|
| 2.x（终） | 1.8.0 | 2.x（javax 变体） | 3.x/4.x | OAS 3.0 |
| 3.x | 2.x（维护至 2.9.1） | 2.2.x-jakarta | 5.x | 2.8.0 起 OAS 3.1 |
| **4.x** | **3.x（3.1.1）** | **2.2.55-jakarta** | **5.32.14** | **OAS 3.1** |

## 1.7 全文章节地图

- **第二章 规范演进**：从 Wordnik 到 OAS 3.2——每一版改了什么、为什么改，规范原文逐条取证。
- **第三章 swagger-core**：注解全家福、模型数据结构、ModelConverters 解析引擎、Jackson 序列化、JAX-RS 参照系。
- **第四章 springdoc 总装配**：Boot 4 下的自动配置链全景（.imports 文件逐条）、依赖图、属性面。
- **第五章 引擎核心**：`AbstractOpenApiResource.getOpenApi` 扫描管线逐行解析——全文的心脏。
- **第六章 模型转换与参数处理**：springdoc 的 converter 家族、独立 ObjectMapper 的原因、@ParameterObject 平铺、javadoc 回填。
- **第七章 定制点与多分组**：9 个函数式定制接口的调用位置、GroupedOpenApi 的物化机制。
- **第八章 UI 集成与端点全景**：/v3/api-docs、swagger-config、initializer.js 七连改写、重定向链、actuator 双端口。
- **第九章 MCP**：v3 新增的 AI 消费层——把 API 文档变成大模型的工具箱。
- **第十章 springfox 兴亡**：插件架构取证、与 springdoc 的架构对照、死亡证据链、迁移表。
- **第十一章 贯通视图**：三条时间线（启动装配 → GET /v3/api-docs → UI 渲染与 Try it out）。
- **第十二章 实战指南**：Boot 4.1.1 新项目从零接入的小白操作手册——依赖选型、3 分钟跑通、配置与代码示例、JWT/分组/Javadoc/生产建议/常见坑，每一步标注对应的源码章节。
- **第十三章 附录**：版本速查、配置速查、注解速查、学习路线、30 个源码入口。

---

# 二、规范演进：从 Wordnik 到 OAS 3.2

> 本章证据全部来自 `D:\code\3rd\OpenAPI-Specification`（规范文本仓库）。各版本的权威发布日期一律以每版文档末尾的 **Appendix A: Revision History** 为准（`versions/versions.yaml` 不存在——目录清单实证）。

## 2.1 Wordnik 时代（1.0~1.2，2011-2014）

**白话**：Swagger 最初不是标准，而是一家词典公司 Wordnik 为了给自己几十个 API 自动生成文档写的内部工具。1.x 时代的"规范"是**多文件**的——先有一份资源清单，每个资源再配一份声明文件。

【规范证据】`versions/1.2.md:58-60`：
> "comprised of two file types: 1. The Resource Listing … 2. API Declaration"

Revision History（`1.2.md:19-21`）：1.0（2011-08-10）→ 1.1（2012-08-22）→ 1.2（2014-03-14，"Initial release of the formal document"）。1.2 首次成为一份"正式文档"，并点明了自己的生态位：*这些文件可以被 Swagger-UI 用来展示 API，被 Swagger-Codegen 用来生成客户端*（`1.2.md:11-13`）——**"契约 + 多消费者"的世界观从第一天就定了**。

## 2.2 Swagger 2.0：单文件化与捐赠（2014-2015）

**白话**：2.0 做了两件影响深远的事——把多文件合并成**一份**文档；2015 年底把规范捐给 OpenAPI Initiative，从此"Swagger"是商标、"OpenAPI"是规范名。

【规范证据】：
- `versions/2.0.md:1-4`：标题即 *"OpenAPI Specification / ## (fka Swagger RESTful API Documentation Specification) / #### Version 2.0"*；`:20`：`2.0 | 2014-09-08 | Release of Swagger 2.0`。
- 捐赠记录在 3.0 的修订史里：`versions/3.0.0.md:3439` `2.0 | 2015-12-31 | Donation of Swagger 2.0 to the OpenAPI Initiative`。
- 2.0 的顶层版本字段名叫 `swagger`——最权威的旁证反而是 3.0 的正文：`versions/3.0.0.md:107` *"OAS 2.0 documents contain a top-level version field named `swagger` and value `\"2.0\"`."*

## 2.3 OAS 3.0（2017-07-26）：结构重组

**白话**：3.0 是一次"目录结构重组"，语义变化不大但工程收益巨大：可复用的部件收进 `components`、服务器信息从三字段变 `servers` 数组、请求体从"一个特殊参数"变成独立 `requestBody`。

【规范证据】（从 `versions/3.0.0.md` 的目录与正文可直接对照）：

| 2.0 | 3.0 | 证据 |
|---|---|---|
| `swagger: "2.0"` | `openapi: 3.0.0` | `3.0.0.md:107` |
| `host` + `basePath` + `schemes` | `servers: [{url, description, variables}]` | `3.0.0.md:355-371`（servers 三种形态示例原文） |
| `definitions/parameters/responses` 散置顶层 | 收进 `components`（schemas/parameters/responses/…） | TOC `3.0.0.md:37-45` |
| 操作级 body parameter | 独立 Request Body Object + Media Type Object + Encoding Object | TOC 同上 |

版本策略也在 3.0 立了规矩（`3.0.0.md:103-107`）：SemVer，**major.minor 定义功能集，patch 只是勘误**，工具应兼容 3.0.*——这就是为什么今天 swagger-core 的 `OpenAPI` 字段默认值是 `"3.0.1"` 而不是 `"3.0.0"`（`OpenAPI.java:27`）。

## 2.4 OAS 3.1（2021-02-15）：向 JSON Schema 低头

**白话**：3.1 是语义级别的大版本——Schema Object 不再是"长得像 JSON Schema 的私货"，而是正式成为 **JSON Schema Draft 2020-12 的超集**。代价是几处不兼容：`nullable` 没了（改成 type 数组）、`type` 可以是数组。

【规范证据】：
- **对齐**：`versions/3.1.0.md:145` *"Data types in the OAS are based on the types supported by the JSON Schema Specification Draft 2020-12"*；`:147` *"Models are defined using the Schema Object, which is a superset of JSON Schema Specification Draft 2020-12"*；`:195` 新增顶层 `jsonSchemaDialect` 字段声明文档遵循的方言。
- **webhooks 入规范**：`:198` 顶层 `webhooks | Map[string, Path Item Object | Reference Object]`；`:72` *"MUST contain at least one paths field, a components field or a webhooks field"*——webhooks 升格为与 paths 平级的第三类内容。
- **nullable 之死**：`grep -c nullable versions/3.1.0.md` = **0**（3.0 里它是 Schema Object 的正式字段）。可空类型改用 JSON Schema 的 type 数组表达。
- **license 加 SPDX**：`:302` `identifier | string | An SPDX license expression … mutually exclusive of the url field`。

【工具侧回声】这一版的 Java 落地就是 swagger-core **v2.2.0（2022-04-04）**：`OpenAPI.java` 新增 `@OpenAPI31 private String jsonSchemaDialect`（39-40 行）与 `@OpenAPI31 private Map<String, PathItem> webhooks`（:64），`Schema.java` 的 `nullable` 被标 `@OpenAPI30`、新增 `types: Set<String>`（157-158 行）——**同一个 Schema 类用 `SpecVersion` 标记位同时表达两个规范版本**（3.3 节）。

## 2.5 OAS 3.2（2025-09-19）：文档即文档

**白话**：3.2 补的是"自我描述"和"工程便利"：文档可以自报家门（$self）、整个 query string 可以作为一个整体参数（querystring）、标签可以组成树（parent）。

【规范证据】（`versions/3.2.0.md`，该文件无独立 CHANGELOG 节，以下均取自正文）：

- **`$self` 顶层字段**：`:98` *"The `$self` field provides the self-assigned URI of this document, which also serves as its base URI"*；`:111` 要求引用解析以 `$self` 为基 URI。
- **querystring 参数**：`:769-770` `in: query` 与 `in: "querystring"` 不得同 operation 出现；`:789` 位置枚举扩为 `"query", "querystring", "header", "path", "cookie"`——querystring 是"把整个 URL 查询串当作一个用 content 描述的值"（`:770`），终于能规范描述"透传式"接口。
- **Tag Object 三连**：`:2898` `summary`（展示用短摘要）、`:2901` `parent`（标签成树，禁止循环）、`:2902` `kind`（机器可读分类）。
- **media type range 作键**：`:1210`/`:2163`，request/response 的 content 键允许 media type range（`text/*` 可被 `text/plain` 覆盖）。
- **解析规范**：新增 Appendix F（基 URI 判定示例，`:5235`）与 Appendix G（解析与引用解析指引，`:5450`）。

3.2.1（2026-09-10）为纯勘误（`versions/3.2.1.md` Appendix A）。注意：**截至 2026-10，主流 Java 工具链（swagger-core/springdoc）尚未跟进 3.2**——swagger-models 仍无 `$self`/`querystring` 支持，规范与工具的时滞是常态（1.6.6 表的"默认输出规范"列即为工具实际能力）。

## 2.6 规范仓库的一处考古彩蛋

历史 JSON Schema 已被归档到 `_archive_/schemas/`（根目录 ls 实证：`v1.2/`、`v2.0/schema.json`、`v3.0/schema.yaml`），**3.1/3.2 不再有 schema 目录**——3.1 起规范自身的校验 Schema 改由外部工具生成托管。这也侧面解释了为什么 3.1 对齐 JSON Schema 后，"规范"与"Schema"两个词的边界开始模糊。

---

# 三、swagger-core：OpenAPI 的 Java 双胞胎

> 本章对应源码：`D:\code\3rd\swagger-core`（main，2.2.56-SNAPSHOT，commit `04558121`）。所有行号实际读取所得。

## 3.1 模块分层与 jakarta 双发机制

**白话**：swagger-core 是个 Maven 多模块工程，五个功能模块排成一条依赖链，外加两个"构建期插件"和一个"改名车间"。理解了"改名车间"，就理解了为什么 Spring Boot 3 项目的依赖表里写的是 `swagger-core-jakarta`。

【源码证据】根 `pom.xml:402-415` 注册模块：swagger-annotations、swagger-models、swagger-core、swagger-integration、swagger-jaxrs2、swagger-jaxrs2-servlet-initializer(v1/v2)、swagger-bom、swagger-maven-plugin、swagger-eclipse-transformer-maven-plugin、swagger-project-jakarta。依赖链（各模块 pom 实证）：

```
swagger-annotations ─┐
                     ├─► swagger-core ─► swagger-integration ─► swagger-jaxrs2 ─► servlet-initializer v1/v2
swagger-models ──────┘        │                                    │
                              └── maven-plugin / gradle-plugin（构建期离线生成 openapi.json）
```

**jakarta 变体不是另一份代码**，而是构建期字节码改名：`modules/swagger-project-jakarta/modules/swagger-annotations-jakarta/pom.xml:11` 声明 `artifactId=swagger-annotations-jakarta`，其 18-27 行使用 Eclipse 官方的 `org.eclipse.transformer:transformer-maven-plugin`（版本 0.5.0，根 pom.xml:662）并开启 `<jakartaDefaults>true</jakartaDefaults>`——把 javax.* 字节码整体重写为 jakarta.*。共 7 个 `-jakarta` artifact（annotations/models/core/integration/jaxrs2/两个 servlet-initializer，`swagger-project-jakarta/pom.xml:221-227`）。

【源码证据】主线模块至今 import javax：`modules/swagger-jaxrs2/src/main/java/io/swagger/v3/jaxrs2/integration/OpenApiServlet.java:16` `import javax.servlet.http.HttpServlet`；根 pom 依赖的 `jakarta.ws.rs-api` 是 2.1.6——那是个仍用 `javax.ws.rs` 包名的过渡版本（pom.xml:597-599）。**"Jakarta EE 工件"与"jakarta 包名"是两回事**，这是排查依赖冲突时的关键认知。

## 3.2 swagger-annotations：58 个注解的全家福

**白话**：这一层就是你写代码时 `@` 的那些东西。全部注解按"文档结构"分组，恰好是 OAS 文档树的一一投影。

【源码证据】`find modules/swagger-annotations -name "*.java"` 共 58 个文件，分组如下（每个只列代表行号，均在 `io/swagger/v3/oas/annotations/` 下）：

| 组 | 注解 |
|---|---|
| 文档元数据 | `OpenAPIDefinition.java:27`、`info/Info`、`info/Contact`、`info/License`、`ExternalDocumentation`、`tags/Tag`、`tags/Tags` |
| 操作 | `Operation`、`Hidden`、`callbacks/Callback`、`callbacks/Callbacks` |
| **Webhook（3.1 专属）** | `Webhook.java:17-21` 与 `Webhooks.java:17-21`——都标 `@OpenAPI31`，@Webhook 可重复 |
| 参数与请求体 | `Parameter`、`Parameters`、`parameters/RequestBody`、`parameters/ValidatedParameter`、`media/Content`、`media/Encoding`、`media/ExampleObject`、`media/Schema`、`media/ArraySchema`、`media/SchemaProperty(.java)`、`media/DependentRequired(.java)`、`media/PatternProperty(.java)`、`media/DiscriminatorMapping` |
| 响应 | `responses/ApiResponse`、`responses/ApiResponses`、`responses/FailedApiResponse`、`headers/Header` |
| 链接 | `links/Link`、`links/LinkParameter` |
| 安全 | `security/SecurityRequirement`、`SecurityRequirementEntry`、`SecurityRequirements`、`SecurityScheme`、`SecuritySchemes`、`OAuthFlow`、`OAuthFlows`、`OAuthScope` |
| 服务器 | `servers/Server`、`servers/ServerVariable`、`servers/Servers` |
| 扩展 | `extensions/Extension`、`extensions/ExtensionProperty`、`extensions/Extensions` |
| 枚举与标记 | `enums/ParameterIn`、`ParameterStyle`、`Explode`、`SecuritySchemeIn`、`SecuritySchemeType`、标记注解 `OpenAPI31` |

注意 @OpenAPI31 的存在：**一个注解库同时服务两个规范版本**，3.1 专属能力（webhook、types 数组相关）都被标记隔离，供引擎在 V30 模式下忽略。

## 3.3 swagger-models：一份 OAS 文档的 Java 镜像

**白话**：`io.swagger.v3.oas.models.OpenAPI` 就是"内存里的 openapi.json"——字段名与规范字段名几乎一致，POJO + 流式 setter。它是全生态的通货：springdoc 生成它、UI 消费它的序列化结果、codegen 读它的 JSON。

【源码证据】`modules/swagger-models/src/main/java/io/swagger/v3/oas/models/OpenAPI.java`：

```java
// OpenAPI.java:26-44（节选）
private String openapi = "3.0.1";          // 默认 3.0.1，见 2.3 节 SemVer 策略
private Info info = null;
private List<Server> servers = null;
private List<SecurityRequirement> security = null;
private List<Tag> tags = null;
private Paths paths = null;
private Components components = null;
private java.util.Map<String, Object> extensions = null;

/** @since 2.2.0 (OpenAPI 3.1.0) */
@OpenAPI31
private String jsonSchemaDialect;

/** @since 2.2.0 (OpenAPI 3.1.0) */
@OpenAPI31
private java.util.Map<String, PathItem> webhooks = null;   // OpenAPI.java:64

private SpecVersion specVersion = SpecVersion.V30;
```

要点：

- **Components 是九张表**（`Components.java:25-40`）：schemas/responses/parameters/examples/requestBodies/headers/securitySchemes/links/callbacks 九个 Map + extensions。**没有 pathItems 字段**——3.1 的 Path Item 复用走 webhooks 与 Paths。
- **PathItem = 七个方法字段**（`PathItem.java:20-33`）：get/put/post/delete/options/head/patch/trace 各一个 `Operation` 字段，外加 summary/description/servers/parameters/$ref/extensions。
- **Schema 是最胖的模型**（`media/Schema.java`，3400+ 行）：3.0 字段 `type`(:136)、`nullable`(:151) 均标 `@OpenAPI30`；3.1 新增 `types: Set<String>`(:157-158)、`_const`(:131)、`prefixItems`、`$id/$schema/$dynamicAnchor`（154-169 区段），还有一个 `SchemaResolution` 枚举（DEFAULT/INLINE/ALL_OF/ALL_OF_REF，:60-74）控制解析策略。**"同一个类装两个规范"的兼容成本全部压在这一层**。
- **扩展字段的强制契约**：`OpenAPI.java:357-365` `addExtension` 要求 name 以 `x-` 开头否则静默丢弃；3.1 语义下 `addExtension31`（:367-373）额外拒收 `x-oas-`/`x-oai-` 保留前缀。`Schema.java:2207-2215` 更宽松——V31 下放开了 x- 前缀强制。

## 3.4 解析引擎：ModelConverters 双单例与 converter 责任链

**白话**：这是 swagger-core 的心脏——"给一个 Java 类型，吐出一个 Schema"。它被组织成一条**责任链**：每环只处理自己认识的类型，处理不了就把 `Iterator<ModelConverter>` 传给下一环。springdoc 能插进来改写行为，靠的就是这条链（6.1 节）。

【源码证据】`modules/swagger-core/src/main/java/io/swagger/v3/core/converter/ModelConverters.java`：

```java
// ModelConverters.java:24-26
private static ModelConverters SINGLETON = null;    // OAS 3.0 世界
private static ModelConverters SINGLETON31 = null;  // OAS 3.1 世界
```

- `getInstance(boolean openapi31)`（:69-79）惰性初始化对应单例；构造时链里先放 `new ModelResolver(Json.mapper())`（:32-35）。
- `init()` 通过 **ServiceLoader** 加载第三方转换器：`ModelConverters.java:135` `ServiceLoader<ModelConverter> loader = ServiceLoader.load(ModelConverter.class)`。
- 读取入口：`read(Type)` 把类型包成 `AnnotatedType`（:174-176，携带 `type/name/ctxAnnotations/resolveAsRef/jsonViewAnnotation` 等解析参数，`AnnotatedType.java:18-34`）；`read(AnnotatedType)`（:178-192）创建 `ModelConverterContextImpl` 后 `context.resolve(type)`；`readAll`（:198-208）返回全部命名模型；`readAllAsResolvedSchema`（:213-229）返回 `{schema, referencedSchemas}`。

责任链的调度中枢是 `ModelConverterContextImpl.resolve`（`ModelConverterContextImpl.java:77-110`）：

1. `ReferenceTypeUtils.unwrapReference` 解包引用（:79-83）；
2. `processedTypes` 防环（:90-93）——递归类型（自引用 DTO）的安全带；
3. 取链头 converter 调 `resolve(type, this, chainIterator)`（:95-97）；
4. 结果同时登记进 `modelByType` 与 `modelByName`（:99-105），`getDefinedModels()` 即后者的视图。

`ModelConverter` 接口只有一个方法（`ModelConverter.java:13-17`）：`Schema resolve(AnnotatedType, ModelConverterContext, Iterator<ModelConverter> chain)`。命名与缓存职责在 `jackson/TypeNameResolver.java:18-45`（`std` 单例、`useFqn` 全限定名开关、泛型走 `nameForGenericType`）。

> ⚠️ 常见误传：swagger-core **没有** `SchemaResolver` 类（全仓 find 无结果），也**没有** `_schemaRefMap` 字段——模型缓存就是 `ModelConverterContextImpl` 的两本 Map。

## 3.5 ModelResolver：Jackson→Schema 的翻译器

**白话**：责任链里唯一的内置实现是 `ModelResolver`（3694 行）——它读 Jackson 的类内省（`_intr()`）来生成 Schema，这就是"你写个 DTO，不写任何 swagger 注解，文档里也有正确的字段列表"的原因：**Jackson 能看到的，Schema 就能看到**。

【源码证据】`modules/swagger-core/src/main/java/io/swagger/v3/core/jackson/ModelResolver.java` 关键分支（`resolve` 主干 :166-1098）：

| 分支 | 行号 | 行为 |
|---|---|---|
| 入口 | :166-196 | 构造 JavaType；`AnnotationsUtils.mergeSchemaAnnotations` 把 @Schema/@ArraySchema 注解合并进解析上下文 |
| 命名 | :216-226 | @Schema.name 优先；否则枚举/非系统类走 `_typeName(type, beanDesc)` |
| @Schema.ref | :238-246 | 注解直接给了 $ref 就短路（V30 直接返回 `new Schema().$ref(...)`，V31 暂存） |
| 枚举与基础类型 | :336-356 | `type.isEnumType()` → `_createSchemaForEnum`；`PrimitiveType.fromType/fromTypeAndFormat` 决定 type+format |
| Bean Validation | :401（属性级 :930） | `applyBeanValidatorAnnotations`——**@NotNull/@Size/@Pattern 无需 swagger 注解即可进文档** |
| 组合 | :1037-1098 | @Schema.allOf/anyOf/oneOf 指向的类 resolve 成 `$ref`（:1048 拼 `#/components/schemas/`） |
| 多态 | :1313 | Jackson `@JsonSubTypes` 逐个注册为子模型；discriminator 解析 `resolveDiscriminator`（:980-982 调用，实现在 :2750-2767：优先 @Schema.discriminatorProperty，回落 `@JsonTypeInfo.property`） |

## 3.6 序列化：ObjectMapperFactory 与 mixin 家族

**白话**：生成的 `OpenAPI` 对象最后要变成 JSON/YAML 文本。swagger-core 用**自己的一套 ObjectMapper**（不是用户应用的）来序列化模型，因为 3.0/3.1 的输出语义不同——最典型的是"$ref 出现时其余属性必须丢弃"这条 3.0 规则。

【源码证据】`modules/swagger-core/src/main/java/io/swagger/v3/core/util/ObjectMapperFactory.java`：

- `create(jsonFactory, openapi31)`（:118 起）按版本挂不同 SerializerModifier：3.0 用 `SchemaSerializer`（:131-132，实现 $ref 时清空其余属性），3.1 用 `Schema31Serializer`（:152-153）。
- mixin 注册三段：基础 26 条（:177-202，如 OperationMixin、ExtensionsMixin）、3.0 分支追加 7 条（:206-212）、3.1 分支追加 6 条（:214-219）；`mixin` 包共 17 个文件，职责是控制模型类的序列化细节（如 3.1 输出 `types` 而非 `type`）。
- 对外四个门面：`Json / Json31 / Yaml / Yaml31`（`util/Json.java:11`、`util/Json31.java:16`、`util/Yaml31.java:15`）——**版本和格式正交出 2×2**。springdoc 的 `ObjectMapperProvider` 就 extends 这个工厂（6.3 节）。

## 3.7 走出 Spring：JAX-RS 参照系

**白话**：swagger-core 自己就带一套"扫描器"——面向 JAX-RS。springdoc 的整个架构（扫描→读→组装→暴露端点）在 JAX-RS 世界有一个同构参照物；理解了它，springdoc 的每个部件都能找到"原型"。

【源码证据】（swagger-jaxrs2 / swagger-integration 模块）：

- **Reader**（`io/swagger/v3/jaxrs2/Reader.java:78` `implements OpenApiReader`）：`read(Set<Class<?>>)`（:151-167）先按继承排序、回调 `ReaderListener.beforeScan/afterScan`（:165-203）；主循环（:426-472 区段）收集构造器/字段级参数（:426-429）、跳过 @Hidden（:441）、读 @Produces/@Consumes（:443-444）、拼 operationPath（:456）、从 @GET/@POST 推断 httpMethod（:472，经 `ReaderUtils.extractOperationMethod` 走 `OpenAPIExtensions` 链）、然后 `parseMethod`（:513）生成 Operation；parseMethod（:1005 起）装配 @Operation、@Callback（:1030-1038）、requestBody（:769-780 经 `OperationParser.getRequestBody`）。
- **SPI 三件套**（swagger-integration 模块 `io.swagger.v3.oas.integration.api`）：`OpenApiScanner`（哪里找资源类）、`OpenApiReader`（怎么读）、`OpenApiConfigurationLoader`（配置从哪来——classpath/file/URL/ServiceLoader/String 五种实现）。默认扫描器 `GenericOpenApiScanner.java:18-36` 按 `resourceClasses/resourcePackages` 配置；JAX-RS 专用 `JaxrsApplicationScanner.java:6-20` 从 `Application.getClasses()/getSingletons()` 取类（依赖 classgraph 找 @Path）。
- **Servlet 自动注册**：v2 initializer 是 `@HandlesTypes({Path.class, OpenAPIDefinition.class, ApplicationPath.class, Webhooks.class})` 的 `ServletContainerInitializer`（`swagger-jaxrs2-servlet-initializer-v2/.../SwaggerServletInitializer.java:25-26`），onStartup 收集命中类后 `new JaxrsOpenApiContextBuilder()...buildContext(true)`（v1 对应 :48-50），并以 `META-INF/services/javax.servlet.ServletContainerInitializer` 文件注册自身——Servlet 3.0 SCI 机制（与本系列《Spring MVC.md》2.6 节 SpringServletContainerInitializer 同款套路）。
- **暴露端点**：`OpenApiServlet`（`io.swagger.v3.jaxrs2.integration.OpenApiServlet.java:24`，位于 swagger-jaxrs2 模块）doGet（:50 起）按 `Accept: application/yaml`（:72）或 URL 以 yaml 结尾（:76-77）决定输出格式，JSON 走 :93 的 `ctx.getOutputJsonMapper().writeValueAsString(oas)`。

**对比预习**：Reader 对应 springdoc 的 `AbstractOpenApiResource`；OpenApiServlet 对应 `OpenApiWebMvcResource`；SwaggerConfiguration 对应 `SpringDocConfigProperties`。差别在于：JAX-RS 版只认注解，Spring 版多了一条"从 `RequestMappingHandlerMapping` 拿运行时事实"的路——这正是第五章的全部内容。

## 3.8 本章小结

swagger-core 把"OpenAPI"拆成三层 Java 实体：**注解**（写意图，58 个，@OpenAPI31 隔离版本）、**模型**（存结果，POJO+流式 setter+extensions x- 契约）、**引擎**（做翻译，双单例 ModelConverters + ModelResolver 责任链 + 版本化 mixin 序列化）。它对 Web 框架完全中立——自带的那套 JAX-RS 扫描器证明了这一点。Spring 世界的适配（springdoc）做的只是"换个数据源"：不用注解扫描器，改从 Spring MVC 的运行时注册表拿端点。

---

# 四、springdoc-openapi：Boot 4 下的总装配

> 本章对应源码：`D:\code\3rd\springdoc-openapi`（main，3.1.2-SNAPSHOT，commit `354c98cbab`，父 POM spring-boot-starter-parent 4.1.0）。v3 线 = Boot 4/Framework 7 基线。

## 4.1 模块矩阵：3 个功能域 × 4 种交付形态

**白话**：springdoc 把"引擎"与"暴露方式"彻底解耦——引擎只有 starter-common 一个；按 Web 栈分 servlet（webmvc）/reactive（webflux）两族；每族按"给什么"分 api（只有 JSON 端点）/ui（+Swagger UI）/scalar（+Scalar UI）/mcp（+AI 工具）四种 starter。

```
springdoc-openapi-starter-common（引擎，~190 个类）
  ├─ core/（configuration 17 + providers 17 + customizers 23 + converters 19 + service 8
  │        + properties 5 + models 7 + fn 4 + extractor 2 + …）
  ├─ api/（AbstractOpenApiResource——第五章主角）
  └─ ui/（AbstractSwaggerConfigurer / AbstractSwaggerIndexTransformer——第八章主角）
        ▲
        ├── starter-webmvc-api  / starter-webflux-api    （/v3/api-docs）
        ├── starter-webmvc-ui   / starter-webflux-ui     （+ Swagger UI webjar）
        ├── starter-webmvc-scalar / starter-webflux-scalar（+ Scalar）
        └── starter-webmvc-mcp  / starter-webflux-mcp    （+ AI 工具，依赖 common-mcp）
springdoc-openapi-starter-common-mcp（MCP 桥：OpenAPI → Spring AI ToolCallback）
```

## 4.2 自动配置链：.imports 文件逐条全景

**白话**：springdoc 的自动装配是"分级开关"结构：common 层 16 个配置类按能力切成小开关（Javadoc、Kotlin、Security、Data REST…），web 栈层再加 2 个，UI 层最后 1 个；每一级都用 `@ConditionalOnBean(上一级)` 串联，保证"引了 ui 必然有引擎"。

【源码证据】starter-common 的 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 全文（16 条）：

```
org.springdoc.core.configuration.SpringDocConfiguration
org.springdoc.core.properties.SpringDocConfigProperties
org.springdoc.core.configuration.SpringDocJavadocConfiguration
org.springdoc.core.configuration.SpringDocGroovyConfiguration
org.springdoc.core.configuration.SpringDocSecurityConfiguration
org.springdoc.core.configuration.SpringDocFunctionCatalogConfiguration
org.springdoc.core.configuration.SpringDocHateoasConfiguration
org.springdoc.core.configuration.SpringDocPageableConfiguration
org.springdoc.core.configuration.SpringDocSortConfiguration
org.springdoc.core.configuration.SpringDocSpecPropertiesConfiguration
org.springdoc.core.configuration.SpringDocDataRestConfiguration
org.springdoc.core.configuration.SpringDocKotlinConfiguration
org.springdoc.core.configuration.SpringDocKotlinxConfiguration
org.springdoc.core.configuration.SpringDocJacksonKotlinModuleConfiguration
org.springdoc.core.configuration.SpringDocWebServerConfiguration.EmbeddedWebServerConfiguration
org.springdoc.core.configuration.SpringDocWebServerConfiguration.DeployedWebServerConfiguration
```

webmvc-api 加 2 条：`SpringDocWebMvcConfiguration` + `MultipleOpenApiSupportConfiguration`；webmvc-ui 加 3 条：`org.springdoc.webmvc.ui.SwaggerConfig` + `SwaggerUiConfigProperties` + `SwaggerUiOAuthProperties`；common-mcp 3 条、webmvc-mcp 1 条、webmvc-scalar 1 条（`ScalarConfiguration`）。各模块另有 `META-INF/spring/aot.factories`（RuntimeHintsRegistrar，4.5 节）。

装配链的"榫卯"在各配置类注解上（`SwaggerConfig.java:70-76`，webmvc-ui）：

```java
@Lazy(false)
@Configuration(proxyBeanMethods = false)
@AutoConfigureAfter(SpringDocConfiguration.class)
@ConditionalOnProperty(name = "springdoc.swagger-ui.enabled", matchIfMissing = true)
@ConditionalOnWebApplication(type = SERVLET)
@ConditionalOnBean(SpringDocConfiguration.class)
public class SwaggerConfig { ... }
```

- `@Lazy(false)`：UI 资源处理器必须在启动期就绪（不能等首次请求才初始化）；
- `@ConditionalOnBean(SpringDocConfiguration)`：没有引擎就没有 UI——两级 starter 的桥接不用 @Import 而用条件；
- 总开关是 `@ConditionalOnProperty(SPRINGDOC_ENABLED)`（`SpringDocConfigProperties.java:61`，即 `springdoc.api-docs.enabled`，matchIfMissing）。

聚合注入对象 `SpringDocProviders`（providers 包 :42-72，七个 Optional 字段）与 `SpringDocCustomizers`（customizers 包 :53 起，10 个 Optional 字段）在 `SpringDocConfiguration.java:453-462` 装配，随后注入 `AbstractOpenApiResource`——**springdoc 把"可选依赖探测"与"用户定制器收集"都收敛成两个聚合对象传递**，避免了 AbstractOpenApiResource 构造函数参数爆炸。

## 4.3 SpringDocConfigProperties：一个类装下全部 springdoc.* 属性

**白话**：springdoc 的配置哲学是"一个 @ConfigurationProperties 装下一切，内部类分区"。挑默认值最值得记住的几组：

【源码证据】`org/springdoc/core/properties/SpringDocConfigProperties.java`，`@ConfigurationProperties(prefix = "springdoc")`（:60，常量 `Constants.java:42`）：

| 属性 | 默认值 | 行号 |
|---|---|---|
| showActuator | false | :69 |
| packagesToScan / pathsToMatch（含 exclude 四件套） | null = 不过滤 | :79-109 |
| autoTagClasses | true | :124 |
| removeBrokenReferenceDefinitions | true | :139 |
| defaultConsumesMediaType / defaultProducesMediaType | application/json、*/* | :154/:159 |
| useFqn（Schema 命名用全限定名） | false | :164 |
| showLoginEndpoint / showOauth2Endpoints | false / false | :169/:259 |
| preLoadingEnabled（启动期预热扫描） | false | :179 |
| useManagementPort | false | :189 |
| defaultFlatParamObject（全局 @ParameterObject 化） | false | :209 |
| enableJavadoc / enableKotlin / enableHateoas / enableDataRest / enableSpringSecurity… | true | :219-244 |
| use-management-port 场景下的 enableDefaultApiDocs | true | :249 |
| nullableRequestParameterEnabled | false | :269 |
| explicitObjectSchema（3.1 下对象显式 type: object） | false | :290 |
| **ApiDocs.path** | **/v3/api-docs** | :1406 |
| **ApiDocs.version** | **OPENAPI_3_1**（v3 默认；枚举值 "3.1.0"，3.0 线为 "3.0.1"） | :1431、:1544-1552 |
| Cache.maxEntries | 100（非正数回退） | :1614-1671（:1669） |
| ModelConverters.deprecating/pageable/polymorphic 三个子开关 | false | :1283/:1312/:1344 |

## 4.4 本章小结

springdoc 的装配是一棵"条件树"：16+2+3(+1) 个配置类逐级 @ConditionalOnBean 串联，能力开关细到 Kotlin/Groovy/Javadoc 单独成类。全部属性汇聚在 `SpringDocConfigProperties` 一个类里（20+ 顶层默认值 + 6 个内部类）。下一章进入真正干活的类。

---

# 五、引擎核心：AbstractOpenApiResource 扫描管线

> 本章是全文心脏。对应源码：`springdoc-openapi-starter-common/src/main/java/org/springdoc/api/AbstractOpenApiResource.java`（以下行号均指该文件，另注明者除外）。

## 5.1 类的骨架：继承 SpecFilter + 两个聚合对象

**白话**：这个类干的事一句话可以说完——"收到 `/v3/api-docs` 请求时，把 Spring MVC 的所有端点翻译成一份 OpenAPI 对象"。它的两个设计选择值得注意：继承 swagger-core 的 `SpecFilter`（白得一个"清理断链 $ref"的过滤器）；只做抽象流程，把"从哪拿端点"留给 webmvc/webflux 子类。

【源码证据】：

```java
// AbstractOpenApiResource.java:153
public abstract class AbstractOpenApiResource extends SpecFilter {
```

关键字段：`springDocConfigProperties`(:178)、`groupName`(:183)、**`springDocProviders`(:188)**、**`springDocCustomizers`(:193)**、`ObjectFactory<OpenAPIService> openAPIBuilderObjectFactory`(:198)、`requestBuilder`(:203, AbstractRequestService)、`responseBuilder`(:208, GenericResponseService)、`operationParser`(:213, OperationService)、`antPathMatcher`(:218)、`reentrantLock`(:223)、`resolvedGroupConfig`(:233)、`openAPIService`(:238)。

构造器（:253-281）做两件事：① 按构造参数 groupName 从 `springDocConfigProperties.getGroupConfigs()` 匹配出本实例负责的 `GroupConfig`（:267-270）——**一个分组 = 一个 AbstractOpenApiResource 实例**，这是 7.3 节多分组机制的地基；② 若开启 `springdoc.pre-loading-enabled`，用单线程 Executor 在启动期预热调用 `getOpenApi`（:271-280）。

## 5.2 getOpenApi：一次文档生成的十一步

**白话**：每次 `/v3/api-docs` 请求命中且缓存未命中时，走这条流水线。顺序是：**锁 → 定 locale → 查缓存 → 建骨架 → 过滤隐藏控制器 → （3.1 才有）webhooks → 通用响应 → 扫路径 → 修补 → 定制器 → 写缓存**。

【源码证据】`getOpenApi(String serverBaseUrl, Locale locale)`（:379-450），逐步：

```java
// :380 this.reentrantLock.lock();            —— 全程持锁，防并发扫描
// :383 final Locale finalLocale = selectLocale(locale);   （:452-464，按 allowedLocales 匹配）
// :384 缓存判断：openAPIService.getCachedOpenAPI(finalLocale) == null || isCacheDisabled()
// :386 openAPI = openAPIService.build(finalLocale);        —— OpenAPI 骨架（info/tags/servers）
// :387-391 从 mappingsMap 剔除类级 @Hidden 控制器与 isHiddenRestControllers()
// :394-398 OAS 3.1 分支：
//     openAPI.openapi("3.1.0"); openAPI.specVersion(SpecVersion.V31);
//     calculateWebhooks(openAPI, locale);                    —— :552-571 扫描 @Webhooks/@Webhook 注解类
// :399-403 responseBuilder.buildGenericResponse(...)       —— ControllerAdvice 的通用响应进 components
// :404 getPaths(mappingsMap, finalLocale, openAPI);        —— 抽象方法（:543），子类实现
// :406-407 Kotlin 字符串模板缩进裁剪 trimIndent
// :409-415 Spring Cloud Function 路由追加
// :416-420 updateServers(...)                              —— serverBaseUrl / forward-headers 处理
// :422-423 removeBrokenReferenceDefinitions(...)           —— 继承自 SpecFilter 的断链清理
// :429 OpenApiLocaleCustomizer.customize(...)              —— i18n 定制
// :430 OpenApiCustomizer.customize(openAPI)                —— 用户终稿定制
// :434 openAPIService.setCachedOpenAPI(finalLocale, openAPI)
// :436-437 耗时日志；:439-443 缓存命中分支（updateServers 仍执行——base url 因请求而异）
// :447-448 finally：清 javadoc 反射缓存、解锁
```

两个细节值得咀嚼：

1. **3.1 的 webhooks 是"扫描注解"而非"扫描端点"**——`calculateWebhooks`（:552-571）找的是标了 `@Webhooks/@Webhook` 的类，把注解内容 parse 成 POST PathItem 塞进 `openAPI.setWebhooks`。这直接对应 2.4 节规范的 `webhooks | Map[string, PathItem]`。
2. **缓存命中也要 updateServers**（:439-443）：OpenAPI 对象是按 locale 缓存的，但 `servers` 里带着本次请求的 host——同文档多环境访问的前提。

## 5.3 扫描来源：getPaths 的四路汇合（webmvc 实现）

**白话**：`getPaths` 的 webmvc 实现回答"端点从哪来"。答案不是"扫 @RestController 注解"，而是**直接问 Spring 要已注册的 HandlerMethod 映射表**——这就是"先推断"哲学的落地：能被 DispatcherServlet 分发的，就应该进文档。

【源码证据】webmvc-api 模块 `org/springdoc/webmvc/api/OpenApiResource.java:152-196`（`getPaths`）：

1. `springWebProvider.getHandlerMethods()`（:155）——实现在 `SpringWebMvcProvider.java:168-172`：`applicationContext.getBeansOfType(RequestMappingHandlerMapping.class)` 后逐个汇总 `getHandlerMethods()`。**plural**：你自己 new 的多个 RequestMappingHandlerMapping（如 Boot 的 + MVC 的）都会被收进来。
2. Spring Data REST 端点（:157-167，经 RepositoryRestResourceProvider）。
3. Actuator 端点（:169-179，`springdoc.show-actuator=true` 时；`globMatchActuators` 剔除 `**` 通配，tag 打上 `x-actuator` 组——`ActuatorProvider.getTag()` :108）。
4. Security OAuth2 端点（:183-191，SecurityOAuth2Provider——spring-authorization-server 的 /oauth2/* 等）。
5. RouterFunction 函数式端点（:193-194，RouterFunctionProvider，机制见 6.5 节之后的说明）。

与 JAX-RS 参照系对照：JAX-RS 版 Reader 扫的是"注解类"，这里扫的是"运行时映射表"——**文档与 DispatcherServlet 永远看到同一份真相**。

## 5.4 过滤器链：为什么你的接口没出现在文档里

**白话**：每个候选 HandlerMethod 要过五道闸，任何一道不过就出局。排查"接口不见了"就按这个顺序查。

【源码证据】（`AbstractOpenApiResource.java`）：

| 闸门 | 方法/行号 | 默认行为 |
|---|---|---|
| 类级 @Hidden | :387-391（构造期剔除） | 注解在 Controller 类上直接出局 |
| 方法级过滤器 | `isMethodToFilter` :958-963 | `OpenApiMethodFilter` 全部放行才纳入；方法级 @Hidden 在 `operationParser.isHidden`（:616）生效 |
| 包过滤 | `isPackageToScan` :987-1007 | packagesToScan/Exclude（组配置兜底 :993-998） |
| 路径过滤 | `isPathToMatch` :1015-1027 | pathsToMatch/Exclude，AntPathMatcher |
| produces/consumes/headers | `isConditionToMatch` :972-979、`isFilterCondition` :1629-1634 | 默认 null 全放行 |
| actuator 纳入 | `isActuatorRestController` :1565-1571 | 需 `isShowActuator()` 且 ActuatorProvider 判定 |

外加总开关 `springdoc.api-docs.enabled=false` 在 @Configuration 层短路（4.2 节）。

## 5.5 calculatePath → readOperation：一个端点的翻译

**白话**：从 HandlerMethod 到 `PathItem` 是两段式：先算"路径"（`calculatePath`：Spring 的 URL 模板怎么变成 OpenAPI path），再填"操作"（@Operation 注解合并、参数、请求体、响应……）。

【源码证据】MVC 主入口 `calculatePath(HandlerMethod, RouterOperation, ...)`（:581-707）：

```text
:582  customizeRouterOperation          —— RouterOperationCustomizer
:622  MethodAttributes 构建              （方法重载合并 setMethodOverloaded :623）
:629-635 class 级 consumes/produces 合并进方法级
:648-649 operationParser.parse          —— @Operation/@OperationParameters 注解合并
:650  fillParametersList（:1282-1340）   —— router 查询参数与枚举合并
:653  openAPIService.buildTags           —— 类级 @Tag/组名/autoTagClasses
:655-662 requestBody                     —— requestBuilder.getRequestBodyBuilder().buildRequestBodyFromDoc
:664  requestBuilder.build               —— 参数解析主干（AbstractRequestService.java:316-393；
                                          其中 DelegatingMethodParameter.customize :328、
                                          ParameterCustomizer :390-391，见 6.4 节）
:667-668 responseBuilder.build           —— @ApiResponse 合并 + 泛型返回值推导
:671-685 javadoc 回填                    —— enable-javadoc 时方法描述/return 用注释补
:687-690 buildCallbacks                  —— @Callback 注解（委托 OperationService:194）
:693  OperationCustomizer.customize      —— 用户操作级定制（含 Global 版 :1093-1101）
:695-702 路径通配符 {*xxx} 处理           —— Framework 6+ 的 PathPattern 全捕获语法
```

最后 `buildPathItem`（:1370-1422）用 switch 把 Operation 挂到 PathItem 的对应方法字段上，并**剔除路径模板里不存在于参数列表的 PATH 参数**（:1373-1385）——文档与路由不一致时的自愈。RouterFunction/云函数走重载版 `calculatePath(List<RouterOperation>,...)`（:730-771）与 `calculatePath(RouterOperation,...)`（:780-838）。

## 5.6 缓存与国际化：LRU、locale 与一个 CVE

**白话**：文档生成是反射密集型操作，不能每请求重算。缓存按 **locale 分键**（支持 i18n 文档），但"按请求头分区缓存"正是 2026 年那个安全公告的引爆点：攻击者可以伪造无限多个 Accept-Language 值撑爆内存。

【源码证据】缓存本体在 `OpenAPIService`（`org/springdoc/core/service/OpenAPIService.java`）：

```java
// OpenAPIService.java:163   private final Map<String, OpenAPI> cachedOpenAPI;
// :221  this.cachedOpenAPI = createCachedOpenAPI(springDocConfigProperties.getCache().getMaxEntries());
// :234-241
private static Map<String, OpenAPI> createCachedOpenAPI(int maxEntries) {
    return Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        protected boolean removeEldestEntry(...) { return size() > maxEntries; }
    });
}
```

`springdoc.cache.max-entries` 默认 100（`SpringDocConfigProperties.java:1669` 对非正数回退）。这正是 **GHSA-rhhx-6j8h-8cvw**（3.1.1 CHANGELOG 首条：*"Unbounded per-locale OpenAPI cache allows memory exhaustion via Accept-Language"*）的修复：**从无界 HashMap 换成按 locale 的 LRU**。条件刷新则靠 `initOpenAPIBuilder`（`AbstractOpenApiResource.java:1495-1500`）：`isCacheDisabled()` 时整个 OpenAPIService 实例重建。

## 5.7 Framework 7 适配：API 版本化的豁免通道

**白话**：Spring Framework 7 给 `@RequestMapping` 加了 API 版本化（本系列《Spring Framework.md》1.6.2 表）。springdoc 自己的端点没有版本头，会被 Framework 7 的版本校验拒掉——于是 v3.0.0 给所有 `RequestMappingHandlerMapping` 包了一层策略。

【源码证据】`SpringDocWebMvcConfiguration.java:171-192` 注册 `springDocApiVersionCustomizer`：给每个 `RequestMappingHandlerMapping` 包 `SpringDocApiVersionStrategy`，让 springdoc 自身端点（/v3/api-docs、/swagger-ui/**）豁免版本校验。对应 CHANGELOG [3.0.0] 的 `#2975 - Spring Framework 7 - Initial API versioning support`。MCP 模块的 `McpWebMvcAiAutoConfiguration.java:117-124` 对 /mcp 端点做同样包装。

## 5.8 本章小结

AbstractOpenApiResource 的哲学是"**懒 + 锁 + 缓存 + 责任链**"：首次请求才扫描（除非预热）、全程持锁、按 locale 缓存（LRU 防爆）、扫描→过滤→翻译→定制四级流水。它继承 SpecFilter 白得断链清理，把 Web 栈差异压进 `getPaths` 一个抽象方法。下一章下潜到管线中段：类型→Schema 的转换与"参数平铺"这个 springdoc 特色发明。

# 六、模型转换与参数处理

## 6.1 ModelConverterRegistrar：springdoc 如何插队 swagger-core 的责任链

**白话**：springdoc 对 Schema 生成的所有"魔改"（Pageable 换形、File 识别、@Nullable 语义……）都是一组实现 swagger-core `ModelConverter` 接口的 Spring Bean。注册时的关键是：**先从单例链里摘掉同类旧实例再插入**——保证热更新与幂等。

【源码证据】`org/springdoc/core/converters/ModelConverterRegistrar.java:62-69`：

```java
public ModelConverterRegistrar(SpringDocConfigProperties springDocConfigProperties,
        Optional<List<ModelConverter>> modelConvertersOptional) {
    ModelConverters modelConverters = ModelConverters.getInstance(springDocConfigProperties.isOpenapi31()); // :63
    modelConvertersOptional.ifPresent(converters -> converters.forEach(converter -> {
        removeConverter(modelConverters, converter);   // :64-68 按类名去重（:98-103 反射读私有 converters 字段）
        modelConverters.addConverter(converter);
    }));
}
```

注册入口在 `SpringDocConfiguration.java:340-344`（收集容器里全部 `ModelConverter` bean）。

## 6.2 内置 converter 家族（18 个）

【源码证据】`org/springdoc/core/converters/` 全清单（Bean 注册位置见括号行号，`SpringDocConfiguration.java`）：

| Converter | 一句话职责 |
|---|---|
| AdditionalModelsConverter（:228） | 兜底通用转换；静态 `getParameterObjectReplacement` 被 `DelegatingMethodParameter.java:144` 用于参数替换 |
| FileSupportConverter（:254） | `MultipartFile`/`File` → `type: string, format: binary` |
| ResponseSupportConverter（:267） | `ResponseEntity`/`HttpEntity` 剥壳取 body 类型 |
| JsonNullableSupportConverter（:279） | org.openapitools.jackson.nullable.JsonNullable（"传 null 才置空"语义） |
| PageableOpenAPIConverter / SortOpenAPIConverter / PageOpenAPIConverter | Pageable/Sort/Page → 自定义模型（配 models 子包的 `DefaultPageable/Pageable/Sort/SortObject/MonetaryAmount` 七个替身模型） |
| PolymorphicModelConverter（:302-307） | 多态子类型（可由 `springdoc.model-converters.polymorphic.enabled=true` 打开，:1344 默认 false） |
| SchemaPropertyDeprecatingConverter（:290-301） | `@Deprecated` 属性标 `deprecated: true`（开关 :1283） |
| PropertyCustomizingConverter | **包装层**：把用户的 `PropertyCustomizer` 挂到每个属性生成后（仅当存在该 bean，:238-249） |
| OAS31ModelConverter（:542-548） | `explicit-object-schema=true` 且 3.1 时给对象显式补 `type: object` |
| HateoasLinksConverter / WebFluxSupportConverter / JavaTypeToIgnoreConverter / KotlinInlineClassUnwrappingConverter.kt / KotlinInlineParameterResolver / CollectionModelContentConverter | 名字即职责 |

## 6.3 为什么必须有一套独立 ObjectMapper

**白话**：你在 `application.yml` 里配置的 Jackson（命名策略、日期格式、模块）是给**业务 JSON**用的；OpenAPI 文档的序列化语义（$ref 清空、3.1 的 types、x- 扩展）由 swagger-core 的 mixin 决定。两套 ObjectMapper 一旦混用，文档格式就随业务配置漂移。

【源码证据】`org/springdoc/core/providers/ObjectMapperProvider.java:50` **extends swagger-core 的 `ObjectMapperFactory`**（3.6 节）：按 `apiDocs.version` 选 `Json31/Yaml31.mapper()` 并 addMixIn(SchemaTypeMixin)（:72-93），3.0 走 `Json.mapper()`（:87-88）；静态 `createJson`（:98-115）；`writer-with-order-by-keys` 的输出排序用 `sortOutput`（:120-135，排序 MixIn）。springdoc 还给自己的 mapper 追加 `SpringDocRequiredModule`、`SpringDocSealedClassModule`（`SpringDocConfiguration.java:460`）。UI 返回的最终 JSON 也带 `springdoc.writer-with-default-pretty-printer` 开关（属性 :144）。

## 6.4 @ParameterObject 与 DelegatingMethodParameter：springdoc 的参数平铺术

**白话**：Spring MVC 里 `public Page<User> list(UserQuery query)` 把整个 DTO 当一个对象绑定；但 API 文档的正确姿势是把 DTO 的每个字段展开成独立 query 参数。springdoc 的做法相当"外科手术"：**造一个假 MethodParameter 列表骗过swagger 的参数解析器**。

【源码证据】三件套：

1. **注解只是标记**：`org/springdoc/core/annotations/ParameterObject.java`——`@Target({PARAMETER, TYPE})`、零属性。
2. **展开判定与执行**：`DelegatingMethodParameter.java:139-162`——`hasFlatAnnotation`（:146，参数或类型上标了 @ParameterObject）；v3 新增**全局平铺**：`defaultFlatParamObject` 且非 @RequestBody/@RequestPart 且不在 ignore 名单（:147-150）；平铺时调 `methodParameterPojoExtractor.extractFrom(paramClass)`（:152），再过 `DelegatingMethodParameterCustomizer`（:153）。
3. **递归收集**：`MethodParameterPojoExtractor.extractFrom`（:157-171）用 Introspector 的 propertyDescriptors 逐字段 `fromGetterOfField`（:182-201）：简单类型直接产 `DelegatingMethodParameter`（record 走 RecordComponent accessor 分支 :263-270）；嵌套对象递归并加 `字段名.` 前缀（:197-199）。"简单类型"白名单在 `SIMPLE_TYPES`（:98-128，23 个类型）+ `SIMPLE_TYPE_PREDICATES`，可用 `addSimpleTypes/addSimpleTypePredicate` 扩展（:323-334）。

`DelegatingMethodParameter`（:65 `extends MethodParameter`）是整个技巧的关键：它重写 `getParameterAnnotations()`（:189-192，拼 delegate 注解 + @Parameter 附加 + 字段注解三者）和 `getParameterName()`（:195-197，返回点分名 `user.name`），于是下游所有"按 MethodParameter 生成 Parameter"的逻辑无感知地工作在平铺视图上。

## 6.5 javadoc 回填与函数式端点

- **javadoc**：v3 开关是 `springdoc.enable-javadoc`（默认 true，`SpringDocConfigProperties.java:224`；注意旧属性 `springdoc.api-docs.resolve-javadoc` 全仓已不存在）。`SpringDocJavadocConfiguration.java:55-58` 要求 classpath 有 `com.github.therapi:therapi-runtime-javadoc` 的 `CommentFormatter`；默认实现 `SpringDocJavadocProvider`（:55，:75-158 逐行解析源码注释）。消费点：`AbstractOpenApiResource.java:671-685`（方法描述与 return）、`OpenAPIService.buildTags:379-389`（类注释做 Tag description）。**效果**：不用写 @Operation，方法上方的 `/** 注释 */` 直接进文档。
- **函数式端点**：`RouterFunctionWebMvcProvider.getRouterFunctionPaths`（:68-80）`getBeansOfType(RouterFunction.class)`（:69）后用内部 `RouterFunctionVisitor`（:92，实现 RouterFunctions.Visitor + RequestPredicates.Visitor）遍历出 path/methods/consumes/produces；`AbstractOpenApiResource.getRouterFunctionPaths`（:879-912）若路由构建时挂了 OPERATION_ATTRIBUTE（`SpringdocRouteBuilder.path(...)` :584/:599，配合 fn/builders 下 19 个目录的链式 Builder DSL）则直接取 RouterOperation，否则回退读 `@RouterOperation/@RouterOperations` 注解（:890-909），`mergeRouters`（:1122-1202）按 **PATH → PATH+METHOD → +PRODUCES → +CONSUMES** 四级匹配把两边缝合。RouterOperation（`fn/RouterOperation.java:49-119`）承载 beanClass/beanMethod 等"回指代码"的信息。

## 6.6 本章小结

springdoc 的"厚"不在扫描而在翻译：18 个 converter 插队 swagger-core 责任链、独立 ObjectMapper 锁死文档序列化语义、DelegatingMethodParameter 用"假参数"实现 DTO 平铺、javadoc 直接搬运注释。这些机制共同服务于一个目标——**不写注解也得到像样的文档**。

---

# 七、定制点大全与多分组

## 7.1 定制点全景：9 个函数式接口与它们的调用位置

**白话**：springdoc 把"改文档"切成五个粒度（全局文档 → 单操作 → 参数 → Schema 属性 → 构建期），每个粒度一个函数式接口。用 Lambda 一行注册，比 springfox 时代写 Plugin 轻了两个数量级。

【源码证据】`org/springdoc/core/customizers/`（全部 @FunctionalInterface，行号指各文件声明处）：

| 接口 | 粒度 | 调用位置（源码证据） |
|---|---|---|
| OpenApiBuilderCustomizer（:36-37） | OpenAPIService 构建期 | OpenAPIService.java:292 |
| ServerBaseUrlCustomizer（:36-37） | server url | OpenAPIService.java:515-519 |
| OpenApiLocaleCustomizer（:38-39） | 按 locale | AbstractOpenApiResource.java:429 |
| **OpenApiCustomizer**（:36-38） | 终稿 OpenAPI | AbstractOpenApiResource.java:430 |
| GlobalOpenApiCustomizer（:35，无标记） | 同上，全局注入每个分组 | 经 SpringDocCustomizers 聚合 |
| **OperationCustomizer**（:40-41） | 单 Operation | AbstractOpenApiResource.java:693（Global 三参重载 :1095-1096） |
| GlobalOperationCustomizer / GlobalOperationComponentsCustomizer（:36/:41） | 同上 | 同上 |
| **ParameterCustomizer**（:39-40） | 单 Parameter | AbstractRequestService.java:390-391 |
| DelegatingMethodParameterCustomizer（:39-40） | 平铺后的假参数 | AbstractRequestService.java:328 |
| **PropertyCustomizer**（:38-39） | Schema 属性 | 经 PropertyCustomizingConverter 挂链（SpringDocConfiguration.java:238-249） |
| RouterOperationCustomizer / DataRestRouterOperationCustomizer | 路由/DR 路由 | AbstractOpenApiResource.java:582/:846-850 |
| OpenApiMethodFilter | 方法放行 | :958-963 |

内置实现可当示例读：`ActuatorOpenApiCustomizer`(:50)（actuator 端点统一塞 `x-actuator` tag）、`OperationIdCustomizer`(:40)、`SpecPropertiesCustomizer`(:96)、`ParameterObjectNamingStrategyCustomizer`(:45)、`QuerydslPredicateOperationCustomizer`(:71)。

**用法一屏**（v3 与 v2 相同）：

```java
@Bean
OpenApiCustomizer securityFix() {
    return openApi -> openApi.getComponents()
        .addSecuritySchemes("bearer", new SecurityScheme()
            .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"));
}
```

## 7.2 GroupedOpenApi：分组的"声明式描述"

**白话**：多分组是大型单体拆分文档的手段：`/v3/api-docs/users`、`/v3/api-docs/orders` 各是一份完整 OpenAPI。GroupedOpenApi 本身只是个描述（Builder 模型），真正的物化在 7.3。

【源码证据】`org/springdoc/core/models/GroupedOpenApi.java`：

- Builder 字段（:325-379）：group/displayName、pathsToMatch/Exclude、packagesToScan/Exclude、produces/consumes/headersToMatch + 四类定制器/过滤器集合。
- **构造校验**（:124-150）：group 非空（:125）；**"所有过滤条件与定制器全空"直接抛 IllegalStateException**（:139-149）——防止定义了一个和默认文档一模一样的冗余分组。
- `addAllOpenApiCustomizer/addAllOperationCustomizer/addAllOpenApiMethodFilter`（:284-318）。

## 7.3 多分组的物化：一个分组一个 Resource 实例

**白话**：装配时每个 GroupedOpenApi 被翻译成一个 `GroupConfig` + 一个新的 `OpenApiWebMvcResource`（各自带定制器集合），`/v3/api-docs/{group}` 只是路由到对应实例。

【源码证据】：

- 启用条件：`MultipleOpenApiSupportConfiguration.java:69` `@Conditional(MultipleOpenApiSupportCondition.class)`（AnyNestedCondition：容器里有 GroupedOpenApi bean，或 `springdoc.group-configs` 非空）。
- 物化：`AbstractMultipleOpenApiResource.afterPropertiesSet`（:126-144）——先把 Global 定制器并入每个分组（:128-133），再为每个分组 new `GroupConfig` 塞进 `springDocConfigProperties.addGroupConfig`（:138-139），随后 `buildOpenApiResource(item)`（webmvc 实现 `MultipleOpenApiResource.java:73-96`：普通端口组 → new OpenApiWebMvcResource，管理端口组 → OpenApiActuatorResource，均携带分组自己的 `SpringDocCustomizers`）。
- 端点：`MultipleOpenApiWebMvcResource.java:93` `@GetMapping(API_DOCS_URL + "/{group}")`，找不到分组抛 `OpenApiResourceNotFoundException`（:160-166）。
- 回看 5.1 节：AbstractOpenApiResource 构造器按 groupName 匹配 GroupConfig（:267-270）——**声明（GroupedOpenApi）、配置（GroupConfig）、执行（带 groupName 的 Resource）三者在此闭环**。

---

# 八、UI 集成与端点全景

## 8.1 一张表看清全部端点

| 端点 | 提供者 | 证据 |
|---|---|---|
| `GET /v3/api-docs`（JSON） | OpenApiWebMvcResource `@GetMapping(API_DOCS_URL)` 返回 byte[] | webmvc-api/OpenApiWebMvcResource.java:113-115 |
| `GET /v3/api-docs.yaml` | 同上 | :130-132 |
| `GET /v3/api-docs/{group}` | MultipleOpenApiWebMvcResource | :93 |
| `GET /v3/api-docs/swagger-config`（UI 启动配置 JSON） | SwaggerConfigResource | webmvc-ui/SwaggerConfigResource.java:68-71 |
| `/swagger-ui/**`（静态资源） | SwaggerWebMvcConfigurer 资源映射 | AbstractSwaggerConfigurer.java:58-75 |
| `/swagger-ui.html`（302 入口） | SwaggerWelcomeWebMvc | Constants.java:247/252、SwaggerWelcomeCommon.java:70-82 |
| `/`（根路径，可选） | SwaggerUiHome（use-root-path=true） | webmvc-ui/SwaggerUiHome.java:64-72 |
| 管理端口模式：`/actuator/openapi`、`/actuator/swagger-ui` | OpenApiActuatorResource（`@RestControllerEndpoint(id="openapi")`）、SwaggerWelcomeActuator | OpenApiActuatorResource.java:61-63、SwaggerWelcomeActuator.java:52 |

路径常量的占位符写法值得注意：`Constants.java:62` `API_DOCS_URL = "${springdoc.api-docs.path:#{T(...Constants).DEFAULT_API_DOCS_URL}}"`——默认值 `/v3/api-docs`（:52）可直接被属性覆盖，注解里写 SpEL。

## 8.2 UI 资源映射：webjar 的版本化 classpath

**白话**：Swagger UI 前端完全是外部 webjar（`org.webjars:swagger-ui:5.32.14`，根 pom.xml:63）。springdoc 把 `/swagger-ui/**` 映射到 webjar 内部路径，并且**用"过滤后的 properties"把 webjar 版本号动态注入路径**——升级 UI 只需换依赖版本。

【源码证据】`org/springdoc/ui/AbstractSwaggerConfigurer.java:58-75`：

```java
String swaggerUiPattern = getUiRootPath() + SWAGGER_UI_PREFIX + ALL_PATTERN;
String swaggerUiResourceLocation = WEBJARS_RESOURCE_LOCATION + SWAGGER_UI_WEBJAR_NAME + "/"
        + swaggerUiConfigProperties.getVersion() + "/";
return new SwaggerResourceHandlerConfig[]{
    SwaggerResourceHandlerConfig.createCached()
        .setPatterns(swaggerUiPattern).setLocations(swaggerUiResourceLocation),
    SwaggerResourceHandlerConfig.createUncached()          // index/initializer 不缓存
        .setPatterns(swaggerUiIndexPattern, swaggerUiInitializerPattern).setLocations(...)
};
```

version 的来源：`springdoc-openapi-starter-common/src/main/resources/springdoc.config.properties` 内容为 `springdoc.swagger-ui.version=@swagger-ui.version@`（Maven filtering 注入），`SwaggerUiConfigProperties.afterPropertiesSet`（:109-120）启动时回填。index.html 与 swagger-initializer.js 走 `Cache-Control: no-store`（webmvc-ui/SwaggerWebMvcConfigurer.java:147-164），因为它们要被 8.3 节的改写器动态加工。

## 8.3 initializer.js 七连改写：springdoc 的"参数注入针管"

**白话**：Swagger UI 5.x 的启动参数写在静态文件 `swagger-initializer.js` 的 `SwaggerUIBundle({...})` 里。springdoc 不能改 webjar 文件，于是用 Spring 的 ResourceTransformer 在**响应流里做文本手术**，把服务端配置注入进去。

【源码证据】`org/springdoc/ui/AbstractSwaggerIndexTransformer.defaultTransformations`（:157-190），webmvc 侧触发条件是 URL 匹配 `**/swagger-ui/**/swagger-initializer.js`（`SwaggerIndexPageTransformer.java:79`，命中后包成 `TransformedResource` :82-83）。七步：

1. **OAuth 配置**：`addInitOauth` 注入 `ui.initOAuth({...})`（:159-160；方法 :102-109，来自 SwaggerUiOAuthProperties）。
2. **CSRF**：`csrf.enabled=true` 时按 cookie/localStorage/sessionStorage 三种来源注入 `requestInterceptor`（:162-169；实现 :243-261）——让 Try it out 的写请求带上 CSRF 头。
3. **语法高亮**（:171-172；:317-334）。
4. **配置参数注入**：`queryConfigEnabled` 非 true 时，把 `SwaggerUiConfigParameters.getConfigParameters()` 的键值（configUrl、deepLinking、operationsSorter、urls 按 groupsOrder 排序 :309-320……validatorUrl 为 null 时置空串以禁用 UI 默认的在线校验 :274）全部写进 `SwaggerUIBundle({...})`（:174-175；addParameters :200-221，排除 oauth2RedirectUrl/url/urls 三个保留键）。
5. **删 petstore 示例**：`disable-swagger-default-url` 时删除默认 URL（:179-180；:135-137）。
6. **直连 url**：配了 `springdoc.swagger-ui.url` 且无 configUrl 时直接替换（:182-184）。
7. **documentTitle**（:186-187；:342-351）。

测试对这条链有逐字符契约：`AbstractSpringDocTest.java:37-42` 断言改写后的 initializer.js 含 `window.ui`、带 no-store 响应头、且与期望文件**全量相等**。

## 8.4 重定向链：从 `/swagger-ui.html` 到 Try it out

**白话**：用户敲 `/swagger-ui.html`，springdoc 302 到 `/swagger-ui/index.html`（UI 模式）；index.html 里的 initializer.js 已被注入 `configUrl`；UI 先拉 `swagger-config` 拿到分组列表，再拉 api-docs 渲染。

【源码证据】：

- `SwaggerWelcomeWebMvc.java:78` `@GetMapping(SWAGGER_UI_PATH)`（即 `${springdoc.swagger-ui.path:/swagger-ui.html}`，`Constants.java:247/252`）→ `SwaggerWelcomeCommon.redirectToUi`（:70-82）：拼 `contextPath + uiRootPath + swagger-ui/index.html`，`queryConfigEnabled=true` 时把参数当查询串，返回 302。测试契约：`SpringDocRedirectDefaultTest.java:33-37` 断言 `Location: /swagger-ui/index.html`。
- configUrl 的拼装：`AbstractSwaggerWelcome.buildConfigUrl`（:133-164）= `buildApiDocUrl`(:247-249) + `buildSwaggerConfigUrl`(:256-258，即 apiDocsUrl + "/swagger-config")；`oauth2RedirectUrl` 兜底 `swagger-ui/oauth2-redirect.html`（SwaggerWelcomeCommon.java:101-107）。
- 多分组时 swagger-config 返回 `urls: [...]`（每组一个下拉项）；测试 `SpringDocSwaggerConfigTest.java:43-50` 断言 urls 含 `x-actuator` 组。

## 8.5 actuator 双端口模式

**白话**：生产环境常把 API 文档放到管理端口。`springdoc.use-management-port=true` 时，文档与 UI 从业务端口消失，整体搬到 actuator 端点体系。

【源码证据】（webmvc-api）：

- `SpringDocWebMvcConfiguration.java:115-125`：默认 `OpenApiWebMvcResource` 注册条件 `use-management-port==false && enable-default-api-docs`——管理端口模式直接不注册。
- `OpenApiActuatorResource.java:61-63` `@RestControllerEndpoint(id = "openapi")`：GET `/actuator/openapi`（:113）与 `/yaml`（:129）。
- `MultipleOpenApiSupportConfiguration.java:106-137` 内嵌 DIFFERENT 管理端口配置注册 MultipleOpenApiActuatorResource；UI 侧 `SwaggerConfig.java:205-236` 的 `SwaggerActuatorWelcomeConfiguration` 换用 `SwaggerWelcomeActuator`（configUrl 指向管理端口的 swagger-config :122-126）。
- `springdoc.show-actuator=true`（业务端口场景）则由 `ActuatorWebMvcProvider.getMethods()`（:67-80，合并 `WebMvcEndpointHandlerMapping` + `ControllerEndpointHandlerMapping`）把 actuator 端点**当普通端点扫描进文档**（5.3 节第 2 路）。

## 8.6 WebFlux 与 Scalar 对照

- **WebFlux**：`SwaggerWebFluxConfigurer`（webflux-ui :51，implements WebFluxConfigurer）的 addResourceHandlers 与 webmvc 版逐行同构（:106-146），仅 webjars-path-pattern 取值不同（:157-159）；`OpenApiWebFluxResource` 返回 **`Mono<byte[]>`**（:115-117）。reactive 栈的文档端点仍然是"注解 @RestController"——扫描的端点和被扫描的端点栈不必一致。
- **Scalar**：结构是 UI 模块的瘦身版——无 transformer、无 configResource（Scalar 前端自己拉 api-docs）：`ScalarConfiguration.java:66-73`（`@ConditionalOnProperty("scalar.enabled", matchIfMissing=true)`）注册 `ScalarWebMvcController`（`@Controller @RequestMapping("${scalar.path:/scalar}")` :48-50，getDocs :69-75 生成一段引 webjar 的 HTML；依赖 `com.scalar.maven:scalar-webmvc` 0.5.55）。

## 8.7 本章小结

UI 集成的全部秘密是三件事：**外部 webjar + 版本化路径映射**（8.2）、**ResourceTransformer 文本手术注入配置**（8.3）、**两级重定向 + swagger-config 元端点**（8.4）。springdoc 对 UI 前端零侵入、零内嵌——UI 与引擎的耦合点只剩一个 `springdoc.swagger-ui.version` 属性和一份注入后的 initializer.js。

---

# 九、MCP：API 文档成为 AI 的工具箱（v3 新增）

## 9.1 白话与依赖

**白话**：Model Context Protocol（MCP）是 2024 年底出现的"给大模型接工具"的开放协议。springdoc v3 新增的 MCP 模块回答了一个前沿问题：**既然 `/v3/api-docs` 已经机器可读，能不能把整个 REST API 直接变成大模型可调用的工具集？** 它的做法：遍历 OpenAPI 的 paths，把每个 operation 包成一个 Spring AI `ToolCallback`，再注册进 MCP Server——大模型通过 MCP 调"工具"，实际是对你的服务发一次真实 HTTP 请求。

【源码证据】依赖：`springdoc-openapi-starter-common-mcp/pom.xml:16-29` 引 `spring-ai-mcp`（Spring AI 2.0.0，根 pom.xml:72；底层 `io.modelcontextprotocol` mcp-core SDK 2.0.0，:73）。

## 9.2 OpenApiMcpToolCallbackProvider：从 paths 到工具

【源码证据】`org/springdoc/ai/mcp/OpenApiMcpToolCallbackProvider.java`：

- `implements ToolCallbackProvider`（:64，Spring AI 的工具提供者接口）；
- `getToolCallbacks()`（:120-131）读 `openAPIService.getCachedOpenAPI()`——**直接复用 5.6 节的缓存，零额外扫描**；
- `buildToolCallbacks`（:139-181）遍历 `openAPI.getPaths()`，对每个有 operationId 的 operation 构造 `OpenApiToolCallback`（:68）；支持 `springdoc.ai.mcp.paths-to-exclude`（:147-154）与 `McpToolCustomizer`（:161-168）定制；
- `OpenApiToolCallback.call(toolInput)`（:334）**向本服务发 HTTP**（baseUrl 取 `springdoc.ai.mcp.base-url`，缺省 `http://localhost:server.port` :195-201）执行对应请求；`getToolDefinition()`（:270）产出工具元数据。

## 9.3 装配、时序与安全阀

【源码证据】：

- 开关：`SpringDocAiAutoConfiguration.java:73-77` `@ConditionalOnProperty("springdoc.ai.mcp.enabled", matchIfMissing=false)`——**opt-in，默认关**；属性类 `SpringDocAiProperties`（`@ConfigurationProperties("springdoc.ai.mcp")` :49）：`mcpEndpoint="/mcp"`(:83)、`dashboardPath="/mcp-ui"`(:98)、`initTimeoutSeconds=30`(:73)、`audit.redact=true`(:266)。
- **时序耦合**：工具依赖 OpenAPI 缓存就绪（首次请求前缓存为空），于是 `McpDeferredToolRegistration`（:198-249，`@ConditionalOnClass(McpSyncServer, McpToolUtils)`）**轮询等待缓存就绪**后逐个 `mcpServer.addTool(...)` 并 `notifyToolsListChanged()`（:221-249），超时上限即 initTimeoutSeconds；启动期还有一条未开启依赖时的告警日志（:141-143/:176-182）。
- **安全阀**：写操作默认要求人工确认——`requireApprovalForMutatingTools=true`（`SpringDocAiProperties.java:309`）；配套审计：`McpWebMvcAiAutoConfiguration`（webmvc-mcp :60-63）注册 `McpAuditMdcFilter`（:72-75）与两个审计切面（SpringDocAiAutoConfiguration :83-100）。
- 附赠看板：`McpDashboardAutoConfiguration`（:51-53）在 dashboard-enabled 时注册 `McpDashboardController`（`@RestController @RequestMapping("/api/mcp-admin")`，GET /tools :89、GET /audit :149）。
- API 版本化适配：/mcp 端点同样要 Framework 7 豁免（`McpWebMvcAiAutoConfiguration.java:117-124`，同 5.7 节）。

## 9.4 本章小结

MCP 模块是"契约多消费者"世界观的最新一集：同一份 `OpenAPI` 对象，2019 年喂给网页，2026 年开始喂给大模型。技术上它没有任何新扫描逻辑——纯消费层（缓存 → ToolCallback → McpSyncServer），但 `requireApprovalForMutatingTools`、审计过滤器、opt-in 开关三件套表明作者对"AI 自动调用生产 API"的风险有清醒认知。

---

# 十、springfox 兴亡：一个时代的背影

> 本章对应源码：`D:\code\3rd\springfox`（最后提交 2020-10-13，tag 3.0.0 = 2020-07-13 21:54 -0500，即 UTC 07-14）。

## 10.1 架构取证：DocumentationPlugin 与 Docket

**白话**：springfox 的世界观是"文档是插件系统的一个产物"：每个 `Docket` 就是一个 DocumentationPlugin，SmartLifecycle 引导器在容器启动后统一扫描、缓存，Controller 只做缓存读取。它比 springdoc "重"在：用户必须显式声明插件、必须理解 select() 选择器 DSL、必须处理 2.0/3.0 两套输出模型。

【源码证据】：

- **SPI**：`springfox-spi/.../spi/service/DocumentationPlugin.java:27` `interface DocumentationPlugin extends Plugin<DocumentationType>`（基于 spring-plugin 的 PluginRegistry）；方法 `isEnabled()`(:31)、`getDocumentationType()`(:33)、`configure(DocumentationContextBuilder)`(:41)、`getGroupName()`(:47)。`DocumentationType.java:26-31` 定义三档：`SWAGGER_12("swagger","1.2")`、`SWAGGER_2("swagger","2.0")`、`OAS_30("openApi","3.0")`。
- **Docket 即插件**（常见误解澄清：**仓库里不存在 Swagger2DocumentationPlugin/OpenApiPlugin 类**，git grep 实证）——`springfox-spring-web/.../spring/web/plugins/Docket.java:73` `public class Docket implements DocumentationPlugin`；类 javadoc（:68-71）自述 *"A builder which is intended to be the primary interface into the Springfox framework."*。用户写 `new Docket(DocumentationType.OAS_30)`，`select()`（:492-494）返回 `ApiSelectorBuilder`（paths/produces/consumes…），最终 `configure()`（:503-534）把全部状态灌进 `DocumentationContextBuilder`。
- **引导器**：`DocumentationPluginsBootstrapper.java:47-51` `@Component implements SmartLifecycle`（getPhase = MAX_VALUE :116-118）；`start()`（:97-102）一次性触发父类 `AbstractDocumentationPluginsBootstrapper.java:73-89` 的流程：`documentationPluginsManager.documentationPlugins()` 排序 → 逐个 `if(each.isEnabled()) scanDocumentation(buildContext(each))` → **结果存进 DocumentationCache**（:94-105）。
- **交付**：`/v2/api-docs` 由 `springfox-swagger2/.../web/Swagger2ControllerWebMvc.java:58-62`（路径 `${springfox.documentation.swagger.v2.path:/v2/api-docs}` :34-35，GET produces json+HAL :86-88）；`/v3/api-docs` 由 `springfox-oas/.../web/OpenApiControllerWebMvc.java:52-56`。**Controller 读 DocumentationCache 出 JSON**——扫描发生在启动期（对比 springdoc 的首次请求扫描，第十一章时间线一将再次对比这一点）。
- **启用**：`@EnableSwagger2`（springfox-swagger2 `annotations/EnableSwagger2.java:42` `@Import(Swagger2DocumentationConfiguration.class)`）；`@EnableOpenApi`（springfox-oas :41）。UI 是构建期从 GitHub 下载 swagger-ui **3.26.2** zip 打成的 webjar（`springfox-swagger-ui/build.gradle:24`）。

## 10.2 架构对照：springfox vs springdoc

| 维度 | springfox 3.0.0 | springdoc 3.x |
|---|---|---|
| 启用方式 | `@EnableSwagger2`/`@EnableOpenApi` + 必须声明 Docket bean | 零注解、零声明，starter 即用 |
| 分组模型 | 多个 Docket（每个是一个 DocumentationPlugin） | GroupedOpenApi 描述 + 自动物化（7.3） |
| 扫描时机 | SmartLifecycle 启动期 → DocumentationCache | 首次请求（可 pre-loading 预热）→ LRU 缓存 |
| 端点 | /v2/api-docs + /v3/api-docs 并存 | 仅 OAS 3.x（/v3/api-docs，默认 3.1） |
| Schema 生成 | 自研 ModelProvider/AlternateTypeResolver 体系（springfox-schema 模块 53 类） | 委托 swagger-core ModelConverters 责任链（3.4/6.1） |
| WebFlux | 独立模块 springfox-spring-webflux（RequestHandler 适配） | starter-webflux-* 同构 |
| 定制 | Plugin + PluginRegistry + Enricher（重量级） | 9 个函数式定制器（7.1） |
| 依赖基线 | Spring 5.2.7 / Boot 2.3.1 / javax | Boot 4.1 / Framework 7 / jakarta |

## 10.3 死亡证据链

- **活跃度**：`git log --since=2020-01-01 --oneline | wc -l` = 471，但 3.0.0（2020-07-13）后仅剩 stale-bot 规则更新、UI URL 修正、NPE 修复与 dependabot 合并；**最后一次提交 2020-10-13**（ab5868471c）。
- **依赖冻结**：`gradle/dependencies.gradle`——`spring = "5.2.7.RELEASE"`(:22)、`springBoot = "2.3.1.RELEASE"`(:28)、`servlet = "4.0.1"`(:18)、`swagger2Core = "1.5.20"`(:27)、`oasCore = "2.1.2"`(:17)；全仓 grep 无 jakarta.servlet 迁移（`Swagger2ControllerWebMvc.java:49` 仍 `import javax.servlet.http.HttpServletRequest`）。
- **时代背景**：Boot 2.6 起默认 PathPattern 匹配策略与 springfox 的 AntPathMatcher 假设冲突（社区大量 issue），Boot 3 / Framework 6 的 jakarta 迁移更是物理性断代；仓库 README 无任何维护声明（仍推荐 3.0.0）——**silent EOL**。
- 本系列《Spring REST Docs.md》6.4 节的结论在此得到源码级印证："所有还在教 `@EnableSwagger2`/`Docket` 的教程都该判为过时内容"。

## 10.4 迁移对照表（Docket → springdoc）

| springfox 写法 | springdoc 对应 |
|---|---|
| `new Docket(DocumentationType.OAS_30).select().paths(PathSelectors.ant("/api/**")).build()` | `GroupedOpenApi.builder().group("x").pathsToMatch("/api/**").build()`（7.2） |
| `.apiInfo(new ApiInfo(...))` | `@Info` 注解（@OpenAPIDefinition）或 OpenApiCustomizer 设置 `openAPI.setInfo(...)` |
| `.enable(false)` | `springdoc.api-docs.enabled=false` |
| `.host(...)` / `.protocols(...)` | `openAPI.setServers(...)`（OpenApiCustomizer）或 `springdoc.api-docs.resolve...`/forward-headers |
| `.securitySchemes(...)` | `openAPI.getComponents().addSecuritySchemes(...)`（7.1 示例） |
| `@ApiIgnore` | `@Hidden`（swagger-annotations） |
| `@ApiModel/@ApiModelProperty` | `@Schema` |
| `@ApiOperation(value=...)` | `@Operation(summary=...)` |
| `@ApiParam` | `@Parameter` |
| springfox-swagger-ui 资源 | `springdoc-openapi-starter-webmvc-ui`（webjars swagger-ui 5.32.14） |

---

# 十一、贯通视图：三条时间线看懂 Swagger 全貌

## 11.1 时间线一：应用启动（装配但不扫描）

```
Boot 启动 → AutoConfigurationImportSelector 读各 starter 的 .imports
  ├─ starter-common 16 条：SpringDocConfiguration 装配 SpringDocProviders/SpringDocCustomizers 聚合
  │    ├─ SpringDocPageableConfiguration / SpringDocJavadocConfiguration / … 能力开关
  │    └─ ModelConverterRegistrar：把容器里的 ModelConverter 插进 swagger-core 责任链
  ├─ webmvc-api 2 条：SpringDocWebMvcConfiguration（openApiResource 注册 + Framework7 版本豁免）
  │    └─ MultipleOpenApiSupportConfiguration（有分组时）→ afterPropertiesSet 物化每分组一个 Resource
  └─ webmvc-ui 3 条：SwaggerConfig 注册资源映射/重定向/Transformer
       └─ OpenApiWebMvcResource 挂上 GET /v3/api-docs（注意：此刻还没生成任何文档！）

（可选）springdoc.pre-loading-enabled=true → 单线程预热 getOpenApi（AbstractOpenApiResource:271-280）
对比 springfox：启动期 SmartLifecycle 已完成全部扫描进 DocumentationCache——
springdoc 改为"懒扫描"，换来的是启动零开销与首次请求的最新事实。
```

## 11.2 时间线二：GET /v3/api-docs（一次请求的 14 步）

```
1  DispatcherServlet → OpenApiWebMvcResource.openapiJson（@GetMapping /v3/api-docs）
2  → AbstractOpenApiResource.getOpenApi(serverBaseUrl, locale)
3  持 reentrantLock；selectLocale（allowedLocales 匹配）
4  缓存查询：OpenAPIService.cachedOpenAPI.get(locale)  —— LRU，maxEntries=100
   ├─ 命中 → 跳 11（仍执行 updateServers）
   └─ 未命中 ↓
5  openAPIService.build(locale)：组装 OpenAPI 骨架（info/tags/servers），收集 controller mappings
6  过滤：类级 @Hidden / isHiddenRestControllers
7  （3.1）openapi("3.1.0") + specVersion(V31) + calculateWebhooks（扫 @Webhooks 注解类）
8  buildGenericResponse：ControllerAdvice 的通用响应进 components
9  getPaths（webmvc）：四路汇合——RequestMappingHandlerMapping 全量 / DataREST / actuator / RouterFunction
10 逐端点：五道过滤器（5.4）→ calculatePath → readOperation（注解合并→参数→请求体→响应→javadoc→callbacks）
        → OperationCustomizer；类型转换走 swagger-core ModelConverters 责任链（ModelResolver 出 Schema）
11 removeBrokenReferenceDefinitions（SpecFilter 继承）→ OpenApiLocaleCustomizer → OpenApiCustomizer
12 setCachedOpenAPI(locale) 写缓存 + 耗时日志
13 ObjectMapperProvider（Json31）序列化 → byte[]
14 响应 200 application/json（springdoc.writer-with-default-pretty-printer 决定是否美化）
```

## 11.3 时间线三：Swagger UI 渲染与 Try it out

```
浏览器 GET /swagger-ui.html
  → SwaggerWelcomeWebMvc 302 → /swagger-ui/index.html（SwaggerWelcomeCommon.redirectToUi）
GET /swagger-ui/index.html
  → AbstractSwaggerConfigurer 资源映射 → webjars swagger-ui 5.32.14（createUncached，no-store）
GET /swagger-ui/swagger-initializer.js
  → SwaggerIndexPageTransformer 命中 **/swagger-ui/**/swagger-initializer.js
      → defaultTransformations 七连改写（8.3）：注入 configUrl=/v3/api-docs/swagger-config 等
GET /v3/api-docs/swagger-config
  → SwaggerConfigResource → SwaggerWelcomeCommon.openapiJson：urls[]（各分组）+ configUrl + oauth2RedirectUrl
GET /v3/api-docs（或 /v3/api-docs/{group}）          ← 时间线二在这里发生
  → Swagger UI 渲染 paths/schemas（3.3 的模型在这里变成网页里的表格）
[Try it out] → UI 直接按 PathItem+Schema 构造请求发往服务端
  （csrf.enabled 时 initializer.js 注入的 requestInterceptor 会带上 CSRF 头）
  → 响应回到 UI 的 responses 面板——契约与实现闭环
```

## 11.4 收束：两条路线，一个生态

把三条时间线并排看，Spring 世界的 Swagger 全景是：

- **springdoc 负责"事实"**：DispatcherServlet 的运行时映射、参数解析、javadoc——保证文档是代码的投影；
- **swagger-core 负责"表达"**：注解补语义、模型存结构、ModelResolver 做 Jackson→Schema 的翻译；
- **Swagger UI / Scalar / MCP 负责"消费"**：同一份契约分别喂给人（网页）、给客户端（codegen 的原料）、给 AI（工具箱）。

它与方法论上的对手（REST Docs：文档从通过的测试里长出来）不是替代关系——双轨共存的架构原因（REST Docs 活在测试 JVM，springdoc 活在生产运行时，连依赖都不相遇）已在《Spring REST Docs.md》6.8 节论证，此处不赘。

---

# 十二、实战指南：Boot 4.1.1 新项目从零接入（小白向）

> 本章是操作手册，与前十一章的源码解析互为表里：每一步操作后面标注它在源码里对应哪个机制（回指章节）。版本基线：**Spring Boot 4.1.1 + springdoc-openapi 3.1.1**（v3 线最新 GA，2026-09-06，含 GHSA-rhhx-6j8h-8cvw 安全修复，见 5.6 节）。Maven/Gradle 任选其一。

## 12.1 选型结论：引什么、绝不引什么

| 决策 | 结论 | 原因 |
|---|---|---|
| 引什么依赖 | `org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1` | Boot 4.x 对应 springdoc v3 线（版本配套表见 1.6.6 节） |
| 要不要写注解 | 可选，建议补一点 | 引依赖后**零注解就出文档**（1.2 节哲学 2）；`@Tag/@Operation/@Schema` 只补描述 |
| 绝不引什么 | springfox、`@EnableSwagger2`、`Docket`、`@Api/@ApiOperation` | springfox 已死（第十章）；这些是上一代写法 |

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>3.1.1</version>
</dependency>
```

> Gradle：`implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1")`

【源码依据】Boot 4 下 `spring-boot-starter-web` 仍然存在（Boot 4.2 仓库 starter/ 目录实证），同时新增了更细粒度的 `spring-boot-starter-webmvc`，两者皆可；springdoc starter 内部依赖的正是 Boot 4 拆分后的 `spring-boot-webmvc` 模块（4.2 节依赖表）。

## 12.2 第一步：3 分钟跑起来（零配置）

启动应用，直接访问三个端点：

- **交互页面**：`http://localhost:8080/swagger-ui.html`——会 302 到 `/swagger-ui/index.html`（重定向链见 8.4 节），页面上可点 Try it out 直接发请求；
- **机器可读文档**：`http://localhost:8080/v3/api-docs`（OpenAPI **3.1** 格式 JSON，默认值见 4.3 节）与 `/v3/api-docs.yaml`；
- 这份 JSON 可直接导入 Postman / Apifox / Swagger Editor，也可喂给 OpenAPI Generator 生成客户端 SDK。

此刻你已经拥有：全部 Controller 的路径、参数、返回值 Schema、DTO 字段列表——**没有写任何配置类、任何启用注解**。这背后就是 4.2 节的自动配置链在 boot。

## 12.3 第二步：application.yml 常用配置

默认值已经很好，下面是"常用微调"版（每项默认值的完整大表见 4.3 节）：

```yaml
springdoc:
  api-docs:
    path: /v3/api-docs        # JSON 文档路径，默认即它
    # version: OPENAPI_3_0    # 默认输出 OAS 3.1；老工具（旧版 Postman/Apifox）导入报错时改回 3.0
  swagger-ui:
    path: /swagger-ui.html    # 页面入口，默认即它
    operations-sorter: method # 接口按 HTTP 方法排序（alpha=按字母）
    tags-sorter: alpha        # 分组标签按字母排序
    try-it-out-enabled: true  # 默认已开启
    disable-swagger-default-url: true  # 顶部不显示官方 petstore 示例（8.3 节改写器第⑤步）
  cache:
    max-entries: 100          # 默认 100（3.1.1 安全修复项，保持默认即可）
```

## 12.4 第三步：DTO 与 Controller 代码示例

推荐结构：

```
src/main/java/com/example/demo/
├── DemoApplication.java
├── config/SwaggerConfig.java        （12.5 节）
├── controller/UserController.java
└── dto/UserCreateRequest.java
```

**DTO——`@Schema` 贴在类和字段上：**

```java
@Schema(description = "创建用户的请求")
public class UserCreateRequest {

    @Schema(description = "用户名，全局唯一", example = "zhangsan",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String username;

    @Schema(description = "年龄，0~150", example = "18")
    private Integer age;

    // getter/setter 省略
}
```

**Controller——`@Tag` 标分组、`@Operation` 标接口：**

```java
@Tag(name = "用户管理", description = "用户的增删改查")
@RestController
@RequestMapping("/api/users")
public class UserController {

    @Operation(summary = "查用户列表", description = "支持按用户名关键字过滤")
    @GetMapping
    public List<String> list(
            @Parameter(description = "用户名关键字，可省略")
            @RequestParam(required = false) String keyword) { ... }

    @Operation(summary = "创建用户")
    @PostMapping
    public Long create(@RequestBody UserCreateRequest request) { ... }

    @Operation(summary = "删除用户")
    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) { ... }
}
```

两条小白必懂的原则：

1. **注解不是必须的**。把上面所有注解删掉，文档照样生成（3.5 节：Jackson 能看到的，Schema 就能看到）。注解的价值是补 `description/example/summary` 这类机器推断不出的语义。
2. **Bean Validation 自动进文档**。字段上加 `@NotBlank/@Size/@Min`（需引 `spring-boot-starter-validation`），Schema 自动带 `maxLength/minLength` 等约束——源码对应 swagger-core 的 `applyBeanValidatorAnnotations`（3.5 节），不必再重复写进 `@Schema`。

## 12.5 第四步：全局文档信息 + JWT 调试（两个 Bean）

```java
@Configuration
public class SwaggerConfig {

    /** 文档标题、版本、全局 JWT 安全定义 */
    @Bean
    public OpenApiCustomizer globalInfo() {
        return openApi -> openApi
                .info(new Info()
                        .title("Demo API")
                        .description("Spring Boot 4.1.1 学习项目接口文档")
                        .version("v1.0"))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }

    /** 全部接口自动带"需要登录"锁标志，页面右上角 Authorize 填 token 即可调试 */
    @Bean
    public OpenApiCustomizer globalSecurity() {
        return openApi -> openApi.addSecurityItem(
                new SecurityRequirement().addList("bearer"));
    }
}
```

这两个 Lambda 就是 7.1 节的 `OpenApiCustomizer`（调用点 `AbstractOpenApiResource.java:430`）——对应旧教程里 `Docket.apiInfo()/securitySchemes()` 干的事（迁移对照见 10.4 节）。

## 12.6 第五步（可选）：接口太多就分组

```java
@Bean
public GroupedOpenApi userApi() {
    return GroupedOpenApi.builder()
            .group("用户模块")
            .pathsToMatch("/api/users/**")
            .build();
}

@Bean
public GroupedOpenApi orderApi() {
    return GroupedOpenApi.builder()
            .group("订单模块")
            .pathsToMatch("/api/orders/**")
            .build();
}
```

页面右上角出现下拉框切换；每组各自是一份 `/v3/api-docs/{group}`。注意：**必须至少写一个过滤条件**（pathsToMatch/packagesToScan 等），全空会在启动期抛 `IllegalStateException`（7.2 节，`GroupedOpenApi.java:149`）。物化机制见 7.3 节。

## 12.7 可选进阶速览（每条 30 秒）

- **Javadoc 注释直接进文档**：v3 默认 `springdoc.enable-javadoc=true`（6.5 节），但需要让注释编译进 class——引 `com.github.therapi:therapi-runtime-javadoc:0.15.0`，并在 maven-compiler-plugin 的 `annotationProcessorPaths` 里注册它：

  ```xml
  <dependency>
      <groupId>com.github.therapi</groupId>
      <artifactId>therapi-runtime-javadoc</artifactId>
      <version>0.15.0</version>
  </dependency>
  ```

  ```xml
  <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-compiler-plugin</artifactId>
      <configuration>
          <annotationProcessorPaths>
              <path>
                  <groupId>com.github.therapi</groupId>
                  <artifactId>therapi-runtime-javadoc</artifactId>
                  <version>0.15.0</version>
              </path>
          </annotationProcessorPaths>
      </configuration>
  </plugin>
  ```

  之后方法上的 `/** 查用户列表 */` 自动成为文档描述，`@Operation` 都可以少写。（springdoc 自己的 pom 里该依赖是 optional 的——不引它不报错，只是 javadoc 没有内容可读。）
- **分页接口**：`list(Pageable pageable)` 开箱即用（springdoc 自带 Pageable/Sort 转换，6.2 节替身模型表）；自定义查询 DTO 加 `@ParameterObject`（`org.springdoc.core.annotations.ParameterObject`），字段会平铺成独立 query 参数（机制见 6.4 节）。
- **AI 场景**：引 `springdoc-openapi-starter-webmvc-mcp` 并设 `springdoc.ai.mcp.enabled=true`，整个 API 变成 MCP 工具集供大模型调用（写操作默认要求人工确认）——见第九章。
- **监控端点进文档**：引 actuator 后设 `springdoc.show-actuator=true`（8.5 节）。
- **换一套 UI**：引 `springdoc-openapi-starter-webmvc-scalar` 即得 Scalar 界面（8.6 节），与 Swagger UI 可共存。

## 12.8 上生产前必做的两件事

1. **别把文档暴露给公网**。内部系统随意；对外服务要么整体关闭（`springdoc.api-docs.enabled=false` + `springdoc.swagger-ui.enabled=false`，对应 4.2 节的 @ConditionalOnProperty 短路），要么用 Spring Security 保护。
2. **用了 Spring Security 记得放行文档路径**（否则 401）：

```java
http.authorizeHttpRequests(auth -> auth
        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
        .anyRequest().authenticated());
```

管理端口方案（文档整体搬到 actuator 端口、业务端口完全不暴露）见 8.5 节。

## 12.9 常见坑速查

| 症状 | 原因与解法 |
|---|---|
| 照旧教程写 `@EnableSwagger2`/`Docket` 编译不过 | springfox 的 API，删掉——springdoc 零注解启用（第十章） |
| 页面 404 | 应用配了 `server.servlet.context-path`（要带前缀访问）；或 `paths-to-match` 过滤了所有接口；或 `api-docs.enabled=false`（五道过滤闸见 5.4 节） |
| 页面能开但没有接口 | Controller 没被扫到——检查 `packages-to-scan` 包名，或类/方法上有 `@Hidden` |
| Postman/旧版 Apifox 导入 JSON 报错 | 默认 OAS 3.1，老工具只认 3.0 → `springdoc.api-docs.version=OPENAPI_3_0` |
| 开了 javadoc 但注释没进文档 | 只加依赖没配 annotationProcessorPaths——注释没编译进 class（见 12.7 节） |
| `use-management-port=true` 后业务端口 404 | 这是设计如此：文档整体搬到管理端口的 `/actuator/openapi`（8.5 节） |
| Try it out 请求 403 | 安全链/CSRF 拦截——普通 REST 服务检查放行策略；需要 CSRF 的场景用 `springdoc.swagger-ui.csrf.enabled=true`（8.3 节改写器第②步） |

---

# 十三、附录

## 13.1 源码阅读入口清单（30 个关键文件）

**springdoc-openapi（D:\code\3rd\springdoc-openapi）**

| 文件 | 一句话 |
|---|---|
| starter-common/.../api/AbstractOpenApiResource.java | 引擎心脏（getOpenApi 流水线） |
| starter-common/.../service/OpenAPIService.java | 骨架构建 + LRU 缓存 |
| starter-webmvc-api/.../api/OpenApiResource.java | getPaths 四路汇合（webmvc） |
| starter-webmvc-api/.../api/OpenApiWebMvcResource.java | /v3/api-docs 端点 |
| starter-common/.../properties/SpringDocConfigProperties.java | 全部 springdoc.* 属性 |
| starter-common/.../configuration/SpringDocConfiguration.java | 总装配（providers/customizers/converter 注册） |
| starter-webmvc-api/.../configuration/SpringDocWebMvcConfiguration.java | webmvc 装配 + Framework7 豁免 |
| starter-common/.../converters/ModelConverterRegistrar.java | 插队 swagger-core 责任链 |
| starter-common/.../converters/models/（7 个替身模型） | Pageable/Sort 文档形态 |
| starter-common/.../extractor/MethodParameterPojoExtractor.java | @ParameterObject 平铺收集 |
| starter-common/.../params/.../DelegatingMethodParameter.java | 假参数术（核心技巧） |
| starter-common/.../models/GroupedOpenApi.java | 分组声明 |
| starter-common/.../configuration/MultipleOpenApiSupportConfiguration.java | 分组物化 |
| starter-common/.../customizers/（13 个接口） | 定制点全家福 |
| starter-common/.../ui/AbstractSwaggerIndexTransformer.java | initializer.js 七连改写 |
| starter-common/.../ui/AbstractSwaggerConfigurer.java | webjar 版本化映射 |
| starter-webmvc-ui/.../ui/SwaggerConfig.java | UI 装配总类 |
| starter-webmvc-ui/.../ui/SwaggerIndexPageTransformer.java | transformer 触发点 |
| starter-common-mcp/.../ai/mcp/OpenApiMcpToolCallbackProvider.java | OpenAPI→MCP 工具 |
| starter-common-mcp/.../ai/configuration/SpringDocAiAutoConfiguration.java | MCP 装配与告警 |

**swagger-core（D:\code\3rd\swagger-core/modules/…）**

| 文件 | 一句话 |
|---|---|
| swagger-models/.../oas/models/OpenAPI.java | 规范的 Java 镜像（根对象） |
| swagger-models/.../oas/models/media/Schema.java | 最胖模型（3.0/3.1 双规范兼容） |
| swagger-core/.../core/converter/ModelConverters.java | 双单例 + 责任链入口 |
| swagger-core/.../core/converter/ModelConverterContextImpl.java | resolve 调度与缓存 |
| swagger-core/.../core/jackson/ModelResolver.java | Jackson→Schema 翻译器（3694 行） |
| swagger-core/.../core/util/ObjectMapperFactory.java | 版本化序列化（mixin 家族） |
| swagger-jaxrs2/.../jaxrs2/Reader.java | JAX-RS 参照系的 Reader |
| swagger-jaxrs2/.../jaxrs2/integration/OpenApiServlet.java | JAX-RS 世界的"api-docs 端点" |

**其他**

| 文件 | 一句话 |
|---|---|
| OpenAPI-Specification/versions/3.1.0.md | 3.1 与 JSON Schema 对齐的原文 |
| OpenAPI-Specification/versions/3.2.0.md | $self/querystring/Tag 树 |
| springfox/springfox-spring-web/.../plugins/Docket.java | 旧世界的大门 |
| springfox/springfox-spring-web/.../plugins/DocumentationPluginsBootstrapper.java | 旧世界的启动期扫描 |

## 13.2 高频配置速查（springdoc.*，默认值均为源码实证）

```yaml
springdoc:
  api-docs:
    enabled: true          # 总开关（false 时全部自动配置短路）
    path: /v3/api-docs
    version: OPENAPI_3_1   # 枚举；3.1 自 springdoc 2.8.0 起为默认
    groups:
      enabled: true        # 多分组
  cache:
    disabled: false
    max-entries: 100       # GHSA-rhhx-6j8h-8cvw 修复引入
  pre-loading-enabled: false
  show-actuator: false     # actuator 端点进文档
  use-management-port: false  # 文档整体搬到管理端口
  packages-to-scan: [ com.example.api ]
  paths-to-match: [ /api/** ]
  use-fqn: false
  enable-javadoc: true     # javadoc 回填（v3 改名，默认 true）
  default-flat-param-object: false
  show-oauth2-endpoints: false
  swagger-ui:
    enabled: true
    path: /swagger-ui.html # 入口 302 重定向
    operations-sorter: method
    tags-sorter: alpha
    try-it-out-enabled: true
    persist-authorization: false
    csrf:
      enabled: false       # Try it out 带 CSRF 头
    disable-swagger-default-url: true
```

## 13.3 常用注解速查（io.swagger.v3.oas.annotations，jakarta 变体同包名）

| 注解 | 贴在哪 | 干什么 |
|---|---|---|
| @Tag | 类 | 分组/文档目录 |
| @Operation(summary, description, hidden) | 方法 | 操作元数据 |
| @Parameter(description, required, example) | 方法参数 | 单参数 |
| @Parameters | 方法 | 参数容器 |
| @RequestBody(description, required) | 参数 | 请求体（参数不是 body 时用） |
| @ApiResponse / @ApiResponses | 方法 | 响应（responseCode + content） |
| @Schema(description, example, defaultValue, requiredMode…) | 类/字段 | Schema 元数据（最常用） |
| @ArraySchema | 字段（集合） | 集合元素 Schema |
| @SecurityRequirement / @SecurityScheme | 方法或全局 | 安全声明（Scheme 建议放 OpenApiCustomizer） |
| @Server / @Servers | 类或全局 | 服务器 |
| @Callback | 方法 | 回调（服务端反推调用方） |
| @Hidden | 类/方法/参数 | 从文档消失 |
| @Webhook（@OpenAPI31） | 方法 | 3.1 webhook 声明（springdoc calculateWebhooks 扫描） |

## 13.4 初学者学习路线（动手向）

1. **基线接入**：按第十二章 12.1~12.2 完成依赖引入，访问 `/swagger-ui.html`，用浏览器 F12 观察 302 → index → swagger-config → api-docs 三个请求（时间线三）。
2. **零注解基线**：写一个普通 CRUD + 一个 DTO，看生成的 Schema——体会 3.5 节"Jackson 能看到的 Schema 就能看到"（改 Jackson `@JsonProperty` 名、加 `@NotNull` 再刷新）。
3. **补语义**：给 DTO 加 `@Schema(description=…)`、方法加 `@Operation`，对比前一步 diff。
4. **动定制**：写一个 `OpenApiCustomizer`（加安全定义）+ 一个 `OperationCustomizer`（给所有 POST 加统一错误响应），对照 7.1 表找到自己的代码走的调用点。
5. **分组**：两个 `GroupedOpenApi`，观察 `/v3/api-docs/{group}` 与 UI 右上角下拉框（swagger-config 的 urls[]）。
6. **踩坑实验**：把 Controller 加 `@Hidden`、路径加 pathsToExclude、开 `use-management-port`——逐个验证 5.4/8.5 的行为。
7. **读源码**：按 12.1 清单顺序，先 AbstractOpenApiResource（5.2 的 14 步对照源码走一遍），再 ModelResolver 的三个分支。

## 13.5 结语

Swagger 用十五年时间回答了一个朴素的问题：**API 文档能不能不撒谎？** 它的答案分三步：先把文档变成一份可计算的数据（OAS，2011-2017）；再把"从代码提取这份数据"做成引擎（swagger-core，2018）；最后让 Spring 的运行时事实成为数据源（springdoc，2019-）。springfox 的死不是意外——当它把"扫描"固定在启动期、把"扩展"固定在插件 SPI、把依赖冻结在 javax 的那个春天，springdoc 用"懒扫描 + 函数式定制器 + 委托 swagger-core"的轻架构完成了换血。而 2026 年的 MCP 模块提醒我们：这份契约的消费者名单还在变长——下一个读 `/v3/api-docs` 的，可能不是浏览器里的人，而是正在排障的智能体。

（本文完，欢迎对照源码勘误。）


