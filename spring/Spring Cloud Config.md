# Spring Cloud Config 深度源码解析（写给初学者的架构全景）

> **本文基于的源码**：`D:\code\3rd\spring-cloud-config`，版本 **5.0.5**（2025.1 "Oakwood" 发布列车，Git tag `v5.0.5`，2026-08-03 发布）。动态刷新机制来自 `D:\code\3rd\spring-cloud-commons`（main 分支，spring-cloud-context 模块），Bus 广播来自 `D:\code\3rd\spring-cloud-bus`（main 分支），Nacos 对比章基于 `D:\code\3rd\nacos`（main 分支，3.3.0-RC）浅克隆源码。文中所有【源码证据】的文件路径与行号均为对上述快照实际读取所得。
>
> **版本取舍说明**：Spring Cloud Config 的整体骨架自 1.x（2015）以来高度稳定——"客户端拉取 + 服务端装配 + Git 存储 + Bus 刷新"这条主线从未变过。3.0（2020，随 Boot 2.4）把接入方式从 bootstrap 迁移到 Config Data API，4.0（2022，随 Boot 3）完成 Jakarta 迁移，5.0（2025，随 Boot 4）完成 Jackson 3 迁移——这些都是"换发动机不换底盘"的演进。因此本文内容对使用 4.x（Boot 3.x）的读者同样适用；差异点在 1.6 节逐项标注。
>
> **阅读约定**：每章"先白话、后源码"——先用一两句人话讲清"这是什么、为什么需要它"，再给出源码证据链（`模块/src/main/java/.../类名.java` + 方法名 + 行号 + 代码片段）。行号只对 5.0.5 快照精确，读者用 IDEA 打开源码按类名 + 方法名定位即可。涉及 spring-cloud-commons / nacos 的证据会注明来源仓库。

## 如何读这份文档

如果你是 Spring Cloud Config 初学者，推荐两遍读法：

- **第一遍（建立地图，1~1.5 小时）**：只读第一章（总览）每节的开头白话段、各章的"本章小结"节、以及第十章（贯通视图：一次配置变更的一生）。目标是能回答：客户端启动时配置是怎么进来的？服务端收到一次 GET 后内部发生了什么？`@RefreshScope` 为什么能热刷新？Spring Cloud Config 和 Nacos 的本质差别是什么？
- **第二遍（深入源码）**：对照每一章的【源码证据】逐行读。顺序建议：第三章（客户端两种接入模式）→ 第四章（服务端 REST 契约与组合仓储）→ 第五章（Git 后端为什么是第一公民）→ 第七章（刷新机制，与 spring-cloud-context 联动）→ 第六章（加密，可跳读）→ 第九章（与 Nacos 对比）→ 第八章（运维随用随查）。

---

# 一、总览：Spring Cloud Config 的定位、设计哲学与整体架构

## 1.1 一句话定位

**Spring Cloud Config 是"把 Spring 的 Environment 抽象搬上 HTTP"的配置中心**：服务端（config-server）把 Git/SVN/Vault/JDBC 等后端里的配置文件装配成一份份有序的 PropertySource，通过 REST 接口吐给客户端；客户端（config-client）在启动阶段把这份远程 PropertySource 列表**原样插入**自己进程内的 Spring Environment——此后对 Spring 应用来说，"远程配置"与"本地 application.yml"没有任何区别，占位符解析、`@Value` 注入、profile 优先级全部照旧。

官方文档的自述（`docs/modules/ROOT/pages/index.adoc`）：*"Spring Cloud Config provides server-side and client-side support for externalized configuration in a distributed system. With the Config Server, you have a central place to manage external properties for applications across all environments."*

它解决的不是"配置存哪里"一个点，而是**外部化配置的全生命周期**：配置的版本化存储（Git 天然提供）、按应用/环境/版本的检索（name/profile/label 三元组）、敏感值的加密存储（`{cipher}` 密文随配置走）、变更的分发（actuator 端点 + Spring Cloud Bus 广播 + Git webhook 联动）。

一个需要立刻破除的误解：**Spring Cloud Config 不是长连接推送式的配置中心**。它天生是"拉模型"——客户端只在启动时拉一次，之后的变更感知要么靠人/脚本调 `POST /actuator/refresh` 触发客户端重新拉取，要么靠 Spring Cloud Bus 把"该刷新了"的信号广播给所有实例。这一点与 Nacos（长轮询/gRPC 推拉结合）有本质架构差异，第九章会展开对比。

## 1.2 设计哲学：读源码前先记住五句话

1. **一切皆 PropertySource**。服务端返回的不是自定义协议，而是一个名为 `org.springframework.cloud.config.environment.Environment` 的 DTO（`spring-cloud-config-client/src/main/java/org/springframework/cloud/config/environment/Environment.java:35`）——它就是 Spring core 里 `org.springframework.core.env.Environment` 的"可序列化镜像"：一个有序的 PropertySource 列表加上 name/profiles/label/version/state 六个字段。客户端收到后按顺序插入自己的 Environment，**wire format 与 Spring 内部模型同构**，这是 Config 一切"无缝"体验的根源（见 2.3 节）。
2. **仓储抽象一条接口**。服务端对"配置存在哪"的全部认知浓缩为一个方法：`EnvironmentRepository#findOne(application, profile, label, includeOrigin)`（`spring-cloud-config-server/.../environment/EnvironmentRepository.java:25-30`）。Git、SVN、JDBC、Vault、Redis、AWS S3、Parameter Store、Secrets Manager、CredHub、MongoDB、Google SecretManager……十几个后端全是这一个方法的实现，再由 `CompositeEnvironmentRepository` 按 `@Order` 顺序组合（见 4.5 节）。加一个新后端 = 写一个类 + 一个 Factory，控制器零改动。
3. **服务端是"装配工"，不是"解析器"**。这是 5.0.5 源码里最值得注意的一条：SCM 类仓库（Git/SVN）在 `AbstractScmEnvironmentRepository` 里 checkout 好工作副本后，把**文件系统路径**交给一个 `NativeEnvironmentRepository`（`AbstractScmEnvironmentRepository.java:93-101`），而 Native 的实现竟然是调用 **Spring Boot 自己的 `ConfigDataEnvironmentPostProcessor.applyTo()`**（`NativeEnvironmentRepository.java:147`）。也就是说，Config Server 解析 `application-prod.yml` 用的引擎，和你本地启动一个 Spring Boot 应用用的引擎**是同一个**——多文档 YAML、`spring.config.activate.on-profile`、占位符这些 Boot 特性在服务端"免费"可用。
4. **拉模型 + 事件刷新**。客户端不维持长连接，变更感知靠"重新拉取 + 新旧属性 diff + 事件通知"三步（`ContextRefresher#refreshEnvironment`，spring-cloud-commons `context/refresh/ContextRefresher.java:103-109`）。重新拉取复用启动时的 Config Data 引擎，diff 结果发布为 `EnvironmentChangeEvent`，`@RefreshScope` 则通过"销毁缓存实例、下次访问时重建"实现热刷新（见第七章）。
5. **密文自描述**。加密值以 `{cipher}...` 前缀形式直接存在配置文件里，前缀本身还携带密钥选择器（`{key:mykey}{cipher}...`）。值走到哪里密文跟到哪里，服务端默认在返回前解密，也可以关掉开关让客户端本地解密——安全策略与传输机制解耦（见第六章）。

## 1.3 模块分层全景

spring-cloud-config 仓库 5.0.5 实测共 7 个工程模块。按"用户感知"分层如下：

```
┌──────────────────────────── 监控联动层 ─────────────────────────────┐
│  spring-cloud-config-monitor                                        │
│    /monitor 端点：解析 GitHub/GitLab/Gitee/Gitea/Gogs/Bitbucket     │
│    的 webhook，猜出受影响的服务名，发 RefreshRemoteApplicationEvent   │
│    （依赖 spring-cloud-bus，需要 Server 引入此模块）                  │
├──────────────────────────── 服务端 ─────────────────────────────────┤
│  spring-cloud-config-server（@EnableConfigServer）                  │
│    REST 契约层：EnvironmentController / ResourceController          │
│                  / EncryptionController                             │
│    仓储体系：EnvironmentRepository + 12+ 后端实现 + Composite        │
│    装配引擎：NativeEnvironmentRepository（复用 Boot ConfigData）      │
│    加密体系：TextEncryptorLocator / EnvironmentEncryptor             │
├──────────────────────────── 客户端 ─────────────────────────────────┤
│  spring-cloud-starter-config（聚合 starter）                         │
│  spring-cloud-config-client                                         │
│    Config Data 接入：ConfigServerConfigDataLocationResolver/Loader  │
│    legacy 接入：ConfigServicePropertySourceLocator（bootstrap 模式） │
│    契约 DTO：environment.Environment / PropertySource（client 定义） │
│    运维件：ConfigServerHealthIndicator / ConfigClientWatch           │
├──────────────────────── 依赖 BOM / 测试 / 样例 ─────────────────────┤
│  spring-cloud-config-dependencies（BOM）                             │
│  spring-cloud-config-client-tls-tests（TLS 集成测试工程）             │
│  spring-cloud-config-sample                                          │
└──────────────────────────────────────────────────────────────────────┘
```

注意一个容易搞错的点：**服务端依赖客户端**——`spring-cloud-config-server/pom.xml` 中明确依赖 `spring-cloud-config-client`。原因有二：wire DTO（Environment 类）定义在 client 模块里，服务端要用它做响应序列化；服务端自身也时常需要"作为 client"的语义（比如 4.7 节讲的防止自调用循环的开关）。

## 1.4 模块依赖图（以各模块 pom.xml 的依赖实证）

```
                spring-cloud-starter（starter 基座：含 spring-cloud-context/commons）
                        ▲
                        │
        spring-cloud-config-client ◄────────────────────┐
              （Boot autoconfigure、Config Data SPI）      │
                        ▲            ▲                    │
                        │            │                    │
   spring-cloud-config-server       │      spring-cloud-starter-config
   （web、jgit、spring-vault-core、  │      （仅聚合 starter + client + jackson）
     snakeyaml、micrometer-observation）│
                        ▲            │
                        │            │
        spring-cloud-config-monitor ◄── spring-cloud-bus
```

真实依赖声明（摘自各模块 `pom.xml`，已逐个验证）：

| 模块 | 关键依赖 | 说明 |
|---|---|---|
| spring-cloud-config-client | `spring-boot-autoconfigure`、`spring-cloud-starter`（传递引入 spring-cloud-context/commons）、可选 `spring-retry` | 客户端所有自动装配入口；`spring.factories` 注册 Config Data SPI（见 3.2 节） |
| spring-cloud-config-server | `spring-cloud-config-client`、`spring-boot-starter-web`、`org.eclipse.jgit`（+http.apache/ssh.apache）、`spring-vault-core`、`snakeyaml`、`micrometer-observation` | Git/Vault 是编译期硬依赖（`@ConditionalOnClass` 直接命中），其余后端是 optional |
| spring-cloud-config-monitor | `spring-cloud-config-server`、`spring-cloud-bus`（BOM 版本 5.0.3） | monitor 无自己的 spring.factories，只有 `AutoConfiguration.imports` 一行 |
| spring-cloud-starter-config | `spring-cloud-starter`、`spring-cloud-config-client`、`jackson-databind` | 用户唯一需要引的坐标 |

这张表本身就是架构说明：**client 是协议层，server 是协议的实现方**（所以 server 依赖 client），**monitor 把 server 和 bus 缝起来**（webhook → Bus 事件的翻译官），**starter 只做聚合**。用户侧最小组合是 `starter-config`（客户端）或 `config-server`（服务端，如需 webhook 联动再加 `config-monitor` + 任一 Bus binder）。

## 1.5 关键问题 → Config 方案映射（全文导览）

| 分布式配置的关键问题 | Spring Cloud Config 的方案 | 详见 |
|---|---|---|
| 配置散落各服务、改一处要重新打包发版 | 外置到 Git 等后端，服务端 REST 提供，客户端启动时拉取 | 第三、四章 |
| 多环境（dev/test/prod）组合爆炸 | profile 维度：`{application}-{profile}.yml` 文件名约定 + 请求参数 `/{name}/{profiles}/{label}` | 4.3、5.4 节 |
| 版本化 / 回滚 / 审计 | label 维度直接映射 Git 分支/Tag；变更历史 = Git 提交历史 | 5.2 节 |
| 不同应用差异化 + 公共配置 | name 维度 + `application.yml`（默认应用）兜底；搜索路径占位符 `{application}/{profile}/{label}` | 4.3、5.4 节 |
| 新服务上线时 Config Server 还没起来 / 挂了 | `fail-fast` 快速失败 + spring-retry 重试 + 多 URI 轮询（`multiple-uri-strategy`）+ discovery-first | 3.5 节 |
| 密码/密钥不能明文进 Git | `{cipher}` 密文 + 服务端 `/encrypt /decrypt` 端点 + KeyStore/RSA | 第六章 |
| 配置改了怎么生效？ | actuator `/refresh` 端点、Bus 广播、webhook `/monitor`、定时轮询 state 四条触发路径 | 第七章 |
| `@Value` 注入的值启动后还能变吗 | 默认不能；`@RefreshScope`（销毁重建代理目标）与 `@ConfigurationProperties`（Rebinder 重绑定）可以 | 7.3、7.5 节 |
| 多个后端想同时用（Git + Vault） | `@Profile` 装配 + `CompositeEnvironmentRepository` 按 `@Order` 组合 + `SearchPathCompositeEnvironmentRepository` 搜索路径拼接 | 4.5 节 |
| 非 Java 服务能用吗 | 服务端 REST 是标准 HTTP+JSON，任何语言可以直接读；但没有官方的多语言客户端 | 9.8 节 |
| 和 Nacos 怎么选 | 数据模型、变更推送、灰度、容错、一致性五条主线的架构对比 + 选型决策树 | 第九章 |

## 1.6 版本演进：1.x → 5.0 关键变化

写作时（2026 年 10 月）的版本格局：**5.0.x**（2025.1 Oakwood，Boot 4 基线）是当前主干线，最新 GA 5.0.5（2026-08-03）；**4.3.x**（2025.0 Northfields，Boot 3.5 基线）并行维护，是绝大多数存量项目所在线；4.2 及更早版本已陆续结束 OSS 维护。本节所有日期均经本地 git tag（`git log -1 --format=%ad <tag>`）逐项实证。

### 1.6.1 版本时间线与运行基线

