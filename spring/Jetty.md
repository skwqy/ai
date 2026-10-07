# Jetty 深度源码解析（独立启动与 Spring Boot 内嵌：同一台服务器的两条生命线）

> **本文基于的源码**：
> - **Jetty 12.1.12**（tag `jetty-12.1.12`，仓库 `D:\code\3rd\jetty`）。所有【源码证据】的文件路径与行号均为对该 tag 实际读取所得。
> - **Spring Boot 4.0.8**（tag `v4.0.8`，仓库 `D:\code\3rd\spring-boot`，工作树 `D:\tmp\sb408`）——负责"嵌入式启动"一侧的证据。Boot 4.0 官方管理的 Jetty 版本即 12.1.12（`platform/spring-boot-dependencies/build.gradle:1045`：`library("Jetty", "12.1.12")`），所以本文两侧版本天然对齐，不存在"文档讲的和你用的不一样"的问题。
> - 行文与证据约定与《Spring Framework.md》《Spring MVC.md》一致：**先白话、后源码**，证据链格式为 `模块/.../类名.java` + 方法名 + 行号 + 代码片段。行号只对上述快照精确，读者按"类名 + 方法名"定位即可。
>
> **阅读约定（本文特有）**：Jetty 12 把代码分成 `jetty-core`（纯 HTTP 服务器骨架，无 Servlet 依赖）与 `jetty-eeN`（N=8/9/10/11，Servlet 各时代的适配层）两大块。**谈"内核"时路径都在 `jetty-core` 下，谈"Servlet"时在 `jetty-ee11` 下**，下文路径会写全模块名，不再重复解释。

## 如何读这份文档

- **如果你只想搞清楚"Java -jar start.jar 到底做了什么，Spring Boot 内嵌 Jetty 又做了什么，两者差在哪"**：读第一章（总览）→ 第三章、第四章（独立启动两章）→ 第六章（Boot 内嵌）→ 第七章（两条启动线对比，本文的核心章）。
- **如果你想理解 Jetty 的内核设计（为什么它是"最像 Netty 的 Servlet 容器"）**：读第二章（LifeCycle 容器与 Server 对象图），这是所有章节的地基。
- **如果你在做生产运维（线程池、端口、优雅停机、热部署）**：第二、六、七章都有对应小节，第七章 7.4 有"什么时候还值得独立跑 Jetty"。
- **系统通读**：第一章建立地图 → 第二章内核 → 第三、四章独立启动 → 第五章请求链路 → 第六章 Boot 内嵌 → 第七章对比 → 第八章附录。

---

# 一、总览：Jetty 的定位、设计哲学与模块全景

## 1.1 一句话定位

**Jetty 是一个以 `LifeCycle` 组件树为骨架、以异步 I/O 为血液的 Servlet 容器与 HTTP 服务器**：它把"服务器"拆成一棵可组装的组件树（线程池、调度器、连接器、Handler），每个组件只做一件事，靠统一的 start/stop 生命周期串起来；再往上用 Servlet 规范（`jetty-eeN` 模块）承接 Web 应用。

和《Spring MVC.md》里分析过的 Tomcat 相比，两者的表层职责一样（字节流 → `HttpServletRequest`），但工程性格截然不同：

- **Tomcat** 是"全能应用服务器"：完整覆盖 Servlet/JSP/集群/管理界面，内部以 Pipeline/Valve 责任链组织请求处理，代码体量大（约 4000+ 源文件）。
- **Jetty** 是"组件化骨架"：核心极小（`jetty-core` 的 `Server` 类只有约 1000 行），一切能力（HTTP/2、WebSocket、JNDI、注解扫描）都以可选组件挂进来。官方自我定位一直在强调 "small, embeddable, asynchronous"——**嵌入是它的第一公民身份，独立发行版反而是一层"壳"**。

这个性格差异直接决定了本文的主线：**Jetty 独立启动是"壳"在替它拼装对象图（start.jar + 模块系统 + XML 装配脚本），Spring Boot 内嵌是"另一个壳"（Boot 的自动装配）在替它拼装对象图**。两条启动线殊途同归——终点都是同一个 `Server.start()`。看懂了两条线怎么"拼"、拼到什么程度、谁负责 start，Jetty 的设计就全通了。

## 1.2 设计哲学：读源码前先记住四句话

1. **一切皆 LifeCycle 组件**。Jetty 里几乎所有对象（`Server`、`Connector`、线程池、每个 `Handler`）都实现 `LifeCycle` 接口（`jetty-util/.../component/LifeCycle.java:28`），并有 `ContainerLifeCycle` 提供"容器聚合"能力——把子组件 addBean 进来，容器 start 时它们按加入顺序自动 start（见 2.2 节）。这与 Spring 的 `Lifecycle`/`SmartLifecycle` 异曲同工，但 Jetty 的更"底层"：它就是整个服务器的组织方式，而不是容器对 bean 的附加回调。
2. **组合优于继承，Handler 即插件**。Jetty 12 中一个请求的处理入口是 `Handler` 接口的唯一方法 `handle(Request, Response, Callback)`（`jetty-server/.../Request.java:830`）。`Server` 本身就是一个 `Handler.Wrapper`（`Server.java:77`），内部挂着一棵 Handler 树（`Handler.Sequence` 顺序执行、`ContextHandlerCollection` 按上下文分发……）。想加功能？包一层 Handler，就像 Servlet 的 Filter，但粒度更粗、能力更强（可以改写请求、路由、缓存）。
3. **异步是默认，阻塞是特例**。Jetty 12 的内容抽象是 `Content.Source`/`Content.Sink`（`jetty-io/.../Content.java:162`），读写都通过 `Callback`（成功 `succeeded()` / 失败 `failed()`）通知，而不是阻塞等待。连接器侧 `SelectorManager` 用少量 NIO selector 线程服务成千上万连接。Servlet 侧再由 `ServletContextHandler` 把异步内核"翻译"成同步的 `Servlet.service()`。
4. **Core 与 EE 分离，XML 只是壳的壳**。Jetty 12 起仓库按 `jetty-core`（协议与 I/O，零 Servlet 依赖）+ `jetty-ee8/9/10/11`（四个 Servlet 时代适配层，同一个 core 上并存）组织。而"独立发行版"里那些 `jetty.xml`、`jetty-http.xml` 并不是硬编码的启动逻辑，而是 `jetty-xml` 模块这个"XML 装配脚本引擎"解释执行的**配置数据**——这正是 Spring Boot 能把 Jetty 抽走换掉的根本原因：Jetty 本身就不依赖任何一种装配方式。

## 1.3 模块分层全景

Jetty 12 仓库根目录实测分组如下（本文只深入标注 ★ 的模块）：

```
┌──────────────────── 发行版层（jetty-home：最终 java -jar start.jar 的成品）────┐
│  start.jar ← jetty-start 模块编译产物（Main + 模块系统）                        │
│  modules/*.mod ← 从各模块 src/main/config/modules/ 汇编而来                    │
│  etc/*.xml、bin/jetty.sh ← 从各模块 src/main/config/ 汇编而来                  │
├──────────────────── Servlet 适配层（jetty-ee8/ee9/ee10/ee11 并存）──────────────┤
│  ★ jetty-ee11-servlet（ServletContextHandler/ServletHandler/ServletHolder）    │
│  ★ jetty-ee11-webapp（WebAppContext/WebAppClassLoader/Configurations）         │
│  jetty-ee11-annotations（注解扫描）、ee11-plus(JNDI)、ee11-jsp、ee11-websocket… │
├──────────────────── 协议层（jetty-core）──────────────────────────────────────┤
│  jetty-http3 / jetty-http2 / ★ jetty-http（HTTP/1.1 解析器+HttpConnection）    │
│  jetty-websocket-core                                                          │
├──────────────────── 服务器骨架（jetty-core）─────────────────────────────────┤
│  ★ jetty-server（Server/Handler/ServerConnector/HttpChannel）                  │
│  ★ jetty-xml（XmlConfiguration：XML 装配脚本引擎——独立启动的灵魂）             │
│  ★ jetty-deploy（DeploymentScanner/StandardDeployer：webapps 目录热部署）      │
│  jetty-security、jetty-session、jetty-jmx…                                     │
├──────────────────── I/O 与地基（jetty-core）─────────────────────────────────┤
│  ★ jetty-io（SelectorManager/EndPoint/Content.Source/Sink/ByteBufferPool）     │
│  ★ jetty-util（LifeCycle/ContainerLifeCycle/QueuedThreadPool/Callback/日志）   │
└──────────────────────────────────────────────────────────────────────────────┘
```

一个容易误解的点：**`jetty-home`（发行版）不是源码模块，而是"汇编车间"**——它把各模块的 `src/main/config/` 下的 `.mod` 文件、`etc/*.xml` 打包成发行版目录。所以本文读到的 `server.mod` 在源码里的真实路径是 `jetty-core/jetty-server/src/main/config/modules/server.mod`，装进发行版后才变成 `$JETTY_HOME/modules/server.mod`。

## 1.4 模块依赖图（以各模块 pom.xml 实证）

```
                jetty-util（LifeCycle/线程池/Callback：一切的地基）
                  ▲              ▲
                  │              │
              jetty-io ◄──── Content.Source/Sink、SelectorManager
                  ▲
                  │
              jetty-http（HTTP/1.1 解析器）──► jetty-server（Server/Connector/Handler）
                  ▲                              ▲            ▲
                  │                              │            │
             jetty-xml（装配脚本引擎，被 start 调用，server 不依赖它）
                                                 │            │
                              jetty-security ────┘   jetty-session
                                                 │
                                jetty-ee11-servlet（+ jakarta.servlet-api）
                                                 ▲
                                jetty-ee11-webapp（WebAppContext/类加载/Configuration 链）
                                                 ▲
                              jetty-ee11-deploy / jetty-start（start.jar）
```

真实依赖声明（摘自各模块 `pom.xml`，已逐个验证）：

| 模块 | 直接依赖 |
|---|---|
| jetty-util | slf4j-api（无 Jetty 内部依赖，纯地基） |
| jetty-io | jetty-util |
| jetty-http | jetty-io, jetty-util |
| jetty-server | jetty-http, jetty-io, jetty-jmx |
| jetty-xml | jetty-util（独立于 server，仅被 start.jar 引用） |
| jetty-ee11-servlet | jakarta.servlet-api, jetty-security, jetty-server, jetty-session |
| jetty-ee11-webapp | jetty-ee11-servlet |
| Spring Boot `spring-boot-jetty` | `org.eclipse.jetty.ee11:jetty-ee11-webapp`（api，传递带入 servlet/server/http 全链） |

这张图本身就是架构说明：**`jetty-xml` 不在 `jetty-server` 的依赖里**——XML 装配是 start.jar 这一"壳"的行为，不是 Jetty 内核的能力；**Boot 只依赖 `jetty-ee11-webapp` 一个坐标**，整条 Servlet 链随之而来。

## 1.5 关键问题 → Jetty 方案映射（全文导览）

| 关键问题 | Jetty 的方案 | 详见 |
|---|---|---|
| 服务器由几百个对象组成，谁管它们的创建与启动顺序 | ContainerLifeCycle 聚合：加入顺序即启动顺序，失败逆序回滚 | 第二章 |
| 同一份服务器骨架，如何服务完全不同的部署形态 | 装配与内核分离：start.jar/XML 是一种装配，Spring Boot 工厂是另一种 | 第三、四、六、七章 |
| 独立发行版不想改代码就能加功能（HTTP/2、JSP、热部署…） | 模块系统：.mod 文件声明依赖/类库/XML，按需启用 | 第三章 |
| 配置项（端口、线程数）如何在 XML 里生效 | XmlConfiguration 的 `<Property>` 与 Props 体系（ini/系统属性/命令行） | 第四章 |
| 万级连接下线程不爆炸 | 异步内核：SelectorManager + Content.Source/Sink + Callback | 第二、五章 |
| 多个 webapp 类冲突 | WebAppClassLoader：child-first + 隐藏类/系统类过滤 | 4.6 |
| Spring Boot 里 DispatcherServlet、Filter 如何注册进 Jetty | ServletContextInitializer 桥：ServletContextInitializerConfiguration | 6.4 |
| 容器还没 refresh 完，请求来了怎么办 | Boot 的"摘除 connectors 两阶段启动"+ Jetty 的 deferred initialize | 6.5、7.5 |
| 优雅停机 | 独立：stopTimeout + ShutdownThread；Boot：StatisticsHandler 轮询 + SmartLifecycle | 6.6、7.8 |

## 1.6 版本演进：9.x → 10/11 → 12

