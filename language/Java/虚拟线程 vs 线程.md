# 虚拟线程 vs 线程 —— 基于 JDK 25 的迁移全景

> 基于 **JDK 25(25.0.1)** 源码与 **Spring Boot 4.1.1** 源码撰写,目标只有一个:**把现有"平台线程(线程池)"应用安全迁移到"虚拟线程"**。
> JDK 源码坐标以 `D:\soft\jdk\jdk-25.0.1\lib\src.zip` 解压后为准;Spring 源码坐标以 `D:\code\3rd\spring-boot`(4.1.x 分支)、`D:\code\3rd\spring-framework`(7.1.x)、`D:\code\3rd\tomcat`(11.x) 为准;文中【实测】结论均在本机 JDK 21/25 上跑通(实验代码见附录 C)。
> 兄弟文档:[Java并发.md](<Java并发.md>)、[cursor_JDK_线程池(包含定时任务).md](<cursor_JDK_线程池(包含定时任务).md>)、[JDK版本特性变化.md](<JDK版本特性变化.md>)(15.2 虚拟线程全景、19.2 Scoped Values 全景)、[../spring/Spring Boot.md](<../spring/Spring Boot.md>)。

## 如何读这份文档

- **第一遍(10 分钟,只看表)**:1.4 全景图 → 1.5 关键问题映射 → 2.6 "不用改"清单 → 3.6 差异速查表 → 4.3 ThreadLocal 迁移决策表 → 5.9 迁移检查清单。这六张表就是迁移决策的全部。
- **第二遍(2 小时,对照源码)**:每个结论都标注 `文件:行号`,建议对照本机源码读一遍。排查线上问题时,这份对照会救你。
- 先修知识:线程/线程池基础见 [Java并发.md](<Java并发.md>) 与 [cursor_JDK_线程池(包含定时任务).md](<cursor_JDK_线程池(包含定时任务).md>);本文不重复线程池原理,只讲"换了虚拟线程之后,哪些假设失效"。

# 一、总览

## 1.1 一句话定位

> **虚拟线程不是新 API,而是同一个 `java.lang.Thread` 的第二种实现**:平台线程 1:1 绑定 OS 线程,虚拟线程 M:N 挂载到少量载体线程上——阻塞时把栈卸载到堆、让出载体,就绪时再挂载回来。于是"一请求一线程"这种最易读的写法,重新变成了高吞吐写法。

迁移的第一认知:**这不是"换一种线程池",而是"把线程变便宜了"**。原来花在池化、异步化、上下文传播上的复杂度,大部分可以拆掉;原来依赖"线程很贵"才成立的设计(池复用摊销 ThreadLocal、用池大小限流),必须逐条重审。

## 1.2 设计哲学:为什么 JDK 21 要做这件事

- **Java 1.0 ~ 20 的并发模型**:线程是 OS 线程的薄封装,每线程默认预留 1MB 栈、创建/切换走内核。成本决定了并发上限是"几千个线程",于是行业发明了线程池 + 异步框架(Reactor/CompletableFuture)——**用可读性换吞吐**。
- **Project Loom 的判断**:OS 线程贵,贵在"内核资源";但"等待"根本不需要内核。把"调用栈"从内核线程上拆下来放进堆里,阻塞 = 把栈存起来,就绪 = 找个载体线程恢复栈。**线程回归廉价,阻塞式写法回归主流**。
- **兼容性压倒一切**:虚拟线程就是 `java.lang.Thread` 的子类实现,现有生态(锁、ThreadLocal、调试器)不换 API 就能用——这是它和 Kotlin 协程、Reactor 最大的区别(对比见 5.6)。

## 1.3 两个"线程"到底是什么(JDK 25 术语)

【源码证据】`java.base/java/lang/Thread.java:102-126`(类注释,小标题 "Virtual threads"):

> Virtual threads are typically **user-mode threads** scheduled by the Java runtime rather than the operating system... a single Java virtual machine may support **millions** of virtual threads. Virtual threads are suitable for executing tasks that spend most of the time **blocked**... Virtual threads are **not intended for long running CPU intensive operations**.
> Virtual threads typically employ a small set of platform threads used as **carrier threads**... Code executing in a virtual thread is **not aware of the underlying carrier thread**. The `Thread.currentThread()` method ... will always return the `Thread` object **for the virtual thread**.

| | 平台线程(Platform Thread) | 虚拟线程(Virtual Thread) |
|---|---|---|
| 本质 | `java.lang.Thread`,1:1 包裹 OS 线程 | `VirtualThread extends BaseVirtualThread extends Thread`,`java.base/java/lang/VirtualThread.java:63` |
| 调度者 | 操作系统内核 | JVM 内置调度器(默认 FIFO `ForkJoinPool`,`VirtualThread.java:66,1420`) |
| 栈 | OS 线程栈(默认预留 1MB,`-Xss`) | "延续(continuation)"栈帧存在 **堆** 上,按需增长 |
| 阻塞代价 | 占死一个 OS 线程 | 卸载(unmount),载体线程去跑别的虚拟线程 |
| 数量级 | 数千(受内存/OS 限制) | 百万级(实测 20 万个 160ms 全部启动,见 3.3) |
| 创建方式 | `new Thread()`、`Thread.ofPlatform()` | `Thread.ofVirtual()`、`Thread.startVirtualThread()`、`Executors.newVirtualThreadPerTaskExecutor()`(`java.base/java/util/concurrent/Executors.java:263`) |
| 适用场景 | CPU 密集、需要长驻(如调度器线程) | I/O 密集、短生命周期、高并发阻塞 |

## 1.4 运行机制全景:一次"阻塞"的完整旅程

【白话】虚拟线程的运行只有三个角色:**虚拟线程**(你的任务 + 堆上的栈)、**调度器**(ForkJoinPool,池里是载体线程)、**载体线程**(真正执行字节码的平台线程)。任务看起来"阻塞在 socket.read()"时,实际发生的是:虚拟线程把栈封存进堆 → 释放载体 → 载体被调度器派给下一个就绪的虚拟线程 → 网络事件到达后,虚拟线程带着栈回来重新挂载。

```text
                 ┌─────────────────────────── JVM (JDK 25) ───────────────────────────┐
  百万个任务 ──▶ │  VirtualThread 对象(堆)                                            │
                 │   ├─ state 状态机(NEW/STARTED/RUNNING/PARKED/BLOCKED/...)           │
                 │   ├─ Continuation:栈帧快照,阻塞时挂起在堆                          │
                 │   └─ scheduler 引用 ──▶ DEFAULT_SCHEDULER(静态,ForkJoinPool FIFO)  │
                 │                          │ parallelism = CPU 核数(可调)            │
                 │                          ▼                                          │
                 │   CarrierThread ×N(平台线程,真正跑字节码)                          │
                 │      mount(): 载体设置 currentThread=虚拟线程,开始/继续执行         │
                 │      阻塞点:Continuation.yield() 成功 → unmount() → 载体空闲        │
                 │      yield 失败(native 帧/类初始化)→ PINNED,占住载体(见 3.4)      │
                 └─────────────────────────────────────────────────────────────────────┘
```

【源码证据】`java.base/java/lang/VirtualThread.java:82-122`(状态机注释,摘录):

```java
/*
 * Virtual thread state transitions:
 *
 *      NEW -> STARTED         // Thread.start, schedule to run
 *  STARTED -> RUNNING         // first run
 *  RUNNING -> TERMINATED      // done
 *
 *  RUNNING -> PARKING         // Thread parking with LockSupport.park
 *  PARKING -> PARKED          // cont.yield successful, parked indefinitely
 *  PARKING -> PINNED          // cont.yield failed, parked indefinitely on carrier
 *   PARKED -> UNPARKED        // unparked, may be scheduled to continue
 *   PINNED -> RUNNING         // unparked, continue execution on same carrier
 *
 *       RUNNING -> TIMED_PARKING   // Thread parking with LockSupport.parkNanos
 *  TIMED_PARKING -> TIMED_PINNED    // cont.yield failed, timed-parked on carrier
 *
 *   RUNNING -> BLOCKING       // blocking on monitor enter
 *  BLOCKING -> BLOCKED        // blocked on monitor enter
 *   BLOCKED -> UNBLOCKED      // unblocked, may be scheduled to continue
 *
 *   RUNNING -> WAITING        // transitional state during wait on monitor
 *   WAITING -> WAIT           // waiting on monitor
 *      WAIT -> BLOCKED        // notified, waiting to be unblocked by monitor owner
 *
 *  RUNNING -> YIELDING        // Thread.yield
 * YIELDING -> YIELDED         // cont.yield successful, may be scheduled to continue
 */
```

状态常量在 `:123-134`,`PINNED=5` 意为"已挂载"——**钉住就是"想卸载而卸载不掉"**。注意 `BLOCKING/BLOCKED/WAIT` 三组监视器状态在 JDK 21 里会连累载体(JEP 444 的钉住),24 起(JEP 491)改为正常让出。

## 1.5 关键问题映射(迁移视角)

| 你关心的问题 | 答案位置 |
|---|---|
| 哪些代码一行都不用改? | 2.6 "不用改"清单 |
| `synchronized` 还要换成 `ReentrantLock` 吗? | 3.4(JDK 24 起不用换了) |
| ThreadLocal / InheritableThreadLocal 怎么办? | 第四章(全文重点) |
| MDC / SecurityContext / RequestContextHolder 会不会丢? | 4.5 + 5.4 |
| Spring Boot 里一个开关够吗?开关动了哪些组件? | 5.1 作用点总表 |
| Tomcat 的 maxThreads 还管用吗? | 5.2 |
| `@Async` / `@Scheduled` 迁移后有什么坑? | 5.3 |
| 并发上限怎么控(原来靠池大小)? | 5.7 Semaphore |
| 怎么观测虚拟线程(线程 dump、指标)? | 3.5 + 5.8 |
| 钉住(pinning)到底还有没有? | 3.4(21 vs 25 实测) |
| 哪些场景**不该**用虚拟线程? | 3.2 + 5.5 + 第六章 |

## 1.6 版本演进:迁移前必须知道的时间线

> 完整演进叙事见 [JDK版本特性变化.md](<JDK版本特性变化.md>) 15.2,这里只列影响迁移决策的四站:

