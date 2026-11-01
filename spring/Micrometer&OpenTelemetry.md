# Micrometer 与 OpenTelemetry 全景解析：关系、分工与开源组件地图

> **版本基线（截至 2026-10-31）**：Micrometer `1.17.0`（main 为 `1.18.0-SNAPSHOT`）、Micrometer Tracing `1.7.0`、OpenTelemetry Java API/SDK `1.67.0`（2026-10-09）、OpenTelemetry Java Instrumentation `2.33.0`（2026-10-23，Java Agent 3.0 正在预览）、`semantic-conventions-java 1.41.0`、`opentelemetry-java-contrib 1.57.0`、Collector `0.15x`。
>
> **证据口径**：本文是"架构级对比 + 组件地图"，不是逐行源码解析（逐行解析见同目录 [Micrometer.md](Micrometer.md) 与 [Micrometer Tracing.md](Micrometer%20Tracing.md)）。凡涉及实现细节的结论，均标注构建文件或官方文档出处；仅在生产环境验证过、但无公开文档支撑的，标为"经验值"。

---

## 一分钟回答

如果你只想要结论：

1. **它们不在同一层**。Micrometer 是"应用侧的埋点门面 + 计量实现"，OpenTelemetry（下称 OTel）是"跨语言的遥测标准 + SDK + 传输协议 + 汇聚管道"。前者像 SLF4J + Logback 的合体，后者像"日志标准 + 采集器 + 传输协议"的合体。
2. **指标上二者重叠，可以互相替代**：`MeterRegistry` ↔ `MeterProvider`。
3. **追踪上 Micrometer 不实现，只做门面**：`micrometer-tracing-bridge-otel` 把 OTel SDK 当引擎。
4. **日志上 Micrometer 基本不碰**：日志信号是 OTel Logs SDK + appender 的领地，Micrometer 只提供"日志事件计数"这类指标 binder。
5. **Spring Boot 3/4 的默认姿势是"混血"**：Micrometer Observation 负责埋点语义，Micrometer Tracing 负责追踪门面，OTel SDK 负责追踪实现，OTLP + Collector 负责传输与汇聚。

一句话记法：**Micrometer 管"怎么埋、怎么算"，OTel 管"叫什么、怎么传、怎么收"。**

---

## 目录

