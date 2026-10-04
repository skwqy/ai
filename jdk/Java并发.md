# Java 并发深度源码解析（写给初学者的并发工具全景）

> **本文基于的源码**：本机 JDK 21 LTS（`D:\soft\jdk\jdk-21\lib\src.zip`，版本 21.0.12.1）实际走读所得。文中所有【源码证据】的文件路径与行号均为对该快照实际读取所得，源码包内路径形如 `java.base/java/util/concurrent/ConcurrentHashMap.java`（`java.base` 是几乎所有并发类的所在模块）。
>
> **版本取舍说明**：并发工具的核心骨架自 JDK 5（JSR-166）以来高度稳定，`AbstractQueuedSynchronizer`、`ThreadPoolExecutor.execute`、`ConcurrentHashMap.putVal` 这些核心方法在 8/11/17/21 之间的骨架几乎一致，因此本文内容对使用 JDK 8 ~ 27 的读者同样适用。需要特别注意的三处版本差异，文中会随章节标注：① JDK 14 起 `AbstractQueuedSynchronizer` 内部被重写（队列节点字段改名、主循环合并），与很多教材写的 JDK 8 经典版对不上，本文按 JDK 21 实码讲解并给出新旧对照；② JDK 21 的 `SynchronousQueue` 已重写为基于 `LinkedTransferQueue` 的统一实现；③ 虚拟线程在 JDK 21 正式落地，但在 synchronized 块中持锁时仍会"钉住"载体线程，JDK 24（JEP 491）才解除该限制。
>
> **阅读约定**：每章"先白话、后源码"——先用一两句人话讲清"这是什么、为什么需要它"，再给出源码证据链（`java.base/.../类名.java` + 方法名 + 行号 + 代码片段）。行号只对该快照精确，读者用 IDEA 打开 `src.zip` 按类名 + 方法名定位即可。所有使用示例都是完整可运行的小程序（默认已 `import java.util.concurrent.*`）。

## 如何读这份文档

如果你是并发初学者，推荐两遍读法：

- **第一遍（建立地图，1~2 小时）**：只读第一章（总览）每一节的开头白话段、各章的"小结"节、以及第十一章（决策表与陷阱清单）。目标是能回答：并发 bug 的三大根源是什么？JDK 的并发工具分几层？遇到"计数慢了 / Map 炸了 / 线程不够用了"分别该用什么？
- **第二遍（深入源码）**：对照每一章的【源码证据】，打开源码逐行读。顺序建议：第二章（线程与中断）→ 第三章（volatile 与 JMM）→ 第四章（synchronized 与 wait/notify）→ 第五章（AQS，j.u.c 的心脏）→ 第六章（读写锁）→ 第七章（原子类）→ 第八章（并发容器）→ 第九章（协作工具）→ 第十章（线程池与异步，篇幅最大可拆两次读）。

---

# 一、总览：并发的三大根基问题与 JDK 工具地图

## 1.1 并发编程到底难在哪：三大根基问题

一句话定位：**并发编程 = 多个执行流同时读写共享数据**。只要"同时"和"共享"同时存在，就有三个绕不开的问题：

| 问题 | 一句话定义 | 典型现象 | 硬件/编译器根源 |
|---|---|---|---|
| **原子性**（Atomicity） | 一个操作要么全部完成、要么全部不做，不能被"切一半" | `count++` 一万次，结果小于一万 | 一条 Java 语句对应多条 CPU 指令（读-改-写），随时可能被其他线程插入 |
| **可见性**（Visibility） | 一个线程改了数据，另一个线程能不能"立刻看到" | 死循环停不下来：后台线程永远读到旧值 | 每个核心有自己的高速缓存，普通变量的修改未必马上刷回内存/同步到其他核心 |
| **有序性**（Ordering） | 代码写的顺序 = 实际执行的顺序吗 | 双检锁单例返回"半成品"对象 | 编译器和 CPU 都会重排指令（只要单线程内看起来等价） |

一个 10 行的复现实验（`count++` 不是原子操作）：

```java
public class RaceDemo {
    static int count = 0;                       // 普通共享变量
    public static void main(String[] args) throws Exception {
        Runnable task = () -> {
            for (int i = 0; i < 10_000; i++) count++;   // 读-加-写，三步
        };
        Thread t1 = new Thread(task), t2 = new Thread(task);
        t1.start(); t2.start();
        t1.join();  t2.join();                  // 等两个线程都跑完
        System.out.println(count);              // 期望 20000，实际常输出 10000~20000 之间的数
    }
}
```

`count++` 在字节码层面是 `getfield → iadd → putfield` 三步（可自行 `javap -c` 验证）。两个线程交错执行时会发生**丢失更新**：都读到 `count=100`，各自加 1 后都写回 `101`——两次自增只生效了一次。JDK 后面设计的几乎所有并发对象，都是在对这三大问题做文章：**要么消灭共享（ThreadLocal），要么用互斥挡住并发（synchronized/Lock），要么把"读-改-写"压缩成一条硬件原子指令（CAS/原子类），要么干脆不阻塞线程（异步、虚拟线程）**。

## 1.2 统一一切的语言：JMM 与 happens-before

**白话**：上面三个问题之所以各平台表现不一，是因为"内存什么时候同步"没有说清楚。Java 内存模型（JMM，JLS §17.4）就是 JVM 给所有开发者的合同：**定义了 `happens-before`（先行发生）规则——只要动作 A happens-before 动作 B，A 的结果对 B 可见且 B 看到的顺序不会乱**。JDK 并发包的 javadoc 里有一节专门把这份合同翻译成了程序员能直接使用的条目：

【源码证据】`java.base/java/util/concurrent/package-info.java:227`（"Memory Consistency Properties" 一节标题）

```java
 * <h2 id="MemoryVisibility">Memory Consistency Properties</h2>
```

这一节列出的核心条目（均摘自 `package-info.java`，行号见注释）：

```java
 *   // 解锁先行于后续加锁（package-info.java:243-248）
 *   An unlock (synchronized block or method exit) of a monitor happens-before
 *   every subsequent lock (synchronized block or method entry) of that same monitor.
 *
 *   // start 先行于被启动线程内的任何动作（package-info.java:256-257）
 *   A call to start on a thread happens-before any action in the started thread.
 *
 *   // 放入并发集合 先行于 另一线程对该元素的访问/移除（package-info.java:270-272）
 *   Actions in a thread prior to placing an object into any concurrent collection
 *   happen-before actions subsequent to the access or removal of that element
 *   from the collection in another thread.
```

记住这几条，后面每一章的工具都能对号入座：`synchronized` 的解锁/加锁、`volatile` 的读写、`Thread.start()/join()`、向并发容器放入元素、`CountDownLatch.countDown()` 到 `await()` 返回……**都是 happens-before 边**。读懂 JMM 之后，"为什么 volatile 标志能停线程""为什么 ConcurrentHashMap 放入的元素另一线程能看到"这类问题就不再是玄学。

## 1.3 JDK 并发工具全景图（分层）

JDK 对并发的解决方案可以画成六层，**越往下越靠近硬件，越往上越接近业务**：

```
┌──────────────────────────────────────────────────────────────────┐
│ ⑥ 异步与并行编程模型                                              │
│    CompletableFuture（依赖编排）  ForkJoinPool（分治/工作窃取）      │
│    Flow/SubmissionPublisher（响应式流，见系列文档 Flow.md）          │
├──────────────────────────────────────────────────────────────────┤
│ ⑤ 线程池：ThreadPoolExecutor / ScheduledThreadPoolExecutor         │
│    Future / FutureTask（一次性结果）  虚拟线程 VirtualThread（JDK21）│
├──────────────────────────────────────────────────────────────────┤
│ ④ 线程协作工具（同步器）：CountDownLatch / CyclicBarrier /          │
│    Semaphore / Exchanger / Phaser（全部构建在 AQS 或 Lock 之上）    │
├──────────────────────────────────────────────────────────────────┤
│ ③ 并发容器：ConcurrentHashMap / CopyOnWriteArrayList /             │
│    ConcurrentLinkedQueue / BlockingQueue 家族（生产者消费者标准件）  │
├──────────────────────────────────────────────────────────────────┤
│ ② 显式同步工具：ReentrantLock / Condition / ReentrantReadWriteLock │
│    / StampedLock / Semaphore —— 全部基于 ① 的 AQS 与 LockSupport   │
│    原子类：AtomicInteger / AtomicLong / LongAdder / ...（CAS 无锁） │
├──────────────────────────────────────────────────────────────────┤
│ ① 语言级地基：Thread（线程抽象与中断）、synchronized + wait/notify │
│    （对象监视器）、volatile（可见性/有序性）、ThreadLocal（隔离）     │
│    LockSupport（park/unpark，j.u.c 的阻塞原语）                    │
└──────────────────────────────────────────────────────────────────┘
```

两条主线值得预先点破，它们贯穿全文：

1. **AQS 是 j.u.c 的心脏**：ReentrantLock、Semaphore、CountDownLatch、线程池的 Worker……全都继承 `AbstractQueuedSynchronizer`，复用同一套"volatile state + FIFO 等待队列 + park/unpark"的骨架（第五章详读源码）。
2. **CAS 是无锁世界的原子**：原子类、ConcurrentHashMap 的空桶插入与计数、ConcurrentLinkedQueue 的入队出队，全靠 `Unsafe.compareAndSetXxx` 这一条硬件原子指令（第七章详读源码）。

## 1.4 版本演进：并发能力是三代人堆出来的

| 版本 | 并发领域的关键事件 | 一句话意义 |
|---|---|---|
| JDK 1.0~1.4 | `Thread`、`synchronized`、`wait/notify`、`volatile` | 只有"对象监视器"一把锤子：不可尝试、不可超时、不可中断、读写不分 |
| **JDK 5（2004，JSR-166）** | Doug Lea 的 `util.concurrent` 收编进 JDK：AQS、ReentrantLock、线程池、原子类、并发容器、Future | 并发编程从"手搓"进入"标准库"时代 |
| JDK 6 | 锁优化（偏向锁/轻量级锁/自旋锁），ConcurrentHashMap 并发度提升 | 让 synchronized 变快 |
| JDK 7 | ForkJoinPool（工作窃取）、TransferQueue、Phaser | 分治并行 + 更灵活的同步器 |
| **JDK 8** | CompletableFuture、LongAdder、Striped64、StampedLock、`addAndGet` 函数式更新 | 异步编排 + 高竞争计数 + 乐观读 |
| JDK 9~11 | VarHandle 替代部分 Unsafe 用法；Reactive Streams（Flow） | 更安全的底层访问入口 |
| JDK 14 | **AQS 内部重写**（本文第五章讲的即是新版） | 外部语义不变，内部更简洁 |
| JDK 15 | 偏向锁默认禁用并废弃（JEP 374），后续版本移除 | 承认"重量级锁其实没那么重" |
| **JDK 21（LTS）** | **虚拟线程正式落地**（JEP 444） | 一个请求一个线程的海量并发模型 |
| JDK 24 | JEP 491：虚拟线程在 synchronized 中不再钉住载体线程 | 虚拟线程的最后一个大坑被填 |

> 本文以 JDK 21 为基线的原因：它是当前生产环境最主流的 LTS，既有全部经典并发工具，又有虚拟线程；再往上 JDK 25/27 在本文覆盖的领域几乎无变化（已实测比对：AQS 在 JDK 21 为 1984 行、JDK 27 为 1991 行，结构一致）。

## 1.5 关键问题 → JDK 方案映射（全文导览）

| 你遇到的问题 | JDK 给的工具 | 详见 |
|---|---|---|
| 想同时做多件事 | `Thread` / `Runnable` / `Callable` | 第二章 |
| 一个线程要等另一个线程的结果 | `join()` / `Future.get()` | 第二章、第十章 |
| 共享变量改了别的线程看不见 | `volatile` | 第三章 |
| `count++` 丢了更新 | `synchronized` / `AtomicInteger` / `LongAdder` | 第四章、第七章 |
| 一个资源同一时刻只能一个人用 | `synchronized` / `ReentrantLock` | 第四章、第五章 |
| 需要尝试获取/超时/可中断的锁 | `ReentrantLock.tryLock/lockInterruptibly` | 第五章 |
| 线程 A 等"某条件成立"再干活 | `wait/notify` 或 `Condition` | 第四章、第五章 |
| 读多写少的缓存 | `ReentrantReadWriteLock` / `StampedLock` 乐观读 | 第六章 |
| 线程各用各的数据，不想加锁 | `ThreadLocal` | 第二章 |
| 多线程共享的 Map/ArrayList | `ConcurrentHashMap` / `CopyOnWriteArrayList` | 第八章 |
| 生产者-消费者解耦、削峰 | `BlockingQueue` 家族 | 第八章 |
| "N 个任务全做完再继续" | `CountDownLatch` / `CompletableFuture.allOf` | 第九章、第十章 |
| "大家到齐了再一起走，反复多次" | `CyclicBarrier` / `Phaser` | 第九章 |
| 最多允许 N 个线程同时访问 | `Semaphore` | 第九章 |
| 线程创建销毁太频繁、线程数失控 | `ThreadPoolExecutor` | 第十章 |
| 异步任务串联/汇聚/异常处理 | `CompletableFuture` | 第十章 |
| CPU 密集的分治计算 | `ForkJoinPool` + `RecursiveTask` | 第十章 |
| 单机百万并发连接（IO 密集） | 虚拟线程（JDK 21） | 第十章 |

## 1.6 小结

- 并发的三大根源问题（原子性、可见性、有序性）来自硬件与编译器，JMM 用 happens-before 把"什么时候可见"变成了可推理的合同，`java.util.concurrent/package-info.java` 的 "Memory Consistency Properties" 一节就是这份合同的官方清单。
- JDK 的并发工具是六层金字塔：地基（Thread/synchronized/volatile/LockSupport）→ 显式锁与原子类 → 并发容器 → 同步器 → 线程池 → 异步/并行模型。
- 两条设计主线：**AQS 复用**（一个框架长出所有的锁和同步器）与 **CAS 无锁化**（能用一条硬件指令解决的事就不排队）。

---
# 二、线程：并发的基本单位（Thread / Runnable / 中断 / ThreadLocal）

## 2.1 产生背景与定位

**白话**：操作系统提供"进程"作为资源分配单位，但进程太重（独立内存空间、切换开销大），于是 OS 又提供了更轻的**线程**：同一进程内的多个执行流，共享堆内存、各自有栈和寄存器。JVM 把 OS 线程包装成 `java.lang.Thread`——JDK 21 之前是严格的一对一（一个 Java 线程 = 一个 OS 内核线程）；JDK 21 起新增虚拟线程，由 JVM 调度、可百万级创建（见第十章）。本章先讲平台线程这一地基。

线程要解决的问题是：**让"同时做多件事"成为可能**。但多个线程共享堆内存，就回到了第一章的三大问题——所以 Thread 类除了"启动执行流"，还自带了一组配套机制：等待（join）、睡眠（sleep）、协作式中断（interrupt），它们全是后续一切并发工具的原材料。

## 2.2 启动一个线程：start() 与 run() 的本质区别

【源码证据】`java.base/java/lang/Thread.java:1521-1528`（start）与 `Thread.java:1578-1598`（run）

```java
    public void start() {
        synchronized (this) {
            // zero status corresponds to state "NEW".
            if (holder.threadStatus != 0)
                throw new IllegalThreadStateException();
            start0();
        }
    }
    private native void start0();        // Thread.java:1563

    @Override
    public void run() {
        Runnable task = holder.task;
        if (task != null) {
            Object bindings = scopedValueBindings();
            runWith(bindings, task);
        }
    }
```

两个要点：

1. **start() 不是 run()**。`start()` 通过 native 方法 `start0()` 请求操作系统创建新线程，新线程再回来调用 `run()`；直接调 `run()` 只是普通方法调用，在当前线程里执行，没有任何并发。
2. **一个 Thread 只能 start 一次**。`holder.threadStatus != 0` 说明已经不是 NEW 状态，直接抛 `IllegalThreadStateException`。注意 JDK 21 用 `synchronized(this)` 保证检查的原子性。

三种创建方式（小白必会）：

```java
// 方式一：继承 Thread
class MyThread extends Thread {
    @Override public void run() { System.out.println("继承方式：" + Thread.currentThread().getName()); }
}
new MyThread().start();

// 方式二：传 Runnable（推荐：任务与线程解耦，线程池只认 Runnable）
Thread t = new Thread(() -> System.out.println("Runnable 方式"), "worker-1");
t.start();

// 方式三：Callable + FutureTask（需要返回值时）
Callable<Integer> calc = () -> { Thread.sleep(100); return 42; };
FutureTask<Integer> ft = new FutureTask<>(calc);
new Thread(ft, "calc").start();
System.out.println(ft.get());        // 阻塞直到结果出来：42（FutureTask 详见第十章）
```

方式三是理解后面 `Future`/线程池的钥匙：**任务（Callable）和"任务的将来结果"（FutureTask）分离**，线程只负责跑任务，结果由 FutureTask 这个信封保管。

## 2.3 六种线程状态：Thread.State

**白话**：排查并发问题时第一件事就是看线程"卡在哪了"。JDK 把线程状态收敛成一个枚举，jstack、线程池源码全都围绕它。

【源码证据】`java.base/java/lang/Thread.java:2665-2728`（State 枚举，注释要点摘录）

```java
    public enum State {
        /** Thread state for a thread which has not yet started. */
        NEW,
        /** A thread in the runnable state is executing in the Java virtual
         *  machine but it may be waiting for other resources from the
         *  operating system such as processor. */
        RUNNABLE,
        /** A thread in the blocked state is waiting for a monitor lock
         *  to enter a synchronized block/method or reenter a synchronized
         *  block/method after calling Object.wait. */
        BLOCKED,
        /** WAITING: Object.wait() / Thread.join() / LockSupport.park() */
        WAITING,
        /** TIMED_WAITING: Thread.sleep / wait(long) / join(long) /
         *  LockSupport.parkNanos / parkUntil */
        TIMED_WAITING,
        TERMINATED;
    }
```

