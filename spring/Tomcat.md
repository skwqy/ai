# Tomcat 深度源码解析（独立运行与 Spring Boot 内嵌启动双轨全景）

> **本文基于的源码**：`D:\code\3rd\tomcat`，版本 **11.0.24**（Apache 官方源码发布包，与 Spring Boot 4.0.8 的 `gradle.properties` 中 `tomcatVersion=11.0.24` 严格对应）。文中所有【源码证据】的文件路径与行号均为对该版本实际读取所得；路径省略前缀 `java/`，如 `catalina/startup/Bootstrap.java` 即 `D:\code\3rd\tomcat\java\org\apache\catalina\startup\Bootstrap.java`。
>
> **Spring 侧源码**：内嵌启动章节基于 `D:\code\3rd\spring-boot`（Boot 4.0.8 worktree），关键类位于 `module/spring-boot-tomcat`。Boot 侧"refresh 时序、自动配置接线"已在《Spring Boot.md》第五章展开，本文不重复，只讲 **Tomcat 侧被"当作对象"使用时发生了什么**。
>
> **阅读约定**：与本系列其他文档相同——每章"先白话、后源码"，先用人话讲清"这是什么、为什么需要它"，再给出证据链（`模块/src/main/java/.../类名.java` + 方法名 + 行号 + 代码片段）。行号只对 11.0.24 精确，读者按类名 + 方法名定位即可。Tomcat 的双轨主题——**独立运行**（`startup.sh` 起、`shutdown.sh` 停、`server.xml` 配置、`webapps/` 目录部署）与**内嵌启动**（Spring Boot 一个 main 方法把 Tomcat 当库用）——贯穿全文，第三章与第六章分别逐行拆解，第六章末尾给出逐项差异总表。

## 如何读这份文档

如果你是 Tomcat 初学者（或只用过 Spring Boot 但想知道"那个 8080 端口后面到底是谁"），推荐两遍读法：

- **第一遍（建立地图，1~2 小时）**：只读第一章（总览）每一节的开头白话段、各章"小结"、第二章 2.2 节的容器层级图、3.4 节的启动时序图、4.0 节的请求全景图、6.9 节的差异总表。目标是能回答：Tomcat 内部有哪些组件、谁监听端口、一个 HTTP 请求从字节流到 `service()` 方法经过哪些节点？独立运行和内嵌启动差在哪？
- **第二遍（深入源码）**：对照【源码证据】逐行读。顺序建议：第二章（Lifecycle 状态机，Tomcat 的"脊柱"）→ 第三章（独立启动全链路）→ 第六章（内嵌启动，与第三章时时对照）→ 第四章（请求处理）→ 第五章（类加载/会话/JSP 等支撑设施，随用随查）→ 第七章（扩展点与调优速查）。

如果你带着一个具体问题来，直接翻 1.5 节的"关键问题 → 章节映射"表。

---

# 一、总览：Tomcat 的定位、设计哲学与整体架构

## 1.1 一句话定位

**Tomcat 是一个"Servlet 容器"——它把 HTTP 字节流翻译成 `HttpServletRequest/Response` 对象、把你的 `Servlet/Filter/Listener` 三类组件装配成一条能处理请求的流水线、并管理它们从出生到销毁的整个生命周期。** 用 Tomcat 官方文档的话说，它实现了 Jakarta Servlet、Jakarta Expression Language、Jakarta WebSocket 三大规范（11.0.x 对应 Jakarta EE 11 平台级别，即 **Servlet 6.1**——这正是《Spring MVC.md》确定的 Framework 7.0/Boot 4 容器基线）。

它同时是 Spring Boot 默认的内嵌 Web 服务器：你在 Boot 应用里看到的 `Tomcat started on port(s) 8080 (http)` 这行日志，背后就是本文第四章的那台机器在监听。Tomcat 内部由若干子项目拼成，每个子项目一句话定位：

| 子项目 | 一句话职责 | 源码位置（`java/org/apache/` 下） |
|---|---|---|
| **Catalina** | Servlet 容器本体：组件层级、生命周期、部署、会话、安全（Realm） | `catalina/` |
| **Coyote** | 连接器框架：与协议无关的 I/O 端点（NIO/NIO2）、HTTP/1.1、HTTP/2、AJP 解析 | `coyote/` + `tomcat/util/net/` |
| **Jasper** | JSP 编译引擎：把 `.jsp` 翻译成 Servlet 源码并编译 | `jasper/` |
| **tomcat（util 家族）** | 地基工具箱：NIO 实现、线程池（`threads`）、字符串缓存（`buf`）、HTTP 头解析（`http`）、Jar 扫描（`scan`） | `tomcat/util/` |
| **JULI** | Tomcat 自己的 `java.util.logging` 实现（按 Web 应用隔离日志配置） | `juli/` |
| **Naming** | JNDI 实现（`java:comp/env` 数据源、环境条目） | `naming/` |
| **Cluster/Tribes** | 会话复制集群（本文不展开） | `catalina/ha/` |

## 1.2 设计哲学：读源码前先记住五句话

1. **一切皆组件，一切皆 Lifecycle。** Server、Service、Connector、Engine、Host、Context、Wrapper、Pipeline、Realm、Loader……全部实现 `org.apache.catalina.Lifecycle`，全部继承同一个模板基类 `LifecycleBase`（2.3 节）：`init → start → stop → destroy` 四段式 + 11 个生命周期事件 + 一个严格的状态机。启动 Tomcat 就是"对 Server 这棵树按深度优先做一遍 init/start"，没有第二套机制。
2. **配置即结构，Digester 即"XML→对象图"的编译器。** 独立运行的 Tomcat 没有 main 配置类，`conf/server.xml` 里写什么，对象树就长什么样——`Catalina` 用 Digester（一个 SAX 规则引擎）把 `<Server>/<Service>/<Connector>/<Engine>/<Host>/<Context>` 逐个 `new` 出来并挂接（3.3 节）。这条设计直接埋下了内嵌模式的伏笔：**Spring Boot 的做法本质上是"绕过 Digester，直接写 Java 代码构建同一棵对象树"**（6.2 节）。
3. **容器链与连接器正交。** Coyote 只管"字节流 ↔ `org.apache.coyote.Request/Response`"，不认识 Servlet；Catalina 只管"对象 ↔ 组件生命周期"，不认识 socket。两者的唯一缝合点是一个接口：`Adapter`（4.2 节）。所以同一个 `StandardEngine` 树可以接 HTTP/1.1、HTTP/2、AJP 任意多种连接器。
4. **层层管道，节节可插。** 每一层容器（Engine/Host/Context/Wrapper）都持有一条 `Pipeline`（管道），管道末端有一个必然存在的 `basic Valve`，请求逐层流经各级 Valve（4.4 节）。`AccessLogValve`、`ErrorReportValve`、远程地址过滤 Valve……全是往不同层的管道里插的插件。
5. **隔离优先。** 每个 Web 应用一个独立的 `WebappClassLoader`（5.1 节），一个应用的类泄漏、依赖冲突不殃及邻居；日志按应用隔离（JULI 的 per-classloader 配置）；连"应用停止时回收线程/ThreadLocal"都有专门的内存泄漏防护代码（`WebappClassLoaderBase.checkThreadLocalsForLeaks`）。

## 1.3 源码目录分层全景

以源码包结构实拍（`java/` 根下，11.0.24 实测）：

```
java/org/apache/
├─ catalina/                    ← Catalina：容器本体
│   ├─ startup/                 ← 启动/停止入口：Bootstrap、Catalina、Digester 规则集、HostConfig、
│   │                             ContextConfig、ContextRuleSet、嵌入式门面 org.apache.catalina.startup.Tomcat
│   ├─ core/                    ← 标准实现：StandardServer/Service/Engine/Host/Context/Wrapper/Pipeline、
│   │                             各级 Valve、ApplicationFilterChain、ApplicationContext（ServletContext 实现）
│   ├─ connector/               ← Connector + CoyoteAdapter + Request/Response（Catalina 侧外观）
│   ├─ loader/                  ← WebappLoader、WebappClassLoaderBase、ParallelWebappClassLoader
│   ├─ session/                 ← StandardManager（会话管理器）
│   ├─ realm/ valve/ ha/ naming/ ...
├─ coyote/                      ← Coyote：协议层（与 Servlet 无关）
│   ├─ Request.java/Response.java/Adapter.java/Processor.java/ProtocolHandler.java
│   ├─ http11/                  ← Http11NioProtocol、Http11Processor、输入/输出缓冲
│   ├─ http2/ ajp/ AsyncContextImpl（在 core）...
├─ tomcat/                      ← 工具与底层网络
│   ├─ util/net/                ← NioEndpoint、NioChannel、Acceptor、SSLHostConfig（I/O 核心）
│   ├─ util/digester/ buf/ http/ scan/ threads/ res/ ...
├─ jasper/ juli/ naming/ el/ websocket/ ...
```

而**二进制发布版**的目录与源码包一一对应（本文第三章会回到这张图）：

```
apache-tomcat-11.0.24/
├─ bin/catalina.sh(.bat)        ← 启动脚本：拼 classpath、设 JAVA_OPTS、调 Bootstrap
├─ conf/server.xml              ← 对象树蓝本（3.3 节被 Digester 消费）
│   ├─ catalina.properties      ← 类加载器路径、Jar 跳过清单、连接器缓冲配置
│   └─ web.xml                  ← 全局默认 Web 描述（DefaultServlet + JspServlet 就定义在这）
├─ lib/                         ← common.loader 指向的目录（catalina.jar、coyote.jar…）
├─ webapps/                     ← Host 的 appBase：应用目录/WAR 放这里被自动部署（3.5 节）
├─ logs/ work/ temp/            ← 输出、编译产物（Jasper 生成类）、临时文件
```

## 1.4 核心组件层级：一图看懂"谁包含谁、谁听谁的"

Tomcat 的对象树严格分两部分：**容器树**（Engine→Host→Context→Wrapper，管"请求分发给哪个 Servlet"）与**服务器骨架**（Server→Service，管"启动谁、监听哪些端口"）。连接器横插在 Service 与 Engine 之间。`conf/server.xml`（`conf/server.xml:22-135`）就是这棵树的文本投影：

```
<Server port="8005" shutdown="SHUTDOWN">            ← 顶层：JVM 内唯一，管整体生命周期 + 关闭命令监听
 └─ <Service name="Catalina">                       ← 把若干 Connector 绑到一个 Engine 上
     ├─ <Connector port="8080" protocol="HTTP/1.1"/> ← 监听端口、协议解析（Coyote 世界）
     ├─ <Connector port="8443" .../>                 ← 可多个（HTTP/2、AJP…）
     └─ <Engine name="Catalina" defaultHost="localhost"> ← 容器树根：按 Host 名分发
         └─ <Host name="localhost" appBase="webapps">     ← 虚拟主机：管应用部署
             └─ <Context docBase="myapp"/>          ← 一个 Web 应用：Servlet 容器里的"应用"
                 └─ <Wrapper>Servlet</Wrapper>       ← 一个 Servlet（含 Filter/映射/初始化参数）
```

【源码证据】这套层级在接口层是 `org.apache.catalina.{Server,Service,Engine,Host,Context,Wrapper}`，在实现层全部落在 `catalina/core/Standard*`。继承树（11.0.24 实测，均继承 `LifecycleMBeanBase`）：

```
LifecycleMBeanBase
 └─ LifecycleBase（init/start/stop/destroy 状态机，2.3 节）
     ├─ StandardServer          （Server：findServices[]、await()、utilityExecutor）
     │    └─ 持有 Service[]         （StandardService：engine + connectors[] + executors[] + Mapper）
     ├─ ContainerBase           （容器骨架：children[]、pipeline、backgroundProcessor、startStopExecutor）
     │    ├─ StandardEngine      （basic = StandardEngineValve）
     │    ├─ StandardHost        （basic = StandardHostValve，挂 HostConfig 做部署）
     │    ├─ StandardContext     （basic = StandardContextValve，挂 ContextConfig 做 web.xml/SCI 装配）
     │    └─ StandardWrapper     （basic = StandardWrapperValve，管一个 Servlet 实例）
     └─ Connector               （不是容器：持 ProtocolHandler + CoyoteAdapter，三、四章主角）
```

这张图是全文的地图：第三章讲 Server 这棵树如何从 `startup.sh` 一路 init/start 下来；第四章讲一个请求如何从 Connector 穿过这四级容器；第六章讲 Spring Boot 如何跳过 `startup.sh` 直接 `new` 出这棵树。

## 1.5 关键问题 → Tomcat 方案映射（全文导览）

| 关键问题 | Tomcat 的方案 | 详见 |
|---|---|---|
| 进程怎么起、为什么 `startup.sh` 后控制台能退出而进程不死 | Bootstrap.main 主线程反射调 Catalina → 启动完成后主线程阻塞在 `StandardServer.await()`（非 daemon，8 秒轮询 ServerSocket） | 第三章 |
| `server.xml` 里每个标签如何变成对象 | Digester 规则集：`addObjectCreate` + `addSetNext` 构建对象树 | 3.3 |
| 8080 端口谁在监听、谁来搬运字节 | Coyote `NioEndpoint`：1 个 Acceptor（accept）+ 1 个 Poller（就绪事件）+ 线程池（执行） | 4.1 |
| HTTP 字节流如何变成 `HttpServletRequest` | `Http11Processor` 解析请求头 → `CoyoteAdapter.service` 转换对象 | 4.2-4.3 |
| URL `/app/hello` 如何命中某个 Servlet | `Mapper`（Service 级路由表）三层匹配：Host → Context → Wrapper | 4.3 |
| 请求如何在容器层级间流转 | 四级 Pipeline/Valve 责任链（Engine→Host→Context→Wrapper） | 4.4 |
| Filter 链怎么组装、`service()` 谁来调 | `ApplicationFilterFactory.createFilterChain` 匹配 → `ApplicationFilterChain.doFilter` 末尾调 `servlet.service()` | 4.5 |
| 两个应用的同名类为何不冲突 | 每应用一个 `WebappClassLoader`，先本地后父加载器的特殊委派次序 | 5.1 |
| `webapps/` 丢个 war 为什么自动部署 | Host 的 `HostConfig` 监听 START_EVENT/PERIODIC_EVENT，扫描 appBase | 3.5 |
| `@WebServlet`/Spring Boot 的 Servlet 是怎么注册的 | Servlet 3.0 动态注册：SCI 扫描（独立）→ `ServletContextInitializer` 回调（内嵌） | 3.5、6.5 |
| JVM 为什么不会退出（两种模式下原因不同！） | 独立：主线程阻塞 await；内嵌：Boot 起的非 daemon 容器线程 | 3.6、6.8 |
| 优雅停机 | 独立：8005 端口收 SHUTDOWN；内嵌：Boot `GracefulShutdown` pause Connector | 3.7、6.8 |
| 虚拟线程怎么接入 | `AbstractEndpoint.createExecutor` 的 `VirtualThreadExecutor` 分支；Boot 侧一个 Customizer 即可切换 | 4.1、7.2 |

