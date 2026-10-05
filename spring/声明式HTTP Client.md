# 声明式 HTTP Client 深度解析与选型：OpenFeign 与 Spring HTTP Service（写给初学者的架构全景）

> **本文基于的源码**（均克隆在 `D:\code\3rd`，文中所有【源码证据】的文件路径与行号均为对以下快照实际读取所得）：
>
> | 仓库 | 本地路径 | 快照版本 | 说明 |
> |---|---|---|---|
> | OpenFeign/feign | `D:\code\3rd\feign` | master `1f02e66c`（2026-10-02），最新 release tag **13.15**（2026-09-09） | Feign 本体 |
> | spring-cloud-openfeign | `D:\code\3rd\spring-cloud-openfeign` | main = **5.1.0-SNAPSHOT**（2026-10-01），GA tag **v5.0.3** | Spring Cloud 对 Feign 的集成 |
> | spring-framework | `D:\code\3rd\spring-framework` | 7.1.0-SNAPSHOT `f447f3c310`（2026-10-01），含 v5.3.39 ~ v7.0.9 标签 | HTTP Service（@HttpExchange）本体 |
> | spring-boot | `D:\code\3rd\spring-boot` | main = 4.2.0-SNAPSHOT `7f9eef2c33c`（2026-10-01），含 v3.4.0 / v4.0.0 标签 | 客户端工厂与自动配置 |
> | spring-cloud-commons | `D:\code\3rd\spring-cloud-commons` | main = 5.1.0-SNAPSHOT `589f31d`（2026-10-01），含 v5.0.0 标签 | 负载均衡/熔断对两种客户端的挂接 |
>
> **版本取舍说明**：Spring HTTP Service（HTTP Interface）自 Spring Framework 6.0（2022-11）引入后 API 高度稳定，6.x 的用法在 7.x 完全兼容（7.0 新增的是"分组注册"一层，不动老 API）；OpenFeign 自 13.x（2023-06）后核心架构未变。因此本文对使用 Spring Boot 3.x + spring-cloud-openfeign 4.x 的读者同样适用，差异处以"版本演进"小节单独说明。
>
> **阅读约定**：与《Spring Framework.md》一致——每章"先白话、后源码"。先用人话讲清"这是什么、为什么"，再给源码证据链（`模块/src/main/java/.../类名.java` + 行号 + 片段）。行号只对上述快照精确，读者按类名 + 方法名定位即可。

## 如何读这份文档

如果你正在做"用 OpenFeign 还是 Spring 自带的声明式客户端"的选型，推荐两遍读法：

- **第一遍（建立判断，30~60 分钟）**：读第一章（总览）全部 + 第二章、第三章每节的开头白话段 + 各章"小结" + 第五章（选型建议）。读完应当能回答：两者的注解模型差在哪？连接池各归谁管？"per-client 隔离"各自怎么做？负载均衡和熔断怎么挂进来？
- **第二遍（深入源码）**：对照【源码证据】打开两边仓库逐行读。建议顺序：2.2 / 3.2（一次调用的生命周期，先建立一个心智模型）→ 2.6 / 3.5（连接池与隔离，选型里最容易被低估的部分）→ 第四章（正面对比，逐维度）→ 2.7 / 3.6（超时重试熔断，生产事故高发区）。

---

# 一、总览：两条路线的定位、设计哲学与版本格局

## 1.1 一句话定位

**OpenFeign 是一个独立于 Spring 的开源声明式 HTTP 客户端**：你写一个 Java 接口 + 一组注解，Feign 在运行时生成动态代理，把方法调用翻译成 HTTP 请求。它的 Spring 集成（spring-cloud-openfeign）只做两件事——把"注解解析"换成 Spring MVC 风格（SpringMvcContract），把"连接与寻址"交给 Spring Cloud（服务发现、负载均衡、熔断）。官方仓库自述："Feign makes writing java http clients easier"（`README.md`）。

**Spring HTTP Service（HTTP Interface / HTTP Service Registry）是 Spring Framework 内建的声明式 HTTP 客户端**：同样写一个 Java 接口，但注解是 Spring 自家的 `@HttpExchange` 家族，底层执行器（adapter）是 `RestClient` / `WebClient`——也就是说，**Feign 的"最后一公里"（真正发请求）由它自己的 `Client` 接口体系承担，而 HTTP Service 的"最后一公里"复用 Spring 6.x 以来统一的 `ClientHttpRequestFactory` 体系**。

它们解决的问题高度重叠，但站位完全不同：

| | OpenFeign | Spring HTTP Service |
|---|---|---|
| 归属 | 独立社区项目（OpenFeign org）+ Spring Cloud 集成模块 | Spring Framework 本体（spring-web） |
| 存在形式 | `feign-core` + 40 余个可选模块 | spring-web 的三个包（annotation / invoker / registry） |
| 最小依赖 | 不依赖 Spring，可用于任何 Java 项目 | 必须 spring-web（本质上是 Spring 技术栈的一部分） |
| 心智模型 | "给任意 HTTP API 造一个 SDK"（Netflix 当年内部 RPC 用的姿势） | "把 MVC 的注解体系翻转到客户端"（消费方与服务方共享契约） |

## 1.2 设计哲学：读源码前先记住的六句话

**OpenFeign 侧（三条）**：

1. **接口即请求，元数据先行**。启动期把接口方法解析成 `MethodMetadata`（URL 模板、header、body 索引），运行期只做"填参数 + 发请求"（见 2.2 节）。
2. **一切皆可插拔**：Client、Encoder、Decoder、ErrorDecoder、Contract、Retryer、QueryMapEncoder 全部是单方法/少方法接口，通过 `Feign.Builder` 组装——Feign 自己只提供最朴素的默认实现（`HttpURLConnection` + 手写编码解码）。
3. **per-client 子容器隔离**。Spring Cloud 集成里每个 `@FeignClient`（按 `contextId`）拥有一个独立的子 ApplicationContext（`NamedContextFactory`），配置类、拦截器、超时互不污染（见 2.6.3 节）。

**Spring HTTP Service 侧（三条）**：

1. **复用而非另造**。HTTP Service 只负责"方法调用 → `HttpRequestValues`"这一段，发送、序列化、连接管理全部复用 RestClient/WebClient 既有的 `ClientHttpRequestFactory`、`HttpMessageConverter`、Micrometer 观测（见 3.2、3.4 节）。
2. **与服务端对称**。`@HttpExchange` 注解与 MVC 的 `@RequestMapping` 家族同名同语义，同一个接口可以被 `@Controller` 实现成为服务端，也可以被代理成为客户端——契约一份，两端共享。
3. **分组而非子容器**。7.0 引入的 HTTP Service Registry 用"group"组织多个客户端（共享同一套 client 定制），不做 Bean 子容器隔离——它认为 Spring 上下文本身就是配置中心（见 3.5.3 节）。

这六句话基本预定了后面所有差异：Feign 的灵活度来自"每个部件都能换"，代价是组合面大、默认值保守；HTTP Service 的易用性来自"背靠 Spring 全家桶"，代价是离开 Spring 生态就无从谈起。

## 1.3 技术坐标与依赖图

```
┌────────────────────── OpenFeign 路线 ──────────────────────┐
│  业务接口 @FeignClient + @GetMapping…                       │
│      ▲                                                     │
│  spring-cloud-openfeign（5.x）：SpringMvcContract、         │
│  FeignClientFactory 子容器、LB/CB/观测挂接、属性绑定         │
│      ▲                                                     │
│  feign-core（13.x）：Feign.Builder → 动态代理 → Client      │
│      │                                                     │
│      ├── feign-hc5 ────── Apache HttpClient 5（连接池）      │
│      ├── feign-java11 ─── JDK HttpClient                   │
│      ├── feign-okhttp ─── OkHttp（4.3 仍可手动接入，5.0 移除）│
│      └── Client.Default ── HttpURLConnection（兜底）        │
└────────────────────────────────────────────────────────────┘

┌────────────────────── Spring HTTP Service 路线 ────────────┐
│  业务接口 @HttpExchange + @GetExchange…                     │
│      ▲                                                     │
│  spring-web：registry（@ImportHttpServices，7.0+）          │
│             invoker（HttpServiceProxyFactory，6.0+）        │
│      ▲                                                     │
│  adapter：RestClientAdapter / WebClientAdapter /            │
│           RestTemplateAdapter（7.1 起废弃）                 │
│      ▲                                                     │
│  ClientHttpRequestFactory 五选一：                          │
│  HttpComponents │ Jetty │ Reactor Netty │ JDK │ Simple      │
│      ▲                                                     │
│  Spring Boot 4：spring.http.serviceclient.<group>.* 自动配置 │
│  Spring Cloud 2025.1：LB / CircuitBreaker 分组挂接           │
└────────────────────────────────────────────────────────────┘
```

关键观察：**两条路线在"负载均衡 / 熔断 / 可观测"这一层最终会师**——都由 Spring Cloud（commons / loadbalancer / circuitbreaker）和 Micrometer 提供；真正的分野在"注解解析 → 请求构建"与"底层连接栈"这两段。

## 1.4 关键问题 → 两家方案映射（全文导览）

| 声明式客户端的关键问题 | OpenFeign 的方案 | Spring HTTP Service 的方案 | 详见 |
|---|---|---|---|
| 接口方法如何变成 HTTP 请求 | Contract 解析 → MethodMetadata → SynchronousMethodHandler | HttpServiceMethod 初始化期编译 → HttpRequestValues | 2.2、3.2 |
| 用什么注解描述 API | Spring MVC 注解（经 SpringMvcContract）或 Feign 自有注解 | @HttpExchange 家族（自带，不依赖 MVC） | 2.3、3.3 |
| 谁真正发请求 | feign Client 接口（7 种实现任选） | HttpExchangeAdapter → RestClient/WebClient/RestTemplate → 五种 ClientHttpRequestFactory | 2.5、3.4 |
| 连接池怎么配 | 底层 client 各管各的（feign.httpclient.hc5.* 等） | 归 ClientHttpRequestFactory/Boot spring.http.client.* | 2.6、3.5 |
| 多个服务如何互不干扰 | FeignClientFactory（NamedContextFactory 子容器） | HttpServiceGroup 分组 + Boot per-group 属性 | 2.6.3、3.5.3 |
| 负载均衡 | FeignBlockingLoadBalancerClient 包装 Client | LoadBalancerRestClientHttpServiceGroupConfigurer / @LoadBalanced | 2.8、3.7 |
| 熔断与 fallback | FeignCircuitBreakerTargeter + @FeignClient(fallback) | CircuitBreakerRestClientHttpServiceGroupConfigurer + @HttpServiceFallback | 2.8、3.7 |
| 错误处理 | ErrorDecoder → FeignException | RestClientResponseException / defaultStatusHandler | 2.4、3.6 |
| 重试 | Retryer（默认永不重试） | 无内建（Spring Retry / Framework 7 @Retryable / LB retry） | 2.7、3.6 |
| 可观测性 | MicrometerObservationCapability（Capability 机制） | RestClient/WebClient 原生 Observation | 2.8、3.7 |
| 非 Spring 项目能用吗 | 能（feign-core 零 Spring 依赖） | 不能（绑定 spring-web） | 4.7 |

