# Spring Cloud Stream 深度源码解析（写给初学者的架构全景）

> **本文基于的源码**：`D:\code\3rd\spring-cloud-stream`，版本 **5.0.3**（tag `v5.0.3`，发布于 2026-08-10，Git commit `ec87bc8fa1`），配套的函数内核 `D:\code\3rd\spring-cloud-function`，版本 **5.0.4**（tag `v5.0.4`，Stream 5.0.3 的 pom 声明依赖 `spring-cloud-function.version=5.0.4`）。文中所有【源码证据】的文件路径与行号均为对这两个快照实际读取所得。
>
> **版本基线**：Spring Cloud Stream 5.0.x 运行在 Spring Boot 4.0.x / Spring Framework 7.0.x 之上（Framework 7.0 GA 于 2025-11-13，Stream 5.0.0 GA 于 2025-11-24），已迁移到 Jackson 3（`tools.jackson`）与 Framework 7 内建重试（`org.springframework.core.retry`）。本文对应《Spring Framework.md》所分析的同一代基线。
>
> **阅读约定**：与同系列文档一致，每章"先白话、后源码"——先用一两句人话讲清"这是什么、为什么需要它"，再给出源码证据链（`模块/src/main/java/.../类名.java` + 方法名 + 行号 + 代码片段）。行号只对该快照精确，读者用 IDEA 打开源码按类名 + 方法名定位即可。所有"某特性属于哪个版本"的结论均经本地 git 标签（v3.0.0.RELEASE ~ v5.0.3）逐项实证。

## 如何读这份文档

如果你是 Spring Cloud Stream 初学者，推荐两遍读法：

- **第一遍（建立地图，1~2 小时）**：只读第一章（总览）每节开头白话段、第二章（上手与概念）、各章的"小结"节、以及第十章（贯通视图）。目标是能回答：一个 `Function` Bean 如何长出两条绑定并连上 Kafka？`StreamBridge.send` 从调用到出网经过了哪些环节？消费失败后重试与 DLQ 是谁在驱动？
- **第二遍（深入源码）**：对照每一章的【源码证据】逐行读。顺序建议：第三章（启动装配，回答"绑定从哪来"）→ 第四章（Binder SPI，回答"中间件无关怎么实现"）→ 第五章（收发全链路）→ 第七章（错误处理）→ 第六章（转换，随用随查）→ 第八章（高级特性，按需跳读）→ 第九章（两个官方 binder 范本）。

---

# 一、总览：Spring Cloud Stream 的定位、设计哲学与整体架构

## 1.1 一句话定位

**Spring Cloud Stream 是一个"函数驱动的消息中间件门面"**：你只写一个普通的 `Supplier` / `Function` / `Consumer` Bean（发数据、处理数据、收数据），框架负责把它与 Kafka / RabbitMQ / Pulsar 等外部目的地（destination）之间的所有管道——通道创建、连接建立、序列化转换、分区路由、重试与死信——全部装配好。中间件的差异被收进一个叫 **Binder** 的 SPI 后面，切换中间件理论上只需换依赖、改配置，业务函数一行不动。

它解决的不是"怎么用 Kafka"（那是 spring-kafka 的事），而是**消息中间件的组织问题**：业务代码与中间件 API 的解耦（Binder 抽象）、消息处理逻辑的声明化（函数式模型）、跨中间件统一的语义（消费组、分区、重试、死信、内容类型），以及与 Spring 生态其他部分的接线（生命周期、Actuator、可观测性、AOT）。

一个容易混淆的点先说清楚：Spring Cloud Stream **不是消息中间件的客户端封装库**——Kafka 客户端、AMQP 客户端、监听容器都是 spring-kafka / spring-amqp 的类；Stream 的核心代码（core 模块）里**没有任何一行中间件 API**。它卖的是"绑定"这件事的编排能力，这一点读完全文回头再看会非常清楚。

## 1.2 设计哲学：读源码前先记住四句话

1. **函数即消息处理器**。3.0 起编程模型完全围绕 java.util.function：`Supplier` 是消息源、`Function` 是处理器、`Consumer` 是汇点，多个函数用 `;` 分隔、用 `|` 组合。框架把"哪个函数绑定哪条管道"的答案从注解声明（`@Input`/`@Output` 时代）变成了**命名约定**：函数 `uppercase` 的输入绑定名必然是 `uppercase-in-0`、输出必然是 `uppercase-out-0`（见 3.3 节）。Spring Cloud Function 提供函数目录（FunctionCatalog）与统一调用包装器（`FunctionInvocationWrapper`），Stream 在其上追加绑定语义。
2. **Binder 是消息中间件的 JDBC**。应用代码面对的是 `MessageChannel` / `PollableMessageSource` 这类 Spring Messaging 抽象，Binder 负责把它们"焊接"到具体中间件——正如 JDBC 把 SQL 应用焊接到底层数据库。Binder 的能力边界被定义得非常克制：一个 SPI 三件套 `Binder`（绑定动作）+ `ProvisioningProvider`（目的地供给）+ 属性体系（`ConsumerProperties`/`ProducerProperties` 及扩展），core 模块用 `AbstractMessageChannelBinder` 一个 1300 行的模板类把"绑定流程 + 错误基础设施 + 头嵌入"等公共逻辑全部做完，各官方 binder 只需实现两三个工厂方法（见 4.2 节）。
3. **一切绑定都长在 Spring Integration 管道上**。core 模块内部构造的每一个"绑定目标"都是 SI 组件：输入绑定是 `DirectWithAttributesChannel`（SI `DirectChannel` 子类）、Supplier 的轮询源是 `SourcePollingChannelAdapter`（绑定名 `_spca` 后缀）、响应式绑定是 `FluxMessageChannel` + `ReactiveStreamsConsumer`、错误基础设施是 `PublishSubscribeChannel` + `BridgeHandler`。理解 Stream 的钥匙之一是：**所谓"绑定"，就是把一个 SI 端点接到一个通道上，再把通道交给中间件监听器/发送器**（见 3.6、5.1 节）。
4. **配置驱动、可运维、可静态化**。绑定行为几乎全部由分层属性决定（`spring.cloud.stream.bindings.<bindingName>.*` → `spring.cloud.stream.<consumer|producer>.*` 默认值 → binder 扩展属性），且每条绑定在运行期是一个可查询、可暂停、可重启、可动态创建的实体（Actuator 端点 `BindingsEndpoint`、`BindingsLifecycleController`，见 8.5 节）。对云原生，binder 子上下文可被 AOT 预生成（`DefaultBinderFactory` 的 child-context-initializer 路线，4.4 节），并且支持 CRaC 检查点恢复（`DefaultBinderFactory` 实现 `SmartLifecycle` 供恢复后 start/stop）。

## 1.3 模块分层全景

仓库实测目录（v5.0.3，`ls` 根目录）只有 6 个顶层模块组：`core`、`binders`、`schema-registry`、`tools`、`docs`、`bom`。按"用户感知"分层如下：

```
┌─────────────────────────── 测试层 ───────────────────────────────┐
│ core/spring-cloud-stream-test-binder（TestChannelBinder +        │
│   InputDestination/OutputDestination，单测用假 binder）           │
│ core/spring-cloud-stream-test-support（binder 实现者的测试基类）  │
├─────────────────────────── Binder 层 ────────────────────────────┤
│ binders/kafka-binder                                             │
│   ├ spring-cloud-stream-binder-kafka-core（公共：provisioner、   │
│   │   属性、health、EnvironmentPostProcessor）                   │
│   ├ spring-cloud-stream-binder-kafka（KafkaMessageChannelBinder）│
│   └ spring-cloud-stream-binder-kafka-streams（KStream/KTable）   │
│ binders/rabbit-binder（RabbitMessageChannelBinder）              │
│ binders/pulsar-binder（PulsarMessageChannelBinder）              │
├─────────────────────────── 内核 ────────────────────────────────┤
│ core/spring-cloud-stream（本文主角：binder SPI、绑定编排、        │
│   函数式装配、StreamBridge、转换、错误基础设施、端点）             │
│   ↑ 依赖 spring-cloud-function-context（函数目录/类型系统）       │
│   ↑ 依赖 spring-integration-core + spring-messaging（消息管道）   │
├─────────────────────────── 周边 ────────────────────────────────┤
│ schema-registry/*（Avro 消息转换与独立 schema server，可选）      │
│ tools/*（kafka/rabbit 本地开发辅助脚本）                          │
└──────────────────────────────────────────────────────────────────┘
```

core 模块内部再分 9 个包（`core/spring-cloud-stream/src/main/java/org/springframework/cloud/stream/` 下实测）：`binder`（SPI 与模板类）、`binding`（绑定编排与生命周期）、`config`（自动配置与属性）、`function`（函数式模型与 StreamBridge）、`converter`（消息转换）、`provisioning`（目的地供给 SPI）、`endpoint`（Actuator 端点）、`messaging`（`DirectWithAttributesChannel`）、`aot` / `utils` / `annotation` / `reflection`（支撑）。

## 1.4 模块依赖图（以 core 模块 pom 实证）

core 模块 `core/spring-cloud-stream/pom.xml` 声明的关键依赖（已去重过滤插件类条目）：

| 依赖 | 角色 |
|---|---|
| spring-cloud-function-context | 函数目录、类型系统（FunctionTypeUtils）、`FunctionInvocationWrapper`、Jackson 包装（JacksonMapper） |
| spring-integration-core | 消息管道内核：DirectChannel / FluxMessageChannel / SourcePollingChannelAdapter / ReactiveStreamsConsumer |
| spring-messaging | Message / MessageChannel / MessageConverter 契约 |
| spring-boot-integration、spring-boot-autoconfigure | 自动配置与属性绑定 |
| spring-boot-starter-actuator | BindingsEndpoint / health 端点 |
| context-propagation（micrometer） | 响应式上下文传播（ThreadLocal 快照） |
| jackson-databind / jackson-annotations | JSON 转换（Boot 4 代已用 Jackson 3 `tools.jackson`，见 6.2 节） |

```
        用户业务函数（Supplier/Function/Consumer Bean）
                    ▲ 查找与调用
            spring-cloud-function-context
                    ▲ 绑定语义装配
              spring-cloud-stream (core)
              ▲                ▲
     spring-integration      spring-boot
       （管道内核）          （属性/端点/生命周期）
              ▲
   spring-cloud-stream-binder-{kafka|rabbit|pulsar}
              ▲
   spring-kafka / spring-amqp / pulsar-client（真正的中间件客户端）
```

这张图的要害在最后一层：**binder 模块才是中间件客户端的依赖方，core 与中间件完全隔离**。这也是"换中间件不动业务代码"的结构保证——依赖树上的隔离是硬约束，比任何注释都可靠。

## 1.5 关键问题 → Spring Cloud Stream 方案映射（全文导览）

| 企业消息开发的关键问题 | Stream 的方案 | 详见 |
|---|---|---|
| 业务代码与 Kafka/Rabbit API 强耦合，换中间件要重写 | Binder SPI + `AbstractMessageChannelBinder` 模板 + 子上下文隔离 | 第四章 |
| 消息处理逻辑分散在通道配置、适配器、监听器里 | 函数式模型：函数 Bean + 命名约定自动绑定 | 第三章 |
| 事件驱动场景之外还有命令式/临时发送需求 | `StreamBridge`（动态目的地 + 通道缓存 + 复用绑定语义） | 5.4 节 |
| 发送端序列化、接收端反序列化到处散落 | contentType 属性 + 通道拦截器 + `CompositeMessageConverter` | 第六章 |
| 消费失败怎么办（重试几次、丢弃还是进死信） | 重试属性 → Framework 7 RetryTemplate → 错误通道 → binder DLQ | 第七章 |
| 数据要按 key 路由到固定分区（顺序性保证） | `PartitionHandler` + KeyExtractor/Selector 策略 + 分区头 | 8.1 节 |
| 想主动拉而不是被动推（批处理场景） | `PollableMessageSource` + 手动/自动 Ack 回调 | 8.2 节 |
| 流式计算 / 多输入输出 | Flux/Mono 函数 + `ReactiveStreamsConsumer` + Tuples 多输出 | 8.3 节 |
| 同一应用要连多个集群/多种中间件 | 多 binder 配置 + per-binding binder 指定 | 4.5 节 |
| 运行期要查/停/启/暂停某条绑定 | `BindingsEndpoint` + `BindingsLifecycleController` + `Pausable` | 8.5 节 |
| 单元测试不想起 Kafka/Rabbit | TestChannelBinder + InputDestination/OutputDestination | 8.7 节 |

## 1.6 版本演进：3.x → 4.x → 5.x 关键变化对比

写作时（2026 年 10 月）的版本格局：3.x / 4.x 已结束 OSS 维护，5.0.x 是当前主线（对应 Boot 4.x / Framework 7.x），5.1 处于里程碑阶段（v5.1.0-M1，2026-09-18）。本节所有"特性属于哪个版本"的结论都经过**本地仓库 git 标签逐项 grep 实证**。

### 1.6.1 版本时间线与运行基线

| 版本 | GA 时间（tag 提交日期实测） | 运行基线 | 一句话主题 |
|---|---|---|---|
| 3.0 | 2019-11-22 | Boot 2.2 | **函数式模型登场**：`FunctionConfiguration` 引入，`@EnableBinding`/`@StreamListener` 降级为遗留 |
| 3.1 | 2020-12-21 | Boot 2.4 | **StreamBridge 发布**（v3.0.0.RELEASE 中不存在该类、v3.1.0 首次出现）；注解模型标记废弃 |
| 3.2 | 2021-12-01 | Boot 2.6 | 函数式模型全面稳定，注解类仍在（`@interface EnableBinding`/`@interface StreamListener` 实测存在） |
| 4.0 | 2022-12-15 | Boot 3.0 / Jakarta EE | **注解模型删除**（两个注解类实测消失）+ jakarta 迁移 + AOT 正式化 |
| 4.1 | 2023-12-06 | Boot 3.2 | 稳定迭代 |
| 4.2 | 2024-12-03 | Boot 3.4 | `spring.cloud.stream.input-bindings`/`output-bindings` 独立绑定（属性实测已存在） |
| 5.0 | 2025-11-24 | **Boot 4.0 / Framework 7.0** | **基线大迁移**：Jackson 3（`tools.jackson`）、Framework 7 内建重试（`org.springframework.core.retry`）替换 spring-retry、binder 子上下文 AOT 预生成 |
| 5.0.3（本文快照） | 2026-08-10 | 同上 | 补丁维护线 |
| 5.1（快照） | 预计 2026 年末 | Boot 4.1 | 迭代增强（v5.1.0-M1 已发布） |

