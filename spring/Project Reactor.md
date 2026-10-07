# Project Reactor 深度源码解析（写给初学者的架构全景）

> **本文基于的源码**：`D:\code\3rd\reactor-core`，版本 **3.8.8-SNAPSHOT**（main 分支，Git commit `ee7ec788`，快照日期 2026-10-01，配套 BOM `2025.0.7`）。文中所有【源码证据】的文件路径与行号均为对该快照实际读取所得；根路径 `reactor-core/` 特指仓库内的 Gradle 子模块 `D:\code\3rd\reactor-core\reactor-core`。
>
> **版本取舍说明**：Reactor 3.x 的核心骨架（`Flux`/`Mono` 双抽象 + `OptimizableOperator` 组装链 + `CoreSubscriber` 上下文传递）自 3.3 以来高度稳定，3.5/3.6/3.7/3.8 的变化集中在外围（虚拟线程、Sinks 内化、`TimedScheduler` 移除、jspecify 空安全注解等，见 1.6 节），因此本文内容对使用 3.5.x～3.7.x（Spring Boot 3.2～3.5 时代）的读者同样适用。1.6 节中"特性属于哪个版本"的结论，一部分经本地源码直接实证（会注明行号），一部分依据官方发布说明整理（会注明"据官方 release notes"）。
>
> **阅读约定**：与《Spring Framework 深度源码解析》一致——每章"先白话、后源码"，先用一两句人话讲清"这是什么、为什么需要它"，再给出源码证据链（`模块/src/main/java/.../类名.java` + 行号 + 代码片段）。行号只对该快照精确，读者用 IDEA 打开源码按类名 + 方法名定位即可。

## 如何读这份文档

如果你是响应式编程初学者，推荐两遍读法：

- **第一遍（建立地图，1~2 小时）**：只读第一章（总览）每一节的开头白话段、第二章（响应式流规范）、各章的"小结"节、以及第十章（贯通视图）。目标是能回答：`Flux` 和 `Subscriber` 之间的六个信号是什么？为什么 `map()` 之后链上多了一个对象？`request(n)` 是谁发给谁的？`publishOn` 和 `subscribeOn` 差在哪？
- **第二遍（深入源码）**：对照每一章的【源码证据】，打开源码逐行读。顺序建议：第三章（核心抽象与订阅的一生，全文地基）→ 第四章（背压）→ 第五章（调度器）→ 第六章（错误与重试）→ 第八章（Sinks）→ 第七/九/十章（Context、观测、测试，随用随查）。

> **前置知识**：本文假设读者了解 Java 并发基础（线程池、`Future`、`ExecutorService`），不要求事先学过响应式流规范——第二章就是为此准备的。

---

# 一、总览：Reactor 的定位、设计哲学与整体架构

## 1.1 一句话定位

**Project Reactor 是一个完全遵循响应式流（Reactive Streams）规范、面向 JVM 的异步流编程库**：它把"随时间到达的 N 个数据"抽象成两种可组合的发布者——`Flux`（0..N 个元素）与 `Mono`（0..1 个元素），用几百个操作符（`map`/`filter`/`flatMap`/`retryWhen`…）像流水线一样组装它们，并以 `request(n)` 作为第一公民的**背压（backpressure）**机制，让"上游生产多快"始终由"下游消费多快"说了算。它同时是 Spring 反应式技术栈（WebFlux、R2DBC、Spring Cloud Gateway、Spring Data Reactive…）的唯一反应式引擎。

`Flux` 的类级 Javadoc 开宗明义（`reactor-core/src/main/java/reactor/core/publisher/Flux.java` 第 90 行）：

```java
 * A Reactive Streams {@link Publisher} with rx operators that emits 0 to N elements, and then completes
 * (successfully or with an error).
```

`Mono` 的类级 Javadoc 则定义了第二种形状（`reactor-core/src/main/java/reactor/core/publisher/Mono.java` 第 81 行）：

```java
 * A Reactive Streams {@link Publisher} with basic rx operators that emits at most one item <em>via</em> the
 * {@code onNext} signal then terminates with an {@code onComplete} signal (successful Mono,
 * with or without value), or only emits a single {@code onError} signal (failed Mono).
```

它解决的不是"怎么写异步"这一个问题，而是**异步系统的三个顽疾**：

1. **背压**：生产者与消费者速度不匹配时，中间的内存缓冲谁买单？响应式流的答案是 `Subscription.request(n)`——下游要多少，上游才给多少。
2. **组合性**：回调嵌套（callback hell）、`Future.get()` 阻塞轮询都无法表达"结果到手之后再做下一件事，出错走统一出口"的流水线。`Flux`/`Mono` 把每个环节做成操作符，组装期是纯对象图，运行期才真正流动数据。
3. **线程与阻塞**：异步代码里"这段代码在哪个线程执行、能不能阻塞"失去了语言级表达。Reactor 用 `Scheduler`/`Worker`（`publishOn`/`subscribeOn`）把线程边界显式化，用 `NonBlocking` 标记 + BlockHound 把"不许阻塞"变成可检测的约束。

一句话总结它和 Spring 的关系：**Spring Framework 提供 `spring-webflux` 等"反应式应用框架"，而 Reactor 是这些框架脚下那台"异步流发动机"**。Spring 生态里几乎所有返回 `Mono`/`Flux` 的 API，最终都会流转到本文解析的这套源码上。

## 1.2 设计哲学：读源码前先记住五句话

1. **六个信号走天下，规范之上不加魔法**。整个 Reactor 运行期的信号只有六种：下游发给上游的 `request(long)`、`cancel()`，上游发给下游的 `onNext(T)`、`onError(Throwable)`、`onComplete()`，加上订阅握手时的 `onSubscribe(Subscription)`。所有操作符、调度器、Sinks，本质都是对这六种信号的"接收—加工—转发"。`reactor.core.CorePublisher`（`reactor-core/src/main/java/reactor/core/CorePublisher.java`）也只是给规范接口 `Publisher` 加了一个带 `CoreSubscriber` 的内部订阅入口，用于传 `Context`：

    ```java
    public interface CorePublisher<T> extends Publisher<T> {
        void subscribe(CoreSubscriber<? super T> subscriber);
    }
    ```

2. **组装期与运行期严格分离**。写 `Flux.just(1).map(x -> x + 1).filter(...)` 时，什么数据都没流动——每个操作符只是 `new` 了一个包装对象，把上游和参数存起来（"套娃"）；直到 `subscribe()` 被调用的那一刻，订阅动作才沿着这条对象图**自下游向上游**走一遍。理解"组装是造配方的图纸、订阅才是开工"，就理解了 Reactor 一半的源码。
3. **背压不是可选项，而是骨架**。每个操作符在向上游转发 `request(n)` 时都有自己的策略：`map` 原样转发、`flatMap` 转发 `prefetch` 并在内部队列缓冲、`onBackpressureDrop` 拦下多余需求直接丢弃。第四章会展开"默认预取 256/32"这套常数体系从哪来（`Queues.XS_BUFFER_SIZE`/`SMALL_BUFFER_SIZE`）。
4. **为性能预留"融合"口子，但不牺牲规范**。操作符大量实现了 `Fuseable` 下的 `QueueSubscription`/`ConditionalSubscriber`（`reactor-core/src/main/java/reactor/core/Fuseable.java` 第 121、149 行），让相邻操作符可以跳过队列出队直通（fusion）、让 `filter` 后紧跟的 `map` 走条件化订阅避免无效 `request`。这些优化对用户透明，关不掉也不需要关。
5. **线程边界显式声明，不隐藏**。调度器模块的类 Javadoc 一句话点题（`reactor-core/src/main/java/reactor/core/scheduler/Scheduler.java` 第 29-34 行）：*"Provides an abstract asynchronous boundary to operators."*——不调用 `publishOn`/`subscribeOn`，信号就在订阅发起的线程上流动；调用了，边界就钉死在那里。

## 1.3 模块分层全景

Reactor 主仓库是一个多模块 Gradle 工程，`settings.gradle` 实测 include 五个模块（`benchmarks`、`reactor-core`、`reactor-test`、`reactor-tools`、`reactor-core-micrometer`，另有 JDK 17+ 时附加的 `docs` 文档模块）。按"用户感知"分层如下：

```
┌──────────────────────── 调试层 ─────────────────────────┐
│  reactor-tools（ReactorDebugAgent：字节码织入的调试堆栈）   │
├──────────────────────── 测试层 ─────────────────────────┤
│  reactor-test（StepVerifier / VirtualTimeScheduler /      │
│               TestPublisher / PublisherProbe，23 个类）    │
├──────────────────────── 观测层 ─────────────────────────┤
│  reactor-core-micrometer（Micrometer 指标/Observation，    │
│               10 个类，3.4 起独立成模块）                   │
├──────────────────────── 核心层 ─────────────────────────┤
│  reactor-core（443 个 Java 文件，一切的地基）               │
│  ├ reactor.core          # CorePublisher/CoreSubscriber、  │
│  │                       # Disposable、Exceptions、        │
│  │                       # Fuseable、Scannable、observability(3)│
│  ├ reactor.core.publisher# 364 个类：Flux/Mono/全部操作符/   │
│  │                       # Sinks/Processor/Hot 流/LambdaSubscriber│
│  ├ reactor.core.scheduler# 26 个类：Scheduler/Schedulers/   │
│  │                       # 三大实现/Worker/任务包装          │
│  ├ reactor.util.context  # 12 个类：Context/ContextView 不可变链│
│  ├ reactor.util.concurrent# 5 个类：Queues + 三种自研无锁队列 │
│  ├ reactor.util.retry    # 4 个类：Retry/RetryBackoffSpec    │
│  └ reactor.util.function # 9 个类：Tuple2~Tuple8 等函数式工具 │
└─────────────────────────────────────────────────────────┘
```

【源码证据】各包文件数经 `find reactor-core/src/main/java/reactor -maxdepth 2 -type d` 与逐目录 `wc` 统计；模块划分见根目录 `settings.gradle`（`reactor-core`、`reactor-test`、`reactor-core-micrometer`、`reactor-tools`、`benchmarks`）。

与 Spring 的"模块金字塔"不同，Reactor 的分层是**"一个胖核心 + 三个小卫星"**：`reactor-core` 一个模块扛下全部运行期职责（发布者、调度器、背压、上下文、重试），测试/观测/调试各自独立成模块，只为可选依赖——用户在生产环境只需要引一个 `reactor-core`。

## 1.4 依赖图（以 reactor-core/build.gradle 的依赖声明实证）

Reactor 的依赖图比 Spring 还要"骨感"，`reactor-core/build.gradle` 中的生产依赖声明全文如下（第 111-112、134-150 行）：

| 依赖 | 范围 | 用途 |
|---|---|---|
| `org.reactivestreams:reactive-streams` | **api（唯一硬依赖）** | 四接口规范：Publisher/Subscriber/Subscription/Processor |
| `org.jspecify:jspecify` | api | 空安全注解（`@Nullable` 等，3.8 全面接入） |
| `com.github.spotbugs:jsr305` | compileOnly | 兼容性注解 |
| `org.slf4j:slf4j-api` | compileOnly | 日志（`reactor.util.Loggers` 可选集成） |
| `io.micrometer:micrometer-*`（core/commons/context-propagation） | compileOnly | 观测（`tap(Micrometer.observation(...))`）与 ThreadLocal 桥接 |
| `io.projectreactor.tools:blockhound` | compileOnly | 阻塞检测集成（`ReactorBlockHoundIntegration`） |

【源码证据】`reactor-core/build.gradle` 第 111-112 行 `api libs.reactiveStreams`、`api libs.jspecify`；第 138-150 行的 compileOnly 组。

三个值得注意的工程决策：

1. **规范即依赖**：Reactor 对外的类型签名里直接使用 `org.reactivestreams.Publisher`/`Subscriber`，而不是像很多框架那样"自建类型 + 适配器"。这保证了任何 RS 规范实现（Akka Streams、RxJava、Mutiny）都能与 Reactor 互订阅。
2. **可选依赖全部 compileOnly**：micrometer、blockhound、slf4j 在编译期可见、运行期由用户按需提供——`reactor.util.Metrics` 这类门面在类路径缺失时自动降级。核心 jar 因此保持极小（reactor-core 无传递依赖，仅 reactive-streams + jspecify 两个 api 依赖）。
3. **无锁队列自研内化**：曾长期使用的 JCTools 已从依赖中移除，`reactor.util.concurrent` 下现在只有 5 个文件——`Queues`（工厂/尺寸体系）+ `SpscArrayQueue`、`SpscLinkedArrayQueue`、`MpscLinkedQueue` 三种自研无锁队列。需要哪些场景用哪种队列，见 4.4 节。

```
                 reactive-streams（四接口规范）
                 ▲
             reactor-core（Flux/Mono/操作符/调度器/Sinks/Context/Retry）
                 ▲    ▲    ▲
   reactor-test ─┘    │    └─ reactor-core-micrometer
   （StepVerifier，   │        （Observation/指标监听器，
    依赖 reactor-core）│          依赖 micrometer + reactor-core）
                     │
                     └─ reactor-tools（Debug Agent，字节码织入）
```

## 1.5 关键问题 → Reactor 方案映射（全文导览）

| 异步开发的关键问题 | Reactor 的方案 | 详见 |
|---|---|---|
| 数据到达速率不一，中间缓冲谁买单、何时丢、何时报错 | 响应式流 `request(n)` + 操作符预取体系（256/32）+ onBackpressure* 族 | 第二章、第四章 |
| 回调地狱、`Future` 阻塞轮询，流程组合不起来 | `Flux`/`Mono` 操作符链：组装期对象图 + 订阅期信号流 | 第三章 |
| 一段异步代码到底跑在哪个线程？想切线程怎么办 | `Scheduler`/`Worker` 抽象 + `publishOn`/`subscribeOn` 显式切线程 | 第五章 |
| 阻塞调用混进无阻塞线程池，拖垮全局 | `NonBlocking` 线程标记 + BlockHound 运行期检测 | 第五章 |
| 异步流水线的错误处理（跳过？替换？重试？兜底？） | `onErrorResume` 族 + `Operators.onXxxError` 传播工具 + `Retry` 重试规范 | 第六章 |
| ThreadLocal（MDC、SecurityContext、Trace）在异步链上丢失 | `Context` 不可变上下文（订阅树自下而上）+ ThreadLocal 桥接传播 | 第七章 |
| 多消费者共享一条流 / 外部世界向流里"灌"数据 | 热流（ConnectableFlux）与 Sinks API（Processor 的现代替代品） | 第八章 |
| 异步链出了问题无从下手：调用栈全断 | `checkpoint()`/`onOperatorDebug`/reactor-tools Debug Agent | 第九章 |
| 异步代码不好测：时序不定、时间不可控 | StepVerifier 计划式验证 + VirtualTimeScheduler 虚拟时钟 | 第九章 |
| 观测（指标/链路）如何无侵入接入反应式流 | `reactor.core.observability` SignalListener + `tap()` + micrometer 模块 | 第九章 |
| 重试要退避（backoff）、要抖动、要有上限 | `util.retry` 包的 `Retry`/`RetryBackoffSpec` 规范 | 第六章 |
| 与 JDK 原生流式 API（HttpClient、SubmissionPublisher）互通 | `reactor.adapter.JdkFlowAdapter` 双向适配：RS ↔ Flow 七方法同构、一身二职 | 第二章 |

## 1.6 版本演进：从 1.x 到 3.8 的关键变化

写作时（2026 年 10 月）的版本格局：**3.7.x 与 3.8.x 并行维护**（对应 BOM `2024.0.x` 与 `2025.0.x`），本文分析的 main 分支即 3.8.8 快照（`gradle.properties` 第 1 行 `version=3.8.8-SNAPSHOT`、第 2 行 `bomVersion=2025.0.7`）。Spring Boot 4.0.x 对应 Reactor `2025.0.x` / core 3.8.x（Boot 4.0 维护版已随 2025.0.7 发布）；Boot 3.4/3.5.x 对应 `2024.0.x` / 3.7.x。

### 1.6.1 版本时间线与运行基线

| 版本 | GA 时间（据官方 release notes） | JDK 基线 | 一句话主题 |
|---|---|---|---|
| Reactor 1.x | 2012~2014 | Java 6 | Pivotal 内部的"函数式反应式"雏形（`reactor-core` 第一代，非流式 API） |
| Reactor 2.x | 2015 | Java 8 | 与 RxJava 风格对齐的第一代操作符库（`ringbuffer` 起家） |
| Reactor 3.0 | 2016-09 | Java 8 | **推倒重来**：直接实现 Reactive Streams 规范，`Flux`/`Mono` 双抽象、`Fluxable` 融合体系诞生 |
| Reactor 3.2 | 2018-10 | Java 8 | 与 Spring Boot 2.0 配套；`CoreSubscriber` 体系与性能大幅强化 |
| Reactor 3.3 | 2019-08 | Java 8 | BlockHound 集成、`Sinks` API 首次引入（预览） |
| Reactor 3.4 | 2020-10 | Java 8 | **Sinks 正式化**（取代 Processor）、Context 传播工具化、micrometer 集成开始独立化 |
| Reactor 3.5 | 2022-11 | Java 8 | BOM 时代开始（2022.0）；无锁队列去 JCTools 化（内化自研，见 4.4 节） |
| Reactor 3.6 | 2023-11 | Java 8 | **虚拟线程支持**（JDK 21 运行时 + 系统属性开启，Multi-Release JAR，见 5.5 节） |
| Reactor 3.7 | 2024-11 | Java 8 | `Flux.share()`、ThreadLocal 恢复型 `contextWrite`、**Sinks 实现内化**（`InternalManySink` 等）、`FluxPublishOn` 重写清理 |
| Reactor 3.8 | 2025-11 | Java 8（主源码）| jspecify 空安全全面接入、**`TimedScheduler` 移除**、`Scheduler.disposeGracefully()`、`CorePublisher` 文档化强化 |

**JDK 基线的现状值得注意**：3.8 的主源码仍以 Java 8 编译（`reactor-core/build.gradle` 第 141 行 `languageVersion = JavaLanguageVersion.of(name == "docs" ? 21 : 8)`），同时通过 Multi-Release JAR 提供 `src/main/java21` 的覆盖类（虚拟线程调度器），并要求构建时的"最高验证 JDK"为 21（第 81 行 `latestLteJdk = JavaVersion.VERSION_21.majorVersion`）。**"为 Java 8 用户保留主代码"与"为 JDK 21+ 用户启用虚拟线程"并存**，是理解 Reactor 近三个版本兼容策略的钥匙。

### 1.6.2 特性演进对照表（本文写作时逐项核对源码）

| 特性 | 引入版本 | 本快照中的源码锚点 |
|---|---|---|
| `Flux`/`Mono` 双抽象、`CorePublisher.subscribe(CoreSubscriber)` | 3.0 / 3.3 | `Flux.java:126`、`Mono.java:121`、`CorePublisher.java:30` |
| `Scannable` 运行期 introspection（调试/指标的探针接口） | 3.2 | `reactor/core/Scannable.java` |
| `Sinks` API 正式化（`tryEmit`/`EmitResult`/`EmitFailureHandler`） | 3.4 | `reactor/core/publisher/Sinks.java` |
| `util.retry` 包（`Retry`/`RetryBackoffSpec`） | 3.3 | `reactor/util/retry/Retry.java` |
| 无锁队列去 JCTools 化（相关类内化进 `reactor.util.concurrent`） | 3.5（3.4.x 起类已内化，3.5 移除 `jctools-core` 依赖声明） | `reactor/util/concurrent/`（仅 5 个文件，无 jctools import） |
| 虚拟线程版 boundedElastic（`BoundedElasticThreadPerTaskScheduler`） | 3.6（JDK 21+） | `src/main/java21/reactor/core/scheduler/BoundedElasticThreadPerTaskScheduler.java` |
| `Flux.share()`（无参热流化快捷方式） | 3.7 | `Flux.java:8351` |
| ThreadLocal 恢复型 `contextWrite`（`FluxContextWriteRestoringThreadLocals`） | 3.7 | `FluxContextWriteRestoringThreadLocals.java:29` |
| Sinks 内部实现类内化（`InternalManySink` 等，用户不可见） | 3.7 | `reactor/core/publisher/`（内部 sink 系列） |
| jspecify `@Nullable` 全面接入 | 3.8 | `OptimizableOperator.java:19`（`import org.jspecify.annotations.Nullable`）等 |
| `Scheduler.disposeGracefully()`（优雅关闭，返回 `Mono<Void>`） | 3.8 | `Scheduler.java:157-159` |
| `scheduler.TimedScheduler` 接口移除（时间能力内聚到各 Scheduler） | 3.8 | 主模块 grep 无此接口，仅 `SingleScheduler.java:119` 残留注释；注意 reactor-core-micrometer 模块另有一个**同名**指标装饰类 `TimedScheduler`（`Micrometer.java:127`），两者无关易混淆 |

### 1.6.3 对初学者的意义：哪些知识过时了，哪些永远有效

**过时/不要再用的**：

