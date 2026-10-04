# Spring Cloud LoadBalancer 深度源码解析（写给初学者的架构全景）

> **本文基于的源码**：`D:\code\3rd\spring-cloud-commons`，模块 `spring-cloud-loadbalancer`，版本 **5.1.0-SNAPSHOT**（main 分支，Git commit `589f31d`）。文中所有【源码证据】的文件路径与行号均为对该快照实际读取所得；消费方（调用端）证据另取自本地 `D:\code\3rd\spring-cloud-gateway`（5.1.x 快照，commit `752fd490a`）的 `ReactiveLoadBalancerClientFilter`。
>
> **版本取舍说明**：Spring Cloud LoadBalancer 的架构骨架自 **2.2.0（Hoxton，2019-11-26 GA）**确立以来高度稳定——`ReactiveLoadBalancer.choose()`、`ServiceInstanceListSupplier` 装饰器链、`NamedContextFactory` 每服务一容器这三件套，在 3.x / 4.x / 5.x 之间的骨架几乎一致。差异集中在两类：**新增装饰器**（加权、子集、API 版本路由等，见 1.6.2 对照表）与**接入点的现代化**（RestTemplate → RestClient/HttpServiceClient）。因此本文内容对使用 Spring Boot 3.x（2022.0 / 2023.0 / 2024.0 列车）的读者同样适用，特性差异按 1.6 节对号入座。
>
> **阅读约定**：每章"先白话、后源码"——先用一两句人话讲清"这是什么、为什么需要它"，再给出源码证据链（`模块/src/main/java/.../类名.java` + 方法名 + 行号 + 代码片段）。行号只对该 5.1.0-SNAPSHOT 快照精确，读者用 IDEA 打开源码按类名 + 方法名定位即可。

## 如何读这份文档

如果你是 Spring Cloud 初学者，推荐两遍读法：

- **第一遍（建立地图，1~2 小时）**：只读第一章（总览）每一节的开头白话段、各章的"小结"节、以及第九章（贯通视图）。目标是能回答：一次 `lb://` 请求如何被翻译成一台具体实例？实例列表从哪来、被谁层层加工？为什么每个服务名对应一个独立的子容器？
- **第二遍（深入源码）**：对照每一章的【源码证据】，打开源码逐行读。顺序建议：第四章（实例来源与装饰器链，全框架的脊柱）→ 第二章（接口体系与"命名迷宫"）→ 第六章（三种接入点）→ 第五章（子容器隔离）→ 第三章（算法，很短）→ 第七章（可观测）→ 第八章（配置实战，随用随查）。

---

# 一、总览：Spring Cloud LoadBalancer 的定位、设计哲学与整体架构

## 1.1 一句话定位

**Spring Cloud LoadBalancer 是一个进程内的客户端负载均衡框架：它把调用方 URL 里的"服务名"（如 `http://user-service/api`）翻译成一台具体的服务实例地址（`http://10.0.3.17:8080/api`），翻译所依据的实例列表来自服务注册中心，翻译所用算法默认是轮询，且每一步都可替换。**

它不启动服务器、不接管流量，只是一个"选地址"的库，被 WebFlux `WebClient`、RestTemplate/RestClient、Spring Cloud Gateway、OpenFeign 等消费方在**发起调用的那一刻**调用。官方文档将其归入 spring-cloud-commons 的客户端负载均衡章节（`docs/modules/ROOT/pages/spring-cloud-commons/loadbalancer.adoc`，本地可查）。

它存在的历史理由只有一个：**替代 Netflix Ribbon**。Ribbon 已进入维护模式并从 Spring Cloud 2020.0 列车移除，Spring Cloud LoadBalancer 从 Hoxton 开始铺路、从 2020.0 正式接班（演进细节见 1.6.3）。

## 1.2 设计哲学：读源码前先记住四句话

1. **"选哪台"与"有哪些可选"完全分离**。负载均衡算法（`RoundRobinLoadBalancer` 等）只对"一个实例列表"做选择，完全不关心列表从哪来——从注册中心实时拉？从本地缓存读？先过滤同机房？先做健康检查？这些都由另一条独立抽象 `ServiceInstanceListSupplier` 负责。两层各自扩展、互不干扰（见第三、四章）。
2. **装饰器链而非继承树**。所有"加工实例列表"的能力（缓存、健康检查、同机房优先、加权、粘滞会话、按 hint 过滤……）都是 `DelegatingServiceInstanceListSupplier` 的子类——每个装饰器只包一层委托，用 `ServiceInstanceListSupplierBuilder` 自由组装（见 4.9）。Spring Cloud 官方自己实现的能力与第三方（如 Nacos 的 `NacosLoadBalancerClientConfiguration`）接的是同一个口子。
3. **一个服务名一个子容器**。`LoadBalancerClientFactory` 继承 `NamedContextFactory`（源自 Feign/Ribbon 时代的 `SpringClientFactory`），为每个 serviceId 创建一个独立的小 `ApplicationContext`：算法 bean、装饰器 bean、配置类都按服务名隔离——`user-service` 用加权，`order-service` 用默认轮询，互不影响（见第五章）。
4. **响应式内核，阻塞外壳**。核心接口的返回值是 Reactor 的 `Mono`/`Flux`（`Mono<Response<ServiceInstance>>` / `Flux<List<ServiceInstance>>`），但模块并不强制引入 WebFlux——阻塞世界通过 `BlockingLoadBalancerClient` 的 `block()` 桥接，且桥接层同时适配 RestTemplate、RestClient、WebClient、Gateway 四种调用姿势（见第六章）。

## 1.3 模块分层全景

Spring Cloud LoadBalancer 的代码分布在 **两个仓库、三个模块** 里（本仓库 spring-cloud-commons 占两个，外加消费它的项目）：

```
┌────────────────────── 消费方（各自的仓库） ──────────────────────┐
│  spring-cloud-gateway：ReactiveLoadBalancerClientFilter（lb://）  │
│  spring-cloud-openfeign：Feign 的 LoadBalancerClient 实现         │
│  spring-cloud-netflix 等其他集成                                   │
├────────────── 实现层：spring-cloud-loadbalancer 模块 ─────────────┤
│  annotation/  @LoadBalancerClient(s)、每服务默认配置类             │
│  core/        算法（RoundRobin/Random）+ ServiceInstanceListSupplier │
│               装饰器全家福（缓存/健康检查/区域/加权/hint/子集…）    │
│  blocking/    BlockingLoadBalancerClient + 粘滞 Cookie/X-Forwarded │
│  cache/       DefaultLoadBalancerCache（内置驱逐）+ Caffeine 适配  │
│  config/      4 个自动装配（LB/缓存/阻塞客户端/统计）              │
│  support/     LoadBalancerClientFactory（每服务一容器）+ AOT/预热  │
│  stats/       Micrometer 指标   aot/ security/（残留壳）           │
├────────────── 抽象层：spring-cloud-commons 模块 ──────────────────┤
│  client/loadbalancer/             # 阻塞侧抽象与拦截器             │
│    LoadBalancerClient、@LoadBalanced、LoadBalancerInterceptor、    │
│    RetryLoadBalancerInterceptor、LoadBalancerProperties、         │
│    Request/Response/RequestData/RequestDataContext 上下文家族、    │
│    LoadBalancerUriTools（URI 重建）、LoadBalancerLifecycle 回调    │
│  client/loadbalancer/reactive/    # 响应式侧抽象                   │
│    ReactiveLoadBalancer、ReactorLoadBalancerExchangeFilterFunction │
│    RetryableLoadBalancerExchangeFilterFunction、WebClientCustomizer│
├────────────── 基建层：spring-cloud-context 模块 ──────────────────┤
│  context/named/NamedContextFactory   # 每服务一子容器（Feign 遗产） │
└───────────────────────────────────────────────────────────────────┘
```

一句话记住分工：**commons 定接口，loadbalancer 给实现，NamedContextFactory 管隔离，消费方各自插线**。

## 1.4 模块依赖图（以各模块 pom.xml 实证）

`spring-cloud-loadbalancer/pom.xml` 的依赖声明（已逐项核对）：

| 依赖 | 强/可选 | 用途 |
|---|---|---|
| spring-cloud-commons | 必需 | 本章 1.3 的"抽象层"全部内容 |
| spring-cloud-context | 必需 | `NamedContextFactory` 子容器基建 |
| reactor-core | 必需 | Mono/Flux 响应式内核 |
| spring-boot-autoconfigure | 必需 | 4 个自动装配 |
| `com.stoyanr.evictor:evictor` | **必带**（starter 直带） | `DefaultLoadBalancerCache` 的底层（带定时驱逐的 ConcurrentHashMap） |
| caffeine | 可选 | `CaffeineBasedLoadBalancerCacheManager`（有则优先用） |
| micrometer-core | 可选 | `MicrometerStatsLoadBalancerLifecycle` 指标 |
| spring-retry | 可选 | 阻塞重试链 `RetryLoadBalancerInterceptor` |
| spring-web / spring-webflux / spring-webmvc | 可选 | RestTemplate/RestClient/WebClient 接入 |
| spring-security-oauth2-autoconfigure 等 | 可选 | security 包（见 7.4：残留壳） |

`spring-cloud-starter-loadbalancer/pom.xml` 则打包：`spring-cloud-starter` + `spring-cloud-loadbalancer` + `spring-boot-starter-cache` + `evictor`。**注意 starter 一定带 evictor**——这是"默认缓存开箱即用"的前提；而 caffeine 不在 starter 里，需要自己加依赖。

这张表本身就是一份架构说明：**核心只依赖 Reactor**（不依赖任何 Web 框架、不依赖任何注册中心实现）；注册中心通过 commons 的 `DiscoveryClient`/`ReactiveDiscoveryClient` 接口反向接入（谁在 classpath 上谁生效）；缓存有"内置 + Caffeine"双实现自动降级。

## 1.5 关键问题 → 方案映射（全文导览）

| 微服务调用的关键问题 | Spring Cloud LoadBalancer 的方案 | 详见 |
|---|---|---|
| 拿着服务名不知道调哪台机器 | `ReactiveLoadBalancer.choose()` 返回 `Response<ServiceInstance>`；URI 由 `LoadBalancerUriTools.reconstructURI` 重建 | 第二、三、六章 |
| 每次调用都查注册中心，性能与可用性差 | `CachingServiceInstanceListSupplier` + `DefaultLoadBalancerCache`（35s TTL）装饰器 | 4.3 |
| 注册中心里的实例可能已宕机 | `HealthCheckServiceInstanceListSupplier` 主动探测剔除（可配） | 4.4 |
| 跨机房调用延迟高 | `ZonePreferenceServiceInstanceListSupplier` 同区域优先（匹配 metadata `zone`） | 4.5 |
| 实例性能不均，想按权重分流 | `WeightedServiceInstanceListSupplier`（读 metadata `weight`，惰性展开列表） | 4.6 |
| 会话需要粘在同一实例 | Cookie 粘滞（`RequestBasedStickySession`）、进程内粘滞（`SameInstancePreference`）、灰度粘滞（`HintBased`） | 4.7 |
| 大集群下全量列表拉取/广播开销大 | `SubsetServiceInstanceListSupplier` 一致性分桶，只看子集 | 4.8 |
| 重试时避免再次打到刚失败的实例 | `RetryAwareServiceInstanceListSupplier` + `RetryableRequestContext` | 4.8、6.3 |
| 不同服务要不同的算法/装饰器/超时 | `NamedContextFactory` 每服务一个子容器 + `@LoadBalancerClient(s)` 按名注入配置 | 第五章 |
| 想观测"选了谁、成没成功、耗时多少" | `LoadBalancerLifecycle` 回调 + `MicrometerStatsLoadBalancerLifecycle` 指标 | 第七章 |
| 全局配置与单服务配置打架 | `LoadBalancerClientsProperties` 的 per-service overlay + `LoadBalancerEnvironmentPropertyUtils` | 8.2 |

## 1.6 版本演进：2.1 → 5.1（git 标签逐项实证）

写作时（2026 年 10 月）的版本格局：2.x/3.x/4.x 已陆续结束 OSS 维护，5.0.x（2025.0 Northfields 列车）在维护线内（最新 v5.0.3 发布于 2026-08-03），5.1.0 处于 M1/快照阶段（v5.1.0-M1 于 2026-09-18 发布）——正是本文分析的这份 main 分支快照。本节所有"某特性属于哪个版本"的结论均经本地 git 标签逐项实证。

### 1.6.1 版本时间线与运行基线

GA 时间取自各发布 tag 的 git 提交日期（`git log -1 --format=%ad <tag>` 可复现）；列车代号与 Boot 对应为社区公知信息，非 tag 实证。

| 版本 | 发布列车 | GA（tag 提交日） | 一句话主题 |
|---|---|---|---|
| 2.1.0.RELEASE | Greenwich | 2019-01-22 | 从孵化仓并入 spring-cloud-commons 仓库：`ServiceInstanceSupplier`（旧接口）+ 轮询算法 + 子容器工厂 |
| 2.2.0.RELEASE | Hoxton | 2019-11-26 | **奠基版**：`ServiceInstanceListSupplier` 新接口 + 缓存装饰器 + `BlockingLoadBalancerClient` + 缓存自动装配（Ribbon 替代工程铺路） |
| 3.0.0 | 2020.0 (Ilford) | 2020-12-21 | **正式接班 Ribbon**：装饰器 `Builder`、健康检查、请求粘滞、重试感知、Micrometer 统计、`LoadBalancerLifecycle` 回调、响应式重试过滤器 |
| 3.1.0 | 2021.0 (Jubilee) | 2021-12-01 | 粘滞 Cookie 回写（`LoadBalancerServiceInstanceCookieTransformer`） |
| 4.0.0 | 2022.0 (Kilburn) | 2022-12-15 | **Boot 3 / jakarta 基线**：加权负载、X-Forwarded 头、子上下文 AOT（`LoadBalancerChildContextInitializer`）、实例列表预热 |
| 4.1.0 | 2023.0 (Leyton) | 2023-12-06 | Subset 子集路由（一致性分桶） |
| 4.2.0 | 2024.0 (Moorgate) | 2024-12-03 | 拦截器延迟接线：`DeferringLoadBalancerInterceptor` + RestTemplate/RestClient Builder 后置处理器 |
| 5.0.0 | 2025.0 (Northfields) | 2025-11-24 | **Boot 4 / Framework 7 世代**：API 版本路由（`ApiVersion`）、HttpServiceClient 服务组集成、JSpecify 空安全全面启用 |
| 5.1.0-SNAPSHOT（本文基线） | 2025.1 | 预计 2026 下半年 | 迭代增强（v5.1.0-M1：2026-09-18） |

基线的源码证据（对照 tag 实证）：

| 结论 | 证据 |
|---|---|
| 本文基线是 5.1.0-SNAPSHOT | 根 `pom.xml:8`：`<version>5.1.0-SNAPSHOT</version>` |
| 5.0 起进入 Boot 4 包结构世代 | v4.2.0 `LoadBalancerCacheAutoConfiguration.java:35`：`import org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration`（Boot 3 包）；5.0.0 起同位置变为 `org.springframework.boot.cache.autoconfigure.CacheAutoConfiguration`（Boot 4 重排后的包） |
| 5.0 接入 Framework 7 / Boot 4 的 HTTP 服务组 | `LoadBalancerRestClientHttpServiceGroupConfigurer.java:25`：`import org.springframework.boot.http.client.autoconfigure.service.HttpServiceClientProperties`（v5.0.0 已存在） |
| 空安全注解换为 JSpecify | 全模块统一 `org.jspecify.annotations.Nullable`（如 `spring-cloud-context/.../NamedContextFactory.java:30`、`client/loadbalancer/reactive/ReactiveLoadBalancer.java:21`），4.2 时代为 Spring 自有 `@Nullable` |

### 1.6.2 特性引入版本对照表（git 实证，可复现）

每行都可用如下命令复现（`N` 为该 tag 下命中文件数，含 main+test）：

```bash
git ls-tree -r <tag> --name-only | grep "<类名>.java"
```

| 特性（类） | 引入版本 | 实证（旧 tag → 新 tag 命中数） | 详见 |
|---|---|---|---|
| `ReactorLoadBalancer` / `RoundRobinLoadBalancer` / `LoadBalancerClientFactory` / `@LoadBalancerClient` | 2.1.0.RELEASE | v2.1.0.RELEASE 已存在（模块并入本仓的起点，当时 14 个主源码文件） | 二、三、五章 |
| `ServiceInstanceListSupplier`（新接口）/ `DiscoveryClientServiceInstanceListSupplier` / `CachingServiceInstanceListSupplier` / `BlockingLoadBalancerClient` / `DefaultLoadBalancerCache` / `LoadBalancerUriTools` | 2.2.0.RELEASE | v2.2.0.RELEASE=1（此前 0） | 四、六 |
| `ZonePreferenceServiceInstanceListSupplier`（@since 2.2.1）、`RandomLoadBalancer`（@since 2.2.7） | 2.2.x 补丁 | 类头 @since 标签实证；tag 粒度：v2.2.0=0 → v3.0.0=1 | 3.3、4.5 |
| `HealthCheckServiceInstanceListSupplier`（类头 @since 2.2.0，但 v2.2.0.RELEASE tag 中无此文件，应在 2.2.x 补丁期落地） | 2.2.x 补丁 | v2.2.0.RELEASE=0 → v3.0.0=1 | 4.4 |
| 旧接口 `ServiceInstanceSupplier` **移除** | 3.0.0 | v2.2.0.RELEASE 存在（与新旧接口并存过渡）→ v3.0.0 消失 | — |
| `ServiceInstanceListSupplierBuilder`（装饰器链 Builder）/ `HealthCheckServiceInstanceListSupplier`（tag 可见）/ `RequestBasedStickySessionServiceInstanceListSupplier` / `SameInstancePreferenceServiceInstanceListSupplier` / `RetryAwareServiceInstanceListSupplier` / `MicrometerStatsLoadBalancerLifecycle` / `LoadBalancerLifecycle`（commons）/ `RequestData` / `RequestDataContext` / `RetryableLoadBalancerExchangeFilterFunction` | 3.0.0 | v2.2.0.RELEASE=0 → v3.0.0≥1 | 四、六、七 |
| `HintBasedServiceInstanceListSupplier`（@since 3.0.2） | 3.0.2 补丁 | 类头 @since 实证；tag 粒度：v3.0.0=0 → v3.1.0=1 | 4.7.3 |
| `LoadBalancerServiceInstanceCookieTransformer`（向下游回写粘滞 Cookie） | 3.1.0 | v3.0.0=0 → v3.1.0=1 | 4.7.1 |
| `WeightedServiceInstanceListSupplier` + `LazyWeightedServiceInstanceList`（加权）/ `XForwardedHeadersTransformer`（core+blocking 两份）/ `LoadBalancerChildContextInitializer`（子上下文 AOT）/ `LoadBalancerEagerContextInitializer` + `LoadBalancerEagerLoadProperties`（预热） | 4.0.0 | v3.1.0=0 → v4.0.0=1 | 4.6、5.3、6.8 |
| `SubsetServiceInstanceListSupplier`（子集路由） | 4.1.0 | v4.0.0=0 → v4.1.0=1 | 4.8.1 |
| `DeferringLoadBalancerInterceptor` / `LoadBalancerRestTemplateBuilderBeanPostProcessor`（阻塞接入延迟接线） | 4.2.0 | v4.1.0=0 → v4.2.0=1 | 6.2 |
| `Blocking/ReactiveApiVersionServiceInstanceListSupplier` + `ApiVersionStrategy`（API 版本路由）/ `LoadBalancerRestClientHttpServiceGroupConfigurer`（HttpServiceClient）/ JSpecify | 5.0.0 | v4.2.0=0 → v5.0.0=1 | 4.8.3 |