### 1.6.2 特性引入版本对照表（git 实证，可复现）

每行都可用 `git grep -l "<关键字>" <tag> -- '*.java'` 复现：

| 特性 | 引入版本 | 实证（旧 tag → 新 tag） | 详见 |
|---|---|---|---|
| PollableMessageSource（轮询消费者） | 2.1 | v2.1.0.RELEASE 已存在 `binder/PollableMessageSource.java` | 8.2 |
| 函数式模型（FunctionConfiguration） | 3.0 | v3.0.0.RELEASE 已存在 `function/FunctionConfiguration.java` | 三 |
| StreamBridge | 3.1 | v3.0.0.RELEASE 全源码 0 命中 → v3.1.0 存在 | 5.4 |
| `@EnableBinding`/`@StreamListener` **移除** | 4.0 | v3.2.0 两个注解存在 → v4.0.0 消失 | — |
| `input-bindings`/`output-bindings` 独立绑定 | ≤4.1 | v4.1.0 `BindingServiceProperties` 中 `outputBindings` 属性已存在 | 3.3 |
| 重试内核 spring-retry → Framework 7 `core.retry` | 5.0 | v4.3.3 `AbstractBinder` import `org.springframework.retry.*`；v5.0.3 import `org.springframework.core.retry.*` | 7.2 |
| Jackson 2 → Jackson 3（tools.jackson） | 5.0 | v4.3.3 全源码 import `com.fasterxml.jackson`；v5.0.3 core import `tools.jackson.*` | 6.2 |
| binder 子上下文 AOT 预生成（BinderChildContextInitializer） | 5.0 | v4.3.3 无此类 → v5.0.3 `binder/BinderChildContextInitializer.java` | 4.4 |
| CRaC 支持（binder 工厂 start/stop 生命周期） | ≤5.0 | v5.0.3 `DefaultBinderFactory` 实现 SmartLifecycle，注释明言 "CRaC checkpoint" | 4.3 |

三个容易搞错的点，特别提醒：

- **StreamBridge 不是 3.0 特性**：大量老文章写"3.0 引入 StreamBridge"，实际 v3.0.0.RELEASE 源码里没有这个类，它随 3.1.0 发布（类头 `@since 3.0.3` 指的是 3.0.x 生命周期内的提交历史，GA 随 3.1）。
- **重试不是 spring-retry 了**：5.0 起 `AbstractBinder.buildRetryTemplate` 构造的是 Framework 7 的 `org.springframework.core.retry.RetryTemplate`（注意不是 `org.springframework.retry`），这在升级 Boot 4 时会直接影响自定义重试策略的扩展点。
- **"注解模型在 3.x 已不可用"是错的**：`@EnableBinding`/`@StreamListener` 在 3.x 全程可用（只是废弃），4.0 才真正删除代码；网上"迁移到 3.x 必须重写"的说法过头了。

### 1.6.3 三大版本主题对比

| 维度 | 3.x（2019~2021） | 4.x（2022~2024） | 5.x（2025~） |
|---|---|---|---|
| 编程模型 | 函数式（新）+ 注解式（废弃中） | 仅函数式 | 仅函数式 |
| 运行基线 | Boot 2.x + javax.* | Boot 3.x + jakarta.* | Boot 4.x + Framework 7 |
| JSON | Jackson 2（com.fasterxml） | Jackson 2 | Jackson 3（tools.jackson） |
| 重试 | spring-retry | spring-retry | Framework 7 内建 RetryTemplate |
| 命令式发送 | StreamBridge（3.1 起） | StreamBridge 增强（async 等） | + 动态目的地缓存上限（dynamicDestinationCacheSize） |
| 原生化 | — | GraalVM 逐步适配 | binder 子上下文 AOT 预生成 + CRaC |

### 1.6.4 对初学者的意义：哪些知识过时了，哪些永远有效

- **直接跳过**：`@EnableBinding` / `@StreamListener` / `@Input` / `@Output` / `Processor`/`Source`/`Sink` 接口的一切资料——4.0 已删除，教程里看到即视为过时。
- **永久有效**：函数式模型三件套、绑定命名约定（`<函数>-in-0`）、destination/group 语义、contentType 转换规则、重试与错误通道的分层——这些从 3.0 沿用至今，本文第二~七章正是按这条主线写的。
- **版本与 Boot 的对应关系**：Boot 2.2~2.6 ↔ Stream 3.x；Boot 3.0~3.4 ↔ Stream 4.0~4.2；Boot 4.0 ↔ Stream 5.0。从 Boot 3.x 入手的读者用的是 4.x 内核，与本文 5.0.3 的差异主要是重试内核与 Jackson 版本。

## 1.7 全文章节地图

- **第二章 上手与核心概念**：用 20 行代码 + 一段 yml 建立"函数—绑定—目的地—组—Binder"五个名词的画面感，给出绑定命名约定的完整规则。
- **第三章 启动装配（重点 A）**：从自动配置入口开始，走完 `FunctionBindingRegistrar` → `BindableFunctionProxyFactory` → `FunctionToDestinationBinder` / `supplierInitializer` → `InputBindingLifecycle`/`OutputBindingLifecycle` 的完整装配链。
- **第四章 Binder SPI 与 Binder 工厂（重点 B）**：三接口一模板类的 SPI 结构；`DefaultBinderFactory` 如何用 `META-INF/spring.binders` 发现 binder、如何为每个 binder 建子上下文、默认 binder 决策树、AOT 与 CRaC。
- **第五章 消费与生产全链路（重点 C）**：`doBindProducer`/`doBindConsumer` 的模板流程、`SendingHandler`、一条消息从 Kafka 网卡到业务函数的完整旅程、`StreamBridge` 全解。
- **第六章 消息转换与内容类型（重点 D）**：contentType 从属性到头、inbound/outbound 拦截器、内置转换器清单、原生编解码的取舍。
- **第七章 错误处理、重试与 DLQ（重点 E）**：错误通道命名、`registerErrorInfrastructure` 的四层订阅结构、Framework 7 重试、用户错误处理函数、Kafka/Rabbit 两种 DLQ 风格。
- **第八章 高级特性**：分区、轮询消费、响应式与多输入输出、RoutingFunction、运维端点、可观测性与 AOT、测试支持。
- **第九章 两个官方 Binder 深入**：Kafka binder（provisioner、监听容器、事务、DLQ）与 Rabbit binder（声明式拓扑、republishToDlq）作为实现范本。
- **第十章 贯通视图**：启动、发送、消费三条时间线叠加成全景图。
- **第十一章 附录**：接口速查表、配置命名规则、学习路线、源码阅读入口清单。

---

# 二、上手与核心概念：先建立整体画面

## 2.1 二十行代码看懂编程模型

**（白话）** Stream 5.x 的应用代码只有三样东西：业务函数 Bean、一段绑定属性、一个 binder 依赖。下面是一个完整可跑的"收字符串、转大写、发出去"应用：

```java
@SpringBootApplication                      // 无任何 Stream 注解
public class DemoApplication {
    @Bean
    public Function<String, String> uppercase() {
        return s -> s.toUpperCase();
    }
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
```

```yaml
spring:
  cloud:
    function:
      definition: uppercase            # 要绑定的函数（单个函数可省略，见 3.2 自动发现）
    stream:
      bindings:
        uppercase-in-0:
          destination: raw-words       # 输入目的地：Kafka topic / Rabbit exchange
          group: demo-group            # 消费组（输出绑定没有 group）
        uppercase-out-0:
          destination: upper-words
```

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-stream-binder-kafka</artifactId>
</dependency>
```

启动后框架自动完成：注册 `KafkaMessageChannelBinder`（来自 `META-INF/spring.binders`）→ 发现 `uppercase` 函数 → 创建名为 `uppercase-in-0`、`uppercase-out-0` 的通道 → 分别与 binder 执行 `bindConsumer("raw-words", "demo-group", ...)` 和 `bindProducer("upper-words", ...)` → `uppercase` 函数被一个消息处理器订阅到输入通道、其返回值被发往输出通道。此后 Kafka topic `raw-words` 里的每条消息都会经过你的函数，结果自动落到 `upper-words`。

## 2.2 五个名词

- **函数（Function Bean）**：业务逻辑本体，`Supplier`（无入参，框架周期性调用取值发送）、`Function`（入站→出站）、`Consumer`（只入站）。函数可以返回 `Mono`/`Flux`（8.3 节）。
- **绑定（Binding）**：一条通道与一个外部目的地之间的关联关系，运行期是一个 `DefaultBinding` 对象（可 start/stop/pause，见 3.6）。绑定名在函数式模型下就是通道名。
- **目的地（destination）**：中间件侧的物理实体名（topic / exchange+queue）。绑定名与目的地名默认相同，用 `spring.cloud.stream.bindings.<bindingName>.destination` 可以让两者解耦（一处代码、多环境不同 topic）。
- **消费组（group）**：同一 `destination` 上同组消费者分摊分区（Kafka consumer group / Rabbit 竞争队列）；不设 group 则为匿名订阅，每个实例都能收到全量消息。
- **Binder**：中间件适配器。每个 binder 在自己的**子应用上下文**里构建（4.4 节），对外只暴露 `bindConsumer`/`bindProducer` 两个动作。

## 2.3 绑定命名约定（源码级规则）

命名约定的源头在 `BindableFunctionProxyFactory`：

【源码证据】`core/spring-cloud-stream/src/main/java/org/springframework/cloud/stream/function/BindableFunctionProxyFactory.java` `buildInputNameForIndex`/`buildOutputNameForIndex` 行 157-178：

```java
private String buildInputNameForIndex(int index) {
    if (!this.isFunctionExist()) {
        return this.functionDefinition;               // 独立绑定：绑定名即函数定义本身
    }
    return new StringBuilder(this.functionDefinition.replace(",", "|").replace("|", ""))
        .append(FunctionConstants.DELIMITER)          // "-"
        .append(FunctionConstants.DEFAULT_INPUT_SUFFIX)   // "in"
        .append(FunctionConstants.DELIMITER)
        .append(index)                                // 从 0 开始
        .toString();                                  // → "uppercase-in-0"
}
```

| 函数签名 | 输入绑定名 | 输出绑定名 |
|---|---|---|
| `Supplier<String>` | （无） | `supplier-out-0` |
| `Function<String, Integer>` | `fn-in-0` | `fn-out-0` |
| `Consumer<String>` | `c-in-0` | （无） |
| `BiFunction<String, String, Integer>` | `fn-in-0`、`fn-in-1` | `fn-out-0` |
| 多输出函数（返回 `Tuple2<Flux, Flux>`） | `fn-in-0` | `fn-out-0`、`fn-out-1` |

绑定名可被改写为别名：`spring.cloud.stream.function.bindings.uppercase-in-0=input`（重命名后所有 `bindings.uppercase-in-0.*` 属性要改写到 `bindings.input.*`），改名逻辑见 3.3 节 `createInput` 开头。

---

# 三、启动装配：一个函数如何长出两条绑定（重点 A）

> 本章对应源码：`core/spring-cloud-stream` 的 `function`、`binding`、`config` 三个包。这是理解 Stream 的**主入口**——所有其他机制（Binder、转换、错误处理）都是这条装配链上被调用的环节。

## 3.1 自动配置入口与先后顺序

**（白话）** Stream 的启动装配由 5 个自动配置类接力完成，顺序是精心编排的：先把"函数世界"准备好，再让"绑定世界"去找函数。

【源码证据】`core/spring-cloud-stream/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（全文件）：

```
org.springframework.cloud.stream.config.BindersHealthIndicatorAutoConfiguration
org.springframework.cloud.stream.config.ChannelsEndpointAutoConfiguration
org.springframework.cloud.stream.config.BindingsEndpointAutoConfiguration
org.springframework.cloud.stream.config.BindingServiceConfiguration
org.springframework.cloud.stream.function.FunctionConfiguration
```

两个装配次序约束（`FunctionConfiguration` 类头，行 139-146）：

```java
@Lazy(false)
@AutoConfiguration
@EnableConfigurationProperties(StreamFunctionConfigurationProperties.class)
@Import({ BinderFactoryAutoConfiguration.class })
@AutoConfigureBefore(BindingServiceConfiguration.class)        // 先于绑定服务装配
@AutoConfigureAfter(ContextFunctionCatalogAutoConfiguration.class) // 后于函数目录装配
@ConditionalOnBean(FunctionRegistry.class)                     // 没有 FunctionCatalog 就整个不生效
public class FunctionConfiguration {
```

三个装配体各司其职：

| 装配类 | 职责 |
|---|---|
| `FunctionConfiguration` | 函数绑定编排（本章主角），并 `@Import` 下一行 |
| `BinderFactoryAutoConfiguration` | binder 类型注册表、绑定目标工厂、参数解析器（4.3 节） |
| `BindingServiceConfiguration` | BindingService、生命周期 bean、binder 工厂 bean（4.5 节） |

另外 core 的 `META-INF/spring.factories` 注册了三个早期钩子：`RoutingFunctionEnvironmentPostProcessor`（环境后置）、`PollerConfigEnvironmentPostProcessor`、`VersionExtractor`，以及一个上下文初始化器 `PollableSourceInitializer`（8.2 节）。

## 3.2 FunctionBindingRegistrar：从函数定义到代理工厂

**（白话）** 这一步回答"我写了函数 Bean，框架怎么知道要给它建绑定"。规则是：先找 `spring.cloud.function.definition` / `spring.cloud.stream.function.definition` 显式声明；没声明但开了路由则用 `functionRouter`；再没有就"自动发现"（目录里只有唯一函数时直接用它）。

【源码证据】`function/FunctionConfiguration.java` 内部类 `FunctionBindingRegistrar#determineFunctionName` 行 1021-1042：