| 状态 | 怎么进去 | 怎么出来 | 常见卡点 |
|---|---|---|---|
| NEW | `new Thread()` 尚未 start | start() | —— |
| RUNNABLE | 就绪/正在运行 | —— | —— |
| BLOCKED | 进不去 synchronized | 拿到锁 | 锁竞争（synchronized 专属状态） |
| WAITING | `wait()`/`join()`/`LockSupport.park()` | notify/unpark/对方终止 | 死等 |
| TIMED_WAITING | 带 timeout 的同类调用 | 超时或被唤醒 | 定时等待 |
| TERMINATED | run() 执行完 | —— | —— |

两个易错点：**RUNNABLE ≠ 正在占 CPU**（可能只是在等 CPU 时间片）；**BLOCKED 只对应 synchronized**——`ReentrantLock` 排队等待的线程显示的是 WAITING（底层是 `LockSupport.park`），这是排查锁问题时的重要区别。

## 2.4 等待一个线程：join() 就是循环 wait()

【源码证据】`java.base/java/lang/Thread.java:2055-2083`（join(long)）

```java
    public final void join(long millis) throws InterruptedException {
        if (millis < 0)
            throw new IllegalArgumentException("timeout value type");
        if (this instanceof VirtualThread vthread) {
            if (isAlive()) {
                long nanos = MILLISECONDS.toNanos(millis);
                vthread.joinNanos(nanos);
            }
            return;
        }
        synchronized (this) {
            if (millis > 0) {
                if (isAlive()) {
                    final long startTime = System.nanoTime();
                    long delay = millis;
                    do {
                        wait(delay);
                    } while (isAlive() && (delay = millis -
                             NANOSECONDS.toMillis(System.nanoTime() - startTime)) > 0);
                }
            } else {
                while (isAlive()) {
                    wait(0);
                }
            }
        }
    }
```

（原文行号 2055-2083，注释已略作压缩；"timeout value is negative" 为原文。）

**join() 的原理让人恍然大悟**：它就是 `synchronized(this)` 持有目标线程对象，循环 `wait()`——因为**线程终止时会自动调用 `this.notifyAll()`**（由 JVM 完成），所以"等待目标线程死亡"就变成了"在目标线程对象上 wait"。这也解释了为什么 `Thread` 对象可以被当作锁用（虽然不推荐）。while 循环 + 重算剩余时间，是处理**虚假唤醒**的标准写法（第四章展开）。

## 2.5 中断：不是强杀，而是"递纸条"

**白话**：没有任何 Java API 能强行停止另一个线程（`Thread.stop()` 因会破坏数据一致性早已废弃）。JDK 的方案是**协作式中断**：`t.interrupt()` 只是给 t 设一个标志位（递纸条），t 自己决定看到纸条后怎么办——停下来？忽略？清理后退出？

【源码证据】`java.base/java/lang/Thread.java:1712-1730`（interrupt）、`1745-1747`（interrupted）、`1758-1760`（isInterrupted）

```java
    public void interrupt() {
        if (this != Thread.currentThread()) {
            checkAccess();
        }
        // Setting the interrupt status must be done before reading nioBlocker.
        interrupted = true;
        interrupt0();  // inform VM of interrupt
        // thread may be blocked in an I/O operation
        ...
    }

    public static boolean interrupted() {
        return currentThread().getAndClearInterrupt();   // 读并清除
    }

    public boolean isInterrupted() {
        return interrupted;                              // 只读不清除
    }
```

三个要点：

1. `interrupted` 是 Thread 上的 `volatile boolean`（`Thread.java:243`），所以 `interrupt()` 的设置对目标线程立即可见——这就是 volatile 的最小应用（第三章）。
2. `interrupt()` 除了设标志，还会**唤醒在 wait/sleep/join/park 中阻塞的线程**（`interrupt0()` 通知 JVM），让它们抛 `InterruptedException`——所以叫"中断"而不是"设标志"：**它既影响自旋的线程，也解救阻塞的线程**。
3. `Thread.interrupted()`（静态、清除标志）与 `t.isInterrupted()`（实例、不清除）是两个方法，初学者极易混。

标准的中断响应模板——**要么恢复中断标志并退出，要么抛出异常**：

```java
// 模板一：循环里协作检查（CPU 密集型任务）
public void run() {
    while (!Thread.currentThread().isInterrupted()) {   // 注意用实例方法，不清标志
        doWork();                                        // 一个小步骤
    }
    // 被打断后自然退出循环，可在此做清理
}

// 模板二：阻塞方法抛 InterruptedException 时，必须重新打上标志再退出
try {
    Thread.sleep(1000);
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();   // 恢复中断状态，让上层感知（吞掉异常是并发 bug 高发点）
    return;
}
```

## 2.6 ThreadLocal：不共享，就没有竞争

**产生背景**：解决竞争有两条路——加锁（争）和**不共享**（各自一份）。SimpleDateFormat 非线程安全、数据库连接不能被两个线程同时用、用户上下文要贯穿一次请求的所有方法调用……这些场景根本不想加锁，只想"每个线程有自己的副本"。

【源码证据】`java.base/java/lang/ThreadLocal.java:38-44`（类头 javadoc 关键句）

```java
 * This class provides thread-local variables.  These variables differ from
 * their normal counterparts in that each thread that accesses one (via its
 * get or set method) has its own, independently initialized copy of the variable.
```

**原理（先白话）**：数据不放在 ThreadLocal 里，而是放在**每个线程自己身上**——`Thread` 对象里有一个 `ThreadLocal.ThreadLocalMap` 字段，以 ThreadLocal 实例为 key、你的值为 value。`tl.set(v)` 就是往**当前线程的 map** 里写，`tl.get()` 就是从当前线程的 map 里读。同一个 ThreadLocal 在 100 个线程里 get，拿到的是 100 份独立的值。

【源码证据】`java.base/java/lang/ThreadLocal.java:171-195`（get）、`250-269`（set）

```java
    public T get() {
        return get(Thread.currentThread());
    }
    private T get(Thread t) {
        ThreadLocalMap map = getMap(t);
        if (map != null) {
            ThreadLocalMap.Entry e = map.getEntry(this);   // 以 this（ThreadLocal）为 key
            if (e != null) {
                T result = (T) e.value;
                return result;
            }
        }
        return setInitialValue(t);
    }

    private void set(Thread t, T value) {
        ThreadLocalMap map = getMap(t);
        if (map != null) {
            map.set(this, value);
        } else {
            createMap(t, value);
        }
    }
```

**三个精妙设计（也是面试高频）**：

1. **哈希魔数**：每个 ThreadLocal 实例的 hashCode 按 `HASH_INCREMENT = 0x61c88647`（黄金分割数）递增（`ThreadLocal.java:107-111`），让 2 的幂长度的开放寻址表分布近乎完美，减少冲突。
2. **弱引用 key，防泄漏了一半**：`ThreadLocalMap.Entry` 继承 `WeakReference<ThreadLocal<?>>`（`ThreadLocal.java:371-389`）——外部把 ThreadLocal 变量置 null 后，key 会被 GC，Entry 变成"key 为 null 的 stale entry"；map 在 get/set 时会顺手清理（`expungeStaleEntry`，`ThreadLocal.java:669`；`cleanSomeSlots`，`ThreadLocal.java:729`）。
3. **但 value 仍可能泄漏**：线程池里线程长活不死，stale entry 的清理又只在"恰好用到这个 ThreadLocal 或扩容"时发生——**用完必须 `remove()`**，这是 ThreadLocal 唯一的硬性使用纪律。

```java
// 标准用法：static final + withInitial + try/finally remove
private static final ThreadLocal<SimpleDateFormat> DF =
        ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"));

public String format(Date d) {
    try {
        return DF.get().format(d);      // 每个线程拿到自己的 SimpleDateFormat
    } finally {
        // 短任务场景建议 remove；池化线程长活时若该 TL 之后还用，可不清
        DF.remove();
    }
}
```

**InheritableThreadLocal**：子线程想继承父线程的副本怎么办？`Thread` 构造时会浅拷贝父线程的 `inheritableThreadLocals` map：

【源码证据】`java.base/java/lang/Thread.java:744-758`（构造器中复制继承变量）

```java
        // thread locals
        if (!attached) {
            if ((characteristics & NO_INHERIT_THREAD_LOCALS) == 0) {
                ThreadLocal.ThreadLocalMap parentMap = parent.inheritableThreadLocals;
                if (parentMap != null && parentMap.size() > 0) {
                    this.inheritableThreadLocals = ThreadLocal.createInheritedMap(parentMap);
                }
                ...
            }
        }
```

注意两点：是**创建线程那一刻**的快照（之后父线程再改，子线程看不见）；线程池里线程是复用的，`InheritableThreadLocal` 会拿到上一个任务的残留——池化场景请用 `TransmittableThreadLocal`（阿里开源）或 ScopedValue（JDK 21 预览）。

## 2.7 小结

- `start()` 才创建线程，`run()` 只是普通方法；一个 Thread 只能 start 一次。
- 六状态枚举是排查问题的地图：`BLOCKED` 对应 synchronized，`WAITING` 对应 wait/join/park。
- `join()` = synchronized(this) + while 循环 wait()，线程终止时 JVM 自动 notifyAll。
- 中断是协作式的：`interrupt()` 设 volatile 标志并唤醒阻塞；捕获 `InterruptedException` 后要恢复中断标志。
- `ThreadLocal` 用"每线程一份"消灭共享；弱引用 key + stale entry 清理只解决一半泄漏，**用完 remove() 是纪律**。

---
# 三、可见性与有序性：volatile——最轻量的同步手段

## 3.1 产生背景与解决的问题域

**白话**：看一个最小死循环 bug：

```java
static boolean stop = false;              // 普通 boolean
public static void main(String[] args) throws Exception {
    Thread t = new Thread(() -> {
        while (!stop) { /* 空转 */ }      // JIT 可能把它优化成 while(true)
        System.out.println("stopped!");
    });
    t.start();
    Thread.sleep(100);
    stop = true;                          // 主线程改标志
    System.out.println("main 已设置 stop=true，但 t 可能永远停不下来");
}
```

主线程明明把 `stop` 改成了 true，后台线程却常常**永远看不到**。原因在第三章开头那张表里：编译器发现单线程视角下 `!stop` 每次都一样，把它提升到循环外（有序性优化）；每个核心的缓存里 stop 还是 false（可见性问题）。JMM 并没有要求普通变量"改了马上对别人可见"。

**volatile 的承诺**：对一个变量加 volatile，JVM 保证①写立即对所有线程可见（happens-before：volatile 写 先行于 后续任意线程对它的 volatile 读）；②禁止它与周围指令重排序；③读/写本身是原子的（但 `volatile int i; i++` 依然不原子！自增是读-改-写三步，见第七章）。

volatile 不能直接在源码里"看到"（它是字段修饰符，由 JVM 内建实现），但 JDK 源码里处处是它最典型的两个用法——**状态标志**和**安全发布**：

- `Thread` 的中断标志：`volatile boolean interrupted;`（`Thread.java:243`）——`interrupt()` 设置它（2.5 节源码），任何 `isInterrupted()` 读取它，靠 volatile 保证"递纸条"立刻可见。
- `AbstractQueuedSynchronizer.state`、`ConcurrentHashMap.table`、`FutureTask.state`……第五~十章所有核心字段全是 volatile：**volatile 是 j.u.c 万丈高楼的地基**。

## 3.2 使用示例一：停止标志（上面 bug 的修复就是把声明改成 volatile）

```java
static volatile boolean stop = false;    // 只改这一处
// ... 其余代码同上，后台线程将稳定输出 "stopped!"
```

## 3.3 使用示例二：双检锁单例（DCL）——volatile 防的是"半成品对象"

```java
public class Singleton {
    private static volatile Singleton instance;      // 没有 volatile 的 DCL 是经典 bug

    private Singleton() { this.data = loadData(); }  // 构造耗时
    private int data;

    public static Singleton getInstance() {
        if (instance == null) {                      // 第一次检查：无锁快路径
            synchronized (Singleton.class) {
                if (instance == null) {              // 第二次检查：持锁确认
                    instance = new Singleton();      // 问题行！
                }
            }
        }
        return instance;
    }
}
```

`new Singleton()` 实际是三步：①分配内存 → ②执行构造器 → ③把引用指向内存。②③可能被重排为①③②——另一个线程在第一次检查时拿到**非 null 但字段还没初始化完**的实例。volatile 写保证③之前的②对读者可见（happens-before）。这也是 JEP 129（JDK 8 支持的安全发布）之外最著名的有序性案例。

> 顺带说明：volatile 有个"加强版兄弟"——`final` 字段的安全发布保证：构造器中给 final 字段赋值后，其他线程看到的该字段一定不会是默认值（JLS §17.5）。这也是不可变对象（String、各种 immutable 类）能被放心共享的原因。

## 3.4 小结

- volatile 解决**可见性与有序性**，不解决复合操作的**原子性**（`i++` 依然要靠锁或 CAS）。
- 两大惯用法：状态标志（读多写少）、DCL/安全发布。
- j.u.c 内部的所有核心状态字段（AQS.state、CHM.table、FutureTask.state）都是 volatile——它是一切上层工具的地基。

---

# 四、互斥与等待/通知：synchronized 与对象的 wait/notify

## 4.1 产生背景与解决的问题域

**白话**：volatile 管不住"读-改-写"，需要一个工具保证**一段代码同一时刻只有一个线程在执行**——这就是互斥锁。JDK 1.0 就内置了最简单的一种：`synchronized`，锁的对象是**任意一个 Java 对象**（每个对象头里都藏着一个监视器 monitor）。同时它配套了线程间等待/通知机制：`wait()`（我先歇着，条件不满足）/ `notify()`（条件可能好了，叫醒一个）。

## 4.2 synchronized 在字节码里长什么样（实测）

【源码证据】（本机实测，JDK 21 javac + `javap -c -v`）同步方法与同步块是两种编译方式：

```
  public synchronized void incMethod();
    flags: (0x0021) ACC_PUBLIC, ACC_SYNCHRONIZED     ← 方法级：flag 标记，进入/退出由 JVM 隐式处理

  public void incBlock();
    flags: (0x0001) ACC_PUBLIC
    Code:
       0: aload_0
       1: dup
       2: astore_1
       3: monitorenter                                  ← 拿锁
       4: aload_0
       ...  count++ 的实际指令 ...
      14: aload_1
      15: monitorexit                                   ← 正常路径释放
      16: goto          24
      19: astore_2                                      ← 异常路径
      20: aload_1
      21: monitorexit                                   ← 保证异常也释放锁！
      22: aload_2
      23: athrow
      24: return
    Exception table:
       from    to  target type
           4    16    19   any
          19    22    19   any
```

这张字节码图解释了两个事实：① synchronized 的解锁**天然自动**（编译器生成两个 monitorexit + 异常表，这就是"不需要 finally unlock"的原因，也是它比手写 Lock 更抗造的地方）；② 锁的粒度是"对象"，`synchronized(this)`、`synchronized(static.class)`（锁 Class 对象）、`synchronized` 实例方法（锁 this）锁的是不同对象，**用错对象=没加锁**。

**JVM 层实现概览**（源码在 JVM 而非 Java 层，这里给结论）：无竞争时走**轻量级锁**（CAS 把锁记录压入线程栈，本质上就是一次 CAS），只有真竞争起来才膨胀为**重量级锁**（OS 互斥量 + 线程挂起）。曾经还有第三档"偏向锁"（假设永远只有一个线程访问，连 CAS 都省了），但它维护成本高于收益，JDK 15 起默认禁用并废弃（JEP 374），后续版本已移除——所以现代 JDK 上，**synchronized 没那么慢**，很多场景和 ReentrantLock 性能相当。

## 4.3 wait/notify：锁对象自带的"叫号系统"

【源码证据】`java.base/java/lang/Object.java:260-293`（notify 的 javadoc 关键段与声明）、`Object.java:363-378`（wait(long)）

```java
    // notify(): 唤醒一个在该对象 monitor 上等待的线程（选择任意一个，由实现决定）
    // javadoc 原文关键句：
    //   The awakened thread will not be able to proceed until the current
    //   thread relinquishes the lock on this object. ...
    //   This method should only be called by a thread that is the owner
    //   of this object's monitor.
    @IntrinsicCandidate
    public final native void notify();          // Object.java:292-293

    public final void wait(long timeoutMillis) throws InterruptedException {   // Object.java:363
        long comp = Blocker.begin();
        try {
            wait0(timeoutMillis);               // 真正的 native 实现
        } catch (InterruptedException e) {
            Thread thread = Thread.currentThread();
            if (thread.isVirtual())
                thread.getAndClearInterrupt();
            throw e;
        } finally {
            Blocker.end(comp);
        }
    }
```

**规则与原理（白话）**：每个对象都有三样隐形资产——**锁**（同一时刻最多一个线程持有）、**锁等待队列**（没抢到锁的线程在这排队，状态 BLOCKED）、**等待集合 wait set**（调用了 wait() 的线程在这歇着，状态 WAITING）。`wait()` 做**一件原子的事**：释放锁 + 进等待集合；`notify()` 把一个线程从等待集合挪回锁等待队列（它要重新抢锁才能继续）。

四条铁律（全部来自上面 javadoc 和 JLS）：

1. **必须持有该对象的 monitor 才能调用** wait/notify，否则 `IllegalMonitorStateException`——所以 wait/notify 必须写在 synchronized 块内。
2. `wait()` 释放锁，`notify()` 不释放锁——所以 notify 完要尽快退出 synchronized，否则被唤醒的线程还是拿不到锁。
3. **虚假唤醒**：wait 可能"无理由"返回（OS 层面允许），也可能 notify 先于 wait 到达（错过叫号）——所以必须用 `while(条件不满足) wait()`，永远不要用 if。
4. 优先 `notifyAll()`：notify 只随机唤醒一个，条件判断又因线程而异时容易"叫错人"造成全员永久等待。

## 4.4 使用示例：synchronized 版生产者-消费者（把上面的铁律全用上）

