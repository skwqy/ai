# Spring Cloud CircuitBreaker 深度源码解析（写给初学者的架构全景）

> **本文基于的源码**（双仓库）：
> - `D:\code\3rd\spring-cloud-circuitbreaker`，main 分支 **5.1.0-SNAPSHOT**（v5.1.0-M1 之后第 7 个提交，Git commit `1f4dab0`），Resilience4J 版本 **2.4.0**（`spring-cloud-circuitbreaker-dependencies/pom.xml`）；
> - `D:\code\3rd\spring-cloud-commons`，main 分支 **5.1.0-SNAPSHOT**（commit `589f31d`，与[Spring Cloud LoadBalancer.md](Spring Cloud LoadBalancer.md)同一基线）——**这套抽象 API（`org.springframework.cloud.client.circuitbreaker` 包）定义在 commons 里**，这是读本文最重要的一个前提。
>
> **版本取舍说明**：这个项目小得惊人——实现仓库全部主源码只有 **26 个 Java 文件**（Resilience4J 模块 13 个 + spring-retry 模块 5 个 + framework-retry 模块 7 个 + 1 个聚合 pom），抽象层 22 个文件在 commons。它的骨架自 1.0（2019-11）以来几乎没变：`CircuitBreaker.run(Supplier, Function)` 这个方法签名从第一天活到现在；演进都发生在**外围**（Bulkhead 装配、group 三级配置、disableThreadPool/disableTimeLimiter 逃生门、Observation 埋点、5.0 的声明式 HTTP 集成与 framework-retry 新实现）。因此本文内容对使用 Spring Cloud 2022.0 ~ 2025.1（circuitbreaker 3.x ~ 5.x）的读者全部适用；演进差异在 1.6 节逐项标注（全部经本地 git tag 实证）。
>
> **阅读约定**：每章"先白话、后源码"——先用一两句人话讲清"这是什么、为什么需要它"，再给出源码证据链（`模块/src/main/java/.../类名.java` + 方法名 + 行号 + 代码片段）。行号只对该快照精确，读者用 IDEA 打开源码按类名 + 方法名定位即可。涉及 Resilience4J 库自身（`io.github.resilience4j.*`）的机制（滑动窗口、状态机）本文只讲到"Spring Cloud 如何调用它"这一层——它是与 Sentinel 平行的另一个独立库，深入对比见[Spring Cloud Alibaba Sentinel.md](Spring Cloud Alibaba Sentinel.md)。

## 如何读这份文档

如果你是初学者，推荐两遍读法：

- **第一遍（建立地图，1 小时）**：只读第一章（总览）每节开头的白话段、各章"本章小结"、第十章（贯通视图）。目标是能回答：熔断器到底在保护什么？`run()` 里那几层装饰器是怎么套上去的？为什么 TimeLimiter 要拖一个线程池？
- **第二遍（深入源码）**：对照【源码证据】打开源码逐行读。顺序建议：第二章（背景，理解 Hystrix 退场的设计动机）→ 第三章（commons 抽象，全文的"接口面"）→ 第四章（阻塞实现，全文核心）→ 第五章（响应式实现）→ 第六章（Bulkhead）→ 第七章（两个 retry 实现）→ 第八、九章（装配与消费方，可跳读）。

---

# 一、总览：定位、设计哲学与整体架构

## 1.1 一句话定位

**Spring Cloud CircuitBreaker 是一套"熔断器门面"（Facade）**：它把"调用下游失败时怎么办"这件事抽象成两个极小的接口（阻塞的 `CircuitBreaker` 与响应式的 `ReactiveCircuitBreaker`），自己不实现任何熔断算法，而是让 Resilience4J、Spring Retry、Spring Framework Retry 等具体容错库来填空。你在 Spring Cloud Gateway 里写 `CircuitBreaker=foo` 过滤器、在接口上用 `@HttpExchange` 组自动熔断、或手工注入 `CircuitBreakerFactory`，背后都是这套门面。

它解决的不是"怎么熔断"（那是 Resilience4J/Sentinel 的事），而是**"怎么让应用代码不绑死在任何一家熔断库上"**：换实现只换 starter，业务代码里 `factory.create("foo").run(...)` 一字不改。

## 1.2 设计哲学：读源码前先记住四句话

1. **门面 + 注册表，不造轮子**。`CircuitBreakerFactory` 只是"按 id 发牌子"的抽象工厂；真正的状态机、滑动窗口全部委托给底层库（Resilience4J 的 `CircuitBreakerRegistry`、Spring Retry 的 `RetryTemplate`）。本仓库 26 个文件里找不到一行熔断算法——除了 framework-retry 模块那个约 250 行的自研三态机（第七章）。
2. **函数式包裹，不搞代理**。对比 Sentinel 用注解 + AOP 动态代理织入（[Sentinel.md](Spring Cloud Alibaba Sentinel.md) 13.1 节），这套 API 走的是**装饰器路线**：你把业务包成 `Supplier<T>` 传进去，它按"断路器→舱壁→限时器"的洋葱顺序层层包裹后执行。显式、无魔法、天然支持任意调用形态（HTTP、DB、本地方法）。
3. **配置三级寻址**。任何一个断路器找配置都走同一条路：**按 id 找 → 按 group 找 → 用默认**。id 是方法/操作级，group 是服务级，默认兜底——这套语义同时贯穿 CircuitBreaker、TimeLimiter、Bulkhead 三类组件（4.1 节、6.3 节）。
4. **逃生门比功能多**。默认实现为了"限时"会把你的代码扔进线程池跑，这对虚拟线程/响应式是灾难；于是 5.x 长出了 `disableThreadPool`、`disableTimeLimiter`、按 id/group 关闭限时器的一系列开关（4.5 节）。读懂这些开关，就读懂了这个项目的历史包袱。

## 1.3 双仓库三模块分层全景

```
┌────────────────────────── 你的业务代码 ──────────────────────────┐
│  circuitBreakerFactory.create("order").run(() -> call(), e -> fallback)  │
│  @HttpExchange 接口 + @HttpServiceFallback（声明式，5.0+）              │
├─────────────── 抽象层：spring-cloud-commons（22 文件）───────────────┤
│  CircuitBreaker / ReactiveCircuitBreaker        ← 两个 run 接口       │
│  CircuitBreakerFactory / Abstract...Factory     ← 工厂 + 配置注册表    │
│  ConfigBuilder / Customizer / NoFallbackAvailableException           │
│  observation/  ObservedCircuitBreaker 等 6 个    ← Micrometer 埋点装饰 │
│  httpservice/ 装饰器 + @HttpServiceFallback 等 7 个 ← 声明式 HTTP 集成 │
├─────────── 实现层：spring-cloud-circuitbreaker（26 文件）────────────┤
│  resilience4j 模块（13 文件，主力）：                                 │
│    Resilience4JCircuitBreaker(Factory)          ← 阻塞实现           │
│    ReactiveResilience4JCircuitBreaker(Factory)  ← 响应式实现          │
│    Resilience4jBulkheadProvider / Reactive...   ← 舱壁               │
│    Resilience4JAutoConfiguration / Reactive...  ← 自动装配 + 指标     │
│  spring-retry 模块（5 文件）：RetryTemplate 包装（5.0 起已 @Deprecated）│
│  framework-retry 模块（7 文件）：FW7 原生 retry + 自研三态机（5.0 新增）│
├────────────────────── starter 层（4 个空壳）────────────────────────┤
│  spring-cloud-starter-circuitbreaker-resilience4j                    │
│  spring-cloud-starter-circuitbreaker-reactor-resilience4j            │
│  spring-cloud-starter-circuitbreaker-spring-retry                    │
│  spring-cloud-starter-circuitbreaker-framework-retry                 │
├────────────────────── 第三方实现（不在本仓库）────────────────────────┤
│  spring-cloud-alibaba 的 spring-cloud-circuitbreaker-sentinel 模块    │
│  spring-cloud-netflix 的（已退役的）hystrix 实现                       │
└──────────────────────────────────────────────────────────────────────┘
```

> 一个容易踩的认知坑：**官方 README 明确说"API 在 commons，用法文档也在 commons"**（`docs/modules/ROOT/pages/spring-cloud-circuitbreaker.adoc` 第 6-9 行：*"The APIs implemented in Spring Cloud CircuitBreaker live in Spring Cloud Commons. The usage documentation for these APIs are located in the Spring Cloud Commons documentation."*）。所以本文把 commons 的 22 个文件当作第一公民来讲，而不是只讲实现仓库。

## 1.4 依赖图（以各模块 pom.xml 实证）

```
spring-cloud-starter-circuitbreaker-resilience4j
  ├─ spring-cloud-starter                （引.commons 等基础件）
  ├─ spring-cloud-circuitbreaker-resilience4j
  │    ├─ spring-cloud-commons           （抽象 API + observation）
  │    ├─ resilience4j-circuitbreaker    （必选）
  │    ├─ resilience4j-timelimiter       （必选！阻塞实现强依赖）
  │    ├─ resilience4j-spring-boot4      （r4j 自己的 Boot 集成，供 registry bean）
  │    ├─ resilience4j-bulkhead          （optional）
  │    ├─ resilience4j-reactor / reactor-core（optional，响应式）
  │    └─ micrometer-observation         （Observation 装饰）
  └─ resilience4j-circuitbreaker + resilience4j-timelimiter（再声明一次）

spring-cloud-circuitbreaker-spring-retry   → spring-retry + spring-core(classify)
spring-cloud-circuitbreaker-framework-retry → FW7 spring-core 的 core.retry 包（零新依赖！）
```

【源码证据】`spring-cloud-circuitbreaker-resilience4j/pom.xml`：`resilience4j-bulkhead` 与 `resilience4j-reactor` 均标 `<optional>true</optional>`——所以 starter 里替你显式声明（`spring-cloud-starter-circuitbreaker/spring-cloud-starter-circuitbreaker-resilience4j/pom.xml` 的依赖列表含 `resilience4j-circuitbreaker`、`resilience4j-timelimiter`；reactor 版 starter 多一个 `resilience4j-reactor`）。

framework-retry 模块最特别：它只依赖 spring-core（`org.springframework.core.retry.RetryTemplate`，FW 7.0 引入，类头 `@since 7.0`，`RetryTemplate.java:49`）——**不引入任何第三方容错库**，这是它存在的意义（7.3 节）。

## 1.5 关键问题 → Spring Cloud 方案映射（全文导览）

| 你的问题 | 方案所在 | 章节 |
|---|---|---|
| 熔断器到底保护什么？为什么 Hystrix 死了？ | 故障隔离的历史与动机 | 二 |
| `run()` 一行代码背后发生了什么？ | 洋葱装配四条路径 | 4.2 |
| 配置怎么生效？yaml 一行没写为什么也有默认行为？ | 三级寻址 + registry 兜底 | 4.1 |
| TimeLimiter 为什么默认把我的代码扔进线程池？ | Future 超时取消模型 | 4.4 |
| 怎么去掉线程池（配合虚拟线程）？ | disableThreadPool / disableTimeLimiter | 4.5 |
| 响应式实现和阻塞实现差别在哪？ | CircuitBreakerOperator + Reactor timeout | 五 |
| Bulkhead 舱壁什么时候用信号量、什么时候用线程池？ | useSemaphoreBulkhead 判定 | 6.2 |
| spring-retry 和 framework-retry 什么区别？为什么一个废弃一个新增？ | 第七章 | 七 |
| 指标在哪看？Observation 和 Micrometer 指标什么关系？ | 第八章 | 八 |
| Gateway 的 CircuitBreaker 过滤器怎么用这套 API？ | 消费方证据 | 9.1 |
| `@HttpExchange` 接口能声明式熔断吗？（5.0+） | httpservice 包 | 3.5 |
| 换 Sentinel 实现要改业务代码吗？ | 门面价值 + SCA 实现 | 9.3 |

## 1.6 版本演进：1.0 → 5.1 关键变化对比（全部本地 git tag 实证）

版本号跟随 spring-cloud-build 大版本（即 Spring Cloud 发布列车）：