1. **Processor 作为热流入口**：`EmitterProcessor`/`ReplayProcessor`/`UnicastProcessor` 等已全部标注 `@Deprecated`（行号证据见 8.2 节），替代品是 `Sinks.many()`/`Sinks.one()`。网上 2020 年前的教程大量示范 `EmitterProcessor.create()`，一律跳过。
2. **`Hooks.onOperatorDebug()` 全局调试**：它捕获所有组装点堆栈，性能代价大；3.7+ 的正确姿势是 reactor-tools 的 Debug Agent（字节码织入，零组装开销）或局部的 `checkpoint()`。
3. **`subscribe(consumer, errorConsumer, completeConsumer, subscriptionConsumer)` 四参重载**：已 `@Deprecated`（`Flux.java:8807` Javadoc 明言 *"Because users tend to forget to request the subscription"*）——不主动 `request` 是响应式流最常见的"订阅了但没数据"事故。

**永远有效的（3.0 定型至今未变）**：

1. 六信号模型与"组装/订阅两阶段"心智模型；
2. `CoreSubscriber` + `OptimizableOperator` 的订阅链遍历（`Flux.subscribe(Subscriber)` 的实现，见 3.5 节，3.3 引入后仅微调）；
3. `request(n)`/背压/预取常数体系；
4. `Scheduler`/`Worker` 双层抽象与三大内置实现；
5. `Context` 自下而上的传播语义（3.3 引入，方向从未改变——这是与"自上而下"的 ThreadLocal 最本质的区别）。

## 1.7 全文章节地图

```
第一章  总览          —— 定位、哲学、模块、依赖、版本（你现在在这里）
第二章  规范          —— Reactive Streams 四接口与背压规则（一切的地基）
第三章  核心抽象      —— Flux/Mono、组装期、订阅的一生、FluxMap 解剖、Fusion
第四章  背压          —— request 的流转、预取常数、无锁队列、onBackpressure* 族
第五章  调度器        —— Scheduler/Schedulers/三大实现/Worker/任务包装/线程切换
第六章  错误与重试    —— 错误信号传播、恢复操作符、Retry 退避、Exceptions、Hooks
第七章  Context       —— 不可变上下文链、currentContext 传播、ThreadLocal 桥接
第八章  热流与 Sinks  —— 冷热流、Processor 兴衰、Sinks API、create 五种背压策略
第九章  观测、调试与测试 —— tap()/micrometer、checkpoint/Debug Agent、StepVerifier/虚拟时间
第十章  贯通视图      —— 组装/订阅/数据三条时间线 + 设计模式视角
第十一章 附录          —— 接口速查表、学习路线、30 个关键源码文件
```

---

# 二、背景与响应式流规范（Reactive Streams）

> 本章是全文的地基：Reactor 的每一个类，最终都在实现或消费本章定义的四个接口。本章的规范条文引用自 reactive-streams-jvm 官方 README（规范文本，无行号可注）；Reactor 侧的证据均来自本文基线源码。

## 2.1 历史背景：为什么需要"带背压的异步流"

2010 年代初，JVM 上的异步编程有两个主流选项，各有致命伤：

1. **回调地狱**：以 Netty、老的 Guava `Future`/`ListenableFuture` 为代表。组合两个异步结果就要嵌套两层回调，错误处理要在每一层手写，无法用 `for`/`try-catch` 等语言结构表达流程。Netflix 的 RxJava（Rx.NET 的 JVM 移植，2013 年前后兴起）用"事件序列 + 操作符"解决了组合性，但它的 `Observable` 是"推"模型——**没有背压**：下游跟不上时，事件只能堆在内部队列里，最终 `OutOfMemoryError`。
2. **阻塞式 `Future.get()`**：JDK 的 `java.util.concurrent.Future` 只能阻塞等待，组合 N 个结果要 N 次 `get`，把宝贵的线程烧在空等上。

于是 2013 年前后，Netflix（RxJava 团队）、Pivotal（Reactor 团队）、Lightbend（Akka Streams 团队）等坐到一起，把"如何在 JVM 上表达带背压的异步流"讨论成一个**最小公共规范**——这就是 Reactive Streams（2015 年发布 1.0.0，后纳入 JDK 9 作为 `java.util.concurrent.Flow`）。规范刻意做得很小：**只有四个接口 + 若干规则，不提供任何实现**。Reactor 3.0（2016-09）就是在这份规范之上重写的第一个大规模生产级实现之一。

Reactor 仓库至今通过 TCK（Technology Compatibility Kit）验证合规——`gradle/libs.versions.toml` 第 13 行 `reactiveStreams = "1.0.4"`、第 49 行 `reactiveStreams-tck` 依赖即是明证。

**规范要解决的核心矛盾是"生产者快、消费者慢"**。没有背压的世界只有三种结局：丢数据（不可靠）、无界缓冲（OOM）、阻塞上游（退化为同步）。响应式流给出的答案是第四种：**把"要不要更多数据"变成上游可见的显式信号**（`request(n)`），由下游全权决定节奏。

## 2.2 四个接口：最小规范的全部

Reactive Streams 的 API 一共只有四个接口、五个方法签名：

```java
public interface Publisher<T> {
    public void subscribe(Subscriber<? super T> s);
}

public interface Subscriber<T> {
    public void onSubscribe(Subscription s);   // 握手：上游把"遥控器"交给下游
    public void onNext(T t);                   // 数据信号
    public void onError(Throwable t);          // 终止信号（失败）
    public void onComplete();                  // 终止信号（成功）
}

public interface Subscription {
    public void request(long n);               // 下游向上游要 n 个元素（背压的载体）
    public void cancel();                      // 下游撕毁合同
}

public interface Processor<T, R> extends Subscriber<T>, Publisher<R> { }
```

用一张"合同"视角理解它们：

- **`Subscription` 是合同本身**：`onSubscribe` 是"交付合同"，`request` 是"下一批订单"，`cancel` 是"撕毁合同"。**下游对上游的唯一合法操作就是 request/cancel**。
- **`onNext/onError/onComplete` 是履约信号**：上游对下游的信号必须**串行**（不允许并发 `onNext`）且**终态只出现一次**（onError 或 onComplete 之后不再有任何信号）。
- **`request(n)` 是唯一的需求表达**：`n` 是累积计数，下游可以分次要（先 `request(1)` 再 `request(1)`），也可以一次要 `Long.MAX_VALUE`（意为"无界需求"，之后上游想推多少推多少）。

**Reactor 对这四个接口的继承关系**：

```
org.reactivestreams.Publisher<T>
└── reactor.core.CorePublisher<T>（内部扩展：subscribe(CoreSubscriber)，传 Context 用）
    ├── Flux<T>（0..N）
    └── Mono<T>（0..1）

org.reactivestreams.Subscriber<T>
└── reactor.core.CoreSubscriber<T>（内部扩展：currentContext() 供上游读取）
    └──（几乎全部操作符内部的中间订阅者都实现它）
```

【源码证据】`reactor-core/src/main/java/reactor/core/CoreSubscriber.java` 第 24-27 行——Reactor 对规范做了一处"放松"并写进 Javadoc：

```java
 * A {@link Context} aware subscriber which has relaxed rules for §1.3 and §3.9
 * compared to the original {@link org.reactivestreams.Subscriber} from Reactive Streams.
 * If an invalid request {@code <= 0} is done on the received subscription, the request
 * will not produce an onError and will simply be ignored.
```

§1.3/§3.9 的放松意味着：内部订阅者收到 `request(0)` 这类非法请求时**静默忽略**而不是按规范抛 `onError`——内部机制（如融合优化中的试探性请求）需要这种宽容。对外暴露给用户的订阅者仍遵循严格规范。

【源码证据】`reactor-core/src/main/java/reactor/core/CorePublisher.java` 第 30-46 行——`CorePublisher` 只加了一个方法：

```java
public interface CorePublisher<T> extends Publisher<T> {

	/**
	 * An internal {@link Publisher#subscribe(Subscriber)} that will bypass
	 * {@link Hooks#onLastOperator(Function)} pointcut.
	 * <p>
	 * In addition to behave as expected by {@link Publisher#subscribe(Subscriber)}
	 * in a controlled manner, it supports direct subscribe-time {@link Context} passing.
	 */
	void subscribe(CoreSubscriber<? super T> subscriber);
}
```

这个"绕过 `onLastOperator` 钩子、直接带 Context 订阅"的内部入口，是 Reactor 全部操作符之间互相订阅的实际通道；规范的 `subscribe(Subscriber)` 则保留给外部世界（并在进入时被 `Operators.toCoreSubscriber` 适配，见 3.5 节）。

## 2.3 背压规则：request(n) 的六条军规

规范 README 把约束组织成 14 条规则（1.1~3.13），与日常写代码最相关的六条：

1. **规则 1.1**：订阅者收到的信号总数**不得超过** `request(n)` 之和（上游不能多推）。
2. **规则 1.2**：订阅者收到的信号总数**可以小于** request 的量（上游可以少给：比如 `Flux.just(1)` 对 `request(100)` 只发 1 个）。
3. **规则 1.3**：`onSubscribe`、`onNext`、`onError`、`onComplete` **必须串行调用**（不允许两个线程同时对同一个订阅者发信号）——这是 8.3 节 Sinks `FAIL_NON_SERIALIZED` 的规范出处。
4. **规则 2.7**：`cancel` 之后再发信号，信号必须被视为"取消信号"（可以丢弃，不得再触发 request）。
5. **规则 2.13**：`onError` 之前必须 `cancel` 掉 `Subscription`。
6. **规则 3.9**：`request` 参数必须 > 0（`request(0)`/负数应触发 `onError(IllegalArgumentException)`——Reactor 对内部订阅者按 2.2 节所述做了放松）。

**`Long.MAX_VALUE` 的特殊语义**：它不是"要这么多"，而是"无界需求"。此后上游可以随意推送，request 累计数饱和于 `MAX_VALUE`。Reactor 中 `subscribe()`（无参）默认就走这条路——`LambdaSubscriber.onSubscribe` 中的 `s.request(Long.MAX_VALUE)`（`reactor-core/src/main/java/reactor/core/publisher/LambdaSubscriber.java` 第 123 行）。这就是为什么很多人"订阅了却觉得背压没用"：**不是没用，是你选择了无界需求**。

## 2.4 JDK 9 的 Flow API 与 Reactor 的关系：详细分析

> 本节引用的 JDK 侧证据来自 openjdk/jdk 仓库主线的 `src/java.base/share/classes/java/util/concurrent/Flow.java`（2026-10-06 抓取的快照，逐字引用），并与 Oracle JDK 17 API 文档交叉核对。

### 2.4.1 历史脉络：社区规范如何"进宫"

时间线很紧凑：

1. **2013~2015**：Netflix（RxJava）、Pivotal（Reactor）、Lightbend（Akka Streams）等组成工作组起草响应式流规范；2015 年 4 月发布 **Reactive Streams 1.0**（reactive-streams-jvm），含四接口 + TCK。
2. **2017-09**：JDK 9 把四个接口原样收编为 `java.util.concurrent.Flow` 的嵌套接口。执笔人不是别人，正是 JSR-166（`java.util.concurrent` 包）的主持人 Doug Lea——Flow.java 的版权头写得很清楚：

    ```
    Written by Doug Lea with assistance from members of JCP JSR-166
    Expert Group and released to the public domain
    ```

   Flow 的类级 Javadoc 也自报了血统（openjdk/jdk 主线 Flow.java 原文）：

   > "These interfaces correspond to the reactive-streams specification."

3. **但是 Flow 只是 API，不是实现**。JDK 自带的唯一 `Flow.Publisher` 实现是 `java.util.concurrent.SubmissionPublisher`——一个"自带缓冲、可配 executor 的多播发布器"，没有任何操作符。在 Flow 世界里想要 `map`/`flatMap`/`retryWhen`，你仍然需要 Reactor、RxJava 或 Mutiny。**Flow 收编的是"合同"，真正"干活的车间"还是这些第三方库**——这也是它对本章主题的意义：Reactor 与 Flow 不是竞争关系，而是"实现 ↔ 官方 API 副本"的关系。

两个值得原文品读的 Javadoc 细节，与本章前文一一呼应：

> "All (seven) methods are defined in void 'one-way' message style."

——七个方法（subscribe/onSubscribe/onNext/onError/onComplete/request/cancel）全是 void 单向消息，与 1.2 节"六个信号走天下"完全一致。

> "Publishers ensure that Subscriber method invocations for each subscription are strictly ordered in happens-before order."

——信号串行性（规范规则 1.3，见 2.3 节）同样写进了 JDK 文档；8.4 节 Sinks 的 `FAIL_NON_SERIALIZED` 在两个世界里都是这条红线。

### 2.4.2 类型对照：七个方法，两个命名空间

| org.reactivestreams（RS 1.0.4） | java.util.concurrent.Flow | 差异 |
|---|---|---|
| `Publisher<T>` | `Flow.Publisher<T>` | Flow 版标注了 `@FunctionalInterface`（RS 版没有） |
| `Subscriber<T>` | `Flow.Subscriber<T>` | 完全同构（方法签名逐一相同） |
| `Subscription` | `Flow.Subscription` | 完全同构 |
| `Processor<T,R>` | `Flow.Processor<T,R>` | 完全同构 |
| reactive-streams-tck 1.0.4（合规验证） | 无 TCK | TCK 只存在于 RS 侧，JDK 不提供 |

【源码证据】Reactor 侧的规范依赖版本与 TCK——`gradle/libs.versions.toml` 第 13、49 行：

```toml
reactiveStreams = "1.0.4"
...
reactiveStreams-tck = { module = "org.reactivestreams:reactive-streams-tck", version.ref = "reactiveStreams" }
```

**"默认批量"的平行魔数**是两个世界最有趣的暗合：Flow 类唯一的一个静态方法 `defaultBufferSize()` 返回 **256**（openjdk/jdk 主线源码：`static final int DEFAULT_BUFFER_SIZE = 256;`，@implNote *"The current value returned is 256"*）——与 Reactor 的 `Queues.SMALL_BUFFER_SIZE = 256`（4.2 节）同值同义，都是"发布者/订阅者缓冲的默认水位"。Flow 的类 Javadoc 还给出了示例订阅者的补货策略：**消费过半就补**（示例中 bufferSize=64，把未满足需求维持在 32~64 之间）——与 Reactor 的 `unboundedOrLimit` 75% 水位补货（4.3 节）是同一思想的两种工程取值。注意 Flow 的**公共 API 里没有**"批量"维度的方法，Javadoc 示例里出现的 32 只是示例代码的取值，不要与 Reactor 的 `XS_BUFFER_SIZE=32`（系统属性可调）混为一谈。

### 2.4.3 Reactor 的立场：为什么坚持 org.reactivestreams

既然语义完全等价，Reactor 为什么不直接用 `Flow` 类型？四个理由，全部有仓库内证据：

1. **Java 8 基线**。`Flow` 是 JDK 9+ API，而 Reactor 3.8 的主源码仍以 Java 8 编译（`reactor-core/build.gradle` 第 141 行 `languageVersion = JavaLanguageVersion.of(name == "docs" ? 21 : 8)`，见 1.6.1 节）——用 RS 类型才能兑现"一个 jar 跑遍 Java 8~25"的兼容承诺。
2. **生态中立**。RS 是跨库最小公约数：RxJava、Mutiny、Akka Streams 都在 RS 世界。Reactor 的操作符按 3.2 节的约定接受裸 `Publisher`，任何一个 RS 实现都能直接喂进来——这是"实现库之间互不锁定"的前提。
3. **规范即契约**。TCK 依赖（上表）证明 Reactor 接受 1.0.4 规范合规验证；若绑定 `Flow`，这条合规链就断了（JDK 侧无 TCK）。
4. **零收益的迁移**。接口逐方法同构，改名没有任何语义或性能收益。

这条立场的"物理证据"可以用 grep 验证：**reactor-core 主源码 443 个文件中，唯一 import `java.util.concurrent.Flow` 的就是适配器 `reactor/adapter/JdkFlowAdapter.java`**——Reactor 内核与 Flow 零耦合，互通被隔离在 2 个文件（adapter 包：`JdkFlowAdapter.java` + `package-info.java`）里。

### 2.4.4 适配器 JdkFlowAdapter 全解剖：一身二职的桥墩

适配器总共 179 行、两个入口，方向相反：

| 方法 | 行号 | 方向 |
|---|---|---|
| `publisherToFlowPublisher(Publisher<T>)` | 第 44-47 行 | RS → Flow（Reactor/任意 RS 实现供给 JDK 世界） |
| `flowPublisherToFlux(Flow.Publisher<T>)` | 第 56-58 行 | Flow → Reactor |

实现精髓是"**一身二职**"——桥上每个适配对象同时实现两边的两个角色：

【源码证据】`reactor-core/src/main/java/reactor/adapter/JdkFlowAdapter.java` 第 91-105 行（`FlowSubscriber`，RS→Flow 方向的内核）：

```java
	private static class FlowSubscriber<T> implements CoreSubscriber<T>, Flow.Subscription {

		private final Flow.Subscriber<? super T> subscriber;

		Subscription subscription;

		@Override
		public void onSubscribe(final Subscription s) {
		    this.subscription = s;
			subscriber.onSubscribe(this);          // 把"自己"当作 Flow.Subscription 递给 JDK 世界
		}

		@Override
		public void onNext(T o) {
			subscriber.onNext(o);                  // 数据信号：纯转发
		}
		...
		@Override
		public void request(long n) {
		    subscription.request(n);               // 需求信号：打回 RS 上游
		}
```

它站在 RS 世界当 `Subscriber`（收 RS 上游的信号），却把 **`this` 当作 `Flow.Subscription`** 递给 JDK 世界的订阅者——于是 JDK 侧的 `request/cancel` 会打回它身上，再转手调真正的 RS `Subscription`。反方向的 `SubscriberToRS`（第 134-175 行）严格对称（实现 `Flow.Subscriber` + RS `Subscription`）。

三个特性值得注意：

- **零成本翻译**：没有队列、没有缓存、没有线程切换——信号逐个直通，适配器是"海关"不是"加工厂"；
- **不产生 Context**：`FlowSubscriber` 没有覆写 `currentContext()`，返回默认 `Context.empty()`（7.3 节）——Flow 世界天然没有 Context 概念，过桥即清零；
- **没有 Mono 版本**：`flowPublisherToFlux` 只能返回 `Flux`——`Flow.Publisher` 的语义就是 0..N，"至多一个"的基数知识在过桥时丢失（想保住 Mono 语义得在上层重新 `next()`/`single()`）。

### 2.4.5 双向互通的真实战场：Spring WebClient ↔ JDK HttpClient

这不是纸上谈兵——Spring Framework 7.1.0-SNAPSHOT 的 `spring-web` 模块在 JDK HttpClient 连接器（`JdkClientHttpConnector`）里**每个请求都在双向过桥**：

**入站（JDK → Reactor）**：JDK `HttpResponse.BodyHandlers.ofPublisher()` 交给应用的响应体类型是 `Flow.Publisher<List<ByteBuffer>>`，Spring 转成 Flux 再拆包：

【源码证据】`spring-framework/spring-web/src/main/java/org/springframework/http/client/reactive/JdkClientHttpResponse.java` 第 68-80 行：

```java
		HttpResponse<Flow.Publisher<List<ByteBuffer>>> response, DataBufferFactory bufferFactory) {

		Flow.Publisher<List<ByteBuffer>> body = response.body();
		if (body == null) {
			return Flux.empty();
		}

		return JdkFlowAdapter.flowPublisherToFlux(body)
				.flatMapIterable(Function.identity())
				.map(bufferFactory::wrap)
				...
	}
```

**出站（Reactor → JDK）**：请求体在 Spring 这边是 `Flux/Mono<DataBuffer>`，转换后喂给 `HttpRequest.BodyPublishers.fromPublisher`（它只认 `Flow.Publisher`）：

【源码证据】`spring-framework/spring-web/src/main/java/org/springframework/http/client/reactive/JdkClientHttpRequest.java` 第 109-120 行：

```java
	private HttpRequest.BodyPublisher toBodyPublisher(Publisher<? extends DataBuffer> body) {
		Publisher<ByteBuffer> byteBufferBody = (body instanceof Mono ?
				Mono.from(body).map(this::toByteBuffer) :
				Flux.from(body).map(this::toByteBuffer));

		Flow.Publisher<ByteBuffer> bodyFlow = JdkFlowAdapter.publisherToFlowPublisher(byteBufferBody);

		return (getHeaders().getContentLength() > 0 ?
				HttpRequest.BodyPublishers.fromPublisher(bodyFlow, getHeaders().getContentLength()) :
				HttpRequest.BodyPublishers.fromPublisher(bodyFlow));
	}
```

互通姿势速查：**JDK 世界的发布者**（`HttpClient` 响应体、`SubmissionPublisher`、任何第三方返回的 `Flow.Publisher`）用 `flowPublisherToFlux` 收编进 Reactor 流水线；**Reactor 的流要交给只认 `Flow.Publisher` 的 API**（`BodyPublishers.fromPublisher`、自研的 JDK 风格组件）时用 `publisherToFlowPublisher` 送出去。

一张图收拢：