```java
public class SyncBuffer {
    private final int[] items = new int[10];
    private int putIdx, takeIdx, count;

    public synchronized void put(int v) throws InterruptedException {
        while (count == items.length)      // 铁律3：while 判断条件
            wait();                        // 铁律1：持锁才能调；wait 释放锁
        items[putIdx] = v;
        if (++putIdx == items.length) putIdx = 0;
        count++;
        notifyAll();                       // 铁律4：唤醒所有等待者（含消费者）
    }

    public synchronized int take() throws InterruptedException {
        while (count == 0)
            wait();
        int v = items[takeIdx];
        if (++takeIdx == items.length) takeIdx = 0;
        count--;
        notifyAll();
        return v;
    }

    public static void main(String[] args) {
        SyncBuffer buf = new SyncBuffer();
        for (int i = 1; i <= 3; i++) {                          // 3 个生产者
            int id = i;
            new Thread(() -> { try { for (int j = 0; ; j++) buf.put(id * 100 + j); } catch (InterruptedException ignored) {} }, "P" + id).start();
        }
        new Thread(() -> { try { for (;;) System.out.println("消费: " + buf.take()); } catch (InterruptedException ignored) {} }, "C").start();
    }
}
```

这个手写版跑通之后，再去看 JDK 的标准答案 `ArrayBlockingQueue`（第八章）——它把这个模式做成了 `ReentrantLock + 两个 Condition`，把"生产者等不满"和"消费者等不空"分成两个等待集合，不再无差别 notifyAll。

## 4.5 synchronized 的四大局限（引出第五章）

| 局限 | 场景 | 需要的能力 |
|---|---|---|
| ① 拿不到锁就死等 | 接口慢、死锁 | 尝试获取 `tryLock` / 超时获取 |
| ② 等锁过程不可中断 | 死锁线程无法救援 | `lockInterruptibly()` |
| ③ 不区分公平 | 后来者插队 | 公平锁 |
| ④ 只有一个等待集合 | 生产者消费者互相 notifyAll | 多个 Condition 分别等待 |

这四条正是 `Lock` 接口存在的意义；而 `Lock` 的实现，建立在第五章的两个原语之上：`LockSupport`（真正的阻塞/唤醒）与 AQS（排队与状态管理）。

## 4.6 小结

- synchronized = 对象监视器锁：同步方法（ACC_SYNCHRONIZED）与同步块（monitorenter/monitorexit + 异常表），**异常路径也保证解锁**。
- 无竞争时是轻量级 CAS，竞争时膨胀为 OS 互斥量；偏向锁已成历史（JEP 374）。
- wait/notify 是锁对象自带的等待/通知机制：必须持锁调用、wait 释放锁 notify 不释放、while 防虚假唤醒、优先 notifyAll。
- 它的四大局限（不可尝试/不可中断/不公平/单一等待集合）正是下一章 Lock 体系的出发点。

---
# 五、显式锁体系与 AQS：j.u.c 的心脏（LockSupport / Lock / Condition / ReentrantLock）

## 5.1 LockSupport：一切阻塞与唤醒的底层积木

**产生背景**：wait/notify 有三个先天缺陷——必须持有 monitor 才能调用；每个对象只有一个等待集合；notify 是"失忆"的（先 notify 后 wait 就错过）。JDK 5 需要一个**不依赖 monitor、可成对、可先叫后等**的阻塞原语给 AQS 用，这就是 `LockSupport`。

【源码证据】`java.base/java/util/concurrent/locks/LockSupport.java:45-63`（javadoc 关键段）

```java
    * <p>This class associates, with each thread that uses it, a permit
    * (in the sense of the Semaphore class). A call to park will return
    * immediately if the permit is available, consuming it in the process;
    * otherwise it may block. A call to unpark makes the permit available,
    * if it was not already available. (Unlike with Semaphores though,
    * permits do not accumulate. There is at most one.)
```

**原理（白话）**：每个线程有一张最多一张的"停车券"（permit）。`park()`：有券就消费掉立刻返回，没券就睡觉；`unpark()`：把券发出去（对方还没睡也先把券给着）。**与 wait/notify 的关键差别**：unpark 先于 park 调用也不会丢——券不会累积，但可以"预存"。所以用 park/unpark 搭建的排队系统不需要担心"唤醒丢失"。

【源码证据】`java.base/java/util/concurrent/locks/LockSupport.java:214-226`（park(Object)）

```java
    public static void park(Object blocker) {
        Thread t = Thread.currentThread();
        setBlocker(t, blocker);          // 记录"被谁阻塞"，供诊断工具显示
        try {
            if (t.isVirtual()) {
                VirtualThreads.park();   // 虚拟线程：走 JVM 的挂起/卸载路径（第十章）
            } else {
                U.park(false, 0L);       // 平台线程：Unsafe native，OS 层挂起
            }
        } finally {
            setBlocker(t, null);
        }
    }
```

park 还会**响应中断但不抛异常**——醒来后由调用方自己 `Thread.interrupted()` 检查，这正是 AQS 主循环需要的语义。`park(Object blocker)` 传入的 blocker 会被 jstack 显示为阻塞原因，排查问题必备。

```java
// 5 行示例：unpark 先于 park 也不会丢
Thread worker = new Thread(() -> {
    System.out.println("干活前先 park");
    LockSupport.park();                      // 有预存券 → 直接通过
    System.out.println("醒来继续干活");
});
worker.start();
LockSupport.unpark(worker);                  // 故意先发券
// 输出顺序稳定：两行都会打印，且不会卡住
```

## 5.2 Lock 接口：synchronized 四大局限的正面回答

【源码证据】`java.base/java/util/concurrent/locks/Lock.java:169-186、234、263、323、337-358`

```java
public interface Lock {
    void lock();                                              // :169 起，死等获取
    void lockInterruptibly() throws InterruptedException;     // :234，可中断获取
    boolean tryLock();                                        // :263，立刻返回成败
    boolean tryLock(long time, TimeUnit unit) throws InterruptedException;  // :323，限时获取
    void unlock();                                            // :337
    Condition newCondition();                                 // :358，创建绑定条件
}
```

六个方法就是四大局限的逐条回答。标准使用姿势（javadoc 强调的 try-finally 惯用法，`Lock.java:81-88`）：

```java
Lock l = ...;
l.lock();
try {
    // 访问共享资源
} finally {
    l.unlock();          // 手动锁必须手动还，漏掉 unlock 是 ReentrantLock 最常见事故
}
```

## 5.3 AQS：用一个 state + 一条队列，长出所有的锁

**产生背景**：JDK 5 之前每个同步工具都自己实现排队、挂起、唤醒，重复造轮子还容易错。Doug Lea 的解法是抽取共同骨架：**任何一个阻塞式同步器，本质都是"一个表示资源的状态变量 + 一条 FIFO 等待队列"**——锁的 state 是 0/重入次数，信号量的 state 是剩余许可数，Latch 的 state 是剩余计数。把这个骨架抽出来，就是 `AbstractQueuedSynchronizer`（AQS）。

> **版本差异必读**：很多教材讲的是 JDK 8 经典版 AQS（字段叫 `waitStatus/thread`，方法叫 `addWaiter/shouldParkAfterFailedAcquire`）。JDK 14 起内部被重写：字段改名（`status/waiter`），排队逻辑并入一个主循环方法 `acquire(...)`。**外部语义完全不变**，本文按 JDK 21 实码讲解，并随时给出新旧对照，读者对照老书阅读时不会迷路。

【源码证据】`java.base/java/util/concurrent/locks/AbstractQueuedSynchronizer.java:46-59`（类头 javadoc）、`136-152`（独占模式核心伪代码，原文）

```java
/**
 * Provides a framework for implementing blocking locks and related
 * synchronizers (semaphores, events, etc) that rely on
 * first-in-first-out (FIFO) wait queues.  This class is designed to
 * be a useful basis for most kinds of synchronizers that rely on a
 * single atomic int value to represent state. ...
 */
    // 独占同步的核心（javadoc 中的原文伪代码）：
    //   Acquire:
    //     while (!tryAcquire(arg)) {
    //        enqueue thread if it is not already queued;
    //        possibly block current thread;
    //     }
    //   Release:
    //     if (tryRelease(arg))
    //        unblock the first queued thread;
```

**三要素逐一来看源码**：

**① 状态 state**（`AQS.java:524-570`）：

```java
    private transient volatile Node head;    // 等待队列头（惰性初始化）
    private transient volatile Node tail;    // 等待队列尾
    private volatile int state;              // 同步状态：资源的有无/多少

    protected final boolean compareAndSetState(int expect, int update) {
        return U.compareAndSetInt(this, STATE, expect, update);
    }
```

**② 节点 Node 与两种模式**（`AQS.java:461-522`）：节点里最重要的是 `Thread waiter`（等的是谁）和 `volatile int status`（含 `WAITING`/`CANCELLED`/`COND` 三个标志位）；独占/共享不再用标志位表示，而是两个空子类：

```java
    abstract static class Node {
        volatile Node prev;       // initially attached via casTail
        volatile Node next;       // visibly nonnull when signallable
        Thread waiter;            // visibly nonnull when enqueued
        volatile int status;      // written by owner, atomic bit ops by others
        ...
    }
    static final class ExclusiveNode extends Node { }   // 独占节点（旧版 waitStatus + EXCLUSIVE）
    static final class SharedNode  extends Node { }     // 共享节点（旧版 SHARED 标记）
```

**③ 模板方法 acquire/release**（`AQS.java:1022-1025、1092-1098`）：

```java
    public final void acquire(int arg) {
        if (!tryAcquire(arg))                     // ① 先试一次（子类实现语义）
            acquire(null, arg, false, false, false, 0L);   // ② 失败则走主循环：排队+阻塞
    }

    public final boolean release(int arg) {
        if (tryRelease(arg)) {                    // ① 子类决定"是否真的释放了资源"
            signalNext(head);                     // ② 唤醒队首的下一个线程
            return true;
        }
        return false;
    }
```

`tryAcquire/tryRelease` 就是 AQS 留给子类的**唯一语义口子**；排队、挂起、唤醒、取消全部由框架代劳。主循环 `acquire(Node,int,boolean,boolean,boolean,long)`（`AQS.java:693-798`）用一个大 for 循环实现了伪代码的全部内容，关键分支（摘录）：

```java
        for (;;) {
            if (!first && (pred = (node == null) ? null : node.prev) != null &&
                !(first = (head == pred))) {
                ...                               // 还没轮到自己：确认前驱有效
            }
            if (first || pred == null) {          // 自己是队首（或尚未入队）→ 再试 tryAcquire
                boolean acquired;
                try {
                    if (shared) acquired = (tryAcquireShared(arg) >= 0);
                    else        acquired = tryAcquire(arg);
                } catch (Throwable ex) { cancelAcquire(node, interrupted, false); throw ex; }
                if (acquired) {
                    if (first) {                  // 成功：把自己变成新的 head（出队）
                        node.prev = null;
                        head = node;
                        pred.next = null;
                        node.waiter = null;
                        if (shared) signalNextIfShared(node);   // 共享模式：级联唤醒后续共享节点
                        if (interrupted) current.interrupt();
                    }
                    return 1;
                }
            }
            Node t;
            if ((t = tail) == null) {             // 队列未初始化：初始化
                if (tryInitializeHead() == null) return acquireOnOOME(shared, arg);
            } else if (node == null) {            // 还没建节点：建（旧版 addWaiter 的一半）
                node = (shared) ? new SharedNode() : new ExclusiveNode();
            } else if (pred == null) {            // 入队：CAS 挂到队尾
                node.waiter = current;
                node.setPrevRelaxed(t);
                if (!casTail(t, node)) node.setPrevRelaxed(null);
                else t.next = node;
            } else if (first && spins != 0) {
                --spins; Thread.onSpinWait();     // 队首自旋几次，减少不必要的 park
            } else if (node.status == 0) {
                node.status = WAITING;            // 先标记"准备睡"（旧版 shouldParkAfterFailedAcquire）
            } else {
                ...
                if (!timed) LockSupport.park(this);      // 旧版 parkAndCheckInterrupt
                else if ((nanos = time - System.nanoTime()) > 0L)
                    LockSupport.parkNanos(this, nanos);
                else break;
                node.clearStatus();
                if ((interrupted |= Thread.interrupted()) && interruptible) break;
            }
        }
        return cancelAcquire(node, interrupted, interruptible);
```

而"唤醒下家"就是一行核心（`AQS.java:635-647`，旧版 unparkSuccessor）：

```java
    private static void signalNext(Node h) {
        Node s;
        if (h != null && (s = h.next) != null && s.status != 0) {
            s.getAndUnsetStatus(WAITING);      // 清掉 WAITING 标记，防 park 竞态
            LockSupport.unpark(s.waiter);      // 唤醒（5.1 的积木在这里发挥作用）
        }
    }
```

**一张图收拢 AQS 主流程**：

```
        tryAcquire() 成功？──────── 是 ──▶ 拿到资源，直接返回
              │否
              ▼
        创建 Node ──CAS 挂到队尾──▶ while(自己不是队首 || tryAcquire 失败){
              ▲                        标记 WAITING → LockSupport.park() 睡觉
              │                        被 unpark 醒来 → 回到循环再试
              └────────────────────  }
                                        release(): tryRelease 成功 → signalNext(head) 唤醒下家
```

**共享模式**与独占共用同一条队列，只是语义口子换成 `tryAcquireShared`（返回值 >=0 表示成功，剩余量还会被"传播"给后续共享节点，`AQS.java:649-657` 的 `signalNextIfShared`）——这一个口子将来长出了 Semaphore（剩余许可数）、CountDownLatch（剩余计数）、读写锁的读锁（共享读者数）。

## 5.4 ReentrantLock：AQS 的第一个孩子

**产生背景与问题域**：synchronized 的四大局限（4.5 节）需要一个"可尝试、可超时、可中断、可公平、可多条件"的独占锁。

【源码证据】`java.base/java/util/concurrent/locks/ReentrantLock.java`。三个内部类：`Sync`（基类）→ `NonfairSync` / `FairSync`。state 的语义：**0 = 无人持有；N = 当前持有者重入了 N 次**。

```java
    // Sync.tryLock()：非公平快路径，等价旧版 nonfairTryAcquire —— ReentrantLock.java:121-140
    final boolean tryLock() {
        Thread current = Thread.currentThread();
        int c = getState();
        if (c == 0) {
            if (compareAndSetState(0, 1)) {          // CAS 抢锁：0→1
                setExclusiveOwnerThread(current);    // 记下持有者（AbstractOwnableSynchronizer）
                return true;
            }
        } else if (getExclusiveOwnerThread() == current) {   // 是自己 → 重入
            if (++c < 0) throw new Error("Maximum lock count exceeded");
            setState(c);                             // 计数 +1，不需要 CAS（只有持有者能改）
            return true;
        }
        return false;
    }

    // NonfairSync.initialTryLock()：非公平的 lock() —— 上来就抢，不看队列 —— :217-231
    final boolean initialTryLock() {
        Thread current = Thread.currentThread();
        if (compareAndSetState(0, 1)) { // first attempt is unguarded
            setExclusiveOwnerThread(current);
            return true;
        } else if (getExclusiveOwnerThread() == current) { ... return true; }
        else return false;
    }

    // FairSync.tryAcquire()：公平版的唯一差别 —— ReentrantLock.java:270-284
    protected final boolean tryAcquire(int acquires) {
        if (getState() == 0 && !hasQueuedPredecessors() &&   // ← 队列里有人在我前面？那就排队
            compareAndSetState(0, acquires)) {
            setExclusiveOwnerThread(Thread.currentThread());
            return true;
        }
        return false;
    }

    // Sync.tryRelease()：重入计数减到 0 才真正释放 —— :171-181
    protected final boolean tryRelease(int releases) {
        int c = getState() - releases;
        if (getExclusiveOwnerThread() != Thread.currentThread())
            throw new IllegalMonitorStateException();
        boolean free = (c == 0);
        if (free) setExclusiveOwnerThread(null);
        setState(c);
        return free;                       // false = 还在重入中，不唤醒任何人
    }
```

**可重入的实现**一目了然：同一线程再次加锁只是 `state + 1`，unlock 时减 1，减到 0 才放锁。**公平与否只差一行**：公平版在抢锁前先问 `hasQueuedPredecessors()`（`AQS.java:1318-1325`，队列里是否有排在我前面的线程）。

```java
// 使用示例一：tryLock 破解死锁（两个转账互等 → 拿不到就退回重试）
public boolean transfer(Account from, Account to, int amount) throws Exception {
    while (true) {
        if (from.lock.tryLock(100, TimeUnit.MILLISECONDS)) {    // 限时拿 from
            try {
                if (to.lock.tryLock(100, TimeUnit.MILLISECONDS)) {  // 限时拿 to
                    try {
                        from.debit(amount); to.credit(amount);
                        return true;
                    } finally { to.lock.unlock(); }
                }
            } finally { from.lock.unlock(); }
        }
        // 随机退避后重试，双方总有一个先让步 → 无死锁
        Thread.sleep(50);
    }
}

// 使用示例二：lockInterruptibly —— 慢请求可以被取消
ReentrantLock lock = new ReentrantLock();
Thread t = new Thread(() -> {
    try {
        lock.lockInterruptibly();     // 等锁期间可被 interrupt 打断（synchronized 做不到）
        try { /* 临界区 */ } finally { lock.unlock(); }
    } catch (InterruptedException e) {
        System.out.println("等锁时被取消，放弃任务");
    }
});
lock.lock();                            // 主线程故意占住锁
t.start();
Thread.sleep(500);
t.interrupt();                          // t 干净退出，而不是永远卡死
```

## 5.5 Condition：把一个等待集合拆成多个

**产生背景**：生产者-消费者场景里 wait/notifyAll 会把"等空位的"和"等数据的"一起叫醒、醒来又睡下（惊群）。`Condition` 把 wait/notify 因子分解开：**一把 Lock 可以造多个 Condition，各等各的条件，signal 只叫醒该叫的人**。

【源码证据】`java.base/java/util/concurrent/locks/Condition.java:42-59`（javadoc）

```java
 * Condition factors out the Object monitor methods (wait, notify and
 * notifyAll) into distinct objects to give the effect of having multiple
 * wait-sets per object, by combining them with the use of arbitrary
 * Lock implementations. Where a Lock replaces the use of synchronized
 * methods and statements, a Condition replaces the use of the Object
 * monitor methods.
```

实现藏在 AQS 的内部类 `ConditionObject`（`AQS.java:1517+`）：每个 Condition 有自己的条件队列（`firstWaiter/lastWaiter` 链），`await()` 的本质是**释放锁 + 挂到条件队列 + park**；`signal()` 是**把条件队列的头节点转移到 AQS 的同步队列**（让它重新参与抢锁）。核心源码：