| 版本 | 发布日期 | build 版本 | 发布列车 ↔ Boot | 关键事件 |
|---|---|---|---|---|
| v1.0.0.RELEASE | 2019-11-26 | 2.2.0.RELEASE | Hoxton ↔ Boot 2.2 | 首发：r4j + spring-retry 两个实现；hystrix 实现已于 2019-08-27（commit `3068bf0`）迁往 spring-cloud-netflix |
| v2.0.0 | 2020-12-21 | 3.0.0 | 2020.0 Ilford ↔ Boot 2.4 | 去掉对 Hoxton 的兼容包袱 |
| v2.0.1 | 2021-03-17 | 3.0.x | Ilford SR | **Bulkhead 支持**（`35125aa` 2021-02-18，Issue-86） |
| v2.1.0 | 2021-12-01 | 3.1.0 | 2021.0 Jubilee ↔ Boot 2.6 | — |
| v2.1.4 | 2022-09-06 | 3.1.x | Jubilee SR | **disableThreadPool**（`e620983` 2022-05-18） |
| v3.0.0 | 2022-12-15 | 4.0.0 | 2022.0 Kilburn ↔ Boot 3.0 | jakarta 化；**group 三级配置**（`create(id, groupName)` 重载，`f8eedff` 2022-02-17）；commons 同期 4.0.0 引入 **ObservedCircuitBreaker** |
| v3.0.4 | 2024-01-26 | 4.0.x | Kilburn SR | **disableTimeLimiter**（`08e4d44` 2023-11-30）；**groupExecutorServiceFactory**（`5fa638e` 2023-12-12，#180） |
| v3.1.0 | 2023-12-06 | 4.1.0 | 2023.0 Leyton ↔ Boot 3.2 | — |
| v3.2.0 | 2024-12-03 | 4.2.0 | 2024.0 Moorgate ↔ Boot 3.4 | — |
| v3.3.0 | 2025-05-29 | 4.3.0 | 2025.0 Northfields ↔ Boot 3.5 | — |
| **（跳过 4.x）** | — | — | — | 版本号直接从 3.3 跳到 5.0，**与 spring-cloud-build 5.0 / Boot 4.0 对齐**（与 commons 5.0.0 同步跳版） |
| v5.0.0 | 2025-11-24 | 5.0.0 | 2025.1 ↔ Boot 4.0 | **framework-retry 模块**（`4863af9` 2025-11-01）；**spring-retry 模块标 @Deprecated**（`b879d73` 2025-11-07）；JSpecify + NullAway（`99b3764`/`661a4af`）；commons 同期 5.0.0 引入 **httpservice 声明式集成** |
| v5.1.0-M1 | 2026-09-18 | 5.1.0-M1 | 2026.0 ↔ Boot 4.2 | 本文基线 v5.1.0-SNAPSHOT = M1 + 7 commits |

三条容易被问到的演进线：

1. **Hystrix 的葬礼**：Netflix 2018-11 宣布 Hystrix 进入维护模式；Spring Cloud 在 Hoxton 周期（2019）立项本抽象（Initial commit `57addc7` 2019-04-04），同年 8 月把 hystrix 实现让渡给 spring-cloud-netflix 仓库，11 月 GA。此后 Spring Cloud 官方推荐 Resilience4J。
2. **bulkhead-group 比 bulkhead 主 体 还 早 进 老 分支**：`3617fd7`（2021-04-09 "Support bulkhead group"）落在 v1.0.5.RELEASE（2021-04-21）——Hoxton 维护线当时还活着，特性同步回灌 1.0.x。读 tag 时要小心"维护分支回灌"现象。
3. **从加线程池到拆线程池**：1.0 时代阻塞实现默认"线程池 + TimeLimiter"（学 Hystrix 的隔离思路）；虚拟线程时代（Boot 4 / JDK 21+）这个模型反而碍事，于是 2.1.4 加 `disableThreadPool`、3.0.4 加 `disableTimeLimiter` 与按 id/group 的 `disableTimeLimiterMap`——演进方向是"把 Hystrix 的遗产一件件还回去"。

## 1.7 全文章节地图

| 章 | 内容 | 优先级 |
|---|---|---|
| 二 | 背景：熔断器原理与 Hystrix 退场 | ★★ |
| 三 | 抽象层（commons）：接口、工厂、观测装饰、声明式集成 | ★★★ |
| 四 | Resilience4J 阻塞实现：洋葱装配与线程池模型 | ★★★（全文核心） |
| 五 | Resilience4J 响应式实现 | ★★ |
| 六 | Bulkhead 双舱壁体系 | ★★ |
| 七 | spring-retry 与 framework-retry | ★★ |
| 八 | 自动装配与可观测性（配置大全） | ★ |
| 九 | 消费方：Gateway / 声明式 HTTP / Sentinel 实现 | ★★ |
| 十 | 贯通视图：三条时间线 + 三实现对比 | ★★ |
| 十一 | 附录：接口速查表 / 学习路线 / 源码入口清单 | ★（工具章） |

---

# 二、背景：熔断器在保护什么

## 2.1 白话：熔断器是电路保险丝

家里电路短路时保险丝熔断，保护的是**整栋房子的电路**，而不是那个短路的电器。微服务里的熔断器同理：当下游服务持续失败/超时时，熔断器"跳闸"（OPEN），后续调用**不再发出**而是立即走降级逻辑（fallback），保护的是**调用方自己的线程与连接资源**——不至于让一个慢下游把上游所有工作线程拖死，进而雪崩到整条调用链。

三态状态机（所有实现共用的心智模型）：

```
            失败率达到阈值
  CLOSED ────────────────────► OPEN
    ▲   （正常放行，统计失败）     │ 等待 openTimeout
    │                            ▼
    └──── 探测成功 ──── HALF_OPEN ◄── 放行一个探测请求
                          │
                          探测失败 → 回到 OPEN
```

- **CLOSED（闭合）**：正常放行，同时统计失败率（Resilience4J 用滑动窗口，Sentinel 也用滑动窗口——[Sentinel.md](Spring Cloud Alibaba Sentinel.md) 第四章 LeapArray）。
- **OPEN（打开）**：请求直接失败（`CallNotPermittedException`），不打下游。Gateway 会把它翻译成 503（9.1 节）。
- **HALF_OPEN（半开）**：冷却期到了放一个探测请求，成了关门、败了继续跳闸。

## 2.2 Hystrix 为什么退场

Hystrix（2012，Netflix）的核心设计是**线程池隔离**：每个下游依赖一个专用线程池，调用方线程把请求提交给池内线程并等待。这带来强隔离（下游慢只耗光自己的池），代价是：

1. 每个依赖一个池，线程资源开销大、排队/切换延迟高；
2. 编程模型重（`HystrixCommand` 继承体系）；
3. Netflix 2018-11-20 宣布进入维护模式（不再开发新特性，建议社区自寻替代）。

Spring Cloud 的应对分两步：**先抽象、后换实现**——2019 年立本项目统一 API，把 Hystrix 实现迁去 spring-cloud-netflix 养老（commit `3068bf0` 2019-08-27 *"Removing hystrix implementation as it was moved to spring cloud netflix"*），主推轻量的 Resilience4J。官方 README（`docs/modules/ROOT/pages/spring-cloud-circuitbreaker.adoc`）对三个现役实现的定位描述：

- **Resilience4J**：*"A comprehensive fault tolerance library ... Supports both blocking and reactive applications."*（全能主力）
- **Spring Retry**：*"Declarative retry support with circuit breaker functionality. A mature project ..."*（老将，5.0 起废弃）
- **Framework Retry**：*"Built on Spring Framework 7's native retry support ... without additional dependencies. Does not support reactive applications."*（FW7 新路线）

## 2.3 Resilience4J 的答案：函数式装饰器

Resilience4J（2016，Robert Winkler）反其道而行：不用线程池做默认隔离，而是提供一组**函数式装饰器**——`CircuitBreaker.decorateSupplier(cb, supplier)` 返回的还是 `Supplier<T>`，只是先过断路器记账再执行。理论上零额外线程（限时除外，见 4.4 节）。Spring Cloud 的 `run()` 本质就是把这些装饰器**按固定顺序套起来**：

```
CircuitBreaker.decorateSupplier(            ← 最外层：断路器记账/拒绝
  bulkheadProvider.decorateCallable(       ← 中层：舱壁限并发
    TimeLimiter.decorateFutureSupplier(    ← 内层：限时（需要 Future）
      () -> executorService.submit(toRun)  ← 最内：你的业务，扔进线程池变成 Future
    )))
```

这个"洋葱"就是第四章的主角。记住它，`run()` 的四条 if-else 分支（4.2 节）只是这颗洋葱在不同开关下的裁剪。

## 2.4 三家熔断库的哲学对照（速览）

| 维度 | Resilience4J | Sentinel（SCA） | Spring/Framework Retry |
|---|---|---|---|
| 编程模型 | 函数式装饰器（显式包裹） | 注解/AOP + 责任链 Slot（动态代理） | 模板方法（RetryTemplate.execute） |
| 熔断判据 | 滑动窗口失败率/慢调用比例 | 滑动窗口 + 丰富规则（QPS/异常/慢调用） | 单次"完整失败"（重试耗尽即跳闸） |
| 状态管理 | 库内 CircuitBreaker 对象 | 库内 CircuitBreaker 对象（1.8.0+） | 实现自管（framework-retry 自研三态机） |
| 控制台 | 无自带（靠 Micrometer） | 自带 Dashboard 实时推送 | 无 |
| 与本抽象关系 | 主力实现 | SCA 提供 sentinel 实现 | 两个官方实现 |

Sentinel 细节见[Spring Cloud Alibaba Sentinel.md](Spring Cloud Alibaba Sentinel.md)（尤其 6.x 章断路器、13.x 章集成）；本章只需建立"门面之下，每家的熔断语义并不相同"的意识。

## 2.5 本章小结

- 熔断器保护的是**调用方**的资源，不是下游；三态状态机是共同心智模型。
- Hystrix 退场的根因是线程池隔离太重；本项目因此诞生，先抽象后换芯。
- Resilience4J 用函数式装饰器实现"零线程开销的记账"（限时除外）——Spring Cloud 的 `run()` 只是把装饰器按洋葱顺序装配起来。

---

# 三、抽象层：spring-cloud-commons 的 circuitbreaker 包

## 3.1 CircuitBreaker 接口：两个方法定义一个门面

白话版：整个抽象层的"用户面"只有一个概念——"给我一个能跑 `Supplier` 并在出错时调用 fallback 的东西"。接口小到可以全文贴出：

【源码证据】`spring-cloud-commons/src/main/java/org/springframework/cloud/client/circuitbreaker/CircuitBreaker.java:29-39`：

```java
public interface CircuitBreaker {

	default <T> T run(Supplier<T> toRun) {
		return run(toRun, throwable -> {
			throw new NoFallbackAvailableException("No fallback available.", throwable);
		});
	}

	<T> T run(Supplier<T> toRun, Function<@Nullable Throwable, T> fallback);

}
```

三个设计细节值得停下来看：

1. **唯一抽象方法只有 `run(Supplier, Function)`**。单参重载是 default 方法，语义是"没给 fallback？那就把异常包成 `NoFallbackAvailableException` 抛出去"——调用方能靠这个异常类型区分"业务真的错了"和"熔断器拦下来了但没人接"。
2. **fallback 的入参是 `@Nullable Throwable`**。为什么可空？因为 Hystrix 时代存在"断路器打开但没有历史异常可给"的场景（实现可以传 null），实现方约定此时应返回一个兜底值。5.x 全线 JSpecify 化后这个注解是 `org.jspecify.annotations.Nullable`（v5.0.0 引入，1.6 节）。
3. **`Supplier`/`Function` 而不是 `Callable`**。没有受检异常——业务代码不用到处 `throws`，异常统一走 Throwable 捕获链。这是整个 API"轻"的根基。

配套异常类（同包 `NoFallbackAvailableException.java:24-29`）：就是一个带 cause 的 RuntimeException，无多话。

## 3.2 ReactiveCircuitBreaker：Mono/Flux 各一对

响应式版接口与阻塞版完全对称，只是把 `Supplier` 换成 `Mono`/`Flux`、fallback 换成返回Publisher 的 Function：

【源码证据】`ReactiveCircuitBreaker.java:29-47`：

```java
public interface ReactiveCircuitBreaker {

	default <T> Mono<T> run(Mono<T> toRun) { ... 同样抛 NoFallbackAvailableException ... }

	<T> Mono<T> run(Mono<T> toRun, Function<Throwable, Mono<T>> fallback);

	default <T> Flux<T> run(Flux<T> toRun) { ... }

	<T> Flux<T> run(Flux<T> toRun, Function<Throwable, Flux<T>> fallback);

}
```

