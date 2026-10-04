# Seata 深度源码解析（写给初学者的架构全景）

> **本文基于的源码**：`D:\code\3rd\seata`，版本 **2.8.0-SNAPSHOT**（2.x 分支开发线，Git 最新提交 `dbea1859`，2026-08-15，对应 apache/incubator-seata 主开发分支；仓库经 Gitee 镜像 `gitee.com/mirrors/seata` 同步获取）。文中所有【源码证据】的文件路径与行号均为对该快照实际读取所得。
>
> **版本取舍说明**：Seata 的核心骨架——TC/TM/RM 三角色、两阶段协议、XID 传播、AT 模式的"一阶段本地提交 + undo_log 反向补偿"——自 2019 年 Fescar 0.1 开源以来没有动过。2.x 的主要演进发生在**外围**：包名从 `io.seata` 迁到 `org.apache.seata`（2.1.0）、Raft 集群存储与 namingserver（2.0/2.1）、TCC fence 从 `tcc_fence_log` 泛化为 `common_fence`、拦截器体系从 `GlobalTransactionalInterceptor` 重构为 `integration-tx-api` 的 SPI（2.1~2.2）。因此本文内容对使用 1.5~2.7 的读者同样适用；演进差异在 1.6 节逐项标注。
>
> **阅读约定**：每章"先白话、后源码"——先用一两句人话讲清"这是什么、为什么需要它"，再给出源码证据链（`模块/src/main/java/.../类名.java` + 方法名 + 行号 + 代码片段）。行号只对 2.8.0-SNAPSHOT 快照精确，读者用 IDEA 打开源码按类名 + 方法名定位即可。

## 如何读这份文档

如果你是分布式事务/Seata 初学者，推荐两遍读法：

- **第一遍（建立地图，1~2 小时）**：只读第一章（总览）每节的开头白话段、各章的"本章小结"节、以及第十一章（贯通视图：三条时间线）。目标是能回答：TC/TM/RM 各自管什么？`@GlobalTransactional` 一个注解背后发生了什么？AT 模式为什么不用每台机器都装事务管理器？全局锁和数据库行锁是什么关系？回滚到底"回"的是什么？
- **第二遍（深入源码）**：对照每一章的【源码证据】逐行读。顺序建议：第二章（概念与协议地基）→ 第三章（TM，一切的起点）→ 第四章（RM/AT 一阶段，全文核心 A）→ 第六章（TC，全文核心 B）→ 第五章（AT 二阶段：提交与回滚）→ 第七章（Netty 通信，随用随查）→ 第八章（TCC）→ 第十章（Spring 集成与配置）→ 第九章（XA/SAGA 概览）。

---

# 一、总览：Seata 的定位、设计哲学与整体架构

## 1.1 一句话定位

**Seata 是一个"把两阶段提交做轻"的微服务分布式事务框架**：它用 TC（事务协调器）集中登记每个全局事务的进度，用 TM（事务管理器）划定"哪些业务调用算一个全局事务"，用 RM（资源管理器）把每个参与方数据库连接"包裹"进全局事务——四种事务模式（AT/TCC/SAGA/XA）共享同一套协调协议，只是"一阶段做什么、二阶段怎么收尾"各不相同。官网自述：

> *"Seata is an easy-to-use, high-performance, open source distributed transaction solution."*（`README.md` 项目横幅）

它解决的不是"单库事务"（那是 JDBC `Connection#commit` 的事），而是**跨服务、跨库的原子性**：下单服务改了自己的库、扣库存服务改了自己的库，任何一方失败都要能把**所有**已提交的改动"退回去"。传统 XA 要求所有参与方在一阶段就 prepare 并持有数据库锁直到全局结束，性能差、且依赖数据库原生支持；Seata 的 AT 模式把"持有锁"的时间压缩到"一阶段本地事务"那么短，代价是用 **undo_log 反向补偿 + 全局锁**来保障二阶段能回滚——这正是读 Seata 源码要盯住的两条主线。

三个容易混淆的角色，先立好坐标：

1. **TC（Transaction Coordinator）**：独立部署的 `seata-server`（`server/` 模块），维护全局事务/分支事务的会话、全局锁表、驱动两阶段的定时任务。它不碰业务数据库，只管自己的存储（file/db/redis/raft 四种）。
2. **TM（Transaction Manager）**：嵌入在**事务发起方**（`seata-tm` 模块），定义全局事务边界——begin/commit/rollback 三个动作，通过 RPC 报给 TC。
3. **RM（Resource Manager）**：嵌入在**每个资源参与方**（`seata-rm`、`seata-rm-datasource`、`seata-tcc`、`seata-saga-rm` 模块），管分支事务的注册与二阶段执行——AT 模式下它以"数据源代理"的形态存在。

一句话记住它：**Seata = 一个跑在应用进程外的"事务状态机"（TC） + 一个贴在应用进程里的"事务边界拦截器"（TM） + 一个贴在数据源上的"分支事务代理"（RM）**。

## 1.2 设计哲学：读源码前先记住五句话

1. **一阶段就提交，二阶段异步收尾**。这是 AT 模式对 XA 的核心颠覆：业务 SQL 执行完，本地事务直接 `commit()`（`rm-datasource/.../ConnectionProxy.java:255`），数据立即对其他事务可见；Seata 在提交前偷偷做了两件事——把改前/改后数据拍成快照写进 `undo_log` 表、把"我动了哪些行"上报 TC 注册全局锁。二阶段提交 = 异步删 undo_log（`AsyncWorker`）；二阶段回滚 = 用 undo_log 里的快照生成反向 SQL 补偿。**"回滚"在 AT 里不是数据库回滚，而是一次新的本地事务**。
2. **业务零侵入，侵入全部压在"代理层"**。业务方只加一个 `@GlobalTransactional`（TM 侧）和一份 starter 依赖；数据源被 `DataSourceProxy` 包装、连接被 `ConnectionProxy` 包装、语句被 `StatementProxy` 包装、SQL 被 Druid/Antlr 识别器解析——四层代理全由框架自动织入（`spring/.../GlobalTransactionScanner.java:307`），业务代码感知不到 Seata 存在。
3. **TC/TM/RM 三角色分权，一切靠消息协议协作**。角色之间没有本地调用，全靠 Netty 长连接上的二进制协议消息（`core/protocol/MessageType.java`）：`TYPE_GLOBAL_BEGIN(1)`、`TYPE_GLOBAL_COMMIT(7)`、`TYPE_BRANCH_REGISTER(11)`……消息类型即架构——把 MessageType 表读一遍，整个分布式事务的"对话剧本"就摆在眼前了（见 2.4 节）。
4. **模式可插拔：BranchType 是总开关**。AT/TCC/SAGA/XA 四种模式在服务端各自对应一个 `AbstractCore` 实现（`server/.../transaction/at/ATCore.java`、`transaction/tcc/...`），由 SPI 加载进 `CORE_MAP`（`server/.../coordinator/DefaultCore.java:71-80`），TC 按 `BranchType` 分发。客户端同样：AT 的分支走 `DataSourceManager`，TCC 的分支走 `TCCResourceManager`。**新增一种事务模式 = 新增一对客户端 Resource + 服务端 Core**，协调器骨架零改动。
5. **一致性强度可选，全局锁是 AT 的灵魂开关**。AT 默认走"写隔离 + 读不加锁"：写操作在 TC 侧按 `表:主键` 粒度抢**全局行锁**（`server/.../storage/file/lock/FileLocker.java:70`），抢不到就客户端重试（`exec/LockRetryController.java`）；要更强的隔离就读 `select for update` 也拿全局锁（`SelectForUpdateExecutor` + `@GlobalLock`）。不想要全局锁的强一致场景，退回 XA 模式用数据库原生两阶段。**四种模式本质上是一致性/性能/侵入度的四个档位**。

## 1.3 模块分层全景

seata 仓库 2.8.0-SNAPSHOT 实测共 30+ 个 Maven 工程。按"进程归属"分层如下：

```
┌────────────────────────── seata-server（TC，独立部署）──────────────────────────┐
│  server/         DefaultCoordinator、DefaultCore、SessionHolder、LockManager、  │
│                  cluster/raft（Raft 集群）、console（控制台 API）                │
│  namingserver/   2.1+ 内置注册中心（含 console UI），vgroup 寻址 + 集群元数据    │
├────────────────────────────── TM 侧（事务发起方）───────────────────────────────┤
│  tm/             GlobalTransaction、TransactionalTemplate、TMClient             │
├────────────────────────────── RM 侧（各资源参与方）─────────────────────────────┤
│  rm/             DefaultResourceManager、AbstractRMHandler（分支请求入口）       │
│  rm-datasource/  DataSourceProxy/ConnectionProxy、exec/*（SQL 执行器）、         │
│                  undo/*（undo_log 读写与反向 SQL）、xa/*（XA 模式）、AsyncWorker │
│  tcc/            TwoPhaseBusinessAction、TCCResourceManager、RMHandlerTCC       │
│  saga/           8 个子模块：状态机引擎/状态语言/日志存储/RM/注解模式            │
├────────────────────────────── 接入层（应用侧胶水）─────────────────────────────┤
│  integration-tx-api/  2.x 新拦截器 SPI：ProxyInvocationHandler/Parser、TCC fence│
│  spring/         seata-spring（GlobalTransactionScanner、数据源自动代理）        │
│                  seata-spring-autoconfigure（Boot 2/3 自动配置）                 │
│                  seata-spring-boot-starter（依赖聚合）                          │
│  extensions/     rpc 适配（dubbo/sofa/http/motan/grpc...）、apm、messaging      │
├────────────────────────────── 公共地基 ─────────────────────────────────────────┤
│  common/         XID、GlobalStatus/GlobalLockConfig、ConfigurationKeys、SPI 工具│
│  core/           协议（MessageType/RpcMessage/codec）、Netty remoting（收发两端）│
│                  core/model（BranchType/BranchStatus/TransactionManager 接口）  │
│  config/         配置中心 SPI（nacos/apollo/zk/consul/etcd3/spring-cloud/file）  │
│  discovery/      注册中心 SPI（nacos/redis/eureka/sofa/raft/namingserver/file）  │
│  sqlparser/      seata-sqlparser-core + druid 实现 + antlr 实现                  │
│  serializer/ compressor/ threadpool/ metrics/  …可插拔公共件                     │
│  compatible/     保留 io.seata 老包名的兼容层                                    │
└─────────────────────────────────────────────────────────────────────────────────┘
```

## 1.4 模块依赖图（以各模块 pom.xml 的 seata 内部依赖实证）

```
                seata-common（XID、配置键、SPI 工具）
                 ▲       ▲        ▲
     seata-threadpool ◄─── seata-core（协议+remoting+model）◄── seata-discovery-core
                 ▲       ▲    ▲     ▲
        seata-tm ┘       │    │     └── seata-rm ──┐
                         │    │                    ▼
   seata-sqlparser-core ◄┤    ├──── seata-rm-datasource（AT/XA、undo、AsyncWorker）
   seata-sqlparser-druid ┘    │
                              ├──── seata-tcc ── seata-integration-tx-api（拦截器 SPI + fence）
                              │            ▲           ▲
   seata-saga-*（8 模块）──────┼────────────┘           │
                              ▼                        │
   seata-spring（Scanner/数据源代理）─── seata-spring-boot-starter
                              ▼
   seata-server（core + config-all + discovery-all + metrics + console …）
```

真实依赖声明（摘自各模块 `pom.xml`，已逐个验证）：

| 模块 | 依赖的 seata 内部模块 |
|---|---|
| seata-core | common、discovery-core、threadpool |
| seata-tm | core |
| seata-rm | core |
| seata-rm-datasource | core、rm、sqlparser-core、sqlparser-druid、compressor-all |
| seata-tcc | core、rm、integration-tx-api |
| seata-integration-tx-api | common、rm、rm-datasource、tm、serializer-all |
| seata-spring | integration-tx-api、rm、rm-datasource、tm、tcc、sqlparser-druid、serializer-all |
| seata-spring-boot-starter | seata-all、spring-autoconfigure-client |
| seata-server | core、compressor-all、config-all、discovery-all、metrics-all、console、spring-autoconfigure-server、threadpool-loom |

这张表本身就是一份架构说明：**core 居中**（协议与 remoting 是唯一被所有人共享的地基），**tm 与 rm 平行且互不依赖**（TM 报全局事务、RM 报分支事务，互不越权——TM 发不出 BranchRegister，RM 也发不出 GlobalCommit，这种"权限隔离"是刻意的），**rm-datasource 是 AT 的全部秘密所在**（依赖 sqlparser 才能看懂 SQL），**seata-spring 是客户端胶水**（把 tm/rm/tcc/integration 全部接上 Spring AOP）。

## 1.5 关键问题 → Seata 方案映射（全文导览）

| 微服务开发的关键问题 | Seata 的方案 | 详见 |
|---|---|---|
| 跨服务改多库，怎么保证"要么都成、要么都退"？ | 全局事务：TC 登记会话 + TM 划边界 + RM 注册分支 | 第三、四、六章 |
| 业务代码不想写事务样板 | `@GlobalTransactional` 注解 + AOP 拦截器 + TransactionalTemplate | 第三章、第十章 |
| 二阶段要能回滚，但数据已经本地提交了 | undo_log：执行前拍 before/after 镜像，回滚时生成反向 SQL | 第四章、第五章 |
| 两个全局事务同时改一行怎么办 | TC 侧全局锁表（`表:主键`粒度）+ 客户端重试 | 第四章 4.6、第六章 6.5 |
| 本地事务已提交，但全局事务最终失败 | 二阶段回滚：TC 驱动 → RM 执行 undo_log 反向补偿 | 第五章、第六章 6.6 |
| 服务宕机后事务怎么办 | TC 会话持久化（file/db/redis/raft）+ 超时检查 + 重试队列 | 第六章 6.4/6.6 |
| TC 单点问题 | Raft 集群（2.0+）/ db+redis 存储高可用 | 第六章 6.7 |
| 事务边界内 RPC 调用怎么把"身份"传过去 | XID 通过 RPC attachment/header 透传（RootContext ThreadLocal） | 第二章 2.2、第十章 10.8 |
| 不想用代理数据源，又要强一致 | XA 模式：数据库原生两阶段（ResourceManagerXA） | 第九章 9.1 |
| 老代码无法改造出"可回滚"的资源 | SAGA 状态机/补偿（seata-saga）或 TCC 手写 confirm/cancel | 第八、九章 |
| TCC 的空回滚/幂等/悬挂三大经典坑 | fence 日志表（STATUS_TRIED/SUSPENDED 状态机） | 第八章 8.4 |
| 事务多了性能扛不住 | 异步提交（AsyncCommitting/AsyncWorker）、消息合并、分支异步删除 | 第五章 5.2、第七章 7.6 |

## 1.6 版本演进：Fescar → Apache Seata（0.x → 2.x）

写作时（2026 年 10 月）的版本格局：1.x 已停止主线开发，2.x 是唯一活跃线，最新稳定版为 2.7.x（2.8.0 尚在快照阶段——正是本文分析的这份 2.x 分支快照）。本节版本时间线以官方发布历史页与 GitHub Releases 为准（1.x 各版本 GA 日期已与官网 release-history 页交叉核对；2.6.0 发布日期 2026-01-28 经 Maven Central 查询验证）。

### 1.6.1 版本时间线与一句话主题

| 版本 | GA 时间 | 一句话主题 |
|---|---|---|
| Fescar 0.1~0.5 | 2019 上半年 | 以 "Fast & Easy Commit And Rollback" 之名开源，AT 模式 + TCC 已具雏形 |
| Seata 0.5+（更名） | 2019-03 | 蚂蚁金服加入共建，更名 Seata（Simple Extensible Autonomous Transaction Architecture） |
| 1.0.0 | 2019-12-21 | 首个 GA：SAGA 状态机引擎随版发布 |
| 1.2.0 | 2020-04-21 | 稳定性迭代 |
| **1.3.0** | 2020-07-16 | **XA 模式引入**（数据库原生两阶段，DataSourceProxyXA） |
| 1.4.0 | 2020-11-02 | Redis 注册中心、注解驱动的 Saga 模式起步 |
| 1.4.2 | 2021-04-25 | 修复系列回滚/锁问题 |
| 1.5.0 | 2022-05-17 | **控制台 UI**（seata-console）、db 模式表结构调整、AT 优化（beforeImage 查询瘦身等） |
| 1.6.0 | 2022-12-17 | 性能大幅提升（并发分支处理、锁粒度优化）、Spring Boot 3 适配铺垫 |
| 1.7.0 / 1.8.0 | 2023-07-11 / 2023-10-31 | JDK 17/21 支持、线程池虚拟线程铺垫 |
| **2.0.0** | 2023-11-24 | **Raft 存储模式**（file 模式的多机强一致替代，seata-server/cluster/raft） |
| **2.1.0** | 2024-10 | **进入 Apache 孵化器后的首个版本**：包名 `io.seata` → `org.apache.seata`；**namingserver + console 模块**内置注册中心；拦截器重构为 integration-tx-api |
| 2.2.0 | 2025 初 | namingserver 客户端（discovery-namingserver）、Saga 注解模式 |
| 2.3.0 | 2025 上半年 | Fastjson2 RPC 序列化、人大金仓（Kingbase）数据库支持、Saga 注解模式增强 |
| 2.4.0 | 2025 下半年 | console 迁移至 namingserver、Kingbase XA、安全强化（强制初始化账户） |
| 2.5.0 | 2025 末 | 默认凭据禁用（CVE-2025-53606 修复）、安全审计系列 |
| 2.6.0 | 2026-01-28 | server 端 HTTP 请求过滤器链、Raft 集群管理增强 |
| 2.7.0 | 2026 上半年 | Benchmark 命令行工具、TCC fence 清理防误删（#8138） |
| 2.8.0-SNAPSHOT | （本文快照） | namingserver GraalVM 原生镜像、BusinessActionContext 自动标记 updated、Saga 注解防悬挂增强（#8188） |

