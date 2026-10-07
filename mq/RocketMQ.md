# RocketMQ 深度源码解析（写给初学者的架构全景）

> **本文基于的源码**：`D:\code\3rd\rocketmq`，apache/rocketmq master 分支快照，**Git commit `37af1437`（2026-10-03）**，根 `pom.xml:91` 声明 `<revision>5.5.1</revision>`，`MQVersion.CURRENT_VERSION = Version.V5_5_1.ordinal()`（`common/src/main/java/org/apache/rocketmq/common/MQVersion.java:21`）。文中所有【源码证据】的文件路径与行号均为对该快照实际读取所得。
>
> **版本取舍说明**：RocketMQ 的核心骨架——NameServer 路由、Remoting 协议、CommitLog 顺序写 + ConsumeQueue 定长索引、Push 消费者"长轮询拉取"、重试/死信/事务消息——自 4.x 以来高度稳定，本文第二~八章的内容对使用 4.9.x 与 5.x 的读者同样适用。5.x 新增的 Proxy/gRPC、Controller 自动切主、时间轮定时消息、分层存储等单独放在第九章。行号只对这份快照精确，读者按"类名 + 方法名"在 IDEA 中定位即可。
>
> **阅读约定**：与《Spring Framework 深度源码解析》一致——每章"先白话、后源码"，【源码证据】格式为 `模块/src/main/java/.../类名.java:行号` + 代码片段。写作过程中所有关键结论均经过对源码的逐行核验；凡与网上常见旧资料（多基于 4.x）不一致的地方，文中会以"⚠️ 与旧资料不符"显式标注，并以这份快照的代码为准。

## 如何读这份文档

如果你是 RocketMQ 初学者，推荐两遍读法：

- **第一遍（建立地图，1~2 小时）**：只读第一章（总览）每节的白话段、各章"本章小结"、第八章（可靠性专题）和第十章（贯通视图）。目标是能回答：一条消息从 `producer.send()` 到被消费经历了哪几步？Broker 宕机/断电时消息会丢在哪里？"不丢"与"不重"分别靠什么保证？
- **第二遍（深入源码）**：对照每章【源码证据】逐行读。顺序建议：第六章（Producer，最贴近日常编码）→ 第七章（Consumer）→ 第八章（把前两章的机制串成可靠性方案）→ 第四章（存储内核）→ 第二章（NameServer）→ 第三章（Remoting，随用随查）。

第八章是本文的"落点"：**Client 端必须怎样配置、怎样编码，才能与 Broker 机制配合做到消息不丢、业务不重复处理**——如果只看一章，看它。

---

# 一、总览：RocketMQ 的定位、设计哲学与整体架构

## 1.1 一句话定位

**RocketMQ 是一个以"顺序写单个 CommitLog"为存储内核、以"无状态 NameServer + 长轮询客户端"为接入层、承诺 at-least-once 投递的分布式消息中间件**：它把"谁在哪、消息在哪、消费到哪"拆给三个角色——NameServer 只记路由（谁在）、Broker 只管存储（消息在哪 + 消费到哪）、客户端自拉自推（自取自消费）。官方对自身能力的自述（`docs/cn/features.md:31`）："至少一次(At least Once)指每个消息必须投递一次。Consumer先Pull消息到本地，消费完成后，才向服务器返回ack，如果没有消费一定不会ack消息，所以RocketMQ可以很好的支持此特性。"

它解决的不是"发个消息"这一个动作，而是**异步架构的三类组织问题**：生产与消费速率不匹配（削峰填谷）、系统间强耦合（解耦）、主流程等待次要流程（异步化）；再往前一步，是跨系统的数据最终一致（事务消息，第六章 6.7 节）。

## 1.2 设计哲学：读源码前先记住五句话

1. **路由中心轻到极致，一致性让位给最终一致**。NameServer 的路由就是五张内存 Map（`RouteInfoManager.java:72-77`），不持久化、节点之间不通信；Broker 每 30 秒"心跳即注册"，客户端每 30 秒拉路由，靠周期性重传达成最终一致（第二章）。对比 Kafka 依赖 ZooKeeper/KRaft 元数据仲裁、Pulsar 依赖 ZooKeeper+BookKeeper，RocketMQ 把元数据的一致性等级降到了"够用就好"，换来的是运维上"可以随手多部署几个 NameServer"。
2. **所有消息混写一条时间线**。CommitLog 是唯一的事实来源，ConsumeQueue/IndexFile/事务 op 队列全部由单线程 ReputMessageService 异步"回放"生成（第四章 4.4 节）。顺序写带来磁盘吞吐上限，代价是"先落盘、后可见"约 1ms 的分发延迟。
3. **Push 是伪装的长轮询**。RocketMQ 没有 push 协议：客户端 PullMessageService 拉空后，Broker 把请求挂住（PullRequestHoldService），新消息落盘瞬间唤醒（第七章 7.1 节）。既拿到"准实时"体验，又保留拉模式的背压与进度控制。
4. **可靠性是一份分层合同**：Producer 只保证"发出去的消息有回执"，Broker 只保证"回执过的地方能扛住断电与主备切换"（要靠刷盘/复制参数加码），Consumer 只保证"处理完才推进 offset"。三段合同拼起来才是端到端不丢，任何一段偷懒都会漏水——这正是第八章逐段展开的内容。
5. **每个环节都留了口子**：发送有 `SendMessageHook`/`CheckForbiddenHook`，消费有 `MessageListener` 两种语义 + `ConsumeReturnType` 五态，过滤有 Tag/SQL92/Filter Server，存储有 `MessageStorePlugin`（tieredstore 即基于此），HA 有 SPI（`ServiceProvider.loadClass(HAService.class)`，第五章 5.1 节）。框架自己也是这些口子的首位用户。

## 1.3 模块分层全景

RocketMQ 是多模块 Maven 工程，根 `pom.xml` 实测共 19 个模块。按"用户感知"分层如下：

```
┌─────────────────────────── 接入层（5.x 新架构，第九章）────────────────────────────┐
│  proxy（gRPC 数据面 + LOCAL/CLUSTER 两种模式）   rocketmq-proto（v2 协议生成类）     │
│  auth（5.x 认证授权新框架）                      rocketmq-apis（proto 子模块）       │
├─────────────────────────── 客户端层 ──────────────────────────────────────────────┤
│  client（remoting 协议 Java 客户端：Producer/Consumer 全部实现、Rebalance、Offset）  │
│  example（官方示例：quickstart/simple/transaction/filter...）                      │
├─────────────────────────── 服务端层 ──────────────────────────────────────────────┤
│  namesrv（NameServer：路由五张表 + 心跳扫描）                                       │
│  broker（Broker：SendMessageProcessor/PullMessageProcessor/长轮询/事务回查/         │
│          schedule（延迟消息）/subscription/topic 管理全部在这）                     │
│  store（存储内核：CommitLog/ConsumeQueue/IndexFile/刷盘/HA/Timer 时间轮）           │
│  container（单进程多 Broker 托管）    controller（Controller 自动切主仲裁）          │
│  tieredstore（冷热分层存储插件）                                                   │
├─────────────────────────── 通信与地基 ────────────────────────────────────────────┤
│  remoting（Netty 通信框架、RemotingCommand 协议、RequestCode/ResponseCode、路由对象）│
│  common（Message/BrokerConfig/TopicValidator/MixAll 常量/异常体系）                 │
│  srvutil（CommandLine 工具）  tools（mqadmin/控制台后端）  filter（Filter Server）   │
│  distribution（部署脚本/配置模板）  openmessaging（OMS 兼容层）  test（集成测试）      │
└───────────────────────────────────────────────────────────────────────────────────┘
```

一个初学者常有的误解是"逻辑都在 broker 模块里"——实际上**消费进度的协商、重试队列的投递决策、长轮询的挂起逻辑都在 broker 模块，而拉取的调度、流控、offset 的本地缓存全部在 client 模块**。两边的代码要对照着读，这也是本文第六、七、八章的读法。

## 1.4 模块依赖图（以各模块 pom 的依赖实证）

```
                    common（一切的地基：Message/Config/常量）
                   ▲    ▲    ▲    ▲    ▲
     ┌─────────────┘    │    │    │    └──────────────┐
 remoting ◄── srvutil  │    │    │                filter
   ▲   ▲               │    │    │
   │   └── client（依赖 common + remoting + [tls/auth-migration]）
   │                    │    │    └── namesrv（依赖 remoting + common）
   ├── store（依赖 common + remoting）        （store 不依赖 client！）
   │         ▲
   │         └── broker（依赖 store + client + namesrv + remoting + common + ...）
   │                    ▲              ▲
   │     controller（依赖 broker + store） container（依赖 broker）
   │                                    │
   └── proxy（依赖 rocketmq-proto + broker + container + client + auth）
```

值得注意的两点（均已用 pom 验证）：

- **store 模块不依赖 client/broker**——存储内核是纯粹的字节问题，broker 才是"把 RPC 翻译成存储调用"的胶水层。想读懂存储，只需要第四、五章；
- **client 依赖了 remoting 而不是再抽一层**——Java 客户端与 Broker 用同一套 RemotingCommand 协议与 Netty 框架，第三章讲一次、客户端和服务端两处受用。

## 1.5 关键问题 → RocketMQ 方案映射（全文导览）

| 企业开发的关键问题 | RocketMQ 的方案 | 详见 |
|---|---|---|
| 客户端怎么知道消息在哪个 Broker | NameServer 五张内存表 + Broker 30s 心跳注册 + 客户端 30s 拉路由 | 第二章 |
| 网络协议与并发调用怎么管 | RemotingCommand（opaque 匹配请求响应）+ Netty + 公平信号量三模式 | 第三章 |
| 海量消息怎么存得快 | 单 CommitLog 顺序写 + mmap + 可选堆外写缓冲 | 第四章 |
| 按队列消费怎么定位消息 | ConsumeQueue 定长 20 字节索引 + IndexFile 哈希索引（异步回放生成） | 第四章 |
| 断电/宕机消息会不会丢 | 同步刷盘 GroupCommitService（SYNC_FLUSH）/ 异步刷盘 + 复制 | 第四章、第五章 |
| 主机挂了服务怎么不中断 | 主从（ASYNC/SYNC_MASTER）→ DLedger（已弃用）→ Controller 模式（Epoch+SyncStateSet） | 第五章 |
| 消息发出去对方一定收到吗 | 同步重试/异步递归重试/故障规避 + Broker 四态回执（SEND_OK 与三个"也算成功"的状态） | 第六章、第八章 |
| 本地事务和发消息怎么一致 | 半消息（RMQ_SYS_TRANS_HALF_TOPIC）+ op 队列 + 定时回查 | 第六章 6.7 |
| 多消费者怎么分摊队列 | Rebalance：排序 + AllocateMessageQueueStrategy（默认均分），20s 周期 + 变更即时触发 | 第七章 7.3 |
| 消费太慢会不会把客户端压垮 | 三级流控（条数/字节/Span）+ Broker 端 OS_PAGE_CACHE_BUSY 限流 | 第七章 7.4 |
| 处理失败怎么办 | RECONSUME_LATER → %RETRY% → 延迟级别退避 16 次 → %DLQ% 死信 | 第七章 7.8 |
| 消费到哪了、宕机后从哪继续 | OffsetStore：集群模式提交到 Broker（5s 定时），广播模式写本地文件 | 第七章 7.7 |
| **怎么做到消息不丢、处理不重** | 三段可靠性合同 + 客户端幂等（本文重点） | **第八章** |
| 任意时间的定时消息 | TimerMessageStore 时间轮（1s 精度、最长 3 天） | 第九章 9.3 |

## 1.6 版本演进：4.x → 5.x 关键变化对比

写作时（2026 年 10 月）的版本格局：4.9.x 仍在小版本维护；5.x 为活跃主线——**5.3.0（2024-07-16）→ 5.4.0（2025-12-24）→ 5.5.0（2026-04-10）→ 5.5.1（2026-08-20）**（GA 日期取自 ASF 董事会纪要与官方发布渠道交叉核对），本文分析的即是 5.5.1 之后的 master 快照。版本时间线：

| 版本 | 时间 | 一句话主题 |
|---|---|---|
| 开源捐赠 | 2012 开源（阿里），2016-11 入 Apache 孵化器，2017-02 成为顶级项目 | 从 MetaQ 到 RocketMQ |
| 4.0 GA | 2017-02 | Java 客户端标准化，架构定型：NameServer+Broker+Client |
| 4.3 | 2018-07 | **事务消息**（half 消息 + 回查） |
| 4.5 | 2019-04 | **DLedger**（Raft 多副本自动切换）进入主线 |
| 4.9 | 2021-12 | 4.x 末期，大量稳定性与批量化改进 |
| 5.0 GA | 2022-11 | 新架构元年：**Proxy + gRPC 多语言客户端、Controller 自动主从切换、时间轮定时消息、客户端类型化（Normal/FIFO/Delay/Transaction）** |
| 5.1 | 2023-04 | **分层存储（tieredstore）**，Pop 消费与重平衡优化 |
| 5.2 | 2024-02 | Controller/代理完善，auth 新框架落地 |
| 5.3 | 2024-07 | Controller 模式成熟、`clientRebalance` 双轨（本地分配/服务端分配） |
| 5.4 | 2025-12 | 存储与可观测性持续增强 |
| 5.5.1（本文基线） | 2026-08-20 | 当前稳定版；master 快照即在此之上 |

### 1.6.1 4.x → 5.x 的关键差异（源码实证）

| 变化点 | 4.x 时代说法 | 本文快照（5.5.1+）实证 |
|---|---|---|
| 接入协议 | 仅 Remoting 自研协议 | 新增 gRPC v2 单一 `MessagingService`（`proxy/grpc/v2/GrpcMessagingApplication.java:76`），多语言客户端在独立仓库 rocketmq-clients |
| 主从切换 | 手工切换或 DLedger | Controller 模式（`brokerConfig.enableControllerMode`，`AutoSwitchHAService` + Epoch/SyncStateSet）；`DLedgerCommitLog` 已 `@Deprecated`（`store/dledger/DLedgerCommitLog.java:59-65`） |
| 同步复制等待超时 | 旧教程常说"syncFlushTimeout 同时管刷盘和复制" | **刷盘等待用 `syncFlushTimeout=5000`，复制等待用 `slaveTimeout=3000`**（`MessageStoreConfig.java:257/260`，`CommitLog.java:2288 vs 1396`）——两套超时已彻底分开 |
| 延迟消息 | 仅 18 个固定延迟级别 | 保留级别制（broker/schedule 包）+ 新增时间轮任意定时（`store/timer/TimerMessageStore.java`，默认 `timerWheelEnable=true`） |
| 消费模式 | Push/Pull/LitePull | 新增 **Pop**（服务端分配 + invisible time + AckMessage/ChangeInvisibleDuration，code 200050/200051/200053） |
| 认证 | `PlainAccessValidator` ACL | 5.x 新 auth 框架（`org.apache.rocketmq.auth.*`），旧 ACL 仅剩迁移类 |
| 同步调用骨架 | `countDownLatch.await()` | `invokeImpl(...).thenApply(...).get(timeout)`（CompletableFuture，`NettyRemotingAbstract.java:583-594`） |
| 客户端状态映射 | — | `SendStatus` 四态不变（SEND_OK/FLUSH_DISK_TIMEOUT/FLUSH_SLAVE_TIMEOUT/SLAVE_NOT_AVAILABLE），但 Broker 端后三态 `sendOK=true`（第六章 6.5 节，第八章 8.2.2 展开其可靠性含义） |

> ⚠️ 网上大量资料（包括一些官方旧文档）描述的 `markResponseRPC()`、`HEADER_MAGIC_CODE`、`waitTimeMillsInQueue`、`firstCheckImmunityTime` 等在当前快照中已被更名、删除或重构。凡与本文行号对不上的旧描述，以本文快照为准。

## 1.7 全文章节地图

- **第二章 NameServer**：五张内存表、心跳即注册、过期剔除、路由查询与客户端增量更新——为什么"无状态"反而是可用性优势。
- **第三章 Remoting**：帧格式、RemotingCommand 字段、同步/异步/单向三模式与公平信号量、请求分发与 GO_AWAY 优雅驱逐。
- **第四章 存储内核**：CommitLog 顺序写与 mmap、三种刷盘服务、ConsumeQueue/IndexFile 定长索引、读路径与冷热分离、过期清理与磁盘水位。
- **第五章 高可用复制**：主从 push 复制、GroupTransferService 的 ack 等待、DLedger 弃用与 Controller 模式（Epoch + SyncStateSet + confirmOffset）。
- **第六章 Producer**：三模式发送语义、重试与超时预算、故障规避、Broker 端四态回执、BrokerFastFailure 流控、事务消息全链路。
- **第七章 Consumer**：长轮询三层唤醒、Rebalance 与队列分配、流控、并发/顺序两种消费服务、OffsetStore、重试与死信。
- **第八章 可靠性专题（重点）**：Client 与 RocketMQ 机制配合做到"不丢"与"不重"——丢点/重点的源码级定位 + 配置清单 + 幂等模板 + 事故案例 + 检查清单。
- **第九章 5.x 新架构**：Proxy/gRPC/SimpleConsumer、TopicMessageType、时间轮、分层存储、auth。
- **第十章 贯通视图与附录**：一条消息的一生、关键参数速查表、源码导航、学习路线。


---

# 二、NameServer：无状态路由中心

> 本章对应源码：`namesrv` 模块（根目录 `D:\code\3rd\rocketmq\namesrv`），以及 client/broker 模块中与路由交互的类。

## 2.1 先说白话：为什么不需要 ZooKeeper

RocketMQ 集群里每台 Broker 都知道自己的 topic/queue 配置，真正的问题是：**客户端（Producer/Consumer）怎么知道"这个 topic 的队列分布在哪几台 Broker 上"**。答案是一个极其轻量的注册中心：Broker 主动把配置报上来，客户端来查，仅此而已。

NameServer 刻意不做三件事：**节点间不通信**（不复制数据）、**路由不持久化**（重启即空）、**不做选主仲裁**（Controller 模式之前没有）。这三条"不做"换来的是：部署 N 个 NameServer 之间完全独立，挂掉任何一个不影响其余，客户端轮询使用。代价是路由数据存在最长约 30s（心跳周期）+ 若干秒（客户端拉取周期）的**最终一致窗口**——RocketMQ 判断：路由短暂过期顶多导致一次发送失败重试，不值得为它引入一个分布式协调系统的运维成本。

## 2.2 五张内存表 + 一把读写锁

【源码证据】`namesrv/src/main/java/org/apache/rocketmq/namesrv/routeinfo/RouteInfoManager.java:70-77`：

```java
private static final long DEFAULT_BROKER_CHANNEL_EXPIRED_TIME = 1000 * 60 * 2;
private final ReadWriteLock lock = new ReentrantReadWriteLock();
private final Map<String/* topic */, Map<String, QueueData>> topicQueueTable;          // topic → brokerName → QueueData
private final Map<String/* brokerName */, BrokerData> brokerAddrTable;                 // brokerName → {brokerId → addr}
private final Map<String/* clusterName */, Set<String/* brokerName */>> clusterAddrTable;
private final Map<BrokerAddrInfo/* brokerAddr */, BrokerLiveInfo> brokerLiveTable;     // 存活表
private final Map<BrokerAddrInfo/* brokerAddr */, List<String>/* Filter Server */> filterServerTable;
```