```java
private boolean determineFunctionName(FunctionCatalog catalog, Environment environment) {
    boolean autodetect = environment.getProperty("spring.cloud.stream.function.autodetect", boolean.class, true);
    String definition = streamFunctionProperties.getDefinition();
    if (!StringUtils.hasText(definition)) {
        definition = environment.getProperty("spring.cloud.function.definition");   // ① 显式声明
    }
    if (StringUtils.hasText(definition)) {
        streamFunctionProperties.setDefinition(definition);
    }
    else if (Boolean.parseBoolean(environment.getProperty("spring.cloud.stream.function.routing.enabled", "false"))
            || environment.containsProperty("spring.cloud.function.routing-expression")) {
        streamFunctionProperties.setDefinition(RoutingFunction.FUNCTION_NAME);     // ② 路由模式
    }
    else if (autodetect) {
        FunctionInvocationWrapper function = functionCatalog.lookup("");
        if (function != null) {
            streamFunctionProperties.setDefinition(function.getFunctionDefinition()); // ③ 唯一函数自动发现
        }
    }
    return StringUtils.hasText(streamFunctionProperties.getDefinition());
}
```

确定函数定义后，`afterPropertiesSet`（行 902-954）按函数类型数出输入/输出个数并**为每个函数定义注册一个 `BindableFunctionProxyFactory` bean**（行 929-943）：Supplier 记 0 入 1 出；Consumer/路由函数记 N 入 0 出；普通 Function 记 N 入 M 出（个数由泛型签名解析，`FunctionTypeUtils.getInputCount/getOutputCount`）。签名带 `Publisher` 的函数会走带 `SupportedBindableFeatures(reactive=true)` 的构造（行 930-937）。

`function definition` 支持两种组合符号：`;` 分隔多个独立函数（各自绑定），`|` 组合为管道（组合函数共享一对绑定）。`filterEligibleFunctionDefinitions`（行 1048-1079）在此把 KStream/KTable 类型函数过滤掉——那是 kafka-streams binder 的领地（注释原文："This is to accommodate Kafka streams binder"）。

## 3.3 BindableFunctionProxyFactory：通道的创建与改名

**（白话）** `BindableFunctionProxyFactory` 本质是一个"按方法名分发到缓存"的 JDK 动态代理工厂，同时持有"绑定名 → 通道实例"的映射。它在初始化时按 2.3 节的命名规则创建输入/输出通道。

【源码证据】`function/BindableFunctionProxyFactory.java` `afterPropertiesSet` 行 104-120（创建通道）+ `createInput` 行 181-203（通道类型分叉）：

```java
private void createInput(String name) {
    if (this.functionProperties.getBindings().containsKey(name)) {
        name = this.functionProperties.getBindings().get(name);   // spring.cloud.stream.function.bindings.<name>=<别名>
    }
    updateChannelNameToFunctionName(name);                        // 通道名→函数名 映射表（供后续转换逻辑反查）
    if (this.supportedBindableFeatures.isPollable()) {
        PollableMessageSource pollableSource = (PollableMessageSource)
            getBindingTargetFactory(PollableMessageSource.class).createInput(name);
        ...
        this.inputHolders.put(name, new BoundTargetHolder(pollableSource, true));
    }
    else if (this.supportedBindableFeatures.isReactive()) {
        this.inputHolders.put(name, new BoundTargetHolder(
            getBindingTargetFactory(FluxMessageChannel.class).createInput(name), true));
    }
    else {
        this.inputHolders.put(name, new BoundTargetHolder(
            getBindingTargetFactory(SubscribableChannel.class).createInput(name), true));
    }
}
```

三种通道类型对应三种可绑定特征（`SupportedBindableFeatures`）：轮询、响应式、普通订阅式。普通订阅式的通道由 `SubscribableChannelBindingTargetFactory` 创建：

【源码证据】`binding/SubscribableChannelBindingTargetFactory.java` `createInput` 行 64-78：

```java
if (subscribableChannel == null) {
    DirectWithAttributesChannel channel = new DirectWithAttributesChannel();
    channel.setComponentName(name);
    if (context != null && !context.containsBean(name)) {
        context.registerBean(name, DirectWithAttributesChannel.class, () -> channel);  // 通道本身注册成 bean
    }
    subscribableChannel = channel;
}
if (subscribableChannel instanceof DirectWithAttributesChannel directWithAttributesChannel) {
    directWithAttributesChannel.setAttribute("type", "input");
    this.messageChannelConfigurer.configureInputChannel(directWithAttributesChannel, name); // 挂转换/分区拦截器（第六章）
}
```

`DirectWithAttributesChannel`（`messaging/DirectWithAttributesChannel.java` 行 30-65）是 SI `DirectChannel` 的薄封装：加了属性表（记录 `type=input|output`、companion 通道）、销毁时级联销毁 companion、并且**最多允许一个订阅者**（`subscribe` 行 62-64：已有 1 个 handler 就拒绝，保证"一条绑定一个消费者"的语义）。

`BindableProxyFactory` 的代理机制（`binding/BindableProxyFactory.java` 行 68-79 `invoke`、行 110-124 `getObject`）：所有方法调用都进 `targetCache` 按方法签名取缓存结果——这解决了"函数式模型下没有 `@Input` 注解帮你拿通道"的问题：用户 `@Autowired MessageChannel uppercaseIn0` 或框架内部按名取通道，实际都由代理统一供给。`replaceInputChannel`/`replaceOutputChannel`（行 81-97）支持运行期把通道整体替换——`spring.cloud.function.definition` 组合场景（composeFrom）会用它。

**独立绑定**（不挂函数的纯通道）：4.2 起支持 `spring.cloud.stream.input-bindings=a;b` 与 `output-bindings=c;d`，由 `FunctionBindingRegistrar#createStandAloneBindingsIfNecessary`（行 956-994）为每个名字注册一个 `functionExist=false` 的代理工厂——`buildInputNameForIndex` 里 `!isFunctionExist()` 分支就是为它准备的：绑定名即函数定义名，不追加 `-in-0` 后缀。

## 3.4 FunctionToDestinationBinder：把函数接上通道

**（白话）** 到现在为止只有通道没有逻辑。这一步把函数真正"订阅"到输入通道：普通函数包一个消息处理器；响应式函数则把输入通道转成 Flux 接给函数、把函数输出 Flux 接给输出通道。

【源码证据】`function/FunctionConfiguration.java` 内部类 `FunctionToDestinationBinder#afterPropertiesSet` 行 449-472：

```java
public void afterPropertiesSet() throws Exception {
    Map<String, BindableProxyFactory> beansOfType = applicationContext.getBeansOfType(BindableProxyFactory.class);
    this.bindableProxyFactories = beansOfType.values().toArray(new BindableProxyFactory[0]);
    for (BindableProxyFactory bindableProxyFactory : this.bindableProxyFactories) {
        String functionDefinition = bindableProxyFactory instanceof BindableFunctionProxyFactory functionFactory
            && functionFactory.isFunctionExist()
                ? functionFactory.getFunctionDefinition() : null;
        ...
        if (StringUtils.hasText(functionDefinition) && !shouldNotProcess) {
            FunctionInvocationWrapper function = functionCatalog.lookup(functionDefinition);
            if (function != null && !function.isSupplier() && functionDefinition.equals(function.getFunctionDefinition())) {
                this.bindFunctionToDestinations(bindableProxyFactory, functionDefinition, applicationContext.getEnvironment());
            }
        }
    }
}
```

（Supplier 被排除在外——它有自己的初始化器，见 3.5。）

`bindFunctionToDestinations`（行 475-642）第一分叉是 `isReactiveOrMultipleInputOutput`（行 754-758）：函数签名带 `Publisher` 或多输入多输出时走**响应式路线**（行 501-630）：输入通道经 `IntegrationReactiveUtils.messageChannelToFlux` 变成 `Flux<Message>`，包裹 `PartitionAwareFunctionWrapper` 后 `apply`，输出的每个 Publisher 逐个 `subscribe` 并把结果 `send` 到对应输出通道（行 579-629）；多输出场景按 `determinePartitionForOutputBinding`（行 644-655）逐输出算分区头。

普通（命令式）路线则创建一个匿名 `AbstractMessageHandler` 订阅到输入通道：

【源码证据】行 631-641 + `createFunctionHandler` 行 657-742 的核心：

```java
AbstractMessageHandler handler = createFunctionHandler(function, inputDestinationName, outputDestinationName);
((SubscribableChannel) inputDestination).subscribe(handler);
```

```java
// createFunctionHandler 内（行 666-694 摘要）
FunctionWrapper functionInvocationWrapper = (new FunctionWrapper(function, consumerProperties,
        producerProperties, applicationContext, this.determineTargetProtocol(outputChannelName)));
...
public void handleMessageInternal(Message<?> message) throws MessagingException {
    Object result = functionInvocationWrapper.apply((Message<byte[]>) message);   // ① 委托函数目录调用
    if (result == null) { return; }                                              // ② null 不发
    if (result instanceof Iterable<?> iterableResult) { ... 逐个发送 }            // ③ 集合/数组展开发送
    else { this.doSendMessage(result, message); }
}
```

`doSendMessage`（行 705-735）还有两个彩蛋分支：结果消息头带 `spring.cloud.stream.sendto.destination` 时动态解析新目的地发送（动态路由）；被包装的是 `RoutingFunction` 时结果经 `streamBridge.send(函数定义 + "-out-0", ...)` 发出（行 728-733）。

`FunctionWrapper`（行 810-871）值得单独看：它在调用前根据 `useNativeDecoding`/`useNativeEncoding` 设置 `setSkipInputConversion`/`setSkipOutputConversion`（行 832-838）——原生编解码时跳过函数目录层的负载转换，交由 binder 原生完成（第六章）；还反射改写 `MessageHeaders` 内部 map 以补 CloudEvent 标记（行 860-869）。

## 3.5 supplierInitializer：Supplier 的 IntegrationFlow 与轮询

**（白话）** Supplier 是"框架主动来取"的模型：Stream 用 SI 的 `IntegrationFlow.fromSupplier(...)` 把它包成 `SourcePollingChannelAdapter`（名字是 `<绑定名>_spca`——这正是第五章 `doBindProducer` 里 companion 生命周期查找的那个名字），默认按 `producer.poller` 属性轮询；返回 `Publisher` 的响应式 Supplier 则直接订阅到 `FluxMessageChannel`。

【源码证据】`function/FunctionConfiguration.java` `supplierInitializer` 行 191-296 关键段：

```java
return () -> {
    for (BindableFunctionProxyFactory proxyFactory : proxyFactories) {
        if (!proxyFactory.isFunctionExist()) { continue; }         // 独立绑定跳过（issue #3203）
        FunctionInvocationWrapper functionWrapper = functionCatalog.lookup(proxyFactory.getFunctionDefinition());
        if (functionWrapper != null && functionWrapper.isSupplier()) {
            ...
            IntegrationFlow integrationFlow = integrationFlowFromProvidedSupplier(
                    new PartitionAwareFunctionWrapper(functionWrapper, context, producerProperties),
                    pollable, context, taskScheduler, producerProperties, outputName)
                .intercept(new ChannelInterceptor() { ... postProcessor.postProcess(); })
                .route(Message.class, message -> {
                    if (message.getHeaders().get("spring.cloud.stream.sendto.destination") != null) {
                        String destinationName = (String) message.getHeaders().get("spring.cloud.stream.sendto.destination");
                        return streamBridge.resolveDestination(destinationName, producerProperties, null);
                    }
                    return outputName;
                })
                .get();
            ...
            context.registerBean(integrationFlowName, IntegrationFlow.class, () -> { return postProcessedFlow; });
        }
    }
};
```

`integrationFlowFromProvidedSupplier`（行 303-360）内部：响应式 Supplier（`FunctionTypeUtils.isPublisher(outputType)`）直接 `supplier.get()` 拿到 Publisher 并订阅，同时**把 `FluxMessageChannel` 以 COMPANION_ATTR 挂到绑定通道上**（行 325-329，随后 `DefaultBinding.stop` 销毁通道时会级联销毁它）；命令式 Supplier 走 `IntegrationFlow.fromSupplier(supplier, spca -> spca.id(bindingName + "_spca")...)`（行 347-351），有 `producer.poller` 属性时按 fixedDelay/cron 配 `PollerMetadata`（行 335-344 + `asTrigger` 行 362-367）。`@PollableBean` 注解的方法会被 `extractPollableAnnotation`（行 377-401）反射识别以支持 splittable 等属性。

`postProcess()` 拦截器值得一提（行 261-265）：每次发送后调用 `FunctionInvocationWrapper.postProcess()`——这是给有状态的 `@Bean` Supplier（如迭代器状态）做清理的口子。

## 3.6 InputBindingLifecycle / OutputBindingLifecycle：绑定何时真正发生

**（白话）** 通道有了、函数接上了，但到此刻还没有任何中间件连接。真正调用 `BindingService.bindConsumer/bindProducer` 的是两个 `SmartLifecycle` bean：**先绑输出（phase 最小，最先 start），后绑输入**；停止时顺序自然相反（先停输入再停输出），保证消费者先下线、生产者后关闭，链路上的消息不丢失。

【源码证据】`binding/OutputBindingLifecycle.java` 行 50-62：

```java
@Override
public int getPhase() {
    return Integer.MIN_VALUE + 1000;      // 尽可能早启动：输出绑定先就绪
}

@Override
void doStartWithBindable(Bindable bindable) {
    Collection<Binding<Object>> bindableBindings = bindable.createAndBindOutputs(this.bindingService);
    ...
}
```

【源码证据】`binding/InputBindingLifecycle.java` 行 51-54：

```java
@Override
public int getPhase() {
    return SmartLifecycle.DEFAULT_PHASE - 3000;   // 比输出晚：输入绑定最后启动
}
```