| 版本 | GA 时间 | 对应 Spring Boot / Cloud 列车 | 一句话主题 |
|---|---|---|---|
| 1.0 | 2015-03-03（`v1.0.0.RELEASE`） | Boot 1.x / Angel-Brixton | 面世：bootstrap 上下文 + `PropertySourceLocator` 奠基 |
| 2.0 | 2018-06-18（`v2.0.0.RELEASE`） | Boot 2.0 / Finchley | Boot 2 基线；多仓库 pattern 匹配成熟 |
| 3.0 | 2020-12-21（`v3.0.0`） | Boot 2.4 / 2020.0 Ilford | **Config Data API 取代 bootstrap**：`spring.config.import=configserver:` 登场 |
| 3.1 | 2021-12-01（`v3.1.0`） | Boot 2.6 / 2021.0 Jubilee | 与 Config Data 语义打磨 |
| 4.0 | 2022-12-15（`v4.0.0`） | Boot 3.0 / 2022.0 Kilburn | **Jakarta EE 9 迁移**（javax.→jakarta.） |
| 4.1 | 2023-12-06（`v4.1.0`） | Boot 3.2 / 2023.0 Leyton | 观测（Observation）API 深化 |
| 4.2 | 2024-12-03（`v4.2.0`） | Boot 3.4 / 2024.0 Moorgate | `send-all-labels` 等增强（见 1.6.2） |
| 4.3 | 2025-05-29（`v4.3.0`） | Boot 3.5 / 2025.0 Northfields | 4.x 末代，存量主流 |
| 5.0 | 2025-11-24（`v5.0.0`） | **Boot 4.0 / 2025.1 Oakwood** | **Jackson 3 迁移**、AOT/原生镜像打磨 |
| 5.0.5（本文快照） | 2026-08-03（`v5.0.5`） | Boot 4.0.8 / 2025.1.3 | 当前 GA |

### 1.6.2 特性演进对照表（源码实证）

| 能力 | 引入版本 | 证据 |
|---|---|---|
| bootstrap 上下文 + `PropertySourceLocator` SPI | 1.0 | 1.x 起 `spring.factories` 就有 `BootstrapConfiguration=ConfigServiceBootstrapConfiguration`（5.0.5 中仍保留作 legacy 路径，见 3.3 节） |
| Config Data 接入（`spring.config.import=configserver:`） | 3.0（随 Boot 2.4） | client 模块 `spring.factories` 注册 `ConfigDataLocationResolver/ConfigDataLoader`（3.2 节）；`ConfigServerConfigDataLoader` 中还保留着对 Boot 2.4.2/2.4.3 选项数量的兼容分支（`ConfigServerConfigDataLoader.java:156-195`） |
| 多 label 请求（`spring.cloud.config.label=a,b`） | 早期 | `AbstractScmEnvironmentRepository#splitAndReorder`（`:103-116`） |
| 失败快速 + 重试 + 多 URI 策略 | 1.x 渐进 | `failFast`（`ConfigClientProperties.java:154`）、`MultipleUriStrategy ALWAYS/CONNECTION_TIMEOUT_ONLY`（`:515-527`） |
| `send-all-labels`（客户端把 label 列表一次性发给服务端） | 4.2 | `ConfigClientProperties.java:180-186` 注释："Support for this would require a config server version of 4.2.0 or higher" |
| 服务端加密值解密失败降级（`invalid.` 前缀） | 早期 | `CipherEnvironmentEncryptor.java:79-82` |
| webhook 校验（SHA256 签名） | 4.x 渐进 | monitor 模块 `Sha256WebhookRequestValidator`、各 Git 提供商 `*WebhookRequestValidator` |
| AOT / GraalVM 原生镜像支持 | 4.x → 5.0 打磨 | client `aot/ConfigClientHints.java`、server `aot/CompositeEnvironmentBeanFactoryInitializationAotProcessor.java` |
| Jackson 3（`tools.jackson.*`） | 5.0 | `EnvironmentController.java:34` 直接 import `tools.jackson.databind.json.JsonMapper`（Boot 4 标志性变化） |

### 1.6.3 对初学者的意义：哪些知识过时了，哪些永远有效

- **过时/弱化**：`bootstrap.yml` 接入方式（仍可用但已不推荐，Boot 2.4 起默认关闭 bootstrap，见 3.3 节）；"Config Server 单点 + 人工 curl /refresh"的老式运维形态。
- **永远有效**：Environment/PropertySource 优先级模型（这是 Spring 全家桶的地基）；`name/profile/label` 三元组检索语义；`EnvironmentRepository` SPI 与组合模式；`{cipher}` 加密语法；`RefreshScope` 销毁重建机制。学懂这些，4.x 和 5.x 之间可以无痛切换。

## 1.7 全文章节地图

```
第一章  总览（你在这里）—— 定位、哲学、模块、版本
第二章  地基 —— Environment 抽象与 wire format（Environment DTO）
第三章  客户端 —— Config Data 与 bootstrap 两种接入、失败快/重试、discovery-first
第四章  服务端 —— REST 契约、EnvironmentRepository 体系、组合与排序、overrides
第五章  后端适配器 —— Git（JGit 全流程）、Native、JDBC、Vault、AWS 三兄弟……
第六章  加密解密 —— {cipher} 语法、/encrypt /decrypt、服务端解密 vs 客户端解密
第七章  动态刷新 —— ContextRefresher、RefreshScope、Rebinder、Bus、monitor
第八章  高可用与运维 —— HA 模型、安全、可观测、运维清单
第九章  与 Nacos 的对比 —— 数据模型/推送/灰度/容错/一致性五条主线 + 选型
第十章  贯通视图 —— 一次配置变更的一生（三条时间线）
```

---

# 二、地基：Spring 环境抽象与"配置如何进入应用"

## 2.1 先白话：配置中心到底要解决什么问题

单机时代，配置就是 `application.properties`，打进 jar 包，改配置=改代码=重新发版。微服务时代它变成三个问题：

1. **规模问题**：N 个服务 × M 个环境 = N×M 份配置文件，散落在各自的代码仓库里，没有统一视角。十二要素应用宣言第三条（Config）早就指出：*Store config in the environment*——配置是与代码严格分离、随环境变化的。
2. **变更问题**：改一个限流阈值要滚动重启几十个 Pod？不行，需要"不改代码就能改配置，且改完能生效"。
3. **安全问题**：数据库密码不能明文提交进 Git，但 Git 又是最顺手的配置存储。

Spring Cloud Config 对这三个问题的回答分别是：**Git 作为唯一真源（配置仓库与代码仓库分离）**、**拉取 + RefreshScope 重建（第七章）**、**{cipher} 密文随值走（第六章）**。

## 2.2 回顾：Environment 与 PropertySource 的优先级链

Spring Framework 的配置模型（本文档的姊妹篇《Spring Framework.md》3.3 节有逐行解析，此处只复述结论）：一切配置读取都经过 `Environment`，它内部维护一个 `MutablePropertySources` 双向链表——**排在前面的 PropertySource 优先**。链表里依次是：系统属性、系统环境变量、命令行参数、`application.yml` 等。

Spring Cloud Config 做的事情，从抽象层面上说只有一件：**在"系统环境变量"之后、"本地 application.yml"之前，插入一个由 Config Server 远程返回的 CompositePropertySource**。至于这个 PropertySource 的内容是从 Git 拉的还是 JDBC 查的，Environment 完全不关心——这就是 1.2 节"一切皆 PropertySource"的含义。

由此推出两条使用铁律（后面多处会用到）：

- **远程配置默认压过本地 application.yml**（因为它插得更靠前），但压不过命令行参数/系统属性——这正好符合"部署参数 > 远程配置 > 本地默认值"的期望。客户端还有 `spring.cloud.config.override-none=true` / `override-system-properties=false` 两个开关可以进一步微调这套优先级（由服务端的 `PropertySourceBootstrapProperties` 消费，本文不展开）。
- **同一个远程 PropertySource 列表内部的顺序也有讲究**：服务端返回的列表里，**排在前面的属性会被排在后面的覆盖还是反过来**？答案在客户端装载代码里，往下看。

## 2.3 wire format：environment.Environment DTO（Config 自己的"协议报文"）

【源码证据】`spring-cloud-config-client/src/main/java/org/springframework/cloud/config/environment/Environment.java:35-52`

```java
public class Environment {
    /** "(_)" is uncommon in a git repo name, but "/" cannot be matched by Spring MVC. */
    public static final String SLASH_PLACEHOLDER = "(_)";           // :40

    private String name;                          // 应用名
    private String[] profiles = new String[0];    // 请求的 profile
    private String label;                         // 版本（Git 分支/Tag）
    private List<PropertySource> propertySources = new ArrayList<>();
    private String version;                       // Git HEAD commit id，用于状态比对
    private String state;                         // 服务端健康状态摘要
```

它包含的每个字段都有精确的用途：

- **name / profiles / label**：请求三要素的回显。特别注意 `SLASH_PLACEHOLDER`——Git 分支名经常含 `/`（如 `release/1.0`），而 Spring MVC 的 `@PathVariable` 切不开斜杠，所以协议规定 URL 里的 `/` 要写成 `(_)`，服务端 `normalize()`（`:83-90`）再转回来。客户端发请求时也做同样处理（`ConfigServerConfigDataLoader.java:306-311` 的 `denormalize`）。
- **propertySources**：核心载荷。列表里的每个元素（`environment/PropertySource.java`）只有两个字段：`name`（形如 `https://github.com/xxx/config-repo/application.yml` 的来源描述）和 `source`（`Map<String, Object>` 扁平键值对）。
- **version**：Git HEAD id。客户端把它存进 `config.client.version` 属性（见 3.2.3 节），`ConfigClientWatch` 和 actuator 端点靠它判断"远端有没有变化"。
- **state**：服务端健康摘要，同样回填给客户端用于状态上报。

【关键设计】**PropertySource 列表的顺序约定**：服务端返回的列表就是服务端内部 Environment 的优先级顺序——**最具体**的源（如 `order-service-prod.yml`）在**列表前部**，**最通用**的源（如 `application.yml`）在**列表后部**（服务端 `PassthruEnvironmentRepository` 按内部顺序原样拷贝，`PassthruEnvironmentRepository.java:79-90`）。客户端装载时用 `propertySources.add(0, ...)` **倒序插桩**（`ConfigServerConfigDataLoader.java:141-142`），把列表反转后交给 Boot。但注意——**"具体配置覆盖通用配置"并不是只靠插入位置实现的**：客户端还会按属性源名字是否匹配 `-profile` 后缀给源打上 Boot 的 `PROFILE_SPECIFIC` Option（3.2.3 节），Boot 的 ConfigData 装配会先把带此标记的源提升到"profile 激活后的导入桶"（spring-boot `ConfigDataEnvironmentContributor#moveProfileSpecific`，`ContributorIterator` 先遍历该桶再 `addLast`），使其在最终环境中位置最靠前。两层机制合起来，`order-service-prod.yml` 才能稳压 `application.yml`——与本地 Boot 加载 yml 的直觉完全一致。这个"协议顺序 = 服务端内部顺序、装载时反转、Option 再提升"的三段配合，是读源码时最容易迷路的地方，记住结论即可。

## 2.4 配置进入应用的三个时机

| 时机 | 发生阶段 | 接入方式 | 源码入口 |
|---|---|---|---|
| 启动拉取（现代） | `spring.config.import=configserver:` 处理阶段（Environment 准备期，早于容器 refresh） | Config Data API | `ConfigServerConfigDataLocationResolver` / `ConfigServerConfigDataLoader`（3.2 节） |
| 启动拉取（legacy） | bootstrap 上下文（父容器）先行启动 | `BootstrapConfiguration` + `PropertySourceLocator` | `ConfigServiceBootstrapConfiguration` + `ConfigServicePropertySourceLocator`（3.3 节） |
| 运行时刷新 | actuator /refresh 或 Bus 事件到达后 | 重新执行 Config Data 流程 + diff + 事件 | `ContextRefresher` / `ConfigDataContextRefresher`（7.2 节） |

三个时机最终都汇聚到同一个方法 `getRemoteEnvironment()`：拼 `GET {uri}/{name}/{profiles}[/{label}]` 请求、带 Accept/TOKEN/STATE 头、解析 `environment.Environment` JSON。**一条 HTTP 契约支撑三个生命周期阶段**——这是 Config 客户端代码量不大却很稳的原因。

## 2.5 本章小结

- Config 的本质是"Environment 抽象的远程化"：协议报文 = 有序 PropertySource 列表，与 Spring 内部模型同构。
- 顺序是这套协议的灵魂：服务端按"具体在前"返回，客户端倒序插桩，最终"具体配置覆盖通用配置"。
- 配置进入应用只有三个时机（启动 Config Data、启动 bootstrap-legacy、运行时刷新），三者共用同一条 HTTP 契约。

---

# 三、客户端（config-client）：一次远程配置拉取的全过程

## 3.1 模块定位与两条接入路线

`spring-cloud-config-client` 是一个**纯 Spring Boot 自动装配模块**——它的所有注册点只有两份文件：

【源码证据】`spring-cloud-config-client/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（全文 1 行）

```
org.springframework.cloud.config.client.ConfigClientAutoConfiguration
```

【源码证据】`spring-cloud-config-client/src/main/resources/META-INF/spring.factories`（关键行）

```properties
# BootstrapConfiguration = ConfigServiceBootstrapConfiguration, DiscoveryClientConfigServiceBootstrapConfiguration   ← legacy 路线
# EnvironmentPostProcessor = ConfigServerConfigDataMissingEnvironmentPostProcessor                                    ← "忘写 import" 检查器
# ConfigDataLocationResolver = ConfigServerConfigDataLocationResolver                                                 ← Config Data 路线：解析器
# ConfigDataLoader = ConfigServerConfigDataLoader                                                                     ← Config Data 路线：装载器
# BootstrapRegistryInitializer = ConfigClientRetryBootstrapper                                                        ← 失败重试挂载点
```

两条接入路线由 Spring Boot 版本和用户的配置决定：

| | Config Data 路线（推荐） | bootstrap 路线（legacy） |
|---|---|---|
| 用户写法 | `spring.config.import=configserver:http://...` | `spring.cloud.config.uri=...`（无需 import，依赖 bootstrap 开启） |
| 生效阶段 | `ConfigDataEnvironmentPostProcessor` 内（Boot 2.4+ 默认） | 独立 bootstrap 父容器，先于主容器启动 |
| 注册机制 | `ConfigDataLocationResolver`/`ConfigDataLoader` SPI | `BootstrapConfiguration` + `PropertySourceLocator` |
| 优先级控制 | 由 Boot 的 `ConfigData.Option`（PROFILE_SPECIFIC 等）精确控制 | `spring.cloud.config.override-none` 等自有开关 |
| 适合 | 一切新项目 | 老项目兼容、`spring.cloud.bootstrap.enabled=true` 显式开启 |

下面按推荐路线展开，legacy 路线只讲差异。

## 3.2 Config Data 模式：`spring.config.import=configserver:`

### 3.2.1 先白话：Spring Boot 2.4 之后配置是怎么加载的

