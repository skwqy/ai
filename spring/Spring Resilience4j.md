# Spring Resilience4j 深度源码解析（写给初学者的容错全景）

> **本文基于的源码**（全部为本地克隆、逐行读取的快照）：
> - Resilience4j 本体：`D:\code\3rd\resilience4j`，HEAD `b7c00698`（即 `v2.4.0-47-gb7c00698`，v2.4.0 之后第 47 个提交，最新发布 tag **v2.4.0**，提交于 2026-03-14）。文中【源码证据】的文件路径与行号均为对该快照实际读取所得。
> - Spring 集成层：同仓库的 `resilience4j-spring6` / `resilience4j-spring-boot3` / `resilience4j-spring-boot4` / `resilience4j-framework-common` / `resilience4j-annotations` 模块；Spring Cloud 一侧为 `D:\code\3rd\spring-cloud-circuitbreaker`（main 分支，5.1.0-SNAPSHOT）。
> - 对照组：`D:\code\3rd\sentinel`（tag 1.8.10，发布于 2026-05-21）与 `D:\code\3rd\spring-cloud-alibaba`（2026.0.x 分支，2026.0.0.0-SNAPSHOT）；以及 `D:\code\3rd\spring-boot`（4.1.x 分支，4.1.2-SNAPSHOT，HEAD 2026-10-01）用于核实"Spring 官方到底有没有 Resilience4j 集成"。同系列已有《Spring Cloud Alibaba Sentinel.md》（Sentinel 全量解析）与《Spring Cloud CircuitBreaker.md》（抽象层全量解析），本文第四章与 3.9 节是服务于选型的对比视角浓缩与版本考古补充。
>
> **版本取舍说明**：Resilience4j 的内核 API（五大容错器、装饰器、注册表）自 2.0 起高度稳定，本文内容对使用 `resilience4j-spring-boot3`（Spring Boot 3.x）的读者同样适用；1.x → 2.x 的演进对比见 1.6 节。所有"某特性属于哪个版本"的结论均经本地 git 标签（v1.7.1 / v2.0.0 / v2.1.0 / v2.2.0 / v2.3.0 / v2.4.0）逐项实证。
>
> **阅读约定**：每章"先白话、后源码"——先用一两句人话讲清"这是什么、为什么需要它"，再给出源码证据链（`模块/src/main/java/.../类名.java` + 方法名 + 行号 + 代码片段）。行号只对该快照精确，读者用 IDEA 打开源码按类名 + 方法名定位即可。

## 如何读这份文档

如果你是容错/稳定性领域的新人，推荐两遍读法：

- **第一遍（建立地图，1~2 小时）**：只读第一章（总览）每节的开头白话段、第二章每小节的标题和"本章小结"、第四章 4.6 的对比总表、第五章（选型建议）。目标是能回答：Resilience4j 有哪五个容错器？熔断器的三态如何流转？为什么 Spring Boot 4 上的集成构件叫 `resilience4j-spring-boot4` 而不是 Spring 官方 starter？新项目到底选 Resilience4j 还是 Sentinel？
- **第二遍（深入源码）**：对照每一章的【源码证据】打开源码逐行读。顺序建议：第二章（CircuitBreaker 状态机是重中之重）→ 第三章（切面顺序与配置合并，实践中最容易踩坑）→ 第四章（Sentinel 对照，换一个视角理解"容错"）→ 附录速查表随用随查。

---

# 一、总览：Resilience4j 的定位、设计哲学与整体架构

## 1.1 一句话定位

**Resilience4j 是一个"为函数式编程设计的轻量级容错库"**（官方 README 自述 "a lightweight fault tolerance library designed for functional programming"）：它把熔断（CircuitBreaker）、限流（RateLimiter）、舱壁隔离（Bulkhead）、重试（Retry）、超时（TimeLimiter）做成五个**彼此正交、可独立实例化**的容错器对象，再用**高阶函数装饰器**把它们像洋葱一样包住任意一段调用（`Supplier` / `Callable` / `CompletionStage` / Reactor `Mono`/`Flux`）。它自己不知道 Spring 的存在——**"Spring Resilience4j" 并不是 Spring 官方的某个子项目**，而是两层集成物的合称：

1. **Resilience4j 项目自己维护的 Spring 集成**：`resilience4j-annotations`（注解定义）+ `resilience4j-spring6`（AOP 切面与装配）+ `resilience4j-framework-common`（配置合并公共件）+ `resilience4j-spring-boot3` / `resilience4j-spring-boot4`（Boot starter 与自动配置）。本文第三章的主角。
2. **Spring Cloud 官方维护的编程式门面**：`spring-cloud-circuitbreaker-resilience4j`，把 Resilience4j 适配成 `CircuitBreakerFactory` 抽象，是 OpenFeign/RestClient 熔断能力的最终落点。见 3.10 节。

一个必须先钉死的事实（写作时用 `git grep -il "resilience4j"` 对 spring-boot 4.1.x 分支全树扫描，**零命中**）：**Spring Boot 官方至今没有任何 Resilience4j starter 或自动配置**。Spring Framework 7.0 在 spring-core 里新增了内建韧性注解（`org.springframework.resilience` 包，`@Retryable`/`@ConcurrencyLimit`），但它只覆盖"重试 + 并发上限"两个场景，且 Boot 4.1.x 同样没有为它提供自动装配。所以"在 Spring 里用 Resilience4j"，用的永远是 Resilience4j 项目自己的 starter。这一事实链在 3.11 节展开。

## 1.2 设计哲学：读源码前先记住五句话

1. **装饰器而非拦截器**。库内核是纯 Java 的高阶函数组合：`CircuitBreaker.decorateSupplier(cb, supplier)` 返回一个新 Supplier，`Decorators.ofSupplier(...).withCircuitBreaker(...).withRetry(...)` 链式叠加。AOP 切面、Feign 适配器都只是"把方法调用翻译成一次被装饰的调用"的壳。理解了这一点，整个库的源码就只剩两部分：容错器内部状态机 + 装饰组合。
2. **每个容错器是一个小对象，实例之间零共享**。`CircuitBreakerRegistry` 管理一堆按名字索引的 `CircuitBreaker` 实例，每个实例有自己的滑动窗口、自己的状态机、自己的事件流——没有全局共享的统计节点。这与 Sentinel（同一资源全局共享一个 `ClusterNode`，见 4.1 节）形成根本分野。
3. **零成本事件与零依赖内核**。`resilience4j-core` 的运行时依赖为 0（v2.0.0 起连 Vavr 都移除了，仅保留 `resilience4j-vavr` 适配模块）；事件发布前先查 `EventProcessor.hasConsumers()`，没人监听就**连事件对象都不构造**（`EventProcessor.java:33-35`、2.3.8 节）。
4. **阻塞与响应式是一等公民的两套 API**。每个容错器同时提供 `decorateSupplier`（阻塞）与 Reactor `XxxOperator`（订阅级保护）；Spring 注解切面识别 `Mono`/`Flux` 返回值后自动切换到 Operator 路径（3.8 节）。
5. **尽量不开后台线程**。熔断 OPEN→HALF_OPEN 默认**惰性判定**（每个被拒调用自己检查时钟，2.3.4 节），AtomicRateLimiter 的配额刷新**完全靠时间戳推导**（2.5 节）——只有显式开启 `automaticTransitionFromOpenToHalfOpenEnabled` 才会借用一个共享调度器。整库的心智模型是"调用线程上同步完成一切判定"。

## 1.3 模块分层全景

Resilience4j 是多模块 Gradle 工程，`settings.gradle:4-31` 实测声明 29 个模块（外加 `resilience4j-test` 测试夹具）。按"用户感知"分层如下：

