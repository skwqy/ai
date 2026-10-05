# Log 家族深度源码解析（SLF4J · Logback · Log4j2 · Log4j 1.x · JUL · JCL——写给初学者的架构全景）

> **本文基于的源码**：`D:\code\3rd\slf4j`（tag `v_2.0.20`）、`D:\code\3rd\logback`（tag `v_1.5.38`）、`D:\code\3rd\logging-log4j2`（tag `rel/2.26.1`，commit `dd0f9d255e24`）；Spring Boot 侧引用 `D:\code\3rd\spring-boot`（4.2.0-SNAPSHOT main 分支）。文中所有【源码证据】的文件路径与行号均为对上述版本实际读取所得。
>
> **版本取舍说明**：SLF4J main 分支已进入 3.0.0-rc0、Logback 进入 1.7.0-rc0（未来主推模块化），但生态里绝大多数项目用的仍是 **SLF4J 2.0.x + Logback 1.5.x**，Log4j2 用的是 **2.x 稳定线**，因此本文行号对齐上述稳定 tag；其架构与 1.7/1.2 时代（StaticLoggerBinder 绑定）的差异在 2.2、2.3 节专门展开。
>
> **阅读约定**：每章"先白话、后源码"——先用一两句人话讲清"这是什么、为什么需要它"，再给出源码证据链（`模块/src/main/java/.../类名.java` + 方法名 + 行号 + 代码片段）。行号只对上述 tag 精确，读者用 IDEA 打开源码按类名 + 方法名定位即可。

## 如何读这份文档

如果你刚接触 Java 日志体系（被 slf4j / logback / log4j / log4j2 / jcl / jul 这一堆名字绕晕了），推荐两遍读法：

- **第一遍（建立地图，1~2 小时）**：只读第一章（总览）的全部白话段与两张大图（1.3 全景分层图、6.1 桥接方向图），加上各章"本章小结"。目标是能回答：SLF4J 和 Logback 是什么关系？为什么 classpath 里会同时出现 log4j-over-slf4j 和 log4j-to-slf4j？Spring Boot 默认用了哪套？
- **第二遍（深入源码）**：按第二章（SLF4J 绑定机制）→ 第三章（Logback）→ 第四章（Log4j2）→ 第六章（桥接实战）的顺序对照【源码证据】逐行读；第五章（遗产三兄弟）与第七章（使用指南）可随用随查；第八章（贯通视图）收束全景。

---

# 一、总览：Log 家族的定位、历史恩怨与整体架构

## 1.1 一句话定位

**SLF4J 是门面（API），Logback 与 Log4j2 是实现（引擎），Log4j 1.x / JUL / JCL 是历史包袱（遗产）**。

业务代码只应该面对一个抽象——`Logger` 接口。这个接口背后由谁真正往磁盘写、怎么格式化、怎么异步，全都由 classpath 上的"绑定"决定。这和 JDBC 的设计一模一样：`java.sql.Connection` 是规范，MySQL/Oracle 驱动是实现，`DriverManager` 负责发现驱动。

它解决的不是"怎么打日志"这一个点，而是**企业应用的日志治理问题**：

1. **解耦**：业务库、第三方库各自用不同的日志框架写日志，若没有门面，你要同时管 5 套配置文件。
2. **可替换**：今天用 Logback，明天要换 Log4j2（比如为了异步吞吐），业务代码零改动。
3. **统一输出**：第三方库里"漏出来"的 JUL/JCL/Log4j1 调用，能被桥接进同一个管道、同一个格式、同一个文件。

## 1.2 历史恩怨：为什么会有这么多日志框架

理解 Log 家族，一半是理解它的**历史**——这不是设计师没想清楚，而是一个人（Ceki Gülcü）和社区博弈 20 年的结果：

| 时间 | 事件 | 意义 |
|---|---|---|
| 1996~2001 | Ceki Gülcü 写出 **Log4j**，后捐给 Apache | 日志概念体系的奠基者：Logger 层级、Level、Appender、Layout 都是它发明的 |
| 2002 | JDK 1.4 内置 **JUL**（java.util.logging），Sun 拒绝直接内置 Log4j | 第一个"官方副本"，功能弱、使用繁琐，但 Java 世界从此有了两套日志 |
| 2002 | Apache 推出 **JCL**（commons-logging）：第一个门面 | 让"代码面向抽象"成为共识，但它的运行时发现机制埋了 ClassLoader 地雷（见 5.3 节） |
| 2006 | Ceki 离开 Apache，创立 **SLF4J**（门面）+ **Logback**（实现） | SLF4J 用编译期绑定替代 JCL 的运行时发现；参数化日志 `log.info("{} 就绪", name)` 避免无谓字符串拼接 |
| 2012~2014 | Apache 吸取教训重写 Log4j1，推出 **Log4j2**（2.0 GA 于 2014） | api/core 双模块 + LMAX Disruptor 异步 + 插件架构，性能全面反超 |
| 2015.08 | Apache 官方宣布 **Log4j 1.x EOL** | Log4j1 正式成为遗产（2022 年还爆出 CVE-2021-4104 JMSAppender 漏洞补刀） |
| 2021.12 | **Log4Shell**（CVE-2021-44228）爆发，Log4j2 < 2.15 存在 JNDI 远程代码执行 | 日志框架史上最大安全事件；JNDI Lookup 默认开启被关停（2.17+） |
| 2022 | **SLF4J 2.0** 发布：绑定机制从 `StaticLoggerBinder` 切换为 `ServiceLoader` | 门面发现实现的姿势现代化（2.3 节） |
| 2023~ | Logback 1.5.x、SLF4J 2.0.x 成为生态默认；main 分支迈向 slf4j 3.0 / logback 1.7 | Logback 1.5.13+ 与 Log4j2 2.24+ 均要求 JDK 11+ |

**这张表要背下来的结论**：新项目只用三样东西——`slf4j-api` + 一个绑定实现（Logback 或 Log4j2）+ 一组"把旧日志拖进门面"的桥接包。其余全是遗产处理问题。

## 1.3 全景分层图：六套框架如何堆成三层

```
┌─────────────────────────── 你的业务代码 ───────────────────────────┐
│   只写一种 API（强烈建议 slf4j-api 或 log4j-api，见第八章选型）      │
└───────────────────────────────────────────────────────────────────┘
        │                      │                    ▲
        ▼                      ▼                    │ (桥接包把旧 API 的
┌─ API 层（门面）──────────────────────────────┐    │  调用"重定向"进来)
│ slf4j-api        log4j-api       JCL   JUL  │────┘
│ (org.slf4j.*)    (org.apache.    (commons-  (jdk 内置
│                   logging.log4j)  logging)
└───────┬──────────────────┬──────────▲────────┘
        │ ServiceLoader    │ Provider │ ServiceLoader
        │ 绑定 Provider     │ 发现     │ (log4j-to-slf4j)
        ▼                  ▼          │
┌─ 实现层（引擎）──────────────────────────────┐
│ Logback                    Log4j2           │
│ logback-core (通用管道)     log4j-core      │
│ logback-classic (SLF4J 适配)                │
└─────────────────────────────────────────────┘
        │
        ▼
┌─ 输出层 ────────────────────────────────────────────────────────┐
│ Console / File / RollingFile / Socket / Kafka / Syslog / DB / …  │
│ (Appender + Encoder/Layout + Filter 的组合管道)                   │
└─────────────────────────────────────────────────────────────────┘
```

关键认识：**API 和实现之间不是继承关系，而是"发现 + 委托"关系**。SLF4J 通过 `ServiceLoader` 找到一个 Provider（第三章讲 Logback 的，第四章讲 Log4j2 反向提供的），拿到 `ILoggerFactory`，之后每次 `getLogger` 都是委托。桥接包则是"旧 API 的替身演员"——`log4j-over-slf4j` 提供了 Log4j1 的所有类名但内部全部转发给 SLF4J。

## 1.4 概念字典：同一件事在各框架里的名字

六套框架概念高度同构（毕竟都源于 Log4j 的思想），对照着记最省力：

| 概念 | Logback / SLF4J | Log4j2 | Log4j 1.x | JUL |
|---|---|---|---|---|
| 日志记录器 | `ch.qos.logback.classic.Logger` | `org.apache.logging.log4j.core.Logger` | `org.apache.log4j.Logger` | `java.util.logging.Logger` |
| 严重级别 | TRACE(5000)<DEBUG(10000)<INFO(20000)<WARN(30000)<ERROR(40000)，**数值越大越严重** | OFF(0)<FATAL(100)<ERROR(200)<WARN(300)<INFO(400)<DEBUG(500)<TRACE(600)，**数值越大越啰嗦** | 与 Logback 同源 | SEVERE/WARNING/INFO/CONFIG/FINE/FINER/FINEST |
| 配置里的级别节点 | `<logger name="x" level="INFO"/>` | `<Logger name="x" level="INFO"/>` | `<logger name="x">` | `-Djava.util.logging.config.file` |
| 输出器 | `Appender`（core 接口） | `Appender` | `Appender` | `Handler` |
| 格式化 | `Encoder`（写字节）+ `Layout`（转字符串） | `Layout`（含 `%d %p` 的 PatternLayout） | `Layout` | `Formatter` |
| 过滤器 | 全局 `TurboFilter`（构造事件前）+ `Filter`（事件后） | 全局/Appender/LoggerConfig 级 `Filter` | `Filter` | `Filter` |
| 上下文变量 | `MDC`（slf4j 接口 + LogbackMDCAdapter） | `ThreadContext`（含 map + stack 两部分） | `MDC` | 无（靠 SLF4J 桥接后补） |
| 上下文栈 | NDC 已废，用 `ThreadLocalMapOfStacks`（CloseableThreadContext） | `ThreadContext` 的 stack 部分 | `NDC` | 无 |
| 标记对象 | `Marker`（SLF4J 接口） | `Marker`（log4j-api） | 无 | 无 |
| 上下文单例 | `LoggerContext`（Logback 里通常一个） | `LoggerContext`（**可多实例**，按 ClassLoader 隔离，4.3 节） | `LogManager` 静态仓 | `LogManager` |
| 配置文件 | `logback.xml` / `logback-test.xml` | `log4j2.xml/json/yaml/properties`（test 前缀优先） | `log4j.xml` / `log4j.properties` | `logging.properties` |
| 热更新 | `<configuration scan="30 seconds">` | `<Configuration monitorInterval="30">` | 无（手动） | 无 |
| 异步方案 | `AsyncAppender`（ArrayBlockingQueue） | `AsyncLogger`（Disruptor）+ `AsyncAppender`（队列） | 无原生 | 无 |

【源码证据】两个 Level 数值体系方向相反，极易记反，务必实锤：
- Logback `logback-classic/src/main/java/ch/qos/logback/classic/Level.java` 第 32-36 行：`ERROR_INT = 40000; WARN_INT = 30000; INFO_INT = 20000; DEBUG_INT = 10000; TRACE_INT = 5000`。判断是否启用用 `effectiveLevelInt > level.levelInt` 则拒绝（`Logger.java` 第 381 行）——**数值小=更啰嗦**。
- Log4j2 `log4j-api/src/main/java/org/apache/logging/log4j/spi/StandardLevel.java` 第 29-59 行：`OFF(0), FATAL(100), ERROR(200), WARN(300), INFO(400), DEBUG(500), TRACE(600)`。判断用 `intLevel >= level.intLevel()` 则启用（`core/.../Logger.java` 第 540 行 `PrivateConfig.filter`）——**数值大=更啰嗦**。

## 1.5 关键问题 → Log 家族方案映射（全文导览）

| 开发中的关键问题 | 谁负责 | 怎么做到 | 详见 |
|---|---|---|---|
| 业务代码不锁定具体日志框架 | SLF4J | `LoggerFactory.getLogger()` 首次调用时经 ServiceLoader 静态绑定一个 Provider | 第二章 |
| 多个绑定包同时在 classpath 上，选哪个？ | SLF4J | `providersList.get(0)` 取第一个并告警其余；`-Dslf4j.provider` 可显式指定 | 2.3 |
| 同名 `com.a.B` 与 `com.a.B.C` 的日志要继承配置 | Logger 层级 | 点分命名树 + effectiveLevel 向下传播 + additive 向上传播 Appender | 3.4、4.5 |
| 第三方库用 JUL/JCL/Log4j1 打日志怎么办 | 桥接包 | `jul-to-slf4j` / `jcl-over-slf4j` / `log4j-over-slf4j` / `log4j-to-slf4j` 提供旧 API 的"替身" | 第六、五章 |
| 日志跟不上吞吐（CPU 花在等磁盘上） | 异步日志 | Logback：队列 + 后台单线程；Log4j2：Disruptor 环形缓冲 + 无 GC 事件复用 | 3.8、4.8 |
| 打日志产生大量临时对象加剧 GC | GC-free | ThreadLocal 复用 LogEvent/Message/StringBuilder（log4j2 独有强项） | 4.9 |
| 生产环境改日志级别要重启？ | 热更新 | Logback `scan` 周期任务；Log4j2 `monitorInterval` + WatchManager | 3.10、4.11 |
| 排查一条请求的全链路日志 | MDC/ThreadContext | 每线程一个 map 存 traceId，PatternLayout 里 `%X{traceId}` 输出 | 3.9、4.10 |
| 一次 `log.info()` 到底发生了什么 | 本文主线 | 两条时间线（8.1）：同步 Logback 路线 vs 全异步 Log4j2 路线 | 第八章 |
| Spring Boot 里怎么换默认日志实现 | Boot 集成 | `LoggingSystem` 抽象 + starter 依赖编排 | 7.3 |

## 1.6 全文章节地图

```
第一章 总览          —— 地图与历史
第二章 SLF4J         —— 门面如何"找到"实现（绑定机制的三个时代）+ 1.x→2.0 演进全景
第三章 Logback       —— 经典实现：层级树、Appender 管道、队列异步、scan 热更新
第四章 Log4j2        —— 现代实现：api/core 双轨、Logger/LoggerConfig 分离、Disruptor、插件体系
第五章 遗产三兄弟     —— Log4j1 / JUL / JCL 的设计、价值与死因
第六章 桥接矩阵       —— 全家桶怎么拼、死循环陷阱、Spring Boot 默认配方
第七章 使用指南       —— 从零搭建两种栈的完整配置与代码规范 + 配置文件语法全解
第八章 贯通视图       —— 两条时间线、选型决策树、面试十连
第九章 附录          —— 类速查表、系统属性速查、依赖坐标、排错清单
```

---

# 二、SLF4J：门面与绑定机制（slf4j-api）

> 本章小结预告：SLF4J 的全部灵魂集中在 `LoggerFactory` 一个类里——**首次调用 getLogger 时，经 ServiceLoader 找 Provider，取第一个，全局唯一绑定；绑定期间发生的日志先攒在队列里，绑定成功后回放**。

## 2.1 模块定位

slf4j 仓库是多模块 Maven 工程（`D:\code\3rd\slf4j` 根目录实测 15 个模块），按"用户感知"分组：

| 分组 | 模块 | 一句话 |
|---|---|---|
| 门面本体 | `slf4j-api` | 唯一该进你 pom 的核心包：`Logger`、`LoggerFactory`、`Marker`、`MDC`、`ILoggerFactory` |
| 最小实现（测试用） | `slf4j-simple` | 绑定即生效，stderr 输出；`slf4j-nop` 吞掉一切 |
| JDK 内置实现 | `slf4j-jdk14` | 把 SLF4J API 绑定到 JUL；`slf4j-jdk-platform-logging` 适配 System.Logger |
| 遗产实现 | `slf4j-log4j12`、`slf4j-reload4j` | 绑定到 Log4j1 / reload4j（reload4j 是 Log4j1 的安全续命分叉），新项目禁用 |
| 桥接（旧→新） | `jcl-over-slf4j`、`jul-to-slf4j`、`log4j-over-slf4j` | 把 JCL / JUL / Log4j1 的调用重定向到 SLF4J |
| 扩展 | `slf4j-ext` | `XLogger`、`EventBuilder` 等进阶 API；`integration` 是回归测试套件；`osgi-over-slf4j` OSGi 场景 |

slf4j-api 自己只有一个原则：**零依赖**。pom 里它是无任何第三方依赖的空壳，这就是它能在任何 classpath 上立足的原因。

## 2.2 getLogger 的四态生命周期：双检锁 + 重入代理 + NOP 兜底

先看用户视角的最短代码：

```java
private static final Logger log = LoggerFactory.getLogger(OrderService.class);
log.info("order {} created", orderId);
```

第一行触发整个绑定流程。`slf4j-api/src/main/java/org/slf4j/LoggerFactory.java` 用一个 `INITIALIZATION_STATE` 状态机管理生命周期（第 97-113 行）：

```java
static final int UNINITIALIZED = 0;          // 未初始化
static final int ONGOING_INITIALIZATION = 1; // 正在初始化（别的线程正在 bind）
static final int FAILED_INITIALIZATION = 2;
static final int SUCCESSFUL_INITIALIZATION = 3;
static final int NOP_FALLBACK_INITIALIZATION = 4; // classpath 上没有任何 Provider
static volatile int INITIALIZATION_STATE = UNINITIALIZED;
static final SubstituteServiceProvider SUBST_PROVIDER = new SubstituteServiceProvider();
static volatile SLF4JServiceProvider PROVIDER;
```

【源码证据】`getProvider()`（第 506-528 行）是所有 `getLogger` 的必经之路：

```java
static SLF4JServiceProvider getProvider() {
    if (INITIALIZATION_STATE == UNINITIALIZED) {
        synchronized (LoggerFactory.class) {
            if (INITIALIZATION_STATE == UNINITIALIZED) {   // 双检锁：绑定全局只做一次
                INITIALIZATION_STATE = ONGOING_INITIALIZATION;
                performInitialization();                    // → bind()
            }
        }
    }
    switch (INITIALIZATION_STATE) {
    case SUCCESSFUL_INITIALIZATION: return PROVIDER;
    case NOP_FALLBACK_INITIALIZATION: return NOP_FALLBACK_SERVICE_PROVIDER; // 没绑上 → NOP 吞日志
    case FAILED_INITIALIZATION: throw new IllegalStateException(UNSUCCESSFUL_INIT_MSG);
    case ONGOING_INITIALIZATION: return SUBST_PROVIDER;  // ★ 重入：初始化期间再来 getLogger
    }
    throw new IllegalStateException("Unreachable code");
}
```

（状态常量定义在第 97-113 行：`UNINITIALIZED=0`（97 行）→ `ONGOING_INITIALIZATION=1`（98 行）→ `FAILED_INITIALIZATION=2`（99 行）→ `SUCCESSFUL_INITIALIZATION=3`（100 行）→ `NOP_FALLBACK_INITIALIZATION=4`（101 行），`PROVIDER` 字段在第 113 行。）

两个设计点值得咀嚼：

1. **重入代理（SUBST_PROVIDER）**：如果某个类的静态初始化器在日志系统初始化过程中又去 `getLogger` 并打日志（例如 Provider 自己的构造函数里用了别的类的 `static Logger`），此时若抛异常或阻塞会造成初始化地狱。SLF4J 的解法是给这类"早到的" Logger 一个 `SubstituteLogger` 替身，它把期间所有日志事件**攒进一个 LinkedBlockingQueue**。
2. **NOP 兜底**：classpath 上没有任何 Provider 时退化为空实现（所有方法为空），而不是让应用起不来——日志是辅助设施，不能反过来杀死业务。

## 2.3 绑定机制：从 StaticLoggerBinder 到 ServiceLoader

### 2.3.1 2.0 时代的绑定主流程

【源码证据】`LoggerFactory.bind()`（第 193-218 行）：

```java
private final static void bind() {
    try {
        List<SLF4JServiceProvider> providersList = findServiceProviders();
        reportMultipleBindingAmbiguity(providersList);       // 找到多个 → 打印"实际绑定+被忽略"清单
        if (providersList != null && !providersList.isEmpty()) {
            PROVIDER = providersList.get(0);                 // ★ 取第一个，后面的全部忽略
            earlyBindMDCAdapter();                           // 把实现的 MDCAdapter 挂给 slf4j MDC 门面
            PROVIDER.initialize();                           // ★ 只在这里调用一次
            INITIALIZATION_STATE = SUCCESSFUL_INITIALIZATION;
            reportActualBinding(providersList);
        } else {
            INITIALIZATION_STATE = NOP_FALLBACK_INITIALIZATION;
            Reporter.warn("No SLF4J providers were found.");
            ...
        }
        postBindCleanUp();                                   // 回放攒下的事件
    } catch (Exception e) {
        failedBinding(e);
        throw new IllegalStateException("Unexpected initialization failure", e);
    }
}
```

`findServiceProviders()`（第 116-137 行）的查找顺序是：

1. **显式指定**：`-Dslf4j.provider=xxx`（常量定义在第 95 行 `PROVIDER_PROPERTY_KEY = "slf4j.provider"`），命中则直接反射实例化（`loadExplicitlySpecified`，第 233-254 行）——多绑定冲突时的"手动拍板"开关。
2. **ServiceLoader**：`ServiceLoader.load(SLF4JServiceProvider.class, ...)`，扫描所有 jar 的 `META-INF/services/org.slf4j.spi.SLF4JServiceProvider` 文件，逐个实例化（`safelyInstantiate` 捕获 `ServiceConfigurationError` 防止单个坏包炸全局）。

Provider 的契约就是 5 个方法（`slf4j-api/src/main/java/org/slf4j/spi/SLF4JServiceProvider.java`）：

