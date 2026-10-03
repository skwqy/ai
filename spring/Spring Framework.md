# Spring Framework 深度源码解析（写给初学者的架构全景）

> **本文基于的源码**：`D:\code\3rd\spring-framework`，版本 **7.1.0-SNAPSHOT**（main 分支、合并 7.0.x 后的快照，Git commit `f447f3c310`）。文中所有【源码证据】的文件路径与行号均为对该快照实际读取所得。
>
> **版本取舍说明**：Spring 核心容器（spring-core/beans/context/aop）的架构自 4.x 以来高度稳定，`refresh()`、`doGetBean`、`proceed()` 这些核心方法在 5.x / 6.x / 7.x 之间的骨架几乎一致，因此本文内容对使用 Spring Boot 3.x（Framework 6.x）的读者同样适用；5.x → 6.x → 7.x 的特性演进对比见 1.6 节；文中所有"某特性属于哪个版本"的结论均经本地 git 标签（v5.3.39 ~ v7.0.9）逐项实证。
>
> **阅读约定**：每章"先白话、后源码"——先用一两句人话讲清"这是什么、为什么需要它"，再给出源码证据链（`模块/src/main/java/.../类名.java` + 方法名 + 行号 + 代码片段）。行号只对该快照精确，读者用 IDEA 打开源码按类名 + 方法名定位即可。

## 如何读这份文档

如果你是 Spring 初学者，推荐两遍读法：

- **第一遍（建立地图，1~2 小时）**：只读第一章（总览）每一节的开头白话段、各章的"小结"节、以及第八章（贯通视图）。目标是能回答：容器启动做了哪 12 步？一个 Bean 从 `getBean` 到可用经历哪几步？`@Transactional` 靠什么生效？
- **第二遍（深入源码）**：对照每一章的【源码证据】，打开源码逐行读。顺序建议：第二章（IoC 内核）→ 第四章（refresh 与注解落地）→ 第五章（AOP）→ 第七章（扩展机制，可跳读）→ 第六章（Web 与事务）→ 第三章（core 地基，随用随查）。

---

# 一、总览：Spring 的定位、设计哲学与整体架构

## 1.1 一句话定位

**Spring 是一个以 IoC 容器为核心、以扩展点为骨架的应用框架**：它把"对象如何被创建、如何被组装、生命周期由谁管理"从业务代码中夺走（IoC），又把"日志、事务、缓存、安全这类横切逻辑"从业务方法中抽离（AOP），再以统一的策略接口把 Web、数据访问、消息等能力接进来。官方文档自述（`framework-docs/modules/ROOT/pages/overview.adoc`）："Spring came into being in 2003 as a response to the complexity of the early J2EE specifications."

它解决的不是某一个具体问题，而是**企业应用的组织问题**：对象之间如何解耦（依赖注入）、功能如何复用而不侵入业务（切面）、配置如何外置且分优先级（Environment/PropertySource）、第三方技术如何以统一姿势接入（FactoryBean/适配器/模板方法）。

## 1.2 设计哲学：读源码前先记住四句话

1. **面向接口 + 最小接口原则**。容器顶层 `BeanFactory` 只暴露 `getBean` 系列方法；要列举、要配置、要装配，分别去实现 `ListableBeanFactory`、`ConfigurableBeanFactory`、`AutowireCapableBeanFactory`（见 2.3 节）。用户永远依赖最小接口，框架内部再"集大成"于 `DefaultListableBeanFactory`。
2. **容器只存配方，不存魔法**。容器里是 `BeanDefinition`（配方元数据），对象按配方延迟/预创建；`@Autowired`、AOP 代理、`@PostConstruct` 全部由可插拔的"后置处理器"完成——框架核心对注解零硬编码（见 2.6.4、7.2 节）。
3. **每个环节留口子**。refresh 的每一步、Bean 生命周期的每一阶段，几乎都对应一个可由用户实现的接口（`BeanFactoryPostProcessor`、`BeanPostProcessor`、`FactoryBean`、`ImportSelector`……），Spring 自己的注解体系也是这些口子的"首位用户"（第七章逐个展开）。
4. **适配而非重造**。数据访问（JDBC/JPA/Hibernate）、消息（JMS/WebSocket）、Web（Servlet/Reactor）都遵循同一配方：**一个核心策略接口 + 若干第三方适配器 + 与容器/事务/AOP 的打通**（第六章）。

## 1.3 模块分层全景

Spring Framework 是多模块 Gradle 工程，根目录实测共有 22 个功能模块。按"用户感知"分层如下（详细逐模块职责见 6.1 节全景表）：

```
┌───────────────────────────── 测试层 ─────────────────────────────┐
│  spring-test（TestContext / MockMvc / Mock 对象）                 │
├───────────────────────────── Web 层 ─────────────────────────────┤
│  spring-web（双栈公共抽象：Http 抽象、参数解析器、RestClient）      │
│  ├ Servlet 栈：spring-webmvc（DispatcherServlet）+ spring-websocket│
│  └ Reactive 栈：spring-webflux（DispatcherHandler）               │
├────────────────────────── 数据访问层 ────────────────────────────┤
│  spring-tx（事务抽象 PlatformTransactionManager、@Transactional） │
│  spring-jdbc（JdbcTemplate）  spring-orm（JPA/Hibernate 适配）     │
│  spring-r2dbc（响应式 DB）    spring-jms  spring-oxm  spring-messaging│
├────────────────────────── 应用层 ────────────────────────────────┤
│  spring-context（ApplicationContext、@ComponentScan、事件、i18n） │
│  spring-context-support（Caffeine/Quartz/邮件胶水）               │
│  spring-context-indexer（编译期组件索引）                          │
├────────────────────────── 核心容器层 ────────────────────────────┤
│  spring-beans（BeanDefinition、BeanFactory、Bean 生命周期）        │
│  spring-aop（动态代理、Advisor/Advice/Pointcut）                   │
│  spring-expression（SpEL 表达式）                                  │
│  spring-aspects（AspectJ 深度集成）  spring-instrument（类加载期织入）│
├────────────────────────── 地基 ─────────────────────────────────┤
│  spring-core（Resource、Environment、类型转换、ASM 元数据、工具箱） │
└──────────────────────────────────────────────────────────────────┘
```

## 1.4 模块依赖图（以各模块 build.gradle 的 api 依赖实证）

```
                 spring-core（一切的地基）
                 ▲    ▲    ▲    ▲    ▲
     ┌───────────┘    │    │    │    └───────────┐
 spring-beans ◄── spring-expression  │    │    spring-messaging
     ▲    ▲          │    │    │    │              │
     │    └ spring-aop     │    │    │              │
     │          ▲     │    │    │    │              │
     │          └─────┴─ spring-context ◄────────────┘
     │                   ▲    ▲    ▲
     │      ┌────────────┘    │    └──────────────┐
 spring-tx ──┘                │            spring-web-support 等
     ▲    ▲                   │
     │    └── spring-jdbc      │
     │           ▲             │
     │      spring-orm          │
     │                          │
 spring-web ◄──── spring-webmvc（额外依赖 context/aop/expression）
     ▲           │
     └ spring-webflux
```

真实依赖声明（摘自各模块 `<module>.gradle` 的 `api(project(...))`，已逐个验证）：

| 模块 | 直接依赖（api） |
|---|---|
| spring-expression | core |
| spring-beans | core |
| spring-aop | beans, core |
| spring-context | aop, beans, core, expression |
| spring-tx | beans, core（拦截器实现基于 `org.aopalliance` 接口，该包已并入 spring-aop 模块源码） |
| spring-jdbc | beans, core, tx |
| spring-orm | beans, core, jdbc, tx |
| spring-web | beans, core |
| spring-webmvc | aop, beans, context, core, expression, web |
| spring-webflux | beans, core, web |
| spring-messaging | beans, core |
| spring-context-support | beans, context, core |
| spring-test | core |

这张表本身就是一份架构说明：**context 居中**（向上支撑一切、向下聚合 beans/aop/expression），**tx 不依赖 web、web 不依赖 tx**（用 Spring 时几乎总是一起引入，但框架内部保持正交），**jdbc/orm 建立在 tx 之上**（事务语义统一）。

## 1.5 关键问题 → Spring 方案映射（全文导览）

| 企业开发的关键问题 | Spring 的方案 | 详见 |
|---|---|---|
| 对象到处 `new`，依赖硬编码，改一处动全身 | IoC 容器：BeanDefinition 配方 + BeanFactory 统一创建组装 + 依赖注入 | 第二章 |
| 业务对象绑定容器 API，测试困难 | POJO + 可选生命周期接口 + 后置处理器（非侵入） | 第二章、第七章 |
| 配置散落各处（XML/注解/环境变量/命令行） | BeanDefinition 多来源加载 + Environment/PropertySource 有序优先级 | 第三章 |
| 日志/事务/缓存等横切逻辑侵入业务 | AOP：动态代理 + 责任链拦截器 + 切点表达式 | 第五章 |
| 周期性样板代码（JDBC 六板斧、事务开关） | 模板方法（JdbcTemplate/TransactionTemplate）+ 回调 | 第六章 |
| 检查型异常（SQLException）污染业务 | 异常转译体系（→ 非检查型 DataAccessException 层次） | 第六章 |
| Web 层与业务层松散无序 | DispatcherServlet 前端控制器 + HandlerMapping/Adapter/Resolver 策略族 | 第六章 |
| 事务边界靠手工 begin/commit，传播语义复杂 | @Transactional = AOP Advisor + TransactionInterceptor + ThreadLocal 资源绑定 | 第五章、第六章 |
| 组件间通信需要解耦 | 容器内事件总线（ApplicationEventMulticaster / @EventListener） | 第四章、第七章 |
| 第三方对象（动态代理、远程引用）接不进容器 | FactoryBean / BeanDefinitionRegistryPostProcessor / @Import | 第七章 |
| 框架能力锁死，定制要改源码 | 20+ 个扩展点（BFPP/BPP/Aware/FactoryBean/Import/Converter/Scope…） | 第七章 |
| 启动慢、反射多（云原生场景） | @Indexed 编译期索引 + AOT（BeanFactory 代码生成）+ GraalVM 适配 | 第三章、第七章 |

## 1.6 版本演进：5.x → 6.x → 7.x 关键变化对比

写作时（2026 年 10 月）的版本格局：5.x 已结束 OSS 维护，6.2 与 7.0.x 并行维护，7.1 尚在快照阶段（按历年节奏预计 2026-11 GA——正是本文分析的这份 main 分支快照）。本节所有"特性属于哪个版本"的结论都经过双重验证：**① 用本地仓库的 git 标签（v5.3.39 / v6.0.0 / v6.1.0 / v6.2.0 / v6.2.19 / v7.0.0 / v7.0.9）对源码逐项 grep 实证；② GA 日期取自各发布 tag 的 git 提交时间并与 spring.io 官方博客交叉核对**。

### 1.6.1 版本时间线与运行基线

| 版本 | GA 时间 | JDK | Java EE / Jakarta EE | 对应 Spring Boot | 一句话主题 |
|---|---|---|---|---|---|
| 5.0 | 2017-09-28 | JDK 8 | Java EE 7 | 2.0 | 响应式起点：WebFlux 登场 |
| 5.3（5.x 收官） | 2020-10-27 | JDK 8（向上兼容 17/21） | Java EE 7/8 | 2.3~2.7 | 长期维护线（最后一个 5.3 版本发布于 2024-08-14，OSS 支持至 2024-08） |
| 6.0 | 2022-11-16 | **JDK 17** | **Jakarta EE 9/10（javax.→jakarta.）** | 3.0 | 基座大迁移 + AOT 正式化 |
| 6.1 | 2023-11-16 | JDK 17（支持 21 虚拟线程） | Jakarta EE 10 | 3.2 | 现代客户端：RestClient / JdbcClient |
| 6.2 | 2024-11-14 | JDK 17 | Jakarta EE 10 | 3.4 | 启动与测试增强 |
| 7.0 | 2025-11-13 | JDK 17（支持至 25） | Jakarta EE 11 | 4.0 | 空安全标准化 + 内建韧性 |
| 7.1（本文快照） | 预计 2026-11 | JDK 17 | Jakarta EE 11 | 4.1 | 迭代增强 |

基线的源码证据（本地 git 标签实证）：

| 结论 | 证据 |
|---|---|
| 5.3 基线 JDK 8 | v5.3.39 `gradle/toolchains.gradle:69`：`sourceCompatibility = JavaVersion.VERSION_1_8` |
| 6.0 基线 JDK 17 | v6.0.0 `gradle/toolchains.gradle:47`：`return JavaLanguageVersion.of(17)`、`:69`：`sourceCompatibility = JavaVersion.VERSION_17` |
| 7.x 基线 JDK 17（构建工具链用 25） | 本快照 `buildSrc/.../JavaConventions.java:54`：`DEFAULT_RELEASE_VERSION = JavaLanguageVersion.of(17)`（编译目标），`:48`：`JavaLanguageVersion.of(25)`（工具链） |
| 6.0 完成 javax.→jakarta. | v5.3.39 `spring-web/spring-web.gradle`：`optional("javax.servlet:javax.servlet-api")`；v6.0.0 同位置：`optional("jakarta.servlet:jakarta.servlet-api")` |
| 7.0 移除 spring-jcl 模块 | `git ls-tree`：spring-jcl 在 v5.3.39 / v6.0.0 / v6.2.19 存在、v7.0.9 消失；v7.0.9 `spring-core/spring-core.gradle:78` 改为直接 `api("commons-logging:commons-logging")` |

### 1.6.2 特性引入版本对照表（git 实证，可复现）

每行都可用 `git grep -c "<关键字>" <tag> -- <路径>` 复现（`git ls-tree -r <tag> --name-only` 验证文件级特性）。"详见"列指向本文对应章节。

| 特性 | 引入版本 | 实证（旧 tag → 新 tag） | 详见 |
|---|---|---|---|
| spring-webflux 响应式栈、ReactiveAdapterRegistry | 5.0 | v5.3.39 已存在（历史特性） | 六.6.4 |
| themeResolver 与主题体系 | 5.0 **移除** | DispatcherServlet 的 initStrategies 此后仅剩 8 个 init | 六.6.3.1 |
| Kotlin 协程（CoroutinesUtils）、@Configuration(proxyBeanMethods) | 5.2 | 历史特性（本地无 5.2 tag，据官方发布说明） | 三.3.7 |
| spring-jcl 日志桥模块 | 5.0 引入 → **7.0 移除** | 见上表 | — |
| AOT 基建（`beans/factory/aot` 包 + AotDetector） | 6.0 | v5.3.39 该包 0 个文件 → v6.0.0 共 59 个，且含 AotDetector | 三.3.8、七.7.10 |
| javax.→jakarta.（全部 API 命名空间） | 6.0 | 见上表 | 六.6.1 |
| supportsAsyncExecution（监听器异步执行过滤） | 6.1 | v6.0.0=0 → v6.1.0=1 | 四.4.5 |
| RestClient / JdbcClient / VirtualThreadDelegate（虚拟线程） | 6.1 | v6.0.0=0 → v6.1.0 各 1 处 | 六.6.2 |
| ReentrantLock 启动锁（startupShutdownLock） | 6.2 | v6.1.0=0 → v6.2.0=7 处 | 四.4.3 |
| 后台并行初始化（bootstrapExecutor / backgroundInit） | 6.2 | v6.1.0=0 → v6.2.0=5 处 | 四.4.3 ⑨ |
| @Fallback | 6.2 | v6.1.0=0 → v6.2.0=1 | — |
| MockMvcTester（AssertJ 风格 MVC 测试） | 6.2 | v6.1.0=0 → v6.2.0=4 | — |
| refresh 失败先停 Lifecycle Bean（lifecycleProcessor.stop） | 7.0 | v6.2.19=0 → v7.0.9=1 | 四.4.3 |
| BeanRegistrar（@Import 的第 4 种玩法） | 7.0 | v6.2.19=0 → v7.0.0=4 处 | 四.4.4.3、七.7.5 |
| getBean 的 ParameterizedTypeReference 重载 | 7.0 | v6.2.19=0 → v7.0.9=3 | 二.2.3 |
| JSpecify 空安全（org.jspecify 全面替换自有注解） | 7.0 | v6.2.19 spring-core 0 个文件 → v7.0.0 达 377 个 | 三.3.7 |
| API 版本化（ApiVersionResolver 族） | 7.0 | v6.2.19=0 → v7.0.0=2 | 六.6.3.3 |
| 内建韧性（@Retryable/@ConcurrencyLimit，resilience 包） | 7.0 | v6.2.19=0 → v7.0.9=1 | — |
| JmsClient（JMS 流式客户端，对标 JdbcClient） | 7.0 | v6.2.19=0 → v7.0.9=1 | — |
| HttpServiceProxyRegistry / @ImportHttpServices | 7.0 | v6.2.19=0 → v7.0.9=3 | — |
| Jackson 3（tools.jackson 命名空间）支持 | 7.0 | v6.2.19=0 → v7.0.9 spring-web 内 23 个文件 | — |
| HttpInvoker 远程调用 | 7.0 **移除** | 本快照全部源码 0 命中 | — |
| JUnit 4 测试支持 | 7.0 **标记废弃** | `SpringJUnit4ClassRunner.java:100`：`@Deprecated(since = "7.0")` | — |
| DefaultResourceLoader 支持 `classpath*:` 前缀 | 7.1（快照，未 GA） | v7.0.9=0 → 本快照=4 处 | 三.3.2 |

三个容易搞错的点，特别提醒（本文初稿也错了前两处，均已被 git 标签实证修正）：

- **后台并行初始化（backgroundInit/bootstrapExecutor）不是 7.x 特性**，6.2 就引入了；
- **BeanRegistrar 不是 7.1 特性**，7.0 已落地；
- **API 版本化不算 6.2 特性**：它在 6.2 里程碑中试验过，但 GA 时被移除，正式落地是 7.0（v6.2.19 中不存在 ApiVersionResolver）。

### 1.6.3 三大版本主题对比

| 维度 | 5.x（2017~2020） | 6.x（2022~2024） | 7.x（2025~） |
|---|---|---|---|
| 运行基线 | JDK 8 + javax.* | JDK 17 + jakarta.* | JDK 17~25 + Jakarta EE 11 |
| 编程模型 | 响应式（WebFlux）、Kotlin/协程、函数式端点 | 延续 5.x 模型，无范式变化 | 编程式 Bean 注册（BeanRegistrar）、内建韧性注解（@Retryable/@ConcurrencyLimit） |
| 云原生 | 实验性（spring-native 独立项目） | AOT/GraalVM 正式化（RuntimeHints、BeanFactory 代码生成） | AOT 深化，全量标准化空安全注解利于 AOT 静态推导 |
| HTTP 客户端 | RestTemplate（阻塞，现为维护态） | RestClient（6.1，流式 API） | HttpServiceProxyRegistry 动态注册 HTTP 服务客户端 |
| JSON 序列化 | Jackson 2 | Jackson 2 | Jackson 3（Jackson 2 支持进入维护） |
| 容器启动 | 同步串行 | 虚拟线程适配（6.1）→ 单例后台并行初始化（6.2） | 成熟化；启动失败先优雅停止 Lifecycle（7.0） |
| 测试 | JUnit 5 支持（5.0） | MockMvcTester（6.2，AssertJ 风格） | JUnit 4 废弃（7.0），JUnit 5 + AssertJ 为主 |
| 空安全 | 自有 @Nullable 注解 | 同左（迁移准备期） | JSpecify 标准注解全面替换 |

### 1.6.4 对初学者的意义：哪些知识过时了，哪些永远有效

- **新项目避免使用**：XML 配置（仍支持但非主流）、`RestTemplate`（改用 `RestClient`）、`javax.*` 依赖、JUnit 4 测试、theme 体系、HttpInvoker。
- **5.x 老教程里永久有效的部分**：IoC/DI 概念、`BeanFactory`/`refresh()`/`doGetBean` 骨架、AOP 责任链、事务传播行为、MVC 分发模型——这些核心从 5.x 到 7.x 基本未变，本文第二~六章正是按这条主线写的。
- **版本与 Boot 的对应关系**（选教材时对号入座）：Boot 2.x ↔ Framework 5.x；Boot 3.0/3.1 ↔ 6.0；Boot 3.2 ↔ 6.1；Boot 3.4/3.5 ↔ 6.2；Boot 4.x ↔ 7.0/7.1。从 Boot 3.x 入手的读者，实际用的就是 Framework 6.2 一带的内核。

## 1.7 全文章节地图

- **第二章 背景与 IoC 容器内核（spring-beans）**：从 EJB 痛点讲起，用源码走完 `doGetBean` → `doCreateBean` 四步 → 三级缓存 → 销毁的完整生命周期。
- **第三章 地基设施（spring-core）**：Resource、Environment/PropertySource、ResolvableType、类型转换双体系、ASM 类元数据、运行时检测器。
- **第四章 应用上下文（spring-context）**：`refresh()` 十二步逐段解析；注解编程模型（@ComponentScan/@Bean/@Import）如何由一组内部处理器落地；事件、国际化、Lifecycle。
- **第五章 AOP 与表达式语言（spring-aop / spring-expression）**：JDK/CGLIB 代理选择、`proceed()` 责任链、自动代理（容器与 AOP 的接线处）、SpEL 递归下降解析。
- **第六章 Web 与数据访问模块**：DispatcherServlet 请求全流程、参数解析与消息转换、@Transactional 事务全链路、JdbcTemplate 模板与异常转译、其余模块速览。
- **第七章 扩展机制大全**：20+ 个扩展点按"接口定义 → 触发时机 → 内置实现 → 最小示例"四段式逐个展开——Spring 作为"框架中的框架"的证据链。
- **第八章 贯通视图**：把容器启动、Bean 生命周期、一次 HTTP 请求三条时间线叠加成一张全景图。
- **第九章 附录**：关键接口速查表与初学者学习路线。


---

# 二、背景与 IoC 容器内核（spring-beans）

> 本章对应源码：`spring-beans` 模块（根目录 `D:\code\3rd\spring-framework\spring-beans`，源码位于 `src/main/java`）。以下所有【源码证据】中的行号均为实际读取 7.1.0-SNAPSHOT（main 分支）源码所得。

## 2.1 历史背景：Spring 要解决什么问题

**（本小节为历史背景综述，基于公开出版史料，行文随后的源码证据将验证 Spring 的确沿这些方向演进。）**

在 Spring 出现之前，Java 企业开发的主流方案是 Sun 制定的 J2EE 规范，核心是 EJB（Enterprise JavaBeans）。EJB 设计的目标是提供分布式事务、远程调用、声明式安全等"企业级"能力，但实际使用中暴露了一连串问题：

1. **重量级组件模型**：一个普通业务类必须写 Home 接口、Remote 接口、Bean 实现类，还要配部署描述符（ejb-jar.xml），才能被容器管理。
2. **侵入式 API**：EJB 2.x 时代，Bean 必须实现 `SessionBean` 等容器接口、实现 `ejbCreate()/ejbRemove()` 等回调，业务类与容器类型紧紧耦合。
3. **测试困难**：EJB 依赖容器运行环境，单元测试要么启动庞大的应用服务器，要么使用昂贵的 mock 框架——业务对象离开容器就无法实例化。
4. **远程调用被过度推销**：为了"可扩展性"，业界默认用分布式对象（EJB 远程接口）做本地调用，网络开销与复杂度大增。Martin Fowler 后来专门写《First Law of Distributed Object Design: don't distribute your objects》批评这一风气。
5. **JNDI 查找与部署描述符繁杂**：对象要自己 `new InitialContext().lookup("...")` 去拉取依赖，配置散落在 XML 里，"配置地狱"由此得名。

2002 年，Rod Johnson 出版《Expert One-on-One J2EE Design and Development without EJB》的前身——《Expert One-on-One J2EE Design and Development》（Wrox, 2002），书中第 4 章与第 11 章提出了一套替代方案：**用普通 Java 对象（POJO）+ 面向接口编程 + 一个"工厂"在容器侧统一完成对象的创建与组装**。2003 年该项目以 interface21 之名开源；官方文档明确记载：*"Spring came into being in 2003 as a response to the complexity of the early J2EE specifications."* 2004 年发布 Spring 1.0。

这套思路就是后来所称的 **IoC（Inversion of Control，控制反转）/ 依赖注入（DI）**：把"对象什么时候创建、依赖谁、生命周期如何"的控制权，从业务代码手里**夺走**，交给框架的容器。业务代码只声明"我需要什么"（依赖一个接口），容器负责"给什么"（找到实现并注入）。

这不是后人的总结，Spring 自己的源码里就写着这段历史。

【源码证据】`spring-beans/src/main/java/org/springframework/beans/package-info.java` 第 5-10 行——beans 包的文档直接引用了 Rod Johnson 那本书：

```java
 * <p>The classes in this package are discussed in Chapter 11 of
 * <a href="https://www.amazon.com/exec/obidos/tg/detail/-/0764543857/">Expert One-On-One J2EE Design and Development</a>
 * by Rod Johnson (Wrox, 2002).
```

【源码证据】`framework-docs/modules/ROOT/pages/overview.adoc` 第 46-50 行——官方文档"History"一节：

```adoc
[[overview-history]]
== History of Spring and the Spring Framework

Spring came into being in 2003 as a response to the complexity of the early
J2EE specifications.
```

**Spring 的解法方向，可以从源码结构性地读出来**：

- **面向接口 + POJO**：IoC 容器的核心入口 `BeanFactory` 自 2001 年 4 月 13 日就存在（见其 `@since` 标签），它只是一个纯接口，对实现没有任何容器环境要求。
- **容器组装**：`BeanFactory` 的 Javadoc 明确说容器的意义是"应用组件的中央注册表，集中管理配置，对象再也不用自己去读属性文件"，并同样引用了 Rod Johnson 书的第 4、11 章。
- **不依赖容器即可测试**：被容器管理的对象是 POJO，`new UserService()` 也能跑，只是依赖需要自己设置；交给容器则自动注入。这正是对 EJB "脱离容器跑不起来"的直接回应。

【源码证据】`spring-beans/src/main/java/org/springframework/beans/factory/BeanFactory.java` 第 43-53 行（Javadoc）与第 104 行：

```java
 * <p>The point of this approach is that the BeanFactory is a central registry
 * of application components, and centralizes configuration of application
 * components (no more do individual objects need to read properties files,
 * for example). See chapters 4 and 11 of "Expert One-on-One J2EE Design and
 * Development" for a discussion of the benefits of this approach.
 *
 * <p>Note that it is generally better to rely on Dependency Injection
 * ("push" configuration) to configure application objects through setters
 * or constructors, rather than use any form of "pull" configuration like a
 * BeanFactory lookup.
```

```java
 * @since 13 April 2001
```

【源码证据】`spring-beans/src/main/java/org/springframework/beans/factory/package-info.java` 第 6-11 行——"Singleton 与 Prototype 设计模式的替代品"这句话本身就是在回应 EJB 时代的设计模式困境：

```java
 * <p>Provides an alternative to the Singleton and Prototype design
 * patterns, including a consistent approach to configuration management.
 * Builds on the org.springframework.beans package.
```

## 2.2 spring-beans 模块定位与包结构

用大白话说：**spring-beans 就是"配方的仓库 + 造物的车间"**。它不管配置从哪来（XML、注解、代码都能当配置），只负责两件事：把配置解析成统一的"bean 配方"（BeanDefinition），再按配方把对象造出来、装配好。

模块内两大顶层包：

```
org.springframework.beans
├── BeanDefinition 相关接口的"类型无关于工厂"部分：config 包
│      （BeanDefinition、BeanFactory 配置族接口、Scope……）
├── beans/                 # BeanWrapper、PropertyValues、类型转换等"属性操作"基础设施
└── beans/factory/
    ├── BeanFactory.java   # 容器顶层接口 + Aware/InitializingBean/DisposableBean 等生命周期接口
    ├── annotation/        # AnnotatedGenericBeanDefinition 等注解元数据配方
    ├── config/            # BeanDefinition、AutowireCapableBeanFactory、BeanPostProcessor、Scope……
    ├── support/           # 全部抽象实现与默认实现：AbstractBeanFactory、
    │                      # DefaultListableBeanFactory、DefaultSingletonBeanRegistry、
    │                      # BeanDefinitionRegistry、XML 之外的通用骨架……
    └── xml/               # XML 时代的解析器（DefaultBeanDefinitionDocumentReader 等）
```

【源码证据】`spring-beans/src/main/java/org/springframework/beans/factory/support/package-info.java` 第 1-3 行：

```java
/**
 * Classes supporting the {@code org.springframework.beans.factory} package.
 * Contains abstract base classes for {@code BeanFactory} implementations.
 */
```

【源码证据】`spring-beans/src/main/java/org/springframework/beans/factory/xml/package-info.java` 第 1-3 行：

```java
/**
 * Contains an abstract XML-based {@code BeanFactory} implementation,
 * including a standard "spring-beans" XSD.
 */
```

## 2.3 BeanFactory 接口体系：一条"按需扩权"的接口链

初学者最容易困惑的一点：Spring 为什么把容器拆成这么多接口？答案是**最小接口原则**——给用户看的只是"能 getBean"的最小视图；需要更多能力（列举、配置、装配）的调用方，再向下转型到对应的子接口。这本身就是"面向接口编程"的示范。

接口继承关系（文字图）：

```
BeanFactory（顶层：getBean / getBeanProvider / isSingleton / getType / getAliases）
├── ListableBeanFactory（可列举：getBeanDefinitionNames / getBeanNamesForType / getBeansOfType）
├── HierarchicalBeanFactory（有父容器：getParentBeanFactory / containsLocalBean）
│    └── ConfigurableBeanFactory（可配置：setParentBeanFactory / addBeanPostProcessor /
│         registerScope；同时 extends SingletonBeanRegistry —— 可手工注册单例）
└── AutowireCapableBeanFactory（可装配"工厂外"的对象：createBean(Class) /
     autowireBean / configureBean / resolveDependency）

实现侧：
AbstractBeanFactory → AbstractAutowireCapableBeanFactory →
DefaultListableBeanFactory（唯一集大成的"全能"实现：
  implements ConfigurableListableBeanFactory, BeanDefinitionRegistry）
```

【源码证据】`spring-beans/src/main/java/org/springframework/beans/factory/BeanFactory.java` 第 26 行、第 123 行、第 156/177/218/234 行、第 267 行：

```java
public interface BeanFactory {

    /**
     * Used to dereference a {@link FactoryBean} instance and distinguish it from
     * beans <i>created</i> by the FactoryBean. ...
     */
    String FACTORY_BEAN_PREFIX = "&";
    ...
    Object getBean(String name) throws BeansException;                    // L156

    <T> T getBean(String name, Class<T> requiredType) throws BeansException; // L177

    Object getBean(String name, @Nullable Object @Nullable ... args) throws BeansException; // L218

    <T> T getBean(Class<T> requiredType) throws BeansException;           // L234
```

```java
    <T> ObjectProvider<T> getBeanProvider(Class<T> requiredType);          // L267
```

`getBean` 六个重载覆盖"按名/按名+类型/按名+泛型（7.0 新增 `ParameterizedTypeReference` 版本，L200）/构造参数/按类型"。`getBeanProvider` 则提供**延迟、可选**的查找——依赖不存在时可以优雅降级，而不是 `getBean` 直接抛 `NoSuchBeanDefinitionException`。注意 `BeanFactory` 接口上还有两份"官方生命周期说明书"：Javadoc 第 69-91 行列出初始化顺序（Aware 系列 → `postProcessBeforeInitialization` → `afterPropertiesSet` → 自定义 init-method → `postProcessAfterInitialization`），第 93-98 行列出销毁顺序（`postProcessBeforeDestruction` → `destroy()` → 自定义 destroy-method）——后面 2.6 节的源码会逐条兑现这份承诺。

【源码证据】四个子接口的声明，分别在 `spring-beans/src/main/java/org/springframework/beans/factory/ListableBeanFactory.java` 第 60 行、`.../HierarchicalBeanFactory.java` 第 34 行、`.../config/AutowireCapableBeanFactory.java` 第 63 行、`.../config/ConfigurableBeanFactory.java` 第 53 行：

```java
public interface ListableBeanFactory extends BeanFactory {          // ListableBeanFactory.java L60

    int getBeanDefinitionCount();                                   // L80

    String[] getBeanDefinitionNames();                              // L90
```

```java
public interface HierarchicalBeanFactory extends BeanFactory {      // HierarchicalBeanFactory.java L34

public interface AutowireCapableBeanFactory extends BeanFactory {   // AutowireCapableBeanFactory.java L63
    int AUTOWIRE_NO = 0;                                            // L71
    int AUTOWIRE_BY_NAME = 1;                                       // L79
    int AUTOWIRE_BY_TYPE = 2;                                       // L87
    int AUTOWIRE_CONSTRUCTOR = 3;                                   // L94
    <T> T createBean(Class<T> beanClass) throws BeansException;     // L137
```

```java
public interface ConfigurableBeanFactory extends HierarchicalBeanFactory, SingletonBeanRegistry {
    String SCOPE_SINGLETON = "singleton";                           // L60
    String SCOPE_PROTOTYPE = "prototype";                           // L67
    void setParentBeanFactory(BeanFactory parentFactory);           // L79（原文形参名 parentBeanFactory）
    void addBeanPostProcessor(BeanPostProcessor beanPostProcessor); // L261
    void registerScope(String scopeName, Scope scope);              // L273
```

各自的定位一句话概括：**Listable** 让你能"反着查"（遍历所有配方、按类型/注解找一批 bean，`getBeanNamesForType` 位于 L162/L221，`getBeansWithAnnotation` 位于 L359）；**Hierarchical** 让容器能组成父子树（Spring MVC 的 root 容器与子容器就是靠它）；**Configurable** 是给容器搭建者用的"控制面板"（设父容器、注册 BeanPostProcessor、注册自定义 scope）；**AutowireCapable** 允许容器去装配**不属于自己管理**的第三方对象（例如把某个框架现成的实例按 Spring 规则注入依赖）。