| 版本 | 变化 | 对迁移的意义 | JEP |
|---|---|---|---|
| 19 → 20(2022~2023) | 虚拟线程两次预览 | 历史包袱,无需关心 | 425 / 436 |
| **21**(2023-09,LTS) | 转正;ThreadLocal **恒支持**(去掉了预览的"无 ThreadLocal 模式");恒为守护线程;优先级固定 NORM | 本文所有"不变"结论的基线 | 444 |
| **24**(2025-03) | **`synchronized` 不再钉住虚拟线程**;`jdk.tracePinnedThreads` 移除;新增 `jdk.management.VirtualThreadSchedulerMXBean` | 迁移评估的**分水岭**:21~23 与 24+ 的迁移清单不同 | 491 |
| **25**(2025-09,LTS) | **`ScopedValue` 转正**;结构化并发第 5 次预览 | ThreadLocal 的官方"继任者"可用,但 Spring 生态尚未原生集成(见 4.4) | 506 / 505 |

【源码说明】本文全部基于 JDK 25 源码;JDK 21 的对照源码取自 `D:\soft\jdk\jdk-21\lib\src.zip`(差异点在 3.4 明确标注)。

## 1.7 全文章节地图

```text
一、总览                    ← 你在这里
二、共同点:哪些代码不用改   ← 兼容面(安心区)
三、差异点:换了什么(源码级) ← 调度/不可配置项/内存/钉住/观测
四、ThreadLocal 迁移专题     ← 用户点名重点:机制不变、前提变了、ScopedValue、Spring 上下文
五、Spring Boot 4.1.1 迁移实战 ← 开关、作用点、Tomcat/@Async/@Scheduled、上下文传播、检查清单
六、反模式与陷阱 Top 10
七、演进展望:结构化并发与 ScopedValue 生态
附录 A  JDK 25 虚拟线程属性/JFR/命令速查
附录 B  Spring Boot 4.1 虚拟线程配置速查
附录 C  本文实验代码与实测输出
```

# 二、共同点:哪些代码一行都不用改

> 本章是"安心区"。结论先行:**除线程池本身外,常规业务代码、并发工具、阻塞 I/O 代码、ThreadLocal 用法全部原样可用**。每条结论都给出源码或实测依据。

## 2.1 还是同一个 `java.lang.Thread`

虚拟线程不是新类型,`isVirtual()` 的实现就是一行 `instanceof`:

【源码证据】`java.base/java/lang/Thread.java:1399-1401`:

```java
public final boolean isVirtual() {
    return (this instanceof BaseVirtualThread);
}
```

因此所有以 `Thread` 为参数/返回值的既有 API 照常工作:`Thread.currentThread()`(永远返回虚拟线程自己,不是载体线程,`Thread.java:115-118`)、`Runnable`/`Callable`、`UncaughtExceptionHandler`、`interrupt()`/`isInterrupted()`、`join()`、`sleep()`、`getId()/threadId()`。三种创建入口:

【源码证据】`java.base/java/lang/Thread.java:791`(ofPlatform)、`:811`(ofVirtual)、`:1384`(startVirtualThread);`java.base/java/util/concurrent/Executors.java:248,263`:

```java
Thread v1 = Thread.ofVirtual().name("io-", 0).start(task);  // builder,可命名/设 factory
Thread v2 = Thread.startVirtualThread(task);                // 快捷方式
ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor();  // 每任务一个虚拟线程
ExecutorService ex2 = Executors.newThreadPerTaskExecutor(factory); // 通用形态,虚拟只是 factory 的一种
```

> ⚠️ 唯一要"改"的创建姿势:`new Thread(runnable)` 和 `new ThreadPoolExecutor(...)` 造出来的仍是平台线程。迁移的本质动作 = **把"池 + 复用"换成"每任务一个虚拟线程"**(怎么换见第五章)。

## 2.2 阻塞 API 自动让出:一张"支持清单"

【白话】虚拟线程的性能前提是:**在虚拟线程里调用 JDK 的阻塞 API 时,JDK 能感知并让出载体**。这些 API 在 21 已全面覆盖,业务代码一行不用改:

| 类别 | API | 让出机制 |
|---|---|---|
| 网络 I/O | `java.net.Socket`、`ServerSocket`、NIO `SocketChannel`/`Selector`、`HttpClient`(阻塞式用法) | 底层改造为可让出的阻塞(`Continuation` + 轮询器) |
| 等待/休眠 | `Thread.sleep`、`Thread.join`、`LockSupport.park*` | `VirtualThread.park()/parkNanos()`(`VirtualThread.java:727,764`) |
| 并发工具 | `ReentrantLock`、`Semaphore`、`CountDownLatch`、`BlockingQueue` 的取放、`Future.get`、`CompletableFuture.join` | 全部建立在 `LockSupport` 之上,自动让出 |
| 监视器 | `synchronized` 竞争阻塞、`Object.wait()` | **JDK 24 起不再钉住载体**(JEP 491,见 3.4) |
| 其他 | `InetAddress` 解析、`java.io` 文件流(通过调度器补偿,见 JEP 444)、`Process.waitFor` | 支持或补偿 |

【源码证据】让出的统一入口是 `yieldContinuation()`(`java.base/java/lang/VirtualThread.java:529-537`),核心一行 `Continuation.yield(VTHREAD_SCOPE)`:

```java
@Hidden
private boolean yieldContinuation() {
    notifyJvmtiUnmount(/*hide*/true);
    try {
        return Continuation.yield(VTHREAD_SCOPE);   // 栈留在堆上,载体线程自由
    } finally {
        notifyJvmtiMount(/*hide*/false);
    }
}
```

返回 `true` 即卸载成功;返回 `false`(如当前处于 native 帧)则走 `parkOnCarrierThread()`(`:806-827`)占住载体等待——那正是 3.4 的"钉住"。

**含义**:原代码里 `socket.getInputStream().read()`、`queue.take()`、`latch.await()`、`sleep()` 全部照旧——这正是虚拟线程"零成本迁移"的底气。

## 2.3 中断、异常、清理语义一致

- `interrupt()` 一个阻塞中的虚拟线程:与平台线程语义一致,阻塞点抛 `InterruptedException` 或置位中断标志(`VirtualThread.java` 的 park 族均检查中断)。
- `UncaughtExceptionHandler`、线程级异常传播不变;`ExecutorService` 的 try-with-resources 关闭语义(`Executors.java:263` 返回的 service)不变。
- `try { ... } finally { lock.unlock(); }`、`ThreadLocal.remove()` 等清理习惯照旧有效。

【源码证据】"中断即醒"的语义在 park 的第一行就体现(`java.base/java/lang/VirtualThread.java:727-732`):

```java
void park() {
    assert Thread.currentThread() == this;

    // complete immediately if parking permit available or interrupted
    if (getAndSetParkPermit(false) || interrupted)
        return;
    ...
```

## 2.4 ThreadLocal / InheritableThreadLocal 机制不变(但前提变了)

【白话】**每个虚拟线程依然有自己独立的 `threadLocals` 和 `inheritableThreadLocals` 两个 Map**(和平台线程一样的 `ThreadLocalMap`)。`ThreadLocal.get()` 在虚拟线程里读到的就是本虚拟线程的值——请求隔离语义完全不变。变的不是机制,是**经济前提**:池化时代一个 ThreadLocal set 一次服务几千个任务,虚拟线程"一任务一线程"后每个请求都要重新 set 一次。这是第四章的主题,此处先给机制结论:

【源码证据】三段源码对应"机制不变"的三块拼图:

① 平台线程构造器(`java.base/java/lang/Thread.java:713-727`):

```java
// thread locals
if (!attached) {
    if ((characteristics & NO_INHERIT_THREAD_LOCALS) == 0) {
        ThreadLocal.ThreadLocalMap parentMap = parent.inheritableThreadLocals;
        if (parentMap != null && parentMap.size() > 0) {
            this.inheritableThreadLocals = ThreadLocal.createInheritedMap(parentMap);
        }
        if (VM.isBooted()) {
            this.contextClassLoader = parent.getContextClassLoader();
        }
    }
    ...
}
```

② **虚拟线程构造器同样复制**(`Thread.java:745-756`,ITL 与 CCL 继承对虚拟线程一视同仁):

```java
// thread locals
if ((characteristics & NO_INHERIT_THREAD_LOCALS) == 0) {
    Thread parent = currentThread();
    ThreadLocal.ThreadLocalMap parentMap = parent.inheritableThreadLocals;
    if (parentMap != null && parentMap.size() > 0) {
        this.inheritableThreadLocals = ThreadLocal.createInheritedMap(parentMap);
    }
    this.contextClassLoader = parent.getContextClassLoader();
}
```

③ `ThreadLocal` 的 `get()/set()` 就是 `getMap(t)` 的普通 Map 操作(`java.base/java/lang/ThreadLocal.java:183-194,228-233`),t 是 `Thread.currentThread()`,虚拟线程照常命中自己的 Map:

```java
private T get(Thread t) {
    ThreadLocalMap map = getMap(t);
    if (map != null) {
        ThreadLocalMap.Entry e = map.getEntry(this);
        if (e != null) {
            ...
            return result;
        }
    }
    return setInitialValue(t);
}

public void set(T value) {
    set(Thread.currentThread(), value);   // map 挂在 Thread 对象上,与线程种类无关
    ...
}
```

【实测】(JDK 25,附录 C `BehaviorDiff`):

```text
[3] 子虚拟线程: TL=null (ThreadLocal 不继承), ITL=main-itl (ITL 按创建线程继承)
[3] inheritInheritableThreadLocals(false): ITL=null
[3] executor 任务: ITL=main-itl (父线程=提交任务的线程)
```

详细展开见 4.1~4.3。

## 2.5 并发工具与诊断工具兼容

- `java.util.concurrent` 全家桶(锁、并发集合、同步器、CompletableFuture)无需任何适配——它们都构建在 `LockSupport` 与 `synchronized` 上,而这两者在虚拟线程上都被正确处理。
- 诊断:JDK 21 起线程 dump 换用 `jcmd <pid> Thread.dump_to_file -format=json dump.json`(可同时含平台线程与虚拟线程);JFR 事件体系对虚拟线程有专属事件(见 3.5)。

## 2.6 本章小结:"不用改"清单