写作时（2026 年 10 月）的版本格局：9.4 与 10 已结束社区维护（9.4 商业支持延续），**11 处于维护态，12.1 是活跃主线**（本文基线 12.1.12，Spring Boot 4.0 同款）。

| 版本 | GA 时间 | Servlet API | 对应 Spring Boot | 一句话主题 |
|---|---|---|---|---|
| Jetty 9.4 | 2016 | javax（EE8） | 2.x | 9.x 的长命维护线；Boot 2 时代的默认非 Tomcat 选项 |
| Jetty 10 / 11 | 2021-06（双发） | 10 = javax（EE8）、11 = jakarta（EE9/10） | —（过渡版本） | 内部重构：`org.eclipse.jetty.eeN` 包名前缀、异步化收尾；11 完成 javax→jakarta |
| Jetty 12.0 | 2023-07 | 无（core 不含 Servlet）+ ee8/9/10 适配层 | 3.2~3.5 | **架构大拆分**：`Server`/`Handler` 全新 core API（`Request`/`Response`/`Callback`），同一 core 上四种 EE 环境并存 |
| Jetty 12.1（本文基线） | 2025 起 | ee11（Jakarta EE 11，Servlet 6.1） | 4.0 | EE11 适配 + core 精修（HTTP/3、连接限额、多环境 `--env` 正式化） |

三个对读源码有直接影响的版本锚点：

- **12.x 的 `Handler` API 与 9/10/11 完全不同**。老教程里的 `Server.handle(String, Request, HttpServletRequest, HttpServletResponse)` 或 `HandlerWrapper` 单子模式已换成"`Handler.process`（core）+ Handler 树 + Sequence/Collection"。网上资料若出现 `HandlerList`（11 及以前）对应 12 的 `Handler.Sequence`。
- **Jetty 12 的 ServletContextHandler 落在 `org.eclipse.jetty.ee1N.servlet` 包**（每个 EE 环境一份），不再有"一个类服务所有规范版本"的幻想——这是 12 拆分的直接代价，也是它能把 ee8~ee11 并行维护的资本。
- **Spring Boot 侧的对应关系**：Boot 3.0/3.1 用 Jetty 11，Boot 3.2 起用 Jetty 12（EE10），Boot 4.x 用 Jetty 12.1（EE11）。Boot 4 还把嵌入式封装类从 `o.s.boot.web.embedded.jetty` 挪到了 `org.springframework.boot.jetty`（模块拆成 `spring-boot-web-server` + `spring-boot-jetty`），搜老资料时注意。

## 1.7 全文章节地图

- **第二章 内核（jetty-core）**：LifeCycle 状态机 → ContainerLifeCycle 聚合 → Server 双重身份 → Handler 树 → QueuedThreadPool → ServerConnector。这是"同一个 Jetty"被两种方式装配前的本体。
- **第三章 独立启动（上）：start.jar 与模块系统**：Main 入口 → jetty.home/jetty.base → .mod 模块图与拓扑排序 → classpath 与 XML 清单的产生。
- **第四章 独立启动（下）：XmlConfiguration 组装 Server**：XML DSL 全集 → 默认 XML 链逐文件解析 → 统一 start → webapps 热部署（DeploymentScanner → WebAppContext → WebAppClassLoader）。
- **第五章 请求链路（速写）**：从 accept 到 Servlet.service() 的骨架，衔接《Spring MVC.md》第四章。
- **第六章 Spring Boot 内嵌启动线**：自动装配 → 工厂方法拼装 → ServletContextInitializer 桥 → 两阶段启动 → 优雅停机 → 响应式/虚拟线程/指标。
- **第七章 两条启动线对比**：15 项维度总表 + 逐项展开（本章是全文答案所在）。
- **第八章 附录**：关键类速查、30 个源码入口、动手验证清单。

---

# 二、内核：LifeCycle 容器与 Server 对象图（jetty-core）

## 2.1 先说白话：Jetty 的内核是一棵"会自己启动的组件树"

一台 Jetty 服务器运行时大约有这几类对象：线程池（`QueuedThreadPool`）、定时器（`Scheduler`）、字节缓冲池（`ByteBufferPool`）、网络连接器（`ServerConnector`）、若干层 Handler。它们之间不是平级协作，而是**一棵树**：`Server` 是根，`Connector` 和顶层 Handler 是它的子节点，Handler 下面还可以再挂 Handler。

这棵树有两个特殊性质：

1. **启动会传染**：根节点 start 时，子节点按顺序自动 start——不用谁写一行"依次启动"的代码。
2. **每个节点知道自己该干什么**：`ServerConnector.doStart()` 负责打开监听端口，`ServletContextHandler.doStart()` 负责初始化 Servlet 上下文——**启动逻辑内聚在每个组件的 doStart 里**，树的遍历只负责时机与顺序。

这两个性质由两个类实现：`LifeCycle`/`AbstractLifeCycle`（单节点的状态机）和 `ContainerLifeCycle`（树的聚合与遍历）。下面逐个看源码。

## 2.2 LifeCycle：一个只有 start/stop 的状态机

【源码证据】`jetty-core/jetty-util/src/main/java/org/eclipse/jetty/util/component/LifeCycle.java:28-134`

```java
public interface LifeCycle
{
    void start();
    void stop();
    boolean isRunning();
    boolean isStarted();
    boolean isStarting();
    boolean isStopping();
    boolean isStopped();
    boolean isFailed();
    // ... 另有 addEventListener/removeEventListener 用于监听状态迁移
}
```

实现类 `AbstractLifeCycle` 把 `start()` 声明为 **final**（`AbstractLifeCycle.java:73`），禁止子类绕过状态机：

```java
public final void start() throws Exception
{
    try (AutoLock ignored = _state.lock())   // 同一时刻只允许一个线程做状态迁移
    {
        switch (_state)
        {
            case STOPPED:
                setStarting();          // 广播 STARTING 事件给监听器
                doStart();              // 模板方法：真正的工作在子类 doStart 里
                setStarted();           // 广播 STARTED
                return;
            // STARTED 重复 start 无害；STOPPING/STARTING 中则抛异常……
        }
    }
}
```

【要点】与 Spring 的 `Lifecycle` 对比：Spring 的 `start()` 是容器对 bean 的**回调**（由 `DefaultLifecycleProcessor` 驱动），bean 本身通常不知道自己是 Lifecycle；而 Jetty 的 `LifeCycle` 是**每个组件的身份证**——组件树靠它自动传播启动。这是第六章 Boot 内嵌时"`JettyWebServer` 包一层自己的 start/stop"的原理基础。

## 2.3 ContainerLifeCycle：聚合 + 自动启动 + 失败回滚

【源码证据】`jetty-core/jetty-util/src/main/java/org/eclipse/jetty/util/component/ContainerLifeCycle.java:81`

```java
public class ContainerLifeCycle extends AbstractLifeCycle implements Container, Destroyable, Dumpable.DumpableContainer
```

它用 `_beans` 列表保存子组件，每个子组件有三种托管状态（类注释 `:38-55` 写得很清楚）：

- **MANAGED**：随容器一起 start/stop/destroy；
- **UNMANAGED**：只挂个名（dump 时可见），生命周期自理；
- **AUTO**：加进来时看情况——容器已启动且 bean 自己在跑 → UNMANAGED；否则 MANAGED。

`doStart()`（`:93-160`）就是"按加入顺序启动所有 MANAGED/AUTO bean"，失败则**逆序回滚**：

```java
protected void doStart() throws Exception
{
    _doStarted = true;
    try
    {
        for (Bean b : _beans)
        {
            if (b._bean instanceof LifeCycle l)
            {
                switch (b._managed)
                {
                    case MANAGED:
                        if (l.isStopped() || l.isFailed())
                            start(l);            // 子组件的 doStart 在此被触发
                        break;
                    case AUTO:
                        if (l.isStopped()) { manage(b); start(l); }
                        else { unmanage(b); }
                        break;
                    // ...
                }
            }
        }
    }
    catch (Throwable th)
    {
        // on failure, stop any managed components that have been started
        List<Bean> reverse = new ArrayList<>(_beans);
        Collections.reverse(reverse);
        for (Bean b : reverse) { /* 逆序 stop 已启动的 MANAGED bean */ }
        throw th;
    }
}
```

`doStop()` 同样是**逆序**（`:188-207`）。【要点】**启动顺序 = 组件加入顺序**——这句话是理解两条启动线的钥匙：独立启动时 XML 从上到下 new 出来的顺序决定了谁先 start；Boot 内嵌时工厂代码的书写顺序决定了同样的事。Jetty 没有依赖注入容器，没有依赖图分析（模块系统的 TopologicalSort 只用于决定 XML 文件的执行顺序，见 3.4），**顺序完全靠"添加顺序"这一约定**。

另外 `Container` 接口支持 `addEventListener`——任何 bean 加进来若是 `EventListener` 会自动注册为容器监听器。Server 启动日志里的 "Started Server@..." 就是各层监听器逐级打印的。

## 2.4 Server：既是根容器，又是 Handler 树的根

【源码证据】`jetty-core/jetty-server/src/main/java/org/eclipse/jetty/server/Server.java:77`

```java
public class Server extends Handler.Wrapper implements Attributes
```

【白话】`Server` 有三重身份：**(a) 一个 ContainerLifeCycle**（继承自 `Handler.Wrapper → Handler.Abstract → ContainerLifeCycle`）， Connector 和全局组件都 addBean 进来；**(b) 一个 Handler**（Wrapper 持有下一个 Handler——通常是通过 `setHandler()` 挂上的 ContextHandlerCollection）；**(c) 全局属性载体**（`Attributes`）。

构造器（`:106-149`）体现了"组件注入"设计：

```java
public Server(@Name("threadPool") ThreadPool threadPool, @Name("scheduler") Scheduler scheduler,
              @Name("bufferPool") ByteBufferPool bufferPool)
```

线程池/调度器/缓冲池都可以从外部注入；不传则各自 new 默认实现。第六章会看到 Boot 的工厂正是走 `new Server(getThreadPool())` 这条构造器。

`doStart()`（`:568-631`）做了几件全局事务：

```java
protected void doStart() throws Exception
{
    _startupDateTime = ZonedDateTime.now();
    if (getStopAtShutdown())
        ShutdownThread.register(this);          // 注册 JVM shutdown hook（第七章：Boot 会关掉它）
    // ...
    for (ShutdownService service: getBeans(ShutdownService.class))
    { service.addComponent(this); service.start(); }
    if (_errorHandler == null)
        setErrorHandler(new DynamicErrorHandler());
    LOG.info("jetty-{}; built: {}; git: {}; jvm {}", getVersion(), ...);
    // ... 随后 super.doStart() 触发 Connector 等 bean 启动
}
```

【要点】`ShutdownThread.register` 与 `setStopAtShutdown`：独立启动时靠 JVM shutdown hook 兜底优雅停机；Boot 内嵌时 `JettyWebServer.initialize()` 里显式 `setStopAtShutdown(false)`（`JettyWebServer.java:129`）——**把停机主权从 JVM hook 移交给 Spring 生命周期**，这是第七章对比的重要一笔。

## 2.5 Handler：Jetty 12 的请求处理契约

【源码证据】`jetty-core/jetty-server/src/main/java/org/eclipse/jetty/server/Handler.java:119`

```java
public interface Handler extends LifeCycle, Destroyable, Request.Handler
```

真正干活的方法在 `Request.Handler`（`jetty-server/.../Request.java:791-830`）：

```java
boolean handle(Request request, Response response, Callback callback) throws Exception;
```

契约三条：返回 `true` 表示"我接了，处理完后**由我**完成 callback"（可能同步也可能异步、跨线程）；返回 `false` 表示"我不处理，别人来"；抛异常则交给 error handler。

常用的树形组合件（均在 `jetty-server/.../handler/` 或 `Handler` 内部类）：

| 组件 | 职责 |
|---|---|
| `Handler.Sequence`（`Handler.java:813`） | 顺序执行所有子 Handler，直到某个返回 true（12.x 取代 11 的 HandlerList） |
| `Handler.Wrapper` | 单链包装（12.x 取代 HandlerWrapper），`Server` 本身就是它 |
| `ContextHandlerCollection`（`Server.java:23` 的 jetty.xml 默认挂载） | 按 contextPath 把请求路由给对应 ContextHandler，支持运行时动态 deploy |
| `DefaultHandler` | 兜底 404（顺便列出现有 context 列表、送 favicon） |
| `StatisticsHandler` | 请求计数——Boot 优雅停机的探针（见 6.6） |
| `GzipHandler` / `CompressionHandler` | 压缩（Boot 用的是 jetty-compression 模块的 CompressionHandler） |