注意 fallback 拿到的 `Throwable` 不可空（响应式链上错误必然存在）。四个抽象/default 方法两两对称——这保证了 Gateway 这类响应式消费方（9.1 节）与阻塞消费方共享同一套工厂心智。

## 3.3 工厂与配置体系：AbstractCircuitBreakerFactory

接口只管"跑"，**"按 id 创建 + 按下发配置"**由工厂层负责。三个类构成一条继承线：

```
AbstractCircuitBreakerFactory<CONF, CONFB>          （配置注册表，abstract）
  ├─ CircuitBreakerFactory<CONF, CONFB>             （+create(id)/create(id, groupName)）
  └─ ReactiveCircuitBreakerFactory<CONF, CONFB>     （+create 同款）
```

【源码证据】`AbstractCircuitBreakerFactory.java:28-68` 的全部核心：

```java
public abstract class AbstractCircuitBreakerFactory<CONF, CONFB extends ConfigBuilder<CONF>> {

	private final ConcurrentHashMap<String, CONF> configurations = new ConcurrentHashMap<>();

	public void configure(Consumer<CONFB> consumer, String... ids) {
		for (String id : ids) {
			CONFB builder = configBuilder(id);
			consumer.accept(builder);
			CONF conf = builder.build();
			getConfigurations().put(id, conf);
		}
	}

	protected ConcurrentHashMap<String, CONF> getConfigurations() { return configurations; }

	protected abstract CONFB configBuilder(String id);

	public abstract void configureDefault(Function<String, CONF> defaultConfiguration);

}
```

白话版：这其实就是一个 **`Map<id, 配置>` 注册表**加两个入口——`configure(consumer, "id1", "id2")` 给**特定**断路器下配置（Java DSL 风格，consumer 收到 builder 随便改），`configureDefault(fn)` 给**所有**断路器兜底（fn 入参是 id，允许按 id 算差异化默认值）。实现层（第四章）每次 `create(id)` 时先查这张表，查不到再走 default 函数——"两级 Java DSL 配置 + 底层库 registry 第三级"就是 4.1 节的完整三级寻址。

【源码证据】`CircuitBreakerFactory.java:25-34`：

```java
public abstract class CircuitBreakerFactory<CONF, CONFB extends ConfigBuilder<CONF>>
		extends AbstractCircuitBreakerFactory<CONF, CONFB> {

	public abstract CircuitBreaker create(String id);

	public CircuitBreaker create(String id, String groupName) {
		return create(id);          // 基类默认忽略 group，实现可覆写
	}

}
```

group 重载（v3.0.0 引入）默认退化为 `create(id)`——保证"只关心 id"的简单实现（如两个 retry 模块）不必理解 group 语义。

两个配角：

- `ConfigBuilder<CONF>`（`ConfigBuilder.java:24-28`）：只有一个 `CONF build()`，纯粹为了让泛型系统能表达"builder 能产出 CONF"。
- `Customizer<TOCUSTOMIZE>`（`Customizer.java:29-53`）：函数式接口 `customize(T)`，Spring Cloud 全家桶通用的"定制器"约定（LoadBalancer/Gateway 同款）。它还带一个静态工具 `once(customizer, keyMapper)`（L42-51）：用 `ConcurrentHashMap.computeIfAbsent` 保证同一目标只被定制一次——防止自动配置里 Customizer bean 被意外调用多次。

## 3.4 ObservedCircuitBreaker：Observation 埋点装饰（4.0.0 引入）

白话版：Micrometer Observation 是"一次调用"级别的观测模型（trace + metrics 二合一，详见[Spring观测体系设计.md](Spring观测体系设计.md)）。这个类把任意 `CircuitBreaker` 包一层，业务执行与 fallback 执行各自产生一个 observation span——**不改业务代码即可获得熔断调用的耗时与错误遥测**。

【源码证据】`observation/ObservedCircuitBreaker.java:46-54`：

```java
@Override
public <T> T run(Supplier<T> toRun, Function<Throwable, T> fallback) {
	return this.delegate.run(
			new ObservedSupplier<>(this.customConvention,
					new CircuitBreakerObservationContext(CircuitBreakerObservationContext.Type.SUPPLIER),
					"circuit-breaker", this.observationRegistry, toRun),
			new ObservedFunction<>(this.customConvention,
					new CircuitBreakerObservationContext(CircuitBreakerObservationContext.Type.FUNCTION),
					"circuit-breaker fallback", this.observationRegistry, fallback));
}
```

装饰思路是"**包裹传入的 lambda**"而非"包裹断路器本身"：把 `toRun` 换成 `ObservedSupplier`（内部 `observation.observe(delegate)`，`ObservedSupplier.java:47-49`），fallback 换成 `ObservedFunction`。两个 observation 的上下文名分别为 `"circuit-breaker"` 与 `"circuit-breaker fallback"`，通过 `parentObservation(observationRegistry.getCurrentObservation())`（L42）挂到调用方已有的 trace 上。

观测元数据三件套（同包）：

- `CircuitBreakerObservationDocumentation.java:29-67`：枚举声明两个 observation（SUPPLIER/FUNCTION），前缀 `"spring.cloud.circuitbreaker"`，低基数标签只有一个 `spring.cloud.circuitbreaker.type`（L74-79，取值 `supplier`/`function`）——**故意不放 id/group 进标签**，避免时间序列爆炸（id 多了就是高基数）。
- `DefaultCircuitBreakerObservationConvention.java:37-53`：默认约定，`getName()` 返回 `"spring.cloud.circuitbreaker"`，contextualName 按 SUPPLIER/FUNCTION 给上文两个名字。
- `CircuitBreakerObservationContext.java:27-63`：`Observation.Context` 子类 + `Type{FUNCTION, SUPPLIER}` 枚举。

**什么时候会被包上？** 由实现工厂决定：`Resilience4JCircuitBreakerFactory.tryObservedCircuitBreaker`（4.1 节）在 `observationRegistry` 不是 NOOP 时自动包一层。即装了 Micrometer（有 `ObservationRegistry` bean）就自动生效，无需配置。

## 3.5 httpservice 包：`@HttpExchange` 声明式熔断（5.0.0 引入，前瞻）

白话版：Boot 3.2+/FW 6.1+ 的接口式 HTTP 客户端（`@HttpExchange`，类似 OpenFeign 的官方版）支持按 **group** 定制。commons 5.0.0 借这个口子把熔断做成了**声明式**：给 HTTP 服务接口组一个名字，再用 `@HttpServiceFallback` 指定 fallback 类，接口方法就自动被熔断器包住——不写一行 `factory.create(...)`。

四个角色配合（全在 `httpservice/` 包）：

1. **组配置器**（接 Boot 的 `HttpServiceGroupConfigurer` 扩展点）：`CircuitBreakerRestClientHttpServiceGroupConfigurer`（`ORDER = 15`，L45）/`CircuitBreakerWebClientHttpServiceGroupConfigurer`（`ORDER = 16`，L49——注释说明比 Boot 的配置器晚跑）。以 WebClient 版为例（`CircuitBreakerWebClientHttpServiceGroupConfigurer.java:65-80`）：

```java
@Override
public void configureGroups(Groups<WebClient.Builder> groups) {
	groups.forEachGroup((group, clientBuilder, factoryBuilder) -> {
		String groupName = group.name();
		Map<String, Class<?>> perGroupFallbackClasses = resolveAnnotatedFallbackClasses(applicationContext, groupName);
		Map<String, Class<?>> fallbackClasses = !perGroupFallbackClasses.isEmpty() ? perGroupFallbackClasses
				: resolveAnnotatedFallbackClasses(applicationContext, null);
		factoryBuilder.httpRequestValuesProcessor(new CircuitBreakerRequestValueProcessor());
		factoryBuilder.exchangeAdapterDecorator(httpExchangeAdapter -> {
			Assert.isInstanceOf(ReactorHttpExchangeAdapter.class, httpExchangeAdapter);
			return new ReactiveCircuitBreakerAdapterDecorator((ReactorHttpExchangeAdapter) httpExchangeAdapter,
					buildReactiveCircuitBreaker(groupName), buildCircuitBreaker(groupName), fallbackClasses);
		});
	});
}
```

   两件事：① 扫容器里带 `@HttpServiceFallback` 注解的 bean，解析出"组 → fallback 类"映射（组内没有就取全局默认）；② 给这组接口的 `HttpExchangeAdapter` 套熔断装饰器，断路器 id = 组名（`circuitBreakerFactory.create(groupName)`，L87）。

2. **注解** `@HttpServiceFallback`（`HttpServiceFallback.java:67-115`）：可重复注解，三个属性——`value()` fallback 类、`service()` 适用的服务接口（不填=全组默认）、`group()` 组名（不填=全局默认）。查找优先级：**组内按 service 匹配 > 组内 default 键 > 全局按 service 匹配 > 全局 default 键**（`CircuitBreakerConfigurerUtils.java:170-189` 的 `getFallback`，`DEFAULT_FALLBACK_KEY = "default"`，L54）。

3. **请求值处理器** `CircuitBreakerRequestValueProcessor`（`CircuitBreakerRequestValueProcessor.java:46-76`）：把当前调用的方法名/参数类型/实参/返回类型/声明类塞进 `HttpRequestValues` 的 attributes（键名如 `spring.cloud.method.name`）——**因为 fallback 触发时拿到的只有 requestValues，反射匹配方法全靠这些属性**。

4. **适配器装饰器** `CircuitBreakerAdapterDecorator`（阻塞，`CircuitBreakerAdapterDecorator.java:57-148`）/ `ReactiveCircuitBreakerAdapterDecorator`（`ReactiveCircuitBreakerAdapterDecorator.java:62-239`）：覆写全部 `exchange*` 方法，逐个包 `circuitBreaker.run(...)`。fallback 匹配规则（阻塞版 javadoc L44-50）：优先找**同名同参**方法，找不到再找**同名、Throwable 打头 + 原参数**的方法（能拿到失败原因）。fallback 类会被 `ProxyFactory`（CGLIB，`setProxyTargetClass(true)`）包成代理并惰性缓存（`getFallbackProxies`，L137-146，双重检查锁）。

响应式装饰器有个精妙设计：它**同时持有阻塞与响应式两个断路器**（构造器 L74-81）——返回 `Mono/Flux` 的方法（`exchangeForMono/exchangeForBodyFlux` 等 L119-160）走 `reactiveCircuitBreaker.run`；返回阻塞类型的方法（`exchange/exchangeForEntity` 等 L84-116）走阻塞 `circuitBreaker.run`。**按返回类型分流**，因为 `@HttpExchange` 接口方法两种签名都合法。而阻塞 `create` 出来的断路器 id 是组名、响应式是 `groupName + "-reactive"`（组配置器 L83）——两套状态机互不干扰。

> 提示：这套东西 5.0.0（Boot 4.0 线）才有。3.x 用户要达到类似效果得手写切面或等价封装；它在 commons 而非实现仓库，意味着**任何实现（含 SCA 的 Sentinel）都能免费获得声明式能力**。

## 3.6 本章小结

- 用户面 = 2 个接口（阻塞/响应式）× 各 2 个 run；异常语义靠 `NoFallbackAvailableException`。
- 工厂面 = `Map<id, CONF>` 注册表 + default 函数；group 是 v3.0.0 加的可选维度。
- 观测面 = `ObservedCircuitBreaker` 装饰（4.0.0），标签刻意低基数。
- 声明式面 = httpservice 包（5.0.0），借 Boot 的 HttpServiceGroup 扩展点 + `@HttpServiceFallback` 反射匹配，响应式装饰器按返回类型分流双断路器。

---

# 四、Resilience4J 阻塞实现：洋葱装配与线程池模型（全文核心）

## 4.1 工厂：create 的三级配置寻址

白话版：`Resilience4JCircuitBreakerFactory` 的 `create(id)` 做的事可以概括为"**查三次配置，造一个断路器**"。先看它持有什么：

【源码证据】`spring-cloud-circuitbreaker-resilience4j/.../Resilience4JCircuitBreakerFactory.java:49-74`（字段节选）：

