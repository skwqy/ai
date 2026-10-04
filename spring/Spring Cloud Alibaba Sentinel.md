# Spring Cloud Alibaba Sentinel 深度源码解析（写给初学者的架构全景）

> **本文基于的源码**：两个仓库。① **Sentinel 核心库** `D:\code\3rd\sentinel`，版本 **1.8.10**（tag `1.8.10`，Git commit `cf97bba`，2026-05-21）；② **Spring Cloud Alibaba 集成层** `D:\code\3rd\spring-cloud-alibaba`，版本 **2026.0.0.0-SNAPSHOT**（2026.0.x 分支，Git commit `efd2fda`，2026-09-27，该分支声明依赖 Sentinel 1.8.10）。文中所有【源码证据】的文件路径与行号均为对这两个快照实际读取所得。
>
> **版本取舍说明**："Spring Cloud Alibaba Sentinel" 实际上是**两层东西**：下层是阿里开源的 **Sentinel**（`com.alibaba.csp.sentinel.*`，纯 Java 库，不依赖 Spring），上层是 **spring-cloud-starter-alibaba-sentinel**（`com.alibaba.cloud.sentinel.*`，把 Sentinel 装进 Spring Boot 的自动配置外壳）。Sentinel 的核心骨架——Entry/Context 调用树、Slot 责任链、滑动窗口统计——自 1.0 以来高度稳定；1.8 系列的演进主要在**外围**：1.8.0 把熔断重构为独立断路器对象、1.8.7 新增匀速排队重构版 `ThrottlingController` 与"免配置兜底熔断" `DefaultCircuitBreakerSlot`（commit `8b43cae`）、1.8.8 新增面向 jakarta 的 `webmvc_v6x` 适配器包（commit `6c47548`）；集成层的演进则是：SCA 2.2.x/2021.x（javax + 老适配包）→ 2023.x（jakarta + v6x 适配包）→ 2026.0.x（Boot 4.2 + Jackson 3 `tools.jackson`）。因此本文内容对使用 SCA 2021.x ~ 2025.x 的读者同样适用；演进差异在 1.6 节逐项标注。
>
> **阅读约定**：每章"先白话、后源码"——先用一两句人话讲清"这是什么、为什么需要它"，再给出源码证据链（`模块/src/main/java/.../类名.java` + 方法名 + 行号 + 代码片段）。行号只对上述两个快照精确，读者用 IDEA 打开源码按类名 + 方法名定位即可。

## 如何读这份文档

如果你是流量防护/熔断限流初学者，推荐两遍读法：

- **第一遍（建立地图，1~2 小时）**：只读第一章（总览）每一节的开头白话段、各章的"本章小结"节、以及第十四章（贯通视图：三条生命线）。目标是能回答：`SphU.entry()` 一个调用背后发生了什么？滑动窗口为什么是"滑动"的？熔断从"打开"到"恢复"要经历哪三个状态？`@SentinelResource` 的 blockHandler 和 fallback 谁先谁后？Nacos 上改一条规则怎么做到"不重启生效"？
- **第二遍（深入源码）**：对照每一章的【源码证据】逐行读。顺序建议：第二章（Entry 与调用树，一切的地基）→ 第三章（Slot 责任链，全文骨架）→ 第四章（滑动窗口，全文核心 A）→ 第五章（流控）→ 第六章（熔断，全文核心 B）→ 第十章（SCA 自动配置）→ 第十一章（四条适配链）→ 第十二章（Nacos 动态规则）→ 第七章、第八章、第九章、第十三章（随用随查）。

---

# 一、总览：Sentinel 的定位、设计哲学与整体架构

## 1.1 一句话定位

**Sentinel 是一个"把流量当资源来防守"的轻量级流量防卫兵**：它把"一次 HTTP 请求、一次方法调用、一次远程调用"统统抽象为**资源（Resource）**，对每个资源实时统计 QPS、线程数、响应时间、异常数，再用一组**可动态更新的规则**在调用发生的那一刻决定"放行 / 拒绝 / 排队等待 / 熔断半开探测"。官方自述：

> *"As distributed systems become increasingly popular, the reliability between services is becoming more important than ever before. Sentinel takes 'flow' as breakthrough point, and works on both flow control, flow shaping, circuit breaking and system adaptive protection."*（`README.md` 项目横幅）

它解决的不是"功能对不对"，而是**过载之下服务活不活**：上游突发十倍流量把线程池打满、下游抖动让整条调用链雪崩、热点商品挤垮全局——这三件事对应 Sentinel 的三大能力：**流控（Flow）**、**熔断降级（Degrade）**、**热点参数限流（ParamFlow）**，外加**系统自适应保护（System）**和**来源访问控制（Authority）**两个辅助位。

而 **Spring Cloud Alibaba Sentinel** 做的事是"把 Sentinel 无缝塞进 Spring 生态"：

1. **自动配置**：引入 starter 即生效——Web 请求自动变成资源、`@SentinelResource` 注解自动织入、Feign/RestTemplate/Gateway 自动包上防护；
2. **配置绑定**：`spring.cloud.sentinel.*` 一套属性接管 Sentinel 全部初始化参数；
3. **动态规则**：与 Nacos/Apollo/ZooKeeper 等 Spring Cloud 配置中心打通，规则改在配置中心、秒级生效。

一句话记住它：**Sentinel = 一个嵌在业务进程里的"流量统计 + 规则检查"内核（slot chain）+ 一层贴在 Spring 各入口上的"拦截器/适配器"（SCA starter）+ 一条把规则从配置中心送到内核的"动态管道"（SentinelProperty）**。

## 1.2 设计哲学：读源码前先记住五句话

1. **一切皆资源，一切靠 entry**。Sentinel 只有 `SphU.entry("资源名")` 一个动作（`sentinel-core/.../SphU.java:85` 委托给 `Env.sph`），业务代码在 entry/exit 之间就是"资源内部"。HTTP 请求、Feign 调用、自定义代码块，在内核眼里毫无区别——都是一条 entry 记录。**新增一种防护场景 = 写一个新的适配器调 entry，内核零改动**。
2. **检查靠责任链，链靠 SPI 拼装**。每次 entry 走过一条固定的 Slot 链：选节点 → 建统计 → 记日志 → 统计 → 授权 → 系统保护 → 热点 → 流控 → 兜底熔断 → 熔断（顺序见 3.2 节）。每个 Slot 都是 SPI 注册（`META-INF/services/com.alibaba.csp.sentinel.slotchain.ProcessorSlot`），想加检查逻辑就注册自己的 Slot——**规则检查是流水线，不是 if-else 大泥球**。
3. **数据先行，检查在后**。规则检查依赖实时数据，所以滑动窗口统计内核（LeapArray，第四章）是一切的地基：FlowSlot 看 `node.passQps()`、DegradeSlot 看 RT/异常计数、ParamFlowSlot 看参数桶——**先有每秒两次采样的精确计数，才有后面的规则判定**。
4. **规则与数据分离，规则可热更**。规则对象（FlowRule/DegradeRule/…）与统计节点（Node）完全分离，规则的装载统一走 `SentinelProperty` 发布-订阅模式（`DynamicSentinelProperty.updateValue` 通知所有 `PropertyListener`）——**控制台、Nacos、文件、代码，谁能把新规则喂给 Property，谁就是规则源**，内核对此一无所知。
5. **拦截而非代理，尽量零侵入**。SCA 对 Web 用 MVC `HandlerInterceptor`、对 Feign 用动态代理 InvocationHandler、对 RestTemplate 用 `ClientHttpRequestInterceptor`、对网关用 `GlobalFilter`——全部是**标准 Spring 扩展点上的薄适配**，业务代码要么一行不改（URL 自动成为资源），要么只加一个注解（`@SentinelResource`）。这与 Seata 把代理压在数据源层、Spring 把横切压在 AOP 层是同一思想（参见《Seata.md》1.2 节、《Spring Framework.md》第五章）。

## 1.3 模块分层全景

两个仓库的模块按"用户感知"分层如下（Sentinel 1.8.10 实测，SCA 2026.0.0.0-SNAPSHOT 实测）：

```
┌─────────────────────────── SCA 集成层（spring-cloud-alibaba 仓库）───────────────────────────┐
│  spring-cloud-starter-alibaba-sentinel    ★唯一的"代码" starter：自动配置 + 适配器 + 属性     │
│  spring-cloud-alibaba-sentinel-datasource 规则数据源绑定（Nacos/Apollo/ZK/Redis/Consul/File）│
│  spring-cloud-alibaba-sentinel-gateway    SCG 网关防护自动配置                               │
│  spring-cloud-circuitbreaker-sentinel     Spring Cloud CircuitBreaker 抽象的 Sentinel 实现  │
├────────────────────────── Sentinel 适配器层（sentinel-adapter，对接各入口）──────────────────┤
│  sentinel-spring-webmvc-v6x-adapter  ★Web MVC（jakarta，1.8.8+）  sentinel-spring-webflux-adapter│
│  sentinel-spring-cloud-gateway-v6x-adapter ★SCG（v6x）     sentinel-web-adapter-common       │
│  sentinel-api-gateway-adapter-common（网关公共规则） dubp/grpc/okhttp/spring-restclient…      │
├────────────────────────── Sentinel 扩展层（sentinel-extension）─────────────────────────────┤
│  sentinel-annotation-aspectj ★@SentinelResource AOP   sentinel-parameter-flow-control ★热点参数│
│  sentinel-datasource-nacos/apollo/zk/redis/consul/…（规则数据源实现）                         │
│  sentinel-metric-exporter / sentinel-prometheus-metric-exporter（指标导出）                   │
├────────────────────────── Sentinel 通信层（sentinel-transport）─────────────────────────────┤
│  sentinel-transport-common（命令框架）  simple-http（★默认，心跳+命令）  netty-http           │
├────────────────────────── Sentinel 集群流控（sentinel-cluster）─────────────────────────────┤
│  cluster-server-default（令牌服务端）  cluster-client-default（令牌客户端）                   │
├────────────────────────── Sentinel 内核（sentinel-core，189 个类）──────────────────────────┤
│  根包：SphU/SphO/CtSph/Env/Entry/AsyncEntry/Tracer（entry 与退出）                            │
│  context：ContextUtil/Context/NullContext（调用上下文，ThreadLocal）                          │
│  slotchain：ProcessorSlot/DefaultProcessorSlotChain/SlotChainProvider（责任链）               │
│  slots：nodeselector/clusterbuilder/logger/statistic（骨架 4 件）                             │
│         block/{flow,degrade,authority,system}（规则 4 类）                                    │
│  node：StatisticNode/DefaultNode/ClusterNode/EntranceNode（统计树）                           │
│  spi：SpiLoader/@Spi（扩展机制）   init：InitExecutor/InitFunc（启动装载）                    │
│  property：SentinelProperty/DynamicSentinelProperty（规则动态管道）                           │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
        另有 sentinel-dashboard（独立部署的控制台 WAR，不在依赖链上，见第十三章）
```

注意两个容易误解的点：

- **`spring-cloud-starter-alibaba-sentinel` 不是空壳 starter**。SCA 老版本（1.x 时代）是"实现模块 + 空壳 starter"结构，但从 2.2.x 起实现代码就并入了 starter 模块本身（`origin/2.2.x` 树实测，`SentinelWebAutoConfiguration.java` 直接位于 starter 模块内）；2026.0.x 延续这一布局，starter 里装着全部集成代码。
- **热点参数限流不在 sentinel-core 里**，而在扩展模块 `sentinel-extension/sentinel-parameter-flow-control`（`ParamFlowSlot` 等），但 SCA starter 直接依赖了它（starter `pom.xml:123`），所以用户无感。

