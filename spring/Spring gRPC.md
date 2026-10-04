# Spring gRPC 深度源码解析（写给初学者的架构全景）

> **本文基于的源码**（双仓库基线）：
> ① `D:\code\3rd\spring-grpc`，main 分支 **1.1.2-SNAPSHOT**（Git commit `692a9ac`，2026-09-28，正在做 Maven→Gradle 迁移）——Spring gRPC 核心库本体；
> ② `D:\code\3rd\spring-boot`，main 分支 **4.2.0-SNAPSHOT**（Git commit `7f9eef2c33c`，2026-10-01）——自 **Boot 4.1.0** 起内置的 `spring-boot-grpc-client` / `spring-boot-grpc-server` / `spring-boot-grpc-test` 三个自动配置模块。
> 文中所有【源码证据】的文件路径与行号均为对上述快照实际读取所得；行号只对快照精确，读者按类名 + 方法名定位即可。
>
> **版本取舍说明**：Spring gRPC 是个年轻项目——2024-10 发布 v0.1.0，2025-12 发布 1.0.0（随 Spring Boot 4 进入官方组合），2026-03 起把 Boot 自动配置整体移交 Spring Boot 4.1。因此读这份文档必须建立"双仓库"意识：**core 在 spring-grpc 仓库，装配在 spring-boot 仓库**。1.0 与 1.1 的自动配置类名高度一致（只是搬家），本文结论对两者都适用，差异处均以行文标注。
>
> **阅读约定**：与系列文档一致，每章"先白话、后源码"——先用一两句人话讲清"这是什么、为什么需要它"，再给出源码证据链（`模块/src/main/java/.../类名.java` + 方法名 + 行号 + 代码片段）。所有"某特性属于哪个版本"的结论均经本地 git 标签（v0.1.0 ~ v1.1.1）逐项实证。

## 如何读这份文档

如果你已经读过本系列（如《Spring Cloud LoadBalancer.md》《Spring Cloud Alibaba Nacos.md》），推荐两遍读法：

- **第一遍（建立地图，1~1.5 小时）**：只读第一章每一节的开头白话段、各章"本章小结"、以及第十二章（贯通视图）。目标是能回答：一个 `BindableService` Bean 如何变成正在监听的 gRPC 服务器？`@ImportGrpcClients` 为什么不需要写 URL？客户端拦截器的顺序为什么"反着排"？
- **第二遍（深入源码）**：对照【源码证据】逐行读。顺序建议：第二章（服务端装配内核）→ 第六章（客户端注册内核）→ 第七章（Channel 工厂）→ 第五章（Boot 条件装配，理解"为什么我只写了两个类就能跑"）→ 第八章（异常）→ 第九章（安全）→ 第十章（横向能力）。

---

# 一、总览：Spring gRPC 的定位、设计哲学与整体架构

## 1.1 一句话定位

**Spring gRPC 是一个把 grpc-java 原生 API 接入 Spring 编程模型的"薄封装层"**：它不重新发明 gRPC——传输、序列化、HTTP/2、流控全部复用 grpc-java；它做的是三件事：

1. **服务端**：让"实现 gRPC 服务的 `BindableService` Bean"被自动发现并组装进一台随容器启动的 `io.grpc.Server`；
2. **客户端**：让 protobuf 生成的 Stub 类型通过 `@ImportGrpcClients` 批量注册成可注入的懒加载 Bean，Channel 的创建、凭据、拦截器由 `GrpcChannelFactory` 统一打理；
3. **语义对齐**：把 Spring 已有的横切能力（异常映射、Security、Observation、健康检查、SSL Bundle、AOT）逐个翻译成 gRPC 的"拦截器 + Builder 定制器"原语。

它的出身也决定了风格：项目由 Broadcom（Spring 团队）在 2024 年发起，核心作者 Dave Syer、Chris Bono；`@GrpcService` 注解的第一作者署名是 `Michael (yidongnan@gmail.com)`——正是社区最流行的 `grpc-spring-boot-starter`（net.devh）的原作者，相当于把社区几年摸索出来的最佳实践"收编进官方"。

从本系列的视角看：**Nacos 3.x 客户端与服务端之间的通信通道就是 gRPC**（见《Spring Cloud Alibaba Nacos.md》第二章），gRPC 也是 Spring 官方组合中 HTTP/REST（RestClient/HttpServiceProxy）之外的另一条 RPC 主干道。Spring gRPC 就是这条主干道的官方 Spring 接入层。

## 1.2 设计哲学：读源码前先记住四句话

1. **薄封装，不重造 gRPC**。核心库 `spring-grpc-core` 全部 Java 源码只有约 **7300 行**（实测 `find . -name "*.java" | xargs wc -l`），其中没有一行是自己实现 RPC：服务器创建委托给 `Grpc.newServerBuilderForPort(...)`（SPI 选择 Netty/InProcess 等实现），通道创建委托给 `Grpc.newChannelBuilder(...)`。框架自己只提供"工厂 + 生命周期 + 装配"。
2. **容器即注册表**。服务端：`BindableService` 类型的 Bean 就是 gRPC 服务（`@GrpcService` 注解是可选的附加信息）；客户端：Stub 类型 Bean 就是 gRPC 客户端（由 Bean 定义的 `factoryMethodOnBean` 惰性创建）。框架的核心工作是三段式：**发现（Discoverer/扫描）→ 配置（Configurer/拦截器装配）→ 制造（Factory/Builder）**。
3. **一切横切能力皆拦截器，一切参数定制皆 Customizer**。异常映射是全局 `ServerInterceptor`，安全是 `ServerInterceptor` + `AuthorizationManager<CallContext>`，观测是 Micrometer 提供的 `ObservationGrpcServerInterceptor` Bean；通道/服务器参数用 `ServerBuilderCustomizer` / `GrpcChannelBuilderCustomizer` 注入。框架自己从不"藏私"——你能用 Bean 定制一切。
4. **Spring 语义全量对齐，最终"回家"到 Boot**。`SmartLifecycle` 启停、`ApplicationEvent` 生命周期事件、`@ConfigurationProperties`、`SslBundles`、`@Order` 排序、AOT/Native hints，一个不缺；1.1 起连自动配置本体都移交 Boot（Boot 4.1 的 `spring-boot-grpc-*` 模块，管理 spring-grpc-core 1.1.0 依赖）——这是它与所有社区 starter 最本质的区别。

## 1.3 模块分层全景

Spring gRPC 1.1 是"**单核心库 + Boot 三模块**"结构（1.0 及之前是"单核心库 + 本仓库 autoconfigure/starter 若干"，见 1.6.3 迁移说明）：

```
┌─────────────────────────── Spring Boot 4.1+（spring-boot 仓库）────────────────────────────┐
│  module/spring-boot-grpc-server                                                            │
│    autoconfigure（GrpcServerAutoConfiguration + Netty/Shaded/Servlet/InProcess 四传输配置） │
│    health（GrpcServerHealth / StatusAggregator / 健康调度）                                  │
│    security autoconfigure（GrpcServerSecurityAutoConfiguration / OAuth2 资源服务器）        │
│  module/spring-boot-grpc-client                                                            │
│    autoconfigure（GrpcClientAutoConfiguration + CompositeChannelFactory + Netty/InProcess）│
│    PropertiesVirtualTargets / PropertiesChannelCredentialsProvider / 观测配置               │
│  module/spring-boot-grpc-test                                                              │
│    AutoConfigureTestGrpcTransport（in-process 测试传输）+ LocalGrpcServerPort               │
├─────────────────────────── spring-grpc-core（spring-grpc 仓库）────────────────────────────┤
│  org.springframework.grpc.client                                                           │
│    注册：ImportGrpcClients / AnnotationGrpcClientRegistrar / GrpcClientFactory              │
│    通道：GrpcChannelFactory / Default / Netty / ShadedNetty / InProcess / Composite         │
│    凭据：ChannelCredentialsProvider    拦截器：ClientInterceptorsConfigurer                 │
│    Stub 工厂：Blocking/BlockingV2/Future/Reactor/Coroutine/Simple + StubFactory SPI         │
│  org.springframework.grpc.server                                                           │
│    工厂：GrpcServerFactory / Default / Netty / ShadedNetty / InProcess                      │
│    生命周期：GrpcServerLifecycle + GrpcServer{Started,Shutdown,Terminated}Event              │
│    服务装配：GrpcServiceDiscoverer / GrpcServiceConfigurer / @GrpcService                   │
│    异常：exception（拦截器式处理）+ advice（@GrpcAdvice / @GrpcExceptionHandler）           │
│    安全：security（GrpcSecurity DSL / AuthenticationProcessInterceptor / 提取器族）          │
│  org.springframework.grpc.internal：GrpcUtils（地址解析）/ GrpcHeaders / ClasspathScanner   │
├─────────────────────────── 依赖的第三方 ───────────────────────────────────────────────────┤
│  grpc-java 1.83.1（grpc-netty / grpc-inprocess / grpc-services / grpc-protobuf / stub）     │
│  protobuf-java 4.35.1   Spring Framework 7.0.8   Spring Security 7.1.0                      │
│  Micrometer（Observation + grpc observation binder，由 Boot 侧引入）                         │
└────────────────────────────────────────────────────────────────────────────────────────────┘
```

## 1.4 模块依赖与版本基线（以各 pom 实证）

核心库 `spring-grpc-core` 的依赖面非常克制（`spring-grpc-core/pom.xml`，1.1.2-SNAPSHOT）：

| 依赖 | 版本（main 快照） | 用途 |
|---|---|---|
| grpc-bom（grpc-api/stub/protobuf/inprocess/netty/services…） | **1.83.1** | 一切 RPC 能力的来源 |
| protobuf-java / protobuf-bom | **4.35.1** | 消息序列化 |
| Spring Framework BOM | **7.0.8** | 容器、Bean 注册、AOT、JSpecify 空安全 |
| Spring Security BOM（optional） | **7.1.0** | `server/security`、客户端 token 拦截器 |
| micrometer（observation，optional） | 随 Boot 管理 | `GrpcSecurity` 内 ObservationAuthenticationManager |

版本矩阵（git tag 实证，`spring-grpc-build-dependencies/pom.xml` / `spring-grpc-dependencies/pom.xml`）：

| spring-grpc | 发布日期 | grpc-java | protobuf | Framework | 对应 Boot 时代 |
|---|---|---|---|---|---|
| 0.1.0 | 2024-10-25 | 1.63.2 | 3.25.5 | 6.1.14 | Boot 3.3/3.4 前夜 |
| 0.3.0 | 2025-01-15 | 1.69.0 | 3.25.5 | 6.2.1 | Boot 3.4 |
| 0.5.0 | 2025-03-10 | 1.70.0 | 3.25.6 | 6.2.3 | Boot 3.4 |
| 0.9.0 | 2025-07-04 | 1.72.0 | 4.30.2 | 6.2.8 | Boot 3.5（官方 whats-new 明确） |
| 1.0.0 | 2025-12-03 | 1.77.0 | 4.33.1 | 7.0.1 | **Boot 4.0** |
| 1.1.0 | 2026-06-09 | 1.81.0 | 4.35.0 | 7.0.8 | **Boot 4.1（自动配置移交）** |
| 1.1.1 | 2026-08-06 | 1.81.x | 4.35.x | 7.0.x | Boot 4.1.x |
| 1.1.2-SNAPSHOT（本文快照） | — | 1.83.1 | 4.35.1 | 7.0.8 | Boot 4.2 开发线 |

Boot 侧的对应关系（spring-boot 仓库实证）：`module/spring-boot-grpc-server` 等三个模块的首次提交为 `e61bb6df5be`（2026-03-19，"Add Spring gRPC server support"，与 spring-grpc v1.1.0-M1 同日）；`v4.0.0` 标签中无这些模块、`v4.1.0` 标签中齐全；`v4.1.0` 的依赖平台（`platform/spring-boot-dependencies/build.gradle`）声明 `library("Spring gRPC", "1.1.0")`。即：**Spring Boot 4.1.0 是首个内置 gRPC 自动配置的版本**。

## 1.5 关键问题 → Spring gRPC 方案映射（全文导览）