三个容易搞错的点，特别提醒：

- **"LoadBalancer 是 Hoxton 才有的"只对一半**：模块 2.1.0（Greenwich，2019-01）就已并入 spring-cloud-commons 仓库并提供了轮询算法与子容器工厂，只是彼时还叫 `ServiceInstanceSupplier`、没有装饰器链，也没人拿它接班 Ribbon——真正可用是 2.2.0，正式接班是 2020.0。
- **`HealthCheckServiceInstanceListSupplier` 的类头 `@since 2.2.0` 与 v2.2.0.RELEASE tag 矛盾**：tag 里确实没有这个文件。这类"@since 早于 tag"说明特性在 2.2.x 补丁版本中落地，类头沿用计划版本号。同理 `RandomLoadBalancer`（@since 2.2.7）、`HintBasedServiceInstanceListSupplier`（@since 3.0.2）。
- **`LoadBalancerUriTools` 不是 3.0 特性**，2.2.0 就有了（v2.2.0.RELEASE tag 命中）——它是 Hoxton 时代从 Gateway 的 `ServerWebExchangeUtils` 抽出来的公共实现（源码注释 `LoadBalancerUriTools.java:55-57` 保留了出处链接）。

### 1.6.3 与 Netflix Ribbon 的交接班

**（本小节为历史背景综述，基于社区公知过程，随后的源码结构对比可交叉验证 Spring Cloud 团队确实按此方向演进。）**

- Ribbon 是 Netflix OSS 一代的核心组件：客户端负载均衡器，Spring Cloud Netflix 把它接进 `@LoadBalanced RestTemplate` 与 Feign。2018 年起 Netflix 宣布 Ribbon 进入维护模式，Spring Cloud Netflix 在 2.2（Hoxton）把 Ribbon 标记为维护态，并在 3.0（2020.0 列车）将 `spring-cloud-starter-netflix-ribbon` 移除。
- 接班不是"发布当天切换"，而是一个版本一个版本铺路（本章 1.6.2 的 tag 实证正好还原了这条时间线）：
  1. **2.1（Greenwich）**：把 LoadBalancer 代码并入 commons 仓库，先解决"有没有"；
  2. **2.2（Hoxton）**：补齐 `ServiceInstanceListSupplier` 新抽象、缓存、`BlockingLoadBalancerClient`，让 Ribbon 与 LoadBalancer 可共存、可切换（此时默认仍是 Ribbon）；
  3. **2020.0（Ilford）**：Ribbon 退场，LoadBalancer 成为所有消费方（Gateway/OpenFeign/WebClient/RestTemplate）的默认实现。
- 源码里的遗产证据：`NamedContextFactory` 的 Javadoc 直言 *"Ported from spring-cloud-netflix FeignClientFactory and SpringClientFactory"*（`spring-cloud-context/.../NamedContextFactory.java:54`）；`RoundRobinLoadBalancer` 的 `choose` 注释引用了 Netflix 的 Ocelli 项目实现出处（`RoundRobinLoadBalancer.java:79-81`）。**新框架站在旧巨头的肩膀上，但接口全部重新设计**——这是 Spring Cloud 生态"替换而不兼容"的经典案例。

### 1.6.4 对初学者的意义：哪些知识过时了，哪些永远有效

- **新项目避免使用**：Ribbon 与一切 `spring-cloud-starter-netflix-ribbon`、2.2 时代的旧接口 `ServiceInstanceSupplier`（3.0 已移除）、`spring-cloud-starter-loadbalancer` 之外的旧缓存方案（Ribbon 的 `ServerListRefreshInterval` 心智模型不适用，这里用 35 秒 TTL 缓存）。
- **老教程里永久有效的部分**：`ReactiveLoadBalancer.choose()` 两层接口、`ServiceInstanceListSupplier` 装饰器链、`NamedContextFactory` 子容器、`@LoadBalanced` 标注 + 拦截器/过滤器接入——这些从 2.2 到 5.1 骨架未变，本文第二~六章正是按这条主线写的。
- **版本与 Boot 的对应关系**（选教材时对号入座）：Boot 2.2/2.3 ↔ 2.2.x；Boot 2.4/2.5 ↔ 3.0.x；Boot 2.6/2.7 ↔ 3.1.x；Boot 3.0/3.1 ↔ 4.0.x；Boot 3.2/3.3 ↔ 4.1.x；Boot 3.4/3.5 ↔ 4.2.x；Boot 4.0 ↔ 5.0.x。从 Boot 3.x 入手的读者，实际用的就是 4.x 一代的内核。

## 1.7 全文章节地图

- **第二章 核心抽象（commons 层）**：`Request`/`Response`/`ReactiveLoadBalancer` 三件套 + 上下文家族（RequestData/RequestDataContext/…），并给出一份"命名迷宫"速查表——这个全家桶里十几个以 LoadBalancer 开头的类各管什么。
- **第三章 算法层（core 包上半场）**：`RoundRobinLoadBalancer` 逐行解析（含轮询位置的原子推进与边界处理）、`RandomLoadBalancer`、如何换算法。
- **第四章 实例来源层（core 包下半场，全框架的脊柱）**：`ServiceInstanceListSupplier` 装饰器体系——从注册中心发现、缓存、健康检查、同区域、加权、粘滞会话、子集到 Builder 组装与默认链装配。
- **第五章 隔离层**：`LoadBalancerClientFactory` 与 `NamedContextFactory` 的每服务一容器机制，`@LoadBalancerClient(s)` 注解如何把配置类注入对应子容器，预热与 AOT。
- **第六章 接入层**：RestTemplate/RestClient（阻塞）、WebClient（响应式）、Gateway（`lb://`）三种消费姿势的完整链路，重试链与 URI 重建。
- **第七章 生命周期与可观测**：`LoadBalancerLifecycle` 回调、Micrometer 指标、缓存自动装配的降级选择。
- **第八章 配置大全与定制实战**：`LoadBalancerProperties` 全字段速查、全局/单服务配置覆盖规则、四种定制姿势与常见坑。
- **第九章 贯通视图**：一次 `lb://` 请求的三条时间线叠加成一张全景图，外加与 Ribbon 的能力对照表。
- **第十章 附录**：关键接口速查表、初学者学习路线、源码阅读入口清单（30 个关键文件）。

---

# 二、核心抽象：commons 层的 Request/Response/ReactiveLoadBalancer 三件套

> 本章对应源码：`spring-cloud-commons` 模块（`D:\code\3rd\spring-cloud-commons\spring-cloud-commons`，源码位于 `src/main/java`），包 `org.springframework.cloud.client.loadbalancer` 及其 `reactive` 子包。这一层全是**接口与数据结构**，没有任何算法实现——它是整个生态的"合同"。

## 2.1 模块定位：为什么抽象要放在 commons 而不是 loadbalancer

白话版：commons 模块是 Spring Cloud 全家桶的"公共语言层"。Gateway、OpenFeign、Zookeeper/Consul/Eureka 集成……这些项目都依赖 commons 但**不该依赖具体负载均衡实现**。所以负载均衡的"接口合同"（`ReactiveLoadBalancer`、`LoadBalancerClient`、`Request`/`Response`）放在 commons，"默认实现"（`spring-cloud-loadbalancer` 模块）由使用方自行引入——Ribbon 退出后，这份合同让"换实现"变成"换 starter"，消费方代码零改动。

## 2.2 请求上下文家族：一次"选择"携带的全部信息

**白话**：负载均衡器选实例时，可能需要知道"这是个什么请求"——是 GET 还是 POST？要调哪个 path？有没有灰度 hint？是不是重试（上次失败的是哪台）？这些信息被打包成一个 `Request<C>` 对象，其中 `C` 是上下文。上下文类的继承链是理解全框架的钥匙，先看全景：

```
Request<C>（接口：getContext()）                        Request.java:27
└── DefaultRequest<T>（最简实现：只包一个 context）      DefaultRequest.java:29

上下文（C）继承链——从"什么都没有"逐层叠加：
TimedRequestContext（接口：打点请求开始时间，供统计）
└── HintRequestContext（类：携带 hint 灰度标记）                      HintRequestContext.java:28
    └── DefaultRequestContext（类：委托对象 + hint）                  DefaultRequestContext.java:30
        └── RequestDataContext（类：+ 完整 HTTP 请求数据）            RequestDataContext.java:29
            └── RetryableRequestContext（类：+ 上次失败的实例）       RetryableRequestContext.java:32
```

【源码证据】`spring-cloud-commons/src/main/java/org/springframework/cloud/client/loadbalancer/RequestData.java` 第 45-55 行——HTTP 请求的"数据快照"，方法/URL/头/Cookie/属性五件套：

```java
public class RequestData {

	private final HttpMethod httpMethod;                          // L47

	private final URI url;                                        // L49

	private final @Nullable HttpHeaders headers;                  // L51

	private final MultiValueMap<String, String> cookies;          // L53

	private final Map<String, Object> attributes;                 // L55
```

为什么要做"快照"而不是直接传 `HttpRequest`？因为消费方横跨阻塞（RestTemplate/RestClient）与响应式（WebClient/ServerWebExchange）两套类型体系，`RequestData` 用最小公约数把它们归一——`RequestDataContext.java:35-41` 提供从 `RequestData` 构造的重载，Gateway 的 `RequestData(exchange.getRequest(), exchange.getAttributes())` 也是先归一再传递。

【源码证据】`spring-cloud-commons/src/main/java/org/springframework/cloud/client/loadbalancer/RetryableRequestContext.java` 第 32-42 行——重试上下文只多一个字段："上次是哪台失败了"：

```java
public class RetryableRequestContext extends RequestDataContext {

	private @Nullable ServiceInstance previousServiceInstance;    // L34
```

这个字段是第六章重试链与 `RetryAwareServiceInstanceListSupplier`（4.8.2）之间的"暗号"：重试时带上上次失败的实例，装饰器把它从候选列表里剔除。

## 2.3 响应家族：选完之后交回什么

**白话**：选择的结果是 `Response<T>`。要么有货（`DefaultResponse` 包着一台实例），要么没货（`EmptyResponse`）——没有货时调用方会收到 503 或抛 `IllegalStateException`，而不是 NPE。

【源码证据】`spring-cloud-commons/src/main/java/org/springframework/cloud/client/loadbalancer/Response.java` 第 27-31 行：

```java
public interface Response<T> {

	boolean hasServer();                                          // L29

	default @Nullable T getServer() {                             // L31
```

实现只有两个：`DefaultResponse.java:30`（包一台 `ServiceInstance`）与 `EmptyResponse.java:26`（空壳）。另有 `ResponseData`（带 HTTP 响应的增强版，供生命周期回调统计状态码用）。

生命周期事件 `CompletionContext` 与 `Response` 是两个东西：后者只描述"选出来的实例"，前者描述"这次调用最终如何收场"。

【源码证据】`spring-cloud-commons/src/main/java/org/springframework/cloud/client/loadbalancer/CompletionContext.java` 第 30、102-109 行：

```java
public class CompletionContext<RES, T, C> {                    // L30
```

```java
	public enum Status {                                          // L102

		SUCCESS,                                                  // L105

		FAILED,                                                   // L107

		DISCARD,                                                  // L109
```

`DISCARD` 的语义是"选出了实例但没真正发请求"（如没有可用实例、或重试前放弃），第七章的指标统计会区分三者。

## 2.4 ReactiveLoadBalancer：核心 SPI 与工厂

【源码证据】`spring-cloud-commons/src/main/java/org/springframework/cloud/client/loadbalancer/reactive/ReactiveLoadBalancer.java` 第 37-86 行：

```java
public interface ReactiveLoadBalancer<T> {

	Request<DefaultRequestContext> REQUEST = new DefaultRequest<>();   // L42：无上下文时的默认请求
	...
	Publisher<Response<T>> choose(Request request);                    // L50

	default Publisher<Response<T>> choose() {                          // L52
		return choose(REQUEST);
	}

	interface Factory<T> {                                             // L56

		default @Nullable LoadBalancerProperties getProperties(String serviceId) {  // L58
			return null;
		}

		ReactiveLoadBalancer<T> getInstance(String serviceId);         // L62

		<X> Map<String, X> getInstances(String name, Class<X> type);   // L72

		<X> X getInstance(String name, Class<?> clazz, Class<?>... generics);  // L84
	}
}
```

三个看点：

1. **返回类型是 `Publisher` 而非 `Mono`**——核心接口不绑死 Reactor；loadbalancer 模块里的 `ReactorLoadBalancer`（下章）才把它收窄成 `Mono`。
2. **`Factory` 按 serviceId 取负载均衡器与属性**——这就是"每服务一容器"（第五章）对消费方暴露的门面；`getProperties` 返回的也是该服务自己的 `LoadBalancerProperties`（per-service 配置覆盖，见 8.2）。
3. `ReactiveLoadBalancer<T>` 的泛型 T 决定"选什么"。日常负载均衡选的是 `ServiceInstance`，所以 loadbalancer 模块有个标记接口 `ReactorServiceInstanceLoadBalancer`（`core/ReactorServiceInstanceLoadBalancer.java:28`，`@since 2.2.0`）= `ReactorLoadBalancer<ServiceInstance>`；`ReactorLoadBalancer.java:31-45` 再把 `Publisher` 收窄为 `Mono<Response<T>>`：

```java
public interface ReactorLoadBalancer<T> extends ReactiveLoadBalancer<T> {

	Mono<Response<T>> choose(Request request);                     // L39

	default Mono<Response<T>> choose() {
		return choose(REQUEST);                                    // L42
	}
}
```

## 2.5 "命名迷宫"速查表：十几个 LoadBalancer 前缀的类各管什么

初学者在源码里最先迷路的地方：类名都以 LoadBalancer 开头，却分属完全不同的角色。按"角色 × 所在模块"对齐后并不复杂：

| 类/注解 | 模块 | 角色（一句话） |
|---|---|---|
| `@LoadBalanced` | commons | 标记注解：打在 RestTemplate/WebClient/RestClient.Builder bean 上，"这个客户端要被负载均衡接管" |
| `LoadBalancerClient` | commons | 阻塞侧 SPI：`execute`/`choose`/`reconstructURI`（`LoadBalancerClient.java:29-67`） |
| `BlockingLoadBalancerClient` | loadbalancer | 上一行的默认实现，内部 `Mono.from(...).block()` 桥到响应式（`BlockingLoadBalancerClient.java:59`） |
| `ReactiveLoadBalancer` / `ReactorLoadBalancer` / `ReactorServiceInstanceLoadBalancer` | commons / loadbalancer | 核心选择 SPI 三级跳（见 2.4） |
| `RoundRobinLoadBalancer` / `RandomLoadBalancer` | loadbalancer | 算法实现（第三章） |
| `LoadBalancerClientFactory` | loadbalancer | 每服务一子容器的工厂（第五章） |
| `LoadBalancerClientConfiguration` | loadbalancer | 子容器的**默认配置类**（默认算法 + 默认装饰器链，第五章） |
| `@LoadBalancerClient` / `@LoadBalancerClients` / `LoadBalancerClientSpecification` / `LoadBalancerClientConfigurationRegistrar` | loadbalancer | 注解四件套：把用户配置类按服务名塞进对应子容器（第五章） |
| `LoadBalancerInterceptor` / `RetryLoadBalancerInterceptor` / `DeferringLoadBalancerInterceptor` / `BlockingLoadBalancerInterceptor` | commons / loadbalancer | RestTemplate/RestClient 的拦截器三兄弟（无重试 / 带重试 / 延迟接线），后者的接口化抽象 4.2 引入（第六章） |
| `ReactorLoadBalancerExchangeFilterFunction` / `RetryableLoadBalancerExchangeFilterFunction` | commons | WebClient 的 ExchangeFilterFunction 两兄弟（无重试 / 带重试）（第六章） |
| `ReactiveLoadBalancerClientFilter` | **gateway 仓库** | Gateway 的 GlobalFilter，处理 `lb://` 路由（第六章） |
| `ServiceInstanceListSupplier`（+ Builder） | loadbalancer | 实例列表来源装饰器链（第四章） |
| `LoadBalancerProperties` / `LoadBalancerClientsProperties` | commons | 配置属性（后者继承前者并加 per-service map，第八章） |
| `LoadBalancerCacheManager` / `DefaultLoadBalancerCache` | loadbalancer | 缓存标记接口 / 内置缓存实现（4.3） |
| `LoadBalancerLifecycle` | commons | 生命周期回调 SPI（第七章） |

记忆口诀：**名字带 Client 的管"发起调用"，名字带 LoadBalancer（无 Client）的管"选实例"，名字带 Interceptor/FilterFunction/Filter 的管"接入某种客户端"，名字带 Supplier 的管"供给实例列表"**。

## 2.6 本章小结

- commons 层定义了负载均衡的最小合同：进 `Request<C>`，出 `Publisher<Response<T>>`，工厂 `Factory` 按服务名取负载均衡器与配置。
- 上下文家族是一条精心设计的继承链：`HintRequestContext` → `DefaultRequestContext` → `RequestDataContext` → `RetryableRequestContext`，每一层只叠加一类信息（hint → 委托对象 → HTTP 数据 → 上次失败实例），装饰器与重试链靠 `instanceof` 逐层取用。
- 命名迷宫的本质是**分层**：SPI 在 commons、默认实现在 loadbalancer、消费方接入件（Interceptor/FilterFunction/Filter）各回各家。拿着 2.5 的表对号入座，源码导航不再迷路。

---

# 三、算法层：RoundRobinLoadBalancer 与 RandomLoadBalancer

