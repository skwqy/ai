# Spring Cloud Bus 深度源码解析（写给初学者的架构全景）

> **本文基于的源码**：`D:\code\3rd\spring-cloud-bus`，版本 **5.1.0-SNAPSHOT**（main 分支，2025.1 "Oakwood" 发布列车开发线，对应 Spring Cloud Commons / Stream / Function 均 5.1.0-SNAPSHOT，Git 最新提交 `d41120d`，2026-10-01）。bus id 组装规则引自 `D:\code\3rd\spring-cloud-commons`（`spring-cloud-commons` 模块的 `IdUtils`）。文中所有【源码证据】的文件路径与行号均为对上述快照实际读取所得。
>
> **版本取舍说明**：Spring Cloud Bus 是 Spring Cloud 家族里最小的项目之一——主模块只有 **38 个 Java 文件**，核心机制自 1.3（2017，Dalston）引入"服务 id + PathMatcher 寻址"以来就没有动过骨架。3.0（2020.0 列车）把 Stream 接入从 `@EnableBinding` 注解式换成函数式（`BusConsumer` + `StreamBridge`），4.0（2022.0 列车）完成 Jakarta 迁移，5.1（2025.1 列车）完成 Jackson 3（`tools.jackson`）迁移——这些演进都是"换发动机不换底盘"。因此本文内容对使用 4.x（Boot 3.x）的读者同样适用；差异点在 1.6 节逐项标注。
>
> **阅读约定**：每章"先白话、后源码"——先用一两句人话讲清"这是什么、为什么需要它"，再给出源码证据链（`模块/src/main/java/.../类名.java` + 行号 + 代码片段）。行号只对 5.1.0-SNAPSHOT 快照精确，读者用 IDEA 打开源码按类名 + 方法名定位即可。涉及 spring-cloud-commons 的证据会注明来源仓库。

## 如何读这份文档

如果你是 Spring Cloud Bus 初学者，推荐两遍读法：

- **第一遍（建立地图，1 小时）**：只读第一章（总览）每节的开头白话段、各章的"本章小结"节、以及第十章（贯通视图：三条时间线）。目标是能回答：Bus 和消息队列是什么关系？`POST /actuator/busrefresh` 之后发生什么？一条消息怎么知道"该不该由我处理"？为什么收到过的消息不会被再次广播？
- **第二遍（深入源码）**：对照每一章的【源码证据】逐行读。顺序建议：第二章（事件模型，一切的起点）→ 第三章（bus id 与寻址，Bus 最精巧的部分）→ 第五章（发送链路）→ 第六章（接收链路与 ACK，全文核心）→ 第七章（序列化，自定义事件的必经之路）→ 第四章（装配细节，可跳读）→ 第八章（三个内置功能的联动）→ 第九章（传输层与运维随用随查）。

---

# 一、总览：Spring Cloud Bus 的定位、设计哲学与整体架构

## 1.1 一句话定位

**Spring Cloud Bus 是"把 Spring 的事件机制（ApplicationEvent）搬上消息中间件"的分布式事件广播器**：它用轻量级消息代理（RabbitMQ / Kafka / 任意 Spring Cloud Stream binder）把一个分布式系统里的所有 Spring Boot 实例连成一条"总线"，任何一个节点发布的 `RemoteApplicationEvent` 都会被广播到所有节点，各节点再按"目的地规则"决定处理还是忽略。官方文档的自述（`docs/modules/ROOT/pages/intro.adoc`）：

> *"Spring Cloud Bus links the nodes of a distributed system with a lightweight message broker. This broker can then be used to broadcast state changes (such as configuration changes) or other management instructions. A key idea is that the bus is like a **distributed actuator** for a Spring Boot application that is scaled out."*

两个容易混淆的点，先立好坐标：

1. **Bus 不是配置中心，也不是 MQ 的替代品**。它不存数据、不做业务消息的可靠投递，它只干一件事：**把"管理指令"（刷新配置、修改环境、关停实例、自定义运维事件）广播出去**。它是套在 MQ 之上的一个极薄的"事件协议层"——消息怎么可靠传递是 RabbitMQ/Kafka 的事，Bus 只负责"事件的建模、寻址、序列化和防自环"。
2. **Bus 的最佳搭档是 Spring Cloud Config**。Config 解决"配置存在哪、怎么拉"，但它天生是拉模型（见《Spring Cloud Config 深度源码解析》第 1.1 节）；"配置变了，通知所有实例去重新拉"这一步，靠的就是 Bus 广播一条 `RefreshRemoteApplicationEvent`。一个 `/actuator/busrefresh` 调用，全集群实例各自执行一次本地的 `/actuator/refresh`。

一句话记住它：**Bus = 分布式的 Actuator**。Actuator 的端点只能打到一个实例上，Bus 把 `/actuator/*` 的能力"横向铺开"到了整个集群。

## 1.2 设计哲学：读源码前先记住五句话

1. **一切皆 RemoteApplicationEvent**。Bus 对"要传什么"的全部建模就是一个抽象类：`RemoteApplicationEvent extends ApplicationEvent`，外加三个字段——`originService`（谁发的）、`destinationService`（发给谁，支持通配符）、`id`（UUID，用于 ACK 关联与去重）（`event/RemoteApplicationEvent.java:32-75`）。刷新、改环境、关停、自定义事件，全是这个类的子类。新加一种"管理指令" = 写一个子类 + 一个本地 Listener，Bus 本体零改动。
2. **寻址即路径匹配**。"这条消息归不归我处理"不依赖注册中心、不维护成员列表，而是把 bus id（形如 `app:profile:port:random` 的冒号分隔串）当作**路径**，用 Spring core 的 `AntPathMatcher`（分隔符换成 `:`）做模式匹配（`PathServiceMatcher.java:58-75`）。`**` 匹配所有实例，`customers:**` 匹配 customers 服务所有实例。没有中心协调者，这是 Bus "无脑扩容、天生广播"的根源（见第三章）。
3. **进程内一条链，进程间一条桥**。Bus 内部有两条清晰分界的事件链路：**本地链路**沿用 Spring 的 `ApplicationEventPublisher`/`ApplicationListener`（端点发事件、Listener 处理事件，全是进程内事件）；**跨进程链路**收敛到一个只有单个方法的接口 `BusBridge#send(RemoteApplicationEvent)`（`BusBridge.java:27-31`）。本地事件怎么"上桥"、桥上来的消息怎么"下桥回本地"，分别在第五章、第六章展开。**换传输实现 = 换一个 BusBridge**，核心逻辑一行不动。
4. **传输层委托给 Spring Cloud Stream**。Bus 自己不写一行 RabbitMQ/Kafka 代码，绑定拓扑（topic 交换机、匿名队列、消息转换）全部复用 Stream 的函数式编程模型：启动时由 `BusEnvironmentPostProcessor` 往环境里"塞"三个属性——把 `busConsumer` 追加进 `spring.cloud.function.definition`、把函数绑定别名成 `springCloudBusInput/Output`、把两个绑定的 destination 指到 `spring.cloud.bus.destination`（默认 `springCloudBus`） topic（`BusEnvironmentPostProcessor.java:49-88`）。**Bus 是 Stream 最薄的一个使用者**。
5. **回环靠"两层 isFromSelf 过滤"，可靠靠 ACK 观测**。广播天然带来两个问题：自己收到自己发的消息怎么办？消息发出去了怎么知道谁处理了？Bus 的答案是：用 `ServiceMatcher#isFromSelf` 在"本地事件上桥"和"桥上消息落地"两处各挡一道（防自环）；为每条处理过的消息回发一条 `AckRemoteApplicationEvent` 广播（ACK 协议），谁收到、谁忽略，靠监听 ACK 事件来**观测**——注意是观测而不是保证，Bus 不做可靠投递的应答重试（见第六章）。

## 1.3 模块分层全景

spring-cloud-bus 仓库 5.1.0-SNAPSHOT 实测共 7 个工程模块。**三个 starter 全是纯依赖聚合 POM（一行 Java 都没有）**，所有代码集中在 `spring-cloud-bus` 核心模块。按"用户感知"分层如下：

```
┌─────────────────────────── 传输绑定层（可选三选一）───────────────────────────┐
│  spring-cloud-starter-bus-amqp   = spring-cloud-bus + spring-cloud-starter-  │
│                                    stream-rabbit（RabbitMQ binder）          │
│  spring-cloud-starter-bus-kafka  = spring-cloud-bus + spring-cloud-starter-  │
│                                    stream-kafka（Kafka binder）              │
│  spring-cloud-starter-bus-stream = spring-cloud-bus + spring-cloud-stream    │
│                                    （任意 binder，理论上 redis/nats 也行）    │
├────────────────────────────── 核心模块 spring-cloud-bus ─────────────────────┤
│  根包 org.springframework.cloud.bus：装配与枢纽                                │
│    BusAutoConfiguration        ← 装配中枢（Listener/Consumer/Destination）    │
│    BusEnvironmentPostProcessor ← 启动前埋三颗"钉子"（函数定义/绑定/bus id）  │
│    PathServiceMatcherAutoConfiguration ← bus id 匹配器装配                    │
│    BusStreamAutoConfiguration  ← StreamBusBridge 装配（有 Stream 才生效）     │
│    BusRefreshAutoConfiguration / BusShutdownAutoConfiguration ← 功能开关      │
│    RemoteApplicationEventListener / BusConsumer ← 本地⇄总线 的两个"港口"     │
│    BusBridge / StreamBusBridge ← 传输抽象与 Stream 实现                       │
│    ServiceMatcher / PathServiceMatcher / DefaultBusPathMatcher ← 寻址三件套   │
│    BusProperties / BusConstants / ConditionalOnBusEnabled                     │
│  endpoint 包：三个 Actuator 端点                                               │
│    RefreshBusEndpoint(busrefresh) / EnvironmentBusEndpoint(busenv)            │
│    ShutdownBusEndpoint(busshutdown) / AbstractBusEndpoint                     │
│  event 包：事件模型与内置 Listener                                              │
│    RemoteApplicationEvent + 6 个事件类 + Destination/PathDestinationFactory   │
│    RefreshListener / EnvironmentChangeListener / ShutdownListener / TraceListener│
│  jackson 包：多态序列化                                                         │
│    BusJacksonAutoConfiguration(+BusJacksonMessageConverter) / SubtypeModule    │
│    @RemoteApplicationEventScan + RemoteApplicationEventRegistrar               │
├────────────────────────────── 依赖支撑（外部）────────────────────────────────┤
│  spring-cloud-stream（函数式绑定 + StreamBridge + 消息转换）                    │
│  spring-cloud-context（ContextRefresher / EnvironmentManager：刷新落地执行者）  │
│  spring-cloud-commons（IdUtils：bus id 组装）                                  │
│  spring-boot-actuator（端点基建）+ Jackson 3（tools.jackson，wire format）     │
└──────────────────────────────────────────────────────────────────────────────┘
```

【源码证据】`pom.xml:28-36`（`<modules>` 列出 spring-cloud-bus-dependencies / spring-cloud-bus / spring-cloud-bus-tests / 三个 starter / docs）；`spring-cloud-starter-bus-amqp/pom.xml`（依赖仅 `spring-cloud-starter-stream-rabbit` + `spring-cloud-bus` 两项）。

核心模块的包结构一览（38 个 main Java 文件的全部清单，读完本文你就认识它们中的每一个）：