```java
public interface SLF4JServiceProvider {
    ILoggerFactory getLoggerFactory();       // getLogger 的真正实现者
    IMarkerFactory getMarkerFactory();
    MDCAdapter getMDCAdapter();              // MDC 门面背后的真实现
    String getRequestedApiVersion();         // 声明自己兼容的 slf4j-api 版本
    void initialize();                       // 后端初始化（读配置文件就在这里发生）
}
```

`versionSanityCheck()`（第 383-402 行）校验第 4 项：api 侧的兼容列表是 `{"2.0"}`（第 165 行），Provider 报上来的版本若不是 2.0 开头就警告——这就是"slf4j-api 2.x 配 1.7 时代绑定"会看到版本不匹配警告的出处。

### 2.3.2 1.7 时代的遗产检测

SLF4J 1.x 的绑定方式是每个实现包在固定路径塞一个类：`org/slf4j/impl/StaticLoggerBinder.class`，SLF4J 靠 `getResources` 扫到它反射加载。2.0 全面放弃这种方式，但**保留了检测**——【源码证据】`LoggerFactory.java` 第 271 行定义了老路径常量：

```java
private static final String STATIC_LOGGER_BINDER_PATH = "org/slf4j/impl/StaticLoggerBinder.class";
```

当没有任何 Provider 被找到时，`bind()` 第 210-211 行会调用 `findPossibleStaticLoggerBinderPathSet()` + `reportIgnoredStaticLoggerBinders()`（第 256-267 行），在控制台打出那条著名警告：

```
Class path contains SLF4J bindings targeting slf4j-api versions 1.7.x or earlier.
Ignoring binding found at [jar:file:.../slf4j-log4j12-1.7.36.jar!/org/slf4j/impl/StaticLoggerBinder.class]
```

**实战含义**：把 slf4j-api 升到 2.x 后，老的 1.7 绑定包（如 `slf4j-log4j12`、`logback-classic 1.2.x`）会"看起来在 classpath 上但不生效"，这条警告就是诊断入口。

## 2.4 绑定期间的日志去哪了：SubstituteLogger 与事件回放

【源码证据】`postBindCleanUp()`（第 296-301 行）→ `fixSubstituteLoggers()`（第 303-312 行）→ `replayEvents()`（第 319-336 行）：

```java
private static void replayEvents() {
    final LinkedBlockingQueue<SubstituteLoggingEvent> queue = SUBST_PROVIDER.getSubstituteLoggerFactory().getEventQueue();
    ...
    while (true) {
        int numDrained = queue.drainTo(eventList, maxDrain);   // maxDrain = 128
        ...
        for (SubstituteLoggingEvent event : eventList) {
            replaySingleEvent(event);     // 对 isDelegateEventAware 的替身 logger 重新 log
            ...
        }
    }
}
```

三步舞：绑定成功后，① 把每个替身 Logger 的 delegate 换成真 Logger（`substLogger.setDelegate(logger)`）；② 把攒下的事件按阈值 128 一批 drain 出来回放（前提是替身背后的实现"事件感知"，即事件回放不会重复打）；③ 清空队列释放内存。这就是为什么**有些启动早期日志会"慢半拍"出现在控制台**——它们是被回放的。

## 2.5 API 面：参数化、Marker 与 Fluent API

`org.slf4j.Logger` 接口（slf4j-api）的核心用法与语义：

```java
// ① 参数化日志：占位符 {}。就算 INFO 被过滤，也不会发生字符串拼接——
//    因为不格式化字符串就不知道是否启用；只有真的要打，才格式化。
log.debug("user {} login from {}", userId, ip);

// ② 异常约定：最后一个参数若是 Throwable，自动识别为异常，不占 {} 位
log.error("call order-service failed, orderId={}", orderId, e);

// ③ Marker：给日志打"业务标签"，供后端 Filter 精准放行/拦截（如审计日志单独落地）
Marker audit = MarkerFactory.getMarker("AUDIT");
log.info(audit, "sensitive action by {}", userId);

// ④ 2.0 Fluent API（源码证据：Logger.java 第 114-131 行的 default 方法）
log.atDebug().addKeyValue("uid", userId).log("login");      // addKeyValue 是 2.0 新增
log.atLevel(Level.TRACE).setCause(ex).log("detailed trace");
```

【源码证据】Fluent API 的实现是"默认方法桥接"——`Logger.java` 第 129-131 行：

```java
default public LoggingEventBuilder atLevel(Level level) {
    if (isEnabledForLevel(level)) return makeLoggingEventBuilder(level);
    else return NOP_BUILDER;   // 未启用返回空实现，链式调用零成本
}
```

而 `isDebugEnabled()` 这类布尔判断（老代码里常见的 `if (log.isDebugEnabled())`）在新写法下基本可以退场——Fluent API 内部已经做了"未启用就给 NOP 构建器"的短路。

## 2.6 桥接器族谱（slf4j 视角）

slf4j 仓库里三个桥接模块的原理各不相同，注意方向都是**旧 API 的调用 → 转发进 SLF4J**：

| 桥接包 | 替换掉的坐标 | 原理 |
|---|---|---|
| `jcl-over-slf4j` | `commons-logging:commons-logging` | 提供同包同名的 `org.apache.commons.logging.LogFactory`/`Log`，内部全转发 SLF4J |
| `log4j-over-slf4j` | `log4j:log4j`（1.x） | 提供同包同名的 `org.apache.log4j.*`，转发 SLF4J |
| `jul-to-slf4j` | （不替换坐标，共存） | 不动 JDK 的 Logger 类，而是在 JUL 的 root Logger 上挂一个 Handler：`SLF4JBridgeHandler.install()` 后，所有 JUL LogRecord 经 `publish()`（`jul-to-slf4j/src/main/java/org/slf4j/bridge/SLF4JBridgeHandler.java` 第 298-322 行）翻译成 SLF4J 调用 |

JUL 桥接比较特殊——JDK 类没法被替换，所以走的是"Handler 挂载"而不是"类替换"，代码里必须手动（或让 Spring Boot 帮你）调一次 `SLF4JBridgeHandler.removeHandlersForRootLogger()`（第 174 行，先清掉 JUL 默认的 ConsoleHandler，防止日志打两遍）+ `SLF4JBridgeHandler.install()`（第 127 行）。

**死循环陷阱**（第六章展开）：`log4j-over-slf4j` 与 `slf4j-log4j12` 不能同时上 classpath——前者把 Log4j1 调用转给 SLF4J，后者把 SLF4J 绑定到 Log4j1，互相转发直到栈溢出。

## 2.7 版本演进全景：1.x → 2.0 改了什么（和什么没变）

> 本节为逐文件对照的版本考古：1.7 侧证据取自 tag `v_1.7.36`（1.7 线终版，commit `e4b93526`，经 `git show v_1.7.36:<path>` 实读，行号该 tag 实测），2.0 侧取自 `v_2.0.20`。一句话结论先给出：**对使用者 API 完全向后兼容，真正"断裂"的是绑定包生态；一切断裂都源于绑定机制的换代**。

### 2.7.1 十个维度的总对比

| 维度 | 1.7.36 | 2.0.20 | 证据 |
|---|---|---|---|
| Java 基线 | **Java 5**（root pom `required.jdk.version=1.5`） | **Java 8**（parent pom `jdk.version=8`，注释 "java.util.ServiceLoader requires Java 6"） | 两个 tag 的 pom.xml |
| 绑定契约 | `org.slf4j.impl` 三件套：`StaticLoggerBinder` + `StaticMDCBinder` + `StaticMarkerBinder` | `SLF4JServiceProvider` 一接口五方法统一供给 | 2.3 节已逐行展开 |
| 发现方式 | `ClassLoader.getResources` 扫固定路径 `org/slf4j/impl/StaticLoggerBinder.class` + 反射单例 | `ServiceLoader` 扫 `META-INF/services` | 2.3.2 / 2.3.1 |
| 显式指定绑定 | 无此能力 | `-Dslf4j.provider=实现类全名` | `LoggerFactory.java` 第 95 行（2.0） |
| 版本协商 | `API_COMPATIBILITY_LIST = {"1.6", "1.7"}`（第 102 行） | `{"2.0"}`（第 165 行） | 各自 LoggerFactory |
| MDC/Marker 绑定 | `MDC.java` 直接 `import StaticMDCBinder`（第 33 行，第 97-102 行做 1.7.14 前后字段名兼容反射） | Provider 统一 `getMDCAdapter()/getMarkerFactory()`，`earlyBindMDCAdapter`（第 226-231 行） | 各自源码 |
| JPMS | 无 module-info（后期补丁版仅清单 Automatic-Module-Name） | 真 JPMS 模块：`module org.slf4j { ... uses org.slf4j.spi.SLF4JServiceProvider; }`（`slf4j-api/src/main/java9/module-info.java`，multi-release jar） | 实读 module-info |
| spi 包内容 | 4 个接口：`LocationAwareLogger` / `LoggerFactoryBinder` / `MDCAdapter` / `MarkerFactoryBinder` | 新增 `SLF4JServiceProvider`、`LoggingEventBuilder` + `Default/NOPLoggingEventBuilder`、`LoggingEventAware`、`CallerBoundaryAware`；旧两个 Binder 保留兼容 | `org/slf4j/spi/` 目录清单 |
| 框架内部上报 | `org.slf4j.helpers.Util.report` → 固定 System.err，不可调 | `org.slf4j.helpers.Reporter`：`-Dslf4j.internal.verbosity=DEBUG/INFO/WARN/ERROR`（第 65 行）、`-Dslf4j.internal.report.stream`（第 58 行）可调级别与输出流 | Reporter.java |
| API 增量 | 无 Fluent API | `atLevel()/atDebug()...` 链 + `addKeyValue` 结构化键值（见 2.7.3） | `Logger.java` 第 114-131 行等 |

### 2.7.2 绑定机制：从"占位三件套"到 ServiceLoader（最有价值的一处改造）

1.7 有个少有人注意的细节：**slf4j-api 自己的源码树里就有 `org/slf4j/impl/StaticLoggerBinder.java`**——javadoc 自述 "meant to provide a **dummy** StaticLoggerBinder"（真实现由各绑定 jar 提供）。既然是假的，为什么必须存在？因为 `LoggerFactory`、`MDC`、`MarkerFactory` 编译期要引用 `StaticMDCBinder` 这些符号；但它又绝不能进 api 的 jar——否则 api 会"绑定到自己"永远输出 NOP。1.7 的解法是构建期物理删除（`slf4j-api/pom.xml` 的 maven-antrun-plugin 在 `process-classes` 阶段执行 `<delete dir="target/classes/org/slf4j/impl"/>`，echo 原文 "Removing slf4j-api's dummy StaticLoggerBinder and StaticMarkerBinder"）。

这套"源码占位 + 打包删除 + getResources 扫描反射"机制的实际痛点：多 ClassLoader / OSGi 环境下资源扫描语义模糊；找不到绑定时的报错文案晦涩（`Failed to load class org.slf4j.impl.StaticLoggerBinder`，新人无从下手）。2.0 一次性解决：**标准 `java.util.ServiceLoader`、显式 `initialize()` 生命周期、版本协商、`-Dslf4j.provider` 拍板**（2.3 节）。对生态的直接影响：1.7 绑定包全部失效（被检测并忽略，2.3.2），绑定矩阵整体换代——logback-classic 1.3+/1.5.x、`log4j-slf4j2-impl`、`slf4j-reload4j` 才是 2.0 时代的合法绑定。

### 2.7.3 API 增量：Fluent API 与结构化键值

1.7 的 `Logger` 是纯方法集（每个级别 × 每种参数形态一个重载）。2.0 用 **default 方法**扩展接口而不破坏任何旧代码：

```java
// LoggingEventBuilder 链（org.slf4j.spi.LoggingEventBuilder）
log.atDebug().addKeyValue("uid", userId)      // 第 86 行 addKeyValue / 第 96 行 addKeyValue(key, Supplier)
     .setCause(ex)                            // 第 48 行 setCause
     .log(() -> "expensive " + compute());    // 第 167 行 log(Supplier<String>)
```

`addKeyValue` 的产物是新类型 `org.slf4j.event.KeyValuePair`（event 包 2.0 新增，与 `DefaultLoggingEvent` 一起加入）——键值对不再是"参数化消息"，而是结构化字段。这是 SLF4J 迈向结构化/可观测日志的第一步：门面先立契约，后端（如新版 Logback）再择机消费。未启用级别时返回 `NOPLoggingEventBuilder` 短路（2.5 节已引 `Logger.java` 第 129-131 行）。**代价**：启用路径多一个小对象的分配；全部 1.7 式重载保留、热路径性能不变。

### 2.7.4 MDCAdapter 扩容与 helpers 换血

2.0 的 `MDCAdapter` 接口新增 5 个方法（`MDCAdapter.java` 第 80-131 行）：`getCopyOfContextMap`、`pushByKey`、`popByKey`、`getCopyOfDequeByKey`、`clearDequeByKey`——为了支撑 `CloseableThreadContext` 的嵌套上下文（map + 栈）跨门面统一；logback 的 `LogbackMDCAdapter.threadLocalMapOfDeques`（3.7 节）正是对这组新契约的实现。

一个常见误传要纠正：**`MDC.putCloseable` / `MDCCloseable`（try-with-resources 写法）在 1.7.0 就有了**（1.7.36 的 `MDC.java` 第 73 行内部类、第 165 行用法），不是 2.0 新增；2.0 新增的是上面那组栈操作。

helpers 包同时换血：`Util.report` 退役为 `Reporter`（内部告警终于可调级别/输出流）；新增 `ThreadLocalMapOfStacks`、`Slf4jEnvUtil`。而 **`SubstituteLogger` 攒队列回放机制两代一致**——1.7.36 的 helpers 里就有它（2.4 节机制描述对两代都成立）。

### 2.7.5 模块清单增删（`git ls-tree` 两 tag 实测）

| 1.7.36 有、2.0.20 没有 | 2.0.20 新增 | 说明 |
|---|---|---|
| `slf4j-android` | —— | Android 官方场景已弃，社区另行维护 |
| `slf4j-jcl` | —— | "把 SLF4J 绑定到 JCL"的方向倒挂绑定，随 JCL 退场（5.3 节） |
| —— | `slf4j-jdk-platform-logging` | 把 JDK 9+ 的 `System.Logger`（`LoggerFinder`）接到 SLF4J，配合 `jul-to-slf4j` 补齐 JDK 平台日志的最后一块 |

另一个微妙事实：**`slf4j-log4j12` 在 1.7.36 里就已经是重定位壳**——目录里只剩 `LICENSE.txt + pom.xml`，pom description 原话 "SLF4J LOG4J-12 relocated to slf4j-reload4j"（依赖坐标直接指向 `slf4j-reload4j`）。这是 Log4j1 EOL（1.2 节）后 1.7 线末期的安全善后，2.0 延续同一壳。simple 模块则从 `org.slf4j.impl` 包搬到 `org.slf4j.simple`（配 `SimpleServiceProvider`），对使用者透明——它的配置文件在 7.5.2 详谈。

### 2.7.6 什么没变（吃定心丸）

1. **用户代码零改动**：1.x 的所有调用姿势在 2.0 下照常编译运行（default 方法扩展的功劳）。
2. 参数化 `{}` 语义与 `MessageFormatter` 热路径一致。
3. 多绑定 first-wins 语义一致（1.7 取第一个 StaticLoggerBinder，2.0 取 `providersList.get(0)`）。
4. `event.Level` 1.7 已有（`org/slf4j/event/Level.java`），2.0 只是配合 Fluent API 转正。
5. `simplelogger.properties` 的 11 个键两代逐字一致（7.5.2 实测）。

### 2.7.7 迁移清单（slf4j-api 升 2.x 三步）

1. **查绑定**：classpath 上必须有 2.0 兼容 Provider（logback-classic ≥ 1.3、log4j-slf4j2-impl、slf4j-simple ≥ 2.0、slf4j-jdk14 ≥ 2.0 等）；老 Static 绑定会被忽略并打印 "Class path contains SLF4J bindings targeting slf4j-api versions 1.7.x or earlier"（2.3.2 节）。
2. **清过渡包**：老 `slf4j-log4j12` jar、`slf4j-jcl` 等物理排除；多 Provider 冲突时用 `-Dslf4j.provider` 拍板。
3. **可选升级代码**：`isDebugEnabled()` 守卫 → `atLevel()` 链；审计/链路日志用 `addKeyValue`；想接管 JDK 平台日志加 `slf4j-jdk-platform-logging`。

> 展望：slf4j main 分支已出现 `3.0.0-rc0` 快照（本文克隆时实测，方向是更彻底的模块化与新基线），写作时未 GA，不展开。

## 2.8 本章小结

- SLF4J = 零依赖 API 包 + 一个静态绑定点。`getLogger` 首次触发双检锁初始化：**显式 `-Dslf4j.provider` → ServiceLoader 扫 `SLF4JServiceProvider` → 取第一个 → `initialize()` 一次**。
- 找不到 Provider 退化为 NOP，找到老式 1.7 绑定包则警告"忽略"；多绑定取第一个并打印完整清单。
- 初始化期间的日志经 `SubstituteLogger` 攒队列、成功后回放（每批 128 条）。
- 三种桥接姿势：类替换（jcl/log4j-over-slf4j）、Handler 挂载（jul-to-slf4j）、Provider 反向提供（log4j-to-slf4j，见 4.2）。
- 新代码用参数化 + Fluent API（`atLevel().addKeyValue().log()`），`isDebugEnabled()` 大多数场景可以退休。
- 1.x → 2.0 的演进全景（2.7 节）：Java 5→8、Static 三件套→ServiceLoader、真 JPMS 模块、Fluent API/KeyValuePair、MDCAdapter 栈操作、Reporter 可调内部上报；用户代码零改动，断裂只在绑定包生态。

---

# 三、Logback：经典实现（logback-core / logback-classic / logback-access）

> 本章小结预告：Logback 的架构 = **core 的"事件管道"（Appender/Encoder/Layout）+ classic 的"SLF4J 适配层"（Logger 层级树 + TurboFilter + Joran 配置）**。它是唯一"门面与实现同一作者"的实现，与 SLF4J 的贴合度最高；异步用朴素队列，性能取向是"够用且稳"。

## 3.1 模块定位

| 模块 | 内容 | 关键类 |
|---|---|---|
| `logback-core` | 与日志"格式"无关的通用管道：Appender、Encoder、异步基类、Joran 配置引擎、状态系统 Status | `Appender`、`AsyncAppenderBase`、`OutputStreamAppender` |
| `logback-classic` | SLF4J 的实现：Logger 层级树、Level、MDC、`logback.xml` 解析 | `Logger`、`LoggerContext`、`LogbackServiceProvider` |
| `logback-access` | 与 Servlet 容器集成记录 HTTP 访问日志（与业务日志互不相干） | `AccessEvent` |

【源码证据】绑定入口 `logback-classic/src/main/java/ch/qos/logback/classic/spi/LogbackServiceProvider.java`：实现第二章的 `SLF4JServiceProvider` 接口，第 41 行声明 `REQUESTED_API_VERSION = "2.0.99"`（供 2.3.2 节的版本校验），`initialize()` 中完成两件事——设置默认 LoggerContext 名字与 MDCAdapter，然后 `new ContextInitializer(defaultLoggerContext).autoConfig()`（读配置文件的真正入口），最后 `defaultLoggerContext.start()`。

## 3.2 配置文件查找顺序：谁最先说了算

【源码证据】`logback-classic/src/main/java/ch/qos/logback/classic/util/ContextInitializer.java` 第 71-112 行 `autoConfig(ClassLoader)` 的完整决策链：

```
1. ServiceLoader 加载自定义 Configurator 实现并按 @ConfiguratorRank 排序（第 82-88 行）
   —— Spring Boot 就是靠这步把 logback-spring.xml 塞进流程的
2. 依次调用，返回 DO_NOT_INVOKE_NEXT_IF_ANY 即终止（第 98-101 行）
3. 都没接管 → 内置双保险（第 55 行）：
   INTERNAL_CONFIGURATOR_CLASSNAME_LIST =
     {"ch.qos.logback.classic.util.DefaultJoranConfigurator",   // 读 logback.xml
      "ch.qos.logback.classic.BasicConfigurator"}               // 兜底：控制台 + root=DEBUG
```

`DefaultJoranConfigurator.performMultiStepConfigurationFileSearch()`（`classic/util/DefaultJoranConfigurator.java` 第 47-62 行）按以下顺序找文件：

1. 系统属性 `-Dlogback.configurationFile=xxx`（`ClassicConstants.CONFIG_FILE_PROPERTY`）；
2. classpath 上的 **`logback-test.xml`**（`ClassicConstants.TEST_AUTOCONFIG_FILE`，第 64 行）——测试环境覆盖神器；
3. classpath 上的 **`logback.xml`**（`ClassicConstants.AUTOCONFIG_FILE`，第 63 行）。

一个都没有 → `BasicConfigurator` 兜底：ConsoleAppender + root=DEBUG（这就是启动时没写 logback.xml 却满屏 DEBUG 日志的原因）。

## 3.3 Logger 层级树：effectiveLevel 的维护是"推"出来的

SLF4J 的 `ILoggerFactory` 在 Logback 里由 `LoggerContext` 实现。`getLogger("com.a.b.C")` 沿点号逐级下钻，边走边建，最后挂进 `loggerCache`（`classic/LoggerContext.java` 第 138 行起，创建子节点时在 `synchronized (logger)` 块里调 `createChildByName`，第 156-159 行，保证同名的并发创建只发生一次）。

每个 Logger 上维护一个 `effectiveLevelInt` 缓存（`classic/Logger.java` 第 59 行），**默认值 DEBUG**（`localLevelReset()`，第 323-330 行：root 显式 `level = Level.DEBUG`，非 root 的 `level = null` 表示"继承父级"）。妙处在于：改级别时不是"用时向上查"，而是**父级变更向下推送**：

