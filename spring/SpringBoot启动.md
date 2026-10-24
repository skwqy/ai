# Spring Boot 启动过程与启动优化

> **本文要回答的两件事**：一次 `SpringApplication.run()` 到底按什么顺序把进程变成可接流量的应用；启动慢的时候，时间耗在哪一段，官方机制和业界实践各自动的是哪一段。
>
> **源码版本与行号约定**（三处来源，行号各自精确，不要混用）：
>
> | 来源 | 版本 | 本文用它覆盖的内容 |
> |---|---|---|
> | 本地 `D:\code\3rd\spring-boot` | **4.2.0-SNAPSHOT**，Git commit `11778bdc24f`（2026-10-23）。`gradle.properties` 声明 `springFrameworkVersion=7.1.0-RC1` | `SpringApplication.java`、`META-INF/spring.factories`、`ConfigDataEnvironment*`。该目录**不是完整检出**（`core/spring-boot` 下目前只有启动主类和 ConfigData 相关文件） |
> | Maven `spring-boot-*-4.2.0-RC1-sources.jar` | 4.2.0-RC1（与本地快照同一代；`SpringApplication.run` / `prepareContext` / `refresh` 在第 400 行之前与本地快照逐行一致。第 401 行起仅 `KeepAlive` 构造参数不同：本地快照为 `new KeepAlive(context)`，RC1 为 `new KeepAlive()`；第 1703 行之后内部类有增补） | 事件发布、日志监听、BeanDefinition 装载、懒初始化、自动配置选择器、内嵌 Tomcat、Startup 端点、AOT 处理入口、后台预初始化 |
> | Framework | 本地 `D:\code\3rd\spring-framework` 的 `AbstractApplicationContext`、`ConfigurationClassParser` 为 **7.1.0-SNAPSHOT**；`AotDetector`、`SpringProperties`、`DefaultLifecycleProcessor`、`DefaultListableBeanFactory`、`Bean`、`SmartLifecycle` 本地树缺失，取 **7.1.0-RC1** sources（与 Boot 声明的 Framework 版本一致） | `refresh()` 十二步、延迟导入、单例预实例化、CRaC / exit、后台 Bean |
>
> 配套阅读：同目录《Spring Boot 深度源码解析》（`Spring Boot.md`）覆盖自动配置、配置绑定、Actuator 的全景；《Spring Framework 深度源码解析》覆盖 `refresh()` 与 Bean 生命周期。本文只沿**启动关键路径**展开，并在后半专门写优化。
>
> **阅读约定**：先白话、后【源码证据】。路径写法为 `模块/.../类名.java`。Boot 类若无"本地"字样，行号对 4.2.0-RC1 sources jar；`SpringApplication.java` 的行号对本地快照。

## 如何读这份文档

- **第一遍（建立时间线，约 40 分钟）**：读第一章的时间线总表、1.4 的两条时钟、第八章的成本地图、第十一章的优化顺序。目标是能回答：配置文件在 `refresh()` 之前还是之后加载？端口在哪一步才绑定？`Started` 和 `process running for` 为什么不是同一个数？`ApplicationReadyEvent` 为什么晚于端口打开？
- **第二遍（对照源码）**：第二至七章按 `run()` 顺序读。第九章只读你打算启用的那一种优化的源码段，再回到第十章看它在业界怎么落地、会伤到什么。

---

# 一、总览：启动在 Boot 里是什么

## 1.1 一句话定位

**Spring Boot 的启动，是在调用 Framework `refresh()` 之前把 Environment、主配置类、容器类型备齐，再在 `refresh()` 内部用 Framework 已有扩展点完成自动配置、单例创建和内嵌服务器绑定。** `SpringApplication.refresh()` 本身只有一行 `applicationContext.refresh()`。所谓启动魔法，是这条时间线上的编排，不是另一个容器。

## 1.2 设计哲学：看启动源码前先记住四句

1. **Boot 不重写容器，只占 `refresh()` 前后的空档。** 构造器装 SPI，`run()` 准备 Environment 和 BeanDefinition，然后把方向盘交还 `AbstractApplicationContext.refresh()`。
2. **刷新前的事件用 Boot 自己的多播器，刷新后的事件用容器。** `ApplicationStartingEvent` 到 `ApplicationPreparedEvent` 发生时容器还不能 `publishEvent`，所以 `EventPublishingRunListener` 持有一个 `SimpleApplicationEventMulticaster`。从 `ApplicationStartedEvent` 起改走 `context.publishEvent`。
3. **“能改启动行为”的配置，必须在回绑 `spring.main` 之前进入 Environment。** `application.yml` 能关掉 banner、打开懒初始化，是因为 ConfigData 在 `bindToSpringApplication` 之前执行。反过来，`spring.aot.enabled`、`spring.context.exit`、`spring.context.checkpoint` 走的是 `SpringProperties`（classpath 上的 `spring.properties` 和 `-D`），**不是** `Environment`。写进 `application.yml` 不会生效。
4. **端口打开、存活、就绪是三件不同的事。** Tomcat 在 `onRefresh` 里被 `start()` 但拆掉 Connector；真正 `bind` 在 `finishRefresh` 的 `SmartLifecycle`；`LivenessState.CORRECT` 在 Runner 之前；`ReadinessState.ACCEPTING_TRAFFIC` 在 Runner 之后。用端口探活会把“还在跑迁移”误判成“可以接流量”。

## 1.3 启动时间线全景

下面这张表是全文地图。左列是墙钟上的顺序，右列是这一步第一次变得可能的事。

| 顺序 | 发生了什么 | 此时还不能假设什么 |
|---|---|---|
| 0 | `new SpringApplication(primarySources)`：推断 Web 类型、装载 `spring.factories` 里的初始化器、监听器、`BootstrapRegistryInitializer`，用 `StackWalker` 找 `main` 类 | 还没有 Environment，还没有容器 |
| 1 | `Startup.create()` 记下起点；允许后续注册 shutdown hook | 计时从这一刻起，不含 JVM 启动和类加载到 `main` 之前的时间 |
| 2 | `createBootstrapContext()`，跑 `BootstrapRegistryInitializer` | 给启动早期（例如配置导入）放对象的地方，还不是 BeanFactory |
| 3 | `listeners.starting` → **ApplicationStartingEvent** | 日志系统只做 `beforeInitialize()`；**不能读 `application.yml`** |
| 4 | `prepareEnvironment`：创建 Environment、放入命令行参数、`environmentPrepared` | |
| 4a | **ApplicationEnvironmentPreparedEvent** → `EnvironmentPostProcessor`（含 ConfigData）→ 日志按配置正式初始化 → 后台预初始化线程启动 | 容器仍不存在 |
| 4b | `bind("spring.main", properties)`：`lazy-initialization`、`banner-mode`、`web-application-type`、`keep-alive` 等在这里才改变 `run()` 后半段的行为 | 写在 yml 里的 `spring.main.*` 对步骤 0–4a 来不及 |
| 5 | 打印 Banner | |
| 6 | `createApplicationContext()`：按 Web 类型 new 出**空**容器 | 没有任何业务 Bean |
| 7 | `prepareContext`：挂 Environment、跑 `ApplicationContextInitializer`、发布 **ApplicationContextInitializedEvent**、注册主类 BeanDefinition、发布 **ApplicationPreparedEvent** | AOT 模式下跳过 `load(sources)` |
| 8 | `refreshContext` → Framework `refresh()` | 见 1.3.1 |
| 9 | `startup.started()`，日志 `Started ... in X seconds (process running for Y)` | X 不含 Runner |
| 10 | **ApplicationStartedEvent** + `AvailabilityChangeEvent(LivenessState.CORRECT)` | 存活已上报，业务 Runner 还没跑 |
| 11 | `callRunners`：`ApplicationRunner` / `CommandLineRunner` | 这段会计入“进程已运行”，**不计入** Started 的 X |
| 12 | 若 `context.isRunning()`：**ApplicationReadyEvent** + `ReadinessState.ACCEPTING_TRAFFIC` | 这才是“可以接流量” |
| 失败 | 任一步抛错 → `handleRunFailure`：退出码、**ApplicationFailedEvent**、`FailureAnalyzers` 报告、`context.close()` | |

### 1.3.1 `refresh()` 内部与启动相关的四步

Framework `refresh()` 的骨架在本地 `AbstractApplicationContext.java` 第 582–625 行。和启动耗时、端口、自动配置直接相关的是这四步：

| refresh 步骤 | 行号（本地 Framework 快照） | 启动上的含义 |
|---|---|---|
| `invokeBeanFactoryPostProcessors` | 604 | 解析 `@SpringBootApplication`，**延迟导入**自动配置，条件注解在这里评估 |
| `onRefresh` | 616 | Servlet 容器在这里 `createWebServer()`：Tomcat 对象被 `start()`，但 Connector 被拆下，**端口尚未绑定** |
| `finishBeanFactoryInitialization` | 622 | `preInstantiateSingletons()`：非懒单例在这里创建。多数启动时间在这里 |
| `finishRefresh` | 625 | `LifecycleProcessor.onRefresh()`：按 phase 升序启动 `SmartLifecycle`，**端口在这一阶段绑定**；然后发 `ContextRefreshedEvent` |

## 1.4 两条时钟：不要把日志上的两个数当成同一个

`StartupInfoLogger.getStartedMessage`（RC1 `StartupInfoLogger.java` 第 73–85 行）拼出的句子是：

```text
Started <App> in X seconds (process running for Y)
```

- **X** = `System.currentTimeMillis()` 减去 `Startup.startTime()`，终点是 `run()` 里 `startup.started()`，位置在 `callRunners` **之前**（本地 `SpringApplication.java` 第 311–316 行）。
- **Y** = `ManagementFactory.getRuntimeMXBean().getUptime()`，从 JVM 启动算起，包含 JVM 初始化、classpath 扫描到进入 `main`、以及 Boot 自己的 X。
- CRaC 恢复后走另一个实现：`action()` 变成 `"Restored"`，`startTime()` 改用 `CRaCMXBean.getRestoreTime()`，uptime 改用 `getUptimeSinceRestore()`（本地 `SpringApplication.java` 第 1806–1832 行）。所以同一行日志在 checkpoint 恢复后度量的是**恢复后的时间**，不是当初训练运行的时间。

因此：Y 比 X 大一截，多出来的主要是 JVM 与类加载，不是 Spring“又偷偷做了什么”。优化 X 解决不了 Y 里的类加载；优化 Y 要动 CDS / AOT cache / Native Image。Runner 里的迁移只拉长 Y 和就绪时间，不拉长 X。

## 1.5 关键问题 → 启动阶段映射

