# Micrometer Tracing 深度源码解析（写给初学者的架构全景）

> **本文基于的源码**（双仓库）：
> - `D:\code\3rd\micrometer-tracing`，main 分支 **1.8.0-SNAPSHOT**（v1.8.0-M2 之后第 3 个提交，Git commit `03c0a58`，2026-10-02）。项目版本号由 release tag 派生（`build.gradle:41`：`ext['release.version'] = tag.substring(1)`），main 上即 1.8.0-SNAPSHOT。
> - `D:\code\3rd\spring-boot`，main 分支 **4.1.0-SNAPSHOT**（commit `7f9eef2c33`，2026-10-01）——用于第九章"Spring Boot 如何把这套 API 装配进容器"的实证。Boot 3.x 的差异会用 git tag（v3.0.0）单独标注。
>
> **版本取舍说明**：这个项目比 Spring Cloud CircuitBreaker 还小——三个主源码模块共 **110 个 Java 文件**（核心 API 59 + Brave 桥 24 + OTel 桥 27），核心里没有一个"追踪算法"：Tracer 是接口、Span 是接口、Propagator 是接口，真正干活的是桥接模块背后的 Brave 或 OpenTelemetry SDK。骨架自 1.0（2022-11，与 Spring Boot 3.0 同步 GA）以来几乎没变，演进都在外围（Link、批量 tag、上下文传播协调、JSpecify 空安全）。因此本文内容对使用 1.0 ~ 1.8 的读者全部适用；演进差异在 1.6 节逐项标注（全部经本地 git tag 实证）。
>
> **阅读约定**：每章"先白话、后源码"——先用一两句人话讲清"这是什么、为什么需要它"，再给出源码证据链（`模块/src/main/java/.../类名.java` + 方法名 + 行号 + 代码片段）。行号只对该快照精确，读者用 IDEA 打开源码按类名 + 方法名定位即可。文中的【源码证据】路径均省略前缀 `micrometer-tracing/src/main/java/io/micrometer/tracing/`（桥接模块则注明完整模块路径）。

## 如何读这份文档

如果你是初学者，推荐两遍读法：

- **第一遍（建立地图，1 小时）**：只读第一章（总览）每节开头的白话段、第二章（背景，搞清 Trace/Span/Baggage 四个名词）、各章"本章小结"、第十章（贯通视图）。目标是能回答：一条 Trace 是怎么从 HTTP 请求头里"长"出来的？为什么 Spring Boot 3 换掉了 Sleuth？Observation 和 Span 是什么关系？
- **第二遍（深入源码）**：对照【源码证据】打开源码逐行读。顺序建议：第三章（核心 API，全文的"接口面"）→ 第四章（handler 包，全文核心，讲透 Observation → Span 的翻译过程）→ 第五章（跨线程传播）→ 第七、八章（两个桥，选你用的那个精读，另一个跳读）→ 第六章（Brave 桥，结构同第七章）→ 第九、十章（Spring Boot 装配与全链路）。

---

# 一、总览：定位、设计哲学与整体架构

## 1.1 一句话定位

**Micrometer Tracing 是一个"追踪门面"（Tracing Facade）**：它把"如何记录一次跨进程调用的时序数据（Span）"抽象成一组小接口（`Tracer`、`Span`、`ScopedSpan`、`Baggage`、`Propagator`），自己不实现任何采样、传播、上报算法，而是让 **OpenZipkin Brave** 和 **OpenTelemetry** 两个具体追踪库通过"桥"来填空。你在 Spring Boot 3/4 里看到的"开箱即用的分布式追踪"——HTTP 头里的 `traceparent`、日志里的 `[traceId-spanId]`、Jaeger/Zipkin/Tempo 上的一条调用链——底层全部是这套门面在起作用。

它解决的不是"怎么追踪"（那是 Brave/OTel SDK 的事），而是**"怎么让框架埋点和业务代码不绑死在任何一家追踪库上"**。这与 Micrometer 之于监控（MeterFacade）、SLF4J 之于日志（LoggerFacade）是同一个配方：**应用编程 against 门面，运行时桥接 to 实现**。官方 README 自述只有一句话："A application tracing facade."（`README.md:11`）

它在 Spring 生态里的特殊地位：**它是 Micrometer Observation（指标 + 追踪的统一 API）的"追踪执行器"**。`micrometer-observation` 定义了 Observation 生命周期（onStart/onStop/onError/onScopeOpened……），而 Micrometer Tracing 的 `handler` 包提供了把这些生命周期事件"翻译"成 Span 的全套处理器——这是本文第四章的主题，也是读这个项目最重要的一条主线。

## 1.2 设计哲学：读源码前先记住四句话

1. **门面 + 桥，不造轮子**。核心模块 59 个文件里全是接口和极小的工具类，找不到一行"生成 traceId"或"上报到 Zipkin"的代码。真正干活的是两个桥：`micrometer-tracing-bridge-brave`（24 个文件，包装 Brave）和 `micrometer-tracing-bridge-otel`（27 个文件，包装 OTel）。这与 Spring Cloud CircuitBreaker 不实现熔断算法（见 [Spring Cloud CircuitBreaker.md](Spring%20Cloud%20CircuitBreaker.md) 1.2 节）是同一个思路，甚至更彻底——这里连"实现"都在仓库之外。
2. **API 词汇表抄 Brave，兼容性抄 OTel**。`Tracer`、`Span`、`ScopedSpan`、`CurrentTraceContext` 的方法签名几乎逐字来自 Brave（每个接口的 Javadoc 都写着 "This API was heavily influenced by Brave. Parts of its documentation were taken directly from Brave."，见 `Tracer.java:25`）；而 `Span.Kind`（SERVER/CLIENT/PRODUCER/CONSUMER）、`Propagator`（inject/extract）、`Span.Builder` 的语义则取自 OpenTelemetry（`Span.java:269`："Documentation of the enum taken from OpenTelemetry"）。一门两姓，只为让两个世界的用户都觉得"眼熟"。
3. **Observation 是前台，Tracing 是后台**。1.0 起这个项目就是为 Observation 体系设计的：`handler` 包里的四个 `ObservationHandler` 实现，负责在 Observation 的生命周期回调里创建/结束/透传 Span。**你很少需要直接 new 一个 Span**——框架组件（Web、RestClient、Kafka……）创建的每个 Observation 都会被这些 handler 自动翻译成 Span。
4. **上下文就是 ThreadLocal（或它的抽象化）**。`CurrentTraceContext`（把当前 TraceContext 放进线程局部）、`contextpropagation` 包的 `ThreadLocalAccessor`（把 Span/Baggage 挂到 Reactor/虚拟线程等场景）、OTel 桥直接采用 OTel 自己的 `Context` 存储——三个层次的答案指向同一个问题：**代码跳线程了，"当前是谁"不能丢**。

## 1.3 三模块分层全景

```
┌──────────────────────────── 你的代码 / 框架埋点 ───────────────────────────┐
│  tracer.nextSpan(...).start()          ← 手工埋点（少见）                    │
│  Observation.start()                   ← 框架自动埋点（主流：Web/Client/MQ） │
│  @NewSpan / @ContinueSpan / @SpanTag   ← 注解埋点（第八章）                  │
├──────────── 核心门面：micrometer-tracing（59 文件）─────────────────────────┤
│  根包   Tracer/Span/ScopedSpan/SpanCustomizer/Span.Builder/TraceContext     │
│         CurrentTraceContext/Baggage/BaggageManager/Link/ThreadLocalSpan     │
│  handler        4 个 TracingObservationHandler   ← Observation 翻译层（核心）│
│  contextpropagation  2 个 ThreadLocalAccessor + ReactorBaggage             │
│  annotation     @NewSpan/@ContinueSpan/@SpanTag + SpanAspect                │
│  propagation    Propagator（inject/extract 抽象）                           │
│  exporter       FinishedSpan/SpanReporter/SpanFilter/SpanExportingPredicate│
│  internal       DefaultSpanNamer/EncodingUtils/SpanNameUtil                 │
├────────── 桥接层：micrometer-tracing-bridges（二选一引入）──────────────────┤
│  bridge-brave（24 文件）：BraveTracer/BraveSpan/BraveCurrentTraceContext      │
│       W3CPropagation/ProbabilityBasedSampler/RateLimitingSampler …           │
│       → 下沉到 io.zipkin.brave:brave（再上 Zipkin/Tempo 等后端）              │
│  bridge-otel（27 文件）：OtelTracer/OtelSpan/OtelCurrentTraceContext          │
│       EventPublishingContextWrapper/Slf4JEventListener/                      │
│       BaggageTaggingSpanProcessor/CompositeSpanExporter …                    │
│       → 下沉到 opentelemetry-sdk（再上 OTLP 任意后端）                        │
├────────────────── Spring Boot 集成层（Boot 仓库，第九章）───────────────────┤
│  Boot 4.x：spring-boot-micrometer-tracing / -brave / -opentelemetry /        │
│            -otlp / -zipkin 等独立模块 + 自动装配                              │
│  Boot 3.x：全部在 spring-boot-actuator-autoconfigure 的 tracing 包            │
└──────────────────────────────────────────────────────────────────────────────┘
```

> 一个容易踩的认知坑：**"Micrometer Tracing" ≠ 一个追踪系统**。它既不存数据也不做界面，甚至不生成采样决定（那是 Brave `Sampler`/OTel `Sampler` 的事）。把它类比成 JDBC：核心模块是 `java.sql.*`，两个桥是 JDBC 驱动，Jaeger/Zipkin/Tempo 是数据库。

## 1.4 依赖图（以各模块 build.gradle 实证）

```
             io.micrometer:micrometer-observation ┐
             io.micrometer:context-propagation    │（核心模块的 api 依赖）
                                                  ▼
                          micrometer-tracing（核心门面）
                            ▲ api             ▲ api
      micrometer-tracing-bridge-brave    micrometer-tracing-bridge-otel
        ├─ io.zipkin.brave:brave           ├─ opentelemetry-api / sdk / sdk-trace
        ├─ brave-context-slf4j             ├─ opentelemetry-sdk-common / -trace
        ├─ brave-instrumentation-http      ├─ opentelemetry-extension-trace-propagators
        ├─ brave-propagation-w3c           └─ opentelemetry-semconv
        └─ zipkin-aws（可选）
```

真实依赖声明（摘自各模块 `build.gradle` 的 `api(...)`，已逐个验证）：

| 模块 | 直接依赖（api） | 说明 |
|---|---|---|
| micrometer-tracing | micrometer-observation、context-propagation（均 api）；aspectjweaver、aopAlliance、micrometer-core、reactor-core（optional） | **observation + context-propagation 是编译期硬依赖**，追踪与指标、跨线程传播天生一体 |
| bridge-brave | micrometer-tracing、brave、brave-context-slf4j、brave-instrumentation-http、brave-propagation-w3c；**显式 exclude zipkin-reporter2/zipkin2**（上报交给 Zipkin Reporter 单独配） | 上报器不在本模块 |
| bridge-otel | micrometer-tracing、opentelemetry-api、opentelemetry-sdk、opentelemetry-sdk-trace、sdk-common、extension-trace-propagators、semconv | OTel SDK 全家桶 |

这张表就是架构说明：**核心模块只依赖 micrometer 自己的两个库**，Brave/OTel 只出现在桥里——业务代码 import 不到 `brave.*` 或 `io.opentelemetry.*` 的任何类型（除非故意下钻）。

## 1.5 关键问题 → 方案映射（全文导览）