【源码证据】`Logger.setLevel()`（第 150-177 行）与 `handleParentLevelChange()`（第 185-200 行）：

```java
public synchronized void setLevel(Level newLevel) {
    ...
    if (newLevel == null) {
        effectiveLevelInt = parent.effectiveLevelInt;      // 置 null = 重新继承父级
        newLevel = parent.getEffectiveLevel();
    } else {
        effectiveLevelInt = newLevel.levelInt;             // 记录自己的 effective 值
    }
    if (childrenList != null) {
        for (Logger child : childrenList) {
            child.handleParentLevelChange(effectiveLevelInt);  // ★ 向下推送
        }
    }
    loggerContext.fireOnLevelChange(this, newLevel);
}

private synchronized void handleParentLevelChange(int newParentLevelInt) {
    if (level == null) {                 // 只有"自己没显式设级别"的孩子才跟随
        effectiveLevelInt = newParentLevelInt;
        if (childrenList != null) { /* 继续向更深处递归推送 */ }
    }
}
```

代价是"写时递归"，收益是"读时 O(1)"——日志判断在热路径上，每条日志都要读 effectiveLevel，这个交换是划算的。

## 3.4 一次 log.info() 的完整旅程（同步路径）

【源码证据】`classic/Logger.java`。以 `info(String format, Object arg)` 为例（最终汇入 `filterAndLog_1`，第 392-407 行）：

```
log.info("user {} login", uid)
 └─ filterAndLog_1 (392-407)
     ├─ loggerContext.getTurboFilterChainDecision_1(...)        ① 全局 TurboFilter 前置过滤
     │     DENY → 直接 return；ACCEPT → 跳过级别检查直通
     ├─ NEUTRAL → effectiveLevelInt > INFO_INT ? return : 往下   ② O(1) 级别判断（3.3 的缓存）
     └─ buildLoggingEventAndAppend (427-432)
         ├─ new LoggingEvent(localFQCN, this, level, msg, t, params)  ③ 事件对象化（此刻才格式化参数）
         └─ callAppenders(le) (256-268)
             for (Logger l = this; l != null; l = l.parent) {    ④ 沿层级树向上找 Appender
                 writes += l.appendLoopOnAppenders(event);
                 if (!l.additive) break;                         ⑤ additive=false 截断向上传播
             }
             if (writes == 0) loggerContext.noAppenderDefinedWarning(this);  // "no appenders" 警告出处
```

两个与直觉不符的点：

1. **Appender 是"向上继承"的**：`com.a.b.C` 自己没配 Appender，事件会一路向上被 root 的 Appender 处理；`<logger name="com.a" additivity="false">` 在第 260 行截断。
2. **TurboFilter 在事件对象创建之前**运行（①在③前），它可以用"拒绝"避免昂贵的 LoggingEvent 构造——这是 Logback 的性能武器之一（如 `DuplicateMessageFilter` 抑制重复日志）。

## 3.5 输出管道：Appender → Encoder → Layout

Logback 把"写出去"拆成两级职责：

- **Layout**：`ILoggingEvent` → String（人类可读格式）；核心实现 `PatternLayout`，转换符注册表在静态块里初始化（`classic/PatternLayout.java` 第 43 行起：`d/date`→`DateConverter`、`ms/micros`→`MicrosecondConverter`……你写的每个 `%d %p %m` 都对应一个 Converter 类）。
- **Encoder**：把 Layout 产物（或原生 POJO）变成字节流，并管理**头部/尾部字节**（文件重启时的 log header 由 encoder 输出，这是它比裸 Layout 强的地方）。

【源码证据】`logback-core/src/main/java/ch/qos/logback/core/OutputStreamAppender.java` 第 202-203 行——所有文件/控制台输出的最终落点：

```java
protected void writeOut(E event) throws IOException {
    byte[] byteArray = this.encoder.encode(event);   // LayoutWrappingEncoder 内部包一个 PatternLayout
    this.outputStream.write(byteArray);
}
```

`RollingFileAppender`（core/rolling 包）= `FileAppender` + 触发策略（`TimeBasedRollingPolicy` / `SizeBasedTriggeringPolicy` / `SizeAndTimeBasedRollingPolicy`）+ 滚动动作（按日期改文件名、压缩历史文件）。它是日常用得最多、也最常配错的组件，完整示例见 7.1。

## 3.6 AsyncAppender：朴素队列 + 可丢弃策略

【源码证据】`logback-core/src/main/java/ch/qos/logback/core/AsyncAppenderBase.java`：

- 默认容量 `DEFAULT_QUEUE_SIZE = 256`（第 54 行）——注意，**这个默认值在生产环境基本等于没缓冲**；
- 装填（调用方线程，`append()` 第 161-168 行）：若队列剩余容量已低于丢弃阈值且事件"可丢"，直接丢弃；否则 `put()`；
- `put()` 第 173-179 行：`neverBlock=false`（默认）时走 `putUninterruptibly()`（第 180-193 行）——**队列满时调用线程阻塞等待**；`neverBlock=true` 时 `offer()` 失败即丢事件，绝不拖慢业务；
- 单个后台 worker 线程从队列取事件，逐个交给包在里面的真 Appender。

classic 层的 `ch.qos.logback.classic.AsyncAppender`（继承 core 的基类）补充了 Logback 特有的两件事：

```java
protected boolean isDiscardable(ILoggingEvent event) {        // 第 39-44 行
    Level level = event.getLevel();
    return level.toInt() <= Level.INFO_INT;                   // TRACE/DEBUG/INFO 视为可丢
}
// discardingThreshold 未显式配置时 = queueSize / 5（AsyncAppenderBase 第 112 行）
// 即队列剩余容量 < 20%（已装 80%）时开始丢低级别事件
```

**结论**：Logback 异步的丢日志语义是"**队列快满时优先保 ERROR/WARN，牺牲 TRACE/DEBUG/INFO**"。另有两个坑要记住：① 异步后调用栈属于 worker 线程，`%class %method %line` 位置信息默认拿不到——`includeCallerData=true` 可修，但会拖慢生产端（`preprocess` 里同步取栈，第 45-51 行）；② `maxFlushTime` 默认 1000ms（第 70-71 行），停机时 worker 被打断后最多等 1 秒冲刷队列。

## 3.7 MDC：链路日志的地基

【源码证据】`classic/util/LogbackMDCAdapter.java` 第 47-49 行——每个线程两个 Map 的设计：

```java
final ThreadLocal<Map<String, String>> readWriteThreadLocalMap = new ThreadLocal<>();
final ThreadLocal<Map<String, String>> readOnlyThreadLocalMap = new ThreadLocal<>();
private final ThreadLocalMapOfStacks threadLocalMapOfDeques = new ThreadLocalMapOfStacks();
```

- `put/get/clear` 走读写 map（`put` 在第 68 行）；
- **只读 map 是给异步 Appender 的**：事件进入队列前，`prepareForDeferredProcessing()` 把当前 MDC 快照拷贝进事件（防止 worker 线程取到的是"后来的"MDC）；拷贝的引用放进只读 map 防止下游误改；
- `threadLocalMapOfDeques` 支撑 `MDC.pushByKey/popByKey`（第 188-189 行），配合 `org.slf4j.MDC.putCloseable()` 的 try-with-resources 用法（slf4j-api 的 `CloseableThreadContext`）。

用法（业务代码）：

```java
MDC.put("traceId", traceId);          // 入口处
try { doBusiness(); } finally { MDC.remove("traceId"); }   // 务必清理，防线程池串号
```

```xml
<pattern>%d{HH:mm:ss.SSS} [%thread] %-5level [%X{traceId:-NONE}] %logger{36} - %msg%n</pattern>
<!-- %X{traceId:-NONE}：从 MDC 取 traceId，取不到输出 NONE -->
```

## 3.8 配置热更新：scan="30 seconds" 背后的定时任务

【源码证据】完整链条：

1. `logback.xml` 的 `<configuration scan="30 seconds">` 被 `ConfigurationAction` 解析为 `scanPeriodStr` 属性（`classic/joran/action/ConfigurationAction.java` 第 25-35 行）；
2. `ConfigurationModelHandlerFull.scheduleReconfigureOnChangeTask()`（`classic/model/processor/ConfigurationModelHandlerFull.java` 第 119-152 行）创建 `ReconfigureOnChangeTask`，然后：

```java
ScheduledExecutorService scheduledExecutorService = context.getScheduledExecutorService(); // ContextBase 第 242-246 行懒创建
ScheduledFuture<?> scheduledFuture = scheduledExecutorService.scheduleAtFixedRate(
        rocTask, duration.getMilliseconds(), duration.getMilliseconds(), TimeUnit.MILLISECONDS); // 第 149-150 行
context.addScheduledFuture(scheduledFuture);
```

3. 任务每次运行（`classic/joran/ReconfigureOnChangeTask.java` 第 46-79 行 `run()`）：从 `ConfigurationWatchList` 检查配置文件及 `<include>` 引用文件的 lastModified 是否变化，变了 → 取消自身旧调度 → 重新执行 XML 配置（`performXMLConfiguration`），全程不停应用。

与 Log4j2 的文件监听（4.11）相比：Logback 是**固定周期轮询**， granularity = scan 周期，实现简单可靠。

## 3.9 Spring Boot 与 Logback 的连接点：`logback-spring.xml` 详解

**先纠正一个名字**：这个文件叫 **`logback-spring.xml`**（`-spring` 是**后缀**），不存在 `spring-logback.xml` 这种约定——`-spring` 后缀是 Spring Boot 对"框架感知变体"的通用命名法，同一套规则还派生出 `logback-test-spring.xml`、`logback-spring.groovy`，以及 Log4j2 的 `log4j2-spring.xml`。它不是 Logback 的功能，而是 **Boot 专属扩展文件名 + Boot 专属解析器 + 两个 Boot 专属标签**的组合拳。本节全部展开。

### 3.9.1 为什么需要它：logback.xml 被 Logback 解析得太早

回顾 3.2 节：`logback.xml` 由 Logback 自己在"第一次 `getLogger`"时经 `ContextInitializer.autoConfig()` 解析——那一刻 Boot 的 `Environment` 可能还没构建，`<springProfile>`、`<springProperty>` 这些需要读 Spring 配置的标签无从谈起。所以 Boot 官方文档（`documentation/spring-boot-docs/src/docs/antora/modules/reference/pages/features/logging.adoc` 第 384 行）明确建议：

> When possible, we recommend that you use the `-spring` variants for your logging configuration (for example, `logback-spring.xml` rather than `logback.xml`).

`-spring` 变体的本质：**跳过 Logback 的自解析，等 Boot 的 Environment 就绪后，由 Boot 用扩展解析器一次性加载**，从此 `<springProfile>` 能按环境裁剪配置、`<springProperty>` 能把 `application.yml` 的属性注入日志变量。

### 3.9.2 发现与加载时机：Environment 就绪那一刻

【源码证据 1·触发点】`core/spring-boot/.../context/logging/LoggingApplicationListener.java` 监听三类事件（第 177 行：`ApplicationEnvironmentPreparedEvent` / `ApplicationPreparedEvent` / `ContextClosedEvent`）。Environment 构建完成、上下文尚未刷新时（第 224-225 行分派 → 第 243-248 行 `onApplicationEnvironmentPreparedEvent` → `initialize(environment, classLoader)`），日志系统才开始初始化——**这就是"-spring 文件能读 Environment"的时间窗**。`initializeSystem`（第 328-340 行）的取值顺序：`logging.config` 属性有值 → 直接用它；没有 → 走下面的约定搜索。

【源码证据 2·搜索顺序】`LogbackLoggingSystem`（第 81 行，继承 `AbstractLoggingSystem`）声明标准名清单（第 127-129 行：`logback-test.groovy / logback-test.xml / logback.groovy / logback.xml`），父类 `getSpringConfigLocations()`（第 131-138 行）把它们自动派生成 `-spring` 变体（`logback.xml → logback-spring.xml`）。`AbstractLoggingSystem.initializeWithConventions()`（第 74-92 行）三段式接管：

```java
String config = getSelfInitializationConfig();      // ① 先找标准名（logback.xml/logback-test.xml）
if (config != null && logFile == null) {
    reinitialize(initializationContext);            //    存在 → Logback 自己已 autoConfig，Boot 做二次初始化
    return;
}
if (config == null) {
    config = getSpringInitializationConfig();       // ② 标准名不存在 → 找 -spring 变体（logback-spring.xml）
}
if (config != null) {
    loadConfiguration(initializationContext, config, logFile);
    return;
}
loadDefaults(initializationContext, logFile);       // ③ 都没有 → Boot 内置默认配置
```

**结论**：`logback-spring.xml` 会被自动发现（`logback.xml` 不存在时）；**两者并存时标准名赢**，`-spring` 变体被忽略。③分支的 `loadDefaults`（`LogbackLoggingSystem` 第 235-254 行）用的是 Boot 内置的 `DefaultLogbackConfiguration`——控制台彩色 pattern + 可选文件滚动（第 64-77 行的 pattern 里全是 `${LOG_DATEFORMAT_PATTERN:-...}` 这类"环境变量优先、内置兜底"的占位符，所以 `logging.pattern.console` 等横切配置才能生效）。

### 3.9.3 解析器扩展：SpringBootJoranConfigurator

【源码证据】`core/spring-boot/.../logging/logback/SpringBootJoranConfigurator.java`（第 84 行 `extends JoranConfigurator`）——Boot 对 3.2 节 Joran 体系的"注册表级扩展"：

```java
// 第 110-115 行：往 Joran 规则库塞两个新标签
public void addElementSelectorAndActionAssociations(RuleStore ruleStore) {
    super.addElementSelectorAndActionAssociations(ruleStore);
    ruleStore.addRule(new ElementSelector("configuration/springProperty"), SpringPropertyAction::new);
    ruleStore.addRule(new ElementSelector("*/springProfile"), SpringProfileAction::new);
    ruleStore.addTransparentPathPart("springProfile");
}
// 第 99-107 行：模型管道里挂对应的 ModelHandler（真正干活的，见 3.9.4）
defaultProcessor.addHandler(SpringPropertyModel.class,
        (hc, mic) -> new SpringPropertyModelHandler(this.context, this.initializationContext.getEnvironment()));
defaultProcessor.addHandler(SpringProfileModel.class,
        (hc, mic) -> new SpringProfileModelHandler(this.context, this.initializationContext.getEnvironment()));
```

**注意 handler 构造参数里的 `initializationContext.getEnvironment()`**——Environment 就是在这里被递给日志配置的，这正是裸 Logback 做不到的事。另外两点：`<included>` 子文件会经 `configuratorSupplier`（第 120-124 行）同样用扩展解析器处理；AOT/native 场景下整个模型树被序列化进 `META-INF/spring/logback-model`（第 173 行），运行期直接反序列化跳过 XML 解析（第 127-135 行）。

而加载入口 `loadConfiguration`（`LogbackLoggingSystem` 第 257-279 行）→ `configureByResourceUrl`（第 305-310 行）**只用这一个扩展解析器**：

```java
private void configureByResourceUrl(LoggingInitializationContext initializationContext,
        LoggerContext loggerContext, URL url) throws JoranException {
    JoranConfigurator configurator = new SpringBootJoranConfigurator(initializationContext);   // 第 307 行
    configurator.setContext(loggerContext);
    configurator.doConfigure(url);
}
```

加载前后还各有一件小事值得知道：`stopAndReset`（第 312-318 行）在 JUL 桥已安装时挂上 `LevelChangePropagator`（logback 级别变化反向传播回 JUL）；`reportConfigurationErrorsIfNecessary`（第 281-303 行）把 StatusManager 里的 ERROR 汇总成 `IllegalStateException("Logback configuration error detected")`——**Boot 时代日志配置错误会让应用启动失败**，而不是 Logback 裸用时的"打警告继续跑"。

### 3.9.4 两个专属标签的源码实现

**`<springProfile name="...">`：按环境裁剪整段子树**。【源码证据】`SpringProfileModelHandler.java` 第 51-56 行——不匹配就**把整个子模型标记跳过**，不是运行期判断，而是**配置解析期就把不相关环境的 XML 节点剪掉**：

```java
public void handle(ModelInterpretationContext intercon, Model model) throws ModelHandlerException {
    SpringProfileModel profileModel = (SpringProfileModel) model;
    if (!acceptsProfiles(intercon, profileModel)) {
        model.deepMarkAsSkipped();          // 整棵子树不参与后续装配
    }
}
```

`acceptsProfiles`（第 58-75 行）三步：`name` 属性按逗号拆分 → `OptionHelper.substVars` 做 logback 变量替换 → `environment.acceptsProfiles(Profiles.of(profileNames))` 交给 Spring 判定。所以它**天然支持 profile 表达式**：`name="dev | staging"`（或）、`name="!production"`（取反）、`name="prod & mysql"`（且），语义与 `@Profile` 注解完全一致。

**`<springProperty name source defaultValue scope>`：Environment 属性注入为 logback 变量**。【源码证据】`SpringPropertyModelHandler.java` 第 53-63 行：

```java
public void handle(ModelInterpretationContext intercon, Model model) throws ModelHandlerException {
    SpringPropertyModel propertyModel = (SpringPropertyModel) model;
    Scope scope = ActionUtil.stringToScope(propertyModel.getScope());   // local / context / system 三档
    ...
    PropertyModelHandlerHelper.setProperty(intercon, propertyModel.getName(), getValue(source, defaultValue), scope);
}
private String getValue(String source, String defaultValue) {
    ...
    return this.environment.getProperty(source, defaultValue);          // 第 70 行：从 Environment 取值
}
```

之后 pattern/配置里就能用 `${appName}` 引用它——这是把 `spring.application.name` 之类属性带进日志格式（甚至文件名）的标准姿势。

### 3.9.5 四个高频陷阱（每个都有源码出处）

1. **把扩展标签写进 `logback.xml` → 启动报错**。Logback 自解析先于 Boot，裸 Joran 不认识这两个标签，StatusManager 里留下 `no applicable action for [springProperty]`（官方文档第 800-801 行给出的真实报错）；虽然 Boot 的 reinitialize 会用扩展解析器重解析成功，但第一次解析留下的 ERROR 状态会被 `reportConfigurationErrorsIfNecessary`（第 281-303 行）汇总抛出。官方文档第 793 行的结论："要么用 `logback-spring.xml`，要么显式定义 `logging.config` 属性"。
2. **`scan="true"` 与扩展标签不兼容**。Logback 的热更新重解析用的是**裸** `new JoranConfigurator()`（logback `classic/joran/ReconfigureOnChangeTask.java` 第 128-130 行），不认识 Boot 标签——文件一变，重解析即失败并回退到上次配置。官方文档第 795 行的 WARNING 原话："The extensions cannot be used with Logback's configuration scanning."（要用热更新 + 按环境裁剪，改成部署期占位符或多个配置文件）。
3. **`-Dlogback.configurationFile` 在 Boot 下被忽略**。`LogbackLoggingSystem.initialize`（第 205-208 行）发现该系统属性会打警告并继续：`Ignoring 'logback.configurationFile' system property. Please use 'logging.config' instead.`——Boot 场景统一用 `logging.config=classpath:xxx.xml`。
4. **内置转换器 `%clr/%correlationId/%esb/%wex/%wEx` 不会自动进你的自定义文件**。这批 Boot 转换器只在"无任何配置文件"的默认路径注册（`DefaultLogbackConfiguration.java` 第 104-108 行 `config.conversionRule("clr", ColorConverter.class, ...)`）；写了 `logback-spring.xml` 后想用它们，需自己声明 `<conversionRule conversionWord="clr" converterClass="org.springframework.boot.logging.logback.ColorConverter"/>`。

### 3.9.6 Log4j2 的对应物：`log4j2-spring.xml`

同一个 `-spring` 命名法在 Log4j2 引擎下依然成立，但实现完全不同——Boot 没有给 Log4j2 做标签扩展，而是**复用 4.6 节的资源搜索**。【源码证据】`core/spring-boot/.../logging/log4j2/Log4J2LoggingSystem.java`：

- `getSelfInitializationConfig()`（第 151-153 行）直接问"Log4j2 自己已经加载的 Configuration 的 location"（自初始化检测）；
- `getSpringInitializationConfig()`（第 156-163 行）调 `configurationFactory.getConfiguration(getLoggerContext(), "-spring", null, getClassLoader())`——把名字参数传成 `-spring`，Log4j2 的 `Factory` 就按"前缀 + 名字 + 后缀"拼出 `log4j2-spring.xml`、`log4j2-test-spring.xml` 等资源名去 classpath 找。

也就是说 **`log4j2-spring.xml` 只是"让 Boot 决定加载哪个文件"，文件内容仍是标准 Log4j2 配置**（Spring 侧的支持靠 Boot 把 Environment 注册进 Log4j2 的属性源，`initialize` 第 264-267 行 `PropertiesUtil.addPropertySource`，供 `${spring:...}` 类查找使用），没有 logback 那两个专属标签的等价物（环境裁剪用 Log4j2 自己的属性替换与多文件 `log4j2.configurationFile` 逗号分隔合并实现）。

> 顺带补全 Boot 介入的两个时间点（与 7.3 的"启动日志延迟"呼应）：`LogbackLoggingSystem.beforeInitialize`（第 132-140 行）会先安装 JUL 桥（第 142-153 行 `SLF4JBridgeHandler.install()`，6.3 节说"Boot 帮你装桥"的出处）并挂上 `SUPPRESS_ALL_FILTER` 抑制一切日志；`initialize` 完成后移除该过滤器（第 203 行），被抑制的日志才恢复输出。