> 本章对应源码：`spring-cloud-loadbalancer/src/main/java/org/springframework/cloud/loadbalancer/core/`。算法层是整个框架**最薄**的一层——内置实现合计不到 230 行，这是"算法只认列表"设计哲学的直接体现。

## 3.1 模块定位

白话版：算法层回答一个问题——"给定一个实例列表，返回一台"。它不知道列表怎么来的（第四章的事），不知道谁在调用（第六章的事），甚至不知道实例死活（那是上游装饰器已经过滤过的前提）。

## 3.2 RoundRobinLoadBalancer：60 行的轮询（重点）

默认算法。先用一句话说清结构：**一个 `AtomicInteger` 记住轮到第几号 + 一次取模**，其余全是防御性细节。

【源码证据】`spring-cloud-loadbalancer/src/main/java/org/springframework/cloud/loadbalancer/core/RoundRobinLoadBalancer.java` 第 43-120 行（关键段）：

```java
public class RoundRobinLoadBalancer implements ReactorServiceInstanceLoadBalancer {

	final AtomicInteger position;                                       // L47

	final String serviceId;                                             // L49

	private final SingletonSupplier<ServiceInstanceListSupplier> serviceInstanceListSingletonSupplier;  // L51

	public RoundRobinLoadBalancer(ObjectProvider<ServiceInstanceListSupplier> serviceInstanceListSupplierProvider,
			String serviceId) {
		this(serviceInstanceListSupplierProvider, serviceId, new Random().nextInt(1000));   // L60：随机种子
	}
	...
	@Override
	// see original
	// https://github.com/Netflix/ocelli/blob/master/ocelli-core/        // L79-81：Ocelli 出处
	// src/main/java/netflix/ocelli/loadbalancer/RoundRobinLoadBalancer.java
	public Mono<Response<ServiceInstance>> choose(Request request) {
		ServiceInstanceListSupplier supplier = serviceInstanceListSingletonSupplier.obtain();
		return supplier.get(request)                                    // L84：向来源层要列表
			.next()                                                     // L85：只取第一个发射
			.map(serviceInstances -> processInstanceResponse(supplier, serviceInstances));
	}
```

```java
	private Response<ServiceInstance> getInstanceResponse(List<ServiceInstance> instances) {   // L99
		if (instances.isEmpty()) {
			...
			return new EmptyResponse();                                 // L104：空列表 → 空响应
		}

		// Do not move position when there is only 1 instance, especially some suppliers
		// have already filtered instances                              // L107-108
		if (instances.size() == 1) {
			return new DefaultResponse(instances.get(0));               // L110：单实例不推进游标
		}

		// Ignore the sign bit, this allows pos to loop sequentially from 0 to
		// Integer.MAX_VALUE                                            // L113-114
		int pos = this.position.incrementAndGet() & Integer.MAX_VALUE;  // L115

		ServiceInstance instance = instances.get(pos % instances.size());   // L117：取模定人

		return new DefaultResponse(instance);                           // L119
	}
```

四个容易被面试官问到的细节：

1. **随机种子位置（L60）**：`new Random().nextInt(1000)` 给每个 JVM 的游标一个随机初值。如果所有实例都以 0 起步，应用冷启动瞬间多个客户端会"齐步走"打向同一台实例（列表内容相同、位置相同 → 选择相同），随机初值错开了这个相位。
2. **`& Integer.MAX_VALUE`（L115）**：`AtomicInteger.incrementAndGet()` 会溢出变负，负数取模会出负下标。屏蔽符号位后，游标在 0~2³¹-1 内顺序循环——比"溢出归零"更平滑，代价是 2³¹ 次后才重复一轮。
3. **单实例不推进游标（L109-111）**：注释点明动机——某些装饰器（如健康检查、区域过滤）已经把列表过滤到只剩 1 台时，推进游标会让下一次多实例时的相位失真；1 台时直接返回，零副作用。
4. **懒解析 + Noop 兜底（L72-73）**：`SingletonSupplier` 包住 `ObjectProvider`，真正第一次 `choose` 才去解析 `ServiceInstanceListSupplier` bean；解析不到就退化成 `NoopServiceInstanceListSupplier`（永远返回空列表，`core/NoopServiceInstanceListSupplier.java:32`），而不是启动期就报错——子容器尚未就绪时调用方拿到的是"优雅的 503"。

还有一个对外回调：`processInstanceResponse`（L90-97）里若 supplier 实现了 `SelectedInstanceCallback`（`core/SelectedInstanceCallback.java:28`），会把最终选中的实例回传给装饰器层——4.7.2 的进程内粘滞（`SameInstancePreferenceServiceInstanceListSupplier`）全靠这个钩子知道"上次选了谁"。

## 3.3 RandomLoadBalancer：随机算法

【源码证据】`spring-cloud-loadbalancer/src/main/java/org/springframework/cloud/loadbalancer/core/RandomLoadBalancer.java` 第 39-92 行（关键段）：

```java
 * @since 2.2.7                                                        // L39
public class RandomLoadBalancer implements ReactorServiceInstanceLoadBalancer {   // L41
	...
	private Response<ServiceInstance> getInstanceResponse(List<ServiceInstance> instances) {  // L80
		if (instances.isEmpty()) { ... return new EmptyResponse(); }
		int index = ThreadLocalRandom.current().nextInt(instances.size());   // L87

		ServiceInstance instance = instances.get(index);

		return new DefaultResponse(instance);
	}
```

与轮询唯一的差别：不要游标，`ThreadLocalRandom` 取随机下标。注意它同样实现了"空列表 → EmptyResponse"，但没有轮询版的"单实例短路"（随机数对单实例取模恒为 0，天然正确）。

## 3.4 切换与自定义算法

内置只有轮询与随机两种，但换算法是官方设计好的"第一扩展点"：

```java
public class CustomLoadBalancerConfig {

    @Bean
    ReactorServiceInstanceLoadBalancer reactorServiceInstanceLoadBalancer(
            Environment environment, LoadBalancerClientFactory loadBalancerClientFactory) {
        String name = environment.getProperty(LoadBalancerClientFactory.PROPERTY_NAME);
        return new RandomLoadBalancer(
                loadBalancerClientFactory.getLazyProvider(name, ServiceInstanceListSupplier.class), name);
    }
}

// 应用入口（或任意配置类）：
@LoadBalancerClient(name = "user-service", configuration = CustomLoadBalancerConfig.class)
```

原理先按下不表（第五章展开），只需记住两点：这段配置会被注册到**仅属于 `user-service` 的子容器**，且 `@LoadBalancerClientConfiguration` 里默认算法 bean 带有 `@ConditionalOnMissingBean`（`annotation/LoadBalancerClientConfiguration.java:71-78`）——用户 bean 先注册、默认 bean 让位。生态里的第三方算法（如 Nacos 的 `NacosLoadBalancer`）接的正是同一个口子。

## 3.5 本章小结

- 算法层薄到极致：默认轮询的全部逻辑 = 原子游标 + 取模 + 三个防御分支（空/单实例/符号位）。
- `choose()` 的三段式（`supplier.get(request).next().map(...)`）是贯穿全框架的调用模板：向来源层要列表 → 取第一次发射 → 组装响应并回调。
- 换算法 = 在子容器里放一个 `ReactorServiceInstanceLoadBalancer` bean，默认配置类 `@ConditionalOnMissingBean` 让位——第五章会解释"子容器"从哪来。

---

# 四、实例来源层：ServiceInstanceListSupplier 装饰器体系（全框架的脊柱）

> 本章对应源码：`spring-cloud-loadbalancer/src/main/java/org/springframework/cloud/loadbalancer/core/`（本章是全框架最厚的一章）。所有类都是 2.2.0 起新抽象的成员，版本归属见 1.6.2 对照表。

## 4.1 为什么需要这一层

**白话**：算法层只需要"一个列表"，但现实世界里"拿到这个列表"远比想象复杂：列表从注册中心来（网络调用，可能失败、可能慢）；不能每次调用都去拉（要缓存）；注册中心的实例可能已宕机（要健康检查）；希望优先调本机房（要区域过滤）；实例性能不均（要加权）；灰度发布要把特定流量粘到特定实例（要 hint/粘滞）；大集群不想全量广播（要子集）……如果把这些逻辑都塞进算法类，`RoundRobinLoadBalancer` 会变成一千行的怪物。

Spring Cloud LoadBalancer 的解法：**把"供给实例列表"抽成一个接口，每种加工能力做成一个装饰器，用 Builder 串成链**。

【源码证据】`spring-cloud-loadbalancer/src/main/java/org/springframework/cloud/loadbalancer/core/ServiceInstanceListSupplier.java` 第 33-45 行——接口小到只剩三句话：

```java
public interface ServiceInstanceListSupplier extends Supplier<Flux<List<ServiceInstance>>> {   // L33

	String getServiceId();                                          // L35

	default Flux<List<ServiceInstance>> get(Request request) {      // L37：带请求上下文的取法
		return get();
	}

	static ServiceInstanceListSupplierBuilder builder() {           // L41
		return new ServiceInstanceListSupplierBuilder();
	}
}
```

两个设计决定值得咀嚼：

1. **返回 `Flux<List<ServiceInstance>>` 而不是 `Mono`**：列表可以被多次发射（如健康检查装饰器周期性重新探测后再次推送），算法层用 `.next()` 只取最新一次即可，天然支持"列表的流"。
2. **`get()` 与 `get(Request)` 双入口**：默认实现里 `get(Request)` 直接退化为 `get()`（L37-39）；但过滤型装饰器会覆写 `get(Request)` 按请求上下文过滤（如 hint）。是否把请求传给委托链由 `LoadBalancerProperties.callGetWithRequestOnDelegates`（`LoadBalancerProperties.java:94`，默认 `true`）控制。

所有装饰器的公共基类是 `DelegatingServiceInstanceListSupplier`（`core/DelegatingServiceInstanceListSupplier.java:32`）：持有 `delegate`、转发 `getServiceId()`、把 `SelectedInstanceCallback`/`InitializingBean`/`DisposableBean` 逐层透传（初始化与销毁回调会沿链传播——4.4 的健康检查后台任务就靠它自动启停）。

## 4.2 基座实现：DiscoveryClientServiceInstanceListSupplier

**白话**：链的最底端，把 Spring Cloud 的 `DiscoveryClient`/`ReactiveDiscoveryClient`（注册中心的公共抽象）包成列表流。它最重要的品质是**永不抛错、永不挂死**——注册中心抖一下，你的调用不应该跟着挂。

【源码证据】`spring-cloud-loadbalancer/src/main/java/org/springframework/cloud/loadbalancer/core/DiscoveryClientServiceInstanceListSupplier.java` 第 47-101 行：

```java
	public static final String SERVICE_DISCOVERY_TIMEOUT = "spring.cloud.loadbalancer.service-discovery.timeout";  // L52

	private Duration timeout = Duration.ofSeconds(30);              // L56：默认 30 秒

	public DiscoveryClientServiceInstanceListSupplier(DiscoveryClient delegate, Environment environment) {   // L62
		String property = environment.getProperty(PROPERTY_NAME);   // L63：子容器里注入的服务名
		Assert.hasText(property, "'serviceId' must not be empty");
		this.serviceId = property;
		resolveTimeout(environment);
		this.serviceInstances = Flux.defer(() -> Mono.fromCallable(() -> delegate.getInstances(serviceId)))
			.timeout(timeout, Flux.defer(() -> {                    // L68：超时兜底
				logTimeout();
				return Flux.just(new ArrayList<>());
			}), Schedulers.boundedElastic())                        // L71：阻塞调用搬到弹性线程池
			.onErrorResume(error -> {                               // L72：异常兜底
				logException(error);
				return Flux.just(new ArrayList<>());
			});
	}
```

三个防御动作在源码里一目了然：**阻塞的 `getInstances` 被 `defer + boundedElastic` 挪出调用线程**（L67-71）、**超时 30 秒（可配）后按"空列表"处理**（L68-71）、**任何异常都降级为空列表**（L72-75）。空列表沿链上传，最终由算法层转成 `EmptyResponse` → 调用方收到 503。响应式构造器（L78-91）同理，只是把阻塞调用换成了 `ReactiveDiscoveryClient.getInstances(serviceId).collectList()`。

可配置项只有 `spring.cloud.loadbalancer.service-discovery.timeout`（支持 `10s`/`1m` 这类 Duration 格式，`resolveTimeout` L103-108 用 `DurationStyle.detectAndParse` 解析）。

## 4.3 缓存：CachingServiceInstanceListSupplier 与两级缓存实现

**白话**：这是默认链里**唯一默认开启**的装饰器（见 4.10）。它把"每次调用都问注册中心"变成"每 35 秒问一次"。

【源码证据】`spring-cloud-loadbalancer/src/main/java/org/springframework/cloud/loadbalancer/core/CachingServiceInstanceListSupplier.java` 第 38-85 行：

```java
public class CachingServiceInstanceListSupplier extends DelegatingServiceInstanceListSupplier {

	public static final String SERVICE_INSTANCE_CACHE_NAME =
			CachingServiceInstanceListSupplier.class.getSimpleName() + "Cache";   // L45-46：缓存名固定

	@SuppressWarnings("unchecked")
	public CachingServiceInstanceListSupplier(ServiceInstanceListSupplier delegate, CacheManager cacheManager) {
		super(delegate);
		String serviceId = delegate.getServiceId();
		this.serviceInstances = Flux.defer(() -> {
			Cache cache = cacheManager.getCache(SERVICE_INSTANCE_CACHE_NAME);   // L56
			...
			List<ServiceInstance> list = cache.get(serviceId, List.class);      // L63：缓存 key 就是 serviceId
			if (list != null && !list.isEmpty()) {
				return Flux.just(list);                                          // L65：命中直接返回
			}
			return delegate.get().take(1).doOnNext(instances -> {               // L68：未命中→问委托
				Cache writeCache = cacheManager.getCache(SERVICE_INSTANCE_CACHE_NAME);
				...
				writeCache.put(serviceId, instances);                            // L76：回填缓存
			});
		});
	}

	@Override
	public Flux<List<ServiceInstance>> get() {
		return serviceInstances;                                        // L83-85
	}
}
```

注意三个语义细节：

- **缓存 key 是 serviceId、缓存名固定**（L45-46，源码里留有 `TODO: configurable cache name`）——所有服务的实例列表共用同一个命名缓存区。
- **只缓存非空列表**（L65 的 `!list.isEmpty()` 判断）：注册中心短暂抖动返回空时不会把空列表写进缓存，从而避免"缓存里没有可用实例"的雪崩窗口。
- **委托只取一次**（`take(1)`，L68）：委托链可能发射多批（如健康检查的周期重发），缓存装饰器只消费第一批并回填。

**缓存实现有两套，自动二选一**（自动装配见 7.3）：类路径有 Caffeine 时用 `CaffeineBasedLoadBalancerCacheManager`（`cache/CaffeineBasedLoadBalancerCacheManager.java:36`，继承 Spring 的 `CaffeineCacheManager` 实现 `LoadBalancerCacheManager` 标记接口）；没有时用 `DefaultLoadBalancerCacheManager`（`cache/DefaultLoadBalancerCacheManager.java:50`），其底层 `DefaultLoadBalancerCache`（`cache/DefaultLoadBalancerCache.java:43`）基于第三方小库 **Evictor** 的 `ConcurrentMapWithTimedEviction`——一个自带"定时驱逐"的 ConcurrentHashMap（L22-24 的 import 与 Javadoc 注明出处）。

【源码证据】`cache/DefaultLoadBalancerCache.java` 第 110-139 行——每次写入都带驱逐时间：

```java
	@Override
	public @Nullable <T> T get(Object key, Callable<T> valueLoader) {
		return (T) fromStoreValue(cache.computeIfAbsent(key, k -> {    // L111：原子加载，防击穿
			try {
				return toStoreValue(valueLoader.call());
			}
			catch (Throwable ex) {
				throw new ValueRetrievalException(key, valueLoader, ex);
			}
		}));
	}
	...
	@Override
	public void put(Object key, @Nullable Object value) {
		cache.put(key, toStoreValue(value), evictMs);                   // L138：写入即定时驱逐
	}
```

**TTL 默认 35 秒**、容量默认 256，来自 `cache/LoadBalancerCacheProperties.java` 第 42、47 行：

```java
	private Duration ttl = Duration.ofSeconds(35);                  // L42

	private int capacity = 256;                                     // L47
```

配置前缀 `spring.cloud.loadbalancer.cache.*`。这 35 秒是使用者最常踩的坑——新实例上线后最长 35 秒不被调用（配合注册中心自身同步延迟可能更久），生产上要么调小 TTL，要么接受这个窗口（详见 8.5）。

## 4.4 健康检查：HealthCheckServiceInstanceListSupplier（主动探测）

**白话**：默认链**不含**它（要显式开 `configurations=health-check`）。开启后，框架会在客户端侧周期性 HTTP 探测每个实例的 `actuator/health`，把不健康的从列表里剔除；全挂时**回退返回全量**（宁可信注册中心，也不直接摆烂）。

【源码证据】`spring-cloud-loadbalancer/src/main/java/org/springframework/cloud/loadbalancer/core/HealthCheckServiceInstanceListSupplier.java` 第 48-160 行：

```java
public class HealthCheckServiceInstanceListSupplier extends DelegatingServiceInstanceListSupplier
		implements InitializingBean, DisposableBean {               // L48-49：自动启停后台任务

	public HealthCheckServiceInstanceListSupplier(ServiceInstanceListSupplier delegate,
			ReactiveLoadBalancer.Factory<ServiceInstance> loadBalancerClientFactory,
			BiFunction<ServiceInstance, String, Mono<Boolean>> aliveFunction) {   // L63-65：探测函数外置
		super(delegate);
		LoadBalancerProperties properties = loadBalancerClientFactory.getProperties(getServiceId());
		this.healthCheck = (properties == null) ? new LoadBalancerProperties.HealthCheck()
				: properties.getHealthCheck();
		defaultHealthCheckPath = healthCheck.getPath().getOrDefault("default", "/actuator/health");   // L70

		Flux<List<ServiceInstance>> aliveInstancesFlux = Flux.defer(delegate)
			.repeatWhen(completed -> completed.takeWhile(emitted -> this.healthCheck.getRefetchInstances())
				.delayElements(healthCheck.getRefetchInstancesInterval()))        // L72-74：按间隔重拉注册中心
			.switchMap(serviceInstances -> healthCheckFlux(serviceInstances).map(List::copyOf));
		aliveInstancesReplay = aliveInstancesFlux.delaySubscription(healthCheck.getInitialDelay())
			.replay(1)
			.refCount(1);                                            // L76-78：保留最近一批，首个订阅者触发
	}
```

