# Spring for Apache Kafka 深度源码解析（写给初学者的架构全景）

> **本文基于的源码**：`D:\code\3rd\spring-kafka`，版本 **4.2.0-SNAPSHOT**（main 分支快照，Git commit `3f766c67`，2026-10-04 读取）。写作时最新稳定版为 **4.1.1**（kafka-clients **4.2.1**、Spring Framework **7.0.9**），main 分支基线为 kafka-clients **4.3.1**、Spring Framework **7.1.0-M2**。文中所有【源码证据】的文件路径与行号均为对该快照实际读取所得（主模块路径 `spring-kafka/src/main/java/org/springframework/kafka/...`）。
>
> **版本取舍说明**：spring-kafka 自 2.8 引入 `DefaultErrorHandler`、2.7 引入 `@RetryableTopic` 非阻塞重试之后，核心骨架（KafkaTemplate / 监听容器 poll 循环 / AckMode / 事务管理器）在 3.x 与 4.x 之间保持稳定；4.0 主要是基线升级（Framework 7 / Boot 4 / kafka-clients 4.x），4.1 新增共享组（ShareGroup）容器支持。因此本文内容对使用 Spring Boot 3.x（spring-kafka 3.x）的读者同样适用，4.x 新增点在 1.6 节单独列出。所有"某特性属于哪个版本"的结论均经本地 git 标签（v2.8.0 ~ v4.1.1）与 `@since` 标注逐项实证。
>
> **阅读约定**：与同系列文档（《Spring Framework.md》《Spring Cloud Stream.md》）一致，每章"先白话、后源码"——先用一两句人话讲清"这是什么、为什么需要它"，再给出源码证据链（`模块/.../类名.java` + 方法名 + 行号 + 代码片段）。行号只对该快照精确，读者用 IDEA 打开源码按类名 + 方法名定位即可。

## 如何读这份文档

如果你是 Spring for Apache Kafka（下文按官方习惯简称 **spring-kafka**）初学者，推荐两遍读法：

- **第一遍（建立地图，1~2 小时）**：只读第一章（总览）每一节的开头白话段、第二章（上手与概念）、各章的"小结"节、第九章（与 Spring Cloud Stream 的对比）、第十章（贯通视图）。目标是能回答：`KafkaTemplate.send` 到 broker ack 经过了哪几步？一个 `@KafkaListener` 方法如何长出一个监听容器？消费失败后重试与死信是谁在驱动？spring-kafka 与 Spring Cloud Stream 各管什么？
- **第二遍（深入源码）**：对照每一章的【源码证据】逐行读。顺序建议：第三章（生产端，回答"producer 由谁管理"）→ 第四章（注册链路，回答"容器从哪来"）→ 第五章（容器内核，回答"poll 循环怎么跑"）→ 第六章（错误处理）→ 第七章（事务）→ 第八章（扩展件，随用随查）→ 第九章（对比，读源码前先重读一遍第一章 1.7）。

---

# 一、总览：spring-kafka 的定位、设计哲学与整体架构

## 1.1 一句话定位

**spring-kafka 是 Apache Kafka 官方 Java 客户端（kafka-clients）的 Spring 化封装**：`KafkaTemplate` 把"取 Producer → 发送 → 回调"模板化并接上 Spring 消息抽象，`@KafkaListener` + 监听容器把"poll 循环 → 分派 → 提交位移"容器化，`KafkaAdmin` 把建题声明化，`KafkaTransactionManager` 把 Kafka 原生事务接进 Spring 事务抽象。它**不改变 Kafka 的任何语义**（分区顺序、offset、消费者组、事务边界都原样保留），只把客户端对象的生命周期、线程模型、异常翻译、重试语义、可观测性接进 IoC 容器。

它解决的不是"消息中间件的组织问题"（那是 Spring Cloud Stream 的事），而是**一个具体客户端的使用问题**：KafkaProducer 线程不安全创建成本高怎么办（工厂 + 缓存）、KafkaConsumer 的 poll 循环怎么写才正确（容器替你写好了）、消费失败怎么重试与进死信（错误处理器 + 非阻塞重试主题）、消费与生产如何原子（原生事务的 Spring 事务接口化）。官方仓库自述其目标："Spring for Apache Kafka (spring-kafka) applies core Spring concepts to the development of Kafka-based messaging solutions."

两个容易混淆的点先说清楚：

1. **spring-kafka 不是 Spring 官方的"Kafka 消息抽象层"**——它只有一个中间件：Kafka。想写一次代码跑在 Kafka 和 RabbitMQ 上，用的是 Spring Cloud Stream；但 Stream 的 Kafka binder 底层**就是** spring-kafka 的对象（`KafkaMessageChannelBinder` 内部 new 的是 `KafkaTemplate` 和 `ConcurrentMessageListenerContainer`，见《Spring Cloud Stream.md》9.2/9.3 节）。两者是"地基与门面"的关系，第九章展开对比。
2. **spring-kafka 本身没有自动配置**——`spring.kafka.*` 属性、`KafkaAutoConfiguration`（提供 ProducerFactory/ConsumerFactory/KafkaTemplate/监听容器工厂等 Bean）都住在 Spring Boot 的 `module/spring-boot-kafka` 里。裸用 spring-kafka 时这些对象要自己声明。

## 1.2 设计哲学：读源码前先记住四句话

1. **客户端对象贵如线程，工厂负责生死**。KafkaProducer 创建时要做 TCP 握手与元数据拉取、KafkaConsumer 是非线程安全的轮询器，都不该随手 new。spring-kafka 把"何时创建、缓存还是池化、何时物理关闭"全部收进 `ProducerFactory`/`ConsumerFactory`（第三章）：非事务场景工厂内**单例共享**一个 Producer（`doCreateProducer`，`DefaultKafkaProducerFactory.java:784`），事务场景按 `transactionIdPrefix` 维护**可归还的池**（`createTransactionalProducer:861`），`close()` 只是"归还"，物理关闭由工厂在容器停止/销毁时统一做（`CloseSafeProducer.close:1240`）。
2. **poll 循环是唯一的消费原语，容器把它容器化**。kafka-clients 的消费本质是一个单线程 `while(poll)` 循环，位移提交时机、rebalance 处理、暂停恢复全靠手写极易出错。spring-kafka 用 `KafkaMessageListenerContainer.ListenerConsumer.run()`（`KafkaMessageListenerContainer.java:1438`）把这个循环固化成产品：你在它上面只做两件事——选一个 `AckMode`（七种提交策略，见 5.6 节），写一个监听方法；剩下的"何时提交、失败是否 seek 回去、rebalance 时提交到哪"全是容器的代码路径。
3. **注解是声明，工厂才是语义**。`@KafkaListener` 的每个属性（concurrency、autoStartup、properties、ackMode……）都没有魔法：注解被 `KafkaListenerAnnotationBeanPostProcessor` 读出来填进 `KafkaListenerEndpoint`，端点交给容器工厂长成容器，属性最终落到 `ContainerProperties` 或 consumer 配置（第四章逐行走读）。理解了这条链，注解的一切行为都可推理。
4. **框架不发明语义，只做接线**。Kafka 的幂等、事务、read_committed、分区顺序全部来自 kafka-clients；spring-kafka 做的是把这些语义**接到 Spring 的既有抽象上**——事务接到 `PlatformTransactionManager`（第七章，骨架与 `DataSourceTransactionManager` 同一套 `AbstractPlatformTransactionManager`），消息接到 `org.springframework.messaging.Message`（8.3 节转换器），指标接到 Micrometer（8.6 节），异常统一翻译成 `KafkaException` 体系。这也是为什么它比 Stream"薄"：没有 binder 抽象层，省下的复杂度变成了对 Kafka 能力的无损暴露。

## 1.3 包分层全景

spring-kafka 仓库实测（main 快照）共 5 个顶层模块：`spring-kafka`（主模块，354 个源码文件）、`spring-kafka-test`（内嵌 broker 与测试支持）、`spring-kafka-bom`、`spring-kafka-docs`、`samples`。主模块**单包体系、包即模块**，21 个包按"用户感知"分层如下：

```
┌──────────────────────────── 测试层 ──────────────────────────────┐
│ spring-kafka-test 模块（EmbeddedKafkaBroker KRAFT 内嵌集群、      │
│   @EmbeddedKafka、ContainerTestUtils）；主模块 mock 包           │
│   （MockConsumerFactory/MockProducerFactory，不起 broker 单测）  │
├──────────────────────────── 高级特性层 ─────────────────────────┤
│ retrytopic（非阻塞重试：RetryTopicConfigurer、DestinationTopic…）│
│ requestreply（ReplyingKafkaTemplate 请求-应答、Aggregating…）    │
│ streams（Streams DLQ 异常处理器、InteractiveQueryService；       │
│   StreamsBuilderFactoryBean 在 config 包）                      │
├──────────────────────────── 错误与事务层 ────────────────────────┤
│ listener 包的错误处理子系统（CommonErrorHandler、                │
│   DefaultErrorHandler、DeadLetterPublishingRecoverer、SeekUtils）│
│ transaction（KafkaTransactionManager、KafkaAwareTransactionManager)│
├──────────────────────────── 容器层 ─────────────────────────────┤
│ listener（KafkaMessageListenerContainer 内核、                   │
│   ConcurrentMessageListenerContainer、ContainerProperties、      │
│   adapter 子包：MessagingMessageListenerAdapter 等 20+ 适配器）   │
├──────────────────────────── 生产端层 ────────────────────────────┤
│ core（KafkaTemplate、ProducerFactory/ConsumerFactory 工厂族、    │
│   KafkaAdmin、KafkaResourceHolder、Micrometer 监听器）           │
├──────────────────────────── 支撑层 ─────────────────────────────┤
│ annotation（@KafkaListener/@EnableKafka/@RetryableTopic/BPP）    │
│ config（容器工厂、端点、Registry/Registrar、StreamsBuilderFactoryBean)│
│ support（KafkaHeaders、头映射、converter 子包：消息转换、         │
│   serializer 子包：Json/ErrorHandling/Delegating 序列化器、       │
│   micrometer 子包：Observation 定义）                            │
│ event（17 个容器事件）、aot（RuntimeHints）、security/jaas        │
└──────────────────────────────────────────────────────────────────┘
```

与 Spring Framework 的多 Gradle 模块不同，spring-kafka 是**单模块多包**——包边界就是它的"模块边界"，包间依赖方向与上图一致（listener 不依赖 retrytopic；retrytopic 反过来大量复用 listener 的错误处理器；core 只依赖 support）。

## 1.4 依赖图（以主模块 build.gradle 实证）

`build.gradle:305-326` 的 `project('spring-kafka')` 依赖块实测：

| 依赖 | 角色 |
|---|---|
| spring-context / spring-messaging / spring-tx（api） | 容器接线、`Message`/`MessageConverter` 抽象、事务抽象 |
| org.apache.kafka:kafka-clients（api） | 唯一的中间件客户端 |
| micrometer-observation（api） | Observation API（3.0 起的观测门面） |
| kafka-streams、jackson（2 组：fasterxml + tools.jackson 3）、spring-data-commons、reactor-core、micrometer-core/tracing（全部 optional） | Streams、JSON 序列化、类型映射、响应式签名方法、指标 |

```
                kafka-clients（中间件语义的来源）
                        ▲
              spring-kafka（唯一主角）
              ▲           ▲          ▲
     spring-context   spring-messaging   spring-tx
        （IoC/事件）     （Message 抽象）    （事务 SPI）
                        ▲
        micrometer-observation（观测门面，api）
              ▲
   （optional）micrometer-core / reactor-core / kafka-streams / jackson
                        ▲
          Spring Boot module/spring-boot-kafka（KafkaAutoConfiguration，
          spring.kafka.* 属性 → 生产者/消费者工厂、模板、容器工厂 Bean）
```

这张图的要害：**spring-tx 是显式 api 依赖**（事务是头等公民）、**没有任何 Spring Cloud 依赖**（与 Cloud Stream 无耦合，Stream 反过来依赖它）、**optional 依赖全可缺**（不引 Jackson 则 Json 系列退化为编译期条件，不引 Streams 则 streams 包不可用）。

## 1.5 关键问题 → spring-kafka 方案映射（全文导览）

| 使用 Kafka 的关键问题 | spring-kafka 的方案 | 详见 |
|---|---|---|
| Producer 创建贵、Consumer 非线程安全 | ProducerFactory/ConsumerFactory + 单例/线程绑定/事务池三种模式 | 第三章 |
| 发送结果异步回调写起来繁琐 | `KafkaTemplate.send` → `CompletableFuture<SendResult>` + `ProducerListener` | 3.2 节 |
| `@KafkaListener` 方法从哪长出容器 | BPP 扫描 → Endpoint → Registrar → Registry → 容器工厂（五步链） | 第四章 |
| poll 循环、位移提交时机、乱序提交 | 容器内核 + 七种 AckMode（含 MANUAL/MANUAL_IMMEDIATE） | 第五章 |
| 消费失败：重试几次、seek 回去还是进死信 | `DefaultErrorHandler`（阻塞退避）+ `DeadLetterPublishingRecoverer`（死信） | 第六章 |
| 重试期间阻塞分区拖累吞吐 | `@RetryableTopic` 非阻塞重试：`-retry-N`/`-dlt` 主题树 + 分区暂停 | 6.5 节 |
| 消费-生产原子性（read_process_write） | `KafkaTransactionManager` + 容器事务 + `sendOffsetsToTransaction` | 第七章 |
| 建题脚本谁来管 | `KafkaAdmin` + `NewTopic` Bean 声明式建/改题 | 8.1 节 |
| JSON 消息类型还原、反序列化失败不丢消息 | JsonSerializer/JsonDeserializer 类型头 + `ErrorHandlingDeserializer` | 8.2 节 |
| 头透传到业务方法、SpEL 取值 | KafkaHeaders 常量族 + DefaultKafkaHeaderMapper + messaging 转换器 | 8.3 节 |
| 发一条消息等一个应答 | `ReplyingKafkaTemplate`（correlationId + 回复主题 + 超时调度） | 8.4 节 |
| 流处理任务想当 Spring Bean 管理 | `StreamsBuilderFactoryBean`（SmartLifecycle 包装 KafkaStreams） | 8.5 节 |
| 指标与链路追踪 | Micrometer 指标监听器 + `KafkaListenerObservation`/`KafkaTemplateObservation` | 8.6 节 |
| 单测不想起 broker | `@EmbeddedKafka`（KRAFT 内嵌集群）+ MockConsumer/MockProducer | 8.7 节 |
| 同一套代码换中间件 / 多中间件 | 这不是 spring-kafka 的问题域 → 用 Spring Cloud Stream | 第九章 |