Boot 介入的机制面（`LogbackLoggingSystem.Factory` 第 511 行作为 `LoggingSystemFactory` SPI 抢先注册）在 3.2 节 ServiceLoader 处已交代；Log4j2 的对应物即本节引用的 `Log4J2LoggingSystem`。

## 3.10 本章小结

- Logback 分 core（管道）/ classic（SLF4J 适配）/ access（HTTP 访问日志）三模块；SLF4J 绑定由 `LogbackServiceProvider` 完成，配置加载经 `ContextInitializer.autoConfig`（自定义 Configurator SPI → DefaultJoranConfigurator → BasicConfigurator）。
- 配置查找顺序：`-Dlogback.configurationFile` → `logback-test.xml` → `logback.xml` → BasicConfigurator 兜底（DEBUG+控制台）。
- Logger 层级树用**父改子推**维护 `effectiveLevelInt`（读 O(1)）；Appender 沿树向上冒泡，`additivity=false` 截断。
- 一条日志：TurboFilter（构造事件前）→ 级别判断 → new LoggingEvent（此刻才格式化）→ callAppenders 向上冒泡 → Appender → Encoder → 磁盘。
- 异步 = ArrayBlockingQueue（默认 256，太小）+ 单 worker + 队列 80% 满丢 TRACE/INFO；neverBlock 换延迟为丢日志；位置信息默认丢失。
- 热更新 = `scan` 属性 → context 自带调度器周期轮询文件 lastModified（**与 Boot 扩展标签互斥**）。
- Spring Boot 侧：`logback-spring.xml`（注意是**后缀** `-spring`，无 `spring-logback.xml`）= 等 Environment 就绪后由 `SpringBootJoranConfigurator` 加载，获得 `<springProfile>`（解析期剪子树）与 `<springProperty>`（Environment→logback 变量）；与 `logback.xml` 并存时标准名优先（3.9 节）。

---

# 四、Log4j2：双轨架构与性能怪兽（log4j-api / log4j-core）

> 本章小结预告：Log4j2 的三板斧——①**api/core 物理分离**（api 包零依赖，core 缺席时 api 自动退化 SimpleLogger）；②**Logger 与 LoggerConfig 分离**（热更新时 Logger 不换，只换指针）；③**全链路异步**（AsyncLogger 用 Disruptor 环形缓冲 + 事件复用，吞吐比同步高一个数量级）。

## 4.1 模块定位

log4j2 仓库（`D:\code\3rd\logging-log4j2`）模块众多，日常相关的是这几个：

| 模块 | 一句话 |
|---|---|
| `log4j-api` | API 门面（对内也可直接编程使用）：`LogManager`、`Logger`、`Level`、`Marker`、`ThreadContext`、`Message` 体系 |
| `log4j-core` | 实现：`LoggerContext`、`Configuration`、Appender/Layout/Filter 全家桶、异步引擎、插件体系 |
| `log4j-slf4j2-impl` | SLF4J 2.x → Log4j2 的绑定（提供 `SLF4JServiceProvider`） |
| `log4j-slf4j-impl` | SLF4J 1.7.x → Log4j2 的绑定（老项目用） |
| `log4j-to-slf4j` | **反向桥**：Log4j2 API → SLF4J（应用被迫引 log4j-api 依赖的第三方库时，让这些日志走 SLF4J） |
| `log4j-jcl` / `log4j-jul` / `log4j-1.2-api` | JCL→Log4j2、JUL Handler→Log4j2、Log4j1 API→Log4j2 |
| `log4j-jakarta-*`、`log4j-cassandra/couchdb/docker/iostreams` | 各类集成 Appender |

## 4.2 api/core 双轨与 Provider 发现

【源码证据】`log4j-api/src/main/java/org/apache/logging/log4j/LogManager.java` 第 62-63 行，工厂字段在类加载时静态初始化：

```java
private static volatile LoggerContextFactory factory =
        ProviderUtil.getProvider().getLoggerContextFactory();
```

`ProviderUtil`（`log4j-api/.../util/ProviderUtil.java`）的发现姿势（第 125-161 行）：

1. `ServiceLoader.load(Provider.class, ...)` 扫所有 jar 的 `META-INF/services/org.apache.logging.log4j.spi.Provider`；
2. 兼容历史格式 `META-INF/log4j-provider.properties`（第 58 行常量 `PROVIDER_RESOURCE`，Spi/Provider.java 第 72 行的键名 `LoggerContextFactory`）；
3. 多个 Provider 时按优先级排序取最高者（`Log4jProvider` 带版本与优先级声明）。

log4j-core 在 `META-INF/services/org.apache.logging.log4j.spi.Provider` 注册了 `impl/Log4jProvider.java`（第 39 行 `class Log4jProvider extends Provider`），它提供 `Log4jContextFactory`（`core/impl/Log4jContextFactory.java` 第 48 行，同时实现 `LoggerContextFactory` 和 `ShutdownCallbackRegistry`）。

**api/core 分离的意义**：只引 log4j-api 不引 core 时，`ProviderUtil` 找不到实现会退化为 `SimpleLoggerContextFactory`——`LogManager.getContext()` 捕获 IllegalStateException 后显式降级（`LogManager.java` 第 98-107 行）：

```java
try {
    return factory.getContext(FQCN, null, null, true);
} catch (final IllegalStateException ex) {
    LOGGER.warn("{} Using SimpleLogger", ex.getMessage());
    return SimpleLoggerContextFactory.INSTANCE.getContext(FQCN, null, null, true);
}
```

与 SLF4J 的差异：SLF4J 没实现 → NOP（吞日志，静默）；Log4j2 没实现 → SimpleLogger（ERROR 级打到控制台）。**这也解释了为什么很多库直接依赖 log4j-api**——它自带最简实现，不会像 SLF4J 那样"没有绑定就彻底无声"。

## 4.3 LoggerContext：可多实例、可隔离

Logback 全局只有一个 LoggerContext；Log4j2 把它做成了**可以按 ClassLoader / 按命名域创建多个**的对象——容器类应用（Tomcat 多 webapp、OSGi、fat-jar 里的隔离类加载器）中每个 classloader 一套独立配置互不干扰。

【源码证据】`LogManager.getContext(boolean currentContext)`（第 116-127 行）经 `factory.getContext(fqcn, loader, currentContext...)` 到 `Log4jContextFactory`（第 153-155 行）：

```java
final LoggerContext ctx = selector.getContext(fqcn, loader, currentContext);
```

`selector` 默认 `ClassLoaderContextSelector`（按调用方 ClassLoader 键控），换 `-Dlog4j2.contextSelector=AsyncLoggerContextSelector` 则返回 `AsyncLoggerContext`（其 `newInstance` 造出来的是 AsyncLogger，见 4.8）——**一个系统属性把全应用切进全异步**。

core 的 `LoggerContext` 生命周期（`core/LoggerContext.java` 第 71 行 `extends AbstractLifeCycle`）：`start()`（第 300-322 行）拿 `configLock`，`setStarting()` → `reconfigure()`（读配置）→ 可选注册 shutdownHook → `setStarted()`。

## 4.4 Logger 与 LoggerConfig 分离：热更新的关键设计

Log4j2 有两棵"树"：运行期的 `core.Logger`（getLogger 创建，缓存在 `loggerRegistry`，`core/LoggerContext.java` 第 585-588 行 `loggerRegistry.computeIfAbsent(name, ...)`）和配置期的 `config.LoggerConfig`（XML 里 `<Logger>` 生成的，带 level/appenders/filter）。

【源码证据】`core/Logger.java` 第 484-541 行的 `PrivateConfig`——Logger 里只缓存一个**指向当前配置的指针包**：

```java
protected class PrivateConfig {
    public final LoggerConfig loggerConfig;    // 我该把事件交给配置树上的哪个节点
    public final Configuration config;         // 当前 Configuration（热更新后整体替换）
    private final Level loggerConfigLevel;
    private final int intLevel;                // 级别判断的 int 缓存
    private final boolean requiresLocation;    // 该配置是否需要调用位置（取栈昂贵）

    boolean filter(final Level level, final Marker marker, final String msg) {
        final Filter filter = config.getFilter();          // 全局 Filter
        if (filter != null) {
            final Filter.Result r = filter.filter(logger, level, marker, msg);
            if (r != Filter.Result.NEUTRAL) return r == Filter.Result.ACCEPT;
        }
        return level != null && intLevel >= level.intLevel();  // O(1) 级别判断
    }
}
```

热更新时 `reconfigure(Configuration)` + `updateLoggers()`（`core/LoggerContext.java` 第 807-832 行）把每个 Logger 的 privateConfig 换成新 Configuration 的引用（`loggerRegistry.getLoggers().forEach(logger -> logger.updateConfiguration(config))`）——**Logger 对象永远不变，变的只是指针**。业务代码里缓存的 `Logger` 字段热更新后自动生效，这个设计直接回答了"为什么 Log4j2 改配置不用重启应用也不用清 Logger 缓存"。

## 4.5 一次 log.info() 的完整旅程（同步路径）

```
log.info("user {} login", uid)          // api Logger（AbstractLogger 兜底实现）
 └─ logIfEnabled(FQCN, Level.INFO, null, msg, params)
     ├─ isEnabled(...) → privateConfig.filter(...)          ① 全局 Filter + intLevel >= INFO_INT？
     └─ core.Logger.logMessage (core/Logger.java 169-175)
         └─ ReliabilityStrategy.log(...)                    ② 可靠投递策略（见下）
             └─ new LogEvent（由 config 的 LogEventFactory 造，默认 ReusableLogEventFactory，见 4.9）
             └─ LoggerConfig.log (config/LoggerConfig.java 728-742)
                 ├─ isFiltered(event)                        ③ LoggerConfig 级 Filter
                 └─ processLogEvent (762-768)
                     ├─ event.setIncludeLocation(isIncludeLocation())
                     ├─ callAppenders(event) (803-811)       ④ 本节点 AppenderControl[] 逐个投递
                     └─ logParent(event) (796-800)           ⑤ additive && parent → 继续给父 LoggerConfig
```

与 Logback 对照的三个差异：

1. **两层 Filter**：Logger/LoggerConfig 级 filter 在 ①③ 两处，Appender 级 filter 在 AppenderControl 内部再拦一道；Logback 则是 TurboFilter（前置）+ Appender 内 Filter。
2. **ReliabilityStrategy**（②）：热更新或 Appender 重启的瞬间事件不能丢——默认 `AwaitUnconditionallyReliabilityStrategy` 等锁，可配 `log4j2.reliabilityStrategy`（高性能场景可换 LockingReliabilityStrategy 等）。Logback 没有这层概念。
3. **additive 向上传播的实现在配置树**（⑤在 LoggerConfig 链上），而不是 Logback 那样沿运行期 Logger 链。

`callAppenders` 值得看一眼源码（`config/LoggerConfig.java` 第 802-811 行）：

```java
@PerformanceSensitive("allocation")
protected void callAppenders(final LogEvent event) {
    final AppenderControl[] controls = appenders.get();
    //noinspection ForLoopReplaceableByForEach
    for (int i = 0; i < controls.length; i++) {      // 手写下标循环：避免迭代器对象分配
        controls[i].callAppender(event);
    }
}
```

`@PerformanceSensitive` + 手写 for —— Log4j2 的性能执念处处可见。

## 4.6 配置体系：工厂优先级与文件查找顺序

【源码证据】`core/config/ConfigurationFactory.java`：

1. **工厂本身是插件**：`getInstance()`（第 154-190 行）用 `PluginManager(CATEGORY).collectPlugins()` 收集所有 `ConfigurationFactory`，按 `@Order` 升序排列进 `factories`。四个内置工厂的优先级实测：**Xml @Order(5) → Json(6) → Yaml(7) → Properties(8)**（各工厂源文件第 28-35 行）。
2. **文件查找**（内部 `Factory.getConfiguration(ctx, name, uri)`，第 454-559 行）：

```
① 系统属性 log4j2.configurationFile（第 458 行）
    支持逗号分隔多文件 → CompositeConfiguration 合并（第 462-482 行，改级别可拆多文件了）
② 兼容 Log4j1 配置：log4j.configurationFile → 置 log4j1Experimental 标志（第 485-490 行）
③ classpath 资源：先 test 前缀后默认前缀，按工厂优先级逐个试（第 538-547 行 → 599-635 行）
    log4j2-test.xml → log4j2-test.json → log4j2-test.yaml → log4j2-test.properties
    → log4j2.xml → log4j2.json → log4j2.yaml → log4j2.properties
④ 一个都没有 → DefaultConfiguration（第 558 行）：
    root=ERROR + ConsoleAppender（属性 org.apache.logging.log4j.level 可改默认级别，第 35 行）
    并打出那句经典警告 "No Log4j 2 configuration file found. Using default configuration..."
```

对比记忆：Logback 是 `test.xml 优先`；Log4j2 是 **test.* 四兄弟整体优先**（且 XML 在自己这一组里也排最前）。

## 4.7 Plugin 体系：XML 里的每个标签都是插件

Log4j2 配置里的 `<Console>`、`<PatternLayout>`、`<RollingFile>`、`<ThresholdFilter>` ……全部对应一个 `@Plugin` 注解类。这就是它"配置即组装"的根基：新增一种 Appender 不用改 Log4j2 源码，写个插件类即可。

【源码证据】三件套：

```java
// core/config/plugins/Plugin.java（第 32-47 行）
public @interface Plugin {
    String name();        // XML 里的标签名，如 "Console"
    String category();    // 命名空间："Core"（Appender/Layout 等）、"ConfigurationFactory"、...
    ...
}

// core/config/plugins/processor/PluginProcessor.java（第 94 行）
// 编译期注解处理器把全部插件索引写进缓存文件：
"META-INF/org/apache/logging/log4j/core/config/plugins/Log4j2Plugins.dat"

// core/config/plugins/util/PluginManager.java（第 148 行 collectPlugins）
// 运行期从 classpath 的 Log4j2Plugins.dat 批量读取（避免逐包扫描），
// 额外支持 -Dlog4j2.plugin.packages 指定扫描包
```

自定义插件的写法（业务扩展示例）：

```java
@Plugin(name = "AlarmAppender", category = Core.CATEGORY_NAME, elementType = Appender.ELEMENT_TYPE)
public class AlarmAppender extends AbstractAppender {
    @PluginFactory
    public static AlarmAppender createAppender(@PluginAttribute("name") String name, ...) { ... }
    @Override public void append(LogEvent event) { /* 转发到告警系统 */ }
}
```

## 4.8 异步日志三件套：Log4j2 的护城河

Log4j2 的异步有三层粒度，按"谁被异步"分：

| 方案 | 打开方式 | 队列载体 | 作用范围 |
|---|---|---|---|
| `AsyncAppender` | XML 里 `<AsyncAppender><AppenderRef ref="..."/></AsyncAppender>` | `ArrayBlockingQueue`（可换 BlockingQueueFactory） | 包住的 Appender |
| **全异步 AsyncLogger** | `-Dlog4j2.contextSelector=org.apache.logging.log4j.core.async.AsyncLoggerContextSelector` | **LMAX Disruptor 环形缓冲** | 应用所有 Logger |
| 混合 AsyncLoggerConfig | XML 里 `<AsyncLogger>/<AsyncRoot>` 标签 | Disruptor（AsyncLoggerConfigDisruptor） | 只异步指定 Logger |

**方案 2（全异步）是 Log4j2 的招牌**，源码拆解：

【源码证据 1】事件入队（`core/async/AsyncLogger.java`）：AsyncLogger 继承 Logger 并实现 `EventTranslatorVararg<RingBufferLogEvent>`（第 70 行）。`logMessage` 后默认走 ThreadLocal 翻译器复用路径（第 235-248 行 `logWithThreadLocalTranslator`）：

```java
private void logWithThreadLocalTranslator(fqcn, level, marker, message, thrown) {
    // Implementation note: this method is tuned for performance. MODIFY WITH CARE!
    final RingBufferLogEventTranslator translator = getCachedTranslator(); // ThreadLocal 复用（125-129）
    initTranslator(translator, fqcn, level, marker, message, thrown);      // 把 5 个字段写进翻译器
    publish(translator);
}
private void publish(final RingBufferLogEventTranslator translator) {      // 第 271-275 行
    if (!loggerDisruptor.tryPublish(translator)) handleRingBufferFull(translator);
}
```

调用线程只做一次"字段搬运"就返回，**不格式化字符串、不取调用栈、不碰 I/O**——这些全留给消费线程。

【源码证据 2】环形缓冲的参数（`core/async/AsyncLoggerDisruptor.java` 第 124-152 行 + `DisruptorUtil.java` 第 35-37 行、87-103 行）：

```java
waitStrategy = DisruptorUtil.createWaitStrategy("AsyncLogger.WaitStrategy", factory); // 默认 TimeoutBlockingWaitStrategy（DefaultAsyncWaitStrategyFactory 第 31 行）
new Disruptor<>(RingBufferLogEvent.FACTORY, ringBufferSize, threadFactory, ProducerType.MULTI, waitStrategy); // 第 139 行
// DisruptorUtil.java:
private static final int RINGBUFFER_MIN_SIZE = 128;
private static final int RINGBUFFER_DEFAULT_SIZE = 256 * 1024;      // GC 模式默认 26 万槽
private static final int RINGBUFFER_NO_GC_DEFAULT_SIZE = 4 * 1024;  // GC-free 模式默认 4096（每槽对象常驻复用）
```

【源码证据 3】满环形缓冲怎么办（`AsyncLogger.handleRingBufferFull`，第 277-302 行）——三个分支一个都不能少：

```java
if (AbstractLogger.getRecursionDepth() > 1) {   // 递归保护：Appender 内部再打日志时直接同步落盘，防死锁
    logMessageInCurrentThread(...); return;
}
final EventRoute eventRoute = loggerDisruptor.getEventRoute(translator.level);
switch (eventRoute) {
    case ENQUEUE:     loggerDisruptor.enqueueLogMessageWhenQueueFull(translator); break; // 阻塞等空位（默认）
    case SYNCHRONOUS: logMessageInCurrentThread(...); break;    // 直接同步写，绝不丢也不等
    case DISCARD:     translator.clear(); break;                // 直接丢
}
```

路由由 `-Dlog4j2.asyncQueueFullPolicy=Discard`（配合 `-Dlog4j2.discardThreshold=INFO`）切换，语义对齐 3.6 的 Logback 丢弃策略但更精细。

【源码证据 4】GC-free 的事件复用（`core/async/RingBufferLogEvent.java`）：第 53 行声明 `implements LogEvent, ReusableMessage, CharSequence`——**同一个事件对象在环形缓冲里转完一圈被 `setValues`（第 96 行）重新填充**；参数数组用 `swapParameters` 交换复用（第 148、287 行）；消息文本用共享 StringBuilder，超大后 `StringBuilders.trimToMaxSize(messageText, Constants.MAX_REUSABLE_MESSAGE_SIZE)` 收缩（第 424 行）。配合 `-Dlog4j2.enable.threadlocals=true`（`log4j-api/util/Constants.java` 第 65 行，默认 `!IS_WEB_APP`，web 应用要手动开）——热路径日志**零对象分配**。

**代价清单**（写代码前就要知道）：异步后 `%class/%method/%line` 位置信息默认不取（`includeLocation="true"` 会让异步慢到接近同步）；MDC 走 `ThreadContext` 在入队时已复制进事件（无 Logback 那类手工坑，但跨线程提交任务时仍需自己传）；停机要保证 Disruptor 冲刷——Log4j2 的 shutdownHook 或 Spring Boot 的优雅停机会处理，自定义关闭逻辑要小心。

## 4.9 ReusableLogEventFactory：同步路径也省 GC

即便不用异步，`log4j-core` 默认用 `core/impl/ReusableLogEventFactory` 复用 LogEvent（每个线程一个，ThreadLocal 池化），而 Logback 每条日志都 `new LoggingEvent`。这就是"Log4j2 同步也比 Logback 略快"的来源之一。切换点在 `Configuration` 的 `LogEventFactory`，一般不动。

## 4.10 ThreadContext：MDC + NDC 二合一

`log4j-api` 的 `ThreadContext`（`api/ThreadContext.java`）同时提供 map（对应 MDC）和 stack（对应 Log4j1 的 NDC）两部分：

```java
ThreadContext.put("traceId", traceId);                       // map 部分
try (CloseableThreadContext.Instance ctx = ThreadContext.closeableThreadContext()
        .put("traceId", traceId).push("order-sub-flow")) {   // try-with-resources 自动清理（map+stack）
    doBusiness();
}
// PatternLayout：%X{traceId}（map）、%x（stack）
```

实现上委托给 `ThreadContextMap`（可换 `-Dlog4j2.disableThreadContextMap` / 自定义垃圾低模式实现 `ThreadContextMapFactory.init()`，见 `core/LoggerContext.onChange` 里的 `initApiModule()` 调用）。

## 4.11 热更新：monitorInterval 与 WatchManager

【源码证据】`<Configuration monitorInterval="30">` 的执行链（与 Logback 的差异：Log4j2 支持**秒级 interval 且只盯真正变化的文件**）：

1. 配置加载完成时 `AbstractConfiguration` 的 `initializeWatchers()`（`core/config/AbstractConfiguration.java` 第 277-298 行）：`monitorIntervalSeconds > 0` → `watchManager.setIntervalSeconds(...)`，并把 `ConfigurationFileWatcher` 挂到 `watchManager.watch(cfgSource, watcher)`（`watchManager` 第 145 行创建时就绑定了配置专用调度线程 `configurationScheduler`）；
2. 文件变化 → `LoggerContext.onChange(Reconfigurable)`（`core/LoggerContext.java` 第 846-868 行）：`reconfigurable.reconfigure()` 生成新 Configuration → `setConfiguration(newConfig)` → 各 Logger 经 4.4 的指针机制无感切换。