## 1.6 版本演进：从 Tomcat 3 到 11，哪些变了、哪些没变

写作时（2026 年 10 月）的版本格局：**8.5/9 已 EOL，10.1 与 11.0 并行维护**（10.1 对应 Servlet 6.0/Jakarta EE 10，11.0 对应 Servlet 6.1/Jakarta EE 11）。本节结论以官方 RELEASE-NOTES 与发布记录为据（源码包内附 `RELEASE-NOTES`）。

| 大版本 | GA 年份 | Servlet/EE | 一句话主题 |
|---|---|---|---|
| Tomcat 3/4/5 | 1999-2003 | Servlet 2.2-2.4 | Catalina（4.0）替代原容器；5.x 引入"连接器与容器分离"的 Coyote 雏形 |
| Tomcat 6/7 | 2007/2011 | 2.5 / 3.0 | 7.0 吃下 Servlet 3.0：**注解配置、动态注册、可插拔容器**——Spring Boot 内嵌启动的规范基础 |
| Tomcat 8/8.5 | 2014/2016 | 3.1 | **移除 BIO**，默认连接器全面 NIO；HTTP/2（8.5） |
| Tomcat 9 | 2017 | 4.0 | 最后的 `javax.servlet` 版本 |
| Tomcat 10 | 2020 | 5.0 (Jakarta EE 9) | **命名空间大迁徙：`javax.servlet` → `jakarta.servlet`**（本文全程 `jakarta`） |
| Tomcat 11 | 2024 | 6.1 (Jakarta EE 11) | 本文基线；NIO 端点支持**虚拟线程执行器**；HTTP/2 默认可升级；**移除 NIO2 之外连 JMX 的部分依赖也持续瘦身** |

对初学者的意义：**架构自 7.0（2011）以来高度稳定**——Lifecycle 状态机、Pipeline/Valve、Mapper 路由、WebappClassLoader 委派次序、Digester 启动，这些核心机制在 7 → 11 之间骨架一致（方法签名几乎原样）；真正变化的是协议层（BIO 移除、HTTP/2、虚拟线程）与命名空间（jakarta）。所以本文的源码分析对维护 Tomcat 9（`javax` 包名，其余几乎相同）的读者同样适用。

## 1.7 全文章节地图

```
一、总览                     ← 你在这里：定位、哲学、层级地图、版本演进
二、内核                     ← Lifecycle 状态机 + 容器骨架 + Pipeline/Valve（一切的地基）
三、独立启动全链路（重点 A）  ← catalina.sh → Bootstrap → Digester → init/start 递归 → 部署 → await
四、请求的一生（重点 B）      ← Acceptor/Poller → Processor → CoyoteAdapter → 4 级 Valve → FilterChain
五、支撑设施                 ← 类加载隔离、会话、JSP、线程池、JULI
六、内嵌启动（重点 C）        ← startup.Tomcat 门面 → Boot 工厂 → 半启动/延后绑端口 → 差异总表
七、扩展点与调优速查          ← 每个节点能插什么手、server.* 配置对应哪个字段
```

---

# 二、内核：组件化与 Lifecycle 生命周期状态机（catalina/util + catalina/core）

## 2.1 模块定位与包结构

先说白话：Tomcat 有几十种组件，每种组件都要"初始化、启动、停止、销毁、失败处理、事件通知"。如果每个组件各写一套，启动顺序立刻失控。Tomcat 的解法是把这五件事**抽成一个抽象基类 `LifecycleBase`**，子类只需实现四个 `xxxInternal()` 方法。本章讲清这个模板，后面三章就都在"重复使用"它。

【源码证据】接口 `catalina/Lifecycle.java` 定义四段式操作与 13 个事件常量：

```java
public interface Lifecycle {
    public static final String BEFORE_INIT_EVENT = "before_init";
    public static final String AFTER_INIT_EVENT = "after_init";
    public static final String START_EVENT = "start";
    public static final String BEFORE_START_EVENT = "before_start";
    public static final String AFTER_START_EVENT = "after_start";
    public static final String STOP_EVENT = "stop";
    public static final String BEFORE_STOP_EVENT = "before_stop";
    public static final String AFTER_STOP_EVENT = "after_stop";
    public static final String AFTER_DESTROY_EVENT = "after_destroy";
    public static final String BEFORE_DESTROY_EVENT = "before_destroy";
    public static final String PERIODIC_EVENT = "periodic";
    public static final String CONFIGURE_START_EVENT = "configure_start";
    public static final String CONFIGURE_STOP_EVENT = "configure_stop";
    public void init() throws LifecycleException;
    public void start() throws LifecycleException;
    public void stop() throws LifecycleException;
    public void destroy() throws LifecycleException;
    ...
}
```

事件常量与状态一一对应：每个 `LifecycleState`（`catalina/LifecycleState.java`）都携带一个默认事件名（`getState().getLifecycleEvent()`），状态迁移即发事件——这是 Tomcat 里"组件间松耦合协作"的总开关：**ContextConfig 靠 `configure_start` 事件装配 web.xml（3.5 节）、HostConfig 靠 `start` 事件扫描部署（3.5 节）、NamingContextListener 靠 `before_start` 建 JNDI 树**，全都不是父组件硬编码调子组件，而是事件广播。

## 2.2 容器骨架 ContainerBase：四级容器的公共行为

Engine/Host/Context/Wrapper 四级容器的公共行为（持有子容器、管 Pipeline、起后台线程）全部在 `ContainerBase`（`catalina/core/ContainerBase.java`）：

- **子容器集合**：`children`（`addChild/findChildren`），Context 的孩子是 Wrapper，Host 的孩子是 Context——树的形状由这一对方法维护；
- **管道**：每级容器固定持有一条 `StandardPipeline`，`basic` Valve 由构造器指定（如 `StandardEngine` 构造时 `pipeline.setBasic(new StandardEngineValve())`）；
- **后台处理线程**：`backgroundProcessorDelay`（默认 Engine 为 10 秒）驱动的 `ContainerBackgroundProcessor`，每周期递归调用各级容器的 `backgroundProcess()`——**会话超时扫描、HostConfig 的热部署检查（autoDeploy）、集群周期同步都靠它**（3.5 节会再遇到它）。

【源码证据】`ContainerBase.startInternal()`（`catalina/core/ContainerBase.java:712-769`）给出"容器启动"的标准动作——先起 Cluster/Realm 附属，再**并行启动全部子容器**，最后起管道：

```java
protected void startInternal() throws LifecycleException {
    reconfigureStartStopExecutor(getStartStopThreads());
    ...
    // Start our child containers, if any
    Container[] children = findChildren();
    List<Future<Void>> results = new ArrayList<>(children.length);
    for (Container child : children) {
        results.add(startStopExecutor.submit(new StartChild(child)));   // ← 子容器并行 start
    }
    for (Future<Void> result : results) { result.get(); ... }          // ← 等全部完成，任一失败整体失败
    // Start the Valves in our pipeline (including the basic), if any
    if (pipeline instanceof Lifecycle) {
        ((Lifecycle) pipeline).start();                                 // ← 管道随后启动
    }
    setState(LifecycleState.STARTING);
    ...
}
```

注意两个细节：① `startStopExecutor` 是容器自备的小线程池（默认 2 线程，`getStartStopThreads()`），Host 下有多个 Context 时它们并行启动——**这就是 Tomcat 启动日志里多个应用同时 "Deployment ... has finished" 的原因**；② `setState(STARTING)` 会触发 `START_EVENT`——HostConfig 挂在 Host 上等的就是这个事件（3.5 节）。

## 2.3 LifecycleBase：一个基类管住四种状态迁移（本章核心）

先说白话：`init()/start()` 是 public 方法，但绝不能让子类乱改流程。Tomcat 把四个方法声明为 **`final synchronized`**，子类只能填 `xxxInternal()` 空格；状态迁移是否合法由模板统一裁决。

【源码证据】`catalina/util/LifecycleBase.java`。**init()**（:121-133）——必须从 NEW 出发，前后状态 INITIALIZING/INITIALIZED：

```java
@Override
public final synchronized void init() throws LifecycleException {
    if (!state.equals(LifecycleState.NEW)) {
        invalidTransition(BEFORE_INIT_EVENT);              // ← 非 NEW 状态 init = 非法迁移，直接抛
    }
    try {
        setStateInternal(LifecycleState.INITIALIZING, null, false);
        initInternal();                                    // ← 子类填空格
        setStateInternal(LifecycleState.INITIALIZED, null, false);
    } catch (Throwable t) {
        handleSubClassException(t, "lifecycleBase.initFail", toString());
    }
}
```

**start()**（:145-187）——允许"从 NEW 直接 start"（自动补 init），并对"受控失败"做了优雅处理：

```java
@Override
public final synchronized void start() throws LifecycleException {
    if (LifecycleState.STARTING_PREP.equals(state) || ... STARTED.equals(state)) {
        return;                                            // ← 幂等：重复 start 静默返回
    }
    if (state.equals(LifecycleState.NEW)) {
        init();                                            // ← 没 init 过？先补
    } else if (state.equals(LifecycleState.FAILED)) {
        stop();                                            // ← 上次失败了？先清理
    } else if (!state.equals(LifecycleState.INITIALIZED) && !state.equals(LifecycleState.STOPPED)) {
        invalidTransition(BEFORE_START_EVENT);
    }
    try {
        setStateInternal(LifecycleState.STARTING_PREP, null, false);
        startInternal();                                   // ← 子类填空格（必须把自己置为 STARTING）
        if (state.equals(LifecycleState.FAILED)) {
            stop();                                        // ← "受控失败"：子组件自判 FAILED，父组件收尾
        } else if (!state.equals(LifecycleState.STARTING)) {
            invalidTransition(AFTER_START_EVENT);
        } else {
            setStateInternal(LifecycleState.STARTED, null, false);
        }
    } catch (Throwable t) {
        handleSubClassException(t, "lifecycleBase.startFail", toString());   // ← "不受控失败"：置 FAILED 并上抛
    }
}
```

状态机本身是 `setStateInternal`（:361-397）里的**白名单校验**（:383-389）：除"任何状态可转 FAILED、STARTING_PREP→STARTING、STOPPING_PREP→STOPPING、FAILED→STOPPING"外一律拒绝迁移。每次合法迁移都会把状态对应的事件广播给所有 `LifecycleListener`（:393-396，监听器列表是 `CopyOnWriteArrayList`，:52）。

**读 Tomcat 源码的心法**：任何 `Standard*` 类，看到 `setState(LifecycleState.STARTING)` 就要意识到"这一行同时完成两件事：改状态 + 广播事件"，所有挂在它身上的监听器（部署器、JNDI、JMX 通知）在此刻被唤醒。

## 2.4 Pipeline/Valve：容器侧的责任链

先说白话：请求在容器树里的流转不走"父容器调用子容器方法"这种写死的路径，而是走**每级容器各有一条管道、管道里串着一串阀门**的责任链。默认情况下每级管道只有一个 basic Valve（必在末端），但用户可以往任意一级插自定义 Valve——访问日志、请求过滤都在这里做。

【源码证据】`catalina/core/StandardPipeline.java`：`getFirst()`（:402-412）返回"首个可调用 Valve"（basic 之前的第一个），`addValve()`（:267）按顺序插入。四个 basic Valve 的 invoke 方法行号（11.0.24 实测，4.4 节逐个拆解）：

| Valve | 类 | invoke 起始行 | 干什么 |
|---|---|---|---|
| Engine 级 | `StandardEngineValve` | :54 | 按 Host 名选中 StandardHost，把请求交给 Host 管道 |
| Host 级 | `StandardHostValve` | :78 | 按 Context 映射选中 StandardContext；**错误页/状态码兜底**在这里 |
| Context 级 | `StandardContextValve` | :54 | 校验（禁止直访 WEB-INF/META-INF），定位 Wrapper，交给 Wrapper 管道 |
| Wrapper 级 | `StandardWrapperValve` | :84 | **加载/分配 Servlet 实例、组装 Filter 链、调 `filterChain.doFilter`** |

管道启动也走 Lifecycle：`Pipeline.start()` 会逐个 start 其上的 Valve（Valve 也实现 Lifecycle）。这套"管道 + 阀门"与 Servlet 规范的 Filter 链是两回事：**Valve 是容器内部的钩子（应用代码碰不到），Filter 是规范定义的应用级钩子（应用代码全权控制）**——前者在 ContextValve 之前就生效，后者已经进入应用世界。

## 2.5 本章小结

- Tomcat 全部组件实现 `Lifecycle`：四段式生命周期（init/start/stop/destroy）由 `LifecycleBase` 用 **final 模板方法 + 状态白名单机**管死，子类只填 `xxxInternal()`。
- 状态迁移 = 事件广播：ContextConfig/HostConfig/NamingContextListener 等关键装配逻辑全部挂在事件上，这是"组件松耦合"的总开关。
- 容器骨架 `ContainerBase` 统一了"子容器并行启动 + 管道启动 + 后台处理线程"三件事；四级容器的差异只在各自的 basic Valve 与附属组件。
- Pipeline/Valve 是容器内责任链，Filter 是规范级应用钩子，两者作用域不同、时序先后有别（4.4/4.5 节展开）。

下一章把这台机器从冷启动到待命完整跑一遍：`startup.sh` 敲下去之后到底发生了什么。

---
# 三、独立启动全链路：从 startup.sh 到 8005 端口监听（重点 A）

## 3.1 先说白话：独立启动的三幕剧

把 `bin/startup.sh`（Windows 下 `startup.bat`）想象成三幕剧：

1. **第一幕·环境搭建**：`catalina.sh` 拼好 classpath、设好系统属性，用 JVM 启动 `Bootstrap.main()`——到此为止全是 shell 的事；
2. **第二幕·对象树搭建**：Bootstrap 造好三层类加载器，反射调用 `Catalina`；Catalina 用 Digester 解析 `conf/server.xml`，把 1.4 节那棵对象树 `new` 出来，然后对树根做一遍递归 `init()`；
3. **第三幕·通电**：再对树根做一遍递归 `start()`——Engine 起来后 HostConfig 部署 webapps 下的应用，Connector 起来后端口开始监听；最后**主线程阻塞在 8005 端口等 SHUTDOWN 命令**，这一阻塞同时回答了"进程为什么不会退出"。

下面逐幕拆解。每一幕都给出源码证据，行号基于 11.0.24。