| 你想搞清的问题 | 答案所在 | 详见 |
|---|---|---|
| `main` 到空容器之间 Boot 做了什么 | `SpringApplication` 构造器 + `run()` 前半 | 第二、三章 |
| `application.yml` 何时进入 Environment | `ApplicationEnvironmentPreparedEvent` → `ConfigDataEnvironmentPostProcessor` | 第四章 |
| 自动配置为什么让用户 Bean 优先 | `DeferredImportSelector` 被推迟到本轮配置类解析的末尾 | 第六章 |
| 端口为何在单例创建之后才监听 | `onRefresh` 半启动，`SmartLifecycle.start()` 才 `addConnector` | 第六章 |
| 启动失败为什么有时是人话而不是纯堆栈 | `FailureAnalyzers` | 第七章 |
| 怎么知道时间耗在哪个 Bean / 哪个自动配置 | `ApplicationStartup` 的 StartupStep + Actuator `startup` 端点 | 第八、九章 |
| 懒初始化会不会把 Web 服务器也推迟 | 不会。服务器工厂在 `onRefresh` 里被点名 `getBean` | 第九章 |
| 后台初始化能不能让 `refresh()` 提前返回 | 非懒单例不能。`preInstantiateSingletons` 会 `join` 全部 future | 第九章 |
| 训练运行如何在刷新后立刻停掉 JVM | `spring.context.exit=onRefresh`，且必须走 `SpringProperties` | 第九、十章 |

## 1.6 和启动直接相关的版本演进

只列本文会用到、且能在当前源码的 `@since` / 常量上对上的节点：

| 引入 | 版本（源码标注） | 启动上的意义 |
|---|---|---|
| 可执行 jar、内嵌容器、自动配置、Actuator | Boot 1.0 | 启动模型的四个原点 |
| `LazyInitializationBeanFactoryPostProcessor` | Boot 2.2.0 | 全局懒初始化 |
| ConfigData、`StartupEndpoint` | Boot 2.4.0 | 配置加载重做；启动时间线可查询 |
| `AotDetector` / 生成式 `ApplicationContextInitializer` | Framework 6.0 / Boot 3.0 | 运行期可以不再做 classpath 扫描式解析 |
| `spring.context.checkpoint`、`spring.context.exit` | Framework 6.1（`DefaultLifecycleProcessor` 常量 `@since 6.1`） | CRaC 与 CDS 训练运行的钩子 |
| `@Bean(bootstrap = BACKGROUND)` | Framework 6.2 | 单例预实例化可以重叠 |
| 同一 phase 的并发启动 + 超时 | Framework 6.2.6（`setConcurrentStartupForPhase`） | `SmartLifecycle` 组内并发，需要 bootstrap executor |
| `BackgroundPreinitializer` 拆成接口 + 各模块实现 | Boot 4.0.0 | 预热从核心挪到有那个依赖的模块（例如 Tomcat） |
| `WebApplicationType.deduce()` 改为 SPI | Boot 4.0.1 | servlet / reactive 模块各自注册 `Deducer`，核心不再硬编码 DispatcherServlet |

## 1.7 章节地图

| 章 | 内容 |
|---|---|
| 二 | 构造器：还没 `run` 就已经决定的事 |
| 三 | `run()` 总装与失败路径 |
| 四 | Environment、ConfigData、`spring.main` 回绑 |
| 五 | 空容器、初始化器、主类如何变成 BeanDefinition |
| 六 | `refresh()` 里的自动配置、单例、Tomcat 半启动与绑端口 |
| 七 | Started、Runner、Ready、FailureAnalyzers |
| 八 | 启动时间通常耗在哪 |
| 九 | 官方机制里的优化开关（全部带源码） |
| 十 | 业界实践：CDS、Leyden、CRaC、Native、镜像、探针、远程配置 |
| 十一 | 建议的优化顺序，以及一张贯通时间线 |

---

# 二、入口：构造器在 `run()` 之前做完的事

## 2.1 模块定位

启动编排的核心类都在 `spring-boot` 这个 jar（Boot 4 的 `core/spring-boot`）：`SpringApplication`、`SpringApplicationRunListeners`、`EventPublishingRunListener`、`BeanDefinitionLoader`、`ApplicationProperties`。自动配置选择器在 `spring-boot-autoconfigure`。内嵌服务器在 `spring-boot-web-server` 与 `spring-boot-tomcat`。启动时间线端点在 `spring-boot-actuator`。

用户入口通常是：

```java
// 本地 SpringApplication.java 第 1341–1353 行
public static ConfigurableApplicationContext run(Class<?> primarySource, String... args) {
    return run(new Class<?>[] { primarySource }, args);
}

public static ConfigurableApplicationContext run(Class<?>[] primarySources, String[] args) {
    return new SpringApplication(primarySources).run(args);
}
```

静态方法没有额外逻辑：先构造，再 `run`。要改 banner、懒初始化、监听器，必须拿到 `SpringApplication` 实例，在 `run` 之前改——或者依赖后面的 `spring.main` 回绑（那时候构造阶段该做的事已经做完了）。

## 2.2 构造器三件事

本地 `SpringApplication.java` 第 262–272 行：

```java
public SpringApplication(@Nullable ResourceLoader resourceLoader, Class<?>... primarySources) {
    this.resourceLoader = resourceLoader;
    Assert.notNull(primarySources, "'primarySources' must not be null");
    this.primarySources = new LinkedHashSet<>(Arrays.asList(primarySources));
    this.properties.setWebApplicationType(WebApplicationType.deduce());
    this.bootstrapRegistryInitializers = new ArrayList<>(
            getSpringFactoriesInstances(BootstrapRegistryInitializer.class));
    setInitializers((Collection) getSpringFactoriesInstances(ApplicationContextInitializer.class));
    setListeners((Collection) getSpringFactoriesInstances(ApplicationListener.class));
    this.mainApplicationClass = deduceMainApplicationClass();
}
```

**（1）推断是不是 Web 应用。** Boot 4.0.1 起核心不再自己比较 DispatcherServlet 和 WebFlux。`WebApplicationType.deduce()`（RC1 第 51–58 行）先问 `spring.factories` 里的 `WebApplicationType.Deducer`，都没给出结论时，仅当 `jakarta.servlet.Servlet` 和 `ConfigurableWebApplicationContext` 都在 classpath 上才判为 `SERVLET`，否则 `NONE`。reactive 是否优先，由 webflux 模块注册的 Deducer 决定，不在核心枚举里硬编码。

**（2）从 `META-INF/spring.factories` 装三类 SPI。** 本地 `core/spring-boot/src/main/resources/META-INF/spring.factories` 里和启动直接相关的登记：

| key | 实现 | 何时被调用 |
|---|---|---|
| `SpringApplicationRunListener` | `EventPublishingRunListener` | `run()` 的每个阶段 |
| `ApplicationContextInitializer` | `ConfigurationWarningsApplicationContextInitializer`、`ContextIdApplicationContextInitializer`、`ProtocolResolverApplicationContextInitializer` | `prepareContext` → `applyInitializers` |
| `ApplicationListener` | `ClearCachesApplicationListener`、`ParentContextCloserApplicationListener`、`FileEncodingApplicationListener`、`LoggingApplicationListener`、`AnsiOutputApplicationListener`、`EnvironmentPostProcessorApplicationListener` | 随事件。前四个事件由内部多播器直接调到这些监听器 |
| `EnvironmentPostProcessor` | `CloudFoundryVcapEnvironmentPostProcessor`、`ConfigDataEnvironmentPostProcessor`、`RandomValuePropertySourceEnvironmentPostProcessor`、`SpringApplicationJsonEnvironmentPostProcessor`、`SystemEnvironmentPropertySourceEnvironmentPostProcessor` | 监听器在 `ApplicationEnvironmentPreparedEvent` 里逐个调用 |
| `SpringBootExceptionReporter` | `FailureAnalyzers` | 仅失败路径 |

`getSpringFactoriesInstances`（本地第 455–461 行）就是 `SpringFactoriesLoader.forDefaultResourceLocation(classLoader).load(type)`。Boot 自己的启动插件和用户的启动插件走同一条 SPI。

**（3）找主类。** `deduceMainApplicationClass`（本地第 274–284 行）用 `StackWalker` 沿栈找第一个方法名是 `main` 的帧。它用于启动日志里的应用名、AOT 生成类名（`主类名__ApplicationContextInitializer`），不是用来决定扫哪个包。扫包的根是 `@SpringBootApplication` 所在类，由 `@ComponentScan` 的默认策略决定。

## 2.3 本章小结

构造器结束时，进程里有一份主源列表、一个 Web 类型的猜测、三组成品 SPI，以及一个可能为 null 的主类。没有 Environment，没有 ApplicationContext，没有读过 `application.yml`。

---

# 三、`run()`：总装车间

## 3.1 主干

本地 `SpringApplication.java` 第 292–329 行，删去空行后的控制流就是启动的全部阶段：

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
        if (this.properties.isLogStartupInfo()) {
            new StartupInfoLogger(this.mainApplicationClass, environment).logStarted(getApplicationLog(), startup);
        }
        listeners.started(context, timeTakenToStarted);
        callRunners(context, applicationArguments);
    }
    catch (Throwable ex) {
        throw handleRunFailure(context, ex, listeners);
    }
    try {
        if (context.isRunning()) {
            listeners.ready(context, startup.ready());
        }
    }
    catch (Throwable ex) {
        throw handleRunFailure(context, ex, null);
    }
    return context;
}
```

几点容易看漏：

- `afterRefresh`（本地第 752–753 行）是空方法，留给子类，Boot 自己不在这里做事后处理。
- `ready` 在 `try` 之外的第二个 `try` 里，并且要求 `context.isRunning()`。Runner 抛错走第一个 catch，不会发布 Ready。Runner 成功但应用在 refresh 期间没被标成 running（例如非 Web 且没有 keep-alive，主线程即将退出）时，也不会发布 Ready。
- 第二个 catch 调用 `handleRunFailure` 时 listeners 传 `null`，因此 Ready 阶段的失败**不再**发 `ApplicationFailedEvent`，但仍会做 FailureAnalyzers 和 `context.close()`。
- 每个监听器回调都被包进一个 StartupStep。步骤名是固定的：`spring.boot.application.starting`、`environment-prepared`、`context-prepared`、`context-loaded`、`started`、`ready`、`failed`（RC1 `SpringApplicationRunListeners.java` 第 43–82 行）。把 `ApplicationStartup` 换成 `BufferingApplicationStartup` 之后，这些名字会出现在 `/actuator/startup` 里。

## 3.2 计时器如何选择实现

本地第 1768–1772 行：

```java
static Startup create() {
    ClassLoader classLoader = Startup.class.getClassLoader();
    return (ClassUtils.isPresent("jdk.crac.management.CRaCMXBean", classLoader)
            && ClassUtils.isPresent("org.crac.management.CRaCMXBean", classLoader))
                    ? new CoordinatedRestoreAtCheckpointStartup() : new StandardStartup();
}
```

两个类都在才走 CRaC 实现。只引入 `org.crac:crac` 而 JVM 不是 CRaC JDK 时，`jdk.crac.management.CRaCMXBean` 不存在，仍然用 `StandardStartup`。这只影响日志里的 “Started/Restored” 和 uptime 口径，不负责真正发起 checkpoint。发起 checkpoint 的是 Framework 的 `DefaultLifecycleProcessor`（第九章）。

## 3.3 事件由谁发出

`EventPublishingRunListener`（RC1 第 63–100 行）是 `spring.factories` 里唯一的 `SpringApplicationRunListener`。刷新前四个事件走内部多播器；`started` / `ready` 走容器，并各带一个可用性事件：

```java
public void started(ConfigurableApplicationContext context, @Nullable Duration timeTaken) {
    context.publishEvent(new ApplicationStartedEvent(this.application, this.args, context, timeTaken));
    AvailabilityChangeEvent.publish(context, LivenessState.CORRECT);
}