## 4.12 StatusLogger：日志框架自己的日志

Log4j2 初始化失败/配置错误的信息不往业务日志走（业务日志可能还没就绪），而是走内置 `StatusLogger`（默认 ERROR 级打 stderr）。排障开关：`-Dlog4j2.debug=true`（StatusLogger 全量输出）或配置 `<Configuration status="WARN">`。Logback 的对应物是 Status 体系（`StatusPrinter.printInCaseOfErrorsOrWarnings`，在 `LogbackServiceProvider.initializeLogger` 里，无 StatusListener 时自动打印）。

## 4.13 本章小结

- **api/core 物理分离**：api 包零依赖自带 SimpleLogger 兜底；实现经 `ServiceLoader(Provider)` / `log4j-provider.properties` 发现（`Log4jProvider` → `Log4jContextFactory`）。
- **LoggerContext 可多实例**（ClassLoaderContextSelector），`AsyncLoggerContextSelector` 一个属性切全异步。
- **Logger/LoggerConfig 分离 + PrivateConfig 指针包**：热更新只换指针，业务缓存的 Logger 引用永不过期。
- 一条日志：isEnabled（Filter+intLevel）→ ReliabilityStrategy → LogEvent（可复用）→ LoggerConfig.log → 本节点 AppenderControl[] → additive 向上给父 LoggerConfig。
- 配置查找：`log4j2.configurationFile`（支持逗号分隔合并）→ test 前缀四兄弟 → 默认前缀四兄弟 → DefaultConfiguration（ERROR+Console）。每个配置标签都是编译期索引进 `Log4j2Plugins.dat` 的插件。
- **异步三件套**：AsyncAppender（队列）< AsyncLoggerConfig（部分异步）< 全异步 AsyncLogger（Disruptor：生产端只搬运字段、RingBufferLogEvent 复用、满时 ENQUEUE/SYNCHRONOUS/DISCARD + 递归死锁保护）；GC-free 热路径零分配。
- `ThreadContext` = MDC + NDC；热更新靠 WatchManager 轮询；StatusLogger 是排障总开关。

---

# 五、遗产三兄弟：Log4j 1.x、JUL、JCL

> 本章小结预告：三者都还活在无数老系统的依赖树里。本章按"它教会了我们什么 → 它为什么该被退场 → 怎么退场（指路第六章桥接）"展开。Log4j1 与 JUL 的源码不在本文克隆清单内（Log4j1 已 EOL、JUL 在 JDK 里），以机制描述为主。

## 5.1 Log4j 1.x：概念奠基者，性能与安全的双重迟暮

**它教会了世界的**（今天 Logback/Log4j2 的概念表几乎全来自它）：Logger 点分层级 + 级别继承、Appender/Layout/Filter 分离、MDC/NDC、.properties/XML 配置。

**它为什么退场**：

1. **锁粒度**：Appender 写出在 `Category.callAppenders` 路径上持类级锁，高并发下线程大量阻塞在日志上（Log4j2 的 Disruptor 正是冲着这个来的）。
2. **配置能力停滞**：不支持热更新、无异步 Appender、自定义扩展要改代码。
3. **安全**：官方 2015-08 宣布 EOL 后仍被广泛使用，2021 年底 CVE-2021-4104（JMSAppender 反序列化，需特殊配置才受影响）与 2022 年的 CVE-2022-23305/23307 相继披露——一个不再发版的框架，漏洞永远无法被修复。
4. **续命分叉**：社区为存量系统提供了 reload4j（1.2.19+ 的安全修复版），作为"暂时不能重构"场景的最小代价替换。

**退场姿势**：代码里把 `log4j:log4j` 坐标替换为 `log4j-over-slf4j`（类名包名完全相同，业务零改动）；或直接替换为 `log4j-1.2-api`（API 桥到 Log4j2，由 Log4j2 官方维护，两者二选一，不要混用）。

## 5.2 JUL（java.util.logging）：JDK 的"官方平替"，为何没人爱

JDK 1.4 内置，设计上明显参考 Log4j：`Logger` / `Level` / `Handler`（≈Appender）/ `Formatter`（≈Layout）/ `Filter`。致命短板：

1. **默认配置简陋**：不配置 `logging.properties` 时只往控制台打 INFO 以上，文件轮转等要写代码或维护属性文件，表达力远逊 XML；
2. **级别模型别扭**：9 个级别（SEVERE~FINEST）与业界四六级对不齐，第三方库用它时映射混乱；
3. **性能弱**：LogRecord 每次必新建对象、无参数化惰性格式化（JUL 的占位符 `{0}` 要用 `MessageFormat`，且很晚才优化）；
4. **加载器问题**：JUL 是 bootstrap 类加载器加载的，其 `LogManager` 是 JVM 级单例——容器多应用场景天然隔离困难（Log4j2 的多 LoggerContext 就是为了解决这个）。

它今天存在的意义：作为 JDK 内置"保底"和 `slf4j-jdk14` 绑定的目标；反向地，`jul-to-slf4j` 把存量 JUL 调用拖进统一管道（5.1 节同款逻辑，Handler 挂载式桥接，见 2.6）。

## 5.3 JCL（commons-logging）：第一个门面的 ClassLoader 地狱

JCL 的历史功绩是确立了"面向门面编程"；它的历史罪名是**运行时发现机制**：`LogFactory.getLog()` 在**每次调用**时按顺序尝试：系统属性 → Log4j → JDK14Logger → SimpleLog……自省探测 classpath 上有什么就用什么。

三宗罪：

1. **不确定性**：同一应用，classpath 变化会静默切换底层实现，行为不可预期；
2. **ClassLoader 泄漏**：`LogFactory` 缓存放在底层 jar（常在父加载器），以业务类加载器为 key，容器 redeploy 时形成经典的 PermGen/Metaspace 泄漏（Tomcat 特意为其打了补丁级 workaround）；
3. **发现顺序玄学**：多容器（JBoss 老版本曾强制替换 JCL 实现）踩坑无数。

SLF4J 用两个改变终结了它：**编译期静态绑定**（首次 getLogger 固化 Provider，此后不再探测）与**无参构造反射调用**（不做 try-catch 自省）。退场姿势：坐标上用 `org.slf4j:jcl-over-slf4j` 替换 `commons-logging:commons-logging`，同包同名类直接接管。

## 5.4 本章小结

- Log4j1：概念奠基 + 锁粒度粗 + 已 EOL 不可修复 → `log4j-over-slf4j` 或 `log4j-1.2-api` 桥走，存量可暂用 reload4j 过渡。
- JUL：JDK 保底 + 配置表达力弱 + 全局单例 → `jul-to-slf4j` 拖进统一管道，或干脆用 `slf4j-jdk14` 当输出目标。
- JCL：运行时探测三宗罪（不确定、泄漏、玄学）→ `jcl-over-slf4j` 静态化。
- 三兄弟退场的共同心法：**不动业务代码，动坐标**——用同包名替身包完成无痛迁移。

---

# 六、桥接矩阵：全家桶怎么拼（依赖治理实战）

> 本章小结预告：桥接的本质是"**替身演员**"——让旧 API 的调用被同包名的替身类接管后转发到统一管道。本章给一张方向图、一张场景矩阵、Spring Boot 的默认配方，以及四种死循环陷阱。

## 6.1 桥接方向图（先看图再读字）

```
                     ┌──────────────────────────────────────────┐
                     │              统一管道（二选一）            │
                     │   SLF4J+Logback          Log4j2(core)    │
                     └─────▲───────────────────────▲────────────┘
                           │                       │
 你的代码 ──slf4j-api──→ ★ SLF4J 门面             │
                           │                       │
             log4j-slf4j2-impl │  log4j-core     │
             (SLF4J Provider)  │                 │
                           │                       │
 Log4j2 API 调用 ──log4j-api──→ ★ Log4j2 门面 ────┘
                           │
             log4j-to-slf4j│（Log4j2 api 的调用反向交给 SLF4J：Provider 指向 SLF4J）
                           ▼
 ──────── 以下全是"旧 API 替身"，把存量调用拖进 ★ ────────
 jcl-over-slf4j   （替换 commons-logging，同包名 LogFactory → SLF4J）
 log4j-over-slf4j （替换 log4j:log4j 1.x，同包名 Logger → SLF4J）
 jul-to-slf4j     （不换坐标：给 JUL root Logger 挂 SLF4JBridgeHandler → SLF4J）
 log4j-1.2-api    （替换 log4j:log4j 1.x → 直送 Log4j2 api/core，不经 SLF4J）
 log4j-jcl        （替换 commons-logging → 直送 Log4j2，不经 SLF4J）
 log4j-jul        （不换坐标：JUL Handler 挂载 → 直送 Log4j2）
```

方向判断口诀：**包名里 "X-over/to-Y" 表示"X 的调用被转给 Y"**。over=替换坐标的类替身，to=不替换坐标的运行时接管。

## 6.2 场景矩阵：你的项目该引入什么

| 场景 | 业务代码用 | classpath 应有 | classpath 不该有 |
|---|---|---|---|
| 标准新项目（推荐默认） | slf4j-api | slf4j-api + logback-classic（含 core） | 其它一切绑定/桥接包（除非要收编旧日志） |
| 新项目要 Log4j2 引擎 | slf4j-api | slf4j-api + log4j-slf4j2-impl + log4j-core + log4j-api | logback-classic、log4j-to-slf4j |
| 应用直接写 Log4j2 API | log4j-api | log4j-api + log4j-core | log4j-to-slf4j（否则套娃回 SLF4J） |
| 收编第三方库的 JCL 调用 | 不限 | jcl-over-slf4j **替换** commons-logging | commons-logging 本体 |
| 收编第三方库的 Log4j1 调用 | 不限 | log4j-over-slf4j **替换** log4j:log4j | log4j:log4j 本体、slf4j-log4j12 |
| 收编第三方库的 JUL 调用 | 不限 | jul-to-slf4j + 启动时 `SLF4JBridgeHandler.install()` | ——（JUL 坐标无法替换，共存） |
| 存量系统直迁 Log4j2 | 混合 | log4j-1.2-api 替换 log4j:log4j；log4j-jcl 替换 commons-logging | log4j-over-slf4j + slf4j-log4j12（见 6.4） |

【源码证据】收编 Log4j2 API 调用进 SLF4J 的反向桥，注册方式与 2.3.1 同理但方向相反：`log4j-to-slf4j/src/main/resources/META-INF/services/org.apache.logging.log4j.spi.Provider` 内容为 `org.apache.logging.slf4j.SLF4JProvider`——它成为 Log4j2 的 ProviderFactory，让 `LogManager.getLogger()` 拿到的每个 Logger 都转发 SLF4J。而正向绑定 `log4j-slf4j2-impl/src/main/resources/META-INF/services/org.slf4j.spi.SLF4JServiceProvider` 内容为 `org.apache.logging.slf4j.SLF4JServiceProvider`。**一个 jar 是 Log4j2 实现的 SLF4J Provider，另一个 jar 是 SLF4J 的 Log4j2 Provider——名字像绕口令，方向千万别混**。

## 6.3 Spring Boot 的默认配方（源码实证）

【源码证据】`spring-boot/starter/spring-boot-starter-logging/build.gradle` 的依赖清单（4.2.0-SNAPSHOT）：

```groovy
dependencies {
    api("ch.qos.logback:logback-classic")     // ① 引擎：SLF4J 绑定到 Logback
    api("org.apache.logging.log4j:log4j-to-slf4j")  // ② 反向桥：第三方库的 log4j-api 调用拖回来
    api("org.slf4j:jul-to-slf4j")             // ③ 反向桥：JUL 调用拖回来（Boot 启动时自动 install）
}
```

这个配方就是 6.1 图的最佳实践样本：**一个门面（slf4j）+ 一个引擎（logback）+ 两座反向桥（log4j-api 调用、JUL 调用）**。注意它没有引 `jcl-over-slf4j`——因为 Spring Framework 6+ 已把 JCL 依赖换成了 `spring-jcl`（框架自带的重定向层），这在 Spring 生态是历史性的一步。

Boot 的统一入口是 `LoggingSystem` 抽象（`core/spring-boot/.../logging/`）：`LogbackLoggingSystem`、`Log4J2LoggingSystem`、`JavaLoggingSystem` 三个实现经 `LoggingSystemFactory` SPI 竞争注册，启动时探测 classpath 决定谁接管（顺位：Logback > Log4j2 > JUL）。Logback 实现声明的配置文件搜索清单见 3.9 节。

**切换 Boot 默认日志引擎到 Log4j2**（背下来，面试常考）：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
    <exclusions>
        <exclusion>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-logging</artifactId>
        </exclusion>
    </exclusions>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-log4j2</artifactId>  <!-- 里面是 log4j-slf4j2-impl + log4j-core + jul-to-slf4j -->
</dependency>
```

切完 Boot 的 `Log4J2LoggingSystem` 自动接管；`application.yml` 里的 `logging.level.*`、`logging.file.name` 等横切配置两种引擎都通用。

## 6.4 死循环陷阱与排错

四个经典坑，全部源于"替身演员互相转发"：

1. **`log4j-over-slf4j` + `slf4j-log4j12`（或 logback 1.2 时代配 log4j 绑定）**：Log4j1 调用 → SLF4J → 绑定回 Log4j1 替身 → SLF4J → …… `StackOverflowError`。SLF4J 官方在检测到两者共存时会打警告但**不能替你解决**（有的场景两者都必须存在）。
2. **`log4j-to-slf4j` + `log4j-slf4j2-impl`**：Log4j2 api → SLF4J → Provider 又是 log4j2 → api → …… 同样环。正确姿势是二选一：要么 Log4j2 做引擎（删 log4j-to-slf4j），要么 SLF4J 做门面 Logback 做引擎（删 log4j-slf4j2-impl）。
3. **`jul-to-slf4j` 后忘记 `removeHandlersForRootLogger`**：JUL 的默认 ConsoleHandler 还挂着，每条 JUL 日志既走桥又直打控制台 → **日志重复**。
4. **同时上多个 SLF4J Provider**（比如 logback-classic 和 log4j-slf4j2-impl 都在）：SLF4J 按第 198 行 `providersList.get(0)` **静默取第一个**（顺序取决于 classpath），你以为绑了 A 实际绑了 B。对策：`-Dslf4j.provider=...` 显式指定，或物理排除多余包。

**通用排错三板斧**：

- 启动日志找绑定结果：SLF4J 的 "Actual provider is [...]"（多绑定时必打）；Logback 的 Status 输出（出错自动打，或 `<configuration debug="true">` 强制打）；Log4j2 的 `-Dlog4j2.debug=true`。
- `mvn dependency:tree -Dincludes=org.slf4j,log4j*,ch.qos.logback,commons-logging` 一把梭看依赖树。
- 运行期验证：`LoggerFactory.getILoggerFactory()` 的实际类型（期望 `LoggerContext` 是 Logback；是 `NOPLoggerFactory` 说明没有任何 Provider）。

## 6.5 本章小结

- 桥接三姿势：**类替换**（jcl/log4j-over-slf4j）、**Handler 挂载**（jul-to-slf4j）、**Provider 反向提供**（log4j-to-slf4j）。
- Spring Boot 默认 = slf4j + logback-classic + log4j-to-slf4j + jul-to-slf4j；换 Log4j2 = 排除 starter-logging + 上 starter-log4j2。
- 死循环 = 替身互相转发；重复 = Handler 未清；静默换绑 = 多 Provider 取第一。四个坑的解法全部落在"**理清谁该是唯一引擎，然后物理排除其余**"。

---

# 七、使用指南：配置与代码怎么写

> 本章可当模板库用。原则：业务代码**永远只 import 门面 API**；配置文件、异步、轮转、MDC 全部后置到"实现层"解决。

## 7.1 纯 SLF4J + Logback 从零搭

**Maven 依赖**：

```xml
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-api</artifactId>
    <version>2.0.20</version>
</dependency>
<dependency>
    <groupId>ch.qos.logback</groupId>
    <artifactId>logback-classic</artifactId>
    <version>1.5.38</version>
</dependency>   <!-- 传递引入 logback-core 与 logback 的 slf4j Provider -->
```

**代码**（对 1.2 节三条最佳实践的落地）：

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final Marker AUDIT = MarkerFactory.getMarker("AUDIT");

    public void create(long orderId, String userId) {
        MDC.put("traceId", currentTraceId());                 // 链路变量
        try {
            log.info("order created, orderId={}, user={}", orderId, userId);  // 参数化，不拼串
            log.info(AUDIT, "sensitive op, orderId={}", orderId);             // Marker 打标
            if (log.isDebugEnabled()) {                    // 仅当构造参数昂贵时才用守卫
                log.debug("detail: {}", expensiveCompute());
            }
        } catch (Exception e) {
            log.error("create failed, orderId={}", orderId, e);  // 异常放最后一个参数
        } finally {
            MDC.remove("traceId");                         // 防线程池串号
        }
    }
}
```

**logback.xml**（放 `src/main/resources`，注释即讲解）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- scan：每 30 秒轮询本文件变化并热更新（源码链路见 3.8） -->
<configuration scan="30 seconds">

    <!-- 变量：配置项可经 <property> 统一，支持 ${} 与系统属性 -->
    <property name="LOG_HOME" value="${LOG_HOME:-logs}"/>
    <property name="PATTERN"
              value="%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level [%X{traceId:-NONE}] %logger{36} - %msg%n"/>

    <!-- 全局 TurboFilter：事件对象构造"前"拦截，性能最优的过滤姿势（3.4 节①） -->
    <turboFilter class="ch.qos.logback.classic.turbo.MDCFilter">
        <MDCKey>traceId</MDCKey>
        <MDCValue>HEALTHCHECK</MDCValue>
        <OnMatch>DENY</OnMatch>
    </turboFilter>

    <!-- 控制台：开发用 -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder><pattern>${PATTERN}</pattern></encoder>
    </appender>

    <!-- 文件滚动：按天 + 单文件 100MB，保留 30 天（TimeBased + Size 组合策略） -->
    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${LOG_HOME}/app.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>${LOG_HOME}/app.%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
            <maxFileSize>100MB</maxFileSize>
            <maxHistory>30</maxHistory>
            <totalSizeCap>10GB</totalSizeCap>
        </rollingPolicy>
        <encoder><pattern>${PATTERN}</pattern></encoder>
    </appender>

    <!-- 异步：包住 FILE（默认队列 256 太小，必调；discardingThreshold 0 = 不丢日志，
         或保留默认 1/5 队列容量换"高峰丢低级别"；neverBlock=true 换"绝不拖慢业务"） -->
    <appender name="ASYNC_FILE" class="ch.qos.logback.classic.AsyncAppender">
        <queueSize>4096</queueSize>
        <discardingThreshold>0</discardingThreshold>
        <neverBlock>false</neverBlock>
        <maxFlushTime>2000</maxFlushTime>
        <appender-ref ref="FILE"/>
    </appender>

    <!-- 级别树：包级覆盖 + root 兜底；additivity=false 防止事件向上重复输出 -->
    <logger name="com.example.dao" level="DEBUG"/>
    <logger name="org.springframework" level="WARN"/>
    <!-- 审计日志单独落地：AUDIT Marker 的 ACCEPT + 其它 DENY 实现分流 -->
    <appender name="AUDIT_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${LOG_HOME}/audit.log</file>
        <filter class="ch.qos.logback.core.filter.EvaluatorFilter">
            <evaluator class="ch.qos.logback.classic.boolex.OnMarkerEvaluator">
                <marker>AUDIT</marker>
            </evaluator>
            <onMatch>ACCEPT</onMatch><onMismatch>DENY</onMismatch>
        </filter>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>${LOG_HOME}/audit.%d{yyyy-MM-dd}.log</fileNamePattern>
        </rollingPolicy>
        <encoder><pattern>${PATTERN}</pattern></encoder>
    </appender>
    <logger name="AUDIT_LOGGER" level="INFO" additivity="false">
        <appender-ref ref="AUDIT_FILE"/>
    </logger>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="ASYNC_FILE"/>
    </root>
</configuration>
```

**验证绑定是否生效**：写个 main 打印 `LoggerFactory.getILoggerFactory()`，期望输出 `ch.qos.logback.classic.LoggerContext`。

## 7.2 纯 Log4j2 从零搭

**Maven 依赖**：

```xml
<dependency>
    <groupId>org.apache.logging.log4j</groupId>
    <artifactId>log4j-api</artifactId>
    <version>2.26.1</version>
</dependency>
<dependency>
    <groupId>org.apache.logging.log4j</groupId>
    <artifactId>log4j-core</artifactId>
    <version>2.26.1</version>
</dependency>
```

**代码**（也可以直接用 log4j-api，不用 slf4j——注意此时 Marker/ThreadContext 都是 Log4j2 自己的类）：

```java
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.config.Configurator;

public class PaymentService {
    private static final Logger log = LogManager.getLogger(PaymentService.class);

