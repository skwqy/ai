# JDK 中的 Flow（响应式流）深度剖析 —— 及与 CompletableFuture 的全面对比

> **分析依据**：本机 JDK 27 源码（`D:\soft\jdk\jdk-27\lib\src.zip`）实际走读：`java.base/java/util/concurrent/Flow.java`（320 行，4 个嵌套接口 + 1 个常量）与 `java.base/java/util/concurrent/SubmissionPublisher.java`（1503 行，JDK 内置的唯一 `Publisher` 通用实现），另走读 `java.net.http` 模块对 Flow 的真实使用（请求侧 `HttpRequest.BodyPublisher extends Flow.Publisher`，响应侧 `HttpResponse.BodySubscriber extends Flow.Subscriber`）。文中标注 `文件:行号`，均指 JDK 27。
>
> 系列同篇：[《CompletableFuture 与 Netty Future/Promise 深度剖析》](../netty/CompletableFuture&Future-Promise.md)。三者的分工一句话说清：**CompletableFuture 解决"一件事做完后做什么"（单个结果的通知与编排），Flow 解决"一条河如何不冲垮两岸"（连续数据在速率不匹配的组件之间受控传输），Netty 解决"网络操作落定时如何在确定的线程上得到通知"**。前两者同出 Doug Lea / JSR-166 之手，JDK 11 的 `java.net.http` 客户端正是"CF 管编排 + Flow 管传输"两者互补的标准答案（见 2.8）。

---

## 目录