【要点】与 Servlet 的 Filter 对比：Filter 是"每个请求都过、按 url-pattern 匹配"；Handler 是"树形路由，命中即止"。与 Tomcat 的 Valve 对比：Valve 是 Pipeline 上的线性拦截点，Handler 是递归树。**Jetty 的整个 Servlet 适配层（ServletContextHandler）也只是这棵树上的一个节点**——这正是 Boot 内嵌时能把"没有 webapps 目录、没有 web.xml 的 Jetty"拼出来的原因。

## 2.6 QueuedThreadPool 与 ServerConnector：两条腿

### 线程池

【源码证据】`jetty-core/jetty-util/src/main/java/org/eclipse/jetty/util/thread/QueuedThreadPool.java:125-133`

```java
public QueuedThreadPool() { this(200); }
public QueuedThreadPool(@Name("maxThreads") int maxThreads)
{ this(maxThreads, Math.min(8, maxThreads)); }
```

- 代码默认 200 上限 / 最少 8 线程；而发行版 XML（`jetty-core/jetty-server/src/main/config/etc/jetty-threadpool.xml:24-25`）默认 min=10、max=200，并支持 `useVirtualThreads` 属性（配合 `jetty-threadpool-virtual` 模块切到 `VirtualThreadPool`）。
- 线程池同时实现了 `ThreadPool` 与 `LifeCycle`——它也是树上的一个 bean。

### 连接器

【源码证据】`jetty-core/jetty-server/src/main/java/org/eclipse/jetty/server/ServerConnector.java:203-218`

```java
public ServerConnector(Server server, Executor executor, Scheduler scheduler,
        ByteBufferPool bufferPool, int acceptors, int selectors, ConnectionFactory... factories)
{
    super(server, executor, scheduler, bufferPool, acceptors, factories);
    _manager = newSelectorManager(getExecutor(), getScheduler(), selectors);  // NIO 管理器
    installBean(_manager, true);
    setAcceptorPriorityDelta(-2);   // acceptor 线程优先级略低：先保证业务线程
}
```

三个默认值要记住（运维调参常用）：

- **acceptors**：`AbstractConnector.java:201`——`acceptors < 0` 时取 1（超过 CPU 核数会告警 `:203`）。acceptor 只做一件事：`accept()` 拿到 socket 后扔给 selector。
- **selectors**：`SelectorManager.java:88-96`——`selectors <= 0` 时走 `defaultSelectors(executor)` 启发式（注释见 `:67`：默认约为 CPU 核数一半）。selector 负责 I/O 事件的检测与分发。
- **ConnectionFactory 是协议的插件位**：`HttpConnectionFactory`（HTTP/1.1）、`HTTP2CServerConnectionFactory`（明文 HTTP/2）、`HTTP2ServerConnectionFactory + ALPN`（TLS 上的 HTTP/2）……一个 connector 可以同时挂多个 factory（按协商结果选择），这就是 Jetty"协议层与传输层分离"的落点。

`doStart()`（`ServerConnector.java:224-239`）打开监听通道：

```java
protected void doStart() throws Exception
{
    addBean(_acceptChannel);
    super.doStart();
    if (getAcceptors() == 0)
    {
        _acceptChannel.configureBlocking(false);
        _acceptor.set(_manager.acceptor(_acceptChannel));  // 0 acceptor 模式：由 selector 兼职 accept
    }
}
```

端口绑定发生在 `open()`（`:309`，内部 `openAcceptChannel()` bind+listen）。**所以"Server.start() → Connector.start() → 端口开始监听"这条链，就是独立启动与内嵌启动最后都殊途同归的那一步**；Boot 甚至专门在这条链上做了文章（6.5 节）。

## 2.7 请求链路速写（细节留给第五章）

一个 HTTP/1.1 请求的骨架：acceptor `accept()` → `ManagedSelector` 注册读事件 → 数据到达后由 `HttpConnection`（`jetty-http` 模块）解析请求头 → 构造 `Request`/`Response` 对象交给 `HttpChannel` → `HttpChannel` 调用 `Server.handle(...)` 进入 Handler 树 → 最终某片叶子（如 Servlet 适配层的 `ServletHandler`）执行业务 → 写响应经 `Content.Sink` 异步落回 socket。全程除业务代码外无阻塞调用，线程处理完即归还线程池。

## 2.8 和 Spring IoC 容器对照：像，但不一样

| 维度 | Jetty ContainerLifeCycle | Spring BeanFactory/ApplicationContext |
|---|---|---|
| 元数据 | 无（对象由调用方 new 出来直接 addBean） | BeanDefinition（配方，可延迟实例化） |
| 依赖注入 | 无——构造参数手工传（XML 里靠 Ref/Property） | 核心能力（byType/byName/构造器注入） |
| 启动顺序 | **添加顺序**（约定） | 依赖图分析 + @DependsOn + @Order |
| 生命周期 | LifeCycle.start/stop 内聚于组件 | 容器驱动：InitializingBean/SmartLifecycle/destroy 回调 |
| 失败回滚 | 逆序 stop 已启动组件（ContainerLifeCycle.doStart:137-158） | refresh 失败 → destroyBeans + cancelRefresh |
| 扩展模型 | Handler 包装、bean 监听器 | BeanPostProcessor 等 20+ 扩展点 |

**这个对照是第七章的理论底座**：正因为 Jetty 没有"容器级依赖注入"，独立启动才需要 XML 里的 `<Ref refid="threadPool"/>` 手工传引用（4.1 节），Boot 内嵌才需要自己按正确顺序 new 组件（6.3 节）——两种"壳"本质上都在替 Jetty 做本该容器做的事。

## 2.9 本章小结

- Jetty 内核 = `LifeCycle` 状态机 + `ContainerLifeCycle` 聚合树：组件实现 doStart/doStop，容器按**加入顺序**自动启动、**逆序**停止并回滚。
- `Server` 是根容器兼 Handler 树根；`Connector` 挂协议工厂（ConnectionFactory），端口在 `Connector.start()` 时打开。
- Handler 树是 Jetty 的"插件总线"，Servlet 适配层只是树上一节点——这让"嵌入式 Jetty"天然成立。
- Jetty 无 IoC 容器：谁装配，谁负责传引用和排序。独立发行版用 XML 装配（第四、五章），Spring Boot 用 Java 工厂装配（第六章）。

---

# 三、独立启动线（上）：start.jar 与模块系统（jetty-start）

## 3.1 模块定位与全景

`jetty-core/jetty-start`（编译产物即发行版根目录的 `start.jar`）的职责一句话：**解析命令行与配置目录 → 算出"启用哪些模块 → 加载哪些 jar → 执行哪些 XML" → 拉起主类**。它自己不 import 任何 Jetty 内核类（看它的 pom 只有 `jetty-util`），是纯粹的"引导层"。

全景时序（本章讲 1~4 步，第 5 步留到第四章）：

```
jetty.sh / java -jar start.jar
   └─① Main.main() → processCommandLine()          命令行 + start.ini/start.d/*.ini 解析
       └─② BaseHome：定位 jetty.home（只读发行版）与 jetty.base（用户实例）
           └─③ Modules：读 modules/*.mod → 拓扑排序启用集合 → 得到 [lib]→classpath、[xml]→清单
               └─④ Main.start()：classpath 构造 ClassLoader（线程上下文）
                   └─⑤ 反射调用 org.eclipse.jetty.xml.XmlConfiguration.main(XML 清单)   ← 第四章
                       └─⑥ 逐个执行 XML 组装出 Server → LifeCycle.start()
```

## 3.2 入口与 jetty.home/jetty.base 分离

【源码证据】`jetty-core/jetty-start/src/main/java/org/eclipse/jetty/start/Main.java:74-93`

```java
public static void main(String[] args)
{
    Main main = new Main();
    StartArgs startArgs = main.processCommandLine(args);
    main.start(startArgs);
}
```

`processCommandLine()`（`Main.java:384-462`）的注释原文写着 "Processing Order is important!"，其顺序是：

```java
CommandLineConfigSource cmdLineSource = new CommandLineConfigSource(cmdLine);
baseHome = new BaseHome(cmdLineSource);      // ① 定位 home/base
StartArgs args = new StartArgs(baseHome);
Modules modules = new Modules(baseHome, args);
modules.registerAll();                        // ② 注册所有 *.mod
args.parse(baseHome.getConfigSources());      // ③ 合并：命令行 > start.d/*.ini > start.ini > 模块默认
// ... 拓扑排序启用模块、逐个 enable（④⑤，见 3.4）
```

**jetty.home 与 jetty.base 的分离**（`BaseHome`）是独立部署的第一个设计亮点：

- `jetty.home`：发行版目录（`start.jar`、`modules/`、`lib/`、`etc/`），**只读**；
- `jetty.base`：用户实例目录（`start.d/*.ini`、`webapps/`、`logs/`、`resources/`），**可写**。

这样同一个发行版可以被多个实例共享（`java -jar /opt/jetty-home/start.jar jetty.base=/var/myapp`），升级发行版不碰用户数据。命令行上还可以直接给属性（`jetty.http.port=9090`）或 XML 路径，最终都被 `ConfigSources` 按"命令行 > base > home"的优先级合并成一份 `Props`。

## 3.3 .mod 文件：模块系统的元数据

模块清单在发行版 `modules/` 下，每个 `.mod` 是一个自描述文件。看两个最核心的（源码路径 `jetty-core/jetty-server/src/main/config/modules/`）：

**server.mod**（`server.mod:11-25`）：

```
[depend]
threadpool
scheduler
bytebufferpool
http-config

[lib]
lib/jetty-http-${jetty.version}.jar
lib/jetty-server-${jetty.version}.jar
lib/jetty-xml-${jetty.version}.jar
lib/jetty-util-${jetty.version}.jar
lib/jetty-io-${jetty.version}.jar

[xml]
etc/jetty.xml
```

**http.mod**（`http.mod:9-13`）：

```
[depend]
server

[xml]
etc/jetty-http.xml
```

全部段落（解析 switch 见 `jetty-core/jetty-start/src/main/java/org/eclipse/jetty/start/Module.java:399-499`）：

| 段 | 作用 | 装配语义 |
|---|---|---|
| `[description]`/`[tags]` | 文档与 `--list-modules` 分组 | 无 |
| `[depend]` | 硬依赖（启用我必须启用它） | 进拓扑图，**依赖的 XML 先执行** |
| `[before]`/`[after]`/`[optional]` | 软排序（如 `ee11-deploy.mod` 声明 before ee10-deploy） | 进拓扑图 |
| `[provides]` | 我提供的"能力名"（供他人 depend，如 threadpool 模块 provides "threadpool"） | 依赖别名 |
| `[lib]` | 该模块带来的 jar（支持 `${jetty.version}` 占位符） | 汇入 classpath |
| `[xml]` | 该模块的装配脚本 | **汇入 XML 执行清单** |
| `[ini]` | 默认属性（`k?=v` 形式，不覆盖用户值） | 汇入 Props |
| `[ini-template]` | `--add-to-start` 时写入 start.d/xxx.ini 的注释模板 | 生成用户配置 |
| `[files]` | 需要存在的文件/目录（缺了可下载，如 maven 坐标） | 启动前校验 |
| `[license]` | 启用前需同意的许可 | 交互确认 |
| `[exec]` | 需要特殊 JVM 参数（如 --add-opens），带它时 start.jar fork 子 JVM | 触发 exec 模式 |
| `[environment]` | 声明所属 EE 环境（ee11-deploy.mod: `[environment] ee11`） | 多环境隔离 |

【白话】这就是 Jetty 版的"Maven + 自动装配"：**依赖关系靠声明，类路径靠汇总，功能开关靠启用集合**。和 Spring Boot 的对比放在 7.3 节——你会发现 `.mod` 之于独立 Jetty，约等于 `starter + @ConditionalOnClass` 之于 Boot，但一个是文件系统驱动、一个是 classpath 驱动。

## 3.4 拓扑排序：XML 执行顺序是怎么来的

【源码证据】`jetty-core/jetty-start/src/main/java/org/eclipse/jetty/start/Modules.java:323-352`

```java
TopologicalSort<Module> sort = new TopologicalSort<>();
for (Module module : enabled)
{
    for (String dependency : module.getDepends()) { sort.addDependency(module, ...); }
    // ... [before] 反向加边、[provides] 展开别名
}
sort.sort(enabled);   // 就地排序：被依赖者排前
```

启用集合的来源有三处：命令行 `--module=http,ee11-deploy`、`start.d/*.ini`（每行 `--module=xxx`）、模块级传递依赖（`Modules.enable()` 递归展开 `[depend]`）。排序后 `StartArgs.getJettyEnvironment()` 产出两样东西：

