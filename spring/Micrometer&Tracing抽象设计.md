# Micrometer 与 Micrometer Tracing 抽象设计

> **这份文档回答什么**：Micrometer 与 Micrometer Tracing 各自把一个什么样的问题抽象成了什么形状；这个形状上留了哪些扩展孔；业界在这些孔上装了哪些实现；以及——一个具体的埋点（`@Timed`）落到存储里，究竟积累了几个值、每个值表示什么。
>
> **不回答什么**：源码逐行走读（那在三份已有文档里：`Micrometer.md`、`Micrometer Tracing.md`、`Micrometer&OpenTelemetry.md`），Spring Boot 自动装配的每个条件（见 `观测体系.md` 第八章）。
>
> **版本基线**：Micrometer 1.14+（含 1.16 行为）、Micrometer Tracing 1.4+、Spring Boot 3.4 / 4.x。凡涉及"某版本才有的东西"都会标注。
>
> **术语约定**：文中的「指标」= metric，「链路」= trace，「观测」= Observation（Micrometer 的缝合层）。题面里的 `@//time/` 是 `@Timed`，第八章统一按 `@Timed` 展开。

---

## 0. 一页结论

如果只记五句话：

1. **指标侧的抽象只有两个动作：登记身份、读数。** `Meter` 接口就两个方法——`getId()`（我是谁）和 `measure()`（我此刻能读出什么）。所有复杂度不在"记什么"，而在"读出来的数在时间上怎么算"（累计 / 步进 / 滑动窗口）。
2. **每个仪表读出来的是「一组 (值函数, 统计类型)」，不是一个数。** 所以 `Timer` 天然产 3 个值（count / total_time / max），直方图和分位数还不在 `measure()` 里，另走 `takeSnapshot()`。
3. **追踪侧的抽象也只有两个动作：开 span、传上下文。** 一切复杂度在"跨线程、跨进程时谁负责搬现场"——这就是 `CurrentTraceContext` 与 `Propagator` 存在的全部理由。
4. **Observation 是唯一的缝合层，解决 N×M 问题（N 种埋点点 × M 种信号）。** 它把"一次操作"变成可被动多路消费的事件流，指标、链路、日志各自挂 handler 上去听。
5. **抽象最贵的地方不是接口数量，而是语义泄漏。** 同一个 `@Timed`，在 Prometheus（累计型）上是"从进程启动到现在的总和"，在 OTLP/Datadog（步进型）上是"这一分钟的增量"——这条差异无法被抽象抹平，只能被文档化。

---

## 1. 问题域：这层抽象到底在解决什么

### 1.1 三个正交维度

任何可观测性埋点都在三个互相独立的维度上做选择，Micrometer 的抽象就是"把这三个维度拆开，各给一个接口"：

| 维度 | 变化的是什么 | 被抽象成 | 若不做抽象会怎样 |
|---|---|---|---|
| **信号** | 我要的是数（metric）还是因果链（trace）还是文本（log） | `Meter` / `Span` / SLF4J | 每种信号一套 API，业务代码里混着写 |
| **后端** | Prometheus / Datadog / OTLP / JMX … | `MeterRegistry` / `SpanExporter` | 换后端 = 改业务代码 |
| **传播** | 单机 / 跨线程 / 跨进程 / 跨异步边界 | `ThreadLocalAccessor` / `Propagator` / `Baggage` | 每个框架自己造一套上下文搬运 |

**关键观察**：这三个维度里，只有"信号"是业务代码真正要感知的；"后端"和"传播"必须被彻底藏起来。所以 Micrometer 的接口设计有一个反直觉特征——**面向业务的接口极薄（一两个方法），面向扩展的接口极多（30+ 个 SPI）**。

### 1.2 抽象的分界线：什么必须抽象，什么不能

这是理解整套设计的钥匙。Micrometer 划了一条很清楚的线：

| 必须抽象 | 刻意不抽象 |
|---|---|
| 仪表的**语义**（这是计数还是耗时） | 仪表的**存储**（直方图怎么摆、窗口怎么滚——由 registry 决定） |
| 仪表的**身份**（name + tags） | 后端的**命名**（`. → _` 还是 `. → /`，推迟到 NamingConvention） |
| 读数的**统计类型**（COUNT / MAX / VALUE） | 读数的**单位**（只声明 baseUnit，换算交给后端） |
| 上下文的**搬运协议**（谁 inject、谁 extract） | 传播**格式**（W3C 还是 B3，由 Propagator 决定） |
| 一次操作的**生命周期**（start / stop / error） | 生命周期的**副作用**（打指标还是开 span，由 Handler 决定） |

一句话：**Micrometer 抽象"是什么"，不抽象"变成什么样"。** 这是它能同时喂 20 多个后端而不崩的根本原因——如果它试图统一"变成什么样"，早就被最怪的那个后端（Graphite 的分层命名）拖死了。

### 1.3 依赖方向：一条不可逆的线

```
┌──────────────────────────────────────────────────────────────┐
│ micrometer-commons（最底层，谁都可以依赖）                       │
│ KeyValue / KeyName / StringUtils / 注解工具                    │
└──────────────▲──────────────────────────┬───────────────────┘
               │                          │
┌──────────────┴───────────┐  ┌───────────▼─────────────────┐
│ micrometer-observation   │  │ micrometer-core             │
│ Observation / Handler    │◀─┤ Meter / Registry / Binder   │
│ Convention / KeyValues   │  │ Distribution                │
└──────────────▲───────────┘  └───────────┬─────────────────┘
               │                          │
┌──────────────┴───────────┐  ┌───────────▼─────────────────┐
│ micrometer-tracing       │  │ micrometer-registry-*       │
│ Tracer / Span / Baggage  │  │ （20+ 个后端实现）             │
└──────────────▲───────────┘  └─────────────────────────────┘
               │
┌──────────────┴───────────┐
│ bridge-{brave,otel}      │
└──────────────────────────┘
```

（箭头指向**被依赖方**。）

三条硬约束，违反了整个体系就散架：

1. **`observation` 不能依赖 `core`**（反向可以：`core` 依赖 `observation`，`DefaultMeterObservationHandler` 就住在 core 里）。所以 Observation 的高低基数用 `KeyValue` 而不是 `Tag`；指标语义不能反向污染观测模型。也正因如此，`Tag` 从未 `extends KeyValue`——两者刻意平行，只在 `DefaultMeterObservationHandler.createTags` 处手工转换。
2. **`core` 不能依赖 `tracing`**。所以指标侧完全不知道 traceId 的存在；exemplar 只能由 tracing 侧的 `TracingAwareMeterObservationHandler` **反向**塞进 meter 样本。
3. **`tracing` 依赖 `observation`**。`TracingObservationHandler` 系列本质上都是 `ObservationHandler`——这就是"想产出 span 必须先有 Observation"的原因。

**为什么这条线值得记住**：你在排查"为什么我的 traceId 没进指标"这类问题时，答案往往就是这条依赖线——中间少一个 handler，链路就断了，而且编译期毫无提示。

---

## 2. 指标侧的抽象体系

### 2.1 身份系统：`Meter.Id` = name + tags + type

一个仪表在注册表里的唯一身份是 `Meter.Id`，包含五项：`name`、`tags`、`type`、`baseUnit`、`description`（还有内部的 `syntheticAssociation`，用于 Timer 与它派生的 LongTaskTimer 关联）。

最要紧的一条实现细节：**`Id.equals()` 只比较 `name` 和 `tags`，不比较 `type`。**

```java
// Meter.java（内部类 Id）
public boolean equals(Object o) {
    Meter.Id other = (Meter.Id) o;
    return name.equals(other.name) && tags.equals(other.tags);   // type 不参与
}
```

这条设计带来三个直接后果：

- **同名同标签天然复用**。`Timer.builder("x").tag("a","1").register(r)` 调一百次，只有第一个真的创建对象，后面都返回同一个。
- **类型冲突时是"先到先得"**。先注册了 Counter 叫 `x{a=1}`，再想注册同名 Timer，拿到的是**已有那个 Counter 的拒绝**（`MeterRegistry` 会返回 noop 或抛 `IllegalArgumentException`，取决于 registry 实现与 `LoggingMeterRegistry` 配置）。这是线上"指标突然变 0"的经典原因。
- **`baseUnit` 与 `description` 不参与身份**。两个 meter 名字标签一样但单位不同，会被判为同一个，后注册的单位被丢弃。所以**指标名里绝不能靠"同名不同单位"区分**。

`Tag` 本身是 `key/value` 二元组，`Tags` 是不可变集合，`and(...)` 做链式拼接。它 `extends Comparable<Tag>`——排序是为了让 Id 的 hash 稳定，进而让"同 tag 不同顺序"也能命中同一个 meter。

### 2.2 行为系统：8 种仪表 + 8 个统计类型

这是全篇最需要背下来的一张表。**仪表回答"语义"，`Statistic` 回答"读数的含义"**，两者是多对多关系。

| 仪表 | 语义 | `measure()` 产出 | 典型埋点 |
|---|---|---|---|
| `Counter` | 只增不减的事件计数 | `COUNT` | 请求数、异常数、发送字节数 |
| `Gauge` | 瞬时值（可增可减） | `VALUE` | 队列长度、内存占用、在线连接数 |
| `Timer` | 耗时 + 次数 | `COUNT` + `TOTAL_TIME` + `MAX` | 接口耗时、GC 停顿 |
| `DistributionSummary` | 非时间的事件大小 + 次数 | `COUNT` + `TOTAL` + `MAX` | 报文字节数、SQL 返回行数 |
| `LongTaskTimer` | 正在进行的长任务 | `ACTIVE_TASKS` + `DURATION`（+ `MAX`） | 进行中的批处理、未完成的下载 |
| `FunctionCounter` | 从对象方法读单调值 | `COUNT` | 缓存命中累计数 |
| `FunctionTimer` | 从对象方法读次数 + 耗时 | `COUNT` + `TOTAL_TIME` | JIT 编译时间、连接池获取耗时 |
| `MultiGauge`（@Incubating） | 表格型数据（一行一个维度组合） | 每行注册为一个 `Gauge`，产 `VALUE` | 连接池明细、按分片的容量 |