```java
public class Resilience4JCircuitBreakerFactory extends
		CircuitBreakerFactory<Resilience4JConfigBuilder.Resilience4JCircuitBreakerConfiguration, Resilience4JConfigBuilder> {

	private @Nullable Resilience4jBulkheadProvider bulkheadProvider;
	private Function<String, Resilience4JCircuitBreakerConfiguration> defaultConfiguration;
	private CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();
	private TimeLimiterRegistry timeLimiterRegistry = TimeLimiterRegistry.ofDefaults();
	private ExecutorService executorService = Executors.newCachedThreadPool();          // 全局兜底线程池
	private Function<String, ExecutorService> groupExecutorServiceFactory = group -> Executors.newCachedThreadPool();
	private ConcurrentHashMap<String, ExecutorService> executorServices = new ConcurrentHashMap<>();  // group -> 池
	private Map<String, Customizer<CircuitBreaker>> circuitBreakerCustomizers = new HashMap<>();
	private final Set<String> loggedTimeLimiterIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
	private ObservationRegistry observationRegistry = ObservationRegistry.NOOP;
```

两个 registry 字段是 Resilience4J 自己的"配置仓库"（`resilience4j-spring-boot4` 集成会把 yaml 里 `resilience4j.circuitbreaker.configs.xxx` 之类的配置装进去）。核心寻址逻辑在私有 `create(id, groupName, executor)`：

【源码证据】同文件 L177-199：

```java
private Resilience4JCircuitBreaker create(String id, String groupName,
		ExecutorService circuitBreakerExecutorService) {
	Resilience4JCircuitBreakerConfiguration defaultConfig = getConfigurations()
		.computeIfAbsent(id, defaultConfiguration);                       // ① Java DSL：configure()/configureDefault()
	CircuitBreakerConfig circuitBreakerConfig = this.circuitBreakerRegistry.getConfiguration(id)
		.orElseGet(() -> this.circuitBreakerRegistry.getConfiguration(groupName)
			.orElseGet(defaultConfig::getCircuitBreakerConfig));          // ② registry：id > group > ①
	TimeLimiterConfig timeLimiterConfig = this.timeLimiterRegistry.getConfiguration(id)
		.orElseGet(() -> this.timeLimiterRegistry.getConfiguration(groupName).orElseGet(() -> {
			TimeLimiterConfig defaultTimeLimiterConfig = defaultConfig.getTimeLimiterConfig();
			if (loggedTimeLimiterIds.add(id)) {                           // 每个 id 只警告一次
				... LOG.warn("No timeLimiterConfig found for '" + id + "' in time limiter registry, using " + ...) ...
			}
			return defaultTimeLimiterConfig;
		}));
```

**三级寻址**（高 → 低）：

1. **底层库 registry 按 id**：yaml/`CircuitBreakerRegistry` 里给这个 id 配的；
2. **底层库 registry 按 group**：给整个服务组配的（v3.0.0 引入，1.6 节）；
3. **Java DSL 默认**：你 `configureDefault(id -> new Resilience4JConfigBuilder(id)...)` 的；再没有就构造器里那个兜底（L87-90：直接用两个 registry 的 `getDefaultConfig()`，即 r4j 库默认——失败率阈值 50%、窗口 100 次调用；TimeLimiter 默认 1 秒超时）。

TimeLimiter 的 warn 日志（L187-197）是排障金句来源：**"No timeLimiterConfig found for 'xxx' in time limiter registry, using TimeLimiterConfig{...}"**——看到它说明该 id 走的是最后一级默认（1 秒限时），很多"为什么 1 秒就超时"的疑问答案在这。

两个公开 create 的差异只在**用哪个线程池**：

【源码证据】同文件 L134-148：

```java
@Override
public org.springframework.cloud.client.circuitbreaker.CircuitBreaker create(String id) {
	Assert.hasText(id, "A CircuitBreaker must have an id.");
	Resilience4JCircuitBreaker resilience4JCircuitBreaker = create(id, id, this.executorService);
	return tryObservedCircuitBreaker(resilience4JCircuitBreaker);
}

@Override
public org.springframework.cloud.client.circuitbreaker.CircuitBreaker create(String id, String groupName) {
	Assert.hasText(id, "A CircuitBreaker must have an id.");
	Assert.hasText(groupName, "A CircuitBreaker must have a group name.");
	final ExecutorService groupExecutorService = executorServices.computeIfAbsent(groupName,
			groupExecutorServiceFactory);                                 // 每个 group 一个 cachedThreadPool（惰性）
	Resilience4JCircuitBreaker resilience4JCircuitBreaker = create(id, groupName, groupExecutorService);
	return tryObservedCircuitBreaker(resilience4JCircuitBreaker);
}
```

`tryObservedCircuitBreaker`（L150-156）：ObservationRegistry 非 NOOP 就包 `ObservedCircuitBreaker`（3.4 节的自动生效点）。`groupExecutorServiceFactory` 可由 `configureGroupExecutorService` 替换（v3.0.4 引入，#180——正是为了虚拟线程时代自管线程池）。

> **id 与 group 的语义分工**：id 是"这一个操作"（如 Gateway 里路由名/过滤器名），group 是"这一类操作共享的资源边界"——同 group 的断路器共享一个 cachedThreadPool 与 registry 里的 group 配置。`create(id)` 时 group=id（线程池用全局兜底那个）。

## 4.2 run 的洋葱装配：四条路径

白话版：`Resilience4JCircuitBreaker.run` 把 2.3 节那颗洋葱按"有没有舱壁、有没有线程池"裁剪成四条路径。这个类本身**不存断路器状态对象**——每次 run 从 registry 现取（`registry.circuitBreaker(id, config, tags)`，取不到就按 config 建，r4j registry 语义），所以同一 id 的多次 `create()`/`run()` 天然共享状态：

【源码证据】`Resilience4JCircuitBreaker.java:97-141`：

```java
@Override
public <T> T run(Supplier<T> toRun, Function<@Nullable Throwable, T> fallback) {
	final Map<String, String> tags = Map.of(CIRCUIT_BREAKER_GROUP_TAG, this.groupName);
	Optional<TimeLimiter> timeLimiter = loadTimeLimiter();
	io.github.resilience4j.circuitbreaker.CircuitBreaker defaultCircuitBreaker = registry.circuitBreaker(this.id,
			this.circuitBreakerConfig, tags);
	circuitBreakerCustomizer.ifPresent(customizer -> customizer.customize(defaultCircuitBreaker));
	if (bulkheadProvider != null) {
		if (executorService != null) {
			Supplier<Future<T>> futureSupplier = () -> executorService.submit(toRun::get);
			Callable<T> timeLimitedCall = timeLimiter
				.map(tl -> TimeLimiter.decorateFutureSupplier(tl, futureSupplier))
				.orElse(() -> futureSupplier.get().get());
			Callable<T> bulkheadCall = bulkheadProvider.decorateCallable(this.groupName, tags, timeLimitedCall);
			Callable<T> circuitBreakerCall = io.github.resilience4j.circuitbreaker.CircuitBreaker
				.decorateCallable(defaultCircuitBreaker, bulkheadCall);
			return getAndApplyFallback(circuitBreakerCall, fallback);
		}
		else { /* 舱壁在、线程池关：decorateCallable(bulkhead, toRun::get) → CB 装饰 → fallback */ }
	}
	else {
		if (executorService != null) { /* 无舱壁、有线程池：TimeLimiter → CB → fallback */ }
		else {
			Supplier<T> decorator = io.github.resilience4j.circuitbreaker.CircuitBreaker
				.decorateSupplier(defaultCircuitBreaker, toRun);          // 最薄路径
			return getAndApplyFallback(decorator, fallback);
		}
	}
}
```

四条路径对照表：

| 路径 | 条件 | 洋葱（外→内） | 特性 |
|---|---|---|---|
| A | bulkhead 有 + 池开 | CB → Bulkhead → TimeLimiter → **executor.submit** → 业务 | 默认全开就是这个 |
| B | bulkhead 有 + 池关 | CB → Bulkhead → 业务 | 6.2 节信号量舱壁常见搭档 |
| C | bulkhead 无 + 池开 | CB → TimeLimiter → **executor.submit** → 业务 | 未引 bulkhead 依赖时 |
| D | bulkhead 无 + 池关 | CB → 业务 | 薄到只有断路器记账 |

三个横切细节：

1. **fallback 兜底**：`getAndApplyFallback`（L143-159）就是 try-catch——`supplier.get()`/`callable.call()` 抛任何 Throwable 都交给 `fallback.apply(t)`。**断路器打开时 r4j 抛 `CallNotPermittedException` 也走这里**，所以 fallback 同时接住"业务失败"和"熔断拒绝"两种情况（Gateway 靠 instanceof 区分，9.1 节）。
2. **tags**：`Map.of("group", groupName)`（`CIRCUIT_BREAKER_GROUP_TAG = "group"`，L44）传给 registry——r4j 的 Micrometer 指标会带上这个 tag（8.3 节的 group 过滤器靠它）。
3. **`loadTimeLimiter` 的懒注册**（L161-168）：按 id 找 → 按 group 找 → 都没有就**用本地 config 现场 `timeLimiter(id, config, tags)` 注册进 registry 并返回**。此后 registry 里就有了，下次同 id 直接命中——这就是 4.1 节那条 warn 日志只出现一次的原因（`loggedTimeLimiterIds` 侧配合）。

## 4.3 线程池模型：默认每层都在换线程

默认路径 A 下，你的业务代码执行线程 = **group 专属 cachedThreadPool 的某个线程**，不是调用方线程。这与 Hystrix 的线程隔离一脉相承（历史惯性），但注意 cachedThreadPool 无上限（`Executors.newCachedThreadPool()`）——**它不做资源隔离，只为 TimeLimiter 服务**（下一节）。真正的"限并发"由 Bulkhead（第六章）负责。这解释了一个常见困惑：明明没配舱壁，线程 dump 里怎么多出一堆 `pool-N-thread-M`。

`executorService` 字段可由 `configureExecutorService`（L121-123）整体替换——比如换成固定池或虚拟线程池（`Executors.newVirtualThreadPerTaskExecutor()`），后者正是 Boot 4 时代 disableThreadPool 之外的另一种搭配（保留限时但去掉平台线程开销）。

## 4.4 TimeLimiter 的实现代价：为什么限时必须有线程池

白话版：JDK 里"取消一个正在跑的任务"只有一条正路——`Future.cancel(true)` 中断执行线程，而这要求任务跑在**你能控制的线程**上。r4j 的 `TimeLimiter.decorateFutureSupplier(tl, futureSupplier)` 做法是：调度一个超时定时器，到点若 Future 未完成就把它以 `TimeoutException` 完成（并尝试 cancel）。所以路径 A/C 里业务必须先 `executorService.submit(toRun::get)` 变成 `Future`，限时器才有抓手——**线程池是 TimeLimiter 的实现税**。

这笔税的代价清单：

- 一次额外的线程切换与 `Future.get()` 阻塞（调用方线程干等池内线程）；
- ThreadLocal 在业务代码里**失效**（MDC、SecurityContext、事务上下文全丢——MDC 用户常踩）；
- 对虚拟线程不友好之前，平台线程数=并发上限。

于是有了下一节的逃生门。

## 4.5 逃生门：disableThreadPool 与 disableTimeLimiter

【源码证据】`Resilience4JCircuitBreakerFactory.java:200-211`：

```java
if (resilience4JConfigurationProperties.isDisableThreadPool()) {
	return new Resilience4JCircuitBreaker(id, groupName, circuitBreakerConfig, timeLimiterConfig,
			circuitBreakerRegistry, timeLimiterRegistry, Optional.ofNullable(circuitBreakerCustomizers.get(id)),
			bulkheadProvider);                                            // 走无线程池构造器（executorService = null）
}
else {
	boolean isDisableTimeLimiter = ConfigurationPropertiesUtils
		.isDisableTimeLimiter(this.resilience4JConfigurationProperties, id, groupName);
	return new Resilience4JCircuitBreaker(id, groupName, circuitBreakerConfig, timeLimiterConfig,
			circuitBreakerRegistry, timeLimiterRegistry, circuitBreakerExecutorService,
			Optional.ofNullable(circuitBreakerCustomizers.get(id)), bulkheadProvider, isDisableTimeLimiter);
}
```

两级开关（`Resilience4JConfigurationProperties.java:34-40`，前缀 `spring.cloud.circuitbreaker.resilience4j`）：

| 开关 | 默认 | 语义 | 引入版本 |
|---|---|---|---|
| `disableThreadPool` | false | `executorService=null` → 洋葱只剩 CB(+Bulkhead)，**TimeLimiter 一并失效**（没有 Future 可限） | v2.1.4 |
| `disableTimeLimiter` | false | 保留线程池但 `loadTimeLimiter()` 返回空（`Resilience4JCircuitBreaker.java:161-163`），业务仍在池里跑 | v3.0.4 |
| `disableTimeLimiterMap` | `{}` | 按 **id → group → 全局** 三级关闭单个断路器的限时（`ConfigurationPropertiesUtils.isDisableTimeLimiter`，L43-58） | v3.0.4 |