| 企业开发的关键问题 | Spring gRPC 的方案 | 详见 |
|---|---|---|
| gRPC 服务实现类如何注册进服务器 | `BindableService` Bean 自动发现（`DefaultGrpcServiceDiscoverer`）+ `GrpcServiceConfigurer` 拦截器装配 | 第二章 |
| 服务器端口/TLS/keepalive 等参数如何配 | `GrpcServerProperties`（spring.grpc.server.*）+ `ServerBuilderCustomizer` + `SslBundles` | 第三章、第五章 |
| 服务器随容器启动/优雅停机 | `GrpcServerLifecycle`（SmartLifecycle，phase=MAX_VALUE）+ 三个生命周期事件 | 第四章 |
| Web 应用里不想多占一个端口 | Servlet 传输：`GrpcServlet` 注册到 Servlet 容器（HTTP/2） | 第五章 |
| 客户端 Stub 到处 `ManagedChannelBuilder.forAddress` 样板代码 | `@ImportGrpcClients` 扫描/枚举 Stub 类型 → 工厂方法 Bean 懒创建 | 第六章 |
| 多个目标服务器、地址含占位符、TLS 凭据分发 | `PropertiesVirtualTargets`（命名通道）+ `ChannelCredentialsProvider` + SslBundles | 第七章 |
| 异常→Status 手工转换样板 | `GrpcExceptionHandlerInterceptor` + `@GrpcAdvice`/`@GrpcExceptionHandler` | 第八章 |
| 认证鉴权（Basic/Bearer/预认证/OAuth2 资源服务器） | `GrpcSecurity` DSL + `AuthenticationProcessInterceptor` + 客户端 token 拦截器 | 第九章 |
| 链路追踪与指标 | Micrometer `ObservationGrpc{Client,Server}Interceptor`（Boot 自动注册为全局拦截器） | 第十章 |
| 健康检查（gRPC 标准 Health 协议） | `HealthStatusManager` + Boot `HealthContributorRegistry` 聚合 | 第十章 |
| 单元测试不起网口 | `@AutoConfigureTestGrpcTransport`（in-process 传输） | 第十章 |
| GraalVM Native | `ClientBeanRegistrationsAotProcessor` 反射/资源 hints | 第十章 |

## 1.6 版本演进：0.x → 1.0 → 1.1 关键变化对比

### 1.6.1 版本时间线与主题

| 版本 | GA 日期 | 一句话主题 |
|---|---|---|
| 0.1.0 | 2024-10-25 | 项目启动：core 20 个类 + 单一 autoconfigure + starter；已有 Netty/Shaded 工厂与 Lifecycle 事件 |
| 0.3.0 | 2025-01-15 | 服务装配体系成形：`@GrpcService`/Discoverer/Configurer（本快照 `server/service` 包 6 类首次出现）；security 包出现 |
| 0.4.0 | 2025-03-03 | 拆分细粒度 starter：`client-/server-/server-web-spring-boot-starter` |
| 0.5.0 | 2025-03-10 | 自动客户端：`@EnableGrpcClients`/`@GrpcClient` 注解 + 包扫描；AOT 处理器落地 |
| 0.6.0 | 2025-04-04 | **`@GrpcClient` 更名 `@ImportGrpcClients`**（官方 whats-new 记载，tag 实证：v0.5.0 有 `client/GrpcClient.java`、v0.6.0 起变为 `client/ImportGrpcClients.java`）；`BlockingV2StubFactory`、`DefaultDeadlineSetupClientInterceptor`（#136）落地 |
| 0.9.0 | 2025-07-04 | `StubFactory.supports` 改为静态方法；`GrpcChannelBuilderCustomizer` 取代 `GrpcClientFactoryCustomizer`；支持 in-process 拦截器/服务定义过滤 |
| 1.0.0 | 2025-12-03 | 基线升级 Boot 4 / Framework 7 / JSpecify / Java 25；自动配置模块拆分为 client/server/test 三个独立模块 |
| 1.1.0 | 2026-06-09 | **自动配置与 starter 移交 Spring Boot 4.1**；新增 `@GrpcAdvice` 异常适配体系（v1.1.0-M1 起实证）；官方 wiki 发布 1.1 迁移指南 |
| 1.1.2-SNAPSHOT | — | Maven→Gradle 构建迁移（HEAD commit "Minimal Gradle config and migration guide"） |

### 1.6.2 特性引入版本对照表（git tag 实证，可复现）

每行可用 `git ls-tree -r --name-only <tag> | grep <关键字>` 或 `git grep <关键字> <tag>` 复现：

| 特性 | 引入版本 | 实证（旧 tag → 新 tag） | 详见 |
|---|---|---|---|
| 服务器工厂家族 + SmartLifecycle + 生命周期事件 | 0.1.0 | v0.1.0 已有 `DefaultGrpcServerFactory`/`GrpcServerLifecycle`/三个 Event | 三、四章 |
| `@GrpcService`/`GrpcServiceDiscoverer`/`Configurer` | 0.3.0 | v0.1.0 的 core 无 `server/service` 包（20 类）→ v0.3.0 新增 6 类 | 二 |
| `server/security` 包（GrpcSecurity DSL） | 0.3.0 | v0.1.0=0 文件 → v0.3.0=6 文件 | 九 |
| 细粒度 starter（client/server/web） | 0.4.0 | v0.3.0 无 → v0.5.0 有 `spring-grpc-{client,server}-spring-boot-starter` | — |
| `@GrpcClient`（后更名）+ 包扫描 + AOT 处理器 | 0.5.0 | v0.5.0 新增 `client/GrpcClient.java`、`client/aot/` | 六、十 |
| `@ImportGrpcClients` 更名 | 0.6.0 | v0.6.0 起 `ImportGrpcClients.java` 替代 `GrpcClient.java` | 六 |
| `BlockingV2StubFactory`（gRPC v2 blocking stub） | 0.6.0 | 首次提交 2025-04-03 | 六 |
| 默认 deadline 拦截器 | 0.6.0 | `DefaultDeadlineSetupClientInterceptor` 首次提交 2025-03-13（#136） | 七 |
| Boot 内置 `module/spring-boot-grpc-*` | **Boot 4.1.0** | v4.0.0 无 → v4.1.0 有；首个提交 2026-03-19 | 五、七 |
| `@GrpcAdvice` advice 体系 | 1.1.0（M1 起） | v1.0.0=0 文件 → v1.1.0-M1=7 文件 | 八 |
| Boot 自动配置移交 | 1.1.0 | v1.0.0 有 `spring-grpc-{client,server,test}-spring-boot-autoconfigure` → v1.1.0 全部删除 | 1.6.3 |

三个容易搞错的点：

- **`@GrpcClient` 不是最终形态**：它只在 0.5.0 生存了一个小版本，0.6.0 即更名 `@ImportGrpcClients`——网上教程若教你在类上标注 `@GrpcClient`，那是旧版（或 net.devh 库的注解，同名不同物）；
- **1.1 之后 spring-grpc 仓库里没有自动配置**：`spring.grpc.server.*` / `spring.grpc.client.*` 的配置类在 spring-boot 仓库的 `spring-boot-grpc-*` 模块里，读源码别找错仓库；
- **`@GrpcService` 是可选注解**：不加它，`BindableService` Bean 照样注册（但无法声明服务级拦截器）。

### 1.6.3 1.0 → 1.1 迁移要点（官方 whats-new + Boot 侧源码）

1. **依赖坐标变化**：starter 由 `org.springframework.grpc:spring-grpc-*-spring-boot-starter` 改为 Spring Boot 的 `spring-boot-starter-grpc-*` 系（Boot 4.1 文档 `io/grpc.adoc` 链接 spring-grpc 参考文档）；核心库坐标不变（`spring-grpc-core`）。
2. **自动配置包名变化**：`org.springframework.grpc.autoconfigure.*` → `org.springframework.boot.grpc.{server,client,test}.autoconfigure.*`（本快照 Boot 侧 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 实测 10 条）。
3. **属性命名空间不变**：仍是 `spring.grpc.server.*` / `spring.grpc.client.*`，两代自动配置属性类（`GrpcServerProperties`/`GrpcClientProperties`）结构一致。

## 1.7 全文章节地图

- **第二章 服务端装配内核（spring-grpc-core）**：`@GrpcService` → Discoverer → Configurer 的三段式，拦截器"全局+服务级+融合排序"的完整规则。
- **第三章 服务器工厂与传输层**：`DefaultGrpcServerFactory` 模板骨架、Netty（含 unix domain socket）、Shaded Netty、InProcess 四实现；TLS 凭据与地址解析。
- **第四章 生命周期与事件**：`GrpcServerLifecycle` 的启动、等待线程、三档优雅停机、三个事件。
- **第五章 Boot 服务端装配决策树**：条件注解如何自动选出 Servlet/Netty/Shaded/InProcess 之一；`GrpcServerProperties` 全景；反射服务。
- **第六章 客户端注册内核（spring-grpc-core）**：`@ImportGrpcClients` → Registrar → `GrpcClientFactory.register` 的工厂方法 Bean 机制；StubFactory SPI。
- **第七章 Channel 工厂与 Boot 客户端装配**：`DefaultGrpcChannelFactory` 建连五步与有序停机；Composite 工厂；命名通道（VirtualTargets）、凭据 Provider、客户端拦截器顺序之谜。
- **第八章 异常处理**：异常拦截器的包装机制与 `@GrpcAdvice` 注解体系。
- **第九章 安全**：服务端 `GrpcSecurity` DSL 与认证拦截器、Servlet 场景、客户端 token 拦截器。
- **第十章 可观测、健康、测试与 AOT**。
- **第十一章 配置大全**：`spring.grpc.*` 属性速查。
- **第十二章 贯通视图**：装配、调用、停机三条时间线。
- **第十三章 附录**：扩展点速查、与系列文档联动、学习路线。

---

# 二、服务端装配内核：从 `@GrpcService` 到 `ServerServiceDefinition`（spring-grpc-core）

> 本章对应源码：`spring-grpc-core/src/main/java/org/springframework/grpc/server/service/`。这一包只有 9 个类，却定义了"Spring 容器里的一个 gRPC 服务"的完整语义。

## 2.1 `@GrpcService`：一个"元注解复合体"

**白话**：gRPC 服务实现类（protobuf 生成的 `XxxGrpc.XxxImplBase` 子类）要变成 Spring Bean 才能被容器管理，也要实现 `io.grpc.BindableService` 才能被 gRPC 服务器托管。`@GrpcService` 同时满足这两个条件——它自己组合了 `@Service` 和 `@Bean` 两个元注解，贴上它等于"声明为组件 + 可作为 @Bean 方法返回值"，再附带声明服务级拦截器的位置。

【源码证据】`spring-grpc-core/src/main/java/org/springframework/grpc/server/service/GrpcService.java:43-74`：

```java
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Service      // ← 元注解：标了 @GrpcService 的类自动成为组件扫描候选
@Bean         // ← 元注解：标了 @GrpcService 的方法自动成为 Bean 方法
public @interface GrpcService {

	Class<? extends ServerInterceptor>[] interceptors() default {};   // 按类型引用拦截器

	String[] interceptorNames() default {};                           // 按名字引用拦截器

	boolean blendWithGlobalInterceptors() default false;              // 是否与全局拦截器"融合排序"
}
```

注意两点：

- **注解是可选的**。类 Javadoc 明确写着 "NOTE: This annotation is optional as all `BindableService` beans will be registered with a gRPC server"（GrpcService.java:33-38）——不加注解的服务照样注册，只是拿不到"服务级拦截器"这些附加信息；
- **TYPE 与 METHOD 双目标**：既可贴在实现类上，也可贴在 `@Configuration` 里返回 `BindableService` 的 `@Bean` 方法上。

## 2.2 Discoverer：把容器当成服务注册表

**白话**：服务器工厂需要拿到"所有要托管的服务"。`DefaultGrpcServiceDiscoverer` 做的事情用一句话说就是：**"给我容器里所有 `BindableService` 类型的 Bean，按 `@Order` 排好，并顺带告诉我谁贴了 `@GrpcService`（附加信息）"**。

【源码证据】`server/service/DefaultGrpcServiceDiscoverer.java:45-52`：

```java
@Override
public List<GrpcServiceSpec> findServices() {
	return ApplicationContextBeanLookupUtils
		.getOrderedBeansWithAnnotation(this.applicationContext, BindableService.class, GrpcService.class)
		.entrySet()
		.stream()
		.map((e) -> new GrpcServiceSpec(e.getKey(), this.serviceInfo(e.getValue())))
		.toList();
}
```