### 1.6.2 关键特性引入版本对照表（可复现）

每行的"实证"均可在本地仓库用 `git grep <关键字> <tag>` 或 `changes/zh-cn/<版本>.md` 复现（本仓库 changes 目录收录了 1.4.2 至 2.x 全部变更记录）：

| 特性 | 引入版本 | 实证 | 详见 |
|---|---|---|---|
| AT 模式（DataSourceProxy/undo_log） | 0.1（2019） | 项目起点，`rm-datasource/` 核心自始存在 | 四、五章 |
| TCC 模式（TwoPhaseBusinessAction） | 0.x 早期 | `tcc/` 模块 | 八 |
| XA 模式（DataSourceProxyXA） | **1.3.0** | 1.3.0 官方发布说明"新增 XA 模式"；`rm-datasource/xa/` | 九 9.1 |
| SAGA 状态机引擎 | 1.0.0 | `saga/seata-saga-engine` | 九 9.2 |
| 控制台 UI（seata-console） | 1.5.0 | `server/console` 子模块；1.5.0 发布说明 | — |
| Raft 存储模式 | **2.0.0** | `server/cluster/raft/`；2.0.0 发布说明 | 六 6.7 |
| `io.seata` → `org.apache.seata` 包名迁移 | **2.1.0** | 全仓库包名 + `compatible/` 模块保留老包 | — |
| namingserver 内置注册中心 | **2.1.0** | `namingserver/` 模块 + `discovery/seata-discovery-namingserver` | 六 6.7 |
| 拦截器体系重构（GlobalTransactionalInterceptor → GlobalTransactionalInterceptorHandler + Parser SPI） | 2.1~2.2 | 2.x 已无 `GlobalTransactionalInterceptor` 类（`find` 验证 0 命中）；`integration-tx-api/` | 十 10.3 |
| TCC fence 从 tcc_fence_log 泛化为 common_fence | 2.2+ | `integration-tx-api/.../fence/`（CommonFenceStore）；`spring/.../rm/fence/SpringFenceHandler.java` | 八 8.4 |
| server 端 HTTP 过滤器链 | 2.6.0 | changes/2.6.0.md #7485 | — |
| Saga 注解模式（@LocalCompensation 等） | 2.2~2.3 | `saga/seata-saga-annotation/`；changes/2.2.0.md | 九 9.2 |

### 1.6.3 对初学者的意义：哪些知识过时了，哪些永远有效

- **新项目直接用 2.x**：1.x 的 `io.seata` 包名已停止演进；2.x 的 starter 在 Spring Boot 2/3 下均有对应 autoconfigure 模块。
- **老教程里永久有效的部分**：TC/TM/RM 三角色、XID 格式（`ip:port:txId`）、AT 的前后镜像与全局锁、TCC 三大坑与 fence、TC 的五类定时任务——这些自 0.x/1.0 至 2.8 骨架未变，本文第四~八章正是按这条主线写的。
- **容易被老资料误导的部分**：`GlobalTransactionalInterceptor`（2.x 已拆分重组）、`tcc_fence_log`（已泛化为 `common_fence`，但表语义不变）、file 存储的单机局限（2.0 起可用 Raft 集群）、"TC 只能配 Nacos"（2.1 起有内置 namingserver）。

## 1.7 全文章节地图

- **第二章 概念与协议地基（seata-common / seata-core）**：XID 的生成与传播、GlobalStatus/BranchStatus 状态机、MessageType 消息协议总表、RpcMessage 结构。
- **第三章 TM：全局事务的发起者（seata-tm）**：GlobalTransaction 角色模型、TransactionalTemplate 的六步模板、传播行为、DefaultTransactionManager 如何把 API 变成 RPC。
- **第四章 RM 与 AT 模式（一）：一阶段（seata-rm-datasource）**：数据源四层代理、SQL 执行器分派、前后镜像、lockKey 构建与全局锁注册、undo_log 写入、select for update 与 @GlobalLock。
- **第五章 RM 与 AT 模式（二）：二阶段**：ConnectionProxy.commit 完整流程、AsyncWorker 异步删 undo_log、UndoLogManager.undo 反向补偿与脏写检查、GlobalFinished 防御。
- **第六章 TC：事务协调器（seata-server）**：Server 启动、DefaultCoordinator 入口、DefaultCore 全局两阶段、SessionHolder 四种存储、全局锁三实现、六个定时任务、Raft 集群。
- **第七章 通信层：Netty RPC（seata-core remoting）**：消息帧布局、同步请求-响应与 MessageFuture、客户端连接池与重连、服务端 ChannelManager 注册、消息合并。
- **第八章 TCC 模式**：注解与代理、prepare 流程、二阶段反射调用、fence 防空回滚/幂等/悬挂。
- **第九章 XA 与 SAGA（概览）**：XA 的连接级两阶段、SAGA 状态机引擎与补偿。
- **第十章 Spring 集成与配置体系**：SeataAutoConfiguration、GlobalTransactionScanner、integration-tx-api 拦截器 SPI、降级检查、数据源自动代理、配置/注册中心 SPI、XID 传播。
- **第十一章 贯通视图**：一次下单的三条时间线（正常提交 / 异常回滚 / 全局锁互斥）。
- **第十二章 附录**：关键类速查表、模块-职责速查、学习路线、30 个源码入口文件。

---

# 二、概念与协议地基（seata-common / seata-core）

## 2.1 先说白话：三个接口文件就是"宪法"

Seata 的核心概念全部落在 `common` 和 `core` 两个模块的十几个接口/枚举里。读源码前把这四样东西背下来，后面所有章节都只是它们的"展开"：

1. **XID**：全局事务的唯一标识，格式 `TC 的 ip:port:事务ID`（如 `192.168.1.5:8091:1234567`）；
2. **TransactionManager 接口**：TM 对 TC 的"发言权清单"——begin/commit/rollback/getStatus/globalReport；
3. **GlobalStatus / BranchStatus**：全局事务与分支事务的状态机（TC 内部驱动的"进度条"）；
4. **BranchType**：AT/TCC/SAGA/XA 四选一，贯穿客户端与服务端的模式分发开关。

## 2.2 XID：全局事务的身份证

**白话**：一个全局事务在 TC 里只是一行会话数据，在应用间传递时就是一串字符串。谁拿着 XID，谁就有资格往这个全局事务里"挂"分支。

【源码证据】`common/src/main/java/org/apache/seata/common/XID.java:55-63`——XID 的生成就是把 TC 自己的地址拼上事务号：

```java
public static String generateXID(long tranId) {
    return new StringBuilder()
            .append(ipAddress)
            .append(IP_PORT_SPLIT_CHAR)
            .append(port)
            .append(IP_PORT_SPLIT_CHAR)
            .append(tranId)
            .toString();
}
```

- `ipAddress/port` 在 server 启动时赋值：`server/src/main/java/org/apache/seata/server/Server.java:109`——`XID.setPort(nettyRemotingServer.getListenPort())`（默认 8091）。
- 事务号 `tranId` 由 `GlobalSession` 创建时生成：`server/.../session/GlobalSession.java:461`——`this.xid = XID.generateXID(transactionId)`，而 `transactionId` 出自 `UUIDGenerator`（server 启动时用 `serverNode` 参数初始化，保证多节点不撞号，`Server.java:110`）。
- 客户端解析 XID 只需取最后一个冒号后的数字：`XID.java:71-78`（`getTransactionId`）。

**XID 怎么"随身携带"**：答案在 `core/src/main/java/org/apache/seata/core/context/RootContext.java`——一个基于 ThreadLocal 的上下文容器（`:90`，`CONTEXT_HOLDER = ContextCoreLoader.load()`），TM begin 成功后 `RootContext.bind(xid)`（`:126-139`，同时写入 MDC 便于日志排查），业务方法里任何时刻 `RootContext.getXID()` 都能取到当前事务（`:117-119`）。RPC 框架扩展（dubbo/feign/http filter 等）在发请求前从 `RootContext.getXID()` 取值塞进 attachment/header，接收方再 `bind` 回自己的 ThreadLocal——这就是"事务边界内调用下游服务，下游自动加入同一全局事务"的全部原理（10.8 节给出传播链）。

RootContext 还管理一个容易被忽略的开关：**全局锁标志**（`:86`，`KEY_GLOBAL_LOCK_FLAG`；`:152-159`，`bindGlobalLockFlag()`）。它服务于"本地事务也要和全局事务互斥"的场景——`@GlobalLock` 注解的方法执行前会绑定它，数据源代理看到这个标志就会走"抢全局锁"路径（见 4.8 节）。

## 2.3 状态机：GlobalStatus 与 BranchStatus

**白话**：TC 是一台状态机。全局事务从 `Begin` 出发，要么滑向 `Committed`，要么滑向 `Rollbacked`；中间每个"进行中"状态都对应 TC 的一个定时任务在推着走。把两张状态表记熟，看第六章的定时任务就不会迷路。

【源码证据】`common/src/main/java/org/apache/seata/core/model/GlobalStatus.java:29-149`（21 个状态，括号内为 code）：

| 状态 | code | 含义 |
|---|---|---|
| UnKnown | 0 | 尚未 begin 或事务不存在 |
| **Begin** | 1 | 全局事务进行中 |
| **Committing** | 2 | 二阶段同步提交中 |
| CommitRetrying | 3 | 提交失败重试中 |
| **Rollbacking** | 4 | 二阶段回滚中 |
| RollbackRetrying | 5 | 回滚失败重试中 |
| TimeoutRollbacking | 6 | 超时，开始回滚 |
| TimeoutRollbackRetrying | 7 | 超时回滚失败重试中 |
| **AsyncCommitting** | 8 | AT 专用：等异步提交任务处理 |
| Committed | 9 | 已提交 |
| CommitFailed | 10 | 提交失败（终态） |
| Rollbacked | 11 | 已回滚 |
| RollbackFailed | 12 | 回滚失败（终态） |
| TimeoutRollbacked | 13 | 超时回滚完成 |
| TimeoutRollbackFailed | 14 | 超时回滚失败 |
| Finished | 15 | 模糊终态（SAGA report 用） |
| CommitRetryTimeout | 16 | 提交重试超时（终态） |
| RollbackRetryTimeout | 17 | 回滚重试超时（终态） |
| Deleting | 18 | 删除中 |
| StopCommitOrCommitRetry / StopRollbackOrRollbackRetry | 19/20 | 运维手动暂停 |

分支状态 `core/src/main/java/org/apache/seata/core/model/BranchStatus.java:31-109`（14 个）：`Registered(1)` → 一阶段 `PhaseOne_Done(2)`/`PhaseOne_Failed(3)`/`PhaseOne_Timeout(4)`/`PhaseOne_RDONLY(13，XA 只读优化)` → 二阶段 `PhaseTwo_Committed(5)`/`PhaseTwo_CommitFailed_Retryable(6)`/`PhaseTwo_CommitFailed_Unretryable(7)`/`PhaseTwo_Rollbacked(8)`/`PhaseTwo_RollbackFailed_Retryable(9)`/`PhaseTwo_RollbackFailed_Unretryable(10)`/两个 XA 专用的 `XAER_NOTA(11/12)`。

**一个值得品味的设计**：分支状态的 `Retryable / Unretryable` 后缀就是 TC 重试队列的"指令"。RM 在二阶段执行失败时上报哪个状态，TC 的定时任务就决定"再试一次"还是"判死刑"——两侧协议靠枚举值完成了协商（见 5.3、6.6 节）。

模式枚举 `core/src/main/java/org/apache/seata/core/model/BranchType.java:28-44`：`AT`、`TCC`、`SAGA`、`XA`——客户端注册资源、注册分支、服务端分派 Core 全靠它。

## 2.4 TM 的"发言权"：TransactionManager 接口

【源码证据】`core/src/main/java/org/apache/seata/core/model/TransactionManager.java:40-82`：

```java
String begin(String applicationId, String transactionServiceGroup, String name, int timeout)
        throws TransactionException;
GlobalStatus commit(String xid) throws TransactionException;
GlobalStatus rollback(String xid) throws TransactionException;
GlobalStatus getStatus(String xid) throws TransactionException;
GlobalStatus globalReport(String xid, GlobalStatus globalStatus) throws TransactionException;
```

注意这个接口**只有全局事务级别的五个方法**——没有 branchRegister/branchReport。那是 RM 的权限（`ResourceManager` 接口体系，见 4.1 节）。TC 侧对两种请求的处理入口也完全分开：`AbstractTransactionRequestToTC` vs `AbstractTransactionRequestToRM`。这种"角色即接口"的切分贯穿全库，是读 Seata 源码最重要的地图。

## 2.5 消息协议：MessageType 与 RpcMessage

**白话**：TM/RM 和 TC 之间说什么话，全在 `MessageType` 里。每条消息一个 short 类型码，请求与响应成对（响应码 = 请求码 + 1 或固定配对）。

【源码证据】`core/src/main/java/org/apache/seata/core/protocol/MessageType.java`（关键常量，数值已逐项核对）：

| 类型码 | 常量 | 方向 | 语义 |
|---|---|---|---|
| 1 / 2 | TYPE_GLOBAL_BEGIN / _RESULT | TM→TC / 返回 | 开启全局事务，返回 XID |
| 7 / 8 | TYPE_GLOBAL_COMMIT / _RESULT | TM→TC / 返回 | TM 发起全局提交 |
| 9 / 10 | TYPE_GLOBAL_ROLLBACK / _RESULT | TM→TC / 返回 | TM 发起全局回滚 |
| 3 / 4 | TYPE_BRANCH_COMMIT / _RESULT | **TC→RM** / 返回 | 二阶段：提交分支 |
| 5 / 6 | TYPE_BRANCH_ROLLBACK / _RESULT | **TC→RM** / 返回 | 二阶段：回滚分支 |
| 11 / 12 | TYPE_BRANCH_REGISTER / _RESULT | RM→TC / 返回 | 一阶段：注册分支（带 lockKeys） |
| 13 / 14 | TYPE_BRANCH_STATUS_REPORT / _RESULT | RM→TC / 返回 | 分支状态上报（PhaseOne_Failed 等） |
| 17 / 18 | TYPE_GLOBAL_REPORT / _RESULT | TM→TC / 返回 | 全局状态报告（SAGA 用） |
| 21 / 22 | TYPE_GLOBAL_LOCK_QUERY / _RESULT | RM→TC / 返回 | 查询/校验全局锁（@GlobalLock 的 select for update） |
| 59 / 60 | TYPE_SEATA_MERGE / _RESULT | 双向 | 多条消息合并为一个 RPC（7.6 节） |
| 101 / 103 | TYPE_REG_CLT / TYPE_REG_RM | 客户端→TC | TM/RM 握手注册 |
| 121 | TYPE_BATCH_RESULT_MSG | 双向 | 合并结果批量返回 |

消息载体 `core/src/main/java/org/apache/seata/core/protocol/RpcMessage.java:31-36`：

```java
private int id;                 // 消息 ID：请求响应配对的"关联键"
private byte messageType;       // 请求(sync/oneway)/响应/心跳
private byte codec;             // 序列化方式（seata/hessian/kryo/protobuf/fastjson2…）
private byte compressor;        // 压缩方式（none/zip/bzip2/lz4…）
private Map<String, String> headMap = new HashMap<>();
private Object body;            // 具体协议对象（GlobalBeginRequest/…）
```

body 侧每种消息类型一个 POJO（`protocol/transaction/` 下 `GlobalBeginRequest`（name+timeout）、`GlobalCommitRequest`（xid）、`BranchRegisterRequest`（branchType/resourceId/xid/lockKeys/applicationData）……）。**请求类自带 `handle()` 方法**（`AbstractTransactionRequestToRM.handle(context)` 回调 `RMInboundHandler`，`rm/.../AbstractRMHandler.java:148-156`）——这是访问者模式：TC 收到消息后不用 if-else 分发，消息自己"走进"对应处理器。

## 2.6 本章小结

- XID = `ip:port:txId`，由 TC 生成、RootContext（ThreadLocal）保管、RPC attachment 传播；
- 全局事务状态机 21 态、分支状态机 14 态，状态值就是 TC 定时任务与客户端重试的"协议"；
- TransactionManager 接口划定了 TM 的权限边界（只有全局事务五方法）；分支注册是 RM 的专属；
- MessageType 表就是分布式事务的"对话剧本"：TM 与 TC 说 1/7/9/17，RM 与 TC 说 11/13/21，TC 对 RM 说 3/5；
- RpcMessage 五字段（id/messageType/codec/compressor/headMap/body）支撑了超时关联、多序列化、压缩三类横切能力。

---

# 三、TM：全局事务的发起者（seata-tm）

## 3.1 模块定位与包结构

**白话**：tm 模块只有 23 个类，是 Seata 里最小的模块之一，却回答了最重要的问题——"全局事务的边界画在哪"。它给出一个和 Spring `@Transactional` 几乎同构的编程模型：`@GlobalTransactional` 注解 + 模板 + 传播行为 + 回滚规则，区别只在于"事务管理器"从数据库连接换成了 TC 这个远程服务。