【源码证据】`spring-beans/src/main/java/org/springframework/beans/factory/support/DefaultListableBeanFactory.java` 第 132-133 行——唯一"全能"实现：

```java
public class DefaultListableBeanFactory extends AbstractAutowireCapableBeanFactory
        implements ConfigurableListableBeanFactory, BeanDefinitionRegistry, Serializable {
```

它一条继承链吃下"可配置 + 可列举 + 可装配 + 可注册配方"全部能力。日常用的 `AnnotationConfigApplicationContext` / `ClassPathXmlApplicationContext` 内部持有的就是它。**重要认知：容器 = BeanFactory 体系 + BeanDefinitionRegistry（注册表）**。

## 2.4 BeanDefinition：bean 的"配方"

初学者理解 Spring 的第一个跳板：**容器里存的从来不是对象，而是对象的"配方"**。`<bean class="..." scope="..." lazy-init="...">` 这段 XML（或一个 `@Component` 类）解析后变成一个 `BeanDefinition` 对象——它记录"造这个对象需要什么类、什么作用域、哪些构造参数、哪些属性值、初始化/销毁方法叫什么"。真正的对象是第一次 `getBean` 时（或容器刷新时预实例化）按配方造出来的，配方本身可以长期只读地留在内存里。

【源码证据】`spring-beans/src/main/java/org/springframework/beans/factory/config/BeanDefinition.java` 第 42 行（接口声明）、第 107-161 行、第 246/262 行、第 277-295 行：

```java
public interface BeanDefinition extends AttributeAccessor, BeanMetadataElement {

    String SCOPE_SINGLETON = ConfigurableBeanFactory.SCOPE_SINGLETON;  // L50
    String SCOPE_PROTOTYPE = ConfigurableBeanFactory.SCOPE_PROTOTYPE;  // L58
    ...
    void setBeanClassName(@Nullable String beanClassName);   // L107：全类名
    void setScope(@Nullable String scope);                   // L128：作用域
    void setLazyInit(boolean lazyInit);                      // L141：是否懒加载
    void setDependsOn(String @Nullable ... dependsOn);       // L156：显式依赖的前置 bean
    void setAutowireCandidate(boolean autowireCandidate);    // L170：是否参与类型装配
    void setPrimary(boolean primary);                        // L183：多候选时的优先者
    void setFactoryBeanName(@Nullable String factoryBeanName); // L213：实例工厂方法所在 bean
    void setFactoryMethodName(@Nullable String factoryMethodName); // L232：工厂方法名
    ConstructorArgumentValues getConstructorArgumentValues();  // L246：构造参数
    MutablePropertyValues getPropertyValues();                 // L262：属性键值
    void setInitMethodName(@Nullable String initMethodName);   // L277：初始化方法名
    void setDestroyMethodName(@Nullable String destroyMethodName); // L289：销毁方法名
```

注意 L31-33 的自我说明："这是一个最小接口，主要目的是让 `BeanFactoryPostProcessor` 能在容器实例化任何对象**之前**修改这些元数据"——配方先行，为容器扩展留出的钩子。

**常见实现与分工**（类的泛化也是一条"父子配方"链）：

- `AbstractBeanDefinition`（support 包）：所有配方的公共基类，持有上述全部字段的默认值；
- `RootBeanDefinition`（`.../support/RootBeanDefinition.java` L64：`public class RootBeanDefinition extends AbstractBeanDefinition`）：XML 时代每条 `<bean>` 的"合体后"配方——容器运行期真正用来创建实例的是它（`getMergedLocalBeanDefinition` 合并父子定义后的产物就是 RootBeanDefinition，见 2.5 节 doGetBean L309）；
- `GenericBeanDefinition`（`.../support/GenericBeanDefinition.java` L45：`GenericBeanDefinition extends AbstractBeanDefinition`，紧随其后 L46-47 即 `private @Nullable String parentName;`）：带 `parentName` 的一般用途配方，支持"定义继承"；
- `AnnotatedGenericBeanDefinition`（`.../factory/annotation/AnnotatedGenericBeanDefinition.java` L45：`extends GenericBeanDefinition implements AnnotatedBeanDefinition`，L47 起 `private final AnnotationMetadata metadata;`）：`@Configuration` 类式注册用的配方，携带注解元数据；
- `ScannedGenericBeanDefinition`（位于 **spring-context** 模块 `org/springframework/context/annotation/ScannedGenericBeanDefinition.java` L50：`extends GenericBeanDefinition implements AnnotatedBeanDefinition`）：包扫描（`@ComponentScan`）命中候选组件时生成的配方。

**谁来保存这些配方？** `BeanDefinitionRegistry`——注册中心接口（它继承了 `AliasRegistry`，所以别名管理也在这一侧）。

【源码证据】`spring-beans/src/main/java/org/springframework/beans/factory/support/BeanDefinitionRegistry.java` 第 47 行、第 61-97 行：

```java
public interface BeanDefinitionRegistry extends AliasRegistry {

    void registerBeanDefinition(String beanName, BeanDefinition beanDefinition)
            throws BeanDefinitionStoreException;     // L61：存配方

    void removeBeanDefinition(String beanName) throws NoSuchBeanDefinitionException;  // L69

    BeanDefinition getBeanDefinition(String beanName) throws NoSuchBeanDefinitionException; // L77

    String[] getBeanDefinitionNames();               // L91
```

## 2.5 XML 时代的加载链：从 `<bean>` 标签到注册表

初学者视角：XML 文本怎么变成内存里的配方？以 `<beans><bean id="userService" class="..."/></beans>` 为例，Spring 用 DOM 解析文档后，由 `DefaultBeanDefinitionDocumentReader` 逐个元素分发：默认命名空间的 `import`/`alias`/`bean` 走内置处理，其它元素（如 `context:component-scan`）走可插拔的自定义解析。

【源码证据】`spring-beans/src/main/java/org/springframework/beans/factory/xml/DefaultBeanDefinitionDocumentReader.java` 第 166-200 行：

```java
protected void parseBeanDefinitions(Element root, BeanDefinitionParserDelegate delegate) {
    if (delegate.isDefaultNamespace(root)) {
        NodeList nl = root.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node node = nl.item(i);
            if (node instanceof Element ele) {
                if (delegate.isDefaultNamespace(ele)) {
                    parseDefaultElement(ele, delegate);      // 默认命名空间：import/alias/bean
                }
                else {
                    delegate.parseCustomElement(ele);        // 自定义标签：交给扩展解析器
                }
            }
        }
    }
    ...
}

private void parseDefaultElement(Element ele, BeanDefinitionParserDelegate delegate) {
    if (delegate.nodeNameEquals(ele, IMPORT_ELEMENT)) {
        importBeanDefinitionResource(ele);                   // <import>
    }
    else if (delegate.nodeNameEquals(ele, ALIAS_ELEMENT)) {
        processAliasRegistration(ele);                       // <alias>
    }
    else if (delegate.nodeNameEquals(ele, BEAN_ELEMENT)) {
        processBeanDefinition(ele, delegate);                // <bean> —— 主路径
    }
```

`processBeanDefinition` 是三步走：**委托解析 → 装饰 → 注册**。

【源码证据】同文件第 302-317 行：

```java
protected void processBeanDefinition(Element ele, BeanDefinitionParserDelegate delegate) {
    BeanDefinitionHolder bdHolder = delegate.parseBeanDefinitionElement(ele);
    if (bdHolder != null) {
        bdHolder = delegate.decorateBeanDefinitionIfRequired(ele, bdHolder);
        try {
            // Register the final decorated instance.
            BeanDefinitionReaderUtils.registerBeanDefinition(bdHolder, getReaderContext().getRegistry());
        }
        catch (BeanDefinitionStoreException ex) {
            getReaderContext().error("Failed to register bean definition with name '" +
                    bdHolder.getBeanName() + "'", ele, ex);
        }
        // Send registration event.
        getReaderContext().fireComponentRegistered(new BeanComponentDefinition(bdHolder));
    }
}
```

`BeanDefinitionParserDelegate#parseBeanDefinitionElement` 负责把 `class`/`scope`/`lazy-init`/`<property>`/`<constructor-arg>` 等属性逐项搬进一个 `BeanDefinitionHolder`（它包着一根配方 + 名字 + 别名）；`decorateBeanDefinitionIfRequired` 处理需要"装饰"配方元数据的场景；最终交给工具类入册。

【源码证据】`spring-beans/src/main/java/org/springframework/beans/factory/support/BeanDefinitionReaderUtils.java` 第 158-173 行：

```java
public static void registerBeanDefinition(
        BeanDefinitionHolder definitionHolder, BeanDefinitionRegistry registry)
        throws BeanDefinitionStoreException {

    // Register bean definition under primary name.
    String beanName = definitionHolder.getBeanName();
    registry.registerBeanDefinition(beanName, definitionHolder.getBeanDefinition());

    // Register aliases for bean name, if any.
    String[] aliases = definitionHolder.getAliases();
    if (aliases != null) {
        for (String alias : aliases) {
            registry.registerAlias(beanName, alias);
        }
    }
}
```

至此 XML 文件变成 `DefaultListableBeanFactory` 里的一堆 `BeanDefinition`。**但此刻容器里还没有任何业务对象**——这正是"声明与创建分离"的精髓。下面的生命周期才是重头戏。

## 2.6 Bean 创建全生命周期（本章核心）

先给全景图，然后逐段对源码：

```
getBean("a")
 └─ AbstractBeanFactory#getBean → doGetBean            （取/建的调度中枢）
     ├─ ① getSingleton(beanName)                       三级缓存查已建/半成品单例
     ├─ ② getObjectForBeanInstance(...)                若是 FactoryBean，转交它生产产品
     ├─ ③ 本工厂查不到配方 → 委托 parentBeanFactory     层次化容器
     ├─ ④ getMergedLocalBeanDefinition + dependsOn     合并配方、先建依赖
     ├─ ⑤ scope 分叉：singleton / prototype / 自定义
     │    └─ createBean (AbstractAutowireCapableBeanFactory)
     │        └─ doCreateBean 四步：
     │            (1) createBeanInstance  构造器/工厂方法实例化
     │            (2) populateBean        属性填充 + 注入依赖
     │            (3) initializeBean      Aware → 前处理器 → init → 后处理器
     │            (4) registerDisposableBeanIfNecessary  登记销毁回调
     └─ 容器关闭时：DisposableBeanAdapter#destroy 统一执行销毁
```

### 2.6.1 doGetBean：取还是造？

【源码证据】`spring-beans/src/main/java/org/springframework/beans/factory/support/AbstractBeanFactory.java` 第 249-268 行：

```java
protected <T> T doGetBean(
        String name, @Nullable Class<T> requiredType, @Nullable Object @Nullable [] args, boolean typeCheckOnly)
        throws BeansException {

    String beanName = transformedBeanName(name);
    Object beanInstance;

    // Eagerly check singleton cache for manually registered singletons.
    Object sharedInstance = getSingleton(beanName);
    if (sharedInstance != null && args == null) {
        ...
        beanInstance = getObjectForBeanInstance(sharedInstance, requiredType, name, beanName, null);
    }
```

第一步先查单例缓存：命中就直接走 `getObjectForBeanInstance` 返回，**完全不会触碰配方**。这里藏着两个关键细节：

**细节一：`&` 前缀与 FactoryBean 的解引用。** `FactoryBean` 是"工厂 bean"——它本身是一个 bean，但容器对外的 `getBean(name)` 应返回它**生产**的产品。想拿工厂本身，约定用 `&name`。`getObjectForBeanInstance` 负责这层"解引用"。

【源码证据】`AbstractBeanFactory.java` 第 1854-1894 行（方法签名 L1854；`BeanFactory.FACTORY_BEAN_PREFIX = "&"` 见 BeanFactory.java L132）：

```java
protected Object getObjectForBeanInstance(Object beanInstance, @Nullable Class<?> requiredType,
        String name, String beanName, @Nullable RootBeanDefinition mbd) {

    // Don't let calling code try to dereference the factory if the bean isn't a factory.
    if (BeanFactoryUtils.isFactoryDereference(name)) {       // 名字带 "&"
        if (beanInstance instanceof NullBean) {
            return beanInstance;
        }
        if (!(beanInstance instanceof FactoryBean)) {
            throw new BeanIsNotAFactoryException(beanName, beanInstance.getClass());
        }
        ...
        return beanInstance;                                  // "&xxx" → 返回工厂本身
    }

    // Now we have the bean instance, which may be a normal bean or a FactoryBean.
    if (!(beanInstance instanceof FactoryBean<?> factoryBean)) {
        return beanInstance;                                  // 普通对象 → 原样返回
    }
    ...
    object = getObjectFromFactoryBean(factoryBean, requiredType, beanName, !synthetic);
    return object;                                            // 工厂生产的产品
}
```

**细节二：父容器委派与 dependsOn。** 本工厂没有该配方时，会去父工厂找（L279-297，`return abf.doGetBean(nameToLookup, requiredType, args, typeCheckOnly);`）——这就是层次化容器的实现处。找到配方后先合并（L309 `RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);`），再处理 `depends-on` 声明：逐个 `getBean(dep)` 强制先初始化，并用 `isDependent` 检测并拒绝 `depends-on` 环（L313-340，其中 L317-318 直接抛 "Circular depends-on relationship"）。

### 2.6.2 作用域分叉：singleton 与 prototype

【源码证据】`AbstractBeanFactory.java` 第 342-396 行：

```java
// Create bean instance.
if (mbd.isSingleton()) {
    sharedInstance = getSingleton(beanName, () -> {
        try {
            return createBean(beanName, mbd, args);
        }
        catch (BeansException ex) {
            // Explicitly remove instance from singleton cache: ...
            destroySingleton(beanName);
            throw ex;
        }
    });
    beanInstance = getObjectForBeanInstance(sharedInstance, requiredType, name, beanName, mbd);
}

else if (mbd.isPrototype()) {
    // It's a prototype -> create a new instance.
    Object prototypeInstance = null;
    try {
        beforePrototypeCreation(beanName);
        prototypeInstance = createBean(beanName, mbd, args);
    }
    finally {
        afterPrototypeCreation(beanName);
    }
    beanInstance = getObjectForBeanInstance(prototypeInstance, requiredType, name, beanName, mbd);
}

else {
    String scopeName = mbd.getScope();
    ...
    Scope scope = this.scopes.get(scopeName);
    ...
    Object scopedInstance = scope.get(beanName, () -> {
        ...
        return createBean(beanName, mbd, args);
        ...
    });
```

三个分支就是 scope 的全部真相：**singleton 走 `getSingleton(beanName, ObjectFactory)`，容器保证一次创建、之后共享**（若中途抛异常还会 `destroySingleton` 回滚半成品）；**prototype 每次调用 `createBean` 全新创建、容器不缓存实例**，只登记"正在创建中"以拒绝循环依赖（`beforePrototypeCreation/afterPrototypeCreation` 配对维护）；**其它作用域**（request/session 等 web 作用域）委托给 `Scope` 策略对象，由它决定何时复用、何时新建——一句话：自定义 scope 把"存取策略"外置成接口 `org.springframework.beans.factory.config.Scope`（注册入口是 `ConfigurableBeanFactory#registerScope`，L273）。

### 2.6.3 三级缓存：循环依赖下如何"提前暴露半成品"

白话版：A 依赖 B，B 又依赖 A。容器建 A 建到一半（已实例化、未填充属性）就得去建 B，B 又要 A——这时不能重新造一个 A（否则 B 拿到的是最终版之外的原件），必须能把"A 的半成品"提前递出去。Spring 的办法是三个 Map：

【源码证据】`spring-beans/src/main/java/org/springframework/beans/factory/support/DefaultSingletonBeanRegistry.java` 第 85-101 行（字段定义原文）：

```java
/** Cache of singleton objects: bean name to bean instance. */
private final Map<String, Object> singletonObjects = new ConcurrentHashMap<>(256);

/** Creation-time registry of singleton factories: bean name to ObjectFactory. */
private final Map<String, ObjectFactory<?>> singletonFactories = new ConcurrentHashMap<>(16);

/** Cache of early singleton objects: bean name to bean instance. */
private final Map<String, Object> earlySingletonObjects = new ConcurrentHashMap<>(16);
...
/** Names of beans that are currently in creation. */
private final Set<String> singletonsCurrentlyInCreation = ConcurrentHashMap.newKeySet(16);
```

- **一级 `singletonObjects`**：成品单例（beanName → 最终实例）；
- **二级 `earlySingletonObjects`**：提前曝光的半成品/早期引用（一旦有人拿走就存这里，防止重复生成）；
- **三级 `singletonFactories`**：能生产早期引用的工厂（`ObjectFactory`）。

【源码证据】`DefaultSingletonBeanRegistry.java` 第 208-235 行——查找顺序"一级 → 二级 → 三级"，且从三级缓存取出后立刻升级进二级：

```java
protected @Nullable Object getSingleton(String beanName, boolean allowEarlyReference) {
    // Quick check for existing instance without full singleton lock.
    Object singletonObject = this.singletonObjects.get(beanName);
    if (singletonObject == null && isSingletonCurrentlyInCreation(beanName)) {
        singletonObject = this.earlySingletonObjects.get(beanName);
        if (singletonObject == null && allowEarlyReference) {
            if (!this.singletonLock.tryLock()) {
                // Avoid early singleton inference outside of original creation thread.
                return null;
            }
            try {
                // Consistent creation of early reference within full singleton lock.
                singletonObject = this.singletonObjects.get(beanName);
                if (singletonObject == null) {
                    singletonObject = this.earlySingletonObjects.get(beanName);
                    if (singletonObject == null) {
                        ObjectFactory<?> singletonFactory = this.singletonFactories.get(beanName);
                        if (singletonFactory != null) {
                            singletonObject = singletonFactory.getObject();
                            // Singleton could have been added or removed in the meantime.
                            if (this.singletonFactories.remove(beanName) != null) {
                                this.earlySingletonObjects.put(beanName, singletonObject);
                            }
                            ...
```

【源码证据】同文件第 183-188 行——放入三级缓存的唯一入口 `addSingletonFactory`：

```java
protected void addSingletonFactory(String beanName, ObjectFactory<?> singletonFactory) {
    Assert.notNull(singletonFactory, "Singleton factory must not be null");
    this.singletonFactories.put(beanName, singletonFactory);
    this.earlySingletonObjects.remove(beanName);
    this.registeredSingletons.add(beanName);
}
```

而"半成品转正"发生在 `addSingleton`（L159-173）：成品进入一级缓存，同时清掉二、三级缓存条目。为什么需要"工厂"而不是直接放对象？因为**早期引用可能不该是裸对象**——AOP 场景下对外暴露的应是代理。 getObject 时若工厂存在就调 `singletonFactory.getObject()`，而 doCreateBean 注册的工厂体是 `() -> getEarlyBeanReference(beanName, mbd, bean)`，后者会遍历 `SmartInstantiationAwareBeanPostProcessor` 的 `getEarlyBeanReference` 回调（AbstractAutowireCapableBeanFactory.java L965-973）——AOP 提前代理正是挂在这个钩子上，本章一句带过。

`getSingleton(beanName, ObjectFactory)` 的主流程（L253 起）则保证"检查→加锁→调用工厂创建→`afterSingletonCreation` 摘除'创建中'标记→`addSingleton` 转正"的原子性（关键行：L310 `beforeSingletonCreation(beanName);`——同一 bean 并发重复创建会在这里抛 `BeanCurrentlyInCreationException`；L366 `singletonObject = singletonFactory.getObject();`；L399 `afterSingletonCreation(beanName);`；L402-403 `addSingleton(beanName, singletonObject);`）。

### 2.6.4 createBean → doCreateBean：造一个 bean 的四步

`doGetBean` 的三个分支最终都落到 `createBean`。它先解析类、给 `InstantiationAwareBeanPostProcessor#postProcessBeforeInstantiation` 一次"短路"机会（AOP 的 targetSource 场景可以直接返回代理而跳过常规创建），然后进入真正的四步流程。

【源码证据】`spring-beans/src/main/java/org/springframework/beans/factory/support/AbstractAutowireCapableBeanFactory.java` 第 481-528 行（方法注释自称 "Central method of this class"）：

```java
/**
 * Central method of this class: creates a bean instance,
 * populates the bean instance, applies post-processors, etc.
 * @see #doCreateBean
 */
@Override
protected Object createBean(String beanName, RootBeanDefinition mbd, @Nullable Object @Nullable [] args)
        throws BeanCreationException {
    ...
    Class<?> resolvedClass = resolveBeanClass(mbd, beanName);
    ...
    try {
        // Give BeanPostProcessors a chance to return a proxy instead of the target bean instance.
        Object bean = resolveBeforeInstantiation(beanName, mbdToUse);
        if (bean != null) {
            return bean;
        }
    }
    ...
    Object beanInstance = doCreateBean(beanName, mbdToUse, args);
```

【源码证据】同文件第 555-647 行——`doCreateBean` 的四步骨架（关键行抽取）：

```java
protected Object doCreateBean(String beanName, RootBeanDefinition mbd, @Nullable Object @Nullable [] args)
        throws BeanCreationException {

    // Instantiate the bean.
    BeanWrapper instanceWrapper = null;
    if (mbd.isSingleton()) {
        instanceWrapper = this.factoryBeanInstanceCache.remove(beanName);
    }
    if (instanceWrapper == null) {
        instanceWrapper = createBeanInstance(beanName, mbd, args);        // 第 1 步
    }
    ...
    // Eagerly cache singletons to be able to resolve circular references ...
    boolean earlySingletonExposure = (mbd.isSingleton() && this.allowCircularReferences &&
            isSingletonCurrentlyInCreation(beanName));
    if (earlySingletonExposure) {
        ...
        addSingletonFactory(beanName, () -> getEarlyBeanReference(beanName, mbd, bean));
    }

    // Initialize the bean instance.
    Object exposedObject = bean;
    try {
        populateBean(beanName, mbd, instanceWrapper);                     // 第 2 步
        exposedObject = initializeBean(beanName, exposedObject, mbd);     // 第 3 步
    }
    ...
    // Register bean as disposable.
    try {
        registerDisposableBeanIfNecessary(beanName, bean, mbd);           // 第 4 步
    }
    ...
    return exposedObject;
}
```

**第 1 步：createBeanInstance——决定"怎么 new"。** 优先级依次是：实例供给器（`instanceSupplier`，L1184-1188）→ `@Bean`/工厂方法（L1190-1192 `return instantiateUsingFactoryMethod(beanName, mbd, args);`）→ 有构造参数或构造器自动装配（L1215-1219 `autowireConstructor(...)`）→ 兜底无参构造（L1228 `return instantiateBean(beanName, mbd);`）。无参路径最终由 `SimpleInstantiationStrategy` 执行真正的反射 new：

【源码证据】`spring-beans/src/main/java/org/springframework/beans/factory/support/SimpleInstantiationStrategy.java` 第 86-111 行：

```java
@Override
public Object instantiate(RootBeanDefinition bd, @Nullable String beanName, BeanFactory owner) {
    // Don't override the class with CGLIB if no overrides.
    if (!bd.hasMethodOverrides()) {
        Constructor<?> constructorToUse;
        synchronized (bd.constructorArgumentLock) {
            constructorToUse = (Constructor<?>) bd.resolvedConstructorOrFactoryMethod;
            if (constructorToUse == null) {
                Class<?> clazz = bd.getBeanClass();
                ...
                constructorToUse = clazz.getDeclaredConstructor();
                bd.resolvedConstructorOrFactoryMethod = constructorToUse;
                ...
            }
        }
        return BeanUtils.instantiateClass(constructorToUse);
    }
    else {
        // Must generate CGLIB subclass.
        return instantiateWithMethodInjection(bd, beanName, owner);
    }
}
```

注意：bean 类上若声明了 `lookup-method`/`replaced-method` 这类方法覆盖，就退到 CGLIB 动态子类（子类 `CglibSubclassingInstantiationStrategy`）。

**第 2 步：populateBean——属性填充，注入发生的地方。** 白话：容器拿着配方里的属性表，逐个塞进刚 new 出来的对象。

【源码证据】`AbstractAutowireCapableBeanFactory.java` 第 1389-1460 行（关键片段）：

```java
protected void populateBean(String beanName, RootBeanDefinition mbd, @Nullable BeanWrapper bw) {
    ...
    // Give any InstantiationAwareBeanPostProcessors the opportunity to modify the
    // state of the bean before properties are set. This can be used, for example,
    // to support styles of field injection.
    if (!mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
        for (InstantiationAwareBeanPostProcessor bp : getBeanPostProcessorCache().instantiationAware) {
            if (!bp.postProcessAfterInstantiation(bw.getWrappedInstance(), beanName)) {
                return;
            }
        }
    }

    PropertyValues pvs = (mbd.hasPropertyValues() ? mbd.getPropertyValues() : null);

    int resolvedAutowireMode = mbd.getResolvedAutowireMode();
    if (resolvedAutowireMode == AUTOWIRE_BY_NAME || resolvedAutowireMode == AUTOWIRE_BY_TYPE) {
        MutablePropertyValues newPvs = new MutablePropertyValues(pvs);
        // Add property values based on autowire by name if applicable.
        if (resolvedAutowireMode == AUTOWIRE_BY_NAME) {
            autowireByName(beanName, mbd, bw, newPvs);
        }
        // Add property values based on autowire by type if applicable.
        if (resolvedAutowireMode == AUTOWIRE_BY_TYPE) {
            autowireByType(beanName, mbd, bw, newPvs);
        }
        pvs = newPvs;
    }
    if (hasInstantiationAwareBeanPostProcessors()) {
        ...
        for (InstantiationAwareBeanPostProcessor bp : getBeanPostProcessorCache().instantiationAware) {
            PropertyValues pvsToUse = bp.postProcessProperties(pvs, bw.getWrappedInstance(), beanName);
            ...
        }
    }
    ...
    if (pvs != null) {
        applyPropertyValues(beanName, mbd, bw, pvs);    // 真正 setter 写入
    }
}
```

`autowireByName`（L1471-1492）的思路非常直白：找出所有"没被赋值的非简单类型 setter 属性"，属性名恰好是容器里某 bean 的名字就 `getBean(propertyName)` 注入；`autowireByType`（L1505-1543）则按 setter 参数类型用 `resolveDependency` 找唯一匹配（多个候选时靠 `@Primary`/`@Priority` 决胜）。这两个是 XML 时代 `autowire="byName/byType"` 的传统自动装配。而现代的 `@Autowired` 走的是另一条通道：`InstantiationAwareBeanPostProcessor` 后置处理器在 L1443 的 `postProcessProperties` 被逐个调用，`org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor` 在这里扫描字段/方法上的 `@Autowired`、`@Value`、`@Inject` 并完成注入——**注解注入不是容器硬编码的，而是后置处理器插件完成的**，这一句话正是 Spring"开放扩展"设计哲学的最好注脚。

**第 3 步：initializeBean——初始化仪式。** 顺序与 `BeanFactory` 接口 Javadoc 承诺的完全一致。

【源码证据】`AbstractAutowireCapableBeanFactory.java` 第 1809-1851 行：

```java
protected Object initializeBean(String beanName, Object bean, @Nullable RootBeanDefinition mbd) {
    // Skip initialization of a NullBean
    if (bean.getClass() == NullBean.class) {
        return bean;
    }

    invokeAwareMethods(beanName, bean);

    Object wrappedBean = bean;
    if (mbd == null || !mbd.isSynthetic()) {
        wrappedBean = applyBeanPostProcessorsBeforeInitialization(wrappedBean, beanName);
    }

    try {
        invokeInitMethods(beanName, wrappedBean, mbd);
    }
    catch (Throwable ex) {
        throw new BeanCreationException(...);
    }
    if (mbd == null || !mbd.isSynthetic()) {
        wrappedBean = applyBeanPostProcessorsAfterInitialization(wrappedBean, beanName);
    }

    return wrappedBean;
}

private void invokeAwareMethods(String beanName, Object bean) {
    if (bean instanceof Aware) {
        if (bean instanceof BeanNameAware beanNameAware) {
            beanNameAware.setBeanName(beanName);
        }
        if (bean instanceof BeanClassLoaderAware beanClassLoaderAware) {
            ...
        }
        if (bean instanceof BeanFactoryAware beanFactoryAware) {
            beanFactoryAware.setBeanFactory(AbstractAutowireCapableBeanFactory.this);
        }
    }
}
```

四小步：`invokeAwareMethods`（容器把名字/类加载器/工厂引用"告诉"bean——这正是控制反转的反面：需要时 bean 仍可拿到容器，容器主动推送而非 bean 主动拉取）→ `applyBeanPostProcessorsBeforeInitialization`（所有 `BeanPostProcessor` 的前置回调）→ `invokeInitMethods` → `applyBeanPostProcessorsAfterInitialization`（**AOP 代理通常在这最后一步生成**，见 spring-aop 的 `AbstractAutoProxyCreator`）。

【源码证据】同文件第 1866-1889 行——`InitializingBean.afterPropertiesSet` 与自定义 init-method 的先后：

```java
protected void invokeInitMethods(String beanName, Object bean, @Nullable RootBeanDefinition mbd)
        throws Throwable {

    boolean isInitializingBean = (bean instanceof InitializingBean);
    if (isInitializingBean && (mbd == null || !mbd.hasAnyExternallyManagedInitMethod("afterPropertiesSet"))) {
        ...
        ((InitializingBean) bean).afterPropertiesSet();
    }

    if (mbd != null && bean.getClass() != NullBean.class) {
        String[] initMethodNames = mbd.getInitMethodNames();
        if (initMethodNames != null) {
            for (String initMethodName : initMethodNames) {
                if (StringUtils.hasLength(initMethodName) &&
                        !(isInitializingBean && "afterPropertiesSet".equals(initMethodName)) &&
                        !mbd.hasAnyExternallyManagedInitMethod(initMethodName)) {
                    invokeCustomInitMethod(beanName, bean, mbd, initMethodName);
                }
            }
        }
    }
}
```

先接口回调 `afterPropertiesSet()`（Spring 约定的初始化钩子），再执行配方里 `init-method` 指定的业务自定义方法（`invokeCustomInitMethod`，L1898 起，反射调用）。官方推荐后者——让业务类不必依赖 Spring 接口，又一次"非侵入"。

**第 4 步：registerDisposableBeanIfNecessary——为销毁留后门。**

【源码证据】`spring-beans/src/main/java/org/springframework/beans/factory/support/AbstractBeanFactory.java` 第 1935-1954 行：

```java
protected void registerDisposableBeanIfNecessary(String beanName, Object bean, RootBeanDefinition mbd) {
    if (!mbd.isPrototype() && requiresDestruction(bean, mbd)) {
        if (mbd.isSingleton()) {
            // Register a DisposableBean implementation that performs all destruction
            // work for the given bean: DestructionAwareBeanPostProcessors,
            // DisposableBean interface, custom destroy method.
            registerDisposableBean(beanName, new DisposableBeanAdapter(
                    bean, beanName, mbd, getBeanPostProcessorCache().destructionAware));
        }
        else {
            // A bean with a custom scope...
            Scope scope = this.scopes.get(mbd.getScope());
            ...
            scope.registerDestructionCallback(beanName, new DisposableBeanAdapter(...));
        }
    }
}
```

只有 singleton 与自定义 scope 的 bean 会登记销毁回调（prototype 的生死由使用方负责，容器不管），统一打包成 `DisposableBeanAdapter`。

### 2.6.5 销毁：容器关闭时的告别仪式

白话：容器关闭（`context.close()` 或 JVM 注册的 shutdown hook）时，`DefaultSingletonBeanRegistry` 逐个调用登记过的 `DisposableBeanAdapter#destroy()`，它按固定顺序执行三类销毁逻辑。

【源码证据】`spring-beans/src/main/java/org/springframework/beans/factory/support/DisposableBeanAdapter.java` 第 68 行、第 197-231 行：

```java
class DisposableBeanAdapter implements DisposableBean, Runnable, Serializable {
    ...
    @Override
    public void destroy() {
        if (!CollectionUtils.isEmpty(this.beanPostProcessors)) {
            for (DestructionAwareBeanPostProcessor processor : this.beanPostProcessors) {
                processor.postProcessBeforeDestruction(this.bean, this.beanName);   // ① 后置处理器先行
            }
        }

        if (this.invokeDisposableBean) {
            ...
            ((DisposableBean) this.bean).destroy();                                  // ② 接口回调
            ...
        }

        if (this.invokeAutoCloseable) {
            ...
            ((AutoCloseable) this.bean).close();                                     // ③ AutoCloseable
            ...
        }
        ...
        invokeCustomDestroyMethod(destroyMethod);                                    // ④ destroy-method
    }
```

顺序与 `BeanFactory` Javadoc 第 93-98 行承诺一致：`DestructionAwareBeanPostProcessor.postProcessBeforeDestruction`（`@PreDestroy` 注解由 spring-context 的 `CommonAnnotationBeanPostProcessor` 实现此接口）→ `DisposableBean.destroy()` → 配方 `destroy-method`。三销毁钩子全都被**适配器**统一适配——bean 自己只需要选一种声明方式。

## 2.7 本章小结

回望 EJB 时代的问题，本章源码逐一给出了解法：