`GrpcServiceSpec` 是个两元组 record（`GrpcServiceSpec.java:28`）：`service`（`BindableService` 实例）+ `serviceInfo`（可空的 `@GrpcService` 注解信息）。排序能力来自内部工具 `ApplicationContextBeanLookupUtils.getOrderedBeansWithAnnotation`（`internal/ApplicationContextBeanLookupUtils.java`）：先 `getBeanProvider(beanType).orderedStream()` 按 `@Order` 流式取出，再对每个 Bean 反查 `findAnnotationOnBean` 拿注解——**顺序与注解信息一次查清**，这是后续拦截器"融合排序"的地基。

## 2.3 Configurer：拦截器装配的完整规则

**白话**：`bindService()` 只是拿到了"裸"的 `ServerServiceDefinition`（服务描述 + 方法处理器）；真正的服务级横切（认证、日志、异常映射）都要靠 `ServerInterceptors.interceptForward` 把拦截器"缠绕"上去。`DefaultGrpcServiceConfigurer` 定义的缠绕规则是：**全局拦截器（打头）→ 按工厂过滤 → 追加服务级（按类型、按名字）→ 可选按 @Order 融合重排**。

【源码证据】`server/service/DefaultGrpcServiceConfigurer.java:69-94`（`bindInterceptors` 方法）：

```java
private ServerServiceDefinition bindInterceptors(BindableService bindableService,
		@Nullable GrpcServiceInfo serviceInfo, @Nullable GrpcServerFactory serverFactory) {
	var serviceDef = bindableService.bindService();

	// ① 全局拦截器打头（初始化时收集：所有贴 @GlobalServerInterceptor 的 Bean）
	List<ServerInterceptor> allInterceptors = new ArrayList<>(this.globalInterceptors);
	// ② 让服务器工厂过滤（例如 in-process 工厂可声明"不要哪些拦截器"）
	if (serverFactory != null) {
		allInterceptors.removeIf(interceptor -> !serverFactory.supports(interceptor, serviceDef));
	}
	if (serviceInfo == null) {
		// ③ 未标注 @GrpcService：只有全局拦截器
		return ServerInterceptors.interceptForward(serviceDef, allInterceptors);
	}
	// ④ 服务级拦截器：@GrpcService(interceptors = {...}) 按类型
	Arrays.stream(serviceInfo.interceptors())
		.forEachOrdered((interceptorClass) -> allInterceptors.add(this.applicationContext.getBean(interceptorClass)));
	// ⑤ @GrpcService(interceptorNames = {...}) 按名字
	Arrays.stream(serviceInfo.interceptorNames())
		.forEachOrdered((interceptorBeanName) -> allInterceptors
			.add(this.applicationContext.getBean(interceptorBeanName, ServerInterceptor.class)));
	// ⑥ blendWithGlobalInterceptors=true 时整体按 @Order 重新排序
	if (serviceInfo.blendWithGlobalInterceptors()) {
		ApplicationContextBeanLookupUtils.sortBeansIncludingOrderAnnotation(this.applicationContext,
				ServerInterceptor.class, allInterceptors);
	}
	return ServerInterceptors.interceptForward(serviceDef, allInterceptors);
}
```

全局拦截器的来源在 `afterPropertiesSet`（`DefaultGrpcServiceConfigurer.java:54-56`）——Bean 初始化回调时机收集，避免装配期容器抖动：

```java
this.globalInterceptors.addAll(findGlobalInterceptors());   // = 所有 @GlobalServerInterceptor Bean
```

`@GlobalServerInterceptor`（`server/GlobalServerInterceptor.java:41`）本身不带属性，**顺序完全交给 Bean 上的 `@Order`**（类 Javadoc："The bean interceptor `Order` will be respected"）。

### 2.3.1 服务定义与拦截器过滤的两个口子

0.9.0 引入的两个函数式接口是装配阶段的"否决权"：

- `ServerServiceDefinitionFilter`（`server/service/ServerServiceDefinitionFilter.java`）：`boolean filter(ServerServiceDefinition service, GrpcServerFactory factory)`——工厂在 `addService` 时用（`DefaultGrpcServerFactory.java:119-124`），可整体丢弃某个服务（测试模块用它做服务筛选）；
- `ServerInterceptorFilter`（`server/service/ServerInterceptorFilter.java`）：`boolean filter(ServerInterceptor interceptor, ServerServiceDefinition service)`——工厂在 `supports`（`DefaultGrpcServerFactory.java:102-105`）用，可按"服务维度"否决某个拦截器（客户端侧有对称的 `ClientInterceptorFilter`）。

## 2.4 本章小结

服务端装配内核是一条三段流水线：**Discoverer（按 @Order 列出 BindableService + 注解信息）→ Configurer（全局/服务级拦截器缠绕成最终 ServerServiceDefinition）→ Factory（收货并塞进 ServerBuilder）**。`@GrpcService` 用 `@Service + @Bean` 元注解的复合拳同时解决了"组件发现"与"Bean 方法"两种声明姿势；`@GlobalServerInterceptor` + `@Order` 是全局拦截器的唯一排序语言。下一章看流水线最后一站——服务器工厂如何真正"建出"一台监听中的服务器。

---

# 三、服务器工厂与传输层：Netty / Shaded Netty / InProcess

> 本章对应源码：`spring-grpc-core/src/main/java/org/springframework/grpc/server/`（工厂家族）与 `internal/GrpcUtils.java`（地址解析）。

## 3.1 `DefaultGrpcServerFactory`：模板方法骨架

**白话**：所有服务器工厂共享同一个骨架——"造 Builder → 配服务 → 应用定制器 → build"。它对 gRPC 是零假设的：`newServerBuilder()` 用 `Grpc.newServerBuilderForPort(port, credentials)`，由 **grpc-java 自己的 SPI（`ServerProvider`）** 决定具体实现；类 Javadoc 直言 "The server builder implementation is discovered via Java's SPI mechanism"（`DefaultGrpcServerFactory.java:50-51`）。

【源码证据】`server/DefaultGrpcServerFactory.java:112-133`：

```java
@Override
public Server createServer() {
	T builder = newServerBuilder();
	configure(builder, this.serviceList);
	return builder.build();
}
...
@SuppressWarnings("unchecked")
protected T newServerBuilder() {
	return (T) Grpc.newServerBuilderForPort(port(), credentials());   // SPI 选择实现
}
```

三个关键细节：

1. **凭据协商**（`credentials()`，`DefaultGrpcServerFactory.java:159-171`）：没有配置 `KeyManagerFactory` 或端口为 -1（不监听 socket）时返回 `InsecureServerCredentials`；否则构建 `TlsServerCredentials`（可带 trustManager 做 mTLS `clientAuth`）——**TLS 语义完全委托给 grpc-java 的 credentials API**；
2. **重复服务名防重**（`configureServices`，`DefaultGrpcServerFactory.java:193-203`）：两个 Bean 声明同一个 gRPC 服务名会直接抛 `IllegalStateException("Found duplicate service implementation: ...")`——gRPC 服务器本身不容忍重复，提前到装配期失败；
3. **定制器最后压轴**（`configure`，`DefaultGrpcServerFactory.java:182-185`）：先 `configureServices` 再逐个应用 `ServerBuilderCustomizer`，保证用户定制器永远能覆盖框架默认。

## 3.2 `NettyGrpcServerFactory`：TCP 与 Unix Domain Socket

**白话**：Netty 工厂只干一件事——根据地址字符串选择正确的 `NettyServerBuilder.forXxx` 入口；`unix:` 前缀走 epool domain socket（K8s/同机场景零 TCP 开销），其余按 host:port。

【源码证据】`server/NettyGrpcServerFactory.java:54-72`：

```java
@Override
protected NettyServerBuilder newServerBuilder() {
	String address = address();
	if (address.startsWith("unix:")) {                       // Unix domain socket
		String path = address.substring(5);
		return NettyServerBuilder.forAddress(new DomainSocketAddress(path))
			.channelType(EpollServerDomainSocketChannel.class)
			.bossEventLoopGroup(new MultiThreadIoEventLoopGroup(1, EpollIoHandler.newFactory()))
			.workerEventLoopGroup(new MultiThreadIoEventLoopGroup(EpollIoHandler.newFactory()));
	}
	String host = super.hostname();
	int port = super.port();
	if (host == null || host.equals(GrpcUtils.ANY_IP_ADDRESS)) {   // "*" 通配
		return NettyServerBuilder.forPort(port, credentials());
	}
	SocketAddress socketAddress = new InetSocketAddress(host, port);
	return NettyServerBuilder.forAddress(socketAddress, credentials());
}
```

地址字符串的解析在 `GrpcUtils`：默认端口 **9090**（`internal/GrpcUtils.java:44`：`DEFAULT_PORT = 9090`）、通配符 `*`（`:41`）、`getPort` 用"补假 scheme 的 URI"技巧解析（`:62` 起，`unix:` 与带 scheme 的地址直接抛异常）。`ShadedNettyGrpcServerFactory`（`server/ShadedNettyGrpcServerFactory.java`）是 Netty 工厂的"重定位版"——逻辑相同，只是 builder 换成 `io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder`，用于 grpc-java shading 版本与业务 Netty 冲突的场景。

## 3.3 `InProcessGrpcServerFactory`：进程内"伪网络"

**白话**：in-process 传输是 grpc-java 自带的进程内直连（无 socket、无序列化开销可配），Spring gRPC 把它也统一进工厂家族——地址字符串就是"服务器名"。

【源码证据】`server/InProcessGrpcServerFactory.java:29-40`：

```java
public class InProcessGrpcServerFactory extends DefaultGrpcServerFactory<InProcessServerBuilder> {
	...
	@Override
	protected InProcessServerBuilder newServerBuilder() {
		return InProcessServerBuilder.forName(address());    // 地址 = in-process 服务器名
	}
}
```

它同时实现了 `ServerServiceDefinitionFilter` 的支持（`setServiceFilter`，见 2.3.1）——典型用法是 in-process 服务器只暴露部分服务。

## 3.4 本章小结

工厂家族是**同一个模板骨架（Default）+ 三种传输实现（Netty/Shaded/InProcess）**的组合，TLS 与地址语义各让一寸给 grpc-java（credentials API）与 Spring（字符串地址约定 `host:port`/`unix:path`/`in-process 名`）。`ServerBuilderCustomizer` 是参数定制的唯一出口，且永远在框架默认之后执行。下一章看这些建出来的 `io.grpc.Server` 如何与 Spring 容器同生共死。

---

# 四、生命周期与事件：`GrpcServerLifecycle`

> 本章对应源码：`spring-grpc-core/src/main/java/org/springframework/grpc/server/lifecycle/GrpcServerLifecycle.java`（188 行，完整读一遍）。

**白话**：`io.grpc.Server` 是个"自带线程模型的长时运行对象"，Spring 容器需要决定：何时启动它（所有服务 Bean 就绪后）、如何阻止 JVM 提前退出、停机时等多久。`GrpcServerLifecycle` 实现了 `SmartLifecycle`，把这三个问题一次答完。

【源码证据】启动侧（`GrpcServerLifecycle.java:121-147`）：

```java
protected void createAndStartGrpcServer() throws IOException {
	if (this.server == null) {
		final Server localServer = this.factory.createServer();
		if (localServer != null) {
			this.server = localServer.start();
			...
			this.eventPublisher.publishEvent(new GrpcServerStartedEvent(this, localServer, address, port));

			// Prevent the JVM from shutting down while the server is running
			final Thread awaitThread = new Thread(() -> {
				try {
					localServer.awaitTermination();
				}
				catch (final InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			});
			awaitThread.setName("grpc-server-container-" + (serverCounter.incrementAndGet()));
			awaitThread.setDaemon(false);      // 非守护线程：托住 JVM 不退出
			awaitThread.start();
		}
	}
}
```

三个要点：

1. **`getPhase() = Integer.MAX_VALUE`**（`GrpcServerLifecycle.java:96-98`）+ `isAutoStartup()=true`——gRPC 服务器是容器里**最晚启动**的一批 SmartLifecycle，确保所有业务 Bean、拦截器 Bean 都已就绪；
2. **非守护线程 `awaitTermination`**（`:133-143`）：gRPC 的惯用招法——用一个非守护线程阻塞等待服务器终止，否则主线程跑完 `main` 后 JVM 会直接退出（Spring Boot 的 web 应用靠 web 容器线程托底，gRPC 没有，得自己托）；
3. **事件三连**：`GrpcServerStartedEvent`（启动后，`:130`）→ `GrpcServerShutdownEvent`（收到停机指令，`:158`）→ `GrpcServerTerminatedEvent`（完全终止，`:183`）。监听 `GrpcServerStartedEvent` 是"拿到实际绑定端口后做注册中心上报"的标准姿势。