| 包 | 类 | 一句话职责 |
|---|---|---|
| 根包 | `BusAutoConfiguration` | 装配中枢：Listener/Consumer/Destination.Factory/Trace/Env |
| 根包 | `BusEnvironmentPostProcessor` | 启动前注入函数定义、绑定默认值、bus id 默认值 |
| 根包 | `PathServiceMatcherAutoConfiguration` | 装配 `busPathMatcher`（AntPathMatcher":"）与 `PathServiceMatcher` |
| 根包 | `BusStreamAutoConfiguration` | 有 Stream 时装配 `StreamBusBridge`，排序先行 |
| 根包 | `BusRefreshAutoConfiguration` / `BusShutdownAutoConfiguration` | 刷新/关停功能的 Listener 与端点装配 |
| 根包 | `RemoteApplicationEventListener` | 本地 RemoteApplicationEvent → 上桥发送 |
| 根包 | `BusConsumer` | 总线消息 → 判定 → 本地发布 + 回 ACK |
| 根包 | `BusBridge` / `StreamBusBridge` | 传输抽象 / Stream 实现 |
| 根包 | `ServiceMatcher` / `PathServiceMatcher` / `DefaultBusPathMatcher` / `BusPathMatcher` | 寻址接口 / 实现 / 多 profile 匹配器 / 装配限定注解 |
| 根包 | `BusProperties` / `BusConstants` / `ConditionalOnBusEnabled` | 参数 / 常量 / 总开关注解 |
| `endpoint` | `RefreshBusEndpoint` / `EnvironmentBusEndpoint` / `ShutdownBusEndpoint` / `AbstractBusEndpoint` | busrefresh / busenv / busshutdown 端点 |
| `event` | `RemoteApplicationEvent`（抽象基类） | originService/destinationService/id 三要素 |
| `event` | `RefreshRemoteApplicationEvent` / `EnvironmentChangeRemoteApplicationEvent` / `ShutdownRemoteApplicationEvent` / `AckRemoteApplicationEvent` / `UnknownRemoteApplicationEvent` | 五个跨进程事件 |
| `event` | `SentApplicationEvent` | 本地审计事件（不上总线） |
| `event` | `Destination` / `PathDestinationFactory` | 目的地抽象与 `:**` 补全工厂 |
| `event` | `RefreshListener` / `EnvironmentChangeListener` / `ShutdownListener` / `TraceListener` | 四个本地 Listener |
| `jackson` | `BusJacksonAutoConfiguration`（含包私有 `BusJacksonMessageConverter`） | 消息转换器与子类型注册 |
| `jackson` | `@RemoteApplicationEventScan` / `RemoteApplicationEventRegistrar` / `SubtypeModule` | 自定义事件包扫描注册 |

## 1.4 模块依赖图（以各模块 pom.xml 的依赖实证）

```
                    spring-cloud-dependencies (BOM)
                                 │
      ┌──────────────────────────┼────────────────────────────┐
      │                          │                            │
spring-cloud-bus            spring-cloud-starter-bus-*      spring-cloud-config
      │                        (amqp/kafka/stream：            (monitor 模块反向依赖
      │                         纯 POM 聚合，多带一个              spring-cloud-bus)
      │                         binder starter)
      ├── spring-cloud-stream ──────────► binder（rabbit/kafka/...）
      │        │
      │        └── spring-messaging / spring-cloud-function
      ├── spring-cloud-context（ContextRefresher、EnvironmentManager —— 可选依赖）
      ├── spring-cloud-commons（IdUtils 等）
      ├── spring-boot-actuator-autoconfigure（端点条件装配）
      └── tools.jackson（Jackson 3：databind + 可选 cbor）
```

【源码证据】`pom.xml:81-85`：`<spring-cloud-commons.version>`、`<spring-cloud-stream.version>`、`<spring-cloud-function.version>` 均为 `5.1.0-SNAPSHOT`——Bus 与 Stream 同列车同版本号发布。三个 starter 的依赖清单见上表，无任何代码与资源文件。

值得注意的"反向依赖"：**Spring Cloud Config 的 monitor 模块依赖 Bus**（Git webhook → `/monitor` 端点 → 经 Bus 广播 `RefreshRemoteApplicationEvent`）。也就是说在"配置中心 + Bus"这套标准组合里，Bus 是被 Config 复用的基础设施，而不是反过来——这也是理解 Bus 定位的一个好视角：**它是 Spring Cloud 的"系统事件总线"层，谁都可以往上面发指令**。

## 1.5 关键问题 → Bus 方案映射（全文导览）

| 你想搞懂的问题 | 答案在哪个类 | 详见 |
|---|---|---|
| 一条刷新指令长什么样，在线上怎么传？ | `RemoteApplicationEvent` + Jackson `@JsonTypeInfo` | 第二章、7.2 |
| "这条消息归不归我处理"怎么判定？ | `PathServiceMatcher#isForSelf` + `DefaultBusPathMatcher` | 第三章 |
| bus id（`app:profile:port:random`）是怎么拼出来的？ | `BusEnvironmentPostProcessor` + commons `IdUtils` | 3.2、4.2 |
| 启动时 Bus 做了哪些"看不见"的配置？ | `BusEnvironmentPostProcessor`（三颗钉子） | 4.2 |
| `POST /actuator/busrefresh` 到 MQ 发出之间发生了什么？ | `RefreshBusEndpoint` → `RemoteApplicationEventListener` → `StreamBusBridge` | 第五章 |
| MQ 消息到本地刷新之间发生了什么？ACK 是怎么回的？ | `BusConsumer#accept` + `AckRemoteApplicationEvent` | 第六章 |
| 为什么自己发的消息不会被自己再处理一遍？ | 两层 `isFromSelf` 过滤 + ACK 特判 | 6.5 |
| 自定义事件怎么让别的服务认出来？ | `@JsonTypeInfo` 多态 + `@RemoteApplicationEventScan` 包扫描 | 第七章 |
| 对方 classpath 里没有我的事件类，消息会炸吗？ | `UnknownRemoteApplicationEvent` 兜底 | 7.5 |
| 刷新最终是谁执行的？ | `RefreshListener` → spring-cloud-context `ContextRefresher` | 8.2 |
| RabbitMQ/Kafka 上 Bus 的消息拓扑长什么样？ | Stream 匿名绑定（无 group → 每实例独立队列/消费组） | 9.2 |
| 所有参数一览？ | `BusProperties` + `@ConditionalOnProperty` 开关 | 9.3 |

## 1.6 版本演进：1.x → 5.x 关键变化

Spring Cloud Bus 的版本号与发布列车严格对齐（每列车一个 Bus minor 版本）：

| Bus | 发布列车（代号） | Boot 基线 | 关键变化 |
|---|---|---|---|
| 1.3 | Dalston（2017） | 1.x | **服务 id 寻址模型确立**：`app:index:id` 格式 + `AntPathMatcher(":")` + destination 通配符，`/bus/refresh`、`/bus/env` 端点 |
| 2.x | Finchley → Hoxton（2018–2020） | 2.x | Boot 2 / Spring 5 迁移；端点迁到 `/actuator/bus*` |
| 3.0 | 2020.0（Ilford，2020-12） | 2.4 | **函数式改造（本架构的定型点）**：废弃 `@EnableBinding` 注解式绑定，改为 `BusConsumer implements Consumer<RemoteApplicationEvent>` + `StreamBridge` 发送；新增 `BusEnvironmentPostProcessor` 注入函数定义与绑定默认值；`TraceRepository` 集成开始随 Boot 演进调整 |
| 3.1 | 2021.0（Jubilee） | 2.6 | 维护线 |
| 4.0 | 2022.0（Kilburn，2022-12） | 3.0 | **Jakarta EE 迁移**（Boot 3 全家桶），API 无大变化 |
| 4.1 | 2023.0（Leyton） | 3.2 | 维护线；4.x 线期间加入 `spring.cloud.config.name` 的匹配兜底（见 3.5） |
| 4.2 | 2024.0（Moorgate） | 3.4 | 维护线 |
| 5.0 | 2025.0（Northfields） | 3.5 | 维护线 |
| 5.1 | 2025.1（Oakwood） | 4.0 | **本快照**：Jackson 3（`tools.jackson`）迁移；新增 CBOR 消息转换器；`AckRemoteApplicationEvent`/`ShutdownRemoteApplicationEvent` 增加 `@JsonCreator` 构造适配 Jackson 3 |

【源码证据】`pom.xml:9-11`（`5.1.0-SNAPSHOT`）；`jackson/BusJacksonAutoConfiguration.java` 中全量使用 `tools.jackson.databind.*`（Jackson 3 新包名）与 `tools.jackson.dataformat.cbor.*`（CBOR）。

对初学者的意义：**直接学 5.x 就是从 3.0 定型后的主线学起**。网上大量教程还在讲 `@EnableBinding(Sink.class)`/`@StreamListener`——那是 2.x 的旧架构，3.0（2020 年底）起已全部替换为"函数 + StreamBridge"，读旧文时注意甄别。

## 1.7 全文章节地图

- **第二章**：事件模型——`RemoteApplicationEvent` 三要素、7 个内置事件、Destination 补全规则。Bus 世界观的"名词表"。
- **第三章**：身份与寻址——bus id 怎么拼、`ServiceMatcher` 的两问（isForSelf/isFromSelf）、多 profile 展开匹配、configName 兜底。Bus 最精巧的设计。
- **第四章**：装配与贯通——`AutoConfiguration.imports` 六张装配表、`BusEnvironmentPostProcessor` 的三颗"钉子"、装配顺序链。
- **第五章**：发送链路——从 HTTP 端点到 MQ 的七跳全景，`RemoteApplicationEventListener` 的"上桥闸口"。
- **第六章**：接收链路——`BusConsumer#accept` 逐行解析、ACK 协议、防自环双保险、Trace 现状。全文核心。
- **第七章**：序列化——多态 JSON、包扫描注册子类型、`UnknownRemoteApplicationEvent` 兜底、Jackson 3 与 CBOR。
- **第八章**：三个内置功能——Refresh/Env/Shutdown 与 spring-cloud-context 的联动、端点暴露与安全。
- **第九章**：传输层与运维——starter 选择、RabbitMQ/Kafka 拓扑、全参数手册、自定义 BusBridge、故障排查。
- **第十章**：贯通视图——启动、发送、接收三条时间线串起全部类。
- **第十一章**：附录——速查表与源码阅读入口清单。

---

# 二、地基：事件模型——"跨进程事件"的建模

## 2.1 先白话：Bus 到底传什么

Spring 进程内的事件机制你已经熟悉（见《Spring Framework.md》4.5 节）：`ApplicationEventPublisher.publishEvent()` 发，`ApplicationListener` 收，同一个 JVM 里瞬间送达。但这个机制有两个天生局限：**出不了进程**（对象引用传不过去）和**不知道发给谁**（进程内没有"目的地"概念）。

Bus 的做法小得出奇：定义一个抽象类 `RemoteApplicationEvent`，给它加三个字段，让它既能被 Jackson 序列化成 JSON 扔进 MQ，又保留 `ApplicationEvent` 的身份（进程内照常走事件广播）：

```
                 ┌──────────────────────────────────────────┐
                 │  RemoteApplicationEvent                  │
                 │  extends ApplicationEvent                 │
                 ├──────────────────────────────────────────┤
   三要素        │  originService      谁发的（bus id）      │
                 │  destinationService 发给谁（支持通配符）   │
                 │  id                 UUID（ACK 关联/去重） │
                 ├──────────────────────────────────────────┤
   进程内身份     │  source / timestamp（继承自 ApplicationEvent）│
                 └──────────────────────────────────────────┘
```

加上一个 `type`（Jackson 多态类型标记，来自类名），这就是 Bus 的全部"协议头"。**所谓总线，就是围绕这三个字段展开的发送、寻址、回执体系。**

## 2.2 RemoteApplicationEvent：三要素与两条构造路径

【源码证据】`spring-cloud-bus/src/main/java/org/springframework/cloud/bus/event/RemoteApplicationEvent.java:30-75`

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonIgnoreProperties("source")
public abstract class RemoteApplicationEvent extends ApplicationEvent {

    private static final Object TRANSIENT_SOURCE = new Object();
    private static final String TRANSIENT_ORIGIN = "____transient_origin_service___";
    private static final String TRANSIENT_DESTINATION = "____transient_destination___";
    protected static final PathDestinationFactory DEFAULT_DESTINATION_FACTORY = new PathDestinationFactory();

    private String originService;
    private String destinationService;
    private String id;

    protected RemoteApplicationEvent() {
        // for serialization libs like jackson
        this(TRANSIENT_SOURCE, TRANSIENT_ORIGIN, DEFAULT_DESTINATION_FACTORY.getDestination(TRANSIENT_DESTINATION));
    }