| EJB 痛点 | spring-beans 中的答案 |
|---|---|
| 侵入式 API | bean 是 POJO，生命周期钩子全靠**可选**接口（Aware/InitializingBean/DisposableBean）或后置处理器 |
| 脱离容器不能测试 | `createBean` 对任何普通类做反射实例化（SimpleInstantiationStrategy），不要求容器环境 |
| 对象自己拉依赖（JNDI lookup） | 容器"推"依赖：populateBean + 后置处理器注入 |
| 单例/配置管理混乱 | BeanDefinition 配方 + scope 分叉 + 三级缓存的单例注册表 |
| 部署描述符死板 | 同一套 BeanDefinition，XML（2.5 节）、注解扫描、代码注册（BeanDefinitionRegistry）都能产出 |

第三章先补齐框架的地基设施（spring-core），第四章将看到 `BeanFactory` 之上如何长出 `ApplicationContext`（事件、国际化、资源加载），以及注解驱动时代 `@ComponentScan`/`@Autowired` 如何复用本章的 BeanDefinition 与后置处理器机制。


---

# 三、地基设施（spring-core）

如果说 Spring 是一座城市，那 spring-core 就是这座城市的地基：水泥、水管、电网。它在任何应用里都排在所有 Spring 依赖的第一位，却几乎不出现"Bean""事务""Controller"这类业务名词——因为它只提供"后面一切模块都要用"的底层能力：**怎么找到资源、怎么读配置、怎么绕过泛型擦除、怎么做类型转换、怎么不加载类就读取类信息、怎么兼容多种运行时**。后面章节会看到：spring-beans 的属性填充依赖类型转换与 ResolvableType，spring-context 的包扫描依赖 ASM 元数据读取，所有模块的配置解析依赖 Environment，AOT/Native 依赖检测器——没有这一层，上层模块寸步难行。

## 3.1 模块定位与子包总览

先看真实目录。spring-core 模块的 `src/main/java/org/springframework` 下只有 8 个顶层包：`aot / asm / cglib / core / javapoet / objenesis / lang / util`（实测 ls）。其中 `asm`、`cglib`、`objenesis`、`javapoet` 是**重打包的第三方字节码/代码生成库**（统一放在 org.springframework 命名空间下，避免与用户 classpath 上的版本冲突），`util` 是通用工具箱，`aot` 和 `lang`（Nullable 语义）是跨模块基础设施，而真正的"核心 API"都住在 `org.springframework.core` 里。

`org.springframework.core` 的直接子包实测如下（`ls` 结果，按主题一句话说明）：

| 子包 | 一句话说明 |
|---|---|
| `annotation` | 注解编程模型：AnnotatedElementUtils、MergedAnnotation、@AliasFor 等，处理注解的查找与"元注解合并" |
| `convert` | 新一代类型转换体系：Converter、ConversionService、TypeDescriptor |
| `env` | 环境抽象：Environment、PropertySource、MutablePropertySources |
| `io` | 资源抽象：Resource、ResourceLoader 及各实现 |
| `type` | 类元数据：ClassMetadata、AnnotationMetadata、MethodMetadata 及 classreading（ASM 读取器） |
| `codec` | 数据编解码的公共 SPI（DataBuffer、Encoder/Decoder，供 spring-webflux 等使用） |
| `log` | 日志桥接（LogMessage 等） |
| `metrics` | 微观察指标抽象（NamingStrategy 等） |
| `retry` | 通用的"重试"小工具（Retryable 等） |
| `serializer` | 通用序列化接口 Serializer/Deserializer |
| `style` | 对象 toString 的美化输出器（默认值/JSON 风格） |
| `task` | 与并发任务相关的支撑类（task 支持的底层工具） |

注意：任务描述里的 `support` 并不是 `core` 的直接子包，而是嵌套在 `core/io/support`（ResourcePatternResolver 所在地）与 `core/convert/support`（ConversionService 实现所在地）下；也不存在 `tools` 子包。

## 3.2 Resource 抽象体系：一个 InputStream 统一所有"东西"

**为什么需要？** Java 原生世界读一个配置文件至少有三种姿势：文件系统 `new File(...)`、类路径 `ClassLoader.getResource(...)`、网络 `new URL(...)`——三者 API 互不兼容，且 classpath 里的资源打成 jar 后根本拿不到 File。Spring 要在 XML、注解、配置类里随意引用任何来源的配置，就必须先造一个统一的"资源描述符"接口。

【源码证据】`spring-core/src/main/java/org/springframework/core/io/Resource.java` + 接口声明与核心方法（59、67、113、132、236、245 行）：

```java
public interface Resource extends InputStreamSource {
	boolean exists();                      // 67 行：资源是否真实存在
	URL getURL() throws IOException;       // 113 行：解析成 URL
	File getFile() throws IOException;     // 132 行：解析成 File（jar 内资源做不到）
	@Nullable String getFilename();        // 236 行：文件名
	String getDescription();               // 245 行：描述信息，用于报错提示
```

其中 `getInputStream()` 继承自父接口 `InputStreamSource`。`ClassPathResource`、`FileSystemResource`、`UrlResource`、`ByteArrayResource` 等实现各自搞定"从哪里流出来"，公共骨架（exists/contentLength 等）则上提到 `AbstractResource`。

**谁来决定"字符串 location 造哪种 Resource"？** 这就是 `ResourceLoader`。它的核心是 `DefaultResourceLoader.getResource(location)` 的前缀分发逻辑：

【源码证据】`spring-core/src/main/java/org/springframework/core/io/DefaultResourceLoader.java` + `getResource`（154-185 行）：

```java
public Resource getResource(String location) {
	Assert.notNull(location, "Location must not be null");
	for (ProtocolResolver protocolResolver : getProtocolResolvers()) {   // 158 行：先走自定义协议
		Resource resource = protocolResolver.resolve(location, this);
		if (resource != null) { return resource; }
	}
	if (location.startsWith("/")) {
		return getResourceByPath(location);                              // 166 行：相对路径
	}
	else if (location.startsWith(CLASSPATH_URL_PREFIX)) {                // 168 行："classpath:"
		return new ClassPathResource(location.substring(CLASSPATH_URL_PREFIX.length()), getClassLoader());
	}
	else if (location.startsWith(CLASSPATH_ALL_URL_PREFIX)) {            // 171 行："classpath*:"（7.1 快照新增）
		return new ClassPathAllResource(location.substring(CLASSPATH_ALL_URL_PREFIX.length()), getClassLoader());
	}
	else {
		try {
			URL url = ResourceUtils.toURL(location);                     // 177 行：尝试当 URL 解析
			return (ResourceUtils.isFileURL(url) ? new FileUrlResource(url) : new UrlResource(url));
		}
		catch (MalformedURLException ex) {
			return getResourceByPath(location);                          // 182 行：不是 URL，退回类路径
		}
	}
}
```

逻辑一句话：**自定义 ProtocolResolver 优先 → `classpath:` → `classpath*:` → 能解析成 URL（含 `http:`、`file:`）的当 UrlResource → 都不是则当类路径相对路径**。所以用户写 `http:` 并不需要框架"注册"什么——URL 语法天然覆盖，这正是"前缀分发"设计的巧妙之处。开头的 `ProtocolResolver` 扩展点则允许应用插入自己的云存储协议（如 `s3:`），无需改框架代码。

单拿一个资源还不够，`classpath*:com/app/**/dao/*.class` 这种 **Ant 风格通配**怎么办？交给 `ResourcePatternResolver` 子接口（`core/io/support/ResourcePatternResolver.java` 第 58 行声明 `extends ResourceLoader`，第 69 行新增 `Resource[] getResources(String locationPattern)`）及其标准实现 `PathMatchingResourcePatternResolver`：

【源码证据】`spring-core/src/main/java/org/springframework/core/io/support/PathMatchingResourcePatternResolver.java` + `getResources`（365-397 行）与 `findPathMatchingResources`（685-687 行）：

```java
public Resource[] getResources(String locationPattern) throws IOException {
	Assert.notNull(locationPattern, "Location pattern must not be null");
	if (locationPattern.startsWith(CLASSPATH_ALL_URL_PREFIX)) {
		String locationPatternWithoutPrefix = locationPattern.substring(CLASSPATH_ALL_URL_PREFIX.length());
		Set<Resource> resources = findAllModulePathResources(locationPatternWithoutPrefix); // 先搜模块路径
		if (getPathMatcher().isPattern(locationPatternWithoutPrefix)) {
			Collections.addAll(resources, findPathMatchingResources(locationPattern));   // 含 ** 通配
		}
		else {
			Collections.addAll(resources, findAllClassPathResources(locationPatternWithoutPrefix));
		}
		return resources.toArray(EMPTY_RESOURCE_ARRAY);
	}
	...
}
```

`findPathMatchingResources` 的思路（685 行起）：先用 `determineRootDir` 把 `classpath:com/app/**/dao/*.class` 拆成**根目录** `classpath:com/app/` 和**剩余子模式** `**/dao/*.class`，对根目录调用 `doFindAllClassPathResources`（436-450 行，本质是 `ClassLoader.getResources()`，能穿透 jar）枚举出所有匹配的 jar/目录，再对每个根目录资源用 `AntPathMatcher` 匹配子模式（jar 内走 `doFindPathMatchingJarResources`）。7.0 起还带 `rootDirCache` 缓存根目录扫描结果。

**ApplicationContext 继承 ResourcePatternResolver 意味着什么？** 意味着任何 IoC 容器天生就是资源解析器：

【源码证据】`spring-context/src/main/java/org/springframework/context/ApplicationContext.java` + 接口声明（59-60 行）：

```java
public interface ApplicationContext extends EnvironmentCapable, ListableBeanFactory, HierarchicalBeanFactory,
		MessageSource, ApplicationEventPublisher, ResourcePatternResolver {
```

所以你在配置类里写 `@ImportResource("classpath*:config/*.xml")`、`@PropertySource("file:app.properties")` 时，容器自己就能把这些字符串变成 Resource——这层能力是从 spring-core 一路继承上来的。

## 3.3 Environment 与 PropertySource：配置的统一视图与优先级

**为什么需要？** 应用的配置来源五花八门：JVM 启动参数（-D）、系统环境变量、application.properties、命令行……框架需要一个统一入口"给我 xyz 的值"，还要规定**同名 key 谁说了算**。Spring 的答案：Environment 是面向用户的读取门面（还管 profiles），底层是一串有名字、有顺序的 PropertySource。

接口关系（均在 `core/env` 实测）：`PropertyResolver`（只会 getProperty/getRequiredProperty）← `Environment`（72 行，`public interface Environment extends PropertyResolver`）← `ConfigurableEnvironment`（72 行，`extends Environment, ConfigurablePropertyResolver`，可增删改 PropertySource）。标准实现是 `StandardEnvironment`：

【源码证据】`spring-core/src/main/java/org/springframework/core/env/StandardEnvironment.java` + 常量与 `customizePropertySources`（57-61、95-101 行）：

```java
public class StandardEnvironment extends AbstractEnvironment {
	/** System environment property source name: {@value}. */
	public static final String SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME = "systemEnvironment";
	/** JVM system properties property source name: {@value}. */
	public static final String SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME = "systemProperties";
	...
	@Override
	protected void customizePropertySources(MutablePropertySources propertySources) {
		propertySources.addLast(
				new PropertiesPropertySource(SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, getSystemProperties()));
		propertySources.addLast(
				new SystemEnvironmentPropertySource(SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, getSystemEnvironment()));
	}
```

即标准环境默认挂两个 PropertySource：`systemProperties`（JVM -D 参数）先进、`systemEnvironment`（Shell 环境变量）后进。谁先谁后为什么重要？看读取端：

【源码证据】`spring-core/src/main/java/org/springframework/core/env/PropertySourcesPropertyResolver.java` + `getProperty`（73-80 行）：

```java
protected <T> @Nullable T getProperty(String key, Class<T> targetValueType, boolean resolveNestedPlaceholders) {
	if (this.propertySources != null) {
		for (PropertySource<?> propertySource : this.propertySources) {
			...
			Object value = propertySource.getProperty(key);
			if (value != null) {
				...
				return convertValueIfNecessary(value, targetValueType);
			}
```

**按列表顺序遍历，第一个命中即返回**——所以顺序就是优先级。维护这个顺序的是 `MutablePropertySources`，它的存储就是一条有序列表：

【源码证据】`spring-core/src/main/java/org/springframework/core/env/MutablePropertySources.java`（43、103-108、113-118 行）：

```java
private final List<PropertySource<?>> propertySourceList = new CopyOnWriteArrayList<>();

public void addFirst(PropertySource<?> propertySource) {   // 103 行：最高优先级
	synchronized (this.propertySourceList) {
		removeIfPresent(propertySource);
		this.propertySourceList.add(0, propertySource);
	}
}
public void addLast(PropertySource<?> propertySource) {    // 113 行：最低优先级
	synchronized (this.propertySourceList) {
		removeIfPresent(propertySource);
		this.propertySourceList.add(propertySource);
	}
}
```

这就是"配置覆盖"的全部秘密：本地 `application.properties` 之所以能覆盖 jar 包内的默认值，是因为容器启动时被 `addFirst`/`addBefore` 插到了前面。Spring Boot 的 `application.yml`、命令行参数等十几个 PropertySource，也全都插在同一条列表上——排序即配置优先级。

## 3.4 ResolvableType 与 MethodParameter：驯服泛型擦除

**为什么需要？** Java 泛型在运行期被擦除，`List<String>` 字段里你只能拿到 `List`。但框架恰恰需要"知道"泛型：`@Autowired List<Validator>` 要按泛型挑 Bean，`ParameterizedTypeReference<List<User>>` 要还原出 `List<User>`。Spring 的答案是 `ResolvableType`——把字段/方法/父类声明里的泛型变量逐层解析成具体类型，支持缓存、支持沿继承链"代入"类型变量。

【源码证据】`spring-core/src/main/java/org/springframework/core/ResolvableType.java` + 入口与取泛型（1222-1225、754-768 行）：

```java
public static ResolvableType forField(Field field) {           // 1222 行：最常用入口
	Assert.notNull(field, "Field must not be null");
	return forType(null, new FieldTypeProvider(field), null);
}
...
public ResolvableType getGeneric(int @Nullable ... indexes) {  // 754 行：取第 N 个泛型参数
	ResolvableType[] generics = getGenerics();
	if (indexes == null || indexes.length == 0) {
		return (generics.length == 0 ? NONE : generics[0]);
	}
	ResolvableType generic = this;
	for (int index : indexes) {
		generics = generic.getGenerics();
		if (index < 0 || index >= generics.length) { return NONE; }
		generic = generics[index];
	}
	return generic;
}
```

以 `List<String> names` 字段为例：`ResolvableType.forField(field)` 得到代表 `List<String>` 的对象；`.getGeneric(0)` 得到 `String` 那一层；`.resolve()` 返回 `Class<String>`。嵌套泛型 `Map<String, List<Integer>>` 用 `getGeneric(1, 0)` 逐层下钻。跨类代入场景用 `getSuperType()`（508-529 行，内部调 `resolved.getGenericSuperclass()` 拿到带实参的父类类型）和 `as(Class)`（485 行，"把我当作某个父类/接口的视图"）——例如 `class UserRepo extends BaseRepo<User>`，对 `BaseRepo` 字段解析时，类型变量 `T` 会被沿 `superType` 链替换成 `User`。官方测试就是最直白的说明书：

【源码证据】`spring-core/src/test/java/org/springframework/core/ResolvableTypeTests.java` + `forField`（180-184 行）及 305 行断言：

```java
Field field = Fields.class.getField("charSequenceList");
ResolvableType type = ResolvableType.forField(field);
assertThat(type.getType()).isEqualTo(field.getGenericType());
...
assertThat(type.getGeneric(0).resolve()).isEqualTo(String.class);   // 305 行
```

配套工具：`MethodParameter`（第 65 行起）是"参数/返回值元数据的统一载体"——把 `Method`/`Constructor` + 参数下标 + 嵌套层级打包成一个对象，注解、泛型、名字都从它身上查，spring-webmvc 的 `@RequestParam` 解析、spring-beans 的依赖注入都拿着它到处走。`GenericTypeResolver` 提供"静态方法版"的简化泛型解析，`ClassUtils` 提供 getDefaultClassLoader、isPresent 等高频反射判断。四者共同构成框架的"反射中间层"。

## 3.5 类型转换双体系：PropertyEditor 的历史包袱与 core.convert 的新世界

**这是什么？** 把 `"123"` 变成 `Integer`、把 `"a,b,c"` 变成数组——属性填充、@Value 注入、配置绑定全都离不开字符串到目标类型的转换。Spring 存在两套体系：

1. **老的 java.beans.PropertyEditor**（1997 年 JavaBeans 规范）：只有 `String -> Object` 方向，有状态、线程不安全，纯历史包袱；但为了兼容老 API（甚至 spring-core 自己的 `io/ResourceEditor.java` 就是它的实现）不能扔。
2. **新的 core.convert 体系**（3.0 引入）：核心是函数式接口 `Converter<S,T>`：

【源码证据】`spring-core/src/main/java/org/springframework/core/convert/converter/Converter.java`（37-46 行）：

```java
@FunctionalInterface
public interface Converter<S, T extends @Nullable Object> {
	T convert(S source);
```

扩展点还有 `ConverterFactory`（批量造同一族转换器，如 String -> 所有 Number）、`ConditionalGenericConverter`（`core/convert/converter/ConditionalGenericConverter.java` 第 33 行 `interface ConditionalGenericConverter extends GenericConverter, ConditionalConverter`，先判断"能不能转"再转，用于数组/集合/Map 等复合转换）。`GenericConversionService` 负责注册与查找转换器，`DefaultConversionService` 开箱注册了一大批：

【源码证据】`spring-core/src/main/java/org/springframework/core/convert/support/DefaultConversionService.java` + `addDefaultConverters`（89-104 行，节选）：

```java
public static void addDefaultConverters(ConverterRegistry converterRegistry) {
	addScalarConverters(converterRegistry);      // 90 行：StringToNumber、StringToEnum、UUID 等
	addCollectionConverters(converterRegistry);  // 91 行：Array/Collection/Map/Stream 互转
	converterRegistry.addConverter(new ByteBufferConverter((ConversionService) converterRegistry));
	converterRegistry.addConverter(new DateToInstantConverter());
	converterRegistry.addConverter(new ObjectToObjectConverter());
	converterRegistry.addConverter(new FallbackObjectToStringConverter());
	converterRegistry.addConverter(new ObjectToOptionalConverter((ConversionService) converterRegistry));
```

标量组里有 `StringToNumberConverterFactory`、`StringToEnumConverterFactory`、`StringToUUIDConverter` 等（139-185 行）；集合组里有 `StringToCollectionConverter`、`CollectionToCollectionConverter` 等（114-137 行）——这就是你往 `@Value("${list}") List<Integer>` 里注入逗号分隔字符串能成功的原因。

**两套体系如何共存？** 靠桥接器 `TypeConverterDelegate`（在 spring-beans 里）：新体系优先，老 Editor 兜底。

【源码证据】`spring-beans/src/main/java/org/springframework/beans/TypeConverterDelegate.java` + `convertIfNecessary`（114-135 行）：

```java
public <T> @Nullable T convertIfNecessary(..., @Nullable TypeDescriptor typeDescriptor) ... {
	// Custom editor for this type?
	PropertyEditor editor = this.propertyEditorRegistry.findCustomEditor(requiredType, propertyName);
	ConversionService conversionService = this.propertyEditorRegistry.getConversionService();
	if (editor == null && conversionService != null && newValue != null && typeDescriptor != null) {
		TypeDescriptor sourceTypeDesc = TypeDescriptor.forObject(newValue);
		if (conversionService.canConvert(sourceTypeDesc, typeDescriptor)) {
			try {
				return (T) conversionService.convert(newValue, sourceTypeDesc, typeDescriptor);  // 新体系
			}
			catch (ConversionFailedException ex) { /* fallback 到老体系 */ }
```

调用方正是属性填充与依赖注入：`AbstractNestablePropertyAccessor` 持有 delegate（107 行 `this.typeConverterDelegate = new TypeConverterDelegate(this)`），而 **@Value 解析**走的是 `DefaultListableBeanFactory` 的依赖解析路径——先 `${}` 占位符求值（`resolveEmbeddedValue`），再类型转换：

【源码证据】`spring-beans/src/main/java/org/springframework/beans/factory/support/DefaultListableBeanFactory.java` + `doResolveDependency` 内（1678-1691 行）：

```java
if (value instanceof String strValue) {
	String resolvedValue = resolveEmbeddedValue(strValue);      // 解析 ${...}，即 @Value 的占位符
	...
	value = evaluateBeanDefinitionString(resolvedValue, bd);
}
TypeConverter converter = (typeConverter != null ? typeConverter : getTypeConverter());
try {
	return converter.convertIfNecessary(value, type, descriptor.getTypeDescriptor());  // 走桥接
```

`descriptor.getTypeDescriptor()` 返回的 TypeDescriptor 携带泛型信息——又回到 3.4 节的 ResolvableType。可见地基模块之间是环环相扣的。

## 3.6 类元数据与字节码：不加载类，就知道你身上有什么注解

**为什么必须"不加载"？** `@ComponentScan` 要扫描成千上万个 class，逐个 `Class.forName()` 的代价是：触发目标类的静态初始化块、触发其依赖类的加载、可能抛出扫描期异常，且巨慢。Spring 的方案是直接用 **ASM 读 .class 文件的字节码**（字节码常量池里本来就有"我有哪些注解、实现哪些接口"的信息）。

【源码证据】`spring-core/src/main/java/org/springframework/core/type/classreading/SimpleMetadataReader.java`（38-51 行）：

```java
final class SimpleMetadataReader implements MetadataReader {
	private static final int PARSING_OPTIONS =
			(ClassReader.SKIP_DEBUG | ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
	...
	SimpleMetadataReader(Resource resource, @Nullable ClassLoader classLoader) throws IOException {
		SimpleAnnotationMetadataReadingVisitor visitor = new SimpleAnnotationMetadataReadingVisitor(classLoader);
		getClassReader(resource).accept(visitor, PARSING_OPTIONS);
		this.resource = resource;
		this.annotationMetadata = visitor.getMetadata();
	}
```

三个 `SKIP_` 常量表明：调试信息、方法体、栈帧全部跳过——只看类/方法/注解的"目录"，不看"正文"。`SimpleAnnotationMetadataReadingVisitor`（`SimpleAnnotationMetadataReadingVisitor.java` 第 44 行 `extends ClassVisitor`）在回调里收集类名（48 行 `className`）、注解（62 行 `List<MergedAnnotation<?>> annotations`）、方法元数据（64 行 `declaredMethods`）等，全程零类加载。

工厂方法只有两行：

【源码证据】`spring-core/src/main/java/org/springframework/core/type/classreading/SimpleMetadataReaderFactory.java` + `getMetadataReader`（59-62 行）：

```java
@Override
public MetadataReader getMetadataReader(Resource resource) throws IOException {
	return new SimpleMetadataReader(resource, getResourceLoader().getClassLoader());
}
```

每次都重新解析太浪费，于是 `CachingMetadataReaderFactory` 在外面套一层缓存：本地缓存默认 256 条（`CachingMetadataReaderFactory.java` 第 43 行 `DEFAULT_CACHE_LIMIT = 256`），若资源加载器是 `DefaultResourceLoader` 则直接复用它**共享级**缓存（82-84 行 `this.metadataReaderCache = defaultResourceLoader.getResourceCache(MetadataReader.class)`），跨组件复用、随 ResourceLoader 生命周期统一清理。下游一句话带过：spring-context 的扫描器就是它的直接用户——`ClassPathScanningCandidateComponentProvider` 第 267 行 `this.metadataReaderFactory = new CachingMetadataReaderFactory(resourceLoader)`，第 464 行 `getMetadataReaderFactory().getMetadataReader(resource)` 读完后用 TypeFilter 判断"你是不是 @Component"。

## 3.7 环境适配与演进：检测器 + 适配，一套代码跑遍所有运行时

Spring 要同时跑在普通 JVM、Kotlin、GraalVM Native、协程场景下，它的策略不是到处写 if-else 判断字符串，而是提供一组**探测器 + 适配器**：

- **NativeDetector**（GraalVM）：GraalVM 编译 native image 时会注入系统属性 `org.graalvm.nativeimage.imagecode`，Spring 只在静态字段里读一次它。
  【源码证据】`spring-core/src/main/java/org/springframework/core/NativeDetector.java`（30-32、40-42 行）：`private static final @Nullable String imageCode = System.getProperty("org.graalvm.nativeimage.imagecode");`，`public static boolean inNativeImage() { return inNativeImage; }`。CGLIB 代理、反射调用等在 native 下行为不同，全靠这个开关分流。
- **KotlinDetector**：静态块里用 `ClassUtils.forName("kotlin.Metadata", classLoader)`（`KotlinDetector.java` 57 行）探测类路径上有没有 Kotlin，结果缓存成 `KOTLIN_PRESENT`（81 行）。`KotlinReflectionParameterNameDiscoverer` 则是"有 Kotlin 才工作"的适配器——`getParameterNames(Method)`（44 行）借助 kotlin-reflect 读出参数名，弥补 JVM 编译时丢参数名的老问题。
- **CoroutinesUtils**：Kotlin suspend 函数对 Java 侧是"带 Continuation 参数的普通方法"，返回 Deferred。适配层把它们拉平到 Reactor：`CoroutinesUtils.java` 第 73-74 行 `deferredToMono(Deferred<T> source)` 与第 99 行 `invokeSuspendingFunction(Method, Object, ...)`，让 spring-webflux 能统一按 Reactive 流处理 Kotlin 协程端点。

另外一处体现"演进"的是**空安全**：Spring 7.0 把 API 级空安全注解统一切换到了 jspecify 标准。证据俯拾皆是：`Resource.java` 第 30 行 `import org.jspecify.annotations.Nullable;`，`MethodParameter.java` 第 41 行同样，`core` 包根下的 `Nullness.java` 也是配套产物——这解释了为什么本章引用的代码里方法返回值普遍写作 `@Nullable String`。

## 3.8 AOT 支持总览

Spring 6 起的 AOT（提前编译）基石就在 spring-core：`org.springframework.aot` 顶层包内含 `AotDetector` 与 `hint`（RuntimeHints、ReflectionHints、ResourceHints、ProxyHints——声明"native 下需要哪些反射/资源/代理"的元数据模型）、`generate`（代码生成基建）等。运行期是否启用 AOT 产物由它判断：

【源码证据】`spring-core/src/main/java/org/springframework/aot/AotDetector.java`（39、50-52 行）：

```java
public static final String AOT_ENABLED = "spring.aot.enabled";
...
public static boolean useGeneratedArtifacts() {
	return (inNativeImage || SpringProperties.getFlag(AOT_ENABLED));
}
```

注意分工：spring-core 只提供 **hint 模型与开关**；真正的"把 BeanFactory 变成生成代码"的重活在别处——spring-beans 的 `org.springframework.beans.factory.aot`（BeanRegistration 代码生成，实测含 `AutowiredArgumentsCodeGenerator.java` 等）与 spring-context 的 `org.springframework.context.aot`（实测含 `ContextAotProcessor.java`、`ApplicationContextAotGenerator.java`）。测试基建则独立成 `spring-core-test` 模块（`org.springframework.aot.agent` 包：`RuntimeHintsAgent.java`、`RuntimeHintsRecorder.java` 等，可在 JVM 测试期用 Java agent 记录真实触发的反射调用，反推 hint 是否声明齐全）。

## 3.9 小结：为什么这些设施是一切的前提

回头看：Resource 让容器的"配置来源"可以是指任何地方；Environment + PropertySource 的有序列表让所有配置有了统一优先级语义；ResolvableType/MethodParameter 让依赖注入能"按泛型匹配"；双转换体系让字符串世界与 Java 类型世界无缝对接；ASM 元数据读取让 @ComponentScan 不付出类加载的代价；检测器让同一份框架代码跑遍 JVM/Native/Kotlin。后面的 spring-beans、spring-context 乃至 spring-boot 风格的自动配置，本质都是在这套地基上做"编排"——地基不牢，上层全是空中楼阁。


---

# 四、应用上下文（spring-context）

## 4.1 先说白话：BeanFactory 是发动机，ApplicationContext 是整车

第二章我们看到，`BeanFactory`（默认实现 `DefaultListableBeanFactory`）已经能完成"读 BeanDefinition → 实例化 → 注入 → 初始化"的完整流程。那为什么还要一个 ApplicationContext？打个比方：**BeanFactory 是发动机，ApplicationContext 是整车**——发动机只负责把 Bean 造出来，而整车还要有方向盘（国际化消息）、仪表盘（事件广播）、油箱（资源加载）、导航（环境 Profile/属性），以及一键启动按钮（`refresh()` 模板方法）。

`spring-context` 模块就是"整车"的组装车间。它做两件事：
1. **升级容器**：在 BeanFactory 外面套一层 `ApplicationContext`，叠加事件、国际化、资源、环境四大能力，并定义了标准启动流程 `refresh()`；
2. **落地注解编程模型**：让 `@Configuration`、`@ComponentScan`、`@Bean`、`@Autowired`、`@EventListener` 这些注解真正生效——而这一切的执行者，还是普通的后置处理器，没有任何魔法。

## 4.2 ApplicationContext 接口继承体系

先看接口定义本身：

【源码证据】spring-context/src/main/java/org/springframework/context/ApplicationContext.java#接口声明（59-60 行）
```java
public interface ApplicationContext extends EnvironmentCapable, ListableBeanFactory, HierarchicalBeanFactory,
        MessageSource, ApplicationEventPublisher, ResourcePatternResolver {
```

一个接口同时继承六个接口，每个对应一项整车能力：

| 继承自 | 能力 | 一句话 |
|---|---|---|
| `ListableBeanFactory` | 容器本体 | 能按类型列出所有 Bean，`getBean` 依然可用 |
| `HierarchicalBeanFactory` | 父子容器 | Web 应用里父 Context 共享、子 Context 独立 |
| `MessageSource` | 国际化 | `getMessage(code, args, locale)` 按语言取文案 |
| `ApplicationEventPublisher` | 事件 | `publishEvent(...)` 解耦组件间通知 |
| `ResourcePatternResolver`（扩展自 `ResourceLoader`） | 资源 | `getResource`/`getResources("classpath*:...")` 统一访问文件 |
| `EnvironmentCapable` | 环境 | 拿到 `Environment`，查属性、判 Profile |

类 Javadoc（32-46 行）也原文列出了这五项能力，并特别指出：ApplicationContext 会**自动检测并回调** `ApplicationContextAware`、`ResourceLoaderAware`、`ApplicationEventPublisherAware`、`MessageSourceAware` 类型的 Bean——这是它比"裸 BeanFactory"贴心之处。

初学者常见误区：以为 `AnnotationConfigApplicationContext` "实现了自己的容器"。其实它内部就是持有一个 `DefaultListableBeanFactory`（`GenericApplicationContext` 的构造器里 new 出来），`getBeanFactory()` 只是把内部发动机暴露出来。ApplicationContext 从来不替代 BeanFactory，而是包着它提供增值服务。

## 4.3 refresh()：容器的启动总流程

### 4.3.1 全景图

`AbstractApplicationContext#refresh()` 是整个 Spring 容器启动的总导演，是模板方法模式的经典应用。7.1.0-SNAPSHOT 中它位于 582-662 行。与经典教材的"12 步"相比，**阶段顺序完全一致**，但有几处以源码为准的差异（版本归属经 git 标签实证，汇总见 1.6 节），我们先给"阶段 → 方法 → 作用"总表：

