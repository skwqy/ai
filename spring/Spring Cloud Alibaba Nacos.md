# Spring Cloud Alibaba Nacos 深度源码解析（写给初学者的架构全景）

> **本文基于的源码**：
> - **Nacos 服务端与官方客户端**：`D:\code\3rd\nacos`，版本 **3.3.0-RC**（develop 分支快照，Git commit `1b6309f`，2026-09-24）。3.3.0 尚未 GA，比最新发布版 3.2.4（2026-08-27）略新，架构与 3.x 一致。下文证据路径以 **`nacos:`** 前缀指代该仓库。
> - **Spring Cloud Alibaba 集成层**：`D:\code\3rd\spring-cloud-alibaba`，**2023.x 分支**（`2023.0.3.5-SNAPSHOT`），对应 Spring Cloud **2023.0.3**、Spring Boot **3.2.9**、nacos-client **2.4.3**（以上均在 pom.xml 中实证）。下文证据路径以 **`sca:`** 前缀指代该仓库。
> - 文中所有【源码证据】的文件路径与行号均为对上述快照实际读取所得。
>
> **版本取舍说明**：Nacos 的客户端（nacos-client）与服务端同仓同版本演进，本文客户端章节按 **3.3.0-RC 的 nacos-client** 分析；SCA 2023.x 实际绑定的是 nacos-client **2.4.3**——两者对服务端说同一套 gRPC 协议（2.x 客户端已以 gRPC 为主，3.x 客户端删除了 HTTP 长轮询代码），因此本文对客户端机制的分析对 2.4.3 用户同样适用，仅在"3.x 删除了 XX"处需要注意版本。SCA 各版本线与 Spring Boot 的对应关系见 1.6.4。
>
> **阅读约定**：每章"先白话、后源码"——先用一两句人话讲清"这是什么、为什么需要它"，再给出源码证据链（`模块/src/main/java/.../类名.java` + 方法名 + 行号 + 代码片段）。行号只对该快照精确，读者用 IDEA 打开源码按类名 + 方法名定位即可。

## 如何读这份文档

如果你是初学者，推荐两遍读法：

- **第一遍（建立地图，1~2 小时）**：只读第一章（总览）每节的开头白话段、各章的"本章小结"节、以及第九章（贯通视图：三条时间线）。目标是能回答：服务注册到 Nacos 后，数据存在哪？配置改了之后，客户端是怎么知道的？临时实例和持久实例为什么走两套完全不同的协议？
- **第二遍（深入源码）**：对照每章的【源码证据】逐行读。顺序建议：第二章（gRPC 长连接，一切的地基）→ 第三章（naming 服务端）→ 第五章（config 服务端）→ 第七章（Distro/JRaft，可跳读 Raft 细节）→ 第四章/第六章（Spring Cloud 集成层，与业务代码最相关）→ 第八章（鉴权与限流，部署前必读）。

---

# 一、总览：Nacos 的定位、设计哲学与整体架构

## 1.1 一句话定位

**Nacos 是一个把"注册中心"和"配置中心"合二为一的动态服务基础设施，Spring Cloud Alibaba 是它接入 Spring Cloud 生态的适配层。** 名字本身就是定位：**Na**ming and **Co**nfiguration **S**ervice——动态服务发现（Naming）+ 动态配置管理（Config）+ 动态 DNS 服务（DNS，面向非 Java 生态，本文不展开）。官网对它的定位是"an easy-to-use platform designed for dynamic service discovery and configuration and service management"。

它要同时解决微服务的两个"动态"问题：

- **服务的动态**：实例随时上线、下线、扩缩容，调用方要实时拿到"现在有哪些健康的实例"。这是注册中心的职责（本文第三、四章）。
- **配置的动态**：参数改了不想重启应用，改完要秒级生效、可灰度、可回滚。这是配置中心的职责（本文第五、六章）。

两个能力共享同一套地基：同一个 gRPC 长连接协议（第二章）、同一套集群与一致性框架（第七章）、同一套鉴权（第八章）。**一个 SDK、一条连接、两个能力**，这是 Nacos 与"注册中心（Eureka）+ 配置中心（Config/Consul KV）+ MQ（Bus 广播刷新）"三件套组合最大的架构差异。

而 Spring Cloud Alibaba（下称 SCA）在这中间的角色非常薄：把 Spring Cloud 的 `ServiceRegistry`/`DiscoveryClient` 接口接到 `NamingService`，把 Spring Boot 的配置加载（`spring.config.import`/bootstrap）接到 `ConfigService`，把 Nacos 的变更回调翻译成 Spring Cloud 的 `RefreshEvent`。理解了 Nacos 本身，SCA 只是几十个类的胶水（第四章、第六章）。

## 1.2 设计哲学：读源码前先记住六句话

1. **连接即注册（2.x 起的第一性原理）**。客户端与服务器之间只有一条 gRPC 双向流长连接；注册、订阅、配置监听、推送、健康检查全部跑在这条连接上。连接在，注册就在；连接断，实例就该被摘除。1.x 时代"应用层心跳 + HTTP 请求"的模型在 2.x 被整体推翻（临时实例的服务端摘除依赖连接断开事件，3.2.0 起 UDP 推送彻底删除）。全文大量机制都由这一条派生。
2. **注册表不是"一张表"，是一台事件驱动的内存索引机**。naming v2 的核心数据结构是"客户端（Client）"：每个连接/IP+端口是一个 Client 对象，里面放着它发布与订阅了哪些服务；`ClientServiceIndexesManager` 维护 service→clients 的正向索引，`ServiceStorage` 把索引聚合成推送用的 `ServiceInfo` 快照。注册/注销/断连全是"改内存 + 发事件"，没有落库（临时实例），也没有查库。
3. **AP 与 CP 按数据性质分流，配置是第三条路**。临时实例走自研的 **Distro** 协议（AP，最终一致，只同步"本节点负责的 client"）；持久实例与服务/实例元数据走 **JRaft**（CP，强一致）；配置数据既不走 Distro 也不走 Raft（外置 DB 时）——**DB 是唯一真源**，各节点把 DB dump 成本地缓存，靠 gRPC 通知 + 定时对账保证最终一致。三种数据三种一致性模型，是理解 Nacos 集群的钥匙（第七章）。
4. **推拉结合、推送为主，两代演进**。服务发现：客户端订阅一次，服务端变了主动推（1.x UDP → 2.x 起 gRPC，客户端收到后仍会回源查询校验）。配置监听：客户端注册监听，服务端变了主动通知（1.x HTTP 长轮询 → 2.x 起 gRPC 批量监听），客户端收到通知再回源拉内容。"推通知、拉数据"避免了推送内容过大和乱序问题。
5. **兼容是第一工程学**。1.x 的 HTTP open API、`/beat` 心跳、长轮询、`betaIps` 灰度参数，在 2.x/3.x 的服务端里全部保留兼容路径；3.x 才开始删客户端侧旧代码（长轮询客户端、UDP 推送）。读源码时会不断遇到"v1 兼容层"，这不是历史包袱散落，而是刻意设计的升级期共存。
6. **一切皆事件**。模块间解耦几乎全靠 `NotifyCenter` 事件总线（ring buffer 16384），naming 甚至为事件顺序性专门做了分片发布器（`NamingEventPublisherFactory`，同一类事件同队列串行消费）。读懂"谁发事件、谁订阅事件"两张表，一半的调用链就通了。

## 1.3 模块分层全景

Nacos 是多模块 Maven 工程，根目录实测 30 个模块。与 Spring Framework"按层分包"不同，Nacos 的模块是"客户端栈 + 服务端栈 + 共享地基"的十字结构：

```
┌──────────────────────── 客户端栈（打进你的应用的 jar） ────────────────────────┐
│  api          （NamingService/ConfigService 接口 + gRPC 请求/响应 DTO + proto）│
│  client-basic （鉴权 SPI 客户端、服务器列表管理 ServerListManager 基础）        │
│  client       （nacos-client：naming/config 两个子系统的完整客户端实现）        │
├──────────────────────── 共享地基 ────────────────────────────────────────────┤
│  common       （NotifyCenter 事件总线、RpcClient/gRPC 客户端、工具箱、日志 SPI）│
│  sys          （EnvUtil：nacos.home、端口等环境抽象）                          │
│  consistency  （APProtocol/CPProtocol/RequestProcessor 协议抽象，极薄）        │
│  persistence  （Derby/MySQL 双数据源、持久化 SPI）                             │
│  auth / plugin / plugin-default-impl（鉴权骨架 / 插件 SPI / 官方默认实现）      │
├──────────────────────── 服务端内核 ──────────────────────────────────────────┤
│  core         （gRPC 双端口服务器、连接管理、集群成员、Distro/JRaft 协议实现、  │
│                鉴权过滤器、限流、全局参数校验）                                 │
│  naming       （服务注册发现领域模型：Client/索引/ServiceStorage/推送/健康检查）│
│  config       （配置中心：dump 体系、查询责任链、长轮询、灰度、集群通知）        │
├──────────────────────── 组装层（真正打成 nacos-server 包） ────────────────────┤
│  console      （控制台后端，聚合下面所有业务模块 + ai/copilot/mcp）             │
│  server       （启动器，聚合 naming+config+dns+istio+prometheus+插件）         │
│  distribution （打包、application.properties、启动脚本）                       │
├──────────────────────── 生态与外围 ──────────────────────────────────────────┤
│  ai / copilot / ai-registry-adaptor（3.x 新增：MCP Registry 与 AI 服务治理）   │
│  istio / k8s-sync / dns / prometheus / cmdb / lock / maintainer-client /     │
│  address / logger-adapter-impl / example / test                              │
└──────────────────────────────────────────────────────────────────────────────┘
```

初学者只需要盯住 8 个模块：**api、client、common、core、naming、config、consistency、persistence**——其余都是外围能力或组装壳。

## 1.4 模块依赖图（以各模块 pom.xml 的依赖实证）

```
                 common（事件总线/RpcClient/工具）      sys（环境）
                 ▲   ▲    ▲     ▲      ▲              ▲
      api ◄──────┘   │    │     │      └── consistency ─┘
       ▲             │    │     │             ▲
       │             │    │     └── persistence │
  client-basic       │    │           ▲        │
       ▲             │    │           │        │
       │        auth ┘    │           │        │
  client ─────────────────┼───────────┼────────┘（另依赖 api、encryption-plugin）
       ▲                  │           │
       │            core ◄┴───────────┘（依赖 common、consistency、auth、jraft-core）
       │             ▲   ▲
       │      naming ┘   └ config（依赖 core、persistence、config-plugin）
       │             ▲   ▲
       │             └─┬─┘
       │            console（另聚合 ai/copilot/mcp/istio/k8s-sync/插件）
       │               ▲
       └──── server ◄──┘（naming+config+dns+istio+prometheus+default-plugin-all）
```

真实依赖声明（摘自各模块 pom.xml 的 `<dependency>`，已逐个验证）：

| 模块 | 关键依赖 | 说明 |
|---|---|---|
| api | jackson-annotations | 纯接口与 DTO，不依赖任何 nacos 模块 |
| common | slf4j-api、grpc-* | 全仓库最底层；NotifyCenter 与客户端 gRPC 栈在此 |
| consistency | common | 只定义 AP/CP 协议抽象与 proto 实体 |
| client-basic | api | 鉴权 SPI 与服务器列表管理 |
| client | api、client-basic、common、encryption-plugin | 即 nacos-client 发布物 |
| auth | common | 鉴权骨架（filter/service/parser） |
| core | common、consistency、auth、jraft-core、control-plugin | 服务端内核；JRaft 只在这里出现 |
| persistence | consistency、sys、spring-boot-starter-jdbc、derby、mysql-connector-j | Derby/MySQL 双数据源 |
| naming | core、api、cmdb | 服务发现领域层 |
| config | core、api、persistence、config-plugin | 配置中心领域层 |
| console | naming、config、ai、copilot、mcp、istio、k8s-sync、default-plugin-all | 控制台组装层 |
| server | naming、config、dns、istio、prometheus、default-plugin-all | 启动器组装层 |

这张表本身就是架构说明：**client 与 core 是两棵独立的树**（各自对接 common），中间只靠 api 里的 proto 契约通信；**naming 和 config 互不依赖**（两大能力正交）；**consistency 是抽象、core 是实现**（Distro/JRaft 都在 core/distributed 下）。

## 1.5 关键问题 → Nacos 方案映射（全文导览）

| 微服务开发的关键问题 | Nacos 的方案 | 详见 |
|---|---|---|
| 客户端与服务端怎么通信、断网怎么办、怎么换节点 | gRPC 双向流长连接：RpcClient 状态机 + 重连守护线程 + 服务端双端口 + 连接管理 | 第二章 |
| 实例注册后数据存在哪、如何做到"连接断即摘除" | Client 对象 + 双向索引 + ServiceStorage 快照；连接断开事件驱动摘除 | 第三章 |
| 调用方怎么实时感知实例上下线 | 订阅 + 服务端延迟推送（500ms 合并）+ gRPC 主动推送 + 本地 ServiceInfoHolder 缓存 | 第三、四章 |
| SCA 怎么把注册接到 Spring（`@EnableDiscoveryClient` 之后发生了什么） | AbstractAutoServiceRegistration + WebServerInitializedEvent → NacosServiceRegistry | 第四章 |
| 配置改了如何秒级生效 | CacheData md5 监听（gRPC 批量监听）→ 服务端通知 → 回源拉取 → 回调 listener → RefreshEvent | 第五、六章 |
| 配置怎么安全发布（灰度、回滚、历史、加密） | 统一 GrayRule 体系（beta/tag 都是特例）、HistoryService、EncryptionHandler | 第五章 |
| 集群多节点数据怎么一致 | Distro（临时实例，AP）+ JRaft（持久实例/元数据，CP）+ DB 单源+dump 对账（配置） | 第七章 |
| 生产环境怎么鉴权、限流 | 三 scope 鉴权（SDK/admin/console，3.x 默认全开）+ TpsControl + 连接数控制 | 第八章 |
| 部署要开哪些端口、内存参数怎么估 | 8848/9848/9849/7848 全景 + 容量常量表 | 2.4、附录 |
## 1.6 版本演进：1.x → 2.x → 3.x 关键变化对比

写作时（2026 年 10 月）的版本格局：1.x 已停在 1.4.8（2024-08-15）；2.x 维护线仍在发版（最新 2.5.4，2026-08-27）；3.x 是主线（最新 GA 3.2.4，2026-08-27），本文分析的 3.3.0-RC 是 develop 分支快照。本节日期均取自 Nacos 官网发布历史页（nacos.io/download/release-history），架构结论均经本地源码实证。

### 1.6.1 版本时间线与运行基线

| 版本 | GA 时间 | JDK | 一句话主题 |
|---|---|---|---|
| 1.0.0 | 2019-04-10 | Java 8 | 开源正式版：HTTP open API + 应用层心跳 + UDP 推送 + HTTP 长轮询 |
| 1.4.0（1.x 收官前的主力线） | 2020-11-02 | Java 8 | Distro v1 成熟、鉴权插件化（1.4.2 起）；1.4.8 为 1.x 末版 |
| 2.0.0 | 2021-03-20 | Java 8 | **长连接革命**：gRPC 2.0 协议、连接即注册、Distro v2（按 Client 同步）、推送引擎 v2；服务端保留 1.x 兼容 |
| 2.2 / 2.3 / 2.4 / 2.5 | 2022-12 ~ 2026-08 | Java 8 | 持续打磨：v2 API、鉴权插件默认实现、Gray 灰度雏形；2.4.3 正是 SCA 2023.x 绑定的客户端版本 |
| 3.0.0 | 2025-04-25 | **Java 17** | 零信任安全（**鉴权默认开启**）、V3 API 分层（client/admin/console）、控制台独立部署、MCP Registry/AI 能力（ai 模块） |
| 3.0.3 | 2025-08-21 | Java 17 | 3.0 线稳定版 |
| 3.1 / 3.2 | 2025-09 ~ 2026-08 | Java 17 | 3.2.0 起 **UDP 推送彻底删除**、3.3 增加配置 304 条件查询；最新 GA 3.2.4 |
| 3.3.0-RC（本文快照） | 未发布（develop） | Java 17 | 本文分析的基线 |

运行基线的源码/发行包证据：

| 结论 | 证据 |
|---|---|
| 3.x 基线 Java 17 | `nacos:distribution/conf/application.properties` 与根 pom 的 Java 版本约束；官网发布历史 3.0.0 起标注 Java 17 |
| 2.x 系列基线 Java 8 | 官网发布历史 2.x 全系列标注 Java 8 |
| 主端口与偏移 | `nacos:core/.../ServerMemberManager.java:97` `DEFAULT_SERVER_PORT = 8848`；`nacos:api/.../Constants.java:102` `SDK_GRPC_PORT_DEFAULT_OFFSET = 1000` |
| 3.x 鉴权默认开启 | `nacos:distribution/conf/application.properties:359-363`：`nacos.core.auth.enabled=true`、`nacos.core.auth.admin.enabled=true`、`nacos.core.auth.console.enabled=true`；代码默认同（`nacos:core/.../NacosServerAuthConfig.java:113-126` 取默认 `true`） |

### 1.6.2 特性演进对照表（源码实证，可复现）

每行都可用 `grep -r` 在对应仓库复现（旧版本结论结合官方发布说明）：