`Statistic` 枚举只有 **8 个**常量（这是后端序列命名的唯一依据，Prometheus 的 `_count`/`_sum`/`_max` 后缀全部由它派生）：

| 常量 | `getTagValueRepresentation()` | 含义 |
|---|---|---|
| `COUNT` | `"count"` | 事件次数 / 调用次数 |
| `TOTAL` | `"total"` | 数量总和（DistributionSummary） |
| `TOTAL_TIME` | `"total"` | 时间总和，后端基础时间单位 |
| `MAX` | `"max"` | 窗口内最大值 |
| `VALUE` | `"value"` | 瞬时值（Gauge） |
| `UNKNOWN` | `"unknown"` | 未定（自定义仪表兜底） |
| `ACTIVE_TASKS` | `"active"` | 当前活跃任务数（LongTaskTimer） |
| `DURATION` | `"duration"` | 运行中任务的已持续时长（LongTaskTimer） |

**注意两个"不在里面"的东西**——这是最容易误判的地方：

- **分位数（P50/P99）不是 `Statistic`**。它来自 `HistogramSnapshot.percentileValues()`，是"对曲线的采样"，不是"对总量的统计"。所以 `timer.measure()` 永远只有 3 项，P99 得走 `takeSnapshot()`。
- **直方图桶也不是 `Statistic`**。它是 `HistogramSnapshot.histogramCounts()`，一组 `(le, cumulativeCount)`。

### 2.3 注册表：meter 的一生走七步

`MeterRegistry` 是**抽象类**（不是接口），左手是线程安全的 meter 池，右手把"怎么造具体仪表"留成 `newCounter/newTimer/...` 给子类填空。注册一个新 meter 的管线是固定的七步：

```
① getOrCreateMeter(id)              查池子，命中直接返回
② 依次过 MeterFilter                  accept/deny → map（改名/改标签）→ maximumAllowableTags
③ 再查一次池子                       因为 map 可能改成已存在的 id（关键：防重复创建）
④ newXxx(...)                        子类造仪表（cumulative / step 两套实现）
⑤ 写入 meterMap                      以最终 id 为键
⑥ 通知 Config.onMeterAdded           各 registry 在这里做"预登记"（如 Prometheus 建 Collector）
⑦ 发布 MeterAddedEvent               Composite 在这里同步给子 registry
```

第 ③ 步最容易被忽略：**`MeterFilter.map()` 改完 id 之后必须重查池子**，否则"两个不同埋点被 map 成同一个名字"会产生两个 meter 对象、两条同名时间序列，后端报 duplicate series。

**五种 registry 形态**（决定了数据在时间轴上的形状，见 2.5）：

| 形态 | 代表实现 | 换步行为 | 适合 |
|---|---|---|---|
| `SimpleMeterRegistry` | 自己玩 | 不换步，纯累计 | 单测、`/actuator/metrics` 的本地快照 |
| `Cumulative` 族 | Prometheus（被 pull 时读） | 不换步，重命名不加 `_total` 逻辑 | pull 型后端 |
| `Step` 族 | OTLP、Datadog、NewRelic | 每 1 分钟算一次差值并清零 | push 型后端 |
| `Push` 族 | StatsD、Graphite | 按固定间隔推，语义自定义 | 老式 UDP/TCP 后端 |
| `Composite` 族 | `CompositeMeterRegistry` | 转发给子 registry，自身不存数 | 同时喂多个后端 |

外加两个特殊成员：`NoopMeterRegistry`（零开销占位，`@ConditionalOnEnabledMetricsExport=false` 时用）、`LoggingMeterRegistry`（每秒把读数打到日志，调试神器，**生产禁用**）。

### 2.4 时间语义：同一个 Timer 在两种后端上含义相反

这是"抽象无法抹平的语义泄漏"，必须单独讲。

| | Cumulative（Prometheus） | Step（OTLP / Datadog） |
|---|---|---|
| `count` | 从进程启动到现在的**累计次数** | 本步（默认 1 分钟）内的**次数** |
| `sum` | **累计**总耗时 | 本步内的**增量**耗时 |
| `max` | 最近一个**时间窗口**（默认 2 分钟窗 / 3 格）内的最大 | 本步内的最大 |
| 查询方式 | `rate(sum)/rate(count)` 才是平均值 | 直接 `sum/count` 就是平均值 |
| 典型故障 | 重启后 `rate()` 出现负值断点 | 采样丢失时数值"塌陷"，无累计兜底 |

**所以：一段"取平均值"的 PromQL 写在 Datadog 上必然错，反之亦然。** 这不是配置问题，是抽象层之外的世界观差异。Micrometer 能做的是把两种形态都实现（`instrument.cumulative` / `instrument.step` 两个包），不能做的是让它们长得一样。

### 2.5 统计系统：三本账 + 两条分位数路线

`Timer` 的 `record(...)` 落到内存里，是一个模板方法串起三层：

```
record(amount, unit)
  ├─ ① histogram.recordLong(nanos)        直方图（可能是 NoopHistogram，零开销）
  ├─ ② recordNonNegative(amount, unit)    子类记三本账：count / total / max
  └─ ③ pauseDetector.recordInterval()     停顿补偿采样（需 LatencyUtils，默认关）
```

三本账的具体载体（`CumulativeTimer`）：

| 账本 | 载体 | 是否有窗口 | 为什么 |
|---|---|---|---|
| count | `LongAdder` | 无 | 次数永远只增 |
| total | `LongAdder`（纳秒） | 无 | 总耗时永远只增 |
| max | `TimeWindowMax` | **有**（expiry=2min，bufferLength=3） | 否则 max 永远停在三个月前那次尖刺上 |

`DistributionStatisticConfig` 的默认值（这张卡会被"仪表配置 → registry 默认配置 → MeterFilter 改写"层层 merge）：

```java
percentilesHistogram  = false              // 不开直方图
percentilePrecision   = 1                  // HdrHistogram 精度位数
minimumExpectedValue  = 1.0                // Timer 语义下是 1ms
maximumExpectedValue  = POSITIVE_INFINITY  // 关键：默认无上界
expiry                = Duration.ofMinutes(2)
bufferLength          = 3                  // 窗口切成 3 格 → 每格 40s
percentiles           = null               // 不发布客户端分位数
serviceLevelObjectives = null              // 不设 SLO 边界
```

两条分位数路线必须二选一（也可以都开，代价翻倍）：

| 路线 | 触发条件 | 实现 | 精度 | 能否跨实例聚合 |
|---|---|---|---|---|
| **客户端分位数** | `publishPercentiles(0.5,0.95,0.99)` | HdrHistogram（`TimeWindowPercentileHistogram`） | 准（受 precision 影响） | **不能**——每实例各算各的，取平均是错的 |
| **聚合直方图** | `publishPercentileHistogram()` 或设 SLO | 固定边界桶（`TimeWindowFixedBoundaryHistogram`） | 受桶边界限制 | **能**——桶可相加，后端算 `histogram_quantile` |

桶从哪来：一张预生成的"4 的幂 + 三分之一插值"表（`PercentileHistogramBuckets.PERCENTILE_BUCKETS`），按 `[minimumExpectedValue, maximumExpectedValue]` 切片。**因为默认 max 是 `+Infinity`，切片几乎等于整张表（上百个桶）**——这就是"只开了一个 `publishPercentileHistogram()` 就把 Prometheus 序列数打爆"的根因。正确姿势是同时显式设定 min/max（例如 1ms ~ 10s），让切片落在几十个桶。

### 2.6 投递系统：把数变成后端能吃的形状

三层翻译，逐层丢信息：

```
仪表读数（double + Statistic + baseUnit）
   ↓ ① 单位换算：baseUnit 是秒，就一律换成秒（Timer 默认 SECONDS，Summary 无单位）
   ↓ ② 命名映射：NamingConvention.name(name, type, baseUnit)
   ↓         prometheus:  http.server.requests + 秒 → http_server_requests_seconds
   ↓         graphite:    保留点分 + 分层路径
   ↓ ③ 序列化：Prometheus 文本 / OTLP protobuf / Datadog JSON / StatsD 行协议
后端存储
```

`NamingConvention` 只声明三个方法（`name` / `tagKey` / `tagValue`），后端专属实现全部在各自模块里——core 只提供 `identity`、`snakeCase`、`camelCase`、`upperCamelCase`、`slashes` 五个常量。**这是"维度优先"能兼容"分层命名"后端的关键**：同一个 meter，Prometheus 拿到的是 `name{tag=...}`，Graphite 拿到的是 `a.b.c.tag.value`。

---

## 3. 追踪侧的抽象体系

### 3.1 两个动作，四个名词

| 名词 | 一句话 | 谁产生 |
|---|---|---|
| **Trace** | 一次端到端请求的全链路，由 traceId 标识 | 入口的第一个 span |
| **Span** | 链路里的一个工作单元，有 spanId + parentId + 起止时间 | `Tracer.nextSpan()` |
| **TraceContext** | 一个 span 的"身份"（traceId + spanId + sampled + baggage） | `Span.context()` |
| **CurrentTraceContext** | "当前线程/当前协程的现场"（一个 ThreadLocal 包装） | 框架自动管理 |