```
┌────────────────────── 框架集成层（各技术各一个模块，互不依赖）──────────────────────┐
│  spring-boot4（Boot 4 starter，2.4.0 新增，包根 io.github.resilience4j.springboot.*）│
│  spring-boot3（Boot 3 starter，包根 io.github.resilience4j.springboot3.*）           │
│  spring6（AOP 切面 + Registry 装配，Boot3/Boot4 共同的地基）                          │
│  framework-common（配置属性合并、fallback 解析、切面 order 公共件）                    │
│  annotations（@CircuitBreaker/@Retry/@RateLimiter/@Bulkhead/@TimeLimiter/@Timer）    │
│  micronaut / micronaut-annotation（AOT 编译期 AOP）   feign（Feign 客户端装饰）       │
├──────────────────────────────── 观测层 ─────────────────────────────────────────────┤
│  micrometer（TaggedCircuitBreakerMetrics 等 Publisher + @Timer）                      │
│  metrics（Dropwizard 遗产，无 Spring 自动配置）                                       │
│  consumer（CircularEventConsumer/EventConsumerRegistry → actuator 事件端点的数据源）  │
├──────────────────────────────── 响应式层 ────────────────────────────────────────────┤
│  reactor（5 个 Operator）   rxjava2 / rxjava3   kotlin（Flow/协程扩展）   vavr（适配）│
├──────────────────────────────── 五大容错器 ──────────────────────────────────────────┤
│  circuitbreaker   ratelimiter   bulkhead   retry   timelimiter   cache（JSR-107）    │
│  hedge（对冲请求，2.0.0 新增）                                                        │
├──────────────────────────────── 地基 ────────────────────────────────────────────────┤
│  core（EventProcessor、IntervalFunction、Registry、ContextPropagator、                │
│        metrics/ 滑动窗口实现——2.x 起从 circuitbreaker 模块提升上来的公共统计内核）      │
│  circularbuffer（环形缓冲，consumer 的唯一生产用户）                                    │
│  commons-configuration（用 Apache Commons-Configuration 文件引导注册表）               │
│  all（Decorators 门面）   bom（约束集合）                                              │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

这张图本身就是一份架构说明：**五大容错器 + core 是不能拆的内核**；reactor/rxjava/kotlin 是"同一种保护在另一种编程范式下的表达"；观测层的 micrometer 是 Spring 集成的默认指标出口；框架集成层每个框架一个模块，boot3 与 boot4 **共同依赖 spring6 这一层切面**（3.1 节实证）。

## 1.4 模块依赖图（以各模块 build.gradle 实证）

| 模块 | 直接依赖（摘自各自 build.gradle） |
|---|---|
| resilience4j-core | **无任何运行时依赖**（仅测试依赖 mock clock/junit-params/lincheck） |
| circuitbreaker / ratelimiter / bulkhead / retry / timelimiter | api(core)（vavr 一律 testImplementation） |
| cache | api(core) + api(jcache) |
| circularbuffer | 无依赖 |
| consumer | implementation(circularbuffer, core) |
| reactor | api(reactor-core) + implementation(五大容错器 + micrometer) |
| rxjava2 / rxjava3 | api(rxjava2/3) + implementation(五大容错器) |
| kotlin | implementation(kotlin-stdlib, kotlinx-coroutines) + compileOnly(五大容错器) |
| vavr | api(vavr) + implementation(五大容错器) |
| micrometer | api(micrometer-core, micrometer-observation) + implementation(五大容错器) |
| metrics | compileOnly(五大容错器) + metrics-core |
| annotations | compileOnly(circuitbreaker, ratelimiter)（注解模块只带注解定义，不带实现） |
| framework-common | api(五大容错器) |
| spring6 | api(annotations, consumer, framework-common) + compileOnly(spring-context/core/aspectj/reactor/rxjava2/3) |
| spring-boot3 | api(spring6) + implementation(micrometer) + compileOnly(Boot 3.2.0 的 actuator/aop/webflux) |
| spring-boot4 | api(spring6) + implementation(spring-boot4-autoconfigure, spring7-context, spring7-core) + compileOnly(Boot 4 的 actuator/aspectj/health/jackson/webflux、Spring Cloud 5 的 context/openfeign) |
| all | api(五大容错器 + cache + micrometer) |
| hedge | api(core) |

三个值得记住的结论：

- **Vavr 已经不是运行时依赖**（v2.0.0 "Removed Vavr as a dependency"，RELEASENOTES.adoc v2.0.0 节）：用 `grep -l "io.vavr" */build.gradle` 扫五大容错器与 core 的构建文件零命中，vavr 只出现在 testImplementation 和 `resilience4j-vavr` 适配模块里。网上大量"Resilience4j 依赖 Vavr"的旧教程说法已过时。
- **circularbuffer 只有一个生产用户**：consumer 模块的 `ConcurrentCircularFifoBuffer`（事件环形缓冲）。历史上著名的 `RingBitSet`（环形位图滑动窗口）已随 #1395 性能重构移除（见 2.4 节考古）。
- **boot3/boot4 都构建在 spring6 之上**：`resilience4j-spring-boot4/build.gradle:2-22` 只有 `api(project(":resilience4j-spring6"))` + Boot 4/Spring 7 的适配依赖，版本目录（`gradle/libs.versions.toml:5,9-11`）显示 spring6 基线 6.1.1、spring7 基线 7.0.2、Boot 4 基线 4.0.0——**没有为 Spring Framework 7 单独写一套切面**，切面层是复用的（3.9 节展开这对迁移的含义）。

## 1.5 关键问题 → Resilience4j 方案映射（全文导览）

| 稳定性领域的关键问题 | Resilience4j 的方案 | 详见 |
|---|---|---|
| 下游抖动，失败的调用还在持续消耗自身资源 | CircuitBreaker 状态机：失败率/慢调用率双阈值熔断，OPEN 后快速失败 | 2.3 |
| 慢调用（不抛异常但耗时）拖垮线程池 | slowCallRateThreshold + slowCallDurationThreshold（慢调用也算失败倾向） | 2.3.3 |
| 突发流量打爆下游或自身 | RateLimiter：固定窗口周期的令牌配额（AtomicRateLimiter） | 2.5 |
| 一个依赖的并发失控拖垮整个进程 | Bulkhead：信号量并发上限或独立线程池隔离 | 2.6 |
| 瞬时故障（网络抖动、超时） | Retry：次数 + 退避间隔函数（固定/指数/随机化） | 2.7 |
| 对端长时间无响应 | TimeLimiter：Future/CompletionStage 超时 | 2.8 |
| 五种手段要叠加使用 | Decorators 显式包裹顺序；Spring 注解由切面 order 决定嵌套 | 2.9、3.3 |
| 运维要看熔断状态、强制开/关 | Actuator 端点（读状态 + POST 强制开/关/停）+ Micrometer 指标 | 3.6、3.7 |
| 声明式、少写样板代码 | @CircuitBreaker 等注解 + starter 自动配置 + fallbackMethod | 3.2 |
| Feign / RestClient 调用的熔断 | Spring Cloud CircuitBreaker 抽象 + Resilience4j 实现 | 3.10 |
| 只是想要"失败重试几次" | Spring Framework 7 内建 @Retryable（无需引入 Resilience4j） | 3.11 |
| 需要控制台、动态规则推送、系统自适应保护、热点参数限流 | Resilience4j **没有**——这是 Sentinel 的领地 | 第四章 |
| Spring Boot 4.1.1 新应用怎么选型 | 推荐 Resilience4j（r4j-spring-boot4 2.4.0 起官方适配 Boot 4） | 第五章 |

## 1.6 版本演进：1.x → 2.x，以及 Spring 集成矩阵

写作时（2026 年 10 月）的版本格局：Resilience4j 最新 GA 为 **v2.4.0**（2026-03-14），此后主线又合入 47 个提交（JDK 21 基线 + 虚拟线程工厂 + 非阻塞 Bulkhead，预计进入 2.4.1/2.5.0）；Spring Boot 最新线为 4.1.x（4.1.1 于 2026-08-20 发布）。本节所有结论经双重验证：① 本地仓库 git 标签逐项实证（`git show <tag>:<file>` / `git log -S`）；② GA 日期取自 tag 的 git 提交时间。

### 1.6.1 Resilience4j 本体时间线

| 版本 | GA 时间 | 关键变化（均实证） |
|---|---|---|
| 0.3.0（前史） | 2017~2018 | 熔断器以 **RingBitSet 环形位缓冲**统计失败率（后于 2.0 周期被 #1395 重构移除，见 2.4 节） |
| v1.0.0 | 2019-09-15 | 1.x 起点（Java 8，`git show v1.0.0:build.gradle` 65-66 行 `sourceCompatibility = "1.8"`） |
| v1.7.1 | 2021-06-25 | 1.x 收官（Java 8） |
| v2.0.0 | 2022-11-21 | **Java 8 → 17**；**移除 Vavr 运行时依赖**（新增 resilience4j-vavr 适配模块）；新增 **hedge** 对冲模块；砍掉 vertx/retrofit/ratpack/prometheus 模块 |
| v2.1.0 | 2023-06-29 | 新增 **resilience4j-spring6 + resilience4j-spring-boot3**（Boot 3 支持） |
| v2.2.0 | 2023-12-17 | 新增 micronaut-annotation、commons-configuration；Retry 负间隔 = 停止重试（#1565）；修 CB OpenState StackOverflow（#2038） |
| v2.3.0 | 2025-01-03 | CircuitBreakerConfig 支持自定义 Clock；`synchronized` → `ReentrantLock`（避免虚拟线程 pinning，#2232）；**无锁滑动窗口选项**（#2241）；注解支持 `permits`（#2121） |
| v2.4.0 | 2026-03-14 | **新增 resilience4j-spring-boot4**（代码于 2025-12-28 f61f9aa1 "Add support for Spring Boot 4 / Spring Cloud 5 (#2384)" 落地），同时移除 spring / spring-boot2 / spring-cloud2 老模块；**启动自检 Verifier**（装错 starter 快速失败）；HALF_OPEN 新增 `transitionToStateAfterWaitDuration`（#2285）；`ignoreExceptionsPrecedenceEnabled`；初始状态可配（initialState） |
| v2.4.0..HEAD | 2026-03~10 | **JDK 基线 17 → 21**（`build.gradle:92-93`）+ Bulkhead 虚拟线程工厂（#2335）；**非阻塞 Bulkhead** `acquirePermissionAsync()`（#1592）；JUnit 4 → JUnit 6 迁移；熔断谓词抛异常时的 permit 泄漏修复（#2488） |

Java 基线有一个容易搞错的"之"字形：v2.3.0 开发期内曾把 17 提到 21 又回退（`ab7dca4b` bump、`e9aedeee` revert #2415），**v2.4.0 发布时仍是 17**，v2.4.0 之后的提交才真正落地 21（`2025c1d1` + 虚拟线程 `b999ae19`）。

### 1.6.2 Spring 集成矩阵（选型的核心事实）

| 构件 | 维护方 | 适配的 Spring Boot | 构建基线（实证） | 启动自检 |
|---|---|---|---|---|
| resilience4j-spring-boot2 | Resilience4j | 2.x | —（v2.4.0 起删除） | 无 |
| resilience4j-spring-boot3 | Resilience4j | 3.x | Boot 3.2.0（`gradle/libs.versions.toml:6`） | 2.4.0 起：要求 Boot 主版本==3 且 !=4（`SpringBoot3Verifier.java:12-19`） |
| **resilience4j-spring-boot4** | Resilience4j | **4.x** | **Boot 4.0.0 / Framework 7.0.2 / Spring Cloud 5.0.0**（`libs.versions.toml:9-11`） | 要求 Boot 主版本==4（`SpringBoot4Verifier.java:10-17`），4.1.x 通过 |
| spring-cloud-circuitbreaker-resilience4j | Spring Cloud | 2025.1 版本线（5.0.x，Boot 4.0/4.1） | **依赖 r4j-spring-boot3 2.3.0**（v5.0.3 pom 实测，BOM `resilience4j.version=2.3.0`） | 无（借用 r4j 自检） |
| 同上（main，5.1.0-SNAPSHOT，面向 2026.0 版本线） | Spring Cloud | Boot 4.x | **依赖 r4j-spring-boot4 2.4.0**（main pom:35-51 实测；BOM 显式管理该构件，因 r4j BOM 遗漏，见 resilience4j#2427） | 同上 |

两个容易踩坑的细节（本文实证过，很多资料不会告诉你）：

- **启动自检 Verifier 是 2.4.0 才引入的**：`git show v2.3.0:.../verifier/autoconfigure/SpringBoot3Verifier.java` 返回文件不存在，该机制随 f61f9aa1（Boot 4 支持）一起落地。因此早期 sc-circuitbreaker 5.0.x 拉着 r4j-spring-boot3 2.3.0 在 Boot 4 上不会"启动报版本不匹配"，而是**部分 Boot 3 专属自动配置（健康、指标排序等引用旧包名的类）被条件装配静默跳过**——能跑，但观测面残缺。
- **Spring 官方（spring-boot 仓库）零参与**：4.1.x 分支 128 个模块中无任何 resil/retry/circuit 模块，`git grep -il "resilience4j"` 与 `git grep "EnableResilientMethods\|@Retryable"` 均零命中。

### 1.6.3 三个被广泛搞错的技术点（本文已被 git 标签实证修正）

1. **"CircuitBreaker 默认记录 IOException 和 RuntimeException"是错的**。源码默认谓词是 `recordExceptionPredicate = throwable -> true`（`CircuitBreakerConfig.java:56`）——**默认记录一切异常**；`recordExceptions(Class...)`/`ignoreExceptions(Class...)` 是在其上叠加的按类型过滤（2.3.6 节）。
2. **"minimumNumberOfCalls 在时间窗口模式下默认 50"是错的**。源码只有一条规则：COUNT_BASED 下 `min(minimumNumberOfCalls, slidingWindowSize)` 钳制（`CircuitBreakerConfig.java:659-663`），TIME_BASED 下没有独立的 50 默认值。
3. **"OPEN 状态由后台定时器自动转 HALF_OPEN"是错的**。默认恰好相反：没有调度器，靠每个被拒调用在 `tryAcquirePermission` 里惰性检查时钟（2.3.4 节）；`automaticTransitionFromOpenToHalfOpenEnabled=true` 才借用共享调度器。

## 1.7 全文章节地图

- **第二章 核心内核（五大容错器）**：resilience4j-core 地基 → CircuitBreaker 无锁状态机（重点）→ 滑动窗口考古 → RateLimiter 周期数学 → Bulkhead 两副面孔 → Retry/TimeLimiter → Decorators 与 Reactor 操作符 → hedge 速览。
- **第三章 Spring 集成层**：六个构件的分工 → 注解切面与 fallback → 切面顺序陷阱（重点）→ 配置合并算法 → Actuator/Micrometer → 响应式 → spring-boot4 模块解剖 → Spring Cloud CircuitBreaker → Framework 7 内建韧性的关系。
- **第四章 Sentinel 对照解析**：槽位链 vs 装饰器、流控/熔断逐项映射、Sentinel 独有的三张牌、维度对比总表。
- **第五章 选型建议**：Spring Boot 4.1.1 新应用的三条技术路线与推荐结论、落地配置、何时仍应选 Sentinel。
- **第六章 附录**：关键类速查、五大容错器默认值总表、陷阱速查。

---

# 二、核心内核：五大容错器（resilience4j-core + 各技术模块）

> 本章对应源码：`D:\code\3rd\resilience4j` 下 `resilience4j-core`、`resilience4j-circuitbreaker`、`resilience4j-ratelimiter`、`resilience4j-bulkhead`、`resilience4j-retry`、`resilience4j-timelimiter`、`resilience4j-all`、`resilience4j-reactor`、`resilience4j-hedge` 模块。以下所有【源码证据】中的行号均为实际读取 HEAD（v2.4.0-47）源码所得。

## 2.1 历史背景：Hystrix 之死与 Resilience4j 的答案

（本小节为历史背景综述，基于公开史料；随后的源码证据将验证 Resilience4j 确实沿这些方向演进。）

2012 年 Netflix 开源 Hystrix，用"命令模式 + 线程池隔离 + 熔断器"定义了 Java 界的服务容错范式，Spring Cloud Netflix 一度将其作为默认熔断方案。但 Hystrix 的线程池隔离成本高（每依赖一个线程池）、API 侵入（继承 `HystrixCommand`）、且 2018 年 Netflix 宣布进入维护模式。社区由此分化出两条路线：一是阿里 Sentinel（插件式埋点 + 控制台治理，见第四章）；二是 2017 年前后 Robert Winkler 发起的 Resilience4j——**吸取 Hystrix 的教训，但把实现彻底翻转**：不继承任何基类、不强制线程池、装饰器可自由组合、依赖极轻。2020 年 Spring Cloud 官方推出 `spring-cloud-circuitbreaker` 抽象，Resilience4j 成为其参考实现，Hystrix 支持进入废弃轨道。

这套历史承诺在源码中处处可见：`resilience4j-core` 零运行时依赖（1.4 节）、五大容错器全是普通对象、连"自动转半开"这种看似需要定时器的功能都默认不开后台线程（2.3.4 节）。

## 2.2 resilience4j-core：一切容错器的四块地基

**先说白话**：core 模块不实现任何容错语义，它提供的是"所有容错器都需要的东西"——事件总线（带零成本短路）、退避间隔函数、注册表骨架、跨线程上下文传播，以及 2.x 时代从 circuitbreaker 模块提升上来的**滑动窗口统计内核**。

### 2.2.1 EventProcessor：零成本事件总线

【源码证据】`resilience4j-core/src/main/java/io/github/resilience4j/core/EventProcessor.java:33-35`：消费者存放在 `CopyOnWriteArraySet`，对外暴露 `hasConsumers()`。五大容错器的每个事件发布点都先过这道闸：

```java
// CircuitBreakerStateMachine.java:387-394（节选）
private <E extends CircuitBreakerEvent> void publishEventIfHasConsumer(
        Supplier<E> eventSupplier) {
    if (eventProcessor.hasConsumers()) {
        LOG.debug("Event {} published: {}", eventSupplier.get().getEventType(), ...);
    } else {
        LOG.debug("No Consumers: Event {} not published", ...);
    }
}
```

没有消费者时**连事件对象都不构造**——生产环境没人订阅事件时，事件系统的开销是一个布尔读。这与"熔断热路径上不能有多余分配"的性能目标一致。

### 2.2.2 IntervalFunction：重试与熔断共用的退避函数

【源码证据】`resilience4j-core/src/main/java/io/github/resilience4j/core/IntervalFunction.java:18-20`：`DEFAULT_INITIAL_INTERVAL = 500`（毫秒）、`DEFAULT_MULTIPLIER = 1.5`、`DEFAULT_RANDOMIZATION_FACTOR = 0.5`。工厂方法族：`of(intervalMillis)` 固定间隔；`ofExponentialBackoff(initial, multiplier)` 即 `x -> (long)(x * multiplier)`（~120-122 行）；`ofExponentialRandomBackoff(initial, multiplier, randomizationFactor)` 在指数结果上再乘 `[1−f, 1+f]` 随机因子（防惊群）；随机化族的实际命名是 **`ofRandomized...`**（不是某些教程写的 `ofRandomWait`，82-97 行）。这个函数同时被 Retry 的重试间隔（2.7 节）和 CircuitBreaker 的 OPEN 等待时间（2.3.4 节）复用——**一次实现，两处语义**，是理解 2.4.0"熔断指数退避开闸"特性的钥匙。

### 2.2.3 Registry 体系与 ContextPropagator

每个容错器都有 `XxxRegistry` 接口 + `InMemoryXxxRegistry` 实现（如 `resilience4j-circuitbreaker/.../internal/InMemoryCircuitBreakerRegistry.java`），统一由 core 的 `Registry`/`AbstractRegistry` + `InMemoryRegistryStore` 支撑，构造时接受 `(configs, 事件复合消费者, 全局 tags)` 三件套——这正是第三章 Spring 配置绑定（`configs`/`instances` 两级 YAML）的落点。

`ContextPropagator<T>`（`resilience4j-core/.../ContextPropagator.java:23-60`）定义 `retrieve()/copy()/propagate()` 三方法，供 FixedThreadPoolBulkhead 把 ThreadLocal 带到隔离线程。它的 javadoc（26-32 行）明确警告虚拟线程 pinning 风险，并建议 Java 21+ 用户改用 Scoped Values——这是 2.4.0 之后 JDK 21 基线 + 虚拟线程工厂（`b999ae19`）演进方向的源头。

### 2.2.4 metrics 包：滑动窗口公共内核

【源码证据】`resilience4j-core/src/main/java/io/github/resilience4j/core/metrics/`：核心抽象 `Metrics` 接口 + **四值结果枚举 `Outcome { SUCCESS, ERROR, SLOW_SUCCESS, SLOW_ERROR }`**（`Metrics.java:41-43`）。"慢"与"失败"在**记录那一刻**就被分类，而不是聚合时——这让滑动窗口只需要数数，不需要存时长明细。三个实现：

- `FixedSizeSlidingWindowMetrics`：基于环形 `Measurement[]` 数组 + 一个实时维护的 `TotalAggregation`，逐出旧样本时**从总数里减掉**（subtract-on-evict），快照读取 O(1)（2.4 节详解）；
- `SlidingTimeWindowMetrics`：按 epoch 秒分桶的 `PartialAggregation[]`，头部桶推进时重置并减账；
- `LockFreeFixedSizeSlidingWindowMetrics` / `LockFreeSlidingTimeWindowMetrics`：v2.3.0 的 #2241 新增，用不可变状态 CAS 替代锁（2.4 节）。

## 2.3 CircuitBreaker：一台无锁状态机（重点 A）

**先说白话**：熔断器就是一个三态开关。CLOSED（放行并记账）→ 失败率/慢调用率超阈值 → OPEN（直接拒绝，抛 `CallNotPermittedException`）→ 等待一段"冷静时间" → HALF_OPEN（只放行固定数量的探测调用）→ 探测成功率高则回 CLOSED，否则再回 OPEN。Resilience4j 的实现要点是：**状态本身是一个可以被 CAS 原子替换的对象**，整个状态机无锁热路径。

### 2.3.1 对外接口与装饰模板

【源码证据】`resilience4j-circuitbreaker/src/main/java/io/github/resilience4j/circuitbreaker/CircuitBreaker.java`。核心方法签名（行号为证）：`tryAcquirePermission():446`、`releasePermission():457`、`acquirePermission():473`、`onError(duration, unit, throwable):482`、`onSuccess(duration, unit):491`、`onResult(...):501`、`reset():509`，以及 2.4.0 新增的 `transitionToOpenStateFor(Duration):535` / `transitionToOpenStateUntil(Instant):543`（声明式"对某个结果强制开闸一段时间"，源自 #1387）。所有装饰模板共用同一个骨架：

```java
// CircuitBreaker.java:66-79（decorateCheckedSupplier，节选）
@Override
public O apply(A a) throws Throwable {
    long start = circuitBreaker.getCurrentTimestamp();          // 可注入 Clock
    try {
        O result = supplier.apply(a);
        long duration = circuitBreaker.getCurrentTimestamp() - start;
        circuitBreaker.onResult(duration, circuitBreaker.getTimestampUnit(), result);
        return result;
    } catch (Throwable exception) {
        // do not use getCause here and fail fast
        long duration = circuitBreaker.getCurrentTimestamp() - start;
        circuitBreaker.onError(duration, circuitBreaker.getTimestampUnit(), exception);
        throw exception;
    }
}
```

（注：快照中该片段的局部变量行在 70-77 行区间，骨架语义为"记起点 → 执行 → 按结果/异常回报时长"。）时间来源可替换：`CircuitBreakerConfig.java:58-59` 默认 `System.nanoTime()`/NANOSECONDS，v2.3.0 起支持自定义 `Clock`——测试时用模拟时钟就能把"等 60 秒"的用例跑成毫秒级。

### 2.3.2 状态机内部：一个 AtomicReference 的对象替换

【源码证据】`resilience4j-circuitbreaker/src/main/java/io/github/resilience4j/circuitbreaker/internal/CircuitBreakerStateMachine.java`（1210 行，全文状态机的家）：

- 状态载体：`AtomicReference<CircuitBreakerState> stateReference`（:62）。**状态切换 = 换一个全新的状态对象**，而不是改字段；
- 一把 `ReentrantLock`（:70）只用于守卫 OPEN→HALF_OPEN 的自动转换竞态，热路径不加锁；
- 对外 6 个状态：`CLOSED, OPEN, HALF_OPEN, DISABLED, METRICS_ONLY, FORCED_OPEN`（没有 TERMINAL）；内部状态类：`ClosedState:594`、`OpenState:687`、`HalfOpenState:1079`、`DisabledState:840`、`MetricsOnlyState:909`、`ForcedOpenState:1006`，全部实现私有接口 `CircuitBreakerState`（:483-518）；
- v2.4.0 新增 `initialState` 配置（提交 `13dd3c46`），`getCircuitBreakerStateObjectFromState`（:471-481）在构造时按配置选初始状态对象。

`tryAcquirePermission` 在各状态下的行为（行号实证）：

| 状态 | 行为 | 证据 |
|---|---|---|
| CLOSED | `return isClosed.get()`——由状态切换路径维护的 AtomicBoolean | :610-612 |
| OPEN | 惰性时钟判定，到点则先转 HALF_OPEN 再把"要不要放行"重新派发给新状态对象 | :740-755 |
| HALF_OPEN | 原子递减探针配额：`getAndUpdate(current -> current == 0 ? current : --current) > 0`；到 0 则记一次拒绝 | :1117-1124 |
| DISABLED / METRICS_ONLY | 恒放行（DISABLED 不记账；METRICS_ONLY 仍记账并发阈值事件） | :855-857、:928-930 |
| FORCED_OPEN | 恒拒绝并记 `onCallNotPermitted` | :1022-1025 |

### 2.3.3 CLOSED→OPEN：失败率与慢调用率的双阈值评估

【源码证据】`CircuitBreakerStateMachine.java:628-637, 663-668`（ClosedState）：

```java
@Override
public void onError(long duration, TimeUnit durationUnit, Throwable throwable) {
    // CircuitBreakerMetrics is thread-safe
    checkIfThresholdsExceeded(circuitBreakerMetrics.onError(duration, durationUnit));
}
...
private void checkIfThresholdsExceeded(Result result) {
    if (Result.hasExceededThresholds(result) && isClosed.compareAndSet(true, false)) {
        publishCircuitThresholdsExceededEvent(result, circuitBreakerMetrics);
        transitionToOpenState();
    }
}
```

阈值判定引擎在 `internal/CircuitBreakerMetrics.java:141-176`：`failureRateInPercentage >= failureRateThreshold` 得 `FAILURE_RATE_ABOVE_THRESHOLDS`，`slowCallsInPercentage >= slowCallRateThreshold` 得 `SLOW_CALL_RATE_ABOVE_THRESHOLDS`，两者可独立或同时触发；`bufferedCalls < minimumNumberOfCalls` 时返回 `BELOW_MINIMUM_CALLS_THRESHOLD`（样本不足不动作——这是"熔断器不会因为 3 个请求全失败就开闸"的保证）。慢调用的归类发生在记账瞬间（:109-132，`duration > slowCallDurationThresholdInNanos` 记 `SLOW_SUCCESS/SLOW_ERROR`），底层四值 `Outcome` 枚举见 2.2.4。

COUNT_BASED 模式下还有一个隐式钳制：`minimumNumberOfCalls = min(minimumNumberOfCalls, slidingWindowSize)`（`CircuitBreakerConfig.java:659-663`）——评估窗口不可能大于统计窗口。

### 2.3.4 OPEN→HALF_OPEN：惰性时钟 + 可选调度器

【源码证据】`CircuitBreakerStateMachine.java:740-755`（OpenState）：

```java
@Override
public boolean tryAcquirePermission() {
    if (clock.instant().isAfter(retryAfterWaitDuration)) {   // 惰性检查：到点了吗？
        toHalfOpenState();
        // 重新向"新状态对象"申请放行
        boolean callPermitted = stateReference.get().tryAcquirePermission();
        if (!callPermitted) {
            publishCallNotPermittedEvent();
            circuitBreakerMetrics.onCallNotPermitted();
        }
        return callPermitted;
    }
    circuitBreakerMetrics.onCallNotPermitted();
    return false;
}
```

四个设计点：

1. **deadline 在 OpenState 构造时一次算死**（:706-714，`clock.instant().plus(waitDurationInMillis)`），之后每次拒绝都只是读一下时钟——这就是"默认无后台线程"的全部实现。
2. **转半开的唯一性由 CAS 保证**：`toHalfOpenState()`（:820-830）在锁内做 `isOpen.compareAndSet(true, false)`，一千个请求同时撞到 deadline 也只有一个执行转换，其余全部重定向到新状态的配额判定。
3. `automaticTransitionFromOpenToHalfOpenEnabled` 默认 false（`CircuitBreakerConfig.java:87`）；开启后 OpenState 构造器（:722-728）通过 `SchedulerFactory.getScheduler().schedule(...)` 补一个定时转换（SchedulerFactory 是全库共享调度器的持有者，`internal/SchedulerFactory.java`），任何手动状态切换会在 `preTransitionHook()`（:816-818）取消该任务。定时器的意义是"没有流量时也按时转半开"，而不是必须品。
4. **OPEN 等待时长本身可以是退避函数**：`waitIntervalFunctionInOpenState` 按 `attempts`（每次重新开闸自增 1，:361-364）取值（:698）——把 `IntervalFunction.ofExponentialBackoff(60s, 1.5)` 配进去，就得到"越熔越久"的开闸节奏。注意它不是布尔开关；同时配置 `waitDurationInOpenState` 和 `waitIntervalFunctionInOpenState` 会直接抛 `IllegalStateException`（`validateWaitIntervalFunctionInOpenState`，:1043-1050）。

### 2.3.5 HALF_OPEN：恰好容纳探针数量的评估窗口

【源码证据】两处：

- `HalfOpenState` 的统计窗口是**按 `permittedNumberOfCallsInHalfOpenState` 定容的 count-based 窗口**：`CircuitBreakerMetrics.forHalfOpen`（`CircuitBreakerMetrics.java:77-81`）。
- 退出判定（`CircuitBreakerStateMachine.java:1188-1195`）：

```java
private void checkIfThresholdsExceeded(Result result) {
    if (Result.hasExceededThresholds(result) && isHalfOpen.compareAndSet(true, false)) {
        transitionToOpenState();
    }
    if (result == BELOW_THRESHOLDS && isHalfOpen.compareAndSet(true, false)) {
        transitionToClosedState();
    }
}
```

因为窗口大小 == 探针配额、且有效最小样本被钳成 `min(minimumNumberOfCalls, permittedNumberOfCallsInHalfOpenState)`，**这批探测调用一有结果就能形成判定**——探针成功率高则关闭熔断，低则立刻再开。响应式场景下被取消的订阅必须归还配额：`releasePermission()`（:1156-1158）把探针计数加回去（Reactor 操作符的 `hookOnCancel` 负责调用，见 2.9 节）。

v2.4.0 给 HALF_OPEN 补了最后一块拼图：`maxWaitDurationInHalfOpenState`（0 表示关闭）+ `transitionToStateAfterWaitDuration`（默认 OPEN，可配 CLOSED）——探测迟迟没有结果时，由调度任务把状态强推到目标态（:1097-1105、:1139-1153）。该配置由 `d6e80d70`（2025-03-25，#2285）引入；仓库 HEAD 提交 `b7c00698` 修的正是这个特性的 javadoc 边界条件。

### 2.3.6 异常归属：谁算失败，谁被无视

【源码证据】`CircuitBreakerStateMachine.handleThrowable`（:206-223），优先级从上到下：

```java
if (evaluatePredicate(circuitBreakerConfig.getIgnoreExceptionPredicate(), throwable)) {
    releasePermission();
    publishCircuitIgnoredErrorEvent(name, duration, durationUnit, throwable);
    return;                                    // 无视：既不算成功也不算失败
}
if (evaluatePredicate(circuitBreakerConfig.getRecordExceptionPredicate(), throwable)) {
    publishCircuitErrorEvent(...);
    stateReference.get().onError(duration, durationUnit, throwable);   // 失败
} else {
    publishSuccessEvent(duration, durationUnit);
    stateReference.get().onSuccess(duration, durationUnit);            // 成功
}
```

- **默认是"一切异常皆失败"**：`DEFAULT_RECORD_EXCEPTION_PREDICATE = throwable -> true`、`DEFAULT_IGNORE_EXCEPTION_PREDICATE = throwable -> false`（`CircuitBreakerConfig.java:56-57`）。`recordExceptions(Class...)`/`ignoreExceptions(Class...)` 由 core 的 `PredicateCreator`（`resilience4j-core/.../predicate/PredicateCreator.java`，`isAssignableFrom` 判定 + OR 组合，~50-60 行）编织成谓词。
- v2.4.0 新增 `ignoreExceptionsPrecedenceEnabled`（默认 false，提交 `7d6895d2`）：开启后 ignore 谓词优先，实现为把 record 谓词包上 `!ignore.test(t)`（:1030-1041）；默认行为（record 优先）是为了保持 1.x 兼容。
- `onResult`（:233-245）允许把"业务上算失败的结果"转成 `ResultRecordedAsFailureException`；`onError` 会自动解包 `CompletionException`/`ExecutionException`（:195-204），所以异步调用链上看到的是根因。
- 细节控：`evaluatePredicate`（:254-261）在用户谓词自身抛异常时会先 `releasePermission()` 再重抛——这是 v2.4.0 之后才修的 permit 泄漏（`2f3d998c`，#2488）。`CallNotPermittedException` 支持 `writableStackTraceEnabled=false` 关闭栈采集（`CallNotPermittedException.java:30,39-48`），被熔断高频拒绝时栈采集本身就是开销。

### 2.3.7 CircuitBreakerConfig 全景默认值表

【源码证据】`CircuitBreakerConfig.java`（常量 43-63 行、字段 79-100 行、builder 341-367 行）：

| 属性 | 默认值 | 行号 |
|---|---|---|
| failureRateThreshold | 50（%） | :43 |
| slowCallRateThreshold | 100（%） | :44 |
| waitDurationInOpenState | 60 s | :45, :89-90 |
| waitIntervalFunctionInOpenState | 固定 60s 的 IntervalFunction | :89-90 |
| permittedNumberOfCallsInHalfOpenState | 10 | :46 |
| minimumNumberOfCalls | 100（COUNT_BASED 下钳制为 min(该值, slidingWindowSize)） | :47, :659-663 |
| slidingWindowSize | 100 | :48 |
| slidingWindowType | COUNT_BASED | :52 |
| slidingWindowSynchronizationStrategy | SYNCHRONIZED（可选 LOCK_FREE，2.3.0+） | :53-54 |
| slowCallDurationThreshold | 60 s | :49 |
| maxWaitDurationInHalfOpenState | 0 s（特性关闭） | :50, :96-97 |
| transitionToStateAfterWaitDuration | OPEN（2.4.0，只能 OPEN/CLOSED，:573-582） | :51 |
| automaticTransitionFromOpenToHalfOpenEnabled | false | :87 |
| writableStackTraceEnabled | true | :55 |
| recordExceptionPredicate / ignoreExceptionPredicate | `t -> true` / `t -> false` | :56-57 |
| recordResultPredicate | `o -> false` | :60 |
| currentTimestampFunction / timestampUnit | `System.nanoTime()` / NANOSECONDS | :58-59 |
| clock | `Clock.systemUTC()`（2.3.0+） | :63 |
| initialState | CLOSED（2.4.0） | :88 |
| ignoreExceptionsPrecedenceEnabled | false（2.4.0） | :100 |
| transitionOnResult | 无转换（#1387，配合 `transitionToOpenStateFor/Until`） | :61-62, :91-92 |

### 2.3.8 事件系统

【源码证据】`resilience4j-circuitbreaker/.../event/CircuitBreakerEvent.java:52+`：10 种事件类型 `ERROR / IGNORED_ERROR / SUCCESS / NOT_PERMITTED / STATE_TRANSITION / RESET / FORCED_OPEN / DISABLED / FAILURE_RATE_EXCEEDED / SLOW_CALL_RATE_EXCEEDED`。消费者按事件类型注册到状态机内部的 `CircuitBreakerEventProcessor`（:520-592），事件全链路受 2.2.1 的 `hasConsumers()` 零成本短路保护。`resilience4j-consumer` 模块的 `CircularEventConsumer`（`.../CircularEventConsumer.java:21-54`）用 circularbuffer 的 `ConcurrentCircularFifoBuffer` 维护一个有界事件缓冲——Spring Boot actuator 的 `/actuator/circuitbreakerevents` 端点读的就是它（3.6 节），默认容量 100（3.5 节实证）。

`reset()`（:317-326）= `stateReference.getAndUpdate(current -> new ClosedState())` + RESET 事件：**全部统计清零重来**。

## 2.4 滑动窗口考古：从 RingBitSet 到 subtract-on-evict，再到无锁变体

**先说白话**：熔断器需要回答"最近 N 次调用里失败了多少""最近 M 秒里失败了多少"。这是滑动窗口问题，Resilience4j 在三个历史阶段用了三种实现，理解这条演进线能解释很多今天源码里的"为什么"。

**阶段一（0.3.0 ~ 1.x）：RingBitSet 环形位图**。每次调用结果只占 1 bit（0/1），N 次窗口就是一个环形位集，`totalBits/setBits` 即失败率——内存极省，但慢调用分类塞不进 1 bit。

**阶段二（v2.0.0 周期）：Measurement/Outcome 设计**。`git log -S "RingBitSet"` 定位到移除提交 `efcb88ee` "[Performance] Improve circuitbreaker throughput (#1395)"。新设计把每次调用记成一个 `Measurement`（含 Outcome 四分类与时长），COUNT_BASED 窗口为环形 `Measurement[]` + 实时 `TotalAggregation`：

```java
// resilience4j-core/.../metrics/FixedSizeSlidingWindowMetrics.java:63-77（v2.4.0 快照）
@Override
public Snapshot record(long duration, TimeUnit durationUnit, Outcome outcome) {
    lock.lock();
    try {
        totalAggregation.record(duration, durationUnit, outcome);
        moveWindowByOne().record(duration, durationUnit, outcome);  // 先逐出旧样本再记新样本
        return new SnapshotImpl(totalAggregation);
    } finally {
        lock.unlock();
    }
}
```

关键技巧是 **subtract-on-evict**：`moveWindowByOne`（:88-94）在覆盖环形槽位前，把被覆盖样本从 `TotalAggregation` 里减掉——于是任何时刻的窗口快照都是 O(1) 读取预聚合的总账。TIME_BASED 窗口（`SlidingTimeWindowMetrics`，javadoc :28-49）则按 epoch 秒维护 `PartialAggregation[]` 桶，头部指针推进时重置到期桶并同样减账；`AbstractAggregation` 承载两层的公共计数逻辑。

**阶段三（v2.3.0）：无锁变体**。提交 `d0d150f2`（2024-12-04，#2241 "Lock free sliding window"）为配置增加 `SlidingWindowSynchronizationStrategy`：默认 `SYNCHRONIZED`（上文的 ReentrantLock 版），可选 `LOCK_FREE`（对不可变状态做 CAS 的 `LockFreeFixedSizeSlidingWindowMetrics`/`LockFreeSlidingTimeWindowMetrics`）；TIME_BASED + LOCK_FREE 要求 `slidingWindowSize >= 2`（`CircuitBreakerConfig.java:665-667`）。这是 v2.3.0"虚拟线程友好化"（#2232 把 synchronized 换成 ReentrantLock）的延续。

环形缓冲的衣钵传给了谁？`resilience4j-circularbuffer` 模块如今唯一的 生产用户是 consumer 模块的事件环形缓冲（1.4 节）——统计内核已经全面迁入 core 的 metrics 包。

## 2.5 RateLimiter：AtomicRateLimiter 的周期数学（重点 B）

**先说白话**：RateLimiter 的语义是"每 `limitRefreshPeriod` 时间发放 `limitForPeriod` 个许可，拿不到许可最多等 `timeoutDuration`"。注意它**不是**平滑的令牌桶——它是按周期整段放行配额的"固定窗口 + 预留"模型。

**两个实现，一主一辅**：工厂只造 `AtomicRateLimiter`（【源码证据】`resilience4j-ratelimiter/.../RateLimiter.java:84,98,122,131-132` 与 `internal/InMemoryRateLimiterRegistry.java:160,179` 全部 `new AtomicRateLimiter(...)`）；`SemaphoreBasedRateLimiter` 需要手动实例化，它用一把**公平信号量**（`SemaphoreBasedRateLimiter.java:78-105`，`new Semaphore(limitForPeriod, true)`）+ 单线程调度器按周期 `release(limitForPeriod)`，有 `shutdown()` 生命周期——适合配额刷新必须由外部托管调度器驱动的部署。

**AtomicRateLimiter 的核心是 `calculateNextState`**（【源码证据】`internal/AtomicRateLimiter.java:227-251`）：

```java
long cyclePeriodInNanos = activeState.config.getLimitRefreshPeriod().toNanos();
int  permissionsPerCycle = activeState.config.getLimitForPeriod();
long currentNanos  = currentNanoTime();
long currentCycle  = currentNanos / cyclePeriodInNanos;      // 绝对周期号：时钟即状态