```
      RS 世界（2015 规范）                        JDK 世界（2017 收编）
 org.reactivestreams.*                      java.util.concurrent.Flow.*
 Publisher      ◄── 七方法同构 ──►          Flow.Publisher
 Subscriber     ◄── 七方法同构 ──►          Flow.Subscriber
 Subscription   ◄── 七方法同构 ──►          Flow.Subscription
 Processor      ◄── 七方法同构 ──►          Flow.Processor
      ▲                                              ▲
      │  reactor.adapter.JdkFlowAdapter（一身二职）    │
      │            Flux ◄──────────► Flow.Publisher   │
      └── Reactor（有操作符、有 TCK、有 Context）      └── SubmissionPublisher/HttpClient
                                                          （有 API、无操作符、无 TCK）
```

## 2.5 本章小结

- Reactive Streams 用**四个接口**定义了"带背压的异步流"的最小合同：`Publisher`（可被订阅）、`Subscriber`（信号接收方）、`Subscription`（request/cancel 遥控器）、`Processor`（既收又发的中间件）。
- **背压的本体是 `Subscription.request(n)`**：它是下游对上游唯一的节奏控制手段，`Long.MAX_VALUE` 即无界需求。
- 规范只定义合同不定义实现；Reactor 是这套合同上最主流的生产级实现，并通过 TCK（reactive-streams-tck 1.0.4）验证。
- `Flow`（JDK 9）是这套规范的官方收编副本：四接口逐方法同构、默认缓冲同值 256；但 Flow 只收编了 API 没有实现（JDK 唯一的 `SubmissionPublisher` 无操作符）。Reactor 坚持使用 RS 类型（Java 8 基线 + 生态中立 + TCK 合规），与 Flow 的全部互通被隔离在 `reactor.adapter.JdkFlowAdapter` 一个类里（2.4 节）。
- Reactor 的全部扩展（`CorePublisher`/`CoreSubscriber`）都围绕两件事：**传 Context** 与 **绕过钩子的内部快速通道**，从不突破六信号模型。
- 下一章进入 Reactor 自己的世界：`Flux`/`Mono` 这两个" Publisher 子类 + 几百个操作符"的类，到底是怎么组织起来的。


# 三、核心抽象：Flux 与 Mono（reactor.core.publisher）

> 本章对应源码：`reactor-core` 模块的 `reactor.core.publisher` 包（364 个 Java 文件）与 `reactor.core` 包的少数地基接口。以下行号均为 3.8.8-SNAPSHOT 快照实测。

## 3.1 先说白话：364 个类的包，只讲一个故事

`reactor.core.publisher` 包有 364 个文件，看上去吓人，其实全是同一个模板的变体：**每个操作符 = 一个包级私有 Final 类 = "记住上游 + 记住你的参数 + 决定怎么转发六种信号"**。`FluxMap`、`FluxFilter`、`FluxPeek`… 的骨架一模一样，差异只在信号加工逻辑。把这些类分成五组，地图就清楚了：

```
reactor.core.publisher（364 个文件）
├── 门面：Flux、Mono、ConnectableFlux、GroupedFlux
│       （用户可见的抽象类，几百个组装方法）
├── 操作符实现：FluxMap、FluxFilter、FluxFlatMap、FluxPublishOn…（300+ 个）
│       每个类 = 组装期容器 + 内部订阅者（转发/加工信号）
├── 数据源：FluxArray、FluxIterable、FluxJust、MonoJust、FluxDefer、
│       FluxCreate…（"流水的源头"）
├── 热流与 Sinks：Sinks、SinksSpecs、SinkMany*、Processor 遗留类
│       （第八章专章）
└── 运行期工具：Operators（静态工具箱）、Subscribers、LambdaSubscriber、
        DrainUtils、Internal* 内部基类
```

配套的两个地基包：

- `reactor.core`：`CorePublisher`/`CoreSubscriber`（规范扩展）、`Fuseable`（融合标记）、`Scannable`（运行期探针）、`Disposable`（取消句柄）、`Exceptions`（异常工具）；
- `reactor.util.function`：`Tuple2`~`Tuple8`（`zip` 操作符的返回类型）等。

## 3.2 Flux 与 Mono：两种形状，一套机制

两者都是抽象类，都直接实现 `CorePublisher`：

【源码证据】`reactor-core/src/main/java/reactor/core/publisher/Flux.java` 第 126 行、`Mono.java` 第 121 行：

```java
public abstract class Flux<T> implements CorePublisher<T> { ... }
public abstract class Mono<T> implements CorePublisher<T> { ... }
```

分工只有一条：**`Flux` 表达 0..N 个元素，`Mono` 表达 0..1 个元素**（引文见 1.1 节）。这不是语法糖，而是类型系统层面的"基数契约"，它带来三个工程收益：

1. **方法签名即文档**：`repository.findById(id)` 返回 `Mono<User>`，读签名就知道"至多一个"；返回 `Flux<User>` 则是列表流。Spring Data Reactive、WebFlux 全靠这个约定。
2. **`Mono<Void>` 表达"只关心完成"**：等价于异步版的 `void`，完成即信号本身。
3. **操作符基数守恒**：`Mono.map` 返回 `Mono`、`Mono.flatMapMany` 才会"升维"到 `Flux`——基数变化必须显式换名。`Mono.java` 第 107-111 行 Javadoc 明说 *"The rx operators will offer aliases for input Mono type to preserve the 'at most one' property"*。

**接口方式的另一个重要约定**（两个类的 Javadoc 都有）：

【源码证据】`Flux.java` 第 101-102 行：

```java
 * <p>It is intended to be used in implementations and return types. Input parameters should keep using raw
 * {@link Publisher} as much as possible.
```

即：**Reactor 类型用在"返回值/实现"里，"入参"尽量用规范的 `Publisher`**。这样第三方（RxJava、Akka、JDK Flow）的实现可以直接喂给 Reactor 的操作符，Reactor 不垄断类型。

## 3.3 组装期 vs 订阅期：一切操作符都是"套娃"

这是全文最重要的一节。看一个最小例子：

```java
Flux<Integer> pipeline = Flux.just(1, 2, 3)
        .map(x -> x * 10)      // ①
        .filter(x -> x > 10);  // ②
// ——到这里，什么都没有发生：just/map/filter 只是 new 了三个对象

pipeline.subscribe(System.out::println);  // ③ 这一行才"开工"
```

三个阶段在源码里的真实形态：

**阶段①②（组装期）**：`map` 只是把 `(上游, 函数)` 包进新对象。

【源码证据】`reactor-core/src/main/java/reactor/core/publisher/Flux.java` 第 6626-6633 行：

```java
	public final <V> Flux<V> map(Function<? super T, ? extends V> mapper) {
		if (this instanceof Fuseable) {
			return onAssembly(new FluxMapFuseable<>(this, mapper));
		}
		return onAssembly(new FluxMap<>(this, mapper));
	}
```

【源码证据】`reactor-core/src/main/java/reactor/core/publisher/FluxMap.java` 第 47-51 行：

```java
	FluxMap(Flux<? extends T> source,
			Function<? super T, ? extends R> mapper) {
		super(source);
		this.mapper = Objects.requireNonNull(mapper, "mapper");
	}
```

组装后的对象图（自下游向上游看，`filter` 在最外层）：

```
subscribe() 调用点
    ↓ 订阅动作自外向内传播
FluxFilter ──source──► FluxMap ──source──► FluxArray(数据 [1,2,3])
```

此时 `onAssembly(...)` 把节点交给 `Hooks.onEachOperator`（组装钩子，包装调试快照用，见 9.3 节），纯参数校验，无任何数据流动。

**阶段③（订阅期）**：`subscribe()` 之后的信号流向与订阅动作方向**相反**——订阅动作自下游向上游走（找源头要遥控器 `Subscription`），数据信号自上游向下游流（源头按需求送货）。4.2 节会展开 `request` 的方向。这张"两个方向"的心智图是理解一切操作符的钥匙：

```
订阅动作（subscribe/onSubscribe）→→→ 自下游流向上游
数据信号（onNext/onError/onComplete）←←← 自上游流向下游
需求信号（request）/取消（cancel）→→→ 自下游流向上游
```

## 3.4 一个操作符的自画像：FluxMap 全解剖

`FluxMap` 是所有"一进一出同步操作符"的标准模板，全部 303 行值得通读。三个关键件：

**件一：`subscribeOrReturn`——订阅时决定"我包一层还是直接换人"**。

【源码证据】`reactor-core/src/main/java/reactor/core/publisher/FluxMap.java` 第 55-62 行：

```java
	@Override
	@SuppressWarnings("unchecked")
	public CoreSubscriber<? super T> subscribeOrReturn(CoreSubscriber<? super R> actual) {
		if (actual instanceof Fuseable.ConditionalSubscriber) {
			Fuseable.ConditionalSubscriber<? super R> cs =
					(Fuseable.ConditionalSubscriber<? super R>) actual;
			return new MapConditionalSubscriber<>(cs, mapper);
		}
		return new MapSubscriber<>(actual, mapper);
	}
```

它的返回值语义：**返回一个新的 `CoreSubscriber`（把自己的加工逻辑缝进下游的"外衣"），而不是 `this`**。这与大多数"发布者"的直觉相反：操作符在订阅期的主要动作不是"我被订阅"，而是"给下游订阅者穿上一件我做的马甲"。

**件二：`MapSubscriber`——信号加工与转发**。

【源码证据】`reactor-core/src/main/java/reactor/core/publisher/FluxMap.java` 第 70-123 行（节选）：

```java
	static final class MapSubscriber<T, R>
			implements InnerOperator<T, R> {

		final CoreSubscriber<? super R>        actual;   // 下游（真正的收件人）
		final Function<? super T, ? extends R> mapper;

		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.validate(this.s, s)) {          // 防"二次订阅"竞态
				this.s = s;
				actual.onSubscribe(this);                 // 把自己当作 Subscription 递给下游
			}
		}

		@Override
		public void onNext(T t) {
			if (done) {
				Operators.onNextDropped(t, actual.currentContext());
				return;
			}
			R v;
			try {
				v = mapper.apply(t);
				if (v == null) {
					throw new NullPointerException("The mapper [" + mapper.getClass().getName() + "] returned a null value.");
				}
			}
			catch (Throwable e) {
				Throwable e_ = Operators.onNextError(t, e, actual.currentContext(), s);
				if (e_ != null) {
					onError(e_);                          // 默认策略：失败即终止
				}
				else {
					s.request(1);                         // onErrorContinue 策略：丢值再要一个
				}
				return;
			}
			actual.onNext(v);
		}

		@Override
		public void request(long n) {
			s.request(n);                                 // request 原样向上游转发
		}

		@Override
		public void cancel() {
			s.cancel();                                   // cancel 原样向上游转发
		}
	}
```

五个设计要点，每一个都是操作符族的公共基因：

1. **`actual` 字段**：下游订阅者。内部订阅者的一切信号加工终点都是 `actual.onNext/onError/onComplete`。
2. **`InnerOperator` 既是 `Subscriber` 又是 `Subscription`**：`onSubscribe` 里它把 `this` 递给下游，于是下游的 `request/cancel` 会打回它身上，再由它（通常原样）转给上游 `s`。一条链上的每个节点都是"上游的下游、下游的上游"。
3. **`done` 布尔 + `Dropped` 通道**：终态后再收到数据，走 `Operators.onNextDropped`（Hook 可观测，默认丢弃），绝不打断既有终态。
4. **`Operators.onNextError` 的两种策略**：mapper 抛异常时，默认（`OnNextFailureStrategy.STOP`）转 `onError` 终止整条流；若 Context 中声明了 `onErrorContinue` 策略，则返回 null → 丢弃该值并向源头补 `request(1)`。这解释了 `onErrorContinue` 为什么"作用于上游"——它其实是每个操作符 `onNext` 时的**查表行为**（见 6.2 节）。
5. **mapper 返回 null 判 NPE**：Reactor 禁止 `null` 元素，`Optional`/`空值` 需显式建模（`Mono.empty()`/`Mono.justOrEmpty()`）。

**件三：`ConditionalSubscriber` 分身**。`filter().map()` 这类组合中，下游（filter）是"条件化订阅者"，`map` 换成 `MapConditionalSubscriber`（同文件 172-301 行），把 `tryOnNext`（"这个值我不要，请换个值试"）透传下去，避免"filter 丢弃一个值就要整轮 request"的浪费。这是性能优化，语义不变。

## 3.5 订阅的一生：`Flux.just(1,2,3).map(f).subscribe(c)` 逐行级

现在把 3.3 的三阶段流程逐行走完。这是全文的"重点 A"，下面每一步都对应真实源码行号。

**Step 1：`subscribe(consumer)` → 造一个 `LambdaSubscriber`**。

【源码证据】`Flux.java` 第 8848-8857 行：

```java
	public final Disposable subscribe(
			@Nullable Consumer<? super T> consumer,
			@Nullable Consumer<? super Throwable> errorConsumer,
			@Nullable Runnable completeConsumer,
			@Nullable Context initialContext) {
		return subscribeWith(new LambdaSubscriber<>(consumer, errorConsumer,
				completeConsumer,
				null,
				initialContext));
	}
```

`LambdaSubscriber` 是"lambda 三件套"的规范适配器。它的 `onSubscribe` 落实了"默认无界需求"：

【源码证据】`reactor-core/src/main/java/reactor/core/publisher/LambdaSubscriber.java` 第 109-126 行：

```java
	@Override
	public void onSubscribe(Subscription s) {
		if (Operators.validate(subscription, s)) {
			this.subscription = s;
			if (subscriptionConsumer != null) {
				try {
					subscriptionConsumer.accept(s);
				}
				catch (Throwable t) {
					Exceptions.throwIfFatal(t);
					s.cancel();
					onError(t);
				}
			}
			else {
				s.request(Long.MAX_VALUE);
			}
		}
	}
```

**Step 2：`subscribe(Subscriber)` 进入操作符链的"循环下潜"**。

【源码证据】`Flux.java` 第 8859-8894 行（核心节选）：

```java
	@Override
	@SuppressWarnings("unchecked")
	public final void subscribe(Subscriber<? super T> actual) {
		CorePublisher publisher = Operators.onLastAssembly(this);
		CoreSubscriber subscriber = Operators.toCoreSubscriber(actual);      // ① 规范适配

		if (subscriber instanceof Fuseable.QueueSubscription && this != publisher && this instanceof Fuseable && !(publisher instanceof Fuseable)) {
			subscriber = new FluxHide.SuppressFuseableSubscriber<>(subscriber); // ② 融合隔离
		}

		try {
			if (publisher instanceof OptimizableOperator) {
				OptimizableOperator operator = (OptimizableOperator) publisher;
				while (true) {                                               // ③ 循环代替递归
					subscriber = operator.subscribeOrReturn(subscriber);
					if (subscriber == null) {
						// null means "I will subscribe myself", returning...
						return;
					}
					OptimizableOperator newSource = operator.nextOptimizableSource();
					if (newSource == null) {
						publisher = operator.source();
						break;
					}
					operator = newSource;
				}
			}

			subscriber = Operators.restoreContextOnSubscriberIfPublisherNonInternal(publisher, subscriber);
			publisher.subscribe(subscriber);                                 // ④ 真源头上工
		}
		catch (Throwable e) {
			Operators.reportThrowInSubscribe(subscriber, e);
			return;
		}
	}
```

四步解读：

- **① `toCoreSubscriber`**：把用户传入的裸 `Subscriber` 适配成 `CoreSubscriber`（补上空的 `currentContext`）。
- **② `SuppressFuseableSubscriber`**：如果内部节点支持融合（`Fuseable`）而外部世界不支持，用一层包装把融合请求"挡回去"，防止规范接口泄漏内部协议。
- **③ 循环下潜**：`OptimizableOperator` 接口（`reactor-core/src/main/java/reactor/core/publisher/OptimizableOperator.java` 第 34-60 行）定义了两个方法：

    ```java
    interface OptimizableOperator<IN, OUT> extends CorePublisher<IN> {
        @Nullable CoreSubscriber<? super OUT> subscribeOrReturn(CoreSubscriber<? super IN> actual) throws Throwable;
        CorePublisher<? extends OUT> source();
        @Nullable OptimizableOperator<?, ? extends OUT> nextOptimizableSource();
    }
    ```

    其 Javadoc（第 24-29 行）点明动机：*"looping instead of recursive subscribes"*。若没有这个优化，`map(map(map(...)))` 的订阅是"N 层方法递归"，深链会烧调用栈；循环版本在 `subscribe(Subscriber)` 一层把马甲全部穿完，然后对**最上游真源**调用一次 `subscribe`。
- **④ `publisher.subscribe(subscriber)`**：这里的 `publisher` 已经是链条最顶端的数据源（`FluxArray`/`FluxIterable`/网络源…），订阅抵达源头，握手开始。

**Step 3：源头交付 `Subscription`，握手完成**。以 `Flux.fromIterable` 为例：

【源码证据】`reactor-core/src/main/java/reactor/core/publisher/FluxIterable.java` 第 69 行起（节选自 `subscribe(CoreSubscriber)`）：

```java
	public void subscribe(CoreSubscriber<? super T> actual) {
		...
		if (s instanceof ConditionalSubscriber) {
			IterableSubscriptionConditional<? extends T> isc = ...   // L156
			...
			s.onSubscribe(isc);                                      // 握手：把 Subscription 递给下游
		}
		else {
			IterableSubscription<? extends T> is = ...               // L181
			...
			s.onSubscribe(is);
		}
	}
```

握手信号 `onSubscribe` 沿原路返回（源头 → MapSubscriber → LambdaSubscriber），每到一层就换上该层的"马甲 Subscription"。最终 `LambdaSubscriber.onSubscribe` 收到的是 `MapSubscriber`（它同时是 `Subscription`），于是 `request(Long.MAX_VALUE)` 发出——**需求信号自下游向上游回流**，`MapSubscriber.request` 原样转发给 `IterableSubscription`。

**Step 4：源头按需拉取迭代器，推挤结合**。`IterableSubscription.request` 分快慢两条路：

【源码证据】`FluxIterable.java` 第 289-302 行（`IterableSubscription.request`）与第 319 行起（`slowPath`）：

```java
		@Override
		public void request(long n) {
			if (Operators.validate(n)) {
				if (Operators.addCap(REQUESTED, this, n) == 0) {
					if (n == Long.MAX_VALUE) {
						fastPath();       // 无界需求：一口气推完
					}
					else {
						slowPath(n);      // 有界需求：按 n 逐个拉取推送
					}
				}
			}
		}

		private void slowPath(long n) {
			final Subscriber<? super T> s = actual;
			long e = 0L;
			for (; ; ) {
				while (e != n) {
					T t;
					try {
						t = Objects.requireNonNull(next(), "The iterator returned a null value");
					...
```

`slowPath` 的循环体是"**从 `Spliterator` 拉 1 个 → `actual.onNext(t)` → 计数 e+1**"，推够 n 个后 CAS 检查是否又有新需求，够本就再来一轮——**拉模型（pull）外面套着推模型（push）的皮**。这就是同步冷流的全部真相：`Flux` 是皮，`Iterator` 是瓤。

至此，`just(1,2,3).map(f).subscribe(c)` 的完整时序：

```
组装期（任意时刻）:
  FluxArray(1,2,3)  ◄──source── FluxMap(f)  ◄──source── [subscribe调用点]

订阅期（subscribe() 那一瞬间，自下游向上游）:
  subscribe(c) → LambdaSubscriber
      → FluxMap.subscribeOrReturn(LambdaSubscriber) = MapSubscriber{actual=LambdaSubscriber}
      → 循环下潜: nextOptimizableSource() → FluxArray
      → FluxArray.subscribe(MapSubscriber)
      → MapSubscriber.onSubscribe(IterableSubscription) → actual.onSubscribe(MapSubscriber自己)
      → LambdaSubscriber.request(Long.MAX_VALUE)
      → MapSubscriber.request(Long.MAX_VALUE) → IterableSubscription.request(Long.MAX_VALUE)

数据期（自上游向下游）:
  IterableSubscription: onNext(1) → MapSubscriber: f(1)=10 → actual.onNext(10) → LambdaSubscriber: println
  ... onNext(2)/onNext(3) ...
  onComplete() → MapSubscriber → LambdaSubscriber.onComplete()
```

**这个三层时序（组装/订阅/数据）在第十章会作为"三条时间线"再次收拢。**

## 3.6 Mono 的特殊性：标量源与"提前获知"

`Mono` 相比 `Flux` 多了一类优化：**标量源（scalar source）**。`Mono.just(v)`、`Mono.empty()` 这类值已知的源实现 `Fuseable.ScalarCallable`：

【源码证据】`reactor-core/src/main/java/reactor/core/Fuseable.java` 第 269 行：

```java
	interface ScalarCallable<T> extends Callable<T> { }
```

它让操作符有机会**不经过完整订阅**就同步取出值：比如 `Mono.just(1).map(f)` 可以直接算出 `f(1)` 变成 `Mono.just(f(1))`（"标量折叠"），`flatMap` 也能对"已知必然立即完成"的内层做快速路径。对用户的意义是：`Mono` 链在热路径上的开销经常接近普通方法调用，这也是 Spring 6/Boot 3 敢于在框架内部大量使用 `Mono` 作为异步返回类型的原因之一。