## 1.4 依赖图（以 starter pom 与模块 pom 实证）

`spring-cloud-starter-alibaba-sentinel/pom.xml` 的直接依赖（实测逐项列出）：

| 依赖 | 作用 |
|---|---|
| sentinel-transport-simple-http（:98） | 与控制台通信（心跳 + 命令），默认传输层 |
| sentinel-annotation-aspectj（:103） | `@SentinelResource` AOP 支持 |
| spring-cloud-circuitbreaker-sentinel（:108） | Spring Cloud 熔断抽象实现（SCA 自研模块） |
| sentinel-spring-webflux-adapter（:113） | WebFlux 适配 |
| sentinel-spring-webmvc-v6x-adapter（:118） | Web MVC 适配（jakarta，1.8.8+） |
| sentinel-parameter-flow-control（:123） | 热点参数限流 |
| sentinel-api-gateway-adapter-common（:128） | 网关流控规则公共层 |
| sentinel-cluster-server-default（:134） | 集群流控服务端 |
| sentinel-cluster-client-default（:139） | 集群流控客户端 |
| spring-cloud-alibaba-sentinel-datasource（:144） | 规则数据源绑定 |
| spring-boot-starter-web / webflux / openfeign / loadbalancer | Spring 生态入口（均为可选路径的条件依赖） |

分层依赖关系：

```
spring-cloud-starter-alibaba-sentinel（SCA）
    │ 装配（自动配置 + 适配器注册）
    ▼
sentinel-spring-webmvc-v6x-adapter ──► sentinel-web-adapter-common ──► sentinel-core
sentinel-parameter-flow-control ───────────────────────────────────────► sentinel-core
sentinel-datasource-nacos（SCA 数据源绑定走它）────────► sentinel-datasource-extension ──► sentinel-core
sentinel-transport-simple-http ─────────────────────────► sentinel-transport-common ──────► sentinel-core
sentinel-annotation-aspectj ────────────────────────────► sentinel-core
sentinel-cluster-{server,client}-default ───────────────► sentinel-core
```

内核 sentinel-core 本身**零 Spring 依赖、零 Netty 依赖**——它是一个可以用在任何 Java 程序里的纯流量库，这也是它被广泛复刻到 Dubbo/Quarkus 等生态的原因。

## 1.5 关键问题映射（问题 → 章节）

| 你关心的问题 | 答案所在 |
|---|---|
| `SphU.entry()` 到底做了哪几步？ | 2.2、3.1 |
| 同名资源在不同调用链里为什么分开统计？ | 2.4（DefaultNode 与 ClusterNode） |
| 滑动窗口怎么做到 O(1) 写入 + 精确采样？ | 4.2（LeapArray 三分支） |
| QPS 限流的"快速失败/预热/排队"分别怎么实现？ | 5.3（四个 Controller） |
| 熔断器"打开→半开→恢复"状态机在哪？ | 6.2、6.3 |
| 没配任何规则会有默认熔断吗？ | 6.4（DefaultCircuitBreakerSlot） |
| 热点参数怎么按参数值限流？ | 7.1 |
| 系统保护凭什么知道机器过载？ | 7.2 |
| 控制台改的规则怎么"秒级生效"？推和拉差在哪？ | 8.3、13.2 |
| `@SentinelResource` 的 blockHandler / fallback / defaultFallback 优先级？ | 9.2 |
| starter 引入后 Web 请求怎么自动成为资源？ | 11.1 |
| Feign / RestTemplate / 网关的防护链路？ | 11.2~11.5 |
| Nacos 上的规则 JSON 怎么变成内存里的规则对象？ | 12.2 |

## 1.6 版本演进（本地 git tag / 分支实证）

**SCA 集成层 ↔ Sentinel 核心库 ↔ Spring 生态的对应矩阵**（从本地 tag/分支的 pom 实测）：

| Spring Cloud Alibaba | Sentinel | Spring Boot | Spring Cloud（commons） | Web 适配包 | Jackson |
|---|---|---|---|---|---|
| 2.2.x / 2021.x | 1.8.x 早中期 | 2.x / 3.x 早期 | 3.x/4.x | `adapter.spring.webmvc`（javax） | 2 |
| 2023.0.3.3（tag） | 1.8.8 | 3.2.9 | 4.x | `adapter.spring.webmvc_v6x`（jakarta） | 2 |
| 2025.0.0.0（tag） | 1.8.9 | 3.5.0 | 4.3.0 | webmvc_v6x | 2 |
| 2025.1.0.0（tag） | 1.8.9 | 4.0.0 | 5.0.0 | webmvc_v6x | 3（`tools.jackson`） |
| 2026.0.0.0-SNAPSHOT（2026.0.x 分支） | 1.8.10 | 4.2.0-M2 | 5.1.0-M1 | webmvc_v6x | 3（`tools.jackson`） |

**Sentinel 核心的关键演进节点**（均在本地 git 可实证的范围内）：

| 变化 | 版本 | 证据 |
|---|---|---|
| 熔断重构为独立断路器对象（`CircuitBreaker`/`ResponseTimeCircuitBreaker`/`ExceptionCircuitBreaker`，`slots/block/degrade/circuitbreaker/` 包） | 1.8.0 | 该包与 1.8.10 同构；DegradeRule 退化为规则元数据 |
| `ThrottlingController`（原 `RateLimiterController` 改名重构，纳秒时间轴，支持 maxQps > 1000） | 1.8.7 | commit `e34d552`，首个包含 tag：1.8.7 |
| 免配置兜底熔断 `DefaultCircuitBreakerSlot` | 1.8.7 | commit `8b43cae`（#2232），首个包含 tag：1.8.7 |
| `webmvc_v6x` 适配器包（jakarta namespace，`@since 1.8.8`） | 1.8.8 | commit `6c47548`；`AbstractSentinelInterceptor.java:51` |
| 版本快照 | 1.8.10（2026-05-21） | tag `1.8.10`，commit `cf97bba` |

**SCA 集成层的关键演进节点**（本地分支/树对比实证）：

| 变化 | 版本线 | 证据 |
|---|---|---|
| 集成代码并入 starter 模块（不再是"实现模块 + 空壳 starter"） | ≤2.2.x | `origin/2.2.x` 树 |
| Web 适配从 `adapter.spring.webmvc`（javax）切到 `adapter.spring.webmvc_v6x`（jakarta） | 2021.x → 2023.0.x | 两分支 `SentinelWebAutoConfiguration.java` import 对比 |
| 新增 `SentinelApplicationContextInitializer`（ContextInitializer 阶段把属性写入 System property，见 10.3） | ≤2023.0.3.3 | 该 tag 下类已存在 |
| Jackson 3（`tools.jackson`）替换 Jackson 2 | 2025.1.0.0 | 2025.0.0.0 仍 Jackson 2；`SentinelAutoConfiguration.java:29-32` |

## 1.7 全文章节地图

```
第一章 总览 ←你在这一章，先建地图
第二章 Entry 与调用树        ——内核地基：SphU/Context/Entry/Node
第三章 Slot 责任链           ——内核骨架：10 个插槽的流水线
第四章 滑动窗口统计          ——内核数据：LeapArray 滑窗内核
第五章 流控                  ——规则一：QPS/线程 + 4 种整形
第六章 熔断降级              ——规则二：断路器状态机 + 兜底熔断
第七章 热点/系统/授权        ——规则三四五：参数桶/系统指标/黑白名单
第八章 规则装载与动态数据源   ——管道：Property 监听器 + 通信命令
第九章 注解与 SPI            ——扩展：@SentinelResource / 自定义 Slot
第十章 SCA 自动配置（上）     ——集成层：starter 如何开机自启
第十一章 SCA 适配链（中）     ——集成层：Web/RestTemplate/Feign/网关/WebFlux/熔断抽象
第十二章 SCA 规则数据源（下） ——集成层：Nacos 动态规则全链路
第十三章 控制台与集群流控     ——外围：dashboard 架构、token server
第十四章 贯通视图            ——三条生命线串起全部十四章
```

---

# 二、核心模型：资源、Context、Entry、调用树

> **本章模块定位**：`sentinel-core` 根包 + `context` 包 + `node` 包。这是 Sentinel 的"世界观"——一次调用如何被建模、统计如何挂树。

## 2.1 一切从一个 entry 开始

白话版：你告诉 Sentinel"我要开始做一件事了"（`SphU.entry("下单")`），它先检查规则，检查通过就发给你一张"通行证"（`Entry` 对象），你做完事把票退回去（`entry.exit()`）。中途抛了业务异常就"记账"（`Tracer.trace(e)`），好让统计知道这个资源出了错。

【源码证据】静态入口委托到全局单例：

- `sentinel-core/src/main/java/com/alibaba/csp/sentinel/SphU.java:85`：`return Env.sph.entry(name, EntryType.OUT, 1, OBJECTS0);`
- `Env.java:32-37`：`public static final Sph sph = new CtSph();` 且**类加载的 static 块里调用 `InitExecutor.doInit()`**——第一次用到任何 entry API 时，Sentinel 的 SPI 初始化（日志、集群模式、CommandCenter 等）自动完成。这是典型的"静态门面 + 类加载触发初始化"手法。

`CtSph.entryWithPriority`（`CtSph.java:117-157`）是所有路径的汇合点，四道防线清晰可辨：

```java
Context context = ContextUtil.getContext();
if (context instanceof NullContext) {            // ① 上下文数超限（>2000），只创建 entry 不做任何检查
    return new CtEntry(resourceWrapper, null, context);
}
if (context == null) {                           // ② 没显式 enter，自动进默认上下文
    context = InternalContextUtil.internalEnter(Constants.CONTEXT_DEFAULT_NAME);
}
if (!Constants.ON) {                             // ③ 全局开关关闭，直接放行
    return new CtEntry(resourceWrapper, null, context);
}
ProcessorSlot<Object> chain = lookProcessChain(resourceWrapper);   // ④ 取/建该资源的责任链
if (chain == null) {                             // 资源数超限（>6000），同样放弃检查
    return new CtEntry(resourceWrapper, null, context);
}
Entry e = new CtEntry(resourceWrapper, chain, context, count, args);
try {
    chain.entry(context, resourceWrapper, null, count, prioritized, args);  // 走链
} catch (BlockException e1) {
    e.exit(count, args);                         // 被拒绝，立即退票再抛出
    throw e1;
}
```

三处"优雅降级"（①③④ 的 null chain 分支）是 Sentinel 的工程哲学：**检查体系自身出问题时宁可不防护，也不让业务失败**。

## 2.2 责任链的缓存：一个资源一条链

同一个资源全进程共享一条 `ProcessorSlotChain`，缓存在 `chainMap`（`CtSph.java:51-52`）。`lookProcessChain`（`CtSph.java:194-215`）用"双重检查锁 + copy-on-write 替换整个 map"避免锁竞争：读不加锁，写时新建 `HashMap` 全量拷贝再整体替换引用（L206-210），链内 Slot 构建一次、之后永远零开销。资源数上限 `MAX_SLOT_CHAIN_SIZE = 6000`（`Constants.java:37`）。

## 2.3 Context：ThreadLocal 里的调用上下文

白话版：Context 是"这次调用故事的背景板"——它有名字（入口名）、有来源（origin，通常是调用方应用名）、有当前挂在调用树上的哪个节点。

【源码证据】`context/ContextUtil.java`：