- [引言：Flow 解决的问题域](#引言flow-解决的问题域)
- [一、JDK 中的 Flow](#一jdk-中的-flow)
  - [1.1 设计定位与演进脉络](#11-设计定位与演进脉络)
  - [1.2 实现原理（SubmissionPublisher 源码级走读）](#12-实现原理submissionpublisher-源码级走读)
  - [1.3 接口全景（分类）](#13-接口全景分类)
  - [1.4 使用场景与示例（详细版）](#14-使用场景与示例详细版)
  - [1.5 陷阱清单](#15-陷阱清单)
- [二、Flow 与 CompletableFuture 对比](#二flow-与-completablefuture-对比)
  - [2.1 问题域一图看懂](#21-问题域一图看懂)
  - [2.2 全维度对比表](#22-全维度对比表)
  - [2.3 数据模型对比：一次性终态 vs 流终止信号](#23-数据模型对比一次性终态-vs-流终止信号)
  - [2.4 背压：Flow 的立身之本，CF 的盲区](#24-背压flow-的立身之本cf-的盲区)
  - [2.5 组合能力对比：50 个算子 vs 0 个算子](#25-组合能力对比50-个算子-vs-0-个算子)
  - [2.6 线程模型对比](#26-线程模型对比)
  - [2.7 异常与取消模型对比](#27-异常与取消模型对比)
  - [2.8 互操作桥接与 JDK 内部的"标准答案"](#28-互操作桥接与-jdk-内部的标准答案)
  - [2.9 选型建议](#29-选型建议)

---

## 引言：Flow 解决的问题域

在 Flow 出现之前，JDK 的异步工具都在回答同一个问题：**"这个异步操作何时完成、结果是什么、完成后做什么"**——`Future`、`CompletableFuture`、`ForkJoinTask` 全是"**结果**"的语言，它们天生面对的是 **0 或 1 个结果**。

但还有一大类场景它们覆盖不了：**生产者以不可控的速率持续产出数据，消费者以自己的节奏消化**——传感器遥测、行情推送、日志采集、大文件下载、数据库游标、消息队列消费。这类场景的核心矛盾不是"何时完成"，而是：

1. **数据是无限的**（或提前不可知总量），"等全部完成"这个语义不成立；
2. **速率失配**：生产快、消费慢时，无界缓冲 = OOM，无界丢弃 = 数据丢失；
3. **多对多的速率解耦**：一个数据源、N 个消化速度各异的下游，各自的减速不应互相牵连。

Flow 给出的答案是一个**传输协议**（而非实现）：把"要不要数据、要多少"的**控制权从生产者移交给消费者**——消费者通过 `request(n)` 声明容量（**信用/credit**），生产者在信用额度内投递（`onNext`），信用耗尽就停下来等。这就是**背压（backpressure）**，Flow 全部设计的核心。

JDK 的选择极为克制：**只收编协议（4 个接口、7 个方法），不收编实现**——内置实现只有一个通用产消桥梁 `SubmissionPublisher`；map/filter/merge 等算子、调度、容错一概不管，那是 Reactor/RxJava/Akka Streams 等库的事。Flow 的角色相当于 JDBC 之于数据库、Servlet 之于容器：**定义互操作边界，让生态在协议上繁荣**。

---

## 一、JDK 中的 Flow

### 1.1 设计定位与演进脉络

| 时间 | 产物 | 形态 | 与 Flow 的关系 |
|---|---|---|---|
| 2011–2013 | RxJava（Netflix）、Reactor（Pivotal）、Akka（Typesafe） | 各自为战的响应式 API | API 泛滥、互不兼容，倒逼统一协议 |
| **2015** | **Reactive Streams 1.0**（reactive-streams.org） | 4 个 Java 接口 + TCK 合规测试集 | **Flow 的母体**：Netflix、Pivotal、Typesafe、Red Hat 联合制定 |
| **JDK 9 (2017)** | **`java.util.concurrent.Flow`**（Doug Lea，JSR-166） | 4 个嵌套接口 + `SubmissionPublisher` + `defaultBufferSize()` | **逐字对应规范接口收编进标准库**（`Flow.java:45-48` 自述 correspondence） |
| JDK 11 (2018) | `java.net.http` HttpClient | 请求体 `BodyPublisher extends Flow.Publisher`、响应体 `BodySubscriber extends Flow.Subscriber` | JDK 内部第一个（也是旗舰级）真实用户 |
| JDK 21+ | 虚拟线程时代 | 阻塞变廉价 | 背压的价值不减：阻塞省的是**线程**，背压省的是**内存与下游处理能力**（见 2.9） |

三个设计定位需要先建立：

1. **协议，不是库**。`Flow` 是个纯命名空间容器类（私有构造器，`Flow.java:167-169`），装 4 个静态嵌套接口：

   ```
   Flow.Publisher<T>          生产者（@FunctionalInterface，唯一方法 subscribe）
   Flow.Subscriber<T>         消费者（onSubscribe / onNext / onError / onComplete）
   Flow.Subscription<T>       一条"订阅连线"的控制柄：request(n) / cancel()
   Flow.Processor<T,R>        既是 Subscriber 又是 Publisher 的中间站（管道级联用）
   ```

   嵌套而非顶层，是刻意为之：不往 `java.util.concurrent` 里新增 4 个顶层名字，同时让接口名与 Reactive Streams 规范原文一一对应，生态库（实现 `org.reactivestreams.*` 的）做一次薄适配即可互认。

2. **全 void 的单向消息风格**。Javadoc 明说：7 个方法全部返回 `void`、无同步点（`Flow.java:48-49`）——**协议不提供任何"等待对方"的原语**，一切通过异步消息传递。这保证同一套接口在单机线程间和分布式进程间都能落地（Reactive Streams 的野心本来就是跨网络边界的，例如 RSocket）。

3. **推拉混合（push-pull）模型**。数据是推的（`onNext`），许可却是拉的（`request(n)`）——拉的部分就是背压。`Subscription` 是这条连线上**只属于订阅者的控制柄**，规范明确它只应由订阅者调用（`Flow.java:267-269`），相当于消费者手里的"遥控器"。

**语义契约**（协议的心脏，实现与使用者都受它约束）：

| 契约 | 出处 | 内容 |
|---|---|---|
| 信号序列 | Reactive Streams 规则 1.x | `onSubscribe → onNext* → (onError \| onComplete)?`，终止信号至多一个、出现后不再有任何信号 |
| 串行性 | `Flow.java:213-215` | 同一 `Subscription` 上的 Subscriber 方法调用**严格串行**——Subscriber 实现无需加锁、无需 volatile |
| 有序性 | `Flow.java:180-183` | 每个 Subscriber 按发布顺序收到同样的项（除非 drop/出错），且**跨线程 happens-before**：发布动作之前的写操作对订阅者可见 |
| 需求记账 | `Flow.java:272-284` | `request(n)` 把 n 累加进未满足需求；投递一项扣一；`Long.MAX_VALUE` 视为事实上的无界（≈关闭背压）；**n ≤ 0 → onError(IllegalArgumentException)** |
| 取消语义 | `Flow.java:291-293` | `cancel()` 是 best-effort：之后仍可能收到少量 `onNext`；已取消的订阅**不保证**收到终止信号 |
| 重放策略 | `Flow.java:185-189` | Publisher 可自定策略：慢订阅者的项是丢还是错、新订阅者能否看到订阅前的旧数据——协议不做规定（`SubmissionPublisher` 的选择：不重放，见陷阱 2） |

### 1.2 实现原理（SubmissionPublisher 源码级走读）

`Flow` 本体只有接口，全部机制在 `SubmissionPublisher`（1503 行）。它是个**产消桥梁**：任意线程往里 `submit` 数据，它负责按每个订阅者的需求节奏、在每个订阅者独立的缓冲里排队、用 executor 线程异步投递。整体两层结构：

```
SubmissionPublisher<T>                          ← 订阅者名册 + 发布入口（一把 ReentrantLock 兜底）
  ├── ReentrantLock lock          :225           多生产者互斥 + 名册保护
  ├── volatile boolean closed / closedException  关闭与异常收尾
  ├── owner (Thread)              :229-231       单生产者"偏置"优化（类似偏向锁，见④）
  └── clients: BufferedSubscription 链表  :222    每个订阅者一条（含缓冲、状态机、需求记账）
        BufferedSubscription<T>（@Contended）:1036
          ├── Object[] array (环形缓冲, head/tail)  2 的幂容量，初始 32，可扩容
          ├── volatile int ctl      :1042           7 位状态机（见②）
          ├── volatile long demand  :1053           未满足需求（信用余额）
          ├── waiter/waiting        :1047/:1055     submit 阻塞等待缓冲空间
          └── implements Subscription + ForkJoinPool.ManagedBlocker
```

#### ① 线程模型：三类角色三种线程

- **生产者线程**（调 `submit`/`offer` 的任意线程）：只做一件事——往每个订阅者的环形缓冲**写一格**（CAS 或 release 写），然后确保消费任务在跑。极快，几乎无锁竞争。
- **executor 线程**（构造器传入，默认 `ForkJoinPool.asyncCommonPool()`，`SubmissionPublisher.java:300-302`）：运行 `ConsumerTask`，执行**真正的投递**——`onSubscribe`、`onNext`、`onError`、`onComplete` 全在这里回调。**注意：连 `onSubscribe` 都是异步的**（`subscribe()` 把订阅入册后通过 `startOnSignal(RUN|ACTIVE)` 拉起消费任务，由任务在 executor 线程里回调 `subscriber.onSubscribe`，`:1230-1232` + `:1363-1375`），`subscribe()` 返回时回调未必已发生。
- **FJP 补偿机制**：`ConsumerTask` 双身份（`extends ForkJoinTask<Void> implements Runnable`，`:955-965`），提交给任何 Executor 都行；跑在 FJP 里时还能被 `helpAsyncBlocker` 帮跑（见⑦）。

不同订阅者各自有独立的 `BufferedSubscription` 和独立任务，**并行消费互不干扰**；同一订阅者严格串行——这就是协议"每订阅串行"契约的实现基础。

#### ② ctl 状态机：7 个位

`BufferedSubscription` 用一个 `volatile int ctl` 编码全部运行状态（`:1058-1064`）：

| 位 | 值 | 含义 |
|---|---|---|
| `CLOSED` | 0x01 | 已关闭，其他位一律忽略 |
| `ACTIVE` | 0x02 | 消费任务"保活位"——任务空转退出的宽限标记（见⑥） |
| `REQS` | 0x04 | （可能）存在未满足需求；demand 归零即清 |
| `ERROR` | 0x08 | 需要向订阅者发 onError（`cancel()` 也走它但抑制信号，见⑧） |
| `COMPLETE` | 0x10 | 缓冲排空后发 onComplete |
| `RUN` | 0x20 | 消费任务在跑或将跑 |
| `OPEN` | 0x40 | onSubscribe 已发 |

所有迁移用 `getAndBitwiseOr` / `weakCas`（`:1083-1089`），**一个 int 字段 + VarHandle 完成状态机**，与 CompletableFuture 的 `result` 单字段思路同源（一个字段编码全部状态），但这里是**位图**而非对象哨兵。

#### ③ 订阅路径：subscribe()

`subscribe()`（`:326-374`）在锁内完成：为订阅者新建 `BufferedSubscription`（缓冲初始容量 = min(maxBufferCapacity, 32)，`:329-331`）→ 遍历 `clients` 链表：**顺手摘除已关闭的死节点**（`:356-362`，与 CF `cleanStack` 同款惰性清理）、发现**重复订阅**则给已有订阅发 `onError(IllegalStateException)`（`:363-365`）→ 挂到链尾。已关闭的 publisher 也接受订阅，但立刻补发 `onComplete` 或 `onError(closedException)`（`:345-348`）——终态对后来者可见，数据不可见。

#### ④ 发布路径：submit / offer 与"偏置"优化

三个发布方法共用 `doOffer`（`:380-425`）：

```java
public int submit(T item)                     // 阻塞版：nanos = Long.MAX_VALUE，缓冲满就等
public int offer(T item, onDrop)              // 立即版：放不下 → 调 onDrop 处理器，true 则重试一次
public int offer(T item, timeout, unit, onDrop) // 折中：限时等待，超时走 onDrop
```

`doOffer` 的流程体现两处性能心思：

1. **单生产者偏置**（`:388-391`）：`owner` 记住首个订阅/发布的线程；若本线程一直是唯一生产者（`unowned == false`），写缓冲用 **release 写**（`QA.setRelease`，`:1135-1136`）跳过 CAS；一旦出现第二个生产者线程，立刻置 `owner = null` 永久退回 CAS 路径（`:1133`）——**偏向锁的思路**，零竞争时零原子开销。
2. **队头阻塞缓解**（`:397-416`）：第一轮对**所有**订阅者无阻塞地各投一格（快订阅者直接入缓冲），放不下的（`stat == 0`）摘出来串成 `nextRetry` 链；第二轮 `retryOffer`（`:431-454`）才针对饱和者等待/重试/丢弃。慢订阅者只拖慢 `submit` 的返回，不拖慢其他订阅者的入队。

返回值语义（易误解）：**负数 = 丢弃的订阅者个数**（-1、-2…），**正数 = 全体订阅者中的最大滞后估计**（提交了但未被消费的项数，至少为 1），0 = 无订阅者。

#### ⑤ 需求与背压：demand 记账

`request(n)`（`:1254-1266`）就是把 n **饱和累加**进 `demand`（自旋 CAS，加和溢出封顶 `Long.MAX_VALUE`），然后 `startOnSignal(RUN|ACTIVE|REQS)` 确保消费任务在跑。`n <= 0` 直接走 `onError(IllegalArgumentException)`——协议契约在实现里的硬编码。`demand` 是**信用余额**：投递一项 `subtractDemand` 扣一（`:1288`、`:1091-1094`），**消费者不 request，消费任务就不投递**——这就是背压的全部机关，简单到令人失望，也正因为简单才成就了一个跨库协议。

#### ⑥ 消费路径：ConsumerTask 与 consume() 主循环

消费任务**不是每项一个**，而是"有活干持续跑、没活退出、新需求再拉起"：

- 拉起：`tryStart`（`:1206-1216`）把 `ConsumerTask` 丢给 executor；`startOnOffer`/`startOnSignal` 用 `RUN` 位保证**同一时刻最多一个任务**在投递（`:1193-1201`）。
- 主循环 `consume()`（`:1274-1306`）每轮：`ERROR` 位 → 关闭；否则 `takeItems` 尽量多投（见下）；需求耗尽清 `REQS`；缓冲稳定为空且无需求时——若 `ACTIVE` 保活位在，先只清保活位**再宽限一轮**（等新数据，省一次任务重启），否则清 `RUN` 退出；`COMPLETE` 位且缓冲空 → `onComplete` 收尾。

`takeItems`（`:1316-1333`）单批投递上限 = **min(剩余需求, cap/8)**（`:1320` 的 `(m >>> 3) + 1`）：批量摊薄开销，代价是 lag/demand 监控值最多陈旧 12.5%（源码注释 `:1020-1023` 自述此权衡）。每项投递 `consumeNext`（`:1335-1345`）→ **`onNext` 抛异常即该订阅作废**（先调构造器传入的 `onNextHandler` 旁观，再 `closeOnError`，`:1350-1358`）。

#### ⑦ submit 的阻塞与 FJP 深度协作

`submit` 遇到缓冲饱和时 `awaitSpace`（`:1441-1455`）：

1. 先 `ForkJoinPool.helpAsyncBlocker(executor, this)`——若当前线程是 FJP 工作线程，**不等，先帮忙把 executor 上的消费任务跑掉**，很可能一帮就腾出空间了；
2. 还不行则 `ForkJoinPool.managedBlock(this)`——`BufferedSubscription` 本身实现了 `ManagedBlocker`（`:1037`），FJP 检测到工作线程要阻塞会**补偿性扩容线程**，池不会被饿死。等待本体是 `waiter/waiting` 字段 + `LockSupport.park`（`:1461-1485`），消费者腾出格子时 `signalWaiter`（`:1421-1425`）唤醒。

这与 CompletableFuture 的 Signaller+ManagedBlocker（见同系列 1.2⑦）是同一套"FJP 阻塞不饿死"哲学的复用。

#### ⑧ 异常、取消与关闭

- **取消**：`cancel()` = `onError(null)`（`:1250-1252`）——置 `ERROR|CLOSED` 位但 `pendingError` 为 null，`consumeError` 里 `ex != null` 检查使 `onError` 回调被抑制（`:1407-1413`）：**取消是"静默的错误式关闭"**。取消还会 `executor = null` + 清空缓冲 + 唤醒阻塞中的生产者（`:1396-1405`）。
- **onNext 异常**：订阅者自己抛的——先 `onNextHandler`（若有），随后该订阅关闭，**不再收到任何信号**。
- **正常关闭** `close()`（`:609-630`）：锁内置 `closed`、摘下 `clients`，锁外逐个触发 `COMPLETE` 位（缓冲排空后才发 `onComplete`）——`close()` 返回只代表**信号已排定**，不代表订阅者已消费完。
- **异常关闭** `closeExceptionally(ex)`（`:643-668`）：同上但发 `onError(ex)`，且 `closedException` 被记住——**之后的订阅者也会收到这个 onError**（`:345-346`）。

#### ⑨ 内存布局与伪共享

`BufferedSubscription` 类级 `@Contended`（`:1035`），且 `demand` 与 `waiting` 单独 `@Contended("c")` 分组（`:1052-1055`）——生产者高频写 `demand`（request 路径）与消费者高频读它分属不同缓存行，消除多订阅者/多线程下的伪共享。这是把"每秒百万项"热路径当网络框架级问题来抠的（同系列里 Netty VoidChannelPromise 的同款精神）。

### 1.3 接口全景（分类）

**A. Flow 四接口（7 个方法）**：

| 接口 | 方法 | 归属 | 语义 |
|---|---|---|---|
| `Publisher<T>` | `subscribe(s)` | 生产者 | 建立订阅；失败/重复则 `onError(IllegalStateException)` |
| `Subscriber<T>` | `onSubscribe(sub)` | 订阅者回调 | **先于一切**；通常在这里 `request` 首批信用 |
| | `onNext(item)` | 订阅者回调 | 收数据；抛异常 = 订阅作废（实现相关） |
| | `onError(t)` | 订阅者回调 | 不可恢复错误，流终止 |
| | `onComplete()` | 订阅者回调 | 正常终止，流结束 |
| `Subscription` | `request(n)` | **订阅者调** | 追加信用；n≤0 → onError；MAX_VALUE ≈ 无背压 |
| | `cancel()` | **订阅者调** | best-effort 断开；不保证终止信号 |
| `Processor<T,R>` | （继承上两者） | 中间站 | 桥接上游消费与下游发布 |

**B. SubmissionPublisher API**：

| 类别 | 方法 | 说明 |
|---|---|---|
| 构造 | `SubmissionPublisher()` | 默认池 = `ForkJoinPool.asyncCommonPool()`，缓冲 256（`:300-302`） |
| | `SubmissionPublisher(executor, maxBufferCapacity[, onNextHandler])` | **IO 密集回调必须显式传池**；容量向上取 2 的幂（`roundCapacity`，`:197-207`） |
| 发布 | `submit(item)` | 阻塞直到所有订阅者缓冲有空间；返回最大滞后估计 |
| | `offer(item[, onDrop])` / `offer(item, timeout, unit, onDrop)` | 丢弃策略；返回负数=丢弃数，正数=最大滞后 |
| 关闭 | `close()` / `closeExceptionally(ex)` | 触发 onComplete / onError；后者对**未来订阅者**也生效 |
| 监控 | `hasSubscribers()` / `getNumberOfSubscribers()` / `getSubscribers()` / `isSubscribed(s)` | 订阅者名册（监控用，勿用于同步） |
| | `estimateMaximumLag()` / `estimateMinimumDemand()` | 最大积压 / 最小未满足需求——背压健康度的两个仪表盘 |
| 简化消费 | `consume(consumer)` | 内置 ConsumerSubscriber，返回 `CompletableFuture<Void>` 作完成句柄；**request(Long.MAX_VALUE)，无背压**（`:907-944`） |

**C. 与 Reactive Streams 的关系**：`java.util.concurrent.Flow` 与 `org.reactivestreams` 的接口**语义逐字等价**；生态库（Reactor、RxJava 2/3、Mutiny、Akka Streams…）全部以 Reactive Streams/Flow 为互操作边界。选库时看它是否"Reactive Streams compliant"（有 TCK 认证），就等于确认了与 JDK Flow 的互认。

### 1.4 使用场景与示例（详细版）

**场景 1：入门——传感器数据推送（SubmissionPublisher 基本形态）。** 温度采集线程持续上报，两个下游各按自己的节奏消化：

```java
try (SubmissionPublisher<Double> publisher = new SubmissionPublisher<>()) {   // AutoCloseable，close() 自动广播 onComplete

    publisher.subscribe(new Flow.Subscriber<>() {
        private Flow.Subscription subscription;
        @Override public void onSubscribe(Flow.Subscription s) {
            this.subscription = s;
            s.request(8);                       // 初始只授权 8 项 —— 背压从这一行开始
        }
        @Override public void onNext(Double t) {
            System.out.println("DB存储: " + t); // 在 publisher 的 executor 线程串行执行
            subscription.request(1);            // 消化一项，再授权一项（单步信用）
        }
        @Override public void onError(Throwable t) { t.printStackTrace(); }
        @Override public void onComplete() { System.out.println("采集结束"); }
    });

    // 另一个订阅者：只打印，消费快 —— 各订阅者独立缓冲与信用，互不牵连
    publisher.consume(t -> System.out.println("仪表盘: " + t));

    for (int i = 0; i < 1_000; i++) {           // 生产者全速发
        int lag = publisher.submit(i * 0.1);
        if (lag > 500) System.out.println("警告：最大积压 " + lag);   // submit 返回滞后估计
    }
}   // try-with-resources 退出时 close() → 订阅者收到 onComplete
```

要点：订阅者**先授权再收货**（`onSubscribe` 里的 `request` 是第一推动力，不 request 就永远收不到数据）；`submit` 返回的 lag 是现成的健康度指标；`consume` 是"懒人订阅"（但无背压，见陷阱 9）。

**场景 2：半量补充策略——生产级背压 Subscriber 的标准形状。** 单步信用每项一次 `request` 往返太碎；官方 Javadoc 给的 SampleSubscriber 模式（`Flow.java:123-145`）是**批量化信用**的标准答案，值得逐行读懂其不变量：

```java
class HalfRefillSubscriber<T> implements Flow.Subscriber<T> {
    private final long bufferSize;          // 例如 64：最大在途授权
    private final Consumer<T> handler;      // 业务处理
    private Subscription subscription;
    private long remaining;                 // 未消费的信用余额

    HalfRefillSubscriber(long bufferSize, Consumer<T> handler) {
        this.bufferSize = bufferSize;
        this.handler = handler;
    }
    @Override public void onSubscribe(Subscription s) {
        this.subscription = s;
        remaining = bufferSize - bufferSize / 2;    // 消费到一半时该剩的量
        s.request(bufferSize);                       // 首批全额授权
    }
    @Override public void onNext(T item) {
        if (--remaining <= 0)                        // 消费过半 → 补一半
            subscription.request(remaining = bufferSize - bufferSize / 2);
        handler.accept(item);
    }
    @Override public void onError(Throwable t) { log.error("流异常", t); }
    @Override public void onComplete() { }
}
```

不变量：**在途授权始终落在 [bufferSize/2, bufferSize] 区间**（以 64 为例，介于 32~64，正是 Javadoc `:113-117` 自述的数字）。授权太大 = 退化为无背压（下游缓冲/内存受它钳制）；太小 = 往返开销大。这个"消费到一半补一半"的模式就是所有响应式库内部 prefetch 的雏形。

**场景 3：自定义 Publisher——数据库游标/分页拉取（拉模型的真正价值）。** 背压的杀手级应用：**把一个任意大的结果集流式送给消费者，内存占用 = 授权量而非结果集大小**。与 `SubmissionPublisher` 的本质区别：不是"先生产后排队"，而是"request 驱动生产"——不授权就不查库：

```java
/** 把 JDBC 游标/Spliterator 变成 Flow.Publisher：request 驱动拉取 */
public class CursorPublisher<T> implements Flow.Publisher<T> {
    private final Spliterator<T> cursor;      // 惰性数据源：游标/文件行/迭代器
    private final Executor executor;          // 投递线程（与查询线程解耦）

    public CursorPublisher(Spliterator<T> cursor, Executor executor) {
        this.cursor = cursor; this.executor = executor;
    }

    @Override
    public void subscribe(Flow.Subscriber<? super T> subscriber) {
        // 协议要求：任何 onNext 之前必须先 onSubscribe
        subscriber.onSubscribe(new Subscription() {
            long demand = 0; boolean cancelled = false;

            @Override public synchronized void request(long n) {
                if (n <= 0) { subscriber.onError(new IllegalArgumentException("n<=0")); return; }
                demand += n;
                drain();
            }
            private void drain() {
                executor.execute(() -> {               // 在 executor 线程投递
                    synchronized (this) {              // 简化示例：生产实现需处理重入与并发 request/cancel 竞态（过 RS TCK）
                        while (demand > 0 && !cancelled) {
                            if (!cursor.tryAdvance(subscriber::onNext)) {   // 查一批、吐一行 —— 不授权就不前进
                                cancelled = true;
                                subscriber.onComplete();               // 游标耗尽 → 正常终止
                                break;
                            }
                            demand--;
                        }
                    }
                });
            }
            @Override public synchronized void cancel() {
                cancelled = true;
                // ★ 真实资源语义：这里应 close 游标/连接 —— cancel 是真正的"停止生产"，
                //   而不是 CompletableFuture.cancel() 那种"只改状态不管底层"（见 2.7）
            }
        });
    }
}

// 用法：一亿行的大表导出，内存里任何时刻最多只有 ~1000 行在途
CursorPublisher<Row> rows = new CursorPublisher<>(resultSet.spliterator(), ioPool);
rows.subscribe(new HalfRefillSubscriber<>(1000, row -> sink.write(row)));
```

对比：`fetchAll()` 一次性物化（OOM 风险）→ Flow 拉模型（O(信用) 内存）。**这正是"为什么有了 CompletableFuture 还要 Flow"的最直接答案之一。**

**场景 4：Processor 管道——日志清洗流水线（级联与背压传导）。** `Processor` 既是上游的订阅者又是下游的发布者，级联即管道。官方 TransformProcessor 骨架（`SubmissionPublisher.java:152-170`）扩展成三段：

```java
/** 通用转换 Processor：继承 SubmissionPublisher 获得对下游的发布能力，自己实现 Subscriber 消费上游 */
class TransformProcessor<S, T> extends SubmissionPublisher<T> implements Flow.Processor<S, T> {
    private final Function<? super S, ? extends T> function;
    private Flow.Subscription upstream;
    TransformProcessor(Executor executor, int maxBuffer, Function<? super S, ? extends T> f) {
        super(executor, maxBuffer);
        this.function = f;
    }
    @Override public void onSubscribe(Flow.Subscription s) {
        this.upstream = s;
        s.request(1);                                  // 简化：单步授权。生产中用窗口化（场景 2）
    }
    @Override public void onNext(S item) {
        submit(function.apply(item));                  // 向下游发布（阻塞直到下游缓冲有空间 → 背压向上传导）
        upstream.request(1);                           // 再向上游要下一项 —— 形成"信用接力"
    }
    @Override public void onError(Throwable t) { closeExceptionally(t); }   // 异常向下游传导
    @Override public void onComplete() { close(); }                         // 正常终止向下游传导
}

// 组装：采集 → 只留 ERROR 级 → 压缩成 JSON → 落盘
SubmissionPublisher<LogEvent> source = new SubmissionPublisher<>(ioPool, 256);
var filtered = new TransformProcessor<LogEvent, LogEvent>(bizPool, 256,
                       e -> e.level() >= Level.ERROR ? e : null);
var encoded = new TransformProcessor<LogEvent, byte[]>(ioPool, 256,
                       e -> Json.encode(e));

source.subscribe(filtered);      // 上游订阅接线：source 的下游是 filtered
filtered.subscribe(encoded);     // filtered 的下游是 encoded
encoded.subscribe(fileSink);     // 终点订阅者

source.submit(logEvent);         // 任意线程生产
```

理解要点：**每一级的缓冲就是背压的"液压缓冲器"**——下游慢 → 下级缓冲满 → 上级 `submit` 阻塞 → 上级停止向上游 request → 上游缓冲积压……整条管道的速率被最慢一级钳制，且每一级内存有界。同时注意：`onNext` 里 `submit` 是阻塞调用，**绝不能在 publisher 的投递线程上做慢事**（陷阱 1）——示例里 filtered/encoded 的投递池与 source 的池分开，就是为了隔离级联回调。

**场景 5：JDK 内部实战——java.net.http 流式下载（Flow 与 CF 混用的官方范本）。** HttpClient 的响应体就是一条 Flow 流：`BodySubscriber extends Flow.Subscriber<List<ByteBuffer>>`（`HttpResponse.java:1043-1046`），网关按网络速率 push 分块，订阅者用 request 控制接收速率。手写一个 NDJSON 逐行解析订阅者：

```java
/** 流式解析 NDJSON 响应：内存中只保留"在途分块 + 已解析对象"，而非整个 body */
class NdjsonSubscriber<T> implements HttpResponse.BodySubscriber<Void> {
    private final CompletableFuture<Void> done = new CompletableFuture<>();
    private final Consumer<T> sink;                 // 逐对象处理（如批量入库）
    private final StringBuilder lineBuf = new StringBuilder();
    private Flow.Subscription subscription;

    NdjsonSubscriber(Consumer<T> sink) { this.sink = sink; }

    @Override public CompletionStage<Void> getBody() { return done; }   // CF 作为"流何时消费完"的句柄

    @Override public void onSubscribe(Flow.Subscription s) {
        this.subscription = s;
        s.request(4);                               // 窗口化授权：一次收 4 个分块
    }
    @Override public void onNext(List<ByteBuffer> chunk) {
        try {
            chunk.forEach(bb -> { /* 解码字节，按 \n 切行，每行 Json.decode → sink.accept(obj) */ });
            subscription.request(4);                // 处理完这批再要下一批：网速 > 处理速度时自动限速
        } catch (Exception e) {
            subscription.cancel();                  // ★ 真实资源语义：中断后 HTTP 连接被关闭（HttpResponse.java:1030-1033）
            done.completeExceptionally(e);
        }
    }
    @Override public void onError(Throwable t) { done.completeExceptionally(t); }
    @Override public void onComplete() { done.complete(null); }
}

// 接线：HttpClient.sendAsync 返回 CompletableFuture（编排世界），
// 而 body 数据本身沿 Flow 流动（传输世界）——两个体系各司其职
CompletableFuture<Void> imported =
    client.sendAsync(request, resp -> HttpResponse.BodyHandlers.fromSubscriber(
            new NdjsonSubscriber<>(order -> dao.batchSave(order))).apply(resp))
         .thenApply(HttpResponse::body);            // thenApply 是 CF 的编排算子，作用在"流消费完成"这个单值上
imported.orTimeout(10, TimeUnit.MINUTES);           // CF 的超时算子为整条流兜底
```

注意 `getBody()` 返回 `CompletionStage` 的深意：**流的每个数据项走 Flow，"整条流何时处理完"这个单值语义走 CF**——JDK 自己就在边界上做两套体系的缝合。上传侧同理：`HttpRequest.BodyPublisher extends Flow.Publisher<ByteBuffer>`（`HttpRequest.java:621`），`BodyPublishers.ofInputStream` 用一个"request 驱动读"的 publisher 包住 `InputStream`——**读取速率由 HTTP 层的授权决定**，大文件上传不会把整个文件读进内存。若不想手写，`BodyHandlers.fromLineSubscriber` / `fromSubscriber` 是官方提供的适配器（JDK 11+）。

**场景 6：丢弃策略与监控——行情/遥测推送的"宁可丢不可堵"。** 行情数据新值永远比旧值有价值，背压的正确姿势是**丢弃而非阻塞**：

```java
SubmissionPublisher<Quote> quotes = new SubmissionPublisher<>(ioPool, 1024);

int stat = quotes.offer(quote, 50, TimeUnit.MILLISECONDS, (sub, item) -> {
    log.warn("订阅者 {} 缓冲饱和，丢弃 {}", sub, item.id());
    return false;                       // true 只会重试一次；行情场景直接放弃
});
if (stat < 0)  log.warn("本轮共 {} 个订阅者被丢弃投递", -stat);
// stat > 0 时它是最大滞后估计，可作为降级信号（如转推 Kafka 削峰）

// 独立监控线程定期看仪表盘（都是估计值，Javadoc 明确不可用于同步控制）
scheduler.scheduleAtFixedRate(() -> {
    log.info("订阅者={} 最大积压={} 最小剩余授权={}",
             quotes.getNumberOfSubscribers(),
             quotes.estimateMaximumLag(),
             quotes.estimateMinimumDemand());
}, 0, 5, TimeUnit.SECONDS);
```

三档发布策略的选择口诀：**要完整性用 `submit`（可能阻塞生产者）、要时效性用 `offer`（可能丢）、要折中用限时 `offer`（先等再丢）**。

**场景 7：消费完成接入 CF 世界（终点站模式）。** 流的终点常常是"全部处理完"这个单值事件——`consume()` 内置了这座桥：

```java
CompletableFuture<Void> drained = publisher.consume(order -> dao.save(order));
// ConsumerSubscriber 内部：request(Long.MAX_VALUE) 全速收（无背压！），
// consumer 抛异常 → cancel 订阅 + CF 异常完成（SubmissionPublisher.java:936-943）

drained
    .orTimeout(30, TimeUnit.SECONDS)                 // 流没有"超时"概念，用 CF 算子兜底整条流
    .thenRun(() -> metrics.mark("订单回放完成"))
    .exceptionally(e -> { log.error("回放失败", e); return null; });
```

模式总结：**Flow 管过程（每一项），CF 管结局（整条流）**——这与场景 5 中 JDK 的用法完全同构，可视为两者互操作的标准公式。

### 1.5 陷阱清单

1. **回调线程 = publisher 的 executor，且同一订阅者严格串行**：`onNext` 里阻塞或慢处理，整个订阅立即停摆；若生产者又在 `submit` 阻塞，卡死会级联回生产者线程。与 Netty "监听器里别做慢事"（同系列陷阱 1）同源，但 **Flow/SubmissionPublisher 没有 Netty 那样的 EventLoop 死锁快速失败**——全靠纪律。IO 密集回调务必显式传业务池（默认池是 commonPool，与 CF 同样的污染问题）。
2. **不重放**：`SubmissionPublisher` 不缓存历史，`submit` 时无订阅者的数据**直接消失**（`doOffer` 里 `b == null` 直接返回）。"先生产后订阅补发历史"的诉求要自己换实现（如基于 Kafka 的 source）。
3. **`onSubscribe` 是异步的**：在 executor 线程的首个消费任务里回调（1.2①），`subscribe()` 返回时未必已发生；不要在 `subscribe` 之后立即假设订阅者已收到 `onSubscribe`。
4. **`request(n ≤ 0)` 是致命错误**：按协议直接 `onError(IllegalArgumentException)` 收尾整条订阅。动态计算授权量时防住负数；也注意 `Long.MAX_VALUE` 会饱和累加（永久关闭背压）。
5. **订阅者抛异常 = 订阅作废**：`onNext` 里未捕获的异常会关闭该订阅（构造器的 `onNextHandler` 只是旁观者，救不回来，1.2⑥）。想容错在订阅者内部自己 try-catch。
6. **重复订阅同一个 Subscriber 实例**：不是幂等忽略，而是给已有订阅发 `onError(IllegalStateException)`（`:363-365`）。
7. **`offer` 返回值当布尔用**：负数是"丢弃的订阅者个数"，正数是"最大滞后估计"，0 是"无订阅者"——语义三分，`if (offer(...) > 0)` 这类判断必然写错。
8. **`maxBufferCapacity` ≠ 背压上限**：容量向上取整到 2 的幂；且 request 授权可以远超缓冲容量——授权未消费的部分会挤压缓冲直到饱和（Javadoc `:77-82` 明确这一交互）。**真正钳制内存的是订阅者的 request 节奏，不是缓冲容量**；缓冲容量决定的是"生产者被阻塞/丢弃的时机"。
9. **`consume()` 无背压**：内部 `request(Long.MAX_VALUE)`（`:928`），全速灌给 consumer——快消费者图省事可用，慢消费者用它等于背压全废。
10. **`cancel` 与 `close` 都是 best-effort/异步**：cancel 后仍可能收到少量 `onNext`（协议允许，`Flow.java:291-293`），订阅者要终态容错；`close()` 返回不代表订阅者已消费完缓冲（1.2⑧），确定性收尾要靠订阅者的 `onComplete` 回调本身。
11. **Flow 没有任何算子**：map/filter/merge/retry/window 一概没有，`Processor` 要手写或上 Reactor/RxJava/Mutiny（它们实现了 Flow/RS 接口，可与 `SubmissionPublisher` 直接互连）。

---

## 二、Flow 与 CompletableFuture 对比

### 2.1 问题域一图看懂

```
CompletableFuture：结果句柄 —— "一件事做完之后做什么"
  producer ──complete(v)/completeExceptionally(t)──▶ CF ──thenApply──▶ CF' ──...
  · 0 或 1 个结果，终态不可变；链上的一切都是"完成事件"的传播与变换
  · 没有"速率"概念：CF 不产数据，只搬运结果
  · 组合的单位是"另一个 CF"（thenCombine/anyOf…）

Flow：传输协议 —— "N 个数据项从快的一端流向慢的一端，谁也不压垮谁"
  producer ──submit(item)──▶ Publisher ──onNext──▶ Subscriber
                  ▲                                   │
                  └───────── Subscription.request(n) ─┘   ← 背压：消费者反向声明容量
  · 0..N 项 + 至多 1 个终止信号（onError | onComplete）
  · 速率失配由 缓冲/阻塞/丢弃 三档策略吸收，控制权在消费者手里
  · 组合的单位是"一条订阅连线"（Publisher→Subscriber 接线，Processor 级联）
```

一句话：**CF 是"结果"的语言，Flow 是"速率"的语言**。CF 上不存在"数据生产太快"这个概念（它只有一件事）；Flow 上不存在"完成后组合另一件事"这个概念（它的核心动词是 request/cancel，而不是 then/combine）。

### 2.2 全维度对比表

| 维度 | CompletableFuture | Flow |
|---|---|---|
| 诞生 | JDK 8（2014，Doug Lea） | JDK 9（2017，Doug Lea 收编 Reactive Streams 2015 规范） |
| 解决的问题 | 单个异步操作的完成通知与声明式编排 | 速率不匹配的组件间流式传输（背压） |
| 数据模型 | **0/1 个结果**，终态不可变 | **0..N 项 + 1 个终止信号**， publisher 可对多订阅者重复使用 |
| 控制权方向 | 生产者/完成者主导（完成即通知下游） | **消费者主导**（request 授权，生产者被钳制） |
| 背压 | ❌ 无此概念 | ✅ 协议核心（credit 模型） |
| 接口面 | `CompletionStage` 约 50 个组合算子 + Future 读法 | **4 接口 7 方法，0 算子**（协议层） |
| 写权限 | 任何持引者可 `complete`（靠 copy/minimalStage 补权限墙） | 生产者/消费者接口天然分离，订阅者只有 request/cancel 两个"遥控"动作 |
| 默认执行器 | commonPool（async 变体） | `ForkJoinPool.asyncCommonPool()`（SubmissionPublisher 默认投递池）——同样的"IO 任务必须显式传池"纪律 |
| 回调线程 | 不确定三选一（完成者/注册者/executor） | 每订阅者固定在其 publisher 的 executor 线程、**严格串行**；不同订阅者并行 |
| 异常模型 | 异常即数据，沿链自动传播、逐级可拦截 | `onError` 是终止信号，至多一次，到达即流结束；无"恢复后继续" |
| 组合多源 | allOf / anyOf / thenCombine 全家桶 | 无聚合原语（自己写 Processor/计数器） |
| 取消语义 | `cancel` = 异常完成，**底层任务照跑** | `Subscription.cancel` = 停止拉取，**真实资源被释放**（HTTP 连接关闭、游标关闭） |
| 重放/缓存 | complete 后任何时刻注册 `thenXxx` 都能拿到结果 | 默认不重放，后到的订阅者只能拿到终止信号 |
| 阻塞读 | `get/join`（Signaller + ManagedBlocker） | `submit` 内部阻塞（同为 ManagedBlocker 体系）；订阅者侧协议无阻塞原语 |
| 超时 | 内置 `orTimeout/completeOnTimeout` | 协议无；工程上用 CF 的超时算子兜底"整条流"（1.4 场景 5/7） |
| 监控面 | `getNumberOfDependents`（弱） | lag/demand/订阅者名册（估计值，仪表盘语义） |
| JDK 内置实现 | CompletableFuture 本体（全家桶式） | 仅 `SubmissionPublisher`（桥梁式）；算子库全留给生态 |
| JDK 内部用户 | 编排无处不在 | `java.net.http`（BodyPublisher/BodySubscriber）；CF 反而承担其编排 |
| 生态位 | Java 异步**编排**的事实标准 | Reactive Streams 生态（Reactor/RxJava/Akka/Mutiny）的**互操作协议** |
| 与虚拟线程 | "少量异步+大量阻塞"的粘合剂 | 背压价值不减：阻塞省线程，背压省内存与下游算力（2.9） |

### 2.3 数据模型对比：一次性终态 vs 流终止信号

```java
// CF：终态是一个"值"，且注册竞争被 CAS 抹平 —— 完成前注册、完成后注册，结果一致
CompletableFuture<String> f = load();
f.thenApply(x -> x + "!");        // 现在注册 → 得到结果
TimeUnit.SECONDS.sleep(10);
f.thenApply(x -> x + "?");        // 十秒后注册 → 同样得到结果（已完成即快路径）
// 终态：SUCCESS / FAILED / CANCELLED，三选一，永不改变（obtrude 除外）

// Flow：流是一个"过程"，终态是一个"信号"，且过程对每个订阅者独立发生
SubmissionPublisher<String> pub = new SubmissionPublisher<>();
pub.submit("a");                  // 此刻无订阅者 → "a" 消失（不重放！）
pub.subscribe(sub);               // 后来者只影响"未来"
pub.submit("b");                  // sub 收到 "b"
pub.close();                      // sub 收到 onComplete；此后订阅者只收到 onComplete，"a"永远不可见
```

两个体系对"时间"的处理截然相反：**CF 把时间抹平**（终态可随时回看，晚注册不丢结果），**Flow 让时间单向流**（每项数据只经过一次，错过的就是错过了）。这决定了：需要"结果可重读"的用 CF；需要"数据即时消费、不可回放"的（行情、遥测、流转发）天然适合 Flow。若确实需要"晚来的订阅者补看历史"，那是在 Flow 之上加持久化/replay 能力（Kafka 类 source 的领地），协议层不管。

### 2.4 背压：Flow 的立身之本，CF 的盲区

CF 世界里"生产过多"没有防御手段，因为它的抽象里根本没有速率——看一个真实的事故形状：

```java
// 需求：把一千万个订单 ID 逐个调远程接口，限流 100 QPS
// CF 直觉写法（事故版）：
List<CompletableFuture<Order>> all = ids.stream()
    .map(id -> CompletableFuture.supplyAsync(() -> client.load(id), pool))
    .toList();                                  // ← 一千万个任务瞬间全部入池排队
// 结果：池队列无界膨胀（OOM）、下游被打爆、任何"控制"都发生在已提交之后。
// 事后补救（信号量限流、分批 allOf）都是在 CF 之上手工造背压，且极易出错。
```

Flow 把这层控制内置进协议：

```java
// request 就是限流阀：消费者只授权 100，生产者（拉模型 publisher）最多在途 100
subscription.request(100);
// 每处理完一个：
subscription.request(1);       // 生产-消费速率被强制同步，内存 O(100)，下游压力可控
```

本质差异一句话：**CF 的并行度控制（传池、信号量）是"提交前"的粗粒度审批；Flow 的背压是"传输中"的细粒度反馈**。前者管"开工多少活"，后者管"数据流多快"。两者也在实际系统里叠加使用：池限制并发线程数，背压限制在途数据量。

### 2.5 组合能力对比：50 个算子 vs 0 个算子

这是 CF 压倒性的优势区（同系列 3.5 的实战对照在这里同样成立，且更悬殊）：CF 的 thenCompose/thenCombine/orTimeout/exceptionally 让复杂业务编排成为一行链；Flow 连 map/filter 都没有，一切结构靠手写 `Processor` 或第三方库。

但换个视角：**Flow 的 0 算子是刻意的**。协议要的是被 Reactor、RxJava、Akka Streams、Mutiny 同时实现——算子做进 JDK 反而会与生态打擂台、锁死演进速度（JDK 接口一旦发布极难改）。于是形成了清晰的分层：

| 层 | 单值（一件事） | 流（N 件事） |
|---|---|---|
| JDK 协议/原语 | `CompletableFuture` | `Flow` + `SubmissionPublisher` |
| 生态算子库 | （基本不需要，CF 已够） | Reactor `Flux` / RxJava `Observable` / Mutiny `Multi` / Akka Streams |
| 生态的"单值"形态 | — | Reactor `Mono`、Mutiny `Uni`（都实现 CF 互操作：`toCompletableFuture()`） |

注意 Reactor 的 `Mono/Flux` 划分恰好复刻了 CF/Flow 的分野：**单值流 = CF 的思想，多值流 = Flow 的思想**——生态库把两者统一进同一套算子体系，而 JDK 把两者的"素颜形态"直接给了你。

### 2.6 线程模型对比

| | CompletableFuture | Flow（SubmissionPublisher） |
|---|---|---|
| 回调线程 | **不确定**：完成者线程 / 注册线程 / executor 三选一 | **确定**：onNext/onSubscribe 固定在 publisher 的 executor 线程 |
| 串行性 | 链上无串行承诺（同一 CF 的多个 thenXxx 无序） | 同一 Subscription 的信号**严格串行**（协议契约）；不同订阅者并行 |
| 多订阅者 | 不适用（单值） | 每订阅者独立缓冲+任务，慢者不拖快者（offer 两轮投递缓解队头阻塞） |
| 生产者线程 | `supplyAsync` 即提交线程池 | 任意线程 `submit`，只写一格缓冲（偏置优化下无 CAS）；阻塞时靠 ManagedBlocker 不饿死 FJP |

Flow 的"每订阅串行 + 固定线程"与 Netty 的"每 Channel 串行 + EventLoop 亲和"（同系列 3.3）在设计精神上同源：**给消费者一个无需加锁的单线程执行域**；区别是 Netty 的执行域是显式绑定的 EventLoop，Flow 的执行域是 publisher 构造时传的 executor。而 CF 的回调线程不确定性在 Flow 世界里基本消失——代价依旧：**在 onNext 里做慢事会停摆整条订阅**（与"在 EventLoop 里做慢事拖垮所有 Channel"同构，见陷阱 1）。

### 2.7 异常与取消模型对比

**异常**：CF 的异常是"数据"——沿链流动、可逐级拦截（exceptionally/handle）、甚至可以在下游"恢复"后继续正常流转。Flow 的异常是"终局"——`onError` 最多出现一次，出现即流死，**没有"恢复后这条流继续"的协议表达**（要恢复，唯一办法是 publisher/Processor 在自己内部捕获异常后选择不发 onError、继续或正常 close——这是 publisher 的策略自由，不是协议能力）。工程上"流级兜底"的标准姿势是用 CF 管"整条流的结局"（`consume()` 返回的 CF 上挂 exceptionally/orTimeout，见 1.4 场景 7）。

**取消**——两者语义差距最大的一条，值得单独展开：

```java
// CF：cancel 只是给自己打个"已取消"标记，底层任务毫无感知、照跑不误
CompletableFuture<Order> f = supplyAsync(() -> client.load(id), pool);
f.cancel(true);            // mayInterruptIfRunning 被无视；client.load 仍在池里跑完
// 想真停？必须自己持有 Future 句柄/任务引用——CF 不给你这个能力

// Flow：cancel 掐断的是"生产许可"，而拉模型下未授权的工作根本还没开始
subscription.cancel();     // producer 的 drain 循环停转、游标关闭、HTTP 连接关闭
// java.net.http 的实现（HttpResponse.java:1030-1033）明文：cancel 后底层 HTTP 连接会被关闭
```

根源在于工作模型：**CF 是"已提交的计算"的句柄**（工作已经发生，cancel 只能改状态）；**Flow 拉模型下生产被需求驱动**（没有授权就没有工作，cancel 是釜底抽薪）。把"取消是否真省资源"作为选型信号之一：要真取消（长下载、大查询）用 Flow 拉模型；CF 的取消只适合"结果不要了"的语义。

### 2.8 互操作桥接与 JDK 内部的"标准答案"

两套体系互转的通用模式（CF 的 `whenComplete` 与 Flow 的订阅回调都是 void 回调，缝合点天然存在）：

```java
/** Flow 流 → CF：把"整条流消费完成"收拢成单值（收集全部数据项，注意内存） */
static <T> CompletableFuture<List<T>> drainAll(Flow.Publisher<T> publisher, Executor pool) {
    CompletableFuture<List<T>> done = new CompletableFuture<>();
    List<T> buffer = Collections.synchronizedList(new ArrayList<>());
    publisher.subscribe(new Flow.Subscriber<>() {
        Flow.Subscription s;
        public void onSubscribe(Flow.Subscription s) { this.s = s; s.request(Long.MAX_VALUE); }
        public void onNext(T item) { buffer.add(item); }
        public void onError(Throwable t) { done.completeExceptionally(t); }
        public void onComplete() { done.complete(List.copyOf(buffer)); }
    });
    return done;   // 之后即可 thenApply/orTimeout/thenCombine —— 进入 CF 编排世界
}

/** CF → Flow：把单值结果"发布"成一项的流（一次性流 = 单值的世界观对齐） */
static <T> Flow.Publisher<T> asPublisher(CompletableFuture<T> cf, Executor pool) {
    SubmissionPublisher<T> pub = new SubmissionPublisher<>(pool, Flow.defaultBufferSize());
    cf.whenComplete((v, t) -> {          // CF 完成线程不确定 → 用 pub 自己的池投递
        pool.execute(() -> {
            if (t != null) pub.closeExceptionally(t);
            else { pub.submit(v); pub.close(); }
        });
    });
    return pub;
}
```

（`SubmissionPublisher.consume()` 就是官方内置的第一种桥；第二种桥对应"单元素流"，Reactor 的 `Mono` 即此形态的算子化。桥接时注意线程语义：Flow 的回调在 publisher 池上，CF 的回调线程不确定，跨体系传递时先显式落回一个受控线程——与同系列 3.8 的 Netty↔CF 桥接同一个纪律。）

**JDK 内部的标准答案——java.net.http 的混合架构**，两者不是竞争者而是分工协作：

```
HttpClient.sendAsync(request, bodyHandler)
   │
   ├─ 编排层（CompletableFuture）：请求生命周期 —— sendAsync 返回 CF<HttpResponse<T>>，
   │    thenApply/thenCompose/exceptionally 做重试、聚合、超时兜底
   │
   ├─ 请求体（Flow.Publisher<ByteBuffer>，HttpRequest.java:621）：
   │    ofInputStream 的 request 驱动读 → 上传大文件内存有界
   │
   └─ 响应体（Flow.Subscriber<List<ByteBuffer>>，HttpResponse.java:1043）：
        网络分块 push + 订阅者 request 限速 → 下载/解析内存有界，cancel 真关连接
```

规则可以提炼为：**"过程"用 Flow（每一项数据的时序、速率、取消），"结局与编排"用 CF（整条流何时完成、失败后怎么办、和其他异步任务怎么汇合）**。

### 2.9 选型建议

| 场景 | 选择 | 理由 |
|---|---|---|
| 一次 RPC / 一步异步计算 / 少量任务并行聚合 | **CompletableFuture** | 单值编排是它的主场（同系列 3.9 同款结论） |
| 已知规模的批量任务（allOf + join） | CompletableFuture | 有限集合一次物化无碍，背压无必要 |
| 生产速率不可控、消费能力有限（消息消费、事件推送、行情） | **Flow** | 背压是协议级能力，CF 只能手工造轮子（2.4） |
| 大结果集/大文件/游标的流式处理，要求内存有界、可中途取消 | **Flow（拉模型）** | request 驱动生产，cancel 真实释放资源（2.7） |
| 流 + 丰富算子（窗口/合并/重试/调度） | Flow 协议 + **Reactor/RxJava/Mutiny** | JDK 层 0 算子是刻意设计（2.5），别手搓 Processor 大全 |
| 单次响应体的流式下载/上传 | java.net.http（内部即 Flow+CF 混合） | 官方范本：过程 Flow、结局 CF（2.8） |
| 桥接回调式 SDK，且 SDK 天然持续产出 | SubmissionPublisher + 手写桥 | 生产者侧建模为流；单结果 SDK 用 CF（同系列场景 4） |
| 虚拟线程时代（JDK 21+）以阻塞为主 | 直接阻塞 + 虚拟线程；流式场景仍用 Flow | 阻塞廉价化省的是**线程**；背压省的是**内存与下游算力**——正交收益，不因虚拟线程消失 |

**一句话总结**：CompletableFuture 回答"**一件事完成后，如何与其他事声明式地编排**"，Flow 回答"**一条数据河，如何在生产者与消费者之间以双方都承受得起的速率流动**"。前者把"完成"建模为一等公民（终态值 + 算子链），后者把"速率"建模为一等公民（request/cancel + 背压）；一个是结果的语言，一个是速率的语言。JDK 11 的 HttpClient 已经示范了答案——**编排交给 CF，传输交给 Flow，边界上用"流的结局是一个 CompletableFuture"缝合**。理解这条分界线，就理解了 Java 异步版图中"一次性"与"流式"两半各自的疆界，也就知道什么时候该用哪个、在哪里缝合。