| 代码形态 | 结论 |
|---|---|
| 业务代码中的阻塞调用(sleep/IO/锁/队列) | ✅ 一行不改 |
| `synchronized` 方法与块 | ✅ 24 起无需换 `ReentrantLock`(21~23 的高频阻塞场景才需换) |
| `ThreadLocal` 的请求隔离用法 | ✅ 语义不变(性能前提变了,见第四章) |
| `java.util.concurrent` 工具 | ✅ 不变 |
| Spring MVC 请求处理、事务、MDC(请求线程内) | ✅ 不变(每个请求一个虚拟线程,隔离依旧) |
| **线程池的创建与参数**(`new ThreadPoolExecutor`、`Executors.newFixedThreadPool`) | ❌ 要换(第一章本质动作) |
| **依赖池大小做的并发控制** | ❌ 要换(5.7) |
| **依赖线程复用做的 ThreadLocal 摊销/缓存** | ❌ 要换(第四章) |

# 三、差异点:换了什么(源码级)

## 3.1 调度器:OS 内核 → JVM 内置 FIFO ForkJoinPool

【白话】平台线程由内核抢占式调度;虚拟线程由 JVM 里一个**静态的、全局唯一的** `ForkJoinPool` 调度——它以 **FIFO 模式**运行(与 `commonPool` 的 LIFO 相对,专为大吞吐短任务设计),池里的工作线程就是你在线程 dump 里看到的 `ForkJoinPool-1-worker-N`——即**载体线程**。默认并行度 = CPU 核数。注意两点:**没有时间片抢占**(JEP 444,一个不阻塞的 CPU 密集虚拟线程会占住载体,其他虚拟线程只能等它让出),以及**调度器可以整体替换**(`jdk.virtualThreadScheduler.parallelism` 等属性,或 `Thread.Builder.virtualThread(scheduler)`)。

【源码证据】`java.base/java/lang/VirtualThread.java:1420-1446`(默认调度器构造):

```java
private static ForkJoinPool createDefaultScheduler() {
    ForkJoinWorkerThreadFactory factory = pool -> new CarrierThread(pool);
    ...
    parallelism = Runtime.getRuntime().availableProcessors();          // :1429,可被属性覆盖
    maxPoolSize   = Integer.max(parallelism, 256);                     // :1435
    minRunnable   = Integer.max(parallelism / 2, 1);                   // :1440
    boolean asyncMode = true; // FIFO                                  // :1443
    return new ForkJoinPool(parallelism, factory, handler, asyncMode, ...);
}
```

调参属性:`jdk.virtualThreadScheduler.parallelism` / `.maxPoolSize` / `.minRunnable`(`:1423-1425`)。虚拟线程因 I/O 阻塞卸载时**不占载体名额**;但文件 I/O、`Object.wait()` 这类"宿主仍需阻塞"的场景,调度器会**临时扩容补偿**(JEP 444 术语 compensating),这正是"阻塞不拖垮吞吐"的实现细节。

**迁移含义**:
- 载体线程 ≈ CPU 核数,意味着**虚拟线程的 CPU 密集部分加起来也只有核数份并行度**——CPU 密集任务仍应留在平台线程池(见 5.5);
- 看到 `ForkJoinPool-1-worker-3` 不必惊慌:那是载体,虚拟线程名在 dump JSON 里以 `VirtualThread[#36]/runnable@ForkJoinPool-1-worker-1` 形式出现(实测见 4.6)。

## 3.2 不可配置项:daemon、priority、ThreadGroup、name

【白话】平台线程有几个"可有可无"的配置项,虚拟线程上要么恒定、要么调用即被忽略——**迁移时扫一遍代码,把对这些 API 的调用直接删掉**:

【源码证据】(`java.base/java/lang/Thread.java`):

```java
// :2040-2047  setDaemon:虚拟线程传 false 直接抛异常
public final void setDaemon(boolean on) {
    if (isVirtual() && !on)
        throw new IllegalArgumentException("'false' not legal for virtual threads");
    if (isAlive())
        throw new IllegalThreadStateException();
    if (!isVirtual())
        daemon(on);
}

// :2061-2067  isDaemon:虚拟线程恒 true
public final boolean isDaemon() {
    if (isVirtual()) {
        return true;
    } else {
        return holder.daemon;
    }
}

// :1703-1710  setPriority:范围校验通过后,虚拟线程被静默忽略
public final void setPriority(int newPriority) {
    if (newPriority > MAX_PRIORITY || newPriority < MIN_PRIORITY) {
        throw new IllegalArgumentException();
    }
    if (!isVirtual()) {
        priority(newPriority);          // 虚拟线程:这一行永远不会执行
    }
}

// :1731-1737  getPriority:虚拟线程恒 NORM_PRIORITY(5)
public final int getPriority() {
    if (isVirtual()) {
        return Thread.NORM_PRIORITY;
    } else {
        return holder.priority;
    }
}

// :1785-1791  getThreadGroup:虚拟线程返回专用组 "VirtualThreads";线程终止后返回 null
public final ThreadGroup getThreadGroup() {
    if (isTerminated()) {
        return null;
    } else {
        return isVirtual() ? virtualThreadGroup() : holder.group;
    }
}
```

【实测】(JDK 25,附录 C `BehaviorDiff`):

```text
[1] virtual: isVirtual=true, isDaemon=true, priority=5, threadGroup=VirtualThreads, 终止后 getThreadGroup()=null
[1] startVirtualThread 默认 name=[] (空串)
[2] v.setDaemon(false) -> IllegalArgumentException: 'false' not legal for virtual threads
[2] v.setPriority(MAX) 不抛异常, getPriority()=5 (被忽略)
```

| API | 平台线程 | 虚拟线程 | 迁移动作 |
|---|---|---|---|
| `setDaemon(false)` | 合法 | **IllegalArgumentException** | 删除调用;若原意是"别挡 JVM 退出"——虚拟线程恒 daemon,天然满足 |
| `setPriority(x)` | 生效 | 静默忽略,恒 5 | 删除调用(想给 I/O 优先级?虚拟线程模型里无此概念) |
| `getThreadGroup()`/`activeCount()`/`enumerate()` | 常用 | 返回特殊组;终止后为 null;**百万线程下枚举无意义** | 改用 MXBean/线程 dump(3.5) |
| 默认 name | `Thread-0` | **空串** | 生产务必用 builder `.name(prefix, 0)` 命名(观测依赖) |
| `setContextClassLoader()` | 合法 | 同样合法(`:2136-2138`,无限制) | 不用改(但注意 4.3 的 CCL 继承) |

> 另外:`Thread.suspend()/resume()/stop()` 本就废弃,虚拟线程不支持;`getStackTrace()` 返回的是**虚拟线程自己的栈**(与载体无关)。

## 3.3 内存与规模:堆上栈 vs 线程栈(实测)

【白话】平台线程的栈是 OS 分配的内存块(JDK 默认预留 1MB,`-Xss` 可调),线程数直接吃内存与内核资源;虚拟线程的栈是堆上的 continuation 对象,**初始只有几百字节,按调用深度增长**,死掉即随对象被 GC。差异是数量级的:

【实测】(JDK 25,附录 C `ScaleTest`,Windows,JDK 25.0.1):

```text
[virtual] 200000 个线程全部启动并存活, 耗时 160 ms
[platform] 成功创建 20000 个线程 (再往上加会逼近 OS 线程上限/内存)
```

200k 平台线程按默认 1MB 预留就是 **200GB 虚拟地址空间**加相应内核对象,而 200k 虚拟线程只是堆上多出约几十 MB 的小对象——**这就是"一请求一线程"重新可行的物质基础**。

【源码证据】"栈在堆上"不是修辞,是字段声明——虚拟线程的栈就是 `Continuation` 这个普通堆对象(`java.base/java/lang/VirtualThread.java:74-77`):

```java
// scheduler and continuation
private final Executor scheduler;      // 挂在哪个调度器上
private final Continuation cont;       // 栈帧快照本身,一个可挂起/恢复的堆对象
private final Runnable runContinuation; // 提交给调度器的"继续执行"任务
```

**迁移含义**:
- 不再需要 `-Xss` 调优;`Thread.Builder.platform().stackSize(...)` 的思路整体作废;
- 内存压力转移到了堆上:**百万级虚拟线程 × 每个 set 的大对象 ThreadLocal = 新的 OOM 形态**(见 4.1);
- GC 视角:虚拟线程对象是一次性短命对象,分代 GC(ZGC/G1)的老年代压力很小;`-XX:+UseZGC` 等无需因虚拟线程改变。

## 3.4 钉住(pinning):JDK 24 前后的最大差异点

【白话】"钉住" = 虚拟线程**想卸载却卸载不掉**,只能占着载体线程睡大觉。钉住本身不产生错误结果,但**高频或长时间钉住会把载体(≈CPU 核数)全部占满**,吞吐退化到"核数个真线程"的水平——这是 21 时代迁移失败案例的第一元凶。

【源码证据】`VirtualThread.java:92-94`(状态机注释):`PARKING → PINNED // cont.yield failed, parked indefinitely on carrier`。卸载失败的兜底路径 `parkOnCarrierThread()`(`:806-827`)——直接 park 载体线程,并发出 JFR 事件:

```java
private void parkOnCarrierThread(boolean timed, long nanos) {
    assert state() == RUNNING;

    setState(timed ? TIMED_PINNED : PINNED);   // 进入"钉住"状态
    try {
        if (!parkPermit) {
            if (!timed) {
                U.park(false, 0);              // 载体线程(平台线程)真的睡了
            } else if (nanos > 0) {
                U.park(false, nanos);
            }
        }
    } finally {
        setState(RUNNING);
    }

    // consume parking permit
    setParkPermit(false);

    // JFR jdk.VirtualThreadPinned event
    postPinnedEvent("LockSupport.park");       // :826 —— 观测钉住就靠它
}
```

**JDK 21~23 的钉住场景**(JEP 444):① `synchronized` 块/方法内阻塞(等锁、wait、I/O);② native 方法/FFM 栈帧内阻塞。当时的迁移清单因此有一条"高频阻塞的 `synchronized` 改 `ReentrantLock`",并靠 `-Djdk.tracePinnedThreads=full` 诊断。

**JDK 24 起(JEP 491)局面彻底改变**:监视器实现重写,JVM 按虚拟线程(而非载体)跟踪监视器归属,`synchronized` 竞争与 `Object.wait()` 全部可让出;`jdk.tracePinnedThreads` 因失去意义被**移除**(设了也无效果)。**剩余钉住场景只有三类**:
1. **native 帧**(JNI 原生方法、FFM)内回调 Java 并阻塞——native 栈无法搬运;
2. 符号解析期间的类加载(native 帧在栈上);
3. 类初始化器(`<clinit>`)内的阻塞,或等待他人初始化类。

【实测】同样一段"在 `synchronized` 块内 `LockSupport.parkNanos(300ms)` + `MON.wait(300ms)`"的代码(附录 C `PinMonitor`):