| 分布式追踪的关键问题 | Micrometer Tracing 的方案 | 详见 |
|---|---|---|
| 跨进程调用的时序数据怎么表示、怎么手工记录 | `Tracer`（门面）+ `Span`/`ScopedSpan`（两种记时方式）+ `Span.Builder` | 第三章 |
| 请求打到本服务时，怎么知道"我是谁的子调用" | `Propagator.extract`（收头解析）→ `Propagator.inject`（发头写入） | 第三章 3.7、第四章 4.4/4.5 |
| 框架埋点（Web/Client/MQ）怎么自动变成 Span | 4 个 `TracingObservationHandler` 挂到 `ObservationRegistry`，按 Context 类型分发 | 第四章 |
| Span 期间打的日志怎么带上 traceId | `CurrentTraceContext` + MDC（Brave 原生；OTel 桥经 `Slf4JEventListener`） | 第三章 3.5、第七章 7.5 |
| 异步/线程池/Reactor 场景"当前 Span"丢失怎么办 | `contextpropagation` 包的 `ThreadLocalAccessor` 与"让位协议" | 第五章 |
| 业务字段（用户 ID、租户）想跟着链路走 | `Baggage`/`BaggageManager`/`BaggageInScope` + 跨线程恢复 + 落 Span 属性（`BaggageTaggingSpanProcessor`） | 第三章 3.6、第七章 7.6 |
| 不同服务用不同传播格式（W3C/B3）怎么办 | `Propagator` 抽象 + 桥内实现（Brave 的 `W3CPropagation`、OTel 的 `OtelPropagator`）+ Boot 的 produce/consume 配置 | 第六章 6.4、第九章 9.7 |
| 少量请求采样，其余怎么办 | `isNoop()` 的 Span 照样传播上下文但不记录——"牺牲自己、传递火种" | 第三章 3.3 |
| 不想引入任何追踪库时的"零成本空转" | `Tracer.NOOP` 全家桶 + Boot 的 `NoopTracerAutoConfiguration` | 第三章 3.8 |
| 想在导出前改/删 Span（脱敏、降噪） | `SpanFilter`/`SpanExportingPredicate`/`SpanReporter` | 第九章 9.1 |
| 方法上加个注解就想要 Span | `@NewSpan`/`@ContinueSpan`/`@SpanTag` + AspectJ 切面 | 第八章 |

## 1.6 版本演进：1.0 → 1.8 关键变化对比（全部本地 git tag 实证）

写作时（2026 年 10 月）的版本格局：1.5.x ~ 1.7.x 并行维护，1.8 处于里程碑阶段（最新 tag `v1.8.0-M2`，正是本文分析的 main 快照）。本节所有"特性属于哪个版本"的结论都经本地仓库的 git 标签逐项验证（`git ls-tree -r <tag> --name-only`、`git grep <关键字> <tag>`），GA 日期取自各发布 tag 的提交时间。

### 1.6.1 版本时间线

| 版本 | GA 时间 | 配套 Spring Boot | 一句话主题 |
|---|---|---|---|
| 1.0.0 | 2022-11-07 | 3.0（同月 GA） | Sleuth 继任者亮相：核心 API + Brave/OTel 双桥 + Observation handler |
| 1.1.0 | 2023-05-08 | 3.1 | `Link`（跨 Trace 关联）、数值 tag 重载（long/double/boolean，#234） |
| 1.2.0 | 2023-11-13 | 3.2 | 迭代维护（1.1.0 已引入 `ObservationAwareSpanThreadLocalAccessor` 带 registry 构造器 #240） |
| 1.3.0 | 2024-05-13 | 3.3 | `ObservationAwareBaggageThreadLocalAccessor`、`ReactorBaggage`、`TestSpanReporter` |
| 1.4.0 | 2024-11-12 | 3.4 | 批量 tag：`tagOfStrings/tagOfLongs/tagOfDoubles/tagOfBooleans`（`Span.java:191-223`） |
| 1.5.0 | 2025-05-13 | 3.5 | 迭代维护（1.3.0 起废弃的 `TracingContext.getBaggage/setBaggage` 至今仍在，见 1.6.2 的提醒） |
| 1.6.0 | 2025-11-06 | 4.0 | **JSpecify 空安全**：`@Nullable` 自有注解全部替换为 org.jspecify（0 → 36 个文件），根包 `@NullMarked` |
| 1.7.0 | 2026-06-08 | 4.1 | W3C baggage 传播加固（防注入/防 OOM，补丁同步 1.6.7/1.7.1）；配合 Observation 废弃 `Scope.reset()/makeCurrent()` |
| 1.8.0-M2 | 2026-09-18（里程碑） | 4.2（计划） | 本文快照所在线 |

时间线与 Spring Boot 高度同步不是巧合：Boot 3.0 GA（2022-11-16）起就把 `micrometer-tracing` 作为 actuator 的默认追踪门面，Boot 4.0 起进一步拆出独立模块（见第九章 9.3）。

### 1.6.2 特性引入版本对照表（git 实证，可复现）

每行都可用 `git ls-tree -r <tag> --name-only | grep <文件>` 或 `git grep -c <关键字> <tag> -- <路径>` 复现。"详见"列指向本文对应章节。

| 特性 | 引入版本 | 实证（旧 tag → 新 tag） | 详见 |
|---|---|---|---|
| 核心 API + 双桥 + Observation handler + 注解切面 | 1.0.0 | 历史基线 | 三~八章 |
| `ObservationAwareSpanThreadLocalAccessor`（span 跨线程协调） | 1.0.4 | v1.0.0 无 → v1.1.0 有（1.0.x 分支补丁） | 五.5.3 |
| 带 `ObservationRegistry` 的 accessor 构造器 | 1.1.0 | commit `6c8384ec`（#240） | 五.5.3 |
| `Link`（跨 Trace 的 Span 关联）+ `Builder.addLink` | 1.1.0 | v1.0.0=0 → v1.1.0=1（`tracing/Link.java`） | 三.3.4 |
| tag 的 long/double/boolean 重载 | 1.1.0 | commit `0faf66fb`（#234） | 三.3.2 |
| `ObservationAwareBaggageThreadLocalAccessor`（baggage 跨线程协调） | 1.3.0 | v1.2.0=0 → v1.3.0=2 处 | 五.5.4 |
| `ReactorBaggage`（Reactor 场景 baggage 工具） | 1.3.0 | v1.2.0=0 → v1.3.0=1 | 五.5.5 |
| `TestSpanReporter`（测试专用 reporter） | 1.3.0 | v1.2.0=0 → v1.3.0=1 | 九.9.1 |
| 批量 tag（tagOfStrings 等 4 个 default 方法） | 1.4.0 | v1.3.0=0 → v1.4.0=1（`Span.java`） | 三.3.2 |
| `TracingContext.getBaggage/setBaggage` 标记废弃 | 1.3.0 | v1.2.0=0 → v1.3.0=2 处 @Deprecated（Javadoc 写 "scheduled for removal in 1.5.0"，但截至本 1.8 快照**仍在**） | 四.4.2 |
| JSpecify `@Nullable`/`@NullMarked` 全面替换 | 1.6.0 | v1.5.0=0 → v1.6.0=36 个文件 | 三.3.1 |
| W3C baggage 传播加固（防注入/防 OOM） | 1.6.7 / 1.7.1（补丁）→ 1.7.0 | commit `8c79d113`、`574fd1ed` | 六.6.4 |

三个容易搞错的点，特别提醒：

- **这个项目没有"大版本重构"**：`Tracer.nextSpan()`、`Span.start()/end()`、`Propagator.inject/extract` 这些签名从 1.0.0 活到现在，升级 1.x 基本无痛；
- **`ObservationAwareSpanThreadLocalAccessor` 不是 1.3 特性**，1.0.4 补丁就有（本地 v1.0.4 tag 实证，Javadoc `@since 1.0.4` 也写明），1.1.0 只是加了构造器；
- **JSpecify 迁移不算 1.7 特性**，1.6.0 完成（36 个文件引用 org.jspecify，1.5.0 为 0）；
- **"scheduled for removal" 不等于已移除**：`TracingContext.getBaggage/setBaggage` 自 1.3.0 标废弃、按 Javadoc 应在 1.5.0 移除，但本 1.8 快照里依然存在（返回空 Map 的空壳）——读源码别只信版本号，要看 tag 里的实际代码。

### 1.6.3 对使用者的意义

- **Boot 3.x（tracing 1.x ~ 1.4/1.5）用户**：本文一切核心章节适用，注意 Boot 3.x 的自动配置类在 `org.springframework.boot.actuate.autoconfigure.tracing` 包（v3.0.0 git grep 实证）。
- **Boot 4.x（tracing 1.6+）用户**：自动配置迁到 `org.springframework.boot.micrometer.tracing.autoconfigure`（独立模块），行为不变、包名变化，细节见第九章 9.3。
- **还在用 Sleuth 的读者**：Spring Cloud Sleuth 已停止维护，其"给每个 Span 打日志"的能力由本文第五章 + Boot 的日志关联（九.9.6）接管；其探测机制（Tracer 接口）正是本文的主角。

## 1.7 全文章节地图

- **第二章 背景**：分布式追踪的四个名词（Trace/Span/TraceContext/Baggage）、W3C 传播标准、Brave/Zipkin/OTel 三方关系、Sleuth 退场史。
- **第三章 核心 API（micrometer-tracing 根包）**：`Tracer`、`Span`/`ScopedSpan`、`Span.Builder`、`TraceContext`/`CurrentTraceContext`、`Baggage` 家族、`Propagator`、NOOP 全家桶。
- **第四章 与 Observation 的接线（handler 包，全文核心）**：四个 handler 的接口骨架、分发规则、Span 创建/结束时机、`getParentSpan` 的三种来源、`RevertingScope` 的恢复语义。
- **第五章 跨线程传播（contextpropagation 包）**：`ThreadLocalAccessor` 机制、Span/Baggage 两个 accessor 与 Observation 的"让位协议"、Reactor 工具。
- **第六章 Brave 桥**：包装类清单、`BraveCurrentTraceContext` 的线程局部实现、`W3CPropagation`、采样器。
- **第七章 OTel 桥**：建立在 OTel `Context` 上的 `OtelCurrentTraceContext`、`OtelSpanBuilder` 的落地时机、`EventListener` 事件广播、MDC 联动、`BaggageTaggingSpanProcessor`。
- **第八章 注解体系（annotation 包）**：`@NewSpan`/`@ContinueSpan`/`@SpanTag`/`@SpanName` 与 AspectJ 切面的落地。
- **第九章 导出与 Spring Boot 集成**：`exporter` 包三件套；Boot 3.x → 4.x 装配演变；`MicrometerTracingAutoConfiguration` 的 Bean 图；`management.tracing.*` 配置大全；日志关联。
- **第十章 贯通视图**：把"一次 HTTP 请求"的三条时间线（Observation 生命周期、Span 生命周期、线程切换）叠成一张全景图。
- **第十一章 附录**：关键接口速查表与学习路线。


---

# 二、背景：分布式追踪的三块基石

## 2.1 白话：一次请求的"旅程日志"

用户点一下"下单"，请求经过网关、订单服务、库存服务、支付服务……某天报了 500，日志分散在四台机器上，你怎么知道哪一段慢、哪一段错？**分布式追踪**（Distributed Tracing）的答案是：给这次"下单"旅程发一个全局编号（traceId），旅程里的每一段工作（处理 HTTP 请求、查数据库、调下游）各发一个子编号（spanId），段与段之间记好父子关系，全部打上时间戳——最后把这些"小段"拼起来，就是一条可视化的调用链。