| 特性 | 引入 → 演进 | 本文快照中的证据 | 详见 |
|---|---|---|---|
| 应用层心跳（BeatTask/BeatReactor） | 1.x 引入 → **2.x 客户端删除** | `find -name "*Beat*"` 在 client/api 模块 0 命中（仅 `PropertyKeyConst.java:94` 残留兼容常量） | 4.4 |
| UDP 推送（UdpPushService） | 1.x 引入 → 2.x 弃用 → **3.2.0 彻底删除** | 全仓 grep `UdpPushService` 0 命中；`nacos:naming/.../InstanceOperatorClientImpl.java:201` 弃用日志 "UDP push has been removed in Nacos 3.2.0" | 3.6 |
| HTTP 长轮询（客户端侧） | 1.x 引入 → **3.x 客户端删除** | `grep -rn "LongPollingRunnable"` 在 client 模块 main 源码 0 命中；服务端 `LongPollingService` 保留兼容旧客户端 | 5.6 |
| gRPC 长连接协议（连接即注册） | 2.0 引入 | `nacos:common/.../RpcClient.java:319` start；`nacos:core/.../GrpcSdkServer.java:46` | 第二章 |
| Distro v2（按 Client + revision 同步） | 2.0 引入 | `nacos:naming/.../DistroClientDataProcessor.java:61` TYPE=`Nacos:Naming:v2:ClientData` | 7.3 |
| ConsistencyService 双协议门面（v1） | 1.x/2.0 存在 → **3.x 整体删除** | 全仓 grep `ConsistencyService`（接口）main 源码 0 命中；分流下沉到 ClientManagerDelegate | 7.5 |
| 配置灰度 beta/tag → 统一 GrayRule | 1.x/2.x 用 betaIps + 独立表 → **3.x 统一为 GrayRule SPI** | `nacos:config/.../model/gray/GrayRuleManager.java:33`；`TagUtils` 类已删除 | 5.8 |
| 集群配置同步 HTTP `/v1/cs/notify` | 1.x 引入 → **3.x 改 gRPC** | `nacos:config/.../service/notify/AsyncNotifyService.java:142`（ConfigChangeClusterSyncRequest）；`RunningConfigUtils` 已不存在 | 5.7 |
| V3 API 分层（client/admin/console 三类鉴权 scope） | 3.0 引入 | `nacos:config/.../controller/v3/ConfigControllerV3.java:113`；v1 `ConfigController` 已删除 | 8.1 |
| 配置 304 条件查询 | 3.3 新增 | `nacos:client/.../ClientWorker.java:1533`（`request.setLocalMd5`）；服务端 `FormalHandler.java:55-66` | 5.5、6.1 |
| MCP Registry / AI 治理 | 3.0 引入 | 根 pom `<module>ai</module>`、console 依赖 `mcp`、`nacos-copilot` | — |
| 配置增量对账（DumpChangeConfigWorker） | 3.x 新增 | `nacos:config/.../dump/DumpChangeConfigWorker.java:43`（30s 周期分页扫 DB） | 5.4 |

### 1.6.3 三大版本主题对比

| 维度 | 1.x（2019~2024） | 2.x（2021~2026） | 3.x（2025~） |
|---|---|---|---|
| 通信 | HTTP 短连接 + 长轮询 + UDP 推送 | gRPC 长连接为主，HTTP 兼容 | gRPC 唯一（客户端删 HTTP 代码），服务端保留旧协议兼容 |
| 健康模型 | 应用层心跳（5s）+ 服务端主动探测（持久实例） | 连接即存活（临时实例）+ 心跳兼容（HTTP 老客户端） | 同 2.x；UDP/心跳残余继续收敛 |
| 注册表模型 | ServiceManager（服务为中心，全量同步） | Client 为中心 + Distro v2（按责任节点同步 Client） | 同 2.x，v1 类全删 |
| 一致性抽象 | ConsistencyService 接口族 | 同左 + v2 实现 | 接口删除，分流下沉到 ClientManager/ClientOperationService |
| 配置变更感知 | HTTP 长轮询（29.5s） | gRPC 批量监听 + 长轮询兼容 | gRPC 主链路（长轮询仅服务 1.x 老客户端）+ 304 优化 |
| 灰度 | betaIps（独立 beta 表） | 同左 | 统一 GrayRule SPI（config_info_gray 表） |
| 安全 | 鉴权默认关闭 | 鉴权插件化（默认关） | **三 scope 分层鉴权，默认全开**（首次部署即有密码门槛） |
| API | /v1 | /v1 + /v2 | /v3（client/admin/console 分层），v1 客户端 API 删除 |
| Java | 8 | 8 | 17 |

### 1.6.4 对初学者的意义：网上教程哪些过时了，SCA 版本怎么选

**网上教程按 1.x 讲、现在已过时或"换地方了"的部分**：

- `BeatTask`/`sendBeat` 心跳源码分析——2.x 起客户端无应用层心跳，健康检查改为连接级（第二章 2.3.3）；服务端 `/beat` 只是 HTTP 老客户端兼容。
- `LongPollingRunnable`/`checkUpdateDataIds` 长轮询客户端分析——3.x 客户端已删，监听走 gRPC 批量监听（5.6、6.1.3）。
- `UdpPushService`/`PushService`（1.x）分析——已删除，现推送引擎是 `PushDelayTaskExecuteEngine`（3.6）。
- `ServiceManager`/`Service`（v1，按服务存储、全量 Distro 同步）分析——v2 已是 Client 为中心（3.2）。
- Ribbon 权重负载均衡——SCA 2021.x 起换 Spring Cloud LoadBalancer（4.5.4）。
- bootstrap 时代的 `bootstrap.yml` + shared-configs——SCA 2023.x 已转向 `spring.config.import`（6.2.1）。

**SCA 版本线与运行基线**（本文仓库 git 实证仅覆盖 2023.x；其余据官方发布记录与 Maven Central 版本线归纳）：

| SCA 版本线 | 最新版本（本文写作时） | Spring Cloud | Spring Boot | 客户端绑定 | 关键差异 |
|---|---|---|---|---|---|
| 1.5.x | 1.5.1.RELEASE | Edgware~Finchley | 1.5.x | nacos-client 0.x/1.x | bootstrap + Ribbon |
| 2.2.x | 2.2.11 | Hoxton | 2.2~2.4 | nacos-client 1.x/2.0 | bootstrap + Ribbon；`NacosWatch` 定时任务时代 |
| 2021.x | 2021.0.6.2 | 2021.0.x | 2.6 | nacos-client 2.1/2.2 | bootstrap 默认关闭；LoadBalancer 替代 Ribbon |
| 2022.0.0.x | 2022.0.0.2 | 2022.0.0 | 3.0 | nacos-client 2.2 | Boot 3 / jakarta |
| **2023.x（本文）** | 2023.0.3.4（仓库快照 2023.0.3.5-SNAPSHOT） | 2023.0.3 | 3.2.9 | **nacos-client 2.4.3** | `AutoConfiguration.imports`；config-data 主推 |
| 2025.x | 2025.1.0.0 | 2025.0/2025.1 | 3.4+ | nacos-client 3.x | 后续版本线 |

选型提示：连 **Nacos server 3.x** 用哪条 SCA 线都可以（gRPC 协议向后兼容，nacos-client 2.4.3 可连 3.x server）；但新项目建议直接 SCA 2023.x 及以上 + Boot 3.x，本文第四章/第六章的源码即按此基线分析。

## 1.7 全文章节地图

- **第二章 通信地基：gRPC 长连接体系（common / core.remote）**：客户端 RpcClient 状态机、建连三步、重连与健康检查；服务端双端口服务器、连接注册与驱逐、请求分发；端口全景。
- **第三章 注册中心服务端（naming 模块）**：Client 模型、双向索引、ServiceStorage、注册/查询/订阅三条 gRPC 链路、推送引擎、健康检查三套并存、元数据的 CP 化。
- **第四章 注册中心客户端与 Spring Cloud 集成（nacos-discovery）**：NamingService 委托链、redo 重放、订阅回调线程模型；SCA 的注册时机、发现、负载均衡、优雅下线；默认关闭的 NacosWatch/HeartBeat。
- **第五章 配置中心服务端（config 模块）**：DB 单源 + dump 双缓存、写入链路、查询责任链、长轮询与 gRPC 批量监听两代并存、集群 gRPC 通知、统一灰度模型、历史与加密。
- **第六章 配置中心客户端与 Spring Cloud 集成（nacos-config）**：ClientWorker 铃声驱动监听循环、CacheData、failover/快照容灾；SCA 的两条接入路线、加载顺序、变更刷新到 @RefreshScope 的全链路。
- **第七章 集群一致性（core / consistency）**：成员管理与三种寻址、Distro 协议全流程（sync/load/verify）、JRaft 多 group 写路径、ConsistencyService 的消亡、事件总线与分片。
- **第八章 鉴权、限流与连接治理**：三 scope 鉴权、JWT、server identity、客户端登录 token、TpsControl、生产部署要点。
- **第九章 贯通视图**：一次服务注册的一生、一次配置变更的一生、一次节点宕机的一生——三条时间线把前八章串成全景。
- **第十章 附录**：关键参数速查表、关键类速查、学习路线。


---
# 二、通信地基：gRPC 长连接体系（nacos-common / core.remote）

## 2.1 模块定位

**先白话**：1.x 时代，客户端每一次注册、每一次查询都是一次独立 HTTP 请求；服务端"知不知道这个客户端还活着"，靠客户端每 5 秒发一次心跳包。这在几十万实例的规模下，心跳本身就是风暴。2.0 起的答案是：**客户端与服务端之间保持一条 gRPC 双向流长连接，所有业务（注册/订阅/查询/监听/推送）都复用它，连接本身就承担了心跳职责**。

这条连接的两端分别是：

- 客户端栈：`nacos:common/src/main/java/com/alibaba/nacos/common/remote/client/`（RpcClient 抽象 + grpc 子包），naming 与 config 各自包装一个 RpcClient 实例。
- 服务端栈：`nacos:core/src/main/java/com/alibaba/nacos/core/remote/`（BaseRpcServer 抽象 + grpc 子包 + 连接管理），**两个端口**各起一台服务器：9848 面向 SDK（客户端），9849 面向集群节点间。

本章自底向上走完：客户端怎么建连、怎么保活、怎么换节点；服务端怎么管连接、怎么把请求分发给业务 handler；最后给出端口全景表。

## 2.2 客户端：RpcClient 状态机与重连守护线程

### 2.2.1 先白话：一台"永不断线"的客户端

`RpcClient` 是所有 gRPC 客户端的抽象基类，核心是一套状态机（`INITIALIZED → STARTING → RUNNING → SHUTDOWN`，中间还有 `UNHEALTHY`/`RECONNECTING` 等过渡态）和一个**重连守护线程**：它不停地从一个"重连信号队列"里取活儿——队列空了说明连接空闲，就做健康检查；收到切换信号就去重连下一台服务器。

### 2.2.2 start()：守护线程与四种请求处理器

【源码证据】`nacos:common/src/main/java/com/alibaba/nacos/common/remote/client/RpcClient.java:319-468` `start()`：

```java
public final void start() throws NacosException {
    boolean success = rpcClientStatus.compareAndSet(INITIALIZED, STARTING);   // :319-321
    clientEventExecutor = new ScheduledThreadPoolExecutor(2,
        new NameThreadFactory("com.alibaba.nacos.client.remote.worker"));      // :327-328
```

这个 2 线程的小线程池里跑着核心守护循环（`:347-418`）：

```java
ReconnectContext reconnectContext = reconnectionSignal.poll(connectionKeepAlive, MILLISECONDS);
if (reconnectContext == null) {
    // check alive time.
    if (System.currentTimeMillis() - lastActiveTimeStamp >= rpcClientConfig.connectionKeepAlive()) {
        boolean isHealthy = healthCheck();          // :357-359  空闲超过 5s 才探测
        ...
```

要点三个：

1. **`lastActiveTimeStamp` 每次成功请求都会刷新**（`request()` 内 `:791`），所以正常业务流量下几乎不会触发额外健康检查——连接的"活性"由业务流量自然续期。
2. start 里还注册了两个服务端可发起的处理器（`:457-466`）：`ConnectResetRequestHandler`（服务端要求换连接，立即 `switchServerAsync`）与 `ClientDetectionRequest` 应答器（服务端反向探测，直接回包）。
3. 首次连接同步重试 `retryTimes` 次（默认 3，`:421-441`），失败抛异常——这就是"启动时连不上 Nacos 会报错重试 3 次"的出处。

### 2.2.3 建连三步：serverCheck → 双向流 → ConnectionSetupRequest

【源码证据】`nacos:common/.../remote/client/grpc/GrpcClient.java:365-438` `connectToServer()`：

```java
Response serverCheckResponse = serverCheck(serverIp, serverPort);          // :376  第一步：确认端口上真的是 Nacos
...
BiRequestStreamGrpc.newStub(...)                                           // :386-401 第二步：建立双向流，bindRequestStream 挂接流上收包的处理链
grpcConn.sendRequest(new ConnectionSetupRequest(... abilityTable ...));    // :408-415 第三步：连接注册（携带客户端能力表）
```

第三步的 `abilityTable` 是 2.x 后期加入的**能力协商**：客户端声明"我支持持久实例注册"等开关位，服务端据此决定某些请求能不能发给它（`nacos:naming` 客户端路由 gRPC/HTTP 时就用到了 `SERVER_PERSISTENT_INSTANCE_BY_GRPC`，见 4.1）。能力等待最多 `capabilityNegotiationTimeout`（默认 5000ms，`:417-430`）。

连接载体 `GrpcConnection`（`nacos:common/.../grpc/GrpcConnection.java:48`）：`request()` 把请求塞给 gRPC future stub 并 `get(timeout)`（`:70-85`）；服务端主动推送走双向流回调线程（`sendRequest` 的 streamObserver，`:126-129`）。**这意味着：客户端收服务端推送是在 gRPC 流回调线程里，后续一切"推送→本地缓存→用户回调"的线程模型都从这里展开（3.6、6.1.3）。**

### 2.2.4 请求模型：超时、重试、失败换机

【源码证据】`RpcClient.java:740-817` `request()`：

- 默认超时 `timeOutMills=3000ms`、重试 `retryTimes=3`（`DefaultGrpcClientConfig.java:215-247` 的一串默认值：`connectionKeepAlive=5000`、`serverCheckTimeOut=3000`、`healthCheckRetryTimes=3`、`healthCheckTimeOut=3000`、`maxInboundMessageSize=10MB`、`channelKeepAlive=6min`）。
- 收到 `UN_REGISTER` 类 ErrorResponse 说明服务端已不认识这条连接 → 立即换服务器（`:774-787`）。
- 全部重试失败 → 状态置 `UNHEALTHY` 并 `switchServerAsyncOnRequestFail()`（`:816-817`）。

换机的服务器列表来自 `ServerListFactory`。客户端栈里对应 `nacos:client-basic/.../address/AbstractServerListManager.java:42`：SPI 加载 `ServerListProvider`（`PropertiesListProvider`——读 `serverAddr` 配置；`EndpointServerListProvider`——读地址服务器动态列表，`:89-112`），列表变化通过 `ServerListChangeEvent` 通知到 RpcClient（`naming` 侧 `NamingGrpcClientProxy.onEvent → rpcClient.onServerListChange()`）。重连时逐台尝试、退避 `min(retryTurns+1, 50)*100ms`（`RpcClient.java:674-677`）。

### 2.2.5 naming 与 config 各自的连接

naming 与 config 各建自己的 RpcClient（注册中心与配置中心故障隔离，一条断了另一条不受影响）：

- naming：`NamingGrpcClientProxy` 构造器里 `RpcClientFactory.createClient(uuid, ConnectionType.GRPC, grpcClientConfig)`（`nacos:client/.../remote/gprc/NamingGrpcClientProxy.java:119-121`）。
- config：`ClientWorker.ConfigRpcTransportClient.ensureRpcClient` 里按 `taskId` 分片创建多个 RpcClient（`uuid + "_config-" + taskId`，`nacos:client/.../impl/ClientWorker.java:1379-1398`）——监听的配置条数极多时，分片摊平单连接压力。

两者都通过 `GrpcSdkClient`（`nacos:common/.../grpc/GrpcSdkClient.java:31`）连到服务端 9848（偏移 `SDK_GRPC_PORT_DEFAULT_OFFSET = 1000`，`nacos:api/.../Constants.java:102`）。

## 2.3 服务端：双端口 gRPC 服务器与连接管理

### 2.3.1 两台服务器，一套抽象

【源码证据】`nacos:core/src/main/java/com/alibaba/nacos/core/remote/BaseRpcServer.java:107-109`：

```java
public int getServicePort() { return EnvUtil.getPort() + rpcPortOffset(); }
```

| 服务器 | 偏移 | 端口 | 面向 | 证据 |
|---|---|---|---|---|
| `GrpcSdkServer` | +1000 | 9848 | 客户端 SDK | `nacos:core/.../grpc/GrpcSdkServer.java:46-51` |
| `GrpcClusterServer` | +1001 | 9849 | 集群节点间（Distro 同步、配置通知、成员汇报） | `nacos:core/.../grpc/GrpcClusterServer.java:46-51`；`nacos:api/.../Constants.java:104` `CLUSTER_GRPC_PORT_DEFAULT_OFFSET = 1001` |

gRPC 服务名只有两个（`nacos:core/.../grpc/GrpcServerConstants.java:55-61`）：一元调用 `Request/request`、双向流 `BiRequestStream/requestBiStream`。所有请求都是 `Request` 的子类（InstanceRequest、ConfigQueryRequest……），由 `type` 字符串路由。

### 2.3.2 连接建立与注册：ConnectionManager

客户端发来 `ConnectionSetupRequest` 后，服务端流程（`nacos:core/.../grpc/GrpcRequestAcceptor.java`）：

1. `ServerCheckRequest` 特判：返回带新 `connId` 的 `ServerCheckResponse`（`:100-110`）；
2. 业务请求先查 `RequestHandlerRegistry.getByRequestType(type)`（`:117`），再 `connectionManager.checkValid(connectionId)`（`:135-145`）。

连接正式注册在 `ConnectionManager.register(connectionId, connection)`（`nacos:core/.../ConnectionManager.java:104-131`）：先过**连接数限流** `checkLimit`（走 `ControlManagerCenter` 的 `ConnectionControlManager`，替代了 2.x 早期的 `LimitStream`，`:133-146`），成功后 `notifyClientConnected` 通知监听器——**naming 模块正是在这个回调里把一条 gRPC 连接包装成一个 `ConnectionBasedClient`（3.2.2），这是"连接即注册"的接线处**。

连接元数据存 `ConnectionMeta`（clientIp、app、version、标签等，`nacos:core/.../ConnectionMeta.java:38`）。

### 2.3.3 连接驱逐：3 秒一轮的"点名"

【源码证据】`nacos:core/.../ConnectionManager.java:255-279`：启动时调度 `runtimeConnectionEjector::doEject`，周期 3000ms。默认实现 `NacosRuntimeConnectionEjector`（`nacos:core/.../NacosRuntimeConnectionEjector.java:49-146`）做两件事：

- `ejectOutdatedConnection`：对超时（`lastActiveTime` 过期）或推送队列阻塞超 300s 的连接，先发 `ClientDetectionRequest` 反向探测，`asyncRequest` 等待 5000ms，latch 汇总 5s 后仍未应答的 `connectionManager.unregister(...)`（`:89-140`）——**unregister 会触发 `notifyClientDisconnected`，naming 收到后摘实例**。
- `ejectOverLimitConnection`：超过连接数上限时按策略驱逐。