```java
    // await() —— AQS.java:1728-1765（关键骨架）
    public final void await() throws InterruptedException {
        if (Thread.interrupted()) throw new InterruptedException();
        ConditionNode node = newConditionNode();
        int savedState = enableWait(node);            // 入条件队列，并 release(savedState) 释放锁！
        LockSupport.setCurrentBlocker(this);
        boolean interrupted = false, cancelled = false, rejected = false;
        while (!canReacquire(node)) {                 // 循环：防虚假唤醒（同 wait 的铁律）
            if (interrupted |= Thread.interrupted()) { ... }
            else if ((node.status & COND) != 0) {
                try {
                    if (rejected) node.block();
                    else ForkJoinPool.managedBlock(node);   // park（兼容虚拟线程/FJP）
                } catch (RejectedExecutionException ex) { rejected = true; }
                ...
            } else Thread.onSpinWait();
        }
        LockSupport.setCurrentBlocker(null);
        node.clearStatus();
        reacquire(node, savedState);                  // 被唤醒后按 savedState 重新拿回锁
        ...
    }

    // signal() —— AQS.java:1567-1573
    public final void signal() {
        ConditionNode first = firstWaiter;
        if (!isHeldExclusively()) throw new IllegalMonitorStateException();  // 必须持锁
        else if (first != null) doSignal(first, false);      // 把头节点转移到同步队列
    }
```

`savedState` 这个细节值得品味：await 前锁可能已被重入持有 3 次，所以必须先**全部释放**（`release(savedState)`），被 signal 后**原样拿回 3 次**——否则重入语义就破坏了。

```java
// 使用示例：两个 Condition 的有界缓冲（ArrayBlockingQueue 的完整缩小版，第八章看真身）
public class CondBuffer {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull  = lock.newCondition();   // 生产者在这等
    private final Condition notEmpty = lock.newCondition();   // 消费者在这等
    private final Object[] items = new Object[10];
    private int putIdx, takeIdx, count;

    public void put(Object x) throws InterruptedException {
        lock.lock();
        try {
            while (count == items.length) notFull.await();    // 只惊动生产者
            items[putIdx] = x;
            if (++putIdx == items.length) putIdx = 0;
            count++;
            notEmpty.signal();                                // 只叫醒一个消费者
        } finally { lock.unlock(); }
    }
    public Object take() throws InterruptedException {
        lock.lock();
        try {
            while (count == 0) notEmpty.await();              // 只惊动消费者
            Object x = items[takeIdx];
            items[takeIdx] = null;
            if (++takeIdx == items.length) takeIdx = 0;
            count--;
            notFull.signal();                                 // 只叫醒一个生产者
            return x;
        } finally { lock.unlock(); }
    }
}
```

## 5.6 小结：什么时候用 synchronized，什么时候用 ReentrantLock

| 维度 | synchronized | ReentrantLock |
|---|---|---|
| 锁的获取 | 死等 | 可尝试/限时/可中断 |
| 公平性 | 不支持 | 构造参数可选 |
| 等待集合 | 1 个（wait set） | 多个 Condition |
| 解锁 | 自动（异常也释放） | 手动（必须 try-finally） |
| 性能 | 现代已接近 | 相当 |
| 排查 | jstack 显示 BLOCKED | park + blocker |

**默认用 synchronized**（简单、不会忘 unlock）；需要 tryLock/公平/多 Condition 时才升级为 ReentrantLock。而无论哪种锁，底层已经汇合：synchronized 用 JVM 的对象监视器，ReentrantLock 用 AQS + LockSupport——两者都在无竞争时退化为一次 CAS。

---
# 六、读写分离与乐观读：ReentrantReadWriteLock 与 StampedLock

## 6.1 产生背景与解决的问题域

**白话**：很多数据结构是**读多写少**的（配置表、缓存、路由表）。独占锁把"读"和"读"也互斥了，白白损失并行度——10 个线程读同一个缓存，本可同时进行。读写锁的规则是：**读-读共享，读-写互斥，写-写互斥**。JDK 给了两代方案：`ReentrantReadWriteLock`（JDK 5，悲观读）与 `StampedLock`（JDK 8，多了**乐观读**——读的时候根本不加锁，读完再验证）。

## 6.2 ReentrantReadWriteLock：一个 state 拆成两半

【源码证据】`java.base/java/util/concurrent/locks/ReentrantReadWriteLock.java:256-271`

```java
        /*
         * Read vs write count extraction constants and functions.
         * Lock state is logically divided into two unsigned shorts:
         * The lower one representing the exclusive (writer) lock hold count,
         * and the upper the shared (reader) hold count.
         */
        static final int SHARED_SHIFT   = 16;
        static final int SHARED_UNIT    = (1 << SHARED_SHIFT);
        static final int MAX_COUNT      = (1 << SHARED_SHIFT) - 1;
        static final int EXCLUSIVE_MASK = (1 << SHARED_SHIFT) - 1;

        /** Returns the number of shared holds represented in count. */
        static int sharedCount(int c)    { return c >>> SHARED_SHIFT; }
        /** Returns the number of exclusive holds represented in count. */
        static int exclusiveCount(int c) { return c & EXCLUSIVE_MASK; }
```

**原理（白话）**：还是 AQS 那个 int state，但**高 16 位记账读锁（多少个读者），低 16 位记账写锁（重入次数）**。加读锁 = 高半区 +65536（SHARED_UNIT）；加写锁 = 低半区 +1。两个半区互相检查对方是否为 0 来实现互斥。写锁的可重入记在 state 低 16 位；每个线程的读锁重入次数则用 ThreadLocal 记（`HoldCounter` + `ThreadLocalHoldCounter`，`ReentrantReadWriteLock.java:273-292`）。

读锁的获取入口（`tryAcquireShared` 方法在 `ReentrantReadWriteLock.java:453`，下摘 469-495 关键段）：

```java
            Thread current = Thread.currentThread();
            int c = getState();
            if (exclusiveCount(c) != 0 &&
                getExclusiveOwnerThread() != current)
                return -1;                             // 有写锁且不是自己 → 排队等待
            int r = sharedCount(c);
            if (!readerShouldBlock() &&
                r < MAX_COUNT &&
                compareAndSetState(c, c + SHARED_UNIT)) {   // 高 16 位 +1（CAS）
                ... /* 记录每线程重入数（firstReader/cachedHoldCounter 快速路径） */
                return 1;
            }
            return fullTryAcquireShared(current);      // 慢路径：处理重入/队列等复杂情况
```

**防止写者饿死**：非公平模式下读锁太容易加（读-读共享嘛），写者可能永远插不进去。JDK 的启发式是：只要队首排着的是等待中的写者，后来的读者也得去排队（`NonfairSync.readerShouldBlock` 返回 `apparentlyFirstQueuedIsExclusive()`，`ReentrantReadWriteLock.java:680-695`）。

**锁降级**（官方 javadoc 自带的示例，`ReentrantReadWriteLock.java:144-166`）：持有写锁的线程可以再拿读锁，然后释放写锁——**写降为读**（反之不行）：

```java
    // 官方示例（源码 javadoc 原文，ReentrantReadWriteLock.java:144-166）的中文注释版：
    void processCachedData() {
        rwl.readLock().lock();
        if (!cacheValid) {
            rwl.readLock().unlock();      // 想拿写锁必须先放读锁（读→写不可升级）
            rwl.writeLock().lock();       // 写锁：独占重建缓存
            try {
                if (!cacheValid) {        // 双检：可能别的线程已重建
                    data = ...;
                    cacheValid = true;
                }
                rwl.readLock().lock();    // ★ 先拿读锁再放写锁 = 降级，中间无空窗
            } finally {
                rwl.writeLock().unlock(); // 仍持有读锁
            }
        }
        try {
            use(data);                    // 安心地读
        } finally {
            rwl.readLock().unlock();
        }
    }
```

降级的意义：如果先放写锁再拿读锁，中间这个瞬间缓存刚改完还没人加读锁，数据就可能被别人改掉；降级保证了"我写的数据我立刻能一致地读到"。

```java
// 使用示例：读多写少缓存
ReentrantReadWriteLock rwl = new ReentrantReadWriteLock();
Map<String, Object> cache = new HashMap<>();

Object get(String key) throws InterruptedException {
    rwl.readLock().lock();
    try {
        Object v = cache.get(key);
        if (v != null) return v;
    } finally { rwl.readLock().unlock(); }

    rwl.writeLock().lock();               // miss 才升级为写（先放读锁再拿写锁）
    try {
        Object v = loadFromDb(key);       // 双检可加：拿写锁后再查一次
        cache.put(key, v);
        return v;
    } finally { rwl.writeLock().unlock(); }
}
```

## 6.3 StampedLock：乐观读——不加锁的读

**产生背景**：读-读共享还不够——读锁对写者仍是纯悲观互斥。Doug Lea 在 JDK 8 给出更激进的方案：**读操作先"乐观"地不锁任何东西，直接读；读完用一个版本号验证"我读的期间没有写入"**——没有写就等于拿到了一致数据（白赚的零开销读），有写就老老实实升级成悲观读重试。

【源码证据】`java.base/java/util/concurrent/locks/StampedLock.java:621-641`（tryOptimisticRead / validate）

```java
    public long tryOptimisticRead() {
        long s;
        return (((s = state) & WBIT) == 0L) ? (s & SBITS) : 0L;   // 无写锁 → 发一个"版本戳"
    }

    public boolean validate(long stamp) {
        U.loadFence();                                            // 读屏障：保证读到的真实
        return (stamp & SBITS) == (state & SBITS);                // 版本戳没变 → 期间无写
    }
```

**原理（白话）**：内部是一个 64 位 `state`（`StampedLock.java:408-410`），写锁占用其中一个位（WBIT，`StampedLock.java:312-339` 的位编码注释），**每次写锁的加/放都会翻转这个位，相当于版本号 +1**。乐观读拿到的是"当时的版本快照"，validate 就是比对版本。读期间无锁、无 CAS、无 park——开销就是两次读 + 一次比较。

javadoc 同时给出了红字警告（`StampedLock.java:96-104`）：**不可重入**；乐观读段只能读字段到局部变量、不得调用未知方法（读到的字段在验证前可能不一致）。另外：读锁的持有数只有 7 位（LG_READERS=7，127 个），溢出后转用 `readerOverflow` 字段记账——这就是为什么它是"内部工具级"的设计。

```java
// 使用示例：经典二维点（javadoc 同款场景）
class Point {
    private double x, y;
    private final StampedLock sl = new StampedLock();

    void move(double deltaX, double deltaY) {          // 写：悲观写锁
        long stamp = sl.writeLock();
        try {
            x += deltaX; y += deltaY;
        } finally { sl.unlockWrite(stamp); }
    }

    double distanceFromOrigin() {                      // 读：乐观读 → 失败再升级
        long stamp = sl.tryOptimisticRead();           // ① 拿版本戳，不加锁
        double currentX = x, currentY = y;             // ② 读字段到局部变量
        if (!sl.validate(stamp)) {                     // ③ 验证：期间是否发生过写
            stamp = sl.readLock();                     // ④ 失败 → 老实拿悲观读锁重来
            try {
                currentX = x; currentY = y;
            } finally { sl.unlockRead(stamp); }
        }
        return Math.sqrt(currentX * currentX + currentY * currentY);
    }
}
```

**三者选型**：读极少写 → 普通锁；读多写少、要重入和 Condition → RRW；读多写少、追求吞吐且逻辑简单可接受不可重入 → StampedLock（乐观读还可以 `tryConvertToWriteLock` 一步升级，适合缓存重查场景）。

## 6.4 小结

- RRW 把 AQS 的 state 拆成高 16 位（读者数）/低 16 位（写者重入数），读-读共享；`apparentlyFirstQueuedIsExclusive` 防写者饿死；支持写→读**降级**，不支持读→写升级。
- StampedLock 在悲观锁之外提供乐观读：`tryOptimisticRead` 取版本戳 + 读到局部变量 + `validate` 验证；不可重入、适合内部工具类。

---

# 七、无锁之路：CAS 与原子类（Atomic* / LongAdder / 字段更新器）

## 7.1 产生背景与解决的问题域

**白话**：锁的代价是"排队 + 可能挂起/唤醒线程"（内核态切换）。但很多操作其实只有一个变量的小改动（计数器、标志位）。换一个思路：**不排队，改了再说——CAS（Compare-And-Swap）**：`CAS(内存位置, 期望值, 新值)`：当且仅当位置的当前值 == 期望值时，原子地把它改成新值，返回成功与否。失败？没关系，重读、重算、再试（自旋）。这条指令由 CPU 硬件保证原子性（x86 上是 `lock cmpxchg`）。

【源码证据】`java.base/jdk/internal/misc/Unsafe.java:1462-1474`（CAS 的官方语义定义）

```java
    /**
     * Atomically updates Java variable to x if it is currently
     * holding expected.
     *
     * <p>This operation has memory semantics of a volatile read
     * and write.  Corresponds to C11 atomic_compare_exchange_strong.
     */
    @IntrinsicCandidate                     // JVM 内建指令：x86 lock cmpxchg 等
    public final native boolean compareAndSetInt(Object o, long offset,
                                                 int expected, int x);
```

两个关键信息：① 语义 = "当前是 expected 才改成 x"，具备 volatile 读写的内存语义（可见性顺手解决）；② `@IntrinsicCandidate` 表示 JVM 会把它替换成平台专用原子指令——**原子性在硬件层，不在排队层**。CAS 的两个代价：自旋空转（竞争激烈时白白烧 CPU）与 **ABA 问题**（值从 A 改成 B 又改回 A，CAS 看不出中间过程——需要带版本号的 `AtomicStampedReference`）；此外 CAS 只能保证单个变量的原子性，多变量一致仍需锁。

## 7.2 AtomicInteger：volatile + CAS 的标准样板

【源码证据】`java.base/java/util/concurrent/atomic/AtomicInteger.java:58-66、128-137、182-184、255-264`

```java
    /*
     * This class intended to be implemented using VarHandles, but there
     * are unresolved cyclic startup dependencies.
     */
    private static final Unsafe U = Unsafe.getUnsafe();
    private static final long VALUE
        = U.objectFieldOffset(AtomicInteger.class, "value");   // 缓存字段的内存偏移

    private volatile int value;                                // 可见性由 volatile 保证

    public final boolean compareAndSet(int expectedValue, int newValue) {
        return U.compareAndSetInt(this, VALUE, expectedValue, newValue);   // :133-134
    }

    public final int getAndIncrement() {
        return U.getAndAddInt(this, VALUE, 1);                 // :183-184，Unsafe 内部自旋 CAS
    }

    // 任意函数式更新的通用模板（自旋 CAS 的教科书写法）—— :255-264
    public final int getAndUpdate(IntUnaryOperator updateFunction) {
        int prev = get(), next = 0;
        for (boolean haveNext = false;;) {
            if (!haveNext)
                next = updateFunction.applyAsInt(prev);        // 基于旧值计算新值
            if (weakCompareAndSetVolatile(prev, next))
                return prev;                                   // 成功 → 返回旧值
            haveNext = (prev == (prev = get()));               // 失败 → 重读；若没变则跳过重算
        }
    }
```

**原理收拢**：Atomic 类 = **volatile 保证可见性 + CAS 保证原子性 + 自旋保证最终成功**。`incrementAndGet()` 的底层 `U.getAndAddInt` 就是一个"do-while CAS"自旋（在 Unsafe 中实现）。所有 AtomicXxx 同构：`AtomicBoolean` 内部用 int 存 1/0（`AtomicBoolean.java:101-105`）；`AtomicLong` 额外保留了 `VM_SUPPORTS_LONG_CAS` 探测（`AtomicLong.java:64-80`，兼容不支持 64 位原子写的旧平台，现代 64 位 JVM 上恒为 true）；`AtomicReference<V>` 操作任意对象引用（走 VarHandle，`AtomicReference.java:122-124`）。

```java
// 使用示例：把第一章的丢失更新修好 —— 三种姿势
static AtomicInteger ai = new AtomicInteger();
ai.incrementAndGet();                       // 姿势一：现成方法

static AtomicReference<Integer> ref = new AtomicReference<>(0);
int v;
do { v = ref.get(); } while (!ref.compareAndSet(v, v + 1));   // 姿势二：手写自旋 CAS

ai.accumulateAndGet(5, Integer::sum);       // 姿势三：函数式累加
```

## 7.3 AtomicIntegerFieldUpdater：给"别人家的字段"加原子性

**产生背景**：类已经写好了，字段是普通 `volatile int`，不想改成 AtomicXxx（改类型影响序列化/内存布局/大量使用点），还想偶尔原子更新它——用反射找字段，然后对这个字段做 CAS。

【源码证据】`java.util.concurrent.atomic.AtomicIntegerFieldUpdater.java:416-421、489-492`

```java
            if (field.getType() != int.class)
                throw new IllegalArgumentException("Must be integer type");

            if (!Modifier.isVolatile(modifiers))                 // 字段必须 volatile！
                throw new IllegalArgumentException("Must be volatile type");
        ...
        public final boolean compareAndSet(T obj, int expect, int update) {
            accessCheck(obj);
            return U.compareAndSetInt(obj, offset, expect, update);
        }
```

```java
class Node {
    volatile int score = 0;                       // 必须 volatile，否则 newUpdater 直接抛异常
    private static final AtomicIntegerFieldUpdater<Node> UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(Node.class, "score");
    boolean addScore(int delta) { return UPDATER.compareAndSet(this, score, score + delta); }
}
```

典型用户是 JDK 自己：`ThreadPoolExecutor.Worker` 的字段、`FutureTask` 早期版本的 state 等——**大多数对象永远单线程访问，只有个别热点字段需要原子更新**时，Updater 比"整个值包一层 Atomic"省内存。JDK 9+ 的新代码更多改用 VarHandle（同一思想的标准化 API）。

## 7.4 LongAdder：高竞争计数器的正确答案

**产生背景**：100 个线程用一个 `AtomicLong.incrementAndGet()` 计数——CAS 自旋意味着**所有线程都在读写同一个 value 字段所在的缓存行**，缓存一致性协议让这条缓存行在核心间疯狂弹跳，大部分 CAS 白白失败重试。JDK 8 的解法（`LongAdder`/`DoubleAdder`/`LongAccumulator`，内部机制 `Striped64`）：**热点分离——没竞争就记在一个 base 上；一旦竞争，把 increments 分散到一组 Cell 上，各自 CAS 自己的 Cell，最后 sum() 把所有格子加起来**。

