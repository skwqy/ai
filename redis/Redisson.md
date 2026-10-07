# Redisson 深度源码解析（写给初学者的架构全景）

> **本文基于的源码**：Redisson **4.8.0**（2026-10-02 发布的最新稳定版，GitHub `redisson/redisson` tag `redisson-4.8.0`），源码按工作目录约定临时解压在 `D:\tmp\redisson-src`（可随时清理，重取该 tag 即可复现所有行号）。文中所有【源码证据】均为对该版本实际读取所得；**约定：未加前缀的源码路径一律相对于 `redisson/src/main/java/org/redisson/`**（core 模块根，下称 CORE），集成模块则写全相对路径（如 `redisson-spring/redisson-spring-boot-starter/...`）。
>
> **版本取舍说明**：Redisson 的核心骨架——"R 接口 + 异步命令引擎（CommandAsyncService→RedisExecutor）+ Netty 连接层 + Lua 脚本下沉"——自 3.x 以来高度稳定，2.x → 3.0（2016-10，全面转向 JDK 8 + CompletableFuture）与 3.x → 4.0（2025-12，清理型大版本）各做过一次大调整，因此本文内容对 3.4x/3.5x 用户同样适用；4.0 的破坏性变更（JSON 配置格式移除、`RFuture` 旧方法移除、认证/SSL/TCP/nameMapper 参数上收到 Config 级、Jackson 变为可选依赖等）单独立节标注（1.6 节）。需要说明：本文源码包不含 `.git`，版本演进结论以源码包内自带 `CHANGELOG.md`（逐版本记录，包含精确发布日期）+ GitHub Release 信息交叉核对，**未做逐 git tag 实证**，与姊妹篇《Redis.md》《Spring Framework.md》的取证严格程度略有差异，特此声明。
>
> **阅读约定**：每章"先白话、后源码"——先用一两句人话讲清"这是什么、为什么需要它"，再给出源码证据链（`文件名.java` + 类/方法 + 行号 + 代码片段）。行号只对 4.8.0 精确，读者重新获取源码后按类名 + 方法名定位即可。

## 如何读这份文档

如果你是 Redisson 初学者，推荐两遍读法：

- **第一遍（建立地图，1~2 小时）**：只读第一章（总览）每一节的开头白话段、各章的"小结"节、以及第十章（贯通视图）。目标是能回答：`redissonClient.getMap("m").get("k")` 这行同步代码底下发生了什么？`lock()` 为什么能在锁未释放时一直等、靠什么被唤醒、靠什么不因宕机而永久持有？同一个 Redisson 客户端怎么同时有 sync/async/reactive/rx 四套 API？
- **第二遍（深入源码）**：对照每一章的【源码证据】，打开源码逐行读。顺序建议：第三章（命令执行主线，一切请求的必经之路）→ 第五章（分布式锁，Redisson 的招牌）→ 第二章（连接层地基）→ 第四章（对象与集合体系，随用随查）→ 第六章（消息与任务调度）→ 第七~九章（事务、响应式、集成生态，可跳读）。

---

# 一、总览：Redisson 的定位、设计哲学与整体架构

## 1.1 一句话定位

**Redisson 是把 Redis（及 Valkey）的"命令面"翻译成 Java"对象面"的客户端 + 一个架在内存数据库之上的分布式并发工具箱**。官方 README 自述（`README.md:1`、`README.md:5`）：

```
# Redisson: Valkey & Redis Java Client<br>and Real-Time Data Platform
Redisson is the Java Client and Real-Time Data Platform for Valkey and Redis.
```

它解决的不是"怎么发一条 GET 命令"（那是 Jedis/Lettuce 的本职），而是三层更高层的问题：

1. **分布式并发原语**：锁（RLock/读写锁/公平锁/fencing token 锁）、信号量、闭锁、限流器——把 JVM 内的 `java.util.concurrent.locks` 家族"平移"到 Redis 上，互斥原子性靠 Lua 脚本、等待唤醒靠 pub/sub、续命靠看门狗；
2. **分布式对象与集合**：`RMap extends ConcurrentMap`、`RList extends List`、`RSet extends Set`……业务代码像操作本地集合一样操作分布在 Redis 里的数据，还附送读写分离、近缓存（LocalCachedMap）、条目级过期（RMapCache）、淘汰（EvictionScheduler）这些 Redis 原生命令不直接给的能力；
3. **应用框架集成**：Spring Cache/Spring 事务/Spring Boot 自动装配/spring-data-redis 适配、JCache（JSR-107）、Tomcat Session、MyBatis/Hibernate 二级缓存、Quarkus/Micronaut/Helidon——同一套内核，包一层"框架方言"。

一句话记住它与同类客户端的分野：**Jedis 给你连接，Lettuce 给你命令，Redisson 给你对象和语义**。

## 1.2 设计哲学：读源码前先记住五句话

1. **面向对象，而非面向命令**。`api` 包（388 个接口文件）几乎每个 R 接口都继承一个 JDK 集合/并发接口：【源码证据】`api/RMap.java:39` `public interface RMap<K, V> extends ConcurrentMap<K, V>, RExpirable, RMapAsync<K, V>, RDestroyable`。这意味着 `ConcurrentMap` 的使用习惯（含 `compute`、`merge`、`computeIfAbsent`）原样可用，业务代码与本地 `ConcurrentHashMap` 可近乎无痛替换——替换的代价是"每次调用都是一次网络往返 + 一次序列化"，这也是初学者最常见的性能误用（见 11.3 节）。
2. **一切皆异步，同步只是壳**。所有 R 接口的实现都只有一套异步内核：`CommandAsyncService` 把每个调用变成 `CompletableFuture`，`getMap(...).get(key)` 的 `get()` 只是阻塞等待这个 future（见 3.5 节）。Reactive/Rx 两套 API 更是连实现都"不写"——用动态代理把同步实现类实时转译成 Reactor `Mono/Flux` 与 RxJava3 类型（`reactive/ReactiveProxyBuilder.java:33-40`，见 8.1 节）。
3. **复合原子性下沉 Lua，客户端只做编排**。分布式锁的加锁/解锁、限流器的令牌计算、MapCache 的过期清理、延迟队列的搬运……凡是"读-判-写"必须原子的地方，Redisson 一律写成一个 Lua 脚本让 Redis 单线程原子执行（`RedissonLock.java:214-224` 的加锁脚本只有 7 行 Lua，却是整个分布式锁的互斥核心）。这个哲学与 Redis 官方"everything is atomic per command, use Lua for compound"的立场完全同构。
4. **Netty 是引擎，Redisson 是车身**。连接管理（`RedisClient`/`RedisConnection`）、RESP 编解码（`client/protocol`、`client/handler`）、命令回调派发、订阅消息分发、甚至定时任务（Netty 的 `HashedWheelTimer`，`connection/ServiceManager.java:304`）全部跑在 Netty 的事件循环上。Redisson 没有自造线程池——理解 Netty 的 EventLoop 模型，就读懂了它一半。
5. **显式声明服务端能力边界**。每个依赖新 Redis 命令的特性都注明版本下限（CHANGELOG 3.17.0 多处"requires Redis 7.0+"）；协议默认 RESP2、可切 RESP3（`config/Config.java:114` `private Protocol protocol = Protocol.RESP2;`）；对 Valkey 的兼容在 3.45.0 起以 `valkey://`/`valkeys://` scheme 显式支持（CHANGELOG 3.45.x）。客户端不"赌"服务端有什么，而是探测降级（如 3.6 节 `syncedEval` 对 `WAIT`/`WAITAOF` 的运行时探测）。

## 1.3 工程结构全景

Redisson 是多模块 Maven 工程，根 `pom.xml` 声明 9 个模块。按"用户感知"分层：

```
┌──────────────────────────── 集成层（framework 方言）────────────────────────────┐
│ redisson-spring（starter 自动装配 / spring-cache / spring-transaction /          │
│                  spring-data 子模块×19：适配 spring-data-redis 1.6 → 4.1）       │
│ redisson-tomcat（Session 复制）  redisson-mybatis / redisson-hibernate（缓存）    │
│ redisson-quarkus / redisson-micronaut / redisson-helidon（DI 容器原生集成）       │
├──────────────────────────── 聚合层 ──────────────────────────────────────────┤
│ redisson-all（core + 常用可选依赖打成一个 fat jar）                              │
├──────────────────────────── 内核（redisson 模块，即 CORE）─────────────────────┤
│ api/（388 个 R* 接口：对象面）      reactive/ rx/（响应式转译）                    │
│ Redisson*/RedissonXxx（实现类，位于 CORE 根）        liveobject/ remote/        │
│ command/（CommandAsyncService + RedisExecutor：异步执行引擎）                    │
│ connection/（ConnectionManager 家族 + 连接池 + ServiceManager 全局服务）          │
│ client/（RedisClient、netty pipeline、RESP 协议编解码、异常体系）                 │
│ codec/（38 个编解码器）  pubsub/（订阅基础设施）  renewal/（看门狗批量续期）        │
│ eviction/（惰性/定时淘汰）  transaction/（RTransaction）  executor/（任务调度）    │
│ config/（Config 家族）  misc/ jcache/ cache/ mapreduce/ iterator/ cluster/       │
└──────────────────────────────────────────────────────────────────────────────┘
```

三点读法提示：

- **CORE 根目录直接放了几十个 `RedissonXxx.java` 实现类**（`RedissonMap`、`RedissonLock`、`RedissonBatch`……），这是历史包结构（锁、Map 都诞生于"一个包走天下"的年代）。4.0 的破坏性变更里把 `GeoEntry`、`StreamMessageId` 等类型移入 `api.geo`、`api.stream` 子包，但实现类根目录布局未动——别按 Spring 的习惯去子包里找实现。
- **`client/` 与 `connection/` 是两个不同的层**：`client` 只认识"一台 Redis 服务器 + 一条连接 + 一个命令"（RedisClient 是连接工厂），`connection` 才认识"拓扑"（主从/集群/哨兵、读模式、槽位）。命令执行主线（第三章）恰好贯穿这两层。
- **`renewal/` 与 `eviction/` 是 4.x 稳定性工程的两块基石**：前者把 3.x"每把锁一个定时任务"的看门狗重构为"按集群槽位分组、一次 Lua 批量续期最多 100 把锁"（5.4 节），后者统一了 RMapCache/RSetCache/JCache/TimeSeries 的过期清理（4.5 节）。

## 1.4 内核分层依赖图（以包依赖与构造函数实参实证）

```
                ┌────────────────── config（Config 家族，一切的开始）─────────────────┐
                │                                                                    │
          ┌─────▼──────┐                                                      ┌──────▼──────┐
          │ RedisClient │（client：连接工厂，只认一台服务器）                        │ ServiceManager │（timer/executor/
          │ RedisConnection（netty Channel 封装）                                │  事件枢纽/UUID/renewal）│
          │ CommandEncoder/CommandDecoder（RESP 编解码）                          └──────┬──────┘
          └─────▲──────┘                                                             │
                │ 被持有                                                ┌────────────┼────────────┐
      ┌─────────┴──────────┐                                           │            │            │
      │ ConnectionManager   │（connection：拓扑感知）                      │            │            │
      │  MasterSlaveEntry   │─── 连接池（读写分离/订阅独立）                 │            │            │
      └─────────▲──────────┘                                    evictionScheduler  renewalScheduler  pubsub 服务
                │                                                          │            │            │
      ┌─────────┴──────────────────────────────────────────────────────────▼────────────▼────────────▼──┐
      │ command：CommandAsyncService（所有 R 实现的基类）→ RedisExecutor（重试/重定向/超时/连接获取）          │
      └─────────▲──────────────────────────────────────────────────────────────────────────────────┘
                │ 继承
   ┌────────────┴──────────┬──────────────────────┬───────────────────┐
   │ Redisson* 实现（CORE 根）│ reactive/ rx/ 动态代理   │ transaction/ 集成层  │
   │ （RedissonLock/Map/…）  │ （同步实现 → Mono/Flux）  │ （Spring/JCache…）  │
   └────────────────────────┴──────────────────────┴───────────────────┘
```