long nextCycle = activeState.activeCycle;
int  nextPermissions = activeState.activePermissions;
if (nextCycle != currentCycle) {                             // 惰性刷新：没有定时器
    long elapsedCycles = currentCycle - nextCycle;
    long accumulated = elapsedCycles * permissionsPerCycle;
    nextCycle = currentCycle;
    nextPermissions = (int) min(nextPermissions + accumulated, permissionsPerCycle);
}
long nextNanosToWait = nanosToWaitForPermission(permits, cyclePeriodInNanos,
    permissionsPerCycle, nextPermissions, currentNanos, currentCycle);
State nextState = reservePermissions(...);                   // 减掉本次消耗，可能变负
```

三个精妙之处：

1. **状态由 `System.nanoTime()/周期` 直接推导**——补发配额就是把"跨过的周期数 × 每周期配额"加回来并封顶，不需要任何刷新线程。
2. **跨周期预留**：当前周期配额不够而请求量大于 1 时，`nanosToWaitForPermission`（:265-276）用 `divCeil` 算出要等到第几个未来周期，`reservePermissions` 直接把 `activePermissions` 减成**负数**（:433-436 的 javadoc 明说这是有意为之）——未来周期的额度被这次请求"预订"了，后续请求不可能重复占坑。调用线程则 `parkNanos` 到周期边界（`waitForPermission`，:345-359，`waitingThreads` 计数器喂给 `getNumberOfWaitingThreads()`）。
3. **无锁 CAS + 自旋退避**：状态对象以 CAS 更新，失败后 `parkNanos(1)` 让步（:185-215，注释引用了 CAS 自旋的论文 arXiv:1305.5800）。

默认配置（【源码证据】`RateLimiterConfig.java:152-155`）：`timeoutDuration = 5s`、`limitRefreshPeriod = 500 纳秒`、`limitForPeriod = 50`。**注意默认周期是 500 纳秒**——这是一个刻意的"测试用退化默认值"（相当于每 500ns 放 50 个许可≈不限流），生产必须显式配置，这也是大量"配了 RateLimiter 却不限流"疑问的答案。2.4.0 周期内还加入了 `drainPermissionsOnResult`（结果触发排干许可）。`reservePermission` 的三态返回（0=立即放行 / 正数=需等待的纳秒 / -1=拒绝）就是为响应式 Operator 设计的（2.9 节）。被拒绝时抛 `RequestNotPermitted`（`RequestNotPermitted.java:25`，RuntimeException）。

## 2.6 Bulkhead：信号量与线程池两副面孔

**先说白话**：舱壁隔离有两种姿势。信号量版 = 给依赖调用设并发上限（超了快速失败或短暂等待），零线程切换成本；线程池版 = 真把调用扔进专属线程池（隔离崩溃、但有线程开销 + 队列）。Resilience4j 两者都有，但定位不同。

**SemaphoreBulkhead**（【源码证据】`resilience4j-bulkhead/.../internal/SemaphoreBulkhead.java`）：

- 默认 `maxConcurrentCalls = 25`、`maxWaitDuration = 0`（`BulkheadConfig.java:33-36`），且 **信号量默认公平**（构造器 :95 `new Semaphore(config.getMaxConcurrentCalls(), config.isFairCallHandlingEnabled())`，`fairCallHandlingEnabled` 默认 true）；
- 阻塞路径 `tryEnterBulkhead`（:294-303）= `semaphore.tryAcquire(maxWaitDuration.toMillis(), MILLISECONDS)`，拿不到抛 `BulkheadFullException`（:172-181）；调用结束 `onComplete()` 归还并发布 CallFinished 事件（:240-244）；
- `changeConfig`（:136-151）支持运行时改并发上限（锁内结算许可差值）；
- v2.4.0 之后（提交 `7e3ab525`，#1592）新增**非阻塞** `acquirePermissionAsync()`（:187-225）：快速路径 tryAcquire，满了则挂起一个带 PENDING/GRANTED/EXPIRED/CANCELLED 状态机的 `PendingPermission`（:364-388），按 FIFO 排队（`grantPendingPermissions` 排水循环，:318-348）——响应式 BulkheadOperator 因此不再阻塞调用线程（2.9 节）。

**FixedThreadPoolBulkhead**（【源码证据】`internal/FixedThreadPoolBulkhead.java:84-95`）：**每个舱壁实例独占一个 ThreadPoolExecutor**：

```java
this.executorService = new ThreadPoolExecutor(config.getCoreThreadPoolSize(),
    config.getMaxThreadPoolSize(), config.getKeepAliveDuration().toMillis(), TimeUnit.MILLISECONDS,
    config.getQueueCapacity() == 0 ? new SynchronousQueue<>() : new ArrayBlockingQueue<>(config.getQueueCapacity()),
    threadFactory, config.getRejectedExecutionHandler());