【源码证据】`java.base/java/util/concurrent/atomic/LongAdder.java:85-95`（add）、`Striped64.java:118-150`（Cell）

```java
    // LongAdder.add()：先试 base，失败则找自己的 Cell，还失败才进 longAccumulate 扩容
    public void add(long x) {
        Cell[] cs; long b, v; int m; Cell c;
        if ((cs = cells) != null || !casBase(b = base, b + x)) {   // ① 无竞争：CAS base
            int index = getProbe();                                 // ② 线程专属哈希
            boolean uncontended = true;
            if (cs == null || (m = cs.length - 1) < 0 ||
                (c = cs[index & m]) == null ||
                !(uncontended = c.cas(v = c.value, v + x)))         // ③ CAS 自己的 Cell
                longAccumulate(x, null, uncontended, index);        // ④ 兜底：初始化/扩容
        }
    }

    // 求和 = base + 所有 Cell
    public long sum() {
        Cell[] cs = cells;
        long sum = base;
        if (cs != null) {
            for (Cell c : cs)
                if (c != null)
                    sum += c.value;
        }
        return sum;
    }
```

```java
    // Striped64.Cell：带填充的 AtomicLong —— @Contended 让 JVM 在对象前后塞满"垫片"，
    // 确保不同 Cell 不落进同一条 64 字节缓存行（否则分散计数毫无意义！）
    @jdk.internal.vm.annotation.Contended static final class Cell {
        volatile long value;
        Cell(long x) { value = x; }
        final boolean cas(long cmp, long val) {
            return VALUE.weakCompareAndSetRelease(this, cmp, val);
        }
    }
```

`longAccumulate`（`Striped64.java:229-296`）就是分散策略的状态机：槽位空 → 抢 `cellsBusy` 自旋锁挂新 Cell；CAS 失败 → 给线程换个哈希（`advanceProbe`）再试；持续冲突且表未到 `NCPU` → **翻倍扩容**。这套机制在 JDK 8 被原样搬进了 `ConcurrentHashMap` 做元素计数（`CounterCell`，源码注释明说 "Adapted from LongAdder and Striped64"，第八章见）。

**AtomicLong vs LongAdder 决策表**：

| 维度 | AtomicLong | LongAdder |
|---|---|---|
| 低竞争 | 都好 | 都好 |
| 高竞争写 | 自旋重试，吞吐崩 | 分散后接近线性扩展 |
| 读（取当前值） | O(1)，**精确瞬时值** | `sum()` 需遍历，**非原子快照**（统计值） |
| 能否当同步条件用（CAS 判定） | 能（compareAndSet） | 不能（没有单值 CAS 语义） |

官方 javadoc 的原话（`LongAdder.java:41-54`）：高竞争下吞吐显著更高、代价是空间；**"collecting statistics"（统计计数）适用，"fine-grained synchronization control"（精细同步控制）不适用**。

```java
// 使用示例：接口 QPS 统计（高竞争写、低精度读 → LongAdder 是标准答案）
static final LongAdder qps = new LongAdder();
void handle() {
    qps.increment();                                  // 每个请求 +1，无锁竞争
}
void reportEverySecond() {
    new Thread(() -> { while (true) {
        try { Thread.sleep(1000); } catch (InterruptedException ignored) { return; }
        System.out.println("QPS=" + qps.sumThenReset());   // 读走清零
    }}).start();
}
```

## 7.5 小结

- CAS 用一条硬件原子指令替换"排队"，Atomic 类 = volatile（可见性）+ CAS（原子性）+ 自旋（重试）；代价是高竞争自旋与 ABA（需要版本号时用 `AtomicStampedReference`）。
- 单变量原子更新优先 AtomicXxx；已有类不想改字段类型用 `AtomicIntegerFieldUpdater`（字段必须 volatile）。
- 计数统计类高竞争场景换 `LongAdder`（热点分离 + @Contended 缓存行填充），但它的 sum 不是原子快照、不能做同步判定。

---
# 八、并发容器：ConcurrentHashMap / CopyOnWriteArrayList / ConcurrentLinkedQueue / BlockingQueue 家族

## 8.0 产生背景：普通集合在并发下有多惨

三连击的问题，催生了并发容器家族：

1. **ArrayList/HashMap 迭代时被修改 → fail-fast 炸异常**。机制本身有源码可查：父类记一个结构修改计数，迭代器每次 `next()` 都比对快照：
   【源码证据】`java.base/java/util/AbstractList.java:630`（modCount 声明）、`java.base/java/util/ArrayList.java:1093-1096`（检查）

   ```java
   // AbstractList.java
   protected transient int modCount = 0;          // add/remove 时 ++
   // ArrayList$Itr
   final void checkForComodification() {
       if (modCount != expectedModCount)          // 和创建迭代器时的快照不等
           throw new ConcurrentModificationException();
   }
   ```
2. **给普通集合套全表锁**（`Collections.synchronizedMap`）：一个 mutex 罩住所有方法，10 个线程读也得排队——并发度 1。
3. **Hashtable 式的"一个锁管一张表"** 同样并发度太低。

JDK 5 起按三个思路给出三族答案：**细粒度锁/CAS 混合**（ConcurrentHashMap）、**写时复制**（CopyOnWriteArrayList）、**无锁 CAS 链表**（ConcurrentLinkedQueue），外加一族**自带阻塞语义的队列**（BlockingQueue，生产者消费者的标准件）。

## 8.1 ConcurrentHashMap：把"一张表一把锁"拆到"一个桶一把锁"

**演进一句话**：JDK 7 用 Segment 分段锁（默认 16 段，并发度固定 16）；**JDK 8 起推倒重做**——去掉分段，锁的粒度降到**单个桶的头节点**，空桶插入用 CAS，扩容支持多线程协同。JDK 21 沿用这一设计。

【源码证据】`java.base/java/util/concurrent/ConcurrentHashMap.java:74-83`（类头定位）

```java
 * A hash table supporting full concurrency of retrievals and
 * high expected concurrency for updates. ... However, even though all
 * operations are thread-safe, retrieval operations do not entail locking,
 * and there is not any support for locking the entire table in a way
 * that prevents all access.
```

**核心结构**（`ConcurrentHashMap.java:625-650、774-815`）：

```java
    static class Node<K,V> implements Map.Entry<K,V> {
        final int hash;
        final K key;
        volatile V val;              // 读不加锁的关键：值是 volatile
        volatile Node<K,V> next;
        ...
    }
    transient volatile Node<K,V>[] table;      // 桶数组（volatile：扩容时新表立即可见）
    private transient volatile int sizeCtl;    // 多功能控制字：>0 阈值；-1 正在初始化；
                                               // < -1 正在扩容（含参与线程数信息）
    private transient volatile long baseCount;         // 计数 base（无竞争走这）
    private transient volatile CounterCell[] counterCells;  // 计数分格（竞争时，仿 LongAdder）
```

**put 的四路分支（putVal，`ConcurrentHashMap.java:1010-1050`）——锁粒度设计的精华**：

```java
    final V putVal(K key, V value, boolean onlyIfAbsent) {
        if (key == null || value == null) throw new NullPointerException();  // ① 不许 null
        int hash = spread(key.hashCode());            // 高低位异或扰动 + 抹掉符号位（:696-698）
        for (Node<K,V>[] tab = table;;) {
            Node<K,V> f; int n, i, fh;
            if (tab == null || (n = tab.length) == 0)
                tab = initTable();                     // ② 惰性建表（见下）
            else if ((f = tabAt(tab, i = (n - 1) & hash)) == null) {
                if (casTabAt(tab, i, null, new Node<K,V>(hash, key, value)))
                    break;                             // ③ 空桶：CAS 插入，无锁！
            }
            else if ((fh = f.hash) == MOVED)
                tab = helpTransfer(tab, f);            // ④ 撞上 ForwardingNode：帮着扩容
            else {
                V oldVal = null;
                synchronized (f) {                     // ⑤ 只有真冲突才锁：锁桶头节点 f
                    if (tabAt(tab, i) == f) {          // 双检：f 还是桶头吗
                        // 链表遍历追加/覆盖；若 f 是 TreeBin 则走红黑树插入
                        ...
                    }
                }
                if (binCount != 0) {
                    if (binCount >= TREEIFY_THRESHOLD)  // 链长 >=8 且表够大 → 树化
                        treeifyBin(tab, i);
                    ...
                    break;
                }
            }
        }
        addCount(1L, binCount);                        // ⑥ 计数 + 判断是否要扩容
        return null;
    }
```

五段源码对应五个设计决策：

- **③ 空桶 CAS**：大多数插入落在新桶上，一条 CAS 就完事，连 synchronized 都不用进。
- **⑤ 冲突才锁桶头**：锁的是 `f`（该桶第一个节点）而不是整张表——不同桶的写入完全并行；锁前 `tabAt(tab,i) == f` 双检防"锁错对象"。
- **② 惰性建表**：`initTable()`（`:2291-2312`）用 CAS 把 `sizeCtl` 改成 -1 抢初始化权，输家 `Thread.yield()` 让路——用控制字代替锁。
- **④ 协同扩容**：`transfer()`（`:2424-2462`）把旧表按 stride 切片，线程用 `CAS(transferIndex)` 认领区间各自迁移；迁完的桶放一个 `ForwardingNode`（hash=MOVED）指向新表，get 遇到它就转发、put 遇到它就搭把手——**扩容期间读写不停止**。
- **⑥ 分散计数**：`addCount`（`:2324-2358`）与 LongAdder 同构（CAS baseCount 失败 → 找自己的 CounterCell），`CounterCell` 就定义在 CHM 内部（`:2561-2579`，`@Contended` 填充）。所以 **size() 是近似值**（`size()` → `sumCount()`，`:909-921`），CHM 的 javadoc 也明说 size 是统计性质、并发下应视为估计值。

**get 为什么全程无锁**（`ConcurrentHashMap.java:934-952`）：读的是 `volatile val/next`，桶数组引用也是 volatile——写线程的修改靠 happens-before 保证可见；唯一要处理的是扩容（`eh < 0` 时 `e.find` 会路由到 ForwardingNode → 新表）。弱一致迭代器同理：不 fail-fast、不保证看到创建迭代器之后的修改，但保证不炸。

```java
// 使用示例：并发缓存 + 原子化的"查了再放"（不要用 get+put 两步走！）
ConcurrentHashMap<String, Long> counter = new ConcurrentHashMap<>();

long hits(String page) {
    return counter.merge(page, 1L, Long::sum);       // 原子合并：等价"读改写"且线程安全
}

Map<String,byte[]> cache = new ConcurrentHashMap<>();
byte[] load(String key) {
    return cache.computeIfAbsent(key, k -> expensiveLoad(k));  // 原子的不存在则计算
}

// 陷阱：复合操作仍然不原子
// if (!map.containsKey(k)) map.put(k, v);   ← 两步之间可能被插入，改用 putIfAbsent(k,v)
```

**两条纪律**：① key/value **不允许 null**（并发下 `get(key)==null` 无法区分"不存在"和"值是 null"，二义性在并发下无法用 containsKey 消除）；② 需要对整个 map 加锁的复合操作（批量迁移等）CHM 拒绝支持——javadoc 原话 "there is not any support for locking the entire table"，真需要请换 `Collections.synchronizedMap` 或加外部锁。

## 8.2 CopyOnWriteArrayList：读不锁、写复制

**产生背景**：监听器列表、配置快照这类场景：**读远远多于写**（每次请求都遍历，偶尔加一个监听器），而且希望遍历期间别人怎么改都不影响我（拿到的是"当时的快照"）。加锁读书太亏，于是干脆：**写的时候复制一个新数组改，改完原子地换掉引用；读永远无锁读当前数组**。

【源码证据】`java.base/java/util/concurrent/CopyOnWriteArrayList.java:109-110、461-470、402-404`

```java
    /** The array, accessed only via getArray/setArray. */
    private transient volatile Object[] array;        // 唯一的存储，volatile

    public boolean add(E e) {
        synchronized (lock) {                          // 写互斥（JDK 21 用显式 lock 对象）
            Object[] es = getArray();
            int len = es.length;
            es = Arrays.copyOf(es, len + 1);           // ★ 复制新数组（长度+1）
            es[len] = e;                               // 在新数组上改
            setArray(es);                              // volatile 写：原子换引用
            return true;
        }
    }
    public E get(int index) {
        return elementAt(getArray(), index);           // 读无锁
    }
```

迭代器创建时记下当前数组引用，之后数组再被换也与我无关——"快照式迭代"，永远不会抛 `ConcurrentModificationException`，也**不支持迭代器自身的 remove/set/add**（`COWIterator.remove` 直接抛 `UnsupportedOperationException`，`:1202-1209`）。

```java
// 使用示例：事件监听器注册表（写极少、读/遍历极多）
class EventBus {
    private final CopyOnWriteArrayList<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    void subscribe(Consumer<String> l)      { listeners.add(l); }      // 写：复制，O(n)
    void publish(String event) {
        for (Consumer<String> l : listeners) l.accept(event);          // 读：无锁 + 快照语义
    }
}
```

代价必须清楚：每次写 O(n) 复制 + 旧数组等 GC，写多时内存抖动惨烈。**只用于读多写少**，官方 javadoc 原话：适用场景是 "traversal operations vastly outnumber mutations"（`:62-80`）。

## 8.3 ConcurrentLinkedQueue：无锁队列（CAS 链表）

**产生背景**：需要一个**不阻塞、不加锁**的先进先出队列（线程池任务队列的备选、消息传递）。JDK 采用 Michael & Scott 1996 年论文的非阻塞算法。

【源码证据】`java.base/java/util/concurrent/ConcurrentLinkedQueue.java:354-381`（offer）

```java
    public boolean offer(E e) {
        final Node<E> newNode = new Node<E>(Objects.requireNonNull(e));
        for (Node<E> t = tail, p = t;;) {                 // 从 tail 出发
            Node<E> q = p.next;
            if (q == null) {
                // p 是最后一个节点：CAS 把新节点挂上去
                if (NEXT.compareAndSet(p, null, newNode)) {
                    if (p != t) // hop two nodes at a time; failure is OK
                        TAIL.weakCompareAndSet(this, t, newNode);   // tail 允许滞后，更新失败也没事
                    return true;
                }
            }
            else if (p == q)                              // 掉到表外（被并发删除）→ 跳回 head
                p = (t != (t = tail)) ? t : head;
            else
                p = (p != t && t != (t = tail)) ? t : q;  // tail 落后两跳 → 前进
        }
    }
```

两个精妙点：① **线性化点是 CAS 成功那一刻**——入队的原子性只有一次 CAS，队列永远处于"要么插入成功要么没插入"的中间可恢复状态；② **tail 允许滞后**（javadoc 的 Non-invariants 明说）：CAS 失败不重试更新 tail，留给下一个线程顺手帮忙（"helping"），减一个 CAS 换来整体更少的重试。poll() 同理用 `p.casItem(item, null)` 把节点值置空完成出队（`:383-402`）。缺点：无界、无阻塞语义——需要"满了就等"请用 BlockingQueue。

## 8.4 BlockingQueue 家族：生产者消费者的标准件

**产生背景**：8.3 的队列"满了不等人、空了不等人"，但生产消费之间恰恰需要**流量控制**：生产太快希望生产者等一等（背压），消费太快希望消费者等一等。`BlockingQueue` 把这两个"等"内建进接口：

【源码证据】`java.base/java/util/concurrent/BlockingQueue.java:231、261、251-252、275-276、198、217`

| 语义 | 入队 | 出队 |
|---|---|---|
| 抛异常（立即） | `add(e)`（:198） | `remove()`（Queue 继承） |
| 返回特殊值（立即） | `offer(e)`（:217） | `poll()` |
| **阻塞等待** | `put(e)`（:231） | `take()`（:261） |
| 限时等待 | `offer(e, timeout, unit)`（:251） | `poll(timeout, unit)`（:275） |

四个代表实现，锁的粒度与结构各不相同——**一组现成的"条件等待"教材**：

**① ArrayBlockingQueue：一把锁 + 两个条件**（容量固定，数组环形存储）

【源码证据】`java.base/java/util/concurrent/ArrayBlockingQueue.java:364-375、415-425、121-129`

```java
    final ReentrantLock lock;                    // 全局唯一一把锁
    private final Condition notEmpty;            // 消费者在这等"有货"
    private final Condition notFull;             // 生产者在这等"有位"

    public void put(E e) throws InterruptedException {
        Objects.requireNonNull(e);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == items.length)        // while 防虚假唤醒（与第四章铁律一致）
                notFull.await();
            enqueue(e);                          // 循环数组放入，count++
        } finally { lock.unlock(); }
    }
    public E take() throws InterruptedException {
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == 0)
                notEmpty.await();
            return dequeue();                    // 取出，count--，notFull.signal()
        } finally { lock.unlock(); }
    }
```

对比第四章手写的 synchronized 版本：同样的模式，但**两把 Condition 把"等空位"和"等数据"分开**，put 只 signal notEmpty、take 只 signal notFull，不再惊群。

**② LinkedBlockingQueue：两把锁（读端写端各一把）**——链表头尾分离，put 只碰 putLock、take 只碰 takeLock，读写几乎完全并行；中间用一个 AtomicInteger count 衔接（`LinkedBlockingQueue.java:155-167、326-354`）。有意思的细节是"跨界唤醒"：`put` 后 `if (c == 0) signalNotEmpty()`——队列从空变非空时必须越过自己的锁去唤醒 take 侧（否则消费者还睡着）。

```java
    private final ReentrantLock takeLock = new ReentrantLock();
    private final Condition notEmpty = takeLock.newCondition();
    private final ReentrantLock putLock = new ReentrantLock();
    private final Condition notFull = putLock.newCondition();
```

