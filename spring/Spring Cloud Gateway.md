# Spring Cloud Gateway 深度源码解析（写给初学者的架构全景）

> **本文基于的源码**：`D:\code\3rd\spring-cloud-gateway`，版本 **5.1.0-SNAPSHOT**（main 分支，Git commit `752fd490a`，提交于 2026-10-02，与本地 `spring-framework` 7.1.0-SNAPSHOT 同期）。文中所有【源码证据】的文件路径与行号均为对该快照实际读取所得。
>
> **版本取舍说明**：网关的架构自 2.0（2018，响应式内核诞生）以来高度稳定——"断言找路由 → 过滤链 → Netty 出网"这条主干在 3.x / 4.x / 5.x 之间骨架几乎一致，因此本文内容对使用 Spring Boot 3.x（Gateway 4.x）的读者同样适用；1.x → 5.x 的特性演进对比见 1.6 节，所有"某特性属于哪个版本"的结论均经本地 git 标签（v1.0.0.RELEASE ~ v5.0.3）逐项实证。
>
> **阅读约定**：每章"先白话、后源码"——先用一两句人话讲清"这是什么、为什么需要它"，再给出源码证据链。行号只对该快照精确，读者用 IDEA 打开源码按类名 + 方法名定位即可。主模块是 `spring-cloud-gateway-server-webflux`（响应式网关本体），下文章节中【源码证据】路径若以 `src/main/java` 开头且未加模块名，均指该模块下的 `org/springframework/cloud/gateway` 包（即 `spring-cloud-gateway-server-webflux/src/main/java/org/springframework/cloud/gateway/...`）；涉及其他模块时写全路径。

## 如何读这份文档

如果你是 API 网关初学者，推荐两遍读法：

- **第一遍（建立地图，1~2 小时）**：只读第一章（总览）每一节的开头白话段、各章的"小结"节、以及第九章（贯通视图）。目标是能回答：一条 YAML 路由是怎么变成可执行的 Route 对象的？一次 HTTP 请求进来先经过谁、后经过谁？`lb://` 是怎么变成真实 IP 的？
- **第二遍（深入源码）**：对照每一章的【源码证据】，打开源码逐行读。顺序建议：第二章（路由数据模型）→ 第三章（分发与过滤链，全书核心）→ 第五章（过滤器工厂）→ 第六章（动态路由）→ 第四章（断言工厂，随用随查）→ 第七章（自动配置与扩展）→ 第八章（双栈与生态，可跳读）。

---

# 一、总览：网关的定位、设计哲学与整体架构

## 1.1 一句话定位

**Spring Cloud Gateway 是一个建立在 Spring WebFlux 之上的 API 网关**：它站在微服务集群的入口，用"断言（Predicate）"决定一个请求"走哪条路由"，用"过滤器（Filter）"决定这条请求"被怎么加工"，再用 Reactor Netty 的 HTTP 客户端把请求转发到真正的下游服务。官方 README 自述：*"This project provides an API Gateway built on top of the Spring ecosystem, including: Spring 6, Spring Boot 3(4) and Project Reactor."*

它解决的不是单个问题，而是**微服务的入口横切问题**：外部请求如何统一进入（鉴权、限流、熔断都在网关做，不必每个服务做一遍）、内部服务如何被隐藏（客户端只见网关地址）、路由规则如何随发布动态变化（配置中心 + 刷新事件）、请求如何在服务实例间分摊（集成 Spring Cloud LoadBalancer）。

与老一代网关（Zuul 1）的本质区别在于**线程模型**：Zuul 1 是阻塞式 Servlet（一个请求占一个线程直到转发完成），Gateway 基于 Reactor 事件循环——转发下游等待响应的线程会被释放去处理别的请求，少数线程即可支撑高并发长连接（含 SSE/WebSocket 流式）。这也是它"只能跑在 Spring MVC 之外"的原因：源码里专门写了类路径冲突检测——响应式网关发现 classpath 上有 spring-webmvc 会直接启动失败并给出诊断。

【源码证据】`src/main/java/org/springframework/cloud/gateway/support/MvcFoundOnClasspathException.java`（全文 19 行，异常本体）与 `META-INF/spring.factories`（注册 `MvcFoundOnClasspathFailureAnalyzer` 生成人话诊断）。配套测试见 `spring-cloud-gateway-integration-tests/mvc-failure-analyzer`。

## 1.2 设计哲学：读源码前先记住四句话

1. **配方与成品分离**。配置文件里写的是 `RouteDefinition`（配方：id、断言名、过滤器名、目标 URI），运行时内存里跑的是 `Route`（成品：不可变、带编译好的 `AsyncPredicate` 与 `GatewayFilter` 列表）。两者之间由 `RouteDefinitionRouteLocator` 翻译（第二章）。所有动态路由能力（Actuator 改路由、配置中心刷新）都只在"配方层"操作，刷新时整体重译、原子换缓存。
2. **断言决定走不走，过滤器决定怎么走**。断言（Predicate）是纯判断，不碰请求；过滤器（Filter）包住 `chain.filter(exchange)` 形成责任链，可以在转发前/后各插一段逻辑。全局逻辑（选实例、出网、写回）用 `GlobalFilter`，路由级逻辑（改头、重写路径、限流）用 `GatewayFilterFactory` 产出的路由级过滤器——两者最终在 `FilteringWebHandler` 里合并成一条链、按 order 排序（第三章）。**"一切皆过滤器"是这个框架的心智模型**：负载均衡是过滤器（`ReactiveLoadBalancerClientFilter`），出网是过滤器（`NettyRoutingFilter`），WebSocket 转发也是过滤器。
3. **交换属性当黑板**。整个请求处理过程中，"匹配到哪条路由""目标 URL 改成了什么""上游连接在哪"这些中间状态全部放在 `ServerWebExchange` 的 attributes 里（key 定义在 `ServerWebExchangeUtils`），不建 ThreadLocal、不加新线程模型——这是它对 WebFlux 透明性的关键（3.5 节全表）。
4. **适配而非重造**。HTTP 客户端用 Reactor Netty（`HttpClientFactory`）、负载均衡用 Spring Cloud LoadBalancer（`ReactiveLoadBalancerClientFilter` 只是适配层）、熔断用 Spring Cloud CircuitBreaker（Resilience4J）、函数路由用 Spring Cloud Function（`fn:`）、消息路由用 Spring Cloud Stream（`stream:`）。Gateway 自己只做"装配与约定"，把这一堆技术用同一条过滤器链串起来（1.4 节依赖图）。

## 1.3 模块分层全景

工程是多模块 Maven 项目，main 分支（5.1.0-SNAPSHOT）的顶层模块与分层如下（源码目录实测）：

```
┌──────────────────────────────────────── 示例与测试 ────────────────────────────────────────┐
│  spring-cloud-gateway-sample（可运行示例）  spring-cloud-gateway-integration-tests（重场景）│
├──────────────────────────────────────── Starter ──────────────────────────────────────────┤
│  spring-cloud-starter-gateway-server-webflux          spring-cloud-starter-gateway-server-webmvc
│  （= spring-cloud-starter + server-webflux + boot-starter-webflux，仅 3 个依赖）
├──────────────────────────────────── 服务端双栈 ────────────────────────────────────────────┤
│  spring-cloud-gateway-server-webflux（响应式网关本体，约 250 类）                            │
│    ├ actuate      运维端点（/actuator/gateway）                                            │
│    ├ config       自动配置 + GatewayProperties + HttpClientProperties                       │
│    ├ discovery    服务发现自动路由（DiscoveryClientRouteDefinitionLocator）                 │
│    ├ event        RefreshRoutesEvent / RefreshRoutesResultEvent / WeightDefinedEvent       │
│    ├ filter       GlobalFilter 本体 + factory/（40+ 过滤器工厂）+ ratelimit/ + headers/      │
│    ├ handler      RoutePredicateHandlerMapping + FilteringWebHandler + predicate/（15 断言）│
│    ├ route        Route/RouteDefinition/各种 Locator/Repository/CachingRouteLocator         │
│    └ support      ServerWebExchangeUtils、ConfigurationService、ShortcutType、NameUtils     │
│  spring-cloud-gateway-server-webmvc（Servlet 栈网关，约 100 类，4.1 预览/4.3 正式）          │
├────────────────────────────────── 独立小工具 ──────────────────────────────────────────────┤
│  spring-cloud-gateway-proxyexchange-webflux / -webmvc（Controller 参数注入式迷你代理）      │
├──────────────────────────────────── BOM ──────────────────────────────────────────────────┤
│  spring-cloud-gateway-dependencies（版本对齐）                                             │
└────────────────────────────────────────────────────────────────────────────────────────────┘
```

另有三个"看得见但别动"的目录：`spring-cloud-gateway-server`（历史单体模块 5.0 拆分双栈后的残留空壳，只剩一份共享的 `additional-spring-configuration-metadata.json`，不在根 pom 的 `<modules>` 里，不参与构建）、`docs`（官方 asciidoc 文档源码）、`src`（工程级资源）。

## 1.4 模块依赖图（以各模块 pom 的依赖实证）

主模块 `spring-cloud-gateway-server-webflux/pom.xml` 的依赖声明（compile 仅 2 个，其余全部 optional，自动配置按 classpath 条件激活）：

| 依赖 | 强制/optional | 用途 |
|---|---|---|
| spring-boot-starter | compile | 基座 |
| spring-boot-starter-validation | compile | RouteDefinition 的 `@Validated` 校验 |
| spring-boot-starter-webflux | **optional** | WebFlux/Reactor/Reactor Netty |
| spring-cloud-loadbalancer | **optional** | `lb://` 负载均衡 |
| spring-cloud-starter-circuitbreaker-reactor-resilience4j | **optional** | 熔断过滤器 |
| spring-boot-starter-data-redis | **optional** | RedisRateLimiter / Redis 路由仓库 |
| spring-boot-starter-actuator | **optional** | /actuator/gateway 端点 |
| micrometer-tracing / spring-boot-micrometer-tracing | **optional** | 链路追踪 |
| caffeine | **optional** | LocalResponseCache |
| bucket4j_jdk17-core | **optional** | Bucket4jRateLimiter |
| grpc-* / jackson-dataformat-protobuf | **optional** | JsonToGrpc 过滤器 |
| spring-cloud-function-context / spring-cloud-stream | **optional** | `fn:` / `stream:` 路由 |
| spring-boot-starter-oauth2-client / security | **optional** | TokenRelay 过滤器 |

这张表本身就是一份架构说明：**网关内核不强制依赖任何"重"技术**——不装 LoadBalancer 就没有实例选择（有 `NoLoadBalancerClientFilter` 兜底报 503/404），不装 Redis 就没有 Redis 限流，不装 actuator 就没有运维端点。starter（`spring-cloud-starter-gateway-server-webflux`）只再叠加 `spring-cloud-starter` 与 `spring-boot-starter-webflux` 两个依赖；**指标、端点、限流都要自己额外引**（官方 sample 因此单独引了 actuator）。

## 1.5 关键问题 → Gateway 方案映射（全文导览）

| 微服务入口的关键问题 | Gateway 的方案 | 详见 |
|---|---|---|
| 路由规则写死在代码里，改一条要发版 | RouteDefinition 配方层 + 多来源 Locator + 刷新事件 | 第二章、第六章 |
| 一个请求该交给哪个服务 | 15 种 RoutePredicateFactory（路径/方法/头/时间/权重/版本…）| 第四章 |
| 跨服务的通用逻辑（加头、限流、熔断）每处都要写 | GlobalFilter + default-filters + 路由级过滤链 | 第三章、第五章 |
| 客户端直连内部服务，拓扑泄漏 | 统一入口 + 路径重写（StripPrefix/RewritePath）+ 服务发现自动路由 | 第四章、第六章 |
| 上下游协议/地址差异 | RouteToRequestUrlFilter 合并 URL + ReactiveLoadBalancerClientFilter 换实例地址 | 第三章 |
| 下游故障拖垮入口 | CircuitBreaker 过滤器（fallbackUri）+ Retry 过滤器（指数退避）| 第五章 |
| 突发流量打垮下游 | RequestRateLimiter（Redis 令牌桶）/ Bucket4j | 第五章 |
| 每个请求的耗时/状态没有统一观测 | GatewayMetricsFilter + Micrometer Observation + Tracing | 第七章 |
| 运维要在线看/改路由 | /actuator/gateway 端点（routes 的增删查 + refresh）| 第六章 |
| 不想用响应式栈 | Server WebMVC（MVC 函数式端点实现）+ ProxyExchange | 第八章 |

## 1.6 版本演进：1.x → 2.x → 3.x → 4.x → 5.x

写作时（2026 年 10 月）的版本格局：4.3.x（2025.0 Northfields train，Boot 3.5）与 5.0.x（2025.1 Oakwood train，Boot 4.0/Framework 7）并行维护，5.1 尚在快照阶段（正是本文分析的这份 main 分支）。本节所有"特性属于哪个版本"的结论都经过双重验证：**① 用本地仓库的 git 标签（v1.0.0.RELEASE ~ v5.0.3 共 83 个 GA tag）对源码逐项 grep / ls-tree 实证；② GA 日期取自各 tag 的提交日期（本仓库除 v5.0.3 外均为 lightweight tag，提交日即发布日）**。每条结论都附了可复现的命令模式。

### 1.6.1 版本时间线与运行基线

| 版本 | GA 时间 | Spring Cloud train※ | 基线（Spring/Framework · Boot） | 一句话主题 |
|---|---|---|---|---|
| 1.0.0.RELEASE | 2017-11-22 | 不属于任何 train | Spring MVC（**非响应式**） | 实为 ProxyExchange 小库（7 个 Java 文件），无网关内核 |
| 2.0.0.RELEASE | 2018-06-19 | Finchley | Spring 5 · Boot 2.0 | **响应式网关内核诞生**（Reactor + WebFlux + Netty） |
| 2.2.0.RELEASE | 2019-11-26 | Hoxton | Spring 5 · Boot 2.2 | CircuitBreaker 过滤器取代 Hystrix 路线 |
| 3.0.0 | 2020-12-21 | 2020.0 (Ilford) | Spring 5 · Boot 2.4 | core→server 改名；Hystrix 移除 |
| 3.1.0 | 2021-12-01 | 2021.0 (Jubilee) | Spring 5 · Boot 2.6 | 迭代增强 |
| 4.0.0 | 2022-12-15 | 2022.0 (Kilburn) | Spring 6 · Boot 3.0（**jakarta.**） | 基座大迁移 + AutoConfiguration.imports |
| 4.1.0 | 2023-12-06 | 2023.0 (Leyton) | Spring 6 · Boot 3.2 | **Server MVC 网关首次引入**（server-mvc，预览态） |
| 4.2.0 | 2024-12-03 | 2024.0 (Moorgate) | Spring 6 · Boot 3.4 | RouteDefinition.enabled 等 |
| 4.3.0 | 2025-05-29 | 2025.0 (Northfields) | Spring 6 · Boot 3.5 | 模块改名双栈化（新老并存）；配置前缀改为 `spring.cloud.gateway.server.webflux` |
| 5.0.0 | 2025-11-24 | 2025.1 (Oakwood) | Spring 7 · Boot 4.0 | 老模块/老 starter 移除；Version 断言；GatewayFilter 接口重构 |
| 5.1.0-SNAPSHOT（本文） | 未 GA（M1 2026-09-24） | 下一代 | Spring 7 · Boot 4 | 迭代增强（最新 GA 为 v5.0.3，2026-08-20） |

※ release train 名称与 Boot 大版本的对应来自 spring.io 官方公告与 spring-cloud-release wiki（外部映射，仓库内 pom 只写 `${spring-boot.version}` 占位符由父 POM `spring-cloud-build` 注入，无法直接考证小版本）；一个可复核的旁证：v5.0.0 树内 README 仍写着 "Spring Framework 6 / Spring Boot 3"，属于文档未随大版本更新，以官方公告为准。另注：配置前缀 `spring.cloud.gateway` → `spring.cloud.gateway.server.webflux` 的改名经 `git show <tag>:.../GatewayProperties.java` 实证发生于 **v4.3.0**。

### 1.6.2 特性引入版本对照表（git 实证，可复现）

每行都可用 `git log --diff-filter=A --oneline -- '<文件glob>'` 找最早提交、`git tag --contains <sha>` 找首个 GA tag 复现：

| 特性 | 引入 commit | 首个 GA | 备注 |
|---|---|---|---|
| 项目起点 | 2016-11-16 `9e493f80e` "first commit" | v1.0.0.RELEASE | 源自 spring-cloud-incubator |
| Hystrix 过滤器（历史） | 2017-01-17 | v2.0.0.RELEASE（树级） | 2020-01-21 删除 → v3.0.0 |
| RedisRateLimiter 令牌桶 | 2017-04-18 | v2.0.0.RELEASE（树级） | `request_rate_limiter.lua` 延用至今 |
| WebSocket 转发 | 2017-08-09 | v2.0.0.RELEASE | |
| Retry 过滤器（Reactor retryWhen） | 2018-02-14 | v2.0.0.RELEASE | |
| Weight 权重路由 | 2018-03-01 | v2.0.0.RELEASE | |
| GatewayMetricsFilter 指标 | 2018-07-17 | v2.0.1.RELEASE | |
| CircuitBreaker 过滤器（Spring Cloud CircuitBreaker） | 2019-11-05 | v2.2.0.RELEASE | 接替 Hystrix |
| **core → server 模块改名** | 2020-09-08 #1807 | v3.0.0 | 2.2.6 维护线也含过渡 |
| javax. → jakarta. | — | v4.0.0 | `git grep javax.servlet v4.0.0` 归零 |
| AutoConfiguration.imports 注册文件 | — | v4.0.0 | spring.factories 并存未删 |
| LocalResponseCache 本地响应缓存 | 2022-10-25 #2759 | v4.0.0 | Caffeine |
| Server MVC 网关（server-mvc + starter） | 2023-07-05 #2949 | v4.1.0 | 预览态 |
| RouteDefinition.enabled 开关 | 2023-08-06 | v4.2.0 | |
| 模块改名 server-mvc→server-webmvc、starter 双栈化 | 2025-01-16 #3645 | v4.3.0（新老并存） | 老命名 2025-07-23 #3859 删除 → v5.0.0 |
| Version 断言（API 版本化） | 2025-07-28 #3865 | v5.0.0 | 依赖 Framework 7 的 ApiVersionStrategy |
| GatewayFilter 接口重构（不再继承 WebFilter） | — | v5.0.0 | 接口 Javadoc `@since 5.0`（GatewayFilter.java:32） |

三个容易搞错的点，特别提醒（本文初稿也错了前两处，均已被 git 标签实证修正）：

- **1.x 不是"响应式网关的早期版本"**：v1.0.0.RELEASE 树里只有从 spring-cloud-function 迁来的 MVC 版 ProxyExchange 库（`git ls-tree -r v1.0.0.RELEASE | grep -c '\.java$'` = 7 个），响应式内核从 2.0 线起步（2017-06-30 在 1.x 线执行过 "Remove 2.0.x modules"，两线就此分叉）；
- **`RouteDefinition.enabled` 不是 5.1 特性**：`git grep "private boolean enabled" v4.2.0` 命中，v4.1.0 无，v4.2.0（2024-12-03）已落地；
- **spring.factories 并没有被删除**：4.0.0 新增 AutoConfiguration.imports 后，`spring.factories` 一直并存至今（只用于注册 EnvironmentPostProcessor / FailureAnalyzer 这类非自动配置条目）。

### 1.6.3 模块与命名变迁史（选教材时对号入座）

| 版本 | 顶层 spring-cloud 模块（`git ls-tree` 实证） |
|---|---|
| v1.0.0.RELEASE | gateway-mvc（ProxyExchange）、gateway-dependencies、gateway-sample |
| v2.0.0.RELEASE | gateway-core（响应式内核）、gateway-webflux、gateway-mvc、gateway-sample、starter-gateway |
| v3.0.0 ~ v4.0.0 | core 改名 gateway-server；gateway-mvc、sample、starter-gateway、integration-tests（3.1 起） |
| v4.1.0 / v4.2.0 | + gateway-server-mvc、starter-gateway-mvc |
| v4.3.0 | + gateway-server-webflux / -webmvc、starter-gateway-server-webflux / -webmvc、proxyexchange-webflux / -webmvc（**新旧两套并存**） |
| v5.0.0 起 | 老命名全部消失，只剩 server-webflux、server-webmvc、两个新 starter、proxyexchange ×2、dependencies、sample、integration-tests |

对应到使用者：**用 Boot 3.x 的老教程找的是 `spring-cloud-starter-gateway`（= 现在的 server-webflux）；5.x 的新工程用 `spring-cloud-starter-gateway-server-webflux`（响应式）或 `spring-cloud-starter-gateway-server-webmvc`（Servlet 栈）**。

### 1.6.4 对初学者的意义：哪些知识过时了，哪些永远有效

- **新工程避免使用**：`spring-cloud-starter-gateway` 老坐标（5.0 已移除）、`spring.cloud.gateway.*` 老配置前缀（4.3 起改 `spring.cloud.gateway.server.webflux.*`）、Hystrix 过滤器（3.0 已删）、`spring.factories` 方式的自定义自动配置注册。
- **2.x 老教程里永久有效的部分**：Route/Predicate/Filter 三概念、断言与过滤器工厂的 shortcut 写法、GlobalFilter 责任链、`lb://` 语义、Netty 转发骨架——这些从 2.0 到 5.1 基本未变，本文第二~五章正是按这条主线写的。
- **版本与 Boot 的对应关系**（选教材时对号入座）：Boot 2.x ↔ Gateway 3.x；Boot 3.0/3.1 ↔ 4.0；Boot 3.2/3.3 ↔ 4.1；Boot 3.4 ↔ 4.2；Boot 3.5 ↔ 4.3；Boot 4.0 ↔ 5.0。从 Boot 3.x 入手的读者，实际用的就是 Gateway 4.x 一带的内核。