## 3.2 第一幕：catalina.sh 与 Bootstrap 的类加载器铺垫

### 3.2.1 catalina.sh：环境与入口

`bin/catalina.sh` 的 `start` 分支做三件事：设置 `CATALINA_HOME`/`CATALINA_BASE`（默认相同；多实例部署时 base 独立、home 共享）、加载 `setenv.sh` 与 `JAVA_OPTS`、最终执行：

```bash
exec "$_RUNJAVA%" $JAVA_OPTS ... \
  -Dcatalina.home="$CATALINA_HOME" \
  -Dcatalina.base="$CATALINA_BASE" \
  -Djava.io.tmpdir="$CATALINA_TMPDIR" \
  org.apache.catalina.startup.Bootstrap "$@" start
```

两个目录属性是后续一切路径解析的根：`Bootstrap` 的静态代码块（`catalina/startup/Bootstrap.java:65-124`）把它们规范化为 `catalinaHomeFile`/`catalinaBaseFile`，找不到显式值时按"当前目录是否含 bootstrap.jar"推断（:82-95）。**这个静态块是 JVM 里最先执行的 Tomcat 代码**——`server.xml`、`catalina.properties`、应用目录的所有相对路径都锚定在它确定的两个文件对象上。

### 3.2.2 Bootstrap：三层类加载器 + 对 Catalina 的反射调用

先说白话：Tomcat 不把 catalina.jar 放进系统 classpath（第一幕的 classpath 只有 bootstrap.jar 一个文件），而是启动时**自己建加载器**再加载真正的容器代码。这样做的目的写在 Bootstrap 的类注释里（:37-43）：把 Catalina 及其依赖"藏"在系统 classpath 之外，不让应用类看见。

【源码证据】`catalina/startup/Bootstrap.java`。加载器按 `conf/catalina.properties` 的三行配置创建（`initClassLoaders()`，:142-156）：

```java
private void initClassLoaders() {
    commonLoader = createClassLoader("common", null);      // ← 父为 null（直达 JVM 的 bootstrap 加载器）
    if (commonLoader == null) {
        commonLoader = this.getClass().getClassLoader();   // ← 无配置则退化为当前加载器（单目录环境）
    }
    catalinaLoader = createClassLoader("server", commonLoader);
    sharedLoader = createClassLoader("shared", commonLoader);
}
```

11.0.24 默认 `conf/catalina.properties` 的实况（`conf/catalina.properties:33,51,70`）：

```properties
common.loader="${catalina.base}/lib","${catalina.base}/lib/*.jar","${catalina.home}/lib","${catalina.home}/lib/*.jar"
server.loader=
shared.loader=
```

**注意这个常被误解的点**：`server.loader` 与 `shared.loader` 默认为空 → `createClassLoader` 对空配置直接返回 parent（`Bootstrap.java:161-164`），于是 `catalinaLoader == sharedLoader == commonLoader`。**现代 Tomcat 实际只有一层"common/lib"加载器**，三层结构只剩骨架——但理解它仍然重要，因为它决定了 5.1 节 WebappClassLoader 的 parent 是谁。

`init()`（:251-277）随后完成"自举"：把线程上下文加载器设为 catalinaLoader，用它加载 `Catalina` 类并反射实例化（:261-262），再反射调用 `setParentClassLoader(sharedLoader)`（:268-274）。**从这一刻起，Bootstrap 的使命只剩一件事：反射代理**。它的 `start()`（:339-346）、`stop()`（:354-357）、`stopServer()`（:365-369）全部是把方法名反射到 `catalinaDaemon` 上的三行代码——这也是类名"daemon"（守护对象）的由来。

### 3.2.3 main()：命令分发表

【源码证据】`Bootstrap.main()`（`catalina/startup/Bootstrap.java:441-511`）。命令分发（:469-500）：

```java
switch (command) {
    case "startd":                       // ← catalina.sh start 调用的其实是 startd
        args[args.length - 1] = "start";
        daemon.load(args);
        daemon.start();
        break;
    case "start":                        // ← 由 startup.sh 传入 start，先 setAwait(true)
        daemon.setAwait(true);
        daemon.load(args);
        daemon.start();
        if (null == daemon.getServer()) {
            System.exit(1);
        }
        break;
    case "stop":                         // ← shutdown.sh：向 8005 发 SHUTDOWN（3.7 节）
        daemon.stopServer(args);
        break;
    case "configtest":                   // ← 只 load 不 start，校验 server.xml
        daemon.load(args);
        ...
        System.exit(0);
```

`setAwait(true)` 是关键伏笔：它决定 `Catalina.start()` 末尾是否阻塞等待（3.6 节）。

## 3.3 第二幕：Catalina.load——Digester 把 server.xml 编译成对象树

### 3.3.1 load() 主流程

【源码证据】`catalina/startup/Catalina.java` 的 `load()`（:803-845）：

```java
public void load() {
    if (loaded) { return; }
    loaded = true;                          // ← 防重复加载
    long t1 = System.nanoTime();
    initNaming();                           // ← ① 初始化 JNDI 系统属性（javaURLContextFactory）
    parseServerXml(true);                   // ← ② Digester 解析 conf/server.xml（核心，下节展开）
    Server s = getServer();
    if (s == null) { return; }
    getServer().setCatalina(this);          // ← ③ Server ↔ Catalina 双向挂接
    getServer().setCatalinaHome(Bootstrap.getCatalinaHomeFile());
    getServer().setCatalinaBase(Bootstrap.getCatalinaBaseFile());
    initStreams();                          // ← ④ System.out/err 包上 SystemLogHandler（按线程捕获应用输出）
    try {
        getServer().init();                 // ← ⑤ 从树根开始递归 init（3.4 节）
    } catch (LifecycleException e) { ... }
    ...
}
```

### 3.3.2 Digester：XML → 对象图的"规则引擎"

先说白话：Digester 是 Tomcat 自研的 SAX 封装（`tomcat/util/digester/`），核心思想是**注册"路径→动作"规则**：遇到 `<Server>` 标签 `new StandardServer()` 压栈（`addObjectCreate`），把标签属性 `set` 到栈顶对象（`addSetProperties`），标签闭合时把栈顶对象"送"给下层对象的指定方法（`addSetNext`，如 `setServer`/`addService`/`addConnector`）。对象树就这样在栈上生长出来。

【源码证据】`createStartDigester()`（`catalina/startup/Catalina.java:484-590`）。骨架规则（节选）：

```java
digester.addObjectCreate("Server", "org.apache.catalina.core.StandardServer", "className");
digester.addSetProperties("Server");
digester.addSetNext("Server", "setServer", "org.apache.catalina.Server");       // ← 挂到 Catalina.server

digester.addObjectCreate("Server/Service", "org.apache.catalina.core.StandardService", "className");
digester.addSetNext("Server/Service", "addService", "org.apache.catalina.Service");

digester.addRule("Server/Service/Connector", new ConnectorCreateRule());        // ← 按 protocol 属性 new 连接器
digester.addSetProperties("Server/Service/Connector",
        new String[] { "executor", "sslImplementationName", "protocol" });
digester.addSetNext("Server/Service/Connector", "addConnector", "org.apache.catalina.connector.Connector");
```

容器树的规则来自四个 RuleSet（:577-582）：`EngineRuleSet("Server/Service/")`、`HostRuleSet("Server/Service/Engine/")`、`ContextRuleSet("Server/Service/Engine/Host/")`、`NamingRuleSet(...)`。其中 **HostRuleSet 还埋了独立启动最关键的一条暗线**（`catalina/startup/HostRuleSet.java:65`）：

```java
digester.addRule(prefix + "Host",
        new LifecycleListenerRule("org.apache.catalina.startup.HostConfig", "hostConfigClass"));
```

翻译成人话：**只要 server.xml 里有 `<Host>`，Digester 就自动给这个 Host 挂上 `HostConfig` 监听器**——3.5 节的"自动部署"由此而来。同理 `ContextRuleSet` 给每个 Context 挂上 `ContextConfig`（负责 web.xml/SCI 装配）。**独立运行的部署能力不是容器天生的，而是配置规则注入的监听器提供的——这一点正是独立与内嵌的分水岭**（6.5 节）。

解析入口 `parseServerXml(boolean)`（:648-735）：以 catalina.base 为根定位 `conf/server.xml`（:650-652），`digester.push(this)` 把 Catalina 自身压栈底（:713，`addSetNext("Server","setServer")` 的回调目标），`digester.parse(inputSource)` 触发解析（:718）。顺带一提，Tomcat 11 新增了 `-generateCode` 参数：把 server.xml 翻译成等价的 Java 类（`ServerXml.java`）供下次直接加载——官方自己也在给这条"配置即代码"路线做 AOT（:654-735 的 generateCode 分支）。

### 3.3.3 停止也要解析 XML：createStopDigester

`catalina.sh stop` 是**另一个 JVM 进程**。`Bootstrap.main` 的 `stop` 分支走到 `Catalina.stopServer(String[])`（:749-797）：此时 `getServer() == null`，于是用 `createStopDigester()`（:627-640）解析 server.xml——**只需要 `<Server>` 标签上的 port/shutdown 两个属性**（:634-636），拿到后开一条 socket 到 8005，把 SHUTDOWN 字符串逐字符写出（:775-782）。被停止方的那一半故事在 3.7 节。

## 3.4 init 链与 start 链：两遍递归，把整棵树点亮

### 3.4.1 全景时序图

```
Bootstrap.main("start")
 ├─ setAwait(true)                      ← Catalina.await 标志位
 ├─ load(args) → Catalina.load()
 │    ├─ Digester.parse(server.xml)     ← 对象树成型（无任何网络动作）
 │    └─ server.init()                  ← ★ init 递归（只做资源申请，不绑端口……默认情况下）
 │         ├─ globalNamingResources.init()
 │         ├─ StandardService.init()
 │         │    ├─ engine.init()          ← 容器递归：Engine → Host → Context → Wrapper 的 initInternal
 │         │    │                           （Context.init → ContextConfig.AFTER_INIT_EVENT：解析 web.xml 的准备）
 │         │    ├─ executors[i].init()
 │         │    └─ connector.init()       ← CoyoteAdapter 创建、protocolHandler.init()
 │         │                                （bindOnInit=true 默认：★此刻绑定 8080 端口！）
 │         └─ ...
 └─ start() → Catalina.start()
      ├─ server.start()                 ← ★ start 递归
      │    ├─ StandardService.startInternal()
      │    │    ├─ engine.start()        ← Engine → Host(HostConfig 部署 webapps) → Context(应用启动)
      │    │    │                          → Wrapper(loadOnStartup Servlet init) 逐层向下
      │    │    ├─ executors[i].start()  ← 工作线程池就绪
      │    │    ├─ mapperListener.start()← 路由表开始收集 Host/Context 映射
      │    │    └─ connectors[i].start() ← Poller/Acceptor 线程启动，端口正式开始 accept
      │    └─ （Server 自身 60s 周期发 PERIODIC_EVENT）
      ├─ 注册 CatalinaShutdownHook
      └─ await → server.await()          ← 主线程阻塞在 8005（3.6 节）
```

### 3.4.2 init 链：Server → Service → Connector

【源码证据】`StandardServer.initInternal()`（`catalina/core/StandardServer.java:938-955`）：注册 StringCache/MBeanFactory，`globalNamingResources.init()`，然后对每个 Service 调 `init()`。`StandardService.initInternal()`（`catalina/core/StandardService.java:522-543`）顺序固定：**engine.init() → executors init → mapperListener.init → connectors init**。

Connector 的 init（`catalina/connector/Connector.java:1255-1289`）是两个世界的接线仪式：

```java
adapter = new CoyoteAdapter(this);
protocolHandler.setAdapter(adapter);        // ← Catalina 世界把"回调接口"交给 Coyote 世界
...
try {
    protocolHandler.init();                 // ← 进入 Coyote：AbstractProtocol.init()
} catch (Exception e) { ... }
```

`protocolHandler.init()`（`coyote/AbstractProtocol.java:934-960`）最终调 `endpoint.init()`。端点的 init（`tomcat/util/net/AbstractEndpoint.java:2203-2222`）第一行就是那个重要默认值：

```java
public final void init() throws Exception {
    if (bindOnInit) {                       // ← AbstractEndpoint.java:1291: private boolean bindOnInit = true;
        bindWithCleanup();                  // ← NioEndpoint:336 ServerSocketChannel.open() + bind —— 端口在此绑定！
        bindState = BindState.BOUND_ON_INIT;
    }
    ...                                     // JMX 注册
}
```

**独立运行时 8080 端口在 init 阶段就已绑定**（bindOnInit 默认 true）——只是 accept 线程还没启动，连接进不来。记住这个默认值，第六章会看到 Boot 把它改成了 false，端口绑定被推迟到所有 Bean 就绪之后：这是两种启动方式最精妙的一处差异。

### 3.4.3 start 链：Service 起容器、起线程池、最后起连接器

【源码证据】`StandardService.startInternal()`（`catalina/core/StandardService.java:424-455`）：

```java
setState(LifecycleState.STARTING);
// Start our defined Container first
if (engine != null) {
    engine.start();                         // ← 先容器：Engine 树点亮，应用开始部署
}
for (Executor executor : findExecutors()) {
    executor.start();                       // ← 再线程池
}
mapperListener.start();                     // ← 路由表激活
// Start our defined Connectors second
for (Connector connector : findConnectors()) {
    if (connector.getState() != LifecycleState.FAILED) {
        connector.start();                  // ← 最后连接器：此刻才开始 accept
    }
}
```

"**容器先起、连接器后起**"是刻意的：保证连接器开始收流量时，路由表（Mapper）里已经有可用的 Host/Context 映射，请求不会 404 在半路上。

Connector.startInternal（`catalina/connector/Connector.java:1300-1323`）把启动权继续下放给 `protocolHandler.start()`（`AbstractProtocol.java:968-978`）→ `endpoint.start()`（`AbstractEndpoint.java:2279-2285`，未绑定时此刻才 bind）→ `startInternal()`。NioEndpoint 的 startInternal（`tomcat/util/net/NioEndpoint.java:370-381`）做最后一件事——**把两条常驻线程拉起来**：

```java
poller = new Poller();
Thread pollerThread = new Thread(poller, getName() + "-Poller");
pollerThread.setPriority(threadPriority);
pollerThread.setDaemon(true);               // ← Poller 是 daemon
pollerThread.start();
startAcceptorThread();                      // ← AbstractEndpoint.java:2291-2300，同样 setDaemon(getDaemon())
```

（`AbstractEndpoint.java:1680`：`private boolean daemon = true;` ——**Tomcat 自己的所有线程默认全是 daemon 线程**。独立模式下 JVM 不退出靠的是 main 主线程阻塞在 await()；内嵌模式下靠 Boot 另起的一条非 daemon 容器线程——6.8 节正面回答这个问题。）