**③ SynchronousQueue：容量为零的"击鼓传花"**——每个 put 必须等到一个 take 当面接走（javadoc：`does not have any internal capacity, not even a capacity of one`，`:55-58`）。适合**直接交付**场景（如 `Executors.newCachedThreadPool` 用它：来一个任务要么有线程立刻接、要么开新线程）。JDK 21 的实现已重写：内部 `Transferer extends LinkedTransferQueue`（`:152`），fair=true 走 FIFO 的 `xfer`，否则走 LIFO 栈式 `xferLifo`（`:167-201`：发现互补节点后 CAS 摘 item + `LockSupport.unpark` 对端）——旧教材里"TransferQueue/TransferStack 两个内部类"的说法在本版本已不适用。

**④ DelayQueue：到期的元素才能取**——元素实现 `Delayed` 接口（`getDelay(NANOSECONDS) <= 0` 即到期），内部是 PriorityQueue（按到期时间排序的小顶堆）+ **leader-follower 模式**（`DelayQueue.java:235-267`）：只有一个 leader 线程用 `awaitNanos(delay)` 睡到堆顶到期，其他线程无限期等——避免全员定时醒来空转。定时任务框架（`ScheduledThreadPoolExecutor` 的 DelayedWorkQueue 同思想）、本地延迟队列都用它。

```java
// 使用示例：ArrayBlockingQueue 版生产者-消费者（生产代码中最常用组合）
BlockingQueue<Integer> queue = new ArrayBlockingQueue<>(100);   // 有界！天然背压

new Thread(() -> {                       // 生产者
    try {
        for (int i = 0; ; i++) queue.put(i);       // 满了自动等，不会 OOM
    } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
}).start();

new Thread(() -> {                       // 消费者
    try {
        while (true) {
            Integer job = queue.take();            // 空了自动等
            process(job);
        }
    } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
}).start();
```

## 8.5 小结与选型

| 容器 | 加锁方式 | 特点 | 典型场景 |
|---|---|---|---|
| ConcurrentHashMap | 空桶 CAS + 桶头 synchronized + 分散计数 | 读无锁；size 近似；不允许 null | 通用并发 Map、缓存、计数 |
| CopyOnWriteArrayList | 写 synchronized + 全量复制 | 读无锁、快照迭代、写贵 | 监听器列表、读多写少 |
| ConcurrentLinkedQueue | 全 CAS 无锁 | 无界、不阻塞 | 任务转交、消息缓冲 |
| ArrayBlockingQueue | 1 锁 2 条件 | 有界、背压 | 生产者消费者（首选） |
| LinkedBlockingQueue | 2 锁 + Atomic 计数 | 有界/无界（小心无界 OOM） | 吞吐更高的产消队列 |
| SynchronousQueue | CAS 配对交付 | 容量 0 | 直接交付、newCachedThreadPool |
| DelayQueue | 堆 + leader-follower | 到期才能 take | 定时/延迟任务 |

---
# 九、线程协作工具：CountDownLatch / CyclicBarrier / Semaphore / Exchanger / Phaser

## 9.1 产生背景与解决的问题域

锁解决的是"**别踩脚**"（互斥），但还有一类问题是"**会合**"（rendezvous）：A 必须等 B/C/D 都做完才能开工；一批人必须到齐才能出发；同一时刻只允许 5 个人进场。这类"数量与节奏的约定"如果手写 wait/notify 极易出错，JDK 5（+7）给出了五个语义化工具。它们几乎全部构建在 AQS 的共享模式上——**第九章就是第五章 AQS `tryAcquireShared` 那个口子的集中应用展**。

| 工具 | 一句话语义 | 可复用？ |
|---|---|---|
| CountDownLatch | 数到 0 放行（等 N 件事） | 否（一次性） |
| CyclicBarrier | 人到齐了一起走（N 个线程互相等） | 是（可反复） |
| Semaphore | 同时最多 N 个（许可数） | 是（数量可增减） |
| Exchanger | 两个线程当面交换数据 | 是 |
| Phaser（JDK 7） | 多阶段栅栏，人数可动态增减 | 是（且分阶段） |

## 9.2 CountDownLatch：一次性倒计时门闩

**语义**：`new CountDownLatch(3)` 立一个计数为 3 的门闩；`await()` 卡在门口直到计数归零；每完成一件事 `countDown()` 减一；归零后所有等待者放行，**之后再 await 直接通过**（不能重置——官方 javadoc：`This is a one-shot phenomenon -- the count cannot be reset. If you need a version that resets the count, consider using a CyclicBarrier.`，`CountDownLatch.java:44-50`）。

【源码证据】`java.base/java/util/concurrent/CountDownLatch.java:156-186、229-231、289-291`

```java
    private static final class Sync extends AbstractQueuedSynchronizer {
        Sync(int count) {
            setState(count);                        // AQS 的 state 就是计数！
        }
        protected int tryAcquireShared(int acquires) {
            return (getState() == 0) ? 1 : -1;      // await：state==0 才放行
        }
        protected boolean tryReleaseShared(int releases) {
            for (;;) {                              // countDown：自旋 CAS 递减
                int c = getState();
                if (c == 0) return false;
                int nextc = c - 1;
                if (compareAndSetState(c, nextc))
                    return nextc == 0;              // 减到 0 → 返回 true 触发唤醒
            }
        }
    }
    public void await() throws InterruptedException { sync.acquireSharedInterruptibly(1); }
    public void countDown()                          { sync.releaseShared(1); }
```

**原理（白话）**：await 走 AQS 共享模式（5.3 节）：state != 0 时 `tryAcquireShared` 返回 -1，线程进队列 park；countDown 把 state 减到 0 时 `tryReleaseShared` 返回 true，`releaseShared` 触发 `signalNext` 级联唤醒所有共享等待者——**一个类 30 行，全靠 AQS 打底**。

```java
// 使用示例：并行抓取 3 个数据源，全部完成后再汇总
CountDownLatch latch = new CountDownLatch(3);
String[] results = new String[3];
String[] urls = {"a", "b", "c"};
for (int i = 0; i < 3; i++) {
    final int idx = i;
    new Thread(() -> {
        try {
            results[idx] = fetch(urls[idx]);    // 各自干活（注意 results 是定长槽位，无竞争写）
        } finally {
            latch.countDown();                  // 务必放 finally，异常也要减计数
        }
    }).start();
}
latch.await();                                  // 等三路都回来
System.out.println(Arrays.toString(results));
```

**与 `Thread.join()` 的区别**：join 只能等"线程结束"，latch 等的是"事件计数"——3 个任务可以跑在同一个线程里分三次 countDown，也可以来自线程池； latch 让"等待的粒度"从线程变成了任务。

## 9.3 CyclicBarrier：人齐再走，可反复使用

**语义**：N 个线程各自 `await()`，谁最后一个到，谁触发"放行"（可选先执行一个 barrierAction 回调），然后**所有人同时继续，栅栏自动进入下一轮**——所以叫 cyclic（循环）。实现不走 AQS，而是 **ReentrantLock + Condition**：

【源码证据】`java.base/java/util/concurrent/CyclicBarrier.java:158-174、201-230、176-196`

```java
    /** The lock for guarding barrier entry */
    private final ReentrantLock lock = new ReentrantLock();
    /** Condition to wait on until tripped */
    private final Condition trip = lock.newCondition();
    private final int parties;                       // 总人数
    private final Runnable barrierCommand;           // 全员到齐后、放行前执行的回调
    private Generation generation = new Generation();  // 当前"代"
    private int count;                               // 本代还差几个人

    private int dowait(boolean timed, long nanos) ... {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            final Generation g = generation;
            if (g.broken) throw new BrokenBarrierException();   // 本代已破裂
            if (Thread.interrupted()) { breakBarrier(); throw new InterruptedException(); }

            int index = --count;
            if (index == 0) {                    // ★ 我是最后一个到的
                Runnable command = barrierCommand;
                if (command != null) {
                    try { command.run(); }       // 先执行回调（在锁内！）
                    catch (Throwable ex) { breakBarrier(); throw ex; }
                }
                nextGeneration();                // 换代：count=parties、新 Generation、signalAll
                return 0;
            }
            // 不是最后一个：在 trip 条件上等待，醒来后判断 broken / 换代 / 超时（:232-265）
            for (;;) {
                try { trip.await(); ... }
                if (g.broken) throw new BrokenBarrierException();
                if (g != generation) return index;      // 换代了 = 放行
                ...
            }
        } finally { lock.unlock(); }
    }
```

**"复用"的实现**是点睛之笔：一个只有 `boolean broken` 的 `Generation` 对象代表"这一轮"。最后一个到达者 `nextGeneration()`：signalAll 唤醒所有人 + `count = parties` + **换一个新的 Generation 实例**——等待的线程醒来发现 `g != generation` 就知道本轮已放行。

```java
// 使用示例：4 个线程分段求和，每段算完在栅栏处会合，最后一轮汇总
int[] data = new int[400];
CyclicBarrier barrier = new CyclicBarrier(4, () ->        // barrierAction：每轮到齐后执行
        System.out.println("一轮计算完成，检查点：" + System.currentTimeMillis()));
AtomicLong total = new AtomicLong();

for (int i = 0; i < 4; i++) {
    final int seg = i;
    new Thread(() -> {
        long sum = 0;
        for (int j = seg * 100; j < (seg + 1) * 100; j++) sum += data[j];
        total.addAndGet(sum);
        try { barrier.await(); }                          // 等齐
        catch (Exception e) { return; }
        if (seg == 0) System.out.println("合计=" + total.get());
    }).start();
}
```

**Latch vs Barrier 对比**（选型高频题）：

| 维度 | CountDownLatch | CyclicBarrier |
|---|---|---|
| 角色 | 事件计数：等"N 件事" | 线程会合：N 个线程互相等 |
| 复用 | 一次性 | 自动循环复用 |
| 回调 | 无 | barrierCommand（到齐触发） |
| 实现 | AQS 共享模式 | ReentrantLock + Condition |
| 破坏语义 | —— | 有人 broken 则全员 BrokenBarrierException |

## 9.4 Semaphore：数量型闸门

**语义**：维护 N 个"许可"，`acquire()` 拿一个（没有就等），`release()` 还一个。**没有真实的许可对象，只是个计数**（javadoc 原话：`no actual permit objects are used; the Semaphore just keeps a count`，`Semaphore.java:41-47`）——AQS 的 state 就是许可数。

【源码证据】`java.base/java/util/concurrent/Semaphore.java:186-196、239-260`

```java
    // 非公平：上来就 CAS 扣减，不看队列（谁手快谁先得）
    final int nonfairTryAcquireShared(int acquires) {
        for (;;) {
            int available = getState();
            int remaining = available - acquires;
            if (remaining < 0 || compareAndSetState(available, remaining))
                return remaining;              // 负数 = 失败进队列排队
        }
    }
    // 公平：先看队列里有没有人排在我前面
    protected int tryAcquireShared(int acquires) {
        for (;;) {
            if (hasQueuedPredecessors()) return -1;    // 排队去
            int available = getState();
            int remaining = available - acquires;
            if (remaining < 0 || compareAndSetState(available, remaining))
                return remaining;
        }
    }
```

```java
// 使用示例：限制并发数据库连接数
Semaphore dbPermits = new Semaphore(10);          // 最多 10 个并发

void query() throws InterruptedException {
    dbPermits.acquire();                          // 拿不到许可就等（可 acquire(n) 一次拿多个）
    try {
        doDbWork();
    } finally {
        dbPermits.release();                      // 必须 finally 归还！否则许可泄漏
    }
}
```

另一个经典用途是当"**二值开关**"：`new Semaphore(1)` 互斥（但不重入，和锁不同）；`new Semaphore(0)` 则变成"信箱"——release 相当于发通知，acquire 等通知。

## 9.5 Exchanger：两个线程当面交换

**语义**：两个线程各自带着一个对象调 `exchange(x)`，互相阻塞，配对成功后**各自拿到对方的对象**返回。官方定位一句话（`Exchanger.java:43-50`）："An Exchanger may be viewed as a bidirectional form of a SynchronousQueue"（双向的 SynchronousQueue）。

【源码证据】`java.base/java/util/concurrent/Exchanger.java:452-481`（slotExchange 核心配对）

```java
    private final Object slotExchange(Object item, boolean timed, long ns) {
        Node p = participant.get();                   // 每线程一个 Node（@Contended 填充）
        ...
        for (Node q;;) {
            if ((q = slot) != null) {                 // 槽里有人 → 我是配对方
                if (SLOT.compareAndSet(this, q, null)) {   // CAS 摘走对方的槽
                    Object v = q.item;
                    q.match = item;                   // 把我的 item 写进对方 Node 的 match
                    Thread w = q.parked;
                    if (w != null) LockSupport.unpark(w);   // 唤醒对方
                    return v;                         // 返回对方的东西
                }
                if (NCPU > 1 && bound == 0 && BOUND.compareAndSet(this, 0, SEQ))
                    arena = new Node[(FULL + 2) << ASHIFT]; // 竞争激烈 → 升级为 arena 数组
            }
            else if (arena != null)
                return null;                          // 重路由到 arenaExchange
            else {                                    // 槽是空的 → 我先占槽等待
                p.item = item;
                if (SLOT.compareAndSet(this, null, p)) break;
                p.item = null;
            }
        }
        ... // 自旋 → yield → park 等待 match 被写入（:483-518）
    }
```

单槽 CAS 配对（`slot` 字段，`:334`），检测到竞争升级为 elimination arena（消减数组，多对线程可以在不同槽位并行交换）——与 LongAdder 的"热点分离"是同一个思想。

```java
// 使用示例：两个线程交换各自的缓冲区（基因组比对、流水线换Buffer的经典场景）
Exchanger<String> exchanger = new Exchanger<>();
new Thread(() -> {
    try { System.out.println("A 收到: " + exchanger.exchange("A的货物")); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
}).start();
new Thread(() -> {
    try { System.out.println("B 收到: " + exchanger.exchange("B的货物")); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
}).start();
// 两个线程各拿到对方的东西；若有第三个线程来，它会阻塞等待下一个配对
```

## 9.6 Phaser：可动态增减人数的多阶段栅栏

**产生背景**：CyclicBarrier 的 parties 在构造时定死、只能全体一起重用；现实中"分阶段任务"往往人数会变（阶段性退出、动态加入），且希望每个阶段可以自定义动作。Phaser（JDK 7）= CountDownLatch 的计数语义 + CyclicBarrier 的多轮复用 + 动态注册。

**原理**：所有状态塞进**一个 volatile long**，按位段切分（`Phaser.java:270-297` 注释原文）：

```java
     * unarrived  -- the number of parties yet to hit barrier (bits  0-15)
     * parties    -- the number of parties to wait            (bits 16-31)
     * phase      -- the generation of the barrier            (bits 32-62)
     * terminated -- set if barrier is terminated             (bit  63 / sign)
```

一次 CAS 同时更新"已到人数/总人数/代数/终止位"，这是它能支持动态注册的关键（`register()` → `doRegister`，`Phaser.java:586-588、424-452`；`arriveAndAwaitAdvance()` 的最后到达者负责推进 phase、组装下一代 state 并 `releaseWaiters` 唤醒同代等待者，`:671-704`）。`onAdvance(phase, registeredParties)`（`:935-937`）是每代切换的钩子（相当于每阶段可自定义的 barrierAction），默认在人数归零时返回 true 终止。

```java
// 使用示例：3 个考生、2 个阶段考试；有人可以中途退出（arriveAndDeregister）
Phaser phaser = new Phaser() {
    @Override protected boolean onAdvance(int phase, int parties) {
        System.out.println("第 " + phase + " 阶段结束，剩余人数 " + parties);
        return phase >= 1 || parties == 0;         // 考完 2 个阶段自动终止
    }
};
phaser.register();                                  // 主线程占一个名额（控制总阶段数）
for (int i = 0; i < 3; i++) {
    phaser.register();                              // 动态注册每个考生
    final int id = i;
    new Thread(() -> {
        doStage(id, 1);
        phaser.arriveAndAwaitAdvance();             // 阶段1完成，等大家
        if (id == 2) { phaser.arriveAndDeregister(); return; }  // 考生2 退出
        doStage(id, 2);
        phaser.arriveAndDeregister();               // 完成并注销
    }).start();
}
phaser.arriveAndDeregister();                       // 主线程只做阶段推进器
```

## 9.7 小结

- 五个工具 = 语义化的"会合"：等事件（Latch，AQS state=计数）、等人（Barrier，Lock+Condition+Generation 换代）、限数量（Semaphore，AQS state=许可）、两两交换（Exchanger，CAS 占槽配对+arena 消减）、多阶段动态（Phaser，64 位位段 state）。
- 共同纪律：`countDown/release` 类"还资源"的调用务必放 finally；等待方一律 while/语义化方法内部已处理虚假唤醒。
- 深层复用观：除了 CyclicBarrier，其余全部直接站在 AQS 共享模式上——理解了第五章，这一章每个类的源码都只剩"state 的语义"三十行。

---
# 十、Executor 体系与异步编程：线程池 / Future / CompletableFuture / ForkJoinPool / 虚拟线程

## 10.1 产生背景：为什么需要线程池

**白话**：线程不是免费的——每个平台线程默认预留 1MB 栈内存、创建/销毁要走 OS 系统调用；"来一个请求开一个线程"在流量洪峰下等于自杀（几千个线程的上下文切换就能把 CPU 打满）。线程池把"线程的创建与复用"集中管理：**任务来了丢进池子，池子里的线程反复取任务执行**。更抽象一层，它把"任务的提交"与"任务的执行"解耦——提交者不关心代码跑在哪个线程上，这就是 `Executor` 接口只有一个方法的原因：

【源码证据】`java.base/java/util/concurrent/Executor.java:124-137`

```java
public interface Executor {
    /**
     * Executes the given command at some time in the future.  The command
     * may execute in a new thread, in a pooled thread, or in the calling
     * thread, at the discretion of the Executor implementation.
     */
    void execute(Runnable command);
}
```

javadoc 那句 "may execute in a new thread, in a pooled thread, or in the calling thread" 值得读三遍：执行策略完全由实现决定——后面 `CallerRunsPolicy` 拒绝策略（让提交线程自己跑）就是这句话的回响。

## 10.2 ThreadPoolExecutor：一个 AtomicInteger 管住所有线程

### 10.2.1 核心状态：ctl 一个 int 拆两半

【源码证据】`java.base/java/util/concurrent/ThreadPoolExecutor.java:387-401`