- [一、为什么会有两个东西：问题域与历史](#一为什么会有两个东西问题域与历史)
- [二、定位对比：门面 vs 标准](#二定位对比门面-vs-标准)
- [三、功能清单：各自到底实现了什么](#三功能清单各自到底实现了什么)
- [四、五种组合形态与数据流](#四五种组合形态与数据流)
- [五、决定踩坑的关键实现差异](#五决定踩坑的关键实现差异)
- [六、开源组件全景](#六开源组件全景)
- [七、选型决策与迁移路径](#七选型决策与迁移路径)
- [八、十个高频坑](#八十个高频坑)
- [九、速查表与结论](#九速查表与结论)
- [十、OpenTelemetry Java Agent 深度解析](#十opentelemetry-java-agent-深度解析)
- [附录 A：依赖坐标速查](#附录-a依赖坐标速查)
- [附录 B：参考链接](#附录-b参考链接)

---

# 一、为什么会有两个东西：问题域与历史

## 1.1 两条时间线

这两个项目诞生于不同的年代，解决的是不同的"当下之痛"。

| 时间 | Micrometer 侧 | OTel 侧 |
|---|---|---|
| 2017 | Micrometer 立项（Pivotal），目标是"监控界的 SLF4J" | OpenTracing / OpenCensus 各自为战 |
| 2018 | Micrometer 1.0 GA，绑定 Spring Boot 2.0 | — |
| 2019 | — | OpenTracing + OpenCensus 合并，OpenTelemetry 成立（CNCF） |
| 2021-03 | — | OTel Java 1.0（Trace 先稳定） |
| 2022-11 | Micrometer 1.10 引入 **Observation API**；Micrometer Tracing 1.0；Spring Boot 3.0 用其替换 Sleuth | — |
| 2023 | Micrometer 1.11 持续增强 OTLP registry（直方图/临时性配置） | OTel Metrics 稳定，Logs 进入 beta |
| 2024 | Micrometer 1.13 重写 Prometheus registry | OTel Logs 稳定，Profiles 仍在开发 |
| 2025 | Spring Boot 4.0，观测相关模块拆分为 `spring-boot-micrometer-*` | Java Agent 2.x 成熟，eBPF profiler 起步 |
| 2026 | Micrometer 1.17 / Tracing 1.7 | OTel Java SDK 1.67、Instrumentation 2.33、Agent 3.0 预览、声明式配置（declarative config）落地 |

**为什么 Micrometer 没有被 OTel 吃掉？** 三个现实原因：

1. **存量**。Micrometer 1.0 比 OTel Metrics 稳定早了四五年，Spring Boot 2.x 时代整个 JVM 生态的指标埋点都长在 `MeterRegistry` 上。
2. **Spring 的深度**。`@Timed`/`@Counted`、`MeterBinder`、`management.metrics.*` 那一整套自动配置和约定，OTel 至今没有等价物（OTel 的 Spring Starter 反而要绕开 Boot 的 Actuator 体系）。
3. **轻**。Micrometer 不需要 agent、不需要 Collector，一个 jar 就能把指标推到 Prometheus 或 Datadog；OTel 的完整价值要等你接受"agent + Collector + OTLP"这套基础设施之后才兑现。

**为什么新项目又绕不开 OTel？** 因为它解决的是 Micrometer 结构上不解决的问题：跨语言一致、跨信号关联、标准化的传输协议与汇聚层。

## 1.2 三个问题，两种解法

所有可观测性方案都在回答三个问题，两个项目各自只答对了一部分：

| 问题 | 具体内容 | Micrometer 的答案 | OTel 的答案 |
|---|---|---|---|
| **怎么埋** | 业务/框架代码如何产生遥测数据，且不绑死厂商 | `MeterRegistry` + `Observation` 门面；Spring 自动埋点 | API + 自动插桩 agent + 各语言 library instrumentation |
| **叫什么** | 指标名、单位、标签、Span 名的统一语义 | `NamingConvention` + 自家命名（`http.server.requests`） | Semantic Conventions（`http.server.request.duration`，单位秒） |
| **怎么传/怎么收** | 数据如何离开进程、如何加工、如何落到后端 | 22 种 registry 各自直连后端（或 OTLP） | OTLP 单一协议 + Collector 管道 + 任意后端 |

**关键洞察**：Micrometer 在第 1 题上做到了极致，第 2 题只有"厂商适配"级别的答案（同一份数据给 Prometheus 叫一种名，给 Datadog 叫另一种），第 3 题靠"多写 22 个 registry"暴力解决。OTel 反过来：第 2、3 题是它的立身之本，第 1 题在 JVM 上反而不如 Micrometer 贴 Spring。

这就是为什么 2022 年之后 Spring 的选择是"**用 Micrometer 答第 1 题，用 OTel 答第 2、3 题**"，而不是二选一。

---

# 二、定位对比：门面 vs 标准

## 2.1 三种关系并存

把二者简单描述为"竞争"或"替代"都是错的。它们同时存在三种关系：

```
┌──────────────────────────────────────────────────────────────────────┐
│ 关系一：指标层「竞争」                                                │
│   Micrometer MeterRegistry  ←── 同一层 ──→  OTel MeterProvider       │
│   两者都能算 Counter/Histogram，都能导出 OTLP/Prometheus              │
├──────────────────────────────────────────────────────────────────────┤
│ 关系二：追踪层「委托」                                                │
│   Micrometer Tracing（门面） ──bridge-otel──→ OTel SDK（唯一实现）    │
│   Micrometer 只有接口，采样/传播/上报全部由 OTel SDK 完成              │
├──────────────────────────────────────────────────────────────────────┤
│ 关系三：语义与管道层「互补」                                          │
│   Micrometer 产生数据 ──OTLP──→ OTel Collector ──→ 后端              │
│   Micrometer 用 OTel 的 semconv 命名（Boot 4.2 起可切换）             │
└──────────────────────────────────────────────────────────────────────┘
```

一句话：**在指标 API 上是竞品，在追踪实现上是上下游，在语义与传输上是标准与用户。**

## 2.2 分层图：谁站在哪一层

```
┌───────────────────────────── 你的业务代码 ─────────────────────────────┐
│  counter.increment()      observation.observe(() -> ...)              │
│  @Observed / @Timed       tracer.nextSpan()（少见）                    │
└───────────────┬───────────────────────────────────┬───────────────────┘
                │ 指标                               │ 追踪
┌───────────────▼───────────────────┐   ┌───────────▼───────────────────┐
│ Micrometer（门面 + 实现）          │   │ Micrometer Tracing（纯门面）   │
│  · MeterRegistry / Meter          │   │  · Tracer / Span / Propagator │
│  · Observation API（指标+追踪统一）│   │  · handler 包：Observation→Span│
│  · 聚合/直方图/百分位（自己算）     │   │  · 不实现采样/传播/上报        │
└───────┬───────────────────┬───────┘   └───────────┬───────────────────┘
        │                   │                       │
        │ 直连后端           │ OTLP                  ├── bridge-brave ─► Brave ─► Zipkin
        ▼                   ▼                       └── bridge-otel ──► OTel SDK
  Prometheus/Datadog   OtlpMeterRegistry                     │
  /NewRelic/...（22 种）  （自己聚合，                  TracerProvider / Sampler
                          复用 OTel 的                  SpanProcessor / Exporter
                          数据模型+导出器）                    │
        │                   │                                 │
        └───────────────────┴──────────────┬──────────────────┘
                                           ▼
                              OTLP（gRPC 4317 / HTTP 4318）
                                           ▼
                        ┌──────────────────────────────────┐
                        │ OpenTelemetry Collector（可选但推荐）│
                        │ 接收 → 处理（批/采样/脱敏）→ 导出   │
                        └──────────────┬───────────────────┘
                                       ▼
                    Jaeger / Tempo / Prometheus / Loki / 商业后端
```

这张图里最容易被忽略的一点：**指标和追踪在"出口"处走了两条完全不同的技术栈**。

- 指标：Micrometer 自己聚合、自己编码 OTLP，OTel 在这里只是"数据模型 + 导出器供应商"。
- 追踪：Micrometer 只有接口，OTel SDK 全权负责采样、处理、导出。

这一不对称是理解全文的钥匙，第五章会给出构建文件级证据。

## 2.3 概念映射表

两套词汇看似南辕北辙，其实大多能一一对上：

| 概念 | Micrometer | OpenTelemetry | 说明 |
|---|---|---|---|
| 仪表容器 | `MeterRegistry` | `MeterProvider` → `Meter` | Micrometer 的 registry 同时是容器和出口，OTel 把容器（Provider）和出口（Reader/Exporter）分开 |
| 仪表 | `Counter` / `Gauge` / `Timer` / `DistributionSummary` / `LongTaskTimer` | `LongCounter` / `Gauge` / `Histogram` / `UpDownCounter` | Micrometer 的 `Timer` 是"带单位语义的 Histogram"，OTel 无 Timer 类型 |
| 维度 | `Tag` / `KeyValue` | `Attribute` / `KeyValue` | 语义一致 |
| 时间序列身份 | `Meter.Id` = name + tags + type | Instrument + attribute set | OTel 多了 Instrumentation Scope |
| 资源 | `commonTags` / `resourceAttributes` | `Resource` | OTel 的 Resource 是标准化的（`service.name` 等） |
| 聚合节奏 | `StepMeterRegistry`（推）/ pull registry | `MetricReader` + `PeriodicExportingMetricReader` | 同为"周期聚合"，但 Micrometer 的 step 是"整点对齐窗口" |
| 临时性 | 隐含在 registry 类型里（step/pull） | `AggregationTemporality`（cumulative / delta） | Micrometer 的 OTLP registry 暴露了该配置 |
| 观测 | `Observation` | 无直接等价物（Span + Metric 分别做） | **这是 Micrometer 的独有能力** |
| 追踪 | `micrometer-tracing` 的 `Span`/`Tracer` | OTel `Span`/`Tracer` | Micrometer Tracing 的 API 借自 Brave + OTel |
| 传播 | `Propagator`（inject/extract） | `TextMapPropagator` | W3C `traceparent` 是共同语言 |
| 行李 | `Baggage` | `Baggage` | 同名同义 |
| 示例样本 | `Exemplar`（OTLP/Prometheus 支持） | `Exemplar`（SDK 内建 reservoir） | 都用于"指标 → 链路"跳转 |
| 命名规范 | `NamingConvention` | Semantic Conventions | 前者是"适配器"，后者是"标准" |

**最值得记住的一行**：OTel 没有 Observation。OTel 里你要分别调 `tracer.spanBuilder()` 和 `meter.counterBuilder()`；Micrometer 的 Observation 让你写一次、自动产出指标 + Span + 日志上下文。这正是 Spring Boot 3/4 选择它的原因。

## 2.4 同一件事，两种写法

把"记录一次下单"翻译成两套 API，差异立刻可见。

**Micrometer（Observation 一次埋点，指标 + Span 同时产出）：**

```java
// 纯指标
Counter orders = Counter.builder("orders.created")
        .tag("channel", "web")
        .register(registry);
orders.increment();

// 观测：一个 observe() 同时驱动 Timer 与 Span
Observation.createNotStarted("order.create", observationRegistry)
        .lowCardinalityKeyValue(KeyValue.of("channel", "web"))
        .observe(() -> createOrder(command));
```

**OpenTelemetry（指标与追踪是两套独立 API，异常与收尾要自己管）：**

```java
// 指标
LongCounter orders = meter.counterBuilder("orders.created")
        .setUnit("{order}")
        .build();
orders.add(1, Attributes.of(AttributeKey.stringKey("channel"), "web"));

// 追踪：try/catch/finally 一个都不能少
Span span = tracer.spanBuilder("order.create").startSpan();
try (Scope ignored = span.makeCurrent()) {
    createOrder(command);
} catch (RuntimeException e) {
    span.recordException(e);
    span.setStatus(StatusCode.ERROR);
    throw e;
} finally {
    span.end();
}
```

**读法**：Micrometer 的 3 行 vs OTel 的 10 行，差的不是语法糖，而是"生命周期由谁负责"。Observation 把 `start/stop/error/scope` 封进了统一生命周期，指标和追踪只是它的两个消费者；OTel 则要求你把每个信号的生命周期各自管好。这也是为什么 Spring 团队宁愿在 OTel 之上再加一层 Observation，而不是直接把 OTel API 暴露给业务代码。

顺带一提：MicroProfile Telemetry、Quarkus 走的是另一条路——直接用 OTel API 承担埋点。所以"用不用 Observation"本质上取决于你是否接受"以 Spring 为中心"的埋点语义。

---

# 三、功能清单：各自到底实现了什么

## 3.1 Micrometer：六大能力域

Micrometer 不是一个"监控 SDK"，而是六个能力的组合体。分清楚这六块，才不会把 `micrometer-core` 和 `micrometer-tracing` 混为一谈。

### （1）计量 API 与内存模型

- 七种仪表：`Counter`、`Gauge`、`Timer`、`DistributionSummary`、`LongTaskTimer`、`FunctionCounter`、`FunctionTimer`、`MultiGauge`。
- 身份 = 名字 + 标签集 + 类型（`Meter.Id`），维度优先，不是层级命名。
- 全部仪表住在 `MeterRegistry` 里，是**进程内状态**；`SimpleMeterRegistry` 就是一个 `ConcurrentHashMap`。

### （2）分布统计内核（真正难的部分）

- 客户端百分位（`publishPercentiles`，基于 HdrHistogram 或 `TimeWindow` 环形缓冲）。
- 百分位直方图（`publishPercentileHistogram`），把桶暴露给后端做聚合百分位。
- SLO 桶（`serviceLevelObjectives`），业务自定义边界。
- 时间窗轮换、步进差值（`StepValue`）、`TimeWindowMax` 等——这些是 `micrometer-core` 里最复杂的包。

### （3）注册表路由与治理

- `CompositeMeterRegistry`：一次埋点，多后端分发。
- `MeterFilter`：deny / rename / 加公共标签 / 上限熔断（`maximumAllowableMetrics`、`maximumAllowableTags`）。
- `MeterBinder`：把第三方库的状态翻译成仪表（JVM、Logback、Tomcat、Jetty、HikariCP、Kafka、Netty……）。
- `NamingConvention`：把 `http.server.requests` 翻译成 `http_server_requests_seconds`（Prometheus 风格）或 Datadog 风格。

### （4）后端投递（22 种 registry）

两类模型：

- **pull**：Prometheus（HTTP 端点被抓）、`prometheus-simpleclient`（旧版）。
- **push**：Datadog、Influx、Elastic、New Relic、CloudWatch2、Dynatrace、Azure Monitor、AppOptics、SignalFx、Stackdriver、Humio、Kairos、OpenTSDB、Ganglia、Graphite、JMX、Atlas、StatsD、OTLP、Health。

`PushMeterRegistry` 统一了"周期推送 + 失败重试 + 批量"的骨架，各后端只需实现"怎么把一批 Meter 编码成 JSON/Protobuf"。

### （5）Observation API（1.10 起，Micrometer 的护城河）

一次 `Observation.start()/observe()`，通过 `ObservationHandler` 链同时产出：

- 指标（`MeterObservationHandler` → Timer/Counter）
- 追踪（Micrometer Tracing 的 handler → Span）
- 日志上下文（`Observation.Context` 可携带 MDC 字段）
- 自定义事件（`observation.event(EventData)`）

配套四件套扩展点：`ObservationPredicate`（要不要观测）、`ObservationFilter`（改上下文）、`ObservationConvention`（改命名）、`ObservationHandler`（消费生命周期）。`@Observed`、`@Counted`、`@Timed` 是它的注解外壳。

### （6）测试支撑

`micrometer-test` 提供 `MeterRegistryAssert`、`TimerAssert` 等 AssertJ 断言；`micrometer-observation-test` 提供 `TestObservationRegistry` 与 `ObservationRegistryAssert`。这是很多团队选择 Micrometer 的隐性理由——OTel 的 `opentelemetry-sdk-testing` 只覆盖 Span/Log 数据断言，没有"指标断言 DSL"。

### Micrometer 不做的事

- 不做追踪算法（采样、ID 生成、上报）——交给 Brave / OTel。
- 不做日志信号采集——日志仍是 SLF4J 的领地，Micrometer 只有 `LogbackMetrics`（统计日志条数）。
- 不做跨语言、不做传输协议标准化、不做 Collector。
- 不做自动字节码插桩（没有官方 javaagent）。

## 3.2 OpenTelemetry：六大能力域

OTel 是一个"规范 + 参考实现 + 工具链 + 管道"的复合体。把它当成一个 SDK 会低估它，当成一个标准又会高估它的落地程度。

### （1）规范（Specification）

定义 API 语义、SDK 行为、数据模型（Metrics 的 Sum/Gauge/Histogram/ExponentialHistogram/Summary）、Context 与 Propagators、Resource、OTLP 线格式。**规范是跨语言一致性的根源**：Java 和 Go 的 `http.server.request.duration` 必须是同一个含义、同一个单位。

### （2）API（每个语言一套）

- Trace：`Tracer`、`Span`、`SpanBuilder`、`StatusCode`、`Link`。
- Metrics：`Meter`、`LongCounter`、`DoubleHistogram`、`ObservableGauge`、`UpDownCounter`。
- Logs：`Logger`、`LogRecord`（Logs Bridge API，供 appender 桥接）。
- Context：`Context`、`ContextKey`、`Scope`（可跨线程/跨回调传播）。
- Baggage、Propagator（`TextMapPropagator`：W3C tracecontext、baggage、B3、Jaeger、X-Ray）。

### （3）SDK（参考实现）

- `SdkTracerProvider`：`Sampler`（always_on/always_off/traceidratio/parentbased/jaeger_remote）+ `SpanProcessor`（`BatchSpanProcessor` / `SimpleSpanProcessor`）+ `SpanExporter`。
- `SdkMeterProvider`：`MetricReader`（`PeriodicExportingMetricReader` / `PrometheusMetricReader`）+ `View`（改名、改聚合、改基数上限）+ `Aggregation`（explicit bucket / exponential / last value / sum）。
- `SdkLoggerProvider`：`LogRecordProcessor` + `LogRecordExporter`。
- `Resource`：`service.name`、`service.version`、`telemetry.sdk.*`、`host.*`、`k8s.*`、`container.*`。
- `sdk-extension-autoconfigure`：用环境变量/系统属性/声明式 YAML 装配整套 SDK（Java Agent 与 Spring Starter 都靠它）。

### （4）协议与导出器

- **OTLP**：gRPC（4317）/ HTTP+protobuf（4318），Trace/Metrics/Logs/Profiles 共用一套 protobuf 定义（`opentelemetry-proto`）。
- 官方导出器：OTLP、Prometheus、Logging（stdout）、Zipkin（逐步退役）、Jaeger（已移除，改走 OTLP）。
- 采样扩展：Jaeger Remote Sampler、`consistent-sampling`（contrib）。

### （5）自动插桩（Java Agent）与库级插桩

- `-javaagent:opentelemetry-javaagent.jar`：基于 ByteBuddy 的零代码插桩，覆盖 Spring Web MVC/WebFlux、JDBC、Kafka、RabbitMQ、Netty、gRPC、Servlet、Logback/Log4j2 等 100+ 库。
- Library instrumentation：不用 agent，直接引 `opentelemetry-spring-webmvc-6.0` 这类库，在代码里显式装配（对 GraalVM native、对可控性要求高的场景更友好）。
- 关键点：**agent 自带一份 SDK**。这是它与"引 OTel SDK 依赖"互斥的根因（见第八章）。
- 展开版见**第十章**：jar 结构、四层类加载、API 桥、muzzle、配置体系、与 Boot 4 的共存方式、3.0 变更。

### （6）Collector 与运维面

- 接收（OTLP/Jaeger/Prometheus/Filelog/Kafka）→ 处理（batch、memory_limiter、attributes、filter、transform、tail_sampling）→ 导出（OTLP/OTLPHTTP/Prometheus/Loki/Kafka/debug）。
- 连接器（connector）：`spanmetrics`（Span → 指标）、`servicegraph`（服务拓扑）、`routing`、`count`。
- 扩展（extension）：`health_check`、`zpages`、`pprof`、`oauth2client`。
- 部署形态：DaemonSet（节点级 agent）/ Gateway（集群级汇聚）/ Sidecar。
- 周边：Kubernetes Operator、Helm Chart、`ocb`（OpenTelemetry Collector Builder，自定义发行版）。

## 3.3 能力对照矩阵

| 能力 | Micrometer | OpenTelemetry | 备注 |
|---|---|---|---|
| 指标 API | ✅ 成熟（2018 起） | ✅ 稳定（2023 起） | 重叠区，可二选一 |
| 指标聚合 | ✅ 自带，客户端百分位/SLO 更强 | ✅ View/Aggregation，指数直方图更标准 | Micrometer 的百分位配置更"业务友好" |
| 指标导出后端 | ✅ 22 种直连 | ⚠️ 主要走 OTLP / Prometheus | Micrometer 胜在"不开 Collector 也能上商业 APM" |
| 分布式追踪 | ⚠️ 仅门面（委托 Brave/OTel） | ✅ 完整（API+SDK+导出） | 无 OTel/Brave 则 Micrometer Tracing 无用 |
| 日志信号 | ❌ 只做日志指标 | ✅ Logs SDK + appender bridge | OTel 独有 |
| Profiling | ❌ | ⚠️ 开发中（eBPF profiler） | 尚不建议生产依赖 |
| 上下文传播 | ✅（`context-propagation`，适配 Reactor/虚拟线程） | ✅（`Context` + agent 的 ThreadLocal 管理） | 两者可共存，需对齐 |
| 语义约定 | ⚠️ 自家命名 + 适配器 | ✅ 跨语言标准 | Boot 4.2 起可切到 OTel 约定 |
| 自动插桩 | ❌ | ✅ Java Agent | 零代码改造的唯一路径 |
| 汇聚/加工管道 | ❌ | ✅ Collector | 尾采样、脱敏、fan-out 只能在这里做 |
| 统一埋点（一次埋点多信号） | ✅ Observation API | ❌（需分别调 Tracer/Meter） | **Micrometer 的独有能力** |
| Spring Boot 集成 | ✅ 一等公民（自动配置/注解/Actuator） | ⚠️ 需绕开或替换 Actuator 体系 | Boot 4 拆模块后更明显 |
| 测试断言 | ✅ 指标/观测均有 DSL | ⚠️ 主要针对 Span/Log | — |
| 跨语言 | ❌ JVM only | ✅ 12+ 语言 | — |

## 3.4 各自不可替代的地方

**Micrometer 不可替代**：

1. `Observation` 的"一次埋点、多信号产出"。
2. Spring Boot Actuator 的整套 `management.metrics.*` / `management.observations.*` 契约。
3. 22 种 registry 的"直连商业后端"能力（Datadog/NewRelic/Dynatrace 的团队往往不想为此部署 Collector）。
4. 客户端百分位与 SLO 桶的细粒度配置（`management.metrics.distribution.slo.*`）。

**OTel 不可替代**：

1. 跨语言统一（一个 Go 网关 + 一个 Java 服务 + 一个 Python 模型服务，只有 OTel 能给出同一套指标名）。
2. Java Agent 的零代码插桩。
3. Collector 的尾采样（tail_sampling）、脱敏、扇出、`spanmetrics` 派生。
4. OTLP 作为唯一传输协议带来的后端可替换性。
5. Logs 与 Profiles 信号。

---

# 四、五种组合形态与数据流

工程上你会遇到五种装配方式（D 含 agent 与 starter 两个变体）。它们的依赖、数据流、适用场景完全不同，混用是事故之源。

## 4.0 先统一四个问题的口径

每种形态都按同样四个问题拆，避免"只看依赖、不看线程和边界"：

| 问题 | 具体看什么 |
|---|---|
| 引哪些 jar | 直接依赖 + **它替你带进来的传递依赖**（尤其"SDK 到底是谁带进来的"） |
| 怎么起作用 | 谁创建 registry/tracer、何时聚合、在哪个线程编码与发送 |
| 进程边界 | JVM 内（堆、线程、类加载器）与 JVM 外（socket）的分界线在哪 |
| 与外部配合 | 谁发起连接、什么协议、对端是谁、对端挂了应用会怎样 |

跨进程其实只有四种载荷。认清这四样，五种形态的差异就一目了然：

| 载荷 | 格式 | 默认端口 | 谁发起 | 典型对端 |
|---|---|---|---|---|
| 指标（pull） | Prometheus 文本 / OpenMetrics | 应用自身的 HTTP 端口（8080） | 监控端来抓 | Prometheus / VM Agent |
| 指标（push） | OTLP/protobuf over HTTP | 4318 | 应用主动推 | Collector / 厂商端点 |
| 追踪 | OTLP/protobuf（HTTP 4318 或 gRPC 4317） | 4318 / 4317 | 应用主动推 | Collector |
| 链路上下文 | `traceparent` / `tracestate` 请求头 | 复用业务端口 | **调用方**带上 | 下游服务 |

> 记住一句话：**pull 形态没有出站连接，push 形态一定有后台线程**。这决定了"后端挂了会不会影响应用"。

如果只想快速判断"某个形态到底行不行"，问这四个问题比看依赖更快：

| # | 问题 | 为什么能一刀切开 |
|---|---|---|
| 1 | 指标/Trace 的埋点 API、注解是谁？ | 决定业务代码写什么、换实现要不要改代码 |
| 2 | 埋点数据、Trace 数据是谁在真正处理？ | 决定采样、基数治理、聚合发生在哪一侧 |
| 3 | 发往外部用 pull 还是 push？ | 决定有没有出站连接、有没有后台线程 |
| 4 | 真正提供这个 pull/push 能力的是谁？ | 决定"后端挂了谁扛"、故障该找谁 |

这四问抓得住主干，但还缺三层，否则排障时会卡住：**谁聚合与缓冲**（写入即聚合 / step 缓冲 / BSP 队列，三种失败语义完全不同）、**谁定义语义与编码**（命名、单位、temporality 在这一层定）、**失败时谁兜底**（丢弃 / 重试 / 磁盘缓冲）。补上这三问，就是 4.9 的七问法。

## 4.1 形态 A：纯 Micrometer（指标 only，pull 模型）

### 依赖

```groovy
implementation 'org.springframework.boot:spring-boot-starter-actuator'   // 端点 + Observation 自动配置
implementation 'io.micrometer:micrometer-registry-prometheus'           // 1.17 起基于 prometheus-metrics-core
```

`micrometer-registry-prometheus` 1.17 的构建文件（实测）声明了这些传递依赖，它们各自有明确分工：

| 传递依赖 | 作用 | 少了会怎样 |
|---|---|---|
| `micrometer-core` | `Meter`/`MeterRegistry` 实现 | 什么都做不了 |
| `micrometer-observation` | Observation API，Boot 的观测自动配置依赖它 | `@Observed` 与 `Observation` API 不可用 |
| `io.prometheus:prometheus-metrics-core` | Prometheus Java client 1.x，`PrometheusMeterRegistry` 建在它上面 | 无法注册/暴露 |
| `prometheus-metrics-exposition-formats` | 文本与 OpenMetrics 序列化 | `/actuator/prometheus` 出不了内容 |
| `prometheus-metrics-tracer-common` | 只取 `SpanContext`，给 exemplar 用 | exemplar 里的 traceId 为空 |

若存量项目与 Prometheus client 1.x 冲突，可退回 `io.micrometer:micrometer-registry-prometheus-simpleclient`（基于 `simpleclient_common`）过渡——这是官方构建文件里写明的兼容路径。

### 怎么起作用

1. Boot 的 `PrometheusMetricsExportAutoConfiguration` 在 classpath 有 `PrometheusMeterRegistry`、且 `management.prometheus.metrics.export.enabled=true`（默认）时创建 registry。
2. 业务代码写入时**当场聚合**：`Counter` 累加、`Timer` 记 count/total/max。**没有后台线程，也没有网络客户端**。
3. 请求 `/actuator/prometheus` 时，`PrometheusScrapeEndpoint`（在 `spring-boot-actuator` 里）调 `registry.scrape()`，把堆里的时间序列一次性序列化。
4. 按 `Accept` 协商格式：`text/plain;version=0.0.4` 或 `application/openmetrics-text;version=1.0.0`（后者才带 exemplar）。

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus   # 千万别写 *
```

### 进程边界

```
[JVM 内]  业务代码 → MeterRegistry（堆内时间序列，无导出线程）
                          │ scrape() 序列化
[边界]    HTTP GET /actuator/prometheus  ◄── Prometheus（pull，默认 15s）
                                                  │
[外部]                                            ▼
                                            Grafana / Alertmanager
```

| 边界内 | 边界外 |
|---|---|
| `MeterRegistry` 持有全部时间序列（占的是**应用堆**） | Prometheus 负责存储、查询、告警规则 |
| Tomcat/Netty 线程处理 scrape 请求 | Grafana 只读 Prometheus，不碰应用 |
| 无出站连接、无后台线程 | 服务发现（K8s SD / Consul）决定抓谁 |

### 与外部怎么配合、怎么坏

- Prometheus 按 `scrape_interval`（常见 15s）拉取，超时由对端 `scrape_timeout` 控制；应用侧只感知为"多了一个 HTTP 请求"。
- 应用**不需要知道 Prometheus 的地址**，因此扩缩容、灰度都不影响采集；反过来，NAT/防火墙必须允许**入站**。
- 失败模式：

| 场景 | 表现 | 影响面 |
|---|---|---|
| Prometheus 挂了 | 应用无感，只是数据缺口 | 无 |
| 标签基数爆炸 | 时间序列堆积在**应用堆**里 | 应用 OOM |
| registry 很大 | 单次 scrape 序列化耗时上升，占一个请求线程 | 抓取超时、偶发慢请求 |
| 端点裸奔 | 内部指标外泄 | 安全问题，需鉴权/内网 |

### 三个常见理解的校正

"门面是 Micrometer、实现是 `PrometheusMeterRegistry`、只能出指标"——三句话里两句半对。逐句拆：

| 说法 | 判定 | 精确表述 |
|---|---|---|
| 门面是 Micrometer | **半对** | Micrometer 不是纯门面（facade），而是"抽象基类 + 模板方法 + SPI"。`MeterRegistry` 是**有状态的抽象类**：它自己持有 meter 缓存、`NamingConvention`、`MeterFilter` 链、`Clock`，子类只实现 `newCounter`/`newGauge`/`newTimer` 这些工厂方法。类比 SLF4J 只在"面向接口、可换实现"这一层成立；关键差别是 SLF4J 运行期只能绑一个后端，Micrometer 允许多个 registry 并存（`CompositeMeterRegistry` 多播）。 |
| 实现是 `PrometheusMeterRegistry` | **对，但不完整** | 它只是 22 个 registry 实现之一，而且它下面还压着一层：`io.prometheus:prometheus-metrics-core` 的 `PrometheusRegistry` + 内部 `MicrometerCollector`。所以是**三层**：Micrometer API（`Meter` 语义）→ `PrometheusMeterRegistry`（语义映射）→ Prometheus client（数据模型与文本编码）。 |
| 只能实现指标功能 | **对"这套装配"，不对"Micrometer"** | 见下方两条。 |

**为什么形态 A 只出指标**：这套依赖里没有任何 tracing 实现。`Observation` 是"一次埋点、多路分发"，而分发目标由 classpath 决定——只有 `MeterHandler` 时，`Observation` 只记指标、不建 span。要出 span 必须再加 `micrometer-tracing-bridge-otel`（或 `-brave`），那是形态 C。

**但 Prometheus 这条通道永远收不了 span**：它只是 HTTP 拉取的文本/OpenMetrics，没有 trace 语义。即使补上 tracing 依赖，span 也必须另开一条 OTLP（4318/4317）或 Jaeger/Zipkin 出口。"指标走 Prometheus、追踪走 OTLP"是两条独立连接，不存在"复用"。

**"Micrometer 只能做指标"是错的**：`micrometer-observation` 提供 Observation API，追踪门面在独立仓库 `micrometer-tracing`，加上 bridge 就能出 span。准确说法是"**形态 A 这一组装配只产出指标信号**"，而不是"Micrometer 只有指标能力"。

两个类型层面的证据，记住就能自己判断：

- 1.17 的 `PrometheusMeterRegistry` 直接 `extends MeterRegistry`，**不是** `PushMeterRegistry`——从类型上就决定了它没有 push 调度线程。对照：`SimpleMeterRegistry`（纯内存）、`CompositeMeterRegistry`（多播）、`OtlpMeterRegistry`（继承 `PushMeterRegistry`，自带定时线程）。
- 它依赖 `prometheus-metrics-tracer-common`，只为拿一个 `SpanContext` 接口。这说明形态 A 与追踪**唯一的交集是 exemplar**：直方图上可以带 `trace_id`/`span_id`，但前提是 classpath 里真有 tracing 实现，且抓取时用 OpenMetrics 格式（`Accept: application/openmetrics-text`）才看得见。没有 tracing 依赖时 exemplar 为空。

两个容易忽略的实现细节：

1. **pull ≠ 绝对没有后台线程**。若加了 `prometheus-metrics-exporter-pushgateway` 并设 `management.prometheus.metrics.export.pushgateway.enabled=true`，Boot 会建 `PrometheusPushGatewayManager` 定时推送到 Pushgateway——此时就有了出站连接，本章"后端挂了不影响应用"的结论失效。它只该用于批处理/短生命周期任务。
2. **`Gauge` 与 `FunctionCounter`/`FunctionTimer` 是在 scrape 那一刻求值的**，不是写入时聚合。supplier 慢会直接拖慢抓取，抛异常会让该指标缺失（Micrometer 记 warn 后跳过，不至于整个端点 500）。埋点里别放远程调用。

## 4.2 形态 B：Micrometer + OTLP registry（指标走 OTLP，但没有 OTel SDK）

### 依赖

```groovy
implementation 'org.springframework.boot:spring-boot-starter-actuator'
implementation 'io.micrometer:micrometer-registry-otlp'
```

这个 jar 会把 `opentelemetry-sdk-metrics`（数据模型）、`opentelemetry-exporter-otlp`（编码/发送）、`opentelemetry-proto` 打进来，但**全部 relocate 到 `io.micrometer.registry.otlp.shaded.*`**。后果有两个：

- 好处：与你自己的 OTel SDK 不冲突，两套类各用各的。
- 代价：你拿不到它的 SDK 实例，也**不能**用 OTel 的 Sampler/Processor/View 去干预它；`Resource` 是 Micrometer 自己拼的（源码 `Resource.newBuilder().addAllAttributes(getResourceAttributes())`），`telemetry.sdk.name` 写着 `io.micrometer`。

```yaml
management:
  otlp:
    metrics:
      export:
        url: http://collector:4318/v1/metrics
        step: 30s
        aggregation-temporality: delta
        resource-attributes:
          service.name: order-service
```

### 怎么起作用

- `OtlpMeterRegistry extends PushMeterRegistry`：内部一个 `ScheduledExecutorService`（`NamedThreadFactory` 命名的单线程），按 `step` 对齐发布。
- 每次发布：step 内的 `Meter` 经 `OtlpMetricConverter` 映射成 OTel `MetricData` → 组装 `ExportMetricsServiceRequest`（protobuf）→ `OtlpMetricsSender`（默认 `OtlpHttpMetricsSender`，内部包 `HttpUrlConnectionSender`）→ `POST {url}`，`Content-Type: application/x-protobuf`。按 `batch-size` 分批，**每批独立 try/catch**。
- `aggregation-temporality=delta` 时额外起一个 `meterPollingService` 做 rollover（源码里能看到 `newSingleThreadScheduledExecutor`）。
- 默认值与来源（源码实测）：

| 配置 | 默认 | 说明 |
|---|---|---|
| `management.otlp.metrics.export.url` | `http://localhost:4318/v1/metrics` | 也读 `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT` / `OTEL_EXPORTER_OTLP_ENDPOINT` |
| `step` | `1m` | 时间对齐，不是"启动后每 60s" |
| `aggregation-temporality` | `cumulative` | 可改 `delta` |
| `connect-timeout` / `read-timeout` | `1s` / `10s` | 已标 `@Deprecated`，官方建议改在 `HttpSender` 上配 |
| `batch-size` | 按 `PushRegistryConfig` | 单次请求的最大测量数 |
| `enabled` | `true` | 关掉即退化为纯本地 registry |

### 进程边界

```
[JVM 内]  业务代码 → OtlpMeterRegistry（step 聚合）
                          │ 单线程定时任务
[边界]    HTTP POST /v1/metrics（protobuf，4318）
                          ▼
[外部]    Collector ──► Prometheus（remote write）/ 商业后端
```

| 边界内 | 边界外 |
|---|---|
| 一个 push 线程 + 一个 step 缓冲 | Collector 决定转成什么后端格式、是否扇出 |
| 指标只活在堆里，**没有磁盘缓冲** | 后端负责长期存储与查询 |
| 每个 step 一次同步 HTTP | 网络抖动直接体现为发布线程阻塞 |

### 与外部怎么配合、怎么坏

- 协议只有 **HTTP/protobuf**：官方文档明确 "Micrometer does not currently provide a gRPC implementation of `OtlpMetricsSender`"。要 gRPC 只能自己实现 sender，或改用形态 C。
- 失败模式：

| 场景 | 表现 | 影响面 |
|---|---|---|
| Collector 不可达 | 该 step 数据**直接丢弃**，日志里一条错误 | 应用无感，数据缺口 |
| 网络慢 | 发布线程被 10s 超时拖住，后续 step 顺延 | 采集间隔漂移，看板出现"台阶" |
| Collector 重启 | 重启窗口内的 step 永久丢失 | 需要 Collector 侧持久化队列 |
| 需要 tail sampling | **做不到**，尾采样只能在 Collector | 形态 B 必须部署 Collector 才有治理能力 |

### 三个断言的对错

"埋点仍用 Micrometer 的接口和注解、`OtlpMeterRegistry` 负责对外交互、可以 push 到外部"——三句基本都对，但每句都要收窄一点。

| 断言 | 判定 | 收窄后的说法 |
|---|---|---|
| 埋点仍用 Micrometer 的接口和注解 | **对** | `MeterRegistry`/`Counter`/`Timer`/`@Timed`/`@Counted`/`Observation` 一行不改，这也是形态 B 唯一的核心价值：**换出口、不改业务代码**。 |
| `OtlpMeterRegistry` 负责"对外交互" | **半对** | 它只负责"映射 + 编码 + POST"这一段。服务发现、重试、磁盘缓冲、多后端扇出，它**一概不做**。 |
| 可以 push 到外部 | **对，而且只有 push** | 它 `extends PushMeterRegistry`（1.17 实测），没有 pull 能力；要暴露给 Prometheus 抓取只能靠形态 A 或 Collector 侧再暴露。 |

### "编码"要分两层，别混

这是形态 B 最容易含糊的地方：

| 层 | 谁做 | 说明 |
|---|---|---|
| 埋点编码（instrumentation） | **Micrometer** | 你写的 `Counter.increment()`、`@Timed`、`Observation`，落到 `Meter` 上 |
| 线上编码（wire encoding） | **OTel** | `OtlpMetricConverter` 转 `MetricData`，protobuf 序列化由 `opentelemetry-proto` 完成 |

所以准确说法是：**语义侧全是 Micrometer，传输侧全是 OTel**。它不是"OTel SDK 换了个皮"，更像"Micrometer 借 OTel 的行李箱发货"。

### 一次发布的完整链路

```
@Timed / Counter.increment()
        │ 写入时聚合（step 缓冲，无网络）
        ▼
Meter ──OtlpMetricConverter──► OTel MetricData
        │  单位按 base-time-unit 缩放、temporality 决定是否带 startTime
        ▼
ExportMetricsServiceRequest（protobuf，按 batch-size 分批）
        │
        ▼
OtlpMetricsSender（默认 OtlpHttpMetricsSender + HttpUrlConnectionSender）
        │ 同步 POST，失败只 warn
        ▼
Collector:4318/v1/metrics
```

三个隐含约束，配置前必须知道：

1. **时间单位默认是毫秒**。`OtlpConfig.baseTimeUnit()` 默认 `MILLISECONDS`（1.17 源码），而 OTel 语义约定偏好秒。跨后端比对（尤其和形态 C 的同一指标）时，显式设 `base-time-unit: seconds` 才不会出现"数值差 1000 倍"。
2. **exemplar 默认是空的**。1.17 新增了 `ExemplarContextProvider` SPI，但 registry **不内置实现**；Boot 4.1.1 的自动配置只做 `exemplarContextProvider.ifAvailable(builder::exemplarContextProvider)`——容器里没有这个 bean，就没有 exemplar。想要 `trace_id`，得自己写一个 bean 去对接 `micrometer-tracing` 的当前 span。
3. **`step` 会被环境变量抢走**。`step()` 的实现优先读 Micrometer 配置，其次读 `OTEL_METRIC_EXPORT_INTERVAL`，最后才是默认 1m。容器里设过 OTel 环境变量的话，`management.otlp.metrics.export.step` 与它的优先级关系要自己捋一遍。

## 4.3 形态 C：Spring Boot 标准栈（推荐，指标 + 追踪混血）

这是 Spring Boot 3.x/4.x 的"官方姿势"，也是绝大多数 Spring 团队应该用的形态。

### 依赖

Boot 4.1.1 里一个 starter 就够（pom 实测）：

```groovy
implementation 'org.springframework.boot:spring-boot-starter-opentelemetry'
// 注意：它不含 actuator，也不含 web；要端点自己加 spring-boot-starter-actuator
```

它替你带进来的东西，正好对应 2.2 那张分层图的每一层：

| 传递依赖 | 角色 | scope |
|---|---|---|
| `spring-boot-starter-micrometer-metrics` → `spring-boot-micrometer-metrics` | 指标自动配置（`MeterRegistry`、`MeterFilter`、`management.metrics.*`） | compile |
| `spring-boot-micrometer-tracing-opentelemetry` | 把 Micrometer Tracing 与 OTel SDK 接起来 | compile |
| `spring-boot-opentelemetry` → `opentelemetry-api` + `opentelemetry-sdk` | **OTel SDK 的实际来源** | compile |
| `micrometer-tracing-bridge-otel` | 门面 → OTel 实现（`OtelTracer`/`OtelSpan`） | runtime |
| `micrometer-registry-otlp` | 指标出口（Micrometer 自建 sender，见 4.2） | runtime |
| `opentelemetry-exporter-otlp` | 追踪出口（OTel 官方 exporter） | runtime |

> 关键细节：`micrometer-tracing-bridge-otel` 1.7.0 的 `build.gradle` 把 `opentelemetry-sdk`、`sdk-trace`、`sdk-common` 声明为 **`api` 依赖**。也就是说 SDK 是桥带进来的，你**不该**再手写 `implementation 'io.opentelemetry:opentelemetry-sdk'`——多写一次就容易出现版本漂移。
>
> Boot 3 时代没有这个 starter，等价写法是三条：`spring-boot-starter-actuator` + `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp`（指标再加 `micrometer-registry-otlp`）。

```yaml
management:
  tracing:
    sampling:
      probability: 0.1
  otlp:
    tracing:
      endpoint: http://collector:4318/v1/traces
    metrics:
      export:
        url: http://collector:4318/v1/metrics
```

### 怎么起作用（两条独立链路，共用一次埋点）

```
HTTP 请求
   │  Boot 注册的 ServerHttpObservationFilter（Servlet 与 WebFlux 各有一个同名类）
   ▼
ObservationRegistry（一次埋点：name + context + 低/高基数标签）
   │
   ├─ DefaultMeterObservationHandler ──► MeterRegistry（OtlpMeterRegistry）
   │                                          │ Micrometer 聚合 + 自建 sender
   │                                          ▼  /v1/metrics（HTTP/protobuf）
   │
   └─ TracingObservationHandler ──► OtelTracer ──► OtelSpan
                                        │
                                        ▼
                              SdkTracerProvider（Sampler + BatchSpanProcessor）
                                        │
                                        ▼  /v1/traces（HTTP 或 gRPC）
```

四个要点：

1. **埋点只有一次**：`Observation` 同时喂给 meter handler 和 tracing handler，这是形态 C 相对"分别调 Tracer/Meter"的唯一优势。
2. **指标与追踪走两套代码路径**：前者 Micrometer 聚合、Micrometer 编码；后者 OTel SDK 聚合、OTel exporter 编码。Boot 4 里它们甚至被拆进不同模块：`spring-boot-micrometer-metrics`、`spring-boot-micrometer-observation`、`spring-boot-micrometer-tracing`（及 `-opentelemetry` 适配模块）（证据：Boot 4 `module/` 目录与 `AutoConfiguration.imports`）。
3. **协议能力不对等**：`micrometer-registry-otlp` 只有 HTTP；`micrometer-tracing-bridge-otel` 走 OTel exporter，**gRPC 与 HTTP 都支持**。所以同一个应用可能出现"追踪走 4317、指标走 4318"。
4. **上下文传播**：入口由 observation 的 `ServerRequestObservationContext` 建 scope；出口由 `RestTemplate`/`WebClient`/Feign 的 observation 注入 `traceparent`；异步/Reactor 靠 `ContextPropagation` + `ObservationThreadLocalAccessor`（见 5.5）。

### 进程边界

| 边界内（JVM） | 边界外 |
|---|---|
| 请求线程：建 scope、记指标、开 span，全是内存操作 | Collector（推荐）或厂商 OTLP 端点 |
| Micrometer push 线程：每个 `step` 聚合一次、同步 POST 指标 | 后端：Tempo/Jaeger/Prometheus/商业 APM |
| OTel `BatchSpanProcessor` 线程：攒批（默认 512/5s）后 POST span | DNS / 负载均衡 / 网络策略 |
| 队列：span 默认 2048，满了丢弃 | 证书、代理、认证头（`management.otlp.tracing.headers.*`） |

**跨边界的两条连接**（可指向同一 host:port，但是两个独立 exporter）：

- 指标：`management.otlp.metrics.export.url`，**只有 HTTP**（Micrometer 自建 sender）。
- 追踪：`management.otlp.tracing.endpoint`，协议由 OTel exporter 决定，HTTP 或 gRPC 均可。

### 与外部怎么配合、怎么坏

- **Collector 是推荐的边界点**：应用只认 OTLP，后端换 Tempo→Jaeger→商业 APM 时应用零改动。
- 失败模式：

| 场景 | 表现 | 影响面 |
|---|---|---|
| Collector 挂 | span 入队后丢弃（队列满打 warning）；指标丢 step | 应用不受影响，数据缺口 |
| 应用被 `SIGKILL` | `BatchSpanProcessor` 来不及 flush，最后 5s 的 span 丢失 | 滚动发布时"尾巴"缺失 |
| 发布无优雅停机 | 同上，且指标最后一个 step 不完整 | 需配 `server.shutdown=graceful` |
| 两套 exporter 各自超时 | 排障要看 Micrometer 与 OTel 两套日志 | 定位成本高 |
| 采样只配了概率 | 错误链路也可能被采样掉 | 错误全留要在 Collector 做尾采样 |


## 4.4 形态 D：纯 OTel（agent 或 Spring Starter）

### D1：Java Agent（零代码）

**引哪些 jar**

严格说不是"引 jar"，是"挂一个 jar"：

| 文件 | 必需 | 作用 |
|---|---|---|
| `opentelemetry-javaagent.jar`（26MB） | 是 | 内含 SDK、exporter、ByteBuddy、三百多个插桩模块 |
| `opentelemetry-instrumentation-annotations` | 否 | 只想用 `@WithSpan`/`@SpanAttribute` 时才加 |

```bash
java -javaagent:/opt/otel/opentelemetry-javaagent.jar \
     -Dotel.service.name=order-service \
     -Dotel.exporter.otlp.endpoint=http://collector:4318 \
     -Dotel.exporter.otlp.protocol=http/protobuf \
     -jar app.jar
```

**怎么起作用**

`premain` → 把 agent 类推进 bootstrap CL → agent CL 里起 SDK → ByteBuddy 注册 `ClassFileTransformer` → 每个 `InstrumentationModule` 先过 muzzle 再织入。应用侧代码一行不改；应用里若调 OTel API，会被 10.2 说的"API 桥"接到 agent 的 SDK 上。

**进程边界**

| 边界内 | 边界外 |
|---|---|
| **第二套 SDK**，与 Boot 自带观测平行存在（这是所有冲突的根源） | Collector / 厂商 OTLP 端点 |
| ByteBuddy 在类加载期改字节码，改完的类进应用的 CL | 配置来自环境变量/`-D`，**不读 `application.yml`** |
| BSP 线程负责攒批发送；`runtime-telemetry` 另有 JMX/JFR 采集 | K8s 用 Downward API 注入 `OTEL_RESOURCE_ATTRIBUTES` |

**与外部怎么配合**：这是最容易忽略的一点——agent 的资源属性来自 `OTEL_SERVICE_NAME` / `OTEL_RESOURCE_ATTRIBUTES`，**与 `spring.application.name` 无关**。容器里通常这么接：

```yaml
env:
  - name: OTEL_SERVICE_NAME
    value: order-service
  - name: OTEL_RESOURCE_ATTRIBUTES
    value: deployment.environment=prod,k8s.pod.name=$(POD_NAME)
  - name: OTEL_EXPORTER_OTLP_ENDPOINT
    value: http://otel-collector.observability:4318
  - name: OTEL_EXPORTER_OTLP_HEADERS
    value: authorization=Bearer%20xxx
```

**怎么坏**：muzzle 判定版本不匹配 → 该库静默没有 span（应用正常）；与 Boot 观测同时启用 → 双份 span/指标；Collector 挂 → 队列满后丢弃。细节见 10.6、10.8。

### D2：OTel Spring Boot Starter

**引哪些 jar**

```groovy
implementation 'io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter:2.33.0'
```

pom 实测，它带进来的是"一整套 OTel 运行时"，而不是薄薄一层自动配置：

| 传递依赖 | 作用 |
|---|---|
| `opentelemetry-spring-boot-autoconfigure` | 自动配置 + 库级插桩装配（按 classpath 决定装哪些） |
| `opentelemetry-api` / `opentelemetry-sdk` | API 与参考实现 |
| `opentelemetry-exporter-otlp` / `opentelemetry-exporter-logging` | 出口（生产用 OTLP，本地调试用 logging） |
| `opentelemetry-sdk-extension-autoconfigure-spi` | 让 `AutoConfigurationCustomizer` 生效 |
| `opentelemetry-resources` + `aws/gcp/azure/cloudfoundry-resources` | 云环境资源探测 |
| `opentelemetry-samplers` | 额外采样器（如 Jaeger remote） |
| `opentelemetry-baggage-processor` | Baggage 处理 |
| `opentelemetry-instrumentation-annotations` | `@WithSpan` |

**怎么起作用**：starter 用 Spring Boot 的自动配置机制创建 `OpenTelemetry` bean（内部是 `OpenTelemetrySdk`），并按 classpath 装配库级插桩（webmvc/webflux/jdbc 等）。它**替换的是"追踪实现"这一层**：Boot 自己的 `MicrometerTracingAutoConfiguration` 因为 classpath 上没有 `micrometer-tracing` 而自动退让。

但要注意两个"没有替换"：

- **Actuator 的 Micrometer 指标还在**，`MeterRegistry` 与 OTel `MeterProvider` 是两套互不相通的计量系统（要打通得用 4.5 的桥）。
- **`@Timed` 只产 Micrometer 指标**，不会变成 OTel span；要 span 得用 OTel API 或 `@WithSpan`。

**进程边界**：与 C 类似（出站 OTLP，BSP 攒批），区别在"SDK 在应用 classpath 里"，因此你可以 `@Autowired OpenTelemetry`、自己注册 `SpanProcessor`，也更容易做 AOT/原生镜像适配——这些在 D1 里都做不到。

**与外部配合**：配置既可用 OTel 标准环境变量，也可用 Spring 的 `otel.*` 属性（starter 把 Spring `Environment` 作为配置源），这一点比 agent 友好；资源属性同样建议显式给 `service.name`。

**D1 与 D2 的取舍**：

| 维度 | D1 agent | D2 starter |
|---|---|---|
| 安装 | 改启动参数，零代码 | 加依赖，可能要调自动配置 |
| 配置源 | `OTEL_*` / `-D` / YAML 文件 | Spring `otel.*` + 环境变量 |
| 覆盖范围 | 整个 JVM（含第三方 jar 内部） | 主要是 Spring 生态与显式接入的库 |
| 指标 | agent 内置 SDK 的插桩指标 | OTel `MeterProvider`，Actuator 指标另算 |
| 可编程性 | 只能通过扩展 jar | 可直接注入 `OpenTelemetry` |
| GraalVM native | 不可用 | 可用 |
| 与 Boot 观测冲突 | 有（双份） | 较小（Boot 追踪自动退让） |


## 4.5 形态 E：双向桥（两个生态互相嵌入）

桥不是"又一种部署形态"，而是**把某个接口的实现换掉**。所以每个桥只要回答两个问题：**替换谁**、**谁跨过了边界**。

### 桥的清单与插入点

| 方向 | 坐标 | 插入点（替换谁） | 机制 | 状态 |
|---|---|---|---|---|
| Micrometer → OTel（指标） | `io.opentelemetry:opentelemetry-micrometer1-shim` | 用一个 `OpenTelemetryMeterRegistry` 当 Micrometer 的 `MeterRegistry` | Micrometer API 写入 → OTel `MeterProvider` | **已停更**：1.13.0-alpha ~ 1.17.0-alpha，最后一版 2022-08 |
| Micrometer → OTel（指标，现役） | `io.opentelemetry.instrumentation:opentelemetry-micrometer-1.5` | `OpenTelemetryMeterRegistry.builder(openTelemetry).build()`；agent 里对应 `micrometer` 插桩（默认关） | Micrometer API（含 Observation 产生的指标）写入 → OTel Metrics | 2.33.x-alpha |
| OTel → Micrometer（指标） | `io.opentelemetry.contrib:opentelemetry-micrometer-meter-provider` | 用 `MicrometerMeterProvider.builder(meterRegistry).build()` 当 `MeterProvider` | OTel API 写入 → Micrometer registry 出口 | contrib alpha |
| OTel → Prometheus client | `io.opentelemetry.contrib:opentelemetry-prometheus-client-bridge` | `sdkMeterProvider.registerMetricReader(PrometheusCollector.create())` | OTel 指标 → `CollectorRegistry.defaultRegistry` | contrib alpha |
| OTel → JMX | `io.opentelemetry.contrib:opentelemetry-jmx-metrics` | agent 插桩或库 | JMX MBean → OTel 指标 | alpha |
| 追踪（双向） | `micrometer-tracing-bridge-otel` / `-brave` | Micrometer `Tracer` 门面 → 具体实现 | 门面 → SDK | GA |

> 表格里第一条需要特别更正：市面上不少文章还在推 `opentelemetry-micrometer1-shim`，但它**最后一版是 1.17.0-alpha（2022-08 发布）**，之后没有跟进 OTel SDK 的 1.6x。新项目请直接用 `opentelemetry-micrometer-1.5`，它同时提供库插桩与 agent 插桩两种形态。

### 它们怎么起作用

- **Micrometer → OTel（`OpenTelemetryMeterRegistry`）**：这是一个正宗的 `MeterRegistry` 实现，Micrometer 的 `Counter`/`Gauge`/`Timer` 分别映射成 OTel 的同步 counter、异步 gauge、同步 histogram，单位用 base unit。在 agent/库插桩形态下还有 `prometheus-mode`，会把命名改成 Micrometer 风格、时间单位改成秒，用于"看板先不动、后端先换掉"的迁移。
- **OTel → Micrometer（`MicrometerMeterProvider`）**：反向替换 `MeterProvider`。它有一处结构性坑：OTel 的仪器**创建时不带标签**（标签在每次测量时给），而 Micrometer 必须有标签才能建出 `Meter`。所以实现只能"在 `MeterRegistry` 读取时按当时的标签注册"，meter 的生命周期与读取节奏绑定，动态标签多时容易堆出大量 meter。
- **OTel → Prometheus client**：`PrometheusCollector` 是一个 `MetricReader`，只注册到 `CollectorRegistry.defaultRegistry`；而且它基于 **simpleclient**，不是 1.x 的 `prometheus-metrics-core`。同时引两代 Prometheus client 会把依赖树搞乱。
- **OTel → JMX**：把 MBean 属性采成 OTel 指标，适合"应用没埋点、只有 JMX"的中间件；在 Spring 应用里会和 Micrometer 的 JMX 指标重复。

### 进程边界

桥**不改变进程边界**——它仍然在同一个 JVM 里，不新增端口、不新增出站连接。但它改变了边界内"谁聚合、谁编码、谁发送"：

| 桥 | 聚合方 | 编码/发送方 | 跨边界时的变化 |
|---|---|---|---|
| Micrometer → OTel registry | 桥（OTel SDK 的 reader 周期） | OTel exporter | temporality 由 OTel 决定（默认 cumulative） |
| OTel → Micrometer provider | Micrometer registry | Micrometer registry（Prometheus/OTLP/…） | 变成 Micrometer 的 step 与命名 |
| OTel → Prometheus client | Prometheus client | 应用自身 HTTP 端点 | 回到 pull 模型，由 Prometheus 来抓 |

**实战结论**：桥只在"迁移期"或"单一方向无法覆盖"时用，且要接受三件事——命名会变、temporality 会变、exemplar 可能丢。双向桥目前全是 alpha，别在一条链路上同时用两个方向的桥。


## 4.6 五种形态的选择表

| 形态 | 指标实现 | 追踪实现 | 传输 | 适合谁 | 主要代价 |
|---|---|---|---|---|---|
| A 纯 Micrometer | Micrometer | 无 | pull / 厂商 API | 单体、已有 Prometheus | 无追踪，命名不标准 |
| B Micrometer + OTLP | Micrometer | 无 | OTLP/HTTP | 想统一传输但暂不上追踪 | 只有 HTTP，无 gRPC |
| C Boot 标准栈 | Micrometer | OTel SDK | OTLP | **绝大多数 Spring 微服务** | 两条路径，配置项多 |
| D1 Java Agent | OTel SDK | OTel SDK | OTLP | 多语言、存量系统、零改造 | 与 Boot 观测重复、SDK 冲突 |
| D2 OTel Starter | OTel SDK | OTel SDK | OTLP | OTel 原教旨组织 | 放弃 Actuator 指标体系 |

这张表回答"选哪个"；"每个环节的权力归谁"在 4.9 的七问表里。

## 4.7 进程边界与线程总表

把前五节压缩成一张"出事时该看哪里"的表：

| 形态 | 出站连接（跨边界） | 入站端口 | JVM 内新增线程 | 外部组件职责 | 对端挂掉时 |
|---|---|---|---|---|---|
| A 纯 Micrometer | **无** | 应用端口 `/actuator/prometheus` | 无 | Prometheus 抓取/存储，Grafana 查询 | 应用无感，仅数据缺口 |
| B Micrometer + OTLP | HTTP POST `:4318/v1/metrics` | 无（actuator 可选） | 1 个 push 线程 | Collector 转格式/扇出 | 丢弃该 step |
| C Boot 标准栈 | 指标 `:4318/v1/metrics`；追踪 `:4318/v1/traces` 或 `:4317` | `/actuator/*`（若引入 actuator） | Micrometer push 线程 + OTel BSP 线程 | Collector 汇聚、尾采样、脱敏 | 队列满后丢弃，应用正常 |
| D1 Java Agent | 同 C（默认 HTTP 4318） | `/actuator/*`（Boot 自带） | BSP 线程 + JVM 指标采集线程 | 同 C | 同 C |
| D2 OTel Starter | 同 C | `/actuator/*` | BSP 线程（+ 指标 reader，若启用） | 同 C | 同 C |
| E 双向桥 | **不新增出站**，继承底座 | 继承底座（pull 形态复用应用端口） | 继承底座 | 同底座 | 同底座 |

三个容易记错的点：

1. **形态 A（以及以它为底座的桥）是唯一没有出站连接的**，所以它也是唯一"网络怎么抖都不影响应用"的形态——**前提是不开 Pushgateway**（一开就有推送线程，见 4.1 末尾）。
2. **形态 D 的线程比 C 多**：agent 除了 BSP，还要跑 `runtime-telemetry` 的 JMX/JFR 采集；指标不是"免费"的。
3. **桥不产生新连接**，但它会把"谁聚合"换掉，出问题时先看 temporality 和命名，而不是看网络。

## 4.8 跨边界协作的三种典型拓扑

**拓扑 1：应用直连后端**（适合小规模、后端原生支持 OTLP）

```
App ──OTLP──► 商业 APM / Tempo（直连）
```

省掉 Collector，但换后端要改应用配置，且**没有尾采样、没有脱敏、没有扇出**。只在"团队只有三个服务"时成立。

**拓扑 2：应用 → Collector → 后端**（推荐）

```
App ──OTLP──► Collector（DaemonSet/边车）──► Prometheus / Tempo / 商业 APM
                    │
                    ├─ memory_limiter：保护自己不被压垮
                    ├─ batch：攒批，降低后端压力
                    ├─ tail_sampling：错误全留、正常 1%
                    └─ attributes/transform：脱敏、改名
```

职责划分很清楚：

| 角色 | 只负责 | 不该负责 |
|---|---|---|
| 应用（agent/SDK） | 产生遥测、短时缓冲、按比例采样 | 长期缓冲、脱敏、跨服务决策 |
| Collector | 攒批、重试、限流、尾采样、扇出、脱敏 | 业务语义 |
| 后端 | 存储、查询、告警 | 采集与清洗 |

**拓扑 3：双跑**（迁移期专用）

```
App ──► 旧路径（Prometheus pull / 旧 agent）
    └──► 新路径（OTLP → Collector）
```

用于"指标改名 + 语义约定切换"的过渡：两套同时出数，按 5.2 的对照表校准看板后再拆旧路径。代价是采集与存储成本翻倍，**必须有明确的拆除日期**，否则会永久留在两套体系上。

## 4.9 七问定位法：五种形态逐项对照

把 4.1–4.8 压缩成两张表。第一张看"数据在进程内被谁处理"，第二张看"数据怎么出去、坏了谁扛"。

### 表 1：埋点、聚合、语义（进程内）

| 形态 | ① 埋点 API / 注解 | ② 谁聚合与缓冲 | ③ 谁定义语义与数据模型 |
|---|---|---|---|
| A 纯 Micrometer | Micrometer：`MeterRegistry`、`@Timed`、`Observation` | Micrometer：写入时累加，**无 step 缓冲** | Micrometer `Meter.Id` + `PrometheusNamingConvention` → Prometheus 数据模型 |
| B Micrometer + OTLP | 同上，**一行不改** | Micrometer：step 缓冲（`OtlpMeterRegistry`） | 两侧各一半：语义是 Micrometer，数据模型是 OTel `MetricData`（`OtlpMetricConverter` 转换） |
| C・指标链 | Micrometer（Observation） | Micrometer：step 缓冲 | 同 B |
| C・追踪链 | Micrometer Observation → `micrometer-tracing` 的 `Tracer` 门面 | **OTel SDK**：`SdkTracerProvider`（Sampler → SpanProcessor → 队列） | **OTel**：`SpanData` + semconv |
| D1 Java Agent | 无（ByteBuddy 自动插桩）；可选 `@WithSpan`/`@SpanAttribute` | agent **内置的** OTel SDK，独立类加载器 | OTel |
| D2 OTel Starter | OTel API（`Tracer`/`Meter`）+ 库级插桩 | 应用 classpath 里的 OTel SDK | OTel |
| E 双向桥 | 取决于底座 | **被桥接到另一侧**：如 `OpenTelemetryMeterRegistry` 让 Meter 由 OTel 处理 | 转换层（两套模型同时在场） |

### 表 2：传输、能力归属、失败语义（跨进程）

| 形态 | ④ 方式 | ⑤ 谁真正提供这个能力 | ⑥ 编码（wire） | ⑦ 失败时谁兜底 |
|---|---|---|---|---|
| A | pull | **Prometheus 服务端的抓取器**；应用只提供被动端点 | Micrometer（`ExpositionFormats`，文本 / OpenMetrics） | 无连接可失败；抓不到只是数据缺口 |
| B | push | **Micrometer**：`PushMeterRegistry` 调度 + `HttpUrlConnectionSender` 发送；OTel 只提供编码器 | OTel（`opentelemetry-proto`） | 无：warn 一条，丢该批 |
| C・指标 | push | 同 B | OTel | 同 B |
| C・追踪 | push | **OTel SDK**：`BatchSpanProcessor` + exporter | OTel | BSP 队列满则丢弃并计数 |
| D1 | push | agent 内置 SDK（BSP + exporter） | OTel | 同 C・追踪 |
| D2 | push | 应用内 OTel SDK | OTel | 同 C・追踪 |
| E | 继承底座 | 继承底座 | 继承底座 | 继承底座 |

读这两张表，有四个反直觉点值得单独记：

1. **第 5 问在形态 B/C 指标链上的答案不是 OTel**。虽然是 OTLP，push 的调度能力来自 Micrometer 的 `PushMeterRegistry`，HTTP 客户端也是 Micrometer core 的。所以 `OTEL_EXPORTER_OTLP_*` 环境变量只影响 exporter 那一截，**发多久一次由 `step` 决定**。
2. **pull 的能力根本不在应用侧**。Micrometer 不做 pull，应用也不做 pull，做 pull 的是 Prometheus 的 scraper；应用只是"把堆里的状态序列化成一个 HTTP 响应"。所以"Micrometer 支持 pull"是 shorthand，不是事实。
3. **第 2 问在形态 C 会分裂**。同一进程里，指标由 Micrometer 聚合、追踪由 OTel SDK 聚合。这才是"混血"的准确含义，也是排查"指标有、Span 没有"时该看哪一侧的依据。
4. **第 7 问决定 SLA**。五种形态没有一种会在后端不可用时落盘；要"不丢"只能靠 Collector 的 `sending_queue` + `file_storage` 持久化队列。


---

# 五、决定踩坑的关键实现差异

这一章只讲"看起来一样、实际不一样"的地方。每一条都对应第八章的一个坑。

## 5.0 两个数据模型：仪表盘 vs 信号

理解后面所有差异的前提，是承认两者建模的出发点不同：

- **Micrometer 是"仪表盘模型"**（instrument-centric）：一个 `Meter` = 一个 `Id` + 一组 `Measurement`。`Timer` 这一个对象同时装着 count、total、max、直方图桶，读的时候生成 `HistogramSnapshot`。
- **OTel 是"信号模型"**（signal-centric）：Metrics 只是三信号之一，与 Traces、Logs 平级，共享 `Resource` 与 `InstrumentationScope`。一个 `Metric` 只有一种类型，同类型的 `DataPoint` 同构。

| 维度 | Micrometer | OpenTelemetry |
|---|---|---|
| 顶层容器 | `MeterRegistry`（按 name + tags 索引） | `MeterProvider` → `Meter` → `Instrument`；`MetricReader` collect 出 `MetricData` |
| 计量单元 | `Meter` + **多条** `Measurement(Statistic, amount)` | `Instrument` → 聚合 → **单一类型**的 `Metric` + `DataPoint` |
| 类型数量 | `Meter` 接口有 9 个（Counter / Gauge / Timer / DistributionSummary / LongTaskTimer / TimeGauge / FunctionCounter / FunctionTimer / MultiGauge），但 `Meter.Type` 枚举只有 6 个（`COUNTER`、`GAUGE`、`LONG_TASK_TIMER`、`TIMER`、`DISTRIBUTION_SUMMARY`、`OTHER`） | 7 种同步/异步 Instrument；5 种 `MetricData`（`Sum`、`Gauge`、`Histogram`、`ExponentialHistogram`、`Summary`） |
| 标识 | `Meter.Id{name, tags, type, baseUnit, description, syntheticAssociation}` | `InstrumentationScope{name, version}` + `Metric{name, description, unit, type}` |
| 属性 | `Tag`：**String → String** | `Attribute`：**有类型**（string / bool / int / double / 数组） |
| 进程级元数据 | `commonTags`（复制进每个 meter） | `Resource`（独立于 metric，随每批数据发送） |
| 时间基准 | `baseTimeUnit`（registry 级配置） | `unit` 字符串（UCUM）+ `start_time_unix_nano` |
| 读时形态 | 现算快照（`HistogramSnapshot` / `DistributionSnapshot`） | 不可变 `MetricData`，collect 时构造 |
| 归属信息 | 没有 instrumentation scope 概念 | 每个 metric 自带 scope（OTLP registry 源码里写着：`we don't have instrumentation library/version attached to meters; leave unknown`） |

同一个 `Timer`，两边长出来的"形状"完全不同。

**Micrometer**：一个对象，一组 measurement，导出时被**展开成并列的多条序列**：

```java
Timer.builder("http.server.requests")
     .tag("method", "GET").tag("uri", "/orders/{id}")
     .register(registry)
     .record(Duration.ofMillis(120));

// 读出来是一个快照
HistogramSnapshot{ count=100, total=12.3s, max=0.8s,
                   percentileValues=[...], histogramCounts=[...] }
```

```
# 导出到 Prometheus：同一前缀下 4 类序列
http_server_requests_seconds_count{method="GET",uri="/orders/{id}"} 100
http_server_requests_seconds_sum{method="GET",uri="/orders/{id}"}   12.3
http_server_requests_seconds_max{method="GET",uri="/orders/{id}"}   0.8
http_server_requests_seconds_bucket{le="0.1",method="GET",...}      42
```

**OTel**：一个 Histogram，count / sum / buckets 全塞进**同一个 data point**：

```java
meter.histogramBuilder("http.server.request.duration")
     .setUnit("s")
     .build()
     .record(0.12, Attributes.of(stringKey("http.request.method"), "GET",
                                  stringKey("http.route"), "/orders/{id}"));
```

```
Metric{ name="http.server.request.duration", unit="s", type=Histogram }
  └─ DataPoint{ attributes=[http.request.method=GET, http.route=/orders/{id}],
                count=100, sum=12.3, min=..., max=0.8,
                explicit_bounds=[0.005, 0.01, ..., 10],
                bucket_counts=[...] }
```

**所以"序列数变少"不是丢数据，是折叠方式不同**：Micrometer 把 count / sum / max / bucket 拆成并列序列，OTel 把它们塞进一个 data point。看板从 `_count` / `_sum` 迁到 OTLP 时，查询要改成对一个 histogram 做 `histogram_quantile` 或直接用后端的聚合函数。

由此派生三条容易踩的结论：

1. **Micrometer 的"一个指标"在 OTel 里可能不是一个 metric**。`LongTaskTimer` 同时产出 active tasks（`Sum`）与 duration（`Histogram`）；开了 `publishMaxGaugeForHistograms`（默认 true）的 Timer 会额外多一个 `_max` Gauge。迁移后"metric 数量对不上"是正常的。
2. **属性类型在 OTel 里有意义**。Micrometer 的 tag 永远是字符串，`status=200` 与 `status="200"` 没有区别；OTel 的 `http.response.status_code` 是 int。用 Micrometer 埋点再走 OTLP，数值型属性只能以字符串落地——这是桥接层修不了的语义损失。
3. **`Resource` 与 `commonTags` 不等价**。commonTags 会被复制进每条序列（Prometheus 里看得见、也算基数），Resource 是批次级元数据（不进序列标签）。把 `service.name` 塞进 commonTags 会推高基数与存储成本，而在 OTLP 里它本该在 Resource。

## 5.1 聚合与发布模型

| 维度 | Micrometer | OTel SDK |
|---|---|---|
| 谁持有状态 | `MeterRegistry`（进程内） | `SdkMeterProvider` → `MetricReader` |
| 聚合时机 | 写入时增量聚合，按 step 窗口轮换 | 写入时聚合，由 Reader 周期 collect |
| 推送节奏 | `StepMeterRegistry.step`（默认 1 分钟，整点对齐） | `PeriodicExportingMetricReader`（默认 60 秒，非对齐） |
| 临时性 | pull registry 天然 cumulative；step registry 天然 delta | `AggregationTemporality` 可配，默认 cumulative |
| 拉取模式 | Prometheus registry 暴露 HTTP 端点 | `PrometheusMetricReader` 暴露端点 |
| 基数超限 | `MeterFilter.maximumAllowableTags` 直接拒绝新标签组合 | View 的 `cardinalityLimit`（默认 2000），超出折叠成 `otel.metric.overflow=true` |

**实战影响**：Micrometer 的 step 是"时间对齐"的（00:00、00:01……），OTel 的是"从启动时刻起算"。两个数据源混在一个看板上时，会出现"总量对得上、峰值对不上"的诡异现象。做双跑对比时务必对齐这一点。

## 5.2 命名与单位：最贵的迁移成本

### 名字要经过三次变形

一个 HTTP 指标的名字，在链路上会被改三次，每一次的"负责人"不同：

```
① 语义名（Observation name / Instrument name）
   Micrometer：http.server.requests
   OTel semconv：http.server.request.duration
        │
        │ ② 注册名（Meter.Id.name / Metric.name）
        ▼
   Micrometer：http.server.requests（原样）
   OTel：http.server.request.duration（原样，unit=s）
        │
        │ ③ 导出名（NamingConvention / exporter）
        ▼
   Prometheus：http_server_requests_seconds_count / _sum / _max / _bucket
   OTLP：      http.server.requests（点分保留，unit=s）
```

**第 ③ 步只做字符替换，不改语义**。这是最容易误解的地方：

- Micrometer 的 `NamingConvention` 接口只有三个方法：`name(name, type, baseUnit)`、`tagKey(key)`、`tagValue(value)`。
- `PrometheusNamingConvention` 做两件事：`sanitizeMetricName`（`.` → `_` 等），以及按类型补后缀——`COUNTER` / `DISTRIBUTION_SUMMARY` / `GAUGE` 在 baseUnit 存在时补 `_{baseUnit}`，`TIMER` / `LONG_TASK_TIMER` 补 `_seconds`。`_total` **不是它加的**：新版 client 在序列化时自己加，源码注释明确写着 "we mustn't do this since it appends `_total` on its own"，重复会抛 `IllegalArgumentException`。
- **`OtlpMeterRegistry` 构造时把 naming convention 设成 `NamingConvention.dot`（即 identity）**（源码 `config().namingConvention(NamingConvention.dot)`）。所以走 OTLP 时名字**原样保留点分**，既不加单位后缀，也不转下划线。

### 两套命名规则的结构差异

| 规则 | Micrometer | OTel semconv |
|---|---|---|
| 形态 | `{domain}.{plural}`，如 `http.server.requests`、`jvm.memory.used` | `{namespace}.{object}.{metric}`，如 `http.server.request.duration` |
| 单复数 | **复数**（requests、connections） | **单数**（request、connection） |
| 单位 | 不进名字；导出到 Prometheus 时才追加 `_seconds` | 不进名字，放 `unit` 字段（UCUM：`s`、`By`、`1`、`{request}`） |
| 后缀 | 无固定后缀 | `_duration`、`_size`、`_count`、`_usage` 表意 |
| 属性命名 | 短名：`method`、`uri`、`status`、`exception`、`outcome` | 带命名空间：`http.request.method`、`http.response.status_code`、`http.route`、`error.type` |
| 属性类型 | 只有 string | 有类型（int / double / bool / string / 数组） |
| 基数控制 | `MeterFilter.maximumAllowableTags` | View `cardinalityLimit` + semconv 强制低基数（`http.route` 而非原始 URI） |

`http.server.request.duration` 的官方定义值得记牢：**Histogram、unit `s`、推荐桶 `[0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1, 2.5, 5, 7.5, 10]`**；Server 端必填属性只有 `http.request.method` 与 `url.scheme`，`http.response.status_code`、`http.route`、`error.type` 是条件必填，`server.address` / `server.port` 是 opt-in（因为基于请求头的值可能被用来打爆基数）。

### Spring Boot 的 conventions 开关

同一份 HTTP 请求，两套约定产出完全不同的时间序列。Spring Boot 4.2 引入 `management.observations.conventions` 开关（`micrometer` 默认 / `open-telemetry`）后，这个差异被摆到了台面上：

| 项目 | Micrometer 约定（默认） | OTel 约定（`open-telemetry`） |
|---|---|---|
| 指标名 | `http.server.requests` | `http.server.request.duration` |
| 标签 | `method`、`status`、`uri`、`exception`、`error`、`outcome` | `http.request.method`、`http.response.status_code`、`http.route`、`url.scheme`、`error.type`、`error`、`outcome` |
| 单位 | 秒 | 秒（历史版本曾是毫秒，v1.13 前后统一为秒） |
| Span 名 | `http get /hello/{name}` | `GET /hello/{name}` |
| 直方图桶 | Micrometer 默认桶 / SLO 自定义 | semconv 推荐桶（0.005 ~ 10 秒） |

> 注：Boot 4.2 处于 RC 阶段时，官方文档写的属性值 `open-telemetry` 会导致启动失败（找不到 `ServerRequestObservationConvention` bean），实测可用值是 `opentelemetry`。正式版请以官方文档为准，升级前先在测试环境验证。

**这意味着**：切 conventions 不是改代码，是**重建所有看板和告警**。而且 `exception` 标签在新约定里消失了，依赖它的告警会静默失效。

### 一个必须记住的结论

**上了 OTLP，不等于符合 OTel 语义约定。** `http.server.requests` 走 OTLP 之后仍然叫 `http.server.requests`——registry 只负责保留点分，不改语义。要得到 `http.server.request.duration`，必须换 `ObservationConvention`（或直接用 OTel API 埋点），而不是换 exporter。

反过来说：只切 exporter 不切 conventions，得到的是"OTLP 传输 + Micrometer 命名"的混合体——格式是标准的，语义不是。在 Collector 用 `transform` processor 改名之前，任何基于 semconv 的看板模板都用不了。

单位则是最隐蔽的偏差源：Micrometer 的 `Timer` 内部按纳秒存储，导出时按 `baseTimeUnit` 换算；Prometheus registry 用秒，OTLP registry 默认毫秒（见 4.2）。同一份数据在两条出口上可能相差 1000 倍，**而指标名看起来完全一样**。

## 5.3 直方图与百分位

- Micrometer 的强项是"业务语义的桶"：`serviceLevelObjectives` 直接给业务阈值，`publishPercentiles` 给客户端百分位。
- OTel 的强项是"标准化的桶 + 指数直方图"：`ExplicitBucketHistogram` 与 `ExponentialHistogram`，后者在高基数、宽量程场景下更省内存。
- 交叉点：Micrometer 的 OTLP registry 同时支持两种（`histogramFlavor` / `histogramFlavorPerMeter`），但**一旦配了 SLO，就只能用显式桶**——指数直方图不支持自定义边界。
- 陷阱：Micrometer 的 `publishPercentiles` 导出成 OTLP 的 **Summary** 数据点，而 OTel 规范已把 Summary 标为 legacy、不推荐新应用使用。跨后端迁移时，Summary 往往不被支持（比如某些后端的 Prometheus 兼容层）。

## 5.4 Exemplar：指标跳链路的唯一桥梁

两边都支持，但机制不同：

- Micrometer：`OtlpMeterRegistry` 通过 `ExemplarContextProvider`（如 `TracingExemplarContextProvider`）在记录值时抓取当前 traceId/spanId，附在 Counter/Timer/DistributionSummary 上。**Prometheus registry 需要 OpenMetrics 格式才带 exemplar**。
- OTel：SDK 内建 `ExemplarReservoir`（默认 `AlignedHistogramBucketExemplarReservoir`）+ `TraceBased` 过滤器，只在被采样的 Span 内保留 exemplar。
- 陷阱：采样率 0 或 Span 未采样时，exemplar 为空；两个体系混用时，exemplar 里的 traceId 可能指向一个后端里不存在的 trace（因为追踪采样策略不同）。

## 5.5 上下文传播

- Micrometer 侧：`context-propagation` 库 + `ThreadLocalAccessor`，解决 Reactor、`@Async`、虚拟线程里"当前 Span/Baggage 丢失"的问题。
- OTel 侧：`Context` 是不可变键值容器，`Context.current()` 底层仍是 ThreadLocal（agent 会做 instrument 以保证跨线程传递）。
- 共同语言：W3C `traceparent` / `tracestate`。**但 Brave 传统上默认 B3**，与 OTel 默认的 W3C 混用时链路会断。Spring Boot 用 `management.tracing.propagation.type` 显式指定，微服务群必须统一。
- Baggage 陷阱：Micrometer Tracing 的 `BaggageManager` 与 OTel `Baggage` 是两套实现，桥接时会做转换；字段名大小写、允许的 key 列表都可能丢数据。

## 5.6 采样

- Micrometer **不做采样**。`management.tracing.sampling.probability` 只是把值传给 Brave 或 OTel 的 Sampler。
- OTel SDK 支持 head sampling（`parentbased_traceidratio` 等）与 Jaeger Remote Sampler。
- **尾采样只能在 Collector 做**（`tail_sampling` processor），两边 SDK 都无能为力。这也是"要不要上 Collector"的关键决策点之一。

## 5.7 Resource 与 SDK 标识

- OTel SDK 自动填 `telemetry.sdk.name=opentelemetry`、`telemetry.sdk.language=java`、`telemetry.sdk.version=1.67.0`。
- Micrometer 的 OTLP registry 填 `telemetry.sdk.name=io.micrometer`、`telemetry.sdk.version=<micrometer 版本>`，`service.name` 默认 `unknown_service`（可用 `OTEL_SERVICE_NAME` 覆盖）。
- 影响：在 Collector 里做资源处理器、在后端做"按 SDK 分组"的看板时，你会看到两种 `telemetry.sdk.name`。这不是 bug，是两套实现各自诚实的结果。

## 5.8 类型映射总表

埋点类型是两套模型之间最硬的一道坎。这张表按"Micrometer 的 9 个 Meter 接口 → OTel Instrument → 导出的 `MetricData`"排：

| Micrometer | 对应的 OTel Instrument（写入侧） | 导出的 `MetricData` | 备注 |
|---|---|---|---|
| `Counter` | Counter（同步） | `Sum{monotonic=true}` | Prometheus 导出时名字加 `_total` |
| `FunctionCounter` | ObservableCounter（异步） | `Sum{monotonic=true}` | collect 时回调求值 |
| `Gauge` | Gauge（同步） | `Gauge` | 持对象弱引用，读 `value()` / `get()` |
| `TimeGauge` | Gauge（unit=`s`） | `Gauge` | 本质是带时间单位的 Gauge |
| `Timer` | Histogram（同步） | `Histogram` 或 `Summary` | 配了 `publishPercentiles` 就退化为 `Summary` |
| `DistributionSummary` | Histogram（同步） | `Histogram` 或 `Summary` | 同上，但没有时间语义 |
| `LongTaskTimer` | Histogram + UpDownCounter | `Histogram` + `Sum` | active tasks 与 duration 拆成两个 metric |
| `FunctionTimer` | ObservableGauge / Histogram | `Gauge` / `Histogram` | 取决于是否带分布统计 |
| `MultiGauge` | 多条 Gauge | 多条 `Gauge` | 一个 MultiGauge 展开成 N 个 metric |

读表必须注意三点：

1. **`Timer` 不是"一定"变 Histogram**。`OtlpMetricConverter` 的判断是 `if (histogramSnapshot.percentileValues().length != 0) → buildSummaryDataPoint(...)`。只要配了 `publishPercentiles`，导出的是 Summary——而 OTel 规范已把 Summary 标为 legacy，很多后端不接。
2. **`publishMaxGaugeForHistograms` 默认 true**，会给每个 histogram 额外发一个 `_max` Gauge。所以一个 Timer 在 OTLP 里可能是 2 个 metric，Collector 侧做去重或按 metric 计费时要留意。
3. **反向映射不成立**。OTel 的 `UpDownCounter` 在 Micrometer 里没有对应类型（只能拿 `Gauge` 加回调凑），`ObservableUpDownCounter` 也一样。所以"OTel 埋点 → Micrometer 出口"这条桥（`opentelemetry-micrometer-meter-provider`）必然有损。

## 5.9 语义约定：标签、错误、Span

### 标签与属性：不只是改名

| 维度 | Micrometer | OTel |
|---|---|---|
| 类型 | `Tag`：String → String | `Attribute`：有类型，数值不会被字符串化 |
| 挂载位置 | 绑在 `Meter.Id` 上，成为序列身份的一部分 | 绑在 `DataPoint` 上 |
| 命名 | 短名：`status`、`uri`、`exception` | 带命名空间：`http.response.status_code`、`http.route`、`error.type` |
| 基数治理 | `MeterFilter.maximumAllowableTags` 直接拒绝 | View `cardinalityLimit`（默认 2000）溢出折叠成 `otel.metric.overflow=true` |
| 进程元数据 | `commonTags`（进每条序列） | `Resource`（批次级，不进序列标签） |
| 动态值 | 只有 `Gauge` 的 value 是动态的 | 属性在 data point 上，可逐点不同 |

### 错误语义：Micrometer 把异常变成维度，OTel 把它变成事件

| 维度 | Micrometer | OTel |
|---|---|---|
| HTTP 状态 | `status=500` | `http.response.status_code=500` |
| 错误归类 | `outcome=SERVER_ERROR`、`error=...` | `error.type=500`（或异常类名） |
| 异常本身 | `exception=IllegalStateException`，**只有一个类名字符串** | `exception` **span event**，带 `exception.type` / `exception.message` / `exception.stacktrace` |
| Span 状态 | 门面层只有 `Span.error(Throwable)`，没有显式 status | `Status{code=ERROR, description}` |
| Span 名 | `http get /orders/{id}`（小写方法 + `http ` 前缀） | `GET /orders/{id}`（大写方法 + route） |
| 查询影响 | 可以按 `exception` 分组，代价是基数 | 指标里没有异常维度，查异常必须回到 trace |

这一条是迁移里最容易被忽略的**功能性**损失：原来一句 `sum by (exception) (rate(http_server_requests_seconds_count{status=~"5.."}[5m]))` 能直接出"哪种异常最多"，切到 OTel 约定后这个维度直接消失。要么改用 `error.type`（如果是你手动填的），要么承认"异常分析只能去 trace 里做"。

### Trace 的数据模型差异

| 维度 | Micrometer Tracing（门面） | OTel |
|---|---|---|
| Span 接口 | `Span`：`name` / `tag` / `event` / `error` / `end` / `abandon` | `Span`：`name` / `kind` / `attributes` / `events` / `links` / `status` |
| Kind | 有（`Span.Kind.CLIENT` 等，在 `Tracer.nextSpan()` 上设） | 有（`SpanKind`） |
| 状态 | 只有 `error(Throwable)`，没有 OK/ERROR 枚举 | `Status{code=UNSET/OK/ERROR, description}` |
| 事件 | `event(String)` / `event(String, time, unit)` | `addEvent(name, attributes, timestamp)` |
| **Link** | **没有**（1.7 的接口里不存在） | `Link`，批处理 / fan-in / 跨队列场景必需 |
| 采样 | 不做，委托给 bridge | `Sampler`（`parentbased_traceidratio` 等） |
| Resource | 无此概念 | `Resource` 随每批 span 发送 |

**Link 的缺失是能力差距，不是命名差异。** Kafka 批量消费、多个上游汇入一个下游、异步任务重放这些场景，标准做法是用 `Link` 指向来源 span；Micrometer Tracing 的门面表达不了，只能绕过门面直接用 OTel API（`Span.current().addLink(...)` 之类），或者放弃这段关联。

---

# 六、开源组件全景

## 6.1 Micrometer 家族

### 6.1.1 核心仓库 `micrometer-metrics/micrometer`

| 模块 | 作用 | 什么时候需要 |
|---|---|---|
| `micrometer-core` | 仪表接口 + `MeterRegistry` + 分布统计内核 + 全部 `MeterBinder`（JVM/Tomcat/Jetty/Hikari/Logback/Kafka/Netty…） | 永远需要 |
| `micrometer-observation` | Observation API：`ObservationRegistry`、handler/predicate/filter/convention | 用 Boot 3+ 的观测体系就需要（`core` 已传递依赖） |
| `micrometer-commons` | `KeyValue`/`KeyValues`、注解处理、内部日志 | 被上面两个传递依赖 |
| `micrometer-test` | `MeterRegistryAssert`、`TimerAssert` 等断言 + registry 兼容性 TCK | 写指标单测时 |
| `micrometer-observation-test` | `TestObservationRegistry`、`ObservationRegistryAssert` + Observation TCK | 写观测单测时 |
| `micrometer-bom` | 依赖版本清单 | 多模块工程必加 |

### 6.1.2 注册表（`implementations/`，22 个）

| 类型 | artifact |
|---|---|
| Pull | `micrometer-registry-prometheus`（1.13 起新版，基于 `prometheus-metrics`）、`micrometer-registry-prometheus-simpleclient`（旧版） |
| Push（Step 节奏） | `-datadog`、`-influx`、`-elastic`、`-new-relic`、`-cloudwatch2`、`-dynatrace`、`-azure-monitor`、`-appoptics`、`-signalfx`、`-stackdriver`、`-humio`、`-kairos`、`-opentsdb`、`-ganglia` |
| Push（自定义节奏） | `-otlp` |
| 桥接/其他 | `-graphite`、`-jmx`、`-atlas`、`-statsd`、`-health`（SLO 健康判定） |

### 6.1.3 版本专属小模块

| 模块 | 作用 |
|---|---|
| `micrometer-java11` | JDK 11 `HttpClient` 埋点 |
| `micrometer-java21` | 虚拟线程指标 |
| `micrometer-jakarta9` | Jakarta JMS / Mail 埋点 |
| `micrometer-jetty11` / `-jetty12` | Jetty 版本适配 |

### 6.1.4 独立仓库

| 仓库 / artifact | 作用 |
|---|---|
| `micrometer-metrics/tracing` → `micrometer-tracing` | 追踪门面（`Tracer`/`Span`/`Propagator`/`handler`/注解） |
| ↳ `micrometer-tracing-bridge-brave` | Brave 桥（Zipkin 系） |
| ↳ `micrometer-tracing-bridge-otel` | OTel 桥（**本文主角**） |
| ↳ `micrometer-tracing-test` | `SimpleTracer` 等测试替身 |
| ↳ `micrometer-tracing-bom` | 版本清单 |
| `micrometer-metrics/context-propagation` → `io.micrometer:context-propagation` | `ThreadLocalAccessor`，Reactor/虚拟线程上下文传播 |
| `micrometer-metrics/docs` | Antora 文档源 |
| `micrometer-metrics/micrometer` 的 `samples/`、`benchmarks/` | 示例与 JMH 基准 |

## 6.2 OpenTelemetry 家族

OTel 的组织方式是"一个规范 + 多个仓库"，理解仓库边界比记 artifact 更重要。

| 仓库 | 内容 | 版本节奏 |
|---|---|---|
| `open-telemetry/opentelemetry-specification` | 规范（API/SDK/数据模型/OTLP/Resource/Propagator） | 独立演进 |
| `open-telemetry/semantic-conventions` | 语义约定 YAML + 生成器 | 独立演进 |
| ↳ `io.opentelemetry.semconv:opentelemetry-semantic-conventions` | 生成的 Java 常量 | 跟随 semconv（1.41.0） |
| `open-telemetry/opentelemetry-proto` | OTLP protobuf 定义 | 独立 |
| ↳ `io.opentelemetry.proto:opentelemetry-proto` | Java 绑定 | 1.3.2-alpha |
| `open-telemetry/opentelemetry-java` | API + SDK + 导出器（见下表） | 每月（1.67.0） |
| `open-telemetry/opentelemetry-java-instrumentation` | Java Agent + 库级插桩 + Spring Starter | 每月（2.33.0） |
| `open-telemetry/opentelemetry-java-contrib` | 社区扩展（采样、JMX、运行时挂载…） | 每月（1.57.0） |
| `open-telemetry/opentelemetry-collector` | Collector 核心 | 独立 |
| `open-telemetry/opentelemetry-collector-contrib` | 200+ 组件 | 独立 |
| `open-telemetry/opentelemetry-collector-releases` | 发行版（core / contrib / k8s）与 `ocb` | 独立 |
| `open-telemetry/opentelemetry-operator` | K8s Operator（注入 sidecar、管理 Collector） | 独立 |
| `open-telemetry/opentelemetry-demo` | 官方微服务 Demo（Astronomy Shop） | 独立 |
| `open-telemetry/weaver` | semconv 代码/文档生成工具 | 独立 |
| `open-telemetry/opentelemetry-ebpf-profiler` | eBPF 持续 profiling | 开发中 |

### 6.2.1 `opentelemetry-java` 的构件分组

| 分组 | 代表 artifact | 说明 |
|---|---|---|
| API | `opentelemetry-api` | 只编译期依赖，运行时由 SDK 提供实现 |
| Context | `opentelemetry-context` | `Context`/`Scope`，被 api 传递依赖 |
| SDK | `opentelemetry-sdk`（聚合）、`-sdk-trace`、`-sdk-metrics`、`-sdk-logs`、`-sdk-common` | 参考实现 |
| 自动装配 | `opentelemetry-sdk-extension-autoconfigure`（+ `-spi`） | 环境变量/YAML 装配 SDK |
| OTLP 导出 | `opentelemetry-exporter-otlp`、`-otlp-common`、`-sender-okhttp`、`-sender-jdk` | gRPC/HTTP 发送器 |
| 其他导出 | `-exporter-logging`、`-exporter-prometheus`、`-exporter-zipkin` | — |
| 传播扩展 | `opentelemetry-extension-trace-propagators` | B3 / Jaeger / OT-Trace / X-Ray |
| 采样扩展 | `opentelemetry-sdk-extension-jaeger-remote-sampler` | 远程采样 |
| 测试 | `opentelemetry-sdk-testing` | `InMemorySpanExporter` 等 |
| 迁移垫片 | `opentelemetry-opentracing-shim`、`-opencensus-shim`、`-micrometer1-shim` | 存量迁移 |
| BOM | `opentelemetry-bom`、`opentelemetry-bom-alpha` | **alpha 构件必须用 alpha BOM** |

### 6.2.2 `opentelemetry-java-instrumentation` 的构件分组

| 分组 | 代表 artifact | 说明 |
|---|---|---|
| Java Agent | `opentelemetry-javaagent`（发布物 jar） | `-javaagent` 零代码插桩，**自带 SDK** |
| Agent 扩展 API | `opentelemetry-agent-extension-api` | 自定义 agent 扩展 |
| 库级插桩 | `opentelemetry-spring-webmvc-6.0`、`-jdbc`、`-kafka-clients-3.3`、`-netty-4.1`、`-grpc-1.6`… | 不用 agent，显式装配 |
| Spring Starter | `opentelemetry-spring-boot-starter` | OTel 版 Boot 自动配置 |
| BOM | `opentelemetry-instrumentation-bom`、`-alpha` | 版本对齐 |

### 6.2.3 `opentelemetry-java-contrib` 的高价值件

| 构件 | 用途 |
|---|---|
| `opentelemetry-consistent-sampling` | 一致性概率采样（多服务同一 traceId 决策一致） |
| `opentelemetry-jmx-metrics` | JMX → OTel 指标（老 Java 应用救星） |
| `opentelemetry-runtime-attach` | 运行时 attach agent（容器里免改启动参数） |
| `opentelemetry-span-stacktrace` | 给 Span 附调用栈 |
| `opentelemetry-inferred-spans` | 无埋点推断 Span（基于栈采样） |
| `opentelemetry-micrometer-meter-provider` | OTel API → Micrometer 出口 |
| `opentelemetry-prometheus-client-bridge` | OTel 指标 → Prometheus Java client |
| `opentelemetry-disk-buffering` | 断网时磁盘缓冲遥测数据 |
| `opentelemetry-kafka-exporter` | 经 Kafka 传输遥测 |
| `opentelemetry-samplers` | 自定义采样器集合 |

### 6.2.4 Collector 组件地图

| 类别 | 常用组件 |
|---|---|
| Receiver（收） | `otlp`、`jaeger`、`zipkin`、`prometheus`、`filelog`、`kafka`、`hostmetrics`、`journald` |
| Processor（处理） | `batch`、`memory_limiter`、`attributes`、`filter`、`transform`、`resource`、`tail_sampling`、`probabilistic_sampler` |
| Exporter（发） | `otlp`、`otlphttp`、`prometheus`、`prometheusremotewrite`、`loki`、`kafka`、`debug`、`loadbalancing` |
| Connector（接） | `spanmetrics`（Span→RED 指标）、`servicegraph`（服务拓扑）、`routing`、`count`、`forward` |
| Extension（扩展） | `health_check`、`zpages`、`pprof`、`oauth2client`、`ballast`、`file_storage` |
| 发行版 | `otelcol`（core）、`otelcol-contrib`（全量）、`otelcol-k8s`；Grafana Alloy 也是兼容发行版 |
| 构建工具 | `ocb`（OpenTelemetry Collector Builder）——裁剪自己的发行版 |

## 6.3 桥接件总表（跨生态接口）

| 桥 | 方向 | artifact | 成熟度 |
|---|---|---|---|
| OTel 追踪桥 | Micrometer Tracing → OTel SDK | `io.micrometer:micrometer-tracing-bridge-otel` | GA |
| Brave 追踪桥 | Micrometer Tracing → Brave | `io.micrometer:micrometer-tracing-bridge-brave` | GA |
| 指标桥 | Micrometer `MeterRegistry` → OTel | `io.opentelemetry:opentelemetry-micrometer1-shim` | alpha，**已停更（2022-08）**，改用下一个 |
| 指标桥 | OTel `MeterProvider` → Micrometer | `io.opentelemetry.contrib:opentelemetry-micrometer-meter-provider` | alpha |
| 指标桥 | Micrometer API（含 Observation 指标）→ OTel | `io.opentelemetry.instrumentation:opentelemetry-micrometer-1.5` | alpha |
| 遥测出口 | Micrometer → OTLP | `io.micrometer:micrometer-registry-otlp` | GA |
| Prometheus 出口 | OTel → Prometheus client | `io.opentelemetry.contrib:opentelemetry-prometheus-client-bridge` | alpha |
| 日志桥 | Logback/Log4j2 → OTel Logs | `opentelemetry-logback-appender-1.0`、`opentelemetry-log4j-appender-2.17` | alpha |

## 6.4 后端与周边生态

| 层 | 项目 |
|---|---|
| 追踪后端 | Jaeger、Grafana Tempo、Zipkin、SigNoz、Uptrace、Elastic APM |
| 指标后端 | Prometheus、Grafana Mimir、VictoriaMetrics、Thanos |
| 日志后端 | Loki、Elasticsearch/OpenSearch |
| 一体化 | SigNoz、Uptrace、Grafana LGTM（Loki+Granafa+Mimir+Tempo）、Datadog/Splunk/Dyno 等商业 SaaS（走 OTLP 接入） |
| K8s 集成 | OTel Operator、OTel Helm Chart、Prometheus Operator、Grafana Alloy |

---

# 七、选型决策与迁移路径

## 7.1 决策树

```
你的技术栈是否以 JVM/Spring 为主？
├── 是 ──► 是否需要分布式追踪 / 统一 OTLP 出口？
│           ├── 否（只要指标）──► 形态 A：Micrometer + Prometheus（最简单）
│           └── 是 ──► 形态 C：Micrometer Observation + Tracing bridge-otel + OTLP + Collector
│                      （保留 Actuator 体系，同时拿到 OTel 的传输与语义）
└── 否（多语言混合）
    ├── 需要零代码改造 ──► 形态 D1：OTel Java Agent（Java 侧）+ 各语言 SDK
    └── 可以改代码     ──► 形态 D2：各语言原生 OTel SDK + Collector
```

## 7.2 场景矩阵

| 场景 | 推荐 | 理由 |
|---|---|---|
| 单体 Spring Boot + Prometheus + Grafana | A | 零额外组件，成本最低 |
| Spring Cloud 微服务（10~100 个服务） | C | 保留 `@Observed`/Actuator，同时统一 OTLP |
| 已有 Datadog/New Relic 合同 | A 或 B | 直连商业后端，不必自建 Collector |
| 多语言（Java + Go + Python） | D1/D2 + Collector | 只有 OTel 能统一指标名与传播格式 |
| 需要尾采样（只留错误/慢请求） | 必须引入 Collector | SDK 层做不到 |
| 需要按租户脱敏后再上报 | Collector `attributes`/`transform` | 应用侧不该承担 |
| 强合规、数据不出集群 | Collector Gateway + 自建后端 | OTLP 是唯一开放协议 |
| 遗留系统无法改代码 | D1（agent） | 唯一选择 |

## 7.3 迁移路径：Micrometer 风格 → OTel 语义

如果你决定从"Micrometer 命名"迁到"OTel 语义约定"，按这个顺序做，别一步到位：

1. **先统一出口**：把各 registry 直连改成 `micrometer-registry-otlp` + Collector，后端不变。此时指标名还是 Micrometer 风格，风险最低。
2. **再统一追踪**：加 `micrometer-tracing-bridge-otel` + OTLP exporter，确认 `traceparent` 在全链路贯通（尤其跨语言网关）。
3. **双写验证**：Boot 4.2 之前，用 Collector 的 `metricstransform`/`attributes` 处理器把 Micrometer 指标名映射成 OTel 名，双份数据并存 2~4 周，对比看板差异。
4. **切语义**：Boot 4.2+ 直接开 `management.observations.conventions=opentelemetry`（注意 RC 期拼写差异），或自定义 `ObservationConvention`。
5. **清理**：删除旧看板、旧告警、旧 SLO 定义；检查 `exception` 标签依赖是否已迁移到 `error.type`。
6. **收尾**：评估是否还需要 `publishPercentiles`（Summary 已 legacy），统一改为直方图 + 后端计算分位。

**反向迁移**（OTel → Micrometer）通常只在"放弃 Collector、回归 Prometheus pull"时发生，用 `opentelemetry-micrometer-meter-provider` 即可，但注意它还是 alpha。

---

# 八、十个高频坑

**坑 1：以为引了 `micrometer-registry-otlp` 就是"用了 OpenTelemetry"。**
不是。它只是把 OTLP 当成又一个出口协议，聚合、命名、基数治理全是 Micrometer 的。`telemetry.sdk.name` 会诚实地写着 `io.micrometer`。

**坑 2：需要 OTLP/gRPC，却发现指标发不出去。**
`micrometer-registry-otlp` 只实现 HTTP/protobuf。要 gRPC 就用形态 C（追踪走 OTel SDK 的 gRPC exporter），或自己实现 `OtlpMetricsSender`。

**坑 3：Java Agent + Spring Boot 自带观测 = 双份 Span、双份指标。**
agent 会插桩 Spring MVC，Boot 的 `ServerHttpObservationFilter` 也在插桩，于是一个请求两条链路。解决办法二选一：关掉 agent 的 Spring 插桩（`OTEL_INSTRUMENTATION_SPRING_WEBMVC_ENABLED=false` 等），或干脆不用 agent、走形态 C。混合模式的具体关停清单见 10.8。

**坑 4：Java Agent 与 `opentelemetry-sdk` 依赖共存。**
agent 自带 SDK，再引 SDK 会出现两份 `OpenTelemetry` 实例、两套 exporter，行为不可预测（常见症状：Span 数量翻倍、`GlobalOpenTelemetry` 拿到空实现）。**用 agent 就不要引 SDK。**

**坑 5：传播格式不统一，链路在网关处断掉。**
Brave 传统默认 B3，OTel 默认 W3C。跨团队/跨语言时统一用 `management.tracing.propagation.type=W3C`（或明确列出多种格式做兼容），并在网关验证 `traceparent` 是否能透传。

**坑 6：切 OTel 语义后看板全空。**
`http.server.requests` → `http.server.request.duration`，`uri` → `http.route`，`status` → `http.response.status_code`。指标名和标签同时变了，PromQL 全部失效。**切换前先做双写**。

**坑 7：把高基数标签塞进指标。**
`userId`、`orderId`、原始 URL（含 ID）进标签，Prometheus 直接爆。Micrometer 用 `MeterFilter.maximumAllowableTags` 兜底，OTel 用 View 的 `cardinalityLimit`（默认 2000）折叠成 `otel.metric.overflow`。**兜底不是方案，设计阶段就要控制。**

**坑 8：以为配了采样率，Collector 就不需要了。**
head sampling 在 SDK，尾采样（按错误/耗时决定留不留）只能在 Collector。想"错误全留、正常 1%"，必须上 Collector 的 `tail_sampling`。

**坑 9：Exemplar 配了但看不到。**
Prometheus 需要 OpenMetrics 格式 + 抓取端开启 exemplar 支持；OTel 需要 Span 被采样。两个条件缺一个，指标上的 traceId 就是空的。

**坑 10：版本错配。**
Micrometer 由 Spring Boot BOM 管，OTel 由 `opentelemetry-bom` 管，两者独立。尤其注意：**alpha 构件必须配 `opentelemetry-bom-alpha`**，混用 GA BOM 会拉到不兼容版本；`micrometer-tracing-bridge-otel` 对 OTel SDK 有最低版本要求，升级 Boot 时不要手工锁死 OTel 版本。

---

# 九、速查表与结论

## 9.1 一页速查

| 问题 | 答案 |
|---|---|
| Micrometer 是什么 | JVM 应用指标门面 + 计量实现 + Observation 统一埋点 API |
| OTel 是什么 | 跨语言遥测标准 + SDK + OTLP 协议 + Collector 管道 |
| 谁实现追踪 | OTel SDK / Brave；Micrometer 只做门面 |
| 谁实现指标聚合 | 两者都实现；Micrometer 在形态 B/C 里仍是聚合方 |
| 谁定义指标名 | 默认 Micrometer 命名；可切 OTel semconv（Boot 4.2+） |
| 谁负责传输 | OTLP；Micrometer 自己编码，OTel 提供模型与 exporter |
| 谁负责汇聚加工 | OTel Collector（可选但强烈推荐） |
| 能不能只用 Micrometer | 能，指标场景完全够用 |
| 能不能只用 OTel | 能，但要接受放弃 Actuator 指标体系 |
| Spring Boot 推荐组合 | Micrometer Observation + Tracing bridge-otel + OTLP + Collector |

## 9.2 结论

**它们不是替代关系，而是"门面与标准"的分层协作。** Micrometer 把 JVM 世界的埋点做得足够薄、足够贴 Spring；OTel 把跨语言的语义、协议、管道做得足够标准。Spring Boot 3/4 的组合恰好是两者的最大公约数：用 Micrometer 写代码，用 OTel 传数据。

选型时只需回答三个问题：

1. **是否需要跨语言统一？** 是 → OTel 必须在场（至少作为传输与语义层）。
2. **是否需要零代码改造？** 是 → Java Agent，但要做好与 Boot 观测二选一的准备。
3. **是否只需要指标 + 已有 Prometheus？** 是 → 纯 Micrometer，别为了"架构先进"引入不必要的 Collector。

最后提醒一句：**这两套体系的迁移成本不在代码，而在看板、告警和 SLO 定义。** 指标名和标签一改，所有下游查询都要重建。技术上一天能切完，组织上可能要一个季度。

---

# 十、OpenTelemetry Java Agent 深度解析

> 这一章是 3.2(5) 与 4.4-D1 的展开。前九章把它当成"一个选项"，这里把它当成**一个独立产品**拆开：装了什么、怎么织入、能出哪些信号、配置怎么给、和 Spring Boot 怎么共存、3.0 会改什么。
>
> **基线**：`opentelemetry-javaagent 2.33.0`（2026-10-23）。本章中的体积、文件数、插桩条目数、注册模块数均为对该版本发行包的**实测值**，不是文档转述。

## 10.1 它到底是什么：一个 26MB、自带 SDK 的字节码插件包

打开 2.33.0 的发行包（`io.opentelemetry.javaagent:opentelemetry-javaagent:2.33.0`，GitHub Releases 也提供同名 jar）可以看到：

| 观测项 | 实测值 | 说明 |
|---|---|---|
| jar 体积 | 26 MB | 插桩模块 + OTel SDK + exporter + ByteBuddy 全在里面 |
| `.classdata` 文件 | 14,229 | agent 自己的类，故意改扩展名 |
| `.class` 文件 | 1,172 | 少数必须留在常规位置的类 |
| `InstrumentationModule` 注册数 | 317 | 实际可加载的插桩模块（含 API 桥的多版本副本） |
| MANIFEST | `Main-Class`/`Premain-Class`/`Agent-Class` 均为 `io.opentelemetry.javaagent.OpenTelemetryAgent`；`Can-Retransform-Classes: true` | 启动期织入，且具备 retransform 能力 |
| 顶层结构 | `inst/` 目录 + `META-INF/` | `inst/` 下还有 `META-INF/services` 等 |

一句话：**它不是"给 SDK 加个壳"，而是一个把 SDK、导出器、传播器、字节码引擎和三百多个插桩模块压进一个 jar 的运行时。**

这也是"用 agent 就不能再引 `opentelemetry-sdk`"这条铁律的物理原因——一个 JVM 里会出现两套 `OpenTelemetry` 实例、两套 exporter、两套 Resource（见第八章坑 4）。

和 library instrumentation（`opentelemetry-spring-webmvc-6.0` 这类依赖）的分工：

| 维度 | Java Agent | Library instrumentation |
|---|---|---|
| SDK 由谁装 | agent 自带 | 应用自己引 `opentelemetry-sdk` 并装配 |
| 代码改动 | 零（改启动参数） | 加依赖 + 写装配 |
| 覆盖范围 | 整个 JVM，包括第三方 jar 内部 | 只有显式接入的库 |
| GraalVM native | 基本不可用 | 可用 |
| 配置入口 | `OTEL_*` / `-D` / YAML | Spring 配置或代码 |
| 升级影响面 | 整个进程 | 单个依赖 |
| 可调试性 | 2.x inlined 模式不可断点；3.0 indy 后可断点 | 正常调试 |

## 10.2 一次启动发生了什么：四层类加载

agent 的类不是随便塞进应用的 classpath，而是按"谁能看见"严格分层：

| 类加载器 | 放什么 | 关键模块 / 类 |
|---|---|---|
| System CL | **只有一个**入口类 | `io.opentelemetry.javaagent.OpenTelemetryAgent`（实现 `premain`） |
| Bootstrap CL | 必须全局可见的 | `javaagent-bootstrap`、`instrumentation-api`(+`-incubator`)、`instrumentation-annotations-support`、shaded OTel API |
| Agent CL | 绝大部分实现 | `javaagent-tooling`（起 SDK、装 `ClassFileTransformer`）、`muzzle`、全部插桩模块、OTel SDK + exporter、ByteBuddy |
| Extension CL | 每个扩展一个 | `otel.javaagent.extensions` 指定的 jar |

两个容易被忽略、但决定"为什么它不把应用搞崩"的设计：

**（1）`.classdata` 反扫描。** agent 的类全部挪进 `inst/`，并把 `.class` 改名为 `.classdata`。这样 Spring 的 `classpath*:` 扫描、JPA 实体扫描、Shade 插件、应用服务器的类扫描器都看不见它们。隔离不是文档口号，是落到了文件扩展名这一层。

**（2）API 桥（API bridge）。** 应用里如果自己引了 `opentelemetry-api` 并调 `GlobalOpenTelemetry.getTracer()`，拿到的其实是 **agent 的 SDK**，而不是空实现。做法是给每个 API 版本编一个桥模块——2.33.0 里实测存在这些桥：

```
v1_0  v1_4  v1_10  v1_15  v1_27  v1_32  v1_38  v1_42
v1_50 v1_57 v1_59  v1_63
（外加 v1_31/v1_32/v1_37/v1_40/v1_47/v1_50/v1_56/v1_63/v1_65 的 incubator 变体）
```

机制是：检测应用 classpath 上的 API 版本 → 启用对应版本的桥。**版本对不上时不生效，而不是报错**——宁可丢自定义埋点，也不让应用 `NoSuchMethodError`。这与 10.6 的 muzzle 是同一套价值观。

织入策略有两代，这也是 3.0 的破坏点之一：

| 维度 | inlined（2.x 默认） | invokedynamic（`otel.javaagent.experimental.indy=true`，3.0 默认） |
|---|---|---|
| Advice 代码 | 复制进目标方法体 | 不复制，通过 `invokedynamic` 调独立类加载器里的 advice |
| 引用类的可见性 | 必须在目标类的 CL 可见 | advice 在自己的隔离 CL，可用任意类型 |
| IDE 断点 | 不行 | 可以 |
| JPMS | 继承被插桩代码的访问权 | 需显式授权 |
| Java 7 及更早字节码 | 无限制 | 用生成的 `invokestatic` helper 兜底 |
| 插桩 `ClassLoader` 自身 | 支持 | 不支持（这些模块保持 inlined） |

> 为什么值得关心：indy 让"自己写插桩"从"必须 fork agent"变成"写一个普通 jar"。3.0 一上，自定义扩展的加载方式会变，维护发行版的团队必须先测。

## 10.3 覆盖面：256 个条目、244 个带 agent 实现、12 个默认关闭

数据取自 `docs/instrumentation-list.yaml`（v2.33.0，官方生成的机器可读清单）。按领域抽样：

| 领域 | 典型插桩名（`otel.instrumentation.<name>.enabled` 里的 `<name>`） |
|---|---|
| HTTP 服务端 | `servlet`、`tomcat`、`jetty`、`undertow`、`netty`、`spring-webmvc`、`spring-webflux`、`spring-cloud-gateway`、`jaxrs`、`vertx-web`、`armeria` |
| HTTP 客户端 | `java-http-client`、`http-url-connection`、`apache-httpclient`、`okhttp`、`reactor-netty`、`jetty-httpclient`、`async-http-client` |
| 数据库 | `jdbc`、`jdbc-datasource`、`r2dbc`、`mongo`、`cassandra`、`couchbase`、`elasticsearch-rest`、`clickhouse`、`hibernate`（3.0 起默认关） |
| 缓存 | `lettuce`、`jedis`、`redisson`、`spymemcached`、`vertx-redis-client` |
| 消息 | `kafka`、`spring-kafka`、`rabbitmq`、`spring-rabbit`、`jms`、`spring-jms`、`pulsar`、`rocketmq-client`、`spring-pulsar`、`nats` |
| RPC | `grpc`、`apache-dubbo`、`thrift`、`finagle-http`、`axis2`、`cxf` |
| 框架/运行时 | `spring-core`、`spring-scheduling`、`spring-security-config`、`executors`、`kotlinx-coroutines`、`reactor`、`quartz`、`xxl-job` |
| 日志 | `logback-appender`、`logback-mdc`、`log4j-appender`、`log4j-context-data`、`java-util-logging` |
| JVM | `runtime-telemetry`（JMX + JFR） |

**12 个默认关闭的插桩**（这几位要么会产生重复遥测，要么噪音大于价值）：

```
micrometer-1.5                          spring-boot-actuator-autoconfigure-2.0
mybatis-3.2                             spring-batch-3.0
spring-ws-2.0                           apache-commons-pool-2.0
dropwizard-metrics-4.0                  jaxrs-1.0
jaxrs-2.0-annotations                   jaxrs-3.0-annotations
jaxws-jws-api-1.1                       vertx-kafka-client-3.6
```

注意"默认关闭"不等于"没有"：`-Dotel.instrumentation.micrometer.enabled=true` 即可打开。排查"为什么这个库没 span"时，第一步就该查它是不是在这张名单里。

Spring Boot 应用最相关的一小组，单独列一下（默认状态很重要）：

| 插桩名 | 作用 | 默认 |
|---|---|---|
| `spring-webmvc` / `spring-webflux` | HTTP server span + `http.server.request.duration`，能拿到 `http.route` | 开 |
| `spring-web` | 客户端侧（`RestTemplate`/`WebClient` 的语义补充） | 开 |
| `spring-cloud-gateway` | 网关路由 span | 开 |
| `spring-core` | `@Async` 等任务执行的上下文传播，**本身不产遥测** | 开 |
| `spring-scheduling` | `@Scheduled` 方法 span | 开 |
| `spring-kafka` / `spring-rabbit` / `spring-jms` | 消息消费链路（含拦截器自动装配） | 开 |
| `spring-security-config` | 认证主体相关属性（3.0 里 `enduser.*` → `user.*`） | 开 |
| `spring-boot-autoconfigure` | 把上面这些装配进 Boot，**本身不产遥测**；官方注释写明"主要给 Spring Boot Starter 用" | 开 |
| `spring-boot-actuator-autoconfigure` | 把 Actuator 指标接进 Micrometer 桥，**本身不产遥测** | **关** |
| `micrometer-1.5` | 提供一个 OTel 后端的 `MeterRegistry`，把 Micrometer 埋点转成 OTel 指标 | **关** |
| `spring-batch-3.0` | Batch job/step span | **关** |

## 10.4 三个信号的真实能力边界

**Traces：自动拓扑是主菜**

典型一次下单的自动 span 结构：

```
SERVER   GET /api/orders/{id}        http.route, http.response.status_code
├─ CLIENT  GET inventory-service     server.address, url.template
├─ CLIENT  SELECT orders             db.system.name, db.namespace, db.query.text
└─ PRODUCER send orders              messaging.*（3.0 起形状变化，见 10.9）
```

三层去重靠 **span suppression**：

| 策略 | 行为 | 何时用 |
|---|---|---|
| `semconv`（默认） | 按语义约定去重：DB 客户端内部的 reactor-netty、netty 不再重复出 HTTP span | 生产默认 |
| `span-kind` | 同 kind 只留一个（INTERNAL 除外） | 想让链路更"瘦" |
| `none` | 全留 | 只用于调试，会产生大量重复 |

另外两个默认关的开关值得一提：`otel.instrumentation.common.experimental.controller-telemetry.enabled` 与 `...view-telemetry.enabled`。它们控制 Spring MVC 的 controller/view 内部 span——默认关闭，因为对多数人只是噪音，而 `http.route` 仍然由 server span 提供。

**Metrics：只出"插桩自带的指标"，不是通用指标系统**

agent 能自动产出的指标只有两类：

1. 插桩自带：`http.server.request.duration`、`http.client.request.duration`、`rpc.server.duration`、`db.client.connection.*` 等（以 semconv 为准）。
2. 运行时指标（`runtime-telemetry`）：Java 8+ 走 JMX、Java 17+ 走 JFR，覆盖 `jvm.memory.*`、`jvm.gc.duration`、`jvm.thread.count`、`jvm.class.*`、`jvm.cpu.*`。实验项另有 `emit-experimental-metrics`、`emit-experimental-jfr-metrics`。

**业务指标它一个都不会替你做。** 需要业务指标时只有两条路：自己用 OTel API 埋，或者启用 `micrometer` + `spring-boot-actuator-autoconfigure` 这两个桥（见 10.8）。

**Logs：桥接 + 关联，两件事要分开**

- **导出**：`logback-appender` / `log4j-appender` 把日志事件转成 OTel `LogRecord`。代价是 `capture-*` 全默认关闭——`capture-arguments`、`capture-key-value-pair-attributes`、`capture-logger-context-attributes`、`capture-mdc-attributes`、`capture-template`、`capture-code-attributes` 逐个开，开多了日志体积翻倍。
- **关联**：MDC 注入三个键 `trace_id`、`span_id`、`trace_flags`。Spring Boot（logback）只需改一行：

```properties
logging.pattern.level = trace_id=%mdc{trace_id} span_id=%mdc{span_id} trace_flags=%mdc{trace_flags} %5p
```

判断标准：日志量可控、且需要"按 trace 聚合"时再开导出；量大就先在 Collector 侧过滤采样，别直接怼后端。

## 10.5 配置体系：四级优先级、四条通道

优先级（高 → 低）：

```
系统属性 -D  >  环境变量 OTEL_*  >  配置文件  >  AutoConfigurationCustomizer#addPropertiesSupplier()（SPI）
```

四条通道：

| 通道 | 写法 | 适用 |
|---|---|---|
| 系统属性 | `-Dotel.service.name=order-service` | 本地、容器 `JAVA_TOOL_OPTIONS` |
| 环境变量 | `OTEL_SERVICE_NAME=order-service` | K8s、12-factor |
| properties 文件 | `-Dotel.javaagent.configuration-file=/etc/otel/agent.properties` | 配置项多、想集中管理 |
| 声明式 YAML（实验） | `-Dotel.config.file=/etc/otel/otel-config.yaml` | 3.0 的方向，可与 Collector 配置同构 |

环境变量命名规则：全大写，`.` 和 `-` 都换成 `_`。例如 `otel.instrumentation.common.default-enabled` → `OTEL_INSTRUMENTATION_COMMON_DEFAULT_ENABLED`。

常用配置分组成表：

| 分类 | 属性 | 默认 / 说明 |
|---|---|---|
| 资源 | `otel.service.name` | 无默认，**必须显式给** |
| 资源 | `otel.resource.attributes=deployment.environment=prod,service.version=1.2.3` | 逗号分隔 k=v |
| 资源 | `otel.resource.providers.aws.enabled` / `gcp` / `azure` | `false`，要用云资源探测才开 |
| 导出 | `otel.exporter.otlp.endpoint` | `http://localhost:4318` |
| 导出 | `otel.exporter.otlp.protocol` | `http/protobuf`（**2.0 起，不是 gRPC**） |
| 导出 | `otel.exporter.otlp.headers` / `.timeout` / `.compression` | 认证头、超时、gzip |
| 信号 | `otel.traces.exporter` / `otel.metrics.exporter` / `otel.logs.exporter` | `otlp`；日志需 appender 才有数据 |
| 采样 | `otel.traces.sampler=parentbased_traceidratio` + `otel.traces.sampler.arg=0.1` | 默认 `parentbased_always_on` |
| 传播 | `otel.propagators=tracecontext,baggage` | 跨语言务必保持 W3C |
| 批量 | `otel.bsp.schedule.delay=5000`、`otel.bsp.max.queue.size=2048`、`otel.bsp.max.export.batch.size=512` | span 批量导出 |
| 指标 | `otel.metric.export.interval=60000`、`otel.exporter.otlp.metrics.temporality.preference=cumulative` | 与 Micrometer 的 step 对齐时要注意（见 5.1） |
| agent 自身 | `otel.javaagent.enabled` | `true`，设为 `false` 可整体关停 |
| agent 自身 | `otel.javaagent.debug` | `false`，排查时开，日志很吵 |
| agent 自身 | `otel.javaagent.logging` | `simple`（stderr）/ `none` / `application`（转进应用 slf4j） |
| 插桩开关 | `otel.instrumentation.<name>.enabled` | 见 10.3 的名字表 |
| 扩展 | `otel.javaagent.extensions` | jar 或目录 |
| 3.0 预览 | `otel.instrumentation.common.v3-preview`、`otel.semconv-stability.opt-in`、`otel.semconv-stability.preview` | 见 10.9 |

一段可以直接用的启动命令（走 Collector，不直连后端）：

```bash
java -javaagent:/opt/otel/opentelemetry-javaagent.jar \
  -Dotel.service.name=order-service \
  -Dotel.resource.attributes=deployment.environment=prod \
  -Dotel.exporter.otlp.endpoint=http://otel-collector:4318 \
  -Dotel.exporter.otlp.protocol=http/protobuf \
  -Dotel.traces.sampler=parentbased_traceidratio \
  -Dotel.traces.sampler.arg=0.1 \
  -Dotel.instrumentation.spring-scheduling.enabled=false \
  -Dotel.javaagent.logging=application \
  -jar order-service.jar
```

对应的声明式 YAML（3.0 方向，当前仍是实验）：

```yaml
file_format: "1.0"
resource:
  attributes:
    - name: service.name
      value: order-service
    - name: deployment.environment
      value: prod
tracer_provider:
  sampler:
    parent_based:
      root:
        trace_id_ratio_based:
          ratio: 0.1
propagator:
  composite:
    - tracecontext
    - baggage
```

## 10.6 安全网：muzzle 才是它能上生产的原因

一个字节码 agent 最怕的不是"没数据"，而是"把应用搞挂"。OTel 用两层 muzzle + 一层隔离来兜底：

| 阶段 | 做什么 | 失败时的表现 |
|---|---|---|
| 编译期（muzzle check） | 收集插桩对目标库的所有引用（类/方法/字段），到 Maven 上**随机抽版本**验证引用都存在；开区间版本（如 `[4.1,)`）的验证上界用仓库里 pinned 的 latest 版本封顶，避免上游发新版把 CI 打红 | CI 失败，发不出包 |
| 运行期（muzzle runtime） | 把同一份引用集合写进 agent，启动时比对你应用里实际的库版本；缺引用就**不加载该插桩** | 该库没有 span，应用正常启动 |
| 类加载隔离 | `.classdata` + shading + 每扩展独立 CL | 不会与应用的依赖打架 |

**排查第一原则**：某个库没有 span 时，先看 debug 日志里有没有 `Did not apply instrumentation` 之类的行——这通常是 muzzle 主动放弃，而不是你的配置写错了。

配套还有 Docker 冒烟矩阵：多 JVM、多应用服务器（Tomcat/Jetty/WildFly/Liberty…）各跑一遍。第三方 agent 出事大多出在"某个应用服务器的类加载器上"，这套矩阵就是为它准备的。

## 10.7 开销：不要背别人的数字

官方立场很明确：**给不出统一的开销数字**，因为取决于机器、JVM、容器、依赖、采样率、日志量。仓库自带 `benchmark-overhead` 子项目与 `results.csv`，要数字就自己跑。

可以当"量级参考"的历史数据（1.14 时代）：启动耗时约 +9%、内存约 +17%、GC 时间约 +8%、循环吞吐影响约 1%。版本已过去两年多，只能用来判断"是不是同一个数量级"，不能写进 SLA。

降开销的六个手段（按性价比排序）：

1. **采样**：入口 10%，错误全留交给 Collector 的 `tail_sampling`。
2. **关掉不需要的插桩**：`spring-scheduling`、`executors`、`apache-commons-pool` 这类先关。
3. **加堆**：插桩会产生大量短命对象，`-Xmx` 给足比调参有效。
4. **别乱用 `@WithSpan`**：逐方法打点会让 span 数量指数上升，且很多方法本身比埋点还快。
5. **关 debug**：`otel.javaagent.debug=true` 在高并发下会显著拖慢。
6. **Collector 侧调**：`memory_limiter` + `batch` 没配好，agent 侧再怎么调都白费。

自己量的时候注意：必须预热（JIT）、看 p95/p99 而不是均值、错误率控制在 2%~10%、同环境单变量对比。

## 10.8 与 Spring Boot 4 共存：三种装法与三条硬规则

这是本文档读者最可能踩的坑，单独成表：

| 维度 | Boot starter（形态 C） | 纯 agent（形态 D1） | 混合：Boot 观测 + agent 补盲区 |
|---|---|---|---|
| 指标归属 | Micrometer | agent 内置 OTel SDK | Micrometer（agent 另发自己那份） |
| 追踪实现 | Micrometer Tracing → OTel SDK | agent SDK | 两套，必须关掉 agent 的 web 插桩 |
| 日志 | Boot 的 logback appender | agent 的 `logback-appender` | 二者只能留一个 |
| 配置入口 | `management.*` | `OTEL_*` / `-D` | 两套并存，最容易失控 |
| 与 Actuator 关系 | 原生 | 需显式开两个桥插桩 | 原生 |
| 适合谁 | 新项目、指标要看 Actuator | 存量系统、多语言、零改造 | 遗留库多（JDBC/MQ），只想补盲区 |

**三条硬规则：**

1. **agent + `spring-boot-starter-opentelemetry` = 两套 SDK、两套 span。** 不是"叠加增强"，是重复上报。二选一。
2. **想让 Actuator 指标进 OTel，必须显式开两个默认关闭的插桩：**

```bash
-Dotel.instrumentation.micrometer.enabled=true
-Dotel.instrumentation.spring-boot-actuator-autoconfigure.enabled=true
```

   官方把这两个设成默认关，理由写在元数据里："may produce metrics that overlap with metrics already captured by other instrumentations"。打开后要接受 HTTP 指标可能与 agent 自带的那份重叠；`otel.instrumentation.micrometer.prometheus-mode.enabled=true` 可以让命名对齐 Micrometer 的 Prometheus 风格。

3. **用 agent 就别在应用里引 `opentelemetry-sdk`。** `opentelemetry-api` 可以引（走 10.2 的 API 桥），SDK 不行。

**混合模式的关停清单**（保留 JDBC/Kafka/Redis 这些 Boot 观测覆盖不到的盲区）：

```bash
# 关掉 agent 的服务端/客户端插桩，把 HTTP 语义交给 Boot 观测
-Dotel.instrumentation.spring-webmvc.enabled=false
-Dotel.instrumentation.spring-webflux.enabled=false
-Dotel.instrumentation.spring-web.enabled=false
-Dotel.instrumentation.spring-boot-autoconfigure.enabled=false
-Dotel.instrumentation.servlet.enabled=false
# 保留：jdbc / kafka / lettuce / jedis / mongo / aws-sdk / grpc ...
```

> 注意副作用：关掉 `servlet` 后 `http.route` 也没了。如果 Boot 观测负责 server span 就没问题；两边都关，链路会只剩 client span、没有根。

## 10.9 3.0 路线图：这次升级会改什么

截至 2026-10-31，最新稳定版是 **2.33.0**；`main` 已是 `3.0.0-SNAPSHOT`，3.0 目标 2026-10，**2.32.0 被官方当作 3.0 的候选版**用于预览。变更集中在四块：

| 变更 | 具体表现 | 影响 |
|---|---|---|
| database / code 语义约定转正 | `db.system` → `db.system.name`（值也变：`mssql` → `microsoft.sql_server`）；`db.name` → `db.namespace`（库\|schema，如 `orders\|public`）；`db.statement` → `db.query.text`；`code.namespace`+`code.function` → `code.function.name` | 所有 SQL/DB 看板查询失效 |
| 单位与命名收敛 | 连接池时长指标 **ms → s**；`db.client.connections.max` → `db.client.connection.limit` | 阈值要换算，不只是改名 |
| messaging 约定升级（v1.24 → v1.43） | span 名 `orders publish` → `send orders`；process span 的父子关系变：消费者侧有活动 span 时成为其子 span，只 link 生产 span | 链路形状变，按服务聚合的图会变 |
| 默认值与能力调整 | Hibernate、Hystrix、Twilio 三个插桩**改为默认关闭**；Zipkin exporter 移除（预览模式下用它会导致启动失败）；**indy 成为默认** | 会"静默少 span"，双发也发现不了 |

身份类属性也一起改：`enduser.id` → `user.name`，`enduser.role` → `user.roles`（字符串变数组），`enduser.scope` 不再采集；开关相应改为 `otel.instrumentation.common.user.name.enabled` / `...user.roles.enabled`。

**迁移三步（官方推荐）**：

```bash
# 1) 采基线：先不加任何预览开关，导出当前遥测
# 2) 双发：新旧属性同时出，用于对照
OTEL_INSTRUMENTATION_COMMON_V3_PREVIEW=false
OTEL_SEMCONV_STABILITY_OPT_IN=database/dup,code/dup
OTEL_SEMCONV_STABILITY_PREVIEW=messaging/dup
# 3) 开总开关，验证只有新约定时的看板与告警
OTEL_INSTRUMENTATION_COMMON_V3_PREVIEW=true
```

> 双发不是万能的：**span 只有一个名字和一种 kind**，`/dup` 期间用的是新约定；连接池指标即使开了 `database/dup` 也只出新名新单位。所以必须拿基线逐项比"名字、类型、值、单位"四件事，而不是只看名字对不对得上。

## 10.10 扩展与自定义发行版

agent 的扩展点不是"改源码"，而是 SPI + 独立 jar：

| 项目 | 坐标 / 用法 |
|---|---|
| 扩展 API | `io.opentelemetry.javaagent:opentelemetry-javaagent-extension-api:2.33.0-alpha`（groupId 是 `io.opentelemetry.javaagent`，且只发 `-alpha` 版本） |
| 装载 | `-Dotel.javaagent.extensions=/path/ext.jar`（指向目录时，目录内每个 jar 各自独立加载） |
| 打包进发行版 | 把扩展 jar 放进 agent jar 的 `extensions/` 目录，做一个"定制发行版" |
| 编译期工具 | Gradle 插件 `io.opentelemetry.instrumentation.muzzle-generation` / `muzzle-check`（与 agent 同版本，`2.33.0-alpha`） |
| BOM | `io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom`(+`-bom-alpha`) |

2.33.0 的 jar 里实际注册了这些 SPI（`inst/META-INF/services/` 下可见）：`InstrumentationModule`、`AgentListener`、`IgnoredTypesConfigurer`、`AgentExtension`、`ExperimentalJmxMetricHandler`，以及 SDK 侧的 `AutoConfigurationCustomizerProvider`。

典型扩展场景：

- 自定义 `Sampler`（比如按租户采样）、自定义 `IdGenerator`；
- 自定义 `SpanProcessor` 做脱敏（把 `db.query.text` 里的手机号抹掉）；
- 把公司内部框架包一层 `InstrumentationModule`（这才是 indy 真正的价值）；
- 在 `AgentListener` 里做启动自检（版本、配置、连通性）。

闭坑提醒：扩展里对 `instrumentation-api` 用 `compileOnly`（运行时由 agent 提供），但第三方依赖必须 shadow 进 fat jar；否则运行时 `NoClassDefFoundError` 且很难定位。

## 10.11 落地清单

1. **固定版本 + 校验**：从 Releases 下载并核对 sha256，别用 `latest`。
2. **资源三件套**：`service.name`、`deployment.environment`、`service.version`。
3. **只连 Collector**：agent 不直连后端，出口可替换性全靠这一层。
4. **采样分层**：入口按比例，错误全留交给 Collector `tail_sampling`。
5. **先减噪再上线**：`spring-scheduling`、`executors`、`apache-commons-pool` 视情况关闭。
6. **agent 日志进应用通道**：`otel.javaagent.logging=application`（先在测试环境验证）。
7. **`@WithSpan` 只补关键业务方法**，不要全量。
8. **与 Boot 观测二选一**；混合模式按 10.8 清单关插桩。
9. **升级前跑 `/dup`**：拿基线比名字/类型/值/单位四项。
10. **性能基线**：发布前后各跑一次 `benchmark-overhead`，别抄网上的数字。

---

# 附录 A：依赖坐标速查

```groovy
// ── Micrometer 核心 ─────────────────────────────
implementation platform('io.micrometer:micrometer-bom:1.17.0')
implementation 'io.micrometer:micrometer-core'
implementation 'io.micrometer:micrometer-observation'

// ── Micrometer 出口 ─────────────────────────────
implementation 'io.micrometer:micrometer-registry-prometheus'
implementation 'io.micrometer:micrometer-registry-otlp'

// ── Micrometer Tracing 门面 + OTel 桥 ───────────
implementation platform('io.micrometer:micrometer-tracing-bom:1.7.0')
implementation 'io.micrometer:micrometer-tracing-bridge-otel'

// ── OTel SDK 与导出 ─────────────────────────────
implementation platform('io.opentelemetry:opentelemetry-bom:1.67.0')
implementation 'io.opentelemetry:opentelemetry-api'
implementation 'io.opentelemetry:opentelemetry-sdk'
implementation 'io.opentelemetry:opentelemetry-exporter-otlp'
// 注意：alpha 构件（shim 等）改用 opentelemetry-bom-alpha

// ── 上下文传播 ──────────────────────────────────
implementation 'io.micrometer:context-propagation:1.2.1'

// ── Java Agent 路线（与上面的 SDK 依赖互斥）─────
// 发行包：GitHub Releases 下载 opentelemetry-javaagent.jar（26MB，内含全部插桩）
// 同一份发行包也可从 Maven 取：
//   io.opentelemetry.javaagent:opentelemetry-javaagent:2.33.0
// 自定义扩展：groupId 是 javaagent，且只发 -alpha 版本
compileOnly 'io.opentelemetry.javaagent:opentelemetry-javaagent-extension-api:2.33.0-alpha'
compileOnly 'io.opentelemetry.instrumentation:opentelemetry-instrumentation-api'
// 注解：运行时由 agent 的 API 桥提供实现
implementation 'io.opentelemetry.instrumentation:opentelemetry-instrumentation-annotations:2.33.0'

// ── 测试 ────────────────────────────────────────
testImplementation 'io.micrometer:micrometer-test'
testImplementation 'io.micrometer:micrometer-observation-test'
testImplementation 'io.opentelemetry:opentelemetry-sdk-testing'
```

```yaml
# Spring Boot 3.x / 4.x 关键配置
management:
  observations:
    conventions: micrometer      # 4.2+ 可切 opentelemetry（RC 期拼写有差异，升级前验证）
  metrics:
    distribution:
      percentiles-histogram.http.server.requests: true
      slo.http.server.requests: 10ms,50ms,100ms,500ms,1s
  tracing:
    sampling:
      probability: 0.1
    propagation:
      type: W3C
  otlp:
    metrics:
      export:
        url: http://collector:4318/v1/metrics
        step: 30s
    tracing:
      endpoint: http://collector:4318/v1/traces
```

# 附录 B：参考链接

**Micrometer**

- 官方文档：https://docs.micrometer.io/micrometer/reference/
- OTLP registry：https://docs.micrometer.io/micrometer/reference/implementations/otlp.html
- 源码：https://github.com/micrometer-metrics/micrometer
- Tracing：https://github.com/micrometer-metrics/tracing
- Context Propagation：https://github.com/micrometer-metrics/context-propagation

**OpenTelemetry**

- 官网：https://opentelemetry.io/
- Java 入门：https://opentelemetry.io/docs/languages/java/intro
- Java 插桩生态（含桥接件清单）：https://opentelemetry.io/docs/languages/java/instrumentation
- Java SDK 源码：https://github.com/open-telemetry/opentelemetry-java
- Java Instrumentation：https://github.com/open-telemetry/opentelemetry-java-instrumentation
- Java Contrib：https://github.com/open-telemetry/opentelemetry-java-contrib
- Java Agent 文档：https://opentelemetry.io/docs/zero-code/java/agent/
- Java Agent 配置项：https://opentelemetry.io/docs/zero-code/java/agent/configuration/
- Java Agent 3.0 预览：https://opentelemetry.io/blog/2026/java-agent-3.0-preview
- 插桩清单（机器可读，含默认开关）：https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/docs/instrumentation-list.yaml
- Agent 类加载结构：https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/docs/contributing/javaagent-structure.md
- Muzzle 安全机制：https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/docs/safety-mechanisms.md
- Agent 特性清单：https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/main/docs/agent-features.md
- Collector：https://opentelemetry.io/docs/collector/
- 组件注册表：https://opentelemetry.io/ecosystem/registry/
- 语义约定：https://opentelemetry.io/docs/specs/semconv/

**同目录相关文档**

- [Micrometer.md](Micrometer.md)：计量模型、注册表、分布统计、发布机制、Observation 的源码级解析
- [Micrometer Tracing.md](Micrometer%20Tracing.md)：追踪门面、handler 翻译层、Brave/OTel 双桥、Spring Boot 装配
- [Spring观测体系设计.md](Spring观测体系设计.md)：落地视角的观测体系设计（指标/链路/日志/Arthas）