`AbstractBindingLifecycle.start()`（`binding/AbstractBindingLifecycle.java` 行 55-75）收集容器里所有 `Bindable` bean 逐个 `doStartWithBindable`；`createAndBindOutputs`/`createAndBindInputs`（`binding/AbstractBindableProxyFactory.java` 行 90-121）则把每个 `BoundTargetHolder` 交给 `BindingService.bindProducer/bindConsumer`——**这里就是 core 与 Binder SPI 的交割点**（第四章展开）。中途失败会 `stopStartedBindables` 回滚已启动的绑定。

## 3.7 本章小结

用一张装配链表总结"函数 → 绑定 → 中间件"的全过程：

| 步骤 | 执行者 | 产出 |
|---|---|---|
| ① 自动配置（imports 顺序） | FunctionConfiguration | StreamBridge、三个 InitializingBean |
| ② 定位函数定义 | FunctionBindingRegistrar | definition 字符串（显式/路由/自动发现） |
| ③ 注册代理工厂 | FunctionBindingRegistrar | `BindableFunctionProxyFactory`（含输入/输出计数） |
| ④ 创建通道 | BindableFunctionProxyFactory | DirectWithAttributesChannel / FluxMessageChannel / PollableMessageSource |
| ⑤ 函数接通道 | FunctionToDestinationBinder / supplierInitializer | 消息处理器订阅 / IntegrationFlow + `_spca` 端点 |
| ⑥ 触发绑定 | OutputBindingLifecycle（先）→ InputBindingLifecycle（后） | `BindingService.bindProducer/bindConsumer` 调用 |
| ⑦ SPI 交割 | BindingService → Binder | 物理目的地 + 监听器/发送器（第四、五章） |

第三章先补齐内核，下一章看交割的另一端：Binder 如何被发现、创建、缓存，以及它的 SPI 契约长什么样。

---

# 四、Binder SPI 与 Binder 工厂：中间件无关性的实现（重点 B）

## 4.1 SPI 三件套：Binder、ProvisioningProvider、属性体系

**（白话）** 写一个新 binder（比如接一个内部 MQ）只需要实现三类契约：`Binder` 回答"怎么绑"，`ProvisioningProvider` 回答"目的地实体怎么建"，属性类回答"怎么配"。三者都以泛型参数挂钩，core 在编译期就能校验类型。

【源码证据】`core/spring-cloud-stream/src/main/java/org/springframework/cloud/stream/binder/Binder.java` 行 36-71：

```java
public interface Binder<T, C extends ConsumerProperties, P extends ProducerProperties> {

    default String getBinderIdentity() {
        return String.valueOf(this.hashCode());
    }

    Binding<T> bindConsumer(String name, String group, T inboundBindTarget,
            C consumerProperties);                    // name=目的地，group=消费组

    Binding<T> bindProducer(String name, T outboundBindTarget, P producerProperties);
}
```

【源码证据】`provisioning/ProvisioningProvider.java` 行 39-62（javadoc 原文说明其意图："This SPI will allow the binders to be separated from any provisioning concerns and only focus on setting up endpoints for sending/receiving messages"）：

```java
ProducerDestination provisionProducerDestination(String name, P properties) throws ProvisioningException;
ConsumerDestination provisionConsumerDestination(String name, String group, C properties) throws ProvisioningException;
```

属性体系的两个根类按主题组织（字段实测摘要）：`ConsumerProperties`（`binder/ConsumerProperties.java` 行 44-180）——`concurrency`、`partitioned`/`instanceCount`/`instanceIndex(List)`、`maxAttempts`/`backOffInitialInterval`/`backOffMaxInterval`/`retryableExceptions`、`headerMode`、`useNativeDecoding`、`multiplex`（多目的地复用）、`batchMode`；`ProducerProperties`（`binder/ProducerProperties.java` 行 45-84）——`partitionKeyExtractorName`/`partitionSelectorName`/`partitionCount`、`requiredGroups`、`headerMode`、`useNativeEncoding`、`errorChannelEnabled`、`poller`。官方 binder 通过 `ExtendedConsumerProperties<T>`/`ExtendedProducerProperties<T>` 在其上叠加扩展（如 Kafka 的 `startOffset`、Rabbit 的 `republishToDlq`），判别口子是 `ExtendedPropertiesBinder` 接口（BindingService 在绑定时把通用属性拷进扩展属性，见 5.1 节）。

## 4.2 AbstractBinder 与 AbstractMessageChannelBinder：模板方法

**（白话）** core 用两个抽象类把 binder 实现者要操心的事压到最少：`AbstractBinder` 定骨架（final 的 bindConsumer/bindProducer 包住可覆盖的 doBindXxx），`AbstractMessageChannelBinder` 把"绑定 = 目的地供给 + 消息处理器/端点创建"的流程固化，并附赠整套错误基础设施。Kafka/Rabbit/Test 三个 binder 全部继承后者——这是 Stream 架构里复用度最高的一段代码。

【源码证据】`binder/AbstractBinder.java` 行 139-159：

```java
@Override
public final void afterPropertiesSet() throws Exception {   // 行 124-128
    Assert.notNull(this.applicationContext, ...);
    onInit();                                               // 子类初始化钩子
}

@Override
public final Binding<T> bindConsumer(String name, String group, T target, C properties) {
    if (!StringUtils.hasText(group)) {
        Assert.isTrue(!properties.isPartitioned(),
                "A consumer group is required for a partitioned subscription");  // 分区订阅必须显式 group
    }
    return doBindConsumer(name, group, target, properties);
}
```

`AbstractBinder` 还提供两个公共工具：`groupedName`（行 167-170，`name + "." + group`，Rabbit 队列命名的基础）与 `constructDLQName`（行 95-97，`name + ".dlq"`）。行 193-228 的 `buildRetryTemplate` 是第七章重试的工厂（5.0 起构造 Framework 7 的 `RetryPolicy.builder()`，含 retryableExceptions 映射）。

【源码证据】`binder/AbstractMessageChannelBinder.java` 行 105-107 类声明与其要求的实现方法（javadoc 行 85-90 明确列出）：

```java
public abstract class AbstractMessageChannelBinder<C extends ConsumerProperties, P extends ProducerProperties,
        PP extends ProvisioningProvider<C, P>>
        extends AbstractBinder<MessageChannel, C, P> implements PollableConsumerBinder<MessageHandler, C>,
        ApplicationEventPublisherAware {
```

binder 必须实现（抽象方法）：

| 方法 | 行号 | 职责 |
|---|---|---|
| `createProducerMessageHandler(destination, properties, channel, errorChannel)` | 510-512 | 返回把消息发往中间件的 `MessageHandler`（如包着 KafkaTemplate 的 handler） |
| `createConsumerEndpoint(destination, group, properties)` | 755-756 | 返回从中间件收消息的 `MessageProducer`（如包着监听容器的端点） |
| （可选）`createPolledConsumerResources(...)` | 735-739 | 轮询消费者支持，默认抛 UnsupportedOperationException |

可选覆盖：`useNativeEncoding`（447-449）、`getErrorMessageHandler`（1035-1038，第七章）、`getErrorMessageStrategy`（1072-1074）、`postProcessOutputChannel`（457-460）等。

## 4.3 Binder 从哪来：spring.binders → 类型注册表 → 配置 → 子上下文

**（白话）** 每个 binder jar 里都有一个 `META-INF/spring.binders` 文件，键是 binder 名、值是它的 Spring 配置类。core 启动时扫描classpath 汇总成注册表；用户可再声明具体的 binder 实例配置（多集群）；真正实例化 binder 时，core 会为每个 binder 实例**开一个独立的子应用上下文**——这就是"中间件依赖不污染主上下文"的实现。

【源码证据】`binders/kafka-binder/spring-cloud-stream-binder-kafka/src/main/resources/META-INF/spring.binders`（全文件）：

```
kafka:\
org.springframework.cloud.stream.binder.kafka.config.KafkaBinderConfiguration
```

【源码证据】`config/BinderFactoryAutoConfiguration.java` `binderTypeRegistry` 行 169-197：

```java
Enumeration<URL> resources = classLoader.getResources("META-INF/spring.binders");
...
while (resources.hasMoreElements()) {
    URL url = resources.nextElement();
    UrlResource resource = new UrlResource(url);
    for (BinderType binderType : parseBinderConfigurations(classLoader, resource)) {
        binderTypes.put(binderType.getDefaultName(), binderType);
    }
}
...
return new DefaultBinderTypeRegistry(binderTypes);
```

用户侧的 binder 实例配置（`spring.cloud.stream.binders.<name>.type/environment/defaultCandidate`）由 `BindingServiceConfiguration.getBinderConfigurations`（行 94-150）翻译成 `BinderConfiguration`：声明过的 binder 按声明来（type 属性指向注册表中的类型名）；**一个都没声明时，把注册表里所有类型都补成 defaultCandidate=true 的默认配置**（行 139-148）——这是"只加一个 binder 依赖就能跑"的约定来源。

实例化发生在第一次 `getBinder` 调用时（懒创建 + 缓存）：

【源码证据】`binder/DefaultBinderFactory.java` `getBinderInstance` 行 361-418 关键段：

```java
if (!this.binderInstanceCache.containsKey(configurationName)) {
    ...
    BinderType binderType = this.binderTypeRegistry.get(binderConfiguration.getBinderType());
    ...
    binderProducingContext = this.initializeBinderContextSimple(configurationName, binderProperties,
            binderType, binderConfiguration, true);          // ① 建子上下文
    ...
    Binder<T, ?, ?> binder = binderProducingContext.getBean(Binder.class);   // ② 从子上下文取 binder
    if (this.context != null && binder instanceof ApplicationContextAware applicationContextAwareBinder) {
        applicationContextAwareBinder.setApplicationContext(this.context);   // ③ 但让 binder 能看到主上下文
    }
    ...
    this.binderInstanceCache.put(configurationName, new SimpleImmutableEntry<>(binder, binderProducingContext));
}
```

`initializeBinderContextSimple`（行 467-544）的几个关键动作：把 binder 配置属性扁平化后以 `MapPropertySource` **addFirst** 进子环境（行 474-475 + `flatten` 行 655-663）；binder 无专属属性时直接把主上下文设为 parent（行 477-483），否则转发事件（行 485-497）并按 `inheritEnvironment` 合并环境（行 503-510）；先注册用户 `spring.main.sources` 指定的类再注册 binder 自己的配置类（行 513-534）；refresh 前 `propagateSharedBeans` 按 `META-INF/shared.beans` 清单把主上下文的指定类型 bean 复制进子上下文（行 546-578）。

另一个容易被忽略的接线：子上下文里的自定义 `MessageConverter` 会被收集进 `SimpleFunctionRegistry`（行 388-394）——这就是"用户在主上下文定义的转换器能参与函数参数转换"的通道（第六章）。

## 4.4 默认 binder 决策树与 AOT 路线

`getBinder`（行 174-207）的完整决策树：

1. **主上下文里已有 Binder bean？**（行 179-192）——新版 binder（kafka-streams、测试 binder 等以普通 bean 注册的）优先：按名取 / 唯一直接用 / 多个报错。
2. **否则走传统路线 `doGetBinder`**（行 209-215）：若有 AOT 预置的 child-context-initializers 走 `doGetBinderAOT`（行 217-246，KStream/KTable 类型按类型名小写直连 kafka-streams binder）；否则走 `doGetBinderConventional`（行 257-330）：
   - 未指定 binder 名且未设默认 → 在 defaultCandidate 中选：唯一候选直接用；多个候选时**按 Binder 泛型第一参数能否兼容绑定目标类型过滤**（行 290-315，`GenericsUtils.getParameterType(...Binder.class, 0)`），过滤后仍不唯一则报错；
   - reactor 前缀候选有特殊处理（行 332-340，仅 FluxMessageChannel 可配 reactor binder）；
   - 最后 `verifyBinderTypeMatchesTarget`（行 351-358）兜底校验类型兼容。

AOT 支持的第二条线：`createBinderContextForAOT`（行 433-442）与 `setBinderChildContextInitializers`（行 452-455）允许构建期把 binder 子上下文**预生成**为初始化器，运行期 `getBinderInstance` 走 `createUnitializedContextForAOT` + initializer + refresh（行 368-377）——GraalVM native 镜像下不再动态反射构建子上下文。

CRaC 支持：`DefaultBinderFactory` 实现 `SmartLifecycle`（行 83），start/stop（行 151-165）在检查点恢复时对全部 binder 子上下文整体启停，注释原文 "This is essentially used when CRaC checkpoint is restored"。

## 4.5 多 binder 与 per-binding binder

绑定与 binder 的对应关系由 `BindingService.getBinder`（`binding/BindingService.java` 行 433-437）决定：`spring.cloud.stream.bindings.<bindingName>.binder=<binderName>` 逐绑定指定；`spring.cloud.stream.default-binder` 全局兜底。多 binder 场景下两个经典约束都源自 `getBinderInstance` 的子上下文模型：不同 binder 实例的同名扩展属性互不影响（各自环境隔离），而 `spring.cloud.stream.kafka.binder.*` 这类全局属性会被放进每个 kafka binder 子上下文。

## 4.6 自定义 binder 最小实现

**（白话）** 有了 `AbstractMessageChannelBinder`，一个最小自定义 binder 只需要：继承它、实现两个工厂方法、提供一个装配类并在 `META-INF/spring.binders` 登记。仓库自带的 TestChannelBinder 就是最好的"最小范本"：

【源码证据】`core/spring-cloud-stream-test-binder/src/main/java/org/springframework/cloud/stream/binder/test/TestChannelBinder.java` 行 109-110、145-154：

```java
public class TestChannelBinder extends
    AbstractMessageChannelBinder<ConsumerProperties, ProducerProperties, TestChannelBinderProvisioner> {
    ...
    @Override
    protected MessageHandler createProducerMessageHandler(ProducerDestination destination,
                                                        ProducerProperties producerProperties, MessageChannel errorChannel)
        throws Exception {
        BridgeHandler handler = new BridgeHandler();     // "发送" = 桥接到内存通道
        handler.setBeanFactory(this.beanFactory);
        handler.setOutputChannel(((SpringIntegrationProducerDestination) destination).getChannel());
        return handler;
    }
```

`TestChannelBinderProvisioner` 把每个目的地建成一对内存通道（`SpringIntegrationProducerDestination`/`SpringIntegrationConsumerDestination`），消费端 endpoint 订阅同一通道——整个"消息系统"退化成两次内存转发，这正是它能在单元测试里替代 Kafka 的原因（8.7 节）。