```text
JDK 21:jdk.VirtualThreadPinned { duration = 297 ms,
    eventThread = "park-in-sync" (virtual),
    stackTrace = [ LockSupport.parkNanos → VirtualThread.parkNanos → parkOnCarrierThread ] }
JDK 25:(零事件)
```

**迁移含义**:
- 在 **JDK 25** 上,把"换锁"从迁移清单里**划掉**——按 JEP 491 的说法,遵循《Java 并发实践》的老建议即可:`synchronized` 求便利,`j.u.c.locks` 求灵活;
- 唯一要排查的钉住源是**封装了 JNI 的库**(老加密机 SDK、部分 JDBC 驱动的 native 层、嵌入式文件库等):用 JFR `jdk.VirtualThreadPinned`(默认阈值 20ms,建议压测时调到 10ms)扫一遍流量;
- 21~23 升 24+ 时,记得**删除** `jdk.tracePinnedThreads` 配置。

## 3.5 观测:线程 dump、JFR、MXBean

| 工具 | 用法 | 备注 |
|---|---|---|
| 线程 dump | `jcmd <pid> Thread.dump_to_file -format=json dump.json` | 唯一能含虚拟线程的 dump 方式;`jstack` 看不到虚拟线程 |
| JFR 事件 | `jdk.VirtualThreadStart/End`(默认关闭)、`jdk.VirtualThreadPinned`(默认阈值 20ms)、`jdk.VirtualThreadSubmitFailed` | 24 起 Pinned 事件增加钉住原因与载体标识 |
| MXBean | `jdk.management.VirtualThreadSchedulerMXBean`(JDK 24+):`getParallelism/getPoolSize/getMountedVirtualThreadCount/getQueuedVirtualThreadCount` | Spring Boot actuator 已封装进 `/actuator/info`(见 5.8) |
| 系统属性诊断 | `-Djdk.traceVirtualThreadLocals=true` | **JDK 25 新增**,虚拟线程上 set ThreadLocal 即打印调用栈(4.6) |

虚拟线程在 JSON dump 里的名字形如 `VirtualThread[#36]/runnable@ForkJoinPool-1-worker-1`:`#36` 是线程 id,`@` 后是当前载体。**没有名字的虚拟线程排查时是灾难**,所以 3.2 强调用 builder 统一命名。

## 3.6 本章小结:差异速查表

| 维度 | 平台线程 | 虚拟线程(JDK 25) | 迁移动作 |
|---|---|---|---|
| 调度 | OS 内核抢占 | JVM FIFO ForkJoinPool,载体≈核数,无时间片抢占 | CPU 密集留在平台线程池 |
| daemon | 默认 false | 恒 true,`setDaemon(false)` 抛异常 | 删除相关调用 |
| priority | 1~10 可调 | 恒 5,`setPriority` 被忽略 | 删除相关调用 |
| 栈 | OS 线程栈,1MB 预留 | 堆上 continuation,按需增长 | 丢掉 `-Xss` 思维;盯堆上 ThreadLocal |
| `synchronized` | — | 24 起不钉住 | 不必换锁;native 钉住用 JFR 排查 |
| 数量 | 数千 | 百万级(实测 20 万/160ms) | 并发上限改用 Semaphore(5.7) |
| dump | `jstack`(仅平台线程) | `jcmd Thread.dump_to_file -format=json` | 运维脚本、监控模板要换 |
| 复用 | 池化复用 | 一次性,禁止池化 | 换 per-task 模型 |

# 四、ThreadLocal 迁移专题(重点)

> 你的原话:"有一些是基于 ThreadLocal 的信息需要切换"。本章回答三件事:机制上**什么都没变**(2.4 已证);**前提变了**(4.1);**该怎么选**(4.3 决策表 + 4.4 ScopedValue + 4.5 Spring 上下文清单)。

## 4.1 前提变了:池化摊销消失,成本模型反转

【白话】ThreadLocal 本身的开销有两部分:**每线程一份 Map 存储**(不变),和**值本身的创建/复制**(随用法而变)。平台线程池时代,一个 worker 线程活几年,set 一次服务成千上万请求——摊销后近乎免费。虚拟线程"一任务一线程"后,**每个请求都要从零初始化一遍**,同一份数据被复制 N 份:

| 模式 | ThreadLocal Map 数量 | 大对象副本数 | 泄漏风险 |
|---|---|---|---|
| 200 线程池 × 10 万请求 | 200 份(复用摊销) | ≤200 份 | 池线程长存,忘 remove 会**跨请求串值** |
| 10 万虚拟线程(一请求一线程) | 10 万份(一次性) | 10 万份,随请求销毁 | 串值风险消失(线程即请求生命周期),但**内存峰值放大** |

由此得出三条铁律:

1. **`SimpleDateFormat` 这类"每线程一个可变实例"的 ThreadLocal 缓存,直接删**——改 `DateTimeFormatter`(不可变)或方法局部变量;
2. **大对象(几 MB 的缓冲、解析器上下文)不能 set 进虚拟线程的 ThreadLocal**——百万请求并发时就是百万份大对象;需要复用就放**对象池**(如 Netty 的 `Recycler`、Apache Pool2),池是**资源**的池,不是线程的池;
3. **请求级小上下文(userId、traceId、Locale)保持 ThreadLocal 或迁 ScopedValue**——这类几十字节的值复制成本可忽略,语义反而比池化时代更干净。

【源码证据】JDK 官方在 `ThreadLocal` 旁埋了一个迁移期探针(JDK 25):`set()` 尾部对虚拟线程挂了打印钩子,开关读取 `jdk.traceVirtualThreadLocals` 属性——专门帮你定位**哪些库在虚拟线程上高频/大对象地用 ThreadLocal**(用法见 4.6):

```java
// java.base/java/lang/ThreadLocal.java:228-233 —— set() 尾部挂了虚拟线程探针
public void set(T value) {
    set(Thread.currentThread(), value);
    if (TRACE_VTHREAD_LOCALS && Thread.currentThread().isVirtual()) {
        printStackTrace();                       // 只对虚拟线程生效
    }
}

// ThreadLocal.java:789-797 —— 探针开关的读取逻辑
private static boolean traceVirtualThreadLocals() {
    String propValue = System.getProperty("jdk.traceVirtualThreadLocals");
    return (propValue != null)
            && (propValue.isEmpty() || Boolean.parseBoolean(propValue));
}
```

(`:210-215` 的 `setInitialValue()` 也挂了同一个钩子,覆盖 `withInitial` 的惰性初始化路径。)

## 4.2 InheritableThreadLocal:继承时机没变,语义却更干净了

【白话】ITL 的继承发生在 **`Thread` 构造时**(不是 start 时),父线程 = **创建线程对象**的那个线程。三条推论:

- 虚拟线程同样继承(`Thread.java:741-756`),所以"主线程 set ITL → 起虚拟线程读"是通的;
- `Executors.newVirtualThreadPerTaskExecutor()` 的任务线程由**提交线程**创建,继承的是提交者的 ITL(实测 [3]);
- 反而是老代码里的隐患消除了:池线程长存导致的 **ITL 残留串值**问题,在"一次性线程"上不存在。

但要警惕反向陷阱:**池化迁移到虚拟线程后,原来"set 一次终身有效"的 ITL 用法会退化成"每次请求都从提交者复制一份"**;如果提交者(如 Tomcat 工作线程)上的 ITL 是陈旧值,每个虚拟线程都会继承到陈旧值——比原来更隐蔽。所以 ITL 的迁移动作是:**要么删,要么换 ScopedValue**(4.4),至少要在 builder 上显式断开继承:

【源码证据】`java.base/java/lang/Thread.java:880-888`(Builder 接口,javadoc 明说"默认继承"):

```java
/**
 * Sets whether the thread inherits the initial values of {@linkplain
 * InheritableThreadLocal inheritable-thread-local} variables from the
 * constructing thread. The default is to inherit.
 *
 * @param inherit {@code true} to inherit, {@code false} to not inherit
 * @return this builder
 */
Builder inheritInheritableThreadLocals(boolean inherit);    // :888
```

(跳过继承的实现就是构造器里那个 `NO_INHERIT_THREAD_LOCALS` 特征位,`Thread.java:649,715,746`——源码见 2.4 ①②。)

```java
Thread v = Thread.ofVirtual()
        .inheritInheritableThreadLocals(false)   // 显式断开 ITL 继承
        .start(task);
```

> 顺带:`StructuredTaskScope` 的子线程由框架统一按需构造,`ScopedValue` 绑定走**绑定链共享**(一次指针复制,见 4.4),不走 ITL 复制——这是 ScopedValue 比 ITL 快两个数量级的根源(JEP 506)。此前实验也确认过:普通 `new Thread` 读不到 ScopedValue 绑定(实测 [4]),详见 [JDK版本特性变化.md](<JDK版本特性变化.md>) 19.2。

## 4.3 四类 ThreadLocal 的迁移决策表

把你代码里的 ThreadLocal 按"装了什么"分成四类,逐类给动作:

| 类别 | 典型代表 | 虚拟线程上的问题 | 迁移动作 |
|---|---|---|---|
| ① 请求上下文(小对象) | `RequestContextHolder`、`SecurityContextHolder`、SLF4J `MDC`、traceId | 机制不变,一请求一线程天然隔离;**只在"换线程执行"(@Async/消息消费/手动线程池)时才需要传播** | 请求线程内:不改;跨线程:TaskDecorator / context-propagation(4.5、5.4) |
| ② 可变实例缓存 | `SimpleDateFormat`、`StringBuilder` 复用、编解码器 | 每请求重建,失去意义且放大内存 | **删**:换不可变 API(`DateTimeFormatter`)或局部变量 |
| ③ 重对象/缓冲池化 | 大字节数组缓冲、解析上下文 | 百万请求 = 百万份大对象,OOM 高发 | 改**对象池**(Pool2/`Recycler`),池容量=真资源上限 |
| ④ 框架隐式线程状态 | Spring 事务(`TransactionSynchronizationManager`/`ConnectionHolder`)、`LocaleContextHolder` | 请求线程内不变;**@Async 换线程后事务上下文不会跟过去**(历来如此,虚拟线程不改变这一点) | 不改;跨线程按 Spring 既有方案显式传播 |