1. **classpath**：按启用顺序汇总各模块 `[lib]`（去重）；
2. **activeXmls**：按拓扑序汇总各模块 `[xml]`。

想亲眼看这两个产物，不需要读源码，发行版自带自省命令（`Main.start()` 里逐一分发，`Main.java:479-524`）：

```
java -jar start.jar --list-modules     # 所有模块及启用状态
java -jar start.jar --list-classpath   # 最终 classpath
java -jar start.jar --list-config      # 属性 + classpath + XML 清单全 dump
java -jar start.jar --dry-run          # 打印等价的完整 java 命令行
```

## 3.5 关键实证：start.jar 的"主类"其实是 XmlConfiguration

这是独立启动链最反直觉、也最能说明"壳与内核分离"的一处。`StartArgs.java:103`：

```java
private static final String MAIN_CLASS = "org.eclipse.jetty.xml.XmlConfiguration";
```

`Main.start()` 收尾（`Main.java:597-609`）：

```java
ClassLoader cl = classpath.getClassLoader();
Thread.currentThread().setContextClassLoader(cl);   // 模块系统拼出的 classpath 成为应用类路径
invokeMain(cl, args);                               // 反射调用 XmlConfiguration.main(……)
```

`invokeMain()`（`Main.java:272-313`）通过反射找到 `main(String[])` 并调用，参数就是 XML 清单与属性。**start.jar 到此退出历史舞台**——它不持有任何 Server 引用，不调用任何 start 方法；服务器能否启动，全看 XML 脚本怎么写。对比 Boot：`ServletWebServerApplicationContext.onRefresh()` 里 `factory.getWebServer(...)` 是**编译期类型引用**，两种壳在这里完成分岔。

顺带两个运维细节：

- **`--exec` 分叉**：任一启用模块带 `[exec]` 段（需要 JVM 参数）时，`Main.start()` 用 `ProcessBuilder` fork 一个新 JVM（`Main.java:558-586`），因为 start.jar 自身 JVM 的参数已不可改。
- **STOP.PORT 停止协议**：`Main.stop()`（`:663-731`）向 `STOP.PORT` 发送 `key + "\r\nstop\r\n"` 文本指令让运行中的实例优雅退出——独立部署没有 Spring 时的另一种停机通道（对应 server 端的 ShutdownMonitor/ShutdownService，`Server.doStart` 会注册，见 2.4）。

## 3.6 本章小结

- start.jar = 配置解析器 + 模块图求解器 + ClassLoader 组装器 + 主类反射启动器，四件事之外无他。
- `.mod` 文件以声明式元数据描述"依赖、类库、XML、默认属性"，拓扑排序决定 XML 顺序。
- `jetty.home`（只读）与 `jetty.base`（实例）分离支持多实例共享发行版。
- **最终主类是 `XmlConfiguration`**：装配知识全在 XML 数据里，start.jar 不含一行服务器逻辑。

---

# 四、独立启动线（下）：XmlConfiguration 组装 Server

## 4.1 模块定位：一份"解释执行的装配脚本"

`jetty-core/jetty-xml` 的 `XmlConfiguration` 是一个 **SAX 解析器 + 反射求值器**：读一份 XML，按标签语义 new 对象、调 setter、执行方法。它对标 Spring 的 XML 时代（`spring-beans` 的 `<bean>` 解析），但定位不同——**Spring XML 描述的是"bean 定义"交给容器管理，Jetty XML 是一段"命令式脚本"直接执行**：每个 `<New>` 都立即构造、每个 `<Set>` 都立即赋值，没有延迟、没有代理、没有依赖注入。

## 4.2 DSL 全集（源码实证）

【源码证据】`jetty-core/jetty-xml/src/main/java/org/eclipse/jetty/xml/XmlConfiguration.java:580-616`（`configure` 递归例程的 switch，即标签全集）：

```java
case "Set": ...          // obj.setXxx(值)：调 setter
case "Put": ...          // 往 Map/集合 put
case "Call": ...         // 调任意方法（可链式嵌套）
case "Get": ...          // 调 getter 拿引用
case "New": ...          // 构造新对象（<Arg> 传构造参数）
case "Array": ...        // 构造数组
case "Map"/"Entry": ...  // 构造 Map
case "Ref": ...          // 按 id 取已有对象
case "Property": ...     // 取属性值（可 default、可联动 Deprecated）
case "SystemProperty": ... // 读系统属性
case "Env": ...          // 读环境变量
```

根节点是 `<Configure class="...">`（或纯 `<Configure>` 只挂属性）。求值入口 `JettyXmlConfiguration.configure()`（`:512-546`）的三步：

```java
String id = aoeNode.getString("Id");
Object obj = id == null ? null : _configuration.getIdMap().get(id);   // ① id 已存在则复用
if (obj == null && oClass != null)
{
    obj = construct(oClass, new Args(null, oClass, aoeNode.getNodes("Arg")));  // ② 否则反射构造
    if (id != null) _configuration.getIdMap().put(id, obj);
}
_configuration.initializeDefaults(obj);
configure(obj, _root, aoeNode.getNext());   // ③ 递归执行子标签
```

`<Configure id="Server">` 的 id 存进 **idMap**——跨文件共享对象图的唯一通道（4.4 节展开）。

## 4.3 默认 XML 链：从 threadPool 到 ServerConnector

启用 `server` + `http` 模块后，拓扑排序产出（近似）这样的执行序列——**XML 顺序由 3.4 的模块依赖图决定，文件的先后就是对象图的搭建顺序**：

**① etc/jetty-threadpool.xml**（`jetty-core/jetty-server/src/main/config/etc/jetty-threadpool.xml:22-31`）——先造三件套之线程池：

```xml
<Configure>
  <New id="threadPool" class="org.eclipse.jetty.util.thread.QueuedThreadPool">
    <Set name="minThreads" type="int"><Property name="jetty.threadPool.minThreads" deprecated="threads.min" default="10"/></Set>
    <Set name="maxThreads" type="int"><Property name="jetty.threadPool.maxThreads" deprecated="threads.max" default="200"/></Set>
    <Set name="useVirtualThreads" property="jetty.threadPool.useVirtualThreads" />
  </New>
</Configure>
```

**② etc/jetty-http-config.xml**——造 HTTP 参数集（`:7` `<Configure id="httpConfig" class="org.eclipse.jetty.server.HttpConfiguration">`，请求/响应头大小、缓冲等，被连接器引用）。

**③ etc/jetty.xml**（`jetty-core/jetty-server/src/main/config/etc/jetty.xml:11-36`）——组装 Server 本体：

```xml
<Configure id="Server" class="org.eclipse.jetty.server.Server">
  <Arg><Ref refid="threadPool"/></Arg>       <!-- 手工依赖注入！ -->
  <Arg><Ref refid="scheduler"/></Arg>
  <Arg><Ref refid="byteBufferPool"/></Arg>
  <Set name="handler">
    <New id="Contexts" class="org.eclipse.jetty.server.handler.ContextHandlerCollection"/>
  </Set>
  <Call name="addBean"><Arg><Ref refid="ShutdownService"/></Arg></Call>
  <Set name="stopAtShutdown"><Property name="jetty.server.stopAtShutdown" default="true"/></Set>
</Configure>
```

【要点】`<Arg><Ref refid="threadPool"/></Arg>` 这一行就是 2.8 节说的"Jetty 没有容器，手工传引用"：XML 通过 idMap 完成了本该依赖注入做的事。`ContextHandlerCollection` 挂上后，后面部署的任何 WebAppContext 都会进入这棵树（2.5 节）。

**④ etc/jetty-http.xml**（`jetty-core/jetty-server/src/main/config/etc/jetty-http.xml:21-48`）——加连接器：

```xml
<Call name="addConnector">
  <Arg>
    <New id="httpConnector" class="org.eclipse.jetty.server.ServerConnector">
      <Arg name="server"><Ref refid="Server" /></Arg>
      <Arg name="acceptors" type="int"><Property name="jetty.http.acceptors" default="1"/></Arg>
      <Arg name="selectors" type="int"><Property name="jetty.http.selectors" default="-1"/></Arg>
      <Arg name="factories">
        <Array type="org.eclipse.jetty.server.ConnectionFactory">
          <Item><New class="org.eclipse.jetty.server.HttpConnectionFactory">
            <Arg name="config"><Ref refid="httpConfig" /></Arg>
          </New></Item>
        </Array>
      </Arg>
      <Set name="port"><Property name="jetty.http.port" default="8080" /></Set>
      ...
    </New>
  </Arg>
</Call>
```

注意它 `<Configure id="Server">` 复用同一个 id——**多个 XML 共同配置同一个 Server 实例**（文件头注释原话："Other configuration files may also configure the Server ID"）。想加 HTTPS 就是再启用 `https` 模块，追加一份 jetty-https.xml（SslConnectionFactory 进 factories 数组）。

## 4.4 启动：遍历对象，统一 start

【源码证据】`jetty-core/jetty-xml/src/main/java/org/eclipse/jetty/xml/XmlConfiguration.java:2092-2149`（`main()` 内）

```java
Object obj = configuration.configure();
if (obj != null && !objects.contains(obj)) objects.add(obj);
// ... 全部 XML 执行完后：
// For all objects created by XmlConfigurations, start them if they are lifecycles.
List<LifeCycle> started = new ArrayList<>(objects.size());
for (Object obj : objects)
{
    if (obj instanceof LifeCycle lifeCycle)
    {
        if (!lifeCycle.isRunning())
        {
            lifeCycle.start();
            if (!lifeCycle.isStarted())
            {
                // Failed to start a component, so stop all started components
                Collections.reverse(started);
                for (LifeCycle slc : started) slc.stop();
                break;
            }
            started.add(lifeCycle);
        }
    }
}
```

【白话】XML 逐个跑完后，main 遍历**这轮脚本产生的所有对象**，凡是 LifeCycle 就 start。由于 `Server.doStart()` 会经 ContainerLifeCycle 传播给全部子组件（线程池、连接器、Handler 树），所以**实际只需要 start 到 Server 一层，整棵树就起来了**；其余零散对象（如 threadPool 若不被 Server 引用）各自单独 start。启动失败的回滚与 2.3 的容器级回滚形成两级兜底。

**独立启动的完整时序**（汇总第三章）：

```
① java -jar start.jar [--module=...]      Main.main
② 解析 ini/mod → 拓扑排序 → classpath + XML 清单
③ 反射调 XmlConfiguration.main(XML 清单)
④ 逐份 XML：new threadPool → new httpConfig → new Server(Ref…) → addConnector
⑤ for(objects) lifeCycle.start()
    └ Server.doStart → ShutdownThread 注册 → Connector.doStart → open() 监听 8080
    └ ContextHandlerCollection.doStart（若 ee11-deploy 已部署 webapp，各 WebAppContext 就绪）
⑥ 日志 "Started oejs.Server@..." —— 开始服务
```

## 4.5 独立部署 war：DeploymentScanner → WebAppContext

### 部署器链

启用 `ee11-deploy` 模块后（`jetty-ee11/jetty-ee11-webapp/src/main/config/modules/ee11-deploy.mod`，`[depend] deployment-scanner, ee11-webapp`），`jetty-deployment-scanner.xml` 往 Server addBean 一个 `DeploymentScanner`（源码 `jetty-core/jetty-deploy/src/main/config/etc/jetty-deployment-scanner.xml:18-40`）：

```xml
<New id="deploymentScanner" class="org.eclipse.jetty.deploy.DeploymentScanner">
  <Arg name="server"><Ref refid="Server"/></Arg>
  <Arg name="deployer"><Ref refid="Deployer"/></Arg>
  <Set name="webappsDirectories">
    <Call name="csvSplitAndResolvePaths" class="org.eclipse.jetty.xml.XmlConfiguration">
      <Arg><Property name="jetty.base" /></Arg>
      <Arg><Property name="jetty.deploy.webappsDir" default="webapps" /></Arg>
    </Call>
  </Set>
  <Set name="scanInterval" property="jetty.deploy.scanInterval" />
</New>
```

`DeploymentScanner`（`jetty-core/jetty-deploy/.../DeploymentScanner.java:126`，实现 `Scanner.BulkListener`）按 `scanInterval` 秒扫 `webapps/` 目录，发现 `*.war` / `*.xml` / 目录后走 `StandardDeployer`（`StandardDeployer.java:42`：部署即 `ContextHandlerCollection.deployHandler(...)` 并 start）——动态挂进 2.4 节那棵 Handler 树，**这就是 Jetty 热部署的全部原理**：Handler 树支持运行时增删（ContextHandlerCollection 的 dynamic 特性，jetty.xml `:24`）。