    protected RemoteApplicationEvent(Object source, String originService, Destination destination) {
        super(source);
        // ... originService 判定 TRANSIENT 占位符后赋值
        this.destinationService = destination.getDestinationAsString();
        Assert.hasText(destinationService, "destinationService may not be empty");
        this.id = UUID.randomUUID().toString();
    }
}
```

三个细节值得停下来看：

1. **`@JsonTypeInfo(use = Id.NAME, property = "type")`（第 32 行）**：序列化时 JSON 里会多一个 `"type"` 字段，值默认是类的 simple name（如 `"RefreshRemoteApplicationEvent"`）。反序列化时 Jackson 靠它决定实例化哪个子类——这是跨进程多态的锚点，第七章整章都在围绕它展开。
2. **`@JsonIgnoreProperties("source")`**：`ApplicationEvent` 的 `source` 字段可能是任意对象（这里通常是端点实例），不序列化、反序列化时直接忽略。**Bus 传的是"事件信封"，不传业务对象**——想带数据请自己作为字段放进子类（如 `EnvironmentChangeRemoteApplicationEvent.values`）。
3. **无参构造 + 占位符常量（`TRANSIENT_ORIGIN` 等）**： Jackson 反序列化会先调无参构造再逐字段赋值，此时 origin/destination 还没进来，用占位符绕过 `Assert.hasText` 校验。子类（如 `ShutdownRemoteApplicationEvent`）的无参构造都标注了 `@JsonCreator` 以适配 Jackson 3。

**两条构造路径**：业务代码用四参构造（`source, originService, Destination`，id 自动生成 UUID）；Jackson 反序列化用无参构造。`Destination` 参数（接口，见 2.4 节）最终被 `getDestinationAsString()` 成字符串存进 `destinationService`。

## 2.3 内置事件家族：5 个跨进程 + 1 个本地审计

`event` 包共 7 个事件类，按"是否真的上总线"分两类：

| 事件类 | 方向 | 语义 | 目的地默认值 |
|---|---|---|---|
| `RefreshRemoteApplicationEvent` | 出 | "去重新拉配置并刷新 RefreshScope"（与 `/actuator/refresh` 等效） | `**`（全体） |
| `EnvironmentChangeRemoteApplicationEvent` | 出 | "把 key=value 写进各实例的 Environment"（与 `/actuator/env` 的 POST 等效） | `**` |
| `ShutdownRemoteApplicationEvent` | 出 | "优雅关停"（与 `/actuator/shutdown` 等效） | `**` |
| `AckRemoteApplicationEvent` | 出（回执） | "我（originService）处理过 id=xxx 的事件了" | `**`（回执广播） |
| `UnknownRemoteApplicationEvent` | （不会产生） | 反序列化兜底容器：对方发来我认不出的 type 时装入 | 固定 `unknown` |
| `SentApplicationEvent` | **本地** | "某事件已发出"的审计信号，**不是 RemoteApplicationEvent 子类，永不上总线** | — |

每个出站事件子类都极薄——例如刷新事件全文只有一个带参构造（`event/RefreshRemoteApplicationEvent.java:35-38`）：

```java
public RefreshRemoteApplicationEvent(Object source, String originService, Destination destination) {
    super(source, originService, destination);
}
```

**"指令"的全部语义由监听它谁来处理决定**（`RefreshListener` 调 `ContextRefresher.refresh()`），事件类本身只是信封。这也是"设计哲学 1"的具象化。

两个特殊事件多说一句：

- **`AckRemoteApplicationEvent`**（`event/AckRemoteApplicationEvent.java:30-135`）多三个字段：`ackId`（被确认的那条事件的 id）、`ackDestinationService`（原事件的目的地）、`event`（原事件的 `Class`）。`setEventName(String)`（第 77 行）用 `Class.forName` 反射还原事件类——**如果本进程 classpath 里没有那个类，降级为 `UnknownRemoteApplicationEvent.class`**，ACK 链路不会因此中断。
- **`UnknownRemoteApplicationEvent`**（`event/UnknownRemoteApplicationEvent.java:33-46`）：构造时 originService 传**空串**、destination 固定 `"unknown"`——注释写明是"避免 AntPathMatcher 空指针"。空串作为匹配模式永远 false，所以这个事件**天然不会被任何实例当成"发给我的"**，它只是把"认不出的消息"装起来留证据（typeInfo = 认不出的 type 名，payload = 原始字节）。

## 2.4 Destination 与 PathDestinationFactory："`:**`" 的补全规则

destination 字符串在事件生命周期里不是"原样存储"，而是经过一次**规范化补全**。接口只有一个方法（`event/Destination.java`）：

```java
public interface Destination {
    String getDestinationAsString();
    interface Factory {
        Destination getDestination(String originalDestination);
    }
}
```

补全逻辑全在默认实现 `PathDestinationFactory` 里（【源码证据】`event/PathDestinationFactory.java:23-40`）：

```java
public Destination getDestination(String originalDestination) {
    String path = originalDestination;
    if (path == null) {
        path = "**";
    }
    // 如果不是通配符，且":"不超过 1 个、结尾不是":**"，则补全为"xxx:**"
    if (!"**".equals(path)) {
        if (StringUtils.countOccurrencesOf(path, ":") <= 1 && !StringUtils.endsWithIgnoreCase(path, ":**")) {
            path = path + ":**";
        }
    }
    ...
}
```

**口诀：不写就全体，写了自动带上"它的所有实例"**：

| 端点收到的 destination | 补全后 | 含义 |
|---|---|---|
| （不传） | `**` | 全体实例 |
| `customers` | `customers:**` | customers 服务的所有实例 |
| `customers:9000` | `customers:9000:**` | customers:9000 这个 bus id 的所有变体（含 profile 段） |
| `customers:**` | `customers:**`（不变） | 显式写全 |

这就是官方文档 `/busrefresh/customers:9000` 示例能"指定实例"的机制基础（配合 bus id 的格式，见 3.2）。注意补全只在 `:` 不超过 1 个时进行——bus id 本身有 3~4 段，写全 `customers:dev:9000:abc` 这样超过两段的串视为已明确指定，不再追加 `:**`。

端点侧的 destination 来自 URL 路径的**剩余所有段**（`@Selector(match = Match.ALL_REMAINING)`）用 `:` 拼回（如 `endpoint/RefreshBusEndpoint.java:39-43`），所以 URL 里写 `/busrefresh/customers:9000` 会被还原成字符串 `customers:9000` 再走补全。

## 2.5 本章小结

- Bus 的协议核心是 `RemoteApplicationEvent`：`originService` / `destinationService` / `id` 三要素 + Jackson `type` 多态标记；`source` 不序列化，传的是"指令信封"不是业务对象。
- 五个跨进程事件（Refresh/EnvironmentChange/Shutdown/Ack/Unknown）+ 一个本地审计事件（Sent），子类全部极薄——语义在 Listener 里，不在事件里。
- destination 有规范化补全规则："不写全体，写了带实例"，`PathDestinationFactory` 的三行 if 是唯一实现点。
- `UnknownRemoteApplicationEvent` 用"空 origin + unknown 目的地"的构造技巧天然免疫寻址，专门当"认不出的消息"的容器。

---

# 三、身份与寻址：bus id 与 ServiceMatcher

## 3.1 先白话：为什么需要 bus id

消息广播出去之后，每个实例要回答两个问题：

1. **这条消息是发给我（或我所在的服务）的吗？**（isForSelf——决定"处理不处理"）
2. **这条消息是我自己发出来的吗？**（isFromSelf——决定"要不要再次处理/上桥"）

要回答这两个问题，每个实例必须先有一个**可被模式匹配的身份字符串**，这就是 bus id。Bus 的设计是把身份做成**冒号分隔、从粗到细的层级路径**，然后用 `AntPathMatcher`（分隔符换成 `:`）做匹配——于是"发给某个服务的所有实例""发给某个特定实例""发给全体"统一成一个匹配表达式问题，**不需要注册中心、不需要维护成员表**。

## 3.2 bus id 默认值的组装：IdUtils 与 BusEnvironmentPostProcessor

bus id 可以用 `spring.cloud.bus.id` 显式指定；不指定时由 `BusEnvironmentPostProcessor` 在启动早期填默认值（【源码证据】`BusEnvironmentPostProcessor.java:76-82`）：

```java
if (!environment.containsProperty(PREFIX + ".id")) {
    String unresolvedServiceId = IdUtils.getUnresolvedServiceId();
    if (StringUtils.hasText(environment.getProperty("spring.profiles.active"))) {
        unresolvedServiceId = IdUtils.getUnresolvedServiceIdWithActiveProfiles();
    }
    defaults.put(PREFIX + ".id", unresolvedServiceId);
}
```

默认模板来自 spring-cloud-commons 的 `IdUtils`（【源码证据】`spring-cloud-commons/src/main/java/org/springframework/cloud/commons/util/IdUtils.java:30-32`）：

```java
// 无激活 profile 时：
"${vcap.application.name:${spring.application.name:application}}:
 ${vcap.application.instance_index:${spring.application.index:${local.server.port:${server.port:8080}}}}:
 ${vcap.application.instance_id:${cachedrandom....value}}"
// 有激活 profile 时，第二段插入 profiles：
"app:${spring.profiles.active}:port:random"
```

剥掉嵌套占位符，实际就是一个四段式：

```
app : profiles? : port : random
 │        │       │       └─ 实例级随机数（cachedrandom，同实例重启不变）
 │        │       └─ local.server.port → server.port（层层回退）
 │        └─ spring.profiles.active（有才出现，如 dev,cloud）
 └─ vcap.application.name → spring.application.name → "application"
```

举两个真实例子：本地起一个 8080 端口的 customers 服务（带 dev profile）→ `customers:dev:8080:<random>`；Cloud Foundry 上会优先用 `vcap.application.*` 的名字与实例索引。

**注意这些占位符是"未解析"状态直接放进默认属性源的**（优先级最低，见 4.2 节），真正读取时才解析——所以配置文件后加载的 `server.port` 也能生效。

## 3.3 PathServiceMatcher：isForSelf 与 isFromSelf 的两问

【源码证据】`PathServiceMatcher.java:52-75`

```java
public boolean isFromSelf(RemoteApplicationEvent event) {
    String originService = event.getOriginService();   // 注意：origin 作为"模式"
    String serviceId = getBusId();
    return this.matcher.match(originService, serviceId);
}

public boolean isForSelf(RemoteApplicationEvent event) {
    String destinationService = event.getDestinationService();
    if (destinationService == null || destinationService.trim().isEmpty()
            || this.matcher.match(destinationService, getBusId())) {
        return true;
    }
    // 用所有潜在的配置名再试一遍（见 3.5）
    for (String configName : this.configNames) {
        if (this.matcher.match(destinationService, configName)) {
            return true;
        }
    }
    return false;
}
```

读这段代码有两个"反直觉但正确"的点：

1. **`match(pattern, path)` 的第一个参数是模式**。`isFromSelf` 把**事件里的 origin** 当模式、自己的 id 当路径——正常情况下 origin 是对方的明确 id（无通配符），`match("stores:9000", "customers:8080")` 为 false；只有当 origin 就是自己的 id 时才 true。妙处在于：如果某个事件 origin 带通配符（比如自己手写的 `*`），也能正确判定"来自自己一方"。
2. **`isForSelf` 对空 destination 直接放行**——配合 2.4 节的补全规则，"不写目的地"就是广播，人人有份。

matcher 本体在装配时注入（见 3.4），它是一个 `AntPathMatcher(":")` 再包一层多 profile 逻辑。

## 3.4 DefaultBusPathMatcher：多 profile 的展开匹配

问题场景：bus id 第二段可能是 `dev,cloud`（多 profile 逗号并列），那么 `customers:dev,cloud:8080:abc` 这种 id 与模式 `customers:dev:**` 用 AntPathMatcher 直接匹配会失败——`dev,cloud` 不是一个合法的路径段。`DefaultBusPathMatcher` 专门解决这个问题（【源码证据】`DefaultBusPathMatcher.java:106-113` 的 `match` 与 `:53-95` 的 `matchMultiProfile`）：

```java
public boolean match(String pattern, String path) {
    if (!this.delagateMatcher.match(pattern, path)) {
        return matchMultiProfile(pattern, path);   // 委托失败后，展开多 profile 重试
    }
    return true;
}

protected boolean matchMultiProfile(String pattern, String idToMatch) {
    String[] tokens = tokenizeToStringArray(idToMatch, ":");
    if (tokens.length <= 1) { return false; }
    String selfProfiles = tokens[1];                  // 第二段是 profile 位
    String[] profiles = tokenizeToStringArray(selfProfiles, ",");
    if (profiles.length == 1) { return false; }       // 单 profile 无需展开
    // 把 "dev,cloud" 拆开，生成 N 个单 profile 候选 id，逐个用委托匹配器再试
    for (String id : idsWithSingleProfile) {
        if (this.delagateMatcher.match(pattern, id)) { return true; }
    }
    return false;
}
```

装配处（【源码证据】`PathServiceMatcherAutoConfiguration.java:38-58`）能看到两个防御性设计：

```java
@BusPathMatcher   // 自定义 @Qualifier 注解
// There is a @Bean of type PathMatcher coming from Spring MVC
@ConditionalOnMissingBean(name = BUS_PATH_MATCHER_NAME)   // 按"名字"条件，不按类型
@Bean(name = BUS_PATH_MATCHER_NAME)
public PathMatcher busPathMatcher() {
    return new DefaultBusPathMatcher(new AntPathMatcher(":"));
}