## 1.5 版本演进：两条时间线

### 1.5.1 OpenFeign 侧

Feign 本体（本地 git 实证，tag 日期）：

| 时间 | 事件 |
|---|---|
| 2012-03 | 首次提交（Netflix，作者 Adrian Cole）；Netflix OSS 时代随 Ribbon/Hystrix 大规模使用 |
| 2016 | 移交社区，成立 OpenFeign 组织（脱离 Netflix 发布节奏） |
| 13.0（2023-10-20，tag 日期实证）起的 13.x | 当前主流线；**基线仍是 Java 8**（`pom.xml:160`：`<main.java.version>1.8</main.java.version>`） |
| 13.8.1 → 13.15（2026-02-20 → 2026-09-09） | 8 个月内 11 个 release（13.9.x/13.10~13.15），维护活跃、节奏稳定 |

spring-cloud-openfeign（本地 tag/源码实证）：

| 版本 | 所属 Cloud Train | 对应 Boot | 关键变化 |
|---|---|---|---|
| 2.x | Hoxton 及以前 | 2.2/2.3 | Netflix Feign 时代收尾 |
| 3.x | 2020.x/2021.x | 2.4~2.7 | 属性前缀还是 `feign.client.*`（v3.1.8 `FeignClientProperties.java:46`） |
| 4.0~4.2 | 2022.x~2024.0 (Moorgate) | 3.0~3.4 | 属性前缀迁移为 `spring.cloud.openfeign.client.*`（v4.0.4 实证）；`FeignContext` 更名 `FeignClientFactory`（4.1.x 完成命名对齐） |
| 4.3.x | 2025.0 (Northfields，GA 2025-05-29) | 3.5 | OkHttp 支持仍在（v4.3.3 中 okhttp 相关文件 7 个） |
| **5.0.x** | 2025.1 (Oakwood，GA 2025-11) | **4.0** | **移除 OkHttp 支持**（v5.0.3 中 okhttp 文件 0 个）；全面适配 Boot 4 模块化 |
| 5.1.x | 2026.0 | 4.1 | 当前 main（5.1.0-SNAPSHOT），v5.1.0-M1 已发 |

**注意**：Feign 仓库里 `ribbon/`、`hystrix/` 模块至今仍在（社区维护层面"没删"），但对应的 Netflix 组件早已进维护态，实际项目一律走 Spring Cloud LoadBalancer + Spring Cloud CircuitBreaker 路线。

### 1.5.2 Spring HTTP Service 侧

以下每一条都用本地 git 标签实证过（方法：`git ls-tree -r <tag> -- spring-web/src/main/java/org/springframework/web/service` 数文件）：

| 版本 | GA 时间 | 该包文件数 | 新增能力 |
|---|---|---|---|
| 5.3.39 | 2020-10 | **0** | 尚不存在 |
| 6.0.0 | 2022-11 | 26 | **HTTP Interface**：@HttpExchange 家族 + HttpServiceProxyFactory（`HttpServiceProxyFactory.java` `@since 6.0`）；服务端 @Controller 可实现同一接口 |
| 6.1.0 | 2023-11 | 31 | **HttpExchangeAdapter 抽象**（`@since 6.1`）+ **RestClient** 上场；新增 UriBuilderFactoryArgumentResolver、ReactorHttpExchangeAdapter |
| 7.0.9 | 2025-11 | 44 | **HTTP Service Registry**：@ImportHttpServices / HttpServiceProxyRegistry / HttpServiceGroup(+Configurer)、AOT 处理器 |
| 7.1（main） | 预计 2026-11 | 44 | RestTemplate 全类废弃（`RestTemplate.java:105`：`@Deprecated(since = "7.1", forRemoval = true)`）；Builder 增加 proxyFactoryCustomizer |

配套组件的同步演进（Boot / Cloud 侧）：

| 版本 | 关键变化 |
|---|---|
| Spring Boot 3.4 | 引入 `ClientHttpRequestFactorySettings` + 客户端工厂探测（`v3.4.0` 源码 `ClientHttpRequestFactorySettings.java:46` `DEFAULTS`），统一 connect/read timeout、SSL bundle 配置 |
| Spring Boot 4.0 | **首次为 HTTP Service 提供自动配置**：`HttpServiceClientAutoConfiguration` + 分组属性 `spring.http.serviceclient.<group>.*`（v4.0.0 实证：`@ConfigurationProperties("spring.http.serviceclient")`） |
| Spring Cloud 2025.1 | spring-cloud-commons 新增 `LoadBalancerRestClientHttpServiceGroupConfigurer`（负载均衡）与 `CircuitBreakerRestClientHttpServiceGroupConfigurer`（熔断 + `@HttpServiceFallback`），对 HTTP Service 分组透明挂接 |
| Spring Security 7.0 | HTTP Service 方法支持 `@ClientRegistrationId` 直连 OAuth2 客户端注册表 |

### 1.5.3 版本配套矩阵（选型时先对号入座）

| Spring Boot | Spring Cloud | spring-cloud-openfeign | Feign 本体 | Spring HTTP Service 可用形态 |
|---|---|---|---|---|
| 3.2 | 2023.0 (Leyton) | 4.1.x | 13.x | 6.1：手工 HttpServiceProxyFactory + RestClient |
| 3.4 | 2024.0 (Moorgate) | 4.2.x | 13.x | 6.2：同上 + Boot 统一 client 工厂设置 |
| 3.5 | 2025.0 (Northfields) | 4.3.x | 13.x | 6.2：同上 |
| 4.0 | 2025.1 (Oakwood) | 5.0.x | 13.x | **7.0：+ @ImportHttpServices 分组注册 + Boot/Cloud 全套自动配置** |
| 4.1 | 2026.0 | 5.1.x | 13.x | 7.1：RestTemplate 适配器废弃 |

一个重要结论：**HTTP Service 的"对标 OpenFeign 体验"（分组注册、负载均衡、熔断、Boot 属性化配置）要到 Boot 4.0 + Cloud 2025.1 才凑齐**。在 Boot 3.x 上用 HTTP Service，等于接受"手工注册 + 自己接 LB"的原始形态。

## 1.6 全文章节地图

```
一、总览（本章）
二、OpenFeign 深入源码解析
    2.1 社区生态与发展现状      2.5 支持的 HTTP Client 选型
    2.2 核心架构与调用生命周期   2.6 连接池的配置与隔离策略
    2.3 契约层 Contract         2.7 超时、重试与熔断
    2.4 编解码与错误处理        2.8 Spring Cloud 集成与可观测性
    2.9 本章小结
三、Spring HTTP Service 深入源码解析
    3.1 出身与演进              3.5 连接池的配置与隔离策略
    3.2 核心架构与调用生命周期   3.6 超时、重试与错误处理
    3.3 注解与参数解析层        3.7 Spring Cloud 生态集成
    3.4 支持的 HTTP Client 选型 3.8 本章小结
四、正面对比（逐维度）
五、技术选型建议（决策树 + 场景映射 + 避坑清单）
```

---

# 二、OpenFeign：深入源码解析

## 2.1 社区生态与发展现状

**项目沿革**。Feign 起源于 Netflix（本地仓库首次提交 2012-03-18，`git log --format='%ad' HEAD | tail -1` 实证），是 Netflix OSS 微服务套件（Eureka/Ribbon/Hystrix）的"接口化 HTTP 调用"组件；2016 年移交社区成立 OpenFeign 组织。累计提交约 3400+，核心贡献者依次为 Marvin Froeder（现任主力维护者）、Adrian Cole（原作者）、Kevin Davis 等（`git shortlog -sn` 实证）。GitHub 星标长期在 9k+ 量级，是 Java 生态事实上的两大声明式客户端之一（另一个就是 Spring 系）。

**现状判断（2026-10 时点）**：

- **本体很活跃**：13.8.1 → 13.15 之间 8 个月 11 个 release，节奏稳定，Issue/PR 由社区驱动，无官方"roadmap 文档"，方向在 GitHub Issues/Milestones 跟踪。
- **基线保守**：core 仍编译目标 Java 8（`pom.xml:160`），这让 Feign 能覆盖老系统，但也意味着它的 API 设计（如检查型 IOException、无 Kotlin 协程一等支持）带有年代感——协程支持在单独的 `feign-kotlin` 模块里补。
- **模块面极宽**：本地仓库实测 40+ 子模块，可分为四类——
  1. **传输适配**：`hc5`、`httpclient`（HC4）、`java11`（内含 `feign.http2client.Http2Client`）、`okhttp`、`googlehttpclient`、`jaxrs2`、`mock`（测试）；
  2. **编解码**：`gson`、`jackson`、`jackson-jr`、`jackson-jaxb`、`jackson3`、`moshi`、`fastjson2`、`json`、`sax`、`jaxb`/`jaxb-jakarta`、`soap`/`soap-jakarta`、`form`/`form-spring`；
  3. **契约扩展**：`jaxrs`/`jaxrs3`/`jaxrs4`、`spring`/`spring4`、`graphql`/`graphql-apt`；
  4. **集成与观测**：`micrometer`、`dropwizard-metrics4/5`、`kotlin`、`vertx`、`reactive`（响应式包装）、`http-cache`、`annotation-error-decoder`、`ribbon`/`hystrix`（历史遗留）。
- **Spring Cloud 集成随大部队走**：版本矩阵见 1.5.1；5.0（Boot 4 基线）移除了 OkHttp 支持——`git ls-tree v4.3.3 | grep -ci okhttp` 得 7，`v5.0.3` 得 0。用 OkHttp 的团队升级 Boot 4 后要么换 HC5/JDK HttpClient，要么自己实现 feign `Client` 接口注入。
- **与 Spring 官方态度的关系**：Spring 官方博客《HTTP Service Client Enhancements》（2025-09-23）明确把 @HttpExchange 定位为"这些能力长期由 Spring Cloud OpenFeign 提供，现在 Framework 6+ 原生化，'更精简、更普用'（more minimal and widely useful）"。这可以理解为 Spring 团队的"官方推荐新姿势"，但 OpenFeign 并未被宣布废弃，spring-cloud-openfeign 5.x 仍在随 Boot 4 演进。

## 2.2 核心架构与一次调用的完整生命周期

**白话版**：`@EnableFeignClients` 扫描接口 → 每个接口经 `FeignClientFactoryBean` 从子容器取 `Feign.Builder` → Builder 用 Contract 把接口方法解析成元数据 → JDK 动态代理接管所有方法调用 → 调用时"填模板、过拦截器、发请求、解码、判错（可能重试）"。

**源码证据链**（feign-core）：