五张表的分工：`brokerLiveTable` 回答"谁活着"（`BrokerLiveInfo` 含 `lastUpdateTimestamp`、`DataVersion`、channel、`haServerAddr`，L1180-1185）；`brokerAddrTable` 回答"这个 broker 组有哪些节点"（brokerId 0=MASTER，`MixAll.MASTER_ID=0`，`common/.../MixAll.java:95`）；`topicQueueTable` 回答"topic 的队列分布在哪些 broker 上"（`QueueData` 四字段：`brokerName/readQueueNums/writeQueueNums/perm/topicSysFlag`，`remoting/.../route/QueueData.java:24-28`）；`clusterAddrTable` 与 `filterServerTable` 是辅助。全部是 ConcurrentHashMap，**没有任何持久化代码**（全文无 persist/save/load）——"无状态"不是宣传语，是代码事实。

## 2.3 心跳即注册：Broker 每 30 秒全量上报

Broker 端没有独立的"心跳包"：**REGISTER_BROKER 既是注册也是心跳**，每次都全量携带 `TopicConfigSerializeWrapper`（所有 topic 配置 + `DataVersion`）+ body CRC32 校验。

【源码证据】BrokerController 的注册任务（`broker/src/main/java/org/apache/rocketmq/broker/BrokerController.java:1981-2003`）：启动时立即注册一次，之后按 `registerNameServerPeriod` 周期执行，周期被钳制在 [10s, 60s]：

```java
scheduledFutures.add(this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {
    ...
        BrokerController.this.registerBrokerAll(true, false, brokerConfig.isForceRegister());
    ...
}, 1000 * 10, Math.max(10000, Math.min(brokerConfig.getRegisterNameServerPeriod(), 60000)), TimeUnit.MILLISECONDS));
```

周期默认值 30s（`common/.../BrokerConfig.java:188`：`private int registerNameServerPeriod = 1000 * 30;`）。`BrokerOuterAPI.registerBrokerAll`（`broker/out/client/BrokerOuterAPI.java:506-534`）用 CountDownLatch 对**每个 NameServer 并发**发送 `RequestCode.REGISTER_BROKER`（=103），body 编码后计算 `UtilAll.crc32` 放进 header，NameServer 侧 `DefaultRequestProcessor.java:225-229` 校验 CRC 失败直接拒收——防的是网络层损坏，不是安全。

NameServer 侧的注册逻辑（`RouteInfoManager.registerBroker`，L240-394）全程持有写锁，顺序为：更新 clusterAddrTable/brokerAddrTable → **同名同 brokerId 换地址时用 DataVersion 仲裁**（旧地址的 `stateVersion` 更新则拒绝本次注册，L281-289，防止"旧节点复活覆盖新节点"）→ 只有 master（或 prime slave）携带的 TopicConfig 才写入 topicQueueTable → 最后 `brokerLiveTable.put(..., new BrokerLiveInfo(System.currentTimeMillis(), ...))` 刷新存活时间。

【源码证据】`DataVersion` 三元组（`remoting/.../protocol/DataVersion.java:21-24`）：

```java
public class DataVersion extends RemotingSerializable {
    private long stateVersion = 0L;
    private long timestamp = System.currentTimeMillis();
    private AtomicLong counter = new AtomicLong(0);
```

Broker 每次修改 topic 配置调 `nextVersion()` 递增 counter；NameServer 用它做**增量注册判断**（没变就不重建 QueueData）与**乱序仲裁**。这是 RocketMQ 在"无协调系统"前提下抵抗路由脑裂的全部机制，简单但有效。

## 2.4 过期剔除：5 秒一扫，2 分钟未更新即判死

【源码证据】`RouteInfoManager.java:803-814`：

```java
public void scanNotActiveBroker() {
    try {
        log.info("start scanNotActiveBroker");
        for (Entry<BrokerAddrInfo, BrokerLiveInfo> next : this.brokerLiveTable.entrySet()) {
            long last = next.getValue().getLastUpdateTimestamp();
            long timeoutMillis = next.getValue().getHeartbeatTimeoutMillis();
            if ((last + timeoutMillis) < System.currentTimeMillis()) {
                RemotingHelper.closeChannel(next.getValue().getChannel());
                log.warn("The broker channel expired, {} {}ms", next.getKey(), timeoutMillis);
                this.onChannelDestroy(next.getKey());
            }
        }
```

扫描任务由 `NamesrvController.initialize()`（`NamesrvController.java:118-123`）注册，默认周期 5s（`common/.../namesrv/NamesrvConfig.java:55`：`scanNotActiveBrokerInterval = 5 * 1000`）。默认过期阈值 `DEFAULT_BROKER_CHANNEL_EXPIRED_TIME = 1000 * 60 * 2`（2 分钟，L70）；5.x 允许 broker 注册时自带 `heartbeatTimeoutMillis` 覆盖。过期即关 channel + `onChannelDestroy` 级联清理五张表（`BatchUnregistrationService` 批量注销，L836-840）。

**这解释了一个经典运维现象**：杀死一台 Broker 后，最长要等约 2 分钟 NameServer 才摘除它的路由，此间客户端仍可能向死 Broker 发送消息并失败——客户端的故障规避（第六章 6.4 节）和重试就是为这个窗口兜底的。

## 2.5 路由查询：GET_ROUTEINFO_BY_TOPIC

路由查询被注册在独立的 `ClientRequestProcessor`（`NamesrvController.java:206-216`，独立线程池）——高频的客户端路由查询不与 broker 注册/运维请求抢线程。查询实现：

【源码证据】`RouteInfoManager.java:700-734`（读锁内组装 `TopicRouteData`）：

```java
public TopicRouteData pickupTopicRouteData(final String topic) {
    TopicRouteData topicRouteData = new TopicRouteData();
    ...
    this.lock.readLock().lockInterruptibly();
    Map<String, QueueData> queueDataMap = this.topicQueueTable.get(topic);
    if (queueDataMap != null) {
        topicRouteData.setQueueDatas(new ArrayList<>(queueDataMap.values()));
        ...
        for (String brokerName : brokerNameSet) {
            BrokerData brokerData = this.brokerAddrTable.get(brokerName);
            ...
            BrokerData brokerDataClone = new BrokerData(brokerData);
            brokerDataList.add(brokerDataClone);
```

5.x 在此内置了 **acting master 补偿**（L763-790）：某 broker 组的 master 掉线且未启用 Controller 时，若 QueueData 不可写，就把最小 brokerId 的 slave 地址临时改挂到 `MASTER_ID` 返回给客户端——配合 `enableSlaveActingMaster`，让"slave 顶替 master 收消息"在路由层可行。对应的对称动作是 prime slave 注册时 NameServer 主动抹掉其写权限（`perm & ~PERM_WRITE`，L344-347）。

## 2.6 客户端路由发现：每 30 秒拉一次，变了才生效

【源码证据】`client/.../impl/factory/MQClientInstance.java:400-406`（定时任务）与 `ClientConfig.java:58`（`pollNameServerInterval = 1000 * 30`）：

```java
this.scheduledExecutorService.scheduleAtFixedRate(() -> {
    try {
        MQClientInstance.this.updateTopicRouteInfoFromNameServer();
    } catch (Throwable t) { ... }
}, 10, this.clientConfig.getPollNameServerInterval(), TimeUnit.MILLISECONDS);
```

`updateTopicRouteInfoFromNameServer` 的关键在"变化检测"（`MQClientInstance.java:951-958`）：新路由与本地缓存 `topicRouteTable` 做**排序后整体 equals** 比对（`TopicRouteData.topicRouteDataChanged`，`TopicRouteData.java:120-129`），不变就什么都不做；变了才走三步——更新本地 `brokerAddrTable` → 把路由转成发布视角 `TopicPublishInfo`（只取可写 perm 且必须有 MASTER_ID 的 broker，按 `writeQueueNums` 生成 MessageQueue 列表，`MQClientInstance.java:261-312`）推给 Producer → 转成订阅视角 MessageQueue 集合推给 Consumer。

NameServer 挂了会怎样？已缓存的路由继续可用，收发不受影响；新 topic 的路由拉不到、故障 Broker 的路由摘不掉——又回到 1.2 节那句话：**路由的暂时陈旧由客户端重试与故障规避兜底，而不是由注册中心的强一致保证**。

## 2.7 本章小结

| 问题 | 答案（源码锚点） |
|---|---|
| 路由存哪 | RouteInfoManager 五张内存 Map，无持久化（`RouteInfoManager.java:72-77`） |
| 谁来更新 | Broker 30s 一次 REGISTER_BROKER，心跳即注册（`BrokerController.java:1981-2003`） |
| 怎么判死 | 5s 扫描，2 分钟未更新剔除（`RouteInfoManager.java:803-814`） |
| 乱序注册怎么防 | DataVersion(stateVersion+counter) 仲裁（`DataVersion.java:21-24`） |
| 客户端怎么感知 | 每 30s 拉路由，排序比对后增量生效（`MQClientInstance.java:400-406, 951-998`） |
| NameServer 挂了会怎样 | 无影响（客户端用缓存路由）；长期挂掉影响新增 topic 与故障摘除 |

下一章进入承载数据的通道：Remoting 层。

---

# 三、Remoting 通信层：一套 Netty 框架，三种调用模式

> 本章对应源码：`remoting` 模块（根目录 `D:\code\3rd\rocketmq\remoting`）。

## 3.1 先说白话：一条 RPC 的三个问题

自研 RPC 框架要解决三件事：**字节怎么排**（协议）、**请求和响应怎么对上**（并发复用）、**调用方等到什么程度**（同步/异步/单向）。RocketMQ 的答案分别是：`RemotingCommand` 定长头 + 变长头 + body；`opaque` 自增序号匹配；三对公平信号量限流。整个框架由 `NettyRemotingAbstract` 一个抽象类承载核心逻辑，`NettyRemotingClient`/`NettyRemotingServer` 各自补齐 Netty 接线。

## 3.2 帧格式：4 + 4 + header + body

【源码证据】`remoting/.../protocol/RemotingCommand.java:387-410`（`encode()`）：

```java
public ByteBuffer encode() {
    // 1> header length size
    int length = 4;
    // 2> header data length
    byte[] headerData = this.headerEncode();
    length += headerData.length;
    // 3> body data length
    if (this.body != null) {
        length += body.length;
    }
    ByteBuffer result = ByteBuffer.allocate(4 + length);
    // length
    result.putInt(length);
    // header length
    result.putInt(markProtocolType(headerData.length, serializeTypeCurrentRPC));
```

帧结构为：

```
┌───────────────┬────────────────────────┬───────────────┬──────────┐
│ 4 字节总长度   │ 4 字节 headerLength     │ header 数据    │ body     │
│               │ = (序列化类型 << 24)     │ (定长字段+扩展  │ (消息体) │
│               │   | header 实际长度      │  字段 extFields)│         │
└───────────────┴────────────────────────┴───────────────┴──────────┘
```

注意第二段的"标志位"真相：`markProtocolType`（`RemotingCommand.java:248-250`）是 `(type.getCode() << 24) | (source & 0x00FFFFFF)`——**高 1 字节是序列化类型代号**（0=JSON，1=ROCKETMQ 二进制），低 3 字节才是 header 长度。⚠️ 旧资料常见的"HEADER_MAGIC_CODE 魔数 / FLAG_COMPRESSED 压缩标志"在当前快照中已不存在（全模块 grep 无命中）。header 本身支持两种序列化：ROCKETMQ 二进制（`RocketMQSerializable`，字段按序号紧凑排列）或 JSON（`RemotingSerializable`），按连接内单帧自适应解析（`getProtocolType`，L236-238）。发送侧 `NettyEncoder` 走 `fastEncodeHeader`（L458-477）：先占 8 字节再回填两个 int，避免 header 编码前的长度预计算。

解码侧 `NettyDecoder extends LengthFieldBasedFrameDecoder(16777216, 0, 4, 0, 4)`（`NettyDecoder.java:32-37`）——16MB 单帧上限，长度字段在帧首 4 字节、剥离 4 字节长度域。这个 16MB 上限也决定了单条消息物理上限为 4MB（`MessageStoreConfig.maxMessageSize`）。

## 3.3 RemotingCommand：opaque 是全部的秘密

【源码证据】`RemotingCommand.java:86-98`：

```java
    private int code;                    // 请求/响应码（RequestCode/ResponseCode）
    private LanguageCode language = LanguageCode.JAVA;
    private int version = 0;
    private int opaque = requestId.getAndIncrement();   // 自增请求 ID
    private int flag = 0;                // bit0=RPC_TYPE(响应位) bit1=RPC_ONEWAY
    private String remark;               // 错误说明
    private HashMap<String, String> extFields;   // 定制 header 序列化后的字段表
    ...
    private transient byte[] body;
```

- `opaque` 由静态 `AtomicInteger` 自增分配（L71、L89）。客户端发送前把它放进 `responseTable`（`ConcurrentHashMap<Integer, ResponseFuture>`，`NettyRemotingAbstract.java:91-92`）；服务端响应原样带回 opaque；客户端收到响应时 `processResponseCommand` 一次 `responseTable.remove(opaque)` 即完成请求-响应匹配（L468-483）。**单连接上成千上万并发请求的复用，靠的就是这一个自增 ID**。
- `flag` 的两个位：`markResponseType()` 置 bit0 表示响应（L252-255），`markOnewayRPC()` 置 bit1 表示单向（L512-515）。
- `code` 是协议的"方法名"：`SEND_MESSAGE=10`、`PULL_MESSAGE=11`、`UPDATE_CONSUMER_OFFSET=15`、`HEART_BEAT=34`、`CONSUMER_SEND_MSG_BACK=36`、`END_TRANSACTION=37`、`CHECK_TRANSACTION_STATE=39`、`NOTIFY_CONSUMER_IDS_CHANGED=40`、`LOCK_BATCH_MQ=41`、`REGISTER_BROKER=103`、`UNREGISTER_BROKER=104`、`GET_ROUTEINFO_BY_TOPIC=105`、`SEND_MESSAGE_V2=310`、`SEND_BATCH_MESSAGE=320`、Pop 家族 `POP_MESSAGE=200050 / ACK_MESSAGE=200051 / CHANGE_MESSAGE_INVISIBLETIME=200053`（`remoting/.../protocol/RequestCode.java:22-26, 52-65, 80-84, 107-110, 173, 199`）。完整表见第十章 10.2 节。

## 3.4 三种调用模式与公平信号量

`NettyRemotingAbstract` 构造器持有两把**公平信号量**（`NettyRemotingAbstract.java:147-150`：`new Semaphore(permits, true)`），默认值：客户端 async/oneway 各 **65535**（`NettySystemConfig.java:48-51`，可经系统属性覆盖），服务端 async=**64**、oneway=**256**（`NettyServerConfig.java:30-31`）。

**同步**（`invokeSyncImpl`，L583-594）：

```java
public RemotingCommand invokeSyncImpl(final Channel channel, final RemotingCommand request,
    final long timeoutMillis) throws ... {
    try {
        return invokeImpl(channel, request, timeoutMillis).thenApply(ResponseFuture::getResponseCommand)
            .get(timeoutMillis, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
        throw new RemotingTimeoutException(channel.remoteAddress().toString(), timeoutMillis, e.getCause());
    }
}
```

⚠️ 与旧资料不同：当前快照的同步等待已改为 **CompletableFuture.get(timeout)**，`CountDownLatch` 只在 `ResponseFuture.waitResponse` 的遗留路径保留（`ResponseFuture.java:37, 103-106`）。

**异步**（`invokeAsyncImpl`，L677-694）：信号量 `tryAcquire(timeoutMillis)` 拿到额度后构造 `ResponseFuture` 并放入 `responseTable`，写 channel 成功置 `sendRequestOK=true`，失败走 `requestFail`；响应到达时 `processResponseCommand` 触发 `InvokeCallback.operationSucceed`。配套两个兜底：① `SemaphoreReleaseOnlyOnce`（`remoting/.../common/SemaphoreReleaseOnlyOnce.java:30-36`）用 CAS 保证信号量**有且仅释放一次**——正常响应、超时扫描、发送失败三条路径竞争也不会重复 release；② `scanResponseTable`（L557-571）由 HashedWheelTimer 每 1 秒扫描，把 `beginTimestamp + timeoutMillis + 1000` 仍未返回的请求超时掉并释放信号量，防泄漏。

**单向**（`invokeOnewayImpl`，L727-744）：`markOnewayRPC` 后 `writeAndFlush` 完即释放信号量——**没有任何响应与重试**，写失败只打一行 warn。这是第八章"不丢消息"要重点排除的模式：oneway 语义上就是 at-most-once。

## 3.5 请求分发：processorTable、流控与 GO_AWAY

服务端收到请求后（`processRequestCommand`，L342-393）：

1. 按 `cmd.getCode()` 查 `processorTable`（`HashMap<Integer, Pair<NettyRequestProcessor, ExecutorService>>`，L104-105）——**每个 RequestCode 可绑定独立处理器 + 独立业务线程池**，Broker 正是靠这个把发送（sendMessageProcessor）、拉取（pullMessageProcessor）、客户端管理（clientManageProcessor）、消费管理（consumerManageProcessor）、运维（adminBrokerProcessor）物理隔离开，慢的运维查询拖不垮发送；
2. 处理器 `rejectRequest()` 返回 true（或业务线程池已满）→ 立即回 `SYSTEM_BUSY` / 拒绝，**不进队列**；
3. 否则包装成 `RequestTask` 提交业务线程池，当前 Netty 线程立即返回——Netty EventLoop 永远不做业务逻辑。

RPCHook（`remoting/RPCHook.java:22-27`，`doBeforeRequest/doAfterResponse`）在分发前后统一回调（L414、L432）——ACL 鉴权（`AclClientRPCHook` 在 client 模块 `org/apache/rocketmq/acl/common/AclClientRPCHook.java:27`）与 5.x 新 auth 都挂在这里。

【源码证据】优雅驱逐（L359-368）：Broker 关机时对新进请求直接回 `ResponseCode.GO_AWAY`，客户端 `invokeImpl` 收到 GO_AWAY 后通过 `ChannelWrapper.reconnect` 换新 channel，并**用剩余超时重发同一请求**（`NettyRemotingClient.java:836-867`），二次 GO_AWAY 才算失败——这是 5.x 主从优雅切换时客户端几乎无感的实现细节。

## 3.6 连接管理：惰性重连与 120 秒空闲检测

客户端 pipeline（`NettyRemotingClient.java:218-224`）：

```java
ch.pipeline().addLast(
    new NettyEncoder(),
    new NettyDecoder(),
    new IdleStateHandler(0, 0, nettyClientConfig.getClientChannelMaxIdleTimeSeconds()),
    new NettyConnectManageHandler(),
    new NettyClientHandler());
```