组合矩阵（工程选型速查）：

| 你想要 | 配置 |
|---|---|
| 传统防挂死（Hystrix 风） | 全默认（限时 1s + cachedThreadPool） |
| 虚拟线程时代的极简断路 | `disableThreadPool=true`（超时交给 HTTP client 自身的 readTimeout） |
| 大部分要限时、个别慢操作免限 | 默认 + `disableTimeLimiterMap.{orderSlowCall: true}` |
| 保留限时语义但自管线程 | `configureExecutorService(newVirtualThreadPerTaskExecutor())` 或 `configureGroupExecutorService(...)` |

## 4.6 本章小结

- 工厂 create = 三级配置寻址（registry id → registry group → Java DSL default → r4j 库默认），选线程池（全局 vs 按 group 惰性建），可能包 Observation 装饰。
- run = 洋葱装配，四条路径由 bulkhead × executorService 两个开关决定；fallback 统一接业务失败与熔断拒绝。
- TimeLimiter 是线程池存在的原因（Future.cancel 模型）；两个 disable 开关是把它拆掉/关掉的逃生门。
- 断路器状态不存在这个类里——registry.circuitBreaker(id) 每次现取，天然同 id 共享。

---

# 五、Resilience4J 响应式实现

## 5.1 Reactive 工厂：同一套寻址，不同的运行体

`ReactiveResilience4JCircuitBreakerFactory`（`ReactiveResilience4JCircuitBreakerFactory.java:99-132`）的 create 与阻塞版**逐行同构**：同样的三级寻址、同样的 warn 日志、同样把最终 config 打包传入。差异有三：

1. 产出 `ReactiveCircuitBreaker`（自然）；
2. **没有 executorService/线程池字段**——响应式链上限时用 Reactor 的 `.timeout()` 运算符，不需要 Future；
3. `create(id)` 直接 `create(id, id)`（L82），没有全局池与 group 池的区分。

## 5.2 Reactor 原生装配：Operator + timeout + 手动记账

【源码证据】`ReactiveResilience4JCircuitBreaker.java:88-112`（run(Mono)）：

```java
@Override
public <T> Mono<T> run(Mono<T> toRun, @Nullable Function<Throwable, Mono<T>> fallback) {
	final Map<String, String> tags = Map.of(CIRCUIT_BREAKER_GROUP_TAG, this.groupName);
	Tuple2<CircuitBreaker, Optional<TimeLimiter>> tuple = buildCircuitBreakerAndTimeLimiter();
	Mono<T> toReturn;
	if (bulkheadProvider != null) {
		toReturn = bulkheadProvider.decorateMono(groupName, tags, toRun);      // ① 舱壁
	else {
		toReturn = toRun;
	}
	toReturn = toReturn.transform(CircuitBreakerOperator.of(tuple.getT1()));  // ② 断路器
	if (tuple.getT2().isPresent()) {
		final Duration timeoutDuration = tuple.getT2().get().getTimeLimiterConfig().getTimeoutDuration();
		toReturn = toReturn.timeout(timeoutDuration)                          // ③ 限时
			// Since we are using the Mono timeout we need to tell the circuit
			// breaker about the error
			.doOnError(TimeoutException.class,
					t -> tuple.getT1().onError(timeoutDuration.toMillis(), TimeUnit.MILLISECONDS, t));
	}
	if (fallback != null) {
		toReturn = toReturn.onErrorResume(fallback);                          // ④ 降级
	}
	return toReturn;
}
```

（Flux 版 L115-139 完全对称。）四个装配点的对应关系：

| 阻塞版 | 响应式版 | 说明 |
|---|---|---|
| `CircuitBreaker.decorateCallable` | `transform(CircuitBreakerOperator.of(cb))` | r4j-reactor 提供的 Reactor 运算符，订阅/完成/错误时记账 |
| `executor.submit + TimeLimiter.decorateFutureSupplier` | `.timeout(duration)` | Reactor 原生超时，零线程 |
| — | `.doOnError(TimeoutException.class, cb::onError)` | **手动补账**：`Mono.timeout` 抛的 TimeoutException 发生在 r4j 运算符"看不见"的时机（顺序：operator 已记成功/错误，timeout 之后才触发），所以要手动调 `cb.onError(耗时, 单位, t)` 把这次超时记进滑动窗口——注释 L103-105 特意解释了这步 |
| try-catch + fallback.apply | `.onErrorResume(fallback)` | Reactor 惯用法 |

`buildCircuitBreakerAndTimeLimiter`（L141-155）与阻塞版 `loadTimeLimiter` 同构（id → group → 现场注册），`disableTimeLimiter` 时直接返回空 Optional（L146-149）——**响应式版没有也不需要 disableThreadPool**。

## 5.3 Reactive 舱壁：只有信号量

`ReactiveResilience4jBulkheadProvider`（`ReactiveResilience4jBulkheadProvider.java:81-95`）用 `BulkheadOperator.of(bulkhead)` + `transformDeferred` 装饰 Mono/Flux——只支持**信号量舱壁**（`BulkheadRegistry`）。自动配置里如果你配 `enableSemaphoreDefaultBulkhead=false`，它直接打警告拒绝（`ReactiveResilience4JAutoConfiguration.java:89-93`）：

> *"Ignoring '...enableSemaphoreDefaultBulkhead=false'. ReactiveResilience4jBulkheadProvider only supports SemaphoreBulkhead."*

理由在第六章讲完舱壁模型后自明：响应式链上不该有线程切换。

## 5.4 本章小结

- 响应式版是阻塞版的"翻译"：装饰器 → Reactor 运算符、Future 限时 → `.timeout()`、try-catch → `onErrorResume`。
- 唯一的坑点：timeout 抛错要 `doOnError` 手动喂给断路器，否则超时不计入失败率（熔断永不因超时打开）。
- 没有线程池、没有 disableThreadPool；舱壁只有信号量形态。

---

# 六、BulkheadProvider：双舱壁体系

## 6.1 白话：舱壁是"限并发"，断路器是"限故障"

船舱隔板（bulkhead）让一个舱进水不沉全船。在容错语境里：**断路器**在"下游已经坏"时拒绝调用，**舱壁**在"下游还没坏但忙不过来"时限制同时挂起的请求数——保护的是调用方内存/连接/线程不被打爆。Resilience4J 提供两种形态：

- **信号量舱壁**（`Bulkhead`）：一个 `Semaphore.tryAcquire`，拿不到立刻抛 `BulkheadFullException`。零线程、零切换。
- **线程池舱壁**（`ThreadPoolBulkhead`）：一个专用线程池 + 有界队列，任务提交不进队列时拒绝。**自带隔离性**（该下游的执行全部发生在自己的池里），这就是 Hystrix 模型的直系遗产。

## 6.2 useSemaphoreBulkhead：三种线索决定用哪个

【源码证据】`Resilience4jBulkheadProvider.java:186-206`：

```java
private boolean useSemaphoreBulkhead(String id) {
	// If we find a configuration in the threadPoolBulkheadRegistry, we assume the
	// user configured the bulkhead specifically to use a threadpool ...
	if (threadPoolBulkheadRegistry.find(id).isPresent()) {
		return false;
	}
	// ... use a semaphore bulkhead if enableSemaphoreDefaultBulkhead is true
	// or if we find a configuration in the bulkheadRegistry ...
	return semaphoreDefaultBulkhead || bulkheadRegistry.find(id).isPresent();
}
```

判定优先级（注释写得很清楚）：

1. registry 里有该 id 的**线程池舱壁配置**（yaml `resilience4j.thread-pool-bulkhead.configs.<id>`）→ 用线程池，属性开关说了也不算；
2. 否则：`enableSemaphoreDefaultBulkhead=true`（**默认 false**）或 registry 里有**信号量舱壁配置** → 用信号量；
3. 都没有 → 默认走**线程池**舱壁（再次体现 Hystrix 惯性）。

注意这里按 id 判断、却以 **groupName** 建/取舱壁（`decorateBulkhead` L141/L148 传入的是 group 名）——同组共享一个舱壁，与 4.1 节"组=资源边界"呼应。

## 6.3 装配位置与配置三级

阻塞版里舱壁处在洋葱中间层（4.2 节路径 A/B）。`decorateCallable`（L166-184）两条分支：

- 信号量：`Bulkhead.decorateCallable(bulkhead, callable)`——进出各一次 permit；
- 线程池：`() -> threadPoolBulkhead.decorateCallable(callable).get().toCompletableFuture().get()`（L182）——任务扔进舱壁线程池，**Future.get() 阻塞取回**。这意味着默认全开（路径 A）时业务可能经历"调用方线程 → group executor → bulkhead 池"两次换线程（4.4 节的税又交一遍），虚拟线程时代推荐显式改信号量。

配置体系完全复刻工厂三级（`configure/configureDefault/addBulkheadCustomizer/addThreadPoolBulkheadCustomizer`，L71-106），Builder 是 `Resilience4jBulkheadConfigurationBuilder`（一次构建两种 config：`BulkheadConfig` + `ThreadPoolBulkheadConfig`，`Resilience4jBulkheadConfigurationBuilder.java:26-78`）。`getConfiguration(id)`（L154-164）同样"registry 有则用、无则 default 函数"。

## 6.4 本章小结

- 舱壁限并发、断路器限故障，二者在洋葱上正交叠加（CB 在外、Bulkhead 在内）。
- 默认线程池舱壁（Hystrix 遗产），三种线索可切信号量；响应式只有信号量。
- 舱壁按 group 共享；customizer 允许拿到底层 `Bulkhead`/`ThreadPoolBulkhead` 对象做事件监听等深度定制。

---

# 七、两个 Retry 适配：spring-retry（将逝）与 framework-retry（新生）

## 7.1 白话：用重试库做熔断，语义是什么？

初看矛盾：重试（Retry）是"失败了再来"，熔断（Circuit Breaker）是"失败多了别再试"。Spring Retry 的答案是一个巧妙转换——**"一次完整调用（含其全部重试）算一个样本；样本失败了，断路器记账一次"**。于是：

- 断路器 CLOSED：正常执行（内部可重试 N 次）；
- 断路器 OPEN：连第一次尝试都不给，直接降级。

这个模型没有滑动窗口、没有失败率百分比——**一次完整失败即跳闸**，是"激进单样本熔断"。轻，但粗（更适合"下游要么全好要么全坏"的开关式场景）。

## 7.2 spring-retry 模块：RetryTemplate + DefaultRetryState（已 @Deprecated）

【源码证据】`SpringRetryCircuitBreaker.java:34-65`：

```java
@Deprecated
public class SpringRetryCircuitBreaker implements CircuitBreaker {
	...
	@Override
	public <T> T run(Supplier<T> toRun, Function<@Nullable Throwable, T> fallback) {
		retryTemplate.setBackOffPolicy(config.getBackOffPolicy());
		retryTemplate.setRetryPolicy(config.getRetryPolicy());
		if (retryTemplateCustomizer != null) {
			retryTemplateCustomizer.customize(retryTemplate);
		}
		return retryTemplate.execute(context -> toRun.get(),
				context -> fallback.apply(context.getLastThrowable()),
				new DefaultRetryState(id, config.isForceRefreshState(), config.getStateClassifier()));
	}
}
```

关键在第三个参数 `DefaultRetryState(id, forceRefresh, classifier)`：Spring Retry 的**有状态重试**——同一个 state key（这里=断路器 id）在 `CircuitBreakerRetryPolicy`（Spring Retry 库内的同名策略，本类不在此仓库）视角下共享开/闭状态。`stateClassifier` 决定哪些异常算"熔断级失败"。

工厂（`SpringRetryCircuitBreakerFactory.java:53-57`）逻辑单薄：查配置注册表 → new 断路器。自动配置 `SpringRetryAutoConfiguration.java:39-49` 也只有一个 `@ConditionalOnMissingBean(CircuitBreakerFactory.class)` 的工厂 bean——**谁先到谁得**（8.1 节）。

废弃公告：v5.0.0（commit `b879d73` 2025-11-07 "Add deprecation to Spring Retry classes"）类头 javadoc 直接写明替代者——*"deprecated in favor of the CircuitBreaker implementation in spring-cloud-circuitbreaker-framework-retry"*。

## 7.3 framework-retry 模块（v5.0.0 新增）：FW7 原生 retry + 自研三态机