## 3.5 部署流水线：webapps 里的应用是怎么"装进去"的

### 3.5.1 HostConfig：应用的发现与创建

先说白话：HostConfig 挂在 Host 上（3.3.2 节那条 Digester 暗线），监听三种事件——`START_EVENT`（Host 启动，首次部署）、`PERIODIC_EVENT`（后台线程周期触发，热部署检查）、`STOP_EVENT`。它的核心工作是扫三个地方：`conf/<Engine>/<Host>/` 下的 XML 描述符、appBase（默认 `webapps/`）下的 WAR、appBase 下的目录。

【源码证据】`catalina/startup/HostConfig.java`。事件分发（:266-292）：

```java
switch (event.getType()) {
    case Lifecycle.PERIODIC_EVENT -> check();     // ← 热部署/热加载的周期检查入口
    case Lifecycle.BEFORE_START_EVENT -> beforeStart();
    case Lifecycle.START_EVENT -> start();        // ← start() 内部末尾调 deployApps()
    case Lifecycle.STOP_EVENT -> stop();
}
```

首次部署 `deployApps()`（:401-411）三条线并行铺开：

```java
protected void deployApps() {
    migrateLegacyApps();
    File appBase = host.getAppBaseFile();
    File configBase = host.getConfigBaseFile();
    String[] filteredAppPaths = filterAppPaths(appBase.list());
    // Deploy XML descriptors from configBase
    deployDescriptors(configBase, configBase.list());    // ← ① conf/<Engine>/<Host>/*.xml（可指定任意 docBase）
    // Deploy WARs
    deployWARs(appBase, filteredAppPaths);               // ← ② appBase/*.war（按 unpackWARs 决定是否解压）
    // Deploy expanded folders
    deployDirectories(appBase, filteredAppPaths);        // ← ③ appBase/目录
}
```

每条线的产物都是一个 `StandardContext`：HostConfig 负责给 Context 补齐类名、docBase、context.xml 合并，然后 `host.addChild(context)`——**Context 的生命周期从 addChild 开始，启动则由 2.2 节看到的容器递归（Host.start → children 并行 start）驱动**。

### 3.5.2 StandardContext.startInternal：一个 Web 应用的启动全景

Context 是整棵树上最复杂的组件。先说白话：它的启动 = "准备资源目录和类加载器 → 触发装配事件（web.xml/SCI 在此生效）→ 依次启动子组件（Servlet/Filter/Listener）→ 打开可用开关"。

【源码证据】`catalina/core/StandardContext.java` 的 `startInternal()`（:4355-4670），主干（有删节）：

```java
// (1) 资源根：/WEB-INF/classes、/WEB-INF/lib/*.jar、jar 内 META-INF/resources 的统一视图
if (getResources() == null) { setResources(new StandardRoot(this)); }
resourcesStart();

// (2) 类加载器：每个应用一个 WebappLoader（内部 new ParallelWebappClassLoader，5.1 节）
if (getLoader() == null) {
    WebappLoader webappLoader = new WebappLoader();
    webappLoader.setDelegate(getDelegate());
    setLoader(webappLoader);
}

// (3) JNDI：挂 NamingContextListener，建 java:comp/env 树
if (ok && isUseNaming()) { ... addLifecycleListener(ncl); ... }

ClassLoader oldCCL = bindThread();            // (4) 线程上下文加载器临时切换为 webapp CL
try {
    ((Lifecycle) loader).start();             // (5) ★ webapp 类加载器在此创建（WebappLoader.startInternal:331）

    fireLifecycleEvent(CONFIGURE_START_EVENT, null);   // (6) ★★ ContextConfig 在此被唤醒！
    //  → ContextConfig.configureStart()：web.xml 解析、碎片排序、注解扫描、SCI 注册、
    //    把 servlet/filter/listener 的定义写进 Context（下节展开）

    for (Container child : findChildren()) {           // (7) 子容器（Wrapper）启动
        if (!child.getState().isAvailable()) { child.start(); }
    }
    ((Lifecycle) pipeline).start();                    // (8) Context 管道启动

    ...                                                 // (9) 会话管理器 Manager（无集群则 new StandardManager）

    for (Map.Entry<ServletContainerInitializer,Set<Class<?>>> entry : initializers.entrySet()) {
        entry.getKey().onStartup(entry.getValue(), getServletContext());   // (10) ★★ SCI.onStartup
    }
    if (!listenerStart()) { ok = false; }               // (11) 应用级 Listener（ServletContextListener.contextInitialized）
    if (!filterStart()) { ok = false; }                 // (12) Filter 实例化 + init
    if (!loadOnStartup(findChildren())) { ok = false; } // (13) load-on-startup 的 Servlet：wrapper.load()
    super.threadStart();                                // (14) 加入后台处理线程族
} finally {
    unbindThread(oldCCL);
}
...
if (ok) { setState(LifecycleState.STARTING); }          // (15) 置 STARTING = isAvailable()==true
```

三个星级标注是理解"注册体系"的关键：**(6)** 是"容器把配置信息读进来"的时刻（ContextConfig），**(10)(11)(12)(13)** 是"应用代码拿到控制权"的时刻。Servlet 规范把整个应用启动切成这四段回调，Spring Boot 的整套内嵌装配（6.5 节）正是踩着 (10) 这一级台阶进来的。

### 3.5.3 ContextConfig：web.xml → 碎片 → 注解 → SCI 的合并总装

【源码证据】`catalina/startup/ContextConfig.java`。`lifecycleEvent()`（:287-307）按事件分发（`AFTER_INIT_EVENT→init()`、`CONFIGURE_START_EVENT→configureStart()`）；`configureStart()`（:1033）在安全检查后调 `webConfig()`（:1287-1420）。webConfig 的注释里明确写着"Step 1"到"Step 11"的完整流水线，主干：

```java
// Step 1. 识别应用内 JAR 与容器级 JAR，解析各自的 web-fragment.xml
Map<String,WebXml> fragments = processJarsForWebFragments(webXml, webXmlParser);
// Step 2. 按绝对/相对顺序排序碎片
Set<WebXml> orderedFragments = WebXml.orderWebFragments(webXml, fragments, sContext);
// Step 3. 扫描 ServletContainerInitializer（META-INF/services/jakarta.servlet.ServletContainerInitializer）
if (ok) { processServletContainerInitializers(); }
// Steps 4 & 5. 注解扫描（@WebServlet 等；metadata-complete=false 时）
if (!webXml.isMetadataComplete() || !typeInitializerMap.isEmpty()) { processClasses(webXml, orderedFragments); }
// Step 6. web-fragment.xml 合并进主 web.xml；Step 7a/7b 合并 tomcat-web.xml 与全局默认 conf/web.xml
// Step 9. configureContext(webXml)：把合并结果注册进 Context（addServletMapping/addFilterDef/...）
// Step 11. 把 @HandlesTypes 匹配出的类集合交给各 SCI（context.addServletContainerInitializer）
```

`processServletContainerInitializers()`（:1850-1895）用 `WebappServiceLoader` 走标准 `ServiceLoader` 协议发现 SCI，再按 `@HandlesTypes` 决定要喂给它哪些类——Spring 的 `SpringServletContainerInitializer`（带 `@HandlesTypes(WebApplicationInitializer.class)`）在 WAR 部署模式下就是在这里被找到的（《Spring MVC.md》2.5 节"轨道 A"的终点）。

### 3.5.4 Wrapper.load：Servlet 实例的诞生

【源码证据】`catalina/core/StandardWrapper.java`：`load()`（:705）→ `loadServlet()`（反射 `servletClass`、调 `createServlet` 走 InstanceManager 注入、`servlet.init(facade)`），`allocate()`（:567）在请求期做单例复用/单线程模型分池。3.5.2 节 (13) 的 `loadOnStartup(findChildren())` 就是按 `loadOnStartup` 值从小到大逐个调 `wrapper.load()`——**内嵌模式下 Boot 把这一步整个接管了（6.7 节）**。

## 3.6 第三幕：端口开始 accept 与主线程阻塞

应用部署完毕、连接器 start 后，Acceptor/Poller 线程就位（4.1 节展开），HTTP 请求开始被处理。控制流回到 `Catalina.start()` 尾部（`catalina/startup/Catalina.java:868-924`）：

```java
// Register shutdown hook
if (useShutdownHook) {
    if (shutdownHook == null) { shutdownHook = new CatalinaShutdownHook(); }
    Runtime.getRuntime().addShutdownHook(shutdownHook);   // ← SIGTERM/SIGINT 兜底：直接 stop()（:1110-1136）
    ...
}
if (await) {          // ← Bootstrap.main 里 setAwait(true) 设置的
    await();          // → getServer().await()：主线程从此阻塞
    stop();           // await 返回（收到 SHUTDOWN）后收尾
}
```

【源码证据】`StandardServer.await()`（`catalina/core/StandardServer.java:510-632`）。这段代码同时是"关闭协议"的定义：

```java
public void await() {
    if (getPortWithOffset() == -2) {          // ← 端口 -2：不等待（留给"由别人管生命周期"的嵌入场景）
        return;
    }
    ...
    if (getPortWithOffset() == -1) {          // ← 端口 -1：纯 sleep 轮询 stopAwait 标志
        while (!stopAwait) { Thread.sleep(10000); }
        ...
    }
    awaitSocket = new ServerSocket(getPortWithOffset(), 1, InetAddress.getByName(address));  // ← 监听 8005
    while (!stopAwait) {
        ...
        socket = serverSocket.accept();        // ← 阻塞等连接（shutdown.sh 的 socket）
        socket.setSoTimeout(10 * 1000);
        // 读取命令（限长防 DoS，:569-583）
        ...
        boolean match = command.toString().equals(shutdown);   // :612 与 server.xml 的 shutdown 属性比对
        if (match) { break; }                                   // ← SHUTDOWN！跳出循环
        ...
    }
    ...
}
```

**端口 -1/-2 的语义是官方给嵌入方的口子**——`startup.Tomcat.getServer()` 里那句 `server.setPort(-1)`（`catalina/startup/Tomcat.java:625`）正是用的它。但有趣的是 Boot 并没有走这条路：Boot 的 `TomcatWebServer` 起了自己的非 daemon 线程来调 `server.await()`（6.8 节），比 sleep 轮询更优雅。

至此 JVM 存活的原因完全清楚：**独立模式下所有 Tomcat 线程都是 daemon，唯一非 daemon 的是 main 主线程，它阻塞在 8005 端口的 accept() 上**。进程退出 = 主线程从 await 返回 → Catalina.stop() 走完 stop/destroy 链 → main 返回且无其他非 daemon 线程 → JVM 退出。

## 3.7 停止：两条路径殊途同归

| 路径 | 触发方 | 机制 |
|---|---|---|
| **优雅关闭** | `catalina.sh stop`（另一 JVM） | 向 8005 发 SHUTDOWN 字符串（3.3.3 节）；运行方 await 返回 → `Catalina.stop()`（:930-965）：先摘 shutdown hook 防二次停止，再 `server.stop(); server.destroy()` |
| **信号兜底** | kill/SIGTERM、Ctrl+C | JVM 退出钩子 `CatalinaShutdownHook`（:1110-1136）→ 同样的 `Catalina.stop()` |

`server.stop()/destroy()` 触发与启动严格镜像的逆序递归。中间有一个值得看的细节——Service 停止时的"**先礼后兵**"（`StandardService.stopInternal()`，`catalina/core/StandardService.java:459-505`）：

```java
// 先让每个连接器优雅关闭 server socket（等待在途请求处理完，默认给 5s）
for (Connector connector : connectors) {
    connector.getProtocolHandler().closeServerSocketGraceful();
}
long waitMillis = gracefulStopAwaitMillis;
if (waitMillis > 0) {
    for (Connector connector : connectors) {
        waitMillis = connector.getProtocolHandler().awaitConnectionsClose(waitMillis);
    }
}
// 再 pause（停止 accept 新连接）
for (Connector connector : connectors) { connector.pause(); }
setState(LifecycleState.STOPPING);
if (engine != null) { engine.stop(); }          // ← 容器在连接器已停后停止
for (Connector connector : connectors) { ... connector.stop(); }
```

stop 链之后 destroy 链释放资源（连接器 destroy → 端点 unbind），一切与启动镜像对称。

## 3.8 本章小结（独立启动 12 步清单）

1. `catalina.sh` 设环境 → 启动 `Bootstrap`（classpath 仅 bootstrap.jar）。
2. Bootstrap 静态块确定 `catalina.home/base`（`Bootstrap.java:65-124`）。
3. `initClassLoaders()` 建 common/server/shared 加载器（默认塌缩为一层 common）。
4. 反射加载 Catalina，注入 sharedLoader。
5. `Catalina.load()`：initNaming → Digester 解析 server.xml（顺带给 Host 挂 HostConfig、给 Context 挂 ContextConfig）。
6. `server.init()` 递归：Service → Engine/Host/Context/Wrapper + Connector（bindOnInit=true，端口此刻绑定）。
7. `Catalina.start()` → `server.start()` 递归：容器树先行（HostConfig 部署 webapps：descriptor/war/目录三线）。
8. Context 启动 20 步：建 WebappClassLoader → CONFIGURE_START（web.xml/碎片/注解/SCI 总装）→ SCI.onStartup → listenerStart → filterStart → loadOnStartup。
9. Executors、MapperListener 就绪 → Connectors start：Poller/Acceptor 两条 daemon 线程上岗，开始 accept。
10. 注册 CatalinaShutdownHook → 主线程阻塞在 `StandardServer.await()`（8005）——**这是 JVM 不退出的唯一非 daemon 线程**。
11. 收 SHUTDOWN（或 kill 触发 hook）→ `Catalina.stop()`：连接器优雅关闭 → pause → 容器 stop → destroy 镜像递归。
12. main 返回，JVM 退出。

下一章视角一转：启动完毕的机器收到了第一个 HTTP 请求。

---
# 四、一次请求的一生：Coyote 与 Catalina 的接力（重点 B）

## 4.0 全景图

先给全景，后拆解。一次 `GET /app/api/hello` 从网卡到 `HttpServlet.service()` 再返回，共经过 10 个节点：