## 1.6 版本演进：2.8 → 2.9 → 3.x → 4.x 关键变化对比

### 1.6.1 版本时间线与运行基线（git 标签实证）

GA 标签日期与基线（`git log -1 --format=%ci <tag>` + 各 tag 的 build.gradle/gradle.properties 实测）：

| 版本 | GA 日期 | kafka-clients | Spring Framework 基线 | 时代主题 |
|---|---|---|---|---|
| 2.8.x | 2021-11-15 | 3.0.2 | 5.3.x（Boot 2.6） | 错误处理统一（DefaultErrorHandler 取代 SeekToCurrent 系）、事务生产者池、receive() 雏形 |
| 2.9.x | 2022-07-25 | 3.2.3 | 5.3.x（Boot 2.7） | 2.x 终线，KIP-848 前的最后 5.x 基线 |
| 3.0.0 | 2022-11-21 | 3.3.2 | 6.0.x（Boot 3.0） | jakarta 命名空间、`send` 返回值 ListenableFuture→**CompletableFuture**、Micrometer **Observation** 接入 |
| 3.1.0 | 2023-11-20 | 3.6.0 | 6.1.x（Boot 3.2） | 容器线程池可换虚拟线程执行器（Boot 侧）、`ShareKafkaListenerContainerFactory` 雏形 |
| 3.2.0 | 2024-05-20 | 3.7.2 | 6.1.x（Boot 3.3） | `TransactionIdSuffixStrategy`（事务 id 池化限制）、批监听增强 |
| 3.3.0 | 2024-11-18 | 3.8.1 | 6.2.x（Boot 3.4） | kafka-clients 3.8/3.9 适配，4.0 前的稳定长线 |
| 4.0.0 | 2025-11-17 | 4.1.2 | 7.0.x（Boot 4.0） | Framework 7 基线、清理废弃 API（`ChainedKafkaTransactionManager` 已 @deprecated 等）、jspecify 判空注解 |
| 4.1.0 | 2026-06-09 | 4.2.1 | 7.0.x（Boot 4.1） | **KIP-932 共享组（ShareGroup/ShareConsumer）容器支持**（`ShareAckMode`、`DefaultShareConsumerFactory`、`@KafkaListener.ackMode` 属性） |
| 4.2.0（M 阶段） | 2026 下半年 | 4.3.1 | 7.1.x | 异步 ACK/异步应答（`asyncAcks`、inFlightAsyncResults）、事务位移修复等 |

### 1.6.2 特性引入版本对照表（@since 实证，可复现）

| 特性 | 引入版本 | 源码锚点 |
|---|---|---|
| `@KafkaListener` / `@EnableKafka` | 1.0 | — |
| `ReplyingKafkaTemplate` | 2.1.3 | `requestreply/ReplyingKafkaTemplate.java:80` |
| `ErrorHandlingDeserializer` | 2.2 | `support/serializer/ErrorHandlingDeserializer.java:42` |
| `SeekUtils` | 2.2 | `listener/SeekUtils.java:49` |
| 非阻塞重试 `@RetryableTopic`/`RetryTopicConfigurer` | 2.7 | `retrytopic/RetryTopicConfigurer.java:243` |
| `DefaultErrorHandler` 统一错误处理 | 2.8 | `listener/DefaultErrorHandler.java:50` |
| `KafkaTemplate.receive()` 单条拉取 | 2.8 | `core/KafkaTemplate.java`（receive javadoc） |
| Micrometer 定时器（MicrometerHolder 路线） | 2.5 | `KafkaTemplate.java:357`（setMicrometerEnabled） |
| Observation 路线（KafkaTemplateObservation 等） | 3.0 | `KafkaTemplate.java:443`（setObservationEnabled） |
| `CompletableFuture<SendResult>` 返回值 | 3.0 | `core/KafkaOperations.java:72` |
| `@KafkaListener.batch`（单工厂批/记录双模式） | 2.8 | `annotation/KafkaListener.java:305` |
| `@KafkaListener.filter`、`info`、`containerPostProcessor` | 2.8.4/3.1 | 同上 `:316/:334/:347` |
| `@KafkaListener.ackMode`（注解级 AckMode 覆盖） | **4.1** | 同上 `:357` |
| 共享组容器（ShareConsumer/ShareAckMode） | **4.1** | `listener/ContainerProperties.java:127`、core/DefaultShareConsumerFactory.java |

### 1.6.3 对初学者的意义：哪些知识过时了，哪些永远有效

- **过时的**：`SeekToCurrentErrorHandler`/`RecoveringErrorHandler` 等 2.8 之前的错误处理器家族（已被 `CommonErrorHandler` 统一）；`ListenableFuture` 返回值（3.0 换 CompletableFuture）；`ChainedKafkaTransactionManager`（@deprecated，改用容器事务管理器直接组合）；KIP-848 之前的 `zookeeper.connect` 心智。
- **永远有效的**：分区与位移语义、消费者组与 rebalance、AckMode 七策略、事务两阶段（beginTransaction/commitTransaction）、非阻塞重试的主题树模型。这些是 Kafka 本身的语义，框架只做了工程化包装。

## 1.7 与 Spring Cloud Stream 的问题域对比（摘要，详见第九章）

一句话版本：**spring-kafka 解决"怎么把 Kafka 用对、用稳"，Spring Cloud Stream 解决"怎么让业务代码不绑死任何消息中间件"**。前者暴露 Kafka 全部原生能力（分区精确控制、事务、Streams、KIP-848 共享组），代价是业务代码 import 的是 `org.springframework.kafka.*`；后者只暴露"目的地 + 函数"的最小公约数，代价是 Kafka 的深度能力要靠 binder 扩展属性间接透传（`spring.cloud.stream.kafka.binder.*`）。两者的结构关系是：**Stream 的 Kafka binder 底层就是 spring-kafka**——《Spring Cloud Stream.md》9.2/9.3 节已实证 `KafkaMessageChannelBinder` 内部构造 `KafkaTemplate`（其 `createProducerMessageHandler` 行 412-512）与 `ConcurrentMessageListenerContainer`（其 `createConsumerEndpoint` 行 594-712）。第九章给出完整对比表、同一需求两种写法与选型决策。

## 1.8 全文章节地图

```
第二章 上手与核心概念        —— 20 行代码 + 五个 Kafka 名词（框架每个机制都对应一个）
第三章 生产端（重点 A）      —— KafkaTemplate.doSend 全链路；ProducerFactory 三种模式
第四章 注册链路（重点 B）    —— @EnableKafka → BPP → Endpoint → Registrar → Registry → 容器
第五章 容器内核（重点 C）    —— poll 循环、AckMode、seek、暂停恢复（全文最长）
第六章 错误处理与死信（重点 D）—— DefaultErrorHandler → SeekUtils → DeadLetterPublishingRecoverer
                              → @RetryableTopic 非阻塞重试
第七章 事务（重点 E）        —— KafkaTransactionManager 与容器的消费-生产原子性
第八章 核心扩展件            —— KafkaAdmin / JSON 序列化 / 消息转换 / 请求应答 / Streams /
                              可观测性 / 测试支持 / AOT
第九章 与 Spring Cloud Stream 对比（重点 F）—— 问题域划分、对比表、两种写法、选型决策
第十章 贯通视图              —— 一条消息的一生：生产 → 消费 → 失败 → 重试 → 死信
附录                         —— 关键类速查表 / 学习路线 / 源码入口清单
```

---

# 二、上手与核心概念：先建立整体画面

## 2.1 二十行代码看懂编程模型

裸用 spring-kafka（不用 Boot）时，生产与消费的最小闭环：

```java
@Configuration
@EnableKafka                                    // ① 打开 @KafkaListener 开关
public class AppConfig {
    @Bean
    public ProducerFactory<Integer, String> pf() {
        return new DefaultKafkaProducerFactory<>(Map.of(
            BOOTSTRAP_SERVERS_CONFIG, "localhost:9092",
            KEY_SERIALIZER_CLASS_CONFIG, IntegerSerializer.class,
            VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
    }
    @Bean
    public ConsumerFactory<Integer, String> cf() {
        return new DefaultKafkaConsumerFactory<>(Map.of(
            BOOTSTRAP_SERVERS_CONFIG, "localhost:9092", GROUP_ID_CONFIG, "demo",
            KEY_DESERIALIZER_CLASS_CONFIG, IntegerDeserializer.class,
            VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
    }
    @Bean
    public ConcurrentKafkaListenerContainerFactory<Integer, String> kf() {
        var f = new ConcurrentKafkaListenerContainerFactory<Integer, String>();
        f.setConsumerFactory(cf());
        f.setConcurrency(3);                    // ② 3 个子容器 = 3 个 consumer 线程
        return f;
    }
}

@Service
public class Demo {
    private final KafkaTemplate<Integer, String> template = new KafkaTemplate(pf());

    @KafkaListener(id = "demo", topics = "t1")  // ③ 注解即端点
    public void listen(String value, @Header(KafkaHeaders.RECEIVED_PARTITION) int p) {
        System.out.println("p" + p + " -> " + value);
    }
}
```

用 Spring Boot 时 ①②③ 全部消失：`KafkaAutoConfiguration`（`spring-boot/module/spring-boot-kafka/.../KafkaAutoConfiguration.java:85`）根据 `spring.kafka.*` 属性（`KafkaProperties`，`:64` 的 `@ConfigurationProperties("spring.kafka")`）自动装配 `ProducerFactory`/`ConsumerFactory`/`KafkaTemplate`（`:101-112`，还会把 `messageConverter`、`observationConvention`、`producerListener` 注入模板）/名为 `kafkaListenerContainerFactory` 的默认容器工厂；业务代码只剩一个 `@KafkaListener` 方法。

这个对比本身就是 spring-kafka 的设计陈述：**框架只负责"对象怎么造、循环怎么跑"，Boot 负责"配置怎么映射"，你只负责"消息来了做什么"。**

## 2.2 五个名词：框架的每个机制都对应 Kafka 的一个语义

1. **分区（Partition）与位移（Offset）**：Kafka 的并行与顺序单元。容器并发模型（`concurrency` 个子容器，每个一个 consumer 线程，5.1 节）、AckMode 提交的就是位移（5.6 节）、`@PartitionOffset` 手工定位（4.3 节）都建立在这个语义上。
2. **消费者组（Consumer Group）**：同一组内分区独占分配。`@KafkaListener.id` 默认兼作 `group.id`（`KafkaListenerAnnotationBeanPostProcessor.java:864-873` 的 `getEndpointGroupId`）；4.1 新增的 ShareGroup（KIP-932）是另一条路——消息逐条确认、组内共享消费，spring-kafka 以独立的 Share 容器族支持（8.8 节）。
3. **Rebalance**：组成员变化触发的分区再分配。容器在 rebalance 前后回调 `ConsumerAwareRebalanceListener` 并处理"未提交位移的归属"（`checkRebalanceCommits`，`KafkaMessageListenerContainer.java:1935`）。
4. **幂等与事务（transactional.id）**：生产端精确一次的基础。`setTransactionIdPrefix` 自动打开 `enable.idempotence`（`DefaultKafkaProducerFactory.java:689-695`），第七章全部围绕它。
5. **`__TypeId__` 类型头（spring-kafka 私有约定）**：JSON 序列化器写入/读取的类型坐标，是 8.2 节类型还原的载体——这是 spring-kafka 少数"发明"的协议，出跨语言消息时要注意。

## 2.3 本章小结

spring-kafka 的编程模型 = **一个模板（发）+ 一个注解（收）+ 一组工厂（对象管理）+ 一组属性（行为调参）**。下面三章沿"发 → 收 → 收失败"的顺序逐层拆开。

---

# 三、生产端：KafkaTemplate 与 ProducerFactory（重点 A）

## 3.1 模块定位

`core` 包是生产端的家：`KafkaOperations<K,V>` 接口定义能力面（send 七个重载、execute/executeInTransaction、flush、partitionsFor、metrics、sendOffsetsToTransaction、receive），`KafkaTemplate` 是唯一标准实现，`ProducerFactory` 接口族管理 Producer 对象。白话：**KafkaTemplate 自己几乎不干活，它的全部价值是"用对 Producer"+"翻译回调"+"接观测"**。

## 3.2 send 全链路：从 observeSend 到 CompletableFuture

【源码证据】`core/KafkaTemplate.java:599-642`——send 系列全部是"构造 `ProducerRecord` → `observeSend`"的两行方法；`send(Message<?>)`（`:633`）先用 `MessagingMessageConverter.fromMessage` 把 Spring 消息翻成 ProducerRecord（topic 来自 `kafka_topic` 头或 defaultTopic，见 8.3 节）。

【源码证据】`core/KafkaTemplate.java:819-878`——`observeSend` + `doSend` 主链：