- `contextHolder`：`ThreadLocal<Context>`（L50）；
- `trueEnter`（L120-159）：从 `contextNameNodeMap` 按**上下文名**取全局共享的 `EntranceNode`，没有就创建并挂到机器根节点 `Constants.ROOT` 下（L138-140）；同样用 copy-on-write 替换 map（L142-145）；
- **熔断式自我保护**：上下文名数量超过 `MAX_CONTEXT_NAME_SIZE = 2000`（`Constants.java:36`）时，写入一个全局单例 `NullContext`（L126-128、L163-171）——之后所有 entry 都走 2.1 节的①分支只建票不检查。这个设计防的是"把 URL 原样当上下文名"导致内存被打爆的事故；
- `exit`（L200-205）：只有当 `context.getCurEntry() == null`（调用栈已完全退出）才清 ThreadLocal；
- 异步切换：`replaceContext`（L255-263）与 `runOnContext`（L273-280）供线程池场景传递上下文。

`Context` 对象本身很轻：名字、origin、EntranceNode 引用、当前 entry 指针（`curEntry`）、一个 `NullContext` 单例（`context/NullContext.java`）。

## 2.4 调用树：DefaultNode 是"每链路"的，ClusterNode 是"每资源"的

白话版：同一个资源 `/order/create`，从入口 A 进来和从入口 B 进来，**分路径统计**（DefaultNode），但**总账只有一本**（ClusterNode）。规则检查大多看总账，排查问题看分账。

【源码证据】`slots/nodeselector/NodeSelectorSlot.java` 的类注释（L51-116）画出了完整的树形结构：

```
              machine-root
              /         \
     EntranceNode1   EntranceNode2      ← 上下文名（入口）
            /               \
   DefaultNode(nodeA)  DefaultNode(nodeA) ← 分路径统计（按 context 区分）
             |                    |
             +- - - - - - - - - - +→ ClusterNode(nodeA)   ← 每资源一本总账
```

- `NodeSelectorSlot`：`@Spi(isSingleton = false, order = -10000)`（L127）——**每个资源的链上各有一份实例**，所以它的 `map`（L133）以 `context.getName()` 为 key 缓存该链路自己的 DefaultNode（L156-165），首次出现时挂到 `context.getLastNode()` 下构成调用树（L167），并把 `context.setCurNode(node)`（L173）；
- `CtEntry` 构造时也织入这棵树：`parent = context.getCurEntry()`，把父 entry 的 child 指向自己（`CtEntry.java:61-65`）——**Entry 链与 Node 树是同一棵调用树的两份投影**；
- `ClusterBuilderSlot`：静态 `clusterNodeMap` 以 ResourceWrapper 为 key（`ClusterBuilderSlot.java` L69 区域），首次遇到该资源创建 `ClusterNode`（L85 区域）并 `node.setClusterNode(clusterNode)`（L94 区域）；若 Context 带了 origin，再从 ClusterNode 里 getOrCreate 一个**来源统计节点**（L97-101）。

**Node 家族一览**（`node/` 包）：`StatisticNode`（滑动窗口 + 线程数，见第四章）是基类；`DefaultNode`（分路径）和 `ClusterNode`（每资源总账）继承它；`EntranceNode`（入口，`totalQps` 汇总子树）继承 DefaultNode；机器根 `Constants.ROOT`（`Constants.java:60-61`）是一个特殊的 EntranceNode。

## 2.5 退出：exit 的配对检查与树的回退

`CtEntry.exitForContext`（`CtEntry.java:90-133`）是退出逻辑的完整呈现：

1. **顺序配对检查**（L97-109）：如果当前 entry 不是 Context 的栈顶，说明调用方 exit 的顺序和 entry 顺序不配对，沿父链一路强制退栈并抛 `ErrorEntryFreeException`——防止"内层没退、外层先退"把树搞乱；
2. 正常路径：先走 slot 链的 exit（L112-114，逆序回调，见 3.4 节），再执行 exitHandler（L116），然后**恢复调用树**：`context.setCurEntry(parent)`、父的 child 置 null（L119-122）；
3. 栈退空且是自动进入的默认上下文时，顺手 `ContextUtil.exit()` 清 ThreadLocal（L123-128）。

## 2.6 本章小结

- 一次调用 = 一对 entry/exit，一张票 = `Entry`（CtEntry/AsyncEntry）；
- Context 在 ThreadLocal 里，带入口名和 origin，超限自动退化为 NullContext（放弃检查而不是报错）；
- 调用树有两份投影：Entry 链（parent/child）与 Node 树（DefaultNode 分路径 + ClusterNode 总账 + EntranceNode 入口）；
- 责任链按资源缓存（chainMap），构建一次终身复用；资源超 6000 条、上下文超 2000 个、全局开关关闭——三种情况都**静默放行**。

---

# 三、Slot 责任链：规则检查的流水线

> **本章模块定位**：`sentinel-core` 的 `slotchain` + `slots` 包。这是 Sentinel 的骨架，后面所有规则章都挂在链上。

## 3.1 链怎么拼：SPI 加载 + 顺序排序

白话版：链上每个工位（Slot）都是 SPI 注册的，内核启动时把所有登记过的 Slot 按 order 排序串起来。你想加一个"把每次限流写到自定义审计库"的 Slot，只需实现 `AbstractLinkedProcessorSlot` 并注册 SPI 文件，不用改任何内核代码。

【源码证据】`slots/DefaultSlotChainBuilder.java`：

```java
@Spi(isDefault = true)
public class DefaultSlotChainBuilder implements SlotChainBuilder {
    @Override
    public ProcessorSlotChain build() {
        ProcessorSlotChain chain = new DefaultProcessorSlotChain();
        List<ProcessorSlot> sortedSlotList = SpiLoader.of(ProcessorSlot.class).loadInstanceListSorted();
        for (ProcessorSlot slot : sortedSlotList) { ... chain.addLast(...); }
        return chain;
    }
}
```

- SPI 文件：`sentinel-core/src/main/resources/META-INF/services/com.alibaba.csp.sentinel.slotchain.ProcessorSlot`，逐行列出 9 个默认 Slot；
- `SpiLoader`（`spi/SpiLoader.java`）：`SPI_FILE_PREFIX = "META-INF/services/"`（L76），`loadInstanceListSorted()`（L168）按 `@Spi(order)` 升序排序，支持 `isDefault`/`isSingleton` 语义；
- 扩展模块的 Slot（如 ParamFlowSlot）自带同名的 SPI 文件，ServiceLoader 会把两个来源**合并**——这就是 1.8.10 的 SPI 文件里没有 ParamFlowSlot、但它依然在链上的原因。

## 3.2 默认 Slot 清单与顺序（1.8.10 实测）

| order | Slot | 职责 | 单例？ |
|---|---|---|---|
| -10000 | NodeSelectorSlot | 构建调用树，选中本链路的 DefaultNode | 否（每链一份） |
| -9000 | ClusterBuilderSlot | 绑定 ClusterNode、创建 origin 统计节点 | 是（静态 map） |
| -8000 | LogSlot | 兜底捕获下游 Slot 抛的 BlockException，写 sentinel-block.log | 是 |
| -7000 | **StatisticSlot** | **两段式统计**（见 3.3） | 是 |
| -6000 | AuthoritySlot | 来源黑白名单检查 | 是 |
| -5000 | SystemSlot | 系统自适应保护检查 | 是 |
| -3000 | ParamFlowSlot（扩展模块） | 热点参数检查 | 是 |
| -2000 | FlowSlot | 流控检查 | 是 |
| -1500 | DefaultCircuitBreakerSlot（1.8.7+） | 无降级规则时的兜底熔断 | 是 |
| -1000 | DegradeSlot | 熔断降级检查 | 是 |

顺序常量集中在 `Constants.java:76-84`（`ORDER_NODE_SELECTOR_SLOT = -10000` … `ORDER_DEGRADE_SLOT = -1000`）；ParamFlowSlot 的 order 在扩展模块类上（`ParamFlowSlot.java:34`，`@Spi(order = -3000)`）。

**为什么 StatisticSlot 排在所有规则 Slot 前面？** 因为规则 Slot 需要 node 参数（`passQps()`/`curThreadNum()` 等）做判定——先由前两个 Slot 把节点装配好、StatisticSlot 拿到 node，规则 Slot 才有数据可查。**统计体系是规则体系的前置依赖，这条顺序不能颠倒**。

## 3.3 StatisticSlot 的两段式：先检查、后记账

白话版：entry 时先让下游规则 Slot 检查（fireEntry 往后传），检查通过才把"通过 + 线程数"记上账；exit 时算出响应时间、把"成功/异常/RT"补记并归还线程。**统计与检查在同一个 Slot 里，但发生在链的不同深度**。

【源码证据】`slots/statistic/StatisticSlot.java`：

- entry（L54-123）：`fireEntry(...)` 先行（L59）；通过后 `node.increaseThreadNum(); node.addPassRequest(count)`（L62-63），并同步记 origin 节点（L65-69）与全局入站 `Constants.ENTRY_NODE`（L71-75）；捕获 `PriorityWaitException`（WarmUp 借用未来的令牌，线程数要记但 QPS 还不能记，L81-95）；捕获 `BlockException` 时 `context.getCurEntry().setBlockError(e)`（L98）+ `node.increaseBlockQps(count)`（L101-109）后**重新抛出**（L116）——拒绝本身也是统计；
- exit（L125-153）：若无 blockError，`rt = completeStatTime - createTimestamp`（L131-133），`recordCompleteFor`（L155-165）对 node/originNode/ENTRY_NODE 三份账本各记一次 `addRtAndSuccess`、`decreaseThreadNum`，非 BlockException 的错误记 `increaseExceptionQps`；
- 回调扩展：`StatisticSlotCallbackRegistry` 的 onPass/onBlocked/onExit（L78-80、L112-114、L146-149）——ClusterBuilderSlot 之外的第五种"挂点"。

## 3.4 链的执行细节：entry 正序、exit 逆序、心跳炸弹

- `AbstractLinkedProcessorSlot`（`slotchain/AbstractLinkedProcessorSlot.java`）：`fireEntry` 调 `next.transformEntry`（L29-34 区域），`fireExit` 同理——每个 Slot 自己决定"检查完要不要往下传"；
- `DefaultProcessorSlotChain`（`slotchain/DefaultProcessorSlotChain.java`）：内置一个哑元 `first` 头结点（L20-30 区域），`addFirst/addLast` 操作指针（L40-52 区域）；`entry()` 从 first 开始正序、`exit()` 同样从 first 开始——但由于每个 exit 是嵌套调用栈，**exit 的"执行完"顺序恰好与 entry 相反**（DegradeSlot.exit 先完成，StatisticSlot.exit 后完成）；
- 这解释了第六章的一个细节：`DegradeSlot.exit` 里断路器 `onRequestComplete` 需要请求结束时间，而此刻 StatisticSlot 还没算 RT——所以 `ResponseTimeCircuitBreaker` 自己兜底：`if (completeTime <= 0) completeTime = TimeUtil.currentTimeMillis()`（`ResponseTimeCircuitBreaker.java:69-71`）；
- `LogSlot` 在链上位于规则 Slot 之前（order -8000）：规则 Slot 抛出的 BlockException 被 LogSlot 捕获记入 sentinel-block.log 后**继续上抛**——日志是旁路，不吞异常。

## 3.5 本章小结