```
浏览器 TCP 连接
  │
  ▼ ① Acceptor（1 条线程，阻塞 accept）                         AbstractEndpoint.java:2291 / Acceptor.java:133-165
  │    countUpOrAwaitConnection（maxConnections 限流）→ serverSocketAccept()
  ▼ ② setSocketOptions：包 NioChannel、注册进 Poller            NioEndpoint.java:559-601
  │    configureBlocking(false) → poller.register(wrapper)
  ▼ ③ Poller（1 条线程，Selector 多路复用）                     NioEndpoint.java:898-972
  │    selector.select() → processKey(OPEN_READ) → processSocket()
  ▼ ④ 工作线程池（默认 200 线程的内部 ThreadPoolExecutor）       AbstractEndpoint.java:1929-1944 / 2093-2123
  │    executor.execute(SocketProcessor) → Processor 处理
  ▼ ⑤ Http11Processor：解析请求行/头 → Coyote Request/Response   coyote/http11/Http11Processor
  ▼ ⑥ Adapter.service()：Coyote → Catalina 的唯一大门            coyote/AbstractProtocol 持 adapter；Connector.init:1263-1265
  │    CoyoteAdapter.service()                                  connector/CoyoteAdapter.java:305-400
  │    ├─ 创建/复用 Catalina Request/Response（:306-319）
  │    ├─ postParseRequest：解码 URI、会话定位、Mapper 路由（:561, :700）
  ▼ ⑦ Engine 管道：StandardEngineValve.invoke（:54）→ Host 管道
  │    StandardHostValve.invoke（:78）→ Context 管道
  │    StandardContextValve.invoke（:54）→ Wrapper 管道
  ▼ ⑧ StandardWrapperValve.invoke（:84）
  │    wrapper.allocate() → ApplicationFilterFactory.createFilterChain（ApplicationFilterFactory.java:54）
  ▼ ⑨ ApplicationFilterChain.doFilter → internalDoFilter        ApplicationFilterChain.java:88-132
  │    Filter 链走完 → servlet.service(request, response)（:132）★ 你的代码第一次被调用
  ▼ ⑩ 响应写回：ServletOutputStream → Coyote Response → NioChannel → 客户端
  （keep-alive：Processor 归还 Poller 等下一次读事件；超时/上限到达则关闭连接）
```

《Spring MVC.md》2.1 节已从 Servlet 规范视角画过同一张图（当时标注"Tomcat 实现细节，另见 Tomcat 文档"）——本章就是那张欠你的"另见"。

## 4.1 NioEndpoint：三级线程模型（本章核心 A）

先说白话：Tomcat 的网络层是"**一个收银台（Acceptor）+ 一个叫号屏（Poller）+ 一群厨师（线程池）**"的模型。Acceptor 只负责把新连接收进来登记；Poller 拿着 Selector 盯着几百个连接"谁有数据可读了"；真正干解析和业务的是线程池里的工人线程。任何时刻慢的都不是 I/O 监视，而是厨师——这就是 `maxThreads` 调优的对象。

### 4.1.1 Acceptor：accept 限流与登记

【源码证据】`tomcat/util/net/Acceptor.java` 的 run 循环（:120-178）：

```java
while (running) {
    state = AcceptorState.RUNNING;
    try {
        endpoint.countUpOrAwaitConnection();       // :133 ← maxConnections 打满就在此等（信号量限流）
        ...
        socket = endpoint.serverSocketAccept();    // :145 ← 阻塞 accept（NioEndpoint: serverSock.accept()）
        ...
        if (!stopCalled && !endpoint.isPaused()) {
            // setSocketOptions() will hand the socket off to an appropriate processor if successful
            if (!endpoint.setSocketOptions(socket)) {   // :165 ← 登记进 Poller
                endpoint.closeSocket(socket);
            }
        } else { endpoint.destroySocket(socket); }
    } catch (Throwable t) { ... }
}
```

`setSocketOptions()`（`NioEndpoint.java:559-601`）做四件事：从 `nioChannels` 回收栈里取/新建 `NioChannel`（:564-568，**对象池复用**）、包上 `NioSocketWrapper` 并放入 `connections` 表（:572-574）、`configureBlocking(false)`（:579）、**`poller.register(socketWrapper)`（:587）**——从此这条连接归 Poller 管，Acceptor 转身去收下一个连接。

### 4.1.2 Poller：Selector 主循环

【源码证据】`NioEndpoint.Poller.run()`（:898-972）：

```java
public void run() {
    while (true) {
        hasEvents = events();                       // ← 处理注册/写事件队列
        if (wakeupCounter.getAndSet(-1) > 0) {
            keyCount = selector.selectNow();        // ← 有待处理事件就非阻塞扫
        } else {
            keyCount = selector.select(selectorTimeout);
        }
        ...
        Iterator<SelectionKey> iterator = keyCount > 0 ? selector.selectedKeys().iterator() : null;
        while (iterator != null && iterator.hasNext()) {
            SelectionKey sk = iterator.next(); iterator.remove();
            NioSocketWrapper socketWrapper = (NioSocketWrapper) sk.attachment();
            if (socketWrapper != null) { processKey(sk, socketWrapper); }   // ← 可读/可写事件分发
        }
        timeout(keyCount, hasEvents);               // ← 顺带做超时扫描
    }
}
```

`processKey` 对可读事件调 `processSocket(socketWrapper, SocketEvent.OPEN_READ, true)`（:1004 一带）。`processSocket` 在 `AbstractEndpoint`（:2093-2123）：从 `processorCache` 取（或新建）一个 `SocketProcessor` 任务，**丢给 Executor**：

```java
Executor executor = getExecutor();
if (dispatch && executor != null) {
    executor.execute(sc);      // ← 至此离开"网络线程"，进入"工作线程"
} else {
    sc.run();
}
```

### 4.1.3 工作线程池与虚拟线程开关

【源码证据】`AbstractEndpoint.createExecutor()`（:1929-1944）：

```java
public void createExecutor() {
    internalExecutor = true;
    if (getUseVirtualThreads()) {
        executor = new VirtualThreadExecutor(getName() + "-virt-");   // ← JDK 21+ 虚拟线程：每请求一线程
    } else {
        TaskQueue taskqueue = new TaskQueue(maxQueueSize);
        TaskThreadFactory tf = new TaskThreadFactory(getName() + "-exec-", daemon, getThreadPriority());
        executor = new ThreadPoolExecutor(getMinSpareThreads(), getMaxThreads(), ...);
        taskqueue.setParent((ThreadPoolExecutor) executor);           // ← Tomcat 定制队列，见下
    }
}
```

默认参数即 7.2 节调优表的出处：`maxThreads=200`、`minSpareThreads=10`、`maxConnections=8192`、`acceptCount=100`。`TaskQueue`（`tomcat/util/threads/TaskQueue.java`）重写了 offer：**仅当现有线程数 < maxThreads 时才入队，否则让 ThreadPoolExecutor 继续造线程**——这是对 JDK 线程池"队列无限堆积"语义的定向修正，也正是 Tomcat 并发模型的灵魂之一。

**虚拟线程**：`useVirtualThreads` 为 true 时执行器换成 `VirtualThreadExecutor`，请求处理（⑤-⑨ 全程）跑在虚拟线程上，而 Acceptor/Poller 仍是平台线程（《Spring MVC.md》第六章已实证 Boot 侧开关链 `spring.threads.virtual.enabled=true` → `TomcatVirtualThreadsWebServerFactoryCustomizer` → `protocolHandler.setExecutor(...)`——底层落点就是这里的 createExecutor 分支）。

## 4.2 Coyote 层：Processor 解析与 Adapter 交接

工作线程拿到 SocketProcessor 后进入协议处理器：`Http11Processor`（`coyote/http11/Http11Processor.java`）用 `Http11InputBuffer` 从 NioChannel 读字节、解析请求行与请求头，填进 **Coyote 世界**的 `org.apache.coyote.Request/Response`（纯数据容器，没有任何 Servlet 痕迹）。然后按 Handler 契约调用 `adapter.service(req, res)`——这个 adapter 正是 3.4.2 节 Connector.init 时挂进来的 `CoyoteAdapter`。

**Adapter 是两个世界唯一的接口**（`coyote/Adapter.java`，4 个方法）。Coyote 不 import 任何 catalina 类；Catalina 不 import 任何 socket 类。HTTP/2、AJP 的处理器同样经由各自的 Adapter 调进同一棵容器树——1.2 节"连接器与容器正交"的落点。

## 4.3 CoyoteAdapter.service：请求对象、路由与会话

【源码证据】`catalina/connector/CoyoteAdapter.java` 的 `service()`（:305-400）：

```java
public void service(org.apache.coyote.Request req, org.apache.coyote.Response res) throws Exception {
    Request request = (Request) req.getNote(ADAPTER_NOTES);
    Response response = (Response) res.getNote(ADAPTER_NOTES);
    if (request == null) {
        request = connector.createRequest(req);       // ← Catalina Request 首次创建后存 note 复用
        response = connector.createResponse(res);
        request.setResponse(response); response.setRequest(request);
        req.setNote(ADAPTER_NOTES, request); res.setNote(ADAPTER_NOTES, response);
    }
    ...
    boolean postParseSuccess = false;
    try {
        postParseSuccess = postParseRequest(req, request, res, response);   // :342 ★
        if (postParseSuccess) {
            request.setAsyncSupported(connector.getService().getContainer().getPipeline().isAsyncSupported());
            connector.getService().getContainer().getPipeline().getFirst().invoke(request, response);   // :347 ★
        }
        ...
        if (!request.isAsync()) {
            request.finishRequest(); response.finishResponse();             // 刷新响应、收尾
        }
    } ...
}
```

`postParseRequest()`（:561-760）是请求进入容器前的最后梳理：URI 规范化与解码（处理 `uriEncoding`、禁止路径穿越）、代理头转换（`remoteIpHeader`）、**会话定位**（URL 重写 `;jsessionid=` 与 Cookie 两种来源）、最后把请求交给 **Mapper** 做三级路由（:700）：

```java
connector.getService().getMapper().map(serverName, decodedURI, version, request.getMappingData());
```

先说白话 Mapper：它是 Service 级的**只读路由表**（`tomcat/util/http/mapper/Mapper.java`），每当 Host/Context/Wrapper 挂接、映射变化，`MapperListener` 就把变更同步进几棵前缀树（精确 > 通配 > 扩展名 > 默认）。`map()` 一次性把"命中哪个 Host、哪个 Context、哪个 Wrapper"连同欢迎页/通配规则写进 `MappingData`——后续四级 Valve 直接取用，不再逐级查找。

## 4.4 Pipeline 逐级下潜：四个 Valve 各司其职

请求从 `getPipeline().getFirst().invoke()`（4.3 节 :347）进入 Engine 管道（默认无附加 Valve 时 First 即 basic `StandardEngineValve`）。四个 basic Valve 的职责（行号 11.0.24 实测）：

| 层 | Valve | invoke | 白话职责 |
|---|---|---|---|
| Engine | `StandardEngineValve` | `catalina/core/StandardEngineValve.java:54` | 按 `request.getHost()`（Mapper 已定）选中 Host，`host.getPipeline().getFirst().invoke()`——**这是唯一会校验 Host 头的层**（拒绝未知虚拟主机的请求） |
| Host | `StandardHostValve` | `StandardHostValve.java:78` | 选中 Context、绑定应用类加载器；**响应码异常时接手错误页机制**（`status()`/`throwable()`，ErrorReportValve 同层协作——Boot 的 `/error` 兜底页也常在这一层之后被 MVC 错误处理接管，见《Spring Boot.md》5.7 节） |
| Context | `StandardContextValve` | `StandardContextValve.java:54` | 安全闸门：拒绝 `/WEB-INF/`、`/META-INF/` 直访；按 `request.getWrapper()` 进 Wrapper 管道 |
| Wrapper | `StandardWrapperValve` | `StandardWrapperValve.java:84` | 本章 4.5 节的主角 |

【源码证据】Wrapper 级主流程（`StandardWrapperValve.java:84` 起主干节选）：

```java
public void invoke(Request request, Response response) throws IOException, ServletException {
    ...
    if (!unavailable) {
        servlet = wrapper.allocate();                    // ← 单例 Servlet：首次在此 init，之后复用
    }
    ...
    // Create the filter chain for this request
    ApplicationFilterChain filterChain =
            ApplicationFilterFactory.createFilterChain(request, wrapper, servlet);   // ← 每请求一条链
    try {
        if ((servlet != null) && (filterChain != null)) {
            ...
            filterChain.doFilter(request.getRequest(), response.getResponse());   // ★ 进入 Filter/Servlet 世界
        }
    } catch (...) { ... exception(request, response, e); }   // ← 异常转错误页
    finally {
        wrapper.deallocate(servlet); ...
    }
}
```

## 4.5 ApplicationFilterChain：Filter 匹配与 service 调用

先说白话：Filter 链不是启动时串好的一条静态链，而是**每个请求现配的**。`createFilterChain` 按 `web.xml`/注解里登记的 `FilterMap` 顺序扫两遍：第一遍匹配 URL 模式，第二遍匹配 Servlet 名，命中者依序入链。

【源码证据】`catalina/core/ApplicationFilterFactory.createFilterChain()`（`catalina/core/ApplicationFilterFactory.java:54-150`），URL 匹配段：

```java
String requestPath = FilterUtil.getRequestPath(request);
String servletName = wrapper.getName();
// Add the relevant path-mapped filters to this filter chain
for (FilterMap filterMap : filterMaps) {
    if (!matchDispatcher(filterMap, dispatcher)) { continue; }
    if (!FilterUtil.matchFiltersURL(filterMap, requestPath)) { continue; }
    ApplicationFilterConfig filterConfig =
            (ApplicationFilterConfig) context.findFilterConfig(filterMap.getFilterName());
    ...
    filterChain.addFilter(filterConfig);
}
// Add filters that match on servlet name second
for (FilterMap filterMap : filterMaps) { ... matchFiltersServlet(filterMap, servletName) ... }
```

链的执行端 `ApplicationFilterChain`（`catalina/core/ApplicationFilterChain.java`）——递归式责任链：

```java
@Override
public void doFilter(ServletRequest request, ServletResponse response) throws IOException, ServletException {
    if (pos < n) {                                        // :91 链未走完：下一个 Filter
        ApplicationFilterConfig filterConfig = filters[pos++];
        Filter filter = filterConfig.getFilter();
        ...
        filter.doFilter(request, response, this);         // :100 ← Filter 的最后必须 chain.doFilter，否则链断
        return;
    }
    // We fell off the end of the chain -- call the servlet instance
    ...
    servlet.service(request, response);                   // :132 ★★ 业务代码的起点
}
```

**132 行是全文最重要的一行代码**：往上全是 Tomcat，往下全是应用（Spring MVC 场景即 DispatcherServlet——《Spring MVC.md》第四章从这行无缝接续）。另外注意 :97-98 的 asyncSupported 校验：链上任何一个 Filter 不支持异步，整个请求的 async 开关就被关掉——这是"@WebFilter(asyncSupported=false) 导致 WebSocket 握手失败"这类疑难杂症的病根。