**两个动作**：`Tracer.withSpan(span)`（开，并把它设为"当前"）、`Propagator.inject/extract`（传，跨进程序列化/反序列化）。其余所有类都是这两个动作的配角。

### 3.2 `Tracer` / `Span` / `SpanCustomizer` / `ScopedSpan`

```java
public interface Tracer {
    Span nextSpan();                                  // 新建一个 span（不激活）
    Span nextSpan(TraceContextOrSamplingFlags flags);  // 继承上游上下文
    Span currentSpan();                                // 读"现场"，没有就返回 noop
    ScopedSpan startScopedSpan(String name);           // 新建 + 激活 + try-finally 自动关闭
    Span.Builder spanBuilder();                        // 先配 tag 再开
    <T> T withSpan(Span span, Tracer.SpanInScope scope); // 激活期间执行，结束还原现场
}
```

四个设计要点：

- **`Span` 与 `SpanCustomizer` 分离**。`SpanCustomizer` 只有 `name/tag/event` 三个方法，没有 `start/end`——它存在的意义是"让业务代码能加标签，但不能结束 span"。这是接口隔离原则在追踪里的典型应用：日志组件拿到的是 `SpanCustomizer`，就永远不会误关别人的 span。
- **`currentSpan()` 永不返回 null**，返回 `NoopSpan`。所以 `tracer.currentSpan().tag("k","v")` 在没有链路时是安全的空操作——这是"埋点不能因为没开链路就 NPE"的保证。
- **`ScopedSpan` 是"懒人版"**：它内部持有 `SpanInScope`，`close()` 时既结束 span 又还原现场。适合"一个方法一个 span"的简单场景，代价是**必须 try-finally**。
- **`Span.Builder` 与 `nextSpan()` 的分工**：需要 `remoteParent`、`kind(CLIENT/SERVER/PRODUCER/CONSUMER)`、`tag` 预置时用 builder；否则用 `nextSpan()` 更省。

### 3.3 `TraceContext` 与 `CurrentTraceContext`：身份 vs 现场

这是初学者最容易混的一对：

| | `TraceContext` | `CurrentTraceContext` |
|---|---|---|
| 是什么 | 一个 span 的**数据**（不可变 ID 集合） | 一个**存取现场**的机制（ThreadLocal） |
| 生命周期 | 随 span 生死 | 进程级单例 |
| 关键方法 | `traceId()` / `spanId()` / `parentId()` / `sampled()` | `get()` / `newScope(ctx)` / `wrap(Runnable)` |
| 谁实现 | `BraveTraceContext` / `OtelTraceContext` | `ThreadLocalCurrentTraceContext`（默认） |

**为什么必须分开**：因为"身份"要跨进程传（序列化进 HTTP header），"现场"只在进程内有效（ThreadLocal 无法序列化）。把两者合成一个类，是自研追踪系统最常见的第一个设计错误。

`CurrentTraceContext.newScope()` 返回的是 `Scope`（`AutoCloseable`），`close()` 时**恢复成进入前的那个值**（而不是清空）——这是支持嵌套 span 的前提。实现上是一个栈，不是一个变量。

### 3.4 `Propagator` 与 `Propagation`：跨进程的搬运协议

```java
public interface Propagator {
    <C> void inject(TraceContext context, C carrier, Setter<C> setter);       // 出口：写进 header
    <C> TraceContextOrSamplingFlags extract(C carrier, Getter<C> getter);     // 入口：从 header 读
}
```

三个必须知道的事实：

1. **`Propagator` 是"单个格式"，`Propagation` 是"格式组合"**。`Propagation` 工厂把多个 `Propagator` 串起来（`Propagation.builder().add(...)`），默认 `Propagation.W3C` 或 `Propagation.B3`。生产上常见的是"W3C traceparent + 自定义 baggage"两条并联。
2. **`extract` 返回的是 `TraceContextOrSamplingFlags`，不是 `TraceContext`**。因为上游可能只给了采样标志（`sampling flags`）而没给完整上下文——这时你仍然要能新建一个 span，并继承"采/不采"的决定。
3. **传播格式与桥绑定**。`micrometer-tracing-bridge-brave` 默认 B3（也支持 W3C），`bridge-otel` 默认 W3C `traceparent`。**一条链路上两种格式混用 = 断链**，且不报错，只是链路从中间断成两截。

### 3.5 `Baggage`：跟着链路走的业务数据

Baggage 是"随 trace 一起传的业务上下文"（用户 ID、租户 ID、灰度标签）。它和 traceId 的区别：**traceId 由框架管，baggage 由你管**。

```java
Baggage baggage = Baggage.builder()
    .name("tenant").tagKey("tenant").build();
// 出口 side：写进 header（默认 header 名就叫 tenant）
// 入口 side：从 header 读出来
```

三个坑：

- **默认不上链路**（`remoteBaggage` 需显式声明），否则你的自定义 header 根本不会出现在下游。
- **基数炸弹**：把 userId 放进 baggage，等于让每条 trace 的 header 都带一个高基数键，跨进程体积暴涨。baggage 只放**低基数的路由/租户类**信息。
- **与 MDC 的接缝**：日志里的 `%X{tenantId}` 依赖 `Baggage` → MDC 的桥（Boot 里由 `CorrelationScopeDecorator` 完成）。这条桥断掉的表现是"trace 里有、日志里没有"。

### 3.6 两座桥的语义映射

Micrometer Tracing 只定义接口，真正干活的是两个桥。它们的差异会直接体现在你的数据上：

| 维度 | `bridge-brave` | `bridge-otel` |
|---|---|---|
| 底层 | Brave（Zipkin 系） | OpenTelemetry SDK |
| 默认传播格式 | B3（`X-B3-TraceId` 等） | W3C（`traceparent`） |
| ID 长度 | 64/128 位 traceId，64 位 spanId | 128 位 traceId，64 位 spanId |
| 采样控制 | Brave `Sampler` | OTel `Sampler`（`parentbased_*`） |
| 导出 | `zipkin-reporter`（HTTP/Kafka） | `SpanExporter`（OTLP 为主） |
| 与 agent 共存 | 会打架（两套 tracer） | 也会打架（两套 SDK） |
| 适合 | 已有 Zipkin 体系 | 目标是 Tempo/Jaeger/统一 OTel Collector |

**"共存会打架"的机理**：bridge 自己的 `Tracer` 与 Java agent 的 `Instrumentation` 各自维护 `CurrentTraceContext`（或 OTel `Context`），同一个线程里两份"现场"互不认账，结果是同一方法两个 span、或者父子关系错乱。选择只有一个：**要么用桥（代码埋点），要么用 agent（字节码埋点），不要同时开。**

---

## 4. 缝合层：Observation

### 4.1 为什么需要它：N×M → N+M

没有 Observation 时，一个"下单"操作要同时记指标和链路，你得写：

```java
Timer.Sample s = Timer.start(registry);
Span span = tracer.nextSpan().name("order.create").start();
try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
    ...
} catch (Exception e) { span.error(e); throw e; }
finally { s.stop(timer); span.end(); }
```

N 种操作 × M 种信号 = N×M 段样板代码，且**顺序、异常处理、上下文还原全部靠人肉保证**。

有了 Observation：

```java
Observation.createNotStarted("order.create", registry)
    .lowCardinalityKeyValue("channel", channel)
    .observe(() -> doCreate());
```

N + M：操作只声明一次，信号由 `ObservationHandler` 各自挂上去。

### 4.2 生命周期与五个回调

```
Observation.start()
   ├─ onStart(context)                     ← 所有 handler 依次执行（有序！）
   ├─ [业务代码]
   ├─ onEvent(event)                       ← 中途打点（如"重试一次"）
   ├─ onError(throwable)                   ← 异常路径（不会阻止异常继续抛）
   └─ onStop(context)                      ← 收尾：算耗时、关 span、记指标
```

外加两个作用域回调：`onScopeOpened` / `onScopeClosed`（用于"把上下文设为当前"这件事本身也需要被观测的场景）。

**顺序问题**：handler 默认按 `@Order` 排序，但有硬约束——`TracingObservationHandler` 必须在 `DefaultMeterObservationHandler` **之前**，因为后者要靠前者留下的 `TracingContext` 来填 exemplar（traceId）。顺序错了不会报错，只是 exemplar 永远为空。

### 4.3 `Context` 与低/高基数

`Observation.Context` 是一个可继承的"行李箱"：

```java
public class Context {
    private final Map<Object, Object> map;      // 任意 handler 都能往里塞东西
    private Throwable error;
    private String name;                        // 操作名（会成为 metric name / span name）
    private String contextualName;              // 上下文名（如 "http get /orders/{id}"）
    private KeyValues lowCardinalityKeyValues;  // → metric tag
    private KeyValues highCardinalityKeyValues; // → span attribute（不进 metric！）
}
```

**低/高基数的分界线是整个 Observation 设计里最实用的一条规则**：

| | 进哪里 | 基数要求 | 例子 |
|---|---|---|---|
| **Low cardinality** | metric 的 tag | 必须可控（几十~几百） | `method=GET`、`status=200`、`outcome=SUCCESS` |
| **High cardinality** | span 的 attribute | 可以任意（但有上限） | `http.url=/orders/12345`、`user.id=u_88`、SQL 语句 |

一条铁律：**高基数键值绝不能进指标**。把 `http.url` 当 tag 打指标，是 Prometheus 内存爆炸的第一大原因（每个 URL 一条时间序列）。`http.server.requests` 用 `uri`（模板化后的 `/orders/{id}`）而不是 `http.url`，就是为了这条规则。