```java
    private final AtomicInteger ctl = new AtomicInteger(ctlOf(RUNNING, 0));
    private static final int COUNT_BITS = Integer.SIZE - 3;        // 29
    private static final int COUNT_MASK = (1 << COUNT_BITS) - 1;   // 29 个 1（JDK8 里叫 CAPACITY）

    // runState is stored in the high-order bits
    private static final int RUNNING    = -1 << COUNT_BITS;   // 111：接受新任务
    private static final int SHUTDOWN   =  0 << COUNT_BITS;   // 000：不接新任务，清完存量
    private static final int STOP       =  1 << COUNT_BITS;   // 001：不接新任务，中断进行中的
    private static final int TIDYING    =  2 << COUNT_BITS;   // 010：全干完了，即将终结
    private static final int TERMINATED =  3 << COUNT_BITS;   // 011：终结

    private static int runStateOf(int c)     { return c & ~COUNT_MASK; }   // 取高 3 位
    private static int workerCountOf(int c)  { return c & COUNT_MASK; }    // 取低 29 位
    private static int ctlOf(int rs, int wc) { return rs | wc; }
```

**线程数 + 生命周期状态**用一个原子 int 打包（高 3 位状态、低 29 位线程数），任何线程改动/读取都是一次 CAS/读——这是全书反复出现的"一个 volatile 变量表达多个相关量"思路的巅峰应用。

### 10.2.2 七个参数（决策点）

字段声明在 `ThreadPoolExecutor.java:454、499-561`：`corePoolSize`（常驻线程数）、`maximumPoolSize`（上限）、`keepAliveTime`（空闲存活时间）、`workQueue`（任务队列）、`threadFactory`（线程工厂）、`handler`（拒绝策略）、`allowCoreThreadTimeOut`。注释特意说明这些参数都是 volatile——"运行期可动态调整而无需加锁"。

### 10.2.3 execute 的三步决策（必背）

【源码证据】`java.base/java/util/concurrent/ThreadPoolExecutor.java:1339-1377`

```java
    public void execute(Runnable command) {
        if (command == null) throw new NullPointerException();
        /*
         * 1. If fewer than corePoolSize threads are running, try to
         *    start a new thread with the given command as its first task.
         * 2. If a task can be successfully queued, then we still need to
         *    double-check whether we should have added a thread ...
         * 3. If we cannot queue task, then we try to add a new thread.
         *    If it fails, we know we are shut down or saturated and so
         *    reject the task.
         */
        int c = ctl.get();
        if (workerCountOf(c) < corePoolSize) {          // ① 线程数 < 核心 → 直接开新线程跑
            if (addWorker(command, true)) return;
            c = ctl.get();
        }
        if (isRunning(c) && workQueue.offer(command)) { // ② 核心满了 → 任务入队
            int recheck = ctl.get();
            if (! isRunning(recheck) && remove(command))
                reject(command);                         //    复查：池刚关了 → 拒绝
            else if (workerCountOf(recheck) == 0)
                addWorker(null, false);                  //    复查：没线程了 → 补一个
        }
        else if (!addWorker(command, false))             // ③ 队列也满了 → 开非核心线程
            reject(command);                             //    还不行 → 拒绝策略
    }
```

**流程图**（任务到达时的决策树）：

```
任务 execute(task)
   │
   ├─ 线程数 < corePoolSize？ ──是──▶ addWorker(新核心线程直接跑)
   │         否
   ├─ 队列 offer 成功？ ──是──▶ 排队等空闲线程取走
   │         否
   ├─ 线程数 < maximumPoolSize？──是──▶ addWorker(非核心线程跑)
   │         否
   └─ reject(task) → 拒绝策略（默认 AbortPolicy 抛异常）
```

注意顺序是**先核心、再队列、最后非核心**——与很多人直觉（先开满线程再入队）相反。后果：`newFixedThreadPool` 用的 `LinkedBlockingQueue` 是**无界队列**，第二步永远成功，`maximumPoolSize` 形同虚设——这正是《阿里巴巴 Java 开发手册》强制"不要用 Executors 快捷工厂、要手动 new ThreadPoolExecutor"的技术根源。

### 10.2.4 Worker：线程池的"工人"，自己也是个 AQS

【源码证据】`java.base/java/util/concurrent/ThreadPoolExecutor.java:608-643、1123-1160、1042-1078`

```java
    private final class Worker
        extends AbstractQueuedSynchronizer          // ★ Worker 本身是个不可重入锁
        implements Runnable
    {
        final Thread thread;                        // 工人的手（真正跑任务的线程）
        Runnable firstTask;                         // 开局任务（可为 null）
        volatile long completedTasks;

        Worker(Runnable firstTask) {
            setState(-1); // inhibit interrupts until runWorker   // 开工前禁止被 shutdown 打断
            this.firstTask = firstTask;
            this.thread = getThreadFactory().newThread(this);
        }
        public void run() { runWorker(this); }      // 线程的使命 = 进入主循环
    }

    final void runWorker(Worker w) {
        Thread wt = Thread.currentThread();
        Runnable task = w.firstTask;
        w.firstTask = null;
        w.unlock(); // allow interrupts             // 解锁：此后允许 shutdown 中断"空闲"状态
        boolean completedAbruptly = true;
        try {
            while (task != null || (task = getTask()) != null) {   // ★ 死循环取任务
                w.lock();                            // 执行期间持 Worker 锁（标记"我忙"）
                if ((runStateAtLeast(ctl.get(), STOP) ||
                     (Thread.interrupted() &&
                      runStateAtLeast(ctl.get(), STOP))) &&
                    !wt.isInterrupted())
                    wt.interrupt();                  // 池已 STOP → 自我中断
                try {
                    beforeExecute(wt, task);         // 钩子
                    try {
                        task.run();                  // ★ 真正执行任务
                        afterExecute(task, null);    // 钩子
                    } catch (Throwable ex) {
                        afterExecute(task, ex);
                        throw ex;
                    }
                } finally {
                    task = null;
                    w.completedTasks++;
                    w.unlock();                      // 执行完释放锁（标记"我闲了"）
                }
            }
            completedAbruptly = false;
        } finally {
            processWorkerExit(w, completedAbruptly); // 取不到任务 → 退出并收尾
        }
    }
```

Worker 继承 AQS 但**故意实现成不可重入锁**（state 只有 0/1）：它的锁不是防并发，而是**标记忙/闲**——`shutdown()` 的 `interruptIdleWorkers` 只对 `w.tryLock()` 成功（= 空闲阻塞在 getTask 里）的线程发中断，正在跑任务的线程（持锁）不会被误伤（`ThreadPoolExecutor.java:799-819`）。

**getTask：空闲线程的生死判官**（`ThreadPoolExecutor.java:1042-1078`）：

```java
    private Runnable getTask() {
        boolean timedOut = false;
        for (;;) {
            int c = ctl.get();
            if (runStateAtLeast(c, SHUTDOWN) && (runStateAtLeast(c, STOP) || workQueue.isEmpty())) {
                decrementWorkerCount(); return null;        // 池在关闭 → 退出
            }
            int wc = workerCountOf(c);
            boolean timed = allowCoreThreadTimeOut || wc > corePoolSize;   // ★ 我是否"可淘汰"
            if ((wc > maximumPoolSize || (timed && timedOut))
                && (wc > 1 || workQueue.isEmpty())) {
                if (compareAndDecrementWorkerCount(c)) return null;   // 空闲超时 → 自杀退出
                continue;
            }
            try {
                Runnable r = timed ?
                    workQueue.poll(keepAliveTime, TimeUnit.NANOSECONDS) :   // 限时等任务
                    workQueue.take();                                        // 核心线程：永久等
                if (r != null) return r;
                timedOut = true;
            } catch (InterruptedException retry) { timedOut = false; }
        }
    }
```

"核心线程默认永不回收"的实现原来如此朴素：核心线程用 `take()` 死等队列，非核心线程用 `poll(keepAliveTime)` 限时等——超时返回 null → runWorker 循环结束 → 线程退出。

### 10.2.5 拒绝策略与关闭

四种内置策略（`ThreadPoolExecutor.java:2051-2143`）：`AbortPolicy`（默认，抛 RejectedExecutionException）、`CallerRunsPolicy`（**让提交者自己跑**——天然的反压：提交慢下来，生产代码 `r.run()`）、`DiscardPolicy`（静默丢弃）、`DiscardOldestPolicy`（丢队首最老的再重试）。

```java
    // CallerRunsPolicy —— ThreadPoolExecutor.java:2051-2055
    public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
        if (!e.isShutdown()) {
            r.run();                       // 在调用者线程里直接执行
        }
    }
```

关闭的两阶段（`ThreadPoolExecutor.java:1390-1435`）：`shutdown()` = 状态→SHUTDOWN + 中断**空闲**工人（存量和在跑的跑完）；`shutdownNow()` = 状态→STOP + 中断**所有**工人 + `drainQueue()` 把没跑的任务还给你。优雅关闭的标准姿势：

```java
// 使用示例：完整构造一个生产级线程池
ThreadPoolExecutor pool = new ThreadPoolExecutor(
        8, 16,                                     // core, max（IO 密集可大于 CPU 数）
        60L, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(1000),            // ★ 有界队列，拒绝策略才有意义
        r -> {                                     // ThreadFactory：命名（排查必备）
            Thread t = new Thread(r, "biz-worker-" + SEQ.incrementAndGet());
            t.setDaemon(false);
            return t;
        },
        new ThreadPoolExecutor.CallerRunsPolicy()); // 打满时让提交者干活（反压）

pool.submit(() -> System.out.println("run in " + Thread.currentThread().getName()));
pool.shutdown();                                   // 不再接新任务
if (!pool.awaitTermination(30, TimeUnit.SECONDS)) { // 优雅等待
    pool.shutdownNow();                             // 超时强停
}
```

参数经验：CPU 密集 ≈ `N_CPU + 1`；IO 密集 ≈ `N_CPU * (1 + 等待时间/计算时间)`，起点可 `2 * N_CPU` 再压测；**队列必须有界**，否则 OOM 只是时间问题。

## 10.3 ScheduledThreadPoolExecutor：定时任务的实现原理

**产生背景**：`Timer` 单线程、异常即崩、时间敏感。JDK 5 用线程池重写了它。

**原理（白话）**：没有魔法线程在"掐表"。所有任务包成 `ScheduledFutureTask`，按**下次执行时间**排序扔进一个小顶堆 `DelayedWorkQueue`（堆顶 = 最早到期的）；工作线程 `take()` 时若堆顶未到期，就 `awaitNanos(剩余时间)` 睡一觉（leader-follower，同 DelayQueue）；到期取出执行。**周期任务 = 执行完重算下次时间再塞回堆里**：

【源码证据】`java.base/java/util/concurrent/ScheduledThreadPoolExecutor.java:300-309、272-285、616-634`

```java
        public void run() {
            if (!canRunInCurrentRunState(this))
                cancel(false);
            else if (!isPeriodic())
                super.run();                       // 一次性任务：正常跑完置结果
            else if (super.runAndReset()) {        // 周期任务：跑完但"不置结果、复位状态"
                setNextRunTime();                  //    重算下次时间
                reExecutePeriodic(outerTask);      //    重新入队
            }
        }

        private void setNextRunTime() {
            long p = period;
            if (p > 0)
                time += p;                         // fixedRate：以上次"计划时间"+period（追进度）
            else
                time = triggerTime(-p);            // fixedDelay：以本次"实际结束时间"+delay
        }
```

`fixedRate` 与 `fixedDelay` 的区别就在 `period` 的**符号**（fixedRate 传正数、fixedDelay 传负数，见 `scheduleWithFixedDelay` 的 `-unit.toNanos(delay)`，`:664-682`）。另外注意 `runAndReset()`：任务抛出未捕获异常时状态不会复位，**周期任务会静默停摆**——周期任务体内必须自己 try-catch。

```java
ScheduledExecutorService ses = Executors.newScheduledThreadPool(2);
ses.scheduleAtFixedRate(() -> { try { heartbeat(); } catch (Throwable t) { log(t); } },
                        0, 1, TimeUnit.SECONDS);      // 固定频率：每秒一次（不管上次多久）
ses.scheduleWithFixedDelay(() -> { try { sync(); } catch (Throwable t) { log(t); } },
                        0, 5, TimeUnit.SECONDS);      // 固定间隔：上次结束后再等 5 秒
```

## 10.4 Future 与 FutureTask：给异步一个"收据"

**产生背景**：`Runnable.run()` 没有返回值也不能抛受检异常——异步任务的"结果"和"异常"需要一张凭证。`Future` 就是凭证接口：

【源码证据】`java.base/java/util/concurrent/Future.java:39-47`（javadoc）、88-163（五个方法）

```java
 * A Future represents the result of an asynchronous computation. ...
 * The result can only be retrieved using method get when the computation
 * has completed, blocking if necessary until it is ready.
    boolean cancel(boolean mayInterruptIfRunning);
    boolean isCancelled();
    boolean isDone();
    V get() throws InterruptedException, ExecutionException;          // 死等结果
    V get(long timeout, TimeUnit unit) throws ... TimeoutException;   // 限时等
```

`FutureTask` 是"一个 Runnable + 一个 Future"的合体（提交给 Thread/线程池、自己保管结果），核心是 **7 状态的状态机**：

【源码证据】`java.base/java/util/concurrent/FutureTask.java:76-108、307-337、281-287`

```java
     * Possible state transitions:
     * NEW -> COMPLETING -> NORMAL
     * NEW -> COMPLETING -> EXCEPTIONAL
     * NEW -> CANCELLED
     * NEW -> INTERRUPTING -> INTERRUPTED
     */
    private volatile int state;
    private static final int NEW = 0, COMPLETING = 1, NORMAL = 2,
                             EXCEPTIONAL = 3, CANCELLED = 4,
                             INTERRUPTING = 5, INTERRUPTED = 6;
    private Object outcome;                  // 结果/异常（非 volatile，靠 state 的读写做内存屏障）
    private volatile Thread runner;          // 执行线程（CAS 抢跑）
    private volatile WaitNode waiters;       // 等结果的线程栈（Treiber stack）

    public void run() {
        if (state != NEW ||
            !RUNNER.compareAndSet(this, null, Thread.currentThread()))   // 只允许一个线程跑
            return;
        try {
            Callable<V> c = callable;
            if (c != null && state == NEW) {
                V result;
                boolean ran;
                try {
                    result = c.call();
                    ran = true;
                } catch (Throwable ex) {
                    result = null; ran = false;
                    setException(ex);        // 异常也存进 outcome
                }
                if (ran) set(result);
            }
        } finally { ... }
    }

    protected void set(V v) {
        if (STATE.compareAndSet(this, NEW, COMPLETING)) {
            outcome = v;
            STATE.setRelease(this, NORMAL);  // 置终态（release 写：结果对 get() 可见）
            finishCompletion();              // 唤醒所有 waiters 里的等待线程
        }
    }
```

要点：① `RUNNER.compareAndSet(null → 我)` 保证**任务只被一个线程执行一次**；② outcome 不需要 volatile，因为读写顺序被 state 的 volatile 语义隔开（注释原话：non-volatile, protected by state reads/writes）；③ `get()` 未完成时把当前线程挂到 waiters 栈上 park（`awaitDone`，`:455-501`）。`cancel(true)` 的语义 = 尝试 interrupt 执行线程（2.5 节的协作式中断！）——所以任务不响应中断的话 cancel(true) 也只能干等。

```java
// 使用示例（承接 2.2 的 Callable）
FutureTask<Integer> ft = new FutureTask<>(() -> heavyCompute());
new Thread(ft, "calc").start();
Integer r = ft.get(3, TimeUnit.SECONDS);     // 限时等；超时 TimeoutException
```

**Future 的致命局限**：`get()` 一阻塞，异步就退化成同步；想知道"A 完成后自动做 B"只能轮询或层层 get。这就轮到 CompletableFuture 登场。

---
## 10.5 CompletableFuture：会主动"打电话"的 Future

**产生背景**：Future 是"你去问"（get 阻塞/轮询），CompletableFuture 是"我告诉你"——完成时**自动触发**注册在其上的后续动作（回调），还提供把多个异步步骤串联/汇聚的编排能力。JDK 8 引入，实现出自 Doug Lea 之手。

【源码证据】`java.base/java/util/concurrent/CompletableFuture.java:48-72`（javadoc）

```java
/**
 * A {@link Future} that may be explicitly completed (setting its
 * value and status), and may be used as a {@link CompletionStage},
 * supporting dependent functions and actions that trigger upon its
 * completion.
 */
     * <li>Actions supplied for dependent completions of non-async methods
     * may be performed by the thread that completes the current
     * CompletableFuture, or by any other caller of a completion method.
     * <li>All async methods without an explicit Executor argument are
     * performed using the ForkJoinPool.commonPool() ...
```

javadoc 定义了两个关键行为：**① 可以被外部显式 complete**（不一定要真跑了任务）；**② 非 async 的回调由"完成它的线程"执行，async 系列默认用 `ForkJoinPool.commonPool()`**——这解释了回调到底跑在哪个线程（排查问题时必知）。

### 10.5.1 数据结构：结果 + 一条依赖栈

【源码证据】`java.base/java/util/concurrent/CompletableFuture.java:265-266、459-486`

```java
    volatile Object result;       // Either the result or boxed AltResult   ← 结果（异常用 AltResult 包装）
    volatile Completion stack;    // Top of Treiber stack of dependent actions  ← 依赖动作的栈

    abstract static class Completion extends ForkJoinTask<Void>
        implements Runnable, AsynchronousCompletionTask {
        volatile Completion next;      // Treiber stack link
        abstract CompletableFuture<?> tryFire(int mode);   // mode: SYNC / ASYNC / NESTED
    }
```

**原理（白话）**：每个 CF 有两个 volatile 字段：结果和一条**无锁栈**。`thenApply(f)` 时若结果还没好，就把一个包装了 f 的 `UniApply` 节点 CAS 压入栈；将来 `complete(v)` 时（CAS 写入 result）沿栈逐个弹出节点、调用其 `tryFire`——回调被"引爆"。若调用 thenApply 时结果**已经**在，则直接同步执行（`uniApplyNow`）。这就是"主动通知"的全部秘密。

【源码证据】`CompletableFuture.java:657-666`（uniApplyStage）、`2177-2181`（complete）、`492-513`（postComplete 骨架）