基数差异还体现为 API 的成对出现：`Flux.flatMap` vs `Mono.flatMap`（保持 0..1）、`Mono.flatMapMany`（0..1 → 0..N 升维）、`Flux.next`（0..N → 0..1 降维取第一个）、`Flux.single/last/elementAt`（降维族）。读源码时把"基数"当坐标系，同名操作符的 `Flux`/`Mono` 两版差异就一目了然。

## 3.7 融合（Fusion）与条件订阅：为性能预留的暗门

规范之外，Reactor 定义了一套"旁路协议"让相邻操作符绕过队列出队：

【源码证据】`reactor-core/src/main/java/reactor/core/Fuseable.java` 第 121、149、250 行：

```java
	interface ConditionalSubscriber<T> extends CoreSubscriber<T> { ... }      // L121 "可拒绝元素"的订阅者

	interface QueueSubscription<T> extends Queue<T>, Subscription {           // L149 双重身份：订阅 + 队列
		int requestFusion(int requestedMode);
	}

	interface SynchronousSubscription<T> extends QueueSubscription<T> { ... } // L250 纯同步源
```

三个层次：

1. **条件订阅（ConditionalSubscriber）**：`filter` 等丢弃型操作符下游走 `tryOnNext`，被拒时不消耗 `request` 配额（见 3.4 件三）。
2. **队列融合（QueueSubscription）**：当一个操作符的"内部缓冲"恰好也是个队列时，它把"订阅"和"队列"合二为一，下游直接从上游身上 `poll()` 数据，省掉中间复制。`requestFusion(mode)` 就是协商接口：`NONE`/`SYNC`/`ANY(+THREAD_BARRIER)` 三档。
3. **边界适应**：融合是内部协议，跨越"Reactor → 外部世界"边界时必须关掉。`Flux.subscribe(Subscriber)` 里的 `SuppressFuseableSubscriber`（`Flux.java:8865-8867`，见 3.5 Step 2）与 `FluxHide`/`ConnectableFluxHide` 等包装类就是"海关"。

对初学者的实用结论：**看懂 `FluxMap` 这类"非融合"路径，就理解了 Reactor 90% 的语义；融合只是给同一条语义做了复写优化**。阅读源码时先跳过 `*Fuseable` 变体，主流程读通再回头看。

## 3.8 本章小结

- `reactor.core.publisher` 的 364 个类高度同构：**门面（Flux/Mono）+ 操作符实现 + 数据源 + 热流/Sinks + 运行期工具** 五组。
- **组装期是对象图，订阅期才流动**：`map()` 只是 `new FluxMap(source, mapper)`；`subscribe()` 才触发订阅动作自下游向上游传播。
- 操作符的标准实现模式：**`subscribeOrReturn` 给下游穿马甲（内部订阅者）→ 马甲既是 Subscriber 又是 Subscription → 信号加工后转 `actual`，`request/cancel` 转上游**。
- `OptimizableOperator` 用**循环代替递归**完成整条链的订阅（`Flux.subscribe(Subscriber)`），深链不烧栈。
- 同步冷流的本质：**拉模型套推模型的皮**（`IterableSubscription.slowPath`）。
- `Mono` 多一层"标量源"优化（`ScalarCallable`），允许免订阅同步求值；融合/条件订阅是性能暗门，语义与普通路径等价。


# 四、背压与请求管理

> 本章对应源码：`reactor.util.concurrent.Queues` 与三种无锁队列、`reactor.core.publisher` 下的预取流转与 `FluxOnBackpressure*` 操作符族。

## 4.1 先说白话：request(n) 是一条"回传的调速器"

第三章讲了信号的两个方向，本章聚焦**第三个方向的细节**：`request(n)` 如何沿着操作符链自下游向上游回传，以及每一层如何"克扣"或"放大"它。

```
LambdaSubscriber.request(256) ──► MapSubscriber（原样转发）──► 上游 Subscription
        ◄── 每 onNext(1) 消耗 1 个配额；配额耗尽前上游不得再推
```

三类典型角色：

1. **透明转发者**（`map`/`filter` 等同步一对一操作符）：`request/cancel` 原样转发，自己不缓冲任何东西——`MapSubscriber.request` 就是 `s.request(n)`（见 3.4 件二）。
2. **预取缓冲者**（`flatMap`/`publishOn` 等异步操作符）：把下游的"无界需求"翻译成对上游的"分批需求"（prefetch），在内部队列里蓄水。**这是背压真正"发力"的地方**——上游被约束在 prefetch 节奏内，下游仍可无界消费。
3. **截流决策者**（`onBackpressure*` 族）：在"源头天生无界"（热源、外部系统回调）的场合，主动宣布丢/留/报错的策略。

## 4.2 两个魔数：256 与 32 从哪来

Reactor 所有"默认批量"都出自 `Queues` 的两个常量：

【源码证据】`reactor-core/src/main/java/reactor/util/concurrent/Queues.java` 第 85-92 行：

```java
	public static final int XS_BUFFER_SIZE    = Math.max(8,
			Integer.parseInt(System.getProperty("reactor.bufferSize.x", "32")));

	public static final int SMALL_BUFFER_SIZE = Math.max(16,
			Integer.parseInt(System.getProperty("reactor.bufferSize.small", "256")));
```

| 常量 | 默认值 | 系统属性 | 典型用途 |
|---|---|---|---|
| `SMALL_BUFFER_SIZE` | 256 | `reactor.bufferSize.small` | 默认预取（prefetch）：flatMap 并发度、publishOn 队列容量、create 的 BUFFER 策略初始容量、EmitterProcessor 队列 |
| `XS_BUFFER_SIZE` | 32 | `reactor.bufferSize.x` | flatMap 的内部源预取（内层 publisher 每次要多少）、`FluxPublish` share() 场景的小队列 |

两者的差异体现的是**两级流水线的错位设计**：外层（本流→内层流的订阅管理）用 256，内层（内层流→结果的传输队列）用 32——外层管"同时在飞多少个任务"，内层管"每个任务的暂存深度"。

【源码证据】`Flux.java` 第 5474-5477 行——`flatMap` 默认值的实际落点：

```java
	public final <R> Flux<R> flatMap(Function<? super T, ? extends Publisher<? extends R>> mapper) {
		return flatMap(mapper, Queues.SMALL_BUFFER_SIZE, Queues
				.XS_BUFFER_SIZE);
	}
```

（第一个参数是 concurrency——同时激活的内流上限；第二个是 prefetch——对每个内流预取多少。）

调整姿势：`-Dreactor.bufferSize.small=1024` 全局调大（内存换吞吐），或对单个操作符显式传参（`flatMap(fn, 64, 16)`、`publishOn(scheduler, 128)`），不动全局。

## 4.3 prefetch 的流转：从"无界"到"分批"再到"补货"

异步操作符把背压做成了一台"信用计"（credit-based）机器：

1. **下游给无界**：`subscribe()` 默认 `request(Long.MAX_VALUE)`，操作符对下游**永远可以推**（只要内部有货）。
2. **对上游给有界**：操作符订阅源头时只 `request(prefetch)`（如 256），此后不是每消费一个就补一个，而是**攒到 75% 水位一次性补足**。补货量与水位由 `Operators.unboundedOrLimit` 统一计算：

    【源码证据】`reactor/core/publisher/Operators.java` 第 1511-1521 行：

    ```java
    static int unboundedOrLimit(int prefetch) {
        return prefetch == Integer.MAX_VALUE ? Integer.MAX_VALUE : (prefetch - (prefetch >>	2));
    }
    static int unboundedOrLimit(int prefetch, int lowTide) {
        if (lowTide <= 0) return prefetch;
        if (lowTide >= prefetch) return unboundedOrLimit(prefetch);
        return prefetch == Integer.MAX_VALUE ? Integer.MAX_VALUE : lowTide;
    }
    ```

    `prefetch - (prefetch >> 2)` 即 75% 水位：消费计数 `e` 攒到这个 limit 时，一次性 `s.request(e)` 把已消费的量补回上游（`FluxPublishOn.runAsync` 第 451-457 行：`e == limit` 时 `REQUESTED.addAndGet(this, -e); s.request(e)`）。批量化（减少 request 次数）与平稳性（需求永不归零）由此兼得。`FluxFlatMap` 的 `FlatMapInner` 注释里还留着旧公式遗迹 `// this.limit = prefetch >> 2;`（第 947 行），如今同样统一走 `unboundedOrLimit`。
3. **上游被钉在批节奏里**：源头（如 `FluxIterable`、网络适配器）收到的始终是 `256、64、64…` 这类有限数，多快的数据源也不会淹没下游。

**与拉模型的对照**：同步源（`IterableSubscription`）根本不需要队列——`slowPath` 直接边拉边推（3.5 节 Step 4）；只有当"生产/消费发生在不同线程"时，队列才出现，预取才有意义。这也解释了为什么 `map` 没有 prefetch 概念而 `publishOn`/`flatMap` 必须有。

## 4.4 内部队列三剑客：自研无锁队列

异步操作符的内部缓冲全部出自 `reactor.util.concurrent` 包——5 个文件，其中 3 个是队列实现（历史上来自 JCTools，3.5 起内化自研，依赖已移除，见 1.4 节）：

| 队列 | 并发形态 | 典型使用者 |
|---|---|---|
| `SpscArrayQueue` | 单生产者/单消费者，有界（环形数组） | `Queues.xs()`/`small()` 的默认供给（prefetch 固定场景） |
| `SpscLinkedArrayQueue` | 单生产者/单消费者，**无界**（链式分段的稀疏数组） | `Queues.unbounded()`：UnicastProcessor、create 的 BUFFER 策略、bufferUntil 类操作 |
| `MpscLinkedQueue` | 多生产者/单消费者，无界 | `Queues.unboundedMultiproducer()`：多个线程向同一条流发数据（如 SerializedSink 的排队通道） |

【源码证据】`reactor-core/src/main/java/reactor/util/concurrent/Queues.java` 第 485-492 行——三张"供给表"：

```java
    static final Supplier XS_SUPPLIER    = () -> Hooks.wrapQueue(new SpscArrayQueue<>(XS_BUFFER_SIZE));

    static final Supplier SMALL_SUPPLIER = () -> Hooks.wrapQueue(new SpscArrayQueue<>(SMALL_BUFFER_SIZE));

    static final Supplier SMALL_UNBOUNDED =
			() -> Hooks.wrapQueue(new SpscLinkedArrayQueue<>(SMALL_BUFFER_SIZE));

	static final Supplier XS_UNBOUNDED = () -> Hooks.wrapQueue(new SpscLinkedArrayQueue<>(XS_BUFFER_SIZE));
```

`Hooks.wrapQueue` 是"队列海关"——所有内部队列创建后都要过一道全局钩子（可用于包裹监控代理）。选型规律一句话：**SPSC 管单管道（一个发射者灌一个 drain 循环），MPSC 管多对一汇聚，Array 管有界、Linked 管无界**。有界队列满了，`offer` 返回 false——操作符据此触发各自的溢出策略（下一节），这就是"背压落地"的最后一米。

补充一点工程细节：`Queues.get(batchSize)`（第 113-137 行）是所有操作符拿队列的统一入口——`1` 走 `ONE_SUPPLIER`、`0` 走 `ZERO_SUPPLIER`、命中 256/32 走预建 Supplier、其余按 `max(8, batchSize)` 建有界 SpscArrayQueue；请求容量超过 **10,000,000** 时直接转无界（第 130-133 行）。3.8 中已不存在 `MAX_BUFFER_SIZE` 常量，这个 1000 万上限内联在 `get()` 里。

## 4.5 溢出对策操作符族：onBackpressure* 五种人生态度

适用前提：**源头天生不尊重 request**（热源、外部回调灌数据），普通预取救不了，只能由这一族操作符"截流定政策"。对上游它们一律 `request(Long.MAX_VALUE)` 放开灌入，把裁决权握在自己手里。

**① onBackpressureBuffer——攒着，攒不下就报错**。默认对上游无界 request，中间挂一个有界队列（默认 256，来自 `Queues.SMALL_BUFFER_SIZE`）；下游需求恢复时从队列排水。

【源码证据】`reactor/core/publisher/FluxOnBackpressureBuffer.java` 第 179-193 行——队列满时的处置：

```java
if ((capacityOrSkip != Integer.MAX_VALUE && queue.size() >= capacityOrSkip) || !queue.offer(t)) {
    Throwable ex = Operators.onOperatorError(s, Exceptions.failWithOverflow(), t, ctx);
    if (onOverflow != null) {
        try { onOverflow.accept(t); }
        catch (Throwable e) { Exceptions.throwIfFatal(e); ex.initCause(e); }
    }
    Operators.onDiscard(t, ctx);
    onError(ex);
    return;
}
drain(t);
```

注意三个细节：溢出异常先经 `Operators.onOperatorError` 包装（顺带 cancel 上游）；`onOverflow` 回调在报错前执行（其自身异常作为 cause 附加）；被丢弃的元素交给 `Operators.onDiscard`（Context 局部钩子可观测）。错误传播是**延迟**的——`checkTerminated` 保证排空队列后才发 onError（第 417-429 行，Javadoc 自述 *"the operator always delays the errors"*）。

**② onBackpressureDrop——不要的直接扔**。根本没有队列：`requested != 0` 就直发并回扣计数；`requested == 0` 就调 `onDrop` 回调 + `onDiscard` 丢弃，流继续（`FluxOnBackpressureDrop.java` 第 127-142 行）。3.8 中**没有独立的 `FluxOnBackpressureError` 文件**——`onBackpressureError()` 是 Drop 的复用（`Flux.java` 第 7099-7101 行）：

```java
public final Flux<T> onBackpressureError() {
    return onBackpressureDrop(t -> { throw Exceptions.failWithOverflow();});
}
```

用"onDrop 回调里抛溢出异常"表达 error 语义——一个能说明 Reactor 源码"组合优于新建"品味的好例子。

**③ onBackpressureLatest——只留最新的**。缓冲区就是一个 `AtomicReference<T>`：新值 `getAndSet` 覆盖旧值并 discard 旧值，下游有需求时取走当前值（`FluxOnBackpressureLatest.java` 第 132-138、170-189 行）。**不报错、不排队，只保证"下游拿到的是最新值"**——适合行情报价这类"只要当下"的场景。

**④ onBackpressureBuffer(fn, strategy)——细粒度策略版**。`BufferOverflowStrategy` 枚举三选一（`BufferOverflowStrategy.java` 第 26-42 行）：`ERROR`（满了报 IllegalStateException）、`DROP_LATEST`（丢新值、不报错）、`DROP_OLDEST`（挤掉最旧、放入新值）。实现上缓冲区直接继承 `ArrayDeque`，onNext 在 `synchronized(this)` 内按策略分支（`FluxOnBackpressureBufferStrategy.java` 第 159-203 行）——唯一用重量级锁的背压实现，因为 ArrayDeque 本身非线程安全。

**共同语言**：这一族操作符把 2.3 节的规范规则变成了用户可声明的选项——规范只说"上游不能多推"，Reactor 补齐了"多推的那个元素怎么办"的答案空间：**排队等（buffer）/ 丢新的（drop）/ 留最新（latest）/ 摊牌报错（error）**。

## 4.6 异步操作符的需求管理：flatMap 与 concatMap 的分野

**flatMap——并发 + 合并**。`FlatMapMain` 内部用数组 + free-slot 环形复用管理所有激活的内流（`FlatMapTracker`，`FluxFlatMap.java` 第 1048-1175 行）：

- 对上游的初始 request：`Operators.unboundedOrPrefetch(maxConcurrency)`（第 374 行）——默认 256（4.2 节）。
- 每个内流由 `FlatMapInner` 订阅，`limit = Operators.unboundedOrLimit(prefetch)`（第 944-949 行），消费攒到 limit 一次性补货（第 1008-1020 行）。
- drainLoop（第 596-807 行）先排 scalar 队列（内流是 `Callable` 时的标量直通优化），再 round-robin 轮询各 inner 队列；某个 inner 排空即从 tracker 移除并累计 `replenishMain`，最后 `s.request(replenishMain)` 向上游补货（第 794-796 行）——**内流完成一个，上游就补一个**，并发窗口维持 maxConcurrency。
- 队列满时以 `Exceptions.failWithOverflow(Exceptions.BACKPRESSURE_ERROR_QUEUE_FULL)`（消息原文 *"Queue is full: Reactive Streams source doesn't respect backpressure"*，`Exceptions.java` 第 49 行）经 `onOperatorError` 加入 ERROR，delayError 语义下等其他 inner 排完再报（第 881-893 行）。

**concatMap——串行 + 有序**。与 flatMap 的全部区别凝结成一个 `active` 布尔：drain 循环里只有 `!active` 才从主队列取下一个元素去 `apply` 并订阅，当前内流完成回调 `innerComplete()` 把 `active` 置 false 才放行下一个（`FluxConcatMap.java` 第 354-455 行）。错误时机由 `ErrorMode` 枚举控制（第 60-71 行）：`IMMEDIATE`（立即报错取消当前内流）/ `BOUNDARY`（等当前内流终止）/ `END`（全部终止后报）。`Flux.concatMap` 默认 `IMMEDIATE`、`concatMapDelayError` 默认 `BOUNDARY/END`（`Flux.java` 第 4090-4091、4212-4216 行分派）。

**publishOn/subscribeOn 的 request 细节**（与 5.7 节呼应）：publishOn 的 `onSubscribe` 按 `unboundedOrPrefetch(prefetch)` 向上游要第一批货（SYNC 融合则干脆不 request，直接同步排空，`FluxPublishOn.java` 第 177-213 行）；subscribeOn（`FluxSubscribeOn.java` 第 123-150 行）的精妙在**需求暂存**：下游的 request 若在上游订阅就绪前到达，先累进 `REQUESTED` 字段，上游 `onSubscribe` 到达时 `getAndSet(this, 0L)` 清零并补发 `requestUpstream`——且 `requestOnSeparateThread=false` 时（`subscribeOn(scheduler, false)`，处理阻塞源防死锁的场景）request 会在当前线程直发而不搬到 worker。

## 4.7 本章小结

- 背压的三种角色：**透明转发**（map/filter）、**预取缓冲**（flatMap/publishOn：把无界需求翻译成分批 request + 内部队列）、**截流决策**（onBackpressure*）。
- 两个魔数 `256`/`32`（`reactor.bufferSize.small`/`reactor.bufferSize.x`）定义了全部默认批量；`flatMap` 默认 `concurrency=256, prefetch=32`。
- 补货公式 `unboundedOrLimit = prefetch - (prefetch >> 2)`：**75% 水位一次性补足**，批量化与平稳性兼得。
- 队列三剑客全自研：**SpscArrayQueue（有界单管道）/ SpscLinkedArrayQueue（无界单管道）/ MpscLinkedQueue（多对一）**，经 `Hooks.wrapQueue` 统一出口。
- onBackpressure* 族的答案空间：**buffer（攒）/ drop（丢）/ latest（留新）/ error（摊牌）**，上游一律无界 request、裁决权在手。
- flatMap 与 concatMap 的差异只是一个 `active` 布尔：并发窗口开不开。


# 五、调度器与线程模型（reactor.core.scheduler）

> 本章对应源码：`reactor-core/src/main/java/reactor/core/scheduler/`（26 个文件）。3.8 的调度器体系与网上旧资料差异较大（`TimedScheduler` 已移除、缓存机制重构、虚拟线程经 Multi-Release JAR 实现），本章全部以 3.8.8 快照实证为准。

## 5.1 先说白话：异步边界由谁执行

响应式流规范只定义了信号语义，完全没说"信号在哪个线程上跑"。Reactor 的答案：**默认不切换——信号沿订阅链在"触发它的那个线程"上流动**；需要改变时，用 `publishOn`/`subscribeOn` 把某段链路"钉"到一个 `Scheduler` 上。`Scheduler` 类 Javadoc 的一句话就是本章的纲（`scheduler/Scheduler.java` 第 29-34 行）：

```java
/**
 * Provides an abstract asynchronous boundary to operators.
 * ...
 */
```

包内 26 个类按职责分四组：

```
reactor.core.scheduler
├── 抽象与工厂：Scheduler（接口）、Worker（内部接口）、Schedulers（工厂门面）
├── 三大实现：ParallelScheduler、SingleScheduler、BoundedElasticScheduler
│   └── JDK21 专属：BoundedElasticThreadPerTaskScheduler（虚拟线程版）
├── 适配器：DelegateServiceScheduler（包 ExecutorService）、ExecutorScheduler（包 Executor）、
│          ImmediateScheduler（当前线程直跑）、SingleWorkerScheduler
└── 运行设施：SchedulerTask/WorkerTask/Periodic*Task（任务包装）、
             SchedulerState（生命周期）、ReactorThreadFactory/VirtualThreadFactory（线程工厂）、
             SchedulerMetricDecorator（指标）、ReactorBlockHoundIntegration（阻塞检测）
```

## 5.2 Scheduler 接口与 Worker：两层执行单元

【源码证据】`scheduler/Scheduler.java` 第 39 行 `public interface Scheduler extends Disposable`。完整方法面：