- 链是 SPI 拼装的：`SpiLoader.loadInstanceListSorted()` 按 order 排序，扩展模块自动合入；
- 1.8.10 默认 10 个工位（9 个 core + 1 个 ParamFlow 扩展），骨架 4 个 + 规则 5 个 + 日志 1 个；
- StatisticSlot 是"检查前装配、检查后记账"的两段式枢纽；
- entry 正序执行、exit 逆序收尾——所有跨 Slot 的时序问题都要按这个顺序推演。

---

# 四、滑动窗口统计内核：LeapArray 与 Node

> **本章模块定位**：`sentinel-core` 的 `slots/statistic/base`（LeapArray/WindowWrap/MetricBucket）、`slots/statistic/metric`（ArrayMetric）与 `node` 包。这是全文核心 A：所有规则检查的数据来源。

## 4.1 数据结构：数组 + 环形覆盖

白话版：把 1 秒切成 2 个 500ms 的桶，把 1 分钟切成 60 个 1s 的桶。请求来了往"当前时间所在的桶"里加数；查询时把窗口内所有有效桶加总。桶数组只有 N 格，时间一过就**复用旧格子**（清零重来）——内存恒定，这就是"环形滑动窗口"。

【源码证据】`StatisticNode.java:96-103` 两条账本：

```java
private transient volatile Metric rollingCounterInSecond = new ArrayMetric(SampleCountProperty.SAMPLE_COUNT, IntervalProperty.INTERVAL);  // 默认 2 桶 × 500ms
private transient Metric rollingCounterInMinute = new ArrayMetric(60, 60 * 1000, false);  // 60 桶 × 1s，不启用"借用"
private final LongAdder curThreadNum = new LongAdder();  // 线程数不走滑窗，是实时计数器
```

秒级账本用于**规则判定**（passQps/avgRt/minRt/blockQps 等，`StatisticNode.java:200-238`），分钟级账本用于**导出监控数据**（`metrics()`，L115-133，控制台每秒拉取的就是它）。

桶里的账本 `MetricBucket`（`slots/statistic/data/MetricBucket.java`）：核心就是**一组 `LongAdder` 按事件类型索引**（L30），对应 `MetricEvent`：PASS/BLOCK/EXCEPTION/SUCCESS/RT/OCCUPIED_PASS——`addPass/addBlock/addSuccess/addRT`（L106-126）全部是 LongAdder 的无锁累加。

## 4.2 LeapArray.currentWindow：无锁的窗口推进

白话版：找桶的公式是 `时间片序号 % 数组长度`；当前桶要么不存在（CAS 建新）、要么最新（直接用）、要么过期（加锁复用）——三分支覆盖一切。

【源码证据】`slots/statistic/base/LeapArray.java`：

- 结构：`AtomicReferenceArray<WindowWrap<T>> array`（L48）+ 一把只在"桶过期"时才用的 `ReentrantLock updateLock`（L53）；
- 定位：`calculateTimeIdx = timeMillis / windowLengthInMs % array.length()`（L100-104），`calculateWindowStart = timeMillis - timeMillis % windowLengthInMs`（L106-108）；
- 三分支（L132-190，源码里配了三张 ASCII 时刻图）：

```java
while (true) {
    WindowWrap<T> old = array.get(idx);
    if (old == null) {                            // ① 格子空：CAS 建新桶
        WindowWrap<T> window = new WindowWrap<>(windowLengthInMs, windowStart, newEmptyBucket(timeMillis));
        if (array.compareAndSet(idx, null, window)) { return window; }
        Thread.yield();                           //    CAS 失败让出时间片自旋
    } else if (windowStart == old.windowStart()) { // ② 桶最新：直接复用
        return old;
    } else if (windowStart > old.windowStart()) {  // ③ 桶过期：tryLock 复位
        if (updateLock.tryLock()) {
            try { return resetWindowTo(old, windowStart); } finally { updateLock.unlock(); }
        }
        Thread.yield();
    }
}
```

①② 是无锁快路径，③ 的锁竞争只发生在"沉寂一段时间后的第一笔请求"上——**平时零锁，偶发才有锁**，这是 Sentinel 高性能的关键。

## 4.3 ArrayMetric：对账本的只读聚合

`ArrayMetric`（`slots/statistic/metric/ArrayMetric.java`）是 `LeapArray<MetricBucket>` 的门面：所有查询方法都先 `data.currentWindow()` 推进窗口（L108-116 的 `pass()` 是模板），再遍历 `data.values()` 把窗口内所有桶的 LongAdder 求和。构造时按 `enableOccupy` 决定底层是 `BucketLeapArray`（普通）还是 `OccupiableBucketLeapArray`（支持"预借用"未来窗口，供 WarmUp 借用，L40-49）。

## 4.4 借用未来：tryOccupyNext 与优先级通过

当请求打上 `prioritized` 标记（Sentinel 认为它"值得等"），当前 QPS 超阈值时可以**预支下一个窗口的配额**、小睡片刻再通过：

【源码证据】`StatisticNode.tryOccupyNext`（L288-320）：借用量上限 = `threshold × interval / 1000`（L289-291），从最早的失效窗口开始逐个测算"窗口通过量 + 已借量 + 本次申请量 ≤ 上限"（L305-317），可行则返回需要等待的毫秒数；等待事件记入 `FutureBucketLeapArray`（`addWaitingRequest`，L328-330）。`DefaultController.canPass`（`slots/block/flow/controller/DefaultController.java:52-64`）在 borrow 可行时 `sleep(waitInMs)` 后**抛 `PriorityWaitException`**——这正是 3.3 节 StatisticSlot 里"只记线程数、不记通过量"的特判来源。

## 4.5 本章小结

- 滑窗 = AtomicReferenceArray 环形数组 + WindowWrap(开始时间, MetricBucket)；
- 写入无锁（LongAdder + CAS 建桶），仅过期复位用条件锁；秒级 2 桶判规则、分钟级 60 桶出报表；
- 线程数独立于滑窗（LongAdder 实时计数），pass/block/exception/success/rt 全在桶里；
- "借用未来"把 WarmUp/优先级通过统一成一套账本机制（occupiedPass/waiting）。

---

# 五、流控：FlowSlot 与四种流量整形

> **本章模块定位**：`sentinel-core` 的 `slots/block/flow`。规则一：什么阈值、按什么维度、超了怎么办。

## 5.1 检查入口：从 Slot 到规则遍历

白话版：FlowSlot 把"这个资源的所有流控规则"逐条问一遍"能过吗"，任何一条说不能，立刻抛 `FlowException`。

【源码证据】`slots/block/flow/FlowSlot.java`：`checkFlow` 委托给 `FlowRuleChecker`（L167-170），规则来源是一个函数 `ruleProvider = resource -> FlowRuleManager.getFlowRules(resource)`（L177-182）——**Slot 不持有规则，规则管理器是唯一权威**。`FlowRuleChecker.checkFlow`（`FlowRuleChecker.java:44-57`）遍历规则，任一条不过就 `throw new FlowException(rule.getLimitApp(), rule)`。

## 5.2 三维选节点：limitApp × strategy × (node)

一条 FlowRule 说了三件事：对**谁**（limitApp：default/其他来源/具体来源）、按**什么参照物**（strategy：直接/关联/链路）、查**哪本账**。三者的组合决定查哪个 Node（`FlowRuleChecker.selectNodeByRequesterAndStrategy`，L115-145）：

| strategy | direct | relate（关联资源） | chain（链路） |
|---|---|---|---|
| 查哪本账 | origin 节点或 ClusterNode | `ClusterBuilderSlot.getClusterNode(refResource)` | 仅当 `refResource == context.getName()` 才查本链路 node |

选出的 `selectedNode` 交给 `rule.getRater().canPass(selectedNode, acquireCount, prioritized)`（L85）——**"查哪本账"与"怎么判"解耦**，后者就是流量整形器。

## 5.3 四种 controlBehavior：一个工厂 + 四个 Controller

白话版：`controlBehavior` 决定超阈值后的"脾气"——直接拒绝（硬）、预热（先松后紧）、匀速排队（软着陆）、预热排队（两者叠加）。

【源码证据】`FlowRuleUtil.generateRater`（L133-151）：

```java
if (rule.getGrade() == RuleConstant.FLOW_GRADE_QPS) {
    switch (rule.getControlBehavior()) {
        case CONTROL_BEHAVIOR_WARM_UP:             return new WarmUpController(count, warmUpPeriodSec, ColdFactorProperty.coldFactor);
        case CONTROL_BEHAVIOR_RATE_LIMITER:        return new ThrottlingController(maxQueueingTimeMs, count);
        case CONTROL_BEHAVIOR_WARM_UP_RATE_LIMITER:return new WarmUpRateLimiterController(...);
        case CONTROL_BEHAVIOR_DEFAULT: default:    // 快速失败
    }
}
return new DefaultController(rule.getCount(), rule.getGrade());
```

**① DefaultController（快速失败 / 线程数）**（`controller/DefaultController.java:48-76`）：`avgUsedTokens` 按 grade 取 `passQps()`（QPS 模式）或 `curThreadNum()`（线程模式，L71-76），加上本次申请量超阈值即拒绝；带 prioritized 标记时走 4.4 节的借用路径（L52-64）。

**② WarmUpController（预热/冷启动）**（`controller/WarmUpController.java`）：Guava RateLimiter 同款思路的"令牌桶 + 冷热斜率"。冷启动期令牌库存高（系统冷、处理慢），随请求消耗令牌库存下降、放行速率线性爬升到目标值。三个预计算量（L83-104）：`warningToken = warmUpPeriodSec × count / (coldFactor - 1)`（库存警戒线）、`maxToken = warningToken + 2 × warmUpPeriodSec × count / (1 + coldFactor)`（库存上限）、`slope = (coldFactor - 1) / count / (maxToken - warningToken)`（爬升斜率）。canPass 时若库存高于警戒线，按 `warningQps = 1 / (aboveToken × slope + 1 / count)`（L127）收缩放行量——**库存越满，放行越慢**，给冷系统爬坡时间。

**③ ThrottlingController（匀速排队，1.8.7 重构版）**（`controller/ThrottlingController.java`）：把两次请求的间隔拉齐到 `1/QPS`——用 `AtomicLong latestPassedTime`（L44）记录上一个请求的放行时刻（纳秒精度，支持 maxQps > 1000），本次预期放行时刻 = `costTime + latestPassedTime`（L77），还没到就 CAS 排队并 `sleep`，超过 `maxQueueingTimeMs` 直接拒绝。适用于消息消费、批处理这类"能等"的场景。

**④ WarmUpRateLimiterController**：②与③叠加——先按②的冷热公式算当前速率，再按③排队。

## 5.4 本章小结

- FlowSlot → FlowRuleChecker → (limitApp/strategy 选 Node) → TrafficShapingController.canPass；
- 四种整形 = 四个 Controller，由 `FlowRuleUtil.generateRater` 按 controlBehavior 一比一构造；
- QPS 模式查滑窗 passQps、线程模式查 curThreadNum；prioritized 请求可借用未来窗口（PriorityWaitException）；
- WarmUp 用"令牌库存 × 斜率"建模爬坡，Throttling 用"上次放行时刻 + 固定间隔"建模排队。

---

# 六、熔断降级：断路器状态机

> **本章模块定位**：`sentinel-core` 的 `slots/block/degrade`。全文核心 B：下游不稳时怎么"壮士断腕"又能"自动愈合"。

## 6.1 从规则到断路器：1.8.0 的重构

白话版：1.8 之前熔断逻辑挤在 `DegradeRule.passCheck` 里；1.8.0 起每个规则配一个独立的**断路器对象**（CircuitBreaker），自己管自己的状态和窗口——规则是"配置"，断路器是"运行体"，二者分离。