| # | 阶段 | 方法（AbstractApplicationContext 行号） | 作用 |
|---|---|---|---|
| 0 | 加锁 | `refresh()` 583-585 | `startupShutdownLock`（ReentrantLock）串行化启动/关闭 |
| 1 | 启动准备 | `prepareRefresh()` 668-703 | 记录 startupDate、置 active、校验必需属性、暂存早期监听器/事件 |
| 2 | 获取新 BeanFactory | `obtainFreshBeanFactory()` 720-723 | 让子类 refresh 内部工厂（加载/解析 BeanDefinition） |
| 3 | 工厂标配 | `prepareBeanFactory()` 730-776 | 设置类加载器、注册 Aware 处理器、resolvable 依赖、环境单例 |
| 4 | 子类钩子 | `postProcessBeanFactory()` 787-788 | 模板方法，Web 等子类扩展用 |
| 5 | 工厂后置处理器 | `invokeBeanFactoryPostProcessors()` 795-805 | 委托 delegate 执行 BFPP/**BDRPP**，`ConfigurationClassPostProcessor` 在此触发 |
| 6 | 注册 Bean 后置处理器 | `registerBeanPostProcessors()` 812-814 | 把 BPP 类型的 Bean 提前实例化并注册 |
| 7 | 国际化 | `initMessageSource()` 821-846 | 找 `messageSource` Bean，没有就注册空实现 |
| 8 | 事件广播器 | `initApplicationEventMulticaster()` 854-871 | 找 `applicationEventMulticaster` Bean，没有就 new `SimpleApplicationEventMulticaster` |
| 9 | 子类刷新 | `onRefresh()` 907-909 | 模板方法（如 Web 子类初始化特殊 Bean） |
| 10 | 注册监听器 | `registerListeners()` 915-936 | 注册静态监听器 + `ApplicationListener` 类型 Bean 名，补发早期事件 |
| 11 | 实例化单例 | `finishBeanFactoryInitialization()` 943-996 | 冻结配置、`preInstantiateSingletons()` 实例化全部非懒加载单例 |
| 12 | 收尾 | `finishRefresh()` 1003-1018 | 清缓存 → `initLifecycleProcessor` → 启动 Lifecycle Bean → 发布 `ContextRefreshedEvent` |

【源码证据】spring-context/src/main/java/org/springframework/context/support/AbstractApplicationContext.java#refresh（582-626 行，节选）
```java
public void refresh() throws BeansException, IllegalStateException {
    this.startupShutdownLock.lock();                       // 6.2 起：ReentrantLock 取代 synchronized
    try {
        this.startupShutdownThread = Thread.currentThread();
        StartupStep contextRefresh = this.applicationStartup.start("spring.context.refresh");
        // Prepare this context for refreshing.
        prepareRefresh();
        // Tell the subclass to refresh the internal bean factory.
        ConfigurableListableBeanFactory beanFactory = obtainFreshBeanFactory();
        // Prepare the bean factory for use in this context.
        prepareBeanFactory(beanFactory);
        try {
            // Allows post-processing of the bean factory in context subclasses.
            postProcessBeanFactory(beanFactory);
            ...
            invokeBeanFactoryPostProcessors(beanFactory);
            registerBeanPostProcessors(beanFactory);
            ...
```

与经典 12 步的差异（以实际源码为准）：
- **锁**：旧版用 `synchronized(this)`，6.2 改为成员字段 `private final Lock startupShutdownLock = new ReentrantLock()`（215 行）+ `startupShutdownThread` 记录当前线程（585 行），对虚拟线程更友好，关闭时可用 `tryLock(100ms)` 轮询（1097 行）。
- **失败回滚更完整**：catch 块（628-652 行）在销毁单例之前，**先停掉已启动的 Lifecycle Bean**（635-642 行 `this.lifecycleProcessor.stop()`，7.0 引入），避免启动中途失败留下"悬空资源"。
- **`initLifecycleProcessor` 的位置**：教材常把它算作第 12.5 步，实际源码里它一直都在 `finishRefresh()` 内部（7.1 快照 1011 行）——这是教材表达与源码的差异，不是版本变化。
- **预实例化支持后台并行**：`finishBeanFactoryInitialization` 开头的 `beanFactory.prepareSingletonBootstrap()`（945 行，6.2 引入的能力）与 `bootstrapExecutor` 的装配（948-952 行）——若容器里有名为 `bootstrapExecutor` 的 Bean，标记了 `backgroundInit=true`（`AbstractBeanDefinition` 175 行字段）的单例将交给它（虚拟线程池）并行初始化，`dependsOn` 依赖仍在主线程先行创建。

### 4.3.2 重点阶段展开

**① prepareRefresh：开机自检**（668-703 行）

```java
this.startupDate = System.currentTimeMillis();
this.closed.set(false);
this.active.set(true);
...
initPropertySources();                       // 子类可替换占位 property source（如 ServletContext）
getEnvironment().validateRequiredProperties(); // 校验标记为必需的属性是否可解析
...
this.earlyApplicationEvents = new LinkedHashSet<>(); // multicaster 就绪前先攒着事件
```
`earlyApplicationEvents` 是个小彩蛋：在广播器初始化之前发布的事件不会丢，而是暂存，等第 10 步补发（929-935 行）。

**② obtainFreshBeanFactory：把工厂造出来**（720-723 行）
```java
protected ConfigurableListableBeanFactory obtainFreshBeanFactory() {
    refreshBeanFactory();
    return getBeanFactory();
}
```
对 `AnnotationConfigApplicationContext` 来说，`refreshBeanFactory()` 就是 `GenericApplicationContext` 里的几行（spring-context/.../support/GenericApplicationContext.java#refreshBeanFactory，296-302 行）：用 `compareAndSet` 保证**只允许 refresh 一次**，不允许像老 XML 容器那样反复刷新。BeanDefinition 的实际装载发生在更早——构造 `AnnotatedBeanDefinitionReader`/`ClassPathBeanDefinitionScanner` 时就已经写入（见 4.4 节）。

**③ prepareBeanFactory：给发动机装"整车线束"**（730-776 行），这是理解"Context 增值"的关键：

```java
beanFactory.setBeanClassLoader(getClassLoader());
beanFactory.setBeanExpressionResolver(new StandardBeanExpressionResolver(...)); // 支持 #{...} SpEL
beanFactory.addPropertyEditorRegistrar(new ResourceEditorRegistrar(this, getEnvironment()));
// Configure the bean factory with context callbacks.
beanFactory.addBeanPostProcessor(new ApplicationContextAwareProcessor(this)); // 回调 xxAware 接口
beanFactory.ignoreDependencyInterface(EnvironmentAware.class);
beanFactory.ignoreDependencyInterface(EmbeddedValueResolverAware.class);
beanFactory.ignoreDependencyInterface(ResourceLoaderAware.class);
beanFactory.ignoreDependencyInterface(ApplicationEventPublisherAware.class);
beanFactory.ignoreDependencyInterface(MessageSourceAware.class);
beanFactory.ignoreDependencyInterface(ApplicationContextAware.class);
beanFactory.ignoreDependencyInterface(ApplicationStartupAware.class);          // 6.0 起已列入
// BeanFactory interface not registered as resolvable type in a plain factory.
beanFactory.registerResolvableDependency(BeanFactory.class, beanFactory);
beanFactory.registerResolvableDependency(ResourceLoader.class, this);
beanFactory.registerResolvableDependency(ApplicationEventPublisher.class, this);
beanFactory.registerResolvableDependency(ApplicationContext.class, this);
beanFactory.addBeanPostProcessor(new ApplicationListenerDetector(this));      // 拦截器 754 行
```
三个动作值得记住：
- **注册 `ApplicationContextAwareProcessor`**（737 行）：在 Bean 初始化前回调 7 类 `xxAware` 接口，把容器自己"塞"给 Bean；
- **`ignoreDependencyInterface`**（738-744 行）：让这些 Aware 类型**不参与普通按类型自动注入**——否则注入的会是还没初始化完的引用；Aware 回调交给后置处理器走另一条路；
- **`registerResolvableDependency`**（748-751 行）：让 `@Autowired BeanFactory/ApplicationContext` 这类注入即使容器里没有同名 Bean 也能拿到"容器本身"。

之后（757-775 行）：检测 `loadTimeWeaver` 注册织入处理器；把 `environment`、`systemProperties`、`systemEnvironment`、`applicationStartup` 四个单例注册进工厂，让你能 `@Autowired Environment`。

**④ invokeBeanFactoryPostProcessors：注解世界的引爆点**（795-805 行）
```java
PostProcessorRegistrationDelegate.invokeBeanFactoryPostProcessors(beanFactory, getBeanFactoryPostProcessors());
```
真正的排序逻辑在 `spring-context/src/main/java/org/springframework/context/support/PostProcessorRegistrationDelegate.java`（68-209 行）。规则是：**先执行 `BeanDefinitionRegistryPostProcessor`（BDRPP），再执行普通 `BeanFactoryPostProcessor`（BFPP）**；同类内部按 `PriorityOrdered → Ordered → 无序` 三轮执行：

```java
// Invoke BeanDefinitionRegistryPostProcessors first, if any.        （84 行）
...
// First, invoke the BeanDefinitionRegistryPostProcessors that implement PriorityOrdered.（107-119 行）
    beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
    if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
        currentRegistryProcessors.add(beanFactory.getBean(ppName, BeanDefinitionRegistryPostProcessor.class));
    }
...
invokeBeanDefinitionRegistryPostProcessors(currentRegistryProcessors, registry, ...);
```
为什么要多轮循环（134-150 行）？因为 BDRPP 可以**注册新的 BDRPP**，所以要"while 直到没有新增"。`ConfigurationClassPostProcessor` 实现了 `PriorityOrdered`（其类声明 152-153 行：`implements BeanDefinitionRegistryPostProcessor, ..., PriorityOrdered`），因此在第一轮就被 `getBean` 实例化并执行——**注解配置的全部 BeanDefinition 都是在这一刻被"翻译"进容器的**。之后普通 BFPP 阶段（187-204 行）再执行 `PropertySourcesPlaceholderConfigurer` 等占位符处理器。

**⑤ registerBeanPostProcessors**（812-814 行 → delegate 211 行起）：把 `AutowiredAnnotationBeanPostProcessor` 等 BPP 从 BeanDefinition 变成实例并注册，同样按 PriorityOrdered/Ordered 排序。此后创建的每个 Bean 都会被这些 BPP 拦截（Autowired 就靠它）。

**⑥ initMessageSource / ⑦ initApplicationEventMulticaster：装配两个"标准件"**（821-871 行）。都以"用户自定义 Bean 名优先，否则注册默认实现"的模式：`messageSource` 不存在就注册 `DelegatingMessageSource`（838-841 行）；`applicationEventMulticaster` 不存在就 `new SimpleApplicationEventMulticaster(beanFactory)`（864-865 行）。这就是著名的 **i18n Bean 名与广播器 Bean 名约定**（常量 `MESSAGE_SOURCE_BEAN_NAME` 在 156 行、`APPLICATION_EVENT_MULTICASTER_BEAN_NAME` 在 166 行）。

**⑧ registerListeners：把监听器挂上广播器**（915-936 行）：先挂手工 `addApplicationListener` 的静态监听器，再按类型 `getBeanNamesForType(ApplicationListener.class, true, false)` 找监听器 **Bean 名**（注意只查名不初始化，把创建时机留给第 11 步），最后补发早期事件。

**⑨ finishBeanFactoryInitialization：大点名**（943-996 行）：
```java
beanFactory.prepareSingletonBootstrap();                 // 945 行，6.2 引入
if (beanFactory.containsBean(BOOTSTRAP_EXECUTOR_BEAN_NAME) && ...) {
    beanFactory.setBootstrapExecutor(beanFactory.getBean(BOOTSTRAP_EXECUTOR_BEAN_NAME, Executor.class));
}
...
if (!beanFactory.hasEmbeddedValueResolver()) {           // 964 行：兜底 ${} 解析器
    beanFactory.addEmbeddedValueResolver(strVal -> getEnvironment().resolvePlaceholders(strVal));
}
...
beanFactory.setTempClassLoader(null);
beanFactory.freezeConfiguration();                       // 992 行：冻结 BeanDefinition
beanFactory.preInstantiateSingletons();                  // 995 行
```
`preInstantiateSingletons` 在 spring-beans 模块（spring-beans/src/main/java/org/springframework/beans/factory/support/DefaultListableBeanFactory.java，1102-1151 行）：遍历全部 BeanDefinition，对非抽象单例逐个 `getBean`；6.2 起若 `mbd.isBackgroundInit()` 且配置了 bootstrapExecutor，则返回 `CompletableFuture` 攒起来最后 `allOf().join()`（1117-1134 行）。全部单例就绪后，统一回调 **`SmartInitializingSingleton.afterSingletonsInstantiated()`**（1144-1147 行）——注意它与 `InitializingBean` 的区别：后者在每个 Bean 自己初始化完就调，前者要等**所有**单例都齐了才调。

**⑩ finishRefresh：点火成功，广播世界**（1003-1018 行）：
```java
resetCommonCaches();            // 1005 行：清反射/注解缓存
clearResourceCaches();          // 1008 行：清 ASM 元数据等资源缓存
initLifecycleProcessor();       // 1011 行
getLifecycleProcessor().onRefresh();   // 1014 行：启动 SmartLifecycle Bean
publishEvent(new ContextRefreshedEvent(this));  // 1017 行
```
`onRefresh()`（DefaultLifecycleProcessor 295-315 行）会 `startBeans(true)` 启动所有 `isAutoStartup()==true` 的 SmartLifecycle；最后一条 `ContextRefreshedEvent` 是 Spring Boot 打印"启动完成"类日志的时机基础。

## 4.4 注解编程模型在源码中的落地

白话先行：**注解本身只是"标签"，真正干活的是一小组内部 Bean**。下面按一条完整链路追源码。

### 4.4.1 起点：AnnotationConfigApplicationContext 构造 → 注册内部后置处理器

【源码证据】spring-context/src/main/java/org/springframework/context/annotation/AnnotationConfigApplicationContext.java#构造器（68-93 行）
```java
public AnnotationConfigApplicationContext() {
    this.reader = new AnnotatedBeanDefinitionReader(this);
    this.scanner = new ClassPathBeanDefinitionScanner(this);
}
public AnnotationConfigApplicationContext(Class<?>... componentClasses) {
    this();
    register(componentClasses);
    refresh();
}
```
注意：`new AnnotationConfigApplicationContext(AppConfig.class)` = 创建 reader/scanner → 注册配置类为 BeanDefinition → `refresh()`。关键在 reader 构造器的最后一行：

【源码证据】spring-context/src/main/java/org/springframework/context/annotation/AnnotatedBeanDefinitionReader.java#构造器（85-91 行）
```java
public AnnotatedBeanDefinitionReader(BeanDefinitionRegistry registry, Environment environment) {
    ...
    this.registry = registry;
    this.conditionEvaluator = new ConditionEvaluator(registry, environment, null);
    AnnotationConfigUtils.registerAnnotationConfigProcessors(this.registry);  // ★
}
```

【源码证据】spring-context/src/main/java/org/springframework/context/annotation/AnnotationConfigUtils.java#registerAnnotationConfigProcessors（143-202 行，节选）
```java
if (!registry.containsBeanDefinition(CONFIGURATION_ANNOTATION_PROCESSOR_BEAN_NAME)) {
    RootBeanDefinition def = new RootBeanDefinition(ConfigurationClassPostProcessor.class);
    ...
    beanDefs.add(registerPostProcessor(registry, def, CONFIGURATION_ANNOTATION_PROCESSOR_BEAN_NAME));
}
if (!registry.containsBeanDefinition(AUTOWIRED_ANNOTATION_PROCESSOR_BEAN_NAME)) {
    RootBeanDefinition def = new RootBeanDefinition(AutowiredAnnotationBeanPostProcessor.class);
    ...
}
// Check for Jakarta Annotations support, and if present add the CommonAnnotationBeanPostProcessor.
if (JAKARTA_ANNOTATIONS_PRESENT && ...) { ... CommonAnnotationBeanPostProcessor ... }
...
if (!registry.containsBeanDefinition(EVENT_LISTENER_PROCESSOR_BEAN_NAME)) {
    RootBeanDefinition def = new RootBeanDefinition(EventListenerMethodProcessor.class); ... }
if (!registry.containsBeanDefinition(EVENT_LISTENER_FACTORY_BEAN_NAME)) {
    RootBeanDefinition def = new RootBeanDefinition(DefaultEventListenerFactory.class); ... }
```
这一下注册了 6 个关键内部 BeanDefinition（都标记 `ROLE_INFRASTRUCTURE`，207-213 行），逐个一句话：
1. **`ConfigurationClassPostProcessor`**（Bean 名 `internalConfigurationAnnotationProcessor`）：BeanFactory 级处理器，解析 `@Configuration`/`@ComponentScan`/`@Bean`/`@Import` 的总指挥；
2. **`AutowiredAnnotationBeanPostProcessor`**：处理 `@Autowired`/`@Value` 注入（Bean 级 BPP）；
3. **`CommonAnnotationBeanPostProcessor`**：处理 `@Resource`、`@PostConstruct`/`@PreDestroy`（classpath 有 jakarta 注解才注册，171-175 行）；
4. **`PersistenceAnnotationBeanPostProcessor`**：处理 JPA `@PersistenceContext`（有 JPA 依赖才注册，178-190 行）；
5. **`EventListenerMethodProcessor`**：`SmartInitializingSingleton`，把 `@EventListener` 方法转成监听器（第 12 步之后执行）；
6. **`DefaultEventListenerFactory`**：上面那位的助手，实际创建 `ApplicationListenerMethodAdapter`。

另外 146-154 行还顺手把依赖比较器换成 `AnnotationAwareOrderComparator`、把 `@Lazy` 感知的 `ContextAnnotationAutowireCandidateResolver` 装进工厂。

### 4.4.2 ClassPathBeanDefinitionScanner：@ComponentScan 的执行者

`scanner` 扫描分两步：`scan()` → `doScan()`（ClassPathBeanDefinitionScanner 254-300 行）。`doScan` 骨架：

```java
for (String basePackage : basePackages) {
    Set<BeanDefinition> candidates = findCandidateComponents(basePackage);   // 279 行
    for (BeanDefinition candidate : candidates) {
        ... // 解析 @Scope、生成 bean 名、处理 @Lazy 等公共注解、applyScopedProxyMode
        registerBeanDefinition(definitionHolder, this.registry);            // 295 行
    }
}
```
`findCandidateComponents` 在父类 `ClassPathScanningCandidateComponentProvider`（312-322 行）：如果存在编译期索引（`componentsIndex != null` 且过滤器受支持）就走 `addCandidateComponentsFromIndex`，否则走默认的 `scanCandidateComponents`：

【源码证据】.../annotation/ClassPathScanningCandidateComponentProvider.java#scanCandidateComponents（446-478 行，节选）
```java
String packageSearchPattern = ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX +
        resolveBasePackage(basePackage) + '/' + this.resourcePattern;   // classpath*:com/example/**/*.class