对照客户端 2.2.2 的双向探测，完整闭环是：客户端空闲 5s 主动 `HealthCheckRequest`；服务端每 3s 扫描疑似死连接主动 `ClientDetectionRequest`。任一方向失败，都走向"断开 → 摘除"。

### 2.3.4 请求分发：RequestHandlerRegistry 与 @InvokeSource

所有业务 handler 继承 `RequestHandler<Req, Resp>`，注册进 `RequestHandlerRegistry`（`nacos:core/.../RequestHandlerRegistry.java:44-59`）。Spring 容器刷新时统一扫描（`:75-119`）：

- `@TpsControl(pointName = "...")` 注解的方法/类 → 注册 TPS 限流点位（第八章）；
- `@InvokeSource(source = {RemoteConstants.LABEL_SOURCE_SDK})` → 限制该 handler 只能被 SDK/CLUSTER 某类连接调用（`checkSourceInvokeAllowed`，`:68-73`）——例如 `ConfigChangeClusterSyncRequestHandler` 标注了 `LABEL_SOURCE_CLUSTER`，防止客户端伪造集群同步请求（`nacos:config/.../remote/ConfigChangeClusterSyncRequestHandler.java:45-74`）。

### 2.3.5 服务端推送出口：RpcPushService

naming 的推送与 config 的通知最终都走 `RpcPushService.pushWithCallback`（`nacos:core/.../RpcPushService.java:38,50`）：找到 `Connection` → 发 `NotifySubscriberRequest`/`ConfigChangeNotifyRequest` → 回调里做成功/失败处理。推送走双向流，无需等待一元应答；失败由各业务的 callback 决定重试（3.6、5.6）。

## 2.4 端口全景与部署含义

| 端口 | 用途 | 代码证据 |
|---|---|---|
| 8848 | HTTP（管理 API/控制台）+ 主端口基线 | `nacos:core/.../ServerMemberManager.java:97`；`nacos:distribution/conf/application.properties:21` `nacos.server.main.port=8848` |
| 9848 | gRPC SDK（客户端注册/发现/配置/推送） | `nacos:api/.../Constants.java:102`（+1000） |
| 9849 | gRPC 集群（Distro、配置同步、成员汇报） | `nacos:api/.../Constants.java:104`（+1001） |
| 7848 | JRaft 专用 RPC（持久实例/元数据共识；默认 = 主端口 **−**1000） | `nacos:core/.../MemberUtil.java:51,122-124` `calculateRaftPort() = port - 1000`；JRaft 拥有独立 RpcServer（`JRaftServer.start():210-216`） |
| 8080（可选） | address-server 模式下的地址服务器默认端口 | `nacos:core/.../AddressServerMemberLookup.java:87-89` |
| （无） | Derby 内嵌存储，纯目录文件，无网络端口 | `nacos:persistence/.../LocalDataSourceServiceImpl.java:56,87` |

**部署含义**：防火墙/K8s Service 必须放行 9848/9849/7848，只开 8848 是新手最常见的"客户端连不上 Nacos 3.x"事故；客户端侧只需连 `serverAddr`（8848），gRPC 端口由客户端自动 +1000 推导。

## 2.5 认证随行：token 如何挂上每一条 gRPC 请求

登录与 token 细节在第八章展开，这里只给接线位置：naming 侧 `AbstractNamingClientProxy.getSecurityHeaders()` 构造 `RequestResource` 后取 `SecurityProxy.getIdentityContext(resource)`，由 `requestToServer` 塞进每个请求 header（`nacos:client/.../AbstractNamingClientProxy.java:44-52`、`NamingGrpcClientProxy.java:512-516`）；config 侧同样在 `ClientWorker.requestProxy`（`:1628-1634`）。收到 403 时触发 `reLogin()`（`NamingGrpcClientProxy.java:529-531`）。

## 2.6 本章小结

- 客户端与服务端之间是 **gRPC 双向流长连接**：一元请求 `Request/request` + 双向流 `BiRequestStream/requestBiStream`；连接即注册的前提是这条连接有完整的生命周期管理。
- 客户端 `RpcClient` 是状态机 + 守护线程：空闲 5s 健康检查、请求失败 3 次重试后换机、退避重连；naming/config 各持一条连接，config 还按 taskId 分片。
- 服务端 9848/9849 双端口 + `ConnectionManager`：连接注册时限流，3s 周期驱逐死连接（反向探测 5s 不应答即 unregister），unregister 即"客户端死亡"信号。
- 端口速记：**8848 基线、+1000 SDK、+1001 集群、−1000 Raft**。
- 能力协商（abilityTable）决定服务端对某个客户端"能不能用新特性"，这是 2.x→3.x 平滑升级的机制基础。


---
# 三、注册中心服务端：naming 模块的 Client 模型、索引与推送引擎

## 3.1 模块定位与包结构

**先白话**：naming 模块回答一个问题——"某个服务的健康实例列表现在是什么"。1.x 的实现是"以服务为中心的注册表"（一张 Service 表，同步靠全量比较）；2.0 起推翻为**"以客户端为中心的注册表"**：服务器记的不是"服务→实例列表"，而是"每个客户端发布了什么、订阅了什么"，服务列表是查询/推送时从索引**现算**出来的。这个反转是读 naming v2 源码最重要的心法。

包结构（`nacos:naming/src/main/java/com/alibaba/nacos/naming/`）：

| 包 | 职责 |
|---|---|
| `remote/rpc/handler` | 8 个 gRPC handler（Instance/PersistentInstance/BatchInstance/ServiceQuery/SubscribeService/ServiceList/DistroData/FuzzyWatch）——所有客户端请求的入口 |
| `core/v2` | 领域层：`client`（Client 抽象与实现/ClientManager 族）、`index`（ClientServiceIndexesManager、ServiceStorage）、`service`（ClientOperationService 接口与 Ephemeral/Persistent 两实现）、`metadata`（元数据 CP 化）、`event`（事件定义与分片发布器）、`pojo`（Service/InstancePublishInfo） |
| `push` / `push/v2` | 推送引擎：NamingSubscriberServiceV2Impl + 延迟任务引擎 + executor |
| `healthcheck` | 健康检查：`heartbeat`（HTTP 老客户端兼容）、`v2`（持久实例主动探测） |
| `consistency/ephemeral/distro/v2` | 临时实例数据的 Distro 挂接（DistroClientDataProcessor 等） |
| `controllers/v3` | V3 HTTP 管理 API（Service/Instance/Cluster/Client/Health/Operator） |
| `cluster`、`misc`、`web` 等 | 集群状态、SwitchDomain 动态开关、HTTP 过滤器 |

## 3.2 注册表模型：不是"服务表"，是"客户端索引"

### 3.2.1 Service：不可变的坐标

服务本身只是一个坐标 POJO：`namespace + group + name + ephemeral`（`nacos:naming/.../core/v2/pojo/Service.java:31-45`；`equals` 只比较三元组，`:106-122`）。所有 Service 去重地存放在 `ServiceManager` 的 `ConcurrentHashMap`（容量 1<<10，`core/v2/ServiceManager.java:42-45`），`getSingleton` 首次创建时会发 `MetadataEvent.ServiceMetadataEvent`（`:61-70`）。**注意：ServiceManager 里没有任何实例列表**——它只是"见过哪些服务"的登记簿。

### 3.2.2 Client：一台"客户端对象"两种实现

实例数据挂在 Client 对象上（`nacos:naming/.../core/v2/client/AbstractClient.java:44-50`）：

```java
protected final ConcurrentHashMap<Service, InstancePublishInfo> publishers;   // 它发布了什么
protected final ConcurrentHashMap<Service, Subscriber> subscribers;           // 它订阅了什么
```

两种实现（`core/v2/client/impl/`）：

| 实现 | clientId | 来源 | 摘除时机 |
|---|---|---|---|
| `ConnectionBasedClient` | gRPC connectionId | 2.x 客户端（SDK 主路径） | 连接断开 |
| `IpPortBasedClient` | `ip:port:true/false`（true/false 即 ephemeral 标记，`:69-71`） | 1.x HTTP API 兼容（OpenAPI/SDK 旧版、第三方语言客户端） | 心跳超时（临时）/主动探测（持久） |

按 clientId 路由的分流器是 `ClientManagerDelegate`（`core/v2/client/manager/ClientManagerDelegate.java:122-127`）：Http 连接型 → `HttpConnectionBasedClientManager`；gRPC 连接型 → `ConnectionBasedClientManager`；其余按后缀分 `EphemeralIpPortClientManager` / `PersistentIpPortClientManager`。发布的数据结构是 `InstancePublishInfo`（ip/port/healthy/cluster/extendDatum，`pojo/InstancePublishInfo.java:35-43`），批量注册时是 `BatchInstancePublishInfo`。

Client 的变更都会触发两件事：`ClientEvent.ClientChangedEvent`（`AbstractClient.addServiceInstance:74-87`）与**修订号重算** `recalculateRevision()` = `DistroUtils.hash(this)`（`:203-207`）——revision 是 Distro 校验的凭据（7.3.3）。

## 3.3 注册链路：从 InstanceRequest 到一次事件

**先白话**：临时实例注册不写磁盘、不进 DB、不走 Raft——就是"往 Client 对象的 map 里 put 一条 + 发一个事件"。真正的一致性由第七章的 Distro 补齐。

【源码证据】gRPC 注册入口 `nacos:naming/.../remote/rpc/handler/InstanceRequestHandler.java:64-96`：

```java
case NamingRemoteConstants.REGISTER_INSTANCE:
    return registerInstance(service, request, meta);        // :79  service = Service.newService(ns, group, name, true/*ephemeral*/)
...
clientOperationService.registerInstance(service, instance, meta.getConnectionId());   // :89
NotifyCenter.publishEvent(new RegisterInstanceTraceEvent(...));                        // :90-94  审计 trace
```

落到 `EphemeralClientOperationServiceImpl.registerInstance`（`core/v2/service/impl/EphemeralClientOperationServiceImpl.java:56-78`）：

```java
Service singleton = ServiceManager.getInstance().getSingleton(service);
if (!singleton.isEphemeral()) { throw ...; }                // 临时实例必须落在临时服务上
Client client = clientManager.getClient(clientId);          // clientId = meta.getConnectionId()
InstancePublishInfo instanceInfo = getPublishInfo(instance);
client.addServiceInstance(singleton, instanceInfo);         // ① 数据入内存（并触发 ClientChangedEvent）
client.setLastUpdatedTime();
client.recalculateRevision();                               // ② 修订号+1
NotifyCenter.publishEvent(new ClientOperationEvent.ClientRegisterServiceEvent(singleton, clientId));  // ③ 发事件
```

**持久实例是另一个世界**：`PersistentInstanceRequestHandler`（`:67-94`）构造 `ephemeral=false` 的 Service，clientId 用 `IpPortBasedClient.getClientId(addr, false)`，落地到 `PersistentClientOperationServiceImpl`——它直接把注册动作序列化成 raft 日志写 JRaft（`WriteRequest → protocol.write`，`:108-133`），第七章展开。

其余入口的对应关系：HTTP 老客户端（open API）走门面 `InstanceOperatorClientImpl`（`core/InstanceOperatorClientImpl.java:101-111`，内部经 `ClientOperationServiceProxy` 分流到同两个实现）；批量注册走 `BatchInstanceRequestHandler`。

## 3.4 索引与快照：事件如何变成"服务实例列表"

注册只改了 Client 内存并发了事件；**下游两个订阅者**把事件变成可用数据：

**① `ClientServiceIndexesManager`（双向索引的另一半）**（`core/v2/index/ClientServiceIndexesManager.java:47-51`）：

```java
private final ConcurrentMap<Service, Set<String>> publisherIndexes;    // service → 发布它的 clientIds
private final ConcurrentMap<Service, Set<String>> subscriberIndexes;   // service → 订阅它的 clientIds
```

（client→service 方向由 Client 自身的 `publishers/subscribers` 承担。）它订阅 5 种 `ClientOperationEvent`（`:84-92`），`handleClientOperation`（`:122-134`）注册时 `addPublisherIndexes`——**若是该服务的第一个发布者，则发布 `ServiceEvent.ServiceChangedEvent(service, ADD_SERVICE)`**（`:136-145`）；注销/断连则清索引并发出带 `REMOVE_SERVICE`/`INSTANCE_DELETE` 类型的变更事件（`:103-120`）。订阅关系同理（首次订阅发 `ServiceSubscribedEvent`，`:158-165`，修复 issue #5404）。

**② `ServiceStorage`（推送数据的现算缓存）**（`core/v2/index/ServiceStorage.java:59-92`）：

```java
private final ConcurrentMap<Service, ServiceInfo> serviceDataIndexes;      // 已算好的推送数据
public ServiceInfo getPushData(Service service) {
    ServiceInfo result = emptyServiceInfo(service);
    result.setHosts(getAllInstancesFromIndex(singleton));                  // 遍历 publisherIndexes 聚合
    serviceDataIndexes.put(singleton, result);
    ...
```

`getAllInstancesFromIndex`（`:108-132`）对每个发布者 client 取 `getInstancePublishInfo(service)`，转成 API 层 `Instance` 并叠加实例元数据（`:161-168`）。变更事件到来时，推送引擎会先让 ServiceStorage 重新组装（见 3.6 的 PushExecuteTask）。

## 3.5 查询与订阅：两条不同的 gRPC 链路

**查询（pull）**——`ServiceQueryRequestHandler`（`remote/rpc/handler/ServiceQueryRequestHandler.java:65-78`）：

```java
ServiceInfo result = serviceStorage.getData(service);          // 命中缓存直接返回
result = ServiceUtil.selectInstancesWithHealthyProtection(result, serviceMetadata,
    cluster, healthyOnly, true, getSourceIp(meta));            // 按集群过滤 + 保护阈值
```

纯读、无副作用。`getServiceList`（服务名分页列表）走 `ServiceListRequestHandler`（`:57-73`），直接翻 `ServiceManager`。

**订阅（push）**——`SubscribeServiceRequestHandler`（`:79-117`）：先照查询一样返回一次当前列表，然后：

```java
if (request.isSubscribe()) {
    clientOperationService.subscribeService(service, subscriber, meta.getConnectionId());   // :103-115
```

`subscribeService` 落到 `EphemeralClientOperationServiceImpl.java:130-139`：`client.addServiceSubscriber(singleton, subscriber)` 并发 `ClientSubscribeServiceEvent`——**订阅记录同样存在 Client 对象里**，`subscriberIndexes` 同步建立。从此该服务的任何变更都会推给这条连接。服务列表页/网关启动时的 `getServiceList` 走查询；应用运行期的 `subscribe` 走订阅。1.x 的"查询时顺带注册为订阅者"行为已废弃（`InstanceOperatorClientImpl.listInstance` 里 UDP 订阅路径只打弃用日志，`:195-217`）。

## 3.6 推送引擎：500ms 合并、失败重试的两级流水线

**先白话**：一个秒杀服务 1 秒内实例抖动 20 次，如果每次变更都立刻推给几千个订阅者，推送本身就会打爆集群。Nacos 的做法是"**延迟合并**"：变更事件只往延迟任务引擎里放任务（同服务的任务自然合并），默认 500ms 后真正执行一次"重组数据 + 批量推送"。

【源码证据】事件 → 任务：`push/v2/NamingSubscriberServiceV2Impl.java:111-137`（订阅 `ServiceChangedEvent` 与 `ServiceSubscribedEvent`）：

```java
if (event instanceof ServiceEvent.ServiceChangedEvent) {
    delayTaskEngine.addTask(service, new PushDelayTask(service,
        PushConfig.getInstance().getPushTaskDelay()));          // 全量推送
} else if (event instanceof ServiceEvent.ServiceSubscribedEvent) {
    delayTaskEngine.addTask(service, new PushDelayTask(service, ..., subscribedEvent.getClientId()));  // 只推新订阅者
}
```

默认参数（`push/v2/PushConfig.java:34-55` 读环境；`constants/PushConstants.java:29-45`）：`DEFAULT_PUSH_TASK_DELAY = 500L` ms、`DEFAULT_PUSH_TASK_TIMEOUT = 5000L`、`DEFAULT_PUSH_TASK_RETRY_DELAY = 1000L`。

任务 → 执行：`PushDelayTaskExecuteEngine`（`push/v2/task/PushDelayTaskExecuteEngine.java:37`）继承通用的 `NacosDelayTaskExecuteEngine`；`PushDelayTaskProcessor` 把到期任务交给 `NamingExecuteTaskDispatcher` 并行执行（`:93-110`，受 `switchDomain.isPushEnabled()` 总开关控制）。`PushExecuteTask.run()`（`task/PushExecuteTask.java:57-95`）：

1. `generatePushData()` → `serviceStorage.getPushData(service)` 重新组装最新列表（失败兜底再投 1000ms 任务）；
2. 按"推全量订阅者"还是"只推某个 clientId"逐个 `pushExecutor.doPushWithCallback(...)`。

出口：`PushExecutorDelegate`（SPI 可替换，默认 `PushExecutorRpcImpl`，`push/v2/executor/PushExecutorDelegate.java:54-65`）→ `RpcPushService.pushWithCallback(clientId, NotifySubscriberRequest.buildNotifySubscriberRequest(serviceInfo), callback, ...)`（`PushExecutorRpcImpl.java:45-59`），经第二章的连接推送。失败回调 `ServicePushCallback.onFail`（`PushExecuteTask.java:162-178`）重新投递 `PushDelayTask`（1000ms 后重试；`NoRequiredRetryException` 除外，比如目标客户端已不存在）。

**UDP 推送已死**：全仓 grep 无 `UdpPushService` 类；1.x 客户端经 HTTP 查询时若带 udpPort，只打一条弃用日志（`InstanceOperatorClientImpl.java:201`："UDP push has been removed in Nacos 3.2.0"）。

## 3.7 健康检查：三套机制并存

**先白话**：3.x 服务端同时存在三种"实例死亡判定"，对应三种客户端来源。选哪种由客户端接入方式决定，不是配置项。

**① gRPC 客户端：连接断 = 实例摘除（主路径）**。链路（core → naming）：