## 4.7 本章小结

| 问题 | 答案 | 证据 |
|---|---|---|
| binder 如何被发现 | jar 内 `META-INF/spring.binders` → `BinderTypeRegistry` | BinderFactoryAutoConfiguration:175 |
| binder 如何被实例化 | 每个实例一个子应用上下文，懒创建 + 缓存 | DefaultBinderFactory:361-418 |
| 中间件依赖为何不污染应用 | 子上下文隔离 + parent/事件转发/shared.beans 白名单 | DefaultBinderFactory:467-578 |
| 用哪个 binder | 主上下文 bean 优先 → per-binding → default-binder → 泛型兼容过滤 | DefaultBinderFactory:174-358 |
| 写一个 binder 要多少代码 | 两个工厂方法 + 一个配置类 + spring.binders 登记 | TestChannelBinder |

---

# 五、消费与生产全链路（重点 C）

## 5.1 生产端：doBindProducer 的五步模板

**（白话）** 第三章的 `OutputBindingLifecycle` 最终调到 binder 的 `doBindProducer`。模板类把它固化成五步：建目的地 → （可选）建错误通道 → 创建中间件发送器 → 把发送器订阅到应用通道 → 打包成 `DefaultBinding`。

【源码证据】`binder/AbstractMessageChannelBinder.java` `doBindProducer` 行 300-433 摘要（保留行号）：

```java
public final Binding<MessageChannel> doBindProducer(final String destination,
        MessageChannel outputChannel, final P producerProperties) throws BinderException {
    final MessageHandler producerMessageHandler;
    final ProducerDestination producerDestination;
    try {
        producerDestination = this.provisioningProvider                          // ① 目的地供给
                .provisionProducerDestination(destination, producerProperties);
        ...
        SubscribableChannel errorChannel = errorHandlerDefined || producerProperties.isErrorChannelEnabled()
                ? registerErrorInfrastructure(producerDestination, producerProperties.getBindingName(), errorHandlerDefined)
                        : null;                                                  // ② 错误基础设施（异步发送失败时才有意义）
        ...
        producerMessageHandler = createProducerMessageHandler(producerDestination,
                producerProperties, outputChannel, errorChannel);                // ③ binder 工厂方法
        customizeProducerMessageHandler(producerMessageHandler, producerDestination.getName());
        if (producerMessageHandler instanceof InitializingBean initializingHandler) {
            initializingHandler.afterPropertiesSet();
        }
    }
    catch (Exception e) { ... throw new BinderException("Exception thrown while building outbound endpoint", e); }

    if (producerProperties.isAutoStartup()
        && producerMessageHandler instanceof Lifecycle ProducerMessageHandlerWithLifeCycle) {
        ProducerMessageHandlerWithLifeCycle.start();                             // ④ 立即启动
    }
    this.postProcessOutputChannel(outputChannel, producerProperties);
    ...
    if (outputChannel instanceof SubscribableChannel subscribableOutputChannel) {
        subscribableOutputChannel
            .subscribe(new SendingHandler(producerMessageHandler,                // ⑤ 订阅到应用通道
                HeaderMode.embeddedHeaders.equals(producerProperties.getHeaderMode()),
                this.headersToEmbed, useNativeEncoding(producerProperties)));
    }
    else if (outputChannel instanceof FluxMessageChannel) {
        final ReactiveStreamsConsumer reactiveStreamsConsumer = new ReactiveStreamsConsumer(outputChannel, producerMessageHandler);
        ...
    }
    ...
    doPublishEvent(new BindingCreatedEvent(binding));                            // ⑥ 发布 BindingCreatedEvent
    return binding;
}
```

两点展开：

- **`SendingHandler`（行 1223-1290）是"消息出应用的最后一站"**：`handleMessageInternal`（行 1244-1248）里 `useNativeEncoding` 为真时原样透传（序列化已由函数目录跳过/中间件完成），否则 `serializeAndEmbedHeadersIfApplicable`（行 1250-1268）在 `HeaderMode.embeddedHeaders` 时把指定头以 JSON 形式嵌入 payload 前缀（配合消费端 `EmbeddedHeadersChannelInterceptor` 行 1159-1199 解出，用于不支持原生头的中间件）。
- **companion 生命周期**（行 423-429）：Supplier 场景 3.5 节注册的 `<绑定名>_spca` 端点在这里被查出来挂为 binding 的 companion——对 binding 调 start/stop 时连带启停轮询适配器。

## 5.2 消费端：doBindConsumer 与端点接线

【源码证据】`binder/AbstractMessageChannelBinder.java` `doBindConsumer` 行 541-632 摘要：

```java
ConsumerDestination destination = this.provisioningProvider
        .provisionConsumerDestination(name, group, properties);           // ① 目的地供给（可能自动建 topic/queue）

if (HeaderMode.embeddedHeaders.equals(properties.getHeaderMode())) {
    enhanceMessageChannel(inputChannel);                                  // ② 内嵌头模式挂解包拦截器（行 741-744）
}
consumerEndpoint = createConsumerEndpoint(destination, group, properties); // ③ binder 工厂方法（如监听容器端点）
consumerEndpoint.setOutputChannel(inputChannel);                          // ④ 端点输出接到应用输入通道
this.consumerCustomizer.configure(consumerEndpoint, name, group);          // ⑤ ConsumerEndpointCustomizer
if (consumerEndpoint instanceof InitializingBean initializingConsumerEndpoint) {
    initializingConsumerEndpoint.afterPropertiesSet();
}
if (properties.isAutoStartup() && consumerEndpoint instanceof Lifecycle consumerEndpointWithLifecycle) {
    consumerEndpointWithLifecycle.start();                                // ⑥ 启动
}
...                                                                       // ⑦ DefaultBinding（含 afterUnbind 清理）
```

`doBindConsumer` 内还匿名实现了 `DefaultBinding`（行 562-614），其中 `afterUnbind`（行 599-612）负责销毁端点、通知 binder（`afterUnbindConsumer`）、拆除错误基础设施——解绑的完整清理链。

## 5.3 一条消息的消费之旅（以 Kafka 为例）

把前三章的零件串起来，一条 Kafka 消息的完整旅程：

1. **spring-kafka `ConcurrentMessageListenerContainer`** 从 broker 拉到 `ConsumerRecord`，经 binder 创建的端点转成 Spring `Message<byte[]>`（Kafka binder 在 `createConsumerEndpoint` 里组装容器，见 9.3 节）；
2. 端点 `setOutputChannel(inputChannel)`（5.2 节第④步）把消息发进 `uppercase-in-0` 通道，途中经过通道拦截器：`InboundContentTypeEnhancingInterceptor` 补 contentType 头（6.1 节）、嵌入头模式先解头；
3. 通道的唯一订阅者——3.4 节的匿名消息处理器——被调起，`FunctionWrapper.apply` → `FunctionInvocationWrapper.doApply`：**convertInput**（`byte[]`+contentType → 函数入参类型，`CompositeMessageConverter` 分派，第六章）→ **invokeFunction**（反射调用你的 `uppercase`）→ **convertOutput**（结果 → 按输出 contentType 转换，默认转 `byte[]`）；
4. 处理器把结果经 `MessagingTemplate.send(outputChannelName, ...)` 发往 `uppercase-out-0` 通道（`FunctionConfiguration.java` 行 726），出站拦截器 `OutboundContentTypeConvertingInterceptor` 在此完成出站转换（6.1 节）；
5. 通道订阅者 `SendingHandler.handleMessage` → Kafka binder 的 `ProducerConfigurationMessageHandler` → `KafkaTemplate.send`，落盘成功后整条链路返回，监听容器按 AckMode 提交位移；
6. 任何一步抛异常则进入第七章的错误链：重试（RetryTemplate）→ 错误通道（binder 错误处理器/用户错误函数/DLQ）。

## 5.4 StreamBridge：命令式发送与动态目的地

**（白话）** 并非所有数据都源自函数：Web 请求、定时任务、第三方回调……StreamBridge 是"把外部数据塞进 Stream 语义"的桥——`send(bindingName, data)` 会像函数绑定一样做转换、分区，目的地不存在时**现场创建通道 + 现场绑定**（动态目的地），并缓存复用。

【源码证据】`function/StreamBridge.java` `send` 行 191-234 摘要：

```java
public boolean send(String bindingName, @Nullable String binderName, Object data, MimeType outputContentType) {
    if (!this.initialized) { this.afterSingletonsInstantiated(); }
    ProducerProperties producerProperties = this.bindingServiceProperties.getProducerProperties(bindingName);
    MessageChannel messageChannel = this.resolveDestination(bindingName, producerProperties, binderName);  // ① 通道
    Function functionToInvoke = this.getStreamBridgeFunction(outputContentType.toString(), producerProperties); // ② 内置 streamBridge 函数
    if (producerProperties != null && producerProperties.isPartitioned()) {
        functionToInvoke = new PartitionAwareFunctionWrapper(functionToInvoke, this.applicationContext, producerProperties);
    }
    ...
    resultMessage = (Message<byte[]>) functionToInvoke.apply(messageToSend);   // ③ 复用函数目录做输出转换
    ...
    return messageChannel.send(resultMessage);                                  // ④ 发送（随后 SendingHandler → 中间件）
}
```

三个精巧点：

- **内置恒等函数**：`afterSingletonsInstantiated`（行 259-271）向函数目录注册一个名为 `streamBridge` 的 `PassThruFunction`——转换不是 StreamBridge 自己写的，而是借道函数目录（`getStreamBridgeFunction` 行 249-257 按属性 hash 缓存 wrapper，并按 `useNativeEncoding` 设 `setSkipOutputConversion`）。
- **动态目的地与缓存**：`resolveDestination`（行 274-317）先查 LRU 缓存（行 150-163，容量 `dynamicDestinationCacheSize` 默认 10，**淘汰时真正 unbind 生产者**）；没有则新建 `DirectWithAttributesChannel`（异步模式为 `ExecutorChannel`，行 291）、挂分区拦截器（仅 `useNativeEncoding` 时，行 319-327——分区已在 `send` 里算过，见 `PartitionAwareFunctionWrapper`）、执行 `bindingService.bindProducer` 并入缓存（行 307-308）。向 Spring Cloud Bus 的 `RefreshRemoteApplicationEvent` 监听会整体清缓存解绑（行 368-376）。
- **动态发送的目标**：`resolveDestination` 若发现上下文里已有同名通道 bean（如某个输入绑定），直接复用并发警告（行 285-288，GH-2563：往输入绑定发数据会绕过 binder）。

## 5.5 本章小结

| 环节 | 生产端 | 消费端 |
|---|---|---|
| 供给 | `provisionProducerDestination`（建 topic/exchange） | `provisionConsumerDestination`（建 topic/queue，group 参与命名） |
| 中间件设施 | `createProducerMessageHandler`（KafkaTemplate/AmqpTemplate 包装） | `createConsumerEndpoint`（监听容器包装） |
| 接线 | SendingHandler 订阅到应用输出通道 | 端点 outputChannel 指向应用输入通道 |
| 消息出口 | SendingHandler → 中间件发送器 | 通道 → 函数处理器 →（Function 目录转换）→ 输出通道 |
| 生命周期 | Binding.start/stop、companion（`_spca`） | Binding.start/stop、unbind 全清理 |

---

# 六、消息转换与内容类型（重点 D）

## 6.1 contentType 的旅程：从属性到头

**（白话）** Stream 的转换模型是"声明式"的：你只需要为每条绑定声明 `contentType`（默认 `application/json`），框架用**通道拦截器**保证消息头与它一致，真正的转换则委托给 `CompositeMessageConverter` 按头分派。这个设计的好处是转换点前移到通道上——函数目录、binder、StreamBridge 全都无需重复实现转换。

【源码证据】`binding/MessageConverterConfigurer.java` `configureMessageChannel` 行 140-195 摘要：

```java
private void configureMessageChannel(MessageChannel channel, String channelName, boolean inbound) {
    ...
    String contentType = bindingProperties.getContentType();
    boolean partitioned = !inbound && producerProperties != null && producerProperties.isPartitioned();
    boolean functional = streamFunctionProperties != null && (...);
    if (partitioned) {
        if (inbound || !functional) {
            messageChannel.addInterceptor(new PartitioningInterceptor(bindingProperties));   // 分区拦截器（8.1）
        }
    }
    ...
    if (this.isNativeEncodingNotSet(producerProperties, consumerProperties, inbound)) {     // 未开原生编解码才挂
        if (inbound) {
            messageChannel.addInterceptor(new InboundContentTypeEnhancingInterceptor(contentType));
        }
        else {
            ... // rabbit 特殊场景处理
            if (!functional) {
                messageChannel.addInterceptor(new OutboundContentTypeConvertingInterceptor(contentType, this.compositeMessageConverter));
            }
        }
    }
}
```

两个拦截器的分工（行 233-323）：

- **InboundContentTypeEnhancingInterceptor**（行 233-261）只补头不转体：头里没有 `contentType` 就写入绑定声明的值（反射改 `MessageHeaders` 内部 map，因为 MessageHeaders 不可变）。
- **OutboundContentTypeConvertingInterceptor**（行 270-323）在**出站通道**上做真正的转换：payload 已是 `byte[]` 且带 contentType 头则放行（行 283-286）；否则调 `messageConverter.toMessage(payload, headers)` 转成字节数组（行 305-307），转换失败抛 `IllegalStateException`。

`contentType` 属性有两种写法（`converter/MessageConverterUtils.java` 行 69-100）：标准 MIME（`application/json`）；Java 类型语法（`com.example.Foo` 或 `application/x-java-object;type=com.example.Foo`）——后者常用于消费端把字节流转回具体 POJO。

## 6.2 转换器目录与 Jackson 3

【源码证据】`converter/CompositeMessageConverterFactory.java` `initDefaultConverters` 行 100-127：

```java
this.converters.add(new JsonMessageConverter(this.jsonMapper) { ... });   // application/json（含 String 直转 UTF-8 的兼容行为）
this.converters.add(new ByteArrayMessageConverter() { ... });             // application/octet-stream，supports 放宽到 Object
this.converters.add(new ObjectStringMessageConverter());                  // text/plain
```