```java
private CompletableFuture<SendResult<K, V>> observeSend(final ProducerRecord<K, V> producerRecord) {
    Observation observation = KafkaTemplateObservation.TEMPLATE_OBSERVATION.observation(
            this.observationConvention, DefaultKafkaTemplateObservationConvention.INSTANCE,
            () -> new KafkaRecordSenderContext(producerRecord, this.beanName, this::clusterId),
            this.observationRegistry);                       // ① 观测起点（3.0 起默认可开）
    observation.start();
    try (Observation.Scope ignored = observation.openScope()) {
        return doSend(producerRecord, observation);
    } ...

protected CompletableFuture<SendResult<K, V>> doSend(final ProducerRecord<K, V> producerRecord,
        Observation observation) {
    final Producer<K, V> producer = getTheProducer(producerRecord.topic());   // ② 取 Producer（3.4 节）
    final CompletableFuture<SendResult<K, V>> future = new CompletableFuture<>();
    ...
    Future<RecordMetadata> sendFuture =
            producer.send(interceptedRecord, buildCallback(interceptedRecord, producer, future, sample, observation));
    if (sendFuture.isDone()) { ... }          // ③ 立即失败（元数据超时等）同步抛 KafkaException
    if (this.autoFlush) { flush(); }
    return future;                            // ④ 异步完成点
}
```

【源码证据】`core/KafkaTemplate.java:888-926`——`buildCallback` 是"客户端回调 → Spring 事件"的翻译层：成功则 `future.complete(new SendResult<>(record, metadata))` + `producerListener.onSuccess`（默认 `LoggingProducerListener` 只打失败日志）；失败则 `future.completeExceptionally(new KafkaProducerException(record, "Failed to send", exception))`；**最后 `closeProducer(producer, this.transactional)`——非事务 Producer 用完即逻辑关闭，事务 Producer 归还缓存**（这一行是理解 Producer 生命周期的钥匙）。

要点归纳：

1. **返回值即完成点**：`CompletableFuture` 在 broker ack（或失败）时完成，`thenApply`/`get` 都行；2.x 的 `ListenableFuture` 已成历史（1.6.2 表）。
2. **默认不阻塞**：`linger.ms`/`batch.size` 照常生效；`autoFlush=true` 时每次 send 后调 `producer.flush()`（`:873-875`），牺牲吞吐换即时可见。
3. **micrometer 定时器与 Observation 双轨**：老路线 `MicrometerHolder`（`spring.kafka.template` 计时器，`:854-856/:928-952`），新路线 Observation（`:819-823`，`afterSingletonsInstantiated:499-531` 里解析 ObservationRegistry 与 KafkaAdmin 的 clusterId 作为 tag）。

## 3.3 Producer 的三种生存模式

【源码证据】`core/DefaultKafkaProducerFactory.java`。三个入口决定 Producer 的生命周期：

```java
private Producer<K, V> doCreateProducer(@Nullable String txIdPrefix) {   // :784
    if (txIdPrefix != null) {
        return createTransactionalProducer(txIdPrefix);   // 模式三：事务池（按 txIdPrefix 分队列）
    }
    if (this.producerPerThread) {
        return getOrCreateThreadBoundProducer();          // 模式二：线程绑定（threadBoundProducers，:134）
    }
    // 模式一：单例共享（producer 字段 :172），globalLock 保护，closed/maxAge 时换新
    if (this.producer == null || ...) { this.producer = new CloseSafeProducer<>(...); }
    return this.producer;
}
```

- **模式一（默认）**：全应用共享一个 `CloseSafeProducer`。KafkaProducer 本身线程安全，共享是安全且最高效的；`close()` 被 `CloseSafeProducer` 拦截（`:1240-1260`），非失败路径只是"标记 + 归还回调"，**物理关闭只发生在工厂 `destroy()`/`reset()`**（`:703-744`，同时 `epoch` 自增让旧引用失效）。
- **模式二（`setProducerPerThread(true)`，:452）**：每线程一个 Producer，避免共享下的锁竞争；**工厂销毁不关它们**（javadoc 明示），要自己调 `closeThreadBoundProducer()`。
- **模式三（事务池）**：`createTransactionalProducer:861-880`——每个 `txIdPrefix` 一个 `BlockingQueue` 缓存（`:132`），取出时检查 `maxAge`（`:882-888`，防 transactional.id 过期），新建时 `transactionIdSuffixStrategy.acquireSuffix` 生成唯一 `transactional.id`（`prefix+suffix`，3.2 起可限制池大小），`doCreateTxProducer:923-948` 里 `initTransactions()` 失败会归还 suffix 并抛 `KafkaException`。

【源码证据】`CloseSafeProducer`（`:1054-1280`）是理解"关闭语义"的关键类：

```java
public void close(@Nullable Duration timeout) {                     // :1240
    if (!this.closed) {
        if (this.producerFailed != null) {                          // 失败过：物理关闭、不还池
            this.closed = true; this.removeProducer.test(...); this.delegate.close(...);
        } else {
            this.closed = this.removeProducer.test(this, timeout);  // 正常：还池（返回 false 表示留在缓存）
            if (this.closed) { this.delegate.close(...); }
        }
    }
}
```

失败记忆机制：`send` 回调收到 `OutOfOrderSequenceException`（`:1133-1136`）、`beginTransaction`/`commitTransaction` 抛异常（`:1169-1199`）都会记 `producerFailed`，之后 `abortTransaction` 直接跳过（`:1203-1220`）——一个失败的 Producer 永不回池，这是幂等序列号可能已错乱的正确处理。

## 3.4 executeInTransaction 与三态发送

【源码证据】`core/KafkaTemplate.java:679-723`——`executeInTransaction` 把 Producer **绑定到当前线程**（`this.producers.put(currentThread, producer)`，`:696`），begin/commit/abort 全在方法内完成，禁止嵌套（`:684`）；`commitTransaction` 失败包装成内部 `SkipAbortException` **不再 abort**（`:700-708`，因为提交失败通常意味着事务已被 broker 终结）。`inTransaction():962-966` 三查：线程绑定 map → `TransactionSynchronizationManager` 资源 → 是否存在活跃事务。

【源码证据】`core/KafkaTemplate.java:972-1004`——`getTheProducer` 的三态分派（每次 send 的 ②）：

```java
if (transactionalProducer) {                       // 工厂支持事务
    if (!inTransaction()) { Assert.state(this.allowNonTransactional || inTransaction, ...
        "No transaction is in process; ... run in a transaction started by a listener container ..."); }
    // 在事务里：优先线程绑定，否则从 ProducerFactoryUtils.getTransactionalResourceHolder
    // 拿 TransactionSynchronizationManager 绑定的 Producer（第七章的接缝）
}
else if (this.allowNonTransactional) { return this.producerFactory.createNonTransactionalProducer(); }
else { return topic == null ? this.producerFactory.createProducer() : getProducerFactory(topic).createProducer(); }
```

这就是事务模板的行为矩阵：**事务能力开着 → 默认必须在事务里发（报错信息给出了三种出路）→ `allowNonTransactional` 放行非事务旁路**。

## 3.5 反向操作：receive()

【源码证据】`core/KafkaTemplate.java:751-798`——2.8 起模板提供 `receive(topic, partition, offset, pollTimeout)`：临时 `assign` + `seek` + `poll(max.poll.records=1)` 的一条取数工具（`receiveOne:788-798`），适合"补拉/审计"场景，不是消费主路径——消费请走第五章的容器。

## 3.6 本章小结

生产端三层：`send` 系列（薄）→ `doSend`（观测 + 取 Producer + 翻译回调）→ `ProducerFactory`（对象生死与事务池）。记住三个事实：**CompletableFuture 在 broker ack 时完成；非事务 Producer 用完即还、事务 Producer 池化复用；失败 Producer 永不回池**。

---

# 四、@KafkaListener 注册链路：注解如何长出容器（重点 B）

## 4.1 模块定位

本章回答"一个 `@KafkaListener` 方法如何变成一个正在 poll 的 consumer 线程"。五个主角按出场顺序：`@EnableKafka`（开关）→ `KafkaListenerAnnotationBeanPostProcessor`（BPP，扫描注解造端点）→ `KafkaListenerEndpoint`（端点：方法 + 定位 + 属性）→ `KafkaListenerEndpointRegistrar`（端点登记处）→ `KafkaListenerEndpointRegistry`（容器注册表 + SmartLifecycle 总闸）→ 容器工厂（端点长成容器）。

## 4.2 开关：@EnableKafka 注册了什么

【源码证据】`annotation/EnableKafka.java:246`——`@Import(KafkaListenerConfigurationSelector.class)`；selector 最终引入 `KafkaBootstrapConfiguration`（`annotation/KafkaBootstrapConfiguration.java:42-54`），它是 `ImportBeanDefinitionRegistrar`，注册两个基础设施 bean：

```java
registry.registerBeanDefinition(KafkaListenerConfigUtils.KAFKA_LISTENER_ANNOTATION_PROCESSOR_BEAN_NAME, ...);  // BPP
registry.registerBeanDefinition(KafkaListenerConfigUtils.KAFKA_LISTENER_ENDPOINT_REGISTRY_BEAN_NAME, ...);     // Registry
```

Boot 环境下 `KafkaAutoConfiguration` 额外用 `@EnableKafka` 语义打开注解处理，但这两个 bean 的名字与职责不变——**理解了它们就理解了任何环境下的启动前提**。

## 4.3 BPP 扫描：从方法到端点

【源码证据】`annotation/KafkaListenerAnnotationBeanPostProcessor.java`。

**入口** `postProcessAfterInitialization:385-428`：对每个 bean，`AopUtils.getTargetClass` 拿真身（保证代理后的注解可见），`MethodIntrospector.selectMethods` 找方法级 `@KafkaListener`（可重复，`KafkaListeners` 容器注解）；类级 `@KafkaListener` + `@KafkaHandler` 方法组走 `processMultiMethodListeners:470-494` 造 `MultiMethodKafkaListenerEndpoint`（一个容器按消息类型分派多个方法，`isDefault()` 方法兜底）。没有注解的类缓存进 `nonAnnotatedClasses`（`:182`）避免重复扫描。

**注解 → 端点** `processKafkaListenerAnnotation:662-703`：方法级属性逐个解析——`${...}` 先 `resolveEmbeddedValue`，`#{...}` 再走容器 `BeanExpressionResolver`（`resolveExpression:1172-1187`；`beanRef`/`__listener` 伪 bean 名通过 `ListenerScope`（`:1308-1348`）让 SpEL 能引用所在 bean，如 `topics = "#{__listener.topicList}"`）。落到端点的字段：id/groupId/topics/topicPattern/topicPartitions/concurrency/autoStartup/consumerProperties/batch/errorHandler/filter/ackMode（4.1 新增，`resolveAckMode:749-757`）/containerPostProcessor。

**id 与 groupId 规则**（`getEndpointId:854-861`、`getEndpointGroupId:864-873`）：不写 id 则自动 `org.springframework.kafka.KafkaListenerEndpointContainer#N`（`:175`）；groupId 未指定且 `idIsGroup=true`（默认）时 **id 即 group.id**——这是"一个监听器一个组"习惯的来源。

**手工定位**：`resolveTopicPartitionsList:927-961` 支持 `@TopicPartition(topic="t", partitions="0-5,10-15")`（区间解析 `parsePartitions:1219-1243`）与 `@PartitionOffset(partition="*", initialOffset="0", relativeToCurrent=...)`，产出的 `TopicPartitionOffset[]` 由容器在启动时 `seek`（5.7 节）。

**retry 分叉**（`processMainAndRetryListeners:511-549`）：`RetryTopicConfigurationProvider` 先查有没有 `@RetryableTopic`/`RetryTopicConfiguration` 匹配本方法——有则交 `RetryTopicConfigurer` 造"主端点 + N 个重试端点 + DLT 端点"（6.5 节），没有才走普通 `processListener → registrar.registerEndpoint`。容器不存在该 bean 时 `createDefaultConfigurer:574-610` 会现场注册一套默认 `RetryTopicConfigurationSupport`。

## 4.4 Registrar 延迟登记与 Registry 总闸

【源码证据】`config/KafkaListenerEndpointRegistrar.java:189-261`：BPP 在 Bean 生命周期早期就开始处理各 bean 的注解，但端点**先攒进 `endpointDescriptors`**；直到 `afterSingletonsInstantiated`（所有单例就绪）触发 `registerAllEndpoints:193-211` 才统一 `endpointRegistry.registerListenerContainer(...)`，并把 `startImmediately` 置 true——此后**动态注册**的端点（如 prototype bean）立即建容器并启动。

【源码证据】`config/KafkaListenerEndpointRegistry.java:265-371`：

```java
public void registerListenerContainer(KafkaListenerEndpoint endpoint, KafkaListenerContainerFactory<?> factory,
        boolean startImmediately) {                                   // :265
    String id = endpoint.getId(); Assert.hasText(id, ...);
    Assert.state(!this.listenerContainers.containsKey(id), "Another endpoint is already registered with id '...'");
    MessageListenerContainer container = createListenerContainer(endpoint, factory);   // :277
    this.listenerContainers.put(id, container);                       // :278 —— id 即容器寻址键
    ... // containerGroup 归组（ContainerGroup bean，可整组启停/排序）
    if (startImmediately) { startIfNecessary(container); }
}

protected MessageListenerContainer createListenerContainer(...) {      // :329
    ...
    MessageListenerContainer listenerContainer = factory.createListenerContainer(endpoint);  // :349
    ...
    int containerPhase = listenerContainer.getPhase();                 // :360 —— phase 上提
    if (listenerContainer.isAutoStartup() && containerPhase != DEFAULT_PHASE) { this.phase = ...; }
    return listenerContainer;
}
```