```java
	protected Flux<List<ServiceInstance>> healthCheckFlux(List<ServiceInstance> instances) {   // L90
		return Flux.defer(() -> {
			List<Mono<ServiceInstance>> checks = new ArrayList<>(instances.size());
			for (ServiceInstance instance : instances) {
				Mono<ServiceInstance> alive = isAlive(instance).onErrorResume(error -> {
					...
					return Mono.empty();                          // 探测异常=不健康
				}).timeout(healthCheck.getInterval(), Mono.defer(() -> { ... return Mono.empty(); }))
				  .handle((isHealthy, sink) -> {
					if (isHealthy) {
						sink.next(instance);
					}
				});
				checks.add(alive);
			}
			...
			return Flux.merge(checks).collectList();              // L123：并发探测后收集全部结果
		})
			.repeatWhen(completed -> completed.takeWhile(emitted -> healthCheck.getRepeatHealthCheck())
				.delayElements(healthCheck.getInterval()));       // L125-126：周期重复
	}
```

读这段源码的四个要点：

1. **探测函数是外置的**（`aliveFunction`，L61）：`ServiceInstanceListSupplierBuilder` 里用 WebClient（响应式，L396-406）或 RestTemplate/RestClient（阻塞包 `Mono.defer`，L408-441）提供实现，核心逻辑与 HTTP 客户端解耦。
2. **探测路径按服务覆写**：`healthCheck.path.default=/actuator/health`，`path.<serviceId>` 可单改某服务；把某服务路径置空则跳过探测（`isAlive` L134-142 直接 `Mono.just(true)`）。探测端口也可独立覆写（`updatedServiceInstance` L152-160：健康检查走 management 端口时用）。
3. **背景长跑 + `replay(1).refCount(1)`**（L76-78）：bean 初始化后 `afterPropertiesSet`（L82-88）就 `subscribe()` 拉起常驻探测；`replay(1)` 让任意时刻的调用方立刻拿到"最近一次探测结果"，不必等下一轮。
4. **两个周期参数**：`interval`（默认 25s，既是探测周期也是单次探测超时）与 `refetchInstancesInterval`（默认 25s，从注册中心重拉全量的周期，`refetchInstances` 默认 false 不重拉）——前者管"实例死活"，后者管"列表成员"。

## 4.5 同区域优先：ZonePreferenceServiceInstanceListSupplier

**白话**：多机房部署时优先调同机房实例，本机房没有才走跨机房。这是唯一一个"过滤失败自动回退全量"语义写死在 Javadoc 里的装饰器。

【源码证据】`core/ZonePreferenceServiceInstanceListSupplier.java` 第 42-113 行（关键段）：

```java
	private static final String ZONE_KEY = "zone";                  // L44：按实例 metadata 里的 zone 匹配

	private List<ServiceInstance> filteredByZone(List<ServiceInstance> serviceInstances) {   // L85
		if (zone == null) {
			zone = zoneConfig.getZone();                            // L87-88：惰性读一次配置
		}
		if (zone != null) {
			List<ServiceInstance> filteredInstances = new ArrayList<>();
			for (ServiceInstance serviceInstance : serviceInstances) {
				String instanceZone = getZone(serviceInstance);     // L92：取实例 metadata["zone"]
				if (zone.equalsIgnoreCase(instanceZone)) {
					filteredInstances.add(serviceInstance);
				}
			}
			if (!filteredInstances.isEmpty()) {
				return filteredInstances;                           // L97-99：命中则只留同区域
			}
		}
		// If the zone is not set or there are no zone-specific instances available,
		// we return all instances retrieved for given service id.  // L101-103：回退全量
		return serviceInstances;
	}
```

本机区域来自 `spring.cloud.loadbalancer.zone`（`config/LoadBalancerAutoConfiguration.java:55-57` 把它放进 `LoadBalancerZoneConfig` bean）；在 Eureka 场景下，实例的 zone 通常就是注册中心 metadata 的 `zone` 键。**缓存装饰器在它上游**（默认链顺序 `withDiscoveryClient().withCaching().withZonePreference()`），所以"区域过滤"发生在缓存之后的内存列表上，切换 zone 无需清缓存。

## 4.6 加权：WeightedServiceInstanceListSupplier 与惰性展开

**白话**：4.0 引入。读实例 metadata 里的 `weight`（正整数，默认 1），把"3 台机器权重 1:1:2"变成"列表里 1:1:2 的重复槽位"，算法层继续无感轮询/随机。这个设计让加权**与任何算法正交**。

【源码证据】`core/WeightedServiceInstanceListSupplier.java` 第 38-132 行（关键段）：

```java
	static final String METADATA_WEIGHT_KEY = "weight";             // L42

	static final int DEFAULT_WEIGHT = 1;                            // L44

	@Override
	public Flux<List<ServiceInstance>> get() {
		return delegate.get().map(this::expandByWeight);            // L79：对每批列表做展开
	}

	private List<ServiceInstance> expandByWeight(List<ServiceInstance> instances) {   // L90
		if (instances.size() == 0) {
			return instances;
		}

		int[] weights = instances.stream().mapToInt(instance -> {
			try {
				int weight = weightFunction.apply(instance);
				if (weight <= 0) {
					... return DEFAULT_WEIGHT;                       // L98-105：非法权重降级为 1
				}
				return weight;
			}
			catch (Exception e) {
				... return DEFAULT_WEIGHT;                           // L108-115：异常降级为 1
			}
		}).toArray();

		return new LazyWeightedServiceInstanceList(instances, weights);   // L118：惰性展开
	}

	static int metadataWeightFunction(ServiceInstance serviceInstance) {   // L121：默认权重函数
		Map<String, String> metadata = serviceInstance.getMetadata();
		if (metadata != null) {
			String weightValue = metadata.get(METADATA_WEIGHT_KEY);
			if (weightValue != null) {
				return Integer.parseInt(weightValue);
			}
		}
		return DEFAULT_WEIGHT;                                       // L131：没配权重=1
	}
```

**精髓在 `LazyWeightedServiceInstanceList`**（`core/LazyWeightedServiceInstanceList.java:35`）：若权重是 1:100:1000，朴素展开要造 1101 个槽位。它的做法是先求**最大公约数归一化**（ctor L45-56：`total / greatestCommonDivisor` 决定数组长度），再只在算法层真正按下标取值时**按需展开**——`get(int index)`（L59-73）里用平滑加权轮转（`WeightedServiceInstanceSelector.next()` L110-132：active/expired 两个队列轮转，每次消耗 1 点权重、耗尽后回炉补满）填充到 index 为止，填满全表后把 selector 置 null 让 GC 回收。**轮询算法的 `pos % size` 与随机算法的 `nextInt(size)` 都天然适配这个"虚拟列表"**——上层零改动。

自定义权重函数也有口子：`ServiceInstanceListSupplierBuilder.withWeighted(WeightFunction)`（`ServiceInstanceListSupplierBuilder.java:138`）。

## 4.7 粘滞会话三兄弟

"粘滞"有三种完全不同的实现路径，按"粘在谁身上"区分：粘在 **Cookie**（跨请求、跨客户端进程）、粘在**本进程上次选择**（单客户端实例内）、粘在**灰度标记**（按请求头路由到特定分组）。

### 4.7.1 RequestBasedStickySessionServiceInstanceListSupplier（Cookie 粘滞）

【源码证据】`core/RequestBasedStickySessionServiceInstanceListSupplier.java` 第 61-104 行（关键段）：

```java
	@Override
	public Flux<List<ServiceInstance>> get(Request request) {        // L61
		...
		Object context = request.getContext();
		if (context instanceof RequestDataContext requestDataContext) {
			RequestData clientRequest = requestDataContext.getClientRequest();
			...
			String cookie = cookies.getFirst(instanceIdCookieName);  // L78：从请求 Cookie 里取实例 ID
			if (cookie != null) {
				return delegate.get(request).map(serviceInstances -> selectInstance(serviceInstances, cookie));
			}
			...
			return delegate.get(request);                            // L87：无 Cookie 则全量放行
		}
		return delegate.get(request);
	}

	private List<ServiceInstance> selectInstance(List<ServiceInstance> serviceInstances, String cookie) {   // L91
		for (ServiceInstance serviceInstance : serviceInstances) {
			if (cookie.equals(serviceInstance.getInstanceId())) {
				return Collections.singletonList(serviceInstance);   // L96：粘住该实例
			}
		}
		return serviceInstances;                                     // L103：实例已下线则回退全量
	}
```

配套的"写"端在 `LoadBalancerServiceInstanceCookieTransformer`（`core/LoadBalancerServiceInstanceCookieTransformer.java:51`，`LoadBalancerClientRequestTransformer` 实现，3.1 引入）：命中实例后在响应里追加 Cookie `sc-lb-instance-id=<instanceId>`（cookie 名常量见 `LoadBalancerProperties.java:202`，开关 `spring.cloud.loadbalancer.sticky-session.add-service-instance-cookie`）。读、写两端对称，跨请求会话粘滞闭环。

### 4.7.2 SameInstancePreferenceServiceInstanceListSupplier（进程内粘滞）

**白话**：不做任何跨请求约定，只是"这个客户端进程上次选了谁，下次还优先选谁；它挂了就放开重选"。适合把连续操作尽量压在同一台机器上提升缓存命中率的场景。

【源码证据】`core/SameInstancePreferenceServiceInstanceListSupplier.java` 第 78-99 行（关键段）：

```java
	private List<ServiceInstance> filteredBySameInstancePreference(List<ServiceInstance> serviceInstances) {  // L78
		if (previouslyReturnedInstance != null && serviceInstances.contains(previouslyReturnedInstance)) {
			...
			return Collections.singletonList(previouslyReturnedInstance);   // L82：上次选的还在→只留它
		}
		...
		previouslyReturnedInstance = null;                          // L92：已下线，清空记忆
		return serviceInstances;                                     // L93：全量放行
	}

	@Override
	public void selectedServiceInstance(ServiceInstance serviceInstance) {   // L96：算法层回调（见 3.2）
		super.selectedServiceInstance(serviceInstance);
		if (previouslyReturnedInstance == null || !previouslyReturnedInstance.equals(serviceInstance)) {
			previouslyReturnedInstance = serviceInstance;
		}
	}
```

它实现了 `SelectedInstanceCallback`：算法层每次选定实例都会回调它（装饰器基类会沿链透传，`DelegatingServiceInstanceListSupplier.java` 的 `selectedServiceInstance`），形成"选→记→下次优先选"的闭环。**注意它必须排在算法层能回调到的链上**——这也是为什么它的默认装配链是 `withDiscoveryClient().withCaching().withSameInstancePreference()`（`LoadBalancerClientConfiguration.java:148-152`）。

### 4.7.3 HintBasedServiceInstanceListSupplier（hint 灰度粘滞）

**白话**：3.0.2 引入。请求带 hint（HTTP 头 `X-SC-LB-Hint` 或属性 `hint`），实例 metadata 里配了 `hint` 键——对上的才放行，对不上回退全量。典型用法：网关按用户 ID 打标，把金丝版本实例的 metadata 配成 `hint=canary`。

【源码证据】`core/HintBasedServiceInstanceListSupplier.java` 第 41-105 行（关键段）：

```java
 * @since 3.0.2                                                     // L39
public class HintBasedServiceInstanceListSupplier extends DelegatingServiceInstanceListSupplier {   // L41
	...
	private @Nullable String getHint(@Nullable Object requestContext) {   // L63
		if (requestContext == null) {
			return null;
		}
		String hint = null;
		if (requestContext instanceof RequestDataContext requestDataContext) {
			hint = getHintFromHeader(requestDataContext);           // L69：先看 HTTP 头
		}
		if (!StringUtils.hasText(hint) && requestContext instanceof HintRequestContext hintRequestContext) {
			hint = hintRequestContext.getHint();                    // L71-73：再看上下文属性
		}
		return hint;
	}

	private @Nullable String getHintFromHeader(RequestDataContext context) {   // L77
		if (context.getClientRequest() != null) {
			HttpHeaders headers = context.getClientRequest().getHeaders();
			if (headers != null) {
				return headers.getFirst(properties.getHintHeaderName());   // L81：默认 X-SC-LB-Hint
			}
		}
		return null;
	}

	private List<ServiceInstance> filteredByHint(List<ServiceInstance> instances, @Nullable String hint) {   // L87
		...
		if (serviceInstance.getMetadata() != null
				&& serviceInstance.getMetadata().getOrDefault("hint", "").equals(hint)) {
			filteredInstances.add(serviceInstance);
		}
		...
		return instances;                                            // L104：无匹配回退全量
	}
```

头名可配（`LoadBalancerProperties.java:71`：`hintHeaderName`，默认 `X-SC-LB-Hint`）；hint 值本身也可按服务在 `spring.cloud.loadbalancer.hint.<serviceId>` 里配置（消费端发请求时由 Gateway/Feign 等注入）。

## 4.8 其余装饰器：Subset、RetryAware、ApiVersion

### 4.8.1 SubsetServiceInstanceListSupplier（子集路由，4.1 引入）

**白话**：大集群（如数千实例）下，让每个客户端只固定看一个小子集（默认 100 台），削减内存/传播开销。关键在"固定"——用**本机 instanceId 的哈希做种子**洗牌，同一台客户端每次算出的子集稳定一致。

【源码证据】`core/SubsetServiceInstanceListSupplier.java` 第 71-88 行（关键段）：

```java
	@Override
	public Flux<List<ServiceInstance>> get(Request request) {        // L71
		return delegate.get(request).map(instances -> {
			if (instances.size() <= size) {                          // L75：不超过子集大小就全量
				return instances;
			}

			instances = new ArrayList<>(instances);

			int instanceId = this.instanceId.hashCode() & Integer.MAX_VALUE;   // L79：本机身份哈希
			int count = instances.size() / size;                     // L80：分桶数
			int round = instanceId / count;                          // L81：洗牌种子（同一桶内一致）

			Random random = new Random(round);
			Collections.shuffle(instances, random);                  // L83-84：确定性洗牌

			int bucket = instanceId % count;                         // L85：本机落哪个桶
			int start = bucket * size;
			return instances.subList(start, start + size);           // L87：切出本桶
		});
	}
```

`instanceId` 取 `spring.cloud.loadbalancer.subset.instance-id`，未配置时退化为 `IdUtils.getDefaultInstanceId`（`resolveInstanceId` 私有方法解析，支持属性占位符）。

### 4.8.2 RetryAwareServiceInstanceListSupplier（重试感知，3.0 引入）

**白话**：重试时把"上次失败的那台"从候选里剔除。它与 2.2 节的 `RetryableRequestContext.previousServiceInstance` 是一对：

【源码证据】`core/RetryAwareServiceInstanceListSupplier.java` 第 44-62 行（关键段）：

```java
	@Override
	public Flux<List<ServiceInstance>> get(Request request) {
		if (!(request.getContext() instanceof RetryableRequestContext context)) {
			return delegate.get(request);                            // 非重试请求原样放行
		}
		ServiceInstance previousServiceInstance = context.getPreviousServiceInstance();
		if (previousServiceInstance == null) {
			return delegate.get(request);
		}
		return delegate.get(request).map(instances -> filteredByPreviousInstance(instances, previousServiceInstance));
	}

	private List<ServiceInstance> filteredByPreviousInstance(List<ServiceInstance> instances,
			ServiceInstance previousServiceInstance) {               // L62
		List<ServiceInstance> filteredInstances = new ArrayList<>(instances);
		if (previousServiceInstance != null) {
			filteredInstances.remove(previousServiceInstance);
		}
		if (filteredInstances.size() > 0) {
			return filteredInstances;
		}
		... return instances;                                        // L76：剔完剩空则回退全量
	}
```

它默认**不进链**——只有当"重试开启且 avoid-previous-instance 开启"时，`LoadBalancerClientConfiguration` 的 `BlockingRetryConfiguration`/`ReactiveRetryConfiguration`（`annotation/LoadBalancerClientConfiguration.java:316-344`）才会把已经构建好的链再包一层 `RetryAwareServiceInstanceListSupplier` 并标记 `@Primary`。开关见 6.3。

### 4.8.3 API 版本路由：Blocking/ReactiveApiVersionServiceInstanceListSupplier（5.0 引入）

**白话**：Framework 7 世代的版本化 API（`Accept: application/vnd+json;v=2`、URL 段 `/v2/`、query 参数等）落到负载均衡侧：请求声明的版本 → 匹配实例 metadata 里的版本，版本不匹配的实例不进候选；`required=false`（默认）时无可用匹配回退全量。

配置载体是 `LoadBalancerProperties.ApiVersion`（`LoadBalancerProperties.java:596-633`）：`required`（L601，默认 false）、`defaultVersion`（L607）、从哪取版本：`header`（L612）/`queryParameter`（L617）/`pathSegment`（L622）/`mediaTypeParameters`（L627），以及 `fallbackToAvailableInstances`（L633）。解析器默认是 `SemanticApiVersionParser` bean（`annotation/LoadBalancerClientConfiguration.java:82-84`）。两个实现分别处理响应式（`RequestDataContext`）与阻塞（`HttpRequest`）两种请求载体，由 `spring.cloud.loadbalancer.configurations=api-version` 开启（4.10）。

## 4.9 组装链：ServiceInstanceListSupplierBuilder

**白话**：以上所有装饰器靠一个 Builder 串起来——baseCreator 定"列表从哪来"，一串 DelegateCreator 定"列表被谁加工"，`build()` 从里向外套娃。

【源码证据】`core/ServiceInstanceListSupplierBuilder.java` 第 58-394 行（关键段）：

```java
public final class ServiceInstanceListSupplierBuilder {

	private @Nullable Creator baseCreator;                          // L62：唯一的"基座"

	private final List<DelegateCreator> creators = new ArrayList<>();   // L64：装饰器流水线

	public ServiceInstanceListSupplierBuilder withDiscoveryClient() {    // L93：响应式注册中心基座
		...
		this.baseCreator = context -> {
			ReactiveDiscoveryClient discoveryClient = context.getBean(ReactiveDiscoveryClient.class);

			return new DiscoveryClientServiceInstanceListSupplier(discoveryClient, context.getEnvironment());
		};
		return this;
	}

	public ServiceInstanceListSupplierBuilder withCaching() {            // L306
		DelegateCreator creator = (context, delegate) -> {
			ObjectProvider<LoadBalancerCacheManager> cacheManagerProvider = context
				.getBeanProvider(LoadBalancerCacheManager.class);
			if (cacheManagerProvider.getIfAvailable() != null) {
				return new CachingServiceInstanceListSupplier(delegate, cacheManagerProvider.getIfAvailable());
			}
			...
			return delegate;                                         // L316：没缓存组件就跳过这层
		};
		creators.add(creator);
		return this;
	}
	...
	public ServiceInstanceListSupplier build(ConfigurableApplicationContext context) {   // L384
		Assert.notNull(baseCreator, "A baseCreator must not be null");

		ServiceInstanceListSupplier supplier = baseCreator.apply(context);

		for (DelegateCreator creator : creators) {
			supplier = creator.apply(context, supplier);             // L390：按注册顺序逐层包裹
		}

		return supplier;
	}
}
```