@Bean
@ConditionalOnMissingBean(ServiceMatcher.class)
public PathServiceMatcher pathServiceMatcher(@BusPathMatcher PathMatcher pathMatcher,
        BusProperties properties, Environment environment) { ... }
```

- Spring MVC 里已经有一个 `PathMatcher` 类型的 bean（`AntPathMatcher`，给 URL 匹配用）。为了不和它混淆，Bus 用**按名字的条件装配**（`@ConditionalOnMissingBean(name = "busPathMatcher")`）加**自定义限定符注解 `@BusPathMatcher`**（`BusPathMatcher.java`，一个 `@Qualifier` 元注解）双保险——想替换匹配逻辑的用户只要注册一个名为 `busPathMatcher` 的 bean 即可。
- `AntPathMatcher(":")` 一行把路径分隔符从 `/` 换成 `:`，`**`、`*`、`?` 的语义原封不动保留——**"寻址"复用的全是 Spring MVC 的路径匹配心智**。

## 3.5 configNames 兜底：`spring.cloud.config.name` 的匹配扩展

`PathServiceMatcher` 的构造器里藏着一段容易被忽略的逻辑（【源码证据】`PathServiceMatcher.java:31-49`）：

```java
public PathServiceMatcher(PathMatcher matcher, String id, String[] configNames) {
    this(matcher, id);
    int colonIndex = id.indexOf(":");
    if (colonIndex >= 0) {
        // 如果 id 含 profile 和 port 段，把它们追加到每个 configName 上
        String profilesAndPort = id.substring(colonIndex);
        for (int i = 0; i < configNames.length; i++) {
            configNames[i] = configNames[i] + profilesAndPort;
        }
    }
    this.configNames = configNames;
}
```

`configNames` 来自 `spring.cloud.config.name`（Config 客户端用来声明"我在配置中心叫什么名字"的属性，`PathServiceMatcherAutoConfiguration.java:43` 的 `CLOUD_CONFIG_NAME_PROPERTY`）。它解决的是这样的事：

> 你的服务在注册中心/配置中心叫 `order-service`（`spring.cloud.config.name=order-service`），但 `spring.application.name` 是别的（或者你想让配置中心名与 spring 应用名解耦）。此时发一条目的地为 `order-service` 的事件，按 3.3 节的 `isForSelf` 用 bus id（`my-app:dev:8080:x`）匹配会失败——于是再用补全过的 `order-service:dev:8080` 候选名试一遍，匹配成功。

**这个兜底让"配置中心里的应用名"与"spring 应用名"可以不一致，事件照样寻址**。是 Config + Bus 组合使用时的一个贴心细节。

## 3.6 Service ID 必须唯一：官方警告的源码依据

官方文档（`docs/modules/ROOT/pages/spring-cloud-bus/addressing.adoc`）有一条重要警告：

> *"The bus tries twice to eliminate processing an event — once from the original `ApplicationEvent` and once from the queue. To do so, it checks the sending service ID against the current service ID. **If multiple instances of a service have the same ID, events are not processed.**"*

对应到源码就是第六章要展开的两层 `isFromSelf` 过滤。含义直白：**同服务的两个实例若 bus id 完全相同（例如都没配 `server.port` 差异化、又在同一台机器），各自都会把对方的广播当成"自己发的"而丢弃**。本地多实例联调时端口天然不同；生产上若端口无法区分，要显式给每个实例配唯一的 `spring.cloud.bus.id`（或 `spring.application.index`）。

## 3.7 本章小结

- bus id 是四段式冒号路径（app : profiles? : port : random），默认值由 `BusEnvironmentPostProcessor` 填入（未解析占位符 + 最低优先级属性源），`IdUtils` 模板支持 vcap/Boot 本地端口层层回退。
- `ServiceMatcher` 的两问：`isFromSelf` 把 origin 当模式匹配自己（防自环用），`isForSelf` 把 destination 当模式匹配自己 + configNames 兜底（寻址用）。
- `DefaultBusPathMatcher` 解决多 profile（`dev,cloud`）id 的展开匹配：委托失败后拆 profile 重试。
- matcher 用"按名字条件 + 自定义 @Qualifier"避开与 Spring MVC 的 `PathMatcher` bean 冲突；`AntPathMatcher(":")` 让 Bus 寻址零成本复用 MVC 的通配符心智。
- bus id 必须实例级唯一，否则两层防自环会把合法广播误杀。

---

# 四、装配与贯通：BusEnvironmentPostProcessor 与六张自动装配表

## 4.1 注册点全景：AutoConfiguration.imports + spring.factories

Bus 核心模块的全部注册点只有两份文件——这是典型的"Boot 4 时代"自动装配布局：

【源码证据】`spring-cloud-bus/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（全文 6 行）

```
org.springframework.cloud.bus.PathServiceMatcherAutoConfiguration
org.springframework.cloud.bus.BusAutoConfiguration
org.springframework.cloud.bus.BusRefreshAutoConfiguration
org.springframework.cloud.bus.BusShutdownAutoConfiguration
org.springframework.cloud.bus.BusStreamAutoConfiguration
org.springframework.cloud.bus.jackson.BusJacksonAutoConfiguration
```

【源码证据】`spring-cloud-bus/src/main/resources/META-INF/spring.factories`（全文 3 行）

```properties
org.springframework.boot.EnvironmentPostProcessor=\
org.springframework.cloud.bus.BusEnvironmentPostProcessor
```

六张装配表各管一段（实际执行顺序受 `@AutoConfigureBefore/After` 调整，见 4.3 节）：

| 自动装配类 | 管什么 | 生效条件 |
|---|---|---|
| `PathServiceMatcherAutoConfiguration` | `busPathMatcher` + `PathServiceMatcher` | `spring.cloud.bus.enabled` 非显式 false |
| `BusAutoConfiguration` | 核心三件（Destination.Factory / Listener / Consumer）+ Trace + Env 功能 | 同上 |
| `BusRefreshAutoConfiguration` | `RefreshListener` + `busrefresh` 端点 | ContextRefresher 存在 + `bus.refresh.enabled` |
| `BusShutdownAutoConfiguration` | `ShutdownListener` + `busshutdown` 端点 | `bus.shutdown.enabled` |
| `BusStreamAutoConfiguration` | `StreamBusBridge` | classpath 有 `StreamBridge` + Stream 配置类 |
| `BusJacksonAutoConfiguration` | `busJsonConverter`（+可选 CBOR 转换器） | actuator 端点类 + Jackson 3 在 classpath |

所有配置类都以 `@ConditionalOnBusEnabled`（`ConditionalOnBusEnabled.java`）开头——它只是 `@ConditionalOnProperty(value = "spring.cloud.bus.enabled", matchIfMissing = true)` 的语义化封装，一个开关总闸。

## 4.2 BusEnvironmentPostProcessor：启动前埋好的三颗"钉子"

`EnvironmentPostProcessor` 在 Boot 启动流程里跑在**配置文件加载之后、容器刷新之前**（《Spring Boot.md》的启动流程章有全景）。Bus 借这个时机往 Environment 里注入运行期不好改的 Stream 接线配置（【源码证据】`BusEnvironmentPostProcessor.java:49-83`）：

**第一颗钉子：把 `busConsumer` 追加进函数定义**（最高优先级属性源 `springCloudBusOverridesProperties`，`addFirst`）：

```java
String definition = BusConstants.BUS_CONSUMER;            // "busConsumer"
if (environment.containsProperty(FN_DEF_PROP)) {          // spring.cloud.function.definition
    String property = environment.getProperty(FN_DEF_PROP);
    if (property != null && property.contains(BusConstants.BUS_CONSUMER)) {
        return;   // 幂等护栏：EPP 可能被跑多次
    }
    definition = property + ";" + definition;             // 用户函数在前，busConsumer 追加在后
}
overrides.put(FN_DEF_PROP, definition);
```

这一步让 Stream 把容器里名为 `busConsumer` 的 `Consumer<RemoteApplicationEvent>` bean（第六章主角）注册成消息消费者。用户自己定义的函数不会被顶掉，只是**并排多挂了一个总线消费者**。

**第二颗钉子：绑定别名与 topic 默认值**（最低优先级属性源 `springCloudBusDefaultProperties`，`addLast`）：

```java
defaults.put("spring.cloud.stream.function.bindings.busConsumer-in-0", "springCloudBusInput");
String destination = environment.getProperty(PREFIX + ".destination", BusConstants.DESTINATION); // springCloudBus
defaults.put("spring.cloud.stream.bindings.springCloudBusInput.destination", destination);
defaults.put("spring.cloud.stream.bindings.springCloudBusOutput.destination", destination);
```

三个默认值连起来读：函数绑定 `busConsumer-in-0` 别名为 `springCloudBusInput`；输入、输出两个绑定都指向同一个 topic（默认 `springCloudBus`）。**一收一发同名 topic，这正是"总线"的拓扑本质。**

**第三颗钉子：bus id 默认值**（同在最低优先级属性源，见 3.2 节）。

三颗钉子共用一个精心设计的属性源管理方法 `addOrReplace(...)`（第 85-112 行）：**overrides 放最高优先级**（保证合并后的函数定义压过用户 application.yml 里的原始值——因为它已经把用户原值合并进来了，重复定义会破坏函数注册），**defaults 放最低优先级**（保证用户在任何配置文件里都能覆盖 destination、bus id）。

## 4.3 装配顺序链：谁必须先于谁

自动装配的执行顺序在类注解上明示（`BusStreamAutoConfiguration.java:29-37`）：

```java
@AutoConfigureBefore({ BindingServiceConfiguration.class, BusAutoConfiguration.class })  // so stream bindings work properly
@AutoConfigureAfter({ LifecycleMvcEndpointAutoConfiguration.class, PathServiceMatcherAutoConfiguration.class })
```

- `BusStreamAutoConfiguration` 必须先于 **Stream 的 `BindingServiceConfiguration`**：Stream 收集消息转换器、建立绑定前，`StreamBusBridge` 得先就位；也必须先于 `BusAutoConfiguration`，让 `BusConsumer`/`RemoteApplicationEventListener` 注入 `BusBridge` 时拿得到实现。
- `BusRefreshAutoConfiguration` 标注 `@AutoConfigureAfter(RefreshAutoConfiguration)`（spring-cloud-context 的）：`RefreshListener` 依赖 `ContextRefresher` bean，得等刷新体系装配完。
- `BusJacksonAutoConfiguration` 标注 `@AutoConfigureBefore(name = "...JacksonAutoConfiguration")`（第 64 行，注意是 Boot 4 的新包名 `org.springframework.boot.jackson.autoconfigure`）：让 bus 转换器赶在 Boot 的 JSON 装配前注册。

核心三件套的装配代码（【源码证据】`BusAutoConfiguration.java:49-70`）：

```java
@Bean
@ConditionalOnMissingBean(Destination.Factory.class)
public PathDestinationFactory pathDestinationFactory() { ... }

@Bean
@ConditionalOnMissingBean
public RemoteApplicationEventListener busRemoteApplicationEventListener(
        ServiceMatcher serviceMatcher, BusBridge busBridge) { ... }

@Bean
@ConditionalOnMissingBean(name = BUS_CONSUMER)
public BusConsumer busConsumer(ApplicationEventPublisher applicationEventPublisher,
        ServiceMatcher serviceMatcher, ObjectProvider<BusBridge> busBridge,
        BusProperties properties, Destination.Factory destinationFactory) { ... }
```

注意两个依赖细节：`RemoteApplicationEventListener` 直接注入 `BusBridge`（**必须有**，所以 Bus 核心必须搭配一个 Bridge 实现使用——正常由 `BusStreamAutoConfiguration` 或用户自定义提供）；`BusConsumer` 注入的是 `ObjectProvider<BusBridge>`（**可选**，ACK 发不出去就静默跳过，不影响接收）。三个 bean 全部 `@ConditionalOnMissingBean`，留足了替换空间。

## 4.4 本章小结