Boot 2.4 起引入 Config Data API：把"从哪里加载配置"抽象成可插拔的 `config:xxx` 导入协议——`classpath:`、`file:`、`configtree:`，以及本文的主角 `configserver:`。Boot 在 Environment 准备阶段做两步：**先"解析"**（把 `configserver:` 字符串解析成 Resource 列表），**再"装载"**（对每个 Resource 实际发起加载，产出 PropertySource）。Config client 在这两步各插了一个实现类。

### 3.2.2 解析器：ConfigServerConfigDataLocationResolver

【源码证据】`spring-cloud-config-client/.../ConfigServerConfigDataLocationResolver.java:207-213`（是否受理）

```java
public boolean isResolvable(ConfigDataLocationResolverContext context, ConfigDataLocation location) {
    if (!location.hasPrefix(getPrefix())) {     // 只认 "configserver:" 前缀（:62）
        return false;
    }
    return context.getBinder().bind(ConfigClientProperties.PREFIX + ".enabled", Boolean.class).orElse(true);
}
```

解析的主流程在 `resolveProfileSpecific()`（`:226-316`），做了五件事：

1. **绑定客户端配置**：`loadProperties()`（`:112-197`）把 `spring.cloud.config.*` 绑定成 `ConfigClientProperties`。两个细节值得注意——(a) 应用名兜底链：`spring.cloud.config.name` 未设置时取 `spring.application.name`，再兜底 `"application"`（`:144-150`）；(b) **import 字符串本身可以携带参数**：`spring.config.import=configserver:http://s1:8888?fail-fast=true&max-attempts=6`，参数会覆盖 yml 里的同名设置（`:158-193`）。
2. **注册 bootstrap 上下文单例**：`RestTemplate`（含超时/ Basic 认证设置，由 `ConfigClientRequestTemplateFactory` 创建）、`ConfigClientProperties`（PROTOTYPE 作用域，每次注入的是同一份但随发现服务刷新）、`PropertyResolver`（`:236-252`）。这些对象生命周期跨越 bootstrap 与主容器，是 Config Data 与旧世界的粘合剂。
3. **可选：加密能力的提前装配**：`setTextEncryptorDelegate()`（`:89-110`）——bootstrap 阶段可能只能创建 `FailsafeTextEncryptor`（占位实现），此时若 yml 已解析出 `encrypt.key`，就地替换其内部 delegate，使 `{cipher}` 值在**首次导入时就能解密**。
4. **可选：discovery-first**：`spring.cloud.config.discovery.enabled=true` 时注册 `ConfigServerInstanceMonitor`，从注册中心（Eureka/Nacos/Consul）按 serviceId（默认 `configserver`）找 Server 实例，把解析出的真实 URL 回填进 uri 列表（`:269-310`），并提升为主容器单例以便心跳时更新。
5. **产出 Resource**：一个 `ConfigServerConfigDataResource`（携带 properties + profiles + 是否 optional + retry 配置），交给 Loader。

### 3.2.3 装载器：ConfigServerConfigDataLoader（客户端的心脏）

【源码证据】`spring-cloud-config-client/.../ConfigServerConfigDataLoader.java:109-223`（`doLoad`，有删节）

```java
public ConfigData doLoad(ConfigDataLoaderContext context, ConfigServerConfigDataResource resource) {
    ...
    try {
        String[] labels;                                   // label 支持 "a,b" 逐个尝试（:116-129）
        for (String label : labels) {
            Environment result = getRemoteEnvironment(context, resource, label.trim(), state);
            if (result != null && result.getPropertySources() != null) {
                for (org.springframework.cloud.config.environment.PropertySource source : result.getPropertySources()) {
                    Map<String, Object> map = translateOrigins(source.getName(), source.getSource());
                    propertySources.add(0,                 // ★ 倒序插桩：把"具体在前"的协议顺序反转
                        new OriginTrackedMapPropertySource("configserver:" + source.getName(), map, true));
                }
                HashMap<String, Object> map = new HashMap<>();
                if (StringUtils.hasText(result.getState()))   putValue(map, "config.client.state", result.getState());
                if (StringUtils.hasText(result.getVersion())) putValue(map, "config.client.version", result.getVersion());
                // the existence of this property source confirms a successful response from config server
                propertySources.add(0, new MapPropertySource(CONFIG_CLIENT_PROPERTYSOURCE_NAME, map));
                ...
                return new ConfigData(propertySources, propertySource -> {   // ★ ConfigData 选项（下文）
                    ...
                });
            }
        }
        errorBody = String.format("None of labels %s found", Arrays.toString(labels));
    } catch (...) { ... }
    if (properties.isFailFast() || !resource.isOptional()) {   // ★ 失败策略（3.5 节）
        throw new ConfigClientFailFastException("Could not locate PropertySource ...", error);
    }
    logger.warn("Could not locate PropertySource (" + resource + "): " + ...);
    return null;                                               // 静默降级：应用照常启动，只是没有远程配置
}
```

逐点拆解：

- **HTTP 请求细节**（`getRemoteEnvironment()`，`:276-396`）：路径模板 `/{name}/{profile}[/{label}]`，label 经 `denormalize` 处理斜杠；请求头带 `Accept: application/vnd.spring-cloud-config-server.v2+json`（V2 格式会回传属性来源 origin）、`X-Config-Token`（透传给 Vault 等后端）、`X-Config-State`（客户端自己的状态）。多 URI 时按序尝试：`HttpClientErrorException/HttpServerErrorException` 或连接异常时，`MultipleUriStrategy.ALWAYS`（默认）换下一个 URI，`CONNECTION_TIMEOUT_ONLY` 只在"完全连不上"时换（`:351-370`）。404 被特殊对待——它不算错误，只是"这个 label 下没有配置"。
- **`configClient` 哨兵 PropertySource**（`:146-155`）：成功响应后额外塞入一个只含 `config.client.state` / `config.client.version` 的 Map 源。注释写得很直白：*its existence confirms a successful response*。它同时是 `ConfigClientWatch`（3.7 节）轮询比对的依据。
- **ConfigData 选项**（`:164-195`）：给每个 PropertySource 打上 `IGNORE_IMPORTS`（防止远程配置里的 `spring.config.import` 再触发二次导入——Config Server 返回的内容里不该再"链式导入"）与 `PROFILE_SPECIFIC` 判定（按属性源名字匹配 `-profile` 后缀；`configserver:overrides` 恒为 profile-specific，保证 overrides 压过一切，见 4.6 节）。**这就是 Config Data 路线在优先级语义上比 bootstrap 路线精确的原因**：Boot 原生知道哪些源是 profile 专属的。
- **origin 追踪**（`translateOrigins`，`:246-268`）：V2 JSON 里每个值都带 `origin`（来自哪个文件哪一行），客户端包装成 `OriginTrackedValue`——于是 `/actuator/env` 能看到每个远程属性的真实出处。
- **插件化拦截**（`load()`，`:86-107`）：`ConfigServerBootstrapper.withLoaderInterceptor()` 允许用户在装载前后插桩（重试、熔断、自定义 RestTemplate 都走这个口子，3.5 节的 retry 就是官方示例）。

### 3.2.4 忘写 `spring.config.import` 会怎样？

【源码证据】`spring-cloud-config-client/.../ConfigServerConfigDataMissingEnvironmentPostProcessor.java:35-60,63-81`

一个 `EnvironmentPostProcessor`（order = `ConfigDataEnvironmentPostProcessor.ORDER + 1000`，紧跟 Boot 主流程之后）检查：classpath 上有 config client、却没有任何 `configserver:` 导入 → 直接抛 `ImportException` 让启动失败，并由 `ImportExceptionFailureAnalyzer`（`:63-81`）输出人话报告：

```
Add a spring.config.import=configserver: property to your configuration.
    If configuration is not required add spring.config.import=optional:configserver: instead.
    To disable this check, set spring.cloud.config.enabled=false or
    spring.cloud.config.import-check.enabled=false.
```

设计意图：加了 client 依赖却没配 import 几乎必然是失误，与其让应用"静默地没有远程配置"，不如启动期就报错。这也是无数升级到 Boot 2.4+ 的老项目遇到的第一个报错的出处。

## 3.3 legacy 路线：bootstrap 模式三十秒通览

`spring-cloud-context` 的 bootstrap 机制会根据 `spring.factories` 里的 `BootstrapConfiguration` 先启动一个轻量父容器。Config client 的 legacy 注册是 `ConfigServiceBootstrapConfiguration`，它创建 `ConfigClientProperties`（前缀绑定 `bootstrap` 阶段的环境）和 `ConfigServicePropertySourceLocator`：

【源码证据】`spring-cloud-config-client/.../ConfigServicePropertySourceLocator.java:70-72,105-125`

```java
@Order(0)
public class ConfigServicePropertySourceLocator implements PropertySourceLocator {
    @Override
    @Retryable(interceptor = "configServerRetryInterceptor")     // ← retry 在这里是注解式（spring-retry AOP）
    public PropertySource<?> locate(Environment environment) {
        ...
        CompositePropertySource composite = new OriginTrackedCompositePropertySource("configService");
```

它与 Config Data 路线的逻辑几乎逐行对应（同一个 `getRemoteEnvironment` 骨架、同样的 label 循环、同样的 failFast 抛错），差异只有三点：属性源统一塞进名为 `configService` 的 Composite（因此 `/actuator/env` 里看到的名字不同）；额外校验应用名不能以 `application-` 开头（`:113-123`，防止 `spring.application.name=application-dev` 造成文件名歧义——Config Data 路线没有这个拦截，靠 Boot 自己的文件名匹配语义天然规避）；优先级由 `PropertySourceBootstrapProperties` 的三个开关（`override-none`/`override-system-properties`/`allow-override`）粗粒度控制，而不是 Boot 原生 Option。

**结论：新项目一律用 Config Data。** bootstrap 路线保留是为了兼容，两者不建议混用。

## 3.4 ConfigClientProperties：客户端全参数手册

【源码证据】`spring-cloud-config-client/.../ConfigClientProperties.java:42-186`（前缀 `spring.cloud.config`）

| 属性 | 默认值 | 含义与实战要点 |
|---|---|---|
| `enabled` | `true` | 总开关；`false` 时 resolver 不受理、import-check 也关闭 |
| `name` | `${spring.application.name:application}` | 请求的"应用名"维度；决定服务端搜什么文件 |
| `profile` | `default` | 逗号分隔；构造器里默认取当前 active profiles（`:191-197`） |
| `label` | 无（服务端默认 `main`） | 版本维度（Git 分支/Tag）；支持逗号分隔多 label 逐个尝试；4.2+ 支持 `send-all-labels` 一次下发 |
| `uri` | `http://localhost:8888` | **数组**，多实例按序轮询（HA 关键，3.5 节）；支持 `?fail-fast=true&...` 内联参数；支持 `user:pass@host` 内嵌凭证 |
| `multiple-uri-strategy` | `ALWAYS` | `ALWAYS`=任何错误换下一个；`CONNECTION_TIMEOUT_ONLY`=仅连接失败换（避免 5xx 时无脑轮询） |
| `media-type` | `v2+json` | V2 回传 origin，便于 `/actuator/env` 排查来源 |
| `fail-fast` | `false` | 拉不到就启动失败（配合 retry 使用）；**生产建议 true**——宁可起不来也不要"悄悄用本地默认配置上线" |
| `token` | 无 | 放入 `X-Config-Token` 头透传给服务端后端（如 Vault token） |
| `request-connect-timeout` / `request-read-timeout` | 10s / 185s（`60*3+5` 秒） | 读超时给得很大，因为服务端 Git 冷启动 clone 可能很慢 |
| `send-state` | `true` | 回传客户端状态给服务端 |
| `headers` | 空 | 附加自定义请求头 |
| `discovery.enabled/service-id` | `false` / `configserver` | discovery-first 模式（3.2.2 第 4 步） |
| `tls.*` | 无 | 客户端侧 HTTPS 证书（trust-store/key-store） |

凭证提取的优先级（`extractCredentials()`，`:355-406`）：URL 内嵌 `user:pass` 与显式 `username/password` 并存时，**显式属性优先**（但 URL 里单独写了 password 时，URL 的 password 不会被显式 username 覆盖——源码 `:388-397` 的注释专门解释了这个边角）。

## 3.5 失败策略三件套：fail-fast、retry、多 URI 轮询

三者语义的精确边界（这是面试与排障高频区）：

1. **多 URI 轮询**是"单次请求内"的容错：URI 数组逐个试，全失败才算失败（`getRemoteEnvironment` 的 for 循环）。
2. **retry**是"整个装载动作"的重试：`ConfigClientRetryBootstrapper`（spring.factories 注册的 `BootstrapRegistryInitializer`）检查 classpath 有无 spring-retry，若 `fail-fast=true` 则向 BootstrapRegistry 注册一个 `LoaderInterceptor`——用 `RetryTemplateFactory`（默认 initialInterval=1000ms、multiplier=1.1、maxAttempts=6）包裹整个 `doLoad` 调用（`ConfigClientRetryBootstrapper.java:42-55`）。**注意：不开 fail-fast 就不会重试**——因为"拉不到就用本地默认"本来就是可接受的降级，重试反而拖慢启动。
3. **fail-fast**决定失败的后果：`true` 或 resource 非 optional（`configserver:` 不带 `optional:` 前缀）→ 抛 `ConfigClientFailFastException` 终止启动；`false` 且 optional → 打 WARN 日志、返回 null、应用继续起。

生产推荐组合：`spring.config.import=optional:configserver:...`（或 fail-fast=true）+ `spring-retry` 依赖 + 多实例 URI 列表 / discovery。

## 3.6 discovery-first：从注册中心找 Config Server

`spring.cloud.config.discovery.enabled=true` 后，客户端不再直连 URL，而是按 `discovery.service-id`（默认 `configserver`）从注册中心查实例（`ConfigServerInstanceProvider`），选出实例后把真实 uri/username/password 回填进 `ConfigClientProperties`（resolver `:130-137`），后续心跳（`HeartbeatEvent`）时 `ConfigServerInstanceMonitor.refresh()` 持续更新——Server 扩缩容对客户端透明。代价是客户端必须同时引入 discovery 依赖，且**启动期多了一次注册中心查询**。

## 3.7 健康检查与变更轮询

- **健康指示器**（默认开启，`ConfigClientAutoConfiguration.java:58-70`，健康端点名 `config`）：`ConfigServerHealthIndicator` 缓存地检查客户端 Environment 里的 `configService`（或 `configserver:` 前缀）属性源是否为空——**它检查的是"启动时拉没拉到"，不会主动探测 Server**，所以 UP 不代表 Server 现在活着。
- **ConfigClientWatch**（默认关闭，`spring.cloud.config.watch.enabled=true` 开启）：`@Scheduled` 每 500ms 比对环境里的 `config.client.state` 与 `ConfigClientStateHolder` 里的记录，不一致就调 `ContextRefresher.refresh()`（`ConfigClientWatch.java:60-73`）。**本质是对 Config Server 的定时轮询**，官方默认关掉它、把定时职责推给 Bus，是避免 N 客户端 × 高频轮询压垮 Server——这也再次印证 Config 的"拉模型"定位。