要点：① 空闲阈值默认 120s（`NettySystemConfig.java:63`），`ALL_IDLE` 事件触发 `closeChannel`（L1206-1217）——不写不读 2 分钟就断，防连接泄漏与半死连接；② `channelInactive` 只清理 `channelTables` **不主动重连**，真正建连发生在下次请求时惰性 `createChannelAsync`；③ 连接事件（CONNECT/CLOSE/IDLE/EXCEPTION）由单线程 `NettyEventExecutor` 分发（队列上限 10000，L768-806），回调客户端的 `ChannelEventListener`（Broker 用它维护连接表）。

NameServer 地址的选择是"随机起点 + 递增取模"：`namesrvIndex` 初值 `ThreadLocalRandom.nextInt(999)`（L182-184），选中后粘住该节点直至失效（L677-686）；地址列表更新时 shuffle（L536）并关闭失效连接；`scanAvailableNameSrv` 以 3s（connectTimeoutMillis）为周期维护可用地址表（L263-278）。

## 3.7 本章小结

| 问题 | 答案（源码锚点） |
|---|---|
| 帧怎么排 | 4 总长 + 4（类型<<24 \| headerLen）+ header + body（`RemotingCommand.encode`，L387-410） |
| 响应怎么匹配 | opaque 自增 + responseTable.remove（L468-483） |
| 并发上限 | 三对公平信号量：客户端 65535/65535，服务端 64/256（`NettySystemConfig.java:48-51`） |
| 超时兜底 | scanResponseTable 每 1s 清理 timeout+1000（L557-571） |
| 业务隔离 | processorTable 按 code 绑定"处理器+线程池"（L104-105） |
| 优雅下线 | GO_AWAY + 客户端剩余超时重发（`NettyRemotingClient.java:836-867`） |

---

# 四、存储内核：CommitLog、ConsumeQueue 与 IndexFile

> 本章对应源码：`store` 模块（根目录 `D:\code\3rd\rocketmq\store`）。这是 RocketMQ 性能与可靠性的物理基础。

## 4.1 先说白话：为什么所有消息混着写一条时间线

Kafka 按 partition 分文件，topic/partition 多了之后就是"随机写"灾难；RocketMQ 反其道：**全部 topic 的消息按到达顺序追加进同一条 CommitLog**（默认 1GB 一个文件，`MessageStoreConfig.java:52`），把磁盘随机写问题彻底消灭——无论多少 topic，写入永远是对当前活跃文件的顺序 append。代价是消费必须"二次定位"：每个 topic 队列维护一份 20 字节定长条目的 ConsumeQueue（偏移索引），由后台线程从 CommitLog 异步回放生成。**顺序写 + 异步索引**六个字，就是 RocketMQ 存储设计的全部。

## 4.2 写入路径：从 putMessage 到 mmap

【源码证据】`DefaultMessageStore.java:668-684`（store 层只做 hook/校验/统计，真正的写入在 CommitLog）：

```java
long beginTime = this.getSystemClock().now();
CompletableFuture<PutMessageResult> putResultFuture = this.commitLog.asyncPutMessage(msg);
putResultFuture.thenAccept(result -> {
    long elapsedTime = this.getSystemClock().now() - beginTime;
    if (elapsedTime > 500) {
        LOGGER.warn("DefaultMessageStore#putMessage: CommitLog#putMessage cost {}ms, ...", ...);
```

`CommitLog.asyncPutMessage` 主干（`CommitLog.java:1080-1138`）：线程本地编码器先把消息序列化成定长格式（`MessageExtEncoder`）→ `putMessageLock.lock()`（锁类型可配，L152-153：默认 `useReentrantLockWhenPutMessage=true` 用 ReentrantLock，可切自旋锁）→ 取 `mappedFileQueue.getLastMappedFile()`（没有或写满则同步建新文件）→ `mappedFile.appendMessage(...)` 回调 `doAppend` 真正写入 → 若撞到文件尾则先写 8 字节占位（`TOTALSIZE + BLANK_MAGIC_CODE`，L2054-2067）再换新文件重写——**文件尾的"半条消息"问题用占位符解决**。

【源码证据】消息的定长字段布局（`store/.../MessageExtEncoder.java:60-83`，`calMsgLength` 注释即文档）：

```java
return 4 //TOTALSIZE
    + 4 //MAGICCODE          // V1=-626843481 (0xDACDFE07), 文件尾占位 BLANK_MAGIC_CODE=-875286124
    + 4 //BODYCRC
    + 4 //QUEUEID
    + 4 //FLAG
    + 8 //QUEUEOFFSET        // 消息在 ConsumeQueue 里的逻辑偏移（SendResult.queueOffset）
    + 8 //PHYSICALOFFSET     // 消息在 CommitLog 里的物理偏移（offsetMsgId 的来源）
    + 4 //SYSFLAG            // 压缩/事务/多队列等标志位
    + 8 //BORNTIMESTAMP  + bornhostLength //BORNHOST(8/20)
    + 8 //STORETIMESTAMP + storehostAddressLength //STOREHOSTADDRESS(8/20)
    + 4 //RECONSUMETIMES
    + 8 //Prepared Transaction Offset
    + 4 + bodyLength //BODY
    + topicLengthSize + topicLength //TOPIC（V1 1 字节长 / V2 2 字节）
    + 2 + propertiesLength; //propertiesLength + 属性表
```

底层文件访问在 `DefaultMappedFile`（5.x 中 `MappedFile` 已接口化）：构造时 `fileChannel.map(MapMode.READ_WRITE, 0, fileSize)` 做 mmap（`DefaultMappedFile.java:209-218`）；append 直接写 mmap 映射的页缓存（开启 transientStorePool 时写堆外 buffer，见 4.3 节）。

## 4.3 刷盘：三个服务线程与两种介质

RocketMQ 的"写成功"有两层含义：写进 OS PageCache（进程崩溃不丢、断电仍可能丢）、force 到磁盘（断电也不丢）。三个服务线程分别对应不同策略（构造选择在 `CommitLog.java:2227-2244` 的 `DefaultFlushManager`）：

【源码证据】同步刷盘 `GroupCommitService`（`CommitLog.java:1706-1751`）——同步刷盘的关键设计是"攒批 + 读写队列交换"：

```java
private LinkedList<GroupCommitRequest> requestsWrite = new LinkedList<>();
private LinkedList<GroupCommitRequest> requestsRead = new LinkedList<>();

public void putRequest(final GroupCommitRequest request) {
    lock.lock();
    try { this.requestsWrite.add(request); } finally { lock.unlock(); }
    this.wakeup();
}
private void swapRequests() { /* 交换两个 List，写请求提交与刷盘线程无锁竞争 */ }

private void doCommit() {
    if (!this.requestsRead.isEmpty()) {
        for (GroupCommitRequest req : this.requestsRead) {
            boolean flushOK = CommitLog.this.mappedFileQueue.getFlushedWhere() >= req.getNextOffset();
            for (int i = 0; i < 1000 && !flushOK; i++) {
                CommitLog.this.mappedFileQueue.flush(0);
                flushOK = CommitLog.this.mappedFileQueue.getFlushedWhere() >= req.getNextOffset();
                ...
            }
            req.wakeupCustomer(flushOK ? PutMessageStatus.PUT_OK : PutMessageStatus.FLUSH_DISK_TIMEOUT);
```

同一毫秒内并发到达的同步刷盘请求被合并成一次 `flush(0)`（force 到已写入的最大位置），所有 `flushedWhere ≥ nextOffset` 的请求一起满足——**同步刷盘不是每条消息一次 fsync，而是 10ms 窗口内的一次组提交**，这就是 SYNC_FLUSH 也能有可观吞吐的原因。等待上限 `syncFlushTimeout=5000ms`（`MessageStoreConfig.java:257`），超时即 `FLUSH_DISK_TIMEOUT`。

异步刷盘 `FlushRealTimeService`（`CommitLog.java:1586-1626`）：默认 `flushCommitLogTimed=true` 时固定 sleep `flushIntervalCommitLog=500ms` 后 `flush(flushCommitLogLeastPages)`（默认攒够 4 页才刷，每 10s 强制全刷一次）；`flushCommitLogTimed=false` 时改为事件驱动（putMessage 后 `wakeup()`）。

第三种是堆外写缓冲 `CommitRealTimeService`（L1538-1563）：`transientStorePoolEnable=true` 时消息先写进 `TransientStorePool` 预分配的 5 个 1GB DirectBuffer（逐个 `mlock` 防换出，`TransientStorePool.java:48-58`），由 commit 线程每 200ms（`commitIntervalCommitLog`）把数据写进 FileChannel（页缓存），再联动 flush 线程 force——"堆外 buffer → page cache → 磁盘"两级，隔离了 mmap 写入时的缺页抖动（`DefaultMappedFile.flush` 的注释："We only append data to fileChannel or mappedByteBuffer, never both"，L537-542）。

> **丢不丢的关键分界**：ASYNC_FLUSH 下进程宕机不丢（数据在 PageCache），**主机断电可能丢最多 500ms**（还没 force 的部分）；SYNC_FLUSH 只有在 force 超时那一刻才存在理论丢失窗口，且返回给客户端的是明确的 `FLUSH_DISK_TIMEOUT`。这个区别是第八章 8.2.3 的核心依据。

## 4.4 ConsumeQueue：20 字节定长索引与"先落盘、后可见"

【源码证据】`store/.../ConsumeQueue.java:62-64`：

```java
/*
 * ConsumeQueue's store unit. Size: CommitLog Physical Offset(8) + Body Size(4) + Tag HashCode(8) = 20 Bytes
 */
public static final int CQ_STORE_UNIT_SIZE = 20;
```

每个 topic 的每个 queue 一个 ConsumeQueue（默认文件 300000×20=6MB，`MessageStoreConfig.java:137`），条目三段式：`物理偏移(8) + 消息长度(4) + tagsCode(8)`。tagsCode 通常是 tag 的 hashCode（Broker 端哈希过滤用），启用 CQ 扩展时是扩展单元地址（`isExtAddr`，L1169）。**定长的意义**：给定逻辑偏移 offset，条目物理位置 = offset×20，O(1) 定位，无需任何 B+ 树。

生成者 `ReputMessageService`（`DefaultMessageStore.java:2820-2833` run 循环，L2732-2751 doReput）：单线程每 1ms 从 `reputFromOffset` 顺序读 CommitLog，`checkMessageAndReturnSize` 解析出 `DispatchRequest`，依次交给 dispatcherList（L248-250：`CommitLogDispatcherBuildConsumeQueue` → `CommitLogDispatcherBuildIndex` → `CommitLogDispatcherBuildTransIndex`）——**ConsumeQueue、IndexFile、事务索引全部是 CommitLog 的"派生物"**，源头只有一条时间线。reput 起点/终点由 `confirmOffset` 控制（L2707-2709）——这就是"先落盘、后可见"：消息写完 CommitLog 并确认后才进入索引，消费者永远只能看到已确认的消息。

写入端有幂等保护（`ConsumeQueue.putMessagePositionInfoWrapper`，L839-847）：`offset + size <= getMaxPhysicOffset()` 直接返回 true，崩溃恢复后重复分发不会写重。

## 4.5 IndexFile：按 Key/UniqKey 查历史消息

ConsumeQueue 只支持"按队列顺序拉"，`QUERY_MESSAGE`（按 Key 查）靠另一套哈希索引：

【源码证据】`store/.../index/IndexFile.java:31-45`——每个索引条目 **20 字节**（⚠️ 旧资料常写成 40 字节，以源码为准）：

```
│ Key HashCode(4B) │ Physical Offset(8B) │ Time Diff(4B) │ Next Index Pos(4B) │  = 20 Bytes
```

文件 = 40 字节 IndexHeader + slot 区（每 slot 4 字节，默认 500 万个，`MessageStoreConfig.java:236`）+ index 区（默认 2000 万条 = 5000000×4，L237）。哈希冲突用链地址法：slot 存最新条目序号，条目内 `Next Index Pos` 串成链（`IndexFile.putKey`，L141-150）。索引 key 的组装在 `IndexService.buildKey`（`IndexService.java:220-222`）：`topic + "#" + key`，来源有两个——**每条消息的 UNIQ_KEY**（客户端自动写入，`IndexService.java:245-248`）与用户设置的 `Keys` 属性（按 `MessageConst.KEY_SEPARATOR` 空格分隔可建多个）。这就是 `producer.setKeys()` 能被 `mqadmin queryMsgById/-key` 查到的物理原因。

## 4.6 读路径：getMessage 与冷热分离

消费拉取的入口 `DefaultMessageStore.getMessage`（L896-990）：按 topic/queueId 找 ConsumeQueue → `iterateFrom(nextBeginOffset, maxMsgNums)` 遍历定长条目 → 用条目里的 `offsetPy/sizePy` 回 CommitLog 读取。两个值得记住的细节：

1. **满批判定 `isTheBatchFull`**（L955）会参考条目所在文件是否在内存（`isInMem`）——PageCache 里的"热"数据允许一次拉更多；
2. 冷热引导（L1027-1030）：如果消费进度远落后于最新写入（`diff = maxOffsetPy - maxPhyOffsetPulling` 超过内存占比阈值），响应头会置 `suggestPullingFromSlave`，客户端据此**切到从节点读**，把读压力从 master 上卸掉——RocketMQ 读写分离的开关在 brokerConfig `slaveReadEnable`。

## 4.7 过期清理与磁盘水位

CommitLog/ConsumeQueue 都是"追加 + 过期整文件删除"，没有就地更新：

- **时间触发**：每天 `deleteWhen="04"`（凌晨 4 点，`MessageStoreConfig.java:183`）扫描，文件最后修改时间超过 `fileReservedTime=72` 小时即删（`DefaultMessageStore.isTimeToDelete`，L2292-2299；删除主流程 L2374-2397）；
- **水位触发**：磁盘使用率超过 `diskMaxUsedSpaceRatio=75` 开始按最老文件删除；超过 warning 水位（默认 0.90，可配有上下限钳制）立即清理，超过 forcibly 水位（默认 0.85）强制清理（`isSpaceToDelete`，L2447-2458）；**水位越限的同时 `runningFlags.getAndMakeDiskFull()` 置位拒绝写入**——磁盘写满是 RocketMQ 自我保护的少数"直接拒收"场景之一（第八章 8.2 会再遇到它）；
- ConsumeQueue 侧由 `ConsumeQueueStore.start()` 内的定时清理与 `CorrectLogicOffsetService` 对齐（`ConsumeQueueStore.java:75-85`）。

> **这决定了 RocketMQ 的一个硬约束**：消息保留期由磁盘容量与水位决定（默认约 72h），"回溯消费"最多回到还没被清理的位置——`CONSUME_FROM_FIRST_OFFSET` 在旧 topic 上实际是从 ConsumeQueue 的最小 offset 开始，而不是 CommitLog 的第一天。

## 4.8 本章小结

| 问题 | 答案（源码锚点） |
|---|---|
| 为什么快 | 所有 topic 顺序追加同一条 CommitLog + mmap 页缓存（`CommitLog.java:1080-1138`） |
| 消息定长格式 | TOTALSIZE..properties 共 20+ 字段（`MessageExtEncoder.java:60-83`） |
| 同步刷盘怎么扛吞吐 | GroupCommitService 组提交：10ms 窗口合并一次 force（`CommitLog.java:1731-1751`） |
| 断电会丢多少 | ASYNC_FLUSH：≤500ms 未 force 部分；SYNC_FLUSH：仅 force 超时窗口 |
| 消费怎么定位消息 | ConsumeQueue 20 字节定长条目，offset×20 直接寻址（`ConsumeQueue.java:62-64`） |
| 索引何时生成 | ReputMessageService 每 1ms 异步回放，先落盘后可见（`DefaultMessageStore.java:2732-2751`） |
| 磁盘满了怎么办 | 75/85/90 三级水位 + DiskFull 拒写（`DefaultMessageStore.java:2447-2458`） |

---

# 五、高可用复制体系：主从、DLedger 与 Controller

> 本章对应源码：`store/src/main/java/org/apache/rocketmq/store/ha/`、`controller` 模块、`broker/controller/ReplicasManager.java`。

## 5.1 三代 HA：一条"少协调"路线的演进

| 方案 | 引入版本 | 切主方式 | 数据不丢强度 | 现状 |
|---|---|---|---|---|
| 传统主从（DefaultHAService） | 4.0 起 | **人工**（改 brokerId/配置重启） | 取决于 `brokerRole`：ASYNC_MASTER 可能丢未复制部分；SYNC_MASTER 少数切换场景 | 仍是默认 |
| DLedger（Raft） | 4.5（2019-04） | Raft 自动 | 多数派 | **已 @Deprecated**：`DLedgerCommitLog.java:59-65` 注释明示 "Use Controller mode for automatic Broker failover in new deployments"，且有专门测试钉死弃用（`DLedgerDeprecationTest.java:22-25`） |
| Controller 模式（AutoSwitchHAService） | 5.0 | Controller 仲裁自动 | SyncStateSet 多数确认 + confirmOffset | 5.x 推荐 |

选 HA 实现的开关在 store 初始化（`DefaultMessageStore.java:1976-1988`）：

```java
private void initializeHAService() {
    if (!this.messageStoreConfig.isEnableDLegerCommitLog() && !this.messageStoreConfig.isDuplicationEnable()) {
        if (brokerConfig.isEnableControllerMode()) {
            this.haService = new AutoSwitchHAService();
        } else {
            this.haService = ServiceProvider.loadClass(HAService.class);   // SPI，默认 DefaultHAService
```

## 5.2 传统主从：push 复制的最小实现

Master 监听 `haListenPort=10912`（`MessageStoreConfig.java:241`）等 slave 连接；Slave 端 `DefaultHAClient` 连上 master 后循环"收数据 → append 到本地 CommitLog → 回报自己的 maxOffset"。

【源码证据】Slave 回报格式就是 8 字节的 offset（`DefaultHAClient.java:110-115`）：

```java
private boolean reportSlaveMaxOffset(final long maxOffset) {
    this.reportOffset.position(0);
    this.reportOffset.limit(REPORT_HEADER_SIZE);
    this.reportOffset.putLong(maxOffset);
```

空闲超过 `haSendHeartbeatInterval=5000ms` 也会主动上报一次（`isTimeToReportOffset`，L105-108）——HA 通道自带心跳。Master 端每个连接一个 `DefaultHAConnection`（读线程解析 slave ack + 写线程 `WriteSocketService` 推数据），写线程每次最多推 `haTransferBatchSize=32KB`（`MessageStoreConfig.java:248`），并带 `FlowMonitor` 限流。

## 5.3 同步复制的等待：GroupTransferService 与两套超时

`brokerRole=SYNC_MASTER` 时，发送线程在写完 CommitLog 后**同步等 slave ack**——等待逻辑在 `CommitLog.handleHA`（L1387-1399，上文已引用）：

```java
// Wait enough acks from different slaves
GroupCommitRequest request = new GroupCommitRequest(nextOffset,
    this.defaultMessageStore.getMessageStoreConfig().getSlaveTimeout(), needAckNums);
haService.putRequest(request);
haService.getWaitNotifyObject().wakeupAll();
return request.future();
```

`GroupTransferService` 每 10ms 检查一次等待队列（`GroupTransferService.java:152-155`），唤醒条件两档（L92-128）：单 ack 模式用 `haService.getPush2SlaveMaxOffset()` 原子变量快速判断；多 ack（5.x Controller 的 `ALL_ACK_IN_SYNC_STATE_SET`）逐连接统计 `ackNums >= req.getAckNums()`。超时的产物**只有一个**（L136-141）：