## 1.7 全文章节地图

- **第二章 路由数据模型（route 包）**：RouteDefinition（配方）与 Route（成品）的字段与不可变设计；RouteLocator/RouteDefinitionLocator 接口体系；RouteDefinitionRouteLocator 如何用 ConfigurationService 把 YAML 参数绑成配置对象。
- **第三章 一次请求的一生（handler/filter 包，全书核心）**：RoutePredicateHandlerMapping 怎么找路由、FilteringWebHandler 怎么合并全局与路由过滤器、DefaultGatewayFilterChain 怎么推进、16 个 GlobalFilter 按 order 走一遍（重点：ReactiveLoadBalancerClientFilter 与 NettyRoutingFilter）。
- **第四章 断言工厂大全（handler/predicate）**：15 个内置断言逐个解析，重点 Path/Weight/ReadBody/Version。
- **第五章 过滤器工厂大全（filter/factory）**：40+ 内置过滤器按主题分组，重点 Retry/CircuitBreaker/RequestRateLimiter/ModifyBody。
- **第六章 动态路由**：路由定义的来源、RouteDefinitionRepository SPI、RefreshRoutesEvent 刷新链、/actuator/gateway 运维端点。
- **第七章 自动配置与扩展点**：13 个自动配置、组件开关体系、HttpClientFactory、可观测性、五种自定义姿势。
- **第八章 双栈与生态**：Server WebMVC（Servlet 栈网关）、ProxyExchange、sample 与集成测试。
- **第九章 贯通视图**：应用启动、一条路由的一生、一次 HTTP 请求三条时间线叠加成全景图。
- **第十章 附录**：接口速查表、学习路线、30 个关键文件清单。


---

# 二、路由数据模型：Route 与 RouteDefinition（route 包）

> 本章对应源码：`src/main/java/org/springframework/cloud/gateway/route/`（主模块）。以下所有【源码证据】中的行号均为实际读取 5.1.0-SNAPSHOT 源码所得。

## 2.1 先说白话：一条路由就是"配方 + 成品"

网关最核心的数据就两样东西：

- **RouteDefinition（配方）**：配置文件/配置中心/Actuator API 里写的那份"路由说明"——id、一组断言名、一组过滤器名、目标 URI、顺序、元数据。它是**可变、可刷新、可序列化**的。
- **Route（成品）**：运行时真正参与请求匹配的对象——断言已被编译成 `AsyncPredicate`，过滤器已被实例化成 `GatewayFilter` 列表。它是**不可变**的（字段全 final，构造私有），想换内容只能整体重建。

为什么这么分？因为**断言和过滤器的"实例化"是有成本的**（解析 PathPattern、编译正则、构造 HttpClient 回调对象），请求路径上绝不能做这些事；而"配置"又是随时可能变的（配置中心推一条新路由）。于是把"变得慢但需要变"的翻译工作放在配置变更时做（RouteDefinitionRouteLocator），把"每请求都要跑"的匹配工作放在成品上做（Route + AsyncPredicate）。这个"**配方→成品→缓存→事件失效**"的四步循环，是理解 Gateway 所有动态行为的钥匙。

## 2.2 Route：运行时不可变对象

【源码证据】`src/main/java/org/springframework/cloud/gateway/route/Route.java` 第 46-72 行——五个字段全 final、构造私有、只能走 Builder：

```java
public class Route implements Ordered {

	private final String id;

	private final URI uri;

	private final int order;

	private final AsyncPredicate<ServerWebExchange> predicate;

	private final List<GatewayFilter> gatewayFilters;

	private final Map<String, Object> metadata;

	private Route(String id, URI uri, int order, AsyncPredicate<ServerWebExchange> predicate,
			List<GatewayFilter> gatewayFilters, Map<String, Object> metadata) {
		...
	}

	public static Builder builder() {
		return new Builder();
	}
```

几个值得注意的设计细节：

- **断言是 `AsyncPredicate` 而不是 `Predicate`**（`src/main/java/org/springframework/cloud/gateway/handler/AsyncPredicate.java:34`，`interface AsyncPredicate<T> extends Function<T, Publisher<Boolean>>, HasConfig`）。因为有的断言需要读请求体（ReadBody），那是 IO 操作，只能异步。`and/or/negate` 组合子用 `flatMap` 实现惰性短路：`AndAsyncPredicate.apply`（AsyncPredicate.java:122-124）在左边为 false 时根本不求值右边。
- **`Route implements Ordered`**，路由自身有 order，`CachingRouteLocator` 缓存时按它排序（2.6 节）。
- **Builder 的 `uri()` 做防线**（Route.java:199-215）：scheme 不能为空、不能是 `localhost`，`http(s)` 无端口时补默认 80/443——把配置错误的失败时机提前到启动/刷新时。
- **`build()` 三非空校验**（Route.java:253-260）：`id`、`uri`、`predicate` 缺一不可。

## 2.3 RouteDefinition 与 PredicateDefinition / FilterDefinition：配置态的载体

【源码证据】`src/main/java/org/springframework/cloud/gateway/route/RouteDefinition.java` 第 40-58 行——字段就是 YAML 的映射目标：

```java
@Validated
public class RouteDefinition {

	private @Nullable String id;

	@NotEmpty
	@Valid
	private List<PredicateDefinition> predicates = new ArrayList<>();

	@Valid
	private List<FilterDefinition> filters = new ArrayList<>();

	private @Nullable URI uri;

	private Map<String, Object> metadata = new HashMap<>();

	private int order = 0;

	private boolean enabled = true;
```

注意两个易被忽略的点：

- **`enabled` 字段**（2023-08 引入，v4.2.0 首发）：为 false 的路由在 `PropertiesRouteDefinitionLocator` 里直接被过滤掉（`src/main/java/org/springframework/cloud/gateway/config/PropertiesRouteDefinitionLocator.java:37`），这是"注释掉一条路由"的正规姿势。
- **`PredicateDefinition` / `FilterDefinition` 支持 shortcut 字符串**。`src/main/java/org/springframework/cloud/gateway/handler/predicate/PredicateDefinition.java` 第 44-57 行的构造器把 `Path=/demo/**` 拆成 name=`Path`、args=`{_genkey_0: "/demo/**"}`：

```java
	public PredicateDefinition(String text) {
		int eqIdx = text.indexOf('=');
		if (eqIdx <= 0) {
			throw new ValidationException(
					"Unable to parse PredicateDefinition text '" + text + "'" + ", must be of the form name=value");
		}
		setName(text.substring(0, eqIdx));

		String[] args = tokenizeToStringArray(text.substring(eqIdx + 1), ",");

		for (int i = 0; i < args.length; i++) {
			this.args.put(NameUtils.generateName(i), args[i]);
		}
	}
```

`_genkey_0` 这种占位键后面会被 shortcut 机制映射回真实字段名（2.5.1 节）。FilterDefinition（`src/main/java/org/springframework/cloud/gateway/filter/FilterDefinition.java:43-56`）同构，差别是允许无参过滤器整串当 name（如 `StripPrefix`）。

## 2.4 两个定位器接口：RouteLocator 与 RouteDefinitionLocator

【源码证据】两个顶层接口，一"成品"一"配方"（`src/main/java/org/springframework/cloud/gateway/route/`）：

```java
// RouteDefinitionLocator.java:24-28
public interface RouteDefinitionLocator {

	Flux<RouteDefinition> getRouteDefinitions();

}
```

```java
// RouteLocator.java:29-38
public interface RouteLocator {

	Flux<Route> getRoutes();
```

`RouteLocator` 在 5.x 还加了按 metadata 过滤的 default 方法 `getRoutesByMetadata`（RouteLocator.java:42-49），支撑"按元数据作用域刷新"。实现侧的家族关系：

```
RouteDefinitionLocator（配方侧，Flux<RouteDefinition>）
├── PropertiesRouteDefinitionLocator     # YAML：spring.cloud.gateway.server.webflux.routes[n]
├── RouteDefinitionRepository（读+写 SPI，动态路由存储）
│    ├── InMemoryRouteDefinitionRepository（默认，Actuator 写的就是它）
│    └── RedisRouteDefinitionRepository（可选）
├── DiscoveryClientRouteDefinitionLocator  # 服务发现自动路由
└── CompositeRouteDefinitionLocator        # 聚合以上全部（@Primary）

RouteLocator（成品侧，Flux<Route>）
├── RouteDefinitionRouteLocator            # 配方 → 成品（本章核心）
├── 用户 Java DSL 产生的 RouteLocator（RouteLocatorBuilder）
└── CompositeRouteLocator → CachingRouteLocator（@Primary，缓存 + 事件刷新）
```

**为什么配方侧和成品侧各有一个 Composite？** 因为自动配置要同时接纳任意数量的"配方来源"（YAML、注册中心、用户自定义 Repository、Java DSL）——用户只要往容器里放一个 `RouteLocator` 或 `RouteDefinitionLocator` bean，就会被聚合进来（第七章 7.1 的 `cachedCompositeRouteLocator` bean 证据）。

## 2.5 RouteDefinitionRouteLocator：从配方到成品（本章核心）

【源码证据】`src/main/java/org/springframework/cloud/gateway/route/RouteDefinitionRouteLocator.java` 第 61-79 行——构造器把容器里**全部**断言工厂和过滤器工厂按 `name()` 建索引：

```java
	public RouteDefinitionRouteLocator(RouteDefinitionLocator routeDefinitionLocator,
			List<RoutePredicateFactory> predicates, List<GatewayFilterFactory> gatewayFilterFactories,
			GatewayProperties gatewayProperties, ConfigurationService configurationService) {
		this.routeDefinitionLocator = routeDefinitionLocator;
		this.configurationService = configurationService;
		initFactories(predicates);
		gatewayFilterFactories.forEach(factory -> this.gatewayFilterFactories.put(factory.name(), factory));
		this.gatewayProperties = gatewayProperties;
	}
```

`factory.name()` 的规则在 `support/NameUtils.java:39-63`：类名去掉 `RoutePredicateFactory` / `GatewayFilterFactory` 后缀——`PathRoutePredicateFactory` → `Path`，`RewritePathGatewayFilterFactory` → `RewritePath`。**YAML 里写的断言/过滤器名，就是靠这个规则对上类的。**

### 2.5.1 参数怎么变成配置对象：ConfigurationService 与 shortcut 机制

翻译一条路由分两步：先"参数归一"，再"绑定成配置类"。

**第一步：shortcut 归一**。`support/ShortcutConfigurable.java` 定义了三种 shortcut 形态（`ShortcutType` 枚举，第 102-181 行）：

| ShortcutType | 行为 | 典型用户 |
|---|---|---|
| `DEFAULT` | `_genkey_n` 占位键按位替换为 `shortcutFieldOrder()` 里的字段名 | `RewritePath=/red/$\segment,/$\{segment}` |
| `GATHER_LIST` | 全部参数收进唯一的 List 字段 | `Method=GET,POST` |
| `GATHER_LIST_TAIL_FLAG` | 参数收进 List，末尾若是 true/false 剥成第二个 boolean 字段 | `Path=/a/**,/b/**,false` |

占位键替换的实现在 `ShortcutConfigurable.normalizeKey`（第 60-67 行）：`key.startsWith(NameUtils.GENERATED_NAME_PREFIX)` 且 `shortcutFieldOrder()` 有对应位置，就用第 entryIdx 个字段名替换。此外每个参数值还支持 `#{...}` SpEL 表达式（`getValue`，第 69-84 行，beanFactory 里解析引用）。

**第二步：绑定成配置类**。`support/ConfigurationService.java` 把 args Map 包成 Spring Boot 的 `MapConfigurationPropertySource`，用 `Binder.bindOrCreate` 绑定 + JSR-303 校验（第 89-103 行 `bindOrCreate`，注释自述"see ConfigurationPropertiesBinder from spring boot for this definition"）。绑定成功还能发布事件（`FilterArgsEvent` / `PredicateArgsEvent`），`WeightCalculatorWebFilter` 就靠监听它收集权重配置（4.3 节）。

### 2.5.2 断言的合并与过滤器的装配

【源码证据】`RouteDefinitionRouteLocator.java` 第 106-136 行——转换主流程：

```java
	private Flux<Route> getRoutes(Flux<RouteDefinition> routeDefinitions) {
		Flux<Route> routes = routeDefinitions.map(this::convertToRoute);

		if (!gatewayProperties.isFailOnRouteDefinitionError()) {
			// instead of letting error bubble up, continue
			routes = routes.onErrorContinue((error, obj) -> {
				if (logger.isWarnEnabled()) {
					logger.warn("RouteDefinition id " + ((RouteDefinition) obj).getId()
							+ " will be ignored. Definition has invalid configs, " + error.getMessage());
				}
			});
		}

		return routes.map(route -> {
			if (logger.isDebugEnabled()) {
				logger.debug("RouteDefinition matched: " + route.getId());
			}
			return route;
		});
	}

	private Route convertToRoute(RouteDefinition routeDefinition) {
		AsyncPredicate<ServerWebExchange> predicate = combinePredicates(routeDefinition);
		List<GatewayFilter> gatewayFilters = getFilters(routeDefinition);

		return Route.async(routeDefinition).asyncPredicate(predicate).replaceFilters(gatewayFilters).build();
	}
```

一条路由有多个断言时，用 `AsyncPredicate::and` 归约成一条（`combinePredicates`，第 199-211 行）；一条都没有时退化为"全部匹配"（`AsyncPredicate.from(exchange -> true)`）。

过滤器侧的 `loadGatewayFilters`（第 139-179 行）是"配方→实例"的现场直播，值得逐行读：

```java
	List<GatewayFilter> loadGatewayFilters(String id, List<FilterDefinition> filterDefinitions) {
		ArrayList<GatewayFilter> ordered = new ArrayList<>(filterDefinitions.size());
		for (int i = 0; i < filterDefinitions.size(); i++) {
			FilterDefinition definition = filterDefinitions.get(i);
			GatewayFilterFactory factory = this.gatewayFilterFactories.get(definition.getName());
			if (factory == null) {
				throw new IllegalArgumentException(
						"Unable to find GatewayFilterFactory with name " + definition.getName());
			}
			...
			// @formatter:off
			Object configuration = this.configurationService.with(factory)
					.name(definition.getName())
					.properties(definition.getArgs())
					.eventFunction((bound, properties) -> new FilterArgsEvent(
							// TODO: why explicit cast needed or java compile fails
							RouteDefinitionRouteLocator.this, id, (Map<String, Object>) properties))
					.bind();
			// @formatter:on

			// some filters require routeId
			// TODO: is there a better place to apply this?
			if (configuration instanceof HasRouteId hasRouteId) {
				hasRouteId.setRouteId(id);
			}

			GatewayFilter gatewayFilter = factory.apply(configuration);
			if (gatewayFilter instanceof Ordered) {
				ordered.add(gatewayFilter);
			}
			else {
				ordered.add(new OrderedGatewayFilter(gatewayFilter, i + 1));
			}
		}

		return ordered;
	}
```

三个关键点：① 配置类实现了 `HasRouteId` 时自动回填 routeId（Retry/CircuitBreaker 依赖它开 body 缓存）；② **未显式实现 Ordered 的过滤器，默认 order = 在该路由 filters 列表中的位置 i+1**——这就是"YAML 里先写的过滤器先执行"的来源；③ 找不到工厂名直接抛异常，让坏配置在刷新时立刻暴露（或被 `failOnRouteDefinitionError=false` 降级为跳过）。

`getFilters`（第 181-197 行）还保证 **default-filters 先于路由自己的 filters 被加载**，最后统一 `AnnotationAwareOrderComparator.sort`。

## 2.6 聚合与缓存：CompositeRouteLocator 与 CachingRouteLocator

【源码证据】`src/main/java/org/springframework/cloud/gateway/route/CachingRouteLocator.java` 第 44-106 行——缓存 + 刷新事件的落点：

```java
public class CachingRouteLocator
		implements Ordered, RouteLocator, ApplicationListener<RefreshRoutesEvent>, ApplicationEventPublisherAware {

	private static final String CACHE_KEY = "routes";

	private final RouteLocator delegate;

	private final Flux<Route> routes;

	private final Map<String, List> cache = new ConcurrentHashMap<>();

	public CachingRouteLocator(RouteLocator delegate) {
		this.delegate = delegate;
		routes = CacheFlux.lookup(cache, CACHE_KEY, Route.class).onCacheMissResume(this::fetch);
	}
```

```java
	@Override
	public void onApplicationEvent(RefreshRoutesEvent event) {
		try {
			if (this.cache.containsKey(CACHE_KEY) && event.isScoped()) {
				final Mono<List<Route>> scopedRoutes = fetch(event.getMetadata()).collect(Collectors.toList())
					.onErrorResume(s -> Mono.just(List.of()));

				scopedRoutes.subscribe(scopedRoutesList -> {
					updateCache(Flux.concat(Flux.fromIterable(scopedRoutesList), getNonScopedRoutes(event))
						.sort(AnnotationAwareOrderComparator.INSTANCE));
				}, this::handleRefreshError);
			}
			else {
				final Mono<List<Route>> allRoutes = fetch().collect(Collectors.toList());
				allRoutes.subscribe(list -> updateCache(Flux.fromIterable(list)), this::handleRefreshError);
			}
		}
		catch (Throwable e) {
			handleRefreshError(e);
		}
	}
```

读法：`getRoutes()` 返回的是一条**常驻的 Flux**（`CacheFlux.lookup` 缓存 miss 时才调 `fetch()` 拉全量并按 order 排序）；收到 `RefreshRoutesEvent` 后整体重拉、重排、原子替换缓存内容，成功后发 `RefreshRoutesResultEvent`（CachingRouteLocator.java:108-131），失败也发（携带 Throwable），下游（如 CORS 监听器 `filter/cors/CorsGatewayFilterApplicationListener.java:66`）据此跟进。5.x 还支持**带 metadata 的 scoped 刷新**——只重取命中的那部分路由再与非 scoped 路由合并，减少大配置下的刷新开销。

## 2.7 服务发现自动路由：DiscoveryClientRouteDefinitionLocator

打开 `spring.cloud.gateway.server.webflux.discovery.locator.enabled=true` 后，注册中心里的每个服务都会自动获得一条路由。实现是"**模板 + SpEL**"：

- 每个服务实例按模板生成一条 RouteDefinition：断言模板 `Path='/' + serviceId + '/**'`、过滤器模板 `RewritePath='/' + serviceId + '/?(?<remaining>.*)' → '/${remaining}'`、URI 模板 `'lb://'+serviceId`——这三份默认模板不在 Locator 类里，而在自动配置 `discovery/GatewayDiscoveryClientAutoConfiguration.java` 第 55-79 行的 `initPredicates()/initFilters()` 里；
- Locator 本体（`discovery/DiscoveryClientRouteDefinitionLocator.java:82-156`）用 `SimpleEvaluationContext` 对每个 `ServiceInstance` 求值模板表达式（`getValueFromExpr`），并把实例 metadata 透传进路由 metadata（`buildRouteDefinition`，第 145-156 行）：

```java
	protected RouteDefinition buildRouteDefinition(Expression urlExpr, ServiceInstance serviceInstance) {
		String serviceId = serviceInstance.getServiceId();
		RouteDefinition routeDefinition = new RouteDefinition();
		routeDefinition.setId(this.routeIdPrefix + serviceId);
		String uri = urlExpr.getValue(this.evalCtxt, serviceInstance, String.class);
		if (uri != null) {
			routeDefinition.setUri(URI.create(uri));
		}
		// add instance metadata
		routeDefinition.setMetadata(new LinkedHashMap<>(serviceInstance.getMetadata()));
		return routeDefinition;
	}
```

所以"自动路由"的本质仍是普通 RouteDefinition——它也要经过 2.5 节的翻译管线，`lb://` 也要等第三章的负载均衡过滤器换成真实地址。

## 2.8 Java DSL：RouteLocatorBuilder

不想写 YAML 时可以用 Java DSL 定义路由（`route/builder/RouteLocatorBuilder.java`）。Builder 链是四段式：`route(id)` → `PredicateSpec`（断言，`path()/host()/...`，内部直接 `getBean(PathRoutePredicateFactory.class).applyAsync(...)`，PredicateSpec.java:179-195）→ `GatewayFilterSpec`（filters，能拿到 URI 模板变量）→ `uri()` 收尾。sample 工程的示范（`spring-cloud-gateway-sample/src/main/java/org/springframework/cloud/gateway/sample/GatewaySampleApplication.java:57-68`）：

```java
	@Bean
	public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
		return builder.routes()
				.route(r -> r.host("**.abc.org").and().path("/anything/png")
					.filters(f ->
							f.prefixPath("/httpbin")
									.addResponseHeader("X-TestHeader", "foobar"))
					.uri(uri)
				)
```

DSL 产出的 RouteLocator 与 YAML 来源地位平等，一并被 `cachedCompositeRouteLocator` 聚合缓存。

## 2.9 本章小结

