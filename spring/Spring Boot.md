# Spring Boot 深度源码解析（写给初学者的架构全景）

> **本文基于的源码**：`D:\code\3rd\spring-boot`，版本 **4.2.0-SNAPSHOT**（main 分支、合并 4.1.x 后的快照，Git commit `7f9eef2c33c`）。文中所有【源码证据】的文件路径与行号均为对该快照实际读取所得。
>
> **配套阅读**：本文是《Spring Framework 深度源码解析》（同目录 Spring-Framework.md）的续篇。Framework 篇讲清了"容器本身怎么工作"（refresh()、Bean 生命周期、AOP、扩展点），本篇回答"Boot 在这些机制之上做了什么"。文中多处标注"呼应 Framework 文档第 X 章"，建议两篇对照阅读。
>
> **版本取舍说明**：Spring Boot 4.0（2025-11-20 GA）对仓库做了大重组——单体 `spring-boot-autoconfigure` 拆分为约 60 个技术模块、`@MockBean` 彻底移除、`spring-boot-starter-web` 更名 `spring-boot-starter-webmvc`。网上多数 Boot 源码文章基于 2.x/3.x，类名、包名、目录与本快照对不上时，以本文（即源码）为准。1.x → 4.x 的演进对比见 1.5 节，所有版本归属结论均经本地 git 标签实证。

## 如何读这份文档

- **第一遍（建立地图）**：读第一章每一节的白话段、各章"小结"、第九章（贯通视图）。目标是能回答：`run()` 在 `refresh()` 之前做了哪几件事？自动配置靠哪两个 Framework 扩展点？Tomcat 的端口为什么在所有 Bean 就绪后才开始监听？
- **第二遍（深入源码）**：顺序建议：第二章（启动流程）→ 第三章（自动配置，Boot 核心）→ 第四章（配置绑定）→ 第五章（内嵌服务器）→ 第八章（扩展机制）→ 第六章（Actuator）/第七章（工具链）随用随查。

---

# 一、总览：Spring Boot 的定位、设计哲学与整体架构

## 1.1 一句话定位

**Spring Boot 不是新容器，而是 Spring Framework 扩展点上的一套自动化编排**。Framework 篇已证明：`AbstractApplicationContext.refresh()` 的每一步都留了钩子（BFPP/BPP/事件/DeferredImportSelector……）。Boot 做的全部事情，就是在 `main()` 与 `refresh()` 之间那段"黑暗期"里把原料备齐——Environment（配置）、BeanDefinition（主类与自动配置）、容器类型（Servlet/Reactive）——然后**原封不动地调用 Framework 的 `refresh()`**（第二章 2.4 节：`SpringApplication.refresh()` 只有一行 `applicationContext.refresh()`）。所谓"魔法"，是 Framework 扩展点的教科书级组合（第三章 3.7 节逐字验证）。

## 1.2 设计哲学：读源码前先记住四句话

1. **约定优于配置，且约定可被覆盖**。自动配置类挂满 `@ConditionalOnMissingBean`：用户定义了就"让位"（第三章 3.6.2 的三级让位链：用户 > Boot > Framework）。整个 Boot 没有一处"强制注册"。
2. **机制自己吃自己的狗粮**。Boot 的每个环节都用 Framework 的扩展点实现：自动配置 = DeferredImportSelector；属性绑定 = BeanPostProcessor；内嵌服务器 = onRefresh 钩子 + SmartLifecycle；条件评估报告 = BeanFactory 单例。Boot 没有为容器开任何"后门"。
3. **SPI 注册文件是骨架**。`META-INF/spring.factories`（基础设施 13 类 key）+ `META-INF/spring/<注解FQN>.imports`（102 个自动配置清单 + 约 80 个测试切片/管理上下文清单）——Boot 的"插件"全部经这两条通道进入（第八章 8.0 节人口普查）。
4. **约定即默认值，默认值可观测**。默认值不是拍脑袋：条件评估有报告（/actuator/conditions）、配置绑定有 Origin 追踪（报错能定位到文件行）、启动全程有事件与 StartupStep。出了问题，Boot 总给你留了"查看为什么"的口子。

## 1.3 模块分层全景（4.x 新布局，实测）

Boot 4.0 把 3.x 的扁平结构重组为按职能分组的目录（`ls` 实测）：

```
┌──────────────────────── 交付与工具（开发者直接接触） ────────────────────────┐
│ starter/   176 个 starter——纯依赖聚合，0 个含 src（第七章 7.4 实测）          │
│ loader/    可执行 jar 三件套：spring-boot-loader / loader-tools / jarmode-tools│
│ build-plugin/  Maven/Gradle 插件（repackage、bootJar）                        │
│ cli/  smoke-test/  system-test/  buildpack/                                   │
├──────────────────────── 技术模块层（每个技术一个模块） ────────────────────────┤
│ module/    130 个模块：tomcat、jetty、web-server、webmvc、webflux、            │
│            data-jpa、data-redis、actuator、health、micrometer-metrics、        │
│            micrometer-observation、devtools、restclient、jackson……             │
│            每个模块自带 AutoConfiguration + imports 文件 + Customizer          │
├──────────────────────── 核心层 ────────────────────────────────────────────────┤
│ core/spring-boot               启动编排：SpringApplication、事件、ConfigData、  │
│                                Binder、日志系统、FailureAnalyzer（第二章）      │
│ core/spring-boot-autoconfigure 自动配置引擎：ImportSelector、条件注解族、      │
│                                ConditionEvaluationReport（第三章）              │
│ core/spring-boot-test(-autoconfigure)  @SpringBootTest 与切片基建（第七章）    │
│ core/spring-boot-docker-compose / -testcontainers / -properties-migrator       │
├──────────────────────── 支撑 ───────────────────────────────────────────────────┤
│ configuration-metadata/  配置元数据处理器（IDE 提示的来源）                    │
│ platform/  antora/  documentation/  integration-test/  …（构建与文档）          │
└────────────────────────────────────────────────────────────────────────────────┘
```

依赖方向一句话：`starter → module → core`；`core → Spring Framework`。运行时你的 classpath 上是"一个 starter 拉进来的若干 module jar + Framework 全家"，而 Boot 核心只负责启动那一刻的编排。

## 1.4 关键问题 → Boot 方案映射（全文导览）

| 裸用 Framework 的痛点 | Boot 的方案 | 详见 |
|---|---|---|
| 依赖版本地狱：挑版本、对兼容 | starter（依赖聚合）+ BOM（统一版本） | 二.2.1、七.7.4 |
| 每个技术都要手写一串 @Bean/XML | 自动配置：imports 名单 + @Conditional 按需装配 | 三 |
| 配置散落、强类型绑定繁琐 | ConfigData（yml/properties 统一加载）+ Binder（宽松绑定、构造器绑定、校验） | 四 |
| war + 外部容器部署链长 | 可执行 jar（JarLauncher/嵌套 jar）+ 内嵌容器（onRefresh 造、finishRefresh 绑端口） | 五、七.7.1 |
| 生产运维缺失 | Actuator：@Endpoint 端点体系 + health/metrics/observation | 六 |
| 开发反馈慢 | devtools：RestartClassLoader 秒级重启 | 七.7.3 |
| 测试要起真容器 | @SpringBootTest 复用生产启动流水线 + 切片测试 TypeExcludeFilter | 七.7.5 |
| 启动失败只有堆栈 | FailureAnalyzer 把异常翻译成"描述+动作"报告 | 二.2.6、八.8.6 |

## 1.5 版本演进：1.x → 2.x → 3.x → 4.x（git 实证）

与 Framework 篇 1.6 节同一方法论：GA 日期取自本地 git 发布 tag 的提交时间，特性归属用跨 tag 的 `git grep`/`git ls-tree` 实证（写作时本地仓库含 v1.0.0.RC4 起的全部 tag）。

| 版本 | GA 时间 | JDK 基线 | 注册文件体系 | 关键变化 |
|---|---|---|---|---|
| 1.0 | 2014-04-01 | Java 6+ | spring.factories 承载一切 | 可执行 jar（JarLauncher）、内嵌容器、Actuator、自动配置"四大件"诞生 |
| 2.0 | 2018-03-01 | **Java 8**（v2.0.0 pom：`<java.version>1.8</java.version>`） | 同上 | 响应式支持（WebFlux/Netty）；2.4 引入 ConfigData 机制重构配置加载 |
| 2.7 | 2022-05-19 | Java 8 | **`AutoConfiguration.imports` 首次登场**（实测 v2.7.0 已有 5 个 imports 文件，autoconfigure 的 spring.factories 中 `EnableAutoConfiguration` 键已清零） | 迁移过渡版：新旧注册方式并存，为 3.0 铺路 |
| 3.0 | 2022-11-24 | **Java 17**（v3.0.0 buildSrc：`JavaVersion.VERSION_17`） | spring.factories 彻底不再承载自动配置 | JDK 17 + jakarta.*；GraalVM native/AOT 正式化；与 Framework 6.0 对齐 |
| 3.4 | 2024-11-21 | Java 17 | 同上 | `@MockBean` 标记废弃（源码实证：`@Deprecated(since = "3.4.0", forRemoval = true)`），能力上交 Framework 的 `@MockitoBean` |
| 4.0 | 2025-11-20 | Java 17（支持至 25） | spring.factories（基础设施 13 类 key）+ imports（102 个自动配置清单） | **仓库大重组**：`core/`、`module/`（130 个技术模块）、`starter/`、`loader/` 分层；单体 autoconfigure 拆分；`@MockBean.java` 从源码消失（git ls-tree 实证 0 个）；`spring-boot-starter-web` 废弃改名 `spring-boot-starter-webmvc` |
| 4.2（本文快照） | 预计 2026-11 | Java 17 | 同上 | 迭代增强（如 `TestRestTemplate` 标记废弃 forRemoval 4.4，改用 Framework 的 RestTestClient） |

三个初学者最容易踩的"版本坑"，源码实证如下：

- **`@MockBean` 别再用了**：3.4.0 废弃、4.0 彻底移除，替代品是 Framework 6.2 的 `org.springframework.test.context.bean.override.mockito.MockitoBean`（Boot 4 仓库里连定义文件都不存在，官方文档测试代码 import 的是 Framework 的包——第七章 7.5.2）。
- **`spring.factories` 与 `*.imports` 的分工**（第八章 8.0 考据）：自动配置类只放 imports；EnvironmentPostProcessor/Initializer/Listener/FailureAnalyzer 等"容器诞生前"的基础设施仍走 spring.factories。
- **网上 2.x/3.x 源码文章的类名大量失效**：`ServletWebServerApplicationContext` 移到了 `module/spring-boot-web-server`、`HealthEndpoint` 移到 `module/spring-boot-health`、`WebEndpointServletHandlerMapping` 类已不存在（仅剩 bean 名）、`TestRestTemplate` 已废弃。以本文（即源码）为准。

## 1.6 全文章节地图

- **第二章 背景与核心启动流程（spring-boot）**：四大痛点的结构性证据；`SpringApplication` 构造器"认清自己"；`run()` 19 步全链路；启动事件时间线；FailureAnalyzer 诊断报告。
- **第三章 自动配置：Boot 的"魔法"引擎**：@SpringBootApplication 解剖；imports 花名册；AutoConfigurationImportSelector 全链路；条件注解族；ConditionEvaluationReport；WebMvcAutoConfiguration 标本解剖。
- **第四章 外部化配置与类型安全绑定**：配置优先级清单；ConfigData 机制；Binder/宽松绑定/构造器绑定；profile；@Validated 校验。
- **第五章 内嵌 Web 服务器**：onRefresh 造服务器（半启动）→ finishRefresh 绑端口（SmartLifecycle）；DispatcherServlet 注册链；错误页与静态资源；反应式栈同构。
- **第六章 Actuator 与生产级运维**：端点定义与暴露协议解耦；EndpointDiscoverer；health 聚合；Micrometer 指标与 Observation。
- **第七章 部署与开发者工具链**：可执行 jar（JarLauncher/嵌套 jar）；构建插件；devtools；starter 机制；@SpringBootTest 与切片测试。
- **第八章 扩展机制大全：Boot 的插件系统**：注册文件体系人口普查；14 类扩展点四段式展开；自定义 Starter 完整步骤。
- **第九章 贯通视图**：run() 19 步 ⊃ refresh() 12 步的全景叠图；三个高频误解的源码级澄清。
- **第十章 附录**：关键类速查表、与 Framework 文档的对照阅读表、学习路线。


---

# 二、背景与核心启动流程（spring-boot）

> 本章源码版本：Spring Boot **4.2.0-SNAPSHOT**（main 分支，`git log` 显示为合并 4.1.x 后的 `7f9eef2c33c`）。仓库根目录 `D:\code\3rd\spring-boot`，采用 4.x 新布局：`core/`（核心）、`module/`（技术模块）、`starter/`（依赖聚合）、`loader/`（可执行 jar 加载器）。除标注"公开史料"的历史叙述外，所有论断均附本仓库实测源码证据。

## 2.1 Boot 产生的背景：裸用 Spring Framework 的四大痛点

**公开史料（非本仓库内容）**：Spring Boot 由 Phillip Webb、Dave Syer 等人在 Pivotal 发起，2013 年开源，2014 年 4 月发布 1.0 GA。它诞生的目的不是替代 Spring Framework，而是解决"裸用 Framework"时反复出现的工程化摩擦。这些痛点在今天的仓库结构里仍然能找到结构性证据。

### 痛点一：依赖版本地狱 → starter + BOM

白话：在 Framework 时代，你想用 Spring MVC + Jackson + 日志 + 数据库连接池，得自己挑每个库的版本，版本之间还互相不兼容——"Spring 版本升级导致 Hibernate 报 ClassNotFound"是那个时代的日常。Boot 的解法有两个：**starter**（把"做某件事"所需的一组依赖打包成一个坐标）和 **BOM**（一份统一版本清单）。

【源码证据】本仓库 `starter/` 目录下 `ls | wc -l` 实测 **176 个条目**，从 `spring-boot-starter`、`spring-boot-starter-webmvc` 到 `spring-boot-starter-actuator` 一应俱全；`module/` 目录实测 **130 个技术模块**。starter 本质只是 POM 依赖聚合，`starter/spring-boot-starter-webmvc` 里没有任何 Java 代码，只声明"引入 webmvc 模块 + tomcat + 校验"等坐标。这就是"starter 治理版本地狱"的活体证据：**依赖组合被产品化为一个名字**。

### 痛点二：配置繁杂 → 自动配置

白话：Framework 时代你要手写 `<mvc:annotation-driven/>` 或一长串 `@Bean` 才能让 MVC、数据源跑起来。Boot 的解法是**自动配置**：启动时根据 classpath 上"有什么类"自动帮你注册这些 Bean，你只需要写业务代码。

【源码证据】自动配置引擎位于 `core/spring-boot-autoconfigure`。4.x 中自动配置候选不再写在 `spring.factories`，而是独立文件 `core/spring-boot-autoconfigure/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（实测存在，每行一个全限定类名，如 `org.springframework.boot.autoconfigure.aop.AopAutoConfiguration`、`...task.TaskExecutionAutoConfiguration` 等，核心模块内实测 12 行；其余自动配置随 130 个 `module/` 各自携带同名 imports 文件）。注册文件从 `spring.factories` 拆出为 `*.imports`，是 2.7→3.0 演进的落点，本章第 2.3 节会给出 Boot 自身仍在用 `spring.factories` 的对照样例。

### 痛点三：部署复杂 → 可执行 jar + 内嵌容器

白话：以前交付一个 Spring 应用，要打 war 包、装一个 Tomcat、把 war 扔进 `webapps/`。"应用"和"容器"是分离的。Boot 把容器**内嵌**进应用，产出一个 `java -jar` 就能跑的 fat jar——部署单元从"war + 容器"退化成"一个文件"。

【源码证据】`loader/` 目录实测包含 `spring-boot-loader`、`spring-boot-loader-tools`、`spring-boot-jarmode-tools` 三个模块（`loader/spring-boot-loader/src/main/java/org/springframework/boot/loader/` 下有 `jar/`、`launch/`、`jarmode/` 等子包）。它提供自定义 jar 布局与 `JarLauncher`，让 JVM 能加载嵌套 jar 里的类——这是"可执行 jar"的物理基础。本章后面会看到，启动流程对 web 应用的处理只是创建对应类型的 `ApplicationContext`，内嵌容器在 refresh 过程中由模块自动装配拉起。

### 痛点四：生产运维缺失 → actuator

白话：应用跑起来了，但"活着吗、占多少内存、哪些配置生效"在生产上怎么看？Boot 内置 actuator：一组开箱即用的运维端点（health、metrics 等）。

【源码证据】`module/spring-boot-actuator`（`org.springframework.boot.actuate` 下实测有 `audit/beans/context/endpoint/env/health/log/...` 子包）与 `module/spring-boot-actuator-autoconfigure` 同在；配套 starter `spring-boot-starter-actuator` 也在 176 个之列。

### 一句话定位：Boot 是 Framework 扩展点上的自动化编排

Boot **不是新容器**。它没有另写一套 IoC，而是把 Framework 留出的扩展点串成一条流水线：`SpringApplication.run()` 先准备好 `Environment`（配置）、`BeanDefinition`（主类）、`ApplicationContextInitializer`，然后**原封不动地调用 Framework 的 `AbstractApplicationContext.refresh()`** 完成容器启动（2.4 节 321 行处源码为证）。自动配置也不是新机制，它只是挂在 `refresh()` 的 `BeanFactoryPostProcessor` 阶段执行的普通配置类。读完本章 run() 全链路，这句话就有了全部代码背书。

## 2.2 core/spring-boot 模块定位与包结构

白话：Boot 4.x 把"以前一个巨型 spring-boot jar"拆成了若干 core 模块，其中 `core/spring-boot` 是**启动编排层**：包含 `SpringApplication` 本体、事件体系、环境后处理、日志、诊断报告等，但不包含具体技术（Tomcat、Data 等都在 `module/`）。`ls core/spring-boot/src/main/java/org/springframework/boot/` 实测结构如下（顶层是零散的核心类，子包按职能分类）：

| 子包/顶层类 | 一句话职责 |
|---|---|
| `SpringApplication.java` 等顶层类 | 启动编排本体：`SpringApplication`、`WebApplicationType`、`ApplicationRunner`/`CommandLineRunner`、`EnvironmentPostProcessor`、`SpringBootExceptionReporter`、`BeanDefinitionLoader`、`SpringApplicationShutdownHook` |
| `bootstrap/` | 引导上下文 `BootstrapContext`/`DefaultBootstrapContext`：refresh 之前临时存对象（如未就绪的数据源），refresh 时移交 |
| `context/` | `context.event/`（7 个启动事件 + `EventPublishingRunListener`）、`context/config/`（ConfigData 引擎：加载 application.properties/yml）、`context/properties/`（绑定） |
| `env/` | `ApplicationEnvironment` 等环境实现与属性源工具（`DefaultPropertiesPropertySource`） |
| `web/` | web 侧公共抽象：内嵌 web server 的统一接口、错误页等（具体实现按 servlet/reactive 分模块） |
| `logging/` | 日志系统抽象：Logback/Log4J2/JavaLogging 统一接口，启动早期初始化日志 |
| `system/` | `JavaVersion`、`PathUtils` 等运行时环境探测小工具 |
| `support/` | 杂项支撑：`EnvironmentPostProcessorApplicationListener`（2.4 节主角）、`AnsiOutput` 等 |
| `builder/` | `SpringApplicationBuilder`：父子容器、流式构建 |
| `availability/` | `AvailabilityChangeEvent`、`LivenessState`/`ReadinessState`：K8s 探针语义的就绪/存活状态 |
| `diagnostics/` | `FailureAnalyzer` 诊断报告框架（2.6 节） |
| 其余 `admin/ ansi/ cloud/ convert/ info/ io/ json/ origin/ retry/ ssl/ task/ thread/ util/ validation/` | JMX 管理、彩色输出、Cloud Foundry 支持、类型转换、应用信息、资源 IO、JSON、配置来源追溯、重试、TLS、任务调度、线程、工具、校验等专项能力 |

## 2.3 SpringApplication 构造器：启动前先"认清自己"

白话：`new SpringApplication(MyApplication.class)` 这一行在 run 之前干了四件事——判断我是不是 web 应用、把"工厂文件"里登记的初始器和监听器装进兜里、找到 main 方法在哪个类。一切"自动"都始于这里。

【源码证据】`core/spring-boot/src/main/java/org/springframework/boot/SpringApplication.java` 构造器（`SpringApplication(ResourceLoader, Class...)`，行 274-284）：

```java
public SpringApplication(@Nullable ResourceLoader resourceLoader, Class<?>... primarySources) {
    this.resourceLoader = resourceLoader;
    Assert.notNull(primarySources, "'primarySources' must not be null");
    this.primarySources = new LinkedHashSet<>(Arrays.asList(primarySources));
    this.properties.setWebApplicationType(WebApplicationType.deduce());          // 行 278
    this.bootstrapRegistryInitializers = new ArrayList<>(
            getSpringFactoriesInstances(BootstrapRegistryInitializer.class));    // 行 279-280
    setInitializers((Collection) getSpringFactoriesInstances(ApplicationContextInitializer.class)); // 行 281
    setListeners((Collection) getSpringFactoriesInstances(ApplicationListener.class));              // 行 282
    this.mainApplicationClass = deduceMainApplicationClass();                    // 行 283
}
```

**（1）推断应用类型。** 老教材里的 `WebApplicationType.deduceFromClasspath()` 在 4.x 已被 SPI 化的 `WebApplicationType.deduce()` 取代（`WebApplicationType.java` 行 63-71，`@since 4.0.1`）：

```java
public static WebApplicationType deduce() {
    for (Deducer deducer : SpringFactoriesLoader.forDefaultResourceLocation().load(Deducer.class)) {
        WebApplicationType deduced = deducer.deduceWebApplicationType();
        if (deduced != null) {
            return deduced;
        }
    }
    return isServletApplication() ? WebApplicationType.SERVLET : WebApplicationType.NONE;
}
```

兜底逻辑 `isServletApplication()`（行 73-80）检查 `SERVLET_INDICATOR_CLASSES = { "jakarta.servlet.Servlet", "org.springframework.web.context.ConfigurableWebApplicationContext" }`（行 55-56）：classpath 上有这两个类才算 SERVLET，否则 NONE（纯命令行应用）。REACTIVE 的判定交给了模块注册的 Deducer：`module/spring-boot-webmvc/src/main/resources/META-INF/spring.factories` 实测内容为 `org.springframework.boot.WebApplicationType$Deducer=org.springframework.boot.webmvc.WebMvcWebApplicationTypeDeducer`，该 Deducer（`WebMvcWebApplicationTypeDeducer.java` 行 35-50，`@Order(10)` 排在 WebFlux 之前）要求 `jakarta.servlet.Servlet`、`DispatcherServlet`、`ConfigurableWebApplicationContext` 三类齐备才返回 SERVLET；`module/spring-boot-webflux` 用同样机制注册 `WebFluxWebApplicationTypeDeducer` 返回 REACTIVE。**注意：这里的注册文件是模块自己的 `META-INF/spring.factories`，而不是 `*.imports`**——`spring.factories` 在 4.x 依然是 Boot 内部基础设施的注册表，`*.imports` 专用于自动配置清单。

**（2）从注册文件加载初始器与监听器。** `getSpringFactoriesInstances`（行 471-473）委托 `SpringFactoriesLoader.forDefaultResourceLocation(classLoader).load(type)`，读取的就是各 jar 的 `META-INF/spring.factories`。核心模块的 `core/spring-boot/src/main/resources/META-INF/spring.factories` 实测登记（摘录）：

```properties
# Run Listeners
org.springframework.boot.SpringApplicationRunListener=\
org.springframework.boot.context.event.EventPublishingRunListener

# Application Context Initializers
org.springframework.context.ApplicationContextInitializer=\
org.springframework.boot.context.ConfigurationWarningsApplicationContextInitializer,\
org.springframework.boot.context.ContextIdApplicationContextInitializer,\
org.springframework.boot.io.ProtocolResolverApplicationContextInitializer

# Application Listeners
org.springframework.context.ApplicationListener=\
org.springframework.boot.ClearCachesApplicationListener,\
org.springframework.boot.builder.ParentContextCloserApplicationListener,\
org.springframework.boot.context.FileEncodingApplicationListener,\
org.springframework.boot.context.logging.LoggingApplicationListener,\
org.springframework.boot.support.AnsiOutputApplicationListener,\
org.springframework.boot.support.EnvironmentPostProcessorApplicationListener
```

也就是说：构造阶段 `this.initializers` 装进 3 个初始器、`this.listeners` 装进 6 个监听器，它们将在 run() 的不同时点被调用。

**（3）推断主类。** `deduceMainApplicationClass()`（行 286-296）用 Java 9+ 的 `StackWalker` 沿调用栈找方法名为 `main` 的第一帧，取其声明类。没有魔法，纯粹是"往栈底看一眼"。

## 2.4 run(String... args) 全链路：Boot 的"总装车间"

先给总表，再逐段展开。行号均指 `SpringApplication.java`。

| 步骤 | 方法（行号） | 作用 |
|---|---|---|
| 1 | `Startup.create()`（305） | 起一个计时器（内部类，见 2.7） |
| 2 | shutdown hook 预备（306-308） | 默认 `registerShutdownHook=true`（`ApplicationProperties.java` 行 74），允许稍后注册 JVM 钩子 |
| 3 | `createBootstrapContext()`（309，344-348） | 创建 `DefaultBootstrapContext`，执行 `BootstrapRegistryInitializer` |
| 4 | `configureHeadlessProperty()`（311，448-451） | 设置 `java.awt.headless=true`（若无显式设置） |
| 5 | `getRunListeners(args)`（312，453-465） | 从 `spring.factories` 加载 `SpringApplicationRunListener`（即 `EventPublishingRunListener`） |
| 6 | `listeners.starting(...)`（313） | 发布 **ApplicationStartingEvent** |
| 7 | `new DefaultApplicationArguments(args)`（315） | 包装命令行参数 |
| 8 | `prepareEnvironment(...)`（316，350-368） | 创建 Environment、**触发 EnvironmentPostProcessor（ConfigData 在此加载）**、绑定 spring.main |
| 9 | `printBanner(environment)`（317，559-570） | 打印 Spring Boot banner |
| 10 | `createApplicationContext()`（318，579-584） | 按应用类型 new 容器实例 |
| 11 | `context.setApplicationStartup(...)`（319） | 注入可观测钩子 |
| 12 | `prepareContext(...)`（320，380-419） | 挂 Environment、跑初始器、注册主类 BeanDefinition |
| 13 | `refreshContext(context)`（321，441-446） | 注册 shutdown hook + **调用 Framework 的 `refresh()`** |
| 14 | `afterRefresh(...)`（322，764-765） | 空模板方法，留给子类 |
| 15 | `startup.started()` + `StartupInfoLogger`（323-326） | 计算"Started ... in X seconds" |
| 16 | `listeners.started(...)`（327） | 发布 **ApplicationStartedEvent** + 存活事件 |
| 17 | `callRunners(...)`（328，767-777） | 排序并执行 Runner |
| 18 | `listeners.ready(...)`（333-337） | 发布 **ApplicationReadyEvent** + 就绪事件 |
| 19 | `return context`（341） | 交付运行中的容器 |

run() 本体（行 304-342）：

```java
public ConfigurableApplicationContext run(String... args) {
    Startup startup = Startup.create();
    if (this.properties.isRegisterShutdownHook()) {
        SpringApplication.shutdownHook.enableShutdownHookAddition();
    }
    DefaultBootstrapContext bootstrapContext = createBootstrapContext();
    ConfigurableApplicationContext context = null;
    configureHeadlessProperty();
    SpringApplicationRunListeners listeners = getRunListeners(args);
    listeners.starting(bootstrapContext, this.mainApplicationClass);
    try {
        ApplicationArguments applicationArguments = new DefaultApplicationArguments(args);
        ConfigurableEnvironment environment = prepareEnvironment(listeners, bootstrapContext, applicationArguments);
        Banner printedBanner = printBanner(environment);
        context = createApplicationContext();
        context.setApplicationStartup(this.applicationStartup);
        prepareContext(bootstrapContext, context, environment, listeners, applicationArguments, printedBanner);
        refreshContext(context);
        afterRefresh(context, applicationArguments);
        Duration timeTakenToStarted = startup.started();
        ...
        listeners.started(context, timeTakenToStarted);
        callRunners(context, applicationArguments);
    }
    catch (Throwable ex) {
        throw handleRunFailure(context, ex, listeners);
    }
    ...
    return context;
}
```

**重点一：prepareEnvironment——配置文件在这里被加载。** 行 350-368 依次做：`getOrCreateEnvironment()`（行 475-485，默认 `new ApplicationEnvironment()`）→ `configureEnvironment()`（行 498-504，把命令行参数包成 `SimpleCommandLinePropertySource` 加到属性源最前面，行 513-533）→ `listeners.environmentPrepared(...)`（行 356）。**EnvironmentPostProcessor 正是在这一步被触发**：`EventPublishingRunListener.environmentPrepared`（`EventPublishingRunListener.java` 行 80-84）发布 `ApplicationEnvironmentPreparedEvent`，而构造阶段装进兜里的 `EnvironmentPostProcessorApplicationListener` 恰好监听该事件（行 111-115 `supportsEventType`）：

```java
// EnvironmentPostProcessorApplicationListener.java 行 130-139
private void onApplicationEnvironmentPreparedEvent(ApplicationEnvironmentPreparedEvent event) {
    ConfigurableEnvironment environment = event.getEnvironment();
    SpringApplication application = event.getSpringApplication();
    List<EnvironmentPostProcessor> postProcessors = getEnvironmentPostProcessors(application.getResourceLoader(),
            event.getBootstrapContext());
    ...
    for (EnvironmentPostProcessor postProcessor : postProcessors) {
        postProcessor.postProcessEnvironment(environment, application);
    }
}
```

`spring.factories` 里登记的 5 个 `EnvironmentPostProcessor` 中最关键的是 `ConfigDataEnvironmentPostProcessor`（order 同为 `HIGHEST_PRECEDENCE + 10`，`ConfigDataEnvironmentPostProcessor.java` 行 52）：其 `postProcessEnvironment`（行 89-98）调用 `new ConfigDataEnvironment(...).processAndApply()`，**application.properties/yml、profile、import 展开等 ConfigData 体系都在这里完成**。此后 run() 行 361 `bindToSpringApplication`（行 550-557）用 `Binder.get(environment).bind("spring.main", Bindable.ofInstance(this.properties))` 把 `spring.main.*` 配置回绑到 SpringApplication 自身——所以 `spring.main.banner-mode=off` 这类配置能改启动行为。注意顺序：**先发布 environmentPrepared（外部配置进 Environment），再回绑（行为受配置影响）**，这正是"配置文件能改变启动方式"的实现位置。

**重点二：createApplicationContext——按类型选容器，但不启动。** 行 579-584 委托 `ApplicationContextFactory`，默认实现 `DefaultApplicationContextFactory`（行 55-71）先从 `spring.factories` 找专用工厂（servlet/reactive 模块各注册了工厂以创建 `ServletWebServerApplicationContext` 等），找不到再走兜底：

```java
// DefaultApplicationContextFactory.java 行 66-71
private ConfigurableApplicationContext createDefaultApplicationContext() {
    if (!AotDetector.useGeneratedArtifacts()) {
        return new AnnotationConfigApplicationContext();   // 普通/AOT 前
    }
    return new GenericApplicationContext();                // AOT 产物
}
```

此刻只是 new 出空容器，**没有任何 Bean 被创建**——真正的装配留给 refresh。

**重点三：prepareContext——把"原料"装进容器。** 行 380-419：`context.setEnvironment(environment)`（383）→ 设置 allowCircularReferences/allowBeanDefinitionOverriding（387-392）→ `applyInitializers(context)`（393，行 615-625 遍历构造阶段加载的 3 个初始器逐个 `initializer.initialize(context)`）→ `listeners.contextPrepared`（394，发布 ApplicationContextInitializedEvent）→ `bootstrapContext.close(context)`（395，引导期对象移交容器）→ 注册 `springApplicationArguments`、`springBootBanner` 两个单例（401-404）→ `load(context, sources)`（416）。load（行 683-698）创建 `BeanDefinitionLoader`，其 `load(Class)`（`BeanDefinitionLoader.java` 行 155-164）对主类执行 `this.annotatedReader.register(source)`——**你的 `@SpringBootApplication` 主类就是在这里变成一个 BeanDefinition 进入注册表的**。最后 `listeners.contextLoaded`（418，发布 ApplicationPreparedEvent）。

**重点四：refreshContext——Boot 把方向盘交还 Framework。** 行 441-446：

```java
private void refreshContext(ConfigurableApplicationContext context) {
    if (this.properties.isRegisterShutdownHook()) {
        shutdownHook.registerApplicationContext(context);
    }
    refresh(context);
}