    public void pay(long id) {
        try (var ctx = ThreadContext.closeableThreadContext().put("traceId", traceId())) {
            log.info("paying order {}", id);                    // 同样是参数化
            log.printf(Level.INFO, "raw printf %d", id);        // Log4j2 特有 printf 风格
        }
    }
}
```

**log4j2.xml**（放 `src/main/resources`；等价 YAML/JSON/properties 见 4.6 的工厂优先级）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- monitorInterval：每 30 秒检查配置文件变更热更新（源码链路见 4.11） -->
<Configuration status="WARN" monitorInterval="30">

    <Properties>
        <Property name="PATTERN">%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5level [${ctx:traceId:-NONE}] %logger{36} - %msg%n</Property>
        <Property name="LOG_DIR">logs</Property>
    </Properties>

    <Appenders>
        <Console name="CONSOLE" target="SYSTEM_OUT">
            <PatternLayout pattern="${PATTERN}"/>
        </Console>

        <RollingFile name="FILE" fileName="${LOG_DIR}/app.log"
                     filePattern="${LOG_DIR}/app.%d{yyyy-MM-dd}.%i.log.gz">
            <PatternLayout pattern="${PATTERN}"/>
            <Policies>
                <TimeBasedTriggeringPolicy/>
                <SizeBasedTriggeringPolicy size="100MB"/>
            </Policies>
            <DefaultRolloverStrategy max="30"/>
        </RollingFile>

        <!-- 应用级异步（备选方案：把某 Appender 异步化；Log4j2 更推荐 4.8 的全异步） -->
        <Async name="ASYNC_FILE">
            <AppenderRef ref="FILE"/>
            <!-- 队列满路由：默认阻塞；可 -Dlog4j2.asyncQueueFullPolicy=Discard -Dlog4j2.discardThreshold=INFO -->
        </Async>
    </Appenders>

    <Loggers>
        <Logger name="com.example.dao" level="debug"/>
        <Logger name="org.springframework" level="warn"/>
        <Root level="info">
            <AppenderRef ref="CONSOLE"/>
        </Root>
    </Loggers>
</Configuration>
```

**打开全异步**（Log4j2 的正确打开方式，二选一）：

```bash
# ① JVM 属性（应用所有 Logger）
-Dlog4j2.contextSelector=org.apache.logging.log4j.core.async.AsyncLoggerContextSelector
# ② 或 XML 里用 <AsyncLogger>/<AsyncRoot> 只异步关键 Logger

# 常用配套参数（源码出处见 4.8）：
-Dlog4j2.enable.threadlocals=true      # web 应用手动开 ThreadLocal 池化（GC-free 前提）
-DAsyncLogger.RingBufferSize=8192      # 调大环形缓冲（默认 GC-free 模式 4096 / GC 模式 262144）
-Dlog4j2.asyncQueueFullPolicy=Discard  # 环形缓冲满时丢弃 INFO 以下（默认阻塞）
-Dlog4j2.discardThreshold=INFO
```

**注意 includeLocation 的代价**：`<PatternLayout ... includeLocation="true">`（或 pattern 里 `%C/%M/%L`）会强制取调用栈——异步场景下每条日志多出一次栈捕获，吞吐断崖式下跌，生产环境能不用就不用（用 traceId/键值对定位代替行号）。

## 7.3 Spring Boot 场景

**默认栈**（什么都不配）：logback-classic + log4j-to-slf4j + jul-to-slf4j（6.3 节实证），配 `application.yml` 就能覆盖 90% 需求：

```yaml
logging:
  level:
    root: info
    com.example.dao: debug
  file:
    name: logs/app.log          # 给了 file 就自动用 Boot 预设的 RollingFile 配置滚动
  logback:
    rollingpolicy:
      max-file-size: 100MB
      max-history: 30
```

**自定义 logback 配置**：写 `src/main/resources/logback-spring.xml`（Boot 专属扩展：`<springProfile>` 按环境在解析期剪掉整段配置、`<springProperty>` 把 Environment 属性注入 logback 变量），Boot 会自动发现它（`logback.xml` 不存在时），无需配 `logging.config`；但注意**同目录下的 `logback.xml` 优先级更高**，同时存在时 `-spring` 变体被忽略。标签的源码实现、发现的完整时机与四个高频陷阱（不能与 `scan` 同用、内置转换器需自行 conversionRule 等）**详见 3.9 节**：

```xml
<configuration>
    <springProperty scope="context" name="appName" source="spring.application.name"/>
    <springProfile name="dev">
        <root level="DEBUG"><appender-ref ref="CONSOLE"/></root>
    </springProfile>
    <springProfile name="prod">
        <!-- 生产段：异步 + 滚动，同 7.1 -->
        <root level="INFO"><appender-ref ref="ASYNC_FILE"/></root>
    </springProfile>
</configuration>
```

**切 Log4j2 引擎**：按 6.3 节的排除 + 替换配方；此后自定义配置用 `log4j2-spring.xml`（Boot 会把它交给 Log4J2LoggingSystem 处理并支持 Environment 占位符）。

**Boot 场景注意**：
- Boot 的 `LoggingSystem` 会**延迟日志初始化**：`beforeInitialize` 时给 Logback 挂"全拒绝"TurboFilter、初始化完成后移除（`LogbackLoggingSystem` 第 139、203 行），业务代码的启动早期日志会被直接丢弃；框架自身的早期日志经 `DeferredLog` 缓冲、就绪后回放——启动日志顺序偶有"时空错乱"属正常现象。
- 不要在代码里直接碰 `LoggerContext.reconfigure()` 之类引擎 API，交给 Boot 管理；要改级别用 `logging.level.*` 或 Actuator 的 `/loggers` 端点（本质就是 3.3/4.4 的 setLevel/updateLoggers）。

## 7.4 代码层最佳实践清单

1. **门面唯一**：import 只允许 `org.slf4j.*`（或团队统一选定的 `org.apache.logging.log4j.*`）；静态常量 Logger + lombok `@Slf4j` 都可以，但别用 `LoggerFactory.getLogger(new Object(){}.getClass())` 这类花活。
2. **参数化优先**：`log.info("{} paid {}", a, b)` 而非字符串拼接；只有"构建参数本身昂贵"才加 `isDebugEnabled()` 守卫或 Log4j2 的 Supplier 参数 `log.debug("{}", this::expensive)`。
3. **异常最后一个参数**：`log.error("msg {}", arg, e)`——自动识别，不占 `{}`；要打印根因摘要用 `ExceptionUtils.getRootCauseMessage(e)`（别在消息里再拼一次 `e.getMessage()`）。
4. **MDC 有借有还**：try/finally 或 `closeableThreadContext()`；线程池场景务必清理，最好在框架层（拦截器/Filter）统一设置与清除。
5. **级别语义约定**：ERROR=需要人介入；WARN=可自愈但值得看；INFO=业务关键节点（别在循环里逐条 INFO）；DEBUG=诊断细节；TRACE=近洪泛级别。
6. **Marker 治理**：审计、安全类日志用 Marker 打标 + 独立 Appender 分流（7.1 示例），比"猜正则"可靠得多。
7. **异步环境下**：位置信息（%M/%L）默认不要开；日志里出现的线程名是 worker 线程名（排查时别被误导）；MDC 用 ThreadContext/Logback 的复制机制（3.7/4.10）而不是自己手工传 map。

## 7.5 配置文件全解：谁的文件、什么语法、怎么找

> 前面 7.1/7.2 给的是"可直接抄"的模板，本节补齐背后的**语法语义层**：每个文件归谁管、变量怎么解析、条件/包含怎么写、找不到文件时谁兜底。也顺带回答"slf4j-api 1.x 和 2.x 的配置文件有什么区别"——先说结论：**门面没有配置文件，配置属于绑定/实现；1.x 与 2.x 的差异在"谁在什么时机读它"（StaticBinder 类初始化 vs `Provider.initialize()`，见 2.7.2），文件格式几乎不变**。

### 7.5.1 全家桶配置文件总表

| 配置文件 | 归属（谁读它） | 格式 | 查找顺序详见 |
|---|---|---|---|
| `logback.xml` / `logback-test.xml` | logback-classic | XML | 3.2 节 |
| `logback-spring.xml` | Spring Boot + logback | XML + Boot 专属标签 | 本节 7.5.4（机制 3.9 节） |
| `log4j2.{xml,json,yaml,yml,properties}`（test 前缀优先） | log4j-core | 四种格式等价 | 4.6 节 |
| `log4j2-spring.xml` | Spring Boot + log4j2 | 标准 Log4j2 语法 | 3.9.6 节 |
| `simplelogger.properties` | slf4j-simple | properties | 本节 7.5.2 |
| `logging.properties` | JUL（`slf4j-jdk14` / `log4j-jul` 的引擎） | properties | 7.5.6 |
| `log4j.properties` / `log4j.xml` | Log4j 1.x / reload4j（`slf4j-reload4j` 的引擎） | properties / XML | 7.5.6 |
| （无任何文件时的兜底） | BasicConfigurator（DEBUG+控制台）/ DefaultConfiguration（ERROR+控制台）/ Boot DefaultLogbackConfiguration | —— | 3.2 / 4.6 / 3.9.2 节 |

slf4j 桥接包（`jcl-over-slf4j`、`log4j-over-slf4j`、`jul-to-slf4j`、`log4j-to-slf4j`）**没有也不需要配置文件**——它们只是转发器，配置永远由最终引擎的那份文件接管（这解释了 7.5.6 末尾的"旧配置失效"陷阱）。

### 7.5.2 slf4j-simple 的 `simplelogger.properties`：唯一随 slf4j 模块分发的配置文件

定位：测试、本地脚本、CLI 工具里最省事的绑定——没有层级树、没有 Appender 抽象，一个全局配置 + 每个 Logger 只能覆盖级别。它的配置体系反而是**最简单可背**的：

- **加载方式**：从类路径读 `simplelogger.properties`（`SimpleLoggerConfiguration.java` 第 33 行常量 `CONFIGURATION_FILE`，第 108-114 行经线程上下文类加载器加载），**逐键可被同名系统属性覆盖**（前缀 `org.slf4j.simpleLogger.`）。没有"指定自定义文件路径"的开关——要多环境切换就用 `-D` 传属性或换 classpath。
- **全部 11 个键**（1.7.36 与 2.0.20 逐键 grep 实测**完全一致**）：

| 键（去掉 `org.slf4j.simpleLogger.` 前缀） | 取值/含义 |
|---|---|
| `defaultLogLevel` | 全局默认级别：trace/debug/info/warn/error |
| `logFile` | `System.err`（默认）/ `System.out` / 文件路径 |
| `cacheOutputStream` | true 时缓存输出流引用（防轮转期间重建） |
| `showDateTime` / `dateTimeFormat` | 是否打时间戳 / `SimpleDateFormat` 格式 |
| `showThreadName` / `showThreadId` | 线程名 / 线程 id |
| `showLogName` / `showShortLogName` | 全限定 logger 名 / 仅最后一段 |
| `levelInBrackets` | 级别加方括号 `[INFO]` |
| `warnLevelString` | 自定义 WARN 级别文案 |

- 单个 Logger 只能覆盖级别：`-Dorg.slf4j.simpleLogger.log.com.example.dao=debug`（前缀 `org.slf4j.simpleLogger.log.` + logger 名）。
- **1.x → 2.0 对使用者透明**：2.0 把类从 `org.slf4j.impl` 包搬到 `org.slf4j.simple`（换 ServiceLoader 绑定，2.7.5 节），文件名与键一个没变。`slf4j-nop` 则是零配置零输出的极端形态。

### 7.5.3 logback.xml：完整样例 + 逐节点解释

先给一份覆盖常用节点的**参考样例**（7.1 是最小可用版，这里展开为全量参考；XML 注释里的 ①~⑬ 与下面的逐节点解释一一对应）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- ① 根节点：全局开关 -->
<configuration debug="false" scan="30 seconds" packagingData="false">

    <!-- ② 上下文名 -->
    <contextName>order-app</contextName>

    <!-- ③ 变量定义（作用域见下文解释） -->
    <property name="LOG_HOME" value="${LOG_HOME:-logs}" scope="local"/>
    <property name="PATTERN"
              value="%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level [%X{traceId:-NONE}] %logger{36} - %msg%n"/>

    <!-- ④ 启动时间戳变量 -->
    <timestamp key="LAUNCH_TS" datePattern="yyyyMMdd-HHmmss"/>

    <!-- ⑤ 注册自定义 pattern 转换符 -->
    <conversionRule conversionWord="tid" converterClass="com.example.logging.TraceIdConverter"/>

    <!-- ⑥ 框架内部状态监听：只把 ERROR/WARNING 级 Status 打到控制台 -->
    <statusListener class="ch.qos.logback.core.status.OnErrorConsoleStatusListener"/>

    <!-- ⑦ 全局 TurboFilter：LoggingEvent 构造"之前"拦截（3.4 节①，性能最优的过滤姿势） -->
    <turboFilter class="ch.qos.logback.classic.turbo.MDCFilter">
        <MDCKey>traceId</MDCKey>
        <MDCValue>HEALTHCHECK</MDCValue>
        <OnMatch>DENY</OnMatch>
    </turboFilter>
    <turboFilter class="ch.qos.logback.classic.turbo.DuplicateMessageFilter">
        <allowedRepetitions>5</allowedRepetitions>
        <cacheSize>100</cacheSize>
    </turboFilter>

    <!-- ⑧ 控制台 Appender + Encoder -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>${PATTERN}</pattern>
            <charset>UTF-8</charset>
        </encoder>
    </appender>

    <!-- ⑨ 滚动文件 Appender + 触发策略 -->
    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${LOG_HOME}/app.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>${LOG_HOME}/app.%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
            <maxFileSize>100MB</maxFileSize>
            <maxHistory>30</maxHistory>
            <totalSizeCap>10GB</totalSizeCap>
        </rollingPolicy>
        <encoder><pattern>${PATTERN}</pattern></encoder>
    </appender>

    <!-- ⑩ Appender 级 Filter：只放行 ERROR（精确匹配） -->
    <appender name="ERROR_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <filter class="ch.qos.logback.classic.filter.LevelFilter">
            <level>ERROR</level>
            <onMatch>ACCEPT</onMatch>
            <onMismatch>DENY</onMismatch>
        </filter>
        <file>${LOG_HOME}/error.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>${LOG_HOME}/error.%d{yyyy-MM-dd}.log.gz</fileNamePattern>
            <maxHistory>60</maxHistory>
        </rollingPolicy>
        <encoder><pattern>${PATTERN}</pattern></encoder>
    </appender>

    <!-- ⑪ 异步包装（参数机制见 3.6 节） -->
    <appender name="ASYNC" class="ch.qos.logback.classic.AsyncAppender">
        <queueSize>4096</queueSize>
        <discardingThreshold>0</discardingThreshold>
        <neverBlock>false</neverBlock>
        <maxFlushTime>2000</maxFlushTime>
        <appender-ref ref="FILE"/>
    </appender>

    <!-- ⑫ 包级 Logger：覆盖级别 / 私有 Appender -->
    <logger name="com.example.dao" level="DEBUG"/>
    <logger name="com.example.audit" level="INFO" additivity="false">
        <appender-ref ref="ERROR_FILE"/>
    </logger>

    <!-- ⑬ 根 Logger：兜底级别 + 兜底 Appender -->
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="ASYNC"/>
    </root>
</configuration>
```

**逐节点解释**（编号对应样例注释；括号内是机制章节出处）：

- **① `<configuration debug scan scanPeriod packagingData>`** —— 唯一根节点。`debug="true"` 等于内置一个打全量 Status 的监听器；`scan`/`scanPeriod` 开热更新（3.8 节的定时任务链路；**与 Boot 扩展标签互斥**，3.9.5）；`packagingData="true"` 让异常栈附带 jar 坐标（昂贵，仅排错）。
- **② `<contextName>`** —— 给 LoggerContext 起名（默认 "default"），Status 消息里区分多应用共 JVM 时的归属。
- **③ `<property>`** —— 变量定义，logback 最值得吃透的一块：
  - **三档作用域** `scope="local|context|system"`（枚举实测 `logback-core/.../joran/action/ActionUtil.java` 第 22-23 行）：`local`（默认）解析期替换后即弃；`context` 挂到 LoggerContext、运行期可读改；`system` 写回 JVM 系统属性（影响同 JVM 其他组件）。
  - **默认值语法** `${name:-fallback}`：`:-` 是替换文法的一等公民（`logback-core/.../subst/Parser.java` 第 25-27 行文法注释 `C = E|E :- E`）。
  - **查找顺序**：local 属性 → context 属性 → JVM 系统属性 → 操作系统环境变量，一路向上兜底。
  - 内置变量：`HOSTNAME`、`CONTEXT_NAME`，以及 ④ 定义的键。pattern 里的 `%X{traceId:-NONE}` 同样支持 `:-` 默认值语法（logback 原生支持）。
- **④ `<timestamp>`** —— 在**配置解析时刻**生成时间字符串存为变量（`key` + `datePattern`），典型用途 `value="logs/${LAUNCH_TS}"` 让目录名带启动时间；只在（重）解析时取值一次。
- **⑤ `<conversionRule>`** —— 注册"新词 → Converter 类"，之后 pattern 里 `%tid` 就走你的类；Boot 场景手动补 `%clr`/`%wEx` 等内置转换器也走这里（3.9.5 节陷阱④）。
- **⑥ `<statusListener>`** —— 监听 Logback 内部 Status 事件：`OnErrorConsoleStatusListener` 只打 ERROR/WARNING 级内部消息，`OnConsoleStatusListener` 打全量。"配置看起来没生效"时，它是比 `debug="true"` 更精准的入口。
- **⑦ `<turboFilter>`** —— 全局前置过滤，在 LoggingEvent 对象构造**之前**运行（3.4 节①），DENY 能省掉整个事件构造。常用四类：`MDCFilter`（MDC 值匹配即拒/放，如静音健康检查流量）、`MarkerFilter`、`DynamicThresholdFilter`（按 MDC 动态调级别，配合链路采样）、`DuplicateMessageFilter`（重复消息抑制：`allowedRepetitions` 内放行、超出即拒；有状态缓存 `cacheSize`，超高并发有锁竞争，慎用于热路径）。
- **⑧ `<appender>` + `<encoder>`** —— Appender 是输出端抽象（3.5 节）。`<encoder>` 里 `<pattern>` 走 PatternLayout 转换符（`%d` 时间、`[%thread]` 线程名、`%-5level` 左对齐 5 宽级别、`%logger{36}` 包名缩写、`%msg` 消息、`%n` 换行），`<charset>` 控制编码；encoder 还负责文件头输出（3.5 节双层职责）。控制台注意 `immediateFlush`（默认 true，关闭可提吞吐但崩前可能丢尾巴）。
- **⑨ `<rollingPolicy>`** —— 滚动策略三选一：`TimeBasedRollingPolicy`（按 `%d` 粒度滚动，`maxHistory` 保留份数）、`SizeBasedRollingPolicy`（按大小）、`SizeAndTimeBasedRollingPolicy`（叠加：`%i` 是同日内序号，`totalSizeCap` 管总盘）。历史压缩看 `fileNamePattern` 后缀（`.gz`/`.zip`）。`file` 与 `fileNamePattern` 同时存在时：当前日志写 `file`，滚动后按 pattern 归档。
- **⑩ `<filter>`（Appender 级）** —— 挂在单个 Appender 上，在事件已构造之后拦（与 ⑦ 的位置不同：AppenderControl 链内，4.5 节对照）。`LevelFilter` 精确匹配级别（注意类路径 `ch.qos.logback.classic.filter.LevelFilter`），`ThresholdFilter` 放行"≥阈值"全部（`ch.qos.logback.core.filter.ThresholdFilter`，默认只挡低级别、其余透传）；`onMatch`/`onMismatch` 取 ACCEPT/NEUTRAL/DENY 三值。
- **⑪ `<appender name="ASYNC">`（AsyncAppender）** —— 异步包装器（3.6 节完整机制）：`queueSize` 队列容量（默认 256 太小，生产建议 4096+）；`discardingThreshold` 丢弃阈值（默认 queueSize/5，队列 80% 满开始丢 TRACE/DEBUG/INFO；设 0 绝不主动丢）；`neverBlock="true"` 队列满改 offer 丢事件，换"绝不拖慢业务"；`maxFlushTime` 停机冲刷上限毫秒（默认 1000）；`includeCallerData` 生产端同步取栈（补异步丢的位置信息，有代价）。`<appender-ref ref="FILE"/>` 指定被包装的真 Appender。
- **⑫ `<logger>`** —— 配置树节点：`level` 大小写不敏感；`additivity="false"` 事件不再向父级 Appender 冒泡（3.4 节⑤，防重复输出）；未显式设 level 则继承父级（3.3 节 effectiveLevel 机制）。root 的 level 不能为 null（3.3 节 `setLevel` 第 155-157 行抛异常）。
- **⑬ `<root>` + `<appender-ref>`** —— 树根，级别不可缺；`<appender-ref>` 按声明顺序挂 Appender（同一 Appender 被多节点引用是允许的，重复输出的根源是 additivity 而非多引用）。

**样例未含但常用的节点**：`<include file|url|resource optional="true">` 拆分/复用配置（1.5 支持嵌套与变量传递）；`<if condition="...">` 条件处理（需另引 janino 依赖，多环境建议 Boot `<springProfile>` 或多文件 include）；`<contextListener>` 监听上下文生命周期；SocketAppender/DBAppender 等远程输出端。

**版本兼容**：logback-classic 1.2（1.7 绑定时代）→ 1.5 的 `logback.xml` **格式向后兼容**，基本免改；但 logback-core 与 classic 必须**同版本**（`LogbackServiceProvider` 启动期做一致性校验，3.1 节）。

### 7.5.4 logback-spring.xml：完整样例 + 逐节点解释（Boot 专属）

**它与 logback.xml 的差异先一张表说清**（机制出处都在 3.9 节，本节给可抄的样例）：

| 维度 | logback.xml | logback-spring.xml |
|---|---|---|
| 谁会加载它 | Logback 自己（autoConfig，3.2 节） | **只由 Boot** 的 `SpringBootJoranConfigurator`（3.9.3 节） |
| 非 Boot 项目 | 正常生效 | **死文件——没有任何组件会去找这个名字** |
| 加载时机 | 第一次 getLogger（Environment 尚未构建） | `ApplicationEnvironmentPreparedEvent` 之后（Environment 可用，3.9.2） |
| 专属标签 | 无 | `<springProfile>` / `<springProperty>` |
| 能否读 application.yml | 不能 | 能（`<springProperty>` 是唯一通道） |
| 与 logback.xml 并存 | 标准名优先 | **被忽略**（3.9.2 节①分支）——用 -spring 就删掉 logback.xml |
| `scan="true"` 热更新 | 可用 | **禁用**（重解析用裸 Joran，3.9.5 陷阱②） |
| `%clr` 等 Boot 转换器 | 不可用 | 可用，但需自己 `<conversionRule>` 注册（3.9.5 陷阱④） |

**完整样例**（①~⑨ 对应逐节点解释；假设 application.yml 里有 `spring.application.name: order-app`）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- ① 根节点：注意没有 scan（Boot 扩展标签与热更新互斥） -->
<configuration packagingData="false">

    <!-- ② 手动注册 Boot 内置转换器（自定义 -spring 文件不会自动获得） -->
    <conversionRule conversionWord="clr" converterClass="org.springframework.boot.logging.logback.ColorConverter"/>
    <conversionRule conversionWord="correlationId" converterClass="org.springframework.boot.logging.logback.CorrelationIdConverter"/>
    <conversionRule conversionWord="wEx" converterClass="org.springframework.boot.logging.logback.ExtendedWhitespaceThrowableProxyConverter"/>

    <!-- ③ 从 Spring Environment 取值（Boot 专属标签） -->
    <springProperty scope="context" name="APP_NAME" source="spring.application.name" defaultValue="unknown-app"/>

    <!-- ④ 按 profile 定义变量（springProfile 可以包 <property>） -->
    <springProfile name="dev | local">
        <property name="LOG_LEVEL_ROOT" value="DEBUG"/>
        <property name="LOG_HOME" value="logs"/>
    </springProfile>
    <springProfile name="!(dev | local)">
        <property name="LOG_LEVEL_ROOT" value="INFO"/>
        <property name="LOG_HOME" value="/var/log/${APP_NAME}"/>
    </springProfile>

    <!-- ⑤ 公共控制台（两个环境都要用，不做裁剪）：%clr 上色 + %wEx 异常栈 -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%clr(%d{yyyy-MM-dd HH:mm:ss.SSS}){faint} %clr(${LOG_LEVEL_PATTERN:-%5p}) %clr(${PID:- }){magenta} --- [%15.15t] %clr([${APP_NAME}]){cyan} %-40.40logger{39} : %msg%n%wEx</pattern>
            <charset>UTF-8</charset>
        </encoder>
    </appender>

    <!-- ⑥⑦ 非 dev 环境才装配文件与异步 appender（裁剪"整个节点"，合法姿势） -->
    <springProfile name="!(dev | local)">
        <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
            <file>${LOG_HOME}/app.log</file>
            <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
                <fileNamePattern>${LOG_HOME}/app.%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
                <maxFileSize>100MB</maxFileSize>
                <maxHistory>30</maxHistory>
            </rollingPolicy>
            <encoder><pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5level ${APP_NAME} %logger{36} - %msg%n%wEx</pattern></encoder>
        </appender>
        <appender name="ASYNC_FILE" class="ch.qos.logback.classic.AsyncAppender">
            <queueSize>4096</queueSize>
            <neverBlock>false</neverBlock>
            <appender-ref ref="FILE"/>
        </appender>
    </springProfile>

    <!-- ⑧ 包级 Logger：与 logback.xml 语法完全一致 -->
    <logger name="com.example.dao" level="DEBUG"/>
    <logger name="org.springframework" level="WARN"/>

    <!-- ⑨ root 按环境拆两份：springProfile 在"外层"包 <root> 合法；嵌进 <root> 内部才违法 -->
    <springProfile name="dev | local">
        <root level="${LOG_LEVEL_ROOT}">
            <appender-ref ref="CONSOLE"/>
        </root>
    </springProfile>
    <springProfile name="!(dev | local)">
        <root level="${LOG_LEVEL_ROOT}">
            <appender-ref ref="CONSOLE"/>
            <appender-ref ref="ASYNC_FILE"/>
        </root>
    </springProfile>
</configuration>
```