## 3.8 本章小结

- 客户端只有两个注册点：`AutoConfiguration.imports`（主容器运维件）+ `spring.factories`（Config Data SPI / legacy bootstrap / 失败检查器）。
- Config Data 路线：Resolver 绑定配置并注册跨阶段单例 → Loader 发 HTTP、倒序插桩 PropertySource、写入 state/version 哨兵源、按 Option 打优先级标记。
- 忘写 `spring.config.import` 会在启动期被 `ConfigServerConfigDataMissingEnvironmentPostProcessor` 拦下并给出人话修复建议。
- 失败策略三层：URI 内轮询 → fail-fast 门控的 retry → optional 决定"报错"还是"降级继续"。

---

# 四、服务端（config-server）：EnvironmentRepository 体系与 REST 契约

## 4.1 启动装配：一个注解，五层 @Import

【源码证据】`spring-cloud-config-server/.../EnableConfigServer.java` + `config/ConfigServerAutoConfiguration.java:32-35`

```java
@EnableConfigServer                    // 用户唯一要写的注解（@Import(ConfigServerConfiguration.class)，经 Marker 导入下面的自动配置）
   └─► ConfigServerAutoConfiguration   // 同时也在 AutoConfiguration.imports 第 2 行（加依赖即可触发）
         @Import({ EnvironmentRepositoryConfiguration.class, CompositeConfiguration.class,
                   ResourceRepositoryConfiguration.class, ... })   // 仓储/组合/资源/加密装配
```

`spring-cloud-config-server/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 共 6 行：`ConfigServerBootstrapOverridesAutoConfiguration`、`ConfigServerAutoConfiguration`、`RsaEncryptionAutoConfiguration`、`DefaultTextEncryptionAutoConfiguration`、`EncryptionAutoConfiguration`、`VaultEncryptionAutoConfiguration`。另外 server 的 `spring.factories` 还注册了 `EnvironmentPostProcessor = ConfigServerBootstrapApplicationListener`（4.7 节的主角）。

## 4.2 后端选择：@Profile 驱动的十二路开关

【源码证据】`spring-cloud-config-server/.../config/EnvironmentRepositoryConfiguration.java:110-134`（@Import 列表尾部）

```java
@Import({ CompositeRepositoryConfiguration.class, JdbcRepositoryConfiguration.class, VaultConfiguration.class,
        SpringVaultRepositoryConfiguration.class, CredhubConfiguration.class, CredhubRepositoryConfiguration.class,
        SvnRepositoryConfiguration.class, NativeRepositoryConfiguration.class, GitRepositoryConfiguration.class,
        RedisRepositoryConfiguration.class, ..., GoogleSecretManagerRepositoryConfiguration.class, MongoRepositoryConfiguration.class,
        // DefaultRepositoryConfiguration must be last
        DefaultRepositoryConfiguration.class })
```

每个后端一个嵌套配置类，形如：

```java
@Profile("git")        // :405 —— 服务端 spring.profiles.active 决定用哪个后端
class GitRepositoryConfiguration extends DefaultRepositoryConfiguration { ... }