### 4.4 六个内置 Handler 的分工

| Handler | 挂在哪个信号 | 干什么 | 依赖 |
|---|---|---|---|
| `TracingObservationHandler` | trace | 开 span、设名字、把 `TraceContext` 塞进 Context | `Tracer` |
| `DefaultTracingObservationHandler` | trace | 给 span 打 tag/event、错误标记、结束 span | 上一个 |
| `PropagatingSenderTracingObservationHandler` | trace | 出口：`inject` 到 carrier（HTTP header / Kafka record） | `Propagation` |
| `PropagatingReceiverTracingObservationHandler` | trace | 入口：从 carrier `extract`，建 child span | `Propagation` |
| `DefaultMeterObservationHandler` | metric | 把 Context 翻译成 Timer（`Observation` → `Timer.record`） | `MeterRegistry` |
| `TracingAwareMeterObservationHandler` | metric + trace | 把当前 traceId 填进 exemplar | 前两者都在 |

最后一个值得一提：它是唯一**跨两个信号**的 handler——只有它同时需要 `TracingContext` 和 `MeterRegistry`，也正因如此，它在依赖图上属于 `micrometer-tracing` 而不是 `micrometer-core`。

### 4.5 Convention / Filter / Predicate：三个容易混的扩展点

| 扩展点 | 触发时机 | 能改什么 | 不能改什么 |
|---|---|---|---|
| `ObservationConvention` | `start` 时 | name、contextualName、低/高基数键值 | 不能阻止观测发生 |
| `GlobalObservationConvention` | 同上，全局兜底 | 同上（优先级低于具体 Convention） | 同上 |
| `ObservationFilter` | `stop` 时（map 阶段） | 增删改 Context 里的键值 | 不能改 name |
| `ObservationPredicate` | `start` 之前 | **决定这个观测要不要发生** | 不能改内容 |

选型口诀：**"改内容"用 Convention，"改结果"用 Filter，"别发生"用 Predicate。**

### 4.6 与 `@Timed` 的关系：两代 API

| | `@Timed` / `@Counted` | `@Observed` |
|---|---|---|
| 所属 | `micrometer-core`（1.0 起） | `micrometer-observation`（1.10 起） |
| 需要什么 | `TimedAspect` / `CountedAspect` bean（**Boot 不自动注册**） | `ObservedAspect` bean（Boot 有条件自动注册） |
| 产出 | 只有 Timer / Counter | Observation → 可派生 span + Timer + 日志 |
| 扩展方式 | `TimedAspect` 构造参数（tags 函数、skip 判定） | Convention / Filter / Predicate 全套 |
| 现状 | 兼容保留，Spring 官方建议新代码用 `@Observed` | 推荐 |

**结论**：`@Timed` 是"指标专用的一代"，`@Observed` 是"信号无关的二代"。存量代码里的 `@Timed` 不用急着改，但新埋点应该用 Observation API——否则你永远拿不到 span 与 exemplar。

---

## 5. 扩展点总表

按抽象层排列，一共 30 个。这张表的价值在于"遇到需求先查表，别自己造轮子"。

### 5.1 仪表层（Instrument）

| # | 扩展点 | 接口 / 类 | 典型用途 | 代价 / 坑 |
|---|---|---|---|---|
| 1 | 自定义仪表类型 | `Meter.builder(name, type, ...)` + `MeterProvider`（1.12+） | 后端有特殊语义的仪表（如 Atlas 的 percentiles） | 后端不认识就退化成 `UNKNOWN` |
| 2 | 第三方状态适配 | `MeterBinder.bindTo(registry)` | JVM、连接池、缓存、Kafka 客户端 | 必须实现 `AutoCloseable`，否则热部署泄漏 |
| 3 | 注解切面 | `TimedAspect` / `CountedAspect` / `MeterTagAnnotationHandler` | 方法级埋点 | 需手写 bean；`class` 标签用全限定名，基数高 |
| 4 | 时间源 | `Clock`（`Clock.SYSTEM` / `MockClock`） | 单测精确控制窗口轮换 | 换 clock 后 `TimeWindowMax` 行为随之改变 |
| 5 | 停顿检测 | `PauseDetector`（默认 `NoPauseDetector`） | 修正 STW 导致的 P99 偏乐观 | 需 LatencyUtils，有额外开销 |

### 5.2 注册表层（Registry）

| # | 扩展点 | 接口 / 类 | 典型用途 | 代价 / 坑 |
|---|---|---|---|---|
| 6 | 注册闸门 | `MeterFilter.accept/deny` | 屏蔽噪声指标、按前缀放行 | deny 后拿到 noop meter，读数永远 0 |
| 7 | 改名改标签 | `MeterFilter.map` | 统一命名规范、剔除高基数 tag | 改完必须重查池子（见 2.3） |
| 8 | 公共标签 | `MeterFilter.commonTags` | 打 `application`、`region` | 与 OTLP 的 Resource 语义冲突，别把 `service.name` 当 tag |
| 9 | 标签治理 | `MeterFilter.maximumAllowableTags` / `ignoreTags` / `replaceTagValues` | 给某个指标设标签数上限 | 超限的 meter 会被整体拒绝，**静默丢失** |
| 10 | 分布配置 | `MeterFilter.configure(DistributionStatisticConfig)` | 给某类指标统一开直方图/SLO | 只影响此后创建的 meter |
| 11 | 命名约定 | `NamingConvention` | 适配后端命名（`.→_`、`.→/`） | `name()` 里拿不到 tags，分层命名后端信息不足 |
| 12 | 注册表钩子 | `MeterRegistry.Config.onMeterAdded/onMeterRemoved` | 预登记 Collector、维护外部索引 | 回调里再注册 meter 会递归 |
| 13 | 新后端 | `extends MeterRegistry` 或 `StepMeterRegistry` / `PushMeterRegistry` | 接自研 TSDB | 必须想清楚 cumulative 还是 step |
| 14 | 多后端 | `CompositeMeterRegistry` | 同时推 Prometheus + Datadog | 子 registry 的 filter 各自生效，容易两套数据 |
| 15 | Boot 定制 | `MeterRegistryCustomizer<MeterRegistry>` | 在自动配置之后补一刀 | 执行时机是"registry 建成之后、binder 绑定之前"，顺序靠 `@Order` |

### 5.3 统计层

| # | 扩展点 | 接口 / 类 | 典型用途 | 代价 / 坑 |
|---|---|---|---|---|
| 16 | 分位数/直方图/SLO | `DistributionStatisticConfig` | P99、SLO 达标率 | 默认 `max=∞` 会生成上百个桶 |
| 17 | 自定义直方图 | `Histogram` / `HistogramSupport` | 接 HdrHistogram 之外的实现 | 必须实现 `takeSnapshot()` 的累计语义 |
| 18 | 预置分位值 | `ValueAtPercentile` | 从外部系统读现成的 P99 | 只在 percentile 路线生效 |

### 5.4 投递层

| # | 扩展点 | 接口 / 类 | 典型用途 | 代价 / 坑 |
|---|---|---|---|---|
| 19 | 步进/推送 | `StepMeterRegistry` / `PushMeterRegistry` / `CountingMode` | 对接 push 型后端 | 步长与后端抓取间隔不对齐会出锯齿 |
| 20 | Prometheus 细节 | `PrometheusConfig`、`CollectorRegistry`、exemplars | 自定义 exemplar、复用已有 Collector | exemplar 需要 Observation 链路打通 |

### 5.5 观测层

| # | 扩展点 | 接口 / 类 | 典型用途 | 代价 / 坑 |
|---|---|---|---|---|
| 21 | 加信号 | `ObservationHandler` | 新增一种消费方（日志、审计、事件） | 顺序敏感；异常必须自己吞掉 |
| 22 | 定名字/标签 | `ObservationConvention` / `GlobalObservationConvention` | 统一 span 名与 tag 词表 | 全局 Convention 会被具体 Convention 覆盖 |
| 23 | 改结果 | `ObservationFilter` | 脱敏、补字段 | 只在 stop 阶段生效 |
| 24 | 关观测 | `ObservationPredicate` | 屏蔽健康检查、内部调用 | 关掉后 span 和 metric 一起没了 |
| 25 | 自定义上下文 | `Observation.Context` 子类 + `ObservationDocumentation` | 携带业务对象、生成可读文档 | 文档类只服务可读性，不参与运行时 |
| 26 | 跨线程 | `ContextPropagator` / `ThreadLocalAccessor` | 线程池、`@Async`、Reactor | 注册顺序决定还原顺序 |

### 5.6 追踪层

| # | 扩展点 | 接口 / 类 | 典型用途 | 代价 / 坑 |
|---|---|---|---|---|
| 27 | 换 tracer 实现 | `Tracer` / `Span` 桥（Brave / OTel） | 换底层 SDK | 与 agent 不能共存 |
| 28 | 换传播格式 | `Propagator` / `Propagation` | 自定义 header、多格式并联 | 格式不一致 = 静默断链 |
| 29 | 上下文搬运 | `CurrentTraceContext` / `ScopeDecorator` | 把 traceId 写进 MDC | 顺序与 `Baggage` 桥相关 |
| 30 | 导出与采样 | Brave `SpanHandler`/`Sampler`；OTel `SpanProcessor`/`SpanExporter`/`Sampler` | 过滤、采样、二次加工 | 采样率在入口定，下游无法改判 |

### 5.7 三个"看起来是扩展点其实不是"的坑