`withXxx` 全家福（全部方法与行号，定制时按需取用）：

| 方法 | 行号 | 作用 |
|---|---|---|
| `withBlockingDiscoveryClient()` | L75 | 基座：阻塞 `DiscoveryClient` |
| `withDiscoveryClient()` | L93 | 基座：响应式 `ReactiveDiscoveryClient` |
| `withBase(supplier)` | L111 | 基座：用户自定义 |
| `withWeighted()` / `withWeighted(WeightFunction)` | L121 / L138 | 加权（默认读 metadata `weight`） |
| `withHealthChecks()` / `withHealthChecks(WebClient)` | L153 / L170 | 健康检查（WebClient 探测） |
| `withBlockingHealthChecks()` / `(RestTemplate)` / `withBlockingRestClientHealthChecks()` / `(RestClient)` | L198 / L229 / L213 / L244 | 健康检查（阻塞探测） |
| `withSameInstancePreference()` | L184 | 进程内粘滞 |
| `withZonePreference()` / `withZonePreference(zoneName)` | L258 / L274 | 同区域优先 |
| `withRequestBasedStickySession()` | L289 | Cookie 粘滞 |
| `withCaching()` | L306 | 缓存（无 CacheManager 则跳过） |
| `withRetryAwareness()` | L322 | 重试剔除上次失败实例 |
| `withHints()` | L328 | hint 过滤 |
| `withSubset()` | L337 | 子集路由 |
| `withReactiveApiVersioning()` / `withBlockingApiVersioning()` | L347 / L356 | API 版本路由 |
| `with(DelegateCreator)` | L371 | **万能扩展口**：插任意自定义装饰器 |

两个值得注意的组装纪律：

- **装饰顺序 = 注册顺序**（`build()` L390 的 for 循环从内往外套），默认链把 `withCaching()` 紧跟在发现之后、过滤装饰器之前——缓存"生列表"，装饰器在缓存之上做"每请求过滤"，各司其职。
- **健康检查链故意不加缓存**：默认装配是 `withDiscoveryClient().withHealthChecks()`（`LoadBalancerClientConfiguration.java:126`），因为健康检查自己会周期重拉列表（4.4），再套一层 35 秒缓存会把列表冻住，探测就失去意义了。

## 4.10 默认链装配：LoadBalancerClientConfiguration 的条件化 bean

**白话**：你什么都没配时，链是怎么长出来的？答案是 loadbalancer 模块的"每服务默认配置类"（也是第五章子容器的默认注册类型）提供了一组**互斥**的 `ServiceInstanceListSupplier` bean，按 `spring.cloud.loadbalancer.configurations` 的取值二选一（默认 `default`）。

【源码证据】`annotation/LoadBalancerClientConfiguration.java` 第 65-98 行（响应式侧节选；阻塞侧 `BlockingSupportConfiguration` L194-308 结构对称）：

```java
@Configuration(proxyBeanMethods = false)
@ConditionalOnDiscoveryEnabled
public class LoadBalancerClientConfiguration {                      // L67

	@Bean
	@ConditionalOnMissingBean
	public ReactorLoadBalancer<ServiceInstance> reactorServiceInstanceLoadBalancer(Environment environment,
			LoadBalancerClientFactory loadBalancerClientFactory) {  // L72-78：默认算法
		String name = environment.getProperty(LoadBalancerClientFactory.PROPERTY_NAME);
		return new RoundRobinLoadBalancer(
				loadBalancerClientFactory.getLazyProvider(name, ServiceInstanceListSupplier.class), name);
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnReactiveDiscoveryEnabled
	@Order(REACTIVE_SERVICE_INSTANCE_SUPPLIER_ORDER)                // L88：响应式优先于阻塞侧
	public static class ReactiveSupportConfiguration {

		@Bean
		@ConditionalOnBean(ReactiveDiscoveryClient.class)
		@ConditionalOnMissingBean
		@Conditional(DefaultConfigurationCondition.class)           // L94：configurations=default 或未配置
		public ServiceInstanceListSupplier discoveryClientServiceInstanceListSupplier(
				ConfigurableApplicationContext context) {
			return ServiceInstanceListSupplier.builder().withDiscoveryClient().withCaching().build(context);
		}                                                            // L95-98：默认链 = 发现 + 缓存
```

完整的 configurations 取值 → 链形 → 条件类行号对照（响应式侧，阻塞侧各有一个对称实现）：

| `spring.cloud.loadbalancer.configurations=` | 装配的链 | 条件类（行号） |
|---|---|---|
| `default`（默认/缺省） | 发现 + **缓存** | `DefaultConfigurationCondition` L393 |
| `zone-preference` | 发现 + 缓存 + 区域优先 | L403 |
| `health-check` | 发现 + 健康检查（**无缓存**） | L413 |
| `request-based-sticky-session` | 发现 + 缓存 + Cookie 粘滞 | L423 |
| `same-instance-preference` | 发现 + 缓存 + 进程内粘滞 | L433 |
| `weighted` | 发现 + 缓存 + 加权 | L443 |
| `subset` | 发现 + 缓存 + 子集 | L453 |
| `api-version` | 发现 + 缓存 + API 版本路由 | L463 |

三个装配细节：

1. **这些取值互斥**（各条件类用 `equalToForClientOrDefault` 精确匹配），想组合多个装饰器只能走自定义 `ServiceInstanceListSupplier` bean（8.3）。
2. **支持按服务名覆写**：条件类调的是 `LoadBalancerEnvironmentPropertyUtils.equalToForClientOrDefault(environment, "configurations", "…")`，它支持 `spring.cloud.loadbalancer.clients.<serviceId>.configurations` 单服务覆盖。
3. **重试包装链自动垫底**：`BlockingRetryConfiguration`（L316-326）与 `ReactiveRetryConfiguration`（L334-344）在"重试开启且 avoid-previous-instance 开启"时把链再包一层 `RetryAwareServiceInstanceListSupplier` 并 `@Primary`（见 4.8.2）。

## 4.11 本章小结

- 一条链的完整形态：`发现（永不抛错 + 超时兜底） → 缓存（35s TTL，只缓存非空） → 过滤/加权/粘滞/子集/版本路由（按需）`，算法层在链尾用 `.next()` 取最新一批。
- 九个内置装饰器全部继承 `DelegatingServiceInstanceListSupplier`，每个都遵循同一条安全语义：**过滤结果为空时回退委托全量**——宁可负载均衡失效，也不让请求无实例可去。
- 定制入口有三个层级：换/加一个装饰器（Builder 或自定义 bean）、换整条链（`configurations`）、全自定义（`withBase` + `with(DelegateCreator)`）。下一章解释这些 bean 到底住在哪个容器里。

---

# 五、隔离层：一个服务名一个子容器

> 本章对应源码：`support/LoadBalancerClientFactory.java`（loadbalancer 模块）、`annotation/` 四件套（loadbalancer 模块）、`spring-cloud-context` 模块的 `NamedContextFactory`。这一层继承自 Feign/Ribbon 时代的设计遗产（见 1.6.3）。

## 5.1 白话：为什么需要子容器

假设 `user-service` 要加权算法、`order-service` 要默认轮询 + 区域优先，而两者都叫 `RoundRobinLoadBalancer`/`ServiceInstanceListSupplier` 这些 bean 名——放在同一个 IoC 容器里必然冲突。Spring Cloud 的答案：**给每个 serviceId 开一个独立的小 ApplicationContext（子容器）**，同一套 bean 定义在每个子容器里各注册一份、互不可见；子容器把主容器设为 parent，从而共享主容器的 Environment（配置）与父 bean（如 `DiscoveryClient`）。

管理这些子容器的是 `LoadBalancerClientFactory`，它继承自通用的 `NamedContextFactory`（Feign 的 `FeignClientFactory`、Ribbon 的 `SpringClientFactory` 同源）。

【源码证据】`spring-cloud-loadbalancer/src/main/java/org/springframework/cloud/loadbalancer/support/LoadBalancerClientFactory.java` 第 46-98 行：

```java
public class LoadBalancerClientFactory extends NamedContextFactory<LoadBalancerClientSpecification>
		implements ReactiveLoadBalancer.Factory<ServiceInstance> {     // L46-47：既是工厂又是 SPI 门面

	public static final String NAMESPACE = "loadbalancer";              // L54：子容器属性源名

	// PROPERTY_NAME = "spring.cloud.loadbalancer.client.name"：服务名注入的属性键
	public static final String PROPERTY_NAME = NAMESPACE + ".client.name";   // L59
	@Override
	public ReactiveLoadBalancer<ServiceInstance> getInstance(String serviceId) {
		return getInstance(serviceId, ReactorServiceInstanceLoadBalancer.class);   // L79-81：按类型取算法 bean
	}

	@Override
	public LoadBalancerProperties getProperties(String serviceId) {     // L84：per-service 配置 overlay
		...
		if (serviceId == null || !properties.getClients().containsKey(serviceId)) {
			// no specific client properties, return default
			return properties;                                           // L92-93：没有专属配置就用全局
		}
		// because specifics are overlayed on top of defaults, everything in `properties`,
		// unless overridden, is in `clientsProperties`
		return properties.getClients().get(serviceId);                   // L97：有则返回该服务专属属性
	}
}
```

注意 `PROPERTY_NAME` 的妙用（配合第五章 4.2 节的 `DiscoveryClientServiceInstanceListSupplier` L63）：**子容器的 Environment 里被注入了一个 `spring.cloud.loadbalancer.client.name=<服务名>` 的属性**，所有 bean 都能通过 `environment.getProperty(PROPERTY_NAME)` 知道"我为谁服务"——这就是同一个配置类能在 N 个子容器里各造各 bean 的机关。

## 5.2 NamedContextFactory：子容器的创建与销毁

【源码证据】`spring-cloud-context/src/main/java/org/springframework/cloud/context/named/NamedContextFactory.java` 第 62-260 行：

```java
 * Creates a set of child contexts that allows a set of Specifications to define the beans
 * in each child context. Ported from spring-cloud-netflix FeignClientFactory and
 * SpringClientFactory                                                // L52-54：出处自述

	private final Map<String, GenericApplicationContext> contexts = new ConcurrentHashMap<>();   // L71

	protected GenericApplicationContext getContext(String name) {        // L121：双检锁懒创建
		if (!this.contexts.containsKey(name)) {
			synchronized (this.contexts) {
				if (!this.contexts.containsKey(name)) {
					this.contexts.put(name, createContext(name));
				}
			}
		}
		return this.contexts.get(name);
	}

	public void registerBeans(String name, GenericApplicationContext context) {   // L145：注册配置类
		...
		if (this.configurations.containsKey(name)) {                     // L148：本服务专属配置类
			for (Class<?> configuration : this.configurations.get(name).getConfiguration()) {
				registry.register(configuration);
			}
		}
		for (Map.Entry<String, C> entry : this.configurations.entrySet()) {
			if (entry.getKey().startsWith("default.")) {                 // L154：default.* 前缀=全局默认配置
				for (Class<?> configuration : entry.getValue().getConfiguration()) {
					registry.register(configuration);
				}
			}
		}
		registry.register(PropertyPlaceholderAutoConfiguration.class, this.defaultConfigType);   // L160：默认配置类垫底
	}

	public GenericApplicationContext buildContext(String name) {         // L163：造子容器
		...
		context.getEnvironment()
			.getPropertySources()
			.addFirst(
					new MapPropertySource(this.propertySourceName,
							Collections.singletonMap(this.propertyName, name)));   // L185-188：注入服务名属性
		if (this.parent != null) {
			// Uses Environment from parent as well as beans
			context.setParent(this.parent);                              // L189-192：挂主容器为父
		}
		...
	}

	public void destroy() {                                              // L111：主容器关闭时逐一关闭子容器
		Collection<GenericApplicationContext> values = this.contexts.values();
		for (GenericApplicationContext context : values) {
			context.close();
		}
		this.contexts.clear();
	}
```

配置类的注册顺序决定了优先级（后注册者 + `@ConditionalOnMissingBean` 让位机制共同作用）：**服务专属配置类 → `default.*` 全局默认配置类 → 框架默认配置类（`LoadBalancerClientConfiguration`）**。这正是 3.4 节"换算法"能生效的底层原因：用户的 `RandomLoadBalancer` bean 属于第一梯队，默认的 `@ConditionalOnMissingBean` 轮询 bean 检测到已有同类型 bean 便不再注册。

取 bean 的几个门面方法：`getInstance(name, type)`（L201，找不到返回 null 不抛异常）、`getLazyProvider(name, type)`（L212，返回延迟到子容器里找 bean 的 `ObjectProvider`——算法构造器里传的就是它）、`getInstances(name, type)`（L255，含祖先容器的全部匹配 bean，供 `LoadBalancerLifecycle` 收集用）。

## 5.3 注解四件套：把配置类塞进正确的子容器

**白话**：`@LoadBalancerClient(name = "user-service", configuration = X.class)` 这行注解做了什么？它在主容器里注册了一个"说明书" bean（`LoadBalancerClientSpecification`，内容是服务名 + 配置类）；`LoadBalancerClientFactory` 启动时收集所有说明书，之后第一次用到 `user-service` 时按说明书装配子容器。

【源码证据】`annotation/LoadBalancerClient.java` 第 42-68 行：

```java
@Configuration(proxyBeanMethods = false)
@Import(LoadBalancerClientConfigurationRegistrar.class)              // L38：注册器入口
public @interface LoadBalancerClient {

	@AliasFor("name")
	String value() default "";                                           // L51

	@AliasFor("value")
	String name() default "";                                            // L59

	Class<?>[] configuration() default {};                               // L68：子容器专属配置类
```

`@LoadBalancerClients`（`annotation/LoadBalancerClients.java:39-42`）支持批量：`LoadBalancerClient[] value()`，并另有一个 `defaultConfiguration` 属性——它注册的说明书名以 `default.` 开头，进入 `NamedContextFactory.registerBeans` 的 L154 全局默认分支，**对所有服务生效**。

【源码证据】`annotation/LoadBalancerClientConfigurationRegistrar.java` 第 47-80 行——注册器把注解翻译成说明书 bean：

```java
	private static void registerClientConfiguration(BeanDefinitionRegistry registry, Object name,
			Object configuration) {
		BeanDefinitionBuilder builder = BeanDefinitionBuilder
			.genericBeanDefinition(LoadBalancerClientSpecification.class);
		builder.addConstructorArgValue(name);
		builder.addConstructorArgValue(configuration);
		registry.registerBeanDefinition(name + ".LoadBalancerClientSpecification", builder.getBeanDefinition());   // L53
	}

	@Override
	public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {   // L57
		Map<String, Object> attrs = metadata.getAnnotationAttributes(LoadBalancerClients.class.getName());
		if (attrs != null && attrs.containsKey("value")) {
			... // 逐个注册 @LoadBalancerClients 的 value
		}
		if (attrs != null && attrs.containsKey("defaultConfiguration")) {
			String name;
			if (metadata.hasEnclosingClass()) {
				name = "default." + metadata.getEnclosingClassName();    // L68：default. 前缀=全局
			}
			...
		}
		...
	}
```

说明书本身是极简的数据类：`LoadBalancerClientSpecification`（`annotation/LoadBalancerClientSpecification.java:29`，实现 `NamedContextFactory.Specification`：`getName()` + `getConfiguration()`）。工厂侧的收口在自动装配：`config/LoadBalancerAutoConfiguration.java` 第 61-67 行把容器里所有 Specification 收进工厂：

```java
	@ConditionalOnMissingBean
	@Bean
	public LoadBalancerClientFactory loadBalancerClientFactory(LoadBalancerClientsProperties properties,
			ObjectProvider<List<LoadBalancerClientSpecification>> configurations) {
		LoadBalancerClientFactory clientFactory = new LoadBalancerClientFactory(properties);
		clientFactory.setConfigurations(configurations.getIfAvailable(Collections::emptyList));
		return clientFactory;
	}
```

**实战约定**：`configuration` 指向的配置类**不要**再加 `@Configuration`（或至少确保不被主类组件扫描到）——否则它会同时被主容器加载，子容器隔离就名存实亡（官方文档 `loadbalancer.adoc` 也如此建议）。这也是 3.4 示例里 `CustomLoadBalancerConfig` 不加注解的原因。

## 5.4 预热与 AOT：子容器的两个现代化补丁

子容器是"第一次用到才创建"的（5.2 `getContext` 双检锁），带来两个问题：**首次调用抖动**与 **AOT/Native 下无法运行期装配**。4.0 起给了两个补丁：

1. **预热**：`support/LoadBalancerEagerContextInitializer.java:27` 是 `ApplicationListener<ApplicationReadyEvent>`，应用就绪即按配置 `spring.cloud.loadbalancer.eager-load.clients` 列表逐个 `factory::getInstance`（L39），提前把子容器建好。配置载体 `LoadBalancerEagerLoadProperties`（commons，`LoadBalancerEagerLoadProperties.java:27`）。
2. **AOT**：`aot/LoadBalancerChildContextInitializer.java:52` 实现 `BeanRegistrationAotProcessor`（L65 `processAheadOfTime`），构建期为每个（显式配置或 eager-load 声明的）serviceId 预构建子上下文并生成代码，运行期由 `LoadBalancerClientFactory` 构造器传入的 `applicationContextInitializers` 直接回放——`NamedContextFactory.createContext`（L132-143）开头那段 `if (applicationContextInitializers.get(name) != null)` 就是 AOT 回放分支。

## 5.5 本章小结

- 每服务一容器 = **配置隔离的物理边界**：同一 bean 名在各子容器独立存在，服务专属配置类 > `default.*` > 框架默认配置类。
- 服务名的注入靠子容器 Environment 的 `spring.cloud.loadbalancer.client.name` 属性，配置 overlay 靠 `LoadBalancerClientFactory.getProperties` 的 per-service 分支。
- `@LoadBalancerClient(s)` 注解四件套本质是"往主容器塞说明书"，运行期才按说明书开子容器；预热与 AOT 解决懒创建的两类现代问题。

---

# 六、接入层：三种调用姿势的完整链路

> 本章对应源码：commons 的 `client/loadbalancer`（阻塞拦截器）与 `client/loadbalancer/reactive`（WebClient 过滤器）、loadbalancer 的 `blocking/`（阻塞桥接），以及 gateway 仓库的 `ReactiveLoadBalancerClientFilter`。