```
连接断/被驱逐（2.3.3）
  → ConnectionManager.unregister → notifyClientDisConnected        （core）
  → ConnectionBasedClientManager.clientDisConnected：clients.remove(clientId) + release()
      发 ClientOperationEvent.ClientReleaseEvent + ClientEvent.ClientDisconnectEvent
                                                    （naming/core/v2/client/manager/impl/ConnectionBasedClientManager.java:96-118）
  → ClientServiceIndexesManager.handleClientDisconnect：清发布/订阅索引，逐服务发 ServiceChangedEvent  （:103-120）
  → 推送引擎推送新列表；DistroClientDataProcessor 收到 Disconnect 事件 → distroProtocol.sync(DELETE)  （7.3）
```

**② HTTP 老客户端：心跳兼容层**。`/beat` 请求进入 `InstanceOperatorClientImpl.handleBeat`（`:245-279`），刷新心跳时间戳的是 `ClientBeatProcessorV2`（`healthcheck/heartbeat/ClientBeatProcessorV2.java:52-85`，由不健康转健康时补发 `ServiceChangedEvent`）；超时摘除的是 `IpPortBasedClient.init()` 里为临时客户端挂的 `ClientBeatCheckTaskV2`（`client/impl/IpPortBasedClient.java:135-143` + `heartbeat/ClientBeatCheckTaskV2.java:36-83`），其中 `ExpiredInstanceChecker`（`:50-91`）超过 `preserved.ip.delete.timeout`（默认 30s）直接把实例从 Client 里移除并发 `ClientDeregisterServiceEvent`（trace 原因 `HEARTBEAT_EXPIRE`），`UnhealthyInstanceChecker` 只标不健康并广播。

**③ 持久实例：服务端主动探测（与 1.x 相同的逻辑）**。`HealthCheckTaskV2`（`healthcheck/v2/HealthCheckTaskV2.java:43`，类注释明确"Current health check logic is same as v1.x"）按集群配置的 `ClusterMetadata.healthCheckType`（TCP/HTTP/MySQL，`core/v2/metadata/ClusterMetadata.java:25-38`）周期探测；失败次数达阈值（`switchDomain.getCheckTimes()`）时经 `PersistentHealthStatusSynchronizer`（`:34-45`）**把健康状态变更写回 JRaft**——因为持久实例的数据是 CP 的，状态变更也必须走共识。这也是"持久实例没有 15s 心跳，只有服务端探测"的来源。

## 3.8 元数据：服务/实例元数据的 CP 化

服务级元数据（保护阈值 protectThreshold、选择器 Selector、集群配置）与实例级元数据（权重、enabled）不随 Client 走内存，而是**全部走 JRaft**：

- 写入口 `NamingMetadataOperateService`（`core/v2/metadata/NamingMetadataOperateService.java:39`）：把变更打包成 `MetadataOperation<T>` 日志（`:26`），`updateServiceMetadata`/`updateInstanceMetadata`/`addClusterMetadata` 各自构造 `WriteRequest`（group = `naming_service_metadata` / `naming_instance_metadata`）并 `cpProtocol.write`（`:56-97,144-155`）。
- 状态机应用侧是 `ServiceMetadataProcessor` / `InstanceMetadataProcessor`（均 `extends RequestProcessor4CP`，`onApply` 合并进 `NamingMetadataManager` 的内存 map 并递增 service revision，`NamingMetadataManager.java:119-122`）。
- 查询侧：`ServiceQueryRequestHandler` 里的 `metadataManager.getServiceMetadata(service)` 就是它的读接口；保护阈值（protectThreshold）就作用在 `ServiceUtil.selectInstancesWithHealthyProtection`。

1.x 里独立的 `Cluster` 实体已降级为 `ServiceMetadata.clusters` 里的一个 `ClusterMetadata` map 条目。

## 3.9 本章小结

- naming v2 的注册表 = **Client 对象（连接/IP） + 双向索引 + ServiceStorage 快照**三层：Client 是数据源，索引是加速器，ServiceStorage 是推送现算缓存。
- 临时实例注册是纯内存操作 + 事件（无落盘无共识）；持久实例注册是 raft 日志；元数据（保护阈值/权重/选择器）全部 CP 化。
- 订阅与查询是两条链路：查询读 ServiceStorage；订阅额外在 Client 上写 `subscribers` 并建 `subscriberIndexes`，从此走推送。
- 推送 = 500ms 延迟合并 + 按订阅者批量推 + 1000ms 失败重试；推送数据来自 ServiceStorage，推送通道是第二章的 gRPC 连接。
- 健康检查三轨制：gRPC 连接断开（主路径）、HTTP 心跳兼容（1.x 客户端）、服务端主动探测（持久实例，状态变更写 raft）。


---
# 四、注册中心客户端与 Spring Cloud 集成（nacos-discovery）

## 4.1 nacos-client：NamingService 的委托链

**先白话**：应用代码里注入的 `NamingService`（SCA 的 `NacosServiceManager` 创建的就是它）是个门面：它把请求转给一个"代理总调度"，再按实例类型/能力路由到 gRPC 通道或 HTTP 兼容通道。

【源码证据】接口 `nacos:api/src/main/java/com/alibaba/nacos/api/naming/NamingService.java:37`（注册 `:48`、订阅 `:527`）；实现 `nacos:client/.../naming/NacosNamingService.java:74`：

- 构造链（`init():110-136`）：创建 `InstancesChangeNotifier`（用户回调分发器）→ `NotifyCenter.registerToPublisher(InstancesChangeEvent.class, 16384)`（事件队列）→ `ServiceInfoHolder`（本地实例缓存）→ `NamingClientProxyDelegate`（总代理）。
- `registerInstance:178-184`：先 `NamingUtils.checkInstanceIsLegal`，再 `clientProxy.registerService(...)`。
- `subscribe` → `doSubscribe:536-547`：先 `changeNotifier.registerListener(...)` 注册监听、`notifyIfSubscribed` 用现有缓存立刻回放一次，再 `clientProxy.subscribe(...)`。

总调度 `NamingClientProxyDelegate` 的路由规则（`naming/remote/NamingClientProxyDelegate.java:218-224`）：

```java
if (instance.isEphemeral() || grpcClientProxy.isAbilitySupportedByServer(
        AbilityKey.SERVER_PERSISTENT_INSTANCE_BY_GRPC)) {
    return grpcClientProxy;      // 临时实例永远走 gRPC；持久实例只有服务端支持时才走 gRPC
}
return httpClientProxy;          // 否则 HTTP 兼容通道（1.x open API）
```

这就是"客户端 2.4.3 连 server 3.x / 2.x 都能工作"的兼容机关之一。

## 4.2 注册与重做（redo）：断线后谁替你重新注册

**先白话**：注册是"无状态"的——服务端把实例挂在你的连接上，连接一断实例就没了。那客户端重连后，谁来重新注册？答案是客户端自己：每次注册/订阅前先记入"重做日志"（RedoData），重连后由定时任务重放。

【源码证据】`nacos:client/.../remote/gprc/NamingGrpcClientProxy.java:154-171,278-284`：

```java
// registerService → registerServiceForEphemeral
redoService.cacheInstanceForRedo(serviceName, groupName, instance);   // 先记账
doRegisterService(...);                                               // InstanceRequest → rpcClient.request
...
redoService.instanceRegistered(serviceName, groupName);               // 成功后销账（标记 registered）
```

Redo 体系在 `redo/` 包：`NamingGrpcRedoService`（`:52`）持两个 map（`registeredInstances`/`subscribes`，`:60-63`），构造时启动 3s 周期的 `RedoScheduledTask`（默认 `DEFAULT_REDO_DELAY_TIME = 3000L`，`nacos:api/.../Constants.java:238`；`:76-80`）。**断连回调 `onDisConnect` 把所有 RedoData 标记为"未注册"**（`:105-120`）；`RedoScheduledTask.run` 先判 `isConnected()`（`:46-57`），然后按 `RedoData.RedoType` 状态机重放：`REGISTER → doRegisterService`、`UNREGISTER → doDeregisterService`、`REMOVE → 清账`；订阅同理（`RedoScheduledTask.java:71-148`）。`RedoType` 由 `expectedRegistered`（用户意图）与 `registered`（实际状态）两个布尔推导（`client/redo/data/RedoData.java:110-124`）——**断线期间用户注销过的服务，重连后不会"复活"**，这个语义就是由这两个布尔实现的。

对照服务端：这正是第三章"连接断 = 实例摘除"能够成立的前提——服务端敢删，是因为约定了客户端会重放。

## 4.3 订阅与推送接收：从 gRPC 回调到你的 EventListener

推送到达链路（线程模型值得细看）：

1. 服务端 `NotifySubscriberRequest` 经双向流到达，`NamingPushRequestHandler` 处理（`client/.../gprc/NamingPushRequestHandler.java:32-49`，注册于 `NamingGrpcClientProxy.start:130-141`）——**运行在 gRPC 流回调线程**：
```java
serviceInfoHolder.processServiceInfo(notifyRequest.getServiceInfo());
return new NotifySubscriberResponse();
```
2. `ServiceInfoHolder.processServiceInfo`（`client/.../cache/ServiceInfoHolder.java:129-173`）：更新 `serviceInfoMap`（`:145`）、diff 出变更、`NotifyCenter.publishEvent(new InstancesChangeEvent(...))`（`:164-169`，failover 开关打开时跳过事件发布）。
3. `InstancesChangeNotifier`（`client/.../event/InstancesChangeNotifier.java:38,106-112`）订阅该事件，按 selector 包装后回调用户 `EventListener`（`NamingListenerInvoker` 支持用户自定义 executor；否则在分发线程同步执行）。

防过载兜底：`ServiceInfoUpdateService.scheduleUpdateIfAbsent`（`client/.../core/ServiceInfoUpdateService.java:108-111`）提供**订阅兜底轮询**，但仅当 `NAMING_ASYNC_QUERY_SUBSCRIBE_SERVICE` 开启（默认 false，`:78-86`）才生效——即官方默认"纯推送"，不接受轮询参数派生的困惑。

## 4.4 心跳的消亡与替代

3.3.0-RC 的 client/api/client-basic 三个模块 `find -name "*Beat*"` 零命中——1.x 的 `BeatReactor`/`BeatTask` 已删除（仅 `PropertyKeyConst.java:94` 留有 `naming.client.beat.threadCount` 兼容常量，`Instance.java:244-249` 留有供服务端心跳兼容层读取的元数据键）。取而代之：

- 客户端空闲 5s 主动 `HealthCheckRequest`（第二章 2.2.2）；
- 服务端 3s 周期反向 `ClientDetectionRequest`（2.3.3）；
- 断开即摘除 + 客户端 redo 重放（4.2）。

**这就是 2.x 官方文档里"临时实例不发送心跳"的源码出处**——健康检查的粒度从"实例"上升到了"连接"。

## 4.5 SCA 集成层：spring-cloud-starter-alibaba-nacos-discovery

### 4.5.1 自动配置全景

Boot 3 风格 `AutoConfiguration.imports` 注册 10 个自动配置类（`sca:spring-cloud-alibaba-starters/spring-cloud-starter-alibaba-nacos-discovery/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`）：`NacosDiscoveryAutoConfiguration`、`NacosServiceRegistryAutoConfiguration`、`NacosDiscoveryClientConfiguration`、`NacosDiscoveryHeartBeatConfiguration`、`NacosReactiveDiscoveryClientConfiguration`、`LoadBalancerNacosAutoConfiguration`、`NacosServiceAutoConfiguration`、`NacosDiscoveryEndpointAutoConfiguration`、`NacosConfigServerAutoConfiguration`、`UtilIPv6AutoConfiguration`。`spring.factories` 只剩两个遗留入口（bootstrap 的 `NacosDiscoveryClientConfigServiceBootstrapConfiguration` 与日志 RunListener）。**Ribbon 相关类在 2023.x 已完全不存在**（全模块 grep -i ribbon 零命中）。

### 4.5.2 注册时机：WebServerInitializedEvent → register

SCA 不自己发明注册时机，而是复用 spring-cloud-commons 的标准机制：`NacosAutoServiceRegistration extends AbstractAutoServiceRegistration<Registration>`（`sca:.../registry/NacosAutoServiceRegistration.java:35-36`）。父类监听 `WebServerInitializedEvent`（Tomcat/Netty 端口确定后触发），子类覆写 `register()`（`:70-79`）：先校验 `isRegisterEnabled()` 与端口，再进入真正的注册器：

【源码证据】`sca:.../registry/NacosServiceRegistry.java:60-90`：

```java
Instance instance = getNacosInstanceFromRegistration(registration);
namingService.registerInstance(serviceId, group, instance);
```

Instance 组装（`:180-190`）把 Spring 的 Registration 转成 nacos Instance：ip/port 来自容器实际绑定的地址，weight/clusterName/enabled/ephemeral 来自 `NacosDiscoveryProperties`（字段见 4.6），metadata 直传。失败处理由 `failFast` 开关决定抛出或仅告警（`:79-89`）——**启动时 Nacos 不可达默认会让应用起不来**。

顺带一提 `NacosRegistration.init()`（`:75-113`）会把 `preserved.heart.beat.interval`、`preserved.heart.beat.timeout`、`preserved.ip.delete.timeout` 塞进 metadata——这些只是**透传给服务端心跳兼容层**的参数（对 gRPC 客户端无效果），别再当成 2.x 的有效心跳配置。

### 4.5.3 服务发现：DiscoveryClient 的两条退路

`sca:.../discovery/NacosDiscoveryClient.java:60-75` `getInstances`：正常调 `NacosServiceDiscovery`（`:56-61`）→ `namingService.selectInstances(serviceId, group, true)`；**失败且 `spring.cloud.nacos.discovery.failure-tolerance-enabled=true` 时退回本地缓存 `ServiceCache`**（属性定义 `:47-48`）——即"Nacos 挂了， Ribbon/LB 还能用最后一份列表撑一阵"。metadata 转换在 `hostToServiceInstance`（`NacosServiceDiscovery.java:87-114`：过滤 disabled/unhealthy，回填 `nacos.weight`、`nacos.cluster` 等）。

### 4.5.4 负载均衡：加权随机，默认关闭

2023.x 只保留 Spring Cloud LoadBalancer 路线（`LoadBalancerNacosAutoConfiguration` + `@LoadBalancerClients(defaultConfiguration = NacosLoadBalancerClientConfiguration.class)`，`sca:.../loadbalancer/LoadBalancerNacosAutoConfiguration.java:30-40`）。算法实现在 `NacosBalancer.getHostByRandomWeight3`（`sca:.../balancer/NacosBalancer.java:58-82`）：从 `ServiceInstance` 的 metadata 里取回 `nacos.weight`，委托 nacos-client 的 `Balancer.getHostByRandomWeight`——**按权重随机，不是轮询**。同集群优先过滤在 `NacosLoadBalancer.getInstanceResponse`（`sca:.../loadbalancer/NacosLoadBalancer.java:142-160`，匹配 metadata `nacos.cluster`）。**默认不启用**：`@ConditionalOnProperty("spring.cloud.loadbalancer.nacos.enabled", havingValue = "true")`（`ConditionalOnLoadBalancerClientConfiguration.java:28`），不配就使用 Spring Cloud LoadBalancer 自带的 RoundRobin。

### 4.5.5 下线与优雅停机

两条路：① spring-cloud-commons 的 `AbstractAutoServiceRegistration.stop()`（SmartLifecycle stop 阶段）→ `NacosServiceRegistry.deregister`（`sca:.../registry/NacosServiceRegistry.java:92-116`，调 `namingService.deregisterInstance`）；② `NacosGracefulShutdownDelegate` 监听 `ContextClosedEvent`（`:34,55-65`），stop 后按 `gracefulShutdownWaitTime`（默认 10s，`NacosDiscoveryProperties.java:237`）睡眠，给上游一个"从注册表消失 → 已建立流量收敛"的窗口（`supportsAsyncExecution() = false` 保证阻塞在关闭钩子里，`:83-87`）。配合第三章"连接断即摘除"，进程被 kill -9 时注册表也会在秒级收敛——优雅下线是"提前摘除 + 流量收尾"，不是唯一手段。

### 4.5.6 NacosWatch 与 HeartBeatPublisher：默认关闭的历史遗物

1.x 时代 `NacosWatch` 是"启动即订阅自身服务"的组件，2023.x 里类还在但**默认关闭**（`sca:.../discovery/NacosDiscoveryClientConfiguration.java:55-65`，注释明确 "NacosWatch is no longer enabled by default"，需 `spring.cloud.nacos.discovery.watch.enabled=true`；作用退化为订阅自身服务、同步自身 metadata，`:94-119`）。`NacosDiscoveryHeartBeatPublisher`（每 30s 发 spring-cloud-commons 的 `HeartbeatEvent`）同样默认关闭，仅为 Spring Cloud Gateway 的 discovery locator、Spring Boot Admin 等依赖 `HeartbeatEvent` 的旧组件保留（`NacosDiscoveryHeartBeatConfiguration.java:60-83` 的 `AnyNestedCondition`）。**初学者不必启用它们**。

## 4.6 参数手册：NacosDiscoveryProperties 关键字段

（`sca:.../nacos/NacosDiscoveryProperties.java`，前缀 `spring.cloud.nacos.discovery`；公共前缀 `spring.cloud.nacos.username/password` 会自动回落注入 `:679-684`）

| 字段 | 默认 | 说明 |
|---|---|---|
| server-addr | — | 地址列表（逗号分）；或用 endpoint 指向地址服务器 |
| namespace / group | 空 / DEFAULT_GROUP | 命名空间（隔离环境）、服务分组 |
| cluster-name | DEFAULT | 机房/单元标识，LB 同集群优先 |
| weight | 1 | 实例权重（配合 4.5.4 的加权随机） |
| ephemeral | true | 临时实例（连接断即摘除）；false 走持久实例+服务端探测 |
| register-enabled | true | 是否注册自身（纯消费者可关） |
| fail-fast | true | 注册失败是否抛异常阻断启动 |
| failure-tolerance-enabled | false | 发现失败时是否退回本地缓存 |
| heart-beat（watch）相关 | — | `watch.enabled`/`heart-beat.enabled` 默认 false（4.5.6） |
| graceful-shutdown-wait-time | 10000ms | 下线后流量收敛窗口 |
| username/password/accessKey/secretKey | — | 鉴权凭据（第八章） |

## 4.7 本章小结