【源码证据】停机侧三档语义（`GrpcServerLifecycle.java:153-185`）：

```java
protected void stopAndReleaseGrpcServer() {
	final Server localServer = this.server;
	if (localServer != null) {
		final long millis = this.shutdownGracePeriod.toMillis();
		this.eventPublisher.publishEvent(new GrpcServerShutdownEvent(this, localServer));
		localServer.shutdown();                      // ① 优雅关闭：停止接收新调用
		try {
			if (millis > 0) {
				localServer.awaitTermination(millis, TimeUnit.MILLISECONDS);   // ② 等待在途调用完成
			}
			else if (millis == 0) {
				// Do not wait                        // ③ 0：立即走强制关停
			}
			else {
				localServer.awaitTermination();      // ④ 负数：无限等
			}
		}
		catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		finally {
			localServer.shutdownNow();               // ⑤ 兜底强制关闭
			this.server = null;
		}
		...
		this.eventPublisher.publishEvent(new GrpcServerTerminatedEvent(this, localServer));
	}
}
```

宽限期默认 **30 秒**（Boot 侧 `GrpcServerProperties.Shutdown.gracePeriod`，`spring-boot-grpc-server/.../GrpcServerProperties.java:122`）。

**本章小结**：`GrpcServerLifecycle` 是"容器节奏"与"gRPC 服务器节奏"之间的适配器：phase=MAX_VALUE 决定了启动次序，非守护 await 线程决定了 JVM 存活，三档 gracePeriod + `shutdownNow` 兜底决定了停机弹性，三个事件把状态变化广播给容器。

---

# 五、Boot 服务端装配决策树（spring-boot-grpc-server）

> 本章对应源码：`spring-boot/module/spring-boot-grpc-server/src/main/java/org/springframework/boot/grpc/server/autoconfigure/`。读完本章你会明白：**为什么你只是写了两个类，应用就多了一个 gRPC 服务器**。

## 5.1 入口自动配置与五路导入

**白话**：`GrpcServerAutoConfiguration` 是服务端装配的"总开关"，它自己只装配公共组件（服务发现、拦截器融合、异常处理器、属性定制器），把"选哪种传输"交给五个 `@Import` 的嵌套配置互斥竞争。

【源码证据】`GrpcServerAutoConfiguration.java:63-71`：

```java
@AutoConfiguration
@ConditionalOnClass({ GrpcServerFactory.class, Grpc.class })        // classpath 有 spring-grpc-core + grpc-api
@ConditionalOnBean(BindableService.class)                            // 容器里有 gRPC 服务 Bean 才装配
@ConditionalOnBooleanProperty(name = "spring.grpc.server.enabled", matchIfMissing = true)
@EnableConfigurationProperties(GrpcServerProperties.class)
@Import({ GrpcServerCodecConfiguration.class, ServletGrpcServerConfiguration.class,
		ShadedNettyGrpcServerConfiguration.class, NettyGrpcServerConfiguration.class,
		InProcessGrpcServerConfiguration.class })
public final class GrpcServerAutoConfiguration { ... }
```

它还装配了三个值得记住的公共 Bean：

- **全局异常拦截器**（`GrpcServerAutoConfiguration.java:96-103`）：`GrpcExceptionHandlerInterceptor` 以 `@GlobalServerInterceptor` 注册，把容器里所有 `GrpcExceptionHandler` 实现组合成 `CompositeGrpcExceptionHandler`——这是第八章的主角；
- **`@GrpcAdvice` 体系**（`:112-136`）：存在 `@GrpcAdvice` Bean 时才装配 Discoverer/MethodResolver/ExceptionHandler 三件套；
- **Reactive Stub 支持**（`:105-110`）：classpath 有 `com.salesforce.reactivegrpc.common.Function`（reactive-grpc 库）时导入 `ReactiveStubBeanDefinitionRegistrar`，为 Reactor 风格 stub 补注册表。

## 5.2 传输选择的条件决策树

**白话**：四条传输配置的竞争规则是"**Servlet 优先（如果它赢了）→ Shaded/Netty 二选一（看 classpath）→ InProcess 始终可选**"，由两个条件注解完成裁决。

【源码证据】四条配置的条件（类注解逐个列出）：

| 配置类 | 条件 | 源码位置 |
|---|---|---|
| `ServletGrpcServerConfiguration` | `@ConditionalOnWebApplication(SERVLET)` + `@ConditionalOnClass(GrpcServlet.class)` + `@ConditionalOnMissingNetworkGrpcServer` + `spring.grpc.server.servlet.enabled`（默认 true） | `ServletGrpcServerConfiguration.java:41-46` |
| `ShadedNettyGrpcServerConfiguration` | `@ConditionalOnClass(shaded NettyServerBuilder)` + `@ConditionalOnMissingNetworkGrpcServer` + `@ConditionalOnGrpcServerFactoryEnabled` | `ShadedNettyGrpcServerConfiguration.java:42-46` |
| `NettyGrpcServerConfiguration` | `@ConditionalOnClass(NettyServerBuilder)` + `@ConditionalOnMissingNetworkGrpcServer` + `@ConditionalOnGrpcServerFactoryEnabled` | `NettyGrpcServerConfiguration.java:42-46` |
| `InProcessGrpcServerConfiguration` | `@ConditionalOnProperty("spring.grpc.server.inprocess.name")` + `@ConditionalOnMissingBean(InProcessGrpcServerFactory.class)` | `InProcessGrpcServerConfiguration.java:40-44` |

"网络服务器互斥"的条件本体（`MissingNetworkGrpcServerCondition.java:31-47`）：

```java
class MissingNetworkGrpcServerCondition extends AllNestedConditions {
	@ConditionalOnMissingBean(GrpcServletRegistration.class)
	static class MissingGrpcServletRegistrationBean { }
	@ConditionalOnMissingBean(value = GrpcServerFactory.class, ignored = InProcessGrpcServerFactory.class)
	static class MissingGrpcFactoryBean { }
}
```

即：**一旦容器里已经有"网络型"服务器工厂（Servlet 注册或任何非 in-process 的 `GrpcServerFactory`），其他网络配置全部让位**——用户自己注册一个 `GrpcServerFactory` Bean 就能完全接管（框架绝不抢戏）。`@ConditionalOnGrpcServerFactoryEnabled`（`ConditionalOnGrpcServerFactoryEnabled.java:34-40`）则是总开关 `spring.grpc.server.factory.enabled`（默认 true）的缩写。

## 5.3 Netty 配置的两只手：地址与凭据

【源码证据】`NettyGrpcServerConfiguration.java:48-65`：

```java
@Bean
NettyGrpcServerFactory nettyGrpcServerFactory(GrpcServerProperties properties,
		GrpcServiceDiscoverer serviceDiscoverer, GrpcServiceConfigurer serviceConfigurer,
		GrpcServerBuilderCustomizers grpcServerBuilderCustomizers, SslBundles bundles,
		ObjectProvider<GrpcServerFactoryCustomizer> customizers) {
	NettyAddress address = NettyAddress.fromProperties(properties);          // ① 属性 → 地址字符串
	ServerCredentials credentials = ServerCredentials.get(properties.getSsl(), bundles,
			InsecureTrustManagerFactory.INSTANCE);                            // ② SSL bundle → TLS 凭据
	NettyGrpcServerFactory factory = new NettyGrpcServerFactory(address.toString(),
			grpcServerBuilderCustomizers.forFactory(), credentials.keyManagerFactory(),
			credentials.trustManagerFactory(), credentials.clientAuth());
	customizers.orderedStream().forEach((customizer) -> customizer.customize(factory));   // ③ 工厂级定制
	serviceDiscoverer.findServices()                                          // ④ 发现服务
		.stream()
		.map((spec) -> serviceConfigurer.configure(spec, factory))            // ⑤ 缠绕拦截器
		.forEach(factory::addService);                                        // ⑥ 收货
	return factory;
}
```

这正是第二、三、四章三条线在 Boot 侧的"接线图"：**发现（②⑥）→ 配置（⑤）→ 制造（①②③）**。

**地址推导**（`NettyAddress.java:51-67`）：`spring.grpc.server.address`/`port` 与 `spring.grpc.server.netty.domain-socket-path` **互斥校验**（同时设置抛 `MutuallyExclusiveConfigurationPropertiesException`）；给了 address/port 走 TCP（缺省 `*:9090`，`tcpAddress()` `:69-73` 用 `GrpcUtils.DEFAULT_PORT`），给了 domain-socket-path 走 `unix:` 前缀。

**凭据推导**（`ServerCredentials.get(...)`）：复用 Boot 3.1 引入的 **SSL Bundle** 机制——`spring.grpc.server.ssl.bundle=<bundle 名>` 一个属性即可挂上一整套 keystore/truststore，`clientAuth` 属性控制 mTLS。

## 5.4 Servlet 传输：gRPC 住进 Tomcat

**白话**：web 应用（尤其部署在共享 Servlet 容器的场景）可以不开独立端口，把 gRPC 服务以 `GrpcServlet` 的形式注册进 Servlet 容器，复用容器的 9090 之外的主端口（HTTP/2/h2c）。

【源码证据】前提校验与注册（`ServletGrpcServerConfiguration.java:48-60`）：

```java
if (properties.getServlet().isValidateHttp2()
		&& !Boolean.TRUE.equals(environment.getProperty("server.http2.enabled", Boolean.class))) {
	throw new FailureAnalyzedException(
			"Configuration property 'server.http2.enabled' should be set to true for gRPC support", ...);
}
return new GrpcServletRegistration(serviceDiscoverer, serviceConfigurer, grpcServerBuilderCustomizers::apply);
```

`GrpcServletRegistration`（`GrpcServletRegistration.java:74-92`）在**构造器里**就完成了服务发现与 `ServletServerBuilder` 装配，并为每个服务生成 URL 映射 `"/" + 服务名 + "/*"`（`:83`）——gRPC 的 HTTP/2 路径就是 `/服务全名/方法名`，所以映射按服务名前缀收口。注意 `server.http2.enabled=true` 是硬前提（gRPC 需要 HTTP/2），可用 `spring.grpc.server.servlet.validate-http2=false` 跳过校验。

## 5.5 `GrpcServerProperties` 全景与内置反射服务

属性前缀 `spring.grpc.server`（`GrpcServerProperties.java:39-503`），关键默认值：

| 属性 | 默认值 | 源码位置 |
|---|---|---|
| `port` / `address` | null（由 NettyAddress 兜底为 `*:9090`） | `:45-50` |
| `shutdown.grace-period` | **30s** | `:122` |
| `inbound.message.max-size` | **4 MiB**（4194304） | `:161` |
| `inbound.metadata.max-size` | **8 KiB** | `:183` |
| `inprocess.name` | null（设了才启动 in-process 服务器） | `:205` |
| `keepalive.time` / `timeout` | 2h / 20s | `:227/:235` |
| `keepalive.permit.time`（客户端 permit） | 5min | `:275` |
| `keepalive.connection.max-idle/max-age` | null（不限制） | `:312/:320` |
| `ssl.client-auth` / `ssl.bundle` | NONE / null | `:370/:375` |
| `netty.transport`（TCP/DOMAIN_SOCKET）/ `domain-socket-path` | 自动推断 | `:427-432` |
| `servlet.enabled` / `validate-http2` | true / true | `:477/:483` |

这些默认值如何落到 Builder？由 `PropertiesServerBuilderCustomizer`（自动配置时排在用户定制器**之前**）统一应用；`CompressorRegistry`/`DecompressorRegistry`/`GrpcServerExecutorProvider`（自定义执行器，安全模块用它包一层 `DelegatingSecurityContextExecutor`）也在 `GrpcServerBuilderCustomizers` 里按 Bean 有则必装（`GrpcServerBuilderCustomizers.java:60-62`）。

**反射服务默认开启**（`GrpcServerServicesAutoConfiguration.java:45-57`）：classpath 有 `ProtoReflectionServiceV1` 且 `spring.grpc.server.reflection.enabled`（默认 true）时，自动注册 gRPC 标准反射服务——grpcurl/postman 等工具可直接内省。该自动配置 `@AutoConfiguration(before = GrpcServerAutoConfiguration.class)`，保证反射服务这个"Bean 形态的服务"也能被 Discoverer 发现。

