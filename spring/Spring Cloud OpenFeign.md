# Spring Cloud OpenFeign 深度源码解析（写给初学者的架构全景）

> **本文基于的源码**：`D:\code\3rd\spring-cloud-openfeign`，版本 **5.1.0-SNAPSHOT**（main 分支，Git commit `46df9e2a`；最新 GA 为 v5.0.3，2026-08-10 发布）。文中所有【源码证据】的文件路径与行号均为对该快照实际读取所得。Spring Cloud OpenFeign 是 `io.github.openfeign/feign`（下称 **Feign 内核**）的 Spring 集成层，因此关键内核证据另取自 `D:\code\3rd\feign`（master 分支，commit `1f02e66c`，最新 tag 13.15）；子上下文机制取自 `D:\code\3rd\spring-cloud-commons`（5.1.0-SNAPSHOT，commit `589f31d`，与《Spring Cloud LoadBalancer.md》同基线）。
>
> **版本取舍说明**：Spring Cloud OpenFeign 自 **2022-12-16（Spring Cloud 2022.0.0 Kilburn 发布博客）起官方宣布 feature-complete**——只修 bug、接受少量社区小特性，官方建议新项目迁移到 Spring HTTP Service Clients（本系列《声明式HTTP Client.md》有专门对比）。但它并未停止跟进大版本：5.0.0（2025-11-24）对齐 **Boot 4.0**（OkHttp 支持移除、HttpMessageConverters 适配、Jackson 3），5.1.0-M1（2026-09-18）继续跟进 Boot 4.1/4.2 线。因此本文内容对使用 Boot 3.x + OpenFeign 4.x 的读者同样适用，差异集中在 1.6 节的版本对照表。
>
> **阅读约定**：每章"先白话、后源码"——先用一两句人话讲清"这是什么、为什么需要它"，再给出源码证据链（`模块/src/main/java/.../类名.java` + 方法名 + 行号 + 代码片段）。行号只对该 5.1.0-SNAPSHOT / feign 1f02e66c 快照精确，读者用 IDEA 打开源码按类名 + 方法名定位即可。涉及 Feign 内核的文件统一标注 `feign-core` 前缀，涉及 spring-cloud-openfeign 的统一标注 `openfeign-core` 前缀。

## 如何读这份文档

如果你是初学者，推荐两遍读法：

- **第一遍（建立地图，1~2 小时）**：只读第一章（总览）每节开头白话段、各章"本章小结"、以及第八章（贯通视图）。目标是能回答：`@FeignClient` 接口为什么没有实现类却能注入？`http://user-service/users` 里的服务名谁换成真实 IP？默认到底重不重试？
- **第二遍（深入源码）**：对照【源码证据】逐行读。顺序建议：第三章（注册与装配，理解"接口如何变成 Bean"）→ 第二章（Feign 内核速通，理解"代理被调一次发生了什么"）→ 第四章（契约与编解码）→ 第五章（负载均衡）→ 第六章（熔断）→ 第七章（外围能力，可跳读）。

---

# 一、总览：Spring Cloud OpenFeign 的定位、设计哲学与整体架构

## 1.1 一句话定位

**Spring Cloud OpenFeign 是"声明式 HTTP 客户端"的 Spring Cloud 集成层：它把 OpenFeign 的"接口 + 注解 + 动态代理"翻译引擎接入 Spring 容器，让一个普通 Java 接口通过 `@FeignClient` 注解就变成可注入、可负载均衡、可熔断降级、可观测的 HTTP 客户端 Bean。** 你写接口，它生成实现。

官方文档自述（`docs/modules/ROOT/pages/spring-cloud-openfeign.adoc`）："Feign is a declarative web service client... Spring Cloud adds support for Spring MVC annotations and for using the same `HttpMessageConverters` used by default in Spring Web."——即它做的是**融合**：Feign 的代理引擎 + Spring MVC 的注解词汇 + Spring 容器的装配与扩展体系。服务发现、负载均衡、熔断、观测分别由 Spring Cloud LoadBalancer / CircuitBreaker / Micrometer 提供，本项目负责接线（这正是本系列《Spring Cloud LoadBalancer.md》《Spring Cloud CircuitBreaker.md》两篇的下游视角）。

它解决的不是"怎么发 HTTP 请求"（那是 RestTemplate/RestClient/OkHttp 的事），而是**微服务间调用的工程组织问题**：调用方不该手写 URL、不该各自实现重试/降级/追踪、不该为每个下游服务复制一遍超时与编解码配置。

一个必须先讲清的现实（intro.adoc 的 WARNING）：**项目自 2022-12 起 feature-complete**，官方点名迁移到 Spring Framework 的 HTTP Service Clients（`@HttpExchange` + `HttpServiceProxyFactory`）。但存量迁移周期极长，且 OpenFeign 仍在按 Boot 4.0/4.1 的节奏发版与维护，因此它仍是当前生产环境中最主流的声明式客户端之一——本文按 5.1.0-SNAPSHOT 现状解析。

## 1.2 设计哲学：读源码前先记住四句话

1. **接口即客户端，实现即代理**。`@FeignClient` 注解在一个没有任何实现的接口上；容器里注册的是 `FeignClientFactoryBean`（一个 `FactoryBean`），`getObject()` 时用 JDK 动态代理凭空造出实现（见 3.4 节）。这是 Spring `FactoryBean` 模式（本系列《Spring Framework.md》第七章）最典型的第三方接入应用。
2. **每个客户端一个子容器**。超时、编码器、拦截器这些"每个下游服务都不同"的配置，放进以 contextId 为名的 `NamedContextFactory` 子 `ApplicationContext`（见 3.3 节）——同一套 Bean 类型，N 个客户端 N 份独立配置，互不污染。
3. **引擎可插拔，词汇用 Spring 的**。Feign 内核把 Client/Encoder/Decoder/Contract/Retryer/ErrorDecoder 全部做成接口（第二章）；Spring Cloud OpenFeign 只做两件事：给每段塞一个"Spring 风格"的默认实现（复用 `HttpMessageConverters`、`ConversionService`、MVC 注解），再把负载均衡/熔断/观测通过装饰器与 `Capability` 挂上去。
4. **约定可覆盖，顺序定胜负**。默认配置类（`FeignClientsConfiguration`）→ `@FeignClient(configuration=...)` → `spring.cloud.openfeign.client.config.*` 四层覆盖、`defaultToProperties` 一键反转（见 3.5 节）。源码里最值得精读的就是这些覆盖顺序——它们决定了"我配的为什么没生效"。

## 1.3 模块分层全景

仓库是 Maven 多模块工程：`spring-cloud-openfeign-dependencies`（BOM）、`spring-cloud-openfeign-core`（**全部源码，87 个 main 文件 + 109 个测试文件**）、`spring-cloud-starter-openfeign`（纯 pom 聚合依赖，0 个 Java 文件）。core 模块按包分层如下（`org/springframework/cloud/openfeign/` 下）：

```
┌──────────────────────── 自动配置层（Boot 装配总装） ────────────────────────┐
│  FeignAutoConfiguration（+ 内部 5 个配置类）  ← AutoConfiguration.imports 五项之一
│  FeignLoadBalancerAutoConfiguration（loadbalancer 包）
│  FeignAcceptGzipEncodingAutoConfiguration / FeignContentGzipEncodingAutoConfiguration
│  FeignHalAutoConfiguration（hateoas 包）
├──────────────────────── 注册层（注解 → BeanDefinition） ───────────────────┤
│  @EnableFeignClients → @Import(FeignClientsRegistrar)                      │
│  @FeignClient 注解 + FeignClientSpecification（子容器规格）                 │
├──────────────────────── 装配层（BeanDefinition → 可用代理） ───────────────┤
│  FeignClientFactoryBean（组装车间）  FeignClientFactory（子上下文工厂）     │
│  Targeter/DefaultTargeter  FeignClientBuilder（编程式）  OptionsFactoryBean │
│  RefreshableUrl 三件套 + PropertyBasedTarget（url 运行时刷新）             │
│  aot/ 两件套（GraalVM native）                                             │
├──────────────────────── 契约与编解码层 ────────────────────────────────────┤
│  SpringMvcContract + annotation/ 7 个参数处理器 + AnnotatedParameterProcessor│
│  support/：SpringEncoder  SpringDecoder  ResponseEntityDecoder             │
│           PageableSpringEncoder  PageableSpringQueryMapEncoder             │
│           FeignHttpMessageConverters  Page/Sort Jackson 模块               │
├──────────────────────── 运行时增强层（装饰 feign.Client/InvocationHandler）│
│  loadbalancer/：FeignBlockingLoadBalancerClient（+ Retryable 版，11 文件）  │
│  FeignCircuitBreaker*（熔断家族 6 文件）  CachingCapability（Spring Cache） │
│  security/OAuth2AccessTokenInterceptor  encoding/（gzip 双拦截器，7 文件）  │
│  FeignClientsConfiguration（子容器默认配置类，观测/重试默认值都在这）       │
└─────────────────────────────────────────────────────────────────────────────┘
```

包文件数速查：根包 36、annotation 7、aot 2、clientconfig 4、encoding 7、hateoas 2、loadbalancer 11、security 1、support 17，合计 87。**一个显著特征：除了 `spring-boot-autoconfigure` 与 jspecify，所有依赖都是 optional**（`spring-cloud-openfeign-core/pom.xml` 实证）——commons/context、feign-core/hc5/java11/micrometer、spring-retry、spring-data-commons、spring-cloud-loadbalancer、oauth2-client 全部按 classpath 条件装配，这也是它的类条件注解密度极高的原因。

## 1.4 依赖图（以 core 模块 pom 实证）

```
                    feign-core（引擎：代理+模板+重试）
                          ▲（全部 optional）
   spring-cloud-openfeign-core
     ├─ 必选：spring-boot-autoconfigure、jspecify
     ├─ Spring 装配（optional）：spring-cloud-commons、spring-cloud-context、
     │        spring-retry、spring-data-commons、spring-cloud-loadbalancer
     ├─ Feign 家族（optional）：feign-form-spring、feign-slf4j、
     │        feign-micrometer、feign-hc5、feign-java11
     └─ 场景能力（optional）：boot-jackson、boot-data-commons/rest、
              boot-starter-{web,webflux,actuator,security,hateoas}、
              spring-security-oauth2-client、reactor-core、netty-codec-http
```

读这张图能看出架构意图：**core 本体是"零硬依赖的条件装配器"**——你引入哪个生态件，它就启用哪段能力；`spring-cloud-starter-openfeign` 则只聚合 `feign-core + feign-slf4j + spring-cloud-starter-loadbalancer` 等最小集合，把选择权留给用户。Feign 内核版本随列车走：5.1.0-SNAPSHOT 绑 feign 13.14（v5.0.0 绑 13.6，v4.0.0 绑 12.1，`spring-cloud-openfeign-dependencies/pom.xml` 逐 tag 实证）。

## 1.5 关键问题 → Spring Cloud OpenFeign 方案映射（全文导览）

| 微服务客户端的关键问题 | 本项目的方案 | 详见 |
|---|---|---|
| 一个没有实现类的接口凭什么能 `@Autowired` | `FeignClientsRegistrar` 扫描注册 `FeignClientFactoryBean` → JDK 动态代理 | 第三章 |
| `http://user-service/...` 的服务名谁换成真实 IP | `FeignBlockingLoadBalancerClient` 装饰 `feign.Client`，经 LoadBalancer 选实例、重建 URI | 第五章 |
| 下游挂了怎么办（降级） | `FeignCircuitBreakerInvocationHandler` 包装 InvocationHandler + `FallbackFactory` | 第六章 |
| 下游慢/抖动怎么办（重试） | 双轨：feign 内核 `Retryer`（默认 NEVER_RETRY）与 LoadBalancer 重试客户端（spring-retry） | 第二章、第五章 |
| `@RequestParam/@PathVariable` 这些注解谁来解释 | `SpringMvcContract`（继承 feign `BaseContract`）+ 7 个参数处理器 SPI | 第四章 |
| 对象 ↔ 字节流怎么转换 | `SpringEncoder/SpringDecoder` 直接复用 Spring MVC 的 `HttpMessageConverters` | 第四章 |
| 分页参数 `Pageable` 怎么传 | 两条路线：`PageableSpringQueryMapEncoder`（默认）与 `PageableSpringEncoder`（body 场景） | 第四章 |
| 10 个下游服务要 10 套超时/编解码 | `FeignClientFactory`（NamedContextFactory）每 contextId 一个子容器 + 四层配置覆盖 | 第三章 |
| 调用想自动带 token | `OAuth2AccessTokenInterceptor`（client registration 或按服务名匹配） | 第七章 |
| 想要调用耗时指标/链路 | feign-micrometer 双 Capability（Observation 优先）经子上下文注入 | 第七章 |
| 接口方法上写 `@Cacheable` 就想生效 | `CachingCapability` 替换 InvocationHandlerFactory，复用 Spring `CacheInterceptor` | 第七章 |
| url/超时想不重启就改（配置中心） | refresh-enabled：Options/Url 注册为 refresh 作用域 bean | 第三章 3.7 |
| GraalVM native 镜像 | `aot/` 两件套：子上下文 AOT 化 + 客户端注册代码重生成 | 第三章 3.9 |