## 2.2 Trace / Span / TraceContext / Baggage 四个名词

这四个名词贯穿全文，Micrometer Tracing 对它们的建模如下（类型名 → 源码位置）：

| 名词 | 白话 | Micrometer Tracing 中的类型 |
|---|---|---|
| Trace | 一次完整旅程（一个全局编号贯穿始终） | 没有专门类型——由共享同一 traceId 的 Span 集合隐式构成 |
| Span | 旅程中的一段有始有终的工作 | `Span`（可异步、显式 start/end）与 `ScopedSpan`（"当前线程的这段代码"，end 即收工） |
| TraceContext | Span 的"身份证"：traceId/parentId/spanId/采样标记 | `TraceContext`（四个方法，见 3.5） |
| Baggage | 跟着旅程走的业务行李（用户 ID、租户号……） | `Baggage`/`BaggageView`/`BaggageManager`/`BaggageInScope`（见 3.6） |

【源码证据】`TraceContext.java:57-78`——身份证只有四个字段：

```java
String traceId();                    // 全局旅程编号
@Nullable String parentId();         // 父段编号（根 Span 没有）
String spanId();                     // 本段编号
@Nullable Boolean sampled();         // true/false/延迟决定（null）
```

`sampled()` 返回 `Boolean`（可为 null）值得停一下：**采样决定可以由入口服务做，也可以交给下游**。null 表示"还没有决定"，这给了桥接实现按自身策略（Brave/OTel 的 Sampler）延迟判定的空间。

## 2.3 上下文传播：traceparent 头与 W3C Trace Context

跨进程时，"我是谁的子调用"必须随请求带走。W3C Trace Context 标准规定了两个 HTTP 头：

```
traceparent: 00-<32位traceId>-<16位spanId>-<2位flags>   例：00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
tracestate:  <vendor 特有的附加状态>
```

Micrometer Tracing 对"读写这两个头"的抽象就是 `Propagator` 接口（`propagation/Propagator.java`，三个核心方法 `:65/:77/:92`）：

```java
List<String> fields();                    // 我认识哪些头（traceparent、tracestate、baggage…）
<C> void inject(TraceContext context, @Nullable C carrier, Setter<C> setter);   // 发请求时写入
<C> Span.Builder extract(@Nullable C carrier, Getter<C> getter);                // 收请求时读出
```

`carrier`（载体）刻意设计成泛型 `<C>`：HTTP 请求头、Kafka 消息头、gRPC metadata 都能当载体，`Setter`/`Getter` 负责具体读写——框架适配器只需提供"怎么从我的载体里取一个 key"的 lambda。这与 Spring 观测体系里 `SenderContext`/`ReceiverContext` 的 `Setter`/`Getter` 一一对应（第四章会看到对接处）。

**Baggage 头**：W3C Baggage 标准定义了第三个头 `baggage: userId=42,tenant=acme`，让业务字段跟着链路走。OTel 桥的 `BaggageTextMapPropagator` 负责这个头的读写；Brave 桥里 baggage 走 `tracestate`（`W3CPropagation.java:109`）或自定义头。

## 2.4 Brave、Zipkin、OpenTelemetry 的关系

- **Zipkin**：Twitter 开源的追踪后端（存储 + 查询 UI），2012 年诞生。
- **Brave**：Zipkin 的 Java 插桩库（`io.zipkin.brave:brave`），运行在应用内，负责生成 Span 并上报给 Zipkin（或其他兼容后端）。本文的 Brave 桥就是包装它。
- **OpenTelemetry（OTel）**：CNCF 项目，由 OpenTracing 与 OpenCensus 两大阵营合并而来，目标是统一"指标、日志、追踪"三类遥测数据的 API + SDK + 协议（OTLP）。本文的 OTel 桥对接其 tracing API/SDK。
- **Micrometer Tracing 的立场**：我不管你是 Zipkin 派还是 OTel 派，我给你一个统一词汇表，你选一个桥。

## 2.5 从 Spring Cloud Sleuth 到 Micrometer Tracing

Spring 生态的追踪史值得一小段：Sleuth（Spring Cloud Sleuth）曾是 Spring Boot 2.x 时代的追踪方案，自己定义了一套 `Tracer`/`Span` 接口。Boot 3.0 立项时，Spring 团队决定把追踪能力下沉到 Micrometer（与指标统一为 Observation 体系），Sleuth 停止演进，其接口设计被 Micrometer Tracing 继承——**`Tracer` 接口的 Javadoc 署名 "OpenZipkin Brave Authors" + "Marcin Grzejszczak"（Sleuth 的作者）**，`Tracer.java:62-63`。所以老 Sleuth 用户迁移到 Micrometer Tracing 会发现 API 相当眼熟：这不是巧合，是同一个人在新家续写旧 API。

## 2.6 本章小结

分布式追踪 = traceId（旅程）+ span（分段）+ 上下文传播（traceparent 头）+ baggage（行李）。Micrometer Tracing 把这四样抽象成 `TraceContext`/`Span`/`Propagator`/`Baggage` 四组接口，实现交给 Brave 或 OTel 的桥。下一章进入接口本体。


---

# 三、核心 API（micrometer-tracing 模块，接口面）

## 3.1 模块定位与包结构

核心模块 59 个文件、7 个包，每个包一个职责：

| 包 | 文件数 | 职责 | 章节 |
|---|---|---|---|
| 根包 `io.micrometer.tracing` | 15 | Tracer/Span/ScopedSpan/TraceContext/CurrentTraceContext/Baggage 家族/Link/ThreadLocalSpan | 本章 |
| `handler` | 6 | Observation → Span 的翻译层 | 第四章 |
| `contextpropagation` | 4 | 跨线程恢复"当前 Span/Baggage" | 第五章 |
| `annotation` | 12 | `@NewSpan` 等注解与切面 | 第八章 |
| `propagation` | 1 | `Propagator`（W3C/B3 的抽象） | 3.7 |
| `exporter` | 6 | 导出前对 FinishedSpan 的钩子 | 第九章 9.1 |
| `internal`/`docs` | 7 | SpanNamer 等工具 / 文档专用注解 | — |

自 1.6.0 起整个核心模块是 **JSpecify `@NullMarked`** 的：`package-info.java:16` 声明 `@NullMarked package io.micrometer.tracing;`——包内默认非空，可空的参数/返回值逐一标 `@Nullable`。读源码时"有没有 @Nullable"本身就是 API 语义的一部分（例如 `Tracer.currentSpan()` 标了 `@Nullable`，含义是"没有现场时返回 null 而不是抛异常"）。

## 3.2 Tracer：门面中的门面

【源码证据】`Tracer.java:69`：

```java
public interface Tracer extends BaggageManager {
    Span nextSpan();                                    // :153 有父取父、无父开新 Trace
    @Nullable Span nextSpan(@Nullable Span parent);     // :161 显式指定父
    Tracer.SpanInScope withSpan(@Nullable Span span);   // :180 把 Span 放进"现场"
    ScopedSpan startScopedSpan(String name);            // :202 开一个"自带现场"的 Span
    Span.Builder spanBuilder();                         // :212 高级玩法：先配置再启动
    TraceContext.Builder traceContextBuilder();         // :218 纯上下文构建（手工接传播时用）
    CurrentTraceContext currentTraceContext();          // :224 现场管理器
    @Nullable SpanCustomizer currentSpanCustomizer();   // :230 只想打 tag/event 时用它
    @Nullable Span currentSpan();                       // :236 当前现场里的 Span，没有则 null
}
```

三组方法对应三种使用姿势，初学者按此对号入座：

1. **"帮我记一段"**：`startScopedSpan("encode")` ——ScopedSpan 自带现场，`try/catch/finally` 三件套收尾（Javadoc 里的标准示例，`Tracer.java:187-198`）；
2. **"我需要更多控制"**：`nextSpan().name("encode").start()` + `withSpan(span)` 显式管现场，`finally` 里 `span.end()`（`Tracer.java:46-58`）；
3. **"我要在收请求时接上下文"**：`spanBuilder()` 拿 Builder，配合 `Propagator.extract` 构造带父的 Span（第四章 4.5 的正是这条路径）。

注意 `Tracer extends BaggageManager`（`:69`）——**baggage 的入口就在 Tracer 上**：`getBaggage(name)`、`createBaggage(name[, value])`、`getAllBaggage()`（`BaggageManager.java:77-125`）。设计意图：baggage 生命周期与 trace 绑定，从 Tracer 上取最自然。

## 3.3 Span 与 ScopedSpan：两种记时方式

【源码证据】`Span.java:34`：`public interface Span extends io.micrometer.tracing.SpanCustomizer`——Span 本身继承了 `SpanCustomizer`（name/event/tag 四个打标方法），加上生命周期方法：

```java
boolean isNoop();                          // :111 采样没过？照样传播上下文，但不记录
Span start();                              // :122 启动（Builder 出来的 Span 需要显式 start）
void end();                                // :235 结束并上报
void end(long time, TimeUnit timeUnit);    // :242 指定结束时间
void abandon();                            // :247 结束但**不上报**（放弃）
Span error(Throwable t);                   // :230 记异常
Span remoteServiceName(String name);       // :254 对端服务名（如 "inventory-service"）
Span remoteIpAndPort(String ip, int port); // :263 对端地址
```

`Kind` 枚举（`:271-299`）取自 OTel：`SERVER`（收到请求）/ `CLIENT`（发起调用）/ `PRODUCER`（发消息）/ `CONSUMER`（收消息）——四个 Kind 正是"进程边界"的四种形态，也是第四章 Sender/Receiver handler 分类的根源。

`ScopedSpan`（`ScopedSpan.java`）是 Span 的"限缩版"：只有 name/tag/event/error/end 五件事，**没有 abandon、没有 Builder、创建即入现场**。语义差异一句话说清：`Span` + `withSpan` 是"现场与记时分离"（你可以造好一个 Span、稍后在另一个线程 `withSpan` 它），`ScopedSpan` 是"现场与记时同生共死"。后端记录的结果两者完全一致（`Tracer.java:60`："Both of the above examples report the exact same span on finish!"）。

`isNoop()` 是采样机制的 API 面：**采样没过的 Span 是一个"空壳"，但它依然要参与 inject**（下游还要靠它续 traceId）——这就是注释 `:107-109` 说的 "However, this span should still be injected into outgoing requests"。理解为"**牺牲自己、传递火种**"即可。

## 3.4 Span.Builder：先配置再启动

为什么需要 Builder 而不直接 `nextSpan()`？`Tracer.java:205-209` 的 Javadoc 给了答案：**有些属性只能在 Span 启动前设置**（Kind、父上下文、起始时间戳、Link），extract 场景（收请求时）尤其依赖它。

【源码证据】`Span.java:310-535` 的 `Span.Builder` 关键方法：

```java
Builder setParent(TraceContext context);   // :377 显式指定父亲（extract 出来的）
Builder setNoParent();                     // :383 断绝父子关系（独立 Trace）
Builder kind(Span.Kind spanKind);          // :489 SERVER/CLIENT/...
Builder startTimestamp(long, TimeUnit);    // :512 指定开始时间（消息积压场景回填）
Builder addLink(Link link);                // :525 关联另一条 Trace 的 Span（1.1.0+）
Span start();                              // :533 构建并启动
```

`Link`（`Link.java`，1.1.0 引入）是"亲戚关系"而非"父子关系"：一个批处理任务消费 100 条来自不同 Trace 的消息时，批处理 Span 用 100 个 Link 指回各源头，图谱上出现"扇入"虚线。数据结构极简：`TraceContext + Map<String,Object> tags`（`Link.java:30-40`）。