**本章小结**：Boot 服务端装配是一棵条件决策树——`@ConditionalOnBean(BindableService)` 决定"装不装"，`MissingNetworkGrpcServerCondition` 决定"哪条传输装"，用户自注册 `GrpcServerFactory` Bean 即可整树接管。默认值 4 MiB 消息上限 / 30s 停机宽限 / 反射服务开启，是三个最常被面试问到的"为什么我什么都没配它就能跑"。

---

# 六、客户端注册内核：从 `@ImportGrpcClients` 到可注入的 Stub（spring-grpc-core）

> 本章对应源码：`spring-grpc-core/src/main/java/org/springframework/grpc/client/`。protobuf 编译器生成的 Stub（`XxxGrpc.XxxBlockingStub` 等）只是普通类，本章讲它们如何"一键"变成 Spring Bean。

## 6.1 `@ImportGrpcClients`：注解驱动的批量注册

**白话**：`@ImportGrpcClients` 是 `@Import` 的语义升级版——导入的不是配置类，而是"一个注册器"，注册器负责把指定类型（或指定包下扫到的所有 Stub 类型）注册成 Bean 定义。它贴在 `@SpringBootApplication` 类上通常不写任何参数。

【源码证据】`client/ImportGrpcClients.java:34-92`（节选）：

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
@Import(AnnotationGrpcClientRegistrar.class)      // ← 真正干活的注册器
@Repeatable(ImportGrpcClients.Container.class)    // ← 可重复：同一启动类可多次声明不同 target
public @interface ImportGrpcClients {

	String target() default "default";            // 逻辑目标名（不是 URL！见 6.3）

	String prefix() default "";                   // Bean 名前缀（同类型多客户端时消歧）

	@AliasFor("value")
	Class<?>[] types() default {};                // 显式枚举 Stub 类型

	Class<? extends StubFactory<?>> factory() default UnspecifiedStubFactory.class;

	Class<?>[] basePackageClasses() default {};   // 扫描起点

	String[] basePackages() default {};
}
```

解析侧（`AnnotationGrpcClientRegistrar.java:30-59`）用 `getMergedRepeatableAnnotationAttributes` 把可重复注解合并成 spec 数组，且有一个重要默认值（`:50-52`）：**types 与 basePackages 都为空时，扫描注解所在类自己的包**——这就是"贴在启动类上零参数即可用"的原因。

## 6.2 `GrpcClientFactory.register`：工厂方法 Bean 的精髓

**白话**：注册出来的每个 Stub Bean 定义并不立即实例化——它被注册成"**调用 `GrpcClientFactory.getClient(target, type, factory)` 的工厂方法 Bean**"，且**懒加载**。第一次被注入时才创建 Channel、才连服务器。

【源码证据】`client/GrpcClientFactory.java:225-246`：

```java
public static void register(BeanDefinitionRegistry registry, GrpcClientRegistrationSpec spec) {
	spec = spec.prepare(registry);
	for (Class<?> type : spec.types()) {
		if (GrpcClientFactory.findDefaultFactory(registry, spec.factory(), type) == null) {
			continue;                                   // 没有 StubFactory 认识它 → 跳过
		}
		RootBeanDefinition beanDef = (RootBeanDefinition) BeanDefinitionBuilder.rootBeanDefinition(type)
			.setLazyInit(true)                                                    // 懒加载
			.setFactoryMethodOnBean("getClient", GrpcClientFactory.class.getName())  // 工厂方法
			.addConstructorArgValue(spec.target())
			.addConstructorArgValue(type)
			.addConstructorArgValue(spec.factory())
			.getBeanDefinition();
		beanDef.setTargetType(type);
		String beanName = StringUtils.hasText(spec.prefix()) ? spec.prefix() + type.getSimpleName()
				: StringUtils.uncapitalize(type.getSimpleName());
		if (!registry.containsBeanDefinition(beanName)) {
			registry.registerBeanDefinition(beanName, beanDef);   // 同名去重
		}
	}
}
```

这与 Spring Framework 7 的 `@ImportHttpServices`（HTTP 服务客户端声明式注册）是同款机制：**Bean 定义只是配方，配方指向某个 Bean 上的工厂方法**。运行时链路（`getClient`，`GrpcClientFactory.java:86-91`）一句话讲完：

```java
public <T> T getClient(String target, Class<T> type, Class<?> factory) {
	StubFactory<T> stubs = (StubFactory<T>) findFactory(factory, type);
	T client = stubs.create(() -> channels().createChannel(target, ChannelBuilderOptions.defaults()), type);
	return client;
}
```

`channels()` 取容器里唯一的 `GrpcChannelFactory`（`GrpcClientFactory.java:221-223`）——下一章的主角。

### 6.2.1 扫描如何识别"Stub"

`GrpcClientRegistrationSpec.prepare`（`GrpcClientFactory.java:257-313`）的包扫描用 `ClasspathScanner` + 自定义 `TypeFilter`，判定标准是**继承链上溯**：沿 superclass 一路用 ASM 元数据读到 `io.grpc.stub.AbstractStub` 才放行（`isAbstractStub`，`:274-302`）——不实例化类、不触发静态初始化，兼容 AOT。扫描到的类型还要过 `findDefaultFactory`（`supports` 判定）。

### 6.2.2 `StubFactory` SPI：五种默认工厂 + 反射式 supports

**白话**：同一个 `XxxGrpc` 外层类里住着多个 Stub 内部类（Blocking/BlockingV2/Future/Reactor/Coroutine…），谁该为哪个类型负责？答案是每个工厂一个静态 `supports(Class)` 方法。

【源码证据】默认工厂清单（`GrpcClientFactory.java:69-75`）：

```java
static {
	DEFAULT_FACTORIES.add((Class<? extends StubFactory<?>>) BlockingStubFactory.class);
	DEFAULT_FACTORIES.add((Class<? extends StubFactory<?>>) BlockingV2StubFactory.class);
	DEFAULT_FACTORIES.add((Class<? extends StubFactory<?>>) FutureStubFactory.class);
	DEFAULT_FACTORIES.add((Class<? extends StubFactory<?>>) ReactorStubFactory.class);
	DEFAULT_FACTORIES.add((Class<? extends StubFactory<?>>) SimpleStubFactory.class);
}
```

`supports` 的调用方式很讲究（`GrpcClientFactory.java:179-202`）：**用反射找静态 `supports(Class)` 方法调用，避免为判定而实例化工厂**；没有 `supports` 方法的非默认工厂默认"全支持"（用户自定义工厂的宽容语义）。工厂实例优先取容器里的 `StubFactory` Bean（用户可覆盖），缺省时用 `createBean` 补齐默认五件套（`findFactory`，`:93-119`）；0.9.0 起 `supports` 静态化正是为了这个"先判定、后实例化"的顺序（官方 whats-new 0.9.0 条目）。

**本章小结**：客户端注册内核 = **注解（可重复、可扫描、可消歧）+ ImportBeanDefinitionRegistrar + 懒加载工厂方法 Bean 定义**。它把"protobuf 生成类"与"Spring Bean"两个世界接起来，却只在容器里留下极薄的一层（每类型一个 Bean 定义）；真正的网络资源（Channel）推迟到第一次注入才创建。

---

# 七、Channel 工厂与 Boot 客户端装配（spring-boot-grpc-client）

> 本章对应源码：`spring-grpc-core/.../client/`（DefaultGrpcChannelFactory 等）+ `spring-boot/module/spring-boot-grpc-client/.../autoconfigure/`。

## 7.1 `DefaultGrpcChannelFactory`：建连五步

**白话**：Channel 是 gRPC 客户端的"连接池 + 负载均衡 + 名称解析"总管（通常一个目标一个 Channel、进程内复用）。`DefaultGrpcChannelFactory.createChannel` 按固定五步造它：**解析目标 → 选凭据 → 缠拦截器 → 应用定制器 → build 并登记**。

【源码证据】`client/DefaultGrpcChannelFactory.java:110-126`：

```java
@Override
public ManagedChannel createChannel(String target, ChannelBuilderOptions options) {
	var targetUri = this.targets.getTarget(target);                        // ① 目标解析（VirtualTargets）
	T builder = newChannelBuilder(targetUri, this.credentials.getChannelCredentials(target));  // ② 凭据
	// ③ 拦截器：全局 + 本次的
	this.interceptorsConfigurer.configureInterceptors(builder, options.interceptors(),
			options.mergeWithGlobalInterceptors(), this);
	// ④ 定制器：全局定制器 + 本次专属定制器
	this.globalCustomizers.forEach((c) -> c.customize(target, builder));
	var customizer = options.<T>customizer();
	if (customizer != null) {
		customizer.customize(target, builder);
	}
	var channel = builder.build();
	this.channels.add(new ManagedChannelWithShutdown(channel, options.shutdownGracePeriod()));  // ⑤ 登记
	return channel;
}
```

`supports(String target)`（`DefaultGrpcChannelFactory.java:88-94`）声明了它的管辖范围：**凡以 `in-process:` 开头的目标不归我管**——这是 Composite 工厂路由的依据（见 7.3）。

### 7.1.1 有序停机：每个 Channel 一个宽限期

**白话**：客户端 Channel 的关闭比服务器更讲究——应用停机时在途 RPC 要给足时间，且多个 Channel 的宽限期要**共享总预算**。`DefaultGrpcChannelFactory` 实现 `DisposableBean`，先全员 `shutdown`，再按宽限期从小到大串行 `awaitTermination`（每人只等"自己额度减去已耗时间"），最后全员 `shutdownNow` 兜底。

【源码证据】`DefaultGrpcChannelFactory.java:154-179`：

```java
@Override
public void destroy() {
	this.channels.stream().map(ManagedChannelWithShutdown::channel).forEach(ManagedChannel::shutdown);
	this.channels.sort(Comparator.comparingLong((t) -> t.shutdownGracePeriod().toMillis()));   // 短限期优先
	try {
		long start = System.currentTimeMillis();
		this.channels.forEach((channelWithShutdown) -> {
			var channel = channelWithShutdown.channel();
			var gracePeriod = channelWithShutdown.shutdownGracePeriod();
			if (!channel.isTerminated()) {
				long totalTimeWaitedSinceStart = System.currentTimeMillis() - start;
				long gracePeriodRemaining = gracePeriod.toMillis() - totalTimeWaitedSinceStart;
				this.awaitTermination(channel, gracePeriodRemaining);   // 只等剩余额度
			}
		});
	}
	finally {
		this.channels.stream().map(ManagedChannelWithShutdown::channel)
			.forEach((channel) -> { if (!channel.isTerminated()) channel.shutdownNow(); });
	}
}
```

## 7.2 InProcess 客户端工厂与 Composite 路由

`InProcessGrpcChannelFactory`（`client/InProcessGrpcChannelFactory.java`）与服务器侧镜像：`supports` **只**认 `in-process:` 前缀。`CompositeGrpcChannelFactory`（`client/CompositeGrpcChannelFactory.java:59-66`）则把多个工厂串成责任链：

```java
@Override
public ManagedChannel createChannel(final String target, ChannelBuilderOptions options) {
	return this.channelFactories.stream()
		.filter((cf) -> cf.supports(target))
		.findFirst()
		.orElseThrow(() -> new IllegalStateException("No grpc channel factory found that supports target : " + target))
		.createChannel(target, options);
}
```

Boot 侧何时启用 Composite？（`CompositeChannelFactoryAutoConfiguration.java:55-70`）`MultipleNonPrimaryChannelFactoriesCondition` 是 `NoneNestedConditions`——**容器里没有任何 `GrpcChannelFactory`、或只有一个候选时都不装**，只有"多个工厂共存"（典型：Netty + in-process 测试通道）时才装一个 `@Primary` 的 Composite 做路由。这个设计让简单应用拿到的就是裸工厂，复杂应用才多一层路由。

## 7.3 命名通道：`PropertiesVirtualTargets`

**白话**：`@ImportGrpcClients(target = "billing")` 里的 `billing` 是**逻辑名**。Boot 侧把它翻译成真实地址的规则表就是 `spring.grpc.client.channel.*` 属性——这正是"客户端不写 URL"的全部秘密。

【源码证据】`PropertiesVirtualTargets.java:36-57`：

```java
@Override
public String getTarget(String target) {
	Channel channel = this.properties.getChannel().get(target);        // ① 命名通道表
	if (channel != null) {
		return clean(this.propertyResolver.resolvePlaceholders(channel.getTarget()));  // ② 占位符解析
	}
	if ("default".equals(target)) {
		return clean(Channel.DEFAULT_TARGET);                          // ③ default → static://localhost:9090
	}
	target = this.propertyResolver.resolvePlaceholders(target);        // ④ 目标本身可含 ${...}
	if (target.contains(":/") || target.startsWith("unix:")) {
		return clean(target);
	}
	return target;
}

