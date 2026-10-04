# Micrometer 深度源码解析（写给初学者的架构全景）

> **本文基于的源码**：`D:\code\3rd\micrometer`，**main 分支快照**，Git commit `7175340`（2026-10-02，"Merge branch '1.17.x'" 合入 main 后的开发线，即 **1.18.0-SNAPSHOT**）。文中所有【源码证据】的文件路径与行号均为对该快照实际读取所得。
>
> **版本取舍说明**：Micrometer 核心计量模型自 1.0（2018-02 GA）以来高度稳定；1.10（2022-11）把 Observation API 一并 GA 进来，此后架构骨架再未发生伤筋动骨的变化（1.13 重写了 Prometheus 注册表、1.16 引入 JSpecify 空安全、1.18 把观测的 active 长任务计时器改为可选——均不影响主干）。因此本文内容对使用 Spring Boot 3.x（Micrometer 1.10~1.15）的读者同样适用，个别差异在文中以"版本注"标注；1.0 → 1.17 的特性演进对比见 1.6 节，所有"某特性属于哪个版本"的结论均经本地 git 标签（v1.0.0 ~ v1.17.0 共 18 个 GA tag）逐项实证。
>
> **阅读约定**：每章"先白话、后源码"——先用一两句人话讲清"这是什么、为什么需要它"，再给出源码证据链（`模块/src/main/java/.../类名.java` + 方法名 + 行号 + 代码片段）。行号只对该快照精确，读者用 IDEA 打开源码按类名 + 方法名定位即可。第八章"Spring Boot 如何接线"的证据取自 `D:\code\3rd\spring-boot`（4.2.0-SNAPSHOT）。

## 如何读这份文档

如果你是 Micrometer 初学者，推荐两遍读法：

- **第一遍（建立地图，1~2 小时）**：只读第一章（总览）每一节的开头白话段、各章的"小结"节、以及第九章（贯通视图）。目标是能回答：为什么埋点代码不用改就能换监控后端？`counter.increment()` 之后发生了什么？一次 HTTP 请求怎么同时产生指标和链路数据？
- **第二遍（深入源码）**：对照每一章的【源码证据】，打开源码逐行读。顺序建议：第二章（计量模型）→ 第三章（注册表）→ 第五章（发布机制）→ 第四章（分布统计，难但值得）→ 第七章（Observation）→ 第八章（Boot 接线，随用随查）→ 第六章（后端实现，挑你用的那个读）。

---

# 一、总览：Micrometer 的定位、设计哲学与整体架构

## 1.1 一句话定位

**Micrometer 是应用指标的"门面"（facade）：你在业务代码里用它的统一 API 埋点，数据存进一个与厂商无关的内存注册表，再由 20 多种注册表实现把数据投递给 Prometheus、Datadog、OTLP 等监控后端——换后端不改一行埋点代码。** 官方 README 自述（`README.md:9-10`）：

> An application metrics facade for the most popular monitoring tools. Instrument your code with dimensional metrics with a vendor-neutral interface and decide on the monitoring backend at the last minute.

社区常把它类比成"**监控界的 SLF4J**"——SLF4J 之于日志（Logback/Log4j2 随插随换），就是 Micrometer 之于指标（Prometheus/InfluxDB/Datadog 随插随换）。从 1.10 起（2022-11），Micrometer 又多了一层身份：**Observation API**——一套同时描述"指标 + 链路追踪 + 事件"的观测门面（第七章），Spring Boot 3.x 起的整个观测体系（`management.observations.*`）都压在它上面。

它解决的不是"怎么监控"这一个问题，而是**埋点与后端的解耦问题**：业务代码只认识 `Counter`/`Timer` 这些抽象，后端选择被推迟到部署那一刻（`implementation 'micrometer-registry-prometheus'` 还是 `micrometer-registry-otlp`）。

## 1.2 设计哲学：读源码前先记住四句话

1. **门面做到极致：用户只依赖最小抽象**。业务代码永远面对 `MeterRegistry` 和十来个仪表接口（`Counter`/`Timer`/`Gauge`/…）；`MeterRegistry` 本身是抽象类而非接口，但用户视角等价于一个"造表工厂"。后端特有的东西（命名风格、投递协议、直方图能力）全部沉到 `implementations/` 下的 22 个注册表模块里，core 对后端零感知。
2. **维度优先（dimensional first）**。仪表的身份是**名字 + 标签集**（`Meter.Id` = name + tags + type），而不是前缀路径名。同一个 `http.server.requests`，配上不同 `uri`/`method`/`status` 标签就是多条时间序列——这是它区别于 Dropwizard（分层命名、层级树）的立身之本；对坚持分层命名后端的兼容交给 `HierarchicalNameMapper` 这类适配器。
3. **计量与投递分离，内存统计内核才是复杂度所在**。core 里真正难读的不是接口，而是 `distribution`/`step`/`cumulative`/`push` 四个包：时间窗轮换（`TimeWindow*`）、HdrHistogram 百分位、步进差值（`StepValue`）、推送调度（`PushMeterRegistry`）。接口一天能读完，这四章才是功力所在（第三~五章）。
4. **一切皆管线，处处留口子**。指标登记走 `MeterFilter` 管线（deny/rename/commonTags/上限熔断），观测走 `ObservationHandler`/`ObservationPredicate`/`ObservationFilter`/`ObservationConvention` 四件套，第三方数据接入走 `MeterBinder` 适配器，后端命名走 `NamingConvention`。Micrometer 自己的每个能力都是这套口子的"首位用户"。

## 1.3 模块分层全景

Micrometer 是多模块 Gradle 工程，根目录实测模块如下。按"用户感知"分层：

```
┌─────────────────────────── 后端投递层 implementations/ ───────────────────────────┐
│  pull 型：micrometer-registry-prometheus（新版） / -prometheus-simpleclient（旧版）  │
│  push 型（Step 节奏）：datadog / influx / elastic / new-relic / cloudwatch2 /      │
│          dynatrace / azure-monitor / appoptics / signalfx / stackdriver / humio / │
│          kairos / opentsdb / ganglia                                               │
│  push 型（自定义节奏）：micrometer-registry-otlp                                    │
│  桥接 Dropwizard：graphite / jmx     其他：health（SLO 健康判定） / statsd / atlas  │
├──────────────────────────────── 计量核心层 ────────────────────────────────────────┤
│  micrometer-core（仪表接口 + MeterRegistry + 分布统计内核 + binder 适配器，579 个   │
│                    java 文件，是整个体系最大的模块）                                 │
├──────────────────────────────── 观测门面层 ────────────────────────────────────────┤
│  micrometer-observation（Observation API：指标/链路统一门面，56 个文件）             │
│  micrometer-commons（KeyValue/KeyValues、注解处理、内部日志，39 个文件）             │
├──────────────────────────────── 测试与支撑层 ──────────────────────────────────────┤
│  micrometer-test（MeterRegistryAssert/TimerAssert 等 AssertJ 断言 + 兼容性 TCK）    │
│  micrometer-observation-test（ObservationRegistryAssert + Observation TCK）         │
│  micrometer-bom（依赖清单）                                                          │
├──────────────────────────────── 版本专属小模块 ────────────────────────────────────┤
│  micrometer-java11（JDK11 HttpClient 埋点）   micrometer-java21（虚拟线程指标）      │
│  micrometer-jakarta9（Jakarta JMS/Mail 埋点） micrometer-jetty11 / -jetty12         │
├──────────────────────────────── 工程配套 ──────────────────────────────────────────┤
│  samples/（示例）  benchmarks/（JMH 基准）  docs/（Antora 文档源）  concurrency-tests/│
└────────────────────────────────────────────────────────────────────────────────────┘
```

一个容易迷路的地方：`SimpleMeterRegistry`、`LoggingMeterRegistry`、`MeterFilter`、全部 `binder` 都住在 **micrometer-core** 里，不在 implementations 下；implementations 目录里只有需要第三方依赖（HTTP 客户端、protobuf、Prometheus 客户端）的注册表。

## 1.4 模块依赖图（以各模块 build.gradle 的 api 依赖实证）

```
                 micrometer-commons（KeyValue / 注解处理 / 内部日志）
                    ▲                                    ▲
        api(project)│                                    │api(project)
   micrometer-observation ──optional── io.micrometer:context-propagation:1.2.1
                    ▲                    （线程上下文快照，独立仓库）
        api(project)│
                 micrometer-core ──implementation── HdrHistogram:2.2.2（客户端百分位）
                    ▲              ──compileOnly─── LatencyUtils（停顿检测，运行期可选）
                    │
     ┌──────────────┼─────────────────┬─────────────────────┐
 micrometer-test  implementations/*   micrometer-java11/java21/jakarta9/jetty1x
 micrometer-observation-test（另依赖 observation）
```

真实依赖声明（已逐个验证）：

| 模块 | 直接依赖 | 证据 |
|---|---|---|
| micrometer-core | api: micrometer-commons, micrometer-observation | `micrometer-core/build.gradle:93-94` |
| micrometer-observation | api: micrometer-commons；optionalApi: io.micrometer:context-propagation | `micrometer-observation/build.gradle:39,41`；版本 `gradle/libs.versions.toml:90` |
| micrometer-core（统计） | implementation: HdrHistogram 2.2.2；compileOnly: LatencyUtils | `micrometer-core/build.gradle:96-105`（OSGI `resolution:=dynamic` 表达可选性，`:79-80`） |

这张图本身是架构说明：**commons 是最底层的"词表"**（KeyValue/日志/注解工具，谁都能依赖），**observation 只依赖 commons**（可以脱离指标体系单独用），**core 同时依赖两者**并把 Observation 的 `MeterObservationHandler` 反向接进指标世界。context-propagation 和 Micrometer Tracing 是**独立仓库**（`micrometer-metrics/context-propagation`、`micrometer-metrics/tracing`），本仓库只以 optional 依赖引用前者。

## 1.5 关键问题 → Micrometer 方案映射（全文导览）

| 开发中的关键问题 | Micrometer 的方案 | 详见 |
|---|---|---|
| 业务代码绑死监控 SDK，换后端要改代码 | 门面：MeterRegistry 抽象 + 22 个 registry 实现，最后一步才选后端 | 第二章、第三章 |
| 指标越打越多拖垮后端 | MeterFilter 管线：deny/maximumAllowableTags 熔断、commonTags 统一打标 | 三.3.4 |
| 接口响应时间想看 P99，但不能为每个接口存全量样本 | 分布统计内核：HdrHistogram 百分位（近似）或 SLO 桶（精确计数），时间窗轮换控制内存 | 第四章 |
| 不同后端计数语义不同（Prometheus 要累计值，Graphite 要每分钟速率） | step/cumulative 双语义：`StepValue` 差值内核 + `CountingMode` 可配 | 五.5.1~5.3 |
| 推送型后端怎么调度、失败会不会拖垮业务线程 | PushMeterRegistry：单线程调度 + 信号量去重 + 随机错峰 + 异常兜底 | 五.5.4 |
| 指标只有低基数维度，链路是高基数维度，两套 API 割裂 | Observation API：一次观测同时喂 metrics/tracing/logs，高/低基数 KeyValues 分离 | 第七章 |
| 观测上下文怎么跨线程/跨响应式传播 | ObservationThreadLocalAccessor + context-propagation 库 | 七.7.8 |
| JVM/连接池/缓存等第三方数据怎么变成指标 | MeterBinder 适配器（208 个文件，jvm/http/grpc/cache…） | 二.2.6 |
| 方法级埋点懒得写样板代码 | @Timed/@Counted/@Observed + Aspect 切面（core/observation 各一套） | 二.2.7、七.7.7 |
|  Spring Boot 里这套东西怎么自动装配 | Boot 4 的 micrometer-metrics / micrometer-observation 模块 + 两个 PostProcessor | 第八章 |
| Prometheus 抓取与 OTLP 推送的区别在哪 | pull vs push 两种节奏的典型实现对比 | 第六章 |

## 1.6 版本演进：1.0 → 1.17 关键变化对比

写作时（2026 年 10 月）的版本格局：main 分支为 1.18.0-SNAPSHOT（1.17.x 已于 2026-06-08 GA），Spring Boot 4.2 开发线锁定 Micrometer **1.18.0-M2**（`D:\code\3rd\spring-boot\platform\spring-boot-dependencies\build.gradle:1689`）。本节所有"特性属于哪个版本"的结论都经过验证：**用本地仓库的 18 个 git tag 对模块树逐项 `git ls-tree` / `git grep` 实证**，GA 日期取自各 tag 的提交时间。

### 1.6.1 版本时间线（GA 日期 = 本地 tag 提交时间，逐个实测）

| 版本 | GA 日期 | 一句话主题 |
|---|---|---|
| 1.0.0 | 2018-02-20 | 首个 GA：计量门面 + 首批注册表（Spring Boot 2.0 的官方指标门面） |
| 1.1.0 | 2018-10-28 | NamingConvention 扩展、MultiGauge 等 |
| 1.2.0 | 2019-06-30 | 逐步丰富的 binder（grpc/jetty 系列等） |
| 1.3.0 | 2019-10-01 | PushMeterRegistry 体系成型期 |
| 1.4.0 | 2020-03-21 | binder 大扩充 |
| 1.5.0 | 2020-04-29 | LoggingMeterRegistry 等周边完善 |
| 1.6.0 | 2020-10-29 | **micrometer-registry-health**（SLO 健康判定注册表，`@Incubating`） |
| 1.7.0 | 2021-05-12 | OpenTelemetry 生态前夜 |
| 1.8.0 | 2021-11-10 | — |
| 1.9.0 | 2022-05-11 | **micrometer-registry-otlp** 首次发布 |
| 1.10.0 | 2022-11-07 | **Observation API GA：micrometer-observation + micrometer-commons 模块诞生**（Spring Boot 3.0 同期引入） |
| 1.11.0 | 2023-05-05 | Observation 生态扩充（AnnotationHandler、@MeterTag 1.11 起） |
| 1.12.0 | 2023-11-13 | **Meter.MeterProvider**（延迟注册/动态打标，`@since 1.12.0`） |
| 1.13.0 | 2024-05-14 | **Prometheus 注册表重写**：迁移到 prometheus/metrics 新客户端，旧实现拆到 micrometer-registry-prometheus-simpleclient 并 @Deprecated |
| 1.14.0 | 2024-11-11 | **micrometer-java21**（虚拟线程指标，基于 JFR 事件） |
| 1.15.0 | 2025-05-12 | — |
| 1.16.0 | 2025-11-06 | **JSpecify 空安全**（core+observation 226 个文件引入 org.jspecify）、@ObservationKeyValue |
| 1.17.0 | 2026-06-08 | 当前稳定线（本文快照的合并来源） |
| 1.18.0（开发中） | 预计 2026-11 | 本快照：DefaultMeterObservationHandler 的 active 长任务计时器改为显式开启 |

### 1.6.2 特性引入版本对照表（git 实证，可复现）

每行都可用 `git ls-tree -r --name-only <tag> | grep -c <模块>/` 复现（数字 = 该 tag 下模块内 java 文件数）：

| 特性 | 引入版本 | 实证（旧 tag → 新 tag 的文件数变化） | 详见 |
|---|---|---|---|
| micrometer-registry-health | 1.6.0 | v1.5.0=0 → v1.6.0=18 | 六.6.4 |
| micrometer-registry-otlp | 1.9.0 | v1.8.0=0 → v1.9.0=16 | 六.6.2 |
| **micrometer-observation + micrometer-commons** | **1.10.0** | v1.9.0 两者均=0 → v1.10.0=49/31 | 第七章 |
| prometheus-simpleclient 拆分（旧版降级） | 1.13.0 | v1.12.0=0 → v1.13.0=20 | 六.6.1 |
| micrometer-java21（虚拟线程） | 1.14.0 | v1.13.0=0 → v1.14.0=5 | — |
| JSpecify 空安全（`org.jspecify`） | 1.16.0 | v1.15.0 core+observation=0 → v1.16.0=226 | — |
| Meter.MeterProvider | 1.12.0 | `Meter.java:488-496` javadoc `@since 1.12.0` | 二.2.2 |
| @Timed.serviceLevelObjectives | 1.14.0 | `Timed.java:85` | 二.2.7 |
| DefaultMeterObservationHandler 的 active LTT 默认关闭 | 1.18.0（本快照） | `DefaultMeterObservationHandler.java:70-73`（Builder `@since 1.18.0`） | 七.7.6 |