```java
req.wakeupCustomer(transferOK ? PutMessageStatus.PUT_OK : PutMessageStatus.FLUSH_SLAVE_TIMEOUT);
```

> ⚠️ **两个与旧资料不符的硬事实**（写作时逐行验证）：
> 1. **复制等待超时是 `slaveTimeout=3000ms`（`MessageStoreConfig.java:260`），不是 `syncFlushTimeout=5000ms`**。`syncFlushTimeout` 只约束同步刷盘（`CommitLog.java:2253/2290`，SYNC_FLUSH 分支内）。
> 2. **store 层当前不再产生 `SLAVE_NOT_AVAILABLE`**（全 store 主代码 grep 仅枚举定义与响应码映射两处消费）；slave 未连接/未复制到位统一返回 `FLUSH_SLAVE_TIMEOUT`。`SLAVE_NOT_AVAILABLE` 现在只在 broker 响应码映射（`SendMessageProcessor.java:410-411`）与 EscapeBridge 逃逸转发（`EscapeBridge.java:298-299`）中出现。

`needAckNums` 的取值即 5.5 节 Controller 的 in-sync 语义入口：普通 SYNC_MASTER 为 1（任意 slave ack 即可），Controller 模式可要求 SyncStateSet 内全部/多数 ack（不足时返回 `IN_SYNC_REPLICAS_NOT_ENOUGH`，`PutMessageStatus.java:19-36` 共 17 个状态之一）。

## 5.4 Controller 模式：Epoch + SyncStateSet + confirmOffset

Controller 是一个独立小集群（默认用 DLedger 做 Raft 选举，也可选 jRaft：`ControllerManager.java:102-124`，`ControllerConfig.controllerType` 默认 `DLedger`），权威保存两组元数据（`controller/.../manager/ReplicasInfoManager.java:106-107`）：

```java
this.replicaInfoTable = new ConcurrentHashMap<String, BrokerReplicaInfo>();
this.syncStateSetInfoTable = new ConcurrentHashMap<String, SyncStateInfo>();
```

三个核心概念：

1. **SyncStateSet**：当前"数据足够新、可参与多数确认"的副本集合。Broker 侧 `AutoSwitchHAService` 内存持有并主动上报变更（`AutoSwitchHAService.java:64-71`）；收缩规则——slave 落后超过 `haMaxTimeSlaveNotCatchup` 移出；扩张规则——slave 追上 confirmOffset 即可加入（方法注释 L277-316）。
2. **Epoch（世代号）**：每次切主 epoch+1，master 写本地 `EpochFileCache` 并在切主时**截断脏数据**（`AutoSwitchHAService.changeToMaster`，L134-148：`truncateInvalidMsg()` + 追加新 EpochEntry）——Raft 语义下"新主可能有旧主没确认的数据"，复制时用 epochStartOffset 判断从哪续传（`AutoSwitchHAConnection` 的 TRANSFER_HEADER 含 epoch/confirmOffset，L69）。
3. **confirmOffset**：SyncStateSet 内所有连接 slaveAckOffset 的最小值（L438-443）——**消费者读到的安全水位线**，ReputMessageService 以它为终点保证"从节点读到的消息一定是已多数确认的"（对应 4.4 节 reputFromOffset 的终点）。

切主决策由 Controller 依据心跳做（broker 每 `brokerHeartbeatInterval` 上报 epoch/maxOffset/confirmOffset，`BrokerOuterAPI.java:1493-1502`），选举策略 `DefaultElectPolicy` 的三级比较器（`DefaultElectPolicy.java:39-51`）：**epoch 降序 → maxOffset 降序 → electionPriority 升序**——先保证数据最新，再谈优先级。决策结果推给 broker，`ReplicasManager.changeBrokerRole` 执行切换（`ReplicasManager.java:225-233`，要求 `newMasterEpoch > this.masterEpoch` 防 fence）。

指令面：`CONTROLLER_ALTER_SYNC_STATE_SET=1001`、`CONTROLLER_ELECT_MASTER=1002` 等（`RequestCode.java:257-267`），AlterSyncStateSet 带 epoch fence 校验（旧 masterEpoch 直接 `CONTROLLER_FENCED_MASTER_EPOCH` 拒绝，`ReplicasInfoManager.java:144-158`）。

## 5.5 本章小结：怎么选

| 维度 | 主从 + SYNC_MASTER | 主从 + ASYNC_MASTER | Controller 模式 |
|---|---|---|---|
| 断电不丢（master 单机） | ✔（SYNC_FLUSH 配合） | ✘（≤500ms） | ✔（SYNC_FLUSH 配合） |
| master 机器报废不丢 | 基本 ✔（等 slave ack 才回执） | ✘（可能丢未复制消息） | ✔（多数派 SyncStateSet） |
| 故障切换 | 人工 | 人工 | 自动（秒级） |
| 运维复杂度 | 低 | 最低 | 中（多一个 Controller 集群） |
| 写吞吐 | 受 slave ack 延迟影响 | 最高 | 多数派确认 |

结论先行：**要求"master 物理报废也不丢"且能接受少量运维，用主从 + SYNC_MASTER + SYNC_FLUSH；要求自动故障转移，直接上 Controller 模式**。这两种组合如何反映到 Client 端编码与配置，是第八章 8.2.2 的主题。

**补充一本"机器账"**（机器数 ≠ 进程数，部署最小值常被问倒）：

- **传统主从**：一组 broker（1 主 N 从）要具备容灾，主从**必须分机**——同机部署 master+slave 技术上可行（错开端口），但主机一挂全组覆灭，无容灾意义。所以最少 **2 台**（经典 2m-2s 也是 2 台：两台机器互为主从）；NameServer 另算（无状态，可与之混部）。
- **Controller 模式**：Controller 遵循 Raft 多数派，**要具备容错需 ≥3 个节点**（官方 `docs/cn/controller/deploy.md` 原文："Controller部署需要三副本及以上（遵循Raft的多数派协议）"；部署单副本也能完成切换，但该单点 Controller 故障后集群失去切换能力，存量收发不受影响；2 副本没有意义——挂 1 个即失去多数派）。Broker 侧同组至少 **2 副本**才能自动切换：2 副本（1 主 1 从）数据可以不丢（等 slave ack），但要求 `allAckInSyncStateSet` 时，切换完成前写入不可用、切换后处于"无备份裸奔"状态；要"**挂一台、照常写、不丢**"，同组需要 **3 副本**（1 主 2 从，RocketMQ-on-DLedger 文档同样给出 2n+1 原则）。好消息是 Controller **可以内嵌进 NameServer 进程**（`enableControllerInNamesrv=true`，见 5.4 节的 `ControllerManager`），因此 Controller 模式的典型最小生产形态就是 **3 台机器**：每台跑一个 NameServer（内嵌 Controller）+ 同一组 broker 的一个副本。

---

# 六、Producer 深度解析：从 send() 到 Broker 落盘

> 本章对应源码：`client` 模块 producer 相关类 + `broker/processor/SendMessageProcessor.java`。

## 6.1 先说白话：一次 send() 的契约

`producer.send(msg)` 返回 `SendResult` 时，客户端实际承诺了什么？**消息已经被 Broker 写进了 CommitLog，并按 Broker 的刷盘/复制配置等待到了对应程度**——"对应程度"取决于 Broker 配置（4.8、5.3 节），而不是客户端。理解这一点，第八章的"三态也算成功"问题就有了根：`SendStatus` 有四个值，后三个（FLUSH_DISK_TIMEOUT / FLUSH_SLAVE_TIMEOUT / SLAVE_NOT_AVAILABLE）**消息可能已经写成功**，只是没等到全部确认。默认参数下客户端对此**不做重试**（6.2 节），这就是"发是发出去了，收没收到只有天知道"的来源。

发送三模式一览（`CommunicationMode`）：

| 模式 | 等待 | 重试 | 语义 | 适用 |
|---|---|---|---|---|
| SYNC | 阻塞至响应 | `1+retryTimesWhenSendFailed`（默认共 3 次） | at-least-once（配合幂等） | 关键业务，默认选它 |
| ASYNC | 立即返回，回调通知 | `retryTimesWhenSendAsyncFailed`（默认 2，递归重试） | at-least-once | 高吞吐 + 回调里兜底 |
| ONEWAY | 什么都不等 | **无** | at-most-once | 指标上报等可容忍丢失场景 |

## 6.2 sendDefaultImpl：重试循环与超时预算

【源码证据】`client/.../impl/producer/DefaultMQProducerImpl.java:744-791`（主干）：

```java
TopicPublishInfo topicPublishInfo = this.tryToFindTopicPublishInfo(msg.getTopic());
if (topicPublishInfo != null && topicPublishInfo.ok()) {
    boolean callTimeout = false;
    ...
    int timesTotal = communicationMode == CommunicationMode.SYNC ? 1 + this.defaultMQProducer.getRetryTimesWhenSendFailed() : 1;
    int times = 0;
    String[] brokersSent = new String[timesTotal];
    boolean resetIndex = false;
    for (; times < timesTotal; times++) {
        String lastBrokerName = null == mq ? null : mq.getBrokerName();
        if (times > 0) {
            resetIndex = true;
        }
        MessageQueue mqSelected = this.selectOneMessageQueue(topicPublishInfo, lastBrokerName, resetIndex);
        ...
            long costTime = beginTimestampPrev - beginTimestampFirst;
            if (timeout < costTime) {
                callTimeout = true;
                break;
            }
            ...
            sendResult = this.sendKernelImpl(msg, mq, communicationMode, sendCallback, topicPublishInfo, curTimeout);
```

五个关键事实：

1. **重试次数语义**：`retryTimesWhenSendFailed=2`（`DefaultMQProducer.java:126`）表示总共发 1+2=3 次（L756）。**且只有 SYNC 走这个循环**——ASYNC 在上层固定 1 次，重试下沉到 MQClientAPIImpl（见 6.3）；ONEWAY 连尝试失败的感知都没有。
2. **重试必然换 broker**：每次循环传 `lastBrokerName` + `resetIndex=true`，`MQFaultStrategy.selectOneMessageQueue` 优先选**与上次不同 broker** 的队列（`MQFaultStrategy.java:143-168`）——单机故障时重试才有意义。
3. **总超时预算共享**：`sendMsgTimeout=3000ms`（`DefaultMQProducer.java:116`）是**整次 send 的预算**，重试时按已耗时间递减（`curTimeout`）；预算耗尽 `callTimeout=true` 直接抛 `RemotingTooMuchRequestException`（L871-874）。想让重试真的发生，要么调大 `sendMsgTimeout`，要么接受"重试次数多但超时截断"。
4. **异常分类决定重试与否则**（L808-853）：`MQClientException`/`RemotingException`（连不上、超时）→ 无条件 continue 重试，且 `RemotingException` 会 `updateFaultItem(..., isolation=true)` 把该 broker 隔离一段时间；`MQBrokerException` → 仅当响应码在可重试集合（`retryResponseCodes`：TOPIC_NOT_EXIST、SERVICE_NOT_AVAILABLE、SYSTEM_ERROR、SYSTEM_BUSY、NO_PERMISSION、GO_AWAY 等，`DefaultMQProducer.java:77-86`）才重试，否则抛出；`InterruptedException` → 直接抛出不重试。
5. **非 SEND_OK 的三分支默认不重试**（L797-807）：

```java
case SYNC:
    if (sendResult.getSendStatus() != SendStatus.SEND_OK) {
        if (this.defaultMQProducer.isRetryAnotherBrokerWhenNotStoreOK()) {
            continue;
        }
    }
    return sendResult;
```

`retryAnotherBrokerWhenNotStoreOK` 默认 **false**（`DefaultMQProducer.java:133`）——Broker 返回"刷盘超时/复制超时"时，默认**直接把这个疑似不丢的 SendResult 返回给业务**。这是第八章 8.2.2 的头号议题。

## 6.3 sendKernelImpl：UNIQ_KEY、压缩与异步递归重试

`sendKernelImpl`（入口 L933-954）在真正发包前完成四件事：`MessageClientIDSetter.setUniqID(msg)` 写入 UNIQ_KEY（**客户端消息全局唯一 ID**，组成 = IP+PID+ClassLoader hash+月内毫秒偏移+自增序号，`MessageClientIDSetter.java:33-48, 116-139`）；body ≥ 4KB（`compressMsgBodyOverHowmuch=1024*4`）时压缩并置 COMPRESSED_FLAG（L1112-1131）；检测 `TRAN_MSG` 属性则置 `sysFlag |= TRANSACTION_PREPARED_TYPE`（事务半消息，6.7 节）；执行 `SendMessageHook`（异常路径也会执行 after）。

**异步发送的重试在 MQClientAPIImpl 内部递归**（`MQClientAPIImpl.java:704-742` 的 `onExceptionImpl`）：

```java
int tmp = curTimes.incrementAndGet();
if (needRetry && tmp <= timesTotal && timeoutMillis > 0) {
    String retryBrokerName = brokerName;   // 默认发回同一 broker
    if (topicPublishInfo != null) {
        MessageQueue mqChosen = producer.selectOneMessageQueue(topicPublishInfo, brokerName, false);
        retryBrokerName = instance.getBrokerNameFromMessageQueue(mqChosen);
    }
    String addr = instance.findBrokerAddressInPublish(retryBrokerName);
    ...
    request.setOpaque(RemotingCommand.createNewRequestId());   // 复用请求体，新 opaque
    sendMessageAsync(addr, retryBrokerName, msg, timeoutMillis, request, sendCallback, ...);
} else {
    sendCallback.onException(e);   // 重试耗尽才回调 onException
}
```

要点：异步重试最多 `retryTimesWhenSendAsyncFailed=2` 次，耗尽前业务回调 `onSuccess` 无感知，**耗尽后只回调 `onException`，不会抛异常**——回调里不做兜底，这条消息就"悄悄没了"。另外 5.x 新增了异步背压：`enableBackpressureForAsyncMode=true` 时用信号量限制在途异步消息（默认 1024 条 / 32MB，`DefaultMQProducer.java` 相应字段），示例工程也建议在异步高吞吐时开启（`example/simple/AsyncProducer.java:36-37`）。

高并发下的"哪个队列"问题由 `MQFaultStrategy` 解决（`client/latency/MQFaultStrategy.java:26-31, 143-186`）：

```java
private long[] latencyMax = {50L, 100L, 550L, 1800L, 3000L, 5000L, 15000L};
private long[] notAvailableDuration = {0L, 0L, 2000L, 5000L, 6000L, 10000L, 30000L};
```

开关 `sendLatencyFaultEnable` 默认 false（藏在系统属性 `com.rocketmq.sendLatencyEnable` 后，`ClientConfig.java:99-100`）。false 时只做"避开 lastBrokerName 的轮询"；true 时按上表把"上次发送耗时 ≥550ms"的 broker 隔离 2 秒、≥15s 隔离 30 秒，选队列按"available → reachable → 全量"三级降级；5.x 还新增了后台主动探测（`startDetectorEnable` 系统属性，探测线程 3s 周期、200ms 超时，`LatencyFaultToleranceImpl.java:82-95`），隔离期内的 broker 可能被探测"复活"。多机房/多单元场景建议开启。

## 6.4 Broker 端接收：msgCheck、四态回执与"三态也算成功"

Broker 的 `SendMessageProcessor` 先做校验（`AbstractSendMessageProcessor.msgCheck`，L464-536）：写权限、topic 合法性、系统 topic 禁发；**topic 不存在且 `autoCreateTopicEnable=true` 时借 `TBW102` 的配置自动建题**（`TopicConfigManager.java:278-299`，`TopicValidator.AUTO_CREATE_TOPIC_KEY_TOPIC="TBW102"`）——自动建题的队列数继承 TBW102 在各 Broker 的配置，这也是生产环境建议关掉 autoCreateTopic 的原因：建题流量瞬时会集中到一台 Broker。

真正落盘后，按 `PutMessageStatus` 映射响应（`SendMessageProcessor.java:396-467`）：

```java
switch (putMessageResult.getPutMessageStatus()) {
    // Success
    case PUT_OK:
        sendOK = true;  response.setCode(ResponseCode.SUCCESS);  break;
    case FLUSH_DISK_TIMEOUT:
        sendOK = true;  response.setCode(ResponseCode.FLUSH_DISK_TIMEOUT);  break;
    case FLUSH_SLAVE_TIMEOUT:
        sendOK = true;  response.setCode(ResponseCode.FLUSH_SLAVE_TIMEOUT);  break;
    case SLAVE_NOT_AVAILABLE:
        sendOK = true;  response.setCode(ResponseCode.SLAVE_NOT_AVAILABLE);  break;
    // Failed
    case IN_SYNC_REPLICAS_NOT_ENOUGH:
        response.setCode(ResponseCode.SYSTEM_ERROR); ...
    case CREATE_MAPPED_FILE_FAILED: ...
    case SERVICE_NOT_AVAILABLE: ...
    case OS_PAGE_CACHE_BUSY:
        response.setCode(ResponseCode.SYSTEM_BUSY);
        response.setRemark("[PC_SYNCHRONIZED]broker busy, start flow control for a while"); break;
```

**后三个状态 `sendOK=true`**：Broker 认为"消息主体已写入 CommitLog，只是确认没等全"，算半成功。客户端把它映射成 `SendStatus` 后三种状态（`MQClientAPIImpl.processSendResponse`，L751-770；`SendStatus` 枚举在 `client/.../producer/SendStatus.java:19-24`）。**两端对"成功"的定义并不一致**——Broker 半成功、客户端默认按成功返回，这中间的裂缝由第八章 8.2 的配置来焊上。

`OS_PAGE_CACHE_BUSY` 的判定（`DefaultMessageStore.isOSPageCacheBusy`，L742-749）：锁内写入耗时超过 `osPageCacheBusyTimeOutMills=1000ms` 视为 PageCache 忙。配套的主动流控 `BrokerFastFailure`（`broker/latency/BrokerFastFailure.java:81-147`）每 **10ms** 检查一次：PageCache 忙就立即丢弃 SendThreadPoolQueue 中排队的发送请求并回 SYSTEM_BUSY；排队超过 `waitTimeMillsInSendQueue=200ms`（`BrokerConfig.java:147`）也被清理（`[TIMEOUT_CLEAN_QUEUE]`）。总开关 `brokerFastFailureEnable=true`（`BrokerConfig.java:146`）——**Broker 选择了"快速失败保护自己"而不是"排队硬扛"**，客户端的同步重试刚好接住这批 SYSTEM_BUSY（在 retryResponseCodes 里）。

## 6.5 双 msgId：幂等键的事实标准

一条消息从客户端视角有两个 ID（`SendResult.java:22-44`、`MQClientAPIImpl.java:783-798`）：

- **`msgId`** = 客户端 UNIQ_KEY（`MessageClientIDSetter` 生成，重试时**不变**——`setUniqID` 只在属性为空时写入，L1547-1560 `setUniqID` 的 if 判断）。`UNIQ_KEY` 属性名常量是 `MessageConst.PROPERTY_UNIQ_CLIENT_MESSAGE_ID_KEYIDX`（`MessageConst.java:42`）；
- **`offsetMsgId`** = Broker 返回的物理地址 ID（brokerAddr + CommitLog 偏移编码，`MessageDecoder.createMessageId`）。⚠️ 若消息重发到另一台 Broker（重试换 broker），offsetMsgId 会**变**。