private String clean(String target) {
	if (target.startsWith("static:") || target.startsWith("tcp:")) {   // ⑤ 清洗掉 Boot 风格前缀
		String withoutScheme = target.substring(target.indexOf(":") + 1);
		return withoutScheme.replaceFirst("/*", "");
	}
	return target;
}
```

解读：`spring.grpc.client.channel.billing.target=dns:///billing.default.svc.cluster.local:9090`（K8s 场景）或 `static://localhost:9090`；core 侧 `VirtualTargets.DEFAULT`（`client/VirtualTargets.java:25`）只做一件事——把逻辑名 `"default"` 映射到 `localhost:9090`。`clean` 的存在是因为 grpc-java 名称解析器需要裸的 `host:port`，而 Boot 属性习惯带 `static:`/`tcp:` scheme。

## 7.4 Boot 客户端装配与属性全景

【源码证据】`GrpcClientAutoConfiguration.java:49-84`：`@ConditionalOnClass({AbstractStub.class, GrpcChannelBuilderCustomizer.class})` + `spring.grpc.client.enabled`（默认 true）入口，装配 `ClientInterceptorsConfigurer`（全局拦截器收集器）、`PropertiesChannelCredentialsProvider`（SslBundles 驱动的凭据）、`PropertiesGrpcChannelBuilderCustomizer`（属性→Builder）；`NettyGrpcClientConfiguration`（`NettyGrpcClientConfiguration.java:46-58`）创建 `NettyGrpcChannelFactory` 并注入 `setVirtualTargets(new PropertiesVirtualTargets(environment, properties))` 与凭据；Kotlin 协程 classpath 出现时自动补 `CoroutineStubFactory`（`GrpcClientAutoConfiguration.java:86-96`）。

属性前缀 `spring.grpc.client`（`GrpcClientProperties.java:40-393`），核心结构是**按通道名的 Map**（`:46`），每个通道的默认值：

| 属性 | 默认值 | 源码位置 |
|---|---|---|
| `spring.grpc.client.channel.*.target` | `static://localhost:9090` | `:57` |
| `inbound.message.max-size` / `metadata.max-size` | 4 MiB / 8 KiB | `:177/:200` |
| `default.deadline` | null（不设超时） | `:224` |
| `default.load-balancing-policy` | **round_robin** | `:229` |
| `idle.timeout` | 20s | `:259` |
| `keepalive.time` / `timeout` | 5min / 20s | `:283/:290` |
| `health.enabled` / `health.service-name` | false / null | `:332/:337` |
| `ssl.bundle` | null（设了自动启用 TLS） | `:371` |

`loadBalancingPolicy=round_robin` 值得一提：gRPC 客户端默认策略本是 `pick_first`，Spring gRPC 默认改成了 `round_robin`（多后端场景更符合微服务直觉），它以 `defaultServiceConfig` 的形式通过 `GrpcChannelBuilderCustomizers` 应用（`GrpcChannelBuilderCustomizers.java:73,91-93`）。K8s headless service + DNS 解析 + round_robin，就是 Spring gRPC 版的"客户端负载均衡"；要接 Nacos/Consul 等注册中心，则注册自定义名称解析器/工厂（见第十三章）。

## 7.5 客户端拦截器顺序之谜：为什么 reverse

**白话**：gRPC 客户端拦截器 API 的语义是"**后注册的先执行**"（`ManagedChannelBuilder.intercept` 是前插）。Spring 世界的直觉是"@Order 小的先执行"。`ClientInterceptorsConfigurer` 的处理是：先按直觉排序，再 reverse 成 gRPC 需要的顺序。

【源码证据】`client/ClientInterceptorsConfigurer.java:54-68`：

```java
protected void configureInterceptors(ManagedChannelBuilder<?> builder, List<ClientInterceptor> interceptors,
		boolean mergeWithGlobalInterceptors, GrpcChannelFactory factory) {
	// Add global interceptors first
	List<ClientInterceptor> allInterceptors = new ArrayList<>(this.globalInterceptors);   // ① 全局
	// Add specific interceptors
	allInterceptors.addAll(interceptors);                                                 // ② 专属
	// Filter all interceptors
	allInterceptors.removeIf(interceptor -> !factory.supports(interceptor));              // ③ 过滤
	if (mergeWithGlobalInterceptors) {
		ApplicationContextBeanLookupUtils.sortBeansIncludingOrderAnnotation(...);         // ④ 可选融合排序
	}
	Collections.reverse(allInterceptors);                                                 // ⑤ 逆转成 gRPC 顺序
	builder.intercept(allInterceptors);
}
```

全局拦截器同样来自 `@GlobalClientInterceptor`（`client/GlobalClientInterceptor.java:41`）+ `@Order`。对比服务端：`ServerInterceptors.interceptForward` 是"列表顺序即执行顺序"，所以服务端**不需要 reverse**（对照 `DefaultGrpcServiceConfigurer.java:79/93`）——同一个"@Order 直觉"在两端落地方式相反，这是读源码时最容易迷糊、也最能体现封装价值的一处。

## 7.6 默认 deadline 与凭据 Provider

两个小而美的补充：

- **默认超时**：`spring.grpc.client.channel.*.default.deadline` 由 `DefaultDeadlineSetupClientInterceptor`（`client/interceptor/DefaultDeadlineSetupClientInterceptor.java:35-53`）落地——`callOptions.getDeadline() == null` 时补 `withDeadlineAfter`（`:49`），已有 deadline 的调用不覆盖；0.6.0 引入（#136）；
- **凭据策略**：`ChannelCredentialsProvider`（`client/ChannelCredentialsProvider.java:29-32`）是一个 `path → ChannelCredentials` 函数，默认 `INSECURE`；Boot 侧 `PropertiesChannelCredentialsProvider` 按"命名通道的 ssl.bundle"查找 Boot `SslBundles` 生成 TLS 凭据，通道级隔离。

**本章小结**：客户端装配 = **Composite 工厂路由 + VirtualTargets 命名通道 + 凭据 Provider + 拦截器/定制器双通道注入**。记住三个默认值：`default → static://localhost:9090`、`load-balancing-policy=round_robin`、4 MiB 消息上限；记住一个细节：客户端拦截器列表最终要 reverse。

---

# 八、异常处理：拦截器式统一出口与 `@GrpcAdvice`

> 本章对应源码：`spring-grpc-core/src/main/java/org/springframework/grpc/server/exception/` 与 `.../server/advice/`。

## 8.1 为什么 gRPC 异常处理"更难"

**白话**：gRPC 的错误码（`Status`）要显式 `close` 到流上；业务代码抛出的普通异常若没人管，客户端只会看到一个裸 `UNKNOWN`。更麻烦的是异常可能抛在**消息回调**（`Listener.onMessage/onHalfClose`）里——那里不在"调 service 方法"的栈上。Spring gRPC 的解法是一个全局拦截器 + 两层包装。

【源码证据】`server/exception/GrpcExceptionHandlerInterceptor.java:72-90`（拦截入口）：

```java
@Override
public <ReqT, RespT> Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,
		ServerCallHandler<ReqT, RespT> next) {
	Listener<ReqT> listener;
	FallbackHandler fallbackHandler = new FallbackHandler(this.exceptionHandler);
	GrpcExceptionHandledServerCall<ReqT, RespT> exceptionHandledServerCall =
			new GrpcExceptionHandledServerCall<>(call, fallbackHandler);   // 包装 ServerCall：close 时兜异常
	try {
		listener = next.startCall(exceptionHandledServerCall, headers);
	}
	catch (Throwable t) {                                                 // 启动阶段就抛？
		StatusException statusEx = fallbackHandler.handleException(t);
		exceptionHandledServerCall.close(statusEx != null ? statusEx.getStatus() : Status.fromThrowable(t), ...);
		return new Listener<>() {};
	}
	return new ExceptionHandlerListener<>(listener, exceptionHandledServerCall, fallbackHandler);
}
```

消息回调侧（`ExceptionHandlerListener.handle`，`GrpcExceptionHandlerInterceptor.java:153-171`）：捕获异常后先交给 `GrpcExceptionHandler` 策略换 `StatusException`，再用包装过的 call `close(status, trailers)`；**一旦出现过异常，后续 `onMessage/onHalfClose/onReady` 全部短路**（`:116/:129/:142` 的 `if (this.exception != null) return;`）——半关闭的流上不能再写消息。兜底规则在 `FallbackHandler`（`:175-197`）：策略返回 null 时退化为 `Status.fromThrowable(exception).asException()`（通常 UNKNOWN）。

装配链：Boot 自动配置把它注册为 `@GlobalServerInterceptor`（`GrpcServerAutoConfiguration.java:96-103`），处理策略是容器里**所有** `GrpcExceptionHandler` Bean 组成的 `CompositeGrpcExceptionHandler`——你只要提供自己的 `GrpcExceptionHandler` Bean 就能参与映射。

## 8.2 `@GrpcAdvice`：MVC 式的注解映射

**白话**：像 `@ControllerAdvice + @ExceptionHandler` 一样，你可以写一个 `@GrpcAdvice` 类，里面用 `@GrpcExceptionHandler(IllegalArgumentException.class)` 标注方法返回 `Status`/`StatusException`/`StatusRuntimeException`/`Throwable`。1.1 系列新增。

【源码证据】注解定义（`server/advice/GrpcAdvice.java:38`、`server/advice/GrpcExceptionHandler.java:36`）与解析执行（`server/advice/GrpcAdviceExceptionHandler.java:60-80`）：

```java
@Override
public @Nullable StatusException handleException(Throwable exception) {
	try {
		Object mappedReturnType = handleThrownException(exception);     // 反射调用匹配的 advice 方法
		if (mappedReturnType == null) {
			return null;                                                // 没有匹配的映射 → 让给下一个策略
		}
		Status status = resolveStatus(mappedReturnType);                // Status / Throwable 两种返回
		Metadata metadata = resolveMetadata(mappedReturnType);          // StatusException/RuntimeException 可带 trailers
		return status.asException(metadata);
	}
	catch (Throwable errorWhileResolving) {
		...
		return Status.INTERNAL.withCause(errorWhileResolving)
			.withDescription("There was a server error trying to handle an exception")
			.asException();
	}
}
```

`GrpcAdviceDiscoverer`（`InitializingBean`，扫描容器里 `@GrpcAdvice` Bean 及其 `@GrpcExceptionHandler` 方法）+ `GrpcExceptionHandlerMethodResolver`（异常类型→方法的映射与匹配）两个协作类负责映射表；Boot 侧在"容器里存在 `@GrpcAdvice` Bean"时才装配三件套（`GrpcServerAutoConfiguration.java:112-136`，`@ConditionalOnBean(annotation = GrpcAdvice.class)`）——零注解应用不多付一分钱。

**本章小结**：异常处理是"**全局拦截器兜底 + 策略组合（Composite）+ @GrpcAdvice 声明式映射**"三层：拦截器保证"任何异常必有 Status 出口"，Composite 保证"用户策略优先"，@GrpcAdvice 保证"MVC 手感的写法"。这层设计把 gRPC 最容易漏的"错误码卫生"变成了默认行为。

---

# 九、安全：gRPC 上的 Spring Security

> 本章对应源码：`spring-grpc-core/.../server/security/`（20 类，0.3.0 引入、0.5.0 补全 OAuth2）+ Boot 侧 `spring-boot-grpc-server/.../autoconfigure/security/`。

## 9.1 服务端：把 SecurityFilterChain 思想搬进拦截器

**白话**：`GrpcSecurity` 是 Spring Security `HttpSecurity` 的 gRPC 版——同一个 `AbstractConfiguredSecurityBuilder` 基类、同一个 `AuthenticationManagerBuilder`、同一个 `AuthorizationManager` 契约，只是"请求"从 `HttpServletRequest` 换成了 `CallContext`（headers + attributes + 方法描述符）。

【源码证据】DSL 骨架（`server/security/GrpcSecurity.java:137-158`）：