```
tm/src/main/java/org/apache/seata/tm/
├── api/
│   ├── GlobalTransaction.java          对外接口：begin/commit/rollback/suspend/resume
│   ├── DefaultGlobalTransaction.java   实现：角色判断 + 重试 + 上下文绑定
│   ├── GlobalTransactionContext.java   工厂：createNew/getCurrent/getCurrentOrCreate/reload
│   ├── GlobalTransactionRole.java      Launcher（发起者）/ Participant（参与者）
│   ├── TransactionalTemplate.java      ★ 模板：execute() 六步
│   ├── TransactionalExecutor.java      业务回调 + TransactionInfo 配置载体
│   ├── FailureHandler.java(+Default)   begin/commit/rollback 失败钩子（用户可覆盖）
│   └── transaction/
│       ├── Propagation.java            REQUIRED/REQUIRES_NEW/SUPPORTS/NOT_SUPPORTED/NEVER/MANDATORY
│       ├── TransactionInfo.java        超时/名称/传播/锁重试/回滚规则
│       ├── RollbackRule/NoRollbackRule  与 Spring 同名语义
│       ├── TransactionHook(Manager)    事务生命周期钩子
│       └── SuspendedResourcesHolder    挂起时保存的 XID
├── DefaultTransactionManager.java      ★ TransactionManager 实现：发 RPC 给 TC
├── TransactionManagerHolder.java       单例持有
└── TMClient.java                       init()：启动 TM 网络客户端
```

## 3.2 DefaultGlobalTransaction：角色决定生死

**白话**：同一个类有两种人格——Launcher 可以 begin/commit/rollback；Participant（被 XID 传播"传染"进来的下游服务）对这些操作一律忽略。这解决了分布式事务的经典问题：每个服务都注解了 `@GlobalTransactional`，但只有最外层那个真正开事务、提事务。

【源码证据】`tm/src/main/java/org/apache/seata/tm/api/DefaultGlobalTransaction.java:235-263`，begin 的三道闸门：

```java
@Override
public void begin(int timeout, String name) throws TransactionException {
    this.createTime = System.currentTimeMillis();
    // 闸门一：参与者不开新事务
    if (role != GlobalTransactionRole.Launcher) {
        assertXIDNotNull();
        ... return;
    }
    // 闸门二/三：XID 必须为空 + 当前线程没绑定事务
    assertXIDNull();
    String currentXid = RootContext.getXID();
    if (currentXid != null) {
        throw new IllegalStateException("Global transaction already exists," + ...);
    }
    // 核心：向 TC 要 XID，绑到线程上下文
    xid = transactionManager.begin(null, null, name, timeout);
    status = GlobalStatus.Begin;
    RootContext.bind(xid);
}
```

commit 与 rollback 是同构的重试循环（`commit()` `:300-345`，`rollback()` `:387-432`）：Participant 直接 return（`:302-308`）；Launcher 进入 while 重试（默认 5 次，`DEFAULT_TM_COMMIT_RETRY_COUNT = 5`，`common/.../DefaultValues.java:289`），失败只打日志继续重试，重试耗尽抛 `TransactionException`；**无论成败，finally 里解绑上下文**（`:335-340`，`suspend(true)` 清掉 RootContext 的 XID，否则线程池复用会"串事务"）。

`globalReport`（`:538-553`）是给 SAGA 模式的特殊通道：不经过 commit/rollback，直接把最终状态报给 TC。

## 3.3 TransactionalTemplate.execute()：六步模板（本章核心）

**白话**：`@GlobalTransactional` 拦截到方法后，最终落到这个模板。它和 Spring 的 `TransactionTemplate` 逐行对得上，多出来的是"传播行为对 XID 上下文的挂起/恢复"。

【源码证据】`tm/src/main/java/org/apache/seata/tm/api/TransactionalTemplate.java:180-280`，骨架：

```java
public Object execute(TransactionalExecutor business) throws Throwable {
    // 1. 取配置（超时/传播/锁重试/回滚规则）
    TransactionInfo txInfo = business.getTransactionInfo();
    // 1.1 看当前线程有没有事务 —— 有则本轮是 Participant
    GlobalTransaction tx = GlobalTransactionContext.getCurrent();
    // 1.2 传播行为分派
    switch (propagation) { ... }
    // 1.3 把注解上的全局锁配置压栈（4.8 节用）
    GlobalLockConfig previousConfig = replaceGlobalLockConfig(txInfo);
    try {
        // 2. Launcher 才真正 begin
        beginTransaction(txInfo, tx);
        Object rs;
        try {
            // 3. 执行业务
            rs = business.execute();
        } catch (Throwable ex) {
            // 3.1 按回滚规则决定回滚或照常提交
            completeTransactionAfterThrowing(txInfo, tx, ex);
            throw ex;
        }
        // 4. 提交
        commitTransaction(tx, txInfo);
        return rs;
    } finally {
        // 5. 恢复锁配置、触发 afterCompletion 钩子、清理
        resumeGlobalLockConfig(previousConfig);
        triggerAfterCompletion(tx);
        cleanUp(tx);
    }
    // finally: 挂起过的事务 resume 回来
}
```

**传播行为分派**（`:193-240`）逐条对齐 Spring：

- `REQUIRED`：`GlobalTransactionContext.getCurrentOrCreate()`——有 XID 就当 Participant 加入，没有就 new 一个 Launcher；
- `REQUIRES_NEW`：先 `tx.suspend(false)` 把外层 XID 从 ThreadLocal 摘下存进 `SuspendedResourcesHolder`，再 createNew 开新事务，结束时 `tx.resume(holder)` 换回外层 XID（`:201-208`、`:274-279`）——注意**外层事务并未结束**，内层是独立的全局事务；
- `SUPPORTS`/`NOT_SUPPORTED`/`NEVER`/`MANDATORY`：分别对应"没事务就裸跑/有事务就挂起裸跑/有事务就炸/没事务就炸"。

**提交时机的两处关键判断**（`commitTransaction` `:330-393`）：

1. **客户端先行超时检测**（`:338-347`）：`isTimeout(tx.getCreateTime(), txInfo)` 成立就直接转 rollback——不等 TC 判超时，省一轮 RPC；
2. **提交结果状态翻译**（`:352-384`）：TC 返回的 `GlobalStatus` 若是 `TimeoutRollbacking/RollbackRetrying/Finished/...`，翻译成 `ExecutionException.Code`（Rollbacking/RollbackDone/CommitFailure），外层拦截器据此触发 `FailureHandler` 对应回调（`failureHandler.onCommitFailure(...)` 等，见 `integration-tx-api/.../GlobalTransactionalInterceptorHandler.java:249-277`）。

**回滚规则**（`completeTransactionAfterThrowing` `:318-328` + `TransactionInfo.rollbackOn`）：与 Spring 完全同构——`RollbackRule/NoRollbackRule` 列表逐个匹配异常类名，默认 RuntimeException + Error 回滚。

## 3.4 DefaultTransactionManager：API 变 RPC

【源码证据】`tm/src/main/java/org/apache/seata/tm/DefaultTransactionManager.java:84-136`。begin 就是拼一个 `GlobalBeginRequest`（name + timeout）同步发出去：

```java
@Override
public String begin(String applicationId, String transactionServiceGroup, String name, int timeout)
        throws TransactionException {
    GlobalBeginRequest request = new GlobalBeginRequest();
    request.setTransactionName(name);
    request.setTimeout(timeout);
    GlobalBeginResponse response = (GlobalBeginResponse) syncCall(request);
    if (response.getResultCode() == ResultCode.Failed) {
        throw new TmTransactionException(TransactionExceptionCode.BeginFailed, response.getMsg());
    }
    return response.getXid();
}

private AbstractTransactionResponse syncCall(AbstractTransactionRequest request) throws TransactionException {
    try {
        return (AbstractTransactionResponse) TmNettyRemotingClient.getInstance().sendSyncRequest(request);
    } catch (TimeoutException toe) {
        throw new TmTransactionException(TransactionExceptionCode.IO, "RPC timeout", toe);
    }
}
```

TM 侧 RPC 超时默认 30 秒（`DEFAULT_RPC_TM_REQUEST_TIMEOUT`，`common/.../DefaultValues.java:442`）。commit/rollback/getStatus/globalReport 四个方法如法炮制（`:97-127`）。整个类没有一行状态管理——全局事务的真正状态在 TC，TM 只是"遥控器"。

## 3.5 事务钩子与失败处理器

- `TransactionHookManager`（`tm/api/transaction/TransactionHookManager.java`）：ThreadLocal 保存本事务的钩子链，`TransactionalTemplate` 在 beforeCommit/afterCommit/beforeRollback/afterRollback/afterCompletion 五个时机触发（`triggerBeforeCommit` 等，`TransactionalTemplate.java:350/388/404/406/271`）。注册方式 `TransactionHookManager.registerHook(...)`，常用于"提交后发 MQ/清缓存"。
- `FailureHandler`（`tm/api/FailureHandler.java` + `DefaultFailureHandlerImpl`）：begin/commit/rollback 失败、Rollbacking 中的四种回调，默认实现只打日志；用户可注入自己的 Bean 做告警（`SeataAutoConfiguration.java:50-54` 默认装配）。

## 3.6 本章小结

- TM 的全部工作 = **在正确的时机向 TC 发正确的消息**；`DefaultGlobalTransaction` 管角色与重试，`TransactionalTemplate` 管边界与传播，`DefaultTransactionManager` 管 RPC。
- 角色（Launcher/Participant）由"当前线程是否有 XID"决定，这让"链路上每个服务都写 `@GlobalTransactional`"成为合法姿势——只有第一个生效。
- 传播行为与回滚规则和 Spring `@Transactional` 同构，迁移心智成本几乎为零；差别在于超时由**客户端先行检测**（TC 兜底）、提交结果要"翻译"成异常语义。
- finally 里 `suspend(true)` 清上下文是线程安全的关键一笔——线程池场景漏了它就会"事务串门"。

---

# 四、RM 与 AT 模式（一）：SQL 拦截、前后镜像与全局锁注册（seata-rm-datasource）

## 4.1 模块定位：AT 的四层代理

**白话**：AT 模式要"业务无感"，唯一的办法是把 JDBC 的每一层都包一层代理，在 `commit()` 这个动作上做文章：提交前抢全局锁、写 undo_log；执行 SQL 时拍快照。四层代理依次是：

```
DataSource（连接池入口）
  └─ DataSourceProxy          资源身份（resourceId=jdbcUrl）+ 资源注册
       └─ AbstractConnectionProxy → ConnectionProxy   事务上下文 + commit 拦截
            └─ AbstractStatementProxy → StatementProxy   语句执行拦截
                 └─ ExecuteTemplate → 各 SQL 执行器            SQL 解析 + 镜像 + undo_log
```

配套的角色分工（`rm` 模块）：

- `DefaultResourceManager`（`rm/.../DefaultResourceManager.java:38`）：按 BranchType 路由到具体 RM（`getResourceManager(branchType)` `:146`），对外提供 branchRegister/branchReport/lockQuery 三个 RM 专属 RPC（`:93/106/113`）；
- `DataSourceManager`（`rm-datasource/.../DataSourceManager.java:45`，AT 模式的 ResourceManager 实现）：持有本进程所有被代理数据源（`dataSourceCache`，key=resourceId）；
- `AbstractRMHandler`（`rm/.../AbstractRMHandler.java:42`）：TC 主动下发消息（BranchCommit/BranchRollback）时客户端侧的**入口**——注意方向反过来了：二阶段是 TC 调 RM，不是 RM 调 TC。

## 4.2 DataSourceProxy：一个数据源 = 一个资源

【源码证据】`rm-datasource/src/main/java/org/apache/seata/rm/datasource/DataSourceProxy.java:105-133`，构造时 init：

```java
private void init(DataSource dataSource, String resourceGroupId) {
    this.resourceGroupId = resourceGroupId;
    try (Connection connection = dataSource.getConnection()) {
        jdbcUrl = connection.getMetaData().getURL();
        dbType = JdbcUtils.getDbType(jdbcUrl);
        ...
        checkUndoLogTableExist(connection);   // 启动期就验证 undo_log 表存在
    } catch (SQLException e) { ... }
    initResourceId();
}
```

- **resourceId 就是 jdbcUrl**（`initResourceId()` 把 `jdbcUrl` 去掉 `?` 之后的部分作为 key）——TC 的 lock 表、分支表都以它为"资源坐标"。
- `registerResource()`（`DataSourceManager.java:77-82`）把数据源放进本地 cache，并调 `RmNettyRemotingClient.registerResource(...)` 把 resourceId 报给 TC——之后 TC 的二阶段消息才能按 resourceId 找回这台客户端（第七章 7.5 节的 RM_CHANNELS 索引）。
- `checkUndoLogTableExist` 说明 AT 有"建表前置检查"：`script/client/at/db/mysql.sql:19-33` 提供了 undo_log 建表脚本（`branch_id`、`xid`、`context`、`rollback_info LONGBLOB`、`log_status`（0 正常/1 防御）、`UNIQUE KEY ux_undo_log (xid, branch_id)`）。

## 4.3 SQL 执行链：ExecuteTemplate 与执行器族

**白话**：业务执行任何一条写 SQL，都会先被解析成结构（认出是什么类型、动了哪张表），再分派给对应的执行器——每种 SQL 的"拍镜像"策略不同，但骨架一致。

【源码证据】分派逻辑在 `rm-datasource/.../exec/ExecuteTemplate.java`：拿 `SQLRecognizer`（由 `sqlparser/seata-sqlparser-druid` 的 `DruidSQLRecognizerFactoryImpl` 按 dbType 创建，见 `sqlparser/seata-sqlparser-druid/.../DruidSQLRecognizerFactoryImpl.java`），按 SQLType（INSERT/UPDATE/DELETE/SELECT_FOR_UPDATE）+ dbType 从工厂取执行器：

| SQL 类型 | 执行器 | 镜像策略 |
|---|---|---|
| UPDATE | `UpdateExecutor` | beforeImage：按 where 条件反向 select；afterImage：按主键再 select |
| DELETE | `DeleteExecutor` | beforeImage：反向 select；afterImage = 空 |
| INSERT | `InsertExecutor`（及 mysql/oracle/pg 等 10+ 变体） | beforeImage = 空；afterImage：按主键 select |
| SELECT FOR UPDATE | `SelectForUpdateExecutor` | 不拍镜像，改为**抢全局锁**（4.8 节） |

镜像的载体是 `sql/struct/TableRecords`（表名 + 行集合，每行是 `Field` 列表并标注主键）。

## 4.4 executeAutoCommitTrue：自动提交场景的"伪事务"（本章关键细节）

**白话**：Spring + MyBatis 场景每条写 SQL 通常都在 `autoCommit=true` 下执行。但 AT 的"拍镜像 + 抢锁 + 写 undo_log + 提交"必须在一个**本地事务**里完成，否则两个动作之间数据可能被别人改掉。所以 Seata 先把 autoCommit 偷偷改成 false，干完再改回来。

【源码证据】`rm-datasource/.../exec/AbstractDMLBaseExecutor.java:80-88`：

```java
@Override
public T doExecute(Object... args) throws Throwable {
    AbstractConnectionProxy connectionProxy = statementProxy.getConnectionProxy();
    if (connectionProxy.getAutoCommit()) {
        return executeAutoCommitTrue(args);
    } else {
        return executeAutoCommitFalse(args);
    }
}
```

`:146-166`，自动提交路径：

```java
protected T executeAutoCommitTrue(Object[] args) throws Throwable {
    ConnectionProxy connectionProxy = statementProxy.getConnectionProxy();
    try {
        connectionProxy.changeAutoCommit();     // autoCommit=false，并记录 isAutoCommitChanged
        return new LockRetryPolicy(connectionProxy).execute(() -> {
            T result = executeAutoCommitFalse(args);   // 拍镜像 + 执行 SQL
            connectionProxy.commit();                  // 走 5.1 节的提交流程（注册分支+写undo_log+commit）
            return result;
        });
    } catch (Exception e) { ...
    } finally {
        connectionProxy.getContext().reset();
        connectionProxy.setAutoCommit(true);           // 恢复
    }
}
```

`:97-112`，核心三步（非自动提交路径也是它）：

```java
TableRecords beforeImage = beforeImage();                                  // ① 前镜像
T result = statementCallback.execute(statementProxy.getTargetStatement(), args); // ② 真正执行 SQL
TableRecords afterImage = afterImage(beforeImage);                         // ③ 后镜像
prepareUndoLog(beforeImage, afterImage);                                   // ④ 暂存 undo_log（还不落库）
```

`ConnectionProxy.changeAutoCommit()`（`ConnectionProxy.java:297-300`）把 `autoCommitChanged` 标志写进上下文——`setAutoCommit(true)` 恢复时会触发 JDBC 规范要求的"先 commit"（`:302-309`），避免隐式提交把事务提前收掉。

## 4.5 前后镜像：以 UPDATE 为例

【源码证据】`rm-datasource/.../exec/UpdateExecutor.java:67-115`：

- `beforeImage()`：构造 `select * from t where (原 where 条件)`（`buildBeforeImageSQL` `:74`，Seata 1.5 起加了优化：只 select 主键 + where 中出现的列 + 被更新的列，而非全列），执行后 `TableRecords.buildRecords` 得到改前快照；
- `afterImage(beforeImage)`：用 beforeImage 里**查出的主键**构造 `select ... where pk in (...)`（`buildAfterImageSQL` `:117`、`SqlGenerateUtils.setParamForPk(beforeImage.pkRows(), ...)` `:109`）——用主键回查而不是重新执行 where 条件，**保证 after 镜像就是"被我改过的那些行"**，不受其他并发事务影响。

INSERT/DELETE 的 before/after 策略见 4.3 节表格；JDK 层面所有执行器的公共骨架在 `AbstractDMLBaseExecutor.executeAutoCommitFalse`。

## 4.6 lockKey 与全局锁注册（本章核心）

**白话**：拍完镜像还差最后一件事——把"我锁了哪些行"告诉 TC。lockKey 是一个紧凑字符串：`表名:主键值1,主键值2`（联合主键用 `_` 连接）。TC 收到后写进全局锁表，第二个事务想锁同一行就要排队。