```

- 默认值（`ThreadPoolBulkheadConfig.java:43-58`）：`queueCapacity = 100`、`keepAliveDuration = 20ms`、`coreThreadPoolSize = max(可用处理器−1, 1)`、`maxThreadPoolSize = 可用处理器`、拒绝策略 **AbortPolicy**——"队列 + 线程全满被 JDK 拒绝"**就是**舱壁满的信号：`submit`（:142-165）捕获 `RejectedExecutionException` 转抛 `BulkheadFullException`（:164）。
- 提交前用 `ContextPropagator.decorateSupplier(...)` 搬运 ThreadLocal（2.2.3）；返回 `CompletableFuture`。
- HEAD 快照的线程工厂已支持 `ThreadType.VIRTUAL`（:87-90，来自 `b999ae19`）——线程池舱壁 + 虚拟线程在 2.5.0 时代将是低成本组合（JDK 21 基线的原因之一）。

注意语义差异：**线程池舱壁的"满"发生在提交瞬间（队列 + 线程），信号量舱壁的"满"发生在执行瞬间（并发数）**；且线程池舱壁把异常包进 `ExecutionException`，而信号量舱壁的异常直接传播——这是选型与测试时最常被忽略的差别。

## 2.7 Retry：间隔函数与"负间隔"信号

**先说白话**：Retry 解决瞬时故障：最多 `maxAttempts` 次、每次失败后按 `IntervalFunction` 算等待、可以选择只重试某些异常或某些结果值。

【源码证据】`resilience4j-retry/.../RetryConfig.java:39-43,60-61`：默认 `maxAttempts = 3`、固定 500ms 间隔（`DEFAULT_INTERVAL_FUNCTION = numOfAttempts -> 500`）、`failAfterMaxAttempts = false`（耗尽后抛最后一次真实异常，而非 `MaxRetriesExceededException`）、结果谓词默认 null。`IntervalFunction` 默认参数见 2.2.2（500ms / ×1.5 / ±50% 随机化）。

每次 `retry.context()` 创建独立上下文（`internal/RetryImpl.java:147+`：AtomicInteger `numOfAttempts` + AtomicReference `lastException`），并发调用互不共享计数。核心判定（`RetryImpl.java:206-259`）：

```java
@Override
public void onError(Exception exception) throws Exception {
    totalAttemptsCounter.increment();
    if (exceptionPredicate.test(exception)) {
        lastException.set(exception);
        throwOrSleepAfterException();      // 次数耗尽 → 重抛；否则 sleep(interval)
    } else {
        failedWithoutRetryCounter.increment();
        publishRetryEvent(() -> new RetryOnIgnoredErrorEvent(getName(), exception));
        throw exception;                   // 不在重试名单：直接抛给调用方
    }
}
```

两个值得记住的机制：

- **负间隔 = 停止重试**（v2.2.0，#1565）：`intervalBiFunction` 返回负数时不再 sleep，直接发布 `RetryOnErrorEvent` 并抛出——这是用 IntervalBiFunction 表达"这个错误别再试了"的出口；
- **sleep 可替换**：`sleepFunction` 默认 `Thread::sleep`（:48），测试可注入；Reactor 路径用 `asyncContext()`，其 `onError/onResult` 不 sleep 而是**返回应等待的毫秒数**，交由 `Mono.delay` 表达（2.9 节）。

事件族：`RetryOnRetryEvent`（携带本次算出的间隔）、`RetryOnErrorEvent`、`RetryOnIgnoredErrorEvent`、`RetryOnSuccessEvent`；实例级 LongAdder 统计（成功/失败/总尝试，:68-72）。

## 2.8 TimeLimiter：两种超时路径

**先说白话**：TimeLimiter 只做一件事——"这个调用最多等 `timeoutDuration`，到点视为超时"。默认 1 秒、默认 `cancelRunningFuture = true`（【源码证据】`resilience4j-timelimiter/.../TimeLimiterConfig.java:63-64`）。它**只对异步返回值有意义**：Future/CompletionStage，或响应式流（Spring 注解 `@TimeLimiter` 用在普通同步方法上会直接抛 `IllegalReturnTypeException`，3.8 节）。

两条实现路径（`internal/TimeLimiterImpl.java`）：

1. **Future 路径**（`decorateFutureSupplier`，:41-70）：直接 `future.get(timeout, MILLISECONDS)`；捕获 `TimeoutException` 后构造带名字的 TimeoutException、发布 `TimeLimiterOnTimeoutEvent`，并按配置 `future.cancel(true)`（:50-56）——协作式取消，线程中断交给任务自己响应。
2. **CompletionStage 路径**（`decorateCompletionStage(scheduler, supplier)`，:72-110）：预先在调度器上挂一个 Timeout 任务（:180-188），到点 `completeExceptionally(TimeoutException)`——超时由"调度器到点补一枪异常完成"实现而非阻塞等待；正常完成时 `whenComplete` 取消 Timeout 任务并把 `CompletionException` 因果解包进 `onError`（:83-108）。

事件三种（Success/Error/Timeout），同样受 `hasConsumers()` 保护（:133-161）。v2.4.0 补了 `decorateFunction`/`executeFunction`（提交 `03b41b24`）。

## 2.9 组合的艺术：Decorators 的包裹顺序与 Reactor 操作符

### 2.9.1 Decorators：第一个 withX 在最内层

【源码证据】`resilience4j-all/src/main/java/io/github/resilience4j/decorators/Decorators.java`，javadoc（:26-41）明说组合规则："Decorators are applied in the order of the builder chain … This results in the following composition when executing the supplier: `Fallback(Retry(CircuitBreaker(Supplier)))`"。机制是每次 `withX` 都把当前 supplier **重新包一层**（:99-125）：

```java
public DecorateSupplier<T> withCircuitBreaker(CircuitBreaker circuitBreaker) {
    supplier = CircuitBreaker.decorateSupplier(circuitBreaker, supplier);
    return this;
}
public DecorateSupplier<T> withRetry(Retry retryContext) {
    supplier = Retry.decorateSupplier(retryContext, supplier);   // Retry 包住"到目前为止的一切"
    return this;
}
```

所以 `Decorators.ofSupplier(s).withCircuitBreaker(cb).withBulkhead(bh).withRetry(retry)` 的执行结构是 **Retry(Bulkhead(CircuitBreaker(Supplier)))**：**链上第一个装饰器最贴近被保护代码，最后一个在最外层**；`withFallback` 若写在最后就是总兜底。同文件提供 Callable/CheckedSupplier/Runnable/Function/Consumer 变体；异步等价物是 `DecorateCompletionStage`（其 `withThreadPoolBulkhead` 会把类型切换为 `Supplier<CompletionStage<T>>`，:147-162；线程池舱壁在 2.4.0 进入 Decorators，#2284）。Vavr 用户用 `resilience4j-vavr` 的 `VavrDecorators`。

### 2.9.2 Reactor 操作符：订阅级保护

【源码证据】`resilience4j-reactor` 各 Operator 均实现 `UnaryOperator<Publisher<T>>` 并区分 Mono/Flux（否则抛 `IllegalPublisherException`）。最有代表性的是 **CircuitBreakerOperator——权限在订阅时获取，而非每元素一次**：

```java
// reactor/circuitbreaker/operator/FluxCircuitBreaker.java:26-35
@Override
public void subscribe(CoreSubscriber<? super T> actual) {
    if (circuitBreaker.tryAcquirePermission()) {
        source.subscribe(new CircuitBreakerSubscriber<>(circuitBreaker, actual, false));
    } else {
        Operators.error(actual, createCallNotPermittedException(circuitBreaker));
    }
}
```

`CircuitBreakerSubscriber` 的信号钩子：`hookOnNext` 对 Mono 用 CAS 保证只记一次 `onResult`（:52-61）；`hookOnComplete → onSuccess`（:63-70）；**`hookOnCancel` → 未收到任何事件则 `releasePermission()`，否则 `onSuccess`**（:72-81）——这是"取消必须归还熔断许可"的响应式对应物；`hookOnError → onError`（:83-87）。

其余操作符一览：**BulkheadOperator** 已随 #1592 非阻塞化（javadoc :22-29：用 `acquirePermissionAsync()`，满了且配置了 maxWaitDuration 则推迟订阅直到授予权限，否则超时后 `BulkheadFullException`）；**RateLimiterOperator**（:43-56）支持加权 `of(rateLimiter, permits)`，在订阅时预留并把订阅**延迟** nanosToWait；**RetryOperator**（:47-56）用 `doOnNext + retryWhen(Retry.withThrowable(errors -> errors.flatMap(context::handleErrors)))` 组合，等待时长变成 `Mono.delay`，命中结果谓词的值被包成 `RetryDueToResultException` 走同一条重试通道（:86-105）；**TimeLimiterOperator**（:52-66）就是 `upstream.timeout(timeoutDuration)` + 成功/错误钩子记账。

## 2.10 hedge 模块速览：把"重试"升级成"对冲竞赛"

`resilience4j-hedge`（提交 `d74e254f` 2021-12-03，#1454/#1557，随 v2.0.0 首发不在）解决的是另一个问题：重试要等失败，对冲（hedged request）**不等失败，超时或按历史耗时估计就提前再发一次，谁先回来用谁**。API：`Hedge.of(name, hedgeConfig[, scheduler])` + `decorateCompletionStage`；两种对冲时机策略 `HedgeConfig.HedgeDurationSupplierType { PRECONFIGURED, AVERAGE_PLUS }`（`HedgeConfig.java:127-128`）：`preconfiguredDuration(cutoff)` 固定对冲点（:208-213），`averagePlusDuration(...)` 在滚动窗口平均耗时上加一个百分比/绝对值余量（`internal/HedgeDurationSupplier.java:39-58`）。实现是干净的双跑竞赛：`HedgeImpl.decorateCompletionStage`（:101-137）把主调用包成 `HedgeResult(fromPrimary=true)`，定时器到点再发一次次级调用，`CompletableFuture.anyOf` 取先到者，两者互取 cancel；事件分主/次成功/失败四类。

## 2.11 本章小结

- **状态机即对象**：CircuitBreaker 用 `AtomicReference` 持有状态对象，六态切换全是 CAS 对象替换；OPEN 的等待是惰性时钟判定，HALF_OPEN 是定容探针窗口，二者合起来构成"无定时器、无锁热路径"的熔断实现。
- **统计即预聚合**：滑动窗口用 subtract-on-evict 维护 O(1) 快照；"慢"在记账瞬间被分类为独立 Outcome；2.3.0 起还有无锁变体可选。
- **限流即时钟**：AtomicRateLimiter 的配额状态完全由 `nanoTime/周期` 推导，跨周期额度用负数预留——没有调度器、没有锁，只有 CAS + park。
- **隔离即资源**：信号量舱壁是公平的并发上限，线程池舱壁是独占 JDK 线程池（AbortPolicy 即舱壁满）；2.4.0 后两者都有了非阻塞形态（后者靠虚拟线程工厂，前者靠 PendingPermission 队列）。
- **重试与超时是两个独立的"等待语义"**：Retry 管失败后的退避（负间隔可提前终止），TimeLimiter 管在途调用的死线（Future.get 或调度器补枪）；它们的 Reactor 形态共享同一套 per-call Context。
- **组合即洋葱**：Decorators 首个 withX 最内层；Reactor 操作符把同样的保护下沉到订阅信号层，并补上了"取消归还许可"这条阻塞世界没有的语义。

---

# 三、Spring 集成层：从注解切面到 Spring Cloud 门面

> 本章对应源码：`D:\code\3rd\resilience4j` 的 `resilience4j-annotations`、`resilience4j-framework-common`、`resilience4j-spring6`、`resilience4j-spring-boot3`、`resilience4j-spring-boot4` 模块，以及 `D:\code\3rd\spring-cloud-circuitbreaker`（main 5.1.0-SNAPSHOT，历史 tag v5.0.0~v5.0.3、v5.1.0-M1 用于版本考古）。以下【源码证据】行号均为实际读取所得。

## 3.1 六个构件的分工

**先说白话**：在 Spring 里用 Resilience4j，你的 classpath 上通常会出现 4~6 个构件。它们的分工像一个"洋葱从里到外"：

| 构件 | 职责 | 关键内容 |
|---|---|---|
| resilience4j-annotations | 注解定义 | `@CircuitBreaker`/`@Retry`/`@RateLimiter`/`@Bulkhead`/`@TimeLimiter`/`@Timer`，全部 `@Retention(RUNTIME) @Target({METHOD, TYPE})`（标注在类上等效于全部 public 方法，如 `CircuitBreaker.java:30,44-59`） |
| resilience4j-framework-common | 框架公共件 | 配置属性合并（`configs`/`instances` 两级模型）、fallback 方法解析、切面 order 常量（3.3/3.5 节） |
| resilience4j-spring6 | 切面 + 装配（纯 spring-context，不含 Boot） | 六个 AspectJ 切面（`io.github.resilience4j.spring6.<tech>.configure` 包）、Registry Bean 工厂、Actuator 端点定义；**boot3 与 boot4 共同的地基**（`resilience4j-spring-boot4/build.gradle:2-22` 仅 `api(project(":resilience4j-spring6"))`） |
| resilience4j-spring-boot3 | Boot 3 starter | 包根 `io.github.resilience4j.springboot3.*`，构建基线 Boot 3.2.0（`gradle/libs.versions.toml:6`） |
| resilience4j-spring-boot4 | Boot 4 starter（v2.4.0 新增） | 包根 **`io.github.resilience4j.springboot.*`（注意：没有 "4"）**，构建基线 Boot 4.0.0 / Spring 7.0.2 / Spring Cloud 5.0.0（`libs.versions.toml:9-11`） |
| spring-cloud-circuitbreaker-resilience4j | Spring Cloud 编程式门面 | `CircuitBreakerFactory` 抽象的 Resilience4j 实现，Feign/OpenFeign 熔断的落点（3.10 节） |

## 3.2 注解与切面：一个注解如何变成一层装饰

### 3.2.1 五个切面，同一个切点模板

【源码证据】`resilience4j-spring6/src/main/java/io/github/resilience4j/spring6/<tech>/configure/` 下五个切面类（外加 micrometer 的 TimerAspect）：

| 切面 | 类（:67 等） | @Pointcut 行 | @Around 行 |
|---|---|---|---|
| CircuitBreakerAspect | `.../spring6/circuitbreaker/configure/CircuitBreakerAspect.java:67` | :90 | :94 |
| RetryAspect | `.../spring6/retry/configure/RetryAspect.java:65` | :99 | :103 |
| RateLimiterAspect | `.../spring6/ratelimiter/configure/RateLimiterAspect.java:67` | :95 | :100 |
| TimeLimiterAspect | `.../spring6/timelimiter/configure/TimeLimiterAspect.java:43` | :70 | :75 |
| BulkheadAspect | `.../spring6/bulkhead/configure/BulkheadAspect.java:73` | :98 | :102 |

切点表达式统一为"类上有注解 **或** 方法上有注解"：

```java
// CircuitBreakerAspect.java:90
@Pointcut(value = "@within(circuitBreaker) || @annotation(circuitBreaker)", argNames = "circuitBreaker")
public void matchAnnotatedClassOrMethod(CircuitBreaker circuitBreaker) {}
```

类级注解场景下切点参数为 null，由 `getCircuitBreakerAnnotation()`（CircuitBreakerAspect.java:147-160）经 `AnnotationExtractor`（`.../spring6/utils/AnnotationExtractor.java:26,53`）从目标类或 JDK 代理接口上兜底提取。

### 3.2.2 注解名字的解析：四级表达式

backend id（注册表里的实例名）来自 `annotation.name()`（CircuitBreakerAspect.java:105）。这个名字可以不是字面量——`DefaultSpelResolver`（`.../spring6/spelresolver/DefaultSpelResolver.java:26-88`）按前缀四级解析：`${...}` 走 Spring 占位符链（`EmbeddedValueResolver`，:33-36，所以 `@CircuitBreaker(name = "${cb.name}")` 可用）；`#{...}` 是 SpEL 模板；`#...` 是参数 SpEL；`@bean` 引用容器 Bean。`configuration()` 非空时作为共享配置 key（:106），运行时 `registry.getConfiguration(configKey).orElseGet(getDefaultConfig)`（:132-133）。