结论（第八章 8.3.2 展开）：**业务幂等键应该用 msgId（UNIQ_KEY）或业务唯一键，永远不要用 offsetMsgId**。

## 6.6 事务消息：半消息、op 队列与回查

事务消息解决"**本地事务成功了，消息却没发出去**"（或反过来）的最终一致问题。全链路（源码锚点齐全）：

1. **prepare**：`sendMessageInTransaction` 给消息打 `TRAN_MSG=true` 属性走普通 send（`DefaultMQProducerImpl.java:1444-1451`）；Broker 的 `TransactionalMessageBridge.parseHalfMessageInner`（L211-236）把消息改写到系统 topic **`RMQ_SYS_TRANS_HALF_TOPIC`**（`TopicValidator.java:32`），原 topic/queueId 存进 `REAL_TOPIC/REAL_QID` 属性——半消息已持久化但对消费者不可见；
2. **执行本地事务**：客户端收到 prepare 发送回执后同步调 `TransactionListener.executeLocalTransaction`（L1452-1484）；⚠️ 发送结果是 FLUSH_DISK_TIMEOUT/FLUSH_SLAVE_TIMEOUT/SLAVE_NOT_AVAILABLE 时**直接判 ROLLBACK**（L1485-1490）——半消息状态不明，宁可回滚等回查兜底；
3. **二阶段**：`endTransaction` 用 **oneway** 发 END_TRANSACTION（L1547-1567，`endTransactionOneway`）；Broker 的 `EndTransactionProcessor`（L132-167）对 COMMIT：恢复 REAL_TOPIC 重新 `putMessage` 进 CommitLog（对消费者可见）+ 写 op 队列标记 half 已决；对 ROLLBACK：仅写 op 队列（`RMQ_SYS_TRANS_OP_HALF_TOPIC`）；
4. **回查兜底**：二阶段是 oneway（可能丢），且生产者可能宕机——`TransactionalMessageCheckService` 每 `transactionCheckInterval=30s`（`BrokerConfig.java:295`，⚠️ 4.x 旧资料写 60s）触发 `TransactionalMessageServiceImpl.check`（L162-201）：扫 half 队列，对照 op 队列剔除已决消息，**超过免疫期**（消息级属性 `CHECK_IMMUNITY_TIME_IN_SECONDS`，默认 `transactionTimeOut=6s`）且回查次数未达 `transactionCheckMax=15` 的，恢复原 topic 后经 `Broker2Client.checkProducerTransactionState`（L74-88，oneway CHECK_TRANSACTION_STATE）发给生产者；客户端 `ClientRemotingProcessor`（L95-115）反查 `transactionListener.checkLocalTransaction`，结果仍走 endTransactionOneway 回给 Broker。

协议语义上是 **2PC + 补偿**（官方 `docs/cn/design.md:143-167` 原文："RocketMQ采用了2PC的思想来实现了提交事务消息，同时增加一个补偿逻辑来处理二阶段超时或者失败的消息"）。注意它保证的是**最终一致**而非强一致：prepare 与 commit 之间存在消费者不可见的窗口。

## 6.7 本章小结

| 问题 | 答案（源码锚点） |
|---|---|
| 同步发送重试几次 | 1+retryTimesWhenSendFailed（默认 3），共享 sendMsgTimeout 预算（`DefaultMQProducerImpl.java:756, 780`） |
| 异步怎么重试 | MQClientAPIImpl.onExceptionImpl 递归重试，耗尽才回调 onException（L704-742） |
| oneway 有保障吗 | 无响应无重试，at-most-once（L570-572） |
| FLUSH_DISK_TIMEOUT 算成功吗 | Broker 端 sendOK=true，消息可能已落盘；客户端默认不重试直接返回（`SendMessageProcessor.java:399-413`） |
| 幂等用哪个 ID | msgId(UNIQ_KEY) 跨重试稳定；offsetMsgId 换 broker 会变 |
| 事务消息怎么兜底 | half+op 双队列 + 30s 回查 ×最多 15 次（`TransactionalMessageServiceImpl.java:162-201`） |

---

# 七、Consumer 深度解析：长轮询 Push、Rebalance 与 Offset

> 本章对应源码：`client` 模块 consumer 相关类 + `broker/processor/PullMessageProcessor.java`、`broker/longpolling/`。

## 7.1 先说白话：Push 消费者其实是"挂住的拉"

`DefaultMQPushConsumer` 名字里是 Push，实现里没有任何服务端推送：客户端线程池 `PullMessageService` 不停地对每个队列发 PULL_MESSAGE（=11），拉到消息就回调消费、拉不到就把请求**挂在 Broker**。新消息一落盘，Broker 立刻唤醒挂着的请求返回数据。这就是长轮询：既有拉模式的进度控制与背压，又有接近推模式的延迟（消息落盘到被消费通常毫秒级）。

三层唤醒链（从写入到唤醒，全部源码可循）：

1. 消息写入 CommitLog，`ReputMessageService` 回放 DispatchRequest 时回调 `notifyMessageArriveIfNecessary`（`DefaultMessageStore.java:2643-2653`，仅 `longPollingEnable=true` 时）；
2. `NotifyMessageArrivingListener.arriving`（`broker/longpolling/NotifyMessageArrivingListener.java:41-53`）转发给 `PullRequestHoldService.notifyMessageArriving`（Tag/SQL92 先按 ConsumeQueue 的 tagsCode 匹配）；
3. 匹配命中 → `PullMessageProcessor.executeRequestWhenWakeup` 用**原请求**重新走一遍 processRequest（`brokerAllowSuspend=false` 防止再次挂起死循环，`PullMessageProcessor.java:806-814`）。

兜底：`PullRequestHoldService` 每 **5s** 全量 recheck（`PullRequestHoldService.java:73-76`：`waitForRunning(5 * 1000)`），挂起时长上限由客户端随请求携带的 `suspendTimeoutMillis`（`BROKER_SUSPEND_MAX_TIME_MILLIS = 15s`，`DefaultMQPushConsumerImpl.java:114`）控制，超时也唤醒。⚠️ 旧资料说的 `brokerConfig.waitTimeMillsInQueue=15000` 在当前快照已不存在，15s 这个数字现在来自客户端。

## 7.2 客户端后台任务全景：五个周期

`MQClientInstance.start()` 为所有 producer/consumer 共享的后台任务（全部源码可证）：

| 周期 | 任务 | 证据 |
|---|---|---|
| 30s | 拉 NameServer 路由（updateTopicRouteInfoFromNameServer） | `MQClientInstance.java:400-406` |
| 30s | 清理下线 Broker + 向全部 Broker 发心跳（带订阅） | `MQClientInstance.java:408-415`，`ClientConfig.java:62` |
| 5s | 持久化所有消费 offset（persistAllConsumerOffset） | `MQClientInstance.java:417-423`，`ClientConfig.java:66` |
| 20s | 触发一次 doRebalance（不平衡时降到 1s） | `RebalanceService.java:25-53` |
| 2min | 拉取地址服务器（address server）上的 NameServer 地址文件 | `MQClientInstance.java:391-397` |

**5s 这个数字请先记住**：它是"集群模式下消费进度丢失窗口"的量级——进程崩溃时，最多 5 秒内已消费但未持久化的 offset 会回到 Broker 记录的旧值，重启后从旧 offset 重新拉取，产生**重复消费（而不是丢消息）**。第八章 8.3.1 会引用它。

## 7.3 Rebalance：队列怎么分到消费者头上

【源码证据】`RebalanceImpl.doRebalance`（`RebalanceImpl.java:232-249`）对每个订阅 topic 分两条路：5.x 默认 `clientRebalance()=true` 走本地分配；仅当 push+集群+非顺序且用户显式关闭 `isClientRebalance()` 时，才向 Broker 发 `QUERY_ASSIGNMENT` 由服务端分配（服务端可以据此下发 **Pop 模式**的 assignment，`QueryAssignmentProcessor.java:107-126`）。

本地分配主干（`RebalanceImpl.java:297-325`）：

```java
List<String> cidAll = this.mQClientFactory.findConsumerIdList(topic, consumerGroup);
...
Collections.sort(mqAll);
Collections.sort(cidAll);
AllocateMessageQueueStrategy strategy = this.allocateMessageQueueStrategy;
List<MessageQueue> allocateResult = strategy.allocate(
    this.consumerGroup, this.mQClientFactory.getClientId(), mqAll, cidAll);
...
boolean changed = this.updateProcessQueueTableInRebalance(topic, allocateResultSet, isOrder);
```

**先排序再分配**是公平的根基：所有消费者看到同样的 mqAll/cidAll 顺序，各自独立计算也得到全局一致的划分，无需任何分布式协调。默认策略 `AllocateMessageQueueAveragely`（均分，前 mod 个消费者多拿 1 个，L37-46）；可选按环形均分（AveragelyByCircle）、一致性哈希（ConsistentHash）、机房就近（MachineRoomNearby）等（`client/consumer/rebalance/` 目录）。

队列易主的交接（`updateProcessQueueTableInRebalance`，L430-503）：不再属于自己的队列，`processQueue.setDropped(true)` 后移除（dropped 的队列不再更新 offset，见 7.5）；新分到的队列创建 ProcessQueue + PullRequest，初始 offset 由 `computePullFromWhere` 按 `ConsumeFromWhere` 计算，然后 `dispatchPullRequest(pullRequestList, 500)` **延迟 500ms** 才开始拉——给旧 owner 一点时间收尾，降低交接期重复。

成员变化的感知：Broker 在心跳处理里发现某组的成员/订阅变化（`ConsumerManager.registerConsumer`，L248-264），`isNotifyConsumerIdsChangedEnable` 时向组内所有客户端 oneway 下发 `NOTIFY_CONSUMER_IDS_CHANGED`（`Broker2Client.java:104-110`），客户端收到立即触发一次 doRebalance——**20s 周期兜底 + 事件即时触发**，扩缩容秒级生效。

**顺序消费的额外一环**：Rebalance 只保证"队列分配给了谁"，不保证"全世界都知道"。ConsumeMessageOrderlyService 每 20s（`ProcessQueue.REBALANCE_LOCK_INTERVAL=20000`，`ProcessQueue.java:42`）向队列所在 Broker 发 LOCK_BATCH_MQ 抢队列级分布式锁（`RebalanceImpl.lockAll`，L182-220），抢到才允许消费；锁过期时间 30s（`REBALANCE_LOCK_MAX_LIVE_TIME`）。

## 7.4 拉取回调与三级流控

拉取回调四分支（`DefaultMQPushConsumerImpl` 内匿名 `PullCallback`）：

- **FOUND**（L353-381）：`pullRequest.setNextOffset(pullResult.getNextBeginOffset())` → `processQueue.putMessage(msgs)` 入本地缓存 TreeMap → `submitConsumeRequest` 提交消费 → 立即（或 pullInterval 后）再次拉取——**拉取是"拉到即续拉"的自驱动循环**；
- **NO_NEW_MSG / NO_MATCHED_MSG**（L394-400）：修正 offset 后立即续拉（Broker 会挂起 15s，所以这一支实际是"挂起被唤醒后返回空"）；
- **OFFSET_ILLEGAL**（L401-427，5.x 命名）：`updateAndFreezeOffset` 冻结非法 offset → persist → 移除 ProcessQueue → 触发 Rebalance 重新分配（`ControllableOffset` 的 `allowToUpdate` 语义防止并发线程把 offset 写回去，`ControllableOffset.java:52-54`）；
- **异常分支**（L445-449）：Broker 回 FLOW_CONTROL 响应码时延迟 20ms 重试，其他异常延迟 `pullTimeDelayMillsWhenException`（默认 3s）。

三级流控（L269-302）在**发起拉取前**检查 ProcessQueue 缓存量：

```java
if (cachedMessageCount > this.defaultMQPushConsumer.getPullThresholdForQueue()) {   // 默认 1000 条
    this.executePullRequestLater(pullRequest, PULL_TIME_DELAY_MILLS_WHEN_CACHE_FLOW_CONTROL);  // 50ms
    ... return;
}
if (cachedMessageSizeInMiB > ... getPullThresholdSizeForQueue()) { ... }           // 默认 100 MiB
if (!this.consumeOrderly && processQueue.getMaxSpan() > ... getConsumeConcurrentlyMaxSpan()) { ... } // 默认 2000
```

流控的形态是"**把 PullRequest 延迟 50ms 重新入队**"，不阻塞任何线程（单线程 PullMessageService 不被拖住）；MaxSpan 流控只在并发模式生效，目的是限制"最老未消费与最新消息的跨度"，防止单队列缓存无界增长导致本地内存溢出。

## 7.5 并发消费：processConsumeResult 与 offset 推进

消费线程池 `consumeExecutor`（默认 `consumeThreadMin=consumeThreadMax=20`，`DefaultMQPushConsumer.java:162-169`）执行 `ConsumeRequest.run`：调用 `messageListener.consumeMessage(...)` → 记录耗时与 `ConsumeReturnType`（SUCCESS/TIME_OUT/EXCEPTION/RETURNNULL/FAILED，`ConsumeReturnType.java:20-40`；TIME_OUT 阈值 = `consumeTimeout * 60 * 1000`，默认 15 分钟）→ **返回 null 视为 RECONSUME_LATER**（`ConsumeMessageConcurrentlyService.java:399-404`）→ `processConsumeResult`：

【源码证据】`ConsumeMessageConcurrentlyService.java:238-269`（本章最关键的一段）：

```java
case CLUSTERING:
    List<MessageExt> msgBackFailed = new ArrayList<>(consumeRequest.getMsgs().size());
    for (int i = ackIndex + 1; i < consumeRequest.getMsgs().size(); i++) {
        MessageExt msg = consumeRequest.getMsgs().get(i);
        ...
        boolean result = this.sendMessageBack(msg, context);   // 失败消息回发 retry topic
        if (!result) {
            msg.setReconsumeTimes(msg.getReconsumeTimes() + 1);
            msgBackFailed.add(msg);
        }
    }
    if (!msgBackFailed.isEmpty()) {
        consumeRequest.getMsgs().removeAll(msgBackFailed);
        this.submitConsumeRequestLater(msgBackFailed, ...);    // 回发也失败的，5s 后本地重投
    }
    break;
...
long offset = consumeRequest.getProcessQueue().removeMessage(consumeRequest.getMsgs());
if (offset >= 0 && !consumeRequest.getProcessQueue().isDropped()) {
    this.defaultMQPushConsumerImpl.getOffsetStore().updateOffset(consumeRequest.getMessageQueue(), offset, true);
}
```

三个可靠性要点：

1. **成功才推进**：`removeMessage` 只把**本次提交给监听器的消息**从 ProcessQueue 移除并返回新 offset，`updateOffset` 是"只增"的（`increaseOnly=true`）。CONSUME_SUCCESS 时 `ackIndex` 使循环体不执行（全部算成功）；RECONSUME_LATER 时 `ackIndex=-1`，全部消息逐条回发。**`sendMessageBack` 失败的消息留在 `consumeRequest.getMsgs()` 里一起被 remove**——它们已被发到 retry topic，本条队列的 offset 才能推进（否则队列会被一条失败消息卡死）；
2. **回发失败的三重兜底**：先走 CONSUMER_SEND_MSG_BACK（=36）发给源 Broker 的 retry topic（`DefaultMQPushConsumerImpl.sendMessageBack`，L759-774）；Broker 不可达时降级为客户端直发 `%RETRY%+group` 普通消息（`sendMessageBackAsNormalMessage`，L787-801，注意此时 `setDelayTimeLevel(3 + reconsumeTimes)`）；直发也失败则计数 +1 后 **5 秒后本地重新提交消费**（L297-303）；
3. **removeMessage 的返回值语义**（`ProcessQueue.java:187-222`）：移除后树空 → 返回 `queueOffsetMax + 1`（推进到已拉取的最大位）；树非空 → 返回 `firstKey()`（剩余最老消息的 offset，保守推进）。**消费线程可以乱序并发完成，但 offset 永远取最小未完成位**——这就是"至少一次"在客户端的实现：慢的那条消息会拖住 offset，但也保证它不会被跳过。

15 分钟消费超时的另一个出口：`cleanExpireMsgExecutors` 每 `consumeTimeout`（15min）周期把 ProcessQueue 里**超过 consumeTimeout 还没被消费**的队首消息 sendMessageBack 后移除（`ProcessQueue.cleanExpiredMsg`，L75-89）——防止某条消息把队列卡死。⚠️ 这也意味着：**消费回调里做"先返回成功、异步处理"的设计，实际在拿消息安全赌博**（第八章 8.2.4 展开）。

## 7.6 顺序消费：队列锁 + 单线程语义

`ConsumeMessageOrderlyService` 与并发版的差异全部围绕"保序"：

- 集群模式每 20s `lockMQPeriodically` 抢 Broker 分布式锁（L74-85）；
- 消费线程内先拿**队列级 JVM 锁**（`messageQueueLock.fetchLockObject`），再检查 `processQueue.isLocked() && !isLockExpired()`（30s），不满足就 10ms 后重试（L396-419）——Rebalance 把队列分给别人后，旧 owner 的锁过期，自动停手；
- 取消息用 `takeMessages(batchSize)` 把队首 N 条挪进 `consumingMsgOrderlyTreeMap`（`ProcessQueue.java:312-333`），消费失败时 `makeMessageToConsumeAgain` 放回去重试（L296-310）——**顺序模式下失败消息不回发 retry topic，而是原地挂起重试**（`SUSPEND_CURRENT_QUEUE_A_MOMENT`，挂起 `suspendCurrentQueueTimeMillis=1000ms`，L256-265），只有 `reconsumeTimes` 超限时才回发（此时队列被它卡住的部分继续挂起）；
- `maxReconsumeTimes` 语义不同：并发模式 -1 → **16**（`DefaultMQPushConsumerImpl.getMaxReconsumeTimes`，L890-897）；顺序模式 -1 → **Integer.MAX_VALUE**（`ConsumeMessageOrderlyService.java:313-320`）——顺序消费默认"无限重试保序"，业务必须自己设置上限，否则一条毒消息能挂死整条队列。

## 7.7 OffsetStore：内存、内存文件与 Broker

【源码证据】接口（`client/consumer/store/OffsetStore.java:38-82`）：`updateOffset / updateAndFreezeOffset / readOffset / persistAll / persist / removeOffset`。两种实现按 `MessageModel` 在 start 时选择（`DefaultMQPushConsumerImpl.java:952-967`）：