【源码证据】`rm-datasource/.../exec/BaseTransactionalExecutor.java:404-424`：

```java
protected void prepareUndoLog(TableRecords beforeImage, TableRecords afterImage) throws SQLException {
    if (beforeImage.getRows().isEmpty() && afterImage.getRows().isEmpty()) {
        return;                                        // 无行变动，什么都不用做
    }
    ...
    TableRecords lockKeyRecords = sqlRecognizer.getSQLType() == SQLType.DELETE ? beforeImage : afterImage;
    String lockKeys = buildLockKey(lockKeyRecords);    // DELETE 用前镜像，其余用后镜像
    if (null != lockKeys) {
        connectionProxy.appendLockKey(lockKeys);       // 暂存到 ConnectionContext
        SQLUndoLog sqlUndoLog = buildUndoItem(beforeImage, afterImage);
        connectionProxy.appendUndoLog(sqlUndoLog);
    }
}
```

`buildLockKey`（`:443-475`）生成形如 `t_order:1,2,3` 的串（联合主键 `t_user:1_a,2_b`，见注释 `:441`）。注意 lockKey 与 SQLUndoLog 都只是**暂存**在 `ConnectionContext` 的两个 buffer 里（`ConnectionContext.java:72/76`，按 savepoint 分组，支持 savepoint 回退时撤销），真正落库/上报发生在 commit 时。

**注册动作**在 commit 流程里（5.1 节），RPC 层一路是：`ConnectionProxy.register()` → `DefaultResourceManager.branchRegister(BranchType.AT, resourceId, null, xid, applicationData, lockKeys)`（`ConnectionProxy.java:267-281`）→ `AbstractResourceManager.branchRegister` 组装 `BranchRegisterRequest` 发给 TC（`rm/.../AbstractResourceManager.java:73-92`）。

**TC 侧收锁**：`DefaultCore.branchRegister`（`server/.../coordinator/DefaultCore.java:105-115`）按 BranchType 找到 `ATCore`，走 `AbstractCore.branchRegister`（`server/.../coordinator/AbstractCore.java:87-132`）：

```java
return SessionHolder.lockAndExecute(globalSession, () -> {
    globalSessionStatusCheck(globalSession);              // 会话必须还活着且是 Begin 状态
    BranchSession branchSession = SessionHelper.newBranchByGlobal(
            globalSession, branchType, resourceId, applicationData, lockKeys, clientId);
    branchSessionLock(globalSession, branchSession);      // ★ AT 在这里抢全局锁（空实现由子类覆盖）
    globalSession.addBranch(branchSession);               // 分支挂到全局会话
    ...
    return branchSession.getBranchId();
});
```

`ATCore.branchSessionLock`（`server/.../transaction/at/ATCore.java:57-96`）就是锁冲突的判定点：

```java
if (!branchSession.lock(autoCommit, skipCheckLock)) {
    throw new BranchTransactionException(
            LockKeyConflict,
            String.format("Global lock acquire failed xid = %s branchId = %s",
                    globalSession.getXid(), branchSession.getBranchId()));
}
```

抢锁失败抛 `LockKeyConflict`，客户端 `ConnectionProxy.recognizeLockKeyConflictException`（`ConnectionProxy.java:152-164`）把它翻成 `LockConflictException`，由重试策略接手（5.1、4.8 节）。`BranchSession.branchId` 由 `UUIDGenerator.generateUUID()` 生成（`server/.../session/SessionHelper.java:107`）——所以分支 ID 是雪花式长整数，与全局事务 ID 同源。

**锁怎么存**（三种存储实现，见 6.5 节）：file/raft 模式用内存桶结构 `FileLocker`（`server/.../storage/file/lock/FileLocker.java:45-48`，`resourceId → 表名 → 桶号(pk.hashCode()%桶数) → {pk → BranchSession}`，抢锁用 `putIfAbsent` `:92`，失败先回滚已抢的锁再返回 false `:107-118`）；db 模式写 `lock_table`；redis 模式走 Lua 脚本。

## 4.7 undo_log 的写入：flushUndoLogs

**白话**：commit 时（注册分支之后、本地提交之前），把暂存的多条 `SQLUndoLog` 打包成一条 `BranchUndoLog`，序列化后写进业务库的 undo_log 表——和业务数据**同一个本地事务**，这是"日志与数据原子性"的基石。

【源码证据】`rm-datasource/.../undo/AbstractUndoLogManager.java:266-303`：

```java
@Override
public void flushUndoLogs(ConnectionProxy cp) throws SQLException {
    ConnectionContext connectionContext = cp.getContext();
    if (!connectionContext.hasUndoLog()) { return; }
    String xid = connectionContext.getXid();
    long branchId = connectionContext.getBranchId();
    BranchUndoLog branchUndoLog = new BranchUndoLog();
    branchUndoLog.setXid(xid);
    branchUndoLog.setBranchId(branchId);
    branchUndoLog.setSqlUndoLogs(connectionContext.getUndoItems());
    UndoLogParser parser = UndoLogParserFactory.getInstance();     // 默认 jackson
    byte[] undoLogContent = parser.encode(branchUndoLog);
    CompressorType compressorType = CompressorType.NONE;
    if (needCompress(undoLogContent)) {                            // 太大先压缩
        compressorType = ROLLBACK_INFO_COMPRESS_TYPE;
        undoLogContent = CompressorFactory.getCompressor(compressorType.getCode()).compress(undoLogContent);
    }
    String rollbackCtx = buildContext(parser.getName(), compressorType,
            UndoLogConstants.MAX_ALLOWED_PACKET, maxAllowedPacket);
    insertUndoLogWithNormal(xid, branchId, rollbackCtx, undoLogContent, cp.getTargetConnection());
}
```

MySQL 的插入 SQL（`undo/mysql/MySQLUndoLogManager.java:54-58`）：

```java
private static final String INSERT_UNDO_LOG_SQL = "INSERT INTO " + UNDO_LOG_TABLE_NAME + " ("
        + "branch_id, xid, context, rollback_info, log_status, log_created, log_modified)"
        + " VALUES (?, ?, ?, ?, ?, now(6), now(6))";
```

序列化器默认 jackson（`DEFAULT_TRANSACTION_UNDO_LOG_SERIALIZATION = "jackson"`，`common/.../DefaultValues.java:257`），可选 kryo/protostuff/fastjson/fory 等（`undo/parser/` 目录），`context` 列记录序列化器名与压缩方式，回滚时按它反序列化（5.3 节）。

## 4.8 select for update 与 @GlobalLock：读也能被"锁住"

**白话**：AT 默认读不加全局锁，两个事务可能"读到了彼此回滚前的数据"。要读到最新已提交数据，用 `select ... for update`——AT 会先执行本地查询，再拿着查到的行去 TC 抢全局锁，抢不到就重试，抢到了才返回。

【源码证据】`rm-datasource/.../exec/SelectForUpdateExecutor.java:59-133`：

```java
while (true) {
    try {
        rs = statementCallback.execute(...);                          // ① 先执行本地 select for update
        TableRecords selectPKRows = buildTableRecords(getTableMeta(), selectPKSQL, paramAppenderList);
        String lockKeys = buildLockKey(selectPKRows);                 // ② 用查到的行构造 lockKey
        ...
        if (RootContext.inGlobalTransaction() || RootContext.requireGlobalLock()) {
            statementProxy.getConnectionProxy().checkLock(lockKeys);  // ③ 去 TC 查/抢全局锁
        } else { throw new RuntimeException("Unknown situation!"); }
        break;
    } catch (LockConflictException lce) {
        if (sp != null) { conn.rollback(sp); } else { conn.rollback(); }
        lockRetryController.sleep(lce);                               // ④ 释放本地锁 → 睡一会 → 重来
    }
}
```

细节值得咀嚼：执行前先把 autoCommit 设为 false 或设置 savepoint（`:64-81`）——**在全局锁冲突时回滚本地事务、释放本地行锁**，防止"我拿着本地锁等全局锁、对方拿着全局锁等我提交"的死锁组合；`checkLock` 走的是 `lockQuery` RPC（`ConnectionProxy.java:113-127` → `DefaultResourceManager.lockQuery` → `GlobalLockQueryRequest`，`DataSourceManager.java:54-75`），TC 侧 `ATCore.lockQuery` 直接查锁表 `isLockable`（`ATCore.java:104-107`）。

`@GlobalLock` 注解（非全局事务里的本地事务也想参与全局互斥）的入口在 `GlobalLockTemplate`（`rm-datasource/.../GlobalLockTemplate.java:28-50`）：`RootContext.bindGlobalLockFlag()` 绑标志 → `GlobalLockConfigHolder` 压栈重试配置 → 执行 → 恢复。这样 4.4 节的 `doCommit` 分叉（`ConnectionProxy.java:227-235`）才会走 `processLocalCommitWithGlobalLocks`（`:237-245`）：只 checkLock + 本地提交，不注册分支、不写 undo_log。

## 4.9 本章小结

- AT 一阶段 = **四层代理 + 一个模板**：`beforeImage() → SQL → afterImage() → prepareUndoLog()`（`AbstractDMLBaseExecutor.java:97-112`），全部发生在把 autoCommit 偷偷改为 false 的"伪事务"里；
- lockKey（`表:pk` 粒度）与 SQLUndoLog 在 commit 时才生效：前者注册全局锁，后者落 undo_log 表；
- 镜像查询的精髓是"after 镜像用 before 镜像的主键回查"，保证快照与被改行严格对应；
- 全局锁冲突在 TC 侧抛 `LockKeyConflict`，客户端翻译成 `LockConflictException` 交给 `LockRetryController` 重试；`select for update` + `@GlobalLock` 让读操作也能参与全局互斥；
- undo_log 与业务数据同事务写入（序列化器默认 jackson，过大自动压缩），`UNIQUE KEY (xid, branch_id)` 保证分支唯一。

---

# 五、RM 与 AT 模式（二）：二阶段——异步提交与反向补偿回滚

## 5.1 一阶段本地提交的完整流程（承上启下）

**白话**：上一章的"暂存"在 `ConnectionProxy.commit()` 汇聚成一次完整的一阶段收尾。这是 AT 模式被调用频率最高的方法，值得逐行读。

【源码证据】`rm-datasource/src/main/java/org/apache/seata/rm/datasource/ConnectionProxy.java`，入口 `commit()` `:184-199`——包了一层锁重试策略（抢全局锁失败 → 整个本地事务回滚重来）：

```java
@Override
public void commit() throws SQLException {
    try {
        lockRetryPolicy.execute(() -> { doCommit(); return null; });
    } catch (SQLException e) {
        if (targetConnection != null && !getAutoCommit() && !getContext().isAutoCommitChanged()) {
            rollback();                              // 真正的业务事务失败，回滚本地事务
        }
        throw e;
    } ...
}
```

`doCommit()` `:227-235` 三分叉：在全局事务里走 `processGlobalTransactionCommit`；在 `@GlobalLock` 本地事务里走 `processLocalCommitWithGlobalLocks`（4.8 节）；普通本地事务直接透传 `targetConnection.commit()`——**没挂全局事务的连接零开销**，这是 AT "无事务路径无性能损失"的保证。

`processGlobalTransactionCommit()` `:247-265`，四步：

```java
private void processGlobalTransactionCommit() throws SQLException {
    try {
        register();                                   // ① 注册分支 + 抢全局锁（4.6 节）
    } catch (TransactionException e) {
        recognizeLockKeyConflictException(e, context.buildLockKeys());
    }
    try {
        UndoLogManagerFactory.getUndoLogManager(this.getDbType()).flushUndoLogs(this); // ② 写 undo_log
        targetConnection.commit();                    // ③ 业务数据 + undo_log 一起本地提交
    } catch (Throwable ex) {
        report(false);                                // ④ 提交失败 → 上报 PhaseOne_Failed
        throw new SQLException(ex);
    }
    if (IS_REPORT_SUCCESS_ENABLE) {
        report(true);                                 // ④' 成功上报（默认关闭，省一次 RPC）
    }
    context.reset();
}
```

`register()` `:267-281` 开头有个精明的短路：`if (!context.hasUndoLog() || !context.hasLockKey()) return;`——**纯查询或没涉及表的"空写"不注册分支**，二阶段自然无事可做。

一阶段异常路径：`rollback()` `:283-290`——本地回滚后，如果分支已经注册过（极端：注册成功但 flushUndoLogs 失败），补一个 `report(false)` 告诉 TC"我这个分支 PhaseOne_Failed"，TC 二阶段直接跳过它（`DefaultCore.doGlobalCommit/Rollback` 里对 `PhaseOne_Failed` 的 removeBranch 分支，`server/.../DefaultCore.java:302-306/437-441`）。

## 5.2 二阶段提交：AsyncWorker 把"提交"变成"删日志"

**白话**：AT 一阶段数据已提交，二阶段"提交"其实什么都不用做——只需要把 undo_log 删掉。如果删失败呢？也没事，undo_log 多留一会儿不影响正确性。所以 AT 把全局提交设计成异步：TC 把全局事务标记为 `AsyncCommitting`，客户端把要删的日志攒一攒批量删。

【源码证据】链路从 TC 侧看：`DefaultCore.commit`（`server/.../DefaultCore.java:237-281`）判断 `globalSession.canBeCommittedAsync()`（`GlobalSession.java:165-170`——所有分支都是 AT 就返回 true）→ 是则 `globalSession.asyncCommit()` 状态置为 `AsyncCommitting` 并**立即向 TM 返回 Committed**——TM 的 `@GlobalTransactional` 方法就此放行，用户零等待。

随后客户端收到 TC 下发的 `BranchCommitRequest`（第七章处理器表 → `AbstractRMHandler.handle` `:48-61` → `doBranchCommit` `:95-112`）→ `DataSourceManager.branchCommit`（`DataSourceManager.java:112-115`）→ `AsyncWorker.branchCommit`：

```java
// rm-datasource/.../AsyncWorker.java:78-94
public BranchStatus branchCommit(String xid, long branchId, String resourceId) {
    Phase2Context context = new Phase2Context(xid, branchId, resourceId);
    addToCommitQueue(context);          // 只入队，立即返回 PhaseTwo_Committed
    return BranchStatus.PhaseTwo_Committed;
}
```

批量执行（`:110-123` + `:138-167`）：`AsyncWorker` 构造时注册了一个**每 1 秒**跑一次的任务（`:74-75`，`scheduleAtFixedRate(this::doBranchCommitSafely, 10, 1000, ms)`），到点把队列 `drainTo` 出来按 resourceId 分组，每组拆成不超过 `UNDOLOG_DELETE_LIMIT_SIZE = 1000` 条的批次（`:57`），用**普通 JDBC 连接**（不走代理，`getPlainConnection()` `:153`）批量删除：

```java
undoLogManager.batchDeleteUndoLog(xids, branchIds, conn);   // AbstractUndoLogManager.java:158
```

队列缓冲上限 `ASYNC_COMMIT_BUFFER_LIMIT` 默认 10000（`DefaultValues.java:50`）；队列满了不阻塞业务，而是"立刻清空一轮再入队"（`addToCommitQueue` `:88-94`）。

## 5.3 二阶段回滚：undo() 反向补偿（本章核心）

**白话**：全局回滚时，TC 按**分支注册的逆序**逐个下发 `BranchRollbackRequest`。RM 读出 undo_log，用快照生成反向 SQL（insert 的回滚是 delete、update 的回滚是"把值改回 before"），执行前先校验"当前数据和 after 镜像是否一致"——不一致说明有人绕过 Seata 改了数据，报告脏写，停止重试等人工介入。

【源码证据】入口 `DataSourceManager.branchRollback`（`rm-datasource/.../DataSourceManager.java:119-145`）：

```java
try {
    UndoLogManagerFactory.getUndoLogManager(dataSourceProxy.getDbType()).undo(dataSourceProxy, xid, branchId);
} catch (TransactionException te) {
    ...
    if (te.getCode() == TransactionExceptionCode.BranchRollbackFailed_Unretriable) {
        return BranchStatus.PhaseTwo_RollbackFailed_Unretryable;   // 脏写 → 告诉 TC 别重试了
    } else {
        return BranchStatus.PhaseTwo_RollbackFailed_Retryable;     // 网络/锁问题 → TC 稍后重试
    }
}
return BranchStatus.PhaseTwo_Rollbacked;
```

TC 侧逆序回滚的凭证在 `DefaultCore.doGlobalRollback`（`server/.../DefaultCore.java:424-511`）：`getReverseSortedBranches()` `:432`——后注册的分支先回滚，保证"先退改在上游的数据"（也符合 undo_log 里多条日志的逆序执行，见下）。

`AbstractUndoLogManager.undo`（`rm-datasource/.../undo/AbstractUndoLogManager.java:315-468`）主干：

1. **循环 + 本地事务包裹**（`:322-331`，`for(;;)` 整个回滚在一个本地事务里跑）；
2. **读 undo_log**（`:334-337`，按 branchId + xid）；
3. **状态过滤**（`:346-352`）：`canUndo(state)`——`log_status=1`（防御态，5.4 节）的日志直接跳过，防止 TC 把同一分支重复下发给多个客户端实例时重复回滚；
4. **反序列化 + 逆序执行**（`:354-378`）：

```java
UndoLogParser parser = UndoLogParserFactory.getInstance(serializer);  // context 列里记录的
BranchUndoLog branchUndoLog = parser.decode(rollbackInfo);
List<SQLUndoLog> sqlUndoLogs = branchUndoLog.getSqlUndoLogs();
if (sqlUndoLogs.size() > 1) { Collections.reverse(sqlUndoLogs); }     // 后执行的先回滚
for (SQLUndoLog sqlUndoLog : sqlUndoLogs) {
    ...
    AbstractUndoExecutor undoExecutor =
            UndoExecutorFactory.getUndoExecutor(dataSourceProxy.getDbType(), sqlUndoLog);
    undoExecutor.executeOn(connectionProxy);      // ★ 反向 SQL
}
```