protected void refresh(ConfigurableApplicationContext applicationContext) {
    applicationContext.refresh();     // 行 755-757
}
```

`applicationContext.refresh()` 就是你在 Framework 篇读过的 `AbstractApplicationContext.refresh()`：beanFactory 预备、`BeanFactoryPostProcessor`（自动配置类在此被解析）、注册监听器、实例化单例、`ContextRefreshedEvent`……**Boot 没有重写其中任何一步**，它做的是让 Environment、BeanDefinition、初始器在 refresh 之前就位。"Boot 是 Framework 扩展点之上的编排"这句话，此处就是最硬的证据。refresh 完成后 `afterRefresh`（764-765）是空方法，纯模板钩子。

**重点五：callRunners——refresh 之后的业务入口。** 行 767-777：

```java
private void callRunners(ConfigurableApplicationContext context, ApplicationArguments args) {
    ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
    String[] beanNames = beanFactory.getBeanNamesForType(Runner.class);
    Map<Runner, String> instancesToBeanNames = new IdentityHashMap<>();
    for (String beanName : beanNames) {
        instancesToBeanNames.put(beanFactory.getBean(beanName, Runner.class), beanName);
    }
    Comparator<Object> comparator = getOrderComparator(beanFactory)
        .withSourceProvider(new FactoryAwareOrderSourceProvider(beanFactory, instancesToBeanNames));
    instancesToBeanNames.keySet().stream().sorted(comparator).forEach((runner) -> callRunner(runner, args));
}
```

细节有二：其一，4.x 统一按 `Runner` 接口取 Bean（`ApplicationRunner`/`CommandLineRunner` 的父接口），一次性取出后按 `@Order` 排序，`FactoryAwareOrderOrderSourceProvider`（行 1843-1874）让排序规则能参考 BeanDefinition 的工厂方法与目标类型；其二，`callRunner`（786-794）按实际类型分发——`ApplicationRunner` 收结构化的 `ApplicationArguments`，`CommandLineRunner` 收原始 `String[]`。Runner 在 `listeners.started` **之后**执行：容器已就绪，Runner 是"启动后的业务脚本"。

## 2.5 事件时间线：监听器何时有机会介入

Boot 启动全程共发布 7 个事件 + 2 个可用性事件。前 4 个由 `EventPublishingRunListener` 的**内部多播器**（`initialMulticaster`，行 61）发布，因为那时容器还没 refresh、没有多播器可用；从 `ApplicationStartedEvent` 起改用 `context.publishEvent`（行 104）。

| 事件 | 发布时机（源码位置） | 监听器可介入点 |
|---|---|---|
| `ApplicationStartingEvent` | `run()` 行 313 → `EventPublishingRunListener.starting`（行 75-77） | 日志系统启动前；不能使用配置 |
| `ApplicationEnvironmentPreparedEvent` | `prepareEnvironment` 行 356 → `EventPublishingRunListener.environmentPrepared`（行 80-84） | **EnvironmentPostProcessor 在此执行**（配置文件/ConfigData 加载） |
| `ApplicationContextInitializedEvent` | `prepareContext` 行 394 → `contextPrepared`（行 87-89） | 初始器跑完、BeanDefinition 未加载 |
| `ApplicationPreparedEvent` | `prepareContext` 行 418 → `contextLoaded`（行 92-100） | 主类已注册为 BeanDefinition，容器未 refresh |
| `ContextRefreshedEvent` | Framework `AbstractApplicationContext.refresh()` 内部（非 Boot 代码） | Framework 原生事件 |
| `ApplicationStartedEvent` + `AvailabilityChangeEvent(LivenessState.CORRECT)` | `listeners.started` 行 327 → `EventPublishingRunListener.started`（行 103-106：`context.publishEvent(...)` + `AvailabilityChangeEvent.publish`） | 容器已活，Runner 未跑 |
| `ApplicationReadyEvent` + `AvailabilityChangeEvent(ReadinessState.ACCEPTING_TRAFFIC)` | run() 行 333-337（需 `context.isRunning()`）→ `ready`（行 108-112） | 一切就绪，可接流量（K8s readiness 探针语义） |
| `ApplicationFailedEvent` | 失败路径 `handleRunFailure` 行 812 → `failed`（行 115-133） | 清理资源、记录失败 |

发布动作全部经由 `SpringApplicationRunListeners.doWithListeners`（行 120-128）：`StartupStep step = this.applicationStartup.start(stepName)` → 逐个回调 → `step.end()`，即**每个事件节点同时是一个可观测的 StartupStep**。而 `EventPublishingRunListener` 本身也是从 `spring.factories` 加载的 `SpringApplicationRunListener`（见 2.3 摘录），Boot 自身的事件机制同样走 SPI。

## 2.6 启动失败处理：FailureAnalyzers 给出"诊断报告"

白话：初学者最怕启动时一屏堆栈。Boot 的做法是先让"诊断器"翻译异常，把堆栈变成一段"DESCRIPTION + ACTION"的人话报告。

【源码证据】失败路径在 run() 的 catch（行 330-332）里进入 `handleRunFailure`（行 803-828）：先 `handleExitCode`（810）→ `listeners.failed`（812，发布 ApplicationFailedEvent）→ finally 里 `reportFailure(getExceptionReporters(context), exception)`（816）并 `context.close()`（818）。异常报告器同样来自 `spring.factories`：

```properties
# Error Reporters
org.springframework.boot.SpringBootExceptionReporter=\
org.springframework.boot.diagnostics.FailureAnalyzers
```

`FailureAnalyzers`（`diagnostics/FailureAnalyzers.java` 行 49-115，包级私有，实现 `SpringBootExceptionReporter` 接口）从 `spring.factories` 加载全部 18 个 `FailureAnalyzer`（同文件 `# Failure Analyzers` 段实测登记了 `BindFailureAnalyzer`、`BeanCurrentlyInCreationFailureAnalyzer` 等），逐个尝试 `analyzer.analyze(failure)`，第一个返回非 null 的获胜（行 90-103），再交给 `FailureAnalysisReporter` 输出（行 105-114）。默认报告器 `LoggingFailureAnalysisReporter`（行 35-57）就是你见过的那面"墙"：

```java
builder.append(String.format("***************************%n"));
builder.append(String.format("APPLICATION FAILED TO START%n"));
builder.append(String.format("***************************%n%n"));
builder.append(String.format("Description:%n%n"));
builder.append(String.format("%s%n", failureAnalysis.getDescription()));
```

**退出码**：`handleExitCode`（`SpringApplication.java` 行 881-892）先发布 `ExitCodeEvent`，再通过 `SpringBootExceptionHandler.registerExitCode(exitCode)` 记下；退出码来源有两路——活跃容器里的 `ExitCodeExceptionMapper` Bean（902-910）与异常自身实现 `ExitCodeGenerator`（912-920，沿 cause 链下钻）。JVM 退出时 `SpringBootExceptionHandler` 把该码设为进程 exit code，让脚本/容器编排能区分"正常退出"与"启动失败"。

## 2.7 启动结束：ShutdownHook、耗时与可观测

**ShutdownHook**：run() 行 306-308 在启动初期就 `enableShutdownHookAddition()`，真正注册发生在 `refreshContext` 行 442-444 的 `shutdownHook.registerApplicationContext(context)`——`SpringApplicationShutdownHook`（行 48-116）是一个 JVM shutdown hook 线程，JVM 退出时逐个 `closeAndWait` 所有已注册容器（行 116），超时 10 分钟（行 52 `TIMEOUT`），保证内嵌容器优雅停机。启动失败时 `handleRunFailure` 行 819 会 `deregisterFailedApplicationContext` 把失败容器摘出。

**耗时与日志**：run() 行 305 的 `Startup startup = Startup.create()` 是 4.x 的计时抽象（内部抽象类，行 1744-1778）：`started()`（1754-1758）记录到"启动完成"的耗时，`ready()`（1766-1769）记录到"可服务"的耗时；`StartupInfoLogger`（行 324-326 被调用）打印熟悉的 `Started xxxApplication in 2.3 seconds`。若 classpath 上有 CRaC（检查点恢复），`create()`（1771-1776）会换成 `CoordinatedRestoreAtCheckpointStartup`，日志显示 `Restored` 而非 `Started`。

**ApplicationStartup 可观测钩子**：一句话——`SpringApplication` 持有 `applicationStartup` 字段（行 245，默认 `ApplicationStartup.DEFAULT`），run() 行 319 把它注入容器，2.5 节的每个启动步骤都被包成带 `mainApplicationClass` 等 tag 的 `StartupStep`（`SpringApplicationRunListeners.doWithListeners` 行 120-128）；配合 Micrometer 的 `ApplicationStartup` 实现即可把启动各阶段耗时导出成指标。

---

**本章小结**：`SpringApplication.run()` 十九步流水线——构造时"认清自己"（deduce 类型/主类/初始器/监听器），run 时"备料"（BootstrapContext → Environment + EnvironmentPostProcessor/ConfigData → 容器实例 → BeanDefinition → 初始器），最后**原样调用 Framework 的 `refresh()`**，再以 Runner、事件与可用性状态收尾。Boot 全程没有绕开 Framework 的任何一个阶段，它只是把喂给 `refresh()` 的原料自动化了——这就是"Boot 不是新容器"的代码级答案。下一章我们进入 refresh 内部，看自动配置（`*.imports` + 条件注解）如何在 `BeanFactoryPostProcessor` 阶段改写 BeanDefinition 注册表。


---

# 三、自动配置：Boot 的"魔法"引擎

> 白话开场：你只写了 `@SpringBootApplication`，一个 `main` 方法，项目里连一个 XML、一行 `web.xml` 都没有，跑起来却有了 DispatcherServlet、Jackson 消息转换器、数据源连接池。这些 Bean 是谁注册的？答案是**自动配置（Auto-configuration）**——而它的本质，就是你在《Spring Framework 深度源码解析》里学过的两个扩展点的组合使用：**DeferredImportSelector**（第七章 @Import 机制）负责"批量引入"配置类，**Condition**（@Conditional）负责"按需取舍"。本章我们逐行验证这句话。

本章源码均来自 Spring Boot **4.2.0-SNAPSHOT**。注意 4.x 的目录布局：自动配置**引擎**（选择器、条件注解、排序器）在 `core/spring-boot-autoconfigure`，而具体技术的自动配置类（如 WebMvc）被拆分到 `module/spring-boot-xxx` 各模块——所以你会在两个地方看到 `.imports` 文件。

---

## 3.1 @SpringBootApplication 解剖：一个注解，三份职责

先看结论：`@SpringBootApplication` 是一个**组合注解**，它自己不包含任何处理逻辑，只是把三个注解"焊"在一起。