- **集群模式** `RemoteBrokerOffsetStore`：offset 存内存 `ConcurrentMap<MessageQueue, ControllableOffset>`（L46-47）；`updateOffset` 带 `increaseOnly` 只增语义（L59-74）；持久化两条路——`MQClientInstance` 每 5s 定时 `persistAllConsumerOffset`（上文 7.2 表）+ consumer shutdown 时 `persistConsumerOffset()` 再 `unregisterConsumer`（L903-913）。`persistAll` 把每个 mq 的 offset 经 `UPDATE_CONSUMER_OFFSET`（=15）提交给 Broker（L121-155，单 mq 失败仅记日志继续），Broker 存进 `ConsumerOffsetManager`（json 持久化）；拉取请求 sysFlag 带 commitOffset 位时 Broker 还会顺手记录 `commitPullOffset`（`PullMessageProcessor.tryCommitOffset`，L792-803）；
- **广播模式** `LocalFileOffsetStore`：写 `%USER_HOME%/.rocketmq_offsets/{clientId}/{group}/offsets.json`（L43-59，主文件损坏自动读 `.bak`）。**广播模式没有 Broker 端 offset，也没有重试队列**——失败消息不会回发（发送回发在广播模式被跳过），可靠性完全取决于本地磁盘，这是第八章 8.2.4 把广播模式列入"高危"的原因。

## 7.8 重试与死信：%RETRY% → SCHEDULE_TOPIC_XXXX → %DLQ%

消费失败不是"重新消费本地缓存的消息"，而是**走一遍完整的 Broker 存储流程**，用延迟消息实现指数退避：

1. 客户端 sendMessageBack（CONSUMER_SEND_MSG_BACK=36）→ Broker `SendMessageProcessor.consumerSendMsgBack` 分派（L93-94）；
2. `AbstractSendMessageProcessor.consumerSendMsgBack`（L132-228）核心判定：

```java
final String retryTopic = msgExt.getProperty(MessageConst.PROPERTY_RETRY_TOPIC);
if (null == retryTopic) {
    MessageAccessor.putProperty(msgExt, MessageConst.PROPERTY_RETRY_TOPIC, msgExt.getTopic());
}
...
int maxReconsumeTimes = subscriptionGroupConfig.getRetryMaxTimes();   // 默认 16
if (request.getVersion() >= MQVersion.Version.V3_4_9.ordinal()) {
    Integer times = requestHeader.getMaxReconsumeTimes();
    if (times != null) { maxReconsumeTimes = times; }
}
...
if (msgExt.getReconsumeTimes() >= maxReconsumeTimes || delayLevel < 0) {
    ... // 进死信
    newTopic = MixAll.getDLQTopic(requestHeader.getGroup());   // %DLQ%+consumerGroup
    queueIdInt = randomQueueId(DLQ_NUMS_PER_GROUP);            // 固定 1 个队列（L79）
} else {
    if (0 == delayLevel) {
        delayLevel = 3 + msgExt.getReconsumeTimes();           // 第 1 次重试 → level 3 = 10s
    }
    msgExt.setDelayTimeLevel(delayLevel);
}
...
msgInner.setReconsumeTimes(msgExt.getReconsumeTimes() + 1);    // L228
```

3. 延迟实现：消息写进系统 topic `SCHEDULE_TOPIC_XXXX`，`queueId = delayLevel - 1`（`broker/schedule/ScheduleMessageService.java:99-105`），18 个级别对应 `messageDelayLevel="1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 20m 30m 1h 2h"`（`MessageStoreConfig.java:261`）；`ScheduleMessageService` 每秒扫描各级别队列，到期消息恢复 `REAL_TOPIC` 写回 CommitLog 投递（`messageTimeUp`，L357-361），扫描位点 10s 持久化一次（`flushDelayOffsetInterval`，`MessageStoreConfig.java:262`）。所以第 1 次失败重试延迟 10s、第 2 次 30s……第 16 次 2h，全部失败进入死信；
4. 消费端订阅：消费者自动订阅 `%RETRY%+consumerGroup`（`DefaultMQPushConsumerImpl.copySubscription`），Broker 心跳处理时为每组自动创建 retry topic（`ClientManageProcessor.java:116-134`）；**`%DLQ%` topic 不会被自动订阅、也不会重投**——它是 RocketMQ 交给你的"最后滞留区"，不监控它，重试 16 次之后的消息就静默过期删除（4.7 节的 72h）。

## 7.9 本章小结

| 问题 | 答案（源码锚点） |
|---|---|
| Push 怎么实现 | 长轮询：拉空挂 Broker 15s，落盘即唤醒，5s recheck 兜底（`PullRequestHoldService.java:69-84`） |
| 队列怎么分配 | 排序 + AllocateMessageQueueStrategy 本地计算，无协调（`RebalanceImpl.java:297-325`） |
| 消费太慢怎么办 | 三级流控延迟 50ms 续拉：1000 条/100MiB/MaxSpan2000（`DefaultMQPushConsumerImpl.java:269-302`） |
| offset 何时推进 | 监听器返回后 removeMessage，取剩余最小 offset，只增（`ProcessQueue.java:187-222`） |
| offset 何时持久化 | 每 5s + shutdown 时提交 Broker（`MQClientInstance.java:417-423`） |
| 失败消息去哪 | 回发 %RETRY%，延迟 3+reconsumeTimes 级别退避，16 次进 %DLQ%（`AbstractSendMessageProcessor.java:181-228`） |
| 顺序怎么保证 | Broker 队列锁（20s 续租）+ 失败原地挂起重试（`ConsumeMessageOrderlyService.java:74-419`） |

第八章把这些机制拼成完整的可靠性方案。

---

# 八、可靠性专题：Client 与 RocketMQ 机制如何配合，做到"不丢"与"不重"

> 本章是全文的落点。前六章讲的是"机制是什么"，这一章讲"**Client 端必须怎样配置、怎样编码，才能与这些机制配合，让消息不丢、业务不重复处理**"。所有结论都能回溯到前六章的源码证据，引用处只标章节。

## 8.1 先把话说满：RocketMQ 承诺什么、不承诺什么

官方对投递语义的承诺是 **at-least-once**（至少一次）——`docs/cn/features.md:31`："Consumer先Pull消息到本地，消费完成后，才向服务器返回ack，如果没有消费一定不会ack消息"。这个承诺的完整含义是三层：

1. **不丢的前提是你把三段合同都签满**（8.2）：Producer 确认策略 + Broker 刷盘/复制 + Consumer 确认时机，任何一段用默认值偷懒，丢失都可能发生在那一段；
2. **"不重"不在承诺范围内**：重复在分布式消息系统里是**不可避免的存在**（8.3.1 列出五个来源，每一个都能在前六章找到源码出处），RocketMQ 在 Broker/协议层不提供去重，**防重复的唯一正解是消费端幂等**；
3. **exactly-once 不可能靠消息系统单方面实现**：消息的"被处理"发生在业务系统里（写库、调接口），消息系统最多管到"投递"，管不到"处理"。Kafka 的 EOS（幂等 Producer + 事务）能把"读-处理-写"闭环锁在"Kafka 流 → Kafka 流"内部，一旦处理涉及外部数据库，两阶段提交的成本与阻塞让消息层 exactly-once 变得不可行——RocketMQ 的选择是干脆不做，把幂等交给业务（这是架构取舍，不是缺陷）。

一句话总纲：**"不丢"靠把 RocketMQ 的可靠性机制全部打开；"不重"靠在消费端为每条消息找一个天然的或约定的唯一键。**

## 8.2 "不丢"的完整链路：逐段定位丢失点并封堵

一条消息要到达"业务处理完成"，要跨三段：**Producer → Broker 存储 → Consumer 处理**。下表先给出全景，后面逐段展开。

| 阶段 | 丢失场景 | 封堵手段 | 源码依据 |
|---|---|---|---|
| Producer→Broker | oneway 发送，网络抖动即丢 | 禁用 oneway（关键业务） | 3.4 节：oneway 无响应无重试 |
| Producer→Broker | 异步发送失败回调被忽略 | 回调中兜底：重发或落本地重发表 | 6.3 节：耗尽才回调 onException，不抛异常 |
| Producer→Broker | 非 SEND_OK 三态被当成功 | 开 `retryAnotherBrokerWhenNotStoreOK` + 代码里检查 SendStatus | 6.2/6.4 节：Broker sendOK=true 三态 |
| Producer→Broker | 重试次数被超时预算截断 | 调大 `sendMsgTimeout` 或减少重试次数 | 6.2 节：总超时共享预算 |
| Broker 存储 | ASYNC_FLUSH 断电丢 ≤500ms | `flushDiskType=SYNC_FLUSH` | 4.3 节：GroupCommitService |
| Broker 存储 | master 写完未复制，master 报废 | `brokerRole=SYNC_MASTER` 或 Controller 模式 | 5.3/5.4 节 |
| Broker 存储 | Controller 模式副本数不足仍确认 | `allAckInSyncStateSet=true` 等 in-sync 参数 | 5.3 节：IN_SYNC_REPLICAS_NOT_ENOUGH |
| Consumer 处理 | 回调里"先返回成功后异步处理"，进程崩溃 | **处理完成才返回 CONSUME_SUCCESS** | 7.5 节：返回即 removeMessage 推进 |
| Consumer 处理 | 消费异常被 catch 吞掉后返回成功 | 异常时返回 RECONSUME_LATER（或不 catch） | 7.5 节：null/异常才回发 |
| Consumer 处理 | 重试 16 次进 DLQ 后无人处理 | 监控 %DLQ% 堆积 + 人工/自动补偿 | 7.8 节：DLQ 不自动重投 |
| Consumer 处理 | 广播模式本地 offset 文件损坏 | 广播模式慎用；重要业务用集群模式 | 7.7 节：LocalFileOffsetStore |

### 8.2.1 Producer 端：四条纪律

**纪律一：关键业务只用 SYNC（或管好回调的 ASYNC），禁用 ONEWAY。**
ONEWAY 在协议层就没有"确认"这个概念（3.4 节 `invokeOnewayImpl` 写完即释放信号量，失败只打 warn）。日志型/指标型消息可以用，业务事实永远不行。

**纪律二：把三个"疑似成功"状态当失败处理。**
`SendStatus` 四态中只有 `SEND_OK` 是干净的成功（6.4 节）。`FLUSH_DISK_TIMEOUT/FLUSH_SLAVE_TIMEOUT/SLAVE_NOT_AVAILABLE` 的真相是"**消息已写入 CommitLog，但没等到刷盘/复制确认**"——它可能丢，也可能没丢，**正因结果不确定，它同时是丢失和重复的共同源头**。标准做法两层：

```java
producer.setRetryAnotherBrokerWhenNotStoreOK(true);   // 让客户端把三态当失败自动重试（换 Broker）
```

同时在代码里检查：

```java
SendResult result = producer.send(msg, 5000);
if (result.getSendStatus() != SendStatus.SEND_OK) {
    // 开了 retryAnotherBrokerWhenNotStoreOK 时通常到不了这里；到得了说明重试也失败了：
    // 落本地"待确认表"（含业务唯一键 + 消息内容），由定时任务补偿重发。
    pendingStore.save(bizId, msg);
}
```

⚠️ 注意配比：三态意味着"消息可能已在 Broker"，补偿重发会**制造重复**——所以补偿表场景必须与 8.3 的消费幂等配套。**不要**为了"不重复"而不重试：丢和重之间，业务几乎总是应该选重（幂等成本低，丢失不可逆）。

**纪律三：给重试留出时间预算。**
`sendMsgTimeout` 是整次 send 的总预算（6.2 节），默认 3000ms。默认 2 次重试下，若第一台 Broker 网络黑洞耗完 3 秒，后两次重试一次都不会发生（`timeout < costTime` 直接 break）。跨机房/弱网环境建议 `sendMsgTimeout ≥ 5000`；同时开启故障规避（`sendLatencyFaultEnable` 及系统属性 `com.rocketmq.startDetectorEnable=true`），让慢 Broker 主动被隔离（6.3 节）。

**纪律四：发送失败的最终兜底落在本地，而不是日志里。**
无论同步重试耗尽、异步回调 `onException`、还是三态确认失败，最后的手段是把消息（带业务唯一键）写进本地存储由补偿任务重发。只打日志的"不丢"是伪命题。若业务侧本来就有"业务事实表 + 发件箱"结构，直接用 8.2.5 的事务消息替代这套手工逻辑。

### 8.2.2 Broker 端：刷盘与复制的两个开关

**开关一：`flushDiskType=SYNC_FLUSH`（断电不丢的充分条件）。**
默认 `ASYNC_FLUSH` 下，FlushRealTimeService 每 500ms 才 force 一次（4.3 节），主机断电时最多丢 500ms 内写入且**客户端已收到 SEND_OK 的消息**——注意此时客户端认为发送成功了，这是最隐蔽的一类丢失。SYNC_FLUSH 后，发送线程被 GroupCommitService 组提交（10ms 窗口合并 force）阻塞到 `flushedWhere >= 消息末尾` 才返回（4.3 节源码），force 失败/超时返回 `FLUSH_DISK_TIMEOUT`。代价是单机写吞吐下降一个量级——这正是 Broker 端以性能换持久性的第一个选择点。

**开关二：`brokerRole=SYNC_MASTER`（master 报废不丢），或直接上 Controller 模式。**
默认 `ASYNC_MASTER` 下消息写给本机即回执，master 物理报废时未复制到 slave 的消息全丢。`SYNC_MASTER` 下发送线程等 slave ack（`GroupTransferService`，`slaveTimeout=3000ms`）才放行（5.3 节）。Controller 模式下进一步用 SyncStateSet 确认（`IN_SYNC_REPLICAS_NOT_ENOUGH`），并要求严格参数组合：

```properties
# 可靠组合（传统主从）
flushDiskType=SYNC_FLUSH
brokerRole=SYNC_MASTER

# 或 Controller 模式（5.x，自动切换 + 多数确认）
enableControllerMode=true
allAckInSyncStateSet=true          # 等 SyncStateSet 全部 ack（默认 false 只等 1 个 slave）
inSyncReplicas=2                   # in-sync 最小副本数
minInSyncReplicas=1                # 可容忍的最低 in-sync 数（低于则拒绝写入）
```

**不要用"环境决定论"欺骗自己**：测试环境 ASYNC_FLUSH + 生产环境忘了改、`brokerFastFailureEnable` 触发 SYSTEM_BUSY 被上游静默吞掉，都是真实事故的常见形态。Broker 端还有两个与"不丢"相关的次级开关：`transientStorePoolEnable=true`（写堆外 buffer，降低 PageCache 抖动，配合 SYNC_FLUSH 使用时注意它多了一级 commit 延迟，4.3 节）与磁盘水位（85/90 水位会直接拒写，4.7 节——监控磁盘本身就是可靠性工程）。

### 8.2.3 Consumer 端：确认时机是唯一的安全绳

RocketMQ 消费端"不丢"的全部机制，浓缩成一句话：**offset 只在监听器返回后被推进，且回发失败的消息会本地重投**（7.5 节 `processConsumeResult` 源码）。与之配套的使用纪律：

**纪律一：处理完成才返回 CONSUME_SUCCESS。**
`ConsumeMessageConcurrentlyService.processConsumeResult` 在你返回的瞬间就 `removeMessage` 推进 offset（7.5 节）。因此：

- ❌ 反模式：回调里把消息扔进自己的内存队列/线程池，立即返回 CONSUME_SUCCESS——进程崩溃时这些消息**永久丢失**（offset 已推进、Broker 认为你消费完了）。且内部线程池被打满时 `RejectedExecutionException` 会直接把消息炸掉；
- ❌ 变体：`RECONSUME_LATER` + 本地异步重试——回发已发生，消息进入延迟队列，本地那份若失败就两份都不完整；
- ✅ 正解：要异步就交给 RocketMQ——把"下一步工作"作为**新消息发出去**（生产者幂等由 8.3 保证），当前消息返回 CONSUME_SUCCESS；或者干脆同步处理完再返回。

**纪律二：异常要么不 catch，要么 catch 后返回 RECONSUME_LATER。**
监听器抛异常或返回 null 都会触发回发（7.5 节：null 视为 RECONSUME_LATER）。"catch (Throwable e) { log.error(...); return CONSUME_SUCCESS; }" 是把 RocketMQ 的重试机制整段短路——那条消息从此只存在于日志里。

**纪律三：把 DLQ 当作"必看的收件箱"而不是"垃圾桶"。**
重试 16 次（默认）后进 `%DLQ%consumerGroup`，**不会自动重投、不会被订阅**（7.8 节）。DLQ 有堆积 = 有消息在业务上彻底失败了，只是"没丢在 RocketMQ 手里、丢在流程设计里"。标准配置：对 `%DLQ%+consumerGroup` 建立堆积告警 + 补偿消费者（人工审核后重放，或自动修复后重投原 topic——重放时注意 8.3 幂等键仍然有效）。

**纪律四：消费超时与卡死有自己的出口，别依赖它兜底。**
单条消息消费超过 `consumeTimeout=15` 分钟会被 `ConsumeReturnType.TIME_OUT` 标记，超过 15 分钟还占着队首会被 `cleanExpiredMsg` 回发重投（7.5 节）。它的作用是防卡死，不是给慢业务续命的——单条消费稳定超过分钟级的业务应该拆消息/拆状态，而不是调大 consumeTimeout。

**纪律五：广播模式慎用。**
广播模式 offset 存本地 json 文件（7.7 节）、无重试队列、失败消息不回发——一台消费机的磁盘或进程问题就是这条消息在它那里的终点。除非"每台机器都必须处理一遍"是硬需求（如本地缓存刷新），否则用集群模式 + 按业务分 group。

### 8.2.4 事务消息：解决"DB 写成功、消息发不出"

8.2.1 的纪律四（本地兜底表）能手工实现"业务落库 + 发消息"的一致性，但样板代码多、补偿任务要自维护。RocketMQ 的事务消息（6.6 节）就是官方版的"发件箱模式（transactional outbox）"：

```java
TransactionMQProducer producer = new TransactionMQProducer("order_tx_group");
producer.setTransactionListener(new TransactionListener() {
    @Override
    public LocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        // ① 半消息已写入 Broker（对消费者不可见）
        try {
            orderService.createOrderAndBindTxId((Order) arg, msg.getTransactionId()); // ② 本地事务：订单落库
            return LocalTransactionState.COMMIT_MESSAGE;                              // ③ 提交 → 消息对消费者可见
        } catch (Exception e) {
            return LocalTransactionState.ROLLBACK_MESSAGE;                            // ② 失败 → 回滚半消息
        }
    }
    @Override
    public LocalTransactionState checkLocalTransaction(Message msg) {
        // ④ Broker 每 30s 回查一次、最多 15 次：查"这笔订单到底成没成"
        return orderService.existsTxId(msg.getTransactionId())
            ? LocalTransactionState.COMMIT_MESSAGE : LocalTransactionState.UNKNOW;
    }
});
```

实现要点（对应 6.6 节源码）：`executeLocalTransaction` 里做的本地事务必须**可查询**（回查时能独立判断结果——一般通过业务表上的 tx_id 字段或事务状态字段）；UNKNOW 表示"还没提交完"，Broker 会继续回查；回查 15 次仍不定即进半消息滞留（`transactionCheckMax=15`），需人工介入。**事务消息与消费幂等仍是互补关系**：它只解决"发"，不解决"收"。

### 8.2.5 端到端配置速查表

**Producer（代码/配置）**