而环境级配置 `jetty-ee11-deploy.xml`（`jetty-ee11/jetty-ee11-webapp/src/main/config/etc/jetty-ee11-deploy.xml:6-33`）告诉扫描器：ee11 环境的默认 Handler 类是 `WebAppContext`、默认描述符是 `webdefault-ee11.xml`、`parentLoaderPriority` 怎么取——扫描器据此 new 出 `WebAppContext`。

### WebAppContext：Servlet 时代的"应用"

【源码证据】`jetty-ee11/jetty-ee11-webapp/src/main/java/org/eclipse/jetty/ee11/webapp/WebAppContext.java:87-96`

```java
public class WebAppContext extends ServletContextHandler implements WebAppClassLoader.Context, Deployable
{
    public static final String WEB_DEFAULTS_XML = "org/eclipse/jetty/ee11/webapp/webdefault-ee11.xml";
```

它是 `ServletContextHandler`（Servlet 上下文 + SessionHandler/ServletHandler/SecurityHandler 三件套的组装者）的子类，额外加了：文档根目录（WAR/目录）、**独立类加载器**、**Configuration 流水线**。父类的 `startContext()`（`jetty-ee11/jetty-ee11-servlet/.../ServletContextHandler.java:1320-1350`）揭示了 Servlet 侧的启动顺序：

```java
protected void startContext() throws Exception
{
    for (ServletContainerInitializerCaller sci : getBeans(ServletContainerInitializerCaller.class))
    { if (sci.isStopped()) sci.start(); }        // ① SCI 钩子（注解/SCI 扫描在这里触发）
    if (_servletHandler != null)
    { for (ListenerHolder holder : _servletHandler.getListeners()) holder.start(); }  // ② 监听器实例化
    super.doStart();                              // ③ 整个上下文树启动（Session 等）
    if (_servletHandler != null)
        _servletHandler.initialize();             // ④ Servlet/Filter 的 init（最后一步）
}
```

`ServletHandler.initialize()`（`ServletHandler.java:223` 一带）把注册的 `ServletHolder` 按其 init-on-startup 顺序逐个实例化并调用 `Servlet.init()`——Servlet 规范的容器职责到此完成，之后的请求链路见第五章。

### Configuration 流水线：WebAppContext 的"启动钩子数组"

Servlet 规范要处理 web.xml、web-fragment.xml、`@WebServlet` 注解、ServletContainerInitializer、JNDI……Jetty 把这些切成一个个 `Configuration`（接口方法：preConfigure/configure/postConfigure/deconfigure/destroy），`WebAppContext.start` 前后按序调用。

实现发现机制是 **ServiceLoader**（`jetty-ee11/jetty-ee11-webapp/.../Configurations.java:70-107`）：

```java
TypeUtil.serviceProviderStream(ServiceLoader.load(Configuration.class)).forEach(provider ->
{
    Configuration configuration = provider.get();
    if (!configuration.isAvailable()) { __unavailable.add(configuration); return; }  // 依赖缺失则跳过
    __known.add(configuration);
});
sort(__known);   // 按 @Order/类型权重排序
```

`jetty-ee11-webapp` 自带的 services 文件注册了 12 个（`META-INF/services/org.eclipse.jetty.ee11.webapp.Configuration`：WebInfConfiguration、WebXmlConfiguration、MetaInfConfiguration、FragmentConfiguration、WebAppConfiguration、ServletsConfiguration、JettyWebXmlConfiguration、JspConfiguration、JndiConfiguration……），注解模块 `jetty-ee11-annotations` 再追加 `AnnotationConfiguration`（`@WebServlet`/SCI 的 classpath 扫描）。**这个"按 classpath 自动发现 + 依赖缺失自动跳过"的设计，与 Spring Boot 的 @ConditionalOnClass 思想同源**（7.3 节对比）。

### WebAppClassLoader：多 webapp 类隔离

【源码证据】`jetty-core/jetty-ee/jetty-ee-webapp/src/main/java/org/eclipse/jetty/ee/webapp/WebAppClassLoader.java:448-494`

```java
protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException
{
    webappClass = findLoadedClass(name);
    if (webappClass != null) return webappClass;
    if (_context.isParentLoaderPriority())     // 模式 A：父优先（类库服务器风格）
    {
        try { parentClass = _parent.loadClass(name); ... return parentClass; } catch (ClassNotFoundException e) { ex = e; }
        webappClass = this.findClass(name); ... return webappClass;
    }
    else                                       // 模式 B：子优先（默认，webapp 风格）
    {
        webappClass = loadAsResource(name, true);
        if (webappClass != null) return webappClass;
        parentClass = _parent.loadClass(name); ...
    }
    // 中间还穿插 isHiddenClass / isSystemClass 过滤：
    // 服务器自身类（org.eclipse.jetty.* 默认）对 webapp 隐藏，防止 webapp 覆盖容器
}
```

默认 child-first（`parentLoaderPriority=false`，可通过 `jetty.deploy.parentLoaderPriority` 打开），配合"隐藏类/系统类"正则（`SERVER_SYS_CLASSES`/`SERVER_SRV_CLASSES`，`WebAppContext.java:96-101`）实现多应用隔离——**第六章会看到 Boot 内嵌时这套机制整个被绕开**，这是两条启动线差异最大的一处。

## 4.6 本章小结

- XmlConfiguration 是"解释执行的装配脚本"：`<New>/<Set>/<Call>/<Ref>/<Property>` 十余个标签 + idMap 跨文件传引用，替代了不存在的 IoC 容器。
- 默认 XML 链 = 模块依赖图的拓扑序：threadpool → http-config → jetty.xml(Server) → jetty-http.xml(addConnector)；全部执行完后统一 `LifeCycle.start()`。
- webapps 热部署 = DeploymentScanner 扫目录 → new WebAppContext → deployHandler 进 ContextHandlerCollection；WebAppContext 内部由 Configuration 流水线（ServiceLoader 发现）完成 web.xml/注解/SCI 处理。
- 多 webapp 隔离靠 WebAppClassLoader（child-first + 隐藏类过滤）。

---

# 五、请求链路速写：从 accept 到 Servlet.service()

本章不展开（展开版在《Spring MVC.md》第四章），只立骨架并给出 12.x 的入口坐标，供两条启动线共用：

```
ServerConnector.acceptor 线程 accept()
  → SocketChannel 注册到某个 ManagedSelector（SelectorManager 分发，jetty-io）
  → 读就绪：EndPoint 读字节 → HttpConnection（jetty-http）解析请求头
  → 构造 core 的 Request/Response（Content.Source/Sink 抽象）
  → HttpChannel 调 Server.handle(request, response, callback)      ← Handler 树入口
      Server(Wrapper) → ContextHandlerCollection → WebAppContext(ContextHandler)
        → Sequence/SecurityHandler/SessionHandler → ServletHandler
      → ServletHandler.getFilterChain(...) → FilterChain.doFilter → Servlet.service()
  → Servlet 侧（DispatcherServlet 等，见《Spring MVC.md》）写响应
  → Response 写出经 Content.Sink 异步回 socket，Callback 完成后连接回收
```

【要点】**Handler 树在 core 层是"无 Servlet 语义"的**（`Request`/`Response` 是 Jetty 自己的接口，不是 `HttpServletRequest`）；`ServletContextHandler` 节点负责在进入自己子树时把 core 对象适配成 Servlet API 对象（`ServletContextApi`/`ServletApiRequest`）。所以：

- 用 **jetty-core + 自定义 Handler** 可以完全绕开 Servlet（Boot 的响应式栈未来可能走这条线，见 6.7）；
- 独立启动与 Boot 内嵌**从 accept 到 Handler 树入口的这段完全一致**，分岔只在树的内容（webapps 部署 vs 单一 JettyEmbeddedWebAppContext）。

---

# 六、Spring Boot 嵌入式启动线：ServletWebServerApplicationContext 里的 Jetty

## 6.1 模块定位：Boot 4 的容器集成版图

Boot 4.0 把"嵌入式服务器"从 `spring-boot-autoconfigure` 里拆成了独立模块（`module/` 目录，工作树 `D:\tmp\sb408` 实测）：

| 模块 | 职责 |
|---|---|
| `spring-boot-web-server` | 抽象层：`WebServer` 接口、`ServletWebServerApplicationContext`、`ServerProperties`、生命周期 SmartLifecycle |
| `spring-boot-servlet` | Servlet 应用支撑（Filter 排序、multipart、编码） |
| **`spring-boot-jetty`** | **Jetty 实现**：工厂类 + 自动装配 + 属性（本文主角） |
| `spring-boot-tomcat` | Tomcat 实现（同构） |

`spring-boot-jetty` 的依赖声明（`module/spring-boot-jetty/build.gradle:26-42`）：

```gradle
api(project(":module:spring-boot-web-server"))
api("org.eclipse.jetty.ee11:jetty-ee11-webapp")     // ← 唯一的 Jetty 坐标，传递带入全链
implementation("org.eclipse.jetty.compression:jetty-compression-server")   // 压缩（Jetty 12 新模块）
// optional: http2、websocket-jakarta、jasper、conscrypt……
```

【要点】Boot 4 对 Jetty 的依赖面就是 **`jetty-ee11-webapp`**——第四章那个"独立部署 war 的完整栈"被整体搬进了 Boot，但用法天差地别，往下看。

## 6.2 自动装配：条件探测与定制器链

【源码证据】`module/spring-boot-jetty/src/main/java/org/springframework/boot/jetty/autoconfigure/servlet/JettyServletWebServerAutoConfiguration.java:60-102`

```java
@AutoConfiguration
@ConditionalOnClass({ ServletRequest.class, Server.class, Loader.class, WebAppContext.class })
@ConditionalOnWebApplication(type = Type.SERVLET)
@EnableConfigurationProperties(JettyServerProperties.class)
@Import({ JettyWebServerConfiguration.class, ServletWebServerConfiguration.class })
public final class JettyServletWebServerAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean(value = ServletWebServerFactory.class, search = SearchStrategy.CURRENT)
	JettyServletWebServerFactory jettyServletWebServerFactory(ObjectProvider<JettyServerCustomizer> serverCustomizers) {
		JettyServletWebServerFactory factory = new JettyServletWebServerFactory();
		factory.getServerCustomizers().addAll(serverCustomizers.orderedStream().toList());
		return factory;
	}
	// ... 内部类 JettyWebSocketConfiguration：注册 WebSocket 相关定制器
}
```

四个 `@ConditionalOnClass` 正是 3.3 节说的"`.mod` 的 classpath 驱动版"：ServletRequest（Servlet API 在）+ Server（jetty-server 在）+ WebAppContext（ee11-webapp 在）→ 认为"用户想用 Jetty"，注册工厂。用户自己定义了 `ServletWebServerFactory` bean 则完全让位（`@ConditionalOnMissingBean`）。

属性→Jetty 的映射由定制器链完成（`JettyWebServerConfiguration.java:37-59` 注册，经 `WebServerFactoryCustomizerBeanPostProcessor` 在工厂使用前按 `@Order` 回调）：

| 顺序 | 定制器 | 职责 |
|---|---|---|
| -1（web-server 模块） | `ServletWebServerFactoryCustomizer` | 通用 `server.*`：port/address/contextPath/session/mime/http2/compression/ssl-bundles |
| 0 | `JettyWebServerFactoryCustomizer`（`JettyWebServerFactoryCustomizer.java:60,81-110`） | `server.jetty.*`：线程池（`JettyThreadPool.create`）、acceptors/selectors、form 大小、idleTimeout、accesslog |
| 1 | `JettyVirtualThreadsWebServerFactoryCustomizer`（`@ConditionalOnThreading(VIRTUAL)`） | `spring.threads.virtual.enabled=true` 时把线程池换成 `VirtualThreadPool`（`:62-69`） |
| LOWEST | WebSocket 定制器 | 注册 WebSocketUpgradeFilter |
| 用户 | 任何 `JettyServerCustomizer` / `WebServerFactoryCustomizer<JettyServletWebServerFactory>` | 终极后门 |

线程池的属性映射（`JettyThreadPool.java:41-59`）：

```java
static QueuedThreadPool create(JettyServerProperties.Threads properties) {
	BlockingQueue<Runnable> queue = determineBlockingQueue(properties.getMaxQueueCapacity());
	int maxThreadCount = (properties.getMax() > 0) ? properties.getMax() : 200;
	int minThreadCount = (properties.getMin() > 0) ? properties.getMin() : 8;
	int threadIdleTimeout = (properties.getIdleTimeout() != null) ? (int) properties.getIdleTimeout().toMillis() : 60000;
	return new QueuedThreadPool(maxThreadCount, minThreadCount, threadIdleTimeout, queue);
}
```