## 4.6 响应写回与连接复用

Servlet 写响应 = 写 `ServletOutputStream` → Catalina Response → Coyote Response → `Http11OutputBuffer` → NioChannel。请求完成后（无异步），`CoyoteAdapter.service` 收尾（4.3 节 :375-377 的 `finishRequest/finishResponse`），Processor 回到 Handler 状态机判断：**keep-alive 且未达 `maxKeepAliveRequests` 上限 → 连接与 Processor 一并归还，Processor 再注册回 Poller 等下一次读事件**（HTTP/1.1 默认复用，上限默认 100）；否则关闭连接。`maxConnections`/`keepAliveTimeout`/`maxKeepAliveRequests` 三参数把整个连接生命周期锁在 4.1 节的模型里。

## 4.7 本章小结（请求生命周期 10 步清单）

1. Acceptor accept 新连接（maxConnections 信号量限流）；
2. 包 NioChannel、登记进 Poller；
3. Poller 监视到可读，组装 SocketProcessor 任务；
4. 丢给线程池（默认 200 工人；可切虚拟线程执行器）；
5. Http11Processor 解析请求行/头为 Coyote Request；
6. CoyoteAdapter.service：创建/复用 Catalina Request，postParseRequest 解码 + 会话 + **Mapper 三级路由**；
7. `getFirst().invoke()` 下潜四级管道（Engine→Host→Context→Wrapper）；
8. WrapperValve：allocate Servlet + 现配 FilterChain；
9. FilterChain 递归执行，走完调 `servlet.service()`（ApplicationFilterChain.java:132）；
10. 响应原路写回，Processor 归还 Poller 等复用（keep-alive）或关闭。

独立与内嵌在这个维度**零差异**：两种模式下跑的是同一套 NioEndpoint、同一棵四级 Valve 管道。差异全部集中在"这棵树怎么建、Servlet 怎么注册、谁管生命周期"——这正是第六章的主题。

---

# 五、支撑设施：类加载隔离、会话、JSP、线程池与日志

## 5.1 类加载器体系：WebappClassLoader 的"半正交"委派

先说白话：JVM 标准委派是"先问父加载器"，但 Web 应用要求"**我的 /WEB-INF/classes 优先**"（否则应用带的库版本会被容器侧同名列遮蔽），又要求 **JDK 核心类与容器类不可伪造**。Tomcat 的解法是一套介于"先本地"与"先父"之间的混合委派。

### 5.1.1 容器侧：三层加载器（回顾与落点）

3.2.2 节已实证：Bootstrap 建 common/server/shared 三层，默认 `server.loader`/`shared.loader` 为空而塌缩为一层 common（`catalina.properties:33,51,70`）。它的落点是：Digester 给 Engine 树设的 `parentClassLoader`（`Catalina.java:585` 的 `SetParentClassLoaderRule`）就是这个 sharedLoader——Web 应用类加载器的 parent。

### 5.1.2 应用侧：WebappLoader 与 loadClass 五步曲

每个 Context 一个 `WebappLoader`（`catalina/loader/WebappLoader.java`），其 `startInternal()`（:318-360）创建真正的加载器——默认 `ParallelWebappClassLoader`（支持按类名并行的锁粒度）：

```java
classLoader = createClassLoader();
classLoader.setResources(context.getResources());
classLoader.setDelegate(this.delegate);
...
classLoader.start();
```

【源码证据】委派次序全文在 `WebappClassLoaderBase.loadClass(String, boolean)`（`catalina/loader/WebappClassLoaderBase.java:1170-1300`），注释编号即步骤：

```java
// (0)   findLoadedClass0：本地已加载缓存
// (0.1) findLoadedClass：JVM 层缓存
// (0.2) javaseLoader（JDK 平台加载器）：直接放行 Java SE 类——SRV.10.7.2，应用永远无法伪造 java.* 
// (1)   delegate || filter(name)：delegate=true（父先）或类名命中容器包过滤器（jakarta.*/org.apache.*/sun.*...）
//        → 先委托 parent；注：filter() 在 :2387，把 servlet.jsp.jstl 之外的 jakarta/javax 全划给父
// (2)   findClass：★ 查本地仓库（/WEB-INF/classes → /WEB-INF/lib/*.jar）
// (3)   仍未命中：无条件委托 parent（兜底）
```

**delegate 默认 false**（WebappLoader 上可配）→ 应用类先查本地：你的 `spring-core-7.0.jar` 不会被 `lib/` 下的旧版遮蔽；但 `jakarta.*`、`org.apache.*` 等容器族仍强制父先——防伪造、防两份 Servlet API 并存。**Boot 内嵌模式把 delegate 翻转为 true 且换掉加载器实现**（6.6 节），方向恰好相反，这是内嵌类加载策略的最大差异。

### 5.1.3 内存泄漏防护（顺带的"陪葬品"）

`WebappClassLoaderBase` 还内置一套卸载善后：停止时扫描并清空应用启动的线程、ThreadLocal、RMI Target（`clearReferences*` 系列，`checkThreadLocalsForLeaks` 等）——"改个版本重启应用不重启 JVM"在 Tomcat 里能活下来，靠的就是这些代码。独立模式热部署依赖它们；内嵌模式应用与 JVM 同生共死，Boot 甚至用 `DisableReferenceClearingContextCustomizer` 把部分清理关掉（6.6 节）。

## 5.2 会话管理：StandardManager

`catalina/session/StandardManager.java`：默认 Manager，会话存内存（ConcurrentHashMap），靠 3.5.2 节 (14) 的后台线程周期 `backgroundProcess()` 驱动过期扫描；JVM 优雅停止时把会话序列化到 `SESSIONS.ser`、启动时恢复（`doLoad/doUnload`）——内嵌模式下 Boot 默认关掉持久化（`DisablePersistSessionListener`，见 6.3 节工厂源码），`server.servlet.session.persistent=true` 才打开并重定向到 Boot 指定目录。集群场景的 `DeltaManager`（会话复制）本文不展开。

## 5.3 Jasper：JSP 的编译执行

`jasper/` 子项目：`JspServlet`（注册在全局 `conf/web.xml`，映射 `*.jsp`）拦截请求 → `JspRuntimeContext` 管理 JSP → 翻译成 Servlet 源码 → Eclipse JDT 编译（无需 JDK）→ 加载执行 → **按修改时间检测重编译**。内嵌 Boot 默认注册同名 JspServlet（6.3 节工厂源码 `addJspServlet`，loadOnStartup=3）并把 JasperInitializer 显式加进 Context。Spring Boot 应用推崇模板引擎直出，JSP 常年缺席，机制从简介绍。

## 5.4 线程池：StandardThreadExecutor 与 TaskQueue

`catalina/core/StandardThreadExecutor.java` 的 `startInternal()`（:121-131）：

```java
taskqueue = new TaskQueue(maxQueueSize);
TaskThreadFactory tf = new TaskThreadFactory(namePrefix, daemon, getThreadPriority());
executor = new ThreadPoolExecutor(getMinSpareThreads(), getMaxThreads(), maxIdleTime,
        TimeUnit.MILLISECONDS, taskqueue, tf);
taskqueue.setParent(executor);
```

server.xml 的 `<Executor name="tomcatThreadPool" .../>` 即此物；Connector 用 `executor` 属性引用（Digester 的 `ConnectorCreateRule` 接线）。它与 4.1.3 节端点内置池是同一套类：显式 Executor 优先，否则端点自建。**TaskQueue 的 offer 重写（线程未满则造新线程而非入队）两处共用**——Tomcat 的高并发姿态就藏在这一个类里。

## 5.5 JULI：按应用隔离的日志

Tomcat 的日志门面是 `org.apache.juli.logging.LogFactory`（包名同 commons-logging，避免强依赖），底层实现 `DirectJDKLog`（包 JULI）——它不是"又一个日志框架"，而是给 `java.util.logging` 补上**按 ClassLoader 隔离的配置**（`ClassLoaderLogManager`：每个 webapp 加载器一套 properties）与单行格式化。独立模式 `conf/logging.properties` 全权控制；**内嵌模式里 Tomcat 的日志仍走 JULI，只是 Boot 不改它**（Boot 的 Logback/Log4j2 接管的是 Spring 自己的 commons-logging 桥），所以内嵌下你会同时看到 `org.apache.tomcat.*` 的 JULI 输出与应用的 Logback 输出。

## 5.6 本章小结

- 应用类加载委派五步曲：本地缓存 → JDK 平台类（防伪造）→ **容器包过滤器命中则父先** → 本地查找 → 父兜底；delegate 开关与过滤器共同决定"应用优先"还是"容器优先"。
- 会话默认内存 + 后台过期 + 停止时持久化；内嵌默认关闭持久化。
- Jasper 是"JSP → Servlet 源码 → JDT 编译"的即时编译器；Boot 内嵌仍会注册它（仅当类路径有 jasper）。
- TaskQueue 的"先扩线程后入队"是 Tomcat 并发语义的核心修正；独立与内嵌共用。
- JULI 只服务 Tomcat 自身日志，Boot 应用日志体系与它并行不悖。

---
# 六、Spring Boot 内嵌启动：同一台 Tomcat 的另一种活法（重点 C）

## 6.1 先说白话：主客关系的反转

第三章的独立启动，时序是"**容器先起、应用后进**"：Tomcat 是主人，从 `startup.sh` 一路点亮自己，然后 HostConfig 把你的 war 拾进来。内嵌启动把这个关系整个反转：**你的 main 方法是主人，Tomcat 是 Spring 容器 refresh 过程中"顺手 new 出来的一个对象"**。

但这句白话容易产生误解——以为是"另一台阉割版 Tomcat"。读完前五章你已经知道：内嵌模式下跑的是**同一份 11.0.24 源码、同一棵 StandardServer→StandardEngine 树、同一条 NioEndpoint 请求链**（第三章 3.4 节的时序图在第六章会几乎原样重放）。变化的只有四件事：

1. **谁构建对象树**：Digester 解析 server.xml → Boot 的 Java 代码直接 `new`（6.2 节）；
2. **谁部署应用**：HostConfig 扫描 webapps → Boot 程序化准备唯一一个 Context（6.3 节）；
3. **Servlet 怎么注册**：web.xml/注解扫描/SCI 发现 → ServletContextInitializer 显式回调（6.5 节）；
4. **生命周期谁接管**：Catalina 自管（await 阻塞）→ Spring 容器 refresh 时序与 SmartLifecycle（6.4/6.7 节）。

Boot 侧的 refresh 编排（onRefresh 里造服务器、finishRefresh 里 SmartLifecycle 启动）已在《Spring Boot.md》第五章逐行讲过，本章所有"Boot 为什么"之处引用该章，只补 Tomcat 侧"发生了什么"。

## 6.2 嵌入式门面：org.apache.catalina.startup.Tomcat 替你做了什么

Tomcat 官方自己提供嵌入式 API——`org.apache.catalina.startup.Tomcat`（与包内其他类同名，注意与容器整体区分）。先说白话：**它就是"不用写 server.xml"的程序化等价物**，Boot 4.0.8 的工厂直接踩在这个门面上。

【源码证据】`catalina/startup/Tomcat.java`。懒初始化的对象树（`getServer()`，:610-628）：

```java
public Server getServer() {
    if (server != null) { return server; }
    System.setProperty("catalina.useNaming", "false");     // ← 嵌入式默认关 JNDI
    server = new StandardServer();
    initBaseDir();                                          // ← 默认基于 java.io.tmpdir 建 work 目录
    ConfigFileLoader.setSource(new CatalinaBaseConfigurationSource(new File(basedir), null));
    server.setPort(-1);                                     // ← ★ await 语义切换为"轮询标志位"（3.6 节伏笔）
    Service service = new StandardService();
    service.setName("Tomcat");
    server.addService(service);
    return server;
}
```

`getEngine()`（:589-598）、`getHost()`（:575-585）、`getConnector()`（:509-524，默认 `HTTP/1.1` 即 Http11NioProtocol）同样懒创建——首次调用时逐级补齐整棵树。`addContext()`（:318）/`addWebapp()`（:232）程序化创建 Context；`addServlet()`（:340-380）程序化创建 Wrapper。`init()/start()`（:439-457）就是对树根的直接调用：

```java
public void start() throws LifecycleException {
    getServer();
    server.start();
}
```

**与独立模式的对照一目了然**：`Catalina.load()` 的五步（Digester 解析、initStreams、initNaming……）在这里被"直接 new"取代；`Bootstrap` 的类加载器铺垫完全不需要；`server.setPort(-1)` 顺手改写了 await 语义。门面还附赠 `FixContextListener`（`Tomcat.java` 内部类）——它在 Context 的 `configure_start` 事件里 `setConfigured(true)` 并处理注解装配，**顶替了独立模式下 ContextConfig 的"配置合法性"职责**。Boot 正是复用它（6.3 节工厂源码 `context.addLifecycleListener(new FixContextListener())`）。

但注意：**门面没有给 Host 挂 HostConfig**（对比 3.3.2 节 HostRuleSet 的暗线）——没有部署扫描器，这是嵌入式一切差异的源头。

## 6.3 Boot 侧全链路：工厂把树搭成什么样

Spring Boot 4.0.8 把内嵌 Tomcat 支撑收进 `module/spring-boot-tomcat` 模块。工厂入口 `getWebServer()`（`module/spring-boot-tomcat/src/main/java/org/springframework/boot/tomcat/servlet/TomcatServletWebServerFactory.java:163-167`）三步：`createTomcat()` → `prepareContext(...)` → `new TomcatWebServer(tomcat, ...)`。

### 6.3.1 createTomcat：树骨架 + 连接器定制

【源码证据】`tomcat/TomcatWebServerFactory.java:370-393`：

```java
protected Tomcat createTomcat() {
    if (this.isDisableMBeanRegistry()) {
        Registry.disableRegistry();                       // ← 默认 true（:93）：嵌入式关 MBean 注册表
    }
    Tomcat tomcat = new Tomcat();                         // ← 6.2 节的门面
    File baseDir = (getBaseDirectory() != null) ? getBaseDirectory() : createTempDir("tomcat");
    tomcat.setBaseDir(baseDir.getAbsolutePath());         // ← CATALINA_BASE 的等价物 = 临时目录
    ...
    Connector connector = new Connector(getProtocol());   // ← 默认 Http11NioProtocol（:67）
    connector.setThrowOnFailure(true);
    tomcat.getService().addConnector(connector);
    customizeConnector(connector);                        // ← port/server.address/HTTP2/SSL/压缩/virtual threads
    tomcat.setConnector(connector);
    registerConnectorExecutor(tomcat, connector);
    tomcat.getHost().setAutoDeploy(false);                // ← ★ 明确关掉部署开关（本就没有 HostConfig）
    configureEngine(tomcat.getEngine());
    ...
    return tomcat;
}
```