- **两层模型**：RouteDefinition（配方，可变可刷新）与 Route（成品，不可变），中间隔着一个 `RouteDefinitionRouteLocator` 翻译器。
- **翻译器的两个引擎**：`ShortcutType` 三态归一（`_genkey_n` → shortcutFieldOrder 字段名）+ `ConfigurationService`（Boot Binder 绑定 + 校验 + 事件）。
- **多路由断言用 `AsyncPredicate.and` 归约**；无 order 的路由级过滤器默认按"配置位置 i+1"排序。
- **缓存与刷新**：`CachingRouteLocator` 用常驻 Flux + `CacheFlux` 缓存成品；`RefreshRoutesEvent` 触发全量（或 scoped）重译，成功/失败都发结果事件。
- **服务发现自动路由只是"模板化的 RouteDefinition"**，没有特殊通道。
- 一句话记住本章：**配置层的任何变化都只发生在"重译成品 + 原子换缓存"这一个动作里，请求路径上的匹配永远是读缓存。**


# 三、一次请求的一生：分发与过滤链（本章核心）

> 本章对应源码：`src/main/java/org/springframework/cloud/gateway/handler/`、`.../filter/`、`.../support/ServerWebExchangeUtils.java`。这是全书最重要的一章。

## 3.1 先说白话：网关是"一个 HandlerMapping + 一条过滤器链"

回忆 Spring Framework.md 第六章：WebFlux 的 `DispatcherHandler` 有"三大件"——HandlerMapping（找谁处理）、HandlerAdapter（怎么调用）、HandlerResultHandler（结果怎么写回）。Gateway 没有另起炉灶，它就是 WebFlux 的一个普通"应用"：

- **HandlerMapping 位置上坐的是 `RoutePredicateHandlerMapping`**（order=1）：它不用 `@RequestMapping` 匹配，而是拿**路由断言**逐条试探，匹配成功就把 handler 设为 `FilteringWebHandler`；
- **Controller 位置上坐的是 `FilteringWebHandler`**：它不执行业务方法，而是把"全局过滤器 + 该路由的路由级过滤器"合并成一条责任链跑完；
- 链的最末端，`NettyRoutingFilter` 用 Reactor Netty 把请求真正发给下游。

**"转发下游"在这里不是什么特殊机制，就是链上 order 最大的那个过滤器干的活**。理解了这一点，Gateway 的一半源码就读懂了。

## 3.2 RoutePredicateHandlerMapping：用断言找路由

【源码证据】`src/main/java/org/springframework/cloud/gateway/handler/RoutePredicateHandlerMapping.java` 第 45、55-64 行——继承 WebFlux 的 `AbstractHandlerMapping`，默认 order=1（可用 `spring.cloud.gateway.server.webflux.handler-mapping.order` 覆盖）：

```java
public class RoutePredicateHandlerMapping extends AbstractHandlerMapping {
	...
	public RoutePredicateHandlerMapping(FilteringWebHandler webHandler, RouteLocator routeLocator,
			GlobalCorsProperties globalCorsProperties, Environment environment) {
		this.webHandler = webHandler;
		this.routeLocator = routeLocator;

		this.managementPort = getPortProperty(environment, "management.server.");
		this.managementPortType = getManagementPortType(environment);
		setOrder(environment.getProperty(GatewayProperties.PREFIX + ".handler-mapping.order", Integer.class, 1));
		setCorsConfigurations(globalCorsProperties.getCorsConfigurations());
	}
```

匹配入口 `getHandlerInternal()`（第 80-110 行）——注意它重写的是 protected 方法，外层模板（CORS、management 端口分流）在父类里：

```java
	@Override
	protected Mono<?> getHandlerInternal(ServerWebExchange exchange) {
		// don't handle requests on management port if set and different than server port
		if (this.managementPortType == DIFFERENT && this.managementPort != null
				&& exchange.getRequest().getLocalAddress() != null
				&& exchange.getRequest().getLocalAddress().getPort() == this.managementPort) {
			return Mono.empty();
		}
		exchange.getAttributes().put(GATEWAY_HANDLER_MAPPER_ATTR, getSimpleName());

		return Mono.deferContextual(contextView -> {
			exchange.getAttributes().put(GATEWAY_REACTOR_CONTEXT_ATTR, contextView);
			return lookupRoute(exchange)
				.map((Function<Route, ?>) r -> {
					exchange.getAttributes().remove(GATEWAY_PREDICATE_ROUTE_ATTR);
					if (logger.isDebugEnabled()) {
						logger.debug("Mapping [" + getExchangeDesc(exchange) + "] to " + r);
					}

					exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, r);
					return webHandler;
				})
```

```java
				.switchIfEmpty(Mono.empty().then(Mono.fromRunnable(() -> {
					exchange.getAttributes().remove(GATEWAY_PREDICATE_ROUTE_ATTR);
					ServerWebExchangeUtils.clearCachedRequestBody(exchange);
					if (logger.isTraceEnabled()) {
						logger.trace("No RouteDefinition found for [" + getExchangeDesc(exchange) + "]");
					}
				})));
		});
	}
```

匹配的核心 `lookupRoute()`（第 131-157 行）：

```java
	protected Mono<Route> lookupRoute(ServerWebExchange exchange) {
		return this.routeLocator.getRoutes().filterWhen(route -> {
			// add the current route we are testing
			exchange.getAttributes().put(GATEWAY_PREDICATE_ROUTE_ATTR, route.getId());
			try {
				return route.getPredicate().apply(exchange);
			}
			catch (Exception e) {
				logger.error("Error applying predicate for route: " + route.getId(), e);
			}
			return Mono.just(false);
		})
			.next()
			// TODO: error handling
			.map(route -> {
				if (logger.isDebugEnabled()) {
					logger.debug("Route matched: " + route.getId());
				}
				validateRoute(route, exchange);
				return route;
			});
```

四个必须记住的行为：

1. **顺序匹配、取第一条命中**：路由按 order 排好后逐条 `filterWhen`，`.next()` 拿第一个 true 就停——**路由 order 越小优先级越高**。
2. **同步异常被吞成"不匹配"**：`try/catch` 包住 `route.getPredicate().apply(exchange)`，断言同步抛错只打 error 日志、当作 false 继续；但断言内部**异步**错误（Mono 信号里的 error）不会被这里捕获，会沿 Reactive 链上抛（导致 500）。
3. **没有路由命中不抛 NotFoundException**：返回空 Mono，`getHandlerInternal` 返回 empty，404 由 DispatcherHandler 的框架默认行为产生。`NotFoundException`（默认 503，可切 404，`support/NotFoundException.java:27-51`）是**负载均衡阶段**抛的（3.6.4 节）——"找路由"和"找实例"是两个阶段。
4. **试探时写 `GATEWAY_PREDICATE_ROUTE_ATTR`**：每测一条路由就覆盖一次"当前候选路由 id"，Weight 断言依赖它判断"我是不是被选中的那条"（4.3 节）。

## 3.3 FilteringWebHandler：两种过滤器的合流

【源码证据】`src/main/java/org/springframework/cloud/gateway/handler/FilteringWebHandler.java` 第 55-125 行——实现 `WebHandler`（被 Mapping 当 handler 返回），并监听刷新事件失效过滤器缓存：

```java
public class FilteringWebHandler implements WebHandler, ApplicationListener<RefreshRoutesEvent> {

	private final List<GatewayFilter> globalFilters;

	private final ConcurrentHashMap<Route, List<GatewayFilter>> routeFilterMap = new ConcurrentHashMap();

	private final boolean routeFilterCacheEnabled;
```

GlobalFilter 装进链之前先被适配和排序（`loadFilters`，第 74-89 行）：

```java
	private static List<GatewayFilter> loadFilters(List<GlobalFilter> filters) {
		return filters.stream().map(filter -> {
			GatewayFilterAdapter gatewayFilter = new GatewayFilterAdapter(filter);
			if (filter instanceof Ordered ordered) {
				int order = ordered.getOrder();
				return new OrderedGatewayFilter(gatewayFilter, order);
			}
			else {
				Order order = AnnotationUtils.findAnnotation(filter.getClass(), Order.class);
				if (order != null) {
					return new OrderedGatewayFilter(gatewayFilter, order.value());
				}
			}
			return gatewayFilter;
		}).collect(Collectors.toList());
	}
```

`GatewayFilterAdapter`（第 163-189 行）是全局过滤器与路由过滤器之间的"翻译官"：`GlobalFilter` 与 `GatewayFilter` 接口方法签名完全相同（都是 `filter(exchange, chain)`），GlobalFilter 只是多一个"匹配到路由才执行"的语义（`filter/GlobalFilter.java:28-35` 的 Javadoc：*"Only applies to matched gateway routes"*）——Adapter 把它统一成 `GatewayFilter`，再实现 `DecoratingProxy` 让排序时能看穿包装拿到原始类。

`handle()`（第 99-108 行）与合并逻辑（第 110-125 行）：

```java
	@Override
	public Mono<Void> handle(ServerWebExchange exchange) {
		Route route = exchange.getRequiredAttribute(GATEWAY_ROUTE_ATTR);
		List<GatewayFilter> combined = getCombinedFilters(route);

		if (logger.isDebugEnabled()) {
			logger.debug("Sorted gatewayFilterFactories: " + combined);
		}

		return new DefaultGatewayFilterChain(combined).filter(exchange);
	}
```

```java
	protected List<GatewayFilter> getAllFilters(Route route) {
		List<GatewayFilter> gatewayFilters = route.getFilters();
		List<GatewayFilter> combined = new ArrayList<>(this.globalFilters);
		combined.addAll(gatewayFilters);
		AnnotationAwareOrderComparator.sort(combined);
		return combined;
	}
```

**没有"全局过滤器层"和"路由过滤器层"之分**——两个列表 concat 后统一按 order 排序，一条链跑完。`routeFilterCacheEnabled=true`（`spring.cloud.gateway.server.webflux.route-filter-cache-enabled`，默认 false）时按 Route 缓存合并结果，刷新事件到来时 `onApplicationEvent`（第 91-96 行）清缓存。

## 3.4 DefaultGatewayFilterChain：响应式责任链

【源码证据】FilteringWebHandler.java 第 127-161 行——不可变的"索引推进"式责任链：

```java
	private static class DefaultGatewayFilterChain implements GatewayFilterChain {

		private final int index;

		private final List<GatewayFilter> filters;

		@Override
		public Mono<Void> filter(ServerWebExchange exchange) {
			return Mono.defer(() -> {
				if (this.index < filters.size()) {
					GatewayFilter filter = filters.get(this.index);
					DefaultGatewayFilterChain chain = new DefaultGatewayFilterChain(this, this.index + 1);
					return filter.filter(exchange, chain);
				}
				else {
					return Mono.empty(); // complete
				}
			});
		}

	}
```

与 Servlet 栈的 `FilterChain` 用"当前指针"不同，这里每调用一次 `filter()` 就 new 一个 index+1 的新链对象传给下游——因为 Reactive 调用是"订阅才执行"的，同一时刻可能有多个订阅点在不同 index 上，**链对象必须不可变**。整条链就是 `Mono.defer` 的层层嵌套：每个过滤器返回的 `Mono<Void>` 里既包含"转发前"逻辑，也通过 `chain.filter(exchange).then(...)` 包含"转发后"逻辑。走完末尾返回 `Mono.empty()` 表示"没有更多过滤器了"——**如果没有任何过滤器真正"路由"请求，链走到头等于什么都没发生**，响应由框架补 404/连接关闭（这就是"必须有个过滤器调用 setAlreadyRouted 并出网"的原因）。

## 3.5 ServerWebExchange 属性表：网关的请求作用域黑板

【源码证据】`src/main/java/org/springframework/cloud/gateway/support/ServerWebExchangeUtils.java` 第 72-192 行（常量名经 `qualify()` 加 `org.springframework.cloud.gateway.support.ServerWebExchangeUtils.` 全限定前缀）。这张表是读过滤器源码时的"地图"：

| 常量（行号） | 属性名 | 谁写 / 谁读 |
|---|---|---|
| `PRESERVE_HOST_HEADER_ATTRIBUTE`（72） | preserveHostHeader | PreserveHost/SetRequestHostHeader 写 → NettyRoutingFilter 读 |
| `URI_TEMPLATE_VARIABLES_ATTRIBUTE`（77） | uriTemplateVariables | Path/Host 断言写 → SetPath/PrefixPath 等 filter 读 |
| `CLIENT_RESPONSE_ATTR`（82） | gatewayClientResponse | NettyRoutingFilter 写（上游响应）→ NettyWriteResponseFilter 读 |
| `CLIENT_RESPONSE_CONN_ATTR`（87） | gatewayClientResponseConnection | NettyRoutingFilter 写（上游连接）→ 写回/reset 读 |
| `CLIENT_RESPONSE_HEADER_NAMES`（92） | gatewayClientResponseHeaderNames | NettyRoutingFilter 写 |
| `GATEWAY_ROUTE_ATTR`（97） | gatewayRoute | HandlerMapping 写 → 全链读 |
| `GATEWAY_REACTOR_CONTEXT_ATTR`（102） | gatewayReactorContext | HandlerMapping 写 → Observation 头过滤读 |
| `GATEWAY_REQUEST_URL_ATTR`（107） | gatewayRequestUrl | RouteToRequestUrl/重写类过滤器写 → 出网过滤器读 |
| `GATEWAY_ORIGINAL_REQUEST_URL_ATTR`（112） | gatewayOriginalRequestUrl | 每次改 URL 前追加 |
| `GATEWAY_HANDLER_MAPPER_ATTR`（117） | gatewayHandlerMapper | HandlerMapping 写 |
| `GATEWAY_SCHEME_PREFIX_ATTR`（122） | gatewaySchemePrefix | RouteToRequestUrl 写（`lb:xx` 双 scheme）|
| `GATEWAY_PREDICATE_ROUTE_ATTR`（127） | gatewayPredicateRouteAttr | HandlerMapping 试探时写 → Weight/Version 读 |
| `GATEWAY_PREDICATE_MATCHED_PATH_ATTR`（132） | gatewayPredicateMatchedPathAttr | Path 断言写（metrics tags 用）|
| `GATEWAY_PREDICATE_PATH_CONTAINER_ATTR`（143） | gatewayPredicatePathContainer | Path 断言缓存解析结果 |
| `WEIGHT_ATTR`（148） | routeWeight | WeightCalculatorWebFilter 写 → Weight 断言读 |
| `ORIGINAL_RESPONSE_CONTENT_TYPE_ATTR`（153） | original_response_content_type | NettyRoutingFilter 写 → ModifyResponseBody 读 |
| `CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR`（158） | circuitBreakerExecutionException | CircuitBreaker 写 → FallbackHeaders 读 |
| `GATEWAY_ALREADY_ROUTED_ATTR`（164） | gatewayAlreadyRouted | 各路由过滤器互斥标记 |
| `GATEWAY_ALREADY_PREFIXED_ATTR`（169） | gatewayAlreadyPrefixed | PrefixPath 防重复加前缀 |
| `CACHED_SERVER_HTTP_REQUEST_DECORATOR_ATTR`（175） | cachedServerHttpRequestDecorator | 断言读体后留装饰器 → AdaptCachedBody 取用 |
| `CACHED_REQUEST_BODY_ATTR`（182） | cachedRequestBody | 读体/缓存体过滤器写 → Retry/ModifyBody 复用 |
| `GATEWAY_LOADBALANCER_RESPONSE_ATTR`（187） | gatewayLoadBalancerResponse | LB 过滤器写 → Cookie 过滤器/LB 生命周期读 |
| `GATEWAY_OBSERVATION_ATTR`（192） | gateway.observation | Observation 请求/响应头过滤传递 |

配套工具方法：`setAlreadyRouted/isAlreadyRouted`（第 204-214 行，**网关的"互斥锁"——一个请求只允许被路由一次**，Netty/WebSocket/Forward/Stream/Function 各路由过滤器都先查它）、`reset`（第 224-240 行，dispose 上游连接 + 清响应头 + 移除 already-routed，重试/fallback 前调用）、`cacheRequestBody`（第 393-411 行，join 请求体并装饰 request）、`addOriginalRequestUrl`（第 306-310 行）。

## 3.6 按 order 走一遍：内置 GlobalFilter 全景

把主模块注册的全部 GlobalFilter 按 order 排成一张总表（order 实际数值均已 grep 核对），一次 `lb://` + http 的请求恰好从上到下穿过它们：

| GlobalFilter | order | 职责（白话） | 注册点 |
|---|---|---|---|
| RemoveCachedBodyFilter | `HIGHEST_PRECEDENCE`（-2147483648） | 链结束（无论成败）释放缓存请求体 | GatewayAutoConfiguration:421 |
| AdaptCachedBodyGlobalFilter | `HIGHEST+1000`（-2147482648） | 为声明需要读体的路由缓存并装饰请求体 | 同上 :415 |
| GlobalLocalResponseCacheGatewayFilter | -3 | 未配路由级 LocalResponseCache 的兜底响应缓存 | LocalResponseCacheAutoConfiguration:59 |
| NettyWriteResponseFilter | -1 | "then" 阶段把上游响应写回客户端 | NettyConfiguration:906 |
| GatewayMetricsFilter | 0（`WRITE_RESPONSE_FILTER_ORDER+1`） | 请求计时指标 | GatewayMetricsAutoConfiguration:87 |
| ForwardPathFilter | 0 | `forward:` 路由改写请求路径 | GatewayAutoConfiguration:439 |
| RouteToRequestUrlFilter | 10000 | 合并路由 URI 与原始请求 → GATEWAY_REQUEST_URL_ATTR | 同上 :427 |
| StreamRoutingFilter / FunctionRoutingFilter | 10010 | `stream:` / `fn:` scheme 路由 | GatewayStream/FunctionAutoConfiguration |
| ReactiveLoadBalancerClientFilter | 10150 | `lb://` 选实例、重写 URL | GatewayReactiveLoadBalancerClientAutoConfiguration:45 |
| LoadBalancerServiceInstanceCookieFilter | 10151 | 粘性会话：实例 id 写进 Cookie 头 | 同上 :52 |
| WebsocketRoutingFilter | `LOWEST-1`（2147483646） | ws/wss 双向转发 | NettyConfiguration:913 |
| NettyRoutingFilter | `LOWEST`（2147483647） | http/https 真正出网 | NettyConfiguration:899 |
| ForwardRoutingFilter | `LOWEST`（2147483647） | `forward:` 转发到本地 DispatcherHandler | GatewayAutoConfiguration:433 |

（另有 `NoLoadBalancerClientFilter`：未引 LoadBalancer 时的兜底，同 order 10150，遇 `lb://` 直接抛 `NotFoundException`——`config/GatewayNoLoadBalancerClientAutoConfiguration.java:44-82`。`WeightCalculatorWebFilter` 不是链内过滤器，而是 order=10001 的 WebFilter，在进入 DispatcherHandler 之前执行，见 4.3 节。）

下面按请求的实际穿越顺序展开重点过滤器。

### 3.6.1 链首：RemoveCachedBodyFilter 与 AdaptCachedBodyGlobalFilter

【源码证据】`filter/RemoveCachedBodyFilter.java`（全文 37 行）——`chain.filter(exchange).doFinally(s -> ServerWebExchangeUtils.clearCachedRequestBody(exchange))`：无论链成功、失败还是取消，最后都把缓存体释放掉（DataBuffer 要显式 release，这是响应式代码的内存纪律）。

`filter/AdaptCachedBodyGlobalFilter.java`（order=HIGHEST+1000）配合 `EnableBodyCachingEvent` 工作：Retry、CircuitBreaker 等工厂在 apply 时调 `AbstractGatewayFilterFactory.enableBodyCaching(routeId)` 发布事件（`filter/factory/AbstractGatewayFilterFactory.java:49-54`），本过滤器监听后把 routeId 记入 `routesToCache`；请求经过时若该路由声明了要缓存，就调 `ServerWebExchangeUtils.cacheRequestBody` 把请求体读一遍缓存起来，后续 Retry 重发 body、ModifyBody 读 body 都不用再碰流（第 46-72 行）。

### 3.6.2 NettyWriteResponseFilter：先注册、后写回

这是初读最容易困惑的过滤器：order=-1，明明排第二进链，写的却是**最后一步**的响应。

【源码证据】`filter/NettyWriteResponseFilter.java` 第 55、85-139 行：

```java
	public static final int WRITE_RESPONSE_FILTER_ORDER = -1;
	...
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		// NOTICE: nothing in "pre" filter stage as CLIENT_RESPONSE_CONN_ATTR is not added
		// until the NettyRoutingFilter is run
		// @formatter:off
		return chain.filter(exchange)
				.then(Mono.defer(() -> {
					Connection connection = exchange.getAttribute(CLIENT_RESPONSE_CONN_ATTR);

					if (connection == null) {
						return Mono.empty();
					}
					...
					// TODO: needed?
					final Flux<DataBuffer> body = connection
							.inbound()
							.receive()
							.retain()
							.map(byteBuf -> wrap(byteBuf, response));
					...
					Mono<Void> write = (isStreamingMediaType(contentType)
							? response.writeAndFlushWith(body.map(Flux::just))
							: response.writeWith(body));
					return write.then(TrailerHeadersFilter.filter(getHeadersFilters(), exchange, httpClientResponse)).then();
				}))
				.doFinally(signalType -> {
					if (signalType == SignalType.CANCEL || signalType == SignalType.ON_ERROR) {
						cleanup(exchange);
					}
				});
	}
```