**JDK 基线**（一个反直觉的事实）：直到 1.17.0，micrometer-core 的编译基线**仍是 Java 8**——`v1.17.0 build.gradle:4`：`ext.javaTargetVersion = JavaVersion.VERSION_1_8`；`:200`：`// ensure Java 8 baseline is enforced for main source` + `options.release = 8`（main 分支同款在 `build.gradle:207-208`）。需要新 JDK 能力的内容放进独立模块：java11（`micrometer-java11/build.gradle:17` `VERSION_11`）、java21（`micrometer-java21/build.gradle:20` `sourceCompatibility = 21`）。测试代码统一按 Java 11 编译（`build.gradle` 的 `compileTestJava` 块）。

### 1.6.3 与 Spring Boot 的版本对应

| Spring Boot | Micrometer | 说明 |
|---|---|---|
| 2.0（2018-03） | 1.0 | Boot 2.0 起以 Micrometer 为官方指标门面（据官方发布说明） |
| 2.3 ~ 2.7 | 1.5 ~ 1.9 | 每代 Boot 随行升级 Micrometer 小版本（对应关系据 Boot 依赖管理） |
| 3.0（2022-11） | 1.10 | **Observation 集成起点**：`management.observations.*` 配置面世 |
| 4.2（开发线） | **1.18.0-M2** | 本地实证：`spring-boot-dependencies/build.gradle:1689` `library("Micrometer", "1.18.0-M2")` |

### 1.6.4 对初学者的意义：哪些知识过时了，哪些永远有效

- **永远有效**：Meter/Tag/MeterRegistry 三件套（1.0 未变）、MeterFilter（1.0 未变）、step/cumulative 语义、时间窗直方图、Observation 生命周期（1.10 定型）。
- **已过时/需警惕**：① `micrometer-registry-prometheus` 在 1.13 前后是两个完全不同的实现——旧教程里的 `CollectorRegistry`/`io.prometheus.client` 属于现在的 `micrometer-registry-prometheus-simpleclient`（@Deprecated）；② `ObservationRegistry.ObservationConfig` 如今是内部类（v1.12.0 起即如此，`:95` `class ObservationConfig`），早期"接口 + builder 链"的老资料要修正；③ Spring Boot 3.x 教程里的 `org.springframework.boot.actuate.autoconfigure.metrics.*` 包名在 Boot 4 已迁移到 `org.springframework.boot.micrometer.metrics.*`（见第八章）；④ `reset()`/`makeCurrent()` 等 Scope 方法已 @Deprecated。

## 1.7 全文章节地图

```
一、总览（本章）………… 定位 / 哲学 / 模块 / 依赖 / 版本
二、计量模型 …………………… Meter 家族、Meter.Id 与 Tag、NamingConvention、MeterBinder、@Timed/@Counted
三、注册表 ……………………… MeterRegistry 注册管线、MeterFilter、Config、Composite、Metrics 全局门面
四、分布统计内核 …………… DistributionStatisticConfig、record 全路径、TimeWindow 三兄弟、HdrHistogram
五、发布机制 …………………… step 差值内核、StepMeterRegistry 双调度、PushMeterRegistry、实现族总览
六、典型后端 …………………… Prometheus（pull）、OTLP（push）、Dropwizard 桥接、health/其余速览
七、Observation API ………… 生命周期、Context 高低基数、Handler/Predicate/Filter/Convention、@Observed、上下文传播
八、Spring Boot 接线 ……… Boot 4.2 的 metrics/observation 模块、两个 PostProcessor、切面开关、MetricsEndpoint
九、贯通视图 …………………… 三条时间线：一个计数的一生 / 一次 HTTP 请求的观测 / 一次抓取与推送
十、附录 ………………………… 速查表 / 学习路线 / 入口文件清单
```

---

# 二、计量模型：一次埋点，处处消费（micrometer-core 的 instrument 包）

## 2.1 模块定位与包结构

**先说白话**：这一章是"名词表"——你埋点时摸到的所有类型都在 `io.micrometer.core.instrument` 包下。设计上它刻意保持"薄"：接口只声明"我能记什么"，至于记到哪、怎么算 P99、怎么发出去，后面三章分别交给注册表、统计内核和投递层。

`micrometer-core/src/main/java/io/micrometer/core/` 下的关键子包：

| 子包 | 职责 | 详见 |
|---|---|---|
| `instrument` | 仪表接口族（Meter/Counter/Timer/…）、MeterRegistry、Tag | 本章 |
| `instrument.config` | MeterFilter / NamingConvention / MeterRegistryConfig | 本章、三.3.4 |
| `instrument.simple` | SimpleMeterRegistry / SimpleConfig / CountingMode | 五.5.1 |
| `instrument.cumulative` / `instrument.step` | 累计型 / 步进型仪表实现 | 第四章、第五章 |
| `instrument.distribution` | 分布统计内核（TimeWindow*/HistogramConfig） | 第四章 |
| `instrument.push` / `instrument.internal` | PushMeterRegistry / 命名映射等 | 第五章 |
| `instrument.composite` / `instrument.noop` / `instrument.logging` | 组合注册表 / 无开销占位 / 日志注册表 | 三.3.6、三.3.7 |
| `instrument.binder` | 208 个文件的第三方适配器集合 | 二.2.6 |
| `instrument.observation` | Observation → 指标的桥（DefaultMeterObservationHandler） | 七.7.6 |
| `annotation` / `aop` | @Timed/@Counted 注解与切面 | 二.2.7 |

## 2.2 一切仪表的源头：Meter 与 Measurement

**先说白话**：`Meter` 只回答两个问题——"你是谁"（`getId`）和"你此刻能读出什么数"（`measure`）。所有花哨的仪表都是这两个方法的展开。

【源码证据】`micrometer-core/src/main/java/io/micrometer/core/instrument/Meter.java`（接口声明 L39）：

```java
public interface Meter {

    Id getId();                                          // L48：仪表身份（名字+标签+类型）

    Iterable<Measurement> measure();                     // L56：读数 = 一组（值函数, 统计类型）

    enum Type {                                          // L63-67：六种仪表大类
        COUNTER, GAUGE, LONG_TASK_TIMER, TIMER, DISTRIBUTION_SUMMARY, OTHER;
    }
    ...
    default void close() {                               // L521：注册表关闭时被回调
    }
}
```

`Measurement` 不是接口而是"值函数 + 统计类型"的二元组（`Measurement.java:28-53`）：

```java
public class Measurement {                                   // L28

    private final DoubleSupplier f;                          // L30：读数函数（懒求值）

    private final Statistic statistic;                       // L32：COUNT/TOTAL_TIME/MAX/VALUE/...
```

配套的 `Statistic` 枚举（`Statistic.java:23`）是后端序列命名的依据：`TOTAL_TIME("total")` L34、`COUNT("count")` L39、`MAX("max")` L45、`VALUE("value")` L50、`ACTIVE_TASKS("active")` L60、`DURATION("duration")` L66。比如 Prometheus 里 `http_server_requests_seconds_count` 的 `_count` 后缀就来自这里。

另外两个"按类型分派"的糖方法值得一读：`Meter.match(...)`（L92）与 `Meter.use(...)`（L147）按仪表类型把访问者分发到不同 lambda——第六章 Datadog 的 `publish()` 就是靠它逐类型写出 JSON 的。

### Meter.MeterProvider：1.12 的"延迟注册"口子

动态打标的场景（每个 URL 一个 meter）如果每次都 `Timer.builder(...).tags(...).register(...)`，builder 会重建。1.12 引入了 `MeterProvider`：builder 先 `withRegistry(registry)` 换成一个"按需 withTags 的工厂"。

【源码证据】`Meter.java:488-519`（嵌套接口）：

```java
interface MeterProvider<T extends Meter> {                   // L488，@since 1.12.0

    T withTags(Iterable<? extends Tag> tags);                // L496：新建或复用同 Id 的 meter
```

```java
// Counter.java:131-133
public MeterProvider<Counter> withRegistry(MeterRegistry registry) {
    return extraTags -> register(registry, tags.and(extraTags));
}
```

## 2.3 八种仪表：选型即设计

**先说白话**：埋点选型只有两个问题——"这数据是累加的还是瞬时的？"（Counter vs Gauge）和"要不要分布信息？"（Timer/Summary 的直方图开关）。

| 仪表 | 回答的问题 | 关键方法（行号） | 埋点示例 |
|---|---|---|---|
| Counter | 事件只增不减 | `increment(double)` L46、`count()` L51 | 请求次数、异常次数 |
| Gauge | 瞬时值 | `value()` L68 | 队列长度、内存占用 |
| Timer | 耗时 + 次数 | `record(long,TimeUnit)` L118、`record(Runnable)` L197、`count()` L232、`totalTime(TimeUnit)` L238、`max(TimeUnit)` L253、`baseTimeUnit()` L302 | 接口耗时 |
| DistributionSummary | 事件大小（非时间） | `record(double)` L46、`count()` L52、`totalAmount()` L57、`max()` L70 | 报文字节数 |
| LongTaskTimer | 正在进行的长时间任务 | `start()` L193、`duration(TimeUnit)` L199、`activeTasks()` L204、`stop(long)` L242 | 进行中的批处理 |
| FunctionCounter | 从对象方法读单调值 | `count()` L66、builder(obj, func) L41 | 缓存命中数 |
| FunctionTimer | 从对象方法读耗时 | `count()` L41、`totalTime(TimeUnit)` L48 | 第三方统计对象 |
| TimeGauge | 带单位的 Gauge | `value(TimeUnit)` L68、`baseTimeUnit()` L60 | GC 老年代存活时间 |

三个实现细节值得注意：

1. **Timer.Sample 是"开始时刻"的搬运工**。`Timer.start(registry)` 返回 `Sample`（`Timer.java:310`），其 `stop(Timer)`（L326-330）用 `clock.monotonicTime() - startTime` 算出耗时再 `timer.record(...)`——切面（@Timed）就靠它在 finally 里收尾：

```java
public long stop(Timer timer) {                              // Timer.java:326-330
    long durationNs = clock.monotonicTime() - startTime;
    timer.record(durationNs, TimeUnit.NANOSECONDS);
    return durationNs;
}
```

2. **每个仪表自带 measure()**。`Counter.measure()`（`Counter.java:54-56`）返回 `new Measurement(this::count, Statistic.COUNT)`——通用注册表（`MeterRegistry.register(id, type, measurements)`）因此可以不认识具体仪表类型，这是"自定义仪表"（`Meter.builder(...)`）能工作的原因。

3. **LongTaskTimer 与 Timer 是两个东西**。Timer 记"完成的事"，LongTaskTimer 记"正在做的事"（它有 `activeTasks()`，并且 Builder 默认直方图区间是 2 分钟~2 小时，`LongTaskTimer.java:289-291`——因为长任务的时间尺度完全不同）。

还有一个 @Incubating 的 `MultiGauge`（`MultiGauge.java:40-41`，1.1.0 起）：注意它是**类而非 Meter 子接口**，本质是"虚拟表"——每次 `register(rows, overwrite)` 把同名同公共标签的一组行（`Row<T>` L146）同步成一组 Gauge（增/删/改），适合"一行一个维度组合"的表格型数据（如连接池明细）。

## 2.4 Meter.Id 与 Tag：仪表的身份系统

**先说白话**：Meter.Id 是仪表的"身份证"，核心三元组是 name + tags + type。两个 Id 只要 name 和 tags 相同就视为同一个 meter（equals 特意不比较 type）——这是"同名同标签复用"的依据。

【源码证据】`Meter.java:183-195`（内部类 Id）：

```java
class Id {                                                   // L183

    private final String name;                               // L185

    private final Tags tags;                                 // L187

    private final Type type;                                 // L189

    private final Meter.@Nullable Id syntheticAssociation;   // L191（合成关联，如 Timer 与其 LTT）

    private final @Nullable String description;              // L193

    private final @Nullable String baseUnit;                 // L195
```

```java
public boolean equals(@Nullable Object o) {                  // L352-359：只比 name + tags
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Meter.Id other = (Meter.Id) o;
    return name.equals(other.name) && tags.equals(other.tags);
}
```

Id 上还有两个"延迟到后端"的方法，是 NamingConvention 的接线处（L322-341）：

```java
public String getConventionName(NamingConvention namingConvention) {   // L322
    return namingConvention.name(name, type, baseUnit);
}

public List<Tag> getConventionTags(NamingConvention namingConvention) { // L332
    return StreamSupport.stream(tags.spliterator(), false)
        .map(t -> Tag.of(namingConvention.tagKey(t.getKey()), namingConvention.tagValue(t.getValue())))
        .collect(Collectors.toList());
}
```

`Tag`（`Tag.java:24`）只 `extends Comparable<Tag>`，提供 `getKey()/getValue()` 与不可变实现 `ImmutableTag`；集合 `Tags`（`Tags.java`）是 final 不可变类，`and(...)` 系列做链式拼接（L176-212）、`of(...)` 系列做构造（L329-387）。

**一个高频误述的澄清**：`io.micrometer.core.instrument.Tag`（1.0 起，服务于指标）与 `io.micrometer.common.KeyValue`（1.10 起，服务于 Observation）是**结构平行的两个独立接口**——都用 key/value、都是 Comparable，但 `Tag` 从未 `extends KeyValue`（用 `git log -S "extends KeyValue" -- Tag.java` 验证无任何提交）。两者靠 `DefaultMeterObservationHandler.createTags`（`DefaultMeterObservationHandler.java:157-163`）手工转换。为什么是两个？因为 observation 模块不能反向依赖 core，而 commons 不能背上指标语义——门面世界里连"键值对"都要解耦。

## 2.5 NamingConvention：埋点命名到后端命名的"最后一公里"

**先说白话**：你写 `counter("api.requests")`，Prometheus 拿到的是 `api_requests_total`，Graphite 拿到的是 `api.requests`——转换规则就是 NamingConvention，每个注册表自带一份。

【源码证据】core 只提供约定常量（`instrument/config/NamingConvention.java`，接口声明 L38）：

```java
NamingConvention identity = (name, type, baseUnit) -> name;      // L40：原样（Composite 用）
NamingConvention dot = identity;                                  // L46：点分即原样
NamingConvention snakeCase = new NamingConvention() { ... };     // L48：. → _（MeterRegistry 默认）
NamingConvention camelCase = new NamingConvention() { ... };     // L64
NamingConvention upperCamelCase = new NamingConvention() { ... };// L100
NamingConvention slashes = new NamingConvention() { ... };       // L125：. → /（@since 1.1.0）
```

接口方法只有三个（L145-153）：`name(name, type, baseUnit)`（抽象）与 default 的 `tagKey(String)`/`tagValue(String)`（原样返回）。**后端专属实现不在 core**——全仓 `implements NamingConvention` 共 22 处，例如 `PrometheusNamingConvention`（`implementations/micrometer-registry-prometheus/.../PrometheusNamingConvention.java`）、`DatadogNamingConvention`、Graphite 的分层/维度双实现（`GraphiteHierarchicalNamingConvention` / `GraphiteDimensionalNamingConvention`）。细节展开见六.6.1。

## 2.6 MeterBinder：把第三方状态变成指标的适配器