Registry 自己也是 `SmartLifecycle`（`:79`）：`contextRefreshed` 后按 phase 启动所有 autoStartup 容器（`start():389-394`）。**phase 上提规则**：容器的 phase（默认 `Integer.MAX_VALUE`，见 `AbstractMessageListenerContainer.DEFAULT_PHASE`）如果被自定义，会同步到 Registry——所以自定义 phase 的容器在启动时序里可以插到比默认更早的位置（配合"数据库就绪后再消费"这类需求）。

## 4.5 容器工厂：端点长成容器

【源码证据】`config/AbstractKafkaListenerContainerFactory.java:388-406`——`createListenerContainer` 四步：

```java
C instance = createContainerInstance(endpoint);          // ① 子类造空容器（ConcurrentKafkaListenerContainerFactory → new ConcurrentMessageListenerContainer）
... setBeanName / setMainListenerId ...
if (Boolean.TRUE.equals(endpoint.getBatchListener())) {
    endpoint.setupListenerContainer(instance, this.batchMessageConverter);   // ② 把适配器塞进容器（决定监听器形态）
} else {
    endpoint.setupListenerContainer(instance, this.recordMessageConverter);
}
initializeContainer(instance, endpoint);                  // ③ 工厂属性拷贝进容器
customizeContainer(instance, endpoint);                   // ④ ContainerCustomizer/ContainerPostProcessor 最后润色
```

第 ② 步的端点侧实现在 `config/MethodKafkaListenerEndpoint.java:174-249`：`createMessageListenerInstance` 按形态选适配器——`ShareRecordMessagingMessageListenerAdapter`（4.1 共享组）/ `BatchMessagingMessageListenerAdapter`（批）/ `RecordMessagingMessageListenerAdapter`（单条），统一包装成 `MessagingMessageListenerAdapter`（方法反射调用器，8.3 节）；有 `@SendTo` 返回值场景会校验 replyTemplate（`:182-187`）。

第 ③ 步（`initializeContainer:441-470`）的 `BeanUtils.copyProperties(this.containerProperties, properties, ...)` 一行值得停留：**工厂上配的 ContainerProperties 整体拷给容器**（排除 topics 等定位字段），之后端点级覆盖（autoStartup 等）再落上去——这就是"注解属性覆盖工厂属性覆盖全局属性"三层结构在源码里的样子。`ContainerCustomizer`（`:337`）与注解的 `containerPostProcessor`（3.1 起）提供后置修改口子。

## 4.6 本章小结

注册链路五步一图：

```
@EnableKafka ─→ KafkaBootstrapConfiguration ─→ 注册 BPP + Registry（基础设施）
@KafkaListener ─→ BPP.postProcessAfterInitialization ─→ MethodKafkaListenerEndpoint（属性解析）
             ├─ 有 @RetryableTopic → RetryTopicConfigurer（多端点，第六章）
             └─ 无 → Registrar.registerEndpoint（攒单，afterSingletonsInstantiated 统一登记）
                     └→ Registry.registerListenerContainer
                          └→ 容器工厂.createListenerContainer（造容器 + 拷属性 + 定制）
                               └→ Registry.start()（SmartLifecycle，contextRefreshed 后）
                                    └→ ConcurrentMessageListenerContainer.doStart（第五章）
```

---

# 五、消费容器内核：poll 循环、提交与暂停恢复（重点 C）

## 5.1 模块定位

`listener` 包是全文的心脏。两级容器：`ConcurrentMessageListenerContainer` 是"外壳"（管理 N 个子容器、并发与生命周期聚合），`KafkaMessageListenerContainer` 是"内核"（一个 consumer 线程 + 一个 poll 循环，4532 行的主类）。白话：**一个监听容器 = 一个或多个 KafkaConsumer 的托管循环**，业务方法只是这个循环里被回调的一小段。

## 5.2 外壳：concurrency 如何拆成子容器

【源码证据】`listener/ConcurrentMessageListenerContainer.java:246-305`——`doStart`：

```java
protected void doStart() {                                    // :246
    if (!isRunning()) {
        checkTopics();
        TopicPartitionOffset[] topicPartitions = containerProperties.getTopicPartitions();
        if (topicPartitions != null && this.concurrency > topicPartitions.length) {
            ... this.concurrency = topicPartitions.length;    // 手工定位时并发被夹到分区数
        }
        for (int i = 0; i < this.concurrency; i++) {          // :260 —— N 个子容器
            KafkaMessageListenerContainer<K, V> container =
                    constructContainer(containerProperties, topicPartitions, i);
            configureChildContainer(i, container);
            container.start();
            this.containers.add(container);
        }
    }
}
```

`configureChildContainer:273-305` 给每个子容器配：beanName 后缀 `-i`、`clientIdSuffix`（`:285`，并发>1 时才有，保证 client.id 唯一）、共享的 CommonErrorHandler/拦截器，以及**每子容器独立的 `SimpleAsyncTaskExecutor`**（`:294-304`，线程名 `beanName-C-`）——即每个 consumer 线程独占一个执行器，这正是换虚拟线程执行器（Boot 侧 `listenerTaskExecutor` 定制）的入口。`pause()/resume()`（`:459/:471`）级联到全部子容器。

## 5.3 内核状态机：ListenerConsumer 构造

【源码证据】`listener/KafkaMessageListenerContainer.java:943-1010`——`ListenerConsumer` 是 run 循环的全部状态，构造时确定：

```java
this.asyncReplies = listener instanceof AsyncRepliesAware hmd && hmd.isAsyncReplies()
        || this.containerProperties.isAsyncAcks();            // 4.2 新增异步 ACK 模式
this.ackMode = determineAckMode();                            // 手工 ack 检测：监听器带 Acknowledgment 参数 → 强制 MANUAL*
this.isRecordAck / isCountAck / isTimeAck / isManualAck ...   // 七模式布尔快照
this.offsetsInThisBatch = isOutOfCommit ? new ConcurrentHashMap<>() : null;  // 乱序提交跟踪（5.6）
this.autoCommit = determineAutoCommit(consumerProperties);    // enable.auto.commit 关闭与否
this.consumer = KafkaMessageListenerContainer.this.consumerFactory.createConsumer(
        this.consumerGroupId, containerProperties.getClientId(), clientIdSuffix, consumerProperties);  // :967
this.transactionTemplate = determineTransactionTemplate();    // 容器事务管理器 → TransactionTemplate（第七章）
subscribeOrAssignTopics(this.consumer);                       // subscribe(组管理) 或 assign(手工定位) 二选一
if (listener instanceof BatchMessageListener) { ... } else if (listener instanceof MessageListener) { ... }
```

要点：**autoCommit 被容器接管**——只要容器自己管提交（默认），`enable.auto.commit` 被强制关掉（`determineAutoCommit`），位移提交永远是容器代码路径里的一次 `commitSync/commitAsync`，不是客户端后台线程的行为。

## 5.4 run 主循环与 pollAndInvoke

【源码证据】`listener/KafkaMessageListenerContainer.java:1438-1513`——`run()`（每个子容器一条线程）：

```java
public void run() {
    initialize();                          // 线程改名、registerSeekCallback、childStarted、发 ConsumerStarting/Started 事件
    while (isRunning()) {
        try { handleAsyncFailure(); }      // 4.2 新增：异步重试失败记录的补处理
        catch (Exception e) { ...skip... }
        try { pollAndInvoke(); }           // 主体
        catch (NoOffsetForPartitionException nofpe) { fatalError = true; break; }      // 无位移且无复位策略
        catch (AuthenticationException | AuthorizationException ae) {
            if (this.authExceptionRetryInterval == null) { fatalError = true; break; }  // 默认致命
            sleepFor(this.authExceptionRetryInterval);    // 配了间隔则原地重试（发 ConsumerRetryAuthEvent）
        }
        catch (FencedInstanceIdException fie) { fatalError = true; break; }             // group.instance.id 被隔离
        catch (StopAfterFenceException e) { stop(false); ... }
        catch (Error e) { ...; throw e; }
        catch (Exception e) { handleConsumerException(e); }
        finally { clearThreadState(); }
    }
    wrapUp(exitThrowable);                 // 发 ConsumerStoppedEvent、关 consumer
}
```

【源码证据】`listener/KafkaMessageListenerContainer.java:1549-1600`——`pollAndInvoke` 是全文最重要的 50 行：

```java
protected void pollAndInvoke() {
    doProcessCommits();                    // ① 上一轮攒下的 ack 落盘（AckMode 策略）
    fixTxOffsetsIfNeeded();                // ② 事务场景位移修正（read_committed 滞后标记）
    idleBetweenPollIfNecessary();          // ③ 节流
    if (!this.seeks.isEmpty()) { processSeeks(); }   // ④ 待办 seek（手工 ack/错误处理器排队来的）
    enforceRebalanceIfNecessary();
    pauseConsumerIfNecessary(); pausePartitionsIfNecessary();   // ⑤ 暂停请求落地
    this.polling.set(true);
    ConsumerRecords<K, V> records = doPoll();
    if (!this.polling.compareAndSet(true, false) && records != null) { ... 丢弃 ... }  // ⑥ stop 竞态保护
    if (!this.firstPoll && this.definedPartitions != null && this.consumerSeekAwareListener != null) {
        this.firstPoll = true; this.consumerSeekAwareListener.onFirstPoll();
    }
    if (records != null && records.count() == 0 && this.isCountAck && this.count > 0) { commitIfNecessary(); }
    ...
    if (records == null || records.count() == 0) { fixTxOffsetsIfNeeded(); }  // poll 后再修一次（跨批事务标记）
    invokeIfHaveRecords(records);          // ⑦ 分派给监听器（5.5）
    if (this.remainingRecords == null) {   // ⑧ 没有剩余记录才恢复暂停
        resumeConsumerIfNecessary(); resumePartitionsIfNecessary();
    }
}
```

注意 ⑥ 的注释原文：*"There is a small race condition where wakeIfNecessaryForStop was called between exiting the poll and before we reset the boolean."*——容器停止与 poll 返回的竞态用 `polling` 标志 + CAS 处理，这就是"自己写 poll 循环容易漏的细节"的实例。

## 5.5 消息分派：从 ConsumerRecord 到业务方法

【源码证据】`listener/KafkaMessageListenerContainer.java:2880-3009, 3166-3260`：

```java
private void invokeRecordListener(final ConsumerRecords<K, V> records) {     // :2880
    if (this.transactionTemplate != null) { invokeRecordListenerInTx(records, this.transactionTemplate); }  // 事务容器（第七章）
    else { doInvokeWithRecords(records); }                                    // 普通：逐条 for 循环
}

private @Nullable RuntimeException doInvokeRecordListener(final ConsumerRecord<K, V> cRecord,
        Iterator<ConsumerRecord<K, V>> iterator) {                            // :3166
    Object sample = startMicrometerSample();
    Observation observation = KafkaListenerObservation.LISTENER_OBSERVATION.observation(   // 消费侧观测
            ..., () -> new KafkaRecordReceiverContext(cRecord, getListenerId(), getClientId(),
                    this.consumerGroupId, this::clusterId), this.observationRegistry);
    try {
        invokeOnMessage(cRecord);                                             // → 适配器（8.3）
        ...
    } catch (RuntimeException e) {
        ...
        if (this.commonErrorHandler == null) { throw e; }                     // 无处理器 → 往外抛（run 循环）
        try {
            invokeErrorHandler(cRecord, iterator, e);                         // → 第六章
            commitOffsetsIfNeededAfterHandlingError(cRecord);                 // ackAfterHandle 语义
        } catch (RecordInRetryException rire) { return rire; }                // 重试中：容器知道别急着提交
        catch (KafkaException | RuntimeException ee) { return ee; }           // 错误处理器抛了：本轮记为失败
        ...
    } ...
}

private void invokeOnMessage(final ConsumerRecord<K, V> cRecord) {            // :3257
    if (cRecord.value() instanceof DeserializationException ex) { throw ex; } // ErrorHandlingDeserializer 塞进来的毒丸
    if (cRecord.key() instanceof DeserializationException ex) { throw ex; }
    ...
}
```

三条支路在源码里一目了然：**批监听**走 `invokeBatchListener`（一次给 `List<ConsumerRecord>`，失败可抛 `BatchListenerFailedException` 精确指认第几条，否则整批交给回退处理器）；**事务监听**走 `invokeRecordListenerInTx:2895-2933`（每条记录一个 `TransactionTemplate` 事务，`ProducerFencedException` 按 `stopContainerWhenFenced` 决定停不停容器）；**普通监听**就是上面的逐条循环。

## 5.6 AckMode 全解与提交实现

【源码证据】`listener/ContainerProperties.java:67-120`——七种模式（javadoc 原文语义）：

| AckMode | 提交时机 | 典型场景 |
|---|---|---|
| RECORD | 每条处理完立即提交 | 最强不丢消息，吞吐最低 |
| BATCH（默认） | 上一次 poll 的一批全部处理完提交 | 默认折中 |
| TIME | 距上次提交超过 `ackTime` 毫秒 | 高吞吐容忍重复 |
| COUNT | 累计 `ackCount` 条 | 高吞吐容忍重复 |
| COUNT_TIME | COUNT 或 TIME 先到者 | 组合 |
| MANUAL | 监听器调 `Acknowledgment.acknowledge()`，ack 排队，整批处理完统一提交 | 手工控制 + 批量提交 |
| MANUAL_IMMEDIATE | acknowledge 时立即提交（消费线程上） | 精确手工控制 |

【源码证据】`listener/KafkaMessageListenerContainer.java:3529-3571`——提交引擎只有三个方法：