1. **`MeterRegistry.addListener`** 不是给你加数据的，它只监听 meter 增删事件。
2. **`Timer.record` 的负数**默认被静默丢弃（不是"记 0"），只有部分版本会对负值打一条 WARN。排查"耗时统计偏小"时要先确认没有负值或溢出。
3. **`Observation` 的 name 不能事后改**。`contextualName` 可以，但 `Context.name` 在 `onStart` 之后不可变——想在 stop 阶段改名叫什么"统一命名"是做不到的，只能在 Convention 里提前定。

---

## 6. 业界扩展实现全景

### 6.1 指标后端：20+ 个 registry

按"谁主动"分三类，选型时先看这一类：

| 类型 | 实现 | 说明 |
|---|---|---|
| **Pull（被拉）** | `micrometer-registry-prometheus`（1.13 起基于 `prometheus-metrics` 1.x） | 事实标准。`/actuator/prometheus` 暴露文本格式，Prometheus 定期抓 |
| | `micrometer-registry-prometheus-simpleclient` | 旧版（`simpleclient` 4.x），存量项目常见 |
| | `micrometer-registry-jmx` | JMX MBean，给老监控系统或本地诊断用 |
| **Push（主动推）** | `micrometer-registry-otlp` | 推 OTLP 到 Collector，是"统一 OTel 体系"的入口 |
| | `micrometer-registry-datadog` | Datadog 原生 API |
| | `micrometer-registry-newrelic` | New Relic Metric API |
| | `micrometer-registry-dynatrace` | Dynatrace v2 API |
| | `micrometer-registry-elastic` | Elasticsearch Metricbeat 格式 |
| | `micrometer-registry-cloudwatch2` | AWS CloudWatch（`cloudwatch` 是 v1 旧版） |
| | `micrometer-registry-stackdriver` | GCP Cloud Monitoring |
| | `micrometer-registry-azure-monitor` | Azure Application Insights |
| | `micrometer-registry-influx` | InfluxDB 1.x 行协议 |
| | `micrometer-registry-signalfx` | Splunk Observability |
| | `micrometer-registry-appoptics` | SolarWinds AppOptics（1.13 起已移除，只在旧版本存在） |
| **推 + 老式协议** | `micrometer-registry-statsd` | UDP 行协议，喂 StatsD/Telegraf |
| | `micrometer-registry-graphite` | 纯文本 TCP，自带分层/维度两种命名 |
| | `micrometer-registry-atlas` | Netflix Atlas |
| | `micrometer-registry-kairos` | KairosDB |
| | `micrometer-registry-ganglia` | Ganglia（UDP/XDR） |
| | `micrometer-registry-humio` | Humio |
| **调试** | `micrometer-registry-logging` | 定期把读数打日志，**只能调试** |
| **占位** | `micrometer-registry-noop` | 零开销，用于关掉导出 |

**现实中的组合**：Prometheus（pull，集群内）+ OTLP（push，跨云/统一 Collector）双写；或只用 OTLP，让 Collector 分发到任何后端。**不建议超过两个**，否则同一指标三份数据、三套命名、三个成本中心。

### 6.2 追踪后端：两座桥 + 若干导出器

| 层 | 实现 | 说明 |
|---|---|---|
| 桥 | `micrometer-tracing-bridge-brave` | Brave → Zipkin 语义，`zipkin-reporter` 导出（HTTP / Kafka / ActiveMQ） |
| 桥 | `micrometer-tracing-bridge-otel` | OTel SDK → `SpanExporter`，主流是 OTLP（gRPC 4317 / HTTP 4318） |
| 报告器 | `micrometer-tracing-reporter-wavefront` | 直推 Wavefront |
| 后端 | Zipkin / Jaeger / Tempo / SkyWalking / Datadog APM / Honeycomb | 前三个最常见；Tempo 只吃 OTLP |

**Boot 的装配**：`management.tracing.enabled=true` + 一个 bridge 依赖 + `management.tracing.sampling.probability`。Boot 3.x 会自动注册 `ObservationRegistry`、`PropagatingSender/Receiver`、`Tracer` 全套——**你不需要手写任何 bean**，这也是 `@Observed` 比 `@Timed` 省事的原因。

### 6.3 Binder 清单：能"白捡"多少指标

`micrometer-core` 的 `binder` 包有 200+ 个文件，按被观测对象分组：

| 分组 | 代表 Binder | 产出 |
|---|---|---|
| JVM | `JvmMemoryMetrics`、`JvmGcMetrics`、`JvmThreadMetrics`、`ClassLoaderMetrics`、`JvmHeapPressureMetrics`、`JvmInfoMetrics`、`JvmCompilationMetrics` | 第 7 章整表 |
| 系统 | `ProcessorMetrics`、`UptimeMetrics`、`FileDescriptorMetrics`、`DiskSpaceMetrics` | CPU、运行时长、FD、磁盘 |
| Web 容器 | `TomcatMetrics`、`JettyServerThreadPoolMetrics`、`NettyAllocatorMetrics` | 线程池、会话、连接 |
| HTTP 客户端 | `OkHttpMetricsEventListener`、`ApacheHttpClientMetrics`（4/5）、`HttpComponents` | 请求耗时、连接池 |
| 连接池 | `HikariCpMetrics`（Hikari 自带）、`DataSourcePoolMetadataMetrics` | 活跃/空闲/等待 |
| 缓存 | `CaffeineCacheMetrics`、`GuavaCacheMetrics`、`EhCache2Metrics`、`HazelcastCacheMetrics` | 命中率、淘汰数 |
| 消息 | `KafkaClientMetrics`（consumer/producer/admin） | 吞吐、延迟、lag |
| 异步 | `ExecutorServiceMetrics` | 池大小、队列、完成数 |
| 日志 | `LogbackMetrics`、`Log4j2Metrics` | 各级别日志条数 |
| DB | `MongoMetrics`、`jpa`（Hibernate 统计） | 命令数、事务 |

**注意**：binder 白捡的指标都是**基础设施指标**——它们能回答"服务是不是慢了"，回答不了"支付成功率是多少"。后者必须手工埋点（`Counter` + 业务标签）。

### 6.4 框架集成矩阵（Spring 生态）

| 框架 | 集成方式 | 产出 |
|---|---|---|
| Spring MVC / WebFlux | `ServerHttpObservationFilter` | `http.server.requests`（Timer）+ server span |
| RestTemplate / RestClient / WebClient | `ClientHttpObservationFilter` 类 | `http.client.requests` + client span |
| OpenFeign | `FeignObservationConvention` | 同 `http.client.requests`，`clientName` = feign 服务名 |
| Spring Cloud Gateway | `GatewayMetricsFilter` | `spring.cloud.gateway.requests`（route/status/outcome） |
| Spring Kafka | `KafkaTemplate` / `@KafkaListener` 的 Observation | `spring.kafka.template` / `spring.kafka.listener` span |
| Spring Data JPA | `RepositoryMethodObservation`（Boot 3.1+） | `spring.data.repository.invocations`（Timer，repository/method/state） |
| Spring Security | `Authentication` / `Authorization` Observation | `spring.security.authentications` / `.authorizations` |
| Spring Batch / Integration | 各自 Observation | job/step、channel 级 span |
| Spring AI | `ChatClient` Observation + token 计量 | `gen_ai.client.operation`（Timer）、`gen_ai.client.token.usage` |
| Reactor | `reactor-core-micrometer` | 订阅/onNext 级 span（`contextCapture`） |
| gRPC（spring-grpc） | `GrpcObservation` | 服务端/客户端 span + `grpc.*` 指标 |
| Resilience4j | `micrometer` 模块 | 熔断状态、重试次数 |

### 6.5 三种典型组合

| 场景 | 组合 | 成本 | 代价 |
|---|---|---|---|
| **单体 / 小团队** | `actuator` + `micrometer-registry-prometheus` | 最低 | 只有 pull，跨集群聚合靠 Prometheus 联邦 |
| **微服务 + 已有 OTel** | `micrometer-registry-otlp` + `bridge-otel` + Collector | 中 | 必须严格只留一套埋点，否则 QPS 翻倍 |
| **混合（推荐）** | Prometheus 拉指标 + OTLP 推链路 | 中高 | 两套 pipeline 要分别运维 |

---

## 7. 指标清单：Micrometer 到底会积累哪些指标

### 7.1 来源分三层

| 层 | 谁产生 | 例子 | 可控性 |
|---|---|---|---|
| **Binder 层** | `MeterBinder` 主动注册 | `jvm.*`、`system.*`、`hikaricp.*` | 高（可开关、可加 tag） |
| **框架层** | 框架的 Observation / Filter | `http.server.requests`、`spring.*` | 中（靠配置改 tag） |
| **业务层** | 你写的 `Counter`/`Timer` | `order.created`、`payment.failed` | 完全可控 |

下面按族列出**实际会出现在 Prometheus 面板上的名字**（Prometheus 命名 = Micrometer 名 + 单位后缀 + `_count/_sum/_max`）。

### 7.2 JVM 族