- 用户自定义转换器 bean 会追加在默认转换器之后，且 binder 子上下文里的转换器也被汇入 `SimpleFunctionRegistry`（4.3 节末）——`CompositeMessageConverterFactory` 行 70-78 构造时接受 `customConverters`。
- contentType 解析器（行 81-93）处理一个历史坑：`contentType` 头被某些中间件序列化成 `byte[]` 时先还原为字符串再解析。
- **Jackson 3 迁移**：`jsonMapper` 的类型是 `org.springframework.cloud.function.json.JacksonMapper`，内部持 `tools.jackson.databind.ObjectMapper`（Stream 5.0.x 全线 `import tools.jackson.*`，如 `AbstractMessageChannelBinder` 行 27-32、`CompositeMessageConverterFactory` 行 61-72）；4.3.3 同位置还是 `com.fasterxml.jackson.*`（git 标签实证，1.6.2 表）。Boot 4 应用升级时自定义 `ObjectMapper` bean 的包名要跟着换。

## 6.3 原生编解码（useNativeEncoding / useNativeDecoding）

**（白话）** 默认路径是"框架转成 byte[]、中间件只存字节"。开启原生编解码后，转换让位给中间件自己的序列化器（如 Kafka 的 Avro serializer）：出站跳过函数目录与出站拦截器的转换，直接把 POJO 交给 `KafkaTemplate`。开关的落点分散在三处，均已在源码中确认：

| 落点 | 源码证据 |
|---|---|
| 函数目录层跳过输出转换 | FunctionConfiguration `FunctionWrapper` 行 836-838：`setSkipOutputConversion(producerProperties.isUseNativeEncoding())` |
| 出站拦截器不挂 | MessageConverterConfigurer `isNativeEncodingNotSet` 行 213-223 |
| StreamBridge 的内置函数跳过 | StreamBridge 行 254：`functionToInvoke.setSkipOutputConversion(...)` |
| 消费端对称：`useNativeDecoding` | FunctionConfiguration 行 532-533（响应式）/ 832-834（命令式）+ `configurePolledMessageSource` 行 127-131 |

代价与收益：原生编解码省一次"对象→byte[]→对象"，但消息头里携带的元数据（contentType 等）依赖中间件原生头机制，跨中间件互转能力下降；且分区 key 计算会前移（StreamBridge 行 319-327 的注释解释了为何此时才挂 `DefaultPartitioningInterceptor`）。

## 6.4 本章小结

| 问题 | 答案 |
|---|---|
| contentType 存在哪 | 绑定属性 `spring.cloud.stream.bindings.<b>.contentType`（默认 application/json）→ 消息头 |
| 入站谁转换 | 函数目录 `convertInputIfNecessary`（参数解析器），头由 Inbound 拦截器保证 |
| 出站谁转换 | 函数目录 `convertOutputIfNecessary` + Outbound 拦截器兜底（非函数场景） |
| 自定义转换器 | 声明 `MessageConverter` bean 即自动汇入（主上下文 + binder 子上下文） |
| 原生编解码 | 跳过框架转换，交给中间件序列化器；三处开关联动 |

---

# 七、错误处理、重试与 DLQ（重点 E）

## 7.1 错误通道的命名与四层订阅结构

**（白话）** 每条绑定都有自己专属的错误通道，命名规则是 `<binderIdentity>.<bindingName>.errors`（实际感知为 `<bindingName>.errors`）。错误通道是 SI `PublishSubscribeChannel`，上面按"先用户后框架"的顺序挂最多四层订阅者：用户错误处理函数、binder 提供的错误处理器（如 DLQ 发布者）、全局 errorChannel 的桥、兜底的"最终重抛"处理器。这是理解 Stream 错误语义的核心图景。

【源码证据】`binder/AbstractMessageChannelBinder.java` 行 1104-1106 + `registerErrorInfrastructure` 行 866-945 摘要：

```java
private String doErrorBaseName(String bindingName) {
    return this.getBinderIdentity() + "." + bindingName + ".errors";
}
```

```java
binderErrorChannel = new BinderErrorChannel();                                   // ① 错误通道（容器里可被同名 bean 覆盖，行 888-896）
...
boolean userHandlerSubscribed = this.subscribeFunctionErrorHandler(errorChannelName, ...);  // ② 用户错误函数（errorHandlerDefinition）

ErrorMessageSendingRecoverer recoverer = new ErrorMessageSendingRecoverer(binderErrorChannel, errorMessageStrategy);  // ③ 重试耗尽后的"恢复器"

MessageHandler binderProvidedErrorHandler = polled
        ? getPolledConsumerErrorMessageHandler(destination, group, consumerProperties)
                : getErrorMessageHandler(destination, group, consumerProperties);   // ④ binder 提供的处理器（如 DLQ）
if (binderProvidedErrorHandler == null) {
    binderProvidedErrorHandler = this.getDefaultErrorMessageHandler(binderErrorChannel, polled);  // ⑤ 兜底：FinalRethrowingErrorMessageHandler
}
if (binderProvidedErrorHandler != null && !userHandlerSubscribed) { ... binderErrorChannel.subscribe(binderProvidedErrorHandler); }

if (this.getApplicationContext().containsBean(IntegrationContextUtils.ERROR_CHANNEL_BEAN_NAME) && ...) {
    ... BridgeHandler bridge = new BridgeHandler();
    bridge.setOutputChannel(globalErrorChannel);                                   // ⑥ 桥接全局 errorChannel（日志统一观测点）
    binderErrorChannel.subscribe(bridge);
    ...
}
```

各层语义：

- **用户错误处理函数**（行 832-854 + 875-885）：`spring.cloud.stream.bindings.<b>.error-handler-definition=<函数名>`，从函数目录 lookup 一个 `Consumer<ErrorMessage>` 直接订阅——错误处理也函数化。
- **binder 处理器**：Kafka 的 DLQ 发布者（9.4 节）、Rabbit 的 republish（9.5 节）都从这里接入。
- **兜底处理器**：`FinalRethrowingErrorMessageHandler`（`binder/FinalRethrowingErrorMessageHandler.java` 行 34-47）把异常重新抛给消息流的源头——效果是**重试耗尽且无 DLQ 时，异常向上冒泡**（对 Kafka 即监听容器按 `errorHandler` 记录并继续，位移不提交）。
- **生产端也有错误通道**（行 775-815）：仅当 `errorChannelEnabled=true` 或配置了 error-handler-definition 时创建，接收**异步发送失败**（如 Kafka future 异常）。
- 全局 `errorChannel` 的 `ignoreFailures`（`BindingServiceConfiguration.globalErrorChannelCustomizer` 行 152-163）防止下游订阅者故障阻断错误传播。

消息异常本身如何进入错误通道？两条路：重试框架的恢复器 `ErrorMessageSendingRecoverer`（上③，重试耗尽时发送）；无重试时由监听容器/端点的错误处理直接发布。

## 7.2 重试：属性 → Framework 7 RetryTemplate

**（白话）** 重试发生在**消息处理器调用你的函数这一层**（不是中间件重投递），默认开 3 次。5.0 起实现从 spring-retry 换成 Framework 7 内建重试。

【源码证据】`binder/AbstractBinder.java` `buildRetryTemplate` 行 193-228 摘要：

```java
protected RetryTemplate buildRetryTemplate(ConsumerProperties properties) {
    RetryTemplate rt;
    if (CollectionUtils.isEmpty(this.consumerBindingRetryTemplates)) {
        rt = new RetryTemplate();                                  // org.springframework.core.retry.RetryTemplate
        Map<Class<? extends Throwable>, Boolean> retryableExceptionMapping = properties.getRetryableExceptions();
        ...
        RetryPolicy retryPolicy =
            RetryPolicy.builder()
                .maxRetries(Math.max(0, properties.getMaxAttempts() - 1))          // maxAttempts=3 → 重试 2 次
                .delay(Duration.ofMillis(properties.getBackOffInitialInterval()))  // 初始退避 1000ms
                .multiplier(properties.getBackOffMultiplier())                     // 退避倍数 2.0
                .maxDelay(Duration.ofMillis(properties.getBackOffMaxInterval()))   // 退避上限 10000ms
                .includes(retryableExceptions)
                .excludes(nonRetryableExceptions)                                  // retryableExceptions 映射
                .build();
        rt.setRetryPolicy(retryPolicy);
    }
    else {
        rt = ... this.consumerBindingRetryTemplates.get(properties.getRetryTemplateName()) ...;  // 用户 @StreamRetryTemplate bean
    }
    return rt;
}
```

属性对应（`binder/ConsumerProperties.java` 行 95-137）：`maxAttempts`（默认 3）、`backOffInitialInterval`（1000）、`backOffMaxInterval`（10000）、`backOffMultiplier`、`defaultRetryable`、`retryableExceptions`（`Map<异常类, Boolean>`）、`retryTemplateName`。想全局自定义模板就定义一个 `@StreamRetryTemplate` 限定的 RetryTemplate bean（`annotation/StreamRetryTemplate.java`）。

**注意重试与 DLQ 的先后**：重试在先、错误通道在后——重试耗尽后由 `ErrorMessageSendingRecoverer` 发错误消息。这解释了一个常见误配："为什么设了 maxAttempts 还进 DLQ 丢了重试？"——DLQ 消息里的异常是最后一次重试失败的异常。

## 7.3 Kafka DLQ 与 Rabbit republish：两种死信风格

- **Kafka**：DLQ 就是另一个 topic。binder 在 `getErrorMessageHandler`（`KafkaMessageChannelBinder.java` 行 1206-1260）里检查扩展属性 `enableDlq`，为 true 时构造一个专用 `KafkaTemplate`（`DlqSender`）作为错误处理器，把原始 `ConsumerRecord` 转投死信 topic（命名 `error.<destination>.<group>` 或 `destination.dlq`，可由 `DlqDestinationResolver`/`DlqPartitionFunction` 自定义，见 `binders/kafka-binder/spring-cloud-stream-binder-kafka-core/.../utils/`）。匿名订阅（无 group）不允许 DLQ（行 600-602 断言）。
- **Rabbit**：两种模式。`republishToDlq=true`（默认）时 binder 自己把失败消息重新发布到 DLQ 队列（`RabbitMessageChannelBinder.java` 行 692-696，含异常头）；false 时交给 Rabbit 原生死信机制（队列声明 `x-dead-letter-exchange`，由 `autoBindDlq` 自动建拓扑，行 867-881）。原生死信保留原始消息体但异常信息有限——这就是两个模式的取舍。

## 7.4 轮询消费者的 requeue/nack

轮询消费（8.2 节）的错误语义更细：`DefaultPollableMessageSource.poll`（`binder/DefaultPollableMessageSource.java` 行 196-269）失败时按 `shouldRequeue`（行 284-292，异常链上有 `RequeueCurrentMessageException` 则 requeue）决定**重新入队**还是 `AckUtils.autoNack`（消极确认）再走错误通道；正常路径 finally 里 `AckUtils.autoAck`（行 265-268）。用户可主动抛 `RequeueCurrentMessageException` 实现"这条消息下轮再拉"。

## 7.5 本章小结

| 失败场景 | 谁接手 | 证据 |
|---|---|---|
| 函数抛异常（首次） | RetryTemplate 重试 maxAttempts-1 次（带退避） | AbstractBinder:193-228 |
| 重试耗尽 | ErrorMessageSendingRecoverer → `<binder>.<binding>.errors` 通道 | AbstractMessageChannelBinder:900-905 |
| 通道上的订阅者 | 用户错误函数 → binder DLQ 处理器 → 全局 errorChannel 桥 → FinalRethrowing（重抛） | AbstractMessageChannelBinder:866-945 |
| 异步发送失败 | 生产端错误通道（errorChannelEnabled） | AbstractMessageChannelBinder:775-815 |
| 轮询失败 | requeue（RequeueCurrentMessageException）或 nack | DefaultPollableMessageSource:196-292 |

---

# 八、高级特性

## 8.1 分区：PartitionHandler 与两个策略接口

**（白话）** 分区解决"同一 key 的消息必须顺序落到同一分区"。生产端三要素：`partitionKeyExtractorName`（从消息提取 key 的策略 bean）、`partitionSelectorName`（key → 分区下标，默认 `key.hashCode()` 取模）、`partitionCount`。计算结果写入 `scst_partition` 头，由各 binder 的发送器落到中间件分区。

【源码证据】`binder/PartitionHandler.java` `determinePartition` 行 102-116：

```java
public int determinePartition(Message<?> message) {
    Object key = extractKey(message);                                            // 先 KeyExtractor 后 keyExpression
    int partition;
    if (this.producerProperties.getPartitionSelectorExpression() != null) {
        partition = this.producerProperties.getPartitionSelectorExpression()
                .getValue(this.evaluationContext, key, Integer.class);           // SpEL 选择器
    }
    else {
        partition = this.partitionSelectorStrategy.selectPartition(key, this.partitionCount);
    }
    // protection in case a user selector returns a negative.
    return Math.abs(partition % this.partitionCount);                            // 负数保护 + 取模
}
```

策略 bean 的获取（行 136-187）：优先按属性名取 bean，否则要求容器中该类型 bean 唯一，多个则报错并提示用 `partitionKeyExtractorName` 指定。计算在哪发生取决于路径：函数式默认在函数输出处（`PartitionAwareFunctionWrapper`，`function/PartitionAwareFunctionWrapper.java` 行 80-83 写 `PARTITION_HEADER`）；命令式/独立绑定在出站通道的 `PartitioningInterceptor`（`MessageConverterConfigurer.java` 行 350-386，支持 `PARTITION_OVERRIDE` 头跳过计算）；`useNativeEncoding` 的 StreamBridge 路径则靠 `DefaultPartitioningInterceptor`（`binding/DefaultPartitioningInterceptor.java`）。消费端对应 `partitioned=true` + `instanceCount`/`instanceIndex`（或 `instanceIndexList`，多实例索引），Kafka binder 据此手工分配分区（9.3 节）；注意 `AbstractBinder.bindConsumer` 行 142-145 的断言：分区订阅必须显式 group。