## 1.6 版本演进：Netflix 血统 → 独立项目 → feature-complete → Boot 4 适配

写作时（2026 年 10 月）的版本格局：main 即 5.1.0-SNAPSHOT，最新 GA v5.0.3。本节全部结论经本地 114 个 git tag（v2.0.0.M1 ~ v5.1.0-M1）逐项实证。

### 1.6.1 它原本是 spring-cloud-netflix 的一部分

本仓库保留了完整的 git 历史：2015~2018 年的提交都在 netflix 仓库里（Feign 支持当年随 Hystrix/Ribbon 一起住在 `spring-cloud-netflix`）。**2018-02-05 提交 `21744304 "openfeign project"` 是立库点**，Feign 支持从 Netflix 套件中独立成项目——这就是为什么项目首个 tag 就是 2.0.0（v2.0.0.M1，2018-06-18 GA v2.0.0.RELEASE，Finchley 列车，Boot 2.0，feign 9.5.1）：版本号继承了它在 netflix 时代的身位。 commons 的 `NamedContextFactory` javadoc 至今写着 "Ported from spring-cloud-netflix FeignClientFactory"（`NamedContextFactory.java:53`），全家都姓过 Netflix。

### 1.6.2 发布列车与依赖基线（GA 时间线全实证）

| 版本 | GA 时间 | 发布列车 | Boot 基线 | feign 内核 | 一句话主题 |
|---|---|---|---|---|---|
| 2.0.0 | 2018-06-18 | Finchley | 2.0.x | 9.5.1 | 自 netflix 独立立库（`git log -1 v2.0.0.RELEASE` 实证） |
| 2.1.0 | 2019-01-22 | Greenwich | 2.1.x | 10.x | Pageable 编码支持（2.1.1 首入 PageableSpringEncoder） |
| 2.2.0 | 2019-11-26 | Hoxton | 2.2.x | 10.4.0 | `contextId` 属性、`@since` 后缀时代终点（v2.2.10.RELEASE 是最后一个 .RELEASE tag，2021-10-21） |
| 3.0.0 | 2020-12-21 | 2020.0（Ilford） | 2.4.x | 10.10.1 | **删 Ribbon/Hystrix**（提交 8a08e1ec，2020-01-17），改挂 Spring Cloud CircuitBreaker（提交 63a52484） |
| 4.0.0 | 2022-12-15 | 2022.0（Kilburn） | 3.0.x | 12.1 | **jakarta、spring.factories→imports、AOT、Micrometer Observation、FeignContext→FeignClientFactory、lazy-attributes-resolution**（#794 一揽子）；feature-complete 宣布 |
| 4.1.0 | 2023-12-06 | 2023.0（Leyton） | 3.2.x | 13.1 | feign 13.x 线 |
| 4.2.0 | 2024-12-03 | 2024.0（Moorgate） | 3.4.x | 13.5 | 平稳迭代 |
| 4.3.0 | 2025-05-29 | 2025.0（Northfields） | 3.5.x | 13.6 | FeignOAuth2Properties（4.3.2，#1312） |
| 5.0.0 | 2025-11-24 | 2025.1 | **4.0.x** | 13.6 | **移除 OkHttp**（提交 167a866b，2025-09-22）、Boot 4 结构适配（#1213）、HttpMessageConverters 改造（#1277，新增 `FeignHttpMessageConverters`）、Jackson 3 |
| 5.1.0-M1 | 2026-09-18 | 下一列车 | 4.1/4.2 线 | 13.14 | `FeignCircuitBreakerProperties`（alphanumeric-ids 开关） |

### 1.6.3 特性首入版本对照表（`git tag --contains` 逐项实证）