1. **契约解析（启动期）**。`Contract` 接口只有一个方法：`List<MethodMetadata> parseAndValidateMetadata(Class<?> targetType)`（`core/src/main/java/feign/Contract.java:40`），抽象骨架 `BaseContract.parseAndValidateMetadata` 在 `:49`。每个方法解析出：URL 模板、HTTP 方法、header 模板、参数索引（`@PathVariable`/`@RequestParam`/`@RequestHeader`/`@Body` 各归其位）。
2. **代理与执行链（运行期）**。每个接口方法对应一个 `SynchronousMethodHandler`（`core/src/main/java/feign/SynchronousMethodHandler.java:31`），其 `invoke` 的主干：

```java
// SynchronousMethodHandler.java:51（节选，有删节）
Options options = findOptions(argv);                 // 允许调用方传 Request.Options 覆盖超时
...
MethodInterceptor.Chain endOfChain = inv -> runWithRetry(inv, options);
chain.next(invocation);
```

`runWithRetry`（`:68`）内部是经典的 `while(true)` 重试环：

```java
// SynchronousMethodHandler.java:72~75
try { return executeAndDecode(invocation, options); }
catch (RetryableException e) {
    retryer.continueOrPropagate(e);   // Retryer 决定：重试 or 抛出
```

3. **请求构建与发送**。`executeAndDecode`（`:103`）先 `targetRequest(template)`（`:105`、`:147`）——把 `RequestTemplate` 依次交给所有 `RequestInterceptor`，产出不可变 `Request`，然后交给 `Client.execute(Request, Options)`，拿到 `Response` 后走 Decoder / ErrorDecoder。

4. **异步形态**。`AsyncFeign<C>`（`core/src/main/java/feign/AsyncFeign.java:46`）是同一套模型的事件驱动版，配合 `AsyncClient`（实现有 `DefaultAsyncClient`、HC5 的 `AsyncApacheHttp5Client`），非虚拟线程场景下能省占线程。

**结构小结**：Feign 把"一次调用"拆成 `Contract（静态）→ MethodMetadata → RequestTemplate → Request → Client → Response → Decoder` 六段，每段都可以单独替换。这是后面所有配置项的根源。

## 2.3 契约层：从 Feign 自有注解到 SpringMvcContract

**白话版**：Feign 本体默认读的是它自己的 `@RequestLine("GET /repos/{owner}/{repo}")` + `@Headers`；Spring Cloud 集成把 Contract 换成 `SpringMvcContract`，让客户端接口直接用 `@GetMapping`、`@PathVariable` 等 Spring MVC 注解——**同一个注解体系写在服务端是 Controller，写在客户端是 Feign 接口**。

【源码证据】spring-cloud-openfeign 默认契约：

```java
// FeignClientsConfiguration.java:128
return new SpringMvcContract(parameterProcessors, feignConversionService, feignClientProperties);
```

一个必须知道的硬约束——**类级 `@RequestMapping` 在 @FeignClient 接口上是非法的**：

```java
// SpringMvcContract.java:191~196
protected void processAnnotationOnClass(MethodMetadata data, Class<?> clz) {
    RequestMapping classAnnotation = findMergedAnnotation(clz, RequestMapping.class);
    if (classAnnotation != null) {
        // ① Pre-initialize...
        // ② throws IllegalArgumentException
        throw new IllegalArgumentException("@RequestMapping annotation not allowed on @FeignClient interfaces");
```

所以公共"路径前缀"要放在 `@FeignClient(path = "...")` 属性里，而不是类上的 `@RequestMapping`。这是 OpenFeign 初学者最常踩的坑（对比：Spring HTTP Service 的 `@HttpExchange` 注解本身就有 `url` 属性，天然支持类级前缀，见 3.3）。

Feign 仓库还提供 JAX-RS 契约（`jaxrs`/`jaxrs3`/`jaxrs4`）——同一个接口定义理论上可以在"服务端 JAX-RS 实现"与"客户端 Feign"之间复用；`spring`/`spring4` 模块则让**不引 Spring Cloud** 的纯 Feign 项目也能用 Spring MVC 注解。

## 2.4 编解码与错误处理

**Encoder/Decoder**：两个单方法接口，第三方实现生态极其丰富（1.2 节清单里的编解码模块全家桶）。Spring Cloud 集成默认换成复用 `HttpMessageConverters` 的 `SpringEncoder`/`SpringDecoder`，因此 Jackson 的 Boot 级定制（模块注册、日期格式等）对 Feign 自动生效；`SpringEncoder` 同时处理 `MultipartFile`（`SpringEncoder.java:50` 引入 `org.springframework.web.multipart.MultipartFile`），multipart 能力实际由可选依赖 `feign-form-spring` 提供（`spring-cloud-openfeign-core/pom.xml:114`）。

**ErrorDecoder**：响应非 2xx 时，Feign 不抛裸 IOException，而是交给 `ErrorDecoder` 翻译。默认实现产生 `FeignException` 家族（含 status 与 body），并识别 `Retry-After` 响应头转成 `RetryableException` 与 Retryer 联动。业务上常见的定制是"按状态码/业务错误码解码成领域异常"，或直接引入 `annotation-error-decoder` 模块用注解声明映射。

## 2.5 支持的 HTTP Client 选型（含装配优先级）

**白话版**：feign `Client` 接口 = "给我 Request，还你 Response"。Feign 本体自带 `Default`（HttpURLConnection），其余靠模块；spring-cloud-openfeign 负责"检测类路径，自动选一个最好的"。

【源码证据】feign-core 内部接口（`core/src/main/java/feign/Client.java:24` 导入 `HttpURLConnection`；`:49` `class Default extends DefaultClient`，`:90~91` `getConnection` 返回 `HttpURLConnection`）。

**feign 生态中的 Client 全景**（本地仓库逐一验证存在）：

| 实现类 | 模块 | 底层技术 | 连接池 | 异步 | 备注 |
|---|---|---|---|---|---|
| `Client.Default` / `DefaultClient` | core | HttpURLConnection | JVM 全局 keep-alive 缓存 | 否 | 兜底，不建议生产 |
| `ApacheHttp5Client` | hc5 | Apache HttpClient 5 | **PoolingHttpClientConnectionManager（可配）** | 否 | **spring-cloud-openfeign 首选** |
| `AsyncApacheHttp5Client` | hc5 | HC5 Async | 同上 | 是 | 配合 AsyncFeign |
| `Http2Client` | java11 | JDK HttpClient（java.net.http） | JDK 内置池 | 否（AsyncClient 另有 DefaultAsyncClient） | 类名带"Http2"，天然支持 HTTP/2 协议升级 |
| `OkHttpClient` | okhttp | OkHttp | ConnectionPool（默认 5 空闲/5 分钟） | 否 | **spring-cloud-openfeign 5.0 起不再自动装配** |
| `ApacheHttpClient` | httpclient | Apache HttpClient **4.x** | 可配 | 否 | 老项目遗留，勿新用 |
| `GoogleHttpClient` | googlehttpclient | Google HTTP Client | 有 | 否 | 少用 |
| `JAXRSClient` | jaxrs2 | JAX-RS Client | 随实现 | 否 | 生态孤岛 |
| `MockClient` | mock | 内存模拟 | — | — | 测试用 |

【源码证据】非负载均衡场景的自动装配与优先级（spring-cloud-openfeign 5.x，`FeignAutoConfiguration.java`）：

```java
// FeignAutoConfiguration.java:239~256（节选）
@ConditionalOnClass(ApacheHttp5Client.class)
@ConditionalOnMissingBean(org.apache.hc.client5.http.impl.classic.CloseableHttpClient.class)
@ConditionalOnProperty(value = "spring.cloud.openfeign.httpclient.hc5.enabled", havingValue = "true",
        matchIfMissing = true)
@Import(HttpClient5FeignConfiguration.class)
protected static class HttpClient5FeignConfiguration {
    @Bean
    @ConditionalOnMissingBean(Client.class)
    public Client feignClient(CloseableHttpClient httpClient5) {
        return new ApacheHttp5Client(httpClient5);
    }
}
// FeignAutoConfiguration.java:286 —— 兜底：Http2ClientFeignConfiguration（JDK HttpClient）
```

负载均衡场景同理，顺序硬编码在 Import 列表里：

```java
// loadbalancer/FeignLoadBalancerAutoConfiguration.java:56
@Import({ HttpClient5FeignLoadBalancerConfiguration.class, Http2ClientFeignLoadBalancerConfiguration.class,
        DefaultFeignLoadBalancerConfiguration.class })
```

即：**HC5 在类路径且未禁用 → 用 HC5；否则有 JDK HttpClient → 用 Http2Client；再否则 → Client.Default**。任何情况下，只要你自己在子容器里放一个 `Client` bean（`@ConditionalOnMissingBean(Client.class)` 让位），就整体接管。

> OkHttp 的历史位置：4.x 中 OkHttp 与 HC5 是平级选项（属性 `spring.cloud.openfeign.okhttp.enabled`）；5.0 直接移除实现类。上文表格与 4.7 节的生态对比都受此影响。

## 2.6 连接池的配置与隔离策略

这是选型里**最容易被低估**的一节：声明式客户端本身很薄，高并发下的行为几乎完全由"底层连接栈"决定。

### 2.6.1 各底层 client 的连接池真相

**(a) Client.Default（HttpURLConnection）**——连接池根本不在 Feign 手里。JDK 对 `HttpURLConnection` 维护一个 JVM 全局的 keep-alive 连接缓存，上限由系统属性 `http.maxConnections` 控制（默认 5 个/目标主机）。Feign 对它唯一能做的是把超时套上去（`DefaultClient.java:158~159`：`connection.setConnectTimeout(...); connection.setReadTimeout(...)`）。这意味着：多个 Feign 客户端、RestTemplate（Simple 工厂）、任何裸 HttpURLConnection **共享同一个全局缓存**——既无法按服务调参，也互相影响。

**(b) ApacheHttp5Client（HC5）**——Feign 的池化主战场，但池对象（`CloseableHttpClient`）由**外部构造**后传入（见 2.5 的 `feignClient(CloseableHttpClient httpClient5)`）。spring-cloud-openfeign 默认帮你造一个，参数全部来自属性（【源码证据】`support/FeignHttpClientProperties.java`）：

| 属性（5.x 前缀 `spring.cloud.openfeign.httpclient.*`） | 默认值 | 常量出处 |
|---|---|---|
| `max-connections` | **200**（总连接） | `:43` `DEFAULT_MAX_CONNECTIONS = 200` |
| `max-connections-per-route` | **50**（单主机） | `:48` |
| `time-to-live` / `time-to-live-unit` | **900 s** | `:53`/`:58` |
| `connection-timeout` | 2000 ms | `:68` |
| `hc5.pool-concurrency-policy` / `pool-reuse-policy` | HC5 默认 | `:216`/`:221` |
| `hc5.socket-timeout` / `connection-request-timeout` | 见属性类 | `:226`/`:236` |
| `http2.version` | `HTTP_2` | `:338` |