**先说白话**：JVM 内存、Tomcat 线程池、Caffeine 缓存……这些数据在别人手里。MeterBinder 的约定是"给我 registry，我把该注册的一次注册完"。

【源码证据】`instrument/binder/MeterBinder.java:27-29`：

```java
public interface MeterBinder {                               // L27

    void bindTo(MeterRegistry registry);                     // L29
```

binder 包共 208 个 java 文件，按被观测对象分包（节选）：`jvm`（61 个）、`httpcomponents`（23）、`grpc`（21）、`jetty`（16）、`jersey`（15）、`mongodb`（7）、`cache`（9）、`okhttp3`（8）、`http`（9）、`kafka`/`db`/`system`/`netty4`/`tomcat`/`jpa` 等。

以 `JvmGcMetrics` 为例看 binder 模式三要素（`binder/jvm/JvmGcMetrics.java`，类声明 L73，`implements MeterBinder, AutoCloseable`）：持有第三方源（MXBean/NotificationEmitter）、把状态包成 Gauge（弱引用 state + 取值函数）、把事件转成 Timer.record。

```java
@Override
public void bindTo(MeterRegistry registry) {                 // L128
    if (!this.managementExtensionsPresent || !this.garbageCollectorNotificationsAvailable) {
        return;
    }
    gcNotificationListener = new GcMetricsNotificationListener(registry);
    ...
    maxDataSize = new AtomicLong((long) maxLongLivedPoolBytes);
    Gauge.builder("jvm.gc.max.data.size", maxDataSize, AtomicLong::get)   // L140：状态 → Gauge
        .tags(tags)
        .description("Max size of long-lived heap memory pool")
        .baseUnit(BaseUnits.BYTES)
        .register(registry);
```

```java
public void handleNotification(Notification notification, Object ref) {   // L221：GC 事件 → Timer
    CompositeData cd = (CompositeData) notification.getUserData();
    GarbageCollectionNotificationInfo notificationInfo = GarbageCollectionNotificationInfo.from(cd);
    ...
    Tags gcTags = Tags.of("gc", gcName, "action", gcAction, "cause", gcCause).and(tags);  // L231
    if (isConcurrentPhase(gcCause, gcName)) {
        Timer.builder("jvm.gc.concurrent.phase.time")        // L233
            ...
            .register(registry)
            .record(duration, TimeUnit.MILLISECONDS);
    }
    else {
        Timer.builder("jvm.gc.pause")                       // L240
            ...
```

Spring Boot 会自动装配一批 binder（`JvmMetricsAutoConfiguration` 等，见八.8.2），你也可以自己 `new JvmGcMetrics().bindTo(registry)`。

## 2.7 注解与切面：@Timed / @Counted / @MeterTag

**先说白话**：不想在方法体里写样板代码，就把注解打上去，让 AOP 替你 start/stop。core 这一套是**指标专用**的；Observation 那套（@Observed）在第七章。

注解在 `io.micrometer.core.annotation` 包：`@Timed`（L38，`@Repeatable(TimedSet.class)` L35、`@Inherited` L37；属性 `value()` L44、`extraTags()` L51、`longTask()` L58、`percentiles()` L67、`histogram()` L75、`serviceLevelObjectives()` L85 @since 1.14.0、`description()` L92）与 `@Counted`（L40；`value() default "method.counted"` L46、`extraTags()` L61、`recordFailuresOnly()` L72）。

`@MeterTag` 在 **aop 包**（`aop/MeterTag.java:43`，1.11.0 起）：打在方法参数上，用 SpEL（`expression()`）或 `resolver()` 把参数值变成 meter 的标签。

两个切面的分工：

```java
// TimedAspect.java:90（类）、L96（DEFAULT_METRIC_NAME = "method.timed"）、L107（持有 MeterRegistry）
@Around("execution (@io.micrometer.core.annotation.Timed * *.*(..))")      // L201：方法级
public @Nullable Object timedMethod(ProceedingJoinPoint pjp) throws Throwable {   // L202

@Around("@within(io.micrometer.core.annotation.Timed) && !@annotation(...Timed) && execution(* *(..))")  // L185：类级
```

环绕核心（`TimedAspect.java:229-264`）：

```java
private @Nullable Object processWithTimer(ProceedingJoinPoint pjp, Timed timed, String metricName,
        boolean stopWhenCompleted) throws Throwable {        // L229-230

    Timer.Sample sample = Timer.start(registry);             // L232：开始采样
    ...
    finally {
        record(pjp, result, timed, metricName, sample, exceptionClass);   // L264：异常类也进标签
    }
}

private void record(...) {                                   // L268
    sample.stop(recordBuilder(...).register(registry));      // L271：stop 即 record
}
```

`longTask()` 为 true 时走 `processWithLongTaskTimer`（L225 分支，L316 `buildLongTaskTimer(...).map(LongTaskTimer::start)`——长任务只 start 不等返回）。`CountedAspect`（`CountedAspect.java:79`）结构对称，切点在 L189/L223，异常/成功分别进 `outcome` 标签。`@MeterTag` 的处理由 `MeterTagAnnotationHandler`（`MeterTagAnnotationHandler.java:34`，extends commons 的 `AnnotationHandler<Timer.Builder>`）挂在 TimedAspect 上（`setMeterTagAnnotationHandler`），**没有独立的 MeterTagAspect**。

> 版本注：1.11 之前 @MeterTag 不存在；SpEL 求值依赖用户注入 `ValueExpressionResolver`（commons 接口，七.7.9），Spring Boot 的装配条件正是 `@ConditionalOnBean(ValueExpressionResolver.class)`（八.8.4）。

## 2.8 本章小结

- 一切仪表都是 `Meter`（getId + measure），六种 `Type`，读数即 `Measurement(值函数, Statistic)`；`MeterProvider`（1.12）让动态打标不必重建 builder。
- 身份 = `Meter.Id`（name + tags + type），equals 只看 name + tags——同名同标签天然复用；`Tag` 与 observation 的 `KeyValue` 是平行接口，从未互相继承。
- 命名转换被推迟到后端一侧（NamingConvention，core 只留接口），这是"维度优先"能兼容分层命名后端的关键。
- `MeterBinder` 用"bindTo 一次性注册"的方式吞下 208 个文件的第三方适配器；`@Timed/@Counted` 切面是 Timer.Sample + finally record 的标准样板。

---

# 三、注册表：meter 的一生从这里开始（MeterRegistry 体系）

## 3.1 先说白话：MeterRegistry 是抽象类，一半框架一半约定

`MeterRegistry`（`instrument/MeterRegistry.java:69`，**抽象类**）左手拿着一个线程安全的 meter 存储池，右手把"怎么造具体仪表"留成一组抽象方法（`newGauge/newCounter/newTimer/...`）给子类填空。它默认的命名约定是 snakeCase（L151），构造时注入 `Clock`（L153-156）——**整个体系的时间都从这一个口子进**，测试时换一个假时钟就能精确控制时间窗轮换（见四.4.4）。

```java
public abstract class MeterRegistry {                        // L69

    protected abstract <T> Gauge newGauge(Meter.Id id, T obj, ToDoubleFunction<T> valueFunction);  // L167

    protected abstract Counter newCounter(Meter.Id id);      // L175

    protected abstract Timer newTimer(Meter.Id id, DistributionStatisticConfig distributionStatisticConfig,
            PauseDetector pauseDetector);                    // L215-216

    protected abstract DistributionSummary newDistributionSummary(Meter.Id id,
            DistributionStatisticConfig distributionStatisticConfig, double scale);   // L227-228

    protected abstract Meter newMeter(Meter.Id id, Meter.Type type, Iterable<Measurement> measurements);  // L238

    protected abstract TimeUnit getBaseTimeUnit();           // L315

    protected abstract DistributionStatisticConfig defaultHistogramConfig();  // L327
```

存储就是一个 ConcurrentHashMap（L101-106，注释明说"写有 meterMapLock 保护、读要支持跨值迭代，所以用 CHM 防 CME"）：

```java
private final Map<Meter.Id, Meter> meterMap = new ConcurrentHashMap<>();
```

## 3.2 注册管线：getOrCreateMeter 的七步

**先说白话**：`registry.counter("api.requests", "uri", "/a")` 不是无脑 new——它要过缓存、过映射、过过滤器、过配置合并，最后才落到子类的 newXXX。这条管线是理解"filter 为什么能熔断、commonTags 为什么统一生效"的全部秘密。

【源码证据】`MeterRegistry.java:685-729`（`getOrCreateMeter`，节选、保留原顺序）：

```java
Meter m = preFilterIdToMeterMap.get(originalId);             // ① 先查"过滤前 Id"缓存
if (m != null && !isStaleId(originalId)) {
    checkAndWarnAboutDoubleRegistration(m);                  //    重复注册给警告
    return m;
}

Meter.Id mappedId = mapId(originalId);                       // ② 过 MeterFilter.map()（改名/改标签/加公共标签）
m = meterMap.get(mappedId);                                  // ③ 查"过滤后 Id"缓存
...
if (isClosed()) {
    return noopBuilder.apply(mappedId);                      // ④ 注册表已关 → 无开销空实现
}

synchronized (meterMapLock) {
    m = meterMap.get(mappedId);
    if (m == null) {
        if (!accept(mappedId)) {                             // ⑤ MeterFilter.accept() 决策（三.3.4）
            return noopBuilder.apply(mappedId);              //    被 DENY → 返回 Noop 计数器等
        }

        if (config != null) {
            for (MeterFilter filter : filters) {
                DistributionStatisticConfig filteredConfig = filter.configure(mappedId, config);  // ⑥ filter.configure 改分布配置
                if (filteredConfig != null) {
                    config = filteredConfig;
                }
            }
            config = config.merge(defaultHistogramConfig()); //    最后与注册表默认直方图配置合并
        }

        m = meterSupplier.create(this, mappedId, config, specificPauseDetector);   // ⑦ 子类 newXXX 真正造表
        ...
```

两本缓存的意义：`preFilterIdToMeterMap` 保证**同一次请求不管 filter 怎么改名，拿到的都是同一个 meter 实例**；`meterMap`（按 mappedId）保证**过滤后身份相同的 meter 全局唯一**。`accept()` 的决策规则（L782-793）：任一 filter 说 DENY 即拒绝，任一说 ACCEPT 即通过，全 NEUTRAL 则放行：

```java
private boolean accept(Meter.Id id) {                        // L782-793
    for (MeterFilter filter : filters) {
        MeterFilterReply reply = filter.accept(id);
        if (reply == MeterFilterReply.DENY) {
            return false;
        }
        else if (reply == MeterFilterReply.ACCEPT) {
            return true;
        }
    }
    return true;
}
```

`MeterFilterReply` 就三个值（`instrument/config/MeterFilterReply.java:18-20`）：`DENY, NEUTRAL, ACCEPT`。

其余公开 API 的行号速查：造表入口（包级，供 Builder 调）`counter(Id)` L345、`gauge(...)` L358、`timer(...)` L374、`summary(...)` L389、`register(Id, Type, measurements)` L404；遍历/检索 `getMeters()` L415、`config()` L434、`find(String)` L444、`get(String)` L454；快捷注册 `counter(String, String...)` L475 等；`more()` L548（低频表型入口）；`remove(Meter)` L805、`remove(Meter.Id)` L835、`clear()` L868、`close()` L1284；`getMappedId` L666、`mapId` L674。

## 3.3 Config：注册表自己的"控制面板"

**先说白话**：`registry.config()` 返回的 Config 是注册表的配置面板——公共标签、过滤器、命名约定、时钟、监听器都在这配。它的一切方法都是**顺序敏感**的。

【源码证据】`MeterRegistry.java:875`（内部类 Config）：

```java
public class Config {                                        // L875

    public Config commonTags(Iterable<Tag> tags) {           // L883：语法糖，等价于 commonTags 的 meterFilter
        return meterFilter(MeterFilter.commonTags(tags));
    }

    public synchronized Config meterFilter(MeterFilter filter) {   // L905
        if (!meterMap.isEmpty()) {
            logWarningAboutLateFilter();                     // L907：已有 meter 时再配 filter → 告警
            ...
```

其余方法行号：`onMeterAdded(Consumer<Meter>)` L939、`onMeterRemoved` L951、`onMeterRegistrationFailed` L964、`namingConvention(...)` L975、`clock()` L991、`pauseDetector(...)` L1002。`More`（L1081）是低频表型入口：`longTaskTimer(...)` L1090、`counter(..., obj, func)`（FunctionCounter）L1142、`timer(...)`（FunctionTimer）L1211、`timeGauge(...)` L1254。

## 3.4 MeterFilter：注册管线的闸门

**先说白话**：MetricFilter 是三个钩子的组合——`map`（改 Id）、`accept`（放/拒）、`configure`（改分布统计配置）。官方内置了一整套静态工厂，覆盖"限流、改名、统一标签、砍高基数"四大场景。

【源码证据】`instrument/config/MeterFilter.java`（关键静态工厂，行号实测）：

| 场景 | 工厂方法 | 行号 |
|---|---|---|
| 统一公共标签 | `commonTags(Iterable<Tag>)` → `map(id)` 加标签 | L55 |
| 重命名标签 | `renameTag(meterNamePrefix, from, to)` | L71 |
| 删除标签（治高基数） | `ignoreTags(String... tagKeys)` | L96 |
| 替换标签值（如把具体 URI 归并） | `replaceTagValues(tagKey, replacement, exceptions...)` | L122 |
| 白名单/黑名单 | `denyUnless(Predicate)` L147、`accept(Predicate)` L162、`deny(Predicate)` L177、`denyNameStartsWith(prefix)` L286 | |
| 全局熔断 | `accept()` L191、`deny()` L200 | |
| 时间序列上限 | `maximumAllowableMetrics(int)` L217 | |
| **按标签值熔断（防基数爆炸）** | `maximumAllowableTags(meterNamePrefix, tagKey, maximumTagValues, onMaxReach)` | L241 |
| 改分布统计配置 | `maxExpected(prefix, Duration)` L308、`minExpected(...)` L365 | |

`maximumAllowableTags` 值得单独一提：它是生产上防"每个用户 ID 一个标签值"把后端打爆的第一道闸——超过 `maximumTagValues` 后触发 `onMaxReach`（通常是 `MeterFilter.deny()` 或 `replaceTagValues` 把溢出值归并成 "other"）。Spring Boot 对 composite registry 只保留这一种 filter（八.8.2），其余交给子注册表，正是这个原因。

## 3.5 Search：从注册表里找 meter

【源码证据】`instrument/search/Search.java`（final class L36）：`Search.in(registry)` L329 → `name(...)` L57/L66 → `tags(...)`/`tag(...)` L78-99 → `meter()` L196（可空）；`get(name)` 走 `RequiredSearch`（找不到直接抛 `MeterNotFoundException`）。Boot 的 `/actuator/metrics/{name}?tag=k:v` 就建立在这上面（八.8.5）。

## 3.6 CompositeMeterRegistry 与 Metrics 全局门面

**先说白话**：组合注册表是"接线板"——你在它身上注册的每个 meter，会被复制到它挂着的每个真实子注册表里（各自再过一遍各自的 filter）；`Metrics` 则是一个**静态全局**的接线板，给不方便注入 registry 的场合兜底。

【源码证据】`instrument/composite/CompositeMeterRegistry.java:66-79`（构造时挂好同步钩子）：

```java
public CompositeMeterRegistry(Clock clock, Iterable<MeterRegistry> registries) {
    super(clock);
    config().namingConvention(NamingConvention.identity).onMeterAdded(m -> {
        if (m instanceof CompositeMeter) { // should always be
            lock(registriesLock, () -> nonCompositeDescendants.forEach(((CompositeMeter) m)::add));  // 新 meter 同步给所有子 registry
        }
    }).onMeterRemoved(m -> {
        if (m instanceof CompositeMeter) { // should always be
            lock(registriesLock, () -> nonCompositeDescendants.forEach(r -> r.removeByPreFilterId(m.getId())));
        }
    });

    registries.forEach(this::add);
}
```