## 8.2 轮询消费者 PollableMessageSource

**（白话）** 不是所有场景都适合"推"：批处理、限速消费、按需拉取。`@PollableSource` 声明的函数会被 `PollableSourceInitializer`（spring.factories 里的 ApplicationContextInitializer）注册成独立绑定（`function/PollableSourceInitializer.java` 行 31-46），绑定的"通道"是一个 `DefaultPollableMessageSource`，应用代码主动调 `poll(handler)`。

【源码证据】`binder/DefaultPollableMessageSource.java` `poll` 行 196-269 摘要：

```java
public boolean poll(MessageHandler handler, ParameterizedTypeReference<?> type) {
    Message<?> message = this.receive(type);
    if (message == null) { return false; }
    AcknowledgmentCallback ackCallback = StaticMessageHeaderAccessor.getAcknowledgmentCallback(message);
    if (ackCallback == null) { ackCallback = status -> log.warn("No AcknowledgementCallback defined. ..."); }
    try {
        setAttributesIfNecessary(message);
        if (this.retryTemplate == null) {
            handle(message, handler);
        }
        else {
            try { this.retryTemplate.execute(() -> { handle(message, handler); return null; }); }
            catch (RetryException ex) {
                if (this.recoveryCallback != null) { ... this.recoveryCallback.recover(attributeAccessor, ex.getCause()); }
                else { ReflectionUtils.rethrowRuntimeException(ex.getCause()); }
            }
        }
        return true;
    }
    catch (MessagingException e) {
        if (this.retryTemplate == null && !shouldRequeue(e)) { ... 发错误通道 ... return true; }
        else { requeueOrNack(message, ackCallback, e); return true; }
    }
    catch (Exception e) { AckUtils.autoNack(ackCallback); ... }
    finally {
        ATTRIBUTES_HOLDER.remove();
        AckUtils.autoAck(ackCallback);                    // 默认自动确认（可手动控制）
    }
}
```

要点：消息源经 AOP 代理插入拦截器链（`setSource` 行 109-138）；`poll(handler, ParameterizedTypeReference)` 支持泛型负载转换（行 300-316）；手动确认只需在 handler 里持住 `AcknowledgmentCallback` 并在 finally 自动 ack 前调用 `ackCallback.acknowledge(...)`（header 已随消息携带）。`useNativeDecoding=false` 时转换拦截器在 `configurePolledMessageSource` 挂上（`MessageConverterConfigurer.java` 行 122-132）。

## 8.3 响应式与多输入输出

函数返回 `Mono`/`Flux`（或参数是 Publisher）时走响应式装配（3.4 节）。多输入输出场景（`BiFunction`、返回 `Tuple2`）在 `bindFunctionToDestinations` 的响应式分支统一处理：N 个输入通道各转一个 Flux，`Tuples.fromArray` 合并后喂给函数（`FunctionConfiguration.java` 行 528-575），输出的每个 Publisher 顺序对应 `fn-out-0..n`（行 576-579），多输出时逐输出计算分区头（行 584-592）。响应式路径直接 `outputChannel.send`，并把 Reactor 上下文通过 micrometer `ContextSnapshot` 传播到线程本地（行 606-614 + `ContextSnapshotHelper` 行 1083-1091）——可观测性与 MDC 在响应式链路上不丢的关键。

## 8.4 RoutingFunction：一个入口路由到多个函数

开启 `spring.cloud.stream.function.routing.enabled=true`（或配置 `spring.cloud.function.routing-expression`）后，3.2 节的 `determineFunctionName` 会把"函数定义"定为 `RoutingFunction.FUNCTION_NAME = "functionRouter"`（spring-cloud-function `config/RoutingFunction.java` 行 57-62）。此后所有消息先进入路由函数，按 routing-expression（SpEL，可访问消息头）决定真正调用的目标函数；结果由 3.4 节 `doSendMessage` 的路由分支（`FunctionConfiguration.java` 行 728-733）经 StreamBridge 发往 `<路由函数>-out-0`。注意 `FunctionWrapper.apply` 行 850-853 的限制：路由函数返回 Publisher 不支持。

## 8.5 运维面：端点、控制器与暂停

Actuator 暴露两个端点（`endpoint/BindingsEndpoint.java`、`endpoint/ChannelsEndpoint.java`，自动配置见 3.1 节 imports）：

- `GET /actuator/bindings`：`queryStates`（行 78-87）返回全部绑定的名称、目的地、状态（running/stopped/paused，来自 `DefaultBinding.getState` 行 106-117）、扩展信息（binder 属性脱敏后输出，`sanitizeSensitiveData` 行 105+）；
- `POST /actuator/bindings/<name>`：`changeState`（行 73-75）配 `BindingsLifecycleController`（`binding/BindingsLifecycleController.java`）执行 stop/start/pause/resume——pause/resume 底层是 SI `Pausable`（`DefaultBinding.pause/resume` 行 162-186，Kafka 监听容器原生支持暂停消费）；
- `BindingsLifecycleController` 还支持运行期**创建新绑定**（`createInputBinding`/`createOutputBinding` 行 104-141，指定 binder 名与目的地属性）与"定义即注册"的 `defineInputBinding`/`initializeBinding`（行 143-178，配合 3.3 节的独立绑定代理工厂）。

动态目的地除 StreamBridge 外还有 `DynamicDestinationsBindable`（`binding/DynamicDestinationsBindable.java`，`BindingServiceConfiguration` 行 237-240 注册）：`spring.cloud.stream.output-bindings`/`dynamic-destinations` 声明的输出绑定可运行期增删。

## 8.6 可观测性、AOT 与 CRaC

- **可观测性**：通道层面 SI 7 的 `registerObservationRegistry`（StreamBridge 行 296）；binder 层面 kafka binder 的 `enableObservation` 透传到 `KafkaTemplate` 与监听容器（9.2 节）；错误传播统一过全局 errorChannel 便于日志聚合（7.1 节⑥）。
- **AOT**：`aot/StreamRuntimeHints.java` 行 35-42 注册 `InputBindingLifecycle`/`OutputBindingLifecycle` 等的反射提示；binder 子上下文预生成见 4.4 节；kafka-streams binder 另有 `KafkaStreamsBinderRuntimeHints`。
- **CRaC**：binder 工厂的 SmartLifecycle（4.4 节）+ `ContextStartAfterRefreshListener`（`binding/ContextStartAfterRefreshListener.java`，refresh 后补偿启动）共同保证检查点恢复后绑定可用。

## 8.7 测试支持：不起 Kafka 的单元测试

**（白话）** `spring-cloud-stream-test-binder` 提供一个把"消息系统"退化为内存通道的 binder，配套两个测试用"假目的地"：

【源码证据】`core/spring-cloud-stream-test-binder/src/main/java/org/springframework/cloud/stream/binder/test/InputDestination.java` javadoc 行 40-73 给出了完整用法说明（绑定名 vs 目的地名的注意事项），核心 API 行 78-80：

```java
public void send(Message<?> message, String destinationName) {
    this.getChannelByName(destinationName).send(message);
}
```

```java
// OutputDestination.java 行 46-55
public Message<byte[]> receive(long timeout, String bindingName) {
    bindingName = bindingName.endsWith(".destination") ? bindingName : bindingName + ".destination";
    return this.outputQueue(bindingName).poll(timeout, TimeUnit.MILLISECONDS);
}
```

```java
@SpringBootTest
@EnableTestBinder                                   // @Import(TestChannelBinderConfiguration.class)
class UppercaseTests {
    @Autowired InputDestination input;
    @Autowired OutputDestination output;

    @Test
    void transforms() {
        input.send(new GenericMessage<>("hello"), "uppercase-in-0");
        assertThat(new String(output.receive(1000, "uppercase-out-0").getPayload(), StandardCharsets.UTF_8))
            .isEqualTo("HELLO");
    }
}
```

`TestChannelBinder` 继承 `AbstractMessageChannelBinder`（行 109-110）——**测试 binder 与生产 binder 走完全相同的绑定/转换/错误链路**，只把"中间件"换成内存通道；它还支持 `@PollableSource` 测试（`messageSourceDelegate` 行 117-135）与断言最近一条错误消息（`getLastError` 行 137-139）。`OutputDestination.afterChannelIsSet`（行 105-111）自动把每个输出通道订阅进内存队列，让 `receive` 像消费队列一样工作。

---

# 九、两个官方 Binder 深入：Kafka 与 Rabbit

## 9.1 Kafka binder 的模块结构与装配

kafka-binder 拆为 `kafka-core`（provisioner、属性、health、公共工具）与 `kafka`（binder 本体）。装配类 `KafkaBinderConfiguration`（行 96-263）以 `@ConfigurationProperties` 声明 `KafkaBinderConfigurationProperties` 并注册 binder、provisioner、health 等十来个 bean；`META-INF/spring.binders` 登记 `kafka` 类型（4.3 节）。

## 9.2 生产者侧：createProducerMessageHandler

【源码证据】`KafkaMessageChannelBinder.java` 行 412-512 摘要（这是 4.2 节工厂方法的最复杂实现之一）：

```java
KafkaAwareTransactionManager<byte[], byte[]> transMan = transactionManager(
        producerProperties.getExtension().getTransactionManager());
final ProducerFactory<byte[], byte[]> producerFB = transMan != null
        ? transMan.getProducerFactory()
        : getProducerFactory(null, producerProperties, destination.getName() + ".producer", destination.getName());
Collection<PartitionInfo> partitions = provisioningProvider.getPartitionInfoForProducer(
        destination.getName(), producerFB, producerProperties);
...
if (producerProperties.isPartitioned() && producerProperties.getPartitionCount() < partitions.size()) {
    ...
    producerProperties.setPartitionCount(partitions.size());        // 实际分区数反写回属性（BindingService 侧同步，5.1）
    List<ChannelInterceptor> interceptors = ((InterceptableChannel) channel).getInterceptors();
    interceptors.forEach((interceptor) -> {
        if (interceptor instanceof PartitioningInterceptor partitioningInterceptor) {
            partitioningInterceptor.setPartitionCount(partitions.size());   // 分区数同步进拦截器
        }
        ...
    });
}
KafkaTemplate<byte[], byte[]> kafkaTemplate = new KafkaTemplate<>(producerFB);
...
ProducerConfigurationMessageHandler handler = new ProducerConfigurationMessageHandler(
        kafkaTemplate, destination.getName(), producerProperties, producerFB, getBeanFactory());
if (errorChannel != null) {
    handler.setSendFailureChannel(errorChannel);                    // 异步发送失败 → 错误通道（7.1）
}
```

事务支持（行 416-423 注释原文）：配置 `spring.cloud.stream.kafka.binder.transaction.transaction-id-prefix` 后整个 binder 进入事务模式，所有生产者共用事务管理器的 ProducerFactory，"个别 producer 属性被忽略"。header 映射由 `BinderHeaderMapper`（kafka-core）按 `headerPatterns` 决定哪些 Spring 消息头进 Kafka headers（行 479-512）。

## 9.3 消费者侧：监听容器的组装与分区分配

【源码证据】`KafkaMessageChannelBinder.java` `createConsumerEndpointCaptureHelper` 行 594-712 摘要：

```java
boolean anonymous = !StringUtils.hasText(group);
Assert.isTrue(!anonymous || !extendedConsumerProperties.getExtension().isEnableDlq(),
        "DLQ support is not available for anonymous subscriptions");
String consumerGroup = anonymous ? "anonymous." + UUID.randomUUID().toString() : group;
...
Collection<PartitionInfo> listenedPartitions = new ArrayList<>();
...
if (!extendedConsumerProperties.isMultiplex()) {
    listenedPartitions.addAll(provisioningProvider.getListenedPartitions(consumerGroup,
            extendedConsumerProperties, consumerFactory, partitionCount, usingPatterns, groupManagement, ...));
}
...
final ConcurrentMessageListenerContainer<K, V> messageListenerContainer =
        new ConcurrentMessageListenerContainer<>(consumerFactory, containerProperties) { ... };
...
messageListenerContainer.setConcurrency(concurrency);   // min(concurrency, 分区数)
...
ContainerProperties.AckMode ackMode = extendedConsumerProperties.getExtension().getAckMode();
```

分区分配两条路线：`autoRebalanceEnabled=true`（默认）交给 Kafka consumer group 协议，容器只订阅 topic；false（显式 `partitioned` + `instanceCount`/`instanceIndex`）时 provisioner 按 `instanceIndex * concurrency` 的偏移**手工算出该实例负责的分区**并构造 `TopicPartitionOffset`（行 607-645）。并发数取 `min(concurrency, 实际分区数)`（行 668-670）。监听容器把记录转成 `Message<byte[]>` 后发往输入通道——旅程回到 5.3 节。

## 9.4 Kafka DLQ：handleRecordForDlq

7.3 节已述主流程。补充细节（`KafkaMessageChannelBinder.java` 行 1262-1310）：死信消息默认**原样保留 ConsumerRecord 的 key/value/headers**，附加异常类名、异常消息、原始 topic-partition-offset 等头；`nativeDecoding` 场景会检查反序列化器配置能否安全重发（行 1266-1296）；`DlqPartitionFunction` 决定死信分区（默认 0 或跟随原始分区）。

## 9.5 Rabbit binder：声明式拓扑与 republishToDlq

Rabbit binder 的 `RabbitMessageChannelBinder`（行 692-970 为错误处理核心）配 `RabbitProvisioner` 声明式建拓扑：每个 `destination.group` 建一个队列（`groupedName`：`destination.group`，4.1 节）、destination 建 topic exchange、两者按 routing key 绑定——消费组语义天然由"竞争消费者共享队列"实现。错误处理两模式见 7.3 节。生产端 `AmqpOutboundEndpoint` 包装 `RabbitTemplate`，`mapped-request-headers` 决定头映射。

## 9.6 批处理模式（batchMode）

`ConsumerProperties.batchMode=true`（行 180）切换为一次拉取一批：Kafka binder 用监听容器 batch listener 把整批记录包成 `Message<List<ConsumerRecord>>`（`function/StandardBatchUtils.java` 提供批消息工具），函数签名改为 `Consumer<List<T>>`；AckMode 在批模式下对 RECORD 有约束（行 707-710）；DLQ 处理对批消息逐条转发（7.3 节行 1245-1251）。