【源码证据】`DegradeSlot.performChecking`（`slots/block/degrade/DegradeSlot.java`）：

```java
List<CircuitBreaker> circuitBreakers = DegradeRuleManager.getCircuitBreakers(r.getName());
for (CircuitBreaker cb : circuitBreakers) {
    if (!cb.tryPass(context)) {
        throw new DegradeException(cb.getRule().getLimitApp(), cb.getRule());
    }
}
```

`DegradeSlot.exit`（同文件）在请求完成时逐个调用 `cb.onRequestComplete(context)`——**entry 查状态、exit 喂数据**，这就是断路器的全部外部行为。

## 6.2 三态状态机

【源码证据】`circuitbreaker/CircuitBreaker.java` 的 `State` 枚举（L62-79）与两个核心方法（L42/L57）：

```
        错误率/慢调用率超阈值
CLOSED ────────────────────────► OPEN        （熔断中，所有请求秒拒）
   ▲                            │
   │ 探测成功                    │ 等待 recoveryTimeoutMs
   │                            ▼
   └────────────────────── HALF_OPEN        （放一个探测请求）
        探测失败：立刻回到 OPEN，重新计时
```

- CLOSED：放行 + 统计，超阈值转 OPEN；
- OPEN：`tryPass` 一律 false，直到 `recoveryTimeoutMs` 到点后**下一个请求**进入 HALF_OPEN；
- HALF_OPEN：只放这一个"探子"，探子成功转 CLOSED、失败回 OPEN（并重置下次恢复时间）。

状态迁移的方法名也自解释：`fromClosedToOpen` / `fromOpenToHalfOpen` / `fromHalfOpenToOpen` / `fromHalfOpenToClose`（基类 `AbstractCircuitBreaker.java`），支持 `CircuitBreakerStateChangeObserver` 观察者。

## 6.3 两种断路器：慢调用比例与异常

**① ResponseTimeCircuitBreaker（慢调用比例）**（`circuitbreaker/ResponseTimeCircuitBreaker.java`）：自带一个 1 桶滑动窗口 `SlowRequestLeapArray`（L149-166）计"慢请求/总请求"。`onRequestComplete`（L62-79）：`rt = completeTime - entry.getCreateTimestamp()`，超过 `maxAllowedRt` 就 slowCount+1，然后 `handleStateChangeWhenThresholdExceeded(rt)`（L81-115）——HALF_OPEN 时直接用探子结果定去留（L86-95）；CLOSED 时聚合窗口内计数，`totalCount < minRequestAmount`（最小请求数）不判定（L104），`slowCount/totalCount > maxSlowRequestRatio` 才转 OPEN（L107-109）。

**② ExceptionCircuitBreaker（异常比例 / 异常数）**（`circuitbreaker/ExceptionCircuitBreaker.java`）：同样的窗口骨架，`strategy` 决定按 `DEGRADE_GRADE_EXCEPTION_RATIO`（异常比例）还是 `DEGRADE_GRADE_EXCEPTION_COUNT`（异常数）判定（L49-53），同样受 `minRequestAmount` 保护（L102）。**注意：异常来自哪里？**——业务代码把异常交给 `Tracer.trace(e)`（或适配器自动做，如 11.1 节 `traceExceptionAndExit`），它把异常记到当前 entry 上，`ExceptionCircuitBreaker` 在 exit 侧读取。

## 6.4 兜底熔断：DefaultCircuitBreakerSlot（1.8.7+）

白话版：没配任何降级规则的资源，也可以享受一套"出厂预设"熔断——这是 1.8.7 新增的 Slot（commit `8b43cae`，#2232），1.8.10 已进默认 SPI 清单。

【源码证据】`slots/block/degrade/DefaultCircuitBreakerSlot.java:48-65`：

```java
// If user has set a degrade rule for the resource, the default rule will not be activated
if (DegradeRuleManager.hasConfig(r.getName())) {   // 配了显式规则就让位
    return;
}
List<CircuitBreaker> circuitBreakers = DefaultCircuitBreakerRuleManager.getDefaultCircuitBreakers(r.getName());
for (CircuitBreaker cb : circuitBreakers) {
    if (!cb.tryPass(context)) { throw new DegradeException(...); }
}
```

它复用与 DegradeSlot 完全一致的断路器机制，只是数据源换成了 `DefaultCircuitBreakerRuleManager`（出厂默认规则），且 exit 侧同样做 onRequestComplete 喂数据（L67-95）。**显式规则永远压过兜底规则**，二者互斥。

## 6.5 本章小结

- 1.8.0 起熔断 = DegradeSlot（entry 查 / exit 喂）+ 每规则一个 CircuitBreaker 状态机；
- 三态：CLOSED（统计判定）→ OPEN（秒拒 + 计时）→ HALF_OPEN（单探子定去留）；
- 两种策略共用一套"小滑窗 + minRequestAmount 防误伤"骨架；慢调用看 RT，异常看 Tracer 记账；
- 1.8.7+ 无规则资源有出厂兜底熔断，显式规则存在时自动让位。

---

# 七、热点参数、系统自适应与来源访问控制

> **本章模块定位**：热点在 `sentinel-extension/sentinel-parameter-flow-control`（运行时经 SPI 合入链上，order -3000）；系统保护与授权在 `sentinel-core` 的 `slots/system`、`slots/block/authority`。

## 7.1 热点参数限流（ParamFlow）

白话版：普通流控看资源整体，热点限流看**参数值**——同样是 `getSku(id)`，id=1 被万人秒杀限流，id=2 正常放行。规则里指定第几个参数、阈值多少、哪些"例外值"单独定阈值。

【源码证据】链上位置：`ParamFlowSlot.java:34` `@Spi(order = -3000)`（System 之后、Flow 之前）。检查核心 `ParamFlowChecker.java`：`paramIdx = rule.getParamIdx()`（L51）取出本次调用的第 idx 个参数（`args` 来自 entry 时传入——`@SentinelResource` 方法就是方法实参，Web 适配器则是 URL 参数），每个**参数值**在 `ParameterMetric` 里有一套独立的计数桶（threadCount/QPS 桶，`ParameterMetric.java`），QPS 模式走**令牌桶**（L134/204 的 token 计算），线程模式直接 `++threadCount <= threshold`（L117-118）。例外项（exceptionItems）优先于全局 count。值得注意的是：参数桶不挂在 Node 上而是挂在 `ParameterMetricStorage` 里按资源维度管理——因为**参数维度是资源级而非链路级**的统计。

## 7.2 系统自适应保护（System）

白话版：前面规则都是"单资源视角"，系统保护是"整机视角"——load/CPU/平均 RT/线程数/入口总 QPS 任一超标，就压低**所有入口**的放行量（只管 EntryType.IN）。

【源码证据】`slots/system/SystemSlot.java:38`：`SystemRuleManager.checkSystem(resourceWrapper, count)`。`SystemRuleManager`（`slots/system/SystemRuleManager.java`）：五个阈值字段（highestSystemLoad L68、highestCpuUsage L72、maxThread L75 等）；`loadSystemConf`（L232 起）装载规则——CPU 使用率只认 0~1 的小数，大于 1 直接告警忽略（L246-248）；后台 `SystemStatusListener` 定时采集 load/CPU，`checkSystem` 里与各阈值比对，超标的处理方式是**按 BBR 思想压容量**（取最近 minRt 与 maxThread 推算允许 QPS），对入口资源抛 `SystemBlockException`。系统保护是"最后的安全带"，建议只对入口配置。

## 7.3 来源访问控制（Authority）

白话版：按 origin（调用方标识，通常由 `RequestOriginParser` 从请求头解析，见 11.1）做黑白名单——黑名单里的来源一律拒绝，白名单外的来源一律拒绝。

【源码证据】`slots/block/authority/AuthoritySlot.java:57-58`：`AuthorityRuleChecker.passCheck(rule, context)` 不过则 `throw new AuthorityException(context.getOrigin(), rule)`；`AuthorityRuleChecker.passCheck`（`authority/AuthorityRuleChecker.java:30`）按 `limitApp` 与 origin 的匹配关系 + 白/黑名单模式（`AuthorityRule.strategy`）判定。它排在 StatisticSlot 之后——**需要 origin 统计节点已就位**。

## 7.4 本章小结

- 热点参数 = 资源级 `ParameterMetric` 里按参数值再开桶，令牌桶限 QPS、LongAdder 限线程；扩展模块经 SPI 合入 order -3000；
- 系统保护 = 整机指标（load/CPU/RT/线程/入口 QPS）+ BBR 压容量，只对入口资源生效；
- 授权规则 = origin 黑白名单，依赖 ClusterBuilderSlot 建好的 origin 统计节点与 `RequestOriginParser`。

---

# 八、规则装载与动态数据源：SentinelProperty 管道

> **本章模块定位**：`sentinel-core` 的 `property` 包 + 各 `RuleManager` + `transport` 的命令处理器。这是"规则怎么进来"的问题。

## 8.1 发布-订阅：SentinelProperty

白话版：所有规则管理器都是"订阅方"，任何"规则源"（内存调用、文件、Nacos、控制台推送）都把新规则通过一个 `SentinelProperty` 对象 `updateValue()` 进来，订阅方立即换账。**规则替换是原子换引用，正在执行的请求继续用旧规则，下一次检查用新规则——天然无锁热更**。

【源码证据】`property/DynamicSentinelProperty.java`：`CopyOnWriteArraySet<PropertyListener<T>> listeners`（L25），`addListener/removeListener`（L37/44），`updateValue` 遍历通知所有 listener（L55 区域）。以流控为例，`FlowRuleManager.register2Property(property)` 把一个"规则加载器 listener"挂上去，listener 收到新值后经 `FlowRuleUtil` 校验/分组后原子替换内部 ruleMap。

## 8.2 启动装载：InitFunc SPI

Sentinel 自身的初始化（CommandCenter、心跳、集群模式等）不走 Spring，走自己的 SPI：`init/InitExecutor.doInit()`（`Env.java:36` 类加载时触发）按 `META-INF/services/com.alibaba.csp.sentinel.init.InitFunc` 加载所有 `InitFunc` 实现（带 `@InitOrder` 排序）。transport 的 `CommandCenterInitFunc`、`HeartbeatSenderInitFunc`（`sentinel-transport-common/.../transport/init/`）就是从这里启动的。

## 8.3 控制台推规则：setRules 命令

白话版：你在控制台点"保存规则"，控制台向**应用进程内嵌的 HTTP 服务**（默认 8719 端口）发一个 GET 请求，进程内的命令处理器解析后调 `updateValue` ——这就是"推模式"。

【源码证据】`sentinel-transport-common/.../command/handler/ModifyRulesCommandHandler.java`：

```java
@CommandMapping(name = "setRules", desc = "modify the rules, accept param: type={ruleType}&data={ruleJson}")
public class ModifyRulesCommandHandler implements CommandHandler<String> { ... }
```

命令体系全景（`command/handler/` 目录实测）：`FetchActiveRuleCommandHandler`（拉当前规则）、`SendMetricCommandHandler`（控制台拉监控指标）、`FetchTreeCommandHandler`/`FetchJsonTreeCommandHandler`（拉调用树）、`BasicInfoCommandHandler`/`VersionCommandHandler`（环境信息）、`OnOffSet/GetCommandHandler`（全局开关）、`ApiCommandHandler`（命令列表）。

**推模式的痛点**：控制台内存 → 应用内存，应用重启规则全丢。工程实践必须把规则落到配置中心——这正是第十二章 SCA 数据源与 Nacos 的组合拳（推模式与拉模式的详细架构对比见 13.2）。

