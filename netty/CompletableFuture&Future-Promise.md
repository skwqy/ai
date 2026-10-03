# CompletableFuture 与 Netty Future/Promise 深度剖析

> **分析依据**：JDK 侧基于本机 JDK 27 源码（`java.base/java/util/concurrent/CompletableFuture.java`，3042 行）实际走读，涉及版本差异处单独标注；Netty 侧基于本地源码 `netty-4.2.18.Final`（`D:\code\3rd\netty-netty-4.2.18.Final`）的 `common/.../concurrent/` 与 `transport/.../channel/` 实际走读，文中标注 `文件:行号`。
>
> 两者解决的是同一个问题——**异步操作的"结果句柄"**，但给出了两套几乎相反的答案：CompletableFuture 是"**每个算子产生新阶段的函数式管道**"，Netty 是"**单对象 + 监听器 + 线程亲和的事件通知**"。本文分三节：第一节剖析 CompletableFuture（实现原理 → 接口分类 → 场景示例 → 陷阱），第二节以同样结构剖析 Netty Future/Promise，第三节全面对比并给出选型与互操作方案。

---

## 目录

- [一、JDK 中的 CompletableFuture](#一jdk-中的-completablefuture)
  - [1.1 设计定位与演进脉络](#11-设计定位与演进脉络)
  - [1.2 实现原理（源码级走读）](#12-实现原理源码级走读)
  - [1.3 接口全景（分类）](#13-接口全景分类)
  - [1.4 使用场景与示例](#14-使用场景与示例)
  - [1.5 陷阱清单](#15-陷阱清单)
- [二、Netty 中的 Future/Promise](#二netty-中的-futurepromise)
  - [2.1 设计定位与接口体系](#21-设计定位与接口体系)
  - [2.2 实现原理（DefaultPromise 源码级走读）](#22-实现原理defaultpromise-源码级走读)
  - [2.3 接口全景（分类）](#23-接口全景分类)
  - [2.4 使用场景与示例](#24-使用场景与示例)
  - [2.5 陷阱清单](#25-陷阱清单)
- [三、对比分析](#三对比分析)
  - [3.1 两种范式一图看懂](#31-两种范式一图看懂)
  - [3.2 全维度对比表](#32-全维度对比表)
  - [3.3 线程模型对比：不确定 vs 确定](#33-线程模型对比不确定-vs-确定)
  - [3.4 异常模型对比：数据流 vs 终态字段](#34-异常模型对比数据流-vs-终态字段)
  - [3.5 组合能力实战对照](#35-组合能力实战对照)
  - [3.6 性能与内存对比](#36-性能与内存对比)
  - [3.7 完成语义与取消对比](#37-完成语义与取消对比)
  - [3.8 互操作桥接](#38-互操作桥接)
  - [3.9 选型建议](#39-选型建议)

---

## 一、JDK 中的 CompletableFuture

### 1.1 设计定位与演进脉络

| 时间 | 产物 | 形态 | 核心局限 |
|---|---|---|---|
| JDK 1.5 (2004) | `java.util.concurrent.Future` | 结果句柄：只能阻塞 `get()` 或轮询 `isDone()` | 无回调、无组合；异常被 `ExecutionException` 强行包一层；任务一旦提交无法表达"完成后再做 X" |
| ~2010 | Guava `ListenableFuture` | Future + 监听器回调 | 非标准库；组合能力仍弱（早期靠 `Futures.transform`） |
| **JDK 8 (2014)** | **`CompletableFuture`**（Doug Lea，JSR-166） | `Future` + `CompletionStage`：函数式异步编排 | 主流方案，Java 异步的事实标准 |
| JDK 9 (2017) | 大幅增强 | 新增 `orTimeout` / `completeOnTimeout` / `completeAsync` / `delayedExecutor` / `copy` / `minimalCompletionStage` / `failedFuture` / `completedStage`；开放 `defaultExecutor()`、`newIncompleteFuture()` 供子类定制 | — |
| JDK 12 | 补齐异常组合 | `exceptionallyAsync` / `exceptionallyCompose` / `exceptionallyComposeAsync` | — |
| JDK 19+ | 对齐新 `Future` 接口 | `state()` / `resultNow()` / `exceptionNow()`（`Future.State` 枚举：RUNNING/SUCCESS/FAILED/CANCELLED） | — |
| JDK 21+ | 虚拟线程时代 | **默认执行器不变**（仍为 commonPool，见 1.2.9）；要跑虚拟线程需显式传 `Executors.newVirtualThreadPerTaskExecutor()` | — |

理解这个类先要理解它的**三重身份**：

1. **`CompletionStage<T>` 的实现**——一个可组合的异步计算"阶段"，约 50 个组合方法（转换/消费/合并/竞速/异常恢复）；
2. **`Future<T>` 的实现**——可阻塞读取（`get/join`）；
3. **可被外部完成的容器**——任何人拿到引用都能调 `complete(v)` / `completeExceptionally(t)` 手动写结果，这正是类名中 "Completable" 的含义，也是它区别于 `FutureTask`（结果只能由内部任务产生）的关键。

设计目标只有一个：**把"完成后做什么"从回调地狱变成数据流声明**。代价是每个算子一个新对象、回调线程不确定——这两个代价正是后面 Netty 做出不同选择的原因。

### 1.2 实现原理（源码级走读）

整个 `CompletableFuture`（3000+ 行）只靠**两个 volatile 字段 + 一族 Completion 内部类**支撑，全部无锁（CAS + VarHandle）：

```java
public class CompletableFuture<T> implements Future<T>, CompletionStage<T> {
    volatile Object result;       // 完成态的唯一载体：null = 未完成，非 null = 已完成
    volatile Completion stack;    // 依赖本阶段的后续动作，Treiber 栈（无锁链表，LIFO）
}
```

#### ① 状态表示：一个字段编码四种结果

`result` 的取值规则（`CompletableFuture.java:286-306`）：

| result 的值 | 含义 |
|---|---|
| `null` | 未完成 |
| 非 null 的业务值 `t` | 成功，结果就是 t 本身（零包装开销） |
| `NIL`（单例 `new AltResult(null)`） | 成功但值为 null |
| `AltResult(ex)`（ex 非 null） | 异常完成，`AltResult` 是仅含一个 `Throwable ex` 字段的包装盒 |

配套一组编码/解码原语：`encodeValue(t)`（null→NIL）、`encodeThrowable(x)`（非 `CompletionException` 统一包成 `CompletionException`）、`encodeRelay(r)`（跨阶段转发时的再包装）。**完成 = 一次 `RESULT.compareAndSet(this, null, r)`**（`internalComplete`，`CompletableFuture.java:268`），原子性天然保证"一个 CF 只能被完成一次"——两个线程竞争 `complete()`，只有一个成功。

#### ② 依赖动作：Completion 内部类族

每个 `thenXxx` 注册的"后续动作"是一个 Completion 对象，压到**上游**的 `stack` 栈顶。类族谱系（源码注释 `:168-176` 自述，JDK 27 实测共 21 个 Completion 子类 + 若干辅助类）：

```
Completion (abstract, extends ForkJoinTask<Void> implements Runnable)
│   统一契约: tryFire(mode) 触发动作并返回"需要继续传播的下游"; isLive() 供 cleanStack 判死活
├── UniCompletion<T,V>            ← 单源动作基类：executor / dep(下游) / src(上游) 三要素
│   ├── UniApply   (thenApply)    ├── UniAccept  (thenAccept)
│   ├── UniRun     (thenRun)      ├── UniWhenComplete / UniHandle (whenComplete/handle)
│   ├── UniExceptionally / UniComposeExceptionally (exceptionally 族)
│   ├── UniCompose (thenCompose)  └── UniRelay   (结果原样转发, copy/toCompletableFuture 用)
├── BiCompletion<T,U,V> extends UniCompletion   ← 双源动作基类, 加一个 snd(第二源)
│   ├── BiApply / BiAccept / BiRun     (thenCombine / thenAcceptBoth / runAfterBoth, AND 语义)
│   ├── OrApply / OrAccept / OrRun     (applyToEither 族, OR 语义)
│   └── BiRelay                        (allOf 树的内部转发的"与"节点)
├── CoCompletion                  ← 双源动作在"另一个源"上的占位代理(见 ⑥)
├── AnyOf                         ← anyOf 的输入 future 上的完成节点(见 ⑥)
├── Signaller                     ← get()/join() 阻塞等待的唤醒节点(见 ⑦)
├── AsyncSupply / AsyncRun        ← supplyAsync / runAsync 的任务体(零输入源)
└── Timeout / Canceller / DelayedExecutor / TaskSubmitter / MinimalStage  (超时/延迟/最小视图辅助)
```

两个精妙的继承设计：

- **`Completion extends ForkJoinTask<Void>`**（`:488`）：不为了 work-stealing，而是①可以直接 `execute` 到任何 Executor（同时实现了 Runnable）；②**复用 ForkJoinTask 状态字里的 tag 位**做"认领"标记（见 ④），一个字段都不多花。早期 JDK 版本用每个动作自带的 `volatile Thread runner` 字段 CAS 做同样的事，新版改成 tag 位后省掉了这个字段。
- 每个动作持有 `dep/src/fn/executor` 四要素，**触发完成后立刻把自己字段置 null**（如 `UniApply.tryFire` 末尾的 `src = null; dep = null; fn = null;`，`:673`）——源码注释解释：CF 链是"下游指回上游"的引用链，不主动断链会积累垃圾，所以"能 null 就 null"。

#### ③ 注册路径：thenApply 的完整走读

```java
private <V> CompletableFuture<V> uniApplyStage(Executor e, Function<? super T,? extends V> f) {
    Object r;
    Objects.requireNonNull(f);
    if ((r = result) != null)
        return uniApplyNow(r, e, f);              // 快路径：上游已完成
    CompletableFuture<V> d = newIncompleteFuture(); // ① 新建下游阶段
    unipush(new UniApply<T,V>(e, d, this, f));      // ② 动作压入上游 Treiber 栈
    return d;
}
```

- **快路径**（`uniApplyNow`，`:689`）：上游已完成——若携带异常则**直接把异常编进 d，fn 根本不会被调用**；否则非 async 时**在调用者线程当场执行 f**（这就是"非 async 回调线程不确定"的来源之一），async 时包成 `UniApply` 提交 executor。
- **慢路径** `unipush`（`:605`）：自旋 `tryPushStack`（CAS 改栈顶，`next` 指针用 VarHandle 写入做 CAS 搭载），**push 完再复查一次 `result != null`**——覆盖"push 的同时上游恰好完成"的竞态，此时直接 `c.tryFire(SYNC)` 补触发。挂在栈上的动作可能永远不会被 pop，靠 `cleanStack()`（`:537`）在后续操作时惰性摘除死节点（`isLive()==false` 的）。

#### ④ 触发路径：tryFire 的三种模式与 claim 防重

`tryFire(int mode)` 是所有 Completion 的核心方法，mode 三个常量（`:481-483`）：

```java
static final int SYNC   =  0;   // 同步触发：在触发者线程直接执行动作
static final int ASYNC  =  1;   // 已作为任务提交到 executor 后，任务线程内执行
static final int NESTED = -1;   // 完成传播链上的递归触发（防栈溢出的蹦床，见 ⑤）
```

以 `UniApply.tryFire`（`:648-675`）为例，逐行语义：

```java
final CompletableFuture<V> tryFire(int mode) {
    // 1. 屏蔽检查：上游没结果 / 字段已被清理 → 返回 null（不触发）
    if ((a = src) == null || (r = a.result) == null || (d = dep) == null || (f = fn) == null)
        return null;
    if (d.result == null) {
        // 2. 异常短路：上游异常 → 直接把异常转发给下游，fn 被跳过（异常即数据）
        if (r instanceof AltResult && (x = ((AltResult)r).ex) != null) {
            d.completeThrowable(x, r); break tryComplete;
        }
        // 3. 认领：mode<=0（SYNC/NESTED）且带 executor 时，CAS tag 位 0→1；
        //    抢到的线程把 executor 置 null 并把自己 submit 出去，然后返回 null；
        //    任务随后在 executor 线程里以 ASYNC 模式再次进入 tryFire，此时 claim 放行。
        //    → 保证并发触发时动作只被执行一次、且一定在指定 executor 上执行
        if (mode <= 0 && !claim())
            return null;
        // 4. 真正执行业务函数，结果 CAS 进下游；fn 抛异常 → completeThrowable(ex)
        d.completeValue(f.apply(t));
        // 5. src/dep/fn 置 null（断引用防 GC 堆积）
    }
    return d.postFire(a, mode);   // 返回需要继续传播的下游（见 ⑤）
}
```

`claim()` 的实现（`:587-596`）把"谁执行动作"的竞态收敛成一个原子操作：

```java
final boolean claim() {
    Executor e = executor;
    if (compareAndSetForkJoinTaskTag((short)0, (short)1)) {  // ForkJoinTask tag 位当认领标记
        if (e == null) return true;          // 非 async：认领成功，当场执行
        executor = null;                     // async：置空防重复提交
        e.execute(this);                     // 提交自己，稍后以 ASYNC 模式跑
    }
    return false;
}
```

#### ⑤ 完成传播：postComplete 蹦床

`complete(v)` → CAS result 成功 → `postComplete()`（`:513-534`）弹出并触发栈上所有动作。难点是**触发一个动作会导致下游完成，下游又有自己的栈……** 如果递归做，深链会把调用栈打爆。Doug Lea 的解法是手动循环替代递归：

```java
final void postComplete() {
    CompletableFuture<?> f = this; Completion h;
    while ((h = f.stack) != null || (f != this && (h = (f = this).stack) != null)) {
        if (STACK.compareAndSet(f, h, t = h.next)) {   // CAS 弹栈顶
            ...
            f = (d = h.tryFire(NESTED)) == null ? this : d;  // ★ 蹦床：
        }                                                  // NESTED 模式下 tryFire 不再递归 postComplete，
    }                                                      // 而是把下游 d 交回本循环继续处理
}
```

配合 `postFire`（`:623-638`）：`mode < 0`（NESTED）时只 `return this` 把传播任务交还给上层循环；`mode >= 0`（SYNC/ASYNC）时才自己 `postComplete()`。**深层依赖链的传播深度是 O(1) 调用栈 + O(n) 循环**。这与 Netty `DefaultPromise` 的 `MAX_LISTENER_STACK_DEPTH` 栈深计数（见 2.2.3）是同一问题的两种解法。

#### ⑥ 双源与多源组合：CoCompletion / BiRelay 树 / AnyOf

**AND 双源（thenCombine = BiApply）** 的挂载（`bipush`，`:1257-1270`）：

- 动作 c 压入第一个源 a 的栈；若第二个源 b 未完成，再往 b 的栈上压一个 **`CoCompletion(c)`**——一个指向同一个 c 的占位代理（`:1235-1251`）；
- 于是 **a、b 谁后完成谁的 postComplete 触发 c**；`c.tryFire` 开头会检查两个源的 result，**只有两个都完成才执行 fn**，先到的那次空转返回 null；
- 配合 claim 的 tag 位，即使两源同时完成、两线程同时触发，fn 也只执行一次。

**allOf** = `andTree(cfs, 0, n-1)`（`:2492`）：递归二分，叶子两两用 `BiRelay` 相连，形成一棵"与"树——全部源完成时根的 `CF<Void>` 才完成（结果固定为 NIL，**业务值需要自己再 join 收集**）；任一源异常则根异常完成。

**anyOf**（`:2511-2534`）：任一源完成即完成，结果**转发**（`completeRelay`）最先完成者的结果。JDK 27 用共享 `cfs` 数组的 `AnyOf` 节点实现：第一个完成的源触发 `d.completeRelay(r)` 后**对其余源做 cleanStack 清理死节点**（`:1752-1755`）；旧版本（JDK 8～21）实现为 `OrRelay` 二叉树。**OR 双源（applyToEither）** 则是 `orpush + OrApply`：两源谁先完成谁触发，另一个的完成事件被忽略。

#### ⑦ 阻塞读的实现：Signaller 与 managedBlock

`get()` / `join()` 不能傻等——如果调用线程恰好是 ForkJoinPool 工作线程，傻等会吃掉池的并行度。解法（`:1851-1982`）：

- `Signaller extends Completion implements ForkJoinPool.ManagedBlocker`：记录等待线程引用 + deadline；
- `waitingGet`：Signaller 压入 `stack`（完成时 postComplete 会弹出它并 `LockSupport.unpark` 唤醒），然后 `ForkJoinPool.managedBlock(q)` 阻塞；
- **`ManagedBlocker` 契约**：FJP 发现工作线程要阻塞时，会**补偿性地临时增加一个工作线程**，池不会饿死；若当前线程是 FJP 工作线程还会先 `helpAsyncBlocker` 帮忙偷活干（`:1907-1908`）；
- `timedGet`（get(timeout)）同构，带 deadline，超时抛 `TimeoutException`。

这是"CF 与 FJP 深度共生"的体现——Netty 的 promise 没有也不会有这种待遇，它用的是完全不同的等待模型（见 2.2.5）。

#### ⑧ 异常传播：异常即数据

由 ④ 的走读已可见核心规则：

- 上游异常完成 → 下游所有 `thenApply/thenAccept/...` **跳过业务函数**，把异常原样（`encodeThrowable(x, r)` 尽量复用同一包装）转发给自己的下游——异常像值一样沿管道流动；
- 业务函数自己抛异常 → 该算子的下游以 `CompletionException(原异常)` 异常完成（`encodeThrowable` 统一包装）；
- 直到遇到三个"异常拦截算子"之一：`exceptionally`（只在失败时回调，返回恢复值）、`handle`（成败都回调，可返回新值，可改变类型）、`whenComplete`（成败都回调但**正常返回时结果不变**；⚠️ 回调自己抛异常会用该异常替换原结果）。

**读取侧的解包规则**（`reportGet` / `reportJoin`，`:427-460`）：

| 方法 | 正常结果 | 取消 | 其他异常 |
|---|---|---|---|
| `get()` / `get(timeout)` | 返回值 | 抛 `CancellationException` | 抛 `ExecutionException`（**拆开** CompletionException，cause 为原始异常） |
| `join()` / `getNow()` | 返回值 | 抛 `CancellationException` | 直接抛 `CompletionException`（非受检，函数式风格友好） |
| `resultNow()` (JDK 19+) | 返回值 | 抛 `IllegalStateException` | 抛 `IllegalStateException`（需先 `state()` 判断） |

#### ⑨ 执行器与线程语义（最容易被误解的部分）

**默认执行器**：`ASYNC_POOL = ForkJoinPool.asyncCommonPool()`（`:477`）。JDK 27 的 `asyncCommonPool` 会在 commonPool 并行度为 0（单核机器）时**强制把并行度提升到 2**（`ForkJoinPool.java:3120-3125`）——而 JDK 8～较老版本的策略是退化为 `ThreadPerTaskExecutor`（每任务一线程）。两者目的相同：保证异步任务永远有线程跑，绝不 caller-runs。commonPool 默认并行度 = CPU 核数 − 1，全 JVM 共享。

**子类定制点**（JDK 9+）：`defaultExecutor()`（async 方法无参变体的池）与 `newIncompleteFuture()`（所有算子产生下游时用的"虚构造器"）。覆盖这两个方法即可让整条链用自定义执行器和自定义子类——虚拟线程时代推荐的 `CompletableFuture` 子类化方案就建立在这上面。

**回调线程的三种可能**（官方 Javadoc `:67-70` 明文）：非 async 方法的动作可能由——
1. **完成该 CF 的线程**执行（postComplete → tryFire(SYNC) 内联）；
2. **调用 thenXxx 的线程**执行（注册时上游已完成的快路径）；
3. async 变体则是**指定 executor 的线程**。

所以**非 async 回调里永远不要假设线程**——ThreadLocal、锁顺序、线程亲和逻辑都不可靠。

**超时机制**（JDK 9+，JDK 27 实现 `:2792-2857`）：`orTimeout` / `completeOnTimeout` 把一个 `Timeout` Runnable（到期时 `completeExceptionally(TimeoutException)` 或 `complete(value)`）作为 `ScheduledForkJoinTask` 挂到 FJP 内部的 **DelayScheduler 调度线程**上，同时 `whenComplete(new Canceller(t))` 注册反向取消——future 先完成了就取消定时任务，防止任务堆积泄漏。（JDK 9～21 的旧实现是类内部一个单线程守护 `ScheduledThreadPoolExecutor`，线程名 `CompletableFuture-DelayedPool-N`；新版改用了 FJP 自带的延迟调度器。）`delayedExecutor(delay, unit[, executor])` 同理，返回"延迟投递"的 Executor 装饰器。

#### ⑩ 对象与开销模型

每次 `thenXxx` 调用分配 **2 个对象**：一个新 `CompletableFuture`（下游）+ 一个 Completion 动作；`supplyAsync` 分配 2 个（下游 + AsyncSupply）。应用层完全可忽略，但在每秒百万次操作的框架热路径上（如网络转发），这就是 Netty 不采用该模型的理由之一（Netty 一个操作 1 个 promise，监听器还是数组复用的，见 2.2.3）。

### 1.3 接口全景（分类）

`CompletionStage` 的组合方法有一个**命名规律**：`[前缀][动词][Async]`——前缀标识依赖源数量（无前缀=单源、双源 AND 无特殊前缀、OR 语义用 either/any），动词标识对结果做什么（Apply=转换出值 / Accept=消费 / Run=执行动作），几乎每个方法都有 **×3 变体**：`m(fn)`（不确定线程）、`mAsync(fn)`（默认池）、`mAsync(fn, executor)`（指定池）。

**A. 创建与工厂（静态方法）**

| 方法 | 语义 | 备注 |
|---|---|---|
| `completedFuture(v)` | 已完成的 CF | 零成本包装 |
| `failedFuture(ex)` | 已异常完成的 CF | JDK 9 |
| `completedStage(v)` / `failedStage(ex)` | 已完成的最小 Stage | JDK 9；只暴露 CompletionStage 方法 |
| `supplyAsync(supplier[, ex])` | 提交有返回值的异步任务 | 体内抛异常 → 异常完成 |
| `runAsync(runnable[, ex])` | 提交无返回值的异步任务 | 结果固定为 null |
| `delayedExecutor(delay, unit[, ex])` | 延迟投递的 Executor 装饰器 | JDK 9；配 supplyAsync 用 |

**B. 单源组合（消费上游 T）**

| 类别 | 方法 | 函数形状 | 语义 |
|---|---|---|---|
| 转换 | `thenApply` ×3 | `T → U` | map：结果类型改变 |
| 消费 | `thenAccept` ×3 | `T → void` | 只读结果，产出 `CF<Void>` |
| 执行 | `thenRun` ×3 | `() → void` | 连结果都不要 |
| **展平** | `thenCompose` ×3 | `T → CF<U>` | **flatMap**：fn 返回 CF，其完成结果作为下游结果——解决 `CF<CF<U>>` 嵌套（内部 `UniCompose`：先等上游，再等 fn 返回的 CF，两跳才完成） |

**C. 双源 AND 组合（两个都完成才触发，任一异常则异常）**

| 方法 | 函数形状 | 说明 |
|---|---|---|
| `thenCombine(other, fn)` ×3 | `(T, U) → V` | 拿两个结果合成新值 |
| `thenAcceptBoth` ×3 | `(T, U) → void` | 消费两个结果 |
| `runAfterBoth` ×3 | `() → void` | 只等两个都完成 |

**D. 双源 OR 组合（任一完成即触发，取先完成者）**

| 方法 | 函数形状 | 说明 |
|---|---|---|
| `applyToEither(other, fn)` ×3 | `T → V`（两源同类型） | 竞速，先到先转换 |
| `acceptEither` ×3 | `T → void` | 竞速消费 |
| `runAfterEither` ×3 | `() → void` | 任一完成即执行 |

**E. 多源聚合（静态）**

| 方法 | 返回 | 语义 |
|---|---|---|
| `allOf(cf...)` | `CF<Void>` | 全部完成才完成；任一异常则异常（**不含业务值**，需再 join 收集）；实现为 BiRelay 与树 |
| `anyOf(cf...)` | `CF<Object>` | 任一完成即完成，结果转发先完成者（异常也是"先到先得"）；实现为 AnyOf/OrRelay |

**F. 异常通道（异步世界的 try/catch）**

| 方法 | 触发时机 | 能否改变结果 | 典型用途 |
|---|---|---|---|
| `exceptionally(fn)` | 仅上游异常 | ✅ 返回恢复值 | 降级/兜底 |
| `exceptionallyAsync(fn[, ex])`（JDK 12） | 仅异常，fn 跑在池上 | ✅ | 恢复逻辑较重时 |
| `exceptionallyCompose(fn)`（JDK 12） | 仅异常，fn 返回 CF | ✅（flatMap 式恢复） | 恢复本身也是异步调用 |
| `handle(fn)` ×3 | **成败都触发** `(v, t) → U` | ✅ 可返回新值（含改类型） | 统一收口/记账 |
| `whenComplete(fn)` ×3 | 成败都触发 `(v, t) → void` | ❌ 正常返回时结果不变；**fn 抛异常则替换结果** | 观测/日志/埋点 |

**G. 主动完成与覆写**

| 方法 | 语义 |
|---|---|
| `complete(v)` / `completeExceptionally(t)` | 手动完成（CAS，只有一个调用者成功）；桥接回调式 API 的标准入口 |
| `completeAsync(supplier[, ex])` | 用池/指定 executor 跑 supplier 并以其结果完成自己（JDK 9） |
| `cancel(mayInterrupt)` | **= completeExceptionally(CancellationException)**，不中断任何底层任务 |
| `obtrudeValue(v)` / `obtrudeException(t)` | **暴力覆写** result（无视 CAS、可改已完成态），官方注明仅限错误恢复/调试 |

**H. 超时**

| 方法 | 语义 |
|---|---|
| `orTimeout(t, unit)` | 超时未完成 → 异常完成 `TimeoutException`（JDK 9） |
| `completeOnTimeout(v, t, unit)` | 超时未完成 → 正常完成缺省值 v（JDK 9） |

**I. 读取与状态**

| 方法 | 阻塞 | 异常形态 |
|---|---|---|
| `get()` / `get(t, u)` | ✅ | 受检：`ExecutionException` / `InterruptedException` / `TimeoutException` |
| `join()` | ✅ | 非受检 `CompletionException`（流式代码友好） |
| `getNow(v)` | ❌ | 未完成返回 v；已完成异常则抛 `CompletionException` |
| `resultNow()` / `exceptionNow()` / `state()`（JDK 19+） | ❌ | 配合 `state()` 枚举的安全读取 |
| `isDone()` / `isCancelled()` / `isCompletedExceptionally()` | ❌ | 状态查询 |
| `getNumberOfDependents()` | ❌ | stack 长度估算（监控用） |

**J. 防御与互操作**

| 方法 | 语义 |
|---|---|
| `copy()` | 防御性副本：结果同步自本 CF，但**客户端无法通过它完成你**（JDK 9） |
| `minimalCompletionStage()` | 只暴露 CompletionStage 方法的最小视图，write 侧全抛 `UnsupportedOperationException`（实现类 `MinimalStage`，`:2967`） |
| `toCompletableFuture()` | Stage → CF（接口方法） |

### 1.4 使用场景与示例

**场景 1：并行聚合多个独立调用（最常见）。** 首页聚合用户/订单/推荐，串行 300ms → 并行 100ms：

```java
CompletableFuture<Page> page =
    CompletableFuture.supplyAsync(() -> userService.get(uid), bizPool)
        .thenCombine(CompletableFuture.supplyAsync(() -> orderService.list(uid), bizPool),
                     (user, orders) -> Page.of(user, orders))
        .thenCombine(CompletableFuture.supplyAsync(() -> recoService.recommend(uid), bizPool),
                     Page::withRecos);
```

要点：三个独立任务各自 `supplyAsync` 并行跑，`thenCombine` 两次 stitching；**业务 IO 任务必须显式传池**（见 1.5-①）。

**场景 2：依赖流水线（thenCompose 解嵌套）。** 下单三步，每步依赖上一步结果且各自异步：

```java
CompletableFuture<Receipt> receipt =
    CompletableFuture.supplyAsync(() -> inventory.lock(orderId), bizPool)
        .thenCompose(order    -> CompletableFuture.supplyAsync(() -> pay.charge(order),    bizPool))
        .thenCompose(payment  -> CompletableFuture.supplyAsync(() -> ship.deliver(payment), bizPool));
```

若误用 `thenApply` 会得到 `CF<CF<CF<Receipt>>>`——`thenCompose` 就是异步世界的 flatMap。

**场景 3：超时 + 降级（远程调用防护）。**

```java
CompletableFuture<Stock> stock =
    CompletableFuture.supplyAsync(() -> remoteClient.query(sku), bizPool)
        .orTimeout(200, TimeUnit.MILLISECONDS)           // 超时 → TimeoutException
        .exceptionally(e -> Stock.fromCache(sku));       // 任意失败 → 本地缓存兜底
// 或不改异常通道、直接给缺省值：
//   .completeOnTimeout(Stock.unknown(sku), 200, TimeUnit.MILLISECONDS);
```

**场景 4：桥接回调式第三方 API（手动完成）。** 把"回调风格"SDK 包成 CF，之后即可用全部组合算子：

```java
public CompletableFuture<Body> fetch(Request req) {
    CompletableFuture<Body> f = new CompletableFuture<>();       // 生 CF，不启动任何任务
    legacyClient.request(req, new Callback() {                   // SDK 自己跑
        @Override public void onSuccess(Body b) { f.complete(b); }
        @Override public void onError(Throwable t) { f.completeExceptionally(t); }
    });
    return f;   // 竞态安全：SDK 可能先于 return 完成，complete/注册都有 CAS 兜底
}
```

**场景 5：批量扇出 + 收集（allOf + join）。** 对每个 sku 并行询价，全部完成后汇总：

```java
List<CompletableFuture<Price>> futures = skus.stream()
        .map(sku -> CompletableFuture.supplyAsync(() -> priceClient.get(sku), bizPool))
        .toList();

CompletableFuture<List<Price>> all = CompletableFuture
        .allOf(futures.toArray(CompletableFuture[]::new))     // CF<Void>，只表"全部完成"
        .thenApply(v -> futures.stream().map(CompletableFuture::join).toList());
// 注意：任一询价失败 allOf 即异常完成；逐个 join 前 exceptionally 各自兜底可做部分失败容错
```

**场景 6：多源竞速容灾（applyToEither / anyOf）。** 两个机房任一可用即返回：

```java
CompletableFuture<Resp> resp =
    CompletableFuture.supplyAsync(() -> dc1Client.query(q), bizPool)
        .applyToEither(CompletableFuture.supplyAsync(() -> dc2Client.query(q), bizPool),
                        Function.identity());
```

**场景 7：竞态写入（手动完成的第二用途）。** 本地缓存与远端谁先返回用谁：

```java
CompletableFuture<Data> f = new CompletableFuture<>();
cacheAsync.get(key).whenComplete((d, e) -> { if (d != null) f.complete(d); });
remoteAsync.fetch(key).whenComplete((d, e) -> { if (e == null) f.complete(d);
                                                else if (...) f.completeExceptionally(e); });
// complete 的 CAS 保证只有第一次生效，天然防双写
```

### 1.5 陷阱清单

1. **默认池污染**：`commonPool` 全 JVM 共享、并行度 = 核数−1、为 CPU 密集设计。在上面跑阻塞 I/O 会拖垮并行流等所有共享者。**任何 IO 型任务必须显式传业务池或虚拟线程执行器**。
2. **异常静默丢失**：异常完成的 CF 若无人 `get/join`、链上无 `exceptionally/handle/whenComplete`，异常彻底无声（不打日志、不打印栈）。长链编排建议末端统一 `whenComplete` 记账。
3. **`cancel()` 不中断任务**：只是以 `CancellationException` 完成本 CF，底层计算照跑；需要真中断要自己持有任务/线程句柄。
4. **非 async 回调线程不确定**（1.2.⑨ 的三种可能）：回调里不要依赖 ThreadLocal、不要假设持有某把锁。
5. **`whenComplete` ≠ `handle`**：前者不改结果（除非自己抛异常），后者可改；混用是常见 bug 源。
6. **`thenCompose` vs `thenApply`**：fn 返回 CF 时用错会产生嵌套类型，编译期能发现，但在泛型擦除的胶水层里容易漏。
7. **`obtrudeValue` 危险**：可覆写已完成的结果，破坏"完成即不可变"的推理基础，仅用于故障恢复/测试。
8. **回调风暴与栈深**：长同步链在单线程上内联传播（SYNC 模式）虽被蹦床控住，但把重量逻辑挂在非 async 回调上仍会拖慢完成者线程——重逻辑一律 `*Async(fn, pool)`。

---

## 二、Netty 中的 Future/Promise

### 2.1 设计定位与接口体系

Netty 的异步句柄比 CompletableFuture **早了约六年**（3.x，2008 年前后，与 Guava ListenableFuture 同期）。不复用 JDK 1.5 `Future` 的理由写死在 `io.netty.util.concurrent.Future` 的接口设计里——JDK Future 只有阻塞 `get()`，而网络框架要的是：**非阻塞监听器、直接裸取的 `cause()`、可控等待（`await/sync`）、与 EventLoop 线程模型的深度绑定**。

**核心设计选择：读/写接口分离。**

```
java.util.concurrent.Future<V>                        ← JDK 1.5，只能 get/cancel
   └── io.netty.util.concurrent.Future<V>             ← common 模块：读侧扩展
        │     isSuccess() / cause() / isCancellable()
        │     addListener(s) / removeListener(s)      ← 非阻塞回调
        │     await() 家族 / sync() 家族              ← 受控阻塞
        │     getNow()
        ├── Promise<V>                                ← 写侧：setSuccess/setFailure 的 try/set 两档
        │     + setUncancellable()
        │        ├── ProgressivePromise<V>            ← + setProgress/tryProgress（进度上报）
        │        └── (transport) ChannelPromise       ← + channel()、无参 setSuccess/trySuccess、unvoid()
        │              ├── DefaultChannelPromise（默认实现）
        │              ├── VoidChannelPromise（voidPromise()，热路径特化）
        │              └── PendingRegistrationPromise（Bootstrap 注册期特化）
        ├── ProgressiveFuture<V>                      ← 读侧的进度视图
        └── (transport) ChannelFuture / ChannelProgressiveFuture

实现与配套（common/.../concurrent/）：
  AbstractFuture<V>        只用 await+cause+getNow 实现 get()/get(timeout) 的模板基类
  DefaultPromise<V>        ★ 全家桶的默认实现（本节主角）
  DefaultProgressivePromise / DefaultChannelPromise   进度 / Channel 特化
  PromiseTask<V>           RunnableFuture：executor.submit(...) 的任务体（Netty 版 supplyAsync）
  SucceededFuture / FailedFuture   预完成对象（newSucceededFuture()，零状态）
  PromiseCombiner          多 Future 聚合成一个（Netty 版 allOf）
  GenericFutureListener<F> 回调接口：operationComplete(F future)
  GenericProgressiveFutureListener  + operationProgressed(progress, current, total)
```

**读写分离即权限边界**：发起异步操作的一方（框架内部，如 `unsafe.write`）拿到 `Promise` 负责在操作落定时 `setSuccess/setFailure`；调用方拿到的返回类型是 `Future`/`ChannelFuture`，只能读结果和挂监听器——**消费者不可能替生产者写结果**。CompletableFuture 没有这道墙（人人可 `complete`），所以它要靠 `copy()/minimalCompletionStage()` 事后补救权限控制。

### 2.2 实现原理（DefaultPromise 源码级走读）

`common/src/main/java/io/netty/util/concurrent/DefaultPromise.java`（共 902 行），全部机制围绕**一个 volatile 字段、一把 `synchronized(this)`、一次 CAS** 展开：

```java
public class DefaultPromise<V> extends AbstractFuture<V> implements Promise<V> {
    private volatile Object result;                  // 唯一状态字段
    private final EventExecutor executor;            // 构造时绑定：决定通知线程 + 死锁检测
    private GenericFutureListener<? extends Future<?>> listener;  // 第 1 个监听器（单字段，零数组开销）
    private DefaultFutureListeners listeners;        // 第 2 个起升级为数组容器
    private short waiters;                           // await 的等待者计数（上限 Short.MAX_VALUE）
    private boolean notifyingListeners;              // 通知重入防护
}
```

#### ① 状态编码

`result` 的取值域（对照 `DefaultPromise.java:57-63, 638-655, 862-868`）：

| result 的值 | 含义 | 说明 |
|---|---|---|
| `null` | 未完成 | `isCancellable()` 也仅在此为 true |
| 业务值本身 | 成功 | 值直接存，零包装（与 CF 相同思路） |
| `SUCCESS`（全局哨兵对象） | 成功且值为 null | Channel 写操作都是 `Void`，全部走这个哨兵，**零分配** |
| `UNCANCELLABLE`（哨兵） | 不可取消的"进行中"态 | **既不算完成（`isDone0` 排除它）也不可取消**，是介于 null 与终态之间的第三态 |
| `CauseHolder(cause)` | 失败 | 异常包一层；取消也是失败的一种特例 |
| `CANCELLATION_CAUSE_HOLDER`（全局共享哨兵） | 已取消 | 共享实例 + 懒替换（见 ④） |

与 CF 的显著差异：**CF 只有"未完成/已完成"两态**；Netty 多出一个显式的 `UNCANCELLABLE` 中间态——传输层写路径大量使用：消息已写出去（`AbstractChannel.register0` 第一行就是 `promise.setUncancellable()`，`AbstractChannel.java:369`），此后外部 `cancel()` 无效，但 promise 仍未完成。

#### ② 一次性完成：CAS + 允许的迁移路径

```java
private boolean setValue0(Object objResult) {                       // :646
    if (RESULT_UPDATER.compareAndSet(this, null, objResult) ||       // null → 终态
        RESULT_UPDATER.compareAndSet(this, UNCANCELLABLE, objResult)) { // UNCANCELLABLE → 终态（允许！）
        if (checkNotifyWaiters()) {   // synchronized: 唤醒 waiters + 返回是否有监听器
            notifyListeners();
        }
        return true;
    }
    return false;
}
```

完成迁移只有两条：`null → 终态` 和 `UNCANCELLABLE → 终态`；**终态之间互不可达**。`setXxx` 与 `tryXxx` 共用此路径，区别仅在失败时（`:110-133`）：

- `setSuccess/setFailure`：已完成 → 抛 `IllegalStateException("complete already: " + this)`——用于断言"我是唯一写方"；
- `trySuccess/tryFailure`：返回 false——用于竞争场景（多个线程都可能完成同一 promise）。

#### ③ 监听器：存储、通知线程与防护

**存储升级**（`:612-624`）：第 1 个监听器直接放 `listener` 字段（网络场景绝大多数操作 0~1 个监听器，省掉数组分配）；第 2 个到来才升级为 `DefaultFutureListeners`（数组 + size + progressiveSize 三个 int 字段，`add` 满时翻倍扩容，**FIFO 保序**——对照 CF 的 Treiber 栈 LIFO、无序）。

**注册路径**（`:191-203`）：`synchronized(this)` 写入 → 出锁后查 `isDone()`，已完成则 `notifyListeners()` 立即补通知。已完成 + 注册线程是绑定的 executor 线程 → 当前线程内联执行；否则投递（见下）。

**通知线程规则**（`notifyListeners`，`:498-520`）——Netty 模型的灵魂：

```java
private void notifyListeners() {
    EventExecutor executor = executor();
    if (executor.inEventLoop()) {                       // 当前线程就是绑定的 executor 线程
        int stackDepth = threadLocals.futureListenerStackDepth();
        if (stackDepth < MAX_LISTENER_STACK_DEPTH) {    // 默认 8，系统属性 io.netty.defaultPromise.maxListenerStackDepth
            threadLocals.setFutureListenerStackDepth(stackDepth + 1);
            try { notifyListenersNow(); }               //   内联执行，栈深 +1
            finally { threadLocals.setFutureListenerStackDepth(stackDepth); }
            return;
        }
    }
    safeExecute(executor, () -> notifyListenersNow());  // 否则（外部线程 或 栈深超限）→ 投递任务
}
```

三层防护：

1. **线程确定性**：监听器永远在 `executor`（传输层即 Channel 的 EventLoop）线程上执行——与该 Channel 的 pipeline 事件同线程，监听器里操作 Channel 状态无需加锁；
2. **栈深防护**：`InternalThreadLocalMap.futureListenerStackDepth` 计数（借 FastThreadLocal 体系），"监听器里又完成下一个 promise"的链式级联超过 8 层就改为投递任务——与 CF 的 NESTED 蹦床解决同一问题；
3. **重入防护**：`notifyListenersNow`（`:552-591`）在 `synchronized` 块里先把 `listener(s)` 字段**置 null 摘走**并置 `notifyingListeners=true`，锁外执行回调；执行期间新 add 的监听器会被 for(;;) 循环捞起继续通知，不会丢也不会插队。

**监听器异常的处理**（`notifyListener0`，`:602-610`）：catch 一切 Throwable，**只 warn 日志，绝不传播**——一个监听器出错不能影响其他监听器和 EventLoop。副作用：监听器里的业务异常如果没自己记日志，就只剩这行 warn。

**executor 拒绝兜底**（`safeExecute`，`:877-883`）：EventLoop 已关闭时投递失败，专门用 `rejectedExecution` logger 记 error，不抛出。

#### ④ 取消的零开销设计

取消是高频操作（超时关连接、写失败级联），Netty 把它的成本压到极致（`:59-61, 155-169, 176-188`）：

- 静态共享的 `CANCELLATION_CAUSE_HOLDER`：取消时 CAS 写入这个**全局单例**，不 new 异常、不抓栈（`cancel()`，`:397-405`：CAS `null → CANCELLATION_CAUSE_HOLDER`，成功则唤醒 + 通知；`mayInterruptIfRunning` 参数与 JDK 一样被无视）；
- `StacklessCancellationException`：`fillInStackTrace()` 返回预生成的静态栈（归属 `DefaultPromise.cancel(...)` 一行）；
- **懒替换**：只有当有人真的调 `cause()` 时，才把共享 holder CAS 成一个新的 `LeanCancellationException`（同样 fillInStackTrace 不抓真栈）——绝大多数被取消的 promise 从没人在意 cause，全程零分配。

`isCancelled()` = result 是 CauseHolder 且 cause 是 CancellationException（`:862-864`）。`setUncancellable()`（`:136-142`）：CAS `null → UNCANCELLABLE`；此后 `cancel()` 的 CAS 从 null 出发必然失败 → 不可取消。

#### ⑤ await / sync：受控阻塞 + 死锁检测

`await()`（`:253-275`）：经典 wait/notifyAll——`checkDeadLock()` → `synchronized(this)` 内 `while(!isDone()) { incWaiters(); wait(); }`。三个细节：

- **`checkDeadLock()`（`:474-479`）**：若当前线程就是绑定的 EventLoop 线程 → 直接抛 `BlockingOperationException`。**在 EventLoop 里 await 自己 Channel 的 promise = 自杀式死锁**（完成该 promise 的正是这个线程），Netty 选择快速失败而非永久挂起；
- `waiters` 是 short，超过 `Short.MAX_VALUE` 抛 `IllegalStateException("too many waiters")`（`:668-673`）；
- `awaitUninterruptibly` 吞中断但在结束时**补回中断标志位**（`:300-302`），语义精确。

`sync()`（`:418-422`）= `await()` + `rethrowIfFailed()`。后者（`:679-689`）有两个讲究：失败时把 cause **原样重抛**（不包 `ExecutionException`——Netty 世界没有包装文化）；并给 cause 补挂一个 suppressed `CompletionException("Rethrowing promise failure cause")` 提示"这是 sync 重抛的"；受检异常靠 `PlatformDependent.throwException` 无声明抛出。

三兄弟语义对比（易混，必须分清）：

| 方法 | 阻塞 | 失败时 | 中断 |
|---|---|---|---|
| `await()` | 等完成 | **不抛业务异常**，完成后自己查 `isSuccess()/cause()` | 抛 `InterruptedException` |
| `sync()` | 等完成 | **重抛 cause 原异常** | 抛 `InterruptedException` |
| `get()`（JDK 兼容） | 等完成 | 包装成 `ExecutionException`（CancellationException 直接抛） | 抛 `InterruptedException` |

#### ⑥ get() 的实现与 AbstractFuture 模板

`DefaultPromise.get()`（`:349-366`）不走 `AbstractFuture`，而是直接读 `result` 字段 + `await()`，把三种终态分别处理（SUCCESS/UNCANCELLABLE→null 等）。`AbstractFuture<V>`（59 行）则是给**不支持取消**的实现用的模板：只用 `await + cause + getNow` 三个原语拼出 `get()/get(timeout)`——`VoidChannelPromise` 继承的就是它。

#### ⑦ 传输层特化（transport 模块）

**`DefaultChannelPromise`**（`DefaultChannelPromise.java`）：

- `executor()` 惰性回退：构造时可不传 executor，通知线程动态取 `channel().eventLoop()`（`:57-64`）——Channel 注册到 EventLoop 之前 eventLoop 字段都是空的；
- `checkDeadLock()` 只在 `channel().isRegistered()` 时才检测（`:157-161`）——未注册的 promise 完成方是外部线程，等待不构成死锁；
- 额外实现 `FlushCheckpoint`（`:142-149`）——`ChannelOutboundBuffer` 用 promise 的 checkpoint 字段给 flush 排序，写缓冲与 promise 是打通的。

**`PendingRegistrationPromise`**（`AbstractBootstrap.java:511-530`）：注册期专用。注册成功前 `executor()` 未定，若注册失败需通知监听器，只能回退到全局单线程的 `GlobalEventExecutor.INSTANCE` 兜底（issue #2586）——保证"注册失败"这种边缘路径的监听器也一定有线程执行。

**`VoidChannelPromise`**（`VoidChannelPromise.java`）——热路径的极致特化：

- `channel.voidPromise()` 返回它；`isDone()` 永远 false、`setSuccess` 是 no-op；
- `addListener/await(timeout)/sync` 全部抛 `IllegalStateException("void future")`——用异常做 API 约束；
- `setFailure/tryFailure` 不记录任何状态，直接 `pipeline.fireExceptionCaught(cause)`（仅当 channel 已注册，`:230-238`）——**失败走 pipeline 通道而非 promise 通道**；
- `unvoid()` 可随时换回真 promise（`:217-223`）。
- 价值：转发/广播等"不关心每次写结果"的场景，一次写从"分配 promise + 监听器数组 + 通知"降为**零分配**。

#### ⑧ 组合与任务执行

**`PromiseCombiner`**（`PromiseCombiner.java`，Netty 版 allOf）：

- `add(future)` 逐个挂共享监听器（计数 + 记第一个失败 cause），`finish(aggregatePromise)` 封口：全成功 → `aggregate.trySuccess(null)`，任一失败 → `tryFailure(第一个 cause)`；
- **不是线程安全的**，javadoc 明确要求所有调用在指定 EventExecutor 线程上进行（`checkInEventLoop`，`:163-167`）——又一处"线程确定性换简洁"的取舍；
- 对比 `allOf`：无 anyOf、无结果收集、无树形结构——就是一个计数器。

**`PromiseTask<V> implements RunnableFuture<V>`**：`executor.submit(callable/runnable)` 的任务体（Netty 版 `supplyAsync`），在绑定的 executor 上跑完 `trySuccess/tryFailure`。也就是说 **Netty Future 也能"自己产生结果"，只是默认姿势是"外部写入"**。

#### ⑨ 进度上报

`ProgressivePromise.setProgress/tryProgress(progress, total)`（4.2 接口）→ `DefaultPromise.notifyProgressiveListeners`（`:754-792`）：从已注册监听器中**只挑出 `GenericProgressiveFutureListener`** 逐个调 `operationProgressed(progress, total)`（total 为 -1 表示未知总量）；通知线程规则与完成通知完全一致（inEventLoop 内联 / 外部投递）。大文件传输 `writeAndFlush(FileRegion)` 返回 `ChannelProgressiveFuture` 即基于此。

### 2.3 接口全景（分类）

**读侧（`io.netty.util.concurrent.Future`）**：

| 类别 | 方法 | 说明 |
|---|---|---|
| 状态 | `isSuccess()` / `cause()` / `isDone()` / `isCancelled()` / `isCancellable()` | cause **裸取不包装**；未完成/成功时为 null |
| 非阻塞回调 | `addListener(l)` / `addListeners(l...)` / `removeListener(s)` | 已完成则立即触发（线程规则见 2.2.③）；**可移除**（CF 无此能力） |
| 受控等待 | `await()` / `await(t,u)` / `awaitUninterruptibly()` / `awaitUninterruptibly(t,u)` | 只等完成不抛业务异常；带死锁检测 |
| 抛错等待 | `sync()` / `syncUninterruptibly()` | await + 重抛 cause 原异常 |
| 快速读 | `getNow()` | 未完成返回 null（**必须配 isDone 判断**，成功值也可能是 null） |
| JDK 兼容 | `get()` / `get(t,u)` / `cancel(b)` | cancel = 以 CancellationException 失败，不中断底层 |

**写侧（`Promise`）**：

| 方法 | 失败时 | 用途 |
|---|---|---|
| `setSuccess(v)` / `setFailure(t)` | 抛 `IllegalStateException` | 唯一写方、断言式完成 |
| `trySuccess(v)` / `tryFailure(t)` | 返回 false | 竞争写方（多个线程都可能完成） |
| `setUncancellable()` | — | 进入不可取消态（写路径防误取消） |
| `cancel(b)` | 返回 false | 与 JDK 语义对齐 |

**获取途径**：

| 途径 | 得到 |
|---|---|
| `eventLoop.newPromise()` / `channel.newPromise()` | 全新 DefaultPromise / DefaultChannelPromise |
| `channel.voidPromise()` | VoidChannelPromise（零开销热路径） |
| `channel.newSucceededFuture()` / `newFailedFuture(t)` | 预完成的单例式 Future（SucceededFuture/FailedFuture） |
| 所有传输操作 `bind/connect/write/flush/close/deregister` | ChannelFuture（由框架完成） |
| `executor.submit(...)` / `schedule(...)` | PromiseTask 包装的 Future |

**Channel 层特化**：`ChannelFuture`（+ `channel()`，成功恒为 Void）、`ChannelPromise`（+ 无参 `setSuccess()/trySuccess()`、`unvoid()`/`isVoid()`）、`ChannelProgressiveFuture/Promise`、`ChannelFutureListener`（含现成常量 `CLOSE_ON_FAILURE`、`FIRE_EXCEPTION_ON_FAILURE`）。

**与 CompletableFuture 的方法对照**：

| CompletableFuture | Netty 对应 | 差异 |
|---|---|---|
| `complete(v)` | `setSuccess(v)` / `trySuccess(v)` | Netty 区分断言式/竞争式两档 |
| `completeExceptionally(t)` | `setFailure(t)` / `tryFailure(t)` | 同上 |
| `thenApply/thenAccept/...` | **无**，只有 `addListener` | 无下游新阶段，同一对象挂回调 |
| `handle` / `exceptionally` | 无，监听器里 `isSuccess()/cause()` 自判 | 异常处理显式化 |
| `allOf` | `PromiseCombiner.add/finish` | 单线程使用、无结果收集 |
| `anyOf` / `applyToEither` | 无 | 需自己写监听器竞速 |
| `get()/join()` | `await()/sync()/get()` | Netty 三档等待 + 死锁检测 |
| `getNow(默认值)` | `getNow()`（无默认值参数） | 必须配 `isDone()` |
| `cancel()` | `cancel()` | 语义相同（不中断）；Netty 多 `setUncancellable/isCancellable` |
| 无 | `voidPromise()` / `ProgressivePromise` / `removeListener` | Netty 特有 |

### 2.4 使用场景与示例

**场景 1：启动与生命周期编排（官方示例的标准姿势）。** 启动/关闭低频且在主线程，同步等待可读性最好：

```java
ChannelFuture f = b.bind(PORT).sync();       // 等端口绑定完成；失败会重抛 cause（connect refused 等）
f.channel().closeFuture().sync();            // 挂住主线程直到服务端 Channel 关闭
group.shutdownGracefully();                  // 返回 Future，也可 .sync() 等待真正退出
```

框架内部（`AbstractBootstrap`）则全程监听器风格：注册失败用 `CLOSE_ON_FAILURE` 自动关连接。

**场景 2：写确认与失败处理（最日常）。**

```java
channel.writeAndFlush(msg).addListener((ChannelFutureListener) f -> {
    if (!f.isSuccess()) {
        log.warn("发送失败: {}", f.cause().toString());   // cause() 裸取，无包装
        f.channel().close();                              // 常见善后：写失败即断连
    }
});
// 或直接用现成监听器：
channel.writeAndFlush(msg).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
```

**场景 3：桥接回调式 SDK（业务侧最实用的模式）。** 在 handler 里把异步存储/下游 RPC 包成 promise，通知线程自动回到本 Channel 的 EventLoop：

```java
Promise<User> promise = ctx.executor().newPromise();
asyncUserStore.find(uid, new Callback<User>() {
    @Override public void onSuccess(User u) { promise.trySuccess(u); }  // 竞争写方 → try 系
    @Override public void onError(Throwable t) { promise.tryFailure(t); }
});
// 消费方：
promise.addListener(f -> {
    if (f.isSuccess()) ctx.writeAndFlush(render(f.getNow()));
    else               ctx.fireExceptionCaught(f.cause());
});
// 注意监听器在 EventLoop 线程执行：不能阻塞，重逻辑投给业务线程池
```

**场景 4：批量聚合（广播 / 批量写）。**

```java
EventExecutor executor = ctx.executor();
PromiseCombiner combiner = new PromiseCombiner(executor);
for (Channel ch : channels) {
    combiner.add(ch.writeAndFlush(broadcastMsg, ch.voidPromise()));  // 单个写不关心结果 → voidPromise
}
Promise<Void> allSent = executor.newPromise();
combiner.finish(allSent);                       // 全部写完 allSent 完成；任一失败带出第一个 cause
allSent.addListener(f -> log.info("广播完成: {}", f.isSuccess() ? "ok" : f.cause()));
// 注意：combiner 的 add/finish 必须在同一个 EventExecutor 线程调用（内部有 checkInEventLoop）
```

**场景 5：带超时与防护的等待（EventLoop 之外的线程）。**

```java
if (!promise.await(3, TimeUnit.SECONDS)) {       // 返回 false = 超时未完成
    throw new TimeoutException("操作 3s 未完成");
}
if (!promise.isSuccess()) {                       // await 成功 ≠ 操作成功！
    throw new RuntimeException(promise.cause());
}
// 若在 EventLoop 线程内误 await 绑定本 loop 的 promise：
//   → checkDeadLock() 直接抛 BlockingOperationException，而不是永久挂死
// 若在 EventLoop 线程 await 其他 channel 的 promise：不会死锁但会拖垮本 loop，同样禁止
```

**场景 6：大文件传输进度。**

```java
ChannelProgressivePromise pp = channel.newProgressivePromise();
channel.writeAndFlush(new DefaultFileRegion(fileChannel, 0, length), pp);
pp.addListener(new ChannelProgressiveFutureListener() {
    @Override public void operationProgressed(ChannelProgressiveFuture f, long progress, long total) {
        log.info("已发送 {}/{}", progress, total);      // total = -1 表示未知
    }
    @Override public void operationComplete(ChannelProgressiveFuture f) {
        if (!f.isSuccess()) log.warn("发送失败", f.cause());
    }
});
```

**场景 7：Netty 执行器上跑异步任务（PromiseTask）。**

```java
Future<String> f = ctx.executor().submit(() -> blockAndCompute());  // 在该 EventLoop 上排队执行
// 或调度：ctx.executor().schedule(() -> heartbeat(), 45, TimeUnit.SECONDS);
// 与 supplyAsync 的本质区别：不占用任何新池，任务与 Channel 事件同线程串行——
// 所以任务必须快；阻塞任务要交给独立的业务 EventExecutorGroup
```

### 2.5 陷阱清单

1. **监听器里做慢操作 = 阻塞整个 EventLoop**：该线程上几百个 Channel 的所有事件都被拖住（Netty 头号生产事故来源）。重逻辑必须投递业务线程池（`pipeline.addLast(businessGroup, handler)` 或 `executor.execute`）。
2. **未消费的失败 promise 静默**：不 `addListener` 也不 `await/sync`，失败无人知晓（`voidPromise` 例外——它会 fire `exceptionCaught`）。至少挂个日志监听器。
3. **`setSuccess` 二次调用抛异常**：竞争场景统一用 `tryXxx`；把 `set` 系当断言用。
4. **`await()` 返回只代表"完成"**：成功与否还要 `isSuccess()`；要抛错语义用 `sync()`；要 JDK 语义用 `get()`。
5. **`getNow()` 的 null 歧义**：未完成、成功 null、失败三种情况都可能返回 null，必须配 `isDone()/isSuccess()`。
6. **EventLoop 线程内等待**：await 自己 loop 的 promise 会被 `BlockingOperationException` 拦下；await 别人的 promise 不会报错但会卡死本 loop——两种都要靠纪律避免。
7. **跨线程通知假设**：监听器线程 = promise 绑定的 executor（Channel 场景即 EventLoop），不是"完成它的那个线程"。在监听器里访问其他 Channel/非本 loop 状态仍需注意。

---

## 三、对比分析

### 3.1 两种范式一图看懂

```
CompletableFuture：函数式管道 —— 每个算子产生【新阶段】，数据/异常沿链流动
  cf1 ──thenApply──▶ cf2 ──thenCompose──▶ cf3 ──thenCombine(cfB)──▶ cf4
       (UniApply)        (UniCompose)          (BiApply + CoCompletion)
  · cf1..cf4 互相独立、皆不可变；异常自动向下游传播
  · 触发沿链递推（postComplete 蹦床防栈溢出）
  · 回调线程不确定（完成者线程 / 注册线程 / executor）

Netty：单对象监听器 —— 结果由生产者显式写入，同一对象上【挂回调】
  producer ──setSuccess(v)/setFailure(t)──▶ promise ──▶ listener1（FIFO 数组）
                                                        └▶ listener2 ...
  · 无中间对象；异常是终态字段，监听器各自 isSuccess()/cause() 判断
  · 通知线程固定 = 构造时绑定的 EventExecutor（Channel 场景 = EventLoop）
  · 读写接口分离：Future 给消费者，Promise 给生产者
```

一句话：**CF 是"数据流"，Netty 是"事件通知"**。

### 3.2 全维度对比表

| 维度 | CompletableFuture | Netty Future/Promise |
|---|---|---|
| 诞生时间 | JDK 8（2014，Doug Lea） | Netty 3.x（约 2008，早 6 年） |
| 核心抽象 | `CompletionStage`：阶段化组合管道 | `Future`(读)/`Promise`(写) 接口分离 |
| 结果写入方 | 链上算子自动写；任何持引者也可 `complete` | 只有持有 `Promise` 的生产者显式写入 |
| 组合能力 | **丰富**：thenCompose/thenCombine/applyToEither/allOf/anyOf/handle 全家桶 | **极简**：仅 addListener；聚合靠 PromiseCombiner（无 anyOf、无结果收集） |
| 权限控制 | 事后补救（copy/minimalCompletionStage） | 天然分离（Future 接口无写方法） |
| 状态空间 | 未完成 / 完成（值或异常） | 未完成 / UNCANCELLABLE / 完成（值/失败/取消） |
| 一次性保证 | CAS result，终态不可变（obtrude 可破例） | CAS result，终态不可变（无破例手段） |
| 多回调 | Treiber 栈，**LIFO、顺序无保证**，不可移除 | 数组，**FIFO 保序**，可 removeListener |
| 回调线程 | **不确定**（完成者 / 注册者 / executor 三选一） | **确定**（绑定的 EventExecutor / EventLoop） |
| 默认执行器 | commonPool（JDK 27 起单核也强制并行度 2）；子类可覆盖 defaultExecutor | **无默认池**，一切显式（EventLoop / EventExecutorGroup） |
| 深链/级联防护 | postComplete 蹦床（NESTED 模式） | 栈深计数 ≥8 转投递（futureListenerStackDepth） |
| 阻塞等待 | get/join；**无死锁检测**；Signaller+ManagedBlocker 防 FJP 饿死 | await/sync/get 三档；**EventLoop 自等抛 BlockingOperationException**；wait/notifyAll |
| 等待者规模 | 每个 get 一个 Signaller 节点，无上限 | short 计数，上限 Short.MAX_VALUE |
| 异常获取 | 包装文化：CompletionException/ExecutionException（join/get 各一套） | 裸文化：`cause()` 直取；sync 原样重抛 |
| 异常传播 | 自动沿链下传，算子逐级跳过 | 无传播，每个监听器显式判断 |
| 回调异常 | 编码进下游 result（继续流动） | 吞掉 + warn 日志（不影响其他监听器） |
| 取消 | = 异常完成 CancellationException，不中断任务 | 同语义；额外 setUncancellable/isCancellable |
| 超时 | orTimeout/completeOnTimeout（内置，全局调度线程） | **无内置**（自己配 schedule 或 HashedWheelTimer） |
| 进度通知 | 无 | ProgressivePromise/tryProgress |
| 热路径特化 | 无 | VoidChannelPromise（零分配）、SucceededFuture |
| 每操作开销 | 2 对象/算子（新 CF + Completion） | 1 对象 + 至多 1 数组；void 路径 0 分配 |
| 生态 | 实现 CompletionStage，**Java 事实标准**，与响应式库互认 | 私有接口体系，出 Netty 边界需适配 |
| 定位 | 应用层：业务编排、并行计算 | 框架内核：连接生命周期上的传输事件确认 |

### 3.3 线程模型对比：不确定 vs 确定

**CF 的线程不确定性**是官方契约（`CompletableFuture.java:67-70` 原文：非 async 动作"may be performed by the thread that completes the current CompletableFuture, or by any other caller of a completion method"）：

```java
CompletableFuture.supplyAsync(() -> query())       // commonPool 线程 A
                 .thenApply(v -> transform(v));    // 在 A 执行？还是在注册线程执行（若 query 已完成）？
                                                  // → 都可能，无法静态推断
```

**Netty 的线程确定性**是模型根基：

```java
channel.writeAndFlush(msg).addListener(f -> handleAck());
// handleAck 必然在 channel 绑定的 EventLoop 线程执行，
// 与 pipeline 事件、定时器、其他写回调全部同线程 → 监听器内读写 Channel 状态无需锁
```

差异的根源是服务对象不同：CF 面向"通用异步计算"，回调跑在哪不重要，重要的是**组合表达力与吞吐**（FJP work-stealing）；Netty 面向"网络框架内核"，一个 Channel 的所有事件**必须**串行在同一线程，否则 pipeline 的无锁模型、ByteBuf 的线程假设全部破产。**Netty 用组合能力换线程确定性，CF 用线程确定性换组合能力**——两者的取舍互为镜像。

### 3.4 异常模型对比：数据流 vs 终态字段

```java
// CF：异常是"数据"，沿管道自动流动，直到被拦截算子处理
supplyAsync(() -> risky())
    .thenApply(this::step2)        // risky 失败 → step2 被跳过
    .thenAccept(this::log)         //   继续跳过
    .exceptionally(t -> fallback()); // 任意位置收口恢复
// 读取包装：get() → ExecutionException(cause)；join() → CompletionException（非受检）

// Netty：异常是 promise 的一个终态字段，每个观察者显式判断
promise.addListener(f -> {
    if (f.isSuccess()) handle(f.getNow());
    else {
        Throwable cause = f.cause();   // 裸取，无包装
        // 这里没有"下一个阶段"——异常传播到此为止，必须当场处理
    }
});
```

推论：**多步转换选 CF**（异常自动穿透 N 层不用每层判空），**单操作确认选 Netty**（一个监听器一个 if，简单直接）。两者共同的坑：**无人消费的失败都静默**——CF 无下游无 get 则无声，Netty 无监听器无 await 则无声（CF 甚至连 warn 都没有，Netty 至少在监听器抛异常时会记日志）。

### 3.5 组合能力实战对照

同一需求——**并行调三个下游、任一失败整体降级、其中一个要 200ms 超时兜底**：

```java
// CompletableFuture：声明式，业务结构即代码结构
CompletableFuture<Page> page =
    CompletableFuture.supplyAsync(() -> userCli.get(uid), biz)
        .thenCombine(CompletableFuture.supplyAsync(() -> orderCli.list(uid), biz),
                     Page::ofUserOrders)
        .thenCombine(CompletableFuture.supplyAsync(() -> recoCli.get(uid), biz)
                         .orTimeout(200, TimeUnit.MILLISECONDS)
                         .exceptionally(e -> Reco.empty()),
                     Page::withReco)
        .exceptionally(t -> Page.fallback(uid));   // 整体兜底
```

```java
// Netty：无组合原语，需手工编排计数器与竞态（伪代码忠实还原可用写法）
EventExecutor ex = ctx.executor();
Promise<Page> result = ex.newPromise();
AtomicInteger left = new AtomicInteger(3);
AtomicReference<Object> user = new AtomicReference<>(), orders = ..., reco = ...;

userFuture.addListener(f  -> { if (!f.isSuccess()) return result.tryFailure(f.cause());
                               user.set(f.getNow()); tryAggregate(); });
orderFuture.addListener(f -> { ...同上... });
recoFuture.addListener(f  -> { reco.set(f.isSuccess() ? f.getNow() : Reco.empty()); tryAggregate(); });
// tryAggregate(): left==0 时 result.trySuccess(组装 Page)
// 还需自备：recoFuture 的 200ms 超时（ex.schedule 里 tryFailure/写默认值）
```

高下立判：**这正是两套 API 的分水岭**——Netty 的组合要自己造轮子，但它换来的单对象、零中间分配、线程确定，在网络内核里每毫秒都在收益。业务编排层照抄 Netty 风格，等于放弃 CF 十年沉淀的表达力。

### 3.6 性能与内存对比

| 关注点 | CompletableFuture | Netty |
|---|---|---|
| 一次操作的对象 | 算子链 N 步 = 2N 个新对象 | 1 个 promise；voidPromise 路径 0 个 |
| 无锁结构 | VarHandle CAS（result/stack/next）+ ForkJoinTask tag 位 | AtomicReferenceFieldUpdater CAS + synchronized(监听器/等待者路径) |
| 锁的使用 | 完全无 synchronized（park/ManagedBlock） | 监听器增删与 waiters 用 `synchronized(this)`（低频路径可接受） |
| 级联防护 | postComplete 循环蹦床，O(1) 栈深传播任意链长 | 栈深计数 >8 转投递任务 |
| GC 防泄漏 | 触发后字段置 null + cleanStack 惰性摘死节点 | 通知后 listener(s) 置 null；数组复用 |
| 高频特化 | — | LeanCancellationException（取消零抓栈）、SUCCESS 哨兵（Void 零分配）、voidPromise |
| 调度集成 | ForkJoinTask 血统（work-stealing、ManagedBlock 补偿线程） | EventLoop 亲和（任务队列、pipeline 同线程） |

两套实现都是各自生态的性能标杆，方向不同：CF 优化"**吞吐与池协作**"（FJP 一等公民），Netty 优化"**单操作延迟与分配**"（网络热路径抠到每个对象）。

### 3.7 完成语义与取消对比

| 语义 | CF | Netty |
|---|---|---|
| 一次性 | CAS 保证，先到先得 | 同左 |
| 重复完成 | `complete` 返回 false | `tryXxx` 返回 false；`setXxx` 直接抛 IllegalStateException（更显式的编程错误信号） |
| 强制覆写 | `obtrudeValue/obtrudeException`（官方限错误恢复） | 无 |
| 中间态 | 无 | `UNCANCELLABLE`（可等待、不可取消、未完成） |
| cancel | 异常完成，不中断任务，mayInterrupt 无效 | 同左，完全同语义 |
| 反取消 | 无 | `setUncancellable()` / `isCancellable()` 探测 |
| 超时完成 | orTimeout/completeOnTimeout 内置 | 无内置，用 EventLoop.schedule/HashedWheelTimer 手工实现 |

### 3.8 互操作桥接

实际系统的常见形态：**Netty 管网络，CF/响应式库管业务编排**，边界处需要双向适配（两个方向都要注意线程语义）：

```java
/** Netty Future → CompletableFuture：把传输事件接回业务编排世界 */
public static <T> CompletableFuture<T> toCf(Future<T> nettyFuture) {
    CompletableFuture<T> cf = new CompletableFuture<>();
    nettyFuture.addListener(f -> {          // 注意：此回调在 EventLoop 线程执行
        if (f.isSuccess()) cf.complete(f.getNow());
        else               cf.completeExceptionally(f.cause());
    });
    return cf;
    // complete 只是 CAS 置结果，下游 thenXxx 的执行线程由下游自己的语义决定，
    // EventLoop 线程不会被业务链拖住
}

/** CompletableFuture → Netty Promise：业务结果回填传输层 */
public static <T> Promise<T> toNettyPromise(CompletableFuture<T> cf, EventExecutor executor) {
    Promise<T> promise = executor.newPromise();
    cf.whenComplete((v, t) -> {             // 可能在任意线程执行 → 必须投递回目标 executor
        executor.execute(() -> {
            if (t != null) promise.tryFailure(t);
            else           promise.trySuccess(v);
        });
    });
    return promise;
    // ★ 关键：whenComplete 的回调线程不确定，而 promise 的监听器期望在 executor 线程被通知；
    //   虽然 DefaultPromise.notifyListeners 本身会自动投递，但显式 execute 让语义更可控
}
```

Reactor Netty、Vert.x 等 Netty 之上的响应式框架，本质上就在系统化地做这类桥接（再叠一层 Publisher 语义）。

### 3.9 选型建议

| 场景 | 选择 | 理由 |
|---|---|---|
| 业务异步编排：聚合多下游、依赖流水线、超时降级、竞速容灾 | **CompletableFuture** | 组合表达力无可替代 |
| CPU 并行计算 | CompletableFuture（显式传池） | 与 FJP/并行流同生态 |
| Netty 之上写 handler / 协议栈内部 | **Netty Promise** | 线程亲和（与 pipeline 同线程）、voidPromise、与写缓冲联动 |
| 桥接回调式 SDK，结果要进业务编排 | CF | 下游要接 thenXxx 链 |
| 桥接回调式 SDK，结果只在连接内消费 | Netty Promise | 少一层适配，通知回 EventLoop |
| 海量小操作且不关心单个结果（转发/广播） | Netty voidPromise | 零分配热路径 |
| 需要进度上报（大文件/流式） | Netty ProgressivePromise | CF 无对应物 |
| 虚拟线程时代（JDK 21+），阻塞为主 | 直接阻塞 + 虚拟线程 | 两种异步 API 的复杂度都可绕开；CF 仍是"少量异步 + 大量阻塞混排"的粘合剂 |

**一句话总结**：CompletableFuture 回答"**如何把多个异步计算声明式地编排成数据流**"，Netty Future/Promise 回答"**一个网络操作落定时，如何在确定无疑的线程上得到通知**"。前者赢在组合，后者赢在线程确定性与单操作开销；一个是应用层的编排语言，一个是内核层的事件凭据。理解这条分界线，就理解了 Java 异步编程的两条主线——也就知道什么时候该用哪个、在边界上怎么缝合。