- 全部注册点 = 6 行 `AutoConfiguration.imports` + 1 行 `spring.factories`（EPP）；总闸是 `spring.cloud.bus.enabled`。
- `BusEnvironmentPostProcessor` 埋三颗钉子：函数定义追加 `busConsumer`（最高优先级）、绑定别名 + topic 默认值（最低优先级）、bus id 默认值（最低优先级）；并有防重复执行的幂等护栏。
- 收发共用一个 topic（默认 `springCloudBus`）——"总线"拓扑的本质就写在 EPP 的三行 defaults 里。
- 装配顺序的核心约束：Bridge 实现先于使用者装配；`RefreshListener` 等刷新体系就绪。
- 核心三件套全部可替换（`@ConditionalOnMissingBean`）；Listener 硬依赖 BusBridge，Consumer 软依赖（ObjectProvider）。

---

# 五、发送链路：从 HTTP 端点到消息中间件

## 5.1 全景图：一条事件的七跳

以 `curl -X POST http://localhost:8080/actuator/busrefresh/customers:9000` 为例，从 HTTP 请求到消息离开本进程，一共七跳：

```
 ① HTTP POST /actuator/busrefresh/customers:9000
      │
 ② RefreshBusEndpoint.busRefreshWithDestination(["customers:9000"])
      │   destination 补全："customers:9000" → "customers:9000:**"（PathDestinationFactory）
      │   publish(new RefreshRemoteApplicationEvent(this, bus.getId(), destination))
      ▼
 ③ 本地事件广播（ApplicationEventMulticaster）
      │
 ④ RemoteApplicationEventListener.onApplicationEvent
      │   判定：isFromSelf(event) && !(event instanceof Ack)
      ▼
 ⑤ BusBridge.send(event)                        ← 本地世界 ⇄ 总线世界的边界
      │
 ⑥ StreamBusBridge.send
      │   streamBridge.send("springCloudBusOutput", MessageBuilder.withPayload(event).build())
      ▼
 ⑦ Stream 输出绑定 → binder（RabbitMQ/Kafka）→ topic "springCloudBus"
```

要点：**端点只是"本地事件的发布者"之一**。任何业务代码 `applicationEventPublisher.publishEvent(new MyRemoteEvent(...))` 都会走 ③ 之后的同一链路——Bus 不关心事件从哪来，只认"本地广播里出现了 RemoteApplicationEvent"。

## 5.2 Actuator 端点：busrefresh / busenv / busshutdown

三个端点全部继承 `AbstractBusEndpoint`（提供 publish 与 destination 工具方法），全部是 `@WriteOperation`（只响应 POST）。以 busrefresh 为例（【源码证据】`endpoint/RefreshBusEndpoint.java:31-50`）：

```java
@Endpoint(id = "busrefresh")
public class RefreshBusEndpoint extends AbstractBusEndpoint {

    @WriteOperation
    public void busRefreshWithDestination(@Selector(match = Match.ALL_REMAINING) String[] destinations) {
        String destination = StringUtils.arrayToDelimitedString(destinations, ":");
        publish(new RefreshRemoteApplicationEvent(this, getInstanceId(), getDestination(destination)));
    }

    @WriteOperation
    public void busRefresh() {
        publish(new RefreshRemoteApplicationEvent(this, getInstanceId(), getDestination(null)));
    }
}
```

`getInstanceId()` 返回的就是 `BusProperties.id`（bus id），作为事件的 `originService`。三个端点的装配位置不同，值得注意：

| 端点 | 装配处 | 额外条件 |
|---|---|---|
| `busrefresh` | `BusRefreshAutoConfiguration$BusRefreshEndpointConfiguration` | actuator + RefreshScope 类在 classpath；`@ConditionalOnAvailableEndpoint` |
| `busenv` | `BusAutoConfiguration$BusEnvironmentConfiguration` 深层嵌套类 | **还要求容器里有 `EnvironmentManager` bean**（spring-cloud-context 提供） |
| `busshutdown` | `BusShutdownAutoConfiguration$BusShutdownEndpointConfiguration` | actuator 在 classpath |

`@ConditionalOnAvailableEndpoint` 把"bean 是否创建"与"端点是否暴露"（`management.endpoints.web.exposure.include`）解耦：bean 可以在，Web 上不暴露。三个端点都**不在默认暴露名单**里，必须显式配置（见 8.5 节的安全讨论）。

## 5.3 RemoteApplicationEventListener：本地事件的"上桥"闸口

【源码证据】`RemoteApplicationEventListener.java:33-49`

```java
public class RemoteApplicationEventListener implements ApplicationListener<RemoteApplicationEvent> {

    @Override
    public void onApplicationEvent(RemoteApplicationEvent event) {
        if (this.serviceMatcher.isFromSelf(event) && !(event instanceof AckRemoteApplicationEvent)) {
            if (log.isDebugEnabled()) {
                log.debug("Sending remote event on bus: " + event);
            }
            this.busBridge.send(event);
        }
    }
}
```

短短一行判定，承担了两个关键职责：

1. **`isFromSelf(event)`**：只把自己（origin 是自己 bus id）产生的事件上桥。反过来，`BusConsumer` 从桥上收到的别人事件也会在本地广播（见 6.2 节），若没有这道闸，本地广播会再次触发上桥，**消息就在"本地↔总线"之间无限乒乓**。这是防自环的第一道闸。
2. **`!(event instanceof AckRemoteApplicationEvent)`**：ACK 是 `BusConsumer` 直接通过 bridge 发送的（不走本地广播），但 BusConsumer 处理 ACK 时若 trace 开启也会在本地广播 ACK（见 6.2）——这道类型判断保证**本地广播里出现的 ACK 永远不会被再次上桥**，ACK 风暴被掐死在源头。这是防自环的第二道闸。

## 5.4 BusBridge 与 StreamBusBridge：传输层抽象

【源码证据】`BusBridge.java:27-31` + `StreamBusBridge.java:32-38`

```java
public interface BusBridge {
    void send(RemoteApplicationEvent event);
}

public class StreamBusBridge implements BusBridge {
    public void send(RemoteApplicationEvent event) {
        // TODO: configurable mimetype?
        this.streamBridge.send(BusConstants.OUTPUT, MessageBuilder.withPayload(event).build());
    }
}
```

- **`BusBridge` 是 Bus 与传输层的全部接口**：一个方法、一个参数。第五章的全部发送逻辑（端点、监听器）都不知道 Stream 的存在。
- **`StreamBusBridge` 的实现只有一行**：用 Stream 的 `StreamBridge` 把事件作为 payload 发往输出绑定 `springCloudBusOutput`（即 4.2 节钉到 topic `springCloudBus` 的那个绑定）。payload 就是事件对象，序列化由 Stream 的消息转换器完成（第七章的 `BusJacksonMessageConverter`）。
- 那行 `TODO: configurable mimetype?` 说明：**Bus 目前不暴露"发什么 content-type"的配置**，`BusProperties.contentType` 存在但发送侧并未消费它（JSON 是事实上的默认；classpath 有 CBOR 时转换器会多注册一个，见 7.6）。读源码时要分清"配置存在"与"链路真的使用"。
- **接收侧没有 Bridge**：接收靠 Stream 把消息递给函数 `busConsumer`（第六章），Bridge 只管"出"。这种"出走 Bridge、进走函数"的不对称，是函数式改造后的现状——自定义传输（如 Redis pub/sub、HTTP 轮询）时，实现 `BusBridge` 负责发送，再自己把收到的消息转成 `RemoteApplicationEvent` 交给 `BusConsumer#accept` 即可（见 9.4 节）。

## 5.5 本章小结

- 发送链路七跳：HTTP → 端点 publish → 本地事件广播 → RemoteApplicationEventListener（isFromSelf && 非 ACK）→ BusBridge → StreamBridge → topic。
- 端点只是事件发布者之一；业务代码 publish 一个 RemoteApplicationEvent 子类，走的是完全相同的链路。
- destination 在端点处完成补全（2.4 节），origin 固定为本实例 bus id。
- 防自环两道闸都在"上桥"处：isFromSelf 过滤 + ACK 类型特判。
- BusBridge 单方法接口是传输层的全部；StreamBusBridge 一行实现；接收侧不走 Bridge（函数式）。

---

# 六、接收链路：BusConsumer 与 ACK 协议

## 6.1 全景图：一条消息在接收方的一生

```
 topic "springCloudBus"（RabbitMQ exchange / Kafka topic）
      │  binder 投递（每实例独立匿名队列/消费组 → 广播语义）
      ▼
 Stream 函数绑定 springCloudBusInput（busConsumer-in-0）
      │  content-type 协商 + BusJacksonMessageConverter 反序列化（第七章）
      │  payload(byte[]/String) → RemoteApplicationEvent 子类实例
      ▼
 BusConsumer.accept(RemoteApplicationEvent event)      ← 本章主角
      ├─ 是 ACK？─► trace 开启且非自环才本地广播 ─► return（终）
      ├─ isForSelf(event)?
      │    ├─ 是 ─┬─ 非 isFromSelf ─► 本地 publishEvent(event)   ← 业务 Listener（如 RefreshListener）处理
      │    │      └─ ack.enabled ─► 回发 AckRemoteApplicationEvent（bridge 直发 + 本地广播）
      │    └─ 否 ─► 什么都不做（消息与我无关）
      └─ trace.enabled ─► 本地 publishEvent(new SentApplicationEvent(...))（审计信号）
```

## 6.2 BusConsumer.accept 逐行解析

`BusConsumer` 是一个实现了 `java.util.function.Consumer<RemoteApplicationEvent>` 的普通 bean，靠 4.2 节埋的函数定义被 Stream 识别为消费者。它的 `accept` 是**整个 Bus 的心脏**，全文不到 40 行（【源码证据】`BusConsumer.java:55-87`）：

```java
@Override
public void accept(RemoteApplicationEvent event) {
    if (event instanceof AckRemoteApplicationEvent) {
        if (this.properties.getTrace().isEnabled() && !this.serviceMatcher.isFromSelf(event)
                && this.publisher != null) {
            this.publisher.publishEvent(event);          // ACK 只在 trace 开启时本地广播
        }
        // If it's an ACK we are finished processing at this point
        return;                                           // ← ACK 到此为止，绝不走业务处理
    }

    if (this.serviceMatcher.isForSelf(event) && this.publisher != null) {
        if (!this.serviceMatcher.isFromSelf(event)) {
            this.publisher.publishEvent(event);          // ← 真正的"处理"：转成本地事件
        }
        if (this.properties.getAck().isEnabled()) {
            AckRemoteApplicationEvent ack = new AckRemoteApplicationEvent(this,
                    this.serviceMatcher.getBusId(),                      // origin = 我
                    destinationFactory.getDestination(this.properties.getAck().getDestinationService()),
                    event.getDestinationService(),                       // ackDestinationService = 原事件目的地
                    event.getId(),                                       // ackId = 原事件 id
                    event.getClass());                                   // 原事件类型
            this.busBridge.ifAvailable(bridge -> bridge.send(ack));      // 上桥广播
            this.publisher.publishEvent(ack);                            // 本地也让监听器看到
        }
    }
    if (this.properties.getTrace().isEnabled() && this.publisher != null) {
        // irrespective of the origin —— 审计信号不论来源都发
        this.publisher.publishEvent(new SentApplicationEvent(this, event.getOriginService(),
                event.getDestinationService(), event.getId(), event.getClass()));
    }
}
```

对照这张判定表逐块消化：

| 消息情形 | isForSelf | isFromSelf | 本地 publishEvent(event) | 回 ACK | trace 开启时 |
|---|---|---|---|---|---|
| 别人发的、发给我的 | ✔ | ✘ | **✔（核心处理路径）** | ✔ | 发 Sent 信号 |
| 别人发的、与我无关 | ✘ | ✘ | ✘ | ✘ | 发 Sent 信号 |
| 自己发的又转回来（RabbitMQ 广播自己也能收到） | ✔ | ✔ | ✘（防重复处理） | ✔ | 发 Sent 信号 |
| ACK 回执 | — | — | 仅 trace 开启且非自环 | — | — |

**最核心的一行是 `this.publisher.publishEvent(event)`**：Bus 的"处理一条远程消息"，就是把跨进程事件**转译回进程内事件**。此后一切照旧——`RefreshListener` 监听 `RefreshRemoteApplicationEvent`、`EnvironmentChangeListener` 监听 `EnvironmentChangeRemoteApplicationEvent`（第八章），它们对"这条事件是从总线上来的"毫无感知。**跨进程与进程内两个世界，在这一行完成衔接。**

三个次级但重要的细节：