`add/remove`（L140-184）触发 `updateDescendants()`（L208-240）递归展平嵌套 composite；每个 newXXX（如 newTimer L82-85）造的是 `CompositeTimer` 这种**组合仪表**，真正的复制逻辑在 `AbstractCompositeMeter.registerNewMeter`（在每个子 registry 里各自走一遍完整注册管线）。

**noop 降级**：一个没有任何子注册表的 Composite 必须还能让业务代码无感调用。`AbstractCompositeMeter.firstChild()`（`AbstractCompositeMeter.java:52-66`）：

```java
T firstChild() {
    final Iterator<T> i = children.values().iterator();
    if (i.hasNext())
        return i.next();

    // There are no child meters. Return a lazily instantiated no-op meter.
    final T noopMeter = this.noopMeter;
    if (noopMeter != null) {
        return noopMeter;
    }
    else {
        // noinspection ConstantConditions
        return this.noopMeter = newNoopMeter();   // 惰性创建 NoopCounter/NoopTimer...
    }
}
```

`Metrics`（`instrument/Metrics.java:33-55`）就是持有一个全局 Composite 的静态门面：

```java
public class Metrics {

    public static final CompositeMeterRegistry globalRegistry = new CompositeMeterRegistry();  // L35

    public static void addRegistry(MeterRegistry registry) {          // L41
        globalRegistry.add(registry);
    }

    public static void removeRegistry(MeterRegistry registry) {       // L46
        globalRegistry.remove(registry);
    }
```

静态便捷方法（`Metrics.counter(...)` L63-76、`Metrics.timer(...)` L105-118、`Metrics.gauge(...)` L147-224）全部一行委托 globalRegistry。Spring Boot 默认把 auto-configured registry 也加进 globalRegistry（`management.metrics.use-global-registry`，八.8.2）。

## 3.7 noop 家族：被拒绝的 meter 也要"能用"

`instrument/noop/` 下有 9 个类（NoopCounter/NoopTimer/NoopGauge/NoopDistributionSummary/NoopLongTaskTimer/NoopFunctionCounter/NoopFunctionTimer/NoopTimeGauge/NoopMeter）。触发点在 3.2 的管线里（filter DENY 或 registry 已关闭 → `noopBuilder.apply(mappedId)`）。它们不是 null，而是**合法但永远为零**的仪表（`NoopCounter.java:20-35`：`increment` 空方法、`count()` 返回 0）——调用方代码不需要写任何 if，这是门面 API "一次埋点"承诺的兜底。

## 3.8 本章小结

- MeterRegistry = CHM 存储池 + 七步注册管线（预过滤缓存 → mapId → accept → configure → merge → newXXX → onMeterAdded）。
- MeterFilter 的 map/accept/configure 三钩子分别治"名字、数量、直方图"；`maximumAllowableTags` 是基数爆炸的熔断闸。
- Config 顺序敏感（晚配 filter 有告警）；Composite 用"复制 + noop 降级"实现零依赖可用；Metrics 是它的静态化身。

---

# 四、分布统计内核：P99 是怎么算出来的（distribution / cumulative 包）

## 4.1 先说白话：Timer 的三本账与两难选择

Timer 至少要维护 count、total、max 三本账（这样才能算均值和 max）。但当产品经理要看 P99 时，两难来了：

- **客户端百分位（client-side percentiles）**：用 HdrHistogram 在内存里维护完整分布，直接读 P99——准，但**每台应用实例各自算各自的 P99**，且内存开销大（每个 timer 一份直方图）；
- **聚合直方图（aggregated histogram/SLO 桶）**：只维护"落在每个预设桶里的次数"——便宜、可在后端把多实例桶**相加**后算分位数，但只能报桶边界上的精度。

Micrometer 两条路都给，由 `DistributionStatisticConfig` 的两个开关决定（`isPublishingPercentiles()` → HdrHistogram；`isPublishingHistogram()` → 固定边界桶），决策点在 `AbstractTimer.defaultHistogram`（四.4.3）。**绝大多数后端推荐后者**——Prometheus/OTLP 的 histogram 语义就是桶（六.6.1/6.2）。

## 4.2 DistributionStatisticConfig：一张会层层合并的配置卡

**先说白话**：直方图行为（要不要百分位、桶边界、时间窗多长）由这张"配置卡"描述；仪表自己的配置会 merge 注册表默认配置，filter 还能中途改写——merge 规则是"自己有的优先，没有的抄父级"。

【源码证据】`instrument/distribution/DistributionStatisticConfig.java:40-48`（内置默认）：

```java
public static final DistributionStatisticConfig DEFAULT = builder().percentilesHistogram(false)
    .percentilePrecision(1)                        // HdrHistogram 精度位数
    .minimumExpectedValue(1.0)
    .maximumExpectedValue(Double.POSITIVE_INFINITY)
    .expiry(Duration.ofMinutes(2))                 // 时间窗总长 2 分钟
    .bufferLength(3)                               // 窗口切成 3 格 → 每格 40 秒
    .build();
```

merge（L77-94，字段逐个三元表达式）与桶计算（L101-117）：

```java
public NavigableSet<Double> getHistogramBuckets(boolean supportsAggregablePercentiles) {  // L101
    NavigableSet<Double> buckets = new TreeSet<>();

    if (percentileHistogram != null && percentileHistogram && supportsAggregablePercentiles) {
        buckets.addAll(PercentileHistogramBuckets.buckets(this));   // 由 min/max 期望值生成的指数桶
        buckets.add(minimumExpectedValue);
        buckets.add(maximumExpectedValue);
    }

    if (serviceLevelObjectives != null) {
        for (double sloBoundary : serviceLevelObjectives) {
            buckets.add(sloBoundary);               // SLO 边界也是桶
        }
    }

    return buckets;
}
```

两个决策开关（L489-496）：

```java
public boolean isPublishingPercentiles() {       // L489：配了 percentiles → HdrHistogram 路线
    return percentiles != null && percentiles.length > 0;
}

public boolean isPublishingHistogram() {         // L493：percentilesHistogram=true 或配了 SLO → 桶路线
    return (percentileHistogram != null && percentileHistogram)
            || (serviceLevelObjectives != null && serviceLevelObjectives.length > 0);
}
```

Builder 关键项行号：`percentiles(double...)` L273、`percentilePrecision` L286、`serviceLevelObjectives` L303、`minimumExpectedValue` L376、`maximumExpectedValue` L402、`expiry` L418、`bufferLength` L432、`build()` 校验 L440-485（percentiles 须在 [0,1]、min<=max 等）。

## 4.3 record 全路径：从一次调用到三本账 + 直方图 + 停顿补偿

**先说白话**：`timer.record(100, MILLISECONDS)` 看似一行，实际走了"模板方法记直方图 → 子类记 count/total/max → 可选的停顿补偿"三层。

【源码证据】`instrument/AbstractTimer.java:270-288`（final，模板方法）：

```java
@Override
public final void record(long amount, TimeUnit unit) {
    if (amount >= 0) {
        histogram.recordLong(TimeUnit.NANOSECONDS.convert(amount, unit));   // ① 先喂直方图（可能是 NoopHistogram）
        recordNonNegative(amount, unit);                                     // ② 子类记 count/total/max

        if (intervalEstimator != null) {
            ((IntervalEstimator) intervalEstimator).recordInterval(clock.monotonicTime());  // ③ 停顿检测采样
        }
    }
    else {
        ... // 负数告警（默认关闭）
    }
}
```

累计型的三本账（`instrument/cumulative/CumulativeTimer.java:61-75`）：

```java
protected CumulativeTimer(Id id, Clock clock, DistributionStatisticConfig distributionStatisticConfig,
        PauseDetector pauseDetector, TimeUnit baseTimeUnit, Histogram histogram) {
    super(id, clock, pauseDetector, baseTimeUnit, histogram);
    this.count = new LongAdder();        // 计数
    this.total = new LongAdder();        // 总耗时（纳秒）
    this.max = new TimeWindowMax(clock, distributionStatisticConfig);   // 会衰减的 max
}

@Override
protected void recordNonNegative(long amount, TimeUnit unit) {          // L70-74
    long nanoAmount = unit.toNanos(amount);
    count.increment();
    total.add(nanoAmount);
    max.record((double) nanoAmount, TimeUnit.NANOSECONDS);
}
```

`CumulativeDistributionSummary.recordNonNegative`（`CumulativeDistributionSummary.java:74-79`）完全同构。**直方图选型**在父类 `AbstractTimer.defaultHistogram`（L121-134，`AbstractDistributionSummary` 有对称实现 L53-66）：`isPublishingPercentiles()` → `TimeWindowPercentileHistogram`；否则 `isPublishingHistogram()` → `TimeWindowFixedBoundaryHistogram`；都关 → `NoopHistogram.INSTANCE`（零开销）。

**停顿补偿（coordinated omission）**是隐藏的第四层：系统 STW/GC 停顿时请求没发出，"本该出现的样本"缺失了，分位数会偏乐观。`AbstractTimer.initPauseDetector`（L136-174）用反射探测 LatencyUtils（L142 `Class.forName("org.LatencyUtils.SimplePauseDetector")`），配合 `TimeCappedMovingAverageIntervalEstimator(128, 10s, pauseDetector)`（L162）估算正常请求间隔；补偿点在 `recordValueWithExpectedInterval`（L176-184）——把"停顿期间缺失"的样本按期望间隔补录。没配 LatencyUtils 时全程零开销（`distribution/pause/NoPauseDetector.java:21-36`，1.16.0 起的单例默认值）。

## 4.4 时间窗轮换三兄弟：内存恒定的秘密

**先说白话**：如果 count/total/max 永远累加，max 会停在一个月前的那次尖刺上。Micrometer 的解法是"环形窗口"：把时间切成 N 格（默认 expiry=2min、bufferLength=3），**写入时所有格子都记，读取时只读当前格**；时间流逝就轮换清零。这样任何时刻的内存占用恒定，读到的又永远是"最近一段"的统计。

### 4.4.1 TimeWindowSum：写全窗、读当前格

【源码证据】`distribution/TimeWindowSum.java:59-64`：

```java
public void record(long sampleMillis) {
    rotate();                                    // 先把该轮换的格子轮换掉
    for (AtomicLong sum : ringBuffer) {          // ★ 每个格子都累加
        sum.addAndGet(sampleMillis);
    }
}
```

`poll()`（L69-74）只返回 `ringBuffer[currentBucket]`；`rotate()`（L76-105）用 CAS 的 `rotatingUpdater` 标记防并发重入，再 synchronized 循环清零、推进 `currentBucket`（最多绕 ringBuffer.length 圈）。

### 4.4.2 TimeWindowMax：CAS 争最大 + 整窗过期清零

【源码证据】`distribution/TimeWindowMax.java:118-128`：

```java
public void record(double sample) {
    record(Double.doubleToLongBits(sample));     // Summary 用位模式比较；Timer 用原值
}

private void updateMax(AtomicLong max, long sample) {
    long curMax;
    do {
        curMax = max.get();
    }
    while (curMax < sample && !max.compareAndSet(curMax, sample));   // 无锁 CAS 争最大
}
```

衰减行为有两级（L143-153）：普通轮换清当前格；**超过整个窗口时长没活动则整体清零**——这就是"max 会随时间衰减"的来源（Prometheus 上表现为 `http_server_requests_seconds_max` 平滑回落）。

### 4.4.3 直方图的时间窗：AbstractTimeWindowHistogram

【源码证据】`distribution/AbstractTimeWindowHistogram.java:69-81`（构造）：

```java
final int ageBuckets = Objects.requireNonNull(distributionStatisticConfig.getBufferLength());

ringBuffer = (T[]) Array.newInstance(bucketType, ageBuckets);

durationBetweenRotatesMillis = Objects.requireNonNull(distributionStatisticConfig.getExpiry()).toMillis()
        / ageBuckets;                            // 每格时长 = expiry / bufferLength
```

`rotate()`（L203-235）逐格 `resetBucket` 并把"累积直方图"标记 stale；`takeSnapshot`（L134-153）先 rotate、stale 则把当前格 accumulate 进累积直方图再读——**快照保证 count/total/percentile/buckets 出自同一瞬间**（`HistogramSupport.java:20-28` 的 javadoc 明说这是为了防止"桶计数之间不一致"）。

两个子类对应两条路线：

- **TimeWindowPercentileHistogram（客户端百分位）**：环形窗里放 HdrHistogram 的 `DoubleRecorder`（`TimeWindowPercentileHistogram.java:38-43`），accumulate 时把 interval 快照 add 进 `DoubleHistogram` 累积器（L105-115），`countsAtBuckets()`（L128-144）按累积/非累积语义输出桶计数。
- **TimeWindowFixedBoundaryHistogram（SLO 桶）**：桶集合来自 `getHistogramBuckets(...)` + 可选 +Inf 桶（`TimeWindowFixedBoundaryHistogram.java:86-101`），不用 swap 累积（L124-135），单格直接计数。单格内部是 `FixedBoundaryHistogram`：`record(long)` 用**二分查找**找 `<= value` 的最小桶（`FixedBoundaryHistogram.java:57-78` 的 `leastLessThanOrEqualTo`），`getCountAtBuckets()`（L84-99）按需输出累计/非累计桶数。

### 4.4.4 快照：HistogramSnapshot

【源码证据】`distribution/HistogramSnapshot.java:32-60`：字段 `count/total/max/percentileValues(ValueAtPercentile[])/histogramCounts(CountAtBucket[])`，构造时空数组兜底（L55-56）。Timer 的 `histogramCountAtValue`/`percentile` 方法已 @Deprecated（`Timer.java:267-296`），官方口径统一到 `takeSnapshot()`。

## 4.5 本章小结

- 分布配置是一张可层层 merge 的卡（仪表配置 > filter.configure > 注册表 default），两个开关对应两条路线：percentiles → HdrHistogram（实例本地准），SLO 桶 → FixedBoundary（后端可聚合）。
- record 的四层：直方图 → count/total/max →（可选）停顿补偿；三本账分别是 LongAdder×2 + TimeWindowMax。
- 时间窗轮换是"内存恒定 + 统计新鲜"的全部秘密：写全窗、读当前格、CAS 轮换、整窗过期清零。
- 一切读数走 `takeSnapshot()`，快照内强一致。

---

# 五、发布机制：step、push、pull 三种节奏（step / push / simple 包）

## 5.1 先说白话：计数语义之争

同一个 Counter，Prometheus 想要**从启动到现在累加**的值（`rate()` 自己算），Graphite/Influx 想要**每分钟增量**。Micrometer 用两个词概括：**cumulative（累计）**与 **step（步进/速率归一）**。谁用哪种不是随便选的——它是注册表的身份：

- `SimpleMeterRegistry` 两种都支持，由 `CountingMode` 配置（`simple/CountingMode.java:18-30`：`CUMULATIVE`"monotonically increasing"、`STEP`"rate normalize"；默认 CUMULATIVE，`SimpleConfig.java:54-56`）：

```java
// SimpleMeterRegistry.java:114-123
@Override
protected Counter newCounter(Meter.Id id) {
    switch (config.mode()) {
        case CUMULATIVE:
            return new CumulativeCounter(id);
        case STEP:
        default:
            return new StepCounter(id, clock, config.step().toMillis());
    }
}
```

- 面向 Prometheus 的注册表必须 cumulative，面向 Graphite 系的必须 step（第六章的 extends 总览表可印证：13 个 StepMeterRegistry 子类 + 2 个 PushMeterRegistry 子类）。

`SimpleConfig.step()` 默认 1 分钟（`SimpleConfig.java:45-47`），`SimpleMeterRegistry.newTimer`（L86-107）在装配前还会把 `expiry=config.step()` merge 进分布配置——步进语义下统计窗与投递窗对齐。