5. **反向 SQL 的构造**：`AbstractUndoExecutor.executeOn`（`undo/AbstractUndoExecutor.java:114-148`）按 undo 类型选语句——`MySQLUndoUpdateExecutor` 生成 `UPDATE t SET (before 值) WHERE pk in (...)`、insert 的回滚是 `DELETE WHERE pk`、delete 的回滚是 `INSERT (before 行)`（PK 恒排最后，注释 `:210-213`）；
6. **脏写检查**（`:234-280`，`dataValidationAndGoOn`，在 executeOn 开头调用 `:116`）：

```java
// after 镜像 与 当前数据 比对
Result<Boolean> afterEqualsCurrentResult = DataCompareUtils.isRecordsEquals(afterRecords, currentRecords);
if (!afterEqualsCurrentResult.getResult()) {
    // 再与 before 镜像比对（可能数据被改了又改回去）
    Result<Boolean> beforeEqualsCurrentResult = DataCompareUtils.isRecordsEquals(beforeRecords, currentRecords);
    if (beforeEqualsCurrentResult.getResult()) { return false; }   // 等于改前 → 无需回滚
    else { throw new SQLUndoDirtyException("Has dirty records when undo."); }   // ★ 脏写
}
```

脏写 → `BranchRollbackFailed_Unretriable`（`:442-452`）→ TC 收到后 `endRollbackFailed`，全局事务停回滚，等人工处理（undo_log 里保留着 before/after 镜像，就是"校准数据"的现场）；
7. **收尾**（`:413-428`）：有 undo_log → `deleteUndoLog` + commit；**没有 undo_log（分支本地事务没来得及提交就收到回滚，比如业务超时）→ `insertUndoLogWithGlobalFinished` 写一条 `log_status=1` 的防御日志**（`State.GlobalFinished`，注释引用 issue #489），防止迟到的本地提交"复活"已被回滚的数据。

## 5.4 防御日志（log_status=1）与回滚幂等

5.3 的第 7 步和第 3 步是一对组合拳，解决回滚的两大经典竞态：

| 竞态场景 | 时序 | 解法 |
|---|---|---|
| 分支本地事务提交晚于回滚到达 | TC 超时回滚 → RM 写 `log_status=1` 防御日志 + commit → 迟到的一阶段 commit 想写 undo_log？唯一键 `(xid, branch_id)` 冲突 → 本地提交失败，业务报错（可接受：事务已超时） | 防御日志 + 唯一键 |
| 同一分支的回滚请求被投递到多个 RM 实例（客户端集群） | 先到的回滚并删日志 → 后到的读到 `log_status=1` 或查无日志 → 静默跳过/写防御日志 | `canUndo` 状态过滤（`:346-352`）+ 防御日志 |
| 回滚执行中 undo_log 又被插入 | `SQLIntegrityConstraintViolationException` → 捕获后 `for(;;)` 重试（`:431-435`） | 循环重试 |

## 5.5 提交侧的兜底：PhaseOne_Failed 与全局锁的提前释放

- 一阶段上报 `PhaseOne_Failed` 的分支，TC 在提交与回滚两条路径都会直接 `removeBranch` 跳过（`DefaultCore.java:302-306/437-441`）——分支级失败不会拖垮全局事务的其余分支。
- 回滚时**全局锁的释放**发生在 `GlobalSession.clean()` / 分支删除里（`GlobalSession.java:365`，`branchSession.unlock()`）；TC 允许配置"回滚重试超时后强制解锁"（`ROLLBACK_RETRY_TIMEOUT_UNLOCK_ENABLE`，`DefaultCoordinator.java:172`、`:462-465`）——防止死锁把锁"焊死"。
- 全局事务提交/回滚的**逆序/并行**受 `ENABLE_PARALLEL_HANDLE_BRANCH` 开关控制（`DefaultCore.java:62-64`，默认 false），并行时按分支数 ≥2 才启用（`:379/497`）。

## 5.6 本章小结

- AT 二阶段提交 = **客户端异步删 undo_log**（AsyncWorker，1s 一批 1000 条），TM 在 TC 标记 `AsyncCommitting` 的瞬间就拿到成功——用户视角的全局提交耗时 ≈ 一阶段耗时；
- AT 二阶段回滚 = **读 undo_log → 逆序 → 前后镜像双重比对 → 反向 SQL → 删日志**，全程在 RM 的本地事务里；脏写是**不可重试**错误，锁/网络问题是**可重试**错误——分支状态枚举把意图带给 TC；
- `log_status=1` 防御日志 + `(xid, branch_id)` 唯一键 + `canUndo` 过滤三件套，把"迟到的一阶段""重复的二阶段"两类竞态全部兜住；
- 一切的前提仍是 4.6 节的全局锁：**写隔离靠锁，回滚正确性靠镜像比对**——两道防线缺一不可。

---

# 六、TC：事务协调器（seata-server）

## 6.1 模块定位与启动流程

**白话**：seata-server 是个薄壳 Spring Boot 应用：起来一个 Netty 服务端（端口 8091）、初始化会话存储、装配协调器、起定时任务——四件事。

【源码证据】`server/src/main/java/org/apache/seata/server/Server.java:108-130`（`start` 方法尾部，即主流程）：

```java
NettyRemotingServer nettyRemotingServer = new NettyRemotingServer(workingThreads);
XID.setPort(nettyRemotingServer.getListenPort());
UUIDGenerator.init(parameterParser.getServerNode());
...
DefaultCoordinator coordinator = DefaultCoordinator.getInstance(nettyRemotingServer);
... // 注册进 Spring 容器，同时注册为 ApplicationListener
SessionHolder.init();          // ① 会话存储（file/db/redis/raft 四选一）
LockerManagerFactory.init();   // ② 锁管理器（与存储模式配套）
coordinator.init();            // ③ 六个定时任务
nettyRemotingServer.setHandler(coordinator);   // ④ 消息处理器 = 协调器
nettyRemotingServer.init();    // ⑤ 网络开服
```

`ServerRunner`（`server/.../ServerRunner.java:38`，`implements CommandLineRunner`）是真正的入口；参数解析（`ParameterParser`）负责 store mode、serverNode 等。

## 6.2 请求入口：DefaultCoordinator

**白话**：所有 TM/RM 请求的落点。DefaultCoordinator 同时是 `TCInboundHandler`（处理 TM 的全局事务请求）和 `TransactionMessageHandler`（处理 RM 的分支请求），再加一串定时任务——一个类把"接线员"和"调度员"两个角色演完。

【源码证据】`server/.../coordinator/DefaultCoordinator.java:792-803`：

```java
@Override
public AbstractResultMessage onRequest(AbstractMessage request, RpcContext context) {
    if (!(request instanceof AbstractTransactionRequestToTC)) { throw new IllegalArgumentException(); }
    AbstractTransactionRequestToTC transactionRequest = (AbstractTransactionRequestToTC) request;
    transactionRequest.setTCInboundHandler(this);
    LimitRequestDecorator limitRequestDecorator = new LimitRequestDecorator(transactionRequest);
    return limitRequestDecorator.handle(context);   // 带限流装饰器
}
```

五个 `doXxx` 方法只是薄转发（`:321-385`）：`doGlobalBegin` → `core.begin(...)`、`doGlobalCommit` → `core.doGlobalCommit(...)`、`doGlobalRollback`、`doBranchRegister` → `core.branchRegister(...)`（4.6 节已读）、`doBranchReport`。真正逻辑全在 `DefaultCore`。

单例工厂里藏着 Raft 分叉（`:249-261`）：

```java
SessionMode storeMode = StoreConfig.getSessionMode();
instance = Objects.equals(SessionMode.RAFT, storeMode)
        ? new RaftCoordinator(remotingServer)      // Raft 模式：写请求走 Raft 状态机
        : new DefaultCoordinator(remotingServer);
```

## 6.3 DefaultCore：全局两阶段的编排（本章核心）

**白话**：TC 侧的"业务逻辑层"。begin 造会话；commit 先关闸（不再收分支）再决定同步/异步提交；rollback 先关闸再逆序驱动每个分支回滚。

【源码证据】`server/.../coordinator/DefaultCore.java`：

**begin**（`:222-234`）：

```java
GlobalSession session = GlobalSession.createGlobalSession(applicationId, transactionServiceGroup, name, timeout);
session.begin();                       // 状态=Begin、记 beginTime、通知存储层落库
MetricsPublisher.postSessionDoingEvent(session, false);
return session.getXid();               // XID 在 createGlobalSession 内生成（GlobalSession.java:461）
```

**commit**（`:237-281`）——注意"先 close 再提交"的顺序注释：

```java
boolean shouldCommit = SessionHolder.lockAndExecute(globalSession, () -> {
    boolean shouldCommitNow = false;
    if (globalSession.getStatus() == GlobalStatus.Begin) {
        // Highlight: Firstly, close the session, then no more branch can be registered.
        globalSession.close();
        if (globalSession.canBeCommittedAsync()) {          // 全部是 AT 分支 → 异步提交
            globalSession.asyncCommit();                    // 状态 → AsyncCommitting，立刻返回
            MetricsPublisher.postSessionDoneEvent(globalSession, GlobalStatus.Committed, false, false);
        } else {
            globalSession.changeGlobalStatus(GlobalStatus.Committing);   // TCC/XA/SAGA → 同步提交
            shouldCommitNow = true;
        }
        globalSession.clean();
    }
    return shouldCommitNow;
});
if (shouldCommit) {
    boolean success = doGlobalCommit(globalSession, false);
    ...
}
```

`canBeCommittedAsync`（`GlobalSession.java:165-170`）遍历分支：全是 AT（`BranchType.AT`）才返回 true——**AT 可以异步提交而 TCC/XA 不行**，因为后者的二阶段 commit 是真正要执行的业务动作（confirm/xa commit），必须同步完成才能给 TM 准话。

**doGlobalCommit**（`:284-397`）逐分支驱动，状态机分支处理值得整段细读：`PhaseTwo_Committed` → `removeBranch` 继续；`PhaseTwo_CommitFailed_Unretryable` → `endCommitFailed` 全局判死；**其他任何值 → `queueToRetryCommit()` 入重试队列**（`:347-349`）。全部分支清空后 `endCommitted` 收尾（`:393-395`）。

**rollback**（`:401-421`）与 **doGlobalRollback**（`:424-511`）：同样先 `close()` 关闸，状态置 `Rollbacking`；回滚按 `getReverseSortedBranches()`（`:432`，**逆序**）驱动，每分支调 `branchRollback`（`:447`，按 BranchType 分派到 `ATCore/TccCore/SagaCore/XACore`），结果为 `PhaseTwo_Rollbacked` → removeBranch；`Unretryable` → 全局 `endRollbackFailed`；其余 → `queueToRetryRollback()`（`:475-477`）。

**分支注册**已在 4.6 节读过（`AbstractCore.branchRegister`）。分支状态上报 `branchReport`（`DefaultCore.java:117-122`）只更新会话，不驱动流程——驱动永远是定时任务的事（6.6 节）。

## 6.4 会话与存储：GlobalSession / BranchSession / 四种 store mode

**白话**：TC 的"数据库"。`GlobalSession`（全局事务）、`BranchSession`（分支）两个内存对象背后，是可以换成 file/db/redis/raft 的持久化层。

【源码证据】存储模式选择在 `server/.../session/SessionHolder.init`（`:100-159`）：

```java
if (SessionMode.DB.equals(sessionMode)) {
    ROOT_SESSION_MANAGER = EnhancedServiceLoader.load(SessionManager.class, SessionMode.DB.getName());
} else if (SessionMode.RAFT.equals(sessionMode) || SessionMode.FILE.equals(sessionMode)) {
    if (SessionMode.RAFT.equals(sessionMode)) {
        ROOT_SESSION_MANAGER = EnhancedServiceLoader.load(SessionManager.class, SessionMode.RAFT.getName(), ...);
        RaftServerManager.init();
        RaftServerManager.start();
    } else { /* FileSessionManager，sessionStorePath 目录 */ }
} else if (SessionMode.REDIS.equals(sessionMode)) { ... }
```

- **SessionManager SPI**：接口方法 onBegin/onStatusChange/onAddBranch/onRemoveBranch/onEnd…（每种状态变化一条持久化）；实现有 `FileSessionManager`、`DataBaseSessionManager`、`RedisSessionManager`、`RaftSessionManager`（`EnhancedServiceLoader` 按 mode 名加载）。
- **重启恢复**：`reload`（`:166-221`）按状态分派：`Rollbacked/TimeoutRollbacked` → 补 `endRollbacked` 清理；`Committed` → 补收尾；`AsyncCommitting/Committing/CommitRetrying` → 重新入重试队列——**TC 宕机重启后事务不丢、继续推进**。
- **db 模式三张核心表**（`script/server/db/mysql.sql:20/40/59`）：`global_table`（全局会话：xid、status、application_id、begin_time、timeout…）、`branch_table`（分支：branch_id、xid、resource_id、lock_key、status…）、`lock_table`（全局锁：row_key、xid、branch_id…），另有 `distributed_lock`（2.x 为异步任务分布式互斥）与 `vgroup_table`（vgroup 映射，2.1+）。
- **分支异步删除**：`ENABLE_BRANCH_ASYNC_REMOVE` 开启时（`DefaultCoordinator.java:231-246`，file 模式除外），二阶段结束后的分支清理走独立线程池 `branchRemoveExecutor`，避免慢存储拖住主流程。

## 6.5 全局锁管理：LockManager 三实现

**白话**：锁的抽象在 `LockManager`（`server/.../lock/LockManager.java`）+ `AbstractLockManager`（`:46-67`，`collectRowLocks` 把 lockKey 字符串解析成 `RowLock{resourceId,tableName,pk,xid,branchId}` 列表，交给具体 Locker）。三种实现与存储模式一一对应：

| 存储模式 | Locker | 锁的存放 | 抢锁方式 |
|---|---|---|---|
| file / raft | `FileLocker`（内存） | `LOCK_MAP`：resourceId → 表名 → 桶号 → {pk → BranchSession}（`FileLocker.java:45-48`） | `putIfAbsent`（`:92`），自持判断（`:98`），冲突先释放已抢（`:107-118`） |
| db | `DataBaseLocker`（`server/storage/db/lock/`） | `lock_table` 表 | `select for update` + insert/delete |
| redis | `RedisLocker` / `RedisLuaLocker`（`server/storage/redis/lock/`） | Redis Hash | Lua 脚本原子抢/释放 |

内存实现里两个精巧点：

1. **分桶**（`:89`，`int bucketId = pk.hashCode() % BUCKET_PER_TABLE`）——每表固定桶数，把大表锁 Map 切小，降低扩容时的全表 rehash 停顿；
2. **failFast**（`:113-117`）：发现持锁方的状态已是 `Rollbacking`（它马上要释放了）且请求方是 autoCommit 事务，直接抛 `LockKeyConflictFailFast`——**不重试，让上层走"回滚再重来"的快路径**（与 4.4 节 `executeAutoCommitTrue` 的 LockRetryPolicy 呼应：`LockRetryPolicy.doRetryOnLockConflict` 把 `FailFast` 翻回普通冲突继续重试，`ConnectionProxy.java:373-376`）。

锁的粒度校验：`AbstractLockManager.isLockable`（`:75-90`）供 `@GlobalLock` 的 lockQuery 使用，只查不占。

## 6.6 定时任务：TC 的心脏（本章核心）

**白话**：TC 不是"请求驱动"就完了——begin 之后全局事务的推进全靠六个定时任务轮询会话表。它们就是 2.3 节那张 GlobalStatus 状态机的"发动机"。

【源码证据】`server/.../coordinator/DefaultCoordinator.java:757-790`，init 注册：

```java
retryRollbacking.scheduleAtFixedRate(() -> SessionHolder.distributedLockAndExecute(RETRY_ROLLBACKING,
        this::handleRetryRollbacking), 0, ROLLBACKING_RETRY_PERIOD, TimeUnit.MILLISECONDS);
retryCommitting.scheduleAtFixedRate(..., COMMITTING_RETRY_PERIOD, ...);
asyncCommitting.scheduleAtFixedRate(..., ASYNC_COMMITTING_RETRY_PERIOD, ...);
timeoutCheck.scheduleAtFixedRate(..., TIMEOUT_RETRY_PERIOD, ...);
undoLogDelete.scheduleAtFixedRate(..., UNDO_LOG_DELAY_DELETE_PERIOD, UNDO_LOG_DELETE_PERIOD, ...);
rollbackingSchedule(0);   // 同步提交/回滚的自适应调度（timeToDeadSession 提前唤醒）
committingSchedule(0);
endSchedule(0);
```

默认周期（`common/.../DefaultValues.java`）与职责：

