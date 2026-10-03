# OpenTelemetry Java 源码深度分析（小白友好）

> 基于 `D:\code\3rd\opentelemetry-java`（v1.66.0，2026-09-11）本地源码的逐文件精读：API/SDK 分层、三大信号实现、Context 传播、扩展点体系与设计模式。
> 所有类名、方法签名、常量、文件路径均对照源码验证，可按图索骥。
> 姊妹篇：《opentelemetry分析.md》（双仓库全景 + Spring Boot 4.1.1 实战）、《OpenTelemetry.md》（OTel 概念全景）。

---

## 目录

- [一、OTel 规范与 Java 实现总览](#一otel-规范与-java-实现总览)
  - [1.1 两条铁律：理解整个仓库的钥匙](#11-两条铁律理解整个仓库的钥匙)
  - [1.2 规范核心概念表](#12-规范核心概念表)
  - [1.3 API-SDK 分层架构](#13-api-sdk-分层架构)
- [二、代码库整体结构](#二代码库整体结构)
  - [2.1 目录结构与模块表](#21-目录结构与模块表)
  - [2.2 构建系统与版本管理](#22-构建系统与版本管理)
- [三、API vs SDK 分层：门面、全局单例与混淆包装](#三api-vs-sdk-分层门面全局单例与混淆包装)
  - [3.1 设计原则](#31-设计原则)
  - [3.2 入口接口：OpenTelemetry](#32-入口接口opentelemetry)
  - [3.3 全局单例：GlobalOpenTelemetry](#33-全局单例globalopentelemetry)
  - [3.4 API 层的 no-op 降级实现](#34-api-层的-no-op-降级实现)
  - [3.5 SDK 装配点：OpenTelemetrySdk](#35-sdk-装配点opentelemetrysdk)
- [四、三大信号实现分析](#四三大信号实现分析)
  - [4.1 Traces（链路追踪）](#41-traces链路追踪)
  - [4.2 Metrics（指标度量）](#42-metrics指标度量)
  - [4.3 Logs（日志桥接）](#43-logs日志桥接)
- [五、Context 与传播机制](#五context-与传播机制)
  - [5.1 Context：不可变上下文容器](#51-context不可变上下文容器)
  - [5.2 ContextStorage：存储提供者 SPI](#52-contextstorage存储提供者-spi)
  - [5.3 Baggage：跨服务的业务行李](#53-baggage跨服务的业务行李)
  - [5.4 TextMapPropagator：跨进程传播器](#54-textmappropagator跨进程传播器)
  - [5.5 W3C traceparent：标准传播格式](#55-w3c-traceparent标准传播格式)
  - [5.6 其他传播器扩展](#56-其他传播器扩展)
- [六、SDK 扩展点体系](#六sdk-扩展点体系)
  - [6.1 IdGenerator：ID 生成器](#61-idgeneratorid-生成器)
  - [6.2 Resource：资源元信息](#62-resource资源元信息)
  - [6.3 三大 Exporter 的对称设计](#63-三大-exporter-的对称设计)
  - [6.4 CompletableResultCode：统一异步结果](#64-completableresultcode统一异步结果)
  - [6.5 sender：导出器底层的传输抽象](#65-sender导出器底层的传输抽象)
  - [6.6 profiles：正在孵化中的第四信号](#66-profiles正在孵化中的第四信号)
- [七、自动配置（Autoconfigure）](#七自动配置autoconfigure)
  - [7.1 三层配置接口](#71-三层配置接口)
  - [7.2 AutoConfiguredOpenTelemetrySdk 源码](#72-autoconfiguredopentelemetrysdk-源码)
  - [7.3 常用配置项速查](#73-常用配置项速查)
- [八、设计模式与工程实践](#八设计模式与工程实践)
  - [8.1 关键设计模式汇总](#81-关键设计模式汇总)
  - [8.2 Null 安全与错误处理哲学](#82-null-安全与错误处理哲学)
  - [8.3 包可见性控制](#83-包可见性控制)
  - [8.4 线程安全实践](#84-线程安全实践)
  - [8.5 兼容性与版本策略](#85-兼容性与版本策略)
- [九、动手验证：用本地源码跑通最小闭环](#九动手验证用本地源码跑通最小闭环)
- [十、总结与参考资料](#十总结与参考资料)

---

## 一、OTel 规范与 Java 实现总览

OpenTelemetry（简称 OTel）是 CNCF 旗下的开源可观测性框架，由 OpenTracing 与 OpenCensus 合并而来，目标是提供**厂商中立**的可观测数据采集标准。`opentelemetry-java` 是规范在 Java 语言的参考实现（API + SDK + 导出器），本文基于本地源码 v1.66.0 分析。

### 1.1 两条铁律：理解整个仓库的钥匙

规范强制两条设计原则，这个仓库的每个目录、每个类都能追溯到它们：

**铁律一：API 与 SDK 严格分离。** 业务代码、被插桩的框架（如 Spring）只依赖轻量的 API 包（接口 + 极简逻辑）；SDK（真正的采集、聚合、导出实现）由应用在运行时选择并注入。API 包只有几百 KB、零传递依赖。

**铁律二：没有 SDK 也能正常运行（no-op 降级）。** 只有 API 时，所有调用落到空实现：创建 Span 不报错、打点不报错，只是不产生任何数据。所以应用代码可以在**不知道运行时是否有 SDK** 的情况下安全写埋点——Java Agent 和 Spring Boot Starter 的魔法全建立在这一条上。

### 1.2 规范核心概念表

| 概念 | 一句话解释 | 规范关键词 |
|------|-----------|-----------|
| **Traces（追踪）** | 一次请求跨服务的完整调用链 | Trace / Span / SpanContext / TraceState |
| **Metrics（指标）** | 可聚合的量化度量 | Meter / Counter / Gauge / Histogram / UpDownCounter |
| **Logs（日志）** | 结构化事件记录，可与 Trace 关联 | Logger / LogRecord / Severity |
| **Baggage（行李）** | 随调用链跨服务传播的业务键值对 | Baggage / BaggageEntry |
| **Context 传播** | 进程内（线程）与跨进程（协议头）的上下文传递 | Context / TextMapPropagator / W3C TraceContext |
| **Resource（资源）** | 信号来源实体的元信息（服务名、版本、环境） | Resource / Attributes |
| **Instrumentation Scope** | 标识"哪个插桩库产生了这条数据" | InstrumentationScopeInfo |

### 1.3 API-SDK 分层架构

```
┌────────────────────────────────────────────────────── 应用层
│  应用代码 / 框架插桩代码（Instrumentation）
├────────────────────────────────────────────────────── API 层（稳定，无业务逻辑）
│  OpenTelemetry（门面）
│  TracerProvider / MeterProvider / LoggerProvider
│  Tracer / Meter / Logger
│  Span / 各类 Instruments / LogRecordBuilder
│  Context / Baggage / TextMapPropagator
├────────────────────────────────────────────────────── SDK 层（可插拔实现）
│  SdkTracerProvider / SdkMeterProvider / SdkLoggerProvider
│  Sampler / SpanProcessor / MetricReader / LogRecordProcessor
│  SpanExporter / MetricExporter / LogRecordExporter
│  Resource / Limits
├────────────────────────────────────────────────────── 导出层
│  OTLP(gRPC/HTTP) / Prometheus / Console …
└────────────────────────────────────────────────────── 后端（Collector / Jaeger / Tempo…）
```

按铁律二，应用只落在 API 层；SDK 与导出层缺席时整条链路静默空转。

---

## 二、代码库整体结构

### 2.1 目录结构与模块表

仓库顶层按功能分区（`ls` 实测）：

| 目录/模块 | 说明 | 发布的 Artifact ID |
|-----------|------|-------------------|
| `api/all` | API 层核心（Traces/Metrics/Logs/Baggage/Context） | `opentelemetry-api` |
| `api/incubator` | API 孵化器（实验性 API：ExtendedOpenTelemetry、PassThroughPropagator、config 等） | `opentelemetry-api-incubator` |
| `context/` | Context 与传播机制（独立模块，无 SDK 依赖） | `opentelemetry-context` |
| `common/` | 跨模块公共组件 | `opentelemetry-common` |
| `sdk/trace` | Trace SDK | `opentelemetry-sdk-trace` |
| `sdk/metrics` | Metrics SDK | `opentelemetry-sdk-metrics` |
| `sdk/logs` | Logs SDK | `opentelemetry-sdk-logs` |
| `sdk/common` | SDK 公共组件（Resource、Clock、CompletableResultCode） | `opentelemetry-sdk-common` |
| `sdk/profiles` | 实验性 Profiles 信号（孵化中） | `opentelemetry-sdk-profiles` |
| `sdk/all` | SDK 聚合入口（`OpenTelemetrySdk` 所在） | `opentelemetry-sdk` |
| `sdk/testing` | SDK 测试工具（内存导出器等） | `opentelemetry-sdk-testing` |
| `exporters/otlp` | OTLP 导出器（gRPC + HTTP 双协议） | `opentelemetry-exporter-otlp` |
| `exporters/prometheus` | Prometheus 拉模式导出器 | `opentelemetry-exporter-prometheus` |
| `exporters/logging` | Console 打印导出器（本地调试） | `opentelemetry-exporter-logging` |
| `exporters/logging-otlp` | 以日志形式输出 OTLP 请求体 | `opentelemetry-exporter-logging-otlp` |
| `exporters/sender` | 传输层抽象（gRPC/OkHttp/JDK HttpClient） | `opentelemetry-exporter-sender-*` |
| `sdk-extensions/autoconfigure` | 环境变量/系统属性自动装配 | `opentelemetry-sdk-extension-autoconfigure` |
| `sdk-extensions/autoconfigure-spi` | 自动装配 SPI 接口 | `opentelemetry-sdk-extension-autoconfigure-spi` |
| `sdk-extensions/declarative-config` | YAML 声明式配置支持 | `opentelemetry-sdk-extension-declarative-config` |
| `sdk-extensions/incubator` | SDK 孵化器组件 | `opentelemetry-sdk-extension-incubator` |
| `sdk-extensions/jaeger-remote-sampler` | Jaeger 远程采样策略 | `opentelemetry-sdk-extension-jaeger-remote-sampler` |
| `extensions/trace-propagators` | B3 等 W3C 之外的传播器 | `opentelemetry-extension-trace-propagators` |
| `extensions/kotlin` | Kotlin 协程上下文扩展 | `opentelemetry-extension-kotlin` |
| `bom` / `bom-alpha` | 物料清单（版本对齐用） | `opentelemetry-bom` |

> 注意三层"扩展"目录的分工：`sdk-extensions/`（SDK 能力扩展）、`extensions/`（周边工具）、`api/incubator/`（还没稳定下来的 API）。新手找东西时容易迷路，记住这个规律。

### 2.2 构建系统与版本管理

- Gradle + Kotlin DSL（`build.gradle.kts`、`settings.gradle.kts`、`gradle.properties`）
- 版本策略见根目录 `VERSIONING.md`：无 `-alpha` 后缀的 artifact 有强向后兼容承诺；CI 用 japicmp 自动检测破坏性变更（源码里大量 `@Deprecated` 注释配合 `@Incubating` 标记，就是这套流程的产物）
- 目标运行环境：Java 8+（SDK 内部大量针对 Java 8 的降级代码，如 `LongCallable`）

---

## 三、API vs SDK 分层：门面、全局单例与混淆包装

### 3.1 设计原则

1. **API 层零依赖**：应用与框架只 import `opentelemetry-api`，不含任何 SDK 类
2. **无 SDK 优雅降级**：API 退化为 no-op，埋点代码不会崩（铁律二的代码体现）
3. **实现可替换**：规范接口是契约，任何厂商可提供自己的 SDK
4. **API 强稳定**：接口向后兼容，SDK 可独立迭代

### 3.2 入口接口：OpenTelemetry

`api/all/src/main/java/io/opentelemetry/api/OpenTelemetry.java`（真实方法签名，已对照源码验证）：

```java
public interface OpenTelemetry {

  static OpenTelemetry noop() {
    return DefaultOpenTelemetry.getNoop();
  }

  // 抽象方法: SDK 必须实现
  TracerProvider getTracerProvider();
  ContextPropagators getPropagators();

  // default 方法: SDK 缺席时自动降级为 no-op(铁律二的体现)
  default MeterProvider getMeterProvider() {
    return MeterProvider.noop();
  }

  default LoggerProvider getLogsBridge() {
    return LoggerProvider.noop();
  }

  // 便捷方法(内部委托给上面四个 Provider)
  default Tracer getTracer(String instrumentationScopeName) { ... }
  default Meter getMeter(String instrumentationScopeName) { ... }
  ...
}
```

三个设计细节值得注意：

1. **门面模式**：`OpenTelemetry` 是唯一入口，业务代码持有它而永不直接接触 Provider 细节；
2. **default 方法兜底**：`getMeterProvider()`/`getLogsBridge()` 给了 no-op 默认实现，任何不关心该信号的自定义实现无需强制实现它们；
3. **错误处理哲学**：便捷方法对 null 参数**不抛异常**，而是通过 `ApiUsageLogger` 以 FINE 级别记日志后返回 no-op 对象（详见 8.2 的双边界设计）。

### 3.3 全局单例：GlobalOpenTelemetry

`api/.../api/GlobalOpenTelemetry.java`（对照源码核实的关键逻辑）：

```java
public final class GlobalOpenTelemetry {

  private static volatile OpenTelemetry globalOpenTelemetry;

  public static OpenTelemetry get() {
    OpenTelemetry openTelemetry = globalOpenTelemetry;
    if (openTelemetry == null) {
      synchronized (mutex) {
        openTelemetry = globalOpenTelemetry;
        if (openTelemetry == null) {
          // 若 classpath 上有 autoconfigure 模块则自动装配并 set
          OpenTelemetry autoConfigured = maybeAutoConfigureAndSetGlobal();
          if (autoConfigured == null) {
            // 没有就兜底 set 一个 no-op, 后续 get() 不再进入慢路径
            set(OpenTelemetry.noop());
          }
          openTelemetry = globalOpenTelemetry;
        }
      }
    }
    return openTelemetry;
  }

  public static void set(OpenTelemetry openTelemetry) {
    // 内部: globalOpenTelemetry = obfuscatedOpenTelemetry(openTelemetry);
  }
}
```

两个设计亮点：

- **惰性自动装配**：`get()` 首次调用时若发现 classpath 上有 autoconfigure 模块，就自动读环境变量装配 SDK（这就是"只加依赖不写代码也能用"的原理）；否则兜底 no-op，之后 `get()` 永远走快路径。
- **Obfuscation（混淆包装）**：`set()` 时会用 `ObfuscatedOpenTelemetry` 包装后再存入静态字段。包装类逐方法委托给真实实现，但**不是 SDK 类型**——调用方拿到的引用无法强转为 `OpenTelemetrySdk`，从类型系统上强制"只能通过 API 交互"，防止 API 与 SDK 耦合。

### 3.4 API 层的 no-op 降级实现

`api/.../api/DefaultOpenTelemetry.java`：API 侧默认实现，所有 Provider 都指向 no-op 家族：

```
DefaultOpenTelemetry
  → getTracerProvider() → DefaultTracerProvider
      → DefaultTracer → 创建的 Span 全部是 no-op(PropagatedSpan/EmptySpan)
  → getMeterProvider() → DefaultMeterProvider
      → DefaultMeter → 各 Instrument 均为 no-op
  → getLogsBridge()  → DefaultLoggerProvider
      → DefaultLogger → LogRecordBuilder 空转
```

调用 no-op Span 的 `setAttribute()`/`end()` 等方法**零开销且无副作用**——这是"业务代码不感知 SDK 是否存在"的底层保证。

### 3.5 SDK 装配点：OpenTelemetrySdk

`sdk/all/src/main/java/io/opentelemetry/sdk/OpenTelemetrySdk.java` 是 SDK 三大信号的聚合点：

```java
public final class OpenTelemetrySdk implements OpenTelemetry {

  private final ObfuscatedTracerProvider tracerProvider;
  private final ObfuscatedMeterProvider meterProvider;
  private final ObfuscatedLoggerProvider loggerProvider;
  private final ContextPropagators propagators;

  // 构造时同样做混淆包装(与 GlobalOpenTelemetry 呼应)
  OpenTelemetrySdk(SdkTracerProvider tracerProvider, ...) {
    this.tracerProvider = new ObfuscatedTracerProvider(tracerProvider);
    ...
  }

  // SDK 用户显式需要 SDK 能力时的"合法后门"
  @Override
  public SdkTracerProvider getSdkTracerProvider() {
    return tracerProvider.unobfuscate();
  }
  ...
}
```

**混淆模式的应用**：API 使用者拿到的 Provider 是 Obfuscated 包装；`getSdkTracerProvider()` 这类方法只在 SDK 命名空间（`io.opentelemetry.sdk.*`）里提供，`unobfuscate()` 解包。这套"包装-解包"机制让 API/SDK 的类型边界变得可执行（编译器强制），而不是靠文档约定。

---

## 四、三大信号实现分析

### 4.1 Traces（链路追踪）

#### 4.1.1 核心类层次结构

```
       API 层(接口)                     SDK 层(实现)
┌─────────────────────┐        ┌──────────────────────────────┐
│ TracerProvider      │───────→│ SdkTracerProvider            │
│                     │        │   · ComponentRegistry 缓存    │
│    get(name)        │        │   · TracerSharedState 共享态  │
│    ↓                │        │                              │
│ Tracer              │───────→│ SdkTracer                    │
│    spanBuilder()    │        │                              │
│    ↓                │        │                              │
│ SpanBuilder         │───────→│ SdkSpanBuilder               │
│    startSpan()      │        │   · Sampler.shouldSample()   │
│    ↓                │        │   · 生成 SpanContext         │
│ Span(接口)          │───────→│ SdkSpan                      │
│                     │        │   (实现 ReadWriteSpan)       │
└─────────────────────┘        └──────────────────────────────┘
```

#### 4.1.2 SdkTracerProvider：追踪提供者

`sdk/trace/.../trace/SdkTracerProvider.java`：

```java
public final class SdkTracerProvider implements TracerProvider {

  // 跨所有 Tracer 共享的状态: 时钟/ID生成器/Resource/采样器/处理器链
  private final TracerSharedState sharedState;
  // 按 InstrumentationScope 缓存 SdkTracer, 同名 scope 复用实例
  private final ComponentRegistry<SdkTracer> tracerComponentRegistry;

  SdkTracerProvider(
      Clock clock,
      IdGenerator idsGenerator,
      Resource resource,
      Supplier<SpanLimits> spanLimitsSupplier,
      Sampler sampler,                       // 采样器(默认 AlwaysOn 的 ParentBased)
      List<SpanProcessor> spanProcessors,    // 处理器链
      ...) { ... }

  @Override
  public TracerBuilder tracerBuilder(String instrumentationScopeName) {
    // 优化: 没有注册任何 SpanProcessor 时, 直接走 no-op, 连 Span 都不创建
    if (sharedState.hasNoSpanProcessor()) {
      return TracerProvider.noop().tracerBuilder(instrumentationScopeName);
    }
    return new SdkSpanBuilder(instrumentationScopeName, tracerComponentRegistry, ...);
  }
}
```

两个设计模式：

- **共享状态（TracerSharedState）**：时钟、采样器、处理器链等全局配置抽出来共享，多个 Tracer 不重复持有；
- **组件注册表（ComponentRegistry）**：按 scope（名字+版本）缓存 Tracer，保证 `get("x")` 幂等。

#### 4.1.3 采样机制（Sampling）

`sdk/trace/.../trace/samplers/Sampler.java`：

```java
public interface Sampler {

  static Sampler alwaysOn();
  static Sampler alwaysOff();
  static Sampler parentBased(Sampler rootSampler);      // 生产推荐组合器
  static Sampler traceIdRatioBased(double ratio);       // 按比例

  SamplingResult shouldSample(
      Context parentContext,
      String traceId,
      String name,
      SpanKind spanKind,
      Attributes attributes,
      List<LinkData> parentLinks);
}
```

内置实现（实测 `samplers/` 目录）：

| 采样器 | 行为 |
|--------|------|
| `AlwaysOnSampler` | 全量记录 |
| `AlwaysOffSampler` | 全部丢弃 |
| `ParentBasedSampler` | **组合器**：有父 Span 时跟随父的决策，无父时用根采样器兜底 |
| `TraceIdRatioBasedSampler` | 用 TraceId 数值按比例采样（同一 Trace 的所有 Span 决策一致） |

**采样时机**：在 `SdkSpanBuilder.startSpan()` 里，创建 Span 之前。`SamplingResult` 返回三种 `SamplingDecision`：`DROP`（丢弃）、`RECORD_ONLY`（只记录不导出）、`RECORD_AND_SAMPLE`（记录并导出，即 sampled 位为 1）。**为什么按 TraceId 比例而不是随机数**：保证同一条分布式链路在所有服务上做出一致的采样决策，链路才完整。

#### 4.1.4 SpanProcessor：生命周期钩子

`sdk/trace/.../trace/SpanProcessor.java`（方法签名已核实）：

```java
public interface SpanProcessor {

  // Span 启动时回调(同步, 在 startSpan 调用线程)
  void onStart(Context parentContext, ReadWriteSpan span);
  boolean isStartRequired();

  // Span 结束时回调(同步, 在 end() 调用线程)
  void onEnd(ReadableSpan span);
  boolean isEndRequired();

  // 生命周期
  default CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
  default CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }

  // 组合多个处理器
  static SpanProcessor composite(SpanProcessor... processors);
}
```

实现家族：

```
SpanProcessor
 ├── MultiSpanProcessor        组合模式: 依次回调多个处理器
 ├── SimpleSpanProcessor       每个 Span end() 后立即同步调用 Exporter(调试用)
 ├── BatchSpanProcessor        异步批量导出(生产默认)
 └── (SDK 内部还有 semconv 适配等 instrumented 变体)
```

#### 4.1.5 BatchSpanProcessor：批量导出的默认参数

`sdk/trace/.../trace/export/BatchSpanProcessor.java`（构造常量与 onEnd 已对照源码核实）：

```java
// BatchSpanProcessorBuilder 中的默认值(实测):
//   DEFAULT_SCHEDULE_DELAY_MILLIS = 5000     → 每 5 秒导出一批
//   DEFAULT_MAX_QUEUE_SIZE        = 2048     → 内存队列容量
//   DEFAULT_MAX_EXPORT_BATCH_SIZE = 512      → 每批最多 512 条
//   DEFAULT_EXPORT_TIMEOUT_MILLIS = 30_000   → 单次导出超时 30 秒

public final class BatchSpanProcessor implements SpanProcessor {

  @Override
  public void onEnd(ReadableSpan span) {
    // 只有采样的 Span 才入队(可用 exportUnsampledSpans 开关放开)
    if (span != null && (exportUnsampledSpans || span.getSpanContext().isSampled())) {
      worker.addSpan(span);
    }
  }
}
```

内部结构：守护线程 `Worker` 循环消费 `ArrayBlockingQueue`，按 `scheduleDelay` 定时或攒够 `maxExportBatchSize` 触发一次 `SpanExporter.export()`。这四个参数就是高并发场景下调优导出的全部旋钮（`otel.bsp.*` 环境变量可覆盖）。

#### 4.1.6 SdkSpan：核心实现与状态机

`sdk/trace/.../trace/SdkSpan.java`（字段已对照源码核实）：

```java
final class SdkSpan implements ReadWriteSpan {

  // ── 不可变部分(构造时确定, 无需加锁) ──
  private final SpanContext context;            // TraceId/SpanId/Flags
  private final SpanContext parentSpanContext;  // 父上下文
  private final SpanKind kind;                  // INTERNAL/CLIENT/SERVER/...
  private final long startEpochNanos;           // 开始时间
  private final AnchoredClock clock;            // 锚点时钟(子 Span 用父的结束时刻做基准, 避免系统时钟跳变)

  // ── 可变部分(全部由专用锁保护) ──
  private final Object lock = new Object();     // 专用锁, 不用 this

  @GuardedBy("lock") private String name;               // 可被 updateName 修改
  @GuardedBy("lock") @Nullable private AttributesMap attributes;
  @GuardedBy("lock") @Nullable private List<Event<EventData>> events;
  @GuardedBy("lock") private StatusData status = StatusData.unset();

  // 结束状态机: end() 只允许成功执行一次
  @GuardedBy("lock") private EndState hasEnded = EndState.NOT_ENDED;
  enum EndState { NOT_ENDED, ENDING, ENDED }
}
```

`end()` 的状态机流程：

```
end() 被调用
  ↓ synchronized(lock)
  hasEnded == NOT_ENDED ?  ──否──→ 直接返回(重复 end 无害)
  ↓ 是
  hasEnded = ENDING                 (阻止并发二次结束)
  记录结束时间戳 endEpochNanos
  ↓ 退出锁
  spanProcessor.onEnd(this)         (此时对外呈只读 ReadableSpan)
  ↓
  hasEnded = ENDED
```

**线程安全设计**：专用锁对象（不用 `synchronized(this)`）、可变字段全部标注 `@GuardedBy("lock")`、状态机保证幂等结束。

#### 4.1.7 SpanContext：跨进程传播的最小信息集

`api/.../api/trace/SpanContext.java`：

```java
public interface SpanContext {
  String getTraceId();         // 16 字节 = 32 字符 hex
  String getSpanId();          // 8 字节 = 16 字符 hex
  TraceFlags getTraceFlags();  // 1 字节, bit0 = sampled
  TraceState getTraceState();  // 供应商扩展键值对
  boolean isRemote();          // 是否来自远程父 Span(跨进程提取而来)
}
```

| 字段 | 大小 | 说明 |
|------|------|------|
| TraceId | 16 字节 | 全局唯一，采样决策的输入 |
| SpanId | 8 字节 | Trace 内唯一 |
| TraceFlags | 1 字节 | sampled 标志位等 |
| TraceState | 变长（≤32 项） | 供应商扩展，W3C 规范约束 |

#### 4.1.8 SpanLimits：防内存膨胀的护栏

`SdkTracerProvider.builder().setSpanLimits(...)` 可定制：最大属性数、事件数、链接数、单属性值长度等。所有超限写入会被丢弃并计入 dropped 计数——保证单条 Span 的内存占用有上界，恶意/异常流量不会打爆 exporter。

### 4.2 Metrics（指标度量）

#### 4.2.1 仪器类型（Instruments）

Metrics API 的七类仪器（`api/.../api/metrics/` 下对应 `LongCounter`、`DoubleHistogram` 等成对出现）：

| 仪器类型 | 同步/异步 | 值语义 | 典型场景 |
|----------|-----------|--------|---------|
| **Counter** | 同步 | 单调递增非负 | 请求数、错误数 |
| **UpDownCounter** | 同步 | 可增可减 | 队列长度、活跃连接 |
| **Histogram** | 同步 | 分布统计 | 延迟、响应大小 |
| **Gauge** | 异步（回调） | 瞬时值 | CPU 使用率 |
| **ObservableCounter** | 异步（回调） | 单调递增 | 进程 CPU 时间 |
| **ObservableUpDownCounter** | 异步（回调） | 可增可减 | 当前连接数 |
| **ObservableGauge** | 异步（回调） | 瞬时值 | 内存占用 |

#### 4.2.2 SdkMeterProvider：与 Trace 不同的管线模型

`sdk/metrics/.../metrics/SdkMeterProvider.java`（核心字段实测）：

```java
public final class SdkMeterProvider implements MeterProvider {

  private final List<RegisteredReader> registeredReaders;   // 注册的读取器
  private final List<RegisteredView> registeredViews;       // 注册的视图
  private final MeterProviderSharedState sharedState;
  private final ComponentRegistry<SdkMeter> registry;
  ...
}
```

**与 Trace 管线的本质差异**——事件 vs 聚合：

```
Trace:   Span(离散事件) ──→ SpanProcessor ──→ SpanExporter       逐条转发, 批量发送
Metrics: 测量值 ──→ Aggregator(内存持续聚合) ──→ MetricReader 周期拉取快照 ──→ MetricExporter
                          ↑ 每 60 秒(可配)导出一次聚合结果, 而不是每个测量值都发送
```

Reader 分两种（对应后端两种模型）：

- **Push**（`PeriodicMetricReader`）：定时把聚合快照交给 exporter 发出去（OTLP 用）；
- **Pull**（`PrometheusMetricReader`）：后端来拉时才生成快照（Prometheus 用）。

#### 4.2.3 聚合器体系

SDK 内部聚合器（`sdk/metrics` 内部实现）：

```
Aggregator 接口
 ├── LongSumAggregator / DoubleSumAggregator          Sum 求和
 ├── LongLastValueAggregator / DoubleLastValueAggregator  Gauge 记最后值
 ├── DoubleExplicitBucketHistogramAggregator          显式桶直方图
 ├── DoubleBase2ExponentialHistogramAggregator        指数直方图(自适应桶)
 └── DropAggregator                                   丢弃
```

每个 Instrument × 每个 Reader/View 组合生成独立的聚合句柄，互不干扰。

#### 4.2.4 View：视图定制机制

View 允许不改业务代码调整指标行为：改名/改描述、换聚合类型（如 Sum → Drop 来"静音"某个指标）、过滤属性（降基数）、调整直方图桶边界。`SdkMeterProvider.builder().registerView(InstrumentSelector, View)` 注册。

#### 4.2.5 Exemplar：指标与链路的连接点

Exemplar（范例）是聚合中携带的"有代表性的原始样本"，可附带当前 Trace 的 SpanContext——这是在 Prometheus 直方图上"点击一个桶跳转到对应链路"的技术基础。实测相关类型：`ExemplarFilter`（`TraceBasedExemplarFilter`/`AlwaysOn`/`AlwaysOff`）+ 水库采样 `FixedSizeExemplarReservoir`/`HistogramExemplarReservoir`。

#### 4.2.6 MetricExporter 接口

```java
public interface MetricExporter extends AggregationTemporalitySelector,
                                        DefaultAggregationSelector {

  CompletableResultCode export(Collection<MetricData> metrics);
  CompletableResultCode flush();
  CompletableResultCode shutdown();
}
```

`getAggregationTemporality()` 决定时间语义：**Cumulative**（每次上报累计值，Prometheus 风格）或 **Delta**（上报增量，OTLP 可选）。

### 4.3 Logs（日志桥接）

#### 4.3.1 定位：桥接 API，不是日志框架

官方 JavaDoc（`api/.../api/logs/LoggerProvider.java` 原文）：

> The OpenTelemetry logs bridge API exists to enable bridging logs from other log frameworks (e.g. SLF4J, Log4j, JUL, Logback, etc) into OpenTelemetry and is NOT a replacement log API.

业务代码继续用 SLF4J/Logback 写日志；OTel 的角色是把它们**桥接**进统一的遥测管线（并自动附上 trace_id 与链路关联）。实际接入用 `opentelemetry-logback-appender-1.0` 等 appender 模块（在 instrumentation 仓库）。

#### 4.3.2 SdkLoggerProvider：与 Trace 完全对称的结构

```java
public final class SdkLoggerProvider implements LoggerProvider {

  private final LoggerSharedState sharedState;                 // 与 TracerSharedState 对称
  private final ComponentRegistry<SdkLogger> loggerComponentRegistry;  // 与 trace 对称
  ...
}
```

三大信号的对称性（学习一处即可举一反三）：

| 概念 | Traces | Metrics | Logs |
|------|--------|---------|------|
| Provider | SdkTracerProvider | SdkMeterProvider | SdkLoggerProvider |
| 共享状态 | TracerSharedState | MeterProviderSharedState | LoggerSharedState |
| 处理器 | SpanProcessor | （MetricReader 承担） | LogRecordProcessor |
| 导出器 | SpanExporter | MetricExporter | LogRecordExporter |
| Limits | SpanLimits（属性/事件/链接上限） | CardinalityLimitSelector | LogLimits（属性上限） |

（Metrics 没有 Processor 而用 Reader，因为它是聚合拉取模型——这是三信号中唯一的不对称点。）

#### 4.3.3 Severity：统一的日志级别数值

`api/.../api/logs/Severity.java`（实测枚举定义）：数值 0–24，每档 4 个子级：

```
UNSPECIFIED(0)
TRACE(1-4) → TRACE, TRACE2, TRACE3, TRACE4
DEBUG(5-8), INFO(9-12), WARN(13-16), ERROR(17-20), FATAL(21-24)
```

桥接时各种框架的级别（Log4j 的 TRACE/FATAL、JUL 的 FINE/WARNING…）映射到这把统一的 1–24 刻度上，后端无需理解各框架的差异。

---

## 五、Context 与传播机制

### 5.1 Context：不可变上下文容器

`context/src/main/java/io/opentelemetry/context/Context.java`：

```java
public interface Context {

  static Context current();          // 当前线程绑定的上下文
  static Context root();             // 根上下文

  <V> V get(ContextKey<V> key);

  // 不可变! with() 返回新 Context, 原对象不变
  <V> Context with(ContextKey<V> k1, V v1);

  // 绑定为当前线程上下文, 返回 Scope 供 try-with-resources 使用
  @MustBeClosed
  default Scope makeCurrent();

  // 包装 Runnable/Callable/Executor, 跨线程自动携带 Context
  default Runnable wrap(Runnable runnable);
  default Executor wrap(Executor executor);
  static Executor taskWrapping(Executor executor);
}
```

**不可变设计**是线程安全的根基：`with()` 像函数式链表一样返回新节点，并发读写无锁竞争。Span 的"当前 Span"就存在 Context 里（`SpanContextKey`）——`Span.makeCurrent()` 本质是把 Span 放进 Context 再 attach。

### 5.2 ContextStorage：存储提供者 SPI

默认实现 `ThreadLocalContextStorage`（`context/.../context/`）：

```java
enum ThreadLocalContextStorage implements ContextStorage {
  INSTANCE;

  private static final ThreadLocal<Context> THREAD_LOCAL_STORAGE = new ThreadLocal<>();

  @Override
  public Scope attach(Context toAttach) {
    Context beforeAttach = current();          // 记住旧值
    THREAD_LOCAL_STORAGE.set(toAttach);
    return () -> THREAD_LOCAL_STORAGE.set(beforeAttach);  // close() 时恢复
  }
}
```

通过 `ContextStorageProvider` SPI 可替换存储后端——`io.opentelemetry.context.internal.spi` 下的服务发现机制允许第三方（如响应式框架）接入：

- `StrictContextStorage`：调试模式，检测忘记 close 的 Scope；
- gRPC/Reactor 等框架的存储桥（把 OTel Context 挂到框架自己的上下文对象上）。

**典型用法**（跨线程传播的标准姿势）：

```java
try (Scope scope = context.makeCurrent()) {
  // 此代码块内 Context.current() == context
  executor.execute(context.wrap(() -> /* 子线程同样能看到该 Context */ ...));
}
// scope.close() 自动恢复旧上下文
```

### 5.3 Baggage：跨服务的业务行李

Baggage 是随调用链**跨服务传播的业务键值对**（如租户 ID、灰度标记），与 Span 属性的区别：属性只属于单个 Span，Baggage 挂在整个调用上下文上，下游每个服务都能读。

```java
// 写入
Baggage.currentBuilder()
    .put("tenant.id", "acme")
    .build()
    .makeCurrent();
try {
  // 后续的出站调用会把 tenant.id=acme 传播到下游(baggage 头)
} finally {
  // Scope 关闭
}

// 下游读取
String tenant = Baggage.fromContext(Context.current()).getEntryValue("tenant.id");
```

实现要点：API 层 `api/.../api/baggage/Baggage.java`；跨进程由 `W3CBaggagePropagator` 以 `baggage` 头传播（与 traceparent 平行）。注意 Baggage **默认不会自动变成 Span 属性**——需要显式同步（这是新手常见困惑：下游 Span 看不到上游 Baggage 值，除非用 `BaggageProcessor` 之类的组件或手动 `setAttribute`）。

### 5.4 TextMapPropagator：跨进程传播器

`context/.../context/propagation/TextMapPropagator.java`：

```java
public interface TextMapPropagator {

  static TextMapPropagator composite(TextMapPropagator... propagators);
  static TextMapPropagator composite(Iterable<TextMapPropagator> propagators);

  // 声明读写的字段(如 HTTP header 名), 便于中间件做白名单透传
  Collection<String> fields();

  // Context → carrier(出站请求)
  <C> void inject(Context context, @Nullable C carrier, TextMapSetter<C> setter);

  // carrier → Context(入站请求)
  <C> Context extract(Context context, @Nullable C carrier, TextMapGetter<C> getter);
}
```

**carrier 泛型设计**是亮点：HTTP 请求、gRPC Metadata、Kafka 消息头……各种载体通过 `TextMapGetter/Setter` 函数式接口适配，propagator 本身零修改。组合多个 propagator 时由 `MultiTextMapPropagator` 依次注入/提取。

### 5.5 W3C traceparent：标准传播格式

`api/.../api/trace/propagation/W3CTraceContextPropagator.java`（默认启用）：

```
出站 inject:
  Context 中的 SpanContext
    ↓
  traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
               │  └────────── 32 hex TraceId ──────────┘ └─ 16 hex SpanId ─┘ │
               version=00                                     trace-flags=01(sampled)

入站 extract:
  解析 traceparent → SpanContext.createFromRemoteParent(...)
    → Span.wrap(spanContext) 包成"远程引用 Span"
    → 存入 Context, 后续创建的 Span 自动成为其子 Span
```

格式约束：版本与 flags 各 2 字符 hex，TraceId/SpanId 为小写 hex；解析失败（格式非法/全零 ID）时静默回退到 root Context，不抛异常。

### 5.6 其他传播器扩展

| 传播器 | 格式 | 所在模块 |
|--------|------|---------|
| `W3CTraceContextPropagator` | `traceparent`/`tracestate`（**默认**） | api/all |
| `W3CBaggagePropagator` | `baggage` 头（**默认**） | api/all |
| `B3Propagator` / `B3MultiConfigurablePropagator` | Zipkin B3 单头/多头 | extensions/trace-propagators |
| `JaegerPropagator` | Jaeger 原生 `uber-trace-id` | extensions/trace-propagators |
| `OtTracePropagator` | LightStep OT 格式 | extensions/trace-propagators |
| `PassThroughPropagator` | 仅透传指定字段不解析 | api/incubator（实验性） |

多套协议共存的环境（比如上游是 B3 的老服务、下游是新服务），用 `composite` 组合即可，SDK 自动做多格式兼容。

---

## 六、SDK 扩展点体系

### 6.1 IdGenerator：ID 生成器

`sdk/trace/.../trace/IdGenerator.java`（已核实）：

```java
public interface IdGenerator {

  // 默认实现: RandomIdGenerator
  // 优先用 SecureRandom; 不可用时有随机数兜底逻辑
  static IdGenerator random() { return RandomIdGenerator.INSTANCE; }

  String generateSpanId();    // 16 字符 hex, 不允许全零
  String generateTraceId();   // 32 字符 hex, 不允许全零
  default boolean generatesRandomTraceIds() { ... }  // W3C 优化提示位
}
```

`generatesRandomTraceIds()` 是个有意思的细节：若 TraceId 低 56 位是均匀随机的（SecureRandom 场景），TraceIdRatioBasedSampler 可以只取低 56 位判断比例，性能更好。

### 6.2 Resource：资源元信息

`sdk/common/.../resources/Resource.java`（`@AutoValue` 生成值类）：

```java
// 默认 Resource = MANDATORY ∪ TELEMETRY_SDK
//   MANDATORY:     service.name = "unknown_service:java"   (实测默认值)
//   TELEMETRY_SDK: telemetry.sdk.name = "opentelemetry"
//                  telemetry.sdk.language = "java"
//                  telemetry.sdk.version = <当前版本>

public Resource merge(@Nullable Resource other);   // 属性合并(右侧优先)
public abstract Attributes getAttributes();
public abstract @Nullable String getSchemaUrl();   // 语义约定 schema 版本锚点
```

自动增强：classpath 上有 autoconfigure 时，`EnvironmentResourceProvider` 会从 `OTEL_SERVICE_NAME`/`OTEL_RESOURCE_ATTRIBUTES` 读入覆盖；SDK 自动配置还会探测宿主环境（AWS/GCP/Azure 云 resource 模块）补上 `cloud.provider` 等属性。**规范要求 `service.name` 必须设置**——默认值 `unknown_service:java` 就是给忘配的人看的"告警色"。

### 6.3 三大 Exporter 的对称设计

| | Traces | Metrics | Logs |
|---|--------|---------|------|
| 接口 | `SpanExporter` | `MetricExporter` | `LogRecordExporter` |
| 导出方法 | `export(Collection<SpanData>)` | `export(Collection<MetricData>)` | `export(Collection<LogRecordData>)` |
| 生命周期 | `flush()` / `shutdown()` | 同左 | 同左 |
| 组合 | `SpanExporter.composite(...)` | `MetricExporter.composite(...)` | `LogRecordExporter.composite(...)` |
| 空实现 | `SpanExporter.noop()` | `MetricExporter.noop()` | `LogRecordExporter.noop()` |
| 返回值 | `CompletableResultCode` | 同左 | 同左 |

内置实现：`exporters/otlp`（三信号 × gRPC/HTTP 双协议）、`exporters/prometheus`（Metrics 拉模式）、`exporters/logging`（Console 打印，调试）、`exporters/logging-otlp`（打印 OTLP 原始请求体，排查序列化问题）。

### 6.4 CompletableResultCode：统一异步结果

`sdk/common/.../common/CompletableResultCode.java`——所有 export/flush/shutdown 的统一返回类型：

```java
CompletableResultCode.ofSuccess();
CompletableResultCode.ofFailure();
CompletableResultCode.ofAll(codes);       // 聚合多个异步结果(多 exporter 并发导出)
code.join(timeout, unit);                 // 阻塞等待(应用退出前 flush 时用)
code.whenComplete(action);                // 异步回调
code.isSuccess();
```

设计意图：导出是异步的（尤其 BatchSpanProcessor 的后台线程），同步返回值没意义；`CompletableResultCode` 是一个轻量的、无依赖的 Future 替代品（Java 8 兼容，不引入 CompletableFuture 的取消语义复杂度）。

### 6.5 sender：导出器底层的传输抽象

`exporters/sender/` 模块把"怎么发"从"发什么"中剥离：

```
OtlpGrpcSpanExporter ──┐
OtlpHttpSpanExporter ──┤→ 序列化(OTLP protobuf) → Sender 抽象 → 实际传输
                       │
Sender 实现家族:
 ├── GrpcSender        grpc-netty-shaded
 ├── OkHttpSender      OkHttp(HTTP/protobuf)
 └── JdkHttpSender     JDK11+ HttpClient
```

好处：新增传输方式（或降级某个依赖）只需新 Sender；exporter 类只关心序列化与重试语义。

### 6.6 profiles：正在孵化中的第四信号

`sdk/profiles/` 是 v1.66 实测存在的实验性模块：Profiles（性能剖析采样数据，如 CPU/内存 profile）作为第四类信号正在 OTel 规范化中。三信号的 Provider/Processor/Exporter 模式也出现在这里——**这套对称架构的可扩展性正在被用来验证新信号**。

---

## 七、自动配置（Autoconfigure）

### 7.1 三层配置接口

1. **编程式配置**（最基础，见 3.5）：

```java
OpenTelemetrySdk.builder()
    .setTracerProvider(SdkTracerProvider.builder()
        .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
        .build())
    .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
    .build();
```

2. **环境变量/系统属性**（扁平键值，`sdk-extensions/autoconfigure`）：

```bash
OTEL_SERVICE_NAME=my-app
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
OTEL_TRACES_EXPORTER=otlp
OTEL_TRACES_SAMPLER=parentbased_traceidratio
OTEL_TRACES_SAMPLER_ARG=1.0
OTEL_PROPAGATORS=tracecontext,baggage
```

3. **声明式 YAML 配置**（结构化，`sdk-extensions/declarative-config`）：

```yaml
file_format: "1.0"
resource:
  attributes:
    - name: service.name
      value: my-app
tracer_provider:
  processors:
    - batch:
        exporter:
          otlp:
            endpoint: http://localhost:4317
```

三者可组合：YAML > 环境变量 > 代码默认值。

### 7.2 AutoConfiguredOpenTelemetrySdk 源码

`sdk-extensions/autoconfigure/.../autoconfigure/AutoConfiguredOpenTelemetrySdk.java`（签名已核实）：

```java
public abstract class AutoConfiguredOpenTelemetrySdk {

  // 一行完成自动装配并设为全局
  public static AutoConfiguredOpenTelemetrySdk initialize() {
    return builder().setResultAsGlobal().build();
  }

  public static AutoConfiguredOpenTelemetrySdkBuilder builder() { ... }
}
```

内部装配流程：

```
build()
 → 读 ConfigProperties(env + system properties + 声明式文件)
 → SPI(ServiceLoader) 发现扩展:
     SpanExporterProvider / MetricExporterProvider / LogRecordExporterProvider
     ResourceProvider / ConfigurablePropagatorProvider / SamplerProvider ...
 → 分别装配(实测同目录配置类):
     ResourceConfiguration → TracerProviderConfiguration → SpanExporterConfiguration
     MeterProviderConfiguration → LoggerProviderConfiguration → PropagatorConfiguration
 → OpenTelemetrySdk 完成, 可选 setResultAsGlobal()
```

SPI 的意义：**classpath 上加一个含 Provider 实现的 jar，新 exporter/propagator 自动可用**——零注册代码。所有 `*Provider` 接口定义在 `sdk-extensions/autoconfigure-spi`，OTLP/Console 等实现靠 `@AutoService` 注解生成 `META-INF/services` 注册文件。

### 7.3 常用配置项速查

| 环境变量 | 作用 | 默认值 |
|----------|------|--------|
| `OTEL_SERVICE_NAME` | 服务名 | `unknown_service:java` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | OTLP 端点 | `http://localhost:4317` |
| `OTEL_EXPORTER_OTLP_PROTOCOL` | grpc / http/protobuf | grpc |
| `OTEL_TRACES_EXPORTER` | trace 导出器（otlp/console/none） | otlp |
| `OTEL_METRICS_EXPORTER` | metrics 导出器 | otlp |
| `OTEL_LOGS_EXPORTER` | logs 导出器 | otlp |
| `OTEL_TRACES_SAMPLER` | 采样器名 | `parentbased_always_on` |
| `OTEL_TRACES_SAMPLER_ARG` | 采样参数（比例） | 1.0 |
| `OTEL_PROPAGATORS` | 传播器列表 | `tracecontext,baggage` |
| `OTEL_SDK_DISABLED` | 一键禁用 SDK | false |
| `OTEL_BSP_SCHEDULE_DELAY` | Batch 导出间隔 | 5000ms |
| `OTEL_BSP_MAX_QUEUE_SIZE` | Batch 队列容量 | 2048 |
| `OTEL_METRIC_EXPORT_INTERVAL` | 指标导出周期 | 60000ms |

---

## 八、设计模式与工程实践

### 8.1 关键设计模式汇总

| 模式 | 应用场景 | 代码示例 |
|------|----------|---------|
| **Builder** | 所有复杂对象创建 | `SdkTracerProviderBuilder`、`OpenTelemetrySdkBuilder` |
| **门面（Facade）** | API 唯一入口 | `OpenTelemetry` 接口 |
| **组合（Composite）** | 多 Processor/Exporter/Propagator | `SpanProcessor.composite()`、`MultiTextMapPropagator` |
| **装饰器** | 混淆包装 | `ObfuscatedOpenTelemetry`、`ObfuscatedTracerProvider` |
| **静态工厂** | 接口上的创建入口 | `Sampler.alwaysOn()`、`OpenTelemetry.noop()` |
| **状态机** | Span 结束幂等 | `EndState.NOT_ENDED → ENDING → ENDED` |
| **不可变对象** | Context/Attributes/Resource/SpanContext | `Context.with()` 返回新实例 |
| **共享状态** | 跨组件全局配置 | `TracerSharedState` |
| **注册表/缓存** | 按scope复用组件 | `ComponentRegistry<SdkTracer>` |
| **SPI/服务发现** | 扩展点解耦 | `SpanExporterProvider`、`ContextStorageProvider` |
| **空对象（Null Object）** | no-op 降级 | `DefaultTracer`、`NoopSpanProcessor` |
| **AutoValue 代码生成** | 值类样板消除 | `Resource`、`AutoConfiguredOpenTelemetrySdk` |

### 8.2 Null 安全与错误处理哲学

**两套边界，两种策略**（依据仓库 `docs/knowledge/api-design.md`）：

| 边界类型 | null/错误处理 | 理由 |
|----------|---------------|------|
| 配置时边界（Builder、工厂） | `Objects.requireNonNull()` 快速失败 | 只执行一次，bug 应在启动时立刻暴露 |
| 运行时边界（Span#addEvent、Meter 记录等高频路径） | `ApiUsageLogger` 记 FINE 日志 + 降级为 no-op | 每秒千次调用，绝不能因遥测故障影响业务 |
| SDK 内部边界（Sampler/Exporter 间调用） | 快速失败 | 说明是 SDK 自身 bug，应当炸出来 |

一句话概括：**遥测永远不能拖垮业务**——这是贯穿全部代码的第一原则。

### 8.3 包可见性控制

- `io.opentelemetry.api.*`：稳定公共 API，强兼容承诺
- `io.opentelemetry.sdk.*`：SDK 公开层，实现可换但语义稳定
- `...internal/`：内部实现，semver 不保证，跨模块共享需谨慎
- `...impl/`：public 但明确标注非应用开发者使用（供第三方实现 API 层的扩展点）
- `@Incubating` 注解：标记可能变化的孵化 API（对应 `*-alpha` artifact）

### 8.4 线程安全实践

1. 类级 `@ThreadSafe`/`@Immutable` 注解标注并发契约；
2. 专用锁对象（`private final Object lock = new Object()`），不用 `synchronized(this)`；
3. 可变字段全部 `@GuardedBy("lock")` 标注（配合 ErrorProne 静态检查）；
4. 能不可变就不可变（Context/Attributes/Resource/SpanContext）；
5. Span 的可变部分集中在 SdkSpan 一处，其余全只读——把同步范围压到最小。

### 8.5 兼容性与版本策略

- 无 `-alpha` 后缀的 artifact：强向后兼容（minor 升级安全）
- `*-alpha`：可破坏，但通常有 deprecation 过渡期
- CI 用 **japicmp** 自动对比二进制 API 差异，破坏性变更会在构建时被拦下
- Java 8+ 运行时兼容（SDK 内可见 `LongCallable` 这类针对 Java 8 的兼容代码）

---

## 九、动手验证：用本地源码跑通最小闭环

用 20 行代码把"API → SDK → Processor → Exporter → 输出"整条链跑通，所有源码行为亲眼看一遍。

**1. 依赖（Maven）：**

```xml
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-api</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-sdk</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-logging</artifactId>
</dependency>
<!-- 版本统一用 BOM: io.opentelemetry:opentelemetry-bom:1.66.0 -->
```

**2. 最小程序：**

```java
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

public class ManualDemo {
  public static void main(String[] args) throws Exception {
    SdkTracerProvider tracerProvider =
        SdkTracerProvider.builder()
            // Simple: 立即打印, 便于观察
            .addSpanProcessor(SimpleSpanProcessor.builder(LoggingSpanExporter.create()).build())
            .build();

    OpenTelemetrySdk sdk =
        OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
    GlobalOpenTelemetry.set(sdk);   // 全局单例接上 SDK

    Tracer tracer = GlobalOpenTelemetry.get().getTracer("manual-demo", "1.0.0");

    Span parent = tracer.spanBuilder("parent-operation").startSpan();
    try (var scope = parent.makeCurrent()) {
      parent.setAttribute("demo.tag", "hello");

      Span child = tracer.spanBuilder("child-operation").startSpan();
      try (var ignored = child.makeCurrent()) {
        Thread.sleep(50);          // 模拟业务逻辑
      } finally {
        child.end();               // 忘记 end 的 Span 永远不会被导出!
      }
    } catch (Exception e) {
      parent.recordException(e);
      parent.setStatus(StatusCode.ERROR);
    } finally {
      parent.end();
    }

    Thread.sleep(1000);            // 给导出留时间(生产用 provider.shutdown() 代替)
    tracerProvider.shutdown().join(1, java.util.concurrent.TimeUnit.SECONDS);
  }
}
```

**3. 输出（`LoggingSpanExporter` 真实格式，源码 `exporters/logging/.../LoggingSpanExporter.java` 的 `logAll` 方法逐字段拼接）：**

```
INFO 'child-operation' : <32位traceId> <16位spanId> INTERNAL [tracer: manual-demo:1.0.0] (true, [])
INFO 'parent-operation' : <同一个32位traceId> <16位spanId> INTERNAL [tracer: manual-demo:1.0.0] (true, [demo.tag:hello])
```

**4. 对照源码验证理解：**

- 两条日志 **TraceId 相同、SpanId 不同** → 4.1.7 的 SpanContext 与父子关系生效；
- `[tracer: manual-demo:1.0.0]` → 3.5 的 ComponentRegistry 按 scope 记录来源；
- 把 `startSpan()` 改成在 `SdkTracerProvider.builder()` 不加任何 Processor 再跑 → **什么都不输出**（4.1.2 的 `hasNoSpanProcessor()` 优化命中，连 Span 都不创建）；
- 把 child 的 `end()` 删掉 → child 永不出现（4.1.6 状态机：未 end 不进 onEnd）；
- 想看批量行为 → 换 `BatchSpanProcessor`，观察 5 秒延迟后才出日志（4.1.5 的默认 scheduleDelay）。

---

## 十、总结与参考资料

### 10.1 架构设计优点

1. **严格 API-SDK 分离 + no-op 降级**：埋点代码零耦合、可移植；混淆包装把边界变成编译期强制；
2. **三信号高度对称**：Provider/SharedState/Processor/Exporter/Limits 五件套在 Traces/Logs（以及孵化中的 Profiles）上结构一致，学习成本被刻意压低；唯一的不对称（Metrics 用 Reader/聚合）源于其流式聚合的数据本质；
3. **分层可插拔**：Sampler → SpanProcessor → SpanExporter → Sender 四级流水线，每一级都能替换或组合；
4. **Context 统一传播底座**：不可变 + ThreadLocal + SPI 可替换，三信号与 Baggage 共享同一载体；
5. **"不拖垮业务"的工程纪律**：no-op 降级、双边界错误处理、CompletableResultCode 异步语义、内存上限（Limits）处处体现。

### 10.2 规范契合度速览

| 规范要求 | Java 实现对应 |
|----------|---------------|
| API/SDK 分离 | `OpenTelemetry` 接口 vs `OpenTelemetrySdk` |
| no-op fallback | `DefaultOpenTelemetry` + `Default*` 家族 |
| 三大信号 API | TracerProvider/MeterProvider/LoggerProvider |
| W3C TraceContext | `W3CTraceContextPropagator` |
| W3C Baggage | `W3CBaggagePropagator` |
| Resource 语义约定 | `Resource` + `service.name`/`telemetry.sdk.*` 默认属性 |
| Instrumentation Scope | `InstrumentationScopeInfo`（name/version/schema_url） |
| 三种配置接口 | 编程式 / env var（autoconfigure）/ YAML（declarative-config） |
| OTLP 导出 | `exporters/otlp`（gRPC + HTTP/protobuf） |

### 10.3 代码位置速查表

| 想了解的点 | 文件路径（相对仓库根） |
|------------|----------------------|
| API 入口接口 | `api/all/src/main/java/io/opentelemetry/api/OpenTelemetry.java` |
| 全局单例 | `api/all/.../api/GlobalOpenTelemetry.java` |
| no-op 实现 | `api/all/.../api/DefaultOpenTelemetry.java` |
| SDK 装配 | `sdk/all/.../sdk/OpenTelemetrySdk.java` |
| TracerProvider SDK | `sdk/trace/.../sdk/trace/SdkTracerProvider.java` |
| Span 核心 | `sdk/trace/.../sdk/trace/SdkSpan.java` |
| 采样决策 | `sdk/trace/.../sdk/trace/samplers/Sampler.java` |
| 批量导出 | `sdk/trace/.../sdk/trace/export/BatchSpanProcessor.java` |
| MeterProvider SDK | `sdk/metrics/.../sdk/metrics/SdkMeterProvider.java` |
| LoggerProvider SDK | `sdk/logs/.../sdk/logs/SdkLoggerProvider.java` |
| Context 容器 | `context/src/main/java/io/opentelemetry/context/Context.java` |
| ThreadLocal 存储 | `context/.../context/ThreadLocalContextStorage.java` |
| W3C 传播 | `api/all/.../api/trace/propagation/W3CTraceContextPropagator.java` |
| Baggage API | `api/all/.../api/baggage/Baggage.java` |
| Resource | `sdk/common/.../sdk/resources/Resource.java` |
| 自动配置 | `sdk-extensions/autoconfigure/.../autoconfigure/AutoConfiguredOpenTelemetrySdk.java` |
| Console 导出器 | `exporters/logging/.../logging/LoggingSpanExporter.java` |
| 设计约定文档 | `docs/knowledge/api-design.md`、`VERSIONING.md` |

### 10.4 系列交叉阅读

- 《OpenTelemetry.md》：OTel 概念全景（三支柱、OTLP、Collector、语义约定）
- 《opentelemetry分析.md》：双仓库全景 + JavaAgent/Starter 插桩源码 + Spring Boot 4.1.1 实战
- 《Cilium.md》：eBPF 层面的网络可观测（Hubble 与 OTel 的互补关系）

### 参考资料

- OTel Java SDK 文档：https://opentelemetry.io/docs/languages/java/
- OTel 规范：https://opentelemetry.io/docs/specs/otel/
- opentelemetry-java 仓库：https://github.com/open-telemetry/opentelemetry-java
- VERSIONING（版本策略）：https://github.com/open-telemetry/opentelemetry-java/blob/main/VERSIONING.md
- OTLP 协议：https://opentelemetry.io/docs/specs/otlp/