动机（官方文档 `spring-cloud-circuitbreaker-framework-retry.adoc`）：*"Spring Framework 7 introduced native retry support ... Unlike Spring Framework's retry support which is stateless, this implementation adds stateful circuit breaker functionality ... without additional dependencies."*——FW 7.0 在 spring-core 里内置了 `RetryTemplate/RetryPolicy`（`org.springframework.core.retry`，`RetryTemplate.java` 类头 `@since 7.0`，默认 3 次、固定 1 秒退避），但它是**无状态**的；本模块补上状态，且**不引第三方依赖**（对比：spring-retry 模块要多拖一个 spring-retry jar）。

### 7.3.1 断路器本体：先看闸再重试

【源码证据】`FrameworkRetryCircuitBreaker.java:53-84`：

```java
@Override
public <T> T run(Supplier<T> toRun, Function<@Nullable Throwable, T> fallback) {
	// Check if circuit breaker allows execution (handles open -> half-open transition)
	Assert.notNull(this.circuitBreakerPolicy, "Circuit breaker policy is required");
	if (!this.circuitBreakerPolicy.canRetry()) {
		// Circuit is open and timeout hasn't elapsed
		Throwable lastException = this.circuitBreakerPolicy.getLastException();
		if (lastException == null) {
			lastException = new IllegalStateException("Circuit breaker is open for: " + this.id);
		}
		return fallback.apply(lastException);                    // OPEN：直接降级（带上次异常或合成异常）
	}

	RetryTemplate retryTemplate = new RetryTemplate(this.circuitBreakerPolicy.getRetryPolicy());
	try {
		T result = retryTemplate.execute(toRun::get);           // CLOSED/HALF_OPEN：走 FW7 重试
		this.circuitBreakerPolicy.recordSuccess();              // 成功 → 关闸
		return result;
	}
	catch (Throwable t) {
		// Record failure after all retries are exhausted
		this.circuitBreakerPolicy.recordFailure(t);             // 重试耗尽 → 开闸
		return fallback.apply(t);
	}
}
```

注意 fallback 收到的异常：OPEN 时是 `getLastException()`（历史异常），没有就现场 `IllegalStateException("Circuit breaker is open for: " + id)`——比 r4j 的 `CallNotPermittedException` 更"土"，但信息量足够。

### 7.3.2 自研三态机：CircuitBreakerRetryPolicy（全仓唯一一段熔断算法）

与 r4j 版"状态在库的 registry 里"不同，这里状态就在眼前——一个约 250 行的 CAS 状态机（`CircuitBreakerRetryPolicy.java`）。默认参数：`openTimeout=20s`、`resetTimeout=5s`（构造器 L89-91；Builder 同默认，`FrameworkRetryConfigBuilder.java:36-38`）。

**canRetry()（闸门，L111-141）** 做两段检查：

```java
public boolean canRetry() {
	State currentState = this.state.get();
	long now = System.currentTimeMillis();
	// ① resetTimeout 冷却自愈：距上次失败超过 resetTimeout 且当前 OPEN → CAS 回 CLOSED
	long lastFailure = this.lastFailureTime.get();
	if (lastFailure > 0 && (now - lastFailure >= this.resetTimeout.toMillis())) {
		if (currentState == State.OPEN && this.state.compareAndSet(State.OPEN, State.CLOSED)) {
			this.lastException.set(null);
		}
		currentState = this.state.get();
	}
	// ② openTimeout 到点放探子：OPEN 且过了 openTimeout → CAS 到 HALF_OPEN，放行这一次
	if (currentState == State.OPEN) {
		long opened = this.openedAt.get();
		if (now - opened >= this.openTimeout.toMillis()) {
			if (this.state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
				return true;                                     // 允许一个探测请求
			}
		}
		return false;                                            // 其余 OPEN 一律拒绝
	}
	return true;                                                 // CLOSED/HALF_OPEN 放行
}
```

**recordSuccess / recordFailure（记账，L146-185）**：HALF_OPEN 成功 → CAS 回 CLOSED 并清异常/失败时间；HALF_OPEN 失败 → CAS 回 OPEN 刷新 openedAt；CLOSED 失败 → **立即** CAS 到 OPEN（单样本跳闸）。全部 `AtomicReference/AtomicLong` + CAS，无锁；并发下的边界（如两个线程同时尝试 CAS OPEN→HALF_OPEN）只有一个成功、另一个 `return false` 拒绝——天然实现"半开只放一个"。

这套实现与 Spring Retry 库里的老 `CircuitBreakerRetryPolicy` 行为对齐（javadoc L28-36 与 FrameworkRetryCircuitBreaker.java:79-80 的注释都强调了 *"a failure is one complete failed invocation (all retries exhausted)"*）——**先重试、后熔断，重试整体是一个样本**。

### 7.3.3 配置与装配

`FrameworkRetryConfigBuilder`（L30-92）三项可调：`retryPolicy`（FW7 的 `RetryPolicy.withMaxRetries/withMaxDuration/withBackoff/forExceptions`，支持 `and()/or()` 组合）、`openTimeout`、`resetTimeout`。工厂/自动配置与 spring-retry 版一样是"注册表查配置 + ConditionalOnMissingBean 兜底"的最小实现（`FrameworkRetryAutoConfiguration.java:40-50`）。

## 7.4 三实现能力对照（选型速查）

| 能力 | Resilience4J | spring-retry（废弃中） | framework-retry |
|---|---|---|---|
| 熔断判据 | 滑动窗口失败率/慢调用 | 单样本完整失败 | 单样本完整失败 |
| 内部重试 | 无（另有 r4j-retry，不经本抽象） | 有（RetryPolicy + BackOff） | 有（FW7 RetryPolicy） |
| 舱壁/限时 | Bulkhead + TimeLimiter | 无 | 无 |
| 响应式 | ✅ | ❌ | ❌ |
| 指标 | TaggedCircuitBreakerMetrics + Observation | 无内置 | 无内置 |
| 额外依赖 | resilience4j-* | spring-retry | 无（纯 spring-core 7） |
| OPEN 判据细节 | 可配阈值/窗口/minCalls | classifier 分类 | 异常即跳闸 |

## 7.5 本章小结

- "重试库做熔断"的语义转换：一次完整调用（重试耗尽）= 一个熔断样本；单样本跳闸，激进但轻。
- spring-retry 模块 v5.0.0 起 @Deprecated，接替者 framework-retry 用 FW7 原生 retry + 250 行自研 CAS 三态机，零第三方依赖。
- 三态机的两个 CAS 检查（resetTimeout 自愈、openTimeout 放探子）+ 三处 CAS 记账，是本仓库唯一一段"看得见"的熔断算法——读懂它，就读懂了 2.1 节那张状态机图的代码形态。

---

# 八、自动装配与可观测性

## 8.1 注册清单：三个 AutoConfiguration

【源码证据】`spring-cloud-circuitbreaker-resilience4j/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`：

```
org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JAutoConfiguration
org.springframework.cloud.circuitbreaker.resilience4j.Resilence4JMetricsOrderingAutoConfiguration
org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JAutoConfiguration
```

（注意 `Resilence4JMetricsOrderingAutoConfiguration` 类名拼写就是错的——仓库史实，别当成文档笔误。）spring-retry/framework-retry 模块各注册一个。**互斥靠 `@ConditionalOnMissingBean(CircuitBreakerFactory.class)`**：同时引入两个实现 starter 时，Boot 按自动配置排序决定谁先生效、另一个静默退出——生产上应显式排除不用的 starter，别赌顺序。

## 8.2 Resilience4JAutoConfiguration 的五个内部配置类

【源码证据】`Resilience4JAutoConfiguration.java:57-181`：

| 内部类 | 条件 | 产出 |
|---|---|---|
| （顶层） | `spring.cloud.circuitbreaker.resilience4j(.blocking).enabled`（默认 true） | `Resilience4JCircuitBreakerFactory`（注入 r4j 集成提供的两个 registry bean + 可选 bulkheadProvider），随后应用所有 `Customizer<Resilience4JCircuitBreakerFactory>` bean（L73-76） |
| `Resilience4jBulkheadConfiguration` | classpath 有 `Bulkhead` 类 + `...bulkhead.resilience4j.enabled`（默认 true） | `Resilience4jBulkheadProvider`（同样吃 Customizer 列表） |
| `MicrometerResilience4JGroupCustomizerConfiguration` | 有 `MeterRegistry` bean | **group tag 补默认值的 MeterFilter**（L108-120）：resilience4j 前缀且无 `group` tag 的指标补 `group=none`（`defaultGroupTag` 属性可改）。纯查询面板友好：按 group 聚合时无 tag 的序列会丢失，这是防丢失补丁 |
| `MicrometerResilience4JCustomizerConfiguration` | 有 MeterRegistry + r4j-micrometer 在 classpath 且用户没自备 `TaggedCircuitBreakerMetricsPublisher` | `@PostConstruct` 里把 `TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry)` bind 到 MeterRegistry（L143-158）——resilience4j.circuitbreaker.calls 等指标来源；bulkhead 的两类 registry 也一并绑 |
| `ObservationRegistryCustomizerResilience4jCustomizer` | 有 `ObservationRegistry` + `...micrometer.enabled`（默认 true，**属性名有歧义：管的是 Observation 不是 metrics**） | `factory.setObservationRegistry(...)`（L173-178）→ 3.4 节装饰自动生效 |

`Resilence4JMetricsOrderingAutoConfiguration`（`Resilence4JMetricsOrderingAutoConfiguration.java:32-36`）是个空壳，只做排序：`afterName = Boot 的 CompositeMeterRegistryAutoConfiguration`、`beforeName = r4j 的 TimeLimiterMetricsAutoConfiguration`——保证 r4j 拉指标时 MeterRegistry bean 已就位（javadoc L25-28 解释得很直白）。这个"空类调顺序"的手法与 Gateway 里如出一辙（[Spring Cloud Gateway.md](Spring Cloud Gateway.md) 8 章）。

响应式侧 `ReactiveResilience4JAutoConfiguration`（`ReactiveResilience4JAutoConfiguration.java:52-139`）结构对称：`@ConditionalOnClass(Flux/Mono/CircuitBreakerOperator)` 把 r4j-reactor 设为硬前提；内部类的信号量警告（5.3 节）与指标绑定同款。

## 8.3 两套可观测的关系图

```
Micrometer 指标（聚合视图）                Observation（单次调用遥测）
─────────────────────────                ─────────────────────────
TaggedCircuitBreakerMetrics              ObservedCircuitBreaker
  .ofCircuitBreakerRegistry(...)           （包住 run 的 Supplier/Fallback）
  → resilience4j.circuitbreaker.calls        → span: spring.cloud.circuitbreaker
    resilience4j.circuitbreaker.state        → tag: type={supplier|function}
    resilience4j.timelimiter.calls
  带 tag: name=<id>, group=<组>            （低基数：不放 id）
MeterFilter 补 group=none
```

一套回答"这个断路器今天失败率多少"（面板/告警），一套回答"这次调用走了业务还是 fallback、各花多久"（trace）。8.2 表中最后一个内部类负责接线后者。

## 8.4 配置大全（`spring.cloud.circuitbreaker.*` 前缀，metadata 与源码双证）

| 属性 | 默认 | 作用 | 章节 |
|---|---|---|---|
| `circuitbreaker.resilience4j.enabled` | true | 总开关（与 blocking.enabled 并列） | 8.2 |
| `circuitbreaker.resilience4j.blocking.enabled` | true | 阻塞自动配置开关 | 8.2 |
| `circuitbreaker.resilience4j.reactive.enabled` | true | 响应式自动配置开关 | 8.2 |
| `circuitbreaker.resilience4j.micrometer.enabled` | true | Observation 接线开关 | 8.2 |
| `circuitbreaker.resilience4j.enableGroupMeterFilter` | true | group=none 补丁 MeterFilter 开关 | 8.2 |
| `circuitbreaker.resilience4j.defaultGroupTag` | "none" | 补丁用的默认组名 | 8.2 |
| `circuitbreaker.resilience4j.disableThreadPool` | false | 去掉线程池（TimeLimiter 随之失效） | 4.5 |
| `circuitbreaker.resilience4j.disableTimeLimiter` | false | 关限时（保留线程池） | 4.5 |
| `circuitbreaker.resilience4j.disableTimeLimiterMap` | {} | 按 id/group 关限时 | 4.5 |
| `circuitbreaker.resilience4j.enableSemaphoreDefaultBulkhead` | false | 默认舱壁用信号量 | 6.2 |
| `circuitbreaker.bulkhead.resilience4j.enabled` | true | 舱壁自动配置开关 | 8.2 |

