# OpenTelemetry 全面分析：规范、双仓库源码与 SpringBoot 4.1.1 实战（小白友好）

> 结合本地源码 `D:\code\3rd\opentelemetry-java`（v1.66.0，2026-09）与 `opentelemetry-java-instrumentation`（v2.31.1，2026-08），从规范 → 架构 → 源码 → Spring Boot 4.1.1 落地的完整分析。
> 所有引用的类名、文件路径、配置项均直接取自本地仓库源码，可对照阅读。

---

## 目录

- [一、全景定位：规范、两个仓库与你的应用](#一全景定位规范两个仓库与你的应用)
  - [1.1 一张图看清三方关系](#11-一张图看清三方关系)
  - [1.2 两个仓库的分工](#12-两个仓库的分工)
  - [1.3 版本基线与阅读前提](#13-版本基线与阅读前提)
- [二、OTel 规范速览：从规范到 Java 代码的映射](#二otel-规范速览从规范到-java-代码的映射)
  - [2.1 规范要解决什么](#21-规范要解决什么)
  - [2.2 两条铁律：API/SDK 分离、无后端可运行](#22-两条铁律apisdk-分离无后端可运行)
  - [2.3 规范 → Java 实现的对应总表](#23-规范--java-实现的对应总表)
- [三、opentelemetry-java 源码精读：API 与 SDK](#三opentelemetry-java-源码精读api-与-sdk)
  - [3.1 仓库模块地图](#31-仓库模块地图)
  - [3.2 API 层：三个包加一个门面](#32-api-层三个包加一个门面)
  - [3.3 SDK Trace 管线：Span 的一生](#33-sdk-trace-管线span-的一生)
  - [3.4 SDK Metrics 管线：从测量到导出](#34-sdk-metrics-管线从测量到导出)
  - [3.5 SDK Logs 管线与 profiles 新模块](#35-sdk-logs-管线与-profiles-新模块)
  - [3.6 装配点：OpenTelemetrySdk 与 autoconfigure](#36-装配点opentelemetrysdk-与-autoconfigure)
  - [3.7 Exporters：OTLP 双协议与 sender 抽象](#37-exportersotlp-双协议与-sender-抽象)
  - [3.8 Context 与跨进程传播](#38-context-与跨进程传播)
- [四、opentelemetry-java-instrumentation 源码精读：自动化插桩](#四opentelemetry-java-instrumentation-源码精读自动化插桩)
  - [4.1 仓库定位与模块地图](#41-仓库定位与模块地图)
  - [4.2 核心设计：双形态（JavaAgent vs Library）](#42-核心设计双形态javaagent-vs-library)
  - [4.3 JavaAgent 字节码插桩原理（premain → ByteBuddy）](#43-javaagent-字节码插桩原理premain--bytebuddy)
  - [4.4 Spring Boot Starter 内幕](#44-spring-boot-starter-内幕)
  - [4.5 Micrometer 桥接模块](#45-micrometer-桥接模块)
  - [4.6 注解插桩：@WithSpan 与切面](#46-注解插桩withspan-与切面)
  - [4.7 declarative-config-bridge：配置读取的统一桥](#47-declarative-config-bridge配置读取的统一桥)
- [五、两个仓库如何协同：数据流全景](#五两个仓库如何协同数据流全景)
- [六、SpringBoot 4.1.1 项目实战](#六springboot-411-项目实战)
  - [6.1 先做选择题：三条集成路线](#61-先做选择题三条集成路线)
  - [6.2 准备本地观测后端](#62-准备本地观测后端)
  - [6.3 方案 A：Spring Boot 4 原生方式（官方推荐）](#63-方案-a-spring-boot-4-原生方式官方推荐)
  - [6.4 方案 B：OTel Spring Boot Starter](#64-方案-b-otel-spring-boot-starter)
  - [6.5 方案 C：JavaAgent 零代码插桩](#65-方案-cjavaagent-零代码插桩)
  - [6.6 手动埋点补充：注解与 API](#66-手动埋点补充注解与-api)
  - [6.7 源码走读：一个请求的完整旅程](#67-源码走读一个请求的完整旅程)
- [七、总结与参考资料](#七总结与参考资料)

---

## 一、全景定位：规范、两个仓库与你的应用

### 1.1 一张图看清三方关系

OpenTelemetry（下称 OTel）的世界由三层构成：**规范（Specification）、实现（这两个仓库）、应用（你的 Spring Boot 项目）**。

```
┌─────────────────────────────────────────────────────────────────────┐
│ ① OTel 规范 (Specification) —— 一套协议文档                          │
│    定义: 三大信号(Trace/Metric/Log)、数据模型、API/SDK 边界、          │
│          语义约定、传播协议(W3C)、OTLP 协议……                          │
└──────────────────────────┬──────────────────────────────────────────┘
                           │ 实现规范
          ┌────────────────┴─────────────────────┐
          ▼                                      ▼
┌───────────────────────────┐      ┌─────────────────────────────────┐
│ ② opentelemetry-java      │      │ ② opentelemetry-java-           │
│   (v1.66.0)               │      │   instrumentation (v2.31.1)     │
│   "地基"：                  │      │   "自动化层"：                    │
│   · API(接口,无逻辑)        │ ←──  │   · JavaAgent: 无需改代码         │
│   · SDK(标准实现)           │ 依赖 │     拦截 127 种框架/库的方法       │
│   · exporters(OTLP等)      │      │   · Spring Boot Starter          │
│   · context 传播           │      │   · Micrometer 桥接、@WithSpan    │
└───────────┬───────────────┘      └──────────────┬──────────────────┘
            │                                     │
            ▼                                     ▼
┌─────────────────────────────────────────────────────────────────────┐
│ ③ 你的 Spring Boot 4.1.1 应用                                        │
│    三条接入路线(详见第六章):                                            │
│    A. Spring Boot 官方 Micrometer 路线(推荐)                          │
│    B. opentelemetry-spring-boot-starter                              │
│    C. otel javaagent 一键零代码                                       │
└──────────────────────────────┬──────────────────────────────────────┘
                               │ OTLP (gRPC/HTTP, 4317/4318)
                               ▼
                   Jaeger / Tempo / Prometheus / Collector …
```

### 1.2 两个仓库的分工

| | opentelemetry-java | opentelemetry-java-instrumentation |
|---|---|---|
| 定位 | **规范的标准实现**：API + SDK + 导出器 | **自动化插桩**：让主流框架"自动"产生遥测数据 |
| 你要手动写代码吗 | 手动埋点时用它的 API | 用 agent/starter 时不用写一行遥测代码 |
| 关键产物 | `opentelemetry-api`、`opentelemetry-sdk`、`opentelemetry-exporter-otlp` | `opentelemetry-javaagent.jar`、`opentelemetry-spring-boot-starter`、`opentelemetry-instrumentation-annotations` |
| 规模 | 仓库模块约 30 个 | `instrumentation/` 下 **127 个**被插桩技术 |
| 类比 | 语言/编译器 | 语法糖与代码生成器 |

两者是**依赖关系**：instrumentation 仓库的每个模块都依赖 opentelemetry-java 发布的 API/SDK（源码里 `OpenTelemetryAutoConfiguration` 直接 import `io.opentelemetry.sdk.OpenTelemetrySdk`）。

### 1.3 版本基线与阅读前提

| 组件 | 版本 | 说明 |
|------|------|------|
| opentelemetry-java | **v1.66.0**（2026-09-11，CHANGELOG） | 本地仓库实际版本 |
| opentelemetry-java-instrumentation | **v2.31.1**（2026-08-23，CHANGELOG） | 本地仓库实际版本 |
| Spring Boot | 4.1.1（本文实战目标）；本地 `D:\code\3rd\spring-boot` 为 4.2.0-SNAPSHOT 主线 | Spring Boot 4.0 于 2025-11 GA（基于 Spring Framework 7） |
| JDK | 17+（推荐 21） | Spring Boot 4 要求 |

> 本笔记系列的相关篇目：《OpenTelemetry.md》讲 OTel 概念与架构全景；《opentelemetry-java.md》已深入分析过 opentelemetry-java 单仓库（API/SDK 分层、设计模式）。**本文的重心差异化**：规范到代码的映射做精简自包含版，重点放在 instrumentation 仓库源码（JavaAgent/Starter，前篇未覆盖）与 Spring Boot 4.1.1 落地实战。

---

## 二、OTel 规范速览：从规范到 Java 代码的映射

### 2.1 规范要解决什么

OTel 规范定义了三大信号（Signal）的统一数据模型与生产管线：

| 信号 | 描述"什么" | 基本单位 | 典型后端 |
|------|-----------|---------|---------|
| **Traces** | 一次请求在系统中的完整旅程 | Span（有起止时间、父子关系的操作片段） | Jaeger、Tempo |
| **Metrics** | 系统的可聚合数值（QPS、延迟、内存） | Data Point（Counter/Histogram/Gauge/UpDownCounter） | Prometheus、Mimir |
| **Logs** | 离散的事件文本记录 | LogRecord（可携带 trace_id 与链路关联） | Loki、Elasticsearch |

以及把它们连起来的骨架：**Resource**（"这条数据是谁产生的"：服务名/版本/环境）、**Context 传播**（跨进程/线程传递 trace 身份）、**OTLP**（统一的导出协议）。

### 2.2 两条铁律：API/SDK 分离、无后端可运行

规范强制两条设计原则，Java 实现严格遵守，理解了它们，两个仓库的结构就一目了然：

**铁律一：API 与 SDK 分离。** 业务代码和被插桩的库（如 Spring）只 import 轻量的 API 包；SDK（真正的采集/导出逻辑）在运行时注入或装配。好处：库作者只依赖 API（几 KB、无传递依赖），应用侧自由选择 SDK/无 SDK。

**铁律二：没有 SDK 也能跑（No-op 原则）。** 只有 API 时，所有调用落到空实现，零开销。这就是为什么 Spring 框架敢把 OTel API 作为可选依赖——没装 SDK 时一切静默空转。

### 2.3 规范 → Java 实现的对应总表

| 规范概念 | opentelemetry-java 中的位置（真实路径缩写） |
|---------|--------------------------------------------|
| OTel 门面（API 入口） | `api/.../api/OpenTelemetry.java` |
| 全局单例 | `api/.../api/GlobalOpenTelemetry.java` |
| Span API | `api/.../api/trace/Span.java`、`SpanBuilder.java` |
| Metrics API | `api/.../api/metrics/Meter.java`、`LongCounter.java`、`DoubleHistogram.java` |
| Logs API | `api/.../api/logs/Logger.java` |
| Baggage | `api/.../api/baggage/Baggage.java` |
| Context | `api/.../api/context/Context.java` |
| SDK Trace | `sdk/trace/.../SdkTracerProvider.java`、`SdkSpan.java` |
| SDK Metrics | `sdk/metrics/.../SdkMeterProvider.java` |
| SDK Logs | `sdk/logs/.../SdkLoggerProvider.java` |
| Resource | `sdk/common/.../resources/Resource.java` |
| 自动配置 | `sdk-extensions/autoconfigure/.../AutoConfiguredOpenTelemetrySdk.java` |
| OTLP 导出 | `exporters/otlp/.../OtlpGrpcSpanExporter.java`、`OtlpHttpSpanExporter.java` |
| 自动插桩 | （另一仓库）`javaagent`、`spring-boot-autoconfigure` 模块 |

---

## 三、opentelemetry-java 源码精读：API 与 SDK

### 3.1 仓库模块地图

顶层目录即模块分区（`ls` 实测）：

```
opentelemetry-java/
├── api/            → API 模块（api/all 是主模块）
├── sdk/            → SDK 模块（trace / metrics / logs / common / profiles / all）
├── exporters/      → 导出器（otlp / prometheus / logging / logging-otlp / sender）
├── sdk-extensions/ → autoconfigure（环境变量自动装配 SDK）
├── extensions/     → 附加件（kotlin、trace-propagators 的 B3 系）
├── context/        → 上下文与传播接口
├── all/            → 聚合发布的 opentelemetry-all
├── bom/            → Maven BOM（版本对齐用）
└── integration-tests/, docs/, ...
```

### 3.2 API 层：三个包加一个门面

`api/all/src/main/java/io/opentelemetry/api/` 下的核心成员（真实文件）：

```
api/
├── OpenTelemetry.java          ← 门面接口: getTracerProvider/getMeterProvider/...
├── DefaultOpenTelemetry.java   ← "API-only" 空转实现(全部返回 noop)
├── GlobalOpenTelemetry.java    ← 全局单例(静态入口)
├── trace/    Span, SpanBuilder, SpanContext, SpanKind, StatusCode,
│             Tracer, TracerProvider, TraceFlags, TraceState, propagation/
├── metrics/  Meter, MeterProvider, LongCounter, DoubleHistogram,
│             LongGauge, LongUpDownCounter, Observable*, BatchCallback
├── logs/     Logger, LogRecordBuilder, Severity
├── baggage/  Baggage, BaggageEntry
├── context/  Context, ContextKey, ContextStorage(位于 context/ 顶层模块)
└── common/   Attributes(键值对标签体系)
```

三个值得点透的源码行为：

1. **门面模式**：`OpenTelemetry` 接口是唯一入口，业务代码持有它，通过 `getTracerProvider().get("instrumentation-name")` 拿 Tracer。API 里没有任何采集逻辑。
2. **GlobalOpenTelemetry**：静态全局单例，agent 和 starter 的本质就是**在启动早期调用 `GlobalOpenTelemetry.set(sdk)`**，让所有通过全局入口取 Tracer 的代码自动接到真 SDK 上。
3. **noop 降级**：`DefaultTracer`、`DefaultMeter` 等返回的 Span/Counter 是空实现——调用它们不产生任何数据也没有异常，这就是"无 SDK 可运行"原则的代码体现。

### 3.3 SDK Trace 管线：Span 的一生

核心类（`sdk/trace/src/main/java/io/opentelemetry/sdk/trace/`，实测目录）：

```
sdk/trace/
├── SdkTracerProvider.java   ← SDK 侧的 TracerProvider(持有 sampler/processor/limits)
├── SdkTracer.java           ← 真正创建 Span 的类
├── SdkSpan.java             ← Span 的 SDK 实现(记录属性/事件/状态)
├── SpanProcessor.java       ← ★ 扩展点: Span 开始/结束时被回调
├── MultiSpanProcessor.java  ← 多个 processor 的组合
├── export/
│   ├── SpanExporter.java        ← ★ 扩展点: 把完成的 Span 送到后端
│   ├── BatchSpanProcessor.java  ← 异步批量导出(生产默认)
│   ├── SimpleSpanProcessor.java ← 同步逐条导出(调试用)
│   └── MultiSpanExporter.java / NoopSpanExporter.java
├── samplers/
│   ├── Sampler.java / SamplingDecision.java / SamplingResult.java
│   ├── AlwaysOnSampler.java / AlwaysOffSampler.java
│   ├── ParentBasedSampler.java  ← ★ 尊重父 Span 采样决定(生产标配)
│   └── TraceIdRatioBasedSampler.java ← 按比例采样
├── SpanLimits.java / IdGenerator.java / RandomIdGenerator.java
└── data/ (ReadableSpan/ReadWriteSpan 数据视图)
```

**Span 的一生**（一次完整的源码流转）：

```
业务代码 tracer.spanBuilder("order.create").startSpan()
   → SdkTracer 创建 SdkSpan
      ① 采样决策: Sampler.shouldSample() → RECORD_ONLY / RECORD_AND_SAMPLE / DROP
      ② 通过的 Span 进入 SpanProcessor.onStart() 回调
   → 业务代码 span.setAttribute(...) / span.addEvent(...)
   → span.end()
      ③ SpanProcessor.onEnd() 回调
      ④ BatchSpanProcessor 把 ReadableSpan 放入内存队列
      ⑤ 后台线程批量取出 → SpanExporter.export() → OTLP 发往后端
   → Span 对象变为只读, GC 回收
```

**BatchSpanProcessor 的默认参数**（源码 `BatchSpanProcessorBuilder.java` 常量，实测）：

```java
DEFAULT_SCHEDULE_DELAY_MILLIS   = 5000   // 每 5 秒批量导出一次
DEFAULT_MAX_QUEUE_SIZE          = 2048   // 内存队列容量 2048 条
DEFAULT_MAX_EXPORT_BATCH_SIZE   = 512    // 每批最多 512 条
DEFAULT_EXPORT_TIMEOUT_MILLIS   = 30_000 // 单次导出超时 30 秒
```

这四个值就是高并发下 span 导出的性能调节旋钮（`otel.bsp.*` 环境变量可覆盖）。

**SimpleSpanProcessor vs BatchSpanProcessor**：Simple 在 `span.end()` 的调用线程里同步执行导出（调试利器，`opentelemetry-exporter-logging` 打印每条 span）；Batch 用后台线程攒批（生产默认）。源码里二者都是 `SpanProcessor` 接口实现，装配时通过 `SdkTracerProviderBuilder.addSpanProcessor()` 挂载、由 `MultiSpanProcessor` 组合。

### 3.4 SDK Metrics 管线：从测量到导出

`sdk/metrics/src/main/java/io/opentelemetry/sdk/metrics/`（实测关键类）：`SdkMeterProvider`、`SdkLongCounter`/`SdkDoubleHistogram` 等实现、`Aggregation`（聚合方式：Sum、ExplicitBucketHistogram、Base2ExponentialHistogram、LastValue、Gauge）、`InstrumentSelector`（按名称/类型选仪器做视图定制）、`ExemplarFilter`。

与 Trace 管线的结构差异（新手常混淆）：

```
Trace:  Span 产生 → SpanProcessor → SpanExporter        (逐条事件, 批量转发)
Metric: 测量值 → 聚合器(内存中持续累加) → MetricReader 周期性"拉取"快照 → MetricExporter
                          ↑ SDK 内部聚合, 默认每 60s 导出一次累计值
```

Metrics 是**流式聚合**模型：SDK 在内存里维护每个仪器（如名为 `http.server.request.duration` 的 Histogram）的聚合状态，`PeriodicMetricReader` 定时把聚合快照交给 exporter。这也是为什么 metrics 导出配置叫 `export.interval` 而不是"batch"。

### 3.5 SDK Logs 管线与 profiles 新模块

`sdk/logs/`：`SdkLoggerProvider`、`SdkLogger`、`LogRecordProcessor`、`export/BatchLogRecordProcessor`、`export/SimpleLogRecordProcessor`、`export/LogRecordExporter`——结构与 trace 几乎镜像（同样的 processor/exporter 二段式），体现规范对三信号的对称设计。

另外注意 `sdk/profiles/` 模块（v1.66 实测存在）：这是较新的实验性信号（Profiles，性能剖析采样数据，OTel 正在标准化中），说明规范仍在扩展第三、第四信号。

### 3.6 装配点：OpenTelemetrySdk 与 autoconfigure

**手工装配**：`sdk/all/.../OpenTelemetrySdk.java` + `OpenTelemetrySdkBuilder.java`（实测路径）。典型装配代码：

```java
SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
    .setResource(Resource.getDefault().merge(Resource.create(Attributes.of(
        ResourceAttributes.SERVICE_NAME, "my-app"))))
    .addSpanProcessor(BatchSpanProcessor.builder(
        OtlpGrpcSpanExporter.builder().setEndpoint("http://localhost:4317").build())
        .build())
    .build();

OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
    .setTracerProvider(tracerProvider)
    .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
    .build();
GlobalOpenTelemetry.set(sdk);
```

**自动装配**：`sdk-extensions/autoconfigure/`（实测路径）的 `AutoConfiguredOpenTelemetrySdk`。同目录下有一组配置类（实测文件名）：`TracerProviderConfiguration`、`SpanExporterConfiguration`、`MeterProviderConfiguration`、`MetricExporterConfiguration`、`LoggerProviderConfiguration`、`LogRecordExporterConfiguration`、`PropagatorConfiguration`、`ResourceConfiguration`，以及 `DeclarativeConfigUtil`（新版声明式 YAML 配置支持）。

它做的事情：读环境变量/系统属性（`otel.*` 键）→ SPI 发现 exporter → 装出完整 SDK。**一行代码 + 一组环境变量即可替代上面手工装配的八行**：

```java
AutoConfiguredOpenTelemetrySdk.builder().build();   // 读取 OTEL_* 环境变量
```

```bash
export OTEL_SERVICE_NAME=my-app
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
export OTEL_TRACES_SAMPLER=parentbased_traceidratio
export OTEL_TRACES_SAMPLER_ARG=1.0
```

> agent 和 starter 都复用这套 autoconfigure 机制——这是三、四章之间的连接点。

### 3.7 Exporters：OTLP 双协议与 sender 抽象

`exporters/` 实测目录：`otlp/`、`prometheus/`、`logging/`（本地调试打印）、`logging-otlp/`（以日志形式输出 OTLP）、`common/`、`sender/`。

OTLP 模块内部（`exporters/otlp/all/.../`实测）：

```
trace/  OtlpGrpcSpanExporter.java   ← gRPC 传输(默认端口 4317)
http/trace/   OtlpHttpSpanExporter.java    ← HTTP/protobuf(4318), 对 gRPC 不友好的网络常用
http/metrics/ OtlpHttpMetricExporter.java
http/logs/    OtlpHttpLogRecordExporter.java
```

`exporters/sender/` 是传输层抽象：gRPC（grpc-netty-shaded）与 HTTP（OkHttp / JDK HttpClient）各自实现，exporter 面向接口编程。**设计要点：exporter 只管"序列化 + 发送"，采样、批量、重试这些策略都在 processor/SDK 侧**，职责分离使 exporter 实现非常薄。

### 3.8 Context 与跨进程传播

- `context/` 顶层模块：`Context`（不可变键值链表，线程内传递）、`ContextStorage`（SPI，可接入 ThreadLocal 替代实现，如 Reactor 的上下文桥）。
- `context/.../propagation/`（实测）：`TextMapPropagator`、`TextMapGetter/Setter`、`ContextPropagators`、`DefaultContextPropagators`、`MultiTextMapPropagator`。
- 默认启用 **W3C TraceContext**（`traceparent`/`tracestate` 头）+ **W3C Baggage**，由 autoconfigure 的 `PropagatorConfiguration` 装配。
- `extensions/trace-propagators/`（实测路径）提供 B3 系（`B3Propagator`、`B3MultiConfigurablePropagator` 等），兼容 Zipkin 生态。

一次 HTTP 调用的传播闭环：服务 A 的插桩层把当前 `SpanContext` 写进 `traceparent` 头（inject）→ 服务 B 的插桩层解析该头（extract）→ B 的 Span 成为 A Span 的子节点。**这也是分布式追踪能把多服务串成一条链的全部原理**。

---

## 四、opentelemetry-java-instrumentation 源码精读：自动化插桩

### 4.1 仓库定位与模块地图

这个仓库回答的问题是：**"能不能不写任何代码，就让 Spring/JDBC/Redis/Kafka… 自动产生遥测数据？"**

实测 `instrumentation/` 下有 **127 个**一级模块，几乎覆盖 Java 生态主流技术：`spring/`、`jdbc/`、`kafka/`、`netty/`、`lettuce/`、`elasticsearch/`、`mongo/`、`mybatis-3.2/`、`apache-dubbo-2.7/`、`grpc-1.6/`、`log4j/`、`logback/`、`micrometer/`、`runtime-telemetry/`（JVM 指标）等。

除 `instrumentation/` 外的关键顶层模块：

```
javaagent/                 → 最终的 opentelemetry-javaagent.jar(全功能 agent)
javaagent-bootstrap/       → agent 启动引导(含 premain 入口 OpenTelemetryAgent)
javaagent-extension-api/   → 写自定义插桩扩展的 API(InstrumentationModule 等)
javaagent-tooling/         → agent 引擎: 安装器/字节码匹配/类加载隔离
sdk-autoconfigure-support/ → agent 内装配 SDK 的支持层
instrumentation-annotations/ → @WithSpan 等注解(library 形态可用)
declarative-config-bridge/ → 新声明式配置的桥接层
examples/                  → distro / extension 示例
```

### 4.2 核心设计：双形态（JavaAgent vs Library）

同一个插桩逻辑在仓库里通常有两份打包形态。以 `instrumentation/spring/spring-webmvc/spring-webmvc-6.0/` 实测目录为例：

```
spring-webmvc-6.0/
├── library/     ← library 形态: 普通依赖, 手动装配
│   └── io/opentelemetry/instrumentation/spring/webmvc/v6_0/
│        SpringWebMvcTelemetry.java          ← 入口类
│        SpringWebMvcTelemetryBuilder.java   ← 构建器
│        WebMvcTelemetryProducingFilter.java ← 一个普通 Servlet Filter
└── javaagent/   ← agent 形态: 字节码自动插入, 无需改代码
    └── io/opentelemetry/javaagent/instrumentation/spring/webmvc/v6_0/
         SpringWebMvcInstrumentationModule.java   ← 插桩声明
         DispatcherServletInstrumentation.java    ← 拦截 DispatcherServlet
         HandlerAdapterInstrumentation.java       ← 拦截 HandlerAdapter
```

- **library 形态**：本质是给你一个 `Filter`/拦截器/工具类，自己往应用里装（Spring Boot Starter 就是把 library 形态用自动配置装配起来）；
- **javaagent 形态**：JVM 启动时字节码注入，功能更全（还能插桩 agent 类路径之外的第三方库），零代码。
- **两形态共享同一套"遥测产生器"核心**（SpanName/Attributes 提取逻辑），保证行为一致。这是理解该仓库所有模块组织的钥匙。

### 4.3 JavaAgent 字节码插桩原理（premain → ByteBuddy）

**第一步：JVM 钩子。** `javaagent-bootstrap/src/main/java/io/opentelemetry/javaagent/OpenTelemetryAgent.java`（实测源码）：

```java
public static void premain(@Nullable String agentArgs, Instrumentation inst) {
    startAgent(inst, agentArgs, true);     // JVM 启动时进入
}

public static void agentmain(@Nullable String agentArgs, Instrumentation inst) {
    startAgent(inst, agentArgs, false);    // 运行中 attach 进入
}
```

`premain` 是 Java `Instrumentation` 规范的标准入口：`java -javaagent:opentelemetry-javaagent.jar -jar app.jar` 启动时，JVM 会在任何业务类加载前先调用它。

**第二步：引擎装配。** `javaagent-tooling/.../tooling/`（实测目录）里的关键角色：

- `AgentStarterImpl` / `AgentInstaller`：初始化 SDK（`OpenTelemetryInstaller`），注册字节码转换器；
- `bytebuddy/`：字节码操作引擎 ByteBuddy 的封装——它通过 `Instrumentation.addTransformer()` 注册 `ClassFileTransformer`，**在每个类被 JVM 加载的瞬间**按匹配规则改写字节码；
- `ignore/`：排除类（`otel.instrumentation.common.*.ignored` 等配置的消费方）；
- `field/`：VirtualField 机制——给无法修改的第三方类"附加"字段存 OTel 状态。

**第三步：插桩声明抽象。** `javaagent-extension-api/.../extension/instrumentation/`（实测路径）：

- `InstrumentationModule`：一个插桩单元（对应一种库的一个功能面），声明名字、适用类加载器、包含哪些 `TypeInstrumentation`；
- `TypeInstrumentation`：描述"匹配哪个类 + 改哪个方法"。

**真实源码走读**：`spring-webmvc-6.0/javaagent/.../SpringWebMvcInstrumentationModule.java`（实测原文）：

```java
@AutoService(InstrumentationModule.class)              // SPI 注册
public class SpringWebMvcInstrumentationModule extends InstrumentationModule {

  public SpringWebMvcInstrumentationModule() {
    super("spring-webmvc", new String[] {"spring-webmvc-6.0"});  // 插桩名
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    // 只有类路径上存在 jakarta.servlet.Filter(Spring Boot 3+/6+)才启用
    return hasClassesNamed("jakarta.servlet.Filter");
  }

  @Override
  public List<TypeInstrumentation> typeInstrumentations() {
    return asList(
        new DispatcherServletInstrumentation(),   // 拦截 Spring MVC 前端控制器
        new HandlerAdapterInstrumentation());     // 拦截 handler 执行
  }
}
```

配套的 `DispatcherServletInstrumentation` 声明"匹配 `DispatcherServlet` 类的 `doService` 方法"，并用 **Advice 模式**注入增强：advice 类里写 `@Advice.OnMethodEnter`/`@Advice.OnMethodExit` 方法，ByteBuddy 把这两个方法体**内联拷贝**进目标方法的首尾——效果等价于"在 `DispatcherServlet.doService()` 开头开启 Span、结尾结束 Span"，但目标类源码完全不动。这就是"零侵入插桩"的全部魔法。

**类加载隔离**（`javaagent-bootstrap/AgentClassLoader.java` 等，实测）：agent 自身依赖（ByteBuddy、SDK 等）打在 fat jar 的独立命名空间里，通过自定义 `AgentClassLoader` 加载，避免与业务应用的依赖版本冲突；插桩产生的 helper 类会被 remap（`AgentInstaller` 同目录的 `ShadingRemapper`）。**理解这一点就明白为什么 agent jar 有 20MB+ 却不会污染你的应用**。

### 4.4 Spring Boot Starter 内幕

模块链（实测）：`instrumentation/spring/starters/spring-boot-starter`（发布名 `opentelemetry-spring-boot-starter`）→ 依赖 `instrumentation/spring/spring-boot-autoconfigure`（发布名 `opentelemetry-spring-boot-autoconfigure`）。

**starter 的依赖构成**（`starters/spring-boot-starter/build.gradle.kts` 实测原文）：

```kotlin
dependencies {
  api(project(":instrumentation:spring:spring-boot-autoconfigure"))
  api(project(":instrumentation-annotations"))            // @WithSpan 注解
  api("io.opentelemetry:opentelemetry-sdk-extension-autoconfigure-spi")
  api("io.opentelemetry:opentelemetry-api")
  api("io.opentelemetry:opentelemetry-exporter-logging")  // 本地调试打印
  api("io.opentelemetry:opentelemetry-exporter-otlp")     // OTLP 导出
  api("io.opentelemetry:opentelemetry-sdk")
  implementation(project(":instrumentation:resources:library"))
  implementation("io.opentelemetry:opentelemetry-sdk-extension-incubator")
  implementation("io.opentelemetry.contrib:opentelemetry-aws-resources")      // 自动探测 AWS 环境
  implementation("io.opentelemetry.contrib:opentelemetry-azure-resources")    // Azure
  implementation("io.opentelemetry.contrib:opentelemetry-gcp-resources")      // GCP
  implementation("io.opentelemetry.contrib:opentelemetry-baggage-processor")
  implementation("io.opentelemetry.contrib:opentelemetry-samplers")
}
```

即：**starter = autoconfigure（自动配置）+ API/SDK + OTLP 导出器 + 注解 + 云厂商 Resource 探测**，开箱即用。

**自动配置注册表**（`spring-boot-autoconfigure/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 实测原文，Spring Boot 3+ 标准注册方式）：

```
io.opentelemetry.instrumentation.spring.autoconfigure.OpenTelemetryAutoConfiguration
...internal.instrumentation.annotations.InstrumentationAnnotationsAutoConfiguration
...internal.instrumentation.kafka.KafkaInstrumentationAutoConfiguration
...internal.instrumentation.kafka.ProducerFactoryCustomizerSpringBoot4Configuration  ← Spring Boot 4 专属
...internal.instrumentation.mongo.MongoClientInstrumentationSpringBoot4AutoConfiguration
...internal.instrumentation.logging.OpenTelemetryAppenderAutoConfiguration
...internal.instrumentation.jdbc.JdbcInstrumentationSpringBoot4AutoConfiguration
...internal.instrumentation.micrometer.MicrometerBridgeSpringBoot4AutoConfiguration
...internal.instrumentation.web.SpringWebInstrumentationSpringBoot4AutoConfiguration
...internal.instrumentation.web.RestClientInstrumentationSpringBoot4AutoConfiguration
...internal.instrumentation.webmvc.SpringWebMvc6InstrumentationAutoConfiguration
...internal.instrumentation.scheduling.SpringSchedulingInstrumentationAutoConfiguration
...internal.instrumentation.runtimetelemetry.RuntimeMetricsAutoConfiguration   ← JVM 指标
...internal.instrumentation.thread.ThreadDetailsAutoConfiguration
(共 25 条注册, 一半以上配有 *SpringBoot4* 变体, 说明已全面适配 Boot 4)
```

**主配置类源码**：`OpenTelemetryAutoConfiguration.java`（实测，javadoc 原文："Create `OpenTelemetry` bean if bean is missing. Adds span exporter beans to the active tracer provider. Updates the sampler probability..."）。它的职责：

1. 把 autoconfigure 的 `AutoConfiguredOpenTelemetrySdk` 装配成 Spring 容器里的 `OpenTelemetry` Bean（支持用户自定义 Bean 覆盖——`@ConditionalOnMissingBean`）；
2. 把容器中存在的 `SpanExporter` Bean 自动注册进 TracerProvider；
3. 将 `otel.*`/`spring.application.name` 等 Spring 配置桥接给 SDK；
4. 激活各插桩自动配置类（web/kafka/jdbc/mongo/…），用 `@ConditionalOnClass` 按需生效——类路径上有什么就插桩什么。

**配置属性前缀**（实测 `internal/properties/` 下三个 `@ConfigurationProperties`）：

| 前缀 | 类 | 用途 |
|------|----|------|
| `otel` | `OtelSpringProperties` | 总开关 `otel.enabled`、采样 `otel.traces.sampler` 等 |
| `otel.resource` | `OtelResourceProperties` | 服务名/环境属性（`otel.resource.attributes`） |
| `otel.exporter.otlp` | `OtlpExporterProperties` | endpoint/headers/协议（`otel.exporter.otlp.endpoint`） |

### 4.5 Micrometer 桥接模块

`instrumentation/micrometer/micrometer-1.5/`（实测）。方向是把 **Micrometer 的 API 调用翻译成 OTel SDK 调用**：

- 应用里已有的 `MeterRegistry`/`Timer`/`Counter` 代码不用改，OTel 通过 Micrometer Bridge（`MicrometerBridge`）把指标送进 OTel 管线导出成 OTLP/Prometheus；
- agent 形态里 `MetricsInstrumentation`（实测类名）自动给所有 Micrometer registry 做这件事；
- starter 里由 `MicrometerBridgeAutoConfiguration`（及 `MicrometerBridgeSpringBoot4AutoConfiguration`）装配。

**为什么这个桥关键**：Spring Boot 的整个 Actuator 指标体系建立在 Micrometer 上。有桥之后，"Spring Boot 生态指标 → OTel 后端"的通道就打通了——这正是第六章 Spring Boot 官方路线（Micrometer-first）能成立的技术前提。

### 4.6 注解插桩：@WithSpan 与切面

`instrumentation-annotations/src/main/java/io/opentelemetry/instrumentation/annotations/`（实测）：`WithSpan.java`、`SpanAttribute.java`、`AddingSpanAttributes.java`。

```java
public class OrderService {
  @WithSpan("process-order")                    // 该方法调用自动产生一个子 Span
  public Order process(@SpanAttribute("order.id") String orderId) { ... }
}
```

两种生效途径（对应双形态）：

1. **JavaAgent**：agent 内置 `opentelemetry-instrumentation-annotations-1.16` 模块，启动时扫描字节码注入；
2. **Starter**：`InstrumentationAnnotationsAutoConfiguration` 注册 `WithSpanAspect`（实测类，AOP 切面）——不需要 agent，Spring AOP 直接代理。starter 模式想用注解埋点必须依赖这一点。

### 4.7 declarative-config-bridge：配置读取的统一桥

实测该模块 README 原文摘要："Declarative Config Bridge allows instrumentation authors to access configuration in a uniform way, regardless of the configuration source"——即插桩代码读配置时，无论是老式扁平属性（`otel.inferred.spans.*`）还是新的声明式 YAML（OTel 配置文件规范），都通过统一的 `DeclarativeConfigProperties` API 读取。这是 OTel 配置体系从"环境变量一把梭"向"结构化配置文件"演进期的兼容层（README 注明 3.0 将移除旧桥接 API）。

---

## 五、两个仓库如何协同：数据流全景

以两条接入路线分别画出完整数据流（结合前面源码结论）：

**路线一：JavaAgent 形态**

```
java -javaagent:opentelemetry-javaagent.jar -jar app.jar
  │
  ├─ OpenTelemetryAgent.premain()                    [instrumentation 仓库]
  ├─ AgentInstaller: 注册 ByteBuddy ClassFileTransformer
  ├─ 127 个 InstrumentationModule 按 classpath 条件激活
  │    └─ DispatcherServlet.doService() 被注入开启/结束 Span 的代码
  ├─ OpenTelemetryInstaller: 组装 SDK                 [opentelemetry-java 仓库]
  │    └─ SdkTracerProvider + BatchSpanProcessor + OtlpGrpcSpanExporter
  │       (由 sdk-extensions/autoconfigure 读 OTEL_* 环境变量完成)
  └─ 运行期: 业务方法 → 注入代码创建 SdkSpan → processor → OTLP → 后端
```

**路线二：Spring Boot Starter 形态**

```
spring-boot-starter 依赖 + application.yml(otel.*)
  │
  ├─ Spring Boot 自动装配扫描 AutoConfiguration.imports  [instrumentation 仓库]
  ├─ OpenTelemetryAutoConfiguration: 装配 OpenTelemetry Bean
  │    └─ 内部调用 AutoConfiguredOpenTelemetrySdk        [opentelemetry-java 仓库]
  ├─ 各 *InstrumentationAutoConfiguration: 注册 Filter/ConsumerFactory 包装/JDBC 代理…
  └─ 运行期: 请求进来 → WebMvc Filter 开 Span → BatchSpanProcessor → OTLP
```

两条路线殊途同归：**instrumentation 负责"在哪采集"，opentelemetry-java 负责"采集后如何处理导出"**。

---

## 六、SpringBoot 4.1.1 项目实战

### 6.1 先做选择题：三条集成路线

| | A. Spring Boot 官方路线 | B. OTel Starter | C. JavaAgent |
|---|---|---|---|
| 思路 | Micrometer Observation → OTel bridge → OTLP | OTel 全家桶直接进容器 | JVM 层字节码注入 |
| 依赖 | `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` | `opentelemetry-spring-boot-starter` | 一个 jar + 启动参数 |
| 代码侵入 | 零（框架自动埋点） | 零 | 零 |
| Trace+Metrics+Logs | 三者都走 Micrometer/OTel 管线 | OTel SDK 统一管理 | OTel SDK 统一管理 |
| 适配 Spring Boot 4 | ✅ 官方内置（4.0 起原生支持 OTLP 导出） | ✅（2.31.x 已带 `*SpringBoot4*` 配置类，源码实测） | ✅（agent 对框架字节码插桩，与 Boot 版本解耦度高） |
| 适合 | **新项目首选**（与 Boot 生态一致性最好） | 想深度用 OTel 原生配置/资源探测 | 不想动构建、想全覆盖第三方库 |

**Spring Boot 4 官方立场**（spring.io 2025-11 官方指南《OpenTelemetry with Spring Boot》）：Boot 4 沿用 **Micrometer-first**——应用统一走 Micrometer Observation API，由 `micrometer-tracing-bridge-otel`（OTel bridge）翻译成 OTel Span，再经 `opentelemetry-exporter-otlp` 导出。本地 `D:\code\3rd\spring-boot`（4.2.0-SNAPSHOT）模块目录也印证了这一点：`module/spring-boot-micrometer-observation`、`spring-boot-micrometer-tracing`、`spring-boot-micrometer-tracing-opentelemetry` 等。

> 三条路线并不互斥：官方路线 + agent 同时用也常见（agent 补齐 Boot 之外的 JDBC driver/Kafka client 等细节插桩）。

### 6.2 准备本地观测后端

最省事的是 All-in-One 的 Jaeger（2.0 版本内置 OTLP 接收）：

```bash
docker run --rm -d --name jaeger \
  -p 16686:16686 -p 4317:4317 -p 4318:4318 \
  jaegertracing/all-in-one:latest
# UI:  http://localhost:16686
# gRPC 4317 / HTTP 4318 都是 OTLP 标准端口
```

想同时看 metrics/持久化，可再起 Grafana + Tempo/Prometheus 或 Grafana LGTM 全家桶（`grafana/otel-lgtm` 镜像一条命令起全套，面向本地开发）。

### 6.3 方案 A：Spring Boot 4 原生方式（官方推荐）

**完整示例项目结构：**

```
otel-demo/
├── pom.xml
└── src/main/java/com/example/demo/
    ├── DemoApplication.java
    ├── OrderController.java
    └── OrderService.java
└── src/main/resources/application.yml
```

**pom.xml（关键依赖，Spring Boot 4.1.1）：**

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.1</version>
</parent>

<properties><java.version>21</java.version></properties>

<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <!-- 可观测核心: Observation API + Actuator -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <!-- Traces: Micrometer Tracing 的 OTel 桥接实现 -->
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-tracing-bridge-otel</artifactId>
    </dependency>
    <!-- Traces 导出: OTLP -->
    <dependency>
        <groupId>io.opentelemetry</groupId>
        <artifactId>opentelemetry-exporter-otlp</artifactId>
    </dependency>
    <!-- Metrics 导出: Micrometer 的 OTLP registry -->
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-registry-otlp</artifactId>
    </dependency>
</dependencies>
```

**application.yml：**

```yaml
spring:
  application:
    name: otel-demo

management:
  endpoints.web.exposure.include: health,info,metrics
  tracing:
    sampling:
      probability: 1.0              # 开发环境全采样; 生产按需调低
  otlp:
    tracing:
      endpoint: http://localhost:4318/v1/traces
    metrics:
      export:
        url: http://localhost:4318/v1/metrics
        step: 10s                   # 每 10 秒导出一次指标聚合快照
```

**业务代码（OrderController + OrderService）：**

```java
@RestController
class OrderController {

    private final RestClient restClient;      // Spring 6.1+ 的同步 HTTP 客户端
    private final OrderService orderService;

    OrderController(RestClient.Builder builder, OrderService orderService) {
        this.restClient = builder.baseUrl("http://localhost:8080").build();
        this.orderService = orderService;
    }

    @GetMapping("/orders/{id}")
    Map<String, Object> getOrder(@PathVariable String id) {
        // RestController/RestClient 均被 Boot 自动 Observation 埋点:
        // 服务端 span "GET /orders/{id}" + 客户端 span 会自动形成父子链
        return orderService.load(id);
    }
}

@Service
class OrderService {

    // 手动埋点: Observation API 是 Spring Boot 4 官方统一入口
    private final ObservationRegistry registry;

    OrderService(ObservationRegistry registry) { this.registry = registry; }

    Map<String, Object> load(String id) {
        return Observation.createNotStarted("order.load", registry)
                .lowCardinalityKeyValue("order.type", "normal")
                .observe(() -> Map.of("id", id, "status", "paid"));
    }
}
```

**运行与验证：**

```bash
mvn spring-boot:run
curl http://localhost:8080/orders/42
# 打开 http://localhost:16686 → 选择 service: otel-demo → 即可看到链路:
#   GET /orders/{id} (server span)
#    └─ order.load (手动 Observation)
```

同时 `/actuator/metrics` 与 OTLP 指标会把 `http.server.requests`、JVM 指标等持续推给后端。

### 6.4 方案 B：OTel Spring Boot Starter

```xml
<dependency>
    <groupId>io.opentelemetry.instrumentation</groupId>
    <artifactId>opentelemetry-spring-boot-starter</artifactId>
    <version>2.31.1</version>
</dependency>
```

```yaml
otel:
  service:
    name: otel-demo            # 也可用 spring.application.name 自动带过
  exporter:
    otlp:
      endpoint: http://localhost:4317    # starter 默认 gRPC 4317
  traces:
    sampler: parentbased_traceidratio
    sampler_arg: 1.0
  instrumentation:
    spring-webmvc:
      enabled: true
    jdbc:
      enabled: true
    micrometer:
      enabled: true
```

行为差异：容器里会直接出现 `OpenTelemetry` Bean，可直接注入 `Tracer` 手动埋点；`@WithSpan` 注解经 AOP 生效；云上自动探测 AWS/GCP/Azure Resource（starter 依赖里实测包含三个云 resource 模块）。**选 A 还是 B 的直觉**：跟随 Spring 生态约定（Observation/Micrometer）选 A；想用纯 OTel 语义（`otel.*` 配置、OTel Resource 规范、Baggage processor 等）选 B。

### 6.5 方案 C：JavaAgent 零代码插桩

```bash
# 1. 下载 agent (github.com/open-telemetry/opentelemetry-java-instrumentation/releases)
curl -LO https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.31.1/opentelemetry-javaagent.jar

# 2. 启动应用(不改任何代码和构建)
java -javaagent:./opentelemetry-javaagent.jar \
     -Dotel.service.name=otel-demo \
     -Dotel.exporter.otlp.endpoint=http://localhost:4317 \
     -jar app.jar
```

常用开关（源码 `javaagent-tooling/.../config/` 消费）：

```bash
-Dotel.instrumentation.common.default-enabled=false   # 全关后按需打开(生产精简)
-Dotel.instrumentation.spring-webmvc.enabled=true
-Dotel.instrumentation.jdbc.enabled=true
-Dotel.instrumentation.jdbc-datasource.enabled=true
-Dotel.instrumentation.logback-appender.enabled=true  # 日志桥接, log 语句带 trace_id
# 排除噪音: 忽略健康检查/静态资源的 span
-Dotel.instrumentation.common.peer-service-mapping=...
-Dotel.traces.exporter=otlp
-Dotel.metrics.exporter=none        # 只要 trace 时关掉 metrics 导出
```

**注意**：agent 与方案 A 同时开启时，同一个请求会同时产生 Micrometer 桥的 span 和 agent 插桩的 span——通常保留一种 span 来源（或用 `otel.instrumentation.*` 关掉重叠部分），避免链路重复。

### 6.6 手动埋点补充：注解与 API

无论 A/B/C，补充手动埋点都可从这两个层级选：

```java
// ① 注解级(方案 B/C 开箱即用; 方案 A 下经 Boot 管理的切面或改用 Observation)
@WithSpan(value = "payment.charge", kind = SpanKind.CLIENT)
void charge(@SpanAttribute("payment.order") String orderId) { ... }

// ② 纯 OTel API(方案 B: 注入 OpenTelemetry; 方案 A: 桥接层同样可拿到全局 OTel)
Tracer tracer = openTelemetry.getTracer("com.example.order");
Span span = tracer.spanBuilder("order.validate").startSpan();
try (Scope scope = span.makeCurrent()) {
    span.setAttribute("order.items", 3);
    // 业务逻辑...
} catch (Exception e) {
    span.recordException(e);
    span.setStatus(StatusCode.ERROR, e.getMessage());
    throw e;
} finally {
    span.end();          // 忘记 end 是新手最常见 bug: span 永不导出
}
```

### 6.7 源码走读：一个请求的完整旅程

以方案 A 为例，把 `curl http://localhost:8080/orders/42` 的遥测数据流与源码对应起来：

```
① Tomcat 收到请求
   → Boot 自动配置的 ObservationFilter(FilterRegistry) 开启 Observation
   → micrometer-tracing-bridge-otel 把 Observation 翻译为 OTel Span
      (bridge 模块: micrometer-tracing 仓库, 持有 opentelemetry-api 的 Tracer)
② 框架链路传播: W3CTraceContextPropagator 写入响应头 traceparent
   [opentelemetry-java: context/.../propagation/]
③ OrderService.load() 的 Observation.createNotStarted("order.load")
   → 生成子 Span, 父子关系由 ThreadLocal Context 传递
      [opentelemetry-java: api/context/Context]
④ Span.end() → SdkTracerProvider 的 SpanProcessor 管线
      [opentelemetry-java: sdk/trace/SdkSpan → MultiSpanProcessor]
⑤ BatchSpanProcessor 每 5s 攒批(默认 512/批, 队列 2048)
      [opentelemetry-java: sdk/trace/export/BatchSpanProcessor]
⑥ OtlpHttpSpanExporter 将批次序列化为 OTLP/protobuf, POST 到 4318/v1/traces
      [opentelemetry-java: exporters/otlp/http/trace/]
⑦ Jaeger 收到并存储 → UI 可视化整条链
⑧ metrics 侧: Micrometer registry 每 10s(管理端点配置的 step)
   把聚合快照交 OTLP metric exporter → /v1/metrics
```

对照方案 B 的旅程，差别只在 ①②③ 步：span 的产生者是 starter 注册的 `WebMvcTelemetryProducingFilter`（library 形态 Filter，见 4.2），后续 ④–⑧ 完全一致。

---

## 七、总结与参考资料

### 核心记忆点，五句话

1. **规范定契约，双仓库分工**：opentelemetry-java 是"API 接口 + SDK 实现 + 导出器"的地基；instrumentation 是"127 种框架自动埋点"的自动化层，二者是依赖关系不是并列关系。
2. **两条铁律解释一切设计**：API/SDK 分离（库只依赖轻 API）、无 SDK 可运行（noop 空转）——Spring 敢内置 OTel 集成、agent 能无侵入插桩，根都在这两条。
3. **SDK 的管线是"Processor → Exporter"两段式**：trace/logs 同构（Batch 5s/2048/512 默认参数），metrics 是聚合拉取模型。
4. **instrumentation 的钥匙是"双形态"**：library（手动装 Filter/切面）与 javaagent（premain + ByteBuddy 注入），同一个遥测核心两种分发方式；Starter 就是"library 形态 + Spring 自动配置"。
5. **Spring Boot 4 落地选 Micrometer-first 官方路线**：Observation API → bridge-otel → OTLP；需要更深覆盖时叠加 JavaAgent。记住"谁产生 span"是每条路线的唯一差异点。

### 参考资料

- OpenTelemetry 规范：https://opentelemetry.io/docs/specs/otel/
- opentelemetry-java 仓库：https://github.com/open-telemetry/opentelemetry-java（本地 `D:\code\3rd\opentelemetry-java`）
- opentelemetry-java-instrumentation 仓库：https://github.com/open-telemetry/opentelemetry-java-instrumentation（本地 `D:\code\3rd\opentelemetry-java-instrumentation`）
- Spring Boot 官方 OTel 指南（2025-11）：https://spring.io/guides 及 blog "OpenTelemetry with Spring Boot"
- Spring Boot 4 OpenTelemetry 实操（foojay.io, 2025-12）：https://foojay.io/today/spring-boot-4-opentelemetry/
- OTel Java Agent 文档：https://opentelemetry.io/docs/instrumentation/java/javaagent/
- OTel Spring Boot Starter 文档：https://opentelemetry.io/docs/specs/otel/ (instrumentation → spring-boot-starter)
- Micrometer Tracing 文档：https://docs.micrometer.io/tracing/reference/
- 系列笔记交叉阅读：同目录《OpenTelemetry.md》（概念全景）、《opentelemetry-java.md》（单仓库深读）、《Cilium.md》（eBPF 层面的网络可观测）