对照 2.6：独立版 XML 默认 10/200，Boot 属性默认 8/200/60s（`JettyServerProperties.java:291-308`）——同一个类，两套默认值来源。

## 6.3 工厂方法：一屏代码拼出 Server

自动装配的终点是 `getWebServer(initializers)`。它由 `ServletWebServerApplicationContext` 调用（6.5 节），先看它怎么拼对象图——**这是第四章 200 行 XML 的 Java 代码等价物**：

【源码证据】`module/spring-boot-jetty/src/main/java/org/springframework/boot/jetty/servlet/JettyServletWebServerFactory.java:156-185`

```java
@Override
public WebServer getWebServer(ServletContextInitializer... initializers) {
	JettyEmbeddedWebAppContext context = new JettyEmbeddedWebAppContext();      // ① 应用容器
	int port = Math.max(getPort(), 0);
	InetSocketAddress address = new InetSocketAddress(getAddress(), port);
	Server server = createServer(address);                                       // ② Server + Connector
	context.setServer(server);
	configureWebAppContext(context, initializers);                               // ③ 装配应用
	server.setHandler(addHandlerWrappers(context));                              // ④ 压缩/Server 头包装
	logger.info("Server initialized with port: " + port);
	if (this.getMaxConnections() > -1) {
		server.addBean(new NetworkConnectionLimit(this.getMaxConnections(), server.getConnectors()));
	}
	if (Ssl.isEnabled(getSsl())) { customizeSsl(server, address); }              // ⑤ SSL（SslBundle）
	for (JettyServerCustomizer customizer : getServerCustomizers()) {
		customizer.customize(server);                                            // ⑥ 定制器回调
	}
	if (getShutdown() == Shutdown.GRACEFUL) {                                    // ⑦ 优雅停机探针
		StatisticsHandler statisticsHandler = new StatisticsHandler();
		statisticsHandler.setHandler(server.getHandler());
		server.setHandler(statisticsHandler);
	}
	return getJettyWebServer(server);
}
```

对照 4.3 的 XML：`new Server(...)`（`createServer` 里 `new Server(getThreadPool())`，`JettyServletWebServerFactory.java:187-196`）↔ `<New id="Server">`；`server.setHandler(...)` ↔ `<Set name="handler">`；定制器循环 ↔ 追加的 XML 文件。**结构完全同构，只是装配语言从 XML 换成了 Java，装配者从 start.jar 换成了 Spring 容器**。

连接器的组装（基类 `JettyWebServerFactory.java:183-202`）：

```java
protected AbstractConnector createConnector(InetSocketAddress address, Server server) {
	HttpConfiguration httpConfiguration = new HttpConfiguration();
	httpConfiguration.setSendServerVersion(false);                       // 不暴露 Server 头
	List<ConnectionFactory> connectionFactories = new ArrayList<>();
	connectionFactories.add(new HttpConnectionFactory(httpConfiguration));
	if (getHttp2() != null && getHttp2().isEnabled()) {
		connectionFactories.add(new HTTP2CServerConnectionFactory(httpConfiguration));  // 明文 HTTP/2
	}
	ServerConnector connector = new ServerConnector(server, executor, scheduler, pool,
			this.getAcceptors(), this.getSelectors(), connectionFactories.toArray(new ConnectionFactory[0]));
	connector.setHost(address.getHostString());
	connector.setPort(address.getPort());
	return connector;
}
```

与 4.3 ④ 的 jetty-http.xml 逐行对应（acceptors/selectors 的默认值语义也一致：-1 → 1 个 acceptor、核数一半的 selector）。

### 应用容器的装配细节

`configureWebAppContext`（`JettyServletWebServerFactory.java:218-243`）值得逐行读：

```java
protected final void configureWebAppContext(WebAppContext context, ServletContextInitializer... initializers) {
	context.clearAliasChecks();
	if (this.resourceLoader != null) {
		context.setClassLoader(this.resourceLoader.getClassLoader());   // ← 关键①：用应用 ClassLoader
	}
	String contextPath = getSettings().getContextPath().toString();
	context.setContextPath(StringUtils.hasLength(contextPath) ? contextPath : "/");
	configureDocumentRoot(context);                                      // docroot（fat-jar 时建临时目录）
	if (getSettings().isRegisterDefaultServlet()) { addDefaultServlet(context); }  // 静态资源
	if (shouldRegisterJspServlet()) { addJspServlet(context); }
	addLocaleMappings(context);
	ServletContextInitializers initializersToUse = ServletContextInitializers.from(this.settings, initializers);
	Configuration[] configurations = getWebAppContextConfigurations(context, initializersToUse);  // ← 关键②
	context.setConfigurations(configurations);
	context.setThrowUnavailableOnStartupException(true);                 // 启动失败直接抛（fail-fast）
	configureSession(context);
	context.setTempDirectory(getTempDirectory(context));
	postProcessWebAppContext(context);
}
```

关键②的 Configuration 数组（`getWebAppContextConfigurations`，`:373-382`）是理解"Boot 内嵌 Jetty 少了什么"的钥匙：

```java
Configuration[] configurations = new Configuration[] {
    getServletContextInitializerConfiguration(initializersToUse),  // ServletContextInitializerConfiguration
    getErrorPageConfiguration(),                                   // 错误页 → /error
    getMimeTypeConfiguration(),                                    // MimeMappings
    getJspConfiguration()（如适用）,
    ...this.configurations.toArray(new Configuration[0]),          // 用户追加
    new WebListenersConfiguration(this.webListeners),              // 仅 @WebListener
};
```

**对照 4.5 节独立版的 12+ 个 Configuration**：web.xml（WebXmlConfiguration）、web-fragment（FragmentConfiguration）、jar 内 META-INF 扫描（MetaInfConfiguration）、`@WebServlet/@WebFilter` 注解扫描（AnnotationConfiguration）、JNDI（JndiConfiguration）……**全部被剔除**。Boot 的世界观里：类路径就是应用本身，没有"部署多个应用"和"运行期发现"的问题——Servlet 组件一律走代码注册（6.4）。

还有一处隐蔽的类加载处理（`JettyEmbeddedWebAppContext.java:31-64`，详见 6.5）：

```java
class JettyEmbeddedWebAppContext extends WebAppContext {
	JettyEmbeddedWebAppContext() {
		setHiddenClassMatcher(new ClassMatcher("org.springframework.boot.loader."));   // 隐藏 fat-jar 加载器类
	}
	@Override
	protected ServletHandler newServletHandler() { return new JettyEmbeddedServletHandler(); }  // 延迟初始化
}
```

## 6.4 ServletContextInitializer 桥：Boot 的 Servlet 组件如何进 Jetty

【白话】独立部署时 Servlet 组件来自 web.xml/注解扫描；Boot 里它们是普通 bean（`DispatcherServletRegistrationBean`、`FilterRegistrationBean`……）。桥接分两跳：

**第一跳（Boot 侧）**：`ServletContextInitializerBeans`（`core/spring-boot/src/main/java/org/springframework/boot/web/servlet/ServletContextInitializerBeans.java:91-136`）把容器里所有"能变成 Servlet 组件注册"的 bean 收集成 `ServletContextInitializer` 列表——`ServletRegistrationBean`/`FilterRegistrationBean`/`ServletListenerRegistrationBean` 是一等公民，`Servlet`/`Filter`/`EventListener` 裸 bean 有适配器兜底，`@WebServlet` 等注解由 `WebServletHandler`/`WebFilterHandler`（spring-boot-web-server 模块）以 SCI 语义处理。

**第二跳（Jetty 侧）**：Boot 写了一个"伪装成 Jetty Configuration 的类"，在 WebAppContext 的 configure 阶段回调所有 initializer：

【源码证据】`module/spring-boot-jetty/src/main/java/org/springframework/boot/jetty/servlet/ServletContextInitializerConfiguration.java:48-70`

```java
@Override
public void configure(WebAppContext context) throws Exception {
	ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
	Thread.currentThread().setContextClassLoader(context.getClassLoader());   // 注册期间 TCCL=应用类加载器
	try {
		callInitializers(context);
	}
	finally {
		Thread.currentThread().setContextClassLoader(classLoader);
	}
}

private void callInitializers(WebAppContext context) throws ServletException {
	context.getContext().setExtendedListenerTypes(true);    // 放开容器监听器类型限制（Servlet 规范 8.1.4）
	for (ServletContextInitializer initializer : this.initializers) {
		initializer.onStartup(context.getServletContext());   // ← DispatcherServlet 就在这注册
	}
	// finally: setExtendedListenerTypes(false)
}
```

【要点】这个类是两条启动线在 Servlet 装配层的**会合点**：它复用了 Jetty `Configuration` 流水线的钩子位（4.5 节），但把钩子里干的事从"解析 web.xml"换成了"回调 Spring bean"。`DispatcherServlet`、`springSecurityFilterChain`（DelegatingFilterProxy）等由此进入 `ServletContextHandler` 的 ServletHandler。

## 6.5 两阶段启动：Boot 何时打开 Jetty 的端口

这是两条启动线**行为差异最大**的地方，值得整段读。

### 背景矛盾

`DispatcherServlet` 等 MVC 组件是 Spring bean，必须在容器 refresh 的**后半程**才能注册；而 `WebAppContext.configure()`（含 6.4 的 initializer 回调）发生在 `WebAppContext.start()` 期间——即 Jetty 的启动期。如果等 refresh 完成才 new Server，ServletContext 就不存在、initializer 无处注册。Boot 的解法：**onRefresh 阶段就把 Server 起起来（ServletContext 可用、组件注册完成），但把"接收流量"推迟到 finishRefresh**。

### 第一阶段：onRefresh 创建（端口未开）

【源码证据】`module/spring-boot-web-server/src/main/java/org/springframework/boot/web/server/servlet/context/ServletWebServerApplicationContext.java:160-206`

```java
@Override
protected void onRefresh() {
	super.onRefresh();
	try { createWebServer(); }
	catch (Throwable ex) { throw new ApplicationContextException("Unable to start web server", ex); }
}

private void createWebServer() {
	ServletWebServerFactory factory = getWebServerFactory();
	webServer = factory.getWebServer(getSelfInitializer());   // ← 6.3 的工厂方法
	this.webServer = webServer;
	getBeanFactory().registerSingleton("webServerGracefulShutdown",
			new WebServerGracefulShutdownLifecycle(webServer));   // SmartLifecycle：优雅停机
	getBeanFactory().registerSingleton("webServerStartStop",
			new WebServerStartStopLifecycle(this, webServer));    // SmartLifecycle：真正的 start
	initPropertySources();
}
```

工厂返回的 `JettyServletWebServer` 构造函数里完成"半启动"：

【源码证据】`module/spring-boot-jetty/src/main/java/org/springframework/boot/jetty/JettyWebServer.java:118-135`

```java
private void initialize() {
	synchronized (this.monitor) {
		// Cache the connectors and then remove them to prevent requests being
		// handled before the application context is ready.
		this.connectors = this.server.getConnectors();
		JettyWebServer.this.server.setConnectors(null);      // ★ 摘除连接器
		// Start the server so that the ServletContext is available
		this.server.start();                                  // Server 启动，但无监听端口
		this.server.setStopAtShutdown(false);                 // ★ 停机主权移交 Spring（2.4 节的呼应）
	}
}
```

【白话】`Server.start()` 正常执行（Handler 树起来、ServletContext 初始化、6.4 的 initializer 把 DispatcherServlet 注册进去），唯独 connectors 是 null——**内核已经活了，但没有耳朵**。这一步还把 `ServletHandler` 的初始化往后推了（`JettyEmbeddedServletHandler.initialize()` 是空方法，等 `deferredInitialize()` 才调 super，`JettyEmbeddedWebAppContext.java:44-64`）——保证 Servlet 的 `init()` 发生在 connector 启动之后。

### 第二阶段：finishRefresh 正式启动（端口打开）

两个 SmartLifecycle singleton 由 spring-context 的 `finishRefresh() → DefaultLifecycleProcessor.onRefresh()` 驱动：

【源码证据】`module/spring-boot-jetty/src/main/java/org/springframework/boot/jetty/JettyWebServer.java:146-170`