```java
    private <V> CompletableFuture<V> uniApplyStage(Executor e, Function<? super T,? extends V> f) {
        if (f == null) throw new NullPointerException();
        Object r;
        if ((r = result) != null)
            return uniApplyNow(r, e, f);                    // 已完成：立刻执行
        CompletableFuture<V> d = newIncompleteFuture();     // 未完成：造新 CF（返回值）
        unipush(new UniApply<T,V>(e, d, this, f));          // 依赖入栈
        return d;
    }

    public boolean complete(T value) {
        boolean triggered = completeValue(value);           // CAS(result: null → value)
        postComplete();                                     // 引爆依赖栈！
        return triggered;
    }

    final void postComplete() {
        CompletableFuture<?> f = this; Completion h;
        while ((h = f.stack) != null || (f != this && (h = (f = this).stack) != null)) {
            CompletableFuture<?> d; Completion t;
            if (STACK.compareAndSet(f, h, t = h.next)) {    // 无锁弹栈
                ...
                f = (d = h.tryFire(NESTED)) == null ? this : d;   // 执行回调，沿依赖链续传
            }
        }
    }
```

异常传播也走同一条路：源 CF 的 result 是 `AltResult`（包着异常）时，依赖 CF 不执行 fn，而是 `d.completeThrowable(x)`——**异常沿着 then 链一路穿透**直到被 `exceptionally/handle` 接住（`UniApply.tryFire` 中 `if (r instanceof AltResult)` 分支，`:634-640`；`uniExceptionally`，`:981-998`）。`allOf` 则递归二分构造 `BiRelay`（`:1508-1532`）：两个子 CF 都完成才放行，任一异常则传播。

### 10.5.2 使用示例：异步编排全家桶

```java
// 0) 三种创建方式
CompletableFuture<String> manual = new CompletableFuture<>();
new Thread(() -> {
    String v = expensiveRpc();
    manual.complete(v);                          // 显式完成（还能 completeExceptionally(ex)）
}).start();

CompletableFuture<Integer> f1 = CompletableFuture.supplyAsync(() -> queryDb(), bizPool);  // 用自己的池！
CompletableFuture<Integer> f2 = CompletableFuture.supplyAsync(() -> queryCache());        // 不传池 → commonPool

// 1) 串联：A 的结果给 B（thenApply 转换 / thenAccept 消费 / thenRun 不关心结果）
f1.thenApply(x -> x * 2)
  .thenAccept(System.out::println)
  .thenRun(() -> log("done"));

// 2) 汇聚：两个都完成后合并
f1.thenCombine(f2, Integer::sum).thenAccept(total -> System.out.println("合计=" + total));

// 3) 等一批全部完成（常用于并发打多个下游接口）
List<CompletableFuture<String>> futures = urls.stream()
        .map(u -> CompletableFuture.supplyAsync(() -> fetch(u), bizPool)
                                  .exceptionally(e -> "fallback:" + u))   // 单个失败不拖垮全部
        .toList();
CompletableFuture<List<String>> all =
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .thenApply(v -> futures.stream().map(CompletableFuture::join).toList());

// 4) 异常处理：exceptionally 只管异常 / handle 结果与异常都给
f1.thenApply(x -> 10 / x)
  .exceptionally(ex -> { log(ex); return -1; })
  .thenAccept(System.out::println);

// 5) 超时（JDK 9+）
f1.orTimeout(2, TimeUnit.SECONDS).get();
```

**两个必踩的坑**：① 默认线程池是 `ForkJoinPool.commonPool()`（CPU 数 -1，全局共享、线程名 ForkJoinPool.commonPool-worker-N），**业务代码请始终传入自己的池**；② `thenApply` 的回调可能跑在"上游完成的那条线程"上（javadoc 明说），回调里有耗时操作会拖慢别人——耗时回调一律用 `thenApplyAsync(..., pool)`。

## 10.6 ForkJoinPool：工作窃取——分治任务的专属池

**产生背景**：归并排序、目录扫描、大规模矩阵运算这类**分治（fork-join）**任务有个特点：任务在执行中动态分裂出子任务、父任务必须 join 子任务的结果。普通线程池里父任务占着一个线程干等子任务，池子很快被"等待者"占满。ForkJoinPool（JDK 7）的解法：**每个线程有自己的双端队列（自己的 fork 出的子任务从头部取），干完自己的活就去偷别人队列尾部的任务**——工作窃取（work-stealing）。

【源码证据】`java.base/java/util/concurrent/ForkJoinPool.java:68-79`（javadoc 关键句）

```java
     * <p>A ForkJoinPool differs from other kinds of ExecutorService
     * mainly by virtue of employing work-stealing: all threads in the
     * pool attempt to find and execute tasks submitted to the pool
     * and/or created by other active tasks (eventually blocking waiting
     * for work if none exist). This enables efficient processing when
     * most tasks spawn other subtasks (as do most ForkJoinTasks), ...
```

【源码证据】`java.base/java/util/concurrent/ForkJoinTask.java:623-655`（fork/join）

```java
    public final ForkJoinTask<V> fork() {
        Thread t; ForkJoinWorkerThread wt;
        ForkJoinPool p; ForkJoinPool.WorkQueue q;
        U.storeStoreFence();  // ensure safely publishable
        if ((t = Thread.currentThread()) instanceof ForkJoinWorkerThread) {
            p = (wt = (ForkJoinWorkerThread)t).pool;
            q = wt.workQueue;                    // FJ 线程：子任务 push 进"自己"的队列头部
        }
        else
            q = (p = ForkJoinPool.common).submissionQueue(false);   // 外部线程：进提交队列
        q.push(this, p, true);
        return this;
    }
    public final V join() {
        int s;
        if ((s = status) >= 0)
            s = awaitDone(s & POOLSUBMIT, 0L);   // 未完成时：先帮它跑/帮别人跑，而不是干等
        if ((s & ABNORMAL) != 0) reportException(s);
        return getRawResult();
    }
```

窃取的关键在于**两端操作方向相反**：owner 从自己队列的**头**取（LIFO，缓存友好、深度优先利于 join），小偷从**尾**偷（FIFO，偷到的是最早分裂、颗粒最大的任务，减少偷的次数）；`join()` 的 `awaitDone` 在等待时还会**帮着干活**（helping），这也是它比"线程阻塞等 Future"高明的地方。

```java
// 使用示例：RecursiveTask 分治求和
class SumTask extends RecursiveTask<Long> {
    static final int THRESHOLD = 10_000;         // 粒度阈值：小于它就不拆了
    private final long[] arr; private final int lo, hi;
    SumTask(long[] arr, int lo, int hi) { this.arr = arr; this.lo = lo; this.hi = hi; }

    @Override protected Long compute() {
        if (hi - lo <= THRESHOLD) {              // 足够小：直接算
            long s = 0;
            for (int i = lo; i < hi; i++) s += arr[i];
            return s;
        }
        int mid = (lo + hi) >>> 1;
        SumTask left = new SumTask(arr, lo, mid), right = new SumTask(arr, mid, hi);
        left.fork();                             // 左半边扔给池（进自己队列）
        long rightResult = right.compute();      // 右半边当前线程自己算（省一个任务）
        return rightResult + left.join();        // 等左半边结果（等待时可能去帮别人）
    }
}
long total = ForkJoinPool.commonPool().invoke(new SumTask(data, 0, data.length));
```

`ForkJoinPool.commonPool()` 是 JVM 内置的全局池（parallelism 默认 = CPU 数 -1，`ForkJoinPool.java:2766-2780`），**CompletableFuture 默认回调、并行流 `parallelStream()` 都跑在它上面**——所以并行流里做阻塞 IO 会拖垮全局，这是常见的性能事故来源。

## 10.7 虚拟线程（JDK 21）：把"线程"变得和对象一样便宜

**产生背景**：线程池解决的是"线程太贵，所以要复用"，但复用带来了新复杂度：任务排队、拒绝策略、上下文丢失……根源是**一个平台线程 = 一个 OS 线程**，1MB 栈 + 内核调度，撑不起"每个请求一个线程"的直白模型。JDK 21（JEP 444）的虚拟线程换了个思路：**线程对象极其便宜（JVM 对象），成千上万个虚拟线程复用在少量 OS 线程（载体线程 carrier）上跑；虚拟线程阻塞时，JVM 把它的栈从载体上"卸载"下来存到堆里，载体立刻去跑别的虚拟线程**——阻塞不再占用 OS 线程。

【源码证据】`java.base/java/lang/VirtualThread.java:64-86`（定位与核心字段）

```java
/**
 * A thread that is scheduled by the Java virtual machine rather than the operating
 * system.
 */
final class VirtualThread extends BaseVirtualThread {
    private static final ForkJoinPool DEFAULT_SCHEDULER = createDefaultScheduler();
    // scheduler and continuation
    private final Executor scheduler;      // 默认：专用 ForkJoinPool
    private final Continuation cont;       // ★ 载体：可暂停/恢复的执行栈
    private final Runnable runContinuation;
    private volatile int state;
```

**两个核心动作的源码**：

【源码证据】`VirtualThread.java:585-609`（park）、`446-453`（yieldContinuation）、`221-254`（runContinuation 骨架）

```java
    void park() {
        ...
        boolean yielded = false;
        setState(PARKING);
        try {
            yielded = yieldContinuation();      // ★ 让出载体线程
        } finally {
            if (!yielded) setState(RUNNING);
        }
        // park on the carrier thread when pinned
        if (!yielded) parkOnCarrierThread(false, 0);   // 没让出去（pinned）→ 退化：占着载体睡
    }

    private boolean yieldContinuation() {
        notifyJvmtiUnmount(/*hide*/true);
        try {
            return Continuation.yield(VTHREAD_SCOPE);   // ★ 一行核心：栈从载体上摘下、存入堆
        } finally {
            notifyJvmtiMount(/*hide*/false);
        }
    }

    private void runContinuation() {                    // 被调度时在"载体（平台线程）"上执行
        if (Thread.currentThread().isVirtual()) throw new WrongThreadException();
        ...compareAndSetState(initialState, RUNNING)...
        mount();                                        // 把栈装回某个载体线程
        try {
            cont.run();                                 // 从上次 park 的地方继续跑
        } finally {
            unmount();
            if (cont.isDone()) afterDone(); else afterYield();
        }
    }
```

`unpark()` 时把 state CAS 回 UNPARKED，再 `scheduler.execute(runContinuation)` 把"续跑"任务提交给 ForkJoinPool 调度器（`VirtualThread.java:736-762、262-269`）——**虚拟线程的调度器本身就是一个 ForkJoinPool**，第 10.6 节的工作窃取在这里复用。

**pinned（钉住）注意点**：JDK 21 中，虚拟线程若在 **synchronized 块内阻塞**、或执行 native 方法时阻塞，栈无法卸载，会"钉"在载体线程上（state 常量表 `VirtualThread.java:114-134` 的 `PINNED`）——大量钉住会耗尽载体线程，池化时代的代码迁移过来要小心（可用 `-Djdk.tracePinnedThreads=full` 排查；把 synchronized 换成 ReentrantLock 是标准处方）。**JDK 24 的 JEP 491 已解除 synchronized 钉住问题**。

```java
// 使用示例：一万个"每任务一线程"的 IO 任务（平台线程模型下不敢想）
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {   // 每任务一个虚拟线程
    for (int i = 0; i < 10_000; i++) {
        executor.submit(() -> {
            Thread.sleep(1000);                       // 阻塞的是虚拟线程，不占 OS 线程
            return callRemoteService();               // IO 密集场景吞吐暴涨
        });
    }
}   // try-with-resources 自动 close（等全部完成并关闭）

Thread.ofVirtual().name("v-worker-", 0).start(() -> System.out.println("虚拟线程跑起来"));
```

**适用边界**：虚拟线程的优势场景是**IO 密集 + 高并发**（Web 服务、RPC 调用、批量外部请求）；**CPU 密集任务依然用线程池/ForkJoinPool**（虚拟线程不减少 CPU 工作量）；池化虚拟线程是反模式（它本身就是"无限池"）。

## 10.8 异步工具选型小结

| 工具 | 一句话定位 | 典型场景 |
|---|---|---|
| ThreadPoolExecutor | 固定资源的并行执行器 | CPU/IO 任务的通用管理 |
| ScheduledThreadPoolExecutor | 时间驱动的任务队列 | 心跳、定时补偿、周期巡检 |
| Future/FutureTask | 一次性异步结果凭证 | 提交任务后按需取结果 |
| CompletableFuture | 依赖编排 + 回调通知 | 并发调多个下游、聚合/降级 |
| ForkJoinPool | 分治 + 工作窃取 | CPU 密集可拆分计算（并行流的底座） |
| 虚拟线程 | 便宜到可"每请求一线程" | IO 密集高并发（JDK 21+） |

---
# 十一、收尾：全景决策表与陷阱清单

## 11.1 问题 → 工具决策表（速查）

| 你要做的事 | 不要用 | 应该用 | 理由关键词 |
|---|---|---|---|
| 停止一个线程 | `Thread.stop()`（废弃） | interrupt + 协作检查 | 协作式中断（2.5） |
| 状态标志位 | 普通 boolean | volatile | 可见性（3.2） |
| 简单互斥 | 手写标志位 | synchronized | 自动解锁、现代性能足够（4.2） |
| 可尝试/超时/可中断的锁 | synchronized | ReentrantLock | 四大局限（4.5/5.4） |
| 生产者-消费者 | wait/notifyAll 手写 | BlockingQueue | 双 Condition 标准件（8.4） |
| 并发 Map | Hashtable / synchronizedMap | ConcurrentHashMap | 桶级锁 + CAS（8.1） |
| 高并发计数 | AtomicLong（高竞争） | LongAdder | 热点分离（7.4） |
| 读多写少 | 独占锁 | ReentrantReadWriteLock / StampedLock | 读-读共享/乐观读（第六章） |
| 等 N 件事完成 | 轮询 isDone | CountDownLatch / CompletableFuture.allOf | AQS 共享（9.2/10.5） |
| 限流最多 N 并发 | 手写计数+synchronized | Semaphore | 许可数（9.4） |
| 线程生命周期管理 | 裸 new Thread | ThreadPoolExecutor（有界队列！） | 复用 + 反压（10.2） |
| 异步任务编排 | Future.get 层层嵌套 | CompletableFuture | 依赖栈 + 回调（10.5） |
| CPU 密集分治 | 手动切线程池 | ForkJoinPool / RecursiveTask | 工作窃取（10.6） |
| 海量 IO 并发（JDK21+） | 调大线程池 | 虚拟线程 | 阻塞不占 OS 线程（10.7） |

## 11.2 并发十大陷阱清单（每一都能在本文找到源码级解释）

1. **`count++` 丢更新**：读-改-写非原子 → synchronized / AtomicInteger（1.1、7.2）。
2. **`volatile` 期待原子性**：volatile 只保可见有序，`i++` 照样错 → 换原子类或锁（3.1）。
3. **wait 用 if 不用 while**：虚假唤醒 → 必须循环判断条件（4.3）。
4. **notify 叫错人**：单一等待集合 → notifyAll 或拆 Condition（4.5、5.5）。
5. **ReentrantLock 忘了 unlock**：没有字节码级的异常兜底 → 必须 try-finally（5.2）。
6. **ThreadLocal 不 remove**：池化线程长活 → value 泄漏（2.6）。
7. **无界队列线程池**：newFixedThreadPool 的 LinkedBlockingQueue 无界 → 手动 new + 有界队列（10.2.3）。
8. **周期任务异常即停摆**：ScheduledThreadPoolExecutor 的 runAndReset 不复位状态 → 任务体自捕异常（10.3）。
9. **CompletableFuture 默认池**：commonPool 全局共享 → 传自己的 Executor；回调注意执行线程（10.5）。
10. **CHM 复合操作不原子**：`containsKey + put` 两步走 → putIfAbsent / computeIfAbsent（8.1）。

## 11.3 一张总图回顾全文

```
                     ┌──────────────────────────────────────────┐
                     │            JMM（happens-before）          │  ← 一切的合同（第一章）
                     └──────────────────────────────────────────┘
   语言级地基          Thread / volatile / synchronized+wait·notify / ThreadLocal
   阻塞与唤醒原语      LockSupport（park/unpark，permit）
   排队框架            AQS：volatile state + FIFO 队列 + 模板方法（tryAcquire/Release）
                         ├── ReentrantLock（独占） ── Condition（多等待集合）
                         ├── ReentrantReadWriteLock（state 拆高低 16 位）
                         ├── Semaphore（state=许可） / CountDownLatch（state=计数）
                         └── 线程池 Worker（忙闲标记）
   硬件原子指令        CAS（Unsafe.compareAndSetXxx）
                         ├── AtomicXxx（volatile+CAS 自旋）
                         ├── LongAdder/Striped64（热点分离+@Contended）
                         └── ConcurrentHashMap（空桶 CAS / 桶头锁 / CounterCell）/ CLQ
   会合语义            CyclicBarrier（Lock+Condition 换代）/ Exchanger（CAS 配对）/ Phaser（64 位位段）
   执行模型            Executor → ThreadPoolExecutor（ctl 三步决策）→ Scheduled（小顶堆重排队）
                         → Future/FutureTask（状态机）→ CompletableFuture（依赖栈回调）
                         → ForkJoinPool（工作窃取）→ 虚拟线程（Continuation 卸载，JDK21）
```

## 11.4 延伸阅读

- 系列同篇：[《JDK 中的 Flow（响应式流）深度剖析》](Flow.md)——CompletableFuture 管"单个结果的通知与编排"，Flow 管"连续数据的受控传输"，两者互为补充。
- 同目录：[JDK 线程池(包含定时任务)笔记](cursor_JDK_线程池(包含定时任务).md)。
- 经典书目：Doug Lea《Java Concurrency in Practice》（JCiP，JSR-166 同作者）、《Java 虚拟机规范》§2.11.10/§17；Doug Lea 的 AQS 论文 *"Abstract Queued Synchronizer"* 与 Michael & Scott 1996 队列论文（ConcurrentLinkedQueue 的 javadoc 里附了链接）。
- 动手验证：本文所有【源码证据】都可用 IDEA 的 "Download Sources" 或直接查看 `D:\soft\jdk\jdk-21\lib\src.zip` 复现；synchronized 字节码可用 `javap -c -v` 自验（见 4.2 的实测输出）。