这张图的关键结论：**所有调用最终都收敛到一条链——`R 接口实现 → CommandAsyncService.async() → RedisExecutor.execute() → MasterSlaveEntry 取连接 → RedisConnection.send()（netty 写出）→ CommandDecoder 解码 → CompletableFuture 完成**。锁的 pub/sub 唤醒、看门狗续期、MapCache 的淘汰任务，全部是这条链的"二次调用者"，没有任何绕开 `RedisExecutor` 的旁门。

## 1.5 关键问题 → Redisson 方案映射（全文导览）

| 使用分布式系统时的关键问题 | Redisson 的方案 | 详见 |
|---|---|---|
| 多进程互斥访问共享资源 | RLock：hash 重入计数 + EVAL 原子加锁 + pub/sub 唤醒 + 看门狗续命 | 第五章 |
| 希望锁持有者宕机后锁能自动释放又不想写死 TTL | lockWatchdogTimeout（默认 30s）+ LockRenewalScheduler 批量续期（watchdog/3 周期） | 5.2、5.4 |
| 主从切换锁可能丢失 | checkLockSyncedSlaves/syncedEval（WAIT/WAITAOF 同步复制后执行）+ RedissonFencedLock（INCR fencing token） | 5.6、5.8 |
| 读多写少想读本地缓存 | RLocalCachedMap：Caffeine 近缓存 + pub/sub 广播失效 | 4.6 |
| 集合条目要"各自独立过期" | RMapCache/RMapCacheNative：hash + zset 到期索引 + EvictionScheduler 定时清扫 | 4.5 |
| 写库与写缓存一致性 | MapOptions.MapWriter（write-through/write-behind）+ writeBehindDelay 批量削峰 | 4.4 |
| 接口限流 | RRateLimiter：令牌桶 Lua（hash 存 rate/interval/type） | 4.7 |
| 分布式任务调度/延迟执行 | RExecutorService/RScheduledExecutorService（任务存 hash + list 队列，CronSchedule） | 6.5 |
| RPC 风格调用（像调本地接口一样调远端） | RRemoteService：请求队列 + 回调 map + pub/sub 通知三件套 | 6.6 |
| 延迟队列 | RDelayedQueue：zset 到期索引 + QueueTransferTask 定时搬运到目标 list | 6.3 |
| 多条命令一次往返 / 原子批量 | RBatch（IN_MEMORY pipeline 默认 / REDIS_WRITE_ATOMIC MULTI+EXEC） | 3.7 |
| 多对象跨进程事务 | RTransaction：本地缓冲 + commit 按序执行（pessimistic/optimistic，无回滚） | 7.1 |
| 一份数据多端失效通知 | RTopic/RReliableTopic（stream 承载，ack + 重放）/ RESP3 client-side caching（RClientSideCaching） | 6.2 |
| 概率统计 | RBloomFilter/RHyperLogLog/RBitSet/RCountMin（4.8 新增） | 4.7 |
| 与 Spring 无缝集成 | redisson-spring-boot-starter 自动选型 + RedissonSpringCacheManager + RedissonTransactionManager | 第九章 |

## 1.6 版本演进：从 1.x 到 4.8

写作时（2026 年 10 月）的版本格局：3.5x 与 4.8.x 并行维护，4.x 是当前主线。本节时间线取自源码包内 `CHANGELOG.md`（自带精确发布日期）与 GitHub Release 交叉核对；"特性属于哪个版本"的结论未做逐 tag git 实证（见文首声明），引用时以 CHANGELOG 原文为准。

### 1.6.1 版本时间线

| 版本 | 发布 | 一句话主题 |
|---|---|---|
| 1.x / 2.x | 2014 ~ 2018 | 起步与并行期：2.x 线长期与 3.x 并行发布（CHANGELOG 中"versions 2.14.0 and 3.9.0 released"直至 2018-10-31） |
| 3.0 | 2016-10-17 | **"Fully compatible with JDK 8"**：内核全面转向 CompletableFuture（CHANGELOG 原文），Async API 成为唯一内核 |
| 3.5.0 | 2017-07-28 | RMapCache、RExecutorService 等成体系（2.10.0/3.5.0 双发） |
| 3.12.0 | 2019-12-26 | Spring Boot 2.2、stream 完善期 |
| 3.13.0 | 2020-05-25 | RxJava/Reactive 接口扩展（RLocalCachedMap 的 Reactive/Rx 接口，CHANGELOG `CHANGELOG.md:1580`） |
| 3.15.0 | 2021-01-28 | Tomcat 10 支持、SpinLock（`CHANGELOG.md:2018-2020`） |
| 3.16.0 | 2021-06-28 | GraalVM native-image、Quarkus/Micronaut/Helidon 集成、JCache 数据分区（`CHANGELOG.md:1891-1895`） |
| 3.17.0 | 2022-03-20 | RFunction（requires Redis 7.0+）、`checkLockSyncedSlaves` 设置（`CHANGELOG.md:1722-1726`） |
| 3.25.0 | 2023-12-05 | Redis 7.x 特性跟进期 |
| 3.40.0 | 2024-12-03 | 稳定性与 PRO 功能开源化推进 |
| 3.45.0 | 2025-02-21 | **Valkey 显式兼容**：`valkey`/`valkeys` scheme 支持（`CHANGELOG.md:728`） |
| 3.50.0 | 2025-06-17 | 3.x 收官线 |
| **4.0.0** | **2025-12-16** | **清理型大版本**（见 1.6.2），Netty 4.2.9、Spring Boot 4.0 / Spring Data Redis 4.0 支持 |
| 4.5.0 | 2026-06-05 | Jackson 3 全家桶编解码器（AvroJackson3Codec 等，`CHANGELOG.md:313`） |
| 4.8.0 | 2026-10-02 | Count-Min Sketch、Time Series (Native)、`RVectorSet`（向量集）、Spring AI 2.0 集成、Netty 4.2.18.Final、Kryo 5.7.0 |

### 1.6.2 4.0.0 破坏性变更清单（CHANGELOG `CHANGELOG.md:372-420` 原文要点）

- **配置层**：弃用的 JSON 配置格式移除（YAML 为唯一文件格式，且改为直接使用 SnakeYAML 解析）；认证、SSL、TCP/keepAlive、nameMapper/commandMapper 参数**上收到 Config 顶层**（不再散落在各 *ServerConfig）。
- **API 层**：`RFuture` 弃用方法移除（3.x 起 `RFuture` 已是 `CompletionStage` 的扩展，旧的 `await()/awaitUninterruptibly()/cause()` netty-style 方法退场）；`RScript.ReturnType` 三个枚举改名（MULTI→LIST、STATUS→STRING、INTEGER→LONG）；`getNodesGroup()/getClusterNodesGroup()` 移除；`GeoEntry`、`StreamMessageId` 等类型迁入 `api.geo`/`api.stream` 子包。
- **集成层**：Spring XML 配置支持移除；弃用的自定义 Spring Session 实现移除。
- **依赖层**：Jackson 变为**可选依赖**（不用 Jackson 序列化的用户不再被强拉 2.22.x）。
- **新增**：全功能 Reliable Pub/Sub（PRO）、Valkey Cluster 的 `database` 设置、`RMapCacheNativeV2` 族对象。

### 1.6.3 三个容易搞错的点

- **"Redisson 4.x = 需要 JDK 17+"**：错。构建脚本仍是 `maven.compiler.release=8`（根 `pom.xml`），4.8.0 保持 **JDK 8 基线**——这是它相对 Lettuce（3.x 起 JDK 8、新特性要求 17 的路线）最保守的地方；仅测试代码用 JDK 25 编译（`maven.compiler.testRelease=25`）。
- **"RedLock 是推荐方案"**：过时。`RedissonRedLock` 早已标记 deprecated 并在文档中推荐单实例锁 + fencing（`RedissonRedLock.java` 仅 63 行，本质是 MultiLock 加了部分成功判定）；3.17.0 引入的 `checkLockSyncedSlaves`（WAIT 同步复制）与 `RedissonFencedLock` 才是官方对"主从切换锁丢失"的正解（5.8 节）。
- **"watchdog 是每把锁一个线程/定时器"**：3.x 早期如此（每把锁独立 `Timeout`），现行实现是**全局一个 `LockRenewalScheduler` + 三类批量任务**（普通锁/读锁/FastMultilock），按集群槽位分组、每批最多 `lockWatchdogBatchSize=100` 把锁、一次 EVAL 完成续期（5.4 节）。

## 1.7 全文章节地图

- **第二章 地基：配置与 Netty 连接层（config/connection/client）**：Config 家族五类拓扑与默认值、ServiceManager 全局服务中枢、连接池分层、netty pipeline 装配与 RESP 解码、重连/保活/DNS 监控。
- **第三章 命令执行主线（command）**：RedisCommands 命令表 → CommandAsyncService 入口 → RedisExecutor 一次执行的完整生命周期（连接获取、三重超时、重试、MOVED/ASK/LOADING 处理）→ syncedEval 与 RBatch。
- **第四章 分布式对象体系（api + 实现类）**：R 接口家族全景、RMap 的 Loader/Writer、RMapCache 与 EvictionScheduler、RLocalCachedMap 近缓存、概率对象、LiveObject。
- **第五章 分布式锁与同步器**：RLock 加锁/解锁 Lua 逐行解读、看门狗批量续期（renewal 包）、pub/sub 唤醒链路、公平锁/读写锁/FencedLock/SpinLock、正确性讨论。
- **第六章 消息、队列与任务调度**：pub/sub 基础设施（连接复用与重订阅）、RTopic 家族、BlockingQueue/DelayedQueue、RStream、RExecutorService、RRemoteService。
- **第七章 事务、批处理与脚本（transaction）**：RTransaction 缓冲-提交模型、BatchOptions、RScript。
- **第八章 响应式与多客户端视图（reactive/rx）**：sync/async/reactive/rx 四套 API 的同构、动态代理转译、RFuture。
- **第九章 集成生态**：redisson-spring（starter/cache/transaction/spring-data 19 个适配）、JCache、Tomcat/MyBatis/Hibernate/Quarkus 等。
- **第十章 贯通视图**：客户端启动、一次 `lock()`、一次 `RMapCache.put(key, val, ttl)` 三条时间线叠加成全景。
- **第十一章 附录**：R 接口速查表、Config 选项速查、学习路线与避坑清单、与 Jedis/Lettuce 的定位对比。


---
# 二、地基：配置体系与 Netty 连接层（config / connection / client）

> 本章对应源码：`config/`（Config 家族）、`connection/`（拓扑与连接池）、`client/`（单机连接与协议）。行号均为 4.8.0 实测。

## 2.1 Config 家族：五类拓扑，一份 Config

**白话**：`Config` 是 Redisson 的一切入口。它与 Jedis/Lettuce 配置对象最大的不同，是**必须先声明拓扑类型**——`useSingleServer()`/`useClusterServers()`/`useSentinelServers()`/`useReplicatedServers()`/`useMasterSlaveServers()` 五选一，内部由 `ConnectionManager.create(configCopy)` 按类型实例化对应的 ConnectionManager（`Redisson.java:77-78`、`connection/ServiceManager.java` 的 `isSingleConfig()` 等判定）。

【源码证据】`config/Config.java:160-175`——无参构造是空的，**默认值在"拷贝构造"里落地**（`Redisson` 构造函数第一件事就是 `new Config(config)`，见 `Redisson.java:76-78`）：

```java
public Config() {
}