## 5.2 step 内核：StepValue 的"差值 + 换步"

**先说白话**：Step 型仪表对外报的永远是"上一个完整步长里的增量"。实现精华在 `StepValue.rollCount`：用一个 CAS 的 `lastInitPos`（步号）判断"上一步是否结束"，结束时把内部累加器 `sumThenReset`（取走即清零）的值搬进 `previous` 对外发布。

【源码证据】`instrument/step/StepValue.java:68-87`：

```java
private void rollCount(long now) {
    final long stepTime = now / stepMillis;              // 当前步号 = 时间 / 步长
    final long lastInit = lastInitPos.get();
    if (lastInit < stepTime && lastInitPos.compareAndSet(lastInit, stepTime)) {   // CAS 抢"换步权"
        final V v = valueSupplier().get();               // sumThenReset：取走即清零
        // 有活动 → 步号前移 1；无活动 → previous 归为 noValue()（0）
        previous = (lastInit == stepTime - 1) ? v : noValue();
    }
}

/**
 * @return The value for the last completed interval.
 */
public V poll() {
    rollCount(clock.wallTime());
    return previous;                                     // ★ 读到的是"上一个完整步"
}
```

- `StepDouble`（L31-49）/`StepLong`（L32-39）：内部累加器，`valueSupplier` 就是 `current::sumThenReset`。
- `StepCounter.count()` = `value.poll()`（L39-46）——**读操作即换步操作**，这个设计把"定时轮询"的职责转嫁给了后端抓取。
- `StepTimer` 需要同时发布 count 和 total 两个差值，用 `StepTuple2`（`StepTimer.java:70-99`）：

```java
countTotal = new StepTuple2<>(clock, stepDurationMillis, 0L, 0L,
        count::sumThenReset, total::sumThenReset);       // L74-75：两个累加器同步换步
...
@Override
public long count() {
    return countTotal.poll1();                           // L90-92
}

@Override
public double totalTime(TimeUnit unit) {
    return TimeUtils.nanosToUnit(countTotal.poll2(), unit);   // L94-96
}
```

注意 StepTimer 的 max 用的还是 `TimeWindowMax`（L76）——max 不做差值，它本来就"只看最近"。`StepDistributionSummary`（L71-98）与 StepTimer 同构。

## 5.3 StepMeterRegistry：双调度器与 step 边界对齐

**先说白话**：step 型注册表有两个定时任务，别混为一谈——`pollMetersToRollover` 只负责在步长边界把所有仪表的 count() 调一遍（触发 5.2 的换步）；真正的网络推送 `publish()` 由父类 PushMeterRegistry 的调度器管。

【源码证据】`instrument/step/StepMeterRegistry.java:44-58`（**继承 PushMeterRegistry**）：

```java
public abstract class StepMeterRegistry extends PushMeterRegistry {
    ...
    private final StepRegistryConfig config;

    private @Nullable ScheduledExecutorService meterPollingService;
```

```java
@Override
public void start(ThreadFactory threadFactory) {         // L120-130
    super.start(threadFactory);                          // 先启动父类的 publish 调度

    if (config.enabled()) {
        this.meterPollingService = Executors.newSingleThreadScheduledExecutor(
                new NamedThreadFactory("step-meter-registry-poller-for-" + getClass().getSimpleName()));
        this.meterPollingService.scheduleAtFixedRate(this::pollMetersToRollover, getInitialDelay(),
                config.step().toMillis(), TimeUnit.MILLISECONDS);
    }
}
```

```java
void pollMetersToRollover() {                            // L189-201
    this.lastMeterRolloverStartTime = clock.wallTime();
    this.getMeters()
        .forEach(m -> m.match(gauge -> null, Counter::count, Timer::count, DistributionSummary::count,
                meter -> null, meter -> null, FunctionCounter::count, FunctionTimer::count, meter -> null));  // ★ 调 count() 就是为触发换步
}

private long getInitialDelay() {                         // 步长边界对齐：落到下一个 step 的 1ms 后
    long stepMillis = config.step().toMillis();
    // schedule one millisecond into the next step
    return stepMillis - (clock.wallTime() % stepMillis) + 1;
}
```

close 时的"最后一步别丢"：`close()`（L141-163）会先 `closingRolloverStepMeters()`（L177-182）调 `StepMeter::_closingRollover`（`StepTuple2.java:83-88`：强制滚窗且不再滚动）再走父类补发。newXXX 钩子集在 L66-110（LongTaskTimer 用 `DefaultLongTaskTimer`——**没有 StepLongTaskTimer**，长任务本就按活跃状态计量，无需差值）。

## 5.4 PushMeterRegistry：推送的纪律

**先说白话**：所有"定时往外发"的注册表都继承它。它只管四件事：按 step 调度 publish、并发去重、随机错峰、close 补发——网络细节留给子类。

【源码证据】`instrument/push/PushMeterRegistry.java`：

```java
protected abstract void publish();                       // L52：唯一要子类实现的方法

void publishSafelyOrSkipIfInProgress() {                 // L59-76：信号量去重 + 异常兜底
    if (this.publishingSemaphore.tryAcquire()) {
        this.lastScheduledPublishStartTime = clock.wallTime();
        try {
            publish();
        }
        catch (Throwable e) {
            logger.warn("Unexpected exception thrown while publishing metrics for " + getClass().getSimpleName(), e);
        }
        finally {
            this.publishingSemaphore.release();
        }
    }
    else {
        logger.warn("Publishing is already in progress. Skipping duplicate call to publish().");
    }
}

public void start(ThreadFactory threadFactory) {         // L104-117：单线程 + step 周期
    ...
    scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(threadFactory);
    long stepMillis = config.step().toMillis();
    long initialDelayMillis = calculateInitialDelay();
    scheduledExecutorService.scheduleAtFixedRate(this::publishSafelyOrSkipIfInProgress, initialDelayMillis,
            stepMillis, TimeUnit.MILLISECONDS);
}
```

两个贴心细节：`calculateInitialDelay()`（L166-175）在 80% 范围内**随机错峰**（常量 `PERCENT_RANGE_OF_RANDOM_PUBLISHING_OFFSET = 0.8`，L34），并保证至少比 StepMeterRegistry 的轮询器晚 2ms 起——避免"还没换步就把旧数据发出去"；close（L139-147）会补一次 publish 或等待进行中的推送（`waitForInProgressScheduledPublish` L154-163）。`PushRegistryConfig` 默认值：`step()` 1 分钟、`enabled()` true（L39-48）、`batchSize()` 10000（L89-91）。

## 5.5 实现族总览：22 个注册表怎么分派

（主类 extends 声明逐一验证，行号为类声明行）

| 投递节奏 | 模块（主类 extends 声明） |
|---|---|
| **StepMeterRegistry**（步进推送） | appoptics(52)、azure-monitor(46)、cloudwatch2(54)、datadog(46)、dynatrace(55)、elastic(54)、ganglia(45)、humio(57)、influx(47)、kairos(46)、new-relic(36)、signalfx(58)、stackdriver(62) |
| **PushMeterRegistry**（自定义推送） | otlp(66)、opentsdb(61) |
| **MeterRegistry**（pull / 自管节奏） | prometheus 新版(58)、prometheus-simpleclient 旧版(61)、atlas(48)、statsd(81) |
| **DropwizardMeterRegistry 桥接** | graphite(28)、jmx(27) |
| **SimpleMeterRegistry 特化** | health(55)（SLO 健康判定） |

core 里还有两个"玩具级但常用"的：`LoggingMeterRegistry`（`instrument/logging/LoggingMeterRegistry.java:56-57`，`extends StepMeterRegistry`，publish() 把指标打印成可读文本 L120-123，本地调试神器）与 `SimpleMeterRegistry`（五.5.1，测试标配）。

## 5.6 本章小结

- cumulative vs step 不是配置项而是注册表身份；SimpleMeterRegistry 之所以两种都行，是因为它把 `CountingMode` 做成了配置。
- step 的全部魔法在 `StepValue.rollCount`：CAS 步号 + sumThenReset + previous；读即换步，所以 StepMeterRegistry 只需要"定时调一遍 count()"。
- PushMeterRegistry 定义了推送的纪律（去重、错峰、兜底、补发），StepMeterRegistry 只是它的一个"换步版"子类。

---

# 六、典型后端实现剖析（implementations/）

## 6.1 Prometheus（新版，1.13 起）：pull 的标准姿势

**先说白话**：pull 型注册表不主动发数据，只等着被抓。它的职责是把内存里的 meter 翻译成 Prometheus 文本格式，抓取那一刻才"现做快照"。

【源码证据】`implementations/micrometer-registry-prometheus/src/main/java/io/micrometer/prometheusmetrics/PrometheusMeterRegistry.java`（包名 `io.micrometer.prometheusmetrics`，`@since 1.13.0`）：

```java
public class PrometheusMeterRegistry extends MeterRegistry {     // L58：不是 Step/Push 型

    private final PrometheusConfig prometheusConfig;             // L65
    private final PrometheusRegistry registry;                   // L67：新客户端的注册表
    private final ExpositionFormats expositionFormats;           // L69：text/OpenMetrics 双格式
    private final ConcurrentMap<String, MicrometerCollector> collectorMap = new ConcurrentHashMap<>();  // L71
```

```java
public String scrape() {                                         // L126-128：默认 text 0.0.4
    return scrape(TEXT_004_CONTENT_TYPE);                        // L63：TEXT_004_CONTENT_TYPE 常量
}

private void scrape(OutputStream outputStream, String contentType, MetricSnapshots snapshots) throws IOException {  // L161
    expositionFormats.findWriter(contentType).write(outputStream, snapshots);   // OpenMetrics 由 contentType 分派
}
```

翻译层的组织方式：**一个 Meter 家族一个 `MicrometerCollector`**（`MicrometerCollector.java:74`，实现新客户端的 `MultiCollector`；`addCounter/addTimer/...` 在 L95-141；`collect()` 在 L304-318 按家族聚合 DataPoint 快照）。抓取时由 `PrometheusRegistry.scrape()` 逐 collector 回调。

两个映射细节最能体现"后端差异吸收在实现里"：

1. **直方图桶必须是累计的、且几乎不滚动**。`PrometheusTimer`（L52-60）先 merge 进 `percentilesHistogram(false)+SLO` 强制走桶路线；桶的实现 `PrometheusHistogram`（`PrometheusHistogram.java:36-47`）继承 TimeWindowFixedBoundaryHistogram 但把窗口调成"永不过期"：

```java
class PrometheusHistogram extends TimeWindowFixedBoundaryHistogram {      // L36

    PrometheusHistogram(Clock clock, DistributionStatisticConfig config, ...) {
        super(clock, DistributionStatisticConfig.builder()
            // effectively never rolls over
            .expiry(Duration.ofDays(1825))       // ★ 5 年 = 不滚动 → 生命周期累计桶
            .bufferLength(1)
            .build()
            .merge(config), true);
```

（`PrometheusTimer.java:120-122` 的注释解释了为什么：不能用滚动直方图的计数，Prometheus 的 `_bucket` 必须是自启动以来的累计值。）

2. **命名与单位**。`PrometheusNamingConvention.name(...)`（L47-78）：snake_case 化 → 计数器/摘要/Gauge 自动补单位后缀（L54-55）→ Timer 补 `_seconds`（L67-75）；并且**新版不再由 Micrometer 补 `_total`**（L59-66 的长注释：新客户端自己追加 `_total`，重复追加会抛 IllegalArgumentException）。tag 的 key 用 `sanitizeLabelName` 清洗（L87-89）。

**旧版对照**：`micrometer-registry-prometheus-simpleclient`（包名 `io.prometheus.client` 系，类名一一对应）整类 @Deprecated（其 `PrometheusMeterRegistry.java:50-61`），只有 text 0.0.4 一种输出、基于旧 `CollectorRegistry`。1.13 之前的教程/书里的代码对应的是它。

## 6.2 OTLP（1.9 起）：push 到 OpenTelemetry 生态

**先说白话**：OTLP 注册表每过 step 时间把内存统计转成 OTLP protobuf 经 HTTP POST 出去，语义上紧跟 OTel：计数语义（DELTA/CUMULATIVE）与直方图风格（显式桶/指数桶）都可配，还能识别 `OTEL_*` 环境变量。

【源码证据】`implementations/micrometer-registry-otlp/src/main/java/io/micrometer/registry/otlp/OtlpMeterRegistry.java`：

```java
public class OtlpMeterRegistry extends PushMeterRegistry {       // L66：push 家族

    private final OtlpHttpMetricExporter otlpHttpMetricExporter; // L85：OTel SDK 导出器
    private final AggregationTemporality aggregationTemporality; // L93：DELTA / CUMULATIVE

    private static OtlpMetricsSender createDefaultSender() {     // L158-160：默认 JDK HttpURLConnection
        return new OtlpHttpMetricsSender(new HttpUrlConnectionSender());
    }
```

```java
protected void publish() {                                       // L199-214
    for (List<Meter> batch : MeterPartition.partition(this, config.batchSize())) {   // 按 batchSize 分批
        OtlpMetricConverter otlpMetricConverter = new OtlpMetricConverter(clock, config.step(), getBaseTimeUnit(),
                config.aggregationTemporality(), config().namingConvention(), config.publishMaxGaugeForHistograms(),
                this.resource);
        otlpMetricConverter.addMeters(batch);
        try {
            Collection<MetricData> metrics = otlpMetricConverter.getAllMetrics();
            if (!metrics.isEmpty()) {
                CompletableResultCode result = this.otlpHttpMetricExporter.export(metrics);
                result.join(config.step().toMillis(), TimeUnit.MILLISECONDS);        // 至多等一个 step
                if (!result.isSuccess()) { logger.warn(...); }
```

**计数语义分派**是理解这个注册表的钥匙（`newCounter` L240-243、`newTimer` L246-254；Gauge 两种模式都用 DefaultGauge L235-237；DELTA 下的 LongTaskTimer 用 DefaultLongTaskTimer L288-292）：

```java
protected Counter newCounter(Meter.Id id) {                      // L240-243
    return isCumulative() ? new OtlpCumulativeCounter(id, this.clock, exemplarSamplerFactory)
            : new OtlpStepCounter(id, this.clock, config.step().toMillis(), exemplarSamplerFactory);   // ★ 复用 core 的 step 内核
}
```

**直方图决策**（L468-484 + `histogramFlavor` L498-512）：配了 SLO 边界的 meter **强制显式桶**（L506-511）；否则按 `OtlpConfig.histogramFlavor()`（默认 `EXPLICIT_BUCKET_HISTOGRAM`，`OtlpConfig.java:250-257`）在 `Delta/CumulativeBase2ExponentialHistogram` 与显式桶之间选择。`OtlpConfig` 的环境变量兼容（与 OTel SDK 对齐）：`url()` 默认 `http://localhost:4318/v1/metrics` 并回退 `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT`（L64-79）、`aggregationTemporality()` 默认 CUMULATIVE 并回退 `OTEL_EXPORTER_OTLP_METRICS_TEMPORALITY_PREFERENCE`（L151-157）、headers 从 `OTEL_EXPORTER_OTLP_HEADERS` 读（L206）。

## 6.3 Dropwizard 桥接型：graphite 与 jmx

Graphite 注册表（`GraphiteMeterRegistry.java:28`）是**反直觉的例子**：它不是 StepMeterRegistry，而是把 Micrometer meter 映射进 Dropwizard 的 `MetricRegistry`，定时发布委托给 Dropwizard 的 `GraphiteReporter`（`start()` 里 `reporter.start(config.step().getSeconds(), TimeUnit.SECONDS)`，L88-92）。JMX 同理（`JmxMeterRegistry.java:27-52`）：桥进 Dropwizard 后由 `JmxReporter` 把每个指标注册成 `metrics:<name>` MBean（domain 默认 `"metrics"`，`JmxConfig.java:35-37`），JMX 客户端来拉——**pull 型**。这展示了门面的另一种扩展路径：不是"从头实现投递"，而是"借既有生态的船"。