另有两大类**不在本前缀**的配置：① r4j 自身的 `resilience4j.circuitbreaker.configs.<id>/configs.<group>/configs.default`（`resilience4j-spring-boot4` 集成装载，进入 4.1 节的 registry 三级）；② Java DSL（`Customizer` bean，`configure/configureDefault`）。三处配置的优先级关系见 4.1 节——**yaml(r4j) 高于 Java DSL**，这点常被反直觉地用错。

## 8.5 本章小结

- 三个 r4j 自动配置 + 两个 retry 自动配置，靠 `ConditionalOnMissingBean(CircuitBreakerFactory)` 互斥。
- 指标自动绑定（Tagged*Metrics）+ group tag 补丁 + Observation 自动装饰，都只需 classpath 有相应依赖。
- 一个空自动配置类专职排序（MeterRegistry 先于 r4j 指标装配）。

---

# 九、消费方：谁在用这套抽象

## 9.1 Spring Cloud Gateway 的 CircuitBreaker 过滤器

Gateway 是这套抽象最重量级的消费者（[Spring Cloud Gateway.md](Spring Cloud Gateway.md) 5.5 节讲了网关侧全貌，这里补抽象侧视角）：

【源码证据】`spring-cloud-gateway/spring-cloud-gateway-server-webflux/.../SpringCloudCircuitBreakerFilterFactory.java:92-158`（apply 主体节选）：

```java
ReactiveCircuitBreaker cb = reactiveCircuitBreakerFactory.create(config.getId());
...
return new GatewayFilter() {
	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		return cb.run(chain.filter(exchange).doOnSuccess(v -> {
				if (statuses.contains(exchange.getResponse().getStatusCode())) {
					throw new CircuitBreakerStatusCodeException(status);   // 状态码也算失败 → 计入熔断
				}
			}), t -> {
				if (config.getFallbackUri() == null) { return Mono.error(t); }
				... 改写 GATEWAY_REQUEST_URL_ATTR → reset(exchange) → 交回 DispatcherHandler 内部转发 ...
			}).onErrorResume(t -> handleErrorWithoutFallback(t, config.isResumeWithoutError()));
	}
};
```

三个抽象侧看点：

1. **下游"业务成功但状态码坏"也能记失败**——`doOnSuccess` 里抛 `CircuitBreakerStatusCodeException` 把 5xx/4xx（可配 statusCodes）转成错误信号喂给断路器。这是"熔断判据由消费方定义"的范例；
2. fallback 是**网关内部 forward 转发**（fallbackUri），不是二次 HTTP；
3. 抽象的 `run(Mono, Function)` 在这里就是"包住过滤器链的剩余部分"——熔断粒度=过滤器链片段，展示出门面 API 的任意粒度能力。

r4j 特化子类 `SpringCloudCircuitBreakerResilience4JFilterFactory`（同目录，L41-52）只覆写异常翻译：`TimeoutException → 504 GATEWAY_TIMEOUT`、`CallNotPermittedException → 503 ServiceUnavailableException`——**把底层库异常翻译成 HTTP 语义**，这正是"特化实现"该干的事。装配入口 `GatewayResilience4JCircuitBreakerAutoConfiguration`（`@ConditionalOnClass(...ReactiveResilience4JCircuitBreakerFactory)` + `@ConditionalOnBean`）。

## 9.2 声明式 HTTP 接口（Boot 4 / commons 5.0+）

3.5 节的 httpservice 包落到位后，使用姿势是三行配置 + 一个注解：

```java
@HttpExchangeGroup("order-service")            // Boot 的分组注解（示意）
interface OrderApi { @HttpExchange("/api/order") Order get(@RequestParam String id); }

@Configuration
@HttpServiceFallback(value = OrderApiFallback.class, group = "order-service")
class FallbackConfig { }
```

组内所有方法的 HTTP 调用即被 id=`order-service` 的断路器包住，失败按 3.5 节规则反射匹配 fallback 方法（同名同参 / Throwable 打头）。

## 9.3 第三方实现：SCA 的 spring-cloud-circuitbreaker-sentinel

门面的价值由第三方验证：spring-cloud-alibaba 仓库的 `spring-cloud-alibaba-starters/spring-cloud-circuitbreaker-sentinel` 模块实现了同一套抽象（本地 `D:\code\3rd\spring-cloud-alibaba` 实测文件清单）：`SentinelCircuitBreaker(Factory)`、`ReactiveSentinelCircuitBreaker(Factory)`、`SentinelConfigBuilder`，外加 Feign 集成的 `FeignClientCircuitNameResolver`。业务代码从 r4j 换到 Sentinel（换 starter）后 `factory.create().run()` 一字不改——换掉的只是熔断语义（滑动窗口多判据、Dashboard 可视化，见[Spring Cloud Alibaba Sentinel.md](Spring Cloud Alibaba Sentinel.md) 6 章）。

## 9.4 手工使用（最小例子）

```java
@Service
class OrderService {
	private final CircuitBreakerFactory<?, ?> cbFactory;     // 注入的是抽象工厂

	Order getOrder(String id) {
		return cbFactory.create("order").run(
				() -> restTemplate.getForObject("http://order/api/" + id, Order.class),
				t -> Order.EMPTY);                            // 熔断打开/调用失败时的兜底
	}
}
```

默认配置/指定配置经 Customizer bean 下发：

```java
@Bean
Customizer<Resilience4JCircuitBreakerFactory> slow() {
	return f -> f.configure(b -> b.circuitBreakerConfig(CircuitBreakerConfig.custom()
					.slidingWindowSize(50).build())
			.timeLimiterConfig(TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(3)).build()),
		"slow");
}
```

## 9.5 本章小结

- 消费方三姿势：Gateway（过滤器链级）、声明式 HTTP 组（接口级）、手工 `create().run()`（方法级）——同一门面三种粒度。
- 特化点都收在"异常翻译"和"失败判据"上，洋葱装配本身不可变。
- SCA 的 Sentinel 实现证明门面达成设计目标：实现可替换。

---

# 十、贯通视图：把全文串成三条时间线

## 10.1 一次 `run()` 的完整生命线（默认配置，阻塞版）

```
调用方线程                                group 线程池
──────────                                ────────────
factory.create("order","orderGroup")
  ├─ 配置三级寻址（registry.id → registry.group → DSL default）
  ├─ executorServices.computeIfAbsent("orderGroup") ← 惰性建池
  └─ tryObservedCircuitBreaker(...)（有 ObservationRegistry 则包壳）

cb.run(supplier, fallback)
  ├─ loadTimeLimiter：registry.id → registry.group → 现场注册（首次触发 warn 日志）
  ├─ registry.circuitBreaker("order", config, tags=group) ← 同 id 共享状态
  ├─ CB 装饰（外层）
  │    └─ acquirePermission？OPEN 时抛 CallNotPermittedException ──┐
  ├─ Bulkhead 装饰（中层）                                         │
  │    └─ tryAcquire？满则抛 BulkheadFullException ────────────────┤
  ├─ TimeLimiter 装饰（内层）                    ┌── 1s 到点 ──────┤
  │    └─ executorService.submit(toRun) ────────┼→ 业务执行        │
  │         调用方线程 Future.get() 等待 ←───────┘  （ThreadLocal 在这里丢失）
  ├─ 成功 → 返回结果
  └─ 任何 Throwable（含 ──┘ 的三类）→ fallback.apply(t) → 返回降级值
```

## 10.2 一次启动装配的生命线

```
Boot 启动
  ├─ resilience4j-spring-boot4 装配 CircuitBreakerRegistry/TimeLimiterRegistry（yaml 进 registry）
  ├─ Resilience4JAutoConfiguration
  │    ├─ 工厂 bean（@ConditionalOnMissingBean(CircuitBreakerFactory)）
  │    │    └─ 逐个应用 Customizer<Factory> bean（configure/configureDefault 下发 DSL 配置）
  │    ├─ Resilience4jBulkheadConfiguration → BulkheadProvider（classpath 有 Bulkhead）
  │    ├─ MicrometerResilience4JGroupCustomizerConfiguration → group=none MeterFilter
  │    ├─ MicrometerResilience4JCustomizerConfiguration → Tagged*Metrics.bindTo
  │    └─ ObservationRegistryCustomizer... → factory.setObservationRegistry
  │        → 此后 create() 产物自动是 ObservedCircuitBreaker
  └─ （消费方侧）Gateway 的 GatewayResilience4JCircuitBreakerAutoConfiguration
       → SpringCloudCircuitBreakerResilience4JFilterFactory 就位
首次请求 → 10.1
```

## 10.3 一条故障生命线（熔断的完整回合）

```
t0   下游开始变慢；每次调用 1s 超时 → TimeLimiter 抛 TimeoutException
     → CB 滑动窗口记失败（r4j 内部，非本仓库代码）
t1   窗口内失败率过 50%（r4j 默认）→ r4j 状态机 CLOSED→OPEN
t2   后续调用：CB.acquirePermission 直接抛 CallNotPermittedException
     → run 的 try-catch → fallback.apply（Gateway 翻译成 503）
t3   openTimeout（默认 60s，r4j 默认）到 → OPEN→HALF_OPEN（r4j 放行 N 个探测）
t4a  探测成功 → HALF_OPEN→CLOSED，流量恢复
t4b  探测失败 → HALF_OPEN→OPEN，回到 t2
（对照 framework-retry 版：t1 变为"单次完整失败即 OPEN"，t3 为 20s，t2 的异常是
 IllegalStateException("Circuit breaker is open for: order")）
```

## 10.4 与系列其他文档的连接点

- **[Spring Framework.md](Spring Framework.md)**：4.3 节函数式接口（Supplier/Function）是整个 API 的语言基础；framework-retry 模块直接消费 FW 7 的 `core.retry`（7.3 节）。
- **[Spring Cloud Gateway.md](Spring Cloud Gateway.md)** 5.5 节：网关侧的 CircuitBreaker 过滤器全貌（本文 9.1 是其抽象侧内幕）；fallback 内部转发依赖的 DispatcherHandler/`forward:` 通道在该文档第五章。
- **[Spring Cloud Alibaba Sentinel.md](Spring Cloud Alibaba Sentinel.md)**：第六章断路器（滑动窗口判据）、第十三章 SCA 集成——Sentinel 版熔断语义与 r4j 的差异；SCA 同样提供了本抽象的 sentinel 实现（9.3 节）。
- **[Spring Cloud LoadBalancer.md](Spring Cloud LoadBalancer.md)**：同属 commons 家族、同用 Customizer 约定与 NamedContextFactory 式的"按名隔离"思路（本文是 group executor，LB 是每服务子容器）。
- **[Spring观测体系设计.md](Spring观测体系设计.md)**：Observation 模型与低基数标签设计（3.4 节）。
- **[Spring Cloud vs k8s.md](Spring Cloud vs k8s.md)** 2.4 节：应用层熔断（本项目）与平台层熔断（Istio OutlierDetection）的分层对比。

## 10.5 版本生命线总结（一图收束 1.6 节）

```
2019.02  项目立项（当时叫 resilience4j 相关名，f58ba26 重命名为 Resilience4J）
2019.04  Initial commit（r4j + spring-retry 两实现）
2019.08  hystrix 实现迁往 spring-cloud-netflix；reactor starter 拆分
2019.11  v1.0.0.RELEASE（Hoxton）
2021.02  Bulkhead（v2.0.1 首发；4 月回灌 1.0.5 的 bulkhead group）
2022.02  group 三级配置（v3.0.0 首发）
2022.05  disableThreadPool（v2.1.4，维护线首发）
2022.12  v3.0.0（jakarta；commons 4.0.0 带 ObservedCircuitBreaker）
2023.11  disableTimeLimiter / groupExecutorServiceFactory（v3.0.4）
2025.11  v5.0.0：跳过 4.x 对齐 build 5.0/Boot 4.0；framework-retry 模块落地；
         spring-retry 模块 @Deprecated；JSpecify 化；commons 5.0.0 带 httpservice
2026.09  v5.1.0-M1（本文基线 SNAPSHOT = M1+7）
```