| 特性 | 首入版本 | 证据 |
|---|---|---|
| FeignClientBuilder（编程式构建） | 2.0.2 | 提交 750cb35e（2018-09-19），4.x 重写为 forType |
| PageableSpringEncoder | 2.1.1 | 提交 642d3e69 "Spring data pageable support" |
| contextId 属性 | 2.2.0 | `git show v2.1.5.RELEASE:...FeignClient.java` 无此属性 |
| Spring Cloud CircuitBreaker 集成（Targeter/InvocationHandler） | 3.0.0 | 提交 63a52484（2020-12-15） |
| Hystrix/Ribbon 支持移除 | 3.0.0 | 提交 8a08e1ec（2020-01-17），HystrixTargeter 最后存在于 v2.2.10.RELEASE |
| OptionsFactoryBean（超时可刷新） | 3.0.3 | 提交 e47255e7 (#526) |
| CircuitBreakerNameResolver | 3.0.4 | 提交 c101fd2c (#575) |
| CachingCapability（Spring Cache） | 3.1.0 | 提交 0cd57b2c (#607/#608) |
| Micrometer 指标（前身 FeignClientMetricsEnabledCondition） | 3.0.2 | 提交 e745dd0c (#462) |
| Micrometer Observation API | 4.0.0 | 提交 372a631c (#793) |
| RefreshableUrl（url 可刷新） | 4.0.0 | 提交 e33062fa (#766) |
| AOT/native 支持 + FeignContext 更名 FeignClientFactory + lazy-attributes-resolution | 4.0.0 | 同一提交 7f66cf38 (#794)，`R061 FeignContext.java → FeignClientFactory.java` |
| qualifiers[] 替代 qualifier、dismiss404 替代 decode404 | 4.0.0 | 注解属性 diff 实证 |
| responseInterceptor 配置入口 | 4.0.0 | 提交 fead135d (#804)——注意 `feign.ResponseInterceptor` 是 feign 内核接口，本仓库只提供配置入口 |
| OAuth2AccessTokenInterceptor | 4.0.0 | 提交 66cc168a（PR 系列 2022-09-30 起） |
| PropertyBasedTarget（AOT/native 下运行时读 url） | 4.0.2 | 提交 54a6a56b (#827) |
| FeignOAuth2Properties 属性类 | 4.3.2 | 提交 a3625721 (#1312)——注意与 4.0.0 的拦截器不是一批 |
| OkHttp 支持移除 | 5.0.0 | 提交 167a866b (#1258)；4.3.x 全系仍有 22 个文件涉及 okhttp |
| FeignHttpMessageConverters（Boot 4 适配） | 5.0.0 | 提交 3012cfa5 (#1277) |
| 熔断器字母数字 id 开关（alphanumeric-ids） | 5.1.0-M1 | 提交 1129178a，`FeignCircuitBreakerProperties` |

### 1.6.4 底层注解属性的冻结史

`@FeignClient` 的属性集在 4.0.0 后冻结至今（main 共 11 个：value/name/contextId/qualifiers/url/dismiss404/configuration/fallback/fallbackFactory/path/primary）；`@EnableFeignClients` 五个属性自 2.0.0 从未增删。serviceId 删除于 3.0.0（被 name 取代），decode404 改名 dismiss404、qualifier 改为 qualifiers 数组均在 4.0.0——**一切特征都指向"4.0.0 是这个项目最后一次大整容，之后进入维护态"**。

## 1.7 全文章节地图

- **第二章 Feign 内核速通**：Spring Cloud OpenFeign 站在什么上面——六段可插拔管道、Builder 默认值全景、一次调用的完整代码路径、重试与错误解码、Capability 钩子。
- **第三章 注册与装配**：从 `@EnableFeignClients` 到可用代理——扫描注册、子上下文体系、FactoryBean 组装车间、四层配置覆盖、url 刷新、编程式构建、AOT。
- **第四章 契约与编解码**：Spring MVC 注解如何翻译成 HTTP 请求——SpringMvcContract、参数名解析、七个参数处理器、编码/解码链路、Pageable 双路线、Jackson 2/3 共存。
- **第五章 负载均衡与重试**：name-only 客户端的路由内核——BlockingLoadBalancerClient 全流程、可重试变体与 spring-retry、装配互斥。
- **第六章 熔断与降级**：FeignCircuitBreaker 家族——Targeter 拦截点、InvocationHandler 替换、fallback 分派、名字规则。
- **第七章 外围能力包**：观测（Micrometer 双 Capability）、缓存（CachingCapability）、安全（OAuth2）、压缩（gzip）。
- **第八章 贯通视图**：启动时间线、一次调用的一生、一次异常的一生。
- **第九章 附录**：配置属性速查、与 HTTP Service Clients 的迁移对照、源码阅读入口、常见坑。

# 二、Feign 内核速通：Spring Cloud OpenFeign 站在什么上面

本章基于 `D:\code\3rd\feign`（master，commit `1f02e66c`，最新 tag 13.15）。本系列《声明式HTTP Client.md》已从选型视角概述过这层，本章聚焦"Spring 集成层会碰到的那些点"。先给一张全景图：

```
接口方法调用
  → ReflectiveFeign$FeignInvocationHandler（JDK 动态代理）
    → SynchronousMethodHandler.invoke()
       ├─ ① RequestTemplate.from(metadata) 填充参数      ← Contract 决定元数据
       ├─ ② MethodInterceptor 链（13.x 新增，默认空）
       ├─ ③ 重试循环 runWithRetry（Retryer 决定）
       ├─ ④ targetRequest：RequestInterceptor 链 + Target.apply 补 base URL
       ├─ ⑤ Client.execute(request, options)              ← 负载均衡装饰在这一层
       └─ ⑥ ResponseHandler → ResponseInterceptor 链 → Decoder
```

## 2.1 六段可插拔管道与默认值全景

【feign-core 源码证据】`BaseBuilder.java:49-67`（`Feign.Builder extends BaseBuilder`，`Feign.java:97`）的字段初始化就是**默认值全景表**：

| 组件 | 默认实现（BaseBuilder.java 行号） | Spring Cloud OpenFeign 换成 |
|---|---|---|
| Client | Feign.Builder 特有字段 `new DefaultClient(null, null)`（JDK HttpURLConnection，`Feign.java:99`） | `FeignBlockingLoadBalancerClient`（第五章） |
| Contract | `new DefaultContract()`（L53） | `SpringMvcContract`（第四章） |
| Encoder | `new DefaultEncoder()`（L56） | `SpringEncoder`（第四章） |
| Decoder | `new DefaultDecoder()`（L57） | `OptionalDecoder(ResponseEntityDecoder(SpringDecoder))`（第四章） |
| Retryer | `new DefaultRetryer()`（L54，100ms 起步指数退避、最多 5 次） | **`Retryer.NEVER_RETRY`**（`FeignClientsConfiguration.java:143`） |
| ErrorDecoder | `new DefaultErrorDecoder()`（L61） | 可经 `FeignErrorDecoderFactory` 按 client 定制 |
| Logger / logLevel | `NoOpLogger` / `Level.NONE`（L52/L55） | `Slf4jLogger`（`DefaultFeignLoggerFactory.java:36`） |
| Options | `new Options()` = connect 10s / read 60s / 跟随 3xx（`Request.java:438`） | properties 或子上下文 bean 可覆盖 |
| QueryMapEncoder | `QueryMap.MapEncoder.FIELD.instance()`（L60） | 有 Pageable 时 `PageableSpringQueryMapEncoder` |
| InvocationHandlerFactory | `new DefaultInvocationHandlerFactory()`（L63-64） | 熔断开启时换成 `FeignCircuitBreaker`（第六章） |
| dismiss404 / closeAfterDecode / decodeVoid | false / true / false（L58/L59/L65） | dismiss404 可配（旧名 decode404，`BaseBuilder.java:243-247` 标 @Deprecated） |
| propagationPolicy | `NONE`（L66） | 可配 UNWRAP |

`build()` 的执行是 `enrich().internalBuild()`（`BaseBuilder.java:453-455`）：先让 `Capability` 逐个"装修"各组件（2.4 节），再进 `Feign.Builder.internalBuild()`（`Feign.java:217-242`）——组装 `ResponseHandler`（218-227 行）与 `SynchronousMethodHandler.Factory`（228-239 行），最后 `new ReflectiveFeign<>(contract, methodHandlerFactory, invocationHandlerFactory, ...)`（240-241 行）。`target()` 只是 `build().newInstance(target)`（`Feign.java:212-214`）。

## 2.2 一次调用的完整代码路径

【feign-core 源码证据】`SynchronousMethodHandler.java`（final 类，实现 `InvocationHandlerFactory.MethodHandler`）：

- **invoke（L49-66）**：`create(argv)` 生成填好参数的 `RequestTemplate`（L50）；`findOptions(argv)` 支持调用级覆盖——实参里若有 `Request.Options` 则优先于客户端默认（L154-168，13.x 还支持按方法名配置 methodOptions）；然后把"重试执行"包成终点，外面套上 `MethodInterceptor` 链（13.x 新增的实验特性，L52-65）。
- **runWithRetry（L68-101）**：**每次调用先 `retryer.clone()`**（L69）——Retryer 是有状态的（attempt 计数），必须克隆出独立实例，这正是接口注释 `Retryer.java:19` "Cloned for each invocation" 的含义。循环体内捕获 `RetryableException` 调 `retryer.continueOrPropagate(e)`（L75）：允许则 continue 重试，不允许则抛出（`UNWRAP` 传播策略在 L78-88 解包 cause）。
- **executeAndDecode（L103-141）**：`targetRequest(template)` 先跑全部 `RequestInterceptor` 再 `target.apply()` 拼上 base URL（L147-152）；`client.execute(request, options)` 发出请求（L119）——**这是 Spring 集成层的第一个接缝**（第五章的负载均衡装饰就挂在 Client 上）；IOException 统一转 `FeignException.errorExecuting`（L132）；响应交给 `responseHandler.handleResponse`（L138-140）。
- **ResponseHandler（`ResponseHandler.java:65-88`）**：日志级别非 NONE 时先 logAndRebuffer（L68，否则 body 只能读一次），然后进入 `ResponseInterceptor` 责任链（L69-78，链的组装在 `BaseBuilder.responseInterceptorChain()` L459-469）。
- **InvocationContext.proceed（`InvocationContext.java:69-100`）**——解码决策核心：
  ```java
  final boolean shouldDecodeResponseBody =
      (response.status() >= 200 && response.status() < 300)
          || (response.status() == 404 && dismiss404 && !isVoidType(returnType));   // L75-77
  if (!shouldDecodeResponseBody) {
      throw decodeError(configKey, response);   // L80 → errorDecoder.decode
  }
  ```
  即 2xx 或（404 且 dismiss404）才走 Decoder，其余一律进 ErrorDecoder；`closeAfterDecode` 在 finally 里兜底关流（L96-98）。**Spring 集成层的第二个接缝**：`decoder.decode()` 最终由 `SpringDecoder` 接手（第四章）。

## 2.3 重试与错误解码：Retry-After 才是重试的开关

【feign-core 源码证据】

- `Retryer.NEVER_RETRY`（`Retryer.java:47-59`）：`continueOrPropagate` 直接 `throw e`（L51-53）——**Spring Cloud OpenFeign 的默认行为就是永不重试**，任何 `RetryableException` 直接上抛。要重试得自己给子上下文塞 `Retryer` bean 或配 `retryer` 属性。
- `Retryer.Default` 已 @Deprecated（`Retryer.java:34-44`），现名 `DefaultRetryer`（`DefaultRetryer.java`）：默认 period=100ms、maxPeriod=1s、maxAttempts=5（L28-30）；指数退避 `interval = period * 1.5^(attempt-1)`（L77-80）；**若响应带 `Retry-After` 头则优先用它**（L50-57，超过 maxPeriod 截断、已过期则立即重试）。
- 什么样的异常才算 `RetryableException`？由 ErrorDecoder 决定：`DefaultErrorDecoder.decode`（`DefaultErrorDecoder.java:45-60`）对非 2xx 构造 `FeignException`，**但当响应头含 `Retry-After` 时改抛 `RetryableException`**（L48-58）。也就是说：默认管线里"状态码 + Retry-After 头"决定重试，"状态码本身"不决定——这与 LoadBalancer 重试客户端的 `retryableStatusCodes` 语义完全不同（第五章 5.3）。

## 2.4 Capability：给整套组件做装修的钩子

【feign-core 源码证据】`Capability.java` 是 feign 10.4 引入的**组合器接口**：17 个 `enrich` 钩子（L76-154），覆盖 Client、Retryer、RequestInterceptor、ResponseInterceptor、ResponseInterceptor.Chain、Logger、logLevel、Contract、Options、Encoder、Decoder、ErrorDecoder、InvocationHandlerFactory、QueryMapEncoder 等全部组件。`BaseBuilder.enrich()`（L332-432）在 build 时对每个字段做"逐个 Capability 依次包装"（静态分发见 `Capability.enrich` L38-56）。

Spring Cloud OpenFeign 正是靠它挂载跨切面能力，全程不碰 feign 源码：

- `CachingCapability.enrich(invocationHandlerFactory)` → 换成带缓存判定的工厂（第七章 7.2）；
- feign-micrometer 的 `MicrometerObservationCapability/MicrometerCapability` → 包装 Client/Interceptor 记录指标（第七章 7.1）。

这就是"适配而非重造"哲学在客户端侧的体现：**能力 = 一个 Capability 类 + 子上下文里一个条件 Bean**。

## 2.5 本章小结

- Feign 内核的骨架是：代理（ReflectiveFeign）→ 方法处理器（SynchronousMethodHandler）→ 重试循环 → 拦截器链 → Client → 响应链 → Decoder；每一段都是接口，默认实现见 2.1 全景表。
- Spring Cloud OpenFeign 对内核只做三类动作：**替换默认实现**（Contract/Encoder/Decoder/Client）、**包一层装饰器**（LoadBalancer Client）、**替换 InvocationHandlerFactory**（熔断、缓存）。
- 两个最反直觉的默认：**默认不重试**（NEVER_RETRY）与**默认不记日志**（logLevel=NONE）——都在子上下文默认配置里可改，见第三、四章。

---

# 三、注册与装配：从 @EnableFeignClients 到可用代理

本章全部基于 `spring-cloud-openfeign-core`（路径省略前缀 `.../org/springframework/cloud/openfeign/`）。总流程图：

```
@EnableFeignClients ─@Import→ FeignClientsRegistrar（ImportBeanDefinitionRegistrar）
   ├─ registerDefaultConfiguration：default.<配置类全名> 的 FeignClientSpecification
   └─ registerFeignClients：扫描 @FeignClient
        ├─ registerClientConfiguration：<name>.FeignClientSpecification
        └─ registerFeignClient：FeignClientFactoryBean 的 BeanDefinition（eager 或 lazy）
                                    │ 首次 getBean
                                    ▼
Boot 自动配置：FeignAutoConfiguration 收集全部 Specification → FeignClientFactory（NamedContextFactory）
                                    │ getObject()
                                    ▼
FeignClientFactoryBean.getTarget() → feign() 从子上下文拉组件 → configureFeign 四层覆盖
   → loadBalance()/直连 → Targeter.target() → Feign.Builder.target → JDK 代理
```

## 3.1 两个注解：@EnableFeignClients 与 @FeignClient

【源码证据】`EnableFeignClients.java:38-42`：`@Retention(RUNTIME) @Target(TYPE) @Import(FeignClientsRegistrar.class)`——它是纯触发器。五个属性（`value`/`basePackages`/`basePackageClasses`/`defaultConfiguration`/`clients`）**自 2.0.0 起从未变过**。两个易错点：

- `value` 是 `basePackages` 的别名（L50），javadoc 声称二者互斥（L55），但 `FeignClientsRegistrar.getBasePackages`（L392-415）实际把三者**取并集**，全空才退化为注解所在类的包——文档与实现有出入。
- `clients` 非空时**关闭 classpath 扫描**（`registerFeignClients` L176-188 只处理显式列表），这是 GraalVM native 与 AOT 场景的推荐姿势。

`@FeignClient`（`FeignClient.java:42-119`）11 个属性中最需要理解的是前三个的三角关系：

| 属性 | 作用 | 不写会怎样 |
|---|---|---|
| `name`/`value`（@AliasFor 互为别名，L50-65） | 服务名（可带协议前缀），决定 Bean 名、子上下文名、LoadBalancer 的服务 id | 必填，`getName` 校验主机名合法性（Registrar L92-113） |
| `contextId`（L58） | **仅用作子上下文与配置覆盖的 key**，不用作服务 id | 缺省=name；同 name 多接口想配不同超时就必须拆 contextId |
| `url`（L75） | 直连地址；有值则完全绕过负载均衡（还涉及 5.4 节的"解包"行为） | 缺省走 name → LoadBalancer |

其余：`qualifiers[]`（L70，4.0.0 起替代单数 qualifier）、`dismiss404`（L80，404 解码为空对象而非抛异常）、`configuration`（L90，客户端专属配置类）、`fallback`/`fallbackFactory`（L97/L107，熔断降级）、`path`（L112，注意**没有 @Deprecated**，会被 `cleanPath` 规范化）、`primary`（L117，默认 true）。

## 3.2 FeignClientsRegistrar：两步注册

【源码证据】`FeignClientsRegistrar.java`（`ImportBeanDefinitionRegistrar`）：

1. **注册默认配置**（`registerDefaultConfiguration` L157-170）：`@EnableFeignClients(defaultConfiguration=...)` 生成名为 `default.<配置类全限定名>` 的 `FeignClientSpecification`——`default.` 前缀是子上下文的"全局配置"标记（见 3.3 节 registerBeans）。
2. **注册客户端**（`registerFeignClients` L172-207）：每个 `@FeignClient` 接口先注册 `<name>.FeignClientSpecification`（携带 `configuration()` 里的配置类，L468-469），再注册客户端本体。

客户端 BeanDefinition 按 `spring.cloud.openfeign.lazy-attributes-resolution`（**读取点 L213，默认 false 即 eager**）二选一：

- **eager（默认）**：`eagerlyRegisterFeignClientBeanDefinition`（L222-263）——`genericBeanDefinition(FeignClientFactoryBean.class)`（L225），逐属性 addPropertyValue（url/path/name/contextId/type/dismiss404/fallback/fallbackFactory，L226-243），`AUTOWIRE_BY_TYPE`（L244，这就是 3.4 节类头"禁止 @Autowired"警告的由来——属性靠按类型自动装配注入），qualifiers 默认 `[contextId + "FeignClient"]`（L246-249），并预置 `FactoryBean.OBJECT_TYPE_ATTRIBUTE`（L255）。
- **lazy**：占位 BeanDefinition 用 lambda 推迟 `factoryBean.getObject()`（L278-293）+ `setLazyInit(true)`（L295）——注解里的 `${}` 占位符推迟到首次 getBean 才解析。

两路径末尾都会追加 refresh 附加注册（L261-262 / L314-315，见 3.7 节）。这套注册机制自 2.0 就存在，**lazy 与 eager 的分叉则是 4.0.0 才引入**（1.6.3 节），动机是 AOT：属性要提前解析才能进镜像。

## 3.3 FeignClientFactory：每个 contextId 一个子容器

白话：为什么不能把所有 Feign 客户端的 `Decoder`、`Retryer` 放进主容器？因为 A 服务的 Decoder 和 B 服务的 Decoder 类型相同但实现不同——**同名 Bean 的多实例隔离问题**，Spring Cloud 的答案是"每客户端一个迷你 ApplicationContext"。

【commons 源码证据】`NamedContextFactory.java`（spring-cloud-context，589f31d）：

- `Map<String, GenericApplicationContext> contexts`（L71，ConcurrentHashMap）+ `Map<String, C> configurations`（L73，名字 → 规格）。
- `getContext(name)` 双检锁惰性创建（L121-130）→ `createContext`（L132-143）→ `registerBeans`（L145-161）：注册顺序是 **① 该客户端自己的配置类 → ② 所有 `default.` 前缀的规格配置类 → ③ `PropertyPlaceholderAutoConfiguration` + 构造器固化的默认配置类**。①覆盖②覆盖③——这就是"客户端配置类能覆盖全局默认"的容器语义来源（`@ConditionalOnMissingBean` 让后面的注册让位）。
- `buildContext(name)`（L163-195）：把父容器设为 parent（L189-192，子上下文能读父容器所有 Bean——`inheritParentContext` 的底层支撑），并注入一行属性源 `propertyName → name`（L185-188）。

【openfeign-core 源码证据】`FeignClientFactory.java`（前身 `FeignContext`，4.0.0 随 AOT 一并更名加泛型）：

```java
// L46-50
public FeignClientFactory(Map<String, ApplicationContextInitializer<...>> initializers) {
    super(FeignClientsConfiguration.class, "spring.cloud.openfeign", "spring.cloud.openfeign.client.name",
            initializers);
}
```

即默认配置类 = `FeignClientsConfiguration`（3.5 节详解），属性名 `spring.cloud.openfeign.client.name` 的值就是当前 contextId——**子上下文里的代码可以读到"我是谁"**（第七章的 `FeignClientMicrometerEnabledCondition` 正是靠它实现 per-client 条件判断）。本类新增的三个方法值得记住：`getInstanceWithoutAncestors`（L52-59，不走父容器）、`getInstancesWithoutAncestors`（L61-63）、**按 bean 名取的 `getInstance(contextId, beanName, type)`（L65-67，专为 refresh 作用域 bean 服务）**。

## 3.4 FeignClientFactoryBean：代理的组装车间

【源码证据】`FeignClientFactoryBean.java`（773 行，`implements FactoryBean<Object>, InitializingBean, BeanFactoryAware`）。类头警告值得抄录（L85-88）：

> "Nothing in this class should be @Autowired. It causes NPEs because of some lifecycle race condition."

它自己就是被 `AUTOWIRE_BY_TYPE` 装配的对象，容器生命周期里它可能早于其他 Bean 就绪，所以一切依赖都延迟到 `getObject()` 时现取。

### 3.4.1 getTarget()：负载均衡与直连的分岔（L475-520）

```java
Feign.Builder builder = feign(feignClientFactory);                    // L479
if (!StringUtils.hasText(url) && !isUrlAvailableInConfig(contextId)) { // L480
    // name-only：补协议、拼 cleanPath，走负载均衡
    if (!name.startsWith("http://") && !name.startsWith("https://")) {
        url = "http://" + name;                                       // L485-486
    }
    ...
    return (T) loadBalance(builder, feignClientFactory,
            new HardCodedTarget<>(type, name, url));                  // L492
}
// url 直连：若容器里的 Client 是 LB 装饰器，解包出 delegate 直连（L503-512）
Targeter targeter = get(feignClientFactory, Targeter.class);          // L518
return targeter.target(this, builder, feignClientFactory,
        resolveTarget(feignClientFactory, contextId, url));           // L518-519
```

两个细节：① **url 也可以写在配置里**（`spring.cloud.openfeign.client.config.<id>.url`，`isUrlAvailableInConfig` L575-578 优先级低于注解）；② resolveTarget（L553-573）的三级 fallback：注解 url → `HardCodedTarget`；refresh-enabled → 按名取 `RefreshableUrl` → `RefreshableHardCodedTarget`；配置 url → `PropertyBasedTarget`；全没有 → IllegalStateException（L568-569）。

`loadBalance`（L438-449）：从子上下文取 `Client`（LB 装饰器），没有则抛那句经典错误 "No Feign Client for loadBalancing defined. Did you forget to include spring-cloud-starter-loadbalancer?"（L447-448）——**引入 starter 而没引 loadbalancer 时，这个异常在首次注入/调用时才爆**。

### 3.4.2 feign()：从子上下文拉组件并组装 Builder（L146-162）

```java
Feign.Builder builder = get(context, Feign.Builder.class)   // L151：原型 Bean
        .logger(logger).encoder(get(context, Encoder.class)) // L153-154
        .decoder(get(context, Decoder.class))                // L155
        .contract(get(context, Contract.class));             // L156
```

每个 `get` 都是 `NamedContextFactory.getInstance(contextId, type)`——**首次触发该 contextId 子上下文的创建**（3.3 节）。随后 `applyBuildCustomizers`（L164-175）应用 `FeignBuilderCustomizer`。

### 3.4.3 configureFeign：四层覆盖，顺序定胜负（L177-201）

```java
if (properties != null && inheritParentContext) {
    if (properties.isDefaultToProperties()) {                        // 默认 true
        configureUsingConfiguration(context, builder);               // ① Java 配置类
        configureUsingProperties(properties.getConfig().get(properties.getDefaultConfig()),  // ② config.default
                properties.getConfig().get(contextId), builder);     // ③ config.<contextId>
    } else {
        configureUsingProperties(...);                               // 反转：配置类赢
        configureUsingConfiguration(context, builder);
    }
    configureDefaultRequestElements(...);                            // ④ 默认头/参数（不存在才补，L347-397）
} else {
    configureUsingConfiguration(context, builder);
}
```

**结论（默认 `defaultToProperties=true`）**：`@FeignClient(configuration=...)`/子上下文 Bean 先应用，properties 后应用——**配置文件赢**。`config.<contextId>` 在 `config.default` 之后应用——**客户端级赢**（L267-278 先 base 后 final）。想反转让 Java 配置类赢，设 `spring.cloud.openfeign.client.default-to-properties=false`。

还有一个隐蔽差异：`requestInterceptors` 从**配置类**来时是"排序后整体替换"（`configureUsingConfiguration` L234-240，`AnnotationAwareOrderComparator.sort`），从**properties** 来时是"逐个追加"（`configureUsingProperties` L311 注释 "this will add request interceptor to builder, not replace existing"）。另外 `configureUsingProperties` 里的 Bean 实例化走 `getOrInstantiate`（L399-406）：容器里没有就直接 `BeanUtils.instantiateClass` 反射 new——**配置属性里写的类不需要注册成 Bean**。

## 3.5 子上下文的默认配置类：FeignClientsConfiguration

【源码证据】`FeignClientsConfiguration.java`——它就是 2.1 节默认值全景表在 Spring 侧的落点。逐 Bean 清单（均 `@ConditionalOnMissingBean`，可被 `@FeignClient(configuration=...)` 或子上下文直接替换）：

| Bean | 行号 | 内容 |
|---|---|---|
| `feignDecoder` | L111-115 | `new OptionalDecoder(new ResponseEntityDecoder(new SpringDecoder(messageConverters)))`——三层装饰：Optional 解包 `Optional<T>` 返回值，ResponseEntity 处理 `ResponseEntity<T>`，SpringDecoder 做真解码 |
| `feignEncoder` | L117-123 | classpath **有** Pageable 时让位（L119 `@ConditionalOnMissingClass("org.springframework.data.domain.Pageable")` 反向条件）给 SpringDataConfiguration 的 `feignEncoderPageable`（L194-208，`PageableSpringEncoder` 包裹） |
| `feignContract` | L125-129 | `new SpringMvcContract(parameterProcessors, feignConversionService, feignClientProperties)` |
| `feignConversionService` | L131-138 | `DefaultFormattingConversionService` + 循环应用 `FeignFormatterRegistrar`——**唯一没有 @ConditionalOnMissingBean 的 Bean**，覆盖它得连 Contract 一起换 |
| `feignRetryer` | L140-144 | **`Retryer.NEVER_RETRY`**——默认不重试 |
| `feignLoggerFactory` | L146-150 | 无容器 Logger 时 `Slf4jLogger`（`DefaultFeignLoggerFactory.java:36`） |
| `feignClientConfigurer` | L152-157 | 空实现钩子（`inheritParentConfiguration()` 可关父容器继承） |
| 内部类 `DefaultFeignBuilderConfiguration` | L224-235 | **prototype** 作用域的 `Feign.Builder`（每个客户端拿独立 Builder，互不污染） |
| 内部类 `SpringDataConfiguration` | L184-222 | 条件 `Pageable + DataWebProperties`（L185）；`PageableSpringQueryMapEncoder`（L210-220），page/size/sort 参数名从 `spring.data.web` 读取（L202-206/L214-218）——**与 MVC 分页参数自动一致** |
| 内部类 `MicrometerConfiguration` | L282-302 | 见 7.1 节 |

`Feign.Builder` 的原型 Bean 有两个版本：无熔断时 `Feign.builder().retryer(retryer)`（L231-233）；熔断开启时换成 `FeignCircuitBreaker.builder()`（L265-270）。**注意熔断版的条件是 `@ConditionalOnMissingBean({Feign.Builder.class, CircuitBreakerFactory.class})`**（L244）——用户自定义了 `Feign.Builder` Bean 会让熔断 Builder 消失。

## 3.6 FeignClientProperties：配置属性全景

【源码证据】`FeignClientProperties.java:49` `@ConfigurationProperties("spring.cloud.openfeign.client")`：

- 顶层四键：`defaultToProperties`（L52，true）、`defaultConfig`（L54，字符串 `"default"`）、`config`（L56，`Map<String, FeignClientConfiguration>`，**key = contextId**）、`decodeSlash`（L62，true）、`removeTrailingSlash`（L67，false）。
- `FeignClientConfiguration`（L131-360）即 `config.<contextId>.*` 的载体，**全部字段默认 null**（除两个 map）：`loggerLevel/connectTimeout/readTimeout/retryer/errorDecoder/requestInterceptors/responseInterceptor/defaultRequestHeaders/defaultQueryParameters/dismiss404/decoder/encoder/contract/exceptionPropagationPolicy/capabilities/queryMapEncoder/followRedirects/url/micrometer.enabled(默认 true，L367)`。**没有 path 字段**——path 只能来自注解；`url` 的 javadoc（L170-171）明确"注解 url 未设置时才生效"。

四个"毛边"配置在别处：`FeignHttpClientProperties`（连接池，`spring.cloud.openfeign.httpclient.*`，启用点在 `FeignAutoConfiguration.java:89`）、`FeignEncoderProperties`（`encoder.charset-from-content-type`，默认 false）、`FeignOAuth2Properties`、`FeignCircuitBreakerProperties`（见第七章）。

## 3.7 url 与超时的运行时刷新（refresh-enabled）

白话：配置中心（Nacos/Consul）改了某服务的 url 或超时，不重启就生效——机制是 Spring Cloud Context 的 **refresh 作用域**（本系列《Spring Cloud Config.md》的配套概念）。

【源码证据】开关 `spring.cloud.openfeign.client.refresh-enabled`（`FeignClientsRegistrar.isClientRefreshEnabled` L498-500，默认 false）。开启后 Registrar 把两个 Bean 注册为 **refresh 作用域 + scoped 代理**（`registerRefreshableBeanDefinition` L484-496）：

```java
String beanName = beanType.getCanonicalName() + "-" + contextId;   // L487
definitionBuilder.setScope("refresh");                             // L489
...
definitionHolder = ScopedProxyUtils.createScopedProxy(definitionHolder, registry, true);  // L493
```

- `Request.Options-{contextId}`（由 `OptionsFactoryBean` 产出，`getObject` L54-64 先套 config.default 再套 config.<contextId>）；
- `org.springframework.cloud.openfeign.RefreshableUrl-{contextId}`（由 `RefreshableUrlFactoryBean` 产出，`getObject` L53-69）。

消费端靠**命名约定**取回：`FeignClientFactoryBean.getOptionsByName`（L457-463）用 `Request.Options.class.getCanonicalName() + "-" + contextId`；`resolveTarget`（L559-563）同理。RefreshEvent 触发属性重绑 + refresh 作用域缓存失效后，下次调用经 scoped 代理重新执行 FactoryBean 的 getObject 拿到新值；`RefreshableHardCodedTarget.url()`（L48-51）**每次调用实时读**。注意同包的 `PropertyBasedTarget`（L52-58）只是"首次调用才解析"并缓存——它的受众是 AOT/native（镜像内改环境变量），不是刷新。

## 3.8 FeignClientBuilder：不用注解的编程式构建

【源码证据】`FeignClientBuilder.java`（2.0.2 引入，4.x 重写）。它不是另起炉灶，而是**内部持有一个 `FeignClientFactoryBean`**（javadoc L24-27："builds the Feign client exactly like it would be created by using the FeignClient annotation"）：

```java
this.feignClientFactoryBean.setName(FeignClientsRegistrar.getName(name));   // L70
...
this.url("").path("").dismiss404(false);                                    // L75：预置注解默认值
...
public <T> T build() { return this.feignClientFactoryBean.getTarget(); }    // L124-126
```

`forType(Class, String)` 入口（L42-44），支持 `url/contextId/path/dismiss404/inheritParentContext/fallback/customize(FeignBuilderCustomizer)`。url 不填同样走负载均衡分支（没有"必填"校验）。适用场景：动态目标、多态客户端工厂、测试。

## 3.9 AOT 与 GraalVM native：两件套

【源码证据】`aot/FeignChildContextInitializer.java`（@since 4.0.0，L49）：`BeanRegistrationAotProcessor`，处理目标就是主容器的 `FeignClientFactory` bean（L67-70）。它把**每个（非 `default.` 的）contextId 的子上下文**提前跑一遍 `buildContext + registerBeans`（L78-82），再用 Framework 的 `ApplicationContextAotGenerator` 生成初始化类（L107-109），最后向 `FeignClientFactory` 的注册代码追加 `addFeignChildContextInitializer` 静态方法（L112-123）——native 启动时子上下文按预生成代码创建，不再现场反射。

`aot/FeignClientBeanFactoryInitializationAotProcessor.java`（@since 4.0.0，L66）：身兼 `BeanRegistrationExcludeFilter`（把原始 `FeignClientFactoryBean` 的 BeanDefinition 排除出 AOT 处理，L83-87）与 `BeanFactoryInitializationAotProcessor`。它从 `FeignClientFactory.getConfigurations()` 反查所有客户端接口（L89-98），为每个生成 `register<ClassName>FeignClient` 注册方法（L154-181，逐条还原 FactoryBean 属性，L206-217），并注册 JDK 代理 hints（L168）与方法签名 hints（L110-143）。这也是 `@EnableFeignClients(clients=...)` 在 AOT 下被推荐的原因。

## 3.10 本章小结

- 注册链：`@EnableFeignClients` → `FeignClientsRegistrar`（默认配置 + 客户端扫描）→ `FeignClientFactoryBean` BeanDefinition（eager 默认 / lazy 可选）。
- 装配链：`FeignAutoConfiguration` 收集 Specification → `FeignClientFactory`（NamedContextFactory 子上下文）→ 首次 `getObject()` 触发子上下文创建 → 四层配置覆盖（配置类 ↔ properties，`defaultToProperties` 反转）→ `Targeter.target()` 产出代理。
- 三个易踩坑：**配置文件默认赢过 Java 配置类**；**配置类拦截器是替换、properties 拦截器是追加**；**name-only 客户端必须有 LoadBalancer Client，否则运行时才报错**。

# 四、契约与编解码：Spring MVC 注解如何变成 HTTP 请求

## 4.1 SpringMvcContract：一位翻译官

白话：feign 内核自带 `DefaultContract`（只认 `@RequestLine` 这类自有注解），对 Spring 用户毫无用处。`SpringMvcContract` 的职责是**把 Spring MVC 的参数注解翻译成 feign 的 `MethodMetadata`**——让服务端 Controller 与客户端 Feign 接口共用一套词汇。

【源码证据】`support/SpringMvcContract.java`（583 行，`extends Contract.BaseContract implements ResourceLoaderAware`，L98）。三条主流程：

- **类级检查（processAnnotationOnClass L191-202）**：类上出现 `@RequestMapping` 直接抛 `IllegalArgumentException`（L196，"@RequestMapping annotation not allowed on @FeignClient interfaces"）——这是 2021-10 提交 d6783a6f 引入的硬约束（此前版本还允许拼接）。类级路径前缀请用 `@FeignClient(path=...)`。类级 `@CollectionFormat` 仍支持（L198-201）；注意 `findMergedAnnotation` 会沿父接口查找，**父接口上的 `@RequestMapping` 同样触发异常**。
- **方法级解析（processAnnotationOnMethod L263-316）**：`@RequestMapping` 的 method 为空默认 GET（L277-279）且只允许一个（`checkOne` L280）；path 只取一个值（L284）并支持 `${}` 占位符（L318-323 经 Environment 解析）；补前导 `/`（L290-292）、按 `removeTrailingSlash` 去尾斜杠（L293-295）；随后 `parseProduces`（→Accept 头，L391-400）、`parseConsumes`（→Content-Type 头，**只取第一个值**，L402-408）、`parseHeaders`（k=v 解析，`!=` 取反被跳过，L410-421）、`parseParams`（L433-438，取反表达式仅 debug 日志）。
- **参数级解析（processAnnotationsOnParameter L337-375）**：4.2 节详解。

`parseAndValidateMetadata` 覆写（L205-220）里还有两个 5.x 行为值得知道：configKey 用带接口全名的 `Feign.configKey(targetType, method)`（L206，即熔断器名、日志名的来源）；**GET 方法带"未注解参数"会打警告**（L209-217，提交 deabba62 #1194，2025-05 引入——因为未注解参数会静默成为 body，GET+body 大多数服务器不支持），首参 `URI` 豁免（L228-232）。

## 4.2 参数名解析：合成发生在处理器之前

直觉上"显式 value → 处理器 → 反射参数名"，实际顺序是**反射回退发生在处理器消费之前**：

【源码证据】`synthesizeWithMethodParameterNameAsFallbackValue`（L466-478）：

```java
Object defaultValue = AnnotationUtils.getDefaultValue(parameterAnnotation);        // L468
if (defaultValue instanceof String && defaultValue.equals(annotationAttributes.get(VALUE))) {
    String[] parameterNames = PARAMETER_NAME_DISCOVERER.getParameterNames(method); // L472
    if (shouldAddParameterName(parameterIndex, parameterTypes, parameterNames)) {
        annotationAttributes.put(VALUE, parameterNames[parameterIndex]);           // L474
    }
}
return AnnotationUtils.synthesizeAnnotation(...);                                  // L477
```

1. 参数注解类型命中某个 `AnnotatedParameterProcessor`（L352-354）；
2. **若注解 value 为空串（默认值）**，用 `DefaultParameterNameDiscoverer`（L110，涵盖 `-parameters` 编译选项）把编译期参数名合成进注解（L466-478）——没开 `-parameters` 又不写 value 时，处理器里的 `checkState` 会直接抛 "RequestParam.value() was empty..."；
3. 处理器消费合成后的注解，`context.setParameterName(name)` 委托 feign `BaseContract.nameParam`（`SimpleAnnotatedParameterContext` L536-538）。

另外四条"免名通道"：`@RequestParam`/`@RequestHeader` 参数为 Map（或 HttpHeaders）时直接设 `queryMapIndex`/`headerMapIndex`（RequestParamParameterProcessor L54-59、RequestHeaderParameterProcessor L55-59）；`@SpringQueryMap`/feign `@QueryMap`/`Pageable` 设 `queryMapIndex`（SpringMvcContract L340-347，Pageable 在已有 QueryMap 参数时让位，L341-343）。

## 4.3 七个参数处理器（annotation/ 包）

【源码证据】SPI 接口 `AnnotatedParameterProcessor.java`：`getAnnotationType()`（L37）+ `processArgument(context, annotation, method)`（L46，返回值含义是"该参数是否为 HTTP 注解参数"——false 则可能成为 body）。默认注册顺序见 `getDefaultAnnotatedArgumentsProcessors`（L451-464）：MatrixVariable → PathVariable → RequestParam → RequestHeader → QueryMap → RequestPart → CookieValue。用户自定义处理器经 `FeignClientsConfiguration.feignContract` 的 `parameterProcessors` 注入（L89/L128）。

| 处理器 | 关键行为（行号） |
|---|---|
| PathVariableParameterProcessor | required 校验（L53-54）；名字进 URL/查询/头模板；**URL 里没有 `{name}` 时进 formParams**（L58-63） |
| RequestParamParameterProcessor | Map 参数转 queryMapIndex（L54-59，"Query map can only be present once."）；普通参数 `template().query(name, "{name}")`（L65-68） |
| RequestHeaderParameterProcessor | Map 或 `HttpHeaders` 转 headerMapIndex（L55-59）；普通参数进 header 模板（L64-67） |
| QueryMapParameterProcessor | 标记 `@SpringQueryMap` 参数位（L47-49），运行期交给 QueryMapEncoder |
| RequestPartParameterProcessor | 名字进 formParams（L55-57），multipart 场景 |
| CookieValueParameterProcessor | **每个参数整体重写 `Cookie` 头**：先 removeHeader 再追加 `"; name={name}"`（L56-69） |
| MatrixVariableParameterProcessor | **改写 URI 模板为 `;name={name}`**（L87-94）——因为 feign 展开时 pct-encode 会毁掉 `;`/`=`，把分隔符放进字面量才能保住矩阵变量语义 |

## 4.4 编码：SpringEncoder 与三层编解码装饰

白话：body 对象怎么变成请求字节？答案仍是"复用 Spring MVC"——`SpringEncoder` 内部就是 `HttpMessageConverter` 的调用器。

【源码证据】`support/SpringEncoder.java`：

- **5.0 的结构变化**：转换器来源从直接注入 `HttpMessageConverters` 改为 `ObjectProvider<FeignHttpMessageConverters>`（L74，对应 Boot 4/Framework 7 的转换器体系改造，提交 3012cfa5）。新类 `FeignHttpMessageConverters`（L47-72）懒加载构建：`HttpMessageConverters.forClient().registerDefaults()` → Boot 4 的 `ClientHttpMessageConvertersCustomizer` 定制 Builder → 本项目 `HttpMessageConverterCustomizer`（@since 3.1.0）直接增删最终列表。
- **encode 决策树（L90-112）**：Content-Type 为 multipart 或 form-urlencoded（`isFormRelatedContentType` L222-228）→ 整体委托 feign-form 的 `SpringFormEncoder`（L100-103）；否则走 `encodeWithMessageConverter`（L114-169）——遍历转换器，`GenericHttpMessageConverter` 走带泛型的 checkAndWrite，写完**先 `request.headers(null)` 再从 converter 输出重建全部头**（L131-141），charset 三选一（L143-158）：`charsetFromContentType=true` 时取 converter 输出的 charset（`FeignEncoderProperties.java:34`，默认 false）；binary/protobuf/ByteArray → null（L171-175）；**其余一律 UTF-8**（L155-157）。找不到 converter 抛 `EncodeException`（L163-168）。
- **multipart 的 POJO part**：classpath 有 `AbstractFormWriter`（如 `JsonFormWriter`）时，`FeignClientsConfiguration` 换用 `SpringPojoFormEncoder`（L173-182）把该 writer `addFirstWriter`（L179）进 feign-form 的 multipart 处理器首位——POJO part 序列化为 JSON part（`JsonFormWriter` 注入的是 **Jackson 3** 的 `tools.jackson ObjectMapper`，L33-34）。**坑：`JsonFormWriter` 只是 `@Component`（L30），自动配置类既不扫描也不注册它**——只有用户恰好组件扫描了 `org.springframework.cloud.openfeign.support` 包它才存在。

## 4.5 解码：Optional → ResponseEntity → Spring 三层装饰

【源码证据】`FeignClientsConfiguration.java:114`：

```java
return new OptionalDecoder(new ResponseEntityDecoder(new SpringDecoder(messageConverters)));
```

- `SpringDecoder.decode`（L52-62）：类型白名单 Class/ParameterizedType/WildcardType（L53），然后 `new HttpMessageConverterExtractor(type, converters)` + `extractData(new FeignResponseAdapter(response))`——**就是 Spring MVC `RestTemplate` 解码响应的同一套类**，适配器把 feign Response 伪装成 ClientHttpResponse（L64-102）。
- `ResponseEntityDecoder`（L50-64）：返回类型是 `ResponseEntity<T>`/`HttpEntity<T>` 时解出内部类型再包回去（L66-89）。
- `OptionalDecoder`：返回类型 `Optional<T>` 时把空响应转 `Optional.empty()`。
- 404 特殊语义：`dismiss404=true` 时代码走到这里（2.3 节 InvocationContext L75-77），各解码器返回 `Util.emptyValueOf` 定义的空值。

## 4.6 Pageable 的两条路线

- **路线 A（默认）：QueryMapEncoder**。`@FeignClient` 接口方法带 `Pageable` 参数时，契约期设 `queryMapIndex`（SpringMvcContract L340-347），运行期由 `PageableSpringQueryMapEncoder`（extends feign `BeanQueryMapEncoder`，L45，@since 2.2.8）展开成 `page=0&size=20&sort=name,asc` 查询参数（encode L80-106）。参数名取自 `spring.data.web.pageable.*`（装配见 `FeignClientsConfiguration` L210-220），与 MVC 服务端天然一致。**它还处理 Framework 7 的变化**：`HttpHeaders` 不再实现 `MultiValueMap`，特判直接 putAll（L98-100）。
- **路线 B：Encoder**。当接口里已有真正的 QueryMap 参数、Pageable 落入 body 位置时，由 `PageableSpringEncoder`（@since 2.1.1）在编码期拦截 Pageable/Sort 写进 `template.query`（encode L85-112）。两处的 sort 参数拼接都**硬编码字面 `"%2C"`**（L123-129/L108-120），`ignoreCase` 常量是小写 `"ignorecase"`（L60）。

## 4.7 Jackson 2 与 Jackson 3 如何共存

【源码证据】5.x 的 Page/Sort 适配模块已经切到 Jackson 3，但注解仍是 Jackson 2 包名：

- `PageJacksonModule` 基类是 `tools.jackson.databind.JacksonModule`（L47），而 POJO 注解 `JsonAlias/JsonIgnore/JsonIgnoreProperties/JsonProperty` 仍是 `com.fasterxml.jackson.annotation.*`（L23-26）——**这是 Jackson 3 的官方设计：注解包被原样保留，新旧注解制件通用**，并非"迁移没做完"。
- `SortJsonComponent` 是 Jackson 3 API 更名的活标本：`ValueSerializer`/`ValueDeserializer`（L44/L60，原 JsonSerializer/JsonDeserializer）、`tools.jackson.core.*`（L22-30）。反序列化同时接受顶层数组与 `{"orders":[...]}` 包装（L63-73）。
- `PageJacksonModule.setupModule`（L60-63）给 `Page/Pageable` 挂 MixIn，`totalElements` 容忍五种别名（L96-97），构造器四级回退（L99-114）——专为兼容不同服务端返回的 Page JSON。
- 注册点：`FeignAutoConfiguration$FeignJacksonConfiguration`（L129-147），条件 `JacksonModule + Page + Sort` 三类同时在 classpath，开关 `spring.cloud.openfeign.autoconfiguration.jackson.enabled` 默认开。根 pom 的 `jackson.version=2.22.3` 仍在（Jackson 2 生态的依赖管理），但核心类已不触碰 Jackson 2 API。

## 4.8 本章小结

- 契约期：`SpringMvcContract` 把 MVC 注解翻译成 `MethodMetadata`——类级 `@RequestMapping` 是硬错误；参数名合成发生在处理器之前；`@RequestBody` **没有任何处理器**，未注解参数天然是 body。
- 编码期：form/multipart 委托 feign-form，其余走 Spring `HttpMessageConverters`，charset 默认 UTF-8；Pageable 默认走 QueryMapEncoder 路线。
- 解码期：三层装饰链 `Optional → ResponseEntity → Spring`，404 语义由 `dismiss404` 在更上游决定。
- Jackson 3 已就位（模块/序列化器/ObjectMapper），注解包保持 Jackson 2——升级 Boot 4 后 Feign 客户端的 JSON 行为与 MVC 服务端一致。

---

# 五、负载均衡与重试：name-only 客户端的路由内核

白话：`@FeignClient("user-service")` 生成的代理，发请求时的 URL 是 `http://user-service/...`——主机名是**服务名不是 IP**。谁来翻译？`loadbalancer/` 包用一层 `feign.Client` 装饰器接住：**在 Client.execute 的入口把服务名换成真实实例**。这层是《Spring Cloud LoadBalancer.md》的消费端视角。

## 5.1 FeignBlockingLoadBalancerClient：execute 全流程

【源码证据】`loadbalancer/FeignBlockingLoadBalancerClient.java`（implements `feign.Client`，L61-67 持有 delegate/loadBalancerClient/loadBalancerClientFactory/transformers）：

```java
final URI originalUri = URI.create(request.url());                          // L80
String serviceId = originalUri.getHost();                                   // L81：服务名就是 host
String hint = getHint(serviceId);                                           // L83：默认 "default"
DefaultRequest<RequestDataContext> lbRequest =
        new DefaultRequest<>(new RequestDataContext(buildRequestData(request), hint));  // L84-85
// L86-90：收集 LoadBalancerLifecycle，逐个 onStart
ServiceInstance instance = loadBalancerClient.choose(serviceId, lbRequest); // L91：选实例
// L94-107：选不到 → DISCARD + 合成 503 响应
String reconstructedUrl =
        loadBalancerClient.reconstructURI(instance, originalUri).toString(); // L108：重建 URL
Request newRequest = buildRequest(request, reconstructedUrl, instance);      // L109
return executeWithLoadBalancerLifecycleProcessing(delegate, options, newRequest,
        lbRequest, lbResponse, supportedLifecycleProcessors);                // L110-111
```

三个关键语义：

1. **选不到实例不抛异常**——直接返回 status=503 的合成 Response（L94-107）。调用方拿到的是"正常返回的 503"，走不到 ErrorDecoder 的异常分支，只会在 Decoder/调用侧看到 FeignException.FeignClientException$ServiceUnavailable。
2. **transformer 时机在 reconstructURI 之后**（buildRequest L119-127 逐个应用 `LoadBalancerFeignRequestTransformer`）——transformer 看到的已是实例真实地址，别在实现里假设还是服务名。
3. LoadBalancerLifecycle 三段通知（onStart/onStartRequest/onComplete）经 `LoadBalancerUtils.executeWithLoadBalancerLifecycleProcessing`（L50-71）接入——这是《Spring Cloud LoadBalancer.md》第 7 章的生命周期观测在 Feign 侧的落地。

## 5.2 可重试变体：RetryableFeignBlockingLoadBalancerClient

【源码证据】`loadbalancer/RetryableFeignBlockingLoadBalancerClient.java`（@since 2.2.6）——把整个 execute 包进 **spring-retry 的 RetryTemplate**（L105-106）：

- 重试策略二选一（buildRetryTemplate L205-219）：该服务关闭重试或策略为 null → `NeverRetryPolicy`；否则 → `InterceptorRetryPolicy`（L214-217，spring-cloud-loadbalancer 提供，配合 `LoadBalancedRetryFactory`）。
- **重试次数不在本仓库**：`LoadBalancedRetryPolicy` 内部按 `spring.cloud.loadbalancer.retry.*`（maxRetriesOnSameServiceInstance / maxRetriesOnNextServiceInstance / retryableStatusCodes / avoidPreviousInstance）决定；本层只负责"触发"——**响应状态码命中 `retryPolicy.retryableStatusCode()` 时抛 `LoadBalancerResponseStatusCodeException`**（L160-170）驱动 RetryTemplate 重试，该异常复制了响应 body（L35-44）保证重试时可重读。
- 每轮重试可换实例：从 `LoadBalancedRetryContext` 取实例（L119），并把上一实例写进 `RetryableRequestContext.setPreviousServiceInstance`（L125）供 avoidPreviousInstance 使用。
- **重试耗尽不抛异常**：`LoadBalancedRecoveryCallback` 直接返回最后一次响应（L172-177）。
- 与 feign 内核 Retryer 的关系：**两套独立机制**。内核 Retryer 作用于"ErrorDecoder 判定 RetryableException"（默认 NEVER_RETRY，永不启用）；本层作用于"HTTP 层状态码/网络异常 + 换实例"。都开时外层 RetryTemplate 先转，每次重试内部仍可能有 feign 重试。

## 5.3 装配互斥：谁成为那个 Client

【源码证据】`loadbalancer/FeignLoadBalancerAutoConfiguration.java`：

- 类级（L47-51）：`@ConditionalOnClass(Feign)` + `@ConditionalOnBean({LoadBalancerClient, LoadBalancerClientFactory})`，且 `@AutoConfigureBefore(FeignAutoConfiguration)`——保证 Client bean 在 Feign 客户端解析前就绪。
- `@Import` 三个 delegate 配置类，注释写明 "Order is important here, last should be the default"（L53-57）：`HttpClient5FeignLoadBalancerConfiguration`（hc5.enabled 默认 true）→ `Http2ClientFeignLoadBalancerConfiguration`（http2client.enabled **默认关**）→ `DefaultFeignLoadBalancerConfiguration`（JDK HttpURLConnection，兜底）。
- 每个配置类内部都产两个 Client bean，由 `OnRetryNotEnabledCondition`（AnyNestedCondition，L37-50：缺 RetryTemplate 类 / 缺 `LoadBalancedRetryFactory` bean / `spring.cloud.loadbalancer.retry.enabled=false`，任一命中）互斥：**默认（有 spring-retry + loadbalancer）时 `feignRetryClient` 生效、普通 `feignClient` 被否决**（`DefaultFeignLoadBalancerConfiguration` L50-69）——**加一个 spring-retry 依赖就悄悄改变重试行为**，这是"半开默认"的典型案例。
- X-Forwarded：`XForwardedHeadersTransformer` 默认注册（L60-65），但内部开关是 `spring.cloud.loadbalancer.x-forwarded.enabled`（L49-50，默认 false），开启后给请求加 `X-Forwarded-Host`/`X-Forwarded-Proto`（L55-56）。

## 5.4 直连 url 与 LB 的"解包"行为

3.4.1 节埋的伏笔在这里收尾：`FeignClientFactoryBean.getTarget()` 直连分支（L503-512）发现容器里的 Client 是 `FeignBlockingLoadBalancerClient` 或其 Retryable 版时，**主动取 `getDelegate()` 解包直连**——不报错、不走负载均衡，也因此**吃不到 X-Forwarded 与自定义 transformer**。对一个服务"平时走注册中心、个别环境写死 IP"的需求，正确姿势是 properties 里的 `url` 键（走 PropertyBasedTarget/RefreshableUrl），而不是换依赖。

## 5.5 本章小结

- name-only 客户端的调用链：代理 → SynchronousMethodHandler → `Client.execute` → `FeignBlockingLoadBalancerClient.execute`（choose → reconstructURI → transformer → delegate）→ LoadBalancerLifecycle 三段通知。
- 两层重试正交：feign Retryer（默认关，Retry-After 驱动）与 LB 重试客户端（默认开，状态码/异常驱动 + 换实例，spring-retry 承载）。
- 三个反直觉：选不到实例返回合成 503 而非异常；Retryable 版选不到实例反而拿原始服务名 URL 硬发（L135-145）；直连 url 会解包 LB 客户端。

# 六、熔断与降级：FeignCircuitBreaker 家族

白话：`@FeignClient(fallback=...)` 为什么能降级？feign 内核并不知道"熔断"——Spring Cloud OpenFeign 的做法是：**熔断开启时把子上下文里的 `Feign.Builder` 换成 `FeignCircuitBreaker.Builder`，后者把默认 InvocationHandlerFactory 换成包了熔断器的版本**。真正的熔断语义（断路器状态机、半开探测）在 Spring Cloud CircuitBreaker（本系列专篇）里，本层只做"调用包装 + fallback 分派"。

## 6.1 Targeter：装配期的拦截点

【源码证据】`Targeter.java:27-28` 只有一个方法：

```java
<T> T target(FeignClientFactoryBean factory, Feign.Builder feign, FeignClientFactory context,
        Target.HardCodedTarget<T> target);
```

`FeignClientFactoryBean` 在 loadBalance（L443-444）与直连（L518-519）两条路径的**最后一步**都调它。实现有两个：

- `DefaultTargeter`（L30）：`return feign.target(target);`——什么也不做，交给 feign 内核。
- `FeignCircuitBreakerTargeter.target`（L46-61）：builder 不是 `FeignCircuitBreaker.Builder` 就直通（L48-49）；否则按 contextId 优先取名（L51），**fallback 与 fallbackFactory 二选一**（L52-59），把降级类塞进 builder 后再 target。fallback 实例从**子上下文**取（`getFromContext` L76-108：`context.getInstance(feignClientName, beanType)`，兼容 FactoryBean，类型不匹配抛 IllegalStateException）——**降级类因此可以注入其他 Bean**。

装配决策（`FeignAutoConfiguration.java`）：`DefaultFeignTargeterConfiguration`（L149-159）在 `FeignCircuitBreakerDisabledConditions`（AnyNestedCondition：缺 CircuitBreaker 类 / `spring.cloud.openfeign.circuitbreaker.enabled=false 或未设置`，PARSE_CONFIGURATION 阶段，L26-35）成立时注册 DefaultTargeter；`CircuitBreakerPresentFeignTargeterConfiguration`（L161-230）在 `@ConditionalOnClass(CircuitBreaker)` + **`enabled=true`（无 matchIfMissing，默认关）**时注册 `FeignCircuitBreakerTargeter`（L201-210）。**子上下文里同步有两个版本的 prototype `Feign.Builder`**（3.5 节），Targeter 与 Builder 的条件严格对偶。

## 6.2 FeignCircuitBreaker.Builder：替换 InvocationHandlerFactory

【源码证据】`FeignCircuitBreaker.java`：

```java
// L92-97
public Feign build(final FallbackFactory<?> nullableFallbackFactory) {
    super.invocationHandlerFactory((target, dispatch) -> new FeignCircuitBreakerInvocationHandler(
            circuitBreakerFactory, feignClientName, target, dispatch, nullableFallbackFactory,
            circuitBreakerGroupEnabled, circuitBreakerNameResolver));
    return super.build();
}
```

`@FeignClient(fallback=X.class)` 的适配点在 `target(Target, T fallback)`（L79-81）：普通 fallback 类被统一包装成 `FallbackFactory.Default`（`FallbackFactory.java:57-85`，create 忽略 cause 直接返回常量实例）——所以两种属性最终走同一条分派逻辑。

## 6.3 FeignCircuitBreakerInvocationHandler：运行期的降级分派

【源码证据】`FeignCircuitBreakerInvocationHandler.java`，invoke（L79-118）：

```java
String circuitName = circuitBreakerNameResolver
        .resolveCircuitBreakerName(resolvedFeignClientName, target, method);      // L99-100
CircuitBreaker circuitBreaker = circuitBreakerGroupEnabled
        ? factory.create(circuitName, resolvedFeignClientName)
        : factory.create(circuitName);                                            // L101-102
Supplier<Object> supplier = asSupplier(method, args);                            // L103
Function<Throwable, Object> fallbackFunction = throwable -> {
    Object fallback = this.nullableFallbackFactory.create(throwable);            // L106：原始异常传入
    return this.fallbackMethodMap.get(method).invoke(fallback, args);            // L108
};
return circuitBreaker.run(supplier, fallbackFunction);                           // L115
```

四个要点：

1. **fallback 方法匹配是"同接口方法"恒等映射**：`toFallbackMethod`（L167-174）把 dispatch 的 Method `setAccessible(true)` 后做 Method→Method 映射——fallback 类必须实现同一个接口方法，而非按方法名/参数匹配（注释 L158-166 解释这是为绕过 package-private 接口的反射限制）。
2. **异常传递链**：业务异常（受检异常包装成 RuntimeException，L147-148）→ CircuitBreaker.run 捕获 → 作为 cause 传入 `create(throwable)`（L106）→ **fallback 自身抛出的异常经 `unwrapAndRethrow`（L120-131）取代原始异常抛给调用方**。
3. **无 fallback 时**（L117）`circuitBreaker.run(supplier)`——熔断器打开直接抛 `CallNotPermittedException`，与《Spring Cloud CircuitBreaker.md》的通用语义一致。
4. `asSupplier`（L133-156）捕获当前线程与 `RequestContextHolder`，跨线程执行时恢复请求属性——保证异步调用里 RequestAttributes 不丢。

## 6.4 熔断器命名：默认是"去掉标点的 configKey"

【源码证据】`FeignAutoConfiguration.java:212-228`：

```java
static class DefaultCircuitBreakerNameResolver implements CircuitBreakerNameResolver {
    public String resolveCircuitBreakerName(...) {
        return Feign.configKey(target.type(), method);   // L216：形如 "GitHub#owner(String)"
    }
}
static class AlphanumericCircuitBreakerNameResolver extends DefaultCircuitBreakerNameResolver {
    return super.resolveCircuitBreakerName(...).replaceAll("[^a-zA-Z0-9]", "");   // L225
}
```

选择开关 `spring.cloud.openfeign.circuitbreaker.alphanumeric-ids.enabled`（`FeignCircuitBreakerProperties.java:63`，**默认 true**，5.1.0-M1 新增该属性类）——即默认熔断器名是 `GitHubownerString` 这类**去掉所有非字母数字字符**的串。给 Resilience4J 按名配超时/熔断阈值时极易踩坑；想用原始 configKey 名，把开关设为 false 或自注册 `CircuitBreakerNameResolver`。分组模式（`group.enabled`，L49，默认 false）开启时把 client 名作为分组传给 factory（InvocationHandler L101-102）。

## 6.5 本章小结

- 接缝点三级：Targeter（装配期选择降级能力）→ FeignCircuitBreaker.Builder（build 期换 InvocationHandlerFactory）→ InvocationHandler（运行期 run(supplier, fallback)）。
- fallback 的两条军规：**实现同一接口方法**；**fallback 里的异常会覆盖原始异常**。
- 熔断默认关（`enabled=true` 显式开启）；名字默认被清洗（alphanumeric-ids 默认 true）——两个默认都与直觉相反。

---

# 七、外围能力包：观测、缓存、安全、压缩

## 7.1 观测：Micrometer 双 Capability，Observation 优先

白话：调用耗时的指标与链路追踪从哪来？feign-micrometer 模块提供两个 Capability，Spring Cloud OpenFeign 把它们作为**子上下文 Bean** 注入 Builder。

【源码证据】`FeignClientsConfiguration$MicrometerConfiguration`（L282-302）：

```java
@ConditionalOnProperty(name = "spring.cloud.openfeign.micrometer.enabled", matchIfMissing = true)  // L283
@ConditionalOnClass({MicrometerObservationCapability.class, MicrometerCapability.class, MeterRegistry.class})
@Conditional(FeignClientMicrometerEnabledCondition.class)
protected static class MicrometerConfiguration {
    @Bean @ConditionalOnMissingBean
    @ConditionalOnBean(type = "io.micrometer.observation.ObservationRegistry")                    // L290
    public MicrometerObservationCapability micrometerObservationCapability(...) { ... }
    @Bean
    @ConditionalOnBean(type = "io.micrometer.core.instrument.MeterRegistry")                      // L296
    @ConditionalOnMissingBean({MicrometerCapability.class, MicrometerObservationCapability.class}) // L297
    public MicrometerCapability micrometerCapability(...) { ... }
}
```

- **优先级**：Observation API 版（能同时产出 metrics + trace，链路观测挂到《Micrometer Tracing.md》体系）优先；无 ObservationRegistry 时退化到纯 metrics 版。挂载方式即 2.4 节的 Capability 机制。
- **per-client 开关的实现巧思**：`FeignClientMicrometerEnabledCondition`（L32-50）读的是 `FeignClientProperties` 的 config map，key 从 `spring.cloud.openfeign.client.name` 取——这个属性正是 3.3 节 `FeignClientFactory` 构造时注入子上下文的（值=当前 contextId）。所以 `spring.cloud.openfeign.client.<name>.micrometer.enabled=false` 能只关一个客户端。三层关闭路径：全局开关（L283）→ per-client 开关 → 自注册同名 Capability Bean（@ConditionalOnMissingBean 让位）。

## 7.2 缓存：CachingCapability 复用 Spring CacheInterceptor

【源码证据】`CachingCapability.java`（@since 3.1.0）——`implements Capability`，`enrich(invocationHandlerFactory)`（L38-40）返回 `FeignCachingInvocationHandlerFactory`。后者（L49-80）把默认 InvocationHandler 包一层：

```java
final InvocationHandler delegateHandler = delegateFactory.create(target, dispatch);  // L50
return (proxy, method, args) -> cacheInterceptor.invoke(new MethodInvocation() {     // L53
    public Object proceed() { return delegateHandler.invoke(proxy, method, args); }  // L65-67
    ...
});
```

它**直接复用 Spring AOP 的 `org.springframework.cache.interceptor.CacheInterceptor`**：`@Cacheable/@CachePut/@CacheEvict` 写在 Feign 接口方法上，SpEL key（`#p0`、`#root.methodName`…）、CacheManager、KeyGenerator 全部按 Spring Cache 标准语义工作——Feign 侧没有任何缓存代码。装配（`FeignAutoConfiguration.java:122-127`）：`spring.cloud.openfeign.cache.enabled`（默认 true）**且容器里已有 `CacheInterceptor`**（即应用自己开了 `@EnableCaching`）——没开缓存的应用不会注入。它是全局 Capability：所有客户端一起生效。

## 7.3 安全：OAuth2AccessTokenInterceptor

【源码证据】`security/OAuth2AccessTokenInterceptor.java`（@since 4.0.0）。apply（L95-100）很简单：取 token，`template.header("Authorization")` 先清后设 `"Bearer <token>"`。取 token 的两级策略（getToken L102-118）：

1. 配置了 `spring.cloud.openfeign.oauth2.clientRegistrationId` → 用它（L104-109）；
2. 否则取 `feignTarget().url()` 的 **host**（getServiceId L137-144）——对 name-only 客户端即**服务名本身作为 clientRegistrationId**，适合"每个下游服务一个 registration"的约定；
3. 都取不到 → IllegalStateException（L117）。

真正发 token 的是 `OAuth2AuthorizedClientManager.authorize`（L120-135）：principal 从 SecurityContextHolder 取、缺省匿名（L125-128）。**没有"模式开关"**——token relay（用户会话场景）与 client_credentials（机器场景）由 manager 按 registration 的 grantType 自动处理，token 缓存与刷新都在 Spring Security 内部。装配（`FeignAutoConfiguration` L252-276）默认关（`oauth2.enabled` 无 matchIfMissing）；`FeignOAuth2Properties`（前缀 L26，enabled 默认 false）4.3.2 才独立成属性类（1.6.3 节）。

## 7.4 压缩：两个"声明头"拦截器，不真正压缩

【源码证据】`encoding/` 包。先破除误解：**这两个自动配置都不做压缩/解压本体**——

- `FeignAcceptGzipEncodingAutoConfiguration`（开关 `spring.cloud.openfeign.compression.response.enabled`，默认关）→ 拦截器给**请求**加 `Accept-Encoding: gzip, deflate`（固定两种，`FeignAcceptGzipEncodingInterceptor.java:44-48`），请服务器压缩**响应**；响应解压由底层 Client（如 Apache HC5）处理。
- `FeignContentGzipEncodingAutoConfiguration`（开关 `spring.cloud.openfeign.compression.request.enabled`，默认关）→ 拦截器按条件给请求加 `Content-Encoding: gzip` **声明头**（L44-49），是否真压取决于底层客户端；条件是 Content-Type 命中配置 mimeTypes（L99-116，默认 `text/xml, application/xml, application/json`）且 **Content-Length 超过 2048**（L78-92：恰好一个 Content-Length 头，L81——chunked 请求永远不命中）。
- `BaseRequestInterceptor.addHeader`（L51-56）只在头**不存在**时添加——用户自定义同名头优先。
- 属性类 `FeignClientEncodingProperties` 前缀统一是 `spring.cloud.openfeign.compression.request`（L30），**Accept 侧复用同一属性类**——给响应协商配阈值要意识到它们同源。

## 7.5 hateoas 与其他小件

`hateoas/FeignHalAutoConfiguration`：classpath 有 Spring HATEOAS 时注册 `WebConvertersCustomizer`，让解码端认识 HAL 超媒体表示（它是 `AutoConfiguration.imports` 五项之首）。`FeignErrorDecoderFactory`（`ErrorDecoderFactory.java:35`）按客户端接口类型定制 ErrorDecoder，被 `configureUsingConfiguration` 消费（FactoryBean L217-221）。`CollectionFormat`（CSV/EXPLODED）决定集合查询参数的展开方式，类/方法级皆可。

## 7.6 本章小结

- 观测：双 Capability、Observation 优先、per-client 开关靠子上下文属性名实现——三处都是"NamedContextFactory 子容器"模式的红利。
- 缓存/安全/压缩的共同点：**全部是"已有能力的接线"**——Spring Cache、Spring Security OAuth2、底层 HTTP 客户端的压缩，本项目只做条件装配与适配器。
- 记住三个默认值：micrometer 默认开、oauth2 默认关、两个 gzip 都默认关。

# 八、贯通视图：三条生命线

## 8.1 启动时间线：从注解到可用代理

```
① @EnableFeignClients → @Import(FeignClientsRegistrar)                      (EnableFeignClients.java:41)
② Registrar.registerDefaultConfiguration：default.<类名> 规格                (FeignClientsRegistrar.java:157-170)
③ Registrar.registerFeignClients：扫描 → <name>.FeignClientSpecification
   + FeignClientFactoryBean BeanDefinition（eager）                          (L172-263)
   └─ refresh-enabled 时附加注册 Options/Url refresh 作用域 bean             (L261-262)
④ Boot 自动配置：FeignLoadBalancerAutoConfiguration（before FeignAutoConfiguration）
   → Client bean（Retryable 或普通版）就位
⑤ FeignAutoConfiguration：feignContext() 收集全部 Specification
   → FeignClientFactory 就绪                                                 (FeignAutoConfiguration.java:103-108)
⑥ 首次 getBean（或注入时）：FeignClientFactoryBean.getObject → getTarget     (FactoryBean L465-520)
⑦ feign(context)：getInstance 逐个拉组件 → 首次触发该 contextId 子上下文创建  (NamedContextFactory.getContext L121-130)
   子上下文注册序：客户端配置类 → default.* → FeignClientsConfiguration        (registerBeans L145-161)
⑧ configureFeign 四层覆盖 → applyBuildCustomizers                            (FactoryBean L177-201/L164-175)
⑨ loadBalance/直连 分岔 → Targeter.target → Feign.Builder.target             (L438-449/L518-519)
⑩ ReflectiveFeign.newInstance → JDK 动态代理注入调用方                        (feign-core Feign.java:212-214)
```

## 8.2 一次 name-only 调用的一生

以 `userClient.getUser(1L)`（`@FeignClient("user-service")` + `@GetMapping("/users/{id}")`）为例：

```
① 代理 invoke →（熔断开启时）FeignCircuitBreakerInvocationHandler：
   解析熔断名 → circuitBreaker.run(supplier, fallback)                        (InvocationHandler L99-117)
② SynchronousMethodHandler.invoke：
   RequestTemplate 填参（{id}→1）→ findOptions → 重试循环                     (SynchronousMethodHandler L49-66)
③ targetRequest：RequestInterceptor 链（OAuth2/gzip/自定义）+ Target.apply   (L147-152)
   → 请求 URL 仍为 http://user-service/users/1
④ client.execute → FeignBlockingLoadBalancerClient.execute：
   serviceId=host → hint+RequestDataContext → onStart → choose(serviceId)     (LB Client L80-91)
   → reconstructURI 得 http://10.0.3.7:8080/users/1 → transformer → onStartRequest
   → delegate.execute（ApacheHttp5Client 真发 HTTP）                          (L108-111)
⑤ 响应回来：onComplete(SUCCESS) → 逐层上抛
⑥ ResponseHandler.handleResponse → ResponseInterceptor 链 → InvocationContext.proceed
   2xx → SpringDecoder → HttpMessageConverterExtractor → User POJO           (InvocationContext L69-100)
⑦（熔断开启时）supplier 正常返回 → fallbackFunction 不触发 → 返回调用方
```

## 8.3 一次异常的一生

下游返回 500 且带 `Retry-After: 1` 头：

```
① client.execute 拿到 500 → InvocationContext：非 2xx 且非 dismiss404 → decodeError
② DefaultErrorDecoder.decode：FeignException.errorStatus 构造异常；
   见 Retry-After 头 → 改抛 RetryableException                               (DefaultErrorDecoder L45-60)
③ runWithRetry：retryer.continueOrPropagate → 默认 NEVER_RETRY 直接 throw   (SynchronousMethodHandler L73-90)
④（若配了 Retryer.Default）克隆的 retryer 按指数退避 sleep 后重试            (DefaultRetryer L44-68)
⑤（若走的是 Retryable LB 客户端且是状态码触发）RetryTemplate 重试 + 换实例
   → 耗尽返回最后一次响应                                                     (Retryable LB Client L160-177)
⑥（熔断开启）异常从 supplier 抛出 → CircuitBreaker 记失败 → 打开时直接 CallNotPermittedException
   → fallbackFactory.create(cause) → fallback 方法返回降级值                  (InvocationHandler L104-116)
```

三层容错（feign Retryer → LB RetryTemplate → CircuitBreaker）各自独立、可叠加，默认只有"什么都不做"——**看清每一层的开关与触发条件，比背配置清单更重要**。

# 九、附录

## 9.1 配置属性速查表（`spring.cloud.openfeign.*`）

| 属性 | 默认 | 说明 |
|---|---|---|
| `client.default-to-properties` | true | false 时 Java 配置类赢过 properties（3.4.3） |
| `client.default-config` | default | `config` map 的兜底 key |
| `client.config.<contextId>.*` | — | 每客户端配置：connectTimeout/readTimeout/loggerLevel/retryer/errorDecoder/requestInterceptors/responseInterceptor/dismiss404/encoder/decoder/contract/queryMapEncoder/capabilities/defaultRequestHeaders/defaultQueryParameters/url/micrometer.enabled（3.6） |
| `client.refresh-enabled` | false | url/超时 refresh 作用域化（3.7） |
| `client.lazy-attributes-resolution` | false | 注解属性推迟到首次 getBean 解析（3.2） |
| `circuitbreaker.enabled` | **false** | 熔断集成总开关（6.1） |
| `circuitbreaker.group.enabled` | false | 熔断器按 client 分组（6.4） |
| `circuitbreaker.alphanumeric-ids.enabled` | true | 熔断名去掉非字母数字字符（6.4，5.1.0-M1 新增属性类） |
| `micrometer.enabled` | true | 全局观测开关（7.1） |
| `client.config.<id>.micrometer.enabled` | true | per-client 观测开关（7.1） |
| `cache.enabled` | true | CachingCapability（需容器已有 CacheInterceptor，7.2） |
| `oauth2.enabled` | false | OAuth2 令牌拦截器（7.3） |
| `oauth2.client-registration-id` | — | 指定 client registration；缺省用服务名（7.3） |
| `compression.request.enabled` | false | 请求 `Content-Encoding` 声明（7.4） |
| `compression.response.enabled` | false | 请求 `Accept-Encoding: gzip, deflate`（7.4） |
| `compression.request.mime-types` | text/xml, application/xml, application/json | 压缩声明命中类型 |
| `compression.request.min-request-size` | 2048 | Content-Length 阈值（7.4） |
| `encoder.charset-from-content-type` | false | charset 跟随 Content-Type（4.4） |
| `autoconfiguration.jackson.enabled` | true | Page/Sort Jackson 模块（4.7） |
| `httpclient.hc5.enabled` | true | Apache HC5 客户端（5.3） |
| `http2client.enabled` | false | JDK HttpClient 客户端（5.3） |
| 跨命名空间：`spring.cloud.loadbalancer.retry.enabled` | true | 决定 LB 客户端是否 Retryable（5.3） |
| 跨命名空间：`spring.cloud.loadbalancer.x-forwarded.enabled` | false | X-Forwarded 头（5.3） |

## 9.2 与 Spring HTTP Service Clients 的迁移对照

官方 feature-complete 公告指的替代品是 Framework 6.1+ 的 `@HttpExchange` + `HttpServiceProxyFactory`（Boot 3.2+ 自动配置；本系列《声明式HTTP Client.md》有 22 维对比与选型决策树）。迁移时的心智映射：

| OpenFeign 概念 | HTTP Service 对应物 | 备注 |
|---|---|---|
| `@FeignClient(name/url)` | `@HttpServiceClient` / Boot 属性 `spring.http.serviceclient.<group>.url` | 负载均衡由 commons 的 `LoadBalancerRestClientHttpServiceGroupConfigurer` 接管 |
| `@EnableFeignClients` 扫描 | `@ImportHttpServices(group=..., types=...)` | 显式分组，无 classpath 扫描 |
| SpringMvcContract（MVC 注解） | 同样支持 MVC 注解 + `@HttpExchange` | 词汇更统一 |
| 子上下文 FeignClientFactory | HttpServiceGroup 分组隔离（`ClientHttpRequestFactorySettings`） | 同样"每组一容器"思路 |
| feign Retryer/熔断 Targeter | commons `CircuitBreakerRestClientHttpServiceGroupConfigurer` + `@HttpServiceFallback` | 熔断从"换 InvocationHandler"改为"声明式 fallback" |
| feign Capability | 无对应物 | 观测走 RestClient 标准 observation |

结论不变：**存量 OpenFeign 无需恐慌性迁移**（5.x 仍在维护 Boot 4 适配）；新项目默认 HTTP Service，需要 Feign 生态特有的成熟插件（feign-form、feign-annotation-error-decoder 等）时再选 Feign。

## 9.3 源码阅读入口（30 个文件）

装配主线（第三章）：`EnableFeignClients` → `FeignClientsRegistrar` → `FeignClient` → `FeignAutoConfiguration` → `FeignClientFactory` → `FeignClientFactoryBean` → `FeignClientsConfiguration` → `FeignClientProperties` → `Targeter`/`DefaultTargeter` → `OptionsFactoryBean`/`RefreshableUrlFactoryBean`/`RefreshableHardCodedTarget`/`PropertyBasedTarget` → `FeignClientBuilder` → `aot/` 两件套。
契约编解码（第四章）：`SpringMvcContract` → `AnnotatedParameterProcessor` + annotation/ 七处理器 → `SpringEncoder` → `SpringDecoder`/`ResponseEntityDecoder` → `PageableSpringEncoder`/`PageableSpringQueryMapEncoder` → `FeignHttpMessageConverters` → `PageJacksonModule`/`SortJsonComponent`。
运行时（第五~七章）：`FeignBlockingLoadBalancerClient`/`RetryableFeignBlockingLoadBalancerClient`/`OnRetryNotEnabledCondition`/`LoadBalancerUtils` → `FeignCircuitBreakerTargeter`/`FeignCircuitBreaker`/`FeignCircuitBreakerInvocationHandler`/`FallbackFactory`/`CircuitBreakerNameResolver` → `FeignClientMicrometerEnabledCondition`/`CachingCapability`/`OAuth2AccessTokenInterceptor`/`encoding/` 双拦截器。
feign 内核（第二章，`D:\code\3rd\feign\core\src\main\java\feign\`）：`Feign`/`BaseBuilder` → `ReflectiveFeign` → `SynchronousMethodHandler` → `ResponseHandler`/`InvocationContext` → `Retryer`/`DefaultRetryer` → `DefaultErrorDecoder` → `Capability` → `Request$Options`。

## 9.4 常见坑清单（按踩中概率排序）

1. **默认不重试**：NEVER_RETRY 是子上下文默认 Retryer；配 `retryer` 类属性或塞 Bean 才有重试（2.3）。
2. **配置写了不生效**：先查覆盖顺序——默认 properties 赢配置类；客户端级 `config.<contextId>` 的 key 是 contextId 不是 name（3.4.3/3.6）。
3. **熔断开了但名字对不上**：默认 alphanumeric-ids 清洗掉 `#()` 等字符，Resilience4J 按名配置须用清洗后的名字（6.4）。
4. **fallback 没生效**：确认 `circuitbreaker.enabled=true`、fallback 类是 Spring Bean、方法签名与接口方法完全一致；fallback 里抛的异常会覆盖原始异常（6.3）。
5. **启动报 No Feign Client for loadBalancing**：只引 starter 没引 loadbalancer，且客户端是 name-only（3.4.1）。
6. **RequestParam.value() was empty**：没写 value 且编译没开 `-parameters`（4.2）。
7. **类级 @RequestMapping 报 IllegalArgumentException**：改用 `@FeignClient(path=...)`（4.1）。
8. **加了 spring-retry 后行为突变**：LB 客户端自动切换 Retryable 版（5.3）。
9. **url 配在配置中心不生效**：refresh-enabled 默认 false；PropertyBasedTarget 只"首次调用解析"不是刷新（3.7）。
10. **chunked 请求不压缩**：Content-Encoding 声明依赖恰好一个 Content-Length 头（7.4）。

# 结语

Spring Cloud OpenFeign 的源码读完，印象最深的是它的"三层自律"：**对 feign 内核，它只用装饰与替换，不改一行**（Client 装饰器、Contract 翻译器、InvocationHandlerFactory 替换、Capability 挂载）；**对 Spring 生态，它不发明新货币**（HttpMessageConverters、ConversionService、CacheInterceptor、OAuth2AuthorizedClientManager、refresh 作用域，全是既有组件的接线）；**对用户，它把复杂度放进可覆盖的默认值里**（四层配置覆盖 + 四级条件装配 + 17 个 Capability 钩子）。这套"薄集成层"的写法，与《Spring Cloud LoadBalancer.md》的装饰器体系、《Spring Cloud CircuitBreaker.md》的函数式装饰器一脉相承——读通本篇后，回头看那两篇的抽象层会有"原来接口是这么被用起来的"的闭合感。它虽已 feature-complete，但其设计范式（NamedContextFactory 子容器、ImportBeanDefinitionRegistrar 扫描、FactoryBean 组装、Capability 组合器）仍会在 HTTP Service Clients 与后续框架里长期复用——**学它的实现，比留在它上面更重要**。