## 6.4 其余速览

- **Datadog（step 推送的标准骨架）**：`DatadogMeterRegistry.publish()`（L107-144）= `MeterPartition.partition(this, batchSize)` 分批 → `meter.match(...)` 访问者逐类型拼 JSON（L128-138）→ `httpClient.post(endpoint).withHeader("DD-API-KEY", ...)`（L143-144）。自己写注册表照这个抄即可。
- **HealthMeterRegistry**（`micrometer-registry-health`，1.6.0 起 @Incubating）：`extends SimpleMeterRegistry`（`HealthMeterRegistry.java:55`），按 `ServiceLevelObjective`（如 JVM 堆使用率、磁盘剩余）做健康判定；构造时自动 **deny 所有不属于任何 SLO 的指标**并自动绑定 SLO 依赖的 MeterBinder（javadoc L44-49）——"用过滤器实现最小化"的巧妙应用。不依赖 Spring。
- 其余（influx/elastic/newrelic/cloudwatch2/stackdriver/signalfx/...）都是 5.5 表中的 step 推送变体，差别只在协议与命名约定。

## 6.5 本章小结

- pull（Prometheus：抓取时现做快照，桶必须生命周期累计）与 push（OTLP：分批转换 + join 限等待）是两种完全不同的工程取舍，Micrometer 用"注册表身份"把它们各归其位。
- 后端差异全部吸收在 implementations：命名（_total/_seconds）、桶语义（不滚动的 PrometheusHistogram）、传输（HttpUrlConnection vs HttpSender）、语义（DELTA/CUMULATIVE）。
- 扩展新后端的三条路：继承 StepMeterRegistry/PushMeterRegistry（主流）、桥接 Dropwizard（graphite/jmx）、特化 SimpleMeterRegistry（health）。

---

# 七、Observation API：一次观测，多路信号（micrometer-observation）

## 7.1 先说白话：为什么指标之外还要一个 Observation

指标（metrics）天生**低基数**——`http_server_requests` 只能带 `uri/method/status` 这类有限维度；链路（tracing）天生**高基数**——每个请求一个 traceId/spanId。以前你得起两套 API（Timer 手动埋 + tracer 手动埋），两边的时间点还对不齐。Observation 的答案是：**一次观测（一个 Observation 对象）描述一件事的全过程，挂上高/低基数两组键值，谁关心什么谁来消费**——metrics handler 取低基数打 Timer，tracing handler 全取并生成 span，日志 handler 把 traceId 写进 MDC。它 1.10.0 进 Micrometer 主仓 GA（1.6.2 节实证），血统来自 Spring Cloud Sleuth 的实践。

这个 API 有一个工程前提值得敬佩：**观测必须可以整体关闭且零开销**——所以代码里到处是"快速降级"路径（7.2/7.4）。

## 7.2 生命周期全解：start → context → scope → stop

**先说白话**：一次观测 = `start()` 前放键值、`start()` 通知 handler、（可选）`openScope()` 把自己绑到当前线程、`stop()` 收尾。大多数场景用 `observe(...)`/`scoped(...)` 一步到位。

【源码证据】`micrometer-observation/src/main/java/io/micrometer/observation/Observation.java`（接口 L49，共 1637 行；以下行号均为该文件）：

```java
public interface Observation extends ObservationView {       // L49

    // intentionally anonymous to avoid introducing circular initialization issues
    Observation NOOP = new Observation() {                   // L55-56：NOOP 用匿名类防循环初始化
        ...
        @Override
        public Observation start() { return this; }          // L104
        @Override
        public Observation.@Nullable Context getContext() { return NoopContext.INSTANCE; }   // L109-111
        @Override
        public void stop() { }                               // L114
        @Override
        public Observation.Scope openScope() { return Scope.NOOP; }   // L118-120
```

**创建的快速降级路径**（理解整个 API 的性能设计就看这一段，`createNotStarted` L193-204）：

```java
static <T extends Context> Observation createNotStarted(String name, Supplier<T> contextSupplier,
        @Nullable ObservationRegistry registry) {
    if (registry == null || registry.isNoop()) {             // ① 注册表为空/无 handler → NOOP
        return NoopButScopeHandlingObservation.INSTANCE;
    }
    Context context = contextSupplier.get();                 // ② 才真正创建 Context
    context.setParentFromCurrentObservation(registry);       // ③ 自动认父（当前线程的观测）
    if (!registry.observationConfig().isObservationEnabled(name, context)) {   // ④ Predicate 拦截
        return NoopButScopeHandlingObservation.INSTANCE;     //    禁用但保留 scope 能力
    }
    return new SimpleObservation(name, registry, context);   // ⑤ 真观测
}
```

生命周期方法全集（行号）：`contextualName(String)` L401、`parentObservation(O)` L413、`lowCardinalityKeyValue(s)` L422-448、`highCardinalityKeyValue(s)` L457-483、`isNoop()` L489（`this == NOOP || this == NoopButScopeHandlingObservation.INSTANCE`）、`observationConvention(...)` L502、`error(Throwable)` L509、`event(Event)` L516、`start()` L523、`getContext()` L529、`stop()` L544、`openScope()` L551。一把梭的 `observe(Runnable)`（L566-578）：

```java
default void observe(Runnable runnable) {
    start();
    try (Scope scope = openScope()) {
        runnable.run();
    }
    catch (Throwable error) {
        error(error);
        throw error;
    }
    finally {
        stop();
    }
}
```

变体族：`wrap(Runnable)` L580（把"start+scope+stop"包成可传的 Runnable）、`observeChecked` L598、`observe(Supplier)` L631、`scoped(Runnable)` L774（**不 stop**，只开 scope——用于"外面还有人要 stop"的场景，如 HTTP 服务端框架）、`tryScoped(...)` L842-897（registry 为空时安全跳过）；`observeWithContext` 已 @Deprecated（L707）。约定驱动的工厂 `start(customConvention, defaultConvention, supplier, registry)`（L325-328）内部走 7.6 的约定选择顺序。

`Scope`（L906-1002）实现 `AutoCloseable`：`getCurrentObservation()` L942、`getPreviousObservationScope()` L949（1.10.8 起，恢复链用）、`close()` L959；`reset()`/`makeCurrent()` 已 @Deprecated。`Event`（L1355-1491）：`of(name, contextualName)` L1364、可带 `wallTime`（1.12 起 L1377）与高/低基数 KeyValues（1.18 起 L1392-1412）。

## 7.3 Context：观测的"行李箱"与高/低基数

**先说白话**：Context 是观测数据的载体——名字、错误、父子关系、两组键值（低基数给指标、高基数给链路），外加一个 `ConcurrentHashMap` 让任意框架塞自己的东西（请求/响应对象等）。

【源码证据】同文件 `class Context implements ContextView`（L1010-1347），字段（L1013-1025）：

```java
class Context implements ContextView {

    private final Map<Object, Object> map = new ConcurrentHashMap<>();   // 通用行李箱

    private @Nullable String name;                           // 观测名（≈指标名）
    private @Nullable String contextualName;                 // 语境名（≈span 名）
    private @Nullable Throwable error;
    private @Nullable ObservationView parentObservation;
    private final Map<String, KeyValue> lowCardinalityKeyValues = new ConcurrentHashMap<>();   // → 指标 tag
    private final Map<String, KeyValue> highCardinalityKeyValues = new ConcurrentHashMap<>();  // → trace 属性
```

认父的自动机制（L1084-1091）：

```java
void setParentFromCurrentObservation(ObservationRegistry registry) {
    if (this.parentObservation == null) {
        Observation currentObservation = registry.getCurrentObservation();   // 当前线程正在观测的事
        if (currentObservation != null) {
            setParentObservation(currentObservation);
        }
    }
}
```

读侧是独立的 `ContextView`（L1496-1603，只读方法全集 L1502-1601）；写侧还有 `put/get/getRequired/computeIfAbsent`（L1117-1191）操作通用 map。**没有** Type 枚举与 startWallTime 字段——计时是 metrics handler 的事（7.6）。

## 7.4 ObservationRegistry 与 ThreadLocal 栈

**先说白话**：注册表只做两件事——持有配置（handlers/predicates/conventions/filters 四张表），以及回答"当前线程正在观测什么"。答案存在一个**静态** ThreadLocal 的 Scope 栈里。

【源码证据】`ObservationRegistry.java`（接口 L35；工厂 `create()` L41-43 返回 SimpleObservationRegistry；NOOP L49-74 是匿名类但**复用 SimpleObservationRegistry 的静态方法**读同一个 ThreadLocal——NOOP 也要能看到别的 registry 放下的上下文）；`ObservationConfig` 是**内部类**（L119-227，四张 CopyOnWriteArrayList L121-127；`observationHandler` L134、`observationPredicate` L145、`observationFilter` L156、`observationConvention(GlobalObservationConvention)` L171；约定匹配 `getObservationConvention` L185-193 按 `supportsContext` 找第一个命中的；启用判定 `isObservationEnabled` L202-209 所有 predicate 全过才 true）。

`SimpleObservationRegistry.java`（包私有，75 行，全文核心即"当前观测怎么存"）：

```java
class SimpleObservationRegistry implements ObservationRegistry {     // L28

    private static final ThreadLocal<Observation.@Nullable Scope> localObservationScope = new ThreadLocal<>();  // L31：static！

    private final ObservationConfig observationConfig = new ObservationConfig();   // L32

    static @Nullable Observation _getCurrentObservation() {          // L38-45
        Observation.Scope scope = localObservationScope.get();
        if (scope != null) {
            return scope.getCurrentObservation();
        }
        return null;
    }
    ...
    public boolean isNoop() {                                        // L71-73：没有 handler 就是 noop
        return ObservationRegistry.super.isNoop() || observationConfig().getObservationHandlers().isEmpty();
    }
```

两个关键点：① ThreadLocal 存的是 **Scope 而非 Observation**（Scope 才携带 previous 引用，构成栈）；② **static** ThreadLocal 让 `ObservationRegistry.NOOP` 与跨库代码（如 context-propagation 的 accessor）都能摸到同一个当前上下文。

Scope 的压栈/弹栈在 `SimpleObservation.SimpleScope`（`SimpleObservation.java:262-328`）：

```java
SimpleScope(ObservationRegistry registry, Observation current) {     // L276-281：压栈
    this.registry = registry;
    this.currentObservation = current;
    this.previousObservationScope = registry.getCurrentObservationScope();   // 记住上一个
    this.registry.setCurrentObservationScope(this);
}
...
public void close() {                                                // L286-301：弹栈
    if (currentObservation instanceof SimpleObservation) {
        SimpleObservation observation = (SimpleObservation) currentObservation;
        observation.notifyOnScopeClosed();
    }
    ...
    this.registry.setCurrentObservationScope(previousObservationScope);   // 恢复上一个
}
```

`SimpleObservation` 构造时从 config 收集**匹配此 Context 的 handler** 进 ArrayDeque（L91-100），stop/关闭 scope 时**逆序**通知（L240-255）——handler 列表在观测创建时就固化，中途加 handler 不影响进行中的观测。

## 7.5 Handler / Predicate / Filter / Convention 四件套

**先说白话**：这四个接口就是 Observation 的"扩展点全家福"——handler 消费生命周期事件，predicate 决定观测是否启用，filter 在 stop 前最后改一次 Context，convention 负责按 Context 类型生成名与键值。

【源码证据】`ObservationHandler.java`（接口 L35，回调全集：`onStart` L41、`onError` L48、`onEvent` L56、`onScopeOpened` L63、`onScopeClosed` L70、`onScopeReset` L84（@Deprecated）、`onStop` L91、`supportsContext` L100）。组合器是它的嵌套类型：`CompositeObservationHandler`（L105-113）、**FirstMatching**（L118-223，每个回调只触发第一个 `supportsContext` 命中者，onStart L151-156——给"唯一 tracer"用）与**AllMatching**（L228-334，广播给所有命中者，onStart L261-267——metrics/tracing/logs 并存时用）。

其余角色：

| 角色 | 位置与行号 | 一句话 |
|---|---|---|
| ObservationPredicate | `ObservationPredicate.java`（`BiPredicate<String, Context>`） | 按 name+Context 拦截（Boot 的 `management.observations.enable.*` 落在这） |
| ObservationFilter | `ObservationFilter.java:32` `map(Context)` | stop 前最后修改 Context（`SimpleObservation.stop` L182-184 调用）；Boot 的 `key-values` 落在这 |
| ObservationTextPublisher | `ObservationTextPublisher.java:42-44,82-114` | 把每次回调打成一行的调试 handler（Boot 默认**不装**，八.8.3） |
| ObservationConvention | `ObservationConvention.java`（EMPTY L33、low L39、high L47、supportsContext L57、getName L63、getContextualName L74） | 按 Context 类型出名字与键值；`GlobalObservationConvention`（L24-26）是可注册到 registry 的变体 |

约定选择顺序（`Observation.createNotStarted(customConvention, defaultConvention, ...)` L235-254）：**custom > registry 上注册的 GlobalObservationConvention（按注册序第一个 supportsContext 命中）> default**。

## 7.6 DefaultMeterObservationHandler：Observation 变回指标的那座桥

**先说白话**：观测有了，指标从哪来？这个 handler（住在 **micrometer-core**，observation 模块不依赖指标）把每次观测转成一个 Timer（成功/失败都记、带 `error` 标签）、一个可选的"进行中" LongTaskTimer 和事件 Counter。它只取**低基数**键值——高基数天生不属于指标。

【源码证据】`micrometer-core/src/main/java/io/micrometer/core/instrument/observation/DefaultMeterObservationHandler.java`（类 L54，`implements MeterObservationHandler<Observation.Context>`；接口 `MeterObservationHandler.java:28` @since 1.10.0）：

```java
public void onStart(Observation.Context context) {           // L117-127
    if (includeActiveObservationLongTaskTimer) {             // 1.18 起默认 false（Builder @since 1.18.0）
        LongTaskTimer.Sample longTaskSample = meterRegistry.more()
            .longTaskTimer(context.getName() + ".active", createTags(context))
            .start();
        context.put(LongTaskTimer.Sample.class, longTaskSample);
    }

    Timer.Sample sample = Timer.start(meterRegistry);        // 开始计时
    context.put(Timer.Sample.class, sample);                 // 塞进行李箱，stop 时取
}

public void onStop(Observation.Context context) {            // L132-142
    List<Tag> tags = createTags(context);                    // 只取低基数（L157-163）
    tags.add(Tag.of("error", getErrorValue(context)));       // 无错时 "none"（L152-155）
    Timer.Sample sample = context.getRequired(Timer.Sample.class);
    sample.stop(this.meterRegistry.timer(context.getName(), tags));   // ★ 观测名 = 指标名

    if (includeActiveObservationLongTaskTimer) {
        LongTaskTimer.Sample longTaskSample = context.getRequired(LongTaskTimer.Sample.class);
        longTaskSample.stop();
    }
}
```

`onEvent`（L145-150）为每个事件造 `<observation-name>.<event-name>` Counter。**没有** "spring.observations" 这种神秘指标名——指标名就是观测名（HTTP 场景即 `http.server.requests`，八.8.3）。

## 7.7 @Observed 与 ObservedAspect：注解版观测

【源码证据】`micrometer-observation/src/main/java/io/micrometer/observation/annotation/Observed.java:28-51`（属性 `name()` L37、`contextualName()` L43、`lowCardinalityKeyValues()` L49）；切面 `aop/ObservedAspect.java`（@Aspect L83-84，持有 ObservationRegistry L86；无参构造走 `Observations.getGlobalRegistry()` L105-107——给编译期织入用）：