```java
private void processCommits() {                    // doProcessCommits 每轮 poll 前调用
    this.count += this.acks.size(); handleAcks();
    if (this.isCountAck) { countAcks(); }          // count >= ackCount → commitIfNecessary
    if (this.isTimeAck)  { timedAcks(); }          // now - last > ackTime → commitIfNecessary
    if (!this.isCountAck && !this.isTimeAck && !this.isManualImmediateAck) { commitIfNecessary(); this.count = 0; }
}
```

两个深坑在源码里能看到答案：

1. **乱序提交**：MANUAL 模式下监听器可能在异步线程上延迟 ack，导致"第 3 条先 ack、第 2 条后 ack"，如果直接按最大 offset 提交会把未处理的第 2 条跳过。容器用 `offsetsInThisBatch`（构造处 `:960-961`，仅 `isAnyManualAck && asyncReplies` 时启用）按序跟踪，`ackInOrder` 只在前面全部 ack 后才推进（配合 4.2 的 `asyncAcks` 属性与 `pausedForAsyncAcks` 状态，`:904`）。
2. **空 poll 的 COUNT 提交**：`:1579-1582`——poll 返回空但计数未到阈值时先提交，避免长期无消息时计数滞留。

## 5.7 seek 体系与初始位移

【源码证据】`listener/KafkaMessageListenerContainer.java:3584-3642`——`processSeeks` 统一处理六种 seek（`seeks` 队列）：绝对 offset、`relativeToCurrent`（当前位置 + N）、`offsetComputeFunction`、`TIMESTAMP`（先 `offsetsForTimes` 再 seek）、`BEGINNING`/`END`（可再叠加 offset）；seek 前校验分区还在手（`:3573-3582`，rebalance 可能已把分区分走）。**seek 是异步排队的**——错误处理器和 `Acknowledgment.nack()` 都往队列里塞，下一轮 pollAndInvoke 的 ④ 才执行，这保证 seek 永远发生在消费线程上（KafkaConsumer 非线程安全的纪律）。

手工定位（`@TopicPartition`）的初始位移在 `initPartitionsIfNeeded:3676+`：按 BEGINNING/END/TIMESTAMP 分组批量处理，javadoc 特别注明：*"initial position setting is only supported with explicit topic assignment. When using auto assignment (subscribe), the ConsumerRebalanceListener is not called until we poll()"*——即 subscribe 模式下初始定位要等 rebalance 回调，用 `ConsumerSeekAware`/`ConsumerAwareRebalanceListener` 在 `onPartitionsAssigned` 里 seek。

## 5.8 暂停恢复与容器事件

【源码证据】`listener/KafkaMessageListenerContainer.java:348-360`——`pause()/resume()/resumePartition(TopicPartition)`：容器级与分区级两套，落地在 pollAndInvoke 的 ⑤⑧（`pauseConsumerIfNecessary` → `consumer.pause(...)`，恢复前要确认没有剩余记录，防止暂停期间拉到的数据被丢弃）。**pausing 是 Kafka 消费者背压的标准姿势**：不是停线程，而是 poll 继续但不拉数据（保活 rebalance 心跳）。

事件面：`event` 包 17 个事件覆盖容器全生命周期——`ConsumerStartingEvent/ConsumerStartedEvent/ConsumerFailedToStartEvent`（启动）、`ListenerContainerIdleEvent`（容器空闲，`idleEventInterval` 配置）与 `ListenerContainerPartitionIdleEvent`（分区空闲，`idlePartitionEventInterval` 配置——**这正是非阻塞重试恢复分区的信号**，`KafkaConsumerBackoffManager` 监听它判断何时 resume，见 6.5 节）、`ConsumerPartitionPausedEvent/ResumedEvent`、`ConsumerStoppedEvent/ContainerStoppedEvent` 等，全部由容器 `ApplicationEventPublisher` 发布。

## 5.9 本章小结

容器内核 = **一个被精确工程化的 poll 循环**：提交（AckMode 策略 + 乱序保护）→ seek（异步排队 + 分区归属校验）→ poll（stop 竞态保护）→ 分派（单条/批/事务三支路）→ 暂停恢复（背压）。业务代码只贡献"收到记录做什么"，其余全是这 4532 行的职责——这也是"为什么不自己写 KafkaConsumer"的完整答案。

---

# 六、错误处理与死信：从 seek 重试到非阻塞重试（重点 D）

## 6.1 模块定位

消费失败的处置是 Kafka 应用最大的工程难点：Kafka 本身只有"位移提交"这一个原语，**重试与死信全是应用层概念**。spring-kafka 给出两级答案：**阻塞式**（seek 回去原地重试，`DefaultErrorHandler` + BackOff）与**非阻塞式**（转发到重试主题等延迟到期，`@RetryableTopic`）。两级的尽头都是 `DeadLetterPublishingRecoverer`（死信）。

## 6.2 异常在容器里的三道闸

1. **适配器层**（`listener/adapter/MessagingMessageListenerAdapter.java:457-492`）：`invokeHandler` 把反射调用的 checked 异常包成 `ListenerExecutionFailedException`；`MessageConversionException`/参数校验失败在 `checkAckArg:556` 里给出经典提示——*"No Acknowledgment available as an argument, the listener container must have a MANUAL AckMode..."*。
2. **容器层**（5.5 节 `doInvokeRecordListener:3166`）：统一进 Observation、翻译 `DeserializationException`，交给 `CommonErrorHandler`。
3. **错误处理器层**：`CommonErrorHandler`（2.8 起，`listener/CommonErrorHandler.java`）定义能力面：`handleOne/handleRemaining`（单条）、`handleBatch`（批）、`handleOtherException`（没有记录上下文的异常，如 poll 本身失败）、`isAckAfterHandle()`（处理后是否提交该条）、`deliveryAttemptHeader()`（是否往消息头写 `kafka_deliveryAttempt` 尝试次数，`DefaultErrorHandler.java:142-144` 返回 true）。

## 6.3 DefaultErrorHandler：阻塞式重试的默认答案

【源码证据】`listener/DefaultErrorHandler.java:53-111`——`DefaultErrorHandler extends FailedBatchProcessor implements CommonErrorHandler`，构造三件套：recoverer（可空=仅日志）、BackOff、BackOffHandler；内部还挂了一个 `FallbackBatchErrorHandler` 处理"批监听抛了非 BatchListenerFailedException"的整批回退。

【源码证据】`listener/SeekUtils.java:52-63, 209-251`——重试引擎只有两页：

```java
public static final int DEFAULT_MAX_FAILURES = 10;                              // :57
public static final FixedBackOff DEFAULT_BACK_OFF = new FixedBackOff(0, DEFAULT_MAX_FAILURES - 1);  // :63
// 0 间隔重试 9 次 —— 默认"同一条消息连续失败 10 次后放弃"

public static void seekOrRecover(Exception thrownException, List<ConsumerRecord<?, ?>> records,
        Consumer<?, ?> consumer, MessageListenerContainer container, boolean commitRecovered,
        RecoveryStrategy recovery, LogAccessor logger, Level level) {           // :209
    if (ObjectUtils.isEmpty(records)) {
        if (thrownException instanceof SerializationException) { throw new IllegalStateException(
            "This error handler cannot process 'SerializationException's directly; "
            + "please consider configuring an 'ErrorHandlingDeserializer' in the value and/or key deserializer", ...); }
        ...
    }
    if (!doSeeks(records, consumer, thrownException, true, recovery, container, logger)) {
        throw new RecordInRetryException("Record in retry and not yet recovered", thrownException);  // :227
    }
    if (commitRecovered) {   // MANUAL_IMMEDIATE 下同步/异步提交"已恢复"那条的 offset+1
        ... consumer.commitSync(offsetToCommit, ...) ...
    }
}
```

工作方式：`FailureTracker`（按 topic/partition/offset 记忆失败次数与下次重试时间）→ 未超限：`consumer.seek(tp, 当前 offset)` 把分区拉回失败位置，本轮 poll 的剩余记录一并回卷（`doSeeks:98-135` 只 seek 每个 touched 分区的最小 offset）；到达 BackOff 的 STOP：调用 recoverer（默认仅日志，通常配 `DeadLetterPublishingRecoverer`）。**这就是"阻塞"二字的代价：重试期间整个分区停摆**——引出 6.5 的非阻塞方案。批监听要配合 `BatchListenerFailedException` 指认失败位（否则从第一条开始回卷）。

## 6.4 DeadLetterPublishingRecoverer：死信发布器

【源码证据】`listener/DeadLetterPublishingRecoverer.java:71, 478-510, 540-552`——`accept` 五步：

```java
public void accept(ConsumerRecord<?, ?> record, @Nullable Consumer<?, ?> consumer, Exception exception) {
    TopicPartition tp = this.destinationResolver.apply(record, exception);   // ① 去哪：默认 <topic>-dlt，分区默认跟随原分区
    if (tp == null) { maybeThrow(record, exception); return; }
    if (this.skipSameTopicFatalExceptions && tp.topic().equals(record.topic()) && !getExceptionMatcher().match(exception)) {
        ... return; }                                                        // ② 原地打转保护
    if (consumer != null && this.verifyPartition) { tp = checkPartition(tp, consumer); }   // ③ 分区真实性校验
    DeserializationException vDeserEx = SerializationUtils.getExceptionFromHeader(record, ...);
    Headers headers = new RecordHeaders(record.headers().toArray());
    this.deadLetterRecordManager.addAndEnhanceHeaders(record, exception, vDeserEx, kDeserEx, headers);  // ④ 头增强
    ProducerRecord<Object, Object> outRecord = createProducerRecord(record, tp, headers, ...);
    KafkaOperations<?, ?> kafkaTemplate = this.templateResolver.apply(outRecord);
    sendOrThrow(outRecord, (KafkaOperations<Object, Object>) kafkaTemplate, record);        // ⑤ 发送
}
```

默认死信 topic 命名 `<原 topic>-dlt`（`destinationResolver` 默认实现），死信消息**原样保留 key/value/headers**，附加 `kafka_dlt-exception-fqcn`、`kafka_dlt-exception-stacktrace`、`kafka_dlt-original-topic/partition/offset/consumer-group/timestamp` 头（`support/KafkaHeaders.java:153-228` 的 DLT_* 常量族）。`send:540-552`：模板开着事务且未在事务中时，用 `executeInTransaction` 包住死信发送——**死信发送自身保持原子**。`checkPartition:554-575` 会用 `consumer.partitionsFor` 验证目标分区存在，不存在则回落 -1 让 Producer 自选。

## 6.5 @RetryableTopic：非阻塞重试（生产推荐）

【源码证据】`retrytopic/RetryTopicConfigurer.java:61-89, 307-393`——javadoc 把机制说尽了：

> If a message processing throws an exception, the configured DefaultErrorHandler and DeadLetterPublishingRecoverer forwards the message to the next topic, using a DestinationTopicResolver to know the next topic and the delay for it. Each forwarded record has a **back off timestamp header** and, if consumption is attempted by the KafkaBackoffAwareMessageListenerAdapter before that time, the partition consumption is **paused** by a KafkaConsumerBackoffManager ... When the partition has been idle ... a ListenerContainerPartitionIdleEvent is published, which the KafkaConsumerBackoffManager listens to in order to check whether it should unpause the partition.

```java
@RetryableTopic(attempts = 4, backOff = @BackOff(delay = 1000, multiplier = 2))   // 1s → 2s → 4s
@KafkaListener(topics = "main-topic")
public void listen(String msg) { ... }

@DltHandler
public void dlt(String msg) { ... }
```

装配行为（`processMainAndRetryListeners:307-323` → `processAndRegisterEndpoint:346-393`）：

1. 按 BackOff 参数生成主题树：`main-topic`、`main-topic-retry-1000`、`main-topic-retry-2000`、`main-topic-retry-4000`、`main-topic-dlt`（**名字里嵌延迟值**，`DestinationTopic.Properties` 携带每级延迟与后缀；`SuffixingRetryTopicNamesProvider` 生成）。
2. **为每级主题克隆一个端点**（`:359-372`，新 endpoint 复用主端点的方法与 id，`setMainListenerId` 保留主监听 id），同一个业务方法在 N 个主题上各有一个容器；DLT 端点的方法换成 `@DltHandler` 或默认 `LoggingDltListenerHandlerMethod`（`:260, 499-514`，仅打日志 + ack）。
3. 主端点的错误处理器被替换为"`DeadLetterPublishingRecoverer` + `DestinationTopicResolver`"的组合（`retrytopic/DeadLetterPublishingRecovererFactory.java`），失败时按当前级数转发到下一级主题，并写 `kafka_backoffTimestamp` 头；配置了自动建题时顺手注册 `TopicForRetryable` Bean（`:411-421`，交给 KafkaAdmin 建）。
4. 各重试容器的适配器包上 `KafkaBackoffAwareMessageListenerAdapter`（`listener/adapter/`）：消费到"还没到期"的记录就抛 `KafkaBackoffException`，`KafkaConsumerBackoffManager` 据此 **pause 该分区**，靠 `ListenerContainerPartitionIdleEvent`（5.8 节）到期后 resume——**重试期间不阻塞主流量，代价是同一分区内消息的顺序在主题树间被拆开**（javadoc 开头就承认 "at the expense of ordering guarantees"）。

与 6.3 的阻塞重试对比一句话：**阻塞重试 = 时间换顺序（原地 seek，分区停摆）；非阻塞重试 = 顺序换时间（主题树转发，主流量不停）**。两者可叠加：主容器阻塞重试耗尽 → recoverer 转发重试主题 → 主题树逐级延迟 → DLT。

## 6.6 事务回滚后的处置：AfterRollbackProcessor