1. **`isFromSelf` 的消息不本地发布但仍回 ACK**：自己发的消息转了一圈回来，不能当没发生过（否则处理语义不一致），但不能重复执行业务——所以只回执、不处理。同时这也解释了 3.6 节"bus id 冲突则事件不被处理"：别人的消息会被 `isFromSelf` 误判为自己的而跳过 publish。
2. **ACK 的目的地由 `spring.cloud.bus.ack.destination-service` 控制**，默认 null → 补全为 `**` 全体广播——**任何实例都能观测到全网的回执**，这是"中心化审计节点"的用法基础（官方文档建议单独起一个服务监听 ACK）。
3. **`busBridge.ifAvailable(...)`**：Bridge 缺席时 ACK 直接不发（对象软依赖的兜底，见 4.4 节）。

## 6.3 ACK 协议：语义、路由与自环过滤

把 ACK 当成一个**应用层的回执协议**来读，三句话总结：

1. **谁回**：每个认为"消息是发给我的"的实例（isForSelf 为 true）都回一条。广播消息（`**`）→ 全体回；定向消息 → 目标实例回。**ACK 本身也是广播事件，同样走 topic、同样被所有人收到**。
2. **怎么关联**：`ackId` = 原事件 id（UUID）。监听方拿 `SentApplicationEvent.id` 与 `AckRemoteApplicationEvent.ackId` 对上号（`SentApplicationEvent` 的类 javadoc 明说了这个约定：*"the `id` of this event is the `ackId` of the corresponding ACK"*）。
3. **谁看**：`BusConsumer` 收到 ACK 后只有 `trace.enabled` 且非自环时才本地广播；业务方再接 `@EventListener(AckRemoteApplicationEvent.class)` 自行统计。**ACK 不驱动任何重试/补偿逻辑——Bus 的可靠性完全委托给 MQ，ACK 只是观测手段**。

一条完整刷新广播的 ACK 时间线（官方文档 tracing 示例的翻版）：

```
 customers:9000 发 RefreshRemoteApplicationEvent(id=A, dest="**")
   ├─ ACK: origin=customers:9000, ackId=A   （它自己也 isForSelf，所以自己回自己）
   ├─ ACK: origin=stores:8081,     ackId=A
   └─ ACK: origin=orders:8082,     ackId=A
 中心审计节点监听 AckRemoteApplicationEvent，按 ackId=A 聚合 → 知道三个实例都刷新了
```

## 6.4 TraceListener 与事件追踪（含一个诚实提醒）

`TraceListener`（`event/TraceListener.java`）设计上是把 `SentApplicationEvent`（信号 `spring.cloud.bus.sent`）和 `AckRemoteApplicationEvent`（信号 `spring.cloud.bus.ack`）各转成一条 map 存进 Boot 的 `HttpExchangeRepository`（即 `/actuator/httpexchanges` 的存储）。装配条件三重：actuator `Endpoint` 类在 classpath + 容器有 `HttpExchangeRepository` bean + `spring.cloud.bus.trace.enabled=true`（`BusAutoConfiguration.java:73-84`）。

但**必须诚实地指出**：本快照的两个落库调用都被注释掉了（【源码证据】`event/TraceListener.java:47,53`，两处 `// FIXME boot 2 this.repository.add(trace);`）：

```java
@EventListener
public void onAck(AckRemoteApplicationEvent event) {
    Map<String, Object> trace = getReceivedTrace(event);
    // FIXME boot 2 this.repository.add(trace);
}
```

即 trace 开启后，`SentApplicationEvent`/`AckRemoteApplicationEvent` 依然会作为本地事件广播（BusConsumer 的行为没变），**你自己的 `@EventListener` 一切照常可用**；但"自动存入 HttpExchangeRepository、从端点查看"这一段在 5.1.0-SNAPSHOT 上是空转的（Boot 2 → 3 迁移遗留 TODO，截至本快照未接回）。依赖 `/actuator/httpexchanges` 看 bus 轨迹的老用法在这个版本上不可用，请改用自定义 Listener。

## 6.5 防自环：两层过滤的完整拼图

现在把散落在第三、五、六章的防自环逻辑拼成一张全景表——**同一个事件对象在生命周期里会被 `isFromSelf` 判定两次、ACK 被类型特判两次**：

| 位置 | 判定 | 防的是什么 |
|---|---|---|
| 发送侧 `RemoteApplicationEventListener`（5.3） | `isFromSelf && !instanceof(Ack)` | 本地广播里"别人事件被转发落地"（6.2 第 1 行场景）被再次上桥 → 无限乒乓 |
| 发送侧同上 | `!instanceof(Ack)` | 本地广播的 ACK 被再次上桥 → ACK 风暴 |
| 接收侧 `BusConsumer`（6.2） | `!isFromSelf` 才 publishEvent | 自己的消息转回来被重复处理（业务逻辑执行两遍） |
| 接收侧 `BusConsumer` | ACK 分支直接 `return` | ACK 被当成业务事件处理 |

设计上值得咀嚼的一点：**Bus 没有用"消息头里的 TTL/hop 数"或"发送者实例指纹"这类 MQ 惯用手法，而是复用了寻址的身份体系（bus id 匹配）**。副作用就是 3.6 节的警告——身份体系既是寻址的钥匙，也是防自环的依据，一处配置错误（id 撞车）会同时破坏两个功能。这是"一个抽象服务两个需求"的典型 trade-off。

## 6.6 本章小结

- 接收链路：topic → binder → 函数 busConsumer → 反序列化 → `BusConsumer#accept`；核心处理 = `isForSelf && !isFromSelf` 时 `publisher.publishEvent(event)`——跨进程事件在这里转译回进程内事件。
- ACK 协议三要素：isForSelf 者皆回、ackId 关联原事件、广播给全体观测；ACK 是观测不是可靠性保证。
- `BusConsumer` 的判定表覆盖四种消息情形，全部逻辑不到 40 行——值得背下来。
- TraceListener 在本快照上"事件照发、落库空转"（FIXME boot 2），轨迹要靠自己监听事件收集。
- 防自环 = 发送侧两道闸 + 接收侧两道闸，全部复用 bus id 身份体系；这既是优雅也是 bus id 必须唯一的原因。

---

# 七、序列化：Jackson 多态与 UnknownRemoteApplicationEvent 兜底

## 7.1 先白话：跨进程反序列化的难点

消息从 MQ 回来时只是一段字节。要还原成 `RemoteApplicationEvent` 子类，Jackson 必须知道：**这段 JSON 对应哪个类？** 普通对象反序列化目标类型是编译期确定的，而总线消息的目标类型写在消息自己的 `"type"` 字段里（2.2 节的 `@JsonTypeInfo`），且**类型的集合在编译期根本不可知**——任何服务都可以往总线上发自定义事件。所以 Bus 的序列化方案要解决三件事：

1. 子类注册表：把"允许出现的 type 名 → 类"登记进 Jackson 的 subtype resolver；
2. 转换器挂接：让 Stream 的消息转换流程用上这个"会处理 RemoteApplicationEvent 家族"的转换器；
3. 兜底：对方发来我认不出的 type 时，不能让消息处理线程崩掉。

## 7.2 wire format：一条刷新消息的 JSON 长什么样

以 `RefreshRemoteApplicationEvent` 为例（字段来自 2.2/2.3 节的事件定义）：

```json
{
  "type": "RefreshRemoteApplicationEvent",
  "originService": "customers:dev:9000:8a1c",
  "destinationService": "**",
  "id": "c4d374b7-58ea-4928-a312-31984def293b",
  "timestamp": 1799102444411
}
```

- `type` 来自 `@JsonTypeInfo(use = Id.NAME, property = "type")`，**默认取类 simple name**，想改名用 `@JsonTypeName("my-name")` 标在自定义事件上（官方 custom-events 文档明示）。
- `source` 因 `@JsonIgnoreProperties("source")` 不出现。
- 子类自有字段（如 EnvironmentChange 的 `values`）原样并列。

## 7.3 BusJacksonMessageConverter：包扫描注册子类型

转换器是一个包私有类，与 `BusJacksonAutoConfiguration` 同文件（【源码证据】`jackson/BusJacksonAutoConfiguration.java:89-213`），三段核心逻辑：

**（1）只认 RemoteApplicationEvent 家族**：

```java
@Override
protected boolean supports(Class<?> aClass) {
    return RemoteApplicationEvent.class.isAssignableFrom(aClass);
}
```

它继承 spring-messaging 的 `AbstractMessageConverter`，Stream 的转换工厂会收集容器里所有此类 bean，按 content-type 与 supports 类型分派——于是**只有总线消息走它，不会干扰业务 JSON 转换**。

**（2）初始化时扫描包、注册全部子类型**：

```java
@Override
public void afterPropertiesSet() {
    this.mapperBuilder.subtypeResolver().registerSubtypes(findSubTypes());
}

private Class<?>[] findSubTypes() {
    for (String pkg : this.packagesToScan) {           // 默认只有 DEFAULT_PACKAGE
        ClassPathScanningCandidateComponentProvider provider =
                new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AssignableTypeFilter(RemoteApplicationEvent.class));
        // 扫描包内所有 RemoteApplicationEvent 子类，收集为类型数组
    }
}
```

`packagesToScan` 默认是 `RemoteApplicationEvent` 所在包 **`org.springframework.cloud.bus.event`**（第 93 行 `DEFAULT_PACKAGE`），且 `setPackagesToScan` 永远把默认包追加进去（第 132-137 行）——**Bus 内置事件无论如何都会被注册**。扫描用的是 Spring 的 `ClassPathScanningCandidateComponentProvider` + `AssignableTypeFilter`（与 `@ComponentScan` 同源的类路径扫描机制，见《Spring Framework.md》4.4.2 节）。

**（3）反序列化主干 `convertFromInternal`（第 172-207 行）**：处理 `byte[]` 与 `String` 两种 payload；**捕获 `InvalidTypeIdException` 转降级**（见 7.5）；payload 已是事件对象则原样透传（Stream 某些路径下的双重转换防御，注释里挂着 Stream 的 issue 编号 1564）；其他异常记日志返回 null（消息丢弃，不重试）。

## 7.4 @RemoteApplicationEventScan：自定义事件包注册

官方文档的规则："**默认只有 `org.springframework.cloud.bus.event` 的子包会被自动注册**；自定义事件放别的包，就必须用 `@RemoteApplicationEventScan` 声明"：

```java
@Configuration
@RemoteApplicationEventScan({"com.acme.bus.events", "foo.bar"})
public class BusConfiguration { ... }
```

它的实现是一个教科书级的 `ImportBeanDefinitionRegistrar` 组合（`@RemoteApplicationEventScan` 上标着 `@Import(RemoteApplicationEventRegistrar.class)`，`jackson/RemoteApplicationEventScan.java:33`）：

【源码证据】`jackson/RemoteApplicationEventRegistrar.java:46-100`

```java
// 1. 读注解属性，凑出 basePackages 集合（value/basePackages/basePackageClasses 三种写法）
// 2. 没写包 → 用注解所在类的包名
// 3. 关键：若容器里已有 "busJsonConverter" bean 定义（可能是 @Bean 方式注册的）
if (!registry.containsBeanDefinition(BUS_JSON_CONVERTER)) {
    // 注册 BusJacksonMessageConverter 的 BeanDefinition，属性 packagesToScan = 本次扫描包
} else {
    // 合并：getEarlierPackagesToScan(registry) 取出之前注册的包集合，
    //       与本次包集合并集后，覆盖写回 busJsonConverter 的 packagesToScan 属性
}
```

**"合并而非覆盖"（`getEarlierPackagesToScan`，第 82-95 行）是这段代码的精髓**：多个配置类各标一个 `@RemoteApplicationEventScan`（比如库 jar 里标了一个、应用里标了一个）时，后注册的不会顶掉前面的包——因为 `ImportBeanDefinitionRegistrar` 执行于配置类解析期（容器 refresh 的早期阶段，《Spring Framework.md》4.4.3 节），此时直接操作 BeanDefinition 属性，比事后改 bean 实例更可靠。这段实现注释里也点明 pattern 自 Spring Integration 的 `IntegrationComponentScanRegistrar`。

## 7.5 兜底：InvalidTypeIdException → UnknownRemoteApplicationEvent

【源码证据】`jackson/BusJacksonAutoConfiguration.java:180-195`

```java
if (payload instanceof byte[]) {
    try {
        result = mapper.readValue((byte[]) payload, targetClass);
    }
    catch (InvalidTypeIdException e) {
        return new UnknownRemoteApplicationEvent(new Object(), e.getTypeId(), (byte[]) payload);
    }
}
else if (payload instanceof String) { /* 同样逻辑 */ }
```