| 指标（Micrometer 名） | 类型 | 单位 | 标签 | 含义 | 怎么读 |
|---|---|---|---|---|---|
| `jvm.memory.used` | Gauge | bytes | `area`(heap/nonheap)、`id`(pool) | 当前已用内存 | 看趋势，看是否贴近 `max` |
| `jvm.memory.committed` | Gauge | bytes | 同上 | 已向 OS 申请的内存量 | 与 used 的差 = 已申请未用 |
| `jvm.memory.max` | Gauge | bytes | 同上 | 可用上限 | 老年代 max 不变而 used 持续涨 = 泄漏信号 |
| `jvm.memory.used.after.gc` | Gauge | bytes | `area`、`id` | 上次 GC 后的存活量 | **判断泄漏最准的一个**：持续抬升才是真泄漏 |
| `jvm.gc.pause` | Timer | seconds | `action`(end of minor/major GC)、`cause`、`gc` | 每次 STW 停顿 | `_count`=GC 次数，`_sum`=总停顿，`_max`=最长一次 |
| `jvm.gc.concurrent.phase.time` | Timer | seconds | 同上 | 并发阶段耗时（不 STW） | 高但 `jvm.gc.pause` 低，说明是并发 GC 在忙 |
| `jvm.gc.memory.promoted` | Counter | bytes | — | 晋升到老年代的总字节 | 速率高 = 对象存活久，先查缓存/大对象 |
| `jvm.gc.memory.allocated` | Counter | bytes | — | 两次 GC 之间新分配的总字节 | 反映分配速率（不是堆大小） |
| `jvm.gc.max.data.size` | Gauge | bytes | — | 老年代最大容量 | 分母 |
| `jvm.gc.live.data.size` | Gauge | bytes | — | Full GC 后老年代存活 | 与上面比值 > 70% 考虑扩堆或查泄漏 |
| `jvm.gc.overhead` | Gauge | % | — | 最近窗口内 GC 时间占比 | > 10% 说明已经在"用 CPU 换内存" |
| `jvm.threads.live` | Gauge | threads | — | 当前线程数 | 阶梯式上涨 = 线程泄漏 |
| `jvm.threads.daemon` | Gauge | threads | — | 守护线程数 | 与 live 差值 = 业务线程 |
| `jvm.threads.peak` | Gauge | threads | — | 历史峰值 | 只增不减，用于容量规划 |
| `jvm.threads.started` | Counter | threads | — | 累计创建线程数 | 增长快而 live 不涨 = 线程频繁创建销毁 |
| `jvm.threads.states` | Gauge | threads | `state`(runnable/blocked/waiting/timed-waiting/new/terminated) | 各状态线程数 | `blocked` 高 = 锁竞争 |
| `jvm.classes.loaded` | Gauge | classes | — | 已加载类数 | 持续上涨 = 动态代理/热部署泄漏 |
| `jvm.classes.unloaded` | Counter | classes | — | 累计卸载类数 | 长期为 0 而 loaded 涨 = 类加载器泄漏 |
| `jvm.buffer.count` | Gauge | buffers | `id`(direct/mapped) | 直接内存缓冲块数 | 与 used 一起看 |
| `jvm.buffer.memory.used` | Gauge | bytes | `id` | 直接内存已用 | **堆外 OOM 的唯一线索** |
| `jvm.buffer.total.capacity` | Gauge | bytes | `id` | 直接内存总容量 | 上限参考 |
| `jvm.info` | Gauge | — | `runtime`、`vendor`、`version` | 恒为 1 | 用于 join 出"这个实例跑的哪个 JVM 版本" |
| `jvm.compilation.time` | FunctionTimer | seconds | — | 累计 JIT 编译时间 | 稳态后应停止增长 |

### 7.3 系统与进程族

| 指标 | 类型 | 单位 | 含义 |
|---|---|---|---|
| `system.cpu.count` | Gauge | — | 可用 CPU 核数 |
| `system.cpu.usage` | Gauge | 0~1 | 整机 CPU 使用率 |
| `process.cpu.usage` | Gauge | 0~1 | 本进程 CPU 使用率（**告警用这个**） |
| `system.load.average.1m` | Gauge | — | 1 分钟平均负载（Linux） |
| `process.uptime` | TimeGauge | seconds | 进程运行时长（重启检测） |
| `process.start.time` | TimeGauge | epoch s | 启动时刻（可算"实例年龄"） |
| `process.files.open` | Gauge | files | 打开的文件描述符数 |
| `process.files.max` | Gauge | files | FD 上限 |

### 7.4 HTTP 服务端：`http.server.requests`

这是**每个 Spring Web 应用都会有的一条 Timer**，也是被问得最多的。

| 项 | 值 |
|---|---|
| 类型 | Timer（→ Prometheus 的 `_count`/`_sum`/`_max` + 可选 `_bucket`） |
| 标签 | `method`、`uri`（模板化路径，如 `/orders/{id}`）、`status`（200）、`outcome`（SUCCESS/CLIENT_ERROR/SERVER_ERROR/REDIRECTION/INFORMATIONAL/UNKNOWN）、`exception`（简单类名或 `None`） |
| `_count` | 该 URI+状态 的请求数 |
| `_sum` | 累计耗时（秒） |
| `_max` | 窗口内最大耗时 |
| 派生 | `rate(_count)` = QPS；`_sum/_count` = 平均耗时；`histogram_quantile` = P99 |

`outcome` 的取值来自 `HttpStatus` 的段位，是**给告警用的粗粒度维度**（4xx 涨不等于服务坏，5xx 涨才是）。

### 7.5 HTTP 客户端：`http.client.requests`

| 项 | 值 |
|---|---|
| 标签 | `clientName`（RestTemplate/RestClient 的 bean 名或 Feign 服务名）、`method`、`uri`、`status`、`outcome`、`exception` |
| 与 server 的关系 | 一次"我调你"在两边各记一条，`clientName` ↔ `uri` 可以对上 |
| 常见坑 | 没有 `clientName` 时所有客户端混成一条，无法定位是哪个下游慢 |

### 7.6 连接池与数据库

| 指标 | 类型 | 含义 |
|---|---|---|
| `hikaricp.connections` | Gauge | 连接总数（tag `pool`） |
| `hikaricp.connections.active` | Gauge | 正在被业务持有的连接 |
| `hikaricp.connections.idle` | Gauge | 空闲连接 |
| `hikaricp.connections.pending` | Gauge | **等连接的线程数**（> 0 就是池不够） |
| `hikaricp.connections.min` / `.max` | Gauge | 池配置边界 |
| `hikaricp.connections.timeout` | Counter | 获取连接超时次数（**最该告警的**） |
| `hikaricp.connections.acquire` | Timer | 获取连接的耗时（P99 高 = 池压力大） |
| `hikaricp.connections.creation` | Timer | 新建物理连接的耗时 |
| `hikaricp.connections.usage` | Timer | 连接被持有的时长 |
| `jdbc.connections.active` / `.idle` / `.max` / `.min` | Gauge | 通用 `DataSourcePoolMetadata` 版本（非 Hikari） |

### 7.7 缓存

| 指标 | 类型 | 标签 | 含义 |
|---|---|---|---|
| `cache.gets` | Counter | `cache`、`cacheManager`、`result`(hit/miss) | 命中/未命中次数；`hit/(hit+miss)` = 命中率 |
| `cache.puts` | Counter | 同上 | 写入次数 |
| `cache.evictions` | Counter | 同上 | 淘汰次数（持续 > 0 = 容量不足） |
| `cache.size` | Gauge | 同上 | 当前条目数（部分实现不提供） |

### 7.8 消息（Kafka，示例）

Spring Kafka 的 `KafkaClientMetrics` 把 Kafka 原生指标名点分层后加前缀，名字**随 Kafka 版本变化**，但规则固定（`.total` / `.rate` / `.avg` / `.max` 后缀来自 Kafka 的 `MetricName`）：

| 指标（典型） | 类型 | 含义 |
|---|---|---|
| `kafka.consumer.fetch.manager.records.consumed.total` | Counter | 累计消费条数 |
| `kafka.consumer.fetch.manager.records.lag.max` | Gauge | **消费积压（最该告警）** |
| `kafka.consumer.fetch.manager.fetch.latency.avg` | Gauge | 拉取延迟 |
| `kafka.consumer.commit.latency.avg` | Gauge | 提交延迟 |
| `kafka.producer.record.send.rate` | Gauge | 发送速率 |
| `kafka.producer.record.error.rate` | Gauge | 发送错误率 |

### 7.9 执行器与日志

| 指标 | 类型 | 标签 | 含义 |
|---|---|---|---|
| `executor.pool.size` | Gauge | `name` | 线程池当前线程数 |
| `executor.active` | Gauge | `name` | 正在执行任务的线程数 |
| `executor.queued` | Gauge | `name` | 队列中等待的任务数（**关键**） |
| `executor.completed` | FunctionCounter | `name` | 累计完成任务数 |
| `executor.queue.remaining` | Gauge | `name` | 队列剩余容量 |
| `logback.events` | Counter | `level` | 各日志级别的条数（ERROR 突增是最好用的信号） |
| `log4j2.events` | Counter | `level` | 同上（Log4j2 版） |

### 7.10 一张"哪些会自己出现"的判定表

| 指标族 | 默认就有？ | 触发条件 |
|---|---|---|
| `jvm.*`、`system.*`、`process.*` | 是 | 有 `actuator` + 一个 registry |
| `http.server.requests` | 是 | Spring MVC/WebFlux（Boot 3 起走 Observation） |
| `http.client.requests` | 是 | 用了 RestTemplate/RestClient/WebClient/Feign |
| `hikaricp.*` | 是 | 有 HikariCP 且 `management.metrics.binders.jdbc.enabled`（默认开） |
| `tomcat.*` | **否** | 需 `server.tomcat.mbeanregistry.enabled=true` |
| `executor.*` | **否** | 需手动 `new ExecutorServiceMetrics(...)` 或 Boot 的 `ExecutorServiceMetricsAutoConfiguration` 匹配到 `ThreadPoolTaskExecutor` |
| `logback.events` | 是 | `management.metrics.binders.logback.enabled=true`（默认） |
| `cache.*` | **否** | 需 `CacheMetricsRegistrar` 注册（Spring 自动做，但仅对 `CacheManager`） |
| `kafka.*` | **否** | 需 `KafkaClientMetrics` 手动 bind（或 spring-kafka 的 `KafkaMetrics` 支持） |
| `spring.data.repository.invocations` | **否** | 需 `management.metrics.data.repository.autotime.enabled=true` |
| `spring.cloud.gateway.requests` | 是 | 有 Gateway |
| `gen_ai.*` | 是 | 有 Spring AI `ChatClient`/`ChatModel` 调用 |