源码开头的 NOTICE 注释就是答案：**它执行时（链很靠前）上游连接还不存在，所以"前段"什么都不做，只注册一个 `then` 回调**；等链走到 NettyRoutingFilter、`CLIENT_RESPONSE_CONN_ATTR` 被放入 exchange、且整个后段链完成时，`then` 里的 Mono 才执行——从 Netty 连接收 ByteBuf、`retain()` 后包成 DataBuffer 写回客户端。两个细节：Content-Type 属于 `GatewayProperties.streamingMediaTypes`（默认 text/event-stream、stream+json、grpc 系）时用 `writeAndFlushWith` **逐块刷出**（SSE 必须如此，否则事件会攒着不发）；CANCEL/ON_ERROR 时 `cleanup` dispose 上游连接（第 159-164 行），防止连接泄漏。

### 3.6.3 RouteToRequestUrlFilter：拼出转发 URL

【源码证据】`filter/RouteToRequestUrlFilter.java` 第 63-96 行（order=10000）：

```java
		URI uri = exchange.getRequest().getURI();
		boolean encoded = containsEncodedParts(uri);
		URI routeUri = route.getUri();

		if (hasAnotherScheme(routeUri)) {
			// this is a special url, save scheme to special attribute
			// replace routeUri with schemeSpecificPart
			exchange.getAttributes().put(GATEWAY_SCHEME_PREFIX_ATTR, routeUri.getScheme());
			routeUri = URI.create(routeUri.getSchemeSpecificPart());
		}

		if ("lb".equalsIgnoreCase(routeUri.getScheme()) && routeUri.getHost() == null) {
			// Load balanced URIs should always have a host. If the host is null it is
			// most likely because the host name was invalid (for example included an
			// underscore)
			throw new IllegalStateException("Invalid host: " + routeUri.toString());
		}

		URI mergedUrl = UriComponentsBuilder.fromUri(uri)
			// .uri(routeUri)
			.scheme(routeUri.getScheme())
			.host(routeUri.getHost())
			.port(routeUri.getPort())
			.build(encoded)
			.toUri();
		exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, mergedUrl);
		return chain.filter(exchange);
```

它做的是"**换头不换尾**"：保留客户端原始请求的 path 与 query，只把 scheme/host/port 换成路由目标的——`GATEWAY_REQUEST_URL_ATTR` 从此诞生。三个彩蛋：`hasAnotherScheme`（第 48-55 行）识别 `lb:http://service` 这类**双 scheme 写法**，把前缀 `lb` 存进 `GATEWAY_SCHEME_PREFIX_ATTR`、真正 scheme 交给后半段；`lb://` 无 host（常见于服务名含下划线）在此时就报 `IllegalStateException`；此后所有路径重写类过滤器都只改这个属性、不动原始请求。

### 3.6.4 ReactiveLoadBalancerClientFilter：lb:// 的负载均衡

【源码证据】`filter/ReactiveLoadBalancerClientFilter.java` 第 63、70、87-152 行（order=10150）。判定条件是 URL 的 scheme 或 schemePrefix 为 `lb`：

```java
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		URI url = exchange.getAttribute(GATEWAY_REQUEST_URL_ATTR);
		String schemePrefix = exchange.getAttribute(GATEWAY_SCHEME_PREFIX_ATTR);
		if (url == null || (!"lb".equals(url.getScheme()) && !"lb".equals(schemePrefix))) {
			return chain.filter(exchange);
		}
		// preserve the original url
		addOriginalRequestUrl(exchange, url);
		...
		String serviceId = requestUri.getHost();
```

```java
		DefaultRequest<RequestDataContext> lbRequest = new DefaultRequest<>(new RequestDataContext(
				new RequestData(exchange.getRequest(), exchange.getAttributes()), getHint(serviceId)));
		return choose(lbRequest, serviceId, supportedLifecycleProcessors).doOnNext(response -> {

			if (!response.hasServer()) {
				supportedLifecycleProcessors.forEach(lifecycle -> lifecycle
					.onComplete(new CompletionContext<>(CompletionContext.Status.DISCARD, lbRequest, response)));
				throw NotFoundException.create(properties.isUse404(), "Unable to find instance for " + url.getHost());
			}

			ServiceInstance retrievedInstance = response.getServer();
			...
			DelegatingServiceInstance serviceInstance = new DelegatingServiceInstance(retrievedInstance,
					overrideScheme);

			URI requestUrl = reconstructURI(serviceInstance, uri);
			...
			exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, requestUrl);
			exchange.getAttributes().put(GATEWAY_LOADBALANCER_RESPONSE_ATTR, response);
			supportedLifecycleProcessors.forEach(lifecycle -> lifecycle.onStartRequest(lbRequest, response));
		})
			.then(chain.filter(exchange))
```

读法：serviceId 就是 `lb://` URL 的 host；`choose()`（第 158-167 行）从 `LoadBalancerClientFactory` 按 serviceId 取该服务专属的 `ReactorServiceInstanceLoadBalancer`（轮询/随机等策略由 spring-cloud-loadbalancer 配置）；**没有可用实例抛 `NotFoundException.create(properties.isUse404(), ...)`——默认 503，`spring.cloud.gateway.server.webflux.loadbalancer.use404=true` 时 404**；选中实例后用 `LoadBalancerUriTools.reconstructURI` 把 URL 换成 `http(s)://真实IP:port`，并把 LB 的 `Response` 存进 `GATEWAY_LOADBALANCER_RESPONSE_ATTR`——下游的 `LoadBalancerServiceInstanceCookieFilter`（order 10151）从这里取实例 id 写粘性会话 Cookie，LB 生命周期回调也从这里取完成态做统计。

**架构上的启示**：负载均衡对 Gateway 只是一个 order=10150 的过滤器。想换成自己的注册中心/调度算法，替换或加一个更早 order 的过滤器改写 `GATEWAY_REQUEST_URL_ATTR` 即可——这就是"一切皆过滤器"的红利。

### 3.6.5 NettyRoutingFilter：真正出网（重点）

【源码证据】`filter/NettyRoutingFilter.java` 第 76、81、108-122 行——order 是 `Ordered.LOWEST_PRECEDENCE`（链的最末端，与 ForwardRoutingFilter 并列，靠 scheme 判定谁干活）：

```java
public class NettyRoutingFilter implements GlobalFilter, Ordered {

	public static final int ORDER = Ordered.LOWEST_PRECEDENCE;
	...
	@Override
	public int getOrder() {
		return ORDER;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		URI requestUrl = exchange.getRequiredAttribute(GATEWAY_REQUEST_URL_ATTR);

		String scheme = requestUrl.getScheme();
		if (isAlreadyRouted(exchange) || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
			return chain.filter(exchange);
		}
		setAlreadyRouted(exchange);
```

请求准备与发送（第 129-151 行）——先跑请求侧头过滤（X-Forwarded-* 就在这时加上，见 3.7 节），再用共享 HttpClient 发出：

```java
		HttpHeaders filtered = filterRequest(getHeadersFilters(), exchange);

		final DefaultHttpHeaders httpHeaders = new DefaultHttpHeaders();
		filtered.forEach(httpHeaders::set);

		boolean preserveHost = exchange.getAttributeOrDefault(PRESERVE_HOST_HEADER_ATTRIBUTE, false);
		Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
		Objects.requireNonNull(route, "route must not be null");
		Flux<HttpClientResponse> responseFlux = getHttpClientMono(route, exchange)
			.flatMapMany(httpClient -> httpClient.headers(headers -> {
				headers.add(httpHeaders);
				// Will either be set below, or later by Netty
				headers.remove(HttpHeaders.HOST);
				if (preserveHost) {
					String host = request.getHeaders().getFirst(HttpHeaders.HOST);
					headers.add(HttpHeaders.HOST, host);
				}
			}).request(method).uri(url).send((req, nettyOutbound) -> {
				...
				return nettyOutbound.send(request.getBody().map(this::getByteBuf));
			}).responseConnection((res, connection) -> {
```

响应到达时（`responseConnection` lambda，第 152-192 行）——**只暂存，不写回**：

```java
					// Defer committing the response until all route filters have run
					// Put client response as ServerWebExchange attribute and write
					// response later NettyWriteResponseFilter
					exchange.getAttributes().put(CLIENT_RESPONSE_ATTR, res);
					exchange.getAttributes().put(CLIENT_RESPONSE_CONN_ATTR, connection);

					ServerHttpResponse response = exchange.getResponse();
					// put headers and status so filters can modify the response
					HttpHeaders headers = new HttpHeaders();

					res.responseHeaders().forEach(entry -> headers.add(entry.getKey(), entry.getValue()));
					...
					HttpHeaders filteredResponseHeaders = HttpHeadersFilter.filter(getHeadersFilters(), headers, exchange,
							Type.RESPONSE);
					...
					response.getHeaders().addAll(filteredResponseHeaders);

					return Mono.just(res);
				})
```

最后 `return responseFlux.then(chain.filter(exchange))`（第 212 行）——注意这行是精髓：**`responseFlux` 只负责"把请求发出去、把响应元数据存好"，真正的响应体写出发生在 `chain.filter` 完成后由 NettyWriteResponseFilter 的 then 段接手**。两个过滤器用"前段注册 + 后段执行"接力完成了响应回传。

HttpClient 的构建在 `config/HttpClientFactory.java`（`GatewayAutoConfiguration$NettyConfiguration` 注册，第 881-903 行）：连接池（ELASTIC/FIXED/DISABLED、maxConnections、maxIdleTime/maxLifeTime、FIFO/LIFO 租约、池 metrics）、压缩、代理、SSL、HTTP/2（`server.http2.enabled` 时 `protocol(HTTP11, H2)`）全部来自 `HttpClientProperties`（前缀 `spring.cloud.gateway.server.webflux.httpclient`）；实例是**单例共享**的（`@ConditionalOnMissingBean({ HttpClient.class, HttpClientFactory.class })`）。`NettyRoutingFilter` 内只在两处做派生：按路由 metadata `connect-timeout`/`response-timeout` 覆盖超时（`getHttpClientMono`/`getResponseTimeout`，第 268-306 行；metadata 键常量在 `support/RouteMetadataUtils.java`），响应超时触发 `GATEWAY_TIMEOUT`(504)（第 193-205 行的 `timeout(...).onErrorMap`）。

### 3.6.6 其余全局过滤器速览

- **ForwardPathFilter / ForwardRoutingFilter**（order 0 / LOWEST）：路由 URI 为 `forward:/path` 时，前者把请求路径改成 forward 目标，后者把请求交给网关自己的 `DispatcherHandler`（`ServerWebExchangeUtils.handle`，第 484-494 行，转发前会清掉 `GATEWAY_PREDICATE_PATH_CONTAINER_ATTR`、剥掉 Origin 头避免内部转发被污染）。CircuitBreaker 的 fallback 转发走的也是这条通道。
- **StreamRoutingFilter / FunctionRoutingFilter**（order 10010）：`stream://bindingName` 把请求体经 `StreamBridge` 发到消息 binding；`fn://functionName` 从 Spring Cloud Function 的 `FunctionCatalog` 查函数、执行、把结果经 `CachedBodyOutputMessage` 写回响应。两者都在 `setAlreadyRouted` 后自己负责路由。
- **WebsocketRoutingFilter**（order LOWEST-1，**正好赶在 NettyRoutingFilter 前面**）：`changeSchemeIfIsWebSocketUpgrade`（第 159-174 行）发现 `Upgrade: WebSocket` 头且 scheme 是 http(s) 时把 URL 改写成 ws(s)://，然后用 `WebSocketClient` 与上游建双工通道，`ProxyWebSocketHandler`（第 184-305 行）双向 `retain()` 转发消息并同步 close 状态。这就是"网关代理 WebSocket"的全部实现——依然只是一个过滤器。
- **LoadBalancerServiceInstanceCookieFilter**（order 10151）：配置了 `loadbalancer.sticky-session.add-service-instance-cookie=true` 时，把所选实例的 instanceId 追加进转发请求的 Cookie 头（第 62-91 行）。

## 3.7 HttpHeadersFilter：请求/响应头的统一过滤

出网前改头、回程改头，是网关的高频动作。Gateway 把它抽象成 `filter/headers/HttpHeadersFilter.java`（第 26-75 行）：

```java
public interface HttpHeadersFilter {

	static HttpHeaders filterRequest(@Nullable List<HttpHeadersFilter> filters, ServerWebExchange exchange) {
		HttpHeaders headers = exchange.getRequest().getHeaders();
		return filter(filters, headers, exchange, Type.REQUEST);
	}

	static HttpHeaders filter(@Nullable List<HttpHeadersFilter> filters, HttpHeaders input, ServerWebExchange exchange,
			Type type) {
		if (filters != null) {
			HttpHeaders filtered = input;
			for (int i = 0; i < filters.size(); i++) {
				HttpHeadersFilter filter = filters.get(i);
				if (filter.supports(type)) {
					filtered = filter.filter(filtered, exchange);
				}
			}
			return filtered;
		}

		return input;
	}

	HttpHeaders filter(HttpHeaders input, ServerWebExchange exchange);

	default boolean supports(Type type) {
		return type.equals(Type.REQUEST);
	}

	enum Type {
		REQUEST,
		RESPONSE
	}
}
```

容器里**所有** `HttpHeadersFilter` bean 被 `ObjectProvider<List<HttpHeadersFilter>>` 收集、按 order 排序，在三个时机被应用：请求出网前（NettyRoutingFilter 第 129 行）、上游响应头复制时（第 175-176 行，Type.RESPONSE）、响应 Trailer 写出时（NettyWriteResponseFilter 第 133 行）。内置实现：

| 实现 | 作用 |
|---|---|
| `XForwardedHeadersFilter` | 给下游加/追加 `X-Forwarded-For/Host/Port/Proto/Prefix`（受 `x-forwarded.*` 与 trusted-proxies 控制） |
| `ForwardedHeadersFilter` | RFC 7239 标准 `Forwarded` 头的追加与清洗 |
| `RemoveHopByHopHeadersFilter` | 删掉逐跳头（connection、keep-alive、transfer-encoding、te、trailer、proxy-authorization、proxy-authenticate、x-application-context、upgrade），请求/响应两侧都支持 |
| `RemoveForwardedHeadersFilter` / `RemoveXForwardedHeadersFilter` | 与上面两个互斥注册（来源不可信时先剥头） |
| `GRPCRequestHeadersFilter` / `GRPCResponseHeadersFilter` | gRPC over HTTP/2 的头处理 |
| `TransferEncodingNormalizationHeadersFilter` | Transfer-Encoding 规范化 |
| `ObservedRequestHttpHeadersFilter` / `ObservedResponseHttpHeadersFilter` | Micrometer Observation 的启停（7.4 节） |

## 3.8 本章小结

- **网关 = WebFlux 应用**：`RoutePredicateHandlerMapping`（order=1）用断言找路由，把 handler 设为 `FilteringWebHandler`；找路由阶段无命中只是 404，`NotFoundException` 属于负载均衡阶段。
- **一条链**：GlobalFilter 与路由级 GatewayFilter 在 `FilteringWebHandler.getAllFilters` 合并、按 order 全序排列，`DefaultGatewayFilterChain` 用不可变索引推进（每步 new 一个 index+1 的链）。
- **路由互斥**：`setAlreadyRouted` 保证一个请求只被一种路由过滤器处理；URL 的最终形态由 `GATEWAY_REQUEST_URL_ATTR` 承载，一路被重写类过滤器接力修改。
- **响应回传是接力**：NettyRoutingFilter（LOWEST）出网并把上游连接/响应存进 exchange，NettyWriteResponseFilter（-1）在 then 段把 body 流式写回客户端——"前段注册、后段执行"。
- **属性即状态**：`ServerWebExchangeUtils` 的 20+ 个 GATEWAY_* 属性是全链路的共享黑板，没有 ThreadLocal。
- 一句话记住本章：**匹配靠断言，执行靠过滤器链，出网是 order 最大的过滤器，写回是 order -1 的过滤器的"后半段"。**


---

# 四、断言工厂大全（handler/predicate）

> 本章对应源码：`src/main/java/org/springframework/cloud/gateway/handler/predicate/`。断言工厂是"配置 → 谓词"的翻译器，共 15 个内置实现。

## 4.1 设计：RoutePredicateFactory 与 GatewayPredicate

【源码证据】`handler/predicate/RoutePredicateFactory.java` 第 33-76 行——工厂接口三件套：

```java
@FunctionalInterface
public interface RoutePredicateFactory<C> extends ShortcutConfigurable, Configurable<C> {

	String PATTERN_KEY = "pattern";
	...
	Predicate<ServerWebExchange> apply(C config);

	default AsyncPredicate<ServerWebExchange> applyAsync(C config) {
		return toAsyncPredicate(apply(config));
	}

	default String name() {
		return NameUtils.normalizeRoutePredicateName(getClass());
	}

}
```

- `apply(C config)`：拿到配置对象，返回同步谓词；需要异步（读体）时覆写 `applyAsync`。
- `name()`：类名去 `RoutePredicateFactory` 后缀，即 YAML 里的断言名。
- 配置类 C 的实例化与校验由 `AbstractConfigurable` + `ConfigurationService` 完成（2.5.1 节）。

谓词本体约定实现 `GatewayPredicate` 接口（`handler/predicate/GatewayPredicate.java:26`，`interface GatewayPredicate extends Predicate<ServerWebExchange>, HasConfig`）：带配置元数据、支持 `and/or/negate` 组合，且**每个匿名谓词都重写 `toString()` 输出人类可读的配置描述**（如 `Paths: [/red/**], match trailing slash: true`）——这个字符串会进入 Actuator 输出与日志，是排障时认路由的关键。

## 4.2 十五个内置断言（分主题逐个解析）

### 4.2.1 路径与主机：Path、Host

**Path** 是使用频率最高的断言。`handler/predicate/PathRoutePredicateFactory.java` 第 48 行声明，配置类只有 `patterns: List<String>` 与 `matchTrailingSlash: boolean`（默认 true）两个字段；shortcut 类型是 `GATHER_LIST_TAIL_FLAG`（第 76-83 行），所以 `Path=/a/**,/b/**,false` 的最后一位布尔会被剥成 matchTrailingSlash。apply()（第 86-152 行）两个要点：

- 启动时把所有 pattern 用 **`PathPatternParser`**（不是 AntPathMatcher）解析成 `PathPattern` 缓存——注意源码里留有这条 FIXME（第 89-90 行）：

```java
			// FIXME: 5.0.0 setMatchOptionalTrailingSeparator missing
			// pathPatternParser.setMatchOptionalTrailingSeparator(config.isMatchTrailingSlash());
```

Framework 7 移除了 `PathPatternParser.setMatchOptionalTrailingSeparator`，所以 `matchTrailingSlash` 配置**当前只影响 toString，不影响匹配行为**（尾斜杠匹配交给 PathPattern 语义）——升级 Framework 后产生的行为差异点之一。

- 每次匹配时，请求原始路径解析成 `PathContainer` 后缓存在 exchange（`GATEWAY_PREDICATE_PATH_CONTAINER_ATTR`，computeIfAbsent），因为一次请求要试探多条路由，路径不该重复解析；命中后 `matchAndExtract` 把 `{segment}` 变量合并进 `URI_TEMPLATE_VARIABLES_ATTRIBUTE`（这是 SetPath 模板里 `{segment}` 的来源），并把命中模式写进 `GATEWAY_PREDICATE_MATCHED_PATH_ATTR`（metrics 的 path 标签用）。

**Host**（`HostRoutePredicateFactory.java:35`）用的是 **`AntPathMatcher`，但把分隔符设成了点**（第 39 行 `new AntPathMatcher(".")`）——域名是按 `.` 分段的，`**.myhost.org`、`{sub}.another.org` 这种"按段通配"才有意义。includePort=false 时只取主机名不含端口（第 45-48 行）。

### 4.2.2 请求要素：Method、Header、Cookie、Query

四件套都是纯内存判断，结构雷同（声明行号：MethodRoutePredicateFactory:32、HeaderRoutePredicateFactory:33、CookieRoutePredicateFactory:32、QueryRoutePredicateFactory:33）：

- **Method**：`HttpMethod` 是常量类，匹配用 `==`（第 54-67 行）；配置绑定阶段由转换服务把字符串归一成常量。
- **Header**：正则在 apply 时编译一次（性能），值为空只查"头存在"，有正则则任一值全匹配（`asMatchPredicate`）。
- **Cookie**：指定名 cookie 存在且值匹配正则。
- **Query**：只有参数名时查存在性；带 regexp 或 Java `Predicate<String>` 时任一值匹配，两者互斥（配置类里 `@AssertTrue` 校验，第 140-143 行）。

### 4.2.3 时间窗：After、Before、Between

三兄弟同构（After:32、Before:32、Between:34），配置是 `ZonedDateTime`。时间字符串的解析由 `support/StringToZonedDateTimeConverter.java` 完成：ISO-8601 全格式（支持 `2026-01-20T17:42:47.789+08:00[Asia/Shanghai]` 时区段），也接受 epoch 毫秒数字（按 UTC 偏移 0）。比较用 `ZonedDateTime.now().isAfter/isBefore(...)`——绝对时间线比较，跨时区正确。Between 在 apply 时就校验 dt1 < dt2（第 56-78 行），坏配置启动即炸。

### 4.2.4 客户端地址：RemoteAddr、XForwardedRemoteAddr

**RemoteAddr**（`RemoteAddrRoutePredicateFactory.java:41`）把 `192.168.1.1/24` 这类 CIDR 字符串转成 **Netty 的 `IpSubnetFilterRule`**（ACCEPT 规则，无掩码默认 /32，第 107-117 行），对来源地址做子网匹配。来源地址从哪取是**可插拔的**——`support/ipresolver/RemoteAddressResolver.java:26-32` 接口默认取 TCP 对端，可以注入自定义实现。