## 3.5 TraceContext 与 CurrentTraceContext：身份与现场

`TraceContext`（3.5 节开头已列）是纯数据接口；`CurrentTraceContext` 管"**当前线程的现场**"：

【源码证据】`CurrentTraceContext.java`（三个核心方法 + 四个包装方法）：

```java
@Nullable TraceContext context();                            // :85 当前现场里的身份证
Scope newScope(@Nullable TraceContext context);              // :94 开现场（闭包要关）
Scope maybeScope(@Nullable TraceContext context);            // :102 已在现场就不换（避免重复开）
<C> Callable<C> wrap(Callable<C> task);                      // :110 任务跨线程：捕获-恢复现场
Runnable wrap(Runnable task);                                // :117 同上
Executor wrap(Executor delegate);                            // :124 把整个线程池包成"感知现场"的
ExecutorService wrap(ExecutorService delegate);              // :129 同上
```

`maybeScope` 的存在意义：handler 反复开关现场时，如果请求的 context 和当前一样，就返回一个空 Scope，避免无意义的 ThreadLocal 抖动（第四章 4.2 的 `setMaybeScopeOnTracingContext` 用的正是它）。

**现场是谁实现的？核心模块不回答**——这是接口。Brave 桥交给 Brave 自己的 ThreadLocal 体系（第六章 6.3），OTel 桥交给 OTel 的 `Context` 存储（第七章 7.2，能跟随 Reactor/虚拟线程等场景）。这是"同一份业务代码、两种现场机制"的典型适配。

## 3.6 Baggage 家族：跟着 Trace 走的业务数据

Baggage = "写进请求头、跟着 Span 走全链路的业务键值对"。接口家族四件套，按"读 → 写 → 作用域 → 工厂"分层：

| 类型 | 角色 | 关键方法 |
|---|---|---|
| `BaggageView`（`BaggageView.java`） | 只读视图 | `name()`、`get()`、`get(TraceContext)` |
| `Baggage`（`Baggage.java:39`，extends BaggageView） | 可写条目 | `makeCurrent(...)` 系（写值并短暂入现场）；`set(...)` 自 1.1.0 起 @Deprecated（scope 语义收紧，改用 makeCurrent） |
| `BaggageInScope`（`BaggageInScope.java`） | makeCurrent 返回的作用域 | `close()` 时恢复原值（防止"行李箱"泄漏到别的请求） |
| `BaggageManager`（`BaggageManager.java`） | 工厂/查找 | `getBaggage(name)`、`createBaggage(name[, value])`、`getAllBaggage()`；**Tracer 继承它** |

为什么写个值还要"开作用域"？Javadoc（`Baggage.java:24-30`）说得很直白：有的实现（OTel）baggage 不可变，更新值会创建新 Context，必须用 Scope 圈住"新值生效的代码范围"；Brave 则可变，Scope 形同虚设但 API 统一。**接口为最严格的实现设计，宽松的实现向下兼容**——这是门面设计的通用法则。

## 3.7 Propagator：inject/extract 的统一抽象

2.3 节已展示三个方法（fields/inject/extract 分别在 `Propagator.java:65/:77/:92`），这里补两点实现视角：

- `Propagator.NOOP`（`Propagator.java:46-62`）的 Javadoc 点名了适用场景："sender/receiver that do not need any propagation. e.g. database access"——本地组件（DB）不传播，注入一个空 Propagator 即可，调用方代码零分支。
- `fields()` 返回"我认识的头名集合"绝非摆设：HTTP 客户端在把头透传给下游前，要用这个集合做**白名单清理**（防止把上游的 `traceparent` 原样转发、被下游误读）。Spring Boot 的 REST 客户端埋点就用它决定哪些头要管理。

## 3.8 NOOP 全家桶：空实现也是 API 的一部分

核心模块里几乎每个接口都有 `NOOP` 常量：`Tracer.NOOP`（`Tracer.java:74-146`）、`Span.NOOP`、`ScopedSpan.NOOP`、`Baggage.NOOP`、`Propagator.NOOP`、`CurrentTraceContext.NOOP`……这不是装饰：

1. **依赖注入的默认值**：Spring Boot 检测到 classpath 上没有任何追踪桥时，`NoopTracerAutoConfiguration` 直接注册 `Tracer.NOOP` 这个 Bean（第九章 9.3），整个应用照常注入 Tracer、照常调用，只是什么都不记录——**业务代码与"是否开启追踪"彻底解耦**；
2. **框架内部分支消除**：框架埋点代码不需要 `if (tracingEnabled)` 判断，NOOP 实现天然吞掉一切调用。

这是 Null Object 模式在整个 API 面上的系统应用。

## 3.9 本章小结

核心模块是一张刻意收窄的接口面：`Tracer`（门面 + baggage 工厂）、`Span`/`ScopedSpan`（两种记时）、`Span.Builder`（先配置后启动，承载 extract/Kind/Link）、`TraceContext`/`CurrentTraceContext`（身份与现场）、`Baggage` 家族（业务行李）、`Propagator`（跨进程读写头）、`NOOP`（空转兜底）。所有接口的**注释几乎都来自 Brave**、**语义尽量对齐 OTel**——它是两大阵营之间的一门"普通话"。


---

# 四、与 Observation 的接线：handler 包（全文核心）

## 4.1 白话：Observation 是总机，Tracing Handler 是分机

先摆正两个体系的位置（详见 [Spring观测体系设计.md](Spring观测体系设计.md) 第 1 节）：

- **micrometer-observation** 定义了统一观测抽象：每个值得测量的操作是一个 `Observation`，生命周期回调接口是 `ObservationHandler`（onStart/onStop/onError/onEvent/onScopeOpened/onScopeClosed……）。Web 请求、RestClient 调用、Kafka 收发……Spring 生态的埋点**只面向 Observation**。
- **micrometer-tracing** 的 `handler` 包提供 6 个类：1 个接口 + 3 个实现 + 1 个 Meter 观测包装 + 1 个内部 Scope 类。它们的职责是**把 Observation 生命周期事件翻译成 Span 生命周期**：

```
ObservationRegistry
   │  (每个 Observation 的生命周期事件广播给所有 handler)
   ├─► MeterObservationHandler 们 → 记录指标（timer 计数）
   └─► TracingObservationHandler 们 → 记录 Span（本文主角）
         ├─ DefaultTracingObservationHandler      处理"本地操作"（非收/发）
         ├─ PropagatingSenderTracingObservationHandler   处理"出口"（Client/Producer）
         └─ PropagatingReceiverTracingObservationHandler 处理"入口"（Server/Consumer）
```

分发规则藏在各实现的 `supportsContext` 里：Observation 携带的 Context 类型是 `SenderContext`（要走 inject）就归 Sender handler、是 `ReceiverContext`（要走 extract）就归 Receiver handler、其他一律归 Default handler——**一次 Observation 的"类型"由它带什么 Context 决定，而 Context 是框架埋点放进去的**。

## 4.2 TracingObservationHandler：接口骨架与 TracingContext

【源码证据】`handler/TracingObservationHandler.java:43`：

```java
public interface TracingObservationHandler<T extends Observation.Context> extends ObservationHandler<T> {
    default void tagSpan(T context, Span span)          // :50 把 Observation 的 keyValues 打成 Span tag
    default String getSpanName(T context)               // :66 contextualName 优先，name 兜底
    default void onScopeOpened(T context)               // :78 开 Observation 现场时同步开 Span 现场
    default void onEvent(Event event, T context)        // :123 Observation 事件 → Span event
    default void onError(T context)                     // :134 Observation 异常 → Span error
    default void onScopeClosed(T context)               // :146 关现场
    default @Nullable Span getParentSpan(ContextView)   // :158 找父 Span（见 4.7）
    default TracingContext getTracingContext(T context) // :191 从 Observation.Context 挂一个 TracingContext
    default boolean supportsContext(...)                // :198 默认全收，由子类收窄
    default void endSpan(T context, Span span)          // :220 关闭 TracingContext 并 end
    Tracer getTracer();                                 // :230
}
```

**Observation 和 Span 的"脐带"是内部类 `TracingContext`**（`:238-337`）：它作为附件挂在 `Observation.Context` 上（`computeIfAbsent` 懒创建），保存两样东西——

```java
private @Nullable Span span;                                            // :240 这条 Observation 对应的 Span
private Map<Thread, CurrentTraceContext.Scope> scopes = new ConcurrentHashMap<>(); // :242 各线程各自的现场！
```

`scopes` 是 `Map<Thread, Scope>` 而不是一个字段——**同一个 Observation 可以在多个线程同时开现场**（异步场景：主线程开了现场，工作线程里也能查到这个 Observation），每个线程关各自的现场，互不干扰。这个细节是读懂异步链路的关键。

`onScopeOpened` 的接线（`:78-83` → `:94-103`）是全文最精妙的一段：

```java
default void onScopeOpened(T context) {
    TracingContext tracingContext = getTracingContext(context);
    Span span = tracingContext.getSpan();
    setMaybeScopeOnTracingContext(tracingContext, span);      // 用 maybeScope 开现场
}

default void setMaybeScopeOnTracingContext(TracingContext tracingContext, @Nullable Span newSpan) {
    Span spanFromThisObservation = tracingContext.getSpan();
    TraceContext newContext = newSpan != null ? newSpan.context() : null;
    CurrentTraceContext.Scope scope = getTracer().currentTraceContext().maybeScope(newContext);
    CurrentTraceContext.Scope previousScopeOnThisObservation = tracingContext.getScope();
    RevertingScope revertingScope = new RevertingScope(tracingContext, scope, previousScopeOnThisObservation);
    revertingScope = RevertingScope.maybeWithBaggage(getTracer(), tracingContext, newContext, revertingScope, ...);
    tracingContext.setSpanAndScope(spanFromThisObservation, revertingScope);   // :102
}
```

三处设计动机：**① 用 `maybeScope` 而非 `newScope`**——现场可能已经在（父 Observation 的现场），避免重复；**② `RevertingScope` 记住"本 Observation 上一个现场"**，close 时恢复它而不是盲目清空——嵌套 Observation（A 开现场、B 开现场、B 关、A 还在）的正确性全靠它（`RevertingScope.java:44-48`：`currentScope.close(); tracingContext.setScope(previousScope);`）；**③ baggage 也要跟着现场恢复**（`maybeWithBaggage`）。

## 4.3 DefaultTracingObservationHandler：本地操作

【源码证据】`handler/DefaultTracingObservationHandler.java:37-52`——短到可以全文抄：

```java
@Override
public void onStart(Observation.Context context) {
    Span parentSpan = getParentSpan(context);                     // 三种来源，见 4.7
    Span childSpan = parentSpan != null ? getTracer().nextSpan(parentSpan) : getTracer().nextSpan();
    if (childSpan != null) {
        childSpan.start();
        getTracingContext(context).setSpan(childSpan);            // Span 挂进 TracingContext
    }
}

@Override
public void onStop(Observation.Context context) {
    Span span = getRequiredSpan(context);
    span.name(getSpanName(context));                              // contextualName 优先
    tagSpan(context, span);                                       // keyValues → tags（ERROR 特殊处理）
    endSpan(context, span);                                       // end + 清理
}
```

两个细节：`tagSpan`（`TracingObservationHandler.java:50-59`）遇到 key 为 "ERROR" 的 KeyValue 时不是打 tag，而是 `span.error(new RuntimeException(value))`——Observation 侧的错误约定被翻译成 Span 侧的异常记录；`getRequiredSpan`（`:207-213`）在 Span 缺失时抛 "Span wasn't started - an observation must be started (not only created)"——**这个报错在实战中高频出现，根因是只调了 `observation.observe*` 之外的 start 变体或者 Observation 只创建未启动**。