---

## 8. 单指标解剖：`@Timed` 到底积累了什么

这一章回答题面的具体问题：一个 `@Timed` 打在方法上，从一次调用到面板上的曲线，中间产生了几个值。

### 8.1 调用路径

```java
@Timed(value = "order.create", percentiles = {0.5, 0.95, 0.99}, histogram = true)
public Order create(CreateCmd cmd) { ... }
```

`TimedAspect` 的环绕逻辑（`processWithTimer`）：

```java
Timer.Sample sample = Timer.start(registry);          // ① 记下单调时钟的纳秒时刻
String exceptionClass = "none";
try {
    result = pjp.proceed();                            // ② 业务执行
    return result;
} catch (Throwable e) {
    exceptionClass = e.getClass().getSimpleName();     // ③ 异常类型进标签
    throw e;
} finally {
    sample.stop(recordBuilder(...)
        .register(registry));                          // ④ stop = record(durationNs)
}
```

`recordBuilder` 决定了这个 Timer 身上挂什么：

| builder 调用 | 来源 | 效果 |
|---|---|---|
| `Timer.builder(metricName)` | `@Timed.value()`，空则 `"method.timed"` | 指标名 |
| `.description(...)` | `@Timed.description()` | 元数据 |
| `.tags(timed.extraTags())` | 注解静态标签 | 固定维度 |
| `.tags("exception", exceptionClass)` | 异常简单类名 / `"none"` | 失败维度 |
| `.tags(class, method)` | `pjp.getStaticPart().getSignature()` | **`class` = 全限定类名，`method` = 方法名** |
| `.publishPercentileHistogram(timed.histogram())` | `histogram=true` | 开桶 |
| `.publishPercentiles(timed.percentiles())` | 数组 | 开 HdrHistogram |
| `.serviceLevelObjectives(...)` | 1.14+ | SLO 边界也进桶 |

### 8.2 内存里的五类数据

一次 `record` 之后，这个 Timer 内部维护了五类数据（前三类是"账本"，后两类是"分布"）：

| # | 数据 | 载体 | 是否在 `measure()` 里 |
|---|---|---|---|
| ① | 调用次数 | `LongAdder count` | ✅ COUNT |
| ② | 累计耗时（纳秒） | `LongAdder total` | ✅ TOTAL_TIME |
| ③ | 窗口内最大耗时 | `TimeWindowMax` | ✅ MAX |
| ④ | 直方图桶计数 | `TimeWindowFixedBoundaryHistogram`（或 `NoopHistogram`） | ❌ 在 `HistogramSnapshot.histogramCounts()` |
| ⑤ | 客户端分位值 | `TimeWindowPercentileHistogram`（HdrHistogram） | ❌ 在 `HistogramSnapshot.percentileValues()` |

**这张表解释了一个高频困惑**："我明明配了 P99，为什么 `timer.measure()` 里读不到？"——因为分位数不是 `Statistic`，它走的是快照路径。

### 8.3 `measure()` 产出的三个值（及其确切含义）

`Timer.measure()` 固定返回三项，单位**一律是 `baseTimeUnit`（默认 `SECONDS`）**：

| Statistic | 值 | Prometheus 序列 | 含义 | 是否单调 |
|---|---|---|---|---|
| `COUNT` | `count()` | `order_create_seconds_count` | 方法被调用的次数（**含抛异常的**） | ✅ 只增 |
| `TOTAL_TIME` | `totalTime(SECONDS)` | `order_create_seconds_sum` | 所有调用的耗时总和（秒） | ✅ 只增（cumulative） |
| `MAX` | `max(SECONDS)` | `order_create_seconds_max` | **最近一个时间窗**内单次最大耗时 | ❌ 会衰减 |

三个必须记住的语义细节：

1. **`_count` 包含失败调用**。`finally` 里无条件 record，所以异常也计数。要算"成功率"，得用 `exception="none"` 的 `_count` 除以总 `_count`。
2. **`_max` 是滑动窗口最大值，不是历史最大值**。默认 `expiry=2min / bufferLength=3`，意味着它反映最近约 2 分钟。要看"过去一小时最大"，得写 `max_over_time(order_create_seconds_max[1h])`。
3. **`total_time` 的单位由 registry 的 `baseTimeUnit` 决定，不是你 `record` 时传的单位。** 你写 `record(100, MILLISECONDS)`，Micrometer 统一转成纳秒存储；读出来时按 `baseTimeUnit` 折算，Prometheus 的 baseTimeUnit 是秒，于是 `_sum` 里是 0.1。若某天换了把 baseTimeUnit 设成毫秒的自定义 registry，数值会变成 100，但序列后缀仍由 `PrometheusNamingConvention` 按 `baseUnit` 补齐——这就是"数值量级突然差了 1000 倍、名字却看不出"的根因。

### 8.4 第 4、5 类：桶与分位值

**直方图（`histogram=true`）**产出的是**累计桶**：每个 `le` 的值是"耗时 ≤ 该上界的调用数"，所以桶值天然单调不减，末端 `le="+Inf"` 等于 `_count`。

**客户端分位数（`percentiles={...}`）**产出的是**在采集时刻由 HdrHistogram 算出的分位值**，Prometheus 里表现为同名序列 + `quantile` 标签：

```
order_create_seconds{quantile="0.5"}  0.011
order_create_seconds{quantile="0.95"} 0.048
order_create_seconds{quantile="0.99"} 0.312
```

两者可以共存，但**用途完全不同**：

| | 直方图 + `histogram_quantile` | 客户端分位数 + `quantile` |
|---|---|---|
| 精度 | 桶边界限制（可能在 0.25 和 0.5 之间插值） | 高（HdrHistogram，受 `percentilePrecision` 影响） |
| 跨实例聚合 | ✅ 桶可相加 | ❌ **各实例算各的，取平均/取最大都是错的** |
| 内存 | 固定（桶数 × 计数器） | 每实例一个 HdrHistogram，较重 |
| 推荐 | 服务端指标（多实例） | 单实例诊断、离线分析 |

### 8.5 Prometheus 上的完整形态（逐行解释）

假设 `order.create` 被调用 1000 次，总耗时 12.34 秒，最长一次 0.85 秒，`histogram=true`，全部成功：

```
# HELP order_create_seconds        （来自 @Timed.description）
# TYPE order_create_seconds histogram
order_create_seconds_bucket{class="com.acme.OrderService",exception="none",method="create",le="0.005"} 120
order_create_seconds_bucket{...,le="0.01"}  480
order_create_seconds_bucket{...,le="0.025"} 780
order_create_seconds_bucket{...,le="0.05"}  900
order_create_seconds_bucket{...,le="0.1"}   960
order_create_seconds_bucket{...,le="0.25"}  985
order_create_seconds_bucket{...,le="0.5"}   995
order_create_seconds_bucket{...,le="1.0"}   1000
order_create_seconds_bucket{...,le="+Inf"}  1000
order_create_seconds_count{...}             1000
order_create_seconds_sum{...}               12.34
order_create_seconds_max{...}               0.85
```

逐行读法：

| 行 | 读作 | 用途 |
|---|---|---|
| `_bucket{le="0.005"}` = 120 | 5ms 内完成的占 120 次（12%） | 快请求占比 |
| `_bucket{le="0.5"}` = 995 | 500ms 内完成 995 次 | 99.5% 在 500ms 内 |
| `_bucket{le="+Inf"}` = 1000 | 总次数（必须等于 `_count`，不等说明丢样本） | 自检 |
| `_count` = 1000 | 总调用次数 | QPS = `rate(_count[1m])` |
| `_sum` = 12.34 | 总耗时 12.34 s | 平均 = `_sum/_count` = **12.34 ms** |
| `_max` = 0.85 | 窗口内最慢一次 850ms | 抖动用 `max_over_time` |

**P99 怎么算**（`histogram_quantile(0.99, rate(order_create_seconds_bucket[5m]))`）：

- 目标排位 = `0.99 × 1000 = 990`
- `le="0.25"` 累计 985（不够），`le="0.5"` 累计 995（够）→ 落在 `(0.25, 0.5]` 区间
- 线性插值：`0.25 + (990-985)/(995-985) × (0.5-0.25) = 0.25 + 0.5×0.25 = 0.375 s`

即 **P99 ≈ 375ms**。注意这个值**只能精确到桶边界**——如果桶在 0.25/0.5 之间更密（比如设了 SLO=300ms），P99 会精确到 300ms 附近的桶。这就是"开直方图一定要显式设 min/max/SLO"的实际原因：**桶边界决定你能看到的精度**。

### 8.6 OTLP 上的形态

同一个 Timer，在 OTLP（step registry）里变成**一条 metric 的多个字段**，而不是多条序列：

```
metric.name = "order.create"
metric.unit = "s"
data.histogram:
  count        = 1000
  sum          = 12.34
  max          = 0.85
  bucketCounts = [120, 360, 300, 120, 60, 25, 10, 5, 0]   // 每档是"增量"不是累计
  explicitBounds = [0.005, 0.01, 0.025, ...]
attributes = { class, method, exception }
```

四个关键差异（这就是 2.4 说的语义泄漏）：