| 方法 | 行号 | 语义 |
|---|---|---|
| `Disposable schedule(Runnable)` | 53 | 提交一次性任务；Javadoc 强调 *"safe to be called from multiple threads but there are no ordering guarantees between tasks"*（44-46 行） |
| `schedule(Runnable, long, TimeUnit)` | 68-70 | 延迟任务（default 实现：抛"不支持时间"异常） |
| `schedulePeriodically(Runnable, long, long, TimeUnit)` | 90-92 | 固定速率周期任务 |
| `long now(TimeUnit)` | 106-112 | 调度器视角的"当前时间"；Javadoc 声明只保证 *"monotonicity inside the current JVM"*（98-101 行） |
| `Worker createWorker()` | 126 | 创建 Worker（下层执行单元） |
| `dispose()` / `disposeGracefully()` | 140-141 / 157-159 | 立即关闭 / 优雅关闭（3.8 新增，返回 `Mono<Void>` 可等待） |
| `start()`（已废弃）/ `init()` | 176-177 / 187-189 | 生命周期初始化 |

**Worker 是比 Scheduler 更小的执行单位**（`Scheduler.Worker`，第 197 行起）：同一个 Scheduler 的不同 Worker 可以共享底层线程池（parallel/single 就是如此），也可以独占资源（boundedElastic 的 BoundedState 绑定）。操作符层永远只面对 `createWorker()` + `Worker.schedule(...)` 两个 API——**操作符不关心池子长什么样，这是线程策略与信号逻辑解耦的关键**。

## 5.3 Schedulers 工厂门面与全局缓存

`scheduler/Schedulers.java` 是用户唯一的日常入口。内置五类调度器：

| 工厂方法 | 行号 | 默认实现 | 线程特征 |
|---|---|---|---|
| `parallel()` | 295-297 | `ParallelScheduler`，池大小 = CPU 核数（`DEFAULT_POOL_SIZE`，82-85 行，可被 `reactor.schedulers.defaultPoolSize` 覆盖） | 计算型，**NonBlocking**（禁阻塞） |
| `single()` | 1063-1065 | `SingleScheduler`，1 个线程 | 全局共享单线程，**NonBlocking** |
| `boundedElastic()` | 277-279 | `BoundedElasticScheduler`，线程上限 `10×CPU`（95-98 行），每线程任务队列上限 100000（107-110 行），空闲 TTL 60s | 允许阻塞的弹性池 |
| `immediate()` | 309-311 | `ImmediateScheduler.instance()` | 不切线程，当前线程直接 `task.run()`（`ImmediateScheduler.java` 第 52-55 行） |
| `fromExecutorService(...)` | 192-196 | `DelegateServiceScheduler` 包一层 | 用户自带线程池 |

**全局缓存 = 三个 `AtomicReference`**（第 1214-1222 行的 `CACHED_BOUNDED_ELASTIC/CACHED_PARALLEL/CACHED_SINGLE`）+ `cache()` 方法（1257-1273 行）：首次调用 `Schedulers.parallel()` 时创建实例并 CAS 进缓存，之后 everyone 共享同一实例；竞态下多造的那个实例会被立即 `_dispose()`（源码 Javadoc 自述 *"an extraneous Scheduler can be created, but it'll get immediately disposed"*）。

缓存实例的安全设计藏在 `CachedScheduler`（1298-1381 行）里：**它对外的 `dispose()` 是空操作**（1347-1349 行），防止某段代码误杀全局共享实例；真正释放走 `_dispose()`（1378-1380 行），只有 `Schedulers.shutdownNow()`（1039-1047 行）和 `setFactory()`（851-855 行）会触发。

另有一条常被忽略的"任务拦截链"：`Schedulers.onScheduleHook(key, function)`（962-976 行）注册的装饰器会在**每次** `directSchedule/workerSchedule` 提交前把 `Runnable` 包一层（`onSchedule(task)`，1388/1407/1437/1469 行）——MDC 传递、安全上下文透传等横切需求都走这里。

## 5.4 三大实现逐个看

**ParallelScheduler——CPU 核数个单线程池，轮询分流**。`init()`（107-112 行）创建 n 个 `ScheduledThreadPoolExecutor(1, factory)`（`get()` 80-86 行：核心/最大 1、`setRemoveOnCancelPolicy(true)`），`pick()`（215-237 行）用非原子 `roundRobin` 轮询分配；`createWorker()`（288-291 行）返回共享该池的 `ExecutorServiceWorker`。**它不保证任务全局有序**——两个 Worker 可能在不同线程同时跑，这正是"parallel 用于无状态计算"的语义注脚。

**SingleScheduler——一个池，万事有序**。`get()`（72-77 行）同样是单线程 `ScheduledThreadPoolExecutor`；所有 Worker 共享同一 executor（`createWorker()` 231-233 行）。适合"全局串行化"场景（如单消费者的事件循环模拟）。

**BoundedElasticScheduler——最精巧的一个**。核心结构：

- `BoundedServices extends AtomicInteger`（435 行）：维护 `busyStates`（无锁 CAS 数组，541-558 行）+ `idleQueue`（Deque）；`evictor` 单线程调度器每 `ttlMillis` 跑一次 `eviction()`（526-535 行）。
- `pick()`（617-661 行）的取用优先级：**空闲队列 `pollLast()` → 未达 `maxThreads` 则 CAS 新建 → 都满了挑 `markCount` 最小的忙线程**（`choseOneBusy`，663-685 行）。
- 每个"槽"是 `BoundedState`（726-868 行）：`MARK_COUNT` 记录挂载的 Worker 数，`release`（807-823 行）减到 0 时记录空闲时间戳入 idle 队列，`tryEvict`（785-796 行）在空闲超 TTL 且 `CAS(0, EVICTED)` 成功时 `executor.shutdownNow()`——**线程是按需生长、空闲回收的**。

```java
	public Worker createWorker() {
		BoundedState picked = state.currentResource.pick();
		ExecutorServiceWorker worker = new ExecutorServiceWorker(picked.executor);
		worker.disposables.add(picked); //this ensures the BoundedState will be released when worker is disposed
		return worker;
	}
```

（`BoundedElasticScheduler.java` 第 427-433 行——Worker 销毁时自动释放挂载计数。）

- **任务队列上限与拒绝**：内部 `BoundedScheduledExecutorService extends ScheduledThreadPoolExecutor`（885-1104 行）在所有提交入口前置 `ensureQueueCapacity`（920-928 行）——队列任务数超上限（默认 10 万/线程）直接抛 `RejectedExecutionException`。这就是"boundedElastic 不会无限吞任务"的机制保证：**线程数有上限 + 每线程排队有上限，双保险防 OOM**。

## 5.5 虚拟线程支持：Multi-Release JAR 的教科书实现

3.6 起支持把 boundedElastic 的任务跑在**虚拟线程**上（JDK 21+ 运行时 + `-Dreactor.schedulers.defaultBoundedElasticOnVirtualThreads=true`，`Schedulers.java` 第 120-123 行）。实现分三层：

1. **主源码的占位类**：`BoundedElasticThreadPerTaskScheduler` 构造器直接抛 `UnsupportedOperationException("Unsupported in JDK lower than 21")`（main 源码集，35-37 行）。
2. **java21 源码集的同名实现**（`src/main/java21/...`，约 1377 行）：每个"槽"是 `SequentialThreadPerTaskExecutor`（498 行），用 VarHandle 位域 `wipAndRefCnt`（855-863 行，WIP 31 位/引用计数 31 位/SHUTDOWN 标志）管理；任务入队后 `drain()`（769-804 行）逐个 `task.start()`——每个任务 `factory.newThread(this)` 起一条**新的虚拟线程**（1153-1169 行）。
3. **供应商切换**：`BoundedElasticSchedulerSupplier` 的 JDK21 覆盖版（`src/main/java21/.../BoundedElasticSchedulerSupplier.java` 第 37-48 行）在开关打开时返回虚拟线程版调度器，否则回退平台线程版；JDK<21 的 main 版供应商（39-44 行）只打警告回退。

延迟/周期任务先挂到共享的 `sharedDelayedTasksScheduler`（391 行，`loomBoundedElastic-delayed-tasks-scheduler-N` 单守护线程）到期后转投队列——虚拟线程不擅长挂起定时，Reactor 用一个平台线程专门管时间。

## 5.6 任务包装：SchedulerTask/WorkerTask 如何保证"取消后不执行"

`Scheduler.schedule` 返回的 `Disposable` 不是裸 `Future`，而是包装过的 `SchedulerTask`/`WorkerTask`——它们解决一个经典竞态：**dispose 与任务执行同时发生时，`future.cancel()` 可能来不及**。解法是"双原子状态机"：

【源码证据】`scheduler/SchedulerTask.java`（核心逻辑，`call()` 第 58-92 行、`dispose()` 第 122-147 行）：

```java
	public void run() {                                  // call()：只有抢到 parent 才执行
		Disposable o = PARENT.getAndSet(this, TAKEN);
		if (o == CANCELLED || o == TAKEN) {
			return;
		}
		try {
			task.run();
		}
		...
	}

	@Override
	public void dispose() {
		Disposable o = PARENT.getAndSet(this, CANCELLED);
		if (o == CANCELLED || o == TAKEN) {
			return;
		}
		Future<?> f = future;
		if (f != null) {
			f.cancel(thread != Thread.currentThread());  // 异线程才 interrupt
		}
		if (o != null) o.dispose();                      // 联动父容器
	}
```

`PARENT` 与 `FUTURE` 两个原子更新器构成"先到先得"的裁判：任务执行前 CAS 抢占 `PARENT`，抢占失败（已被取消）直接退出；`dispose` 先抢 `PARENT` 成功再取消 `Future`——两侧必有且只有一侧成功，**任务体要么完整执行、要么完全不执行**。`WorkerTask`（Worker 版）额外区分同步/异步取消（`SYNC_CANCELLED/ASYNC_CANCELLED` 哨兵，源自 issue #1107：**自取消不应中断当前线程**），并把自身挂进 `Disposable.Composite` 供 Worker 统一注销。

周期任务另有两个变体：`PeriodicSchedulerTask`/`PeriodicWorkerTask`，以及处理 `period<=0` 的 `InstantPeriodicWorkerTask`（26-30 行 Javadoc："先 submit 再续期"，规避 `scheduleAtFixedRate` 对非正周期的 `IllegalArgumentException`）。

## 5.7 subscribeOn vs publishOn：一条链的线程归属

两个操作符是调度器与信号流的接合处，区别在"切的是哪个方向的信号"：

**publishOn——切"下游信号"（onNext/onError/onComplete）的线程**。`publishOn` 在组装期只是普通套娃；订阅期 `FluxPublishOn` 在 `onSubscribe` 里 `scheduler.createWorker()`（`FluxPublishOn.java` 第 87 行），此后每个上游信号到达时经 `worker.schedule()` 转投到调度器线程再发给下游——**它像一个"接力站"：站前的线程随便，站后的信号一律在调度器线程上**。同一条链上每个 `publishOn` 都重置一次"下游线程"。预取的补货公式（4.3 节）也落在这里：`onSubscribe` 按 `unboundedOrPrefetch(prefetch)` 向上游要第一批货（SYNC 融合则不 request 直接同步排空，第 177-213 行），`runAsync` 消费攒到 limit（75% 水位）时一次性 `s.request(e)` 补足（第 451-457 行）。

**subscribeOn——切"订阅动作 + 源头行为"的线程**。`MonoSubscribeOn` 在订阅时就 `scheduler.createWorker()`（`MonoSubscribeOn.java` 第 48 行），把"向上游发起订阅"这个动作本身 schedule 到 Worker 上执行——于是**源头的 `subscribe()`、同步数据源的产生、乃至源头线程的后续信号**都发生在调度器线程。它只对"订阅路径"有效，且**链条上第一个（最靠近源头的）`subscribeOn` 赢**——后面的会被前面的覆盖（对源头而言只剩一个订阅动作）。

一张速查表：

| | publishOn | subscribeOn |
|---|---|---|
| 切换对象 | 下游的**信号**（onNext 之后的一切） | **订阅动作**与源头执行 |
| 生效位置 | 每个 `publishOn` 都生效（就近原则） | 只有**最靠近源头**的那个生效 |
| 常见用途 | 消费端切线程池、隔离慢消费者 | 把阻塞 IO 搬离当前线程 |
| Worker 生命周期 | onSubscribe 时创建 | 订阅时创建 |

## 5.8 NonBlocking 标记与阻塞检测

【源码证据】`scheduler/NonBlocking.java` 第 28 行——纯标记接口：

```java
public interface NonBlocking { }
```

Javadoc：*"A marker interface that is detected on Threads while executing Reactor blocking APIs, resulting in these calls throwing an exception."* 判定入口 `Schedulers.isNonBlockingThread()`（695-697 行）：线程实现 `NonBlocking`、或命中用户自定义谓词、或是 `NonBlockingThread`（`ReactorThreadFactory.java` 第 44-60 行，`rejectBlocking=true` 时生成）。parallel/single/虚拟线程调度器的线程都是 NonBlocking，boundedElastic 平台线程不是——**这正对应"parallel 上禁阻塞、boundedElastic 上可阻塞"的使用约定**。

运行期兜底是 BlockHound 集成（`scheduler/ReactorBlockHoundIntegration.java`）：把 Reactor 内部已知的"合法阻塞点"（如 `ScheduledThreadPoolExecutor$DelayedWorkQueue.offer/take`，第 31-37 行）加入白名单，其余阻塞调用在线程检测时直接抛异常。它 + `Mono.block()` 在 parallel 调度器上的组合，是响应式系统里"阻塞泄漏"最常见的现场取证工具。

## 5.9 本章小结

- `Scheduler`（异步边界抽象）→ `Worker`（执行单元）两级抽象；操作符只见 `createWorker()/Worker.schedule`，不感知池形态。
- `Schedulers` 工厂 + **三个 `AtomicReference` 全局缓存**；`CachedScheduler` 把公开 `dispose()` 做成空操作，防误杀共享实例。
- 三大实现三种性格：**parallel**（n 个单线程池轮询、有序性不保证）、**single**（单线程全局串行）、**boundedElastic**（按需生长/空闲回收的线程槽 + 每线程 10 万任务硬上限 + 拒绝异常）。
- 虚拟线程支持经 **Multi-Release JAR**（main 占位抛异常 + java21 覆盖实现）落地，时间任务走共享平台线程。
- `SchedulerTask/WorkerTask` 用双原子状态机保证"**取消后必不执行**"，并区分同步/异步取消防止自中断。
- `publishOn` 切下游信号（每个都生效）、`subscribeOn` 切订阅与源头（最靠源者赢）——这两个规则配 `NonBlocking` 标记 + BlockHound，构成 Reactor 线程模型的全部日常。


# 六、错误处理与重试

> 本章对应源码：`reactor/core/Exceptions.java`、`reactor/core/publisher/Operators.java`、`Hooks.java`、`FluxOnErrorResume/FluxOnErrorReturn/FluxRetry/FluxRetryWhen/FluxTimeout`、`reactor/util/retry/` 包。

## 6.1 先说白话：onError 是一条"单行道"

响应式流里错误不是异常抛出，而是一个**信号**：上游 `onError(t)` 一次，流宣告死亡。这带来与同步世界完全不同的两套问题：

1. **信号怎么传播**：一个操作符内部出错（比如 mapper 抛异常）时，是"终止整条流"还是"跳过这个元素继续"？——这由 `Operators.onNextError` 与 `OnNextFailureStrategy` 的查表机制决定（6.2 节）。
2. **流死了怎么办**：`onErrorResume` 换备胎、`onErrorReturn` 给默认值、`retryWhen` 重开一条流。注意**都是"订阅一条新的流"**，原流本身救不活——"复活"的本质是重新订阅。

## 6.2 Operators 工具箱：错误传播的中央调度

`Operators` 是全体操作符共享的静态工具箱，错误相关的四组方法值得背下来：

**① onOperatorError——操作符自身出错的统一出口**。

【源码证据】`reactor/core/publisher/Operators.java` 第 752-779 行（核心三参重载）：

```java
	public static Throwable onOperatorError(@Nullable Subscription subscription,
			Throwable error, @Nullable Object dataSignal, Context context) {
		Exceptions.throwIfFatal(error);
		if(subscription != null) { subscription.cancel(); }
		Throwable t = Exceptions.unwrap(error);
		BiFunction<? super Throwable, @Nullable Object, ? extends Throwable> hook =
				context.getOrDefault(Hooks.KEY_ON_OPERATOR_ERROR, null);
		if (hook == null) { hook = Hooks.onOperatorErrorHook; }
		if (hook == null) {
			if (dataSignal != null && dataSignal != t && dataSignal instanceof Throwable) {
				t = Exceptions.addSuppressed(t, (Throwable) dataSignal);
			}
			return t;
		}
		return hook.apply(error, dataSignal);
	}
```

四步语义：**fatal 直接抛**（`StackOverflowError` 这类不再包装）→ **立刻 cancel 上游**（止血）→ **剥掉包装**（`Exceptions.unwrap`）→ 无钩子时把"出错的那个数据"作为 suppressed 附加（现场证据保留）。`onErrorResume`、`onBackpressureBuffer`、`publishOn` 队列满……几乎所有操作符的报错都先过这里。

**② onNextError + OnNextFailureStrategy——"一个元素失败"的两种人生**。这是理解 `onErrorContinue` 的钥匙：

【源码证据】`Operators.java` 第 797-811 行（策略查找）与 `reactor/core/publisher/OnNextFailureStrategy.java` 第 41-47、144-158 行：

```java
	static final OnNextFailureStrategy onNextErrorStrategy(Context context) {
		OnNextFailureStrategy strategy = null;
		Function<? super Throwable, ? extends Throwable> fn = context.getOrDefault(
				OnNextFailureStrategy.KEY_ON_NEXT_ERROR_STRATEGY, null);
		...
		if (strategy == null) strategy = OnNextFailureStrategy.STOP;
		return strategy;
	}
```

```java
interface OnNextFailureStrategy extends BiFunction<Throwable, Object, Throwable>,
                                          BiPredicate<Throwable, Object> {
	String KEY_ON_NEXT_ERROR_STRATEGY = "reactor.onNextError.localStrategy";
	...
	OnNextFailureStrategy STOP = new StopStrategy(); // test→false：process 直接抛异常
```

策略的查找顺序：**Context 局部键 → `Hooks.onNextErrorHook` → 默认 `STOP`**。`STOP` 意为"一个元素失败 = 整条流终止"（转 onError）；而 `onErrorContinue(...)` 的实现正是把一个 `LambdaOnNextErrorStrategy` 塞进 Context——下游每个操作符的 `onNext` 错误分支都会查这个表（回看 3.4 节 `MapSubscriber.onNext` 的 catch 块：策略返回 null 就丢值补 `request(1)` 继续）。**这也解释了 `onErrorContinue` 的恶名来源**：它是操作符各自"自觉遵守"的约定，上游若不查表（如某些异步操作符）就不生效，行为依赖链路细节，官方文档也把它标注为"最后的手段"。

**③ onErrorDropped / onNextDropped——无处安放的信号**。终态之后再来的错误/数据走这里（`Operators.java` 第 667-702 行）：优先查 Context 局部钩子 → 全局 `Hooks.onErrorDroppedHook` → 兜底 `log.error("Operator called default onErrorDropped", e)`。生产上常见的"错误日志满天飞但流其实正常"多半就是它。

**④ 校验与饱和运算**。`validate(current, next)`（1342-1351 行，防二次订阅竞态）、`validate(long n)`（1358-1364 行，`n<=0` 记 reportBadRequest）、`addCap`（饱和加法，`Long.MAX_VALUE` 封顶）——规范 3.9 条"非法 request"的工程化落地。

**BaseSubscriber：给用户的"钩子式"订阅者**。`reactor/core/publisher/BaseSubscriber.java` 第 50-51 行 `implements CoreSubscriber, Subscription, Disposable`，全部信号方法都是 `final`，逻辑转入可覆盖钩子：`hookOnSubscribe`（默认 `request(Long.MAX_VALUE)`，第 91-93 行）、`hookOnNext`（NO-OP，103-105 行）、`hookOnError`（默认抛 `errorCallbackNotImplemented`，120-122 行）、`hookOnCancel/hookFinally`。这是需要精细控制 request（如逐个拉取）时的推荐姿势。

## 6.3 恢复操作符：备胎、默认值、超时

**onErrorResume——换一条流**。

【源码证据】`reactor/core/publisher/FluxOnErrorResume.java` 第 57-68、88-109 行（ResumeSubscriber 核心）：

```java
	@Override
	public void onError(Throwable t) {
		if (!second) {
			second = true;
			Publisher<? extends T> p;
			try {
				p = Objects.requireNonNull(nextFactory.apply(t), "The nextFactory returned a null Publisher");
			}
			catch (Throwable e) {
				Throwable e_ = Operators.onOperatorError(...);
				e_ = Exceptions.addSuppressed(e_, t);
				actual.onError(e_);
				return;
			}
			p.subscribe(this);              // 关键：用自己重新订阅备用流
		}
		else {
			actual.onError(t);
		}
	}
```