## 4.4 PropagatingSenderTracingObservationHandler：出口（inject）

【源码证据】`handler/PropagatingSenderTracingObservationHandler.java`。适用于"我的代码要**发**一个请求/消息"——RestClient/WebClient 的客户端调用、Kafka 生产者等，埋点方会塞一个 `SenderContext`（带 `carrier` 载体和 `Setter`）进来：

```java
@Override
public void onStart(T context) {                                  // :54
    Span childSpan = createSenderSpan(context);                   // 用 Builder 建 CLIENT/PRODUCER Span
    this.propagator.inject(childSpan.context(), context.getCarrier(),
            (carrier, key, value) -> context.getSetter().set(carrier, key, value));  // :56 写头！
    getTracingContext(context).setSpan(childSpan);
}

public Span createSenderSpan(T context) {                          // :69
    Span parentSpan = getParentSpan(context);
    Span.Builder builder = getTracer().spanBuilder().kind(Span.Kind.valueOf(context.getKind().name()));
    if (parentSpan != null) builder = builder.setParent(parentSpan.context());
    if (context.getRemoteServiceName() != null) builder = builder.remoteServiceName(...);
    if (context.getRemoteServiceAddress() != null) builder = builder.remoteIpAndPort(host, port);  // URI 解析
    return builder.start();
}

@Override
public boolean supportsContext(Observation.Context context) {      // :121 分发依据
    return context instanceof SenderContext;
}
```

**inject 发生在 onStart**——发出请求的那一瞬间，`traceparent` 头就被写进载体了；后续下游服务收到的头与这个 CLIENT Span 一一对应。这就是"调用方决定 spanId，被调方以它为 parentId"的落点。

## 4.5 PropagatingReceiverTracingObservationHandler：入口（extract）

【源码证据】`handler/PropagatingReceiverTracingObservationHandler.java`。适用于"我的服务**收**到一个请求/消息"——Servlet/WebFlux 的服务端、Kafka 消费者等：

```java
@Override
public void onStart(T context) {                                   // :55
    io.micrometer.observation.transport.Propagator.Getter<Object> getter = context.getGetter();
    Span.Builder extractedSpan = this.propagator.extract(context.getCarrier(), new Propagator.Getter<Object>() { ... });
    extractedSpan.kind(Span.Kind.valueOf(context.getKind().name()));       // SERVER/CONSUMER
    if (context.getRemoteServiceName() != null) extractedSpan.remoteServiceName(...);  // 对端是谁
    if (context.getRemoteServiceAddress() != null) extractedSpan.remoteIpAndPort(...);
    getTracingContext(context).setSpan(customizeExtractedSpan(context, extractedSpan).start());
}
```

**extract 发生在 onStart**——请求刚进来，`traceparent` 头被解析、父上下文被确定、SERVER Span 带着 parent 启动。与 4.4 对读：一出一入，`Propagator` 两侧各用一次，中间的进程边界就是靠这对方法缝起来的。

`supportsContext`（`:125`）：`context instanceof ReceiverContext`。

## 4.6 TracingAwareMeterObservationHandler：给指标一次"补看"当前 Span 的机会（Exemplar）

【源码证据】`handler/TracingAwareMeterObservationHandler.java:31`。它是 `MeterObservationHandler` 的装饰器，唯一动作在 `onStop`（`:75-85`）：

```java
@Override
public void onStop(T context) {
    TracingContext tracingContext = context.getRequired(TracingObservationHandler.TracingContext.class);
    Span currentSpan = tracingContext.getSpan();
    if (currentSpan != null) {
        // 暂时把该 Span 设为"当前"，再让真正的 Meter handler 记指标
        try (CurrentTraceContext.Scope ignored = tracer.currentTraceContext().maybeScope(currentSpan.context())) {
            this.delegate.onStop(context);
        }
    } else {
        this.delegate.onStop(context);
    }
}
```

为什么要在记指标前临时开现场？因为 **Exemplar（指标样例关联 Trace ID）** 需要在记录计时器的那一毫秒知道"当前是哪个 Trace"。装饰器保证了 delegate（如 micrometer-core 的 exemplar 支持）读到正确的现场——指标和追踪在最后一步握手。

## 4.7 getParentSpan 的三种来源：手工 Span、父 Observation、当前现场

【源码证据】`TracingObservationHandler.java:158-184`。嵌套 Observation 怎么组成 Span 树？这段代码按优先级找了三处：

```java
default @Nullable Span getParentSpan(Observation.ContextView context) {
    TracingContext tracingContext = context.get(TracingContext.class);      // ① 用户手工放进去的
    Span currentSpan = getTracer().currentSpan();
    if (tracingContext == null) {
        ObservationView observation = context.getParentObservation();       // ② 父 Observation 的
        if (observation != null) {
            tracingContext = observation.getContextView().get(TracingContext.class);
            if (tracingContext != null) {
                Span spanFromParentObservation = tracingContext.getSpan();
                if (spanFromParentObservation == null && currentSpan != null) return currentSpan;
                else if (currentSpan != null && !currentSpan.equals(spanFromParentObservation)) {
                    return currentSpan;                                     // ③ 用户在父 Observation 之后
                }                                                           //    手工 nextSpan 过 → 以手工为准
                return spanFromParentObservation;
            }
        }
    } else {
        return tracingContext.getSpan();
    }
    return null;
}
```

语义总结：**手工创建的 Span 永远优先于 Observation 树**——这保证了 `tracer.nextSpan()` 混用 Observation 时父子关系不乱；纯 Observation 嵌套时，子 Observation 的父 Span 就是父 Observation 的 Span。判断分支③的注释（`:172`："User manually created a span"）就是为此存在的。

## 4.8 本章小结

handler 包是整个项目的"心脏瓣膜"：Spring 生态的一切埋点以 Observation 形式到达，四个 handler 按 Context 类型（Sender/Receiver/其他）把它翻译成 Span——出口 inject、入口 extract、本地 nextSpan；`TracingContext` 用"Span + 每线程 Scope 表"的结构扛住嵌套与异步；`RevertingScope` 保证现场逐层精确恢复；`TracingAwareMeterObservationHandler` 在记指标的瞬间接通 Exemplar。**读懂本章，你就能回答"Spring Boot 里 Span 到底是谁创建的"——不是 Boot，不是 Web 框架，而是挂在你 ObservationRegistry 上的这几个 handler。**


---

# 五、跨线程传播：contextpropagation 包

## 5.1 白话：线程换了，现场不能丢

第四章的现场机制解决了"当前线程里查 Span"；但 `@Async`、线程池、Reactor、虚拟线程都会**换线程**。`io.micrometer.context` 库（context-propagation，核心模块的 api 依赖，见 1.4 节依赖表）定义了统一协议：每个"线程局部状态"实现一个 `ThreadLocalAccessor`（`key()` 标识 + `getValue()/setValue(v)/setValue()` 恢复默认），`ContextRegistry` 汇总所有 accessor，线程切换器（如 Reactor 的 `context-propagation` 集成、`ContextExecutorService`）负责快照与恢复。Micrometer Tracing 在 `contextpropagation` 包里提供**两个 accessor + 一个 Reactor 工具**。

## 5.2 ObservationAwareSpanThreadLocalAccessor：与 Observation 的"让位协议"

【源码证据】`contextpropagation/ObservationAwareSpanThreadLocalAccessor.java:59`（1.0.4 引入；1.1.0 加 registry 构造器 #240）。它把"当前 Span"注册为线程局部状态，key 是常量：

```java
public static final String KEY = "micrometer.tracing";   // :69
```

它的难点不在"存"，在**协调**：线程局部里可能同时存在两个"当前 Span"来源——`ObservationThreadLocalAccessor`（micrometer-observation 的，key 为 `observation`，存的是 Observation）和用户手工 `tracer.withSpan()` 的 Span。谁听谁的？Javadoc（`:33-49`）直接给出协议：

> - If `ObservationThreadLocalAccessor` created a `Span` via the `TracingObservationHandler` - do nothing
> - else - take care of the creation of a `Span` and putting it in thread local

落地在 `getValue()`（`:99-125`）：

```java
@Override
public @Nullable Span getValue() {
    Observation currentObservation = registry.getCurrentObservation();
    Span currentSpan = tracer.currentSpan();
    if (currentObservation != null) {                       // 现场里有一条 Observation
        TracingContext tracingContext = currentObservation.getContext()
            .getOrDefault(TracingObservationHandler.TracingContext.class, new TracingContext());
        if (currentSpan != null && !currentSpan.equals(tracingContext.getSpan())) {
            return currentSpan;                             // 手工 Span ≠ Observation 的 Span → 以手工为准
        }
        return null;                                        // OTLA 已经接管 → 让位（返回 null）
    }
    return currentSpan;                                     // 没有 Observation → 我说了算
}
```

**"让位协议"四句话**：有 Observation 且 Span 一致 → 我退场（null，恢复时也别动）；有 Observation 但 Span 是手工造的 → 我恢复手工 Span；没有 Observation → 正常恢复 Tracer 的当前 Span。`setValue()/setValue()`（`:131-178`）则用 `SpanAction` 记录层层 Scope，保证恢复顺序与捕获顺序互逆（`spanActions` 也是 `Map<Thread, ...>`，`ConcurrentHashMap`，`:64`）。

**注册顺序是硬约束**（`:51` 的 IMPORTANT 注释）：必须注册在 `ObservationThreadLocalAccessor` **之后**（`ContextRegistry.getInstance().registerThreadLocalAccessor(...)` 手动来），Boot 4.x 的自动装配会替你做（第九章 9.3）。

## 5.3 （留给 Baggage 的）ObservationAwareBaggageThreadLocalAccessor

【源码证据】`contextpropagation/ObservationAwareBaggageThreadLocalAccessor.java`（1.3.0 引入）。与 5.2 同一套让位协议，把 **Baggage** 也做成可跨线程恢复的线程局部状态——解决"异步任务里 `baggage.get()` 返回 null"的经典问题。它依赖 `BaggageManager` 序列化/恢复各 baggage 条目，细节与 5.2 对称，不赘述。

## 5.4 ReactorBaggage：Reactor 场景的行李搬运工

【源码证据】`contextpropagation/reactor/ReactorBaggage.java`（1.3.0 引入）。Reactor 的 `Context` 与线程局部是两个世界，`ReactorBaggage` 提供 `getCurrentBaggageMap()`/`read(RMono...)` 一类的组合子，把 Reactor Context 里的 baggage 读进算子内。**它只是糖**——真正的恢复仍靠 5.3 的 accessor + Reactor 的自动 context 传播。

## 5.5 本章小结

跨线程传播的答案分两层：协议层由 context-propagation 库的 `ThreadLocalAccessor` 提供；本项目贡献的是**两个"懂礼貌"的 accessor**——都遵守"Observation 优先、手工 Span 让位"的协议，保证 Observation 树与手工 Tracer 混用时现场不乱。你的应用里若出现"异步后 traceId 丢了"，先查这两件事：accessor 是否注册、注册顺序是否在 OTLA 之后。


---

# 六、Brave 桥：包装 Zipkin 世界

## 6.1 模块定位与依赖