**逐节点解释**（重点讲与 logback.xml 的差异；相同的节点只指路）：

- **① 根节点 `<configuration>`** —— 语法与 logback.xml 相同，但**文件名本身就是加载开关**：Boot 只按 `logback-test-spring.xml` → `logback-spring.xml` 的派生名去找（`AbstractLoggingSystem.getSpringConfigLocations` 把标准名加 `-spring` 后缀，3.9.2 节）；找不到就走 `logging.config`，再没有就用 Boot 内置默认配置。注意**没有 `scan`**——热更新重解析用裸 JoranConfigurator，遇到 `<springProfile>` 会失败（3.9.5 陷阱②）。
- **② `<conversionRule>`（差异点）** —— Boot 的彩色/关联 ID 转换器只注册在"无配置文件"的默认路径（`DefaultLogbackConfiguration.java` 第 104-108 行），自定义 `-spring` 文件必须自己声明。三个最常用的：`%clr(...){颜色}` 按级别/指定色上色（支持 `{faint}`、`{magenta}`、`{cyan}`、`{red}`、`{green}` 等）；`%correlationId` 输出 traceId/spanId 关联 ID（配 Boot 的 micrometer 链路，4.12 侧注）；`%wEx` 异常栈带空行分隔。
- **③ `<springProperty scope name source defaultValue>`（差异点，Boot 专属）** —— 从 Spring `Environment` 取值注入为 logback 变量（实现：`SpringPropertyModelHandler` 第 53-63 行 `environment.getProperty(source, defaultValue)`，3.9.4 节）。**与普通 `${}` 的本质区别**：普通变量走 logback 自己的解析链（local → context → 系统属性 → OS 环境变量），读不到 application.yml；`<springProperty>` 是 yml 属性进入日志配置的唯一通道。`scope` 三档同 7.5.3 节 ③。
- **④ `<springProfile name>`（差异点，Boot 专属）** —— 按激活 profile **在解析期整段剪掉子树**（`SpringProfileModelHandler` 第 51-56 行不匹配即 `deepMarkAsSkipped()`，不是运行期 if）。`name` 是完整的 profile 表达式（`Spring` 的 `Profiles.of` 语义，第 74 行）：逗号分隔等效枚举、`|` 或、`&` 且、`!` 非——与 `@Profile("dev | local")` 注解同一套规则。可包裹的节点：`<property>`、`<appender>`、`<logger>`、`<root>` 等任意顶层段；**唯一禁令是不能作为 `<appender>/<logger>/<root>` 的子节点**（`SpringProfileIfNestedWithinSecondPhaseElementSanityChecker` 第 41-42 行三类 SECOND_PHASE_TYPES 校验，3.9.4 节）。
- **⑤ 公共 `<appender>`** —— 语法与 logback.xml 一致，但 pattern 里的变量来源多了一层：**Boot 在加载配置前会注入一组系统属性**（`LoggingSystemProperty.java` 枚举实测：`PID`(第 44 行)、`LOG_FILE`(49)、`LOG_PATH`(54)、`CONSOLE_LOG_PATTERN`(84，来自 `logging.pattern.console`)、`FILE_LOG_PATTERN`(89)、`LOG_LEVEL_PATTERN`(106，来自 `logging.pattern.level`)、`LOG_DATEFORMAT_PATTERN`(111)、`LOG_CORRELATION_PATTERN`(116)、`LOG_EXCEPTION_CONVERSION_WORD`(79)）。所以 `${LOG_LEVEL_PATTERN:-%5p}` 能吃到 yml 里的 `logging.pattern.level`，`application.yml` 的横切配置与这份 XML 就能协同（3.9.2 节③内置默认配置的 pattern 全靠同一批变量兜底）。logback 专属的滚动参数也有对应注入：`LOGBACK_ROLLINGPOLICY_FILE_NAME_PATTERN` / `_MAX_FILE_SIZE` / `_MAX_HISTORY` / `_TOTAL_SIZE_CAP` / `_CLEAN_HISTORY_ON_START`（`RollingPolicySystemProperty.java` 第 59-60 行，对应 `logging.logback.rollingpolicy.*`）。
- **⑥⑦ `<springProfile>` 包整段 appender（裁剪的标准姿势）** —— dev 环境只留控制台，生产环境多挂文件+异步。合法写法是 springProfile 作为**父节点**包住完整的 `<appender>`；写成 `<appender>...<springProfile>...</springProfile></appender>` 会触发 ② 处校验器的警告且行为不可靠。裁剪 appender 而非"在 appender 内部判条件"，就是这个标签模型的设计本意。
- **⑧ `<logger>`** —— 与 logback.xml 完全一致（level/additivity/appender-ref），没有 Boot 特殊性。补充：application.yml 的 `logging.level.*` 也能配级别，且**优先级更高**——Boot 在配置文件加载完成后统一执行 `initializeFinalLoggingLevels`（`LoggingApplicationListener` 第 360 行起）做最终覆盖；XML 里适合放 yml 表达不了的精细结构（additivity、私有 appender）。
- **⑨ root 按环境拆两份** —— 合法姿势是 `<springProfile>` 在外层包完整的 `<root>`（嵌进 `<root>` 内部才违法，见 ④）；两个块的表达式必须**互斥**（本例 `dev | local` vs `!(dev | local)`），避免同一环境两个 root 都生效。root 的 level 引用 ④ 定义的变量实现"级别也随环境走"。

**收束**：logback-spring.xml = logback.xml 的全部语法 + `springProfile`/`springProperty` 两个解析期标签 + Boot 注入的一批系统属性变量；代价是绑定 Boot 生命周期（非 Boot 项目死文件）、放弃 scan、多一份 conversionRule 手续。机制层的深入分析（加载时序、解析器扩展、四个陷阱的源码证据）全部在 3.9 节。

### 7.5.5 log4j2.xml：完整样例 + 逐节点解释

格式前提：四种格式等价（工厂优先级 Xml5 > Json6 > Yaml7 > Properties8、test 前缀整体先行，4.6 节）；XML 独占 **XInclude**（`xi:include` 拆文件）与 **strict 严格模式**两个能力。下面是 XML 参考样例（①~⑨ 与逐节点解释一一对应）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- ① 根节点 -->
<Configuration name="order-app" status="WARN" monitorInterval="30"
               shutdownHook="5000" packages="com.example.logging" strict="false">

    <!-- ② 全局属性区（变量与 Lookup，见下文解释） -->
    <Properties>
        <Property name="PATTERN">%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5level [${ctx:traceId:-NONE}] %logger{36} - %msg%n</Property>
        <Property name="LOG_DIR">${sys:LOG_DIR:-logs}</Property>
    </Properties>

    <Appenders>
        <!-- ③ 控制台 -->
        <Console name="CONSOLE" target="SYSTEM_OUT">
            <PatternLayout pattern="${PATTERN}" charset="UTF-8"/>
        </Console>

        <!-- ④ 滚动文件 + 触发策略组合 -->
        <RollingFile name="FILE" fileName="${LOG_DIR}/app.log"
                     filePattern="${LOG_DIR}/app.%d{yyyy-MM-dd}.%i.log.gz">
            <PatternLayout pattern="${PATTERN}"/>
            <Policies>
                <TimeBasedTriggeringPolicy interval="1" modulate="true"/>
                <SizeBasedTriggeringPolicy size="100MB"/>
            </Policies>
            <DefaultRolloverStrategy max="30"/>
        </RollingFile>

        <!-- ⑤ Appender 级 Filter：只收 ERROR -->
        <RollingFile name="ERROR_FILE" fileName="${LOG_DIR}/error.log"
                     filePattern="${LOG_DIR}/error.%d{yyyy-MM-dd}.log.gz">
            <ThresholdFilter level="ERROR" onMatch="ACCEPT" onMismatch="DENY"/>
            <PatternLayout pattern="${PATTERN}"/>
            <Policies><TimeBasedTriggeringPolicy/></Policies>
            <DefaultRolloverStrategy max="60"/>
        </RollingFile>

        <!-- ⑥ 应用级异步（4.8 节方案①） -->
        <Async name="ASYNC_FILE">
            <AppenderRef ref="FILE"/>
        </Async>
    </Appenders>

    <Loggers>
        <!-- ⑦ 包级 Logger -->
        <Logger name="com.example.dao" level="debug"/>

        <!-- ⑧ 混合异步 Logger（4.8 节方案③：不需要 contextSelector） -->
        <AsyncLogger name="com.example.audit" level="info" additivity="false" includeLocation="false">
            <AppenderRef ref="ERROR_FILE"/>
        </AsyncLogger>

        <!-- ⑨ 根 Logger -->
        <Root level="info">
            <AppenderRef ref="CONSOLE"/>
            <AppenderRef ref="ASYNC_FILE"/>
        </Root>
    </Loggers>
</Configuration>
```

**逐节点解释**：

- **① `<Configuration>`** —— 根节点（大写开头是 Log4j2 插件命名约定）。`name` 配置名（多配置合并时区分，4.6 CompositeConfiguration）；`status` 是 Log4j2 **自身**日志级别（默认 ERROR，排障设 DEBUG，等效 4.12 的 StatusLogger）；`dest` 控制自身日志去向（err/out/文件）；`monitorInterval` 热更新轮询秒数（4.11 节链路）；`shutdownHook` 填 `disable` 或毫秒数（停机时给异步日志冲刷留时间，4.8 节代价清单）；`packages` 额外插件扫描包（自定义 @Plugin 的运行期入口，4.7 节）；`strict="true"` 强制 schema 严格校验（错配立即报错，不用则宽松处理）。
- **② `<Properties>` / `<Property>`** —— 全局变量区。引用语法 **`${prefix:key:-default}`**，由 Interpolator 管道解析（无前缀先查 `<Properties>`，再落 `sys`）。prefix → Lookup 实现类全表（`log4j-core/.../core/lookup/` 目录实测）：`sys`=SystemPropertiesLookup、`env`=EnvironmentLookup、`date`、`java`（JVM 信息）、`ctx`=ContextMapLookup（读 ThreadContext/MDC）、`marker`、`map`、`main`（命令行主参数）、`sd`（结构化数据）、`bundle`（资源包）、`event`、`log4j`（配置位置）、**`jndi`（默认禁用！）**。支持双层 `${${x}}` 间接引用。
- **安全红线（Log4Shell 遗产）**：JNDI Lookup 默认关闭——`JndiLookup.java` 第 46 行原话 `"JNDI must be enabled by setting log4j2.enableJndiLookup=true"`，同族还有 `log4j2.enableJndiContextSelector` / `log4j2.enableJndiJms` / `log4j2.enableJndiJndiRmi`。生产环境保持默认禁用（1.2 节事件复盘）。
- **③ `<Console>` + `<PatternLayout>`** —— 控制台 Appender（`target` SYSTEM_OUT/SYSTEM_ERR）。转换符与 logback 同源但有名字差异：`%t` 线程名（logback 叫 `%thread`）、`%d`、`%-5level`、`%logger{36}`、`%msg`。`%X{key}` 读 ThreadContext，**带默认值建议写 `${ctx:key:-default}`**（走 ② 的替换管道，语义可靠；样例即此写法，7.2 模板同）。`charset` 控制编码。
- **④ `<RollingFile>` + `<Policies>`** —— `fileName` 当前文件、`filePattern` 归档模式（`%d` 日期粒度、`%i` 序号，后缀 `.gz`/`.zip` 自动压缩）。`<Policies>` 是"任一触发即滚动"的组合：`TimeBasedTriggeringPolicy`（`interval` 为几个 `%d` 粒度单位、`modulate="true"` 对齐时间边界）+ `SizeBasedTriggeringPolicy`（`size` 单文件上限）；`<DefaultRolloverStrategy max="30">` 控制同 pattern 下最多保留多少归档（防 `%i` 无限膨胀）。
- **⑤ Appender 级 `<Filter>`** —— 挂在 Appender 上（4.5 节三层 Filter 的最内层）。`ThresholdFilter` 放行 ≥ 阈值（`onMatch`/`onMismatch` 取 ACCEPT/NEUTRAL/DENY，上例配成"只收 ERROR"）。注意 log4j2 的过滤族命名与 logback 有差异：精确级别匹配是 **`LevelMatchFilter`**（logback 叫 LevelFilter），另有 `LevelRangeFilter`（区间）、`MarkerFilter`、`ThreadContextMapFilter`、`ScriptFilter`（需脚本引擎）、`TimeFilter`、`BurstFilter`（限流）等。
- **⑥ `<Async>`** —— Appender 级异步（4.8 节方案①）：ArrayBlockingQueue + 单线程，队列满路由默认阻塞，可 `-Dlog4j2.asyncQueueFullPolicy=Discard` 切丢弃策略。但 Log4j2 更推荐 ⑧ 的全异步。
- **⑦ `<Logger>`** —— 配置树节点：`level` 大小写不敏感；`additivity` 语义同 logback（事件沿 LoggerConfig 父链向上，4.5 节⑤ `logParent`）；`includeLocation` 的代价见 4.8（异步场景默认别开）。
- **⑧ `<AsyncLogger>` / `<AsyncRoot>`** —— 混合异步（4.8 方案③）：底层是 `AsyncLoggerConfig` + Disruptor，**不需要** `-Dlog4j2.contextSelector` 就能只异步指定 Logger；与全异步（方案②）按需二选一。
- **⑨ `<Root>` + `<AppenderRef>`** —— 根节点级别不可缺（缺了走 4.6 的 DefaultConfiguration 兜底 ERROR+Console）；`<AppenderRef>` 可带 `level`/独立 filter 属性（AppenderControl 级，4.5 节②），实现"同一 Appender 对不同 Logger 收不同级别"。

**样例未含但常用**：`<CustomLevel>` 自定义级别、`<Script>`/`<ScriptFilter>` 条件（需脚本引擎依赖）、XInclude 拆文件、多文件合并（`log4j2.configurationFile` 逗号分隔，4.6）与 Boot 的 `logging.log4j2.config.override`（3.9.6）。

### 7.5.6 遗产配置文件：JUL 的 `logging.properties` 与 Log4j1 的 `log4j.properties`

**JUL**（引擎是 JDK；`slf4j-jdk14` 把 SLF4J 调用它，`log4j-jul` 把它调向 Log4j2）：

```properties
handlers = java.util.logging.ConsoleHandler, java.util.logging.FileHandler   # 顶层：Handler 列表（空格分隔）
.level = INFO                                          # 根级别
java.util.logging.ConsoleHandler.level = INFO          # 每个 Handler 也要配级别（双闸）
java.util.logging.ConsoleHandler.formatter = java.util.logging.SimpleFormatter
java.util.logging.FileHandler.pattern = %h/java%u.log  # 路径模式：%h 用户目录 %t 临时目录 %u 唯一号 %g 轮转序号
java.util.logging.FileHandler.limit = 5000000          # 单文件字节上限
java.util.logging.FileHandler.count = 10               # 轮转文件数
com.example.dao.level = FINE                           # 包级覆盖（FINE≈DEBUG）
```

加载入口 `-Djava.util.logging.config.file=...`；没有它就走 JDK 内置的极简默认（控制台 INFO，5.2 节短板的出处）。注意 JUL 是"Logger 级别 + Handler 级别"**双闸**，漏配后者是"配了不输出"的头号原因。

**Log4j 1.x**（引擎是 log4j/reload4j；`slf4j-reload4j` 绑定它）：

```properties
log4j.rootLogger=INFO, console
log4j.appender.console=org.apache.log4j.ConsoleAppender
log4j.appender.console.layout=org.apache.log4j.PatternLayout
log4j.appender.console.layout.ConversionPattern=%d %-5p %c - %m%n
log4j.logger.com.example.dao=DEBUG, daoFile        # logger 可挂自己的 appender 列表
log4j.additivity.com.example.dao=false             # additivity 语义与 3.4 节一致（Log4j1 是这个概念的原创者）
log4j.appender.daoFile=org.apache.log4j.RollingFileAppender
log4j.appender.daoFile.File=app.log
log4j.appender.daoFile.MaxFileSize=100MB
log4j.appender.daoFile.MaxBackupIndex=10
```

**★ 桥接陷阱（第六章的延伸）**：把 `log4j:log4j` 替换成 `log4j-over-slf4j` 后，**`log4j.properties` 永远不会被读**——配置文件归引擎管，真引擎已经换成你的 SLF4J 后端了；同理 `jul-to-slf4j` 之后 `logging.properties` 里 Handler 相关配置失效（但 JUL Logger 创建与级别判断仍归 JUL LogManager 管，`removeHandlersForRootLogger` 清的正是它，2.6 节）。排查"旧配置不生效"先确认是否桥接；只有把 slf4j 绑定到 `slf4j-reload4j`，这份 properties 才继续被读。

### 7.5.7 收束：我到底该放哪个文件（决策小抄）

- **纯 Java 项目（非 Boot）**：`logback.xml`（默认栈）或 `log4j2.xml`；测试环境靠 `logback-test.xml` / `log4j2-test.*` 前缀覆盖。
- **Spring Boot**：优先 `logback-spring.xml` / `log4j2-spring.xml`（要用 `<springProfile>`/`<springProperty>` 就必须它，样例见 7.5.4 节、机制见 3.9 节）；简单场景直接 `application.yml` 的 `logging.*`（Boot 内置默认配置的 pattern 全是 `${LOG_PATTERN:-...}` 占位符，天然接受横切属性）。
- **脚本/工具/单测**：`slf4j-simple` + `-D` 系统属性零文件起步，或 `slf4j-nop` 全静音。
- **调试配置加载本身**：logback 用 `debug="true"` 或 `<statusListener>`；log4j2 用 `status="DEBUG"` / `-Dlog4j2.debug`（4.12 节）。

## 7.6 本章小结

- 依赖三选一：slf4j+logback（默认）、slf4j+log4j-slf4j2-impl、log4j-api+core；Boot 场景配 starter 排除法。
- 配置文件五要素：Properties（变量）、Appenders（Console/RollingFile/Async）、Loggers（包级覆盖）、Root（兜底）、热更新开关（scan / monitorInterval）。
- Logback 必调参数：queueSize（默认 256 太小）；Log4j2 必调：全异步开关 + RingBufferSize + includeLocation 关闭。
- 代码六条：门面唯一、参数化、异常收尾、MDC 借还、级别语义、Marker 分流。
- 配置文件全解（7.5 节）：**门面没有配置文件**，配置属于绑定/引擎；logback 与 logback-spring 双样例逐节点对照（后者=前者 + springProfile/springProperty + Boot 注入变量，禁 scan）、log4j2 Lookup 管道（jndi 默认禁用）、simple 的 11 键、JUL 双闸、Log4j1 语法，以及"桥接后旧配置文件失效"陷阱。

---

# 八、贯通视图：两条时间线 + 选型决策

> 前面各章是"分镜头"，本章把它们剪成"正片"：一次日志调用一条线、应用启动一条线，最后给决策树与面试问答。行号均已在对应章节验证，此处标注出处便于回查。

## 8.1 时间线一：一次 log.info() 从 API 到磁盘（两引擎并排）

```
业务线程调用 log.info("user {} login", uid)
│
├─【SLF4J + Logback 同步路线】
│  slf4j.Logger.info → filterAndLog_1（classic/Logger.java:392-407）
│  ├─ TurboFilter 链（LoggerContext.getTurboFilterChainDecision_1）      构造事件前拦截
│  ├─ effectiveLevelInt > INFO_INT ? return（O(1)，3.3 推送式缓存的回报）  级别判断
│  ├─ new LoggingEvent（427-432：此刻才把 {} 参数格式化进事件）            事件构造
│  └─ callAppenders（256-268）沿层级树向上
│       → CONSOLE：ConsoleAppender.append → OutputStreamAppender.writeOut（202-203）
│            encoder.encode(event) → outputStream.write   ★ 全程在业务线程，磁盘 IO 就在调用线程
│       → ASYNC_FILE：AsyncAppenderBase.append（161-168）→ put（173-179）
│            ArrayBlockingQueue.offer/put ★ 业务线程到此返回，worker 线程接力写 FILE
│
└─【Log4j2 全异步路线】（-Dlog4j2.contextSelector=AsyncLoggerContextSelector）
   api.Logger.info → AbstractLogger.logIfEnabled → isEnabled（PrivateConfig.filter）
   → AsyncLogger.logMessage（消息对象为 Message/占位符原始值，未格式化）
   → logWithThreadLocalTranslator（235-248）：ThreadLocal 翻译器写入 5 字段
   → AsyncLoggerDisruptor.tryPublish（254-259）★ 业务线程在此返回：只做一次环形缓冲搬运
        [环形缓冲 RingBufferLogEvent 槽位复用：setValues 重填（96）、swapParameters 参数数组复用（148）]
   → Disruptor 消费线程（单线程或按配置）：
        RingBufferLogEvent → LoggerConfig.log（739）→ processLogEvent（762）→ callAppenders（803）
        → PatternLayout 格式化 → RollingFileManager 写文件  ★ 全部离开业务线程