默认的 `HttpClient5FeignConfiguration`（`clientconfig/HttpClient5FeignConfiguration.java`）用 `PoolingHttpClientConnectionManagerBuilder` + `HttpClientBuilder` 拼装（import 清单里可见 `PoolingHttpClientConnectionManagerBuilder`、`SocketConfig`、`RequestConfig`）。

**(c) Http2Client（JDK HttpClient）**——连接池在 JDK 内部，应用只能通过系统属性（如 `jdk.httpclient.connectionPoolSize`，控制 keep-alive 池容量）做**进程级**调整，无 per-client 定制；换来的是协议现代（HTTP/2、TLS 1.3）和零额外依赖。

**(d) OkHttpClient**——`ConnectionPool` 默认 5 个空闲连接、保留 5 分钟；在 spring-cloud-openfeign 5.0 已移除支持，需要自行构造 `OkHttpClient` 并包一个 feign `Client`。

### 2.6.2 配置入口的三个层级

1. **属性层**：`spring.cloud.openfeign.httpclient.*`（只影响框架默认构造的那个全局 `CloseableHttpClient`）；
2. **自定义 Bean 层**：往容器放自己的 `CloseableHttpClient`（全局）→ `@ConditionalOnMissingBean(CloseableHttpClient.class)` 让位（`FeignAutoConfiguration.java:243`）；
3. **per-client 配置类层**：`@FeignClient(configuration = X.class)`——真正的"按服务定制"，见下。

### 2.6.3 隔离策略：FeignClientFactory 子容器（Feign 的独门武器）

**白话版**：每个 `@FeignClient`（按 `contextId`，默认 = name）拥有一个**独立的子 ApplicationContext**，里面放着该客户端专属的 Feign.Builder、Encoder、拦截器、超时配置……子容器之间互相看不见。这就是"隔离"二字的 Feign 答案。

【源码证据】：

```java
// FeignClientFactory.java:40
public class FeignClientFactory extends NamedContextFactory<FeignClientSpecification> {
```

`NamedContextFactory` 是 spring-cloud-commons 的机制：按名字创建子容器，`getInstance(name, type)` 只在该服务的子容器里找 bean。装配时（`FeignClientFactoryBean.java`）：

- `:105` `contextId` 决定子容器身份；
- `:151` `Feign.Builder builder = get(context, Feign.Builder.class)`——从**当前客户端的子容器**取 Builder；
- 配置合并的优先级（`:177~199` `configureFeign`）：**属性文件里 `spring.cloud.openfeign.client.config.<contextId>.*` 优先于 @Configuration 配置类**，`configureUsingConfiguration`（`:203~263`）再把子容器里的 logLevel/retryer/errorDecoder/Options/requestInterceptors/responseInterceptor/dismiss404/capabilities 逐项装进 Builder；
- `:164` `applyBuildCustomizers`：支持 `FeignBuilderCustomizer` 做编程式最终定制。

**"隔离"能隔离什么、不能隔离什么**——这是关键，很多人只知其一：

| 层面 | 是否按客户端隔离 | 机制 |
|---|---|---|
| 超时（connect/read） | ✅ | `config.<contextId>.connect-timeout` 或子容器 Options bean |
| 拦截器/编解码器/重试/错误解码 | ✅ | `configuration = X.class` 注册进子容器 |
| **HTTP 连接池** | ⚠️ **默认不隔离** | 全局唯一的 `CloseableHttpClient`/`Client` bean（FeignAutoConfiguration 顶层装配），所有客户端共用 200/50 那个池 |
| 需要池隔离时 | ✅ 可做 | 在 per-client 配置类里给该子容器注册专属 `CloseableHttpClient`（+ `Client`），并调整 `max-connections-per-route` 语义 |

也就是说：**Feign 给了你"每个服务一个独立池"的全部机械（子容器），但默认策略是共享池**。生产上真正常见的问题是"A 服务慢查询拖垮全局池，殃及 B 服务"，解法就是给 A 单独配 `CloseableHttpClient`（per-route 或独立池）——这正是子容器机制的价值所在。

## 2.7 超时、重试与熔断

**超时三层覆盖**（低→高）：

1. Feign 内建默认：`Request.Options` 无参构造 = **connect 10s / read 60s**（`core/src/main/java/feign/Request.java`：`public Options() { this(10, TimeUnit.SECONDS, 60, TimeUnit.SECONDS, true); }`）；
2. 客户端级：`spring.cloud.openfeign.client.config.<contextId>.connect-timeout / read-timeout`（底层仍是 Options）；
3. **调用级**：方法参数里放一个 `Request.Options`，`SynchronousMethodHandler.findOptions(argv)`（`:51`）识别并覆盖——这是 Feign 少有的"运行期可变"设计。

**重试**：默认 `Retryer.NEVER_RETRY`（`core/src/main/java/feign/Retryer.java:47`）——**收到任何非 RetryableException 都不重试**；`Retryer.Default`（`:41`，period/maxPeriod/maxAttempts 语义）需显式启用。官方默认值保守是有意的：重试语义更推荐放到 Spring Cloud 层（负载均衡重试 `RetryableFeignBlockingLoadBalancerClient`，基于 spring-retry）或熔断层。

**熔断**：`spring.cloud.openfeign.circuitbreaker.enabled=true`（`FeignAutoConfiguration.java:163`）后，`Targeter` 换成 `FeignCircuitBreakerTargeter`（`FeignCircuitBreakerTargeter.java:30`，持有 spring-cloud 的 `CircuitBreakerFactory`），代理执行被 `FeignCircuitBreakerInvocationHandler` 包裹——调用外层套 CircuitBreaker，失败走 `@FeignClient(fallback = ...)` / `fallbackFactory`。熔断器命名可经 `CircuitBreakerNameResolver` 定制，支持按 group 组织（`FeignAutoConfiguration.java:195`）。

## 2.8 Spring Cloud 集成与可观测性

**负载均衡（以 LoadBalancer 为例）**——当 `@FeignClient(name = "user-service")` 不带 `url` 时，spring-cloud-openfeign 给你的是一个"会挑实例的 Client"：

```java
// FeignBlockingLoadBalancerClient.java:79~110（节选）
public Response execute(Request request, Request.Options options) throws IOException {
    URI originalUri = URI.create(request.url());
    String serviceId = originalUri.getHost();                 // :81 服务名藏在 host 位
    ServiceInstance instance = loadBalancerClient.choose(serviceId, lbRequest);  // :91 挑实例
    ...
    URI reconstructedUrl = loadBalancerClient.reconstructURI(instance, originalUri);  // :108 真实地址
    Request newRequest = buildRequest(request, reconstructedUrl, instance);           // :109 换地址
    return executeWithLoadBalancerLifecycleProcessing(delegate, options, newRequest, ...); // :110
}
```

要点：**LB 是 Client 装饰器**，真正的传输仍走 2.5 选出的底层 Client；重试版 `RetryableFeignBlockingLoadBalancerClient` 再包一层。

**可观测性**：Feign 的 Capability 扩展机制（`builder.addCapability`，`FeignClientFactoryBean.java:263`）在 micrometer 模块里提供 `MicrometerCapability`（MeterRegistry 指标）与 `MicrometerObservationConfigurer`；spring-cloud-openfeign 检测到 `ObservationRegistry` 时自动注册 `MicrometerObservationCapability`（`FeignClientsConfiguration.java:284~297`），产生符合 Micrometer Observation 规范的 `http.client.requests` 观测（含 serviceId 等 tag），与 Boot 的观测栈无缝衔接。

**属性全景**（5.x 命名空间，均经源码验证）：

| 属性组 | 前缀 | 内容 |
|---|---|---|
| 客户端级行为 | `spring.cloud.openfeign.client.config.<id>.*` | loggerLevel、connect/readTimeout、retryer、errorDecoder、requestInterceptors、responseInterceptor、queryMapEncoder、dismiss404、exceptionPropagationPolicy、capabilities |
| 底层 HTTP | `spring.cloud.openfeign.httpclient.*`（含 `hc5.*`、`http2.*`） | 见 2.6.1 表 |
| 压缩 | `spring.cloud.openfeign.compression.*` | request/response gzip |
| 熔断 | `spring.cloud.openfeign.circuitbreaker.*` | enabled、alphanumeric-ids、group.enabled |
| OAuth2 | `spring.cloud.openfeign.oauth2.enabled` | `OAuth2AccessTokenInterceptor`（`FeignAutoConfiguration.java:254`） |
| 微米度 | `spring.cloud.openfeign.client.config.<id>.micrometer.enabled` | 观测开关（`FeignClientMicrometerEnabledCondition` 按客户端读取，默认开） |

## 2.9 本章小结

OpenFeign 的源码是一个"**六段可插拔管道 + 子容器隔离**"的设计：Contract 定格式、Encoder/Decoder 定序列化、Client 定传输、Retryer/ErrorDecoder 定失败语义、RequestInterceptor 定公共头，全部经 `Feign.Builder` 在 per-client 子容器里组装。它的优势在灵活与广度（40+ 模块、任意注解契约、Java 8 兼容、脱离 Spring 也能用）；代价是默认值保守（NEVER_RETRY、HttpURLConnection 兜底）、连接池默认全局共享需要自觉治理，以及 Spring 集成层的配置面（属性命名空间）在 3.x→5.x 间历经两次大迁移。理解了 `FeignClientFactoryBean` 的装配优先级与 `FeignBlockingLoadBalancerClient` 的装饰位置，就掌握了它 90% 的生产问题定位路径。

---

# 三、Spring HTTP Service：深入源码解析

## 3.1 出身与演进：为什么 Spring 要自己再做一遍

OpenFeign 已然成熟，Spring 在 Framework 6.0 重做一遍的动机，官方博客（HTTP Service Client Enhancements, 2025-09-23）说得很直白：这些模式"long used with Spring Cloud OpenFeign"，但 Spring 想**内建一个更精简、更普用的版本**——不引第三方、不建子容器、直接复用 6.0 同代诞生的 RestClient 体系。三个时间节点（版本实证见 1.5.2，此处不重复）：

- **6.0（2022-11）**：`@HttpExchange` + `HttpServiceProxyFactory`。定位是"Feign 的最小替代品"：一个 invoker 包 + 一个 annotation 包。
- **6.1（2023-11）**：抽出 `HttpExchangeAdapter` 接口，让同一套代理可以骑在 RestClient / WebClient / RestTemplate 任何一匹马上；RestClient 登场成为默认推荐。
- **7.0（2025-11）**：`registry` 包落地——`@ImportHttpServices` 按组声明、`HttpServiceProxyRegistry` 统一取用、AOT 处理器原生支持 GraalVM；同时 Spring Boot 4.0（同月 GA）提供分组级属性配置，Spring Cloud 2025.1 提供分组级 LB/熔断。**至此功能面对齐 OpenFeign。**