场景回放：服务 A 升级后往总线发 `NewFeatureRemoteApplicationEvent`，服务 B 的 classpath 还没有这个类（没来得及升级/根本不关心这个功能）。没有兜底时 B 会反序列化失败 → 消息消费异常 → 视 binder 配置可能无限重投。有了兜底：B 把消息装进 `UnknownRemoteApplicationEvent`（typeInfo = 认不出的类名，payload = 原始字节）→ 2.3 节讲过它的 origin 为空串、destination 为 `unknown` → `isForSelf` 恒 false → **静默落地，只留证据，总线继续转**。

这是"**松耦合演进**"的关键一环：**事件的产生方永远不需要等消费方升级**。代价是消费方要对 Unknown 事件有观测（日志/自定义监听），否则升级不同步会被无声吞掉。

## 7.6 Jackson 3 与 CBOR：5.1 的两个新面孔

本快照的序列化栈已经整体迁移到 **Jackson 3（`tools.jackson.*`）**——与 Boot 4 对齐（《Spring Boot.md》Jackson 3 相关章节）：

- `BusJacksonMessageConverter` 持有的是 `MapperBuilder`（不可变 `ObjectMapper` 的建造器），注入已有 mapper 时用 `objectMapper.rebuild()` 拷贝改造，否则自建 `JsonMapper.builder()`——Jackson 3 的"builder 优先"风格。
- `BusJacksonAutoConfiguration` 第 71-78 行注册 JSON 转换器，**优先复用容器里 Boot 已有的 `JsonMapper`**（用户的全局 Jackson 定制如日期格式、命名策略对总线消息同样生效）。
- **CBOR 支持（第 80-85 行）**：classpath 出现 `tools.jackson.dataformat.cbor.CBORFactory` 时，额外注册一个 `application/cbor` 的转换器（二进制 JSON，消息更小更快）。配合 Stream 的 content-type 协商，理论上把绑定 content-type 改为 `application/cbor` 即可切换 wire format——但注意 5.4 节说的 TODO：发送侧 mimetype 还没做成可配置，改 CBOR 需要在 Stream 绑定侧配置。

## 7.7 本章小结

- wire format 的灵魂是 `"type"` 字段（`@JsonTypeInfo(Id.NAME)`，simple name 或 `@JsonTypeName`）；source 永不序列化。
- `BusJacksonMessageConverter`：supports 限定事件家族；`afterPropertiesSet` 扫包注册子类型；默认扫 `org.springframework.cloud.bus.event` 且永不丢弃默认包。
- 自定义事件放自定义包 → `@RemoteApplicationEventScan`；Registrar 以 ImportBeanDefinitionRegistrar 身份在容器早期直接操作 BeanDefinition，多次声明**合并包集合**。
- 认不出的 type → `UnknownRemoteApplicationEvent` 兜底：静默落地、留证、不崩——事件生产方无需等消费方升级。
- 5.1 快照 = Jackson 3（tools.jackson）+ builder 风格 + 复用 Boot 全局 JsonMapper + 可选 CBOR 转换器。

---

# 八、内置功能与联动：Refresh / Env / Shutdown

## 8.1 三个 Listener：Bus 的"执行端"

事件被 `BusConsumer` 转译成本地事件后（6.2 节），真正的执行者是三个本地 Listener。它们的共同模式：`implements ApplicationListener<对应事件>` + **入场再判一次 `isForSelf`**（双保险——BusConsumer 已过滤过）+ 调用 spring-cloud-context 的对应能力：

| Listener | 监听事件 | 执行动作 | 装配处与开关 |
|---|---|---|---|
| `RefreshListener` | `RefreshRemoteApplicationEvent` | `contextRefresher.refresh()` | `BusRefreshAutoConfiguration`，需 ContextRefresher bean；`spring.cloud.bus.refresh.enabled` |
| `EnvironmentChangeListener` | `EnvironmentChangeRemoteApplicationEvent` | 逐条 `EnvironmentManager.setProperty(k,v)` | `BusAutoConfiguration$BusEnvironmentConfiguration`，需 EnvironmentManager bean；`spring.cloud.bus.env.enabled` |
| `ShutdownListener` | `ShutdownRemoteApplicationEvent` | `SpringApplication.exit(context, () -> 0)` | `BusShutdownAutoConfiguration`；`spring.cloud.bus.shutdown.enabled` |

【源码证据】`event/RefreshListener.java:46-55`

```java
@Override
public void onApplicationEvent(RefreshRemoteApplicationEvent event) {
    log.info("Received remote refresh request.");
    if (serviceMatcher.isForSelf(event)) {                 // ← 双保险入场检查
        Set<String> keys = this.contextRefresher.refresh();
        log.info("Keys refreshed " + keys);
    }
    else {
        log.info("Refresh not performed, the event was targeting " + event.getDestinationService());
    }
}
```

## 8.2 RefreshListener → ContextRefresher：与 Config 客户端的全链路联动

`ContextRefresher#refresh()` 是 spring-cloud-context 的刷新入口，它做三件事（详见《Spring Cloud Config.md》第七章）：

1. 重新加载所有"可刷新"的 PropertySource——**装了 Config 客户端的话，就是重新去 Config Server 拉一遍配置**；
2. 对比新旧属性，变更集发布 `EnvironmentChangeEvent` → `@ConfigurationProperties` bean rebind；
3. `RefreshScope.refreshAll()` → 清空 `@RefreshScope` 缓存，下次访问重建。

于是 Config + Bus 的经典全链路成型：

```
 Git 提交配置 → webhook → config-server 的 /monitor（config-monitor 模块）
   → 经 Bus 广播 RefreshRemoteApplicationEvent（或运维手动 POST 任一实例的 /actuator/busrefresh）
   → 每个实例的 BusConsumer 落地 → RefreshListener → ContextRefresher.refresh()
   → 实例重新拉取 Config Server → diff → EnvironmentChangeEvent + RefreshScope 刷新
```

**注意 Bus 传的只是"该刷新了"这声令，不传配置内容本身**——内容仍由各实例向 Config Server 重新拉取。这就是 1.1 节说的"Bus 是指令总线不是数据总线"。

## 8.3 EnvironmentChangeListener → EnvironmentManager

`EnvironmentManager`（spring-cloud-context）的 `setProperty` 会把 key/value 放进一个名为 `manager` 的高优先级 MapPropertySource 并发布 `EnvironmentChangeEvent`——效果等同于在本地打 `/actuator/env` 的 POST。因此 `/actuator/busenv` 实现的是"**一次调用，向全集群临时注入运行期属性**"（改日志级别、灰度开关等）。注意它是**临时生效**（属性源级别覆盖，不落盘、重启即失），且要求容器里有 `EnvironmentManager` bean（spring-cloud-context 自动提供），否则整个 busenv 端点都不装配（5.2 节表格）。

## 8.4 ShutdownListener → SpringApplication.exit

最危险的一个：`ShutdownListener#shutdown()` 直接 `SpringApplication.exit(context, () -> 0)`（`event/ShutdownListener.java:57`）优雅关停进程。它默认就装配（`spring.cloud.bus.shutdown.enabled` 默认 true），所以**暴露 busshutdown 端点 = 把"远程关停集群"的按钮挂上了 HTTP**——必须与端点暴露控制、Spring Security 一起审视（下一节）。

## 8.5 端点暴露与安全

三个端点都不在 `management.endpoints.web.exposure.include` 的默认名单，需要显式暴露：

```properties
management.endpoints.web.exposure.include=busrefresh,busenv,busshutdown
```

生产环境的检查单：

1. **busenv / busshutdown 是高危端点**：前者让任意调用方注入全集群环境属性，后者直接远程关停。暴露前必须有认证授权（Spring Security / 网络隔离 / 管理端口独立）。
2. **busrefresh 相对安全**（只是触发重新拉取），但也应纳入管理面。
3. **消息总线本身要考虑访问控制**：能连上 MQ 的人就能往 topic 发伪造事件（伪造 RefreshRemoteApplicationEvent 只是扰乱，伪造 ShutdownRemoteApplicationEvent 则能关停实例）。内网 broker + 最小权限账号是底线。
4. 定向关停的用法（官方文档示例）：`curl -X POST http://localhost:8080/actuator/busshutdown/busid:123`——`@Selector(ALL_REMAINING)` 把路径段拼成 destination，寻址规则与 2.4/3.3 节完全一致。

## 8.6 本章小结

- 三个内置功能 = 三个本地 Listener，模式统一：监听事件 → isForSelf 双保险 → 委托 spring-cloud-context 执行。
- Refresh 链路只传"指令"，配置内容由实例重新拉取——Bus 与 Config 的职责边界。
- busenv 是临时属性注入（manager 属性源），busshutdown 是远程关停按钮：默认装配但默认不暴露，暴露必须带安全。
- 定向语义（destination 路径参数）对三个端点一视同仁，全部复用第三章的寻址体系。

---

# 九、传输层与运维：Stream 绑定、starter 选择、参数手册

## 9.1 三个 starter：纯依赖聚合

【源码证据】三个 starter 模块的 pom.xml（无任何 Java/资源文件）：

| starter | 聚合的依赖 | 适用 |
|---|---|---|
| `spring-cloud-starter-bus-amqp` | `spring-cloud-bus` + `spring-cloud-starter-stream-rabbit` | RabbitMQ（最常用） |
| `spring-cloud-starter-bus-kafka` | `spring-cloud-bus` + `spring-cloud-starter-stream-kafka` | Kafka |
| `spring-cloud-starter-bus-stream` | `spring-cloud-bus` + `spring-cloud-stream` | 任意 binder（Redis、Nats、自定义……） |

**Bus 对 broker 的全部要求就是"Stream 有 binder"**——broker 地址、账号、序列化参数全部沿用 Stream/对应 binder 的配置体系（如 `spring.rabbitmq.*`、`spring.kafka.*`），Bus 自己只有 `spring.cloud.bus.*` 一小节参数。官方文档原话：*"Spring Cloud Bus uses Spring Cloud Stream to broadcast the messages. So, to get messages to flow, you need only include the binder implementation of your choice in the classpath."*

## 9.2 消息拓扑：RabbitMQ 与 Kafka 上 Bus 长什么样

Bus 的绑定是"**无 group 的广播绑定**"——`BusEnvironmentPostProcessor` 只设置了 destination，从没设置 consumer group。按 Stream binder 的通用规则：

- **RabbitMQ**：bus 对应一个 Topic Exchange `springCloudBus`；每个实例启动时声明一个**匿名、auto-delete 的独占队列**，以 `#` 绑定到交换机——于是**每个实例都收到每条消息**（广播语义），实例下线队列自动删除。
- **Kafka**：绑定无 group 时，Kafka binder 给每个实例分配**随机匿名 consumer group**——每个 group 都独立消费全量消息，同样达成广播。

这正是 Bus "管理指令广播"语义在两种 broker 上的落地方式：**刻意避开消费组负载均衡，人人有份**。反过来说——如果你的部署里出现"只有部分实例收到刷新"，第一反应就是检查有没有哪层配置给总线绑定加了 group（那会把广播退化成负载均衡）。

其他运维要点：

- **一收一发共用一个 topic**（4.2 节）：RabbitMQ 上收发走同一交换机；自己发的消息自己也会收到，靠 6.5 节的两层过滤消化——这是架构上"用过滤换拓扑简单"的选择。
- **broker 不可用 = Bus 功能不可用**：Bus 没有本地降级通道；端点调用会在 Stream 发送处报错。启动时 broker 未就绪视 binder 的重试配置而定。
- 官方文档备注：使用 config-first bootstrap 的老项目，`spring.cloud.bus.destination` 要写在 `bootstrap.yml` 里，否则刷新后会被重置为默认值（Config Data 模式无此问题）。

## 9.3 spring.cloud.bus.* 参数手册（全表）

来自 `BusProperties`（`BusProperties.java:28-183`）与各自动装配的 `@ConditionalOnProperty`：