| 任务 | 默认周期 | 扫描状态 | 动作 |
|---|---|---|---|
| `timeoutCheck`（`:406-446`） | **1s** | Begin | `isTimeout` → `close()` + 状态改 `TimeoutRollbacking`（TM 不来 commit 也要回滚） |
| `handleRetryRollbacking`（`:451-482`） | 1s | TimeoutRollbacking/TimeoutRollbackRetrying/RollbackRetrying | 重发分支回滚；重试超上限 → `clean()`（可配置强制解锁）+ `endRollbackFailed` |
| `handleRetryCommitting`（`:487-519`） | 1s | CommitRetrying | 重发分支提交；超上限 → `endCommitFailed` |
| `handleAsyncCommitting`（`:524-544`） | **1s** | AsyncCommitting | 对 AT 会话重新 `doGlobalCommit(retrying=true)`——驱动 5.2 节的删日志 |
| `undoLogDelete`（`:549-571`） | **24h**（延迟首跑 `UNDO_LOG_DELAY_DELETE_PERIOD`） | — | 向每个 RM 发 `UndoLogDeleteRequest`（保留天数默认 7 天，`UndoLogDeleteRequest.java:33`），客户端按 `log_created` 批删残留 undo_log（`MySQLUndoLogManager.deleteUndoLogByLogCreated` `:65-83`，`DELETE ... WHERE log_created <= ? LIMIT ?`） |
| `handleCommittingByScheduled / handleRollbackingByScheduled / handleEndStatesByScheduled`（`:641-760`） | 自适应（下次到期时间 = 最快要死的会话时间，下限 1s） | Committing/Rollbacking/四个终态前状态 | 优先处理"快到 RETRY_DEAD_THRESHOLD（70s，`:396`）"的会话；终态会话的存储清理 |

两个横切设计：

- **分布式互斥**：每个任务包了一层 `SessionHolder.distributedLockAndExecute(taskKey, runnable)`——多 TC 节点（db/raft 模式）时只有抢到 `distributed_lock` 的节点执行本轮扫描，防止重复驱动。
- **重试上限**：`MAX_COMMIT_RETRY_TIMEOUT / MAX_ROLLBACK_RETRY_TIMEOUT` 默认 -1（无限重试，`DefaultValues.java:533/538`），设为正值后超时会话进入 `CommitRetryTimeout/RollbackRetryTimeout` 终态——给运维一个"止血"开关。

## 6.7 Raft 集群与 namingserver（2.x 重点）

**白话**：file 存储的单机 TC 挂了事务就丢了，db 存储又要自己运维一个数据库。2.0 起的 Raft 模式用 Seata 自研的 raft 状态机把**会话与锁的每一次变更**当一条日志复制到多数派，形成"多副本强一致 + 自动选主"的 TC 集群；2.1 的 namingserver 则补上"客户端怎么发现这个集群"的内置答案。

【源码证据】`server/src/main/java/org/apache/seata/server/cluster/raft/` 下：`RaftServer`（sofa-jraft 封装）、`RaftServerManager`（多 group 管理，`SessionHolder.init` 的 RAFT 分支 `:112-122` 启动）、`RaftStateMachine`（状态机回调：onApply 执行会话变更 + 刷快照）、`execute/`（global/branch/lock/vgroup 四类变更的 `RaftMsgExecutor`）、`sync/`（follower 日志回放消息）、`snapshot/`（快照序列化）。要点：

- 写路径：`RaftCoordinator` 不直接改会话，而是把 `global begin/branch register/...` 序列化成日志提交给 raft group，`RaftStateMachine.onApply` 在**所有节点**回放出相同的会话内存——TC 的"状态"变成了"多数派确认的事实"；
- 锁与读：读请求可在 follower 本地（`RaftSessionManager`），写请求必须到 leader；
- 客户端寻址：`discovery/seata-discovery-namingserver` + `common/metadata/namingserver`——namingserver 维护 vgroup → raft group 的映射与节点健康度，客户端定期拉取并跟随 leader 切换；
- console：2.4 起 console 迁入 namingserver 进程，提供全局事务查询/解决（删除）等运维 API。

## 6.8 本章小结

- TC 的骨架 = **Netty 接入（DefaultCoordinator）+ 状态机编排（DefaultCore）+ 会话存储（SessionHolder 四模式）+ 全局锁（LockManager 三实现）+ 六个定时任务**；
- "先 close 再提交/回滚"是 DefaultCore 的第一原则——分支注册的窗口期只有 `Begin` 状态，杜绝二阶段进行中还能挂新分支的竞态；
- AT 分支可异步提交（`canBeCommittedAsync`），TCC/XA/SAGA 必须同步驱动——模式差异在 TC 侧就体现为一个 if；
- 定时任务以 1s/24h 两种节奏推动状态机，`distributed_lock` 保证多节点不重复驱动；重试上限是可配置的运维止血阀；
- Raft 模式把"会话内存 + 全局锁"整体变成多数派复制的状态机，配合 namingserver 实现开箱即用的 TC 高可用。

---

# 七、通信层：Netty RPC（seata-core remoting）

## 7.1 总览

**白话**：客户端（TM/RM 共用一套客户端基建）与 server 共享 `AbstractNettyRemoting` 的收发框架，各自再装配 pipeline 与处理器表。Seata 自研协议而不是用 HTTP/gRPC，图的是：私有帧格式（无 HTTP 头开销）、合并发送（把一秒内的几十条请求合并成一条）、长连接复用（TM/RM 与 TC 之间通常只有几条 TCP 连接）。

## 7.2 消息帧布局

【源码证据】`core/src/main/java/org/apache/seata/core/protocol/ProtocolConstants.java:33/58/63`：魔数 `0xda 0xda`、单帧上限 8MB、V1 头 16 字节。编码器 `core/.../rpc/netty/v1/ProtocolEncoderV1.java:71-112`：

```java
int headLength = ProtocolConstants.V1_HEAD_LENGTH;      // 16
out.writeBytes(ProtocolConstants.MAGIC_CODE_BYTES);     // 0xda 0xda (2B)
out.writeByte(protocolVersion());                       // 版本 (1B)
out.writeByte(messageType);                             // 请求/响应/心跳/oneway (1B)
out.writeByte(rpcMessage.getCodec());                   // 序列化类型 (1B)
out.writeByte(rpcMessage.getCompressor());              // 压缩类型 (1B)
out.writeInt(rpcMessage.getId());                       // 消息 ID (4B) —— 请求响应关联键
out.writeInt(bodyLength);                               // 体长 (4B)
out.writeShort(headLength);                             // 头长 (2B)，16 + headMap 字节数
out.writeBytes(headMapBytes).writeBytes(bodyBytes);     // 头部扩展字段 + 消息体
```

解码器 `ProtocolDecoderV1` 反向解析后交 `processMessage`。除 V1 外 `rpc/netty/v0/` 保留老协议兼容，`rpc/netty/grpc/` 提供 gRPC 承载、`rpc/netty/http/` 提供控制台 HTTP 接口。

## 7.3 同步请求-响应：MessageFuture

【源码证据】`core/.../rpc/netty/AbstractNettyRemoting.java:185-244`，`sendSync`：

```java
MessageFuture messageFuture = new MessageFuture();
messageFuture.setRequestMessage(rpcMessage);
messageFuture.setTimeout(timeoutMillis);
futures.put(rpcMessage.getId(), messageFuture);        // ConcurrentHashMap<Integer, MessageFuture>
channel.writeAndFlush(rpcMessage)...
Object result = messageFuture.get(timeoutMillis, TimeUnit.MILLISECONDS);
```

响应回来按 `id` 取出 future 完成阻塞；一个定时清理任务周期扫描 `futures`，把超时的 future 移除并抛超时（`:118-130`）。超时默认值分角色：RM 15s、TM 30s（`DEFAULT_RPC_RM_REQUEST_TIMEOUT / DEFAULT_RPC_TM_REQUEST_TIMEOUT`，`DefaultValues.java:437/442`）。

## 7.4 客户端连接管理与重连

【源码证据】`core/.../rpc/netty/AbstractNettyRemotingClient.java:161-227`：

```java
public Object sendSyncRequest(Object msg) throws TimeoutException {
    String serverAddress = loadBalance(getTransactionServiceGroup(), msg);   // ① 按负载均衡选地址
    ...
    if (this.isEnableClientBatchSendRequest()) {
        // ② 合并发送：消息进 basketMap 队列，由 MergedSendRunnable 攒批发送
        ... basket.offer(rpcMessage); mergeCondition.signalAll(); ...
    } else {
        Channel channel = clientChannelManager.acquireChannel(serverAddress);  // ③ 直连
        return super.sendSync(channel, rpcMessage, timeoutMillis);
    }
}
```

- **地址来源**：注册中心 SPI（第十章 10.7）按 `transactionServiceGroup` 拿到 TC 地址列表，`loadBalance` 走可插拔负载均衡（X-LoadBalance）；
- **连接池**：`NettyClientChannelManager`（`:102` 起，`acquireChannel`）按 `NettyPoolKey{address, transactionRole}` 从 netty 连接池取/建连接；**建连即握手**：连接工厂里发送 `RegisterTMRequest/RegisterRMRequest`，拿到响应才算连接可用；
- **重连**：`NettyClientChannelManager.reconnect`（`:177-247`）遍历可用地址补建连接；连接事件（断开）由 `ChannelEventHandler` 监听触发；TM/RM 各有独立重连循环，保证 TC 重启后自动追上。

## 7.5 注册与握手：客户端在 TC 里的"户口"

【源码证据】server 侧 `core/.../rpc/netty/ChannelManager.java`：

- `registerTMChannel`（`:139-155`）：`RegisterTMRequest{applicationId, transactionServiceGroup, version}` → `RpcContext{TMROLE, version, clientId...}` 存入 `IDENTIFIED_CHANNELS` 与 `TM_CHANNELS`（按 applicationId+IP 二级索引）；
- `registerRMChannel`（`:164-195`）：`RegisterRMRequest{resourceIds...}` → `RM_CHANNELS` 三级索引：**resourceId → applicationId → clientIp → port → RpcContext**。这个结构就是 TC 二阶段"找人对账"的通讯录：回滚某个分支时，从 branch 的 resourceId + clientId 直接定位到当初注册它的那条连接（`AbstractNettyRemotingServer.sendSyncRequest(resourceId, clientId, msg, tryOtherApp)`，`:71`）；
- 版本协商：握手时校验客户端版本兼容性（`IncompatibleVersionException`）；
- 静默检测：`ChannelManager` 周期检查空闲连接，配合 IdleStateHandler 心跳（`HeartbeatMessage.PING`，客户端定时发）清死链。

## 7.6 消息合并：一秒几十条请求并成一条

【源码证据】`AbstractNettyRemotingClient.sendSyncRequest` 合并分支（`:168-196`）+ `MergedWarpMessage`（多条 `RpcMessage.body` 打包 + `msgIds` 索引）。TC 侧对应处理器拆包逐条处理再 `MergedResultMessage` 批量回；客户端 `childToParentMap`（`:246-248`）维护"子消息 ID → 合并包 ID"的映射，响应到达时能精准唤醒对应的 `MessageFuture`。开启条件 `enableClientBatchSendRequest`（RM 默认开、TM 可配），把高频小请求（分支注册/状态上报）的网络开销摊薄。

## 7.7 处理器表：processMessage 的分发机制

【源码证据】`AbstractNettyRemoting.processMessage`（`:301-353`）：按 body 的 `typeCode` 查 `processorTable`（`HashMap<Integer, Pair<RemotingProcessor, ExecutorService>>` `:109`），有业务线程池就丢池里异步执行，没有就当前线程直行；线程池打满时保护性地 jstack 落盘（`:322-338`）。各端注册清单：

- **TM 客户端**（`tm/.../TmNettyRemotingClient.java:279-287`）：`ClientOnResponseProcessor` 注册给所有 `_RESULT` 类型——TM 只发请求收响应；
- **RM 客户端**（`rm/.../RmNettyRemotingClient.java:430+`）：`RmBranchCommitProcessor`（TYPE_BRANCH_COMMIT=3）、`RmRollbackProcessor`（TYPE_BRANCH_ROLLBACK=5）、`RmUndoLogProcessor`（undo_log 删除）+ `ClientOnResponseProcessor`——RM 既要收 TC 指令，也要收自己请求的响应；
- **TC 服务端**（`RegisterMsgListener`，`core/.../rpc/netty/RegisterMsgListener.java`）：TYPE_REG_CLT/TYPE_REG_RM → ChannelManager 注册；TYPE_GLOBAL_* → DefaultCoordinator；TYPE_BRANCH_REGISTER/REPORT → DefaultCore。

## 7.8 本章小结

- 帧格式 16 字节定长头 + 魔数 `0xdada` + 消息 ID，承载了多序列化/多压缩/请求关联三件事；
- 同步调用的本质是 `futures[id]` + `MessageFuture.get(timeout)`；RM 15s、TM 30s；
- 客户端连接 = 地址列表（注册中心）+ 负载均衡 + 连接池 + 握手注册（RegisterTM/RM）；TC 的 `RM_CHANNELS` 三级索引保证二阶段消息能"找到当初注册的那台机器"；
- 合并发送（MergedWarpMessage）+ 处理器表（processorTable）是吞吐量的两根支柱。

---

# 八、TCC 模式（seata-tcc + integration-tx-api + seata-spring）

## 8.1 模式定位

**白话**：AT 依赖数据库可解析 SQL（要拍镜像）；如果资源是"非库"（发消息、调第三方 API、Redis），或者 SQL 复杂到无法生成反向语句，就得用 TCC——**业务自己写三个方法**：Try（预留资源）、Confirm（确认，用 Try 预留的资源完成）、Cancel（取消预留）。Seata 负责的只剩：把这三个方法和全局事务挂钩、保证二阶段一定会被调用（哪怕服务宕机重启后补调）。

## 8.2 注解与代理：从 @TwoPhaseBusinessAction 到动态代理

【源码证据】注解定义 `tcc/src/main/java/org/apache/seata/rm/tcc/api/TwoPhaseBusinessAction.java:47-82`：

```java
public @interface TwoPhaseBusinessAction {
    String name();                              // TCC 资源名（= TC 里的 resourceId）
    String commitMethod() default "commit";     // Confirm 方法名
    String rollbackMethod() default "rollback"; // Cancel 方法名
    boolean useTCCFence() default false;        // 是否启用 fence 防悬挂（8.4 节）
}
```

配合接口级注解 `@LocalTCC`（本地调用场景）或远程 RPC 场景的 `RemotingParser`（dubbo/sofa/http 识别），`GlobalTransactionScanner.wrapIfNecessary` 把实现了该接口的 Bean 包上 `TccActionInterceptorHandler`（`GlobalTransactionScanner.java:299-304` 的注释即导航图；代理生成走 `TccActionInterceptorParser` + `ProxyUtil` 动态代理）。

**一阶段 prepare**：业务方法（Try）被调用时，`TccActionInterceptorHandler.doInvoke`（`tcc/.../interceptor/TccActionInterceptorHandler.java:86-88`）委托 `ActionInterceptorHandler.proceed`（`integration-tx-api/.../interceptor/ActionInterceptorHandler.java:95-115`）：

```java
BusinessActionContext actionContext = ...;      // 装配上下文
actionContext.setXid(xid);
actionContext.setActionName(actionName);
...
String branchId = doTxActionLogStore(method, arguments, businessActionParam, actionContext);
// ↑ 内部 DefaultResourceManager.branchRegister(BranchType.TCC, actionName, ...) —— 注册 TCC 分支
actionContext.setBranchId(branchId);
actionContext.enableActionContextTracking();    // 2.x：记录上下文是否被业务修改
BusinessActionContextUtil.setContext(actionContext);
// 然后 method.invoke(targetBean, args) —— 执行 Try
```

`BusinessActionContext`（`tcc/.../api/BusinessActionContext.java`）就是 Try 阶段塞进 TC `applicationData` 的"小背包"：Try 里放的业务参数（`@BusinessActionContextParameter` 标注），Confirm/Cancel 时原样发回来。

## 8.3 二阶段：反射调用 commit/rollback

【源码证据】TC 下发 `BranchCommitRequest/BranchRollbackRequest` → `RMHandlerTCC`（`tcc/.../RMHandlerTCC.java`）→ `TCCResourceManager.branchCommit`（`tcc/.../TCCResourceManager.java:109-153`）：

```java
TCCResource tccResource = getTCCResource(resourceId);           // 启动时注册的 方法元数据
BusinessActionContext businessActionContext =
        BusinessActionContextUtil.getBusinessActionContext(xid, branchId, resourceId, applicationData);
Object[] args = this.getTwoPhaseCommitArgs(tccResource, businessActionContext);
...
if (Boolean.TRUE.equals(businessActionContext.getActionContext(Constants.USE_COMMON_FENCE))) {
    result = DefaultCommonFenceHandler.get().commitFence(commitMethod, targetTCCBean, xid, branchId, args);
} else {
    ret = commitMethod.invoke(targetTCCBean, args);             // ★ 反射调 Confirm
    if (ret instanceof TwoPhaseResult) { result = ((TwoPhaseResult) ret).isSuccess(); }
}
return result ? BranchStatus.PhaseTwo_Committed : BranchStatus.PhaseTwo_CommitFailed_Retryable;
```

Confirm/Cancel 返回 `TwoPhaseResult`（`integration-tx-api/.../remoting/TwoPhaseResult.java`）可主动声明成功/失败；抛异常则返回 Retryable 状态，由 TC 重试（6.6 节）。`branchRollback`（`:186-230`）同构调 Cancel。

## 8.4 fence：防空回滚、幂等、悬挂（本章核心）

**白话**：TCC 三个经典坑，fence 用一张日志表解决：

1. **空回滚**：Try 没执行（超时/网络），TC 却发起了 Cancel——Cancel 发现"没有 Try 过"，要能安全拒绝；
2. **幂等**：Confirm/Cancel 因重试被执行多次——效果必须等于一次；
3. **悬挂**：Cancel 先执行完（空回滚后），迟到的 Try 才到——Try 要能发现"事务已回滚"，拒绝执行（否则资源被永久预留）。

【源码证据】`spring/seata-spring/src/main/java/org/apache/seata/rm/fence/SpringFenceHandler.java`（2.2+ 的通用 fence 实现，表 `common_fence`）：

**prepareFence（Try 前置）** `:117-149`：