## 9.7 本章小结

| Binder 关注点 | Kafka | Rabbit |
|---|---|---|
| 目的地实体 | topic | topic exchange + 队列（destination.group） |
| 消费组语义 | consumer group 协议（或手工分区分配） | 竞争消费者共享队列 |
| 分区 | 原生（分区=顺序单元） | 无原生分区（partitioned 支持有限） |
| DLQ | 独立 DLQ topic + DlqSender | republish（默认）或原生死信交换器 |
| 头映射 | BinderHeaderMapper + headerPatterns | mapped-request-headers |
| 额外模块 | kafka-streams（KStream/KTable 函数） | — |

---

# 十、贯通视图：三条时间线看懂 Spring Cloud Stream 全貌

## 10.1 时间线一：启动（从 main 到 Kafka 消费就绪）

```
SpringApplication.run
  └ EnvironmentPostProcessor（RoutingFunction/PollerConfig，spring.factories）
  └ 自动配置（imports 顺序）：
      FunctionConfiguration（含 BinderFactoryAutoConfiguration）
        ├ BinderTypeRegistry ← classpath 扫描 META-INF/spring.binders
        ├ StreamBridge / FunctionBindingRegistrar / FunctionToDestinationBinder / supplierInitializer（声明，未执行）
      BindingServiceConfiguration
        ├ getBinderConfigurations（声明 binder 实例或补默认）
        ├ BindingService / OutputBindingLifecycle / InputBindingLifecycle / BindingsLifecycleController
  └ 单例实例化：
      FunctionBindingRegistrar.afterPropertiesSet
        └ determineFunctionName → 注册 BindableFunctionProxyFactory（function-in-0/-out-0 通道就绪）
      FunctionToDestinationBinder.afterPropertiesSet
        └ 函数 handler 订阅到输入通道（或响应式接线）
      supplierInitializer
        └ Supplier → IntegrationFlow + <binding>_spca 端点
  └ SmartLifecycle 按 phase 启动：
      OutputBindingLifecycle.start（phase=MIN+1000，先）
        └ bindProducer → binderFactory.getBinder（建子上下文）→ AbstractMessageChannelBinder.doBindProducer
           → provisioner 建 topic/exchange → createProducerMessageHandler → SendingHandler 订阅 → BindingCreatedEvent
      InputBindingLifecycle.start（phase=DEFAULT-3000，后）
        └ bindConsumer → doBindConsumer → provisioner 建 topic/queue → createConsumerEndpoint（监听容器）
           → 容器 start → 位移就绪，开始消费
```

## 10.2 时间线二：一次 StreamBridge.send

```
streamBridge.send("out", pojo)
  ├ resolveDestination：缓存未命中 → new DirectWithAttributesChannel → bindProducer（动态目的地，可淘汰）
  ├ getStreamBridgeFunction：目录里名为 streamBridge 的恒等函数（按属性 hash 缓存）
  ├ PartitionAwareFunctionWrapper（若分区）：extractKey → selector → scst_partition 头
  ├ function.apply：convertOutput（contentType → byte[]，Jackson 3）→ byte[] Message
  └ channel.send
      └ OutboundContentTypeConvertingInterceptor（非函数路径兜底）
      └ SendingHandler.handleMessage：embedHeaders（可选）/ nativeEncoding 透传
      └ ProducerConfigurationMessageHandler → KafkaTemplate.send → broker ack
          └（失败）sendFailureChannel → <binding>.errors 通道
```

## 10.3 时间线三：一条消息的消费（含失败）

```
broker push/poll
  └ ConcurrentMessageListenerContainer（Kafka）→ Message<byte[]>
      └ 通道拦截器：EmbeddedHeaders 解包（可选）→ InboundContentTypeEnhancing（补 contentType）
      └ 唯一订阅者：FunctionConfiguration 的匿名 handler
          └ FunctionWrapper.apply
              ├ sanitize（去 sendto 头）/ CloudEvent 标记
              ├ FunctionInvocationWrapper.doApply
              │    ├ convertInput：byte[] → T（CompositeMessageConverter，Jackson 3）
              │    ├ invokeFunction：业务函数（重试由此层 RetryTemplate 包裹，maxAttempts-1 次）
              │    └ convertOutput：R → byte[]（按输出 contentType）
              ├ result==null → 丢弃；Iterable/数组 → 展开
              └ MessagingTemplate.send → <fn>-out-0 通道 → SendingHandler → KafkaTemplate（回到时间线二尾部）
  └ 容器按 AckMode 提交位移
  └（异常路径）
      RetryTemplate 退避重试（初始 1s ×2 倍数，上限 10s）
        └ 耗尽 → ErrorMessageSendingRecoverer → <binder>.<binding>.errors
            ├ 用户 error-handler-definition 函数（若有）
            ├ binder DLQ 处理器（enableDlq → DlqSender → error.<topic>.<group>）
            ├ 全局 errorChannel 桥（日志/告警）
            └ FinalRethrowingErrorMessageHandler（无 DLQ 时重抛 → 容器 errorHandler，位移不提交，重启后重投）
```

## 10.4 从源码中提炼的四个设计模式视角

1. **模板方法**：`AbstractMessageChannelBinder` 的 doBindXxx 固化五步流程，子类只填工厂方法——整个 binder 生态的杠杆。
2. **策略 + 工厂**：`BinderTypeRegistry`/`BinderConfiguration`/子上下文三级发现，本质是"SPI 的 SPI"；分区两策略、转换器链、错误处理器链同构。
3. **装饰与拦截器**：从通道拦截器（转换/分区/嵌头）到 `FunctionWrapper`/`PartitionAwareFunctionWrapper`，Stream 对消息流的增强几乎全部用"包一层"实现，避免改内核。
4. **命名即配置**：`<fn>-in-0`、`<binding>_spca`、`<binder>.<binding>.errors`、`destination.group`——大量组件靠命名约定互相发现，省掉显式装配；读源码时**遇到神秘字符串先查常量表**（`FunctionConstants`、`BinderHeaders`、`MessageConverterUtils`）。

---

# 十一、附录

## 11.1 关键接口/类速查表

| 类型 | 名称 | 一句话职责 | 所在 |
|---|---|---|---|
| SPI | `Binder<T,C,P>` | bindConsumer / bindProducer | core/binder |
| SPI | `ProvisioningProvider<C,P>` | 目的地实体供给 | core/provisioning |
| SPI | `PollableConsumerBinder` | 轮询绑定支持 | core/binder |
| 模板 | `AbstractBinder` | final 绑定方法 + 重试工厂 | core/binder |
| 模板 | `AbstractMessageChannelBinder` | 绑定五步 + 错误基础设施 + SendingHandler | core/binder |
| 编排 | `BindingService` | 绑定动作入口（含 LateBinding 重试） | core/binding |
| 编排 | `Bindable` / `BindableProxyFactory` | 绑定集合抽象 / 通道代理 | core/binding |
| 编排 | `InputBindingLifecycle` / `OutputBindingLifecycle` | 按 phase 启停绑定 | core/binding |
| 函数 | `FunctionConfiguration`（3 个内部类） | 函数→绑定装配 | core/function |
| 函数 | `BindableFunctionProxyFactory` | 命名约定 + 通道创建 | core/function |
| 函数 | `StreamBridge` | 命令式发送/动态目的地 | core/function |
| 函数 | `FunctionInvocationWrapper`（s-c-function） | 统一调用 + 双侧转换 | function-context |
| 工厂 | `DefaultBinderFactory` | 子上下文 + 决策树 + AOT/CRaC | core/binder |
| 转换 | `MessageConverterConfigurer` | contentType 拦截器 + 分区拦截器 | core/binding |
| 转换 | `CompositeMessageConverterFactory` | 内置转换器组装 | core/converter |
| 错误 | `BinderErrorChannel` / `FinalRethrowingErrorMessageHandler` | 错误通道 / 兜底重抛 | core/binder |
| 特性 | `PartitionHandler` / `DefaultPollableMessageSource` | 分区计算 / 轮询消费 | core/binder |
| 运维 | `BindingsEndpoint` / `BindingsLifecycleController` | 状态查询与治理 | core/endpoint, core/binding |
| 测试 | `TestChannelBinder` / `InputDestination` / `OutputDestination` | 内存 binder 与假目的地 | test-binder |
| 范本 | `KafkaMessageChannelBinder` / `RabbitMessageChannelBinder` | 生产级 binder 实现 | binders/* |

## 11.2 配置命名规则速查

| 前缀/模式 | 含义 |
|---|---|
| `spring.cloud.function.definition` | 要绑定的函数定义（`;` 分隔、`|` 组合） |
| `spring.cloud.stream.bindings.<binding>.destination/group/contentType/binder` | 绑定三元组 |
| `spring.cloud.stream.bindings.<binding>.consumer|producer.*` | 绑定级消费/生产属性 |
| `spring.cloud.stream.consumer|producer.*` | 全局默认（绑定级覆盖） |
| `spring.cloud.stream.binders.<name>.type/environment/defaultCandidate` | 多 binder 实例声明 |
| `spring.cloud.stream.function.bindings.<fn-in-0>=<alias>` | 绑定改名 |
| `spring.cloud.stream.input-bindings` / `output-bindings` | 独立绑定（不挂函数） |
| `spring.cloud.stream.<binder>.<binderName>.properties.*`（如 `spring.cloud.stream.kafka.binder.*`） | binder 扩展（全局） |
| `spring.cloud.stream.<binder>.bindings.<binding>.consumer|producer.*`（如 `...kafka.bindings.<b>.consumer.startOffset`） | binder 扩展（绑定级） |
| `spring.cloud.stream.bindings.<b>.error-handler-definition` | 用户错误处理函数 |
| `spring.cloud.stream.binding-retry-interval` | 绑定建立失败的调度重试间隔（秒，默认 30） |

## 11.3 初学者学习路线（动手向）

1. **跑通最小应用**（第二章），用 `TestChannelBinder` + `@EnableTestBinder` 写单测验证函数接线（8.7 节）；
2. 换上 kafka binder 起一个本地 Kafka，观察启动日志里的 binder 子上下文创建与 topic 自动建 topic（`KafkaTopicProvisioner`）；
3. 故意抛异常，对照 10.3 时间线观察：重试日志 → 错误通道 → `enableDlq` 死信 topic 的消息头；
4. 打开 `/actuator/bindings`（加 spring-boot-starter-actuator + 暴露端点），试 POST 停/启、pause/resume；
5. 进阶：把函数改成 `Flux`/`BiFunction` 体验多输入输出（8.3 节）；加 `partitionKeyExpression` 验证 8.1 节分区头；
6. 源码精读：按 11.4 清单逐类读，重点把 3.6 节 lifecycle 与 4.3 节子上下文串成自己的启动时序图。

## 11.4 源码阅读入口清单（20 个关键文件）

相对路径以 `core/spring-cloud-stream/src/main/java/org/springframework/cloud/stream/` 为基（binders 单独标注）：

1. `config/BinderFactoryAutoConfiguration.java` — binder 类型注册与绑定目标工厂
2. `config/BindingServiceConfiguration.java` — 绑定服务与生命周期 bean
3. `function/FunctionConfiguration.java` — **全文最核心**：函数装配三内部类
4. `function/BindableFunctionProxyFactory.java` — 命名约定与通道创建
5. `function/StreamBridge.java` — 命令式发送
6. `function/PartitionAwareFunctionWrapper.java` — 分区在函数层的落点
7. `binding/BindingService.java` — 绑定动作 + LateBinding
8. `binding/AbstractBindableProxyFactory.java` — createAndBindInputs/Outputs
9. `binding/InputBindingLifecycle.java` / `binding/OutputBindingLifecycle.java` — 启停时序
10. `binding/MessageConverterConfigurer.java` — contentType/分区拦截器
11. `binder/Binder.java` → `binder/AbstractBinder.java` → `binder/AbstractMessageChannelBinder.java` — SPI 主线
12. `binder/DefaultBinderFactory.java` — 子上下文与决策树
13. `binder/DefaultBinding.java` — 绑定运行期实体
14. `binder/DefaultPollableMessageSource.java` — 轮询消费
15. `binder/PartitionHandler.java` — 分区计算
16. `converter/CompositeMessageConverterFactory.java` — 转换器目录
17. `endpoint/BindingsEndpoint.java` — 运维端点
18. `../spring-cloud-stream-test-binder/.../test/TestChannelBinder.java` — 测试范本与最小 binder 样例
19. `binders/kafka-binder/spring-cloud-stream-binder-kafka/.../KafkaMessageChannelBinder.java` — 生产级范本
20. `D:\code\3rd\spring-cloud-function\spring-cloud-function-context\.../SimpleFunctionRegistry.java` — `FunctionInvocationWrapper` 调用内核（Stream 的一切函数调用最终经它）

## 结语

回到 1.2 节的四句话：**函数即消息处理器**把业务逻辑从基础设施中解放出来；**Binder 是消息中间件的 JDBC**把中间件差异锁进 SPI；**一切绑定都长在 Spring Integration 管道上**让 Stream 以极小的核心代码（core 主模块 116 个类）撬动整个消息生态；**配置驱动、可运维、可静态化**则让它适配云原生的生命周期管理。读完本文，希望你再看"Spring Cloud Stream 应用"时，看到的不再是黑盒注解，而是这样一幅确定的图景：*一条函数装配链（第三章）、一次 SPI 交割（第四章）、两条消息时间线（第十章）、一套分层错误语义（第七章）*——每一环都有类名、方法名与行号可循。

与同系列文档互为印证之处：Stream 的函数调用内核（`FunctionInvocationWrapper` 的类型推断与转换）建立在《Spring Framework.md》第三章的 `ResolvableType`/类型转换体系之上；其生命周期管理（SmartLifecycle phase）是《Spring Framework.md》4.6.2 节机制的具体应用；而 binder 子上下文的构建方式，则是 ApplicationContext 体系（refresh/parent/PropertySource）的一次教科书式复用。