24 个文件，几乎全部是"同名的包装类"：`BraveTracer` 包 `brave.Tracer`、`BraveSpan` 包 `brave.Span`、`BraveCurrentTraceContext` 包 `brave.propagation.CurrentTraceContext`……依赖上额外引入 `brave-context-slf4j`（Brave 的 MDC 联动）、`brave-instrumentation-http`（HTTP 客户端/服务端语义）、`brave-propagation-w3c`（W3C 格式），并显式 **exclude zipkin-reporter2/zipkin2**（`micrometer-tracing-bridges/micrometer-tracing-bridge-brave/build.gradle`）——**上报通道不归桥管**，Boot 的 zipkin 模块另行装配。

## 6.2 BraveTracer：一层薄壳

【源码证据】`micrometer-tracing-bridges/micrometer-tracing-bridge-brave/src/main/java/io/micrometer/tracing/brave/bridge/BraveTracer.java:31`：

```java
public class BraveTracer implements Tracer {
    private final brave.Tracer delegate;                  // :33 真正的 Brave
    ...
    public @Nullable Span nextSpan(@Nullable Span parent) {          // :64
        if (parent == null) return nextSpan();
        brave.propagation.TraceContext context = ((BraveTraceContext) parent).delegate;
        return new BraveSpan(this.tracer.nextSpan(TraceContextOrSamplingFlags.create(context)));
    }
    public SpanInScope withSpan(@Nullable Span span) {               // :76
        return new BraveSpanInScope(tracer.withSpanInScope(span == null ? null : ((BraveSpan) span).delegate));
    }
    public @Nullable Span currentSpan() {                            // :86
        brave.Span currentSpan = this.tracer.currentSpan();
        if (currentSpan == null) return null;
        return new BraveSpan(currentSpan);
    }
}
```

每个方法都是"类型转换 + 委托"两步：`BraveSpan` ←→ `brave.Span`、`BraveTraceContext` ←→ `brave.propagation.TraceContext`。桥里还提供反向转换静态方法（`BraveCurrentTraceContext.toBrave/fromBrave`，`BraveCurrentTraceContext.java:44-55`），供 Boot 装配时在两套类型间自由穿梭。

## 6.3 BraveCurrentTraceContext 与 Scope：线程局部的经典实现

【源码证据】`BraveCurrentTraceContext.java:26` 起：`newScope/maybeScope/wrap(Executor)` 全部一行委托给 Brave 的 `brave.propagation.CurrentTraceContext`（`:63-85`）。Brave 的现场实现（ThreadLocal + 装饰线程池）经过 Zipkin 十年打磨，Micrometer Tracing 不重写它，直接吃红利——**这就是 3.5 节说的"现场机制留给桥回答"**。

## 6.4 W3CPropagation：复用 brave-propagation-w3c

【源码证据】`W3CPropagation.java:54`：

```java
private static final List<String> FIELDS = Collections.unmodifiableList(Arrays.asList(TRACEPARENT, TRACESTATE));
```

`inject`（`:93`）把 Brave 上下文写成 `traceparent` 头（`TraceparentFormat.get().write(context)`），baggage 走 `tracestate`（`:109`）；`extract`（`:139-147`）反向解析。两个工程细节：① W3C 格式来自独立库 `brave-propagation-w3c`（1.4 节依赖表），桥只做适配；② 1.6.7/1.7.1 的安全补丁（commit `8c79d113`："Harden W3C baggage propagation against injection and DoS/OOM"）发生在 baggage 解析路径上——**任何把外部输入写进内部结构的代码都要当 SQL 注入的思路防**，追踪头也不例外。

Brave 侧还支持 B3/AWS 格式：`brave/propagation/PropagationType.java`（1.0.0 起）枚举 `AWS/B3/W3C/CUSTOM`，Boot 的 `management.tracing.brave.*` 会按配置装配。

## 6.5 采样器与 Baggage

- **采样**：`brave/sampler/ProbabilityBasedSampler.java`、`RateLimitingSampler.java`——两个 20 行左右的包装，把"概率/每秒限速"翻译成 Brave 的 `Sampler`。Boot 按 `management.tracing.sampling.probability` 装配（九.9.7）。
- **Baggage**：`BraveBaggageManager` 基于 Brave 的 `BaggageField` 实现 `BaggageManager`；`BraveBaggageFields` 桥接字段与 MDC。Brave 的 baggage 可变，所以 `BaggageInScope` 在这里是"空操作"（3.6 节说过的为严格实现设计）。

## 6.6 本章小结

Brave 桥的每一行代码几乎都是"转换 + 委托"：类型转换（Brave* ←→ brave.*）、现场委托（Brave ThreadLocal）、格式复用（brave-propagation-w3c）、采样包装。它存在唯一的意义是**让 Brave 用户零成本进入 Observation 世界**。如果你是 OTel 用户，下一章才是你的主场。


---

# 七、OTel 桥：对接 OpenTelemetry 生态

## 7.1 模块定位与依赖

27 个文件，依赖 OTel 的 **api + sdk 全家桶**（opentelemetry-api/sdk/sdk-trace/sdk-common/extension-trace-propagators/semconv，1.4 节依赖表）。与 Brave 桥最大的差异：**现场机制建立在 OTel 自己的 `Context` 体系上**，而不是裸 ThreadLocal——OTel 的 Context 存储可通过 SPI 替换（Reactor、协程、虚拟线程适配由 OTel 生态提供），桥自动受益。

## 7.2 OtelTracer 与 OtelCurrentTraceContext：建立在 OTel Context 之上

【源码证据】`micrometer-tracing-bridges/micrometer-tracing-bridge-otel/src/main/java/io/micrometer/tracing/otel/bridge/OtelCurrentTraceContext.java:47`：

```java
OtelTraceContext otelTraceContext = Context.current().get(OTEL_CONTEXT_KEY);   // 从 OTel Context 取当前 Span
```

`newScope`（`:70-101`）展示了 OTel 桥的独有心机：**开新现场时保留旧 Context 里的 Baggage**（`:85`：`Baggage currentBaggage = Baggage.fromContext(current)`，新 Context 合并旧 baggage 后 `makeCurrent()`）——因为 OTel 的 Baggage 挂在 Context 上而不是 Span 上，不搬运就会丢。

`OtelTracer`（`OtelTracer.java:32`）包装 `io.opentelemetry.api.trace.Tracer`，构造器还收一组 `EventListener`（`:49-63`）——事件广播体系的入口，见 7.4。

## 7.3 OtelSpanBuilder：start 时一次性落地

【源码证据】`OtelSpanBuilder.java:229-242`（`start()`）：

```java
SpanBuilder spanBuilder = this.tracer.spanBuilder(StringUtils.isNotEmpty(this.name) ? this.name : "");
if (this.parentTraceContext != null) spanBuilder.setParent(OtelTraceContext.toOtelContext(this.parentTraceContext));
else if (this.noParent)              spanBuilder.setNoParent();
spanBuilder.setAllAttributes(this.attributes.build());
spanBuilder.setSpanKind(this.spanKind);
if (this.startTimestamp != null) spanBuilder.setStartTimestamp(this.startTimestamp, this.startTimestampUnit);
this.links.forEach(e -> spanBuilder.addLink(e.getKey(), e.getValue()));
io.opentelemetry.api.trace.Span span = spanBuilder.startSpan();
```

OTel 的 Span **创建即启动**（`startSpan` 一步完成），所以 Micrometer `Span.Builder.start()` 里所有积攒的配置在此一次性落地——对比 Brave（先建后启），这是两个 SDK 时序模型的差异在桥里的体现。Kind/Link/parent 的语义映射一目了然。

## 7.4 EventPublishingContextWrapper 与 EventListener：Scope 事件的广播

Brave 的 MDC 联动是 `brave-context-slf4j` 现成的；OTel 没有等价物，桥自己造了一套**事件总线**：

- `EventListener.java:20-24`：一个方法接口 `void onEvent(Object event)`；
- `EventPublishingContextWrapper.java`：包装 `ContextStorage`，在 Scope attach/restore/close 时发布三种事件（`ScopeAttachedEvent`/`ScopeRestoredEvent`/`ScopeClosedEvent`）；
- `EventListener.java` 的两个内置消费者见 7.5。

## 7.5 Slf4JEventListener / Slf4JBaggageEventListener：MDC 的搬运工

【源码证据】`Slf4JEventListener.java:44-48`：

```java
private void onScopeAttached(EventPublishingContextWrapper.ScopeAttachedEvent event) {
    Span span = event.getSpan();
    if (span != null) {
        MDC.put(traceIdKey, span.getSpanContext().getTraceId());    // 默认 key：traceId / spanId（:26-27）
        MDC.put(spanIdKey, span.getSpanContext().getSpanId());
    }
}
```

Scope 关闭时 `MDC.remove` 清理（`:60-65`）。`Slf4JBaggageEventListener` 同理，把配置的 baggage 条目写进 MDC——日志里出现 `userId=42` 的那行格式，源头在这里。**日志关联这条线在 OTel 桥是"事件总线 + 两个监听器"，在 Boot 里由 `management.tracing.baggage.correlation.*` 打开**（九.9.6）。

## 7.6 BaggageTaggingSpanProcessor：Baggage 落到 Span 属性

Baggage 默认只在请求头里旅行，**不会出现在 Span 属性上**——后端查询时看不到。桥提供的解法是一个 OTel `SpanProcessor`：

【源码证据】`BaggageTaggingSpanProcessor.java:44-53`：

```java
@Override
public void onStart(Context context, ReadWriteSpan readWriteSpan) {
    Baggage baggage = Baggage.fromContext(context);
    baggage.forEach((key, baggageEntry) -> {
        AttributeKey<String> attributeKey = tagsToApply.get(key);   // 白名单（构造器传入 tagsToApply）
        if (attributeKey != null) {
            readWriteSpan.setAttribute(attributeKey, baggageEntry.getValue());
        }
    });
}
```

只在 Span 启动时拷贝一次、只拷白名单内的 key（Boot 里对应 `management.tracing.baggage.tag-fields`，九.9.5）——设计上刻意克制，防止每个 Span 被灌满高基数据。

## 7.7 导出管道：CompositeSpanExporter / SpanExporterCustomizer / ArrayListSpanProcessor

- `CompositeSpanExporter.java`：多个 OTel `SpanExporter` 聚合成一个（Boot 按 classpath 上有多少上报器装配多少）；
- `SpanExporterCustomizer.java`：给 Boot 的 `SdkTracerProviderBuilderCustomizer` 机制提供挂载点；
- `ArrayListSpanProcessor.java`：**测试专用** SpanProcessor，把导出的 Span 收进内存 List——`micrometer-tracing-tests` 与各桥的集成测试都用它当"假后端"；
- `OtelFinishedSpan.java`：把 OTel 的 `ReadableSpan` 快照成核心模块的 `exporter.FinishedSpan`（供 `SpanFilter`/`SpanReporter` 加工，九.9.1）。

## 7.8 本章小结

OTel 桥是两个 SDK 的翻译官：现场用 OTel `Context`（还替你搬行李）、Builder 在 `startSpan` 时一次落地、MDC 靠"事件总线 + 监听器"补齐、baggage 上 Span 靠白名单 SpanProcessor。它的存在让"Spring Boot 3+ 的应用"与"OTel 生态的 Collector/后端"之间只隔一个 starter。


---

# 八、注解体系（annotation 包）

## 8.1 白话：@NewSpan 一行注解一个 Span

不是所有团队都愿意在业务代码里写 `tracer.nextSpan()`。Sleuth 时代的声明式用法被原样搬了过来：方法上贴 `@NewSpan`，方法执行期间自动处于一个新 Span 内；参数上贴 `@SpanTag`，参数值自动变成 Span 的 tag。

## 8.2 三个注解与 @SpanName