```java
private @NullUnmarked Object observe(ProceedingJoinPoint pjp, Method method, Observed observed) throws Throwable {  // L152
    Observation observation = ObservedAspectObservationDocumentation.of(pjp, observed, this.registry,
            this.observationConvention);                     // 默认名 "method.observed"（L43）
    ...
    observation.start();
    try (Observation.Scope scope = observation.openScope()) {
        Object result = pjp.proceed();
        if (result != null && CompletionStage.class.isAssignableFrom(method.getReturnType())) {   // L161：异步返回值
            CompletionStage<?> stage = (CompletionStage<?>) result;
            return stage.whenComplete((res, error) -> stopObservation(observation, pjp, res, error)); // 在完成回调里 stop
        }
        stopObservation(observation, pjp, result, null);
        return result;
    }
    catch (Throwable error) {
        stopObservation(observation, pjp, null, error);
        throw error;
    }
}
```

比 @Timed 聪明的一点：识别 `CompletionStage` 返回值，把 stop 推迟到**业务真正完成**的时刻。默认低基数键 `class`/`method`（文档枚举 L77-93）。1.16 又加了 `@ObservationKeyValue` 参数注解（可配 `cardinality = HIGH/LOW`，`aop/Cardinality.java:26-29`）。注意 observation 模块的切面依赖 AspectJ 织入配置（`micrometer-observation/build.gradle:5-7` 的 post-compile-weaving，仅 Java≥17）。

## 7.8 上下文传播：跨线程与响应式的"行李托运"

**先说白话**：ThreadLocal 里的观测在换线程（`@Async`、Reactors、虚拟线程）后会丢。解法是 context-propagation 库（**独立仓库** `micrometer-metrics/context-propagation`，本仓库以 optional 依赖引入 1.2.1）：把"当前观测"打包成 `ContextSnapshot`，在新线程恢复。本仓库出的是那只"钥匙"——`ObservationThreadLocalAccessor`。

【源码证据】`micrometer-observation/src/main/java/io/micrometer/observation/contextpropagation/ObservationThreadLocalAccessor.java`：

```java
public class ObservationThreadLocalAccessor implements ThreadLocalAccessor<Observation> {   // L35
                                                              // ThreadLocalAccessor 来自 io.micrometer:context-propagation
    public static final String KEY = "micrometer.observation";   // L42
    /**
     * The default implementation of {@link ObservationRegistry} that Micrometer provides
     * comes with a static {@link ThreadLocal} instance ...          // L45-52：静态 ThreadLocal 使默认 registry 天然兼容
     */
    private ObservationRegistry observationRegistry = ObservationRegistry.create();

    @Override
    public @Nullable Observation getValue() { return observationRegistry.getCurrentObservation(); }   // L112-114

    @Override
    public void setValue(Observation value) { ... value.openScope(); }   // L117-124：恢复即重新压栈
```

`restore(Observation)`（L175-197）还会校验恢复目标与 `previousObservationScope` 一致（L186-195）——防线程污染。HTTP 头层面的传播（把 traceId 塞进 W3C traceparent 等）是 `transport/Propagator`（内嵌 Setter/Getter L47-62）+ Micrometer Tracing 的活。**TracingObservationHandler 本身不在本仓库**——链路实现在隔壁 `micrometer-metrics/tracing`（桥接 Brave/OpenTelemetry），本仓库只提供 handler 接口约定；`Observations`（`Observations.java:31-106`，1.14 起）则给"没有依赖注入"的环境一个静态全局注册表（内部 `DelegatingObservationRegistry` L67-104 用 AtomicReference 换底）。

## 7.9 micrometer-commons：词表与注解工具

commons 模块（build.gradle 自述 `'Module containing common code'`）是 observation 与 core 共同的地基，全量 39 个文件按职责四组：

| 组 | 类型 | 一句话 |
|---|---|---|
| 词表 | `KeyValue`（L29-35，`NONE_VALUE="none"` L34）、`KeyValues`（L38，按 key 排序去重的不可变集合 L48-53）、`ImmutableKeyValue`/`ValidatedKeyValue`（包私有） | observation 体系的 Tag 等价物 |
| 注解处理 | `AnnotationHandler<T>`（L48，@since 1.11.0）、`ValueResolver`（L29，从对象解析字符串）、`ValueExpressionResolver`（L29，SpEL 等表达式的接口——**实现由 Spring 提供**）、`NoOpValueResolver`、`AnnotationUtils` | @MeterTag/@Observed 的参数注解都靠它 |
| 文档 | `KeyName`（L29） | ObservationDocumentation 生成键名文档 |
| 基建 | `util/internal/logging/InternalLoggerFactory`（L96/L105，slf4j 优先回退 jdk）、`WarnThenDebugLogger`、`StringUtils`、`lang/Nullable`（@Deprecated，让位 JSpecify） | 零依赖日志门面 |

## 7.10 TCK：让"Observation 兼容"可测试

`micrometer-observation-test`（包名是 `tck`）给第三方 registry/handler 实现者一套契约测试：`ObservationRegistryCompatibilityKit`（L50，约 1000 行，覆盖 scope/handler/predicate 行为契约）、`ObservationRegistryAssert`（L34，`doesNotHaveAnyRemainingCurrentObservation` L75 起——专查 scope 泄漏）、`ObservationContextAssert`（L47，`hasNameEqualTo` L74、`hasKeyValuesCount` L169、`hasOnlyKeys` L185 等）、`TestObservationRegistry`（L37，可清空的测试注册表）。指标侧对称的 TCK 在 `micrometer-test`（`MeterRegistryCompatibilityKit` + `MeterRegistryAssert/TimerAssert/CounterAssert/GaugeAssert`；注意类名是 `MeterRegistryAssert`，没有 SimpleMeterRegistryAssert；`ObservationRegistryAssert` 在 observation-test 而非这里）。

## 7.11 本章小结

- Observation = 一次观测 + 两组基数分离的键值 + Scope 栈；`createNotStarted` 的三级降级（null/noop registry → predicate 拦截 → 真观测）是全 API 零开销承诺的落点。
- 当前观测存在 static ThreadLocal 的 Scope 栈上：压栈记住 previous、弹栈恢复；handler 列表在创建时固化、通知逆序。
- 四件套（Handler/Predicate/Filter/Convention）+ FirstMatching/AllMatching 组合器 = 全部扩展点；metrics 消费靠 `DefaultMeterObservationHandler`（观测名即指标名、只取低基数、自动 error 标签）。
- 传播分两层：线程内/跨线程靠 context-propagation（accessor 模式），跨进程靠 Micrometer Tracing（独立仓库）。

---

# 八、Spring Boot 如何接线（Boot 4.2.0-SNAPSHOT 实证）

## 8.1 先说白话：Boot 4 把 Micrometer 接线拆成了两个模块

Boot 4.0 起 actuator 的 metrics/observation 部分拆成独立模块（本仓库 `settings.gradle:156-162` 的 `module:spring-boot-micrometer-metrics` / `module:spring-boot-micrometer-observation`），包名从 `org.springframework.boot.actuate.autoconfigure.metrics.*` 迁到 `org.springframework.boot.micrometer.metrics.*`。自动配置的核心是**两个 BeanPostProcessor**：一个改造每个 `MeterRegistry` bean（挂 customizer/filter/binder），一个改造 `ObservationRegistry` bean（挂 predicate/convention/handler/customizer）。老版本类名的对应关系：`SimpleMeterRegistryAutoConfiguration` → `SimpleMetricsExportAutoConfiguration`；`ObservationRegistryAutoConfiguration` → `ObservationAutoConfiguration`。

版本证据：`D:\code\3rd\spring-boot\gradle.properties:1` `version=4.2.0-SNAPSHOT`；Micrometer 依赖 `platform/spring-boot-dependencies/build.gradle:1688-1696` `library("Micrometer", "1.18.0-M2")`（Tracing 为 1.8.0-M2，L1729）。

## 8.2 指标侧：MetricsAutoConfiguration 与 MeterRegistryPostProcessor

【源码证据】`module/spring-boot-micrometer-metrics/src/main/java/org/springframework/boot/micrometer/metrics/autoconfigure/MetricsAutoConfiguration.java`：

```java
@AutoConfiguration(before = CompositeMeterRegistryAutoConfiguration.class)
@ConditionalOnClass(Timed.class)
@EnableConfigurationProperties(MetricsProperties.class)
public final class MetricsAutoConfiguration {                // L49-68，@since 4.0.0

    @Bean
    @ConditionalOnMissingBean
    Clock micrometerClock() {                                // L54-58：全体系共用一个 Clock bean
        return Clock.SYSTEM;
    }

    @Bean
    static MeterRegistryPostProcessor meterRegistryPostProcessor(...) {   // L60-68：static！BPP 尽早注册
```

它还顺手把观测的 metrics 侧装好（L81-93）：`metricsObservationHandlerGroup()`（`ObservationHandlerGroup.of(MeterObservationHandler.class)`，Boot 4 的 handler 分组机制，8.3）与 `DefaultMeterObservationHandler` bean——注意 fallback：`meterRegistryProvider.getIfAvailable(() -> new CompositeMeterRegistry(clock))`，并按 `management.observations.include-active-long-task-timer` 决定 LTT 开关。

**注册表从哪来**（三条兜底链）：① 用户/其他 starter 注册的任意 `MeterRegistry` bean；② 多个 registry 并存时 `CompositeMeterRegistryConfiguration`（`CompositeMeterRegistryConfiguration.java:39-47`）造 `@Primary AutoConfiguredCompositeMeterRegistry`（条件类 `MultipleNonPrimaryMeterRegistriesCondition` L49-65：仅在"多个非 primary registry"时生效）；③ 什么都没有时 `SimpleMetricsExportAutoConfiguration`（`SimpleMetricsExportAutoConfiguration.java:42-58`）兜底——关键条件 `@ConditionalOnMissingBean(MeterRegistry.class)` + `@ConditionalOnEnabledMetricsExport("simple")`（可用 `management.simple.metrics.export.enabled=false` 关掉）。

**PostProcessor 的定制流程**（`MeterRegistryPostProcessor.java`，实现 `BeanPostProcessor, SmartInitializingSingleton`）：

```java
private void postProcessMeterRegistry(MeterRegistry meterRegistry) {   // L99-109
    this.meterRegistryCloser.getObject().track(meterRegistry);         // Boot4 新增：关闭时清理
    // Customizers must be applied before binders, as they may add custom tags or
    // alter timer or summary configuration.
    applyCustomizers(meterRegistry);       // ① 你的 MeterRegistryCustomizer（LambdaSafe 泛型安全回调，L111-117）
    applyFilters(meterRegistry);           // ② 容器里的 MeterFilter bean（对 composite 只保留 maximumAllowableTags 系，L119-127）
    addToGlobalRegistryIfNecessary(meterRegistry);   // ③ management.metrics.use-global-registry（默认 true）→ Metrics.globalRegistry，L129-134
    if (isBindable(meterRegistry)) {
        applyBinders(meterRegistry);       // ④ MeterBinder bean（JvmGcMetrics 等）；singleton 阶段未到则 deferBinding 推迟到
    }                                     //    afterSingletonsInstantiated()（L59-61/92-97/158-168）
}
```

注释里那句 "Customizers must be applied before binders" 是个实用考点：customizer 改 common tags/分布配置必须发生在 binder 注册仪表之前。配置载体 `MetricsProperties`（`management.metrics.*`，L39）：`use-global-registry` L47、`enable.*` map L53、`tags`（common tags，经 `PropertiesMeterFilter` 生效，L57）L58、`distribution.percentiles-histogram` map L181/190、`web.client.max-uri-tags=100` L114-121（防 URI 基数爆炸的默认熔断）。

## 8.3 观测侧：ObservationAutoConfiguration 与 Handler 分组

【源码证据】`module/spring-boot-micrometer-observation/src/main/java/org/springframework/boot/micrometer/observation/autoconfigure/ObservationAutoConfiguration.java`：

```java
@AutoConfiguration
@ConditionalOnClass(ObservationRegistry.class)
@EnableConfigurationProperties(ObservationProperties.class)
public final class ObservationAutoConfiguration {            // L50-77

    @Bean
    static ObservationRegistryPostProcessor observationRegistryPostProcessor(...) {   // L54-62
    @Bean
    @ConditionalOnMissingBean
    ObservationRegistry observationRegistry() {              // L72-75：ObservationRegistry.create() → SimpleObservationRegistry
        return ObservationRegistry.create();
    }

    @Bean
    @Order(0)
    PropertiesObservationFilterPredicate propertiesObservationFilter(ObservationProperties properties) {  // L77+
```

`ObservationRegistryPostProcessor`（L67-73）对每个 ObservationRegistry bean 调 `ObservationRegistryConfigurer.configure(registry)`（`ObservationRegistryConfigurer.java:68-99`），顺序固定：**predicates → global conventions → handlers → filters → customizers**。其中 handler 注册走 Boot 4 新机制 `ObservationHandlerGroups`（`registerHandlers` L76-80）：容器里的 `ObservationHandler` bean 只有被某个 group（如 metrics 模块声明的 `MeterObservationHandler` 组）认领才注册——解决了 metrics/observation/tracing 模块间 handler 装配互相等待的死锁。

`PropertiesObservationFilterPredicate`（L35-81）一人分饰两角（同时 implements `ObservationFilter, ObservationPredicate`）：`management.observations.enable.<prefix>=false` 走 `test(name, context)` 拦截；`management.observations.key-values` 走 `map(context)` 以**低基数键值**注入每个观测（`createCommonKeyValuesFilter` L75-81）。`ObservationTextPublisher` 全仓零引用——调试要自己注册 bean。`ObservationProperties`（`management.observations.*`，L33）：`key-values` L41、`enable` L47、`conventions=SemanticConventions.MICROMETER` L52（Boot 4 新增）、`http.client/server.requests.name` L109/L136（即 `http.client.requests` / `http.server.requests`）。

## 8.4 切面装配：一个开关管三个切面

【源码证据】`MetricsAspectsAutoConfiguration.java:47-70`（metrics 模块）与 `ObservationAutoConfiguration` 嵌套类 `ObservedAspectConfiguration`（L85-106，observation 模块）：

```java
@ConditionalOnBooleanProperty("management.observations.annotations.enabled")   // ★ 默认 false！
@ConditionalOnClass({ MeterRegistry.class, Advice.class })
...
    @Bean
    @ConditionalOnMissingBean
    TimedAspect timedAspect(MeterRegistry registry, ObjectProvider<MeterTagAnnotationHandler> meterTagAnnotationHandler) {
```

`TimedAspect`/`CountedAspect`/`ObservedAspect` 三个切面共用总开关 `management.observations.annotations.enabled`（**默认关闭**），且都是 `@ConditionalOnMissingBean`——想自定义切点就自己声明 bean 把它顶掉。@MeterTag 的 SpEL 依赖 `ValueExpressionResolver` bean（Boot 默认提供 `SpelValueExpressionResolver`，`ObservationAutoConfiguration` L79-83；tag handler 的装配条件是 `@ConditionalOnBean(ValueExpressionResolver.class)`，L72-90）。

## 8.5 MetricsEndpoint：/actuator/metrics 的实现

【源码证据】`MetricsEndpoint.java:50-57`（`@Endpoint(id = "metrics")`）。`listNames()`（L59-73）递归展开 composite 收集名字（`collectNames` L71-79）；`metric(name, tag)`（L79-92）用 Search API 过滤（`findFirstMatchingMeters` L108-123：composite 时逐个子 registry `find(name).tags(tags).meters()` 取第一个命中的），样本按 `Statistic` 归并、可用的 tag 维度去掉已过滤项返回。装配条件 `@ConditionalOnAvailableEndpoint(MetricsEndpoint.class)`（`MetricsEndpointAutoConfiguration.java:37-47`）。