| 配置 | 推荐值（不丢） | 默认值 | 理由（章节） |
|---|---|---|---|
| 发送模式 | SYNC | — | oneway 必丢，异步需管回调（6.1） |
| `retryTimesWhenSendFailed` | 2~3 | 2 | 总 3~4 次（6.2） |
| `sendMsgTimeout` | ≥5000（弱网） | 3000 | 重试需要时间预算（6.2） |
| `retryAnotherBrokerWhenNotStoreOK` | **true** | false | 三态不自动重试是最大陷阱（6.2） |
| `sendLatencyFaultEnable` + `com.rocketmq.startDetectorEnable` | true | false | 隔离慢/死 Broker（6.3） |
| 异步场景 | 回调 onException 落补偿表；开 `enableBackpressureForAsyncMode` | — | 回调不抛异常（6.3） |

**Broker（broker.conf）**

| 配置 | 推荐值（不丢） | 默认值 | 理由（章节） |
|---|---|---|---|
| `flushDiskType` | SYNC_FLUSH | ASYNC_FLUSH | 断电丢 ≤500ms（4.3） |
| `brokerRole` | SYNC_MASTER 或 Controller | ASYNC_MASTER | master 报废不丢（5.3） |
| Controller 模式 | `allAckInSyncStateSet=true`、`inSyncReplicas≥2` | false/1 | in-sync 确认（5.3） |
| `syncFlushTimeout` / `slaveTimeout` | 默认 5000 / 3000 即可 | 5000/3000 | ⚠️ 两套超时已分离（5.3） |
| `autoCreateTopicEnable` | 生产环境 false | true | 防止建题集中单 Broker（6.4） |
| 磁盘水位监控 | 85%/90% 告警 | — | 水位越限拒写（4.7） |

**Consumer（代码/配置）**

| 配置/行为 | 推荐值（不丢） | 默认值 | 理由（章节） |
|---|---|---|---|
| 返回值语义 | 处理完成才 CONSUME_SUCCESS；失败/异常 RECONSUME_LATER | — | 返回即推进 offset（7.5） |
| 回调内异步 | **禁止**"先返回后处理" | — | 7.5 纪律一 |
| `%DLQ%+group` 监控 | 必配 | 无 | DLQ 不自动重投（7.8） |
| `maxReconsumeTimes` | 按业务上限（防毒消息） | 并发 16 / 顺序 ∞ | 7.6/7.8 |
| `consumeThreadMin/Max` | 按 DB 连接池等下游容量 | 20/20 | 下游打满会连锁超时 |
| 广播模式 | 慎用 | CLUSTERING | 本地 offset 无重试（7.7） |

## 8.3 "不重"的真相：重复不可避免，幂等才是答案

### 8.3.1 重复从哪来：五个源码级来源

| # | 来源 | 触发链路 | 源码锚点 |
|---|---|---|---|
| 1 | **生产端重试** | 网络抖动时 Broker 已落盘但 ACK 丢失/超时 → 客户端重试 → Broker 出现两条内容相同的消息（UNIQ_KEY 相同、offsetMsgId 不同） | 6.2 节重试循环；6.5 节双 msgId |
| 2 | **三态确认后的重试** | FLUSH_DISK_TIMEOUT 等"可能已成功"的状态被当作失败重发（开了 retryAnotherBrokerWhenNotStoreOK 或补偿表） | 6.4 节 sendOK=true；8.2.1 纪律二 |
| 3 | **消费端 Rebalance** | 消费进行中 offset 尚未持久化（5s 窗口，7.2 节），队列易主后新 owner 从旧 offset 重新拉取——**已处理未提交的部分被再次投递** | 7.3 节 updateProcessQueueTable；7.2 节 5s 定时 |
| 4 | **消费回发的多路径** | 回发失败本地 5s 重投（7.5 节）；`cleanExpiredMsg` 对长期未消费消息回发（7.5 节）；重试消息本身再次失败再回发 | 7.5/7.8 节 |
| 5 | **offset 回退与重启** | OFFSET_ILLEGAL 修正（7.4 节）、广播模式本地文件回退（7.7 节）、消费者异常退出后从 Broker 记录位重启 | 7.4/7.7 节 |

结论：**五个来源没有一个能靠客户端"小心"消除**——#1 #2 是网络/时间的固有不确定性，#3 是 at-least-once 的结构性代价（offset 提交永远滞后于处理）。防重复的层级选择只有两个：消息层去重（Broker 侧，RocketMQ 未提供，Kafka 幂等 Producer 也只覆盖单分区内生产端）或**业务层幂等（唯一可行且推荐）**。

### 8.3.2 幂等键怎么选

- **首选业务唯一键**：订单号、支付流水号、事件 ID（往往就是消息的业务负载里本来就有）。它天然跨"重发/重投/重放"所有场景稳定——**消息在 Broker 里有几条副本都无所谓，幂等键相同就只处理一次**；
- **次选 `msg.getMsgId()`（UNIQ_KEY）**：生产端重试场景下稳定（`setUniqID` 只写一次，6.5 节）。但注意两个坑：① 若消息经"死信重放/人工重投"等旁路重新创建，UNIQ_KEY 会变；② 用它去重时无法识别"业务上同一件事被发成了两条不同消息"（上游 bug 或人为双发）；
- **永远不要用** `offsetMsgId`（换 Broker 重发就变，6.5 节）和 `queueOffset`（按队列局部递增，跨队列/重投无意义）；
- 习惯做法：**业务唯一键同时写进消息的用户属性 `Keys`**（`msg.setKeys(bizId)`），让 `INDEX` 查询与幂等表可以用同一个键（4.5 节）。

### 8.3.3 幂等实现模式：四种武器与选型

**模式一：数据库唯一约束（首选，凡消费逻辑写 DB 的场景）。**
把幂等键放进业务表主键/唯一索引，插入冲突即视为已处理。**消费逻辑与业务写入天然在同一事务里，这是唯一"零额外成本、强一致"的模式**：

```java
public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> msgs, ConsumeConcurrentlyContext ctx) {
    for (MessageExt msg : msgs) {
        String bizId = msg.getUserProperty("orderNo");          // 幂等键：业务唯一键
        try {
            // ① 幂等写入：唯一索引冲突 = 已处理过
            orderMapper.insertIgnore(new Order(bizId, parse(msg)));   // INSERT IGNORE / ON DUPLICATE KEY
            // ② 后续动作在同一本地事务里（扣库存等）
            inventoryService.deduct(bizId);
        } catch (DuplicateKeyException e) {
            log.info("duplicated message ignored, bizId={}", bizId); // 重复消息：直接吞掉
        } catch (Exception e) {
            return ConsumeConcurrentlyStatus.RECONSUME_LATER;        // 真失败：交给 RocketMQ 重试
        }
    }
    return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
}
```

**模式二：独立去重表 + 业务事务（业务动作不落本库/无唯一键可用时）。**
`dedup(biz_id PK, consume_time)` 与业务动作放同一个本地事务：先 insert dedup（冲突则整事务回滚并返回成功），再执行业务。与模式一等价，只是把"幂等键"单独存放，适合"消息驱动的动作用户根本不落这个库"（如发短信、调外部接口前先在本库登记）：

```java
@Transactional
public void handleOnce(String bizId, Runnable action) {
    try {
        dedupMapper.insert(new Dedup(bizId));   // 唯一键冲突 → 抛异常 → 事务回滚
    } catch (DuplicateKeyException e) {
        return;                                  // 已处理过
    }
    action.run();                                // 与 dedup 记录同事务提交
}
```

⚠️ 注意：`action` 若含外部调用（RPC/发短信），外部调用不可回滚——事务提交前外部已发生、提交失败时会"外部已执行但 dedup 未记录"，此时 RocketMQ 会重投，外部调用必须自身可容忍重放（或把外部调用做成两步：本库先记"待执行"，定时任务执行后回写）。

**模式三：Redis SETNX + 过期（高吞吐、允许极小概率重复时）。**
`SET dedup:{bizId} 1 NX EX 86400` 抢占式去重，TTL 控制去重窗口。**必须知道它的三个缺口**：SETNX 与后续业务动作不是原子的（SETNX 成功后进程崩溃 → 这条消息永远"已去重"但没处理——把不丢变成了丢）；Redis 主从切换可能丢锁；过期窗口外的重投（如 16 次重试跨 2h+、DLQ 人工重放）会穿透。适合"重复成本低、吞吐极高"的场景（如统计计数），**不适合**"重复即事故"的场景（扣款、库存）。

**模式四：状态机幂等（有状态流转的业务，零额外存储）。**
`UPDATE orders SET status='PAID' WHERE order_no=? AND status='UNPAID'`——乐观更新影响行数为 0 即重复。订单/工单/审批类业务的天然幂等形态，常常与模式一组合。

选型一句话：**写 DB 的用唯一约束/状态机；不写 DB 的高频动作用 Redis；外部副作用先落库再执行。** 所有模式的公共前提是 8.2.3 纪律一——幂等检查和业务动作都在"返回 CONSUME_SUCCESS 之前"完成。

### 8.3.4 顺序消费与幂等的关系

顺序消费（`MessageListenerOrderly`）用 Broker 队列锁 + 原地挂起重试把"乱序导致的重复处理面"压到最小（7.6 节），但**它不消灭重复**：队列锁交接（30s 锁过期、Rebalance）依然有 offset 未提交窗口。另外记住顺序模式的两个坑：默认 `maxReconsumeTimes=-1 → 无限重试`，毒消息会挂死整条队列（必须显式设置上限）；挂起重试期间该队列后续消息全部排队，幂等检查的延迟也会被放大。**顺序性需求来自"状态机按事件序演进"的业务（如账户流水），这类业务的状态机判版本/判序号本身就是幂等实现**——所以顺序消费与模式四天然是一对。

### 8.3.5 完整示例：可靠 Producer + 幂等 Consumer（拼图）

```java
// ============ Producer：三态兜底 + 补偿表 ============
DefaultMQProducer producer = new DefaultMQProducer("biz_producer");
producer.setRetryTimesWhenSendFailed(2);
producer.setSendMsgTimeout(5000);
producer.setRetryAnotherBrokerWhenNotStoreOK(true);          // 8.2.1 纪律二
producer.start();

public void notifyOrderPaid(Order order) {
    Message msg = new Message("ORDER_PAID", "PAID", order.getOrderNo(),
        JSON.toJSONBytes(order));
    msg.setKeys(order.getOrderNo());                          // 幂等键进 Keys（8.3.2）
    try {
        SendResult r = producer.send(msg);
        if (r.getSendStatus() != SendStatus.SEND_OK) {
            outboxTable.save(order.getOrderNo(), msg);        // 落发件箱，定时重发（8.2.1 纪律四）
        }
    } catch (Exception e) {
        outboxTable.save(order.getOrderNo(), msg);            // 重试耗尽/超时 → 发件箱
    }
}
// 或直接用 8.2.4 的事务消息替代 outbox 手工逻辑

// ============ Consumer：唯一约束幂等 + 异常回发 ============
DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("order_paid_consumer");
consumer.registerMessageListener((MessageListenerConcurrently) (msgs, ctx) -> {
    try {
        for (MessageExt m : msgs) {
            String bizId = m.getUserProperty("orderNo");      // 或 m.getKeys()
            orderPaidService.handleOnce(bizId, parse(m));     // 模式一/二：唯一约束 + 同事务
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;     // 处理完成才返回（8.2.3 纪律一）
    } catch (DuplicateKeyException dup) {
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;     // 重复消息：吞掉
    } catch (Throwable e) {
        log.error("consume failed, will retry via retry topic", e);
        return ConsumeConcurrentlyStatus.RECONSUME_LATER;     // 真失败：RocketMQ 退避重试
    }
});
```

## 8.4 事故案例速查

| 事故现象 | 根因（源码级） | 修复 |
|---|---|---|
| 生产恢复后少量"发送成功"的消息消失 | Broker ASYNC_FLUSH 断电，≤500ms 未 force（4.3 节） | `flushDiskType=SYNC_FLUSH`；重要链路加 Controller/SYNC_MASTER |
| master 宕机切换后，部分已回执消息丢失 | ASYNC_MASTER，未复制的部分（5.2 节） | `brokerRole=SYNC_MASTER` 或 Controller 模式 |
| 消费者线程池打满后部分消息"没消费过" | 回调里内部线程池异步处理 + 先返回 CONSUME_SUCCESS，拒绝时消息炸掉（7.5 节反模式） | 禁止先返回后处理；用 RocketMQ 重试或消息链 |
| 每次发布重启后一小批消息重复消费 | offset 5s 持久化窗口 + shutdown 未优雅关闭（7.2/7.7 节） | 幂等（8.3）；优雅停机（kill -SIGTERM，让 persistConsumerOffset 执行） |
| 发送方日志大量 `FLUSH_DISK_TIMEOUT` 后 DB 里部分记录没发出去 | 三态被当成功直接返回（6.4 节），上游误判 | `retryAnotherBrokerWhenNotStoreOK=true` + 补偿表（8.2.1） |
| 一条毒消息让某队列停止消费数小时 | 顺序消费 `maxReconsumeTimes=-1` 无限挂起重试（7.6 节） | 显式设置 `maxReconsumeTimes`；毒消息进 DLQ 后人工处理 |
| 消费 lag 突然清零但下游没收到对应数据 | `%DLQ%` 堆积被忽略，16 次重试后消息静默滞留（7.8 节） | DLQ 告警 + 重放流程（重放依赖幂等键仍有效） |
| Redis 去重后消息"被处理了却没效果" | SETNX 成功后进程崩溃，消息被永久标记已处理（8.3.3 模式三缺口） | 关键业务改 DB 唯一约束 + 同事务 |

## 8.5 常见论述的精确化：通用 MQ"三段论"对照 RocketMQ 源码事实

流传很广的一套可靠性论述——"发送端用事务消息或本地消息表、服务端配置刷盘与同步复制、消费端手动确认 + 幂等，最后权衡可靠性与性能"——**大框架是对的**，与本章 8.2/8.3 的结构同构。但它混合了 RabbitMQ / Kafka / RocketMQ 三家的术语，逐条对照 RocketMQ 源码，有五处需要精确化：

| # | 流行论述 | 判定 | RocketMQ 的精确事实 |
|---|---|---|---|
| 1 | "发送端要结合**事务消息或本地消息表**，保证本地事务和消息发送的原子性" | ✔ 框架正确，但只说了一半 | 对应 8.2.1 纪律四 + 8.2.4。它覆盖的是"**业务 ↔ 消息**"的原子性；发送端还有另一半丢失面——"**消息 ↔ 网络确认**"：oneway 无确认（6.1）、`FLUSH_DISK_TIMEOUT` 等三态默认不重试（6.2/6.4）、异步回调重试耗尽才通知（6.3）。两段都做满，发送端才算可靠 |
| 2 | "绝大多数 MQ 默认**异步刷盘**，消息先写页缓存，**断电宕机**页缓存里的消息**全部丢失**" | ◐ 方向对，两处不精确 | ①"宕机"与"断电"必须分开：进程崩溃时 OS 还活着、PageCache 仍在，**ASYNC_FLUSH 下进程宕机并不丢**，主机断电才丢（4.3）；②丢的是"尚未 force 的尾部"——默认最多一个刷盘周期（500ms），不是"全部"。另注：Kafka 根本没有刷盘开关，可靠性完全建立在副本机制上 |
| 3 | "主节点还没**同步给从节点**就挂了，**从节点升主**后未同步的消息丢失，必须同步复制或多副本强一致" | ✔ 结论正确，"升主"叙事不属于传统 RocketMQ | RocketMQ 传统主从**不会自动升主**（人工改配置重启）；它的"同步复制"是 `brokerRole=SYNC_MASTER`——发送线程等 slave ack（`slaveTimeout=3000ms`）才回执（5.3）；**自动切换 + 多数确认**是 5.x Controller 模式（SyncStateSet / `allAckInSyncStateSet` / confirmOffset，5.4）。"从节点自动升主"的说法来自 Kafka 的 controller 选举或 RabbitMQ quorum queue |
| 4 | "很多人默认用**自动确认**模式，消息一投递就标记为消费成功" | ◐ 术语平移 | autoAck 是 RabbitMQ/AMQP 的概念，**RocketMQ 没有这个开关**——Push 消费者的默认语义就是"处理完才确认"（监听器返回值即 ack，7.5）。它在 RocketMQ 中对应的真实反模式是"**先返回 CONSUME_SUCCESS 再异步处理**"（8.2.3 纪律一） |
| 5 | "就算用手动确认，业务异常**既没回滚也没发送失败确认**，消息也会被静默标记为已消费" | ◐ RocketMQ 中要换一种表述 | RocketMQ 不存在"悬空确认"中间态：监听器抛异常或返回 null 都会被框架转成 RECONSUME_LATER 走重试（7.5）。隐性丢失的确切形态是 **catch 吞掉异常后仍返回 CONSUME_SUCCESS**（8.2.3 纪律二），以及重试 16 次进 `%DLQ%` 后无人处理（7.8）。（在 RabbitMQ 语境下这句反而不成立：manualAck 忘记 ack 的消息会保持 unacked，连接断开后重投） |
| 6 | "**同步刷盘、同步复制会大幅降低吞吐**，要结合业务权衡" | ✔ | 组提交已把 SYNC_FLUSH 的代价从"每条一次 fsync"降到"10ms 窗口一次"（4.3），但仍比异步低一个量级；8.2.5 的配置速查表就是这份权衡的成品清单 |
| 7 | "**手动确认处理不好会导致消息重复消费**" | ◐ 归因不准 | 重复不是"确认方式"造成的，而是 at-least-once 的**结构性代价**——offset/ack 的提交永远滞后于业务执行（8.3.1 的五个来源，任何 MQ 都一样）；"先处理后确认"恰恰是**缩小**重复窗口的正确姿势。重复无法靠确认方式消除，只能幂等（8.3） |
| 8 | "生产环境三件套：发送端事务消息/本地消息表 + 服务端刷盘与多副本 + 消费端确认 + 幂等兜底" | ✔ | 与 8.2.5 配置速查表、8.6 检查清单一一对应；建议再补两项常被漏掉的"最后一公里"：`%DLQ%` 堆积监控（7.8）与磁盘水位告警（4.7） |

一句话总括：这套论述作为"通用 MQ 面试答案"是及格的；落到 RocketMQ 上，把"自动/手动确认"翻译成"**返回值即确认**"、把"断电丢页缓存"收窄成"**断电丢未 force 尾部**"、把"同步复制"落到 **SYNC_MASTER/Controller 具体参数**，它就从及格变成了精确。

## 8.6 上线前检查清单

1. [ ] 关键链路无 ONEWAY 发送；异步发送的 `onException` 有补偿落库；
2. [ ] `retryAnotherBrokerWhenNotStoreOK=true`，代码检查 `SendStatus != SEND_OK` 的兜底逻辑；
3. [ ] Broker：`flushDiskType=SYNC_FLUSH`；`brokerRole=SYNC_MASTER` 或 `enableControllerMode=true`（配 `allAckInSyncStateSet`）；
4. [ ] `sendMsgTimeout` 与网络环境匹配（弱网 ≥5000）；故障规避开关已评估；
5. [ ] 消费回调：处理完成才返回 CONSUME_SUCCESS；异常/失败返回 RECONSUME_LATER；无"先返回后异步"；
6. [ ] 幂等方案落地且与业务事务同库同事务（或等价强度）；幂等键用业务唯一键并写入 `Keys`；
7. [ ] 顺序消费者显式设置 `maxReconsumeTimes`；
8. [ ] `%DLQ%+consumerGroup` 堆积告警 + 人工/自动重放流程；
9. [ ] 磁盘水位（85%/90%）与 `OS_PAGE_CACHE_BUSY`、SYSTEM_BUSY 告警；
10. [ ] 停机发布走优雅关闭（offset persist + unregister），并在发布演练中验证重复消费不影响业务。