【源码证据】`listener/KafkaMessageListenerContainer.java:2949-2978`——容器事务（第七章）里记录处理失败时，本次事务回滚，剩余未处理记录连同失败记录交给 `AfterRollbackProcessor.process(unprocessed, ...)`；默认实现 `DefaultAfterRollbackProcessor` 对未处理记录 **seek 回原位置重投**（语义同 6.3，只是触发条件是回滚而非异常），也可配自定义 recoverer 走死信。`isProcessInTransaction()`（`:2960-2965`）决定处理器自身是否在新事务里跑。

## 6.7 本章小结

错误处理三层：**容器兜底**（错误处理器默认 `DefaultErrorHandler`：0 间隔 seek 重试 9 次 → 交给 recoverer）→ **recoverer**（默认日志，生产配 `DeadLetterPublishingRecoverer`）→ **可选升级**（`@RetryableTopic` 非阻塞重试主题树）。记住一个判据：**分区停摆可接受、消息量小 → 阻塞重试；主流量敏感、延迟要求高 → 非阻塞重试；两者都要 → 组合**。

---

# 七、事务：消费-生产原子性（重点 E）

## 7.1 模块定位

先分清两个容易混的"事务"：

- **Kafka 原生事务**（kafka-clients 能力）：`transactional.id` + `initTransactions/beginTransaction/sendOffsetsToTransaction/commitTransaction`，配合消费者 `isolation.level=read_committed` 实现**跨分区、跨 topic 的生产原子性**，以及"消费位移提交 + 消息生产"的原子（消费-生产闭环）。它的边界：只有 Kafka 自己知道，不覆盖 DB。
- **Spring 事务抽象**（spring-tx）：`PlatformTransactionManager` 的 begin/commit/rollback 骨架、`@Transactional`、传播行为。spring-kafka 的 `KafkaTransactionManager` 把前者**接进**后者的骨架。

白话：**spring-kafka 没有发明任何事务语义，它只是让 Kafka 原生事务可以被 `@Transactional` 声明、被容器自动开启、被 `TransactionSynchronizationManager` 线程绑定**。

## 7.2 KafkaTransactionManager：复用 AbstractPlatformTransactionManager 骨架

【源码证据】`transaction/KafkaTransactionManager.java:71-95`——类声明与构造：

```java
public class KafkaTransactionManager<K, V> extends AbstractPlatformTransactionManager
        implements KafkaAwareTransactionManager<K, V> {
    public KafkaTransactionManager(ProducerFactory<K, V> producerFactory) {
        Assert.isTrue(producerFactory.transactionCapable(), "The 'ProducerFactory' must support transactions");
        setTransactionSynchronization(SYNCHRONIZATION_NEVER);   // 不参与事务同步（与 JT A 同理）
        this.producerFactory = producerFactory;
    }
```

源码走读（与《Spring Framework.md》6.5 节的 `DataSourceTransactionManager` 对照着读，骨架完全同构）：

| 骨架方法 | Kafka 实现 | 源码锚点 |
|---|---|---|
| `doGetTransaction` | 从 `TransactionSynchronizationManager.getResource(producerFactory)` 取 `KafkaResourceHolder` | `:127-132` |
| `isExistingTransaction` | resourceHolder != null | `:135-139` |
| `doBegin` | `ProducerFactoryUtils.getTransactionalResourceHolder(factory, txIdPrefix, closeTimeout)` 从**事务池**取 Producer 并 `beginTransaction`；隔离级别非默认直接抛 *"Apache Kafka does not support an isolation level concept"* | `:142-168` |
| `doSuspend/doResume` | 解绑/重绑 ThreadLocal 资源 | `:171-187` |
| `doCommit/doRollback` | `resourceHolder.commit()/rollback()`（内部即 `producer.commitTransaction()/abortTransaction()`） | `:190-207` |
| `doCleanupAfterCompletion` | 解绑 + `holder.close()`（Producer 归还事务池，3.3 节模式三） | `:220-229` |

【源码证据】`transaction/ChainedKafkaTransactionManager.java:32`——2.5 起已 @deprecated（4.x 仍在但仅作过渡）：旧教程里"DB 事务 + Kafka 事务串联"的做法不再推荐；同一事务多资源的正确姿势是外部协调（如先发后存 + 幂等消费），或干脆分别用各自的事务管理器。

## 7.3 KafkaTemplate 的三态发送与 @Transactional

3.4 节已拆过 `getTheProducer:972-1004`。放到事务语境里，三种用法的行为：

1. **`@Transactional("kafkaTransactionManager")`**（或默认事务管理器是 Kafka）：模板检测到 `TransactionSynchronizationManager` 里的 `KafkaResourceHolder`，复用线程绑定的 Producer——**多个 send 共享一个事务**，方法返回时统一 commit。
2. **`template.executeInTransaction(cb)`**：编程式，线程绑定 Producer，方法内 begin/commit（3.4 节）。
3. **无事务调用**：`allowNonTransactional=false`（默认）直接 `IllegalStateException`，报错文案就是操作指南（*"run the template operation within the scope of a template.executeInTransaction() operation, start a transaction with @Transactional before invoking the template method, run in a transaction started by a listener container when consuming a record"*）。

## 7.4 容器与事务：消费-生产原子闭环

【源码证据】`listener/KafkaMessageListenerContainer.java:2880-2933`——`containerProperties.setKafkaAwareTransactionManager(...)` 后，容器构造出 `TransactionTemplate`（`determineTransactionTemplate:976`），分派走 `invokeRecordListenerInTx`：

```java
private void invokeInTransaction(Iterator<ConsumerRecord<K, V>> iterator, final ConsumerRecord<K, V> cRecord,
        TransactionTemplate transactionTemplate) {                          // :2935
    transactionTemplate.executeWithoutResult((status) -> {
        if (ListenerConsumer.this.kafkaTxManager != null) {
            ListenerConsumer.this.producer = getTxProducer();                // 事务管理器借出的 Producer
        }
        RuntimeException aborted = doInvokeRecordListener(cRecord, iterator);
        if (aborted != null) { throw aborted; }                              // 业务异常 → 事务回滚
    });
}
```

**关键机制**：事务开启后，监听方法里的 `kafkaTemplate.send(...)` 复用本事务的 Producer（7.3 用法 1）；容器在该条记录处理完成后把**位移提交也并进事务**——底层是 `KafkaTemplate.sendOffsetsToTransaction(offsets, groupMetadata)`（`KafkaTemplate.java:743-747`，从线程绑定或 `TransactionSynchronizationManager` 找 Producer，`producerForOffsets:800-810`）。于是"消费 t1 的记录 → 处理 → 发 t2 → 提交位移"四步原子化：**downstream 只会看到已提交事务的消息（read_committed），crash 恢复后位移不会重复提交**。

回滚路径：业务抛异常 → 事务回滚（消息作废、位移未提交）→ 剩余记录交 `AfterRollbackProcessor`（6.6 节）→ 默认 seek 重投（下一次 poll 重新处理，天然形成"重试直到成功或进死信"）。**注意**：如果业务方法里还写了 DB，回滚 Kafka 事务救不了 DB——Kafka 事务只保证 Kafka 侧原子，跨系统一致性要靠幂等设计，这正是 `ChainedKafkaTransactionManager` 被废弃的原因。

fencing：容器消费中收到 `ProducerFencedException`/`FencedInstanceIdException` 时（`:2911-2917`），默认跳出本轮循环（stopContainerWhenFenced=true 则停容器，`StopAfterFenceException` 在 run 循环 `:1494-1498` 处理）——旧实例被新实例隔离后，老实例不该继续提交。

## 7.5 本章小结

事务 = **一套原生语义 + 三个入口**：`KafkaTransactionManager`（@Transactional 声明式）、`executeInTransaction`（编程式）、容器事务（消费-生产闭环）。判断是否需要它只需一句话：**你是否需要"发出去的消息"和"提交的位移"同生共死**——需要就上容器事务 + read_committed；只担心发送重复，把 `enable.idempotence` 打开就够了。

---

# 八、核心扩展件：Admin、序列化、转换、请求应答、Streams、观测、测试

## 8.1 KafkaAdmin：声明式建题

【源码证据】`core/KafkaAdmin.java:115-125, 268-336`——容器里每个 `NewTopic` Bean（topic 名/分区数/副本数/可选配置）都是一个声明；`KafkaAdmin` 实现 `SmartInitializingSingleton`，`afterSingletonsInstantiated:268-273` 时若 `autoCreate=true`（默认）则 `initialize()`：

```java
public final boolean initialize() {                             // :284
    Collection<NewTopic> newTopics = newTopics();
    if (!newTopics.isEmpty()) {
        Admin adminClient = createAdmin();                      // 失败看 fatalIfBrokerNotAvailable
        updateClusterId(adminClient);                           // clusterId 缓存（观测 tag 用）
        addOrModifyTopicsIfNeeded(adminClient, newTopics);      // 已存在且配置不同 → modify；缺失 → create
        ...
    }
}
```

三个实用点：**createOrModify**（分区数只能扩不能缩，缩容请求被忽略）；`createOrModifyTopic` 谓词（`:115`，可过滤哪些 NewTopic 交给它）；`clusterId()` 带锁缓存（`:326-336`），被 `KafkaTemplate`/监听容器借去当观测 tag（3.2/5.5 节的 `this::clusterId`）。重试主题的 `TopicForRetryable`（6.5 节）就是借这条通道自动建题的。

## 8.2 JSON 序列化三件套与类型头

【源码证据】`support/serializer/JsonSerializer.java` / `JsonDeserializer.java` / `ErrorHandlingDeserializer.java`。

- **类型坐标头**：`JsonSerializer` 把 value 类型写进 Kafka 头（`__TypeId__`，即 `JsonDeserializer` 的 `USE_TYPE_INFO_HEADERS` 机制）；`JsonDeserializer` 读取顺序：**头类型（受信任包校验）→ `spring.json.value.default.type` 兜底 → 显式传入 target type**。配置键全部以 `spring.json.*` 命名（`JsonDeserializer.java:81-109`）：`value.default.type`、`trusted.packages`（**反序列化 RCE 防线，默认只信任 java.lang/util**）、`use.type.headers`、`key.type.method`、`key/value.function`。
- **毒丸隔离**：`ErrorHandlingDeserializer`（2.2 起，`ErrorHandlingDeserializer.java:200-216`）包装真正的反序列化器——`deserialize` 内 try/catch，失败时把 `DeserializationException`（含坏数据）写进 `KEY/VALUE_DESERIALIZER_EXCEPTION_HEADER` 头并返回 null；容器 `invokeOnMessage:3257-3263` 发现记录值/键是 `DeserializationException` 就直接抛——**坏消息不丢进黑洞，而是变成一条可被 6.3 流程捕获、可进死信的普通异常**。这也解释了 `SeekUtils.seekOrRecover:214-223` 对裸 `SerializationException` 的报错文案：没有记录上下文的反序列化失败无法定位，必须配 `ErrorHandlingDeserializer`。

## 8.3 spring-messaging 桥：头与转换

【源码证据】`support/converter/MessagingMessageConverter.java:182-264`——`RecordMessageConverter` 契约的两个方向：

```java
public Message<?> toMessage(ConsumerRecord<?, ?> record, ...) {       // 消费方向 :182
    KafkaMessageHeaders kafkaMessageHeaders = new KafkaMessageHeaders(...);
    mapOrAddHeaders(record, rawHeaders);                              // Kafka headers → kafka_received* 系头
    commonHeaders(acknowledgment, consumer, rawHeaders, record.key(), record.topic(), record.partition(), record.offset(), ...);
    if (this.rawRecordHeader) { rawHeaders.put(KafkaHeaders.RAW_DATA, record); }   // 原始记录可留
    return MessageBuilder.createMessage(extractAndConvertValue(record, type), kafkaMessageHeaders);
}
public ProducerRecord<?, ?> fromMessage(Message<?> messageArg, @Nullable String defaultTopic) {  // 生产方向 :230
    Object topicHeader = headers.get(KafkaHeaders.TOPIC);             // kafka_topic 头决定目的地
    ...
    return new ProducerRecord(topic == null ? defaultTopic : topic, partition, timestamp, key,
            convertPayload(message), recordHeaders);                  // 头经 DefaultKafkaHeaderMapper 回写
}
```

`support/KafkaHeaders.java:32-264` 是两方向的词汇表：`kafka_` 前缀（`:32`）+ `RECEIVED_TOPIC/RECEIVED_PARTITION/OFFSET/ACKNOWLEDGMENT/CONSUMER/GROUP_ID/DELIVERY_ATTEMPT/EXCEPTION_*/DLT_*/CORRELATION_ID/REPLY_TOPIC...`——`@Header(KafkaHeaders.OFFSET)` 这些注解参数的取值全部来自这里。头映射默认 `DefaultKafkaHeaderMapper`（Jackson 供反序列化用），无 Jackson 时退化为 `kafka_nativeHeaders` 原样携带（`:210-226`）。批监听对应 `BatchMessagingMessageConverter`（一条 Spring 消息包 `List<ConsumerRecord>` 或逐条转换，`subBatchPerPartition` 可按分区再切）。

【源码证据】`listener/adapter/MessagingMessageListenerAdapter.java:509-554`——`invokeHandler` 三种参数形态：`handlerMethod.invoke(message, ack, consumer)` / 加 `data`（原 ConsumerRecord）/ 加 `ConsumerRecordMetadata`；此后 Framework 的 `InvocableHandlerMethod` 负责 `@Payload/@Header/@Headers` 解析与校验——这就是 `@KafkaListener` javadoc 里"灵活签名"的出处。

## 8.4 请求-应答：ReplyingKafkaTemplate

【源码证据】`requestreply/ReplyingKafkaTemplate.java:413-466, 499-508`——2.1.3 起的"Kafka 版 RPC"：