**为什么事务/MDC 在"请求线程"上不用改**:Spring 事务把 `Connection` 绑定到当前线程的 ThreadLocal,而虚拟线程场景下"一个请求从进 controller 到出 mapper 全程在同一个虚拟线程上"(Tomcat 每请求一个虚拟线程,5.2),绑定/解绑生命周期与请求重合——和池线程时代的"worker 处理完整请求"同构。**会出问题的永远只有"中途换线程"的地方**。

## 4.4 ScopedValue(JEP 506,JDK 25 转正):ThreadLocal 的官方继任者

【白话】`ScopedValue` 解决的是同一件事("不经方法参数把数据带给深处被调方"),但把生命周期从"线程"改为**"作用域"**:绑定在 `where(...).run(...)` 花括号内有效,出来即失效,想重绑定只能嵌套(不可 set 覆盖)。官方在 javadoc 里点名了 ThreadLocal 的三大问题:

【源码证据】`java.base/java/lang/ScopedValue.java:180-190`(javadoc 原文):

> ① `ThreadLocal` does not prevent code in a faraway callee from **setting** a new value(远处的代码可以偷偷改值);② A `ThreadLocal` has an **unbounded lifetime**...(方法结束后值还在,除非记得 remove);③ `InheritableThreadLocal` inheritance is **expensive** — the map ... must be **copied** when creating each child thread(继承即整表复制)。

【源码证据】绑定机制的实现,`java.base/java/lang/ScopedValue.java:461-488`——快照链表挂到线程字段上,进作用域挂链、出作用域恢复前链,这就是"解绑自动完成"和"STS 子线程共享绑定只复制一个指针"的机制根源:

```java
public void run(Runnable op) {                        // :461
    Objects.requireNonNull(op);
    Cache.invalidate(bitmask);
    var prevSnapshot = scopedValueBindings();
    var newSnapshot = new Snapshot(this, prevSnapshot);  // 新绑定 = 链表头插一个快照
    runWith(newSnapshot, op);
}

@Hidden
@ForceInline
private void runWith(Snapshot newSnapshot, Runnable op) {  // :478
    try {
        Thread.setScopedValueBindings(newSnapshot);      // 挂到当前线程的绑定链字段
        Thread.ensureMaterializedForStackWalk(newSnapshot);
        ScopedValueContainer.run(op);                    // 执行业务
    } finally {
        Reference.reachabilityFence(newSnapshot);
        Thread.setScopedValueBindings(newSnapshot.prev); // 出作用域:恢复前一条链
        Cache.invalidate(bitmask);
    }
}
```

(注释同时披露:`runWith` 被 VM 识别为特殊方法(`JVM_FindScopedValueBindings()`),这是绑定能被 JVM 原生继承给 StructuredTaskScope 子任务的前提。)

API 一览(实测 [4]):

```java
private static final ScopedValue<String> REQUEST_USER = ScopedValue.newInstance();

ScopedValue.where(REQUEST_USER, "u-42")
           .where(LOGIN_ID, "admin")            // Carrier 支持链式多绑定(ScopedValue.java:313,524)
           .run(() -> service.handle());        // 作用域内 REQUEST_USER.get()=="u-42"

REQUEST_USER.isBound();    // 作用域外 false
REQUEST_USER.get();        // 作用域外抛 NoSuchElementException
REQUEST_USER.orElse("x");  // 兜底读取(ScopedValue.java:549,580,613,461)
```

【实测】(JDK 25,附录 C `BehaviorDiff`):

```text
[4] 绑定内: SV=req-42, isBound=true
[4] 其他线程读 SV -> NoSuchElementException (绑定不跨线程继承)
[4] 作用域外: isBound=false, orElse=default
[4] 作用域外 SV.get() -> NoSuchElementException
```

与 ThreadLocal 的关键差异(迁移对照):

| | ThreadLocal | ScopedValue(JDK 25) |
|---|---|---|
| 生命周期 | 线程级,需手动 remove | 作用域级,run/call 返回自动解绑 |
| 可变性 | 随时 set 覆盖 | **不可变**;重新绑定 = 新嵌套作用域 |
| 跨线程 | ITL 整表复制 | `StructuredTaskScope` 子线程共享绑定链(近似指针复制,`ScopedValue.java:192-204`);**普通 `new Thread`/executor 线程读不到** |
| 性能小字 | Map 哈希查找 | get() 首次线性搜索后缓存(默认 16 槽,`java.lang.ScopedValue.cacheSize` 可调 2~16);阻塞时默认保留缓存(`jdk.preserveScopedValueCache=true`,`ScopedValue.java:206-236`) |
| 生态现状 | 全生态 | **Spring Framework 7.1 主线源码尚无 ScopedValue 集成**(本地 `grep -r ScopedValue` 仅命中测试),Micrometer/context-propagation 也仍是 ThreadLocal 体系 |

**迁移结论**:① 新写的"上下文跨结构化并发传递"用 ScopedValue;② 现有 Spring 应用在 4.1.1 时点上,**主战场仍是"管好 ThreadLocal"**(下一节),ScopedValue 作为新代码与公共上下文 API 的演进方向;③ 官方也给了性能告诫:绑定数量要少(打成一个 record 绑定),缓存别反复失效。

## 4.5 Spring 生态的 ThreadLocal 清单与对策

把 Spring 全家桶里基于 ThreadLocal 的设施盘一遍,**虚拟线程迁移时的检查重点全在"换线程点"**:

| 设施 | 底层 | 请求线程上 | 换线程点(@Async 等)的对策 |
|---|---|---|---|
| `RequestContextHolder` | `ThreadLocal`(`spring-web` `RequestContextFilter`/`FrameworkServlet` 存取) | ✅ 不变 | 手动传 `RequestAttributes`,或 decorator 传播 |
| `SecurityContextHolder`(Spring Security) | `ThreadLocal`(MODE_THREADLOCAL) | ✅ 不变 | `DelegatingSecurityContextAsyncTaskExecutor` 包一层;或 decorator |
| SLF4J `MDC` / Micrometer Tracing span | `ThreadLocal` | ✅ 不变(traceId 全程在) | **`spring.task.execution.propagate-context=true`**(5.4)或自定义 `TaskDecorator` 拷 MDC |
| Spring 事务 | `TransactionSynchronizationManager`/`DataSourceUtils.ConnectionHolder` | ✅ 不变 | 事务**不跨 @Async 线程**(语义本就如此,不是虚拟线程引入) |
| `LocaleContextHolder` | `InheritableThreadLocal` | ✅ 不变 | 同上,decorator 传播 |

统一解法就是 Spring 的 `TaskDecorator`(装饰 Runnable,把提交者的上下文快照带过去)+ Micrometer 的 **context-propagation** 库(`ContextSnapshot`,统一 MDC/Security/tracing 的打包协议):

```java
// 把"提交线程"的 ThreadLocal 上下文快照,复制进执行线程——对虚拟线程执行器同样有效
executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
```

【源码证据】`spring-core/src/main/java/org/springframework/core/task/SimpleAsyncTaskExecutor.java` 的执行链——**先装饰(上下文传播的挂点)、再限流、最后每任务一个新线程**:

```java
// :295-297  入口
public void execute(Runnable task) {
    execute(task, TIMEOUT_INDEFINITE);
}

// :316-327  先 TaskDecorator 装饰(MDC/上下文传播就挂在这里),再走并发限流
Runnable taskToUse = (this.taskDecorator != null ? this.taskDecorator.decorate(task) : task);
Future<?> future = (task instanceof Future<?> f ? f : null);
if (isThrottleActive() && startTimeout > TIMEOUT_IMMEDIATE) {
    this.concurrencyThrottle.beforeAccess();
    try {
        doExecute(new TaskTrackingRunnable(taskToUse, future));
    }
    catch (Throwable ex) {
        // Release concurrency permit if thread creation fails
        this.concurrencyThrottle.afterAccess();
        throw new TaskRejectedException("Failed to start execution thread for task: " + task, ex);
    }
}

// :360-361  doExecute:每个任务一个新线程(virtualThreads=true 时即虚拟线程)
protected void doExecute(Runnable task) {
    newThread(task).start();
}

// :373-379  newThread:虚拟线程分支
protected Thread newThread(Runnable task) {
    if (this.virtualThreadDelegate != null) {
        return this.virtualThreadDelegate.newVirtualThread(nextThreadName(), task);
    }
    else {
        return (this.threadFactory != null ? this.threadFactory.newThread(task) : createThread(task));
    }
}
```

(`:83` 的 `ConcurrencyThrottleAdapter` 字段与 `:245-246` 的 `setConcurrencyLimit` 即限流实现;Boot 4.1 的自动装配见 5.4 `TaskExecutorConfigurations.java:75-82`。)

> ⚠️ 唯一要重新审视的场景:**消息消费 / 定时任务等"入口即新线程"的场景**。平台线程池时代,MDC 是空的、Security 是匿名的;虚拟线程时代一样是空——行为无变化,但很多人迁移后才发现"原来池线程上根本没有这些上下文,不是切换弄丢的"。

## 4.6 迁移期诊断:jdk.traceVirtualThreadLocals(JDK 25 新增)

【白话】不知道哪些库在偷偷重度使用 ThreadLocal?JDK 25 给了个开关,虚拟线程上每次 `set`(含 `withInitial` 的惰性初始化)都打印调用栈:

【实测】(JDK 25,`-Djdk.traceVirtualThreadLocals=true`,附录 C `TraceLocals`):

```text
VirtualThread[#36]/runnable@ForkJoinPool-1-worker-1
    java.base/java.lang.ThreadLocal.set(ThreadLocal.java:231)
    TraceLocals.lambda$main$0(TraceLocals.java:11)
    java.base/java.lang.VirtualThread.run(VirtualThread.java:456)
```

用法:压测环境开着这个开关跑一轮核心流量,统计打印点,凡命中 4.3 表中②③类的库,按决策表处理。输出头部 `VirtualThread[#36]/runnable@ForkJoinPool-1-worker-1` 同时演示了虚拟线程的标准观测名(线程 id / 状态 @ 载体名)。

## 4.7 本章小结

- ThreadLocal 机制 100% 不变,**变的是"池化摊销"这一经济前提**;
- 三条铁律:可变实例缓存删掉、大对象进对象池、请求级小上下文保留并管好跨线程传播;
- ITL 继承时机 = 构造时、父 = 创建线程;不想继承就 `inheritInheritableThreadLocals(false)`;
- ScopedValue 已在 JDK 25 转正,是作用域化的"不可变 ThreadLocal",但 Spring 生态(7.1)尚未原生接入,**4.1.1 时点的主线方案 = ThreadLocal + context-propagation/TaskDecorator**;
- 迁移期用 `jdk.traceVirtualThreadLocals=true` 找出所有隐形 ThreadLocal 用户。