- `NamingService` = 门面：`NamingClientProxyDelegate` 按 ephemeral 与能力协商路由 gRPC/HTTP；gRPC 通道持有一条独立长连接。
- **redo 机制**是 2.x/3.x 注册可靠性的核心：先记账、后注册、成功销账；断连全部标记未完成，3s 周期重放；注销意图优先于注册意图。
- 订阅推送的线程模型：gRPC 流回调线程 → 更新 ServiceInfoHolder → NotifyCenter 事件 → 用户 executor 回调；官方默认无轮询兜底。
- SCA 侧薄到只有"四个动作"：WebServerInitializedEvent 触发注册、DiscoveryClient 查询 + 容灾缓存、LoadBalancer 加权随机（默认关闭）、ContextClosedEvent 优雅下线。
- NacosWatch/HeartBeatPublisher 是 1.x 遗物，默认关闭；`preserved.*` metadata 只影响 HTTP 心跳兼容层，对 gRPC 客户端无效。


---
# 五、配置中心服务端：config 模块的存储、查询责任链与两代变更通知

## 5.1 模块定位与包结构

**先白话**：config 模块解决的是"配置存在哪、怎么查、改了怎么通知"。它的架构要点一句话：**DB 是唯一真源，每个节点的内存/磁盘缓存是加速层**——写永远写 DB，读永远读缓存（快），两者靠 dump 对齐。这与 naming"内存即真源（临时实例）"形成鲜明对比，也是为什么 config 集群不需要 Distro（7.3.4）。

3.x 的 config 是单一 Maven 模块 `nacos-config`，代码在 `com.alibaba.nacos.config.server` 下：

| 包 | 职责 |
|---|---|
| `controller/v3` | V3 管理 API（ConfigControllerV3/HistoryControllerV3 等）；v1 的 `ConfigController` 已删除，`ConfigServletInner` 仅余遗留代码（无生产调用方） |
| `service` | `ConfigOperationService`（写）、`ConfigCacheService`（内存缓存）、`LongPollingService`（长轮询兼容）、`HistoryService`、`ClientTrackService`、`SwitchService` |
| `service/dump` | DumpService（Embedded/External 两实现）、DumpProcessor、DumpChangeConfigWorker（增量对账）、disk（raw_disk/RocksDB 落盘） |
| `service/notify` | AsyncNotifyService：集群节点间 gRPC 变更通知 |
| `service/query` | 查询责任链（ConfigQueryChainService + handler 链） |
| `service/repository` | DB 持久层接口 + `embedded`（Derby）/`extrnal`（外部 DB）双实现（目录名拼写即如此） |
| `remote` | gRPC handler：ConfigQuery/ConfigPublish/ConfigChangeBatchListen/ConfigChangeClusterSync + RpcConfigChangeNotifier |
| `model/gray` | 统一灰度规则（BetaGrayRule/TagGrayRule/…） |

HTTP 契约常量：`BASE_PATH = "/v1/cs"`（`constant/Constants.java:116`，遗留）；3.x 客户端 API 走 gRPC，管理 API 走 `/v3/cs/admin/config`（`:136`）。

## 5.2 存储模型：DB 单源 + 每节点双缓存

一条配置在服务端有三个副本位：

| 副本位 | 内容 | 代码位置 |
|---|---|---|
| DB（真源） | config_info / config_info_gray / his_config_info 三张表 + derby/mysql 双实现 | `service/repository/ConfigInfoPersistService.java` + `embedded/`、`extrnal/` |
| 节点内存 | `ConfigCacheService`：`ConcurrentHashMap<String/*groupKey*/, CacheItem>`，只存 **MD5/lastModified/encryptedDataKey 等元数据，不存正文**（`:66`） | `service/ConfigCacheService.java` |
| 节点磁盘 | 配置正文：`{nacos.home}/data/config-data/...`（raw_disk）或 RocksDB（`config_disk_type=rocksdb` 切换） | `service/dump/disk/ConfigRawDiskService.java:47-55`、`ConfigDiskServiceFactory.java:38-51` |

groupKey 是配置唯一键：`urlEncode(dataId)+"+"+urlEncode(group)[+"+"+urlEncode(tenant/namespace)]`（`utils/GroupKey2.java:39-49`）。**"namespace/group/dataId 三元组定位一条配置"这句话，在代码里的形态就是这个 groupKey 字符串。**

DB 选择由 `DynamicDataSource`（`nacos:persistence/.../DynamicDataSource.java:41-52`）按部署形态装配：单机内嵌 Derby（`LocalDataSourceServiceImpl.java:56` EmbeddedDriver，库目录 `{nacos.home}/data/derby-data`，schema 在 `META-INF/derby-schema.sql`）；集群外置 MySQL（`ExternalDataSourceServiceImpl`）。持久层双实现以 `@Conditional(ConditionOnEmbeddedStorage/ConditionOnExternalStorage)` 二选一（`embedded/EmbeddedConfigInfoPersistServiceImpl.java:96-98`）。

## 5.3 写入链路：publishConfig

**先白话**：发布一次配置 = 校验 →（可选加密）→ 写 DB（支持 CAS 防并发覆盖）→ 发一个 `ConfigDataChangeEvent`。之后的一切（dump、通知、推送）都是这个事件驱动的下游。

【源码证据】`service/ConfigOperationService.java:84-178` `publishConfig`：

```java
if (StringUtils.isNotBlank(configRequestInfo.getBetaIps())) {        // :102-109  1.x 兼容：betaIps 参数 → BetaGrayRule
    configForm.setGrayName(BetaGrayRule.TYPE_BETA); ...
} else if (tag 非空) { ... TagGrayRule.TYPE_TAG + "_" + tag ... }     // :111-118  tag 参数 → TagGrayRule
// CAS 分支（md5 一致才写，防止两个管理员互相覆盖）：insertOrUpdateCas      :123-135
// 普通分支：insertOrUpdate                                          :136-150
ConfigChangePublisher.notifyConfigChange(new ConfigDataChangeEvent(dataId, group, namespaceId,
    configOperateResult.getLastModified()));                          // :159-162
```

入口在 V3 管理 API `ConfigControllerV3.publishConfig`：写前经 `EncryptionHandler.encryptHandler(dataId, content)` 加密插件钩子（返回 `encryptedDataKey` 随配置存储，`:211-218`），`betaIps` 从请求头读入（`:224-225`）。删除 `deleteConfig` 同样以事件收尾（`:295-315`）。

`ConfigChangePublisher.notifyConfigChange`（`service/ConfigChangePublisher.java:36-41`）有个重要短路：**内嵌存储（Derby）的集群模式下直接 return**——因为该模式下写 DB 本身就走了 JRaft 共识（见 5.10 与 7.4），无需再广播。外置 DB 模式则 `NotifyCenter.publishEvent(event)`，事件被两个订阅者接住：本节点 dump（`DumpService`，5.4）与集群通知（`AsyncNotifyService`，5.7）。

## 5.4 Dump 体系：DB → 缓存的对齐机器

**先白话**：每个节点启动时把 DB 全量 dump 进本地缓存；之后每次收到变更事件 dump 单条；另外加两道保险——每 6 小时全量对账、每 30 秒增量对账。这就是 config 集群"最终一致"的全部实现。

【源码证据】`service/dump/DumpService.java`（抽象类）：

- 构造期装配 `DumpProcessor/DumpAllProcessor/DumpAllGrayProcessor` 并订阅 `ConfigDataChangeEvent`（`:104-140`）；收到事件 → `DumpRequest` → `dump()` 入任务队列（`:128-152,298-345`）。
- `dumpOperate()`（`:212-267`）启动时：集群模式先 `clearAll()` 清盘再 `dumpAllConfigInfoOnStartup`（`:269-291`）；随后调度 **6 小时全量**（`DUMP_ALL_INTERVAL_IN_MINUTE = 6*60`，`:83-88`）与 **3.x 新增的增量兜底** `DumpChangeConfigWorker` / `DumpChangeGrayConfigWorker`（`:243-255`），历史清理每 10 分钟（`:258-261`）。
- `DumpChangeConfigWorker.run`（`DumpChangeConfigWorker.java:68-178`）：分页 100 条（`:59`）扫 `findDeletedConfig`（清理已删除）与 `findChangeConfig`（md5/时间戳有变则重新 `ConfigCacheService.dump`）——**DB 里被绕过事件改掉的数据（比如直接改库），最多 30 秒（`dumpChangeWorkerInterval` 默认 30s，`PropertyUtil.java:316-318`）也会被对齐**。

单条 dump 的落点 `DumpConfigHandler.configDump`（`:52-118`）：formal → `ConfigCacheService.dump`；gray → `dumpGray`；特殊 dataId（客户端 IP 白名单、运行时开关）加载进内存生效。`ConfigCacheService.dumpWithMd5`（`service/ConfigCacheService.java:84-166`）做三件事：时间戳过期检查 → 正文写盘 `saveToDisk` → `updateMd5` 更新内存元数据并**发布 `LocalDataChangeEvent(groupKey)`**（`:394-405`）。

**`LocalDataChangeEvent` 是本节点一切"客户端通知"的总闸**：5.6 的两代通知机制都由它驱动——但它只在"本节点 dump 成功"时发，这正是"推通知、拉数据"设计里通知方与数据方对齐的接缝。

## 5.5 查询链路：ConfigQueryChainService 责任链

**先白话**：读配置不是"查库返回"，而是一条五节点的责任链：加锁取缓存元数据 → 确定内容类型 → 匹配灰度 → 兜底特殊 tag → 读正式内容；md5 相同直接 304，根本不读正文。

【源码证据】链序（`service/query/DefaultConfigQueryHandlerChainBuilder.java:33-40`）：

```
ConfigChainEntryHandler → ConfigContentTypeHandler → GrayRuleMatchHandler → SpecialTagNotFoundHandler → FormalHandler
```

- `ConfigChainEntryHandler.handle`（`query/handler/ConfigChainEntryHandler.java:50-80`）：`GroupKey2.getKey` → `ConfigCacheService.tryConfigReadLock(groupKey)`（读锁重试 10 次 × 1ms，`ConfigCacheService.java:647-661`）→ 取 `CacheItem`；无缓存返回 `CONFIG_NOT_FOUND`，锁冲突返回 `CONFIG_QUERY_CONFLICT`。
- `GrayRuleMatchHandler`（`:44-94`）：遍历 `cacheItem.getSortConfigGrays()`（按 priority 排序的灰度规则）用请求的 `appLabels`（`ClientIp`、`Vipserver-Tag` 等，由 `DefaultChainRequestExtractor.java:48-62` 从请求头/参数抽取）匹配；命中且 md5 相同直接 304，否则从磁盘读**灰度**正文 `getGrayContent`（`:78-80`）。
- `FormalHandler`（`:43-84`）：localMd5 与请求携带 md5 相同 → `CONFIG_NOT_MODIFIED`（304 优化，`:55-66`，3.3 新增配合客户端条件查询）；否则 `getContent(...)` 读正式正文（`:68`）。

调用方两处：HTTP 遗留入口 `ConfigServletInner.doGetConfig`（`:146-149`，已无生产调用方，响应头仍带 `CONTENT_MD5`/`Encrypted-Data-Key` 等，`:334-380`）与 gRPC 主链路 `ConfigQueryRequestHandler`（`remote/ConfigQueryRequestHandler.java:95-121`，304 响应 + trace 标记 `grpc`）。**V3 管理 API 的 `getConfig` 是例外**：它绕过缓存直查 DB 再解密（`ConfigControllerV3.java:158-182`）——管理端要的是"真源视图"，不是"客户端视图"。

## 5.6 变更通知的两代实现

### 5.6.1 第一代：HTTP 长轮询（服务端保留，服务 1.x 老客户端）

【源码证据】`service/LongPollingService.java`：

- `addLongPollingClient`（`:177-226`）：先比对 md5，有变更立即返回（"instant"，`:184-191`）；否则 `startAsync` 且 `setTimeout(0)` 自己管理超时（`:200-203`）；挂载超时 = **max(10000, 客户端 header 值 − 500ms)**（`:215-222`）——旧客户端传 30000ms，于是有了著名的 **29.5 秒**：500ms 提前返回余量用于负载均衡，10000ms 是下限防滥用。
- `ClientLongPolling`（`:327-433`）：超时任务内联在 schedule lambda 里（3.x 无独立 TimeoutDataTask 类），到点返回空响应（表示"没变化"）并 `asyncContext.complete()`。
- `DataChangeTask`（`:280-316`）：由 `LocalDataChangeEvent` 触发，遍历挂载中的 `allSubs`，命中的客户端被移除并**只回传变更的 groupKey 列表**——内容仍需客户端回源拉取。

挂载数/频率治理走连接级限流（`checkLimit` → `ConnectionCheckRequest(ip, appName, "LongPolling")`，`:228-237`；超限延迟 1~3s 返回 503，`:207-210`）。

### 5.6.2 第二代：gRPC 批量监听（2.x/3.x 主链路）

【源码证据】`remote/ConfigChangeBatchListenRequestHandler.java:59-97`：客户端发 `ConfigBatchListenRequest`（每条配置的 groupKey + 本地 md5，listen=true/false），服务端把映射写入 `ConfigChangeListenContext` 的两张表（`remote/ConfigChangeListenContext.java:43-48`）：

```java
ConcurrentHashMap<String, HashSet<String>> groupKeyContext;      // groupKey → 订阅它的 connectionIds
ConcurrentHashMap<String, HashMap<String, ConfigListenState>> connectionIdContext;  // connectionId → 它订阅的 groupKey
```

响应里**立即返回已变更**的配置（`isUptodate` 不通过的，`:84-88`）。此后每次 `LocalDataChangeEvent` 由 `RpcConfigChangeNotifier` 处理（`remote/RpcConfigChangeNotifier.java:51-117`）：`configChangeListenContext.getListeners(groupKey)` 找到连接 → 发 `ConfigChangeNotifyRequest` → 失败按 `maxPushRetryTimes`（默认 **50**，`configuration/ConfigCommonConfig.java:34`）重试（`RpcPushTask:137-203`）。客户端收到通知后回源查询（第六章 6.1.3）。

**一源两流**：`LocalDataChangeEvent` 同时驱动 `LongPollingService`（构造器订阅，`:252-267`）与 `RpcConfigChangeNotifier`——两代机制并存且共用同一数据闸门，这就是 1.x 客户端与 3.x 客户端可以在同一个集群混跑的原因。

## 5.7 集群间同步：gRPC 通知 + 对账

**先白话**：管理员把请求发到了节点 A 写了 DB；节点 B/C 的缓存还不知道。谁告诉它们？节点 A 广播 gRPC 同步请求，B/C 收到后各自执行一次 dump。

【源码证据】`service/notify/AsyncNotifyService.java`：

- 收到 `ConfigDataChangeEvent` → 对 `memberManager.allMembersWithoutSelf()` 每个成员生成 `NotifySingleRpcTask`（`:101-124`），任务间隔 3000ms（`:224`）。
- `executeAsyncRpcTask`（`:142-183`）：构造 `ConfigChangeClusterSyncRequest` 发往成员 9849 集群口；失败按**平方退避重试 6 次**：`delay = 500 + failCount² × 1000` ms（常量 `:60-64`，计算 `:371-378`），回调超时 1000ms（`:303-305`）。
- 接收端 `ConfigChangeClusterSyncRequestHandler`（`remote/`，`:45-74`）：`@InvokeSource(LABEL_SOURCE_CLUSTER)` 限定只能来自集群连接，处理就是一句 `dumpService.dump(DumpRequest.create(...))`。

1.x 的 HTTP `/v1/cs/notify` + `RunningConfigUtils` + `NotifyTask` 在 3.x 已删除（grep 零命中）——集群同步与业务通信统一收敛到 gRPC。

## 5.8 灰度发布：统一 GrayRule 模型

**先白话**："灰度"就是"同一份 dataId 存在多个版本，按匹配条件挑版本"。3.x 把 1.x 的 betaIps（指定 IP 生效）和 tag（指定标签生效）统一成一个规则体系：每种规则是 SPI 注册的一个类，带优先级，命中高优先级规则就用它的内容。

【源码证据】规则模型 `model/gray/`：

- `ConfigGrayPersistInfo`（`:27-33`）：`type / version / expr / priority` 四元组，Gson 序列化后存 `config_info_gray` 表（写入经 `ConfigInfoGrayPersistService`，`ConfigOperationService.publishConfigGray:189-268`；每个 dataId 最多 10 个灰度版本，`DEFAULT_MAX_GRAY_VERSION_COUNT = 10`，`:284`，可配 `nacos.config.gray.version.max.count`）。
- `BetaGrayRule`（`:34-69`）：type=`beta`，expr 逗号分隔 IP 列表，`match` 就是 `labels.get("ClientIp") ∈ betaIps`；**priority = Integer.MAX_VALUE**。1.x 的 `betaIps` 请求头在 `ConfigOperationService.publishConfig:102-109` 被翻译成它。
- `TagGrayRule`（`:36-63`）：type=`tag`，匹配 `Vipserver-Tag` 标签，priority = MAX−1；另有 `singletag`/`multitag` 扩展规则。
- 匹配顺序 = priority 排序后逐一尝试（`GrayRuleMatchHandler`，5.5）；正文按 grayName 落盘 `gray-data` 目录（`ConfigRawDiskService.java:52-55`）。
- 管理操作：停止 beta = 删除 gray 记录 + 通知变更（`ConfigControllerV3.stopBeta:396-421`）；通用灰度 API `/gray`（`:458+`）。

与 Spring Cloud Config 的"label=git 分支"不同，Nacos 灰度是"同 key 多版本 + 匹配规则"，且只对**查询侧**生效（DB 里正式配置永远只有一份）。

## 5.9 历史与安全辅助

- **历史**：`his_config_info` 表由写入链路自动维护；查询 API `HistoryControllerV3`（list/详情/previous/按 namespace 全量，`:75-172`；分页上限 500）。**模块内没有"一键回滚"端点**——控制台的回滚是"取 previous 内容再发布一次"，走普通发布链路。
- **加密**：模块不含加解密实现，只有两个钩子（写 `EncryptionHandler.encryptHandler`、读 `decryptHandler`），密文与 `encryptedDataKey`（数据密钥的密文）一起存储；加解密算法由 `nacos-encryption-plugin` 提供。
- **导入导出/克隆**：`importAndPublishConfig`（zip 上传，冲突策略 overwrite/skip/abort，`ConfigControllerV3.java:574-599`）、`exportConfig`（`:735-737`）、`cloneConfig`（跨 namespace，`:784-798` + `ConfigCloneService`）。