```

对比结论：**同步路线的延迟 = 最慢 Appender 的 IO 时间**；**异步路线的延迟 = 一次内存搬运（约百纳秒级）**，代价是丢失实时性、可能丢低级别日志、位置信息与异常栈快照策略受限。

## 8.2 时间线二：应用启动时，日志系统如何"上线"

```
JVM 启动，第一个用到 Logger 的类触发类加载
│
├─【SLF4J 路线】
│  LoggerFactory.getLogger → getProvider 双检锁（506-528）
│  ├─ -Dslf4j.provider 指定？→ 反射实例化
│  ├─ ServiceLoader 扫 SLF4JServiceProvider → 排序取第一个（198）→ initialize()（201）
│  │    └─【Logback】LogbackServiceProvider.initialize → ContextInitializer.autoConfig
│  │         ├─ ServiceLoader(Configurator) 自定义接管（Spring Boot 的切入口，3.2）
│  │         └─ DefaultJoranConfigurator：-Dlogback.configurationFile → logback-test.xml → logback.xml
│  ├─ 初始化期间的日志 → SubstituteLogger 攒队列 → postBindCleanUp 回放（296-336）
│  └─【Spring Boot】LoggingSystem 在 Environment 就绪后再次注入/覆盖配置（3.9）
│
└─【Log4j2 路线】
   LogManager 静态初始化：ProviderUtil.getProvider() → ServiceLoader(Provider) → Log4jProvider
   → Log4jContextFactory → ContextSelector 按 ClassLoader 取/建 LoggerContext
   → LoggerContext.start（300-322）：configLock → reconfigure（768-800）
        └─ ConfigurationFactory.getInstance（154-190）→ Factory.getConfiguration（454-559）
             log4j2.configurationFile（可逗号分隔合并）→ test 前缀 → 默认前缀 → DefaultConfiguration
        └─ monitorInterval>0 → WatchManager 注册 ConfigurationFileWatcher（onChange 见 846-868 行）
   → Logger 首次 getLogger 时 computeIfAbsent 创建并挂 PrivateConfig 指针（585-588 + 484-541）
```

两条线的共同点：**都把"发现实现→读配置→构建配置树"压缩在第一次 getLogger 里完成，之后所有调用都是查缓存 + 走管道**。差异点：SLF4J 全局唯一绑定且不可热换 Provider；Log4j2 按 ClassLoader 可多上下文，且 Logger 与配置解耦（热更新只换指针）。

## 8.3 选型决策树

```
新项目？
├─ 没有特殊性能诉求 / 团队熟悉生态
│    └─ ★ slf4j-api + logback-classic（Spring Boot 默认，社区资料最多，默认够用）
├─ 高吞吐 / 低延迟 / 大量日志（网关、撮合、大数据管道）
│    └─ ★ slf4j-api + log4j-slf4j2-impl + log4j-core + 全异步
│       （或团队统一直接写 log4j-api：能用到 Message 体系、GC-free 最深能力）
└─ 与某框架深度绑定（如 Netty 生态日志、老 Weblogic 遗留 JUL）
     └─ 保持门面 SLF4J，桥接包收编遗留 API（第六章矩阵）

存量系统？
├─ 混用 Log4j1/JCL/JUL
│    └─ 第一步：坐标替换为桥接包（log4j-over-slf4j / jcl-over-slf4j / jul-to-slf4j）
│       第二步：统一到唯一引擎；第三步：再评估要不要为异步性能切 Log4j2
└─ 已重度绑定 Log4j2
     └─ 保持 log4j-api 门面，用 log4j-1.2-api/log4j-jcl 收编遗留，不必强迁 SLF4J
```

一句话总结：**门面优先 SLF4J（生态最大、桥接最全）；引擎二选一——求稳 Logback，求极限 Log4j2**；遗产靠桥接包无痛收编。

## 8.4 面试十连

1. **SLF4J、Logback、Log4j2 什么关系？** 门面与实现。SLF4J 是 API 包，Logback/Log4j2（经 log4j-slf4j2-impl）是它的 Provider 实现；Log4j2 的 log4j-api 本身也是门面，可脱离 SLF4J 直接用。
2. **SLF4J 如何找到实现？多个绑定怎么办？** 首次 getLogger 时双检锁初始化，`-Dslf4j.provider` 显式指定 → ServiceLoader 扫 `SLF4JServiceProvider` → 取第一个并警告其余；1.7 的 StaticLoggerBinder 会被检测并忽略。
3. **Logger 层级是什么？级别继承怎么实现？** 点分命名成树；Logback 用 setLevel 时父改子推维护 effectiveLevelInt 缓存（读 O(1)）；Log4j2 由配置树 LoggerConfig 链承接（logParent 向上传播 additive）。
4. **additive/additivity 是什么？** 事件沿层级向上寻找 Appender 时是否继续传播；false 在本层截断，用于"包日志只落包文件、不重复进 root"。
5. **参数化日志为什么快？** `{}` 占位在"是否启用"判定为否时不发生任何字符串拼接与包装对象创建；Fluent API（2.0）进一步用 NOP 构建器短路链式调用。
6. **Logback 异步丢日志的规则？** 队列剩余容量低于 discardingThreshold（默认 queueSize/5）时丢弃 TRACE/DEBUG/INFO（isDiscardable，level<=INFO_INT）；neverBlock=true 时 offer 失败即丢，绝不阻塞业务。
7. **Log4j2 为什么快？** 三点：api/core 分离自带 SimpleLogger 兜底；Logger/LoggerConfig 分离 + 事件/消息对象复用（GC-free）；AsyncLogger 用 LMAX Disruptor 环形缓冲（生产端只做字段搬运，无锁 MPSC）。
8. **Log4Shell 是什么？给了我们什么教训？** CVE-2021-44228：日志消息中 `${jndi:ldap://...}` 触发 JNDI 远程加载导致 RCE。教训：日志框架是攻击面（Lookup 插件默认开启）；2.17+ 默认禁用危险 Lookup，生产要关 `log4j2.formatMsgNoLookups` 等效能力并保持升级。
9. **MDC 是什么？异步下要注意什么？** 线程级上下文 map（Logback=LogbackMDCAdapter 双 ThreadLocal；Log4j2=ThreadContext）。异步时事件入队要快照复制（Logback prepareForDeferredProcessing；Log4j2 自动），跨线程池要手动传递并清理。
10. **怎么把第三方库的日志统一到我的管道？** 看它用什么 API：JCL→jcl-over-slf4j；Log4j1→log4j-over-slf4j（或 log4j-1.2-api 直送 Log4j2）；JUL→jul-to-slf4j（Handler 挂载 + removeHandlersForRootLogger 防重复）；log4j-api→log4j-to-slf4j。核心心法：**不动代码动坐标，保证唯一引擎**。

## 8.5 学习路线（动手向）

1. **第 1 天**：空项目引 slf4j-api，什么都不绑定——观察 NOP 静默；再加 logback-classic 重跑——观察"Actual provider"与 BasicConfigurator 兜底（删掉 logback.xml 试一次 DEBUG 满屏）。
2. **第 2 天**：复刻 7.1 的 logback.xml；故意配 `scan="10 seconds"` 运行时改 root 级别观察热更新；把 queueSize 调回默认 256 压测，观察队列满时的阻塞。
3. **第 3 天**：换 Log4j2 引擎（第六章配方），开全异步 + `-Dlog4j2.debug=true`；用 JMH 或简单循环对比同步/异步吞吐；开 includeLocation 感受性能悬崖。
4. **第 4 天**：造坑——同时放 log4j-over-slf4j 与 log4j 1.x 观察警告与栈溢出；同时放两个 Provider 观察静默换绑；用 `-Dslf4j.provider` 拍板。
5. **第 5 天**：读源码收束——按 8.2 时间线把 `LoggerFactory.bind`、`LogbackServiceProvider.initialize`、`Log4jProvider` 三条初始化链在 IDEA 里走一遍断点。

---

# 九、附录

## 9.1 关键类速查表

| 类 | 所属 | 一句话 |
|---|---|---|
| LoggerFactory | slf4j-api | 门面入口：四态状态机 + 双检锁 + ServiceLoader 绑定（bind 第 193-218 行） |
| SLF4JServiceProvider | slf4j-api | 实现契约五方法：getLoggerFactory/getMarkerFactory/getMDCAdapter/getRequestedApiVersion/initialize |
| SubstituteLogger | slf4j-api | 初始化重入期替身：攒事件队列，绑定后回放 |
| LoggingEventBuilder（+NOP 实现） | slf4j-api 2.0 | Fluent API 链条：setCause/addKeyValue/log(Supplier)，未启用返回 NOP 短路 |
| Reporter | slf4j-api 2.0 | 框架内部上报：`slf4j.internal.verbosity` 调级别、`slf4j.internal.report.stream` 调输出流（取代 1.7 的 Util.report） |
| LogbackServiceProvider | logback-classic | Logback 的 SLF4J 绑定：initialize → ContextInitializer.autoConfig |
| ch.qos.logback.classic.Logger | logback-classic | 层级树节点：effectiveLevelInt 缓存 + filterAndLog* + callAppenders |
| LoggerContext（classic） | logback-classic | 全局唯一上下文：loggerCache + 层级树 + 调度器 |
| AsyncAppenderBase（core） | logback-core | 队列异步基类：ArrayBlockingQueue + discardingThreshold + neverBlock |
| ReconfigureOnChangeTask | logback-classic | scan 热更新任务：轮询 ConfigurationWatchList |
| LogbackMDCAdapter | logback-classic | 双 ThreadLocal Map + 只读快照（异步安全） |
| LogManager | log4j-api | api 门面入口：静态 factory = ProviderUtil 发现的 LoggerFactory |
| ProviderUtil | log4j-api | Provider 发现：ServiceLoader + log4j-provider.properties 遗留 |
| LoggerContext（core） | log4j-core | 可多实例上下文：start/reconfigure/updateLoggers/onChange |
| PrivateConfig（core.Logger） | log4j-core | Logger→LoggerConfig 的指针包 + 级别/Filter 缓存 |
| LoggerConfig | log4j-core | 配置树节点：log → isFiltered → callAppenders → logParent |
| ConfigurationFactory | log4j-core | 配置工厂集合：PluginManager 收集 + @Order 排序 + 文件查找链 |
| PluginManager / PluginRegistry | log4j-core | 插件索引：编译期 Log4j2Plugins.dat + 运行期加载 |
| AsyncLogger / AsyncLoggerDisruptor | log4j-core | Disruptor 异步：ThreadLocal 翻译器 + tryPublish + EventRoute 满路由 |
| RingBufferLogEvent | log4j-core | 可复用事件：setValues 重填 + swapParameters 参数复用（GC-free） |
| DefaultConfiguration | log4j-core | 兜底配置：root=ERROR + Console |
| SLF4JBridgeHandler | jul-to-slf4j | JUL→SLF4J 桥：install/removeHandlersForRootLogger/publish |
| LogFactory（jcl-over-slf4j） | jcl-over-slf4j | JCL 类替身：同包名 LogFactory 转发 SLF4J |
| LoggingSystem / LogbackLoggingSystem | spring-boot | Boot 日志统一入口：探测实现 + 配置文件接管 |
| SpringBootJoranConfigurator | spring-boot | Boot 扩展的 Joran 解析器：注册 springProfile/springProperty 两个标签规则 |
| SpringProfileModelHandler | spring-boot | `<springProfile>` 实现：`environment.acceptsProfiles` 不匹配则 `deepMarkAsSkipped` 剪掉子树 |
| SpringPropertyModelHandler | spring-boot | `<springProperty>` 实现：`environment.getProperty(source, defaultValue)` 注入 logback 变量 |

## 9.2 系统属性速查表

| 属性 | 作用 | 出处 |
|---|---|---|
| `slf4j.provider=实现类全名` | SLF4J 显式绑定指定 Provider | LoggerFactory 第 95、233-254 行 |
| `slf4j.detectLoggerNameMismatch=true` | 检测 getLogger(Class) 与调用类不符 | LoggerFactory 第 108-111、471-482 行 |
| `logback.configurationFile=路径` | 指定 logback 配置文件（**Boot 下被忽略并告警，改用 logging.config**） | ClassicConstants + DefaultJoranConfigurator；LogbackLoggingSystem 第 205-208 行 |
| `logging.config=路径` | Boot 统一的日志配置文件指定（Logback/Log4j2 引擎通用，`-spring` 变体之外的手动入口） | LoggingApplicationListener 第 328-340 行 |
| `log4j2.configurationFile=路径[,路径2]` | 指定 log4j2 配置（逗号分隔=合并） | ConfigurationFactory 第 458-483 行 |
| `log4j2.debug=true` | StatusLogger 全量内部日志 | ConfigurationFactory 第 555 行提示 |
| `log4j2.contextSelector=...AsyncLoggerContextSelector` | Log4j2 全异步 | 4.3/4.8 |
| `log4j2.enable.threadlocals=true` | 开 ThreadLocal 池化（GC-free 前提，web 默认关） | Constants 第 65-66 行 |
| `AsyncLogger.RingBufferSize=N` | 环形缓冲槽位数（向上取 2 的幂） | DisruptorUtil 第 87-103 行 |
| `log4j2.asyncQueueFullPolicy=Discard` / `log4j2.discardThreshold=INFO` | 环形缓冲满时丢低级别 | AsyncLogger.handleRingBufferFull 第 286-301 行 |
| `log4j2.julLoggerAdapter` / log4j-jul 相关 | JUL 接管适配 | log4j-jul 模块 |
| `-Dlog4j2.plugin.packages=x.y` | 额外插件扫描包 | PluginManager collectPlugins |

## 9.3 依赖坐标速查（2026-10 当前稳定版）

| 用途 | 坐标 | 版本 |
|---|---|---|
| 门面 | `org.slf4j:slf4j-api` | 2.0.20 |
| 引擎 A | `ch.qos.logback:logback-classic`（传递 core） | 1.5.38 |
| 引擎 B | `org.apache.logging.log4j:log4j-core`（传递 api） | 2.26.1 |
| SLF4J→Log4j2 绑定 | `org.apache.logging.log4j:log4j-slf4j2-impl` | 2.26.1 |
| Log4j2 api 调用→SLF4J | `org.apache.logging.log4j:log4j-to-slf4j` | 2.26.1 |
| JCL→SLF4J | `org.slf4j:jcl-over-slf4j` | 2.0.20 |
| Log4j1 API→SLF4J | `org.slf4j:log4j-over-slf4j` | 2.0.20 |
| JUL→SLF4J | `org.slf4j:jul-to-slf4j` | 2.0.20 |
| Log4j1 API→Log4j2 | `org.apache.logging.log4j:log4j-1.2-api` | 2.26.1 |
| JCL→Log4j2 | `org.apache.logging.log4j:log4j-jcl` | 2.26.1 |
| JUL→Log4j2（Handler） | `org.apache.logging.log4j:log4j-jul` | 2.26.1 |
| SLF4J→JUL 绑定 | `org.slf4j:slf4j-jdk14` | 2.0.20 |
| 测试用最小绑定 | `org.slf4j:slf4j-simple` / `slf4j-nop` | 2.0.20 |
| Boot 默认日志 starter | `org.springframework.boot:spring-boot-starter-logging` | 4.x |
| Boot Log4j2 starter | `org.springframework.boot:spring-boot-starter-log4j2` | 4.x |

## 9.4 排错清单（按症状索引）

| 症状 | 根因定位 | 处置 |
|---|---|---|
| 日志完全没输出 | 没有任何 Provider（NOP） | 加绑定包；`LoggerFactory.getILoggerFactory()` 验证 |
| "Class path contains SLF4J bindings targeting slf4j-api versions 1.7.x or earlier" | 老式绑定包在 classpath 上但被 2.x 忽略 | 换 2.x 兼容绑定（logback-classic 1.3+/log4j-slf4j2-impl） |
| "Multiple bindings were found on the class path" | 多个 Provider | 物理排除多余包，或 `-Dslf4j.provider` 拍板 |
| 日志打两遍 | additivity 未关 / JUL Handler 未清 / 同事件多 Appender | `<logger additivity="false">`；`SLF4JBridgeHandler.removeHandlersForRootLogger()` |
| 日志顺序错乱 | 异步 + 多 Appender 并发 | 业务上依赖 traceId 而非顺序；需要严格顺序的审计日志走同步专属 Appender |
| 高峰期接口抖动 | 同步日志 IO 或异步队列满阻塞 | 调大 queueSize / 开 Log4j2 全异步 / neverBlock 与丢弃策略权衡 |
| 异步日志缺 %M/%L 位置 | 位置信息默认不入队 | includeLocation（代价自负）或改用键值定位 |
| MDC 值串线程/丢失 | 线程池复用未清理 / 异步未复制 | try/finally 清理；确认事件复制机制（3.7/4.10） |
| 改配置不生效 | 热更新未开 / 改的不是实际加载的文件 | 开 scan/monitorInterval；启动日志确认加载路径（Status/StatusLogger） |
| `no applicable action for [springProfile]/[springProperty]` | 扩展标签写进了 `logback.xml`（被 Logback 裸解析）或开了 `scan` | 改用 `logback-spring.xml`；scan 与 Boot 扩展二选一（3.9.5 节） |
| logback-spring.xml 好像没生效 | 同目录存在 `logback.xml`（标准名优先）或 `logging.config` 指向了别的文件 | 删掉/改名 `logback.xml`；启动日志确认加载路径（3.9.2 节） |
| `%clr`/`%wEx` 报未知转换符 | Boot 内置转换器只注册在"无配置文件"默认路径 | 自定义文件里自己 `<conversionRule>` 声明（3.9.5 节） |
| StackOverflowError 与日志有关 | 桥接包循环转发（6.4） | 依赖树排环，删一个方向的桥 |
| 启动报 No Log4j 2 configuration file found | 配置文件未命名/未进 resources | 按 4.6 的文件名清单核对 |