## 6.1 阻塞主链：@LoadBalanced RestTemplate 如何被"接见"

**白话**：`@LoadBalanced` 标注的 `RestTemplate` 会被自动装配塞进一个拦截器；拦截器把 URL 里的主机名当服务名，交给 `BlockingLoadBalancerClient` 选实例、替换 URL、真正发请求。

全链路（自上而下）：

```
业务代码：restTemplate.getForObject("http://user-service/api/users", ...)
  │
  ▼ LoadBalancerAutoConfiguration（commons）                    ← @LoadBalanced 收集点
  　restTemplates 字段（L63-65）收集所有 @LoadBalanced 的 RestTemplate；
  　RestTemplateCustomizer（L165）把 LoadBalancerInterceptor 塞进拦截器链
  │
  ▼ LoadBalancerInterceptor.intercept（commons L50-56）
  　serviceName = originalUri.getHost()                        ← 主机名=服务名
  　return loadBalancer.execute(serviceName, requestFactory.createRequest(...));
  │
  ▼ BlockingLoadBalancerClient.execute(serviceId, request)（loadbalancer L68-81）
  　① lifecycle.onStart(lbRequest)                              ← 生命周期回调（第七章）
  　② choose(serviceId, lbRequest) 选实例；null → DISCARD + IllegalStateException
  　③ execute(serviceId, serviceInstance, lbRequest)（L95-124）：
  　　 lifecycle.onStartRequest → request.apply(serviceInstance) 真正执行
  　　 → lifecycle.onComplete(SUCCESS / FAILED)
  │
  ▼ choose(serviceId, request)（L158-168）——阻塞/响应式的分界线
  　Mono.from(loadBalancer.choose(request)).block()            ← L163：桥接点
```

【源码证据】`spring-cloud-commons/.../loadbalancer/LoadBalancerInterceptor.java` 第 50-56 行：

```java
	@Override
	public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
			throws IOException {
		URI originalUri = request.getURI();
		String serviceName = originalUri.getHost();                 // L53：主机名即服务名
		Assert.state(serviceName != null, "Request URI does not contain a valid hostname: " + originalUri);
		return loadBalancer.execute(serviceName, requestFactory.createRequest(request, body, execution));
	}
```

【源码证据】`spring-cloud-loadbalancer/.../blocking/client/BlockingLoadBalancerClient.java` 第 158-168 行：

```java
	@Override
	public <T> ServiceInstance choose(String serviceId, Request<T> request) {
		ReactiveLoadBalancer<ServiceInstance> loadBalancer = loadBalancerClientFactory.getInstance(serviceId);
		if (loadBalancer == null) {
			return null;                                             // L162：工厂无实现→无实例
		}
		Response<ServiceInstance> loadBalancerResponse = Mono.from(loadBalancer.choose(request)).block();   // L163
		if (loadBalancerResponse == null) {
			return null;
		}
		return loadBalancerResponse.getServer();
	}
```

`execute` 的两个重载分工（L68 与 L95）：第一个"先选后发"，第二个"指定实例直接发"（OpenFeign 等自带选择逻辑的消费方用它跳过选择）。两者都完整驱动 `LoadBalancerLifecycle` 三阶段回调（第七章）。

## 6.2 RestClient / HttpServiceClient 时代：拦截器的"延迟接线"（4.2 起）

**白话**：Boot 3.2 引入 `RestClient`、Framework 7 引入 HttpServiceClient（HTTP 接口分组）后，接入点从"拦截器列表"变成"客户端构建期定制"。但有个新麻烦：构建 `RestClient.Builder` 的时机可能早于负载均衡拦截器 bean 就绪，直接注入会拿不到 bean。4.2 的解法是**两段式**：

1. **`DeferringLoadBalancerInterceptor`**（commons，`DeferringLoadBalancerInterceptor.java:38`，4.2 引入）：一个"壳"拦截器，注入的是 `ObjectProvider<BlockingLoadBalancerInterceptor>`，每次 `intercept`（L51）才现场解析真实拦截器（无重试的 `LoadBalancerInterceptor` 或带重试的 `RetryLoadBalancerInterceptor`）。接口化抽象 `BlockingLoadBalancerInterceptor`（commons，`BlockingLoadBalancerInterceptor.java:28`，4.2 引入）让两兄弟可互换。
2. **两个 Builder 后置处理器**：`LoadBalancerRestTemplateBuilderBeanPostProcessor` / `LoadBalancerRestClientBuilderBeanPostProcessor`（commons 自动装配 `LoadBalancerAutoConfiguration.java:102-104、124-126`）在 bean 初始化期把壳拦截器挂上 Builder；5.0 再补 `LoadBalancerRestClientHttpServiceGroupConfigurer`（`LoadBalancerRestClientHttpServiceGroupConfigurer.java:25`，对接 Boot 4 的 `HttpServiceClientProperties` 服务组），让 Framework 7 的 `@ImportHttpServices` 分组客户端也能走负载均衡。

这一节是"接入点现代化"的缩影：**SPI 未变，接线方式随 Spring 世代表演进**——RestTemplate（拦截器直塞）→ RestClient/Builder（后置处理器）→ HttpServiceClient（GroupConfigurer）。

## 6.3 阻塞重试链：RetryLoadBalancerInterceptor + RetryPolicy

**白话**：类路径有 `spring-retry` 时，commons 自动装配改用 `RetryLoadBalancerInterceptor`（`RetryLoadBalancerInterceptor.java:49`，条件分支见 commons `LoadBalancerAutoConfiguration` 的 `RetryMissingOrDisabledCondition`，无重试版在 `LoadBalancerInterceptorConfig` L155）。它把"选实例 → 发请求 → 判断可否重试"包进 `RetryTemplate`。

【源码证据】`RetryLoadBalancerInterceptor.java` 第 71-130 行（关键段）：

```java
	@Override
	public ClientHttpResponse intercept(final HttpRequest request, final byte[] body,
			ClientHttpRequestExecution execution) throws IOException {       // L71
		...
		final LoadBalancedRetryPolicy retryPolicy = lbRetryFactory.createRetryPolicy(serviceName, loadBalancer);
		RetryTemplate template = createRetryTemplate(serviceName, request, retryPolicy);
		return template.execute(context -> {                                  // L78：spring-retry 主循环
			ServiceInstance serviceInstance = null;
			if (context instanceof LoadBalancedRetryContext lbContext) {
				serviceInstance = lbContext.getServiceInstance();             // L83：上一轮的实例
				...
			}
			...
			if (serviceInstance == null) {                                    // L101：首轮或需要重选
				...
				ServiceInstance previousServiceInstance = ... lbContext.getPreviousServiceInstance();
				DefaultRequest<RetryableRequestContext> lbRequest = new DefaultRequest<>(
						new RetryableRequestContext(previousServiceInstance, new RequestData(request), hint));  // L113-114
				...
				serviceInstance = loadBalancer.choose(serviceName, lbRequest); // L117：带"上次失败实例"上下文选新实例
				...
			}
			...
			ClientHttpResponse response = loadBalancer.execute(serviceName, finalServiceInstance, lbRequest);
			int statusCode = response.getStatusCode().value();
			if (retryPolicy != null && retryPolicy.retryableStatusCode(statusCode)) {   // L126：可重试状态码
				...                                                            // 复制响应体，抛出重试异常
```

重试策略的"同实例 vs 换实例"两级配额在 `BlockingLoadBalancedRetryPolicy`（loadbalancer `blocking/retry/`）：

```java
	public boolean canRetrySameServer(LoadBalancedRetryContext context) {   // L51
		return sameServerCount < properties.getRetry().getMaxRetriesOnSameServiceInstance() && canRetry(context);
	}                                                                        // 默认 maxRetriesOnSameServiceInstance=0

	public boolean canRetryNextServer(LoadBalancedRetryContext context) {   // L56
		return nextServerCount <= properties.getRetry().getMaxRetriesOnNextServiceInstance() && canRetry(context);
	}                                                                        // 默认 maxRetriesOnNextServiceInstance=1
```

与装饰器层的联动：重试轮次里带上了 `RetryableRequestContext`（`RetryLoadBalancerInterceptor.java:113-114`），若 `retry.avoid-previous-instance=true`（4.1.0 起的属性，per-service 支持），4.8.2 的 `RetryAwareServiceInstanceListSupplier` 就会在装饰器层把上次失败的实例剔除——**策略层决定"重不重试"，来源层保证"别再撞同一堵墙"**。spring-retry 属于可选依赖，没有它就没有这条链（ starter 不带，需自行引入 `spring-retry`）。

## 6.4 响应式主链：WebClient 的 ExchangeFilterFunction

**白话**：`@LoadBalanced WebClient.Builder` 会被挂上 `ReactorLoadBalancerExchangeFilterFunction`（commons reactive 包）。它与 `BlockingLoadBalancerClient` 是镜像关系——只是"桥接方向"反了：阻塞版把 Mono `block()` 成实例，响应式版把整个流程编成一条 Mono 流水线。

【源码证据】`spring-cloud-commons/.../loadbalancer/reactive/ReactorLoadBalancerExchangeFilterFunction.java` 第 72-121 行：

```java
	@Override
	public Mono<ClientResponse> filter(ClientRequest clientRequest, ExchangeFunction next) {
		URI originalUrl = clientRequest.url();
		String serviceId = originalUrl.getHost();                    // L74：主机名=服务名
		if (serviceId == null) { ... return Mono.just(ClientResponse.create(HttpStatus.BAD_REQUEST)...); }
		...
		String hint = getHint(serviceId, loadBalancerFactory.getProperties(serviceId).getHint());
		RequestData requestData = new RequestData(clientRequest);    // L86：请求快照（2.2 节）
		DefaultRequest<RequestDataContext> lbRequest = new DefaultRequest<>(new RequestDataContext(requestData, hint));
		supportedLifecycleProcessors.forEach(lifecycle -> lifecycle.onStart(lbRequest));   // L88
		return choose(serviceId, lbRequest).flatMap(lbResponse -> {  // L89：选择
			ServiceInstance instance = lbResponse.getServer();
			if (instance == null) {
				... lifecycle.onComplete(new CompletionContext<>(DISCARD, ...));   // L96-97
				return Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE)
					.body(serviceInstanceUnavailableMessage(serviceId)).build());  // L98-100：503
			}
			...
			ClientRequest newRequest = buildClientRequest(clientRequest, instance,
					stickySessionProperties.getInstanceIdCookieName(),
					stickySessionProperties.isAddServiceInstanceCookie(), transformers);   // L109-111：URI 重建+粘滞 Cookie
			supportedLifecycleProcessors.forEach(lifecycle -> lifecycle.onStartRequest(lbRequest, lbResponse));  // L112
			return next.exchange(newRequest)                          // L113：放行到真实 HTTP 层
				.doOnError(throwable -> ... onComplete(FAILED ...))   // L114-116
				.doOnSuccess(clientResponse -> ... onComplete(SUCCESS ...));   // L117-119
		});
	}

	protected Mono<Response<ServiceInstance>> choose(String serviceId, Request<RequestDataContext> request) {   // L123
		ReactiveLoadBalancer<ServiceInstance> loadBalancer = loadBalancerFactory.getInstance(serviceId);
		if (loadBalancer == null) {
			return Mono.just(new EmptyResponse());
		}
		return Mono.from(loadBalancer.choose(request));
	}
```

带重试的版本 `RetryableLoadBalancerExchangeFilterFunction`（`RetryableLoadBalancerExchangeFilterFunction.java:61`，3.0 引入，无需 spring-retry——基于 Reactor 的 `retryWhen` 与 `RetryableLoadBalancerExchangeFilterFunction` 自带的 `LoadBalancerRetryPolicy`）按 `LoadBalancerProperties.Retry`（状态码白名单、同/换实例配额、退避）在响应式世界重演同一套策略。

## 6.5 Gateway：ReactiveLoadBalancerClientFilter 与 `lb://` 路由

**白话**：网关路由配置 `uri: lb://user-service` 时，`ReactiveLoadBalancerClientFilter`（order 10150）在转发前把 `lb://user-service/api` 翻译成真实实例地址。它是"消费方接入"的最重负载场景：每个请求一次 `choose`。

【源码证据】`D:\code\3rd\spring-cloud-gateway\spring-cloud-gateway-server-webflux\src\main\java\org\springframework\cloud\gateway\filter\ReactiveLoadBalancerClientFilter.java` 第 63-167 行：

```java
	public static final int LOAD_BALANCER_CLIENT_FILTER_ORDER = 10150;   // L70

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		URI url = exchange.getAttribute(GATEWAY_REQUEST_URL_ATTR);
		String schemePrefix = exchange.getAttribute(GATEWAY_SCHEME_PREFIX_ATTR);
		if (url == null || (!"lb".equals(url.getScheme()) && !"lb".equals(schemePrefix))) {
			return chain.filter(exchange);                            // L91-93：不是 lb:// 直接放行
		}
		// preserve the original url
		addOriginalRequestUrl(exchange, url);
		...
		String serviceId = requestUri.getHost();                     // L103
		...
		DefaultRequest<RequestDataContext> lbRequest = new DefaultRequest<>(new RequestDataContext(
				new RequestData(exchange.getRequest(), exchange.getAttributes()), getHint(serviceId)));   // L107-108
		return choose(lbRequest, serviceId, supportedLifecycleProcessors).doOnNext(response -> {
			if (!response.hasServer()) {
				... lifecycle.onComplete(new CompletionContext<>(DISCARD, ...));
				throw NotFoundException.create(properties.isUse404(), "Unable to find instance for " + url.getHost());   // L114：404 或 503
			}

			ServiceInstance retrievedInstance = response.getServer();
			...
			URI requestUrl = reconstructURI(serviceInstance, uri);    // L132：URI 重建（6.6）
			...
			exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, requestUrl);   // L137：写回请求 URL 属性
			...
		})
			.then(chain.filter(exchange))                             // L141：继续后续过滤器链
			.doOnError(...)                                           // L142-145：FAILED 回调
			.doOnSuccess(...);                                        // L146-151：SUCCESS 回调
	}

	private Mono<Response<ServiceInstance>> choose(Request<RequestDataContext> lbRequest, String serviceId,
			Set<LoadBalancerLifecycle> supportedLifecycleProcessors) {   // L158
		ReactorLoadBalancer<ServiceInstance> loadBalancer = this.clientFactory.getInstance(serviceId,
				ReactorServiceInstanceLoadBalancer.class);
		if (loadBalancer == null) {
			throw new NotFoundException("No loadbalancer available for " + serviceId);
		}
		...
		return loadBalancer.choose(lbRequest);
	}
```

两个网关特有细节：`GATEWAY_REQUEST_URL_ATTR` 属性在过滤器间传递（RouteToRequestUrlFilter 先把 `lb://` 写进来，本过滤器替换成真实地址）；找不到实例时 `properties.isUse404()` 决定回 404 还是 503（网关侧 `GatewayLoadBalancerProperties`，默认 503，语义上"服务存在但无实例"回 504/503 更准确，`use-404: true` 用于把"无此服务"混入 404 语义的场景）。

## 6.6 URI 重建：LoadBalancerUriTools

**白话**：把 `http://user-service/api/x` 换成 `http://10.0.3.17:8080/api/x`，听着简单，坑在 scheme 升级（http→https、ws→wss）、端口缺省、URL 编码三处。

【源码证据】`spring-cloud-commons/.../loadbalancer/LoadBalancerUriTools.java` 第 47-119 行：

```java
	public static final String DEFAULT_SCHEME = "http";                 // L47

	static {
		INSECURE_SCHEME_MAPPINGS = new HashMap<>();                     // L50-53：明文→安全 scheme 映射
		INSECURE_SCHEME_MAPPINGS.put(DEFAULT_SCHEME, DEFAULT_SECURE_SCHEME);   // http→https
		INSECURE_SCHEME_MAPPINGS.put("ws", "wss");                      // ws→wss
	}
	...
	public static URI reconstructURI(ServiceInstance serviceInstance, URI original) {   // L91
		if (serviceInstance == null) {
			throw new IllegalArgumentException("Service Instance cannot be null.");
		}
		return doReconstructURI(serviceInstance, original);
	}

	private static URI doReconstructURI(ServiceInstance serviceInstance, URI original) {   // L98
		String host = serviceInstance.getHost();
		String scheme = Optional.ofNullable(serviceInstance.getScheme())
			.orElse(computeScheme(original, serviceInstance));          // L100-101：实例自带 scheme 优先
		int port = computePort(serviceInstance.getPort(), scheme);      // L102：缺省端口按 scheme 补 80/443

		if (Objects.equals(host, original.getHost()) && port == original.getPort()
				&& Objects.equals(scheme, original.getScheme())) {
			return original;                                            // L104-107：没变化就不重建（省编码开销）
		}

		boolean encoded = containsEncodedParts(original);
		return UriComponentsBuilder.fromUri(original).scheme(scheme).host(host).port(port).build(encoded).toUri();
	}

	private static String computeScheme(URI original, ServiceInstance serviceInstance) {   // L113
		String originalOrDefault = Optional.ofNullable(original.getScheme()).orElse(DEFAULT_SCHEME);
		if (serviceInstance.isSecure() && INSECURE_SCHEME_MAPPINGS.containsKey(originalOrDefault)) {
			return INSECURE_SCHEME_MAPPINGS.get(originalOrDefault);     // L115-117：实例是安全的→升级 scheme
		}
		return originalOrDefault;
	}
```

细读三个防御点：**实例声明 `isSecure` 时把请求 scheme 升级**（ws→wss 一并覆盖，L113-119）；**端口 -1（未注册端口）按 scheme 补默认端口**（`computePort` L75-83）；**原 URI 含百分号编码时按"已编码"模式重建**，避免二次编码（`containsEncodedParts` L58-73 还会尝试完整解码验证，只把"真正全编码"当编码处理）。注释里保留了它的出身：从 Gateway 的 `ServerWebExchangeUtils` 抽出的公共实现（L55-57）。

## 6.7 本章小结

- 三种接入姿势共享同一副骨架：**主机名当服务名 → 构造 `RequestDataContext`（含 hint）→ `choose()` → 重建 URI → 发请求 → 三段生命周期回调**；差别只在"如何接到调用流量"（拦截器 / ExchangeFilterFunction / GlobalFilter）与"如何等待 Mono"（`block()` / 全响应式）。
- 阻塞世界的演进线：RestTemplate 直塞拦截器（2.2）→ `DeferringLoadBalancerInterceptor` 壳 + Builder 后置处理器（4.2）→ HttpServiceClient GroupConfigurer（5.0）。SPI 始终未变。
- URI 重建是"最后一公里"里最容易被低估的复杂点：scheme 升级、默认端口、编码保真，`LoadBalancerUriTools` 120 行全部是这些细节。