## 5.10 本章小结

- config 的存储是"**DB 单源 + 节点双缓存**"：内存 CacheItem 只存元数据，正文在磁盘；一致性靠 dump（事件触发单条 + 6h 全量 + 30s 增量对账）三重保障。**因此 config 集群没有脑裂问题，挂 N 个节点只损失读吞吐不损失数据。**
- 写入 = DB（CAS 可选）+ `ConfigDataChangeEvent`；内嵌 Derby 集群模式由 JRaft 承担同步，此时变更发布器短路。
- 查询走五节点责任链（读锁 → 灰度匹配 → 304 → 磁盘正文）；管理端 API 绕过缓存直查 DB。
- 变更通知两代并存：长轮询（29.5s，回传 groupKey 列表）与 gRPC 批量监听（双向注册表 + 50 次推送重试），共用 `LocalDataChangeEvent` 总闸；客户端拿到通知后仍要回源拉内容——"推通知、拉数据"。
- 灰度统一为 GrayRule（beta=MAX_VALUE、tag=MAX−1，存 config_info_gray 表，最多 10 版本）；历史无回滚端点，回滚即重新发布。


---
# 六、配置中心客户端与 Spring Cloud 集成（nacos-config）

## 6.1 nacos-client：NacosConfigService 与 ClientWorker

### 6.1.1 getConfig：四级容灾的读取链

**先白话**：`getConfig` 优先级是——本地 failover 文件（运维强插）→ 内存一致快照 → 服务端（带 304 条件查询）→ 磁盘快照（服务端挂了用旧值）。前三层保证"快"，后一层保证"死也死得优雅"。

【源码证据】`nacos:client/.../config/NacosConfigService.java`：

```java
// getConfig:106-112
ClientWorker.LocalConfigContent localContent = resolveLocalConfigContent(dataId, group);
ConfigResponse response = getConfigInnerWithResponse(...);
```

- `resolveLocalConfigContent`（`:158-207`）：先取 `CacheData.getConsistentSnapshot()`（内存内容与 md5 的一致视图，`CacheData.java:259-265`），再读磁盘 snapshot（`:181-202`）。
- `getConfigInnerWithResponse`（`:497-563`）：failover 文件优先（`LocalConfigInfoProcessor.getFailover`，`:509-523`）→ `worker.getServerConfig(...)`（`:529-531`）→ 异常回落 snapshot（`:547-562`）。
- 服务端查询 `ClientWorker.queryConfigInner`（`ClientWorker.java:1527-1622`）：`ConfigQueryRequest` 携带本地 md5（`request.setLocalMd5(localMd5)`，`:1533-1535`，**3.3 的 304 条件查询**）；成功后 `LocalConfigInfoProcessor.saveSnapshot` 落盘（`:1542`）；服务端返回 `CONFIG_NOT_MODIFIED` 时复用请求前内容（`:1561-1598`）；`CONFIG_NOT_FOUND` 时清空 snapshot（`:1599-1603`，保证"配置删了"对客户端可见）。

### 6.1.2 CacheData：一条配置在客户端的"家"

`client/.../impl/CacheData.java`（772 行）是理解监听回调的关键：

- 字段：`content`（正文）、`md5`（与正文同步更新，`setContent:216-222` / `setConfigContentAndKey:234-238`）、`listeners`（`CopyOnWriteArrayList<ManagerListenerWrap>`，`:135`，wrap 里记着每个 listener 上次收到的 `lastCallMd5`）。
- `checkListenerMd5`（`:441-447`）：md5 ≠ `lastCallMd5` 才回调——**回调的触发条件是"md5 变了"，不是"收到通知了"**，天然去重。
- 回调线程模型（`:596-612`）：优先用 listener 自带 executor（`listener.getExecutor()`），否则在调用线程同步执行；`inNotifying` CAS 防重入（`:528-534`）；慢回调监控告警阈值 60s（`:59`）。**这就是"listener 里写阻塞代码会拖垮配置刷新"的源码出处。**

### 6.1.3 监听循环：铃声驱动的 gRPC 批量监听

**先白话**：3.x 客户端不再"每 50ms 检查一次 md5"（那是 1.x 的 `checkListenerMd5` 定时任务时代），也不再有 HTTP 长轮询（`grep LongPollingRunnable` 零命中）。取而代之的是一个"铃声驱动"的循环：铃声一响（有新 listener / 收到服务端通知 / 到期全量对账），就向服务端发一次批量监听请求。

【源码证据】`client/.../impl/ClientWorker.java`：

- `addListener` → `addTenantListeners`（`:199-217`）写 CacheData 后 `agent.notifyListenConfig()`——**摇铃**。
- 监听循环 `startInternal`（`:1031-1055`）：单线程 `listenExecutor`（线程名 `com.alibaba.nacos.client.listen-executor`）阻塞在 `listenExecutebell.poll(5, SECONDS)`——最长 5 秒自动响一次，收到通知立即响。
- `executeConfigListen`（`:1068-1149`）：对每个 CacheData 先 `checkLocalConfig`（检查 failover 文件是否出现/消失，`:1129-1149`），与服务器一致的调 `checkListenerMd5`；再把**未与服务器对齐**的配置组装成 `ConfigBatchListenRequest`（`buildConfigRequest:1406-1415`）。
- 响应处理 `refreshContentAndCheck`（`:1201-1228`）：对变更的配置 `queryConfigInner` 拉取新内容 → 更新 CacheData → `checkListenerMd5()` → 用户回调被触发。
- 服务端主动推送 `ConfigChangeNotifyRequest` 由 `initRpcClientHandler` 注册处理（`:940-947`）：只置 `receiveNotifyChanged=true` 并摇铃（`:907-927`）——**通知只负责"叫醒"，数据永远回源拉**，彻底规避推送乱序。
- 全量对账：`ALL_SYNC_INTERNAL = 3 * 60 * 1000L`（`:828`）——每 3 分钟把所有监听配置完整对一遍，兜住通知丢失。
- 断连处理：`consistentWithServer=false`（`:973-991`），重连后全量重挂。

对照服务端（5.6.2）：客户端的批量监听请求与服务端的双向注册表一一对应，3 分钟对账则对应服务端 dump 的 6h/30s 对账——两端都是"事件驱动 + 周期兜底"的同一套哲学。

### 6.1.4 本地容灾：snapshot 与 failover 两套文件

目录（`client/.../impl/LocalConfigInfoProcessor.java:62-67,198-218`）：`{JM.SNAPSHOT_PATH|user.home}/nacos/config/{serverName}_nacos/data/` 下：

- `snapshot[-tenant]/...`：每次成功拉取自动落盘（被动，容灾读）；
- `config-data[-tenant]/...`：**failover 文件**（主动，运维强插）——文件存在时客户端无视服务端，直接用它的内容。

naming 侧还有对称的 `FailoverReactor`（`client/.../naming/backups/FailoverReactor.java`，支持把某个服务的实例列表强制改写）；config 侧 3.x 已把 `FailoverReactor` 类删除，改为监听循环内惰性检查（6.1.3 的 `checkLocalConfig`）。

## 6.2 SCA 集成层：spring-cloud-starter-alibaba-nacos-config

### 6.2.1 两条接入路线

**先白话**：Spring Boot 2.4 之前配置中心靠 bootstrap 上下文（先拉远程配置再起主容器）；2.4 起官方转向 `spring.config.import`。SCA 2023.x 两条都支持，但主推后者，并且在"忘写 import"时帮你检查。

`sca:spring-alibaba-nacos-config/src/main/resources/META-INF/spring.factories` 同时注册了：

- legacy：`org.springframework.cloud.bootstrap.BootstrapConfiguration = NacosConfigBootstrapConfiguration`（内部经 `NacosPropertySourceLocator` 拉配置，`@Conditional(NacosConfigEnabledCondition)`，`nacos.config.enabled` 默认 true）；
- config-data：`ConfigDataLocationResolver = NacosConfigDataLocationResolver` + `ConfigDataLoader = NacosConfigDataLoader`；
- 检查器：`EnvironmentPostProcessor = NacosConfigDataMissingEnvironmentPostProcessor`——非 bootstrap 启动且没写 `spring.config.import=nacos:` 时抛 ImportException，提示添加 import 或 `spring.cloud.nacos.config.import-check.enabled=false`（`:49-63` + `ImportExceptionFailureAnalyzer:70-88`）。

config-data 路线的语义（`sca:.../configdata/NacosConfigDataLocationResolver.java`）：

- `PREFIX = "nacos:"`（`:62`）；`spring.config.import=nacos:app.properties` 里的路径就是 dataId（可带 `?group=xx&refreshEnabled=false` 参数）；
- `resolveProfileSpecific`（`:143-159`）向 BootstrapContext 注册 `NacosConfigProperties` 与 `NacosConfigManager`（ConfigService 单例的持有者，`NacosConfigManager.createConfigService:65-79`，失败抛 `NacosConnectionFailureAnalyzer` 会友好提示）；
- 加载在 `NacosConfigDataLoader.doLoad`（`:70-99`）→ `pullConfig`（`:139-147`）`configService.getConfig(dataId, group, timeout)` → `NacosDataParserHandler.parseNacosData` 按后缀解析（properties/yaml/json）；
- `preference=LOCAL/REMOTE`（`prefix.config.preference`，默认 LOCAL）决定远程配置与本地 profile 配置的优先级，REMOTE 时加 `Option.PROFILE_SPECIFIC`（`:101-137`，fix issues/2455）。

### 6.2.2 加载顺序：locator 只加载应用自身三类 dataId（2023.x 重要变化）

【源码证据】`sca:.../client/NacosPropertySourceLocator.java`（legacy 路线）：

```java
// loadApplicationConfiguration:106-124
loadNacosDataIfPresent(composite, dataIdPrefix, group, fileExtension, true);            // 默认 dataId（spring.application.name）
loadNacosDataIfPresent(composite, dataIdPrefix + DOT + fileExtension, ...);             // name.properties
for (String profile : env.getActiveProfiles()) { ... name-profile.properties ... }      // name-prod.properties
// addFirstPropertySource:178-187 —— 后加载者插到最前（优先级最高）
```

**注意**：本分支 HEAD 中 shared-configs / extension-configs **已不再被 locator 加载**——`loadNacosConfiguration`/`checkConfiguration` 成为死代码（文件内无调用点），`getSharedConfigs()/getExtensionConfigs()` 在 starters 内无消费方（字段仍在 `NacosConfigProperties.java:186-191`）。**2023.x 的共享配置推荐用多条 `spring.config.import=nacos:xxx` 表达**（import 语句本身有顺序语义）。拉取超时来自 `NacosConfigProperties.timeout`（默认 3000ms，`:128`）；`NacosPropertySourceBuilder.loadNacosData`（`spring-alibaba-nacos-config/.../client/NacosPropertySourceBuilder.java:82-117`）优先消费 `NacosSnapshotConfigManager` 里 config-data 路线预取的快照，避免 legacy/config-data 双路线重复拉取（`:86-95`）。

### 6.2.3 变更刷新：从 Nacos 回调到 @RefreshScope

**先白话**：Nacos 的配置回调只会改"nacos 自己的 PropertySource"里的值；要让 `@Value`/`@ConfigurationProperties` 重新绑定，还必须走 Spring Cloud 的 Environment 重载 + Bean 刷新。SCA 的活儿就是把两边接起来。

【源码证据】`sca:spring-alibaba-nacos-config/.../refresh/NacosContextRefresher.java`：

- `ApplicationReadyEvent` 后（`:49-50,86-91`）遍历 `NacosPropertySourceRepository.getAll()`（`collectNacosPropertySource` 在每次构建 PropertySource 时登记，key=`dataId,group`，`:66-81`），对 `refreshEnabled` 的注册 nacos listener（`:101-112`）；
- 回调里（`registerNacosListener:114-153`）：记录刷新历史与计数 → 发布 **自定义 `NacosConfigRefreshEvent`**（`:128-132`）；
- `NacosConfigRefreshEventListener`（starter 侧，`supportsEventType:41-43`）把它**转译**为 spring-cloud-context 的 `org.springframework.cloud.endpoint.event.RefreshEvent`（`:51-56`）；
- 之后的链路属于 spring-cloud-context（本文系列《Spring Cloud Config.md》第四章已详解）：`RefreshEventListener` → `ContextRefresher.refresh()` → Environment 重建 + `@RefreshScope` Bean 销毁重建。**Nacos 比之 Config Server 的本质优势在这里显形：不需要 Bus 广播"该刷新了"——每台实例自己从长连接收到变更通知。**

备援路径 `NacosPropertySourceRefreshListener`（`:96-114`）：转译器 bean 不存在时直接替换 PropertySource（兼容极端裁剪场景）。

### 6.2.4 注解与辅助

- **`@NacosValue` 已不存在**（全仓 grep 零命中）；对应能力由 `annotation` 包的 `NacosAnnotationProcessor`（`@NacosConfig`、`@NacosConfigKeysListener` 等）承担，由 `NacosConfigAutoConfiguration` 注册（`:64-67`）。日常场景用 `@Value` + `@RefreshScope` 即可，不必启用。
- `NacosPropertySourceRepository`（原 nacos-context 模块已并入）是"已加载 PropertySource 清单"，供监听注册用。
- 属性前缀 SPI：`NacosPropertiesPrefixer`（`sca:.../NacosPropertiesPrefixer.java:40-64`）默认 `spring.cloud.nacos`（`SpringCloudNacosPropertiesPrefixProvider`），可整体改前缀。

## 6.3 参数手册：NacosConfigProperties 关键字段

（前缀 `spring.cloud.nacos.config`；`overrideFromEnv:202-226` 支持 `${prefix}.username/password` 公共前缀）

| 字段 | 默认 | 说明 |
|---|---|---|
| server-addr / namespace / group | — / 空 / DEFAULT_GROUP | 同 discovery；namespace 为 "public" 时自动置空（fix issues/2872，`:577-585`） |
| file-extension | properties | 应用 dataId 后缀（决定解析器） |
| timeout | 3000ms | 拉取配置的请求超时 |
| refresh-enabled | true | 是否注册变更监听（关掉即"只读不刷"） |
| import-check.enabled | true | 缺 `spring.config.import` 时是否报错 |
| preference | LOCAL | config-data 路线的本地/远程优先级 |
| encode / accessKey / secretKey / ramRoleName / contextPath / clusterName | — | 编码与鉴权/接入参数 |
| shared-configs / extension-configs | — | 字段仍在但 2023.x locator 已不消费（6.2.2），用多条 import 替代 |

## 6.4 本章小结

- 客户端读取是四级容灾（failover 文件 → 内存快照 → 服务端 304 条件查询 → 磁盘快照）；监听是"铃声驱动 + gRPC 批量监听 + 3 分钟对账"，通知只叫醒、数据回源拉。
- 回调触发条件是 CacheData 的 **md5 变化**（天然去重），回调默认在分发线程同步执行——listener 必须轻。
- SCA config-data 路线：`spring.config.import=nacos:xxx` + Resolver/Loader；legacy 路线只加载应用自身三类 dataId，shared/extension 已死代码，共享配置用多条 import。
- 刷新链路：nacos 回调 → `NacosConfigRefreshEvent` → `RefreshEvent` → spring-cloud-context 重载 Environment + @RefreshScope；与 Config Server+Bus 相比，**变更通知由长连接内生的推送到每台实例，无需外部 MQ**。


---
# 七、集群一致性：Distro（AP）、JRaft（CP）与成员管理

## 7.1 模块定位

**先白话**：Nacos 集群里只有两类数据要"多节点一致"：临时实例数据（允许最终一致，可用性优先）和持久实例/元数据（不允许丢，一致性优先）。配置数据走第三条路（DB 单源）。`consistency` 模块只定义抽象（`APProtocol`/`CPProtocol`/`RequestProcessor4CP`，`nacos:consistency/src/main/java/com/alibaba/nacos/consistency/cp/CPProtocol.java:22-33`），**两个协议的实现都在 core 模块**：`core/distributed/distro`（自研 Distro）与 `core/distributed/raft`（封装 SOFAJRaft）。`ProtocolManager` 负责两者的生命周期与成员变更分发（`nacos:core/.../ProtocolManager.java:84-94,161-179`）。

**注意一个版本事实**：1.x/2.x 里那个按 `Service.ephemeral` 分流双协议的门面 `ConsistencyService` 接口族（Ephemeral/Persistent/Delegate 三个实现）在 3.x 已整体删除（全仓 grep main 源码零命中）；分流下沉为两层——客户端路由层（`ClientManagerDelegate` 按 clientId 后缀分临时/持久 manager，`naming/.../ClientManagerDelegate.java:122-127`）与写入层（`EphemeralClientOperationServiceImpl` vs `PersistentClientOperationServiceImpl`，3.3 节）。抽象没有消失，而是融进了领域模型。

## 7.2 成员管理：ServerMemberManager

集群有多少节点、节点死活，由 `ServerMemberManager` 统一管理（`nacos:core/.../cluster/ServerMemberManager.java:90-91`）：

- 节点表：`ConcurrentSkipListMap<String, Member> serverList`（`:111`），self 元数据带 version/能力位（`:158-165`，含 `SUPPORT_JRAFT_AUTH`、`SUPPORT_MCP_LIFECYCLE`——节点间据此做能力兼容）。
- **三种寻址模式**（`cluster/lookup/LookupFactory.java:64-136`）：

| 模式 | 触发条件 | 实现 |
|---|---|---|
| standalone | 单机启动 | `StandaloneMemberLookup`（只有自己） |
| file | 存在 `conf/cluster.conf` 或 `nacos.member.list` 配置 | `FileConfigMemberLookup`（WatchFileCenter 监听文件变化热更新，`:42-53`） |
| address-server | 显式配置或默认（无 cluster.conf 时） | `AddressServerMemberLookup`（周期拉 `http://domain:port/nacos/serverlist`，`:133-163`） |