### 3.2.3 fallback：没有 blockHandler 概念的统一兜底

【源码证据】`.../spring6/fallback/FallbackExecutor.java:42-95`：

- fallback 名同样先过 SpEL 解析，并支持**外部 Bean 语法 `"beanName::methodName"`**（`BEAN_METHOD_SEPARATOR = "::"` :20，:51-78 经 `BeanFactory.getBean` + `AopProxyUtils.getSingletonTarget` 定位到目标类方法）；
- **配置错了不报错**：解析失败只 `logger.warn` 并直接走主逻辑（:84-91）——`fallbackMethod` 拼写错误要到第一次触发时才会发现，这是踩坑重灾区；
- 匹配规则在 `FallbackMethod.filter()`（`.../fallback/FallbackMethod.java:132-152`）：方法名相同、返回类型可赋值（:136）；参数要么只有 1 个且为 `Throwable` 子类，要么**原始参数逐位相同 + 末尾追加一个 Throwable**（:139-151）；多个候选时沿异常父类链就近匹配（:172-183），同一异常被两个 fallback 覆盖直接 `IllegalStateException`（:126-129）；结果缓存于 `ConcurrentReferenceHashMap`（:49）；
- 装饰器按**原始方法返回类型**挑选（`FallbackDecorators.decorate()`，`FallbackDecorators.java:41-55`）：Reactor/RxJava2/RxJava3/CompletionStage 装饰器由 `FallbackConfiguration`（`.../fallback/configure/FallbackConfiguration.java:32-52`）按 classpath 条件注册，兜底 `DefaultFallbackDecorator`。

与 Sentinel 的 `blockHandler`（4.6 节）对比一句话：Resilience4j 的 fallback 对**一切 Throwable** 生效——包括限流/熔断拒绝的 `CallNotPermittedException`——且每个切面**各自**用自己的 fallbackExecutor 包裹自己的装饰链（如 CircuitBreakerAspect.java:110），所以一个方法上多个注解共用同名 fallbackMethod 时它可能被执行多次（3.4 节陷阱）。

## 3.3 切面顺序：默认链与"重试 × 熔断"陷阱（重点 C）

**先说白话**：一个方法同时标 `@Retry` 和 `@CircuitBreaker`，谁包谁？答案由每个切面的 Spring `Ordered` 值决定：**order 越小优先级越高 = 越靠外层**。

【源码证据】默认 order 是各 spring6 `*ConfigurationProperties` 的字段初值（每个切面的 `getOrder()` 委托它）：

| 切面 | 默认 order（字段初值） | 证据 |
|---|---|---|
| Retry | `Ordered.LOWEST_PRECEDENCE - 5`（最外层） | `RetryConfigurationProperties.java:26`、RetryAspect.java:202 |
| CircuitBreaker | `LOWEST_PRECEDENCE - 4` | `CircuitBreakerConfigurationProperties.java:23`、CircuitBreakerAspect.java:186 |
| RateLimiter | `LOWEST_PRECEDENCE - 3` | `RateLimiterConfigurationProperties.java:23`、RateLimiterAspect.java:195 |
| TimeLimiter | `LOWEST_PRECEDENCE - 2` | `TimeLimiterConfigurationProperties.java:25`、TimeLimiterAspect.java:151 |
| Bulkhead | `LOWEST_PRECEDENCE - 1` | `BulkheadConfigurationProperties.java:23`、BulkheadAspect.java:283 |
| Timer | `LOWEST_PRECEDENCE`（最内层） | `TimerConfigurationProperties.java:27`、TimerAspect.java:140 |

因此默认装饰链（外 → 内）为：

```
Retry → CircuitBreaker → RateLimiter → TimeLimiter → Bulkhead → Timer → 目标方法
```

`CircuitBreakerConfigurationProperties.java:26-30` 的 javadoc 自述了历史：0.16.0 起隐式固定为 "retry then circuit breaker then rate limiter then bulkhead"，并声明 **bulkhead 恒定最贴方法（最先真正执行）**，用户可覆盖其余顺序。覆盖属性（relaxed binding）：`resilience4j.circuitbreaker.circuit-breaker-aspect-order`、`resilience4j.retry.retry-aspect-order`、`resilience4j.ratelimiter.rate-limiter-aspect-order`、`resilience4j.timelimiter.time-limiter-aspect-order`、`resilience4j.bulkhead.bulkhead-aspect-order`、`resilience4j.micrometer.timer.timer-aspect-order`。

**"重试 × 熔断"陷阱的完整分析**（默认 Retry 在外、CB 在内）：

- 每次重试 attempt 都会独立过一遍 CB：一次逻辑调用最多给熔断器贡献 `maxAttempts` 条记账——慢调用/失败率窗口被同一次业务故障重复灌入，熔断**更容易也更早**打开（这有时正是你想要的"一次失败就加速熔断"，有时不是）；
- 反过来把 CB 调到 Retry 外层（`resilience4j.circuitbreaker.circuit-breaker-aspect-order` 设为比 retry 更小）：CB 只记 1 次调用；熔断打开后重试会在 `CallNotPermittedException` 上消耗重试次数——通常这是更"正确"的语义；
- Bulkhead 恒在最内层（javadoc 声明，无法用属性排到 Retry 之外）；
- fallback 由每个切面独立包裹（3.2.3），所以 Retry 与 CB 若声明同一个 fallbackMethod，最坏情况被调用多次——常见实践是**只给最外层声明 fallback**；
- `@TimeLimiter` 在 RateLimiter 之内、Bulkhead 之外，且对非 CompletionStage/非响应式返回值直接抛 `IllegalReturnTypeException`（3.8 节）。

## 3.4 配置绑定：configs/instances 两级合并与 Registry 初始化

**先说白话**：YAML 里 `resilience4j.circuitbreaker.configs.*` 定义共享模板、`instances.*` 定义实例，实例可以 `baseConfig` 指向模板。合并规则是"实例覆盖模板、模板之间 default 兜底"，实现上有一个容易忽略的细节：**只有 4 个"元属性"会从共享配置拷贝到实例，其余熔断参数靠 `CircuitBreakerConfig.from(baseConfig)` 继承**。

【源码证据】`resilience4j-framework-common/src/main/java/io/github/resilience4j/common/circuitbreaker/configuration/CommonCircuitBreakerConfigurationProperties.java`：

- 数据结构：`Map<String, InstanceProperties> instances` + `Map<String, InstanceProperties> configs`（:42-43），分别对应 YAML 的 `instances.*` 与 `configs.*`；全局 tags 继承自 `CommonProperties`（`.../common/CommonProperties.java:30`，YAML 键 `resilience4j.circuitbreaker.tags`）；
- 合并主干 `createCircuitBreakerConfig()`（:55-69）：

```java
CircuitBreakerConfig baseConfig = null;
if (instanceProperties != null && StringUtils.isNotEmpty(instanceProperties.getBaseConfig())) {
    baseConfig = createBaseConfig(instanceName, instanceProperties, customizer);   // baseConfig 指向的共享配置
} else if (configs.get(instanceName) != null) {
    baseConfig = createDirectConfig(instanceName, instanceProperties, customizer); // 与实例同名的共享配置
} else if (configs.get(DEFAULT) != null) {
    baseConfig = createDefaultConfig(instanceProperties, customizer);              // configs.default
}
return buildConfig(baseConfig != null ? from(baseConfig) : custom(), instanceProperties, customizer, instanceName);
```

优先级：**实例自身属性 > 实例 `baseConfig` 指向的共享配置 > 与实例同名的共享配置 > `configs.default` > 库默认**（`buildConfig` :109-198 只对非 null 字段调用 builder，所以是字段级覆盖）；`baseConfig` 自引用报 "Circular reference detected"（:76-78），共享配置缺失抛 `ConfigurationNotFoundException`（:82-83）；最后叠加 `CircuitBreakerConfigCustomizer`（:195-197）。
- `ConfigUtils.mergePropertiesIfAny`（`.../common/utils/ConfigUtils.java:21-45`）只搬运 4 个元属性：`registerHealthIndicator`、`allowHealthIndicatorToFail`、`eventConsumerBufferSize`、`ignoreClassBindingExceptions`——**它们不走 `CircuitBreakerConfig.from()` 继承链**，这就是"模板里的 registerHealthIndicator 不生效"类问题的根源；
- Registry 初始化（spring6 `CircuitBreakerConfiguration`）：`createCircuitBreakerRegistry`（:141-151）把所有共享 configs 建成 `Map<String, CircuitBreakerConfig>` 后 `CircuitBreakerRegistry.of(configs, compositeRegistryEventConsumer, Map.copyOf(properties.getTags()))`；`initCircuitBreakerRegistry`（:159-169）**预创建**所有 instances（以及 customizer 提到但未配置的实例名）；`registerEventConsumer`（:178-199）为每个实例挂事件消费者，缓冲默认 **100**（:193-196 `orElse(100)`）；
- 属性校验：如 `failureRateThreshold ∈ [1,100]`（`CommonCircuitBreakerConfigurationProperties.java:417-426`）。

其余四种容错器的绑定结构完全同构（前缀 `resilience4j.retry` / `resilience4j.ratelimiter` / `resilience4j.timelimiter` / `resilience4j.bulkhead` / `resilience4j.thread-pool-bulkhead` / `resilience4j.micrometer.timer` / `resilience4j.thread`——最后一个是 Boot 虚拟线程开关绑定，`@since 3.0.0` 注释）。

## 3.5 Actuator：看状态、看事件、还能"手动熔断"

【源码证据】`resilience4j-spring-boot3/.../circuitbreaker/monitoring/endpoint/`（boot4 同构）：

| 端点 | 能力 | 证据 |
|---|---|---|
| `GET /actuator/circuitbreakers` | 每个实例的 failureRate/failureRateThreshold/slowCallRate/slowCallRateThreshold（百分比字符串）、bufferedCalls、slowCalls、failedCalls、notPermittedCalls、state | `CircuitBreakerEndpoint.java:44`（`@Endpoint(id = "circuitbreakers")`）、`createCircuitBreakerDetails:96-112` |
| `POST /actuator/circuitbreakers/{name}` | **写操作**：body 为 `CLOSE` / `FORCE_OPEN` / `DISABLE`——这就是运维侧的"恢复端点"（没有独立的 recovery auto-config） | `updateCircuitBreakerState:63-79` |
| `GET /actuator/circuitbreakerevents[/{name}[/{eventType}]]` | 三级事件查询，数据来自 `EventConsumerRegistry`（CircularEventConsumer 环形缓冲，容量 = `eventConsumerBufferSize`，默认 100） | `CircuitBreakerEventsEndpoint.java:45-86` |
| SSE 事件流 | `CircuitBreakerServerSideEvent`，条件 `@ConditionalOnClass({..., Flux.class, ReactorAdapter.class})` + `@ConditionalOnAvailableEndpoint` | `CircuitBreakerStreamEventsAutoConfiguration.java:41-55` |
| 健康指示器 | `management.health.circuitbreakers.enabled=true`（**无 matchIfMissing，不显式开启就没有**）+ 实例级 `registerHealthIndicator: true` 双重开关 | `CircuitBreakersHealthIndicatorAutoConfiguration.java:26`（boot3）、:24-26（boot4） |