【源码证据】`annotation/NewSpan.java`（`@Target(METHOD)`、`@Inherited`）：

```java
public @interface NewSpan {
    String name() default "";    // Span 名；缺省用方法名转连字符（DefaultSpanNamer 兜底）
    String value() default "";   // 同 name（别名，兼容老习惯）
}
```

`annotation/SpanTag.java`（`@Target(PARAMETER)`）三种取值方式，优先级从高到低：

```java
public @interface SpanTag {
    String value() default "";   // tag key
    String key() default "";     // 同 value（别名）
    String expression() default "";                          // ② SpEL（无 resolver 时）
    Class<? extends ValueResolver> resolver() default NoOpValueResolver.class;  // ① ValueResolver bean
}                                                            // ③ 都没有 → toString()
```

`annotation/ContinueSpan.java`：**继续**当前 Span（不新建），可附 `log` 值在方法进出时往 Span 里记 event。`@SpanName`（根包，`SpanName.java`）不配切面用：给自定义 `Runnable`/`Callable` 命名 Span，插桩框架会读取它（或退化用 `toString()`）。

## 8.3 SpanAspect：AspectJ 切面

【源码证据】`annotation/SpanAspect.java:37`：

```java
public class SpanAspect {
    private final MethodInvocationProcessor processor;
    ...
    public @Nullable Object continueSpanMethod(ProceedingJoinPoint pjp) throws Throwable {  // :46
        ContinueSpan continueSpan = method.getAnnotation(ContinueSpan.class);
        ...processor.process(method, continueSpan, pjp, args)
    }
    public @Nullable Object newSpanMethod(ProceedingJoinPoint pjp) throws Throwable {       // :53
        NewSpan newSpan = method.getAnnotation(NewSpan.class);
        ...
    }
}
```

注意这是 **AspectJ 注解风格**（`@Around` 由两个 pointcut 方法承担），依赖 `aspectjweaver`——核心模块把它列为 optional（1.4 节依赖表），Boot 里由 `@ConditionalOnClass(Advice.class)` 决定装配（九.9.3）。与 Sentinel 的"注解 + AOP 动态代理"（[Spring Cloud Alibaba Sentinel.md](Spring%20Cloud%20Alibaba%20Sentinel.md)）是同一招式。

## 8.4 MethodInvocationProcessor 与 SpanTagAnnotationHandler

- `ImperativeMethodInvocationProcessor.java`（阻塞版）：进方法 → 解析 `@NewSpan` 名字（`DefaultNewSpanParser`）→ `startScopedSpan` 或 `withSpan` → 执行 → finally 收尾；参数上的 `@SpanTag` 交给 `SpanTagAnnotationHandler` 打 tag。响应式版本由 Spring Cloud Sleuth 遗产 `ReactiveMethodInvocationProcessor` 等（不在本仓库主线）承担。
- `SpanTagAnnotationHandler.java`：按 8.2 的三级优先级解析 tag 值，SpEL 求值需要 Boot 提供 `ValueExpressionResolver`。

## 8.5 本章小结

注解体系是"老 Sleuth 用户体验的完整平移"：`@NewSpan`/`@ContinueSpan`/`@SpanTag` + AspectJ 切面 + 可插拔的名字/标签解析器。能力不大、代价不小（切面代理），适合少数关键业务方法的补充埋点——主战场永远是框架的 Observation 自动埋点。


---

# 九、导出与 Spring Boot 集成

## 9.1 exporter 包：FinishedSpan 三件套

Span 结束、导出前还有最后一次加工机会。核心模块定义了"成品 Span"模型和三个钩子（`exporter/` 包）：

```java
public interface FinishedSpan {                     // FinishedSpan.java（节选）
    Instant getStartTimestamp();                    // :59
    Instant getEndTimestamp();                      // :64
    Map<String, String> getTags();                  // :84（1.1.0 起另有 typed 版本）
    String getSpanId();  @Nullable String getParentId();  // :130/:135
    String getTraceId();                            // :171
    @Nullable String getRemoteServiceName();        // :193
    default List<Link> getLinks() { ... }           // :224（1.1.0+）
}
public interface SpanExportingPredicate { boolean isExportable(FinishedSpan span); }  // 导不导
public interface SpanFilter             { FinishedSpan map(FinishedSpan span); }      // 改一改
public interface SpanReporter extends AutoCloseable { void report(FinishedSpan span); } // 额外上报一份
```

三个钩子由桥实现适配：OTel 桥的 `OtelFinishedSpan` 把 SDK Span 快照成 FinishedSpan，桥内部的适配器在导出链上依次执行 predicate → filter → reporter（Boot 装配见 9.4）。`TestSpanReporter`（1.3.0+）配合 `micrometer-tracing-test` 模块的 `ArrayListSpanProcessor`，可在测试断言里直接拿到 Span 列表。典型用途：脱敏（抹掉 tag 里的手机号）、降噪（丢弃健康检查 Span——`SpanIgnoringSpanExportingPredicate` 是现成实现，按 span 名黑名单过滤）。

## 9.2 Spring Boot 3.x 的装配（actuate.autoconfigure.tracing）

Boot 3.0 起（v3.0.0 实证），追踪自动配置全部在 `spring-boot-actuator-autoconfigure` 的 `org.springframework.boot.actuate.autoconfigure.tracing` 包：`MicrometerTracingAutoConfiguration`、`BraveAutoConfiguration`、`OpenTelemetryAutoConfiguration`、`NoopTracerAutoConfiguration` 等，引入一个 starter 即可：

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
<!-- 或 micrometer-tracing-bridge-brave + zipkin-reporter-brave -->
```

## 9.3 Spring Boot 4.x 的装配：独立模块化

本文分析的 Boot 4.1 快照把追踪拆成独立模块（各自带 spring.factories/AutoConfiguration.imports）：

| 模块 | 内容 |
|---|---|
| `spring-boot-micrometer-tracing` | 核心：4 个 handler、注解切面、NoopTracer、日志关联、`TracingProperties` |
| `spring-boot-micrometer-tracing-brave` | Brave 桥 + `brave.Tracer` 等上游 Bean |
| `spring-boot-micrometer-tracing-opentelemetry` | OTel 桥 + `SdkTracerProvider`/`Sampler`/`ContextPropagators` 等上游 Bean |
| `spring-boot-micrometer-tracing-otlp` | OTLP 上报（gRPC/HTTP，`OtlpTracingAutoConfiguration`） |
| `spring-boot-micrometer-tracing-zipkin` | Zipkin 上报（经 brave-reporter） |
| `-prometheus` / 内部 exemplar 配置 | Exemplar 支持（`OtlpExemplarsAutoConfiguration`/`PrometheusExemplarsAutoConfiguration`） |

## 9.4 Observation → Span 的完整 Bean 图

【源码证据】`module/spring-boot-micrometer-tracing/src/main/java/org/springframework/boot/micrometer/tracing/autoconfigure/MicrometerTracingAutoConfiguration.java`（`@AutoConfiguration(after = ObservationAutoConfiguration.class)`，`@ConditionalOnBean(Tracer.class)`，`:57-58`）：

```java
public static final int RECEIVER_TRACING_OBSERVATION_HANDLER_ORDER = 1000;         // :70 先处理"收"
public static final int SENDER_TRACING_OBSERVATION_HANDLER_ORDER  = 2000;          // :76 再处理"发"
public static final int DEFAULT_TRACING_OBSERVATION_HANDLER_ORDER =
        Ordered.LOWEST_PRECEDENCE - 1000;                                          // :64 本地操作最后

@Bean @ConditionalOnMissingBean @Order(DEFAULT_...)
DefaultTracingObservationHandler defaultTracingObservationHandler(Tracer tracer) { ... }   // :88

@Bean @ConditionalOnMissingBean @ConditionalOnBean(Propagator.class) @Order(SENDER_...)
PropagatingSenderTracingObservationHandler<?> propagatingSenderTracingObservationHandler(Tracer tracer, Propagator propagator) { ... }   // :96

