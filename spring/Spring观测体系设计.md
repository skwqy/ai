# Spring 观测体系架构设计文档

> 版本：v1.0  
> 日期：2026-10-01  
> 适用技术栈：SpringBoot 4.1.1 + JDK 25 + Oracle 19c  
> 服务规模：3 个微服务

---

## 目录

1. [架构设计总览](#1-架构设计总览)
   - 1.5 [方案 D（用户提案）深度评测与对比](#15-方案-d用户提案深度评测与对比)
   - 1.6 [最终推荐](#16-最终推荐)
   - 1.7 [微服务间调用专项设计](#17-微服务间调用专项设计)
     - 1.7.1 三服务调用拓扑（含协议标注）
     - 1.7.2 服务调用协议选型对比
     - 1.7.3 服务发现 + 负载均衡方案
     - 1.7.4 容错设计：超时/重试/熔断/限流
     - 1.7.5 微服务间 Trace 透传（Spring HTTP Service 完整代码 + Feign/gRPC/Dubbo/WebClient 兜底拦截器）
       - 1.7.2.1 OpenFeign → Spring HTTP Service 10 分钟迁移速查表
     - 1.7.6 服务间调用专属指标 & SLA
     - 1.7.7 典型微服务调用故障排查流程
2. [调用链跟踪设计](#2-调用链跟踪设计)
3. [关键指标采集设计](#3-关键指标采集设计)
4. [日志采集设计](#4-日志采集设计)
5. [Arthas 在线诊断集成](#5-arthas-在线诊断集成)
6. [SpringBoot 4.x 集成最佳实践](#6-springboot-4x-集成最佳实践)
7. [部署架构与容量规划](#7-部署架构与容量规划)
8. [风险与应对措施](#8-风险与应对措施)

---

## 1. 架构设计总览

### 1.1 观测体系三大支柱

现代可观测性（Observability）体系建立在三大支柱之上：

| 支柱 | 核心问题 | 典型场景 |
|------|----------|----------|
| **Metrics（指标）** | 「系统是否正常？」 | QPS 突增、响应时间变长、错误率升高 |
| **Logging（日志）** | 「发生了什么？」 | 异常堆栈、业务逻辑追踪、审计记录 |
| **Tracing（调用链）** | 「问题出在哪个环节？」 | 跨服务慢调用、依赖故障定位、瓶颈分析 |

Arthas 作为第四维能力，提供**在线诊断**的即时排查能力。

### 1.2 推荐整体架构（方案 A：OpenTelemetry 全家桶）

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         三个 SpringBoot 微服务                           │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐                               │
│  │ ServiceA │  │ ServiceB │  │ ServiceC │  ← Micrometer + OTLP Exporter │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘                               │
│       │             │             │                                     │
│       └─────────────┴──────┬──────┘                                     │
│                            │                                            │
└────────────────────────────┼────────────────────────────────────────────┘
                             │ OTLP/gRPC
            ┌────────────────┴────────────────┐
            │         OpenTelemetry           │
            │         Collector              │  ← 接收、处理、导出
            └──────┬──────────┬──────────────┘
                   │          │
          ┌────────▼──┐  ┌───▼─────────┐  ┌──────────────┐
          │  Prometheus│  │   Loki      │  │   Tempo      │
          │  (指标)    │  │   (日志)    │  │   (链路)     │
          └─────┬─────┘  └─────┬───────┘  └──────┬───────┘
                │              │                  │
          ┌─────▼──────────────▼──────────────────▼───────┐
          │                   Grafana                      │  ← 统一可视化
          └───────────────────────────────────────────────┘
                              │
                    ┌─────────▼─────────┐
                    │   Arthas Tunnel   │  ← 在线诊断服务端
                    └─────────┬─────────┘
                              │ WebSocket
                    ┌─────────▼─────────┐
                    │  Arthas Dashboard  │
                    └───────────────────┘
```

### 1.3 方案对比：三种主流观测体系

| 维度 | **方案 A：OTel + Grafana 全家桶** | **方案 B：SkyWalking 全家桶** | **方案 C：Sleuth+Zipkin + ELK + Prometheus** |
|------|-----------------------------------|-------------------------------|----------------------------------------------|
| **标准化** | ⭐⭐⭐⭐⭐ W3C TraceContext + OTLP 开放标准 | ⭐⭐⭐ 自有协议，兼容 OTLP | ⭐⭐⭐ B3/Zipkin 协议，偏 Spring 生态 |
| **厂商锁定** | 极低，可自由切换后端 | 高，深度绑定 SkyWalking OAP | 中，各组件可替换但集成复杂 |
| **SpringBoot 4.x 兼容性** | ⭐⭐⭐⭐⭐ Micrometer 官方支持 | ⭐⭐⭐⭐ 需确认 skywalking-agent 支持 JDK25 | ⭐⭐⭐ Sleuth 已废弃（SpringBoot 3.x 后停止维护） |
| **探针侵入性** | Java Agent 或 SDK 二选一 | 必须 Java Agent，字节码增强 | Agent + 依赖 |
| **性能损耗** | 5%~10% | 10%~15% | 8%~12% |
| **Oracle 监控** | 需额外配置 Oracle Exporter | 自带 Oracle 插件 | 需额外配置 |
| **日志关联 Trace** | 通过 MDC 自动关联 trace_id | 自动关联 | 需手动配置 MDC |
| **学习曲线** | 中等（概念多但标准统一） | 低（开箱即用） | 高（多个组件独立配置） |
| **运维复杂度** | 中（Collector + 3 个存储） | 低（单 OAP + ES） | 高（5+ 组件协同） |
| **社区活跃度** | ⭐⭐⭐⭐⭐ CNCF 毕业项目 | ⭐⭐⭐⭐ Apache 顶级 | ⭐⭐ 组件各自维护 |
| **长期演进** | 最佳选择，生态统一 | 稳定，国内用户多 | 不推荐，Sleuth 已停更 |
| **成本估算** | 中 | 中（ES 成本较高） | 中高（组件多） |

---

### 1.5 方案 D（用户提案）深度评测与对比

> 用户提案架构：
> - **日志**：Filebeat → Kafka → Logstash → Elasticsearch → Kibana
> - **指标**：Prometheus → ？ → Grafana
> - **链路**：OTel Java Agent → OTel Collector → Kafka → Jaeger Agent → ES → Jaeger

#### 1.5.1 方案 D 总体架构图

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        三个 SpringBoot 微服务                             │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐                               │
│  │ ServiceA │  │ ServiceB │  │ ServiceC │  ← OTel JavaAgent(字节码注入)  │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  + 本地日志文件输出            │
│       │             │             │                                     │
│       │  OTLP/gRPC  │             │ 本地写文件 (tail -F)                  │
│       └──────┬──────┘             │                                     │
└──────────────┼────────────────────┼─────────────────────────────────────┘
               │                    │
               ▼                    ▼
    ┌──────────────────┐   ┌─────────────────┐
    │ OTel Collector   │   │    Filebeat     │  ← 每台主机/每Pod部署一个
    │ (Trace 采样处理) │   │  (日志文件采集)  │
    └───────┬──────────┘   └────────┬────────┘
            │  Kafka Producer        │ Kafka Producer
            ▼                       ▼
    ┌──────────────────────────────────────────────────────────────┐
    │                    Apache Kafka 集群                         │
    │  Topic: otel-traces  |  Topic: app-logs  |  Topic: app-metrics│
    └──────┬──────────────────────────────────────┬────────────────┘
           │                                      │
           ▼                                      ▼
  ┌──────────────────┐                  ┌──────────────────┐
  │   Jaeger Agent   │ ◄── 疑义！       │   Logstash       │ ← 消费、过滤、格式
  │  (已废弃组件?)   │                  │  (Grok 解析等)    │
  └────────┬─────────┘                  └────────┬─────────┘
           │                                     │
           └────────────┬────────────────────────┘
                        ▼
              ┌─────────────────────┐
              │   Elasticsearch     │ ← 单存储：日志 + Trace（索引分离）
              │   (3 主 + N 数据)   │
              └───────┬───────┬─────┘
                      │       │
              ┌───────▼───┐ ┌─▼─────────┐
              │   Jaeger  │ │  Kibana   │  ← 两套 UI 不互通！
              │    UI     │ │   UI      │
              └───────────┘ └───────────┘

  ┌──────────────────────────────────────────────────────────────────┐
  │       Prometheus (独立拉取，未接入 Kafka/Collector)                │
  │           │                                                       │
  │           ▼                                                       │
  │       Grafana  │  ← 第三套 UI！与 Jaeger UI / Kibana 割裂        │
  └──────────────────────────────────────────────────────────────────┘
```

#### 1.5.2 方案 D 分链路详细评价

##### 🅐 日志链路：Filebeat → Kafka → Logstash → ES → Kibana

| 评估项 | 评分 | 详细说明 |
|--------|------|---------|
| **成熟度** | ⭐⭐⭐⭐⭐ | ELK 经典架构，2014 年至今广泛应用，文档/坑位极其丰富 |
| **全文检索能力** | ⭐⭐⭐⭐⭐ | ES 倒排索引极强，Oracle SQL 模糊搜索、异常堆栈关键字检索秒级响应 |
| **解耦能力（Kafka）** | ⭐⭐⭐⭐⭐ | Kafka 削峰填谷，流量洪峰不丢日志；可消费到多个下游（审计/数仓） |
| **组件数量** | ⭐⭐ | 5 组件（FB + Kafka + LS + ES + KB），每一层都要 HA，运维压力大 |
| **成本（存储）** | ⭐⭐ | SSD 倒排索引存储，288GB/天日志需约 1.5T SSD × 副本 = **3~5T SSD**，成本是 Loki 对象存储方案的 **5~10 倍** |
| **Logstash Grok 性能** | ⭐⭐ | Ruby 引擎正则解析 CPU 消耗大；规则写错会单点瓶颈；**建议用 Fluent Bit / Fluentd 代替** |
| **与 Trace 关联** | ⭐⭐ | 靠 traceId 字段做 Kibana 手动搜索，**无法从 Trace UI 一键跳日志** |
| **SpringBoot 4.x 集成** | ⭐⭐⭐⭐ | 纯日志文件解耦，无兼容性问题；但 Filebeat Sidecar 注入需要 K8s DaemonSet |

**致命隐患：**
```
⚠️  日志数据写入 ES 的模板（Index Template）若配置不当：
    - date 类型误匹配为 text，导致时间范围查询全表扫描 → ES 集群雪崩
    - 未设置 ILM（Index Lifecycle Management），历史索引未轮转 → 磁盘爆仓
    - message 字段未 keyword 子字段，精确匹配 impossible
    → 需要资深 ES 运维人员长期维护
```

##### 🅑 指标链路：Prometheus → ？ → Grafana

用户方案的指标链路是**不完整的**，中间缺失关键组件。补齐方案通常有两种：

| 补齐方式 | 说明 | 评估 |
|---------|------|------|
| **D-i：Prometheus 直接 pull** | Service 暴露 `/actuator/prometheus`，Prometheus 配置 `scrape_configs` 轮询 | ✅ 最简单、推荐；但用户方案没写出来，属于「遗漏」 |
| **D-ii：Prometheus Remote-Write 到 Kafka** | 另加 Prometheus → Kafka → Thanos/VM → Grafana，支持长期存储 | ❌ 复杂度过高，3 个微服务完全没必要 |

**指标链路的关键缺失项（用户方案中未体现）：**

1. ❌ **缺失 Exemplar 支持**：无法从 Prometheus 异常点 → Jaeger Trace，这是三支柱联动的核心
2. ❌ **缺失统一 Tags**：Prometheus metrics tags、Kibana 日志字段、Jaeger Span attributes 需要**全局约定统一 label（app/env/pod/region）**，否则三个 UI 数据无法关联
3. ❌ **缺失 Oracle Exporter**：Oracle 19c 指标（表空间、会话、锁、等待事件）在用户方案中完全没提

##### 🅒 调用链：OTel JavaAgent → Collector → Kafka → Jaeger Agent → ES → Jaeger

```
⚠️  最大硬伤：链路中出现了「Jaeger Agent」—— 这是一个已经被 Jaeger 官方废弃的组件！

Jaeger 架构演进时间线：
  2020 Jaeger 1.20 之前：  Agent（sidecar） → Collector → DB     ← 用户方案
  2022 Jaeger 1.40+：      移除 Agent，直接 OTel Collector → Jaeger Collector
  2024 Jaeger 2.x：        全面拥抱 OTLP，「Jaeger Collector」合并进 OTel Collector
                          存储后端官方首推：Tempo（GrafanaLabs）→ 已停止大力自研存储
```

| 评估项 | 评分 | 详细说明 |
|--------|------|---------|
| **OTel JavaAgent** | ⭐⭐⭐⭐⭐ | 正确选择，JDK25 字节码支持最佳 |
| **OTel Collector** | ⭐⭐⭐⭐⭐ | 正确 |
| **Kafka 缓冲 Trace** | ⭐⭐⭐ | 有争议：Trace 流量 10% 采样下约 5000 spans/s，直接 gRPC 写后端即可；Kafka 徒增运维成本，除非要**跨地域复制 Trace** 或**二次消费到数仓** |
| **Jaeger Agent** | ⭐ | **已废弃！Jaeger 2.x 完全移除**；应改为 OTel Collector → **Jaeger Ingester**（消费 Kafka）或直接 → ES |
| **Trace 存 ES** | ⭐⭐ | 可行但成本高；ES 存 Trace 需要 `jaeger-span-*` 独立索引模板，否则 kibana 查询日志会被 Trace 污染；存储成本是 Tempo+S3 的 **3~5 倍** |
| **Jaeger UI** | ⭐⭐⭐ | Trace 视图优秀，但**拓扑图能力弱于 SkyWalking**，**指标/日志跳转能力为 0（无 Exemplar）** |
| **尾部采样** | ⭐⭐ | 需要 OTel Collector `tailsampling` processor + Kafka 配合；但 Jaeger Agent 废了之后链路不清晰 |

**修正后的正确链路：**
```
原方案（有问题）：
OTel JavaAgent → OTel Collector → Kafka → ❌ Jaeger Agent → ES → Jaeger

修正为（业界标准）：
OTel JavaAgent → OTel Collector(采样+批量) → Kafka → ✅ Jaeger Ingester → ES → Jaeger Query → Jaeger UI
```

#### 1.5.3 方案 A vs 方案 D：16 维度终极对比

| # | 维度 | 方案 A（本文推荐：OTel + Grafana全家桶 + Loki + Tempo） | 方案 D（用户提案：ELK+Kafka+Jaeger） | 胜方 |
|---|------|------------------------------------------------------|-------------------------------------|------|
| 1 | **组件总数** | 9 组件（OTelCol+Prom+Loki+Tempo+Graf+Arthas+MinIO+OraExp+AlertMgr） | 13+ 组件（OTelCol+FB+Kafka+LS+ES+KB+Jaeger+Prom+Graf+Arthas+OraExp+ZK+AlertMgr） | **A** 少 4 组件 |
| 2 | **统一 UI** | ✅ **Grafana 单 UI**：指标/Trace/日志/告警 全打通，一个 traceId 在同一 UI 跨面板跳转 | ❌ **三套 UI 割裂**：Kibana（日志）+ Jaeger UI（Trace）+ Grafana（指标），一个排查要切 3 个窗口 | **A** |
| 3 | **Exemplar 三支柱联动** | ✅ Prometheus 异常点 → Tempo Trace → Loki 日志，点一下自动跳转 | ❌ 无机制。需手动复制 traceId → Jaeger 搜索 → 再复制到 Kibana 搜日志；工程师每排查一个故障多花 10 分钟 | **A** |
| 4 | **存储总成本（288GB 日志+4.3亿Span/天+17亿指标/天）** | 约 **5T 对象存储 + 500G SSD（Prometheus）** = 约 1~1.5 万/月 | 约 **4T SSD（ES 日志）+ 1.5T SSD（ES Trace）+ 500G SSD(Prom)** = 约 **4~6 万/月** | **A 成本 × 1/4** |
| 5 | **运维复杂度** | OTel Collector + 3 存储（Loki/Tempo/Prom 都是 CNCF 项目，K8s Operator 成熟） | 5 层日志链路 + ES 集群（分片、路由、ILM、mapping 调优） + Kafka 集群（分区、消费延迟、ISR） + Jaeger 迁移清理（Agent 废弃） | **A** |
| 6 | **SpringBoot 4.x + JDK 25 兼容性** | ✅ Micrometer Tracing 官方默认，完全无侵入，Spring 官方 blog 背书 | ⚠️ 日志端解耦无问题；**Jaeger + JDK25 字节码可能冲突**（Jaeger 对最新 JDK 跟进慢于 OTel） | **A** |
| 7 | **日志全文检索** | ⭐⭐⭐ Loki「先 Label 过滤，再 grep 内容」，适合结构化查询；ES 作为可选补充只存 ERROR/WARN | ⭐⭐⭐⭐⭐ ES 倒排索引全文检索极强，适合审计/合规/模糊搜索 | **D** 胜，但可融合（A+ES 存 ERROR） |
| 8 | **Kafka 解耦/削峰** | ❌ Loki/Tempo 直接写 gRPC，靠 Collector 自带队列缓冲；极端流量下可能丢 0.1% 非关键数据 | ✅ Kafka Broker 持久化，数据丢失率 ≤ 0（只要 Broker >=3 且 ISR 正常） | **D** 胜 |
| 9 | **链路追踪存储** | ✅ Tempo 专门为 Trace 优化的存储；支持对象存储；支持 TraceQL（2024 新查询语言，比 Jaeger 查询强 3 倍） | ⚠️ ES 存 Trace 不是原生设计；Span 展开 JSON 后字段爆炸，mapping 容易超过 1000 字段上限；Jaeger 官方 2023 年后不再主推 ES 存储 | **A** |
| 10 | **告警体系** | ✅ Alertmanager 统一处理：指标告警、Loki 日志关键字告警、Tempo 错误率告警，全走同一路由通道 | ❌ Prom 走 Alertmanager，ES 走 Watcher（收费 X-Pack），Jaeger 无原生告警；三个告警系统要独立配置和排障 | **A** |
| 11 | **部署上手时间** | 2~3 天：Grafana Agent Operator 一键装 Prom/Loki/Tempo/Graf | 5~7 天：Kafka+ES 集群搭建+调优；ILM/Grok 模板踩坑；Jaeger Agent 废弃的适配 | **A** |
| 12 | **人员技能要求** | 中级运维：K8s + Grafana 生态即可 | 高级运维：ES/Kafka/JAEGER/Prom/LS/FB 全栈精通 + Grok 正则 + ES Mapping 调优 | **A** |
| 13 | **扩展性（服务 3→30）** | ✅ Loki/Tempo 水平扩展；MinIO 扩节点即可 | ⚠️ ES 数据节点每扩 1 个要 Rebalance（数小时）；Kafka 分区要扩还要重新分布；30 服务需 ES 节点 ≥ 15 | **A** |
| 14 | **Oracle 深度观测整合** | ✅ oracledb_exporter → Prom → Grafana Oracle 官方 Dashboard 直接用；可在同一界面关联 SQL 执行慢的 Trace | ⚠️ 同样要加 oracledb_exporter，但数据在 Grafana，而对应的 Trace 在 Jaeger UI，SQL 日志在 Kibana，三屏分离不直观 | **A** |
| 15 | **Arthas 诊断集成** | ✅ Arthas UI 嵌入 Grafana（iframe），单点登录 | ⚠️ 同样可做，但要和 Kibana/Jaeger/Grafana 三套 SSO 打通 | **A** |
| 16 | **长期演进风险** | ✅ Grafana 生态 2023-2026 增长最快；Loki/Tempo/OTel 全 CNCF 毕业；社区投资大 | ⚠️ Jaeger 被 Tempo 蚕食，社区投入下降；ELK 架构太经典但组件沉重，初创公司/小团队难持续维护 | **A** |

**综合对比结论：**
> 16 个维度中：**方案 A 赢 12 项，方案 D 赢 2 项（全文检索 + Kafka 解耦），平手 2 项**
>
> 方案 D 的核心优势是「ES 全文检索」和「Kafka 削峰」—— 这两项都可以**融合进方案 A**，无需全盘用 D：
> - **融合建议 1**：Loki 做主日志存储，另外把 ERROR/WARN 日志单独双写到 ES（仅 1% 数据量），保留 ES 全文检索优势
> - **融合建议 2**：在 OTel Collector 之前加一层 Kafka（仅 OTel Trace/Log 的极端场景缓冲），或直接信任 OTel Collector 的 `sending_queue` + `retry_on_failure` 队列

#### 1.5.4 方案 D 的适用场景（什么时候可以选 D？）

尽管综合 A 优于 D，但方案 D 在**特定场景**下仍是合理选择：

| 场景 | 是否推荐方案 D | 原因 |
|------|--------------|------|
| ✅ **公司已有大型 ELK/ES 团队（≥3 人专职）** | 强烈推荐 | 不浪费现有团队技能栈和 ES 集群；Kafka 和 LS 都已有 SOP |
| ✅ **合规/审计要求日志 100% 可检索 + 全文检索频繁使用** | 推荐 | 合规场景 Loki 的 label-first 可能不够；ES 倒排索引满足等保 2.0/ISO27001 |
| ✅ **现有系统已用 Jaeger，迁移成本高** | 推荐（但修正 Agent） | 历史包袱重时，只需要把链路中「Jaeger Agent」换成「Jaeger Ingester + Kafka Consumer」 |
| ❌ **团队 < 50 人，无专职 SRE/运维** | 强烈不推荐 | 组件太多，一旦 ES 出分片丢失/mapping 爆炸/OOM，没人能救 |
| ❌ **预算紧张（每月云资源 < 3 万）** | 不推荐 | 仅 ES SSD 存储 + Kafka 就超预算 |
| ❌ **使用了 SpringBoot 3+/4.x，追求长期演进** | 不推荐 | 与 Micrometer Tracing + Grafana 生态的官方方向背道而驰 |

#### 1.5.5 融合优化方案：A+（取两家之长）

```
推荐最终落地 = 方案 A 核心 + 方案 D 两个核心优势
（如果对这两个优势有刚需）：

┌───────────────────────────────────────────────────────────────┐
│                        3 个 SpringBoot                         │
│                  OTel SDK/Micrometer Tracing                   │
└──────────────────────┬────────────────────────────┬──────────┘
                       │ OTLP gRPC                   │ ERROR/WARN 仅双写
          ┌────────────▼────────────┐               │
          │   可选：Kafka 缓冲层    │               │
          │（高并发热点时才加）     │               │
          └────────────┬────────────┘               │
                       ▼                            ▼
          ┌────────────────────────┐      ┌──────────────────┐
          │    OTel Collector      │      │  ES 专用集群      │
          │  (tailsampling processor)│     │  (仅 ERROR/WARN)  │
          └──┬───────────┬─────────┘      └─────────┬────────┘
             ▼           ▼                          ▼
        ┌──────────┐  ┌────────┐             ┌──────────────┐
        │Prometheus│  │ Loki   │             │  Kibana       │
        │(含 Oracle│  │(主日志)│             │  (全文检索补) │
        │ Exporter)│  │        │             └──────────────┘
        └────┬─────┘  └────┬───┘
             ▼              ▼    ┌──────────┐
          ┌───────────────────┐  │  Tempo   │ ← 主 Trace
          │   Grafana 单 UI   │  │ (对象存) │
          │ 统一跳转/Exemplar │  └──────────┘
          └─────────┬─────────┘
                    ▼
              Arthas Tunnel
```

---

### 1.6 最终推荐

**推荐采用「方案 A」为主，融合部分 SkyWalking 的设计思想：**

1. **核心原因：**
   - SpringBoot 4.x 官方废弃了 Spring Cloud Sleuth，全面转向 **Micrometer Tracing + OpenTelemetry**
   - JDK 25 需要最新的字节码增强支持，OpenTelemetry Java Agent 更新最快
   - OpenTelemetry 是 CNCF 毕业项目，未来 5~10 年的事实标准

2. **三个微服务划分建议：**
   ```
   ServiceA（网关/接入层） → ServiceB（业务服务层） → ServiceC（数据服务层）
       ↓                        ↓                         ↓
   OTel SDK + Micrometer    OTel SDK + Micrometer     OTel SDK + Micrometer
   + Oracle JDBC 插桩       + Oracle JDBC 插桩        + Oracle JDBC 插桩（最重）
   ```

---

### 1.7 微服务间调用专项设计

> **用户核心关切：三个微服务之间不是孤立的，它们存在服务 A → B → C 的链式调用、A→C 直连、以及可能的循环/扇出调用。本节专门针对「服务间 RPC 调用」设计全链路观测 + 通信治理。**

#### 1.7.1 三服务调用拓扑（含协议标注）

```
                ┌───────────────────────────────────────────────────┐
                │          用户请求 / API / 外部调用                  │
                └───────────────────────┬───────────────────────────┘
                                        │ HTTPS + W3C traceparent
                                        ▼
                         ┌──────────────────────────┐
                         │  ServiceA  (网关/接入层)  │
                         │  - Spring Cloud Gateway  │
                         │  - OpenFeign / WebClient │
                         │  - 鉴权、限流、灰度发布    │
                         └─────┬───────────┬────────┘
                               │           │
               ┌───────────────┘           └───────────────┐
               │ Feign(gRPC)  同步调用                       │ HTTP/WebClient 直连
               ▼                                           ▼
   ┌──────────────────────────────┐           ┌──────────────────────────────┐
   │     ServiceB (业务服务层)     │           │   ServiceC  (数据服务层)      │
   │  - 订单、支付、库存核心逻辑   │◀──────────│  - Oracle CRUD 封装           │
   │  - 调用 ServiceC 查询/落库    │  gRPC     │  - Redis 缓存                 │
   │  - MQ 异步化订单结果          │  Dubbo    │  - Oracle 存储过程调用        │
   └──────────────┬───────────────┘  HTTP     └───────────┬──────────────────┘
                  │                                        │
                  └──────────────┬─────────────────────────┘
                                 │ JDBC (Oracle)
                                 ▼
                        ┌───────────────────┐
                        │   Oracle 19c RAC  │
                        └───────────────────┘

 ─────────────────────── 观测数据流向（不影响业务） ───────────────────────
    ServiceA/B/C 每个 Pod 内部都启动：
    ┌────────────────────────────────────────────────────────────┐
    │ OTel JavaAgent (字节码注入 Feign/gRPC/Dubbo/JDBC 插件)     │
    │ + Micrometer Observation (WebClient/RestTemplate 拦截)     │
    │ + MDC traceId/spanId 自动注入 Logback                       │
    └────────────────────────────┬───────────────────────────────┘
                                 │ OTLP/gRPC (4317)
                                 ▼
                         OTel Collector → Prometheus/Loki/Tempo
```

**调用类型清单（需全部覆盖观测）：**

| 调用方向 | 协议 | 典型接口 | QPS 估 | 超时 |
|----------|------|---------|--------|------|
| A → B | **gRPC**（高性能同步） | `CreateOrder(OrderRequest)` / `QueryPaymentStatus` | 1000/s | 2s |
| A → C | **Spring HTTP Service** (@HttpExchange + RestClient) | `GetUserInfo(userId)` / `GetSkuDetail(skuId)` | 2000/s | 500ms |
| B → C | **Dubbo 3.x**（Triple 协议，历史遗留系统对接） | `CreateOrderRecord` / `DeductInventory` | 800/s | 3s |
| B → B | **Spring Event 异步 + @Async** | `SendOrderNotification`（内部调用） | 500/s | - |
| C → Oracle | **JDBC HikariCP** | SELECT/UPDATE/存储过程 | ~5000/s | 10s |

#### 1.7.2 服务调用协议选型对比

> **⚠️ 关键更新（SpringBoot 3.2+ / 4.x）：**
> - **Spring 官方首推**：`@HttpExchange` + **Spring HTTP Service**（2023 年 Spring Framework 6.1 / SpringBoot 3.2 正式 GA）
> - **Spring Cloud OpenFeign 状态**：2024 年起进入 **Maintenance Mode（维护模式）**，不再新增特性，仅修 Bug 和 CVE；Spring Cloud 2024.0.x 文档明确新项目优先用 `@HttpExchange`
> - **RestClient（Spring 6.1 新增）**：替代 RestTemplate，默认非阻塞 + 观测友好，与 `@HttpExchange` 是完美搭档

| 维度 | **Spring HTTP Service（@HttpExchange + RestClient）⭐⭐⭐⭐⭐ 推荐** | **Spring Cloud OpenFeign** ❌ 维护模式 | **gRPC（HTTP/2 + Protobuf）** | **Dubbo 3.x（Triple/Hessian）** | **WebClient（响应式 HTTP）** |
|------|---------------------------------------------------------------|--------------------------------------|-------------------------------|--------------------------------|------------------------------|
| **SpringBoot 4.x 官方态度** | ✅ **首选推荐**，Spring Framework 核心团队维护 | ⚠️ **维护模式**，Spring Cloud 社区修 Bug 为主，不再新增特性 | ⚠️ netty + protoc 插件配置复杂（Spring 无内置） | ⚠️ Dubbo 3.3.x 刚适配 JDK25，需验证 | ✅ Spring WebFlux 内置，响应式首选 |
| **声明式编程体验** | ✅ `@GetExchange/@PostExchange` 接口注解，和 Feign 一样优雅，底层可换 RestClient/WebClient/RestTemplate | ✅ 注解声明式，但注解体系是 Netflix Feign 自创，与 Spring Web 注解不互通（`@RequestMapping`/`@RequestParam` 语义有细微差异） | ⚠️ 需写 `.proto` + 代码生成，学习曲线陡 | 中，需写 Interface + `@DubboService` 注解 | 中，lambda 链式调用，侵入式 |
| **与 Spring Web 注解复用** | ✅ `@HttpExchange` 直接复用 `@RequestParam`/`@PathVariable`/`@RequestBody`，Controller 和 Client 可共用同一个 DTO | ❌ 需单独写 Feign DTO，Controller `@Validated` 和 Feign `@Param` 常冲突 | ❌ Protobuf Message DTO，需额外 Bean Copy | ⚠️ Dubbo DTO 需实现 Serializable | ⚠️ 用 Mono/Flux 包装，DTO 共享需转换 |
| **性能（1KB Payload）** | **1.3×**（RestClient 默认 HttpClient 5 + HTTP/1.1 连接池复用） | 1×（Apache HC 4 + OkHttp，可选 HC 5） | **3~5×**（HTTP/2 多路复用 + Protobuf 二进制） | 2~3× | 1.5~2×（非阻塞 Reactor Netty） |
| **Micrometer Tracing / OTel 插桩** | ⭐⭐⭐⭐⭐ **零代码！** RestClient 由 `org.springframework.http.client.observation.DefaultClientRequestObservationConvention` 自动注册 Observation，traceparent + Exemplar + `http.client.requests` 指标全自动 | ⭐⭐⭐⭐ Micrometer 有 feign-micrometer 模块（需额外依赖）；Baggage 透传需自定义 `RequestInterceptor`；SpringBoot 4.x 下偶见跨线程上下文丢失 | ⭐⭐⭐⭐⭐ 官方 `opentelemetry-java-instrumentation` grpc 插件成熟，Client/Server 双向 Interceptor 开箱即用 | ⭐⭐⭐⭐ Apache 有 dubbo-3.x 插件，Dubbo Filter 透传 Attachment | ⭐⭐⭐⭐ Micrometer 内置 `MicrometerObservationClientFilter`，需手动 add |
| **Trace 透传 & Baggage** | ✅ **自动透传**：RestClient 创建时注入 `ObservationRegistry`（SpringBoot 4.x Actuator 已自动配），`traceparent/tracestate` + 自定义 Baggage（x-user-id）零配置 | ⚠️ 需手写 `RequestInterceptor`（见代码 1.7.5🅐），`BaggageManager` 在异步场景下偶发 NPE | ✅ 自动注入 `traceparent` / `grpc-trace-bin` Metadata | ✅ Dubbo Filter 自动透传 Attachment | ✅ 需手动 add Filter，`contextWrite()` 保证 reactor 子线程不丢 |
| **Resilience4j 容错整合** | ✅ `@CircuitBreaker`/`@TimeLimiter`/`@Retry` 注解直接打在 `@HttpExchange` 接口方法上；Spring AOP 代理无冲突 | ⚠️ 需 `feign-resilience4j` + 自定义 `InvocationHandler`，Feign 自己的 Hystrix 已废弃 | ✅ gRPC ClientInterceptor 可拦截，或用 Resilience4j `decorateCheckedFunction` 包装 stub | ✅ Dubbo 自带 Cluster Mock/Failfast/Failsafe，可与 R4j 双写 | ✅ `Mono.deferContextual()` + R4j reactor 模块 |
| **代码侵入性 / 迁移成本** | 极低。老 Feign 接口改 4 个注解即可迁移（见 1.7.2.1 迁移指南） | 老项目稳定但不建议新项目使用 | 中，`.proto` 代码生成 CI 流程要搭 | 中，历史遗留系统对接首选 | 中，项目切换到 Reactor 栈成本高 |
| **服务发现集成** | ✅ Spring Cloud LoadBalancer 直接注入到 `RestClient.Builder`，和 Feign 体验一致 | ✅ 与 `@FeignClient(name="service-c")` 深度集成 | ⚠️ 需 gRPC NameResolver 插件（如 grpc-spring-boot-starter） | ✅ Dubbo 注册中心（Nacos/ZK）原生 | ✅ 同左，手动 LoadBalancerExchangeFilterFunction |
| **适用场景** | ⭐⭐⭐⭐⭐ **90% HTTP 场景首选**：A→C 简单查询、内部微服务 HTTP 调用、外部第三方 HTTP API 封装 | ⚠️ **仅限存量 Feign 系统不迁移**，不再推荐新代码 | **高并发核心链路（A→B 推荐）**：如订单/支付写操作、10万+/日、低延迟敏感 | 对接现有 Dubbo 老系统、Alibaba 生态团队 | 响应式栈、并行扇出（A 同时调 B+C 合并结果）、大流量非阻塞 IO |

**⚠️ OpenFeign 维护模式详细说明（2024 年官方公告要点）：**
- Spring Cloud 2023.0.x (Leyton) 是 OpenFeign **最后一个功能版本**
- 2024+ Spring Cloud 路线图：所有 OpenFeign 新需求（如虚拟线程、HTTP/3、新 Observation API）**全部移交给 Spring HTTP Service 实现**
- OpenFeign 在 Spring Cloud 2024.x / 2025.x 中将仅做：CVE 修复、JDK 基线升级、Bug 修复（严重级别）
- 结论：**SpringBoot 4.x + JDK 25 的全新项目，OpenFeign 不应再出现在 pom.xml 里**

**五协议在三服务架构中的分配建议：**

```
ServiceA（接入层）  ──gRPC──▶  ServiceB（业务层）    ← 核心写链路，必须低延迟
ServiceA           ──@HttpExchange + RestClient──▶ ServiceC（数据层）  ← ✅ 简单读，官方首选，开发快
ServiceB           ──Dubbo──▶  ServiceC              ← 历史遗留 Dubbo 接口
ServiceA           ──WebClient(并行扇出)──▶  B + C   ← 组合接口场景（非阻塞并行）
```

##### 1.7.2.1 OpenFeign → Spring HTTP Service 迁移速查表（10 分钟完成）

| 场景 | 老写法（OpenFeign） | 新写法（Spring HTTP Service + RestClient） |
|------|---------------------|-------------------------------------------|
| 依赖 | `spring-cloud-starter-openfeign` | `spring-boot-starter-web`（RestClient 内置，无需额外依赖） |
| 启动类注解 | `@EnableFeignClients` | ❌ **无**，直接 Bean 注册 |
| 接口定义 | `@FeignClient(name = "service-c", path = "/api/internal")`<br>`public interface ServiceCClient {`<br>`  @GetMapping("/user/{id}")`<br>`  UserVO getUser(@PathVariable("id") Long id);`<br>`}` | `public interface ServiceCClient {`<br>`  @GetExchange("/api/internal/user/{id}")`<br>`  UserVO getUser(@PathVariable Long id);`<br>`}` |
| 创建 Bean | ❌ 自动扫描 | `@Bean`<br>`public ServiceCClient serviceCClient(RestClient.Builder builder,` <br>`                                               ObservationRegistry reg) {`<br>`  RestClient client = builder`<br>`    .baseUrl("http://service-c")`<br>`    .observationRegistry(reg)  // ✅ 开启观测`<br>`    .build();`<br>`  return HttpServiceProxyFactory`<br>`    .builderFor(client)`<br>`    .build().createClient(ServiceCClient.class);`<br>`}` |
| 写接口 POST | `@PostMapping(value="/sku/batch", consumes=APPLICATION_JSON_VALUE)`<br>`List<SkuVO> getBatch(@RequestBody List<Long> ids);` | `@PostExchange(value="/sku/batch", contentType=APPLICATION_JSON_VALUE)`<br>`List<SkuVO> getBatch(@RequestBody List<Long> ids);` |
| 请求参数 @RequestParam | `@RequestParam("name") String name`（Feign 必须写 value，JDK 8 -parameters 也不生效） | `@RequestParam String name`（Spring 标准注解，直接用 -parameters 编译参数名） |
| Header 透传 | `@RequestHeader("Authorization") String token` | `@RequestHeader("Authorization") String token` （完全相同） |
| 异常解码器 | `ErrorDecoder` 自定义 | `RestClient` 用 `.defaultStatusHandler(...)` 注册，比 Feign 灵活 3 倍，可直接拿到 ClientResponse |
| 超时配置 | `feign.client.config.default.connectTimeout=2000`<br>`feign.client.config.default.readTimeout=500` | 直接在底层 HttpClient 5 / JdkClient 配置：`RestClient.builder().requestFactory(...)`，或 application.yml `spring.restclient.*`（SpringBoot 4.x 自动配置） |
| 容错 @CircuitBreaker | 需 `feign-resilience4j` 或 `@FeignClient(configuration=...)` 配 Fallback | ✅ `@CircuitBreaker(name="service-c-calls")` **直接打在 @HttpExchange 接口方法上**，Spring AOP 原生生效 |
| 负载均衡（服务发现） | Feign 默认集成 Spring Cloud LB | `RestClient.Builder` 自动被 `SpringCloudClientHttpRequestFactoryBuilderCustomizer` 包装，LB 开箱即用 |

#### 1.7.3 服务发现 + 负载均衡方案

| 方案 | 机制 | 优点 | 缺点 | 推荐 |
|------|------|------|------|------|
| **K8s Service + ClusterIP** | kube-proxy iptables/ipvs 四层负载 | 无依赖，零配置 | 无权重/灰度/标签路由，客户端不知道 Pod 健康 | ✅ **默认首选**，简单稳定 |
| **Spring Cloud LoadBalancer** | 客户端轮询/Random，从 Nacos/Consul/Eureka 取列表 | 支持灰度/权重/自定义策略 | 多一个注册中心依赖 | 有 Nacos 时选 |
| **Istio / Cilium Service Mesh** | Sidecar 透明流量劫持 | 金丝雀、熔断、mTLS、拓扑图自动生成 | 运维复杂度极高，资源开销 +15% | 服务 ≥ 20 才考虑 |
| **Dubbo 注册中心（Nacos/ZK）** | Dubbo 原生服务发现 | 与 Dubbo Filter 联动 | 仅适用于 Dubbo 调用 | 有 Dubbo 必须上 |

**落地：3 个微服务场景**
```yaml
# 推荐：9 Pod 规模用「K8s Service 四层LB + Spring Cloud LoadBalancer 客户端LB」组合
spring:
  cloud:
    kubernetes:
      discovery:
        all-namespaces: false
        service-labels:
          expose: "true"
    loadbalancer:
      ribbon:
        enabled: false  # Ribbon 已停更，用 Spring Cloud LB 默认实现
      cache:
        enabled: true
        ttl: 5s
      health-check:  # 客户端主动探测，避免 k8s Service 转发到半死 Pod
        interval: 3s
```

#### 1.7.4 容错设计：超时 / 重试 / 熔断 / 限流（Resilience4j）

**服务调用四板斧（必须配置，否则观测数据全是「雪崩后的假数据」）：**

```
┌─────────────┐   ┌───────────┐   ┌──────────────┐   ┌──────────────┐
│  TimeLimiter│ → │  Retry    │ → │ CircuitBreaker│ → │  Bulkhead    │
│  超时控制    │   │  重试机制  │   │  熔断保护     │   │  舱壁隔离    │
└─────────────┘   └───────────┘   └──────────────┘   └──────────────┘
```

**Resilience4j 参数建议（三服务各协议不同）：**

| 调用 | TimeLimiter | Retry | CircuitBreaker | Bulkhead |
|------|------------|-------|----------------|----------|
| **A → B（gRPC）** | 2000ms | maxAttempts=2（仅重试 UNAVAILABLE/DEADLINE_EXCEEDED，幂等读接口才可重试） | failureRateThreshold=20%，waitInOpenState=30s，slidingWindowSize=100 | maxConcurrentCalls=200，Fair |
| **A → C（Spring HTTP Service + RestClient）** | 500ms | maxAttempts=3（读接口 GET），写接口 POST 不重试（R4j `@Retry` 直接打在接口方法上） | failureRateThreshold=30%，slowCallRateThreshold=50%，slowCallDuration=400ms | maxConcurrentCalls=500（R4j `@Bulkhead` 注解可直接加在接口上） |
| **B → C（Dubbo）** | 3000ms | maxAttempts=1（写多，谨慎重试） | failureRateThreshold=25%，permittedNumberOfCallsInHalfOpenState=20 | maxConcurrentCalls=150 |
| **全局兜底** | 所有调用必须配超时，严禁 `timeout=-1` | **写接口禁止默认重试**，除非业务支持幂等 | 所有熔断触发时打 WARN 日志 + counter 指标 | 线程池隔离信号量二选一 |

**关键：Resilience4j 与 Micrometer Tracing 关联**
- 熔断半开/打开事件自动上报为 `resilience4j.circuitbreaker.state` Gauge 指标
- 重试次数上报为 `resilience4j.retry.calls` Counter
- 超时/熔断异常自动注入当前 Span error 属性 → Tempo 可定位「慢 → 超时 → 熔断」的演化过程

#### 1.7.5 微服务间 Trace 透传拦截器（覆盖全部协议）

> **Spring HTTP Service（@HttpExchange + RestClient）为官方首选，Trace/Baggage/Exemplar 零代码自动开启；以下代码分两部分：**
> 1. **⭐⭐⭐⭐⭐ 首选方案**：Spring HTTP Service 完整声明式接口 + 观测集成（零拦截器，推荐）
> 2. 历史兼容方案：Feign/gRPC/Dubbo/WebClient 手动拦截器（存量系统或自定义协议时用）

##### ⭐⭐⭐⭐⭐ 🥇 Spring HTTP Service（@HttpExchange + RestClient）—— 官方首选，**零拦截器自动观测**

```java
// ==================================== 接口声明 ====================================
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.List;

/**
 * ServiceC 数据层 HTTP 声明式接口（ServiceA → ServiceC 调用）
 * ✅ 特点：
 * 1. Traceparent/tracestate + x-user-id/x-request-id Baggage：SpringBoot 4.x Actuator 下零配置自动透传
 * 2. Micrometer http.client.requests 指标（P95/P99 + Exemplar）：零配置自动采集
 * 3. Resilience4j 容错注解：直接打在接口方法上，AOP 生效
 */
@HttpExchange(url = "http://service-c/api/internal", accept = MediaType.APPLICATION_JSON_VALUE)
@CircuitBreaker(name = "service-c-client")
@Bulkhead(name = "service-c-client", type = Bulkhead.Type.THREADPOOL)
public interface ServiceCClient {

    /** 按 ID 查询用户（读接口，可重试） */
    @GetExchange("/user/{id}")
    @Retry(name = "service-c-reads")
    @TimeLimiter(name = "service-c-reads")
    UserVO getUser(@PathVariable Long id,
                   @RequestHeader(name = "Authorization", required = false) String bearerToken);

    /** 批量查询 SKU（POST 传 JSON，读接口，可重试） */
    @PostExchange(value = "/sku/batch", contentType = MediaType.APPLICATION_JSON_VALUE)
    @Retry(name = "service-c-reads")
    List<SkuVO> getBatchSku(@RequestBody List<Long> skuIds);

    /** 扣减库存（写接口，禁止重试；熔断降级） */
    @PostExchange("/inventory/deduct")
    @TimeLimiter(name = "service-c-writes", fallbackMethod = "deductInventoryFallback")
    DeductResultVO deductInventory(@RequestBody DeductInventoryRequest req);

    /** Fallback：熔断或超时触发，返回降级响应（避免雪崩） */
    default DeductResultVO deductInventoryFallback(DeductInventoryRequest req, Throwable t) {
        return new DeductResultVO(false, "INVENTORY_FALLBACK",
                "库存服务暂时不可用，稍后重试：" + t.getMessage());
    }
}

// ==================================== Bean 创建（Configuration 类中） ====================================
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Configuration
public class HttpClientConfig {

    @Value("${app.service-c.base-url:http://service-c}")
    private String serviceCBaseUrl;

    /**
     * ServiceCClient Bean 创建
     * ✅ 核心亮点：.observationRegistry(reg) 这一行开启：
     *   - traceparent/tracestate 头（W3C TraceContext）
     *   - MDC traceId/spanId 自动注入日志
     *   - Prometheus http.client.requests{client.name='service-c',uri='/api/internal/user/{id}'} 指标
     *   - Exemplar（Prometheus → Tempo TraceID 一键跳转）
     *   - Spring 标准 Baggage 字段（x-user-id/x-request-id）自动透传
     */
    @Bean
    public ServiceCClient serviceCClient(RestClient.Builder restClientBuilder,
                                         ObservationRegistry observationRegistry) {
        // 1. 自定义请求工厂（连接超时 + 读超时；JDK 25 自带 HttpClient 无第三方依赖）
        ClientHttpRequestFactory factory = ClientHttpRequestFactories.get(
                JdkClientHttpRequestFactory.class,
                ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(Duration.ofMillis(200))
                        .withReadTimeout(Duration.ofMillis(500))
        );

        // 2. 可选：追加自定义 Header（调用方标识；Baggage 字段前面 observationRegistry 会自动处理）
        List<ClientHttpRequestInterceptor> interceptors = new ArrayList<>();
        interceptors.add((request, body, execution) -> {
            request.getHeaders().set("x-caller-service", "service-a");
            return execution.execute(request, body);
        });

        // 3. 构造 RestClient → HTTP Service Proxy
        RestClient restClient = restClientBuilder
                .baseUrl(serviceCBaseUrl)
                .requestFactory(factory)
                .observationRegistry(observationRegistry)      // ⭐ 观测开关（一行搞定全部）
                .requestInterceptors(consumers -> consumers.addAll(interceptors))
                .defaultStatusHandler(HttpStatus::is5xxServerError,
                        (request, response) -> {
                            throw new IllegalStateException("ServiceC Server Error: " + response.getStatusCode()
                                    + "，traceId=" + request.getHeaders().getFirst("traceparent"));
                        })
                .build();

        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(ServiceCClient.class);
    }
}

// ==================================== Resilience4j 参数（application.yml） ====================================
// resilience4j:
//   circuitbreaker:
//     configs:
//       default:
//         failureRateThreshold: 30
//         slowCallRateThreshold: 50
//         slowCallDurationThreshold: 400ms
//         slidingWindowSize: 100
//         waitDurationInOpenState: 30s
//   timelimiter:
//     configs:
//       default:
//         timeoutDuration: 500ms
//         cancelRunningFuture: true
//   retry:
//     instances:
//       service-c-reads:
//         maxAttempts: 3
//         waitDuration: 50ms
//         retryExceptions:
//           - org.springframework.web.client.ResourceAccessException
//           - java.net.SocketTimeoutException
//   bulkhead:
//     thread-pool:
//       configs:
//         default:
//           maxThreadPoolSize: 50
//           coreThreadPoolSize: 20
//           queueCapacity: 200
```

---

##### 🅑 OpenFeign（HTTP）拦截器 —— **历史兼容方案，新项目不推荐**

> ⚠️ 2024 年起 Spring Cloud OpenFeign 进入**维护模式**，不再推荐新代码使用；请优先采用上方「方案 A：Spring HTTP Service」。
> 下方拦截器仅用于**存量 Feign 系统短期不迁移场景**的 Trace 透传兜底。

```java
/** Feign 调用拦截器：透传 Traceparent + x-user-id + x-request-id */
@Configuration
public class FeignTraceInterceptor implements RequestInterceptor {

    private final Tracer tracer;
    private final Propagator propagator;  // OTel W3C Propagator

    @Override
    public void apply(RequestTemplate template) {
        // 1. 自动注入 W3C traceparent / tracestate 头
        Span currentSpan = tracer.currentSpan();
        if (currentSpan != null) {
            propagator.inject(
                currentSpan.context(),
                template,
                (carrier, key, value) -> carrier.header(key, value)
            );
        }
        // 2. 透传业务 Baggage（透传用户身份给下游做权限校验）
        Baggage.fromContext(Context.current(), "x-user-id")
            .ifPresent(v -> template.header("x-user-id", v));
        Baggage.fromContext(Context.current(), "x-request-id")
            .ifPresent(v -> template.header("x-request-id", v));

        // 3. 打调用来源标签（便于下游识别调用方）
        template.header("x-caller-service", "service-a");
    }
}
```

##### 🅑 gRPC Client/Server Interceptor

```java
/** gRPC Client 侧：注入 OTel Context 到 Metadata */
@Bean
public ClientInterceptor grpcTracingClientInterceptor(
        OpenTelemetry openTelemetry, Tracer tracer) {
    return new ClientInterceptor() {
        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                MethodDescriptor<ReqT, RespT> method,
                CallOptions callOptions, Channel next) {
            Context ctx = Context.current();
            ClientCall<ReqT, RespT> call = next.newCall(method, callOptions);
            return new SimpleForwardingClientCall<>(call) {
                @Override
                public void start(Listener<RespT> responseListener, Metadata headers) {
                    // OTel TextMapPropagator 注入 traceparent 到 gRPC Metadata
                    openTelemetry.getPropagators().getTextMapPropagator()
                        .inject(ctx, headers, Metadata::put);
                    super.start(responseListener, headers);
                }
            };
        }
    };
}

/** gRPC Server 侧：从 Metadata 提取 Context（保证链不断） */
@Bean
public ServerInterceptor grpcTracingServerInterceptor(OpenTelemetry otel, Tracer tracer) {
    return new ServerInterceptor() {
        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            Context extracted = otel.getPropagators().getTextMapPropagator()
                .extract(Context.root(), headers, Metadata::get);
            // makeVisible: MDC 自动获得 traceId
            try (Scope ignored = extracted.makeCurrent()) {
                return next.startCall(call, headers);
            }
        }
    };
}
```

##### 🅒 Dubbo 3.x Filter（透传到 Attachment）

```java
/** Dubbo Filter：trace → RpcContext Attachment */
@Activate(group = {CommonConstants.PROVIDER, CommonConstants.CONSUMER}, order = -10000)
public class DubboTraceFilter implements Filter {

    private final Tracer tracer;
    private final Propagator propagator;

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        boolean isConsumer = CommonConstants.CONSUMER.equals(
                invoker.getUrl().getParameter(CommonConstants.SIDE_KEY));

        if (isConsumer) {
            // Consumer 侧：把 Trace 写到 Dubbo Attachment
            Span span = tracer.currentSpan();
            if (span != null) {
                Map<String, String> att = new HashMap<>();
                propagator.inject(span.context(), att, Map::put);
                invocation.setObjectAttachments(att);
            }
        } else {
            // Provider 侧：从 Attachment 恢复 Context
            Map<String, Object> att = invocation.getObjectAttachments();
            if (att != null && !att.isEmpty()) {
                Context ctx = propagator.extract(Context.root(), att,
                        (carrier, k) -> Objects.toString(carrier.get(k), null));
                ctx.makeCurrent();
            }
        }
        return invoker.invoke(invocation);
    }
}
```

##### 🅓 WebClient（并行扇出）调用保留 Span 上下文

```java
/** 并行调 B + C 时每个子流都保留独立 Span */
@Service
public class CompositeService {

    private final WebClient bClient;
    private final WebClient cClient;
    private final ObservationRegistry registry;

    /**
     * 组合接口：A 并行调 B(CreateOrder) + C(GetUserInfo)，合并结果返回
     * 关键：.contextWrite(ContextSnapshot::setThreadLocalsFrom) 保证 reactor 子线程 traceId 不丢
     */
    public Mono<OrderDetailVO> getOrderDetailComposite(String orderId, String userId) {
        var parentSnapshot = ContextSnapshot.captureAll(registry);

        Mono<OrderInfo> bResult = bClient.post()
                .uri("/api/order/{orderId}", orderId)
                .retrieve()
                .bodyToMono(OrderInfo.class)
                .contextWrite(ctx -> parentSnapshot.setThreadLocalsFrom(ctx));

        Mono<UserInfo> cResult = cClient.get()
                .uri("/api/user/{userId}", userId)
                .retrieve()
                .bodyToMono(UserInfo.class)
                .contextWrite(ctx -> parentSnapshot.setThreadLocalsFrom(ctx));

        return Mono.zip(bResult, cResult)
                .map(tuple -> new OrderDetailVO(tuple.getT1(), tuple.getT2()));
    }
}
```

#### 1.7.6 服务间调用专属指标 & SLA

除了 `http.client.requests` 和 `rpc.client.duration` 这种通用指标外，三服务架构下必须有**针对每个下游的专属 SLA 看板**：

| 指标名称（自定义） | Tags | 含义 | SLO |
|-------------------|------|------|-----|
| `app.rpc.success_rate` | caller=service-a, callee=service-b, protocol=grpc, method=CreateOrder | 该调用对成功率 | **≥ 99.9%**（核心写链路） |
| `app.rpc.p95_latency` | caller=service-a, callee=service-c, protocol=feign, api=GetUserInfo | 该调用对 P95 延迟 | **≤ 200ms** |
| `app.rpc.circuit_open_events` | caller, callee, protocol | 熔断打开次数 | **≤ 1 次 / 日** |
| `app.rpc.retries_total` | caller, callee, retryAttempt | 重试次数（带第几次尝试标签） | **P99 ≤ 2 次** |
| `app.rpc.bulkhead_rejections` | caller, callee | 舱壁拒绝次数（线程池/信号量耗尽） | **≈ 0** |
| `app.rpc.timeout_total` | caller, callee | 超时次数 | **≤ 0.1%** |

**下游依赖 Gauge（运维一眼看懂依赖健康度）：**
```
service_downstream_status{caller="service-a", callee="service-b", protocol="grpc"}  1 (健康) / 0 (不可达)
service_downstream_status{caller="service-b", callee="service-c", protocol="dubbo"} 1
service_downstream_status{caller="service-c", callee="oracle_rac1", protocol="jdbc"} 1
```

#### 1.7.7 典型微服务调用故障 → 观测排查流程（实战演练）

| 故障现象 | 观测链路（通过 Grafana 单 UI 点三下完成） | Arthas 介入点 |
|----------|----------------------------------------|--------------|
| **A→B gRPC 成功率突跌至 80%** | ① Prom `app.rpc.success_rate{caller=A,callee=B}` 异常 → ② 点 Exemplar 跳 Tempo 看 Trace → 发现所有失败 Span 都带 `status=UNAVAILABLE: upstream reset` → ③ 点 TraceID 跳 Loki 查 ServiceB Pod 日志 → 发现 ServiceB Pod 10 分钟前发生了一次 Full GC（STW 12s） | `dashboard` 看堆，`heapdump` 下载分析内存泄漏 |
| **B→C Dubbo P99 延迟 3s → 5s → 熔断** | ① Prom `app.rpc.p95_latency` 曲线 + Exemplar → ② Tempo Trace：B `ConsumerSpan` 3.8s，对应 C `ProviderSpan` 120ms，中间 3.6s 空白 → 结论：**Dubbo 线程池排队**（不是 C 处理慢，是 B 拿不到 Dubbo 线程）→ ③ Loki 查 B Dubbo threadpool `QUE FULL` 日志 | `watch org.apache.dubbo.threadpool.EagerThreadPoolExecutor getActiveCount` |
| **A → C Feign 偶发 503，重试后恢复** | ① Prom `app.rpc.retries_total{callee=C, attempt=2}` 突增 → ② Tempo：第一次调用 Span 被 ServiceC K8s ReadinessProbe 标记 Unhealthy 前的 2s 窗口 503 → ③ Loki 查 ServiceC Oracle HikariCP `leak-detection-threshold` 打了 WARN（连接泄漏 10s） → 根因：ServiceC 某分支 `try-with-resources` 没写，Connection 未归还 | `ognl '@HikariDataSource@dataSource.getHikariPoolMXBean().getActiveConnections()'` 配合 `watch` 拿未关闭栈 |
| **Oracle 19c RAC 一节点挂 → 服务级联失败** | ① Prom `oracledb_resource_limit_current_utilization{service=C}` 节点2 sessions 100% → ② Grafana 拓扑图（基于 Tempo Service Graph）发现 ServiceC → Oracle 延迟骤增 → ③ 所有 ServiceC 相关 Span 的 `db.oracle.instance` tag 都指向 orcl2 → 自动触发 C 的 HikariCP `oracle.jdbc.fanEnabled=true` FCF 故障转移到 orcl1 | `jad` 查看 HikariCP 恢复逻辑，`logger` 开启 FAN 事件 DEBUG |

---

## 2. 调用链跟踪设计

### 2.1 Trace 传播标准选型

| 标准 | 说明 | 推荐场景 |
|------|------|----------|
| **W3C TraceContext** | 全球标准，`traceparent` + `tracestate` 头 | ✅ **推荐**，SpringBoot 4 默认 |
| B3（Zipkin） | 老标准，`X-B3-TraceId` 等多 Header | 兼容遗留系统时使用 |
| SkyWalking 8.x | `sw8` 协议头 | 混合 SkyWalking 探针时 |

**推荐：统一使用 W3C TraceContext，B3 仅作为兼容降级。**

### 2.2 调用链组件方案对比

#### 方案 2A：OpenTelemetry + Tempo（推荐）

| 组件 | 作用 |
|------|------|
| OpenTelemetry Java Agent | 字节码注入，无侵入采集 |
| Micrometer Tracing Bridge (OTel) | SpringBoot 4 官方桥接 |
| OTel Collector Gateway | 批量、采样、路由 |
| Grafana Tempo | Trace 存储后端（对象存储友好） |

**核心优势：**
- Tempo 支持对象存储（S3/OSS），成本低，无需 Elasticsearch
- 与 Grafana、Loki、Prometheus 原生联动（Exemplar 关联）
- TraceID 直接跳转日志（Loki）和指标（Prometheus）

#### 方案 2B：SkyWalking Java Agent + OAP Server

| 组件 | 作用 |
|------|------|
| SkyWalking Java Agent | 字节码增强采集 |
| OAP Server | 分析、聚合、存储 |
| Elasticsearch / MySQL | 存储后端 |
| SkyWalking UI | 拓扑图 + Trace 视图 |

**核心优势：**
- 国内文档丰富，中文社区活跃
- 自动生成服务拓扑图
- 对 Oracle、Dubbo、MQ 等国产中间件插件更全

**劣势：**
- OAP Server 资源消耗大
- 存储严重依赖 Elasticsearch（成本高）
- JDK 25 + SpringBoot 4.1 的兼容性需要验证

#### 方案 2C：OpenTelemetry + Jaeger

| 组件 | 作用 |
|------|------|
| OTel Java Agent | 采集 |
| Jaeger Collector + Query | 处理和查询 |
| Cassandra / ES | 存储 |

**劣势：** Jaeger 已将后端逐步迁移到 Tempo，社区投入下降，不推荐新建项目。

### 2.3 采样策略设计

| 采样类型 | 策略 | 适用场景 | 配置示例 |
|----------|------|----------|----------|
| **头部采样（Head-based）** | 在入口服务根据 TraceID 哈希决定 | ✅ 推荐，简单可控 | `sampling.probability=0.1`（10%） |
| **尾部采样（Tail-based）** | Collector 收完所有 Span 后再根据错误/延迟决定 | 重要业务，需捕捉长尾 | OTel Collector `tailsampling` processor |
| **规则采样** | 按接口重要性差异化采样 | 核心接口 100%，非核心 1% | 自定义 `Sampler` Bean |
| **错误全采** | 错误状态 Span 强制采样 | 问题诊断 | 与尾部采样结合 |

**推荐三层采样架构：**
```
入口层（服务A）：
  ├─ 核心接口（/api/order/*、/api/payment/*）：100% 采样
  ├─ 普通接口：10% 概率采样
  └─ 健康检查/静态资源：0% 采样

Collector 层：
  ├─ 错误（status=ERROR）Span：全量落库
  ├─ P99 延迟 > 3s 的 Trace：全量落库
  └─ 其余：按配置比例保留

存储层：
  ├─ 近 7 天：SSD 热数据
  ├─ 8~30 天：对象存储温数据
  └─ >30 天：归档 / 删除
```

### 2.4 Span 设计规范

#### 2.4.1 必须采集的 Span 类型

| 层级 | Span 名称示例 | Semantic Conventions 关键属性 |
|------|--------------|------------------------------|
| **HTTP 入口** | `GET /api/users/{id}` | `http.request.method`, `url.path`, `http.response.status_code`, `client.address` |
| **HTTP 出口（RestClient/WebClient）** | `GET http://service-b/orders` | `http.request.method`, `url.full`, `server.address`, `server.port` |
| **JDBC（Oracle）** | `SELECT * FROM T_ORDER WHERE ID=?` | `db.system=oracle`, `db.name`, `db.statement`, `db.operation` |
| **缓存（Redis）** | `GET user:123` | `db.system=redis`, `db.operation`, `db.statement` |
| **MQ（Kafka/RocketMQ）** | `send orders-topic` | `messaging.system`, `messaging.destination.name`, `messaging.operation` |
| **业务自定义** | `createOrder` / `calculatePrice` | `biz.order_id`, `biz.user_id`, `biz.amount` |
| **方法级（Arthas 触发）** | `com.example.service.OrderService.createOrder` | `code.function`, `code.namespace`, `code.lineno` |

#### 2.4.2 Oracle 专有属性

```yaml
# 建议为 Oracle Span 添加以下自定义属性
db.oracle.sql_id: "abc123def456"       # Oracle SQL_ID（便于关联 AWR/ASH）
db.oracle.bind: "[123, '2026-10-01']" # 脱敏后的绑定变量
db.oracle.fetch_rows: 256              # 实际返回行数
db.oracle.exec_time_ms: 45             # 实际执行时间（不含网络）
```

### 2.5 Trace-ID 全链路透传

```
                          ┌───────────────────────────────┐
                          │     HTTP Request Headers      │
                          │  traceparent: 00-xxxx-yyyy-01 │
                          └──────────────┬────────────────┘
                                         │
              ┌──────────────────────────┼──────────────────────────┐
              │                          │                          │
        ┌─────▼─────┐             ┌──────▼──────┐            ┌──────▼──────┐
        │ ServiceA  │───gRPC────▶│  ServiceB   │───JDBC───▶│  ServiceC   │
        │ (Gateway) │             │  (Business) │            │   (Data)    │
        └─────┬─────┘             └──────┬──────┘            └──────┬──────┘
              │                          │                          │
              ▼                          ▼                          ▼
     MDC: traceId=xxxx            MDC: traceId=xxxx           MDC: traceId=xxxx
     logback 自动注入             logback 自动注入             logback 自动注入
```

**实现要点：**
1. Micrometer Tracing 自动通过 `ObservationRegistry` 注入 MDC
2. 异步线程池需使用 `ContextAwareExecutorService`（Spring 6+ 提供）
3. `@Async` 需配置 `TaskDecorator` 传递上下文
4. Oracle 连接池需传递 `CLIENT_IDENTIFIER` = traceId（关联 V$SESSION）

---

## 3. 关键指标采集设计

### 3.1 指标方案对比

| 方案 | 采集端 | 存储 | 查询语言 | 推荐度 |
|------|--------|------|----------|--------|
| **方案 A：Micrometer + Prometheus** | Micrometer Registry | Prometheus | PromQL | ⭐⭐⭐⭐⭐ 推荐 |
| 方案 B：OTel Metrics + VictoriaMetrics | OTel SDK | VictoriaMetrics | PromQL / MetricsQL | ⭐⭐⭐⭐ 大规模推荐 |
| 方案 C：Micrometer + InfluxDB | Micrometer | InfluxDB 2.x | Flux | ⭐⭐ 不推荐，Flux 已停止维护 |
| 方案 D：SkyWalking 自带指标 | SW Agent | OAP + ES | LAL（自有） | ⭐⭐⭐ 仅在方案 B 全家桶时用 |

**推荐：方案 A（Micrometer + Prometheus）**
- SpringBoot 4.x 默认 Actuator 基于 Micrometer，开箱即用
- PromQL 生态最成熟，Grafana 模板最多

### 3.2 指标分类与采集清单

#### 3.2.1 系统资源指标（System / Host）—— 每 15s 采集

| 指标名称 | 类型 | 含义 | 阈值/告警 | 来源 |
|----------|------|------|-----------|------|
| `system.cpu.usage` | Gauge | 主机 CPU 使用率 | >85% 持续 5min | Micrometer System Metrics |
| `system.cpu.load.average.1m` | Gauge | 1 分钟负载 | >CPU 核数 × 2 | Micrometer |
| `jvm.memory.used` (heap/nonheap) | Gauge | JVM 内存使用量 | Heap >80% 持续 10min | Micrometer JVM |
| `jvm.gc.pause` (G1/ZGC) | Timer | GC 停顿时间和次数 | G1 FullGC >1次/min | Micrometer GC |
| `jvm.threads.live` | Gauge | 活跃线程数 | 突增 50% + | Micrometer Threads |
| `jvm.threads.peak` | Gauge | 峰值线程数 | >配置最大线程 × 0.9 | Micrometer |
| `jvm.classes.loaded` | Gauge | 已加载类数 | 持续增长（内存泄漏） | Micrometer |
| `process.uptime` | Gauge | 进程运行时间 | 重启告警 | Micrometer |
| `disk.free` | Gauge | 磁盘可用空间 | <20% | Micrometer Disk |
| `process.files.open` | Gauge | 打开文件描述符 | >最大限制 × 0.8 | Micrometer |

#### 3.2.2 应用层指标（Application）—— 每 15s 采集

**HTTP 服务端（核心！）：**

| 指标名称 | 类型 | Tags | 含义 |
|----------|------|------|------|
| `http.server.requests` | Timer | method, uri, status, exception | HTTP 请求 QPS、延迟、错误率 |
| `http.server.active.requests` | LongTaskTimer | uri | 正在执行的请求数（压在途） |
| `spring.security.filterchains` | Timer | filter.name | 安全链耗时 |

**HTTP 客户端（调用下游）：**

| 指标名称 | 类型 | Tags | 含义 |
|----------|------|------|------|
| `http.client.requests` | Timer | method, uri, status, client.name | 下游调用 QPS、延迟、错误率 |
| `http.client.connections` | Gauge | client.name, state | HTTP 连接池状态（空闲/使用中） |

**线程池/任务执行：**

| 指标名称 | 类型 | Tags | 含义 |
|----------|------|------|------|
| `executor.completed` | Counter | name | 已完成任务数 |
| `executor.queue.remaining` | Gauge | name | 队列剩余容量 |
| `executor.active` | Gauge | name | 活跃线程数 |
| `executor.pool.size` | Gauge | name | 池大小 |
| `executor.queue.depth` | Gauge | name | 队列排队任务数 |

#### 3.2.3 Oracle 数据库指标（重中之重！）—— 每 30s 采集

**Oracle 连接池（HikariCP）：**

| 指标名称 | 类型 | 含义 | 阈值 |
|----------|------|------|------|
| `hikaricp.connections.active` | Gauge | 活跃连接数 | >最大连接 × 0.8 |
| `hikaricp.connections.idle` | Gauge | 空闲连接数 | 接近 0 说明连接池吃紧 |
| `hikaricp.connections.pending` | Gauge | 等待获取连接的线程数 | >5 持续告警 |
| `hikaricp.connections.creation` | Timer | 创建连接耗时 | >500ms 异常 |
| `hikaricp.connections.usage` | Timer | 连接占用时间 | P99 > 5s 说明慢 SQL |
| `hikaricp.connections.timeout` | Counter | 获取连接超时次数 | >0 立即告警 |

**Oracle 实例级（通过 oracledb_exporter）：**

| 指标 | 含义 | 关注值 |
|------|------|--------|
| `oracledb_resource_limit_current_utilization{resource_name='sessions'}` | 当前 Session 使用率 | >80% |
| `oracledb_resource_limit_current_utilization{resource_name='processes'}` | Process 使用率 | >80% |
| `oracledb_sysmetric_wait_time{class='Wait I/O'}` | I/O 等待时间 | 突增 |
| `oracledb_sysmetric_value{metric_name='CPU Usage Per Sec'}` | Oracle 内部 CPU 使用 | >70% |
| `oracledb_tablespace_used_percent` | 表空间使用率 | >85% |
| `oracledb_wait_time_user_io` | 用户 I/O 总等待 | 趋势分析 |
| `oracledb_wait_time_other` | 其他等待 | 锁相关 |
| `oracledb_cachehitratio_data` | 数据缓存命中率 | <90% |
| `oracledb_locks_count{type='TX', mode='Exclusive'}` | 行锁数量 | 突增 |
| `oracledb_longops_sofar` | 长操作进度（RMAN/统计信息收集） | 夜间任务监控 |

**SQL 级（通过 Micrometer JDBC 自定义 Observation）：**

| 指标 | Tags | 含义 |
|------|------|------|
| `jdbc.connections.query` | sql.name, sql.hash | SQL 执行耗时、次数 |
| `jdbc.row.count` | sql.hash | 返回行数（大结果集） |
| `jdbc.row.update.count` | sql.hash | 变更行数 |

#### 3.2.4 业务指标（Business KPI）—— 按需定义

| 业务域 | 指标示例 | 类型 | Tags |
|--------|----------|------|------|
| 订单 | `order.created.total` | Counter | channel, product_type |
| 订单 | `order.amount.total` | DistributionSummary | channel, region |
| 订单 | `order.created.duration` | Timer | - |
| 支付 | `payment.success.total` | Counter | channel, pay_method |
| 支付 | `payment.failure.total` | Counter | channel, fail_reason |
| 用户 | `user.login.total` | Counter | source, device |
| 库存 | `inventory.stock.gauge` | Gauge | sku_id, warehouse |

#### 3.2.5 可用性指标（SLO/SLI）

| 指标 | 公式 | SLO 目标 |
|------|------|----------|
| 服务可用性（SLI-availability） | `(http.server.requests 成功数 / 总数) × 100%` | ≥ 99.95% 月度 |
| 延迟（SLI-latency） | `http.server.requests P95 < 500ms` 的比例 | ≥ 99% |
| 数据层可用性 | `jdbc.query 成功 / 总数` | ≥ 99.99% |

### 3.3 Exemplar（范例链接）—— Metrics ⇄ Trace 关联

**核心价值：** 从 Prometheus 指标的异常点直接跳转到对应的 Trace 详情。

```
Prometheus 指标点（异常延迟 P99 突增）
        │ 点击 Exemplar 链接
        ▼
Grafana Tempo Trace 详情
        │ 点击 Loki 图标
        ▼
Loki 日志详情（同一 TraceID 下的所有日志）
```

**配置要点（Micrometer 1.13+）：**
```yaml
management:
  metrics:
    tags:
      application: ${spring.application.name}
    distribution:
      percentiles-histogram:
        http.server.requests: true    # 开启直方图，必须有 Exemplar
      percentiles:
        http.server.requests: [0.5, 0.9, 0.95, 0.99]
      sla:
        http.server.requests: 500ms, 1s, 2s, 5s
  observations:
    key-values:
      application: ${spring.application.name}
```

### 3.4 四层告警体系

| 层级 | 触发条件 | 响应级别 | 通知渠道 |
|------|----------|----------|----------|
| **P0 - 紧急** | 服务不可用（错误率 > 30%）、Oracle 连接池耗尽 | 立即响应（5min） | 电话 + 短信 + 钉钉 |
| **P1 - 严重** | 错误率 > 5%、P95 延迟 > 1s 持续 5min | 15min 响应 | 钉钉 @all + 短信 |
| **P2 - 一般** | 错误率 > 1%、连接池使用率 > 80%、GC 异常 | 1h 响应 | 钉钉群通知 |
| **P3 - 提醒** | 磁盘 < 30%、表空间 > 80%、非核心接口慢 | 工作时间处理 | 邮件 + 日报 |

---

## 4. 日志采集设计

### 4.1 日志方案对比

| 维度 | **方案 A：Loki（推荐）** | **方案 B：ELK（Elasticsearch + Logstash + Kibana）** | **方案 C：OpenSearch** |
|------|--------------------------|-----------------------------------------------------|------------------------|
| **存储成本** | ⭐⭐⭐⭐⭐ 对象存储，1/10 成本 | ⭐⭐ SSD 倒排索引，贵 | ⭐⭐ 同 ELK |
| **查询能力** | ⭐⭐⭐ 索引稀疏，靠 Label 过滤 | ⭐⭐⭐⭐⭐ 全文检索强 | ⭐⭐⭐⭐⭐ 同 ELK |
| **Trace 关联** | ⭐⭐⭐⭐⭐ Grafana 原生 Tempo 跳转 | ⭐⭐⭐ 需定制开发跳转 | ⭐⭐⭐ 同 ELK |
| **运维复杂度** | 低，无状态组件少 | 高，ES 集群调优复杂 | 高 |
| **日志量 100G/天** | 3 节点 MinIO + 2 节点 Loki | 至少 6 节点 ES（3主3从） | 6 节点 |
| **全文检索 Oracle SQL** | 一般（需用 Label 先过滤） | ✅ 优秀 | ✅ 优秀 |
| **SpringBoot 4.x 整合** | Loki Appender 直接发 | Logback + Filebeat | 同 ELK |

**推荐策略：**
- **主方案：Loki**（成本低，Grafana 统一面板，Trace 跳转丝滑）
- **补充：关键错误日志同步写入 Elasticsearch**（用于 SQL 关键字搜索）

### 4.2 日志流水线设计

```
┌──────────────────────────────────────────────────────────────────────┐
│  SpringBoot 微服务（3 个）                                             │
│   ┌──────────────────────────────────────────────────────────┐       │
│   │  SLF4J + Logback                                          │       │
│   │    ├─ ConsoleAppender           (stdout 本地调试)         │       │
│   │    ├─ LokiAppender              (直接推送 Loki ← 推荐)   │       │
│   │    ├─ FileAppender + 滚动       (/var/log/app/*.json)    │       │
│   │    └─ MDC 注入: traceId, spanId, userId, orderId         │       │
│   └──────────────────────────────────────────────────────────┘       │
└──────────────────────────────────────────────────────────────────────┘
             │                           │
     gRPC Loki 协议               本地文件 (兜底)
             │                           │
             ▼                           ▼
┌─────────────────────┐         ┌─────────────────┐
│   Loki Distributor  │         │  Promtail       │  ← DaemonSet / 服务
└─────────┬───────────┘         └────────┬────────┘
          │                              │
          └──────────────┬───────────────┘
                         ▼
               ┌───────────────────┐
               │   Loki Ingester    │  ← 写入内存 + WAL
               └─────────┬─────────┘
                         │  flush 每 30min
                         ▼
               ┌───────────────────┐
               │  对象存储 (MinIO) │  ← 温/冷数据低成本
               └───────────────────┘
                         │
                         ▼
               ┌───────────────────┐
               │   Grafana 查询    │  ← 统一 UI + Tempo + Prom
               └───────────────────┘
```

### 4.3 日志级别与规范

#### 4.3.1 分级策略

| 级别 | 使用场景 | 日志量占比（预估） | 采样 |
|------|----------|-------------------|------|
| **ERROR** | 异常未被捕获、影响用户请求、数据库操作失败 | < 0.1% | 100% 全采 |
| **WARN** | 重试成功、降级处理、非致命参数错误 | ~1% | 100% 全采 |
| **INFO** | 核心业务流程关键节点（创建订单、支付完成） | ~15% | 100%（或按 trace 采样） |
| **DEBUG** | 详细入参出参、循环内逻辑 | ~80% | **默认关闭**，仅 Arthas 动态开启 |
| **TRACE** | 逐行追踪、第三方库调试 | <5% | 生产禁用 |

#### 4.3.2 结构化日志字段规范（JSON 格式）

```json
{
  "timestamp": "2026-10-01T10:30:45.123Z",
  "level": "INFO",
  "thread": "http-nio-8080-exec-12",
  "logger": "com.example.biz.OrderService",
  "message": "Order created successfully",
  "application": "service-b",
  "host_ip": "10.0.1.23",
  "pod_name": "service-b-7d8f9-abc",
  "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",   // OTel W3C
  "span_id": "00f067aa0ba902b7",
  "user_id": "U123456",
  "request_id": "REQ-20261001-abcdef",
  "biz": {
    "order_id": "ORD20261001001",
    "amount": 999.00,
    "channel": "APP_ANDROID"
  },
  "stack_trace": null
}
```

### 4.4 MDC 自动注入（关键！）

**SpringBoot 4.x 自动配置（Micrometer Tracing 提供）：**
- 无需手动代码，`micrometer-tracing-bridge-otel` 自动将 traceId/spanId 放入 MDC
- 配合 `%mdc{traceId}` 在 Logback Pattern 中直接使用
- **关键点：异步场景需装饰线程池**

```java
// 线程池装饰器示例（Spring 6+ 已内置 ContextSnapshot）
@Bean
public TaskExecutorDecorator mdcTaskDecorator() {
    return runnable -> {
        ContextSnapshot snapshot = ContextSnapshot.captureAll();
        return snapshot.wrap(runnable);
    };
}
```

---

## 5. Arthas 在线诊断集成

### 5.1 Arthas 方案定位

| 能力 | 解决的痛点 | 不能做什么 |
|------|-----------|------------|
| 反编译查看运行时代码 | 怀疑线上代码没更新 | 替代 Trace（无历史记录） |
| 方法级观测（watch/trace/monitor） | 排查单个方法入参/返回/耗时 | 替代全链路 Trace |
| 热更新代码（redefine） | 紧急修复单行逻辑（不重启） | 改变方法签名/新增类 |
| 线程死锁检测（thread -b） | 诊断线程卡死/CPU 飙高 | 历史问题回放 |
| 对象统计（heapdump/class/classloader） | 内存泄漏定位辅助 | 替代专业 MAT 分析 |
| Logger 级别动态修改 | 临时开 DEBUG 日志 | 永久生效 |
| SQL 执行监控（ognl + datasource） | 某笔业务的 SQL 真实参数 | 替代 AWR/ASH |

### 5.2 三种部署模式对比

| 模式 | 说明 | 适用 | 推荐度 |
|------|------|------|--------|
| **模式 A：Arthas Tunnel 模式（推荐）** | Arthas 服务端统一管理，通过 WebSocket 连接所有 Agent | K8s/多节点/需要审计 | ⭐⭐⭐⭐⭐ |
| **模式 B：Sidecar 模式** | 每个 Pod 独立 Arthas Web Console | 单服务独立排查 | ⭐⭐⭐ |
| **模式 C：直接 Attach** | 手动 `java -jar arthas.jar <pid>` | 临时调试、单节点 | ⭐⭐⭐ 生产慎用 |

### 5.3 Arthas Tunnel 架构

```
┌─────────────────────────────────────────────────────────────────┐
│  K8s Cluster / 物理机                                            │
│                                                                  │
│  ┌─────────────┐   ┌─────────────┐   ┌─────────────┐            │
│  │  Service A  │   │  Service B  │   │  Service C  │            │
│  │ +ArthasAgent│   │ +ArthasAgent│   │ +ArthasAgent│            │
│  └──────┬──────┘   └──────┬──────┘   └──────┬──────┘            │
│         │  WebSocket      │  WebSocket      │  WebSocket        │
└─────────┼─────────────────┼─────────────────┼───────────────────┘
          │                 │                 │
          └─────────────────┴────────┬────────┘
                                    ▼
                        ┌──────────────────────┐
                        │   Arthas Tunnel Server│  ← 统一 Agent 注册
                        │   (SpringBoot 应用)   │
                        └──────────┬───────────┘
                                   │
                      ┌────────────┴─────────────┐
                      │   Arthas Dashboard UI    │  ← 选实例 → 进入
                      │   (Nginx 静态)            │    命令交互
                      └──────────────────────────┘
```

### 5.4 JDK 25 兼容性注意

Arthas 对 JDK 25 支持需要：
- Arthas 版本 ≥ 3.7.3（建议使用最新 release）
- Java 启动参数添加：`--add-opens java.base/java.lang=ALL-UNNAMED` 等模块开放
- **推荐：使用 Arthas SpringBoot Starter（无侵入）**

```xml
<dependency>
    <groupId>com.taobao.arthas</groupId>
    <artifactId>arthas-spring-boot-starter</artifactId>
    <version>3.7.4</version>
</dependency>
```

### 5.5 安全加固（必须！）

| 风险 | 加固措施 |
|------|----------|
| 任意命令执行 | Arthas Console 前置 SSO 鉴权 + IP 白名单 |
| 内存马植入 | 禁用 `redefine/retransform`（生产通过配置关闭） |
| 敏感信息泄露 | 命令审计日志（所有命令写入 ES/Loki 审计库） |
| 连接伪造 | Tunnel 连接 Token 校验 + TLS 加密 |
| 越权访问 | 按用户/角色分配可用命令白名单 |

### 5.6 典型诊断场景速查

| 场景 | Arthas 命令流 |
|------|--------------|
| **CPU 飙高** | `thread -n 5` → 定位到类方法 → `trace` 该方法 → `watch` 参数 |
| **接口慢** | Grafana Tempo 找到慢 Trace → 进入 Arthas → `trace <类方法> '#cost>1000'` |
| **异常无日志** | `watch <方法> '{params,throwExp}' 'throwExp!=null' -n 5 -x 3` |
| **怀疑代码未更新** | `jad com.example.service.OrderService createOrder` → 反编译 |
| **内存泄漏可疑** | `dashboard` → `heapdump /tmp/heap.hprof,live` → 下载用 MAT 分析 |
| **开 DEBUG 日志** | `logger --name com.example --level DEBUG` → 复现 → 再调回 INFO |
| **Oracle 连接池异常** | `ognl '@dataSource@getHikariPoolMXBean().getActiveConnections()'` |
| **获取真实 SQL 参数** | `watch oracle.jdbc.driver.OraclePreparedStatement execute '{params[0]}' -n 1 -x 2` |

---

## 6. SpringBoot 4.x 集成最佳实践

### 6.1 依赖清单（推荐 Maven pom.xml）

```xml
<!-- ======== 观测体系核心依赖（3 个服务都需要） ======== -->

<!-- Micrometer + OTel 桥接（SpringBoot 4.x 代替 Sleuth） -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>

<!-- Micrometer 指标 + Prometheus -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>

<!-- Loki 日志 Appender -->
<dependency>
    <groupId>com.github.loki4j</groupId>
    <artifactId>loki-logback-appender</artifactId>
    <version>1.5.2</version>
</dependency>

<!-- SpringBoot Actuator 暴露端点 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>

<!-- Oracle HikariCP 指标（SpringBoot 4 已自带，确认版本） -->
<dependency>
    <groupId>com.zaxxer</groupId>
    <artifactId>HikariCP</artifactId>
    <version>6.0.0</version>
</dependency>

<!-- Arthas（生产可选 profile 激活） -->
<dependency>
    <groupId>com.taobao.arthas</groupId>
    <artifactId>arthas-spring-boot-starter</artifactId>
    <version>3.7.4</version>
    <scope>runtime</scope>
</dependency>
```

### 6.2 配置文件模板（application-observability.yml）

```yaml
management:
  endpoints:
    web:
      exposure:
        include: "health,info,prometheus,metrics,loggers,heapdump,threaddump"
      base-path: /internal/actuator
  endpoint:
    health:
      show-details: when-authorized
      roles: OBSERVER
      probes:
        enabled: true
      group:
        readiness:
          include: db,redis,diskSpace
  metrics:
    tags:
      application: ${spring.application.name}
      env: ${spring.profiles.active}
      region: cn-north-1
    distribution:
      percentiles-histogram:
        http.server.requests: true
        http.client.requests: true
        jdbc.connections: true
      percentiles:
        http.server.requests: [0.5, 0.9, 0.95, 0.99, 0.999]
        jdbc.connections: [0.5, 0.95, 0.99]
      sla:
        http.server.requests: 100ms, 300ms, 500ms, 1s, 3s
  tracing:
    sampling:
      probability: 1.0   # 入口层单独配置
    baggage:
      correlation:
        enabled: true
      remote-fields:
        - x-request-id
        - x-user-id
      local-fields:
        - x-trace-id
  otlp:
    metrics.export:
      url: http://otel-collector:4318/v1/metrics
      step: 15s
    tracing.export:
      url: http://otel-collector:4318/v1/traces

# ============ Arthas 配置（仅 profile=prod-arthas 激活） ============
arthas:
  agent-id: ${spring.application.name}-${HOSTNAME:random}
  tunnel-server: ws://arthas-tunnel:7777/ws
  telnet-port: 0
  http-port: 0
  session-timeout: 1800

# ============ Oracle 连接池详细监控 ============
spring:
  datasource:
    hikari:
      pool-name: OracleHikariPool
      maximum-pool-size: 50
      minimum-idle: 10
      connection-timeout: 10000
      idle-timeout: 300000
      max-lifetime: 1800000
      leak-detection-threshold: 8000    # >8s 未归还连接打 WARN
      register-mbeans: true
      data-source-properties:
        oracle.jdbc.v$session.program: ${spring.application.name}
        oracle.jdbc.v$session.clientId: ${traceId:unknown}
```

### 6.3 全局 MDC + Baggage 传递配置类

```java
@Configuration
@EnableConfigurationProperties(ObservationProperties.class)
public class ObservationConfig {

    /** 异步线程池传递 Observation Context */
    @Bean
    public AsyncTaskExecutor asyncTaskExecutor(ObservationRegistry registry) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(64);
        executor.setQueueCapacity(1000);
        executor.setTaskDecorator(runnable -> ContextSnapshot.captureAll(registry).wrap(runnable));
        executor.setThreadNamePrefix("async-biz-");
        executor.initialize();
        return executor;
    }

    /** WebClient 传递 Trace + Baggage（服务间调用） */
    @Bean
    public WebClient webClient(ObservationRegistry registry) {
        return WebClient.builder()
                .filter(new MicrometerObservationClientFilter(
                        ClientRequestObservationConvention.DEFAULT, registry))
                .baseUrl("http://service-b")
                .build();
    }

    /** Oracle SQL 执行自定义 Observation（关联 trace + sql_id） */
    @Bean
    public JdbcObservationRegistryCustomizer oracleSqlObservation() {
        return registry -> {
            registry.addEventHandler(new OracleSqlIdExtractingHandler());
        };
    }
}
```

### 6.4 统一异常处理 + 错误码打点

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final MeterRegistry meterRegistry;
    private final Tracer tracer;

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handle(Exception e, HttpServletRequest req) {
        // 1. 指标计数
        Counter.builder("app.exceptions.total")
                .tag("exception", e.getClass().getSimpleName())
                .tag("uri", req.getRequestURI())
                .tag("app", "service-b")
                .register(meterRegistry)
                .increment();

        // 2. Trace 标记 ERROR
        Span currentSpan = tracer.currentSpan();
        if (currentSpan != null) {
            currentSpan.error(e);
            currentSpan.event("exception_handled",
                    Attributes.builder().put("message", e.getMessage()).build());
        }

        // 3. 日志包含完整 traceId
        log.error("Unhandled exception for uri={}", req.getRequestURI(), e);

        return ResponseEntity.status(500).body(new ErrorResponse(
                "SYS-500", e.getMessage(),
                MDC.get("traceId")   // 返回给前端 traceId，方便提单
        ));
    }
}
```

---

## 7. 部署架构与容量规划

### 7.1 部署拓扑

```
                           [ 负载均衡 / Ingress ]
                                     │
          ┌──────────────────────────┼──────────────────────────┐
          │                          │                          │
   ┌──────▼──────┐            ┌──────▼──────┐            ┌──────▼──────┐
   │  Service A  │            │  Service B  │            │  Service C  │
   │   (3 pods)  │            │   (3 pods)  │            │   (3 pods)  │
   └──────┬──────┘            └──────┬──────┘            └──────┬──────┘
          │                          │                          │
          │ OTLP (4317)              │ OTLP (4317)              │ OTLP
          └──────────────────────────┴──────────────────────────┘
                                     │
                          ┌──────────▼──────────┐
                          │  OTel Collector     │ ← 3 副本 (HPA by CPU)
                          │  + Tailsampling      │
                          └──────────┬──────────┘
                                     │
                   ┌─────────────────┼─────────────────┐
                   ▼                 ▼                 ▼
            [ Prometheus ]       [ Loki ]         [ Tempo ]
            3 replicas HA       3 ingesters+     3 ingesters+
            TSDB 60d retention   2 queriers       2 queriers
            + 2 Alertmanager    + MinIO 副本     + MinIO 副本
                                     │
                                     ▼
                              [ Grafana HA ]
                                 2 副本
                                     │
                              [ Arthas Tunnel ]
                                2 副本 HA
```

### 7.2 容量估算（3 微服务 × 3 实例，共 9 Pod）

| 组件 | 预估规格 | 存储 | 月度成本（估算） |
|------|---------|------|-----------------|
| **OTel Collector** | 3 × 2C4G | 无 | 中 |
| **Prometheus** | 3 × 8C16G | 500G SSD（60d） | 中高 |
| **Loki** | 3×4C8G（ingest）+ 2×4C4G（query） | 3T 对象存储（30d） | 低 |
| **Tempo** | 3×4C8G（ingest）+ 2×4C4G（query） | 1.5T 对象存储（15d） | 低 |
| **Grafana** | 2 × 2C4G | 10G（Sqlite/PG） | 低 |
| **Arthas Tunnel** | 2 × 2C4G | 无 | 低 |
| **Oracle Exporter** | 3 × 0.5C512M | 无 | 极低 |
| **合计** | 约 90C 200G | ~5T | **约 2~3 万/月（云服务器估算）** |

### 7.3 关键数据量预估

| 项目 | 每秒数值 | 每日数值 | 备注 |
|------|---------|---------|------|
| Metrics Samples | ~20,000 /s | ~17 亿条 | 9 服务 × 500 指标 × 压缩 |
| Trace Spans | ~5,000 /s | ~4.3 亿条 | 10% 采样；每请求 10 Span |
| Logs | ~200MB/min | ~288GB/天 | 结构化 JSON，INFO 为主 |
| Arthas 会话 | 并发 < 20 | ~100 次/天 | 人工操作低频 |

---

## 8. 风险与应对措施

| 风险 | 概率 | 影响 | 应对措施 |
|------|------|------|----------|
| **JDK 25 兼容性** | 中 | 高 | 提前在测试环境验证 OTel Agent 最新版、Arthas 3.7.3+；回滚 JDK 21 方案备用 |
| **SpringBoot 4.1.1 踩坑** | 中 | 中 | 锁定 Micrometer 1.14.x + OTel SDK 1.42.x 组合；订阅 Spring 发行注记 |
| **观测体系自身故障** | 低 | 中 | Collector 本地磁盘队列缓冲；OTel Exporter 设置超时 + 重试 + 熔断；应用失败不影响业务 |
| **Oracle 19c 新特性不兼容** | 低 | 中 | 连接串 `jdbc:oracle:thin:@//host:1521/PDB1`；最新 JDBC Driver 23.3；禁用 Oracle 12c 之后废弃参数 |
| **TraceID 丢失** | 中 | 高 | 异步线程池统一装饰器；MQ 消息 Header 透传；Feign/WebClient 拦截器全覆盖；集成测试校验 |
| **成本超支** | 中 | 中 | 分级采样（核心 100%/普通 10%/debug 0%）；冷热数据分层；Tempo/Loki 对象存储 |
| **敏感数据泄露** | 中 | 高 | OTel Collector `redaction` processor 脱敏；Arthas 命令审计 + 白名单；Grafana 行级权限 |
| **P0 告警风暴** | 中 | 中 | Alertmanager 分组+抑制+静默；先数据库故障再服务故障只告警根因 |

---

## 附录：实施路线图（建议 4 周完成）

| 阶段 | 时间 | 内容 | 交付物 |
|------|------|------|--------|
| **P1：基础落地** | W1 | Metrics + Grafana 面板 + 基础告警 | 三个服务 Actuator 接入；Oracle 大盘；P1/P2 告警规则 |
| **P2：Trace + Log** | W2 | OTel SDK 接入 + Loki 日志 + Tempo 查询 + 三支柱关联 | 完整链路通；Exemplar 跳转；错误日志关联 TraceID |
| **P3：Arthas + 精细化** | W3 | Arthas Tunnel 部署；Oracle 深度指标；业务 KPI 指标定义 | 在线诊断可用；SQL 级指标；SLO 仪表盘 |
| **P4：压测 + 演练** | W4 | 全链路压测 + 故障演练 + 成本优化 | 性能测试报告；RTO/RPO 验证；采样策略调优定稿 |

---

*文档结束*