装配条件是双层门：外层 `@ConditionalOnClass(Endpoint.class)` 内嵌类 + 每个 Bean `@ConditionalOnAvailableEndpoint`（CircuitBreakerAutoConfiguration.java:54-71）——`management.endpoints.web.exposure.include` 不暴露就整个不注册。

## 3.6 Micrometer 指标：publisher 模式与 legacy 模式

【源码证据】`CircuitBreakerMetricsAutoConfiguration`（boot3 :45-72）：外层 `@ConditionalOnClass({MeterRegistry, CircuitBreaker, TaggedCircuitBreakerMetricsPublisher})` + `@ConditionalOnProperty("resilience4j.circuitbreaker.metrics.enabled", matchIfMissing = true)`，内部二选一：

- 默认 **publisher 模式**：注册 `TaggedCircuitBreakerMetricsPublisher(meterRegistry)`，通过 registry 事件自动为新实例挂表；
- `resilience4j.circuitbreaker.metrics.legacy.enabled=true` 时切 **legacy 模式**：主动 `TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(...)` 绑定存量实例。

全局 tag 来自 `resilience4j.circuitbreaker.tags`（在 `Registry.of(configs, consumer, tags)` 时传入，CircuitBreakerConfiguration.java:150）——注意**不存在** `resilience4j.circuitbreaker.metrics.tags` 这种属性（grep 全仓无命中）。旧 Dropwizard（resilience4j-metrics）在 boot 模块没有任何自动配置，Micrometer 是唯一一等公民出口。

## 3.7 响应式支持：切面认识 Mono/Flux

**先说白话**：注解不是"阻塞世界专属"。切面在执行时按返回类型分派给一组 `XxxAspectExt` 扩展，响应式类型自动切换到 Reactor/RxJava 操作符。

【源码证据】`CircuitBreakerAspect.proceed()` 先遍历 `CircuitBreakerAspectExt` 列表（:116-123）；`ReactorCircuitBreakerAspectExt.canHandleReturnType` 识别 `Flux/Mono`（:38-42），其 `handle()` 调用 `proceed()` 拿到 publisher 后套 `transformDeferred(CircuitBreakerOperator.of(circuitBreaker))`（:51-69）——**订阅级保护**（2.9.2），不是阻塞包装。`CompletionStage` 由切面本体处理（:124-126 `executeCompletionStage`）。两个边界：

- `@TimeLimiter` 对非 CompletionStage、非 Mono/Flux 的返回值**直接抛 `IllegalReturnTypeException`**（TimeLimiterAspect.proceed + `IllegalReturnTypeException.java:18-23`）——同步方法想限超时，要么改异步要么换 TimeLimiter 编程式 API；
- 响应式 Bulkhead 走 `ReactorBulkheadAspectExt`（基于 BulkheadOperator，2.4.0 后非阻塞）。

手动路径即直接使用 `io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator` 等五个操作符（2.9.2）。

## 3.8 spring-boot4 模块解剖：v2.4.0 到底改了什么

**先说白话**：boot4 starter 不是把 boot3 改个包名，而是一次对 Boot 4 模块化重组的全面适配 + 一批 Boot 4 专属增强。以下差异全部来自对两个模块的目录级 diff 与源码阅读。

### 3.8.1 四个实质变化

1. **包重命名**：`io.github.resilience4j.springboot3.*` → `io.github.resilience4j.springboot.*`（模块名带 4、包名不带——迁移时所有 import 与全限定类名配置都要改）。
2. **自动配置结构重写**：boot3 用 `@Configuration + @Import(AbstractXxxConfigurationOnMissingBean)` 的"抽象中间类"模式（如 `AbstractCircuitBreakerConfigurationOnMissingBean.java:45-120`）；boot4 **删掉了全部 8 个 Abstract 中间类**，改为 `@AutoConfiguration` 类内持有一个 `CircuitBreakerConfiguration` 委托实例、每个 `@Bean` 方法自带 `@ConditionalOnMissingBean`（boot4 `CircuitBreakerAutoConfiguration.java:76-150`）。
3. **对 Boot 4 包迁移的适配**：metrics 自动配置的排序目标改为 `@AutoConfiguration(afterName = "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration", ...)`（boot4 的 CircuitBreakerMetricsAutoConfiguration，对应 Boot 4 新模块化包名；boot3 还是 `org.springframework.boot.actuate.autoconfigure.metrics.*`）；健康指示器换用 Boot 4 新 API `org.springframework.boot.health.actuate.endpoint.StatusAggregator`、`org.springframework.boot.health.contributor.HealthIndicator`（boot4 `CircuitBreakersHealthIndicatorAutoConfiguration.java:15-17`）。
4. **GraalVM 注册方式**：boot3 的 `NativeHintsConfiguration` 靠 `@ImportRuntimeHints`；boot4 改由 `META-INF/spring/aot.factories` 注册（内容：`org.springframework.aot.hint.RuntimeHintsRegistrar=io.github.resilience4j.springboot.nativeimage.configuration.NativeHintsConfiguration`）——为 AOT 处理阶段服务。hints 内容（两个模块相同）：为五个切面、`FallbackExecutor`/`FallbackMethod`、`AnnotationExtractor`、`MethodBasedEvaluationContext` 注册反射（`NativeHintsConfiguration.java:15-37`）。

### 3.8.2 boot4 独有：RefreshScope 注册表与启动自检

- **Spring Cloud Config 刷新友好**：新增 5 个 `XxxRefreshScopedRegistryAutoConfiguration`（bulkhead/circuitbreaker/ratelimiter/retry/timelimiter），以 `@AutoConfiguration(before = XxxAutoConfiguration.class, after = RefreshAutoConfiguration.class)` + `@ConditionalOnClass({CircuitBreaker.class, RefreshScope.class})` + `@ConditionalOnBean(RefreshScope.class)` 注册 `@RefreshScope` 修饰的 Registry Bean 覆盖默认实现（`CircuitBreakerRefreshScopedRegistryAutoConfiguration.java:29-56`）——配置中心热刷新时 Registry 随之重建。
- **装错 starter 快速失败**：`SpringBoot4Verifier` 要求 Boot 主版本 == 4（`.../verifier/autoconfigure/SpringBoot4Verifier.java:10-17`），由 `SpringBoot4VerifierAutoConfiguration.java:16-26` 在其余自动配置之前执行，不匹配抛 `IncompatibleSpringBootVersionException`。boot3 的对应 Verifier 要求 ==3 且 !=4（`SpringBoot3Verifier.java:12-19`）。**这套自检是 2.4.0 才有的**（随 f61f9aa1 引入；v2.3.0 的 boot3 模块没有 Verifier——这正是 sc-circuitbreaker 5.0.x 拉着 boot3 starter 2.3.0 在 Boot 4 上"不报错但功能降级"的原因，见 3.10.4）。

其余行为（切面、配置合并、事件缓冲 100、健康双重开关等）在 boot3/boot4 语义一致——因为它们都委托给同一个 spring6 层。

## 3.9 Spring Cloud CircuitBreaker：编程式门面与 Feign 的落点

> 同系列《Spring Cloud CircuitBreaker.md》已对 commons 抽象层、BulkheadProvider 与双 retry 适配做完整解析；本节聚焦 Resilience4j 实现本体与 Boot 4 版本考古。

### 3.9.1 抽象在 spring-cloud-commons

【源码证据】`spring-cloud-circuitbreaker` 仓库（main 5.1.0-SNAPSHOT）根 pom 的 modules（:121-128）只有 resilience4j 实现、starter、spring-retry/framework-retry 与 docs——**核心抽象 `CircuitBreaker`、`CircuitBreakerFactory`、`ReactiveCircuitBreakerFactory`、`Customizer`、`ConfigBuilder` 都定义在 spring-cloud-commons 的 `org.springframework.cloud.client.circuitbreaker.*` 包**（Resilience4JCircuitBreaker.java:33 的 import 为证；commons 版本 5.1.0-SNAPSHOT，根 pom :96）。`factory.create(id)` / `create(id, groupName)` 就是 OpenFeign/RestClient 熔断的最终调用点（`CircuitBreakerNameResolver` 定义在 openfeign 仓库，本仓库 grep 无命中）。

### 3.9.2 Resilience4JCircuitBreaker#run：装饰链的又一处实证

【源码证据】`.../resilience4j/Resilience4JCircuitBreaker.java:97-141`（配置了线程池时）：

```java
Optional<TimeLimiter> timeLimiter = loadTimeLimiter();
io.github.resilience4j.circuitbreaker.CircuitBreaker defaultCircuitBreaker =
    registry.circuitBreaker(this.id, this.circuitBreakerConfig, tags);
...
Supplier<Future<T>> futureSupplier = () -> executorService.submit(toRun::get);
Callable<T> timeLimitedCall = timeLimiter
    .map(tl -> TimeLimiter.decorateFutureSupplier(tl, futureSupplier))
    .orElse(() -> futureSupplier.get().get());
Callable<T> bulkheadCall = bulkheadProvider.decorateCallable(this.groupName, tags, timeLimitedCall);
Callable<T> circuitBreakerCall = io.github.resilience4j.circuitbreaker.CircuitBreaker
    .decorateCallable(defaultCircuitBreaker, bulkheadCall);
return getAndApplyFallback(circuitBreakerCall, fallback);   // :143-159，捕获一切 Throwable
```

组合顺序：**线程池 submit → TimeLimiter → Bulkhead → CircuitBreaker → fallback**。四个要点：

- **TimeLimiter 默认 1 秒**：`loadTimeLimiter()`（:161-168）按 id → group → 新建，配置用 `TimeLimiterConfig.ofDefaults()`——Feign 调慢接口时，**先撞 1s 超时再谈熔断**，这是 sc-circuitbreaker 用户最常见的"为什么一秒就失败了"之谜；
- `disableThreadPool = true` 时走 :118-122 分支：Bulkhead → CB，**没有 TimeLimiter**（没有 Future 就没法限时）；`disableTimeLimiter` 时 `loadTimeLimiter()` 返回 empty（:162-164），TimeLimiter 位置退化为无超时的 `future.get()`；
- **group 是 tag 不是前缀字符串**：`CIRCUIT_BREAKER_GROUP_TAG = "group"`（:44），tags = `Map.of("group", groupName)`（:84, :99），group 名同时是 Bulkhead 与 TimeLimiterRegistry 的查找 key——网上流传的 "group:id" 字符串格式并不存在于源码；
- fallback 兜一切 Throwable（:143-159），所以编程式门面的 fallback 天然覆盖熔断拒绝。

### 3.9.3 Factory、属性与定制 API

`Resilience4JCircuitBreakerFactory`（:49-219）：内部持有 `CircuitBreakerRegistry.ofDefaults()` + `TimeLimiterRegistry.ofDefaults()`（:58-60，自动配置会把它们替换成 Spring 容器里的 Registry Bean）；`create(id)` 的 group 默认等于 id（:134-138）；每个 group 缓存独立线程池（:64, :144-145）。配置查找优先级（:177-213）：`customize(id)` 注册的配置 → Registry 中该 id 的配置 → group 配置 → `configureDefault`；TimeLimiter 同序且每个 id 只 WARN 一次（:184-199）。定制 API：`addCircuitBreakerCustomizer`（:158-162）、`configureExecutorService`、`configureGroupExecutorService`、`configureDefault`、`configureCircuitBreakerRegistry`。

属性前缀 `spring.cloud.circuitbreaker.resilience4j`（`Resilience4JConfigurationProperties.java:27-40`，引入时间经 `git log -S` 实证）：

| 属性 | 默认 | 引入 |
|---|---|---|
| enableGroupMeterFilter | true | 2022-02（#121，group tag + MeterFilter） |
| defaultGroupTag | "none" | 同上 |
| enableSemaphoreDefaultBulkhead | false | 2022-02（`4093c3f`） |
| disableThreadPool | false | 2022-05（`e620983`） |
| disableTimeLimiter（及 disableTimeLimiterMap） | false | 2024-07（`ce4eadf`，#202） |

Bulkhead 信号量/线程池的选择规则（`Resilience4jBulkheadProvider.useSemaphoreBulkhead`）：线程池 Registry 里存在该 id → 强制线程池；否则 `enableSemaphoreDefaultBulkhead || 信号量 Registry 里存在该 id` → 信号量；否则线程池。

响应式版 `ReactiveResilience4JCircuitBreaker`（:87-155）：Bulkhead（恒信号量 + BulkheadOperator）→ `CircuitBreakerOperator` → **`.timeout(timeoutDuration)` + `doOnError(TimeoutException, t -> cb.onError(...))`**（:99-107 的注释明说：因为用 Reactor 的 timeout 而非 TimeLimiter 装饰器，超时错误要手工记入熔断统计）→ `onErrorResume(fallback)`。

### 3.9.4 版本考古：Boot 4 适配的时间线（选型关键事实）

对本地仓库各 tag 的 pom 逐一取证：

| sc-circuitbreaker 版本 | 时间 | 依赖的 r4j 构件 | 说明 |
|---|---|---|---|
| v5.0.0（2025.1.0 train 首发） | 2025-11 | **resilience4j-spring-boot3** | 2025.1 train 是 Boot 4 train，但依赖仍指 Boot 3 线 starter |
| v5.0.3（2025.1.x 补丁） | 2026-08-03 | resilience4j-spring-boot3（BOM `resilience4j.version=2.3.0`） | 2.3.0 **没有 Verifier**（`git show v2.3.0` 证实），在 Boot 4 上不会启动报错；但其 Boot 3 专属自动配置（健康、指标排序等引用旧包名的类）会被 Boot 4 的条件装配静默跳过——工厂/切面核心链路可用，观测面残缺 |
| v5.1.0-M1 | 2026-09-18 | **resilience4j-spring-boot4**（BOM `resilience4j.version=2.4.0`） | 切换到 Boot 4 原生 starter；BOM 显式管理 r4j-spring-boot4（r4j 自家 BOM 遗漏了它，resilience4j#2427） |
| main（5.1.0-SNAPSHOT） | 2026-10 | resilience4j-spring-boot4 2.4.0 | 相关迁移提交：`b29b754`（Boot 4 snapshot）、`001f954`（Boot 4 结构性变更适配 #241）、`e6abd97`（Boot 4.0.1 依赖修复）、`0c1d797`（默认 TimeLimiter 配置回归修复，2026-06-22） |

另有一个无害但讽刺的细节：`Resilence4JMetricsOrderingAutoConfiguration.java:26-33` 的排序目标仍写着 boot3 时代的类名字符串 `io.github.resilience4j.springboot3.timelimiter.autoconfigure.TimeLimiterMetricsAutoConfiguration`——在 Boot 4 场景下这个排序提示指向不存在的类名，只是失效（不影响功能）。sc-circuitbreaker 仓库本身没有任何 `@Endpoint`（grep 全模块零命中）——`/actuator/circuitbreakers` 等全部来自 r4j starter 自带端点（3.5 节）。

## 3.10 Spring Framework 7 的内建韧性：第三个"选项"

**先说白话**：Spring Framework 7.0 在 spring-core 里新增了 `org.springframework.resilience` 包——`@Retryable`（重试）与 `@ConcurrencyLimit`（并发上限）注解、`@EnableResilientMethods` 开关、`RetryAnnotationBeanPostProcessor`/`ConcurrencyLimitBeanPostProcessor` 织入、`MethodRetrySpec`/`SimpleRetryInterceptor` 执行（本地 spring-framework 仓库 `spring-core/src/main/java/org/springframework/resilience/` 下 20 个文件，含响应式 RetryInterceptor；包在 v6.2.19 不存在、v7.0 出现，详见同系列《Spring Framework.md》1.6 节）。

它的定位要与 Resilience4j 分清楚：**只有"重试 + 并发上限"两个场景**，没有熔断、限流、舱壁、超时；启用方式是显式 `@EnableResilientMethods`——而且**Boot 4.1.x 没有为它提供任何自动装配或 starter**（spring-boot 4.1.x 分支 `git grep "EnableResilientMethods\|@Retryable"` 与 `git grep -il "resilience4j"` 全部零命中）。所以它是"纯重试/限并发"场景下可以免依赖使用的官方轻方案，与 Resilience4j 是互补而非竞争关系；一旦需要熔断或限流，仍然回到 Resilience4j（或 Sentinel）。