| 参数 | 默认值 | 作用 | 证据位置 |
|---|---|---|---|
| `spring.cloud.bus.enabled` | `true` | 总开关（所有配置类共享） | `ConditionalOnBusEnabled` |
| `spring.cloud.bus.id` | `app:profile?:port:random`（IdUtils 模板） | 本实例 bus id | EPP 第三颗钉子（3.2） |
| `spring.cloud.bus.destination` | `springCloudBus` | topic 名（收发共用） | EPP 第二颗钉子（4.2） |
| `spring.cloud.bus.content-type` | `application/json` | 声明的消息类型（**发送侧未消费，见 5.4 的 TODO**） | `BusProperties.contentType` |
| `spring.cloud.bus.ack.enabled` | `true` | 是否回发 ACK | `BusConsumer:73` |
| `spring.cloud.bus.ack.destination-service` | null → `**` | ACK 的目的地（可定向给审计节点） | `BusConsumer:75` |
| `spring.cloud.bus.trace.enabled` | `false` | 发布 Sent/Ack 本地审计事件；**HttpExchangeRepository 落库在本快照空转（6.4）** | `BusAutoConfiguration` 第 71-84 行 |
| `spring.cloud.bus.refresh.enabled` | `true` | RefreshListener 开关 | `BusRefreshAutoConfiguration:41` |
| `spring.cloud.bus.env.enabled` | `true` | EnvironmentChangeListener 开关 | `BusAutoConfiguration:89` |
| `spring.cloud.bus.shutdown.enabled` | `true` | ShutdownListener 开关 | `BusShutdownAutoConfiguration:37` |

相关但不在 `bus` 前缀下的：`spring.cloud.config.name`（configNames 匹配兜底，3.5 节）、`spring.cloud.function.definition`（EPP 会追加 busConsumer）、`spring.cloud.stream.bindings.springCloudBus{Input,Output}.*`（Stream 绑定细节）。

## 9.4 自定义 BusBridge：脱离 Stream 的传输

4.2 节"设计哲学 3"的落地示例——用 Redis pub/sub 替代 MQ（示意代码）：

```java
@Bean
public BusBridge redisBusBridge(RedisTemplate<String, byte[]> redis, BusConsumer consumer) {
    return new BusBridge() {
        @Override
        public void send(RemoteApplicationEvent event) {
            // 用 BusJacksonMessageConverter 的逻辑序列化，pub 到自定义 channel
        }
    };
}
// 另一侧：订阅 channel → 反序列化（含 type 多态处理）→ busConsumer.accept(event)
```

要点：`BusAutoConfiguration` 的三个核心 bean 全是 `@ConditionalOnMissingBean`，你自己注册 `BusBridge` 后 `StreamBusBridge` 自动退位（`BusStreamAutoConfiguration:40` 的条件）；接收侧复用 `busConsumer`（或直接调 `accept`）即可白拿寻址、ACK、防自环全部逻辑。**Bus 的扩展面比 Stream 小两个数量级，也正因此它 rarely 需要扩展**——大多数"自定义传输"的真实动机（不经 MQ）本身就不常见。

## 9.5 常见问题排查（收不到 / 重复触发 / 回环）

| 症状 | 排查点（按概率排序） |
|---|---|
| 广播只有部分实例收到 | ① 总线绑定被配置了 consumer group（退化成负载均衡，见 9.2）；② broker 侧 topic/queue 权限或防火墙；③ 目的地写法与 bus id 不匹配（3.3/2.4 的补全规则），开 debug 日志看 `matchMultiProfile` 的 pattern/path |
| 所有实例都收不到（自己也不处理） | ① bus 端点未暴露（`management.endpoints.web.exposure.include`）；② `spring.cloud.bus.enabled=false` 残留；③ broker 连接失败（看 Stream/binder 日志）；④ 自定义事件没被注册成子类型 → 实际上变成了 Unknown 事件（7.5），看日志与 `typeInfo` |
| 事件被处理两遍 | bus id 撞车？不——撞车是"都不处理"；重复处理更常见于**业务 Listener 没做幂等 + ACK/trace 干扰**，先核对处理路径是否真的只有 6.2 表第 1 行 |
| 实例收不到"发给自己服务"的事件 | 多 profile id 的匹配（3.4）、configNames 兜底是否生效（3.5）、destination 是否被写成了完整多段 id（超过两段不再补 `:**`） |
| bus id 不符合预期 | `spring.cloud.bus.id` 是否被占位符解析时序坑到（3.2：默认值是"未解析"模板，读取时才解析）；`server.port` 是否在 EPP 之后才确定 |

## 9.6 本章小结

- starter 三选一只是依赖聚合；broker 的一切配置归 Stream/ binder，Bus 只剩 `spring.cloud.bus.*` 十个参数。
- 广播语义 = 无 group 绑定（RabbitMQ 匿名队列 / Kafka 匿名组）；"部分收到"先查 group 污染。
- `content-type` 参数存在但发送侧未接线（TODO），改 CBOR 走 Stream 绑定配置。
- 自定义传输 = 实现 BusBridge + 复用 BusConsumer，核心 bean 全部可替换。
- 排查口诀：先看端点暴露与开关，再看 broker 连通与 group，最后开 debug 日志看 `DefaultBusPathMatcher` 的 pattern/path 对不对得上。

---

# 十、贯通视图：三条时间线看懂 Bus 全貌

## 10.1 时间线一：应用启动（Bus 的自我接线）

```
SpringApplication.run
 ├─ 配置文件加载（ConfigDataEnvironmentPostProcessor）
 ├─ BusEnvironmentPostProcessor.postProcessEnvironment        ← spring.factories
 │    ├─ spring.cloud.function.definition 追加 "busConsumer"   （最高优先级属性源）
 │    ├─ busConsumer-in-0 别名 springCloudBusInput
 │    ├─ 两个绑定 destination = spring.cloud.bus.destination   （最低优先级属性源）
 │    └─ spring.cloud.bus.id 默认 = IdUtils 模板（未解析占位符）
 ├─ EnvironmentPostProcessor 全部跑完 → 启动容器 refresh()
 │    ├─ PathServiceMatcherAutoConfiguration → busPathMatcher(AntPathMatcher(":")) + PathServiceMatcher
 │    ├─ BusStreamAutoConfiguration → StreamBusBridge（有 Stream 时）
 │    ├─ BusJacksonAutoConfiguration → busJsonConverter（扫包注册事件子类型）
 │    ├─ BusAutoConfiguration → PathDestinationFactory + RemoteApplicationEventListener + BusConsumer
 │    ├─ BusRefreshAutoConfiguration → RefreshListener + busrefresh 端点
 │    └─ BusShutdownAutoConfiguration → ShutdownListener + busshutdown 端点
 └─ Stream 装配：busConsumer 注册为函数消费者，绑定到 topic springCloudBus（匿名队列/匿名组）
```

## 10.2 时间线二：`POST /actuator/busrefresh` 的发送方一生

```
HTTP 请求 → RefreshBusEndpoint.busRefreshWithDestination(destinations)
  → destination 补全（PathDestinationFactory）
  → publish(RefreshRemoteApplicationEvent(origin=本机busId, destination=补全后))
  → 本地事件广播 → RemoteApplicationEventListener（isFromSelf ✔，非 ACK ✔）
  → StreamBusBridge.send → StreamBridge.send("springCloudBusOutput", payload=事件)
  → Jackson 序列化（type 字段）→ binder → topic springCloudBus
```

## 10.3 时间线三：一条消息在接收方的一生

```
topic → binder → 匿名队列/消费组 → 函数 busConsumer 消息到达
  → BusJacksonMessageConverter.convertFromInternal（type → 子类；认不出 → Unknown 兜底）
  → BusConsumer.accept
      ├─ isForSelf？（destination 匹配 bus id，含多 profile 展开与 configNames 兜底）
      │    ├─ 非 ACK 且 !isFromSelf → publisher.publishEvent(event)
      │    │     → RefreshListener/EnvironmentChangeListener/自定义 Listener 执行
      │    ├─ ack.enabled → AckRemoteApplicationEvent（ackId=原id）→ bridge 直发 + 本地广播
      │    └─ trace.enabled → SentApplicationEvent 本地广播
      └─ 与我无关 → 忽略（但 ACK 已被提前 return 掉，Sent 信号照发）
```

三条线交汇处的"必经之路"只有四个类：**BusEnvironmentPostProcessor（启动接线）、PathServiceMatcher（身份判定）、RemoteApplicationEventListener/BusConsumer（出港与入港）**。读透了它们，Bus 的 38 个类里剩下的大多是"端点、事件、Listener 的薄壳"。

---

# 十一、附录

## 11.1 关键接口/类速查表

| 类/接口 | 一句话定位 | 关键方法 |
|---|---|---|
| `RemoteApplicationEvent` | 一切跨进程事件的基类（三要素 + type） | 构造器、getter |
| `Destination.Factory` / `PathDestinationFactory` | 目的地补全规则 | `getDestination()` |
| `ServiceMatcher` / `PathServiceMatcher` | 寻址与防自环的判定者 | `isForSelf` / `isFromSelf` / `getBusId` |
| `DefaultBusPathMatcher` | 多 profile 展开匹配 | `match` / `matchMultiProfile` |
| `BusBridge` / `StreamBusBridge` | 传输抽象与 Stream 实现 | `send` |
| `RemoteApplicationEventListener` | 本地事件上桥闸口（防自环第一闸） | `onApplicationEvent` |
| `BusConsumer` | 总线消息落地与 ACK（全文心脏） | `accept` |
| `BusEnvironmentPostProcessor` | 启动接线三颗钉子 | `postProcessEnvironment` |
| `RefreshBusEndpoint` / `EnvironmentBusEndpoint` / `ShutdownBusEndpoint` | 三个 Actuator 端点 | `busRefresh*` / `busEnv*` / `busShutdown*` |
| `RefreshListener` / `EnvironmentChangeListener` / `ShutdownListener` | 三个内置功能的执行端 | `onApplicationEvent` |
| `BusJacksonMessageConverter` | 事件多态转换器 | `convertFromInternal` / `afterPropertiesSet` |
| `@RemoteApplicationEventScan` + Registrar | 自定义事件包注册（合并语义） | `registerBeanDefinitions` |
| `UnknownRemoteApplicationEvent` | 认不出的消息的容器 | `getPayloadAsString` |
| `IdUtils`（spring-cloud-commons） | bus id 默认模板 | `getUnresolvedServiceId*` |

## 11.2 源码阅读入口清单（15 个关键文件）

按推荐阅读顺序（均在 `spring-cloud-bus/src/main/java/org/springframework/cloud/bus/` 下）：

1. `event/RemoteApplicationEvent.java` —— 世界观的起点
2. `event/PathDestinationFactory.java` —— 三行 if 的寻址补全
3. `PathServiceMatcher.java` + `DefaultBusPathMatcher.java` —— 身份与匹配
4. `BusEnvironmentPostProcessor.java` —— 启动接线
5. `BusAutoConfiguration.java` —— 装配中枢
6. `RemoteApplicationEventListener.java` —— 出港（全文 50 行）
7. `BusConsumer.java` —— 入港 + ACK（全文 89 行）
8. `endpoint/RefreshBusEndpoint.java` —— 端点薄壳的样子
9. `event/RefreshListener.java` —— 功能执行端的样子
10. `event/AckRemoteApplicationEvent.java` —— 回执协议
11. `jackson/BusJacksonAutoConfiguration.java` —— 多态序列化（含转换器）
12. `jackson/RemoteApplicationEventRegistrar.java` —— Registrar 模式范例
13. `event/UnknownRemoteApplicationEvent.java` —— 兜底容器
14. `BusStreamAutoConfiguration.java` —— 传输装配与排序
15. `BusProperties.java` —— 全参数

## 结语

Spring Cloud Bus 是那种"**用最小代码量服务最大场景**"的典型：38 个类、一条接口（BusBridge）、一个抽象类（RemoteApplicationEvent）、一个函数（BusConsumer），就撑起了"分布式 Actuator"的全部语义。它的每个设计决定都值得初学者反复咀嚼——

- **用路径匹配做寻址**，免掉了成员管理与中心协调，代价是 bus id 必须唯一且格式敏感；
- **用本地事件总线做进程内的路由**，让 MQ 与业务 Listener 完全解耦，代价是要用两套过滤防自环；
- **用"指令广播 + 各自拉取"与 Config 分工**，Bus 永远不搬运数据本身；
- **用 Unknown 事件兜底多态反序列化**，让事件的演进不要求集群同步升级。

读它的源码，本质上是在读一份"如何用 1000 行代码设计一个分布式协议"的微型教科书。当你能在白板上画出第十章那三条时间线时，你就已经超过了 90% 只会 `curl /actuator/busrefresh` 的使用者。

> **相关文档**：《Spring Framework.md》（事件机制、路径匹配、BeanDefinition 注册的底层）、《Spring Boot.md》（自动装配与 EnvironmentPostProcessor 时序）、《Spring Cloud Config.md》（RefreshScope 与 ContextRefresher 的完整链路、config-monitor 与 Bus 的联动）。