妙处在 `p.subscribe(this)`：**同一个订阅者实例二次上岗**（`second` 标志防再入），`MultiSubscriptionSubscriber` 基类负责无缝切换上游 Subscription。若备用流的工厂抛异常，原错误作为 suppressed 一并上报。`onErrorReturn`/`onErrorComplete` 是它的孪生：`ReturnSubscriber`（`FluxOnErrorReturn.java` 第 60-62 行）把 `requested` 字段复用成状态机（`STATE_CANCELLED=-3/STATE_TERMINATED=-2/STATE_PENDING_FALLBACK=-1`）——**默认值到达时下游还没 request（r == 0）就先记 `PENDING_FALLBACK`，等 request 来了再补发**（第 131-145 行）。

**timeout——给每一步上闹钟**。`FluxTimeout.java` 第 167-205 行：每个元素到达时 CAS 递增 `INDEX` 并取消旧闹钟、按 `itemTimeout.apply(t)` 挂新闹钟；闹钟触发时 `doTimeout` 用 `INDEX.compareAndSet(i, Long.MIN_VALUE)` 保证只有"当前序号"的闹钟能响（280-284 行，防陈旧闹钟误伤）。无 fallback 时报 `TimeoutException("Did not observe any item or terminal signal within ... (and no fallback has been configured)")`（294-305 行）——这条报错是生产环境的常客。

## 6.4 重试体系：Retry 规范与退避算法

`reactor.util.retry` 包把"重试策略"抽象成一个**响应式规范发布者**（companion）：错误信号以 `RetrySignal` 形式发给它，它发 onNext 就是"允许重试"、发 onError 就是"放弃"。

【源码证据】`reactor/util/retry/Retry.java` 第 62-78 行（Javadoc 摘要）与第 88 行：

```java
	public abstract Publisher<?> generateCompanion(Flux<RetrySignal> retrySignals);
```

`FluxRetryWhen` 把两者接起来（`reactor/core/publisher/FluxRetryWhen.java`）：

- 订阅侧持有一个内部 `Sinks.Many<RetrySignal> signaller`（第 100-102 行）；`onError` 时把自身（实现了 `Retry.RetrySignal`）emit 进 signaller，**然后**向 companion `request(1)`（第 185-198 行，注释原文 *"request after signalling, otherwise it may race"*）。
- companion 每发一个 onNext → `RetryWhenOtherSubscriber.onNext` → `main.resubscribe(t)`（第 276-278 行）：WIP 守护的 do-while 里 `source.subscribe(this)` 整流重开（第 208-225 行）；触发信号若是 `ContextView` 还会把其中的 Context 并入订阅上下文（retryContext 传递）。
- companion 发 onError/onComplete → 视为"重试策略放弃/正常结束"，取消一切并向 actual 传播（281-288 行）。

`Retry` 的三个静态工厂（`Retry.java` 第 167-235 行）：`max(maxAttempts)`/`maxInARow` 返回 `RetrySpec`（次数控），`backoff(maxAttempts, minBackoff)` 返回 `RetryBackoffSpec`，`fixedDelay` 是 backoff 的特例（min==max、jitter=0）。

**退避算法**（`RetryBackoffSpec`）：

【源码证据】`reactor/util/retry/RetryBackoffSpec.java` 第 612-621 行（指数）与第 629-653 行（抖动）：

```java
	long nextBackoffLong = (long) (minBackoffLong * Math.pow(multiplier, iteration));
	nextBackoff = Duration.ofNanos(nextBackoffLong);
	if (nextBackoff.compareTo(maxBackoff) > 0) { nextBackoff = maxBackoff; }
	...
	jitterOffset = nextBackoff.multipliedBy((long)(100*jitterFactor)).dividedBy(100);
	lowBound = max(minBackoff.minus(nextBackoff), jitterOffset.negated());
	highBound = min(maxBackoff.minus(nextBackoff), jitterOffset);
	jitter = random.nextLong(lowBound, highBound);
	effectiveBackoff = nextBackoff.plusMillis(jitter);
```

默认参数：`multiplier=2、jitter=0.5、maxBackoff=Long.MAX_VALUE 毫秒`（`Retry.java` 第 167-171 行与 `RetrySpec.java` 第 55 行）；`iteration >= maxAttempts` 时以 `Exceptions.retryExhausted("Retries exhausted: ...")`（cause=最后一次失败）放弃（第 606-608 行、`Exceptions.java` 第 307-309 行）。抖动计算保证 `effectiveBackoff ∈ [minBackoff, maxBackoff]`。计数器语义：`totalRetries()` = 累计重试次数、`totalRetriesInARow()` = 连续重试次数（`FluxRetryWhen.java` 第 134-141 行；中间成功一次就清零后者，第 177-179 行）。`transientErrors(true)` 切换到"只数连续失败"口径。

简单版 `Flux.retry(long)` 则是裸计数重订阅：`RetrySubscriber.remaining` 减到 0 就透传 onError（`FluxRetry.java` 第 93-125 行），无退避、立即循环——只适合本地快速失败场景。

## 6.5 Exceptions：组合异常与包装体系

`reactor/core/Exceptions.java` 的关键设施：

| 设施 | 行号 | 用途 |
|---|---|---|
| `BACKPRESSURE_ERROR_QUEUE_FULL` | 49 | "Queue is full: Reactive Streams source doesn't respect backpressure" 的统一消息 |
| `TERMINATED` 哨兵 | 56 | `addThrowable` 等原子状态机的"已终结"标记 |
| `failWithOverflow()` | 237-252 | `OverflowException("The receiver is overrun by more signals than expected (bounded queue...)")` |
| `multiple(...)` → `CompositeException` | 137-177 | 把多个异常合并为一个，各异常以 `addSuppressed` 挂载（`zip` 等多源操作符同时失败时用） |
| `bubble(Throwable)` | 188-191 | 包成 `BubblingException` 冲出"没有下游可报错"的现场（如 lambda 内无法传播时） |
| `unwrap(Throwable)` | 544-551 | 循环剥掉 `ReactiveException` 包装取真因（`onOperatorError` 依赖它） |
| `retryExhausted` | 307-309 | 重试耗尽的 `RetryExhaustedException` |

`multiple/unwrapMultiple` 成对出现：多源错误合并成组合异常、诊断时再解开——这是对"一次只能报一个错"的规范限制的官方补丁。

## 6.6 Hooks：全局干预的七个开关

`reactor/core/publisher/Hooks.java` 的静态钩子字段（第 604-614 行）：`onEachOperatorHook`、`onLastOperatorHook`、`onOperatorErrorHook`、`onErrorDroppedHook`、`onNextDroppedHook`、`onNextErrorHook`。三个使用要点：

1. **命名钩子可组合**：`onEachOperator(key, fn)` 用 `LinkedHashMap` 按插入序 + `andThen` 串成链（第 619-621、575-587 行），`onOperatorError(key, fn)` 嵌套组合（589-602 行）。
2. **Context 局部优先**：每个全局钩子都有对应的 Context 键（`KEY_ON_ERROR_DROPPED="reactor.onErrorDropped.local"` 第 644 行、`KEY_ON_OPERATOR_ERROR` 654 行、`KEY_ON_DISCARD` 660 行…），查找顺序都是"局部 → 全局"——`onErrorContinue` 与丢弃策略因此能按流粒度生效。
3. **GLOBAL_TRACE 只读一次**：`Hooks.GLOBAL_TRACE = initStaticGlobalTrace()`（第 669、681-683 行）读系统属性 `reactor.trace.operatorStacktrace`，启动后不可改——这是"组装点堆栈"的老式全局开关，现代替代品见 9.3 节。

## 6.7 本章小结

- 错误是**信号不是异常**；"恢复"的本质永远是**重新订阅**：`onErrorResume` 换流（同一订阅者二次上岗）、`onErrorReturn` 状态机延迟补发默认值、`retryWhen` 整流重开。
- `Operators.onOperatorError` 是操作符报错的统一出口：**fatal 直抛 → cancel 上游 → unwrap → 附加现场数据**。
- `onErrorContinue` 的真身是 `OnNextFailureStrategy`（Context 键 `reactor.onNextError.localStrategy`）：每个操作符 onNext 错误分支查表，查不到默认 `STOP`（终止）。它是"约定"而非"机制"，用前三思。
- 重试规范 = `Retry.generateCompanion`：错误流进 companion，companion 的 onNext 就是重试指令；`RetryBackoffSpec` 默认 `multiplier=2、jitter=0.5`，指数退避 + 抖动夹在 `[minBackoff, maxBackoff]`。
- `Exceptions.multiple` 补丁了"规范一次只能报一个错"的限制；`unwrap` 让包装异常在钩子间可透视。

# 七、Context：订阅树上的不可变上下文

> 本章对应源码：`reactor/util/context/`（12 个文件）与 `reactor/core/publisher/` 下的 `FluxContextWrite*`、`ContextPropagation*` 系列。

## 7.1 先说白话：ThreadLocal 在异步世界失灵了

同步代码里，"当前用户是谁、当前事务是什么、TraceId 是什么"都挂在 `ThreadLocal` 上。可响应式流水线里，**一个信号的加工可能跨越 N 个线程**：`publishOn` 一切线程，`flatMap` 扇出，`flatMap` 的内层又在别的线程完成——ThreadLocal 在"线程切换"的那一瞬就断了。

Reactor 的答案是把"上下文"从线程身上解绑，绑到**订阅树**上：每个订阅都携带一份不可变的键值对（`Context`），操作符在**订阅时**把它自下而上传给上游，任何操作符/源在需要时用 `currentContext()` 读。一句话对比：

- **ThreadLocal**：跟着线程走，自上而下（父线程 → 子线程需要额外传播）；
- **Context**：跟着订阅走，自下而上（下游消费者 → 上游生产者），方向与数据流相反。

为什么方向"反"？想想 `flatMap` 内层的 HTTP 调用要拿到"当前认证信息"：认证信息是**订阅端**（最下游）关心的东西，必须能被链条中游的任何操作符读到——所以它从下游出发，逆流而上。

## 7.2 Context 接口与不可变实现链

【源码证据】`reactor-core/src/main/java/reactor/util/context/Context.java` 第 39 行与第 34-35 行 Javadoc：

```java
public interface Context extends ContextView { ... }
```

> "Past five user key/value pair, the {@link Context} will use a copy-on-write implementation backed by a new {@link java.util.Map} on each {@link #put}."

读写视图分离：`ContextView` 是只读面（`get/getOrDefault/hasKey/stream/...`，`ContextView.java` 第 38-149 行），`Context` 才有 `put/delete/putAll`。**每次 `put` 都返回新对象，原对象永不改变**——这是多订阅者共享同一订阅树时不出竞态的根本保障。

实现是一条"字段式 → Map 式"的升档链（全包私有）：

```
Context0（单例，empty()）→ Context1 → Context2 → Context3 → Context4 → Context5
                                                                  ↓ 第 6 对
                                              ContextN extends LinkedHashMap（写时复制）
```

- `Context0.INSTANCE` 是全局唯一空实例（`Context0.java` 第 27 行）；`Context1.put` 同 key 返回新 `Context1`、否则升 `Context2`（`Context1.java` 第 37-46 行）——**字段式实现逐 key 比对，零哈希开销**。
- 第 6 对触发升档：`Context5.put` 直接 `return new ContextN(key1..key5, key, value);`（`Context5.java` 第 83 行）。
- `ContextN.put` 是教科书式写时复制（`ContextN.java` 第 83-87 行）：

    ```java
    ContextN newContext = new ContextN(this);
    newContext.accept(key, value);
    return newContext;
    ```

    （`ContextN` 实现 `BiConsumer`，`accept` 即内部 `super.put`，方便 `forEach` 直传。）`delete` 降到 5 对时反向退档回 `Context5`（第 96-113 行）。
- 内部优化接口 `CoreContext` 提供 `putAllInto/unsafePutAllInto` 批量合并快路径（`CoreContext.java` 第 26-68 行）；`putAll` 合并结果 ≤5 对时还会重新折回字段式实现（`Context.java` 第 265-281 行）。

这套"小上下文零分配、大上下文写时复制"的设计与 `Context` 在每个订阅/每个信号上被传递的频率直接相关——它必须便宜。

## 7.3 传播机制：currentContext 自下而上

Context 在订阅握手时传播。`CoreSubscriber.currentContext()` 是唯一的读取口；写入口是 `contextWrite` 操作符：

【源码证据】`reactor/core/publisher/FluxContextWrite.java` 第 41-45 行：

```java
	@Override
	public CoreSubscriber<? super T> subscribeOrReturn(CoreSubscriber<? super T> actual) {
		Context c = doOnContext.apply(actual.currentContext());
		return new ContextWriteSubscriber<>(actual, c);
	}
```

回忆 3.5 节：订阅时 `subscribeOrReturn` 自下游向上游逐层调用——于是 `contextWrite` 在"穿马甲"的瞬间就能读到**下游已累积的 Context**，应用修改后作为新马甲的 `currentContext()` 返回（第 91-93 行）。上游订阅时读到的就是修改后的值。把 3.5 节的时序图补上 Context 流向：

```
LambdaSubscriber.currentContext() = 初始 Context（subscribe(consumer, ctx) 传入，或 empty）
      ↑ 自下而上
FilterSubscriber.currentContext() = 下游的
      ↑
ContextWriteSubscriber.currentContext() = modifier(下游的)   ← contextWrite 在这里改写
      ↑
FlatMapMain.currentContext() = 改写后的
      ↑ …直至源头
```

**三个推论**：

1. **下游写、上游读**：`contextWrite` 只影响它**上游**的操作符/源（`MonoContextWrite` 直接复用 `FluxContextWrite` 的订阅者，`MonoContextWrite.java` 第 38-42 行）。
2. **读取靠 currentContext 逐层冒泡**：任何 `CoreSubscriber` 默认返回 `Context.empty()`，包装链上每层要么透传要么改写——读取方（如 `FluxDeferContextual`，订阅时拿 `actual.currentContext()` 当工厂入参生成流，`FluxDeferContextual.java` 第 45-59 行）总是看到"从它往下游回溯到最近一个 contextWrite"的合成结果。
3. **入口的严格适配**：外部裸 `Subscriber` 进入时被 `StrictSubscriber` 严格包装（`Operators.toCoreSubscriber`，`Operators.java` 第 1376-1390 行）——规范世界进入 Reactor 世界的又一次"海关"。

## 7.4 ThreadLocal 桥接：与 micrometer context-propagation 的握手

Context 再好，生态里仍有大量必须用 ThreadLocal 的存量设施（MDC、SecurityContext、OpenTelemetry Scope）。3.7 起 Reactor 给了它们一座双向桥——当 classpath 上存在 `micrometer-context-propagation` 库时自动激活：

【源码证据】`reactor/core/publisher/ContextPropagationSupport.java` 第 75-96 行（开关探测）与 `FluxContextWriteRestoringThreadLocals.java` 第 41-47 行（桥接点）：

```java
	public void subscribe(CoreSubscriber<? super T> actual) {
		Context c = doOnContext.apply(actual.currentContext());
		try (ContextSnapshot.Scope ignored = ContextPropagation.setThreadLocals(c)) {
			source.subscribe(new ContextWriteRestoringThreadLocalsSubscriber<>(actual, c));
		}
	}
```

机制三层：

1. **检测**：`ContextPropagationSupport` 探测 classpath 决定 `contextWrite`/`tap` 是否换用"恢复 ThreadLocal"变体（`Flux.java` 第 4391-4398 行的组装分叉：`shouldPropagateContextToThreadLocals()` 为真则用 `FluxContextWriteRestoringThreadLocals`）。
2. **恢复**：其订阅者在**每个信号回调**（onSubscribe/onNext/onError/onComplete/request/cancel）外都包一层 try-with-resources，把 Context 快照设进 ThreadLocal、离开时还原（同文件第 96-175 行）——操作符体内的用户代码（mapper/predicate 回调）于是能在 ThreadLocal 世界里正常生活。
3. **入站适配**：`Operators.restoreContextOnSubscriberIfPublisherNonInternal`（第 1007-1013 行）在 lift 路径上给"非内部 Publisher"包同样的恢复订阅者；队列场景有 `ContextQueue`——offer 时把 ThreadLocal 快照与元素一起装进信封，poll 时若快照与当前线程不符则恢复（`ContextPropagation.java` 第 399-492 行）——**连"生产者 ThreadLocal → 队列 → 消费者 ThreadLocal"的跨线程搬运都照顾到了**。

一个实用的调试开关：`Hooks.DETECT_CONTEXT_LOSS`（`Hooks.java` 第 505/672 行）开启后，`transform`/`transformDeferred` 会用 `ContextTrackingFunctionWrapper` 在 Context 里埋 marker（`"reactor.core.context.marker." + identityHashCode`，`ContextTrackingFunctionWrapper.java` 第 55-75 行），函数应用后发现 marker 丢失即抛 `IllegalStateException("Context loss after applying ...")`——专门抓"用户在 transform 里偷偷换掉了 Context"这类事故。

## 7.5 本章小结

- Context 是**绑在订阅上、自下而上传播**的不可变键值对——方向与数据流相反，与 ThreadLocal"跟线程走"互补。
- 实现是"字段式 Context1~5 → LinkedHashMap 式 ContextN"的写时复制升档链，≤5 对零 Map 开销。
- 传播靠 `CoreSubscriber.currentContext()` 逐层冒泡；写入靠 `contextWrite`（订阅期在 `subscribeOrReturn` 里应用修改）；读取靠 `deferContextual`/`transformDeferredContextual`。
- 3.7 的 ThreadLocal 桥接（依赖 micrometer context-propagation）：**信号回调包 Scope 恢复 ThreadLocal + ContextQueue 跨线程搬运快照**，让 ThreadLocal 生态在响应式管道里继续可用。
- Spring Security 的 `ReactiveSecurityContextHolder`、micrometer 的链路传播，都是这套机制的直接用户——理解了 7.3 的方向，就理解了它们为什么"写在下游、读在上游"。


# 八、热流与 Sinks

> 本章对应源码：`reactor/core/publisher/Sinks.java`、`SinksSpecs.java`、`SinkMany*` 系列、`FluxCreate.java`、`FluxProcessor` 遗留类、`ConnectableFlux` 与 `FluxPublish/FluxRefCount`。注：3.8 中没有 `internal` 子包，Sinks 实现类（`InternalManySink`、`SinkManySerialized`、`SinkManyBestEffort` 等）直接位于 publisher 包内，"internal" 是命名约定而非包结构。

## 8.1 先说白话：冷流是"每客一份"，热流是"公共电台"

第三章的所有数据源都是**冷流**：每个订阅者触发一次独立的订阅，数据从源头为它单独生成（`FluxIterable` 给每个订阅者跑一遍迭代器）。**热流**则相反：源头只有一个，数据"广播"给当下所有订阅者，先来的听到前奏、后来的只能听到当前——订阅时机决定你能听到什么。

两个经典需求冷流做不了：

1. **多消费者共享一条流**（一条 WebSocket 连接的消息分发给 N 个处理器）；
2. **外部世界向流里"灌"数据**（消息队列监听器、回调式 SDK——它们手里没有 Publisher，只有一个"事件到达"的钩子）。

Reactor 给出的答案经历过一次换代：老 API 是 **Processor**（规范四接口之一，既当 Subscriber 又当 Publisher），新 API 是 **Sinks**（3.4 定型，把"发射端"和"订阅端"拆成两个句柄）。今天 Sinks 是唯一推荐。

## 8.2 Processor 的兴衰史：一行 @Deprecated 背后的架构决策

四个幸存的 Processor 已全部废弃（注意基类 Javadoc 至今仍写着 *"Processors will be removed in 3.5"*，而 3.8.8 快照中它们依然在册——废弃未删，过渡期远比预告的漫长）：

| 类 | @Deprecated 位置 | Javadoc 的替代建议（原文要点） |
|---|---|---|
| `FluxProcessor`（基类） | 第 47 行 | *"Prefer using Many instead"*（第 44-46 行）+ issue #2431 |
| `UnicastProcessor` | 第 93 行 | *"prefer clear cut usage of Sinks through variations under Sinks.many().unicast()"*（第 90-91 行） |
| `EmitterProcessor` | 第 69 行 | *"Prefer clear cut usage of Sinks through variations of Sinks.many().multicast().onBackpressureBuffer()"*；需要订阅上游能力则用 `Sinks.unsafe().manyWithUpstream()`（第 58-67 行） |
| `ReplayProcessor` | 第 53 行 | *"prefer clear cut usage of Sinks through variations under Sinks.many().replay()"*（第 50-51 行） |
| `DirectProcessor` | 第 87 行 | *"Closest sink is Sinks.many().multicast().directBestEffort()"*（第 83-85 行） |

**为什么废弃？** 因为 Processor 把"发射端 API（onNext）"直接暴露给用户，而 `onNext` 按规范**必须串行调用**——多线程同时 `onNext` 就是违规。Processor 的对策五花八门（内部加锁/排队/静默丢），语义难背；Sinks 用 `tryEmit` 的**返回值**把"这一发到底成没成功"变成可检查的事实，把策略选择权交还用户（8.3 节）。

更本质的架构事实：**这四个"废弃"类如今只是 Sinks 内核的兼容壳**——它们全都实现了 `InternalManySink`（如 `UnicastProcessor.java` 第 94-96 行、`EmitterProcessor.java` 第 70-71 行），核心逻辑已迁入对应的 `SinkMany*` 实现。读旧类源码时认准这一点，别被"两套实现"迷惑。