**最后一页记忆**：如果你只能记住三件事——① API 在 commons，两个 run 接口 + 工厂注册表，本仓库只是填空题答案集；② 阻塞实现 = 一颗"CB→Bulkhead→TimeLimiter→线程池→业务"的洋葱，四条裁剪路径由两个开关决定，而线程池存在的唯一理由是 TimeLimiter 的 Future.cancel 模型；③ 所有配置三级寻址（registry id → registry group → DSL default），所有实现互斥于 `@ConditionalOnMissingBean(CircuitBreakerFactory)`。

---

# 十一、附录

## 11.1 关键接口速查表

| 接口/类 | 模块 | 一句话职责 | 深入章节 |
|---|---|---|---|
| `CircuitBreaker` | commons | 阻塞门面：`run(supplier, fallback)` 两个重载 | 3.1 |
| `ReactiveCircuitBreaker` | commons | 响应式门面：Mono/Flux 各一对 run | 3.2 |
| `CircuitBreakerFactory` / `ReactiveCircuitBreakerFactory` | commons | 工厂 SPI：`create(id)` / `create(id, groupName)` | 3.3 |
| `AbstractCircuitBreakerFactory` | commons | 工厂骨架：配置注册表 + `run` 缺省实现 | 3.3 |
| `ConfigBuilder<B>` | commons | 配置原语：让"配置长什么样"由实现定 | 3.3 |
| `Customizer<T>` | commons | 一函数式注入约定（全 Spring Cloud 通用） | 3.3 / 8.2 |
| `NoFallbackAvailableException` | commons | 无 fallback 可用时的统一报错语义 | 3.1 |
| `ObservedCircuitBreaker` | commons（observation 包） | Observation 埋点装饰壳（4.0.0 引入） | 3.4 |
| `ObservedSupplier` / `ObservedFunction` | commons（observation 包） | 埋点的最小执行单元 | 3.4 |
| `CircuitBreakerObservationDocumentation` | commons（observation 包） | 指标名与低/高基数键定义 | 3.4 |
| `CircuitBreakerAdapterDecorator` / `ReactiveCircuitBreakerAdapterDecorator` | commons（httpservice 包） | `@HttpExchange` 组的阻塞/响应式装饰 | 3.5 / 9.2 |
| `HttpServiceFallback` + `CircuitBreakerConfigurerUtils` | commons（httpservice 包） | fallback 反射匹配规则 + 组装配 | 3.5 |
| `Resilience4JCircuitBreaker` | resilience4j | 全文核心：洋葱装配 + 四条裁剪路径 | 4.2-4.5 |
| `Resilience4JCircuitBreakerFactory` | resilience4j | create 三级寻址 + group 线程池惰性建池 | 4.1 / 4.3 |
| `Resilience4JConfigBuilder` | resilience4j | DSL 默认配置（CB+TL 组合） | 4.1 |
| `Resilience4JConfigurationProperties` | resilience4j | `spring.cloud.circuitbreaker.resilience4j.*` 载体 | 8.4 |
| `ConfigurationPropertiesUtils` | resilience4j | yaml→r4j Config 的属性搬运 | 4.1 / 8.4 |
| `ReactiveResilience4JCircuitBreaker(Factory)` | resilience4j | Reactor 原生装配：Operator + timeout + 手动记账 | 5.1-5.3 |
| `Resilience4jBulkheadProvider` | resilience4j | 双舱壁体系：信号量/固定线程池按线索选择 | 6.2-6.3 |
| `Resilience4jBulkheadConfigurationBuilder` | resilience4j | 舱壁 DSL 配置 | 6.3 |
| `Resilience4JAutoConfiguration` | resilience4j | 五个内部配置类的装配总入口 | 8.1-8.2 |
| `ReactiveResilience4JAutoConfiguration` | resilience4j | 响应式工厂装配 | 8.1 |
| `Resilence4JMetricsOrderingAutoConfiguration` | resilience4j | 空类专职排序（MeterRegistry 先行） | 8.2 |
| `SpringRetryCircuitBreaker(Factory)` | spring-retry | RetryTemplate + DefaultRetryState 适配（@Deprecated） | 7.2 |
| `SpringRetryConfig(Builder)` / `SpringRetryAutoConfiguration` | spring-retry | 配置与装配 | 7.2 |
| `FrameworkRetryCircuitBreaker(Factory)` | framework-retry | FW7 原生 retry 上的"先看闸再重试" | 7.3.1 |
| `FrameworkRetryConfig(Builder)` / `FrameworkRetryAutoConfiguration` | framework-retry | 配置与装配 | 7.3.3 |
| `CircuitBreakerRetryPolicy` | framework-retry | 自研三态机（全仓唯一一段熔断算法） | 7.3.2 |

## 11.2 初学者学习路线（动手向）

1. **跑通最小例子**（0.5 天）：Boot 4 项目引入 `spring-cloud-starter-circuitbreaker-resilience4j`，照 9.4 节写 `cbFactory.create("order").run(...)`；故意让下游抛异常，观察 fallback 生效；去掉 fallback 再跑，认识 `NoFallbackAvailableException`。
2. **亲眼看见熔断打开**（0.5 天）：用 Customizer 把 `failureRateThreshold` 调到 10%、`slidingWindowSize` 调小（照 9.4 节写法）；连打几次失败请求后，观察后续调用**不再到达下游**（日志里是 `CallNotPermittedException` 被兜住），等 `waitDurationInOpenState` 过后再看 HALF_OPEN 探测。
3. **体验 TimeLimiter 与线程池**（0.5 天）：下游 `Thread.sleep(2000)`，默认 1s 超时必现 `TimeoutException`；再设 `disableThreadPool=true` 对比（4.5 节）——体会"限时必须有可控线程"的实现税。
4. **配置三级寻址实验**（0.5 天）：分别用 yaml（`resilience4j.circuitbreaker.configs.order...`）、`configure(...,"order")`、`configureDefault(...)` 设不同 `timeoutDuration`，验证 4.1 节"yaml 高于 DSL、id 高于 group 高于 default"的优先级。
5. **压测舱壁**（1 天）：写一个慢接口，并发 20 打满默认 10 容量的固定线程池舱壁，观察 `BulkheadFullException`；再设 `enableSemaphoreDefaultBulkhead=true` 对比行为（6.2-6.3 节）。
6. **打开可观测**（0.5 天）：加 micrometer，`/actuator/metrics` 看 `resilience4j.circuitbreakers`（Tagged*Metrics 自动绑定）与 Observation 指标（8.3 节）；注意 group tag 的 `none` 补丁。
7. **接上网关**（1 天）：Gateway 加 `CircuitBreaker` 过滤器 + `fallbackUri: forward:/fallback`（9.1 节）；配 `statusCodes` 让"业务成功但 5xx"也计入熔断；观察 `TimeoutException → 504`、`CallNotPermittedException → 503` 的翻译。
8. **读源码**（2 天）：按 11.3 清单顺序，对照本文行号读；重点在 `Resilience4JCircuitBreaker` 的四条路径与 `CircuitBreakerRetryPolicy` 的三态机。
9. **进阶**（选修）：实现一个自定义 `CircuitBreakerFactory`（如只打日志不做熔断的 `NoOpCircuitBreakerFactory`），体会门面契约有多小；或引入 SCA 的 sentinel 实现（9.3 节），对比"换 starter 不换业务代码"。

## 11.3 源码阅读入口清单（30 个关键文件）

路径前缀：`C` = `spring-cloud-commons/src/main/java/org/springframework/cloud/client/circuitbreaker/`，`R` = `spring-cloud-circuitbreaker-resilience4j/src/main/java/org/springframework/cloud/circuitbreaker/resilience4j/`，`S` = `spring-cloud-circuitbreaker-spring-retry/src/main/java/org/springframework/cloud/circuitbreaker/springretry/`，`F` = `spring-cloud-circuitbreaker-framework-retry/src/main/java/org/springframework/cloud/circuitbreaker/retry/`，`G` = gateway 仓库 `spring-cloud-gateway-server-webflux/.../filter/factory/`。

| # | 文件 | 看什么 |
|---|---|---|
| 1 | `C`/`CircuitBreaker.java` | 全部故事的起点：两个 run 重载 |
| 2 | `C`/`ReactiveCircuitBreaker.java` | Mono/Flux 版门面 |
| 3 | `C`/`CircuitBreakerFactory.java` | 工厂 SPI 与泛型签名 |
| 4 | `C`/`AbstractCircuitBreakerFactory.java` | 配置注册表 + registries 双容器 |
| 5 | `C`/`ConfigBuilder.java`、`Customizer.java` | 两个函数式接口撑起配置体系 |
| 6 | `C`/`NoFallbackAvailableException.java` | 无兜底时的错误语义 |
| 7 | `C`/`observation/ObservedCircuitBreaker.java` | 观测装饰壳（4.0.0+） |
| 8 | `C`/`observation/ObservedSupplier.java`、`ObservedFunction.java` | 埋点最小单元与 fallback 记账 |
| 9 | `C`/`observation/CircuitBreakerObservationDocumentation.java` | 指标名与基数设计 |
| 10 | `C`/`httpservice/CircuitBreakerAdapterDecorator.java` | 声明式 HTTP 阻塞装饰（5.0.0+） |
| 11 | `C`/`httpservice/ReactiveCircuitBreakerAdapterDecorator.java` | 响应式版装饰 |
| 12 | `C`/`httpservice/CircuitBreakerConfigurerUtils.java` | 组装配与 fallback 匹配规则 |
| 13 | `R`/`Resilience4JCircuitBreakerFactory.java` | create 三级寻址 + group 线程池 |
| 14 | `R`/`Resilience4JCircuitBreaker.java` | **全文核心**：洋葱装配与四条路径 |
| 15 | `R`/`Resilience4JConfigBuilder.java` | DSL 默认配置 |
| 16 | `R`/`ConfigurationPropertiesUtils.java` | yaml→r4j 搬运（含大小写陷阱） |
| 17 | `R`/`Resilience4JConfigurationProperties.java` | 本仓库全部自有开关 |
| 18 | `R`/`ReactiveResilience4JCircuitBreakerFactory.java` | Reactive 寻址与手动记账 |
| 19 | `R`/`ReactiveResilience4JCircuitBreaker.java` | Operator + timeout 装配 |
| 20 | `R`/`Resilience4jBulkheadProvider.java` | 双舱壁线索判定 |
| 21 | `R`/`Resilience4jBulkheadConfigurationBuilder.java` | 舱壁配置 DSL |
| 22 | `R`/`Resilience4JAutoConfiguration.java` | 五个内部配置类一网打尽 |
| 23 | `R`/`ReactiveResilience4JAutoConfiguration.java` | 响应式装配 |
| 24 | `R`/`Resilence4JMetricsOrderingAutoConfiguration.java` | 只为排序而存在的空类 |
| 25 | `S`/`SpringRetryCircuitBreaker.java` | RetryState 即熔断状态（将逝） |
| 26 | `S`/`SpringRetryCircuitBreakerFactory.java` | 注册表如何变成 RetryTemplate |
| 27 | `F`/`FrameworkRetryCircuitBreaker.java` | 先看闸再重试的执行序 |
| 28 | `F`/`CircuitBreakerRetryPolicy.java` | **全仓唯一熔断算法**：三态机 |
| 29 | `F`/`FrameworkRetryCircuitBreakerFactory.java` | 新生实现的工厂 |
| 30 | `G`/`SpringCloudCircuitBreakerFilterFactory.java` | 最重量级消费方（网关侧） |

## 结语

Spring Cloud CircuitBreaker 是本系列目前为止"最小"的一个仓库——主源码不到 30 个文件，真正的熔断算法一行都没有自己实现（都在 Resilience4j、spring-retry、Framework 7 的 `core.retry` 里）。但它恰恰用这个"小"证明了一件事：**好的抽象可以薄到只剩两个方法**。`run(Supplier, Function)` 加上一个按名字取实例的工厂，就足以让网关的过滤器链、声明式 HTTP 接口、手工业务调用三种粒度共享同一套熔断语义，也让 Sentinel 这样的第三方实现即插即换。

如果说 LoadBalancer 文档的主题是"装饰器链"，这一篇的主题就是"洋葱与填空题"：实现方往 `run` 里填什么装饰层、用什么底层库填，业务代码永远不知道也不必知道。你以后再遇到"超时了但线程还在跑"、"熔断了但指标里没有失败"、"换了个实现 503 变 500"，应该能立刻回到本文的对应章节——4.3 节的换线程、3.4 节的 Observation 埋点、9.1 节的异常翻译——把"配置黑盒"重新拆回"读得懂的机器"。这也是整个系列一以贯之的读法。