---

# 七、生命周期与可观测

> 本章对应源码：commons 的 `LoadBalancerLifecycle`、loadbalancer 的 `stats/`、`config/`。

## 7.1 LoadBalancerLifecycle：选择与调用的三段回调

**白话**：想在"每次负载均衡调用"上挂钩子（打点、审计、熔断统计）？实现 `LoadBalancerLifecycle` 并注册成 bean（放进对应服务的子容器）即可，框架会在三个时机回调你。

【源码证据】`spring-cloud-commons/.../loadbalancer/LoadBalancerLifecycle.java` 第 24-62 行：

```java
public interface LoadBalancerLifecycle<RC, RES, T> {

	default boolean supports(Class requestContextClass, Class responseClass, Class serverTypeClass) {   // L37
		...
	}

	void onStart(Request<RC> request);                                  // L46：开始选择实例

	void onStartRequest(Request<RC> request, Response<T> lbResponse);   // L55：已选定，即将真正发请求

	void onComplete(CompletionContext<RES, T, RC> completionContext);   // L62：整个调用收场（SUCCESS/FAILED/DISCARD）
}
```

三个时机的语义边界（配合 2.3 节的 `CompletionContext.Status`）：

- `onStart`：还没选实例。`DISCARD` 的场景（无实例可选）只有 `onStart` 没有真正的请求。
- `onStartRequest`：实例已选定（`lbResponse` 可取到 `getServer()`），请求即将发出。
- `onComplete`：收场。SUCCESS 携带 `ResponseData`（HTTP 状态码、耗时上下文），FAILED 携带异常，DISCARD 表示请求根本没有发出。

`supports()`（L37）让回调按类型选择性生效：框架侧通过 `LoadBalancerLifecycleValidator.getSupportedLifecycleProcessors(...)`（见 `ReactorLoadBalancerExchangeFilterFunction.java:82-84`、`BlockingLoadBalancerClient.java:141-145`）只挑选"上下文/响应/实例类型都匹配"的回调注册——例如阻塞侧默认用 `DefaultRequestContext`，网关/WebClient 侧用 `RequestDataContext`，回调 bean 可只认其一。

**接线点回顾**（第六章已埋伏笔）：阻塞链在 `BlockingLoadBalancerClient.execute`（L72-73 onStart、L101-104 onStartRequest、L108-120 onComplete）；响应式链在 `ReactorLoadBalancerExchangeFilterFunction.filter`（L88、L112、L114-119，用 `doOnError`/`doOnSuccess` 挂在 Reactor 流上）；网关链在 `ReactiveLoadBalancerClientFilter`（L104-151）。

## 7.2 Micrometer 指标：MicrometerStatsLoadBalancerLifecycle

**白话**：官方提供的"现成回调"——统计每次负载均衡调用的次数、耗时、活跃请求数。**默认关闭**，需显式开启。

【源码证据】`config/LoadBalancerStatsAutoConfiguration.java`（关键条件）与 `stats/MicrometerStatsLoadBalancerLifecycle.java` 第 49-102 行：

```java
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnProperty(value = "spring.cloud.loadbalancer.stats.micrometer.enabled", havingValue = "true")   // 默认未开启
public class LoadBalancerStatsAutoConfiguration {
	@Bean
	@ConditionalOnBean(MeterRegistry.class)
	public MicrometerStatsLoadBalancerLifecycle micrometerStatsLifecycle(MeterRegistry meterRegistry,
			ReactiveLoadBalancer.Factory<ServiceInstance> loadBalancerFactory) { ... }
}
```

```java
public class MicrometerStatsLoadBalancerLifecycle implements LoadBalancerLifecycle<Object, Object, ServiceInstance> {   // L49

	private final ConcurrentHashMap<ServiceInstance, AtomicLong> activeRequestsPerInstance = new ConcurrentHashMap<>();   // L61
	...
	@Override
	public void onStartRequest(Request<Object> request, Response<ServiceInstance> lbResponse) {
		if (request != null && request.getContext() instanceof TimedRequestContext) {
			((TimedRequestContext) request.getContext()).setRequestStartTime(System.nanoTime());   // 起始时间打进上下文
		}
		if (lbResponse == null || !lbResponse.hasServer()) {
			return;
		}
		ServiceInstance serviceInstance = lbResponse.getServer();
		AtomicLong activeRequestsCounter = activeRequestsPerInstance.computeIfAbsent(serviceInstance, instance -> {
			AtomicLong createdCounter = new AtomicLong();
			Gauge.builder("loadbalancer.requests.active", () -> createdCounter)   // L97：每实例活跃数 Gauge
				...
			return createdCounter;
		});
		activeRequestsCounter.incrementAndGet();                     // L102
		...
	}
```

指标族：`loadbalancer.requests.active`（Gauge，每实例当前并发，L97）与同名 Timer/Counter（记录总调用次数与耗时，标签由 `stats/LoadBalancerTags.java` 组装：`method`、`uri`（优先取 URI 模板而非真实 path，防止标签爆炸，L92-105）、`outcome`、实例的 serviceId/instanceId/host/port 等）。`onComplete` 里相应 `decrement` 并记录 Timer。配置 `spring.cloud.loadbalancer.stats.include-path`（`LoadBalancerProperties.java:584`，默认 true）控制是否带 `uri` 标签。

## 7.3 缓存自动装配：Caffeine 有则用之，无则降级

**白话**：4.3 节说"缓存实现二选一"，裁判就是 `LoadBalancerCacheAutoConfiguration`。

【源码证据】`config/LoadBalancerCacheAutoConfiguration.java` 第 69-149 行（关键段）：

```java
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass({ CacheManager.class, CacheAutoConfiguration.class })
@AutoConfigureAfter(CacheAutoConfiguration.class)
@EnableConfigurationProperties(LoadBalancerCacheProperties.class)
@Conditional(LoadBalancerCacheAutoConfiguration.OnLoadBalancerCachingEnabledCondition.class)   // L73
public class LoadBalancerCacheAutoConfiguration {

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass({ Caffeine.class, CaffeineCacheManager.class })           // L77：优先分支
	protected static class CaffeineLoadBalancerCacheManagerConfiguration {

		@Bean(autowireCandidate = false)
		@ConditionalOnMissingBean
		LoadBalancerCacheManager caffeineLoadBalancerCacheManager(LoadBalancerCacheProperties cacheProperties) {
			return new CaffeineBasedLoadBalancerCacheManager(cacheProperties);     // L83
		}
	}

	@Configuration(proxyBeanMethods = false)
	@Conditional(OnCaffeineCacheMissingCondition.class)                           // L89：Caffeine 缺席时
	@ConditionalOnClass(ConcurrentMapWithTimedEviction.class)
	protected static class DefaultLoadBalancerCacheManagerConfiguration {

		@Bean(autowireCandidate = false)
		@ConditionalOnMissingBean
		LoadBalancerCacheManager defaultLoadBalancerCacheManager(LoadBalancerCacheProperties cacheProperties) {
			return new DefaultLoadBalancerCacheManager(cacheProperties);           // L96
		}

		@Bean
		LoadBalancerCaffeineWarnLogger caffeineWarnLogger() {                       // L100：降级告警
			return new LoadBalancerCaffeineWarnLogger();
		}
	}
```

总开关在 `OnLoadBalancerCachingEnabledCondition`（L124-140）：`spring.cloud.loadbalancer.enabled`（默认 true）**且** `spring.cloud.loadbalancer.cache.enabled`（默认 true）同时满足才装配。Caffeine 缺席时启动日志会打一行 WARN（`LoadBalancerCaffeineWarnLogger.afterPropertiesSet` L142-149）：**"正在使用默认缓存，建议引入 Caffeine 以获得更好的性能与可观测性"**——默认实现（Evictor 库）可用但功能有限（无 Caffeine 的统计与驱逐策略调优空间）。

## 7.4 一个考古现场：security 包的残留壳

`security/OAuth2LoadBalancerClientAutoConfiguration.java`（类声明 L33）值得点名：打开会发现**全部实现都被注释掉了**——只剩空壳配置类，注册条件也被注释（`@ConditionalOnProperty("spring.cloud.oauth2.loadbalanced.enabled")`）。它是 Ribbon 时代 `OAuth2RestTemplate` 集成的遗迹，OAuth2 资源服务器在 Boot 3+ 已有完全不同的接线方式。读源码时不要在这里浪费时间，但它是"框架考古地层"的一个有趣样本：5.0 时代仍在、功能已清空。

## 7.5 本章小结

- `LoadBalancerLifecycle` 是全框架唯一的调用级观测口子，三段回调 + 类型过滤 + `DISCARD` 语义；所有接入层（阻塞/响应式/网关）都完整驱动它。
- Micrometer 指标默认关闭（`spring.cloud.loadbalancer.stats.micrometer.enabled=true` 开启），标签设计对高基数做了防御（URI 模板优先）。
- 缓存自动装配是"可选依赖优雅降级"的教科书实现：Caffeine 优先、默认缓存兜底、降级打 WARN、双开关控制。

---

# 八、配置大全与定制实战

> 本章是工具章：先把属性表列全（带源码行号），再讲覆盖规则与四种定制姿势，最后收常见坑。

## 8.1 LoadBalancerProperties 全字段速查

配置前缀 `spring.cloud.loadbalancer.*`。载体类：`spring-cloud-commons/.../loadbalancer/LoadBalancerProperties.java`（类声明 L53）。

| 属性 | 默认值 | 行号 | 说明 |
|---|---|---|---|
| `hint.<serviceId或default>` | 空 map | L65 | 服务名 → hint 值，配合 HintBased 装饰器 |
| `hint-header-name` | `X-SC-LB-Hint` | L71 | hint 头名 |
| `call-get-with-request-on-delegates` | `true` | L94 | 是否把请求上下文传给装饰器链（关掉则过滤型装饰器退化） |
| `health-check.initial-delay` | `0` | L255 | 首次健康检查延迟 |
| `health-check.interval` | `25s` | L260 | 探测周期 + 单次探测超时 |
| `health-check.refetch-instances` | `false` | L286 | 是否周期重拉注册中心 |
| `health-check.refetch-instances-interval` | `25s` | L265 | 重拉周期 |
| `health-check.path.default` / `path.<serviceId>` | `/actuator/health` | L272 | 探测路径（服务级置空=跳过探测） |
| `health-check.port` | `null`（用实例端口） | L278 | 独立健康检查端口（management 端口场景） |
| `health-check.repeat-health-check` | `true` | L293 | 周期重复探测 |
| `health-check.update-results-list` | `true` | L301 | 结果逐个更新（增量）而非整批重发 |
| `retry.enabled` | `true` | L371 | 重试总开关（还需 classpath 有 spring-retry 才有阻塞重试链） |
| `retry.retry-on-all-operations` | `false` | L377 | 非 GET 也重试 |
| `retry.retry-on-all-exceptions` | `false` | L383 | 非 IOException 也重试 |
| `retry.max-retries-on-same-service-instance` | `0` | L388 | 同实例重试配额 |
| `retry.max-retries-on-next-service-instance` | `1` | L394 | 换实例重试配额 |
| `retry.retryable-status-codes` | 空 | L399 | 可重试状态码集合 |
| `retry.retryable-exceptions` | `IOException` 系 | L404 | 可重试异常集合 |
| `retry.backoff.enabled/min-backoff/max-backoff/jitter` | false/5ms/MAX/0.5 | L493-508 | 退避 |
| `retry.avoid-previous-instance` | `true` | （属性对象） | 重试时剔除上次失败实例（per-service 支持，见 8.2） |
| `sticky-session.instance-id-cookie-name` | `sc-lb-instance-id` | L202/207 | 粘滞 Cookie 名 |
| `sticky-session.add-service-instance-cookie` | `false` | L213 | 是否回写 Cookie |
| `subset.instance-id` / `subset.size` | 默认实例 ID / `100` | L552/557 | 子集路由 |
| `x-forwarded.enabled` | `false` | L238 | 转发 X-Forwarded-* 头 |
| `stats.include-path` | `true` | L584 | 指标是否带 uri 标签 |
| `api-version.required/default-version/header/query-parameter/path-segment/media-type-parameters/fallback-to-available-instances` | false/null/… | L601-633 | API 版本路由（5.0） |
| `configurations` | 未配置（=`default`） | （条件类用） | 装饰器链选择（4.10 表） |

缓存专属属性在前缀 `spring.cloud.loadbalancer.cache.*`（`cache/LoadBalancerCacheProperties.java:30`）：`enabled`（默认 true）、`ttl`（`35s`，L42）、`capacity`（`256`，L47）。统计开关在前缀 `spring.cloud.loadbalancer.stats.micrometer.enabled`（默认 false）。发现超时是 `spring.cloud.loadbalancer.service-discovery.timeout`（默认 30s）。区域是 `spring.cloud.loadbalancer.zone`。

## 8.2 全局与单服务：overlay 规则

**白话**：属性前缀有两层——全局 `spring.cloud.loadbalancer.*` 与单服务 `spring.cloud.loadbalancer.clients.<serviceId>.*`。读配置时"单服务覆盖全局"。

两个机制各管一段：

1. **普通属性**（超时、健康检查、重试参数……）：`LoadBalancerClientsProperties extends LoadBalancerProperties`（`LoadBalancerClientsProperties.java:35`）并持有 `Map<String, LoadBalancerProperties> clients`；读取方 `LoadBalancerClientFactory.getProperties(serviceId)`（`LoadBalancerClientFactory.java:84-98`）按"有专属用专属、没有用全局"返回。
2. **无法装进属性类的开关**（`configurations` 链选择、`retry.avoid-previous-instance` 这类条件求值场景）：由工具类 `LoadBalancerEnvironmentPropertyUtils` 直接查 Environment——先查 `spring.cloud.loadbalancer.clients.<serviceId>.<suffix>`（`getClientPropertyValue`），查不到再查 `spring.cloud.loadbalancer.<suffix>`（`getDefaultPropertyValue`）。`equalToForClientOrDefault` / `trueOrMissingForClientOrDefault` 的完整语义见源码（`support/LoadBalancerEnvironmentPropertyUtils.java:31-69`）。

**注意**：`clients.<serviceId>` 只有该服务的子容器在被创建后其专属 `LoadBalancerProperties` 才从 map 里绑定；全局属性随主容器绑定。给"还没出现过的服务"配单服务属性是合法的（第一次调用创建子容器时生效）。

## 8.3 定制四种姿势（最小可用示例）

**姿势一：换算法（3.4 已展示完整代码）**——`@LoadBalancerClient(name, configuration)` 放一个 `ReactorServiceInstanceLoadBalancer` bean。适合：接第三方算法、自研算法。

**姿势二：加一个装饰器（保住默认链，再包一层）**——放一个自定义 `ServiceInstanceListSupplier` bean，把框架装配好的 bean 当 delegate 包起来：

```java
public class MyFilterConfig {

    @Bean
    ServiceInstanceListSupplier myTagAwareSupplier(ConfigurableApplicationContext context) {
        // context 里拿到的是"当前子容器"，默认链 bean 已就绪
        ServiceInstanceListSupplier defaultSupplier = ServiceInstanceListSupplier.builder()
                .withDiscoveryClient().withCaching().build(context);   // 或直接注入默认 bean
        return new MyTagAwareServiceInstanceListSupplier(defaultSupplier);
    }
}
```

更细粒度的做法是用 `ServiceInstanceListSupplierBuilder.with(DelegateCreator)`（`ServiceInstanceListSupplierBuilder.java:371`）在链中插入任意一层。

**姿势三：全局默认**——`@LoadBalancerClients(defaultConfiguration = MyDefaultConfig.class)` 让所有服务的子容器都注册你的默认配置类（进 `default.` 前缀分支，`NamedContextFactory.registerBeans` L154-159）。典型用法：全局统一算法 + 全局统一装饰器，个别服务再用 `@LoadBalancerClient` 覆盖。

**姿势四：不改选择结果，改请求本身**——实现 `LoadBalancerRequestTransformer`（commons）注册成 bean：在 URI 重建后、请求发出前改头/改 Cookie。框架自己的粘滞 Cookie 回写（`LoadBalancerServiceInstanceCookieTransformer`）与 X-Forwarded（`XForwardedHeadersTransformer`，`core/XForwardedHeadersTransformer.java:34`，4.0 引入）就是走这个口子的。**它不参与选实例**，只是请求修饰器。

## 8.4 常见坑清单（生产向）

1. **"新实例上线了为什么没流量"**：实例列表缓存 TTL 35 秒（`LoadBalancerCacheProperties.java:42`）+ 注册中心同步延迟。急用则调小 `spring.cloud.loadbalancer.cache.ttl`，或按服务配（8.2）。
2. **"配置了 configurations=weighted 怎么没生效"**：① `configurations` 各值互斥，不能写 `zone-preference,weighted`；② 想组合只能自定义 `ServiceInstanceListSupplier` bean；③ 检查是否被 `spring.cloud.loadbalancer.clients.<serviceId>.configurations` 覆盖。
3. **"重试没生效"**：阻塞侧需要 classpath 有 `spring-retry`（starter 不带）；`retry.enabled` 默认 true 但 GET 才重试（`retry-on-all-operations` 默认 false）；换实例重试默认只 1 次（`max-retries-on-next-service-instance=1`）。另外 reactive 侧用的是 `RetryableLoadBalancerExchangeFilterFunction`，与 spring-retry 无关。
4. **"健康检查开了怎么实例没被剔除"**：健康检查链**没有缓存层**（4.9 末尾），但探测默认 25 秒一轮 + 单次探测超时也是 interval——探测间隔别配得比实例崩溃恢复还短；探测失败不会立刻生效到所有客户端（每客户端独立探测）。
5. **"@LoadBalanced 的 RestTemplate 直接报 'Request URI does not contain a valid hostname'"**：URL 必须写服务名（`http://user-service/...`），写 IP/域名反而没得选（`LoadBalancerInterceptor.java:53-54` 的 Assert）。反过来，**不该**被负载均衡的 RestTemplate 千万别加 `@LoadBalanced`。
6. **"网关返回 503 还是 404"**：无可用实例默认 503，`spring.cloud.gateway.loadbalancer.use-404=true` 改 404（`ReactiveLoadBalancerClientFilter.java:114`）。
7. **"zone 配了却全走跨机房"**：本机区域来自 `spring.cloud.loadbalancer.zone`，实例侧看 metadata 的 `zone` 键；两边键名/大小写都要对上（匹配用 `equalsIgnoreCase`，`ZonePreferenceServiceInstanceListSupplier.java:93`）。
8. **"Native/AOT 下子容器报错"**：自定义配置类避免 `proxyBeanMethods=true` 与运行期反射注册；确保配置类被 `@LoadBalancerClient` 显式声明（这样 5.4 的 AOT 初始化器才能为它生成代码）。
9. **"hint 加了但没过滤"**：三处对齐——请求头名（默认 `X-SC-LB-Hint`）与 `hint-header-name` 一致；实例 metadata 的键必须是 `hint`；且链上要有 `withHints()`（默认链没有，需 `configurations` 或自定义）。