```java
public void start() throws WebServerException {
	synchronized (this.monitor) {
		if (this.started) return;
		this.server.setConnectors(this.connectors);          // ★ 装回连接器
		if (!this.autoStart) return;
		this.server.start();                                  // 容器已启动，幂等；连接器此时才 start
		handleDeferredInitialize(this.server);                // 此时才 ServletHandler.initialize()（Servlet.init）
		Connector[] connectors = this.server.getConnectors();
		for (Connector connector : connectors) {
			try { connector.start(); }
			catch (IOException ex) {
				if (connector instanceof NetworkConnector networkConnector) {
					PortInUseException.throwIfPortBindingException(ex, networkConnector::getPort);  // 端口占用→Boot 异常体系
				}
				throw ex;
			}
		}
		this.started = true;
		logger.info(getStartedLogMessage());                 // "Jetty started on port 8080"
	}
}
```

随后 `WebServerStartStopLifecycle.start()`（`module/spring-boot-web-server/.../servlet/context/WebServerStartStopLifecycle.java:43-48`）发布 `ServletWebServerInitializedEvent`——Boot 生态（如服务注册、Metrics 绑定）以此为"服务器就绪"信号。

### 时序对照图

```
独立启动                                  Boot 内嵌
────────                                  ─────────
main()                                    SpringApplication.run()
 ├ 解析模块/XML                             ├ refresh() 前：定制器链定制工厂
 ├ 逐份 XML 拼 Server                        ├ onRefresh → createWebServer()
 ├ 统一 LifeCycle.start()                    │   ├ 工厂拼 Server + WebAppContext
 │   ├ Server.doStart                        │   ├ server.start()（connectors 摘除，端口未开）
 │   ├ Connector.start → 端口开               │   └ 注册 2 个 SmartLifecycle singleton
 │   └ ServletHandler.initialize             ├ 其余 refresh 步骤（AOP、 bean 创建完）
 └ 开始服务                                  ├ finishRefresh → WebServerStartStopLifecycle.start()
                                             │   ├ setConnectors(装回) → server.start()
                                             │   ├ connector.start() → 端口开
                                             │   ├ deferredInitialize → Servlet.init
                                             │   └ 发布 ServletWebServerInitializedEvent
                                             └ Started 事件 —— 开始服务
```

【要点】独立 Jetty 是**一次性启动**：XML 拼装完成后服务器直接进入服务态；Boot 是**两阶段启动**：先"无端口启动"支撑 ServletContext 装配，refresh 完成后再开闸。这也是为什么 Boot 应用在日志里先看到 "Server initialized with port"（onRefresh）、很久后才看到 "Jetty started on port"（finishRefresh）——中间隔着的正是全部业务 bean 的创建时间。

## 6.6 优雅停机与关闭

Boot 的关闭主权链（对照 2.4 的 ShutdownThread、3.5 的 STOP.PORT）：

1. JVM 收到 SIGTERM / 容器 `close()`：`ServletWebServerApplicationContext.refresh()` 覆写版在失败时 stop+destroy webServer（`:140-158`），正常 close 走 `doClose()` 先发布 `ReadinessState.REFUSING_TRAFFIC`（`:172-181`）；
2. `WebServerGracefulShutdownLifecycle`（phase `GRACEFUL_SHUTDOWN_PHASE = SmartLifecycle.DEFAULT_PHASE - 1024`，先于普通 SmartLifecycle 停止）触发 `JettyWebServer.shutDownGracefully()`（`JettyWebServer.java:319-326`）→ `GracefulShutdown`（`module/spring-boot-jetty/.../GracefulShutdown.java:56-112`）起 "jetty-shutdown" 守护线程，**每 100ms 轮询 `StatisticsHandler.getRequestsActive()`**（这正是 6.3 ⑦ 包裹那层 Handler 的用途），等在途请求清零；
3. `WebServerStartStopLifecycle.stop → JettyWebServer.stop()`（`JettyWebServer.java:258-277`）先 connector.shutdown() 再逐个 stop，最后 `destroy()`。

独立 Jetty 的对应物：`server.setStopTimeout(ms)`（在途请求超时上限）+ `jetty-graceful` 模块（GracefulHandler，行为同 StatisticsHandler 轮询）+ ShutdownThread/STOP.PORT 兜底。**语义等价，所有权不同：一个归 JVM hook 与脚本，一个归 Spring 生命周期。**

## 6.7 响应式栈、虚拟线程与可观测

- **响应式（WebFlux）**：`JettyReactiveWebServerFactory`（`module/spring-boot-jetty/.../reactive/JettyReactiveWebServerFactory.java:74-129`）目前仍经 **Servlet 适配**——`ServletHttpHandlerAdapter` 包住 Reactor 的 `HttpHandler`，注册进 `ServletContextHandler` 的一个 `ServletHolder`。也就是说 Boot 4 的响应式 Jetty 复用了 ee11-servlet 全链，没有直接用 2.5 节的 core Handler API（Tomcat 侧同理）。这是"core 层可以直接承载 MVC 语义"尚未在 Boot 落地的注脚，也是观察 Jetty core 生态的窗口。
- **虚拟线程**：`JettyVirtualThreadsWebServerFactoryCustomizer`（`:62-77`）以 `VirtualThreadPool`（Jetty 实现：任务在虚拟线程上执行）替换 QueuedThreadPool，上限复用 `server.jetty.threads.max`；对 Jetty 而言线程池是注入的组件（2.6 节），替换即生效——组件化设计的红利。
- **可观测**：`JettyMetricsAutoConfiguration`（`:44-70`）在 `ApplicationStartedEvent`（即 6.5 第二阶段完成）后把 `JettyConnectionMetrics`（连接器层）、`JettyServerThreadPoolMetrics`（线程池）、`JettySslHandshakeMetrics` 绑进 Micrometer（`AbstractJettyMetricsBinder.java:35-57`）。独立 Jetty 的对应物是自带的 `dump()`（启动时 `--dump-after-start` 或 jmx 查看全组件树）。

## 6.8 本章小结

- Boot 4 用 `@ConditionalOnClass(ServletRequest, Server, Loader, WebAppContext)` 探测 Jetty，工厂是 `JettyServletWebServerFactory`；定制器链（@Order）把 `server.*`/`server.jetty.*` 属性映射为组件参数。
- `getWebServer()` 一屏代码 = 第四章 XML 链的 Java 等价物；Configuration 只留 4 个，web.xml/注解扫描全免，Servlet 组件经 `ServletContextInitializerConfiguration` 桥注册。
- **两阶段启动**：onRefresh 起 Server 但摘除 connectors（ServletContext 可用而端口未开），finishRefresh 装回并开闸、deferredInitialize 后 Servlet.init 才执行——与独立启动"一次成型"是最大行为差异。
- 停机主权移交 Spring（`setStopAtShutdown(false)` + SmartLifecycle + StatisticsHandler 轮询）；端口冲突被翻译成 `PortInUseException`。

---

# 七、两条启动线对比（本文核心）

## 7.1 总表

| # | 维度 | 独立启动（start.jar） | Spring Boot 内嵌 |
|---|---|---|---|
| 1 | 入口 | `Main.main`（start.jar）→ 反射调 `XmlConfiguration.main` | `SpringApplication.run` → refresh → `factory.getWebServer` |
| 2 | 对象图构建者 | **XmlConfiguration 解释 XML**（数据驱动，运行期反射） | **Java 工厂方法**（编译期类型安全） |
| 3 | 装配知识载体 | `modules/*.mod` + `etc/*.xml`（发行版文件） | `JettyServletWebServerFactory` + 定制器链（class 文件） |
| 4 | 配置来源 | ini/mod `[ini]`/系统属性/命令行 → Props → `<Property>` | application.yml → `ServerProperties`/`JettyServerProperties` → PropertyMapper |
| 5 | 组件排序 | 模块拓扑排序决定 XML 顺序 → 添加顺序决定启动顺序 | 工厂代码书写顺序 + @Order 定制器 |
| 6 | 依赖解析 | 文件系统：`.mod` `[depend]`/`[lib]`，缺文件可下载 | classpath：starter + `@ConditionalOnClass` |
| 7 | 类加载 | start.jar 建独立 ClassLoader；webapp 再套 WebAppClassLoader（child-first+隔离） | 单一应用 ClassLoader（fat-jar 的 LaunchedURLClassLoader），无 WebAppClassLoader；仅隐藏 `org.springframework.boot.loader.` |
| 8 | Servlet 组件来源 | web.xml + web-fragment + `@WebServlet` 注解扫描 + SCI（12+ 个 Configuration） | `ServletContextInitializer` bean 一统（4 个 Configuration），扫描关闭 |
| 9 | 启动模型 | 一次性：XML 拼完 → `LifeCycle.start()` → 服务 | 两阶段：onRefresh 起内核（摘 connectors）→ finishRefresh 开闸 |
| 10 | Servlet.init 时机 | WebAppContext.startContext 内（端口打开前后紧邻） | `deferredInitialize`，明确在 connector.start 之后 |
| 11 | 停机主权 | JVM shutdown hook（ShutdownThread）+ STOP.PORT 协议 | Spring SmartLifecycle + 容器 close；`setStopAtShutdown(false)` |
| 12 | 优雅停机 | `setStopTimeout` + GracefulHandler/StatisticsHandler | 同为 StatisticsHandler 轮询，由 `WebServerGracefulShutdownLifecycle` 驱动 |
| 13 | 部署模型 | 多应用、webapps 目录热部署（DeploymentScanner） | 单应用、应用即部署单元（无热部署，devtools 是应用级重启） |
| 14 | 端口冲突表现 | 连接器启动失败 → MultiException | 统一翻译为 `PortInUseException` |
| 15 | 可观测/运维 | `--list-*`/`--dry-run` 自省、dump()、JMX | Actuator + Micrometer（JettyConnectionMetrics 等） |

## 7.2 谁在装配：两代配置文化的镜像

独立线的装配栈（`.mod` 声明依赖 → 拓扑排序 → XML 反射求值）其实是 **XML 时代的 Spring**（XML bean 定义）+ **Maven**（依赖管理）在服务器领域的同构物；Boot 线（starter + 条件装配 + 属性绑定 + Java 工厂）则是 Spring Boot 把这套文化反向输出给 Jetty。两个细节最能体现代差：

- **类型安全**：XML 里 `<Set name="maxThreads" type="int">` 靠反射按名字找 setter，改名即静默失效（DTD 只校验结构）；Boot 侧 `factory::setMaxConnections` 是方法引用，IDE 直接跳转。
- **可调试性**：XML 装配栈深且反射，断点断在 `XmlConfiguration.configure` 的递归里；Boot 装配是普通调用栈，`getWebServer` 上直接断点。

但独立线也有不可替代处：**发行版可以不改一行 Java 就换装配**——改 ini、加模块、换 XML 都是运维动作；Boot 的等价物（写 Customizer/Factory bean）需要重新编译部署。这决定了两者的最佳场景（7.4）。

## 7.3 模块系统 vs 自动装配：同一思想的两次实现

| | Jetty 模块系统 | Boot 自动装配 |
|---|---|---|
| 触发 | `--module=x` / start.d ini（显式启用） | classpath 探测（隐式启用） |
| 依赖 | `[depend]` 声明 + 拓扑排序 | Maven 依赖树 + `@AutoConfiguration(after=…)` |
| 条件 | `[license]`、`[files]` 存在性校验 | `@ConditionalOnClass/@ConditionalOnMissingBean/@ConditionalOnProperty` |
| 类库 | `[lib]` 汇总 classpath | 坐标即依赖（jar 走 Maven 仓库） |
| 自省 | `--list-modules/--list-config/--dry-run` | `/actuator/conditions` |
| 缺依赖后果 | 模块 unavailable，启动时明确报缺文件 | `@ConditionalOnClass` 静默跳过 |

4.5 节 Configurations 发现流程里的 `if (!configuration.isAvailable()) { __unavailable.add(configuration); return; }`（依赖缺失的 Configuration 直接跳过不注册）与 Boot 的 `@ConditionalOnClass` 精神一致；差别在**粒度与失败模式**：模块系统在启用时就把依赖文件校验清楚（`[files]` 可触发下载），Boot 依赖 Maven 保证 jar 在场、用条件类探测保证 API 兼容。理解了这层同构，`modules/*.mod` 读起来就是一份份"mini starter"。

## 7.4 逐项展开：五个最值得琢磨的差异

**① 对象图的"所有权"（维度 2、3）**。独立启动里 Jetty 的对象图属于 start.jar 进程——XML 是唯一事实，`Server` 之外无人持有引用，运维可通过 XML 替换任意组件（如把 `jetty-threadpool.xml` 换成 virtual 版）。Boot 里对象图属于 Spring：`JettyWebServer` 持有 `Server`，但 Server 内部组件的替换点被收窄为 customizer 回调与属性。**这是"运维主权"与"开发主权"的取舍**。