## 8.4 本章小结

- 规则热更 = `SentinelProperty.updateValue` + `PropertyListener`，原子换引用、无锁、秒级；
- 启动装载走 `InitFunc` SPI（与 Spring 无关）；
- 控制台"推规则"本质是进程内嵌 HTTP 服务的 `setRules` 命令，重启即失——生产必须配持久化数据源。

---

# 九、@SentinelResource 与 SPI 扩展

> **本章模块定位**：`sentinel-extension/sentinel-annotation-aspectj` + `sentinel-core` 的 `annotation`/`spi` 包。

## 9.1 注解只是个标记，生效靠 AOP

白话版：`@SentinelResource("资源名")` 本身什么也不做，SCA 在容器里注册了一个切面，把带注解的方法包一层 entry/exit，并把 BlockException 翻译成你的降级方法。

【源码证据】`sentinel-annotation-aspectj/.../SentinelResourceAspect.java`：

```java
@Pointcut("@annotation(com.alibaba.csp.sentinel.annotation.SentinelResource)")   // L38
public void sentinelResourceAnnotationPointcut() {}

@Around("sentinelResourceAnnotationPointcut()")                                    // L42
public Object invokeResourceWithSentinel(ProceedingJoinPoint pjp) throws Throwable {
    Method originMethod = resolveMethod(pjp);
    SentinelResource annotation = originMethod.getAnnotation(SentinelResource.class);
    ...
    entry = SphU.entry(resourceName, resourceType, entryType, pjp.getArgs());      // L56
} catch (BlockException ex) {
    return handleBlockException(pjp, annotation, ex);                              // L58-59
} catch (Throwable ex) {
    ... return handleFallback(pjp, annotation, ex);                                // L68
} finally {
    if (entry != null) { entry.exit(); }   // 业务正常返回也要退票
}
```

注解字段（`sentinel-core/.../annotation/SentinelResource.java`）：`value` 资源名、`entryType`（默认 OUT，L42）、`resourceType`（默认 0，L48）、`blockHandler`/`blockHandlerClass`（L53）、`fallback`/`fallbackClass`（L68）、`defaultFallback`（L78）。

## 9.2 降级方法的优先级链

【源码证据】`AbstractSentinelAspectSupport.java`：`handleBlockException`（L126-130）先按注解 `blockHandler` + `blockHandlerClass` 找方法（`extractBlockHandlerMethod`，L251，要求**参数列表与原方法一致且末尾多一个 BlockException**，找不到继续找 defaultBlockHandler）。

整体优先级（BlockException 路径）：**blockHandler（本类）→ blockHandlerClass 静态方法 → defaultFallback**；
其他 Throwable 路径：**fallback → fallbackClass 静态方法 → defaultFallback**（`handleFallback`，L84-89）。blockHandler 只处理"被 Sentinel 拒绝"（FlowException/DegradeException/…，见 `BlockException` 家族），fallback 处理业务异常——**两者职责不同，不是谁包谁**。

## 9.3 自定义 Slot 与 SPI 体系

想插自己的检查逻辑：实现 `AbstractLinkedProcessorSlot` + `@Spi(order = ...)` + 在自己的 `META-INF/services/com.alibaba.csp.sentinel.slotchain.ProcessorSlot` 里登记——ServiceLoader 会把它和内核 9 个 Slot 合并排序（3.1 节）。`SpiLoader`（`spi/SpiLoader.java:76` SPI 文件前缀、L168 排序加载、L330 classloader 遍历）是 Sentinel 自己实现的一套轻量 SPI（支持 order/isDefault/isSingleton），比 JDK ServiceLoader 多了排序与默认实现语义。

## 9.4 本章小结

- `@SentinelResource` = AOP 切面（SCA 已自动注册，见 10.2）+ SphU.entry + 手工降级方法路由；
- blockHandler 管"被拒"、fallback 管"业务异常"，defaultFallback 是两者共同的兜底；
- 自定义扩展点：Slot（检查逻辑）、InitFunc（启动逻辑）、MetricExtension（指标）、SentinelProperty（规则源）。

---

# 十、SCA 集成层（上）：自动配置与属性体系

> **本章模块定位**：`spring-cloud-starter-alibaba-sentinel` 模块。从这章起视角切到 SCA 仓库（2026.0.0.0-SNAPSHOT）。

## 10.1 开机自启：5 个自动配置类

【源码证据】`spring-cloud-starter-alibaba-sentinel/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`：

```
com.alibaba.cloud.sentinel.SentinelWebAutoConfiguration            ← Web MVC 适配（11.1）
com.alibaba.cloud.sentinel.SentinelWebFluxAutoConfiguration        ← WebFlux 适配（11.5）
com.alibaba.cloud.sentinel.endpoint.SentinelEndpointAutoConfiguration ← 健康检查端点
com.alibaba.cloud.sentinel.custom.SentinelAutoConfiguration        ← 核心 Bean（10.2）
com.alibaba.cloud.sentinel.feign.SentinelFeignAutoConfiguration    ← Feign 适配（11.3）
```

所有配置类都受 `spring.cloud.sentinel.enabled`（缺省 true）总开关控制。

## 10.2 SentinelAutoConfiguration：三个关键 Bean + 转换器工厂

【源码证据】`custom/SentinelAutoConfiguration.java`：

- `sentinelResourceAspect()`（L55-59）：注册第九章的 `SentinelResourceAspect`——**注解生效的总开关就是这个 Bean**；
- `sentinelBeanPostProcessor()`（L61-69）：`@ConditionalOnClass(RestTemplate)` + `resttemplate.sentinel.enabled`（缺省 true）——给 `@SentinelRestTemplate` 注解的 RestTemplate 织入拦截器（11.2）；
- `sentinelDataSourceHandler()`（L71-77）：规则数据源装配器（第十二章）；
- `SentinelConverterConfiguration`（L79-160）：为 5 类规则 × JSON/XML 注册 10 个 Converter Bean（如 `sentinel-json-flow-converter`，L94-97）——**注意 2026.0.x 已切换 Jackson 3**：`import tools.jackson.databind.ObjectMapper`（L29-32），`JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)`（L89-91），与系列文档《Spring Boot.md》记录的 Jackson 3（`tools.jackson`）口径一致。

## 10.3 SentinelApplicationContextInitializer：比自动配置更早的一步

白话版：Sentinel 内核读的是 **System property**（`csp.sentinel.*`），Spring 属性是 **spring.cloud.sentinel.\***。SCA 必须在 Sentinel 类被加载**之前**把 Spring 属性翻译过去——所以注册了一个 `ApplicationContextInitializer`，在 `spring.factories` 里声明，早于所有 Bean 创建。

【源码证据】`resources/META-INF/spring.factories`：

```
org.springframework.context.ApplicationContextInitializer=\
  com.alibaba.cloud.sentinel.custom.context.SentinelApplicationContextInitializer
```

`custom/context/SentinelApplicationContextInitializer.java`：`Binder.get(environment).bindOrCreate(SentinelConstants.PROPERTY_PREFIX, SentinelProperties.class)`（先手工绑定一次属性），然后逐项搬运：`spring.application.name` → `SentinelConfig.APP_NAME_PROP_KEY`、`transport.port` → `TransportConfig.SERVER_PORT`、`transport.dashboard` → `TransportConfig.CONSOLE_SERVER`、心跳间隔、日志目录/PID 等——**全部用"System property 为空才写"的保守策略**，不覆盖用户显式设置。搬运完成后，内核 `Env` 类加载时（2.1 节）读到的就是 Spring 配置过的值。

## 10.4 SentinelProperties：一套属性接管全内核

【源码证据】`SentinelProperties.java`（prefix = `spring.cloud.sentinel`，L43）：

| 属性组 | 字段 | 说明 |
|---|---|---|
| 顶层 | `eager`（L50） | 是否在启动时就触发初始化（否则首次请求才初始化） |
| 顶层 | `blockPage`（L60） | 被 Web 拦截后的跳转页 |
| `datasource`（L65） | `Map<String, DataSourcePropertiesConfiguration>` | 规则数据源声明（第十二章），**TreeMap 保证装载顺序稳定** |
| `transport`（L71） | port/dashboard/heartbeatIntervalMs/clientIp（L297-315） | 与控制台通信 |
| `metric`（L76） | fileSingleSize/fileTotalCount/charset | 指标日志滚动 |
| `filter`（L87） | order/urlPatterns/enabled（L356-367） | Web 拦截器行为 |
| `flow`（L92） | coldFactor | WarmUp 冷因子 |
| `servlet`/`log` | blockPage、dir/switchPid | 页面与日志 |
| 顶层 | `httpMethodSpecify`（L102）/`webContextUnify`（L108） | 资源名是否含 HTTP 方法；是否把整站请求收拢到一个上下文（默认 true——即**入口统一统计**，链路维度要关掉它） |

## 10.5 本章小结

- 5 个自动配置类 + 1 个 ApplicationContextInitializer，全部受 `spring.cloud.sentinel.enabled` 控制；
- 三个核心 Bean：注解切面、RestTemplate 后置处理器、数据源装配器；10 个规则 Converter（Jackson 3）；
- `SentinelApplicationContextInitializer` 在容器早期把 `spring.cloud.sentinel.*` 搬进 System property，解决"内核不认识 Spring 配置"的时序问题；
- `eager=false` 时 Sentinel 直到第一次 entry 才初始化（依赖 2.1 节 `Env` 类加载触发）。

---

# 十一、SCA 集成层（中）：Web / RestTemplate / Feign / 网关 / WebFlux 五条适配链

> **本章模块定位**：SCA starter 的 `SentinelWeb*` 类 + Sentinel 仓库的 `sentinel-spring-webmvc-v6x-adapter` / `sentinel-spring-cloud-gateway-v6x-adapter` + SCA 的 `spring-cloud-alibaba-sentinel-gateway` / `spring-cloud-circuitbreaker-sentinel`。

## 11.1 Web MVC：一个拦截器把全站 URL 变成资源

白话版：引入 starter 后什么都不配，每个 URL 就自动是一个资源（资源名 = URI），被限流时自动返回 429——整条链只需要一个 MVC 拦截器。

**① 装配**【源码证据】SCA `SentinelWebAutoConfiguration.java`（`@ConditionalOnWebApplication(SERVLET)` + `@ConditionalOnClass(SentinelWebInterceptor.class)`，L46-50）：注册 `SentinelWebInterceptor`（L67-73）、`SentinelWebMvcConfig`（L75-103，把 `httpMethodSpecify`/`webContextUnify` 灌进去，按优先级装配 BlockExceptionHandler：用户自定义 Bean > blockPage 重定向 > DefaultBlockExceptionHandler，L83-98）、并把 UrlCleaner/RequestOriginParser Bean 挂到配置上（L100-101）；`SentinelWebMvcConfigurer.addInterceptors`（`SentinelWebMvcConfigurer.java:44-49`）把拦截器注册进 MVC。

**② 拦截器本体**【源码证据】Sentinel 仓库 `sentinel-adapter/sentinel-spring-webmvc-v6x-adapter/.../AbstractSentinelInterceptor.java`（jakarta 版，`@since 1.8.8`，L51；实现 `AsyncHandlerInterceptor` 支持 servlet 异步，L53）：