- **节点健康上报**（非 Raft 节点）：`MemberInfoReportTask` 每 2s 轮询游标选一个目标节点汇报自己（50s 全量一轮，`:526-564`；走 gRPC `MemberReportRequest` 或 HTTP `/nacos/core/cluster/report` 双路，`:566-652`）；`UnhealthyMemberInfoReportTask` 每 5s 专挑非 UP 节点探测（`:681-708`）。失败计数超阈值置 DOWN（`MemberUtil.onFail:178-197`）。
- 成员变更事件 `MembersChangeEvent` 同时通知 AP/CP 两协议（`ProtocolManager.onEvent:161-179`），并把新成员表持久化回 cluster.conf（`memberChange:361-420`）——**手动改 cluster.conf 文件与运行期成员变更最终会收敛到同一份事实**。

## 7.3 Distro 协议（AP）：临时实例数据的最终一致

### 7.3.1 协议要点：数据分片 + 责任节点

Distro 的核心思想不是"所有节点都有全量数据并互相同步"，而是**每个节点只对自己负责（isResponsibleClient）的客户端数据拥有写权**，其他节点持有副本；读流量任意节点可服务（副本即答案）。naming v2 的挂接者是 `DistroClientDataProcessor`（`nacos:naming/.../consistency/ephemeral/distro/v2/DistroClientDataProcessor.java`）：

- 协议数据类型：`TYPE = "Nacos:Naming:v2:ClientData"`（`:61`）——**Distro 同步的单位是"一个 Client"，不是"一个 Service"**（v1 时代的按服务全量同步已成历史）。
- 只同步临时客户端：`isInvalidClient` 排除 `!client.isEphemeral() || !isResponsibleClient(client)`（`:137-141`，注释原话 "persist client should sync by raft"）。
- 订阅三类事件（`:93-135`）：`ClientChangedEvent → distroProtocol.sync(CHANGE)`、`ClientDisconnectEvent → sync(DELETE)`、`ClientVerifyFailedEvent → syncToTarget(ADD, delay=0)` 直推某个节点。

### 7.3.2 增量同步流水线：延迟去重 + 双引擎

【源码证据】`nacos:core/.../distro/DistroProtocol.java`：

```java
public void sync(DistroKey distroKey, DataOperation action) {
    sync(distroKey, action, DistroConfig.getInstance().getSyncDelayMillis());   // :106-108 默认延迟
}
// sync(..., delay):117-121 —— 对 allMembersWithoutSelf() 每个目标节点 syncToTarget
// syncToTarget:131-142 —— new DistroDelayTask(keyWithTarget, action, delay) 入延迟引擎
```

默认参数（`DistroConstants.java`）：同步延迟 **1000ms**（`DEFAULT_DATA_SYNC_DELAY_MILLISECONDS`，实测 `:28-33`）、校验周期 **5000ms**（`:49-54`）、load 重试/超时各 30000ms（`:63-75`）——**延迟 1 秒的意义与推送 500ms 相同：把高频注册抖动合并成一次传输**。

任务引擎两级（`distro/task/DistroTaskEngineHolder.java:33-45`）：

```
DistroDelayTaskExecuteEngine（延迟引擎，按 key 去重合并）
  → DistroDelayTaskProcessor.process：DELETE → DistroSyncDeleteTask；CHANGE/ADD → DistroSyncChangeTask   （task/delay/DistroDelayTaskProcessor.java:44-68）
  → DistroExecuteTaskExecuteEngine（执行引擎，按 target+type 分 worker）
      → DistroSyncChangeTask.doExecute：findDataStorage.getDistroData → findTransportAgent.syncData     （task/execute/DistroSyncChangeTask.java:44-54）
      → DistroClientTransportAgent.syncData：ClusterRpcClientProxy.asyncRequest → 9849 集群口           （naming/.../DistroClientTransportAgent.java:90-112）
```

接收端：`DistroDataRequest`（PUT/SNAPSHOT/QUERY 类型）由 naming 的 `DistroDataRequestHandler` 处理，回到 `DistroProtocol.onReceive`（`:150-161`）按 TYPE 找到 `DistroClientDataProcessor.processData`，把对端的 Client 数据落地到本机 `ClientManager`。

### 7.3.3 全量 load 与周期 verify：副本怎么补齐、怎么对账

- **load（新节点加入/重启）**：`DistroProtocol.startLoadTask`（`:71-87`）在构造时触发 `DistroLoadDataTask`（`task/load/DistroLoadDataTask.java:61-128`）：等成员列表与 storage 就绪 → 对每个 storage type 调 `transportAgent.getDatumSnapshot(address)` 从任意节点拉**全量快照** → `processSnapshot` 落地 → `finishInitial()`；失败 30s 后重试。**没有 md5 比对环节**——校验职责已整体移交 verify。
- **verify（运行期对账，5s 周期）**：`DistroVerifyTimedTask`（`task/verify/DistroVerifyTimedTask.java:51-86`）让每个节点对**自己负责**的客户端生成校验数据——naming v2 里就是 `DistroClientVerifyInfo(clientId, revision)`（`DistroClientDataProcessor.getVerifyData:297-319`；revision 即 3.2.2 的 `DistroUtils.hash(this)`）——发给各副本节点；对端 `processVerifyData → clientManager.verifyClient`（`:249-258`）比对 revision，不一致返回 false，触发 `ClientVerifyFailedEvent` → 源节点把该 Client **直推补齐**（`syncToVerifyFailedServer`，delay 0）。

一句话总结 Distro v2 的数据面：**增量靠 1s 延迟合并的 gRPC 单 client 推送，兜底靠 5s revision 校验反推补齐，新节点靠全量快照**。

### 7.3.4 为什么 config 不走 Distro

config 模块 grep `distro` 零命中。原因在架构：配置的真源是 DB（外置 MySQL 时），任何节点都能从 DB 全量重建（5.4 的 dump 体系就是"重建 + 对账"），节点间只需通知"该 dump 了"（5.7）——**共识问题被降维成了缓存失效问题**。唯一例外是内嵌 Derby 集群模式：没有外置 DB 可依赖，写路径由 JRaft 共识承担（`ConditionDistributedEmbedStorage` 下 `CircuitFilter` 把写请求转发 leader，`config/.../configuration/NacosConfigConfiguration.java:69-84`；此时 `ConfigChangePublisher` 短路，5.3）。

## 7.4 JRaft（CP）：持久实例与元数据的强一致

### 7.4.1 多 group 设计

JRaftServer 不是一个大 Raft 组，而是**每个数据域一个独立 raft group**（类注释明言设计动机，`nacos:core/.../raft/JRaftServer.java:94-100`）：`Map<String/*group*/, RaftGroupTuple> multiRaftGroup`（`:117`）。当前四个 group（定义 `naming/.../constants/Constants.java:26-32`）：

| group | 数据 | 状态机 processor |
|---|---|---|
| `naming_persistent_service_v2` | 持久实例（v1 的 `naming_persistent_service` 已由 v2 取代） | `PersistentClientOperationServiceImpl` |
| `naming_instance_metadata` | 实例元数据（权重/enabled） | `InstanceMetadataProcessor` |
| `naming_service_metadata` | 服务元数据（保护阈值/选择器/集群配置） | `ServiceMetadataProcessor` |

group 名来自 `RequestProcessor4CP.group()`（`createMultiRaftGroup:240-243`，重复抛异常）。

### 7.4.2 写路径：write → apply

【源码证据】以持久实例注册为例（3.3 的 `PersistentClientOperationServiceImpl.registerInstance`）：

```java
WriteRequest writeRequest = WriteRequest.newBuilder().setGroup(group())
    .setData(ByteString.copyFrom(serializer.serialize(request)))
    .setOperation(DataOperation.ADD.name()).build();          // :108-133
protocol.write(writeRequest);                                  // 同步等待
```

`JRaftProtocol.write` 同步等待 10s（`:180-190`；`getData` 5s，`:169-178`）→ `JRaftServer.commit`（`:339-360`）：本机是 leader 直接 `applyOperation → node.apply(task)`（`:417-440`），否则 `invokeToLeader` 转发给 leader（`:442-474`）。落成日志后，**每个节点**的状态机 `NacosStateMachine.onApply` 回放（`NacosStateMachine.java:94-150`）：leader 侧取回发起时携带的 closure 完成请求应答，follower 侧从日志反序列化并执行 `processor.onApply`（写入本机 ClientManager/元数据表）；失败则 `setErrorAndRollback`。

### 7.4.3 快照、选主与端口

- 快照间隔默认 **1800s**（`RaftSysConstants.DEFAULT_RAFT_SNAPSHOT_INTERVAL_SECS = 30*60`，`:37`）；naming 三个 group 各自有 `JSnapshotOperation`（如持久实例的 `persistent_instance.zip`，`PersistentClientOperationServiceImpl.java:337-557`）。
- 选举超时默认 **5s**（`DEFAULT_ELECTION_TIMEOUT = 5000`，`:32`）；JRaft 自带独立 RPC（`JRaftUtils.initRpcServer`，`JRaftServer.start:210-216`），端口 = **主端口 − 1000**（默认 7848，`MemberUtil.calculateRaftPort:122-124`）。
- 3.x 已删除 2.x 写路径的 `RetryRunner` 自动重试——写失败就是失败，由调用方决策（这也让"持久实例注册失败必须向用户报错"成为默认行为）。

## 7.5 事件总线与分片：NotifyCenter 与 NamingEventPublisherFactory

（common 模块，支撑了第三、五、七章的所有事件链路，值得一节。）

`nacos:common/.../notify/NotifyCenter.java`：

- 普通事件：每类事件一个独立 `EventPublisher`（ring buffer 队列 16384，`nacos.core.notify.ring-buffer-size`，`:49-77`），单线程串行消费——**不同类事件天然并行，同类事件天然有序**。
- `SlowEvent` 标记的事件（低频元数据类，如 `MembersChangeEvent`）：走共享 `DefaultSharePublisher`，发布线程内直接分发（`:88-122`），省去队列投递。
- naming 的问题在于**事件量大且要求顺序**：`ClientEvent.ClientChangedEvent`、`ClientDisconnectEvent` 等如果共享一个全局队列，会互相阻塞；各开独立队列又可能乱序（同一 client 的 Changed/Disconnect 必须有序）。解法是**按外围类分片**的 `NamingEventPublisherFactory`（`naming/.../event/publisher/NamingEventPublisherFactory.java:50-61`）：`ClientEvent$ClientChangedEvent` 归并到 `ClientEvent` 一个 publisher——同类事件同队列串行（javadoc 原话 "Some naming event is in order, so these event need publish by sync (with same thread and same queue)"，`:29-31`）；每个 publisher 独立队列 + 守护线程 `naming.publisher-<Type>`（`NamingEventPublisher.java:41-67`），队列满降级同步处理（`:98-109`），并等待首个订阅者注册最多 60s 防丢消息（`:147-156`）。

## 7.6 本章小结

- Nacos 集群数据的一致性按"数据性质"三选一：临时实例 → Distro（AP）；持久实例/元数据 → JRaft（CP，四个独立 group）；配置 → DB 单源 + dump 对账（外置 DB 时连共识都不需要）。
- Distro v2 的单位是 **Client**：增量 1s 延迟合并推送、5s revision 校验反推补齐、新节点全量快照；只有责任节点能写，副本只读。
- JRaft 写路径是"同步等 10s 的 consensus write"；快照 30 分钟一次，选举 5s 超时，专用端口 7848。
- 成员管理三种寻址（file/address-server/standalone）+ 双任务健康上报；成员变更同时驱动 AP/CP 两协议。
- v1 的 `ConsistencyService` 门面已删，分流下沉到 ClientManagerDelegate 与 ClientOperationService——**"临时/持久"从接口分流变成了数据模型属性**。
- 事件总线按"事件类"分 publisher 保证同类有序；naming 用外围类分片平衡吞吐与顺序。


---
# 八、鉴权、限流与连接治理

## 8.1 三 scope 鉴权：3.x 的最大安全变化

**先白话**：3.0 之前 Nacos 的鉴权是一个总开关（`nacos.core.auth.enabled`，默认 false）——很多人裸奔上线，于是有了著名的未授权访问漏洞史。3.x 把 API 分成三层、配三个开关，且**发行包默认全部开启**：

| 开关 | 管什么 | 默认值证据 |
|---|---|---|
| `nacos.core.auth.enabled` | SDK/gRPC 请求（客户端注册发现配置） | `nacos:distribution/conf/application.properties:359` = true |
| `nacos.core.auth.admin.enabled` | `/v3/admin/*` 管理 API | `:361` = true |
| `nacos.core.auth.console.enabled` | `/v3/console/*` 控制台 API | `:363` = true |

代码默认同样为 true：`NacosServerAuthConfig.getConfigFromEnv` 里 `EnvUtil.getProperty(NACOS_CORE_AUTH_ENABLED, Boolean.class, true)`（`nacos:core/.../NacosServerAuthConfig.java:113-126`），开启时 identity/server identity 缺失会启动报错（`:64-76`）。**2.x 升级 3.x 的部署清单里，鉴权配置是必检项。**

## 8.2 服务端鉴权链路：过滤器 + 插件

**先白话**：鉴权被拆成"骨架（auth/core 模块）+ 插件（plugin-default-impl）"。骨架负责拦截、身份解析、server identity 校验；插件负责"这个 token 是不是真的、这个用户有没有这个权限"。

- HTTP 侧两个过滤器（`core/auth/`）：`AuthFilter` 管 OPEN_API（`AuthFilter.java:45-54`，ADMIN_API 被排除）、`AuthAdminFilter` 只管 ADMIN_API（`AuthAdminFilter.java:44-48`），都继承 `AbstractWebAuthFilter`（`:61`），装配顺序 6、路径 `/*`（`AuthConfig.java:30-76`）。核心流程 `doFilter`（`AbstractWebAuthFilter.java:74-150`）：无 `@Secured` 注解直接放行 → `parseIdentity` → 鉴权未开启放行 → **server identity 校验**（节点间互信：请求带 `nacos.core.auth.server.identity.key/value` 头即认为来自自家集群，`:107-118`；升级期 `INNER_API` 的兼容跳过见 `AuthFilter.java:56-64`）→ `parseResource → validateIdentity → validateAuthority`。
- gRPC 侧对称的 `RemoteRequestAuthFilter`（`:50-114`）：在每个 RequestHandler 执行前走同样的四步（protocol 换成 `GrpcProtocolAuthService`）。
- 默认插件 `NacosAuthPluginService`（`plugin-default-impl/nacos-default-auth-plugin/.../impl/AbstractNacosAuthPluginService.java:74-151`）：validateIdentity 支持 **token（JWT）或 username/password** 两种凭据；`validateAuthority` 走角色权限（`RoleInfo`/`Permission`），ADMIN 角色常量 `ROLE_ADMIN`（`impl/constant/AuthConstants.java:33`，禁止手工创建、首次自动初始化，`NacosRoleServiceDirectImpl.java:129-160`）。
- JWT：`JwtTokenManager`（`token/impl/JwtTokenManager.java:50-94`）——secret 取 `nacos.plugin.auth.nacos.token.secret.key`，**必须 ≥32 字节的 Base64**（否则启动报错 "Please config ... secret.key"，`:147-152`）；鉴权未开启时 token 固定为 "AUTH_DISABLED"。

**server identity 的双刃剑**：它让集群节点互信零成本（Distro/JRaft/配置同步都不用带用户 token），但 key/value 为空时形同虚设——3.x 部署时若开启鉴权，必须配置非空 identity（`NacosServerAuthConfig.validate:64-76` 会拦住）。

## 8.3 客户端登录：SecurityProxy 与 token 刷新

【源码证据】`nacos:client/src/main/java/com/alibaba/nacos/client/security/SecurityProxy.java`：

- 登录：`login()`（`:83-91`）委托 SPI `ClientAuthPluginManager` 加载的 `NacosClientAuthServiceImpl`（`client-basic/.../auth/impl/NacosClientAuthServiceImpl.java:77-121`）→ `HttpLoginProcessor.getResponse`（`process/HttpLoginProcessor.java:67-127`）POST `/v3/auth/user/login`（404/501 时降级 `/v1/auth/users/login` 兼容 2.x server，`:93-98`），取回 `accessToken` + TTL。
- 刷新：naming 侧 `NamingClientProxyDelegate.initSecurityProxy` 每 **5s** 调一次 login（`SECURITY_INFO_REFRESH_INTERVAL_MILLS`，`client-basic/.../constant/Constants.java:44-45`；config 侧同，`ConfigTransportClient.start:150-157`）——TTL 未到是免登录的（`:86-89`）；真正的重新登录窗口在 TTL 的 [1/15, 1/10] 随机区间（`:138-142`），错峰防雪崩。
- 附加：`getIdentityContext(RequestResource)`（`SecurityProxy.java:98-109`）按资源类型（naming/config）返回 token map，由 2.5 节的 `getSecurityHeaders` 塞进每个 gRPC 请求；**任何请求收到 403 触发 `reLogin()`**（`NamingGrpcClientProxy.java:529-531`，置 `NacosAuthLoginConstant.RELOGINFLAG`，`NacosAuthLoginConstant.java:30`）。

## 8.4 限流与连接治理：TpsControl + ConnectionControl

3.x 的流量治理是两把闸（`nacos:core/control/`，经 `ControlManagerCenter` 获取）：

- **TPS 限流**：handler 上标 `@TpsControl(pointName = "...")`（如 `HealthCheckRequestHandler:32-42`），`RequestHandlerRegistry` 启动时注册点位（`:94-103`），按"点位名 + 客户端身份"计数限流；超限返回 TPS 限制错误响应。点位可通过配置放宽/收紧（`nacos.remote.control.*`）。
- **连接数控制**：`ConnectionManager.register` 里 `checkLimit`（`:133-146`，`ConnectionCheckRequest(ip, appName, module)`）——第二章说的"连接注册限流"与第五章说的"长轮询挂载限制"用的是同一机制，超限对长轮询返回 503 并延迟 1~3s（`LongPollingService.java:207-211`）。

这两把闸 + 8.2 的 server identity + 2.3.3 的连接驱逐，构成了"单个客户端能占多少服务端资源"的完整约束。

## 8.5 生产部署要点（清单式）