## 3.2 核心架构与一次调用的完整生命周期

**白话版**：`HttpServiceProxyFactory` 拿到一个 `HttpExchangeAdapter`（RestClient/WebClient/RestTemplate 的适配器），把每个接口方法**编译**成两件东西——"如何从方法参数构建 HttpRequestValues"和"如何把响应变成返回值"——运行期调用只是：解析参数 → 构建 HttpRequestValues → adapter 执行。

**用法先看一眼**（手工路线，6.0/6.1 风格，至今有效）：

```java
public interface RepositoryService {
    @GetExchange("/repos/{owner}/{repo}")
    Repository getRepository(@PathVariable String owner, @PathVariable String repo);
}

RestClient restClient = RestClient.builder().baseUrl("https://api.github.com").build();
HttpServiceProxyFactory factory = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient)).build();
RepositoryService service = factory.createClient(RepositoryService.class);
```

【源码证据】（spring-web）：

1. **工厂与适配器**。`invoker/HttpServiceProxyFactory.java:56`（`@since 6.0`）；`:127` `builderFor(HttpExchangeAdapter)`（`@since 6.1`）。Builder 可挂：自定义 `HttpServiceArgumentResolver`、`ConversionService`、`HttpRequestValues.Processor`（7.0）、`exchangeAdapterDecorator`（7.0，观测/熔断等横切的挂点）、`proxyFactoryCustomizer`（7.1）、`embeddedValueResolver`（占位符解析）。

2. **方法编译（初始化期）**。`invoker/HttpServiceMethod.java:69`（final 类，package-private）。构造时按返回值形态选执行函数：

```java
// HttpServiceMethod.java:101~107（节选）
HttpRequestValuesInitializer.create(...),                     // 参数 → HttpRequestValues
ReactorExchangeResponseFunction.create(...)                   // 响应函数（Reactor 适配器走响应式分支）
    : ExchangeResponseFunction.create(adapter, method, serviceType)));
```

3. **调用（运行期）**。`HttpServiceMethod.invoke(Object[] arguments)`（`:132`）：跑参数解析器链 → 产出 `HttpRequestValues`（含 URL 模板、headers、cookies、body）→ `adapter.exchangeForBody(requestValues, bodyType)`（接口契约见 `invoker/HttpExchangeAdapter.java`，`@since 6.1`：`exchange / exchangeForHeaders / exchangeForBody / exchangeForBodilessEntity / exchangeForEntity` 五件套）。

**与 Feign 的结构性差异**（值得放慢读）：Feign 运行期要经历"RequestTemplate → RequestInterceptor 逐个改写 → 不可变 Request"；HTTP Service 运行期只构建**一个**`HttpRequestValues`（不可变、类型化），横切定制要么在初始化期注入（argumentResolver/processor），要么在 adapter 层装饰（`exchangeAdapterDecorator`——Spring Cloud 的熔断挂接正是用它，见 3.7）。初始化/运行期职责切分更彻底，运行期对象更少。

## 3.3 注解与参数解析层

**注解族**（`spring-web/.../web/service/annotation/`）：`@HttpExchange`（元注解，含 `url`、`method`、`accept`、`contentType`）+ 五个快捷注解 `@GetExchange / @PostExchange / @PutExchange / @PatchExchange / @DeleteExchange`。`url` 支持占位符（配 `embeddedValueResolver` 解析 `${...}`）。

**参数解析器**（`invoker/` 包，11 个，全部实证）：

| 解析器 | 对应参数 | 备注 |
|---|---|---|
| PathVariableArgumentResolver | @PathVariable | |
| RequestParamArgumentResolver | @RequestParam / Map / MultiValueMap | |
| RequestHeaderArgumentResolver | @RequestHeader | |
| CookieValueArgumentResolver | @CookieValue | |
| RequestBodyArgumentResolver | @RequestBody（无注解 POJO 也落这里） | |
| RequestPartArgumentResolver | @RequestPart（multipart） | |
| HttpMethodArgumentResolver | HttpMethod | |
| UriArgumentResolver | URI 参数 | |
| UriBuilderFactoryArgumentResolver | UriBuilderFactory（6.1 新增） | |
| RequestAttributeArgumentResolver | 请求属性 | |
| AbstractNamedValueArgumentResolver | 上两者的公共骨架 | |

**关键设计点**：注解名与 MVC 完全同名同语义，但**注解类型是独立的一套**（`org.springframework.web.service.annotation.*`，不是 MVC 的 `org.springframework.web.bind.annotation.*`）。这带来一个独特能力——**服务端对称**：一个 `@Controller` 可以直接实现这个接口，此时 HTTP Service 接口就是"一式两份的契约"（客户端代理 + 服务端实现各用一半），改接口两端同时编译报错。OpenFeign 做不到这一点（它的 SpringMvcContract 只读不实现——而且类级 @RequestMapping 直接抛异常，见 2.3）。

另一个实用细节：`@HttpExchange` 的 `url` 属性就是天然的"类级前缀"承载——

```java
@HttpExchange("/api/users")          // 类级前缀，等价于 @FeignClient(path=...)，但更直观
public interface UserApi {
    @GetExchange("/{id}")
    User get(@PathVariable Long id);
}
```

## 3.4 支持的 HTTP Client 选型（含探测顺序源码）

**白话版**：HTTP Service 不自己定义传输接口，而是"骑"在 Spring 6.x 统一客户端体系上。选型分两层：**选 adapter**（决定阻塞/响应式形态），**选 request factory**（决定连接栈）。

**第一层：HttpExchangeAdapter 三实现**

| Adapter | 模块 | 底层客户端 | 形态 |
|---|---|---|---|
| `RestClientAdapter` | spring-web（`web/client/support/RestClientAdapter.java`） | RestClient | 同步流式 API（6.1+ 推荐） |
| `RestTemplateAdapter` | spring-web（同目录） | RestTemplate | 同步老式 API（**7.1 起 RestTemplate 全类废弃**，`RestTemplate.java:105`） |
| `WebClientAdapter` | spring-webflux（`web/reactive/function/client/support/WebClientAdapter.java`） | WebClient | 响应式（Mono/Flux 返回值，走 `ReactorHttpExchangeAdapter` 分支） |

**第二层：ClientHttpRequestFactory 五实现**（`spring-web/.../http/client/`，本地目录逐一验证）：

| 工厂 | 连接栈 | 特点 |
|---|---|---|
| `SimpleClientHttpRequestFactory`（`:41`） | HttpURLConnection | 零依赖兜底；JVM 全局 keep-alive；7.1 时代已是最后位 |
| `HttpComponentsClientHttpRequestFactory` | **Apache HttpClient 5** | 连接池可精细配置，生产首选之一 |
| `JdkClientHttpRequestFactory` | JDK HttpClient | 零依赖 + HTTP/2 + 现代 TLS |
| `JettyClientHttpRequestFactory` | Jetty HttpClient | 与 Jetty 容器共用线程资源时划算 |
| `ReactorClientHttpRequestFactory` | **Reactor Netty** | 响应式同源，事件循环复用 |

**默认探测顺序**（framework 侧）：

```java
// DefaultRestClientBuilder.java:448~466（initRequestFactory，节选）
if (this.requestFactory != null) return this.requestFactory;
else if (HTTP_COMPONENTS_CLIENT_PRESENT) return new HttpComponentsClientHttpRequestFactory();
else if (JETTY_CLIENT_PRESENT)           return new JettyClientHttpRequestFactory();
else if (REACTOR_NETTY_CLIENT_PRESENT)   return new ReactorClientHttpRequestFactory();
else if (JDK_CLIENT_PRESENT)             return new JdkClientHttpRequestFactory();
else                                     return new SimpleClientHttpRequestFactory();
```

Boot 侧（4.x `module/spring-boot-http-client/.../ClientHttpRequestFactoryBuilder.java:160~172`）的 detect 顺序一致：**HttpComponents → Jetty → Reactor → JDK → Simple**——类路径里有哪个用哪个；Boot **没有**"用属性指定工厂"的开关（源码全局检索无此属性），想钉死某种栈需注入自定义 `ClientHttpRequestFactoryBuilder`（如 `ClientHttpRequestFactoryBuilder.jdk()`）或自建 RestClient 并指定 `requestFactory`。

## 3.5 连接池的配置与隔离策略

### 3.5.1 各底层 client 的连接池真相

与 2.6.1 逐项对齐：

- **Simple**：同 HttpURLConnection，JVM 全局缓存（`http.maxConnections` 默认 5/主机），无 per-client 池。
- **HttpComponents（HC5）**：`PoolingHttpClientConnectionManager` 池化；**Boot 4 的统一属性不直接暴露池大小**（`spring.http.client.*` 只有超时/SSL/重定向等，见 3.5.2），要调池参数需自定义 `HttpClient5` 构造（`ClientHttpRequestFactoryBuilder.httpComponents().configure(...)` 或自建 factory）。
- **Jdk**：JDK 内置池 + 系统属性进程级调整。
- **Jetty**：内置连接管理（max-connections-per-queue 等可经 Jetty API 调）。
- **Reactor Netty**：Spring 用 `ReactorResourceFactory` 托管资源，默认连接池是一个**固定 500 连接**的池（【源码证据】`spring-web/.../http/client/ReactorResourceFactory.java:62`）：

```java
private Supplier<ConnectionProvider> connectionProviderSupplier = () -> ConnectionProvider.create("webflux", 500);
```

事件循环（LoopResources）同源共享，可与 WebFlux 服务端复用。

### 3.5.2 统一配置层：Boot 把"应用级默认值"接管了

演进线：Boot 3.4 引入 `ClientHttpRequestFactorySettings`（`v3.4.0` 实证 `DEFAULTS` 常量，`:46`）→ Boot 4 更名/归位为 `HttpClientSettings`（`module/spring-boot-http-client/.../HttpClientSettings.java`：cookieHandling、redirects、connectTimeout、readTimeout、sslBundle 五要素），属性前缀 `spring.http.client.*`。Boot 把这些设置应用到**所有**经 Builder 产出的 RestTemplate/RestClient/WebClient——这是 Feign 体系没有的"应用级连接栈统一治理"。

**分组级（7.0 + Boot 4 新能力）**：每个 group 可以有独立一份：

【源码证据】Boot 4.0 GA：

```java
// module/spring-boot-http-client/.../service/HttpServiceClientProperties.java:32（v4.0.0）
@ConfigurationProperties("spring.http.serviceclient")
```

main 快照同款绑定（`:63`：`Binder.get(environment).bind("spring.http.serviceclient", Bindable.mapOf(String.class, HttpClientProperties.class))`）。每个 group 可配的键（`HttpClientProperties extends HttpClientSettingsProperties`，字段实证）：