**XForwardedRemoteAddr**（`XForwardedRemoteAddrRoutePredicateFactory.java:63`）展示了 Gateway 源码里少见的"组合复用"写法：它内部 new 了一个 RemoteAddr 工厂、只把 resolver 换成 `XForwardedRemoteAddressResolver`（第 83-114 行）：

```java
		// Reuse the standard RemoteAddrRoutePredicateFactory but instead of using the
		// default RemoteAddressResolver to determine the client IP address, use an
		// XForwardedRemoteAddressResolver.
		RemoteAddrRoutePredicateFactory.Config wrappedConfig = new RemoteAddrRoutePredicateFactory.Config();
		wrappedConfig.setSources(config.getSources());
		wrappedConfig
			.setRemoteAddressResolver(XForwardedRemoteAddressResolver.maxTrustedIndex(config.getMaxTrustedIndex()));
```

XFF 解析器（`support/ipresolver/XForwardedRemoteAddressResolver.java:110-133`）的信任模型：头内容形如 `client, proxy1, proxy2`，`maxTrustedIndex` 决定"相信倒数第几个"——=1 取最后一个（离网关最近的代理填的），=N 取倒数第 N 个；`trustAll()` 则取第一个（客户端自填，可伪造，仅限内网调试）。多个 X-Forwarded-For 头同时出现时出于安全全部丢弃。

### 4.2.5 读体断言：ReadBody

**唯一"必须异步"的断言**：`ReadBodyRoutePredicateFactory.java` 第 64-121 行只实现 `applyAsync`，同步 `apply` 直接抛 `UnsupportedOperationException("ReadBodyPredicateFactory is only async.")`。逻辑：用 `ServerWebExchangeUtils.cacheRequestBodyAndRequest` 把请求体 join 成单个 DataBuffer（断言阶段 exchange 不能 mutate，装饰器先存进 `CACHED_SERVER_HTTP_REQUEST_DECORATOR_ATTR`，等 3.6.1 的 AdaptCachedBodyGlobalFilter 在链上取用换装），再把字节按配置的 inClass 反序列化成对象缓存进 `cachedRequestBodyObject` 属性，对其跑用户谓词；同一请求再跑该断言（多条路由都用它时）直接用缓存对象，不二次读流。

### 4.2.6 版本：Version（5.0 新增）

【源码证据】`handler/predicate/VersionRoutePredicateFactory.java` 第 40、64-113 行——直接**站在 Spring Framework 7 API 版本化的肩膀上**：

```java
	@Override
	public Predicate<ServerWebExchange> apply(Config config) {

		if (apiVersionStrategy instanceof DefaultApiVersionStrategy strategy) {
			String version = config.version;
			if (version != null) {
				strategy
					.addMappedVersion((version.endsWith("+") ? version.substring(0, version.length() - 1) : version));
			}
		}

		return new GatewayPredicate() {
			@Override
			public boolean test(ServerWebExchange exchange) {
				ServerHttpRequest request = exchange.getRequest();
				if (config.parsedVersion == null) {
					Assert.state(apiVersionStrategy != null, "No ApiVersionStrategy to parse version with");
					...
				}

				ApiVersionHolder requestVersion = (ApiVersionHolder) request.getAttributes()
					.get(HandlerMapping.API_VERSION_ATTRIBUTE);
				...
				int result = compareVersions(config.parsedVersion, requestVersion.getVersion());
				boolean match = (config.baselineVersion ? result <= 0 : result == 0);
				traceMatch("Version", config.version, requestVersion, match);
				return match;
			}
```

"版本从哪读"（header/query/path 段）由 Spring 的 `ApiVersionResolver` 按 `spring.webflux.apiversion.*` 配置解析后放进请求属性；断言只回答"这条路由要哪个版本"：`Version=1.3` 要求相等，`Version=1.3+`（baseline）要求请求版本 ≥ 1.3，**请求不带版本默认放行**。`ApiVersionStrategy` bean 由 Boot 的 WebFlux 自动配置提供，GatewayAutoConfiguration 第 535-540 行用 `@Qualifier("webFluxApiVersionStrategy")` 可选注入。

### 4.2.7 CloudFoundryRouteService（特例）

`CloudFoundryRouteServiceRoutePredicateFactory.java:31`——配置就是 `Object`，无参数。存在原因：识别"已经过 Cloud Foundry Route Service 打标"的请求（`X-CF-Forwarded-Url` + `X-CF-Proxy-Signature` + `X-CF-Proxy-Metadata` 三个头同时存在）。实现是三个 Header 存在性断言的 `and` 组合（第 55-65 行）——恰好是 4.1 节 GatewayPredicate 组合子的实际用例。

## 4.3 Weight：需要 WebFilter 配合的两段式断言

Weight 断言（灰度/金丝雀发布的核心）是 Gateway 里唯一"断言 + 前置 WebFilter"协作的机制，也最能体现"请求作用域状态放 exchange"的设计。

**第一段：`WeightCalculatorWebFilter`**（`filter/WeightCalculatorWebFilter.java:59`，`implements WebFilter, Ordered, SmartApplicationListener`，order=10001——它在 WebFlux 的 filter chain 里执行，**早于一切 handler**）。它做三件事：

1. **收集权重配置**：监听 `PredicateArgsEvent`（YAML 断言解析时发布）、`WeightDefinedEvent`（Java DSL `beforeApply` 发布，WeightRoutePredicateFactory.java:79-83）等事件，把同 group 的各路由权重汇总成 `GroupWeightConfig`（filter 实例字段 `groupWeights`，第 76 行——**应用级状态，非请求级**）；
2. **归一化 + 建区间表**（`addWeightConfig`，第 178-231 行）：权重和归一为 [0,1] 的累积区间 `[0, w1, w1+w2, ..., 1.0]`，区间序号对应 routeId；
3. **每请求掷骰子**（`filter`，第 248-285 行）：

```java
	public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
		Map<String, String> weights = getWeights(exchange);

		for (String group : groupWeights.keySet()) {
			GroupWeightConfig config = groupWeights.get(group);
			...
			// Usually, multiple threads accessing the same random object will have some
			// performance problems, so we can use ThreadLocalRandom by default
			double r = randomFunction.apply(exchange);

			List<Double> ranges = config.ranges;
			...
			for (int i = 0; i < ranges.size() - 1; i++) {
				if (r >= ranges.get(i) && r < ranges.get(i + 1)) {
					String routeId = config.rangeIndexes.get(i);
					weights.put(group, routeId);
					break;
				}
			}
		}
		...
		return chain.filter(exchange);
	}
```

选中结果写进 exchange 属性 `routeWeight`（`WEIGHT_ATTR`）——"这一组的这一枪打中了哪条路由"。

**第二段：`WeightRoutePredicateFactory`**（`handler/predicate/WeightRoutePredicateFactory.java:42`）的谓词**不做任何计算**，只查黑板（第 86-128 行）：

```java
			public boolean test(ServerWebExchange exchange) {
				Map<String, String> weights = exchange.getAttributeOrDefault(WEIGHT_ATTR, Collections.emptyMap());

				String routeId = exchange.getAttribute(GATEWAY_PREDICATE_ROUTE_ATTR);

				if (routeId == null) {
					return false;
				}

				// all calculations and comparison against random num happened in
				// WeightCalculatorWebFilter
				String group = config.getGroup();
				if (weights.containsKey(group)) {

					String chosenRoute = weights.get(group);
					...
					return routeId.equals(chosenRoute);
				}
				...
```

`GATEWAY_PREDICATE_ROUTE_ATTR` 是 HandlerMapping 试探每条路由时写入的"当前候选 id"（3.2 节）——断言拿它和抽签结果比对，相等才算命中。装配上两者有依赖序：`GatewayAutoConfiguration:548-553` 的 `weightRoutePredicateFactory` 加了 `@DependsOn("weightCalculatorWebFilter")`。

## 4.4 本章小结（速查表）

| 断言（类名去 RoutePredicateFactory） | shortcut 示例 | 一句话职责 |
|---|---|---|
| Path | `Path=/red/{segment},/blue/**[,false]` | PathPatternParser 匹配路径，提取 URI 变量 |
| Method | `Method=GET,POST` | 方法集合匹配（HttpMethod 常量 ==） |
| Host | `Host=**.somehost.org,{sub}.another.org` | AntPathMatcher(".") 点分匹配，可提取 {sub} |
| Header | `Header=X-Request-Id,\d+` | 头存在性 / 正则全匹配 |
| Cookie | `Cookie=chocolate,ch.` | cookie 存在且值匹配正则 |
| Query | `Query=green` / `Query=red,gree.` | 参数存在性 / 值匹配（regexp 与谓词互斥） |
| After / Before / Between | `Before=2026-12-31T23:59:59+08:00[Asia/Shanghai]` | 时间窗（ZonedDateTime/epoch 毫秒） |
| RemoteAddr | `RemoteAddr=192.168.1.0/24,10.0.0.1` | CIDR 子网匹配（IpSubnetFilterRule），resolver 可插拔 |
| XForwardedRemoteAddr | `XForwardedRemoteAddr=20.103.252.85` | 同上，但 IP 取自 X-Forwarded-For（maxTrustedIndex 信任模型） |
| Weight | `Weight=group1,2` | 读 WeightCalculatorWebFilter 每请求抽签结果 |
| ReadBody | 仅 Java DSL | 异步读体 + 缓存 + 用户谓词（同步版抛异常） |
| Version | `Version=1.3` / `Version=1.3+` | Framework 7 API 版本化（相等 / 基线以上） |
| CloudFoundryRouteService | 无参 | 三个 X-CF-* 头齐全 = 经过 CF Route Service |

- 设计要点回顾：断言是"配置→谓词"的纯翻译器，请求路径上不做解析/编译；请求级中间状态一律进 exchange 属性；需要异步（读体）就走 `applyAsync`。
- 一句话记住本章：**断言只回答"走不走"，永远不碰请求内容（ReadBody 除外），状态从 exchange 黑板来。**

# 五、过滤器工厂大全（filter/factory）（重点）

> 本章对应源码：`src/main/java/org/springframework/cloud/gateway/filter/factory/`（含 `ratelimit/`、`rewrite/`、`cache/` 子包）。这是日常使用中打交道最多的一层。

## 5.1 设计：GatewayFilterFactory 与三种 shortcut 形态

【源码证据】`filter/factory/GatewayFilterFactory.java` 第 33-80 行：

```java
@FunctionalInterface
public interface GatewayFilterFactory<C> extends ShortcutConfigurable, Configurable<C> {

	String NAME_KEY = "name";

	String VALUE_KEY = "value";
	...
	GatewayFilter apply(C config);
	...
	default String name() {
		// TODO: deal with proxys
		return NameUtils.normalizeFilterFactoryName(getClass());
	}

}
```

`apply(C config)` 是唯一抽象方法——**工厂在路由翻译期（2.5 节）被调用一次，产出的 GatewayFilter 实例被缓存在 Route 里**；请求路径上只有 `filter(exchange, chain)` 在跑。这就是为什么过滤器工厂的 apply 里可以放心做昂贵操作（编译正则、new 模板对象），而过滤器本体必须无状态（同一实例可能同时服务所有请求）。

三个继承体系速记：

- `AbstractGatewayFilterFactory<C>`：标准基类，持有配置类；额外提供 `enableBodyCaching(routeId)`（第 49-54 行，发布 `EnableBodyCachingEvent` 让 AdaptCachedBodyGlobalFilter 开启读体缓存——Retry 与 CircuitBreaker fallback 依赖它）。
- `AbstractNameValueGatewayFilterFactory`：`name/value` 双参数模板（第 29-38 行，shortcutFieldOrder = [name, value]），Add*/Remove*/Set* 九个头/参数类工厂全部继承它，子类只覆写 `apply(NameValueConfig)`。
- `AbstractChangeRequestUriGatewayFilterFactory`：改转发 URI 的模板（子类实现 `determineRequestUri`），默认 order 是 `RouteToRequestUrlFilter.ROUTE_TO_URL_FILTER_ORDER + 1`（即 10001），RequestHeaderToRequestUri、SetRequestUri 继承它。

## 5.2 路径与 URI：PrefixPath、SetPath、StripPrefix、RewritePath

这四个过滤器全部遵循同一纪律：**先把原始 URL 记入 `GATEWAY_ORIGINAL_REQUEST_URL_ATTR`，再把新 URL 写进 `GATEWAY_REQUEST_URL_ATTR`**——排障时把"改写轨迹"留在黑板上。

**PrefixPath**（`PrefixPathGatewayFilterFactory.java:45`）加前缀，且用 `GATEWAY_ALREADY_PREFIXED_ATTR` 防止重复加前缀（第 71-93 行，内部转发场景会再次进入过滤器）。

**SetPath**（`SetPathGatewayFilterFactory.java:42`）按 URI 模板整体设路径，`{segment}` 占位符取自断言匹配时提取的 `URI_TEMPLATE_VARIABLES_ATTRIBUTE`：

```java
	public GatewayFilter apply(Config config) {
		String template = Objects.requireNonNull(config.template, "template must not be null");
		UriTemplate uriTemplate = new UriTemplate(template);

		return new GatewayFilter() {
			@Override
			public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
				ServerHttpRequest req = exchange.getRequest();
				addOriginalRequestUrl(exchange, req.getURI());

				Map<String, String> uriVariables = getUriTemplateVariables(exchange);

				URI uri = uriTemplate.expand(uriVariables);
				String newPath = uri.getRawPath();

				exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, uri);
```

**StripPrefix**（`StripPrefixGatewayFilterFactory.java:40`）按 `/` 切分路径、丢前 N 段（Config 默认 `parts = 1`，第 99 行），保留末尾斜杠语义（第 61-87 行）——把 `/api/order/1` 变 `/order/1` 的标准做法。

**RewritePath**（`RewritePathGatewayFilterFactory.java:40`）正则整体替换；`$\` 转义还原为 `$`（第 63-80 行）：

```java
	public GatewayFilter apply(Config config) {
		String replacementValue = Objects.requireNonNull(config.replacement, "replacement must not be null");
		String replacement = replacementValue.replace("$\\", "$");
		String regexpValue = Objects.requireNonNull(config.regexp, "regexp must not be null");
		Pattern pattern = Pattern.compile(regexpValue);
		return new GatewayFilter() {
			@Override
			public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
				ServerHttpRequest req = exchange.getRequest();
				addOriginalRequestUrl(exchange, req.getURI());
				String path = req.getURI().getRawPath();
				String newPath = pattern.matcher(path).replaceAll(replacement);

				ServerHttpRequest request = req.mutate().path(newPath).build();

				exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, request.getURI());

				return chain.filter(exchange.mutate().request(request).build());
			}