```java
public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {   // L86
    resourceName = getResourceName(request);
    if (increaseReference(request, this.baseWebMvcConfig.getRequestRefName(), 1) != 1) {  // L94 引用计数，防 forward/include 重复记账
        return true;
    }
    String origin = parseOrigin(request);                     // L98 RequestOriginParser 解析来源
    ContextUtil.enter(contextName, origin);                   // L100 固定上下文 sentinel_spring_web_context
    Entry entry = SphU.entry(resourceName, ResourceTypeConstants.COMMON_WEB, EntryType.IN);  // L101
    request.setAttribute(baseWebMvcConfig.getRequestAttributeName(), entry);  // 存进 request 域
    return true;
} catch (BlockException e) {
    try { handleBlockException(request, response, resourceName, e); }   // L106
    finally { ContextUtil.exit(); }
    return false;                                             // 拦截器返回 false，Controller 不执行
}
```

退出侧 `afterCompletion → exit`（L149-179）：`Tracer.traceEntry(ex, entry)`（L198，把 Controller 抛的异常记进统计，供异常比例熔断用）→ `entry.exit()`（L200）→ `ContextUtil.exit()`。资源名 `getResourceName`（v6x `SentinelWebInterceptor.java:52-78`）默认为 URI（可经 UrlCleaner 清洗成 `/order/{id}` 模板）。

**③ 默认拒绝响应**【源码证据】`v6x/callback/DefaultBlockExceptionHandler.java:34-38`：`response.setStatus(429)` + 打印 `Blocked by Sentinel (flow limiting)`。

两个高频配置的语义在此章落地：`web-context-unify=true`（默认）时所有请求共用 `sentinel_spring_web_context` 一个入口（入口不细分，**链路模式规则需要置 false**）；`http-method-specify=true` 时资源名带方法前缀（`GET:/order`）。

## 11.2 RestTemplate：注解 + BeanPostProcessor + 拦截器

白话版：给 `RestTemplate` Bean 标 `@SentinelRestTemplate`，后置处理器就给它织一个 `ClientHttpRequestInterceptor`，对外调用按"主机 + 主机路径"两层资源防护，被拒/出错走你声明的 blockHandler/fallback 静态方法。

【源码证据】SCA starter：

- `annotation/SentinelRestTemplate.java:31-37`：注解字段 `blockHandler`/`fallback`（+Class 版本）；
- `custom/SentinelBeanPostProcessor.java:53`（实现 `MergedBeanDefinitionPostProcessor`）：从 BeanDefinition 读注解元数据（L74-78 缓存），匹配 RestTemplate 类型（L69-70），在后处理阶段为 Bean 生成代理并加入 `SentinelProtectInterceptor`；
- `custom/SentinelProtectInterceptor.java:81-83`：**嵌套两层 entry**——先 `SphU.entry(hostResource, EntryType.OUT)`（按主机限流），再 `SphU.entry(hostWithPathResource, EntryType.OUT)`（按主机+路径限流），两道都过才真正发请求；`BlockException.isBlockException(e)`（L93-94）判断异常是否为拒绝，是则走 `handleBlockException`（L119-137）：先 fallback（业务异常语义的兜底）再 blockHandler；方法解析走 `BlockClassRegistry.lookupFallback`（L157-158，全局静态注册表，`custom/BlockClassRegistry.java`）。

## 11.3 OpenFeign：换 Builder + 换 InvocationHandler

白话版：`feign.sentinel.enabled=true` 时，SCA 把 Feign 的 Builder 换成 `SentinelFeign.Builder`，每个 Feign 方法调用前 entry 一次（资源名 = `大写HTTP方法:url`），被拒或出错自动落到 fallback/fallbackFactory。

【源码证据】SCA starter `feign/` 包：

- `SentinelFeignAutoConfiguration.java:33-41`：`@ConditionalOnClass({SphU.class, Feign.class})` + `feign.sentinel.enabled` 条件下注册 `SentinelFeign.builder()`；
- `SentinelFeign.java:60`（`Builder extends Feign.Builder`）：重写 `invocationHandlerFactory`（L70）替换默认代理工厂；构建期从 `FeignClientFactoryBean` 反射读取 `fallback`/`fallbackFactory` 属性并从容器取实例（L115-133）——**还支持 `@FeignClient` 上写 `fallback=Bean名` 的属性式配置**（按 beanName 从上下文解析）；
- `SentinelInvocationHandler.java:110-129`：资源名 = `methodMetadata.template().method().toUpperCase() + url`（L110），`ContextUtil.enter(resourceName)` + `SphU.entry(resourceName, EntryType.OUT, 1, args)`（L114-115，**把方法实参传进去，热点参数规则由此生效**）；`BlockException` 走 `fallbackFactory.create(ex)` 再反射调用同签名方法（L123-129），没有 fallbackFactory 就把异常抛给上层（L142 附近）。

## 11.4 Spring Cloud Gateway：GlobalFilter + 块处理器

白话版：网关侧 Sentinel 把**路由 ID**当资源（也可对 API 分组自定义），`spring.cloud.gateway.sentinel.*` 控制行为。

【源码证据】SCA `spring-cloud-alibaba-sentinel-gateway` 模块：

- `scg/SentinelSCGAutoConfiguration.java`：`@ConditionalOnClass(GlobalFilter.class)`（L58）；`@PostConstruct init`（L77-83）设置应用类型为网关（`initAppType`，L92-95 写 `SentinelConfig.APP_TYPE_PROP_KEY`）并按 `fallback.mode=response/redirect` 装配 `GatewayCallbackManager.setBlockHandler`（L97-127）；注册 `SentinelGatewayBlockExceptionHandler`（L129-138）与 `SentinelGatewayFilter`（order -1，L140-148）；
- Sentinel 仓库 `sentinel-adapter/sentinel-spring-cloud-gateway-v6x-adapter/.../SentinelGatewayFilter.java:72-95`：`filter()` 里按 routeId（或匹配的 API 分组）以 `EntryType.IN` + 路由参数发起**异步 entry**（L85/95，带 `ContextConfig` 上下文），上下文名 = `GATEWAY_CONTEXT_ROUTE_PREFIX + route`（L103）；
- 规则对象是 `GatewayFlowRule`（gw-flow，见 12.3 的 RuleType）与 `ApiDefinition`（API 分组），存放在 `GatewayRuleManager`/`GatewayApiDefinitionManager`。

## 11.5 WebFlux 与健康端点

- `SentinelWebFluxAutoConfiguration.java`（`@ConditionalOnClass(SentinelReactorTransformer.class)`，L50）：与 Web MVC 平行的一套，把 `WebFluxCallbackManager.setBlockHandler` 挂上用户 Bean（L74），注册 WebFlux 拦截配置——响应式栈的资源名与拒绝处理同 11.1 对应；
- `endpoint/SentinelHealthIndicator.java:61`（`@ConditionalOnAvailableEndpoint`）：暴露 Sentinel 健康信息（拦截异常数等详情，`doHealthCheck` L76 起），供运维探活。

## 11.6 Spring Cloud CircuitBreaker 抽象：另一条"标准姿势"

白话版：除了 Sentinel 自己的注解，还能用 Spring Cloud 的 `CircuitBreakerFactory` 编程式 API——写法与 Resilience4j 完全互换，底层落回 Sentinel 的 DegradeRule。

【源码证据】SCA `spring-cloud-circuitbreaker-sentinel` 模块：

- `SentinelCircuitBreakerFactory.java:33-43`（继承 `CircuitBreakerFactory`）：`create(id)` 按配置构造 `SentinelCircuitBreaker`；
- `SentinelCircuitBreaker.java:87-91`（实现 `org.springframework.cloud.client.circuitbreaker.CircuitBreaker`）：`run(Supplier, Function)` 里 `SphU.entry(resourceName, entryType)`——被拒抛出的 BlockException 被翻译成标准异常交给 fallback；
- `SentinelConfigBuilder.java:33-67`：把 Resilience4j 风格的配置项（timeWindow/minRequestAmount/errorRatio/slowRt 等）翻译成 `DegradeRule` 列表；
- `feign/CircuitBreakerRuleChangeListener.java` + `FeignClientCircuitNameResolver.java`：为 Feign 客户端维度自动生成并动态更新熔断规则——把"熔断规则"也接上了配置中心的动态管道。

## 11.7 本章小结

- 五条适配链共用同一个内核动作（`SphU.entry`），差异只在"资源名怎么取、异常怎么翻译、拒绝怎么呈现"；
- Web 链的核心是 v6x `AbstractSentinelInterceptor`（jakarta，1.8.8+）：引用计数防 forward、异常入账供熔断、429 默认响应；
- RestTemplate 双层资源（主机 / 主机+路径）；Feign 资源名带方法与路径且实参传 entry（热点参数可用）；网关以路由 ID 为资源走异步 entry；
- `spring-cloud-circuitbreaker-sentinel` 让 Sentinel 成为 Spring Cloud 熔断抽象的可插拔实现，规则翻译由 `SentinelConfigBuilder` 完成。

---

# 十二、SCA 集成层（下）：规则数据源与 Nacos 动态配置

> **本章模块定位**：`spring-cloud-alibaba-sentinel-datasource`（属性绑定与 FactoryBean）+ Sentinel 的 `sentinel-datasource-nacos`/`sentinel-datasource-extension`。这是生产环境最关键的一章。

## 12.1 配置即声明：spring.cloud.sentinel.datasource.*

白话版：你在 application.yml 里声明"这条规则从 Nacos 的哪个 dataId 读"，starter 就自动建好"Nacos → 规则对象 → RuleManager"的全套管道。

```yaml
spring:
  cloud:
    sentinel:
      datasource:
        flow-ds:                      # 数据源名（任意）
          nacos:
            server-addr: localhost:8848
            dataId: gateway-flow-rules
            groupId: DEFAULT_GROUP
            rule-type: flow           # 规则类型 → 决定注册到哪个 RuleManager
```

【源码证据】`SentinelProperties.datasource` 是 `Map<String, DataSourcePropertiesConfiguration>`（L65）；`datasource/config/NacosDataSourceProperties.java`：`serverAddr/groupId(默认 DEFAULT_GROUP)/dataId`（L34-46），构造器把 `NacosDataSourceFactoryBean` 类名传给基类（L56-57），`preCheck` 在未配置时回退 `127.0.0.1:8848`（L62-70）。

## 12.2 装配全链：SmartInitializingSingleton → FactoryBean → postRegister

【源码证据】`custom/SentinelDataSourceHandler.java`（实现 `SmartInitializingSingleton`）：

- `afterSingletonsInstantiated()`（L78-106）：遍历 datasource 声明，`getValidField()` 确认**每个数据源名下只填了一种类型**（nacos/apollo/zk/redis/consul/file，多填即报错跳过，L82-88），然后 `registerBean` 动态注册 FactoryBean（Bean 名 = `{name}-sentinel-{type}-datasource`，L98-99）；
- `parseBeanDefinition`（L108-162）：反射读属性灌进 FactoryBean；`dataType=custom` 时按 `converter-class` 动态注册自定义 Converter Bean（L141-162）；
- FactoryBean 产出 Sentinel 的 `NacosDataSource`（`factorybean/NacosDataSourceFactoryBean.java:60-87`，`new NacosDataSource(properties, groupId, dataId, converter)`）；
- **关键一跳**：`AbstractDataSourceProperties.postRegister`（`datasource/config/AbstractDataSourceProperties.java:97-113`）按 `RuleType` 把数据源的 `getProperty()` 注册进对应管理器：

```java
switch (ruleType) {
    case FLOW     -> FlowRuleManager.register2Property(dataSource.getProperty());
    case DEGRADE  -> DegradeRuleManager.register2Property(dataSource.getProperty());
    case PARAM_FLOW -> ParamFlowRuleManager.register2Property(...);
    case SYSTEM -> ...; case AUTHORITY -> ...;
    case GW_FLOW -> GatewayRuleManager.register2Property(...);
    case GW_API_GROUP -> GatewayApiDefinitionManager.register2Property(...);
}
```