@Profile("native")     // :393
@Profile("awss3")      // :411 ... 其余同理（vault/svn/jdbc/credhub/redis/mongodb/awsparamstore/awssecretsmanager/secretmanager）
```

要点：

- **服务端用 Spring profile 选后端**——`--spring.profiles.active=git`（也是缺省兜底）、`native`、`jdbc`……这与客户端的 `spring.cloud.config.profile`（业务环境）是两套互不相干的 profile，初学者最容易混淆。
- `DefaultRepositoryConfiguration`（`:380-392`）带 `@ConditionalOnMissingBean(EnvironmentRepository.class)`，且必须放在 @Import 最后——**什么后端都没配时兜底一个 Git 仓储**（此时没有 uri，首次访问会报"需要配置 uri"）。
- 每个后端的创建走 **Factory 模式**（`EnvironmentRepositoryFactory` 接口）：Properties → Repository 的构造逻辑收敛在 Factory 里，方便用户替换某个后端的默认参数。

## 4.3 EnvironmentRepository SPI：一个方法撑起全部后端

【源码证据】`spring-cloud-config-server/.../environment/EnvironmentRepository.java:25-30`

```java
public interface EnvironmentRepository {
    Environment findOne(String application, String profile, String label);
    default Environment findOne(String application, String profile, String label, boolean includeOrigin) {
        return findOne(application, profile, label);
    }
}
```

配套两个"可选能力"接口：`SearchPathLocator`（暴露文件系统搜索路径，供 ResourceController 找原始文件用）与 `Ordered`（组合时的优先级）。**加新后端 = 实现 findOne + 声明 Factory + 注册配置类**，REST 层零改动——这就是 1.2 节哲学 2 的落地。

## 4.4 REST 契约（一）：EnvironmentController

【源码证据】`spring-cloud-config-server/.../environment/EnvironmentController.java:69-141`（端点一览）

| 路径 | 产出 | 说明 |
|---|---|---|
| `GET /{name}/{profiles}` | JSON `Environment` | 默认 label |
| `GET /{name}/{profiles}/{label}` | JSON `Environment` | label 中的 `/` 写作 `(_)`（2.3 节） |
| 同上，`Accept: vnd.spring-cloud-config-server.v2+json` | JSON + origin | 客户端 media-type 默认值 |
| `GET /{name}-{profiles}.yml`（`/{label}/` 前缀同理） | 聚合后的 YAML 文本 | `resolvePlaceholders` 参数默认 true：用配置值替换文件里的 `${...}` |
| `GET /{name}-{profiles}.properties` / `.json` | 同上 | `.properties` 不支持含 `-` 的 profile（`:287-292`，会与文件名约定混淆） |

核心逻辑 `getEnvironment()`（`:143-161`）只有三步：校验 profile 合法性（防路径注入）→ `repository.findOne(name, profiles, label, includeOrigin)` → `acceptEmpty=false` 时空结果抛 404。异常映射（`:272-285`）：`RepositoryException`→404、`IllegalArgumentException`→400、`EnvironmentException`→500。

【容易忽略的细节】`convertToProperties()`（`:308-339`）把 Environment 扁平化成文本时，先 `Collections.reverse(sources)` 再逐个 `put`——**反转后遍历 + 后写覆盖先写 = "DTO 列表中排在前面的源获胜"**，即具体文件（`order-service-prod.yml`）的值覆盖通用文件——与 2.3 节"具体在前"的协议顺序自洽。数组型 key（`xxx[0]`）单独按前缀归组覆盖，避免部分覆盖错位。

## 4.5 Composite 与排序：多后端如何合作

【源码证据】`spring-cloud-config-server/.../environment/CompositeEnvironmentRepository.java:51-60,79-104`

```java
public CompositeEnvironmentRepository(List<EnvironmentRepository> environmentRepositories,
        ObservationRegistry observationRegistry, boolean failOnError) {
    // Sort the environment repositories by the priority
    Collections.sort(environmentRepositories, OrderComparator.INSTANCE);   // 按 @Order 升序
    ...
    this.failOnError = failOnError;
}
...
public Environment findOne(...) {
    for (EnvironmentRepository repo : environmentRepositories) {
        try {
            env.addAll(repo.findOne(...).getPropertySources());   // 顺序拼接各后端的属性源
        } catch (Exception e) {
            if (failOnError) throw e; else log.info(...);         // spring.cloud.config.server.composite 的 fail-on-error
        }
    }
    return env;
}
```

- **`@Order` 小者先被查询、其属性源排在 Environment 列表前部**；结合 2.3 节客户端的反转插桩——**Order 大（后查）的后端配置优先级更高**（"更具体的后端放后面"）。用户用 `spring.cloud.config.server.{backend}.order` 控制。
- **两个 Composite 变体**：`SearchPathCompositeEnvironmentRepository` 额外实现 `SearchPathLocator`（把各后端的搜索路径拼起来，给 ResourceController 用）；`CompositeConfiguration`（`config/CompositeConfiguration.java`）在用户声明了多个后端 profile（如 `git,jdbc`）时自动生效。
- **可观测包装**：`ObservationEnvironmentRepositoryWrapper`（`:55-58`）把每个后端的 findOne 包成 Observation， micrometer 侧可见 `spring.cloud.config.server.environment.repository` 埋点（`DocumentedConfigObservation`）。

## 4.6 overrides：服务端的"最终覆盖层"

`ConfigServerProperties.overrides`（`config/ConfigServerProperties.java:64`）允许服务端强制注入一批属性（典型如统一的后端地址、日志开关）。实现是一个装饰器：

【源码证据】`spring-cloud-config-server/.../environment/EnvironmentEncryptorEnvironmentRepository.java:63-74`（有删节）

```java
public Environment findOne(...) {
    Environment environment = this.delegate.findOne(application, profile, label, includeOrigin);
    if (this.environmentEncryptors != null) {
        for (EnvironmentEncryptor environmentEncryptor : this.environmentEncryptors) {
            environment = environmentEncryptor.decrypt(environment);   // {cipher} 解密（第六章）
        }
    }
    if (overrides != null) { ... environment.addFirst(overrides source); }  // ★ overrides 永远 addFirst
    return environment;
}
```

`addFirst`（DTO 列表最前）+ 客户端倒序插桩 + **客户端给 overrides 源恒打 `PROFILE_SPECIFIC` Option**（3.2.3 节）⇒ Boot 把它提升到 profile 激活桶最先装配，**overrides 在客户端环境中优先级最高，压过一切 profile 专属源**。

## 4.7 服务端自己别当自己的客户端：防自调用循环

Config Server 本身也是 Spring Boot 应用、classpath 上又有 config-client——若不加干预，它会试图把**自己**当配置来源（拉自己的 `/application/default`），而那个请求又需要自己先起来……死锁。

【源码证据】`spring-cloud-config-server/.../bootstrap/ConfigServerBootstrapApplicationListener.java:65-75`

```java
// 预置 MapPropertySource("configServerClient", {"spring.cloud.config.enabled": "false"}) (:65-66)
protected void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
    ...
    if (environment.getProperty("spring.cloud.config.enabled", Boolean.class, false)) {
        // 显式开启则尊重用户（此时通常配合 spring.cloud.config.server.bootstrap=true 走内嵌模式）
    } else {
        environment.getPropertySources().addLast(configServerClientSource);   // 默认禁用 client
    }
}
```

想反过来"服务端从自己的仓储里取配置启动"（内嵌模式）：`spring.cloud.config.server.bootstrap=true`，此时 `ConfigServerBootstrapConfiguration`（`:42-75`）注册 `EnvironmentRepositoryPropertySourceLocator`，直接调用本地 EnvironmentRepository 而非 HTTP 自调。

## 4.8 ResourceController：原始文件与二进制端点

【源码证据】`spring-cloud-config-server/.../resource/ResourceController.java:118-148,155-199`

`EnvironmentController` 返回的是**聚合解析后的属性**；ResourceController 返回的是**仓库里真实存在的文件**：

- `GET /{name}/{profile}/{label}/**`：按 `SearchPathLocator` 定位文件（Git 仓储即工作副本路径），`**` 部分为文件相对路径；
- 支持 HTTP 304（按 `lastModified` 缓存协商，`checkNotModified` `:257-265`）、`useDefaultLabel` 参数、二进制端点（`produces=octet-stream`）；
- 明文文件也可做占位符替换（`resolvePlaceholders`）与**明文加密文件解密**（`ResourceEncryptor`，按 yml/properties/json 三种格式分别处理，见 6.5 节）；
- `retrieve` 方法是 `synchronized` 的（`:151-154` 注释）：底层 JGit 工作副本非线程安全，与 EnvironmentController 可能并发触发磁盘更新。

ResourceController 的典型用途：下载日志格式模板、配置包、TLS 证书等**非属性文件**，让 Git 兼任"轻量制品库"。

## 4.9 本章小结

- 服务端装配一句话：`@EnableConfigServer` → `EnvironmentRepositoryConfiguration` 按 **服务端 profile** 选后端（Factory 模式）→ 包上 `EnvironmentEncryptorEnvironmentRepository`（解密 + overrides）→ 按 `@Order` 进 `CompositeEnvironmentRepository`。
- REST 双入口：EnvironmentController（聚合属性，四种格式）与 ResourceController（原始文件/二进制，304 缓存）。
- 顺序规则贯穿始终：`@Order` 决定后端查询次序，"后查者胜"；overrides 恒定最优先。
- Server 默认禁用自己的 client 能力，防自调用死锁。

---

# 五、配置后端适配器：Git 为什么是第一公民

## 5.1 抽象层次总览

后端体系分四层，读源码前先对号入座：

```
EnvironmentRepository（SPI）
 ├── AbstractScmEnvironmentRepository        ← SCM 家族公共骨架（Git/SVN）
 │     └── JGitEnvironmentRepository / SvnKitEnvironmentRepository
 │           └── MultipleJGitEnvironmentRepository（pattern 多仓库）
 ├── NativeEnvironmentRepository             ← 本地/类路径文件（也是 SCM 的"文件加载器"）
 ├── AbstractVaultEnvironmentRepository      ← Vault 家族（KV1/KV2）
 │     └── SpringVaultEnvironmentRepository
 └── 直实现：Jdbc / Redis / CredHub / AwsS3 / AwsParameterStore / AwsSecretsManager
              / GoogleSecretManager / MongoDb
```

每个后端还有三件套：`XxxEnvironmentProperties`（`spring.cloud.config.server.xxx.*` 配置）、`XxxEnvironmentRepositoryFactory`（装配）、可选的 `@Profile` 配置类。

## 5.2 核心洞察：SCM 仓库 checkout 之后，交给 Boot 自己解析

【源码证据】`spring-cloud-config-server/.../environment/AbstractScmEnvironmentRepository.java:69-101`（有删节）

```java
public synchronized Environment findOne(String application, String profile, String label, boolean includeOrigin) {
    var environment = new Environment(application, ..., label, "", "");
    List<String> labels = splitAndReorder(label);        // 多 label：反转后逐个尝试（后给的 label 优先）
    for (String l : labels) {
        var e = findOneInternal(application, profile, l, includeOrigin);
        environment.addAll(e.getPropertySources());
        environment.setVersion(concat(e.getVersion(), environment.getVersion()));
        ...
    }
    return this.cleaner.clean(environment, getWorkingDirectory().toURI().toString(), getUri());
}

private Environment findOneInternal(...) {
    var delegate = new NativeEnvironmentRepository(getEnvironment(), new NativeEnvironmentProperties(), ...);
    var locations = getLocations(application, profile, label);   // ← 子类实现：Git 这里做 fetch/checkout
    delegate.setSearchLocations(locations.getLocations());       //   把工作副本路径交给 Native
    var environment = delegate.findOne(application, profile, "", includeOrigin);
    environment.setVersion(locations.getVersion());              //   version = Git HEAD id
    return environment;
}
```

而 `NativeEnvironmentRepository.findOne` 的实现（`NativeEnvironmentRepository.java:141-189`）再次超出直觉——它 new 了一个全新的 `StandardEnvironment`，塞入三个引导属性（`:255-271`）：

```java
map.put("spring.profiles.active", profile);
map.put("spring.config.name", "application," + application);   // 搜 application.yml + {app}.yml
map.put("spring.config.location", StringUtils.arrayToDelimitedString(getLocations(...), ";"));
map.put("spring.config.on-not-found", "IGNORE");
...
ConfigDataEnvironmentPostProcessor.applyTo(environment, resourceLoader, null, profiles, listener);
```

然后用 Boot 的 **`ConfigDataEnvironmentPostProcessor.applyTo()`** 加载文件，最后 `clean()` 把内部源名（`Config resource 'file [xxx.yml]' via location ...'`）清洗成对外友好的名字（`:277-336`）。

**这意味着**：Git 仓库里的 `application-prod.yml`、多文档 YAML、`spring.config.activate.on-profile`、`${}` 占位符——凡是 Boot 本地加载支持的特性，Config Server 服务端解析时全部支持，且解析逻辑永远与最新 Boot 对齐。这是"装配工不写解析器"哲学（1.2 节哲学 3）的完美体现，也是理解整个服务端的钥匙。

## 5.3 JGitEnvironmentRepository：拉取全流程逐行

【源码证据】`spring-cloud-config-server/.../environment/JGitEnvironmentRepository.java`

**主入口 `refresh(label)`**（`:316-358`）——每次 `findOne` 都会走到（除非 refreshRate 节流）：

```
refresh(label)
 ├─ createGitClient()                  (:627-642)  .git/index.lock 残留 → 清理后继续（上次 JVM 崩溃遗留）
 │    ├─ basedir 已有 .git → openGitRepository()
 │    └─ 否则 copyRepository()         (:656-666)  首次：重建安全 basedir → cloneToBasedir()
 ├─ shouldPull(git)                    (:494-525)  refreshRate 节流 + working tree 是否 clean
 ├─ fetch(git, label)                  (:564-588)  fetch origin（失败仅告警，用本地旧数据兜底）
 │    └─ deleteUntrackedLocalBranches  (:437-480)  远端分支已删 & 开关开 → 删本地跟踪分支
 ├─ checkout(git, label)               (:482-492)  分支 → track origin/{label}；Tag/commit → 直接 checkout
 ├─ tryMerge(git, label)               (:360-375)  merge origin/{label}；仍 dirty/领先 → resetHard 到 origin/{label}
 └─ return HEAD id                     (:334)      version = 当前 HEAD commit
```

关键行为逐个说透：

- **懒加载与 clone-on-start**：默认第一次请求才 clone（首请求可能超时——这就是客户端 `request-read-timeout` 给 185 秒的原因）；`spring.cloud.config.server.git.clone-on-start=true` 改为启动时 clone，`afterPropertiesSet()`（`:303-309`）触发。
- **shouldPull 的防御性**（`:494-525`）：`refreshRate`（秒）节流 pull 间隔；工作区不 clean 时默认**放弃 pull**（本地有未跟踪文件说明有人动过副本，宁可旧不可乱），`force-pull=true` 才强拉并 `logDirty`。索引损坏（`Short read of block`）+ forcePull 时删 index 后 `reset --hard`（`:527-542`）。
- **merge 而不是 pull**：fetch 后 `merge origin/{label}`（`:590-606`），merge 失败或本地领先则 `resetHard`（`:608-625`）——保证服务端副本永远是远端的忠实镜像，绝不产生本地提交。
- **label 语义**：`main` 拉取失败且默认 label 是 main 时，可 `try-master-branch=true` 回退拉 `master`（`:288-297`，兼容老仓库）；label 支持分支、Tag、commit id 三种形态（checkout 分支用 track，其余直接 `checkout.setName(label)`）。
- **线程安全**：`findOne`/`getLocations` 全部 `synchronized(LOCK)`（`:160-165,270-301`），与 ResourceController 共用一把锁（issue #2681：两个控制器并发操作同一工作副本会互相破坏）。
- **安全加固**（5.0.x 新增，`:644-815`）：basedir 重建用"不跟随符号链接的独占创建"（`recreateSecureDirectory`，TOCTOU 防护），`file:` 仓库逐级校验 symlink、open 后再校验 JGit 解析出的 gitDir/workTree 没跑出预期根目录——防"符号链接逃逸读取任意目录"。
- **凭证**：`GitCredentialsProviderFactory` 按 uri 协议生成 HTTPS Basic / SSH 私钥+passphrase / AWS CodeCommit / 跳过 SSL 校验等不同 `CredentialsProvider`；SSH 行为可由属性（known-hosts、private-key）或 `transport-config-callback` 全权接管。

## 5.4 MultipleJGitEnvironmentRepository：pattern 多仓库

企业里常见"一个 Git 组织、按应用拆仓库"。`spring.cloud.config.server.git.repos[x].pattern` 支持按 `application/profile` 通配匹配路由到不同仓库（`MultipleJGitEnvironmentRepository.java:285-296`，`PatternMatchUtils.simpleMatch`），三条规则（`:325-347`）：

- `pattern=foo` → 展开 `foo/*`（匹配任意 profile）；
- `pattern=foo/dev` → 额外派生 `foo/dev,*`（profile 列表含 dev 时命中，其余 profile 也放行）；
- uri 支持 `{application}/{profile}/{label}` 占位符，按替换结果缓存派生的子 JGit 仓储（`:212-237`）——"一库多应用"模式可零 pattern 配置。

## 5.5 Native：本地文件后端

`@Profile("native")` + `spring.cloud.config.server.native.search-locations=file:/opt/config/{application}/`。占位符展开逻辑在 `getLocations()`（`NativeEnvironmentRepository.java:192-253`）：按 profile×application 笛卡尔积展开 `{application}`/`{profile}`/`{label}`；`add-label-locations=true` 时追加 `{location}/{label}/` 子目录（用目录模拟分支，适合无 Git 场景的版本隔离）。它是 5.2 节"装配引擎"的独立形态——**直接用 Boot 引擎读任意目录**，所以本地调试配置中心时它最快。

## 5.6 JDBC：数据库后端

【源码证据】`environment/JdbcEnvironmentRepository.java` + `JdbcEnvironmentProperties.java:29-53`

默认 SQL 与表契约：

```sql
SELECT "KEY", "VALUE" from PROPERTIES where APPLICATION=? and PROFILE=? and LABEL=?          -- 默认 sql
SELECT "KEY", "VALUE" from PROPERTIES where APPLICATION=? and PROFILE is null and LABEL=?    -- sql-without-profile
```

- 表结构：`PROPERTIES(APPLICATION, PROFILE, LABEL, KEY, VALUE)`，一行一个属性；
- 优先级靠**反转遍历**实现（`findOne` `:95-139`）：application/profile/label 列表全部 `reverse` 后逐层循环 `addPropertySource`，配合服务端 add 语义得到"具体覆盖通用"；application 自动补 `application,` 前缀保证公共行生效；
- 每次查询产出一个 PropertySource（名字如 `{application}-{profile}`），`PropertiesResultSetExtractor` 把结果集聚成 `LinkedHashMap`（`:186-197`）；
- `fail-on-error`（默认 true）决定 SQL 异常是抛还是跳过；order 默认 `DEFAULT_ORDER - 10`（比 Git 稍低优先级）。

适用：配置量小、需要管理台写库即可改配置的内部系统。**不推荐大规模使用**——无版本化、无审计、无回滚。

## 5.7 Vault：密钥后端

【源码证据】`environment/AbstractVaultEnvironmentRepository.java:100-152` + `environment/vault/SpringVaultEnvironmentRepositoryFactory.java:63-69`

- **路径规则**：默认 backend `secret`，key = `application[,profile][,label]`（enable-label 默认 false，default profile 不拼接）——即请求 `myapp/prod` 读 `secret/myapp,prod`；`{key}` 里可再用 `/` 分层。
- **KV 版本**：`kv-version=1|2` 分别映射 Spring Vault 的 `KeyValueBackend.KV_1/KV_2`（`opsForKeyValue`）；KV2 的 `data/` 前缀由 Spring Vault 内部处理。
- **Token 提供链**：静态 `token` 属性 → `HttpRequestConfigTokenProvider`（**把客户端请求头 `X-Config-Token` 透传为 Vault token**——每个调用方用自己 token 读自己的 secret，服务端零存储）→ 或 11 种 `SpringVaultClientAuthenticationProvider`（AppRole/AWS IAM/K8s/GCP/Azure/Cubbyhole...）。用请求级 token 时必须用 `StatelessSessionManager`（`SpringVaultClientConfiguration.java:104-124` 注释解释了为什么不能缓存 session）。
- 响应 JSON 经 `YamlPropertiesFactoryBean` 解析成属性（JSON 是 YAML 子集），属性源名 `vault:{key}`。
- Vault 家族只提供**密钥类**配置；与 Git 组合（composite）才是标准姿势：业务配置进 Git、密码进 Vault，客户端一次请求同时拿到两份。

## 5.8 其余后端速查表

| 后端 | 键组织方式 | 一句话特征 |
|---|---|---|
| SVN（SvnKit） | 同 Git 的文件名语义 | 需 `@Profile("svn")`；行为类似但无分支语义（用目录） |
| Redis | 每个 key 一个 **Hash**（field=属性名） | `addKeys` 反转实现优先级；`spring.cloud.config.server.redis.keys` 指定 key 列表，自动补 `application`/`application-{profile}` |
| AWS S3 | 对象 key `{label}/{application}[-{profile}].{ext}`（或 `{application}/application[-{profile}].{ext}`） | 支持 properties/json/yaml（多文档按 `on-profile` 匹配）、searchPaths 通配 |
| AWS Parameter Store | 参数路径 `/config/{application}-{profile}/...`，参数名 `/`→`.` 成属性 key | `withDecryption=true` 直接解 KMS；prefix 默认 `/config` |
| AWS Secrets Manager | secret 路径 `{prefix}/{application}[-{profile}]/` | **label 被用作 version stage**（如 `AWSCURRENT`），secret JSON 整包为一个属性源 |
| Google Secret Manager | secret 名 `{application}[-{profile}]` | 支持按版本比较（`GoogleSecretComparatorByVersion`） |
| CredHub | 路径 `/application[,profile]` | Spring CredHub 生态（PCF 遗产） |
| MongoDB | 集合文档按 application/profile/label 查询 | 4.3+ 新增，适合已有 Mongo 的团队 |

## 5.9 后端选型建议

| 维度 | Git | Vault | JDBC | Native | AWS S3/PS/SM |
|---|---|---|---|---|---|
| 版本化/回滚 | ★ Git 原生（Tag/commit/PR 审计） | 无（Vault 侧有版本） | 无 | 无 | S3 版本桶可补 |
| 变更可观测 | PR diff 一目了然 | - | 需自建 | - | - |
| 密文支持 | `{cipher}`（第六章） | 天然密文 | 自建 | 自建 | KMS 集成 |
| 动态热改 | 需 push（配 webhook+Bus） | Vault 动态 secret | UPDATE 即可 | 重启 | API 改 |
| 最佳用途 | **默认首选**（业务配置+GitOps） | 敏感凭证 | 管理台驱动的少量动态项 | 本地/演示/CI | 云原生深度 AWS 化团队 |

## 5.10 本章小结

- 服务端体系一句话：**一个 SPI（findOne）、两层装饰（Encryptor/overrides、Observation）、一个组合（@Order）、一个复用（Boot ConfigData 引擎）**。
- Git 后端的每次请求 = 条件性 fetch → checkout → merge → 必要时 resetHard，副本永远是远端镜像；懒 clone 是首请求慢的根源。
- SCM 后端的文件解析彻底复用 Boot 引擎——Git 里能写什么格式，由 Boot 的能力决定，而不是 Config 自己。
- JDBC/Redis 等直实现后端的"优先级"全靠反转遍历 + add 顺序凑出来，读懂它就读懂了整个协议的顺序语义。

---

# 六、加密与解密：`{cipher}` 的自描述密文

## 6.1 先白话：为什么要"密文随配置走"

把密码明文提交进 Git 显然不行，那把密码放在哪？Spring Cloud Config 的答案是：**加密后照样放 Git**。值写成 `{cipher}AQC4pL...`，密文本身就是配置的一部分——Git 里存的是密文（at-rest 安全），Config Server 返回给客户端前解密（in-flight 明文，需 HTTPS 保护），客户端拿到的就是明文属性。整个安全模型只有两个信任锚点：**对称密钥（服务端 JVM 内）或 RSA 公私钥对**，以及传输层的 TLS。

## 6.2 /encrypt /decrypt 端点与前缀语法

【源码证据】`spring-cloud-config-server/.../encryption/EncryptionController.java:83-150`

```
GET  /key                       → 返回 RSA 公钥（便于"客户端加密、服务端解密"的非对称模式）
GET  /key/{name}/{profiles}     → 按应用/环境返回不同的公钥
GET  /encrypt/status            → {"status":"OK"}；密钥过弱/未配置则报错
POST /encrypt                   → 请求体明文 → 响应体 {cipher}密文
POST /encrypt/{name}/{profiles} → 同上，按 name/profiles 选择密钥
POST /decrypt[...]              → 密文 → 明文
```

**前缀语法**由 `EnvironmentPrefixHelper` 解析（`encryption/EnvironmentPrefixHelper.java:65-128`），完整形态：

```
{name:myapp}{profiles:prod}{key:my-key}{cipher}AQHBq...
   └ 可选：限定该密文属于哪个应用/环境        └ 可选：密钥选择器   └ 密文本体
```

- `{plain}` 标记强制明文不加密（提交前想临时禁用时用）；
- `stripPrefix()` 用正则 `^(\{.*?:.*?\})+` 剥掉全部前缀；
- 加密时 `addPrefix()` 把选择器原样拼回，密文因此**自描述**：服务端解密某个值时，仅凭值本身就知道用哪个密钥（`getEncryptorKeys` → `TextEncryptorLocator.locate(keys)`）。

## 6.3 TextEncryptorLocator：密钥路由

两个内置实现（`encryption/`）：

- **SingleTextEncryptorLocator**：容器里有一个 `TextEncryptor` bean（`RsaEncryptionAutoConfiguration` 生成 RSA 的，或 `DefaultTextEncryptionAutoConfiguration` 按 `encrypt.key` 生成对称的）就包一层；都没有则退化为 `Encryptors.noOpText()`（明文直通——所以 `encrypt/status` 会用 `encrypt("FOO").equals("FOO")` 检测这种退化，`EncryptionController.java:176-180`）。
- **KeyStoreTextEncryptorLocator**：`encrypt.key-store.*` 指定 JKS/PEM，按 `{key:alias}` 选择 keypair 构造 `RsaSecretEncryptor`（salt 默认 `deadbeef`）；不同应用可用不同 alias（多租户密钥隔离）。

需要"每 name/profile 不同密钥"时，官方文档（`docs/modules/ROOT/pages/server/encryption-and-decryption.adoc:53-56`）明确要自己实现 `TextEncryptorLocator`。

## 6.4 两条解密链路：服务端解密 vs 客户端解密

**链路 A（默认）：服务端返回前解密。** `EncryptionAutoConfiguration`（`:58-70`）在 `spring.cloud.config.server.encrypt.enabled=true`（默认）且存在 `TextEncryptorLocator` 时注册 `CipherEnvironmentEncryptor`，它被 `EnvironmentEncryptorEnvironmentRepository` 在每次 findOne 后调用（4.6 节）：深拷贝每个属性源，值以 `{cipher}` 开头就解密替换。**解密失败的降级**（`CipherEnvironmentEncryptor.java:78-91`）：不抛异常，把 key 改成 `invalid.<key>`、value 改成 `<n/a>`——防止密文被客户端误当密码使用而泄露（官方文档原文见 `encryption-and-decryption.adoc:6-7`）。

**链路 B：客户端本地解密。** 服务端 `spring.cloud.config.server.encrypt.enabled=false`（密文原样下发），客户端持有密钥（`ENCRYPT_KEY` 环境变量等），由 spring-cloud-context 在环境后处理阶段解密。官方文档（`serving-encrypted-properties.adoc:5-7`）：*"Sometimes you want the clients to decrypt the configuration locally... you need to explicitly switch off the decryption of outgoing properties."* 适合"公钥分发加密、私钥仅在应用侧"的合规场景（配合 `GET /key` 拿公钥加密）。

**两条链路的选择**：默认用 A（密钥只在 Server，泄露面最小）；合规要求"应用侧持钥"用 B；绝对不要两条都开（会双解密报错）。

## 6.5 明文文件加密（ResourceEncryptor）

ResourceController 下发的原始文件也支持加密：`ResourceEncryptorConfiguration` 注册 `CipherResourceYamlEncryptor` / `CipherResourcePropertiesEncryptor` / `CipherResourceJsonEncryptor`，分别按格式解析文件、只解密 `{cipher}` 值、再序列化回去（`AbstractCipherResourceEncryptor` 复用同一套 TextEncryptorLocator）。开关：`spring.cloud.config.server.encrypt.plain-text-encrypt.enabled=true` + ResourceController 的 `encrypt-enabled`/`plain-text-encrypt-enabled`。

## 6.6 本章小结

- `{cipher}` 是自描述密文：前缀携带密钥选择器，值走到哪密文跟到哪；`/encrypt /decrypt /key /encrypt/status` 四个端点支撑运营。
- 默认服务端解密（密钥不出 Server），可切换客户端解密（应用持钥），失败值降级为 `invalid.<key>` 而非报错。
- 密文安全模型的前提是 TLS：明文毕竟要经 HTTP 下发，HTTPS/mTLS 是必选项（见 8.2 节）。

---

# 七、动态刷新：从"重启生效"到 RefreshScope

## 7.1 先白话：刷新要解决什么，分几种粒度

配置拉取只发生在启动阶段（3.2 节），运行中远端改了配置，客户端进程里的 Environment 还是旧的。刷新 = **重新拉取 + 替换属性源 + 通知相关 Bean**。按"哪些东西能变"分两档：

| 粒度 | 机制 | 覆盖范围 | 源码入口 |
|---|---|---|---|
| Environment 级 | 重新执行 Config Data 拉取，diff 出变化的 key，发布 `EnvironmentChangeEvent` | `@ConfigurationProperties` bean（重绑定）、`LoggingRebinder`（日志级别） | `ContextRefresher#refreshEnvironment` |
| Bean 级 | `refresh` Scope 的缓存实例整体销毁，下次访问时按原 BeanDefinition 重建 | 标了 `@RefreshScope` 的 bean（`@Value`、占位符、连接池等一切初始化期逻辑重跑） | `RefreshScope#refreshAll` |

`ContextRefresher.refresh()` = 两档都做（`:97-101`：先 `refreshEnvironment()` 再 `scope.refreshAll()`）。

## 7.2 ContextRefresher：diff 驱动的刷新内核

【源码证据】spring-cloud-commons `spring-cloud-context/.../context/refresh/ContextRefresher.java:97-109,190-202`

```java
public synchronized Set<String> refresh() {
    Set<String> keys = refreshEnvironment();
    this.scope.refreshAll();
    return keys;
}

public synchronized Set<String> refreshEnvironment() {
    Map<String, Object> before = getCurrentEnvironmentProperties();   // 快照：除系统属性等 standardSources 外全部
    updateEnvironment();                                              // ← 抽象方法：重新拉远程配置并替换属性源
    Set<String> keys = changes(before, getCurrentEnvironmentProperties()).keySet();
    this.context.publishEvent(new EnvironmentChangeEvent(this.context, keys));   // ★ 变更 key 集合
    return keys;
}
```

`updateEnvironment()` 的 Config Data 实现在 `ConfigDataContextRefresher`（`:68-115`）：`copyEnvironment()` 复制一份"只剩 defaultProperties 和 profile 设置"的干净环境 → **重新实例化全部 `EnvironmentPostProcessor` 并执行**（其中就包括 Boot 的 ConfigData 流程，`configserver:` 导入被重新解析、Config Server 被重新请求）→ 把新环境里的属性源按名字 `replace`/`addAfter` 合并回目标环境，**保持原有顺序**。没有变化的属性源名字不动，变化的源整体换新——diff 的粒度是 key，替换的粒度是属性源。

`EnvironmentChangeEvent` 的消费者（`ConfigurationPropertiesRebinder`，7.5 节）只对"key 命中自己前缀"的 bean 重绑定——**属性没变的 bean 完全不被触碰**，这是 Config 刷新不抖动的原因。

## 7.3 RefreshScope / GenericScope：销毁-重建的 Scope 实现

【源码证据】spring-cloud-commons `spring-cloud-context/.../context/scope/refresh/RefreshScope.java:165-170` + `context/scope/GenericScope.java:126-177`

```java
public void refreshAll() {          // RefreshScope
    super.destroy();                // GenericScope：cache.clear()，逐个回调销毁生命周期
    this.context.publishEvent(new RefreshScopeRefreshedEvent());
}

// GenericScope
public void destroy() {                                     // :126-149
    Collection<BeanLifecycleWrapper> wrappers = this.cache.clear();
    for (BeanLifecycleWrapper wrapper : wrappers) {
        Lock lock = this.locks.get(wrapper.getName()).writeLock();   // 每 bean 一把 ReadWriteLock
        lock.lock();
        try { wrapper.destroy(); } finally { lock.unlock(); }
    }
}

public Object get(String name, ObjectFactory<?> objectFactory) {   // :173-...
    BeanLifecycleWrapper value = this.cache.put(name, new BeanLifecycleWrapper(name, objectFactory));
    this.locks.putIfAbsent(name, new ReentrantReadWriteLock());
    ...
    return value.getBean();     // 缓存没了 → objectFactory 重建 → @Value 重新解析
}
```

机制总结成一句话：**`@RefreshScope` 把 bean 的实例从"容器单例池"挪进 Scope 自己的缓存；`refreshAll` 清空缓存并执行销毁回调；下一个访问该 bean 的线程触发 `get()`，按原 BeanDefinition 重新创建——于是所有占位符重新解析，拿到的是新值**。三个工程细节：

1. **代理**：注入到别人的是 CGLIB scoped proxy（`GenericScope` 注册 `LockedScopedProxyFactoryBean`，`:248`），方法调用转发到 Scope 当前的实例——持有旧实例引用的代码也会被切到新实例。但注意：**代理只拦截方法调用**，字段直读、`static @Value`、构造期捕获的值都不会更新（7.4 节）。
2. **eager 初始化**：`RefreshScope.start()` 在 ContextRefreshedEvent 时预创建所有非懒加载的 refresh bean（`RefreshScope.java:116-133`），把创建成本从首次请求挪到启动。
3. **RefreshScopeLifecycle**：`RefreshAutoConfiguration` 注册（`:122-124`），让 scope 随容器生命周期 start/stop。

## 7.4 @RefreshScope 使用要领与常见坑

```java
@RestController
@RefreshScope                       // 类上标注，销毁重建的是整个 controller 实例
@ConditionalOnProperty("feature.x.enabled")
public class DemoController { ... }
```

- **配 `@ConfigurationProperties` 就不需要 `@RefreshScope`**——Rebinder（7.5）已覆盖绝大多数场景；`@RefreshScope` 留给"绑定到构造期/`@Value`/第三方连接对象"的场景。
- 刷新瞬间的行为：旧实例先走 `@PreDestroy`（连接要善后，如 DataSource 重建），再由访问线程重建——**重建期间并发访问由该 bean 的 writeLock 串行化**，极端情况下首次访问会变慢。
- 条件注解陷阱：`@ConditionalOnProperty` 在刷新时**不会重评估**（BeanDefinition 不变）——开关类属性用 Rebinder 绑定的 boolean 字段做运行时判断，不要依赖条件装配。

## 7.5 ConfigurationPropertiesRebinder：属性 Bean 的重绑定

【源码证据】spring-cloud-commons `spring-cloud-context/.../context/properties/ConfigurationPropertiesRebinder.java:86-183`（有删节）

```java
public class ConfigurationPropertiesRebinder
        implements ApplicationContextAware, ApplicationListener<EnvironmentChangeEvent> {
    ...
    public boolean rebind(String name) {
        // destroyBean + initializeBean：销毁旧 bean 实例，用新 Environment 重新走一遍绑定
        // Serialize concurrent rebinds of the *same* bean ...（每 bean 一把锁，:97 rebindLocks）
    }
}
```

收到 `EnvironmentChangeEvent`（携带变化的 key 集合）后，它遍历容器里所有 `@ConfigurationProperties` bean，**只对前缀命中的 bean** 执行 destroy+re-init。与 RefreshScope 的区别：Rebinder 不换 bean 身份（还是那个单例，只是字段值被重新绑定），RefreshScope 是整个实例换人。

## 7.6 触发刷新的四条路径（全景图）

```
路径①  人工/脚本      POST /actuator/refresh                → RefreshEndpoint(@WriteOperation) → ContextRefresher.refresh()
路径②  Bus 本地事件    RefreshEvent                           → RefreshEventListener（ApplicationReady 后才处理）→ refresh()
路径③  Bus 远程广播    RefreshRemoteApplicationEvent（经 MQ） → RefreshListener → refresh()
路径④  定时轮询        ConfigClientWatch（默认关）             → 比对 config.client.state 变化 → refresh()
```

【源码证据】spring-cloud-commons `endpoint/RefreshEndpoint.java:33-45`（`@Endpoint(id = "refresh")` + `@WriteOperation refresh()`）、`endpoint/event/RefreshEventListener.java:43,69-75`（`ready` 门闩：`ApplicationReadyEvent` 之前到达的 RefreshEvent 一律忽略，防止启动期误触发）。

## 7.7 Spring Cloud Bus：从"刷一个"到"刷全部"

路径① 只能刷单实例，集群里几十个 Pod 怎么办——**Spring Cloud Bus**：每个实例连到同一个消息代理（RabbitMQ/Kafka），实例间靠广播消息传递事件。

【源码证据】spring-cloud-bus `spring-cloud-bus/.../event/RefreshRemoteApplicationEvent.java:23-35`、`event/RemoteApplicationEvent.java:34-70`、`event/RefreshListener.java:32-46`

```java
public class RefreshRemoteApplicationEvent extends RemoteApplicationEvent { ... }
// RemoteApplicationEvent：originService（谁发的）+ destinationService（发给谁，"*"=全部，"svc:**"=某服务全部实例）+ id

public class RefreshListener implements ApplicationListener<RefreshRemoteApplicationEvent> {
    public void onApplicationEvent(RefreshRemoteApplicationEvent event) {
        ... contextRefresher.refresh() ...      // 收到广播 → 走 7.2 的标准刷新流程
    }
}
```

消息代理把 `RemoteApplicationEvent` 序列化广播；每个实例的 `RemoteApplicationEventListener` 用 `ServiceMatcher` 判断"destination 是否包含我、origin 是否是我自己"再落地成本地事件。所以 **Bus 携带的只是"该刷新了"这个信号**，刷新动作（重新拉配置、diff、重建）仍由每个客户端独立完成——Server 无需参与。

## 7.8 monitor：Git webhook 到 Bus 的翻译官

最后一块拼图：Git push 之后谁发起刷新？`spring-cloud-config-monitor` 模块给 Config Server 加了一个 `/monitor` 端点，把各 Git 托管平台的 webhook 翻译成 Bus 刷新事件：

【源码证据】spring-cloud-config `spring-cloud-config-monitor/.../monitor/PropertyPathEndpoint.java:49-50,88-145`（有删节）

```java
@RestController
@RequestMapping(path = ".../monitor")        // 默认路径 /monitor，可配前缀
public class PropertyPathEndpoint implements ApplicationEventPublisherAware {
    @PostMapping
    public Set<String> notifyByPath(@RequestHeader HttpHeaders headers, @RequestBody Map<String, Object> request) {
        PropertyPathNotification notification = this.extractor.extract(headers, request);  // ① 各平台 extractor
        if (notification != null) {
            Set<String> services = new LinkedHashSet<>();
            for (String path : notification.getPaths()) {
                services.addAll(guessServiceName(path));                                    // ② 从文件名猜服务名
            }
            for (String service : services) {
                this.applicationEventPublisher.publishEvent(
                    new RefreshRemoteApplicationEvent(this, this.busId, service));          // ③ 发 Bus 事件
            }
            ...
```

三步流水线：

1. **extractor**：`GithubPropertyPathNotificationExtractor`、`Gitlab...`、`Gitee...`、`Gitea...`、`Gogs...`、`Bitbucket...` 各自解析自家 webhook JSON 抽出变更文件路径列表，组合成 `CompositePropertyPathNotificationExtractor`；GitHub/GitLab 还可校验 SHA256 签名（`*WebhookRequestValidator`）。
2. **guessServiceName**（`:119-145`）：`config-repo/myapp-dev.yml` → 去扩展名得 `myapp-dev` → 逐级剥 `-` 后缀产生候选 `myapp`、`myapp-dev`……；剥到 `application` 则广播 `*`（刷新全部服务）。这个"从文件名反推服务"是**启发式**：约定文件名 = 服务名（或 `服务名-环境`），否则猜不出来。
3. **发布 `RefreshRemoteApplicationEvent(busId → service)`**：destination 为 `*` 或具体服务名；`RefreshListener` 落地刷新。

### 端到端时序（配置变更的一生之"推"侧）

```
 开发者            Git 托管平台           Config Server                  消息代理            所有客户端实例
   │ git push          │                        │                          │                    │
   ├──────────────────►│  webhook POST /monitor  │                          │                    │
   │                   ├───────────────────────►│ extractor → guess 服务名   │                    │
   │                   │                        ├─ RefreshRemoteApplicationEvent ──►│              │
   │                   │                        │                          ├── 广播到每个实例 ──►│              │
   │                   │                        │                          │                    ├ RefreshListener
   │                   │                        │                          │                    ├ ContextRefresher.refresh()
   │                   │                        │◄─── 重新 GET /{app}/{profile}（每实例各自拉一次）────┤
   │                   │                        ├── EnvironmentChangeEvent → Rebinder / RefreshScope       │
```

## 7.9 本章小结

- 刷新 = `ContextRefresher.refresh()`：重跑 Config Data 拉新值 → key 级 diff → `EnvironmentChangeEvent`（Rebinder 重绑定 @ConfigurationProperties）→ `scope.refreshAll()`（@RefreshScope 销毁重建）。
- RefreshScope 的本质是"可失效的单例缓存 + CGLIB 代理"：字段值在**重建时**才更新，条件注解不会重评估。
- 四条触发路径殊途同归于 ContextRefresher；集群刷新靠 Bus 广播信号（不是推送配置内容）；webhook 联动靠 monitor 的"文件名→服务名"启发式。
- 与 Nacos 对比的伏笔：**Config 的"刷新"是应用内的环境重载，Nacos 的"监听"是应用外的内容回调**——第九章展开。

---

# 八、高可用、安全与运维

## 8.1 HA 模型：Server 无状态，一致性问题外包给后端

官方文档对 HA 的全部表述集中在 client 侧（`docs/modules/ROOT/pages/client.adoc:191-197`）：

> *"To ensure high availability when you have multiple instances of Config Server deployed ... you can either specify multiple URLs (as a comma-separated list under the spring.cloud.config.uri property) or have all your instances register in a Service Registry like Eureka."*
> *"The URLs listed under spring.cloud.config.uri are tried in the order listed. By default, the Config Client will try to fetch properties from each URL until an attempt is successful to ensure high availability."*

源码印证（3.5 节）：多 URI 按序轮询 + `multiple-uri-strategy` 控制切换时机；或 discovery-first 动态发现。三条配套事实：

- **Config Server 之间没有任何共识协议**——多个实例指向同一个 Git 仓库，真源与一致性完全由后端负责。这既是极简（Server 可随意扩缩容），也是与 Nacos（内置集群一致性）最根本的架构分歧。
- **Git 冲突问题**：多实例各自 clone 同一仓库到本地 basedir，工作副本损坏只影响本实例（`force-pull` + `resetHard` 自愈）；basedir 不共享（不要放 NFS，`.git/index.lock` 会在共享盘上打架）。
- **配置变更的可见性延迟**：Git push → 实例 A 的下一次 findOne 才会 fetch 到新 commit。实例间"短暂读旧"是常态，webhook + Bus 只是把它压缩到秒级。

## 8.2 安全：默认裸奔，需要自己加锁

| 层 | 默认状态 | 加固手段 |
|---|---|---|
| 客户端 → Server 认证 | **无**（任何知道地址的人都能读配置） | 引 spring-boot-starter-security + Basic（客户端 `username/password` 已内置支持，`ConfigClientProperties.java:111-118`）；或网关层鉴权 |
| 传输 | 明文 HTTP（默认 uri 是 http://localhost:8888） | HTTPS；客户端侧 TLS 配置 `spring.cloud.config.tls.*`（trust-store/key-store，client 模块有专门集成测试工程 `spring-cloud-config-client-tls-tests`） |
| 敏感值 | `{cipher}` 密文（第六章），下发时明文 | 客户端解密模式（应用持钥）；或改用 Vault 后端 + `X-Config-Token` 请求级凭证 |
| webhook | 无校验 | `Sha256WebhookRequestValidator` 等签名校验（GitHub X-Hub-Signature-256） |
| Server → Git | HTTPS Basic / SSH key | `GitCredentialsProviderFactory`；最小权限 deploy key |

一个常被忽略的点：`/encrypt`、`/decrypt`、`/key` 端点**能加密解密任意内容**，等于一把"密钥预言机"——官方文档明确假设这三个端点已被安全保护（`encryption-and-decryption.adoc:31`：*"on the assumption that these are secured and only accessed by authorized agents"*）。务必把 management 端点与业务 API 一起纳入认证。

## 8.3 可观测与健康

- **服务端健康**：`ConfigServerHealthIndicator`（`config/ConfigServerHealthIndicator.java:71-117`）对每个配置的 repository 做一次 `findOne("app","default",label)` 探测，报告各源的来源文件列表；失败即 DOWN（状态码可 `down-health-status` 覆盖）。**注意这是真探测**——每 30 秒真的会触发一次 Git fetch，仓库多时要做健康检查降级（`health.enabled=false`）。
- **客户端健康**：`ConfigServerHealthIndicator`（client，3.7 节）只看启动时拉没拉到，不探测。
- **链路观测**：`ObservationEnvironmentRepositoryWrapper` 包住每个后端的 findOne，产出 `spring.cloud.config.server.environment` Observation（含 application/profile/label 标签），对接 Micrometer → Zipkin/OTel。
- **AOT/原生镜像**：client/server 各有 `RuntimeHints`（反射资源注册）；`CompositeEnvironmentBeanFactoryInitializationAotProcessor` 在 AOT 期预计算组合仓储的 bean 贡献——5.0 里 native image 跑 Config Server 是官方支持的。

## 8.4 运维清单（按"出了问题先查什么"组织）

| 症状 | 高概率原因 | 源码依据 |
|---|---|---|
| 首次请求超时 | 懒 clone 大仓库 | `clone-on-start=true`；客户端 read-timeout 默认 185s |
| 改了 Git 没生效 | 没有 webhook/Bus；或文件名与服务名对不上 | 7.8 节 guessServiceName 启发式 |
| 客户端起不来 | fail-fast=true 且 Server 不可用/网络断 | 3.5 节；或忘写 `spring.config.import`（3.2.4 节） |
| `invalid.xxx` 属性出现 | 服务端解密失败（密钥不对） | 6.4 节降级逻辑 |
| basedir 磁盘涨 | 多仓库 clone 累积；本地分支堆积 | `delete-untracked-branches=true`；定期清 basedir |
| 副本 dirty 不再拉新 | 有人/进程改了工作副本 | `force-pull=true` + `resetHard` 自愈（5.3 节） |
| `@Value` 刷新无效 | 没有 `@RefreshScope`，或 static/构造期捕获 | 7.3、7.4 节 |
| `application-xxx` 命名警告 | 应用名以 `application-` 开头 | 3.3 节 name 校验（bootstrap 路线） |

## 8.5 本章小结

- HA 的答案只有一句："Server 无状态、后端保一致、客户端会重试"。没有 Server 间的选主/同步，这是优点（简单）也是天花板（一致性依赖 Git 可用性）。
- 安全默认全裸：认证、传输、webhook 校验都要显式加；`/encrypt` 端点必须纳入鉴权。
- 两个健康指示器语义不同：服务端真探测（开销大）、客户端只看启动结果。

---

# 九、与 Nacos 的对比：两条路线的深层差异

> 本章 Nacos 侧事实基于 `D:\code\3rd\nacos` main 分支（3.3.0-RC）源码与官方发布记录；Spring Cloud Config 侧即前八章。所有 Nacos 证据标注 `nacos:` 前缀。

## 9.1 出身与定位

| | Spring Cloud Config | Nacos |
|---|---|---|
| 首发时间 | 2015-03（Spring 生态） | 2018-06（阿里巴巴开源） |
| 定位 | 纯配置中心（注册发现交给 Eureka/Consul 等） | **注册中心 + 配置中心二合一**（3.x 又叠加 MCP/A2A 的 AI 服务注册） |
| 协议血统 | Spring Environment 抽象的 HTTP 外化 | 自有协议：1.x HTTP 短/长轮询 → 2.0 起全量 gRPC 长连接 |
| 版本现状（2026-10） | 5.0.5（Boot 4 线）/ 4.3.x（Boot 3 线） | 3.1.x GA（2025 下半年，A2A 注册中心）；主线 3.3.0-RC；2.x 兼容线仍在维护 |
| Spring 接入 | 官方 `spring-cloud-starter-config` | Spring Cloud Alibaba 的 `spring-cloud-starter-alibaba-nacos-config`（同样实现了 Spring 的 Environment/Config Data 集成） |

一句话画像：**Config 是"Git 的 Spring 化视图"（把 Git 变成配置 API），Nacos 是"带控制台的实时配置存储"**。下面的五条主线都由这个定位分歧派生。

## 9.2 数据模型：文件系统语义 vs 三元组 KV 语义

| 维度 | Config | Nacos |
|---|---|---|
| 唯一键 | `name + profile + label`（请求参数） → 落到 **文件名** `{application}-{profile}.yml` + **Git 引用**（分支/Tag） | `dataId + group + namespace` 三元组。证据：`nacos: api/.../config/ConfigService.java:45`（`getConfig(dataId, group, timeoutMs)`，namespace 来自客户端属性）；`client/.../impl/ConfigTransportClient.java:91`（`this.tenant = properties.getProperty(PropertyKeyConst.NAMESPACE)`）；`ConfigInfo.java:25-31`（服务端实体 tenant 字段） |
| 分组/租户 | 无原生概念；公共配置靠 `application.yml` 兜底 + 搜索路径占位符 | **group**（默认 DEFAULT_GROUP，`Constants.java:32`）逻辑分组；**namespace**（tenant）物理隔离多租户/多环境 |
| 版本 | label = Git 分支/Tag，回滚 = checkout | 内容级 MD5（`ConfigInfoBase.md5`）+ 发布历史表；无分支语义 |
| 检索语义 | **文件系统式**：`application-prod.yml` 覆盖 `application.yml`，顺序即优先级 | **平面 KV 式**：每个 dataId 一份独立内容，优先级由客户端集成层约定（spring-cloud-alibaba 按 dataId 列表顺序拼接） |

**深层差异**：Config 的 name/profile/label 是"怎么找到文件"的坐标——profile 有顺序语义（多 profile 逗号列表，后者覆盖前者）、label 直接借 Git 的版本能力；Nacos 的三元组是"怎么定位存储记录"的坐标——group/namespace 是纯粹的隔离维度，**没有隐式覆盖规则**，灰度/优先级都要靠应用层的 dataId 命名约定（如 `common.properties`、`myapp.properties`、`myapp-prod.properties` 手工排序导入）。

## 9.3 变更感知：拉 + 广播 vs 长轮询/gRPC 推拉结合

这是两者实现上最大的分水岭。

**Config（拉模型 + 事件触发）**，详见第三章/第七章：启动拉一次 → 运行期靠 actuator/Bus/webhook 触发**整份重新拉取** → key 级 diff。即使开启 `ConfigClientWatch`（默认关），也只是 500ms 级的 state 轮询。**配置内容从不"推"，推的只是"该刷新了"的信号。**

**Nacos（推拉结合）**，分两代：

- **1.x：HTTP 长轮询**。客户端每 30s 发一次 `/listener` 挂起请求；服务端 `LongPollingService` 把请求挂进内存队列，配置变更时 `DataChangeTask` 提前唤醒返回变更的 groupKey，客户端再回源拉内容。29.5 秒的来历——服务端把客户端的 30s 超时减去 500ms 提前量（`nacos: config/.../service/LongPollingService.java:221-227`：`long timeout = Math.max(minLongPoolingTimeout, Long.parseLong(requestLongPollingTimeOut) - delayTime);`），防止客户端先超时。
- **2.x/3.x：gRPC 长连接**。客户端与服务端建立 gRPC 双向流，服务端配置变更后发 `ConfigChangeNotifyRequest` 推**通知**（不是内容），客户端收到后**主动拉取**最新内容——`handleConfigChangeNotifyRequest`（`nacos: client/.../impl/ClientWorker.java:907-925`）：标记 `cacheData.getReceiveNotifyChanged().set(true)` 后 `notifyListenConfig()`，由监听循环发 `ConfigQueryRequest` 拉回内容再 `checkListenerMd5()` 触发回调。客户端另有 3 分钟兜底全量同步（`ALL_SYNC_INTERNAL = 3 * 60 * 1000L`，`ClientWorker.java:834`）。服务端推送链：`ConfigOperationService` 发布变更 → `ConfigChangePublisher.notifyConfigChange`（`config/.../service/ConfigOperationService.java:158-166`）→ 集群内 `AsyncNotifyService` 用 `ConfigChangeClusterSyncRequest` 逐节点同步 dump（`AsyncNotifyService.java:82-175`）→ `RpcConfigChangeNotifier` 收到 `LocalDataChangeEvent` 后经 gRPC 推给客户端（`config/.../remote/RpcConfigChangeNotifier.java:107-108,198`）。
- **3.x 精简**：客户端只保留 gRPC（HTTP 长轮询客户端代码已删除，`grep LongPollingRunnable` 无结果），服务端保留长轮询兼容旧客户端；新增 304 条件查询（`NacosConfigService.java:487-489` `@since 3.3.0`）。

**对比结论**：

| | Config | Nacos 2.x/3.x |
|---|---|---|
| 变更延迟 | 秒~分钟级（取决于 Bus/webhook 配置；不开 Bus 就是"永远等人工刷新"） | **秒级以内**（长连接推通知，客户端立即拉） |
| 基础设施要求 | 需要 MQ（Bus）+ webhook 可达（或接受人工/CI 触发） | 自身即可（长连接内生于 Nacos 协议） |
| 压力模型 | 变更风暴时 = N 客户端同时回源拉（Server→Git 瞬时压力） | 变更风暴时 = 服务端推 N 个通知（Nacos 集群内部消化） |
| 防抖 | 无内置（每次 refresh 全量重拉 + diff） | MD5 对比后只有真变更才回调 |

## 9.4 刷新语义：环境重载 vs 内容回调

两者给应用编程模型带来的差异，比"推还是拉"更影响日常开发：

- **Config：环境级重载**。`ContextRefresher.refresh()` 后，应用拿到的仍然是"Spring Environment"——`@ConfigurationProperties` 被 Rebinder 重绑定（7.5）、`@RefreshScope` bean 销毁重建（7.3）。**开发者心智：配置还是那些配置 Bean，只是值更新了。** 变更明细以"变化的 key 集合"形式出现在 `EnvironmentChangeEvent` 里（ADDED/UPDATED/DELETED 不区分，只有新值）。
- **Nacos：内容级回调**。注册一个 `Listener`（`nacos: api/.../config/listener/Listener.java:33,40`——`Executor getExecutor(); void receiveConfigInfo(String configInfo);`），配置变更时回调把**该 dataId 的完整新内容**（字符串）交给你。想拿结构化 diff 要自己解析——框架提供了 `ConfigChangeEvent` + `ConfigChangeItem`（`api/.../config/ConfigChangeEvent.java:27-39`，`PropertyChangeType`：ADDED/MODIFIED/DELETED）和 properties/yaml 差异解析器（`client/.../impl/PropertiesChangeParser.java` 等）。
- **交接处**：Spring Cloud Alibaba 把 Nacos 回调适配回了 Config 的语义——监听到变更后发布 `RefreshEvent`，最终还是走 `ContextRefresher.refresh()` 重绑定。所以**在 Spring 应用里用 Nacos，刷新体验与 Config 几乎一致**；差异全部沉淀在底层"何时感知、感知什么"。

## 9.5 容错设计：客户端各有一套"保命符"

| | Config | Nacos |
|---|---|---|
| 启动期 | fail-fast + retry + 多 URI 轮询（3.5 节）；optional 导入可"没有远程配置也启动" | 连不上 Server 时读本地快照（见下），同样能启动 |
| 运行期容错 | **无本地缓存**：Server 不可用期间拿不到新配置（进程里还是启动时的旧值——多数场景这已是最后可用状态） | **快照 + failover 双机制**：每次拉取成功写本地快照文件（`nacos: client/.../impl/ClientWorker.java:1542` `LocalConfigInfoProcessor.saveSnapshot`），Server 异常时回退快照（`NacosConfigService.java:474-487`）；更强的是 failover 目录——`{snapshot_path}/{server}_nacos/data/config-data[-tenant]/...`（`LocalConfigInfoProcessor.java:45-59,198-209`），运维手工放文件即可**强制覆盖**服务端配置（应急逃生门），`ClientWorker.checkLocalConfig`（`:1129-1180`）每轮监听都检查 failover 文件变化 |
| 服务端容错 | 多实例 + Git；无集群内部同步 | 集群节点间 gRPC 同步 dump（9.3 节）；DB 为真源（外部 MySQL）或内嵌 Derby+JRaft |

**Nacos 的 failover 目录是运维上的独特优势**：Server 全挂/网络隔离时，改本地文件就能救急；Config 没有对应物（你能做的只是等 Git 和 Server 恢复，或改本地 yml 重启）。

## 9.6 灰度与发布：label 分支 vs gray rule

- **Config**：灰度 = **Git 分支/Tag**。`label=canary` 发一条分支，把灰度实例的 `spring.cloud.config.label` 指过去；回滚 = label 指回旧 Tag。粒度是"实例部署单元"（改实例的启动配置才生效），**没有运行时按 IP 的灰度**。优点是灰度方案本身走 GitOps、可审计。
- **Nacos**：运行时灰度是内建能力。3.x 统一为 gray rule 模型（`nacos: config/.../model/gray/`）：`BetaGrayRule`（按客户端 **IP 列表**匹配，`BetaGrayRule.java:30-40`，2.x 时代叫 betaIps）、`TagGrayRule`（按客户端 tag 标签匹配）；灰度配置独立存储于 `config_info_gray` 表（`ConfigInfoGrayPersistService.java:30`），查询时走责任链匹配（`ConfigQueryRequestHandler.java:157-163`），命中灰度规则的客户端拿到灰度内容、其余拿正式内容。控制台/Admin API 直接操作（`ConfigControllerV3.java:458` `POST /gray`）。

一句话：**Config 的灰度是"部署期"的（换分支重启），Nacos 的灰度是"运行期"的（同一配置对同一服务推两套内容）**。

## 9.7 存储与一致性：Git 为真源 vs DB + Raft

- **Config**：真源是 Git 仓库，Server 是无状态缓存/装配层（第八章）。一致性问题（多 Server 读写同一配置）外包给了 Git：单写多读、commit 即版本。代价是**没有运行时写路径**——配置的"写"发生在 Git 侧（PR/MR 流程），Server 只有读。想要"管理台一键改配置"，Config 生态要自己补（或用 JDBC 后端，5.6 节——但那样就放弃了 Git 的全部优点）。
- **Nacos**：配置是真源在 DB：外部 MySQL（生产推荐）或内嵌 Derby。**外部 DB 模式下集群一致性由 DB 保证**，节点间只同步"变更通知"用于刷新各自内存缓存与磁盘缓存（9.3 节）；内嵌 Derby 模式才启用 **JRaft（CP）**——`EmbeddedDumpService` 直接依赖 `CPProtocol`（`nacos: config/.../service/dump/EmbeddedDumpService.java:27,93`），"只有 leader 可执行写入"（`:178`）。注意：**Distro 协议（AP）只用于服务发现模块，配置链路不用 Distro**（config 模块无 Distro 依赖）。Nacos 配置写入还支持 CAS-MD5 乐观锁（`ConfigService.publishConfigCas`，`api/.../config/ConfigService.java:199`）——Config 因为"写"在 Git 侧，天然没有并发写冲突问题。

## 9.8 运维面、生态与多语言

| | Config | Nacos |
|---|---|---|
| 控制台 | **无官方 UI**（配置管理=Git 工作流；第三方面板均非官方） | 内建控制台：命名空间/分组/权限/发布历史/灰度/监听查询；3.x 控制台独立部署（`bootstrap/` 模块聚合 console+core+server，`nacos: bootstrap/pom.xml:29-51`）与 Admin API v3（`Constants.java:120` `/v3/admin/cs`） |
| 审计 | Git 提交历史 + PR 审批（开发流程即审批流） | 服务端发布历史、登录鉴权（默认开启于 3.0+，"安全零信任"）、操作日志 |
| 多语言 | REST 是标准 HTTP+JSON，任何语言可读；但无官方多语言客户端，`{cipher}`/origin 等语义要自己实现 | 官方多语言 SDK（Java/Go/Python/C++...）+ 长连接语义统一；3.x 面向 AI 生态的 MCP Registry/A2A（非本文重点但体现平台化路线） |
| 依赖面 | 客户端极轻（starter + 可选 retry） | 客户端自带 gRPC 栈（较重）；服务端是独立集群（3 节点起）+ MySQL |
| 与 K8s | 天然 GitOps（配置 PR 即发布流程）；也可对比 ConfigMap（见姊妹篇《Spring Cloud vs k8s.md》2.4 节） | 亦有 K8s 部署方案；国内常见"Nacos 替代 ConfigMap 动态配置"用法 |

## 9.9 全维度对比总表

| 维度 | Spring Cloud Config | Nacos（2.x/3.x） |
|---|---|---|
| 定位 | 配置中心（单一职责） | 配置中心 + 注册中心（+3.x AI 注册） |
| 数据模型 | name/profile/label（文件语义） | dataId/group/namespace（KV 语义） |
| 真源/存储 | Git（或 SVN/JDBC/Vault…） | MySQL / Derby+JRaft |
| 架构模型 | Server 无状态，拉模型 | 有状态集群，推（通知）拉（内容）结合 |
| 变更感知 | 事件触发重拉（Bus/webhook/人工）；默认无主动感知 | gRPC 长连接秒级推送 + 30s 兜底轮询 |
| 刷新编程模型 | Environment 重载：Rebinder/RefreshScope | Listener 内容回调（Spring 生态内被适配成 RefreshEvent） |
| 灰度 | Git 分支（部署期） | Beta(IP)/Tag 规则（运行期） |
| 版本/回滚 | Git Tag/commit，天然 | 发布历史 + CAS；无分支概念 |
| 本地容错 | 无快照（fail-fast/optional 二选一） | 快照 + failover 文件双保险 |
| 加密 | {cipher} + /encrypt 端点（应用层） | 加密插件 SPI（KMS 等，`plugin/encryption`），密钥与内容分离存储 |
| 控制台/审计 | 无官方 UI；Git 审计 | 内建控制台 + 鉴权 + 发布历史 |
| 多语言 | 仅 REST 可用 | 官方多语言 SDK |
| 部署面 | Server 随用随起，可嵌入应用 | 独立集群 + DB（或内嵌 derby 单机） |
| Spring 优先级语义 | Boot ConfigData Option 精确表达 profile 优先级 | 由 spring-cloud-alibaba 集成层约定 |
| 学习/运维成本 | 低（一个 Git 仓库起步） | 中（集群+DB+控制台+鉴权） |

## 9.10 选型决策与组合用法

**决策树**（按序自问）：

```
1. 已经用 Spring Cloud Alibaba（Nacos 做注册中心）？
   → 是：配置直接放 Nacos，别再引 Config（两套配置中心徒增心智负担）。
2. 配置变更需要"秒级、无 MQ、无人值守"地生效？
   → 是：Nacos 的长连接推送是现成答案；Config 需要自建 Bus+webhook 链路。
3. 团队是强 GitOps 流程（配置变更必须走 PR/审批/回滚）？
   → 是：Config + Git 是最贴合的（发布流程 = 开发流程）。
4. 需要运行期 IP 级灰度 / 控制台改配置 / 多语言 SDK？
   → 是：Nacos（Config 无运行时灰度、无官方 UI）。
5. 只有少量 Spring 服务、变更频率低、追求架构简单？
   → Config（甚至 Server 嵌入式：spring.cloud.config.server.bootstrap）。
```

**常见的组合姿势**（不互斥）：

- **敏感配置分流**：业务配置进 Config（Git），凭证进 Vault（Config 的 composite 后端，5.7 节）或直接 Vault Agent sidecar——密钥根本不过 Config Server。
- **国内双栈过渡**：存量 Config + 增量 Nacos，用 Nacos 的 namespace 对应 Config 的环境 profile，逐步迁移 dataId。
- **K8s 原生补充**：ConfigMap 管静态部署配置（ArgoCD GitOps），Config/Nacos 管业务动态配置——三者并存不冲突（姊妹篇 2.4 节有完整矩阵）。

## 9.11 本章小结

- 一切差异的源头是定位：**Config 把"配置存储"外包给 Git、把"变更分发"外包给 MQ，自己只做 Environment 协议**；Nacos 把存储（DB）、分发（长连接）、灰度、控制台全部内建，是一个自洽的配置平台。
- 五条主线：数据模型（文件语义 vs 三元组）、感知（事件重拉 vs 推通知）、刷新（环境重载 vs 内容回调）、容错（无快照 vs 快照+failover）、灰度（Git 分支 vs 运行期规则）。
- 选型不问"谁更强"，问"你的配置变更该走开发流程（GitOps）还是运维操作（控制台）"——这是两条路线真正的分野。

---

# 十、贯通视图：一次配置变更的一生

## 10.1 时间线一：应用启动（配置如何进来）

```
mvn spring-boot:run
 └─ Spring Environment 准备阶段
     ├─ ConfigDataEnvironmentPostProcessor 处理 spring.config.import=configserver:
     │    ├─ ConfigServerConfigDataLocationResolver.resolveProfileSpecific()
     │    │    ├─ loadProperties()：绑定 spring.cloud.config.*（name/profile/label/uri/fail-fast…）
     │    │    ├─ 注册 bootstrap 上下文：RestTemplate（超时+Basic 认证）
     │    │    └─ [可选] discovery.enabled → 从注册中心解析 Server 真实地址
     │    └─ ConfigServerConfigDataLoader.doLoad()
     │         ├─ GET {uri}/{name}/{profiles}[/{label}]  （Accept: v2+json, X-Config-Token）
     │         ├─ [可选 spring-retry] ConfigClientRetryBootstrapper 的 LoaderInterceptor 包裹重试
     │         └─ 倒序插桩 PropertySource → configClient 哨兵源(state/version)
     ├─ ConfigServerConfigDataMissingEnvironmentPostProcessor：没写 import？→ 启动失败+人话报告
     └─ 容器 refresh：@Value/@ConfigurationProperties 绑定远程值；占位符跨源解析
```

## 10.2 时间线二：服务端处理一次 GET（配置如何被装配）

```
GET /order-service/prod/main
 └─ EnvironmentController.getEnvironment()  （校验 profile、denormalize label）
     └─ EnvironmentEncryptorEnvironmentRepository.findOne()      （装饰器外层）
         ├─ SearchPathCompositeEnvironmentRepository.findOne()    （按 @Order 组合 git/vault…）
         │    └─ JGitEnvironmentRepository.findOne()  [synchronized(LOCK)]
         │         ├─ refresh("main")：fetch → checkout → merge →(必要时) resetHard
         │         ├─ version = HEAD commit id
         │         └─ findOneInternal() → NativeEnvironmentRepository
         │              ├─ getLocations()：{application}/{profile}/{label} 占位符展开为搜索路径
         │              └─ ConfigDataEnvironmentPostProcessor.applyTo()   ← Boot 引擎读 application.yml / order-service-prod.yml
         ├─ CipherEnvironmentEncryptor.decrypt()：{cipher} → 明文（失败 → invalid.<key>）
         └─ overrides addFirst()
     └─ 序列化 environment.Environment → JSON（v2 带 origin）
```

## 10.3 时间线三：一次 Git push 之后（变更如何生效）

```
git push（order-service-prod.yml 改了超时参数）
 ├─ Git 平台 webhook → POST /monitor（config-monitor）
 │    ├─ GithubPropertyPathNotificationExtractor.extract() → paths=["order-service-prod.yml"]
 │    ├─ guessServiceName：order-service-prod → order-service（+candidates）
 │    └─ publish RefreshRemoteApplicationEvent(origin=server, dest=order-service)
 ├─ Bus binder（RabbitMQ/Kafka springCloudBus topic）广播
 ├─ 每个实例：RemoteApplicationEventListener（ServiceMatcher 命中）→ RefreshListener
 │    └─ ContextRefresher.refresh()
 │         ├─ refreshEnvironment()：ConfigDataContextRefresher.updateEnvironment()
 │         │    └─ 重跑 ConfigData → 重新 GET Server → JGit fetch 到新 commit → 新属性源替换旧的
 │         ├─ changes(before, after) → {request.timeout: 3000}
 │         └─ publish EnvironmentChangeEvent(keys)
 │              ├─ ConfigurationPropertiesRebinder：前缀命中的 @ConfigurationProperties bean 重绑定
 │              └─ scope.refreshAll()：@RefreshScope bean 销毁，下次访问重建
 └─ 未标 @RefreshScope 且非 @ConfigurationProperties 的 @Value：保持旧值（直到重启）
```

## 10.4 终章小结

三条时间线合起来就是 Spring Cloud Config 的全部：**启动时一条 HTTP 契约把 Git 变成 Environment；运行时一套 Scope/事件机制让"重启才能生效"变成"事件触发重建"；运维上一条 Bus 让单实例刷新变成集群广播**。它没有 Nacos 那样的实时推送与控制台，但把每一层都换成了 Spring 生态的原生抽象——Environment、ConfigData、事件、代理——这也是它读起来像"Spring Framework 的续集"而不是一个独立中间件的原因。什么时候用它、什么时候用 Nacos、什么时候两者混用，答案不在功能表里，而在你的配置变更流程属于"开发"还是"运维"（9.10 节的决策树）。