```

## 5.3 请求/响应头：Add*/Remove*/Set*/Map*/Dedupe*/SecureHeaders

- **AddRequestHeader 等 Name/Value 家族**（9 个，继承 AbstractNameValueGatewayFilterFactory）：请求侧在进链时就 mutate request（如 `AddRequestHeaderGatewayFilterFactory.java:40-49`，值支持 `{placeholder}` 展开）；响应侧在 `chain.filter(...).then(...)` 里改 response——**响应头过滤器总是"后半段"执行**（RemoveResponseHeader:52-56）。
- **DedupeResponseHeader**（`DedupeResponseHeaderGatewayFilterFactory.java:73`）：网关和下游都加了 CORS 头时会出现 `value, value`，三种去重策略 `RETAIN_FIRST`（默认）/`RETAIN_LAST`/`RETAIN_UNIQUE`（第 109-126 行）。
- **SecureHeaders**（`SecureHeadersGatewayFilterFactory.java:44`）：默认给响应加一组安全头（X-Xss-Protection、Strict-Transport-Security、X-Frame-Options: DENY、X-Content-Type-Options: nosniff、Referrer-Policy: no-referrer、Content-Security-Policy、X-Download-Options、X-Permitted-Cross-Domain-Policies，可选 Permissions-Policy），清单在 `SecureHeadersProperties.java` 常量区（第 36-122 行），可全局/路由级 enable/disable。
- **SetRequestHostHeader / PreserveHostHeader**：改写或保留转发 Host 头（后者只放 `PRESERVE_HOST_HEADER_ATTRIBUTE` 标记，真正处理在 NettyRoutingFilter 第 137-142 行：默认剥掉 Host 由 Netty 按目标地址重填，有标记才保留原值）。
- **RewriteLocationResponseHeader / RewriteResponseHeader**：把响应 Location 头里的后端 `host:port` 换成网关对外地址（可选剥离 `/v1` 版本段，StripVersion 三态，`RewriteLocationResponseHeaderGatewayFilterFactory.java:128-195`）、对指定响应头做正则替换。
- **MapRequestHeader / AddRequestHeadersIfNotPresent / RemoveRequestParameter / RewriteRequestParameter**：头改名复制、批量"缺了才加"（GATHER_LIST，值支持展开）、从 URI 上删查询参数（重建 URI，`RemoveRequestParameterGatewayFilterFactory.java:57-74`）、替换已存在参数值。
- **RequestSize / RequestHeaderSize**：Content-Length 超限（默认 5MB）回 **413 CONTENT_TOO_LARGE**、任一请求头超限（默认 16KB）回 **431 REQUEST_HEADER_FIELDS_TOO_LARGE**（无 Content-Length 的流式请求 RequestSize 不生效，第 72-89 行）。
- **RedirectTo / SetStatus**：网关直接 3xx 重定向（校验必须 3xx，可拼原始查询参数）；改写响应状态码（可在 `originalStatusHeaderName` 里留原值）。
- **SaveSession**：转发前强制 `WebSession.save()`（第 44-46 行，Spring Session 懒保存场景）。
- **TokenRelay**（`TokenRelayGatewayFilterFactory.java:39`）：取当前登录用户的 OAuth2 access token（经 `ReactiveOAuth2AuthorizedClientManager`，必要时自动刷新），以 `Authorization: Bearer` 转发下游（第 59-100 行）；需要 `spring-boot-starter-oauth2-client`。
- **JsonToGrpc**（`JsonToGrpcGatewayFilterFactory.java:93`）：请求 JSON → protobuf DynamicMessage → gRPC 调用 → 响应转回 JSON，对客户端隐藏 gRPC；`setAlreadyRouted` 后自己路由，order 挂在 `WRITE_RESPONSE_FILTER_ORDER - 1`。
- **FallbackHeaders**：从 `CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR` 取熔断异常，把类型/消息/根因写进发往 fallback 端点的请求头（第 48-78 行）——与 5.5 节的熔断过滤器联动。

## 5.4 Retry：异常与状态码双路重试（重点）

重试是网关里逻辑最绕的过滤器：它要包住整条后续链，且"重试"意味着**请求体可重读、响应可重置、状态可回滚**。

【源码证据】`filter/factory/RetryGatewayFilterFactory.java` 第 58 行声明；配置类 `RetryConfig`（第 368-386 行）字段全集：

```java
	public static class RetryConfig implements HasRouteId {

		private @Nullable String routeId;

		private int retries = 3;

		private List<Series> series = toList(Series.SERVER_ERROR);

		private List<HttpStatus> statuses = new ArrayList<>();

		private List<HttpMethod> methods = toList(HttpMethod.GET);

		private List<Class<? extends Throwable>> exceptions = toList(IOException.class, TimeoutException.class);

		private @Nullable BackoffConfig backoff;

		private @Nullable JitterConfig jitter;

		private @Nullable Duration timeout;
```

默认策略直译：**默认只重试 GET、只重试 5xx 系列、只重试 IO/超时异常，最多 3 次**。apply() 的骨架（第 81-110 行）：

```java
	public GatewayFilter apply(RetryConfig retryConfig) {
		retryConfig.validate();
		enableBodyCaching(retryConfig.getRouteId());

		boolean hasStatusCodeRepeat = !retryConfig.getStatuses().isEmpty() || !retryConfig.getSeries().isEmpty();
		boolean hasExceptionRetry = !retryConfig.getExceptions().isEmpty();

		GatewayFilter gatewayFilter = (exchange, chain) -> {
			trace("Entering retry-filter");

			// chain.filter returns a Mono<Void>
			Publisher<Void> publisher = chain.filter(exchange)
				.doOnSuccess(aVoid -> updateIteration(exchange))
				.doOnError(throwable -> updateIteration(exchange));

			if (hasExceptionRetry) {
				// retryWhen returns a Mono<Void>
				// retry needs to go before repeat
				publisher = ((Mono<Void>) publisher).retryWhen(buildExceptionRetry(exchange, retryConfig));
			}
			if (hasStatusCodeRepeat) {
				// repeatWhen returns a Flux<Void>
				// so this needs to be last and the variable a Publisher<Void>
				publisher = ((Mono<Void>) publisher).repeatWhen(buildStatusCodeRepeat(exchange, retryConfig));
			}

			return Mono.fromDirect(publisher);
		};
```

两条重试通路用了两套 Reactor 机制，这是理解本类的前提：

- **异常重试走 `retryWhen`**（Reactor `Retry.backoff` spec，第 247-286 行）：谓词先查"超过次数或超预算"，再按异常类型（含 cause 链）+ 方法白名单判定；配了 backoff 时自动指数退避 + 抖动。
- **状态码重试走 `repeatWhen`**：因为"响应已经写完了"不产生异常，只能对完成的 Mono 做 repeat。门控是手写的 `takeWhile`（第 232-245 行）：

```java
	private Function<Flux<Long>, Publisher<Long>> buildStatusCodeRepeat(ServerWebExchange exchange,
			RetryConfig retryConfig) {
		Instant start = Instant.now();
		return companion -> companion.map(ignored -> nextIteration(exchange))
			.map(iteration -> Tuples.of(iteration, nextDelay(iteration, retryConfig)))
			.takeWhile(tuple -> !exceedsMaxIterations(exchange, retryConfig)
					&& isRetryableStatusCode(exchange, retryConfig) && isRetryableMethod(exchange, retryConfig)
					&& withinTimeout(start, retryConfig.getTimeout(), tuple.getT2()))
			.doOnNext(tuple -> reset(exchange))
			.concatMap(tuple -> tuple.getT2().isZero() ? Mono.just(tuple.getT1())
					: Mono.delay(tuple.getT2()).thenReturn(tuple.getT1()));
	}
```

三个值得咀嚼的细节：① **迭代计数放 exchange 属性 `retry_iteration`**（第 63、347-352 行）而不是 Reactor 的伴生 Flux 值——因为 `Mono<Void>` 的 repeat 信号值恒为 0，不可信（源码第 223 行注释）；② **每轮重试前 `reset(exchange)`**（第 312-318 行，即 `ServerWebExchangeUtils.reset`：dispose 上游连接、清响应头、撤 already-routed 标记）——这是"响应可重置"的实现；③ **超时预算把下一轮退避时长也算进去**（`withinTimeout`，第 174-176 行），避免"等完退避才发现超时"。退避公式（第 183-220 行）：`firstBackoff × factor^(n-1)` 封顶 maxBackoff，再叠加 ±randomFactor 抖动。

## 5.5 CircuitBreaker：熔断与 fallback（重点）

【源码证据】`filter/factory/SpringCloudCircuitBreakerFilterFactory.java` 第 56 行声明（抽象类，`name()` 覆写为 `CircuitBreaker`，第 168-171 行——所以 YAML 里过滤器名是 CircuitBreaker 而不是类名）；核心 apply（第 91-158 行）：

```java
	public GatewayFilter apply(Config config) {
		if (config.getFallbackUri() != null) {
			enableBodyCaching(config.getRouteId());
		}
		ReactiveCircuitBreaker cb = reactiveCircuitBreakerFactory.create(config.getId());
		Set<HttpStatus> statuses = config.getStatusCodes()
			.stream()
			.map(HttpStatusHolder::parse)
			.filter(statusHolder -> statusHolder.getHttpStatus() != null)
			.map(HttpStatusHolder::getHttpStatus)
			.collect(Collectors.toSet());

		return new GatewayFilter() {
			@Override
			public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
				return cb.run(chain.filter(exchange).doOnSuccess(v -> {
					if (statuses.contains(exchange.getResponse().getStatusCode())) {
						...
						throw new CircuitBreakerStatusCodeException(status);
					}
				}), t -> {
					if (config.getFallbackUri() == null) {
						return Mono.error(t);
					}

					exchange.getResponse().setStatusCode(null);

					// TODO: copied from RouteToRequestUrlFilter
					URI uri = exchange.getRequest().getURI();
					...
					String expandedFallbackUri = ServerWebExchangeUtils.expand(exchange,
							config.getFallbackUri().getPath());
					String fullFallbackUri = String.format("%s:%s", config.getFallbackUri().getScheme(),
							expandedFallbackUri);
					URI requestUrl = UriComponentsBuilder.fromUri(uri)
						.host(null)
						.port(null)
						.uri(URI.create(fullFallbackUri))
						.scheme(null)
						.build(encoded)
						.toUri();

					exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, requestUrl);
					addExceptionDetails(t, exchange);

					// Reset the exchange
					reset(exchange);

					ServerHttpRequest request = exchange.getRequest().mutate().uri(requestUrl).build();
					return handle(getDispatcherHandler(), exchange.mutate().request(request).build());
				}).onErrorResume(t -> handleErrorWithoutFallback(t, config.isResumeWithoutError()));
			}
```

读法：`cb.run(chain.filter(...), fallback)` 把**整条后段链**（负载均衡、出网、写回）都包进熔断器；配了 `statusCodes` 时，把命中这些状态码的响应转成异常计入失败；走 fallback 的动作是"**改 URL → reset exchange → 交给本地 DispatcherHandler 内部路由**"（`forward:` 语义，最终由 ForwardRoutingFilter 兜住）——不是再发一次 HTTP。异常细节同时存进 `CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR`，供 FallbackHeaders 过滤器带给 fallback 端点。

具体实现是 Resilience4J 特化（`SpringCloudCircuitBreakerResilience4JFilterFactory.java:32`，由 `GatewayResilience4JCircuitBreakerAutoConfiguration:46` 在 classpath 有 ReactiveResilience4JCircuitBreakerFactory 时注册），它定义了"熔断态"的语义翻译（第 32-50 行）：超时 → 504，熔断打开（`CallNotPermittedException`）→ 503，`resumeWithoutError=true` 时吞掉异常返回空。

## 5.6 RequestRateLimiter：KeyResolver + RedisRateLimiter 令牌桶（重点）

限流过滤器是三方协作：**工厂管流程，KeyResolver 定 key，RateLimiter 定算法**。

【源码证据】`filter/factory/RequestRateLimiterGatewayFilterFactory.java` 第 43 行声明（同时是 `@ConfigurationProperties("spring.cloud.gateway.server.webflux.filter.request-rate-limiter")`）；apply 主流程（第 111-151 行）：

```java
		return (exchange, chain) -> resolver.resolve(exchange).defaultIfEmpty(EMPTY_KEY).flatMap(key -> {
			if (EMPTY_KEY.equals(key)) {
				if (denyEmpty) {
					setResponseStatus(exchange, emptyKeyStatus);
					return exchange.getResponse().setComplete();
				}
				return chain.filter(exchange);
			}
			String routeId = config.getRouteId();
			if (routeId == null) {
				Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
				routeId = Objects.requireNonNull(route, "Route not found").getId();
			}
			return limiter.isAllowed(routeId, key).flatMap(response -> {

				for (Map.Entry<String, String> header : response.getHeaders().entrySet()) {
					exchange.getResponse().getHeaders().add(header.getKey(), header.getValue());
				}

				if (response.isAllowed()) {
					return chain.filter(exchange);
				}

				if (throwLimit) {
					return Mono.error(HttpClientErrorException.create(config.getStatusCode(), "Too Many Requests",
							exchange.getResponse().getHeaders(), null, null));
				}

				setResponseStatus(exchange, config.getStatusCode());
				return exchange.getResponse().setComplete();
			});
		});
```

流程：KeyResolver（默认 `PrincipalNameKeyResolver` 按登录名，容器无 KeyResolver bean 时限流过滤器不注册——见 GatewayAutoConfiguration:668-682 的条件）解析限流 key → 空 key 默认 **403**（`denyEmptyKey=true`）→ `RateLimiter.isAllowed(routeId, key)` 判定 → 拒绝时默认 **429 TOO_MANY_REQUESTS**（`statusCode` 可配，或 `throwOnLimit=true` 改抛异常）→ 限流响应头（X-RateLimit-*）无条件回写。

Redis 实现（`filter/ratelimit/RedisRateLimiter.java:57`）是经典令牌桶：`replenishRate`（每秒补充）、`burstCapacity`（桶容量，必须 ≥ replenishRate）、`requestedTokens`（单次请求消耗）。原子性靠 Lua 脚本（`src/main/resources/META-INF/scripts/request_rate_limiter.lua`，bean 名 `redisRequestRateLimiterScript`）：

```lua
local last_tokens = tonumber(redis.call("get", tokens_key)) or capacity
local last_refreshed = tonumber(redis.call("get", timestamp_key)) or 0

local delta = math.max(0, now-last_refreshed)
local filled_tokens = math.min(capacity, last_tokens+(delta*rate))
local allowed = filled_tokens >= requested
local new_tokens = allowed and filled_tokens - requested or filled_tokens
```

两个工程细节：① Redis key 用 **`{}` 哈希标签**保证 Cluster 下两把 key（tokens/timestamp）落在同一 slot（`getKeys`，第 160-171 行）；② **Redis 故障 fail-open**——执行出错时 `onErrorResume` 返回 `[1, -1]` 放行（第 269-274 行），网关可用性优先于限流精确性。另有 `Bucket4jRateLimiter`（`ratelimit/Bucket4jRateLimiter.java:44`，需要 bucket4j 依赖）作为非 Redis 选项。抽象接口在 `ratelimit/RateLimiter.java:30`（`Mono<Response> isAllowed(String routeId, String id)`）与 `ratelimit/KeyResolver.java:26`（`Mono<String> resolve(ServerWebExchange)`）。

## 5.7 Body 处理：ModifyRequestBody / ModifyResponseBody / CacheRequestBody

改 body 的通用套路是"**缓冲 → 改写 → 换装**"：`rewrite/CachedBodyOutputMessage.java:36`（实现 `ReactiveHttpOutputMessage`，`writeWith` 只把 Publisher 存为字段不真写出，`getBody()` 返回缓存的 Flux）充当内存缓冲，再用 `ServerHttpRequestDecorator` / `ServerHttpResponseDecorator` 把装饰后的 exchange 交给链的下一环。

**ModifyRequestBody**（`rewrite/ModifyRequestBodyGatewayFilterFactory.java:55`，第 89-117 行）：把请求体读成 inClass 对象 → 用户 `RewriteFunction` 转换 → 写进 CachedBodyOutputMessage → 请求装饰器把 `getBody()` 换成新 body（无 Content-Length 时自动改 `Transfer-Encoding: chunked`，第 139-163 行）。

**ModifyResponseBody**（`rewrite/ModifyResponseBodyGatewayFilterFactory.java:64`）：用响应装饰器拦截 `writeWith`（第 226-267 行），核心段落：

```java
		public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
			...
			ClientResponse clientResponse = prepareClientResponse(body, httpHeaders);

			// TODO: flux or mono
			Mono modifiedBody = extractBody(exchange, clientResponse, inClass)
				.flatMap(originalBody -> rewriteFunction.apply(exchange, originalBody))
				.switchIfEmpty(Mono.defer(() -> (Mono) rewriteFunction.apply(exchange, null)));

			BodyInserter bodyInserter = BodyInserters.fromPublisher(modifiedBody, outClass);
			CachedBodyOutputMessage outputMessage = new CachedBodyOutputMessage(exchange,
					exchange.getResponse().getHeaders());
			return bodyInserter.insert(outputMessage, new BodyInserterContext(exchangeStrategies))
				.then(Mono.defer(() -> {
					Mono<DataBuffer> messageBody = writeBody(getDelegate(), outputMessage, outClass);
					HttpHeaders headers = getDelegate().getHeaders();
					if (!headers.containsHeader(HttpHeaders.TRANSFER_ENCODING)
							|| headers.containsHeader(HttpHeaders.CONTENT_LENGTH)) {
						messageBody = messageBody.doOnNext(data -> headers.setContentLength(data.readableByteCount()));
					}
					...
					return getDelegate().writeWith(messageBody);
				}));
		}
```

细节：order 挂在 `WRITE_RESPONSE_FILTER_ORDER - 1`（比写回过滤器早）；gzip 响应会按 `Content-Encoding` 找到 `GzipMessageBodyResolver` 在 parallel 调度器上解码、改写后再编码（`extractBody`/`writeBody`，第 282-327 行）；改写后按实际字节数**重算 Content-Length**（上面片段中的 `setContentLength`）。派生工厂 `RemoveJsonAttributesResponseBody`（删除 JSON 响应指定字段，支持递归）就是它的委托封装。

**CacheRequestBody**（`CacheRequestBodyGatewayFilterFactory.java:43`）：不修改只缓存——把请求体按 `bodyClass` 反序列化后放进 `CACHED_REQUEST_BODY_ATTR`，给后续断言/过滤器复用（ReadBody 断言、ModifyRequestBody 等），结尾 `doFinally` 释放备份 DataBuffer 防泄漏（第 64-104 行）。

## 5.8 LocalResponseCache：本地响应缓存

`cache` 子包（约 20 个类）实现"网关侧 GET 响应缓存"：

- **路由级** `LocalResponseCacheGatewayFilterFactory`（`cache/LocalResponseCacheGatewayFilterFactory.java:45`）：为每条路由注册一个 Caffeine 缓存（名字 `routeId + "-cache"`），shortcut 是 `LocalResponseCache=timeToLive,size`；
- **全局兜底** `GlobalLocalResponseCacheGatewayFilter`（`cache/GlobalLocalResponseCacheGatewayFilter.java:39`，order=-3）：给没配路由级缓存的路由套全局缓存，用 `LOCAL_RESPONSE_CACHE_FILTER_APPLIED` 属性防止双重缓存；
- **实际缓存逻辑** `ResponseCacheGatewayFilter`（`cache/ResponseCacheGatewayFilter.java:43`，order=-4）：只缓存 GET 且无 body 的请求、响应 200/206/301，`Cache-Control: private/no-store` 或 `Vary: *` 禁缓存（`ResponseCacheManager.java:58-198`）；命中直接回放缓存 body，未命中挂 `CachingResponseDecorator` 在 `writeWith` 时旁路收集 body 入缓存；
- **缓存 key** = MD5(URI + Authorization + Cookie + Vary 头)（`cache/keygenerator/CacheKeyGenerator.java:36-51`）。

## 5.9 全部过滤器工厂速查表

过滤器名 = 类名去 `GatewayFilterFactory` 后缀（`name()` 规则）；"shortcut 形式"即 `shortcutFieldOrder` 的参数顺序。

| 过滤器名 | shortcut 形式 | 一句话职责 |
|---|---|---|
| PrefixPath | `PrefixPath=prefix` | 路径加前缀（防重复） |
| StripPrefix | `StripPrefix=parts`（默认 1） | 剥掉路径前 N 段 |
| SetPath | `SetPath=/{template}` | URI 模板设路径（{segment} 来自断言变量） |
| RewritePath | `RewritePath=regexp,replacement` | 正则重写路径 |
| SetRequestUri / RequestHeaderToRequestUri | `SetRequestUri=template` / `...Header=name` | 整体重写 URI / 用头的值当 URI |
| SetRequestHostHeader / PreserveHostHeader | `SetRequestHostHeader=host` / 无参 | 改写 / 保留转发 Host 头 |
| AddRequestHeader / SetRequestHeader / RemoveRequestHeader | `=name,value` / `=name` | 请求头增/改/删（值支持占位符） |
| AddRequestHeadersIfNotPresent | `=k1=v1,k2=v2`（GATHER_LIST） | 批量"缺了才加" |
| MapRequestHeader | `MapRequestHeader=from,to` | 头改名复制 |
| AddRequestParameter / RemoveRequestParameter / RewriteRequestParameter | `=name,value` 等 | 查询参数增/删/改值 |
| AddResponseHeader / SetResponseHeader / RemoveResponseHeader | `=name,value` / `=name` | 响应头增/改/删（then 段执行） |
| RewriteResponseHeader / RewriteLocationResponseHeader | `=name,regexp,replacement` 等 | 响应头正则改写 / Location 换网关地址 |
| DedupeResponseHeader | `DedupeResponseHeader=name[,strategy]` | 响应头去重（RETAIN_FIRST 等） |
| SecureHeaders | 无 shortcut（enable/disable 列表） | 默认安全响应头全家桶 |
| RedirectTo | `RedirectTo=status,url[,includeRequestParams]` | 网关直接 3xx 重定向 |
| SetStatus | `SetStatus=status` | 改写响应状态码（可留原值头） |
| Retry | `Retry=retries,statuses,methods,backoff.firstBackoff,...` | 异常 retryWhen + 状态码 repeatWhen |
| CircuitBreaker | `CircuitBreaker=name`（fallbackUri/statusCodes 命名参数） | ReactiveCircuitBreaker 包链，fallback 内部转发 |
| RequestRateLimiter | 命名参数（keyResolver/rateLimiter/denyEmptyKey） | KeyResolver + RateLimiter 限流（默认 429） |
| ModifyRequestBody / ModifyResponseBody | 命名参数（inClass/outClass/rewriteFunction） | 缓冲-改写-换装（请求/响应） |
| RemoveJsonAttributesResponseBody | `=fieldList,deleteRecursively` | 删 JSON 响应字段 |
| CacheRequestBody | 命名参数（bodyClass） | 缓存请求体供复用 |
| LocalResponseCache | `LocalResponseCache=timeToLive,size` | Caffeine 缓存 GET 响应 |
| RequestSize / RequestHeaderSize | `=maxSize` | 413 / 431 体积防线 |
| SaveSession | `SaveSession`（无参） | 转发前强制保存 WebSession |
| TokenRelay | `TokenRelay[=clientRegistrationId]` | OAuth2 Bearer token 转发 |
| FallbackHeaders | `FallbackHeaders[=头名…]` | 熔断异常信息带给 fallback |
| JsonToGrpc | `JsonToGrpc=service,method,protoDescriptor` | JSON↔protobuf + gRPC 透传 |

## 5.10 本章小结

- **工厂与过滤器的分工**：工厂在路由翻译期把配置变实例（可做昂贵初始化），过滤器在请求期只做无状态加工。
- **改 URL 的纪律**：原 URL 追加进 `GATEWAY_ORIGINAL_REQUEST_URL_ATTR`，新 URL 写 `GATEWAY_REQUEST_URL_ATTR`；改响应的过滤器一律挂在 `chain.filter(...).then(...)`。
- **Retry 的关键机制**：迭代计数在 exchange 属性、重试前 `reset(exchange)`、超时预算含退避、依赖 enableBodyCaching 实现体可重读。
- **CircuitBreaker 的 fallback 是"内部转发"**：改 URL → reset → 交回 DispatcherHandler，不是二次 HTTP。
- **限流的两个默认值要背下来**：空 key 403、超限 429；Redis 挂了 fail-open。
- 一句话记住本章：**过滤器工厂 = "配置 → 闭包"的编译器，编译期一次，请求期 N 次。**


---

# 六、动态路由：来源、仓库与刷新机制

> 本章对应源码：`src/main/java/org/springframework/cloud/gateway/route/`、`.../event/`、`.../actuate/`、`.../config/`。

## 6.1 路由定义从哪里来

把 2.4 节的 Locator 家族按"配置从哪来"重新排一遍，就是 Gateway 动态路由的完整供给线：

| 来源 | 实现 | 何时更新 |
|---|---|---|
| YAML/properties | `PropertiesRouteDefinitionLocator`（读 `GatewayProperties.routes`，过滤 enabled=false） | 配置中心推送 → ContextRefresher 发 `RefreshScopeRefreshedEvent` |
| 运维 API | `InMemoryRouteDefinitionRepository`（Actuator POST 写入） | 调 API 即写内存，需手动 refresh |
| 外部存储 | 自定义 `RouteDefinitionRepository`（Redis 版内置：`RedisRouteDefinitionRepository`，`GatewayRedisAutoConfiguration:75` 开关） | 自己实现监听并发布事件 |
| 注册中心 | `DiscoveryClientRouteDefinitionLocator`（服务→模板路由） | 心跳事件触发 |
| Java/Kotlin DSL | 用户 `RouteLocator` bean（RouteLocatorBuilder） | @RefreshScope 重建 |

默认装配（`config/GatewayAutoConfiguration.java`）：`propertiesRouteDefinitionLocator`（:240）+ `inMemoryRouteDefinitionRepository`（:246，`@ConditionalOnMissingBean(RouteDefinitionRepository.class)`，可整体替换）→ `@Primary` 的 `CompositeRouteDefinitionLocator`（给缺 id 的定义补随机 UUID）→ `routeDefinitionRouteLocator`（:264）→ `@Primary` 的 `cachedCompositeRouteLocator = CachingRouteLocator(CompositeRouteLocator(所有 RouteLocator))`（:273-277）。

## 6.2 RouteDefinitionRepository：动态路由的 SPI

【源码证据】`src/main/java/org/springframework/cloud/gateway/route/`：

```java
// RouteDefinitionWriter.java:24-30
public interface RouteDefinitionWriter {

	Mono<Void> save(Mono<RouteDefinition> route);

	Mono<Void> delete(Mono<String> routeId);

}

// RouteDefinitionRepository.java:22-24
public interface RouteDefinitionRepository extends RouteDefinitionLocator, RouteDefinitionWriter {

}
```

读（Locator）+ 写（Writer）合一，是接入 Nacos/Apollo/数据库等外部路由存储的标准 SPI：实现 save/delete 落库、getRouteDefinitions 读出，再在自己的监听器里发布 `RefreshRoutesEvent` 即可接入刷新体系。默认的 InMemory 实现（`InMemoryRouteDefinitionRepository.java:33-63`）用 synchronized LinkedHashMap，delete 未命中抛 `NotFoundException`——**注意：不实现持久化，网关重启即丢**，所以生产上的"动态路由"通常以自定义 Repository + 配置中心为主、InMemory 仅作 Actuator 临时调整。

## 6.3 刷新事件链：RefreshRoutesEvent 的一生

刷新是 Gateway 动态性的心脏，全链路五站（各类行号均已核对）：

```
① 事件源                     ② RouteRefreshListener            ③ CachingRouteLocator
   配置中心刷新(ContextRefresher)  route/RouteRefreshListener.java:35    route/CachingRouteLocator.java:87
   Actuator POST /refresh        监听 4 类事件 → reset()                重译全部（或 scoped）路由
   服务发现心跳(HeartbeatEvent)     publish(RefreshRoutesEvent)           原子换缓存
   编程式 publisher.publishEvent                                        │
                                                                        ▼
                                            ④ FilteringWebHandler:91        ⑤ CorsGatewayFilterApplicationListener:66
                                               清路由过滤器缓存              监听 RefreshRoutesResultEvent 重载 CORS
```

【源码证据】`route/RouteRefreshListener.java` 第 47-77 行——它监听四类"上游事件"后统一发 `RefreshRoutesEvent`：

```java
	@Override
	public void onApplicationEvent(ApplicationEvent event) {
		if (event instanceof ContextRefreshedEvent refreshedEvent) {
			boolean isManagementCtxt = WebServerApplicationContext
				.hasServerNamespace(refreshedEvent.getApplicationContext(), "management");
			...
			if (!isManagementCtxt && !isLoadBalancerCtxt) {
				reset();
			}
		}
		else if (event instanceof RefreshScopeRefreshedEvent || event instanceof InstanceRegisteredEvent) {
			reset();
		}
		else if (event instanceof ParentHeartbeatEvent parentHeartbeatEvent) {
			resetIfNeeded(parentHeartbeatEvent.getValue());
		}
		else if (event instanceof HeartbeatEvent heartbeatEvent) {
			resetIfNeeded(heartbeatEvent.getValue());
		}
	}
```

`reset()` 就是 `publishEvent(new RefreshRoutesEvent(this))`（第 73-75 行）。两个细节：ContextRefreshedEvent 会**排除 management 与 LoadBalancer 子上下文**（否则管理端口的子容器刷新会把主容器路由重刷一遍）；心跳事件经 `HeartbeatMonitor` 判变（`resetIfNeeded`），注册中心无变化不刷。

消费端 `CachingRouteLocator.onApplicationEvent`（2.6 节已展开）重译后发 `RefreshRoutesResultEvent`（携带成功/失败），完成闭环。自定义存储的用户只需照抄 RouteRefreshListener 的姿势，在收到配置中心推送时发 `RefreshRoutesEvent` 即可。

## 6.4 Actuator 运维端点：/actuator/gateway

【源码证据】`actuate/AbstractGatewayControllerEndpoint.java` 与 `actuate/GatewayControllerEndpoint.java`——注意它是 **`@RestControllerEndpoint(id = "gateway", defaultAccess = Access.NONE)`**（GatewayControllerEndpoint.java:47-48）：默认**不可访问**，需在 management 端点配置里显式打开。方法全清单：

| HTTP | 路径 | 方法（行号） | 作用 |
|---|---|---|---|
| GET | `/actuator/gateway/globalfilters` | Abstract:193 | 列出全局过滤器名+order |
| GET | `/actuator/gateway/routefilters` | Abstract:198 | 列出过滤器工厂名 |
| GET | `/actuator/gateway/routepredicates` | Abstract:203 | 列出断言工厂名 |
| GET | `/actuator/gateway/routes` | GatewayController:64 | 运行态路由（含合并后的过滤器） |
| GET | `/actuator/gateway/routedefinitions` | GatewayController:58 | 配置态路由定义 |
| GET | `/actuator/gateway/routes/{id}` | GatewayController:90 | 单条路由 |
| POST | `/actuator/gateway/routes/{id}` | Abstract:227 | **新增/覆盖路由定义**（写入 RouteDefinitionWriter） |
| POST | `/actuator/gateway/routes` | Abstract:241 | 批量新增（须带 id） |
| DELETE | `/actuator/gateway/routes/{id}` | Abstract:317 | 删除（发 RouteDeletedEvent） |
| POST | `/actuator/gateway/refresh` | Abstract:169 | 发布 RefreshRoutesEvent（支持 metadata scoped） |
| GET | `/actuator/gateway/routes/{id}/combinedfilters` | Abstract:325 | 查某路由合并后的过滤器链 |

POST 保存的实现（第 227-239 行）——写入 InMemory 仓库，**还要手动 POST refresh 才生效**（除非配路由定义缓存 TTL 轮询刷新）：

```java
	@PostMapping("/routes/{id}")
	@SuppressWarnings("unchecked")
	public Mono<ResponseEntity<Object>> save(@PathVariable String id, @RequestBody RouteDefinition route) {

		return Mono.just(route)
			.doOnNext(this::validateRouteDefinition)
			.flatMap(routeDefinition -> this.routeDefinitionWriter.save(Mono.just(routeDefinition).map(r -> {
				r.setId(id);
				log.debug("Saving route: " + r);
				return r;
			})).then(Mono.defer(() -> Mono.just(ResponseEntity.created(URI.create("/routes/" + id)).build()))))
			.switchIfEmpty(Mono.defer(() -> Mono.just(ResponseEntity.badRequest().build())));
	}
```

`validateRouteDefinition`（第 264-315 行）会在保存时就校验：uri 非空且有 scheme、filters/predicates 的名字能在已注册工厂里找到——把坏配置挡在写入时。

## 6.5 本章小结

- **四类配方来源 + 一个 SPI**：YAML、Actuator API、自定义 Repository、注册中心，全部汇入 Composite；接入新存储只需实现 `RouteDefinitionRepository` + 在变化时发 `RefreshRoutesEvent`。
- **刷新链五站**：事件源 → RouteRefreshListener（排除子上下文、心跳判变）→ CachingRouteLocator 重译换缓存 → FilteringWebHandler 清过滤器缓存 → 结果事件供 CORS 等下游跟进。
- **Actuator 端点是"写 InMemory + 手动 refresh"的临时通道**，默认不可访问；生产动态路由应落在持久化 Repository 上。
- 一句话记住本章：**动态路由 = 改配方 + 发 RefreshRoutesEvent 两步；其余（重译、换缓存、清过滤器缓存）全是框架自动的。**

# 七、自动配置与扩展点

> 本章对应源码：`src/main/java/org/springframework/cloud/gateway/config/`、`.../actuate/`、`.../support/tagsprovider/`。

## 7.1 启动装配全景：13 个自动配置

【源码证据】`src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（全文 13 行）：

```
org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration
org.springframework.cloud.gateway.config.GatewayAutoConfiguration
org.springframework.cloud.gateway.config.GatewayResilience4JCircuitBreakerAutoConfiguration
org.springframework.cloud.gateway.config.GatewayNoLoadBalancerClientAutoConfiguration
org.springframework.cloud.gateway.config.GatewayFunctionAutoConfiguration
org.springframework.cloud.gateway.config.GatewayMetricsAutoConfiguration
org.springframework.cloud.gateway.config.GatewayRedisAutoConfiguration
org.springframework.cloud.gateway.config.GatewayStreamAutoConfiguration
org.springframework.cloud.gateway.discovery.GatewayDiscoveryClientAutoConfiguration
org.springframework.cloud.gateway.config.SimpleUrlHandlerMappingGlobalCorsAutoConfiguration
org.springframework.cloud.gateway.config.GatewayReactiveLoadBalancerClientAutoConfiguration
org.springframework.cloud.gateway.config.LocalResponseCacheAutoConfiguration
org.springframework.cloud.gateway.config.GatewayTracingAutoConfiguration
```

| 自动配置 | 职责 | 激活条件（一句话） |
|---|---|---|
| GatewayClassPathWarningAutoConfiguration | servlet 环境或缺 DispatcherHandler 时打告警 | 类路径探测 |
| **GatewayAutoConfiguration** | 核心装配：属性 bean、Locators、两个 handler、全部工厂、Netty 配置类、Actuator 配置类 | `spring.cloud.gateway.server.webflux.enabled`（默认 true）+ 有 DispatcherHandler |
| GatewayResilience4JCircuitBreakerAutoConfiguration | 注册 Resilience4J 版熔断工厂 | 有 ReactiveResilience4JCircuitBreakerFactory bean |
| GatewayNoLoadBalancerClientAutoConfiguration | 无 LB 时注册 NoLoadBalancerClientFilter 兜底 | **没有** ReactorLoadBalancer |
| GatewayFunctionAutoConfiguration / GatewayStreamAutoConfiguration | `fn:` / `stream:` 路由过滤器 | 有 FunctionCatalog / StreamBridge |
| GatewayRedisAutoConfiguration | RedisRateLimiter + Redis 路由仓库 | 有 ReactiveRedisTemplate |
| GatewayDiscoveryClientAutoConfiguration | 服务发现自动路由（默认模板在 `initPredicates/initFilters`，:55-79） | `discovery.locator.enabled=true` |
| SimpleUrlHandlerMappingGlobalCorsAutoConfiguration | 给 SimpleUrlHandlerMapping 加全局 CORS | `globalcors.add-to-simple-url-handler-mapping` |
| GatewayReactiveLoadBalancerClientAutoConfiguration | ReactiveLoadBalancerClientFilter + 实例 Cookie 过滤器 | 有 LoadBalancerClientFactory |
| LocalResponseCacheAutoConfiguration | 响应缓存过滤器（含全局兜底） | 有 Caffeine + 开关 |
| GatewayMetricsAutoConfiguration | 指标过滤器 + tags provider + Observation 配置类 | 有 MeterRegistry |

【源码证据】`config/GatewayAutoConfiguration.java:214-221`（类头）——总开关与装配顺序：

```java
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.cloud.gateway.server.webflux.enabled", matchIfMissing = true)
@EnableConfigurationProperties
@AutoConfigureBefore({ HttpHandlerAutoConfiguration.class, WebFluxAutoConfiguration.class })
@AutoConfigureAfter({ GatewayReactiveLoadBalancerClientAutoConfiguration.class,
		GatewayClassPathWarningAutoConfiguration.class })
@ConditionalOnClass(DispatcherHandler.class)
public class GatewayAutoConfiguration {
```

它要装配的 bean 可分四组（行号均实测）：① **基础设施**（:223-236）：时间转换器、`RouteLocatorBuilder`；② **路由供给线**（:238-285）：PropertiesRouteDefinitionLocator → InMemoryRouteDefinitionRepository → CompositeRouteDefinitionLocator → ConfigurationService → RouteDefinitionRouteLocator → **CachingRouteLocator** → RouteRefreshListener；③ **处理链**（:287-316）：`FilteringWebHandler(globalFilters, routeFilterCacheEnabled)` → `RoutePredicateHandlerMapping`（可选注入 `ApiVersionStrategy`）；④ **全部工厂与 GlobalFilter**（:413-784，每个都挂 `@ConditionalOnEnabledXxx`，见 7.2 节）+ Netty 配置类（:840-945，HttpClientFactory、NettyRoutingFilter/NettyWriteResponseFilter、WebSocket 客户端）+ Actuator 配置类（:947-989）。

## 7.2 组件开关：@ConditionalOnEnabledGlobalFilter / Filter / Predicate

Gateway 把"禁用某个内置组件"做成了注解驱动的条件装配。

【源码证据】`config/conditional/` 包（7 个文件）。条件基类 `OnEnabledComponent.java:35-39` 定义属性名模板，判定逻辑在第 75-82 行：

```java
	private ConditionOutcome determineOutcome(Class<? extends T> componentClass, PropertyResolver resolver) {
		String key = PREFIX + normalizeComponentName(componentClass) + SUFFIX;
		ConditionMessage.Builder messageBuilder = forCondition(annotationClass().getName(), componentClass.getName());
		if ("false".equalsIgnoreCase(resolver.getProperty(key))) {
			return ConditionOutcome.noMatch(messageBuilder.because("bean is not available"));
		}
		return ConditionOutcome.match();
	}
```

三个子类的属性名格式（normalizeComponentName，各文件 24-35 行）：

| 注解 | 属性名模板 | 示例 |
|---|---|---|
| `@ConditionalOnEnabledGlobalFilter` | `spring.cloud.gateway.server.webflux.global-filter.<name>.enabled` | `...global-filter.netty-routing.enabled=false` |
| `@ConditionalOnEnabledFilter`（过滤器工厂） | `...filter.<name>.enabled` | `...filter.retry.enabled=false` |
| `@ConditionalOnEnabledPredicate` | `...predicate.<name>.enabled` | `...predicate.after.enabled=false` |

关键细节：**只有显式配成字符串 `"false"` 才禁用**（`"false".equalsIgnoreCase`），其他值（包括不配）都算开启；注解 value 省略时从 `@Bean` 方法返回类型反射推导类名（OnEnabledComponent.java:62-68）。全部属性名在 `spring-cloud-gateway-server/src/main/resources/META-INF/additional-spring-configuration-metadata.json`（约 97KB，IDE 提示数据，无代码）。

这套机制的用法记忆：**排障时怀疑某个内置过滤器捣乱，一行配置就能精准摘除它，而不必替换 bean**。

## 7.3 底层 HTTP 客户端：HttpClientFactory 与 HttpClientProperties

【源码证据】`config/HttpClientProperties.java`（586 行，前缀 `spring.cloud.gateway.server.webflux.httpclient`，:35）。关键字段分组（行号）：

| 分组 | 字段 | 说明 |
|---|---|---|
| 超时 | connectTimeout(:40，默认 30s)、responseTimeout(:43) | 路由 metadata 可按条覆盖（3.6.5 节） |
| 连接池 Pool(:52) | type(:173，**默认 ELASTIC**/FIXED/DISABLED)、maxConnections、acquireTimeout、maxIdleTime/maxLifeTime、evictionInterval、metrics、leasingStrategy(FIFO/LIFO) | 池指标可进 Micrometer |
| 代理 Proxy(:55) | type(HTTP/socks4/socks5)、host/port、username/password、nonProxyHostsPattern | |
| SSL Ssl(:58) | useInsecureTrustManager、trustedX509Certificates、**sslBundle**（Boot SSL bundle）、握手超时、keystore 族 | |
| 压缩/调试 | compression(:67)、wiretap(:64)、websocket.maxFramePayloadLength(:556) | |

构建过程在 `config/HttpClientFactory.java:80-111`（`createInstance`，摘录见 3.6.5 节）：`HttpClient.create(connectionProvider)` 起步，依次叠加 HTTP/2、连接超时、代理、SSL、wiretap、压缩，最后 `applyCustomizers`——**用户可以注册 `HttpClientCustomizer` bean 在链尾做最后定制**，这是调 Netty 底层参数的正规入口。

## 7.4 可观测性：Metrics / Observation / Tracing

**指标（Micrometer MeterRegistry）**：`filter/GatewayMetricsFilter.java:38`（order=0，即 `WRITE_RESPONSE_FILTER_ORDER+1`——**链前开始计时，响应提交前一刻停止**，`endTimerRespectingCommit` 第 80-92 行处理"响应未提交"的场景挂 beforeCommit）：

```java
	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		Sample sample = Timer.start(meterRegistry);

		return chain.filter(exchange)
			.doOnSuccess(aVoid -> endTimerRespectingCommit(exchange, sample))
			.doOnError(throwable -> endTimerRespectingCommit(exchange, sample));
	}
```

指标名 `<prefix>.requests`（prefix 默认 `spring.cloud.gateway`，`GatewayMetricsProperties.java:38`），tags 由 `GatewayTagsProvider` 链合成（`support/tagsprovider/`）：`GatewayHttpTagsProvider`（outcome/status/httpStatusCode/httpMethod）、`GatewayRouteTagsProvider`（**routeId/routeUri**——按路由聚合延迟的来源）、`GatewayPathTagsProvider`（matched path）、`PropertiesTagsProvider`（配置静态 tags）。另有 `RouteDefinitionMetrics`（`route/RouteDefinitionMetrics.java:54`，gauge `<prefix>.routes.count`）。

**Observation（Micrometer 观测 API）**：`filter/headers/observation/` 包用两个 HttpHeadersFilter 夹住一次转发——`ObservedRequestHttpHeadersFilter`（REQUEST 阶段启动 `GATEWAY_HTTP_CLIENT_OBSERVATION`，观测名 `http.client.requests`，父 Observation 从 reactor context 取，第 60-81 行）与 `ObservedResponseHttpHeadersFilter`（RESPONSE 阶段 stop）；异常路径由 `ObservationClosingWebExceptionHandler` 兜底关闭。低基维度：`spring.cloud.gateway.route.id`、`http.method`、`http.status_code`、`spring.cloud.gateway.route.uri`（`GatewayDocumentedObservation.java:49-94`）。

**Tracing（micrometer-tracing）**：`config/GatewayTracingAutoConfiguration.java:43-49` 在有 Tracer/Propagator 时注册 `GatewayPropagatingSenderTracingObservationHandler`——把 W3C TraceContext 注入**发往下游的请求头**（这是"网关是 trace 链上的一跳"的实现）；baggage remote-fields 按配置传播。

三者总开关都在 `spring.cloud.gateway.server.webflux.metrics.*` / `.observability.*`，默认开启。

## 7.5 扩展点清单：五种定制姿势

按介入深度从浅到深：

1. **自定义断言工厂**：继承 `AbstractRoutePredicateFactory<MyConfig>`，实现 `apply()` 与 `shortcutFieldOrder()`，注册为 bean 即自动进入路由翻译器（YAML 里写类名短名即可）。
2. **自定义过滤器工厂**：继承 `AbstractGatewayFilterFactory<MyConfig>`（或 NameValue 基类），同上；改转发 URI 的用 `AbstractChangeRequestUriGatewayFilterFactory`。
3. **自定义 GlobalFilter**：实现 `GlobalFilter + Ordered` 注册为 bean——它会被 FilteringWebHandler 自动收进链并排序（注意与路由级过滤器的 order 错开，参考 3.6 节总表的取值习惯：改写类 < 10000，路由类 > 10000，出网类最大）。
4. **自定义 RouteDefinitionRepository**：实现 `RouteDefinitionRepository`（save/delete/getRouteDefinitions），注册为 bean 后自动替换 InMemory（`@ConditionalOnMissingBean(RouteDefinitionRepository.class)`）；配套在配置推送时发布 `RefreshRoutesEvent`。
5. **HttpClientCustomizer**：对 Reactor Netty HttpClient 做"工厂不管的"底层定制（EventLoopGroup、日志、池参数微调）。

另有两个冷门但好用的口子：`GatewayMetricsProperties.tags` + 自定义 `GatewayTagsProvider` bean 扩展指标维度；路由 metadata（`RouteDefinition.metadata`）传递自定义参数（connect-timeout/response-timeout 是内置消费者，见 `support/RouteMetadataUtils.java`）。

## 7.6 本章小结

- **一个 GatewayAutoConfiguration 装配了网关的一切**，12 个外围自动配置按 classpath 条件补齐能力——这也是主模块"核心依赖只有 starter+validation、其余全 optional"的原因。
- **开关体系**：`@ConditionalOnEnabledGlobalFilter/Filter/Predicate` 三个注解 + `spring.cloud.gateway.server.webflux.{global-filter|filter|predicate}.<name>.enabled=false` 一行配置精准禁用。
- **HttpClientFactory 是 Netty HttpClient 的唯一产地**，池/代理/SSL/HTTP2 全配置化，尾巴留了 Customizer。
- **可观测三件套**：MetricsFilter（按 routeId 打 tags 的 timer）、Observation（`http.client.requests`）、Tracing handler（trace 传播到下游）。
- 一句话记住本章：**Gateway 的"配置面"全部是 Boot 标准姿势（条件装配 + ConfigurationProperties），没有发明私有机制。**


---

# 八、双栈与生态：Server WebMVC、ProxyExchange、集成测试

> 本章对应源码：`spring-cloud-gateway-server-webmvc/`、`spring-cloud-gateway-proxyexchange-webflux/`、`-webmvc/`、`spring-cloud-gateway-sample/`、`spring-cloud-gateway-integration-tests/`。

## 8.1 Server WebMVC：把网关映射到 MVC 函数式端点

不是所有团队都愿意上响应式栈。Server WebMVC（v4.1 引入 server-mvc，v4.3 改名 server-webmvc）用 **Spring MVC 的函数式端点（RouterFunction）** 实现了同样的"Route/Predicate/Filter"概念——概念同构，实现完全不同：

| 概念 | WebFlux 版 | WebMVC 版 |
|---|---|---|
| 路由载体 | `Route`（自建模型）+ `RoutePredicateHandlerMapping` | `RouterFunction`（MVC 标准）+ `RouterFunctionMapping` |
| 断言 | `RoutePredicateFactory` 工厂体系 | `RequestPredicate` + 静态方法集 `GatewayRequestPredicates`（path/host/after/weight/readBody…，类文件第 85-257 行一段一个方法） |
| 路由级过滤器 | `GatewayFilterFactory` 工厂体系 | `HandlerFilterFunction` 静态方法集（`FilterFunctions/BeforeFilterFunctions/AfterFilterFunctions` 三个聚合类 + `CircuitBreakerFilterFunctions` 等） |
| 全局过滤器 | `GlobalFilter` 责任链 | **没有链**：HTTP 头过滤用 `RequestHttpHeadersFilter/ResponseHttpHeadersFilter` bean 聚合（Forwarded/X-Forwarded/RemoveHopByHop 等，ProxyExchangeHandlerFunction 收集应用），横切动作用 servlet Filter（FormFilter、WeightCalculatorFilter） |
| 出网 | `NettyRoutingFilter`（Reactor Netty） | **`ProxyExchangeHandlerFunction` → `RestClientProxyExchange`（RestClient）**，没有独立"路由过滤器" |
| 动态路由 | RouteDefinitionRepository + RefreshRoutesEvent | YAML 路由打包进 **refresh scope 的 RouterFunctionHolder**（`GatewayMvcPropertiesBeanDefinitionRegistrar.java:55-78`），@RefreshScope 刷新整体重建；无 Actuator 端点 |

【源码证据】代理转发的核心 `handler/RestClientProxyExchange.java:43-52`——请求体从 servlet 输入流**流式拷贝**（不整块进内存）：

```java
	@Override
	public ServerResponse exchange(Request request) {
		Objects.requireNonNull(request.getUri(), "uri cannot be null");
		RestClient.RequestBodySpec requestSpec = restClient.method(request.getMethod())
			.uri(request.getUri())
			.headers(httpHeaders -> httpHeaders.putAll(request.getHeaders()));
		if (isBodyPresent(request)) {
			requestSpec.body(outputStream -> copyBody(request, outputStream));
		}
		return requestSpec.exchange((clientRequest, clientResponse) -> doExchange(request, clientResponse), false);
	}
```

响应侧（`doExchange`，第 67-98 行）把响应流放进 request attribute（`CLIENT_RESPONSE_INPUT_STREAM_ATTR`，允许过滤器替换），由 `AbstractProxyExchange.copyResponseBody` 延迟写出——`streamingMediaTypes`（默认 text/event-stream）逐块 flush，其余 `StreamUtils.copy` 一次性拷贝。还有一个重要默认值：`GatewayHttpClientEnvironmentPostProcessor`（`GatewayServerMvcAutoConfiguration.java:270-287`）把 Boot RestClient 的**重定向默认改成不跟随**（`spring.http.client.redirects=DONT_FOLLOW`）——代理网关必须把 3xx 原样透传给客户端，这个语义靠环境后置处理器兜底。

YAML 路由的翻译在 `config/RouterFunctionHolderFactory.java`：predicates/filters 的名字经 `HandlerDiscoverer/FilterDiscoverer` 反射找到 `@Shortcut` 注解的静态方法并调用（第 268-304 行），多个断言 `RequestPredicate.and` 归约——与 WebFlux 版的 shortcut 机制（2.5.1）殊途同归，但底层是"方法反射调用"而非"配置类 Binder"。

## 8.2 双栈能力对照表

WebMVC 版明确**没有**的能力（grep 全模块零命中）：

| 能力 | WebFlux | WebMVC |
|---|---|---|
| WebSocket 转发（WebsocketRoutingFilter） | ✅ | ❌ |
| Redis 令牌桶限流（RedisRateLimiter） | ✅ | ❌（只有 Bucket4j，且同步阻塞调用） |
| gRPC/JSON 转换（JsonToGrpc） | ✅ | ❌ |
| Actuator 网关端点 | ✅ | ❌ |
| 指标 / Observation / Tracing | ✅ | ❌ |
| 服务发现自动路由（DiscoveryClientRouteDefinitionLocator） | ✅ | ❌（`lb:` 仍可用，路由要显式配） |
| 本地响应缓存（LocalResponseCache） | ✅ | ❌ |
| 动态路由 API / RouteDefinitionRepository | ✅ | ❌ |
| Retry 实现 | Reactor retryWhen（一个） | **三选一自动切换**：spring-retry 版 / Boot 框架重试版 / 内置（`RetryFilterFunctions.java:45-95` 按 classpath 与 `use-framework-retry-filter` 决定） |

WebMVC 版**独有**：`GatewayMvcMultipartResolver`（懒解析 multipart，覆盖 DispatcherServlet 的标准 bean 名）、`FormFilter`（servlet 会把 query 与 form 参数混在一起，代理前要重建 form body）、`ClientHttpRequestFactoryProxyExchange`（不经 RestClient 的备选转发实现）。

**选型结论**：要 WebSocket、服务发现、动态路由、可观测——WebFlux 版；只想在 MVC 单体里加个简单代理、团队不写 Reactor——WebMVC 版够用。

## 8.3 ProxyExchange：Controller 里的迷你代理

`proxyexchange-webmvc` / `proxyexchange-webflux` 是与网关 server **完全独立**的小模块（pom 不依赖任何 server 模块）：给普通 `@Controller` 方法注入一个 `ProxyExchange<T>` 参数，手动转发单个请求。

【源码证据】`spring-cloud-gateway-proxyexchange-webmvc/src/main/java/org/springframework/cloud/gateway/mvc/ProxyExchange.java:77-89`（Javadoc 自带示例）与 `:404-415`（核心 exchange）：

```java
	 * &#64;GetMapping("/proxy/{id}")
	 * public ResponseEntity&lt;?&gt; proxy(@PathVariable Integer id, ProxyExchange&lt;?&gt; proxy)
	 * 		throws Exception {
	 * 	return proxy.uri("http://localhost:9000/foos/" + id).get();
	 * }
```

```java
	private ResponseEntity<T> exchange(RequestEntity<?> requestEntity) {
		Type type = this.responseType;
		if (type instanceof TypeVariable || type instanceof WildcardType) {
			type = Object.class;
		}
		if (this.uriTemplate != null && this.uriVariables != null) {
			return rest.exchange(this.uriTemplate, Objects.requireNonNull(requestEntity.getMethod()),
					new HttpEntity<>(requestEntity.getBody(), requestEntity.getHeaders()),
					ParameterizedTypeReference.forType(type), this.uriVariables);
		}
		return rest.exchange(requestEntity, ParameterizedTypeReference.forType(type));
	}
```

MVC 版基于 **RestTemplate**（自动装配 `ProxyResponseAutoConfiguration.java:56-95` 注册参数解析器，配 `NoOpResponseErrorHandler` 把非 2xx 原样透传），WebFlux 版基于 **WebClient**（响应是 `Mono<ResponseEntity<T>>`）。适合"网关不掺和、单个 Controller 想借道转发"的场景；`forward(path)` 方法则是容器内 servlet 转发。

## 8.4 sample 与 integration-tests

**sample**（`spring-cloud-gateway-sample/`）是最小可运行网关：pom = actuator + webflux starter + gateway starter（再次印证 starter 之外要自配 actuator）；`application.yml` 演示 YAML 路由（websocket_test、default_path_to_httpbin）、default-filters（`PrefixPath=/httpbin`）、全量暴露 actuator；主类 `GatewaySampleApplication.java:57-157` 用 DSL 定义了 10 条演示路由（host+path、readBody、modifyRequestBody 等）。

**integration-tests**（聚合 4 个重型子模块）：`grpc`（gRPC/JSON→gRPC 代理）、`http2`（TLS + h2c 经网关）、`httpclient`（自定义 ClientHttpRequestFactory）、`mvc-failure-analyzer`（验证响应式网关遇到 spring-webmvc 时的人话报错）。常规单测在各 server 模块内（test 依赖含 Testcontainers、BlockHound——官方连"阻塞调用混进响应式链路"都在 CI 里盯）。

## 8.5 本章小结

- **Server WebMVC = 概念移植，不是移植实现**：Route/Predicate/Filter 映射到 RouterFunction/RequestPredicate/HandlerFilterFunction，出网用 RestClient，动态性靠 refresh scope；WebSocket/服务发现路由/可观测等响应式专属能力缺席。
- **ProxyExchange 与网关无关**：Controller 参数注入式的单次转发，MVC 用 RestTemplate、WebFlux 用 WebClient。
- 一句话记住本章：**同一个网关概念，两套宿主——响应式栈是"亲儿子"，MVC 栈是"能用但瘦身"的兄弟。**

# 九、贯通视图：三条时间线看懂 Gateway 全貌

## 9.1 时间线一：应用启动（自动配置 → 路由构建与缓存）

```
SpringApplication.run
 └─ 自动配置（.imports 13 个，7.1 节）
     ├─ GatewayAutoConfiguration
     │    ├─ GatewayProperties 绑定（spring.cloud.gateway.server.webflux.*）
     │    ├─ PropertiesRouteDefinitionLocator + InMemoryRouteDefinitionRepository
     │    ├─ CompositeRouteDefinitionLocator（@Primary，聚合一切配方来源）
     │    ├─ RouteDefinitionRouteLocator（收集全部 predicate/filter 工厂建 Map）
     │    ├─ cachedCompositeRouteLocator = CachingRouteLocator(CompositeRouteLocator(...))
     │    │     ★ 此刻路由尚未翻译！CachingRouteLocator 是懒加载：
     │    │       首次 getRoutes() 才 fetch → convertToRoute → 按 order 排序入缓存
     │    ├─ FilteringWebHandler（收集全部 GlobalFilter，loadFilters 适配+排序）
     │    ├─ RoutePredicateHandlerMapping（order=1）
     │    └─ HttpClientFactory → Reactor Netty HttpClient（单例）
     ├─ GatewayReactiveLoadBalancerClientAutoConfiguration（有 LB 时注册 10150 号过滤器）
     ├─ GatewayMetricsAutoConfiguration / GatewayTracingAutoConfiguration ...
     └─ RouteRefreshListener 就位（监听 ContextRefreshed 等 → RefreshRoutesEvent）
```

要点：**启动期不做路由翻译**——`CachingRouteLocator` 的 Flux 常驻、miss 才 fetch（2.6 节），第一条请求触发首次翻译；坏配置若配了 `failOnRouteDefinitionError=true`（默认）会在首次翻译时抛错。

## 9.2 时间线二：一条路由的一生（YAML → 刷新 → 淘汰）

```
yaml: routes[0].id=demo, predicates=[Path=/demo/**], filters=[StripPrefix=1]
 1. Boot Binder 把 routes 绑成 List<RouteDefinition>（GatewayProperties）
 2. PropertiesRouteDefinitionLocator 输出配方（enabled=false 的直接丢弃）
 3. CompositeRouteDefinitionLocator 汇聚（缺 id 补 UUID）
 4. RouteDefinitionRouteLocator.convertToRoute
      ├ combinePredicates：Path=… → lookup("Path") → ConfigurationService 绑定 → PathRoutePredicateFactory.apply
      ├ getFilters：default-filters 先、路由 filters 后 → loadGatewayFilters（i+1 兜底 order）→ sort
      └ Route.async(rd).asyncPredicate(...).replaceFilters(...).build()   ← 成品
 5. 入 CachingRouteLocator 缓存（按 Route order 排序）
 6. （配置变更）事件源 → RouteRefreshListener → RefreshRoutesEvent
      → CachingRouteLocator 重译全量/scoped → 原子换缓存 → RefreshRoutesResultEvent
      → FilteringWebHandler 清过滤器缓存；CORS 监听器重载
 7. （删除/下线）配方消失 → 下次刷新时新缓存里自然没有它
```

要点：**路由没有"原地修改"**——任何变更都是"新配方整体重译 + 缓存原子替换"，正在飞行中的请求仍握着旧 Route 对象跑完，天然安全。

## 9.3 时间线三：一次 HTTP 请求（叠加前两条线）

以 `GET /api/order/1`、路由 `lb://order-service`、filters `[StripPrefix=1]` 为例：

```
1. Reactor Netty 收请求 → DispatcherHandler.handle
2. RoutePredicateHandlerMapping.getHandlerInternal（order=1）
     ├ 写 GATEWAY_HANDLER_MAPPER_ATTR / GATEWAY_REACTOR_CONTEXT_ATTR
     ├ lookupRoute：读 CachingRouteLocator 缓存 → filterWhen 逐条断言
     │    Path 匹配 /api/order/1 ✅（{segment} 变量进 URI_TEMPLATE_VARIABLES_ATTRIBUTE）
     └ 写 GATEWAY_ROUTE_ATTR=demo，返回 FilteringWebHandler
3. FilteringWebHandler.handle：globalFilters + route.filters(StripPrefix) 合并排序 → DefaultGatewayFilterChain
4. 链（按 order）：
     RemoveCachedBodyFilter(-MAX)        doFinally 兜底清体
     AdaptCachedBodyGlobalFilter(-MAX+1000)  （无读体路由则直通）
     GlobalLocalResponseCacheGatewayFilter(-3)（未启用则不注册）
     NettyWriteResponseFilter(-1)        ★只注册 then 段：等连接就位后写响应
     GatewayMetricsFilter(0)             开始计时
     ForwardPathFilter(0)                scheme 非 forward 直通
     [路由级] StripPrefix(1)             /api/order/1 → /order/1，写 GATEWAY_REQUEST_URL_ATTR
     RouteToRequestUrlFilter(10000)      换头不换尾：scheme/host/port ← lb://order-service
     ReactiveLoadBalancerClientFilter(10150)  choose(order-service) → http://10.1.2.3:8080
                                          （无实例 → NotFoundException 503/404）
     LoadBalancerServiceInstanceCookieFilter(10151)（可选粘性 cookie）
     WebsocketRoutingFilter(MAX-1)       非 ws 直通
     NettyRoutingFilter(MAX)             isAlreadyRouted? no → setAlreadyRouted
                                          filterRequest（X-Forwarded-* 加入）
                                          client.send(...) → 响应元数据进 CLIENT_RESPONSE_*
                                          return responseFlux.then(chain.filter(exchange))
                                          → 链到头 Mono.empty() → 后段 then 依次回卷
     NettyWriteResponseFilter 的 then 段  从 connection.inbound() 收 body
                                          → writeWith / writeAndFlushWith 写回客户端
5. GatewayMetricsFilter 响应提交前停止计时（gateway.requests + routeId tags）
6. RemoveCachedBodyFilter 的 doFinally 释放缓存体；连接由 Netty 池回收
```

要点：**匹配一次（读缓存）、翻译零次、出网一次、写回接力一次**；任何一步抛错，异常沿 Mono 上抛进 WebExceptionHandler，MetricsFilter 的 doOnError 照样记录。

## 9.4 从源码中提炼的四个设计模式视角

1. **责任链 + 适配器**：GatewayFilterChain 是索引推进的责任链；GatewayFilterAdapter 把 GlobalFilter 适配进同一条链——两种"过滤器"一个待遇。
2. **策略 + 工厂 + 注册表**：断言/过滤器工厂都是"名字 → 策略实例"的注册表（`factory.name()` 为 key），YAML 里的字符串名就是策略选择器；新增策略=新增 bean，核心零改动。
3. **备忘录/不可变快照**：Route 不可变、刷新是整体换缓存（copy-on-write 的事件驱动版），读多写少的场景没有锁竞争。
4. **黑板模式**：`ServerWebExchange.attributes` 是请求级黑板，20+ 个约定 key 让互相独立的过滤器协作——代价是"隐式契约"（改名/漏写不报编译错，所以常量都集中在 ServerWebExchangeUtils 一个类里管）。

---

# 十、附录

## 10.1 关键接口速查表

| 接口/类 | 一句话契约 | 关键方法 |
|---|---|---|
| `RouteLocator` | 生产运行态路由 | `Flux<Route> getRoutes()` |
| `RouteDefinitionLocator` | 生产路由配方 | `Flux<RouteDefinition> getRouteDefinitions()` |
| `RouteDefinitionWriter` | 写配方 | `save / delete` |
| `RouteDefinitionRepository` | 读+写配方（动态路由 SPI） | 继承上两者 |
| `RoutePredicateFactory<C>` | 配置→谓词 | `apply(C)` / `applyAsync(C)` / `name()` / `shortcutFieldOrder()` |
| `GatewayFilterFactory<C>` | 配置→过滤器 | `apply(C)` / `name()` / `shortcutFieldOrder()` |
| `GatewayFilter` | 路由级过滤器契约 | `filter(exchange, chain)`（`@since 5.0`，不再继承 WebFilter） |
| `GlobalFilter` | "命中路由才执行"的过滤器标记 | 同上签名 |
| `GatewayFilterChain` | 链推进 | `filter(exchange)` |
| `AsyncPredicate<T>` | 响应式谓词 + 组合子 | `apply(T) → Publisher<Boolean>`、and/or/negate |
| `HttpHeadersFilter` | 头过滤（Request/Response 两态） | `filter(input, exchange)`、`supports(type)` |
| `RateLimiter<C>` / `KeyResolver` | 限流算法 / 限流 key | `isAllowed(routeId, id)` / `resolve(exchange)` |
| `ConfigurationService` | 配置绑定服务（Boot Binder 封装） | `with(factory).properties(args).bind()` |
| `ServerWebExchangeUtils` | exchange 属性常量与工具 | `setAlreadyRouted` / `reset` / `cacheRequestBody` |

## 10.2 初学者学习路线（动手向）

1. **跑 sample**：导入 `spring-cloud-gateway-sample`，开 trace 日志（`org.springframework.cloud.gateway: TRACE`），发一个请求看控制台的"Matched Route / Sorted gatewayFilterFactories / outbound route"三段日志——这是理解链路最直观的方式。
2. **加一条自定义路由**：YAML 写 Path + StripPrefix，打到 httpbin；再开 `discovery.locator.enabled` 对比自动路由。
3. **写一个过滤器工厂**：实现"给请求加耗时开始时间戳、给响应加 X-Elapsed 头"的工厂（用 `then` 段），挂到 default-filters。
4. **写一个 GlobalFilter**：打 order=5000，读 `GATEWAY_ROUTE_ATTR` 打日志，体会"全局 vs 路由级"的合并排序。
5. **玩刷新**：Actuator POST 一条路由 → 查 `/actuator/gateway/routes`（注意要再 POST refresh）→ 在 `CachingRouteLocator.onApplicationEvent` 打断点看重译过程。
6. **看一次完整转发**：断点串读 `RoutePredicateHandlerMapping.getHandlerInternal → FilteringWebHandler.handle → DefaultGatewayFilterChain.filter → NettyRoutingFilter.filter → NettyWriteResponseFilter.then 段`，对照 9.3 节时间线。
7. **进阶**：实现 RouteDefinitionRepository 接 Nacos；给 Retry 配 backoff 并用 BlockHound 观察重试时有没有阻塞调用。

## 10.3 源码阅读入口清单（30 个关键文件）

主模块 `spring-cloud-gateway-server-webflux/src/main/java/org/springframework/cloud/gateway/`（1-24）：

| # | 文件 | 读什么 |
|---|---|---|
| 1 | `support/ServerWebExchangeUtils.java` | 全部 GATEWAY_* 属性与工具方法 |
| 2 | `handler/RoutePredicateHandlerMapping.java` | 路由匹配入口 |
| 3 | `handler/FilteringWebHandler.java` | 过滤链合并 + DefaultGatewayFilterChain |
| 4 | `filter/GatewayFilter.java` / `GlobalFilter.java` / `OrderedGatewayFilter.java` | 过滤器三契约 |
| 5 | `route/Route.java` | 运行态路由与 Builder |
| 6 | `route/RouteDefinition.java` + `handler/predicate/PredicateDefinition.java` + `filter/FilterDefinition.java` | 配方三件套 |
| 7 | `route/RouteDefinitionRouteLocator.java` | 配方→成品翻译器（全书枢纽） |
| 8 | `route/CachingRouteLocator.java` | 缓存与刷新落点 |
| 9 | `route/RouteRefreshListener.java` | 刷新事件源 |
| 10 | `route/InMemoryRouteDefinitionRepository.java` | 默认动态存储 |
| 11 | `support/ShortcutConfigurable.java` | 三种 shortcut 形态 |
| 12 | `support/ConfigurationService.java` | 配置绑定引擎 |
| 13 | `discovery/DiscoveryClientRouteDefinitionLocator.java` | 服务发现路由 |
| 14 | `filter/RouteToRequestUrlFilter.java` | URL 合并 |
| 15 | `filter/ReactiveLoadBalancerClientFilter.java` | 负载均衡 |
| 16 | `filter/NettyRoutingFilter.java` | 出网 |
| 17 | `filter/NettyWriteResponseFilter.java` | 写回 |
| 18 | `filter/AdaptCachedBodyGlobalFilter.java` + `RemoveCachedBodyFilter.java` | 读体缓存生命周期 |
| 19 | `filter/WebsocketRoutingFilter.java` | WS 代理 |
| 20 | `filter/WeightCalculatorWebFilter.java` | 权重抽签 |
| 21 | `filter/headers/HttpHeadersFilter.java` + `XForwardedHeadersFilter.java` | 头过滤体系 |
| 22 | `filter/factory/RewritePathGatewayFilterFactory.java` + `StripPrefixGatewayFilterFactory.java` | 改 URL 纪律的样板 |
| 23 | `filter/factory/RetryGatewayFilterFactory.java` | 最复杂的内置过滤器 |
| 24 | `filter/factory/SpringCloudCircuitBreakerFilterFactory.java` + `filter/ratelimit/RedisRateLimiter.java` | 熔断与限流 |

外围（25-30）：

| # | 文件 | 读什么 |
|---|---|---|
| 25 | `config/GatewayAutoConfiguration.java` | 一切的装配点 |
| 26 | `config/GatewayProperties.java` + `config/HttpClientProperties.java` | 配置面全景 |
| 27 | `config/conditional/OnEnabledComponent.java` | 开关体系 |
| 28 | `actuate/AbstractGatewayControllerEndpoint.java` | 运维端点 |
| 29 | `spring-cloud-gateway-server-webmvc/.../handler/RestClientProxyExchange.java` | MVC 版转发 |
| 30 | `spring-cloud-gateway-sample/src/main/resources/application.yml` | 最小可用配置 |

## 结语

如果把 Spring Framework 比作"把对象创建与横切逻辑从业务代码里夺走"，那 Spring Cloud Gateway 就是同一套哲学在"流量"维度上的重演：**路由的翻译（RouteDefinition→Route）夺走了"匹配逻辑怎么写"，过滤器链夺走了"横切逻辑往哪放"，交换属性黑板夺走了"中间状态怎么传"**。整个网关没有魔法——一个 HandlerMapping、一个 WebHandler、一条按 order 排好的责任链、一个 order 最大的过滤器出网、一个 order -1 的过滤器写回。读懂了第三章的这条链，40 多个内置过滤器不过是链上的过客；读懂了第二章的翻译管线，动态路由不过是"重译 + 换缓存"；读懂了第六章的刷新链，配置中心接入不过是"发一个事件"。

最后留给读者一个检验标准：当你在生产上遇到"网关行为诡异"时，能否不查博客，直接打开 `/actuator/gateway/routes/{id}/combinedfilters` 看这条路由的链、按 9.3 节的时间线在脑中走一遍请求、再对照 3.5 节的属性表找出是哪个过滤器没写/多写了那把"黑板钥匙"？能，这份文档就完成它的使命了。