【源码证据】`datasource/RuleType.java:42-62`：7 个枚举值（FLOW/DEGRADE/PARAM_FLOW/SYSTEM/AUTHORITY/GW_FLOW/GW_API_GROUP），每个带默认 Converter 类。

## 12.3 NacosDataSource：监听配置变更 → updateValue

白话版：`NacosDataSource` 做三件事——初始化时读一次配置、注册 Nacos 监听器、每次配置变更都把 JSON 文本经 Converter 变成规则列表再 `updateValue`。这正是第八章 `SentinelProperty` 管道的起点。

【源码证据】Sentinel 仓库 `sentinel-extension/sentinel-datasource-nacos/.../NacosDataSource.java`：构造时 `NacosFactory.createConfigService(properties)`（L78-85 区域），注册 `Listener` 在配置变更回调里 `loadConfig → parser.convert → updateValue`（`AbstractDataSource.java:48` 一带：`T value = parser.convert(conf)` 后走 `updateValue`）——**Nacos 长轮询（默认 30s 内准实时）驱动规则热更，不依赖控制台**。

Converter 由 SCA 的 `SentinelConverter`/`JsonConverter`（`datasource/converter/`）承担：把 JSON 数组反序列化为第 10.2 节注册的那批 `sentinel-json-flow-converter` 等规则 Bean 的目标类型。

## 12.4 与控制台的关系：推 + 拉的组合拳

第 8.3 节说过控制台直推规则重启即失。生产推荐架构：

1. **规则持久化在 Nacos**（第十二章数据源负责"读"）；
2. 控制台改规则时**改为写 Nacos**（需按官方 dashboard README 定制 RuleController），Nacos 变更推给应用；
3. 应用进程内嵌的 8719 命令服务仍可接受控制台查询（拉指标、拉规则快照）。

这也解释了 SCA 为什么把 datasource 模块做成"多配置中心平等声明"（nacos/apollo/zookeeper/redis/consul/file 各有 Properties + FactoryBean）——**规则源是可插拔的，内核只认 SentinelProperty**。

## 12.5 本章小结

- 声明式配置 → `SentinelDataSourceHandler` 动态注册 FactoryBean → Sentinel 数据源对象 → `postRegister` 按 RuleType 挂进 RuleManager；
- Nacos 链路：NacosDataSource（ConfigService + Listener）→ Converter(JSON→规则对象) → `updateValue` → 监听器换规则；
- 7 种规则类型统一走这一条管道，网关规则（GW_FLOW/GW_API_GROUP）也不例外；
- 控制台负责"看"，Nacos 负责"存"，应用负责"执行"——三者职责分离是生产稳定态。

---

# 十三、控制台与集群流控

> **本章模块定位**：`sentinel-dashboard`（独立部署）与 `sentinel-cluster`/`sentinel-transport`。外围设施，一章讲清架构即可。

## 13.1 控制台 = 一个独立的 Spring MVC 应用

`sentinel-dashboard/` 模块（WAR/Boot 应用）本身**不是内核依赖**，它通过两条通道与每个应用交互：

1. **拉监控**：应用进程内嵌的 simple-http 服务暴露 `SendMetricCommandHandler`（`sentinel-transport-common/.../command/handler/`），控制台定时 GET 拉取第四章节级账本导出的分钟级指标（`StatisticNode.metrics()`，`StatisticNode.java:115-133`）；
2. **推规则/心跳**：应用的 `SimpleHttpHeartbeatSender` 定时向控制台发心跳（携带应用名/IP/port），控制台据此维护应用列表；规则下发走 8.3 节的 `setRules` 命令。

## 13.2 推模式 vs 拉模式

| 模式 | 路径 | 优点 | 缺点 |
|---|---|---|---|
| 原生推 | 控制台 → 8719 `setRules` | 实时 | 重启丢失，控制台无持久化 |
| 拉模式 | 应用定时去配置中心/文件读 | 简单 | 时效受轮询间隔限制 |
| 生产推荐 | 控制台 → **Nacos** → NacosDataSource → updateValue | 实时 + 持久化 + 审计 | 需改造控制台（官方文档提供方案） |

## 13.3 集群流控（sentinel-cluster）

单机限流的阈值只能"每实例一份"，N 台实例就是 N × 阈值。集群流控把计数挪到**中心 Token Server**：各实例（Token Client）请求远程取令牌，全局阈值精确共享。SCA starter 直接带上了 `sentinel-cluster-server-default`/`sentinel-cluster-client-default`（starter pom :134/:139），通过 `ClusterStateManager`（`sentinel-core/.../cluster/ClusterStateManager.java`）切换本机角色（服务端/客户端/关闭）。典型部署：网关层做 embedded token server、业务服务做 client。该模式与第十二章的动态规则正交——Token Server 的命名空间与限流规则同样可由 Nacos 下发。

## 13.4 本章小结

- 控制台是旁路应用：拉指标（SendMetric）+ 推规则（setRules）+ 收心跳；
- 规则持久化的正确姿势是"控制台写 Nacos + 应用从 Nacos 读"，原生推模式仅适合演示；
- 集群流控以中心 Token Server 换取全局精确配额，SCA 默认携带两端依赖。

---

# 十四、贯通视图：一次请求的三条生命线

> 把前十四章串成三个可复述的故事。读完后，任何 Sentinel 现象都应该能沿这三条线定位到源码。

## 14.1 生命线一：一个 HTTP 请求被限流

```
浏览器 → 网关/应用
  └ SentinelWebInterceptor.preHandle（AbstractSentinelInterceptor.java:86）
      ├ parseOrigin → RequestOriginParser（若配了）
      ├ ContextUtil.enter("sentinel_spring_web_context", origin)      （:100）
      ├ SphU.entry("/order/create", COMMON_WEB, IN)                   （:101）
      │   └ CtSph.entryWithPriority → lookProcessChain（缓存链）        （CtSph.java:117/194）
      │       └ chain.entry 正序十工位：
      │           NodeSelector(挂树) → ClusterBuilder(绑 ClusterNode/origin 节点)
      │           → Log → Statistic(fireEntry 先行)
      │           → Authority(黑名单?) → System(整机过载?) → ParamFlow(热点?)
      │           → FlowSlot → FlowRuleChecker → DefaultController.canPass
      │               → passQps() 超阈值 → return false
      │           → FlowException(limitApp, rule)                      （FlowRuleChecker.java:53）
      │       └ StatisticSlot catch → setBlockError + increaseBlockQps  （StatisticSlot.java:96-116）
      ├ handleBlockException → DefaultBlockExceptionHandler → 429       （DefaultBlockExceptionHandler.java:35）
      └ ContextUtil.exit；同步看板上该资源 block 计数 +1（分钟级账本供控制台拉取）
```

同时注意**记账与展示是分离的**：控制台上看到的曲线来自应用每秒上报的分钟级滑动窗口快照，而不是控制台自己算的。

## 14.2 生命线二：一个 Feign 调用触发熔断

```
ServiceA --@FeignClient--> ServiceB（下游持续超时/报错）
  └ SentinelInvocationHandler.invoke（SentinelInvocationHandler.java:72）
      ├ SphU.entry("GET:http://service-b/api/pay", OUT, 1, args)      （:114-115）
      ├ ……DegradeSlot.performChecking → circuitBreaker.tryPass         （DegradeSlot.java）
      │     [CLOSED] 放行 → 请求真实发出 → 业务异常
      │         exit 侧：Tracer.traceEntry 记异常（AbstractSentinelInterceptor 同款机制）
      │               → DegradeSlot.exit → ExceptionCircuitBreaker.onRequestComplete
      │               → 窗口内异常比例 > 阈值 且 ≥ minRequestAmount → fromClosedToOpen
      │     [OPEN]   recoveryTimeout 未到 → tryPass=false → DegradeException
      │     [HALF_OPEN] 放一个探子 → 成功 fromHalfOpenToClose / 失败回 OPEN
      └ BlockException.isBlockException(e) → fallbackFactory.create(ex)（:120-129）
            → 反射调用 fallback 同签名方法 → ServiceA 拿到降级结果
```

熔断的"自我修复"完全由状态机驱动：**OPEN 只是计时，不是永久判死**；探子请求是 HALF_OPEN 状态下的普通请求，只是被赋予了"一票定去留"的权力（`ResponseTimeCircuitBreaker.java:86-95`）。若 ServiceA 用的是 `spring-cloud-circuitbreaker-sentinel`，同一套断路器被 `SentinelCircuitBreaker.run` 包成标准 API（11.6 节），换 Resilience4j 只需换 Factory。

## 14.3 生命线三：一条规则从 Nacos 到生效

```
运维在 Nacos 控制台修改 dataId=flow-rules 的 JSON
  └ Nacos 长轮询推送 → NacosDataSource 的 Listener 回调
      └ loadConfig → SentinelConverter.convert（JSON → List<FlowRule>）
          └ DynamicSentinelProperty.updateValue → PropertyListener 遍历通知   （DynamicSentinelProperty.java:25/55）
              └ FlowRuleManager 的监听器：FlowRuleUtil 校验 + 按资源分组 → 原子替换 ruleMap
                  └ 下一个请求进入 FlowSlot 时 ruleProvider 取到的已是新规则      （FlowSlot.java:177-182）
```

这条管道**与 Spring 事件无关、与控制台无关**——SCA 只是把它"声明式化"了（第十二章）。理解了 `SentinelProperty`，你就理解了 Sentinel 所有"动态"能力（规则热更、全局开关、集群模式切换）的共同底座。

## 14.4 全文回顾：与系列文档的互相印证

- **与《Spring Framework.md》**：`@SentinelResource` 的生效依赖 AOP 自动代理（其第五章）；SCA 的适配器全是 Spring 扩展点（Interceptor/BeanPostProcessor/FactoryBean）上的薄壳——"面向扩展点设计"一以贯之；
- **与《Spring Boot.md》**：starter 的 5 个自动配置 + `AutoConfiguration.imports` + `spring.factories`（ContextInitializer）是 Boot 4 自动配置体系的教科书式应用；Jackson 3（`tools.jackson`）切换在 10.2 节实证；
- **与《Spring Cloud Config.md》/《Spring Cloud Bus.md》**：Nacos 动态规则的"配置中心为准、客户端监听"模式与 Config 的总线刷新是同构问题的两种解；
- **与《Spring Cloud Gateway.md》**：11.4 节的 GlobalFilter 前缀 (-1 order) 与网关过滤链的衔接、路由 ID 作资源名的取舍；
- **与《Seata.md》**：同样是"内核纯 Java + Spring 外壳"的分层，Seata 的入口是数据源代理，Sentinel 的入口是 entry/exit——**分布式两大战场（一致性与过载）在 Spring 生态里殊途同归地依赖代理与拦截器**；
- **与《Spring观测体系设计.md》**：Sentinel 的分钟级指标导出（metrics()）与 Prometheus exporter 模块是流量维度的观测拼图。

---

*本文完。源码基线：Sentinel `1.8.10`（commit `cf97bba`）+ Spring Cloud Alibaba `2026.0.0.0-SNAPSHOT`（2026.0.x 分支，commit `efd2fda`）；所有行号证据均可在 `D:\code\3rd\sentinel` 与 `D:\code\3rd\spring-cloud-alibaba` 中按类名 + 方法名定位复核。*