# 五、Spring Boot 4.1.1 迁移实战

## 5.1 一个开关,十处变化:`spring.threads.virtual.enabled=true`

【白话】Boot 对虚拟线程的支持不是"把线程池换成虚拟线程池"这一个动作,而是**用同一个条件注解在十几个自动配置上分支**。条件本身很直白:

【源码证据】`spring-boot/core/spring-boot/src/main/java/org/springframework/boot/thread/Threading.java:48-50`:

```java
return environment.getProperty("spring.threads.virtual.enabled", boolean.class, false)
        && JavaVersion.getJavaVersion().isEqualOrNewerThan(JavaVersion.TWENTY_ONE);
```

对应的条件注解 `@ConditionalOnThreading(Threading.VIRTUAL)`(`spring-boot-autoconfigure/.../condition/ConditionalOnThreading.java`)在 4.1.1 代码里的全部命中点(逐个核对过源码):

| # | 组件 | 虚拟线程激活后的变化 | 源码坐标(spring-boot 仓库内路径) |
|---|---|---|---|
| 1 | **Tomcat** 请求处理 | 协议处理器 executor 换成 `VirtualThreadExecutor("tomcat-handler-")` | `module/spring-boot-tomcat/.../TomcatWebServerConfiguration.java:54-55` → `TomcatVirtualThreadsWebServerFactoryCustomizer.java:39` |
| 2 | **Jetty** 请求处理 | 线程池换 `VirtualThreadPool`(并发任务上限=原 maxThreads) | `module/spring-boot-jetty/.../JettyWebServerConfiguration.java:53-55` → `JettyVirtualThreadsWebServerFactoryCustomizer.java` |
| 3 | **@Async** 默认执行器 | `applicationTaskExecutor` 从 `ThreadPoolTaskExecutor` 换 `SimpleAsyncTaskExecutor`(虚拟线程) | `core/spring-boot-autoconfigure/.../task/TaskExecutorConfigurations.java:91-95` |
| 4 | **@Scheduled** 调度器 | `ThreadPoolTaskScheduler` 换 `SimpleAsyncTaskScheduler` | `.../task/DefaultTaskSchedulerConfiguration.java:61-65` |
| 5 | RabbitMQ 监听 | 容器工厂 executor 换 `VirtualThreadTaskExecutor("rabbit-simple-/direct-")` | `module/spring-boot-amqp/.../RabbitAnnotationDrivenConfiguration.java:79-114` |
| 6 | Kafka 监听 | `SimpleAsyncTaskExecutor("kafka-")` + `setVirtualThreads(true)` | `module/spring-boot-kafka/.../KafkaAnnotationDrivenConfiguration.java:128-136` |
| 7 | Redis(Lettuce/Jedis) | 连接工厂以虚拟线程模式构建 | `module/spring-boot-data-redis/.../LettuceConnectionConfiguration.java:103-111` |
| 8 | OTLP 指标导出 | MeterRegistry 用虚拟线程 ThreadFactory | `module/spring-boot-micrometer-metrics/.../OtlpMetricsExportAutoConfiguration.java:105-113` |
| 9 | Reactor | 设 `reactor.schedulers.defaultBoundedElasticOnVirtualThreads=true` | `module/spring-boot-reactor/.../ReactorEnvironmentPostProcessor.java:59-61` |
| 10 | actuator `/info` | 暴露 `VirtualThreadsInfo`(mounted/queued/parallelism/poolSize) | `core/spring-boot/.../info/ProcessInfo.java:46-48,117-138` |

> 依赖条件:Boot 只在"你**没有**自定义 `Executor` Bean"或 `spring.task.execution.mode=force` 时接管任务执行器(`TaskExecutorConfigurations.java:233,OnExecutorCondition`)——**自定义过线程池的应用,开关打开也不会自动换**,见 5.3 的坑。

最核心的 #3(@Async 默认执行器)源码——**同一个 bean 名按条件二选一**:

【源码证据】`core/spring-boot-autoconfigure/.../task/TaskExecutorConfigurations.java:86-104`:

```java
@Configuration(proxyBeanMethods = false)
@Conditional(OnExecutorCondition.class)
@Import({ AsyncConfigurerWrapperConfiguration.class, AsyncConfigurerConfiguration.class })
static class TaskExecutorConfiguration {

    @Bean(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME)
    @ConditionalOnThreading(Threading.VIRTUAL)                  // 虚拟线程分支
    SimpleAsyncTaskExecutor applicationTaskExecutorVirtualThreads(
            SimpleAsyncTaskExecutorBuilder builder) {
        return builder.build();                                 // builder 已被设 virtualThreads(true),:161
    }

    @Bean(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME)
    @Lazy
    @ConditionalOnThreading(Threading.PLATFORM)                 // 平台线程分支
    ThreadPoolTaskExecutor applicationTaskExecutor(ThreadPoolTaskExecutorBuilder threadPoolTaskExecutorBuilder) {
        return threadPoolTaskExecutorBuilder.build();
    }
}
```

## 5.2 Web 层:Tomcat / Jetty 的语义变化

【白话】以 Tomcat 为例,默认链路是"Acceptor 收连接 → Poller 检测可读 → worker 线程(池,上限 `server.tomcat.threads.max`)跑请求"。开关打开后,Boot 把协议处理器的 executor 整个替换成 Tomcat 的 `VirtualThreadExecutor`——**每个请求一个新虚拟线程**,worker 池退役:

【源码证据】Boot 定制器 + Tomcat 11 执行器 + Jetty 定制器三段源码:

```java
// spring-boot:module/spring-boot-tomcat/.../TomcatVirtualThreadsWebServerFactoryCustomizer.java:33-40
public class TomcatVirtualThreadsWebServerFactoryCustomizer
        implements WebServerFactoryCustomizer<ConfigurableTomcatWebServerFactory>, Ordered {

    @Override
    public void customize(ConfigurableTomcatWebServerFactory factory) {
        factory.addProtocolHandlerCustomizers(
                (protocolHandler) -> protocolHandler.setExecutor(new VirtualThreadExecutor("tomcat-handler-")));
    }   // getOrder() 排在标准定制器之后,保证覆盖 server.tomcat.threads.* 设置的池
```

```java
// tomcat:java/org/apache/tomcat/util/threads/VirtualThreadExecutor.java:47-58 —— 纯 per-task,无队列无池
public VirtualThreadExecutor(String namePrefix) {
    threadBuilder = jreCompat.createVirtualThreadBuilder(namePrefix);
}

@Override
public void execute(Runnable command) {
    if (isShutdown()) {
        throw new RejectedExecutionException(...);
    }
    jreCompat.threadBuilderStart(threadBuilder, command);   // 每个任务起一个新虚拟线程
}
```

```java
// spring-boot:module/spring-boot-jetty/.../JettyVirtualThreadsWebServerFactoryCustomizer.java:61-68
@Override
public void customize(ConfigurableJettyWebServerFactory factory) {
    Assert.state(VirtualThreads.areSupported(), "Virtual threads are not supported");
    Integer maxTasks = getMaxTasks();                                        // 来自 server.jetty.threads.max
    VirtualThreadPool virtualThreadPool = (maxTasks != null) ? new VirtualThreadPool(maxTasks)
            : new VirtualThreadPool();
    virtualThreadPool.setName("jetty-");
    factory.setThreadPool(virtualThreadPool);
}
```

```java
// jetty:jetty-core/jetty-util/.../thread/VirtualThreadPool.java:57-61,98 —— maxTasks 的真实含义
public VirtualThreadPool(int maxTasks) {
    ...
    _maxTasks = maxTasks;
}
// @param maxTasks the maximum number of concurrent tasks run by virtual threads
```

**语义变化清单(重要)**:

| 原配置 | 打开开关后 |
|---|---|
| `server.tomcat.threads.max/min-spare`(池大小) | **失效**——executor 被整体替换;请求并发不再受"池大小"限制 |
| `server.tomcat.max-connections`(端连接上限) | **仍然生效**,这是第一道闸;默认 8192 |
| `server.tomcat.accept-count` | 仍然生效(队列满后拒绝/排队) |
| `server.tomcat.threads.max` 的新角色 | 想限制请求并发,改用 5.7 的 Semaphore 或自管 executor |
| Jetty:`server.jetty.threads.max` | **含义变化**:映射为 `VirtualThreadPool` 的 `maxTasks` = **并发任务上限**(`jetty-util/.../VirtualThreadPool.java:57,98`:"the maximum number of concurrent tasks run by virtual threads"),不再是"池里养多少线程" |

结论:**开关打开 ≠ 并发无界**——Tomcat 的 maxConnections、Jetty 的 maxTasks 仍是一道闸;但原来靠池大小(默认 200)挡住的下游冲击没有了,限流策略要重新设计(5.7)。

## 5.3 @Async / @Scheduled:从"有界池"到"无限流"

【白话】Boot 4.1 里这两个切换是同一模式:虚拟线程分支装配 `SimpleAsyncTaskExecutor`/`SimpleAsyncTaskScheduler`(Framework 的 per-task 执行器,带可选并发限流),平台线程分支装配传统池。**它们不是"虚拟线程版的 ThreadPoolExecutor"——没有队列、没有池大小,投递即创建新虚拟线程**:

【源码证据】

① `TaskExecutorConfigurations.java:91-95`:@Async 的 `applicationTaskExecutor` 二选一(代码见 5.1);Framework 侧的执行链(`SimpleAsyncTaskExecutor.java:295-380`,先 decorator 再限流再 `newThread().start()`)已在 4.5 贴全——**没有队列、没有池,投递即创建**。

② `DefaultTaskSchedulerConfiguration.java:53-66`:@Scheduled 的调度器同样二选一:

```java
@Bean(name = DEFAULT_TASK_SCHEDULER_BEAN_NAME)
@ConditionalOnBean(ThreadPoolTaskSchedulerBuilder.class)
@ConditionalOnThreading(Threading.PLATFORM)
ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder threadPoolTaskSchedulerBuilder) {
    return threadPoolTaskSchedulerBuilder.build();
}

@Bean(name = DEFAULT_TASK_SCHEDULER_BEAN_NAME)
@ConditionalOnBean(SimpleAsyncTaskSchedulerBuilder.class)
@ConditionalOnThreading(Threading.VIRTUAL)
SimpleAsyncTaskScheduler taskSchedulerVirtualThreads(
        SimpleAsyncTaskSchedulerBuilder simpleAsyncTaskSchedulerBuilder) {
    return simpleAsyncTaskSchedulerBuilder.build();
}
```