各自内核一句话（均经源码实证）：

- **UnicastProcessor**：单订阅者（`volatile CoreSubscriber actual`，第 182 行；`once` 保证只订阅一次，第 186 行）+ 无界 `SpscLinkedArrayQueue`；**没有订阅者时数据全部缓存待重放**（`tryEmitNext` 第 332-343 行）。
- **EmitterProcessor**：多播；队列惰性创建、默认容量 256（第 330-333 行）；drain 时取所有订阅者的**最小 maxRequested**（第 471-483 行）——背压协调到最慢订阅者；`autoCancel=true` 时最后一个订阅者取消即断开上游（`remove()` 第 643-646 行）。它的 Javadoc 还自曝历史伤疤：*"This processor was blocking in onNext"*。
- **ReplayProcessor**：缓存回放委托 `FluxReplay.ReplayBuffer` 策略——`UnboundedReplayBuffer`（无界）或 `SizeBoundReplayBuffer`（定长，第 152-161 行）。
- **DirectProcessor**：订阅者注册表直接复用 `SinkManyBestEffort.DirectInner`（第 106 行）；`onSubscribe` 固定 `request(Long.MAX_VALUE)`（第 127-135 行）——对上游无背压、无订阅者时元素直接丢弃。

## 8.3 Sinks API 全景：tryEmit 的返回值革命

【源码证据】`reactor/core/publisher/Sinks.java` 的入口工厂：`empty()`（第 65 行）、`one()`（第 80 行）、`many()`（第 92 行）——都委托 `SinksSpecs.DEFAULT_SINKS`（默认带序列化保护）；`unsafe()`（第 106 行）返回 `SinksSpecs.UNSAFE_ROOT_SPEC`（裸实现，快但要求调用方自己保证串行）。

**核心方法签名**（`Sinks.Many<T>` 接口，第 758 行起）：

| 方法 | 行号 | 语义 |
|---|---|---|
| `EmitResult tryEmitNext(T)` | 774 | 尝试发射，**永不抛溢出异常**，成败看返回值 |
| `EmitResult tryEmitComplete()` / `tryEmitError(Throwable)` | 786 / 799 | 终态同理 |
| `void emitNext(T, EmitFailureHandler)` | 846 | 高层 API：失败时回调 handler 决定重试/放弃/抛出 |
| `int currentSubscriberCount()` | 953 | 当前订阅者数（发射前的防御性检查） |
| `Flux<T> asFlux()` | 960 | 获取订阅端视图（可多人订阅） |

**`EmitResult` 枚举**（第 118-143 行）六种结局：`OK`、`FAIL_TERMINATED`（已终结）、`FAIL_OVERFLOW`（下游背压拒收）、`FAIL_CANCELLED`（已取消）、`FAIL_NON_SERIALIZED`（并发发射违规）、`FAIL_ZERO_SUBSCRIBER`（没人听）；配套 `isSuccess()`（151 行）与 `orThrow()`（172 行）。

**`EmitFailureHandler`**（第 256-292 行）两种预置策略：`FAIL_FAST`（第 262 行，失败立即返回 false 不重试）与 `busyLooping(Duration)`（第 278 行）——后者对 `FAIL_NON_SERIALIZED` 自旋重试直到 deadline（实现类 `OptimisticEmitFailureHandler`，第 229-242 行）：

```java
	public boolean onEmitFailure(SignalType signalType, EmitResult emitResult) {
		return emitResult.equals(Sinks.EmitResult.FAIL_NON_SERIALIZED)
				&& deadline - System.nanoTime() > 0;
	}
```

**失败后的默认分派**在 `InternalManySink.emitNext`（`reactor/core/publisher/InternalManySink.java` 第 22-61 行）：`FAIL_ZERO_SUBSCRIBER` 静默忽略；`FAIL_OVERFLOW` → `Operators.onDiscard` 后以 `failWithOverflow` 出错；`FAIL_CANCELLED` → 丢弃；`FAIL_TERMINATED` → `onNextDropped`；`FAIL_NON_SERIALIZED` → 抛 `EmissionException`，消息直接引用规范 *"Spec. Rule 1.3 ... MUST be signaled serially"*——规范条文第一次以异常消息的形式出现在你的日志里。

**预设矩阵**（`SinksSpecs`，第 34-35 行两套根规格）：

| 预设 | 背压语义 | 行号 |
|---|---|---|
| `many().unicast().onBackpressureBuffer()` | 单订阅者，无界缓冲 | 441 |
| `many().multicast().directAllOrNothing()` | **任一**订阅者跟不上 → 整体 `FAIL_OVERFLOW`（全有或全无） | 579 |
| `many().multicast().directBestEffort()` | 只要**有**订阅者收得下就发，慢订阅者被跳过（尽力而为） | 600 |
| `many().replay().all()/latest()/limit(n)/limit(Duration)` | 缓存回放：全部/最新/限量/限时 | 617-747 |

`directAllOrNothing` 与 `directBestEffort` 的 Javadoc 差异（第 565-589 行）值得原文品读：前者 *"notify the caller with FAIL_OVERFLOW if any of the subscribers cannot process an element, failing fast and backing off from emitting the element at all"*，后者 *"if none of the subscribers can process an element … ignores slow subscribers and emits the element to fast ones as a best effort"*。

## 8.4 线程安全机制：不是锁，是"检测 + 快速失败"

`Sinks.many()` 默认包一层 `SinkManySerialized`（`SinksSpecs.wrapMany`，第 211-213 行）。它的并发对策极具特色——**不排队、不阻塞，靠一个 wip 计数 + 持有者线程号检测竞争**：

【源码证据】`reactor/core/publisher/SinksSpecs.java` 第 39-64 行（`AbstractSerializedSink.tryAcquire`）：

```java
	volatile int wip;
	volatile @Nullable Thread lockedAt;

	boolean tryAcquire(Thread currentThread) {
		if (WIP.get(this) == 0 && WIP.compareAndSet(this, 0, 1)) {
			LOCKED_AT.lazySet(this, currentThread);       // 抢到锁，记录持有者
		}
		else {
			if (LOCKED_AT.get(this) != currentThread) {
				return false;                              // 别的线程在用：快速失败
			}
			WIP.incrementAndGet(this);                     // 同线程重入：计数放行
		}
		return true;
	}
```

`SinkManySerialized.tryEmitNext`（第 91-106 行）抢锁失败直接返回 `FAIL_NON_SERIALIZED`——由上层 `emitNext` 的 `busyLooping` 决定自旋重试。释放时 `WIP.decrementAndGet == 0` 才清空 `lockedAt`。对比 8.6 节 `FluxCreate` 的 SerializedSink（WIP 自旋 + MPSC 队列排队），可以清楚看到两代 API 对"并发发射"的不同哲学：**Sinks 选择"把冲突暴露为返回值"，FluxSink 选择"把冲突消化在内部队列里"**。

多播发射本体在 `SinkManyBestEffort`：allOrNothing 模式先扫描所有 `DirectInner` 取最小 `requested`，为 0 则整体 `FAIL_OVERFLOW`（第 106-119 行）；best-effort 模式逐个 `sub.tryEmitNext(t)`，任一成功即 `OK`（第 132-147 行）。`DirectInner.tryEmitNext`（第 356-369 行）是纯 push：`requested != 0` 就 `actual.onNext(value)`——**热流的"队列"就是订阅者自己的 request 配额**。

## 8.5 FluxCreate：create/push 与五种溢出策略

`Flux.create(emitter)` 给回调一个 `FluxSink`，是"外部世界灌数据"的经典入口。按是否需要多线程发射分两档：`create`（PUSH_PULL，回调拿到**序列化包装**的 sink，`FluxCreate.subscribe` 第 98 行）与 `push`（PUSH_ONLY，裸 sink，单线程约定）。

**BaseSink 的请求量状态机**：不是 LongAdder，而是单个 `volatile long requested` + `AtomicLongFieldUpdater`（第 431-434 行），并**把最高位 `Long.MIN_VALUE` 挪用为"onRequest 消费者已注册"标志位**（构造器 447 行 `REQUESTED.lazySet(this, Long.MIN_VALUE)`；判读见第 688-690 行）——一个 long 字段当两半用，是 Reactor 位域技巧的又一例。`request()` 走 `addCap` 饱和累加并保留标志位（第 531-545、657-670 行）。

**五种 OverflowStrategy**（枚举在 `FluxSink.java` 第 164-189 行；分派工厂 `FluxCreate.createSink` 第 68-87 行）：

| 策略 | Sink 内部类 | 行号 | 溢出行为 |
|---|---|---|---|
| `BUFFER`（**默认**） | `BufferAsyncSink` | 791-948 | `Queues.unbounded(SMALL_BUFFER_SIZE)` 无界队列 + drain 循环按下游 request 排水 |
| `ERROR` | `ErrorAsyncSink` | 772-789 | 满了 `error(Exceptions.failWithOverflow())` 终止 |
| `DROP` | `DropAsyncSink` | 754-770 | 满了丢弃新元素（与 ERROR 共享基类 `NoOverflowBaseAsyncSink`，727-752 行） |
| `LATEST` | `LatestAsyncSink` | 950-1110 | 容量为 1 的 `AtomicReference`，新值覆盖旧值并 discard 旧值 |
| `IGNORE` | `IgnoreSink` | 693-725 | 无视 request 直接推（完全下游自治，规范违规风险自负） |

**SerializedFluxSink 是唯一"真排队"的序列化实现**（第 118-321 行）：fast-path CAS `wip 0→1` 直接发射；竞争线程把元素 offer 进 `Queues.unboundedMultiproducer()` 的 `MpscLinkedQueue`（第 141 行）后尝试接管 drainLoop（第 173-179 行）。另有懒序列化变体 `SerializeOnRequestSink`（第 329-410 行）：只有用户注册了 `onRequest` 回调才升级为序列化包装。

## 8.6 ConnectableFlux：publish / share / refCount / autoConnect

**publish()——把冷流变成可广播的电台**。`Flux.publish()`（`Flux.java` 第 7562-7564 行）= `publish(SMALL_BUFFER_SIZE)`，返回 `ConnectableFlux`：订阅它只是"登记"（进 `FluxPublish.PublishSubscriber.subscribers` 数组），调用 `connect()` 才真正订阅上游开始广播。

`FluxPublish` 的内核（`FluxPublish.java`）：`PublishSubscriber`（第 167-219 行）持**单条共享队列** + `PubSubInner[]` 订阅者数组 + 一个 long 位标志状态字（INIT/CANCELLED/TERMINATED 数组哨兵，第 186-198 行）；上游信号进共享队列，再按各 inner 的 request 配额分发（`PublishInner` 请求到来时反向驱动 `drainFromInner`，第 916-933 行）。融合开启时（SYNC）甚至直接拿上游当队列用（第 226-275 行）。

**share()——一键热流（3.7 新增）**：

【源码证据】`Flux.java` 第 8341-8357 行：

```java
	public final Flux<T> share() {
		return onAssembly(
				new FluxRefCount<>(
						new FluxPublish<>(this, Queues.SMALL_BUFFER_SIZE, Queues.small(), true), 1)
		);
	}
```

Javadoc 自述 *"This is an alias for publish().refCount()"*；第 4 参 `resetUponSourceTermination=true`——源终止后下次订阅重建连接。这是 3.7 为"多订阅者共享上游订阅"这个高频需求补的官方快捷方式。

**连接生命周期的三种管理器**：

- `autoConnect(n)`（`ConnectableFlux.java` 第 53-98 行 → `FluxAutoConnect.java` 第 30-57 行）：第 n 个订阅者出现时自动 connect；**取消不回退计数**（"订阅即计入"）。
- `refCount(n)`（`ConnectableFlux.java` 第 154 行 → `FluxRefCount.java` 第 40 行）：订阅计数归零时断开上游；计数管理在 `synchronized(this)` 内（`subscribe` 第 62-92 行、`cancel` 第 94-113 行）。
- `refCount(n, Duration)`（第 174-175 行 → `FluxRefCountGrace`）：断开有"宽限期"，宽限期内有新订阅者则取消断连——避免"订阅闪断"场景反复重建上游连接。

一个易错点：`DrainUtils`（第 29 行起，`postCompleteRequest/postComplete` 家族，请求量占低 62 位、最高位是完成标志）**服务于 FluxBuffer 等单下游操作符的"完成后补请求"**，`FluxPublish` 的多播 drain 用的是自己的 state 位标志——两者名字相近、机制不同，读源码别混淆。

## 8.7 本章小结

- 冷流"每客一份"，热流"公共电台"；热流的两个真实需求：**多消费者共享**与**外部灌数据**。
- Processor 已全面废弃（但实现已迁入 Sinks 内核，旧类只是兼容壳）；其历史罪状是把规范要求串行的 `onNext` 直接暴露给用户。
- Sinks 的 API 革命：**`tryEmit` 返回 `EmitResult` 六态**，成败可查；`emitNext` + `EmitFailureHandler`（`FAIL_FAST`/`busyLooping`）提供高层语义。
- 预设矩阵按"订阅者形状 × 背压语义"展开：unicast（缓存待客）/ multicast（allOrNothing 严格、bestEffort 尽力）/ replay（all/latest/limit）。
- 两种序列化哲学：**Sinks 检测竞争返回失败值**（wip + lockedAt），**FluxSink 内部排队消化竞争**（wip + MPSC 队列）。
- `create` 默认 BUFFER 策略（无界队列 + SMALL_BUFFER_SIZE 初始容量）；`BaseSink` 用 long 最高位做标志位是位域技巧的典范。
- `share()` = `publish().refCount()`（3.7 语法糖）；连接生命周期三管理器：autoConnect（订阅即计入）、refCount（归零断开）、refCountGrace（断开有宽限）。


# 九、观测、调试与测试

> 本章对应源码：`reactor/core/observability/`（3 个文件）、`reactor-core-micrometer` 模块（10 个文件）、`FluxOnAssembly`、`reactor-tools` 模块（5 个文件）、`reactor-test` 模块（23 个文件）。

## 9.1 观测体系：tap() 与 SignalListener

`reactor.core.observability` 包只有 3 个文件，却是 3.4 起观测体系的接口核心：**`SignalListener`**（监听器：每个信号一个回调钩子）、**`SignalListenerFactory`**（工厂：装配期一次 `initializePublisherState` + 每次订阅一次 `createListener`，`SignalListenerFactory.java` 第 38-63 行）、**`DefaultSignalListener`**（全 no-op 基类）。

`SignalListener` 的回调面覆盖全部生命周期（`SignalListener.java`）：`doFirst`（第 54 行，订阅动作发出前）→ `doOnSubscription`（77 行）→ `doOnFusion/doOnRequest`（87/96 行）→ 每个信号的 `doOnNext/doOnCancel/doOnComplete/doOnError`（106-138 行）→ 终态后的 `doAfterComplete/doAfterError`（146/156 行）→ 规范违规观测 `doOnMalformedOnNext/onError/onComplete`（168-188 行）→ `doFinally(SignalType)`（65 行，只收 `ON_COMPLETE/ON_ERROR/CANCEL`，58-59 行）。对比 `doOnXxx` 操作符族：**tap 的监听器是"全信号、含违规信号"的一次性观测点，且 `SignalType` 枚举（`SignalType.java` 第 23-64 行）给了它统一的信号 vocabulary**。

接入点只有一个操作符——`tap()`（`Flux.java` 第 9396/9433/9471 行三个重载，从 Supplier 到 SignalListenerFactory 逐级增强）。`FluxTap.subscribeOrReturn`（`FluxTap.java` 第 46-91 行）的流程：创建 listener →（context-propagation 可用时包一层 ThreadLocal 恢复，见 7.4 节）→ 调 `doFirst` → 用 `addToContext` 把 listener 挂进 Context（第 74-84 行，**上游因此能看到下游挂了什么监听器**）→ 返回 `TapSubscriber`。`TapSubscriber` 在每个真实信号前后织入对应回调（`onError` 的顺序是 `doOnError` → 传播 → `doAfterError` + `doFinally(ON_ERROR)`，第 230-263 行）；listener 自身抛错有四个分级 helper 兜底，绝不让观测代码弄死业务流（第 145-187 行）。publisher 包里 tap 家族共六个类（Flux/Mono × 普通/Fuseable/RestoringThreadLocals）。

## 9.2 reactor-core-micrometer：指标与 Observation

micrometer 对接有两个入口，都要求**显式传入注册表**（没有全局默认）：

- `Micrometer.metrics(MeterRegistry)`（`Micrometer.java` 第 62-64 行）→ 纯指标，挂在 `tap()` 上用；
- `Micrometer.observation(ObservationRegistry)`（第 94-112 行）→ micrometer Observation 全景（指标 + 链路 span），同挂 `tap()`。

`MicrometerMeterListener` 记录的指标族（前缀默认 `"reactor"`，第 39 行；名称模板见 `MicrometerMeterListenerDocumentation`）：

| 指标 | 类型 | 语义 | 位置 |
|---|---|---|---|
| `{name}.subscribed` | Counter | 订阅次数 | 第 276-281 行 |
| `{name}.flow.duration` | DistributionSummary | 一条流从订阅到终态的时长；tag `status`=completed/completedEmpty/error/cancelled，error 另附 `exception`=异常类名 | tags 第 183-188 行、record 第 198-268 行 |
| `{name}.onNext.delay` | Timer | 相邻两个 onNext 的间隔（吞吐视角） | 第 62-64、116-127 行 |
| `{name}.requested` | DistributionSummary | 下游 request 量分布（仅当显式 `name()` 过） | 第 67-73、142-146 行 |
| `{name}.malformed.source` | Counter | 规范违规信号计数 | 第 214-217 行 |

公共 tag 只有 `type=Flux|Mono`（第 183-184 行）；Mono 特殊处理：无 onNext 间隔 Timer，有值完成直接在 onNext 里记 flow.duration（第 54-60、116-122 行）。

`MicrometerObservationListener` 是链路视角：一个 `Observation` 覆盖"订阅→终止"全程，CAS 状态机管理四种终态（第 67-76 行）。它的关键设计在 `doFirst`：从当前 Context 或 `registry.getCurrentObservation()` 找到 parentObservation 后启动，并**把 Observation 写进 Context 暴露给上游**（第 115-170 行）——于是 `flatMap` 扇出的内层 `tap` 能继承父 Observation，链路树自然成形。observation 名取上游 `name()`，默认 `"reactor.observation"`（`MicrometerObservationListenerDocumentation.java` 第 33-36 行）。

（顺带澄清 1.6.2 节的伏笔：模块里的 `Micrometer.timedScheduler(...)`（第 126-146 行）返回的 `TimedScheduler` 是**本模块的指标装饰类**，把任意 Scheduler 包上任务耗时指标——与 3.8 移除的 `reactor.core.scheduler.TimedScheduler` 接口重名不同物。）

## 9.3 调试三件套：checkpoint / onOperatorDebug / Debug Agent

响应式调试的第一性难题：**异常的调用栈停在"信号被投递的线程"，而不是"组装流水线的地方"**——栈里没有任何你的业务代码。Reactor 的三件套按"代价从低到高、精度从低到高"排列：

**① checkpoint()——路标**（`Flux.java` 第 3625-3695 行）。两种模式：`checkpoint(String)`（轻量，只存描述字符串，`CheckpointLightSnapshot`，零堆栈成本）与 `checkpoint()`（重量，现场抓一次调用栈，`CheckpointHeavySnapshot` 携带 `Traces.callSiteSupplierFactory.get()`）。报错时错误对象被包上 `OnAssemblyException`，其 `getMessage()` 从叶子到根拼出 *"Error has been observed at the following site(s):"* 树形消息（`FluxOnAssembly.java` 第 444-475 行）。

**② Hooks.onOperatorDebug()——全局路标**。把 `Hooks.GLOBAL_TRACE` 置 true（`Hooks.java` 第 336-339 行），生效点是**每个操作符工厂都调用的 `onAssembly`**（`Flux.java` 第 10952-10962 行）：

```java
if (Hooks.GLOBAL_TRACE) {
    AssemblySnapshot stacktrace = new AssemblySnapshot(null, Traces.callSiteSupplierFactory.get());
    source = (Flux<T>) Hooks.addAssemblyInfo(source, stacktrace);
}
```

每个操作符组装时都抓栈——这就是它昂贵的道理（组装是热路径）。`OnAssemblySubscriber.fail` 的细节值得称道：把原异常栈里 Reactor 内部帧剔除、完整栈移交给 `OnAssemblyException`（`fillInStackTrace` 返回 this 不重复填栈，`FluxOnAssembly.java` 第 343-346 行），节点以 `identityHashCode` 建树、去重计数（第 348-420、566-616 行）。

**③ reactor-tools Debug Agent——字节码织入的现代答案**。`ReactorDebugAgent`（reactor-tools 模块）以 `premain` 或 `ByteBuddyAgent.install()` 启动（`ReactorDebugAgent.java` 第 43-59 行），注册可 retransform 的 `ClassFileTransformer`（跳过 JDK 前缀与 reactor 自身，第 71-84 行），对**所有返回类型为 Flux/Mono/ParallelFlux 的方法**做两件事（`ReactorDebugClassVisitor.java` 第 52-72 行）：