```java
public RequestReplyFuture<K, V, R> sendAndReceive(ProducerRecord<K, V> record, @Nullable Duration replyTimeout) {
    CorrelationKey correlationId = this.correlationStrategy.apply(record);   // 默认随机 UUID（16 字节）
    if (!hasReplyTopic && this.replyTopic != null) { headers.add(new RecordHeader(this.replyTopicHeaderName, this.replyTopic)); }
    headers.add(new RecordHeader(this.correlationHeaderName, correlationValue));  // kafka_correlationId
    RequestReplyFuture<K, V, R> future = new RequestReplyFuture<>();
    this.futures.put(correlation, future);                       // 关联表
    future.setSendFuture(send(record));                          // sendFuture / receiveFuture 两段
    scheduleTimeout(record, correlation, timeout);               // 超时调度器摘除并 completeExceptionally
    return future;
}
```

响应侧：模板自己也是一个**批监听器**（`onMessage:511+`），订阅 reply topic，按 `kafka_correlationId` 查 futures 表完成 receiveFuture。约束写在 KIP 与 javadoc：**服务端的应答应带上请求的 `kafka_correlationId` 与 `kafka_replyTopic` 头**（spring-kafka 的服务端适配器回写），`AggregatingReplyingKafkaTemplate` 支持一个请求聚合多个应答（按 release 头聚齐）。与 HTTP RPC 的本质差异：无连接、靠轮询，延迟取决于两次 broker 往返——适合离线对账类场景。

## 8.5 Kafka Streams 集成

【源码证据】`config/StreamsBuilderFactoryBean.java:396-476`——`KafkaStreams` 实例的 Spring Bean 包装（SmartLifecycle）：

```java
public void start() {                                            // :396
    ...
    this.kafkaStreams = this.kafkaStreamsCustomizer.initKafkaStreams(this.topology, this.properties, this.clientSupplier);
    this.kafkaStreams.setStateListener(this.stateListener);
    this.kafkaStreams.setGlobalStateRestoreListener(this.stateRestoreListener);
    if (this.streamsUncaughtExceptionHandler != null) { this.kafkaStreams.setUncaughtExceptionHandler(...); }
    this.kafkaStreamsCustomizer.customize(this.kafkaStreams);
    if (this.cleanupConfig.cleanupOnStart()) { this.kafkaStreams.cleanUp(); }
    this.kafkaStreams.start();
    for (Listener listener : this.listeners) { listener.streamsAdded(this.beanName, this.kafkaStreams); }
    this.running = true;
}
```

`@EnableKafkaStreams`（annotation 包，`KafkaStreamsDefaultConfiguration` 提供默认 factory bean）+ `KafkaStreamsConfiguration`（属性 map）即可把 Topology 交给容器托管启停（stop 时 `CloseOptions.timeout(...).withGroupMembershipOperation(...)`，`:450-455`，4.x 支持 leave/remain group 语义）。streams 包另给三件生产工具：`RecoveringDeserialization/Processing/ProductionExceptionHandler`（异常记录转发 DLQ，配合 `KafkaStreamsDeadLetterDestinationResolver`）、`KafkaStreamsInteractiveQueryService`（查状态存储）。

## 8.6 可观测性：两条路线

- **指标（Metrics）**：`core/MicrometerConsumerListener.java` / `MicrometerProducerListener.java` 挂到工厂上，把 kafka-clients 的原生 metrics 注册进 MeterRegistry（`kafka.consumer.*` / `kafka.producer.*`，tag 带 client.id）。
- **追踪（Observation/Tracing）**：`support/micrometer/` 下 `KafkaTemplateObservation`（发送侧 span，`KafkaRecordSenderContext` 携带目标 topic）与 `KafkaListenerObservation`（消费侧 span，`KafkaRecordReceiverContext` 携带 listener id/groupId/clusterId）——5.5 节 `doInvokeRecordListener:3170-3177` 与 3.2 节 `observeSend:819-838` 的调用点。追踪上下文通过 Kafka header 传递，`DefaultKafkaHeaderMapper` 的映射名单控制哪些头出网。老 `MicrometerHolder` 计时器路线仍在但被 Observation 取代（2.5 vs 3.0，见 1.6.2 表）。

## 8.7 测试支持：spring-kafka-test

【源码证据】`spring-kafka-test/src/main/java/org/springframework/kafka/test/EmbeddedKafkaBroker.java` + `test/context/EmbeddedKafka.java`——`@EmbeddedKafka`（JUnit 平台扩展 + ContextCustomizer）拉起内嵌 **KRAFT 模式**集群（可配 broker 数、端口、topic 自动创建、`bootstrapServersExpression`）；配合 `ContainerTestUtils.waitForAssignment(container, partitions)` 等容器分配就绪再发消息，消除单测竞态。主模块 `mock/` 包的 `MockConsumerFactory`/`MockProducerFactory`（kafka-clients 自带 MockConsumer/MockProducer）则完全不起 broker，用于极端快速单测。

## 8.8 共享组（4.1）与 AOT

- **ShareGroup/KIP-932**（4.1 起）：`core/DefaultShareConsumerFactory.java`、`listener/AbstractShareKafkaMessageListenerContainer.java`、`ContainerProperties.ShareAckMode`（`ContainerProperties.java:127`：`implicit`/`explicit`）、`@KafkaListener.ackMode` 注解属性（`:357`）。共享组没有"位移提交"概念，取而代之的是 broker 侧逐条 ACK（ACCEPT/RELEASE/REJECT），适配器与容器沿消息确认语义改造——**这是容器体系第一次出现两条平行的消费协议**。
- **AOT**：`aot/KafkaRuntimeHints.java`（反射/代理提示）与 `KafkaAvroBeanRegistrationAotProcessor.java`；配合 Boot 4 的原生镜像路线。

## 8.9 本章小结

扩展件各管一段：**KafkaAdmin 管拓扑，序列化三件套管字节↔对象与毒丸，转换器管 Spring 消息词汇，ReplyingKafkaTemplate 管 RPC 式往返，StreamsBuilderFactoryBean 管流任务生命周期，观测两路线管看得到，测试三件套管跑得快**。它们都建立在前六章的骨架上，没有一件需要改内核。

---

# 九、与 Spring Cloud Stream 的问题域对比（重点 F）

> 对比基准：《Spring Cloud Stream.md》（基于 spring-cloud-stream 5.0.3 源码实证）。下文凡引用 Stream 侧行为均以该文档的源码证据为据，避免凭印象比较。

## 9.1 一图看清两者的位置

```
                     业务代码
                        │
        ┌───────────────┴───────────────┐
        │（路线一）                      │（路线二）
   spring-kafka                    Spring Cloud Stream
   （直接面向 Kafka）              core：函数装配 + Binder SPI + 错误基础设施
        │                          binders/kafka：KafkaMessageChannelBinder
        │                                │  ←—— binder 内部用的就是左边的东西
        └─────────────→ 实证：《Spring Cloud Stream.md》9.2：binder 生产端
                          "KafkaTemplate<byte[],byte[]> kafkaTemplate = new KafkaTemplate<>(producerFB)"
                          9.3：binder 消费端
                          "new ConcurrentMessageListenerContainer<>(consumerFactory, containerProperties)"
        │
   kafka-clients（真正的 Kafka 协议实现）
```

**结构结论：两者不是竞争关系，而是不同层次的叠加**。Stream 的 Kafka binder 复用 spring-kafka 的对象做脏活（模板发送、监听容器、位移管理），自己在上面叠加"目的地抽象、函数编程模型、跨中间件统一语义、绑定运维"。spring-kafka 则是终点——它下面就是 kafka-clients。

## 9.2 问题域划分：各自"管"什么

**spring-kafka 管的是"用对 Kafka"**：Kafka 的每个语义（分区、位移、消费者组、事务、Streams、共享组）都被无损暴露，业务代码 import 的是 `org.springframework.kafka.*`，换中间件 = 重写消费/生产代码。

**Spring Cloud Stream 管的是"不被中间件绑死"**：业务代码只 import `java.util.function.*` 与 `Message`，Kafka/Rabbit/Pulsar 的差异被 Binder SPI 收进子上下文；目的地、消费组、分区、重试、死信、contentType 被统一成一份跨中间件属性词汇表（`spring.cloud.stream.bindings.<b>.destination/group/...`）。代价：**Kafka 特有能力必须经由 binder 扩展属性间接透传**（如 `spring.cloud.stream.kafka.binder.transaction.transaction-id-prefix` 开事务，《Spring Cloud Stream.md》9.2；DLQ 只暴露 `enableDlq` 一个开关，死信名固定为 `error.<topic>.<group>`，《Spring Cloud Stream.md》9.4），并且 binder 没暴露的能力就没有。

逐项对比：

| 维度 | spring-kafka | Spring Cloud Stream（Kafka binder） |
|---|---|---|
| 一句话定位 | Kafka 客户端的 Spring 化封装 | 函数驱动的消息中间件门面 |
| 抽象单位 | `ProducerRecord` / `ConsumerRecord` | `destination` + `Supplier/Function/Consumer` Bean |
| 中间件耦合 | 业务代码直接依赖 `org.springframework.kafka.*` | 业务代码零中间件 import；依赖藏在 binder 子上下文 |
| 换中间件成本 | 重写收发代码 | 理论上换依赖 + 改属性（实践受 binder 能力对齐度约束） |
| 编程模型 | `KafkaTemplate` + `@KafkaListener` 方法 | 函数 Bean + 命名约定绑定（`<fn>-in-0`），命令式发送用 `StreamBridge` |
| 分区控制 | 满配：手工定位、`@PartitionOffset`、按 key 分区、分区级暂停 | 有：`partitionKeyExpression` + PartitionHandler（Stream.md 8.1）；精细度低于原生 |
| 位移/Ack | 七种 AckMode + MANUAL 精细控制（5.6） | binder 扩展 `ackMode` 属性透传给容器（Stream.md 9.3 行 1225） |
| 消费组语义 | 原生 group 协议 / 4.1 ShareGroup | group 属性 + 分区实例索引（`instanceCount/instanceIndex` 手工分配，Stream.md 9.3） |
| 事务 | 一等公民：`KafkaTransactionManager`、容器事务、`sendOffsetsToTransaction`（第七章） | 配置项开启：binder 事务前缀属性，所有生产者共用事务管理器的 ProducerFactory（Stream.md 9.2） |
| 重试/死信 | `DefaultErrorHandler`（阻塞）+ `@RetryableTopic` 主题树（非阻塞，6.5） | 统一模型：RetryTemplate + 错误通道 + `enableDlq`（Stream.md 第七章）；无主题树式非阻塞重试 |
| 消息转换 | KafkaHeaders 词汇表 + Json/类型头（8.2/8.3） | contentType 属性 + CompositeMessageConverter（Stream.md 第六章），跨中间件一致 |
| 流处理 | Kafka Streams 一等支持（8.5） | 仅 kafka-streams binder 子模块提供 KStream 函数绑定 |
| 请求-应答 | `ReplyingKafkaTemplate`（8.4） | 无对等物 |
| 多集群/多中间件共存 | 手工建多套工厂 Bean | 一等公民：多 binder + per-binding 指定（Stream.md 4.5） |
| 运维面 | 容器 Bean 手工管理（pause/resume、事件） | Actuator：`BindingsEndpoint`/`BindingsLifecycleController` 可查/停/启/暂停绑定（Stream.md 8.5） |
| 测试 | `@EmbeddedKafka` 真 broker | `TestChannelBinder` 内存假 binder（Stream.md 8.7）；也可用 `@EmbeddedKafka` |
| 适用真相 | 单中间件深耕、要 Kafka 全部能力 | 多中间件/可移植/统一治理优先，接受能力面收窄 |

**问题域边界一句话**：spring-kafka 的边界是 **Kafka 这个中间件的全部**；Stream 的边界是 **所有 binder 的最大公约数**。你要的深度超出公约数越多，Stream 的"换中间件自由"就越名不副实；你从不换中间件，Stream 就只剩一层间接开销。

## 9.3 同一需求的两种写法

需求：监听 `orders` 主题，JSON 反序列化为 `Order`，失败重试三次（间隔 1s），仍失败进死信。

**spring-kafka 写法**（第六章机制）：

```java
@RetryableTopic(attempts = 4, backOff = @BackOff(delay = 1000))   // 非阻塞：orders-retry-1000、orders-dlt
@KafkaListener(topics = "orders")
public void onOrder(Order order) { process(order); }

@DltHandler
public void dlt(Order order, @Header(KafkaHeaders.DLT_EXCEPTION_FQCN) String ex) { saveToDb(order, ex); }
```

```yaml
spring:
  kafka:
    consumer:
      value-deserializer: org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
      properties:
        spring.deserializer.value.delegate.class: org.springframework.kafka.support.serializer.JsonDeserializer
        spring.json.trusted.packages: "*"
```

**Spring Cloud Stream 写法**（Stream.md 第七/九章机制）：

```java
@Bean
public Consumer<Order> onOrder() { return order -> process(order); }   // 绑定 orders-onOrder-in-0
```

```yaml
spring:
  cloud:
    function.definition: onOrder
    stream:
      bindings.onOrder-in-0.destination: orders
      bindings.onOrder-in-0.group: order-group
      bindings.onOrder-in-0.consumer.max-attempts: 4        # RetryTemplate（阻塞式，同进程内退避）
      bindings.onOrder-in-0.consumer.back-off-initial-interval: 1000
      kafka.bindings.onOrder-in-0.consumer.enableDlq: true   # 失败 → error.orders.order-group
      kafka.bindings.onOrder-in-0.consumer.dlq-destination-name: orders-dlt   # 可自定义名
```