public void ready(ConfigurableApplicationContext context, @Nullable Duration timeTaken) {
    context.publishEvent(new ApplicationReadyEvent(this.application, this.args, context, timeTaken));
    AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
}
```

`contextLoaded` 还会把构造期装入的 `ApplicationListener` 注册进容器（第 80–88 行），这样 `ContextRefreshedEvent`、`ContextClosedEvent` 才能被 `LoggingApplicationListener` 等收到。

失败时如果容器已经 active，用容器发布 `ApplicationFailedEvent`；否则把容器上已有的监听器抄回内部多播器再发（第 103–120 行）。启动很早失败（容器还是 null）时，仍然能通知到 `spring.factories` 里的监听器。

## 3.4 失败路径

本地 `handleRunFailure` 第 791–816 行的顺序是固定的：

1. `AbandonedRunException` 原样抛出。AOT 处理用它在 `contextLoaded` 主动中断启动（见 9.4），这不是故障。
2. `handleExitCode`：从 `ExitCodeExceptionMapper` Bean 或异常上的 `ExitCodeGenerator` 取退出码，向容器发布 `ExitCodeEvent`，并记到 `SpringBootExceptionHandler`。
3. `listeners.failed` → `ApplicationFailedEvent`。
4. `finally`：`FailureAnalyzers`（`spring.factories` 的 `SpringBootExceptionReporter`）尝试把异常翻译成 “description + action”；然后 `context.close()`，并 `shutdownHook.deregisterFailedApplicationContext`。

`FailureAnalyzers` 分析不了的异常，才落到 `logger.error("Application run failed", failure)`。Native Image 里日志可能还没起来，改打到 `System.out`（本地第 843–848 行）。

## 3.5 本章小结

`run()` 是一条直线加上两个 catch。直线上的分界点是 `refreshContext`：它左边是 Boot 的准备，右边是 Framework 的容器生命周期，再往右是 Runner 和就绪。计时终点在 Runner 之前。

---

# 四、Environment：配置文件与 `spring.main` 回绑

## 4.1 `prepareEnvironment` 的顺序就是优先级的来源

本地第 338–355 行：

```java
private ConfigurableEnvironment prepareEnvironment(...) {
    ConfigurableEnvironment environment = getOrCreateEnvironment();
    configureEnvironment(environment, applicationArguments.getSourceArgs());
    ConfigurationPropertySources.attach(environment);
    listeners.environmentPrepared(bootstrapContext, environment);
    ApplicationInfoPropertySource.moveToEnd(environment);
    DefaultPropertiesPropertySource.moveToEnd(environment);
    Assert.state(!environment.containsProperty("spring.main.environment-prefix"),
            "Environment prefix cannot be set via properties.");
    bindToSpringApplication(environment);
    if (!this.isCustomEnvironment) {
        environment = environmentConverter.convertEnvironmentIfNecessary(environment, deduceEnvironmentClass());
    }
    ConfigurationPropertySources.attach(environment);
    return environment;
}
```

`getOrCreateEnvironment`（第 463–472 行）优先用调用方 `setEnvironment` 放进来的实例；否则问 `ApplicationContextFactory`。默认工厂（RC1 `DefaultApplicationContextFactory.java` 第 36–38 行）再问 `spring.factories` 里的工厂。servlet 模块会因此贡献 `ApplicationServletEnvironment`。都没有时退回 `new ApplicationEnvironment()`。

`configureEnvironment`（第 486–491 行）做两件事：装上 `ApplicationConversionService`（`Duration`、`DataSize` 这类 Boot 转换在此后可用），然后 `configurePropertySources`。

`configurePropertySources`（第 501–520 行）把两样东西放进 `PropertySources`：

- 若有命令行参数，`SimpleCommandLinePropertySource` **addFirst**。命令行覆盖文件，就是这一行。
- `ApplicationInfoPropertySource` **addLast**，携带主类名等低优先级信息。随后 `moveToEnd` 会把默认属性和这份 info 再推到末尾，避免它们盖住 ConfigData 刚加载的内容。

`configureProfiles`（第 531–533 行）在 4.2 里是空方法。profile 的激活改由 ConfigData 在 `environmentPrepared` 期间完成，而不是在这里预写。

## 4.2 ConfigData 在哪一步跑

`listeners.environmentPrepared` 发布 `ApplicationEnvironmentPreparedEvent`。`EnvironmentPostProcessorApplicationListener`（RC1 第 118–126 行）收到后取出全部 `EnvironmentPostProcessor` 并调用：

```java
private void onApplicationEnvironmentPreparedEvent(ApplicationEnvironmentPreparedEvent event) {
    ConfigurableEnvironment environment = event.getEnvironment();
    SpringApplication application = event.getSpringApplication();
    List<EnvironmentPostProcessor> postProcessors = getEnvironmentPostProcessors(
            application.getResourceLoader(), event.getBootstrapContext());
    addAotGeneratedEnvironmentPostProcessorIfNecessary(postProcessors, application);
    for (EnvironmentPostProcessor postProcessor : postProcessors) {
        postProcessor.postProcessEnvironment(environment, application);
    }
}
```

AOT 模式下，若 classpath 上存在 `主类名__EnvironmentPostProcessor` 形式的生成类，会被插到列表头（同文件第 148–157 行）。普通 JVM 模式没有这个类，行为与以前一致。

本地 `ConfigDataEnvironmentPostProcessor` 的 order 是 `Ordered.HIGHEST_PRECEDENCE + 10`（第 40、72–74 行）。`postProcessEnvironment`（第 77–85 行）创建 `ConfigDataEnvironment` 并 `processAndApply()`。本地 `ConfigDataEnvironment.java` 第 224–235 行的处理顺序是：

1. 处理初始 import（还没有 profile 激活上下文）；
2. 用已有属性建立 `ConfigDataActivationContext`（这里读到 `spring.profiles.active`）；
3. 先处理**不带 profile** 的文件；
4. 再处理**带 profile** 的文件；
5. `applyToEnvironment`，把贡献的 `PropertySource` 放进 Environment。

所以“先默认文件、后 profile 文件、profile 覆盖默认”不是约定俗成，是 `processAndApply` 的调用顺序。`spring.config.import` 的展开也在这同一次 `ConfigDataImporter` 里完成。

日志系统的正式初始化也挂在同一个事件上。`LoggingApplicationListener` 的 order 是 `HIGHEST_PRECEDENCE + 20`（RC1 第 99 行），比 ConfigData 的 `+10` 靠后，因此它在 `onApplicationEnvironmentPreparedEvent`（第 231–236 行）里能读到刚刚加载的 `logging.*`。它在更早的 `ApplicationStartingEvent` 里只调用 `loggingSystem.beforeInitialize()`（第 226–228 行），避免在配置到来之前就把日志定死。

后台预初始化也挂在这个事件之后，见 9.2。

## 4.3 回绑：`application.yml` 为什么能改变后续启动行为

本地第 538–544 行：

```java
protected void bindToSpringApplication(ConfigurableEnvironment environment) {
    try {
        Binder.get(environment).bind("spring.main", Bindable.ofInstance(this.properties));
    }
    catch (Exception ex) {
        throw new IllegalStateException("Cannot bind to SpringApplication", ex);
    }
}
```

`ApplicationProperties`（RC1）上的字段就是 `spring.main.*` 的全部合法键。Binder 做宽松绑定，`lazyInitialization` 对应 `spring.main.lazy-initialization`。这一步**之后**，`run()` 才打印 banner、创建容器、决定是否注册 `LazyInitializationBeanFactoryPostProcessor`。

| 字段 | 默认 | 影响的后续步骤 |
|---|---|---|
| `bannerMode` | 未显式设置时：若开了结构化控制台日志则为 `OFF`，否则 `CONSOLE`（`getBannerMode` 第 92–99 行） | `printBanner` |
| `webApplicationType` | 构造期 `deduce()` 的结果，此处可被配置覆盖 | `createApplicationContext` |
| `lazyInitialization` | `false` | `prepareContext` 是否加懒初始化 BFPP |
| `keepAlive` | `false` | 非 daemon 的 `keep-alive` 线程，避免非 Web 进程在没有非 daemon 线程时立刻退出 |
| `logStartupInfo` | `true` | 是否打印 Starting / Started |
| `registerShutdownHook` | `true` | `refreshContext` 里是否把容器登记到 `SpringApplicationShutdownHook` |
| `allowCircularReferences` / `allowBeanDefinitionOverriding` | `false` | 写到 BeanFactory 上 |
| `sources` | 空 | 追加到主源 |

`spring.main.environment-prefix` 被显式禁止从属性设置（`prepareEnvironment` 里的 `Assert.state`），避免前缀本身又被前缀后的属性改写。

回绑之后如果用户没有自定义 Environment，还会按最终的 Web 类型把 Environment **转换成**对应子类（例如补上 servlet 专用的属性源）。转换后再次 `ConfigurationPropertySources.attach`，保证宽松绑定视图和转换后的 Environment 一致。

## 4.4 本章小结

Environment 的准备是启动里第一条“可观测、可覆盖”的流水线：命令行先进，ConfigData 次之，默认属性被推到末尾，然后 `spring.main` 回绑，最后按需要转换 Environment 类型。yml 能影响的启动行为，全部是回绑之后的那些步骤。

---

# 五、空容器与主类注册

## 5.1 创建上下文：只 new，不 refresh

本地第 567–571 行把创建委托给 `ApplicationContextFactory`。RC1 `DefaultApplicationContextFactory.create`（第 43–58 行）的规则是：

1. 遍历 `spring.factories` 里的 `ApplicationContextFactory`。servlet 模块的 `ServletWebServerApplicationContextFactory` 在类型为 `SERVLET` 时返回 `AnnotationConfigServletWebServerApplicationContext`；reactive 模块同理。某个工厂返回非 null 就用它。
2. 都返回 null（典型是 `NONE`）时走 `createDefaultApplicationContext`：未启用 AOT 产物则 `new AnnotationConfigApplicationContext()`，启用了则 `new GenericApplicationContext()`。

AOT 分支用 `GenericApplicationContext`，是因为生成的 `ApplicationContextInitializer` 会自己 `registerBeanDefinition`，不再需要注解配置应用上下文那套 classpath 扫描。这个判断来自 `AotDetector.useGeneratedArtifacts()`（Framework 7.1.0-RC1 `AotDetector.java` 第 50–52 行）：

```java
public static boolean useGeneratedArtifacts() {
    return (inNativeImage || SpringProperties.getFlag(AOT_ENABLED));
}
```

`AOT_ENABLED` 常量值是 `spring.aot.enabled`。`SpringProperties.getProperty`（`SpringProperties.java` 第 106–116 行）只查 classpath 根上的 `spring.properties`，再回退到 `System.getProperty`。它不读 `Environment`。因此 `spring.aot.enabled=true` 必须是 `-D` 或 `spring.properties`。写在 `application.yml` 里时，`createApplicationContext` 已经根据“未启用”选过容器类型了。

## 5.2 `prepareContext`：刷新前的最后一次装配

本地第 368–407 行，按执行顺序：

1. `context.setEnvironment(environment)`。
2. `postProcessApplicationContext`：若指定了 `BeanNameGenerator` 或 `ResourceLoader`，写进容器；把 Environment 上的 `ConversionService` 设给 BeanFactory。
3. `addAotGeneratedInitializerIfNecessary`：AOT 模式下把 `主类__ApplicationContextInitializer` 插到初始化器列表**最前**（第 409–422 行）。类不存在则抛 `AotInitializerNotFoundException`，而不是静默退回普通模式。Native Image 里额外检查 JVM 版本（`NativeImageRequirementsException`）。
4. 把 `spring.main.allow-circular-references` 和 `allow-bean-definition-overriding` 写到 BeanFactory。两者默认都是 false，这是 Boot 比“裸 Framework 默认允许覆盖”更严的地方。
5. `applyInitializers`：构造期从 `spring.factories` 加载的初始化器逐个 `initialize(context)`。
6. `listeners.contextPrepared` → `ApplicationContextInitializedEvent`。此时主类 BeanDefinition **还没**注册。
7. `bootstrapContext.close(context)`：引导期放进 `BootstrapRegistry` 的对象移交给容器。
8. 打印 `Starting ...` 和当前 profile（`logStartupInfo` / `logStartupProfileInfo`）。`Starting` 日志在 refresh 之前，所以它不包含容器刷新时间；真正的耗时在后面的 `Started`。
9. 注册两个单例：`springApplicationArguments`、（若打印过 banner）`springBootBanner`。业务代码可以注入 `ApplicationArguments`，就是这个单例。
10. `spring.main.lazy-initialization=true` 时注册 `LazyInitializationBeanFactoryPostProcessor`（见 9.1）。
11. `spring.main.keep-alive=true` 时注册 `KeepAlive`。本地快照的实现会在 `ContextRefreshedEvent` 上起一个名为 `keep-alive` 的非 daemon 线程，在 `Thread.sleep(Long.MAX_VALUE)` 上堵住，并在 `ContextClosedEvent` 上 interrupt。它先比对事件的 ApplicationContext，避免父容器的刷新误触发（第 1698–1733 行）。
12. 注册 `PropertySourceOrderingBeanFactoryPostProcessor`，在刷新早期把属性源顺序再固定一次。
13. **若未使用 AOT 产物**：`load(context, sources)`。AOT 模式下这段被跳过（第 400–405 行），BeanDefinition 改由生成的初始化器在步骤 3 注册。
14. `listeners.contextLoaded` → `ApplicationPreparedEvent`。

`load` → `BeanDefinitionLoader.load(Class)`（RC1 第 143–151 行）对主类调用 `annotatedReader.register(source)`。`@SpringBootApplication` 主类在这一刻变成 BeanDefinition，但类上的 `@Import`、`@ComponentScan` 还没被解析。解析发生在下一步 `refresh()` 的配置类后处理器里。

`BeanDefinitionLoader` 同时接受四种源：`Class`（注解注册）、`Resource`（xml/groovy）、`Package`（扫描）、`CharSequence`（按类名、资源路径、包名依次尝试，第 172–192 行）。`spring.main.sources` 追加的就是最后一种。

## 5.3 `@SpringBootApplication` 在启动里实际贡献什么

注解本身不包含启动逻辑。RC1 `SpringBootApplication.java` 第 42–45 行它是三个注解的组合：

```java
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(excludeFilters = {
        @Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class) })