| 键（`spring.http.serviceclient.<group>.*`） | 类型 | 说明 |
|---|---|---|
| `base-url` | String | 分组基地址（LB 场景可省略，见 3.7） |
| `default-header.<name>` | Map | 分组公共头 |
| `connect-timeout` / `read-timeout` | Duration | **分组级超时**（继承全局 `HttpClientSettingsProperties:44/:49`） |
| `ssl.bundle` | String | 分组独立 SSL bundle（`:105`） |
| `redirects` / `cookie-handling` | enum | 重定向与 Cookie 策略（`:39/:54`） |
| `apiversion.*` | 嵌套 | API 版本协商（7.0 的 API Versioning 配套） |

### 3.5.3 隔离策略：HttpServiceGroup（不做子容器，做"分组定制"）

**白话版**：HTTP Service 的隔离单位是 group。7.0 之前没有 group，每个接口自己 `createClient`，要么各建各的 RestClient（天然隔离但浪费），要么共享一个（省资源但绑死）；7.0 的 Registry 让"一组接口共享一套 client 定制"成为一等公民——但定制是**在同一个应用上下文里完成**的，没有 Feign 那种子容器。

【源码证据】：

```java
// registry/HttpServiceGroup.java（7.0）
public interface HttpServiceGroup {
    String DEFAULT_GROUP_NAME = "default";
    String name();
    Set<Class<?>> httpServiceTypes();
    enum ClientType { REST_CLIENT, WEB_CLIENT, UNSPECIFIED; }   // 7.0 即如此，无 REST_TEMPLATE
}
```

```java
// registry/HttpServiceGroupConfigurer.java（7.0，节选方法名）
void configureGroups(Groups<CB> groups);
// Groups: filterByName(...) / filter(...) / forEachClient(...) / forEachGroup(...) / forEachProxyFactory(...)
```

声明方式（`registry/ImportHttpServices.java`）：

```java
@Repeatable(ImportHttpServices.Container.class)
@Import(ImportHttpServiceRegistrar.class)
public @interface ImportHttpServices {
    Class<?>[] value() default {};          // types
    String group() default ...;
    String[] basePackages() default {};     // 按包扫描接口
    ClientType clientType() default ClientType.UNSPECIFIED;  // UNSPECIFIED → RestClient
}
```

编程式注册走 `AbstractHttpServiceRegistrar`（`registry.forGroup("github").detectInBasePackages(...)`），AOT 转译由 `HttpServiceProxyBeanRegistrationAotProcessor` 完成（GraalVM 免手工 hint）。

Boot 4 的自动配置把"分组定制"串起来（【源码证据】`module/spring-boot-restclient/.../service/HttpServiceClientAutoConfiguration.java`）：

```java
@AutoConfiguration(after = { HttpServiceClientPropertiesAutoConfiguration.class,
        ImperativeHttpClientAutoConfiguration.class, RestClientAutoConfiguration.class })
@ConditionalOnClass(RestClientAdapter.class)
@ConditionalOnBean(HttpServiceProxyRegistry.class)          // 只有用了 @ImportHttpServices 才装配
public final class HttpServiceClientAutoConfiguration {
    @Bean
    PropertiesRestClientHttpServiceGroupConfigurer ...       // ① 把 spring.http.serviceclient.<group>.* 灌进每个 group
    @Bean
    RestClientCustomizerHttpServiceGroupConfigurer ...       // ② 把用户的所有 RestClientCustomizer 应用到每个 group
}
```

**隔离粒度对比（与 Feign 对齐记忆）**：

| 想隔离的东西 | Feign | HTTP Service |
|---|---|---|
| 超时/公共头/拦截器 | per-client 子容器 | per-group 属性 + HttpServiceGroupConfigurer |
| 底层连接池 | 默认共享，需手工给子容器塞独立 HttpClient | per-group 可配独立 factory builder + HttpClientSettings（Boot 4 Properties configurer 每组独立构建），天然更近"分组即隔离" |
| 配置冲突面 | 子容器彻底隔离，但 Bean 不可跨组引用 | 同一上下文，定制按组过滤（`filterByName`） |

## 3.6 超时、重试与错误处理

**超时**：没有"方法调用级 Options"这种运行期旋钮（对比 Feign 的 `findOptions`）——超时是**连接栈的属性**：全局 `spring.http.client.connect-timeout/read-timeout`，分组 `spring.http.serviceclient.<group>.connect-timeout/read-timeout`；需要更细的 per-request 超时，得在 RestClient/WebClient 层面用 `timeout()` 响应式操作符或自建多个 group。设计哲学差异：Feign 把超时当"调用参数"，HTTP Service 把超时当"客户端配置"。

**错误处理**：无 ErrorDecoder。错误语义由 adapter 决定——RestClient 默认对 4xx/5xx 抛 `RestClientResponseException` 家族（HttpClientErrorException/HttpServerErrorException，携带 status/headers/body），WebClient 抛 `WebClientResponseException` 家族。定制入口是 RestClient 的 `defaultStatusHandler(...)`（`RestClient.Builder` 上的默认状态处理器，可挂到每个 group 的 builder 上），或干脆把 HTTP Service 接口返回 `ResponseEntity<T>` 自己判断状态。与 Feign 相比：**错误模型统一（全 Spring 生态一种异常家族），但"按服务定制错误语义"要靠 status handler/装饰器，不如 ErrorDecoder 直白**。

**重试**：HTTP Service 自身**无重试内建**。可选层次：(1) Framework 7 的内建韧性注解 `@Retryable`/`@ConcurrencyLimit`（`org.springframework.resilience`，v7.0 起可用在任意 bean 方法上，含 HTTP Service 接口方法）；(2) Spring Retry/Resilience4j 显式包裹；(3) Spring Cloud 负载均衡重试（LB 层，见 3.7）。

## 3.7 Spring Cloud 生态集成（2025.1+）：LB 与熔断的"分组挂接"

这是 7.0 / Boot 4 / Cloud 2025.1 三者合体后最值得关注的变化——**HTTP Service 终于在 Spring Cloud 里成为一等公民**。

**负载均衡**（spring-cloud-commons 5.x）：

- 老路线（6.x 就有）：`@LoadBalanced` 注解在 `RestClient.Builder`/`WebClient.Builder` 上，由 `LoadBalancerRestClientBuilderBeanPostProcessor` 给 builder 挂 `LoadBalancerInterceptor`（`spring-cloud-commons/.../loadbalancer/LoadBalancerInterceptor.java:33`，`implements BlockingLoadBalancerInterceptor` 的请求拦截器）——服务名写在 URL host 位，发请求时挑实例换地址。
- **新路线（2025.1+，推荐）**：`LoadBalancerRestClientHttpServiceGroupConfigurer`（`.../loadbalancer/LoadBalancerRestClientHttpServiceGroupConfigurer.java`）直接挂进分组注册流程：

```java
public class LoadBalancerRestClientHttpServiceGroupConfigurer implements RestClientHttpServiceGroupConfigurer {
    private static final int ORDER = 10;      // 抢在 Boot 的属性 configurer 之前跑
    ...
    groups.forEachGroup((group, clientBuilder, factoryBuilder) -> {
        HttpClientProperties groupProperties = clientServiceProperties.get(groupName);
        URI existingBaseUrl = ...;            // 用户显式配了 base-url 就尊重之
        if (existingBaseUrl == null) {
            URI baseUrl = constructBaseUrl(groupName);   // 服务名 → lb 风格基地址
            clientBuilder.baseUrl(baseUrl);
            clientBuilder.requestInterceptor(loadBalancerInterceptor);  // DeferringLoadBalancerInterceptor
        }
    });
}
```

    即：**凡是没配 base-url 的分组自动获得负载均衡语义**，配了就当普通外部 API——声明方式与语义完全解耦。

- 响应式侧有对称的 `LoadBalancerWebClientHttpServiceGroupConfigurer`（WEB_CLIENT 分组）。

**熔断**（同在 spring-cloud-commons 的 `client/circuitbreaker/httpservice/` 包）：

- `CircuitBreakerRestClientHttpServiceGroupConfigurer`（`:41~47`）持有 spring-cloud `CircuitBreakerFactory`，通过 `exchangeAdapterDecorator`（3.2 提到的 7.0 挂点）把每组 adapter 包上熔断逻辑；
- fallback 用**注解声明**：`@HttpServiceFallback`（`:34~39` javadoc：为每个分组提供基于 HttpServiceFallback 注解的 fallback 行为，支持 per-group/per-class 映射）——接口的 fallback 实现类放上来即可，不再像 Feign 那样写在 `@FeignClient` 属性里。

**其他生态件**：Spring Security 7.0 对 HTTP Service 方法支持 `@ClientRegistrationId`（OAuth2 授权自动获取 token）；Micrometer 观测在 RestClient/WebClient 层原生内建（Boot 自动装配 Observation 定制器），HTTP Service 代理继承之；zipkin/tracing 链路无需任何 HTTP Service 专属配置。

## 3.8 本章小结

Spring HTTP Service 的源码是一个"**薄代理 + 厚复用**"的设计：invoker 包只做"接口方法 ↔ HttpRequestValues"的翻译（11 个参数解析器 + 初始化期编译），传输、序列化、连接池、观测、AOT 全部复用 Spring 6.x/7.x 统一体系。6.0 时它只是"手工 new 出来的 Feign 替代品"；6.1 补上 adapter 抽象与 RestClient；7.0 的 Registry + Boot 4 的分组属性 + Cloud 2025.1 的 LB/熔断挂接让它补齐了 OpenFeign 的功能面，且隔离粒度（group）与配置面（属性绑定）比子容器模型更直白。代价也很清楚：绑定 Spring 技术栈、错误语义随 adapter 走、无运行期调用级超时旋钮、6.x 时代没有开箱即用的 LB/CB。

---

# 四、正面对比：OpenFeign vs Spring HTTP Service

## 4.1 总对照表