③ Framework 的虚拟线程真正落在 `VirtualThreadDelegate`(`spring-core/src/main/java21/.../task/VirtualThreadDelegate.java:16-33`,jar 里按 JDK 版本多发行制加载 java21 变体):

```java
final class VirtualThreadDelegate {

    private final Thread.Builder threadBuilder = Thread.ofVirtual();

    public ThreadFactory virtualThreadFactory(String threadNamePrefix) {
        return this.threadBuilder.name(threadNamePrefix, 0).factory();
    }

    public Thread newVirtualThread(String name, Runnable task) {
        return this.threadBuilder.name(name).unstarted(task);
    }
}
```

④ `SimpleAsyncTaskScheduler`(`spring-context/.../SimpleAsyncTaskScheduler.java:103`)继承 `SimpleAsyncTaskExecutor` 实现 `TaskScheduler`,javadoc 自述每次触发在**单独的新线程**上执行("This is an attractive choice with virtual threads on JDK 21+"):

```java
public class SimpleAsyncTaskScheduler extends SimpleAsyncTaskExecutor implements TaskScheduler,
        ApplicationContextAware, SmartLifecycle, ApplicationListener<ContextClosedEvent> {
    // :127  调度计时用的时钟;:186  可把实际执行委派给别的 executor(setTargetTaskExecutor)
    private Clock clock = Clock.systemDefaultZone();
    ...
}
```

**迁移坑位表**:

| 原认知(池时代) | 虚拟线程时代 | 动作 |
|---|---|---|
| `spring.task.execution.pool.max-size=50` 控制并发 | **失效**;默认并发无界 | 用 `spring.task.execution.simple.concurrency-limit=N`(`TaskExecutionProperties.java:93-104`,Simple 配置组)限流,配套 `reject-tasks-when-limit-reached` |
| 队列满拒绝策略 | 没有"队列"概念,限流满即拒绝(或阻塞视配置) | 评估调用方对拒绝的容错 |
| @Async 默认复用 worker,MDC 靠 decorator | 每次全新虚拟线程,decorator 仍生效(`SimpleAsyncTaskExecutor` 支持 `setTaskDecorator`,`:89,171-173`) | decorator 照常配(5.4) |
| @Scheduled 固定池大小,任务堵了排队 | 每次触发新虚拟线程,**上一次没跑完下一次照样开跑** | 需要互斥的任务加锁/`Semaphore`,或保留平台线程 `ThreadPoolTaskScheduler` |
| 自定义过 `Executor`/`AsyncConfigurer` Bean | **开关不接管**(OnExecutorCondition) | 自行把 Bean 换成 `Executors.newVirtualThreadPerTaskExecutor()` 或 `VirtualThreadTaskExecutor`(`spring-core/.../task/VirtualThreadTaskExecutor.java`) |

## 5.4 上下文传播:ThreadLocal 迁移的 Boot 官方配套

【白话】第四章说"换线程点需要 decorator"——Boot 4.1 直接内置了开关:`spring.task.execution.propagate-context=true` 时,自动给 `applicationTaskExecutor` 挂 `ContextPropagatingTaskDecorator`(基于 Micrometer context-propagation 的 `ContextSnapshot`),把 MDC、tracing、以及注册过 `ThreadLocalAccessor` 的自定义上下文,从提交线程打包带到执行线程(虚拟线程也适用):

【源码证据】`TaskExecutorConfigurations.java:75-82`:

```java
static class TaskExecutorContextPropagationConfiguration {
    @Bean
    @ConditionalOnProperty(name = "spring.task.execution.propagate-context", havingValue = "true")
    ContextPropagatingTaskDecorator contextPropagatingTaskDecorator() { return new ContextPropagatingTaskDecorator(); }
}
```

要点:
- 该 decorator 需要 classpath 上有 `io.micrometer:context-propagation`(`@ConditionalOnClass(ContextSnapshot.class)`,`:77`);
- 自定义上下文(比如你的 `UserContextHolder`)要接入它,实现一个 `ThreadLocalAccessor` 并注册(`ContextRegistry.getInstance().registerThreadLocalAccessor(...)`),之后**同一个机制覆盖 MDC/Security/tracing/你的上下文**;
- 不想引库:自己写 `TaskDecorator` 拷 MDC + `SecurityContextHolder`(`DelegatingSecurityContextRunnable`)也一样,虚拟线程不改变这个接口的用法;
- **注意反向事实**:该开关传播的是"提交者上下文",它**不解决**"请求结束后上下文残留"——虚拟线程一次性生命周期反而让残留问题自然消失。

## 5.5 数据库与下游:真资源不会变多

【白话】虚拟线程把"线程"变便宜了,但 **JDBC 连接、Redis 连接、下游服务的处理能力一点没变**。池时代"池大小 50 = 并发上限 50"的隐式限流消失了,如果没有 5.7 的显式限流,10 万请求会同时挤在 `DataSource.getConnection()` 上 park——不是雪崩,是"都在等连接"的延迟上升。这通常正是想要的行为(等待而非失败),但要重新校准:

- **HikariCP `maximumPoolSize` 成为事实上的并发闸门**:获取连接的虚拟线程在 `getConnection()` 上让出等待,不占载体线程——这是虚拟线程 + 阻塞 JDBC 依然优雅的原因;
- 连接池要按"下游 DB 能承受的并发"设,而不是按"应用线程数"设;
- Jedis/Lettuce/Kafka/Rabbit 的客户端线程(Boot 已切虚拟线程,见 5.1 表 #5~#7)主要是 I/O 等待,虚拟线程化收益稳定;
- 带 native 层的驱动(老 Oracle OCI 等)注意 3.4 的 native 钉住排查。

## 5.6 响应式混用:别把 block 塞进 event loop

【白话】虚拟线程与 Reactor 是两套并发模型。Boot 打开开关后 `reactor.schedulers.defaultBoundedElasticOnVirtualThreads=true`(`ReactorEnvironmentPostProcessor.java:59-61`),`Schedulers.boundedElastic()` 的阻塞任务会跑在虚拟线程上——**这只是兜底**。规则不变且更严厉:

- **WebFlux/Netty 的 event loop 线程不是虚拟线程**(`server.threads.virtual.enabled` 不会虚拟化 Netty worker),在其中 `block()` 照样卡死整个 event loop;
- WebClient/Micrometer 等异步组件保持异步用法;
- 全虚拟线程栈(本章) + 全阻塞代码是**最简形态**;混合栈(Reactor + 虚拟线程)只在"既有响应式模块逐步迁移"时短期共存;
- 一句话:**虚拟线程的哲学是"写起来像同步,跑起来高吞吐";Reactor 的哲学是"把异步显式化"。别用虚拟线程去"修"响应式代码,那是两种解法**。

## 5.7 并发上限:用 Semaphore 顶替池大小

【白话】迁移后"控制同时打到下游的请求数"从"池大小"变成"信号量"。官方 FAQ 的标准答案:

```java
// 限制并发 DB 访问 50(替代原 pool max-size=50 的隐式限流)
Semaphore dbPermits = new Semaphore(50);
List<Result> queryAll(List<String> ids) {
    try (var scope = /* 扇出 */...) { ... }
}
// 每个任务内:
dbPermits.acquire();          // 虚拟线程在此让出等待,零成本
try { return jdbc.query(...); } finally { dbPermits.release(); }
```

- `Semaphore.acquire()` 基于 `LockSupport`,虚拟线程让出,百万任务排队也只占堆内存;
- 三层闸门回顾:OS 层(Tomcat max-connections)→ 应用层(Semaphore / `spring.task.execution.simple.concurrency-limit`)→ 资源层(Hikari `maximumPoolSize`);
- 断路器(Sentinel/Resilience4j)继续用,它们本来就是并发放大时的保护,虚拟线程会**更快触发**它们的阈值——压测校准。

## 5.8 观测落地

- `/actuator/info` 已含 `VirtualThreadsInfo`(`ProcessInfo.java:117-138`,来自 JDK 24+ 的 `VirtualThreadSchedulerMXBean`):mounted(挂载在载体上的)、queued(排队等载体的)、parallelism、poolSize——**queued 持续高位 = 载体不够(CPU 密集或钉住),mounted 持续高位 = 真并发高**:

【源码证据】`core/spring-boot/.../info/ProcessInfo.java:117-134`:

```java
public @Nullable VirtualThreadsInfo getVirtualThreads() {
    if (!VIRTUAL_THREAD_SCHEDULER_CLASS_PRESENT) {   // :46 "jdk.management.VirtualThreadSchedulerMXBean"
        return null;
    }
    try {
        Class<PlatformManagedObject> mxbeanClass = (Class<PlatformManagedObject>) ClassUtils
            .forName(VIRTUAL_THREAD_SCHEDULER_CLASS, null);
        PlatformManagedObject mxbean = ManagementFactory.getPlatformMXBean(mxbeanClass);
        int mountedVirtualThreadCount = invokeMethod(mxbeanClass, mxbean, "getMountedVirtualThreadCount");
        long queuedVirtualThreadCount = invokeMethod(mxbeanClass, mxbean, "getQueuedVirtualThreadCount");
        int parallelism = invokeMethod(mxbeanClass, mxbean, "getParallelism");
        int poolSize = invokeMethod(mxbeanClass, mxbean, "getPoolSize");
        return new VirtualThreadsInfo(mountedVirtualThreadCount, queuedVirtualThreadCount, parallelism, poolSize);
    }
    catch (Exception ex) {
        return null;
    }
}
```
- 线程 dump 换 `jcmd <pid> Thread.dump_to_file -format=json`(虚拟线程才可见);运维脚本/监控 Agent(如仍解析 `jstack` 文本)需要升级;
- JFR 建议常开 `jdk.VirtualThreadPinned`(阈值压测期 10ms、稳态 20ms 默认)与 `jdk.VirtualThreadSubmitFailed`;
- 日志线程名:统一用 builder/executor 的 `namePrefix`(`tomcat-handler-`、`kafka-`、`task-`),否则日志里是空线程名(3.2)。

## 5.9 迁移检查清单(照单执行)

**A. 前置盘点(改代码前)**
- [ ] JDK ≥ 21(本文按 25;21~23 需额外执行 3.4 的"换锁"评估)
- [ ] 依赖库扫描:JNI/native 阻塞点(3.4);重度 ThreadLocal 用户(`-Djdk.traceVirtualThreadLocals=true` 压测,4.6)
- [ ] 找出所有自建线程池与 `Executor` Bean(5.3 最后一行:开关不会接管它们)

**B. 开关与配置**
- [ ] `spring.threads.virtual.enabled=true`
- [ ] 删除/重审失效配置:`server.tomcat.threads.*`、线程池参数(5.2)
- [ ] 限流重设计:Semaphore 或 `spring.task.execution.simple.concurrency-limit`(5.7)
- [ ] Hikari/Redis/Kafka 连接与消费并发按下游能力重校(5.5)
- [ ] `spring.task.execution.propagate-context=true` + 自定义上下文的 `ThreadLocalAccessor`(5.4)

**C. 代码清理**
- [ ] 删除 `setDaemon(false)` / `setPriority` / ThreadGroup 相关调用(3.2,虚拟线程上抛异常或失效)
- [ ] 21~23 时代为绕钉住写的 `ReentrantLock` 改回 `synchronized`(24+,可选)(3.4)
- [ ] ThreadLocal ②③类清理:`SimpleDateFormat` → `DateTimeFormatter`;大缓冲 → 对象池(4.3)
- [ ] ITL 用法迁移或 `inheritInheritableThreadLocals(false)`(4.2)
- [ ] 新上下文 API 评估 ScopedValue(JDK 25)(4.4)

**D. 观测与验收**
- [ ] 线程 dump 脚本换 `jcmd Thread.dump_to_file -format=json`(3.5)
- [ ] `/actuator/info` 的 mounted/queued 接入告警(5.8)
- [ ] 压测对比:吞吐、P99、载体占用率、`jdk.VirtualThreadPinned` 事件数、堆上 ThreadLocal 峰值内存
- [ ] 日志线程名规范(5.8)

**验证 throughput 的最小 A/B**:同一压测脚本,分别以 `spring.threads.virtual.enabled=false/true` 起两实例;重点看"等下游"类接口的 P99 与连接池等待队列——虚拟线程的收益几乎全部来自这里。

## 5.10 本章小结

Boot 4.1.1 的迁移 = **一个开关 + 三类手工活**:开关自动覆盖 Web 容器、@Async/@Scheduled、主流 starter(5.1 表);手工活是①把自建池换成 per-task 执行器、②把"池大小限流"换成"信号量限流"、③把 ThreadLocal 前提重审一遍(第四章)。观测侧记得换 dump 方式并盯 Pinned 事件。

# 六、反模式与陷阱 Top 10

| # | 反模式 | 后果 | 正解 |
|---|---|---|---|
| 1 | **池化虚拟线程**(`newFixedThreadPool` + 虚拟工厂、复用虚拟线程) | 丢掉全部收益,还把 ThreadLocal 缓存带回来 | per-task:`newVirtualThreadPerTaskExecutor()` |
| 2 | CPU 密集任务上虚拟线程 | 载体只有核数个,又无时间片抢占,其他虚拟线程饿到它让出 | 平台线程池(核数大小)+ 虚拟线程只做 I/O |
| 3 | 大对象 set 进 ThreadLocal(4.3②③) | 百万请求 × N MB = OOM | 对象池/不可变 API |
| 4 | 无并发上限直冲 DB/下游(5.7) | 连接池排队,P99 飙升 | Semaphore / concurrency-limit |
| 5 | 以为 21~23 的"换 ReentrantLock"清单适用于 25 | 白改代码 | JEP 491 已解决,保留原 `synchronized`(3.4) |
| 6 | 在 JNI/FFM native 帧内做阻塞 I/O | native 钉住占满载体 | 隔离到平台线程;JFR 扫 Pinned(3.4) |
| 7 | 依赖 `setPriority`/`setDaemon`/ThreadGroup 做管理 | 抛异常或静默失效(3.2 实测) | 删调用,改用调度器/执行器抽象 |
| 8 | 类初始化器里做 I/O(`<clinit>` 中连 DB) | 类初始化钉住(3.4 残留场景) | 惰性初始化 + 双重检查 |
| 9 | `jstack`/老脚本解析平台线程 dump | 看不到虚拟线程,误判"服务没线程在跑" | `jcmd Thread.dump_to_file -format=json`(3.5) |
| 10 | 把虚拟线程当"免费并发",取消舱壁隔离 | 打爆共享资源(连接/文件句柄/下游) | 三层闸门 + 断路器照旧(5.7) |

# 七、演进展望:结构化并发与 ScopedValue 生态

- **Structured Concurrency**(JDK 25 为第 5 次预览,JEP 505):把"扇出多任务、统一等待/取消"从手工 executor 拼装升级为作用域原语,与虚拟线程、ScopedValue 是 Loom 三件套;未定型,**生产先观望**。全景见 [JDK版本特性变化.md](<JDK版本特性变化.md>) 18.3/21.4。
- **ScopedValue 生态**(JEP 506 已定):预期 Spring Framework 后续版本提供 ScopedValue 版的上下文载体(7.1 主线源码暂未见,4.4);Micrometer 的 context-propagation 预计跟进双轨支持。
- **可观测性**:`VirtualThreadSchedulerMXBean`(JDK 24)→ Boot `/actuator/info`(已落地)→ 后续或进 Micrometer JVM 指标体系。
- **给你的时间线建议**:JDK 25 + Boot 4.1 上**现在就可以迁移**(synchronized 已不钉住、诊断工具齐全);结构化并发等转正后再引入新模型。

# 附录 A:JDK 25 虚拟线程速查(属性 / JFR / 命令)

**系统属性**

| 属性 | 作用 | 版本 |
|---|---|---|
| `jdk.virtualThreadScheduler.parallelism` | 默认调度器并行度(默认 CPU 核数) | 21+ |
| `jdk.virtualThreadScheduler.maxPoolSize` | 载体线程上限(默认 max(核数,256)) | 21+ |
| `jdk.virtualThreadScheduler.minRunnable` | 最少可运行载体数(默认 max(核数/2,1)) | 21+ |
| `jdk.traceVirtualThreadLocals` | 虚拟线程 set ThreadLocal 时打印调用栈 | **25** |
| `java.lang.ScopedValue.cacheSize` | ScopedValue 每线程缓存槽位(2~16,默认 16) | 25 |
| `jdk.preserveScopedValueCache` | 阻塞时是否保留缓存(默认 true) | 25 |
| ~~`jdk.tracePinnedThreads`~~ | (已移除,设置无效果) | 21~23 有,24 移除 |

**JFR 事件**:`jdk.VirtualThreadStart`/`jdk.VirtualThreadEnd`(默认关闭)、`jdk.VirtualThreadPinned`(默认阈值 20ms;24+ 含钉住原因与载体标识)、`jdk.VirtualThreadSubmitFailed`。

**命令**:`jcmd <pid> Thread.dump_to_file -format=json dump.json`(唯一含虚拟线程的 dump);`jfr print --events jdk.VirtualThreadPinned pin.jfr`;`jcmd <pid> VM.system_properties` 核对属性。

# 附录 B:Spring Boot 4.1 虚拟线程配置速查

| 配置 | 作用 | 源码坐标(spring-boot 仓库) |
|---|---|---|
| `spring.threads.virtual.enabled` | 总开关(默认 false,需 JDK 21+) | `core/spring-boot/.../thread/Threading.java:48-50` |
| `spring.task.execution.mode=force` | 即使存在自定义 `Executor` Bean 也自动装配 applicationTaskExecutor | `core/spring-boot-autoconfigure/.../task/TaskExecutorConfigurations.java:233` |
| `spring.task.execution.propagate-context` | 给 applicationTaskExecutor 挂 `ContextPropagatingTaskDecorator` | 同上 `:75-82` |
| `spring.task.execution.simple.concurrency-limit` | SimpleAsyncTaskExecutor 并发上限(默认无界) | `core/spring-boot/.../task/TaskExecutionProperties.java:93-104` |
| `spring.task.execution.simple.reject-tasks-when-limit-reached` | 限流满时拒绝而非挂起 | 同上 |
| `spring.task.scheduling.*` | @Scheduled 侧对应配置(SimpleAsyncTaskScheduler 分支) | `.../task/TaskSchedulingConfigurations.java:87-115` |
| `server.tomcat.max-connections` / `accept-count` | 仍生效的两道连接层闸门(池大小失效) | 5.2 |
| `server.jetty.threads.max` | 语义变为 `VirtualThreadPool` 并发任务上限 | `JettyVirtualThreadsWebServerFactoryCustomizer.java` + `jetty-util/.../VirtualThreadPool.java:57,98` |

# 附录 C:本文实验代码与实测输出

实验目录 `D:\tmp\vt-demo`(临时产物,可清理),JDK 25.0.1 与 JDK 21 实测:

| 文件 | 验证点 | 正文引用 |
|---|---|---|
| `BehaviorDiff.java` | isDaemon/setDaemon(false)/setPriority/ThreadLocal/ITL 继承/ScopedValue 边界 | 2.4、3.2、4.2、4.4 |
| `ScaleTest.java` | 20 万虚拟线程 160ms 启动;2 万平台线程对照 | 3.3 |
| `PinMonitor.java` | synchronized 内 park/wait:JDK 21 产生 297ms `jdk.VirtualThreadPinned` 事件,JDK 25 零事件 | 3.4 |
| `TraceLocals.java` | `-Djdk.traceVirtualThreadLocals=true` 的调用栈输出 | 4.6 |

# 相关文档

- [Java并发.md](<Java并发.md>) —— 线程基础、AQS、线程池原理(迁移前对照"池假设"清单)
- [cursor_JDK_线程池(包含定时任务).md](<cursor_JDK_线程池(包含定时任务).md>) —— 被替换对象的全细节
- [JDK版本特性变化.md](<JDK版本特性变化.md>) —— 15.2 虚拟线程演进全景、19.2 Scoped Values 全景、18.3/21.4 结构化并发
- [../spring/Spring Boot.md](<../spring/Spring Boot.md>)、[../spring/Tomcat.md](<../spring/Tomcat.md>) —— Boot 配置体系与 Tomcat 连接器模型(maxConnections 闸门)
- [../spring/Project Reactor.md](<../spring/Project Reactor.md>) —— 响应式对照面(5.6)
- [../spring/Micrometer Tracing.md](<../spring/Micrometer Tracing.md>) —— context-propagation 与 tracing 上下文(5.4)