`customizeConnector()`（:395-420）是 `server.*` 配置（`TomcatServerProperties`）与 Tomcat 字段的接线处：`connector.setPort(...)`、地址、`Http2Protocol` 升级协议、SSL bundle、压缩，最后把全部 `TomcatProtocolHandlerCustomizer` 打到 protocolHandler 上——《Spring MVC.md》第六章实证的虚拟线程定制器就在这批 customizer 里（`setExecutor(VirtualThreadExecutor)` → 落到 4.1.3 节端点的执行器）。

### 6.3.2 prepareContext：唯一的 Context 是"程序化品"

【源码证据】`TomcatServletWebServerFactory.prepareContext()`（:169-208，节选）：

```java
TomcatEmbeddedContext context = new TomcatEmbeddedContext();          // ← 继承 StandardContext（见 6.7）
WebResourceRoot resourceRoot = (documentRootFile != null) ? new LoaderHidingResourceRoot(context)
        : new StandardRoot(context);
...
context.setDocBase(docBase.getAbsolutePath());
context.addLifecycleListener(new FixContextListener());               // ← 6.2 节门面的监听器
ClassLoader parentClassLoader = (this.resourceLoader != null) ? ... : ClassUtils.getDefaultClassLoader();
context.setParentClassLoader(parentClassLoader);                      // ← ★ 应用的类加载器直接当 parent（无三层）
...
WebappLoader loader = new WebappLoader();
loader.setLoaderInstance(new TomcatEmbeddedWebappClassLoader(parentClassLoader));  // ← 6.6 节
loader.setDelegate(true);                                             // ← ★ 父加载器优先
context.setLoader(loader);
if (this.settings.isRegisterDefaultServlet()) { addDefaultServlet(context); }   // ← 程序化补 DefaultServlet
if (shouldRegisterJspServlet()) { addJspServlet(context); addJasperInitializer(context); }
...
ServletContextInitializers initializersToUse = ServletContextInitializers.from(this.settings, initializers);
host.addChild(context);                                               // ← 应用挂上 Host
configureContext(context, initializersToUse);
postProcessContext(context);
```

对照第三章：独立模式下这一切由 HostConfig + ContextConfig + conf/web.xml 在启动期自动完成；这里全部变成工厂方法里的显式代码。**同一个 Context 组件，装配方式从"配置驱动"变成了"代码驱动"**。

`configureContext()`（:291-322）补足细节，其中最关键的一行把 Boot 的装配钩子注入 Context：

```java
protected void configureContext(Context context, Iterable<ServletContextInitializer> initializers) {
    DeferredServletContainerInitializers deferredInitializers = new DeferredServletContainerInitializers(initializers);
    if (context instanceof TomcatEmbeddedContext embeddedContext) {
        embeddedContext.setDeferredStartupExceptions(deferredInitializers);
        embeddedContext.setFailCtxIfServletStartFails(true);
    }
    context.addServletContainerInitializer(deferredInitializers, NO_CLASSES);   // ← ★★ Boot 自己当 SCI
    ...
    for (ErrorPage errorPage : getErrorPages()) { ... context.addErrorPage(...); }
    setMimeMappings(context);
    configureSession(context);                            // ← 默认关会话持久化（DisablePersistSessionListener）
    ...
    for (TomcatContextCustomizer customizer : this.getContextCustomizers()) {
        customizer.customize(context);
    }
}
```

**`addServletContainerInitializer(deferredInitializers, NO_CLASSES)` 是 Boot 与 Servlet 规范的咬合齿**：Tomcat 在 Context 启动第 (10) 步回调所有已注册 SCI（3.5.2 节），这里被回调的就是 Boot 的 `DeferredServletContainerInitializers`——它逐个转发给 Spring 的 `ServletContextInitializer`（`DispatcherServlet` 注册、《Spring MVC.md》3.3 节的 RegistrationBean 链，都在此刻生效）。

【源码证据】`tomcat/servlet/DeferredServletContainerInitializers.java:52-74`：

```java
@Override
public void onStartup(Set<Class<?>> classes, ServletContext servletContext) throws ServletException {
    try {
        for (ServletContextInitializer initializer : this.initializers) {
            initializer.onStartup(servletContext);            // ← Spring 组件在此拿到 ServletContext
        }
    } catch (Exception ex) {
        this.startUpException = ex;
        // Prevent Tomcat from logging and re-throwing when we know we can
        // deal with it in the main thread, but log for information here.
        ...
    }
}

@Override
public void rethrow() throws Exception {
    if (this.startUpException != null) { throw this.startUpException; }
}
```

注意这个设计：异常**吞下暂存**而不是抛给 Tomcat——`TomcatWebServer.initialize()` 在 tomcat.start() 返回后调 `rethrowDeferredStartupExceptions()`（:134、:199-209）把异常在主线程重抛，让 Boot 的启动失败报告（FailureAnalyzers）能接得住。对比独立模式：ContextConfig 装配失败 → Context FAILED → 启动日志里一条 ERROR，容器照常运行其余应用——**单应用世界必须"失败即整场失败"，这个语义差异催生了暂存重抛的机械**。

## 6.4 "半启动"与端口延迟绑定：两道保险的配合

Boot 不能在 onRefresh 就绑端口——那时 Bean 还没就绪，8080 若提前放行请求会打到未装配好的应用；《Spring Boot.md》5.3-5.4 节讲了 Boot 侧的 SmartLifecycle 编排，Tomcat 侧的配合动作有两道：

**第一道：`bindOnInit=false`。** Connector 本可在 init 阶段绑端口（3.4.2 节默认 true），Boot 明确关掉（`tomcat/TomcatWebServer.java:181-190`）：

```java
private void disableBindOnInit() {
    doWithConnectors((service, connectors) -> {
        for (Connector connector : connectors) {
            Object bindOnInit = connector.getProperty("bindOnInit");
            if (bindOnInit == null) {
                connector.setProperty("bindOnInit", "false");
            }
        }
    });
}
```

**第二道：Context 启动事件里摘除 Connector。** `initialize()`（:113-153）先给 Context 挂一个监听器——Context 一到 START_EVENT（3.5.2 节 (15) 之前）就把全部 Connector 从 Service 里摘走，之后才调 `tomcat.start()`：

```java
Context context = findContext();
context.addLifecycleListener((event) -> {
    if (context.equals(event.getSource()) && Lifecycle.START_EVENT.equals(event.getType())) {
        // Remove service connectors so that protocol binding doesn't
        // happen when the service is started.
        removeServiceConnectors();                        // :124
    }
});
disableBindOnInit();
this.tomcat.start();                                      // :131 ← "半启动"
rethrowDeferredStartupExceptions();
...
startNonDaemonAwaitThread();                              // :145（6.8 节）
```

两道保险合围之下，`tomcat.start()` 触发的第三章那套 init/start 递归照常执行——**Engine/Host/Context 全部启动、SCI 回调完成、Servlet/Filter/Listener 全部就绪**——唯独 `StandardService.startInternal()` 里那个 `connectors[i].start()` 循环（`StandardService.java:443-448`）面对空数组无可启动。端口纹丝不动。

端口何时绑上？`TomcatWebServer.start()`（:225-255）——由《Spring Boot.md》5.4 节的 `WebServerStartStopLifecycle`（SmartLifecycle，phase = MAX-2048）在 `finishRefresh()` 里调用：

```java
addPreviouslyRemovedConnectors();        // :233 Connector 装回 Service
Connector connector = this.tomcat.getConnector();
if (connector != null && this.autoStart) {
    performDeferredLoadOnStartup();      // :236 loadOnStartup 的 Servlet 此刻才 init（6.7 节）
}
checkThatConnectorsHaveStarted();        // :238 FAILED → ConnectorStartFailedException（端口占用在此浮出）
this.started = true;
logger.info(getStartedLogMessage());     // ← "Tomcat started on port(s) 8080 (http) ..."
```

Connector 装回时 `StandardService.addConnector()`（`catalina/core/StandardService.java:232-237`）发现 Service 已 STARTED，**顺手就把 Connector start 了**（`if (getState().isAvailable()) connector.start()`）——`endpoint.start()` 发现 bindState 是 UNBOUND（bindOnInit=false 的后效），此刻才 `bindWithCleanup()` 绑端口、起 Acceptor/Poller。**8080 开始 accept 的那一刻，全部单例 Bean 已就绪**——这就是两种模式下端口绑定时机差异的完整链条：独立模式在 init 阶段绑（3.4.2 节）、应用后部署；内嵌模式在最后一步绑、应用早已部署完。

## 6.5 Servlet 装配的替换：从"扫描发现"到"显式回调"

把两种模式的 Servlet 注册路径并排放：

| 环节 | 独立模式（3.5 节） | 内嵌模式（Boot 4.0.8） |
|---|---|---|
| 部署发现 | HostConfig 扫描 webapps（descriptor/war/目录，:401-411） | 无扫描：工厂 `host.addChild(context)`（:205） |
| web.xml | conf/web.xml + 应用的 web.xml + web-fragment.xml 合并（ContextConfig.webConfig，:1287-1420） | 不解析（门面 `addDefaultWebXmlToWebapp=false` 语义；Default/JSP Servlet 由工厂程序化补，:196-202） |
| 注解扫描 | processClasses 扫 @WebServlet/@WebFilter（webConfig Steps 4-5） | 不扫描：Spring 组件以 bean 形式存在，无需注解注册 |
| SCI 发现 | WebappServiceLoader 扫 META-INF/services（ContextConfig:1850-1895） | 不发现：Boot 把自己的聚合器**显式** addServletContainerInitializer（:298） |
| 注册回调 | SCI.onStartup + configureContext(webXml) | ServletContextInitializerBeans 收集 → DeferredServletContainerInitializers.onStartup 转发 |
| DispatcherServlet | 《Spring MVC.md》"轨道 A"：SCL 回调 → Spring 容器启动 → 注册 | 《Spring MVC.md》"轨道 B"：DispatcherServletRegistrationBean 直接在此刻 addServlet |

《Spring MVC.md》2.6 节"双轨殊途同归"的论断在这里得到 Tomcat 侧的最终实证：**两条轨的汇合点都是 `ServletContext` 动态注册 API**，只是"谁最后调 addServlet"不同。而 Boot 关闭扫描的一个隐性收益是启动提速：fat jar 里扫描全部嵌套 jar 的 SCI/注解/碎片是 WAR 部署最贵的环节之一，Boot 用显式装配把它整个买断了（TLD 扫描同理——工厂 `configureTldPatterns()` 设 `StandardJarScanFilter` 的 skip 清单，:241-246）。

## 6.6 类加载差异：一个加载器 vs 三层+每应用隔离

【源码证据】`tomcat/TomcatEmbeddedWebappClassLoader.java`（Boot 4.0.8 全量 130 行，骨架）：

```java
public class TomcatEmbeddedWebappClassLoader extends ParallelWebappClassLoader {
    static {
        if (!JreCompat.isGraalAvailable()) { ClassLoader.registerAsParallelCapable(); }
    }
    public TomcatEmbeddedWebappClassLoader(@Nullable ClassLoader parent) { super(parent); }

    @Override
    public @Nullable URL findResource(String name) { return null; }          // ← 自身不带任何资源仓库
    ...
    @Override
    public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        ...
        Class<?> result = findExistingLoadedClass(name);
        result = (result != null) ? result : doLoadClass(name);
        ...
    }

    private @Nullable Class<?> doLoadClass(String name) {
        if ((this.delegate || filter(name, true))) {
            Class<?> result = loadFromParent(name);                          // ← 父先
            return (result != null) ? result : findClassIgnoringNotFound(name);
        }
        Class<?> result = findClassIgnoringNotFound(name);
        return (result != null) ? result : loadFromParent(name);
    }
    ...
}
```

差异表：

| 维度 | 独立模式（5.1 节） | 内嵌模式 |
|---|---|---|
| 容器加载器 | Bootstrap 建 common/server/shared 三层（默认塌缩为一层 common，来自 `$CATALINA_HOME/lib`） | **不存在**：Tomcat 类就在应用 classpath 上（tomcat-embed-core.jar 是普通依赖） |
| 应用加载器 | 每应用一个 `ParallelWebappClassLoader`，parent=sharedLoader，仓库=/WEB-INF/classes+lib | 同一个类，但 parent=**应用的类加载器**（Boot 的 AppClassLoader/LaunchedURLClassLoader，工厂 :185-195），仓库为空 |
| 委派方向 | delegate 默认 **false**（应用先，容器包过滤强制父先） | `loader.setDelegate(true)`（工厂 :194）+ parent 里已有全部应用类 → **事实上父先** |
| 过滤器 | 仍生效（filter(name) 把 jakarta/javax/org.apache.* 划给父） | 同一套父类过滤器，语义自然成立：这些包的类父加载器（应用 classpath 上的 embed jar）有 |
| addURL | 正常向仓库追加资源 | **覆写为忽略**（:102-107）：加载器只是个"壳"，资源全在 parent 里 |
| 隔离效果 | 应用间隔离、可热部署、需内存泄漏防护（5.1.3） | 单应用与 JVM 同生共死，隔离无意义 → Boot `DisableReferenceClearingContextCustomizer`（工厂 :315）直接关掉泄漏清理 |

一句话：**内嵌模式把"类加载隔离"这个独立模式的核心卖点整个消解了**——应用类本来就在 parent 里可见，WebappClassLoader 退化为遵守 Servlet 规范形态的形式道具（`ServletContext.getClassLoader()` 等 API 仍需要一个它存在）。

## 6.7 Servlet 加载时机：loadOnStartup 的"延迟执行"

第三章 3.5.2 节 (13)：独立模式在 Context.startInternal 里就地执行 `loadOnStartup(findChildren())`。内嵌模式把这个动作从 Context 启动里**摘了出来**：

【源码证据】`tomcat/TomcatEmbeddedContext.java:54-58`：

```java
@Override
public boolean loadOnStartup(Container[] children) {
    // deferred until later (see deferredLoadOnStartup)
    return true;                          // ← 覆盖父类：什么都不 load，只回个"成功"
}
```

随后 `TomcatWebServer.start()`（6.4 节 :236）在端口绑定前调 `performDeferredLoadOnStartup()`（:326-340）→ `TomcatEmbeddedContext.deferredLoadOnStartup()`（:68-71）——用 webapp 类加载器做线程上下文（照顾依赖 TCCL 的老框架，:99-118 的注释）逐个 `wrapper.load()`。为什么要摘？因为 `tomcat.start()`（半启动）发生在 Spring 容器 onRefresh 中途，**此时 DispatcherServlet 还没被实例化成单例 Bean**；等 finishRefresh 阶段 Servlet/Filter 单例全部就绪、RegistrationBean 已把它们注册进 ServletContext，才轮到 load-on-startup 的 init 调用。《Spring Boot.md》5.2.2 节讲过 ServletContextInitializerBeans 会提前实例化引用的 bean，两处合起来才构成完整闭环。