1. **必改默认值**：`nacos.core.auth.*` 三个开关保持 true 时，配好 `nacos.core.auth.server.identity.key/value`（非空）与 `nacos.plugin.auth.nacos.token.secret.key`（≥32B Base64）；默认空值等于未鉴权。
2. **集群形态**：3 节点起（JRaft 选举需要多数派）；外置 MySQL（`nacos.datasource` 配置）；`cluster.conf` 或 address-server 寻址（7.2）。
3. **端口放行**：8848/9848/9849/7848（2.4 表）；客户端侧只感知 8848。
4. **容量认知**：单节点长轮询挂载、gRPC 连接数、推送重试 50 次、配置灰度 10 版本、dump 6h/30s 对账——参数速查见附录 10.1。
5. **版本策略**：server 3.x 与 SCA 2023.x（nacos-client 2.4.3）兼容；升级前先看官网发布历史页的"只维护最新 GA"提示，避免用过老的 2.x server。
6. **历史漏洞教训**：默认口令 `nacos/nacos`、默认 JWT secret、未开启鉴权的 admin API 都曾被大范围利用——3.x 默认全开鉴权正是对这些事故的回应；自建集群仍要改掉默认口令。

## 8.6 本章小结

- 3.x 鉴权 = 三 scope（SDK/admin/console，默认全开）+ 骨架/插件分离 + server identity 节点互信；secret 必须 ≥32B Base64。
- 客户端登录走 SPI（v3 登录接口、v1 降级），5s 周期刷新 + 403 触发 reLogin；token 以 header 形式随每条 gRPC 请求。
- 限流两把闸：`@TpsControl` 点位限流 + 连接数控制（gRPC 注册与长轮询挂载共用）。
- 生产 checklist 的核心是"默认值即风险"：identity、secret、admin 口令三处默认值必须替换。


---
# 九、贯通视图：三条时间线

前八章是"按模块"看的；本章把三条最常见的时间线首尾相接串起来，检验是否真的读通了。

## 9.1 时间线一：一次服务注册的一生

以"用户服务（order 应用）启动，user 应用订阅它"为主线：

```
[order 应用（SCA）]
 ① WebServerInitializedEvent（Tomcat 起来了）
    → AbstractAutoServiceRegistration.onApplicationEvent → NacosAutoServiceRegistration.register   (sca)
 ② NacosServiceRegistry.register：组装 Instance（ip/port/weight/ephemeral/metadata）                (sca:registry:60-90)
 ③ NacosNamingService.registerInstance → NamingClientProxyDelegate（ephemeral→gRPC）
    → NamingGrpcClientProxy.registerService：redoService.cacheInstanceForRedo 先记账                (4.2)
 ④ RpcClient.request(InstanceRequest REGISTER) → 9848
    （若这条连接尚未建立：GrpcClient.connectToServer 三步建连 + 能力协商）                            (2.2.3)

[server]
 ⑤ GrpcRequestAcceptor → RequestHandlerRegistry → InstanceRequestHandler
    → EphemeralClientOperationServiceImpl.registerInstance：
      client.addServiceInstance + recalculateRevision + 发 ClientRegisterServiceEvent              (3.3)
 ⑥ ClientServiceIndexesManager：publisherIndexes[service] += clientId；
    第一个实例 → ServiceChangedEvent(ADD_SERVICE)                                                   (3.4)
 ⑦ 两路并发：
    a) 推送：NamingSubscriberServiceV2Impl → PushDelayTask（500ms 合并）
       → PushExecuteTask → ServiceStorage.getPushData 重新组装 → RpcPushService 推 NotifySubscriberRequest (3.6)
    b) Distro：DistroClientDataProcessor 收 ClientChangedEvent → sync(CHANGE)
       → DistroDelayTask（1s 合并）→ 推给其他节点的 9849 → 对端 ClientManager 落地                   (7.3.2)

[user 应用（SCA）]
 ⑧ 启动时：NacosDiscoveryClient（或首次 selectInstances）触发 subscribe=true
    → SubscribeServiceRequestHandler：返回当前列表 + client.addServiceSubscriber                    (3.5)
 ⑨ 收到 ⑦a 的推送：NamingPushRequestHandler → ServiceInfoHolder.processServiceInfo
    → InstancesChangeEvent → InstancesChangeNotifier → 用户 EventListener / SCA 缓存刷新            (4.3)
 ⑩ LoadBalancer（若开启 nacos LB）按权重随机选一个实例发起调用                                       (4.5.4)
```

**故障注入**：order 进程 kill -9 → 连接断 → 服务端 3s 驱逐/客户端健康检查双路感知 → `ClientDisconnectEvent` → 索引清理 + `ServiceChangedEvent` → 500ms 后推送新列表（⑨ 处 user 感知下线）→ Distro `sync(DELETE)`。全程无心跳包，延迟上界 ≈ 驱逐周期 3s + 5s 探测 + 推送 0.5s。

## 9.2 时间线二：一次配置变更的一生

以"管理员在控制台把 order 的超时从 3s 改成 5s"为主线：

```
[console / API]
 ① PUT /v3/cs/admin/config → ConfigControllerV3.publishConfig
    → EncryptionHandler（可选加密）→ ConfigOperationService.publishConfig：
      CAS/普通写入 DB（his_config_info 同步留痕）→ ConfigDataChangeEvent                           (5.3)

[server 节点 A]
 ② NotifyCenter：DumpService 收到事件 → DumpTask → DumpConfigHandler
    → ConfigCacheService.dumpWithMd5：正文写盘 + 内存 md5 更新 → 发布 LocalDataChangeEvent          (5.4)

[server 节点 A → 集群]
 ③ AsyncNotifyService：ConfigChangeClusterSyncRequest → B/C 的 9849
    → 各自 dumpService.dump（对齐缓存；DB 若被旁路修改，30s DumpChangeConfigWorker 也会兜住）        (5.7/5.4)

[server 节点 A → 客户端]（④/⑤ 两代并存）
 ④ gRPC 订阅者：RpcConfigChangeNotifier → ConfigChangeNotifyRequest → order 客户端                  (5.6.2)
 ⑤ 1.x 老客户端：LongPollingService.DataChangeTask → 长轮询立即返回变更 groupKey 列表                (5.6.1)

[order 客户端]
 ⑥ 收到通知：receiveNotifyChanged=true → 摇铃 → executeConfigListen
    → ConfigBatchListenRequest（或直接 queryConfigInner）→ 服务端 304/新内容
    → CacheData.setConfigContentAndKey → checkListenerMd5 → 用户 listener 回调                      (6.1.3)
 ⑦ SCA：NacosContextRefresher 的 listener 发布 NacosConfigRefreshEvent
    → NacosConfigRefreshEventListener 转译为 RefreshEvent
    → spring-cloud-context：ContextRefresher.refresh() → Environment 重载 → @RefreshScope Bean 重建  (6.2.3)
```

**关键延迟预算**：控制台写库毫秒级；dump 毫秒级；gRPC 通知 + 回源拉取通常亚秒级；@RefreshScope 重建取决于 Bean 本身。**长轮询路线的延迟上界是 0（变更即返回），而轮询感知路线（无通知时）上界是 29.5s。**

## 9.3 时间线三：一次节点宕机的一生

以"Nacos 三节点集群 kill 掉 leader 节点 B"为主线（这是面试高频题，也是选型时最该想清楚的）：

```
[瞬间]
 ① 客户端：连接 B 的 RpcClient 请求超时/收到 UN_REGISTER → 换机到 A/C（服务器列表在 ServerListManager）
    → redo 任务 3s 周期重放注册与订阅 → 数据在新节点"重新出现"                                       (2.2.4/4.2)
 ② A/C：ServerMemberManager 上报任务探测 B 失败 → 置 SUSPICIOUS → 超阈值置 DOWN
    → MembersChangeEvent → Distro/JRaft memberChange                                                (7.2)

[数据面]
 ③ 临时实例：B 上负责的 client 数据，在 A/C 是副本；verify(5s) 发现 B 失联后，
    各节点对自己负责的 client 正常服务；客户端换机后 redo 重放让注册"归位"                            (7.3.3/4.2)
 ④ 持久实例：JRaft 三个 group 在 5s 选举超时后从 A/C 选出新 leader（3 节点仍满足多数派），
    期间持久实例的注册/元数据修改不可用（写阻塞），读不受影响                                         (7.4.3)
 ⑤ 配置：DB 在 MySQL 里，与 B 无关；A/C 照常服务读写；长轮询/gRPC 订阅由客户端换机重挂               (5.2/6.1.3)

[恢复]
 ⑥ B 重启：加回 cluster.conf → 全量 load 拉快照（Distro）+ JRaft 日志追赶 → verify 对齐 → 恢复服务    (7.3.3)
```

**结论表**：

| 数据 | B 宕机瞬间 | 恢复期 | 一致性窗口 |
|---|---|---|---|
| 临时实例 | 读可用（副本），注册写换机后 redo 重放 | 全量快照 + verify | 秒级最终一致 |
| 持久实例/元数据 | 读可用；**写不可用**（等新 leader） | 日志追赶 | 写中断 ≈ 选举期（~5s） |
| 配置 | 完全可用（DB 独立） | 无需恢复 | 无窗口 |

这张表就是"Nacos = AP 优先（服务发现）+ 可选 CP（持久语义）+ 配置靠 DB"的最终注脚，也是 1.2 设计哲学第 3 句的动态版。

## 9.4 常见故障场景速查

| 现象 | 根因定位（对应章节） |
|---|---|
| 客户端连不上，8848 telnet 通 | 9848/9849 未放行（2.4） |
| 启动报错 "Client not connected, current status: STARTING" | gRPC 建连失败/能力协商超时（2.2.3） |
| 实例下线后上游还调用失败实例 | 容灾缓存开着（4.5.3）/保护阈值命中（3.8）/LB 缓存未刷新 |
| 改了配置不生效 | dataId/group/namespace 不匹配（5.2）；refreshEnabled=false（6.2）；listener 阻塞（6.1.2）；非 @RefreshScope Bean |
| 改了配置偶发生效慢 | 长轮询挂载超限延迟返回（5.6.1）；3 分钟对账兜底中（6.1.3） |
| 持久实例注册失败 | JRaft 无多数派/leader 切换中（7.4）；写 10s 超时即报错 |
| 升级 3.x 后客户端登录失败 | 三 scope 鉴权默认开启，未配 secret/identity（8.1/8.5） |


---
# 十、附录

## 10.1 关键参数速查表（全部源码实证）

### 通信与连接

| 参数 | 默认值 | 位置 |
|---|---|---|
| 主端口 | 8848（`nacos.server.main.port`） | `core/.../ServerMemberManager.java:97` |
| gRPC SDK 端口 | 主端口 +1000 = 9848 | `api/.../Constants.java:102` |
| gRPC 集群端口 | 主端口 +1001 = 9849 | `api/.../Constants.java:104` |
| JRaft RPC 端口 | 主端口 −1000 = 7848 | `core/.../MemberUtil.java:122-124` |
| 请求超时/重试 | 3000ms × 3 次 | `common/.../DefaultGrpcClientConfig.java:215-247` |
| 连接空闲健康检查 | 5000ms 空闲触发 | 同上 `connectionKeepAlive` |
| 服务端连接驱逐周期 | 3000ms（探测应答 5000ms） | `core/.../ConnectionManager.java:255-279` |
| gRPC 消息上限 | 10MB | `core/.../GrpcServerConstants.java:101` |

### 服务发现（naming）

| 参数 | 默认值 | 位置 |
|---|---|---|
| 推送延迟（合并窗口） | 500ms | `naming/.../PushConstants.java:31` |
| 推送失败重试延迟 | 1000ms | `naming/.../PushConstants.java:45` |
| 客户端 redo 周期 | 3000ms | `api/.../Constants.java:238` |
| 心跳兼容层（HTTP 老客户端） | 心跳超时/摘除由 `preserved.heart.beat.*` metadata 控制 | `naming/.../ClientBeatCheckTaskV2.java` |
| 订阅兜底轮询 | 默认关闭（`NAMING_ASYNC_QUERY_SUBSCRIBE_SERVICE`） | `client/.../ServiceInfoUpdateService.java:78-86` |

### 配置中心（config）

| 参数 | 默认值 | 位置 |
|---|---|---|
| 长轮询挂载超时 | max(10000, header−500)ms；旧客户端 30000−500=29.5s | `config/.../LongPollingService.java:215-222` |
| 客户端监听对账周期 | 3 分钟 | `client/.../ClientWorker.java:828` |
| gRPC 推送重试 | 50 次 | `config/.../ConfigCommonConfig.java:34` |
| 集群变更通知重试 | 6 次，delay=500+fail²×1000ms，任务间隔 3000ms | `config/.../AsyncNotifyService.java:60-64,224,371-378` |
| 全量 dump / 增量对账 | 6 小时 / 30 秒（分页 100） | `config/.../DumpService.java:83-88`、`DumpChangeConfigWorker.java:59` |
| 灰度版本上限 | 10（`nacos.config.gray.version.max.count`） | `config/.../ConfigOperationService.java:284` |
| 磁盘缓存形态 | raw_disk（可 `config_disk_type=rocksdb`） | `config/.../ConfigDiskServiceFactory.java:38-51` |
| 拉取超时（SCA） | 3000ms（`spring.cloud.nacos.config.timeout`） | `sca:.../NacosConfigProperties.java:128` |

### 一致性与集群

| 参数 | 默认值 | 位置 |
|---|---|---|
| Distro 增量同步延迟 | 1000ms | `core/.../DistroConstants.java:28-33` |
| Distro verify 周期 | 5000ms | `core/.../DistroConstants.java:49-54` |
| Distro load 重试/超时 | 30000ms | `core/.../DistroConstants.java:63-75` |
| JRaft 写/读等待 | 10s / 5s | `core/.../JRaftProtocol.java:169-190` |
| JRaft 选举超时/快照间隔 | 5s / 1800s | `core/.../RaftSysConstants.java:32,37` |
| 成员健康上报 | 每 2s 汇报一个节点（50s 一轮）；异常节点每 5s 探测 | `core/.../ServerMemberManager.java:526-708` |
| 事件 ring buffer | 16384（share 1024） | `common/.../NotifyCenter.java:49-77` |

## 10.2 关键类速查

| 领域 | 类（模块） | 职责 |
|---|---|---|
| 通信-客户端 | `RpcClient`/`GrpcClient`/`GrpcConnection`（common） | 连接状态机、建连三步、重连换机 |
| 通信-服务端 | `GrpcSdkServer`/`GrpcClusterServer`/`ConnectionManager`（core） | 双端口、连接注册/限流/驱逐 |
| 注册表 | `AbstractClient`/`ConnectionBasedClient`/`IpPortBasedClient`（naming） | Client 模型（发布/订阅数据） |
| 索引 | `ClientServiceIndexesManager`/`ServiceStorage`（naming） | service→clients 索引与推送数据快照 |
| 注册操作 | `EphemeralClientOperationServiceImpl`/`PersistentClientOperationServiceImpl`（naming） | 临时（内存+事件）/持久（raft）两实现 |
| 推送 | `NamingSubscriberServiceV2Impl`/`PushDelayTaskExecuteEngine`/`PushExecuteTask`（naming） | 事件→延迟任务→gRPC 推送 |
| 健康 | `ClientBeatProcessorV2`/`HealthCheckTaskV2`/`PersistentHealthStatusSynchronizer`（naming） | 心跳兼容/持久实例探测/状态写回 raft |
| 配置存储 | `ConfigCacheService`/`ConfigDiskServiceFactory`（config） | groupKey→CacheItem 元数据缓存与磁盘正文 |
| 配置查询 | `ConfigQueryChainService` 及 handler 链（config） | 五节点责任链（灰度/304/正文） |
| 配置通知 | `LongPollingService`/`ConfigChangeBatchListenRequestHandler`/`RpcConfigChangeNotifier`（config） | 两代变更通知并存 |
| 配置客户端 | `ClientWorker`/`CacheData`/`LocalConfigInfoProcessor`（client） | 铃声驱动监听、md5 回调、快照/容灾 |
| 一致性 | `DistroProtocol`/`DistroClientDataProcessor`/`JRaftProtocol`/`NacosStateMachine`（core/naming） | AP/CP 两协议 |
| 成员 | `ServerMemberManager`/`LookupFactory`（core） | 节点表、寻址、健康上报 |
| 鉴权 | `AbstractWebAuthFilter`/`NacosAuthPluginService`/`SecurityProxy`（core/auth/client） | 三 scope 鉴权与客户端登录 |
| SCA-注册 | `NacosServiceRegistry`/`NacosAutoServiceRegistration`/`NacosDiscoveryClient`/`NacosLoadBalancer`（sca） | 注册/发现/LB 胶水 |
| SCA-配置 | `NacosConfigDataLocationResolver/Loader`/`NacosPropertySourceLocator`/`NacosContextRefresher`/`NacosConfigRefreshEventListener`（sca） | 两条接入路线与刷新转译 |

## 10.3 学习路线与延伸阅读

**建议的动手路径**（配合本系列其他文档）：

1. 先跑通：本地起一个 Nacos（standalone + Derby），用 SCA 2023.x 写一个注册/发现 + 配置监听的 demo；抓包看 9848 的 gRPC 流量（Wireshark + grpc 过滤器）。
2. 按第二章源码读客户端连接生命周期，在 `RpcClient.start()` 打断点观察状态机迁移与换机。
3. 按第三章读注册链路，重点在"事件表"：把 `ClientRegisterServiceEvent` 之后触发的每个订阅者画出来。
4. 按第五/六章读配置链路，用两次断点验证两个关键断言：服务端 `LocalDataChangeEvent` 只在 dump 成功后发布；客户端回调只在 CacheData md5 变化后触发。
5. 部署三节点集群（MySQL + cluster.conf），重复 9.3 的宕机实验，观察 redo/verify/选举的真实耗时。
6. 对比阅读：本系列《Spring Cloud Config.md》第九章（Config vs Nacos 五条主线对比）——读完本章再看，两边结论可以互相印证。

**延伸阅读**：

- Nacos 官网（nacos.io）：发布历史、架构文档、3.x 零信任安全说明；
- 官方仓库 `nacos:spec/` 与 `doc/` 目录（本快照内含 agent-management、AI 资源等新能力设计稿）；
- SOFAJRaft 原理（理解 7.4 的前提）；
- spring-cloud-commons 的 `AbstractAutoServiceRegistration` 与 spring-cloud-context 的 `RefreshEvent` 机制（见本系列《Spring Cloud Config.md》第三、四章）。

> **文档版本说明**：本文基于 Nacos 3.3.0-RC（develop，commit `1b6309f`，2026-09-24）与 spring-cloud-alibaba 2023.x（`2023.0.3.5-SNAPSHOT`）快照写作，所有行号以该快照为准；3.3.0 正式发布后若行号漂移，按类名 + 方法名重新定位即可。