1. `CallSiteInfoAddingMethodVisitor`：每次方法调用指令后插入 `Hooks.addCallSiteInfo(publisher, "owner.method -> class.method(File.java:line)")`（`CallSiteInfoAddingMethodVisitor.java` 第 105-128 行）——**组装点信息零抓栈成本地写入**；
2. `ReturnHandlingMethodVisitor`：方法返回处对未 checkpoint 的 publisher 注入 `Hooks.addReturnInfo`。

效果：调试堆栈自带完整"装配蓝图"，配合 IDE 断点即用，性能远优于全局抓栈——这是 3.7+ 文档推荐的默认姿势。已加载类还能 `retransformClasses` 热补（第 122-167 行）。

## 9.4 reactor-test：StepVerifier 与虚拟时间

**StepVerifier 的本质是"六信号的脚本回放器"**。`DefaultStepVerifierBuilder` 自己 `implements FirstStep<T>`（没有分层的 FirstStep/Step 内部类，`DefaultStepVerifierBuilder.java` 第 79-80 行）：链式调用期把每个 `expectNext/expectError/thenRequest/thenAwait` 追加成一个 `Event` 进 `script` 列表（第 468-509、358-435 行），`verify()` 时 `conflateScript` 转成**两个 ConcurrentLinkedQueue**——期望队列 + 任务事件队列（第 966-1102 行）。

验证主循环（`DefaultVerifySubscriber`）：

1. `verify()` → `toVerifierAndSubscribe()`（第 835-906 行）→ `publisher.subscribe(newVerifier)`；
2. `onSubscribe`：CAS 记录 subscription、先走一遍"订阅信号"的期望匹配，然后**执行 request 计划**（`subscription.request(initialRequest)`，第 1174-1199 行）——你写的 `thenRequest(n)` 就排在这里；
3. 每个真实信号到达 → `onExpectation`：peek/poll 期望队头，按 `SignalSequenceEvent/SignalCountEvent/SignalEvent/CollectEvent` 等分发匹配（第 1452-1544 行）；`thenCancel/thenRequest` 类 SubscriptionEvent 经串行 drain 执行（第 1508-1526 行）；
4. `verify(Duration)`：等完成闭锁 + 跑完任务事件 + `validate()` 汇总（第 1315-1334 行）。

**VirtualTimeScheduler——把"时间"变成可拨的钟**。它不是普通 Scheduler 替身，而是**整个 `Schedulers` 工厂的替身**：

【源码证据】`reactor-test/src/main/java/reactor/test/scheduler/VirtualTimeScheduler.java` 第 186-194 行：

```java
if (CURRENT.compareAndSet(s, newS)) {
    if (s != null) {
        newS.schedulersSnapshot = s.schedulersSnapshot;
        Schedulers.setFactory(new AllFactory(newS));
    }
    else {
        newS.schedulersSnapshot = Schedulers.setFactoryWithSnapshot(new AllFactory(newS));
    }
```

`AllFactory` 让 `parallel()/single()/boundedElastic()` 全部产出虚拟时钟调度器；`reset()` 用快照恢复（第 233-240 行）。时间模型：任务挂 `PriorityBlockingQueue<TimedRunnable>`（第 242-243 行），`advanceTimeBy/To` 归一为 `advanceTime(nanos)` 后 `drain()`——循环弹出"到期"任务、执行前把时钟拨到该任务的时间点（保证任务内 `now()` 正确，第 382-419 行）。于是 `Mono.delay(1h)` + `retryWhen(backoff)` 的测试在毫秒内跑完。

**TestPublisher 与 PublisherProbe**：`TestPublisher`（`TestPublisher.java` 第 48 行）是可编程 Publisher——`next/error/complete` 手动发信号，`assertSubscribers/assertMinRequested/assertCancelled` 断言订阅侧状态；四种 `Violation`（`REQUEST_OVERFLOW/ALLOW_NULL/CLEANUP_ON_TERMINATE/DEFER_CANCELLATION`，第 275-297 行）让它能"故意违规"以测试下游的防御力。冷流版 `createCold()` 缓冲重放、严格遵守背压（第 85-121 行）。`PublisherProbe` 是断言视图（`wasSubscribed/wasCancelled/wasRequested`，`PublisherProbe.java` 第 53-148 行），实现只是 `AtomicLongArray` 的三个计数槽（`DefaultPublisherProbe`，第 175-189 行）。另有两个趁手工具：`TestSubscriber`（StepVerifier 验证订阅者的独立泛用版，记录取消后信号与协议违规，`DefaultTestSubscriber.java` 第 85-88 行）与 `RaceTestUtils.race(...)`（把多个任务在同一调度器上对齐起跑的竞态测试器，`RaceTestUtils.java` 第 38 行）。

## 9.5 本章小结

- 观测的官方姿势是 **`tap(SignalListener)`**：全信号回调面（含规范违规）、`addToContext` 让上游可见、listener 自身错误分级兜底不伤业务流。
- micrometer 模块两个入口：`metrics()`（五个指标族）与 `observation()`（Observation 全景，经 Context 继承父 span 扇出）；注册表必须显式传入。
- 调试三件套按代价排序：**checkpoint(String)（路标）→ checkpoint()/onOperatorDebug()（抓栈）→ Debug Agent（字节码织入 callsite，零抓栈成本）**；`OnAssemblyException` 把离散的组装点重建成一棵"错误观察树"。
- StepVerifier = **期望队列 + 任务事件队列**的脚本回放器，request 计划在 onSubscribe 时建立；VirtualTimeScheduler 替换整个 `Schedulers` 工厂，drain 时"拨钟到任务时刻"。
- TestPublisher 的四种 Violation 是测试"下游防御力"的独特武器；RaceTestUtils 是操作符竞态测试的标配。


# 十、贯通视图：三条时间线看懂 Reactor 全貌

前面九章按模块拆解，本章把它们重新拼回一条流水线。以一段生产风格的代码为标本：

```java
Flux.fromIterable(orders)                          // 源
    .filter(o -> o.amount() > 0)                   // 条件化
    .flatMap(o -> charge(o), 16, 8)                // 异步并发
    .publishOn(Schedulers.boundedElastic())        // 线程切换
    .retryWhen(Retry.backoff(3, Duration.ofMillis(100)))
    .subscribe(System.out::println);               // 终点
```

## 10.1 时间线一：组装期（纳秒级，纯对象图）

`subscribe()` 之前的每一次链式调用都是一次 `new`：

```
FluxIterable(orders)
  ← FluxFilter(filter)
    ← FluxFlatMap(charge, maxConcurrency=16, prefetch=8)
      ← FluxPublishOn(boundedElastic)
        ← FluxRetryWhen(RetryBackoffSpec)
          ← [subscribe 调用点]
```

每一步发生的全部事情：**构造器存下 (source, 参数) + 参数校验 + `onAssembly` 交给 `Hooks.onEachOperator`（若注册了调试钩子则在这里包一层 `FluxOnAssembly` 快照）**。没有线程、没有队列、没有 IO。这段开销与流的"长度"（即将处理多少元素）无关，只与链的"宽度"（操作符个数）有关——这是响应式 API 敢于在热路径上反复组装的底气。

## 10.2 时间线二：订阅期（一次性握手，自下游向上游）

`subscribe(println)` 触发的握手过程（对照 3.5 节逐行级分析）：

1. `Flux.subscribe(Subscriber)`：lambda 包装成 `LambdaSubscriber`；`Operators.onLastAssembly` 应用 `onLastOperator` 钩子；进入 `OptimizableOperator` 循环。
2. 循环自外向内"穿马甲"：`FluxRetryWhen` 换成 `RetryWhenMainSubscriber` → `FluxPublishOn` 换成 `PublishOnSubscriber`（同时在 boundedElastic 上 `createWorker()`）→ `FluxFlatMap` 换成 `FlatMapMain` → `FluxFilter` 换成 `FilterSubscriber`。**全程无递归**。
3. 抵达源头 `FluxIterable`：创建 `IterableSubscription`，`onSubscribe` 握手沿原路返回，每层换装一次。
4. `LambdaSubscriber.onSubscribe` 收到 `FilterSubscriber`（它同时是 Subscription）→ `request(Long.MAX_VALUE)`。
5. request 自下游向上游回流，每层按自己的策略"翻译"：`FilterSubscriber` 原样转发 → `FlatMapMain` 换成 `unboundedOrPrefetch(16)` → `IterableSubscription` 开始 `slowPath(16)` 拉取前 16 个订单。

注意这一步里 `FluxRetryWhen` 的特殊动作：它还顺手**订阅了 companion**（`RetryBackoffSpec.generateCompanion` 生成的退避流）——一条流在诞生时就拖着一条"影子流"。

## 10.3 时间线三：数据信号期（长期运行，自上游向下游）

数据期的完整旅程（含线程切换与重试）：

```
IterableSubscription            [调用者线程]
   │ onNext(order) ×8 →（攒到 limit 补 request）
FlatMapMain                     [调用者线程]
   │ 对每个 order: charge(order).subscribe(FlatMapInner)
   │   → FlatMapInner.request(8) 预取；结果进 inner 队列
   │ inner 排空 → 移除 → replenishMain → s.request(replenishMain)
PublishOnSubscriber             [调用者线程收货]
   │ worker.schedule(this) → 信号转投
   │                            [boundedElastic 线程]
   │ onNext(chargeResult) ×n →（75% 水位时 s.request(e) 补货）
RetryWhenMainSubscriber         [boundedElastic 线程]
   │ onNext 透传 → LambdaSubscriber → println
   │ onError 时：signaller.emitNext(RetrySignal) → companion → Mono.delay
   │           → resubscribe() → 从第 2 步重新走一遍
```

三条时间线各自的关键问题与答案：

| 时间线 | 谁在跑 | 何时发生 | 关键源码 |
|---|---|---|---|
| 组装 | 调用者线程 | 编写代码时/每次重建链 | 各操作符构造器 + `onAssembly` |
| 订阅 | `subscribe()` 发起线程（除非 `subscribeOn`） | 一次，毫秒级 | `Flux.subscribe(Subscriber)` + `subscribeOrReturn` 链 |
| 数据 | 每个信号所在线程（`publishOn` 重置） | 与数据量成正比，长期 | `onNext` 转发链 + drain 循环 + request 回流 |

调试心法：**组装期的问题（NPE、参数错）在栈顶；订阅期的问题（订阅了没反应）查握手链；数据期的问题（丢数据/不消费）查 request 配额与队列水位**。

## 10.4 从源码中提炼的五个设计模式视角

1. **装饰器模式（组合优于继承）**：全部操作符都是 `new XxxFlux(source, params)` 的洋葱结构；"给下游穿马甲"（`subscribeOrReturn` 返回包装后的订阅者）是它的订阅期对应物。
2. **模板方法**：`InternalFluxOperator.subscribe()` 的循环下潜是固定骨架，操作符只填 `subscribeOrReturn/nextOptimizableSource`；`BaseSubscriber` 的 `hookOnXxx`、`Retry.generateCompanion` 同理。
3. **策略模式**：`Scheduler`（线程策略）、`OverflowStrategy`（溢出策略）、`OnNextFailureStrategy`（失败续行策略）、`EmitFailureHandler`（发射失败策略）、`ReplayBuffer`（回放策略）——策略选择的入口全部收拢在一个静态工厂。
4. **状态机 + 无锁并发**：`SchedulerTask` 的 PARENT/FUTURE 双原子、`IterableSubscription` 的 state 四态、`FluxSubscribeOn` 的 REQUESTED 暂存、`FluxOnErrorReturn` 的负数状态复用 request 字段——Reactor 几乎不用 `synchronized`（例外见 `FluxOnBackpressureBufferStrategy`/`FluxRefCount`），用 CAS 与位域把并发压进单字段。
5. **规范驱动的接口隔离**：对外只暴露 `Publisher/Subscriber`（规范），对内走 `CorePublisher/CoreSubscriber`（扩展）；"海关"（`FluxHide.SuppressFuseableSubscriber`、`Hooks.wrapQueue`、`Operators.toCoreSubscriber`）把两套世界隔开。

---


# 十一、附录

## 11.1 关键接口速查表

| 接口/类 | 所在文件（reactor-core/src/main/java 下） | 一句话职责 |
|---|---|---|
| `Publisher` | org.reactivestreams | 规范：可被订阅，唯一方法 `subscribe(Subscriber)` |
| `Subscriber` | org.reactivestreams | 规范：onSubscribe/onNext/onError/onComplete |
| `Subscription` | org.reactivestreams | 规范：request(n) 与 cancel |
| `Processor` | org.reactivestreams | 规范：既是 Subscriber 又是 Publisher（Reactor 中已弃用为直接编程接口） |
| `CorePublisher` | reactor/core/CorePublisher.java | 内部扩展：`subscribe(CoreSubscriber)`，绕过钩子直传 Context |
| `CoreSubscriber` | reactor/core/CoreSubscriber.java | 内部扩展：`currentContext()`；放宽 §1.3/§3.9 |
| `OptimizableOperator` | reactor/core/publisher/OptimizableOperator.java | 操作符订阅优化的契约：`subscribeOrReturn` + `source` + `nextOptimizableSource` |
| `InnerOperator` / `InnerProducer` | reactor/core/publisher/ | 内部订阅者/内部 Subscription 的公共基接口（持有 `actual`） |
| `Fuseable.QueueSubscription` | reactor/core/Fuseable.java:149 | 订阅 + 队列双重身份（融合协商 `requestFusion`） |
| `Fuseable.ConditionalSubscriber` | reactor/core/Fuseable.java:121 | "可拒绝元素"的订阅者（`tryOnNext`） |
| `Fuseable.ScalarCallable` | reactor/core/Fuseable.java:269 | 标量源标记：值已知可同步取出 |
| `Scannable` | reactor/core/Scannable.java | 运行期 introspection：调试/指标沿订阅树向上探 |
| `Disposable` | reactor/core/Disposable.java | 取消句柄（`dispose/isDisposed`），订阅返回值 |
| `Scheduler` / `Worker` | reactor/core/scheduler/Scheduler.java:39/197 | 异步边界 / 执行单元 |
| `Context` / `ContextView` | reactor/util/context/ | 订阅树上的不可变键值上下文（读/写视图分离） |
| `Retry` | reactor/util/retry/Retry.java | 重试策略规范：`generateCompanion(Flux<RetrySignal>)` |
| `Sinks.Many` / `Sinks.One` | reactor/core/publisher/Sinks.java:758/1151 | 发射端句柄：tryEmit* 返回 EmitResult |
| `FluxSink` | reactor/core/publisher/FluxSink.java | `create/push` 回调句柄（OverflowStrategy 五选一） |
| `SignalListener` | reactor/core/observability/SignalListener.java | 观测监听器：`tap()` 挂到每个信号上 |
| `StepVerifier` | reactor-test/…/StepVerifier.java | 验证器：声明式断言六信号时序 |

## 11.2 初学者学习路线（动手向）

建议在 `D:\code\workspace` 下建一个最小 Maven/Gradle 工程（依赖 `io.projectreactor:reactor-core` + `reactor-test`，JDK 17+），按顺序做七个练习：

1. **看见信号**：`Flux.range(1, 5).map(x -> x * 2)` 链上每一环插 `doOnNext/doOnSubscribe/doFinally`，打印"信号 + 当前线程名"。目标是亲眼看到 3.3 节"两个方向"。
2. **看见背压**：`Flux.interval(Duration.ofMillis(10))` 接 `subscribe(v -> { sleep(100); println(v); })`，观察 `request` 默认无界时的堆积；再换 `BaseSubscriber` 手动 `request(1)`，体会"拉模式"。
3. **看见线程切换**：`publishOn(Schedulers.parallel())` 与 `subscribeOn(Schedulers.boundedElastic())` 各放链上一次，打印线程名，验证 5.7 节"publishOn 就近生效、subscribeOn 最靠源者赢"。
4. **写一个 Publisher**：手写 `class MyPublisher implements Publisher<Integer>`（含 Subscription 的 request/cancel），跑通 StepVerifier——这是理解规范最快的路。
5. **读一个操作符**：对照 3.4 节通读 `FluxMap`，再自己给 `FluxMap` 的 mapper 抛异常场景写 StepVerifier 断言（`expectError`），然后加 `onErrorContinue` 观察行为变化（6.2 节）。
6. **虚拟时间**：`StepVerifier.withVirtualTime(() -> flux.retryWhen(Retry.backoff(3, ...)))` + `thenAwait(Duration)`，验证 6.4 节的退避序列而不真等 100ms/200ms/400ms。
7. **阻塞取证**：在 `Schedulers.parallel()` 的 `publishOn` 之后调用 `Thread.sleep`，挂上 BlockHound 观察异常——建立"NonBlocking 纪律"的肌肉记忆（5.8 节）。

配套阅读顺序（源码）：`FluxMap` → `FluxIterable` → `Flux.subscribe(Subscriber)` → `FluxFlatMap` → `FluxPublishOn` → `Sinks`/`SinkManyBestEffort` → `RetryBackoffSpec`。

## 11.3 源码阅读入口清单（30 个关键文件）

以下路径均相对 `reactor-core/src/main/java/`（reactor-test 模块单独注明），按推荐阅读顺序排列：

**地基（6）**

1. `reactor/core/CoreSubscriber.java`——Context 感知订阅者，全文第一个该读的接口
2. `reactor/core/CorePublisher.java`——内部订阅入口
3. `reactor/core/Fuseable.java`——融合三件套：ConditionalSubscriber/QueueSubscription/ScalarCallable
4. `reactor/core/Exceptions.java`——异常工具与组合异常
5. `reactor/util/context/Context.java`——不可变上下文接口
6. `reactor/util/concurrent/Queues.java`——256/32 魔数与队列工厂

**订阅骨架（6）**

7. `reactor/core/publisher/Flux.java`——门面（11234 行，先只读 `subscribe(Subscriber)` 第 8861-8894 行）
8. `reactor/core/publisher/Mono.java`——门面（对照读）
9. `reactor/core/publisher/OptimizableOperator.java`——循环下潜契约（61 行，全文最短的必读文件）
10. `reactor/core/publisher/InternalFluxOperator.java`——循环下潜的公共实现
11. `reactor/core/publisher/LambdaSubscriber.java`——lambda 三件套的落地
12. `reactor/core/publisher/BaseSubscriber.java`——用户级钩子订阅者

**操作符模板（4）**

13. `reactor/core/publisher/FluxMap.java`——操作符标准模板（303 行，必读）
14. `reactor/core/publisher/FluxIterable.java`——同步冷流（拉模型套推模型皮）
15. `reactor/core/publisher/Operators.java`——静态工具箱（onOperatorError/onNextError/validate/unboundedOrLimit…）
16. `reactor/core/publisher/Hooks.java`——全局钩子表

**异步与背压（5）**

17. `reactor/core/publisher/FluxFlatMap.java`——并发合并的完整范本（drainLoop/FlatMapTracker）
18. `reactor/core/publisher/FluxConcatMap.java`——与 flatMap 只差一个 active 布尔
19. `reactor/core/publisher/FluxPublishOn.java`——信号接力站
20. `reactor/core/publisher/FluxSubscribeOn.java`——订阅搬运工 + request 暂存
21. `reactor/core/publisher/FluxOnBackpressureBuffer.java`——截流决策者代表

**热流与 Sinks（4）**

22. `reactor/core/publisher/Sinks.java`——API 全景（EmitResult/EmitFailureHandler）
23. `reactor/core/publisher/SinksSpecs.java`——预设矩阵 + AbstractSerializedSink 的 wip/lockedAt
24. `reactor/core/publisher/SinkManyBestEffort.java`——多播发射本体（DirectInner）
25. `reactor/core/publisher/FluxPublish.java` + `FluxRefCount.java`——publish/share 的内核与连接生命周期

**调度器（4）**

26. `reactor/core/scheduler/Scheduler.java`——接口 + Worker
27. `reactor/core/scheduler/Schedulers.java`——工厂门面与缓存
28. `reactor/core/scheduler/BoundedElasticScheduler.java`——最精巧实现（BoundedServices/BoundedState）
29. `reactor/core/scheduler/SchedulerTask.java`——"取消后必不执行"的状态机

**重试与测试（收尾）**

30. `reactor/util/retry/RetryBackoffSpec.java`——退避算法；进阶读 `reactor-test/src/main/java/reactor/test/StepVerifier.java` 与 `VirtualTimeScheduler.java`

## 结语

Reactor 的源码初看是一堵 364 个类的墙，读完会发现它其实是**同一个故事的三次重复**：

- **规范层**：四个接口、六种信号——`request` 与 `onNext` 方向相反，这是整套体系的"万有引力"；
- **操作符层**：每个类都在回答"我在两个方向之间如何截留、翻译、转发"——`FluxMap` 与 `FluxFlatMap` 的差别只是转发策略的复杂度；
- **基础设施层**：调度器管"信号在哪个线程"，队列管"信号在哪儿排队"，Context 管"信号带什么行李"，Retry/Sinks 管"信号死了/从哪来"。

理解了"组装是画图纸、订阅是开工、数据是流水线运转"这三段论，再配上 `request(n)` 的方向感，读任何操作符源码都有了抓手。愿这份文档成为你打开 `D:\code\3rd\reactor-core` 时手边的那张地图。