| 维度 | OpenFeign（feign 13.x + spring-cloud-openfeign 5.x） | Spring HTTP Service（Framework 7 + Boot 4 + Cloud 2025.1） |
|---|---|---|
| 注解体系 | Spring MVC 注解（经 SpringMvcContract）或 Feign 自有/JAX-RS 契约 | @HttpExchange 家族（独立体系，与 MVC 同名同语义） |
| 类级路径前缀 | ❌ 类级 @RequestMapping 直接抛异常（SpringMvcContract.java:196）；用 @FeignClient(path) | ✅ @HttpExchange(url) 天然支持 |
| 服务端/客户端契约共享 | ❌（单向：接口仅供客户端） | ✅ @Controller 可实现同一接口 |
| 底层传输 | feign Client 7+ 实现（HC5 默认） | RestClient/WebClient/RestTemplate × 5 种 ClientHttpRequestFactory |
| 连接池归属 | 各 Client 模块 + spring.cloud.openfeign.httpclient.* | ClientHttpRequestFactory + spring.http.client.* / spring.http.serviceclient.<group>.* |
| 连接池默认隔离 | ⚠️ 全局共享（需手工 per-client 独立 HttpClient） | ✅ 分组级独立 factory（Boot 4 Properties configurer 每组构建） |
| per-client/per-group 隔离机制 | NamedContextFactory 子容器（FeignClientFactory.java:40） | HttpServiceGroup + Configurer（无子容器） |
| 超时默认值 | connect 10s / read 60s（Request.Options） | 由 factory 决定（JDK/HC5 各有默认；Boot 可全局/分组覆盖） |
| 调用级超时覆盖 | ✅ 传 Request.Options 参数（SynchronousMethodHandler:51） | ❌（在 client/group 层配置） |
| 重试 | Retryer 可插拔，默认 NEVER_RETRY（Retryer.java:47） | 无内建；Framework 7 @Retryable / Spring Retry / LB retry |
| 错误模型 | ErrorDecoder → FeignException（按客户端可换） | RestClientResponseException 家族（全局统一）+ defaultStatusHandler |
| 负载均衡 | FeignBlockingLoadBalancerClient 装饰 Client（:79~110） | LoadBalancerRestClientHttpServiceGroupConfigurer / @LoadBalanced（:33） |
| 熔断与 fallback | FeignCircuitBreakerTargeter + @FeignClient(fallback) | CircuitBreakerRestClientHttpServiceGroupConfigurer + @HttpServiceFallback |
| 可观测性 | MicrometerObservationCapability（Capability 注入） | RestClient/WebClient 原生 Observation |
| multipart | ✅ SpringEncoder + feign-form-spring | ✅ @RequestPart + 转换器 |
| AOT/GraalVM | 无专属处理（反射 + 动态代理，需 hint） | ✅ 专属 AOT 处理器（7.0） |
| 基线 | Java 8（feign pom.xml:160）；spring-cloud-openfeign 跟 Boot 走 | JDK 17 + Boot 3/4 |
| 非 Spring 可用 | ✅（feign-core 零 Spring 依赖） | ❌ |
| 社区与节奏 | 独立社区（月更节奏，2026 年 8 个月 11 发版） | Spring 官方路线图一环 |
| Spring 官方态度 | 继续维护（5.x 随 Boot 4 演进，OkHttp 支持移除） | 官方推荐的新姿势（"more minimal and widely useful"） |
| 最小配置心智负担 | 属性命名空间多（client/httpclient/circuitbreaker/compression…） | 分组属性 + 少量 Configurer |

## 4.2 注解与契约模型：同一个 API 的两种写法

需求：调用 user-service 的"按 id 查用户"与"创建用户"。

**OpenFeign 写法**：

```java
@FeignClient(name = "user-service", path = "/api/users",
             fallbackFactory = UserClientFallbackFactory.class)
public interface UserClient {

    @GetMapping("/{id}")
    User getUser(@PathVariable("id") Long id);

    @PostMapping
    User createUser(@RequestBody User user);
}
```

（注意 `@PathVariable("id")` 在老版本必须显式写值；类级 `@RequestMapping("/api/users")` 会直接抛 IllegalArgumentException，见 2.3。）

**Spring HTTP Service 写法（Boot 4 + Cloud 2025.1）**：

```java
@HttpExchange("/api/users")
public interface UserClient {

    @GetExchange("/{id}")
    User getUser(@PathVariable Long id);

    @PostExchange
    User createUser(@RequestBody User user);
}

@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = "user-service", types = UserClient.class)
public class UserClientConfig {

    // 可选：给这个分组加公共头（Boot 4 也可用属性代替）
    @Bean
    RestClientHttpServiceGroupConfigurer authHeader() {
        return groups -> groups.filterByName("user-service")
                .forEachClient((group, builder) -> builder.defaultHeader("X-App", "demo"));
    }
}
```

```yaml
spring:
  http:
    serviceclient:
      user-service:
        base-url: http://user-service   # 省略即自动负载均衡（Cloud 2025.1）
        read-timeout: 2s
```

第一观感：**HTTP Service 的注解更少**（不用 @FeignClient 载体注解），路径前缀、fallback、超时各归其位；Feign 的写法把所有东西集中在一个 `@FeignClient` 注解上，紧凑但属性多、学习面大。

契约语义的深层差异有三点，选型时容易被忽略：

1. **参数注解省略规则**：HTTP Service 的 `@PathVariable Long id` 可以省注解值（编译期参数名）；Feign 的 SpringMvcContract 同样支持省略（`-parameters` 编译开启时），但历史版本行为不一致，很多团队规范强制显式写值——移植时是机械改动量最大的一处。
2. **通配/组合注解**：Feign 接口可以复用你自定义的"元注解组合"（SpringMvcContract 支持合成注解查找）；HTTP Service 目前只认 @HttpExchange 家族，自定义"语义化注解"需用元注解 + `@AliasFor` 自行扩展解析（或用 7.1 的 proxyFactoryCustomizer）。
3. **返回值形态**：两者都支持 `T`/`ResponseEntity<T>`；HTTP Service 额外无缝支持响应式 `Mono<T>/Flux<T>`（走 WebClientAdapter）——Feign 要去 reactive 包装模块绕一圈。

## 4.3 HTTP Client 与连接池对比（合并 2.5/2.6 与 3.4/3.5 的结论）

| | OpenFeign | Spring HTTP Service |
|---|---|---|
| 默认传输 | HC5（类路径有 feign-hc5 时；starter 默认带）→ JDK HttpClient → HttpURLConnection | Boot：探测 HttpComponents → Jetty → Reactor → JDK → Simple（与 Framework `DefaultRestClientBuilder:448~466` 同序） |
| 池参数配置 | `spring.cloud.openfeign.httpclient.max-connections=200 / per-route=50 / ttl=900s`（默认值源码实证） | `spring.http.client.*` 只有超时/SSL/重定向；**池大小需自定义 builder**；Reactor 默认 500（ReactorResourceFactory:62） |
| 按服务隔离池 | 可做（per-client 配置类注册独立 CloseableHttpClient）——默认共享 | 分组默认独立（每组走自己的 factoryBuilder + settings）——共享才是要配置的 |
| HTTP/2 | HC5（`http2.version=HTTP_2` 默认）或 JDK HttpClient | HC5 / JDK / Reactor Netty 均支持 |
| 兜底传输 | HttpURLConnection（JVM 全局池 5/主机） | Simple（同为 HttpURLConnection） |
| 池问题定位面 | FeignAutoConfiguration 的 @ConditionalOnMissingBean 层级 + HC5 管理器 | Boot 的 ClientHttpRequestFactoryBuilder.detect + factory 自身 |

两个工程结论：

1. **"谁默认给你隔离"恰好相反**：Feign 默认全共享、可按客户端拆；HTTP Service（Boot 4 分组）默认按组拆、要共享得配同一个 factory。对"几百个客户端共享一个 200 连接的池"这种隐性风险，HTTP Service 的默认姿势更安全。
2. **池参数的显式程度恰好相反**：Feign 把池参数搬进了属性文件（200/50/900s 一目了然）；HTTP Service/Boot 刻意不暴露池大小（认为这是连接栈的实现细节），想精细调优必须落到 HttpClient 构造代码。运维同学改配置文件的习惯在 Feign 一侧更顺。

## 4.4 隔离与配置模型对比

| | Feign 子容器（NamedContextFactory） | HTTP Service 分组 |
|---|---|---|
| 隔离单位 | contextId（每个 @FeignClient 一个子 ApplicationContext） | group（一组接口共享一份 client 定制） |
| 隔离强度 | 强：子容器 Bean 互不可见，可放同名不同型的 Bean | 中：同上下文，定制按组过滤（filterByName/forEachGroup） |
| 配置来源 | 子容器 @Configuration + `spring.cloud.openfeign.client.config.<id>.*` 属性 + FeignBuilderCustomizer（FeignClientFactoryBean:164~199） | `spring.http.serviceclient.<group>.*` 属性 + HttpServiceGroupConfigurer/RestClientCustomizer |
| 属性覆盖顺序 | 属性 > 配置类（configureFeign 明确实现该优先级，:177~199） | 属性 configurer（ORDER 先行）与用户 configurer 按顺序合并 |
| 新增客户端的成本 | 加一个接口（子容器自动建） | 加一个接口进 group（或 basePackage 扫描） |
| 跨客户端共享定制 | 全局默认配置 + 每个 client 显式引用 | 定制器对所有 group 生效或 filterByName 过滤，天然"声明一次、处处生效" |
| 心智负担 | 要理解两层容器、Bean 可见性、条件装配在子容器中的语义 | 只要理解"组"这一个概念 |

结论：Feign 的子容器是"为了隔离而隔离"（连 bean 类型冲突都不怕），表达力强但认知成本高；分组模型牺牲了子容器的强隔离，换来一条直白的配置路径。**除非你需要"每个客户端一套完全不同的编解码器体系"这类极端需求，分组模型够用且更好懂。**

## 4.5 负载均衡、熔断与韧性对比

| | OpenFeign | HTTP Service |
|---|---|---|
| LB 挂接点 | Client 装饰器（execute 里 choose → reconstructURI → 委托） | RestClient 请求拦截器（DeferringLoadBalancerInterceptor）或 WebClient Filter |
| LB 开关方式 | 隐式：@FeignClient 不写 url 即 LB | 显式：分组不配 base-url 即 LB；或老式 @LoadBalanced builder |
| 重试 | feign Retryer（默认关）+ RetryableFeignBlockingLoadBalancerClient（spring-retry） | LB 重试（同 loadbalancer 体系）+ Framework 7 @Retryable |
| 熔断 | circuitbreaker.enabled 开关 + Targeter 替换 + @FeignClient(fallback/fallbackFactory) | CircuitBreakerRestClientHttpServiceGroupConfigurer + @HttpServiceFallback（per-group/per-class） |
| 熔断器命名 | CircuitBreakerNameResolver 可定制 | 按分组/接口派生 |

语义差别很小，差别在**开关的显式程度与 fallback 的声明位置**：Feign 把 fallback 写在注解属性里（离接口近），HTTP Service 用独立注解类（离接口一步之遥但更符合"横切"直觉）。两者都能用 spring-cloud-circuitbreaker 的任意实现（Resilience4j/Sentinel——后者见本系列《Spring Cloud Alibaba Sentinel.md》）。

## 4.6 可观测性与调试体验对比

- **Feign**：观测要靠 Capability 挂（MicrometerObservability 自动条件装配），日志用 feign 自带的 Logger.Level（BASIC/HEADERS/FULL，输出到子容器 Logger）。问题：FULL 日志、指标名、tag 体系与 Spring 生态略有出入（如默认 metric 名 `http.client.requests` 的 tag 集与 RestClient 不同），跨客户端对比时要做一层心智对齐。
- **HTTP Service**：RestClient/WebClient 的 Observation 是 Boot 观测栈的一部分（`ObservedClientHttpRequest` 一路贯通），与 Micrometer Tracing 的 trace 传播、Tail sampling 无缝；调试可直接看 RestClient 的拦截器/交换过滤器。**HTTP Service 的观测是"继承"来的，Feign 的观测是"挂接"来的。**