两种写法差异背后的机制差异：spring-kafka 版是**主题树**（重试记录真实转发出网，消费者组各消费各的，主流量不停）；Stream 版是**进程内 RetryTemplate + 错误通道**（重试期间分区停摆，重试耗尽才进 DLQ）。要 Stream 版也有阻塞重试耗尽后的死信，要 spring-kafka 版的进程内阻塞重试，两者其实是对称的——**但 spring-kafka 多出的那半个能力（非阻塞主题树）Stream 没有**，而 Stream 多出的能力（换中间件、动态目的地、绑定端点）spring-kafka 也没有。

## 9.4 选型决策

```
是否只用一种中间件，且可预见不会换？
├─ 是 → 中间件是 Kafka 且需要深度能力（事务/Streams/分区精控/共享组/请求应答）？
│        ├─ 是 → spring-kafka 直接用（本文）
│        └─ 否 → 两者皆可，spring-kafka 略薄更直接
└─ 否（多中间件/事件驱动微服务群/需要统一治理）→ Spring Cloud Stream
         └─ Kafka 场景仍受 9.2 能力面约束；深度需求靠 binder 扩展属性透传或自定义 binder
```

三条补充判断：

1. **团队规模与技能**：Stream 的统一属性词汇表对"多个团队、多种中间件"是治理工具；单人单中间件场景它是纯间接层。
2. **运维形态**：需要把"暂停/恢复/重启某条消息流"交给运维平台（Actuator 端点驱动），Stream 现成；spring-kafka 要自建（容器 Bean 暴露成管理接口）。
3. **云原生/AOT**：两者都有 AOT 路线（spring-kafka 的 RuntimeHints；Stream 的 binder 子上下文预生成），差异不大。

## 9.5 混用与融合点

- **可以共存**：一个应用里既有 `@KafkaListener` 又有 Stream 绑定是合法的（两者装配互不依赖）；Stream 的 binder 里甚至允许注入你自己的 `KafkaTemplate` 发"旁路"消息。
- **不要叠加处理同一条消息流**：同一段消费链路同时经过 Stream 绑定与手工容器（比如给同一 topic 建了 `@KafkaListener` 又有同 group 的函数绑定）会出现消费组抢分区、重试语义叠加、死信归属混乱——**一条消息流选定一层框架，从头到尾**。
- **融合事实**：Stream 5.x 的 Kafka binder 依赖的就是 spring-kafka 4.x（同代基线，Stream.md 1.6 节）；Stream 的错误通道与 spring-kafka 的 `CommonErrorHandler` 在 binder 内部已经打通（binder 会组装容器并替换错误处理器）——读 binder 源码时随时会回到本文第三~六章的类。

## 9.6 本章小结

spring-kafka 与 Spring Cloud Stream 回答的是两个不同的问题：**"Kafka 怎么用对"与"中间件怎么解耦"**。前者是深度工具，后者是广度门面；前者以业务代码绑定 Kafka 为代价换取全部原生能力，后者以能力面收窄为代价换取可移植与统一治理。结构上 Stream 的 Kafka binder 建立在 spring-kafka 之上——所以无论选哪条路线，读懂本文都是在为 Stream 的一切行为溯源。

---

# 十、贯通视图：一条消息的一生

```
【生产】template.send("orders", order)
  └ observeSend（Observation 起点收）
    └ doSend：getTheProducer
        ├ 非事务 → 工厂单例 Producer（复用，close() 只是空操作）
        └ 事务   → 线程绑定 / 事务池 Producer（beginTransaction 已由事务管理器完成）
    └ producer.send(record, callback) ──batcher/linger──▶ broker
        └ callback：future.complete(SendResult) + producerListener + Observation 收
                     + closeProducer（非事务：逻辑归还）

【消费】orders 分区 P3 被 demo 容器（子容器 1）分配
  └ Registry.start（SmartLifecycle）→ ConcurrentMessageListenerContainer.doStart
      → 子容器1.run()：while(running) pollAndInvoke()
          ├ doProcessCommits（上轮 AckMode=BATCH 的提交）
          ├ pause 落地 / seeks 落地
          ├ consumer.poll → ConsumerRecords
          └ invokeIfHaveRecords → doInvokeRecordListener
              ├ ConsumerRecord → MessagingMessageConverter.toMessage（kafka_received* 头）
              ├ ErrorHandlingDeserializer 毒丸 → 直接抛 DeserializationException
              ├ Observation 消费 span 起止
              └ invokeHandler → InvocableHandlerMethod 反射调用业务方法（@Payload/@Header）

【失败】process(order) 抛 RuntimeException
  └（无 @RetryableTopic）DefaultErrorHandler.handleRemaining
      └ SeekUtils.seekOrRecover：FailureTracker 记 1 次 → seek(P3, 失败 offset)（0 间隔）
          └ 9 次内：下轮 poll 原消息重现；第 10 次 → recoverer
              └ DeadLetterPublishingRecoverer.accept
                  └ destinationResolver → orders-dlt
                  └ 头增强（kafka_dlt-exception-fqcn / dlt-original-*）
                  └（模板开事务则 executeInTransaction 包裹）send → 死信落网
  └（有 @RetryableTopic）DeadLetterPublishingRecovererFactory 转发 orders-retry-1000
      └ 写 kafka_backoffTimestamp 头 → 主容器提交、分区继续
      └ retry 容器：KafkaBackoffAwareMessageListenerAdapter 发现未到期
          → KafkaBackoffException → KafkaConsumerBackoffManager pause(P3)
          → ListenerContainerPartitionIdleEvent 到期 → resume → 业务方法
          → 仍失败 → retry-2000 → retry-4000 → orders-dlt → @DltHandler

【事务版】（containerProperties.transactionManager = KafkaTransactionManager）
  └ invokeRecordListenerInTx：TransactionTemplate.execute
      ├ getTxProducer（事务池）
      ├ 业务方法内 kafkaTemplate.send → 复用同一 Producer（未提交）
      ├ 容器 sendOffsetsToTransaction(P3, offset+1, groupMetadata)
      └ commitTransaction —— 消息与位移一起原子生效（downstream read_committed）
      ├ 异常 → abortTransaction → AfterRollbackProcessor seek 重投

【谢幕】应用关闭
  └ Registry.stop → 子容器 stop → poll 竞态保护丢弃残留 → consumer.close
      → ConsumerStoppedEvent；ProducerFactory.destroy 物理关闭所有缓存 Producer
```

---

# 附录

## A.1 关键类速查表

| 层 | 名称 | 一句话职责 | 所在 |
|---|---|---|---|
| 生产 | `KafkaOperations`/`KafkaTemplate` | send/execute/事务/receive 能力面与实现 | core |
| 生产 | `ProducerFactory`/`DefaultKafkaProducerFactory` | Producer 单例/线程绑定/事务池三模式 | core |
| 生产 | `CloseSafeProducer` | close 语义拦截：归还缓存 vs 物理关闭 | core（内部类） |
| 注册 | `KafkaListenerAnnotationBeanPostProcessor` | 扫描 @KafkaListener 造端点 | annotation |
| 注册 | `KafkaListenerEndpointRegistrar`/`Registry` | 端点登记与容器注册表（SmartLifecycle 总闸） | config |
| 注册 | `AbstractKafkaListenerContainerFactory`/`ConcurrentKafkaListenerContainerFactory` | 端点长成容器 | config |
| 消费 | `ConcurrentMessageListenerContainer` | 外壳：concurrency 拆子容器 | listener |
| 消费 | `KafkaMessageListenerContainer` | 内核：poll 循环/提交/seek/暂停 | listener |
| 消费 | `ContainerProperties` | AckMode 七策略等容器行为属性 | listener |
| 消费 | `MessagingMessageListenerAdapter` | ConsumerRecord → Spring 消息 → 反射调用 | listener/adapter |
| 错误 | `CommonErrorHandler`/`DefaultErrorHandler` | 错误处理能力面与默认实现 | listener |
| 错误 | `SeekUtils` | seekOrRecover：阻塞重试引擎 | listener |
| 错误 | `DeadLetterPublishingRecoverer` | 死信发布（头增强 + 可选事务包裹） | listener |
| 重试 | `RetryTopicConfigurer`/`DestinationTopic` | 非阻塞重试主题树的装配与导航 | retrytopic |
| 事务 | `KafkaTransactionManager` | Kafka 原生事务接入 Spring 事务骨架 | transaction |
| 扩展 | `KafkaAdmin`/`NewTopic` | 声明式建/改题 + clusterId | core |
| 扩展 | `JsonSerializer`/`JsonDeserializer`/`ErrorHandlingDeserializer` | JSON 类型头与毒丸隔离 | support/serializer |
| 扩展 | `MessagingMessageConverter`/`KafkaHeaders` | 消息转换与头词汇表 | support/converter, support |
| 扩展 | `ReplyingKafkaTemplate` | 请求-应答（correlationId + 超时） | requestreply |
| 扩展 | `StreamsBuilderFactoryBean` | KafkaStreams 的 Bean 生命周期 | config |
| 观测 | `KafkaTemplateObservation`/`KafkaListenerObservation` | 收发两侧 Observation 定义 | support/micrometer |
| 测试 | `EmbeddedKafkaBroker`/`@EmbeddedKafka` | KRAFT 内嵌集群 | spring-kafka-test |

## A.2 初学者学习路线（动手向）

1. 用 Boot 起 demo：`spring-kafka` starter + `@KafkaListener` + `KafkaTemplate`，先跑通 BATCH AckMode；
2. 改 AckMode 为 MANUAL_IMMEDIATE，故意抛异常，观察默认 10 次重试与 seek 日志；
3. 配 `DeadLetterPublishingRecoverer`，看死信 topic 里的 `kafka_dlt-*` 头；
4. 加 `@RetryableTopic`，用 kafka-console-consumer 观察 `-retry-1000/-dlt` 主题树与 `kafka_backoffTimestamp` 头；
5. 开容器事务（`spring.kafka.listener.transaction-manager` 或手工配 `KafkaTransactionManager`），下游用 `isolation.level=read_committed` 验证原子性；
6. 压测对比：阻塞重试 vs `@RetryableTopic` 期间主分区吞吐；
7. 源码精读：按 A.3 清单，重点把第四章注册链与第五章 pollAndInvoke 串成自己的时序图。

## A.3 源码阅读入口清单（20 个关键文件）

相对路径以 `spring-kafka/src/main/java/org/springframework/kafka/` 为基：

1. `core/KafkaTemplate.java` — 生产端全链路（doSend/buildCallback/getTheProducer）
2. `core/DefaultKafkaProducerFactory.java` — Producer 三模式与 CloseSafeProducer
3. `core/KafkaAdmin.java` — 声明式建题与 clusterId
4. `annotation/KafkaListenerAnnotationBeanPostProcessor.java` — 注解扫描与属性解析
5. `annotation/KafkaListener.java` / `annotation/EnableKafka.java` — 注解契约
6. `config/KafkaListenerEndpointRegistrar.java` / `config/KafkaListenerEndpointRegistry.java` — 登记与总闸
7. `config/AbstractKafkaListenerContainerFactory.java` — 造容器四步
8. `listener/ConcurrentMessageListenerContainer.java` — 并发外壳
9. `listener/KafkaMessageListenerContainer.java` — **全文核心**（run/pollAndInvoke/invoke*/processCommits/processSeeks）
10. `listener/ContainerProperties.java` — AckMode 与容器属性
11. `listener/SeekUtils.java` — 阻塞重试引擎
12. `listener/DefaultErrorHandler.java` — 默认错误处理
13. `listener/DeadLetterPublishingRecoverer.java` — 死信发布
14. `retrytopic/RetryTopicConfigurer.java` — 非阻塞重试装配
15. `transaction/KafkaTransactionManager.java` — 事务接入
16. `listener/adapter/MessagingMessageListenerAdapter.java` — 方法适配
17. `support/converter/MessagingMessageConverter.java` + `support/KafkaHeaders.java` — 转换与词汇表
18. `support/serializer/JsonDeserializer.java` / `ErrorHandlingDeserializer.java` — JSON 与毒丸
19. `requestreply/ReplyingKafkaTemplate.java` — 请求应答
20. `config/StreamsBuilderFactoryBean.java` — Streams 托管

## 结语

回到 1.2 节的四句话：**客户端对象贵如线程**——三种工厂模式把 Kafka 客户端的生命周期做成了产品；**poll 循环是唯一的消费原语**——容器把最容易写错的一段代码固化成七种 AckMode 与三道异常闸门；**注解是声明，工厂才是语义**——`@KafkaListener` 的每个属性都能在第四章的链路上找到落点；**框架不发明语义，只做接线**——事务、消息、观测全部接入 Spring 既有抽象，Kafka 的深度能力无损保留。读完本文，希望你再看任何 spring-kafka 应用时，看到的不再是黑盒注解，而是一条确定的链：*一次 send 的三态分派（第三章）、一个容器的五步注册（第四章）、一个 poll 的八拍循环（第五章）、两级重试与一座死信（第六章）、一个原子闭环（第七章）*——以及它与 Spring Cloud Stream 之间清晰的分层：**Stream 是门面，spring-kafka 是地基**（第九章）。每一环都有类名、方法名与行号可循。

与同系列文档互为印证之处：`KafkaTransactionManager` 的骨架是《Spring Framework.md》6.5 节 `AbstractPlatformTransactionManager` 机制在 Kafka 上的复用；监听容器的启停时序是《Spring Framework.md》4.6.2 节 SmartLifecycle phase 的直接应用；`MessagingMessageConverter`/`InvocableHandlerMethod` 建立在《Spring Framework.md》spring-messaging 体系与类型转换双体系之上；而 Spring Cloud Stream 一侧的对应关系，见《Spring Cloud Stream.md》结语。