## 6.8 保活与关闭：await 的两种用法与 Boot 的优雅停机

**JVM 为什么不退出**（1.5 节埋的问题）：

- 独立模式：所有 Tomcat 线程是 daemon（`AbstractEndpoint.java:1680` `daemon=true`；Acceptor/Poller 实证 3.4.3 节），唯一非 daemon 的 main 线程阻塞在 8005 的 `await()`（3.6 节）。
- 内嵌模式：main 线程在 `SpringApplication.run()` 返回后就去干用户的事了（甚至结束）。Boot 在 `TomcatWebServer.initialize()` 里自己补了一条**非 daemon 的看门线程**（`tomcat/TomcatWebServer.java:211-223`）：

```java
// Unlike Jetty, all Tomcat threads are daemon threads. We create a
// blocking non-daemon to stop immediate shutdown
private void startNonDaemonAwaitThread() {
    Thread awaitThread = new Thread("container-" + (containerCounter.get())) {
        @Override
        public void run() {
            TomcatWebServer.this.tomcat.getServer().await();     // ← port=-1：10s 轮询 stopAwait 标志
        }
    };
    awaitThread.setContextClassLoader(getClass().getClassLoader());
    awaitThread.setDaemon(false);
    awaitThread.start();
}
```

3.6 节埋的伏笔在此闭环：门面 `server.setPort(-1)`（6.2 节）让 await 走"sleep 轮询标志位"分支（`StandardServer.java:515-527`），Boot 的看门线程用同一 API 实现保活——**同一个 await()，独立模式吃它的"监听 8005"分支，内嵌模式吃它的"轮询标志位"分支**。

**关闭路径**：

| 动作 | 独立模式 | 内嵌模式 |
|---|---|---|
| 触发 | `catalina.sh stop`（8005 发 SHUTDOWN）或 SIGTERM → CatalinaShutdownHook | Spring 上下文 close → `WebServerStartStopLifecycle.stop`（SmartLifecycle 逆序） |
| 第一步 | `Catalina.stop()`：摘 hook → `server.stop()` | `TomcatWebServer.stop()`：`removeServiceConnectors()`（:355，摘连接器即停收流量） |
| 优雅等待 | `StandardService.stopInternal()`：`closeServerSocketGraceful` + `awaitConnectionsClose`（:466-474，默认 5s） | `GracefulShutdown`（Boot 类）：`connector.pause()` + `closeServerSocketGraceful`，50ms 轮询 `StandardContext.getInProgressAsyncCount()` 与 `StandardWrapper.getCountAllocated()` 等在途请求归零（`tomcat/GracefulShutdown.java:93-128`）；`server.shutdown.grace-period` 超时则 abort |
| 容器逆序 | server.stop/destroy 递归 | Spring 上下文销毁 → `TomcatWebServer.destroy()` → `tomcat.stop()+destroy()`（:368-380）→ 同一套镜像递归 |
| JVM 退出 | main 返回，无非 daemon 线程 | 看门线程仍在 await 轮询——`server.stop()` 内部 `stopAwait()`（`StandardServer.java:929`）置标志位，轮询线程醒来退出，JVM 方可结束 |

**stop 与 destroy 分离是 Boot 4 的一个精巧设计**：`WebServer.stop()` 只摘连接器（停止服务但保留组件状态，management 子上下文切换等场景可复用），彻底关停留给 `destroy()`——对齐了《Spring Boot.md》5.2.1 节"延迟开关"的思路。

## 6.9 独立 vs 内嵌：逐项差异总表

| 维度 | 独立运行 | Spring Boot 内嵌（4.0.8 实测） |
|---|---|---|
| 进程入口 | `catalina.sh` → `Bootstrap.main`（独立 JVM） | `SpringApplication.run`（用户 JVM） |
| 对象树构建 | Digester 解析 `conf/server.xml`（`Catalina.java:484-590`） | 程序化 `new`：`startup.Tomcat` 门面 + Boot 工厂（6.2/6.3） |
| 配置来源 | server.xml / catalina.properties / context.xml / web.xml | `application.yml` 的 `server.*` → `TomcatServerProperties` → 工厂/customizer 链 |
| CATALINA_BASE | 显式目录（conf/logs/webapps/work/temp） | `createTempDir("tomcat")`（工厂 :375） |
| 部署发现 | HostConfig 扫描 appBase，多应用并行部署 | 无扫描，唯一 `TomcatEmbeddedContext`（autoDeploy=false） |
| Servlet 注册 | web.xml 合并 + 注解扫描 + SCI 发现（ContextConfig 11 步） | `ServletContextInitializer` 显式回调（Boot SCI 聚合器 :298） |
| loadOnStartup | Context 启动内联执行（`StandardContext.java:4617`） | 覆盖为空操作，推迟到 Boot start() 阶段（6.7） |
| 端口绑定时机 | Connector **init** 阶段（bindOnInit=true 默认） | Boot **start()** 阶段（bindOnInit=false + Connector 摘除双保险，6.4） |
| 类加载 | 三层加载器 + 每应用 WebappClassLoader（隔离、热部署、泄漏防护） | 无三层；`TomcatEmbeddedWebappClassLoader` 为空壳，parent=应用加载器（6.6） |
| JNDI | 默认开（catalina.useNaming） | 门面默认关（`Tomcat.java:613`） |
| JMX/MBean | 默认开（Server/Service/Connector 各级注册） | `Registry.disableRegistry()`（工厂 :371-373） |
| 会话持久化 | 默认开（SESSIONS.ser） | 默认关（`DisablePersistSessionListener`，工厂 :340） |
| JVM 保活 | main 线程阻塞 await（8005 accept） | Boot 起 container-N 非 daemon 线程调 await（port=-1 轮询）（6.8） |
| 关闭 | 8005 SHUTDOWN / SIGTERM hook | Spring 上下文关闭链 + `GracefulShutdown`（pause + 在途请求归零） |
| 请求处理 | **完全相同**（NioEndpoint/Mapper/Pipeline/FilterChain） | **完全相同**（差异仅在装配与生命周期） |

表里最该记住的两组对照：**"配置驱动 → 代码驱动"贯穿全部构建维度**（对象树、部署、注册、类加载策略皆是），而**"请求处理链路零差异"**——理解了这个，就理解了 Boot "简化启动，不偷换运行时"的设计分寸。

## 6.10 本章小结

1. 内嵌启动 = 同一棵 Lifecycle 树、同一套请求链；变化集中在树怎么建（门面 + 工厂程序化）、Servlet 怎么进（SCI 显式回调替代扫描）、生命周期谁管（Spring refresh/SmartLifecycle）。
2. "半启动"由两道 Tomcat 侧机制合成：bindOnInit=false 让 Connector init 不绑端口；Context START_EVENT 监听器把 Connector 摘出 Service，让 service.start 的连接器循环空转。绑端口的时机被精确推迟到所有单例就绪之后。
3. `DeferredServletContainerInitializers` 是 Boot 与 Tomcat 的咬合齿：Tomcat 在 Context 启动第 (10) 步回调它，它转发给 Spring 的 ServletContextInitializer；异常暂存到主线程重抛，换取 Boot 的失败诊断体验。
4. 类加载隔离在内嵌模式下被消解——WebappClassLoader 退化为空壳，delegate 翻转，泄漏清理关闭。
5. await() 的 -1/-2 端口语义是官方给嵌入方的口子；Boot 的保活与停机都建立在"所有 Tomcat 线程皆 daemon"这一事实上。

---
# 七、扩展点与调优速查

## 7.1 节点 × 扩展手段全景表

前六章在源码里遇到过的每个"口子"，按请求/生命周期节点归位（与《Spring MVC.md》第五章"节点 × 扩展手段"同构）：

| 节点 | 扩展手段 | 典型用途 | 独立模式配置点 | Boot 对应 |
|---|---|---|---|---|
| 连接器/协议层 | 自定义 `ProtocolHandler`/`UpgradeProtocol` | HTTP/2、AJP、自定义协议 | server.xml `<Connector protocol=...>` | `TomcatConnectorCustomizer`/`addAdditionalConnectors` |
| Coyote→Catalina 门 | 自定义 `Adapter` | 协议改写、请求预转换 | （代码级） | （代码级，罕见） |
| Engine 管道 | `addEngineValves` / server.xml `<Valve>` | 访问日志、请求追踪、限流 | `<Valve className="...AccessLogValve"/>` | `addEngineValves` / accesslog 配置 |
| Host 管道 | 同上 | 多站点日志/过滤 | `<Host><Valve .../></Host>` | 同上 |
| Context 管道 | 同上 | 单应用日志、请求拒绝 | `<Context><Valve .../></Context>` | `addContextValves` |
| 会话 | 自定义 `Manager`（session/ 包） | Redis 化会话、集群复制 | `<Manager className=.../>` | SessionRepository（Spring Session 代理 Manager） |
| 鉴权 | `Realm`（catalina/realm/） | JNDI/DataSource/JAAS 认证 | `<Realm className=.../>` | Spring Security Filter 链接管（Realm 极少用） |
| 部署 | `HostConfig` 定制 / `JarScanner` | 跳过扫描提速、私有部署器 | context.xml `jarScanner` | `tldSkipPatterns`、`TomcatContextCustomizer` |
| 请求执行 | `Executor` | 平台线程池参数、虚拟线程 | server.xml `<Executor>` | `server.tomcat.threads.*`、`spring.threads.virtual.enabled` |
| 生命周期 | `LifecycleListener` | 资源预热、环境探测 | server.xml `<Listener>` | `addContextLifecycleListeners`、`TomcatBackgroundPreinitializer` |
| 应用内 | `Filter`/`Listener`/`Servlet` | 业务横切、初始化钩子 | web.xml / 注解 | `FilterRegistrationBean` 等（Servlet 规范 API 不变） |

## 7.2 调优参数 → 源码字段映射

高频 `server.tomcat.*` 配置项与独立模式 server.xml 属性同源（Boot 侧字段在 `TomcatServerProperties.java`，最终由 7.1 的 customizer 链落到 Tomcat 字段）：

| 参数 | 默认值 | Tomcat 字段 | 落点类（4.1 节模型中的角色） |
|---|---|---|---|
| `threads.max` | 200 | `maxThreads` | AbstractEndpoint.createExecutor（工作线程池上限） |
| `threads.min-spare` | 10 | `minSpareThreads` | 同上 |
| `max-connections` | 8192 | `maxConnections` | Acceptor.run 的 countUpOrAwaitConnection（连接数信号量） |
| `accept-count` | 100 | `acceptCount` | ServerSocket backlog（内核排队上限） |
| `connection-timeout` | 20s | `connectionTimeout` | SocketWrapper 读超时（setSocketOptions，NioEndpoint:584） |
| `keep-alive-timeout` / `max-keep-alive-requests` | 同 connection-timeout / 100 | `keepAliveTimeout` / `maxKeepAliveRequests` | Processor 复用判定（4.6 节） |
| `max-swallow-size` | 2MB | `maxSwallowSize` | 请求体丢弃上限（大请求上传中断场景） |
| `threads.virtual.enabled` | false | `useVirtualThreads` | createExecutor 的 VirtualThreadExecutor 分支（4.1.3） |
| `basedir` | 临时目录 | `basedir` | 工厂 createTomcat（6.3.1） |
| `uri-encoding` | UTF-8 | `uriEncoding` | Connector（4.3 postParseRequest 解码） |

排障心法一例：**"请求偶尔 503 且日志无异常"** → 先查 `maxConnections` 是否打满（Acceptor 在 :133 处 await，连接进不来但已建立的请求仍会处理）→ 再查工作线程池是否打满（jstack 看 `-exec-` 线程状态）——4.1 节的三级模型给出精确的排查顺序。

## 7.3 本章小结

Tomcat 的扩展面与它的分层严格对齐：协议层（ProtocolHandler/UpgradeProtocol）、连接层（Connector 参数）、容器层（Valve/Realm/Manager）、装配层（Listener/ContextConfig 行为）、应用层（Servlet 规范 API）。独立模式一切经 server.xml，Boot 把同一批口子翻译成 customizer bean——**扩展点不变，接线方式随主客关系反转而反转**。

---

# 结语：把两台 Tomcat 读成一台

全文通读下来可以回答开头的三个问题：

- **Tomcat 的运行机制是什么？** 一棵由 `Lifecycle` 状态机统一驱动的组件树（Server→Service→{Connector, Engine→Host→Context→Wrapper}），配置（server.xml/Digester）或代码（门面/工厂）都能把它构建出来；请求侧由 Coyote 的 Acceptor/Poller/线程池三级模型搬运字节，经唯一的 Adapter 门进入 Catalina，沿四级 Valve 管道下潜，最终在 Filter 链末端调到你的 `service()`（ApplicationFilterChain.java:132）。
- **独立启动与内嵌启动差在哪？** 差在"树的来源"（Digester vs 程序化）、"应用的进入方式"（扫描部署 vs 显式装配）、"生命周期主导权"（Catalina 自管 + await 阻塞 vs Spring 容器 refresh + SmartLifecycle + 非 daemon 看门线程），以及由前两者派生的端口绑定时机、类加载策略、注册扫描路径（6.9 总表 15 项逐项对照）；**运行时请求链路零差异**。
- **为什么读 Tomcat 源码对 Spring 开发者有价值？** 《Spring MVC.md》第四章从 FilterChain 那一行往下讲的 MVC 世界，往上的每一层——Filter 链怎么配出来、请求对象从哪来、DispatcherServlet 何时被 init——答案都在本文第三、四章；《Spring Boot.md》第五章讲的"半启动与延迟绑端口"，其另一半机械（bindOnInit、Connector 摘除、loadOnStartup 延迟、await 保活）正是本文第六章。三份文档拼合，"浏览器字节流 → controller 方法 → 字节流回浏览器"的全程再无黑盒。

配套阅读顺序建议：《Spring Framework.md》（容器内核）→《Spring Boot.md》（自动化编排与内嵌服务器时序）→《Spring MVC.md》（Servlet 规范与请求全链路）→ 本文（容器本体与双轨启动）；姊妹篇《Jetty.md》以同一套问题清单解剖 Jetty 12 的"两条生命线"，两篇对照可看清同一 Servlet 规范之下各家容器的设计分野。至此系列对"Spring 应用"的源码级覆盖，从 IoC 容器到 TCP 字节流完整闭环。

（完）