【源码证据】`core/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/SpringBootApplication.java` — 类 `SpringBootApplication` 声明处，L50-58：

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(excludeFilters = { @Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class) })
public @interface SpringBootApplication {
```

三者各自贡献什么（对照 Framework 文档）：

**① @SpringBootConfiguration —— 提供"配置类"身份。** 它是 Boot 对 @Configuration 的薄包装：

【源码证据】`core/spring-boot/src/main/java/org/springframework/boot/SpringBootConfiguration.java` — 类 `SpringBootConfiguration`，L44-49：

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Configuration
@Indexed
public @interface SpringBootConfiguration {
```

所以你的启动类本质上就是一个 `@Configuration` 配置类，`ConfigurationClassPostProcessor`（Framework 第四章）会照常处理它。`@Indexed` 是给 AOT/类路径索引扫描用的加速标记。

**② @ComponentScan —— 扫描你自己的组件。** 注意默认值里的两个排除过滤器：`TypeExcludeFilter` 和 `AutoConfigurationExcludeFilter`，后者防止 @Configuration 类被既当扫描组件、又当自动配置导入而重复注册。`@SpringBootApplication` 上的 `scanBasePackages` 属性通过 `@AliasFor` 透传给它：

【源码证据】`SpringBootApplication.java` — 属性 `scanBasePackages`，L93-94：

```java
@AliasFor(annotation = ComponentScan.class, attribute = "basePackages")
String[] scanBasePackages() default {};
```

这就是"默认只扫描启动类所在包及其子包"的来源——@ComponentScan 没写 basePackages 时，Framework 以**声明它的配置类所在包**为起点。

**③ @EnableAutoConfiguration —— 触发自动配置引擎，本章主角。**

【源码证据】`core/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/EnableAutoConfiguration.java` — 类 `EnableAutoConfiguration`，L72-78：

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@AutoConfigurationPackage
@Import(AutoConfigurationImportSelector.class)
public @interface EnableAutoConfiguration {
```

看到 `@Import(AutoConfigurationImportSelector.class)` 了吗？这正是 Framework 的 ImportSelector 扩展点——Boot 没有改动容器任何一行代码，它只是**注册了一个特殊的 ImportSelector**。`@AutoConfigurationPackage` 则负责把启动类所在包记下来（供 JPA 等扫描默认值使用）。

所以三层关系是：**Framework 提供机制（@Import/ImportSelector/@Conditional/@ComponentScan），Boot 提供策略（导入哪些类、何时导入、按什么条件取舍）**。

---

## 3.2 自动配置的"花名册"：AutoConfiguration.imports 文件

白话：ImportSelector 需要返回一批要导入的类名。这批类名不写在代码里，而是写在 classpath 上的一个文本清单里——每个 jar 贡献自己的一页名单，Boot 启动时把所有 jar 的名单汇总。

【源码证据】`core/spring-boot-autoconfigure/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` — 实际内容共 **12 行**（每行一个全限定类名，节选）：

```text
org.springframework.boot.autoconfigure.admin.SpringApplicationAdminJmxAutoConfiguration
org.springframework.boot.autoconfigure.aop.AopAutoConfiguration
org.springframework.boot.autoconfigure.availability.ApplicationAvailabilityAutoConfiguration
org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration
org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration
...
org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration
```

格式极简：一行一个全限定类名，支持 `#` 注释和空行（见下文加载源码）。4.x 中技术类自动配置散落在各模块，例如 `module/spring-boot-webmvc` 的同名文件有 6 行（WebMvcAutoConfiguration、DispatcherServletAutoConfiguration、ErrorMvcAutoConfiguration 等）；全仓库共有 **102 个**这样的 imports 文件，合计约 **325** 个自动配置候选。

**历史演变**（对应文件加载机制的源码证据）：

- **2.7 之前**：所有候选写在各 jar 的 `spring.factories` 文件里，键为 `org.springframework.boot.autoconfigure.EnableAutoConfiguration=...`；
- **2.7 引入**独立 imports 文件与 `ImportCandidates` 加载器（注意 `ImportCandidates.java` 的类注释 `@since 2.7.0`），两种方式并存；
- **3.0 移除**旧方式：今天在仓库里 grep `spring.factories` 中的 `EnableAutoConfiguration` 键已经一无所获，加载路径只剩一条。

加载器本体在 `core/spring-boot`：

【源码证据】`core/spring-boot/src/main/java/org/springframework/boot/context/annotation/ImportCandidates.java` — 常量 `LOCATION`（L47）与静态方法 `load`（L81 起）：

```java
private static final String LOCATION = "META-INF/spring/%s.imports";
...
public static ImportCandidates load(Class<?> annotation, @Nullable ClassLoader classLoader) {
    Assert.notNull(annotation, "'annotation' must not be null");
    ClassLoader classLoaderToUse = decideClassloader(classLoader);
    String location = String.format(LOCATION, annotation.getName());
    Enumeration<URL> urls = findUrlsInClasspath(classLoaderToUse, location);
    List<String> importCandidates = new ArrayList<>();
    while (urls.hasMoreElements()) {
        URL url = urls.nextElement();
        importCandidates.addAll(readCandidateConfigurations(url));
    }
    return new ImportCandidates(importCandidates);
}
```

三个细节：`%s` 用的是**注解的全限定名**（所以这个机制是通用的，`@AutoConfiguration` 之外的注解也能有自己的 imports 文件）；`getResources` 返回的是**枚举**——所有 jar 里的同名文件都会被读出来合并；逐行解析支持注释：

【源码证据】`ImportCandidates.java` — 方法 `readCandidateConfigurations`（L110-128）/ `stripComment`（L130-136）：

```java
while ((line = reader.readLine()) != null) {
    line = stripComment(line);
    line = line.trim();
    if (line.isEmpty()) {
        continue;
    }
    candidates.add(line);
}
```

---

## 3.3 AutoConfigurationImportSelector 全链路（本章核心）

### 3.3.1 为什么是 DeferredImportSelector——"用户配置优先"的时机基础

白话：普通 ImportSelector 在配置类解析到它时**立刻**返回类名；而 Deferred（延迟）ImportSelector 会等**所有**配置类（包括你自己写的 @Configuration、@Bean）都处理完之后才统一执行。这个"晚一步"就是"用户的 Bean 优先于自动配置"的时序前提：轮到自动配置时，你的 Bean 定义已经在 BeanFactory 里躺着了，@ConditionalOnMissingBean 一查一个准。

【源码证据】`core/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/AutoConfigurationImportSelector.java` — 类声明，L78-81：

```java
public class AutoConfigurationImportSelector implements DeferredImportSelector, BeanClassLoaderAware,
        ResourceLoaderAware, BeanFactoryAware, EnvironmentAware, Ordered {

    static final int ORDER = Ordered.LOWEST_PRECEDENCE - 1;
```

### 3.3.2 主干：getAutoConfigurationEntry

整个选择器的核心就是这一个模板方法，五步走：读名单 → 去重 → 收集排除项 → 校验排除 → 过滤。

【源码证据】`AutoConfigurationImportSelector.java` — 方法 `getAutoConfigurationEntry`，L142-155：

```java
protected AutoConfigurationEntry getAutoConfigurationEntry(AnnotationMetadata annotationMetadata) {
    if (!isEnabled(annotationMetadata)) {
        return EMPTY_ENTRY;
    }
    AnnotationAttributes attributes = getAttributes(annotationMetadata);
    List<String> configurations = getCandidateConfigurations(annotationMetadata, attributes);
    configurations = removeDuplicates(configurations);
    Set<String> exclusions = getExclusions(annotationMetadata, attributes);
    checkExcludedClasses(configurations, exclusions);
    configurations.removeAll(exclusions);
    configurations = getConfigurationClassFilter().filter(configurations);
    fireAutoConfigurationImportEvents(configurations, exclusions);
    return new AutoConfigurationEntry(configurations, exclusions);
}
```

**第一步 getCandidateConfigurations：读 imports 文件。** 注意它已经不再走 `SpringFactoriesLoader`，而是上一节的 `ImportCandidates.load`：

【源码证据】`AutoConfigurationImportSelector.java` — 方法 `getCandidateConfigurations`，L200-210：

```java
protected List<String> getCandidateConfigurations(AnnotationMetadata metadata,
        @Nullable AnnotationAttributes attributes) {
    ImportCandidates importCandidates = ImportCandidates.load(this.autoConfigurationAnnotation,
            getBeanClassLoader());
    List<String> configurations = importCandidates.getCandidates();
    Assert.state(!CollectionUtils.isEmpty(configurations),
            "No auto configuration classes found in " + "META-INF/spring/"
                    + this.autoConfigurationAnnotation.getName() + ".imports. If you "
                    + "are using a custom packaging, make sure that file is correct.");
    return configurations;
}
```

**第二步 removeDuplicates：** 多个 jar 的名单可能重复，用 LinkedHashSet 保序去重（L305-307：`return new ArrayList<>(new LinkedHashSet<>(list));`）。

**第三步 收集排除项：两个来源。** 一是注解属性 `@SpringBootApplication(exclude=...)` / `excludeName`（通过 `@AliasFor(annotation = EnableAutoConfiguration.class)` 透传，见 `SpringBootApplication.java` L70-71）；二是配置属性 `spring.autoconfigure.exclude`：

【源码证据】`AutoConfigurationImportSelector.java` — 方法 `getExclusions` 与 `getExcludeAutoConfigurationsProperty`，L247-254、L269-272：

```java
Set<String> excluded = new LinkedHashSet<>();
if (attributes != null) {
    excluded.addAll(asList(attributes, "exclude"));
    excluded.addAll(asList(attributes, "excludeName"));
}
excluded.addAll(getExcludeAutoConfigurationsProperty());
return getAutoConfigurationReplacements().replaceAll(excluded);
```

```java
Binder binder = Binder.get(environment);
return binder.bind(PROPERTY_NAME_AUTOCONFIGURE_EXCLUDE, String[].class)
    .map(Arrays::asList)
    .orElse(Collections.emptyList());
```

**第四步 checkExcludedClasses：防呆。** 如果用户 exclude 了一个"类路径上存在、但根本不是自动配置类"的类，说明十有八九是拼写或理解错误，直接抛异常而不是静默忽略：

【源码证据】`AutoConfigurationImportSelector.java` — 方法 `checkExcludedClasses`，L212-223：

```java
private void checkExcludedClasses(List<String> configurations, Set<String> exclusions) {
    List<String> invalidExcludes = new ArrayList<>(exclusions.size());
    ClassLoader classLoader = (this.beanClassLoader != null) ? this.beanClassLoader : getClass().getClassLoader();
    for (String exclusion : exclusions) {
        if (ClassUtils.isPresent(exclusion, classLoader) && !configurations.contains(exclusion)) {
            invalidExcludes.add(exclusion);
        }
    }
    if (!invalidExcludes.isEmpty()) {
        handleInvalidExcludes(invalidExcludes);
    }
}
```

（`handleInvalidExcludes` 抛 `IllegalStateException`，L230-238。）

### 3.3.3 过滤：AutoConfigurationImportFilter——在"加载类"之前预筛

白话：名单上有 300 多个候选，每个都带 `@ConditionalOnClass`。如果直接把类加载进来再评估条件，classpath 上没有对应依赖的类会抛 `NoClassDefFoundError`。所以 Boot 要在**读取候选类字节码之前**就用一个轻量过滤器先把绝大多数不可能命中的候选踢掉。

【源码证据】`core/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/AutoConfigurationImportFilter.java` — 接口 javadoc 与方法，L27-29、L44-59：

```java
 * Filter that can be registered in {@code spring.factories} to limit the
 * auto-configuration classes considered. This interface is designed to allow fast removal
 * of auto-configuration classes before their bytecode is even read.
...
@FunctionalInterface
public interface AutoConfigurationImportFilter {
    boolean[] match(@Nullable String[] autoConfigurationClasses, AutoConfigurationMetadata autoConfigurationMetadata);
}
```

实现者是谁？查 `core/spring-boot-autoconfigure/src/main/resources/META-INF/spring.factories`：

```text
# Auto Configuration Import Filters
org.springframework.boot.autoconfigure.AutoConfigurationImportFilter=\
org.springframework.boot.autoconfigure.condition.OnBeanCondition,\
org.springframework.boot.autoconfigure.condition.OnClassCondition,\
org.springframework.boot.autoconfigure.condition.OnWebApplicationCondition
```

注意：**filter 的注册走的是老的 spring.factories 机制**（这里是 SPI 服务发现，不是自动配置名单——两回事）。过滤动作在 `ConfigurationClassFilter` 里，对每个过滤器返回的 `boolean[]` 逐位标记：

【源码证据】`AutoConfigurationImportSelector.java` — 内部类 `ConfigurationClassFilter.filter`，L399-411：

```java
List<String> filter(List<String> configurations) {
    long startTime = System.nanoTime();
    @Nullable String[] candidates = StringUtils.toStringArray(configurations);
    boolean skipped = false;
    for (AutoConfigurationImportFilter filter : this.filters) {
        boolean[] match = filter.match(candidates, this.autoConfigurationMetadata);
        for (int i = 0; i < match.length; i++) {
            if (!match[i]) {
                candidates[i] = null;
                skipped = true;
            }
        }
    }
```

这里的 `autoConfigurationMetadata` 是一份编译期生成的"速查表"——`META-INF/spring-autoconfigure-metadata.properties`，由注解处理器在构建时把每个自动配置类上的 @ConditionalOnClass 等注解值提前抄写到属性文件里，运行时**不用加载类**就能查：

【源码证据】`core/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/AutoConfigurationMetadataLoader.java` — 常量与方法 `loadMetadata`，L38、L43-45：

```java
private static final String PATH = "META-INF/spring-autoconfigure-metadata.properties";

static AutoConfigurationMetadata loadMetadata(ClassLoader classLoader) {
    return loadMetadata(classLoader, PATH);
}
```

### 3.3.4 排序：@AutoConfigureOrder / Before / After → AutoConfigurationSorter

白话：自动配置之间有依赖关系（比如"WebMvc 配置要在 DispatcherServlet 配置之后"），需要拓扑排序。排序器分三板斧：先按**字母序**兜底（保证结果确定性），再按 @AutoConfigureOrder 数值，最后处理 before/after 关系。

【源码证据】`core/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/AutoConfigurationSorter.java` — 方法 `getInPriorityOrder`，L65-82：

```java
List<String> getInPriorityOrder(Collection<String> classNames) {
    // Initially sort alphabetically
    List<String> alphabeticallyOrderedClassNames = new ArrayList<>(classNames);
    Collections.sort(alphabeticallyOrderedClassNames);
    // Then sort by order
    ...
    // Then respect @AutoConfigureBefore @AutoConfigureAfter
    orderedClassNames = sortByAnnotation(new LinkedHashSet<>(orderedClassNames), classes);
    return orderedClassNames;
}
```

`sortByAnnotation` 是标准的**深度优先拓扑排序**：对每个类，先把"它声明要在其后（after）的类"递归排好，再把自己放入结果集；用 `processing` 集合检测环：

【源码证据】`AutoConfigurationSorter.java` — 方法 `doSortByAfterAnnotation` 与 `checkForCycles`，L97-118：

```java
private void doSortByAfterAnnotation(AutoConfigurationClasses classes, Map<String, Integer> sortOrder,
        Set<String> sorted, Set<String> processing, String current) {
    if (sorted.contains(current)) {
        return;
    }
    processing.add(current);
    Set<String> afters = new TreeSet<>(Comparator.comparingInt((String name) -> sortOrder.getOrDefault(name, -1)));
    afters.addAll(classes.getClassesRequestedAfter(current));
    for (String after : afters) {
        checkForCycles(processing, current, after);
        ...
```

```java
private void checkForCycles(Set<String> processing, String current, String after) {
    Assert.state(!processing.contains(after),
            () -> "AutoConfigure cycle detected between " + current + " and " + after);
}
```

一个工程细节：排序全程只读 ASM 的 AnnotationMetadata（"without loading classes"，见类 javadoc L41-44），优先用编译期元数据（`wasProcessed()` 判断），绝不触发类加载。

### 3.3.5 Group 机制：AutoConfigurationGroup 的两阶段舞步

DeferredImportSelector 有个进阶玩法：实现 `getImportGroup()` 返回一个 `DeferredImportSelector.Group`，Framework 就会把同一 Group 的所有候选交给 Group **先攒着、最后统一决策**（process 阶段收集，selectImports 阶段一次给出全部结果）。

【源码证据】`AutoConfigurationImportSelector.java` — 方法 `getImportGroup`，L157-160：

```java
@Override
public Class<? extends Group> getImportGroup() {
    return AutoConfigurationGroup.class;
}
```

Group 是 AutoConfigurationImportSelector 的私有内部类。`process` 阶段：每个 @EnableAutoConfiguration 标注类被处理时，把它的 AutoConfigurationEntry 攒进列表：

【源码证据】`AutoConfigurationImportSelector.java` — 内部类 `AutoConfigurationGroup.process`，L467-486（节选）：

```java
@Override
public void process(AnnotationMetadata annotationMetadata, DeferredImportSelector deferredImportSelector) {
    ...
    AutoConfigurationEntry autoConfigurationEntry = autoConfigurationImportSelector
        .getAutoConfigurationEntry(annotationMetadata);
    this.autoConfigurationEntries.add(autoConfigurationEntry);
    for (String importClassName : autoConfigurationEntry.getConfigurations()) {
        this.entries.putIfAbsent(importClassName, annotationMetadata);
    }
}
```

`selectImports` 阶段：等 Framework 判定"所有配置类都处理完了"，才汇总去重、统一排序，一次性交出最终名单：

【源码证据】`AutoConfigurationImportSelector.java` — 内部类 `AutoConfigurationGroup.selectImports`，L489-505：

```java
@Override
public Iterable<Entry> selectImports() {
    if (this.autoConfigurationEntries.isEmpty()) {
        return Collections.emptyList();
    }
    Set<String> allExclusions = this.autoConfigurationEntries.stream()
        .map(AutoConfigurationEntry::getExclusions)
        .flatMap(Collection::stream)
        .collect(Collectors.toSet());
    Set<String> processedConfigurations = this.autoConfigurationEntries.stream()
        .map(AutoConfigurationEntry::getConfigurations)
        .flatMap(Collection::stream)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    processedConfigurations.removeAll(allExclusions);
    return sortAutoConfigurations(processedConfigurations, getAutoConfigurationMetadata()).stream()
        .map(this::getEntry)
        .toList();
}
```

---

## 3.4 条件注解全族：按需装配的实现

白话：光"批量导入"还不够，300 个候选里真正适用于你的可能只有 30 个。每个自动配置类上都挂着 @Conditional 家族注解，ConfigurationClassParser 在注册 BeanDefinition 前逐一评估。Boot 的所有条件评估器都继承自一个模板基类。

### 3.4.1 SpringBootCondition：模板方法 + 评估报告累积

设计是教科书级的模板方法：`matches` 是 final 的，固定做四件事——取类名/方法名、调抽象方法 `getMatchOutcome`、打日志、**写入评估报告**；子类只负责"判断 + 给出人类可读的理由"。

【源码证据】`core/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/condition/SpringBootCondition.java` — 方法 `matches` 与 `recordEvaluation`，L44-62、L102-107：

```java
@Override
public final boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
    String classOrMethodName = getClassOrMethodName(metadata);
    try {
        ConditionOutcome outcome = getMatchOutcome(context, metadata);
        logOutcome(classOrMethodName, outcome);
        recordEvaluation(context, classOrMethodName, outcome);
        return outcome.isMatch();
    }
    catch (NoClassDefFoundError ex) {
        throw new IllegalStateException("Could not evaluate condition on " + classOrMethodName + ...);
    }
```

```java
private void recordEvaluation(ConditionContext context, String classOrMethodName, ConditionOutcome outcome) {
    if (context.getBeanFactory() != null) {
        ConditionEvaluationReport.get(context.getBeanFactory())
            .recordConditionEvaluation(classOrMethodName, this, outcome);
    }
}
```

注意那个 catch：条件评估中若发生 `NoClassDefFoundError`，它会被转译成一条**指向用户的提示**（"Make sure your own configuration does not rely on that class..."），这是排查条件问题最常见的报错出口。

### 3.4.2 @ConditionalOnClass → OnClassCondition：一套代码，两个身份

OnClassCondition 继承自 `FilteringSpringBootCondition`——这个抽象类同时是 Condition **和** AutoConfigurationImportFilter，一套判断逻辑两处复用：

【源码证据】`core/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/condition/FilteringSpringBootCondition.java` — 类声明，L42-43：

```java
abstract class FilteringSpringBootCondition extends SpringBootCondition
        implements AutoConfigurationImportFilter, BeanFactoryAware, BeanClassLoaderAware {
```

作为 **filter** 身份（3.3.3 的预筛）时，它不做完整评估，只查编译期元数据里的 `ConditionalOnClass` 值、用 `Class.forName(className, false, loader)` 探测类是否存在——`initialize=false` 只加载不初始化，且 `isPresent` 把一切 Throwable 吞成 false，绝不影响启动：

【源码证据】`OnClassCondition.java` — 内部类 `StandardOutcomesResolver.getOutcomes`，L209-218；`FilteringSpringBootCondition.ClassNameFilter.isPresent`，L145-156：

```java
private @Nullable ConditionOutcome[] getOutcomes(@Nullable String[] autoConfigurationClasses, int start,
        int end, AutoConfigurationMetadata autoConfigurationMetadata) {
    @Nullable ConditionOutcome[] outcomes = new ConditionOutcome[end - start];
    for (int i = start; i < end; i++) {
        String autoConfigurationClass = autoConfigurationClasses[i];
        if (autoConfigurationClass != null) {
            String candidates = autoConfigurationMetadata.get(autoConfigurationClass, "ConditionalOnClass");
            if (candidates != null) {
                outcomes[i - start] = getOutcome(candidates);
            }
        }
    }
    return outcomes;
}
```

```java
private static boolean isPresent(String className, @Nullable ClassLoader classLoader) {
    ...
    try {
        resolve(className, classLoader);
        return true;
    }
    catch (Throwable ex) {
        return false;
    }
}
```

性能彩蛋：候选多且多核时，一半探测工作扔到后台线程并行做（`resolveOutcomesThreaded`，L54-77）。

作为 **condition** 身份（真正评估某个配置类/Bean 方法时），`getMatchOutcome` 同时处理 @ConditionalOnClass 与 @ConditionalOnMissingClass，任一必需类缺失即 noMatch 并把缺失类名写进 ConditionMessage：

【源码证据】`OnClassCondition.java` — 方法 `getMatchOutcome`，L87-96：

```java
@Override
public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
    ClassLoader classLoader = context.getClassLoader();
    ConditionMessage matchMessage = ConditionMessage.empty();
    List<String> onClasses = getCandidates(metadata, ConditionalOnClass.class);
    if (onClasses != null) {
        List<String> missing = filter(onClasses, ClassNameFilter.MISSING, classLoader);
        if (!missing.isEmpty()) {
            return ConditionOutcome.noMatch(ConditionMessage.forCondition(ConditionalOnClass.class)
                .didNotFind("required class", "required classes")
                .items(Style.QUOTE, missing));
        }
```

### 3.4.3 @ConditionalOnMissingBean → OnBeanCondition：顺序敏感的"让位"逻辑

这是理解"用户配置优先"的钥匙。先看它的两个身份声明：

【源码证据】`core/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/condition/OnBeanCondition.java` — 类声明与 `getConfigurationPhase`，L88-94：

```java
@Order(Ordered.LOWEST_PRECEDENCE)
class OnBeanCondition extends FilteringSpringBootCondition implements ConfigurationCondition {

    @Override
    public ConfigurationPhase getConfigurationPhase() {
        return ConfigurationPhase.REGISTER_BEAN;
    }
```

两个关键点：

1. **`ConfigurationPhase.REGISTER_BEAN`**：告诉 Framework 只在"注册 @Bean 方法"阶段评估，不在"扫描组件"阶段评估（那时连你自己的 @Bean 都还没登记，评估必然失真）。
2. **顺序敏感**：由于 DeferredImportSelector 保证自动配置**最后**处理，此时用户配置类里的 @Bean 方法生成的 BeanDefinition 已全部在册。OnBeanCondition 查询的是 BeanFactory 里**已注册的 BeanDefinition**（不是实例化后的 Bean），所以能准确发现"用户已经定义了"，然后自动配置**让位**：

【源码证据】`OnBeanCondition.java` — 方法 `evaluateConditionalOnMissingBean` 与 `getMatchingBeans`（节选），L202-210、L212-232：

```java
private ConditionOutcome evaluateConditionalOnMissingBean(Spec<?> spec, ConditionMessage matchMessage) {
    MatchResult matchResult = getMatchingBeans(spec);
    if (matchResult.isAnyMatched()) {
        String reason = createOnMissingBeanNoMatchReason(matchResult);
        return ConditionOutcome.noMatch(spec.message().because(reason));
    }
    return ConditionOutcome.match(spec.message(matchMessage).didNotFind("any beans").atAll());
}

protected final MatchResult getMatchingBeans(Spec<?> spec) {
    ConfigurableListableBeanFactory beanFactory = getSearchBeanFactory(spec);
    ...
    for (BeanType type : spec.getTypes()) {
        Map<String, @Nullable BeanDefinition> typeMatchedDefinitions = getBeanDefinitionsForType(beanFactory,
                considerHierarchy, type, parameterizedContainers);
```

匹配是**按类型**查 BeanDefinition（`getBeanDefinitionsForType`），支持泛型容器解析、作用域代理解包（`ScopedProxyUtils.isScopedTarget`）、`ignored` 排除类型等细节。搜索范围由 `SearchStrategy` 控制：

【源码证据】`core/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/condition/SearchStrategy.java` — 枚举定义，L25-41：

```java
public enum SearchStrategy {
    /** Search only the current context. */
    CURRENT,
    /** Search all ancestors, but not the current context. */
    ANCESTORS,
    /** Search the entire hierarchy. */
    ALL
}
```

【源码证据】`condition/ConditionalOnMissingBean.java` — 属性 `search`，L153：`SearchStrategy search() default SearchStrategy.ALL;`（默认搜整棵工厂层级）。

### 3.4.4 其余常用条件速览

**@ConditionalOnProperty**（属性开关）：注解定义 `@Conditional(OnPropertyCondition.class)`（`ConditionalOnProperty.java` L94-99）。判断规则在两段源码里：属性存在时比较 `havingValue`（不指定 havingValue 时只要值不等于 "false" 即匹配）；属性**缺失**时看 `matchIfMissing`：

【源码证据】`condition/OnPropertyCondition.java` — 内部类 `Spec.collectProperties` 与 `isMatch`，L166-187：

```java
private void collectProperties(PropertyResolver resolver, List<String> missing, List<String> nonMatching) {
    for (String name : this.names) {
        String key = this.prefix + name;
        if (resolver.containsProperty(key)) {
            if (!isMatch(resolver.getProperty(key), this.havingValue)) {
                nonMatching.add(name);
            }
        }
        else {
            if (!this.matchIfMissing) {
                missing.add(name);
            }
        }
    }
}

private boolean isMatch(@Nullable String value, String requiredValue) {
    if (StringUtils.hasLength(requiredValue)) {
        return requiredValue.equalsIgnoreCase(value);
    }
    return !"false".equalsIgnoreCase(value);
}
```

**@ConditionalOnWebApplication**：预筛阶段用两个"哨兵类名"探测（SERVLET 看 `GenericWebApplicationContext`、REACTIVE 看 `HandlerResult`），正式评估用 switch 按 Type 分派：

【源码证据】`condition/OnWebApplicationCondition.java` — 方法 `getMatchOutcome`，L92-102：

```java
@Override
public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
    boolean required = metadata.isAnnotated(ConditionalOnWebApplication.class.getName());
    ConditionOutcome outcome = isWebApplication(context, metadata, required);
    if (required && !outcome.isMatch()) {
        return ConditionOutcome.noMatch(outcome.getConditionMessage());
    }
    if (!required && outcome.isMatch()) {   // @ConditionalOnNotWebApplication 也由它处理
        return ConditionOutcome.noMatch(outcome.getConditionMessage());
    }
    return ConditionOutcome.match(outcome.getConditionMessage());
}
```

**@ConditionalOnBean**：与 OnMissingBean 共用 OnBeanCondition 的 `getMatchingBeans`，区别只是匹配方向的取反（`evaluateConditionalOnBean`，L157-166）。**@ConditionalOnResource**：逐个解析占位符后 `getResource(resource).exists()`，缺失即 noMatch（`OnResourceCondition.java` L56-66）。此外本版本 condition 包还有 40 余个类（@ConditionalOnJava、@ConditionalOnCloudPlatform、@ConditionalOnThreading、AnyNestedConditions/AllNestedConditions 组合器等），套路一致，不再展开。

---

## 3.5 评估报告：ConditionEvaluationReport——排查"为什么没生效"的瑞士军刀

白话：几十个条件散落在几百个类上，出问题时你不可能逐个断点。Boot 把**每一次**条件评估都记账到一份全局报告里，随取随查。

数据结构是"按类名排序的 TreeMap"，值是该类上所有条件的判定结果：

【源码证据】`core/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/condition/ConditionEvaluationReport.java` — 字段与 `recordConditionEvaluation`，L55-59、L82-89：

```java
private static final String BEAN_NAME = "autoConfigurationReport";
...
private final SortedMap<String, ConditionAndOutcomes> outcomes = new TreeMap<>();
...
public void recordConditionEvaluation(String source, Condition condition, ConditionOutcome outcome) {
    Assert.notNull(source, "'source' must not be null");
    ...
    this.outcomes.computeIfAbsent(source, (key) -> new ConditionAndOutcomes()).add(condition, outcome);
}
```

它本身不是普通 Bean，而是**以单例形式挂在 BeanFactory 上**（名字固定 `autoConfigurationReport`）：

【源码证据】`ConditionEvaluationReport.java` — 方法 `get`，L181-194：

```java
public static ConditionEvaluationReport get(ConfigurableListableBeanFactory beanFactory) {
    synchronized (beanFactory) {
        ConditionEvaluationReport report;
        if (beanFactory.containsSingleton(BEAN_NAME)) {
            report = beanFactory.getBean(BEAN_NAME, ConditionEvaluationReport.class);
        }
        else {
            report = new ConditionEvaluationReport();
            beanFactory.registerSingleton(BEAN_NAME, report);
        }
        ...
```

记账的入口有三处，串起来正好是本章 3.3-3.4 的流程：
1. **候选与排除项**：`ConditionEvaluationReportAutoConfigurationImportListener` 监听导入事件，`report.recordEvaluationCandidates(...)` + `recordExclusions(...)`（`ConditionEvaluationReportAutoConfigurationImportListener.java` L40-46）；
2. **预筛被淘汰的**：`FilteringSpringBootCondition.match()` 中对每个 noMatch 结果 `report.recordConditionEvaluation(...)`（`FilteringSpringBootCondition.java` L60-67）；
3. **正式评估的**：`SpringBootCondition.recordEvaluation`（3.4.1 已引）。

两个出口：**应用启动失败时**，`ConditionEvaluationReportLoggingListener`（在 `spring.factories` 里注册为 ApplicationContextInitializer）会在错误报告中打印"_positive matches / negative matches_"两段；**运行期**，actuator 暴露了 `conditions` 端点：

【源码证据】`module/spring-boot-actuator-autoconfigure/src/main/java/org/springframework/boot/actuate/autoconfigure/condition/ConditionsReportEndpoint.java` — 类声明与 `conditions`，L55-65：

```java
@Endpoint(id = "conditions")
public class ConditionsReportEndpoint {

    private final ConfigurableApplicationContext context;
    ...
    @ReadOperation
    public ConditionsDescriptor conditions() {
```

一句话：GET `/actuator/conditions`，正反匹配一目了然。

---

## 3.6 @AutoConfiguration 注解与一个完整标本：WebMvcAutoConfiguration

### 3.6.1 @AutoConfiguration 本体

2.7 之前自动配置类直接标 @Configuration；2.7 起有了专属注解，把排序属性也收编进来：

【源码证据】`core/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/AutoConfiguration.java` — 类声明，L55-61：

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Configuration(proxyBeanMethods = false)
@AutoConfigureBefore
@AutoConfigureAfter
public @interface AutoConfiguration {
```

三个设计点：**`proxyBeanMethods = false`**——自动配置类按约定都是"每个 @Bean 方法自包含"的轻模式，省掉 CGLIB 代理开销（这也是它与普通 @Configuration 的唯一行为差异，见类 javadoc L36-39）；`before()` / `after()` 属性通过 @AliasFor 分别透传给 @AutoConfigureBefore/@AutoConfigureAfter（L89-90、L112-113），供 3.3.4 的排序器消费。

### 3.6.2 标本解剖：WebMvcAutoConfiguration（位于 module/spring-boot-webmvc）

【源码证据】`module/spring-boot-webmvc/src/main/java/org/springframework/boot/webmvc/autoconfigure/WebMvcAutoConfiguration.java` — 类声明，L160-167：

```java
@AutoConfiguration(after = { DispatcherServletAutoConfiguration.class, TaskExecutionAutoConfiguration.class },
        afterName = "org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration")
@ConditionalOnWebApplication(type = Type.SERVLET)
@ConditionalOnClass({ Servlet.class, DispatcherServlet.class, WebMvcConfigurer.class })
@ConditionalOnMissingBean(WebMvcConfigurationSupport.class)
@AutoConfigureOrder(Ordered.HIGHEST_PRECEDENCE + 10)
@ImportRuntimeHints(WebResourcesRuntimeHints.class)
public final class WebMvcAutoConfiguration {
```

类级注解就是一部"说明书"，逐行翻译：

- `@ConditionalOnClass({Servlet.class, DispatcherServlet.class, WebMvcConfigurer.class})`：classpath 上有 Spring MVC 才考虑我（被预筛 filter 用编译期元数据提前短路）；
- `@ConditionalOnWebApplication(type = SERVLET)`：还得是 Servlet 型 Web 应用（不是 WebFlux）；
- `@ConditionalOnMissingBean(WebMvcConfigurationSupport.class)`：**用户已经自己 @EnableWebMvc（即导入了 DelegatingWebMvcConfiguration 的子类）就整体让位**——这就是"全自动 vs 完全接管"的开关；
- `@AutoConfiguration(after = {DispatcherServletAutoConfiguration.class, ...})`：排序声明，保证 DispatcherServlet 相关 Bean 先注册，本类的 @ConditionalOnMissingBean 才能看清局势；
- `@AutoConfigureOrder(HIGHEST_PRECEDENCE + 10)`：同批次里尽量靠前。

类内的 @Bean 方法则展示"细粒度让位 + 属性开关"的组合拳：

【源码证据】`WebMvcAutoConfiguration.java` — 方法 `hiddenHttpMethodFilter` 与 `formContentFilter`，L181-193：

```java
@Bean
@ConditionalOnMissingBean(HiddenHttpMethodFilter.class)
@ConditionalOnBooleanProperty("spring.mvc.hiddenmethod.filter.enabled")
OrderedHiddenHttpMethodFilter hiddenHttpMethodFilter() {
    return new OrderedHiddenHttpMethodFilter();
}

@Bean
@ConditionalOnMissingBean(FormContentFilter.class)
@ConditionalOnBooleanProperty(name = "spring.mvc.formcontent.filter.enabled", matchIfMissing = true)
OrderedFormContentFilter formContentFilter() {
    return new OrderedFormContentFilter();
}
```

读法：用户没自己定义这个 Filter、且属性没有显式关掉，我才注册；第二个 Bean 配了 `matchIfMissing = true`，表示**不配这个属性也默认开**。两个条件注解一票否决。

内嵌配置类负责装配属性绑定和核心 MVC 基础设施：

【源码证据】`WebMvcAutoConfiguration.java` — 内嵌类 `WebMvcAutoConfigurationAdapter` 声明，L195-201：

```java
// Defined as a nested config to ensure WebMvcConfigurer is not read when not
// on the classpath
@Configuration(proxyBeanMethods = false)
@Import(EnableWebMvcConfiguration.class)
@EnableConfigurationProperties({ WebMvcProperties.class, WebProperties.class })
@Order(0)
static class WebMvcAutoConfigurationAdapter implements WebMvcConfigurer, ServletContextAware {
```

`@EnableConfigurationProperties` 把 `spring.mvc.*` 配置项绑定成 WebMvcProperties Bean 供构造器注入；类内方法再从这些属性对象里取值定制 MVC 行为。真正等价于 @EnableWebMvc 的部分在第二个内嵌类：

【源码证据】`WebMvcAutoConfiguration.java` — 内嵌类 `EnableWebMvcConfiguration` 与其中的 `localeResolver`，L460-464、L531-543：

```java
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebProperties.class)
@ImportRuntimeHints(MvcValidatorRuntimeHints.class)
static class EnableWebMvcConfiguration extends DelegatingWebMvcConfiguration
        implements ResourceLoaderAware, EmbeddedValueResolverAware {
```

```java
@Override
@Bean
@ConditionalOnMissingBean(name = DispatcherServlet.LOCALE_RESOLVER_BEAN_NAME)
public LocaleResolver localeResolver() {
    ...
    AcceptHeaderLocaleResolver localeResolver = new AcceptHeaderLocaleResolver();
    localeResolver.setDefaultLocale(locale);
    return localeResolver;
}
```

这最后一段是最精髓的"覆盖式定制"：它继承了 Framework 的 `DelegatingWebMvcConfiguration`（也就是 @EnableWebMvc 的导入物），但把 `localeResolver()` 等 @Bean 方法**重写并加上 @ConditionalOnMissingBean(name = "localeResolver")**——容器里没有叫这个名字的 BeanDefinition 时才注册 Boot 版本。于是形成三级让位链：**用户自定义 localeResolver > Boot 自动配置版 > Framework 默认版**，全部由"@Bean 重写 + 条件注解"完成，没有任何反射黑魔法。

别忘了它是怎么进容器的：类名写在 `module/spring-boot-webmvc/.../META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 里，被 3.3 的选择器读出、过滤、排序，最终作为普通 @Configuration 注册。

---

## 3.7 收尾

回到开头的断言，现在可以逐字验证了：**Spring Boot 自动配置 = Framework 的 DeferredImportSelector（决定"什么时候导入"——用户配置处理完之后）+ ImportCandidates 机制读取的 .imports 名单（决定"从哪里知道要导入谁"）+ AutoConfigurationImportFilter 预筛与 AutoConfigurationSorter 拓扑排序（决定"导入的次序与性价比"）+ @Conditional 条件族（决定"最终导入哪些"）**。全程只是往容器里注册/不注册 BeanDefinition，容器层没有任何一处为 Boot 开的"后门"——所谓魔法，不过是 Framework 扩展点的教科书级组合。


---

# 四、外部化配置与类型安全绑定

上一章我们讲过，`Environment` 与 `MutablePropertySources` 的有序优先级是 Spring Framework 的地基。本章回答两个初学者最关心的问题：**application.yml 里的内容到底是怎么、在什么时机进入 Environment 的？** 以及 **Environment 里松散的字符串键值对，是怎么变成 `ServerProperties` 这种强类型对象的？** 前者是 ConfigData 机制，后者是 Binder 机制。全程基于 spring-boot 4.2.0-SNAPSHOT 源码（core/spring-boot 模块）。

## 4.1 配置优先级全景：Boot 的 PropertySource 层次

**白话**：Framework 只提供了一个"有序货架"（`MutablePropertySources`，先放的优先级高），Boot 负责往货架上摆货——命令行参数、环境变量、application.yml 等，按一套固定顺序摆放。同一属性名在多个货位出现时，排前面的赢。

**注册时机**：`prepareEnvironment` 中 `configureEnvironment` 先把命令行参数放到货架最前面。

【源码证据】core/spring-boot/src/main/java/org/springframework/boot/SpringApplication.java | SpringApplication.configurePropertySources | 行 513-533

```java
protected void configurePropertySources(ConfigurableEnvironment environment, String[] args) {
    MutablePropertySources sources = environment.getPropertySources();
    if (!CollectionUtils.isEmpty(this.defaultProperties)) {
        DefaultPropertiesPropertySource.addOrMerge(this.defaultProperties, sources);
    }
    if (this.addCommandLineProperties && args.length > 0) {
        String name = CommandLinePropertySource.COMMAND_LINE_PROPERTY_SOURCE_NAME;
        ...
        else {
            sources.addFirst(new SimpleCommandLinePropertySource(args));   // 行 529：命令行永远最前
        }
    }
    environment.getPropertySources().addLast(new ApplicationInfoPropertySource(this.mainApplicationClass));
}
```

**权威顺序清单**（4.2 官方参考文档源文件 documentation/spring-boot-docs/src/docs/antora/modules/reference/pages/features/external-config.adoc 行 13-39 明确列出，按"后列出的覆盖先列出的"排列）：

| 优先级（高→低） | 配置源 | 代码插入点 |
|---|---|---|
| 1 | 测试相关：`@TestPropertySource` / `@DynamicPropertySource` / `@SpringBootTest(properties=)` | 测试模块 |
| 2 | 命令行参数 `--key=value` | SpringApplication.java 行 529 `addFirst` |
| 3 | `SPRING_APPLICATION_JSON` 内联 JSON | SpringApplicationJsonEnvironmentPostProcessor |
| 4 | ServletConfig / ServletContext 初始化参数、JNDI `java:comp/env` | StandardServletEnvironment 内置 |
| 5 | Java 系统属性 `-D` / OS 环境变量 | Framework StandardEnvironment 内置 |
| 6 | `RandomValuePropertySource`（仅 `random.*`） | RandomValuePropertySourceEnvironmentPostProcessor（ORDER=HIGHEST+1） |
| 7 | jar 外 application-{profile} → jar 内 application-{profile} → jar 外 application → jar 内 application | ConfigData 机制（addLast 追加） |
| 8 | `@PropertySource`（refresh 时才生效，太晚配不了 `logging.*`/`spring.main.*`） | ConfigurationClassPostProcessor |
| 9 | `setDefaultProperties()` 默认值 | 行 358 `moveToEnd` 收尾 |

与官方文档一致的排序验证：文档行 15-32 的顺序为 Default properties → @PropertySource → Config data → random → OS 环境变量 → 系统属性 → JNDI → ServletContext → ServletConfig → SPRING_APPLICATION_JSON → 命令行（自低到高）；行 34-39 说明 jar 内 application < jar 内 application-{profile} < jar 外 application < jar 外 application-{profile}。源码侧的一致性由收尾逻辑保证——【源码证据】SpringApplication.java | prepareEnvironment | 行 356-358：

```java
listeners.environmentPrepared(bootstrapContext, environment);   // 触发全部 EnvironmentPostProcessor
ApplicationInfoPropertySource.moveToEnd(environment);
DefaultPropertiesPropertySource.moveToEnd(environment);         // 默认值永远垫底
```

## 4.2 YAML 是怎么被读进来的：ConfigData 机制

**白话**：裸 Framework 里 `PropertySourcesPlaceholderConfigurer` 只认识 .properties；Boot 用 2.4 引入的 **ConfigData** 机制，在 `refresh` 之前的 bootstrap 阶段把 application.yml/properties 解析成一批带"来源坐标"（Origin）的 PropertySource。**这是 Boot 与裸 Framework 最关键的时序差异：配置文件加载发生在 refresh 之前、由 EnvironmentPostProcessor 完成**——所以 `logging.level.root=INFO` 在日志系统初始化时就已经可读。

触发点：`listeners.environmentPrepared` 发布 `ApplicationEnvironmentPreparedEvent`，监听器负责跑所有 EnvironmentPostProcessor。

【源码证据】core/spring-boot/src/main/java/org/springframework/boot/support/EnvironmentPostProcessorApplicationListener.java | supportsEventType | 行 111-114

```java
public boolean supportsEventType(Class<? extends ApplicationEvent> eventType) {
    return ApplicationEnvironmentPreparedEvent.class.isAssignableFrom(eventType)
            || ApplicationPreparedEvent.class.isAssignableFrom(eventType)
            || ApplicationFailedEvent.class.isAssignableFrom(eventType);
}
```

主处理器是 `ConfigDataEnvironmentPostProcessor`（ORDER = `Ordered.HIGHEST_PRECEDENCE + 10`，行 52），它一口气完成"解析位置 → 加载文件 → 应用到 Environment"：

【源码证据】core/spring-boot/src/main/java/org/springframework/boot/context/config/ConfigDataEnvironmentPostProcessor.java | postProcessEnvironment | 行 93-98

```java
void postProcessEnvironment(ConfigurableEnvironment environment, @Nullable ResourceLoader resourceLoader,
        Collection<String> additionalProfiles) {
    this.logger.trace("Post-processing environment to add config data");
    resourceLoader = (resourceLoader != null) ? resourceLoader : new DefaultResourceLoader();
    getConfigDataEnvironment(environment, resourceLoader, additionalProfiles).processAndApply();
}
```

【源码证据】core/spring-boot/src/main/java/org/springframework/boot/context/config/ConfigDataEnvironment.java | processAndApply | 行 236-248

```java
void processAndApply() {
    ConfigDataImporter importer = new ConfigDataImporter(this.logFactory, this.notFoundAction, this.resolvers,
            this.loaders);
    registerBootstrapBinder(this.contributors, null, DENY_INACTIVE_BINDING);
    ConfigDataEnvironmentContributors contributors = processInitial(this.contributors, importer);
    ConfigDataActivationContext activationContext = createActivationContext(...);
    contributors = processWithoutProfiles(contributors, importer, activationContext);
    activationContext = withProfiles(contributors, activationContext);   // 3 次迭代式导入：先无 profile，再带 profile
    contributors = processWithProfiles(contributors, importer, activationContext);
    applyToEnvironment(contributors, activationContext, importer.getLoadedLocations(), ...);
}
```

**去哪里找文件？** 默认搜索位置由常量定义，注意每个位置都带 `optional:` 前缀（不存在不报错）：

【源码证据】ConfigDataEnvironment.java | 静态初始化块 | 行 91-97

```java
static final ConfigDataLocation[] DEFAULT_SEARCH_LOCATIONS;
static {
    List<ConfigDataLocation> locations = new ArrayList<>();
    locations.add(ConfigDataLocation.of("optional:classpath:/;optional:classpath:/config/"));
    locations.add(ConfigDataLocation.of("optional:file:./;optional:file:./config/;optional:file:./config/*/"));
    DEFAULT_SEARCH_LOCATIONS = locations.toArray(new ConfigDataLocation[0]);
}
```

`optional:` 前缀本身在 ConfigDataLocation 中解析（`public static final String OPTIONAL_PREFIX = "optional:"`，行 46；行 168-169 据此裁剪）。`spring.config.import=` 则由 `IMPORT_PROPERTY = "spring.config.import"`（ConfigDataEnvironment.java 行 79）经 `getInitialImportContributors`（行 201-207）导入，`ConfigDataImporter.resolve` 对导入位置递归"解析→加载"，实现配置文件互相 import。

**谁来解析/加载？** `StandardConfigDataLocationResolver`（Order 为 LOWEST，是兜底解析器）从 `spring.config.name`（默认 `application`，行 65-67）+ 所有 `PropertySourceLoader` 的扩展名组合出候选文件；profile 专属文件则在 `resolveProfileSpecific` 里对每个激活 profile 再展开一轮：

【源码证据】core/spring-boot/src/main/java/org/springframework/boot/context/config/StandardConfigDataLocationResolver.java | getProfileSpecificReferences | 行 153-163

```java
private Set<StandardConfigDataReference> getProfileSpecificReferences(ConfigDataLocationResolverContext context,
        ConfigDataLocation[] configDataLocations, Profiles profiles) {
    Set<StandardConfigDataReference> references = new LinkedHashSet<>();
    for (String profile : profiles) {                    // 每个 profile 一轮，后激活的 profile 排前（优先级更高）
        for (ConfigDataLocation configDataLocation : configDataLocations) {
            ...
            references.addAll(getReferences(configDataLocation, resourceLocation, profile));
        }
    }
    return references;
}
```

真正读文件的是 `StandardConfigDataLoader.load`（行 43-58），它委托给扩展名匹配的 `PropertySourceLoader`。YAML 由 `YamlPropertySourceLoader` 处理（基于 snakeyaml），**每个 YAML 文档（`---` 分隔）生成一个独立 PropertySource**，且每个值都包着 `OriginTrackedValue` 记录"来自哪个文件第几行"——`--spring.config.location` 报错提示、actuator `/configprops` 都靠它：

【源码证据】core/spring-boot/src/main/java/org/springframework/boot/env/YamlPropertySourceLoader.java | load | 行 45-59

```java
public List<PropertySource<?>> load(String name, Resource resource) throws IOException {
    if (!ClassUtils.isPresent("org.yaml.snakeyaml.Yaml", getClass().getClassLoader())) {
        throw new IllegalStateException("Attempted to load " + name + " but snakeyaml was not found on the classpath");
    }
    List<Map<String, Object>> loaded = new OriginTrackedYamlLoader(resource).load();
    ...
    propertySources.add(new OriginTrackedMapPropertySource(name + documentNumber,
            Collections.unmodifiableMap(loaded.get(i)), true));
}
```

最后 `applyContributor` 把加载出的配置源 `propertySources.addLast` 进 Environment（ConfigDataEnvironment.java 行 355-374）——`addLast` 保证它们排在命令行、环境变量之下、默认值之上，与 4.1 表格吻合。

**RandomValuePropertySource 一句话**：任何 `random.*` 键都会按类型即时生成随机值（int/long/uuid/十六进制字节），插入点紧跟环境变量之后（`sources.addAfter(SYSTEM_ENVIRONMENT...)`），常用于临时端口、密钥占位（core/spring-boot/.../env/RandomValuePropertySource.java 行 95-114、162-177）。

## 4.3 Binder：类型安全绑定（本章核心）

### 4.3.1 入口：一个 BeanPostProcessor

**白话**：`@ConfigurationProperties` 标注的 bean，是在**初始化方法之前**被"塞值"的。做这件事的正是 Framework 扩展机制章讲过的 `BeanPostProcessor`——Boot 没有发明新钩子，只是把绑定逻辑挂进了 Framework 的既有扩展点。

【源码证据】core/spring-boot/src/main/java/org/springframework/boot/context/properties/ConfigurationPropertiesBindingPostProcessor.java | postProcessBeforeInitialization / bind | 行 82-105

```java
public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
    if (!hasBoundValueObject(beanName)) {
        bind(ConfigurationPropertiesBean.get(this.applicationContext, bean, beanName));
    }
    return bean;
}
private void bind(@Nullable ConfigurationPropertiesBean bean) {
    ...
    try {
        this.binder.bind(bean);
    }
    catch (Exception ex) {
        throw new ConfigurationPropertiesBindException(bean, ex);   // 绑定失败统一包装成 BeanCreationException
    }
}
```

`ConfigurationPropertiesBindException` 的 message 会带上 prefix 与两个 ignore 开关，方便定位（该类行 55-63）。

### 4.3.2 绑定总装：ConfigurationPropertiesBinder 与 Binder 核心三件套

`ConfigurationPropertiesBinder`（包私有）组装 Binder：属性源用 `ConfigurationPropertySources.from(propertySources)` 适配（让 Environment 的源支持宽松命名），`${}` 占位符用 `PropertySourcesPlaceholdersResolver`，类型转换用容器里的 ConversionService（ConfigurationPropertiesBinder.java 行 185-201）。然后调 `getBinder().bind(annotation.prefix(), target, bindHandler)`。

Binder 的主流程是"**回调钩子 + 递归绑定**"：

【源码证据】core/spring-boot/src/main/java/org/springframework/boot/context/properties/bind/Binder.java | bindObject | 行 421-447

```java
private <T> @Nullable Object bindObject(ConfigurationPropertyName name, Bindable<T> target, ...) {
    ConfigurationProperty property = findProperty(name, target, context);
    if (property == null && context.depth != 0 && containsNoDescendantOf(context.getSources(), name)) {
        return null;                                             // 没有任何子属性 → 直接放弃
    }
    AggregateBinder<?> aggregateBinder = getAggregateBinder(target, context);
    if (aggregateBinder != null) {
        return bindAggregate(name, target, handler, context, aggregateBinder);  // Map/Collection/数组
    }
    if (property != null) {
        try {
            return bindProperty(target, context, property);      // 叶子：直接转换
        } catch (ConverterNotFoundException ex) { ... }
    }
    return bindDataObject(name, target, handler, context, allowRecursiveBinding, false);  // 嵌套对象
}
```

- **AggregateBinder 家族**负责集合/映射的递归：`getAggregateBinder`（行 449-461）按类型派发 `MapBinder`/`CollectionBinder`/`ArrayBinder`；`CollectionBinder extends IndexedElementsBinder extends AggregateBinder`（各文件行 44/36/34），叶子元素又回调回 `Binder.bind`，所以 `app.servers[0].host` 这类嵌套列表能逐层绑定。**JavaBeanBinder** 负责普通对象：遍历 setter 属性并逐个赋值——

【源码证据】core/spring-boot/src/main/java/org/springframework/boot/context/properties/bind/JavaBeanBinder.java | bind | 行 121-139

```java
private <T> boolean bind(BeanSupplier<T> beanSupplier, DataObjectPropertyBinder propertyBinder,
        BeanProperty property) {
    String propertyName = determinePropertyName(property);
    ...
    Object bound = propertyBinder.bindProperty(propertyName,
            Bindable.of(type).withSuppliedValue(value).withAnnotations(annotations));
    if (bound == null) {
        return false;
    }
    if (property.isSettable()) {
        property.setValue(beanSupplier, bound);      // 递归绑定后回填 setter
    }
    ...
}
```

- **PropertySourcesPlaceholdersResolver**：绑定前先把 `${...}` 解析掉——它遍历所有 PropertySource 找占位符的值（PropertySourcesPlaceholdersResolver.java 行 60-77），所以配置文件里 `app.path=${user.dir}/data` 会先展开再转换。
- **BindConverter**：真正"字符串→目标类型"的转换器，内部是一串 Framework `ConversionService` 委托（TypeConverterConversionService → 容器 ConversionService → ApplicationConversionService，行 64-78），逐个尝试直到成功——这正是 Framework 第三章类型转换体系在 Boot 侧的延伸。

### 4.3.3 宽松绑定（Relaxed Binding）

**白话**：`spring.main.banner-mode`、`spring.main.bannerMode`、`spring_main_banner_mode`、`SPRING_MAIN_BANNERMODE` 四种写法指的是同一个属性。规则是：把名字规范化成"小写字母数字 + 连字符"的元素序列，`-` 仅用于排版，**`foo-bar` 与 `foobar` 等价**：

【源码证据】core/spring-boot/src/main/java/org/springframework/boot/context/properties/source/ConfigurationPropertyName.java | 类 Javadoc | 行 22-26

```java
 * A configuration property name composed of elements separated by dots. User created
 * names may contain the characters "{@code a-z}" "{@code 0-9}" and "{@code -}", they must
 * be lower-case and must start with an alphanumeric character. The "{@code -}" is used
 * purely for formatting, i.e. "{@code foo-bar}" and "{@code foobar}" are considered
 * equivalent.
```

环境变量侧由适配器把 `SERVER_PORT` 翻译成 `server.port`（下划线变点、转小写、数字段变索引 `[0]`）：

【源码证据】core/spring-boot/src/main/java/org/springframework/boot/context/properties/source/SystemEnvironmentPropertyMapper.java | 类 Javadoc + processElementValue | 行 29-33、81-84

```java
 * {@link PropertyMapper} for system environment variables. Names are mapped by removing
 * invalid characters, converting to lower case and replacing "{@code _}" with
 * "{@code .}". For example, "{@code SERVER_PORT}" is mapped to "{@code server.port}".
 ...
private CharSequence processElementValue(CharSequence value) {
    String result = value.toString().toLowerCase(Locale.ENGLISH);
    return isNumber(result) ? "[" + result + "]" : result;
}
```

### 4.3.4 构造器绑定与 record

**白话**：不可变配置类（没有 setter，或干脆是 record）怎么办？让 Binder 直接走构造器，一个参数一个属性。3.0 起，只要类只有一个带参构造器，**无需 `@ConstructorBinding` 也会自动启用构造器绑定**：

【源码证据】core/spring-boot/src/main/java/org/springframework/boot/context/properties/bind/ConstructorBinding.java | 类 Javadoc | 行 26-29

```java
 * Annotation that can be used to indicate which constructor to use when binding
 * configuration properties using constructor arguments rather than by calling setters. A
 * single parameterized constructor implicitly indicates that constructor binding should
 * be used unless the constructor is annotated with {@code @Autowired}.
```

推断逻辑在 `DefaultBindConstructorProvider`：没有 `@Autowired`、有候选构造器时 `deduceBindConstructor` 直接采纳；record 通过 `boolean immutableType = type.isRecord()` 被标记为不可变类型（DefaultBindConstructorProvider.java 行 100-123）。绑定执行在 `ValueObjectBinder.bind`：逐参数 `parameter.bind(propertyBinder)`，缺省时用 `@DefaultValue`（含占位符解析）补默认值，最后 `valueObject.instantiate(args)`（ValueObjectBinder.java 行 78-104）。绑定方式的选择由 `BindMethod` 决定：有绑定构造器 → `VALUE_OBJECT`（走 ValueObjectBinder），否则 `JAVA_BEAN`（走 JavaBeanBinder）——`return (bindConstructor != null) ? BindMethod.VALUE_OBJECT : BindMethod.JAVA_BEAN;`（ConfigurationPropertiesBean.java 行 308-310）。构造器绑定的 bean 由 bean 工厂直接创建实例，不再经过 BeanPostProcessor 二次绑定（这就是 4.3.1 中 `hasBoundValueObject` 检查 `BindMethodAttribute` 的原因）。

### 4.3.5 配置元数据：IDE 提示是怎么来的

`spring-boot-configuration-processor` 是一个**编译期注解处理器**，模块位于仓库的 `configuration-metadata/spring-boot-configuration-processor`（同目录还有 `spring-boot-configuration-metadata`）。它扫描 `@ConfigurationProperties` 类，生成 JSON 供 IDE 补全：`static final String METADATA_PATH = "META-INF/spring-configuration-metadata.json";`（configuration-metadata/spring-boot-configuration-processor/src/main/java/org/springframework/boot/configurationprocessor/MetadataStore.java 行 45）。处理器入口为 ConfigurationMetadataAnnotationProcessor，支持的类也含 JavaBean、record 构造参数与 Lombok 属性描述符（JavaBeanPropertyDescriptor / ConstructorParameterPropertyDescriptor / LombokPropertyDescriptor）。

### 4.3.6 @ConfigurationProperties vs @Value

| 维度 | @ConfigurationProperties | @Value |
|---|---|---|
| 来源 | Environment 全部 PropertySource | 同左（经占位符解析） |
| 绑定方式 | 宽松绑定（kebab/camel/下划线/大写环境变量等价） | 严格匹配属性名（不含环境变量适配） |
| SpEL | 不支持，`#{}` 不求值（官方 Javadoc 明示，ConfigurationProperties.java 行 32-33 "Note that contrary to @Value, SpEL expressions are not evaluated"） | 支持 |
| 复杂对象/嵌套 | 支持：Map、List、嵌套 bean、record 构造器绑定 | 不支持，只能注入标量/字符串 |
| 校验 | 支持 `@Validated` + JSR-303 | 不支持 |
| 元数据/IDE 提示 | 由 configuration-processor 生成元数据 | 无 |
| 触发时机 | Bean 初始化前由 Binder 批量绑定（或构造器创建时） | 实例化时逐字段解析 |

## 4.4 profile 体系：Boot 怎么处理 spring.profiles.active

**白话**：`@Profile` 是 Framework 的**Bean 级**开关（refresh 阶段决定注册哪些 BeanDefinition）；`spring.profiles.active` 是 Boot 的**配置级**开关（bootstrap 阶段决定加载哪些配置文件），两者互补不冲突。Boot 侧 profile 在 `ConfigDataEnvironment.withProfiles`（行 279-297）里从配置数据中推导，交给 `Profiles` 类展开：

【源码证据】core/spring-boot/src/main/java/org/springframework/boot/context/config/Profiles.java | 构造器 | 行 82-91

```java
Profiles(Environment environment, Binder binder, @Nullable Collection<String> additionalProfiles) {
    ProfilesValidator validator = ProfilesValidator.get(binder);
    ...
    this.groups = binder.bind("spring.profiles.group", STRING_STRINGS_MAP, validator)
        .orElseGet(LinkedMultiValueMap::new);                 // profile 组：组名 → 一组 profile
    this.activeProfiles = expandProfiles(getActivatedProfiles(environment, binder, validator, additionalProfiles));
    this.defaultProfiles = expandProfiles(getDefaultProfiles(environment, binder, validator));
}
```

**2.4 的重要变化**：配置文件里不再允许写 `spring.profiles.include`/`spring.profiles.active`（profile 专属文件中尤其禁止），由静态校验强制抛异常：

【源码证据】core/spring-boot/src/main/java/org/springframework/boot/context/config/InvalidConfigDataPropertyException.java | 静态块 | 行 51-61

```java
private static final Set<ConfigurationPropertyName> PROFILE_SPECIFIC_ERRORS;
static {
    Set<ConfigurationPropertyName> errors = new LinkedHashSet<>();
    errors.add(Profiles.INCLUDE_PROFILES);                                   // spring.profiles.include
    errors.add(Profiles.INCLUDE_PROFILES.append("[0]"));
    errors.add(ConfigurationPropertyName.of(AbstractEnvironment.ACTIVE_PROFILES_PROPERTY_NAME));
    ...
}
```

替代方案是 `spring.config.activate.on-profile`（文档级条件激活，`spring.profiles` 的官方替身，同类行 44-47 的 ERRORS 映射），其匹配逻辑在 `ConfigDataProperties.Activate.isActive`——用 Framework 的 `Profiles.of(...)` 表达式匹配激活 profile（ConfigDataProperties.java 行 122-137）。profile 名本身的合法性由 `ProfilesValidator` 校验（只允许字母数字与 `-_.+@`，且首尾必须是字母数字，ProfilesValidator.java 行 80-88）。

## 4.5 运行时校验：@Validated + JSR-303

**白话**：绑定完成后还能再过一道"体检"。给配置类加 `@Validated`（并在字段上加 `@NotNull` 等 JSR-303 注解），Binder 就会在绑定时调用校验器，失败即抛异常阻断启动。

校验器在 `ConfigurationPropertiesBinder.getBindHandler` 中被包进 `ValidationBindHandler`，选择逻辑在：

【源码证据】core/spring-boot/src/main/java/org/springframework/boot/context/properties/ConfigurationPropertiesBinder.java | getValidators | 行 152-167

```java
private List<Validator> getValidators(Bindable<?> target) {
    List<Validator> validators = new ArrayList<>(3);
    if (this.configurationPropertiesValidator != null) {
        validators.add(this.configurationPropertiesValidator);            // 名为 configurationPropertiesValidator 的 bean
    }
    if (this.jsr303Present && target.getAnnotation(Validated.class) != null) {
        ...
        validators.add(getJsr303Validator(resolved));                     // @Validated → JSR-303 校验器
    }
    ...
}
```

JSR-303 校验器最终委托给 Framework 的 `LocalValidatorFactoryBean`（ConfigurationPropertiesJsr303Validator.java 行 67-75）。触发点在 `ValidationBindHandler.onFinish`：绑定树走完后调用 `validate`，收集到错误且深度归零（整个前缀绑完）时抛 `BindValidationException`：

【源码证据】core/spring-boot/src/main/java/org/springframework/boot/context/properties/bind/validation/ValidationBindHandler.java | onFinish / validate | 行 105-124

```java
public void onFinish(ConfigurationPropertyName name, Bindable<?> target, BindContext context,
        @Nullable Object result) throws Exception {
    validate(name, target, context, result);
    super.onFinish(name, target, context, result);
}
private void validate(ConfigurationPropertyName name, Bindable<?> target, BindContext context,
        @Nullable Object result) {
    ...
    if (context.getDepth() == 0 && this.exception != null) {
        throw this.exception;                                             // 顶层绑定结束时统一抛出
    }
}
```

由于它发生在 `postProcessBeforeInitialization` 内，异常被 `ConfigurationPropertiesBindingPostProcessor.bind` 包装成 `ConfigurationPropertiesBindException`，应用直接启动失败——这就是"配置错了第一时间暴露"的机制保障。

## 4.6 小结

- Boot 在 Framework 的有序 PropertySource 货架上按固定清单叠源：命令行 > SPRING_APPLICATION_JSON > Servlet/JNDI > 系统属性 > 环境变量 > random > 配置文件（jar 外 profile 专属 > jar 内 profile 专属 > jar 外 application > jar 内 application）> @PropertySource > 默认值。
- 配置文件由 ConfigData 机制在 **refresh 之前的 bootstrap 阶段**加载（EnvironmentPostProcessor 触发），`spring.config.import`/`optional:` 支持声明式组合；YAML 逐文档解析并全程携带 Origin。
- `Binder` 通过 `ConfigurationPropertiesBindingPostProcessor`（BeanPostProcessor）在 Bean 初始化前完成递归绑定：MapBinder/CollectionBinder/ArrayBinder/JavaBeanBinder/ValueObjectBinder 各司其职，`${}` 占位符先由 PropertySourcesPlaceholdersResolver 展开，类型转换复用 Framework 的 ConversionService（BindConverter）。
- 宽松绑定由 ConfigurationPropertyName 规范化 + SystemEnvironmentPropertyMapper 适配实现；单参数构造器/record 自动走构造器绑定，保证不可变配置。
- profile 在配置层面由 ConfigData 推导与校验：profile 专属文件中禁用 `spring.profiles.include`/`active`，用 `spring.config.activate.on-profile` 与 `spring.profiles.group` 表达条件与分组。
- `@Validated` + JSR-303 校验挂在 ValidationBindHandler 的 onFinish 上，与绑定一气呵成，出错即 `ConfigurationPropertiesBindException`。


---

# 五、内嵌 Web 服务器

上一章我们跟完了自动配置的整体流程。这一章回答一个初学者最常问的问题：**为什么 `SpringApplication.run(App.class, args)` 一个 main 方法，就能起一个能处理 HTTP 请求的 Web 应用？** 没有安装 Tomcat，没有部署 war 包，端口却真的在监听。

## 5.1 先说白话：war 部署 vs 内嵌启动

传统 war 部署的时序是"**容器先起、应用后进**"：先安装独立的 Tomcat 并启动，Tomcat 绑定 8080 端口开始监听；然后把你的 war 包丢进 webapps，容器再回过头来创建你应用里的 `DispatcherServlet`、`ApplicationContext`。也就是说，**服务器活着的时候，你的应用还不存在**；容器的生命周期是主人，应用是客人。

Spring Boot 把这个关系倒过来了："**应用先起，服务器是应用在启动过程中顺手造出来的一个对象**"。`SpringApplication.run()` 的核心就是容器 refresh（Framework 文档第四章讲过：refresh 是一个模板方法，流程固定，留了 `onRefresh()`、`finishRefresh()` 等钩子）。Boot 选用的 `ServletWebServerApplicationContext` 重写了 `onRefresh()` 钩子——在容器 refresh 的**中途**，把 Tomcat 当作一个普通对象 new 出来；但注意：**new 出来不等于监听端口**。真正的"开始监听"被包装成一个 `SmartLifecycle`，延迟到 refresh 最后一站 `finishRefresh()` 才执行——那时所有单例 bean（包括 `DispatcherServlet`）都已就绪。

一句话总结本章主线：**onRefresh 里造服务器（但不绑端口）→ finishRefresh 里启动服务器（绑端口）**。

【源码证据】module/spring-boot-web-server/…/servlet/context/ServletWebServerApplicationContext.java — `onRefresh()`:160-169

```java
@Override
protected void onRefresh() {
    super.onRefresh();
    try {
        createWebServer();
    }
    catch (Throwable ex) {
        throw new ApplicationContextException("Unable to start web server", ex);
    }
}
```

Boot 选择哪个 ApplicationContext 是由 `WebApplicationType` 决定的，SERVLET 类型对应的就是这个类：

【源码证据】module/spring-boot-web-server/…/servlet/context/ServletWebServerApplicationContextFactory.java — `create()`:50-56

```java
public @Nullable ConfigurableApplicationContext create(@Nullable WebApplicationType webApplicationType) {
    return (webApplicationType != WebApplicationType.SERVLET) ? null : createContext();
}

private ConfigurableApplicationContext createContext() {
    return new AnnotationConfigServletWebServerApplicationContext();
}
```

## 5.2 全链路：onRefresh → createWebServer

### 5.2.1 createWebServer：找工厂、造服务器、注册两个"延迟开关"

【源码证据】ServletWebServerApplicationContext.java — `createWebServer()`:183-206

```java
private void createWebServer() {
    WebServer webServer = this.webServer;
    ServletContext servletContext = getServletContext();
    if (webServer == null && servletContext == null) {
        StartupStep createWebServer = getApplicationStartup().start("spring.boot.webserver.create");
        ServletWebServerFactory factory = getWebServerFactory();
        createWebServer.tag("factory", factory.getClass().toString());
        webServer = factory.getWebServer(getSelfInitializer());
        this.webServer = webServer;
        createWebServer.end();
        getBeanFactory().registerSingleton("webServerGracefulShutdown",
                new WebServerGracefulShutdownLifecycle(webServer));
        getBeanFactory().registerSingleton("webServerStartStop", new WebServerStartStopLifecycle(this, webServer));
    }
    ...
    initPropertySources();
}
```

三步看明白：

1. `getWebServerFactory()` 从容器里找**唯一的** `ServletWebServerFactory` bean：找不到抛 `MissingWebServerFactoryBeanException`，找到多个抛异常（:216-226）。这个工厂 bean 是自动配置放进去的（见 5.5）。
2. `factory.getWebServer(getSelfInitializer())` —— 传入一个"自初始化器"，稍后解释。
3. 把返回的 `WebServer` 包成**两个 SmartLifecycle 单例**注册进容器：`webServerStartStop`（负责启动/停止）和 `webServerGracefulShutdown`（负责优雅停机）。这一行就是"延迟启动"的伏笔。

`ServletWebServerFactory` 接口的 Javadoc 把意图写得非常直白——返回的是"配置完毕但暂停"的服务器：

【源码证据】module/spring-boot-web-server/…/servlet/ServletWebServerFactory.java — 接口定义:31-43

```java
@FunctionalInterface
public interface ServletWebServerFactory extends WebServerFactory {

    /**
     * Gets a new fully configured but paused {@link WebServer} instance. Clients should
     * not be able to connect to the returned server until {@link WebServer#start()} is
     * called (which happens when the {@code ApplicationContext} has been fully
     * refreshed).
     */
    WebServer getWebServer(ServletContextInitializer... initializers);
}
```

### 5.2.2 selfInitialize：DispatcherServlet 是怎么被"塞"进 Tomcat 的

`getSelfInitializer()` 返回的其实是一段回调：

【源码证据】ServletWebServerApplicationContext.java — `getSelfInitializer()`:234-236

```java
private org.springframework.boot.web.servlet.ServletContextInitializer getSelfInitializer() {
    return new WebApplicationContextInitializer(this)::initialize;
}
```

这个回调会在 Tomcat 内部的 `ServletContext` 创建出来之后被执行。它干两件事：把当前容器发布为 `ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE`（模拟 war 部署里 `ContextLoaderListener` 的职责），然后遍历 `ServletContextInitializerBeans` 逐个调 `onStartup`：

【源码证据】core/spring-boot/…/web/context/servlet/WebApplicationContextInitializer.java — `initialize()`:48-56

```java
public void initialize(ServletContext servletContext) throws ServletException {
    prepareWebApplicationContext(servletContext);
    registerApplicationScope(servletContext, this.context.getBeanFactory());
    WebApplicationContextUtils.registerEnvironmentBeans(this.context.getBeanFactory(), servletContext);
    for (ServletContextInitializer initializerBean : new ServletContextInitializerBeans(
            this.context.getBeanFactory())) {
        initializerBean.onStartup(servletContext);
    }
}
```

`ServletContextInitializerBeans` 是个"适配器集合"：它把容器里所有 `ServletContextInitializer` 类型的 bean 直接收进来，再把**所有 `Servlet`、`Filter`、`EventListener` 类型的普通 bean 适配成 initializer**。也就是说，你只要声明一个 `DispatcherServlet` bean（不写任何 web.xml、不用 ServletContextInitializer），它就会被自动注册进内嵌 Tomcat——这正是 Spring MVC 能"零配置"工作的原因。

【源码证据】core/spring-boot/…/web/servlet/ServletContextInitializerBeans.java — 构造器:91-103、`addAdaptableBeans`:160-170

```java
public ServletContextInitializerBeans(ListableBeanFactory beanFactory,
        Class<? extends ServletContextInitializer>... initializerTypes) {
    ...
    addServletContextInitializerBeans(beanFactory);   // 直接是 initializer 的 bean
    addAdaptableBeans(beanFactory);                  // Servlet/Filter/Listener 适配
    this.sortedList = this.initializers.values()
        .stream()
        .flatMap((value) -> value.stream().sorted(AnnotationAwareOrderComparator.INSTANCE))
        .toList();
    logMappings(this.initializers);
}
```

```java
protected void addAdaptableBeans(ListableBeanFactory beanFactory) {
    MultipartConfigElement multipartConfig = getMultipartConfig(beanFactory);
    addAsRegistrationBean(beanFactory, Servlet.class,
            new ServletRegistrationBeanAdapter(multipartConfig, beanFactory));
    addAsRegistrationBean(beanFactory, Filter.class, new FilterRegistrationBeanAdapter(beanFactory));
    for (Class<?> listenerType : ServletListenerRegistrationBean.getSupportedTypes()) {
        addAsRegistrationBean(beanFactory, EventListener.class, (Class<EventListener>) listenerType,
                new ServletListenerRegistrationBeanAdapter());
    }
}
```

适配时还有一条特殊规则：名为 `dispatcherServlet` 的 Servlet **永远映射到 `/`**（多个 Servlet 时其他 bean 只能按 bean 名做前缀）：

【源码证据】ServletContextInitializerBeans.java — `ServletRegistrationBeanAdapter.createRegistrationBean`:297-304

```java
@Override
public RegistrationBean createRegistrationBean(String beanName, Servlet source, int totalNumberOfSourceBeans) {
    String url = (totalNumberOfSourceBeans != 1) ? "/" + beanName + "/" : "/";
    if (beanName.equals(DISPATCHER_SERVLET_NAME)) {
        url = "/"; // always map the main dispatcherServlet to "/"
    }
    ServletRegistrationBean<Servlet> bean = new ServletRegistrationBean<>(source, url);
    bean.setName(beanName);
    bean.setMultipartConfig(this.multipartConfig);
    ...
}
```

顺带修正一个常见误解：这条 initializer 链的执行时机**不是** finishRefresh，而是在 Tomcat 的 Context 启动过程中由一个 `ServletContainerInitializer` 回调触发（见下一节），也就是发生在 onRefresh 阶段——此时 `DispatcherServlet` 等少数 web 组件会被**提前**实例化，其余单例仍等到 `finishBeanFactoryInitialization` 才创建。

## 5.3 Tomcat 的实例化与"半启动"

### 5.3.1 TomcatServletWebServerFactory.getWebServer：new 一个 Tomcat

【源码证据】module/spring-boot-tomcat/…/servlet/TomcatServletWebServerFactory.java — `getWebServer()`:162-168

```java
@Override
public WebServer getWebServer(ServletContextInitializer... initializers) {
    TempDirs tempDirs = new TempDirs(getPort());
    Tomcat tomcat = createTomcat(tempDirs);
    prepareContext(tomcat.getHost(), initializers, tempDirs);
    return getTomcatWebServer(tomcat);
}
```

`createTomcat`（定义在基类 TomcatWebServerFactory）做的事和手工装 Tomcat 一一对应：建实例、设工作目录、装 Connector、设置端口：

【源码证据】module/spring-boot-tomcat/…/TomcatWebServerFactory.java — `createTomcat(TempDirs)`:389-413（节选）

```java
protected Tomcat createTomcat(TempDirs tempDirs) {
    if (this.isDisableMBeanRegistry()) {
        Registry.disableRegistry();
    }
    Tomcat tomcat = new Tomcat();
    File baseDir = (getBaseDirectory() != null) ? getBaseDirectory() : tempDirs.createTempDir("tomcat").toFile();
    tomcat.setBaseDir(baseDir.getAbsolutePath());
    ...
    Connector connector = new Connector(getProtocol());
    connector.setThrowOnFailure(true);
    tomcat.getService().addConnector(connector);
    customizeConnector(connector);
    tomcat.setConnector(connector);
    ...
}
```

`customizeConnector` 里的 `connector.setPort(Math.max(getPort(), 0))`（:415-417）把 `server.port` 灌进 Connector——注意此时**只是记了个数字，端口并没有绑定**。`prepareContext` 则构造一个 `TomcatEmbeddedContext` 挂到 Host 下、设置 contextPath/docBase，并调用 `configureContext` 把 Boot 传进来的 initializer 挂成 Tomcat 的 `ServletContainerInitializer`（TomcatServletWebServerFactory.java:189-229、312-319；真正执行 initializer 的是 module/spring-boot-tomcat/…/servlet/DeferredServletContainerInitializers.java `onStartup`:52-56）。

最后 `getTomcatWebServer` 返回包装对象（TomcatServletWebServerFactory.java:428-430）：

```java
protected TomcatWebServer getTomcatWebServer(Tomcat tomcat) {
    return new TomcatWebServer(tomcat, getPort() >= 0, getShutdown());
}
```

### 5.3.2 TomcatWebServer 构造器：initialize() 里的"半启动"

`TomcatWebServer` 构造器直接调 `initialize()`。这里有一个**面试级细节**：`initialize()` 内部确实调用了 `this.tomcat.start()`，但 Tomcat 并没有开始监听端口——因为 Boot 先把 Connector 从 Service 上摘掉了，且设置了 `bindOnInit=false`：

【源码证据】module/spring-boot-tomcat/…/TomcatWebServer.java — 构造器:105-111、`initialize()`:113-131

```java
public TomcatWebServer(Tomcat tomcat, boolean autoStart, Shutdown shutdown) {
    Assert.notNull(tomcat, "'tomcat' must not be null");
    this.tomcat = tomcat;
    this.autoStart = autoStart;
    this.gracefulShutdown = (shutdown == Shutdown.GRACEFUL) ? new GracefulShutdown(tomcat) : null;
    initialize();
}

private void initialize() throws WebServerException {
    logger.info("Tomcat initialized with " + getPortsDescription(false));
    synchronized (this.monitor) {
        try {
            addInstanceIdToEngineName();
            Context context = findContext();
            context.addLifecycleListener((event) -> {
                if (context.equals(event.getSource()) && Lifecycle.START_EVENT.equals(event.getType())) {
                    // Remove service connectors so that protocol binding doesn't
                    // happen when the service is started.
                    removeServiceConnectors();   // ← 关键：摘掉 Connector
                }
            });
            disableBindOnInit();                 // ← 关键：bindOnInit=false，不绑端口
            // Start the server to trigger initialization listeners
            this.tomcat.start();                 // ← "半启动"：只初始化 Context/Servlet，不监听
            ...
```

摘除动作在 `removeServiceConnectors()`（:172-179），`disableBindOnInit()` 在 :181-190。为什么要"半启动"？因为 Servlet 规范里 web.xml 扫描、ServletContainerInitializer 回调、`DispatcherServlet` 的提前实例化都发生在 Context 启动期——这些必须现在做（好让 initializer 链跑起来），但**端口绝不能现在绑**，否则容器后半段（实例化其余单例、AOP 代理、事件监听器）一旦失败，一个"空壳 Tomcat"就会挂着 8080 端口泄漏出去。

## 5.4 启动时机的精髓：finishRefresh 里的 SmartLifecycle

真正把端口绑上的 `TomcatWebServer.start()`（TomcatWebServer.java:225-255）：

【源码证据】module/spring-boot-tomcat/…/TomcatWebServer.java — `start()`:225-240

```java
@Override
public void start() throws WebServerException {
    synchronized (this.monitor) {
        if (this.started) {
            return;
        }
        try {
            addPreviouslyRemovedConnectors();        // 把摘掉的 Connector 装回去
            Connector connector = this.tomcat.getConnector();
            if (connector != null && this.autoStart) {
                performDeferredLoadOnStartup();      // loadOnStartup 的 Servlet 此时加载
            }
            checkThatConnectorsHaveStarted();        // 端口绑定失败在这里抛 PortInUseException
            this.started = true;
            logger.info(getStartedLogMessage());     // "Tomcat started on port(s) 8080 ..."
        }
        ...
```

那么 `start()` 是谁调的？答案就是 onRefresh 里注册的那个单例：

【源码证据】module/spring-boot-web-server/…/servlet/context/WebServerStartStopLifecycle.java — `start()`:42-48、`getPhase()`:61-64

```java
@Override
public void start() {
    this.webServer.start();
    this.running = true;
    this.applicationContext
        .publishEvent(new ServletWebServerInitializedEvent(this.webServer, this.applicationContext));
}
...
@Override
public int getPhase() {
    return WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE;
}
```

它实现了 Framework 的 `SmartLifecycle`。Framework 文档讲过：refresh 的最后一步 `finishRefresh()` 会触发 `getLifecycleProcessor().onRefresh()`，把容器里所有 `SmartLifecycle` 按 phase 从小到大依次 `start()`：

【源码证据】spring-framework/…/context/support/AbstractApplicationContext.java — `finishRefresh()`:1003-1018

```java
protected void finishRefresh() {
    // Reset common introspection caches in Spring's core infrastructure.
    resetCommonCaches();
    // Clear context-level resource caches (such as ASM metadata from scanning).
    clearResourceCaches();
    // Initialize lifecycle processor for this context.
    initLifecycleProcessor();
    // Propagate refresh to lifecycle processor first.
    getLifecycleProcessor().onRefresh();      // ← 所有 SmartLifecycle 在这里 start
    // Publish the final event.
    publishEvent(new ContextRefreshedEvent(this));
}
```

而 finishRefresh 在 refresh 流程中排在 `finishBeanFactoryInitialization`（实例化全部单例）**之后**。phase 值故意取在 `Integer.MAX_VALUE` 附近、再逐层减 1024，保证 web 服务器属于最后一批启动的 SmartLifecycle：

【源码证据】module/spring-boot-web-server/…/context/WebServerApplicationContext.java — 常量:40、46

```java
int GRACEFUL_SHUTDOWN_PHASE = SmartLifecycle.DEFAULT_PHASE - 1024;

int START_STOP_LIFECYCLE_PHASE = GRACEFUL_SHUTDOWN_PHASE - 1024;
```

（`SmartLifecycle.DEFAULT_PHASE = Integer.MAX_VALUE`，见 spring-framework SmartLifecycle.java:82。）至此，"为什么所有单例就绪后 Tomcat 才真正 start、端口才开始监听"的完整答案就是：**端口绑定代码位于 `TomcatWebServer.start()`，它只被 `WebServerStartStopLifecycle`（SmartLifecycle）调用，而 SmartLifecycle 的 start 由 `finishRefresh()` 的 `lifecycleProcessor.onRefresh()` 触发，在全部单例实例化之后执行。**

### refresh 与服务器启动时序图

```
main → SpringApplication.run()
 └─ refresh() [ServletWebServerApplicationContext, final 防重写]
     │
     ├─ invokeBeanFactoryPostProcessors   ← 自动配置解析出 TomcatServletWebServerFactory
     │
     ├─ ★ onRefresh()（Framework 模板方法的钩子，Boot 在此造服务器）
     │    └─ createWebServer()
     │        ├─ getWebServerFactory()            → 唯一的 ServletWebServerFactory bean
     │        ├─ factory.getWebServer(selfInit)
     │        │    ├─ createTomcat(): new Tomcat / baseDir / Connector(port)
     │        │    ├─ prepareContext(): TomcatEmbeddedContext 挂上 Host
     │        │    └─ new TomcatWebServer(tomcat) → initialize()
     │        │         └─ tomcat.start()  ◄─ "半启动": Connector 已摘除+bindOnInit=false
     │        │                └─ Context 启动 → DeferredServletContainerInitializers
     │        │                   .onStartup() → ServletContextInitializerBeans
     │        │                   → DispatcherServlet 注册到 "/"（bean 被提前实例化）
     │        └─ registerSingleton("webServerStartStop",
     │                 new WebServerStartStopLifecycle(...))   ◄─ 暂存启动权
     │
     ├─ registerBeanPostProcessors / initMessageSource / ...
     │
     ├─ finishBeanFactoryInitialization   ← 其余所有非懒加载单例就绪
     │
     └─ ★ finishRefresh()（Framework:1014 getLifecycleProcessor().onRefresh()）
          └─ SmartLifecycle 按 phase 升序 start
              └─ WebServerStartStopLifecycle.start()   [phase = MAX-2048]
                  ├─ webServer.start() → TomcatWebServer.start()
                  │    ├─ addPreviouslyRemovedConnectors()  ← Connector 装回
                  │    ├─ tomcat.getConnector()             ← ★ 端口绑定，开始监听
                  │    └─ logger.info("Tomcat started on port(s) 8080 ...")
                  └─ publishEvent(ServletWebServerInitializedEvent) → local.server.port
```

另外注意 `ServletWebServerApplicationContext.refresh()` 被声明为 `final` 并加了兜底（:140-158）：refresh 中途任何异常都会触发 `webServer.stop()/destroy()`，保证"造了一半的 Tomcat"不会泄漏。

## 5.5 三件套的自动配置接线

工厂 bean 从哪来？Boot 4.x 把"选服务器"和"通用 Servlet 配置"拆成了两个层次。

**第一层：Tomcat/Jetty/Undertow 谁生效，靠 `@ConditionalOnClass` 决定。** 每个 web 服务器模块在自己的 `META-INF/spring/...AutoConfiguration.imports` 里注册自动配置类（module/spring-boot-tomcat 的 imports 文件里就有 `...tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration`）。类路径上有 Tomcat 的 `Tomcat.class`，这个配置类整条生效：

【源码证据】module/spring-boot-tomcat/…/autoconfigure/servlet/TomcatServletWebServerAutoConfiguration.java — :50-74（节选）

```java
@AutoConfiguration
@ConditionalOnClass({ ServletRequest.class, Tomcat.class, UpgradeProtocol.class, TomcatServletWebServerFactory.class })
@ConditionalOnWebApplication(type = Type.SERVLET)
@EnableConfigurationProperties(TomcatServerProperties.class)
@Import({ ServletWebServerConfiguration.class, TomcatWebServerConfiguration.class })
public final class TomcatServletWebServerAutoConfiguration {
    ...
    @Bean
    @ConditionalOnMissingBean(value = ServletWebServerFactory.class, search = SearchStrategy.CURRENT)
    TomcatServletWebServerFactory tomcatServletWebServerFactory(
            ObjectProvider<TomcatConnectorCustomizer> connectorCustomizers, ...) {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
        factory.getConnectorCustomizers().addAll(connectorCustomizers.orderedStream().toList());
        ...
        return factory;
    }
```

引入 `spring-boot-starter-web` → 类路径有 Tomcat → 这个类生效 → 容器里出现唯一的 `ServletWebServerFactory`；换成 `spring-boot-starter-jetty`（排除 tomcat 依赖）则是 Jetty 的对应类生效。`@ConditionalOnMissingBean` 保证用户自定义的工厂优先。

**第二层：`ServletWebServerConfiguration`**（由上面的 `@Import` 带入，位于 module/spring-boot-web-server/…/autoconfigure/servlet/ServletWebServerConfiguration.java:51-62）负责启用 `ServerProperties`、注册 `ServletWebServerFactoryCustomizer`，并通过 `BeanPostProcessorsRegistrar` 提前注册两个 BeanPostProcessor：`webServerFactoryCustomizerBeanPostProcessor` 和 `errorPageRegistrarBeanPostProcessor`（:83-86）。

**第三件：DispatcherServlet 的注册**。上一节说"Servlet 类型的 bean 会被自动适配注册"，而提供这个 bean 的正是：

【源码证据】module/spring-boot-webmvc/…/autoconfigure/DispatcherServletAutoConfiguration.java — :88-96、:115-125

```java
@Bean(name = DEFAULT_DISPATCHER_SERVLET_BEAN_NAME)      // "dispatcherServlet"
DispatcherServlet dispatcherServlet(WebMvcProperties webMvcProperties) {
    DispatcherServlet dispatcherServlet = new DispatcherServlet();
    dispatcherServlet.setDispatchOptionsRequest(webMvcProperties.isDispatchOptionsRequest());
    ...
    return dispatcherServlet;
}

@Bean(name = DEFAULT_DISPATCHER_SERVLET_REGISTRATION_BEAN_NAME)   // "dispatcherServletRegistration"
@ConditionalOnBean(value = DispatcherServlet.class, name = DEFAULT_DISPATCHER_SERVLET_BEAN_NAME)
DispatcherServletRegistrationBean dispatcherServletRegistration(DispatcherServlet dispatcherServlet,
        WebMvcProperties webMvcProperties, ObjectProvider<MultipartConfigElement> multipartConfig) {
    DispatcherServletRegistrationBean registration = new DispatcherServletRegistrationBean(dispatcherServlet,
            webMvcProperties.getServlet().getPath());   // 默认 "/"
    ...
}
```

即使用户自己定义了别的 `DispatcherServlet` bean，`DefaultDispatcherServletCondition`（:130-155）会让默认的让位——Framework 文档里"约定可被覆盖"的思想在这里又一次落地。

**@ServletComponentScan 一句话**：如果你习惯原生的 `@WebServlet/@WebFilter/@WebListener` 注解，Boot 提供了这个开关——它通过 `ServletComponentScanRegistrar` 注册一个 `BeanDefinitionRegistryPostProcessor`，扫描到带注解的类后转成对应的 RegistrationBean 注册（module/spring-boot-web-server/…/servlet/context/ServletComponentRegisteringPostProcessor.java:62-64 依次挂上 `WebServletHandler/WebFilterHandler/WebListenerHandler`）。注意它扫描的是注解元数据，和 Spring 的 `@ComponentScan` 是两套体系。

## 5.6 WebServerFactoryCustomizer 定制链：server.port 如何到达 Connector

白话：Boot 不希望你为了改个端口去 `@Bean` 覆盖整个 `TomcatServletWebServerFactory`，而是提供了一排"改装接口"——`WebServerFactoryCustomizer`。它的接线器是一个 BeanPostProcessor：**每当有 `WebServerFactory` 类型的 bean 初始化时，就把容器里所有 Customizer 逐个 `customize()` 一遍**。

【源码证据】module/spring-boot-web-server/…/WebServerFactoryCustomizerBeanPostProcessor.java — :58-63、:70-75

```java
@Override
public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
    if (bean instanceof WebServerFactory webServerFactory) {
        postProcessBeforeInitialization(webServerFactory);
    }
    return bean;
}
...
private void postProcessBeforeInitialization(WebServerFactory webServerFactory) {
    LambdaSafe.callbacks(WebServerFactoryCustomizer.class, getCustomizers(), webServerFactory)
        .withLogger(WebServerFactoryCustomizerBeanPostProcessor.class)
        .invoke((customizer) -> customizer.customize(webServerFactory));
}
```

`server.port=9090` 这类属性的搬运工，是自动配置提供的通用 Customizer：

【源码证据】module/spring-boot-web-server/…/autoconfigure/servlet/ServletWebServerFactoryCustomizer.java — `customize()`:75-98（节选）

```java
@Override
public void customize(ConfigurableServletWebServerFactory factory) {
    PropertyMapper map = PropertyMapper.get();
    map.from(this.serverProperties::getPort).to(factory::setPort);
    map.from(this.serverProperties::getAddress).to(factory::setAddress);
    map.from(this.serverProperties.getServlet()::getContextPath).to(factory::setContextPath);
    ...
    map.from(this.serverProperties::getSsl).to(factory::setSsl);
    map.from(this.serverProperties::getCompression).to(factory::setCompression);
    ...
}
```

属性源头是 `@ConfigurationProperties("server")` 的 `ServerProperties`（module/spring-boot-web-server/…/autoconfigure/ServerProperties.java:72-78，`private @Nullable Integer port;`）。Tomcat 专属参数（如 redirectContextRoot）则由 tomcat 模块自己的 `TomcatServletWebServerFactoryCustomizer` 补充（TomcatServletWebServerFactoryCustomizer.java:34、49）。所以整条链是：

`application.yml` → `ServerProperties` → `ServletWebServerFactoryCustomizer.customize()` → `factory.setPort()` → 之后 `getWebServer()` 里 `customizeConnector()` → `connector.setPort()`（TomcatWebServerFactory.java:415-417）。你写的自定义 `WebServerFactoryCustomizer<TomcatServletWebServerFactory>` bean 也会被同一个 PostProcessor 调用，时机在工厂 bean 初始化时、服务器创建之前。

## 5.7 错误处理：/error、BasicErrorController 与白标页

浏览器访问不存在的路径为什么返回一个规整的 JSON/HTML 错误，而不是 Tomcat 默认的灰白堆栈页？这是 Servlet 规范的 error page 机制 + 一个兜底 Controller 的组合：

- **ErrorPageRegistrar**：`ErrorMvcAutoConfiguration` 注册了一个 `ErrorPageCustomizer`，把 error 页路径（默认 `/error`）登记到内嵌服务器上——Tomcat 收到 404/500 时会 forward 到这个路径：

【源码证据】module/spring-boot-webmvc/…/error/ErrorMvcAutoConfiguration.java — `registerErrorPages()`:273-278

```java
@Override
public void registerErrorPages(ErrorPageRegistry errorPageRegistry) {
    ErrorPage errorPage = new ErrorPage(
            this.dispatcherServletPath.getRelativePath(this.properties.getError().getPath()));
    errorPageRegistry.addErrorPages(errorPage);
}
```

- **BasicErrorController**：处理 forward 过来的 `/error` 请求，同一个路径两个方法，按请求的 Accept 头决定产出页面还是 JSON：

【源码证据】module/spring-boot-webmvc/…/error/BasicErrorController.java — :60、:87-95、:97-106

```java
@Controller
@RequestMapping("${spring.web.error.path:${error.path:/error}}")
public class BasicErrorController extends AbstractErrorController {
    ...
    @RequestMapping(produces = MediaType.TEXT_HTML_VALUE)
    public ModelAndView errorHtml(HttpServletRequest request, HttpServletResponse response) { ... }

    @RequestMapping
    public ResponseEntity<Map<String, @Nullable Object>> error(HttpServletRequest request) {
        HttpStatus status = getStatus(request);
        if (status == HttpStatus.NO_CONTENT) {
            return new ResponseEntity<>(status);
        }
        Map<String, @Nullable Object> body = getErrorAttributes(request,
                getErrorAttributeOptions(request, MediaType.ALL));
        return new ResponseEntity<>(body, status);
    }
```

- **白标页一句话**：当没有任何模板引擎/自定义 error 视图时（`ErrorTemplateMissingCondition` 匹配），`ErrorMvcAutoConfiguration.WhitelabelErrorViewConfiguration` 注册一个名为 `"error"` 的 `StaticView`，其 `render()` 直接拼 HTML 字符串——`builder.append("<html><body><h1>Whitelabel Error Page</h1>")`（ErrorMvcAutoConfiguration.java:193-225），这就是那个白底 "Whitelabel Error Page" 的全部实现，配 `spring.web.error.whitelabel.enabled=false` 即可关掉（:145）。

## 5.8 静态资源与欢迎页

`WebMvcAutoConfiguration` 通过实现 `WebMvcConfigurer.addResourceHandlers` 添加了两条资源映射：webjars 一条，静态资源一条，后者把 `WebProperties` 里的四个 classpath 位置全部挂上，外加 ServletContext 根：

【源码证据】module/spring-boot-webmvc/…/autoconfigure/WebMvcAutoConfiguration.java — `addResourceHandlers()`:334-349

```java
@Override
public void addResourceHandlers(ResourceHandlerRegistry registry) {
    if (!this.resourceProperties.isAddMappings()) {
        logger.debug("Default resource handling disabled");
        return;
    }
    addResourceHandler(registry, this.mvcProperties.getWebjarsPathPattern(),
            "classpath:/META-INF/resources/webjars/");
    addResourceHandler(registry, this.mvcProperties.getStaticPathPattern(), (registration) -> {
        registration.addResourceLocations(this.resourceProperties.getStaticLocations());
        if (this.servletContext != null) {
            ServletContextResource resource = new ServletContextResource(this.servletContext, SERVLET_LOCATION);
            registration.addResourceLocations(resource);
        }
    });
}
```

四个默认位置的出处（`spring.web.resources.static-locations` 可覆盖）：

【源码证据】core/spring-boot-autoconfigure/…/autoconfigure/web/WebProperties.java — :98-99

```java
private static final String[] CLASSPATH_RESOURCE_LOCATIONS = { "classpath:/META-INF/resources/",
        "classpath:/resources/", "classpath:/static/", "classpath:/public/" };
```

欢迎页（`index.html`）由同类的 `WelcomePageHandlerMapping` 处理：它在各静态资源位置里找 `index.html`（WebMvcAutoConfiguration.java:579 `location.createRelative("index.html")`），找到就把 `/` 映射到它——这解释了为什么把一个 index.html 丢进 `src/main/resources/static/` 就能当首页。注意 `server.servlet.context-path` 与静态资源前缀都走同一套属性绑定，`spring.web.resources.add-mappings=false` 可整体关闭。

## 5.9 反应式栈：ReactiveWebServerApplicationContext 的同构对照

当应用类型是 REACTIVE（WebFlux）时，Boot 换用 `ReactiveWebServerApplicationContext`。骨架与 Servlet 版**完全同构**：同样 `final refresh()` 兜底（:67-84）、同样重写 `onRefresh()`（:86-95）调 `createWebServer()`（:97-113），同样注册 `webServerGracefulShutdown` 与 `webServerStartStop` 两个 SmartLifecycle 单例。差别只在"适配层"：Servlet 世界里被适配的是 `Servlet/Filter` bean，反应式世界里被适配的是唯一的 **`HttpHandler`** bean，包在 `WebServerManager` 里交给工厂：

【源码证据】module/spring-boot-web-server/…/reactive/context/ReactiveWebServerApplicationContext.java — `createWebServer()`:97-110（节选）

```java
private void createWebServer() {
    WebServerManager serverManager = this.serverManager;
    if (serverManager == null) {
        ...
        ReactiveWebServerFactory webServerFactory = getWebServerFactory(webServerFactoryBeanName);
        boolean lazyInit = getBeanFactory().getBeanDefinition(webServerFactoryBeanName).isLazyInit();
        serverManager = new WebServerManager(this, webServerFactory, this::getHttpHandler, lazyInit);
        this.serverManager = serverManager;
        getBeanFactory().registerSingleton("webServerGracefulShutdown",
                new WebServerGracefulShutdownLifecycle(serverManager.getWebServer()));
        getBeanFactory().registerSingleton("webServerStartStop", new WebServerStartStopLifecycle(serverManager));
        createWebServer.end();
    }
    ...
}
```

`WebServerManager` 里有个值得玩味的 `DelayedInitializationHttpHandler`：服务器创建时 handler 是个"未初始化"占位符，真正的 `HttpHandler` 要等 start 时才 `initializeHandler()`（WebServerManager.java:49-55、:85、:101-103）——这是反应式版本对"别让 HttpHandler 被过早实例化"的对应处理。Servlet 版与反应式版逐项对照：

| 维度 | Servlet 栈 | Reactive 栈 |
| --- | --- | --- |
| ApplicationContext | `ServletWebServerApplicationContext` | `ReactiveWebServerApplicationContext` |
| 重写的模板方法钩子 | `onRefresh()` → `createWebServer()` | 相同（:86-95） |
| 工厂接口 | `ServletWebServerFactory.getWebServer(ServletContextInitializer...)` | `ReactiveWebServerFactory.getWebServer(HttpHandler)` |
| 适配进服务器的东西 | Servlet/Filter/Listener bean → `ServletContextInitializerBeans` | 唯一 `HttpHandler` bean → `WebServerManager` 延迟包装 |
| 默认服务器 | Tomcat（`TomcatServletWebServerFactory`） | Netty/Reactor（`NettyReactiveWebServerFactory.getWebServer` 构造 `HttpServer` + `ReactorHttpHandlerAdapter` → `new NettyWebServer(...)`，NettyReactiveWebServerFactory.java:74-85） |
| 延迟启动开关 | `servlet/context/WebServerStartStopLifecycle` | `reactive/context/WebServerStartStopLifecycle`（:40-43 start 里 `weServerManager.start()`：先 initializeHandler 再 `webServer.start()`） |
| 实际开始监听 | `TomcatWebServer.start()` 里 Connector 装回+绑定 | `NettyWebServer.start()` 里 `startHttpServer()` 完成 channel bind，绑不上抛 `PortInUseException`（NettyWebServer.java:119-144） |
| 优雅停机 | 同下 | 同下 |

优雅停机一句话：`WebServerGracefulShutdownLifecycle`（module/spring-boot-web-server/…/context/WebServerGracefulShutdownLifecycle.java）的 phase 是 `GRACEFUL_SHUTDOWN_PHASE = DEFAULT_PHASE - 1024`（:65-67），比 start/stop 的 phase 大，因此容器关闭时它**先**被调用，`stop(Runnable)` 里 `webServer.shutDownGracefully((result) -> callback.run())`（:54-57）先停止接收新请求、等在途请求处理完，之后 `WebServerStartStopLifecycle.stop()` 才真正 `webServer.stop()`。前置条件是 `server.shutdown=graceful`（Tomcat 侧由 `GracefulShutdown` 实现）。

## 5.10 端口与地址：随机端口与 WebServerInitializedEvent

两句话收尾，都是高频用法：

1. **`server.port=0`**：0 一路传给 `connector.setPort()`，操作系统分配随机可用端口；同时 `getTomcatWebServer` 里 `getPort() >= 0` 恒真，服务器照常自动启动（TomcatServletWebServerFactory.java:428-430）。
2. **拿实际端口**：Tomcat 绑定端口成功的那一刻，`WebServerStartStopLifecycle.start()` 发布 `ServletWebServerInitializedEvent`（WebServerStartStopLifecycle.java:46-47，抽象基类 module/spring-boot-web-server/…/context/WebServerInitializedEvent.java:31-41）。Boot 自带的监听器 `ServerPortInfoApplicationContextInitializer` 把实际端口写进 Environment：

【源码证据】module/spring-boot-web-server/…/context/ServerPortInfoApplicationContextInitializer.java — `onApplicationEvent()`:62-66

```java
@Override
public void onApplicationEvent(WebServerInitializedEvent event) {
    String propertyName = "local." + getName(event.getApplicationContext()) + ".port";
    setPortProperty(event.getApplicationContext(), propertyName, event.getWebServer().getPort());
}
```

于是任何 bean 里 `@Value("${local.server.port}")`、或测试里 `@LocalServerPort`，本质都是读这个事件写入的属性。

## 5.11 小结

- 内嵌 = 把"容器起服务"变成"服务是容器里的一个对象"：`ServletWebServerApplicationContext` 重写 refresh 的 `onRefresh()` 钩子，在容器启动中途 new 出 Tomcat。
- `factory.getWebServer(initializers)` 返回的是"配置完毕但暂停"的服务器；Servlet/Filter/Listener bean 经 `ServletContextInitializerBeans` 适配后，由 `DeferredServletContainerInitializers` 在 Tomcat Context 启动期注册——`DispatcherServlet` 就是这样进 Tomcat 的。
- **端口监听被刻意推迟**：`TomcatWebServer.initialize()` 里虽有 `tomcat.start()`，但 Connector 已摘除、`bindOnInit=false`；真正绑定端口在 `WebServerStartStopLifecycle.start()`（SmartLifecycle），由 `finishRefresh()` 的 `lifecycleProcessor.onRefresh()` 触发——此时全部单例已就绪。
- 自动配置三层接线：`@ConditionalOnClass` 选服务器 → `ServletWebServerConfiguration` 注册 PostProcessor → `DispatcherServletAutoConfiguration` 提供 Servlet bean；`server.*` 属性经 `WebServerFactoryCustomizer` 链灌进工厂。
- 错误页 = `ErrorPageCustomizer`（登记 /error）+ `BasicErrorController`（HTML/JSON 双产出）+ 白标 `StaticView` 兜底；静态资源四个 classpath 位置由 `WebMvcAutoConfiguration.addResourceHandlers` 映射。
- 反应式栈逐层同构：同样的 onRefresh/createWebServer/SmartLifecycle 三板斧，只是把"Servlet 适配"换成"HttpHandler 适配"。

理解了"谁在什么时候造服务器、谁在什么时候按下启动键"，下一章讲 Actuator 的健康检查与指标时你会发现：Actuator 的端点也是同样套路——在 finishRefresh 前就绑定到同一个 `WebServer` 上。


---

# 六、Actuator 与生产级运维

> 白话开场：前几章解决了"应用怎么跑起来"——IoC 容器装好 Bean、自动配置把中间件接上。但应用一上生产，运维同学的问题就来了：**它还活着吗？连上数据库了吗？QPS 多少？谁把配置改坏了？** 传统 Spring 项目里这些都要靠人肉写管理页面或登服务器执行命令，这正是 Boot 立项时对传统 Spring 的第四个痛点——**生产运维缺失**。这些问题的答案就是 **Actuator（执行器）**：一组现成的"运维端点"，通过 HTTP（`/actuator/**`）或 JMX 把应用内部状态暴露出去。你在 Framework 篇学过的"机制与实现分离"在这里再次上演——Actuator 最漂亮的设计是：**端点定义与暴露协议解耦**，一个 `@Endpoint` 类不用改一行代码，就能同时变成 HTTP 接口和 JMX MBean。

本章源码来自 Spring Boot **4.2.0-SNAPSHOT**。先说 4.x 的目录布局（与 2.x/3.x 差异很大，读代码前必须确认；也提醒一句：网上多数 Actuator 源码文章基于 2.x，类名和包名对不上时以本章为准）：

- 端点**基础设施**（注解、发现器、JMX）在 `module/spring-boot-actuator`，包名 `org.springframework.boot.actuate`；
- **健康检查**被拆成了独立模块 `module/spring-boot-health`（包名 `org.springframework.boot.health`，`HealthEndpoint` 不再在 actuator 模块里）；
- **HTTP 映射**在 `module/spring-boot-webmvc` / `spring-boot-webflux`（`WebMvcEndpointHandlerMapping` 于 4.0 迁到了这里）；
- **自动配置**在 `module/spring-boot-actuator-autoconfigure`；starter 在 `starter/spring-boot-starter-actuator`，其 `build.gradle` 依赖 `spring-boot-actuator-autoconfigure`、`spring-boot-health` 与 `io.micrometer:micrometer-observation`。

---

## 6.1 定位：Boot 的第四个痛点——"生产运维缺失"的答案

Framework 给了你构建应用的一切，却没给运维接口：想看 Bean 列表要自己写代码遍历 BeanFactory，想改日志级别要重启。Actuator 把这些**高频运维操作固化成端点（Endpoint）**：每个端点有一个 id（如 `health`、`beans`），通过 `GET /actuator/health` 这样的 URL 或 JMX 属性/操作访问。访问 `GET /actuator` 会返回所有可用端点的链接目录。前缀 `/actuator` 本身也是配置项：

【源码证据】`module/spring-boot-actuator-autoconfigure/src/main/java/org/springframework/boot/actuate/autoconfigure/endpoint/web/WebEndpointProperties.java` — 字段 `basePath`，L47：

```java
	private String basePath = "/actuator";
```

所有端点 URL 都是 `basePath + 端点 id + 操作路径` 的拼接（由 `EndpointMapping.createSubPath` 完成）。

**为什么默认只暴露 health？** 因为 Actuator 能看到的东西太多了——`env` 能看到数据源密码（虽经脱敏）、`shutdown` 能直接关停应用。全暴露等于把后门开在生产端口上，所以 Boot 的默认值极其保守：`management.endpoints.web.exposure.include` 不配置时，只有 `health`。

【源码证据】`module/spring-boot-actuator-autoconfigure/src/main/java/org/springframework/boot/actuate/autoconfigure/endpoint/expose/EndpointExposure.java` — 枚举 `EndpointExposure`，L25-41：

```java
public enum EndpointExposure {

	/**
	 * Exposed over a JMX endpoint.
	 */
	JMX("health"),

	/**
	 * Exposed over a web endpoint.
	 */
	WEB("health");

	private final String[] defaultIncludes;
```

JMX 和 WEB 两种协议的默认包含集都是 `health`——这就是"默认只暴露 health"的定义位置。它作为 `defaultIncludes` 兜底参数传入暴露过滤器（见 6.2.4）。

---

## 6.2 端点技术体系（本章核心）

### 6.2.1 注解本体

一个端点 = `@Endpoint` 标注的 Bean + 若干操作方法。白话：`@Endpoint` 相当于运维世界的 `@RestController`，而三个操作注解对应三种 HTTP 动词。

【源码证据】`module/spring-boot-actuator/src/main/java/org/springframework/boot/actuate/endpoint/annotation/Endpoint.java` — 注解 `Endpoint` 声明处，L53-72：

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Reflective
public @interface Endpoint {

	/**
	 * The id of the endpoint (must follow {@link EndpointId} rules).
	 * @return the id
	 * @see EndpointId
	 */
	String id() default "";

	/**
	 * Level of access to the endpoint that is permitted by default.
	 * @return the default level of access
	 * @since 3.4.0
	 */
	Access defaultAccess() default Access.UNRESTRICTED;
```

`id` 决定 URL 路径与 MBean 名；`defaultAccess`（`Access.NONE/READ_ONLY/UNRESTRICTED`）控制默认访问级别。三个操作注解以 `@ReadOperation` 为例：

【源码证据】`module/spring-boot-actuator/src/main/java/org/springframework/boot/actuate/endpoint/annotation/ReadOperation.java` — 注解 `ReadOperation`，L34-44：

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Reflective(OperationReflectiveProcessor.class)
public @interface ReadOperation {

	/**
	 * The media type of the result of the operation.
	 * @return the media type
	 */
	String[] produces() default {};
```

`@WriteOperation`/`@DeleteOperation` 结构相同。还有一个只暴露给 HTTP 的变体：

【源码证据】`module/spring-boot-actuator/src/main/java/org/springframework/boot/actuate/endpoint/web/annotation/WebEndpoint.java` — 注解 `WebEndpoint`，L37-49：

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Endpoint
@FilteredEndpoint(WebEndpointFilter.class)
public @interface WebEndpoint {

	/**
	 * The id of the endpoint.
	 * @return the id
	 */
	@AliasFor(annotation = Endpoint.class)
	String id();
```

注意它是**元注解组合**：`@Endpoint` + `@FilteredEndpoint(WebEndpointFilter.class)`，即"标记自己只接受 Web 发现器"，JMX 发现器会把它过滤掉——这正是解耦架构里的"协议限定"手法。

### 6.2.2 EndpointDiscoverer 全链路：扫描 → EndpointInfo（EndpointBean）→ OperationKey

白话：发现器干的事和 Framework 篇的 ClassPathBeanDefinitionScanner 很像，只是它扫的不是 @Component，而是 @Endpoint Bean，并把每个 Bean 的方法加工成可调用的 Operation。

【源码证据】`module/spring-boot-actuator/src/main/java/org/springframework/boot/actuate/endpoint/annotation/EndpointDiscoverer.java` — 方法 `getEndpoints()`，L130-144：

```java
	@Override
	public final Collection<E> getEndpoints() {
		Collection<E> endpoints = this.endpoints;
		if (endpoints == null) {
			endpoints = discoverEndpoints();
			this.endpoints = endpoints;
		}
		return endpoints;
	}

	private Collection<E> discoverEndpoints() {
		Collection<EndpointBean> endpointBeans = createEndpointBeans();
		addExtensionBeans(endpointBeans);
		return convertToEndpoints(endpointBeans);
	}
```

三步流水线 + **结果缓存**：`endpoints` 是 L87 声明的 `volatile` 字段，首次调用后缓存，之后每次 HTTP/JMX 访问都直接复用（另有一个 `filterEndpoints` ConcurrentHashMap 缓存 L85，避免过滤判断时重复创建端点对象）。

第一步，扫描 Bean 得到"端点信息"：

【源码证据】同上文件 `EndpointDiscoverer` — 方法 `createEndpointBeans()`，L146-161：

```java
	private Collection<EndpointBean> createEndpointBeans() {
		Map<EndpointId, EndpointBean> byId = new LinkedHashMap<>();
		String[] beanNames = BeanFactoryUtils.beanNamesForAnnotationIncludingAncestors(this.applicationContext,
				Endpoint.class);
		for (String beanName : beanNames) {
			if (!ScopedProxyUtils.isScopedTarget(beanName)) {
				EndpointBean endpointBean = createEndpointBean(beanName);
				EndpointBean previous = byId.putIfAbsent(endpointBean.getId(), endpointBean);
				if (previous != null) {
					throw new IllegalStateException("Found two endpoints with the id '" + endpointBean.getId() + ...
```

`BeanFactoryUtils.beanNamesForAnnotationIncludingAncestors`（含祖先上下文）找出所有 @Endpoint Bean，包装成内部类 `EndpointBean`——它从类上的注解读出 id 和 defaultAccess（L479-491），这就是"端点信息"在本版的真实载体；id 重复直接抛异常。

第二步，把操作方法加工出来。核心在 `DiscoveredOperationsFactory`：

【源码证据】`module/spring-boot-actuator/src/main/java/org/springframework/boot/actuate/endpoint/annotation/DiscoveredOperationsFactory.java` — 静态块与方法 `createOperations()`/`createOperation()`，L53-61、L73-99：

```java
	private static final Map<OperationType, Class<? extends Annotation>> OPERATION_TYPES;
	static {
		Map<OperationType, Class<? extends Annotation>> operationTypes = new EnumMap<>(OperationType.class);
		operationTypes.put(OperationType.READ, ReadOperation.class);
		operationTypes.put(OperationType.WRITE, WriteOperation.class);
		operationTypes.put(OperationType.DELETE, DeleteOperation.class);
		...
	Collection<O> createOperations(EndpointId id, Object target) {
		return MethodIntrospector
			.selectMethods(target.getClass(), (MetadataLookup<O>) (method) -> createOperation(id, target, method))
			.values();
	}
```

对端点类的每个方法，用 `MethodIntrospector.selectMethods` 匹配三种操作注解；命中后构造 `DiscoveredOperationMethod`（方法元数据），并用 `ReflectiveOperationInvoker`（L96）把"反射调用"封装成统一入口——HTTP 或 JMX 最终都调它。之后 `EndpointDiscoverer.convertToEndpoint()`（L227-245）把操作按 `OperationKey` 放进 `LinkedMultiValueMap` 去重。OperationKey 是什么？

【源码证据】同上文件 `EndpointDiscoverer` — 内部类 `OperationKey`，L419-435：

```java
	protected static final class OperationKey {

		private final Object key;

		private final Supplier<String> description;

		/**
		 * Create a new {@link OperationKey} instance.
		 * @param key the underlying key for the operation
		 * @param description a human-readable description of the key
		 */
		public OperationKey(Object key, Supplier<String> description) {
```

它只是操作唯一性的标识，具体取什么做 key 由子类决定。Web 版用"请求谓词"做 key：

把整条链路串成一句话（文字流程图）：

> `getEndpoints()` 首次调用 → `createEndpointBeans()` 扫 @Endpoint Bean 得到 EndpointBean（id/defaultAccess）→ `addExtensionBeans()` 挂上 @EndpointExtension（如 reactive 扩展）→ `convertToEndpoint()` 里经 `DiscoveredOperationsFactory` 把 @Read/@Write/@Delete 方法变成 `DiscoveredOperationMethod + ReflectiveOperationInvoker` → 按子类给的 `OperationKey` 去重 → 产出 `ExposableWebEndpoint` / `ExposableJmxEndpoint` 并缓存。

此后 HTTP 的 HandlerMapping 和 JMX 的 Exporter 都只是这份缓存结果的"消费者"。

【源码证据】`module/spring-boot-actuator/src/main/java/org/springframework/boot/actuate/endpoint/web/annotation/WebEndpointDiscoverer.java` — 方法 `createOperation()`/`createOperationKey()`，L97-110：

```java
	@Override
	protected WebOperation createOperation(EndpointId endpointId, DiscoveredOperationMethod operationMethod,
			OperationInvoker invoker) {
		String rootPath = PathMapper.getRootPath(this.endpointPathMappers, endpointId);
		WebOperationRequestPredicate requestPredicate = this.requestPredicateFactory.getRequestPredicate(rootPath,
				operationMethod);
		return new DiscoveredWebOperation(endpointId, operationMethod, invoker, requestPredicate);
	}

	@Override
	protected OperationKey createOperationKey(WebOperation operation) {
		return new OperationKey(operation.getRequestPredicate(),
				() -> "web request predicate " + operation.getRequestPredicate());
	}
```

"请求谓词"（路径 + HTTP 方法 + 消费/产出类型）既当 Web 操作的描述，也当去重 key——两个操作若映射到同一 URL 就会触发 `assertNoDuplicateOperations` 报错。

### 6.2.3 多协议暴露：一套定义，HTTP 与 JMX 各取所需

白话：`@Endpoint` 类只负责"定义"（id + 操作），"怎么暴露"交给两条平行的装配线。回忆 Framework 篇反复出现的思路：**定义与执行分离**（HandlerMapping 不写业务、业务不关心 HTTP）。这里更进一步：端点连"某种协议"都不关心，HTTP、JMX（理论上还可以有 gRPC、邮件……）各自通过一个 Discoverer 子类把同一份定义"翻译"成自己世界的形态。新增一种暴露协议不需要动任何端点类，只需要新增一套 Discoverer + Exporter——对扩展开放、对修改关闭。

两条装配线的产品分别是 `ExposableWebEndpoint` 和 `ExposableJmxEndpoint`（都实现 `ExposableEndpoint` 接口，`module/spring-boot-actuator/.../endpoint/ExposableEndpoint.java`）。

**HTTP 线**：`WebEndpointDiscoverer`（6.2.2）产出 `ExposableWebEndpoint`；操作注解在这里被翻译成 HTTP 语义——

【源码证据】同上目录 `RequestPredicateFactory.java` — 方法 `determineHttpMethod()`，L142-150：

```java
	private WebEndpointHttpMethod determineHttpMethod(OperationType operationType) {
		if (operationType == OperationType.WRITE) {
			return WebEndpointHttpMethod.POST;
		}
		if (operationType == OperationType.DELETE) {
			return WebEndpointHttpMethod.DELETE;
		}
		return WebEndpointHttpMethod.GET;
	}
```

即 @ReadOperation→GET、@WriteOperation→POST、@DeleteOperation→DELETE。然后由 MVC 侧的 HandlerMapping 把每个操作注册成真正的 Spring MVC 映射（Boot 4 中该类在 webmvc 模块，自动配置里的 bean 名仍叫 `webEndpointServletHandlerMapping`）：

【源码证据】`module/spring-boot-webmvc/src/main/java/org/springframework/boot/webmvc/actuate/endpoint/web/AbstractWebMvcEndpointHandlerMapping.java` — 方法 `initHandlerMethods()`/`registerMappingForOperation()`，L167-193：

```java
	@Override
	protected void initHandlerMethods() {
		for (ExposableWebEndpoint endpoint : this.endpoints) {
			for (WebOperation operation : endpoint.getOperations()) {
				registerMappingForOperation(endpoint, operation);
			}
		}
		...
	}

	private void registerMappingForOperation(ExposableWebEndpoint endpoint, WebOperation operation) {
		WebOperationRequestPredicate predicate = operation.getRequestPredicate();
		String path = predicate.getPath();
		...
		registerMapping(endpoint, predicate, operation, path);
	}
```

它不是注册 @RequestMapping 注解，而是按谓词动态构造 `RequestMappingInfo`（L227-240）。这个 Mapping Bean 在 `WebMvcEndpointManagementContextConfiguration` 中创建（`module/spring-boot-webmvc/.../autoconfigure/actuate/web/WebMvcEndpointManagementContextConfiguration.java`，方法 `webEndpointServletHandlerMapping`，L86-105），并把 `order` 设为 -100（`WebMvcEndpointHandlerMapping` 构造器 L72）保证优先于业务映射。`GET /actuator` 的链接目录由 `WebMvcEndpointHandlerMapping.WebMvcLinksHandler.links()` 提供（该文件 L83-99）。

**JMX 线**：`JmxEndpointDiscoverer` 产出 `ExposableJmxEndpoint`，再由导出器注册成 MBean：

【源码证据】`module/spring-boot-actuator-autoconfigure/src/main/java/org/springframework/boot/actuate/autoconfigure/endpoint/jmx/JmxEndpointAutoConfiguration.java` — 内部配置 `JmxJacksonEndpointConfiguration.jmxMBeanExporter()`，L125-134：

```java
		@Bean
		@ConditionalOnSingleCandidate(MBeanServer.class)
		JmxEndpointExporter jmxMBeanExporter(MBeanServer mBeanServer,
				EndpointObjectNameFactory endpointObjectNameFactory, ObjectProvider<JsonMapper> jsonMapper,
				JmxEndpointsSupplier jmxEndpointsSupplier) {
			JmxOperationResponseMapper responseMapper = new JacksonJmxOperationResponseMapper(
					jsonMapper.getIfAvailable());
			return new JmxEndpointExporter(mBeanServer, endpointObjectNameFactory, responseMapper,
					jmxEndpointsSupplier.getEndpoints());
		}
```

【源码证据】`module/spring-boot-actuator/src/main/java/org/springframework/boot/actuate/endpoint/jmx/JmxEndpointExporter.java` — 方法 `afterPropertiesSet()`/`destroy()`/`register()`，L81-105：

```java
	@Override
	public void afterPropertiesSet() {
		this.registered = register();
	}

	@Override
	public void destroy() throws Exception {
		unregister(this.registered);
	}
	...
	private ObjectName register(ExposableJmxEndpoint endpoint) {
		...
		ObjectName name = this.objectNameFactory.getObjectName(endpoint);
		EndpointMBean mbean = new EndpointMBean(this.responseMapper, this.classLoader, endpoint);
		this.mBeanServer.registerMBean(mbean, name);
```

同一个 `@Endpoint`，HTTP 侧变成 URL 映射，JMX 侧变成 `EndpointMBean` 注册进 `MBeanServer`（利用了 Framework 篇的 Bean 生命周期回调 afterPropertiesSet/destroy）。JMX 整体开关是 `@ConditionalOnBooleanProperty("spring.jmx.enabled")`（JmxEndpointAutoConfiguration L70）。

### 6.2.4 暴露控制：include/exclude 过滤器

白话：上面两条线各自挂了一个"门卫"过滤器，决定哪些端点 id 放行。老资料里的 `ExposeExcludePropertyEndpointFilter` 在本版已更名为 `IncludeExcludeEndpointFilter`（Javadoc 标注 since 2.2.7）。

【源码证据】`module/spring-boot-actuator-autoconfigure/src/main/java/org/springframework/boot/actuate/autoconfigure/endpoint/web/WebEndpointAutoConfiguration.java` — 方法 `webExposeExcludePropertyEndpointFilter()`，L146-151：

```java
	@Bean
	IncludeExcludeEndpointFilter<ExposableWebEndpoint> webExposeExcludePropertyEndpointFilter() {
		WebEndpointProperties.Exposure exposure = this.properties.getExposure();
		return new IncludeExcludeEndpointFilter<>(ExposableWebEndpoint.class, exposure.getInclude(),
				exposure.getExclude(), EndpointExposure.WEB.getDefaultIncludes());
	}
```

`exposure.getInclude()/getExclude()` 就来自属性 `management.endpoints.web.exposure.include/exclude`：

【源码证据】`module/spring-boot-actuator-autoconfigure/src/main/java/org/springframework/boot/actuate/autoconfigure/endpoint/web/WebEndpointProperties.java` — 内部类 `Exposure`，L84-94：

```java
	public static class Exposure {

		/**
		 * Endpoint IDs that should be included or '*' for all.
		 */
		private Set<String> include = new LinkedHashSet<>();

		/**
		 * Endpoint IDs that should be excluded or '*' for all.
		 */
		private Set<String> exclude = new LinkedHashSet<>();
```

过滤逻辑（exclude 永远赢，include 为空则回落到 defaultIncludes=health）：

【源码证据】`module/spring-boot-actuator-autoconfigure/src/main/java/org/springframework/boot/actuate/autoconfigure/endpoint/expose/IncludeExcludeEndpointFilter.java` — 方法 `match()`/`isIncluded()`，L125-134：

```java
	public final boolean match(EndpointId endpointId) {
		return isIncluded(endpointId) && !isExcluded(endpointId);
	}

	private boolean isIncluded(EndpointId endpointId) {
		if (this.include.isEmpty()) {
			return this.defaultIncludes.matches(endpointId);
		}
		return this.include.matches(endpointId);
	}
```

这个过滤器最终在 `EndpointDiscoverer.isEndpointFiltered()`（EndpointDiscoverer L315-322）中生效。回看 6.2.1 的 `WebEndpointAutoConfiguration.webEndpointDiscoverer()`（L92-102）：它通过 `ObjectProvider<EndpointFilter<ExposableWebEndpoint>>` 收集容器里所有过滤器 Bean 传给发现器——**过滤器本身也是普通 Bean**，所以你想写自己的暴露规则，注册一个 `EndpointFilter` 就行。所以 `management.endpoints.web.exposure.include=*` 一配，所有端点立刻通过 HTTP 暴露——方便但危险，生产请用白名单。JMX 侧有对称的 `jmxIncludeExcludePropertyEndpointFilter`（JmxEndpointAutoConfiguration L104-109）。

---

## 6.3 health 聚合：一个 URL 背后的体检报告

白话：`/actuator/health` 返回的不是单个值，而是一棵"体检树"：应用整体状态 + 各组件状态（db、redis、diskSpace...）。每个组件由一个 `HealthIndicator` 贡献，最后由聚合器汇总出一个总状态，供 K8s 探针/负载均衡器决策。

**Boot 4 注意**：health 已整体迁到 `module/spring-boot-health` 模块，包名 `org.springframework.boot.health`。

【源码证据】`module/spring-boot-health/src/main/java/org/springframework/boot/health/actuate/endpoint/HealthEndpoint.java` — 类 `HealthEndpoint` 与方法 `health()`，L46-72：

```java
@Endpoint(id = "health")
public class HealthEndpoint extends HealthEndpointSupport<Health, HealthDescriptor> {
	...
	@ReadOperation
	public HealthDescriptor health() {
		HealthDescriptor health = health(ApiVersion.V3, EMPTY_PATH);
		return (health != null) ? health : IndicatedHealthDescriptor.UP;
	}
```

它本身就是普通 `@Endpoint`——再次印证"端点定义与协议无关"。指标贡献方的接口是一个函数式接口：

【源码证据】`module/spring-boot-health/src/main/java/org/springframework/boot/health/contributor/HealthIndicator.java` — 接口 `HealthIndicator`，L28-48：

```java
@FunctionalInterface
public non-sealed interface HealthIndicator extends HealthContributor {

	/**
	 * Return an indication of health.
	 * @param includeDetails if details should be included or removed
	 * @return the health
	 */
	default @Nullable Health health(boolean includeDetails) {
		Health health = health();
		if (health == null) {
			return null;
		}
		return includeDetails ? health : health.withoutDetails();
	}
```

`health()` 返回的 `Health` 携带 `Status` 与 details。四种标准状态：

【源码证据】`module/spring-boot-health/src/main/java/org/springframework/boot/health/contributor/Status.java` — 常量声明，L45-69：

```java
	public static final Status UNKNOWN = new Status("UNKNOWN");
	public static final Status UP = new Status("UP");
	public static final Status DOWN = new Status("DOWN");
	public static final Status OUT_OF_SERVICE = new Status("OUT_OF_SERVICE");
	...
	public static final List<Status> DEFAULT_ORDER = List.of(DOWN, OUT_OF_SERVICE, UP, UNKNOWN);
```

`DEFAULT_ORDER` 就是聚合的"最坏者优先"排序（DOWN 最严重）。排序逻辑实现在聚合器里（`SimpleStatusAggregator`，现为 @Deprecated，由 `StatusAggregator.of()` 工厂创建），核心是取排序最靠前的状态：

【源码证据】`module/spring-boot-health/src/main/java/org/springframework/boot/health/actuate/endpoint/SimpleStatusAggregator.java` — 方法 `getAggregateStatus()`，L72-75：

```java
	@Override
	public Status getAggregateStatus(Set<Status> statuses) {
		return statuses.stream().filter(this::contains).min(this.comparator).orElse(Status.UNKNOWN);
	}
```

聚合器 Bean 由配置生成（`management.endpoint.health.status.order` 可自定义顺序）：

【源码证据】`module/spring-boot-health/src/main/java/org/springframework/boot/health/autoconfigure/actuate/endpoint/HealthEndpointConfiguration.java` — 方法 `healthStatusAggregator()` 与 `healthEndpoint()`，L48-51、L88-92：

```java
	@Bean
	@ConditionalOnMissingBean
	StatusAggregator healthStatusAggregator(HealthEndpointProperties properties) {
		return StatusAggregator.of(properties.getStatus().getOrder());
	}
	...
	@Bean
	@ConditionalOnMissingBean
	HealthEndpoint healthEndpoint(HealthContributorRegistry healthContributorRegistry, ...
```

聚合动作发生在基类 `HealthEndpointSupport`：

【源码证据】`module/spring-boot-health/src/main/java/org/springframework/boot/health/actuate/endpoint/HealthEndpointSupport.java` — 方法 `getCompositeDescriptor()`，L199-207：

```java
	final CompositeHealthDescriptor getCompositeDescriptor(ApiVersion apiVersion,
			Map<String, HealthDescriptor> descriptors, StatusAggregator statusAggregator, boolean showComponents,
			@Nullable Set<String> groupNames) {
		Status status = statusAggregator
			.getAggregateStatus(descriptors.values().stream().map(this::getStatus).collect(Collectors.toSet()));
```

递归遍历注册表里每个 contributor，收集所有 Status 交给聚合器——只要 db 是 DOWN，整体就是 DOWN。为什么 DOWN 排在 `DEFAULT_ORDER` 第一位？因为聚合取的是"排序最靠前"的状态，把 DOWN 放最前意味着**任何一个组件挂了都会拉低整体状态**——对探针场景这是唯一安全的取向。另外整体返回的 details（各组件明细）受 `management.endpoint.health.show-details` 控制（`Show` 枚举：`NEVER/WHEN_AUTHORIZED/ALWAYS`，`module/spring-boot-actuator/.../endpoint/Show.java` L38-48），默认不显示——公网爬到 /actuator/health 只能拿到一个 `{"status":"UP"}`。顺带一个实用细节：该基类还有慢检查日志（`getDescriptorAndLogIfSlow`，L180-194，阈值 `management.endpoint.health.logging.slow-indicator-threshold`），排查"health 接口为什么慢"很有用。

**健康组**：想把"K8s 存活探针"和"完整体检"分开暴露？用健康组：

【源码证据】`module/spring-boot-health/src/main/java/org/springframework/boot/health/autoconfigure/actuate/endpoint/HealthEndpointProperties.java` — 类 `HealthEndpointProperties` 与内部类 `Group`，L37-48、L72-96：

```java
@ConfigurationProperties("management.endpoint.health")
public class HealthEndpointProperties extends HealthProperties {
	...
	private final Map<String, Group> group = new LinkedHashMap<>();
	...
	public static class Group extends HealthProperties {
		...
		/**
		 * Health indicator IDs that should be included or '*' for all.
		 */
		private @Nullable Set<String> include;
		/**
		 * Health indicator IDs that should be excluded or '*' for all.
		 */
		private @Nullable Set<String> exclude;
```

即 `management.endpoint.health.group.<名字>.include/exclude/show-details/additional-path` 会在 `/actuator/health/<名字>` 生成一个子端点，`additional-path: server:/healthz` 还能把组挂到主端口根路径（`Group.SERVER_PREFIX = "server:"`）。组机制之上 Boot 又预置了 liveness/readiness 探针（`AvailabilityProbesAutoConfiguration`，L48-55 注册 `livenessStateHealthIndicator`/`readinessStateHealthIndicator`）。

**内置 indicator**（Boot 4 已拆到各技术模块，以下是 find 证据）：`DataSourceHealthIndicator` 在 `module/spring-boot-jdbc/.../health/`；`DataRedisHealthIndicator` 在 `module/spring-boot-data-redis/.../health/`；`DiskSpaceHealthIndicator`、`PingHealthIndicator` 在 `module/spring-boot-health/.../contributor|application/`。此外 mongo/rabbit/cassandra/elasticsearch/ldap/mail 等各模块都有 `*HealthContributorAutoConfiguration`。看一个典型的"磁盘剩余空间"实现：

【源码证据】`module/spring-boot-health/src/main/java/org/springframework/boot/health/application/DiskSpaceHealthIndicator.java` — 方法 `doHealthCheck()`，L59-76：

```java
	@Override
	protected void doHealthCheck(Health.Builder builder) throws Exception {
		long diskFreeInBytes = this.path.getUsableSpace();
		if (diskFreeInBytes >= this.threshold.toBytes()) {
			builder.up();
		}
		else {
			...
			builder.down();
		}
		builder.withDetail("total", this.path.getTotalSpace())
```

**Reactive 一句话**：响应式应用实现 `ReactiveHealthIndicator`（同目录 `ReactiveHealthIndicator.java`），`health()` 返回 `Mono<Health>`，端点侧由 `ReactiveHealthContributorRegistry` + `ReactiveHealthEndpointWebExtension` 非阻塞聚合。

---

## 6.4 metrics：Micrometer 集成

白话：Micrometer 之于指标，如同 SLF4J 之于日志——你面向 `MeterRegistry`（计量注册表）写代码，底层可以是 Prometheus、Datadog 等任何后端。注册表里的每个计量项叫 `Meter`，最常用的是 `Timer`（计时/频次）和 `Counter`（累计次数），每个 Meter 都带 name 和一组 tag（标签维度，Prometheus 里就是 label）。Actuator 的 `/actuator/metrics` 端点则让指标不用接后端也能看。Boot 4 中相关自动配置在 `module/spring-boot-micrometer-metrics` 模块（包 `org.springframework.boot.micrometer.metrics`）。

入口自动配置：

【源码证据】`module/spring-boot-micrometer-metrics/src/main/java/org/springframework/boot/micrometer/metrics/autoconfigure/MetricsAutoConfiguration.java` — 类声明与 Bean，L49-74：

```java
@AutoConfiguration(before = CompositeMeterRegistryAutoConfiguration.class)
@ConditionalOnClass(Timed.class)
@EnableConfigurationProperties(MetricsProperties.class)
public final class MetricsAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	Clock micrometerClock() {
		return Clock.SYSTEM;
	}

	@Bean
	static MeterRegistryPostProcessor meterRegistryPostProcessor(ApplicationContext applicationContext, ...
```

`@ConditionalOnClass(Timed.class)` 表明 micrometer-core 在 classpath 即生效。关键角色是 `MeterRegistryPostProcessor`——一个 `BeanPostProcessor`，每个 `MeterRegistry` Bean 初始化后都会被它"调教"：

【源码证据】`module/spring-boot-micrometer-metrics/src/main/java/org/springframework/boot/micrometer/metrics/autoconfigure/MeterRegistryPostProcessor.java` — 方法 `postProcessAfterInitialization()`/`postProcessMeterRegistry()`/`applyFilters()`，L83-89、L99-109、L119-127：

```java
	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		if (bean instanceof MeterRegistry meterRegistry) {
			postProcessMeterRegistry(meterRegistry);
		}
		return bean;
	}
	...
	private void postProcessMeterRegistry(MeterRegistry meterRegistry) {
		this.meterRegistryCloser.getObject().track(meterRegistry);
		// Customizers must be applied before binders, as they may add custom tags or
		// alter timer or summary configuration.
		applyCustomizers(meterRegistry);
		applyFilters(meterRegistry);
		addToGlobalRegistryIfNecessary(meterRegistry);
		...
	}

	private void applyFilters(MeterRegistry meterRegistry) {
		if (this.filters != null) {
			Stream<MeterFilter> filters = this.filters.orderedStream();
			...
			filters.forEach(meterRegistry.config()::meterFilter);
		}
	}
```

顺序固定：`MeterRegistryCustomizer`（如加公共 tag）→ `MeterFilter`（如 `management.metrics.distribution.*` 的百分位/限流配置，含 L72 注册的 `PropertiesMeterFilter`）→ 全局注册表 → `MeterBinder`（JVM、系统等预置指标绑定）。这是 Framework 篇 BeanPostProcessor 扩展点的又一次实战。也就是说什么都不配，你的应用已经自带 `jvm.memory.used`、`system.cpu.usage`、`http.server.requests` 等一批开箱指标——它们来自各 `*MetricsAutoConfiguration` 注册的 MeterBinder（如 `JvmMetricsAutoConfiguration`、`SystemMetricsAutoConfiguration`，同模块 `autoconfigure/jvm|system/` 目录可见）。

默认用什么注册表？没有引入任何导出后端时：

【源码证据】`module/spring-boot-micrometer-metrics/src/main/java/org/springframework/boot/micrometer/metrics/autoconfigure/export/simple/SimpleMetricsExportAutoConfiguration.java` — 类声明与 Bean，L42-51：

```java
@AutoConfiguration(before = CompositeMeterRegistryAutoConfiguration.class, after = MetricsAutoConfiguration.class)
@ConditionalOnBean(Clock.class)
@EnableConfigurationProperties(SimpleProperties.class)
@ConditionalOnMissingBean(MeterRegistry.class)
@ConditionalOnEnabledMetricsExport("simple")
public final class SimpleMetricsExportAutoConfiguration {

	@Bean
	SimpleMeterRegistry simpleMeterRegistry(SimpleConfig config, Clock clock) {
		return new SimpleMeterRegistry(config, clock);
	}
```

`SimpleMeterRegistry` 只存内存、随取随查；想换 Prometheus，加 `micrometer-registry-prometheus` 依赖 + `PrometheusMetricsExportAutoConfiguration`（同模块 `export/prometheus/`，还自带 `PrometheusScrapeEndpoint`）即可，业务代码零改动——门面模式的价值。

`@Timed`/`@Counted` **由 Micrometer 提供**（`io.micrometer.core.annotation.Timed/Counted`），Boot 负责把切面装上：

【源码证据】`module/spring-boot-micrometer-metrics/src/main/java/org/springframework/boot/micrometer/metrics/autoconfigure/MetricsAspectsAutoConfiguration.java` — 类声明与 Bean `countedAspect()`，L47-60：

```java
@AutoConfiguration(after = { MetricsAutoConfiguration.class, CompositeMeterRegistryAutoConfiguration.class,
		ObservationAutoConfiguration.class })
@ConditionalOnClass({ MeterRegistry.class, Advice.class })
@ConditionalOnBooleanProperty("management.observations.annotations.enabled")
@ConditionalOnBean(MeterRegistry.class)
public final class MetricsAspectsAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	CountedAspect countedAspect(MeterRegistry registry,
```

（TimedAspect 同理，L64-69；整个类受 `management.observations.annotations.enabled` 开关控制。）AOP 复用了 Framework 篇的 @AspectJ 支持。

**端点结构**：`/actuator/metrics` 先给指标名列表；`/actuator/metrics/{指标名}?tag=key:value` 下钻给出该指标的测量值（samples）与可用标签（availableTags）：

【源码证据】`module/spring-boot-micrometer-metrics/src/main/java/org/springframework/boot/micrometer/metrics/actuate/endpoint/MetricsEndpoint.java` — 方法 `listNames()` 与 `metric()`，L50-64、L79-92：

```java
@Endpoint(id = "metrics")
public class MetricsEndpoint {

	private final MeterRegistry registry;
	...
	@ReadOperation
	public MetricNamesDescriptor listNames() {
		Set<String> names = new TreeSet<>();
		collectNames(names, this.registry);
		return new MetricNamesDescriptor(names);
	}
	...
	@ReadOperation
	public @Nullable MetricDescriptor metric(@Selector String requiredMetricName, @Nullable List<String> tag) {
```

`@Selector` 参数变成 URL 路径变量（`/actuator/metrics/jvm.memory.used?tag=area:heap`）。

---

## 6.5 可观测（Observability）：Observation API 统一 metrics 与 tracing

白话：过去一次请求要埋两套代码——计时器（metrics）和 span（tracing）。Micrometer 的 **Observation（观察）** 把两者合并：开启一个 Observation，停止时它同时产出一张 timer 记录（进 MeterRegistry）和一个 span（进 tracing 桥接器）。API 在 `micrometer-observation` 包里，Boot 负责装配。

【源码证据】`module/spring-boot-micrometer-observation/src/main/java/org/springframework/boot/micrometer/observation/autoconfigure/ObservationAutoConfiguration.java` — 类与 Bean，L50-77：

```java
@AutoConfiguration
@ConditionalOnClass(ObservationRegistry.class)
@EnableConfigurationProperties(ObservationProperties.class)
public final class ObservationAutoConfiguration {

	@Bean
	static ObservationRegistryPostProcessor observationRegistryPostProcessor(...
	...
	@Bean
	@ConditionalOnMissingBean
	ObservationRegistry observationRegistry() {
		return ObservationRegistry.create();
	}
```

`ObservationRegistry`（观察注册表，业务代码 `registry.observation().name("x").lowCardinalityKeyValue(...).start()...stop()` 全链路操作它）由 Boot 自动提供；`ObservationRegistryPostProcessor`（同目录，`postProcessAfterInitialization` L68-72）把容器里所有 `ObservationPredicate`/`GlobalObservationConvention`/`ObservationHandler`/`ObservationFilter` 装配进注册表——又是 BeanPostProcessor。两条产品线在此汇合：`MetricsAutoConfiguration` 注册的 `DefaultMeterObservationHandler`（MetricsAutoConfiguration L87-93）把每个 Observation 变成 metrics；引入 `micrometer-tracing` 及其桥接（OTel/Brave）后，tracing 的 handler 自动接手产生 span。而且这一切是全栈贯通的：Framework 的 HTTP 请求处理链、RestClient 等基础设施内置了 Observation 仪器化，所以你**一行代码不写**，每个请求就有了 timer 和 trace span。`ObservationThreadLocalAccessor`（Micrometer 提供，非 Boot 源码）一句话：它把"当前 Observation"存取于 ThreadLocal，使任意深层代码能 `Observation.current()` 取回上下文，保证一次请求从头到尾记进同一个 trace/timer。`management.observations.*` 属性（`ObservationProperties`，`@ConfigurationProperties("management.observations")` L33）控制启用范围；`@Observed` 注解切面由 `ObservedAspectConfiguration` 提供（同文件 L85-106），方法上加 `@Observed(name = "...")` 即可产出指标与 span。

---

## 6.6 常用端点速览

| id | 端点类（相对 `module/`） | 作用 |
|---|---|---|
| health | `spring-boot-health/.../health/actuate/endpoint/HealthEndpoint.java` | 健康聚合，默认唯一暴露 |
| info | `spring-boot-actuator/.../actuate/info/InfoEndpoint.java` | 应用信息（git/构建信息等） |
| beans | `spring-boot-actuator/.../actuate/beans/BeansEndpoint.java` | 容器内全部 Bean 及依赖关系 |
| conditions | `spring-boot-actuator-autoconfigure/.../autoconfigure/condition/ConditionsReportEndpoint.java` | 自动配置评估报告（哪些条件命中） |
| configprops | `spring-boot-actuator/.../actuate/context/properties/ConfigurationPropertiesReportEndpoint.java` | 全部 @ConfigurationProperties（含脱敏） |
| env | `spring-boot-actuator/.../actuate/env/EnvironmentEndpoint.java` | 环境属性（含脱敏） |
| mappings | `spring-boot-actuator/.../actuate/web/mappings/MappingsEndpoint.java` | 全部 HTTP 映射 |
| metrics | `spring-boot-micrometer-metrics/.../actuate/endpoint/MetricsEndpoint.java` | 指标查询 |
| loggers | `spring-boot-actuator/.../actuate/logging/LoggersEndpoint.java` | 查看/在线修改日志级别 |
| threaddump | `spring-boot-actuator/.../actuate/management/ThreadDumpEndpoint.java` | 线程转储（查死锁/卡顿） |
| shutdown | `spring-boot-actuator/.../actuate/context/ShutdownEndpoint.java` | 优雅关闭（默认禁用） |

shutdown 值得一提——它体现了新的 `defaultAccess` 设计：

【源码证据】`module/spring-boot-actuator/src/main/java/org/springframework/boot/actuate/context/ShutdownEndpoint.java` — 类与方法，L39-57：

```java
@Endpoint(id = "shutdown", defaultAccess = Access.NONE)
public class ShutdownEndpoint implements ApplicationContextAware {

	private @Nullable ConfigurableApplicationContext context;

	@WriteOperation
	public ShutdownDescriptor shutdown() {
		...
		finally {
			Thread thread = new Thread(this::performShutdown);
```

即使你 `include=shutdown` 暴露它，`Access.NONE` 也会让操作过滤器拦下调用——想真用还得配 `management.endpoint.shutdown.access=unrestricted`（`PropertiesEndpointAccessResolver` 读取），双重保险。

---

## 6.7 安全实践（一句话）

生产三件套：`management.server.port` 指定独立管理端口（与业务流量隔离，健康组还能用 `additional-path` 把探针单独映射回主端口）；`management.endpoints.web.exposure.include` 按白名单最小暴露；再叠加 Spring Security 对 `/actuator/**` 做认证授权（配合 `management.endpoint.health.show-details=when-authorized` 与 `roles` 属性控制详情可见性）。

---

## 6.8 自定义端点最小示例

白话：写运维端点不需要注册 HandlerMapping，一个 `@Endpoint` Bean 搞定——并且自动获得 JMX 暴露、include/exclude 过滤和 links 目录收录（若用 `@WebEndpoint` 则只上 HTTP、不进 JMX）。

```java
@Component
@Endpoint(id = "features")                       // -> /actuator/features
public class FeaturesEndpoint {

    private final FeatureFlags flags;

    public FeaturesEndpoint(FeatureFlags flags) {
        this.flags = flags;
    }

    @ReadOperation                               // GET /actuator/features
    public Map<String, Boolean> features() {
        return flags.snapshot();
    }

    @WriteOperation                              // POST /actuator/features/{name}?enabled=true
    public Map<String, Boolean> enable(@Selector String name, boolean enabled) {
        flags.set(name, enabled);
        return flags.snapshot();
    }
}
```

之后 `management.endpoints.web.exposure.include=health,features` 即可放行——注意你**不需要**写任何 URL 映射代码，`WebEndpointDiscoverer` 会把方法翻译成谓词，`WebMvcEndpointHandlerMapping` 负责注册；同一个类在 JMX 世界里自动变成 `Features` MBean。这就是本章反复出现的架构主张：**端点定义与暴露协议彻底解耦**。

再进一步：如果你的某个操作只对 HTTP 有意义（比如返回 SSE 流），不必污染通用端点——写一个 `@EndpointExtension(endpoint = FeaturesEndpoint.class, filter = WebEndpointFilter.class)` 的扩展类（注解在 `module/spring-boot-actuator/.../endpoint/annotation/EndpointExtension.java`），`EndpointDiscoverer.addExtensionBeans()`（L171-183）会把它挂到原端点上，仅在 Web 发现时替换同名操作。

至此，Actuator 的完整图景可以收拢成一句话：**@Endpoint 定义"能查什么"，EndpointDiscoverer 负责"找到它们"，HandlerMapping/JmxEndpointExporter 决定"从哪里访问"，IncludeExcludeEndpointFilter 决定"谁能访问"，而 health/metrics/observation 三件套则把应用从"能跑"推向"可观测"。**


---

# 七、部署与开发者工具链

前面章节讲的都是"运行中的 Spring"（容器、自动配置、AOP）。本章讲"代码写完之后"的一切：怎么把项目变成一个 `java -jar` 就能跑的 fat jar、怎么让 IDE 改完代码秒级重启（devtools）、Maven/Gradle 插件做了什么、176 个 starter 是什么、以及测试时 `@SpringBootTest` 如何把 Boot 和 Framework 的测试体系接在一起。

先给一张 4.2 源码根目录地图（均为实测目录）：

```
loader/          可执行 jar 的加载器三件套：spring-boot-loader（运行时）、
                 spring-boot-loader-tools（打包时）、spring-boot-jarmode-tools（jarmode 工具）
build-plugin/    spring-boot-maven-plugin、spring-boot-gradle-plugin、spring-boot-antlib
module/          各功能模块，含本章核心 B：spring-boot-devtools
starter/         176 个 starter（实测 `ls starter | wc -l` = 176）
core/            spring-boot、spring-boot-autoconfigure、spring-boot-test、spring-boot-test-autoconfigure
cli/             Spring Boot CLI（命令行脚手架）
smoke-test/      官方示例应用群
system-test/     部署/镜像级系统测试
```

---

## 7.1 可执行 jar：为什么 `java -jar app.jar` 一个文件就能跑（核心 A）

**白话**：普通 jar 是"一套类的容器"，JVM 的标准 ClassLoader 只会读它最外层的 zip 结构，不可能钻进 jar 里面再打开另一个 jar。而 Spring Boot 应用要带上几十上百个依赖 jar，于是 Boot 自带了一个"启动器"（JarLauncher）+ 一个"会读嵌套 jar 的 ClassLoader"（LaunchedClassLoader），先由启动器把 classpath 拼好，再反射调用你自己的 main 方法。

### 7.1.1 fat jar 的结构

一个 Boot fat jar 长这样：

```
app.jar
├── META-INF/MANIFEST.MF      Main-Class: ...loader.launch.JarLauncher
│                             Start-Class:  com.example.MyApplication   ← 你的业务类
├── BOOT-INF/
│   ├── classes/              你自己的 .class
│   ├── lib/*.jar             所有第三方依赖（嵌套 jar）
│   └── classpath.idx         classpath 顺序索引
└── org/springframework/boot/loader/...   加载器自己的类（放最外层，JVM 才能直接读到）
```

这些常量在 loader-tools 的 `Layouts.Jar` 里逐一写死（`Packager` 负责写 MANIFEST）：

【源码证据】loader/spring-boot-loader-tools/src/main/java/org/springframework/boot/loader/tools/Layouts.java · Layouts.Jar · getLauncherClassName()/getRepackagedClassesLocation() · L67~86

```java
public static class Jar implements RepackagingLayout {
    @Override
    public @Nullable String getLauncherClassName() {
        return "org.springframework.boot.loader.launch.JarLauncher";
    }
    @Override
    public String getLibraryLocation(String libraryName, @Nullable LibraryScope scope) {
        return "BOOT-INF/lib/";
    }
    @Override
    public String getRepackagedClassesLocation() {
        return "BOOT-INF/classes/";
    }
```

Main-Class 与 Start-Class 的分工在 `Packager.buildManifest`：Main-Class 填加载器，Start-Class 填你的业务主类——这就是 4.x 的包名（`org.springframework.boot.loader.launch.JarLauncher`，3.2 起从 `loader.JarLauncher` 迁来）：

【源码证据】loader/spring-boot-loader-tools/src/main/java/org/springframework/boot/loader/tools/Packager.java · addMainAndStartAttributes() · L326~337

```java
private void addMainAndStartAttributes(JarFile source, Manifest manifest) throws IOException {
    String mainClass = getMainClass(source, manifest);
    String launcherClass = getLayout().getLauncherClassName();
    if (launcherClass != null) {
        Assert.state(mainClass != null, "Unable to find main class");
        manifest.getMainAttributes().putValue(MAIN_CLASS_ATTRIBUTE, launcherClass);   // Main-Class
        manifest.getMainAttributes().putValue(START_CLASS_ATTRIBUTE, mainClass);      // Start-Class
    }
```

（L66~68 就是 `MAIN_CLASS_ATTRIBUTE = "Main-Class"`、`START_CLASS_ATTRIBUTE = "Start-Class"` 两个常量。）

### 7.1.2 JarLauncher 启动链

完整启动链如下（每一行都对应下面的源码证据）：

```
$ java -jar app.jar
        │
        ▼  JVM 只认识 MANIFEST 里的 Main-Class
JarLauncher.main()                        JarLauncher.java L39~41
        │  new JarLauncher().launch(args)
        ▼
Launcher.launch(args)                     Launcher.java L56~69
        ├─ Handlers.register()          注册自定义 "nested:" URL 协议
        ├─ getClassPathUrls()           遍历归档条目：BOOT-INF/classes/ + BOOT-INF/lib/*.jar
        │                                （Launcher.isLibraryFileOrClassesDirectory L183~189）
        ├─ createClassLoader(urls)      → new LaunchedClassLoader(isExploded, archive, urls, parent)
        │                                                  Launcher.java L85~88
        ├─ getMainClass()               读 MANIFEST 的 "Start-Class" 属性
        │                                ExecutableArchiveLauncher.java L66~73
        └─ launch(classLoader, mainClassName, args)          Launcher.java L97~108
               ├─ Thread.currentThread().setContextClassLoader(classLoader)
               ├─ Class.forName(mainClassName, false, classLoader)
               └─ mainMethod.invoke(null, args)   ← 你的 @SpringBootApplication.main 启动
```

三个关键方法的源码：

【源码证据】loader/spring-boot-loader/src/main/java/org/springframework/boot/loader/launch/JarLauncher.java · main() · L39~41

```java
public static void main(String[] args) throws Exception {
    new JarLauncher().launch(args);
}
```

【源码证据】loader/spring-boot-loader/src/main/java/org/springframework/boot/loader/launch/ExecutableArchiveLauncher.java · getMainClass() · L66~73

```java
@Override
protected String getMainClass() throws Exception {
    Manifest manifest = this.archive.getManifest();
    String mainClass = (manifest != null) ? manifest.getMainAttributes().getValue(START_CLASS_ATTRIBUTE) : null;
    if (mainClass == null) {
        throw new IllegalStateException("No 'Start-Class' manifest entry specified in " + this);
    }
    return mainClass;
}
```

【源码证据】loader/spring-boot-loader/src/main/java/org/springframework/boot/loader/launch/Launcher.java · launch(ClassLoader, String, String[]) · L97~108

```java
protected void launch(ClassLoader classLoader, String mainClassName, String[] args) throws Exception {
    Thread.currentThread().setContextClassLoader(classLoader);
    Class<?> mainClass = Class.forName(mainClassName, false, classLoader);
    Method mainMethod = getMainMethod(mainClass);
    mainMethod.setAccessible(true);
    if (mainMethod.getParameterCount() == 0) {
        mainMethod.invoke(null);
    }
    else {
        mainMethod.invoke(null, new Object[] { args });
    }
}
```

classpath 条目的筛选规则（哪些条目进 classpath）也在这里：目录只认 `BOOT-INF/classes/`，文件只认 `BOOT-INF/lib/` 前缀：

【源码证据】loader/spring-boot-loader/src/main/java/org/springframework/boot/loader/launch/Launcher.java · isLibraryFileOrClassesDirectory() · L183~189

```java
protected boolean isLibraryFileOrClassesDirectory(Archive.Entry entry) {
    String name = entry.name();
    if (entry.isDirectory()) {
        return name.equals("BOOT-INF/classes/");
    }
    return name.startsWith("BOOT-INF/lib/");
}
```

### 7.1.3 嵌套 jar 是怎么被读出来的

`LaunchedClassLoader` 继承 `JarUrlClassLoader`，每个依赖 jar 都以 `nested:` 协议 URL 表示，形如 `nested:/home/app/my.jar/!BOOT-INF/lib/foo.jar`。解析这个 URL 的语法约定在 `NestedLocation`：

【源码证据】loader/spring-boot-loader/src/main/java/org/springframework/boot/loader/net/protocol/nested/NestedLocation.java · javadoc + record 声明 · L38~54

```java
 * {@code nested:/home/example/my.jar/!BOOT-INF/lib/my-nested.jar}
 * ...
public record NestedLocation(Path path, String nestedEntryName) {
```

真正"从 jar 里再打开一个 jar"的活由 `ZipContent` 干：它先按普通 zip 打开外层 jar、解析中央目录（EOCD → Zip64 → 中央目录条目），找到嵌套条目后直接把该条目的字节区间当作新 zip 的数据块，再在区间上解析内层中央目录——全程不解压、不落盘：

【源码证据】loader/spring-boot-loader/src/main/java/org/springframework/boot/loader/zip/ZipContent.java · Loader.load()/loadNestedZip() · L545~570

```java
static ZipContent load(Source source) throws IOException {
    if (!source.isNested()) {
        return loadNonNested(source);
    }
    try (ZipContent zip = open(source.path())) {
        Entry entry = zip.getEntry(source.nestedEntryName());
        ...
    }
}
private static ZipContent loadNestedZip(Source source, Entry entry) throws IOException {
    if (entry.centralRecord.compressionMethod() != ZipEntry.STORED) {
        throw new IOException("Nested entry '%s' in container zip '%s' must not be compressed"
```

注意 L565~568 的硬性要求：**嵌套 jar 必须以 STORED（不压缩）方式存进外层 jar**——只有这样它内部的偏移量才与独立文件一致，才能"零拷贝"地引用一段字节区间当 zip 读。这正是构建插件打包时对依赖 jar 关闭压缩的原因。

### 7.1.4 为什么标准 ClassLoader 加载不了嵌套 jar

一句话：JDK 的 `URLClassLoader` 只认 `jar:file:...!/...` 这种"先解压到临时文件再读"的协议语义，且标准 `jar:` Handler 无法在"一个 zip 的某段字节区间"上再开一层 zip；Boot 通过注册自己的 `nested:` 协议 Handler（`Launcher.launch` 里第一步 `Handlers.register()`，L57~59）+ 自研的 `LaunchedClassLoader`，才让"jar 中 jar"成为合法 classpath 节点。这也是 fat jar 诞生时 Boot 必须自带加载器的根本原因。

### 7.1.5 loader-tools 与 jarmode-tools

- **spring-boot-loader-tools**：打包用的类库（Repackager/Packager/JarWriter/Layouts），构建插件是它唯一的调用方。
- **spring-boot-jarmode-tools**：`java -Djarmode=tools -jar app.jar extract ...` 的运行时工具。Launcher 检测到 `jarmode` 系统属性后不再启动业务类，而是转入 `JarModeRunner`：

【源码证据】loader/spring-boot-loader/src/main/java/org/springframework/boot/loader/launch/Launcher.java · launch(String[]) · L62~63

```java
String jarMode = System.getProperty("jarmode");
String mainClassName = hasLength(jarMode) ? JAR_MODE_RUNNER_CLASS_NAME : getMainClass();
```

它提供 extract（把 jar 解成目录）、extract --layers（按 layers.idx 分层导出，供 Dockerfile 分层 COPY 加速镜像构建——旧的 layertools 模式已并入，见 `ExtractLayersCommand` L57 提示"Use '-Djarmode=tools extract --layers --launcher' instead"）、sbom 三个命令：

【源码证据】loader/spring-boot-jarmode-tools/src/main/java/org/springframework/boot/jarmode/tools/ToolsJarMode.java · getCommands() · L65~68

```java
static List<Command> getCommands(Context context) {
    return List.of(new ExtractCommand(context), new ListLayersCommand(context), new SbomCommand(context));
}
```

---

## 7.2 构建插件：build-plugin/

**白话**：插件就是"打包流水线机器人"。Maven 侧 `spring-boot:repackage` 在 `package` 阶段把普通 jar 改造成 fat jar；Gradle 侧提供 `bootJar`/`bootRun` 任务。

Maven 的 repackage 绑定在 `package` 生命周期上：

【源码证据】build-plugin/spring-boot-maven-plugin/src/main/java/org/springframework/boot/maven/RepackageMojo.java · execute()/repackage() · L55 + L187~202

```java
@Mojo(name = "repackage", defaultPhase = LifecyclePhase.PACKAGE, requiresProject = true, threadSafe = true, ...)
public class RepackageMojo extends AbstractPackagerMojo {
...
private void repackage() throws MojoExecutionException {
    Artifact source = getSourceArtifact(this.classifier);
    File target = getTargetFile(this.finalName, this.classifier, this.outputDirectory);
    ...
    Repackager repackager = getRepackager(source.getFile());
    Libraries libraries = getLibraries(this.requiresUnpack);
    try {
        repackager.repackage(target, libraries, parseOutputTimestamp());
    }
    ...
    updateArtifact(source, target, repackager.getBackupFile());
```

"原始 jar 保留为 `.jar.original`"的实现链：`Repackager.repackage` 发现源和目标是同一个文件时，先把源改名为 backup，再写目标：

【源码证据】loader/spring-boot-loader-tools/src/main/java/org/springframework/boot/loader/tools/Repackager.java · repackage(File, Libraries, FileTime) · L119~124 + Packager.java · getBackupFile() · L371~375

```java
File workingSource = source;
if (source.equals(destination)) {
    workingSource = getBackupFile();
    workingSource.delete();
    renameFile(source, workingSource);        // app.jar → app.jar.original
}
// Packager:
public final File getBackupFile() {
    ...
    return new File(this.source.getParentFile(), this.source.getName() + ".original");
}
```

Gradle 侧对应物是 `bootJar` 任务（同样写 Start-Class）与 `bootRun` 任务；插件把 `bootJar` 注册为构建产物：

【源码证据】build-plugin/spring-boot-gradle-plugin/src/main/java/org/springframework/boot/gradle/plugin/JavaPluginAction.java · configureBootJarTask() · L177~183

```java
return project.getTasks().register(SpringBootPlugin.BOOT_JAR_TASK_NAME, BootJar.class, (bootJar) -> {
    ...
    bootJar.classpath(classpath);
    ...
            .provider(() -> (String) bootJar.getManifest().getAttributes().get("Start-Class"));
```

一句话记忆：`spring-boot:run`/`bootRun` = 跳过打包直接起一个"开发版"进程跑 main；`repackage`/`bootJar` = 生产 fat jar。

---

## 7.3 spring-boot-devtools：为什么重启能"秒级"（核心 B）

**白话**：改一行代码 → 编译 → devtools 检测到 classpath 变化 → **不退出 JVM**，只把"你写的类"扔掉重建，第三方 jar 原封不动。诀窍是两个类加载器分区：

- **base 区**：AppClassLoader 装所有第三方 jar、框架类——永不销毁；
- **restart 区**：`RestartClassLoader` 只装你项目的输出目录（目录才可变，jar 不会变）——每次重启整个换新。

restart 区的类加载器定义（注意 javadoc："parent last"，即你项目的类优先自己加载，不被 base 区的同名类劫持）：

【源码证据】module/spring-boot-devtools/src/main/java/org/springframework/boot/devtools/restart/classloader/RestartClassLoader.java · javadoc + 类声明 · L32~42

```java
/**
 * Disposable {@link ClassLoader} used to support application restarting. Provides parent
 * last loading for the specified URLs.
 */
public class RestartClassLoader extends URLClassLoader implements SmartClassLoader {

    private final ClassLoaderFileRepository updatedFiles;
```

"restart 区只装可变目录"在初始化时就被过滤死：只有 `file:` 开头且以 `/` 结尾的**目录** URL 才算"可变"（jar 一律排除）：

【源码证据】module/spring-boot-devtools/src/main/java/org/springframework/boot/devtools/restart/ChangeableUrls.java · 构造器/isDirectoryUrl() · L56~72

```java
private ChangeableUrls(URL... urls) {
    DevToolsSettings settings = DevToolsSettings.get();
    List<URL> reloadableUrls = new ArrayList<>(urls.length);
    for (URL url : urls) {
        if ((settings.isRestartInclude(url) || isDirectoryUrl(url.toString())) && !settings.isRestartExclude(url)) {
            reloadableUrls.add(url);
        }
    }
...
private boolean isDirectoryUrl(String urlString) {
    return urlString.startsWith("file:") && urlString.endsWith("/");
}
```

重启的完整流程：`restart()` → `stop()`（关掉所有 ApplicationContext、清反射缓存、System.gc）→ `doStart()`（**new 一个全新的 RestartClassLoader**，parent 仍是原来的 AppClassLoader，所以第三方类一个都没丢）→ `relaunch()`（新线程里重新调 main）。类加载器一换，容器里旧类对象自然作废，等于"软重启"：

【源码证据】module/spring-boot-devtools/src/main/java/org/springframework/boot/devtools/restart/Restarter.java · restart()/doStart()/stop() · L244~284, L305~323

```java
public void restart(FailureHandler failureHandler) {
    if (!this.enabled) { ... return; }
    getLeakSafeThread().call(() -> {
        Restarter.this.stop();
        Restarter.this.start(failureHandler);
        return new Object();
    });
}
...
private @Nullable Throwable doStart() throws Exception {
    Assert.state(this.mainClassName != null, "Unable to find the main class to restart");
    URL[] urls = this.urls.toArray(new URL[0]);
    ClassLoaderFiles updatedFiles = new ClassLoaderFiles(this.classLoaderFiles);
    ClassLoader classLoader = new RestartClassLoader(this.applicationClassLoader, urls, updatedFiles);
```

这就是"重启只丢 restart 区所以快"的全部秘密：昂贵的第三方类（Spring、Tomcat、Jackson……）在 base 区缓存着不动，只有你的几百个类重新 define。

**文件触发条件**：`FileSystemWatcher` 轮询输出目录（默认 pollInterval/quietPeriod 见 DevToolsProperties），且默认排除一堆"改了也不用重启"的东西：

【源码证据】module/spring-boot-devtools/src/main/java/org/springframework/boot/devtools/autoconfigure/DevToolsProperties.java · Restart.DEFAULT_RESTART_EXCLUDES · L65~67

```java
private static final String DEFAULT_RESTART_EXCLUDES = "META-INF/maven/**,"
        + "META-INF/resources/**,resources/**,static/**,public/**,templates/**,"
        + "**/*Test.class,**/*Tests.class,git.properties,META-INF/build-info.properties";
```

（静态资源改了只会触发浏览器刷新、不重启应用；可用 `spring.devtools.restart.exclude` 追加。还支持 `spring.devtools.restart.trigger-file` 触发文件模式，见 `LocalDevToolsAutoConfiguration.newFileSystemWatcher` L147~154。）

**LiveReload**：`LocalDevToolsAutoConfiguration` 默认起一个端口 35729 的 `LiveReloadServer`，classpath 变化 → `ClassPathChangedEvent` → `OptionalLiveReloadServer` 向浏览器推送刷新：

【源码证据】module/spring-boot-devtools/src/main/java/org/springframework/boot/devtools/autoconfigure/LocalDevToolsAutoConfiguration.java · LiveReloadConfiguration · L72~92

```java
@ConditionalOnBooleanProperty(name = "spring.devtools.livereload.enabled")
static class LiveReloadConfiguration {
    ...
    @ConditionalOnMissingBean
    org.springframework.boot.devtools.livereload.LiveReloadServer liveReloadServer(DevToolsProperties properties) {
        return new org.springframework.boot.devtools.livereload.LiveReloadServer(...);
```

**生产禁用**：devtools 靠"自我感知"退出——打 fat jar 时构建插件默认根本不把 devtools 打进去；即使被带上（repackage 时被当作普通依赖处理也带不进 BOOT-INF/lib），运行时还有双保险：`spring.devtools.restart.enabled=false` 直接关掉 RestartConfiguration（L102 `@ConditionalOnBooleanProperty(..., matchIfMissing = true)`），以及 `DevToolsEnablementDeducer.shouldEnable` 检测 main 线程类加载器必须是 `AppClassLoader`（开发模式）否则整体不初始化：

【源码证据】module/spring-boot-devtools/src/main/java/org/springframework/boot/devtools/system/DevToolsEnablementDeducer.java · shouldEnable() · L56~66

```java
public static boolean shouldEnable(Thread thread) {
    if (NativeDetector.inNativeImage()) {
        return false;
    }
    for (StackTraceElement element : thread.getStackTrace()) {
        if (isSkippedStackElement(element)) {
            return false;
        }
    }
    return true;
}
```

---

## 7.4 starter 机制：176 个"依赖聚合器"

**白话**：starter 就是"一张购物清单"——只聚合依赖，不含任何代码。实测：`ls starter | wc -l` = **176**（含大量 `-test` 后缀切片 starter，这是 4.x 的新家族）。

抽最核心的 `spring-boot-starter` 看构成：目录里**只有一个 build.gradle，没有 src**（实测 176 个 starter 中含 `src` 目录的为 **0** 个）：

【源码证据】starter/spring-boot-starter/build.gradle · L17~30

```gradle
plugins {
    id "org.springframework.boot.starter"
}

description = "Core starter, including auto-configuration support, logging and YAML"

dependencies {
    api(project(":starter:spring-boot-starter-logging"))
    api(project(":core:spring-boot-autoconfigure"))
    api("jakarta.annotation:jakarta.annotation-api")
    api("org.yaml:snakeyaml")
}
```

4.x 的一个变化：`spring-boot-starter-web` 已标记废弃，改推 `spring-boot-starter-webmvc`：

【源码证据】starter/spring-boot-starter-web/build.gradle · description · L21~28

```gradle
description = "Starter for building web, including RESTful, applications using Spring MVC. Uses Tomcat as the default embedded container (deprecated in favor of spring-boot-starter-webmvc)"

dependencies {
    api(project(":starter:spring-boot-starter-jackson"))
    api(project(":starter:spring-boot-starter-tomcat"))
    api(project(":module:spring-boot-http-converter"))
    api(project(":module:spring-boot-webmvc"))
}
```

**命名规范**（官方约定）：`spring-boot-starter-*` 是官方"全家桶"式 starter（如 spring-boot-starter、spring-boot-starter-webmvc）；`spring-boot-starter-xxx-技术` 形态多为细分技术定位（如 spring-boot-starter-data-jpa）；第三方 starter 命名必须**不要**以 `spring-boot` 开头，推荐 `xxx-spring-boot-starter`（如 mybatis-spring-boot-starter）——这条约定连 starter 模块的 README 都在警告：

【源码证据】starter/README.adoc · L18~21

```
WARNING: While the [reference documentation] mentions that 3rd party starters should not
start with `spring-boot`, some starters do as they were designed before this was clarified.
```

官方 starter 由 Boot 项目同步维护（版本、兼容性、自动配置模块一起升级）；第三方 starter 只是"依赖清单 + 自己写的 autoconfigure jar"，能力与升级节奏自负。

---

## 7.5 测试支持：两套框架如何咬合（核心 C）

### 7.5.1 @SpringBootTest：Boot 借道 Framework 的 TestContext 体系

**白话**：Spring Framework 有一套成熟的测试机器（TestContext framework：`@ContextConfiguration`、Context 缓存、Bootstrapper）。Boot 没有另起炉灶，而是插进去两个类：`SpringBootTestContextBootstrapper`（决定加载器、自动找主配置类）+ `SpringBootContextLoader`（把"启动一个上下文"翻译成"跑一个 SpringApplication"）。

`@SpringBootTest` 的注解声明直接指定了 Bootstrapper：

【源码证据】core/spring-boot-test/src/main/java/org/springframework/boot/test/context/SpringBootTest.java · L74~76

```java
@BootstrapWith(SpringBootTestContextBootstrapper.class)
@ExtendWith(SpringExtension.class)
public @interface SpringBootTest {
```

Bootstrapper 的核心增值：测试类上什么都不写时，**从测试类所在包向上搜索** `@SpringBootConfiguration`（即你的启动类）当作配置：

【源码证据】core/spring-boot-test/src/main/java/org/springframework/boot/test/context/SpringBootTestContextBootstrapper.java · getOrFindConfigurationClasses()/findConfigurationClass() · L205~229

```java
protected Class<?>[] getOrFindConfigurationClasses(MergedContextConfiguration mergedConfig) {
    Class<?>[] classes = mergedConfig.getClasses();
    if (containsNonTestComponent(classes) || mergedConfig.hasLocations()) {
        return classes;
    }
    Class<?> found = findConfigurationClass(mergedConfig.getTestClass());
    logger.info("Found @SpringBootConfiguration " + found.getName() + " for test " + ...);
    return merge(found, classes);
}
...
Class<?> found = new AnnotatedClassFinder(SpringBootConfiguration.class).findFromClass(testClass);
```

然后是两套框架的"衔接点"本体——ContextLoader 的 `loadContext` 里直接 `new SpringApplication().run()`：

【源码证据】core/spring-boot-test/src/main/java/org/springframework/boot/test/context/SpringBootContextLoader.java · loadContext() · L152~156

```java
SpringApplication application = getSpringApplication();
configure(mergedConfig, application);
ContextLoaderHook hook = new ContextLoaderHook(mode, initializer, ALREADY_CONFIGURED);
return hook.run(() -> application.run(args));
```

也就是说：测试里的"容器"与你生产里 `main` 起的容器是同一个东西（同一个 SpringApplication 流水线，含全套自动配置），只是环境被定制过（webEnvironment=MOCK 时用 Mock Servlet 环境，L88~91 javadoc）。

### 7.5.2 @MockitoBean：@MockBean 已经不在了（4.x 实测）

3.4 起 `@MockBean` 废弃、替代品是 `@MockitoBean`；到 4.x 这一步走完的方式出乎意料——**不是"移到 Boot 新包"，而是彻底上交给了 Spring Framework**（spring-test 6.2 的 bean override 机制）。实测：Boot 源码里 `MockitoBean.java`/`MockBean.java` 的定义文件已不存在（`find -name "MockitoBean.java"` 零命中），Boot 自己的文档测试代码 import 的是 Framework 的包：

【源码证据】documentation/spring-boot-actuator-docs/src/test/java/org/springframework/boot/actuate/docs/audit/AuditEventsEndpointDocumentationTests.java · L32

```java
import org.springframework.test.context.bean.override.mockito.MockitoBean;
```

所以 4.x 记忆法：`@MockitoBean`/`@MockitoSpyBean` 属于 `org.springframework.test.context.bean.override.mockito.*`（Framework 提供，Boot 与非 Boot 项目通用），Boot 侧只保留 `spring-boot-test-autoconfigure` 里的场景化自动配置。

### 7.5.3 测试切片：TypeExcludeFilter 在 @ComponentScan 阶段"裁树"

**白话**：`@WebMvcTest(MyController.class)` 之类切片测试的难点是——你的启动类上有个大 `@ComponentScan`，会把整个项目的 bean 都捞进来。Boot 的做法是在 `@SpringBootApplication` 的组件扫描里永久挂一个自定义 Filter：

【源码证据】core/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/SpringBootApplication.java · L55~56

```java
@EnableAutoConfiguration
@ComponentScan(excludeFilters = { @Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
```

`TypeExcludeFilter` 自身不排除任何东西，它把工作委派给容器里注册的所有子类 Filter（测试切片时由 `ExcludeFilterContextCustomizer` 注册测试专用的 `TestTypeExcludeFilter`）：

【源码证据】core/spring-boot/src/main/java/org/springframework/boot/context/TypeExcludeFilter.java · match() · L65~75 + core/spring-boot-test/.../filter/ExcludeFilterContextCustomizer.java · L30~36

```java
@Override
public boolean match(MetadataReader metadataReader, MetadataReaderFactory metadataReaderFactory)
        throws IOException {
    if (this.beanFactory instanceof ListableBeanFactory && getClass() == TypeExcludeFilter.class) {
        for (TypeExcludeFilter delegate : getDelegates()) {
            if (delegate.match(metadataReader, metadataReaderFactory)) {
                return true;
            }
        }
    }
    return false;
}
// ExcludeFilterContextCustomizer:
public void customizeContext(...) {
    TestTypeExcludeFilter.registerWith(context.getBeanFactory());
}
```

以 `@WebMvcTest` 为例：注解上挂 `@TypeExcludeFilters(WebMvcTypeExcludeFilter.class)`（WebMvcTest.java L104），该 Filter 的规则是"只放行 Controller/ControllerAdvice/Filter/Converter 等 MVC 必需品 + 你指定的 Controller，其余全排除"。排除语义的数学表达是"不在白名单里就 match（被排除）"：

【源码证据】core/spring-boot-test/src/main/java/org/springframework/boot/test/context/filter/annotation/AnnotationCustomizableTypeExcludeFilter.java · match() · L55~60

```java
@Override
public boolean match(MetadataReader metadataReader, MetadataReaderFactory metadataReaderFactory)
        throws IOException {
    if (hasAnnotation()) {
        return !(include(metadataReader, metadataReaderFactory) && !exclude(metadataReader, metadataReaderFactory));
    }
    return false;
}
```

`@WebMvcTest` 的白名单本身：

【源码证据】module/spring-boot-webmvc-test/src/main/java/org/springframework/boot/webmvc/test/autoconfigure/WebMvcTypeExcludeFilter.java · KNOWN_INCLUDES · L59~89

```java
Set<Class<?>> includes = new LinkedHashSet<>();
includes.add(ControllerAdvice.class);
includes.add(WebMvcConfigurer.class);
...
includes.add(HandlerInterceptor.class);
...
Set<Class<?>> includes = new LinkedHashSet<>(KNOWN_INCLUDES);
includes.add(Controller.class);          // 未指定 controllers 属性时才加
```

（4.x 注意：切片注解已从 `spring-boot-test-autoconfigure` 拆分到各自技术模块，如 `module/spring-boot-webmvc-test` 的 `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`、`module/spring-boot-data-jpa-test` 的 `DataJpaTest`。）

### 7.5.4 AutoConfigureMockMvc 与 TestRestTemplate

- `@AutoConfigureMockMvc`（module/spring-boot-webmvc-test，L52）：在容器里自动配好 `MockMvc`，不真启动端口就能测 Controller——`@WebMvcTest` 已自带它（WebMvcTest.java L106）。
- `TestRestTemplate`：面向**真启动端口**（RANDOM_PORT）的容错型 RestTemplate（4xx/5xx 不抛异常）。4.2 最新状态：已废弃，改用 Framework 的 `RestTestClient`：

【源码证据】module/spring-boot-resttestclient/src/main/java/org/springframework/boot/resttestclient/TestRestTemplate.java · javadoc · L93~96

```java
 * @since 4.0.0
 * @deprecated since 4.2.0 for removal in 4.4.0 in favor of
 * {@link org.springframework.test.web.servlet.client.RestTestClient}
 */
@Deprecated(since = "4.2.0", forRemoval = true)
```

---

## 7.6 其余目录一句话定位

- **cli/**：`spring-boot-cli`，Spring Boot 命令行工具（`spring run app.groovy` 那套），与核心框架无依赖关系。
- **smoke-test/**：几十个官方最小示例应用（每个 starter/特性一个），读"某功能怎么配"时最好的活文档。
- **system-test/**：部署级系统测试（`spring-boot-deployment-system-tests`、`spring-boot-image-system-tests`），专门验证可执行 jar、buildpack 镜像这些"整包产物"能真跑起来。

---

## 本章小结

| 工具 | 一句话本质 | 关键源码 |
|---|---|---|
| fat jar | 自带 ClassLoader 的"jar 套 jar" | JarLauncher / Launcher.launch / ZipContent.load |
| 构建插件 | 调 Repackager 把普通 jar 改写为 BOOT-INF 布局 | RepackageMojo.repackage / Repackager.repackage |
| devtools | RestartClassLoader 只换"你的类"，第三方类常驻 | Restarter.doStart / ChangeableUrls |
| starter | 只有 build.gradle 的依赖聚合器（176 个，0 个有 src） | starter/spring-boot-starter/build.gradle |
| @SpringBootTest | 用 Boot 的 Bootstrapper+Loader 驱动 Framework TestContext | SpringBootTestContextBootstrapper / SpringBootContextLoader |
| 切片测试 | TypeExcludeFilter 挂在 @ComponentScan 上做白名单裁剪 | TypeExcludeFilter.match / WebMvcTypeExcludeFilter |


---

# 八、扩展机制大全：Boot 的插件系统

读完前面几章你应该已经隐约感觉到：Boot 好像无处不在地"插手"了 Framework 的启动过程。本章把 Boot 给开发者留下的所有"口子"一次性讲透。核心观点先行：**Boot 自己没有发明新的容器扩展模型，它做的是把 Framework 的扩展点（BFPP/BPP/FactoryBean/事件……）"前移"到容器诞生之前，并用一套注册文件把它们编排成开箱即用**。

Framework 的扩展点大多要求"容器已存在"才能生效（BFPP 在 refresh 时调用、@EventListener 需要 Multicaster 就绪）。但一个应用从 `main()` 到容器 refresh 之间还有一段"黑暗期"：Environment 还没建好、配置文件还没读、日志还没初始化、连 ApplicationContext 都不存在。Boot 扩展机制的主要价值，就是让你能在这段黑暗期的每个关键时刻插一脚。

## 8.0 开篇考据：4.2 时代的注册文件体系

**解决什么问题**：Spring Boot 的"插件"从哪里来？答案是 classpath 上的两个特殊位置：`META-INF/spring.factories` 和 `META-INF/spring/<注解全限定名>.imports`。初学者最容易犯的错误就是把该注册的东西放错文件，所以我们先做一次彻底的"人口普查"。

### 8.0.1 spring.factories 现状：只剩基础设施条目

实际读取 `core/spring-boot/src/main/resources/META-INF/spring.factories`（4.2.0-SNAPSHOT），现存 13 类 key：

| key（接口） | 注册数量 | 代表实现 |
|---|---|---|
| `org.springframework.boot.logging.LoggingSystemFactory` | 3 | Logback/Log4J2/JUL 工厂 |
| `org.springframework.boot.env.PropertySourceLoader` | 2 | Properties/Yaml 加载器 |
| `...config.ConfigDataLocationResolver` | 3 | 标准/目录/环境变量解析器 |
| `...config.ConfigDataLoader` | 3 | 同上 |
| `org.springframework.boot.SpringApplicationRunListener` | 1 | `EventPublishingRunListener` |
| `org.springframework.boot.SpringBootExceptionReporter` | 1 | `FailureAnalyzers` |
| `org.springframework.context.ApplicationContextInitializer` | 3 | ConfigurationWarnings/ContextId/ProtocolResolver |
| `org.springframework.context.ApplicationListener` | 6 | LoggingApplicationListener、EnvironmentPostProcessorApplicationListener 等 |
| `org.springframework.boot.EnvironmentPostProcessor` | 5 | ConfigData/RandomValue/Json/SystemEnvironment 等 |
| `org.springframework.boot.diagnostics.FailureAnalyzer` | 18 | BindFailureAnalyzer、NoSuchBean 等 |
| `org.springframework.boot.diagnostics.FailureAnalysisReporter` | 1 | LoggingFailureAnalysisReporter |
| `org.springframework.core.io.ProtocolResolver` | 1 | Base64ProtocolResolver |
| `...io.ApplicationResourceLoader$FilePathResolver` | 3 | 类路径/Servlet/Reactive 资源解析 |

注意：**这一版的 key 里已经没有自动配置**。看一眼其他模块的 spring.factories 会发现它们也只注册"基础设施级"的东西，例如 `module/spring-boot-tomcat` 只注册了 BackgroundPreinitializer 和一个 FailureAnalyzer；`core/spring-boot-docker-compose` 只注册了两个 ApplicationListener。

### 8.0.2 *.imports 清单：102 个 AutoConfiguration.imports 加 80 个测试切片 imports

在仓库根目录执行 `find . -path "*/main/resources/META-INF/spring/*" -name "*.imports"` 得到：

- **`org.springframework.boot.autoconfigure.AutoConfiguration.imports` 共 102 个**（含 integration-test 下 3 个），分布在 `core/spring-boot-autoconfigure`、`core/spring-boot-testcontainers`、`module/` 下的每个技术模块（jackson、tomcat、data-jpa……4.x 已把 monolith 的 autoconfigure 拆成了约 60 个独立模块，每个模块自带自己的 imports 文件）。`core/spring-boot-autoconfigure` 自己那份只剩 **12 行**（AopAutoConfiguration、TaskExecutionAutoConfiguration、SslAutoConfiguration 等）——因为 Jackson、Tomcat、Data-JPA 的自动配置都已搬进各自模块。
- **约 80 个"其他"imports 文件**，分两类：
  1. **测试切片注解的自动配置**：如 `META-INF/spring/org.springframework.boot.test.autoconfigure.json.AutoConfigureJsonTesters.imports`、`org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest.imports`——每种测试注解一个文件；
  2. **Actuator 管理上下文**：`org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration.imports`（tomcat/jetty/webmvc/reactor-netty 等模块各一份）。
- **`ApplicationContextInitializer` 没有独立的 .imports 文件**——这是 find 实测的结论，Initializer 仍走 spring.factories（下一节解释为什么它必须早于容器存在，因而没法用容器期才生效的机制）。

### 8.0.3 机制本身：ImportCandidates

*.imports 文件的读取逻辑在 `core/spring-boot/src/main/java/org/springframework/boot/context/annotation/ImportCandidates.java`：

```java
// ImportCandidates.java 第 47 行
private static final String LOCATION = "META-INF/spring/%s.imports";
// 第 81-85 行 load()
public static ImportCandidates load(Class<?> annotation, @Nullable ClassLoader classLoader) {
    ...
    String location = String.format(LOCATION, annotation.getName());
    Enumeration<URL> urls = findUrlsInClasspath(classLoaderToUse, location);
```

它是个通用机制：`load(注解类)` 就去读 `META-INF/spring/<注解全限定名>.imports`。消费方至少有四家：`AutoConfigurationImportSelector`（第 202 行）、`ImportAutoConfigurationImportSelector`（第 119 行，测试切片用它）、`ManagementContextConfigurationImportSelector`（第 101 行）、`AutoConfigurationExcludeFilter`（第 70 行）。

### 8.0.4 演变史与结论表

- **2.7 之前**：一切都挤在 spring.factories，`org.springframework.boot.autoconfigure.EnableAutoConfiguration` 一个 key 下挂上百个类。
- **2.7**：拆出 `AutoConfiguration.imports`，spring.factories 里的自动配置 key 被标记废弃（为了给用户一个迁移窗口）。
- **3.0**：删除 spring.factories 中的自动配置条目，只认 *.imports。
- **4.x（本章实测）**：模块大拆分（`spring-boot-webmvc`/`spring-boot-tomcat`/`spring-boot-data-jpa`……），`spring-boot-starter-web` 更名 `spring-boot-starter-webmvc`；starter 目录（`starter/`，约 170 个）全部退化为纯依赖聚合——以 `starter/spring-boot-starter-data-jpa/build.gradle` 为证，其 dependencies 只有 4 个 `api(project(...))`；spring.factories 回归"基础设施注册表"定位。

**开发者该往哪个文件注册什么（结论表）**：

| 你要扩展的东西 | 注册文件 | key |
|---|---|---|
| 自动配置类 | `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | 一行一个类名 |
| ApplicationContextInitializer / ApplicationListener / EnvironmentPostProcessor / FailureAnalyzer / SpringApplicationRunListener / SpringBootExceptionReporter | `META-INF/spring.factories` | 接口全限定名 |
| 测试切片自动配置 / 管理上下文配置 | `META-INF/spring/<对应注解FQN>.imports` | 一行一个类名 |
| AOT 反射提示等 | `META-INF/spring/aot.factories` | 见 8.13 |

## 8.0.5 扩展点总表

| 扩展点 | 注册位置 | 触发时机（run 的哪一步） | 典型用途 |
|---|---|---|---|
| ApplicationContextInitializer | spring.factories 或 `addInitializers()` | prepareContext，refresh 之前 | 改 BeanDefinition、注册单例 |
| ApplicationListener | spring.factories 或 `addListeners()` | 全程（contextLoaded 后改由容器广播） | 监听 Boot 生命周期事件 |
| EnvironmentPostProcessor | spring.factories | environmentPrepared | 读外部配置、加密属性 |
| @Conditional + SpringBootCondition | 普通注解/类 | refresh 中解析条件 | 自定义装配开关 |
| AutoConfigurationImportFilter | spring.factories | 自动配置导入期（refresh 前） | 无类加载地剔除候选 |
| AutoConfigureBefore/After | 自动配置类注解 | 导入排序 | 控制装配顺序 |
| FailureAnalyzer / SpringBootExceptionReporter | spring.factories | run 失败时 | 异常翻译、报表 |
| ExitCodeGenerator / ExitCodeExceptionMapper | Bean 或异常类 | handleRunFailure / 正常退出 | 定制进程退出码 |
| ApplicationRunner / CommandLineRunner | Bean | started 事件之后 | 启动后任务 |
| XxxCustomizer（WebServerFactory 等） | Bean | Bean 初始化阶段（BPP） | 定制三方组件 Builder |
| @ConfigurationPropertiesBinding Converter | Bean | 绑定期 | 自定义属性类型转换 |
| SpringApplicationRunListener | spring.factories | run 全程回调 | 极早期/细粒度生命周期钩子 |
| ServiceConnection（docker-compose/testcontainers） | spring.factories + @ServiceConnection | 事件/测试定制器 | 自动注册 ConnectionDetails |
| RuntimeHintsRegistrar | aot.factories | AOT 构建期 | native 反射提示 |

---

## 8.1 EnvironmentPostProcessor：容器诞生前改环境

**解决什么问题**：你想在"任何配置绑定发生之前"往 Environment 里塞 PropertySource——比如从自己的加密配置中心解密、读一个自定义格式的文件。此时 ApplicationContext 尚不存在，BFPP/BPP 全都用不上。

**①它是什么**：`core/spring-boot/src/main/java/org/springframework/boot/EnvironmentPostProcessor.java`（4.0 起从 `org.springframework.boot.env` 挪到顶层包）：

```java
// EnvironmentPostProcessor.java 第 51-61 行
@FunctionalInterface
public interface EnvironmentPostProcessor {
    /**
     * Post-process the given {@code environment}.
     */
    void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application);
}
```

**②框架何时调用它**：这是 4.x 最值得考据的链路。它**不再被直接调用**，而是经由事件驱动：`SpringApplication.run` 第 356 行 `listeners.environmentPrepared(...)` → 唯一的 RunListener `EventPublishingRunListener.environmentPrepared`（第 80-84 行）广播 `ApplicationEnvironmentPreparedEvent` → 被 spring.factories 里注册的 `EnvironmentPostProcessorApplicationListener` 接住：

```java
// EnvironmentPostProcessorApplicationListener.java 第 111-115 行（supportsEventType）
return ApplicationEnvironmentPreparedEvent.class.isAssignableFrom(eventType)
        || ApplicationPreparedEvent.class.isAssignableFrom(eventType)
        || ApplicationFailedEvent.class.isAssignableFrom(eventType);
// 第 130-137 行（执行）
private void onApplicationEnvironmentPreparedEvent(ApplicationEnvironmentPreparedEvent event) {
    ConfigurableEnvironment environment = event.getEnvironment();
    SpringApplication application = event.getSpringApplication();
    List<EnvironmentPostProcessor> postProcessors = getEnvironmentPostProcessors(...);
    for (EnvironmentPostProcessor postProcessor : postProcessors) {
        postProcessor.postProcessEnvironment(environment, application);   // 第 137 行
    }
}
```

注意三件事：监听器 order 是 `HIGHEST_PRECEDENCE + 10`（第 72 行），保证它先于业务监听器跑；实现从 spring.factories 读取（第 85 行 `EnvironmentPostProcessorsFactory::fromSpringFactories`）；构造器可注入 `DeferredLogFactory` 和 `ConfigurableBootstrapContext`（接口 javadoc 第 36-45 行）。

**③内置实现**：spring.factories 里 5 个，最重要的是 `ConfigDataEnvironmentPostProcessor`（application.yml 的加载就是它干的）、`RandomValuePropertySourceEnvironmentPostProcessor`、`SpringApplicationJsonEnvironmentPostProcessor`（SPRING_APPLICATION_JSON）。

**④最小示例**：

```java
public class VaultEnvironmentPostProcessor implements EnvironmentPostProcessor {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        Map<String, Object> map = Map.of("my.db.password", decrypt());
        env.getPropertySources().addFirst(
            new MapPropertySource("vaultSource", map));
    }
}
// resources/META-INF/spring.factories:
// org.springframework.boot.EnvironmentPostProcessor=com.example.VaultEnvironmentPostProcessor
```

## 8.2 ApplicationContextInitializer / ApplicationListener：两条注册路

**解决什么问题**：想在容器 refresh 之前改"容器的元数据"（Initializer），或想在任意生命周期节点收事件（Listener）。

**①它是什么**：直接复用 Framework 的 `org.springframework.context.ApplicationContextInitializer` / `org.springframework.context.ApplicationListener`，Boot 未另造接口。

**②框架何时调用它们**：两条注册路在**同一个构造器里汇合**——`SpringApplication` 构造器（第 279-282 行）从 spring.factories 加载：

```java
// SpringApplication.java 第 279-282 行
this.bootstrapRegistryInitializers = new ArrayList<>(
        getSpringFactoriesInstances(BootstrapRegistryInitializer.class));
setInitializers((Collection) getSpringFactoriesInstances(ApplicationContextInitializer.class));
setListeners((Collection) getSpringFactoriesInstances(ApplicationListener.class));
```

而 `getSpringFactoriesInstances` 最终就是 `SpringFactoriesLoader.forDefaultResourceLocation(getClassLoader()).load(type, ...)`（第 472 行）。调用时机：Initializer 在 `prepareContext` 中、`load(源)` 之前逐个执行（第 393 行 `applyInitializers(context)` → 第 623 行 `initializer.initialize(context)`）；Listener 则由 `EventPublishingRunListener` 接管——context 刷新前用手持的 `SimpleApplicationEventMulticaster` 广播（`EventPublishingRunListener.multicastInitialEvent`，第 135-138 行），`contextLoaded` 时把所有 Listener 移交给真正的容器（第 93-98 行 `context.addApplicationListener(listener)`），从此走容器广播。

**③注册的第二条路：构造时手动 add**。spring.factories 适合类库（jar 自带），手动注册适合本应用的一次性需求，且能拿到 SpringApplication 实例传参：

```java
// SpringApplication.java 第 1256 / 1283 行
SpringApplication app = new SpringApplication(MyApp.class);
app.addInitializers(ctx -> ctx.getBeanFactory().registerSingleton("start", Instant.now()));
app.addListeners(new MySlowStartupListener());
```

**④内置实现**：Initializer 有 `ConfigurationWarningsApplicationContextInitializer`（报告 @ComponentScan 误扫 org 包）、`ContextIdApplicationContextInitializer`；Listener 有 `LoggingApplicationListener`（日志系统随事件切换）、`ClearCachesApplicationListener`。**最小示例**见上面代码块，注意泛型：`implements ApplicationContextInitializer<ConfigurableApplicationContext>`。

## 8.3 Boot 事件体系：把生命周期发布成消息

**解决什么问题**：你只想在"配置加载完""容器就绪""启动失败"这些精确时刻被通知，不想重写整个 run 流程。

**①它是什么**：Boot 在 `org.springframework.boot.context.event` 包定义了 7 个事件（ls 实测）：`ApplicationStartingEvent`、`ApplicationEnvironmentPreparedEvent`、`ApplicationContextInitializedEvent`、`ApplicationPreparedEvent`、`ApplicationStartedEvent`、`ApplicationReadyEvent`、`ApplicationFailedEvent`，全部继承 `SpringApplicationEvent`。

**②框架何时发布它们**：全部出自唯一的 RunListener `EventPublishingRunListener`，与 `SpringApplication.run` 的步骤一一对应：

```java
// EventPublishingRunListener.java
public void starting(ConfigurableBootstrapContext bootstrapContext) {              // 75-77 行
    multicastInitialEvent(new ApplicationStartingEvent(bootstrapContext, this.application, this.args));
}
public void environmentPrepared(...) {                                             // 80-84 行
    multicastInitialEvent(new ApplicationEnvironmentPreparedEvent(..., environment));
}
public void started(ConfigurableApplicationContext context, Duration timeTaken) {  // 103-106 行
    context.publishEvent(new ApplicationStartedEvent(this.application, this.args, context, timeTaken));
    AvailabilityChangeEvent.publish(context, LivenessState.CORRECT);
}
public void ready(ConfigurableApplicationContext context, Duration timeTaken) {    // 109-112 行
    context.publishEvent(new ApplicationReadyEvent(this.application, this.args, context, timeTaken));
    AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
}
```

**③消费方式两种**：实现 `ApplicationListener<具体事件>`（配 8.2 的任一注册路）；或普通 Bean 上标 `@EventListener`——但注意后者要等容器 refresh 完才生效，所以**监听 Starting/EnvironmentPrepared 这类早期事件只能走 ApplicationListener 路线**。`started`/`ready`/`failed` 事件携带 `timeTaken`，K8s 探针（Liveness/Readiness）就靠伴随发布的 `AvailabilityChangeEvent` 实现。

**④最小示例**：

```java
@Component
public class WarmUpListener {
    @EventListener
    public void onReady(ApplicationReadyEvent event) {
        log.info("启动耗时 {} ms，开始预热缓存", event.getTimeTaken());
    }
}
```

## 8.4 @Conditional 自定义条件：SpringBootCondition

**解决什么问题**：Framework 的 `@Conditional` 只给一个 `Condition.matches`，你要自己从元数据抠注解属性、拼不匹配原因，而且结果进不了 Boot 的条件评估报告。`SpringBootCondition` 帮你处理这些杂务，你只需回答"匹配吗、为什么不匹配"。

**①它是什么**：`core/spring-boot-autoconfigure/.../condition/SpringBootCondition.java`，实现 Framework 的 `Condition`，留一个抽象方法：

```java
// SpringBootCondition.java 第 45-50 行（模板方法 matches）
String classOrMethodName = getClassOrMethodName(metadata);
try {
    ConditionOutcome outcome = getMatchOutcome(context, metadata);
    logOutcome(classOrMethodName, outcome);
    recordEvaluation(context, classOrMethodName, outcome);   // 写入 ConditionEvaluationReport
    return outcome.isMatch();
// 第 115 行（子类要实现的唯一方法）
public abstract ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata);
```

**②何时调用**：refresh 阶段 `ConfigurationClassPostProcessor` 解析配置类时（即 Framework 的条件评估管线），所有结果记录进 `ConditionEvaluationReport`——`/actuator/conditions` 端点和启动报告的数据源。

**③内置实现**：`OnJavaCondition` 是最干净的教科书实现，自定义条件照抄即可：

```java
// OnJavaCondition.java 第 40-60 行
class OnJavaCondition extends SpringBootCondition {
    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attributes = metadata.getAnnotationAttributes(ConditionalOnJava.class.getName());
        ...
        return getMatchOutcome(range, JVM_VERSION, version);
    }
}
```

**④最小示例**：写一个 `@ConditionalOnRedisSentinel` 注解 + `RedisSentinelCondition extends SpringBootCondition`，从 `environment.getProperty("spring.redis.sentinel.master")` 判断并返回 `ConditionOutcome.match()/noMatch("未配置哨兵")`，最后 `@Conditional(RedisSentinelCondition.class)` 挂到配置类上。

## 8.5 AutoConfigurationImportFilter 与 @AutoConfigureBefore/After：给自动配置引擎装阀门

**解决什么问题**：上百个候选自动配置类，如果每个都要加载类、跑条件评估，启动会慢死。Filter 允许**在不加载类的前提下**提前剔除候选；Before/After 解决候选之间"谁先装配"的顺序问题。

**①它是什么**：`core/spring-boot-autoconfigure/.../AutoConfigurationImportFilter.java` 第 45-59 行：

```java
@FunctionalInterface
public interface AutoConfigurationImportFilter {
    boolean[] match(@Nullable String[] autoConfigurationClasses,
            AutoConfigurationMetadata autoConfigurationMetadata);
}
```

**②何时调用**：在 `AutoConfigurationImportSelector.getAutoConfigurationEntry`（Framework 的 ImportSelector 机制，`refresh` 的 BFPP 阶段）里，第 152 行 `getConfigurationClassFilter().filter(configurations)`；filter 列表同样来自 spring.factories（第 279 行 `SpringFactoriesLoader.loadFactories(AutoConfigurationImportFilter.class, ...)`），逐个打布尔掩码：

```java
// AutoConfigurationImportSelector.java 第 403-404 行（ConfigurationClassFilter.filter）
for (AutoConfigurationImportFilter filter : this.filters) {
    boolean[] match = filter.match(candidates, this.autoConfigurationMetadata);
```

**③内置实现**：autoconfigure 的 spring.factories 注册了 `OnBeanCondition`、`OnClassCondition`、`OnWebApplicationCondition`——它们同时继承 `SpringBootCondition` 并实现该 filter（`FilteringSpringBootCondition` 第 42-43 行 `abstract class FilteringSpringBootCondition extends SpringBootCondition implements AutoConfigurationImportFilter`），这就是 `@ConditionalOnClass` 能"不加载类就否决"的原因。而顺序方面，`AutoConfigurationSorter` 在排序时读取注解元数据（第 79 行注释"Then respect @AutoConfigureBefore @AutoConfigureAfter"，第 210/219 行读取 `AutoConfigureBefore`/`AutoConfigureAfter`），`@AutoConfiguration` 注解本身已合成这两个注解（`AutoConfiguration.java` 第 58-59 行）。

**④最小使用**：给自定义 starter 排序：`@AutoConfiguration(after = DataSourceAutoConfiguration.class)`（同模块不在 classpath 时用 `name = "全限定名"`，见 `AutoConfigureBefore.java` 第 58-66 行 javadoc）。自定义 Filter 较少见，一般用于企业内部"禁用某些 starter"的管控。

## 8.6 FailureAnalyzer：把异常翻译成人话

**解决什么问题**：新手的 `NoSuchBeanDefinitionException` 三十行堆栈看不懂。Boot 的思路：run 失败时用一串分析器逐个"认领"异常，认领成功就只打印一段带动作建议的人话。

**①它是什么**：`core/spring-boot/.../diagnostics/FailureAnalyzer.java` 第 29-39 行：

```java
@FunctionalInterface
public interface FailureAnalyzer {
    @Nullable FailureAnalysis analyze(Throwable failure);
}
```

**②调用链（本节重点考据）**：`run` 抛异常 → `handleRunFailure`（第 803 行）→ `reportFailure(getExceptionReporters(context), exception)`（第 816 行）→ `getExceptionReporters` 从 spring.factories 加载 `SpringBootExceptionReporter`（第 835 行）——注册表里只有一个：`FailureAnalyzers`。它实现了完整的"认领"逻辑：

```java
// FailureAnalyzers.java 第 85-95 行
public boolean reportException(Throwable failure) {
    FailureAnalysis analysis = analyze(failure, this.analyzers);
    return report(analysis);
}
private @Nullable FailureAnalysis analyze(Throwable failure, List<FailureAnalyzer> analyzers) {
    for (FailureAnalyzer analyzer : analyzers) {
        FailureAnalysis analysis = analyzer.analyze(failure);
        if (analysis != null) { return analysis; }   // 第一个认领者获胜
    }
```

最终由 `FailureAnalysisReporter`（`LoggingFailureAnalysisReporter`）输出，成功后 `registerLoggedException` 抑制堆栈（`SpringApplication.java` 第 845-847 行）。

**③内置实现**：spring.factories 里 core 18 个 + autoconfigure 2 个，如 `BindFailureAnalyzer`、`PortInUseFailureAnalyzer`、`NoSuchBeanDefinitionFailureAnalyzer`。基类 `AbstractFailureAnalyzer<T>`（第 32-47 行）替你在异常链上找目标类型：`findCause` 从外向内遍历 `getCause()`（第 64-72 行）。

**④自定义示例**（业务异常翻译三步走）：

```java
// 1. 写分析器
public class LicenseExpiredFailureAnalyzer extends AbstractFailureAnalyzer<LicenseExpiredException> {
    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, LicenseExpiredException cause) {
        return new FailureAnalysis("License 已过期：" + cause.getExpiry(),
                "请到 https://example.com 续订后重启应用", rootFailure);
    }
}
// 2. 在 FAILURE_ANALYSIS 中描述"问题+动作"（FailureAnalysis 构造参数：message/action/cause）
// 3. resources/META-INF/spring.factories：
// org.springframework.boot.diagnostics.FailureAnalyzer=com.example.LicenseExpiredFailureAnalyzer
```

## 8.7 运行配置口子三件套：SpringBootExceptionReporter / ExitCodeGenerator / setAddCommandLineProperties

**解决什么问题**：控制"启动失败如何上报"和"进程以什么码退出"，以及命令行参数要不要进 Environment。

**SpringBootExceptionReporter**（第 8.6 节的容器）：`core/spring-boot/.../SpringBootExceptionReporter.java` 第 34-44 行，`boolean reportException(Throwable failure)`；返回 true 表示"我报过了"，`SpringApplication.reportFailure` 就不再打印堆栈（第 844-848 行）。约束：必须提供带 `ConfigurableApplicationContext` 参数的公共构造器（javadoc 第 25-27 行）。FailureAnalyzers 就是它唯一的内置实现。

**ExitCodeGenerator**：`ExitCodeGenerator.java` 第 29-37 行 `int getExitCode()`。`run` 失败时 `handleExitCode`（第 881-892 行）先发 `ExitCodeEvent`（第 885 行）再交给 `SpringBootExceptionHandler` 设置 JVM 退出码；还可以注册 `ExitCodeExceptionMapper` Bean 把"某类异常→某退出码"映射起来（第 902-908 行，`context.getBeansOfType(ExitCodeExceptionMapper.class)`）。容器内一个 Bean 实现 `ExitCodeGenerator` 即可在正常退出时生效。

**setAddCommandLineProperties**：`SpringApplication.java` 第 1056-1058 行。默认 true 时 `configurePropertySources` 会把命令行参数包成 `commandLineArgs` PropertySource 放到最前（第 518-531 行），即 `java -jar app.jar --server.port=9090` 能覆盖配置文件；不想让运维在命令行乱传参数就 `setAddCommandLineProperties(false)`。

## 8.8 ApplicationRunner / CommandLineRunner：启动后的最后一棒

**解决什么问题**：容器 refresh 成功之后、`run()` 返回之前，你想跑一段"应用已可用"的逻辑（预热、数据校验、调度触发）。用 `@PostConstruct` 太早（整个容器还没就绪），用事件又拿不到命令行参数。

**①它们是什么**：一个包访问级标记接口 `Runner`（`core/spring-boot/Runner.java` 第 26-28 行）+ 两个公开接口。区别只在参数形态：`ApplicationRunner.run(ApplicationArguments args)` 拿到解析过的参数（能区分 option 参数 `--name=value` 与非 option 参数）；`CommandLineRunner.run(String... args)` 拿原始数组。

**②何时调用**：`run()` 中 `listeners.started(...)` 之后、`listeners.ready(...)` 之前——第 328 行 `callRunners(context, applicationArguments)`。执行体只有 10 行，却包含了排序与两态分发：

```java
// SpringApplication.java 第 767-777 行（节选）
private void callRunners(ConfigurableApplicationContext context, ApplicationArguments args) {
    ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
    String[] beanNames = beanFactory.getBeanNamesForType(Runner.class);
    ...
    instancesToBeanNames.keySet().stream().sorted(comparator).forEach((runner) -> callRunner(runner, args));
}
// 第 786-793 行：一个 Bean 同时实现两个接口会各执行一次
if (runner instanceof ApplicationRunner) {
    callRunner(ApplicationRunner.class, runner, (r) -> r.run(args));
}
if (runner instanceof CommandLineRunner) {
    callRunner(CommandLineRunner.class, runner, (r) -> r.run(args.getSourceArgs()));
}
```

排序用 `AnnotationAwareOrderComparator`（第 774-775 行），所以 `@Order` / `Ordered` 都生效。注意 runner 抛异常会让整个启动失败（被 `IllegalStateException` 包裹，第 797-800 行）。

**③内置实现**：Boot 自身用得很少，生态典型是 Spring Batch 的 `JobExecutionApplicationRunner` 类模式（`JobLauncherApplicationRunner`）。**④最小示例**：

```java
@Component
@Order(1)
public class DataCheckRunner implements ApplicationRunner {
    @Override
    public void run(ApplicationArguments args) throws Exception {
        System.out.println("包含 --xxx 选项? " + args.containsOption("xxx"));
    }
}
```

## 8.9 XxxCustomizer 模式：4.x 最庞大的扩展家族

**解决什么问题**：Boot 帮你构造了 Tomcat、RestClient、JsonMapper……这些对象的"构建器"，但默认值不满足你怎么办？不能替换 Bean（那会失去自动配置），于是 Boot 抽出"对构建器做增量修改"的回调接口——Customizer。它本质是"把 BeanPostProcessor 的修改能力收窄成类型安全的一参数方法"。

**ls 证据**：在仓库执行 `find . -name "*Customizer.java" -path "*/main/java/*"` 得到 **170 个**，横跨 Web 服务器（Tomcat/Jetty/Undertow）、HTTP 客户端（`RestClientCustomizer`、`WebClientCustomizer`）、JSON（Jackson 3 的 `JsonFactoryBuilderCustomizer`/`JsonMapperBuilderCustomizer`，Jackson 2 的 `Jackson2ObjectMapperBuilderCustomizer`）、缓存、数据源、Flyway、gRPC……凡自动配置"代建"的关键对象，几乎都有一个对应的 Customizer。

**源码解剖——最经典的 WebServerFactoryCustomizer**：接口在 `module/spring-boot-web-server/.../WebServerFactoryCustomizer.java` 第 40-46 行（`void customize(T factory)`，泛型上界 `WebServerFactory`）。调用方不是 AutoConfiguration 而是**一个 BeanPostProcessor**：

```java
// WebServerFactoryCustomizerBeanPostProcessor.java 第 58-63、71-81 行
public Object postProcessBeforeInitialization(Object bean, String beanName) {
    if (bean instanceof WebServerFactory webServerFactory) {
        postProcessBeforeInitialization(webServerFactory);
    }
    return bean;
}
private void postProcessBeforeInitialization(WebServerFactory webServerFactory) {
    LambdaSafe.callbacks(WebServerFactoryCustomizer.class, getCustomizers(), webServerFactory)
        .invoke((customizer) -> customizer.customize(webServerFactory));
}
private Collection<WebServerFactoryCustomizer<?>> getCustomizers() {
    ...
    this.customizers = new ArrayList<>(getWebServerFactoryCustomizerBeans());
    this.customizers.sort(AnnotationAwareOrderComparator.INSTANCE);   // 第 81 行：@Order 生效
```

即：当 Tomcat 工厂 Bean 初始化前，收集容器里所有 `WebServerFactoryCustomizer` Bean 依次回调。官方实现 `TomcatWebServerFactoryCustomizer`（`module/spring-boot-tomcat` 第 70-71 行 `implements WebServerFactoryCustomizer<ConfigurableTomcatWebServerFactory>, Ordered`）在 `customize`（第 98 行起）里用 `PropertyMapper` 把 `server.tomcat.*` 属性映射到工厂。另一个干净的解剖样本是 `RestClientCustomizer`（`module/spring-boot-restclient/.../RestClientCustomizer.java` 第 29-38 行），由 `JacksonAutoConfiguration` 同款的 `customizers.orderedStream().forEach(c -> c.customize(builder))` 模式消费。

**④最小示例**：

```java
@Component
public class MyTomcatCustomizer implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {
    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        factory.addConnectorCustomizers(c -> c.setProperty("maxKeepAliveRequests", "200"));
    }
}
```

## 8.10 @ConfigurationProperties 绑定定制：Converter 与命名策略

**解决什么问题**：配置文件里的值要绑定成你自己的类型（如 `my.token=AES:xyz` 绑成 `Token` 对象），或想调整宽松命名的解析规则。

一句话机制：注册一个 `Converter<String, Token>` Bean 并标 `@ConfigurationPropertiesBinding`（`core/spring-boot/.../ConfigurationPropertiesBinding.java` 第 39-43 行，它其实是个 `@Qualifier` 元注解，把该 Converter 标记为专供绑定用的转换服务），Binder 就会用它；命名策略由 `core/spring-boot/.../properties/source/ConfigurationPropertyName.java` 统一裁决（kebab-case 规范，`invalid-config-name` 会直接绑定失败并触发 `InvalidConfigurationPropertyNameFailureAnalyzer`）。配套工具 `configuration-metadata/spring-boot-configuration-processor`：编译期扫描 `@ConfigurationProperties` 生成 `META-INF/spring-configuration-metadata.json`，IDE 才能补全和文档提示——自定义 starter 必配。

## 8.11 实战：写一个自定义 Starter 的完整步骤

**解决什么问题**：把"依赖聚合 + 自动配置 + 条件装配"打包成一行依赖即可用的产品。结合本仓库真实结构（`starter/` + `module/` 两层）给出模板。

以官方 `spring-boot-starter-data-jpa`（`starter/`）与 `module/spring-boot-data-jpa` 为标本：

1. **建自动配置模块**（对应官方 `module/spring-boot-data-jpa`）：写 `XxxAutoConfiguration`，标 `@AutoConfiguration` + `@ConditionalOnClass` + `@ConditionalOnMissingBean`（参照 `JacksonAutoConfiguration.java` 第 105-106 行：`@AutoConfiguration @ConditionalOnClass(JsonMapper.class)`）。
2. **注册 imports**：`src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`，一行一个类名——官方 data-jpa 模块的这个文件只有一行 `org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration`。
3. **建 starter 模块**（对应 `starter/spring-boot-starter-data-jpa/build.gradle`，实测内容）：

```groovy
plugins { id "org.springframework.boot.starter" }
dependencies {
    api(project(":starter:spring-boot-starter"))          // 核心：autoconfigure + logging + yaml
    api(project(":starter:spring-boot-starter-jdbc"))     // 依赖的技术栈
    api(project(":module:spring-boot-data-jpa"))          // 你的自动配置模块
    api(project(":module:spring-boot-jdbc"))
}
```

   **starter 里不放任何 Java 代码**——这是 4.x 官方结构明确的分工：starter 纯聚合，module/ 放 `AutoConfiguration` 与 Customizer，`core/` 放基础设施。
4. **需要早期介入才注册 spring.factories**（如你的 FailureAnalyzer、EnvironmentPostProcessor、BackgroundPreinitializer——官方 jackson/tomcat 模块各有一份样例）。
5. **条件注解选型**：类存在用 `@ConditionalOnClass`，用户没自定义用 `@ConditionalOnMissingBean`，配置开关用 `@ConditionalOnProperty`，顺序用 `@AutoConfiguration(before/after = ...)`。

## 8.12 TypeExcludeFilter（交叉引用）

测试切片能"反查"主容器的 @ComponentScan——`core/spring-boot/src/main/java/org/springframework/boot/context/TypeExcludeFilter.java` 是 @ComponentScan 的 excludeFilters 挂载点，由 `spring-boot-test` 的 `ExcludeFilterApplicationContextInitializer` 注册。详细解剖见"测试体系"一章，此处一句话：它让 `@WebMvcTest` 能从主上下文中只放行切片关心的 Bean。

## 8.13 服务连接扩展：docker-compose 与 testcontainers

**解决什么问题**：本地开发要起数据库容器，测试要起 Testcontainers 容器——连接信息（host/port/密码）应该自动流进 DataSource 配置，而不是手抄到 application.yml。

实测两个模块都靠 spring.factories 注册基础设施（`core/spring-boot-docker-compose/src/main/resources/META-INF/spring.factories` 注册了 `DockerComposeListener` 和 `DockerComposeServiceConnectionsApplicationListener` 两个 ApplicationListener；`core/spring-boot-testcontainers` 注册了 `ServiceConnectionContextCustomizerFactory`）。以 docker-compose 为例：

```java
// DockerComposeServiceConnectionsApplicationListener.java 第 46-48、60-65 行
class DockerComposeServiceConnectionsApplicationListener
        implements ApplicationListener<DockerComposeServicesReadyEvent> {
    ...
    public void onApplicationEvent(DockerComposeServicesReadyEvent event) {
        ...
        registerConnectionDetails(registry, environment, event.getRunningServices());
```

即：容器组就绪事件到达时，按镜像名匹配 `ConnectionDetailsFactory`，把 `RedisConnectionDetails` 等实现注册为 BeanDefinition——这就是"服务连接"扩展：你只需在 Bean 上标 `@ServiceConnection`（Testcontainers 测试）或什么都不标（compose 自动识别），自动配置读 ConnectionDetails 而非 yml。

## 8.14 AOT 与 native：aot.factories 与 RuntimeHintsRegistrar

一句话：native 镜像里反射/资源/代理必须在构建期声明，Boot 在 `META-INF/spring/aot.factories` 里注册三类处理器——实测 `core/spring-boot/src/main/resources/META-INF/spring/aot.factories` 含 `org.springframework.aot.hint.RuntimeHintsRegistrar`（11 个，如 `ConfigDataLocationRuntimeHints`）、`BeanFactoryInitializationAotProcessor`（3 个）、`BeanRegistrationAotProcessor`（1 个，`ConfigurationPropertiesBeanRegistrationAotProcessor`），约 15 个模块各带自己的 aot.factories。开发者实现 `RuntimeHintsRegistrar` 并注册进 `@ImportRuntimeHints` 即可为自定义反射调用补提示。此外本章出现过两处 AOT 彩蛋：`EnvironmentPostProcessorApplicationListener` 会查找并注入 AOT 生成的主类级 `Xxx__EnvironmentPostProcessor`（第 160-171 行），`SpringApplication.addAotGeneratedInitializerIfNecessary` 同理（第 421-439 行）。

## 本章小结

把 14 个扩展点按"容器诞生前后"重新排布，就是 Boot 的全部插件系统：**容器诞生前**（spring.factories + 事件驱动）有 EPP、Initializer、早期 Listener；**容器诞生中**（refresh 的 BFPP 管线）有 AutoConfigurationImportSelector/Filter、@Conditional、@AutoConfigureBefore/After；**容器诞生后**有 Customizer（BPP 阶段）、Runner、Ready 事件；**失败与退出**有 FailureAnalyzer、ExceptionReporter、ExitCodeGenerator。而这一切的钥匙就是两句话：`SpringFactoriesLoader.forDefaultResourceLocation().load(type)` 读 spring.factories，`ImportCandidates.load(annotation)` 读 *.imports——Boot 的"魔法"全部是这两行代码的排列组合。


---

# 九、贯通视图：一次启动的三层嵌套

前面各章是"分镜头"，本章把两条流水线剪成"正片"：Boot 的 `run()` 19 步在外层，Framework 的 `refresh()` 12 步嵌在第 13 步里，而内嵌 Tomcat 的启动又嵌在 refresh 的最后一步。三层嵌套理解了，Boot 就没有秘密了。

## 9.1 全景大图：run() ⊃ refresh() ⊃ Tomcat.start()

```
java -jar app.jar
 └─ JarLauncher.launch（嵌套 jar classpath → 反射调 Start-Class）      [第七章]
     └─ SpringApplication.run(args)                                    [第二章]
         ├─ ① 构造期已完成：deduce 应用类型 / 装载 Initializer+Listener /
         │    推断主类（spring.factories 注册表）                      [二.2.3]
         ├─ ② listeners.starting          → ApplicationStartingEvent
         ├─ ③ prepareEnvironment          → ApplicationEnvironmentPreparedEvent
         │    └─ EnvironmentPostProcessorApplicationListener
         │         └─ ConfigDataEnvironmentPostProcessor
         │              └─ application.yml/properties → Environment（带 Origin）[四.4.2]
         │         └─ spring.main.* 回绑 SpringApplication
         ├─ ④ createApplicationContext    → new ServletWebServerApplicationContext（空容器）
         ├─ ⑤ prepareContext              → applyInitializers → 主类成为 BeanDefinition
         │    └─ ApplicationPreparedEvent
         ├─ ⑥ refreshContext ────────────── applicationContext.refresh() ◄─ 方向盘交还 Framework
         │    │                                                          [Framework 篇第四章]
         │    ├─ invokeBeanFactoryPostProcessors
         │    │    └─ ConfigurationClassPostProcessor
         │    │         └─ 用户配置类先解析 → DeferredImportSelector 后执行
         │    │            └─ AutoConfigurationGroup.selectImports
         │    │               读 102 个 imports → Filter 预筛 → 拓扑排序
         │    │               → @Conditional 逐个评估 → BeanDefinition 入册  [三]
         │    ├─ ★ onRefresh（Boot 重写：造服务器但不绑端口）            [五.5.2]
         │    │    └─ createWebServer
         │    │         ├─ TomcatServletWebServerFactory.getWebServer
         │    │         │    └─ new TomcatWebServer → tomcat.start()「半启动」
         │    │         │       （Connector 已摘除、bindOnInit=false）
         │    │         │       └─ Context 启动 → initializer 链
         │    │         │          → DispatcherServlet 注册到 "/"（提前实例化）
         │    │         └─ registerSingleton("webServerStartStop",
         │    │                WebServerStartStopLifecycle)  ← 启动权暂存
         │    ├─ finishBeanFactoryInitialization（其余单例就绪）
         │    └─ ★ finishRefresh
         │         └─ lifecycleProcessor.onRefresh → SmartLifecycle 按 phase start
         │              └─ WebServerStartStopLifecycle.start [phase=MAX-2048]
         │                   ├─ TomcatWebServer.start → Connector 装回 → ★ 端口监听
         │                   └─ publishEvent(ServletWebServerInitializedEvent)
         │                      → local.server.port                        [五.5.10]
         │         └─ publishEvent(ContextRefreshedEvent)
         ├─ ⑦ listeners.started          → ApplicationStartedEvent（存活探针 UP）
         ├─ ⑧ callRunners                → ApplicationRunner/CommandLineRunner  [八.8.8]
         └─ ⑨ listeners.ready            → ApplicationReadyEvent（就绪探针）
              └─ K8s readiness 通过，开始接流量
```

## 9.2 三个高频误解的源码级澄清

1. **"Tomcat 在 onRefresh 里就 start 了，所以端口很早监听"？** 错。`TomcatWebServer.initialize()` 里的 `tomcat.start()` 是"半启动"——Connector 被摘除、`bindOnInit=false`，端口绑定只发生在 `WebServerStartStopLifecycle.start()`（SmartLifecycle，finishRefresh 阶段），即**所有单例 Bean 就绪之后**（五.5.4 完整证据链）。反过来讲：refresh 中途任何失败，都不会泄漏一个"挂着 8080 的空壳 Tomcat"（两个 Web 上下文的 `refresh()` 都是 final 并带 stop+destroy 兜底）。
2. **"自动配置抢在用户配置之前执行"？** 错，顺序恰好相反。AutoConfigurationImportSelector 是 **Deferred**ImportSelector：等全部用户配置类处理完才执行；OnBeanCondition 又限定在 `REGISTER_BEAN` 阶段查"已注册的 BeanDefinition"。两者配合形成"用户 > Boot > Framework"三级让位链（三.3.3.1、三.3.4.3）。
3. **"配置文件在容器 refresh 后由 BeanPostProcessor 加载"？** 错。application.yml 由 `ConfigDataEnvironmentPostProcessor` 在 **refresh 之前的 `prepareEnvironment`** 阶段加载（二.2.4 重点一、四.4.2）——所以 `logging.level.*`、`spring.main.*` 在容器还不存在时就已经可读可回绑。`@PropertySource` 才是 refresh 阶段才生效的"迟到者"（这也是它配不了 logging 的原因）。

## 9.3 Boot 的三板斧（全篇收拢）

把八 个章节的所有机制收拢，Boot 反复使用的只有三个手法：

1. **SPI 注册文件**：`spring.factories`（容器诞生前的基础设施：EPP、Listener、FailureAnalyzer……）+ `<注解FQN>.imports`（自动配置、测试切片、管理上下文）。钥匙是两行代码：`SpringFactoriesLoader.load(type)` 与 `ImportCandidates.load(annotation)`（八.8.0）。
2. **事件驱动**：7 个启动事件把 run() 的每一步广播出去，EnvironmentPostProcessor 借事件在"无容器时代"执行（八.8.1-8.3），K8s 探针语义借 AvailabilityChangeEvent 落地（二.2.5）。
3. **Framework 扩展点的组合复用**：自动配置 = DeferredImportSelector + @Conditional；属性绑定 = BeanPostProcessor；内嵌服务器 = onRefresh 钩子 + SmartLifecycle；定制 = Customizer（BPP 的类型安全收窄）。

一句话总结：**Boot 的全部价值，在于把 Framework"留白的扩展点"按最常见的工程需求编排成默认值，并且让每一个默认值都能被看到（报告/事件/Origin）、被理解（FailureAnalyzer/conditions 端点）、被覆盖（@ConditionalOnMissingBean/Customizer）。** 这也是它从 2014 年至今主导 Java 开发的根本原因——它没有发明新东西，它把已有的一切组装得恰到好处。

---

# 十、附录

## 10.1 关键类速查表（相对 `D:\code\3rd\spring-boot`）

| 类 / 文件 | 模块 | 一句话 |
|---|---|---|
| SpringApplication | core/spring-boot | 启动编排本体：构造器"认清自己"+ run() 19 步 |
| WebApplicationType | core/spring-boot | SERVLET/REACTIVE/NONE 推断（4.x SPI 化 deduce()） |
| EventPublishingRunListener | core/spring-boot | 唯一 RunListener：7 个启动事件的发布者 |
| EnvironmentPostProcessorApplicationListener | core/spring-boot | EPP 的事件化触发器（HIGHEST+10） |
| ConfigDataEnvironmentPostProcessor / ConfigDataEnvironment | core/spring-boot | application.yml 的加载引擎 |
| Binder / JavaBeanBinder / ValueObjectBinder | core/spring-boot | 类型安全绑定核心 |
| ConfigurationPropertiesBindingPostProcessor | core/spring-boot | @ConfigurationProperties 绑定入口（BPP） |
| ConfigurationPropertyName | core/spring-boot | 宽松绑定的命名规范化 |
| ImportCandidates | core/spring-boot | *.imports 文件通用读取器 |
| AutoConfigurationImportSelector / AutoConfigurationGroup | core/spring-boot-autoconfigure | 自动配置选择器（DeferredImportSelector） |
| SpringBootCondition / OnClassCondition / OnBeanCondition | core/spring-boot-autoconfigure | 条件评估模板与两大主力 |
| ConditionEvaluationReport | core/spring-boot-autoconfigure | 条件评估"账本"（/actuator/conditions） |
| AutoConfigurationSorter | core/spring-boot-autoconfigure | 自动配置拓扑排序 |
| ServletWebServerApplicationContext（module/spring-boot-web-server） | web-server | onRefresh 造服务器 + final refresh 兜底 |
| WebServerStartStopLifecycle | web-server | SmartLifecycle：端口绑定的真正时机 |
| TomcatServletWebServerFactory / TomcatWebServer | module/spring-boot-tomcat | Tomcat 工厂与"半启动"实现 |
| ServletContextInitializerBeans | core/spring-boot | Servlet/Filter bean → initializer 适配器 |
| BasicErrorController / ErrorMvcAutoConfiguration | module/spring-boot-webmvc | /error 与白标页 |
| EndpointDiscoverer / @Endpoint | module/spring-boot-actuator | 端点发现与定义（协议无关） |
| HealthEndpoint / HealthIndicator（module/spring-boot-health） | health | 健康聚合 |
| MetricsAutoConfiguration / MeterRegistryPostProcessor | module/spring-boot-micrometer-metrics | Micrometer 装配 |
| JarLauncher / Launcher / ZipContent | loader/spring-boot-loader | 可执行 jar 的加载链 |
| Repackager / Layouts | loader/spring-boot-loader-tools | fat jar 打包 |
| Restarter / RestartClassLoader | module/spring-boot-devtools | 秒级重启的类加载器分区 |
| SpringBootContextLoader / SpringBootTestContextBootstrapper | core/spring-boot-test | 测试与生产的"同一条流水线" |
| TypeExcludeFilter | core/spring-boot | 切片测试的扫描过滤器挂载点 |

## 10.2 与《Spring Framework 深度源码解析》的对照阅读

| Boot 的概念 | 落在 Framework 的哪个机制上 | 分别详见 |
|---|---|---|
| 自动配置 | @Import + DeferredImportSelector（Framework 第七/四章） | Boot 三.3.3 ↔ FW 四.4.4.3 |
| @ConditionalOnMissingBean | Condition 评估（ConfigurationClassParser 管线） | Boot 三.3.4 ↔ FW 四.4.3 |
| 属性绑定 | BeanPostProcessor + ConversionService | Boot 四.4.3 ↔ FW 三.3.5、七.7.2 |
| 内嵌服务器启动 | refresh 的 onRefresh/finishRefresh 模板方法 + SmartLifecycle | Boot 五.5.2-5.4 ↔ FW 四.4.3、四.6.2 |
| 事件体系 | ApplicationEventMulticaster（Boot 早期事件用独立多播器） | Boot 二.2.5 ↔ FW 四.4.5 |
| @SpringBootTest | TestContext framework + ApplicationContext | Boot 七.7.5 ↔ FW 六.6.7 |
| 自定义 starter | BeanDefinitionRegistryPostProcessor + ImportSelector | Boot 八.8.11 ↔ FW 七.7.1/7.5 |

## 10.3 初学者学习路线（动手向）

1. **跑起来再拆开（第 1 天）**：写一个最简 `@SpringBootApplication`，断点 `SpringApplication` 构造器与 `run()`，对照第二章 19 步表逐步观察；在 `ConfigDataEnvironmentPostProcessor.postProcessEnvironment` 打断点看 application.yml 何时进 Environment。
2. **抓住自动配置（第 2~3 天）**：断点 `AutoConfigurationGroup.selectImports` 与 `OnClassCondition.getMatchOutcome`；写一个 `@ConditionalOnMissingBean` 的自定义 @Bean 观察"让位"；访问 `/actuator/conditions`（配 `management.endpoints.web.exposure.include=conditions`）对照报告。
3. **配置绑定（第 4 天）**：写一个 record 构造器绑定的 `@ConfigurationProperties`，故意绑错类型看 `ConfigurationPropertiesBindException` 与 FailureAnalyzer 的"人话报告"；用 `@Validated` 体验启动期校验。
4. **内嵌服务器时机（第 5 天）**：在 `TomcatWebServer.initialize()` 和 `WebServerStartStopLifecycle.start()` 各打一个断点，验证"半启动→finishRefresh 才绑端口"；用 `server.port=0` + `WebServerInitializedEvent` 拿随机端口。
5. **自己写 starter（第 2 周）**：按第八章 8.11 的四步模板写 `xxx-spring-boot-starter`（模块 + 自动配置 + imports 文件 + 条件注解），用另一个项目引入验证条件装配与 IDE 元数据提示。
6. **运维实践（第 3 周）**：加 actuator starter，逐个看 health/metrics/conditions 端点；写一个自定义 `@Endpoint`；用 `management.endpoints.web.exposure.include` 练习最小暴露。
7. **进阶**：读 `ZipContent.loadNestedZip` 理解嵌套 jar 的字节级实现；读 `RestartClassLoader` 理解类加载器分区；把 devtools 与 AOT/native（RuntimeHintsRegistrar）作为下一个深入方向。

---

## 结语

回到开篇：Spring Boot 解决了什么？

它用 **starter** 回答了"依赖该怎么组合"，用 **自动配置** 回答了"Bean 该谁来注册"，用 **ConfigData + Binder** 回答了"配置从哪来、怎么变强类型"，用 **可执行 jar + 内嵌容器** 回答了"应用怎么交付"，用 **Actuator** 回答了"跑起来之后怎么看"，用 **FailureAnalyzer 与事件** 回答了"出问题时怎么理解"。

而这一切的共同底座，是 Framework 那份"处处留口子"的慷慨：DeferredImportSelector、BeanPostProcessor、SmartLifecycle、事件多播器——Boot 没有改动容器的一行逻辑，它只是那个把口子用到极致的"总装配师"。读完两篇文档，希望你看 Spring 应用时，眼里不再有"魔法"，只有一条条可以被断点验证的、清晰的流水线。


---