```java
public GrpcSecurity preauth(Customizer<PreAuthConfigurer<GrpcSecurity>> customizer) throws Exception {
	customizer.customize(getOrApply(new PreAuthConfigurer<>(getAuthenticationRegistry(), getContext())));
	authenticationExtractor(new SslContextPreAuthenticationExtractor());   // mTLS 证书即身份
	return this;
}

public GrpcSecurity httpBasic(Customizer<HttpBasicConfigurer<GrpcSecurity>> customizer) throws Exception {
	customizer.customize(getOrApply(new HttpBasicConfigurer<>(getAuthenticationRegistry(), getContext())));
	authenticationExtractor(new HttpBasicAuthenticationExtractor());       // Authorization: Basic ...
	return this;
}

public GrpcSecurity authorizeRequests(Customizer<RequestMapperConfigurer> customizer) throws Exception { ... }

public GrpcSecurity oauth2ResourceServer(Customizer<OAuth2ResourceServerConfigurer> customizer) throws Exception {
	customizer.customize(getOrApply(new OAuth2ResourceServerConfigurer(getContext())));
	authenticationExtractor(new BearerTokenAuthenticationExtractor());     // Authorization: Bearer ...
	return this;
}
```

`performBuild`（`GrpcSecurity.java:101-121`）产出最终的 `AuthenticationProcessInterceptor`：若容器有 `ObservationRegistry` 且非 NOOP，`AuthenticationManager` 还会包一层 `ObservationAuthenticationManager`（`:109-111`）——认证过程也可观测。

## 9.2 `AuthenticationProcessInterceptor`：一次调用的完整安检

【源码证据】`server/security/AuthenticationProcessInterceptor.java:69-110`：

```java
@Override
public <ReqT, RespT> Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers,
		ServerCallHandler<ReqT, RespT> next) {
	SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
	Authentication user = this.extractor.extract(headers, call.getAttributes(), call.getMethodDescriptor());
	if (user != null) {
		user = this.authenticationManager.authenticate(user);            // ① 认证
		securityContext.setAuthentication(user);
	}
	if (this.authorizationManager != null) {
		CallContext context = new CallContext(headers, call.getAttributes(), call.getMethodDescriptor());
		if (user == null) {
			user = new AnonymousAuthenticationToken("anonymous", "anonymous",
					AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));   // ② 匿名兜底
		}
		AuthorizationResult authResult = this.authorizationManager.authorize(() -> authentication, context);
		if (authResult == null || !authResult.isGranted()) {
			if (user instanceof AnonymousAuthenticationToken) {
				throw new BadCredentialsException("not authenticated");  // 401 语义
			}
			throw new AccessDeniedException("not allowed");              // 403 语义
		}
	}
	else if (user == null || !user.isAuthenticated()) {
		throw new BadCredentialsException("not authenticated");
	}
	// Only if successfully authenticated and authorized do we set the context for the call
	SecurityContextHolder.setContext(securityContext);
	try {
		Context context = Context.current().withValue(GrpcSecurity.SECURITY_CONTEXT_KEY, securityContext);
		return new SecurityContextHandlerListener<ReqT, RespT>(Contexts.interceptCall(context, call, headers, next),
				securityContext);                                        // ③ 绑定进 gRPC Context
	}
	finally {
		SecurityContextHolder.clearContext();                            // ④ 线程上下文及时清理
	}
}
```

四个值得咀嚼的细节：

1. **顺序极前**：`getOrder() = GrpcSecurity.CONTEXT_FILTER_ORDER - 10 = -10`（`:56-59`），在观测拦截器（Order 0）之前——先安检后计量；
2. **认证/授权异常本身也是异常**：抛出的 `BadCredentialsException`/`AccessDeniedException` 会被第八章的全局异常拦截器接住，`SecurityGrpcExceptionHandler`（Boot 侧自动注册，`GrpcServerSecurityAutoConfiguration.java:70-77`）把它们翻成 gRPC `UNAUTHENTICATED`/`PERMISSION_DENIED`——两个体系在这里咬合；
3. **双通道传播**：`SecurityContextHolder`（ThreadLocal，给同步 stub）+ `GrpcSecurity.SECURITY_CONTEXT_KEY`（`io.grpc.Context`，`GrpcSecurity.java:70`，给流式/异步/协程）；
4. **finally 清理**：拦截器线程与业务执行线程可能不同，ThreadLocal 不留下一位垃圾。

## 9.3 Boot 侧装配与 Servlet 场景

【源码证据】`GrpcServerSecurityAutoConfiguration.java:60-127`：

- `ExceptionHandlerConfiguration`：注册 `SecurityGrpcExceptionHandler`（异常翻译，见 9.2）；
- `GrpcNativeSecurityConfigurerConfiguration`：`@ConditionalOnBean(GrpcServerFactory.class)` + `@EnableGlobalAuthentication`，创建 `GrpcSecurity` DSL Bean（`:84-94`），其 `AuthenticationManagerBuilder` 的**父管理器指向 Boot 全局 `AuthenticationConfiguration`**（`:90`）——gRPC 安全与 Web 安全共享用户体系；
- `GrpcServletSecurityConfigurerConfiguration`：`@ConditionalOnBean({GrpcServletRegistration.class, SecurityFilterChain.class})`——**Servlet 传输时 gRPC 请求走的是 HTTP Filter 链**，此时不再需要认证拦截器，只补一个 `SecurityContextServerInterceptor`（把 Filter 写好的 SecurityContext 转入 gRPC Context）和 `DelegatingSecurityContextExecutor` 包装的执行器（`:100-111`），保证业务线程也能看到调用方身份；
- Kotlin 协程 classpath 出现时自动补 `CoroutineSecurityContextInterceptor`（`:114-125`）。

## 9.4 客户端：三个 token 拦截器

`client/interceptor/security/` 提供随身的"通行证"：

- `BasicAuthenticationInterceptor`：`Authorization: Basic base64(user:pass)`；
- `BearerTokenAuthenticationInterceptor`（`BearerTokenAuthenticationInterceptor.java:35-63`）：构造时给一个 `TokenSupplier`，每次调用 `start` 时把 `Bearer <token>` 塞进 headers（`:61-62`，`GrpcHeaders.AUTHORIZATION_KEY` 定义在 `internal/GrpcHeaders.java:43`）；
- `TokenSupplier` 两个内置实现：`ClientCredentialsTokenSupplier`（机器对机器，向授权服务器拿 token）与 `TokenRelayTokenSupplier`（`TokenRelayTokenSupplier.java:36-63`，**中继当前用户的 OAuth2 token**：从 `SecurityContextHolder` 取 `OAuth2AuthenticationToken`，经 `OAuth2AuthorizedClientManager.authorize` 刷新/获取后透传，`:45-60`）。

这些不是自动配置——按官方文档（client.adoc "HTTP Headers"/"OAuth2 Clients" 节）以 `@GlobalClientInterceptor` Bean 或通道专属拦截器方式装配，与第九章服务端提取器一一对应。

**本章小结**：Spring gRPC 的安全层是"**Spring Security 语义的 gRPC 方言翻译**"：DSL 同构、认证授权同契、异常翻译互通、上下文双通道（ThreadLocal + gRPC Context）、Servlet 场景直接复用 Filter 链。客户端侧则用轻量拦截器把 OAuth2/Bearer 语义带出进程。

---

# 十、可观测、健康、测试与 AOT

## 10.1 观测：Micrometer gRPC Binder 的自动接线

**白话**：Trace/Metrics 不是 spring-grpc 自己实现——Micrometer 1.13+ 把 `ObservationGrpcClientInterceptor`/`ObservationGrpcServerInterceptor` 收进了主仓库（`io.micrometer.core.instrument.binder.grpc`），Spring Boot 4.1 的 gRPC 模块只负责"有 ObservationRegistry 就自动挂上"。

【源码证据】服务端（`GrpcServerObservationAutoConfiguration.java:47-81`）：

```java
@AutoConfiguration(afterName = "...boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration")
@ConditionalOnClass({ BindableService.class, GrpcServerFactory.class, ObservationRegistry.class,
		ObservationGrpcServerInterceptor.class })
@ConditionalOnBean(ObservationRegistry.class)
@ConditionalOnBooleanProperty(name = "spring.grpc.server.observation.enabled", matchIfMissing = true)
public final class GrpcServerObservationAutoConfiguration {

	@Bean
	@Order(0)
	@GlobalServerInterceptor
	@ConditionalOnMissingBean
	ObservationGrpcServerInterceptor grpcServerObservationInterceptor(ObservationRegistry observationRegistry,
			ObjectProvider<GrpcServerObservationConvention> customConvention) { ... }
	// Kotlin 协程场景再补 ObservationCoroutineContextServerInterceptor（Order 10）
}
```

`@Order(0)` 让观测在安全拦截器（Order -10）**之后**执行——不被拒绝的请求不产生 trace；自定义命名/标签通过注入 `GrpcServerObservationConvention`/`GrpcClientObservationConvention` Bean 完成（`customConvention.ifAvailable(interceptor::setCustomConvention)`）。客户端对称（`GrpcClientObservationAutoConfiguration.java:42-61`，`spring.grpc.client.observation.enabled` 开关）。

## 10.2 健康：gRPC Health 协议桥接 Boot 健康体系

**白话**：gRPC 有标准健康检查协议（`grpc.health.v1`，`HealthStatusManager` 提供 `Check`/`Watch` 服务）。Boot 侧把它变成 `HealthContributorRegistry`（Boot 4 的健康指标注册表）的下游：**聚合 Boot 健康指标 → 映射成 gRPC ServingStatus**。

【源码证据】装配（`GrpcServerHealthAutoConfiguration.java:62-79`）：

```java
@Bean(destroyMethod = "enterTerminalState")      // 容器销毁 → 健康服务进入终态（NOT_SERVING）
@ConditionalOnMissingBean
HealthStatusManager grpcServerHealthStatusManager() {
	return new HealthStatusManager();
}

@Bean
BindableService grpcServerHealthService(HealthStatusManager healthStatusManager) {
	return healthStatusManager.getHealthService();   // 又是"Bean 即服务"
}
```

聚合逻辑（`GrpcServerHealth.java:68-94`）：整体状态（服务名 `""`）+ 每个受管服务（`HealthCheckedGrpcComponents` 按 include/exclude 圈定）分别聚合（`StatusAggregator`）并经 `StatusMapper` 翻成 `ServingStatus`；`GrpcServerHealthSchedulerAutoConfiguration` 提供定时刷新（避免每次 `Check` 都实时算所有指标）。

## 10.3 测试：`@AutoConfigureTestGrpcTransport`

**白话**：测试不想占端口、不想等 TCP 握手——用 in-process 传输同时替换服务器工厂与客户端通道工厂，测试内外用同一个进程直连。

【源码证据】`spring-boot-grpc-test/.../TestGrpcTransportAutoConfiguration.java:58-104`：

```java
@AutoConfiguration(before = { GrpcServerAutoConfiguration.class, GrpcClientAutoConfiguration.class })
@ConditionalOnClass({ InProcessServerBuilder.class, InProcessGrpcServerFactory.class })
public final class TestGrpcTransportAutoConfiguration {

	private static final String address = InProcessServerBuilder.generateName();  // 测试类级唯一名
	...
	@Bean
	@Order(Ordered.HIGHEST_PRECEDENCE)
	TestGrpcServerFactory testGrpcServerFactory(...) { ... }         // 抢在真实工厂之前

	@Bean
	TestGrpcChannelFactory testGrpcChannelFactory(ClientInterceptorsConfigurer interceptorsConfigurer) { ... }
}
```

`@AutoConfigureTestGrpcTransport`（测试切面注解）+ `@LocalGrpcServerPort` 注入实际端口信息（`GrpcServerPortInfoAutoConfiguration`）。因为 `before` 于真实自动配置且 `TestGrpcServerFactory` 高优先级，`MissingNetworkGrpcServerCondition`/`CompositeChannelFactoryAutoConfiguration` 的既有规则自动让位——**测试装配复用生产装配的全部条件体系**，这是 Boot 4 模块化设计（test 模块独立）的红利。

## 10.4 AOT：`ClientBeanRegistrationsAotProcessor`

**白话**：GraalVM Native 下 protobuf 消息与 Stub 靠反射构造，必须提前登记 hints。该处理器在 AOT 阶段扫描所有 Stub Bean，给它们的**外层类、消息类型（含 Builder）、工厂类型**注册反射 hints，并给扫描场景补资源 hints。

【源码证据】`client/aot/ClientBeanRegistrationsAotProcessor.java:51-78`：