## 3.11 本章小结：集成层十大陷阱速查

1. **装错 starter 直接启动失败**：boot4 的 `SpringBoot4Verifier` 要求 Boot==4；包名是 `io.github.resilience4j.springboot.*`（无 "4"），从 boot3 迁移要改 import。
2. **没引 `spring-boot-starter-aop`，注解静默失效**：切面 Bean 被 `AspectJOnClasspathCondition` 守卫，条件不满足不注册、不报错。
3. **切面默认顺序 Retry 最外层**：`@Retry + @CircuitBreaker` 同方法时熔断按每次 attempt 记账；用 `resilience4j.circuitbreaker.circuit-breaker-aspect-order` 调整。
4. **fallbackMethod 配错只 WARN**，且多个注解共用 fallback 可能被执行多次；`beanName::methodName` 语法要求 Spring Bean 环境。
5. **`@TimeLimiter` 用在同步方法上抛 `IllegalReturnTypeException`**。
6. **`configs.default` 只向实例合并 4 个元属性**（health/eventBuffer 等），其余参数靠 `baseConfig` 继承链——"模板里的 registerHealthIndicator 不生效"由此而来。
7. **健康指示器双重开关**：`management.health.circuitbreakers.enabled=true`（无 matchIfMissing）+ 实例级 `registerHealthIndicator: true`，缺一没有健康端点。
8. **熔断的手动恢复是写操作**：`POST /actuator/circuitbreakers/{name}`，body 为 CLOSE/FORCE_OPEN/DISABLE，前提是暴露了 circuitbreakers/circuitbreakerevents 端点。
9. **sc-circuitbreaker 默认 TimeLimiter 只有 1s**：Feign 慢接口先撞超时；`disableThreadPool=true` 时干脆没有 TimeLimiter；group 是 tag 而非 "group:id" 字符串。
10. **sc-circuitbreaker 5.0.x（2025.1 train）依赖的是 r4j-spring-boot3 2.3.0**：Boot 4 上核心可用但 Boot 3 专属自动配置静默失效；要完整体验等 5.1.x（2026.0 train，已依赖 r4j-spring-boot4 2.4.0）或直接用 r4j-spring-boot4。

---

# 四、对照解析：Sentinel（tag 1.8.10）眼中的同一个问题域

> 本章对应源码：`D:\code\3rd\sentinel`（HEAD 即 tag 1.8.10，pom revision 1.8.10，发布提交 2026-05-21）与 `D:\code\3rd\spring-cloud-alibaba`（2026.0.x 分支，SCA revision 2026.0.0.0-SNAPSHOT，parent spring-cloud-build 5.1.0-M1，`spring-boot.version=4.2.0-M2`，`sentinel.version=1.8.10`，均 pom 实测）。本章是服务选型的对比视角浓缩；Sentinel 的全量源码解析见同系列《Spring Cloud Alibaba Sentinel.md》。本章目的不是 Sentinel 全解，而是**给第三章的每个"Resilience4j 怎么做"配上一个"Sentinel 怎么做"**，为第五章选型提供证据。

**先交代一个考古发现**：1.8.10 这个 tag 里已经混入了 2.0 重构的产物——限流控制器是 `DefaultController`（旧的 `DefaultTrafficShapingController` 已不存在）、匀速排队是 `ThrottlingController`（javadoc 自述 "Refactored from legacy RateLimitController of Sentinel 1.x"、`@since 2.0`，`ThrottlingController.java:25-43`）、还出现了 `@since 2.0` 的 `DefaultCircuitBreakerSlot.java:37` 与尚无调用方的 `tokenbucket` 包。这佐证了 Sentinel 正在酝酿 2.0 大版本，1.8.x 处于"慢速维护 + 重构过渡"状态。

## 4.1 架构模型：全局槽位链 vs 实例装饰器

**先说白话**：Resilience4j 是"你 new 出来一层层包上去的对象"；Sentinel 是"埋一个资源名，框架在调用线程上跑一条全局注册的责任链"。这是两个物种级别的差异。

【源码证据】Sentinel 侧：

- 入口是 `SphU.entry(name)`（`sentinel-core/.../SphU.java:84`），阻塞抛 `BlockException` 族；`CtSph.entryWithPriority`（`CtSph.java:117`）取出该资源共享的 `ProcessorSlotChain` 并 `chain.entry(...)`（:145），责任链由 JDK 式 SPI 按 order 排序构建（`DefaultSlotChainBuilder.java:39-53`，`SpiLoader.of(ProcessorSlot.class).loadInstanceListSorted()`）；
- 槽位顺序硬编码于 `Constants.java:76-84`（@Spi 注解行号实证）：

| Slot | order | 职责 |
|---|---|---|
| NodeSelectorSlot | -10000 | 按 context 构建调用树 DefaultNode |
| ClusterBuilderSlot | -9000 | **同一资源全局共享一个 ClusterNode**（类注释 55-66 行明说） |
| LogSlot | -8000 | 捕获 BlockException 记日志 |
| StatisticSlot | -7000 | 滑动窗口记账（PASS/BLOCK/EXCEPTION/SUCCESS/RT） |
| AuthoritySlot | -6000 | 黑白名单 |
| SystemSlot | -5000 | 系统自适应保护 |
| ParamFlowSlot（扩展模块） | -3000 | 热点参数限流 |
| FlowSlot | -2000 | 流控规则 |
| DefaultCircuitBreakerSlot（@since 2.0） | -1500 | 无规则资源的默认熔断兜底 |
| DegradeSlot | -1000 | 熔断降级规则 |

对照第三章：Resilience4j 的切面链（Retry→CB→…）是**每个 Bean 方法自己的装饰结构**，实例间零共享；Sentinel 的槽位链是**进程级单例**，所有调用同一资源的代码路径共享同一份统计与规则。由此派生出两个模型的一切分野：

| 维度 | Resilience4j | Sentinel |
|---|---|---|
| 保护单位 | 你显式创建的容错器实例（Registry 按名索引） | 字符串资源名（自动建 Node/ClusterNode） |
| 统计归属 | 实例私有 | 资源全局共享（ClusterNode）+ context 调用树 |
| 失败语义 | `CallNotPermittedException` / `BulkheadFullException` / `RequestNotPermitted` / `TimeoutException` 分设备抛出 | 统一 `BlockException` 族（Flow/Degrade/Authority/ParamFlow/System），`@SentinelResource` 里 blockHandler 与 fallback **双通道分流**（`SentinelResourceAspect.java:56-68`：BlockException → blockHandler，业务异常 → fallback） |
| 线程模型 | 判定在调用线程；Bulkhead 可选线程池隔离 | 全部判定在调用线程同步内联（`CtSph.java:145`），无自带线程隔离，但可主动排队睡眠（`DefaultController.java:59-61` 的 prioritized 借窗口 `Thread.sleep`） |
| 内核依赖 | 零依赖 | 零依赖（`sentinel-core/pom.xml` 主依赖全为 test scope） |

## 4.2 统计与流控：滑动窗口同源，流控能力分野

**统计引擎**其实是近亲：Sentinel 的 `LeapArray`（`slots/statistic/base/LeapArray.java:41`）与 Resilience4j 的 `SlidingTimeWindowMetrics` 都是"时间分桶 + 环形数组 + CAS 复用头部桶"。默认粒度（`StatisticNode.java:96-108`）：秒级 **2 个 500ms 桶**（`SampleCountProperty=2`、`IntervalProperty=1000ms`）、分钟级 60 个 1s 桶；每桶是按 `MetricEvent` 索引的 `LongAdder[]`（`MetricBucket.java:30`）。两个 Resilience4j 没有的花样：`OccupiableBucketLeapArray` 支持 prioritized 请求**预支未来窗口配额**（`addWaiting`，:79-83，borrow 语义）；分钟级长效窗口用于趋势统计。

**流控对比**（Resilience4j RateLimiter vs Sentinel FlowSlot）：

| 能力 | Resilience4j `RateLimiter` | Sentinel `FlowRule` |
|---|---|---|
| 限流维度 | 周期配额（limitForPeriod / limitRefreshPeriod / timeoutDuration） | QPS 或并发线程数（`grade`，`FlowRule.java:52`） |
| 流量整形 | 无（只有"等或拒"） | 四种 controlBehavior：立即拒绝（`DefaultController`）、**预热**（`WarmUpController`，注释自述移植 Guava RateLimiter 公式，coldFactor=3）、**匀速排队**（`ThrottlingController` 漏桶，`latestPassedTime.addAndGet(costTimeNs)` 竞争）、预热+排队 |
| 关联/链路 | 无 | `strategy`：关联资源（A 被打爆限 B）、链路限流（`FlowRuleChecker.java:122-150`） |
| 集群模式 | 无（单机语义） | `clusterMode` → TokenService 向嵌入/独立 Token Server 全局记账（`FlowRuleChecker.java:147-184`），失败可回退本地 |
| 热点参数 | 无 | `ParamFlowSlot`（order -3000）：按**参数值**维度记账限流（`ParameterMetric.java:49-58` 以参数值为 CacheMap 键） |

一句话：**Resilience4j 的 RateLimiter 是"单机周期配额原语"；Sentinel 的 FlowSlot 是"单机 + 集群、可整形的流量治理规则集"**。Resilience4j 完全没有预热、排队、关联资源、热点参数这四类能力。

## 4.3 熔断对比：探针窗口 vs 单探针

两者都是"CLOSED/OPEN/HALF_OPEN 三态 + 半开试探"，但试探粒度与恢复策略不同：

| 维度 | Resilience4j `CircuitBreaker`（2.3 节） | Sentinel `DegradeRule`（1.8.10） |
|---|---|---|
| 触发指标 | **失败率 + 慢调用率双阈值**同时评估（同一实例） | 三选一的规则类型：RT 超限（`DEGRADE_GRADE_RT`）/ 异常比例 / 异常数（`DegradeRule.java:59-96`），内部对应 `ResponseTimeCircuitBreaker` / `ExceptionCircuitBreaker` 两实现 |
| 统计窗口 | count-based（默认 100 窗口）或 time-based（秒桶），最少样本 `minimumNumberOfCalls=100` | 熔断器私有窗口 `statIntervalMs` 默认 **1000ms**，最少请求 `minRequestAmount=5`——**默认配置比 R4j 激进得多** |
| 半开试探 | 定容探针窗口：放行 `permittedNumberOfCallsInHalfOpenState=10` 个，按这批的成功率批量判定回 CLOSED/OPEN | **单探针 CAS**：`fromOpenToHalfOpen`（`AbstractCircuitBreaker.java:88-96`）`compareAndSet(OPEN, HALF_OPEN)` 只放一个请求，成功 `fromHalfOpenToClose`、失败回 OPEN（探针自身被 block 也回 OPEN，issue #1638 修复） |
| OPEN 等待 | `waitDurationInOpenState`（默认 60s）+ 可选 **IntervalFunction 指数/随机退避**（越熔越久） | `timeWindow` 固定秒数（`recoveryTimeoutMs = timeWindow * 1000`），无退避 |
| 半开超时 | 2.4.0 `maxWaitDurationInHalfOpenState` + 可转 CLOSED | 无（单探针立即出结果） |
| 运维干预 | 六态含 `FORCED_OPEN`/`DISABLED`/`METRICS_ONLY`，actuator POST 强制开/关/停 | 规则增删即状态效果（删规则=解除），dashboard 一键降级 |

语义差别值得一记：**Sentinel 的半开是"一次探测一锤定音"**（冷启动抖动可能引发回弹），**Resilience4j 的半开是"10 发子弹的弹匣"**（更平滑但要配好 10 这个数）；Sentinel 的默认 5 请求/1 秒窗口对低流量接口更敏感，Resilience4j 默认 100 样本更保守。

## 4.4 Sentinel 独有的三张牌（Resilience4j 领地之外）

1. **系统自适应保护**（`SystemSlot`，order -5000）：监控整机 load/CPU/平均 RT/入口线程数/入口 QPS（后台单线程采样，`SystemRuleManager.java:92` + `SystemStatusListener.java:52-56` 读 `OperatingSystemMXBean`），其中 maxThread 检查是 **BBR 式容量估计**——`checkBbr`（`SystemRuleManager.java:334-339`）：`currentThread > ENTRY_NODE.maxSuccessQps() * minRt() / 1000` 则拒绝。这是"保护自己这台机器"的能力，Resilience4j 完全没有整机/进程级指标。
2. **热点参数限流**（`ParamFlowSlot`，order -3000）：对"同一接口中某个具体参数值"（如某个 userId）单独设 QPS，`SphU.entry(name, args)` 传参 + `paramIdx` 指定参数位（`ParamFlowSlot.java:63-90`），支持按参数值的例外条目 `paramFlowItemList`。
3. **集群流控**（`clusterMode`）：Token Server 全局记账，天然适合"接口全局 1000 QPS 不能靠 10 个实例各限 100"的场景（各自限流会因流量不均而失效）。

外加一张小牌：AuthoritySlot 黑白名单（按 origin 放行/拒绝）。这些能力的共同代价是**需要一个规则来源与运维界面**——这正是 4.5 的话题。

## 4.5 治理生态：动态规则平台 vs 构造期配置的库

**Sentinel 是平台型**。规则是运行时可变的外部数据：`RuleManager.updateRules` 整体热替换（1.8.10 的 `RuleManager.java:30-47`，支持正则资源名）；`ReadableDataSource` 监听配置中心——SCA 侧把 `spring.cloud.sentinel.datasource.<name>.*`（nacos/apollo/zookeeper/consul/file/redis）绑定成 FactoryBean（`SentinelDataSourceHandler.java:51,85-105`，`NacosDataSource.java:129` 的 `configService.addListener` 即推送通道）；**SCA 全仓库没有任何 WritableDataSource**——写规则永远走配置中心/Dashboard，客户端只读。`sentinel-dashboard` 是 sentinel 仓库里的独立 Spring Boot 应用，经 `sentinel-transport-simple-http` 心跳（默认 10s，`SimpleHttpHeartbeatSender.java:36-40`）+ 命令端口拉取指标、下发规则。Web 埋点由 `SentinelWebInterceptor`（`sentinel-spring-webmvc-v6x-adapter`，jakarta 版）自动注册，`webContextUnify=true` 把所有 URL 收进统一 context（`SentinelWebMvcConfig.java:43`）；Feign 由 `SentinelFeign`（替换 InvocationHandler，`SentinelFeign.java:129-140`）接管。适配器矩阵覆盖 gRPC/Dubbo/OKHttp/Gateway/Quarkus 等 22+ 入口。

**Resilience4j 是库型**。所有配置在**构造期**由 YAML → properties → Registry 固化（3.4 节），运行期官方开口只有三类：代码里改（`registry.circuitBreaker(...)`/`changeConfig`）、Spring Cloud Config 的 RefreshScope（boot4 专属，3.8.2）、actuator 对熔断状态的三个写操作（CLOSE/FORCE_OPEN/DISABLE，3.5 节）。它**没有控制台、没有规则中心、没有动态下发协议**——观测靠 Micrometer + Actuator（3.6/3.5），治理靠你自己的配置体系。

这组对比直接决定第五章的选型答案：**缺治理平台时，Sentinel 的平台能力用不起来；有治理平台时，Resilience4j 的"静态"变成短板**。

**版本健康度**（git 实证）：Sentinel 1.8.8（2024-06-04）→ 1.8.9（2025-10-17）→ 1.8.10（2026-05-21），节奏放缓且处于 2.0 重构过渡期；Resilience4j v2.3.0（2025-01-03）→ v2.4.0（2026-03-14）→ 47 个活跃提交（JDK 21 + 虚拟线程 + 非阻塞 Bulkhead），迭代明显更活跃。

## 4.6 维度对比总表