@Bean @ConditionalOnMissingBean @ConditionalOnBean(Propagator.class) @Order(RECEIVER_...)
PropagatingReceiverTracingObservationHandler<?> propagatingReceiverTracingObservationHandler(Tracer tracer, Propagator propagator) { ... } // :105
```

Order 的含义：**入口 handler 最先跑（Order 1000）**，保证收到请求的第一时间就建好 SERVER Span、解析父上下文，后续 handler（指标、其他）能看到完整现场；Sender handler 其次；Default（本地业务方法）最后。第四个 handler `TracingAwareMeterObservationHandler` 包在 Meter handler 外面，由 `TracingAndMeterObservationHandlerGroup`（`:79-83`，MeterRegistry 在 classpath 时）组装——**Boot 4.0 引入的 `ObservationHandlerGroup` 把"一组该一起装的处理顺序"固化了下来**。

注解切面的开关（`:110-143`）：`@ConditionalOnClass(Advice.class)`（有 aspectjweaver）+ `@ConditionalOnBooleanProperty("management.observations.annotations.enabled")`，依次装配 `DefaultNewSpanParser` → `SpanTagAnnotationHandler`（需 `ValueExpressionResolver`）→ `ImperativeMethodInvocationProcessor` → `SpanAspect`。

兜底（`NoopTracerAutoConfiguration.java`）：classpath 上有 API 没 Bridge 时注册 `Tracer.NOOP`——应用照常启动照常注入，什么都不记录。**这就是"引入 starter 但未配上报器"时的默认世界。**

上游 Bean 由桥模块的自动装配提供（`module/spring-boot-micrometer-tracing-opentelemetry/.../OpenTelemetryTracingAutoConfiguration.java`）：

```java
@Bean @ConditionalOnMissingBean SdkTracerProvider otelSdkTracerProvider(Resource, SpanProcessors, Sampler, ...)   // :105 组装 OTel SDK
@Bean @ConditionalOnMissingBean Sampler otelSampler() {   // :140 采样器按配置翻译
    return switch (openTelemetryTracingProperties.getSampler()) {
        case ALWAYS_ON -> Sampler.alwaysOn();
        case TRACE_ID_RATIO -> Sampler.traceIdRatioBased(tracingProperties.getSampling().getProbability());
        case PARENT_BASED_TRACE_ID_RATIO -> Sampler.parentBased(Sampler.traceIdRatioBased(...));
        ...
    };
}
@Bean @ConditionalOnMissingBean ContextPropagators otelContextPropagators(...)  // :133 多格式复合
```

再往下 `OtelTracer`、`OtelCurrentTraceContext`、`OtelPropagator` 等 Bean 组装成核心 API 的 `Tracer`/`Propagator`——**最底层是桥 → 最顶层是 handler，中间夹着 ObservationRegistry，链条一气呵成**。

## 9.5 management.tracing.* 配置大全

【源码证据】`TracingProperties.java:34`（`@ConfigurationProperties("management.tracing")`），全部经源码核对：

| 配置 | 默认值 | 源码位置 | 说明 |
|---|---|---|---|
| `management.tracing.enabled` | true | （`ConditionalOnEnabledTracingExport`） | 总开关；false → NoopTracer |
| `management.tracing.sampling.probability` | **0.10** | `TracingProperties.java:87` | 采样概率（Boot 默认只采 10%！全采设 1.0） |
| `management.tracing.baggage.enabled` | true | `:104` | baggage 总开关 |
| `management.tracing.baggage.remote-fields` | [] | `:116` | 进出请求头的 baggage 字段名（跨进程） |
| `management.tracing.baggage.local-fields` | [] | `:122` | 仅本地的 baggage 字段（OTel 桥不支持，装配时打 warn，见 9.4 上游 Bean 段） |
| `management.tracing.baggage.correlation.enabled` | true | `:174` | baggage ↔ MDC 关联 |
| `management.tracing.baggage.correlation.fields` | [] | `:180` | 哪些 baggage 进 MDC |
| `management.tracing.baggage.tag-fields` | [] | `:127` | 哪些 baggage 拷进 Span 属性（→ `BaggageTaggingSpanProcessor`，7.6） |
| `management.tracing.propagation.produce` | [W3C] | `:214` | 发出请求用什么格式 |
| `management.tracing.propagation.consume` | [全部] | `:219` | 收请求兼容哪些格式（W3C/B3/B3_MULTI） |
| `management.tracing.mdc.trace-id-key / span-id-key` | traceId / spanId | `:283/:288` | MDC 的 key 名（1.1.0+ 可改） |
| `management.tracing.exemplars.include` | SAMPLED_TRACES | `:326` | Exemplar 关联策略 |

> 实战提醒两条：**① Boot 默认只采样 10%**——测试环境链路"时有时无"多半是它；**② `produce/consume` 不对称是合法的**（发 W3C、同时兼容收 B3，迁移期标准姿势）。

## 9.6 日志关联：MDC 与 logging.pattern.correlation

链路 ID 进日志分两步：**第一步 MDC 有值**（OTel 桥的 `Slf4JEventListener` 写 traceId/spanId，7.5；baggage 经 `Slf4JBaggageEventListener`；Brave 桥由 `brave-context-slf4j` 原生完成）；**第二步日志 pattern 里显示它**。第二步由 Boot 代劳——

【源码证据】`module/spring-boot-micrometer-tracing/.../LogCorrelationEnvironmentPostProcessor.java:43-100`：只要 classpath 上有 `io.micrometer.tracing.Tracer`，Boot 就在环境准备阶段追加一个低优先级 PropertySource，提供两个属性：

```java
// ① 是否期待关联信息（跟随总开关 management.tracing.export.enabled，默认 true）
if (name.equals(LoggingSystem.EXPECT_CORRELATION_ID_PROPERTY)) return isExpectCorrelationId();
// ② 关联信息的输出格式：%correlationId{traceId(32),spanId(16)}
//    —— CorrelationIdFormatter 按定长取 MDC 值，两个都齐才输出 "[<traceId>-<spanId>] " 前缀
return "%%correlationId{%s(%d),%s(%d)}".formatted(traceIdKey, TRACE_ID_LENGTH, spanIdKey, SPAN_ID_LENGTH);
```

即 Boot 3.x 起的机制：日志系统（Logback/Log4j2）看到 `logging.include-correlation-id=true` 就会把 `logging.pattern.correlation` 追加进默认 pattern，每行日志自动出现 `[traceId,spanId]` 样式的前缀——**你什么都不用配**；`management.tracing.export.enabled=false` 或改 `management.tracing.mdc.*-key` 时它跟着变。宽高写死 32/16 与 `CorrelationIdFormatter.DEFAULT` 对齐（`LogCorrelationEnvironmentPostProcessor.java:61-66`）。

## 9.7 采样与传播配置的装配位置

- 采样：9.4 的 `otelSampler`（OTel）与 `BraveAutoConfiguration` 的 `Sampler` Bean（Brave 桥的 `ProbabilityBasedSampler` 包装，6.5）。
- 传播：Boot 按 `propagation.produce/consume` 组装 `Propagator`：Brave 侧是 `BravePropagationConfigurations` + `CompositePropagationFactory`（多格式复合，`module/spring-boot-micrometer-tracing-brave/.../BravePropagationConfigurations.java`）；OTel 侧是 `OpenTelemetryPropagationConfigurations` 的三个分支（`PropagationWithBaggage`/`PropagationWithoutBaggage`/`NoPropagation`，按 baggage 配置选择）+ `CompositeTextMapPropagator`。

## 9.8 本章小结

Boot 的角色是把 1~8 章的一切按 profile 拼起来：**桥给 Tracer/Propagator 的实现，核心模块给 4 个 handler，ObservationRegistry 收编全部，NoopTracer 兜底，配置属性定采样/传播/baggage 的行为**。Boot 3 → 4 只是模块搬家（actuator → 独立 starter 模块），Bean 图不变。记住三个高频实战开关：采样概率（默认 0.10）、`produce/consume` 传播格式、baggage 的三个列表（remote/tag/correlation）。


---

# 十、贯通视图：一次 HTTP 请求的追踪全链路

## 10.1 三条时间线叠加

场景：`gateway → order-service（本文主角）→ inventory-service`，order-service 用 Spring MVC + RestClient + OTel 桥 + OTLP 上报。

```
时间 ─────────────────────────────────────────────────────────────────────────────►

【时间线 A：线程与现场】            【时间线 B：Observation】         【时间线 C：Span（OTel SDK）】

tomcat 工作线程 T1
 ├─ 收到 HTTP 请求，头带 traceparent
 ├─ DispatcherServlet 建 ServerHttpObservation（ReceiverContext）
 │    └─ onStart 广播 → Receiver handler（Order 1000）
 │         ├─ propagator.extract(请求头)          ← 从头里读出父 traceId/spanId
 │         ├─ spanBuilder().kind(SERVER).start()  → SDK 生成 span S1（决定采样！）
 │         └─ TracingContext.setSpan(S1)
 ├─ onScopeOpened → maybeScope(S1.context)  ← OTel Context 入现场
 │    └─ EventPublishingContextWrapper 发 ScopeAttachedEvent
 │         └─ Slf4JEventListener：MDC[traceId,spanId] = S1  ← 此后日志全带链路 ID
 ├─ 业务方法执行（若 @NewSpan → SpanAspect 建子 Span）
 ├─ 调下游：RestClient 建 ClientHttpObservation（SenderContext）
 │    └─ onStart → Sender handler
 │         ├─ spanBuilder().kind(CLIENT).setParent(S1).start() → S2
 │         └─ propagator.inject(S2.context, 请求头)  ← traceparent 写进发出去的头
 ├─ inventory-service 收到（它的 Receiver handler 重复上面流程，父=S2）
 ├─ RestClient 观察 onStop → S2.end() → BatchSpanProcessor 排队 → OTLP → Collector
 └─ 响应返回，ServerHttpObservation onStop → S1.end()（含 http.status 等 tag）
      └─ 导出链：predicate → filter → reporter → OTLP
```

三条时间线读法：**A 是"谁在跑"（现场保证日志和子 Span 挂对地方）**；**B 是"发生了什么"（Spring 生态统一的埋点语言）**；**C 是"记下什么"（真正的追踪数据，由桥下的 SDK 产出）**。把三者对上的零件，本文每章都已拆过：Receiver/Sender handler（四.4.4/4.5）、TracingContext 的每线程 Scope（四.4.2）、OTel Context 与 MDC（七.7.2/7.5）、Exemplar 握手（四.4.6）。

## 10.2 与 Spring 生态组件的对应

| 组件 | 它创建的 Observation | 被 dispatch 到的 handler | 产生的 Span |
|---|---|---|---|
| Tomcat/Netty（Boot Web） | `http.server.requests`（ReceiverContext） | Receiver（1000） | SERVER，父 = 上游 CLIENT |
| RestClient/WebClient | `http.client.requests`（SenderContext） | Sender（2000） | CLIENT，inject 到请求头 |
| Kafka/Rabbit（spring-kafka 等） | 收/发两个 Observation | Receiver + Sender | CONSUMER/PRODUCER 对 |
| `@Async`/线程池 | — | —（靠 contextpropagation） | 子 Span 在工作线程续上 |
| @NewSpan 方法 | —（直接过 Tracer） | Default | 本地子 Span |
| Micrometer 计时器 | 各 Observation 的 onStop | TracingAwareMeter（Exemplar） | 指标样例 ↔ Trace 关联 |


---

# 十一、附录

## 11.1 关键接口速查表

| 接口/类 | 包 | 一句话职责 | 关键方法 |
|---|---|---|---|
| `Tracer` | 根包 | 门面（+BaggageManager） | nextSpan / withSpan / startScopedSpan / spanBuilder / currentSpan |
| `Span` | 根包 | 一段工作（显式 start/end） | start / end / abandon / error / tag / event / isNoop |
| `ScopedSpan` | 根包 | 自带现场的 Span | end（收工即结束） |
| `Span.Builder` | 根包 | 启动前配置 | setParent / kind / addLink / startTimestamp / start |
| `TraceContext` | 根包 | traceId/parentId/spanId/sampled | 四个取值方法 |
| `CurrentTraceContext` | 根包 | 现场管理 | context / newScope / maybeScope / wrap(Executor) |
| `Baggage(View/InScope/Manager)` | 根包 | 业务行李 | get / makeCurrent / createBaggage |
| `Propagator` | propagation | 跨进程头读写 | fields / inject / extract |
| `TracingObservationHandler` | handler | Observation→Span 骨架 | onStart/onStop 各由子类定，onScopeOpened 共用 |
| `DefaultTracingObservationHandler` | handler | 本地操作 | nextSpan + start/end |
| `PropagatingSenderTracingObservationHandler` | handler | 出口 | Builder 建子 Span + **inject** |
| `PropagatingReceiverTracingObservationHandler` | handler | 入口 | **extract** + Builder 建父-Span |
| `TracingAwareMeterObservationHandler` | handler | 指标↔Trace 握手 | onStop 前临时开现场 |
| `ObservationAwareSpanThreadLocalAccessor` | contextpropagation | 跨线程恢复 Span（key=micrometer.tracing） | getValue / setValue / setValue() |
| `SpanAspect` | annotation | @NewSpan/@ContinueSpan 切面 | 两个 @Around |
| `FinishedSpan` + 三钩子 | exporter | 导出前加工 | report / map / isExportable |
| `BraveTracer` / `OtelTracer` | 桥 | 实现 Tracer | 全部为"转换+委托" |

## 11.2 初学者学习路线

1. **先玩现象**：Boot 应用加 `micrometer-tracing-bridge-otel` + 一个 Collector（或先用 `management.tracing.sampling.probability=1.0` + 日志里的 traceId），亲眼看 `traceparent` 头和 `[traceId,spanId]` 日志。
2. **再读接口**（本文第三章）：拿着 `Tracer` 接口在 IDEA 里跳转到 `OtelTracer`/`BraveTracer` 实现，逐方法读"转换+委托"。
3. **后读接线**（本文第四章）：断点打在 `DefaultTracingObservationHandler#onStart` 和 `PropagatingReceiverTracingObservationHandler#onStart`，发一个请求看调用栈——你会看到 Spring MVC 的 Observation 一路走到这里。
4. **进阶**：阅读 `micrometer-tracing-tests` 与两个桥的测试类（如 `W3CPropagationTest`、`BaggageTests`），测试即用法文档；再对照 [Spring观测体系设计.md](Spring观测体系设计.md) 把单点知识拼成观测体系全景。

## 11.3 与系列其他文档的关系

- [Spring Framework.md](Spring%20Framework.md)：Framework 6/7 提供的 Observation 插桩（spring-web 的 `DefaultClientRequestObservationConvention` 等）是本文 handler 的"上游数据源"。
- [Spring Cloud CircuitBreaker.md](Spring%20Cloud%20CircuitBreaker.md) 3.4 节的 `ObservedCircuitBreaker`：Spring Cloud 组件用 Observation 装饰器接入同一体系。
- [Spring观测体系设计.md](Spring观测体系设计.md)：运维视角的选型与部署（Prometheus/Jaeger/Tempo），本文是其"应用内那一段"的源码展开。