```java
boolean result = insertCommonFenceLog(conn, xid, branchId, actionName, CommonFenceConstant.STATUS_TRIED);
if (result) {
    return targetCallback.execute();     // 插入成功（说明是首次 Try）才执行业务
} else {
    throw new CommonFenceException(...InsertRecordError);      // 唯一键冲突
}
```

捕获 `DuplicateKeyException`（`:135-141`）时的日志说明了一切：`"Branch transaction has already rollbacked before, prepare fence failed"`——**防悬挂**：TC 已先发过 Cancel（空回滚插入了一条 ROLLBACKED/SUSPENDED 记录），迟到的 Try 因唯一键 `(xid, branch_id)` 冲突被拒。

**commitFence（Confirm 前置）** `:162-186`：

```java
CommonFenceDO commonFenceDO = COMMON_FENCE_DAO.queryCommonFenceDO(conn, xid, branchId);
if (commonFenceDO == null) { throw ... RecordNotExists; }                  // 没 Try 过 → 异常
if (CommonFenceConstant.STATUS_COMMITTED == commonFenceDO.getStatus()) {
    return true;        // 已提交过 → 幂等拒绝，直接返回成功
}
if (STATUS_ROLLBACKED == status || STATUS_SUSPENDED == status) { throw ...; }  // 状态非法
```

**rollbackFence（Cancel 前置）**：记录不存在（Try 确实没发生）→ **插入一条 `STATUS_SUSPENDED` 记录**（`:236`）并返回成功——这就是**空回滚**的"合法化"：既没有真回滚什么，又给迟到的 Try 留了拦截桩。

记录清理：`FenceLogCleanRunnable` + 单线程清理 executor（`:71-84`，队列 500、单批 1000），按全局事务结束时间定期删除终态记录（2.7.0 的 #8138 修复了误删同事务未完成记录的问题）。启用方式：`useTCCFence = true`（2.x 泛化为 `CommonFenceStore`，业务库需建 `common_fence` 表，脚本在 `script/client/`）。

## 8.5 本章小结

- TCC 的框架价值 = **分支注册（prepare 前挂上全局事务）+ 二阶段反射调用（宕机后 TC 会补调）+ fence 三坑防护**；Try/Confirm/Cancel 的正确性仍要业务自己保证；
- `BusinessActionContext` 是 Try → Confirm/Cancel 的参数通道（塞进 TC 的 applicationData）；
- fence 本质是一张"TCC 分支生命周期日志"：TRIED/SUSPENDED/COMMITTED/ROLLBACKED 四状态 + 唯一键，用"先插桩后执行"的顺序把三个竞态全堵住；
- TCC 分支在 TC 侧不可异步提交（`canBeCommittedAsync` 只认 AT），Confirm 必须同步驱动完成。

---

# 九、XA 与 SAGA 模式（概览）

## 9.1 XA 模式：把两阶段交还给数据库

**白话**：XA 模式的 RM 不改写 SQL、不拍镜像——它把 Seata 的全局事务 ID 翻译成数据库 XA 协议的 Xid，让数据库自己做一阶段 prepare、二阶段 commit/rollback。**一致性强（prepare 后数据锁定）、但锁持有时间 = 整个全局事务**，与 AT 的取舍正好相反。

【源码证据】`rm-datasource/src/main/java/org/apache/seata/rm/datasource/xa/ConnectionProxyXA.java`，分支的开启藏在 `setAutoCommit(false)` 里（`:194-218`）：

```java
// 1. register branch to TC then get the branch message
branchRegisterTime = System.currentTimeMillis();
branchId = DefaultResourceManager.get().branchRegister(BranchType.XA, resource.getResourceId(), null, xid, null, null);
// 2. build XA-Xid with xid and branchId
this.xaBranchXid = XAXidBuilder.build(xid, branchId);
// 3. XA Start
xaResource.start(this.xaBranchXid, XAResource.TMNOFLAGS);   // Oracle 用 ORATRANSLOOSE（:283）
this.xaActive = true;
```

- **一阶段**：业务 SQL 全部执行后 `commit()`（`:229-242`）——客户端只做 `xaEnd(TMSUCCESS)` 并上报分支状态，**不调 xaCommit**；数据在数据库里处于 prepared 态，行锁仍持有；
- **二阶段**：TC 下发 BranchCommit/Rollback → `ResourceManagerXA.finishBranch`（`xa/ResourceManagerXA.java:137-160`）：

```java
try (ConnectionProxyXA connectionProxyXA =
        ((AbstractDataSourceProxyXA) resource).getConnectionForXAFinish(xaBranchXid)) {
    if (committed) {
        connectionProxyXA.xaCommit(xid, branchId, applicationData);
        return BranchStatus.PhaseTwo_Committed;
    } else {
        connectionProxyXA.xaRollback(xid, branchId, applicationData);
        return BranchStatus.PhaseTwo_Rollbacked;
    }
}
```

- **悬挂清理**：prepare 过却迟迟没有二阶段的连接会被"挂起保管"（`keepIfNecessary` `:209`），`ConnectionProxyXA` 内部有基于超时的清理检查（`:317`，`now - branchRegisterTime > TIMEOUT`）——XAER_NOTA 类状态（BranchStatus 11/12）配合 TC 侧 `isXaerNotaTimeout`（`DefaultCore.java:322-328/448-454`）处理"二阶段找不到事务"的情况。

使用上：把数据源换成 `DataSourceProxyXA`（或 starter 自动代理 + `seata.data-source-proxy-mode.branch-type=XA`），业务代码零改动。

## 9.2 SAGA 模式：状态机驱动的长事务补偿

**白话**：SAGA 面向**长流程**（几十个服务、几分钟到几天的编排）：用 JSON 状态机描述"服务调用图 + 每个服务的补偿服务"，正向全跑完则事务完成；中途失败则**逆序重放补偿**。强项是跨企业长流程；代价是业务要为每个动作写补偿、且不提供隔离（中间状态可见）。

模块构成（`saga/` 下 8 个子模块）：

| 模块 | 职责 |
|---|---|
| seata-saga-engine | 状态机引擎（`ProcessCtrlStateMachineEngine`） |
| seata-saga-statelang | 状态机 DSL 模型（State/TaskState/ChoiceState/CompensateSubProcessState…） |
| seata-saga-engine-store | 状态机执行日志持久化（`StateLogStore`：state_inst表 等） |
| seata-saga-processctrl | 流程控制器（ProcessRouter/ProcessHandler/StateInstruction） |
| seata-saga-rm | 与 TC 交互的 SagaResourceManager（全局事务/分支代理） |
| seata-saga-annotation | 2.2+ 注解模式（`@LocalCompensation` 等，`SagaAnnotationResourceManager`） |
| seata-saga-spring / designer | Spring 装配 / 可视化设计器 |

【源码证据】引擎入口 `saga/seata-saga-engine/.../impl/ProcessCtrlStateMachineEngine.java:90-109`：

```java
public StateMachineInstance startWithBusinessKey(String stateMachineName, String tenantId,
        String businessKey, Map<String, Object> startParams) throws EngineExecutionException {
    return startInternal(stateMachineName, tenantId, businessKey, startParams, false, null);
}
```

`startInternal` 创建 `StateMachineInstance` → 交给 process-controller 按 JSON 状态图逐状态执行（ServiceTask 调 Spring Bean/服务，每个状态可配 `CompensateState`）；手动补偿入口 `compensate(stateMachineInstId, ...)`（`:495-497`）。与 TC 的关系：SAGA 全局事务 begin 后，每个状态调用注册为一个 `BranchType.SAGA` 的分支；二阶段回滚 = 引擎从执行日志（`StateLogStore`）恢复实例、逆序触发补偿状态——**补偿的可靠性靠"状态机执行日志"而非 undo_log**。TC 侧 `DefaultCore` 对 SAGA 有特殊通道：`globalReport`（`:524-537`）由 Saga 引擎汇报最终状态。

## 9.3 本章小结

- **XA**：一阶段 prepare（数据库锁定）、二阶段由 TC 驱动 `xaCommit/xaRollback`；Xid = Seata XID + branchId 拼装（`XAXidBuilder`）；强一致、低吞吐、不依赖 SQL 解析；
- **SAGA**：JSON 状态机 + 执行日志 + 逆序补偿，面向长流程与遗留系统；两种用法（状态机编排/注解模式）；
- 四模式对照一句话：**AT 靠镜像、TCC 靠业务三方法、XA 靠数据库、SAGA 靠补偿编排**——TC 的协议与状态机完全复用，差异全部封装在"一阶段做什么/二阶段怎么收尾"里。

---

# 十、Spring 集成与配置体系

## 10.1 SeataAutoConfiguration：starter 装配了什么

【源码证据】`spring/seata-spring-boot-starter/src/main/java/org/apache/seata/spring/boot/autoconfigure/SeataAutoConfiguration.java:45-90`：

```java
@ConditionalOnProperty(prefix = "seata", name = "enabled", havingValue = "true", matchIfMissing = true)
@AutoConfigureAfter({SeataCoreAutoConfiguration.class})
public class SeataAutoConfiguration {
    @Bean(BEAN_NAME_FAILURE_HANDLER)
    @ConditionalOnMissingBean(FailureHandler.class)
    public FailureHandler failureHandler() { return new DefaultFailureHandlerImpl(); }

    @Bean @DependsOn(...)
    @ConditionalOnMissingBean(GlobalTransactionScanner.class)
    public static GlobalTransactionScanner globalTransactionScanner(
            SeataProperties seataProperties, FailureHandler failureHandler, ...) {
        GlobalTransactionScanner.setBeanFactory(beanFactory);
        GlobalTransactionScanner.addScannerCheckers(EnhancedServiceLoader.loadAll(ScannerChecker.class));
        GlobalTransactionScanner.addScannablePackages(seataProperties.getScanPackages());
        GlobalTransactionScanner.addScannerExcludeBeanNames(seataProperties.getExcludesForScanning());
        ...
        return new GlobalTransactionScanner(
                seataProperties.getApplicationId(), seataProperties.getTxServiceGroup(),
                seataProperties.isExposeProxy(), failureHandler);
    }
}
```

`seata.*` 配置（`SeataProperties`）：`application-id`、`tx-service-group`（默认 `default_tx_group`，老名 `my_test_tx_group` 已弃用——`GlobalTransactionScanner.initClient:240-247` 专门打警告）、`excludes-for-scanning`、`enable-auto-data-source-proxy`、`data-source-proxy-mode`（AT/XA）、`degrade-check-*`（10.4 节）等。

## 10.2 GlobalTransactionScanner：一个类三个身份

**白话**：它既是 Spring 的自动代理创建器（扫 Bean、织拦截器），又是客户端网络初始化器（TM/RM 连接就在这里拉起），还内置各种"要不要代理"的检查器。

【源码证据】`spring/seata-spring/src/main/java/org/apache/seata/spring/annotation/GlobalTransactionScanner.java:87`（`extends AbstractAutoProxyCreator`，Spring 的 BeanPostProcessor 体系）。

**网络初始化** `initClient`（`:236-273`）：

```java
TMClient.init(applicationId, txServiceGroup, accessKey, secretKey);   // TmNettyRemotingClient
RMClient.init(applicationId, txServiceGroup);                          // RmNettyRemotingClient
registerSpringShutdownHook();   // ShutdownHook 绑定到 Spring 生命周期（:275-283）
```

`RMClient.init`（`rm/.../RMClient.java:33-39`）的三行就是客户端 RM 的全部装配：

```java
RmNettyRemotingClient rmNettyRemotingClient = RmNettyRemotingClient.getInstance(applicationId, transactionServiceGroup);
rmNettyRemotingClient.setResourceManager(DefaultResourceManager.get());   // 资源路由表
rmNettyRemotingClient.setTransactionMessageHandler(DefaultRMHandler.get()); // TC 下行消息入口
rmNettyRemotingClient.init();
```

调用时机有两处：`afterPropertiesSet`（`:525-539`，`initialized.compareAndSet` 保证只初始化一次）与配置监听 `onChangeEvent`（`:616-632`——若启动时 `seata.enabled=false`，之后把该配置改为 true 也能动态拉起客户端）。**先有网络，再代理 Bean**，保证第一个事务方法执行时连接已就绪。

**Bean 扫描** `wrapIfNecessary`（`:307-353`）：

```java
protected Object wrapIfNecessary(Object bean, String beanName, Object cacheKey) {
    if (!doCheckers(bean, beanName)) { return bean; }         // ScannerChecker 检查（包/配置/Scope）
    synchronized (PROXYED_SET) {
        if (PROXYED_SET.contains(beanName)) { return bean; }
        if (!NEED_ENHANCE_BEAN_NAME_SET.contains(beanName)) { return bean; }
        // 判定依据：预扫描阶段已确认该 Bean 带 @GlobalTransactional/@GlobalLock/@TwoPhaseBusinessAction
        ProxyInvocationHandler proxyInvocationHandler =
                DefaultInterfaceParser.get().parserInterfaceToProxy(bean, beanName);
        if (proxyInvocationHandler == null) { return bean; }
        interceptor = new AdapterSpringSeataInterceptor(proxyInvocationHandler);
        if (!AopUtils.isAopProxy(bean)) {
            bean = super.wrapIfNecessary(bean, beanName, cacheKey);      // 普通代理
        } else {
            // 已是 AOP 代理（如 @Transactional 同 Bean）：把 Seata Advisor 按 order 插进现有代理链
            AdvisedSupport advised = SpringProxyUtils.getAdvisedSupport(bean);
            Advisor[] advisor = buildAdvisors(beanName, getAdvicesAndAdvisorsForBean(null, null, null));
            for (Advisor avr : advisor) { advised.addAdvisor(findAddSeataAdvisorPosition(advised, avr), avr); }
        }
        PROXYED_SET.add(beanName);
        return bean;
    }
}
```

## 10.3 拦截器体系：2.x 的 integration-tx-api（重构点）

**白话**：2.1 起拦截器被抽成与 Spring 解耦的 SPI：`ProxyInvocationHandler`（处理逻辑）+ `InterfaceParser`（判定要不要代理）。好处是同一套拦截器能同时服务 Spring AOP、Dubbo 代理、gRPC 拦截器等多种织入方式。

【源码证据】`integration-tx-api/src/main/java/org/apache/seata/integration/tx/api/interceptor/handler/GlobalTransactionalInterceptorHandler.java`：

- `doInvoke`（`:148-171`）：降级开关判断（`:152`）→ 有 `@GlobalTransactional`（或 `AspectTransactional`）走 `handleGlobalTransaction`，有 `@GlobalLock` 走 `handleGlobalLock`，否则裸跑；
- `handleGlobalTransaction`（`:188-283`）：构造匿名 `TransactionalExecutor`（业务回调 + `TransactionInfo` 组装：超时 `:209-212`（注解 0 或默认值时取配置 `defaultGlobalTransactionTimeout`，60000ms）、传播、锁重试、`RollbackRule` 列表 `:221-234`）→ `transactionalTemplate.execute(...)` → 异常翻译（`:238-277`，Participant 直接还原原始异常 `:242-244`；各失败码触发 `failureHandler` 回调）；
- **降级检查**（`:84-137`）：开启 `seata.tm.degrade-check=true` 后，客户端用 Guava EventBus（`:95`）统计 TC 可用性——连续失败/恢复超阈值（`degradeCheckAllowTimes`，默认 10，间隔 `degradeCheckPeriod` 2000ms，`:127-137`）自动把 `ATOMIC_DEGRADE_CHECK` 置位，此后**所有 `@GlobalTransactional` 方法直通执行**（TC 挂了不再拖死业务），并可配置监听动态恢复。这是生产上最被低估的开关之一。

## 10.4 数据源自动代理

【源码证据】`spring/seata-spring/.../annotation/datasource/`：

- `AutoDataSourceProxyRegistrar`：处理 `@EnableAutoDataSourceProxy`（注解式开启）；
- `SeataAutoDataSourceProxyCreator`（`:35`，`extends AbstractAutoProxyCreator`）：对 DataSource 类型的 Bean 织入 `SeataAutoDataSourceProxyAdvice`；
- `SeataAutoDataSourceProxyAdvice.invoke`（`:43-63`）：只有 `DataSource` 接口声明的方法才拦截——`getConnection()` 返回被代理连接、其余透传：

```java
DataSource origin = (DataSource) invocation.getThis();
SeataDataSourceProxy proxy = DataSourceProxyHolder.get(origin);   // 缓存：原始DS → DataSourceProxy
return proxy.getConnection() 或 method.invoke(origin, args);
```

- `DataSourceProxyHolder`（`:29`）：`ComputeIfAbsent` 式缓存，保证同一个数据源只包一层代理；MyBatis-Plus/Druid 等"要原始数据源"的场景用 `DataSourceProxyHolder.get(origin)` 反查或 `@SeataProxy` 排除。

## 10.5 配置中心与注册中心 SPI

【源码证据】配置侧 `config/seata-config-core/.../ConfigurationFactory.java:60-100`：

```java
static {
    initOriginConfiguration();      // 读 registry.conf（文件名可由 -Dseata.config.name 覆盖，按 env 分文件）
    load();                         // ExtConfigurationProvider SPI —— SpringBoot 配置接入点
    maybeNeedOriginFileInstance();
}
```

- 优先级：**Spring Boot `application.yml`（`SpringBootConfigurationProvider`，通过 ExtConfigurationProvider SPI 注入） > registry.conf > 默认值**；
- `ConfigType`（`ConfigType.java:23-55`）：File/ZK/Nacos/Apollo/Consul/Etcd3/SpringCloudConfig/Custom——每类一个 `ConfigurationProvider` SPI 实现（`config/seata-config-*` 各模块）；
- 配置监听：`Configuration.addConfigListener`——10.3 节的降级开关、事务超时等都是热生效的。