| 维度 | Resilience4j 2.4.x | Sentinel 1.8.10 |
|---|---|---|
| 形态 | 库（装饰器对象，实例化即用） | 库内核 + 控制台 + 配置中心生态（槽位链埋点） |
| 内核第三方依赖 | 0（Vavr 已移除） | 0 |
| 接入方式 | 注解（AOP）/编程式/Reactor Operator | 注解（@SentinelResource）/SphU API/Web 拦截器自动埋点 |
| 熔断 | 双阈值（失败率+慢调用率），10 发探针窗口，指数退避开闸 | 三类规则（RT/异常比例/异常数），单探针 CAS，固定恢复窗 |
| 限流 | 周期配额原语 | QPS/线程数 + 预热/匀速/关联/链路 + 集群模式 |
| 隔离 | 信号量或独占线程池舱壁 | 无自带线程隔离（可配 adaptive/系统保护兜底） |
| 重试 | 内建 Retry（退避、结果重试、负间隔终止） | 无独立重试模块（配合 spring-retry 等） |
| 超时 | 内建 TimeLimiter | 无（依赖客户端/框架超时） |
| 系统自适应 / 热点参数 / 黑白名单 | 无 | SystemSlot BBR / ParamFlowSlot / AuthoritySlot |
| 规则动态性 | 构造期固化（+RefreshScope/actuator 少量开口） | 运行时热更新，配置中心推送为标准姿势 |
| 治理界面 | 无（Actuator + Micrometer 自建） | sentinel-dashboard 开箱 |
| 响应式 | 五大设备各一个 Reactor Operator，取消归还许可 | sentinel-reactor-adapter 适配器 |
| 指标 | Micrometer 一等公民（publisher/legacy 双模式） | 自有指标落盘 + dashboard 拉取（无原生 Micrometer 绑定） |
| JDK 基线 | 17（v2.4.0）/ 21（HEAD，虚拟线程工厂） | 1.8（部分模块 17） |
| Spring Boot 4 支持 | **resilience4j-spring-boot4 2.4.0（GA，2026-03）** | SCA 面向 Boot 4 的版本线处于 **5.1.0-M1 里程碑**（目标 Boot 4.2.0-M2，2026-09） |
| 学习/运维成本 | 低（5 个概念 + YAML） | 中高（资源/槽位/规则/控制台/配置中心一套体系） |
| 甜点区 | 单体/中小微服务、以代码为中心的团队、K8s 原生栈 | 大型微服务平台、需要统一治理与运营调参的组织 |

---

# 五、选型建议：Spring Boot 4.1.1 的新应用怎么选

## 5.1 先厘清四条技术路线

| 路线 | 构件 | 维护方 | Boot 4.1.1 适配状态（实证） |
|---|---|---|---|
| ① 注解 starter | `io.github.resilience4j:resilience4j-spring-boot4:2.4.0` | Resilience4j 官方 | **GA**（2026-03-14 发布，代码 2025-12-28 落地；构建基线 Boot 4.0.0/Framework 7.0.2；`SpringBoot4Verifier` 要求 Boot 主版本==4，4.1.1 通过自检） |
| ② Spring Cloud 门面 | `spring-cloud-starter-circuitbreaker-resilience4j`（2025.1.x train → sc-circuitbreaker 5.0.3） | Spring Cloud | 2025.1 train 是 Boot 4 train，但 5.0.x 依赖的是 **r4j-spring-boot3 2.3.0**（v5.0.3 pom + BOM 实测）：工厂/切面核心链路可用，Boot 3 专属自动配置（健康、指标排序等）被静默跳过；主线 5.1.0-M1（2026-09-18）起切换 r4j-spring-boot4 2.4.0 |
| ③ Sentinel 全家桶 | `spring-cloud-starter-alibaba-sentinel`（SCA 2026.0.0.0 线，parent 5.1.0-M1） | 阿里/Spring Cloud Alibaba | **里程碑**：SCA main 目标 `spring-boot.version=4.2.0-M2`、`spring.cloud.version=2026.0.0-M1`、sentinel 1.8.10；面向 Boot 4 的 GA 尚未出现 |
| ④ Framework 7 内建韧性 | `spring-core` 的 `org.springframework.resilience`（`@Retryable`/`@ConcurrencyLimit`） | Spring 官方 | 类库可用（Framework 7.0/7.1 随 Boot 4.1 而来），但 **Boot 4.1.x 无自动装配**（spring-boot 仓库 grep 零命中），需显式 `@EnableResilientMethods`；且只有重试 + 并发上限两个场景 |

## 5.2 结论：推荐 Resilience4j，以路线 ① 为主、按需叠加 ②

**如果你现在（2026-10）用 Spring Boot 4.1.1 起一个新应用，推荐引入 `resilience4j-spring-boot4:2.4.0`**；需要声明式 Feign/OpenFeign 熔断或编程式门面时，再叠加 Spring Cloud 的 circuitbreaker starter（并知悉 5.0.x 的 r4j-spring-boot3 依赖细节）。理由六条：

1. **适配完成度**：这是唯一在 GA 版本里为 Boot 4 重写过自动配置的方案——包适配、Boot 4 新健康/指标 API、`@AutoConfiguration` 委托模式、RefreshScope、AOT hints 全部就位（3.8 节），还有装错版本快速失败的自检兜底。
2. **生态方向一致**：Spring Cloud 2025.1 版本线把 Boot 4 当一等公民，sc-circuitbreaker 主线已切换到 r4j-spring-boot4（3.9.4）；Resilience4j 是 Boot 4 时代的"顺势"默认，社区教程、start.spring.io 组合、Issue 响应都围绕它。
3. **Sentinel 侧时机未到**：面向 Boot 4 的 SCA 仍是 M1 里程碑（目标甚至是 4.2.0-M2），sentinel-core 1.8.x 处于慢速维护 + 2.0 重构过渡（4.5 节）。新应用押注里程碑版本，等于把稳定性基建建立在一个正在改地基的生态上。
4. **新应用多半没有治理平台**：Sentinel 的一半价值（控制台、动态规则推送、热点/系统自适应运营）依赖 Nacos/Dashboard 配套。没有这套设施时，Sentinel 退化为"注解埋点的限流熔断库"，与 Resilience4j 的差距大幅缩小，而运维成本（传输端口、心跳、规则存储）一分不少。
5. **观测与云原生组合度**：Micrometer/Actuator/AOT-GraalVM 原生集成（3.5/3.6/3.8），指标直接进 Boot 4.1 的指标体系；K8s 探针场景下健康指示器开箱即用。
6. **心智成本**：五个容错器 + 一张 YAML 就是全部；纯"重试几次"的小场景还可以叠加 Framework 7 内建 `@Retryable`（零依赖，路线 ④），但建议统一在 Resilience4j 一套体系内，避免两套重试语义并存。

## 5.3 落地建议（可直接抄的清单)

**依赖（Maven）**：

```xml
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-spring-boot4</artifactId>
    <version>2.4.0</version>
</dependency>
<!-- 注解功能硬依赖：Boot 4 中 AOP starter 已更名为 aspectj（旧名 starter-aop 不存在） -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aspectj</artifactId>
</dependency>
<!-- 可选：观测与运维 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

**配置（application.yaml，随手可用的默认调优版）**：

```yaml
resilience4j:
  circuitbreaker:
    tags:
      env: prod                      # 全局 tag（进 Micrometer）
    instances:
      payment:
        sliding-window-type: TIME_BASED
        sliding-window-size: 60       # 统计最近 60 秒
        minimum-number-of-calls: 30   # 低流量接口把 100 降下来，否则永远不开闸
        failure-rate-threshold: 50
        slow-call-duration-threshold: 2s
        slow-call-rate-threshold: 80
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 5
        automatic-transition-from-open-to-half-open-enabled: true  # 无流量时也按时转半开
        register-health-indicator: true
  timelimiter:
    instances:
      payment:
        timeout-duration: 3s
  retry:
    instances:
      payment:
        max-attempts: 3
        wait-duration: 500ms
        enable-exponential-backoff: true
        exponential-backoff-multiplier: 2.0
        retry-exceptions:
          - java.io.IOException
        ignore-exceptions:
          - com.example.BusinessException

management:
  endpoints:
    web:
      exposure:
        include: health,circuitbreakers,circuitbreakerevents
  health:
    circuitbreakers:
      enabled: true                   # 无 matchIfMissing，必须显式开
```

**五个建议随手做**：

- **把熔断调到重试外层**（语义通常更对）：`resilience4j.circuitbreaker.circuit-breaker-aspect-order: 2147483641`（比 Retry 默认的 `LOWEST_PRECEDENCE-5 = 2147483642` 小即可，见 3.3 节）。
- **虚拟线程应用选信号量舱壁**：`spring.threads.virtual.enabled=true` 时优先 `SemaphoreBulkhead`（零线程成本）；FixedThreadPoolBulkhead 的虚拟线程工厂在 2.4.0 **尚未发布**（HEAD 提交 `b999ae19`，属下个版本），2.4.0 上它是平台线程池。
- **fallback 只声明在最外层注解**，并用 actuator `GET /actuator/circuitbreakers` 验证 fallbackMethod 拼写（3.2.3 的 warn-only 陷阱）。
- **用 sc-circuitbreaker 时**：`configureDefault` 里把 TimeLimiter 调到大于 Feign 读超时（默认 1s 的坑，3.9.2），或干脆 `spring.cloud.circuitbreaker.resilience4j.disable-time-limiter: true`；版本上 2025.1.x 可用但知悉 3.9.4 的降级细节，紧追 5.1.0 GA。
- **GraalVM/AOT 应用**无需额外动作（boot4 模块自带 aot.factories hints，3.8.1），但 properties 绑定类的 hints 依赖 Boot 自身 AOT。

## 5.4 什么情况下仍然选 Sentinel

以下条件命中越多，越值得为 Sentinel 承担"里程碑依赖或自建适配"的成本：

1. 组织已有 **Nacos + Sentinel Dashboard 的统一治理体系**，需要规则热推送、运营人员一键降级；
2. 需要**系统自适应保护**（按 load/CPU 保护本机）、**热点参数限流**（按 userId/itemId 维度）、**集群流控**（全局 QPS 精确分配）这三张 Resilience4j 没有的牌（4.4 节）；
3. 多语言/网关侧需要同一套全局限流策略（Sentinel 的 Go/C++ 版与 Gateway 适配器矩阵）；
4. 接受变通方案：在 Boot 4.1.1 上**裸用 sentinel-core 1.8.10 + 自建注解/Web 适配**（sentinel-core 零依赖、JDK 1.8 起步，本身与 Boot 无关，坑集中在 SCA starter 的适配层）。

反过来，单体应用、中小规模微服务、以开发者为中心（配置即代码）的团队、K8s 原生部署——Resilience4j 都是阻力最小的选择。

---

# 六、附录

## 6.1 关键类速查表

| 想看什么 | 去哪看 |
|---|---|
| 熔断状态机全貌 | `resilience4j-circuitbreaker/.../internal/CircuitBreakerStateMachine.java`（1210 行， ClosedState:594 / OpenState:687 / HalfOpenState:1079） |
| 失败率/慢调用率阈值引擎 | 同模块 `internal/CircuitBreakerMetrics.java:141-176` |
| 滑动窗口实现 | `resilience4j-core/.../metrics/FixedSizeSlidingWindowMetrics.java`、`SlidingTimeWindowMetrics.java`、`LockFree*`（2.3.0+） |
| 限流周期数学 | `resilience4j-ratelimiter/.../internal/AtomicRateLimiter.java:227-251` |
| 线程池舱壁 | `resilience4j-bulkhead/.../internal/FixedThreadPoolBulkhead.java:84-95` |
| 重试决策 | `resilience4j-retry/.../internal/RetryImpl.java:206-259` |
| 组合门面 | `resilience4j-all/.../decorators/Decorators.java`（javadoc :26-41 写明包裹顺序） |
| 注解切面 | `resilience4j-spring6/.../<tech>/configure/XxxAspect.java`（切点 :90 附近，order 经 `*ConfigurationProperties` 字段） |
| 配置合并 | `resilience4j-framework-common/.../common/circuitbreaker/configuration/CommonCircuitBreakerConfigurationProperties.java:55-69` |
| Boot 4 自动配置 | `resilience4j-spring-boot4/.../circuitbreaker/configure/CircuitBreakerAutoConfiguration.java:76-150` |
| Spring Cloud 门面组合 | `spring-cloud-circuitbreaker-resilience4j/.../Resilience4JCircuitBreaker.java:97-141` |
| Sentinel 槽位链 | `sentinel-core/.../slots/DefaultSlotChainBuilder.java:39-53` + `Constants.java:76-84` |
| Sentinel 半开单探针 | `sentinel-core/.../degrade/AbstractCircuitBreaker.java:66-113` |

## 6.2 五大容错器默认值速查

| 容错器 | 关键默认值 |
|---|---|
| CircuitBreaker | 失败率 50%、慢调用率 100%、慢调用阈值 60s、开闸等待 60s、半开探针 10、最小样本 100、窗口 100（COUNT_BASED）、`t -> true` 记录一切异常 |
| RateLimiter | 50 许可 / **500 纳秒**周期（退化测试默认，生产必须显式配）、超时 5s |
| Bulkhead（信号量） | 并发 25、等待 0ms、**公平信号量** |
| Bulkhead（线程池） | 队列 100、keepAlive 20ms、core=max(CPU−1,1)、max=CPU、AbortPolicy（拒绝即舱壁满） |
| Retry | 3 次、固定 500ms（指数退避默认 ×1.5、随机化 ±0.5）、耗尽抛原异常（failAfterMaxAttempts=false） |
| TimeLimiter | 1s、cancelRunningFuture=true、**仅限异步返回值** |

## 6.3 陷阱速查（合并版）

核心层：RateLimiter 的 500ns 默认周期（2.5）；默认记录一切异常而非只有 IO/RT（2.3.6）；minimumNumberOfCalls 无 time-based 50 特例（1.6.3）；OPEN→HALF_OPEN 默认惰性无定时器（2.3.4）；线程池舱壁按实例独占 executor（2.6）；Decorators 首个 withX 最内层（2.9.1）；Reactor 取消必须归还许可（2.9.2）。
集成层：完整清单见 3.11（装错 starter / 缺 AOP 依赖静默失效 / 切面顺序 / fallback warn-only / TimeLimiter 类型限制 / configs.default 元属性 / 健康双重开关 / actuator 写操作恢复 / sc 1s TimeLimiter / sc 5.0.x 依赖细节）。

## 6.4 本文结论的复现方法

本文所有"版本归属"结论均可复现，命令示例（在 `D:\code\3rd` 对应仓库内）：

```bash
# ① 确认 spring-boot 4.1.x 没有任何 resilience 集成
cd spring-boot && git grep -il "resilience4j" | wc -l        # → 0

# ② 确认 boot4 starter 的构建基线与自检
cd ../resilience4j && sed -n '5,11p' gradle/libs.versions.toml
cat resilience4j-spring-boot4/src/main/java/io/github/resilience4j/springboot/verifier/autoconfigure/SpringBoot4Verifier.java

# ③ 确认 Verifier 是 2.4.0 才引入
git show v2.3.0:resilience4j-spring-boot3/src/main/java/io/github/resilience4j/springboot3/verifier/autoconfigure/SpringBoot3Verifier.java  # → 文件不存在

# ④ 确认 sc-circuitbreaker 各版本的 r4j 依赖
cd ../spring-cloud-circuitbreaker
git show v5.0.3:spring-cloud-circuitbreaker-resilience4j/pom.xml | grep -A1 "resilience4j-spring-boot"
git show v5.1.0-M1:spring-cloud-circuitbreaker-resilience4j/pom.xml | grep -A1 "resilience4j-spring-boot"

# ⑤ 确认 Sentinel 1.8.10 的版本状态
cd ../sentinel && git log -1 --format="%ci %s" $(git log --oneline --all | grep "prepare for 1.8.10" | cut -d' ' -f1)
```

生态时间参考：Spring Boot 4.1.1 发布于 2026-08-20（spring.io 官方公告）；Spring Cloud 2025.1.0 GA 于 2025-11（RC1 2025-10-17，见 spring-cloud Historical-Versions wiki）；Resilience4j `resilience4j-spring-boot4` 支持议题见 [resilience4j#2371](https://github.com/resilience4j/resilience4j/issues/2371)，sc-circuitbreaker 对该构件的 BOM 显式管理说明见本地 `spring-cloud-circuitbreaker-dependencies/pom.xml` 注释（引用 [resilience4j#2427](https://github.com/resilience4j/resilience4j/issues/2427)）。