## 8.5 本章小结

- 属性面很宽但结构清晰：`LoadBalancerProperties` 的嵌套静态类（HealthCheck/Retry/StickySession/Subset/ApiVersion/XForwarded/Stats）一一对应功能模块，全部支持 per-service 覆盖。
- 定制的"官方口子"优先级：换算法 bean > 加装饰器 bean > configurations 开关 > LoadBalancerRequestTransformer。都是 bean 装配层面的替换，不需要动任何框架代码。

---

# 九、贯通视图：一次 `lb://` 请求的一生

> 把前六章的部件装回同一台机器上：三条时间线，分别从"一次调用"、"一次缓存刷新"、"一个 bean 的装配"看整个框架。

## 9.1 时间线一：网关收到 `lb://user-service/api/users`（响应式全链）

```
① Gateway 路由匹配：RouteToRequestUrlFilter 把 uri: lb://user-service 的请求 URL
   写进 exchange 属性 GATEWAY_REQUEST_URL_ATTR
        │
② ReactiveLoadBalancerClientFilter（order 10150）登场
   检查 scheme == lb → serviceId = "user-service"
   （ReactiveLoadBalancerClientFilter.java:91,103）
        │
③ 装载请求上下文：RequestData(exchange 请求+属性) → RequestDataContext(…, hint)
   （L107-108；hint 取自 spring.cloud.loadbalancer.hint.*，2.2/4.7.3）
   lifecycle.onStart(lbRequest)（L165）
        │
④ clientFactory.getInstance("user-service") → 跨进 user-service 的子容器
   （NamedContextFactory.getContext 双检锁懒创建，第一次调用才开）→ RoundRobinLoadBalancer
        │
⑤ RoundRobinLoadBalancer.choose(lbRequest)
   → supplier.get(request).next()                      （RoundRobinLoadBalancer.java:84-85）
        │
⑥ 装饰器链执行（从外向里调用、从里向外返回）：
   [RetryAware]（若开启重试）→ [过滤装饰器：Hint/Zone/Sticky/Subset/Weighted/ApiVersion…]
   → [Caching：35s 缓存命中则止步于此]（CachingServiceInstanceListSupplier.java:63-68）
   → [DiscoveryClient：缓存未命中才穿透]（超时 30s、异常降级空列表，:68-75）
        │
⑦ 算法裁决：pos = position.incrementAndGet() & Integer.MAX_VALUE; 取模选人
   → DefaultResponse(instance)（:115-119）
   若实例有 SelectedInstanceCallback → 回传给装饰器（粘滞记忆）
        │
⑧ 回到过滤器：response.hasServer()?
   否 → DISCARD 回调 + 抛 NotFoundException（use-404 决定 404/503）（L111-114）
   是 → LoadBalancerUriTools.reconstructURI(http://user-service/api/users
        → http://10.0.3.17:8080/api/users)（L132；scheme 升级/端口/编码见 6.6）
        │
⑨ lifecycle.onStartRequest(lbRequest, response)（L139）→ chain.filter(exchange)
   后续过滤器用真实 URL 发起 HTTP 调用
        │
⑩ doOnError → lifecycle.onComplete(FAILED)（L142-145）
   doOnSuccess → lifecycle.onComplete(SUCCESS, ResponseData)（L146-151）
   [若 Micrometer stats 开启：Timer/Counter 记账，activeRequests 回落]
```

## 9.2 时间线二：业务代码里一行 RestTemplate 调用（阻塞全链）

`restTemplate.getForObject("http://user-service/api/users", ...)` 的旅程：

1. `@LoadBalanced` 标记让它在启动期就被塞进 `LoadBalancerInterceptor`（commons `LoadBalancerAutoConfiguration` + `RestTemplateCustomizer`）。
2. 拦截器取主机名当服务名（`LoadBalancerInterceptor.java:53`），构造 `HttpRequestLoadBalancerRequest`。
3. `BlockingLoadBalancerClient.execute`：`onStart` → `choose`（内部 `Mono.from(choose).block()`，L163）→ `onStartRequest` → `request.apply(instance)` 真正发出 → `onComplete`。
4. 若 classpath 有 spring-retry，拦截器会换成 `RetryLoadBalancerInterceptor`：套上 `RetryTemplate`，失败按策略同实例/换实例重试，重试轮次携带 `RetryableRequestContext`，装饰器链剔除上次失败实例。
5. 请求落地前同样经过 `LoadBalancerUriTools.reconstructURI`（由 `LoadBalancerRequest` 内部完成 host:port 替换）。

**两条时间线的对应关系**：网关走 ②-⑩ 全响应式；RestTemplate 走同样的 ④-⑦（子容器与链完全相同），只是头尾被 `block()` 压平。

## 9.3 时间线三：一份实例列表的 35 秒人生

```
T+0    首次调用 user-service：缓存未命中
       → DiscoveryClient.getInstances("user-service")（注册中心，超时 30s 兜底）
       → 写入缓存 key="user-service"，TTL 35s（CachingServiceInstanceListSupplier.java:76）
T+0~35s 后续调用全部命中缓存（无论哪个算法、哪个子容器内的装饰器在链上）
T+35s  缓存条目被 DefaultLoadBalancerCache 定时驱逐（DefaultLoadBalancerCache.java:138）
       下一次调用重新穿透到注册中心，写入新列表
       [若某实例已下线：新列表不含它，算法层自然选不中]
```

延伸：开启健康检查后，列表来源不再是"注册中心说什么就是什么"——`HealthCheckServiceInstanceListSupplier` 后台按 interval 主动探测，`replay(1)` 保证任何时刻的 `get()` 拿到最近一批探测结果（4.4）。**缓存的 TTL 管"列表保鲜"，健康检查的 interval 管"成员死活"，两者独立运行**。

## 9.4 从源码中提炼的四个设计模式视角

1. **装饰器模式**：`ServiceInstanceListSupplier` 九兄弟 + `DelegatingServiceInstanceListSupplier` 基类——每个能力一层，`Builder` 组装（第四章）。这是全框架最核心的模式。
2. **工厂 + 组合根**：`NamedContextFactory` 为每个 serviceId 动态开闭迷你容器，配置类按"说明书"（Specification）注入——配置隔离的组合根（第五章）。
3. **策略模式**：`ReactiveLoadBalancer`/`ReactorServiceInstanceLoadBalancer` 是算法策略接口，轮询/随机/Nacos 加权等都是可插拔策略（第三章）。
4. **观察者模式**：`LoadBalancerLifecycle` 三段回调 + Reactor 流的 `doOnSuccess`/`doOnError`——观测与业务彻底解耦（第七章）。

## 9.5 Spring Cloud LoadBalancer vs Netflix Ribbon 对照表

| 维度 | Netflix Ribbon（已退场） | Spring Cloud LoadBalancer（现任） |
|---|---|---|
| 定位 | Netflix OSS 全家桶组件，重且自带传输层 | 轻量库，只管"选地址"，纯抽象 |
| 核心抽象 | `ILoadBalancer` + `Server` + `IRule` | `ReactiveLoadBalancer` + `ServiceInstance` + `ServiceInstanceListSupplier` |
| 编程模型 | 阻塞为主 | 响应式内核（Reactor），阻塞壳桥接 |
| 实例缓存 | ZoneAware 轮换 + 定时刷新（`ServerListRefreshInterval`） | 装饰器链上的 TTL 缓存（35s） |
| 健康检查 | IPing 机制（可配） | `HealthCheckServiceInstanceListSupplier` 主动探测（可配） |
| 每服务配置 | `SpringClientFactory` 子容器 | `NamedContextFactory` 子容器（同源遗产，`NamedContextFactory.java:53-54` 自述） |
| 重试 | 自带 RetryHandler + spring-retry 桥 | spring-retry（阻塞）/ Reactor retryWhen（响应式）+ RetryAware 装饰器 |
| 灰度/路由 | 需自行扩展 IRule | Hint/Zone/Subset/API 版本内置装饰器 |
| 现状 | 2020.0 列车移除，仅维护 | 所有 Spring Cloud 消费方的默认实现 |

---

# 十、附录

## 10.1 关键接口速查表

| 接口/类 | 模块 | 一句话职责 | 深入章节 |
|---|---|---|---|
| `ReactiveLoadBalancer<T>` | commons | 选择器 SPI + Factory 门面 | 2.4 |
| `ReactorLoadBalancer<T>` | loadbalancer | Mono 化的选择器 | 2.4 |
| `ReactorServiceInstanceLoadBalancer` | loadbalancer | 选 `ServiceInstance` 的标记接口 | 2.4 |
| `RoundRobinLoadBalancer` / `RandomLoadBalancer` | loadbalancer | 内置算法 | 3.2/3.3 |
| `ServiceInstanceListSupplier` | loadbalancer | 实例列表供给 SPI | 4.1 |
| `DelegatingServiceInstanceListSupplier` | loadbalancer | 装饰器基类 | 4.1 |
| `DiscoveryClientServiceInstanceListSupplier` | loadbalancer | 注册中心接入口（永不抛错） | 4.2 |
| `CachingServiceInstanceListSupplier` | loadbalancer | TTL 缓存装饰器 | 4.3 |
| `HealthCheckServiceInstanceListSupplier` | loadbalancer | 主动健康探测 | 4.4 |
| `ZonePreferenceServiceInstanceListSupplier` | loadbalancer | 同区域优先 | 4.5 |
| `WeightedServiceInstanceListSupplier` + `LazyWeightedServiceInstanceList` | loadbalancer | 加权（惰性展开） | 4.6 |
| `RequestBasedStickySessionServiceInstanceListSupplier` | loadbalancer | Cookie 粘滞 | 4.7.1 |
| `SameInstancePreferenceServiceInstanceListSupplier` | loadbalancer | 进程内粘滞 | 4.7.2 |
| `HintBasedServiceInstanceListSupplier` | loadbalancer | hint 灰度过滤 | 4.7.3 |
| `SubsetServiceInstanceListSupplier` | loadbalancer | 一致性子集 | 4.8.1 |
| `RetryAwareServiceInstanceListSupplier` | loadbalancer | 重试剔除失败实例 | 4.8.2 |
| `Blocking/ReactiveApiVersionServiceInstanceListSupplier` | loadbalancer | API 版本路由 | 4.8.3 |
| `ServiceInstanceListSupplierBuilder` | loadbalancer | 装饰器链组装 | 4.9 |
| `LoadBalancerClientFactory` | loadbalancer | 每服务子容器工厂 | 5.1 |
| `NamedContextFactory` | spring-cloud-context | 子容器基建（Feign 遗产） | 5.2 |
| `@LoadBalancerClient(s)` + Registrar + Specification | loadbalancer | 配置注入四件套 | 5.3 |
| `LoadBalancerClient` / `BlockingLoadBalancerClient` | commons / loadbalancer | 阻塞客户端 SPI / 实现 | 6.1 |
| `LoadBalancerInterceptor` / `RetryLoadBalancerInterceptor` / `DeferringLoadBalancerInterceptor` | commons(+loadbalancer) | RestTemplate/RestClient 拦截器三兄弟 | 6.1-6.3 |
| `ReactorLoadBalancerExchangeFilterFunction` / `RetryableLoadBalancerExchangeFilterFunction` | commons | WebClient 过滤器两兄弟 | 6.4 |
| `ReactiveLoadBalancerClientFilter` | gateway 仓库 | `lb://` 全局过滤器 | 6.5 |
| `LoadBalancerUriTools` | commons | URI 重建工具 | 6.6 |
| `LoadBalancerLifecycle` | commons | 三段生命周期回调 | 7.1 |
| `MicrometerStatsLoadBalancerLifecycle` | loadbalancer | 官方指标回调 | 7.2 |
| `LoadBalancerProperties` / `LoadBalancerClientsProperties` | commons | 配置载体 / per-service 扩展 | 8.1/8.2 |
| `LoadBalancerCacheManager` / `DefaultLoadBalancerCache` | loadbalancer | 缓存标记接口 / 内置实现 | 4.3 |

## 10.2 初学者学习路线（动手向）

1. **跑通默认链**（0.5 天）：Eureka/Nacos（任选）+ 两个服务提供者实例 + 一个消费者；消费者引入 `spring-cloud-starter-loadbalancer`，用 `@LoadBalanced RestClient` 调 `http://provider/hello`；观察响应轮询切换。
2. **打开观测**（0.5 天）：加 micrometer + `spring.cloud.loadbalancer.stats.micrometer.enabled=true`，在 Grafana/Actuator 里看 `loadbalancer.requests.active` 与 Timer。
3. **体验缓存**（0.5 天）：杀掉一个提供者实例，观察最长 35 秒内仍可能被调用；把 `cache.ttl` 调成 `5s` 再对比。
4. **换算法 + 加装饰器**（1 天）：`@LoadBalancerClient` 换 `RandomLoadBalancer`；再配 `configurations=zone-preference`，给实例 metadata 打 `zone` 标签验证区域优先。
5. **开健康检查**（0.5 天）：`configurations=health-check`，手动 kill 实例但保留注册中心条目（如 Eureka 自我保护），观察探测剔除。
6. **读源码**（2 天）：按 10.3 清单顺序，对照本文行号读。
7. **进阶**（选修）：实现一个自定义 `ServiceInstanceListSupplier` 装饰器（如按 metadata 标签灰度）；或实现自定义 `ReactorServiceInstanceLoadBalancer`（如最少连接数——体会为什么它需要生命周期回调配合）。

## 10.3 源码阅读入口清单（30 个关键文件）

路径前缀：`C` = `spring-cloud-commons/src/main/java/org/springframework/cloud/client/loadbalancer/`，`L` = `spring-cloud-loadbalancer/src/main/java/org/springframework/cloud/loadbalancer/`，`X` = `spring-cloud-context/src/main/java/org/springframework/cloud/context/`，`G` = gateway 仓库 `spring-cloud-gateway-server-webflux/src/main/java/org/springframework/cloud/gateway/`。

| # | 文件 | 看什么 |
|---|---|---|
| 1 | `C`/`reactive/ReactiveLoadBalancer.java` | 核心 SPI 与 Factory |
| 2 | `L`/`core/ReactorLoadBalancer.java` | Mono 化收窄 |
| 3 | `C`/`Request.java`、`DefaultRequest.java` | 请求最小模型 |
| 4 | `C`/`RequestData.java`、`RequestDataContext.java` | 请求快照与上下文链 |
| 5 | `C`/`RetryableRequestContext.java`、`HintRequestContext.java` | 上下文继承链两端 |
| 6 | `C`/`Response.java`、`DefaultResponse.java`、`EmptyResponse.java` | 响应家族 |
| 7 | `C`/`CompletionContext.java` | SUCCESS/FAILED/DISCARD |
| 8 | `L`/`core/RoundRobinLoadBalancer.java` | 默认算法全解 |
| 9 | `L`/`core/RandomLoadBalancer.java` | 第二算法 |
| 10 | `L`/`core/ServiceInstanceListSupplier.java` | 列表 SPI |
| 11 | `L`/`core/DelegatingServiceInstanceListSupplier.java` | 装饰器基类 |
| 12 | `L`/`core/DiscoveryClientServiceInstanceListSupplier.java` | 注册中心接入与兜底 |
| 13 | `L`/`core/CachingServiceInstanceListSupplier.java` | 缓存装饰器 |
| 14 | `L`/`cache/DefaultLoadBalancerCache.java`、`LoadBalancerCacheProperties.java` | TTL/容量与 Evictor |
| 15 | `L`/`core/HealthCheckServiceInstanceListSupplier.java` | 主动探测 |
| 16 | `L`/`core/ZonePreferenceServiceInstanceListSupplier.java` | 区域过滤 |
| 17 | `L`/`core/WeightedServiceInstanceListSupplier.java`、`LazyWeightedServiceInstanceList.java` | 加权与惰性展开 |
| 18 | `L`/`core/HintBasedServiceInstanceListSupplier.java` | hint 灰度 |
| 19 | `L`/`core/RequestBasedStickySessionServiceInstanceListSupplier.java` + `LoadBalancerServiceInstanceCookieTransformer.java` | 粘滞读写两端 |
| 20 | `L`/`core/SameInstancePreferenceServiceInstanceListSupplier.java` + `SelectedInstanceCallback.java` | 进程内粘滞闭环 |
| 21 | `L`/`core/SubsetServiceInstanceListSupplier.java` | 子集分桶 |
| 22 | `L`/`core/RetryAwareServiceInstanceListSupplier.java` | 重试剔除 |
| 23 | `L`/`core/ServiceInstanceListSupplierBuilder.java` | 链组装 |
| 24 | `L`/`annotation/LoadBalancerClientConfiguration.java` | 默认算法 + 默认链 + 条件开关 |
| 25 | `X`/`named/NamedContextFactory.java` | 子容器创建/销毁 |
| 26 | `L`/`support/LoadBalancerClientFactory.java` | 工厂门面与配置 overlay |
| 27 | `L`/`annotation/LoadBalancerClientConfigurationRegistrar.java` | 注解 → 说明书 |
| 28 | `L`/`blocking/client/BlockingLoadBalancerClient.java` | 阻塞桥接 |
| 29 | `C`/`reactive/ReactorLoadBalancerExchangeFilterFunction.java` | 响应式全链 |
| 30 | `C`/`LoadBalancerUriTools.java`、`G`/`filter/ReactiveLoadBalancerClientFilter.java` | URI 重建 + 网关入口 |

## 结语

Spring Cloud LoadBalancer 的全部源码只有 60 个文件、核心不到 3000 行，却撑起了整个 Spring Cloud 生态的客户端负载均衡。它的结构可以压缩成三句话：

1. **两层解耦**：算法层（选哪台）与来源层（有哪些可选）各是一条独立抽象，靠 `choose()` 一个方法相遇；
2. **一条链**：实例列表从注册中心流出，被缓存、健康检查、区域、权重、粘滞、子集等装饰器逐层加工，每个装饰器独立可插拔、失败自动回退；
3. **每服务一容器**：`NamedContextFactory` 把配置隔离做成了物理边界，全局默认、按服务覆盖、AOT 预生成三档齐备。

理解了这三句话，再看 Gateway 的 503、Feign 的轮询切换、35 秒缓存窗口、hint 灰度不生效——这些"使用中的现象"都能瞬间定位到对应源码行。这也是本系列文档的目的：**把框架从"配置项的黑盒"还原为"读得懂的机器"**。