注册侧 `discovery/seata-discovery-core/.../RegistryFactory.java:40-58`：按 `registry.type` 配置项经 `RegistryProvider` SPI 实例化；`RegistryType`（`RegistryType.java:25-69`）：File/Raft/ZK/Redis/Nacos/Eureka/Consul/Etcd3/Sofa/Custom/**Seata(namingserver)**。TM/RM 客户端启动时用它把 `tx-service-group` 解析成 TC 地址列表（再交给 7.4 节的连接管理）。

## 10.6 XID 跨服务传播

**白话**：RootContext 里的 XID 不会自动飞到下一个服务。Seata 的做法：每个 RPC 框架一个"过滤器"，出向把 XID 塞进请求头，入向把它 bind 回 ThreadLocal，同时把本服务的角色标成 Participant。

【源码证据】扩展模块在 `extensions/rpc/`（dubbo、sofa-remoting、grpc、http、motan 等，每者一个 SPI 目录）；以 HTTP 为例（`extensions/rpc/seata-http/`），客户端过滤器发请求前 `request.addHeader(RootContext.KEY_XID, RootContext.getXID())`，服务端过滤器 `RootContext.bind(header)`。`integration-tx-api/.../remoting/parser/` 的 `DubboRemotingParser/HSFRemotingParser/SofaRpcRemotingParser`（`:6-22`）则负责在 TCC 场景识别"这是远程 TCC 参考还是本地实现"。Spring Cloud Alibaba 的 `seata-spring-cloud-starter` 在本仓库之外，用 Feign/RestTemplate/Gateway 拦截器做同样的事。

## 10.7 本章小结

- Spring 集成的全部秘密 = **一个 BeanPostProcessor（GlobalTransactionScanner）+ 一个数据源代理 Creator + 一张 starter 配置表**；客户端网络在扫描期初始化，ShutdownHook 挂到 Spring 容器；
- 2.x 拦截器 SPI（integration-tx-api）让同一套事务逻辑可跨 RPC 框架织入；`degrade-check` 是 TC 故障时的自动降级保险丝；
- 配置优先级 `application.yml > registry.conf > 默认`，配置中心与注册中心都是 SPI（各 8/11 种实现，namingserver 是 2.1+ 的官方内置答案）；
- XID 传播靠 RPC 过滤器把 RootContext 的值"搬"过网络——这是理解分布式事务链路的最短一环。

---

# 十一、贯通视图：一次下单的三条时间线

## 11.1 时间线一：AT 正常提交（跨 order/account 两个服务）

```
┌────────── order-service（TM+RM）─────────────┐      ┌──────── TC ────────┐   ┌── account-service（RM）──┐
│ @GlobalTransactional createOrder()           │      │                    │   │                          │
│  ① GlobalTransactionalInterceptor 拦截       │      │                    │   │                          │
│  ② TransactionalTemplate.execute             │      │                    │   │                          │
│     GlobalBeginRequest ───────────────────────────▶  core.begin()         │   │                          │
│     ▶ xid=ip:8091:1001, RootContext.bind     │      │ GlobalSession(Begin)│  │                          │
│  ③ 本地事务: insert t_order                  │      │                    │   │                          │
│     拍镜像→lockKey→undo_log(内存)             │      │                    │   │                          │
│     commit: branchRegister(AT, lockKeys) ────────▶  ATCore: 抢全局锁      │   │                          │
│     写undo_log → 本地commit → 返回           │      │ branch_table/lock_table│  │                         │
│  ④ Feign 调 account-service（XID 在 header）─────────────────────────────────────▶ RootContext.bind(xid) │
│                                              │      │                    │   │ ⑤ 扣减余额（同③的一阶段）│
│                                              │      │   BranchRegister ──▶RM│ branch 1002 注册+抢锁    │
│  ⑥ 事务边界结束 → GlobalCommitRequest ───────────▶  close→canBeCommittedAsync│   │                          │
│     ◀── GlobalStatus.Committed（立即返回）    │      │ → AsyncCommitting   │   │                          │
│  ⑦ 用户视角：方法已返回 ✔                    │      │ asyncCommitting任务 │   │                          │
│                                              │      │  ↓ 1s 内            │   │                          │
│                                              │      │ BranchCommit ─────────▶  AsyncWorker 入队        │
│                                              │      │ BranchCommit ───────▶RM │ ↑ 1s 内                │
│                                              │      │ 全部分支删除完毕     │   │ 批删 undo_log           │
│                                              │      │ → endCommitted(Finished)│  │                        │
└──────────────────────────────────────────────┘      └────────────────────┘   └──────────────────────────┘
```

要点复述：**用户等待的时间里只发生了一阶段**（TM begin + 各分支本地提交 + TM commit 报告）；undo_log 的清理在用户返回之后异步进行（AsyncCommitting + AsyncWorker，5.2 节）。

## 11.2 时间线二：AT 异常回滚（account 扣款失败）

```
order-service                          TC                                    account-service
────────────────────────────────────────────────────────────────────────────────────────────
insert t_order ✔（分支1001已提交）
调用 account → 扣款 SQL 抛 RuntimeException
account: 本地回滚 + branchReport(PhaseOne_Failed) ──▶ 1002 分支标记失败
异常随 Feign 返回 order
TransactionalTemplate 捕获 → rollbackOn=true
GlobalRollbackRequest ──────────────────────────▶  close()（关闸）
                                                  status → Rollbacking
                                                  doGlobalRollback:
                                                    分支1002(PhaseOne_Failed) → 直接 removeBranch
                                                    分支1001: BranchRollbackRequest ──▶ RMHandlerAT
                                                                                              │
                                                  queueToRetryRollback ◀── 若返回 Retryable     │
                                                  retryRollbacking 任务(1s) 重试                 ▼
                                                                             undo(): 读 undo_log
                                                                             前后镜像比对（脏写?）
                                                                             反向SQL: DELETE FROM t_order ...
                                                                             删 undo_log → PhaseTwo_Rollbacked ◀─┘
                                                  endRollbacked → Rollbacked（终态）
order 抛出原始异常给调用方（方法本来就要失败的）
```

要点复述：**失败方的分支根本不需要回滚**（一阶段本地已回滚）；回滚的是"成功过但全局失败"的分支；回滚失败（对端宕机/锁未释放）由 1s 重试任务兜底，直到成功或触发重试上限。

## 11.3 时间线三：全局锁互斥（两个订单同时改同一行库存）

```
T1(order-1)                             TC(lock_table)                        T2(order-2)
────────────────────────────────────────────────────────────────────────────────────────────
update stock set qty=qty-1 where id=7
branchRegister(lockKeys="stock:7") ──▶ FileLocker.putIfAbsent(pk=7) ✔ 持有
本地 commit ✔（业务很快）
                                          ◀── branchRegister(lockKeys="stock:7") ── T2 也来改 7
                                          putIfAbsent 冲突 → LockKeyConflict
                                          ◀── T2 客户端: LockConflictException
                                          T2: 本地事务回滚（含已拍的镜像）
                                          T2: LockRetryController.sleep（默认 10ms×30 次退避）
                                          ... T1 全局事务 2 阶段完成 → 锁释放
                                          ◀── T2 重试 branchRegister ✔
                                          T2 本地事务重新执行 → commit ✔
```

要点复述：全局锁在**一阶段 commit 时**抢、在**分支二阶段收尾时**释放——持锁时间 ≈ T1 的业务方法耗时，而非整个"下单到支付"的人类时间；T2 的重试发生在数据库本地事务之外（先回滚再重试），不会占着行锁干等（`executeAutoCommitTrue` 的 LockRetryPolicy，4.4 节）。

## 11.4 从源码中提炼的四个设计模式视角

1. **模板方法**：`TransactionalTemplate.execute`（TM）与 `AbstractDMLBaseExecutor.executeAutoCommitFalse`（RM）两个模板定义了各自侧的不变骨架；
2. **代理与拦截器链**：DataSource 四层代理（客户端）+ Spring Advisor 织入（集成层），业务零侵入的全部载体；
3. **策略 + SPI**：BranchType 分派（客户端 ResourceManager / 服务端 AbstractCore）、undo 序列化器、配置/注册中心、序列化器/压缩器、负载均衡——Seata 几乎每个"可替换点"都是 `EnhancedServiceLoader` SPI；
4. **状态机 + 轮询驱动**：TC 的 GlobalStatus 状态转移不靠事件回调，靠 1s 级定时任务扫描 + `Retryable/Unretryable` 状态值协商——简单、可靠、易恢复（重启 reload 即续跑）。

---

# 十二、附录

## 12.1 关键类速查表（按角色分组）

| 角色 | 类 | 文件（模块内路径自 `src/main/java/org/apache/seata/` 起） | 职责一句话 |
|---|---|---|---|
| TM | DefaultGlobalTransaction | `tm/api/` | begin/commit/rollback + 角色判断 + 重试 |
| TM | TransactionalTemplate | `tm/api/` | 六步模板：传播/挂起/提交/回滚/钩子 |
| TM | DefaultTransactionManager | `tm/` | TransactionManager 接口的 RPC 实现 |
| AT-RM | DataSourceProxy | `rm/datasource/` | 资源身份（jdbcUrl）+ 建连包装 |
| AT-RM | ConnectionProxy | `rm/datasource/` | commit 三分叉 + 注册分支 + flush undo_log |
| AT-RM | AbstractDMLBaseExecutor | `rm/datasource/exec/` | autoCommit 伪事务 + 前后镜像模板 |
| AT-RM | SelectForUpdateExecutor | `rm/datasource/exec/` | select for update 抢全局锁 |
| AT-RM | AbstractUndoLogManager | `rm/datasource/undo/` | flushUndoLogs / undo / 防御日志 |
| AT-RM | AsyncWorker | `rm/datasource/` | 二阶段提交：队列 + 1s 批删 undo_log |
| AT-RM | LockRetryController | `rm/datasource/exec/` | 全局锁冲突重试节奏（默认 10ms×30） |
| RM 公共 | DefaultResourceManager / AbstractRMHandler | `rm/` | 分支 RPC 出口 / TC 下行入口 |
| TC | DefaultCoordinator | `server/coordinator/` | 请求入口 + 六定时任务 |
| TC | DefaultCore / AbstractCore / ATCore | `server/coordinator/`、`server/transaction/at/` | 全局两阶段编排 / 分支注册与锁 |
| TC | GlobalSession / BranchSession / SessionHolder | `server/session/` | 会话模型 + 四种存储 + 重启恢复 |
| TC | FileLocker / DataBaseLocker / RedisLocker | `server/storage/*/lock/` | 全局锁三实现 |
| TC | RaftServer / RaftStateMachine | `server/cluster/raft/` | Raft 集群 |
| 协议 | MessageType / RpcMessage / ProtocolEncoderV1 | `core/protocol/`、`core/rpc/netty/v1/` | 消息类型 / 载体 / 帧编码 |
| 通信 | AbstractNettyRemoting(Client) / NettyClientChannelManager / ChannelManager | `core/rpc/netty/` | 收发框架 / 客户端连接 / 服务端户口 |
| TCC | TwoPhaseBusinessAction / TccActionInterceptorHandler / TCCResourceManager | `tcc/` | 注解 / prepare / 二阶段反射 |
| TCC | SpringFenceHandler | `spring/`（rm/fence） | 防空回滚/幂等/悬挂 |
| XA | ConnectionProxyXA / ResourceManagerXA | `rm/datasource/xa/` | xa start/prepare / 二阶段 xa commit |
| SAGA | ProcessCtrlStateMachineEngine / StateLogStore | `saga/…engine/`、`…engine-store/` | 状态机引擎 / 执行日志 |
| Spring | GlobalTransactionScanner / GlobalTransactionalInterceptorHandler | `spring/`、`integration-tx-api/` | 扫描织入 / 拦截 + 降级 |
| 配置 | ConfigurationFactory / RegistryFactory | `config/`、`discovery/` | 双 SPI 入口 |

## 12.2 模块-职责速查

| 模块 | 大小感 | 一句话 |
|---|---|---|
| common | 小 | XID、配置键、状态枚举、SPI 工具——"词汇表" |
| core | 中 | 协议 + Netty remoting + model——"通信与词汇" |
| tm | 最小 | 全局事务边界模板 |
| rm / rm-datasource | 最大 | AT 全部秘密：代理、镜像、锁、undo_log、AsyncWorker、XA |
| tcc | 小 | TCC 注解 + 二阶段反射 + fence 基建 |
| saga | 大（8 子模块） | 状态机引擎与补偿 |
| integration-tx-api | 中 | 2.x 拦截器 SPI + 通用 fence |
| spring（3 子模块） | 中 | Scanner / 数据源代理 / Boot 自动配置 |
| server | 大 | TC：协调器、会话、锁、raft、console |
| config / discovery / sqlparser | 中 | 三大 SPI 家族 |
| namingserver / console | 中 | 2.1+ 内置注册中心与运维台 |

## 12.3 初学者学习路线（动手向）

1. **跑通 AT 最小闭环**：MySQL 起两个业务库 + 一个 seata-server（file 模式）→ 业务库建 `undo_log`、server 库建三张表（`script/server/db/mysql.sql`、`script/client/at/db/mysql.sql`）→ 两个 Spring Boot 服务 + `seata-spring-boot-starter`，一个"下单扣库存"跨服务场景，故意抛异常观察 undo_log 表与日志状态变化；
2. **读日志对照本文**：开启 debug 后按"Begin new global transaction → Register branch successfully → Branch committing/rollbacking → undo_log deleted"的日志顺序回找各章源码；
3. **断点四连**（理解 AT 精髓的最短路径）：`TransactionalTemplate.execute:252`（begin）、`ConnectionProxy.processGlobalTransactionCommit:247`（一阶段收尾）、`DefaultCoordinator.timeoutCheck:406`（TC 心脏）、`AbstractUndoLogManager.undo:315`（反向补偿）；
4. **实验全局锁**：两个测试线程用 `@GlobalTransactional` 同时 update 同一行，观察 `Global lock acquire failed` 与客户端重试日志；再改 `lockRetryTimes/interval` 感受参数；
5. **进阶**：file → db → raft 三种存储各跑一遍，理解 6.4/6.7 节差异；TCC 用 `useTCCFence=true` 复现空回滚/悬挂；最后读 `changes/zh-cn/2.x.md` 跟踪 2.x 动态。

## 12.4 源码阅读入口清单（30 个关键文件）

1. `common/.../XID.java` — XID 格式
2. `common/.../core/model/GlobalStatus.java` — 全局状态机
3. `core/.../core/model/BranchStatus.java` / `BranchType.java` — 分支状态与模式
4. `core/.../core/model/TransactionManager.java` — TM 权限清单
5. `core/.../core/context/RootContext.java` — ThreadLocal 上下文
6. `core/.../core/protocol/MessageType.java` — 协议总表
7. `core/.../core/protocol/RpcMessage.java` + `ProtocolConstants.java` — 载体与帧
8. `core/.../core/rpc/netty/AbstractNettyRemoting.java` — 收发框架
9. `core/.../core/rpc/netty/AbstractNettyRemotingClient.java` — 客户端发送/合并
10. `core/.../core/rpc/netty/NettyClientChannelManager.java` — 连接池与重连
11. `core/.../core/rpc/netty/ChannelManager.java` — 服务端连接注册
12. `core/.../core/rpc/netty/v1/ProtocolEncoderV1.java` — 帧编码
13. `tm/.../tm/api/DefaultGlobalTransaction.java` — 角色与重试
14. `tm/.../tm/api/TransactionalTemplate.java` — 六步模板
15. `tm/.../tm/DefaultTransactionManager.java` — TM RPC
16. `integration-tx-api/.../handler/GlobalTransactionalInterceptorHandler.java` — 拦截 + 降级
17. `rm/.../rm/DefaultResourceManager.java` / `AbstractResourceManager.java` — 分支 RPC 出口
18. `rm/.../rm/AbstractRMHandler.java` — TC 下行入口
19. `rm-datasource/.../DataSourceProxy.java` / `ConnectionProxy.java` — 代理与提交分叉
20. `rm-datasource/.../exec/AbstractDMLBaseExecutor.java` — 镜像模板
21. `rm-datasource/.../exec/BaseTransactionalExecutor.java` — lockKey 与 undo 暂存
22. `rm-datasource/.../exec/SelectForUpdateExecutor.java` — 读锁
23. `rm-datasource/.../undo/AbstractUndoLogManager.java` — flush/undo/防御
24. `rm-datasource/.../AsyncWorker.java` — 异步提交
25. `server/.../server/Server.java` — TC 启动
26. `server/.../server/coordinator/DefaultCoordinator.java` — 入口与定时任务
27. `server/.../server/coordinator/DefaultCore.java` — 全局两阶段
28. `server/.../server/session/SessionHolder.java` / `GlobalSession.java` — 会话与存储
29. `server/.../server/storage/file/lock/FileLocker.java` — 内存全局锁
30. `spring/.../spring/annotation/GlobalTransactionScanner.java` — Spring 织入总入口

## 结语

Seata 的源码魅力在于**一个协议、四种实现**：`MessageType` 定义了 TC 与 TM/RM 之间的全部对话，`GlobalStatus` 定义了事务的一生，而 AT/TCC/XA/SAGA 四种模式只是对"一阶段做什么、二阶段怎么收尾"这道填空题给出的四个不同答案。把第四章的"一阶段四步"（镜像 → 执行 → 镜像 → undo_log）和第六章的"六个定时任务"读懂，你就掌握了这条产品线 80% 的骨架；剩下的 20%——Raft、namingserver、Saga 状态机、fence——都是在这副骨架上为特定场景打的补丁。分布式事务没有银弹，Seata 的每一个开关（全局锁、异步提交、fence、降级检查、重试上限）都是一致性、性能与侵入度之间的一次明码标价——读源码，就是读这些价签。