## 8.7 本章小结

- **不丢 = 三段合同全签满**：Producer（SYNC + 三态兜底 + 补偿表/事务消息）→ Broker（SYNC_FLUSH + SYNC_MASTER/Controller）→ Consumer（处理完才确认 + DLQ 监控）。每一段的默认值都是"性能优先"，可靠性是逐项打开出来的；
- **不重 = 接受 at-least-once + 消费端幂等**：五个重复来源全部不可消除；幂等键用业务唯一键，实现首选 DB 唯一约束与业务同事务；Redis 去重只用于重复低危场景；
- **丢失与重复互为代价，幂等是解开两难的总钥匙**：正因为消费幂等成本足够低，才敢在发送端把三态当失败重试、在消费端把 offset 滞后 5s——整条链路的设计自由度都建立在"重复是安全的"这个前提上。

---

# 九、5.x 新架构与生态（概览）

> 本章对应源码：`proxy`、`rocketmq-proto`、`tieredstore`、`container`、`auth` 模块。5.x 的主线是"**协议现代化 + 存算演进**"，经典架构（第二~八章）原封未动。

## 9.1 Proxy 与 gRPC：多语言接入的新门面

5.x 在 Remoting 自研协议之外新增 gRPC 数据面，由 proxy 模块承担。启动入口 `ProxyStartup`（`proxy/.../ProxyStartup.java:217-269`）按 `proxyMode` 分两条路：

- **LOCAL 模式**：同进程内嵌 `BrokerStartup.createBrokerController` 启动的 Broker——"代理与存储同机部署"，小规模场景一个进程搞定；
- **CLUSTER 模式**（默认，`ProxyConfig.java:91`）：Proxy 独立部署为无状态接入层，可水平扩容——**存储计算分离的第一步**：客户端只认识 Proxy 集群，Broker 拓扑变化对客户端完全透明。

gRPC 侧是一个统一的 `MessagingService`（`GrpcMessagingApplication extends MessagingServiceGrpc.MessagingServiceImplBase`，L76），分发链 `DefaultGrpcMessagingActivity → SendMessageActivity / ReceiveMessageActivity / AckMessageActivity / ChangeInvisibleDurationActivity / EndTransactionActivity` → `DefaultMessagingProcessor` → Local/ClusterMessageService。协议 proto 源文件在独立仓库 rocketmq-apis（本仓库以 git submodule 引入，构建期生成 Java 类，`.gitmodules:13-15`）；**多语言客户端（Java/C++/Go/Python/Node 等）也全部在独立仓库 rocketmq-clients，本仓库只实现服务端协议**（README.md:182；example 模块无任何 gRPC import 可佐证）。

**SimpleConsumer** 是 gRPC 协议上的消费形态：`ReceiveMessage` 拉取消息时带**不可见时长（invisibleDuration）**——拉到即对其他消费者不可见，处理完调 `AckMessage`，处理不完可 `ChangeInvisibleDuration` 延长。相比经典 Push 消费者，它把"确认"从"offset 推进"改成了"逐条显式 Ack"，语义更贴近业务直觉（代价是每条消息一次 Ack RPC）。经典消费者、Pop 消费者（RequestCode 200050 家族）与 SimpleConsumer 是同一套存储上的三种门面。

## 9.2 类型化 Topic：把"怎么消费"写进 topic 属性

5.x 引入 `TopicMessageType`（`common/.../attribute/TopicMessageType.java:26-33`）：`NORMAL / FIFO / DELAY / TRANSACTION / PRIORITY / LITE / MIXED` 等，随 topic 属性持久化，消息侧对应属性 `MessageConst.PROPERTY_MESSAGE_TYPE = "MSG_TYPE"`（L62）。Broker 按 topic 类型决定行为：FIFO 走顺序投递、DELAY 走时间轮、TRANSACTION 走事务队列——**过去靠"用对了 API"保证的行为约束，现在由 topic 类型在服务端强制**（例如给 FIFO topic 发非顺序消息会被服务端拒绝）。

## 9.3 Timer 时间轮：任意精度定时消息

4.x 的延迟消息只有 18 个固定级别（7.8 节）；5.x 新增时间轮实现（`store/timer/TimerMessageStore.java`）：

- 结构：**TimerWheel**（mmap 时间轮，slot 数 = `TIMER_WHEEL_TTL_DAY(7) × DAY_SECS`，L100/174-199）+ **TimerLog**（每条定时消息真实落盘的物理记录）+ TimerCheckpoint（读进度/flush 位点）；
- 精度与上限：`timerPrecisionMs=1000`（1 秒精度，`MessageStoreConfig.java:71`）、最长延迟 `timerMaxDelaySec=259200`（3 天）、超过滚动窗口的消息在 wheel 上 roll 续期；
- 开关：`timerWheelEnable=true` 默认开启（L84）；发送时设置 `PROPERTY_TIMER_DELAY_SEC/MS` 或 `PROPERTY_TIMER_DELIVER_MS`（任意时间点）。
- 典型用途：订单 30 分钟关单、预订类业务提前提醒——过去要靠"轮询数据库"或外部调度器实现的东西。

## 9.4 分层存储与进程容器

- **tieredstore**：冷热分层插件，`TieredMessageStore extends AbstractPluginMessageStore`（`tieredstore/.../TieredMessageStore.java:66`）挂在 MessageStore 插件位上——新消息由 `MessageStoreDispatcherImpl` 异步上传冷存（对象存储/HDFS/本地二级盘），热数据满了不删而是下沉，读取按需回捞（`MessageStoreFetcherImpl`）。核心类：`FlatCommitLogFile/FlatConsumeQueueFile`（冷存上的扁平文件格式）、`IndexStoreService`（冷存索引）。它改变了 4.7 节"72 小时保留期"的约束：**热层保性能，冷层保合规/回溯**。
- **container**：`BrokerContainer`（`container/.../BrokerContainer.java:52`）单 JVM 托管多个 `InnerBrokerController`/`InnerSalveBrokerController`，共享一个 RemotingServer——云环境小规格 Broker 密部署的形态（proxy LOCAL 模式内嵌 Broker 用的就是它）。

## 9.5 auth：从 ACL 到策略框架

5.x 把 4.x 的 `PlainAccessValidator` ACL 体系整体替换为 `org.apache.rocketmq.auth.*` 新框架（authentication/authorization 两子域，账户/角色/策略模型）；旧 ACL 配置仅保留迁移类（`auth/migration/v1/PlainAccessResource.java` 等）。客户端侧签名钩子 `AclClientRPCHook`（`client/.../acl/common/AclClientRPCHook.java:27`，把 AccessKey/Signature 写进请求扩展字段）不变，服务端鉴权点仍是 3.5 节的 RPCHook 与 proxy 的 `AuthenticationPipeline/AuthorizationPipeline`。

## 9.6 本章小结

| 变化 | 一句话 | 影响 |
|---|---|---|
| Proxy/gRPC | CLUSTER 模式无状态接入层 + 多语言客户端（独立仓库） | 接入与存储解耦，跨语言成本骤降 |
| SimpleConsumer | invisible time + 显式 Ack | 消费语义更直白，可动态改超时 |
| TopicMessageType | topic 级类型约束 | FIFO/DELAY/事务行为服务端强制 |
| Timer 时间轮 | 1s 精度、最长 3 天的任意定时 | 关单/提醒类业务无需外部调度 |
| tieredstore | 冷热分层插件 | 保留期与磁盘容量解耦 |
| auth | 策略化认证授权框架 | 替代 4.x ACL |

---

# 十、贯通视图与附录

## 10.1 一条消息的一生（把九章串成一张图）

以"可靠配置"（第八章）下的一条消息为例，从 `producer.send(msg)` 开始：

```
Producer 进程                          NameServer                     Broker (Master)                       Consumer 进程
─────────────                          ──────────                     ───────────────                       ─────────────
send(msg)
 ├ setUniqID / 压缩 / Hook       ①路由查询(30s缓存)  GET_ROUTEINFO_BY_TOPIC
 ├ sendDefaultImpl 循环(1+2次) ────────────────────► ┌─ TopicRouteData ─┐
 │   selectOneMessageQueue(故障规避)                 └─ 五张内存表 ──────┘
 │   ◄──── 心跳注册：REGISTER_BROKER 每 30s（心跳即注册）────┐
 │                                                          │
 ├─ SEND_MESSAGE_V2(310) ──────────────────────────────────►│ SendMessageProcessor
 │                                                          │  msgCheck(权限/自动建题)
 │                                                          │  CommitLog.asyncPutMessage
 │                                                          │   ├ putMessageLock → append mmap ④
 │                                                          │   ├ SYNC_FLUSH: GroupCommitService force ⑤
 │                                                          │   ├ SYNC_MASTER: GroupTransferService 等 slave ack ⑥
 │                                                          │   └ ReputMessageService(1ms) → ConsumeQueue/Index ⑦
 │ ◄── SendResult(SEND_OK,msgId=UNIQ_KEY,offsetMsgId) ──────┘   └ 长轮询唤醒 NotifyMessageArrivingListener ⑧
 │
 │                                          PULL_MESSAGE(11) ◄── PullMessageService(自驱动循环)
 │                                              │ 流控三查(1000条/100MiB/Span2000)
 │                                              │ getMessage: ConsumeQueue 定位 → CommitLog 读
 │ ◄──────────────── 消息数据 ──────────────────┘
 ├ putMessage 进 ProcessQueue(TreeMap)
 ├ submitConsumeRequest → 消费线程池
 │   └ listener.consumeMessage ⑨  幂等检查+业务(同事务) → CONSUME_SUCCESS
 │       └ removeMessage → offset 只增推进 ⑩
 │           ├ 5s 定时 UPDATE_CONSUMER_OFFSET(15) → Broker ⑪
 │           └ 失败: CONSUMER_SEND_MSG_BACK(36) → %RETRY% → 延迟级别退避 → 16 次后 %DLQ% ⑫
```

编号对应：①②路由发现（第二章）；③发送选择队列与重试（第六章）；④⑤⑥存储与确认（第四、五章）；⑦⑧索引与可见性（第四章）；⑨⑩⑪消费与 offset（第七章）；⑫失败重试（第七章）。**第八章的可靠性配置，就是把 ④⑤⑥⑨⑩⑪⑫ 每一个环节的默认行为改成最保守的那一档，并给 ⑫ 之后留下人工出口。**

## 10.2 关键参数速查表

**协议常量（RequestCode，`remoting/.../protocol/RequestCode.java`）**

| 常量 | 值 | 用途 |
|---|---|---|
| SEND_MESSAGE / SEND_MESSAGE_V2 / SEND_BATCH_MESSAGE | 10 / 310 / 320 | 发送（V2 字段名压缩版） |
| PULL_MESSAGE | 11 | 拉取 |
| QUERY_MESSAGE | 12 | 按 Key 查询（IndexFile） |
| UPDATE_CONSUMER_OFFSET | 15 | 消费进度提交 |
| HEART_BEAT | 34 | 客户端心跳（含订阅） |
| GET_CONSUMER_LIST_BY_GROUP | 38 | Rebalance 取成员 |
| CONSUMER_SEND_MSG_BACK | 36 | 消费失败回发 |
| END_TRANSACTION / CHECK_TRANSACTION_STATE | 37 / 39 | 事务二阶段 / 回查 |
| LOCK_BATCH_MQ | 41 | 顺序消费队列锁 |
| REGISTER_BROKER / GET_ROUTEINFO_BY_TOPIC | 103 / 105 | 路由注册/查询 |
| POP_MESSAGE / ACK_MESSAGE / CHANGE_MESSAGE_INVISIBLETIME | 200050 / 200051 / 200053 | 5.x Pop 消费 |

**Producer 默认值（`DefaultMQProducer.java:113-150`）**

| 字段 | 默认 | 含义 |
|---|---|---|
| sendMsgTimeout | 3000ms | 同步发送总预算（重试共享） |
| retryTimesWhenSendFailed | 2 | 同步重试次数（共 1+N 次） |
| retryTimesWhenSendAsyncFailed | 2 | 异步重试次数（递归） |
| retryAnotherBrokerWhenNotStoreOK | false | 三态是否当失败重试 |
| compressMsgBodyOverHowmuch | 4096 | 压缩阈值 |
| maxMessageSize | 4MB | 单条上限 |

**Broker/Store 默认值（`common/.../BrokerConfig.java`、`store/.../config/MessageStoreConfig.java`）**

| 字段 | 默认 | 含义 |
|---|---|---|
| brokerRole / flushDiskType | ASYNC_MASTER / ASYNC_FLUSH | 复制/刷盘模式（可靠性章节重点） |
| syncFlushTimeout / slaveTimeout | 5000 / 3000 | 同步刷盘等待 / 同步复制等待（⚠️ 已分离） |
| flushIntervalCommitLog / commitIntervalCommitLog | 500 / 200ms | 异步刷盘周期 / commit 周期 |
| mappedFileSizeCommitLog / ConsumeQueue | 1GB / 6MB(30万×20) | 文件大小 |
| messageDelayLevel | 1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 20m 30m 1h 2h | 延迟/重试级别 |
| fileReservedTime / deleteWhen / diskMaxUsedSpaceRatio | 72h / "04" / 75 | 过期删除 |
| registerNameServerPeriod | 30s | Broker 心跳注册 |
| longPollingEnable | true | 长轮询 |
| transactionTimeOut / CheckMax / CheckInterval | 6s / 15 / 30s | 事务回查三参数 |
| waitTimeMillsInSendQueue / osPageCacheBusyTimeOutMills | 200ms / 1000ms | fast failure 流控 |

**Consumer 默认值（`DefaultMQPushConsumer.java:162-267`、`ProcessQueue.java:40-43`、`ClientConfig.java:56-66`）**

| 字段 | 默认 | 含义 |
|---|---|---|
| pullBatchSize / pullBatchSizeInBytes | 32 / 256KB | 单次拉取上限 |
| pullThresholdForQueue / SizeForQueue | 1000 条 / 100MiB | 流控一、二 |
| consumeConcurrentlyMaxSpan | 2000 | 流控三（并发模式） |
| consumeThreadMin/Max | 20 / 20 | 消费线程池 |
| consumeMessageBatchMaxSize | 1 | 单次消费条数 |
| maxReconsumeTimes | -1（并发 16 / 顺序 ∞） | 重试上限 |
| consumeTimeout | 15min | 消费超时（ConsumeReturnType/cleanExpireMsg） |
| suspendCurrentQueueTimeMillis | 1000ms | 顺序消费挂起间隔 |
| pollNameServerInterval / heartbeatBrokerInterval / persistConsumerOffsetInterval | 30s / 30s / 5s | 客户端三大周期 |
| REBALANCE_LOCK_INTERVAL / MAX_LIVE_TIME | 20s / 30s | 顺序消费队列锁 |

## 10.3 源码导航（类 → 职责速查）

| 类 | 模块 | 一句话 |
|---|---|---|
| RouteInfoManager | namesrv | 五张内存表，路由的全体 |
| RemotingCommand / NettyRemotingAbstract | remoting | 协议帧与三模式调用骨架 |
| DefaultMQProducerImpl | client | 发送重试/超时/故障规避/事务 |
| MQClientInstance | client | 客户端容器：五大周期任务 + 路由缓存 |
| DefaultMQPushConsumerImpl | client | Push 消费：拉取循环、流控、回调四分支 |
| RebalanceImpl / RebalancePushImpl | client | 队列分配与交接 |
| ConsumeMessageConcurrentlyService / OrderlyService | client | 两种消费语义 + offset 推进 |
| OffsetStore（Remote/Local） | client | 消费进度的内存/文件/Broker 三态 |
| CommitLog / DefaultMessageStore | store | 顺序写内核与消息读路径 |
| GroupCommitService / FlushRealTimeService / CommitRealTimeService | store(CommitLog 内部) | 同步/异步/堆外三种刷盘 |
| DefaultHAService / AutoSwitchHAService | store.ha | 主从复制 / Controller 模式 |
| DLedgerController / JRaftController | controller | Controller 仲裁的两种 Raft 实现 |
| SendMessageProcessor / AbstractSendMessageProcessor | broker | 发送校验/回执映射/重试死信投递 |
| PullMessageProcessor / PullRequestHoldService | broker | 拉取处理与长轮询挂起 |
| TransactionalMessageServiceImpl / EndTransactionProcessor | broker | 事务 half/op 队列与回查 |
| ScheduleMessageService | broker.schedule | 18 级延迟消息投递 |
| TimerMessageStore | store.timer | 5.x 时间轮定时 |
| TieredMessageStore | tieredstore | 冷热分层插件 |

## 10.4 学习路线建议

1. **跑通直觉**（半天）：官方 quickstart（`example/quickstart/Producer.java`、`Consumer.java`）双机起 NameServer+Broker，用 mqadmin 观察路由/消费位点/重试 topic；
2. **打通发送链路**（1 天）：读第六章 + 断点 `sendDefaultImpl → sendKernelImpl → MQClientAPIImpl.sendMessage → SendMessageProcessor → CommitLog.asyncPutMessage`，观察 SendStatus 四态与重试；
3. **打通消费链路**（1~2 天）：读第七章 + 断点 `PullMessageService → PullCallback → ConsumeRequest.run → processConsumeResult`，故意制造消费失败观察 `%RETRY%`/`SCHEDULE_TOPIC_XXXX`/`%DLQ%`；
4. **可靠性演练**（1 天）：按第八章清单逐项开启配置，演练断电（kill -9）、master 报废、消费崩溃三类故障，验证"不丢"与"重复后幂等生效"；
5. **深读存储**（按需）：第四章 + `DefaultMessageStore` 与 `CommitLog` 通读，理解 flush/commit/reput 三个位点（flushedWhere/committedWhere/reputFromOffset）；
6. **扩展视野**（按需）：第九章 5.x 新架构，gRPC 客户端到 rocketmq-clients 仓库体验 SimpleConsumer。

## 10.5 参考资料

- 本地源码：`D:\code\3rd\rocketmq`（master @ `37af1437`，5.5.1+）
- 官方设计文档：仓库内 `docs/cn/design.md`、`docs/cn/features.md`、`docs/en/Design_Store.md`、`docs/en/Design_Transaction.md`、`docs/cn/controller/design.md`
- 官方示例：`example/`（quickstart / simple / transaction / ordermessage）
- 多语言客户端：https://github.com/apache/rocketmq-clients （gRPC/protobuf，本仓库仅服务端）
- 版本发布记录：https://rocketmq.apache.org/download 及 ASF 董事会纪要（5.3.0：2024-07-16；5.4.0：2025-12-24；5.5.0：2026-04-10；5.5.1：2026-08-20）
- 关联文档：本仓库《Spring Framework.md》（文档结构范式）、《Spring for Apache Kafka.md》（同为日志型存储，partition 分文件 vs 单 CommitLog 的对照）、《cncf/一致性理论学习.md》（本文 8.1 节语义讨论的理论背景）