## 4.7 生态位与社区对比

| | OpenFeign | Spring HTTP Service |
|---|---|---|
| 依赖足迹 | feign-core（~几百 KB）+ 选装模块；可脱离 Spring | spring-web（+ 可选 webflux）——本来就在 Boot 里 |
| 非 Spring 场景 | ✅ Quarkus/Vert.x/裸 Java 均可（vertx、kotlin 模块） | ❌ |
| 编解码生态 | 20+ 编解码模块（gson/jackson3/moshi/fastjson2/soap/jaxb…） | HttpMessageConverters 一种体系（但足够深） |
| 协议广度 | SOAP/JAX-RS/GraphQL/apt 生成等 | HTTP/REST 一件事 |
| 版本风险 | 跟随 Spring Cloud 大版本迁移（3.x→5.x 两次属性改名 + 5.0 移除 OkHttp） | 与 Boot 同节奏，无独立版本风险 |
| 长期趋势 | 社区活跃维护，无路线图承诺；Spring 官方推荐度下降 | Spring 官方主推；Boot 4/Cloud 2025.1 全面一等公民 |

值得单独一提的是 **5.0 移除 OkHttp** 的信号意义：spring-cloud-openfeign 的维护团队在主动收缩"底层传输支持面"，向 HC5/JDK HttpClient 聚拢——这缩小了 Feign 与 Spring 体系的差异面，也意味着"因为我已有 OkHttp 调优经验"这类选型理由的分量在下降。

## 4.8 兼容性检查单（迁移视角）

- **Boot 3.x → 4.x 对 Feign 用户**：属性前缀已是 `spring.cloud.openfeign.*`（4.x 早已迁移）；OkHttp 支持消失（5.0）；Netflix 系注解/配置（ribbon/hystrix era）早已不存在。主要动作是依赖替换与回归。
- **Feign → HTTP Service 迁移**：接口注解机械替换（@GetMapping→@GetExchange…），fallback 拆成 @HttpServiceFallback 类，per-client 配置从 `config.<id>.*` 搬到 `serviceclient.<group>.*`，连接池自定义从"注册 CloseableHttpClient"改为"注册 ClientHttpRequestFactoryBuilder/自定义 factory"。
- **共存策略**：两者可以在同一个应用共存（不同 Bean，互不冲突），推荐**按业务域渐进迁移**：新服务/新域用 HTTP Service，存量 Feign 域保持，收敛在 Boot 4 的统一 client 设置上。

## 4.9 性能与资源视角（简评）

两者的"翻译层"开销（注解解析已完成于启动期、运行期都是对象拼装）在毫秒级调用里可忽略；真正的差异在**连接栈与线程模型**：

- 高并发阻塞场景：HC5（两者都可骑）+ 合理池参数 > JDK HttpClient 默认 > HttpURLConnection 全局池；
- 大量并发 IO 等待（慢下游）：WebClient/Reactor Netty 的事件循环 + HTTP Service 响应式分支有结构性优势；Feign 侧要走 AsyncFeign/vertx/reactive 包装模块，生态更碎；
- 虚拟线程（Boot 3.2+ / Java 21）：两者都是阻塞 API，虚拟线程下均可直接受益，此时"响应式优势"叙事减弱，连接池容量成了新瓶颈点。

---

# 五、技术选型建议

## 5.1 先回答三个问题

1. **项目离开 Spring 生态吗？**（Quarkus/Vert.x/SDK 形态、非 Boot 项目）→ 离开：OpenFeign 是唯一解。
2. **Boot 版本是多少？** ≥ 4.0（配 Cloud 2025.1+）→ HTTP Service 功能面完整，默认选它；≤ 3.5 → 要么接受 HTTP Service 的"手工形态"，要么继续 OpenFeign。
3. **存量是什么？** 已有大规模 Feign 资产与团队习惯 → 没有痛点不迁移；痛点明确（OkHttp 依赖、子容器心智、Netflix 遗留恐惧、GraalVM/AOT 需求）→ 按 4.8 清单渐进迁移。

## 5.2 决策树

```
项目是否绑定 Spring Boot？
├─ 否 ────────────────────────────────► OpenFeign（feign-core + 契约模块）
└─ 是 → Boot ≥ 4.0 + Spring Cloud ≥ 2025.1？
    ├─ 否（Boot 2.7~3.5）
    │    ├─ 新项目、追求长期一致（团队愿意手工注册） ──► HTTP Service（手工工厂 + @LoadBalanced）
    │    └─ 追求开箱即用 / 存量迁移成本低 ──────────► OpenFeign（spring-cloud-openfeign 4.x）
    └─ 是（Boot 4.x）
         ├─ 需要 Feign 特有能力：
         │    · 非 Spring 项目共享同一套接口 SDK
         │    · JAX-RS/SOAP 等特殊契约
         │    · per-client 子容器级隔离/复杂编解码
         │    ────────────────────────────────► OpenFeign（5.x，注意 OkHttp 已移除）
         └─ 其余大多数场景 ────────────────────► HTTP Service（@ImportHttpServices 分组注册）
                                                 + 分组属性 + LB/CB 自动挂接 + @Retryable
```

## 5.3 场景映射表

| 场景 | 推荐 | 理由 |
|---|---|---|
| Boot 4 新微服务（Spring Cloud 全家桶） | HTTP Service | 分组注册 + LB/CB/观测全自动化，注解最少，AOT 原生 |
| 存量 Spring Cloud 2021~2025.0 项目 | OpenFeign（4.x） | HTTP Service 在 Boot 3.x 上功能不完整（无分组/自动 LB） |
| 多语言/多框架团队的接口 SDK（给非 Spring 服务用） | OpenFeign（feign-core） | 零 Spring 依赖，可打包成独立 SDK |
| 强观测/云原生（GraalVM、Trace 全链路） | HTTP Service | 原生 Observation + AOT 处理器 |
| 需要调用级精细超时（同一客户端不同方法不同超时） | OpenFeign | Request.Options 方法参数；HTTP Service 需拆分组绕行 |
| 错误语义按服务深度定制（ErrorDecoder 式） | OpenFeign（或 HTTP Service + defaultStatusHandler） | ErrorDecoder 直白；HTTP Service 要在 builder 层做 |
| 高并发 IO 密集 + 响应式栈 | HTTP Service（WebClient 分组） | WebClientAdapter 响应式一等支持 |
| 内网多服务、实例频繁扩缩容 | 两者皆可（LB 语义一致） | 看 Boot 版本与团队栈 |

## 5.4 一句话总结

> **站在 2026 年 10 月做新选型：只要你的世界是 Spring Boot 4 + Spring Cloud 2025.1，默认选 Spring HTTP Service——它已把 OpenFeign 十年验证出的"分组、隔离、负载均衡、熔断、观测"全部内建，且少一层第三方抽象；OpenFeign 留给三种人：不在 Boot 4 上的人、要脱离 Spring 发 SDK 的人、和依赖其极端可插拔性（子容器隔离/特殊契约/特殊编解码）的人。而在 Boot 3.x 的存量世界里，OpenFeign 仍是唯一"开箱即用"的声明式选项，HTTP Service 只是"能用但朴素"。**

## 5.5 常见误区与避坑清单

**OpenFeign 侧**：

1. 类级 `@RequestMapping` 直接抛异常（SpringMvcContract.java:196）——路径前缀用 `@FeignClient(path)`。
2. 默认 `Retryer.NEVER_RETRY`（Retryer.java:47）——"为什么我的 Feign 不重试"九成是这个；开了 `Retryer.Default` 又容易叠加 LB 重试造成重试风暴，二者只留一层。
3. 所有客户端默认共享一个全局连接池（200/50）——慢下游拖垮全局是经典事故；给慢服务单独注册 HttpClient/Client。
4. `name`/`contextId`/`url` 语义：写了 `url` 就绕过 LB 直连；同名不同配置的接口必须拆 `contextId`。
5. 属性前缀记忆：3.x `feign.*` → 4.x+ `spring.cloud.openfeign.*` → 5.0 OkHttp 支持移除。
6. Feign 默认超时 10s/60s 偏粗（对照 2.7 三层覆盖表），生产必须显式收敛 read-timeout。

**Spring HTTP Service 侧**：

1. Boot 3.x 上没有分组注册与自动 LB——不要拿 7.0 的文章直接套 6.x 用法。
2. 错误处理默认抛 `RestClientResponseException` 家族——从 Feign 迁来的团队要重写"按服务翻译异常"的层（defaultStatusHandler）。
3. 连接池大小不在属性文件里——需要池调优时去自定义 `ClientHttpRequestFactoryBuilder`（Reactor 默认 500，HC5 默认池参数同样要显式化）。
4. 分组命名即契约：`@ImportHttpServices(group = "user-service")` 的 group 名同时是 LB 的服务名与 `spring.http.serviceclient.<group>` 的属性键——起名要像服务名。
5. `@HttpExchange` 注解与 MVC 注解同名不同包——写接口时 import 错包会"看起来对却不起作用"（服务端实现时则恰恰依赖这种对称）。
6. 调用级超时没有内建旋钮：确实需要 per-method 超时时，用两个 group 或在响应式分支用 `timeout()`。

## 5.6 结语

声明式 HTTP 客户端的十年史，本质是"Netflix 时代的接口化 RPC 思想"被 Spring 官方吸收为基础设施的过程：OpenFeign 用可插拔管道证明了这套模式的价值与边界，Spring HTTP Service 则在 RestClient 时代用更少的抽象把它变成了框架本体的一部分。选型因此不是"哪个更好"的绝对题，而是"你的工程此刻站在哪条版本线上、把可插拔性和栈外可用性看得多重"的条件题——把 1.5.3 的配套矩阵、4.1 的对照表和 5.2 的决策树对着自己的项目走一遍，答案通常唯一且清晰。

---

## 参考资料

- Spring 官方博客：HTTP Service Client Enhancements（2025-09-23）：<https://spring.io/blog/2025/09/23/http-service-client-enhancements>
- Spring Framework 7.0 Release Notes：<https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-7.0-Release-Notes>
- @ImportHttpServices Javadoc（Framework 7.0）：<https://docs.spring.io/spring-framework/reference/web/webflux/controller.html>（HTTP Interface 章节）
- Spring Cloud 2025.1.x（Oakwood）/ 2025.0.x（Northfields）发布说明：<https://spring.io/projects/spring-cloud#learn>
- OpenFeign/feign：<https://github.com/OpenFeign/feign>
- spring-cloud-openfeign：<https://github.com/spring-cloud/spring-cloud-openfeign>
- 本系列配套文档：《Spring Framework.md》（RestClient/HttpExchange 所在 Web 体系）、《Spring Cloud LoadBalancer.md》、《Spring Cloud CircuitBreaker.md》、《Micrometer.md》