自动配置清单（`module/spring-boot-micrometer-metrics/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` L1-4 + L19 + L22）：`CompositeMeterRegistryAutoConfiguration`、`MetricsAspectsAutoConfiguration`、`MetricsAutoConfiguration`、`MetricsEndpointAutoConfiguration`、`export.simple.SimpleMetricsExportAutoConfiguration`、`jvm.JvmMetricsAutoConfiguration`（另 L5-21 为各导出后端）；observation 模块仅两条（`ObservationAutoConfiguration`、`ScheduledTasksObservationAutoConfiguration`，L1-2）。HTTP 观测的自动配置（类名级别）：`RestClientObservationAutoConfiguration`/`RestTemplateObservationAutoConfiguration`/`WebClientObservationAutoConfiguration`/`WebMvcObservationAutoConfiguration`/`WebFluxObservationAutoConfiguration`。

## 8.6 常用配置项 → 源码落点对照表

| 配置 | 落点（Micrometer 概念） |
|---|---|
| `management.metrics.tags.<k>=<v>` | `MeterFilter.commonTags`（PropertiesMeterFilter）→ 三.3.4 |
| `management.metrics.enable.<prefix>=false` | `MeterFilter.deny` 系（PropertiesMeterFilter 的 mapFilter）→ 三.3.2 |
| `management.metrics.distribution.percentiles-histogram.<name>=true` | `DistributionStatisticConfig.percentileHistogram` → 四.4.2 |
| `management.metrics.distribution.slo.<name>=1ms,10ms,...` | `serviceLevelObjectives` → 四.4.2 |
| `management.metrics.web.client.max-uri-tags=100` | `MeterFilter.maximumAllowableTags` → 三.3.4 |
| `management.observations.key-values.<k>=<v>` | ObservationFilter 注入低基数键值 → 七.7.5 |
| `management.observations.enable.<prefix>=false` | ObservationPredicate 拦截 → 七.7.2 |
| `management.observations.annotations.enabled=true` | Timed/Counted/Observed 三切面总开关（默认 false）→ 八.8.4 |
| `management.observations.include-active-long-task-timer=true` | DefaultMeterObservationHandler 的 LTT（1.18 起默认关）→ 七.7.6 |

## 8.7 本章小结

- Boot 的全部接线 = 两个 PostProcessor：`MeterRegistryPostProcessor`（customizer→filter→global→binder，顺序有讲究）与 `ObservationRegistryPostProcessor`（predicate→convention→handlers→filters→customizer）。
- 注册表三级兜底（用户 bean > 多注册表自动 composite > Simple），Clock 是公共 bean，观测的 metrics handler 挂在 metrics 模块（HandlerGroup 分组装配）。
- 三个注解切面默认关闭（`management.observations.annotations.enabled`），生产启用前先想清楚方法级指标的成本。

---

# 九、贯通视图：三条时间线看懂 Micrometer 全貌

## 9.1 时间线一：`registry.counter("api.requests", "uri", "/a").increment()` 的一生

```
① counter(name, tags)         → RequiredSearch → registry.counter(id)               [三.3.2]
② getOrCreateMeter            → preFilterIdToMeterMap 命中? 直接复用（重复注册告警）  [三.3.2]
  未命中:
③ mapId                       → 逐个 MeterFilter.map()（commonTags/ignoreTags 改写 Id）
④ accept(mappedId)            → DENY → NoopCounter（业务代码无感）                  [三.3.7]
⑤ filter.configure 循环       → 改写 DistributionStatisticConfig → merge(defaultHistogramConfig)
⑥ meterSupplier.create        → 子类 newCounter：CumulativeCounter / StepCounter（语义分叉）[五.5.1]
⑦ onMeterAdded 回调           → Composite 在此把新 meter 同步给子 registry            [三.3.6]
【此后每次 increment】
⑧ LongAdder.increment（cumulative）或 sumThenReset 前的累加（step）
⑨ 后端读取：Prometheus 抓取时 MicrometerCollector.collect() 现做快照            [六.6.1]
              或 StepValue.poll() 在 step 边界发布上一个完整步的差值             [五.5.2]
```

## 9.2 时间线二：一次 HTTP 请求的观测（Boot + Observation）

```
① WebMvc 过滤器起观测        → Observation.createNotStarted("http.server.requests", ctx, registry)
   降级判定: registry 无 handler / ObservationPredicate 拦截 → NoopButScopeHandling（仍可传播）[七.7.2]
② lowCardinalityKeyValue(uri=/a, method=GET, status=200 / outcome)               [七.7.3]
③ highCardinalityKeyValue(requestId=..., traceId 由 tracing handler 补)          [七.7.3]
④ observation.start()        → AllMatching 广播: DefaultMeterObservationHandler.onStart
                               → Timer.Sample 塞进 Context                        [七.7.6]
                               → TracingObservationHandler.onStart（micrometer-tracing 仓库）→ 生成 span
⑤ openScope()               → static ThreadLocal 压栈，previous 引用构成父子链     [七.7.4]
⑥ 业务执行中下游发起调用     → RestClient 拦截器 getCurrentObservation() → 创建子观测
                               → setParentFromCurrentObservation 自动认父          [七.7.3]
⑦ 响应式/线程切换            → context-propagation 快照 → 新线程 setValue 恢复 scope [七.7.8]
⑧ observation.stop()        → handler 逆序 onStop:
                               metrics: sample.stop(timer(name, tags + error=none)) [七.7.6]
                               tracing: span 结束上报
⑨ /actuator/metrics/http.server.requests?uri=/a 可查（Search API）               [八.8.5]
```

## 9.3 时间线三：一次抓取（pull）与一次推送（push）

```
【pull：Prometheus】
① Prometheus 服务器 GET /actuator/prometheus
② PrometheusMeterRegistry.scrape(contentType) → PrometheusRegistry.scrape()      [六.6.1]
③ 逐 MicrometerCollector.collect() → MetricSnapshots（此刻一致性快照）
④ ExpositionFormats 按 Accept 选 writer（text 0.0.4 / OpenMetrics）输出
   — 计数值是自启动累计的；_bucket 是"永不滚动"的累计桶                           [六.6.1]
   — 速率/分位数由 Prometheus 服务端 rate()/histogram_quantile() 计算
【push：OTLP / Datadog】
⑤ PushMeterRegistry 调度器到点（随机错峰起步）→ publishingSemaphore.tryAcquire()  [五.5.4]
⑥ StepMeterRegistry 的 poller 已在 step 边界调过一遍 count() 完成换步             [五.5.3]
⑦ publish(): MeterPartition.partition(batchSize) 分批 → 转协议（OTLP protobuf / Datadog JSON）[六.6.2]
⑧ HTTP POST（HttpUrlConnection / HttpSender）；join 至多一个 step，失败仅告警     [六.6.2]
⑨ finally 释放信号量；close 时补发最后一步                                        [五.5.4]
```

## 9.4 从源码中提炼的设计模式视角

| 模式 | 在 Micrometer 里的化身 |
|---|---|
| 门面（Facade） | MeterRegistry + Meter 家族屏蔽 22 个后端；Observation 屏蔽 metrics/tracing/logs |
| 模板方法（Template Method） | MeterRegistry 的七步注册管线留 newXXX 给子类；AbstractTimer.record(final) 留 recordNonNegative；PushMeterRegistry 留 publish() |
| 访问者（Visitor） | `Meter.match/use` 按仪表类型分派（Datadog publish 逐类型写 JSON，六.6.4） |
| 组合（Composite） | CompositeMeterRegistry 复制 meter 到子注册表；ObservationHandler 的 AllMatching/FirstMatching |
| 策略（Strategy） | NamingConvention（后端命名）、ObservationConvention（上下文出键值）、CountingMode（计数语义） |
| 观察者（Observer） | MeterRegistry.Config 的 onMeterAdded/onMeterRemoved；ObservationHandler 的生命周期回调 |
| 建造者（Builder） | Counter/Timer/DistributionStatisticConfig 的 builder 链；MeterProvider 是"工厂化的 builder" |
| 适配器（Adapter） | MeterBinder 把第三方状态变仪表；DropwizardMeterRegistry 把 micrometer 变 Dropwizard |

---

# 十、附录

## 10.1 关键接口速查表

| 类型 | 关键成员 | 一句话 |
|---|---|---|
| Meter | getId() / measure() / Type / match / use | 一切仪表的根 |
| Meter.Id | name/tags/type；getConventionName/Tags；equals 只比 name+tags | 仪表身份 |
| Counter/Gauge/Timer/DistributionSummary/LongTaskTimer | 见二.2.3 表 | 八种仪表选型 |
| MeterRegistry（抽象类） | config() / find / get / getMeters / more() / close | 七步注册管线 + CHM 存储 |
| MeterRegistry.Config | meterFilter / commonTags / namingConvention / onMeterAdded / clock | 顺序敏感的控制面板 |
| MeterFilter | map / accept→DENY,NEUTRAL,ACCEPT / configure；maximumAllowableTags | 闸门三钩子 |
| DistributionStatisticConfig | percentiles / percentileHistogram / serviceLevelObjectives / expiry / bufferLength / merge | 分布行为配置卡 |
| HistogramSupport | takeSnapshot() → HistogramSnapshot | 一致性读数 |
| StepValue / StepDouble / StepTuple2 | rollCount(CAS 步号) / sumThenReset / poll | step 差值内核 |
| StepMeterRegistry | start()（双调度器）/ pollMetersToRollover / getInitialDelay | step 注册表骨架 |
| PushMeterRegistry | publish()（抽象）/ start（去重+错峰）/ close（补发） | 推送纪律 |
| Observation | start / stop / error / event / low/highCardinalityKeyValue / openScope / observe / scoped | 观测生命周期 |
| Observation.Context(ContextView) | name / contextualName / error / parent / low/highCardinalityKeyValues / map | 观测行李箱 |
| ObservationRegistry（ObservationConfig 内部类） | getCurrentObservation / getCurrentObservationScope / observationConfig | static ThreadLocal 栈 |
| ObservationHandler | onStart/onError/onEvent/onScopeOpened/onScopeClosed/onStop/supportsContext | 观测扩展点 |
| ObservationPredicate / ObservationFilter / ObservationConvention | test / map / supportsContext+keyValues | 启用判定 / stop 前改写 / 键值策略 |
| DefaultMeterObservationHandler（core） | onStart/onStop：Timer.Sample + error 标签（+可选 LTT） | 观测→指标的桥 |
| NamingConvention | name / tagKey / tagValue（core 6 常量 + 22 个后端实现） | 命名最后一公里 |
| MeterBinder | bindTo(registry) | 第三方适配器 |

## 10.2 初学者学习路线（动手向）

1. **跑通最小闭环**：`new SimpleMeterRegistry()`，注册 Counter/Timer/Gauge，`Search.in(registry).name(...).meter()` 读回来；再换 `new LoggingMeterRegistry()` 看输出。
2. **理解过滤器**：给 registry 加 `MeterFilter.deny(nameStartsWith("jvm"))`、`MeterFilter.maximumAllowableTags(...)`，观察 Noop 与告警。
3. **读懂直方图**：给 Timer 配 `percentilesHistogram(true)` 与 `serviceLevelObjectives(...)`，`takeSnapshot()` 打印 `CountAtBucket`；对照 `TimeWindowFixedBoundaryHistogram` 源码走一遍二分。
4. **体会 step**：`SimpleConfig` 设 `mode=STEP, step=10s`，隔 15 秒读两次 `count()`，解释为什么读到的差值是 10 秒窗口的。
5. **接 Prometheus**：本地起 PrometheusMeterRegistry + `HTTPServer`（或 Boot 的 `/actuator/prometheus`），配一条 scrape job，观察 `_total/_bucket/_max` 三个序列与 `rate()`。
6. **玩 Observation**：`Observation.start` + 自定义 `ObservationHandler`（打印回调顺序），再装 `ObservationTextPublisher`；用 `ObservationRegistryAssert.doesNotHaveAnyRemainingCurrentObservation()` 抓一次忘关 scope 的 bug。
7. **对 Boot 收口**：打开 `management.observations.annotations.enabled=true`，给一个 service 方法加 `@Observed`，在 `/actuator/metrics/method.observed` 验证指标与 `error` 标签。

## 10.3 源码阅读入口清单（25 个关键文件）

均相对仓库根 `micrometer-core/src/main/java/io/micrometer/core/instrument/`（observation/commons/实现模块另行标注）：

| # | 文件 | 看什么 |
|---|---|---|
| 1 | Meter.java | Meter/Type/Id/MeterProvider |
| 2 | Counter.java / Gauge.java / Timer.java / DistributionSummary.java / LongTaskTimer.java | 仪表接口与 Sample |
| 3 | Tag.java / Tags.java | 不可变标签 |
| 4 | MeterRegistry.java | 管线 getOrCreateMeter / Config / More |
| 5 | config/MeterFilter.java + config/MeterFilterReply.java | 闸门 |
| 6 | config/NamingConvention.java | 命名常量 |
| 7 | search/Search.java | 检索 DSL |
| 8 | composite/CompositeMeterRegistry.java + composite/AbstractCompositeMeter.java | 组合与 noop 降级 |
| 9 | Metrics.java | 全局门面 |
| 10 | simple/SimpleMeterRegistry.java + simple/SimpleConfig.java + simple/CountingMode.java | 语义分叉 |
| 11 | cumulative/CumulativeTimer.java | 三本账 |
| 12 | AbstractTimer.java | record 模板 + 停顿补偿 + defaultHistogram 选型 |
| 13 | distribution/DistributionStatisticConfig.java | 配置卡与 merge |
| 14 | distribution/TimeWindowSum.java / TimeWindowMax.java | 环形窗口 |
| 15 | distribution/AbstractTimeWindowHistogram.java + TimeWindowPercentileHistogram.java | HdrHistogram 窗口 |
| 16 | distribution/TimeWindowFixedBoundaryHistogram.java + FixedBoundaryHistogram.java | SLO 桶与二分 |
| 17 | step/StepValue.java + StepDouble.java + StepTuple2.java + StepTimer.java | 差值内核 |
| 18 | step/StepMeterRegistry.java | 双调度器 |
| 19 | push/PushMeterRegistry.java + push/PushRegistryConfig.java | 推送纪律 |
| 20 | binder/MeterBinder.java + binder/jvm/JvmGcMetrics.java | 适配器模式 |
| 21 | aop/TimedAspect.java + aop/CountedAspect.java + aop/MeterTag.java | 注解切面 |
| 22 | observation 模块：`Observation.java` / `ObservationRegistry.java` / `SimpleObservationRegistry.java` / `SimpleObservation.java` / `ObservationHandler.java` / `aop/ObservedAspect.java` / `contextpropagation/ObservationThreadLocalAccessor.java` | 观测全套 |
| 23 | commons 模块：`KeyValue.java` / `KeyValues.java` / `annotation/AnnotationHandler.java` | 词表与注解工具 |
| 24 | core 的 `instrument/observation/DefaultMeterObservationHandler.java` | 观测→指标 |
| 25 | implementations：prometheusmetrics/PrometheusMeterRegistry.java、registry/otlp/OtlpMeterRegistry.java、datadog/DatadogMeterRegistry.java | 三种投递范式 |

## 结语

Micrometer 的源码读下来，最值得带走的不是某个类名，而是三层"克制"：**接口层克制**（八种仪表 + 一个注册表，API 面积小到可以背下来）、**统计层克制**（时间窗轮换 + 桶化，让任何后端都不必为内存焦虑）、**观测层克制**（三级降级 + 基数分离，让"全链路观测"的默认成本为零）。Spring Boot 把它选作观测底座不是偶然——你可以不同意它每一个统计取舍（比如客户端百分位的内存开销），但你很难否认：这套门面把"埋点一次、后端随选、观测统一"三个承诺都兑现成了代码。

（完）