1. **`count`/`sum` 是"本步增量"**，不是累计。所以 `rate()` 在 OTLP 上是多余的，直接用值即可。
2. **桶是增量**（`bucketCounts[i]` 是本步落在该档的次数），Prometheus 是累计。
3. **`_max` 是字段**，不是一个叫 `_max` 的序列。
4. **属性（attribute）就是 Prometheus 的 label**，但 OTLP 支持嵌套/数组，语义更宽。

### 8.7 六个语义陷阱

| # | 陷阱 | 现象 | 正解 |
|---|---|---|---|
| 1 | `_max` 是窗口值 | 面板上"最大耗时"突然掉下来 | `max_over_time(...[1h])` 或改看 P99 |
| 2 | `_count` 含失败 | 成功率算出来偏高/偏低 | 用 `exception="none"` 过滤 |
| 3 | 累计 vs 步进 | 同一段 PromQL 换后端就错 | 按 2.4 的表分后端写查询 |
| 4 | `longTask=true` | 没有 `_count`/`_sum`，只有 `active`/`duration` | 长任务用 `LongTaskTimer`，它记"正在做"，不记"做完了" |
| 5 | 返回 `CompletionStage` | 耗时包含了"未完成"的部分 | Aspect 会 `whenComplete` 才 record；但若返回 null 则立即 record |
| 6 | 类级 + 方法级同时有 `@Timed` | 同一方法两套指标 | 类级切点带 `!@annotation` 已互斥；但类级 + `@Observed` 会双记 |

### 8.8 标签的成本：`class` 与 `exception`

`@Timed` 的两个默认标签是有代价的：

- **`class` 是全限定类名**（`getDeclaringTypeName()`），不是简单名。一个 20 个 Service 的系统，`class` 就有 20 个值；如果打在 Controller 上，还会随包结构调整而变。
- **`exception` 的基数是"异常类型数"**。一个方法可能抛 10 种异常，就是 10 条时间序列。**更危险的是动态异常消息**——如果有人把 message 拼进 tag（用 `@MeterTag` 或 `extraTags`），基数直接爆炸。

治理手段（都在 5.2 的 `MeterFilter` 里）：

```java
@Bean
MeterFilter timedTagGuard() {
    return MeterFilter.maximumAllowableTags("order.create", "exception", 5,
        MeterFilter.deny());   // 超过 5 种异常类型就拒绝新 meter（静默）
}
```

### 8.9 四种埋点方式的产出对照

| 方式 | 产出指标 | 产出 span | 需要什么 | 适合 |
|---|---|---|---|---|
| `@Timed` | `method.timed` 或自定义名（Timer） | ❌ | `TimedAspect` bean | 只想要耗时 |
| `@Counted` | `method.counted`（Counter，tag `result`） | ❌ | `CountedAspect` bean | 只想要次数 |
| `@Observed` | 同名 Timer（经 `DefaultMeterObservationHandler`） | ✅ | `ObservedAspect`（Boot 自动） | 新代码首选 |
| 框架自动（`http.server.requests`） | 有 | ✅ | 用 Spring MVC | 入口/出口 |

**注意 `@Observed` 产的指标名与 `@Timed` 不同**：`@Observed(name="order.create")` 产 `order.create` 这个 Observation，`DefaultMeterObservationHandler` 会把它变成同名 Timer（并追加 `error` 等低基数键值）。而 `@Timed("order.create")` 直接产同名 Timer。**两者同时打在一个方法上 = 双份数据**，这是"同一接口 QPS 翻倍"的常见原因之一。

---

## 9. 抽象设计的取舍

### 9.1 为什么 `Tag` 和 `KeyValue` 是两个接口

两个原因叠在一起（语义 + 依赖）：

- **语义上**：`Tag` 服务于指标（可比较、参与 `Meter.Id` 的 hash），`KeyValue` 服务于观测（带 `KeyName`、可懒求值、可缓存）。让 `Tag` 继承 `KeyValue`，等于让指标语义背上一份观测语义。
- **依赖上**：`KeyValue` 在 `micrometer-commons`，`Tag` 在 `micrometer-core`。如果 `KeyValue extends Tag`，commons 就得依赖 core；而 core 依赖 observation、observation 又依赖 commons——成环。

所以宁可写两次 `key/value`，也不破坏分层。代价是两者之间需要手工转换（`DefaultMeterObservationHandler.createTags`）。

### 9.2 为什么 `NamingConvention` 推迟到后端

因为"命名"是后端方言，不是语义。Micrometer 只保证 `name + tags + baseUnit + type` 这四个语义要素完整，具体长什么样交给后端。代价是**你永远无法在埋点处知道最终序列名**——排查"面板上找不到指标"时，必须去对应 registry 的 `NamingConvention` 里查转换规则。

### 9.3 为什么 `Gauge` 没有 `set()` 

`Gauge` 只能"绑定一个对象 + 一个取值函数"（`Gauge.builder(name, obj, ToDoubleFunction)`），不能直接赋值。原因：**如果可以 set，就必须有人负责"什么时候清空"**。一个被 GC 的对象如果还挂在 Gauge 上，会阻止回收或读到脏值。所以 Micrometer 选择"弱引用 + 每次读时求值"——代价是**你必须自己持有被观测对象的强引用**，否则 Gauge 会读到 null（这是"Gauge 显示 0 或 NaN"的最常见原因）。

### 9.4 抽象没覆盖的三件事

| 缺口 | 表现 | 现状 |
|---|---|---|
| **基数治理** | 高基数 tag 打爆 Prometheus | 只有 `maximumAllowableTags` 这种"硬拒绝"，没有"自动降维" |
| **单位一致性** | 同一个语义在不同库里有 ms/s/ns 三种 | 只有 `baseUnit` 声明，没有强制校验 |
| **跨信号关联** | 指标 ↔ 链路要靠 exemplar | exemplar 需要 Observation + Tracing 两套 handler 对齐，配错就静默失效 |

---

## 10. 与其它文档的关系

| 文档 | 关注点 | 本文与它的分工 |
|---|---|---|
| `Micrometer.md` | core 源码逐层走读（注册管线、分布内核、22 个 registry） | 本文只讲"设计意图"，细节查它 |
| `Micrometer Tracing.md` | tracing 模块与 handler 源码 | 本文只讲"两个动作 + 扩展点" |
| `Micrometer&OpenTelemetry.md` | 与 OTel 的对照、桥的细节 | 本文的 3.6 / 6.2 是它的摘要 |
| `观测体系.md` | 现有架构的三条管道、断点清单 | 本文是"应该怎么设计"，它是"你现在是什么样" |
| `Spring观测体系设计.md` | Spring 侧的装配与选型 | 本文 6.4 的展开 |

---

## 附录 A：关键类速查

| 想干什么 | 找哪个类 |
|---|---|
| 自定义指标 | `MeterRegistry.counter/gauge/timer/summary` 或 `Xxx.builder()` |
| 改指标名/标签 | `MeterFilter`（`map` / `commonTags` / `maximumAllowableTags`） |
| 适配第三方状态 | `MeterBinder` |
| 方法级耗时 | `@Timed` + `TimedAspect`（或 `@Observed` + `ObservedAspect`） |
| 观测生命周期 | `Observation` / `ObservationRegistry` / `ObservationHandler` |
| 命名与标签约定 | `ObservationConvention` |
| 开 span | `Tracer.nextSpan()` / `spanBuilder()` / `startScopedSpan()` |
| 当前 span | `Tracer.currentSpan()` / `SpanCustomizer` |
| 跨进程序列化 | `Propagator.inject/extract`（`Propagation` 组装） |
| 跨线程搬运 | `ContextPropagator` / `ThreadLocalAccessor` / `CurrentTraceContext.wrap()` |
| 业务上下文 | `Baggage` / `BaggageManager` |
| 分布统计配置 | `DistributionStatisticConfig`（`MeterFilter.configure` 全局改） |
| 换/接后端 | `MeterRegistry` 子类；`micrometer-registry-*`；`micrometer-tracing-bridge-*` |

## 附录 B：十个高频问答

1. **`@Timed` 打了但面板没数据？** ① `TimedAspect` bean 没注册；② 没有 registry 依赖；③ `MeterFilter.deny` 把它屏蔽了；④ 名字被 `NamingConvention` 改了（去 `/actuator/metrics` 看原名）。
2. **为什么 `_max` 会变小？** 它是 `TimeWindowMax`（默认 2 分钟窗），不是历史最大值。
3. **P99 为什么和日志里的对不上？** 面板上的 P99 是桶插值（`histogram_quantile`），日志是真实值；桶不够密就有偏差。
4. **同一个接口 QPS 翻倍？** 两套埋点并存（`@Timed` + `@Observed`，或 Micrometer + agent 的 servlet instrumentation）。
5. **`jvm.memory.used` 一直涨就是泄漏吗？** 不是。看 `jvm.memory.used.after.gc`，它才反映存活集。
6. **`executor.*` 为什么不出现？** 需要显式 bind；Boot 只对特定 `Executor` bean 自动绑。
7. **`tomcat.*` 为什么没有？** 需 `server.tomcat.mbeanregistry.enabled=true`。
8. **traceId 为什么没进指标（exemplar 空）？** `TracingObservationHandler` 与 `DefaultMeterObservationHandler` 顺序不对，或 Prometheus 侧未开 exemplar。
9. **`http.client.requests` 的 `clientName` 是什么？** RestTemplate/RestClient 的 bean 名或 Feign 的服务名；自定义客户端要自己填。
10. **Brave 和 OTel 桥能一起用吗？** 不能。两个桥各自维护上下文，会导致重复 span 与断链；同理不能与 Java agent 共存。