public Config(Config oldConf) {
    setNettyHook(oldConf.getNettyHook());
    ...
    if (oldConf.getCodec() == null) {
        // use it by default
        oldConf.setCodec(new Kryo5Codec());
    }
```

也就是说：**默认编解码器是 Kryo5Codec，且"默认值生效点"在拷贝构造里**——直接 new Config() 后不 setCodec，真正连接前一定被补上 Kryo5Codec。

关键默认值速览（均为 4.8.0 实测行号）：

| 配置项 | 默认值 | 证据 |
|---|---|---|
| codec | Kryo5Codec | `config/Config.java:168-171`（拷贝构造补默认） |
| nettyThreads | 32 | `config/Config.java:68` |
| transportMode | NIO | `config/Config.java:78` |
| protocol | RESP2 | `config/Config.java:114` |
| lockWatchdogTimeout | 30000 ms | `config/Config.java:82` |
| lockWatchdogBatchSize | 100 | `config/Config.java:84` |
| checkLockSyncedSlaves | true | `config/Config.java:88` |
| slavesSyncTimeout | 1000 ms | `config/Config.java:90` |
| timeout（命令响应超时） | 3000 ms | `config/BaseConfig.java:58` |
| connectTimeout / idleConnectionTimeout | 10000 ms | `config/BaseConfig.java:51,44` |
| retryAttempts / retryInterval | 4 / 1500 ms | `config/BaseConfig.java:62,65` |
| retryDelay | EqualJitterDelay(1s~2s) | `config/BaseConfig.java:67` |
| reconnectionDelay | EqualJitterDelay(100ms~10s) | `config/BaseConfig.java:69` |
| pingConnectionInterval | 30000 ms | `config/BaseConfig.java:126` |
| subscriptionsPerConnection | 5 | `config/BaseConfig.java:86` |
| 单机 connectionPoolSize / MinimumIdleSize | 64 / 24 | `config/SingleServerConfig.java:50,45` |
| 单机 subscriptionConnectionPoolSize | 50 | `config/SingleServerConfig.java:40` |
| 主从 master/slaveConnectionPoolSize | 64 / 64 | `config/BaseMasterSlaveServersConfig.java:59,44` |
| readMode / subscriptionMode | SLAVE / MASTER | `config/BaseMasterSlaveServersConfig.java:61,67` |
| fallbackLoadingToMaster | true | `config/BaseMasterSlaveServersConfig.java:65` |

三个值得注意的点：

- **重试默认 4 次、且带抖动**（EqualJitterDelay 1~2 秒随机）：相比 3.x 的固定 retryInterval，4.x 把重试间隔换成了抖动策略家族（`EqualJitterDelay`/`FullJitterDelay`/`DecorrelatedJitterDelay`/`ConstantDelay`，见 `config/` 包文件列表），避免"惊群重试"。
- **readMode 默认 SLAVE**：读命令默认打到从节点（集群/主从拓扑下），单机模式无差别。这与"写主读从"的传统数据库直觉一致，但意味着**读写分离下的主从延迟会直接暴露给业务**（写后立刻读可能读到旧值）。
- **4.0 把 password/ssl/TCP 参数上收到 Config 顶层**（CHANGELOG 4.0.0：`move auth parameters at Config object level`），五类拓扑共享一份认证配置，不再需要逐个 *ServerConfig 设置。

## 2.2 ServiceManager：全局服务中枢

**白话**：一个 RedissonClient 内所有"跨对象共享"的东西——定时器、事件分发、客户端 ID、看门狗调度器——都挂在 ServiceManager 上。它相当于 Spring 的 ApplicationContext 在 Redisson 里的对应物。

【源码证据】`connection/ServiceManager.java`（节选字段声明，行号实测）：

```java
123    private final ConnectionEventsHub connectionEventsHub = new ConnectionEventsHub();
125    private final String id = UUID.randomUUID().toString();
127    private final EventLoopGroup group;              // netty 事件循环组
131    private final AddressResolverGroup<InetSocketAddress> resolverGroup;  // DNS 解析
133    private final ExecutorService executor;          // 外部 executor（写回、远程服务用）
139    private HashedWheelTimer timer;                  // 一切定时任务的时间轮
145    private final ElementsSubscribeService elementsSubscribeService = ...;
155    private final Map<String, ResponseEntry> responses = ...;  // RemoteService 响应
157    private final QueueTransferService queueTransferService = ...;  // 延迟队列搬运
795    private final Set<RedissonClientSideCaching> cachingInstances = ...;  // RESP3 客户端缓存
```

【源码证据】`connection/ServiceManager.java:304`——时间轮：

```java
timer = new HashedWheelTimer(new DefaultThreadFactory("redisson-timer"), ...)
```

看门狗续期、命令超时、连接保活（ping）、延迟队列搬运（QueueTransferTask）、RMapCache 清扫，**全部注册在这一个 HashedWheelTimer 上**。Redisson 自己的线程主要有三类：netty EventLoop 线程（`nettyThreads=32`）、这个 timer 线程、以及可选的 executor 线程——没有"每个功能一个线程"的铺张。

客户端全局 ID 也在这里生成（`:125`），它直接决定锁的所有权字段：`RLock` 的 hash field 是 `id + ":" + threadId`（5.1 节）。

## 2.3 连接分层：从一条 Channel 到拓扑

**白话**：Redisson 的连接模型是四层洋葱——`RedisClient`（单机连接工厂）→ `RedisConnection`（一条 netty Channel 的封装）→ `ClientConnectionsEntry`（一台节点的读/写/订阅三池）→ `MasterSlaveEntry`（一个"主 + 从们"的逻辑单元）→ `ConnectionManager`（全拓扑，含集群槽位路由）。业务请求只关心"哪个 key"，由最外层反推"哪台节点、哪条连接"。

各层职责与证据：

- **RedisConnection：Channel 的薄封装**。发命令就是一次 netty 写出：【源码证据】`client/RedisConnection.java:236-244`
  ```java
  public ChannelFuture send(CommandsData data) {
      return channel.writeAndFlush(data);
  }
  public <T, R> R sync(Codec encoder, RedisCommand<T> command, Object... params) {
      CompletableFuture<R> promise = new CompletableFuture<>();
      send(new CommandData<T, R>(promise, encoder, command, params));
      return await(promise);
  }
  ```
  注意 `sync()` 只服务于"连接层自己的管理命令"（握手、AUTH、SELECT、CLIENT SETINFO 等）；业务路径的同步语义在 `CommandAsyncExecutor.get()` 层实现（3.5 节）。
- **连接池**：读连接、写连接、订阅连接三池独立（订阅连接要处理 push 消息，不能与请求响应混流，这是 RESP 订阅协议的强制约束）。池实现位于 `connection/pool/ConnectionPool.java` 与 `connection/ConnectionsHolder.java`（4.x 新重构的持有器）。
- **MasterSlaveEntry**：主从/哨兵/集群的一个分片单元，负责"读模式选从、写模式选主、失败从节点摘除"。
- **集群槽位路由**：`ConnectionManager.calcSlot(key)` 委托给 ServiceManager 计算 CRC16 槽位：【源码证据】`connection/MasterSlaveConnectionManager.java:618-624`
  ```java
  public int calcSlot(String key) {
      return serviceManager.calcSlot(key);
  }
  ```
  集群模式下 `getReadEntry(slot)/getWriteEntry(slot)`（`RedisExecutor.java:924-929`）按槽位反查 entry。

## 2.4 netty pipeline 装配与 RESP 解码

**白话**：每条连接建立时，netty 会按固定顺序装一串 handler：SSL → 连接持有器 → 重连看门狗 → 编码器（请求）→ 排队器 → 保活 → 解码器（响应）。这就是 Redisson 网络层的"流水线图"。

【源码证据】`client/handler/RedisChannelInitializer.java:76-110`（`initChannel` 全文骨架）：

```java
protected void initChannel(Channel ch) throws Exception {
    initSsl(config, ch);                       // SSL 可选，最先入 pipeline
    if (type == Type.PLAIN) {
        ch.pipeline().addLast(new RedisConnectionHandler(redisClient));
    } else {
        ch.pipeline().addLast(new RedisPubSubConnectionHandler(redisClient));
    }
    ch.pipeline().addLast(
        connectionWatchdog,                    // 断线自动重连
        new CommandEncoder(config.getCommandMapper()),   // 参数 → RESP 请求
        CommandBatchEncoder.INSTANCE);         // 批量命令（pipeline）编码
    if (type == Type.PLAIN) {
        ch.pipeline().addLast(new CommandsQueue());      // 排队：同一连接串行发送
    } else {
        ch.pipeline().addLast(new CommandsQueuePubSub());
    }
    if (pingConnectionHandler != null) {
        ch.pipeline().addLast(pingConnectionHandler);    // pingConnectionInterval 保活
    }
    if (type == Type.PLAIN) {
        ch.pipeline().addLast(new CommandDecoder(...));  // RESP 响应 → Java 对象
    } else {
        ch.pipeline().addLast(new CommandPubSubDecoder(config)); // + push 消息分发
    }
    ch.pipeline().addLast(new ErrorsLoggingHandler());
    config.getNettyHook().afterChannelInitialization(ch);
}
```

读法要点：

- **PLAIN 与 PUBSUB 两种模板**：普通连接与订阅连接共用前半段，差异在解码器——`CommandPubSubDecoder` 额外处理 RESP 的 push 类消息（`+message`/`+pmessage`），把它们派发给 `RedisPubSubListener`。
- **`CommandsQueue` 是"同一连接串行"的保证者**：netty 的写出是异步的，CommandsQueue 维护"已发送命令队列"，让响应按发送顺序对号入座（Redis 单连接的响应严格有序，这是 RESP 协议特性）。
- **重连与保活是 pipeline 里的常驻 handler**，不是外置的调度逻辑：`ConnectionWatchdog` 监听断线事件并用 `reconnectionDelay` 退避重连；`PingConnectionHandler` 按 `pingConnectionInterval=30000ms`（`BaseConfig.java:126`）发送 PING，避免 NAT/LB 静默断链。
- **空闲连接清理**由 `connection/IdleConnectionWatcher.java` 承担（按 `idleConnectionTimeout=10000ms` 关闭空闲连接），**DNS 变化感知**由 `connection/DNSMonitor.java`（`dnsMonitoringInterval=5000ms`，单机模式）承担。

## 2.5 pub/sub 连接的复用经济账

**白话**：订阅连接很贵（一条连接的订阅槽有限、且要独占 push 通道），所以 Redisson 对它做了两层复用：同一节点所有业务订阅共享一个小池（默认 `subscriptionConnectionPoolSize=50`），每条订阅连接上又可承载多个 channel（默认 `subscriptionsPerConnection=5`）。锁的等待唤醒、RLocalCachedMap 的失效广播、RTopic 全走这套。

【源码证据】`pubsub/PubSubConnectionEntry.java`（371 行，本节只看结构）：一条订阅连接对应一个 entry，内部以 `channelName → listeners` 多值映射计数管理订阅；`PublishSubscribeService`（1201 行）负责"从池里选 entry → 发 SUBSCRIBE → 注册 listener"的全链路，以及**断线后自动重订阅**：

【源码证据】`pubsub/PublishSubscribeService.java:904-966`（重连重订阅入口与日志）：

```java
private void reattachPubSubListeners(Set<ChannelName> channels, MasterSlaveEntry en, PubSubType topicType) {
    ...
    log.info("listeners of '{}' channel have been resubscribed to '{}'", channelName, res);
```

锁（`LockPubSub`）、闭锁（`CountDownLatchPubSub`）、信号量（`SemaphorePubSub`）都是 `PublishSubscribe` 抽象类的子类（`pubsub/PublishSubscribe.java:34`），共享"引用计数 + 每通道信号量"的复用模型，只是各自实现 `onMessage` 的语义（5.5 节以锁为例展开）。

## 2.6 本章小结

- Config 五类拓扑五选一，4.x 的默认值落在 `Config`/`BaseConfig`/`*ServerConfig` 三个类的字段初始化与拷贝构造里；默认编解码 Kryo5Codec、协议 RESP2、重试 4 次带抖动。
- ServiceManager 持有 netty EventLoop 组、HashedWheelTimer、全局 UUID 与各全局服务——一个 RedissonClient 的"ApplicationContext"。
- 连接模型四层：RedisClient→RedisConnection→ClientConnectionsEntry（三池）→MasterSlaveEntry→ConnectionManager（槽位路由）；发命令 = `channel.writeAndFlush(CommandData)`。
- netty pipeline 固定装配顺序：SSL → ConnectionHandler → ConnectionWatchdog → CommandEncoder/CommandBatchEncoder → CommandsQueue → PingConnectionHandler → CommandDecoder/CommandPubSubDecoder → ErrorsLoggingHandler。
- 订阅连接独立成池、多 channel 复用、断线自动重订阅（`reattachPubSubListeners`）——锁等待与近缓存失效的可靠性都建立在这之上。

---

# 三、命令执行主线：从 `getMap("m").get("k")` 到一条网络包（command）

> 本章对应源码：`client/protocol/RedisCommands.java`（命令表）、`command/CommandAsyncService.java`（入口）、`command/RedisExecutor.java`（单次执行状态机）、`command/CommandBatchService.java`（批量）。

## 3.1 RedisCommands：一张"命令对象"大表

**白话**：Redisson 不用字符串拼命令。每个 Redis 命令在客户端是一个类型化的 `RedisCommand` 对象：命令名 + 请求参数如何编码 + 响应如何解码（MultiDecoder）。`RedisCommands.java` 就是这张表的登记处。

【源码证据】`client/protocol/RedisCommands.java:186-187,599-609`（实测该文件约 592 个命令常量定义）：

```java
RedisStrictCommand<Integer> WAIT = new RedisStrictCommand<Integer>("WAIT", new IntegerReplayConvertor());
RedisCommand<List<Integer>> WAITAOF = new RedisCommand("WAITAOF", new ObjectListReplayDecoder<Integer>(), ...);
...
RedisStrictCommand<Boolean> EVAL_BOOLEAN = new RedisStrictCommand<Boolean>("EVAL", new BooleanReplayConvertor());
RedisStrictCommand<Long>    EVAL_LONG    = new RedisStrictCommand<Long>("EVAL");
RedisStrictCommand<Void>    EVAL_VOID    = new RedisStrictCommand<Void>("EVAL", new VoidReplayConvertor());
```

注意 EVAL 家族的技巧：**同一个 EVAL 命令，按"返回值语义"拆成 Boolean/Long/Void/BooleanAmount 等多个命令对象**，区别只在 replay 转换器——Lua 返回的整数 1/0 由 `BooleanReplayConvertor` 变成 true/false。第五章锁代码里 `RedisCommands.EVAL_BOOLEAN/EVAL_LONG` 的选择就是这个用意。

## 3.2 CommandAsyncService：所有 R 实现类的地基

**白话**：`RedissonMap`、`RedissonLock`、`RedissonBucket`……它们不是各写各的网络调用，而是统一继承/持有一个 `CommandAsyncExecutor`，把"key + codec + RedisCommand + 参数"交给它。这一层解决三件事：**算出该去哪个节点**（单机/主从/集群路由）、**选择读写通道**、**把调用包成 CompletableFuture**。

【源码证据】`command/CommandAsyncService.java:667-734`（写路径主干，节选）：

```java
@Override
public <T, R> RFuture<R> writeAsync(String key, Codec codec, RedisCommand<T> command, Object... params) {
    NodeSource source = getNodeSource(key);          // ① key → 节点（含集群槽位）
    return async(false, source, codec, command, params, false, false);
}

public <V, R> RFuture<R> async(boolean readOnlyMode, NodeSource source, Codec codec,
        RedisCommand<V> command, Object[] params, boolean ignoreRedirect, boolean noRetry) {
    RedisCommand<V> cmnd = getServiceManager().resp3(command);   // ② RESP2/3 命令适配
    ...
    CompletableFuture<R> mainPromise = createPromise();
    if (!readOnlyMode && getServiceManager().hasCachingInstances()) {  // ③ 有客户端缓存在监听时，
        ...  getServiceManager().evictClientSideCaching(name);      //    写操作后失效本地缓存
    }
    RedisExecutor<V, R> executor = new RedisExecutor<>(readOnlyMode, source, codec, cmnd, params,
                                        mainPromise, ignoreRedirect, connectionManager, objectBuilder, ...,
                                        retryAttempts, retryDelay, responseTimeout, trackChanges, readMode);
    executor.execute();                              // ④ 交给执行器
    return new CompletableFutureWrapper<>(mainPromise);
}
```

四个要点：① `NodeSource` 是"目标节点"的抽象（单机/主/从/槽位/重定向节点五态）；③ 是 RESP3 client-side caching 的钩子——任何写操作完成后自动失效监听该 key 的本地缓存；④ 每次调用新建一个 `RedisExecutor` 实例（执行器是"一次命令执行"的状态机，不是常驻服务）。

## 3.3 RedisExecutor：一次执行的完整生命周期

**白话**：RedisExecutor 是理解 Redisson 可靠性的钥匙。它把"发一条命令"拆成：取连接 → 发送 → 三重超时保护 → 结果/异常分类 → 重试或重定向 → 释放连接。所有"为什么我超时了""为什么重复执行了""集群搬槽为什么没丢请求"的问题，答案都在这个 934 行的类里。

【源码证据】`command/RedisExecutor.java:122-230`（`execute()` 主干，节选保留编号注释）：

```java
public void execute() {
    ...
    codec = getCodec(codec);                                        // ① 类加载器感知的 codec 适配
    CompletableFuture<R> attemptPromise = new CompletableFuture<>(); // ② “本次尝试”的 future
    CompletableFuture<RedisConnection> connectionFuture = getConnection(attemptPromise);  // ③ 取连接
    ...
    retryInterval = retryStrategy.calcDelay(attempt).toMillis();
    scheduleRetryTimeout(connectionFuture, attemptPromise);          // ④a 重试定时器
    scheduleConnectionTimeout(attemptPromise, connectionFuture);     // ④b 取连接超时
    connectionFuture.whenComplete((connection, e) -> {
        ...
        sendCommand(attemptPromise, connection);                     // ⑤ 发送（ASK 时先 ASKING）
        scheduleWriteTimeout(attemptPromise);                        // ④c 写出超时
        writeFuture.addListener((ChannelFutureListener) future ->
            checkWriteFuture(writeFuture, attemptPromise, connection));   // 写成功→安排响应超时
    });
    attemptPromise.whenComplete((r, e) -> {
        releaseConnection(attemptPromise, connectionFuture);         // ⑥ 释放连接
        checkAttemptPromise(attemptPromise, connectionFuture);       // ⑦ 结果分类→重试/重定向/完成
    });
}
```

**连接获取（③）**按读写分流：【源码证据】`RedisExecutor.java:865-913`——`connectionReadOp` 依据 readMode（MASTER/SLAVE/MASTER_SLAVE）经 entry 的 balancer 选节点；`connectionWriteOp` 永远走主。集群模式下 `getEntry(read)` 按槽位取（`:915-932`）。

**发送（⑤）的细节——ASK 重定向要先发 ASKING**：【源码证据】`RedisExecutor.java:726-734`

```java
protected void sendCommand(CompletableFuture<R> attemptPromise, RedisConnection connection) {
    if (source.getRedirect() == Redirect.ASK) {
        List<CommandData<?, ?>> list = new ArrayList<>(2);
        list.add(new CommandData<>(promise, codec, RedisCommands.ASKING, new Object[]{}));
        list.add(new CommandData<>(attemptPromise, codec, command, params));
        writeFuture = connection.send(new CommandsData(main, list, false, false));
```

**三重超时（④）**与**响应超时（⑥'，写在 checkWriteFuture 成功回调里）**共同构成完整保护：

| 定时器 | 证据 | 语义 |
|---|---|---|
| scheduleRetryTimeout | `RedisExecutor.java:278-375` | 每过 `retryInterval` 检查一次进度：没连上/没写出去就 `attempt++` 并重新 `execute()`；到 `attempts` 上限则报 `RedisTimeoutException` |
| scheduleConnectionTimeout | `:232-252` | 取不到连接（池耗尽）时按 responseTimeout 报"Unable to acquire connection! ... Increase connection pool size" |
| scheduleWriteTimeout | `:254-276` | 命令写不出 channel 时报错，提示检查 CPU/nettyThreads/TCP 丢包 |
| scheduleResponseTimeout | `:436-503` | 写出后等响应超时；**阻塞命令（BLPOP 等）按 popTimeout 延长并 +1s**（源码注释：`// add 1 second due to issue https://github.com/antirez/redis/issues/874`，`:467-468`） |

**重试的边界（防重复副作用）**：【源码证据】`RedisExecutor.java:505-509`

```java
private boolean isResendAllowed(int attempt, int attempts) {
    return attempt < attempts
            && !noRetry
            && (command == null || (!command.isBlockingCommand() && !command.isNoRetry()));
}
```

响应超时后是否重发，取决于三件事：次数没用完、命令没被标记 noRetry、不是阻塞命令。**这正是锁类操作用 `syncedEvalNoRetry`/`evalWriteSyncedNoRetry` 的原因**（5.2 节）：加锁 Lua 已经原子地改了服务端状态，响应丢失时盲目重发会造成语义混乱，宁可上层自己再 try 一次。

## 3.4 异常分类学：MOVED/ASK/LOADING/READONLY 怎么被消化

**白话**：Redis 的错误字符串（MOVED 3999 127.0.0.1:6381）在客户端被解码成类型化异常，`checkAttemptPromise` 像一个分诊台，决定"重试同节点 / 转移节点重试 / 回主节点 / 直接失败"。

【源码证据】`command/RedisExecutor.java:579-661`（`checkAttemptPromise` 节选）：

```java
if (cause instanceof RedisRedirectException && !ignoreRedirect) {
    RedisRedirectException ex = (RedisRedirectException) cause;
    if (source.getRedirect() == Redirect.MOVED && source.getAddr().equals(ex.getUrl())) {
        // 重定向自环：MOVED 指回同一节点，直接失败，防死循环
        mainPromise.completeExceptionally(new RedisException("MOVED redirection loop detected..."));
        return;
    }
    handleRedirect(ex, connectionFuture, reason);   // MOVED/ASK → 解析新地址 → execute()
}
if (cause instanceof RedisLoadingException) {
    // 从节点还在加载 RDB：fallbackLoadingToMaster=true 时改走主节点重试
    if (ce.getNodeType() == NodeType.SLAVE && entry.getConfig().isFallbackLoadingToMaster()) {
        source = new NodeSource(entry.getClient());
        execute();
        return;
    }
}
if (cause instanceof RedisRetryException || cause instanceof RedisReadonlyException || ...) {
    if (attempt < attempts) { attempt++; 延迟后 execute(); }   // 从提升主瞬间的 READONLY 自动换节点重试
}
```

【源码证据】`RedisExecutor.java:663-676`——重定向落地：

```java
private void handleRedirect(RedisRedirectException ex, ...) {
    onException();
    CompletableFuture<RedisURI> ipAddrFuture = connectionManager.getServiceManager().resolveIP(ex.getUrl());
    ipAddrFuture.whenComplete((ip, e) -> {
        ...
        source = new NodeSource(ex.getSlot(), ip, reason);   // 记住槽位 + 新地址 + MOVED/ASK 语义
        execute();                                           // 用新 NodeSource 重新执行
    });
}
```

**故障检测**也在分诊台出口处：任何命令失败都会喂给该节点的 `FailedNodeDetector`（`RedisExecutor.java:700-714`，`handleError`）—— detector 判定节点失败（默认基于连续失败/超时，`client/FailedConnectionDetector.java`、`FailedNodeDetector.java`、`FailedCommandsDetector.java` 三种内置实现）则触发 `entry.shutdownAndReconnectAsync`，把该节点从可读池摘除。

## 3.5 同步语义：get() 只是"阻塞等 future"

**白话**：`RLock.lock()`、`RMap.get()` 这些同步方法没有第二套实现——都是 `get(async方法)`。`CommandAsyncExecutor.get()` 阻塞等待 CompletableFuture；`RFuture` 自 3.0 起继承 `CompletionStage`，因此业务代码可以无缝切到 `thenApply/thenCompose` 编排。

【源码证据】`command/CommandAsyncExecutor.java:51-55`（接口定义）：

```java
<V> V get(RFuture<V> future);
<V> V getInterrupted(RFuture<V> future) throws InterruptedException;
```

以及 `org.redisson.api.RFuture` + `misc/CompletableFutureWrapper`（把 CompletionStage 重新包回 RFuture 类型，保持 R 接口返回签名）。**推论**：同步 API 的线程在等待期间不占用 netty EventLoop（等待发生在调用者线程），但一次同步调用 = 一次网络往返 = 一次阻塞，这正是第十一章避坑清单第一条的根源。

## 3.6 syncedEval：写 Lua 之前，先等从库确认

**白话**：4.x 给"会改变服务端状态的关键 Lua"（主要是锁）加了一层保险：在主从拓扑下，执行 EVAL 前先用 `WAIT`/`WAITAOF` 确认"刚才的写已被多少从节点确认"，从而收窄"主节点宕机、从节点还没收到写、锁凭空复活"的窗口。这个机制由 `checkLockSyncedSlaves=true`（默认开）与 `slavesSyncTimeout=1000ms` 控制。

【源码证据】`command/CommandAsyncService.java:1096-1116`（`syncedEval` 节选）：

```java
private <T> RFuture<T> syncedEval(long timeout, SyncMode syncMode, boolean retry, String key, ...) {
    if (getServiceManager().getCfg().isSingleConfig()           // 单机：不需要 WAIT
            || this instanceof CommandBatchService
            || (waitSupportedCommands != null && waitSupportedCommands.isEmpty() ...)) {
        ... return evalWriteAsync(key, codec, evalCommandType, script, keys, params);
    }
    ...
    if (waitSupportedCommands == null) {                        // 首次：探测 WAIT/WAITAOF 可用性
        CommandBatchService ee = createCommandBatchService(BatchOptions.defaults());
        ee.writeAsync(key, RedisCommands.WAIT, 0, 0);
        ee.writeAsync(key, RedisCommands.WAITAOF, 0, 0, 0);
        waitFuture = ee.executeAsync();
    }
    ...                                                         // 随后检查 availableSlaves，确认从库数后 EVAL
```

三句话总结：单机直接 EVAL；主从/集群先探测并执行 WAIT（把 3.17.0 引入的 `checkLockSyncedSlaves` 落到实处，CHANGELOG `CHANGELOG.md:1726`）；探测结果缓存到 `waitSupportedCommands`，老版本 Redis 不支持 WAITAOF 时自动降级只 WAIT。锁路径调用的 `syncedEvalNoRetry` 还叠加了 3.4 节的 noRetry 语义（`command/CommandAsyncService.java:1075-1081`）。

## 3.7 RBatch：一次往返 N 条命令

**白话**：RBatch 把多个 R 对象操作收集起来，一次网络往返发完（Redis pipeline）。默认"内存排队"，可选"Redis 端 MULTI/EXEC 原子执行"。

【源码证据】`api/BatchOptions.java:31-70`（ExecutionMode 枚举，节选）：

```java
public enum ExecutionMode {
    REDIS_READ_ATOMIC,   // 存入 Redis，MULTI/EXEC 原子执行（读批）
    REDIS_WRITE_ATOMIC,  // 存入 Redis，MULTI/EXEC 原子执行（写批）。集群模式要求同槽位
    IN_MEMORY,           // 客户端内存排队，pipeline 执行。默认
```

【源码证据】`RedissonBatch.java:34-38`——批处理的核心是**给所有 R 对象换一个"共享的 CommandBatchService"执行器**，操作不再立即发送而是入队：

```java
public RedissonBatch(EvictionScheduler evictionScheduler, CommandAsyncExecutor executor, BatchOptions options) {
    this.executorService = executor.createCommandBatchService(options);
    ...
}
```

`CommandBatchService.executeAsync()`（`command/CommandBatchService.java:273`）把队列打包为 `CommandsData` 一次写出（`isRedisBasedQueue()` 分支 `:808` 处理 MULTI/EXEC 模式）。另外 CommandAsyncService 还有按槽位分组的**批量单命令**通道：`readBatchedAsync/writeBatchedAsync`（`command/CommandAsyncService.java:748-755`）——把上千个 key 按槽位分组自动打批，供 `RKeys.delete` 等_mass_ 操作使用，业务无需自己分组。

## 3.8 本章小结

- 命令 = 类型化 `RedisCommand` 对象（名称 + 编码器 + MultiDecoder），约 592 个常量登记于 `RedisCommands`；EVAL 按返回语义派生 BOOLEAN/LONG/VOID 家族。
- 调用链唯一：`R 实现 → CommandAsyncService.writeAsync/readAsync → getNodeSource → new RedisExecutor().execute()`；执行器是一次执行的状态机，不是服务。
- RedisExecutor 的可靠性设计：读写分流取连接、四重超时（重试/取连接/写出/响应）、响应超时重发受 `isResendAllowed` 约束（noRetry/阻塞命令不重发）、MOVED/ASK/LOADING/READONLY 类型化消化、MOVED 自环保护、FailedNodeDetector 摘除坏节点。
- syncedEval 用 WAIT/WAITAOF 在关键 EVAL 前确认从库同步（锁的正确性保险），带运行时探测与降级。
- RBatch = 共享 CommandBatchService 入队 + 一次 pipeline 写出；可选 MULTI/EXEC 原子模式；`writeBatchedAsync` 按槽位自动分组处理海量 key。

---

# 四、分布式对象体系（api + 实现类）

> 本章对应源码：`api/`（388 个接口）、CORE 根的 `RedissonXxx` 实现类、`eviction/`、`cache/`、`liveobject/`。

## 4.1 接口家族全景：两棵树

**白话**：api 包是两棵并行的树。一棵是**同步树**：`RObject`（命名、删除、改名等对象级操作）→ `RExpirable`（TTL）→ 具体对象（RMap/RList/...），每个具体对象再挂一个 Java 集合接口；另一棵是**异步树**：每个同步接口都有一个 `XxxAsync`（返回 RFuture）、`XxxReactive`（Mono/Flux）、`XxxRx`（RxJava3）孪生兄弟。

【源码证据】接口继承链（行号实测）：

```java
api/RObject.java:28     public interface RObject extends RObjectAsync {
api/RExpirable.java:30  public interface RExpirable extends RObject, RExpirableAsync {
api/RMap.java:39        public interface RMap<K, V> extends ConcurrentMap<K, V>, RExpirable, RMapAsync<K, V>, RDestroyable {
api/RList.java:31       public interface RList<V> extends List<V>, RExpirable, RListAsync<V>, RSortable<List<V>>, RandomAccess {
api/RSet.java:32        public interface RSet<V> extends Set<V>, RExpirable, RSetAsync<V>, RSortable<Set<V>> {
api/RQueue.java:30      public interface RQueue<V> extends Queue<V>, RExpirable, RQueueAsync<V> {
api/RBlockingQueue.java:35  public interface RBlockingQueue<V> extends BlockingQueue<V>, RQueue<V>, RBlockingQueueAsync<V> {
api/RSortedSet.java:29  public interface RSortedSet<V> extends SortedSet<V>, RExpirable {
api/RLock.java:28       public interface RLock extends Lock, RLockAsync, RObservable {
api/RLocalCachedMap.java:33  public interface RLocalCachedMap<K, V> extends RMap<K, V> {
```

规模感（4.8.0 实测）：`api/` 共 388 个文件，其中 **81 个 Reactive 接口、81 个 Rx 接口、71 个 Async 接口**。R 接口 ↔ Redis 数据结构对应关系速查见 11.1 节附表。

## 4.2 RedissonObject 基类与 codec 注入

**白话**：所有实现类的公共底座只有三样东西：名字（含 NameMapper 转换后的 rawName）、codec、commandExecutor。codec 决定"Java 对象 ↔ Redis 字节"的翻译方式，在构造 R 对象时传入（`redissonClient.getMap("m", new JsonJacksonCodec())`），否则用 Config 的默认 Kryo5Codec。

值得单独一提的是 4.8.0 的 codec 全家桶（`codec/` 目录，38 个文件）：Jackson 2 系（Json/Cbor/Smile/Ion/Avro/MsgPack + Typed 前缀的带类型版本）、**Jackson 3 系**（4.5.0 新增 `AvroJackson3Codec` 等，适配 `tools.jackson` 新命名空间）、Kryo5Codec（默认）、ForyCodec/JsonForyCodec/TypedJsonForyCodec（4.8.0 优化后编码提速 2 倍，CHANGELOG）、ProtobufCodec、SerializationCodec（JDK 序列化）、以及压缩装饰家族 LZ4/SnappyV2/ZStd（ZStd 4.8.0 提速最高 91 倍）。`CompositeCodec`（key/value 可分别指定 codec）与 `codec/ReferenceCodecProvider`（跨模块按注解查找 codec）是组合用的胶水。

## 4.3 RBucket / RAtomicLong：一 key 一世界的极简对象

**白话**：`RBucket<V>` 就是"一个 String key 装一个对象"（SET/GET + TTL + 比较），`RAtomicLong` 是 INCRBY 的原子计数封装，`RBinaryStream` 是流式读写一个大 value。它们是理解"R 对象 = 命令的类型化包装"最干净的样本，也常被用来做分布式配置缓存、全局序号。

【源码证据】`api/RBucket.java:32` `public interface RBucket<V> extends RExpirable, RBucketAsync<V>`；`api/RTimeSeries.java:40`、`api/RCountMin.java`（4.8 新增，计数-最小草图）、`api/RVectorSet.java`（向量集，底层 Redis 8.0 的 vector set）均为同期新增对象。注意 CHANGELOG 4.8.0 的 `RVectorStore`/`RSemanticCache`/`RAgentMemory` 等 AI 对象属 **Redisson PRO**，CORE 的 api 包中并无对应接口（实测 `ls api | grep -i vector` 仅 `RVectorSet` 与 `RBitVectorStore`）——读文档时勿把 PRO 特性当开源能力。

## 4.4 RMap：把 ConcurrentHashMap 平移到 Redis

**白话**：`RedissonMap` 的每个方法几乎一一对应一条 hash 命令（`get`→HGET、`put`→HSET、`fastPut`→HSET 免返回、`getAll`→HMGET、`readAllMap`→HGETALL），外加两类增强：**read-through（MapLoader，缓存未命中自动回源数据库）**与 **write-through/write-behind（MapWriter，写缓存自动同步数据库）**。

【源码证据】`api/MapOptions.java:38,134-167`——Writer 配置三件套：

```java
public enum WriteMode {          // :38
    WRITE_THROUGH, WRITE_BEHIND
}
public MapOptions<K, V> writeBehindBatchSize(int writeBehindBatchSize)  // :134
public MapOptions<K, V> writeBehindDelay(int writeBehindDelay)          // :151
```

write-behind 模式下写操作先入客户端缓冲，由后台任务按 `writeBehindDelay` 聚合批量刷库（WriteBehindService 持有各 map 的队列，见 `Redisson.java:67,89` 的 `writeBehindService` 字段）——这是"削峰写库"的标准姿势，代价是**写缓存成功 ≠ 已落库**，宕机会丢缓冲（业务需权衡 ack 语义）。

## 4.5 RMapCache 与 EvictionScheduler：条目级过期与淘汰

**白话**：Redis 的 hash 没有条目级 TTL（8.x 的 HEXPIRE 是后来者），于是 Redisson 自己造：`RMapCache` 把每个条目的到期时间记进**辅助 zset**，由客户端侧的 EvictionScheduler 定时把到期条目清走。4.6+ 也适配了 Redis 7.4+ 的原生 HEXPIRE：`RMapCacheNative` 直接用服务端条目过期，无需辅助结构。

【源码证据】`RedissonMapCache.java:66,151,171`——put 时同一条 Lua 里维护数据 hash 与两个 zset（按 ttl 与 maxIdleTime 索引）：

```java
public class RedissonMapCache<K, V> extends RedissonMap<K, V> implements RMapCache<K, V> {
    ...
    + "redis.call('zadd', KEYS[3], t + tonumber(ARGV[1]), ARGV[2]); "   // :151 到期时间 zset
    + "redis.call('zadd', KEYS[4], tonumber(ARGV[1]), ARGV[2]); "       // :171 空闲时间 zset
```

清扫侧：`eviction/` 包一个调度器管五类对象——`MapCacheEvictionTask`、`SetCacheEvictionTask`、`ScoredSetEvictionTask`、`MultimapEvictionTask`、`TimeSeriesEvictionTask`、`JCacheEvictionTask`（`eviction/` 目录列表实测）。调度频率自适应：空闲时回落到 `maxCleanUpDelay`（默认 30 分钟），繁忙时降到 `minCleanUpDelay`（5 秒）——对应 `config/Config.java:98,100`。**读法提醒**：RMapCache 的过期精度取决于客户端在跑（客户端全挂了没人清扫，过期条目会残留到下次调度）；要服务端精度用 RMapCacheNative 或原生 HEXPIRE。

## 4.6 RLocalCachedMap：读写分离场景的"本地缓存 + 广播失效"

**白话**：读多写少的场景，每次都打 Redis 太浪费。RLocalCachedMap 在 JVM 内放一层近缓存（默认 Caffeine/自定义 Map），**读走本地、写打 Redis 并广播失效消息**让其他节点的近缓存也失效——本质是"客户端自建的、以 pub/sub 为失效总线的读缓存"。这也是 4.8.0 RESP3 client-side caching（`RClientSideCaching`，由服务端 tracking 主动推送失效）的"手动档前辈"。

【源码证据】`api/LocalCachedMapOptions.java:42,64,83`——四组策略旋钮：

```java
public enum ReconnectionStrategy {   // :42 订阅断线重连期间本地缓存怎么办
    NONE, LOAD, CLEAR
}
public enum SyncStrategy {           // :64 广播消息里带什么
    INVALIDATE, UPDATE
}
public enum EvictionPolicy {         // :83 本地缓存容量策略
    NONE, LRU, SOFT, WEAK, LFU
}
```

失效链路：写操作 → `MapCacheEventCodec` 编码失效事件 → RTopic 广播 → 各节点 `LocalCachedMapListener` 收到后删除本地条目（`codec/MapCacheEventCodec.java`、CORE 根 `RedissonLocalCachedMap.java`）。**陷阱**：SyncStrategy.INVALIDATE 只失效不回填，下次读重新穿透 Redis；UPDATE 直接推新值但要求 value 可整体传输。缓存一致性敏感的业务请先读 wiki 的"不要把 RLocalCachedMap 当强一致缓存"章节再上生产。

## 4.7 概率结构与限流器

**白话**：这一族对象把 Redis 的位图、HyperLogLog、以及客户端用 Lua 实现的"伪结构"打包成 JDK 风格 API。两个最常被问到的：

- **RBloomFilter**（`api/RBloomFilter.java:28`）：`tryInit(expectedInsertions, falseProbability)` 时按公式计算位图大小与哈希函数个数，`add/contains` 走 Redis BITSET 位操作，误判率可控；容量定死后不能扩——先算准再 init。
- **RRateLimiter**（`api/RRateLimiter.java:29`）：令牌桶算法。`trySetRate(RateType, rate, interval)` 把 rate/interval/type 存进一个 hash，`acquire/tryAcquire` 用 Lua 在服务端原子地"算令牌、扣令牌"——限流状态天然分布式共享，这是单机 Guava RateLimiter 做不到的。

4.8.0 新增 `RCountMin`（Count-Min Sketch，频次估计，`api/RCountMin.java` 实测存在）与 `RTimeSeriesNative`（基于服务端原生时间序列）补齐概率/时序家族。

## 4.8 LiveObjectService：字节码增强路线（了解即可）

**白话**：RMap 是"显式调用 API"操作分布式对象；LiveObject 走另一条路——**用 Javassist 给普通 POJO 做字节码增强，把每个字段变成一次 Redis hash 读写**，`person.setName("x")` 即一次 HSET。适合"把 Redis 当分布式内存对象数据库"的建模偏好，但字段级网络往返的代价与调试成本使其在生产中远不如 RMap 常用。

【源码证据】`liveobject/` 包 + CORE 根 `RedissonLiveObjectService.java`；注解 `@RId`（标识主键）、`@RCascade`（级联，2.5.0 引入，CHANGELOG `CHANGELOG.md:3355`）。Redison 构造函数里可见其基础设施注册：`liveObjectClassCache`（`Redisson.java:69`）缓存增强类。

## 4.9 本章小结

- api 包两棵树：同步对象树（RObject→RExpirable→具体对象，挂 JDK 集合接口）与异步孪生树（Async/Reactive/Rx 各 71/81/81 个接口）；4.8.0 共 388 个 api 文件。
- 所有实现类共享"rawName + codec + commandExecutor"三件套；codec 38 个，默认 Kryo5Codec，Jackson 3/Fory/ZStd 是 4.5~4.8 的性能主线。
- RMap = hash 命令 + MapLoader/MapWriter（读写穿透/异步批量落库）；RMapCache 用"hash + 双 zset 索引 + EvictionScheduler 定时清扫"实现条目级过期，RMapCacheNative 改用服务端 HEXPIRE；RLocalCachedMap = 本地缓存 + pub/sub 广播失效（四组策略旋钮）。
- 概率家族（Bloom/HLL/BitSet/CountMin）、令牌桶限流器（Lua 原子）、LiveObject（Javassist 字段级增强）各有明确的适用边界——AI/向量类的 RVectorStore 等是 PRO 特性，开源版只有 RVectorSet。

---
# 五、分布式锁与同步器（lock 家族 + renewal + pubsub）

> 本章对应源码：CORE 根的锁实现类、`renewal/`（看门狗）、`pubsub/`（唤醒）。锁是 Redisson 被使用最多的能力，也是读源码收益最大的一章。

## 5.1 数据结构：一把锁 = 一个 hash

**白话**：Redisson 的可重入锁在 Redis 里就是一个 hash：key 是锁名，**field 是 `客户端UUID:线程ID`，value 是重入次数**。"谁持有"由 field 回答，"重入几层"由 value 回答，"还要持有多久"由整个 key 的 PTTL 回答。

【源码证据】`RedissonBaseLock.java:57-70`——所有权命名：

```java
public RedissonBaseLock(CommandAsyncExecutor commandExecutor, String name) {
    ...
    this.id = getServiceManager().getId();        // 客户端全局 UUID（ServiceManager.java:125 生成）
    this.entryName = id + ":" + name;             // 客户端内去重用的 entry 名
    ...
}
protected String getLockName(long threadId) {
    return id + ":" + threadId;                   // hash 的 field
}
```

同 JVM 不同线程重入，field 不同；同线程重复 lock，field 相同、value 递增。**这直接推出一个初学者必知的结论**：`lock()` 与 `unlock()` 必须同线程（默认重载下），跨线程要显式用 `lockAsync(threadId)` 家族传递 threadId。

## 5.2 加锁：7 行 Lua 的互斥核心

**白话**：`lock()` 的同步实现分三步：先试一次加锁（Lua）；没抢到就**订阅锁频道**；然后循环"再试 + 按剩余 TTL 定时等"——被唤醒的信号是别的客户端解锁时发的 pub/sub 消息。互斥的原子性完全在 Lua 里。

【源码证据】`RedissonLock.java:214-224`——加锁脚本全文（`tryLockInnerAsync`）：

```java
return evalWriteSyncedNoRetryAsync(getRawName(), LongCodec.INSTANCE, command,
        "if ((redis.call('exists', KEYS[1]) == 0) " +            // 锁不存在
                    "or (redis.call('hexists', KEYS[1], ARGV[2]) == 1)) then " +  // 或本线程已持有（重入）
                "redis.call('hincrby', KEYS[1], ARGV[2], 1); " +  // 重入计数 +1（首次即从 0→1）
                "redis.call('pexpire', KEYS[1], ARGV[1]); " +     // 刷新整把锁的 TTL
                "return nil; " +                                  // nil = 加锁成功
            "end; " +
            "return redis.call('pttl', KEYS[1]);",                // 失败：返回剩余毫秒数
        Collections.singletonList(getRawName()), unit.toMillis(leaseTime), getLockName(threadId));
```

读这段 Lua 的四个要点：

1. **判-写合一**：`exists/hexists` 检查与 `hincrby/pexpire` 写入在同一个 Lua 里原子完成，不存在"检查时没人持有、写入时被插队"的竞态——这就是"互斥"的全部秘密，没有魔法。
2. **重入即加一**：无论首次还是重入，都走 `hincrby +1`，并**刷新整把锁 TTL**——重入操作也会续命。
3. **失败返回 PTTL**：调用方拿到"还剩多久锁空"，用它决定等多久（见下）。
4. **走 `evalWriteSyncedNoRetryAsync`**：两层含义——`syncedEval`（3.6 节，主从拓扑下先 WAIT 从库确认）+ noRetry（3.4 节，响应超时不盲目重发）。

【源码证据】`RedissonLock.java:103-150`——同步 `lock()` 主循环（`leaseTime=-1` 即看门狗模式）：

```java
private void lock(long leaseTime, TimeUnit unit, boolean interruptibly) throws InterruptedException {
    long threadId = Thread.currentThread().getId();
    Long ttl = tryAcquire(-1, leaseTime, unit, threadId);
    if (ttl == null) { return; }                                  // 抢到了
    CompletableFuture<RedissonLockEntry> future = subscribe(threadId);   // ① 订阅解锁频道
    pubSub.timeout(future);
    RedissonLockEntry entry = ... commandExecutor.get(future);    //    等订阅建立
    try {
        while (true) {
            ttl = tryAcquire(-1, leaseTime, unit, threadId);      // ② 再试一次
            if (ttl == null) { break; }
            if (ttl >= 0) {
                entry.getLatch().tryAcquire(ttl, TimeUnit.MILLISECONDS);  // ③ 按 PTTL 等信号量/超时
            } else {
                entry.getLatch().acquire();                       //    TTL<0 防御分支
            }
        }
    } finally {
        unsubscribe(entry, threadId);                             // ④ 无论如何退订
    }
}
```

**为什么等待靠 pub/sub 而不是纯轮询**：持有者解锁时会 PUBLISH 一条 `UNLOCK_MESSAGE=0`（`pubsub/LockPubSub.java:29`）到 `redisson_lock__channel:{锁名}`（`RedissonLock.java:70-72`），等待方收到后释放信号量许可，循环立刻重试加锁——把"轮询间隔"从固定值优化为"事件驱动 + PTTL 兜底"。PTTL 兜底覆盖的是"持有者崩溃、没人 PUBLISH"的场景：锁到期自动释放，等待方最多等一个 PTTL 就会自行重试。

## 5.3 解锁与 unlock latch：4.x 的幂等补丁

**白话**：解锁 Lua 做"减一、归零则删除并广播"。4.x 加了一个巧妙的 `redisson_unlock_latch` 辅助 key：解锁结果（0/1）连同 TTL 写进 latch，**超时重试方先读 latch 拿到既有结论**，避免"解锁命令已执行但响应丢失 → 重试 → 重复广播/误判"。

【源码证据】`RedissonLock.java:347-371`——解锁脚本全文（`unlockInnerAsync`，注释为笔者所加）：

```java
"local val = redis.call('get', KEYS[3]); " +               // KEYS[3] = redisson_unlock_latch:{name}:{requestId}
      "if val ~= false then return tonumber(val); end; " + // 已有结论：直接返回，不再执行（幂等）
      "if (redis.call('hexists', KEYS[1], ARGV[3]) == 0) then return nil; end; " +  // 非持有者：nil
      "local counter = redis.call('hincrby', KEYS[1], ARGV[3], -1); " +
      "if (counter > 0) then " +
          "redis.call('pexpire', KEYS[1], ARGV[2]); " +     // 还有剩余重入层：只续命
          "redis.call('set', KEYS[3], 0, 'px', ARGV[5]); return 0; " +
      "else " +
          "redis.call('del', KEYS[1]); " +                  // 归零：删锁
          "redis.call(ARGV[4], KEYS[2], ARGV[1]); " +       // PUBLISH 解锁消息唤醒等待者
          "redis.call('set', KEYS[3], 1, 'px', ARGV[5]); return 1; " +
      "end; "
```

配套逻辑在基类：latch key 命名 `prefixName("redisson_unlock_latch", name) + ":" + requestId`（`RedissonBaseLock.java:212-214`），requestId 每次解锁调用新生成（`:157`）；解锁完成后 latch 被删除（`:235-244`）。异常路径也在这里收口：脚本返回 nil 意味着"当前线程不持有该锁"，上层翻成 `IllegalMonitorStateException`（`:172-175`）——**unlock 抛这个异常几乎都是"锁已过期被释放"**，是看门狗失效或业务执行超时的信号。

## 5.4 看门狗：watchdog/3 周期的批量续期（renewal 包）

**白话**：`lock()` 不传 leaseTime 时，锁的 TTL 是 `lockWatchdogTimeout`（默认 30s），且有个后台任务**每 10 秒（=timeout/3）把仍持有的锁 TTL 重置回 30s**——只要客户端活着锁就不过期，客户端崩溃则 30s 后锁自动释放。这正是"不写死 leaseTime"的价值：业务执行 5 分钟也不会中途丢锁；也是最大的坑：**忘了 unlock 就等于把锁泄漏 30 秒，且任何异常路径都要保证 unlock 被调用**（try-finally）。

4.x 的看门狗被重构成**全局单例调度器 + 批量续期**，替代 3.x"每把锁一个 netty Timeout"的模型：

【源码证据】`renewal/LockRenewalScheduler.java:28-60`——三类任务、懒创建：

```java
public final class LockRenewalScheduler {
    private final AtomicReference<LockTask> reference = new AtomicReference<>();            // 普通锁
    private final AtomicReference<FastMultilockTask> multilockReference = new AtomicReference<>();  // FasterMultiLock
    private final AtomicReference<ReadLockTask> readLockReference = new AtomicReference<>();        // 读锁
    ...
    public LockRenewalScheduler(CommandAsyncExecutor executor) {
        this.internalLockLeaseTime = executor.getServiceManager().getCfg().getLockWatchdogTimeout();
        this.batchSize = executor.getServiceManager().getCfg().getLockWatchdogBatchSize();  // 默认 100
    }
    public void renewLock(String name, Long threadId, String lockName) {
        reference.compareAndSet(null, new LockTask(internalLockLeaseTime, executor, batchSize));
        LockTask task = reference.get();
        task.add(name, lockName, threadId);     // 只是把锁登记进集合，真正的定时器在任务里
    }
```

【源码证据】`renewal/RenewalTask.java:62-69`——调度周期：

```java
public void schedule() {
    if (!running.get()) return;
    long internalLockLeaseTime = executor.getServiceManager().getCfg().getLockWatchdogTimeout();
    executor.getServiceManager().newTimeout(this, internalLockLeaseTime / 3, TimeUnit.MILLISECONDS);
}
```

【源码证据】`renewal/LockTask.java:82-95`——一次 EVAL 续一把"批"：

```java
CompletionStage<List<String>> f = executor.syncedEval(firstName, LongCodec.INSTANCE,
        new RedisCommand<>("EVAL", new ContainsDecoder<>(keys)),
          "local result = {} " +
                "for i = 1, #KEYS, 1 do " +
                    "if (redis.call('hexists', KEYS[i], ARGV[i + 1]) == 1) then " +
                        "redis.call('pexpire', KEYS[i], ARGV[1]); " +     // 仍持有 → 续回 30s
                        "table.insert(result, 1); " +
                    "else table.insert(result, 0); end; " +               // 已释放 → 返回 0
                "end; return result;",
        new ArrayList<>(keys), args.toArray());
return new ChunkExecution<>(f, existingNames -> { ... cancelExpirationRenewal(key, ...) });  // 不存在的锁取消登记
```

三个设计点：**批量**（每批至多 `lockWatchdogBatchSize=100` 把锁一次 EVAL，网络往返从 O(锁数) 降到 O(锁数/100)）；**集群感知**（`RenewalTask.java:83-88,154-167` 把锁名按 CRC16 槽位分组，同槽位的锁才发给同一节点，避免 CROSSSLOT）；**自愈**（续期发现锁已不存在 → 自动取消登记，`:97-102`）。看门狗登记发生在加锁成功时（`RedissonLock.java:195-203`：Lua 返回 null 且未指定 leaseTime → `scheduleExpirationRenewal(threadId)`），注销发生在解锁或锁丢失时（`RedissonBaseLock.java:76-78`）。

## 5.5 唤醒链路：从 PUBLISH 到 Semaphore.release

**白话**：把 5.2 的"被唤醒"展开成全链路：等待方在 `PublishSubscribe` 里注册"entry（引用计数 + 信号量 + 监听器队列）"，解锁方 PUBLISH，`LockPubSub.onMessage` 收到 `UNLOCK_MESSAGE` 后释放信号量、唤醒一个等待者。公平锁广播 `READ_UNLOCK_MESSAGE` 唤醒全部等待者。

【源码证据】`pubsub/PublishSubscribe.java:70-134`（订阅的引用计数复用，节选）：

```java
public CompletableFuture<E> subscribe(String entryName, String channelName, int permits) {
    AsyncSemaphore semaphore = service.getSemaphore(new ChannelName(channelName));  // 每通道一把信号量
    ...
    E entry = entries.get(entryName);
    if (entry != null) {                     // 同一客户端已有人订阅该锁：只加引用计数
        entry.acquire(permits); ...
    }
    E value = createEntry(newPromise);
    E oldValue = entries.putIfAbsent(entryName, value);   // 竞争兜底
    ...
    CompletableFuture<PubSubConnectionEntry> s =
        service.subscribeNoTimeout(LongCodec.INSTANCE, channelName, semaphore, listener);  // 真正 SUBSCRIBE
```

【源码证据】`pubsub/LockPubSub.java:42-52`——消息语义：

```java
protected void onMessage(RedissonLockEntry value, Long message) {
    if (message.equals(UNLOCK_MESSAGE)) {           // 互斥锁解锁：唤醒一个等待者
        value.tryRunListener();
        value.getLatch().release();
    } else if (message.equals(READ_UNLOCK_MESSAGE)) { // 读锁释放：唤醒所有等待者
        value.tryRunAllListeners();
        value.getLatch().release(value.getLatch().getQueueLength());
    }
}
```

`RedissonLockEntry`（CORE 根，99 行）就是"`Semaphore latch` + `ConcurrentLinkedQueue<Runnable> listeners` + 引用计数 counter"三件套。整个链路的槽点也在这里：**同一客户端对同一锁的等待者共享一个 entry**（entryName = UUID:name，5.1 节），所以 lock() 的 `tryAcquire` 循环里每次唤醒只保证一个线程拿到许可——这是 pub/sub 唤醒"至少一个、不确定是谁"的语义，最终正确性仍由 Lua 加锁的原子性兜底（唤醒只是"提前重试"的优化，永不影响正确性）。

## 5.6 锁家族地图

CORE 根目录实测的锁类一览（4.8.0）：

| 类 | 机制要点 | 证据 |
|---|---|---|
| `RedissonLock` | 非公平可重入锁（本章主角） | `RedissonLock.java:43` 注释自述 "Implements a non-fair locking" |
| `RedissonFairLock` | 公平排队：额外维护等待队列，加锁 Lua 先查"队列里是否有人排在我前面" | `RedissonFairLock.java`（351 行，RedissonLock 子类） |
| `RedissonNonReentrantLock` / `RedissonNonReentrantFairLock` | 不可重入变体（value 不计数，直接 set） | 同包 |
| `RedissonSpinLock` | 无 pub/sub，忙等自旋（3.15.0 引入，CHANGELOG `CHANGELOG.md:2020`），低延迟高 CPU | 同包 |
| `RedissonReadWriteLock` → `RedissonReadLock`/`RedissonWriteLock` | 读写锁：一个 hash 里两个集合字段——读持有人 map（mode:read）与写持有人字段（mode:write），加读锁先查"无写者或自己是写者" | `RedissonReadLock.java:191 行`/`RedissonWriteLock.java:145 行`；读锁续期走 `renewal/ReadLockTask`（`renewal/LockRenewalScheduler.java:44-48`） |
| `RedissonMultiLock` / `RedissonFasterMultiLock` | 把 N 把锁当一把用：全部加锁成功才算成功，失败回滚已持有的；Faster 变体用 `renewal/FastMultilockTask` 批量续期 | `RedissonMultiLock.java`（537 行） |
| `RedissonRedLock` | 已弃用的多实例 RedLock 算法（63 行薄壳） | `RedissonRedLock.java`；官方文档已建议单实例 + fencing |
| `RedissonFencedLock` | **fencing token 锁**：每次加锁 Lua 里 `INCR` 一个单调递增 token（`RedissonFencedLock.java:110-116` `local token = redis.call('incr', KEYS[2])`），token 存于 `redisson_lock_token:{name}`（`:53`），业务拿着 token 去写下游存储，存储侧拒绝旧 token——解决"锁过期后旧持有者还活着"的终极正确性问题 | 同包 |

## 5.7 其他同步器

同一套"EVAL 原子 + pub/sub 唤醒"配方还有三个常客：`RSemaphore`/`RPermitExpirableSemaphore`（许可可带 TTL，防泄漏）、`RCountDownLatch`（`pubsub/CountDownLatchPubSub.java` 专用消息）、`RRateLimiter`（4.7 节）。它们与锁共享 5.5 节的订阅基础设施（各有一个 `PublishSubscribe` 子类，`pubsub/` 目录实测共四个）。

## 5.8 正确性讨论：分布式锁的三个层次

读源码 + CHANGELOG 可以把 Redisson 对"锁正确性"的回答整理成三层，这也是面试与生产排障的高频区：

1. **单实例原子性（默认层）**：EVAL 保证互斥，pub/sub 保证等待效率，看门狗保证"活着就不过期、死了 30s 自动释放"。**已知缺口**：主从异步复制下，主节点写入锁后未同步到从就宕机，新主上锁不存在——另一个客户端可再次加锁（Martin Kleppmann 与 antirez 的著名争论场景）。Redisson 的缓解是 3.17.0 的 `checkLockSyncedSlaves=true`：关键锁操作经 `syncedEval` 先 `WAIT` 从库确认再执行（3.6、5.2 节），把窗口收窄到"从库确认后仍同时丢失主从"的极端场景。
2. **多实例（RedLock）**：`RedissonRedLock` 已弃用。社区共识与 Redisson 官方文档现状：单实例/主从 + 下述 fencing 足矣，多实例 RedLock 的复杂度收益比不划算。
3. **令牌层（fencing）**：`RedissonFencedLock` 每次加锁返回单调递增 token（5.6 节），要求**下游资源（数据库行 version、文件版本号等）拒绝旧 token 的写**。这是唯一能同时防"锁过期双持有"与"主从切换丢锁"的方案，代价是下游必须配合改造。

实践建议排序：能用 JVM 内锁就不用分布式锁 → 能接受 30s 级互斥就用默认 RLock + try-finally unlock → 对正确性有硬要求上 FencedLock + 下游校验 → 业务幂等设计永远是最后一道防线。

## 5.9 本章小结

- 锁 = hash（field=`UUID:threadId`，value=重入数）；加锁/解锁各是一段原子 Lua（`RedissonLock.java:214-224`、`:347-371`），加锁失败返回 PTTL 供等待方定时。
- 同步 `lock()` = try → subscribe → 循环（try + 按 PTTL 等信号量），唤醒走 `redisson_lock__channel` 的 PUBLISH（`UNLOCK_MESSAGE`），PTTL 兜底持有者崩溃场景；正确性永不依赖唤醒。
- 看门狗：全局 `LockRenewalScheduler` + 三类批量任务，周期 = `lockWatchdogTimeout/3`，每批 ≤100 把锁一次 EVAL 续期，按集群槽位分组；锁丢失自动取消登记。
- 4.x 的 unlock latch 给解锁加了幂等层；家族含公平/自旋/读写/Fenced/MultiLock，RedLock 已弃用。
- 正确性三层：原子 EVAL（默认）→ WAIT 同步复制（checkLockSyncedSlaves）→ fencing token（RedissonFencedLock + 下游配合）。

---

# 六、消息、队列与任务调度（pubsub / 队列家族 / executor / remote）

## 6.1 订阅基础设施的经济学（已在 2.5 节展开）

回顾要点：订阅连接独立成池（默认 50 条），每条订阅连接承载 ≤5 个订阅（`subscriptionsPerConnection`），`PubSubConnectionEntry` 以引用计数复用，断线后 `PublishSubscribeService.reattachPubSubListeners`（`pubsub/PublishSubscribeService.java:904`）自动重订阅。锁等待、近缓存失效、Topic、闭锁、信号量全部骑在这套设施上。

## 6.2 RTopic 家族：从 fire-and-forget 到可靠流

**白话**：`RTopic` 是 Redis PUBLISH/SUBSCRIBE 的类型化包装（消息经 codec 序列化）；`RPatternTopic` 订阅通配模式；`RReliableTopic` 则把消息**同时写进一个 Stream**，消费者带 ack，断线重连后从上次 ack 处重放——用 Redis Stream 换取"至少一次"投递。

【源码证据】`api/RTopic.java:29` `public interface RTopic extends RTopicAsync`；`RReliableTopic` 实现于 CORE 根 `RedissonReliableTopic.java`（底层 XADD/XREADGROUP/XACK，4.0.0 的 CHANGELOG 还宣告了 PRO 侧全功能 Reliable Pub/Sub：ack/分组/seek/DLT）。监听器返回的 listener id 可用于 `removeListener`（防内存泄漏的常规姿势：随对象生命周期注销）。

## 6.3 队列家族：BlockingQueue / DelayedQueue / 优先级 / 转移

**白话**：`RBlockingQueue` 就是 BLPOP/BRPOP 的 Java 化（`take()` 阻塞、`poll(timeout)` 带超时）；`RDelayedQueue` 是"延迟 N 秒后元素自动出现在目标队列"的魔法——实现是 zset 到期索引 + 一个**客户端定时搬运任务**；优先级队列用 zset 排序 + 惰性转移。

【源码证据】`RedissonDelayedQueue.java:55-62,104-105`——搬运与入队两段 Lua：

```java
// 搬运脚本（QueueTransferTask 周期执行）：
"local expiredValues = redis.call('zrangebyscore', KEYS[2], 0, ARGV[1], 'limit', 0, ARGV[2]); "
      + "redis.call('rpush', KEYS[1], value);"      // 到期元素推进目标 list
      + "redis.call('zrem', KEYS[2], unpack(expiredValues));"   // 从 zset 索引移除
// offer 脚本：
      + "redis.call('zadd', KEYS[2], ARGV[1], value);"   // 记录到期时间戳
      + "redis.call('rpush', KEYS[3], value);"           // 原始队列（暂存）
```

【源码证据】`QueueTransferTask.java:34`——搬运任务抽象类，每个延迟队列实例对应一个注册在全局 HashedWheelTimer 上的周期任务（`ServiceManager.java:157` 的 `queueTransferService` 统一管理其启停）。**读法提醒**：与 RMapCache 的清扫一样，搬运是客户端驱动的——消费方挂了延迟消息不会死，但**所有客户端都挂了**，到期元素会等任意一个 Redisson 客户端启动后才被搬出。生产上一般专门跑一个 RedissonNode 或常驻服务兜底。

## 6.4 RStream：消费者组的正面强攻

**白话**：Redis 5.0 的 Stream 是服务端原生消息队列，Redisson 的 `RStream`/`RStreamGroup` 把 XADD/XREADGROUP/XACK/XAUTOCLAIM 包成 `add/createGroup/consume/ack/autoClaim` 等 API，支持消费者组、pending 列表、自动认领（autoClaim/fastAutoClaim，3.15.0 引入，CHANGELOG `CHANGELOG.md:2025`）。需要"消息不丢 + 消费进度服务端管理"时优先选它而不是 RTopic。

## 6.5 RExecutorService：把 Runnable 塞进 Redis

**白话**：`RExecutorService` 把任务对象（须可被 codec 序列化，lambda 需要可序列化且类路径两端可用）写入 Redis（任务本体存 hash，任务 id 进 list 队列），任意节点的 Redisson 客户端都可以是执行者——"分布式线程池"。`RScheduledExecutorService` 追加延迟/周期调度与 Cron 表达式。

【源码证据】`RedissonExecutorService.java:130-136`——任务的 Redis 键布局：

```java
statusName = objectName + ":status";      // 执行器状态
schedulerQueueName = objectName + ":scheduler";   // 调度队列
```

执行侧 `executor/TasksRunnerService.java:59,381-416`——`executeRunnable(TaskParameters)` 从队列取任务、反序列化、执行、按需删除任务记录。任务 API 支持三种执行模式（`ExecutionMode`：IN_MEMORY 本地队列 / REDIS 单次执行 / REDIS_ACKED 带 ack）与取消（向队列投递取消指令）。Cron 调度在 `executor/ScheduledTasksService.java` 与 `executor/CronExpression.java`（自带 Cron 解析器，不依赖 cron-utils）。

**冷知识**：任务类会经 `executor/RedissonClassLoader.java` 做动态类加载——执行端没有任务类的字节码时，会从 Redis 里存的类定义加载（这是"lambda 跨节点执行"得以成立的原因）。

## 6.6 RRemoteService：请求-响应三件套

**白话**：RRemoteService 是"像调本地接口一样调远端实现"的 RPC：服务端 `register(MyService, impl)`，客户端 `get(MyService).call(x)`。底层是三件套——**请求队列**（BlockingQueue，`{interface}:{method}` 命名）、**任务与响应 map**、**pub/sub 完成通知**。

【源码证据】`RedissonRemoteService.java:44,78-83,125,175-181`：

```java
public class RedissonRemoteService extends BaseRemoteService implements RRemoteService {
...
String queue = getRequestQueueName(remoteInterface);
return queue + ":tasks";                                  // 任务详情 map 后缀
...
RBlockingQueue<String> requestQueue = getBlockingQueue(requestQueueName, StringCodec.INSTANCE);
...
String requestId = requestQueue.poll(timeout, timeUnit);  // 服务端：从请求队列取请求
RMap<String, RemoteServiceRequest> tasks = getMap(... + ":tasks");
RFuture<RemoteServiceRequest> taskFuture = getTask(requestId, tasks);
```

客户端调用 = 请求序列化进队列 + 订阅响应通道；服务端 poll 到请求、执行、把 `RemoteServiceResponse` 写回并通知；客户端拿到响应完成 future。可选 ack 模式（`invokeMethod` 的 ackTime）在"执行中崩溃重投"与"重复执行"之间做权衡——与所有 RPC 一样，**at-least-once 或幂等二选一**。

## 6.7 本章小结

- 订阅基础设施（独立池 + 引用计数复用 + 断线重订阅）是锁/Topic/闭锁/信号量的共同底座。
- RTopic fire-and-forget；RReliableTopic 用 Stream 换 at-least-once；要消费组语义直接用 RStream（autoClaim 认领僵尸消息）。
- RBlockingQueue = BLPOP/BRPOP；RDelayedQueue = zset 到期索引 + 客户端 QueueTransferTask 周期搬运（搬运依赖客户端活着，生产要常驻兜底）。
- RExecutorService = 任务存 Redis、任意节点执行、RedissonClassLoader 动态加载任务类；RScheduledExecutorService 支持延迟/周期/Cron；RRemoteService = 队列 + map + 通知的请求-响应桥。

---

# 七、事务、批处理与脚本

## 7.1 RTransaction：缓冲-提交模型（无回滚）

**白话**：RTransaction 不是 Redis 的 MULTI/EXEC 包装，而是一个**客户端缓冲事务**：事务内的 get/set 都先作用于本地缓冲并记录操作日志，`commit()` 时才把缓冲的变更按序真正提交到 Redis。它保证的是"**一个事务内的读看见自己未提交的写、提交是原子的、失败前已提交的步骤不回滚**"——文档明确定位为**提供提交原子性而非回滚能力**（无 ROLLBACK：`RTransaction.rollback()` 只做"丢弃本地缓冲"）。

【源码证据】`transaction/RedissonTransaction.java:50,55,227,292`：

```java
public class RedissonTransaction implements RTransaction {
    private final TransactionOptions options;
    ...
    public RFuture<Void> commitAsync() { ... }     // :227 按操作日志顺序执行缓冲变更
    public void commit() { ... }                   // :292
```

事务对象（`RedissonTransactionalMap/Bucket/Set/LocalCachedMap` 等，`transaction/` 目录）在 get 时会先应用缓冲；并发控制二选一：`TransactionOptions` 的 pessimistic 模式（对涉及 key 加 RedissonLock）或 optimistic 模式（提交时校验）。**适用边界**：跨多个 R 对象的"多 key 一致提交"，别拿它当关系型数据库事务用。

【源码证据】`api/TransactionOptions.java:27` `public final class TransactionOptions {`——`pessimistic()/optimistic()/responseTimeout()` 均在此配置。

## 7.2 RBatch 的两种姿势（回看 3.7 节）

IN_MEMORY（默认，pipeline 一次往返）与 REDIS_WRITE_ATOMIC（MULTI/EXEC，集群模式要求所有 key 同槽）。选型口诀：**只要"省往返"用默认；要"一批命令不被插队"才用原子模式**——后者在集群模式还限制 key 布局，多数业务用 Lua 实现原子批更自由。

## 7.3 RScript 与 RKeys：逃生舱口

**白话**：当 Redisson 没有你要的"对象"时，`RScript` 直接执行 Lua（`ReturnType.LONG/STRING/LIST/...`，4.0.0 改名后的枚举），`RKeys` 提供 key 空间级操作（`delete`、`deleteByPattern`（SCAN 扫描 + 批删，避免 KEYS 阻塞）、`getKeysByPattern` 游标迭代、`move/migrate/copy`）。这两个是"Redisson 的 PreparedStatement"——能力兜底，也让"用 Redisson 同时玩转原生 Redis"成为可能。

## 7.4 本章小结

- RTransaction = 本地缓冲 + commit 按序提交 + pessimistic/optimistic 并发控制；有提交原子性、无回滚。
- RBatch 的原子模式与 Lua 各有适用；默认 IN_MEMORY pipeline 已覆盖绝大多数"省往返"诉求。
- RScript/RKeys 是逃生舱：Lua 直写 + key 空间管理（SCAN 驱动的 deleteByPattern 是生产安全姿势）。

---
# 八、响应式与多客户端视图（reactive / rx）

## 8.1 四套 API，一套内核

**白话**：每个 RedissonClient 其实能给你四张"面孔"：`redissonClient`（同步 + 异步方法同接口）、`redissonClient.getReactive()`（Reactor）、`redissonClient.rxJava()`（RxJava3）。关键事实是：**后两张面孔没有独立实现**——用 JDK 动态代理把同步实现类的方法调用实时转译成 `Mono/Flux/RxObservable`，方法名去掉 Async 后缀、返回类型替换即可。

【源码证据】`Redisson.java:71-91,124-131`——三客户端同源（同一 ConnectionManager/调度器构造三个视图）：

```java
Redisson(Config config) {
    ...
    connectionManager = ConnectionManager.create(configCopy);
    ...
    commandExecutor = connectionManager.createCommandExecutor(objectBuilder, ...);
    evictionScheduler = new EvictionScheduler(commandExecutor);
    writeBehindService = new WriteBehindService(commandExecutor);
    connectionManager.getServiceManager().register(new LockRenewalScheduler(commandExecutor));
}

@Override
public RedissonRxClient rxJava() {
    return new RedissonRx(connectionManager, evictionScheduler, writeBehindService);
}
@Override
public RedissonReactiveClient reactive() {
    return new RedissonReactive(connectionManager, evictionScheduler, writeBehindService);
}
```

【源码证据】`reactive/ReactiveProxyBuilder.java:33-40`——动态代理转译器：

```java
public class ReactiveProxyBuilder {
    public static <T> T create(CommandReactiveExecutor commandExecutor, Object instance, Class<T> clazz) {
        return create(commandExecutor, instance, null, clazz);
    }
    ...  // ProxyBuilder.create((callable, instanceMethod) -> ...) 把同步调用包成 Publisher
```

而 `RedissonReactive`/`RedissonRx`（CORE 根，`RedissonReactive.java:44`、`RedissonRx.java:42`）只是"代理工厂 + 少量手写流式实现"（如 `ElementsStream`/`IteratorConsumer` 处理 SCAN 迭代的 Flux 化，`reactive/` 目录实测）。规模佐证：api 包 81 个 Reactive 接口与 81 个 Rx 接口几乎一一对应（4.1 节）。

## 8.2 RFuture：3.0 之后就是 CompletableFuture

**白话**：3.0.0 的 CHANGELOG 自述 "Fully compatible with JDK 8. ... `RFuture` extends `CompletionStage`"（`CHANGELOG.md:3346-3348`）。因此 async API 的返回值可以直接 `thenApply/thenCompose/whenComplete` 与 JDK 编排互操作；`misc/CompletableFutureWrapper` 只是把 `CompletionStage` 重新包回 `RFuture` 类型以维持 R 接口签名。**一个仍然要区分的点**：RFuture 完成发生在 netty EventLoop 线程——回调里做重活/阻塞会拖垮整个事件循环（RedisExecutor 的超时提示文案直接点名这种反模式："Check that there are no blocking invocations in async/reactive/rx listeners"，`RedisExecutor.java:265-266`）。

## 8.3 本章小结

- 四套 API 一套内核：Reactive/Rx 由 `ReactiveProxyBuilder` 动态代理实时转译，无重复实现。
- RFuture = CompletionStage；回调跑在 netty EventLoop 上，回调内禁阻塞。
- 响应式特别适合的场景：SCAN/Stream 的流式消费（Flux 化）、fan-out 聚合、虚拟线程外的异步编排。

---

# 九、集成生态（redisson-spring / tomcat / mybatis / hibernate / quarkus…）

## 9.1 redisson-spring：starter 如何替你选 Config

**白话**：`redisson-spring-boot-starter` 的工作只有两件事：**按 Spring Boot 的 `spring.data.redis` 配置自动生成 RedissonConfig**（单机/哨兵/集群自动对号），并**把 spring-data-redis 的 `RedisConnectionFactory` 换成 Redisson 实现**——于是 RedisTemplate、@Cacheable、Spring Session 全部无缝切到 Redisson 底座。想手写 Config 时提供 `RedissonClient` bean 即可完全接管。

【源码证据】模块结构（目录实测）：

```
redisson-spring/
├── redisson-spring-boot-starter     # 自动装配入口
│   └── starter/RedissonAutoConfiguration.java:73  public class RedissonAutoConfiguration
├── redisson-spring-cache            # Spring Cache 抽象实现
│   └── .../RedissonSpringCacheManager.java  RedissonCache.java  CacheConfig.java
├── redisson-spring-transaction      # Spring 事务管理器
│   └── .../RedissonTransactionManager.java  ReactiveRedissonTransactionManager.java
└── redisson-spring-data/            # 19 个子模块，按 spring-data-redis 版本对号入座
    ├── redisson-spring-data-16 … 27   （Spring Data Redis 1.6 ~ 2.7）
    └── redisson-spring-data-30 … 41   （3.0 ~ 4.1；4.0/4.1 为 4.0.0 新增，CHANGELOG:381）
```

【源码证据】`RedissonAutoConfiguration.java:137,208-241`——通过反射读取 Boot 的 `RedisProperties`（cluster/sentinel 分支）自动构造对应 Redisson 配置：集群分支 `Method clusterMethod = ReflectionUtils.findMethod(RedisProperties.class, "getCluster")`；哨兵分支逐项搬运 `sentinel().getMaster()/getUsername()/getPassword()`。

**版本对号表**（选依赖时用）：Boot 2.x → starter 内自动带对应 spring-data 子模块；Boot 3.x → 30~35；Boot 4.x → 40/41。19 个 spring-data 子模块是 Redisson "适配而非重造"哲学最直白的体现——每个子模块只是把 Redisson 的命令层包成 spring-data-redis 的 `RedisConnection` SPI。

## 9.2 Spring Cache：@Cacheable 之于 Redisson

**白话**：`RedissonSpringCacheManager` 把 `@Cacheable` 的每个 cacheName 变成一个 Redis 对象（默认 RMapCacheNative/RMap，可在 cacheName 后缀 `#ttl=30m#maxIdle=10m#maxSize=1000` 风格的参数定制 TTL/容量），并提供 `RedissonSpringCacheNativeManager` 走服务端 HEXPIRE。与本地 Caffeine 缓存的分野：多实例下"任一节点 @CachePut，其他节点缓存立即失效"需要分布式失效——Redisson 的 cache 实现经 pub/sub 广播（复用 4.6 节的 `MapCacheEventCodec` 事件通道）。

【源码证据】`redisson-spring/redisson-spring-cache/.../cache/` 目录：`RedissonCache.java`、`CacheConfig.java`（ttl/maxIdle/maxSize 三元组）、`NullValue.java`（null 值穿透哨兵）、`RedissonCacheMeterBinderProvider*.java`（Micrometer 指标，V4 双版本适配 Spring Framework 6/7）。

## 9.3 Spring 事务：RedissonTransactionManager

**白话**：`redisson-spring-transaction` 提供 `RedissonTransactionManager`（实现 Spring `PlatformTransactionManager`），使 `@Transactional` 边界内的 RMap/RBucket 操作走第七章的 RTransaction 缓冲-提交——**这是"缓存操作纳入 Spring 事务边界"的官方答案**（响应式版 `ReactiveRedissonTransactionManager` 同目录）。

## 9.4 其余集成模块一览

| 模块 | 作用 | 要点 |
|---|---|---|
| redisson-tomcat | Tomcat Session 复制（`RedissonSessionManager`） | 集群部署的 sticky-free 会话；支持 readMode 与 updateMode（AFTER_REQUEST 等） |
| redisson-mybatis | MyBatis 二级缓存（实现 `org.apache.ibatis.cache.Cache` 接口） | 替换默认 PerpetualCache，多节点缓存一致 |
| redisson-hibernate | Hibernate L2 缓存 RegionFactory | 4.8.0 修复 `createCollectionKey` 高 CPU 问题（CHANGELOG:49） |
| redisson-quarkus / redisson-micronaut / redisson-helidon | DI 容器原生扩展 | 3.16.0（2021-06）引入，`CHANGELOG.md:1892-1894`；quarkus 模块分 runtime/deployment 双构件 |
| jcache（CORE 内 `jcache/`） | JSR-107 JCache 实现 | Spring Boot `spring.cache.type=jcache` 亦可选用；支持数据分区（3.16.0，CHANGELOG:1895） |
| redisson-all | fat jar 聚合 | 想跳过依赖管理时的全家桶 |

## 9.5 本章小结

- starter 的价值 = Boot 配置自动翻译成 Config（反射读 RedisProperties）+ RedisConnectionFactory 换成 Redisson 实现；19 个 spring-data 子模块按版本对号。
- Spring Cache 的 cacheName 支持 `#ttl#maxIdle#maxSize` 后缀定制；分布式失效走 pub/sub。
- `RedissonTransactionManager` 把 RTransaction 挂进 `@Transactional`；Tomcat/MyBatis/Hibernate/三大 DI 容器各是一个"方言适配包"。

---

# 十、贯通视图：三条时间线

把前九章的主干叠加成三条时间线，读完应能在脑中"放电影"。

## 10.1 时间线一：`Redisson.create(config)` 启动

```
Redisson.create(config)                       Redisson.java:119-121
 └─ new Redisson(config)
     ├─ Version.logVersion()                  版本自报家门（服务端 CLIENT SETINFO 也会带）
     ├─ Config configCopy = new Config(config)   拷贝构造：补默认 codec=Kryo5Codec 等   Config.java:163-171
     ├─ ConnectionManager.create(configCopy)  按拓扑类型实例化 Single/Cluster/Sentinel/... 管理器
     │    └─ ServiceManager：EventLoopGroup(nettyThreads=32) + HashedWheelTimer + DNS 解析组 + UUID
     ├─ RedissonObjectBuilder（referenceEnabled=true 时）  弱引用/对象注册表
     ├─ commandExecutor = connectionManager.createCommandExecutor(...)
     ├─ EvictionScheduler / WriteBehindService 挂载
     └─ ServiceManager.register(new LockRenewalScheduler(...))   看门狗调度器就位   Redisson.java:77-91
连接池按需建立（懒连接 + connectionMinimumIdleSize 预热），订阅池独立；此后一切请求走 3.3 节的 RedisExecutor 状态机
```

## 10.2 时间线二：一次 `lock()`（看门狗模式）

```
线程 T: lock()
 ├─ EVAL（exists/hexists → hincrby/pexpire，RedissonLock.java:214-224，走 syncedEvalNoRetry：
 │    主从拓扑下先 WAIT 从库确认，noRetry 禁盲目重发）
 ├─ 成功（返回 nil）→ LockRenewalScheduler.renewLock 登记（name→threadId）→ 全局任务开始
 │    每 10s（watchdog/3，RenewalTask.java:62-69）批量 EVAL 续期（每批 ≤100 把锁、按槽位分组，LockTask.java:82-95）
 ├─ 失败 → subscribe("redisson_lock__channel:{name}")（引用计数复用订阅，PublishSubscribe.java:70-134）
 │    → 循环：tryAcquire + entry.getLatch().tryAcquire(pttl)（RedissonLock.java:121-145）
 │    → 持有者 unlock：Lua del + PUBLISH UNLOCK_MESSAGE（RedissonLock.java:362-366）
 │         → LockPubSub.onMessage → latch.release() → 唤醒 → 重试 EVAL（LockPubSub.java:42-47）
 └─ 线程 T unlock：requestId 生成 → 解锁 Lua（latch 幂等层 + hincrby -1 + 归零 del + PUBLISH）
      → cancelExpirationRenewal（RenewalTask.java:97-134 从续期集合摘除）
崩溃场景：T 宕机 → 续期停止 → 30s 后 PTTL 到期 → 锁消失 → 等待者靠 PTTL 兜底循环重试拿到锁
```

## 10.3 时间线三：一次 `RMapCache.put(key, value, ttl)`（近缓存集群版）

```
客户端 A（写）: getLocalCachedMap("m").put("k", v, 30, SECONDS)
 ├─ evalWrite：同一条 Lua 里 ① HSET 数据 hash  ② ZADD 到期 zset  ③ ZADD 空闲 zset
 │    （RedissonMapCache.java:151,171）
 ├─ 近缓存：写后广播失效事件（MapCacheEventCodec 编码）→ RTopic PUBLISH
 └─ EvictionScheduler 的 MapCacheEvictionTask 周期执行：
      ZRANGEBYSCORE 到期 zset → HDEL/ZREM 过期条目（eviction/MapCacheEvictionTask）
      （调度间隔自适应 minCleanUpDelay=5s ~ maxCleanUpDelay=30min，Config.java:98-100）
客户端 B（读）: getLocalCachedMap("m").get("k")
 ├─ 本地 Caffeine 命中 → 直接返回（0 次网络）
 ├─ 未命中 → HGET → 回填本地
 └─ 收到失效消息 → LocalCachedMapListener 删本地条目（SyncStrategy 决定失效或更新，LocalCachedMapOptions.java:64）
若配了 MapWriter(WRITE_BEHIND)：put 还会进入写缓冲，WriteBehindService 按 writeBehindDelay 批量刷库（4.4 节）
```

三条时间线的公共底座都是第三章的 RedisExecutor 状态机——这就是 Redisson 架构的"一横一纵"：**横**是 config/connection/client 三层地基，**纵**是 api→command→netty 的唯一调用链。

---

# 十一、附录

## 11.1 R 接口速查表（对象 → 底层 Redis 命令）

| 对象 | 继承的 JDK 接口 | 底层结构/命令 | 典型场景 |
|---|---|---|---|
| RBucket | — | SET/GET + TTL | 分布式配置、单值缓存 |
| RMap | ConcurrentMap | HASH | 共享字典、@Cacheable 底座 |
| RMapCache | ConcurrentMap | HASH + 双 ZSET 索引 + 客户端清扫 | 条目级 TTL 的缓存 |
| RMapCacheNative | ConcurrentMap | HEXPIRE（Redis 7.4+） | 服务端条目级 TTL |
| RLocalCachedMap | RMap | HASH + 近缓存 + pub/sub 失效 | 读多写少的多节点缓存 |
| RList/RSet/RQueue/RDeque | List/Set/Queue/Deque | LIST/SET/LPUSH-BRPOP 等 | 简单集合共享 |
| RSortedSet/RScoredSortedSet | SortedSet | ZSET | 排行榜、延迟索引 |
| RBlockingQueue | BlockingQueue | BLPOP/BRPOP | 任务队列 |
| RDelayedQueue | — | ZSET 到期索引 + 定时搬运 | 延迟任务 |
| RStream | — | XADD/XREADGROUP/XACK | 可靠消息、消费者组 |
| RTopic/RReliableTopic | — | PUB/SUB；+Stream ack | 事件广播；可靠广播 |
| RLock/读写锁/公平锁 | Lock | HASH + EVAL + PUB/SUB | 分布式互斥（第五章） |
| RSemaphore/RCountDownLatch | — | EVAL + PUB/SUB | 资源配额、阶段同步 |
| RRateLimiter | — | HASH + 令牌桶 Lua | 分布式限流 |
| RExecutorService | ExecutorService | HASH+LIST 队列 | 分布式任务池/Cron |
| RRemoteService | 自定义接口 | 队列+MAP+PUB/SUB | 接口级 RPC |
| RBloomFilter/RHyperLogLog/RCountMin | — | BITSET/PFADD/CS 序列 | 去重/基数/频次估计 |
| RTimeSeries | Iterable | 服务端 TS 或 ZSET 族 | 时序采样 |
| RScript/RKeys | — | EVAL / SCAN 族 | 逃生舱与 key 管理 |

## 11.2 Config 关键选项速查（4.8.0 默认值）

| 类别 | 选项（默认值） | 一句话 |
|---|---|---|
| 拓扑 | useSingleServer/useClusterServers/useSentinelServers/useReplicatedServers/useMasterSlaveServers | 五选一 |
| 协议 | protocol(RESP2)、transportMode(NIO)、nettyThreads(32) | RESP3 开启 client-side caching 前提 |
| 编解码 | codec(Kryo5Codec) | 换 JsonJacksonCodec 获跨语言可读性 |
| 重试 | retryAttempts(4)、retryInterval(1500)、retryDelay(EqualJitterDelay 1~2s) | 抖动防惊群 |
| 超时 | timeout(3000)、connectTimeout(10000)、idleConnectionTimeout(10000)、pingConnectionInterval(30000) | 保活防 NAT 断链 |
| 连接池 | connectionPoolSize(64)/MinimumIdleSize(24)、subscriptionConnectionPoolSize(50)、subscriptionsPerConnection(5) | 订阅池独立且贵 |
| 读策略 | readMode(SLAVE)、subscriptionMode(MASTER)、fallbackLoadingToMaster(true) | 从节点加载中自动回主 |
| 锁 | lockWatchdogTimeout(30000)、lockWatchdogBatchSize(100)、checkLockSyncedSlaves(true) | 第五章全套 |

## 11.3 学习路线与避坑清单

**学习路线**：① 用 `RBucket`/`RMap` + `Redisson.create(SingleServerConfig)` 跑通最小示例；② 读第三章（命令主线），用 `redisson.reactive()` 观察同一操作的异步形态；③ 精读第五章锁源码 + 用 `MONITOR` 命令观察 Lua 与 PUBLISH；④ 把 RMapCache/RLocalCachedMap/RDelayedQueue 各写一个 demo 并断电观察行为（清扫/搬运是客户端驱动的）；⑤ 按 9.1 对号入座接入 Spring。

**避坑清单**：

1. **同步 API 逐条调用 = 逐条网络往返**。批量场景用 `getAll/putAll`、RBatch、`writeBatchedAsync`，别在循环里 `map.get()`。
2. **锁必须 try-finally unlock**；unlock 抛 `IllegalMonitorStateException` ≈ 锁已过期（业务执行超过 watchdog TTL），先查耗时再查看门狗。
3. **指定 leaseTime 后看门狗不工作**（`RedissonLock.java:162-167,187-191`：leaseTime>0 走定长模式）——"我设了 10s 锁怎么中途没了"的答案。
4. **RLocalCachedMap/RMapCache/RDelayedQueue 的清理与搬运依赖客户端进程**；全量下线会残留过期数据，生产要常驻实例或 RedissonNode。
5. **默认 codec 是 Kryo5**：跨语言、可读性、类结构演进兼容都不如 JSON；Kryo 对类变更敏感，重构字段时注意反序列化兼容。
6. **回调里别阻塞 netty EventLoop**（8.2 节），监控上出现 "Command still hasn't been written into connection!" 先查回调阻塞与 CPU。
7. **读写分离默认开**（readMode=SLAVE）：写后立即读可能不一致，敏感读用 `readMode(MASTER)` 或 `RBucket.getAndExpire` 类原子操作。
8. **PRO 特性别当开源用**：RVectorStore/RSemanticCache/Reliable Pub/Sub 全功能版等在开源 CORE 中不存在或受限（4.3 节）。

## 11.4 与 Jedis / Lettuce 的定位对比

| 维度 | Jedis | Lettuce | Redisson |
|---|---|---|---|
| 抽象层级 | 命令（连接直译） | 命令（异步内核） | 对象与语义 |
| 线程模型 | 连接池 + 阻塞 IO | netty 单连接多路复用 | netty + 自研拓扑层 |
| 分布式锁 | 自己拼 SET NX PX | 自己拼 | RLock 家族开箱即用（第五章） |
| Lua/事务 | 支持（裸） | 支持（裸） | RScript + 全套"伪结构"（MapCache/限流器/延迟队列） |
| 框架集成 | 无 | spring-data-redis 默认底座 | spring-data + Cache/Session/Tomcat/MyBatis/Hibernate/JCache |
| 适合人群 | 要完全掌控每条命令 | 高性能命令层 + 生态默认件 | 想要"分布式工具箱"直接用 |

三者的关系更接近互补而非替代：spring-data-redis 默认用 Lettuce，Redisson 的 starter 也实现同一 `RedisConnectionFactory`；把 Lettuce 当"驱动"、Redisson 当"框架"是最常见的组合理解。

---

> **文档说明**：本文为 Redisson 学习系列笔记，与《Redis.md》（Redis 服务器内核，`D:\ai\redis\Redis.md`）互为参照——那里讲"服务端如何单线程执行你的命令与 Lua"，本文讲"客户端如何把这些命令组织成分布式语义"。两文同读，可以从两端走通"一条 EVAL 的完整旅程"。分析基于 Redisson 4.8.0 源码（临时存放于 `D:\tmp\redisson-src`，可清理）。