**② 类加载（维度 7）**。独立多 webapp 依赖 WebAppClassLoader 的 child-first + 隐藏类过滤实现隔离（4.5 节）；Boot 单应用天然无冲突需求，直接 `context.setClassLoader(appClassLoader)`（6.3 关键①），WebAppClassLoader **整个不参与**。唯一残留是 `setHiddenClassMatcher("org.springframework.boot.loader.")`（6.3 末）——隐藏 fat-jar 启动器类，防的是"应用里恰好也有这些类"的污染。所以"Jetty 类加载隔离"知识只对独立部署有效，内嵌场景可以整体遗忘。

**③ 启动时序（维度 9、10）**。6.5 节详述。一句话总结：独立线"先装配后启动"，装配期没有任何服务行为；Boot 线"边装配边启动"——`server.start()` 在 onRefresh 就发生了（ServletContext 必须此时可用），却靠摘除 connectors 把**对外可见性**推迟到 finishRefresh。由此带来两个可观察现象：日志里两条 port 消息间隔 = 业务 bean 创建时长；以及如果某个 BeanPostProcessor 在 onRefresh 与 finishRefresh 之间抛异常，你会得到一个"内核活着但没监听端口"的失败现场（Boot 会在 refresh 失败路径里 stop+destroy webServer 收尸）。

**④ Servlet 装配语义（维度 8）**。独立 WebAppContext 尊重 Servlet 规范全量发现机制（12 个 Configuration），`AnnotationConfiguration` 甚至提供完整 classpath 扫描；Boot 用 4 个 Configuration + `ServletContextInitializerConfiguration` 桥把发现机制**显式化**（bean 定义即注册）。代价与收益都来自同一个决定：放弃"jar 放进来就生效"的隐式语义，换取启动快与行为可预测。`@WebListener` 是唯一例外（`WebListenersConfiguration`，Boot 4 仍支持该注解的显式列举注册）。

**⑤ 停机（维度 11、12）**。三种停机通道（JVM hook、STOP.PORT、Spring 生命周期）在两条线上的启用组合不同：独立线全开（hook + 可选 STOP.PORT）；Boot 线 hook 被显式关闭、STOP.PORT 不存在，一切经 `WebServerGracefulShutdownLifecycle`。**同一个 Jetty，停机语义由壳决定**——这是"内核无常态、壳才有观点"的最佳例证。

## 7.5 什么时候仍然独立跑 Jetty

- **多应用宿主**：一台 JVM 托管多个 webapp、共享连接器与线程池、各自独立类加载与重部署——Boot 单应用模型覆盖不了。
- **运维可换装配**：线程池/连接器/日志模块由 ini 与 XML 调整，发版不碰代码；配合 `--dry-run` 可审计最终命令行。
- **裸 HTTP 服务**：只要 jetty-core 的 Handler（无需 Servlet），如网关/代理/mock server——Boot 对 Jetty 的 core 直接集成尚未提供（6.7）。
- 反过来，绝大多数"一个应用一个进程"的微服务场景，Boot 内嵌是更优解：装配进版本控制、与依赖管理一体化、可观测随生态即插。

---

# 八、附录

## 8.1 关键类速查表

| 类 | 模块 | 一句话 |
|---|---|---|
| `LifeCycle` / `AbstractLifeCycle` | jetty-util | 组件状态机（final start + doStart 模板方法） |
| `ContainerLifeCycle` | jetty-util | 聚合容器：按加入顺序启动、逆序停止、失败回滚 |
| `Server` | jetty-server | 根容器 + Handler 树根 + 属性载体（三重身份） |
| `Handler` / `Request.Handler` | jetty-server | 请求处理契约 `handle(Request, Response, Callback): boolean` |
| `ServerConnector` | jetty-server | acceptor/selector 线程 + ConnectionFactory 协议插件位 |
| `HttpConnectionFactory` / `HttpConfiguration` | jetty-http / jetty-server | HTTP/1.1 协议实现 / 共享参数集 |
| `QueuedThreadPool` | jetty-util | 默认线程池（独立 XML 默认 10/200；Boot 属性默认 8/200） |
| `SelectorManager` / `ManagedSelector` | jetty-io | NIO 事件循环（selector 数 ≈ 核数一半） |
| `Content.Source` / `Content.Sink` | jetty-io | 异步内容抽象（12.x core 的血液） |
| `XmlConfiguration` | jetty-xml | XML 装配脚本引擎（独立启动的真正主类） |
| `Main` / `StartArgs` / `Module` / `Modules` | jetty-start | start.jar：解析、模块图、classpath、反射主类 |
| `DeploymentScanner` / `StandardDeployer` | jetty-deploy | webapps 目录扫描热部署 |
| `ServletContextHandler` | jetty-ee11-servlet | Servlet 上下文 + 三件套（Session/Servlet/Security） |
| `ServletHandler` / `ServletHolder` | jetty-ee11-servlet | Servlet/Filter 注册与匹配、按序 init |
| `WebAppContext` | jetty-ee11-webapp | war 语义的应用容器（docroot + Configuration 流水线） |
| `WebAppClassLoader` | jetty-ee(webapp) | child-first 类加载 + 隐藏类/系统类过滤 |
| `Configurations` | jetty-ee11-webapp | Configuration 流水线的 ServiceLoader 发现与排序 |
| `JettyServletWebServerFactory` | spring-boot-jetty | Boot 工厂：一屏拼出 Server+WebAppContext |
| `JettyWebServer` | spring-boot-jetty | 摘 connectors 半启动 / 装回开闸 / 优雅停机 |
| `JettyEmbeddedWebAppContext` | spring-boot-jetty | 内嵌专用 WebAppContext（隐藏 loader 类 + 延迟 ServletHandler） |
| `ServletContextInitializerConfiguration` | spring-boot-jetty | Boot→Jetty 桥：Configuration 钩子里回调 initializer |
| `ServletWebServerApplicationContext` | spring-boot-web-server | onRefresh 建 server + finishRefresh 开闸的归属 |

## 8.2 源码阅读入口清单（30 个文件）

**独立启动线**（按阅读顺序）：

1. `jetty-core/jetty-start/src/main/java/org/eclipse/jetty/start/Main.java`（main:74 / processCommandLine:384 / start:471 / invokeMain:272）
2. `jetty-core/jetty-start/src/main/java/org/eclipse/jetty/start/StartArgs.java`（MAIN_CLASS:103）
3. `jetty-core/jetty-start/src/main/java/org/eclipse/jetty/start/Module.java`（段落解析:399-499）
4. `jetty-core/jetty-start/src/main/java/org/eclipse/jetty/start/Modules.java`（拓扑排序:323-352）
5. `jetty-core/jetty-server/src/main/config/modules/server.mod`、`http.mod`（模块元数据样例）
6. `jetty-core/jetty-xml/src/main/java/org/eclipse/jetty/xml/XmlConfiguration.java`（DSL:580-616 / configure():512 / main():1997-2150）
7. `jetty-core/jetty-server/src/main/config/etc/jetty-threadpool.xml` → `jetty-http-config.xml` → `jetty.xml` → `jetty-http.xml`（默认装配链）
8. `jetty-core/jetty-deploy/src/main/config/etc/jetty-deployment-scanner.xml` + `jetty-ee11/jetty-ee11-webapp/src/main/config/etc/jetty-ee11-deploy.xml`
9. `jetty-core/jetty-deploy/src/main/java/org/eclipse/jetty/deploy/DeploymentScanner.java`（scan:624）
10. `jetty-ee11/jetty-ee11-webapp/src/main/java/org/eclipse/jetty/ee11/webapp/WebAppContext.java`（:87）

**内核**：

11. `jetty-core/jetty-util/.../component/LifeCycle.java`（:28）
12. `jetty-core/jetty-util/.../component/AbstractLifeCycle.java`（start:73）
13. `jetty-core/jetty-util/.../component/ContainerLifeCycle.java`（doStart:93 / doStop:188）
14. `jetty-core/jetty-server/src/main/java/org/eclipse/jetty/server/Server.java`（:77 / doStart:568）
15. `jetty-core/jetty-server/src/main/java/org/eclipse/jetty/server/Handler.java`（:119 / Sequence:813）
16. `jetty-core/jetty-server/src/main/java/org/eclipse/jetty/server/Request.java`（Request.Handler:791）
17. `jetty-core/jetty-server/src/main/java/org/eclipse/jetty/server/ServerConnector.java`（ctor:203 / doStart:224）
18. `jetty-core/jetty-server/src/main/java/org/eclipse/jetty/server/AbstractConnector.java`（acceptor 默认:201）
19. `jetty-core/jetty-io/src/main/java/org/eclipse/jetty/io/SelectorManager.java`（selector 默认:88）
20. `jetty-core/jetty-util/src/main/java/org/eclipse/jetty/util/thread/QueuedThreadPool.java`（:125）

**Servlet 接合层**：

21. `jetty-ee11/jetty-ee11-servlet/.../ServletContextHandler.java`（startContext:1320）
22. `jetty-ee11/jetty-ee11-servlet/.../ServletHandler.java`（initialize 调用:223）
23. `jetty-ee11/jetty-ee11-webapp/.../Configurations.java`（ServiceLoader 发现:70）
24. `jetty-ee11/jetty-ee11-webapp/src/main/resources/META-INF/services/org.eclipse.jetty.ee11.webapp.Configuration`（12 个内置 Configuration）
25. `jetty-core/jetty-ee/jetty-ee-webapp/src/main/java/org/eclipse/jetty/ee/webapp/WebAppClassLoader.java`（loadClass:448）

**Boot 内嵌线**（工作树 D:\tmp\sb408）：

26. `module/spring-boot-jetty/src/main/java/org/springframework/boot/jetty/autoconfigure/servlet/JettyServletWebServerAutoConfiguration.java`（:60）
27. `module/spring-boot-jetty/src/main/java/org/springframework/boot/jetty/servlet/JettyServletWebServerFactory.java`（getWebServer:156 / configureWebAppContext:218）
28. `module/spring-boot-jetty/src/main/java/org/springframework/boot/jetty/JettyWebServer.java`（initialize:118 / start:146）
29. `module/spring-boot-jetty/src/main/java/org/springframework/boot/jetty/servlet/ServletContextInitializerConfiguration.java`（:48）
30. `module/spring-boot-web-server/src/main/java/org/springframework/boot/web/server/servlet/context/ServletWebServerApplicationContext.java`（onRefresh:160 / createWebServer:183）

## 8.3 动手验证清单

独立发行版侧（无需写代码）：

```
# 1. 下载 jetty-distribution 12.1.x 并解压到 $JETTY_HOME
cd $JETTY_HOME
java -jar start.jar --list-modules            # ③ 模块清单
java -jar start.jar --create-files --module=server,http,ee11-deploy
java -jar start.jar --list-config             # ③ 属性/classpath/XML 清单
java -jar start.jar --dry-run                 # ③ 等价命令行（会看到主类 XmlConfiguration）
java -jar start.jar                           # ④⑤⑥ 完整启动，观察 XML 执行与 Started 日志
# 热部署：往 webapps/ 丢一个 war，观察 DeploymentScanner 输出（scanInterval>0 时）
```

Boot 侧断点（任意 `spring-boot-starter-jetty` 工程）：

- `JettyServletWebServerFactory#getWebServer`（装配入口）
- `JettyWebServer#initialize`（观察 connectors 摘除）与 `#start`（观察装回）
- `ServletContextInitializerConfiguration#callInitializers`（看 DispatcherServlet 怎么注册）
- `ServletHandler#initialize`（对比普通 WebAppContext 与 JettyEmbeddedServletHandler 的触发时机）
- 日志对照：`Server initialized with port`（onRefresh）与 `Jetty started on port`（finishRefresh）之间隔着什么

---

# 结语

把两条启动线并排放回 1.1 的定位上，Jetty 的设计意图就完全显形了：**内核（LifeCycle 组件树 + 异步 I/O + Handler 契约）提供不变的东西，装配（XML 脚本或 Spring 工厂）负责应对变化的世界**。start.jar 是 2000 年代"应用服务器"文化的产物——配置外置、模块热插、运维主权；Boot 内嵌是 2010 年代"应用即进程"文化的产物——装配代码化、单应用、开发主权。Jetty 用同一套内核同时服务了这两代文化，这本身就是它"嵌入优先"哲学的最好证明。

对读者的实用建议浓缩成三句：**排障时先分清你在哪条线上**（看 port 日志是一条还是两条）；**调优时记住共同锚点**（线程池 8/200、acceptor 1、selector ≈ 核数/2、idleTimeout 30s，独立线从 XML/ini 调，Boot 线从 yml 调）；**扩展时认准所属层**（改行为→Handler 树或 Customizer，改装配→XML 或 Factory bean，改协议→ConnectionFactory）。