```java
public BeanFactoryInitializationAotContribution processAheadOfTime(ConfigurableListableBeanFactory beanFactory) {
	Set<Type> registrations = new HashSet<>();
	Set<Class<?>> resources = new HashSet<>();
	for (String beanName : beanFactory.getBeanDefinitionNames()) {
		RegisteredBean registeredBean = RegisteredBean.of(beanFactory, beanName);
		if (AbstractStub.class.isAssignableFrom(registeredBean.getBeanClass())) {
			Class<?> type = registeredBean.getBeanClass().getEnclosingClass();   // Stub 的外层类 XxxGrpc
			if (type != null) {
				registrations.add(type);
				resources.add(registeredBean.getBeanClass());
			}
			registrations.addAll(findMessageTypes(registeredBean.getBeanClass()));  // 消息 + Builder
		}
	}
	...
}
```

**本章小结**：四个横向能力展示了同一条设计公式的四次重复——**"能力本体交给专业库（Micrometer/grpc-java/Boot Health/protobuf），Spring gRPC 只做'接线 Bean + @Order 编排 + 条件装配'"**。

---

# 十一、配置大全：`spring.grpc.*` 属性速查

服务端（`spring.grpc.server.*`，Boot `GrpcServerProperties`）：

| 属性 | 默认 | 说明 |
|---|---|---|
| `enabled` | true | 服务端总开关 |
| `port` / `address` | null → `*:9090` | 监听端口/地址 |
| `shutdown.grace-period` | 30s | 优雅停机宽限（负数无限等） |
| `inbound.message.max-size` | 4MB | 入站消息上限 |
| `inbound.metadata.max-size` | 8KB | 入站元数据上限 |
| `keepalive.time` / `timeout` | 2h / 20s | 服务端 keepalive ping 节奏 |
| `keepalive.permit.time` / `permit.without-calls` | 5min / false | 允许客户端的 ping 下限 |
| `keepalive.connection.max-idle/max-age/grace-period` | null/null/30s | 连接回收 |
| `ssl.enabled` / `ssl.bundle` / `ssl.client-auth` / `ssl.secure` | 自动/none/NONE/true | TLS（接 Boot SSL Bundles） |
| `netty.transport` / `netty.domain-socket-path` | 自动推断 | TCP 或 DOMAIN_SOCKET |
| `servlet.enabled` / `servlet.validate-http2` | true / true | Servlet 传输与 HTTP/2 前提校验 |
| `inprocess.name` | null | 设置即额外启动一台 in-process 服务器 |
| `factory.enabled` | true | 服务器工厂自动装配总开关 |
| `reflection.enabled` | true | gRPC 反射服务 |
| `observation.enabled` | true | 观测拦截器 |

客户端（`spring.grpc.client.*`，Boot `GrpcClientProperties`，`channel.<名>.*` 可多通道）：

| 属性 | 默认 | 说明 |
|---|---|---|
| `enabled` | true | 客户端总开关 |
| `channel.<名>.target` | `static://localhost:9090` | 目标地址（支持 `${...}`、`dns:///`、`unix:` 等） |
| `channel.<名>.user-agent` / `bypass-certificate-validation` | null / false | UA 与测试用证书旁路 |
| `channel.<名>.default.deadline` | null | 全通道默认超时 |
| `channel.<名>.default.load-balancing-policy` | round_robin | gRPC LB 策略 |
| `channel.<名>.inbound.message/metadata.max-size` | 4MB / 8KB | 入站上限 |
| `channel.<名>.idle.timeout` | 20s | 空闲转 IDLE |
| `channel.<名>.keepalive.time/timeout/without-calls` | 5min / 20s / false | keepalive |
| `channel.<名>.ssl.bundle` | null | 通道级 TLS 凭据 |
| `channel.<名>.health.enabled` / `health.service-name` | false / null | 客户端健康检查（配合 gRPC Health 协议） |

---

# 十二、贯通视图：三条时间线

## 12.1 装配时间线（启动）

```
容器启动
  │
  ├─ BeanDefinitionRegistry 阶段
  │    @ImportGrpcClients → AnnotationGrpcClientRegistrar → GrpcClientFactory.register
  │      （每个 Stub 类型 = 一个懒加载工厂方法 Bean 定义；GrpcClientFactory 自身也注册）
  │
  ├─ Bean 创建阶段
  │    业务服务 Bean（BindableService）……@GrpcService 元注解或 @Bean 方法
  │    拦截器 Bean（@GlobalServerInterceptor / @GlobalClientInterceptor / @Order）
  │    ClientInterceptorsConfigurer.afterPropertiesSet：收集全局客户端拦截器
  │    DefaultGrpcServiceConfigurer.afterPropertiesSet：收集全局服务端拦截器
  │
  ├─ 自动配置阶段（Boot 4.1+）
  │    GrpcClientAutoConfiguration → Netty/InProcess 工厂 +（多工厂时）Composite + @Primary
  │    GrpcServerServicesAutoConfiguration → 反射服务 Bean
  │    GrpcServerAutoConfiguration → Discoverer/Configurer/异常拦截器/@GrpcAdvice 三件套
  │    传输决策树：Servlet? → Shaded? → Netty? →（独立）InProcess
  │      NettyGrpcServerFactory：discover → configure（缠拦截器）→ addService
  │
  └─ SmartLifecycle 阶段（phase=MAX_VALUE）
       GrpcServerLifecycle.start
         → factory.createServer() → server.start()
         → GrpcServerStartedEvent（拿真实端口，注册中心上报点）
         → 非守护 awaitTermination 线程托住 JVM
```

## 12.2 一次 gRPC 调用时间线（unary）

```
业务代码                      spring-grpc                        grpc-java
──────                      ───────────                        ────────
注入 SimpleBlockingStub  ──► 首次注入触发工厂方法
                             GrpcClientFactory.getClient(target,type,factory)
                               └ StubFactory.create(channelSupplier)
                                    └ GrpcChannelFactory.createChannel
                                        ① VirtualTargets: "billing" → "static://10.0.0.7:9090"
                                        ② ChannelCredentialsProvider: bundle → TlsChannelCredentials
                                        ③ ClientInterceptorsConfigurer: 全局+专属 → sort → reverse → intercept
                                        ④ GrpcChannelBuilderCustomizers: defaultServiceConfig(round_robin)…
                                        ⑤ builder.build() → ManagedChannel（登记宽限期）
stub.sayHello(req) ──────►  ClientInterceptor 链（deadline 补默认 → 观测 → Bearer token…）
                             ──► HTTP/2 → 服务器
                                                          ServerInterceptor 链
                                                            AuthenticationProcessInterceptor（-10）
                                                            ObservationGrpcServerInterceptor（0）
                                                            GrpcExceptionHandlerInterceptor
                                                            服务级拦截器… → grpc 实现类方法
业务拿到响应 ◄──────────────  Status OK / 异常经 Composite 策略 → Status.close
```

## 12.3 停机时间线（SIGTERM）

```
容器 close
  ├─ SmartLifecycle.stop（phase 最大者先停）
  │    GrpcServerLifecycle.stopAndReleaseGrpcServer
  │      → GrpcServerShutdownEvent → server.shutdown()（拒新调用）
  │      → awaitTermination(gracePeriod=30s) → shutdownNow() → GrpcServerTerminatedEvent
  ├─ DisposableBean.destroy
  │    DefaultGrpcChannelFactory.destroy
  │      → 全员 shutdown → 按宽限期升序逐个 awaitTermination（共享预算）→ shutdownNow 兜底
  └─ HealthStatusManager.enterTerminalState（健康服务 NOT_SERVING 先行）
```

---

# 十三、附录

## 13.1 扩展点速查表

| 扩展点 | 类型 | 触发时机 | 典型用途 |
|---|---|---|---|
| `BindableService` Bean | 接口 | 自动配置发现 | 注册 gRPC 服务（唯一必做项） |
| `@GrpcService` | 注解 | 服务发现 | 声明服务级拦截器 |
| `@GlobalServerInterceptor` / `@GlobalClientInterceptor` | 注解 | 拦截器收集 | 拦截器全局化（配 `@Order`） |
| `ServerBuilderCustomizer<T>` | Bean | createServer 前 | 定制 NettyServerBuilder（maxInboundMessageSize 等） |
| `GrpcChannelBuilderCustomizer<T>` | Bean | createChannel 前 | 定制 ManagedChannelBuilder |
| `GrpcServerFactoryCustomizer` / `GrpcChannelFactoryCustomizer` | Bean | 工厂创建后 | 改工厂本体（如设 serviceFilter） |
| `GrpcServiceDiscoverer` / `GrpcServiceConfigurer` | Bean 替换 | 自动配置 | 完全接管服务发现/拦截器装配 |
| `GrpcServerFactory` / `GrpcChannelFactory` | Bean 替换 | 自动配置 | 自定义传输（MissingNetwork 条件自动让位） |
| `ChannelCredentialsProvider` | Bean 替换 | createChannel | 按目标动态发凭据 |
| `VirtualTargets` | setter | 工厂创建 | 逻辑名→真实地址 |
| `StubFactory` | Bean | getClient | 自定义 Stub 类型（静态 supports） |
| `GrpcExceptionHandler` | Bean | 异常映射 | 业务异常→Status |
| `@GrpcAdvice` + `@GrpcExceptionHandler` | 注解 | 异常映射 | MVC 风格映射 |
| `GrpcSecurity` DSL | Bean | Boot 装配 | 认证/授权策略 |
| `GrpcServerObservationConvention` 等 | Bean | 观测 | 自定义指标命名/标签 |
| `StatusAggregator` / `StatusMapper` | Bean 替换 | 健康 | gRPC 状态聚合与映射 |
| `ServerServiceDefinitionFilter` / `ServerInterceptorFilter` | 工厂 setter | 装配 | in-process 测试裁剪 |

## 13.2 与系列文档的联动

- **《Spring Cloud Alibaba Nacos.md》**：Nacos 3.x 的客户端↔服务端通信正是 gRPC（双端口 9848/9849）。Spring gRPC 应用接 Nacos 注册发现时，服务发现发生在"名称解析器"层（gRPC `NameResolver` SPI + Nacos 地址列表），本文 7.4 的 `round_robin` 策略即作用在解析结果上。
- **《Spring Cloud LoadBalancer.md》**：LoadBalancer 的抽象在"HTTP 客户端选择实例"层；Spring gRPC 侧对应物是 gRPC 自带的 LB 策略（service config）与自定义 `GrpcChannelFactory`——两者思想同源（客户端负载均衡），实现层不同。
- **《Spring Framework.md》第七章**：`@ImportGrpcClients` 与 `@ImportHttpServices`（Framework 7.0）、`BeanRegistrar` 同属"声明式 Bean 注册"家族；`GrpcServerLifecycle` 与 `WebServerStartStopLifecycle` 同属 SmartLifecycle 家族。
- **《Spring Boot.md》**：`spring-boot-grpc-*` 模块是 Boot 4.1 "模块化自动配置"（autoconfigure 按技术拆分）路线的延续，与 `spring-boot-tx`、`spring-boot-security` 等模块同一世代。

## 13.3 初学者学习路线

1. **先跑通**：clone spring-grpc 仓库的 `samples/grpc-server` 与 `samples/grpc-client`，体会"两个类 + 一个注解"的最小应用；
2. **读装配**：按本文第二章 → 第六章 → 第五章/第七章顺序读 core 的 Discoverer/Configurer 与 Boot 的 AutoConfiguration，画出你自己的决策树；
3. **动手定制**：写一个 `@GlobalServerInterceptor`（`@Order`）+ 一个 `@GrpcAdvice` 类，观察拦截顺序与异常翻译；
4. **进阶传输**：在 K8s 里试 `unix:` domain socket 与 `dns:///` + `round_robin`；
5. **读 grpc-java**：spring-grpc 的所有"魔法"最终都落在 grpc-java 的 `ServerBuilder`/`ManagedChannelBuilder`/`Context`/`Status` 四个原语上——回到源头，才是真正读懂。

> **结语**：Spring gRPC 用 7000 行代码证明了一个观点——当一个底层库的原语足够清晰（Builder/Interceptor/Status/Context），框架集成的正确姿势不是抽象层叠抽象，而是"容器即注册表、注解即装配、Boot 即宿主"。它与 RestRunner 家族（RestClient/HttpServiceProxy）一起，构成了 Spring 官方组合中"同步 RPC"的两条腿。