Resource[] resources = getResourcePatternResolver().getResources(packageSearchPattern);
for (Resource resource : resources) {
    ...
    MetadataReader metadataReader = getMetadataReaderFactory().getMetadataReader(resource); // ASM 读 .class
    if (isCandidateComponent(metadataReader)) {                 // include/exclude 过滤 + @Conditional
        ScannedGenericBeanDefinition sbd = new ScannedGenericBeanDefinition(metadataReader);
        ...
        if (isCandidateComponent(sbd)) { candidates.add(sbd); } // 独立且具体（或含 @Lookup 的抽象类）
```
两个关键点：
- **ASM 元数据**：`MetadataReader` 基于 ASM 直接读字节码，**不加载类**到 JVM，扫描百万级 classpath 也不会触发海量类加载；
- **候选类判定有两层**：`isCandidateComponent(MetadataReader)`（533-546 行）先做 exclude/include 过滤器匹配并评估 `@Conditional`；`isCandidateComponent(AnnotatedBeanDefinition)`（571-575 行）再要求 `metadata.isIndependent() && (metadata.isConcrete() || 含 @Lookup 方法)`——即排除内部类、接口、抽象类。

扫描结束后 `scan()` 还会调一次 `AnnotationConfigUtils.registerAnnotationConfigProcessors`（261 行），保证即使纯扫描路径下内部处理器也齐。

### 4.4.3 ConfigurationClassPostProcessor：注解配置的"大脑"

它身兼三职：`BeanDefinitionRegistryPostProcessor`（5.4.2/refresh 第 5 步被触发）、`PriorityOrdered`（最先执行）、AOT 处理器（152-153 行）。入口：

【源码证据】.../annotation/ConfigurationClassPostProcessor.java#postProcessBeanDefinitionRegistry / processConfigBeanDefinitions（304-316 行、389-491 行，节选）
```java
public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
    ... // 幂等保护
    processConfigBeanDefinitions(registry);
}

public void processConfigBeanDefinitions(BeanDefinitionRegistry registry) {
    List<BeanDefinitionHolder> configCandidates = new ArrayList<>();
    for (String beanName : registry.getBeanDefinitionNames()) {     // 找出所有"配置类候选"
        if (ConfigurationClassUtils.checkConfigurationClassCandidate(beanDef, this.metadataReaderFactory)) {
            configCandidates.add(new BeanDefinitionHolder(beanDef, beanName));
        }
    }
    ...
    do {                                        // do-while：新解析出的配置类还要再解析
        parser.parse(candidates);               // 452 行：构建 ConfigurationClass 模型
        parser.validate();
        ...
        this.reader.loadBeanDefinitions(configClasses);  // 464 行：模型 → BeanDefinition
        ...
    } while (!candidates.isEmpty());            // 491 行
}
```
注意这个 **do-while 循环**：配置类扫描/import 又产生新配置类，就继续解析，直到收敛。

**parse 阶段的完整处理顺序**在 `ConfigurationClassParser#doProcessConfigurationClass`（305-405 行）里一目了然——处理 `@PropertySource`（314-325 行）→ `@ComponentScan`（327-361 行）→ `@Import`（364 行）→ `@ImportResource`（366-376 行）→ `@Bean` 方法（378-385 行）→ 接口默认方法（388 行）→ 父类递归（391-401 行）。

`@ComponentScan` 在 346-359 行立即执行：`this.componentScanParser.parse(...)` 内部 new 一个 `ClassPathBeanDefinitionScanner` 完成 4.4.2 的扫描；扫出来的 Bean 如果本身也是配置类，则在 356-358 行**递归 parse**——这就是"扫描是递归的"。

`@Bean` 方法的注册在 load 阶段：

【源码证据】.../annotation/ConfigurationClassBeanDefinitionReader.java#loadBeanDefinitionsForConfigurationClass（132-154 行）
```java
if (configClass.isImported()) {
    registerBeanDefinitionForImportedConfigurationClass(configClass);   // @Import 的类本身也注册
}
for (BeanMethod beanMethod : configClass.getBeanMethods()) {
    loadBeanDefinitionsForBeanMethod(beanMethod);                        // 每个 @Bean 方法 → BeanDefinition
}
loadBeanDefinitionsFromImportedResources(configClass.getImportedResources());       // XML 兼容
loadBeanDefinitionsFromImportBeanDefinitionRegistrars(configClass.getImportBeanDefinitionRegistrars());
loadBeanDefinitionsFromBeanRegistrars(configClass.getBeanRegistrars());             // 7.0 新增 BeanRegistrar
```

**`@Import` 的三种身份判定**在 `processImports`（ConfigurationClassParser 581-652 行），Spring Boot 的 `@Enable*` 模块机制全靠它：

```java
for (SourceClass candidate : importCandidates) {
    if (candidate.isAssignable(ImportSelector.class)) {                    // 595 行
        ImportSelector selector = ParserStrategyUtils.instantiateClass(...);
        if (selector instanceof DeferredImportSelector deferredImportSelector) {
            this.deferredImportSelectorHandler.handle(configClass, deferredImportSelector);  // 605 行，推迟到所有配置类之后（Boot 的自动装配）
        } else {
            String[] importClassNames = selector.selectImports(currentSourceClass.getMetadata());
            processImports(configClass, currentSourceClass, importSourceClasses, filter, false); // 递归
        }
    }
    else if (candidate.isAssignable(BeanRegistrar.class)) {                // 613 行，7.0 新增
        ... configClass.addBeanRegistrar(metadata.getClassName(), registrar);
    }
    else if (candidate.isAssignable(ImportBeanDefinitionRegistrar.class)) { // 622 行
        ... configClass.addImportBeanDefinitionRegistrar(registrar, currentSourceClass.getMetadata()); // 缓存，load 阶段手动注册
    }
    else {
        // Candidate class not an ImportSelector or ImportBeanDefinitionRegistrar ->
        // process it as an @Configuration class                           // 632-636 行
        this.importStack.registerImport(currentSourceClass.getMetadata(), candidate.getMetadata().getClassName());
        processConfigurationClass(candidate.asConfigClass(configClass), filter);
    }
}
```
即：`ImportSelector` → 调它的 `selectImports` 拿类名递归导入；`ImportBeanDefinitionRegistrar` → 存起来在 load 阶段编程式注册（MyBatis `@MapperScan` 即此类）；普通类 → 直接当配置类递归处理。入口处还有 `isChainedImportOnStack`（588 行）做循环导入检测。

### 4.4.4 CGLIB 增强：为什么 @Bean 方法互调不会 new 出多个对象

`ConfigurationClassPostProcessor#postProcessBeanFactory`（324-338 行）在 BDRPP 回调之后再次被调用（`BeanFactoryPostProcessor` 回调），做两件事：`enhanceConfigurationClasses(beanFactory)` + 注册 `ImportAwareBeanPostProcessor`。增强只针对 **full 模式**（有 @Bean 方法且 proxyBeanMethods 未关）的配置类（544-561 行筛出 `CONFIGURATION_CLASS_FULL`），然后替换 BeanDefinition 的类：

```java
// enhanceConfigurationClasses 570-582 行
beanDef.setAttribute(AutoProxyUtils.PRESERVE_TARGET_CLASS_ATTRIBUTE, Boolean.TRUE);
Class<?> configClass = beanDef.getBeanClass();
Class<?> enhancedClass = enhancer.enhance(configClass, this.beanClassLoader);
if (configClass != enhancedClass) { ... beanDef.setBeanClass(enhancedClass); }
```

**为什么必须增强？** 考虑：
```java
@Configuration
public class AppConfig {
    @Bean public DataSource ds() { ... }
    @Bean public JdbcTemplate jdbc() { return new JdbcTemplate(ds()); }  // 调用了 ds()
}
```
如果不增强，`ds()` 就是一次普通 Java 方法调用，**每次都 new 一个新 DataSource**，单例语义被破坏。CGLIB 子类拦截 @Bean 方法后，改为"去容器里按名取"。`ConfigurationClassEnhancer` 生成的子类实现 `EnhancedConfiguration` 接口并被塞入 `beanFactory` 字段（`BeanFactoryAwareGeneratorStrategy`，newEnhancer 172-188 行），核心拦截逻辑：

【源码证据】.../annotation/ConfigurationClassEnhancer.java#BeanMethodInterceptor#intercept（351-401 行，节选）
```java
ConfigurableBeanFactory beanFactory = getBeanFactory(enhancedConfigInstance);
String beanName = BeanAnnotationHelper.determineBeanNameFor(beanMethod, beanFactory);
... // FactoryBean 特殊处理
if (isCurrentlyInvokedFactoryMethod(beanMethod)) {
    // The factory is calling the bean method in order to instantiate and register the bean
    // (i.e. via a getBean() call) -> invoke the super implementation of the method to actually
    // create the bean instance.
    ...
    return cglibMethodProxy.invokeSuper(enhancedConfigInstance, beanMethodArgs);  // 398 行
}
return resolveBeanReference(beanMethod, beanMethodArgs, beanFactory, beanName);   // 401 行
```
关键判断就一句：**当前这次调用是不是容器为了创建这个 Bean 而发起的**（`isCurrentlyInvokedFactoryMethod`）。是 → 放行父类方法体真正创建；否（说明是用户在另一个 @Bean 方法里"顺手"调用）→ `resolveBeanReference` 走 `beanFactory.getBean(beanName)`（428 行）从容器拿现成单例。这就是 `proxyBeanMethods=true`（full）与 `=false`（lite，不增强、启动更快）的语义分界。

## 4.5 事件机制：观察者模式的一等公民实现

白话：事件机制是"发布-订阅"解耦。事件是一个普通对象，广播器负责投递，监听器负责消费。

**发布端**：`AbstractApplicationContext#publishEvent(Object)`（415-461 行的内部委托）——如果不是 `ApplicationEvent`，就包成 `PayloadApplicationEvent`：
```java
applicationEvent = new PayloadApplicationEvent<>(this, event, payloadType);   // 433 行
...
if (this.earlyApplicationEvents != null) { this.earlyApplicationEvents.add(applicationEvent); }
else if (this.applicationEventMulticaster != null) {
    this.applicationEventMulticaster.multicastEvent(applicationEvent, eventType);  // 449 行
}
if (this.parent != null) { ... }   // 事件还会向父容器传播
```
**`PayloadApplicationEvent` 泛型事件**（spring-context/.../PayloadApplicationEvent.java，64-75 行）：任意 POJO 载荷都能发布，泛型信息由 `ResolvableType.forClassWithGenerics(getClass(), this.payloadType)`（73-75 行）保留，监听器可按 `PayloadApplicationEvent<OrderCreated>` 的泛型精确匹配。

**广播端**：`SimpleApplicationEventMulticaster` 只有两个可配置字段 `taskExecutor`（54 行）、`errorHandler`（56 行）：

【源码证据】.../event/SimpleApplicationEventMulticaster.java#multicastEvent / invokeListener（137-175 行，节选）
```java
public void multicastEvent(ApplicationEvent event, @Nullable ResolvableType eventType) {
    ResolvableType type = (eventType != null ? eventType : ResolvableType.forInstance(event));
    Executor executor = getTaskExecutor();
    for (ApplicationListener<?> listener : getApplicationListeners(event, type)) {  // 按事件类型+泛型筛选
        if (executor != null && listener.supportsAsyncExecution()) {   // 141 行，6.1 引入的判定
            try { executor.execute(() -> invokeListener(listener, event)); }
            catch (RejectedExecutionException ex) { invokeListener(listener, event); }  // 关闭期兜底
        } else {
            invokeListener(listener, event);                           // 默认路径：同步
        }
    }
}
protected void invokeListener(ApplicationListener<?> listener, ApplicationEvent event) {
    ErrorHandler errorHandler = getErrorHandler();
    if (errorHandler != null) {
        try { doInvokeListener(listener, event); }
        catch (Throwable err) { errorHandler.handleError(err); }
    } else { doInvokeListener(listener, event); }
}
```
**默认没有 executor，所以事件是在发布者线程同步串行广播的**——监听器抛异常会直接打断发布者，除非设置 errorHandler。想异步需自己定义 `applicationEventMulticaster` Bean 并注入 executor。`supportsAsyncExecution()` 是 `ApplicationListener` 的 default 方法（ApplicationListener.java 59 行），6.1 引入，用于过滤"不适合异步执行"的监听器（如 `TransactionalApplicationListener`）。

**@EventListener 收集链**：`EventListenerMethodProcessor` 实现了 `SmartInitializingSingleton`，等**所有单例初始化完**（refresh 第 11 步末尾）才工作——afterSingletonsInstantiated（108-150 行）遍历所有 Bean，processBean（152-202 行）反射找 `@EventListener` 方法，然后交给 `EventListenerFactory`：
```java
for (EventListenerFactory factory : factories) {
    if (factory.supportsMethod(method)) {
        ApplicationListener<?> applicationListener = factory.createApplicationListener(beanName, targetType, methodToUse);  // 186-187 行
        ...
        context.addApplicationListener(applicationListener);   // 191 行
        break;
    }
}
```
`DefaultEventListenerFactory` 产出的就是 **`ApplicationListenerMethodAdapter`**（event/ApplicationListenerMethodAdapter.java，类声明 75 行，持有 `Method` 字段 85 行）：它把"某个 Bean 的某个方法"适配成标准 `ApplicationListener`，`onApplicationEvent` → `processEvent`（198-200、267 行），内部按方法参数的 `ResolvableType`（470 行 getResolvableType）做泛型匹配后才反射调用。

## 4.6 国际化与生命周期

### 4.6.1 MessageSource 层级查找

`AbstractMessageSource#getMessageInternal`（spring-context/.../support/AbstractMessageSource.java，205-251 行）体现了完整的查找链：

```java
if (!isAlwaysUseMessageFormat() && ObjectUtils.isEmpty(args)) {
    String message = resolveCodeWithoutArguments(code, locale);  // 无参快速路径（子类实现，如读 .properties）
    if (message != null) { return message; }
} else {
    ...
    MessageFormat messageFormat = resolveCode(code, locale);     // 有参路径
    if (messageFormat != null) { ... return messageFormat.format(argsToUse); }
}
...
// Not found -> check parent, if any.
return getMessageFromParent(code, argsToUse, locale);            // 249 行：父 MessageSource 兜底
```
即：**本实现查找 → 公共消息 → 父 MessageSource**，最后仍找不到则抛 `NoSuchMessageException`（除非 `useCodeAsDefaultMessage=true`）。`ResourceBundleMessageSource`（同目录，注释见 45-64 行）基于 JDK `ResourceBundle` 读 `basename_zh_CN.properties` 这类文件，且**自己缓存** ResourceBundle 与 MessageFormat，比 JDK 原生缓存更可控。refresh 第 7 步 `initMessageSource` 还负责把父 Context 的 MessageSource 挂为 parent（826-831 行），实现 Web 场景"子上下文覆盖、父上下文兜底"的文案分层。

### 4.6.2 Lifecycle / SmartLifecycle 与 DefaultLifecycleProcessor

白话：有些 Bean 不是"造完就完"（如消息消费者要 start 才收消息），Spring 把 start/stop 抽象为 `Lifecycle`；`SmartLifecycle`（`getPhase()`、`isAutoStartup()`、`stop(Runnable)`，接口 default 方法见 SmartLifecycle.java 100-101 行）再加上"启动顺序"与"自动启动"两个语义。refresh 第 12 步 `finishRefresh` 里 `getLifecycleProcessor().onRefresh()` 触发启动：

【源码证据】.../support/DefaultLifecycleProcessor.java#onRefresh / startBeans / doStart（295-315、365-413 行，节选）
```java
public void onRefresh() {
    ...
    try { startBeans(true); }          // autoStartupOnly=true
    ...
    this.running = true;
}
private void startBeans(boolean autoStartupOnly) {
    Map<String, Lifecycle> lifecycleBeans = getLifecycleBeans();
    Map<Integer, LifecycleGroup> phases = new TreeMap<>();       // 按 phase 排序的分组
    lifecycleBeans.forEach((beanName, bean) -> {
        if (!autoStartupOnly || isAutoStartupCandidate(beanName, bean)) {   // 只启动 isAutoStartup 的
            int startupPhase = getPhase(bean);
            phases.computeIfAbsent(startupPhase, phase -> new LifecycleGroup(...)).add(beanName, bean);
        }
    });
    if (!phases.isEmpty()) { phases.values().forEach(LifecycleGroup::start); }  // phase 从小到大依次启动
}
private void doStart(...) {
    ...
    for (String dependency : dependenciesForBean) { doStart(lifecycleBeans, dependency, ...); } // 先启依赖
    if (!bean.isRunning() && (!autoStartupOnly || toBeStarted(beanName, bean))) {
        if (futures != null) { futures.add(CompletableFuture.runAsync(() -> doStart(beanName, bean), getBootstrapExecutor())); } // 6.2 起：可异步（bootstrapExecutor）
        else { doStart(beanName, bean); }
    }
}
```
要点：`isAutoStartupCandidate`（383-387 行）在 refresh 场景只挑 `SmartLifecycle && isAutoStartup()` 的 Bean；phase 升序启动（小 phase 先起、晚停），同一 phase 内还会先启动 `dependsOn` 依赖；容器 close 时反向 stop。Spring Boot 里 SmartLifecycle 被广泛用于优雅启停（如 Kafka/RocketMQ 监听容器）。

## 4.7 spring-context-support 与 spring-context-indexer 的分工

这两个"兄弟模块"一个管运行时集成，一个管编译期加速：

- **spring-context-support**：为常见第三方库提供"支持类"胶水，运行时按需引入。包结构（实际目录）：`org.springframework.cache`（caffeine、jcache、transaction）、`org.springframework.mail`（javamail 实现 `MailSender`）、`org.springframework.scheduling`（quartz 调度支持）、`org.springframework.ui`（freemarker 模板）。它不含核心容器逻辑，只是让这些第三方组件能以 Spring Bean 的方式使用。
- **spring-context-indexer**：编译期注解处理器。`@Indexed` 注解本体在 spring-context 模块（`org.springframework.stereotype.Indexed`），处理器在 indexer 模块：`spring-context-indexer/src/main/java/org/springframework/context/index/processor/CandidateComponentsIndexer.java`（49 行 `public class CandidateComponentsIndexer implements Processor`）在编译期生成 `META-INF/spring.components` 索引文件；运行时 `ClassPathScanningCandidateComponentProvider#findCandidateComponents`（312-321 行）检测到索引后**跳过全 classpath 扫描**，直接 `index.getCandidateTypes(basePackage, stereotype)` 按表查类名——这是 4.4.2 里那条"索引快路径"。适合类路径巨大的大型应用；普通应用收益有限。

## 4.8 本章小结

- ApplicationContext 继承 `ListableBeanFactory + MessageSource + ApplicationEventPublisher + ResourcePatternResolver + EnvironmentCapable`，是 BeanFactory 的"整车版"；内部仍持有一个 DefaultListableBeanFactory。
- `refresh()` 是启动总流程，7.1 快照与经典 12 步阶段顺序一致；相对旧版的变化：ReentrantLock 启动锁与后台预实例化（backgroundInit）为 **6.2** 引入，refresh 失败先停 Lifecycle Bean 为 **7.0** 引入（版本对比详见 1.6 节）。
- 注解编程模型没有魔法：`AnnotationConfigUtils.registerAnnotationConfigProcessors` 注册 6 个内部处理器 Bean；`ConfigurationClassPostProcessor` 在 `invokeBeanFactoryPostProcessors` 阶段被 delegate 优先触发，解析 @ComponentScan/@Bean/@Import（含 ImportSelector/ImportBeanDefinitionRegistrar/7.0 新增 BeanRegistrar）；`ClassPathBeanDefinitionScanner` 用 ASM 元数据完成不加载类的扫描。
- 配置类 CGLIB 增强的意义是保住 @Bean 单例语义：`BeanMethodInterceptor` 用 `isCurrentlyInvokedFactoryMethod` 区分"容器创建"与"用户互调"，后者改走 `beanFactory.getBean`。
- 事件默认同步广播（multicastEvent 无 executor 时走主线程）；@EventListener 由 EventListenerMethodProcessor 在所有单例就绪后（SmartInitializingSingleton）统一收集成 ApplicationListenerMethodAdapter。
- MessageSource 三级查找（自身 → common → parent）；SmartLifecycle 按 phase 升序自动启动，DefaultLifecycleProcessor.onRefresh 在 finishRefresh 中被调用。


---

# 五、AOP 与表达式语言（spring-aop / spring-expression）

前两章我们看到 IoC 容器负责"造对象、注入对象"，本章回答两个新问题：**如何在不改动业务类字节码的前提下给任意 Bean 统一加上日志、事务、缓存等能力**（AOP），以及**框架如何用一条字符串表达式灵活地取值**（SpEL）。这两个模块组合起来，正是 Spring"声明式编程"体验的技术底座。

## 5.1 为什么需要 AOP

白话版：假设订单服务、支付服务、库存服务都要记日志、开事务、查权限。把这些逻辑写进每个方法，代码会成倍膨胀；用继承把日志写在父类里，业务类就被迫绑定在一个继承体系上，而且"所有 public 方法前打日志"这种横跨多个类的规则无法表达；用工具类（`LogUtil.info(...)`）封装，虽然复用了代码，但**调用点仍散落在业务代码里**，忘了写就漏了。

AOP（面向切面编程）的思路是：把"在哪些方法上做什么"整体声明成一个**切面（Aspect）**，由框架在运行时生成代理对象，在调用真正业务方法前后插入这些逻辑。业务类保持纯 POJO。

Spring 自己就是 AOP 的头号用户——`@Transactional`、`@Async`、`@Cacheable` 这些注解的底层实现全部是"一个 Advisor/Interceptor + 自动代理"：

- `@Transactional` → `org.springframework.transaction.interceptor.BeanFactoryTransactionAttributeSourceAdvisor`（spring-tx 模块，内部持有 `TransactionAttributeSourcePointcut` 与 `TransactionInterceptor`）
- `@Async` → `org.springframework.scheduling.annotation.AsyncAnnotationAdvisor`（spring-context 模块，内部包装 `AnnotationAsyncExecutionInterceptor`）
- `@Cacheable` → `org.springframework.cache.interceptor.BeanFactoryCacheOperationSourceAdvisor` / `CacheInterceptor`（spring-context 模块）

也就是说，理解了 spring-aop，就理解了 Spring 大部分"魔法注解"的工作原理。

## 5.2 从概念到接口：AOP 术语在 Spring 里的样子

【源码证据】`spring-aop/src/main/java/org/springframework/aop/Pointcut.java` + 接口定义，行 33-53：

```java
public interface Pointcut {

    ClassFilter getClassFilter();

    MethodMatcher getMethodMatcher();

    /** Canonical Pointcut instance that always matches. */
    Pointcut TRUE = TruePointcut.INSTANCE;

}
```

概念到接口的映射如下：

| AOP 概念 | Spring 接口 | 白话解释 |
|---|---|---|
| 连接点 JoinPoint | AOP Alliance `MethodInvocation`（Spring 里由 `ReflectiveMethodInvocation` 实现） | 一次方法调用本身 |
| 通知 Advice | `org.aopalliance.aop.Advice` 标记接口 + 五类子接口 | "要做什么" |
| 切点 Pointcut | `org.springframework.aop.Pointcut` | "在哪里做" |
| 顾问 Advisor | `org.springframework.aop.Advisor`（`PointcutAdvisor` 组合两者） | 通知 + 切点的完整单元 |
| 目标 Target | `org.springframework.aop.TargetSource` | 被代理的真实对象 |
| 代理配置 | `org.springframework.aop.framework.AdvisedSupport` | 代理的全部配置承载 |

**Advice 五类**：`BeforeAdvice`（前）、`AfterReturningAdvice`（返回后）、`ThrowsAdvice`（抛异常后）、`AfterAdvice`（后置）、`Around`（环绕，通过 AOP Alliance 的 `org.aopalliance.intercept.MethodInterceptor` 表达）。Spring 内部最终把所有通知统一成 AOP Alliance 的 `MethodInterceptor`——这是后面责任链能"串起来"的前提。

**切点 = 类过滤 + 方法匹配** 两级。`ClassFilter` 是单方法函数接口（`spring-aop/.../ClassFilter.java` 行 48：`boolean matches(Class<?> clazz)`）；`MethodMatcher` 分静态匹配与动态匹配两次（`MethodMatcher.java`）：

```java
boolean matches(Method method, Class<?> targetClass);          // 静态：行 72
boolean isRuntime();                                            // 行 83
boolean matches(Method method, Class<?> targetClass,
        @Nullable Object... args);                              // 动态：行 99
```

`isRuntime()` 返回 false 时只做静态匹配；返回 true 时每次调用前还会用实参再做一次三参匹配（例如"参数 > 100 时才织入"这类规则）。

**Advisor 接口**只要求 `getAdvice()`（`Advisor.java` 行 55），常用实现是 `PointcutAdvisor` 体系。而 `@execution(...)` 风格的 AspectJ 表达式切点由 `org.springframework.aop.aspectj.AspectJExpressionPointcut` 实现：它借助 AspectJ 的 `PointcutExpression` 做阴影匹配（【源码证据】`AspectJExpressionPointcut.java` 行 273-306 `matches(Class)` 调用 `obtainPointcutExpression().couldMatchJoinPointsInType(targetClass)`；行 315-331 `matches(Method,...)` 处理 `alwaysMatches/neverMatches/maybe` 三态）。

`TargetSource`（`TargetSource.java` 行 45/55/66）定义 `getTargetClass()`、`isStatic()`、`getTarget()`：每次调用都可以返回不同目标（池化、热替换），默认用 `SingletonTargetSource`。`AdvisedSupport` 则持有 interfaces、advisors、targetSource、`proxyTargetClass` 等全部代理配置，是 `ProxyFactory` 的父类。

## 5.3 代理创建与选择：JDK 还是 CGLIB

`ProxyFactory.getProxy()` 一行就把事情办了（`ProxyFactory.java` 行 96-98）：

```java
public Object getProxy() {
    return createAopProxy().getProxy();
}
```

`createAopProxy()` 定义在父类 `ProxyCreatorSupport`（行 101-105）：`return getAopProxyFactory().createAopProxy(this);`——即把整份 `AdvisedSupport` 配置交给 `DefaultAopProxyFactory` 做二选一。

【源码证据】`spring-aop/src/main/java/org/springframework/aop/framework/DefaultAopProxyFactory.java` `createAopProxy` 行 59-76（原样引用）：

```java
@Override
public AopProxy createAopProxy(AdvisedSupport config) throws AopConfigException {
    if (config.isOptimize() || config.isProxyTargetClass() || !config.hasUserSuppliedInterfaces()) {
        Class<?> targetClass = config.getTargetClass();
        if (targetClass == null && config.getProxiedInterfaces().length == 0) {
            throw new AopConfigException("TargetSource cannot determine target class: " +
                    "Either an interface or a target is required for proxy creation.");
        }
        if (targetClass == null || targetClass.isInterface() ||
                Proxy.isProxyClass(targetClass) || ClassUtils.isLambdaClass(targetClass)) {
            return new JdkDynamicAopProxy(config);
        }
        return new ObjenesisCglibAopProxy(config);
    }
    else {
        return new JdkDynamicAopProxy(config);
    }
}
```

选择逻辑翻译成白话：**只要设了 `optimize` 或 `proxyTargetClass`，或者用户压根没指定任何接口，就走"类代理"分支；但目标本身是接口/已经是 JDK 代理/lambda 时仍退回 JDK 代理；其余情况（目标类 + 指定了接口）用 JDK 动态代理。**

两种代理的限制：
- **JDK 动态代理**：生成的代理类实现了目标的所有接口，**只能拦截接口里声明的方法**。类上自己定义的方法、非 public 方法，通过代理对象是调不到的。类内部 `this.xxx()` 自调用更不会走代理。
- **CGLIB 子类代理**：生成目标类的子类，**不能代理 final 类**（无法继承），**final 方法无法被拦截**（无法重写），private 方法同样拦不到；且代理实例的属性不会走你的构造器初始化流程（Spring 用 Objenesis 绕过构造器）。

## 5.4 JDK 动态代理：JdkDynamicAopProxy

JDK 动态代理的本质是：运行时生成一个实现目标接口的代理类 `$Proxy0`，它把所有方法调用转发给一个 `InvocationHandler`。Spring 让 `JdkDynamicAopProxy` 自己实现 `InvocationHandler`（类声明行 69：`final class JdkDynamicAopProxy implements AopProxy, InvocationHandler, Serializable`）。

【源码证据】`JdkDynamicAopProxy.java` `getProxy` 行 117-122：

```java
@Override
public Object getProxy(@Nullable ClassLoader classLoader) {
    if (logger.isTraceEnabled()) {
        logger.trace("Creating JDK dynamic proxy: " + this.advised.getTargetSource());
    }
    return Proxy.newProxyInstance(determineClassLoader(classLoader), this.cache.proxiedInterfaces, this);
}
```

`invoke` 是整个 Spring AOP 运行时的大门，行 163-251，关键片段：

```java
if (!this.cache.equalsDefined && AopUtils.isEqualsMethod(method)) {
    // The target does not implement the equals(Object) method itself.
    return equals(args[0]);                                   // 行 171-174
}
else if (!this.cache.hashCodeDefined && AopUtils.isHashCodeMethod(method)) {
    return hashCode();                                        // 行 175-178
}
...
target = targetSource.getTarget();                            // 行 199
Class<?> targetClass = (target != null ? target.getClass() : null);

List<Object> chain = this.advised.getInterceptorsAndDynamicInterceptionAdvice(method, targetClass); // 行 203

if (chain.isEmpty()) {
    // 没有任何通知命中：跳过 MethodInvocation，直接反射调用目标
    @Nullable Object[] argsToUse = AopProxyUtils.adaptArgumentsIfNecessary(method, args);
    retVal = AopUtils.invokeJoinpointUsingReflection(target, method, argsToUse);   // 行 211-212
}
else {
    // 有通知：创建 MethodInvocation 并沿拦截器链推进
    MethodInvocation invocation =
            new ReflectiveMethodInvocation(proxy, target, method, args, targetClass, chain);  // 行 216-217
    retVal = invocation.proceed();                            // 行 219
}
```

要点：
1. **equals/hashCode 特殊处理**：除非目标接口自己声明了 equals/hashCode，否则由代理自己响应（行 171-178），避免把这两个"对象身份"方法交给拦截器链。
2. **链为空的快路径**：没有通知命中就纯反射调用，连 `MethodInvocation` 对象都不创建（行 205-213 注释写明这个动机）。
3. **自引用返回值修正**：方法返回 `this` 时把目标对象换成代理对象返回（行 223-231），保证调用方拿到的仍是代理。

## 5.5 CGLIB 代理：CglibAopProxy 与 ObjenesisCglibAopProxy

CGLIB 走的是"生成子类字节码"路线。`CglibAopProxy.buildProxy`（行 171-241）配置一个 `Enhancer`：

```java
enhancer.setSuperclass(proxySuperClass);                                     // 行 201
enhancer.setInterfaces(AopProxyUtils.completeProxiedInterfaces(this.advised)); // 行 202
enhancer.setNamingPolicy(SpringNamingPolicy.INSTANCE);                       // 行 203
...
Callback[] callbacks = getCallbacks(rootClass);                              // 行 210
enhancer.setCallbackFilter(filter);                                          // 行 218
```

`getCallbacks`（行 323-393）准备了一组回调，第 0 号就是核心的 `DynamicAdvisedInterceptor`（行 330），通过 `ProxyCallbackFilter.accept(Method)`（行 826 起）决定每个方法落到哪个回调——finalize 用 NO_OVERRIDE、equals/hashCode 用专用拦截器、Advised 接口方法派发给配置对象、被通知方法用 `AOP_PROXY`。

与 JDK 版同构的执行核心在 `DynamicAdvisedInterceptor#intercept`（行 710-751）：

```java
target = targetSource.getTarget();                                            // 行 722
Class<?> targetClass = (target != null ? target.getClass() : null);
List<Object> chain = this.advised.getInterceptorsAndDynamicInterceptionAdvice(method, targetClass); // 行 724
...
if (chain.isEmpty()) {
    // 行 733-734：直接反射调用目标
    retVal = AopUtils.invokeJoinpointUsingReflection(target, method, argsToUse);
}
else {
    // 行 738：同样的 ReflectiveMethodInvocation + proceed()
    retVal = new ReflectiveMethodInvocation(proxy, target, method, args, targetClass, chain).proceed();
}
return processReturnType(proxy, target, method, args, retVal);                // 行 740
```

可以看到：**无论 JDK 还是 CGLIB，最终都汇入同一条 `ReflectiveMethodInvocation.proceed()` 责任链**，这是两种代理行为一致的原因。

**final 方法怎么办**：拦截不了，只能警告。`doValidateClass`（行 283-321）扫描父类方法：

```java
if (Modifier.isFinal(mod)) {
    if (logger.isWarnEnabled() && Modifier.isPublic(mod)) {
        ...
        logger.warn("Public final method [" + method + "] cannot get proxied via CGLIB, " +
                "consider removing the final marker or using interface-based JDK proxies.");  // 行 301-302
    }
    ...
}
```

final 方法调用会**绕过通知直达原实现**（子类无法重写），这就是 `@Transactional` 方法加 final 会静默失效的原因。另外行 249 `enhancer.setInterceptDuringConstruction(false)` 关闭了构造期间的回调；代理实例本身由 `ObjenesisCglibAopProxy`（行 39，`extends CglibAopProxy`）借助 Objenesis 在行 61 `createProxyClassAndInstance` 中不调用目标构造器创建，构造器参数通过 `setConstructorArguments`（行 143-153）单独传入。

## 5.6 拦截器链执行：ReflectiveMethodInvocation#proceed()

这是 Spring AOP 最精巧的一段递归。`ReflectiveMethodInvocation`（`framework/ReflectiveMethodInvocation.java`）持有 `interceptorsAndDynamicMethodMatchers` 列表和一个从 -1 起步的游标（行 89：`private int currentInterceptorIndex = -1;`）。

【源码证据】`proceed()` 行 154-181（原样引用）：

```java
public @Nullable Object proceed() throws Throwable {
    // We start with an index of -1 and increment early.
    if (this.currentInterceptorIndex == this.interceptorsAndDynamicMethodMatchers.size() - 1) {
        return invokeJoinpoint();                       // ① 链走完了：反射调用目标方法
    }

    Object interceptorOrInterceptionAdvice =
            this.interceptorsAndDynamicMethodMatchers.get(++this.currentInterceptorIndex);  // ② 游标前移取下一个
    if (interceptorOrInterceptionAdvice instanceof InterceptorAndDynamicMethodMatcher dm) {
        // ③ 动态匹配器：现场用本次实参再判一次
        Class<?> targetClass = (this.targetClass != null ? this.targetClass : this.method.getDeclaringClass());
        if (dm.matcher().matches(this.method, targetClass, this.arguments)) {
            return dm.interceptor().invoke(this);
        }
        else {
            // Dynamic matching failed. Skip this interceptor and invoke the next in the chain.
            return proceed();                           // ④ 跳过该拦截器，继续下一个
        }
    }
    else {
        // ⑤ 普通拦截器：把"自己（MethodInvocation）"传进去
        return ((MethodInterceptor) interceptorOrInterceptionAdvice).invoke(this);
    }
}
```

**责任链图解**（以两个环绕拦截器为例）：

```
proxy.foo()
  └─ ReflectiveMethodInvocation.proceed()
       index=-1 → 取[0] LogInterceptor.invoke(this)
                     │  前置日志
                     │  mi.proceed()
                     │    index=0 → 取[1] TxInterceptor.invoke(this)
                     │                  │  开事务
                     │                  │  mi.proceed()
                     │                  │    index=1 == size-1 → invokeJoinpoint()
                     │                  │                          反射调用 target.foo()
                     │                  │  提交事务
                     │  后置日志
       返回值原路回传（调用栈天然就是一个"环绕"栈）
```

每个拦截器在 `invoke(this)` 里先做事、再调用 `mi.proceed()` 放行——递归调用栈让"进入顺序 = Before 顺序、返回顺序 = After 逆序"自动成立，这正是 AspectJ 语义能映射到方法拦截上的原因。

**ExposeInvocationInterceptor：把调用暴露到 ThreadLocal**。`interceptor/ExposeInvocationInterceptor.java` 行 92-101：

```java
public @Nullable Object invoke(MethodInvocation mi) throws Throwable {
    MethodInvocation oldInvocation = invocation.get();
    invocation.set(mi);              // 行 61-62：private static final ThreadLocal<MethodInvocation>
    try {
        return mi.proceed();
    }
    finally {
        invocation.set(oldInvocation);
    }
}
```

AspectJ 风格的通知（如 `@Before` 方法想拿 `JoinPoint`）需要随时读取"当前是哪次调用"，而通知方法签名里并没有这个参数，于是 Spring 让这条拦截器**永远排在链首**（行 104-106：`getOrder()` 返回 `HIGHEST_PRECEDENCE + 1`；`aspectj/AspectJProxyUtils.java` 行 61-62 `advisors.add(0, ExposeInvocationInterceptor.ADVISOR)`），后续代码通过静态方法 `currentInvocation()`（行 71-82）从 ThreadLocal 取。

**Advice → MethodInterceptor 的适配**。经典 Advice 接口不是拦截器，由 `framework/adapter` 下的适配类包一层，全部转发 `mi.proceed()`：

- `MethodBeforeAdviceInterceptor`（行 55-58）：先 `advice.before(...)` 再 `mi.proceed()`；
- `AfterReturningAdviceInterceptor`（行 55-59）：先 `retVal = mi.proceed()` 再 `advice.afterReturning(...)`；
- `ThrowsAdviceInterceptor`（行 58，invoke 在行 134）：proceed 抛异常时反射回调用户写的 `afterThrowing(...)`；
- `AspectJAroundAdvice` 本身就是拦截器（类声明行 39：`public class AspectJAroundAdvice extends AbstractAspectJAdvice implements MethodInterceptor, Serializable`），invoke（行 64-71）把 `MethodInvocation` 包装成 AspectJ 的 `ProceedingJoinPoint` 再反射调用通知方法。

**匹配逻辑**：每次方法调用时由 `AdvisedSupport.getInterceptorsAndDynamicInterceptionAdvice`（行 516-538，带 `methodCache` 缓存）委托 `DefaultAdvisorChainFactory`（行 57-112）构建链：逐个 Advisor，先 `ClassFilter.matches(actualClass)`（行 72），再 `MethodMatcher.matches(method, actualClass)`（行 79-83），命中后 `registry.getInterceptors(advisor)` 拿到拦截器（行 85），若 `mm.isRuntime()` 则包装成 `InterceptorAndDynamicMethodMatcher`（行 90）留给 proceed() 现场判定。其中 `registry` 是 `DefaultAdvisorAdapterRegistry`（行 52-56 构造时注册 MethodBefore/AfterReturning/Throws 三个 Adapter，行 81-96 `getInterceptors` 负责把 Advice 适配成 MethodInterceptor），而"这个 Advisor 对这个类是否适用"的初筛是 `AopUtils.canApply`（AopUtils.java 行 225-280：遍历类和接口的全部声明方法，任一方法静态命中即返回 true）。

## 5.7 自动代理：AOP 与 IoC 容器的接线处

上面的 `ProxyFactory` 是手工用法，真正的 Spring 应用里没人手动 new 代理——靠的是 `AbstractAutoProxyCreator`，一个 **`SmartInstantiationAwareBeanPostProcessor`**（类声明行 96-97），即容器扩展点。

它拦截每个 Bean 的两个时机：

**① `postProcessBeforeInstantiation`（行 244-272，实例化前）**：默认只处理自定义 `TargetSource` 的场景——如果命中就直接 `createProxy` 返回一个代理，让容器跳过目标 Bean 的实例化（"提前短路"）；同时把基础设施类（`isInfrastructureClass`，行 359-368：Advice/Pointcut/Advisor/AopInfrastructureBean 类型）标记为不代理。

**② `postProcessAfterInitialization`（行 285-293，初始化后）→ `wrapIfNecessary`（行 321-345）**：

```java
protected Object wrapIfNecessary(Object bean, String beanName, Object cacheKey) {
    if (StringUtils.hasLength(beanName) && this.targetSourcedBeans.contains(beanName)) {
        return bean;
    }
    if (Boolean.FALSE.equals(this.advisedBeans.get(cacheKey))) {
        return bean;                                    // 之前判定过"不代理"，直接返回
    }
    if (isInfrastructureClass(bean.getClass()) || shouldSkip(bean.getClass(), beanName)) {
        this.advisedBeans.put(cacheKey, Boolean.FALSE);
        return bean;
    }

    // Create proxy if we have advice.
    Object[] specificInterceptors = getAdvicesAndAdvisorsForBean(bean.getClass(), beanName, null);
    if (specificInterceptors != DO_NOT_PROXY) {         // 找到了匹配的 Advisor
        this.advisedBeans.put(cacheKey, Boolean.TRUE);
        Object proxy = createProxy(
                bean.getClass(), beanName, specificInterceptors, new SingletonTargetSource(bean));
        this.proxyTypes.put(cacheKey, proxy.getClass());
        return proxy;                                   // 容器注册的是代理，不再是原始 Bean
    }

    this.advisedBeans.put(cacheKey, Boolean.FALSE);
    return bean;
}
```

`createProxy` → `buildProxy`（行 440-494）内部就是 new 一个 `ProxyFactory`、复制配置、`addAdvisors`、`getProxy`。**Bean 放进容器的瞬间就被换成了代理**，这是 AOP 织入 IoC 的全部秘密。

**Advisor 从哪来、怎么匹配**：`AbstractAdvisorAutoProxyCreator.findEligibleAdvisors`（行 96-110）分三步——`findCandidateAdvisors()`（行 116-119，从容器找出所有 Advisor 类型的 Bean）→ `findAdvisorsThatCanApply(candidateAdvisors, beanClass, beanName)`（行 130-140，委托 `AopUtils` 逐个 canApply）→ `sortAdvisors`（按 @Order/Ordered 排序）。

**@EnableAspectJAutoProxy 的注册链路**。注解声明（spring-context `annotation/EnableAspectJAutoProxy.java` 行 122）：

```java
@Import(AspectJAutoProxyRegistrar.class)
public @interface EnableAspectJAutoProxy {
```

`AspectJAutoProxyRegistrar`（行 34-57）是 `ImportBeanDefinitionRegistrar`，核心一行在行 45：

```java
AopConfigUtils.registerAspectJAnnotationAutoProxyCreatorIfNecessary(registry);
```

它最终进入 `AopConfigUtils.registerOrEscalateApcAsRequired`（spring-aop `config/AopConfigUtils.java` 行 119-142）：以固定 Bean 名 `AUTO_PROXY_CREATOR_BEAN_NAME` 注册一个 `AnnotationAwareAspectJAutoProxyCreator` 的 `RootBeanDefinition`（行 136-140），并按"升级列表"（行 62-67：Infrastructure → AspectJAware → AnnotationAware）在多个注解同时存在时只保留优先级最高的那一个。于是**一行 @EnableAspectJAutoProxy 实际做的是：往容器里塞了一个 BeanPostProcessor**。

**@Aspect 切面类如何变成 Advisor**：`AnnotationAwareAspectJAutoProxyCreator` 会通过 `BeanFactoryAspectJAdvisorsBuilder` 用 `ReflectiveAspectJAdvisorFactory` 解析每个 @Aspect Bean。【源码证据】`aspectj/annotation/ReflectiveAspectJAdvisorFactory.java` `getAdvisors` 行 122-166：

```java
for (Method method : getAdvisorMethods(aspectClass)) {     // 行 134
    ...
    Advisor advisor = getAdvisor(method, lazySingletonAspectInstanceFactory, 0, aspectName);
    if (advisor != null) {
        advisors.add(advisor);
    }
}
```

细节：`adviceMethodFilter`（行 75-76）**排除了 @Pointcut 方法**——@Pointcut 只是表达式占位，供其他通知引用复用；`getAdvisorMethods`（行 168-175）按 `Around→Before→After→AfterReturning→AfterThrowing` 的优先级排序（行 86-95）；每个通知方法经 `getAdvisor`（行 200-222）生成一个 `InstantiationModelAwarePointcutAdvisorImpl`（行 213），其切点在 `getPointcut`（行 224-238）中由方法上的注解表达式构造：`ajexp.setExpression(aspectJAnnotation.getPointcutExpression())`。`getAdvice` 的 switch（行 268-289）把 `@Around` 映射为 `AspectJAroundAdvice`、`@Before` 映射为 `AspectJMethodBeforeAdvice`……与 5.6 的适配层接上。

**为什么 @Transactional/@Async 什么都没配也能生效**：因为它们各自的 `@EnableTransactionManagement`/`@EnableAsync` 用同样的 `@Import` 机制在容器启动时注册了自己的代理设施。以 @Async 为例（spring-context `scheduling/annotation/ProxyAsyncConfiguration.java` 行 44-59）：

```java
@Bean(name = TaskManagementConfigUtils.ASYNC_ANNOTATION_PROCESSOR_BEAN_NAME)
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public AsyncAnnotationBeanPostProcessor asyncAdvisor() {
    ...
    AsyncAnnotationBeanPostProcessor bpp = new AsyncAnnotationBeanPostProcessor();
    bpp.configure(this.executor, this.exceptionHandler);
    ...
    return bpp;
}
```

`AsyncAnnotationBeanPostProcessor`（行 65）继承 `AbstractBeanFactoryAwareAdvisingPostProcessor`——一个"往别的 BeanPostProcessor 创建的代理里追加自己 Advisor"的变体：构造器里 `setBeforeExistingAdvisors(true)`（行 87-89），`setBeanFactory` 时 new 出 `AsyncAnnotationAdvisor`（行 145-150）。@Transactional 同理由 `InfrastructureAdvisorAutoProxyCreator` 与 `BeanFactoryTransactionAttributeSourceAdvisor`（spring-tx 行 34-60，内部 `TransactionAttributeSourcePointcut` 按 `@Transactional` 元数据匹配方法）配合完成。

## 5.8 SpEL：Spring 表达式语言

白话版：SpEL 是一门运行期求值的小语言，解决"把一段可配置的取值逻辑写在字符串里"的需求。典型例子 `@Value("#{systemProperties['user.dir']}")`、缓存 key `@Cacheable(key = "#id")`。

**解析：递归下降**。入口 `SpelExpressionParser`（`spel/standard/SpelExpressionParser.java` 行 35-67）只是薄壳，`doParseExpression`（行 63-65）直接委托给 `InternalSpelExpressionParser`。后者先分词（`Tokenizer`），再按语法规则逐层"吃掉" token 构建抽象语法树（AST）。【源码证据】`InternalSpelExpressionParser.java` 行 137-152：

```java
this.expressionString = expressionString;
Tokenizer tokenizer = new Tokenizer(expressionString);
this.tokenStream = tokenizer.process();
...
SpelNodeImpl ast = eatExpression();
...
return new SpelExpression(expressionString, ast, this.configuration);
```

`eatExpression`（行 173-214）处理三元 `a?b:c`、Elvis `a?:b`、赋值；然后逐级下降：`eatLogicalOrExpression`（行 217-226，`or/||`）→ `eatLogicalAndExpression`（行 229-238，`and/&&`）→ `eatRelationalExpression`（行 241 起，`> < ==` 等）→ 加减 → 乘除 → 一元/主表达式（字面量、`#变量`、属性导航、`T(...)`、方法调用……），每个非终结符对应一个 `eatXxx` 方法，产出 `SpelNodeImpl` 的子类节点（如 `OpOr`、`Ternary`、`Elvis`）。AST 节点统一实现 `spel/SpelNode.java`（行 18 起：`Represents a node in the abstract syntax tree (AST)`）的 `getValue`。

**求值：Expression + EvaluationContext**。`Expression` 接口（行 52-85）的核心契约是"**解析一次、求值多次**"（javadoc 行 30-31），可换根对象、换上下文复用。承载环境的是 `StandardEvaluationContext`（`spel/support/StandardEvaluationContext.java` 行 98），它装配了一整套可插拔策略：属性访问 `PropertyAccessor`、方法解析 `MethodResolver`（行 304 `setMethodResolvers`）、构造器/类型定位 `TypeLocator`（行 367 `setTypeLocator`）、Bean 引用 `BeanResolver`（行 343 `setBeanResolver`，这就是 SpEL 里 `@beanName` 能拿到容器 Bean 的原因）、变量表（行 457 `setVariable`）。`#this`/`#root` 由 `spel/ast/VariableReference.java` 特判（行 43 `private static final String THIS = "this";` 行 60）。`T(java.time.LocalDate)` 类型引用由 `spel/ast/TypeReference.java`（行 39）节点求值。

**容器与 SpEL 的接线点**：
1. `@Value("#{...}")` / XML 属性占位符：spring-context 的 `context/expression/StandardBeanExpressionResolver.java`（实现 `BeanExpressionResolver`，类声明行 61）——默认前缀后缀 `"#{"` / `"}"`（行 72-75），内建 `SpelExpressionParser`（行 120-126），并带两级缓存（表达式缓存行 84、求值上下文缓存行 86）。容器填充属性时发现值是 `#{...}` 模板，就交给它求值。
2. `@Cacheable` 的 key：spring-context `cache/interceptor/CacheOperationExpressionEvaluator.java`（行 45 起）把方法参数、目标对象暴露为 `#参数名`、`#result`（行 60）等变量，`CacheAspectSupport` 在行 434/497 调用生成缓存键。
3. 安全模型：`StandardEvaluationContext` 的 javadoc 明确警告（行 67-69）"`exposes the complete SpEL language, backed by reflection, and must never be used to evaluate a SpEL expression obtained from an untrusted source`"。当表达式来自用户输入（如 Web 参数排序字段），应使用 `SimpleEvaluationContext`（行 128）：它只支持属性读写等数据绑定子集，`TypeLocator` 直接抛异常（行 130-132，`T()` 不可用），且不提供 Bean 引用/类型反射——这正是它存在的意义：**按需开放能力，而不是给全量反射权限**。两个上下文的文档同时强调（`Expression.java` 行 39-45）：同一条解析出的 `Expression` 不能先在 Standard 后在 Simple 上求值，因为缓存的 accessor 状态可能"越权"。

## 5.9 小结：AOP 是如何织入 IoC 容器的

把本章内容按时序串起来：

```
1. 容器刷新（refresh）
   ├─ @EnableAspectJAutoProxy/@EnableAsync/@EnableTransactionManagement
   │    → @Import 的 Registrar/Configuration
   │    → 向 BeanDefinitionRegistry 注册 AnnotationAwareAspectJAutoProxyCreator
   │      / AsyncAnnotationBeanPostProcessor 等"代理设施" BeanDefinition   ← AOP 接线
2. 注册所有 BeanDefinition
3. 实例化每个普通 Bean：
   ├─ BeanPostProcessor.postProcessBeforeInstantiation
   │    （仅自定义 TargetSource 时提前短路返回代理）
   ├─ 反射构造目标 Bean → 依赖注入 → Aware 回调 → @PostConstruct
   └─ BeanPostProcessor.postProcessAfterInitialization
        → AbstractAutoProxyCreator.wrapIfNecessary
        → findEligibleAdvisors：容器内所有 Advisor（含 @Aspect 解析出的
          InstantiationModelAwarePointcutAdvisor、事务/缓存/异步 Advisor）
          逐个 canApply 匹配本 Bean
        → 命中：ProxyFactory → DefaultAopProxyFactory.createAopProxy
          （无接口或 proxyTargetClass → CGLIB 子类代理；否则 JDK 动态代理）
        → 容器缓存并注入的是【代理】，原始对象藏在 SingletonTargetSource 里
4. 运行期每次方法调用：
   proxy.method() → JdkDynamicAopProxy.invoke / DynamicAdvisedInterceptor.intercept
   → AdvisedSupport.getInterceptorsAndDynamicInterceptionAdvice（缓存）
   → new ReflectiveMethodInvocation(...).proceed()
   → ExposeInvocationInterceptor(链首) → 各 MethodInterceptor 递归 proceed()
   → 反射调用真实目标 → 返回值/异常沿链回传
```

一句话总结：**AOP 没有修改你的类，它只是让容器在 Bean 生命周期"初始化完成"这个点把 Bean 悄悄换成了代理；代理内部用统一的责任链在目标方法前后执行横切逻辑；而"要不要切、切什么"由 Advisor 的切点表达式（AspectJ 语法）静态+动态两级匹配决定。** 理解了 `wrapIfNecessary` 与 `proceed()` 这两处源码，`@Transactional` 失效（自调用、final 方法）这类经典问题也就有了从字节码层面的答案。

---

**本章涉及的模块与类速查**：spring-aop（Pointcut/Advisor/TargetSource、framework 包的 ProxyFactory/JdkDynamicAopProxy/CglibAopProxy/ReflectiveMethodInvocation、adapter 包、autoproxy 包、aspectj 包、interceptor/ExposeInvocationInterceptor）；spring-tx（BeanFactoryTransactionAttributeSourceAdvisor）；spring-context（AspectJAutoProxyRegistrar、ProxyAsyncConfiguration、StandardBeanExpressionResolver、CacheOperationExpressionEvaluator）；spring-expression（SpelExpressionParser、InternalSpelExpressionParser、StandardEvaluationContext、SimpleEvaluationContext）。


---

# 六、Web 与数据访问模块

前几章我们吃透了 IoC、AOP 与事件这些"地基"。本章上楼：看 Spring 如何用这些地基盖出 **Web 层**（spring-web / webmvc / webflux / websocket）和 **数据访问层**（spring-tx / jdbc / orm / r2dbc / jms / oxm / messaging），以及**测试层**（spring-test）。两大重点：`DispatcherServlet` 请求处理全流程、`@Transactional` 事务全链路——这两条链路是 Spring 面试与实战中出场率最高的代码路径。

> 以下所有行号均来自对 `D:\code\3rd\spring-framework`（7.1.0-SNAPSHOT 快照）的实际读取。

## 6.1 模块全景表

`ls` 根目录确认，仓库共 22 个功能模块 + 4 个工程支撑目录（另有 framework-docs、integration-tests、src、gradle）：

| 模块 | 一句话职责 |
|---|---|
| spring-core | 核心工具：IO 工具、ResolvableType、ReactiveAdapterRegistry 等（reactive 适配器其实住在这里） |
| spring-beans | BeanDefinition、BeanFactory、属性填充 |
| spring-aop | AOP 联盟：代理创建器、Advisor、切面基础设施（@Transactional 就靠它） |
| spring-aspects | AspectJ 集成（@Configurable、@Async 的 AspectJ 版等） |
| spring-context | ApplicationContext、@Component/@Autowired、事件、i18n、资源加载 |
| spring-context-indexer | 编译期生成组件索引，加速 @ComponentScan |
| spring-context-support | 第三方库胶水：缓存（Caffeine 等）、邮件、调度（Quartz） |
| spring-core-test | core 模块自身的测试脚手架（供其他模块测试复用） |
| spring-expression | SpEL 表达式语言 |
| spring-instrument | JVM Instrumentation agent，类加载期织入 |
| spring-tx | 事务抽象：PlatformTransactionManager、@Transactional 基础设施 |
| spring-jdbc | JdbcTemplate、DataSource 事务实现、异常转译 |
| spring-orm | Hibernate/JPA 与 IoC 的适配器 |
| spring-oxm | O/X 映射抽象（JAXB、XStream 胶水） |
| spring-jms | JMS 消息模板与监听容器 |
| spring-messaging | 消息编程模型（Message/MessageChannel、@MessageMapping、STOMP 支撑） |
| spring-r2dbc | R2DBC 响应式关系数据库访问（DatabaseClient、R2dbcTransactionManager） |
| spring-web | Web 公共层：双栈（Servlet/Reactive）共用的 Http 抽象、RestClient、multipart |
| spring-webflux | 响应式 Web 栈：DispatcherHandler、Reactive @MVC 注解 |
| spring-webmvc | Servlet 栈 MVC：DispatcherServlet、@RequestMapping 体系 |
| spring-websocket | WebSocket/STOMP/SockJS 支持 |
| spring-test | TestContext 框架、Mock 对象、MockMvc |
| framework-api | 对外稳定的 API 坐标（7.x 新的模块化拆分产物） |
| framework-bom | 物料清单（BOM），统一依赖版本 |
| framework-platform | 平台聚合依赖（把 spring-* 打包为 platform 坐标） |
| buildSrc | Gradle 构建逻辑（插件、约定） |

一个直观结论：**Web 四模块 + 数据访问七模块，本质上都是核心容器在特定领域的一组策略接口 + 默认实现**。策略接口（HandlerMapping、PlatformTransactionManager……）定义"能力"，容器负责"装配"。

## 6.2 spring-web：与 Web 技术无关的公共层

**白话**：spring-web 不依赖 Servlet 也不依赖 Reactor 运行时接口，它是两个 Web 栈（webmvc/webflux）共同的地基——HTTP 抽象（`HttpInputMessage/HttpOutputMessage`）、`HttpEntity`、`Cookie/Session` 抽象、`MultipartFile`、编解码器、以及 `org.springframework.web.method.*` 这套"注解驱动方法级编程模型"（参数解析器/返回值处理器都定义在 spring-web 里，两个栈各装配一份）。

【源码证据】`spring-web/src/main/java/org/springframework/web/client/RestClient.java` 接口声明 + 静态工厂（84、149-151 行）：

```java
public interface RestClient {
    RequestHeadersUriSpec<?> get();          // 90 行
    RequestBodyUriSpec post();               // 102 行
    ...
    static RestClient create() {             // 149 行
        return new DefaultRestClientBuilder().build();
    }
```

- **RestClient**：6.1 引入的同步 HTTP 客户端流式 API（`RestClient.java:82` 标注 `@since 6.1`），builder 在同目录 `DefaultRestClientBuilder.java`，接口里 `interface Builder`（231 行）；它是老 `RestTemplate` 的现代化替代（`RestTemplate.java` 仍在，标为维护态使用）。
- **MultipartFile**（`spring-web/.../web/multipart/MultipartFile.java:45`）：`public interface MultipartFile extends InputStreamSource`，把"上传文件"抽象成拿名字/拿流/转移存的接口，Servlet 栈用 `StandardMultipartHttpServletRequest` 实现，Reactive 栈用 `DefaultPart` 适配。
- **HttpEntity**（`spring-web/.../http/HttpEntity.java:59`）：`public class HttpEntity<T>`，即"请求体 T + HttpHeaders"的组合体，`RequestEntity/ResponseEntity` 都是它的子类。
- **双栈分界**：spring-web 里同时存在 `org.springframework.web.servlet` 的*前置*接口与 `org.springframework.web.reactive` 的注解模型，但所有与 Servlet API（`HttpServletRequest`）耦合的类只在 webmvc/webflux 各自模块落地。判断标准很简单：**spring-web = 协议层抽象；webmvc = Servlet 实现；webflux = Reactor 实现**。

## 6.3 spring-webmvc：DispatcherServlet 全流程（重点 A）

### 6.3.1 继承结构与"九大组件"

**白话**：DispatcherServlet 本质是一个 `HttpServlet`。Spring 用三层继承把职责拆开：`HttpServletBean`（把 Servlet 初始化参数注入为 Environment 属性）→ `FrameworkServlet`（持有子 Spring 容器 WebApplicationContext，把 doGet/doPost 统一转发给 `processRequest`）→ `DispatcherServlet`（真正的调度中枢）。

【源码证据】三个类的声明：

```java
// HttpServletBean.java:82
public abstract class HttpServletBean extends HttpServlet implements EnvironmentCapable, EnvironmentAware {
// FrameworkServlet.java:142
public abstract class FrameworkServlet extends HttpServletBean implements ApplicationContextAware {
// DispatcherServlet.java:157
public class DispatcherServlet extends FrameworkServlet {
```

FrameworkServlet 里 `doGet/doPost/...` 全部是 `protected final`，统一调 `processRequest(request, response)`（`FrameworkServlet.java:887-921`）。DispatcherServlet 再实现 `onRefresh`：**容器刷新完成时初始化全部策略组件**——

【源码证据】`spring-webmvc/src/main/java/org/springframework/web/servlet/DispatcherServlet.java` onRefresh/initStrategies（433-450 行）：

```java
protected void onRefresh(ApplicationContext context) {
    initStrategies(context);
}

protected void initStrategies(ApplicationContext context) {
    initMultipartResolver(context);
    initLocaleResolver(context);
    initHandlerMappings(context);
    initHandlerAdapters(context);
    initHandlerExceptionResolvers(context);
    initRequestToViewNameTranslator(context);
    initViewResolvers(context);
    initFlashMapManager(context);
}
```

传统教材说"九大组件"，但**当前源码只有 8 个 init 方法**——themeResolver 已在 5.0 移除。每个 initXxx 的套路一致（`initHandlerMappings` 506-545 行）：先按类型找容器里所有该接口的 Bean（`detectAllHandlerMappings=true` 时 `BeanFactoryUtils.beansOfTypeIncludingAncestors`），找不到就回落到 `DispatcherServlet.properties` 的默认策略（`spring-webmvc/src/main/resources/.../DispatcherServlet.properties:7-19` 默认注册了 `RequestMappingHandlerMapping`、`RequestMappingHandlerAdapter`、`ExceptionHandlerExceptionResolver` 等——**注解 MVC 的"开箱即用"来自这个文件**）。

### 6.3.2 doDispatch：一次请求的一生（逐行级）

【源码证据】`DispatcherServlet.java` doDispatch（935-1004 行）：

```java
protected void doDispatch(HttpServletRequest request, HttpServletResponse response) throws Exception {
    HttpServletRequest processedRequest = request;
    HandlerExecutionChain mappedHandler = null;
    ...
    processedRequest = checkMultipart(request);              // 947：multipart 预处理
    mappedHandler = getHandler(processedRequest);            // 951：找 Handler（含拦截器链）
    if (mappedHandler == null) {
        noHandlerFound(processedRequest, response); return;  // 953：404
    }
    if (!mappedHandler.applyPreHandle(processedRequest, response)) {
        return;                                              // 957：拦截器 preHandle
    }
    HandlerAdapter ha = getHandlerAdapter(mappedHandler.getHandler()); // 962
    mv = ha.handle(processedRequest, response, mappedHandler.getHandler()); // 963：真正执行
    ...
    applyDefaultViewName(processedRequest, mv);              // 969：补默认视图名
    mappedHandler.applyPostHandle(processedRequest, response, mv); // 970
    ...
    processDispatchResult(processedRequest, response, mappedHandler, mv, dispatchException); // 980
```

`getHandler`（1154-1164 行）按序遍历 handlerMappings 取第一个非 null；`getHandlerAdapter`（1185-1195 行）遍历找第一个 `adapter.supports(handler)` 为真的适配器。异常不会直接抛出，而是存进 `dispatchException`（972-978 行），交给 `processDispatchResult`（1022-1062 行）：有异常先 `processHandlerException` 走异常解析器换 ModelAndView，再 `render(mv, request, response)` 渲染视图，最后 `triggerAfterCompletion` 触发拦截器收尾。

```text
doDispatch(request, response)
│
├─ checkMultipart()                 # 文件上传请求 → 包装成 MultipartHttpServletRequest
├─ getHandler(request)              # HandlerMapping 链：按 URL+条件 找到 HandlerExecutionChain
│      └─ HandlerExecutionChain = HandlerMethod + HandlerInterceptor[]
├─ mappedHandler.applyPreHandle()   # 拦截器 preHandle()（返回 false → 直接结束）
├─ getHandlerAdapter(handler)       # 找到能"驾驶"该 handler 的适配器
├─ ha.handle(request, response, handler)
│      └─ RequestMappingHandlerAdapter
│            ├─ 参数解析 HandlerMethodArgumentResolver（含 @RequestBody 反序列化）
│            ├─ 反射调用 Controller 方法
│            └─ 返回值处理 HandlerMethodReturnValueHandler ──► ModelAndView / 直接写响应
├─ mappedHandler.applyPostHandle()  # 拦截器 postHandle()（正常返回后才执行）
├─ processDispatchResult(mv, dispatchException)
│      ├─ 有异常 → processHandlerException() → HandlerExceptionResolver 链（@ExceptionHandler）
│      └─ render() → ViewResolver 解析视图 → View.render() 输出
└─ triggerAfterCompletion()         # 拦截器 afterCompletion()（成功/异常都会走）
```

### 6.3.3 @RequestMapping 如何被注册与匹配

**注册（启动期）**：`RequestMappingHandlerMapping` 实现了 `InitializingBean`，容器启动时回调 `afterPropertiesSet`。父类 `AbstractHandlerMethodMapping` 的实现链条是：`afterPropertiesSet → initHandlerMethods → processCandidateBean → detectHandlerMethods → registerHandlerMethod`。

【源码证据】`spring-webmvc/src/main/java/org/springframework/web/servlet/handler/AbstractHandlerMethodMapping.java`（207-224、249-263、292-295、326-328 行）：

```java
public void afterPropertiesSet() {
    initHandlerMethods();
}
protected void initHandlerMethods() {
    for (String beanName : getCandidateBeanNames()) {
        if (!beanName.startsWith(SCOPED_TARGET_NAME_PREFIX)) {
            processCandidateBean(beanName);
        }
    }
    handlerMethodsInitialized(getHandlerMethods());
}
...
if (beanType != null && isHandler(beanType)) {   // 260 行
    detectHandlerMethods(beanName);
}
...
methods.forEach((method, mapping) -> {           // 292 行
    Method invocableMethod = AopUtils.selectInvocableMethod(method, userType);
    registerHandlerMethod(handler, invocableMethod, mapping);
});
```

`isHandler` 判断类上是否有 `@Controller`（`RequestMappingHandlerMapping.java:177-179`：`AnnotatedElementUtils.hasAnnotation(beanType, Controller.class)`）；`detectHandlerMethods` 用 `MethodIntrospector.selectMethods` 对每个方法算出 `RequestMappingInfo`（URL+HTTP 方法+params+headers+consumes+produces 的组合条件），最后进 `mappingRegistry`（327 行 `this.mappingRegistry.register(mapping, handler, method)`），注册表带读写锁（`ReentrantReadWriteLock`）。

**匹配（请求期）**：`getHandlerInternal`（372-382 行）先 `initLookupPath` 算路径，拿读锁后调 `lookupHandlerMethod`。

【源码证据】`AbstractHandlerMethodMapping.java` lookupHandlerMethod（393-424 行）：

```java
protected @Nullable HandlerMethod lookupHandlerMethod(String lookupPath, HttpServletRequest request) throws Exception {
    List<Match> matches = new ArrayList<>();
    List<T> directPathMatches = this.mappingRegistry.getMappingsByDirectPath(lookupPath);
    if (directPathMatches != null) {
        addMatchingMappings(directPathMatches, matches, request);   // 先按直接路径缩小范围
    }
    if (matches.isEmpty()) {
        addMatchingMappings(this.mappingRegistry.getRegistrations().keySet(), matches, request);
    }
    if (!matches.isEmpty()) {
        Match bestMatch = matches.get(0);
        if (matches.size() > 1) { ...                               // 排序选最优
            if (comparator.compare(bestMatch, secondBestMatch) == 0) {
                throw new IllegalStateException("Ambiguous handler methods mapped for '" + uri + "' ...");
```

即：**先按 URL 粗筛、再按 RequestMappingInfo 全条件精筛、多个匹配则排序取最优、并列则抛 Ambiguous**。另外 7.0 起 `afterPropertiesSet`（`RequestMappingHandlerMapping.java:142-155`）还初始化了 `RequestMappingInfo.BuilderConfiguration`（PatternParser/API 版本策略——API 版本化为 7.0 新特性，见 1.6 节）。

### 6.3.4 参数解析、返回值与消息转换

**白话**：你的 Controller 方法为什么能直接写 `(@RequestBody User u, @RequestParam int page)`？因为 DispatcherServlet 调方法前，会拿 30 多个 `HandlerMethodArgumentResolver`（接口在 `spring-web/.../web/method/support/HandlerMethodArgumentResolver.java:34`）逐个问"这个参数你解吗"（supportsParameter），命中的负责从请求里"变"出这个参数。

【源码证据】`spring-web/.../web/method/annotation/RequestParamMethodArgumentResolver.java`（78、127-152 行，supportsParameter 片段）：

```java
public class RequestParamMethodArgumentResolver extends AbstractNamedValueMethodArgumentResolver {
    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        if (parameter.hasParameterAnnotation(RequestParam.class)) { ... return true; }
        else {
            ...
            else if (this.useDefaultResolution) {
                return BeanUtils.isSimpleProperty(parameter.getNestedParameterType()); // 简单类型兜底
```

`resolveName`（161-184 行）依次尝试 multipart 参数、`request.getParameterValues(name)`——这就是"从查询串取值"的落点。

【源码证据】`spring-webmvc/.../mvc/method/annotation/RequestResponseBodyMethodProcessor.java`（134-136、152-174 行）：

```java
public boolean supportsParameter(MethodParameter parameter) {
    return parameter.hasParameterAnnotation(RequestBody.class);   // @RequestBody 参数归我
}
public @Nullable Object resolveArgument(...) throws Exception {
    parameter = parameter.nestedIfOptional();
    Object arg = readWithMessageConverters(webRequest, parameter, parameter.getNestedGenericParameterType());
    if (binderFactory != null) {
        ...
        WebDataBinder binder = binderFactory.createBinder(webRequest, arg, name, type);
        if (arg != null) {
            validateIfApplicable(binder, parameter);              // 触发 @Valid 校验
            if (binder.getBindingResult().hasErrors() && isBindExceptionRequired(binder, parameter)) {
                throw new MethodArgumentNotValidException(parameter, binder.getBindingResult());
```

`readWithMessageConverters` 的底层就是 `HttpMessageConverter`（`spring-web/.../http/converter/HttpMessageConverter.java:39`，`canRead:48 / read:111 / write:126 / canWriteRepeatedly:73`）：按 Content-Type 挑转换器（如 `MappingJackson2HttpMessageConverter`）把 JSON 字节流反序列化成参数对象。**@RequestBody 反序列化入口 + @Valid 校验入口都在这一个方法里**（校验失败抛 `MethodArgumentNotValidException`）。返回值侧镜像对称：`HandlerMethodReturnValueHandler`（`spring-web/.../method/support/HandlerMethodReturnValueHandler.java:32`），@ResponseBody 的返回值由同一个类的 `supportsReturnType`（139-143 行）接住后反向走 HttpMessageConverter 写响应。`WebDataBinder` 则负责非 JSON 参数的类型转换与绑定（一句话：**BeanWrapper + ConversionService + Validator 的组合拳**）。

### 6.3.5 异常解析体系与 @ExceptionHandler

`HandlerExceptionResolver` 是策略接口，默认策略链（DispatcherServlet.properties:17-19）依次为 `ExceptionHandlerExceptionResolver → ResponseStatusExceptionResolver → DefaultHandlerExceptionResolver`，谁先返回非 null ModelAndView 谁赢。@ExceptionHandler 的主角：

【源码证据】`spring-webmvc/.../mvc/method/annotation/ExceptionHandlerExceptionResolver.java`（87-88、430-434 行）：

```java
public class ExceptionHandlerExceptionResolver extends AbstractHandlerMethodExceptionResolver
        implements ApplicationContextAware, InitializingBean {
    ...
    protected @Nullable ModelAndView doResolveHandlerMethodException(HttpServletRequest request,
            HttpServletResponse response, @Nullable HandlerMethod handlerMethod, Exception exception) {
        ServletWebRequest webRequest = new ServletWebRequest(request, response);
        ServletInvocableHandlerMethod exceptionHandlerMethod = getExceptionHandlerMethod(handlerMethod, exception, webRequest);
```

它启动时（afterPropertiesSet，277 行）扫描所有 @Controller 方法与 @ControllerAdvice，把 `@ExceptionHandler` 方法登记进 `ExceptionHandlerMethodResolver`（类在 `spring-web/.../method/annotation/ExceptionHandlerMethodResolver.java`，82 行 `mappedMethods` 以异常类型为 key）。异常发生时按"本类优先 → @ControllerAdvice 兜底"找最匹配的方法，把它包装成 `ServletInvocableHandlerMethod` 复用同一套参数解析/返回值处理（464 行 `exceptionHandlerMethod.invokeAndHandle(...)`）——**@ExceptionHandler 方法本身就是一个微型 Controller**。

## 6.4 spring-webflux 概览：同一个"三分法"，无阻塞的实现

**白话**：WebFlux 不是"新框架"，而是把 MVC 的 `DispatcherServlet 三分法`（**先找 handler（HandlerMapping）→ 再适配执行（HandlerAdapter）→ 最后处理结果（HandlerResultHandler）**）搬到 Reactor 上：`handle` 返回 `Mono<Void>` 而不是阻塞调用。

【源码证据】`spring-webflux/src/main/java/org/springframework/web/reactive/DispatcherHandler.java`（72、138-151 行）：

```java
public class DispatcherHandler implements WebHandler, PreFlightRequestHandler, ApplicationContextAware {
    ...
    public Mono<Void> handle(ServerWebExchange exchange) {
        if (this.handlerMappings == null) {
            return createNotFoundError();
        }
        ...
        return Flux.fromIterable(this.handlerMappings)
                .concatMap(mapping -> mapping.getHandler(exchange))     // 找 handler
                .next()
                .switchIfEmpty(createNotFoundError())
                .onErrorResume(ex -> handleResultMono(exchange, Mono.error(ex)))
                .flatMap(handler -> handleRequestWith(exchange, handler)); // 适配执行
```

`initStrategies`（115-134 行）同样从容器收集 HandlerMapping/HandlerAdapter/HandlerResultHandler 三类 Bean——与 MVC 完全同构。对照表：

| | spring-webmvc | spring-webflux |
|---|---|---|
| 前端控制器 | DispatcherServlet（阻塞式同步方法） | DispatcherHandler（`Mono<Void>`） |
| 映射 | RequestMappingHandlerMapping | Reactive 版 RequestMappingHandlerMapping |
| 执行 | HandlerAdapter.handle（同步） | HandlerAdapter.handle → Mono<HandlerResult> |
| 结果 | View/MessageConverter 写出 | HandlerResultHandler + Reactive 编解码 |

两句话补充：`ReactiveAdapterRegistry`（实际定义在 **spring-core**，`spring-core/src/main/java/org/springframework/core/ReactiveAdapterRegistry.java:60`）负责 CompletableFuture/Flux/Mono/协程与 Reactive Streams Publisher 之间的互转，是"任何响应式类型都能当返回值"的底层机关；WebSocket/STOMP 则由 spring-websocket 模块提供（`org.springframework.web.socket` 下有 `WebSocketHandler.java`、`messaging/StompSubProtocolErrorHandler.java` 等，STOMP 子协议与 SockJS 降级都在这个模块）。

## 6.5 spring-tx：@Transactional 的完整链路（重点 B）

### 6.5.1 事务抽象：三个方法 + 两本说明书

**白话**：Spring 不亲自管理事务，它定义"事务管理器"接口让 JDBC/JPA/JTA 各自实现。接口只有三个方法；`TransactionDefinition` 是"开始前说明书"（传播行为、隔离级别、超时、只读），`TransactionStatus` 是"进行中账本"（是否新事务、可否打保存点、能否标记回滚）。

【源码证据】`spring-tx/src/main/java/org/springframework/transaction/PlatformTransactionManager.java`（47、72、98、118 行）：

```java
public interface PlatformTransactionManager extends TransactionManager {
    TransactionStatus getTransaction(@Nullable TransactionDefinition definition) throws TransactionException;
    void commit(TransactionStatus status) throws TransactionException;
    void rollback(TransactionStatus status) throws TransactionException;
```

`TransactionDefinition.java:44` 定义 7 种传播行为（`PROPAGATION_REQUIRED=0` 52 行 … `PROPAGATION_REQUIRES_NEW=3` 96 行、`PROPAGATION_NESTED=6` 132 行）；`TransactionStatus.java:40`：`public interface TransactionStatus extends TransactionExecution, SavepointManager, Flushable`。

### 6.5.2 DataSourceTransactionManager：begin/commit 落到 JDBC

【源码证据】`spring-jdbc/src/main/java/org/springframework/jdbc/datasource/DataSourceTransactionManager.java` doBegin/doCommit（263-321、336-348 行）：

```java
protected void doBegin(Object transaction, TransactionDefinition definition) {
    ...
    if (!txObject.hasConnectionHolder() || txObject.getConnectionHolder().isSynchronizedWithTransaction()) {
        Connection newCon = obtainDataSource().getConnection();        // 从池里取连接
        ...
        txObject.setConnectionHolder(new ConnectionHolder(newCon), true);
    }
    ...
    if (con.getAutoCommit()) {                                         // 292 行
        txObject.setMustRestoreAutoCommit(true);
        ...
        con.setAutoCommit(false);                                      // 297 行：关自动提交=开事务
    }
    ...
    txObject.getConnectionHolder().setTransactionActive(true);
    // Bind the connection holder to the thread.                        // 308 行
    if (txObject.isNewConnectionHolder()) {
        TransactionSynchronizationManager.bindResource(obtainDataSource(), txObject.getConnectionHolder()); // 310
    }
}
protected void doCommit(DefaultTransactionStatus status) {             // 336 行
    ...
    con.commit();                                                      // 343 行
```

核心就三步：**取连接 → setAutoCommit(false) → 把 ConnectionHolder 绑到当前线程**。挂起/恢复也清晰：`doSuspend`（324-328 行）`unbindResource` 后返回旧 holder，`doResume`（331-333 行）重新 bind——REQUIRES_NEW 的"挂起"物理上就是换绑 ThreadLocal。

### 6.5.3 @Transactional 生效链：从注解到代理

链路：`@EnableTransactionManagement`（@Import `TransactionManagementConfigurationSelector`）→ `selectImports`（`TransactionManagementConfigurationSelector.java:47-53`，PROXY 模式导入 `AutoProxyRegistrar` + `ProxyTransactionManagementConfiguration`）→ 后者注册 `BeanFactoryTransactionAttributeSourceAdvisor`（`BeanFactoryTransactionAttributeSourceAdvisor.java:34`，extends `AbstractBeanFactoryPointcutAdvisor`）+ `TransactionInterceptor`。

【源码证据】`spring-tx/.../annotation/ProxyTransactionManagementConfiguration.java`（46-67 行）：

```java
public BeanFactoryTransactionAttributeSourceAdvisor transactionAdvisor(
        TransactionAttributeSource transactionAttributeSource, TransactionInterceptor transactionInterceptor) {
    BeanFactoryTransactionAttributeSourceAdvisor advisor = new BeanFactoryTransactionAttributeSourceAdvisor();
    advisor.setTransactionAttributeSource(transactionAttributeSource);  // Pointcut：@Transactional 才拦
    advisor.setAdvice(transactionInterceptor);                          // Advice：拦截后干什么
    ...
}
```

这印证第五章结论：**@Transactional 只是一个普通 AOP Advisor**——Pointcut 匹配带注解的方法，Advice 就是下面的拦截器。

### 6.5.4 TransactionInterceptor：invokeWithinTransaction 全流程

【源码证据】`spring-tx/.../interceptor/TransactionInterceptor.java` invoke（123-134 行）→ 委托给 `TransactionAspectSupport.invokeWithinTransaction`（333-409 行）：

```java
TransactionAttributeSource tas = getTransactionAttributeSource();
final TransactionAttribute txAttr = (tas != null ? tas.getTransactionAttribute(method, targetClass) : null);
final TransactionManager tm = determineTransactionManager(txAttr, targetClass);
...
PlatformTransactionManager ptm = asPlatformTransactionManager(tm);
...
TransactionInfo txInfo = createTransactionIfNecessary(ptm, txAttr, joinpointIdentification); // 365：开事务
Object retVal;
try {
    retVal = invocation.proceedWithInvocation();    // 371：执行业务方法（链上下一个拦截器/目标）
}
catch (Throwable ex) {
    completeTransactionAfterThrowing(txInfo, invocation, ex);  // 375：按规则决定 rollback/commit
    throw ex;
}
finally {
    cleanupTransactionInfo(txInfo);                 // 379：恢复旧 TransactionInfo
}
...
commitTransactionAfterReturning(txInfo);            // 408：正常返回 → 提交
```

```text
代理方法调用
│
├─ TransactionInterceptor.invoke()                       (TransactionInterceptor.java:123)
│    └─ invokeWithinTransaction(method, targetClass, callback)
│         ├─ 读 @Transactional 属性 → TransactionAttribute
│         ├─ determineTransactionManager() → 选 PTM（默认按类型找唯一/指定 qualifier）
│         ├─ createTransactionIfNecessary()
│         │    └─ ptm.getTransaction(txAttr)
│         │         ├─ isExistingTransaction()? ──否──► doBegin()：取连接+setAutoCommit(false)+绑定线程
│         │         └─ 是 ──► handleExistingTransaction()（见 6.5.5 传播分支）
│         ├─ retVal = invocation.proceed()                ← 业务代码在这里执行
│         │
│         ├─ [抛异常] completeTransactionAfterThrowing()  (TransactionAspectSupport.java:697)
│         │    ├─ txAttr.rollbackOn(ex) == true  ──► ptm.rollback(status); 重新抛出异常
│         │    └─ == false（如受检异常且未声明）  ──► ptm.commit(status); 继续抛
│         ├─ [正常] commitTransactionAfterReturning() → ptm.commit(status)
│         └─ cleanupTransactionInfo()：弹出 TransactionInfo 栈帧
```

### 6.5.5 传播行为的关键分支：handleExistingTransaction

【源码证据】`spring-tx/.../support/AbstractPlatformTransactionManager.java`（getTransaction 373-385 行 + handleExistingTransaction 426-518 行）：

```java
public final TransactionStatus getTransaction(@Nullable TransactionDefinition definition) throws TransactionException {
    ...
    Object transaction = doGetTransaction();
    if (isExistingTransaction(transaction)) {
        // Existing transaction found -> check propagation behavior to find out how to behave.
        return handleExistingTransaction(def, transaction, debugEnabled);
    }
    ...
    else if (def.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRED ||
            def.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW ||
            def.getPropagationBehavior() == TransactionDefinition.PROPAGATION_NESTED) {
```

handleExistingTransaction 内部的关键分支（426-492 行）：

```java
if (definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW) {
    ...
    SuspendedResourcesHolder suspendedResources = suspend(transaction);  // 450：挂起当前事务
    try {
        return startTransaction(definition, transaction, false, debugEnabled, suspendedResources);
    } ...
}
if (definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_NESTED) {
    if (!isNestedTransactionAllowed()) {
        throw new NestedTransactionNotSupportedException(                // 462
            "Transaction manager does not allow nested transactions by default - ...");
    }
    ...
    status.createAndHoldSavepoint();                                     // 477：JDBC 保存点实现嵌套
}
```

对照记忆：`REQUIRES_NEW = suspend(旧) + doBegin(新)`；`NESTED = 同一连接上打 savepoint`（不支持就抛 `NestedTransactionNotSupportedException`）；`NOT_SUPPORTED = suspend 后空跑`；`NEVER/MANDATORY` 直接抛 `IllegalTransactionStateException`；其余（REQUIRED/SUPPORTS）走"参与现有事务"（494-518 行）。`startTransaction`（524-541 行）统一调 `doBegin`。

### 6.5.6 同一事务共用一个 Connection 的原理：ThreadLocal 资源绑定

**白话**：Service 开了事务，两个 DAO 各自 `JdbcTemplate` 执行 SQL，凭什么自动同生共死？答案：`TransactionSynchronizationManager` 用 ThreadLocal 把 `ConnectionHolder` 按 DataSource 为 key 绑到线程上；DAO 取连接时不直连 DataSource，而是先查这个 ThreadLocal。

【源码证据】`spring-tx/.../support/TransactionSynchronizationManager.java`（77-92 行）：

```java
private static final ThreadLocal<Map<Object, Object>> resources =       // key=DataSource，value=ConnectionHolder
private static final ThreadLocal<Set<TransactionSynchronization>> synchronizations =
private static final ThreadLocal<String> currentTransactionName =
private static final ThreadLocal<Boolean> currentTransactionReadOnly =
private static final ThreadLocal<Integer> currentTransactionIsolationLevel =
private static final ThreadLocal<Boolean> actualTransactionActive =
```

【源码证据】`spring-jdbc/.../jdbc/datasource/DataSourceUtils.java` doGetConnection（103-118 行）：

```java
public static Connection doGetConnection(DataSource dataSource) throws SQLException {
    Assert.notNull(dataSource, "No DataSource specified");
    ConnectionHolder conHolder = (ConnectionHolder) TransactionSynchronizationManager.getResource(dataSource);
    if (conHolder != null && (conHolder.hasConnection() || conHolder.isSynchronizedWithTransaction())) {
        conHolder.requested();
        ...
        return conHolder.getConnection();     // 事务中：直接复用线程绑定的连接
    }
    // Else we either got no holder or an empty thread-bound holder here.
    logger.debug("Fetching JDBC Connection from DataSource");
    Connection con = fetchConnection(dataSource);   // 无事务：才真正去池里拿
```

首尾呼应：6.5.2 的 `doBegin` 末尾 `bindResource(dataSource, connectionHolder)`（310 行）负责"绑"，这里的 `getResource` 负责"取"，事务完成时的 synchronization 负责"解绑还池"。`@Transactional` 失效一句话总结：**自调用（this.method()）不走代理、方法非 public（代理拦截不到）、异常被 try-catch 吞掉、rollback 规则不匹配（默认只对 RuntimeException/Error 回滚，见 `RuleBasedTransactionAttribute.rollbackOn`，`spring-tx/.../RuleBasedTransactionAttribute.java:123-143`，最深匹配规则获胜）——其中前三个的共同本质都是"拦截器没被走到"**。

## 6.6 spring-jdbc：模板方法 + 异常转译

**白话**：JDBC 裸写要"开连接→建语句→执行→遍历→关资源→处理 SQLException"六板斧，每次都写。JdbcTemplate 把不变的六板斧固定成模板，把变化的一步（"这条 SQL 怎么执行/结果怎么映射"）作为回调参数交给你。

【源码证据】`spring-jdbc/src/main/java/org/springframework/jdbc/core/JdbcTemplate.java`（118、404-435 行）：

```java
private <T extends @Nullable Object> T execute(StatementCallback<T> action, boolean closeResources) throws DataAccessException {
    Assert.notNull(action, "Callback object must not be null");
    Connection con = DataSourceUtils.getConnection(obtainDataSource());   // 优先拿线程绑定的连接！
    Statement stmt = null;
    try {
        stmt = con.createStatement();
        applyStatementSettings(stmt);
        T result = action.doInStatement(stmt);      // 回调：你的 SQL 在这里执行
        handleWarnings(stmt);
        return result;
    }
    catch (SQLException ex) {
        ...
        throw translateException("StatementCallback", sql, ex);           // 异常转译
    }
    finally {
        if (closeResources) { JdbcUtils.closeStatement(stmt); DataSourceUtils.releaseConnection(...); }
    }
}
```

注意第一行就是 6.5.6 的 `DataSourceUtils.getConnection`——**JdbcTemplate 与声明式事务天生互通**。`query/update` 等 60 多个便捷方法全部是这个 execute 的不同回调包装（如 `execute(String sql)`，443-462 行，构造 `ExecuteStatementCallback` 匿名类）。

**异常转译**：为什么要翻译？① SQLException 是检查型异常，污染业务代码；② 它只携带厂商错误码，语义不可移植。Spring 把它翻译成非检查型的 `DataAccessException` 体系（`BadSqlGrammarException`、`DuplicateKeyException`、`UncategorizedSQLException` 等）。

【源码证据】`spring-jdbc/.../core/JdbcTemplate.java:1549-1552` + `spring-jdbc/.../support/SQLExceptionTranslator.java:39`：

```java
protected DataAccessException translateException(String task, @Nullable String sql, SQLException ex) {
    DataAccessException dae = getExceptionTranslator().translate(task, sql, ex);
    return (dae != null ? dae : new UncategorizedSQLException(task, sql, ex));
}
// interface SQLExceptionTranslator {  (support/SQLExceptionTranslator.java:39)
//     DataAccessException translate(...);
```

翻译器实现链在 `spring-jdbc/.../support/` 目录：`SQLExceptionSubclassTranslator`（先看 JDBC 4.x 异常子类）→ `SQLErrorCodeSQLExceptionTranslator`（按数据库厂商错误码表 SQLErrorCodesFactory）→ `SQLStateSQLExceptionTranslator`（SQLState 前缀），逐级回退；`BadSqlGrammarException.java:37` 继承 `InvalidDataAccessResourceUsageException`，最终都汇入 `org.springframework.dao.DataAccessException`。

## 6.7 其余模块速览

每个模块的统一配方：**一个核心抽象 + 若干第三方实现适配器 + 与容器/事务/AOP 的打通**——它们是"第三方技术在 IoC 里的适配器"这一论断的最好例证。

- **spring-orm**：把 Hibernate/JPA 装进 Spring 的资源与事务体系。关键类 `LocalContainerEntityManagerFactoryBean`（`spring-orm/.../orm/jpa/LocalContainerEntityManagerFactoryBean.java:97`，把 JPA 的 EntityManagerFactory 变成容器 Bean，负责读 persistence.xml/建 EMF）与 `JpaTransactionManager`（`JpaTransactionManager.java:117`，`extends AbstractPlatformTransactionManager`——注意它复用了 6.5 的同一套事务骨架，只是 doBegin 里换成"从 EntityManagerFactory 拿 EntityManager 并挂到线程"）。有了它，@Transactional 对 JPA 和 JDBC 是同一个注解、同一套传播语义。
- **spring-r2dbc**：响应式 JDBC 替代。关键类 `DatabaseClient`（`spring-r2dbc/.../r2dbc/core/DatabaseClient.java`，流式 SQL DSL）与 `R2dbcTransactionManager`（`spring-r2dbc/.../r2dbc/connection/R2dbcTransactionManager.java`，实现的是 `ReactiveTransactionManager` 而非阻塞版接口）；6.5.4 源码里 341-358 行的 `ReactiveTransactionSupport` 分支就是为它准备的。注意：`R2dbcEntityTemplate` 属于 Spring Data R2DBC 项目，不在 framework 仓库内。
- **spring-oxm**：Object↔XML 映射抽象，接口 `Marshaller/Unmarshaller`（`spring-oxm/.../oxm/Marshaller.java`、`Unmarshaller.java`），`jaxb`/`xstream` 子包提供实现，统一异常体系（`XmlMappingException`）。如今是存留度最高但使用最少的模块之一。
- **spring-jms**：JMS 胶水。`JmsTemplate`（`spring-jms/.../jms/core/JmsTemplate.java:100`，`extends JmsDestinationAccessor implements JmsOperations`）同步收发，内部同样走 `ConnectionFactoryUtils` 取"事务内绑定"的 Connection（1212 行 `JmsTemplateResourceFactory`）；`@JmsListener` + 监听容器提供异步消费，可由 `@EnableJms` 驱动。
- **spring-messaging**：消息编程模型的地基——`Message/MessageHeaders/MessageChannel`（`spring-messaging/.../messaging/Message.java` 等），加上"注解驱动的消息处理器"：`@MessageMapping`（`spring-messaging/.../messaging/handler/annotation/MessageMapping.java:34`）。它复用了一套类似 MVC 的 `HandlerMethodArgumentResolver`，spring-websocket 的 STOMP 支持就构建在它上面。
- **spring-test**：TestContext 框架——`@ContextConfiguration`、`ContextLoader`、上下文缓存（`spring-test/.../test/context/` 目录，`ContextConfiguration.java` 等）；注解 `@TestExecutionListeners`、事务测试回滚支持（`test/annotation/`）；以及整套 Mock 对象：`MockHttpServletRequest`（`spring-test/.../mock/web/MockHttpServletRequest.java`）、`MockEnvironment`（`mock/env/MockEnvironment.java`）和 `MockMvc`（`spring-test/.../test/web/servlet/MockMvc.java`，不开真服务器就能跑完整 DispatcherServlet 流程）。一句话定位：**@SpringBootTest 属于 Spring Boot；这里提供的是可独立使用的测试基建**。

## 6.8 本章小结

- Web 与数据访问模块 = 核心容器在具体领域装配的**策略接口族**：`DispatcherServlet` 装配 8 组策略跑请求，`PlatformTransactionManager` 一族抽象事务资源，`HttpMessageConverter` 一族抽象序列化。
- `doDispatch` 与 `invokeWithinTransaction` 是两条值得背下来的主链路：前者是"映射→适配→拦截→异常→渲染"五段式；后者是"取属性→开事务→执行→按 rollbackOn 决定提交/回滚"四段式。
- `@Transactional` 没有任何魔法：它是 `BeanFactoryTransactionAttributeSourceAdvisor` 这个普通 AOP 切面 + `TransactionInterceptor` + `ThreadLocal` 连接绑定三件套；理解了它，就同时复习了第五章的 AOP。


---

# 七、扩展机制大全：Spring 的插件系统

如果说前面几章讲的是 Spring"自己怎么干活"，本章要回答的是另一个问题：**Spring 为什么被称为"框架中的框架"**。答案藏在它的源码结构里——容器启动的每一步、Bean 生命周期的每个阶段，几乎都对应着一个可以由你实现的接口。Spring 核心团队写的代码，本质上是在"编排"一批扩展点：`@Autowired` 是扩展点实现的、AOP 代理是扩展点织入的、`@Configuration` 类是扩展点解析的、`MyBatis Mapper` 是扩展点造出来的。你看懂了这些接口，Spring 就从"黑盒魔法"变成了"明牌流水线"。

本章基于 Spring Framework 7.1.0-SNAPSHOT（main 分支）源码逐一求证。先上总表：

## 扩展点总表

| 扩展点 | 触发时机 | 典型用途 |
|---|---|---|
| BeanDefinitionRegistryPostProcessor | refresh() 第 4 步之前，改 BeanDefinition 注册表 | 解析 @Configuration、@Import、扫描注册 |
| BeanFactoryPostProcessor | refresh() 第 4 步，Bean 实例化前改 BeanDefinition | 占位符替换 ${}、属性覆盖 |
| BeanPostProcessor | initializeBean 前后两个钩子 | 代理生成、ApplicationListener 收集 |
| InstantiationAwareBeanPostProcessor | 实例化前/后、属性填充三个钩子 | @Autowired 注入、AOP 短路创建 |
| SmartInstantiationAwareBeanPostProcessor | 循环依赖提前暴露、类型预测 | 三级缓存提前引用、构造器候选 |
| DestructionAwareBeanPostProcessor | 容器关闭销毁 Bean 前 | @PreDestroy 执行 |
| Aware 回调接口 | Bean 初始化各阶段 | 把容器基础设施"递"给 Bean |
| FactoryBean | getBean 时 getObject() | 接入第三方对象（MyBatis、Dubbo） |
| @Import（Selector/Registrar） | 配置类解析阶段 | @EnableAsync、@EnableAutoConfiguration |
| ApplicationListener / @EventListener | publishEvent 广播时 | 解耦的业务事件、容器事件 |
| ApplicationEventMulticaster | refresh() 第 7 步初始化，同名 Bean 可覆盖 | 自定义广播（异步、错误隔离） |
| Converter / Formatter / GenericConverter | 类型转换、属性绑定 | 枚举转换、日期格式化 |
| @PropertySource / PropertySource | Environment 初始化 | 外部化配置文件 |
| ApplicationContextInitializer | 容器引导期（外层调用） | AOT 预生成、环境定制 |
| SmartInitializingSingleton | 所有单例就绪后 | @EventListener 注册、启动后校验 |
| LifecycleProcessor | refresh 完成 / close 时 | SmartLifecycle 优雅启停 |
| Scope | getBean 时按作用域取对象 | request/session、自定义线程级作用域 |
| spring.factories (SpringFactoriesLoader) | 类路径扫描 META-INF/spring.factories | Spring Boot 自动配置基石 |
| AOT Processor | 构建期生成原生镜像代码 | GraalVM native 支持 |
| @Indexed + spring-context-indexer | 编译期生成索引 | 启动性能：扫描变查表 |

---

## 7.1 BeanFactoryPostProcessor：改造 BeanDefinition 的总开关

**解决什么问题？** Bean 还没实例化时，你就想批量修改它们的"设计图纸"（BeanDefinition）——改属性值、改作用域、甚至凭空注册新 Bean。Spring 把"读图纸 → 开工"之间留了一个巨大的口子。

**① 接口定义**

`spring-beans/src/main/java/org/springframework/beans/factory/config/BeanFactoryPostProcessor.java:70-82`：

```java
public interface BeanFactoryPostProcessor {
	/**
	 * Modify the application context's internal bean factory after its standard
	 * initialization.
	 */
	void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException;
}
```

子接口 `BeanDefinitionRegistryPostProcessor`（`spring-beans/src/main/java/org/springframework/beans/factory/support/BeanDefinitionRegistryPostProcessor.java:34-56`）多给了你**注册表本身**：

```java
public interface BeanDefinitionRegistryPostProcessor extends BeanFactoryPostProcessor {
	void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException;
}
```

**② 框架何时调用**

调用入口在 `refresh()` 的第 4 步。`spring-context/src/main/java/org/springframework/context/support/AbstractApplicationContext.java:582-626` 的 refresh 流水线中，`invokeBeanFactoryPostProcessors(beanFactory)` 位于第 604 行——**早于**第 606 行的 BPP 注册和第 622 行的单例实例化。真正干活的是 `PostProcessorRegistrationDelegate`，`AbstractApplicationContext.java:795-796`：

```java
protected void invokeBeanFactoryPostProcessors(ConfigurableListableBeanFactory beanFactory) {
	PostProcessorRegistrationDelegate.invokeBeanFactoryPostProcessors(beanFactory, getBeanFactoryPostProcessors());
```

排序逻辑在 `spring-context/src/main/java/org/springframework/context/support/PostProcessorRegistrationDelegate.java:68-209`，这是全 Spring 最重要的"次序契约"：**先执行 BBDRP 再执行 BFP，每类内部又按 PriorityOrdered → Ordered → 无序分三轮**。87-150 行处理 BBDRP（其中 107-119 行先跑 PriorityOrdered 实现，135-150 行还有一个 while 循环反复扫描——因为后注册的处理器可能带来新的处理器），152-154 行让 BBDRP 再走一遍 BFP 回调，164-204 行才轮到普通 BFP：

```java
// First, invoke the BeanDefinitionRegistryPostProcessors that implement PriorityOrdered.   (行 107)
...
// Finally, invoke all other BeanDefinitionRegistryPostProcessors until no further ones appear.
boolean reiterate = true;                                                                   // (行 135)
while (reiterate) {
	reiterate = false;
	postProcessorNames = beanFactory.getBeanNamesForType(BeanDefinitionRegistryPostProcessor.class, true, false);
	...
}
// Now, invoke the postProcessBeanFactory callback of all processors handled so far.
invokeBeanFactoryPostProcessors(registryProcessors, beanFactory);                           // (行 153)
invokeBeanFactoryPostProcessors(regularPostProcessors, beanFactory);
```

方法开头的 WARNING 注释（71-82 行）明确警告"多个循环是有意为之，改之前先看被拒绝的 PR 列表"——这段排序契约是 Spring 社区反复博弈的结果，初学者只需记住结论：**你的 BBDRP 如果必须最先跑，就实现 PriorityOrdered**。

**③ 框架内置实现**

- `ConfigurationClassPostProcessor`（`spring-context/src/main/java/org/springframework/context/annotation/ConfigurationClassPostProcessor.java:152-154`）：`public class ConfigurationClassPostProcessor implements BeanDefinitionRegistryPostProcessor, BeanRegistrationAotProcessor, BeanFactoryInitializationAotProcessor, PriorityOrdered, ...`——它是整个注解驱动的容器的发动机，`postProcessBeanDefinitionRegistry`（304-316 行）里调用的 `processConfigBeanDefinitions` 负责解析 @Configuration、@ComponentScan、@Import、@Bean。没有它，Spring 7 就退回 XML 时代。
- `PropertySourcesPlaceholderConfigurer`（`spring-context/src/main/java/org/springframework/context/support/PropertySourcesPlaceholderConfigurer.java:130-157`）：在 `postProcessBeanFactory` 里组装 PropertySources，随后 `processProperties`（173-191 行）构造 `StringValueResolver`，最终经父类 `PlaceholderConfigurerSupport.doProcessProperties`（`spring-beans/src/main/java/org/springframework/beans/factory/config/PlaceholderConfigurerSupport.java:238-256`）用 `BeanDefinitionVisitor` 逐个访问 BeanDefinition 完成 `${}` 替换：

```java
String[] beanNames = beanFactoryToProcess.getBeanDefinitionNames();      // PlaceholderConfigurerSupport:243
for (String curName : beanNames) {
	if (!(curName.equals(this.beanName) && beanFactoryToProcess.equals(this.beanFactory))) {
		BeanDefinition bd = beanFactoryToProcess.getBeanDefinition(curName);
		visitor.visitBeanDefinition(bd);
	}
}
```

**④ 最小示例**

```java
@Component
public class MyRenameBfpp implements BeanFactoryPostProcessor {
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory bf) {
        BeanDefinition bd = bf.getBeanDefinition("userService");
        bd.setLazyInit(true);          // 批量把某些 Bean 改成懒加载
    }
}
```

---

## 7.2 BeanPostProcessor：Bean 生命周期的"安检门"

**解决什么问题？** 7.1 是在"图纸阶段"动手，7.2 则是在**每个 Bean 对象创建的多个瞬间**动手：初始化前、初始化后、实例化前、属性填充时、销毁前。Spring 生态一半的"魔法"（代理、注入、监听器收集）都装在这里。

**① 接口定义（一父三子）**

`spring-beans/src/main/java/org/springframework/beans/factory/config/BeanPostProcessor.java:67-111`：

```java
public interface BeanPostProcessor {
	default @Nullable Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
		return bean;
	}
	default @Nullable Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		return bean;
	}
}
```

三个关键子接口都在 `org.springframework.beans.factory.config` 包：

- `InstantiationAwareBeanPostProcessor.java:44-105`：`postProcessBeforeInstantiation`（70 行，实例化前短路）、`postProcessAfterInstantiation`（89 行）、`postProcessProperties`（105 行，**属性填充钩子——@Autowired 的真正落点**）；
- `DestructionAwareBeanPostProcessor.java:30-57`：`postProcessBeforeDestruction`（44 行）、`requiresDestruction`（57 行）；
- `SmartInstantiationAwareBeanPostProcessor.java:37-105`：`predictBeanType`（50 行）、`determineCandidateConstructors`（78 行）、`getEarlyBeanReference`（105 行，三级缓存的灵魂）。

**② 框架何时调用**

全部在 `AbstractAutowireCapableBeanFactory`（下称 AACBF）的创建流程中。`spring-beans/src/main/java/org/springframework/beans/factory/support/AbstractAutowireCapableBeanFactory.java`：

- **实例化前短路**：`createBean`（487-539 行）在 513 行调用 `resolveBeforeInstantiation`；后者（1123-1139 行）调用 `applyBeanPostProcessorsBeforeInstantiation`（1152-1160 行）执行 `postProcessBeforeInstantiation`——一旦返回非 null，`createBean` 直接把这个对象当最终 Bean 返回，**真正的实例化根本不会发生**（AOP 场景的快捷通道）。
- **属性填充**：`populateBean`（1389-1460 行）在 1417 行调用 `postProcessAfterInstantiation`（返回 false 可中断填充），1438-1449 行调用 `postProcessProperties`：

```java
if (hasInstantiationAwareBeanPostProcessors()) {                        // populateBean:1438
	if (pvs == null) { pvs = mbd.getPropertyValues(); }
	for (InstantiationAwareBeanPostProcessor bp : getBeanPostProcessorCache().instantiationAware) {
		PropertyValues pvsToUse = bp.postProcessProperties(pvs, bw.getWrappedInstance(), beanName);
		if (pvsToUse == null) { return; }
		pvs = pvsToUse;
	}
}
```

- **初始化前后**：`initializeBean`（1809-1834 行）的骨架是 `invokeAwareMethods → applyBeanPostProcessorsBeforeInitialization → invokeInitMethods → applyBeanPostProcessorsAfterInitialization`。
- **提前引用**：`doCreateBean`（555-648 行）在 595 行向三级缓存注册工厂 `addSingletonFactory(beanName, () -> getEarlyBeanReference(beanName, mbd, bean))`；`getEarlyBeanReference`（965-973 行）遍历 SmartInstantiationAwareBeanPostProcessor：

```java
protected Object getEarlyBeanReference(String beanName, RootBeanDefinition mbd, Object bean) {
	Object exposedObject = bean;
	if (!mbd.isSynthetic() && hasInstantiationAwareBeanPostProcessors()) {
		for (SmartInstantiationAwareBeanPostProcessor bp : getBeanPostProcessorCache().smartInstantiationAware) {
			exposedObject = bp.getEarlyBeanReference(exposedObject, beanName);   // 行 969
		}
	}
	return exposedObject;
}
```

- **类型预测**：`predictBeanType`（651-666 行）在 657-658 行让 BPP 预测代理后的最终类型。
- **BPP 的注册次序**：`PostProcessorRegistrationDelegate.registerBeanPostProcessors`（211-292 行）同样按 PriorityOrdered（258-260 行）→ Ordered（262-272 行）→ 无序（274-283 行）注册，最后 291 行把 `ApplicationListenerDetector` 重新加到链尾"以便看到代理"。

**③ 内置实现举例**

| 实现类 | 覆盖点 | 干什么 |
|---|---|---|
| `AutowiredAnnotationBeanPostProcessor`（spring-beans，158 行声明） | MergedBD + SmartInstantiation + postProcessProperties(490 行) | 处理 @Autowired/@Value/@Inject，注入逻辑在内部类的 `inject`（733、809 行） |
| `CommonAnnotationBeanPostProcessor`（spring-context，141 行） | 继承 `InitDestroyAnnotationBeanPostProcessor`（spring-beans，87 行） | @Resource/@PostConstruct/@PreDestroy；后者 214 行的 postProcessBeforeInitialization 触发 @PostConstruct |
| `ApplicationContextAwareProcessor`（spring-context，66 行） | postProcessBeforeInitialization | Aware 体系下半场，见 7.3 |
| `ApplicationListenerDetector`（spring-context，75-81 行） | postProcessAfterInitialization | 发现 Bean 实现 ApplicationListener 就注册进容器 |
| `AbstractAutoProxyCreator`（spring-aop，getEarlyBeanReference 在 237 行，wrapIfNecessary 在 321 行） | beforeInstantiation/afterInitialization/getEarlyBeanReference | AOP：`@Transactional`、@Aspect 生成代理 |

**④ 最小示例**

```java
@Component
public class AuditBeanPostProcessor implements BeanPostProcessor {
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        System.out.println("Bean 就绪: " + beanName);   // 观察每个 Bean 的出生
        return bean;
    }
}
```

---

## 7.3 Aware 体系：框架对 Bean 的"单向通知"

**解决什么问题？** 你想在 Bean 内部拿到容器、环境、Bean 名字等基础设施，又不想依赖注入 API。Spring 的方案不是字段注入，而是一组"被动回调"接口——框架在特定时机主动调你的 setter。

**① 接口定义**

核心三个在 `spring-beans/src/main/java/org/springframework/beans/factory/`（`Aware.java` 标记接口 + `BeanNameAware` / `BeanClassLoaderAware` / `BeanFactoryAware`）；ApplicationContext 级的六个在 `spring-context/src/main/java/org/springframework/context/`（`EnvironmentAware` / `ResourceLoaderAware` / `ApplicationEventPublisherAware` / `MessageSourceAware` / `ApplicationStartupAware` / `ApplicationContextAware`）。

**② 框架何时调用**

分两拨。第一拨在 AACBF `initializeBean` 的第一步，`AbstractAutowireCapableBeanFactory.java:1836-1851`：

```java
private void invokeAwareMethods(String beanName, Object bean) {
	if (bean instanceof Aware) {
		if (bean instanceof BeanNameAware beanNameAware) {
			beanNameAware.setBeanName(beanName);
		}
		if (bean instanceof BeanClassLoaderAware beanClassLoaderAware) { ... }
		if (bean instanceof BeanFactoryAware beanFactoryAware) {
			beanFactoryAware.setBeanFactory(AbstractAutowireCapableBeanFactory.this);
		}
	}
}
```

第二拨由 `ApplicationContextAwareProcessor` 补齐。该处理器在 `AbstractApplicationContext.prepareBeanFactory`（730-776 行）的 737 行被注册，738-744 行同时把对应 Aware 接口声明为 `ignoreDependencyInterface`（避免它们走普通依赖注入、只走回调）。`spring-context/src/main/java/org/springframework/context/support/ApplicationContextAwareProcessor.java:90-112`：

```java
private void invokeAwareInterfaces(Object bean) {
	if (bean instanceof EnvironmentAware environmentAware) {
		environmentAware.setEnvironment(this.applicationContext.getEnvironment());
	}
	...
	if (bean instanceof ApplicationContextAware applicationContextAware) {
		applicationContextAware.setApplicationContext(this.applicationContext);
	}
}
```

**③ 设计点评**

为什么用回调而不是给个 `ApplicationContextHolder` 静态字段？三个理由：**显式声明**——一个类实现 `ApplicationContextAware`，依赖关系写进了类型系统，测试时可以自己 mock 传参；**时机可控**——框架保证在属性填充之后、init 之前调用，不会拿到半成品；**最小权限**——只实现 `BeanNameAware` 的类拿不到整个容器，符合接口隔离。

**④ 最小示例**

```java
@Component
public class IdGenerator implements BeanNameAware {
    private String beanName;
    @Override
    public void setBeanName(String name) { this.beanName = name; }
}
```

---

## 7.4 FactoryBean：把第三方对象"造"进容器

**解决什么问题？** 有些对象不适合用 `new + 属性注入` 描述——MyBatis 的 Mapper 是运行时动态代理，Dubbo 的引用要发起远程调用。FactoryBean 让"Bean 的生产过程"完全由你编码决定。

**① 接口定义**

`spring-beans/src/main/java/org/springframework/beans/factory/FactoryBean.java:65-148`：

```java
public interface FactoryBean<T> {
	String OBJECT_TYPE_ATTRIBUTE = "factoryBeanObjectType";   // 行 75
	@Nullable T getObject() throws Exception;                 // 行 95：真正的产品
	@Nullable Class<?> getObjectType();                       // 行 116：产品类型
	default boolean isSingleton() { return true; }            // 行 143
}
```

**② 框架何时调用**

关键在 `AbstractBeanFactory`（`spring-beans/src/main/java/org/springframework/beans/factory/support/AbstractBeanFactory.java`）。`getObjectForBeanInstance`（1854-1895 行）是**每个 getBean 的必经关卡**：若名字带 `&` 前缀（1858 行 `BeanFactoryUtils.isFactoryDereference(name)`），直接返回 FactoryBean 本身（1868 行）；否则若实例是 FactoryBean，就调 1892 行 `getObjectFromFactoryBean` 拿产品：

```java
if (BeanFactoryUtils.isFactoryDereference(name)) {          // getObjectForBeanInstance:1858
	...
	return beanInstance;                                     // "&userService" 拿到的是 FactoryBean
}
...
object = getObjectFromFactoryBean(factoryBean, requiredType, beanName, !synthetic);  // 行 1892
```

`FactoryBeanRegistrySupport.getObjectFromFactoryBean`（`spring-beans/src/main/java/org/springframework/beans/factory/support/FactoryBeanRegistrySupport.java:119-187`）负责缓存单例产品（147-165 行 `factoryBeanObjectCache`）、197 行 `doGetObjectFromFactoryBean` 才真正调用 `factory.getObject()`，并通过 259 行的 `postProcessObjectFromFactoryBean` 给子类（如 DefaultListableBeanFactory，让 BPP 也能处理产品）留口子。

**③ 内置与生态实现**

- 内置：`ProxyFactoryBean`（`spring-aop/src/main/java/org/springframework/aop/framework/ProxyFactoryBean.java:93`）——XML 时代 AOP 的入口；还有 `ListFactoryBean`、`PropertiesFactoryBean` 等"集合/资源工厂"。
- 生态：MyBatis-Spring 的 `MapperFactoryBean`（外部仓库）通过 getObject() 返回 `sqlSession.getMapper(...)` 动态代理；Spring Boot 对第三方框架的整合大量依赖 FactoryBean。**一句话：FactoryBean 是"第三方对象接入容器"的标准转接头。**

**④ 最小示例**

```java
@Component("invoiceClient")
public class InvoiceClientFactoryBean implements FactoryBean<InvoiceClient> {
    @Override
    public InvoiceClient getObject() { return InvoiceProxy.create("https://api.example.com"); }
    @Override
    public Class<?> getObjectType() { return InvoiceClient.class; }
}
// ctx.getBean("invoiceClient") 拿到 InvoiceClient；ctx.getBean("&invoiceClient") 拿到工厂本身
```

---

## 7.5 @Import 的三种（如今四种）用法

**解决什么问题？** `@ComponentScan` 只能扫"打了注解的类"，而框架提供的开关（如 `@EnableAsync`）需要把**任意类、按任意逻辑**注册进容器——这是 `@Import` 的地盘，也是 Spring Boot 自动配置的技术基石。

**① 接口定义**

`ImportSelector`（`spring-context/src/main/java/org/springframework/context/annotation/ImportSelector.java:62-69`）：

```java
public interface ImportSelector {
	String[] selectImports(AnnotationMetadata importingClassMetadata);
}
```

`DeferredImportSelector`（同包 `DeferredImportSelector.java:39`，内含 56 行的 `Group` 接口）是延迟版——**等所有普通配置类都处理完才跑**，从而能基于"全量 Bean 状态"做决策。`ImportBeanDefinitionRegistrar`（同包）则把 `BeanDefinitionRegistry` 直接交给你。

**② 框架何时调用**

`ConfigurationClassParser.processImports`（`spring-context/src/main/java/org/springframework/context/annotation/ConfigurationClassParser.java:581-652`）是分发器，它由 `doProcessConfigurationClass` 的 364 行 `processImports(configClass, sourceClass, getImports(sourceClass), filter, true)` 触发，按类型分四路：

```java
if (candidate.isAssignable(ImportSelector.class)) {                     // 行 595
	...
	if (selector instanceof DeferredImportSelector deferredImportSelector) {
		this.deferredImportSelectorHandler.handle(configClass, deferredImportSelector);   // 行 605
	}
	else {
		String[] importClassNames = selector.selectImports(currentSourceClass.getMetadata());
		processImports(configClass, currentSourceClass, importSourceClasses, filter, false); // 行 608-610
	}
}
else if (candidate.isAssignable(BeanRegistrar.class)) { ... }           // 行 613-621，7.0 新增的编程式注册分支
else if (candidate.isAssignable(ImportBeanDefinitionRegistrar.class)) { // 行 622
	configClass.addImportBeanDefinitionRegistrar(registrar, currentSourceClass.getMetadata());
}
else {
	processConfigurationClass(candidate.asConfigClass(configClass), filter);  // 行 636，普通 @Configuration 类
}
```

注意两个细节：604-611 行显示普通 ImportSelector 会**递归** processImports（返回值还能继续是 Selector）；613-621 行的 `BeanRegistrar` 分支为 **7.0 新增**——@Import 如今支持四种玩法。

**③ 生态实现**

- `@EnableAsync` → `AsyncConfigurationSelector`（`spring-context/src/main/java/org/springframework/scheduling/annotation/AsyncConfigurationSelector.java:33-45`，继承 `AdviceModeImportSelector<EnableAsync>`），按 `@EnableAsync(mode=...)` 返回 `ProxyAsyncConfiguration` 或 AspectJ 配置类名。
- `ImportBeanDefinitionRegistrar` 的代表作：@MapperScan（MyBatis-Spring）、Dubbo 的 @EnableDubbo。
- Spring Boot 的 `@EnableAutoConfiguration` → `AutoConfigurationImportSelector`（不在本仓库）正是基于 `DeferredImportSelector`：等业务配置类处理完、再从 spring.factories 里筛选自动配置。

**④ 最小示例**

```java
public class LogSelector implements ImportSelector {
    @Override
    public String[] selectImports(AnnotationMetadata md) {
        return new String[]{ AuditService.class.getName() };   // 动态决定注册谁
    }
}
@Import(LogSelector.class)
@Configuration
public class AppConfig { }
```

---

## 7.6 事件机制：进程内的发布-订阅扩展点

**解决什么问题？** 组件 A 发生了变化，组件 B、C 想知道，但不想直接引用 A。Spring 提供进程内的发布-订阅，且订阅/广播两端都开放了扩展。

**① 接口定义**

`ApplicationListener<E extends ApplicationEvent>`（spring-context，`org.springframework.context.ApplicationListener`）；注解版 `@EventListener`（`org.springframework.context.event.EventListener`）；广播器 `ApplicationEventMulticaster`（`org.springframework.context.ApplicationEventMulticaster`）。

**② 框架何时调用**

- 发布：`AbstractApplicationContext.publishEvent`（`spring-context/src/main/java/org/springframework/context/support/AbstractApplicationContext.java:415-461`），449 行委托给 multicaster，普通对象会被 433 行包装成 `PayloadApplicationEvent`。
- 广播器初始化：`initApplicationEventMulticaster`（854-871 行）——**这里埋了一个"同名 Bean 覆盖"的口子**：

```java
protected void initApplicationEventMulticaster() {                       // 行 854
	ConfigurableListableBeanFactory beanFactory = getBeanFactory();
	if (beanFactory.containsLocalBean(APPLICATION_EVENT_MULTICASTER_BEAN_NAME)) {   // 行 856
		this.applicationEventMulticaster =
				beanFactory.getBean(APPLICATION_EVENT_MULTICASTER_BEAN_NAME, ApplicationEventMulticaster.class);
	}
	else {
		this.applicationEventMulticaster = new SimpleApplicationEventMulticaster(beanFactory);  // 行 864
```

你只要注册一个名为 `applicationEventMulticaster` 的 Bean 就能替换默认广播器（比如设置异步 taskExecutor）。

- @EventListener 的注册方是 `EventListenerMethodProcessor`（`spring-context/src/main/java/org/springframework/context/event/EventListenerMethodProcessor.java:65-66`，`implements SmartInitializingSingleton, ...`）：它不是急着自己跑，而是在 `afterSingletonsInstantiated`（108-150 行）里遍历全部 Bean，`processBean`（152-199 行）找到 @EventListener 方法后经 `EventListenerFactory` 适配成 ApplicationListener 加入容器（191 行 `context.addApplicationListener`）。
- 泛型事件匹配：`AbstractApplicationEventMulticaster`（`spring-context/src/main/java/org/springframework/context/event/AbstractApplicationEventMulticaster.java`）的 `supportsEvent`（343-379、393-399 行）用 `ResolvableType` 解析监听器声明的泛型参数（357 行 `genericEventType.isAssignableFrom(eventType)`），所以 `@EventListener(OrderCreatedEvent.class)` 能精确过滤。多播执行在 `SimpleApplicationEventMulticaster.multicastEvent`（132-149 行，141 行支持异步 Executor）。

**③ 内置事件**：`ContextRefreshedEvent`（finishRefresh 的 1017 行发布）、`ContextClosedEvent`、`PayloadApplicationEvent` 等。

**④ 最小示例**

```java
@Component
public class OrderListener {
    @EventListener
    public void on(OrderCreatedEvent e) { System.out.println("收到: " + e.orderId()); }
}
// publisher: ctx.publishEvent(new OrderCreatedEvent(42L));
```

---

## 7.7 类型转换与格式化：Converter / Formatter

**解决什么问题？** 配置值都是字符串，字段却是枚举、日期、Duration。Spring 的类型转换体系完全开放：实现接口、注册进 ConversionService 即可。

**① 接口定义**（均在 `spring-core/src/main/java/org/springframework/core/convert/`）

- `Converter.java:38-46`：`public interface Converter<S, T extends @Nullable Object> { T convert(S source); }`
- `ConverterFactory.java:33`：一个工厂覆盖"同一基类的全部子类型"。
- `GenericConverter.java:49-66`：`Object convert(@Nullable Object source, TypeDescriptor sourceType, TypeDescriptor targetType);`——能看到字段注解等上下文，配合 `ConditionalGenericConverter`（`ConditionalGenericConverter.java:33`）。
- 格式化：`Formatter<T>`（`spring-context/src/main/java/org/springframework/format/Formatter.java:27`，`extends Printer<T>, Parser<T>`），注册入口 `FormatterRegistry`（同目录 `FormatterRegistry.java:30`，`extends ConverterRegistry`）。

**② 框架何时调用**

ConversionService 装配在 `AbstractApplicationContext.finishBeanFactoryInitialization`（943-996 行）的 954-959 行：容器查找名为 `conversionService` 的 Bean 并设进 BeanFactory，此后属性填充、配置绑定都走它。Web 端的 DataBinder 也复用同一套体系（详见 Web 章）。

**③ 内置实现**：`DefaultConversionService` 预置了 100+ 转换器；`@DateTimeFormat`、`@NumberFormat` 由 `FormattingConversionService` 桥接 Formatter 实现。

**④ 最小示例**

```java
public class StringToRoleConverter implements Converter<String, Role> {
    @Override
    public Role convert(String source) { return Role.valueOf(source.toUpperCase()); }
}
// 注册：ctx.getBeanFactory().setConversionService(new MyConversionService()) 或向
// FormattingConversionServiceFactoryBean 注册，Web 场景实现 WebMvcConfigurer.addFormatters
```

---

## 7.8 Environment 扩展：@PropertySource 与属性源链

**解决什么问题？** "配置从哪来"要可插拔：JVM 参数、环境变量、文件、Nacos……Spring 把它们抽象为有序的 `PropertySource` 栈，任何人都能插入一层。

**① 机制**：`@PropertySource` 注解的解析在 `ConfigurationClassParser.doProcessConfigurationClass`（`spring-context/src/main/java/org/springframework/context/annotation/ConfigurationClassParser.java:314-325`），委托给 `PropertySourceRegistry.processPropertySource`（`spring-context/src/main/java/org/springframework/context/annotation/PropertySourceRegistry.java:57-77`）——后者读取 name/encoding/factory 属性后交给 `PropertySourceProcessor` 加载并加入 Environment：

```java
Class<? extends PropertySourceFactory> factoryClassToUse =                 // 行 70-75
		(factoryClass != PropertySourceFactory.class ? factoryClass : null);
PropertySourceDescriptor descriptor = new PropertySourceDescriptor(Arrays.asList(locations),
		ignoreResourceNotFound, name, factoryClassToUse, encoding);
this.propertySourceProcessor.processPropertySource(descriptor);
```

注意 `factory` 属性：实现自定义 `PropertySourceFactory`（比如加载 YAML）就能改变解析行为。**占位符解析链**在 `spring-core/src/main/java/org/springframework/core/env/PropertySourcesPropertyResolver.java:73-91`——`getProperty` 按 PropertySources 的**声明顺序**逐个询问 `propertySource.getProperty(key)`，先到先得，拿到后再做嵌套占位符递归解析；7.1 节的 PlaceholderConfigurer 底层正是它。

**② 最小示例**

```java
@Configuration
@PropertySource(value = "classpath:app.properties", encoding = "UTF-8",
                factory = YamlPropertySourceFactory.class)   // 自定义工厂也行
public class AppConfig { }
```

---

## 7.9 容器级扩展点合集

**1. ApplicationContextInitializer**：`spring-context/src/main/java/org/springframework/context/ApplicationContextInitializer.java:43-49`，`void initialize(C applicationContext)`。有意思的是，**7.x 框架核心的 refresh() 里并没有它的调用点**——接口 Javadoc（@see `ContextLoader#customizeContext`、`FrameworkServlet#applyInitializers`）与生态实践（Spring Boot 的 `SpringApplication.applyInitializers`、AOT 的 `AotApplicationContextInitializer`，`spring-context/src/main/java/org/springframework/context/aot/AotApplicationContextInitializer.java:46`）表明它是留给"外层引导器"的契约。

**2. SmartInitializingSingleton**：`spring-beans/src/main/java/org/springframework/beans/factory/SmartInitializingSingleton.java:44-56`。回调点在 `DefaultListableBeanFactory.preInstantiateSingletons`（`spring-beans/src/main/java/org/springframework/beans/factory/support/DefaultListableBeanFactory.java:1102-1151`）的**末尾**：

```java
for (String beanName : beanNames) {                                     // 行 1142
	Object singletonInstance = getSingleton(beanName, false);
	if (singletonInstance instanceof SmartInitializingSingleton smartSingleton) {
		smartSingleton.afterSingletonsInstantiated();                   // 行 1147
	}
}
```

此时**所有非懒加载单例都已就绪**——7.6 节的 EventListenerMethodProcessor 正是靠它才敢扫描全量 Bean。

**3. LifecycleProcessor**：`spring-context/src/main/java/org/springframework/context/LifecycleProcessor.java:26-61`（`onRefresh`/`onClose`）。`AbstractApplicationContext.finishRefresh`（1003-1018 行）在 1014 行调用 `getLifecycleProcessor().onRefresh()` 驱动 SmartLifecycle Bean 的优雅启动，1017 行发布 ContextRefreshedEvent；它同样支持同名 Bean `lifecycleProcessor` 覆盖（`initLifecycleProcessor`，880-898 行）。

**4. MergedBeanDefinitionPostProcessor**：`spring-beans/src/main/java/org/springframework/beans/factory/support/MergedBeanDefinitionPostProcessor.java:38-47`。调用点在 `doCreateBean`（AACBF:576 行，经 1109-1113 行的 `applyMergedBeanDefinitionPostProcessors`）：合并后的 BeanDefinition 定稿瞬间回调——AutowiredAnnotationBeanPostProcessor 在这里缓存 @Autowired 注入点元数据，每个 Bean 定义只做一次。

**5. BeanNameGenerator**：`spring-beans/src/main/java/org/springframework/beans/factory/support/BeanNameGenerator.java:28`。策略化 Bean 命名：`ConfigurationClassPostProcessor` 用 `IMPORT_BEAN_NAME_GENERATOR`（全限定名，165-166 行）给 @Import 的类命名，你可以在 `AnnotationConfigApplicationContext.setBeanNameGenerator` 传入自己的实现。

**6. Scope 扩展**：`spring-beans/src/main/java/org/springframework/beans/factory/config/Scope.java:61-154`——`get`（75 行，拿到 ObjectFactory，懒加载交给你）、`registerDestructionCallback`（124 行）。注册：`AbstractBeanFactory.registerScope`（1076-1088 行，禁止覆盖 singleton/prototype）；消费点在 `doGetBean`（372-396 行）：非 singleton/prototype 的 Bean 一律 `scope.get(beanName, () -> createBean(...))`：

```java
Scope scope = this.scopes.get(scopeName);                               // doGetBean:377
Object scopedInstance = scope.get(beanName, () -> {                     // 行 382
	beforePrototypeCreation(beanName);
	try { return createBean(beanName, mbd, args); }
	finally { afterPrototypeCreation(beanName); }
});
```

request/session 作用域（spring-web 的 `AbstractRequestAttributesScope.java:42`、`RequestScope`、`SessionScope`）就是这样接进来的；`CustomScopeConfigurer`（spring-beans，48 行）作为 BeanFactoryPostProcessor 批量注册自定义 Scope（101 行 `beanFactory.registerScope(scopeKey, scope)`）。

---

## 7.10 spring.factories 与 AOT：面向"整条生态链"的扩展

**解决什么问题？** 前面的扩展点都发生在"容器内部"，spring.factories 解决的是**容器还没建立时**的发现机制：jar 包里放一个清单文件，宿主应用按类型加载实现。

**① 机制**：`spring-core/src/main/java/org/springframework/core/io/support/SpringFactoriesLoader.java:98` 定义资源位置 `META-INF/spring.factories`；核心 API（192-208 行，6.0 起为实例式）：

```java
public <T> List<T> load(Class<T> factoryType, @Nullable ArgumentResolver argumentResolver,
		@Nullable FailureHandler failureHandler) {                 // 行 192
	List<String> implementationNames = loadFactoryNames(factoryType);
	...
	for (String implementationName : implementationNames) {
		T factory = instantiateFactory(implementationName, factoryType, argumentResolver, failureHandlerToUse);
		...
	}
	AnnotationAwareOrderComparator.sort(result);                   // 行 206
}
```

旧版静态方法 `loadFactories`（246-248 行）与 `loadFactoryNames`（263-266 行）已标注 `@Deprecated(since = "6.0")`。**一句话定位：Spring Boot 自动配置与 Spring Cloud 的扩展基石**——`AutoConfigurationImportSelector` 从这些文件读取自动配置类（Boot 3 起迁移到 `AutoConfiguration.imports`，但 SpringFactoriesLoader 仍负责加载框架内部各策略，如 BootstrapExecutor 的 DETECT）。

**② AOT 扩展面**（Spring 6 新增，GraalVM 原生镜像的编译期口子）：

- `spring-beans/src/main/java/org/springframework/beans/factory/aot/BeanFactoryInitializationAotProcessor.java:48,62`：`@Nullable BeanFactoryInitializationAotContribution processAheadOfTime(ConfigurableListableBeanFactory beanFactory);`——构建期检查整个 BeanFactory，产出代码贡献。
- `spring-beans/src/main/java/org/springframework/beans/factory/aot/BeanRegistrationAotProcessor.java:51,76`：`processAheadOfTime(RegisteredBean registeredBean)`——按单个 Bean 注册产出反射/代理/资源提示（86 行 `isBeanExcludedFromAotProcessing` 可豁免）。
- 内部用户：7.1 节的 `ConfigurationClassPostProcessor` 类声明（152-153 行）就同时实现了这两个接口——它在 AOT 运行时替代自己完成配置类解析。

---

## 7.11 @Indexed 与 spring-context-indexer：编译期的性能口子

**解决什么问题？** @ComponentScan 启动时递归扫 classpath，应用越大越慢。方案：编译期就生成"谁是谁"的索引文件，运行时查表代替扫描。

**机制**：注解 `@Indexed`（`spring-context/src/main/java/org/springframework/stereotype/Indexed.java:90`）——注意 @Component、@Repository 等都已元标注它；`spring-context-indexer` 模块（`spring-context-indexer/src/main/java/org/springframework/context/indexer/CandidateComponentsIndexer.java`）是注解处理器，编译期写出 `META-INF/spring.components`（常量见 `CandidateComponentsIndexLoader.java:49` 的 `COMPONENTS_RESOURCE_LOCATION`）。运行时 `ClassPathScanningCandidateComponentProvider`（`spring-context/src/main/java/org/springframework/context/annotation/ClassPathScanningCandidateComponentProvider.java`）在 268 行自动加载索引，`findCandidateComponents`（312-322 行）走索引分支：

```java
public Set<BeanDefinition> findCandidateComponents(String basePackage) {    // 行 312
	if (this.componentsIndex != null && indexSupportsIncludeFilters()) {
		if (this.componentsIndex.hasScannedPackage(basePackage)) {
			return addCandidateComponentsFromIndex(this.componentsIndex, basePackage);   // 行 315：查表
		}
		else { this.componentsIndex.registerScan(basePackage); }
	}
	return scanCandidateComponents(basePackage);                             // 行 321：传统扫描
}
```

**给初学者的提醒**：索引是"全有或全无"的优化——只要部分 jar 带索引，未覆盖的包会退回扫描（321 行），且自定义 TypeFilter 可能不被索引支持（330-337 行 `indexSupportsIncludeFilters`），生产中需谨慎评估。

---

## 本章小结

把本章扩展点按 refresh() 的时序串起来，就是 Spring 的"插件总装图"：

1. **图纸阶段**（refresh 第 4 步）：BBDRP 注册定义 → BFP 修改定义（ConfigurationClassPostProcessor、占位符）；
2. **生产阶段**（每个 Bean）：InstantiationAware BPP 短路/填充 → MergedBD BPP 缓存元数据 → Aware 回调 → init 前后 BPP（AOP 代理在 afterInitialization）→ FactoryBean 包装产品；
3. **就绪阶段**：SmartInitializingSingleton（@EventListener 注册）→ LifecycleProcessor.onRefresh → ContextRefreshedEvent；
4. **生态阶段**：@PropertySource/spring.factories 在启动前后双向扩展，AOT 与 @Indexed 把部分工作挪到编译期。

初学者最该记住的三点：**想改 BeanDefinition 用 BFPP，想改 Bean 用 BPP，想把第三方对象接进来用 FactoryBean**；其余扩展点都是这三招在特定时机的变体。下一章我们将沿着这条流水线走一遍完整源码。


---

# 八、贯通视图：三条时间线看懂 Spring 全貌

前面各章是"分镜头"，本章把它们剪成"正片"：容器启动一条线、单个 Bean 生命周期一条线、一次 HTTP 请求一条线，三条线在扩展点处交叉。所有方法与行号均已在对应章节验证，此处标注出处便于回查。

## 8.1 时间线一：容器启动（refresh 全程）

```
new AnnotationConfigApplicationContext(AppConfig.class)
│  (AnnotationConfigApplicationContext 构造器, 68-93 行)
├─ 1. new AnnotatedBeanDefinitionReader(this)
│      └─ AnnotationConfigUtils.registerAnnotationConfigProcessors
│           注册 6 个内部处理器 BD（ConfigurationClassPostProcessor 等, 158-202 行)★ 扩展点预埋
├─ 2. register(AppConfig.class) → 配置类作为 AnnotatedGenericBeanDefinition 入册
└─ 3. refresh()（AbstractApplicationContext:582-662）
     ├─ [0] 加锁（6.2 起 ReentrantLock，虚拟线程友好）
     ├─ [1] prepareRefresh：置 active、earlyApplicationEvents 攒事件
     ├─ [2] obtainFreshBeanFactory：子类刷新内部 DefaultListableBeanFactory
     ├─ [3] prepareBeanFactory：注册 ApplicationContextAwareProcessor★、
     │       ignoreDependencyInterface(Aware 族)、registerResolvableDependency(容器自身)
     ├─ [4] postProcessBeanFactory：模板方法（Web 子类扩展）
     ├─ [5] invokeBeanFactoryPostProcessors ★★ 全文最关键一步
     │      └─ PostProcessorRegistrationDelegate（68-209 行）
     │          先 BeanDefinitionRegistryPostProcessor 后 BeanFactoryPostProcessor，
     │          各按 PriorityOrdered→Ordered→无序 三轮：
     │          ├─ ConfigurationClassPostProcessor.processConfigBeanDefinitions
     │          │    解析 @ComponentScan → ClassPathBeanDefinitionScanner（ASM 扫描）
     │          │           @Bean/@Import/@PropertySource → do-while 收敛
     │          │    此时全部业务 BD 才真正进容器
     │          └─ PropertySourcesPlaceholderConfigurer → ${} 占位符替换
     ├─ [6] registerBeanPostProcessors：实例化并注册 BPP（Autowired/AOP 自动代理创建器…）★
     ├─ [7] initMessageSource / [8] initApplicationEventMulticaster（同名 Bean 可覆盖）★
     ├─ [9] onRefresh：模板方法（DispatcherServlet 在此 initStrategies，见 6.3.1）
     ├─ [10] registerListeners：静态监听器 + ApplicationListener Bean 名
     ├─ [11] finishBeanFactoryInitialization
     │      └─ preInstantiateSingletons（DefaultListableBeanFactory:1102-1151）
     │          逐个 getBean 触发「时间线二」；支持 backgroundInit 并行（6.2 引入）
     │          末尾回调 SmartInitializingSingleton（@EventListener 在此注册）★
     └─ [12] finishRefresh：initLifecycleProcessor → SmartLifecycle 按 phase 启动★
             → publishEvent(ContextRefreshedEvent)★
```

**观察**：启动全程就是"扩展点依次触发"的编排——`★★` 处都是用户/模块可介入的位置。Spring 自己的注解体系（@ComponentScan、@Bean、@EventListener）恰恰是这些口子的首位用户。

## 8.2 时间线二：单个 Bean 的一生（getBean 全程）

```
getBean("userService")
└─ AbstractBeanFactory.doGetBean（249-268 行）
   ├─ getSingleton 查三级缓存（一级成品 → 二级早期引用 → 三级工厂）
   ├─ getObjectForBeanInstance：FactoryBean 解引用（& 前缀）★
   ├─ 找不到 → 父容器委派 → getMergedLocalBeanDefinition → dependsOn 先建依赖
   └─ scope 分叉（342-396 行）
      └─ createBean（AACBF:481-528）
         ├─ resolveBeforeInstantiation：InstantiationAwareBPP 短路机会★（AOP TargetSource）
         └─ doCreateBean（555-647）四步：
            (1) createBeanInstance：工厂方法/@Bean → 构造器注入 → 无参反射
            │    ↑ SmartInstantiationAwareBPP.determineCandidateConstructors 可指定构造器★
            │    ↓ 实例化后立刻 addSingletonFactory(() -> getEarlyBeanReference(...)) ★循环依赖
            (2) populateBean（1389-1460）
            │    ├─ postProcessAfterInstantiation★（可中断填充）
            │    ├─ autowireByName/ByType（XML 传统装配）
            │    └─ postProcessProperties★ → AutowiredAnnotationBeanPostProcessor
            │         在此完成 @Autowired/@Value 注入（占位符 ${} → SpEL #{} → 类型转换）
            (3) initializeBean（1809-1851）
            │    ├─ invokeAwareMethods：BeanName/ClassLoader/BeanFactoryAware★
            │    ├─ applyBeanPostProcessorsBeforeInitialization
            │    │    └─ ApplicationContextAwareProcessor 回调 6 类 Context-Aware★
            │    │    └─ CommonAnnotationBeanPostProcessor 触发 @PostConstruct★
            │    ├─ invokeInitMethods：InitializingBean.afterPropertiesSet → init-method
            │    └─ applyBeanPostProcessorsAfterInitialization
            │         └─ AbstractAutoProxyCreator.wrapIfNecessary（321-345）★
            │              findEligibleAdvisors 匹配 → ProxyFactory 生成 JDK/CGLIB 代理
            │              —— 容器注册的是代理，原对象藏进 SingletonTargetSource
            (4) registerDisposableBeanIfNecessary：打包 DisposableBeanAdapter★
   ……使用期：proxy.method() → 拦截器链 proceed()（见 5.6）……
   容器 close：DisposableBeanAdapter.destroy
      ① DestructionAwareBPP（@PreDestroy）→ ② DisposableBean → ③ AutoCloseable → ④ destroy-method
```

## 8.3 时间线三：一次 HTTP 请求（叠加了前两条线）

```
浏览器 → Tomcat
└─ DispatcherServlet.doDispatch（935-1004 行，6.3.2 节逐行解析）
   ├─ checkMultipart → getHandler：RequestMappingHandlerMapping.lookupHandlerMethod
   │    （启动期注册的 @RequestMapping 映射表，见 6.3.3）
   ├─ applyPreHandle：HandlerInterceptor 前置（拦截器本身也是容器 Bean —— 时间线二的产物）
   ├─ getHandlerAdapter → RequestMappingHandlerAdapter.handle
   │    ├─ 参数解析：@RequestParam/@RequestBody（HttpMessageConverter 反序列化 + @Valid）
   │    ├─ 反射调用 Controller 方法
   │    │    └─ 若方法带 @Transactional：
   │    │         TransactionInterceptor.invokeWithinTransaction（333-409 行）
   │    │         开事务(doBegin: 连接绑定 ThreadLocal) → 业务方法 → commit/rollbackOn
   │    │         （Controller 调 Service 的对象是 AOP 代理 —— 时间线二的 (3) 步产物）
   │    └─ 返回值处理：@ResponseBody → HttpMessageConverter 写 JSON
   ├─ applyPostHandle / processDispatchResult
   │    ├─ 异常 → HandlerExceptionResolver（@ExceptionHandler，@ControllerAdvice 兜底）
   │    └─ render / 直接写响应
   └─ triggerAfterCompletion：拦截器收尾（成功失败都走）
```

**观察**：一次请求里，`Controller/Service` 是时间线二生产的代理 Bean；`HandlerMapping/Adapter/Converter` 是时间线一装配的策略组件；`@Transactional` 是第五章的责任链 + 第六章的事务拦截器。三条时间线在此交汇——这正是"Spring 是一个编排体系"的直观呈现。

## 8.4 从源码中提炼的四个设计模式视角

1. **模板方法**：`refresh()`（子类插钩子 obtainFreshBeanFactory/onRefresh/postProcessBeanFactory）、`JdbcTemplate.execute`（不变六板斧 + 回调变化点）、`AbstractPlatformTransactionManager.getTransaction`（骨架固定，doBegin/doCommit 交给资源方言）。
2. **策略 + 工厂**：`DefaultAopProxyFactory` 二选一（JDK/CGLIB）、`HandlerAdapter` 族、`PlatformTransactionManager` 族——策略接口定义能力，容器负责装配，用户换实现零侵入。
3. **观察者（事件）**：ContextRefreshedEvent/ClosedEvent、@EventListener、multicaster 可覆盖——容器内部状态变化全部事件化，外部（如 Spring Boot 的启动日志、Cloud 的上下文刷新）借此挂钩。
4. **代理与适配**：AOP 代理（JDK/CGLIB）、`DisposableBeanAdapter`（统一三类销毁钩子）、`FactoryBean`（第三方对象转接头）、`TypeConverterDelegate`（新旧转换体系桥接）——凡是"两套世界要对接"，Spring 都造一个适配器，而不是改造任何一方。

---

# 九、附录

## 9.1 关键接口速查表

| 接口 / 类 | 模块 | 一句话 |
|---|---|---|
| BeanFactory | spring-beans | 容器顶层接口，getBean 四重载 + FACTORY_BEAN_PREFIX "&" |
| DefaultListableBeanFactory | spring-beans | 全能容器实现（可配置+可列举+可注册） |
| BeanDefinition / BeanDefinitionRegistry | spring-beans | Bean 的配方元数据 / 配方注册中心 |
| Scope | spring-beans | 自定义作用域策略（request/session/自定义） |
| InitializingBean / DisposableBean | spring-beans | 初始化/销毁回调（非侵入替代品：init-method/destroy-method） |
| BeanPostProcessor（+3 子接口） | spring-beans | Bean 生命周期各阶段的拦截插件（AOP/@Autowired 的宿主） |
| BeanFactoryPostProcessor / BeanDefinitionRegistryPostProcessor | spring-beans | 实例化前改配方/注册配方 |
| FactoryBean<T> | spring-beans | "工厂 Bean"：编码决定产品如何生产 |
| Resource / ResourceLoader | spring-core | 统一资源抽象与前缀分发 |
| Environment / PropertySource | spring-core | 配置统一视图与有序优先级 |
| Converter / GenericConversionService | spring-core | 新一代类型转换体系 |
| ResolvableType / MethodParameter | spring-core | 泛型擦除的驯服者 |
| MetadataReader（ASM） | spring-core | 不加载类读取类元数据（扫描的引擎） |
| ApplicationContext | spring-context | 容器+事件+国际化+资源+环境的"整车" |
| ApplicationListener / @EventListener | spring-context | 容器内事件订阅 |
| MessageSource | spring-context | 国际化消息层级查找 |
| Lifecycle / SmartLifecycle | spring-context | 优雅启停（phase 排序） |
| ImportSelector / ImportBeanDefinitionRegistrar / BeanRegistrar(7.0) | spring-context | @Import 的动态注册三种玩法 |
| AopProxy / Advisor / Pointcut / MethodInterceptor | spring-aop | 代理创建 / 通知+切点单元 / 切点 / 统一拦截器 |
| ProxyFactory / AbstractAutoProxyCreator | spring-aop | 手工代理 / 容器自动代理（BPP） |
| SpelExpressionParser / EvaluationContext | spring-expression | SpEL 解析与求值环境 |
| PlatformTransactionManager / TransactionDefinition | spring-tx | 事务管理器三方法 / 事务属性（传播、隔离） |
| TransactionInterceptor | spring-tx | @Transactional 的 Advice 实现 |
| JdbcTemplate | spring-jdbc | 模板方法 + 异常转译 |
| DispatcherServlet / HandlerMapping / HandlerAdapter | spring-webmvc | 前端控制器 + 两级策略（映射/执行） |
| HttpMessageConverter | spring-web | 请求/响应体与对象的互转策略族 |
| HandlerMethodArgumentResolver | spring-web | 控制器参数解析策略族 |
| SpringFactoriesLoader | spring-core | spring.factories 加载（Boot 自动配置基石） |
| BeanFactoryInitializationAotProcessor / BeanRegistrationAotProcessor | spring-beans | AOT 编译期扩展面（Spring 6+） |

## 9.2 初学者学习路线（动手向）

1. **用起来（第 1 天）**：不依赖 Spring Boot，用 `AnnotationConfigApplicationContext` + `@Configuration`/`@Component`/`@Bean` 搭一个最小容器；加一个 `BeanPostProcessor` 打印每个 Bean 的出生。对应第二、四章。
2. **跟踪生命周期（第 2~3 天）**：在 IDEA 里断点 `AbstractBeanFactory#doGetBean` 与 `AbstractAutowireCapableBeanFactory#doCreateBean`，观察 2.6 节四步；故意造一个 A↔B 循环依赖，在三级缓存三行字段上打断点。
3. **读懂启动（第 4~5 天）**：断点 `AbstractApplicationContext#refresh()`，重点单步 `invokeBeanFactoryPostProcessors`（观察 ConfigurationClassPostProcessor 如何"翻译"你的 @ComponentScan）与 `preInstantiateSingletons`。
4. **看穿代理（第 6~7 天）**：写一个 @Aspect，断点 `AbstractAutoProxyCreator#wrapIfNecessary` 与 `ReflectiveMethodInvocation#proceed()`；再测 `@Transactional` 自调用失效、final 方法失效，回到源码找原因（5.5、5.7 节）。
5. **自己写扩展点（第 2 周）**：实现一个 `BeanFactoryPostProcessor`（批量改 BD）、一个 `FactoryBean`（包装第三方 SDK 客户端）、一个 `ImportSelector`（仿 @EnableAsync），对应第七章。
6. **走一遍请求（第 3 周）**：用 MockMvc 或真 Tomcat，断点 `DispatcherServlet#doDispatch` 九段流程与 `TransactionInterceptor` 事务链。
7. **进阶**：读第三章的 Resource/Environment/类型转换（写自定义 Converter），再看 Spring Boot 的 `SpringApplication.run` 如何把 refresh() 包进自动配置（AutoConfigurationImportSelector 基于 DeferredImportSelector）。

## 9.3 源码阅读入口清单（30 个关键文件）

按"必读度"排序，路径相对 `D:\code\3rd\spring-framework`：

1. spring-beans/src/main/java/org/springframework/beans/factory/support/AbstractBeanFactory.java（doGetBean）
2. spring-beans/src/main/java/org/springframework/beans/factory/support/AbstractAutowireCapableBeanFactory.java（createBean/doCreateBean/populateBean/initializeBean）
3. spring-beans/src/main/java/org/springframework/beans/factory/support/DefaultSingletonBeanRegistry.java（三级缓存）
4. spring-context/src/main/java/org/springframework/context/support/AbstractApplicationContext.java（refresh）
5. spring-context/src/main/java/org/springframework/context/support/PostProcessorRegistrationDelegate.java（BFPP/BPP 排序契约）
6. spring-context/src/main/java/org/springframework/context/annotation/ConfigurationClassPostProcessor.java（注解容器发动机）
7. spring-context/src/main/java/org/springframework/context/annotation/ConfigurationClassParser.java（@ComponentScan/@Import 解析）
8. spring-context/src/main/java/org/springframework/context/annotation/AnnotationConfigUtils.java（内部处理器注册）
9. spring-beans/src/main/java/org/springframework/beans/factory/config/BeanDefinition.java（配方接口）
10. spring-aop/src/main/java/org/springframework/aop/framework/AbstractAutoProxyCreator.java（自动代理）
11. spring-aop/src/main/java/org/springframework/aop/framework/DefaultAopProxyFactory.java（JDK/CGLIB 选择）
12. spring-aop/src/main/java/org/springframework/aop/framework/ReflectiveMethodInvocation.java（proceed 责任链）
13. spring-aop/src/main/java/org/springframework/aop/framework/JdkDynamicAopProxy.java / CglibAopProxy.java
14. spring-aop/src/main/java/org/springframework/aop/aspectj/annotation/ReflectiveAspectJAdvisorFactory.java（@Aspect 解析）
15. spring-beans/src/main/java/org/springframework/beans/factory/annotation/AutowiredAnnotationBeanPostProcessor.java（@Autowired 注入）
16. spring-context/src/main/java/org/springframework/context/annotation/ConfigurationClassEnhancer.java（@Bean 单例保证）
17. spring-tx/src/main/java/org/springframework/transaction/interceptor/TransactionInterceptor.java / TransactionAspectSupport.java（事务链）
18. spring-tx/src/main/java/org/springframework/transaction/support/AbstractPlatformTransactionManager.java（传播行为）
19. spring-jdbc/src/main/java/org/springframework/jdbc/datasource/DataSourceTransactionManager.java（doBegin/绑定 ThreadLocal）
20. spring-jdbc/src/main/java/org/springframework/jdbc/core/JdbcTemplate.java（模板方法）
21. spring-webmvc/src/main/java/org/springframework/web/servlet/DispatcherServlet.java（doDispatch/initStrategies）
22. spring-webmvc/src/main/java/org/springframework/web/servlet/handler/AbstractHandlerMethodMapping.java（@RequestMapping 注册与匹配）
23. spring-webmvc/src/main/java/org/springframework/web/servlet/mvc/method/annotation/RequestMappingHandlerAdapter.java（方法执行）
24. spring-web/src/main/java/org/springframework/web/method/annotation/RequestResponseBodyMethodProcessor.java（@RequestBody/@Valid）
25. spring-context/src/main/java/org/springframework/context/event/SimpleApplicationEventMulticaster.java（事件广播）
26. spring-core/src/main/java/org/springframework/core/io/DefaultResourceLoader.java（资源前缀分发）
27. spring-core/src/main/java/org/springframework/core/env/StandardEnvironment.java / MutablePropertySources.java（配置优先级）
28. spring-core/src/main/java/org/springframework/core/ResolvableType.java（泛型解析）
29. spring-core/src/main/java/org/springframework/core/type/classreading/SimpleMetadataReader.java（ASM 元数据）
30. spring-expression/src/main/java/org/springframework/expression/spel/standard/InternalSpelExpressionParser.java（SpEL 递归下降）

---

## 结语

回到开篇的问题：Spring 解决了什么？

- 它用一个 **BeanDefinition + BeanFactory** 的极简模型回答了"对象应该由谁创建、如何组装"；
- 用 **后置处理器体系** 回答了"框架如何在不侵入业务的前提下叠加能力"；
- 用 **AOP 责任链** 回答了"横切关注点如何声明式织入"；
- 用 **策略接口 + 适配器** 回答了"Web、事务、数据访问等领域能力如何统一接入"；
- 用 **20+ 个扩展点** 回答了"框架如何从'给用户用的工具'进化为'给生态搭的舞台'"。

这些答案没有一个是复杂的——复杂的是把它们环环相扣地组织在一起，并且二十余年保持兼容。理解了本文的证据链之后，再去读 Spring Boot 的自动配置、Spring Cloud 的上下文扩展，都会是"似曾相识"：它们全部构建在你已经读过的这些扩展点之上。


---

