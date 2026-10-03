# OpenTelemetry 深度指南（小白友好）

> 从观测性分裂历史 → OTel 如何一统三大信号 → SpringCloud 迁移落地的完整手册。

---

## 目录

- [一、OpenTelemetry 是什么](#一opentelemetry-是什么)
- [二、诞生背景：观测性的两次分裂](#二诞生背景观测性的两次分裂)
  - [2.1 第一次分裂：三个公司，三个世界](#21-第一次分裂三个公司三个世界)
  - [2.2 第二次分裂：OpenTracing vs OpenCensus 两军对垒](#22-第二次分裂opentracing-vs-opencensus-两军对垒)
  - [2.3 合并：OTel 的诞生与成长时间线](#23-合并otel-的诞生与成长时间线)
- [三、观测性的三大支柱（基础概念](#三观测性的三大支柱基础概念)
  - [3.1 Metrics（指标）](#31-metrics指标)
  - [3.2 Logs（日志）](#32-logs日志)
  - [3.3 Traces（分布式链路追踪）](#33-traces分布式链路追踪)
  - [3.4 三大支柱的关系与 Exemplars 关联](#34-三大支柱的关系与-exemplars-关联)
- [四、OTel 的统一架构总览](#四otel-的统一架构总览)
- [五、统一数据模型（OTLP）](#五统一数据模型otlp)
  - [5.1 Resource：服务身份元数据](#51-resource服务身份元数据)
  - [5.2 Traces 数据模型（Trace/Link/Event）](#52-traces-数据模型tracelinkevent)
  - [5.3 Metrics 数据模型](#53-metrics-数据模型)
  - [5.4 Logs 数据模型](#54-logs-数据模型)
- [六、统一语义约定（Semantic Conventions）](#六统一语义约定semantic-conventions)
- [七、OTel API（接口层）](#七otel-api接口层)
  - [7.1 为什么 API 与 SDK 必须分离](#71-为什么-api-与-sdk-必须分离)
  - [7.2 Tracer / Meter / Logger Provider](#72-tracer--meter--logger-provider)
  - [7.3 Context & Propagation（上下文传播）](#73-context--propagation上下文传播)
- [八、OTel SDK（实现层）](#八otel-sdk实现层)
  - [8.1 SDK 组件：Tracer/Meter/Logger Provider 提供器](#81-sdk-组件tracermeterlogger-provider-提供器)
  - [8.2 Sampler（采样器）](#82-sampler采样器)
  - [8.3 Processor（处理器）](#83-processor处理器)
  - [8.4 Exporter（导出器）](#84-exporter导出器)
  - [8.5 Resource Detector（资源探测器）](#85-resource-detector资源探测器)
- [九、OTel Collector（采集路由层）](#九otel-collector采集路由层)
  - [9.1 为什么要有 Collector](#91-为什么要有-collector)
  - [9.2 三种部署模式：Agent / Gateway / Sidecar](#92-三种部署模式agent--gateway--sidecar)
  - [9.3 Collector 三大核心架构：Pipeline = Receivers + Processors + Exporters](#93-collector-三大核心架构pipeline--receivers--processors--exporters)
  - [9.4 Connectors（连接器）](#94-connectors连接器)
  - [9.5 一个真实 K8s 部署示例](#95-一个真实-k8s-部署示例)
- [十、零代码接入：Instrumentation](#十零代码接入instrumentation)
  - [10.1 Java SpringBoot 零代码方案（JavaAgent）](#101-java-springboot-零代码方案javaagent)
  - [10.2 Go eBPF 自动探测（无侵入零代码）](#102-go-ebpf-自动探测无侵入零代码)
  - [10.3 自动 vs 手动埋点权衡](#103-自动-vs-手动埋点权衡)
- [十一、OTel 与你原来的老方案对比与迁移](#十一otel-与你原来的老方案对比与迁移)
  - [11.1 和 SpringCloud Sleuth + Zipkin 迁移](#111-和-springcloud-sleuth--zipkin-迁移)
  - [11.2 和 Prometheus + Micrometer 的关系](#112-和-prometheus--micrometer-的关系)
  - [11.3 和 ELK / Fluentd 日志的关系](#113-和-elk--fluentd-日志的关系)
  - [11.4 后端选型：Jaeger vs Tempo vs Zipkin](#114-后端选型jaeger-vs-tempo-vs-zipkin)
- [十二、SpringCloud 迁移 OTel 落地实践](#十二springcloud-迁移-otel-落地实践)
  - [12.1 一图看懂替换关系](#121-一图看懂替换关系)
  - [12.2 Java 微服务接 OTel 的 3 步走](#122-java-微服务接-otel-的-3-步走)
  - [12.3 常见坑与最佳实践](#123-常见坑与最佳实践)
- [十三、总结：OTel 解决了什么、没解决什么](#十三总结otel-解决了什么没解决什么)

---

## 一、OpenTelemetry 是什么

**OpenTelemetry（简称 OTel）是 CNCF 的一个**观测性「工具链 + 标准规范**项目，不生产数据的目标是做「在应用和SDK、格式——**的工具链，**你能采集、处理、导出发送）观测三大支柱数据（Metrics/Traces/Logs），**并把它们统一关联起来，一次埋点可换任意后端**。

关键 4 点理解它的本质：

| 点 | 说明 |
|---|---|
| ① | **厂商中立** | CNCF 毕业项目（2023），Google、Datadog、New Relic、阿里、腾讯全部支持 |
| ② | **是**「**集合**而非后端** | OTel 本身不存数据、不做 UI、不含告警。它是「**、SDK + 采集器」，数据最后发给 Jaeger / Prometheus / Tempo / Loki / 商业 SaaS |
| ③ | **三信号统一** | 以前 Metrics 一套 SDK、Traces 一套 SDK、Logs 一套 Fluentd——OTel 一次搞定，且三大信号互相关联（TraceID 串起来） |
| ④ | **无侵入为目标** | Java SpringBoot / Node / Go 主流框架有零代码自动埋点，老应用不改代码就能全链路采集 |

> 🎯 对你 SpringCloud 迁移 K8s 来说：**以前你用的 SpringCloud Sleuth + Zipkin + Micrometer + ELK 这 4 套工具，现在 OTel 可以一次替代/统一前 3 套的埋点层**，后面存储层可以保留你熟悉的 Prometheus / Grafana / ES。

---

## 二、诞生背景：观测性的两次分裂

理解 OTel 为什么被发明，要从两次「分裂」。

### 2.1 第一次分裂：三个公司，三个世界

2010 年前，Google 发布了三篇论文奠定了现代观测性：

| 公司 | 论文/产品 | 解决什么 | 开源自研/开源工具 | 分裂 |
| Google | **Dapper 2010**（分布式追踪论文 | 跨服务调用链追踪 | Google 内部未开源 |  |
| Google | **Borgmon 2015**（基于指标监控） | 指标 + 告警 | Prometheus（SoundCloud 借鉴后开源 |  |
|  |  |  |  |  |
| Meta | **Canopy（Facebook 内部 | 分布式追踪系统 | 未开源，后演变成 Zipkin（Twitter） |  |
| Twitter | 2012 开源 Zipkin | 分布式追踪 | Zipkin（Uber 开源 |  |
| Uber | 2015 年参考 Google Dapper 写了 **Jaeger** | 分布式追踪 | 开源 Jaeger → CNCF 毕业 |  |

结果：2017 年你要做微服务观测性，每家工具选型非常痛苦：

```
应用 A：选了 Uber Jaeger（Jaeger SDK
应用 B：选了 Twitter Zipkin（Zipkin SDK
应用 C：选了 SkyWalking（阿里（国产
= 三套 SDK 不兼容，Trace 跨团队做不成，你不能串不起来！
```

### 2.2 第二次分裂：OpenTracing vs OpenCensus 两军对垒

社区看不下去了，2016 年 CNCF 先搞了 **OpenTracing 规范**：

| 阵营 | 发起方 | 核心想法 | 优点 | 缺点 |
|------|--------|----------|------|------|
| 阵营 1：**OpenTracing** | CNCF + 很多公司（Uber/Yelp/Datadog 发起 | **只做 API 规范**，统一 Trace 和 多后端（Zipkin / Jaeger 都实现 | 厂商中立，兼容多后端 | 只定义 Tracer 接口，**没有官方 SDK**：Provider：SDK 生态 |
| 阵营 2：**OpenCensus** | Google + Microsoft 2018 联合开源 | **API + SDK 一把梭**，除了 Trace **还有 Metrics | 有官方 SDK / 出 Google 亲儿子 | 只绑定自家 生态广，Metrics 自动埋点丰富（gRPC / HTTP / gRPC Stats |

两阵营用户都有大量用户互相打架：
- OpenTracing 没官方 SDK：Metrcs 弱；
- OpenCensus 是 Google 生态和 MS 生态 绑定；
- 两边都不兼容：API API 规范不互认 → OpenTracing 的 Instrumentation（自动埋点）。**
结果：社区只能选一个站队，两边社区两边社区继续内耗。

### 2.3 合并：OTel 的诞生成长时间线

```
2019.05  历史性握手：OpenTracing + OpenCensus 两项目 TSC 联合宣布合并项目
          合并为 OpenTelemetry（OTel
2019.10  OTel 第一个项目仓库创立（0.0.0 版本
2021       OTel Traces 稳定（v1.0 GA
2022       Metrics 稳定（v1.0）；Logs Logs1.0）
2023       从 CNCF 孵化 -> 毕业（Graduated）
           GitHub Star 数仅次于 K8s，CNCF 历史
```

一句话：OTel 是**观测性界的 K8s：社区停止内耗统一标准**。

---

## 三、观测性三大支柱

小白先必须讲三大支柱的基础概念。

### 3.1 Metrics（指标）

**可聚合数值：可聚合的**可聚合数值**可计数的**数字：

```
服务 QPS：  订单 5xx 错误率：  1.2%
响应 P99 延迟：  320ms
JVM Heap 使用率：76%
CPU 核：4 核 65%
订单每分钟新订单数：1283 单
```

| 项 | 说明 |
|---|---|
| **特点** | 数字，能做趋势、聚合、数学运算（rate / topk / histogram_quantile P99）|
| **存储** | Prometheus / VictoriaMetrics / Thanos / Mimir |
| **用途** | 告警（P99 > 500ms →报警钉钉告警
| **OTel 对应** | Meter API |  |

### 3.2 Logs（日志）

应用每行输出的**文本 + 元数据**：

```json
{
  "time": "2025-10-02T13:22:33Z",
  "level": "ERROR",
  "msg": "创建订单扣库存",
  "trace_id": "abc123...",  ← 三大支柱关联的关键！
  "exception": "java.lang.NullPointerException: ..."
}
```

| 项 | 说明 |
|---|---|
| **特点** | 事件、信息最全，排查具体事件文本文本**具体某一行具体错误原因** |
| **用途** | 堆栈详情 |
| **存储** | Elasticsearch / Loki / Kafka |
| **OTel 对应** | Logger API |

### 3.3 Traces（链路追踪）

**一次请求跨 跨跨跨全链路调用经过：用户点：

```
[Client /api/order/create 总耗时 320ms
 ├─ order-service  40ms  （本地 JVM 127.0.0.1 校验用户
 ├─ user-service   80ms  远程 Feign 调用 HTTP 调用 HTTP 调用 DB 查询用户
 ├─ stock-service 120ms  Dubbo 扣库存
 ├─ pay-service    60ms  支付
 └─ kafka-topic  20ms  异步发消息
```

| 项 | 说明 |
|---|---|
| **核心概念 1** | **Trace**：一次完整请求，ID=一次，全局唯一，一次，全链路 TraceID（abc123xxx |
| **核心概念 2** | **Span**：Trace 中一个**步骤**，每个服务中每个调用都一个一一个，Span 有开始/结束，Span 有父子关系（有 Parent SpanID|
| **核心概念 3 ** |Span Context：父子关系|
| **存储** | Jaeger / Tempo / SkyWalking / Zipkin |
| **OTel 对应** | Tracer API |

### 3.4 三大支柱的关系与 Exemplars 关联

以前三大支柱是**孤岛**：Prometheus 报警 P99 延迟了但不知道哪一次请求慢，慢在哪里？找不着。
OTel 统一了三大支柱的关键：**同一份请求同一条链路都用同一个 TraceID 全部串起来，并通过 Exemplars（范例
+ Span Links 关联：**：

```
用户在 metrics 指标（Histogram 分布）
   │  │
   │  └─► Exemplar （example：这个 metric P99 >500ms 时，点一下，跳转到对应的 到 Trace 慢的那几条慢 Trace
```

---

## 四、OTel 的统一架构总览

一张总图总览（从上到下分层：

```
                 你的业务代码（Java SpringBoot / Go / Node）
  ────────────────────────────────────────────────────────
                  OTel API（接口层，无 Vendor）                ① 接口定义层接口：Tracer/Meter/Logger
  ────────────────────────────────────────────────────────
                  OTel SDK（SDK 实现层）            ② 具体实现：采样、处理、导出
  ────────────────────────────────────────────────────────
       ┌───────────────┴──────────────────┐
       │ OTel Instrumentation 自动埋点       │ OTel Collector 采集器         ③ 采集路由层
       │（Java Agent / Go 代码生成）  │（DaemonSet / Gateway）
       └───────────────┴──────────────────┘
  ────────────────────────────────────────────────────────
                    OTLP 协议（统一格式传输 gRPC / HTTP Protobuf
  ────────────────────────────────────────────────────────
       Prometheus Jaeger Tempo Loki ES Kafka Datadog
       存储后端（Prometheus    Grafana 全家桶 / 商业后端（厂商存储、展示、展示 OTel **都不做。
```

分层 5 层总览：

| 层 | 层名 | 作用 | 不做什么 |
|---|---|---|---|
| ① | **OTel API** | 定义统一接口：Tracer / Meter / Logger | 空接口，啥实际啥实现 |
| ② | **OTel SDK** | API 的具体实现：采样、批处理、导出 | 不提供存储后端 |
| ③ | **OTel Instrumentation** | 各大框架自动埋点（SpringMVC/Feign/JDBC/Kafka/Redis | 你自己不用写代码 |
| ④ | **OTel Collector** | 数据接收 → 处理（采样/过滤/批量 → 导出 | 不持久化 |
| ⑤ | **后端（OTLP 协议** | 统一 gRPC + HTTP Protobuf 协议 | 不定义存储 |
| ⑥ | **存储 & 分析 & 展示（后端**（不是 OTel 范围！ |||

---

## 五、统一数据模型（OTLP）

OTLP = **OpenTelemetry Protocol 的「的**。
一句话：不管是什么格式（gRPC / HTTP Protobuf 格式**。
OTel 内部统一三大信号：从 SDK / Collector 之间全链路全链路全链路传输不用经过 N 种格式。

### 5.1 Resource：服务身份元数据

所有三大信号都必须携带的**公共根元信息：

```yaml
resource:
  service.name: order-service            ← 最重要：服务名（必须
  service.version: v1.2.3              ← 版本号
  service.instance.id: pod-abc123-xyz         ← 实例 ID
  k8s.namespace.name: prod            ← K8s 命名空间
  k8s.pod.name: order-abc123           ← Pod 名
  host.name: worker-node-1             ← 主机名
  telemetry.sdk.name: opentelemetry     ← 用的 OTel SDK
  telemetry.sdk.version: 1.28.0         ← SDK 版本
  deployment.environment: production     ← 环境标签（区分 dev/test/prod
```

→ Jaeger/Prometheus 全靠 `service.name` 聚合。

### 5.2 Traces 数据模型

| 概念 | 说明 | 字段举例 |
|---|---|---|
| **Trace** | 全局 TraceID 16 字节随机 | `4bf92f3577b34da6a3ce929d0e0e4736` |
| **Span** | 一个调用步骤 | 字段： |
| | • SpanID | Span 8 字节 | `00f067aa0ba902b7` |
| | • ParentSpanID | 父 Span | 父调用方 |
| | • Name | 操作名 | `HTTP GET /order/{id}` / `MySQL Query SELECT` |
| | • Kind 种类 | Server（服务端|/Client 调 Server 端/Producer（客户端/MQ 生产方/ | Server：Server Server  |
| | • StartTime / EndTimeUnixNano | 起/1970.1.1 | 起/止纳秒 |
| | • Attributes | 属性键值对 | `http.method=GET`、db.system=mysql` |
| | • Events | 带时间戳事件（打 log | `event:exception、message:stacktrace |
| | • Status | Ok/Error/Unset | `Error + Desc` |
| | • Links | 跨 Trace 链接异步调用 | Trace Trace 关联（发 Kafka 消费 Trace 连接 |

### 5.3 Metrics 数据模型（6 种 Metric 6 种仪器类型）

| 仪器类型 | 用途 | 举例 |
|---|---|---|
| **Counter**（计数器） | 单调递增只增不减 | `requests_total、errors_total |
| **UpDownCounter** | 可增可减 | `queue_size`、`heap_used` |
| **Histogram**（直方图） | 分布 P50/P90/P99分布 | `http_request_duration（P99 |
| **Gauge（瞬时值）** | 瞬时值快照 | `cpu_usage`、`jvm_memory_used` |
| **ExponentialHistogram**（指数直方图） | 动态桶，Prometheus | 专用 |
| **Summary**（摘要） | 分位值 | `P999 / quantile 0.99（历史兼容 |

### 5.4 Logs 数据模型

```
Logs Body（字段：
- **Timestamp**：时间戳
- **TraceID / SpanID**：关联到 Trace Span（关键关联
- **SeverityNumber**：数字严重程度：1-24（TRACE DEBUG INFO WARN ERROR FATAL）
- **Body**：日志内容本身（可以是 JSON 字符串或
- **Attributes**：属性（`thread.name= http.status_code=200 等
- **Resource**：继承 Resource（服务名、Pod、K8s 信息（上一节）

---

## 六、统一语义约定（Semantic Conventions）

OTel 的「**最伟大创新点除了 OTLP 之外最大的是**：**统一标签名！

以前：
```
Prometheus 指标名：http_server_requests_seconds
Zipkin Tag: http.method=GET / http.url= / status=200
Sleuth MDC：
SkyWalking：  tag: http.request.method
= GET 各干各的，不同工具有 N 种命名法
→ Grafana Dashboard 写 各干各的
```

OTel 统一规定：**所有 HTTP 调用必须有这些这些属性名：**

| 域 | 标准属性名 | 示例值 |
|---|---|---|
| **HTTP** | `http.request.method` `http.response.status_code` `url.path` `url.scheme` `server.address` `client.address` |
| **DB 数据库** | `db.system`（mysql/postgresql）`db.name` `db.operation（SELECT/INSERT）`db.statement` |
| **消息队列** | `messaging.system`（kafka/rabbitmq）`messaging.destination.name` `messaging.kafka.client_id |
| **RPC/gRPC** | `rpc.system`（grpc/dubbo）`rpc.service` `rpc.method` `rpc.grpc.status_code` |
| **异常** | `exception.type` `exception.message` `exception.stacktrace` |
| **K8s** | `k8s.pod.name` `k8s.namespace.name` `k8s.node.name` `k8s.deployment.name` |
| **公有云** | `cloud.provider`（aws/azure/gcp/aliyun/tencent_cloud | `cloud.region` `cloud.account.id` |
| **FAAS | `faas.name` `faas.trigger |

→ 结果：**不管你用什么语言、什么框架、什么后端→Dashboard 一套就能所有语言所有服务所有框架的 HTTP 请求 Dashboard 就能跨语言跨框架！这就是语义约定 Semantic Conventions！

---

## 七、OTel API（接口层）

### 7.1 为什么 API 与 SDK 必须分离

**核心设计哲学：**

> 业务代码**只能依赖 OTel API **，绝不直接依赖 SDK 实现类
> 「面向接口编程，切换 Vendor Neutral Vendor Neutral（厂商中立）

```java
// ✅ 正确：业务代码 import 接口 Tracer tracer = GlobalOpenTelemetry.getTracer("com.example");
// ❌ 错误：import io.opentelemetry.sdk.trace.SdkTracerProvider （禁止！
```

→ API 是接口就是 OpenTracing 的遗志：你写的代码永远不用变，底层可以从 Jaeger SDK 换到 OTel SDK，甚至换商业厂商 Agent。

### 7.2 Tracer / Meter / Logger Provider

三大 Provider 是 API 的入口：

| Provider | 作用 | 生成 |
|---|---|---|
| **TracerProvider** | 创建 Tracer（ | 生成 Span、启动 Span |
| **MeterProvider** | 创建 Meter | 创建 Counter/Histogram/Gauge |
| **LoggerProvider** | 创建 Logger | 打结构化日志（带 TraceID 注入 |

### 7.3 Context & Propagation（上下文传播）

最核心的黑魔法部分：**跨服务跨进程 TraceID 怎么带着走？**

→ 把 TraceParent 放在 HTTP Header / gRPC Metadata / Kafka Message Header 里。

OTel 统一定义了传播格式（Propagator）：

| Propagator | 说明 | 默认 W3C Trace-Context 标准（推荐）|
|---|---|---|
| **W3C TraceContext** | `traceparent: 00-{TraceID}-{SpanID}-{采样位}` 响应头 | traceparent+tracestate  |
| **B3 Multi** | 老 Zipkin 兼容 `X-B3-TraceId`、X-B3-SpanId  | 兼容老系统用（Jaeger 老 |
| **B3 Single** |  B3: {TraceId}-{SpanId}-{采样}-{ParentSpanId} |  单个 HTTP Header 一条 |
| **Jaeger** | uber-trace-id | Jaeger 老格式 |

→ 推荐统一 W3C TraceContext（国际标准 / 所有语言所有厂商默认新系统全默认。

传播例子 HTTP 调用：
```
order-service -> stock-service
Header 自动由 OTel Feign 拦截器自动加：
traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
```

---

## 八、OTel SDK（实现层）

### 8.1 SDK 组件

SDK Provider 具体实现组件：TracerProvider / MeterProvider LoggerProvider

### 8.2 Sampler（采样器）

100% 采样太耗钱？Sampler 决策哪条 Span 采集还是丢弃：
```
Trace 太多
├─  AlwaysOn  全采
├─  AlwaysOff  不采集（测试）
├─  TraceIDRatioBased  按 TraceID 百分比（0.01 = 1%。。
└─  ParentBased  根据父Span采样决策（父采，子也采 90% 场景默认默认
```

### 8.3 Processor（处理器）

对 Span 做处理再导出：

| Processor | 作用 |
|---|---|
| **BatchSpanProcessor** | 攒一批一批 Span 打包后批量后批量导出（默认 |
| **SimpleSpanProcessor** | 一条一条立刻导出（调试）|
| **Tail Sampling Processor** | 尾部采样（Collector 里做，按错误Span 才采（生产常用 |

### 8.4 Exporter（导出器）

把数据往哪发：OTLP（推荐，Jaeger / Prometheus / Zipkin / Console（控制台

### 8.5 Resource Detector

自动探测 K8s 环境自动探测 Resource Service：K8sDetector、云环境探测（EKS/ACK/EKS/阿里云 ACK / GKE / GKE / AKS 自动注入云元数据。

---

## 九、OTel Collector

### 9.1 为什么要有 Collector

三大理由：

1. **不用一个应用不用每台机器装 N 个 Exporter 写 N 份 Exporter。
2. **集中配置采样策略，改采样、规则
3. **缓冲 + 批量 + 流量削峰，后端挂了，Collector 暂存数据不丢。
4. 做 Tail-based 尾部采样（全 Trace 全 Trace，才知道这这Span 慢不慢，集中统一过滤采样。

### 9.2 三种部署模式：

```
模式 1：Agent  ┌────────── K8s DaemonSet（每个 Node 一个 Pod
模式 2：Gateway 模式 ┌────────── 集群中央 StatefulSet 一组 Gateway
模式 3：Sidecar ┌────────── 每个业务 Pod 一个 一个 Sidecar（Istio 那种
```

### 9.3 Collector = 核心 Pipeline = Receivers + Processors + Exporters

```yaml
receivers:                  # ① 接收：哪儿：otlp: protocols: gRPC http:
  otlp: protocols: grpc http prometheus:   config: scrape_configs:
    - job_name: 'k8s' kubelet
processors:                 # ②处理：
  batch:   timeout: 10s  send_batch_size: 8192
  memory_limiter:   check_interval: 1s   limit_mib: 2048
  k8sattributes: extract_metadata: extract  Pod 元数据
  tail_sampling:  policies: - name: rate
exporters:                  # ③ 导出：到哪：
  otlp/jaeger: endpoint: jaeger:4317
  prometheusremotewrite: endpoint: prometheus:9090/api/v1/write
  loki: endpoint: loki:3100/loki/api/v1/push
service: pipelines: traces: receivers: [otlp] processors: [batch memory_limiter] exporters: [otlp/jaeger]
```

### 9.4 Connectors（连接器）(2023 新功能：把三大信号的 Pipeline 之间互转：

```
Traces 慢 Span → Span 里生成 metrics.count 请求数 → 自动算成功率
→ 生成
→ Span metrics（RED 指标：Span /错误率 / 延迟 P99）
```

### 9.5 一个真实 K8s 部署示例：

```yaml
# otel-collector-daemonset.yaml（DaemonSet：每个 Node 一个 Pod 收集 Node + Gateway：
├── 1. 接收 OTLP 4317/gRPC 4318/HTTP
├── 2. k8sattributes Processor：自动识别 Pod 信息注入标签 Namespace、Deployment
├── 3. batch Processor：10s 一批次导出
├── 4. Exporter 到中央 Gateway（或直接 Jaeger Prometheus Loki
```

---

## 十、零代码接入 Instrumentation

### 10.1 Java SpringBoot 零代码方案（JavaAgent对你 SpringBoot：

```
你的 SpringBoot Jar
    │
    │
    │ 启动命令 java -javaagent:opentelemetry-javaagent.jar
    │  （-javaagent 字节码增强
    │  （启动加载时编织字节码
    └───► 自动拦截：
        ├── SpringMVC @Controller/@RestController 入口 HTTP 入口
        ├──  RestTemplate/WebClient
        ├──  Feign / OpenFeign 客户端
        ├──  JDBC / Spring Data JPA
        ├──  Kafka /  Lettuce
        ├──  Spring @Async、Spring @Scheduled
        ├──  Spring  动态数据源、
        ├──  @Scheduled 线程池
        └──  日志自动注入 TraceID MDC（日志：自动注入到 logback / log4j2
```

只用加 **行参数即可接入。

### 10.2 Go eBPF 自动探测（OpenTelemetry Go 纯 Go 应用、生产场景）

Go 应用不能用 JavaAgent。社区做不到。现在：
```
OpenTelemetry Go 官方主流程：
1.1 手动埋 → 代码里 http.Handler 拦截器 Wrap 包
2.2 → OTel Go eBPF → 自动 → 自动在 eBPF →
3. 自动 gRPC / net/http 拦截，业务代码 0 侵入

### 10.3 自动 vs 手动埋点权衡

| 接入方式 | 代码改动 | 覆盖度 | 性能开销 | 定制能力 |
|---|---|---|---|---|
| ✅ **JavaAgent（首选）| 0 | 95% 框架全覆盖 | 5-15% CPU、额外 64-128MB | 属性名 Semantic Conventions（够用 |
| ⚠️ | 手动埋点 | 全 | 定制业务属性（订单号、用户 ID）自定义事件 | 2% |

最佳实践：自动埋点打底 + 业务关键手动补业务关键自定义 Span 手动补埋点。

---

## 十一、OTel 与老方案对比迁移

### 11.1 SpringCloud Sleuth + Zipkin → OTel + Jaeger/Tempo

| 原方案 | 老 SpringCloud Sleuth | OTel 新方案 | 是否需要改代码 |
|---|---|---|---|
| 依赖 | spring-cloud-starter-sleuth + zipkin | opentelemetry-javaagent.jar | 0 代码，去掉 maven 依赖即可 |
| TraceID/SpanID 传播 | MDC（日志里 | 同样 MDC 自动注入 | TraceID=相同格式 W3C | B3 可 |
| Feign 调用拦截 |  Sleuth 自己的拦截器→ OTel→ SpringMVC/Feign 自动 Instrumentation→ OTel | 自己拦截，不用 |
| 配置 | spring.sleuth.sampler.* → OTel | OTEL_TRACES_SAMPLER=traceidratio | 环境变量切换 |

### 11.2 Prometheus + Micrometer 关系

Micrometer 是 SpringBoot 2/3 用的指标门面。OTel 和 Micrometer 可以**兼容：
```
现在：代码写 Micrometer（你业务代码代码代码
          ↓  ↓
      Micrometer 提供给 OTel
      ↓  Micrometer  ↓
   Micrometer OTel Bridge（OTel
```

### 11.3 日志
ELK（Fluentd → OTel

| 组件 | 老 |
|---|---|
| ELK采集 | Fluentd Filebeat → OTel 可以替代 | 日志采集 OTel Log Appender Logback Appender + OTLP OTLP 自动带 TraceID | 结构好 自动 OTLP TraceID TraceID TraceID |
| Fluent Bit 采集 → OTel 日志 OTLP 到 ES /  OTLP → Loki | 存储后端：|

### 11.4 后端选型

| 后端 | 适合场景 |
|---|---|
| Jaeger | 毕业 CNCD Grafana + UI 最好，中小规模团队 | Grafana 全家桶 Grafana 深度集成最好 | Grafana Labs Loki → Grafana  Tempo |
| Zipkin | 最小可用、最小 | 轻量、测试、开发 | SkyWalking | 国内流行 老系统保留 | 国内容易上手 |

---

## 十二、SpringCloud 迁移 OTel 落地 3 步走

### 12.1 一图看懂替换关系

```
Spring Cloud 旧栈                        OTel 新栈
────────────┬───────────────────────────────────────
Sleuth                         OpenTelemetry Java Agent（替换
    │                                   │
    ├─ 追踪         →        OTLP / Jaeger
    ├─ Metrics Micrometer  + Prometheus / OTLP Meters Registry OTLP OTLP
    └─  日志        OTel Logback Appender → OTLP → Loki / ES
Zipkin Server                    OTel Collector（DaemonSet）
                                          │
                    Jaeger / Prometheus Loki Grafana

### 12.2 Java 微服务 OTel 的 3 步走：

**Step 1：** 单服务单个微服务先跑通
```yaml
# 1. 下载 JavaAgent 放到镜像里或挂载：
COPY opentelemetry-javaagent.jar /app/agent.jar

# 2. K8s Deployment 环境变量：
env:
  - name: JAVA_TOOL_OPTIONS
    value: "-javaagent:/app/agent.jar
  - name: OTEL_SERVICE_NAME
    value: order-service
  - name: OTEL_EXPORTER_OTLP_ENDPOINT
    value: http://otel-collector:4317
  - name: OTEL_RESOURCE_ATTRIBUTES
    value: "service.name=order-service,service.version=1.0.0,deployment.environment=prod"
  - name: OTEL_TRACES_SAMPLER
    value: "traceidratio"
```

Step 2：Collector 部署 + Collector
Step 3：Grafana 面板 Jaeger Prometheus Loki 配置完 → 看数据

### 12.3 常见坑与最佳实践

1. JavaAgent 开销：Pod 内存请求 + 256MB，CPU 极限 + 0.2 核 requests 预留
2. 采样：生产 10%-30% 头部采样，生产集中 Tail 尾部采样集中
3. K8s K8s Downward API 注入注入 Pod 注入 Resource 自动 service
4. TraceID 用户 ID 用户 链路用 Baggage 跨服务链路 TraceContext

---

## 十三、OTel 解决了什么 / 没解决什么

✅ 解决了：
1. 三大信号：统一接口、统一格式、统一语义、统一上下文传播。
2. 厂商锁定，一次埋点可换后端
3. 自动埋点、主流框架零代码。
❌ 没解决的：
1. 没给存储、UI、告警 → 自己上 Prometheus/Jaeger/Tempo/Loki/Grafana
2. 不替你做 Business KPIs（业务指标订单成功率）→ 仍需手动定义好
3. 业务语义约定写的很好：OTel 不直接解决了
4. APM 全链路拓扑图、拓扑问题定位慢查询面板自己做

但总体总体上 OTel 已经是 95% 的基础。