public @interface SpringBootApplication {
```

- `@SpringBootConfiguration` 是 `@Configuration` 的特化，让主类成为配置类。
- `@EnableAutoConfiguration` 上有 `@Import(AutoConfigurationImportSelector.class)`（`EnableAutoConfiguration.java` 第 65 行）。选择器是 `DeferredImportSelector`，所以自动配置不会在解析主类的中途插入，而会等到本轮用户配置类都解析完。
- `@ComponentScan` 的两个排除过滤器：测试切片用的 `TypeExcludeFilter`，以及防止把自动配置类本身再扫进组件扫描的 `AutoConfigurationExcludeFilter`。
- `proxyBeanMethods` 默认 `true`（第 140 行）。这会在刷新时为配置类生成 CGLIB 增强。用户自己的配置类若没有 `@Bean` 方法互调，改成 `false` 可以去掉这段增强，见 9.6。

`@EnableAutoConfiguration` 的 javadoc（第 31–36 行）写明两件和启动优化直接相关的事：可以用 `exclude` / `excludeName` 或属性 `spring.autoconfigure.exclude` 去掉不需要的自动配置；自动配置**总是在用户定义的 Bean 注册之后**再应用。后者就是延迟导入的语义。

## 5.4 本章小结

`prepareContext` 结束时，容器仍未刷新，但三样原料已经就位：Environment、一组会在刷新最开始跑的初始化器和 BFPP、主类 BeanDefinition（AOT 模式下改为生成的初始化器）。下一步是唯一会创建业务单例的地方。

---

# 六、`refresh()`：自动配置、单例、端口

## 6.1 Boot 把刷新交给 Framework

本地第 429–434 行与第 743–745 行：

```java
private void refreshContext(ConfigurableApplicationContext context) {
    if (this.properties.isRegisterShutdownHook()) {
        shutdownHook.registerApplicationContext(context);
    }
    refresh(context);
}

protected void refresh(ConfigurableApplicationContext applicationContext) {
    applicationContext.refresh();
}
```

shutdown hook 登记在 `refresh()` 之前，这样刷新过程中失败、`handleRunFailure` 关闭容器时，钩子侧也能配对注销。`refresh()` 的方法体没有任何 Boot 分支。

本地 Framework `AbstractApplicationContext.refresh` 第 582–625 行的骨架（异常处理略）：

```java
public void refresh() throws BeansException, IllegalStateException {
    this.startupShutdownLock.lock();
    try {
        this.startupShutdownThread = Thread.currentThread();
        StartupStep contextRefresh = this.applicationStartup.start("spring.context.refresh");
        prepareRefresh();
        ConfigurableListableBeanFactory beanFactory = obtainFreshBeanFactory();
        prepareBeanFactory(beanFactory);
        try {
            postProcessBeanFactory(beanFactory);
            StartupStep beanPostProcess = this.applicationStartup.start("spring.context.beans.post-process");
            invokeBeanFactoryPostProcessors(beanFactory);
            registerBeanPostProcessors(beanFactory);
            beanPostProcess.end();
            initMessageSource();
            initApplicationEventMulticaster();
            onRefresh();
            registerListeners();
            finishBeanFactoryInitialization(beanFactory);
            finishRefresh();
        }
        catch (RuntimeException | Error ex) { /* 停 Lifecycle、destroyBeans、cancelRefresh */ }
        finally { contextRefresh.end(); }
    }
    finally { this.startupShutdownLock.unlock(); }
}
```

`spring.context.refresh` 与 `spring.context.beans.post-process` 两个 StartupStep 是 Actuator 时间线上最粗的两段。要看到单个 Bean，需要 BeanFactory 上更细的步骤（第八章）。

## 6.2 自动配置为什么排在用户配置之后

`invokeBeanFactoryPostProcessors`（本地第 795–796 行）调用 `PostProcessorRegistrationDelegate`，其中 `ConfigurationClassPostProcessor` 解析主类。解析到 `@Import(AutoConfigurationImportSelector)` 时，因为选择器实现的是 `DeferredImportSelector`，`ConfigurationClassParser.DeferredImportSelectorHandler.handle`（本地 Framework 第 813–822 行）只把它放进列表，不立即 `selectImports`。本轮配置类（含 `@ComponentScan` 扫到的 `@Configuration`）全部处理完，`process()`（第 825–834 行）才排序并 `processGroupImports`。

选择器侧（RC1 `AutoConfigurationImportSelector.java`）：

- `getImportGroup()` 返回 `AutoConfigurationGroup`（第 146–148 行）。同一个 group 里可以合并多次 `@EnableAutoConfiguration`。
- `getAutoConfigurationEntry`（第 130–142 行）的顺序：`ImportCandidates.load` 读 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` → 去重 → 去掉 `exclude` 与 `spring.autoconfigure.exclude` → `AutoConfigurationImportFilter` 过滤（`OnClassCondition` 等可以在**不加载配置类**的情况下按 classpath 存在性剔除）→ 发导入事件。
- `isEnabled`（第 150–154 行）读 `spring.boot.enableautoconfiguration`，默认 `true`。这是整段自动配置的总闸。
- `AutoConfigurationGroup.selectImports`（第 477–492 行）再按 `@AutoConfigureOrder` / `@AutoConfigureBefore` / `@AutoConfigureAfter` 排序。

条件注解（`@ConditionalOnMissingBean` 等）的真正评估仍在每个自动配置类被解析时，由 Framework 的 `@Conditional` 机制执行。延迟导入保证的是：**评估发生时，用户的 `@Bean` 和组件扫描结果已经在注册表里**。这就是“用户定义了同类型 Bean，自动配置让位”的时序基础。过滤器和条件评估本身也要花时间：classpath 上每多一个 starter，imports 文件就多一批类名要过滤。启动优化里“去掉没用的 starter”比“调一个 JVM 参数”更常命中这段。

## 6.3 单例预实例化：启动时间的主体

`finishBeanFactoryInitialization`（本地 `AbstractApplicationContext.java` 第 943–996 行附近）最终调用 `beanFactory.preInstantiateSingletons()`。Framework 7.1.0-RC1 的 `DefaultListableBeanFactory.preInstantiateSingletons`（第 1099–1147 行）在主线程上遍历所有单例 BeanDefinition：

```java
this.preInstantiationThread.set(PreInstantiation.MAIN);
try {
    List<CompletableFuture<?>> futures = new ArrayList<>();
    for (String beanName : beanNames) {
        RootBeanDefinition mbd = getMergedLocalBeanDefinition(beanName);
        if (!mbd.isAbstract() && mbd.isSingleton()) {
            CompletableFuture<?> future = preInstantiateSingleton(beanName, mbd);
            if (future != null) {
                futures.add(future);
            }
        }
    }
    if (!futures.isEmpty()) {
        CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).join();
    }
}
finally {
    this.mainThreadPrefix = null;
    this.preInstantiationThread.remove();
}
// 然后对已创建的 SmartInitializingSingleton 调 afterSingletonsInstantiated()
```

`preInstantiateSingleton`（第 1150–1194 行）的分支：

- `lazy-init` 且不是后台初始化：直接跳过，`getBean` 推迟到第一次使用。
- `bootstrap = BACKGROUND` 且容器里有名为 `bootstrapExecutor` 的 `Executor`：`depends-on` 和工厂 Bean 仍在主线程先创建，当前 Bean 丢到该 Executor，并 `join`。**非懒的后台 Bean 不会让 `refresh()` 提前返回**，只是多个这样的 Bean 可以重叠。
- 标记了后台初始化但没有 `bootstrapExecutor`：打一条 info，退回主线程（第 1180–1182 行）。
- 普通非懒单例：主线程 `getBean`。

`allOf(...).join()` 是“后台初始化能省多少启动时间”的上限：省的是重叠部分，不是把初始化挪到第一个请求。真正挪到第一个请求的只有懒初始化，代价是第一次访问的延迟和“启动成功但第一次调用才暴露缺配置”的故障模式。

循环依赖与跨线程的约束写在异常信息里（同文件第 1040–1050 行）：后台 Bean 若在主线程被提前需要，必须让依赖方使用 `ObjectProvider` 或懒注入；主线程 Bean 若被后台线程提前需要，必须用 `depends-on` 把那个 Bean 钉在主线程先创建。不满足时抛 `BeanCurrentlyInCreationException`，而不是死锁。这是启用后台初始化之前要先读的合同。

Boot 侧把应用的任务执行器别名成 `bootstrapExecutor` 的条件写在 `TaskExecutorConfigurations.BootstrapExecutorConfiguration`（RC1 第 203–216 行）：仅当还不存在名为 `bootstrapExecutor` 的 Bean、且存在 `applicationTaskExecutor` 时，才 `registerAlias`。`TaskExecutionAutoConfiguration` 在 classpath 有 `ThreadPoolTaskExecutor` 时导入这段配置（第 23–30 行）。已有别的 `Executor` Bean 时，`applicationTaskExecutor` 默认不创建（`OnExecutorCondition`），别名也不会出现，后台初始化退回主线程。要稳定地并行创建 Bean，应显式声明名为 `bootstrapExecutor` 的 Executor，而不是假设 Boot 一定已经准备好。

## 6.4 内嵌 Tomcat：半启动与真正监听

`ServletWebServerApplicationContext.onRefresh`（RC1 第 149–157 行）在 `super.onRefresh()` 之后调用 `createWebServer()`。此时单例预实例化**还没开始**（`onRefresh` 在 `finishBeanFactoryInitialization` 之前）。`createWebServer`（第 171–184 行）：

```java
StartupStep createWebServer = getApplicationStartup().start("spring.boot.webserver.create");
ServletWebServerFactory factory = getWebServerFactory();
createWebServer.tag("factory", factory.getClass().toString());
webServer = factory.getWebServer(getSelfInitializer());
this.webServer = webServer;
createWebServer.end();
getBeanFactory().registerSingleton("webServerGracefulShutdown",
        new WebServerGracefulShutdownLifecycle(webServer));
getBeanFactory().registerSingleton("webServerStartStop",
        new WebServerStartStopLifecycle(this, webServer));
```

`getWebServerFactory` 按类型在 BeanFactory 里找唯一的 `ServletWebServerFactory` 并 `getBean`。因此即使用了全局懒初始化，服务器工厂也会在这里被点名创建，不会被推迟到第一个请求。找不到工厂则抛 `MissingWebServerFactoryBeanException`（第 205–207 行）。

`TomcatServletWebServerFactory.getWebServer` 最终 `new TomcatWebServer(...)`。构造器（RC1 `TomcatWebServer.java` 第 93–99 行）立刻 `initialize()`：

```java
private void initialize() throws WebServerException {
    logger.info("Tomcat initialized with " + getPortsDescription(false));
    synchronized (this.monitor) {
        addInstanceIdToEngineName();
        Context context = findContext();
        context.addLifecycleListener((event) -> {
            if (context.equals(event.getSource()) && Lifecycle.START_EVENT.equals(event.getType())) {
                removeServiceConnectors();
            }
        });
        disableBindOnInit();
        this.tomcat.start();
        rethrowDeferredStartupExceptions();
        startNonDaemonAwaitThread();
    }
}
```

`removeServiceConnectors` 在 Context 启动事件里把 Connector 从 Service 上摘掉；`disableBindOnInit` 把 `bindOnInit` 设为 false。随后的 `tomcat.start()` 会跑 Tomcat 自己的生命周期监听器（ServletContextInitializer 在这里把 `DispatcherServlet` 注册进 Context），但**不会占用端口**。日志里的 “Tomcat initialized with port(s): ...” 出现在这一步，容易被误读成“已经在监听”。`startNonDaemonAwaitThread` 起一个非 daemon 线程堵在 `tomcat.getServer().await()` 上，避免主线程从 `main` 返回后 JVM 退出。

真正绑定在 `TomcatWebServer.start()`（第 214–228 行）：`addPreviouslyRemovedConnectors()` 把 Connector 加回去，Tomcat 才会 bind。调用者是 `WebServerStartStopLifecycle.start()`（RC1 第 31–36 行）：

```java
public void start() {
    this.webServer.start();
    this.running = true;
    this.applicationContext
        .publishEvent(new ServletWebServerInitializedEvent(this.webServer, this.applicationContext));
}
```

它是 `SmartLifecycle`，phase 为 `WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE`。该常量（RC1 第 28–34 行）定义为 `GRACEFUL_SHUTDOWN_PHASE - 1024`，而 `GRACEFUL_SHUTDOWN_PHASE = SmartLifecycle.DEFAULT_PHASE - 1024`。`SmartLifecycle.DEFAULT_PHASE` 是 `Integer.MAX_VALUE`（Framework `SmartLifecycle.java` 第 82 行）。所以 Web 服务器的 phase 是 `Integer.MAX_VALUE - 2048`。

`DefaultLifecycleProcessor.startBeans`（第 365–380 行）用 `TreeMap` 按 phase **升序**启动。因此在 `finishRefresh` → `getLifecycleProcessor().onRefresh()`（本地 `AbstractApplicationContext.java` 第 1003–1017 行）里的顺序是：

1. phase 更小的普通 `Lifecycle`（默认 phase 0）；
2. Web 服务器（`MAX_VALUE - 2048`）——**这一刻端口才绑定**，并发布 `ServletWebServerInitializedEvent`；
3. 使用默认 phase `Integer.MAX_VALUE` 的其他 `SmartLifecycle`（很多自动配置的后台组件在这一档）；
4. `publishEvent(new ContextRefreshedEvent(...))`。

单例创建完成于端口绑定之前。端口绑定完成于 `refresh()` 返回之前。默认 phase 的 `SmartLifecycle` 排在端口绑定之后，但仍在 `refresh()` 返回之前。`ApplicationStartedEvent` 还要再晚一步。

关闭时 `ServletWebServerApplicationContext.doClose`（第 160–168 行）先发布 `ReadinessState.REFUSING_TRAFFIC`，再 `super.doClose()`，最后 `webServer.destroy()`。就绪状态的撤销早于连接器拆除。

## 6.5 本章小结

`refresh()` 里和启动墙钟最相关的三段依次是：配置类解析（自动配置条件）、非懒单例创建、Lifecycle 启动（含绑端口）。Tomcat 的 “initialized” 日志位于中间段之前，不代表已经监听。

---

# 七、刷新之后：Started、Runner、Ready

## 7.1 `Started` 日志的两个数

`startup.started()` 在 `listeners.started` 和 `callRunners` 之前（本地第 311–316 行）。`StartupInfoLogger.getStartedMessage` 把 `startup.action()`（普通路径是 `"Started"`，CRaC 恢复后是 `"Restored"`）、应用名、X 秒、以及 JVM uptime Y 拼成一行。`appendAotMode` 会在更早的 `Starting` 日志里加上 `AOT-processed`（第 88–89 行），便于确认进程是否真的走了生成产物，而不是仍在做运行期解析。

## 7.2 Runner 排在存活之后、就绪之前

本地 `callRunners`（第 755–764 行）按 `Runner` 这个公共父类型取出 `ApplicationRunner` 和 `CommandLineRunner`，用 `FactoryAwareOrderSourceProvider` 让 `@Order` 可以标在 `@Bean` 工厂方法上，再逐个调用。`ApplicationRunner` 收到解析后的 `ApplicationArguments`，`CommandLineRunner` 收到原始 `String[]`（第 774–781 行）。

因此一条迁移或缓存预热如果写在 Runner 里：

- 它**不**计入 `Started ... in X seconds`；
- 它**会**推迟 `ApplicationReadyEvent`；
- 它期间 `LivenessState` 已经是 `CORRECT`，`ReadinessState` 还不是 `ACCEPTING_TRAFFIC`。

Kubernetes 里 liveness 对 `CORRECT`、readiness 对 `ACCEPTING_TRAFFIC`（Actuator 健康探针的默认映射）时，长时间迁移不会被 liveness 杀掉，也不会被提前送流量。若探针只检查 TCP 端口，则在 6.4 节的绑端口时刻就已经通过，早于 Runner，也早于默认 phase 的 `SmartLifecycle`。

## 7.3 Ready 的额外条件

本地第 321–324 行：只有 `context.isRunning()` 才 `listeners.ready`。刷新成功后的 Servlet 应用会处于 running。非 Web 应用若主线程上已经没有非 daemon 工作，进程可能在观测到 Ready 之前就退出；需要进程留住时用 `spring.main.keep-alive=true`，而不是指望 Ready 事件本身拖住 JVM。

## 7.4 本章小结

刷新返回 ≠ 可以接流量。Boot 把“容器起来了”和“业务启动任务做完了”分成 Started/Liveness 与 Ready/Readiness 两拍，Runner 夹在中间。优化启动时间时要先决定优化的是 X、是 Y，还是就绪时间。

---

# 八、启动时间通常耗在哪

下面不是基准测试数据，而是由前面的调用链推出的**成本地图**。真正的比例必须用第九章的 StartupStep 在目标应用上测。没有这张地图就调 JVM 参数，经常是在优化占比很小的那一段。

| 区段 | 典型内容 | 落在哪一步 | 主要优化手段 |
|---|---|---|---|
| JVM 与类加载（Y − X 的大头，也渗入 X） | JDK 启动、加载 Boot/Framework/第三方类、验证与链接 | `main` 之前直到每个类第一次被用到 | CDS、JDK 24+ AOT cache、Native Image、去掉没用的依赖（少加载类） |
| 配置 | 读 yml、profile、`spring.config.import`、远程配置中心 | `environmentPrepared` | 减少 import 链；配置中心超时与本地快照；不要在这一步做远程重试风暴 |
| 条件评估与组件扫描 | imports 过滤、`@Conditional`、`@ComponentScan` 的 classpath 遍历 | `invokeBeanFactoryPostProcessors` | 去掉 starter、收窄 scan、AOT 生成初始化器 |
| 单例创建 | 数据源池、Hibernate `SessionFactory`、Flyway/Liquibase、消息客户端、缓存客户端 | `preInstantiateSingletons` | 懒初始化、后台重叠、把迁移移出进程、缩小池的启动期连接数 |
| 绑端口与 Lifecycle | Tomcat Connector、其他 `SmartLifecycle` | `finishRefresh` | 通常不是大头；不要为了它关 banner |
| Runner | 应用自己的预热、二次迁移 | `callRunners` | 计入就绪时间，不计入 Started 的 X |
| 第一请求才出现的成本 | 懒 Bean、JIT C2 编译、连接池扩容 | `refresh` 之后 | 懒初始化把成本挪到这里；Leyden 的方法剖析缓存、预热流量解决的是这一段 |

经验上，一个带 JPA 和迁移的服务，X 的大头几乎总是单例创建而不是 Tomcat。一个依赖很少的服务，Y 的大头往往是类加载，CDS / AOT cache 才划算。一个 Spring Cloud 应用，X 里经常夹着 Nacos、配置中心、注册中心的阻塞 RPC，这不是 Spring 容器能靠懒初始化消掉的。

Banner、JMX 导出、字符集预热属于毫秒到几十毫秒级。它们值得在已经无其他明显项之后再关，不值得作为第一刀。

---

# 九、官方机制里的启动优化

这一章只写源码里存在的开关：它改的是哪一步、默认为什么没开、打开之后谁会受伤。

## 9.1 全局懒初始化

开关：`spring.main.lazy-initialization=true`，或 `SpringApplication.setLazyInitialization(true)`。回绑之后，`prepareContext` 注册 `LazyInitializationBeanFactoryPostProcessor`（本地第 393–395 行）。

该 BFPP 的 order 是 `HIGHEST_PRECEDENCE`（RC1 第 99–101 行），在其他 BFPP 改定义之前就把“尚未显式指定 lazy 的 `AbstractBeanDefinition`”设为 `lazyInit=true`（第 64–74 行）。已经写了 `lazy-init` / `@Lazy` 的定义不会被覆盖。

自动排除两类（第 55–61 行，以及类 javadoc）：

- `SmartInitializingSingleton`：否则 `afterSingletonsInstantiated()` 不会在启动末尾被调用；
- `ROLE_INFRASTRUCTURE`：容器内部设施 Bean。

另外可通过 `LazyInitializationExcludeFilter` Bean 再排除。`getBeansOfType(..., false, false)` 的两个 false 避免在收集过滤器时把 FactoryBean 提前实例化。

**它省的是** `preInstantiateSingletons` 里那些没人在启动路径上点名 `getBean` 的单例。**它省不掉的是**：被 `onRefresh` 点名的 `ServletWebServerFactory`、被别的非懒 Bean 依赖到的 Bean、Runner 本身（`callRunners` 会 `getBean`）、以及所有条件评估和类加载。

**受伤的是**第一次请求的尾延迟，以及“进程已 Ready 但第一次调用才发现数据库连不上”的故障发现时间。生产上更稳妥的用法是只对确认不在关键路径上的大对象使用 `@Lazy`，而不是全局打开。

## 9.2 后台预初始化

Boot 4 把旧的单体 `BackgroundPreinitializer` 拆成接口（RC1 `BackgroundPreinitializer.java`，`@since 4.0.0`）。`BackgroundPreinitializingApplicationListener`（第 52–74 行）在 `ApplicationEnvironmentPreparedEvent` 上启动名为 `background-preinit` 的线程，跑 `spring.factories` 里全部 `BackgroundPreinitializer`。

三个关闭条件（第 58–60 行）：Native Image、系统属性 `spring.backgroundpreinitializer.ignore=true`、或者 `availableProcessors() <= 1`。单核上开线程没有重叠收益。

线程若创建失败（注释写明 Google App Engine 禁止创建线程），`countDown` 后继续，初始化退回第一次真正使用时，启动略慢，不会失败。`ApplicationReadyEvent` 和 `ApplicationFailedEvent` 上会 `complete.await()`（第 76–82 行），避免预初始化还在跑就对外宣布就绪或开始关闭。

Tomcat 的实现只提前构造 `Rfc6265CookieProcessor` 和 `NonLoginAuthenticator`（`TomcatBackgroundPreinitializer.java` 第 20–22 行），用来触发一批类的加载与静态初始化。这是类加载重叠，不是 Bean 创建重叠。默认已经开启，一般不需要为了优化去碰它；在禁止创建线程的平台上要显式 ignore。

## 9.3 `@Bean(bootstrap = BACKGROUND)` 与 bootstrap executor

Framework 6.2 起 `@Bean` 有 `bootstrap()` 属性，默认 `DEFAULT`，可设 `BACKGROUND`（`Bean.java` 第 295–307、354–369 行）。语义：

- 非懒单例：允许在后台线程实例化，但 `refresh()` 结束前必须完成（对应 `allOf().join()`）；
- 懒单例：也可用后台线程，完成时刻可以晚到第一次访问。

没有名为 `bootstrapExecutor` 的 Executor 时，容器打日志并退回主线程，不会静默并行。Boot 的别名规则见 6.3 节。phase 内并发启动 `SmartLifecycle` 还要再调用 `DefaultLifecycleProcessor.setConcurrentStartupForPhase`（`@since 6.2.6`），默认每个 phase 仍是顺序的。

适用对象是互相不依赖、各自要几秒的客户端（例如两个独立的远程连接）。互相注入、或者会在 `@PostConstruct` 里反向查找其他 Bean 的对象，容易撞上 6.3 节的 `BeanCurrentlyInCreationException`。

## 9.4 AOT：把运行期解析换成生成的初始化器

构建期入口是 `SpringApplicationAotProcessor.main`（RC1 第 70–84 行）。Maven/Gradle 插件的 `process-aot` 目标最终调用它，参数是应用主类和源码/资源/class 输出目录。它通过 `SpringApplication.withHook` 注册一个 `SpringApplicationRunListener`，在 `contextLoaded` 抛出 `AbandonedRunException`（第 100–107 行），让 `run()` 在刷新之前把已装配的上下文交出来做代码生成，并被 `handleRunFailure` 识别为“不是故障”（本地第 793–795 行）。

运行期要使用产物，必须让 `AotDetector.useGeneratedArtifacts()` 为 true（Native Image 自动为 true，JVM 上需要 `spring.aot.enabled` 走 `SpringProperties`）。之后：

- 容器类型在非 Web 时改为 `GenericApplicationContext`（5.1 节）；
- `prepareContext` 把 `主类__ApplicationContextInitializer` 插到初始化器最前，找不到就失败，不回退（本地第 409–418 行）；
- `load(sources)` 被跳过，不再用注解读取器注册主类；
- `EnvironmentPostProcessorApplicationListener` 会尝试加载生成的 Environment 后处理器（4.2 节）。

AOT 省掉的是刷新期间的配置类解析、条件评估和大量反射，不是单例本身的构造函数。Hibernate 建 `SessionFactory`、连接池建连，在 JVM 模式的 AOT 下仍然发生。Native Image 把这些也提前到构建期能确定的范围，并把类加载换成镜像里的堆，启动墙钟通常会再降一档，但构建变为封闭世界：反射、资源、动态代理都要有 hint。`SpringApplication` 在 Native Image 且初始化器缺失时直接失败，就是在避免“镜像跑起来了却静默退回反射”。

AOT 在 JVM 上的正确验证方法：启动日志的 `Starting` 行带 `AOT-processed`（`StartupInfoLogger.appendAotMode`）。没有这几个字，就是仍在走运行期解析。

## 9.5 CRaC 与 `spring.context.exit`：刷新末尾的分叉

Framework `DefaultLifecycleProcessor` 在类初始化时读取两个属性（7.1.0-RC1 第 88–116 行）：

```java
public static final String CHECKPOINT_PROPERTY_NAME = "spring.context.checkpoint";
public static final String EXIT_PROPERTY_NAME = "spring.context.exit";
public static final String ON_REFRESH_VALUE = "onRefresh";

private static boolean checkpointOnRefresh =
        ON_REFRESH_VALUE.equalsIgnoreCase(SpringProperties.getProperty(CHECKPOINT_PROPERTY_NAME));

private static final boolean exitOnRefresh =
        ON_REFRESH_VALUE.equalsIgnoreCase(SpringProperties.getProperty(EXIT_PROPERTY_NAME));
```

读取通道是 `SpringProperties`：`spring.properties` 或 `-D`，**不是** `application.yml`。并且它们是静态字段，在 `DefaultLifecycleProcessor` 类被加载时定死。

`onRefresh()`（第 295–314 行）在启动 Lifecycle Bean 之前：

```java
public void onRefresh() {
    if (checkpointOnRefresh) {
        checkpointOnRefresh = false;
        new CracDelegate().checkpointRestore();
    }
    if (exitOnRefresh) {
        Runtime.getRuntime().halt(0);
    }
    this.stoppedBeans = null;
    startBeans(true);
    this.running = true;
}
```

两条路径的时机都在 `finishBeanFactoryInitialization` **之后**、`startBeans` **之前**。也就是说：非懒单例已经创建完，Web 服务器的 `SmartLifecycle.start()` 还没执行，端口还没绑定，Runner 还没跑。

- `spring.context.exit=onRefresh`：`Runtime.halt(0)`，不跑 shutdown hook。这是 CDS / AOT cache 训练运行要用的停法。用 `System.exit` 会跑钩子，训练进程的关闭动作可能改写或锁住资源，档案也不稳定。
- `spring.context.checkpoint=onRefresh`：调用 `org.crac.Core.checkpointRestore()`。当前 JVM 不支持则抛 `ApplicationContextException`（第 690–697 行）。类加载时若设置了该属性但 classpath 没有 `org.crac.Core`，构造器直接失败（第 136–142 行）。

只要 classpath 上有 `org.crac.Core` 且不是 Native Image，处理器就会向 CRaC 全局上下文注册 `CracResourceAdapter`（第 136–138、683–687 行），即使这次没有 `checkpoint=onRefresh`。外部触发的 checkpoint（例如 `jcmd`）也会走到它。`beforeCheckpoint` 先起一个非 daemon 的 `prevent-shutdown` 线程，再 `stopForRestart()` 停掉 Spring 管理的 Lifecycle Bean（第 721–727 行）。`afterRestore` 再 `restartAfterStop()`，并打出恢复后的 uptime（第 737–749 行）。Boot 的 `CoordinatedRestoreAtCheckpointStartup` 负责让 “Restored in X seconds” 和这个 uptime 使用同一套 CRaC MXBean。

这个适配器只覆盖 **Lifecycle / SmartLifecycle**。普通单例持有的套接字不会被自动关掉。连接池、消息客户端、HTTP 客户端必须自己实现 `org.crac.Resource`，或在 checkpoint 前释放、在 restore 后重建。否则恢复出来的进程会带着已经失效的 fd。

`checkpointOnRefresh` 在调用前被置回 false，避免 restore 之后再次进入 `onRefresh` 时重复 checkpoint。

## 9.6 其他源码里可见、收益通常较小的开关

| 开关 | 作用 | 依据 |
|---|---|---|
| `spring.autoconfigure.exclude` / `@SpringBootApplication(exclude=...)` | 从候选列表删掉指定自动配置 | `EnableAutoConfiguration` javadoc；`getAutoConfigurationEntry` 第 137–139 行 |
| `spring.boot.enableautoconfiguration=false` | 关闭整个自动配置导入 | `ENABLED_OVERRIDE_PROPERTY`，`isEnabled` 第 150–154 行 |
| 收窄 `scanBasePackages` | 减少 `@ComponentScan` 的 classpath 遍历 | `SpringBootApplication.scanBasePackages` 第 70–78 行 javadoc。它不影响 `@EntityScan` 和 Spring Data 仓库扫描 |
| `@Configuration(proxyBeanMethods = false)` | 配置类不再生成 CGLIB 子类。`@Bean` 方法互调会退化成普通方法调用（不再返回容器里的同一个单例） | `@SpringBootApplication.proxyBeanMethods` 默认 `true`（第 140 行）。自动配置类自身大量使用 `proxyBeanMethods = false`（例如 `TaskExecutionAutoConfiguration` 导入的配置） |
| `spring.main.banner-mode=off` | 不读 banner 文件、不写控制台 | `printBanner` 在回绑之后 |
| `spring.backgroundpreinitializer.ignore=true` | 关掉 9.2 的后台线程 | 仅在不能建线程或要排除预热干扰时使用 |

这些开关里，前两行（少做自动配置、少扫描）影响的是第八章表格的第二、三行，值得先做。banner 和预初始化影响的是表格最下面。

## 9.7 先度量：`BufferingApplicationStartup` 与 `startup` 端点

`SpringApplication` 默认的 `applicationStartup` 是 `ApplicationStartup.DEFAULT`，`start()` 返回的步骤是空操作，开销可以忽略，也没有任何记录。要记录，必须在 `run` 之前：

```java
SpringApplication app = new SpringApplication(MyApplication.class);
app.setApplicationStartup(new BufferingApplicationStartup(2048));
app.run(args);
```

缓冲容量要大于实际步骤数，否则时间线被截断。`context.setApplicationStartup` 发生在 `prepareContext` 之前（本地 `run()` 第 307 行），因此 `spring.context.refresh`、`spring.boot.webserver.create`、各 `spring.boot.application.*` 步骤都能记上。构造器和 `environmentPrepared` 之前的步骤取决于设置时机：上面这种写法则从 `run()` 开始的步骤都在缓冲里。

Actuator 的 `StartupEndpoint`（RC1，`@Endpoint(id = "startup")`，`@since 2.4.0`）构造器只接受 `BufferingApplicationStartup`。`GET` 走 `startupSnapshot()`，返回当前缓冲的只读视图；`POST` 走 `startup()`，`drainBufferedTimeline()` 把缓冲取走（第 47–56 行）。端点不会在你忘记设置 `BufferingApplicationStartup` 时自动出现有用数据。

配合使用的还有：

- `debug` 日志级别下的条件评估报告（哪些自动配置被排除、匹配原因）。它解释“为什么加载了这个配置”，不直接给毫秒数。
- `Started` 行的 X 与 Y。Y 明显大于 X 时优先看类加载；X 大时优先看 StartupStep 里 `spring.context.refresh` 下的 Bean 步骤；X 不大但就绪很晚时看 Runner。

## 9.8 本章小结

官方开关各自对准成本地图的一行：懒初始化和后台 Bean 对准单例创建，AOT 对准解析与反射，`exit=onRefresh` 加 CDS/AOT cache 对准类加载，CRaC 对准“把单例创建挪到快照里”。没有一个开关同时优化这三行。度量是为了先选对那一行。

---

# 十、业界实践

这一章的做法多数**不是** Spring 源码里的一个 if，而是把第九章的钩子和 JVM、镜像、平台拼起来。凡是数字，都以你自己的 Startup 端点和 JVM uptime 为准。公开演讲里的“快了几倍”依赖具体应用、JDK 和硬件，不能当成承诺。

## 10.1 先把探针和日志口径对齐

生产里同时看三个信号，不要合成一个“启动时间”：

| 信号 | 对应源码事件 | 用来判断 |
|---|---|---|
| `Started in X (process running for Y)` | `startup.started()` + MXBean uptime | X 是容器就绪（不含 Runner），Y 含 JVM |
| 端口可连接 | `WebServerStartStopLifecycle.start` | 只说明 Connector 已 bind。早于 Runner，也早于部分 `SmartLifecycle` |
| readiness = `ACCEPTING_TRAFFIC` | `ApplicationReadyEvent` | 可以接流量 |
| liveness = `CORRECT` | `ApplicationStartedEvent` | 进程活着，允许还在跑 Runner |

Kubernetes 上 liveness 用存活、readiness 用就绪、startupProbe 给足 Runner 和迁移的时间。只用 TCP 探针时，优化 Runner 对“探针变绿”没有帮助，因为探针在绑端口时已经绿了；真正的用户请求却会打进还没跑完迁移的进程。

## 10.2 依赖与自动配置：通常是第一刀

对第八章里“条件评估 + 单例创建”两行都有效，而且不改变故障模型：

1. 从构建里去掉不需要的 starter。每少一个 starter，就少一批 `AutoConfiguration.imports` 条目、少一批条件、少一批可能被创建的单例。这比 `exclude` 更彻底，因为类本身不再出现在 classpath 上，CDS 档案也会更小。
2. 暂时去不掉依赖时，用 `spring.autoconfigure.exclude` 去掉确认不会用到的自动配置（例如引入了 JDBC 却只用手动 `DataSource`、不需要 Boot 的数据源自动配置时要非常小心，确认没有别的自动配置依赖它）。
3. `@SpringBootApplication` 放在根包时，`@ComponentScan` 会走过所有子包。把应用代码收进明确的 `scanBasePackages`。实体扫描和 Spring Data 仓库要单独收窄，注解 javadoc 写了 `scanBasePackages` 不管那两件事。
4. 用户配置类在没有 `@Bean` 互调时使用 `proxyBeanMethods = false`。收益通常是几十到几百毫秒级的 CGLIB 生成，不是数量级。

做完用条件评估报告确认那些自动配置确实不再匹配，而不是“exclude 了一个不在 classpath 上的类名”（`checkExcludedClasses` 会对“类存在但不在候选列表”给出错误，见 `AutoConfigurationImportSelector` 第 200–210 行）。

## 10.3 把重活移出启动关键路径

单例创建阶段常见的大头是应用自己拉进来的，不是容器：

- **数据库迁移**（Flyway、Liquibase）若放在启动路径上，会占满 X 或就绪时间。业界更稳的做法是迁移作为独立作业先完成，应用启动使用 `ddl-auto=none` 且不再在 Runner 里迁移。必须留在进程内时，接受它位于 Runner 或一个非懒单例里，并用 readiness 挡住流量。
- **Hibernate `SessionFactory`** 的元数据构建随实体数量增长。收窄实体集合比调几个 Hibernate 布尔值有效。历史文章里的 `hibernate.temp.use_jdbc_metadata_defaults=false` 针对的是旧版本启动时探测 JDBC 元数据；新版本是否还有等价开关，以当前 Hibernate 文档为准，不要照搬旧键。
- **连接池**在启动时按 `minimum-idle` 建连。启动期把最小空闲压低，让池在流量到来后扩展，能减少启动期的数据库往返。代价是第一波流量要付建连成本。
- **远程配置与注册**（Nacos、Spring Cloud Config、服务注册）发生在 Environment 准备或 Lifecycle 启动期间，超时会按重试次数放大。本地快照、合理超时、注册与就绪解耦（先能接流量再注册，或相反，取决于你是否希望未就绪实例被发现）是这一类系统的常规做法。容器的懒初始化不会取消这些远程调用，只要对应的 Bean 仍在启动路径上被创建。

## 10.4 JVM：CDS 与 Leyden 式 AOT cache

类加载占 Y 的比例高时，这一层的收益才明显。训练运行的目标是让 JVM 在“和图 6 相同的类加载轨迹”上留下档案，然后生产运行直接映射这些已链接的类。

**训练运行如何停在正确的位置。** 档案应覆盖 `refresh()` 里会用到的类，又不要在训练进程里绑定端口、跑 Runner、接一次偶然的健康检查。`spring.context.exit=onRefresh` 正好停在单例已创建、Lifecycle 未启动的位置（9.5 节），并且 `halt` 不跑关闭钩子。它必须是：

```text
-Dspring.context.exit=onRefresh
```

或 classpath 上 `spring.properties` 里的同名键。写进 `application.yml` 不会被 `SpringProperties` 读到。

**传统 CDS（JDK 13 起的动态类数据共享档案，AppCDS 一脉）。** 典型两步：

```text
java -Dspring.context.exit=onRefresh -XX:ArchiveClassesAtExit=app.jsa -jar app.jar
java -XX:SharedArchiveFile=app.jsa -jar app.jar
```

档案和生成它的 JDK 小版本、classpath 内容绑定。镜像里 JDK 或依赖一变就要重新训练。训练和生产的 classpath 必须一致，否则 JVM 拒绝映射档案，静默退回普通启动——看起来“优化没生效”，其实是档案未使用。可用 `-Xlog:cds` 确认映射成功。

**JDK 24 起的 AOT 类加载与链接缓存（JEP 483，Project Leyden 的第一块）。** 它缓存的不只是类字节，还包括加载与链接的结果，通常比传统 CDS 更能压缩 Y。JDK 25 把训练/生产收成更短的命令行形式（以该 JDK 的 `java -XX:+PrintFlagsFinal` / 发行说明里的 `-XX:AOTCacheOutput` 与 `-XX:AOTCache` 为准）。仍然建议加上 `-Dspring.context.exit=onRefresh`，让训练轨迹停在容器刷新完成、对外服务尚未开始的地方。

训练运行**不会**覆盖第一个请求才加载的类，也不会覆盖 C2 编译后的机器码。第一请求的延迟要靠流量预热，或 JDK 25 的 AOT 方法剖析缓存（训练时要真的走到那些方法，`exit=onRefresh` 反而覆盖不到请求路径）。启动优化和稳态 JIT 优化是两套档案，不要用同一次训练既要求“停在 refresh 末尾”又要求“缓存请求热路径”。

Spring Boot 3.3 前后的官方指南把 CDS 作为容器镜像构建的一步（训练运行写在镜像构建期，档案打进镜像层）。收益幅度官方示例随应用变化，常见是“类加载占比高的进程有可见下降，I/O 绑定的迁移型启动几乎不动”。这和第八章的地图一致。

## 10.5 CRaC、AWS SnapStart 与同类快照

CRaC（Java 的 Coordinated Restore at Checkpoint）把**已经完成单例创建的进程**打成检查点，之后每次冷启动是 restore 而不是重新 `refresh()`。Spring 侧的合同就是 9.5 节的 `CracResourceAdapter`：checkpoint 前停 Lifecycle，restore 后重启，并要求第三方资源自己登记 `org.crac.Resource`。

业界几种落地，机制相同、触发方不同：

| 做法 | 谁触发 checkpoint | Spring 侧要做的事 |
|---|---|---|
| CRaC JDK + `spring.context.checkpoint=onRefresh` | `DefaultLifecycleProcessor.onRefresh` 里调用 `Core.checkpointRestore()` | 引入 `org.crac:crac`，使用支持 CRaC 的 JDK，审计连接池和客户端 |
| 外部 `jcmd JDK.checkpoint` | JVM | 同样依赖已注册的 `CracResourceAdapter`（只要 crac 在 classpath 上，处理器构造时就会注册） |
| AWS Lambda SnapStart | 平台在 Init 之后对 MicroVM 做快照，Invoke 时恢复 | 与 CRaC 一样要在快照前关掉套接字、在恢复后重建；随机数、唯一 ID、时间戳不能烘焙进快照 |
| 阿里云 Dragonwell、Azul、部分 Kubernetes 检查点方案 | 厂商 JVM 或运行时 | 看厂商是否走 `org.crac`。走标准 API 时 Spring 的适配器自动生效；只做 AppCDS 的则归 10.4，不归本节 |

`onRefresh` 时机的检查点**不含**已绑定的端口和 Runner 的副作用，恢复后会继续 `startBeans`、返回 `refresh()`、跑 Runner、发 Ready。若希望快照里连 Runner 都执行完，就不能用 `checkpoint=onRefresh`，而要在 Ready 之后由外部触发 checkpoint，并自己保证 Runner 的副作用可以在 restore 后重放或已经对外无害。

不适合上快照的进程：启动后必须立刻持有独占外部租约、把本机 IP 写进注册中心、或在静态字段里缓存了“只应存在一份”的进程内状态。恢复出的多份副本会带着同一份状态。

Native Image 与 CRaC 在 `DefaultLifecycleProcessor` 构造器里是互斥的：`NativeDetector.inNativeImage()` 为 true 时不注册 CRaC 资源（第 137 行）。两条路线选一条。

## 10.6 Native Image

在 9.4 的 AOT 产物之上，再用 GraalVM 把应用编成原生可执行文件。类加载和 JIT 从启动路径上消失，冷启动墙钟通常是这一章里最低的一档。成本在构建侧：封闭世界、反射 hint、资源 hint、构建时间、峰值内存，以及部分 JVM 能力（例如任意运行期生成类）不可用。

Boot 的构建插件和 Buildpacks（`bootBuildImage`）能把 AOT 处理与 native 构建串起来。验证仍然看运行期：进程是原生可执行文件，且缺少初始化器时启动直接失败（`AotInitializerNotFoundException`），而不是偷偷用反射容器。

选择建议：冷启动有硬性 SLA（函数、短生命周期 Job、大规模频繁扩容）且团队能维护 hint 时用 Native Image。长驻服务的稳态吞吐往往仍是普通 JVM + JIT 更好；这时 CDS / AOT cache 是更便宜的一档，不必上 native。

## 10.7 镜像与扩容：分层 jar 优化的不是进程启动

Boot 2.3 起的分层 jar（`layers`）把依赖、Boot loader、应用类拆进不同镜像层。依赖层很少变化，应用类层经常变化。这缩短的是**拉取镜像和调度**的时间，不是 `Started in X`。把“镜像就绪”和“进程就绪”加在一起叫做冷启动时，分层 jar、镜像缓存、以及 10.4 的档案打进镜像，分别优化相加式子的不同项。只优化进程内的 X，解决不了 200MB 依赖层每次都重新拉取。

## 10.8 不要再做的事

| 做法 | 原因 |
|---|---|
| `-Xverify:none` / `-noverify` | 跳过字节码验证。新 JDK 已移除或即将移除，且会掩盖损坏的类。CDS 与 AOT cache 才是它的替代 |
| 生产 classpath 带 `spring-boot-devtools` | 双类加载器是为了开发期重启。生产上增加启动成本，并改变类加载模型，和 CDS 档案冲突 |
| 未度量就打开全局懒初始化 | 9.1 节：关键路径上的 Bean 仍会创建，故障转移到第一请求 |
| 把 `spring.aot.enabled`、`spring.context.exit`、`spring.context.checkpoint` 写进 `application.yml` | 三者都走 `SpringProperties`，yml 不参与 |
| 用同一次 Leyden 训练既 `exit=onRefresh` 又指望缓存请求热路径 | 训练在绑端口之前就 halt 了，请求路径没有执行 |
| 生产打开 `debug=true` 或全量 `DEBUG` 日志来“观察启动” | 日志本身成为启动 I/O。用 Startup 端点看时间线，用一次性的条件报告看自动配置 |
| 为了启动时间关掉失败分析器或 readiness | 省下的时间可以忽略，失去的是启动失败时的可操作信息和流量隔离 |

## 10.9 本章小结

业界把启动优化分成四层，和第八章的行一一对应：少装东西（依赖与自动配置）、少在关键路径上做 I/O（迁移、远程配置）、少在每次冷启动时加载类（CDS、AOT cache、Native Image）、少在每次冷启动时重建进程（CRaC 与云厂商快照）。探针口径决定你优化的是哪一层的数字。

---

# 十一、建议的优化顺序

按这个顺序做，是因为前面的步骤便宜、可逆，并且会改变后面步骤的测量基线。先上 CDS 再删 starter，档案要重做，而且你不知道收益来自哪一步。

1. **对齐口径。** 记录 X、Y、端口可连接时间、readiness 变绿时间。接上 `BufferingApplicationStartup` 和 `startup` 端点。确认生产探针用的是 readiness 而不是 TCP。
2. **看时间线再删依赖。** 在 StartupStep 里找出最长的 Bean 与自动配置。去掉对应 starter 或 `exclude`。收窄组件扫描与实体扫描。
3. **把阻塞 I/O 移出关键路径。** 迁移外置或明确留在 Runner 并由 readiness 保护。收紧配置中心与注册中心的超时。连接池不要在启动期建满。
4. **再决定是否动单例模型。** 只对时间线里确实很贵、且不在第一请求关键路径上的 Bean 使用 `@Lazy` 或 `bootstrap = BACKGROUND`。全局懒初始化要单独评估第一请求 SLO。显式提供 `bootstrapExecutor`，并按 6.3 节处理跨线程依赖。
5. **类加载仍占 Y 的大头时，做 CDS 或 JDK 24+ 的 AOT cache。** 训练命令带 `-Dspring.context.exit=onRefresh`。用 `-Xlog:cds`（或该 JDK 对应的 AOT cache 日志）确认生产运行映射了档案。依赖或 JDK 一变就重新训练。
6. **平台支持检查点、且冷启动 SLA 仍然不够时，评估 CRaC 或 SnapStart。** 先列出所有持有套接字和唯一 ID 的 Bean，确认它们实现了 `org.crac.Resource` 或等价的恢复回调。用 `Restored in ...` 日志确认走的是 `CoordinatedRestoreAtCheckpointStartup`。
7. **冷启动要到几十到几百毫秒、且构建封闭世界可接受时，再上 Native Image。** 不要和 CRaC 叠在同一个进程上。

## 贯通：一次普通 Servlet 启动的证据链

把前面的行号收成一条可以在调试器里单步对照的链。

```text
main
  SpringApplication.run                         本地 1352–1353
    构造器：deduce + spring.factories + StackWalker   本地 262–272
    run                                           本地 292
      Startup.create                              本地 293、1768
      listeners.starting                          ApplicationStartingEvent
        LoggingApplicationListener.beforeInitialize     RC1 LoggingApplicationListener 226–228
      prepareEnvironment                          本地 338
        命令行 PropertySource addFirst            本地 506–517
        environmentPrepared
          ConfigDataEnvironmentPostProcessor      本地 ConfigDataEnvironmentPostProcessor 77–85
          ConfigDataEnvironment.processAndApply   本地 ConfigDataEnvironment 224–235
          LoggingApplicationListener 正式初始化   RC1 231–236
          BackgroundPreinitializingApplicationListener 起后台线程   RC1 73–74
        bind spring.main                          本地 538–544
      createApplicationContext                    本地 567；工厂 RC1 DefaultApplicationContextFactory 43–58
      prepareContext                              本地 368
        applyInitializers
        contextPrepared                           ApplicationContextInitializedEvent
        BeanDefinitionLoader.register(主类)       RC1 BeanDefinitionLoader 143–151
        contextLoaded                             ApplicationPreparedEvent
      refreshContext → AbstractApplicationContext.refresh    本地 Framework 582
        invokeBeanFactoryPostProcessors
          DeferredImportSelector 推迟到本轮结束   本地 ConfigurationClassParser 813–834
          AutoConfigurationImportSelector.getAutoConfigurationEntry   RC1 130–142
        onRefresh → createWebServer               RC1 ServletWebServerApplicationContext 149–184
          TomcatWebServer.initialize              RC1 101–119（摘掉 Connector 后 tomcat.start）
        finishBeanFactoryInitialization
          preInstantiateSingletons                Framework RC1 DefaultListableBeanFactory 1099–1131
        finishRefresh                             本地 Framework 1003–1017
          DefaultLifecycleProcessor.onRefresh     Framework RC1 295–314
            （若 -Dspring.context.exit=onRefresh 则 halt）
            （若 checkpoint=onRefresh 则 Core.checkpointRestore）
            WebServerStartStopLifecycle.start     RC1 31–35  ← 端口在这里绑定
      startup.started + "Started in X (process running for Y)"
      listeners.started                           ApplicationStartedEvent + Liveness CORRECT
      callRunners                                 本地 755
      listeners.ready                             ApplicationReadyEvent + Readiness ACCEPTING_TRAFFIC
```

## 本章小结

Spring Boot 的启动是一条可以按行号走完的直线：构造器装 SPI，`run()` 在 `refresh()` 之前准备 Environment 和主类，`refresh()` 内部依次做自动配置、单例创建和 Lifecycle 启动，Runner 夹在存活和就绪之间。`SpringApplication.refresh()` 只有一行，是这条直线上最值得记住的证据。

启动优化不是另一个框架特性，而是选择这条直线上的哪一段去缩短。懒初始化和后台 Bean 缩短单例创建，AOT 缩短解析，CDS 与 Leyden 缩短类加载，CRaC 把已经创建好的进程变成快照。端口绑定、存活、就绪是三个不同的时刻。先用 Startup 端点确认时间在哪一段，再动对应的那一个开关；写进 `application.yml` 却走 `SpringProperties` 的那三个键，不会参与这条直线。
