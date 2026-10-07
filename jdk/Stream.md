# JDK Stream 深度源码解析（写给初学者的机制全景）

> **本文基于的源码**：`D:\soft\jdk\jdk-27\lib\src.zip` 解压后的 `java.base` 模块，版本 **JDK 27**。核心类集中在 `java.base/java/util/stream/` 包（40 个文件）与 `java.base/java/util/Spliterator.java`，文中所有【源码证据】的文件路径与行号均为对该版本实际读取所得。Stream 引擎（`AbstractPipeline`/`ReferencePipeline`/`Sink`）自 JDK 8 以来骨架几乎未动，因此行号对 JDK 21/25 同样基本适用；JDK 22 新增的 `Gatherer` 体系（JDK 24 定稿，见 1.5 节）只有 JDK 24+ 的源码里才有。
>
> **实测验证**：文中所有行为结论（惰性求值、垂直执行、一次性消费、`count()` 的零遍历优化、并行流乱序与线程数、并行 `ArrayList` 丢数据、Gatherer 五件套）均在 `D:\tmp\stream-demo\StreamDemo.java` 用 JDK 27 实际运行验证过，关键输出以【实测输出】形式摘录。
>
> **阅读约定**：每章"先白话、后源码"——先用一两句人话讲清"这是什么、为什么需要它"，再给出源码证据链（`java.base/java/util/stream/类名.java` + 方法名 + 行号 + 代码片段）。行号只对 JDK 27 的 src.zip 精确，读者用 IDEA 打开源码按类名 + 方法名定位即可。
>
> **系列关联**：本文与 [Flow.md](Flow.md)、[Java并发.md](Java并发.md)、[SPI机制.md](SPI机制.md) 同属 D:\ai\jdk 系列。分工一句话说清：**Stream 解决"一批内存中已有的数据如何声明式地算出一个结果"，Flow 解决"两个速率不匹配的组件之间如何持续传输数据"，CompletableFuture 解决"一件事完成后如何在回调里接续"**（见 [《CompletableFuture 与 Netty Future/Promise 深度剖析》](../netty/CompletableFuture&Future-Promise.md)）。第十张贯通图把它们放进同一张地图。

## 如何读这份文档

如果你是 Stream 初学者，推荐两遍读法：

- **第一遍（建立地图，1~2 小时）**：只读第一章（总览）每节的白话段、第二章 2.1（六种创建姿势）、第四章 4.1 与第五章 5.1 的两张分类总表、每章的"本章小结"。目标是能回答：流和集合差在哪？为什么中间操作"不执行"？哪些是中间操作、哪些是终端操作？
- **第二遍（深入源码）**：顺序建议：第三章（流水线引擎，本文的心脏）→ 第五章（终端操作与短路）→ 第六章（Collector）→ 第七章（并行流）→ 第四章（中间操作，随用随查）→ 第八章（Gatherer，JDK 22+ 的新能力）→ 第九章（陷阱清单，可跳读）。

---

## 目录

- [一、总览：Stream 的定位、设计哲学与全景](#一总览stream-的定位设计哲学与全景)
  - [1.1 一句话定位](#11-一句话定位)
  - [1.2 设计哲学：读源码前先记住五句话](#12-设计哲学读源码前先记住五句话)
  - [1.3 全景图：一条流水线的解剖](#13-全景图一条流水线的解剖)
  - [1.4 关键问题映射](#14-关键问题映射)
  - [1.5 版本演进时间线](#15-版本演进时间线)
  - [1.6 全文章节地图](#16-全文章节地图)
- [二、创建流：数据源与 Spliterator](#二创建流数据源与-spliterator)
- [三、流水线引擎：Stage 链与 Sink 链](#三流水线引擎stage-链与-sink-链惰性求值的全部秘密)
- [四、中间操作全景](#四中间操作全景9-个无状态--7-个有状态--mapmultigather)
- [五、终端操作全景](#五终端操作全景数据的泄洪口)
- [六、collect 与 Collector](#六collect-与-collector把如何收集抽成一个参数)
- [七、并行流](#七并行流forkjoin-之上的免费并行)
- [八、Gatherer：JDK 22+ 的中间操作扩展点](#八gathererjdk-22-的中间操作扩展点)
- [九、陷阱清单与最佳实践](#九陷阱清单与最佳实践)
- [十、贯通视图](#十贯通视图stream-在-jdk-数据处理版图中的位置)

---

# 一、总览：Stream 的定位、设计哲学与全景

## 1.1 一句话定位

**Stream 是 JDK 8 引入的一套"对数据源做声明式聚合运算"的 API**：你描述"要做什么"（过滤、映射、归约），而不是"怎么循环"；流水线在终端操作被调用的一刻才真正开始搬运数据，并且可以一键切换为并行执行。

`Stream` 接口的 Javadoc 开篇就是这句定义（`stream/Stream.java:47-57`）：

> A sequence of elements supporting sequential and parallel aggregate operations.

翻译成大白话，它同时做了三件事：

1. **统一了遍历的姿势**。JDK 8 之前，"筛选红色积木再统计重量"要写外部迭代：`for (Widget w : widgets) { if (...) total += ...; }`。Stream 把"遍历 + 过滤 + 累加"的**控制权**从你的 for 循环手里收归库所有——你只交出 lambda，库决定"什么时候、以什么顺序、在哪个线程"执行你的逻辑。这就是**内部迭代**。
2. **把"算子"变成了可拼接的流水线**。`filter`、`map`、`sorted`……每个算子都返回新的 `Stream`，像 Linux 管道一样串起来；而真正触发计算的只有最后一个终端操作（`collect`/`forEach`/`reduce`…）。
3. **把"怎么并行"藏进了库里**。同一个流水线把 `.stream()` 换成 `.parallelStream()`（或中途加 `.parallel()`）就自动切块并行——前提是你的算子满足第三章讲的契约。

它解决的核心问题是**"集合上的批量计算写起来啰嗦、难以复用、更难并行"**：for 循环把"做什么"和"怎么做（遍历细节、临时变量、控制流）"焊死在一起，同一套过滤逻辑没法复用，想并行就得手写线程池和分块。Stream 用声明式把这两者拆开。

但也要先泼一盆冷水，**Stream 不是什么**：

- 它**不是数据结构**：不存元素、不能按下标访问（`Stream.java:95-103` 明说 "streams do not provide a means to directly access or manipulate their elements"）；
- 它**不是 IO 流**：`InputStream`/`OutputStream` 是字节通道，`Stream` 是聚合运算，名字撞车仅此而已；
- 它**不是响应式流**：`java.util.concurrent.Flow` 才管"生产快消费慢"的背压传输（见 [Flow.md](Flow.md)），Stream 的数据源在终端操作开始时是"静止"的一批数据。

## 1.2 设计哲学：读源码前先记住五句话

1. **流是管道的说明书，不是数据**。每调用一个中间操作，只是在内存里**多登记一个阶段**（一个双链表节点），一个字节都不会被处理。真正的计算被推迟到终端操作（**惰性求值**，`Stream.java:78-80` 的 javadoc 原话："Streams are lazy; computation on the source data is only performed when the terminal operation is initiated"）。第三章整个章节都在解释这句话在源码里如何落地。
2. **万物之源是 Spliterator**。集合、数组、生成器、文件行……所有数据源都被统一适配成 `Spliterator`（可分裂迭代器）：既能逐个吐元素，又能对半劈开自己交给别的线程——前者支撑顺序执行，后者支撑并行执行。第二章详解。
3. **数据流是"推"的，控制流是"拉"的**。执行时，Spliterator 从源头把元素**推**进一串首尾相接的 `Sink`（回调链），元素像流水线上的工件一样逐个流完全程（垂直执行）；而"够了没有/要不要停"由终端 Sink 通过 `cancellationRequested()` 向上**拉**问——这就是短路（`anyMatch`/`findFirst`/`limit`）的实现基础。
4. **每个算子自带"能力声明"**。流框架用一组位标志（`StreamOpFlag`：SIZED/DISTINCT/SORTED/ORDERED/SHORT_CIRCUIT）记录每个阶段"保留/清除/注入"了哪些特性，让后面的操作能据此走捷径——比如 `count()` 在整条链 SIZED 时可以直接读出尺寸、一次都不遍历（第五章实测）。
5. **并行是"免费"的选项，但契约很贵**。切换并行的代价只是一次方法调用，但你的算子必须满足**无状态、无干扰、结合律**三条契约（第七章实测违反它们的后果：丢数据、结果错）。

## 1.3 全景图：一条流水线的解剖

一次典型的 Stream 使用，在源码里对应三个层次。以官方示例（`Stream.java:52-57`）为例：

```java
int totalWeight = widgets.stream()          // ① 数据源：Collection → Spliterator
                         .filter(w -> w.getColor() == RED)   // ② 中间操作：登记阶段
                         .mapToInt(w -> w.getWeight())       // ② 中间操作 + 形状转换
                         .sum();            // ③ 终端操作：触发整条流水线
```

```
┌─────────────────────────────── 流水线解剖图 ───────────────────────────────┐
│                                                                            │
│  数据源层        widgets.stream()                                           │
│  (第二章)        Collection.stream() → StreamSupport.stream(spliterator,false)
│                        │                                                    │
│                        ▼   ReferencePipeline.Head（源阶段，持 Spliterator）  │
│  阶段链层        ┌─────────┐   ┌─────────┐   ┌──────────────┐               │
│  (第三/四章)     │  filter  │ → │ mapToInt│ → │     sum()    │               │
│                 │Stateless │   │Stateless│   │  TerminalOp  │               │
│                 └─────────┘   └─────────┘   └──────────────┘               │
│                  每个节点 = 一个 AbstractPipeline 子类对象（双链表）          │
│                        │                                                    │
│                        ▼   evaluate() 一刻才发生                              │
│  执行层          wrapSink()：从尾到头把终端 Sink 包进每个算子 → 一条回调链     │
│  (第三/五/七章)  copyInto()：spliterator.forEachRemaining 推元素进链          │
│                  顺序：单线程一条链；并行：AbstractTask 按块 fork/join        │
└────────────────────────────────────────────────────────────────────────────┘
```

三个层次各自的"门面"类：

| 层次 | 你看到的 API | 库里的实现 | 源码位置 |
|---|---|---|---|
| 数据源 | `list.stream()` / `Arrays.stream(...)` | `StreamSupport.stream(spliterator, parallel)` | `StreamSupport.java:67`；`java/util/Collection.java:747-749` |
| 中间操作 | `filter`/`map`/`sorted`… | 新建一个 `ReferencePipeline` 子类节点 | `ReferencePipeline.java:182-626` |
| 终端操作 | `collect`/`reduce`/`forEach`… | 构造一个 `TerminalOp` 并 `evaluate` | `AbstractPipeline.java:257-266`；`ReduceOps.java:897-929` |

四种流类型共享同一套引擎，只是"形状"（`StreamShape.java:30-55`）不同：

| 类型 | 元素 | 用途 |
|---|---|---|
| `Stream<T>` | 对象引用 | 通用 |
| `IntStream` / `LongStream` / `DoubleStream` | int/long/double | 避免装箱、提供 sum/average/summaryStatistics 等数值终端操作 |

## 1.4 关键问题映射

带着问题读，每章负责回答：

| # | 问题 | 答案在哪 |
|---|---|---|
| 1 | 流存数据吗？和集合什么关系？ | 1.1、十 |
| 2 | 为什么中间操作不执行？数据到底怎么流的？ | 三 |
| 3 | `filter` 里一句 lambda 是怎么被执行的？ | 3.3、3.4 |
| 4 | 有哪些中间操作？每个怎么写？ | 四 |
| 5 | 有哪些终端操作？短路是怎么回事？ | 五 |
| 6 | `collect(Collectors.groupingBy(...))` 背后是什么机制？怎么自定义收集？ | 六 |
| 7 | `parallel()` 到底做了什么？什么时候能用？怎么翻车？ | 七 |
| 8 | JDK 22 的 `Gatherer` 补了什么缺口？ | 八 |
| 9 | 哪些坑最容易踩？ | 九 |
| 10 | Stream / Collection / Flow / CompletableFuture 怎么分工？ | 十 |

## 1.5 版本演进时间线

| 版本 | 变化 | 内容 |
|---|---|---|
| **JDK 8 (2014)** | **Stream 诞生**（JSR 335，Project Lambda） | `java.util.stream` 全套引擎 + `Stream`/`IntStream`/`LongStream`/`DoubleStream` + `Collectors`（44 个工厂方法） |
| JDK 9 (2017) | 中间操作补强 | `takeWhile`/`dropWhile`（`Stream.java:759/:825`）、`iterate(seed, hasNext, next)`（`:1565`）、`ofNullable`（`:1462`） |
| JDK 16 (2021) | 一对多 + 收集便利 | `mapMulti` 四兄弟（`:426` 起，避免 flatMap 每元素建 Stream 的开销）、`Stream.toList()`（`:1248`，返回不可变列表） |
| JDK 22 (2024) | **中间操作扩展点（预览）** | JEP 461：`Stream.gather(Gatherer)` 首次预览 |
| JDK 23 (2024) | 二次预览 | JEP 473 |
| **JDK 24 (2025)** | **Gatherer 定稿** | **JEP 485**：`gather`/`Gatherer`/`Gatherers` 转正，`@since 24`（`Stream.java:1099` 的 javadoc）；这是 Stream API 自 JDK 8 以来最大的一次能力扩展 |
| JDK 25 / 27 | 无 API 变化 | 引擎稳定 |

演进的主线很清晰：**8 年里（8→16）JDK 只在做"补算子"的小修小补，真正的大动作是把"中间操作"本身开放成了可编程扩展点（Gatherer）**——因为 `map`/`flatMap`/`mapMulti` 这类固定算子永远覆盖不完"带状态的一对多变换"（滑动窗口、前缀和、相邻去重……），第八章展开。

## 1.6 全文章节地图

```
二 创建流 ──► 三 引擎(Stage链+Sink链) ──► 四 中间操作 ──► 五 终端操作
   Spliterator      惰性/垂直执行           无状态/有状态      reduce/collect/短路
                          │                                        │
                          ▼                                        ▼
                    七 并行流(用同一个引擎)                 六 Collector(终端操作的参数化)
                          │                                        │
                          └────────────┬───────────────────────────┘
                                       ▼
                          八 Gatherer(中间操作扩展点)
                                       ▼
                          九 陷阱清单 ──► 十 贯通视图
```

# 二、创建流：数据源与 Spliterator

## 2.1 六种创建姿势

| 姿势 | 示例 | 适用场景 | 源码锚点 |
|---|---|---|---|
| ① 集合 | `list.stream()` / `list.parallelStream()` | 最常用 | `Collection.java:747-749/:768-770` |
| ② 数组 | `Arrays.stream(arr)` / `Stream.of("a","b")` | 定长数据 | `Stream.java:1476-1478`（`of` 可变参直接转给 `Arrays.stream`） |
| ③ 静态工厂 | `Stream.of(t)`、`Stream.empty()`、`Stream.ofNullable(t)` | 单元素/可能为 null | `Stream.java:1448/:1437/:1462` |
| ④ Builder | `Stream.builder().add("x").add("y").build()` | 逐个添加 | `Stream.java:1427`，`Builder` 接口 `:1691-1733` |
| ⑤ 生成器 | `Stream.iterate(0, i->i+2)`、`Stream.generate(Math::random)` | **无限流**（配 `limit`/`takeWhile` 用） | `Stream.java:1503/:1620` |
| ⑥ IO / 文本 | `Files.lines(path)`、`BufferedReader.lines()` | 大文件逐行（**必须 close**） | `Stream.java:137-145` 的 javadoc 特别提醒 |

外加两个"逃生舱"：`BaseStream.iterator()` / `BaseStream.spliterator()`（`BaseStream.java:72/:95`）——当预置算子不够用时，把流水线变回迭代器/Spliterator 手工驱动；它们本身也算终端操作（一旦调用，流水线就被消费）。

【源码证据】所有姿势殊途同归——最终都汇入 `StreamSupport.stream(Spliterator, boolean parallel)`（`StreamSupport.java:67`），它只做一件事：拿一个 Spliterator 和"是否并行"的布尔值，new 一个 `ReferencePipeline.Head`（`ReferencePipeline.java:762-819`）。**"数据源"在流的世界里被压缩成了一句话：一个 Spliterator + 一个并行标志。**

```java
// StreamSupport.java:67-73
public static <T> Stream<T> stream(Spliterator<T> spliterator, boolean parallel) {
    Objects.requireNonNull(spliterator);
    return new ReferencePipeline.Head<>(spliterator,
                                        StreamOpFlag.fromCharacteristics(spliterator),
                                        parallel);
}
```

## 2.2 Spliterator：流的"数据源适配器"

**白话**：`Spliterator = Splittable Iterator`，可分裂的迭代器。它比 `Iterator` 多两样东西：① `trySplit()` 能把"剩余的元素"劈一半出去给另一个 Spliterator（并行的根基）；② `characteristics()` 声明数据源的结构特性（有没有序、知不知道大小……），让框架走捷径。

四个核心方法（`Spliterator.java:312/:376/:401/:438`）：

```java
boolean tryAdvance(Consumer<? super T> action);  // 逐个吐：还有元素就喂给 action 并返回 true
default void forEachRemaining(Consumer<? super T> action);  // 批量吐：一口气喂完剩余所有
Spliterator<T> trySplit();                       // 劈一半：把自己剩余元素的前半截切出去
long estimateSize();                             // 估计还剩多少（SIZED 时必须精确）
int characteristics();                           // 特性位集
```

八个特性位（`Spliterator.java:492-590`）及其对流执行的影响：

| 特性 | 含义 | 对框架的意义 |
|---|---|---|
| `ORDERED` (0x10) | 有**相遇顺序**（List 有，HashSet 没有） | 有序时并行结果必须按顺序合并，`limit/skip/findFirst` 代价上升（7.5） |
| `DISTINCT` (0x1) | 元素天然互异（Set） | 后面的 `distinct()` 直接跳过（`DistinctOps.java:71/:104`） |
| `SORTED` (0x4) | 天然有序（SortedSet） | 后面的 `sorted()` 直接跳过 |
| `SIZED` (0x40) | 精确知道剩余数量 | `toArray` 一次性分配数组、`count()` 零遍历（5.4 实测） |
| `NONNULL` (0x100) | 保证无 null | — |
| `IMMUTABLE` (0x400) | 源不可变（如 `List.of`） | 不需要 fail-fast 检查 |
| `CONCURRENT` (0x1000) | 源可并发改（如 `ConcurrentHashMap`） | 遍历中改源也不炸 |
| `SUBSIZED` (0x4000) | 劈开后两半都 SIZED | 并行切块时尺寸可精确分配 |

**late-binding（晚绑定）**（`Spliterator.java:65-74`）：`Collection.spliterator()` 返回的 Spliterator 在**第一次遍历/劈分/查尺寸**时才真正绑到源上——这就是为什么你可以先 `list.stream()`、后改 list、再 `collect`（只要没开始遍历）。绑定之后再改源，最好情况抛 `ConcurrentModificationException`（fail-fast），最坏情况未定义行为（`Stream.java:105-109`）。

【源码证据】`Collection.stream()` 把"数据源适配"完全委托给 `spliterator()`：

```java
// Collection.java:747-749
default Stream<E> stream() {
    return StreamSupport.stream(spliterator(), false);
}
```

而 `ArrayList.spliterator()` 返回的 `ArrayListSpliterator` 报告 `SIZED | SUBSIZED | ORDERED`——这三个位决定了后面所有优化能不能启用。

## 2.3 自定义 Spliterator：30 行看懂"可分裂"

JDK 的 `Spliterator.java:182-242` javadoc 里就躺着一个教学实现（隔位存数据的数组），压缩后如下——**并行能力全部来自 `trySplit` 对半劈**：

```java
static class TaggedArraySpliterator<T> implements Spliterator<T> {
    private final Object[] array;
    private int origin;          // 当前位置（劈分或遍历时推进）
    private final int fence;     // 终点

    TaggedArraySpliterator(Object[] array, int origin, int fence) {
        this.array = array; this.origin = origin; this.fence = fence;
    }

    public boolean tryAdvance(Consumer<? super T> action) {     // 逐个吐
        if (origin < fence) { action.accept((T) array[origin]); origin += 2; return true; }
        return false;
    }

    public Spliterator<T> trySplit() {                          // 对半劈
        int lo = origin, mid = ((lo + fence) >>> 1) & ~1;
        if (lo < mid) { origin = mid; return new TaggedArraySpliterator<>(array, lo, mid); }
        return null;                                            // 小到劈不动了
    }

    public long estimateSize() { return (fence - origin) / 2; }
    public int characteristics() { return ORDERED | SIZED | IMMUTABLE | SUBSIZED; }
}
```

`trySplit()` 返回 `null` 是合法的"我不劈了"信号。**劈得越均匀（理想是精确二分），并行收益越高**——`HashSet` 按"桶区间"劈、`LinkedList` 劈之前要先数一半元素（质量差），这是 7.4 节并行收益差异的根源。

## 2.4 基本类型流：三个"免装箱"副本

`Stream<T>` 元素是引用，`int` 进来就得装箱成 `Integer`（每个对象 16 字节 + GC 压力）。JDK 8 同时提供了 `IntStream`/`LongStream`/`DoubleStream`，元素直接存在 `int[]`/`long[]`/`double[]` 里，并额外提供 `sum()`/`average()`/`summaryStatistics()` 等数值终端操作。

```java
// 反例：装箱流，Integer 挤爆堆
Stream<Integer> boxed = Stream.of(1, 2, 3);
// 正例：数值流
IntStream nums = IntStream.of(1, 2, 3);
IntSummaryStatistics st = IntStream.of(3, 1, 4, 1, 5).summaryStatistics();
// st: {count=5, sum=14, min=1, average=2.800000, max=5}【实测输出】
```

【实测输出】500 万个 long 求和：装箱路径 `mapToObj→mapToLong` 比 `LongStream.sum()` 直接路径慢约 20%（28.9ms vs 24.4ms，`StreamDemo.java` 第 14 节；差异随数据量放大）。

两个方向之间的桥梁：`mapToInt`/`mapToLong`/`mapToDouble`（对象流→数值流，`Stream.java:208/:222/:236`）与 `boxed()`/`mapToObj`（数值流→对象流）。底层用一个枚举 `StreamShape`（`StreamShape.java:30-55`）区分四种形状，`mapToInt` 返回的是 `IntPipeline` 而不是 `ReferencePipeline`（`ReferencePipeline.java:222-236`），形状不匹配的算子在编译期就接不上。

### 本章小结

- 一切数据源 → 一个 `Spliterator` + 一个 `parallel` 标志 → `ReferencePipeline.Head`。
- Spliterator 的 8 个特性位是流框架所有"捷径"（跳 distinct、零遍历 count、精确分配数组）的信息来源。
- `trySplit` 的劈分质量决定并行收益；SIZED/ORDERED 决定顺序执行的优化空间。
- 能用 `IntStream`/`LongStream`/`DoubleStream` 就别用装箱流；大量数值计算这是最大的一档优化。

# 三、流水线引擎：Stage 链与 Sink 链（惰性求值的全部秘密）

## 3.1 白话：流为什么"不流"

先看一个反直觉的事实【实测输出，`StreamDemo.java` 第 1 节】：

```java
List<String> r = List.of("a1", "a2", "b1", "b2", "c1").stream()
        .filter(x -> { System.out.println("filter called: " + x); return x.startsWith("a"); })
        .map(x ->  { System.out.println("map called: " + x); return x.toUpperCase(); });
// —— 到这里，控制台什么都没打印！——
System.out.println(r.collect(Collectors.toList()));
// 现在才打印：filter called: a1 → map called: a1 → filter called: a2 → map called: a2
//            → filter called: b1 → filter called: b2 → filter called: c1
// 结果 [A1, A2]
```

两个结论：① `filter`/`map` 被调用时**一个元素都没处理**，只是"登记"了两个阶段；② 真正执行时，**一个元素会一口气流完 filter→map→终端**，而不是"先把所有元素 filter 完、再把幸存者 map 完"（后者叫水平执行）。这就是**垂直执行**（element-by-element）。

`AbstractPipeline` 的类注释开宗明义（`AbstractPipeline.java:56-65`，implNote 原文）：

> For sequential streams ... pipeline evaluation is done in a single pass that **"jams" all the operations together**. ... In all cases, **the source data is not consumed until a terminal operation begins**.

实现上只用了两个数据结构，后面三节逐个拆：

- **Stage 链**：每个中间操作 new 一个 `AbstractPipeline` 子类对象，用 `previousStage`/`nextStage` 串成双链表——这是"登记"。
- **Sink 链**：终端操作触发时，从链尾往链头把每个算子"包"进一条回调链，然后让 Spliterator 推元素——这是"执行"。

## 3.2 Stage 链：AbstractPipeline 的字段与构造

【源码证据】`AbstractPipeline.java:72-143` 的全部实例字段，每个都值得念一遍：

```java
abstract class AbstractPipeline<E_IN, E_OUT, S extends BaseStream<E_OUT, S>>
        extends PipelineHelper<E_OUT> implements BaseStream<E_OUT, S> {   // :72-73
    private final AbstractPipeline sourceStage;      // :82  永远指向链头（源阶段）
    protected final AbstractPipeline previousStage;  // :88  上游节点（链头为 null）
    protected final int sourceOrOpFlags;             // :94  本阶段的特性标志
    private AbstractPipeline nextStage;              // :101 下游节点
    private int depth;                               // :108 距源（或上一个有状态操作）的深度
    private int combinedFlags;                       // :115 源+截至本阶段所有算子标志的合成
    private Spliterator<?> sourceSpliterator;        // :123 只有链头持有：数据源
    private Supplier<? extends Spliterator<?>> sourceSupplier; // :130 链头持有的懒数据源
    private boolean linkedOrConsumed;                // :135 "本节点是否已被链接/消费"——一次性消费的开关
    private boolean parallel;                        // :143 只有链头有效：并行标志
}
```

两个构造器对应两种身份：

1. **链头**（`AbstractPipeline.java:153-185`）：`previousStage = null`、`sourceStage = this`、`depth = 0`，持有 Spliterator。
2. **中间阶段**（`AbstractPipeline.java:199-210`）：构造时完成"登记"——检查上游未被消费（`:200-201`，违者抛 `IllegalStateException`，错误文案就是 3.7 节实测的那句），然后把上游的 `nextStage` 指向自己、把自己的 `previousStage` 指向上游、深度 +1、**标志合成**（`:207`）：

```java
AbstractPipeline(AbstractPipeline<?, E_IN, ?> previousStage, int opFlags) {
    if (previousStage.linkedOrConsumed)                 // :200  上游已被用过？
        throw new IllegalStateException(MSG_STREAM_LINKED);
    previousStage.linkedOrConsumed = true;              // :202  标记上游已被链接
    previousStage.nextStage = this;                     // :203  接上双向链
    this.previousStage = previousStage;                 // :205
    this.sourceOrOpFlags = opFlags & StreamOpFlag.OP_MASK;
    this.combinedFlags = StreamOpFlag.combineOpFlags(opFlags, previousStage.combinedFlags); // :207
    this.sourceStage = previousStage.sourceStage;       // :208  认祖归宗
    this.depth = previousStage.depth + 1;               // :209
}
```

所以 `list.stream().filter(p1).map(f).filter(p2)` 在内存里就是四个对象串成的链：

```
Head(source=Spliterator) ⇄ filter(p1) ⇄ map(f) ⇄ filter(p2)
   parallel=true/false 存在链头; 每个节点只带自己的标志位
```

**注意它有多"便宜"**：没有数组、没有元素、没有任何数据处理——一个 lambda 包进一个匿名类对象而已。这就是"流是说明书"的字面含义。

## 3.3 中间操作的本质：返回"会包装下游 Sink 的"新阶段

那 `filter` 到底返回了什么？【源码证据】`ReferencePipeline.java:182-202`（这是全文最值得精读的 20 行）：

```java
@Override
public final Stream<P_OUT> filter(Predicate<? super P_OUT> predicate) {
    Objects.requireNonNull(predicate);
    return new StatelessOp<P_OUT, P_OUT>(this, StreamShape.REFERENCE,
            StreamOpFlag.NOT_SIZED) {                    // ← 声明：我清除了 SIZED 特性
        @Override
        Sink<P_OUT> opWrapSink(int flags, Sink<P_OUT> sink) {   // ← 核心方法：包装下游
            return new Sink.ChainedReference<P_OUT, P_OUT>(sink) {   // 持有 downstream
                @Override
                public void accept(P_OUT u) {
                    if (predicate.test(u))
                        downstream.accept(u);            // ← 测试通过才放行给下游
                }
            };
        }
    };
}
```

三层结构看清"登记"和"执行"的分工：

1. `new StatelessOp<>(...)`：**调用 filter 的当下**发生的全部事情——在链表尾部挂一个节点。`StatelessOp`（`ReferencePipeline.java:828-849`）只是把 `opIsStateful()` 固定为 `false` 的中间基类。
2. `opWrapSink(flags, sink)`：**现在不执行**，它是留给执行期的一个"包装函数"——给我下游的 Sink，我把"谓词测试"逻辑包在外面还给你。
3. `Sink.ChainedReference`（`Sink.java:247-268`）：链式 Sink 基类，持有 `downstream` 字段，`begin/end/cancellationRequested` 都原样转发下游。

`map` 一模一样，只是 `accept` 里多了一步变换（`ReferencePipeline.java:210-218`）：

```java
Sink<P_OUT> opWrapSink(int flags, Sink<R> sink) {
    return new Sink.ChainedReference<>(sink) {
        @Override
        public void accept(P_OUT u) {
            downstream.accept(mapper.apply(u));          // ← 变换后必放行
        }
    };
}
```

`peek` 则是"既放行又旁路打印"（`:572-580`：`action.accept(u); downstream.accept(u);`）。**每个中间操作 = 一段"如何包装下游 Sink"的逻辑**，这个统一抽象是整个框架最漂亮的一步。

## 3.4 终端操作触发的一刻：evaluate → wrapSink → copyInto

终端操作是唯一的"点火器"。以 `reduce` 为例，`ReferencePipeline.java:692-694`：

```java
public final P_OUT reduce(final P_OUT identity, final BinaryOperator<P_OUT> accumulator) {
    return evaluate(ReduceOps.makeRef(identity, accumulator, accumulator));
}
```

`evaluate`（`AbstractPipeline.java:257-266`）做三件事：标记流水线已消费 → 按 `isParallel()` 分派给 `TerminalOp.evaluateSequential/evaluateParallel` → 返回结果。顺序路径最终走进 `ReduceOp.evaluateSequential`（`ReduceOps.java:919-922`）：

```java
public <P_IN> R evaluateSequential(PipelineHelper<T> helper, Spliterator<P_IN> spliterator) {
    return helper.wrapAndCopyInto(makeSink(), spliterator).get();
    //                ①包装        ②推数据        ③取结果
}
```

三步走读：

**① `wrapSink`——从链尾到链头把 Sink 层层包起来**（`AbstractPipeline.java:604-611`）：

```java
final <P_IN> Sink<P_IN> wrapSink(Sink<E_OUT> sink) {
    Objects.requireNonNull(sink);
    for (AbstractPipeline p = AbstractPipeline.this; p.depth > 0; p = p.previousStage) {
        sink = p.opWrapSink(p.previousStage.combinedFlags, sink);   // 从最后一个算子往前包
    }
    return (Sink<P_IN>) sink;
}
```

循环从**链尾**（depth 最大的节点）往**链头**走：链尾的算子先包（贴着终端 Sink，成为洋葱最内层），链头方向的算子最后包（成为最外层）。对 `filter(p1).map(f).filter(p2)`，包装完成后得到一条"洋葱"，元素流动方向与声明顺序一致：

```
Spliterator 推入
      │
      ▼
filter(p1).accept ──► map(f).accept ──► filter(p2).accept ──► 终端 ReducingSink.accept
（最外层 = 最先声明的算子；p1 拒绝的元素根本到不了 map）         （最内层 = 最后声明的算子）
```

**② `copyInto`——推元素并处理短路**（`AbstractPipeline.java:565-576`）：

```java
final <P_IN> void copyInto(Sink<P_IN> wrappedSink, Spliterator<P_IN> spliterator) {
    if (!StreamOpFlag.SHORT_CIRCUIT.isKnown(getStreamAndOpFlags())) {
        wrappedSink.begin(spliterator.getExactSizeIfKnown());   // 通知链：开饭（可带精确尺寸）
        spliterator.forEachRemaining(wrappedSink);              // 主循环：逐个推
        wrappedSink.end();                                      // 通知链：吃完收尾
    }
    else {
        copyIntoWithCancel(wrappedSink, spliterator);           // 短路版：见 5.5
    }
}
```

`forEachRemaining` 每吐一个元素调一次 `wrappedSink.accept(e)`，元素就沿着 3.3 节包装好的回调链逐站流动——**这就是垂直执行的全部实现：没有任何魔法，只是"每层的 accept 里调下游的 accept"**。

**③ `get()`**——终端 Sink 吐出最终结果（`ReduceOps.java:877-885` 的 `Box.state`）。

## 3.5 实测：亲手验证"垂直执行"

【实测输出，`StreamDemo.java` 第 2 节】在 filter/map/forEach 里都打印，观察每个元素的轨迹：

```java
List.of(1, 2, 3, 4).stream()
        .filter(x -> { System.out.println("filter(" + x + ")"); return x % 2 == 0; })
        .map(x -> { System.out.println("  map(" + x + ")"); return "v" + x; })
        .forEach(x -> System.out.println("    forEach(" + x + ")"));
```

```
filter(1)          ← 1 被 filter 拒绝，直接看下一个，map 没被调用（filter 的 accept 里没放行）
filter(2)
  map(2)
    forEach(v2)    ← 2 一口气流完全程
filter(3)          ← 3 被拒绝
filter(4)
  map(4)
    forEach(v4)
```

对照"水平执行"的心智模型（`filter 全部完成 → map 开始`）可以确认输出不是这种形态。**副作用场景记住这张图**：`peek` 打日志调试时，你会看到的就是垂直顺序；反之，想让"打印所有过滤结果"和"主计算"解耦，就别指望 peek 的执行时机符合水平直觉。

另一件实测确认的事：**链条上哪个算子先包、后包完全由声明顺序决定**，执行顺序 = 声明顺序。把 `.filter()` 和 `.peek()` 交换位置，peek 是否看到"过滤前"的数据随之改变——优化器不会帮你重排（唯一例外：`count()` 这类"结果与元素无关"的终端操作可能整条剪掉，见 5.4）。

## 3.6 标志位系统：StreamOpFlag

3.2 节的 `combinedFlags`、3.3 节的 `NOT_SIZED` 都指向同一个类：`StreamOpFlag`（`StreamOpFlag.java`，772 行）。

**白话**：每个阶段除了"做什么"，还要声明"我做完了之后，数据源的五个特性（DISTINCT/SORTED/ORDERED/SIZED/SHORT_CIRCUIT）还剩几个"。框架据此走捷径。编码上每个特性占两个 bit（`StreamOpFlag.java:384-394`）：`01`=设置、`10`=清除、`11`=保留——合成时用位运算一趟算完整条链（`AbstractPipeline.java:207`）。

| 算子 | 声明 | 为什么 |
|---|---|---|
| `filter` | `NOT_SIZED`（`ReferencePipeline.java:185`） | 过滤后数量不确定了 |
| `map` | `NOT_SORTED \| NOT_DISTINCT`（`:208`） | 变换可能破坏有序性/唯一性 |
| `flatMap` | 上面俩 + `NOT_SIZED`（`:276`） | 一对多，尺寸也不确定了 |
| `peek` | `0`（`:570`） | 什么都不破坏，全部保留 |
| `sorted`/`distinct`/`limit`/`skip`/`takeWhile`/`dropWhile` | 属于 StatefulOp | 见 4.3 |

这些声明不是摆设，后面两处直接兑现成性能：

- `count()`：链上无 `NOT_SIZED` 声明时直接读尺寸、零遍历（5.4 实测）；
- `distinct()`：源带 `DISTINCT`（如 Set）时整段跳过（`DistinctOps.java:71/:104` 的 `isKnown(DISTINCT)` 检查）；
- `sorted()`：源带 `SORTED` 时跳过排序（`SortedOps.java:99-140` 的 SIZED/SORTED 检查）。

## 3.7 一次性消费：linkedOrConsumed

3.2 节的 `linkedOrConsumed` 字段（`AbstractPipeline.java:135`）在两处生效：中间操作挂链时检查上游（`:200-201`），终端操作执行时检查自己（`evaluate`，`:259-261`）。因此**一个流对象只能被操作一次**——要么被后续中间操作链接，要么被终端操作执行。

【实测输出，`StreamDemo.java` 第 3 节】：

```java
Stream<Integer> s = Stream.of(1, 2, 3);
s.map(x -> x + 1);                    // s 已被"链接"
s.forEach(System.out::println);       // 再用 → 抛异常
```

```
caught: stream has already been operated upon or closed   ← 即 MSG_STREAM_LINKED（AbstractPipeline.java:74）
```

注意区分"流对象"和"数据源"：被消费的是**流对象**（管道说明书），不是数据——重新 `list.stream()` 一次就能再来一遍。

### 本章小结

- 流水线 = 双向链表（Stage 链）+ 回调洋葱（Sink 链）；中间操作只登记（`opWrapSink` 存而不用），终端操作点火（`wrapSink` 从尾到头包装 → `copyInto` 从头到尾推元素）。
- 垂直执行不是优化技巧，而是这套结构的天然结果：元素每到一个 accept，要么继续调 `downstream.accept`，要么被吞掉。
- 每个算子用两个 bit 声明自己保留/清除了哪些特性，框架据此启用 SIZED/DISTINCT/SORTED 捷径。
- 流对象一次性消费，数据源可重复开流。

# 四、中间操作全景（9 个无状态 + 7 个有状态 + mapMulti/Gatherer）

## 4.1 分类总表

中间操作返回的仍是 `Stream`，所以能一直链下去。按"处理第 N 个元素是否需要知道其他元素"分为两大类：

| 类别 | 操作 | 特征 | 源码 |
|---|---|---|---|
| **无状态**（Stateless） | `filter`、`map`、`mapToInt/Long/Double`、`flatMap` 系、`mapMulti` 系、`peek`、`unordered` | 看完当前元素即可做出决定；并行时天然可分 | `ReferencePipeline.java:170-582` |
| **有状态**（Stateful） | `distinct`、`sorted`、`limit`、`skip`、`takeWhile`、`dropWhile`、`gather` | 需要**攒数据**（全量或前缀）；并行时把流水线切成段分别算 | `ReferencePipeline.java:586-626` + `GathererOp.java` |

两个类别一个决定性能、一个决定正确性：

- **无状态操作并行零额外代价**——每个元素独立处理，任务随便切。
- **有状态操作是并行流里最贵、最危险的部分**——`sorted` 要全量收集，`limit(n)` 在有序流上要"精确的前 n 个"，并行时框架必须先分段算完上游、再对结果重劈分（`AbstractPipeline.sourceSpliterator` 的 `hasAnyStateful()` 分支，`AbstractPipeline.java:473-506`；4.5 节展开）。

**惰性的两种深度**（常被混淆）：

- **中间操作都是"登记即返回"**——调用 `filter` 绝不触发计算；
- 但**有状态操作无法做到"元素级完全惰性"**：`sorted()` 必须见到最后一个元素才能吐出第一个；`limit(n)` 在位置 0 就已经"限"住了。它们"登记"仍然免费，真正干活仍要等终端操作。

## 4.2 无状态操作逐个讲（附写法示例）

### filter —— 谓词放行

```java
List<String> names = List.of("Alice", "Bob", "Charlie");
List<String> r = names.stream()
        .filter(n -> n.length() > 3)     // Predicate<T>：true 放行，false 吞掉
        .toList();                        // [Alice, Charlie]
```

实现见 3.3 节。注意 `Predicate` 可以用 `and/or/negate` 组合：`p1.and(p2)`。

### map —— 一对一变换

```java
List<Integer> lengths = names.stream()
        .map(String::length)             // Function<T,R>：每元素恰好产出一个新元素
        .toList();                       // [5, 3, 7]
```

### mapToInt / mapToLong / mapToDouble —— 换形状 + 免装箱

```java
int total = names.stream().mapToInt(String::length).sum();   // 15
double avg = names.stream().mapToInt(String::length).average().orElse(0);
```

返回 `IntStream` 等（`ReferencePipeline.java:222-236`，opWrapSink 里 `downstream.accept(mapper.applyAsInt(u))`——注意下游已是 int 形状的 Sink，没有 `Integer` 中转）。

### flatMap —— 一对多，"把流摊平"

```java
// 订单 → 行项目：把每个订单的所有行项目摊成一条流
List<List<Integer>> nested = List.of(List.of(1, 2), List.of(3), List.of());
List<Integer> flat = nested.stream()
        .flatMap(List::stream)           // Function<T, Stream<R>>：每元素产出一个"子流"
        .toList();                       // [1, 2, 3]
// 词频统计的经典开头：
// Files.lines(path).flatMap(line -> Stream.of(line.split("\\W+")))...
```

【源码证据】`ReferencePipeline.java:273-316`：flatMap 的 accept 里对每个上游元素调用 mapper 得到一个子流，然后用 `try (Stream<? extends R> result = mapper.apply(e))` **try-with-resources 包住子流**——子流用完即关（`:288-295`）。两点推论：

1. mapper 返回的流会被自动 close，所以 mapper 里不要返回需要长期存活的流；
2. 返回 `null` 是合法的（等价空流，`:289` 判空）。

性能注意：flatMap 为**每个上游元素**创建一个子流对象，元素多、每元素产出少（0~2 个）时开销显著——这正是 JDK 16 引入 `mapMulti` 的动机。

### mapMulti —— 一对多，免建子流（JDK 16）

```java
List<Integer> mm = List.of(1, 2, 3).stream()
        .<Integer>mapMulti((x, down) -> { down.accept(x); down.accept(x * 10); })
        .toList();                       // [1, 10, 2, 20, 3, 30]【实测输出】
```

lambda 拿到两个参数：当前元素 `x` 和一个"下游出口" `down`，想产几个就调几次 `down.accept(...)`（0 次也行）。两个典型收益场景（`Stream.java:367-377` javadoc 原文列的两条）：**每元素替换成少量元素**（省掉子流对象）、**命令式生成比组装 Stream 更自然**（如递归展开嵌套结构）。

【源码证据】`ReferencePipeline.java:473-494`：实现比 flatMap 简单得多——`mapper.accept(u, (Consumer<R>) downstream)`，直接把**下游 Sink 本身**当成出口递给 lambda，零中间容器。（`Stream.java:426-433` 的 default 实现用 SpinedBuffer 兜底，`ReferencePipeline` 覆盖了它走快路径。）

### peek —— 旁路观察（调试利器，勿当业务钩子）

```java
List.of("a", "bb", "ccc").stream()
        .filter(s -> s.length() > 1)
        .peek(s -> System.out.println("after filter: " + s))
        .map(String::toUpperCase)
        .peek(s -> System.out.println("after map: " + s))
        .toList();
```

实现就是"调一下 action 再放行"（`ReferencePipeline.java:572-580`）。**两条铁律**：

1. 位置即时机：peek 看到的是"流到此处"的元素（垂直执行，3.5 节实测）；
2. peek 里不该改数据、更不该有业务副作用——**流可能整段不执行**（例如后面接 `count()` 且链 SIZED，5.4 实测 peek 一次都没跑）。

### unordered —— 主动放弃相遇顺序（优化开关）

```java
// 并行 distinct/limit/takeWhile 在无序流上快得多：去掉"保持顺序"的负担
list.parallelStream().unordered().distinct()...
```

实现极简：一个 `NOT_ORDERED` 标志的空 opWrapSink 节点（`ReferencePipeline.java:170-179`）——它不重排任何元素，只是撕掉"必须按相遇顺序处理"的合同，让下游操作敢走快路径。

## 4.3 有状态操作逐个讲

### distinct —— 全局去重

```java
List<Integer> u = List.of(1, 2, 1, 3, 2).stream().distinct().toList();  // [1, 2, 3]
```

【源码证据】`DistinctOps.java:54-63`：顺序执行时就是往一个 **`LinkedHashSet`** 里 add（`makeRef(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll)`）——所以 `distinct` 依赖 `equals/hashCode`，且保持首次出现顺序。并行时按段去重再合并。优化：上游已带 DISTINCT 标志（Set 源）直接跳过（`:71/:104`）。并行+有序的去重要付出"按序合并"的代价，`unordered()` 是官方 javadoc 点名的提速手段（`Stream.java:562-571`）。

### sorted —— 全量排序（唯一的"见完所有元素才能吐第一个"的无条件算子）

```java
List<String> sorted = names.stream()
        .sorted(Comparator.comparingInt(String::length).reversed())
        .toList();
```

【源码证据】`SortedOps.java:99-140`：StatefulOp，opWrapSink 返回排序 Sink——`begin` 时若上游 SIZED 就**按精确尺寸分配原生数组**（`SizedRefSortingSink`，`:333-376`），`accept` 只攒不吐，`end` 时 `Arrays.sort` + 逐个放行下游。免装箱点：SIZED 时分配 `T[]` 而非 `ArrayList`。**sorted 之后的流不再 SIZED？不——它反而精确 SIZED 了；但 sorted 会清除"上游 SORTED"以外的一切排序声明**。另外：对天然 SORTED 的源（SortedSet），`sorted()` 直接跳过。

### limit / skip —— 前缀截断与跳过（切片）

```java
Stream.iterate(0, i -> i + 2).limit(5).toList();   // [0, 2, 4, 6, 8]（无限流靠它收敛）
List.of(1,2,3,4,5).stream().skip(2).limit(2).toList(); // [3, 4]
```

【源码证据】都委托给 `SliceOps.makeRef`（`ReferencePipeline.java:602-616`；`skip(0)` 直接返回 this，`:612-613`）。`SliceOps.java:104` 起按标志走三种实现：上游 SIZED 时顺序执行可**直接在 Spliterator 上做下标运算跳过前 n 个**，几乎零代价；并行有序时用 `SliceTask`（`:573`）做"前缀提取"的 fork/join。**性能反直觉点**（`Stream.java:655-666` javadoc）：有序流上 `limit(n)` 并行执行要协调"哪 n 个算数"，可能比串行还慢——`unordered()` 后立刻起飞。

### takeWhile / dropWhile —— JDK 9 的"谓词切片"

```java
List.of(1, 2, 3, 1, 2).stream().takeWhile(x -> x < 3).toList();  // [1, 2]【实测输出】
List.of(1, 2, 3, 1, 2).stream().dropWhile(x -> x < 3).toList();  // [3, 1, 2]【实测输出】
```

**takeWhile = 从头取到谓词首次为假（含）；dropWhile = 从头丢到谓词首次为假（不含），其后全部放行**。注意是"前缀"语义——不是 filter！`takeWhile(x<3)` 在 3 处断流，后面的 1、2 虽满足条件也不再输出。

【源码证据】有序流走 `WhileOps.makeTakeWhileRef`（`ReferencePipeline.java:619-621`）；无序流走 Stream 接口的 default 实现，用 `WhileOps.UnorderedWhileSpliterator` 包一层 Spliterator（`Stream.java:759-766`）。两者都是 StatefulOp（要记住"谓词是否已经失败过"这个状态）。

## 4.4 flatMap / mapMulti / Gatherer 三兄弟对比

| | `flatMap` | `mapMulti` (16) | `gather(Gatherer)` (22/24) |
|---|---|---|---|
| 产出形状 | 1 → N（N 由子流长度定） | 1 → N（手动 accept） | 1 → N、N → 1（fold）、1 → 1 带状态（scan/window）、甚至跨元素聚合 |
| 每元素开销 | 建一个 Stream 对象 | 零额外对象 | 由 gatherer 自定 |
| 跨元素状态 | 无 | 无 | 有（initializer 攒状态） |
| 典型场景 | 结构摊平 | 类型过滤、少量替换 | 滑动窗口、相邻去重、前缀和、受控并发 |
| 终止下游能力 | 无 | 无 | 有（`downstream.push` 返回 false 可停） |

## 4.5 有状态操作如何改写并行执行：分段（segmentation）

顺序执行时所有算子串成一条 Sink 链一次跑完；**并行执行遇到有状态操作时必须切段**。`AbstractPipeline.sourceSpliterator(int)`（`AbstractPipeline.java:458-514`）在"并行 且 链上有有状态操作"时（`hasAnyStateful()`，`:427-434`）遍历阶段链，每遇到一个有状态操作就调用 `p.opEvaluateParallelLazy(u, spliterator)`（`:495`）把"到此为止的上游"先并行算出一个**中间 Node**，然后重置 depth、以这个 Node 的新 Spliterator 为源继续处理后续阶段。

```
list.parallelStream().filter(f).sorted().map(m).collect(...)   // sorted 是分水岭
执行切两段：
  段1: filter(f) 并行算完 → 结果收集成一个 Node（有序数组）
  段2: 以 Node 的 Spliterator 为源，map(m) 在其上再并行
```

这解释了三个现象：① 有状态操作越多，并行流越接近"多遍扫描"；② `limit/skip` 后面再并行，尺寸标志会被重算（`:497-501` 按 `spliterator.hasCharacteristics(SIZED)` 注入/清除）；③ javadoc 反复说 parallel + stateful 是性能雷区的原因。

### 本章小结

- 无状态 9 个（filter/map/mapToInt×3/flatMap×4/mapMulti×4/peek/unordered 算一族形状变体），有状态 7 个（distinct/sorted/limit/skip/takeWhile/dropWhile/gather）。
- flatMap 每元素建子流、用完自动 close；每元素产出少时换 mapMulti；需要跨元素状态时上 Gatherer（第八章）。
- 有状态操作 = 流水线里的"段间分水岭"：并行流遇它必切段，性能预算要留给它。

# 五、终端操作全景：数据的"泄洪口"

## 5.1 分类总表

终端操作触发整条流水线，返回非 Stream 的结果。四类：

| 类别 | 操作 | 返回 | 短路？ |
|---|---|---|---|
| 消费 | `forEach`、`forEachOrdered` | void | 否 |
| 归约 | `reduce`×3、`count`、`min`、`max`、`toArray`×2、`toList` | 值/Optional/数组/List | 否 |
| 查找匹配 | `anyMatch`、`allMatch`、`noneMatch`、`findFirst`、`findAny` | boolean/Optional | **是** |
| 收集 | `collect`×2 | 任意容器 | 否 |

"短路"指：**结果一确定就停止从源拉取元素**——终端 Sink 通过 `cancellationRequested()` 通知上游（3.4 节 `copyIntoWithCancel`）。这是无限流（`iterate`/`generate`）能配合 `limit`/`anyMatch` 收敛的机制基础。

【源码证据】短路的开关在两处：`TerminalOp.getOpFlags()` 声明 `IS_SHORT_CIRCUIT`（`MatchOps.java:218-220`）；`copyInto` 见标志即改走 `copyIntoWithCancel`（`AbstractPipeline.java:568-574`）：

```java
final <P_IN> boolean copyIntoWithCancel(Sink<P_IN> wrappedSink, Spliterator<P_IN> spliterator) {
    wrappedSink.begin(spliterator.getExactSizeIfKnown());
    boolean cancelled = p.forEachWithCancel(spliterator, wrappedSink);  // 逐个推+逐个问
    wrappedSink.end();
    return cancelled;
}
// ReferencePipeline.java:145-149：每个元素后都问一句"还继续吗"
final boolean forEachWithCancel(Spliterator<P_OUT> spliterator, Sink<P_OUT> sink) {
    boolean cancelled;
    do { } while (!(cancelled = sink.cancellationRequested()) && spliterator.tryAdvance(sink));
    return cancelled;
}
```

中间的算子链也要"传话"：链式 Sink 的 `cancellationRequested` 默认原样转发下游（`Sink.java:265-267`），`flatMap` 这类会自建子流的操作则把取消信号一路带回子流（`ReferencePipeline.java:299-311`）。

## 5.2 forEach / forEachOrdered

```java
List.of("x", "y").parallelStream().forEach(System.out::println);         // 乱序（并行）
List.of("x", "y").parallelStream().forEachOrdered(System.out::println);  // 恒按相遇顺序
```

- `forEach`：只求"每个都被处理"，并行时**哪个线程先算完谁先输出**。
- `forEachOrdered`：并行时也保证按相遇顺序——代价是各段结果要按序汇合（内部用 `ForEachOps.makeRef(action, true)` 的 ordered 标志，`ReferencePipeline.java:636-638`）。
- 【源码证据】`ReferencePipeline.Head` 对**顺序流**的 forEach 有直达优化：不等终端框架，直接 `sourceStageSpliterator().forEachRemaining(action)`（`ReferencePipeline.java:800-818`）——没有中间 Sink 包装，最快路径。

## 5.3 reduce：三种形态与"结合律契约"

```java
List<Integer> nums = List.of(1, 2, 3, 4, 5);

// ① identity + accumulator：必定有结果
int sum = nums.stream().reduce(0, Integer::sum);                    // 15

// ② 只给 accumulator：结果可能为空 → Optional
Optional<Integer> product = nums.stream().reduce((a, b) -> a * b);  // Optional[120]

// ③ identity + accumulator + combiner：并行拆分合并的完整形态
int sum2 = nums.parallelStream().reduce(0, Integer::sum, Integer::sum); // 15
```

【源码证据】三种形态分别对应 `ReduceOps.makeRef(identity, reducer, combiner)`（`ReduceOps.java:68-94`，一个 `state` 字段在 `accept` 里滚雪球）、`makeRef(operator)`（`:104-144`，用 `empty` 标志避免"假 identity 污染"，`get()` 时才包 Optional）、`makeRef(supplier, accumulator, combiner)`（`:204-234`）。并行时每个叶子任务各滚各的 `state`，`ReduceTask.onCompletion` 里用 `combiner` 合并左右孩子的结果（`ReduceOps.java:964-971`）。

**两条契约**（违反就是 7.7 节的实测车祸）：

1. `accumulator` 必须**结合**（associative）：`(a op b) op c == a op (b op c)`——加法/乘法/字符串拼接满足，减法、`(a+b)*2` 不满足；
2. `identity` 必须是真正的幺元：对任意 t，`id op t == t`——用 `0` 求和可以，用 `-1` 求和就错了。

```java
// 【实测输出】非结合律示例：串行 52，并行 80（StreamDemo 第 6 节）
List.of(1,2,3,4).stream().reduce(0, (a,b)->(a+b)*2, (a,b)->(a+b)*2);          // 52
List.of(1,2,3,4).parallelStream().reduce(0, (a,b)->(a+b)*2, (a,b)->(a+b)*2);  // 80
```

`min`/`max` 就是 reduce 的语法糖（`ReferencePipeline.java:738-746`：`reduce(BinaryOperator.maxBy(comparator))`）。

## 5.4 count / min / max：一个零遍历优化

```java
// 【实测输出】peek 一次都没跑！count = 5
long n = Stream.of(1, 2, 3, 4, 5).peek(x -> System.out.println("peek: " + x)).count();

// filter 之后 SIZED 丢失，必须真遍历
long m = Stream.of(1, 2, 3, 4, 5).filter(x -> x > 2)
        .peek(x -> System.out.println("peek2: " + x)).count();   // 打印 3,4,5 → m = 3
```

【源码证据】`ReduceOps.makeRefCounting`（`ReduceOps.java:246-275`）在顺序和并行路径都先问一句 `helper.exactOutputSizeIfKnown(spliterator)`（`AbstractPipeline.java:529-546`：仅当链上标志为 SIZED 才返回 `spliterator.getExactSizeIfKnown()`，否则 -1）——拿到精确尺寸就直接 return，**整条流水线不执行**。`filter` 声明了 `NOT_SIZED`（3.6 节表），SIZED 一断就没了。这正是 `Stream.java:1305` javadoc 用 `l.stream().peek(System.out::println).count()` 举的同一个例子。

这也是"流的自由度"条款的落地：`Stream.java:82-93` 写明实现有权**省略不影响结果的操作（连副作用都不保证执行）**——除 `forEach`/`forEachOrdered` 外，别把业务逻辑藏在 peek/map 里指望它必跑。

## 5.5 match 与 find：短路的两个主人

```java
List<Integer> xs = List.of(1, 2, 3, 4);
boolean any = xs.stream().anyMatch(x -> x > 2);      // true，见到 3 就停
boolean all = xs.stream().allMatch(x -> x < 3);      // false，见到 3 就停
boolean none = xs.stream().noneMatch(x -> x < 0);    // true，全部看完才敢说
Optional<Integer> first = xs.stream().filter(x -> x > 1).findFirst();  // Optional[2]
```

【源码证据】三个 match 共用一个工厂，只差一个枚举参数（`MatchOps.java:50-68`）：

```java
enum MatchKind {
    ANY(true, true),     // 谓词一命中→停，短路结果 true
    ALL(false, false),   // 谓词一失手→停，短路结果 false
    NONE(true, false);   // 谓词一命中→停，短路结果 false
}
```

`MatchSink.accept`（`:88-94`）：`if (!stop && predicate.test(t) == matchKind.stopOnPredicateMatches) { stop = true; ... }`，同时 `cancellationRequested()` 返回 `stop`（`:265-267`）——5.1 节的拉问机制由它驱动。`findFirst`/`findAny` 是 `FindOps.makeRef(mustFindFirst)`（`ReferencePipeline.java:682-689`）：findFirst 必须"相遇顺序的第一个"，findAny 只求"任意一个"（并行下倾向返回先算出的分块结果）。

无限流的正确姿势【实测输出】：

```java
Stream.iterate(1, i -> i + 1)
      .peek(i -> System.out.println("tested: " + i))
      .anyMatch(i -> i > 3);          // 打印 1,2,3,4 后立即停止 → true

Stream.iterate(0, i -> i + 2).limit(5).toList();   // [0, 2, 4, 6, 8]
```

**反过来，无限流 + 无短路的终端操作 = 卡死**：`Stream.generate(...).sorted()`、`Stream.iterate(...).collect(...)`、`Stream.iterate(...).count()` 都永不返回——`sorted`/`collect`/`count` 必须见完所有元素（`count` 还因源非 SIZED 退化为真遍历）。

## 5.6 toArray / toList

```java
String[] arr = names.stream().toArray(String[]::new);   // 精确类型数组
Object[] raw = names.stream().toArray();
List<String> list1 = names.stream().toList();                            // JDK 16+
List<String> list2 = names.stream().collect(Collectors.toList());        // JDK 8+
List<String> list3 = names.stream().collect(Collectors.toUnmodifiableList());
```

| | `toList()` | `collect(Collectors.toList())` | `collect(Collectors.toUnmodifiableList())` |
|---|---|---|---|
| 可变性 | **不可变**（mutator 抛 UnsupportedOperationException） | 可变 ArrayList | **不可变** |
| null 元素 | **允许**（【实测输出】`Stream.of("a", null).toList()` 正常含 null） | 允许 | **禁止**（累加即抛 NPE） |
| JDK | 16+ | 8+ | 10+ |

【源码证据】`toList()` 默认实现 `Collections.unmodifiableList(new ArrayList<>(Arrays.asList(toArray())))`（`Stream.java:1248-1249`，javadoc `:1219-1226` 明确"unmodifiable、无实现类型保证、可能是 value-based"），生产路径在 `ReferencePipeline.java:662-664` 走 `SharedSecrets...listFromTrustedArrayNullsAllowed`（方法名就是"允许 null"）。**【实测输出】三者差异如上表**（`NullList.java`）。需要可变 List 用 `collect(Collectors.toList())` 或 `collect(toCollection(ArrayList::new))`。

## 5.7 collect（三参形态）：可变归约

```java
// 三参 collect：容器怎么建、元素怎么进、两个容器怎么合
List<String> asList = names.stream()
        .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
```

它是 reduce 的"可变容器版"：`reduce` 每步都新建结果对象（不可变归约），`collect` 往同一个容器里 mutate（`ReduceOps.makeRef(supplier, accumulator, combiner)`，`ReduceOps.java:204-234`）。并行时每个任务一个容器、最后 combiner 合并。**这套三函数结构与 `Collector` 接口一一对应**——单参 `collect(Collector)` 就是它的参数化封装，下一章专门展开。

### 本章小结

- 终端操作四类：消费（forEach 系）、归约（reduce 系 + count/min/max/toArray/toList）、短路（match/find）、收集（collect）。
- 短路的实现 = 终端 Sink 的 `cancellationRequested()` + `copyIntoWithCancel` 的逐元素拉问；match 三兄弟只是一个枚举的三种参数化。
- reduce 的结合律/幺元契约是并行正确性的全部；`count()` 在 SIZED 链上零遍历。
- `toList()` 不可变且禁 null，与 `Collectors.toList()` 不是一回事。

# 六、collect 与 Collector：把"如何收集"抽成一个参数

## 6.1 Collector：四个函数 + 三个特性

**白话**：reduce 回答"怎么把元素**滚成一个值**"，collect 回答"怎么把元素**装进一个容器**（最后可再加工）"。`Collector` 接口（`Collector.java:201-245`）把"装"这件事拆成四个函数：

```java
public interface Collector<T, A, R> {
    Supplier<A> supplier();        // 开一个中间容器 A（并行时每任务一个）
    BiConsumer<A, T> accumulator(); // 把一个元素塞进容器
    BinaryOperator<A> combiner();   // 并行时合并两个中间容器
    Function<A, R> finisher();      // 收尾：A → 最终结果 R（多数是恒等）
    Set<Characteristics> characteristics();  // 特性声明
}
```

三个特性（`Collector.java:315-330`）：

| 特性 | 含义 | 框架用它干什么 |
|---|---|---|
| `CONCURRENT` | 中间容器线程安全，可多线程直接塞 | 并行时**共用一个容器**，跳过分段合并（见 6.2） |
| `UNORDERED` | 结果不依赖相遇顺序 | 并行时无需按序合并 |
| `IDENTITY_FINISH` | finisher 是恒等（A 就是 R） | 跳过 finisher，直接强转（`ReferencePipeline.java:725-727`） |

大多数 `Collector` 是 IDENTITY_FINISH（如 `toList`：A=ArrayList、R=List）。而 `joining()` 是"A≠R"的例子：A=StringBuilder，R=String。

## 6.2 collect 怎么执行一个 Collector

【源码证据】单参 `collect(Collector)`（`ReferencePipeline.java:713-728`）有一个容易被忽略的快路径：

```java
public <R, A> R collect(Collector<? super P_OUT, A, R> collector) {
    A container;
    if (isParallel()
            && collector.characteristics().contains(CONCURRENT)
            && (!isOrdered() || collector.characteristics().contains(UNORDERED))) {
        // 快路径：并行 + 容器线程安全 + (无序 或 收集器无序) → 全部线程直接塞同一个容器
        container = collector.supplier().get();
        BiConsumer<A, ? super P_OUT> accumulator = collector.accumulator();
        forEach(u -> accumulator.accept(container, u));
    }
    else {
        // 慢路径：降级为三参 collect（每任务一个容器 + combiner 合并）
        container = evaluate(ReduceOps.makeRef(collector));
    }
    return collector.characteristics().contains(IDENTITY_FINISH)
           ? (R) container
           : collector.finisher().apply(container);
}
```

慢路径里 `ReduceOps.makeRef(Collector)`（`ReduceOps.java:155-190`）就是把四函数"缝"回 5.7 节的三参 ReducingSink。这也解释了 `groupingByConcurrent` 存在的理由：`groupingBy` 的中间容器是普通 `HashMap`（非 CONCURRENT），并行只能走"分容器+合并"；换 `groupingByConcurrent` 用 `ConcurrentHashMap` + CONCURRENT 特性，并行时所有线程无锁直塞（`Collectors.java:1170-1264`）。

## 6.3 Collectors 预置收集器全景（按用途分组）

【源码证据】`Collectors.java`（2038 行，44 个工厂）。按"造什么"分组速查：

| 组 | 方法（行号） | 产出 |
|---|---|---|
| 装容器 | `toList` :243、`toUnmodifiableList` :261、`toSet` :290、`toUnmodifiableSet` :319、`toCollection(Supplier)` :226 | List/Set/任意 Collection |
| 装映射 | `toMap(k,v)` :1442、`toMap(k,v,merge)` :1546、`toUnmodifiableMap` :1701-1802 | Map（**键冲突不给 merge 会抛 IllegalStateException**） |
| 字符串 | `joining()` :340、`joining(delim)` :358、`joining(delim, prefix, suffix)` :375 | String |
| 计数/最值 | `counting()` :593、`minBy(cmp)` :612、`maxBy(cmp)` :631 | Long/Optional |
| 求和均值 | `summingInt/Long/Double` :645/:663/:688、`averagingInt/Long/Double` :756/:775/:807 | 数值 |
| 统计全家桶 | `summarizingInt/Long/Double` :1825/:1848/:1871 | IntSummaryStatistics 等 |
| 归约 | `reducing(identity, op)` :849、`reducing(op)` :892、`reducing(identity, mapper, op)` :956 | T |
| 分组 | `groupingBy(classifier)` :1004、+downstream :1053、+mapFactory :1106、`groupingByConcurrent` :1170-1264 | Map<K, List<T>> / 自定 |
| 分区 | `partitioningBy(pred)` :1328、+downstream :1362 | Map<Boolean, T>（**键恒为 true/false 两个**） |
| 组合 | `mapping` :432、`filtering` :527、`flatMapping` :477、`collectingAndThen` :560、`teeing` :1914（深入见 6.4.1） | 下游组合 |

基础用法（都已在 demo 中实测）：

```java
record Movie(String title, double rating) {}
List<Movie> movies = ...;  // 6 部电影，评分 6.8~9.4

// 分组 + 下游：按评分档分组，组内只留片名
Map<String, List<String>> byGrade = movies.stream().collect(Collectors.groupingBy(
        m -> m.rating() >= 8.0 ? "good" : "avg", TreeMap::new,
        Collectors.mapping(Movie::title, Collectors.toList())));
// {avg=[C, E], good=[A, B, D, F]}【实测输出】

// 分区：二值分组（配额统计场景比 groupingBy 高效）
Map<Boolean, Long> part = movies.stream()
        .collect(Collectors.partitioningBy(m -> m.rating() >= 8.0, Collectors.counting()));
// {false=2, true=4}【实测输出】

// joining
movies.stream().map(Movie::title).collect(Collectors.joining(", ", "[", "]"));
// [A, B, C, D, E, F]【实测输出】
```

`toMap` 的两个高频坑：① 不给 merge 函数遇重复键直接炸：`IllegalStateException: Duplicate key`；② `groupingBy` 的 classifier 返回 null 直接 NPE（HashMap 允许 null 键但分组逻辑不允许）。规避姿势见第九章。

## 6.4 下游组合器：Collector 的"嵌套语法"

Collectors 的函数式威力来自**下游嵌套**——上游收集器把处理完的元素转交给下游继续收集，可以无限套：

```java
// mapping：把"变换"塞进收集阶段（groupingBy 的下游不能直接 map，要用 mapping 包）
Map<String, List<String>> r1 = movies.stream()
        .collect(groupingBy(Movie::grade, mapping(Movie::title, toList())));

// filtering（JDK 9）：在收集阶段再过滤（不影响分组键的统计口径时用它，而不是先 filter）
Map<String, List<Movie>> r2 = movies.stream()
        .collect(groupingBy(Movie::grade, filtering(m -> m.rating() > 7, toList())));

// flatMapping（JDK 9）：组内摊平
// collectingAndThen：收集完再加工一步（最常见：定格式）
Map<String, List<Movie>> r3 = movies.stream()
        .collect(groupingBy(Movie::grade,
                 collectingAndThen(toList(), Collections::unmodifiableList)));

// teeing（JDK 12）：一条流劈两半各收各的，最后合体——只遍历一遍（机制展开见 6.4.1）
record Stats(long count, double avg) {}
Stats stats = movies.stream().collect(Collectors.teeing(
        counting(), averagingDouble(Movie::rating), Stats::new));
// Stats[count=6, avg=8.299999999999999]【实测输出】（double 均值的原样精度）
```

### 6.4.1 teeing 深入：一次遍历，多路收集

**白话**：流是一次性的（3.7 节），一条流水线只能挂**一个**终端操作。可"一趟遍历同时要两个结果"是常见需求——比如既要条数又要均值。土办法两种都有毛病：① 同一条链写两遍、遍历两遍；② 往某个 lambda 里塞共享可变状态攒第二个结果——破坏无状态契约，并行直接错（与 7.7 翻车 1 同源）。`Collectors.teeing`（`Collectors.java:1914`，`@since 12`）是官方给的第三种答案：**把两个 Collector 打包成一个 Collector**，同一个元素在同一趟里喂给两路累加器，最后把两个结果 merge 成一个。名字来自 Unix 的 `tee` 管件——T 形三通，一股水流分成两股。

【源码证据】工厂方法本身很短，全部机制在私有的 `teeing0`（`Collectors.java:1926-1985`）里，核心是一个叫 `PairBox` 的"双容器"：

```java
class PairBox {                                        // Collectors.java:1962-1980
    A1 left = c1Supplier.get();                        // 操作①的中间容器
    A2 right = c2Supplier.get();                       // 操作②的中间容器

    void add(T t) {
        c1Accumulator.accept(left, t);                 // 同一个元素，喂给第一路
        c2Accumulator.accept(right, t);                // 喂给第二路 —— 一次遍历、双路收集
    }

    PairBox combine(PairBox other) {                   // 并行时：两路各自合并
        left = c1Combiner.apply(left, other.left);
        right = c2Combiner.apply(right, other.right);
        return this;
    }

    R get() {                                          // 收尾：两路各自 finisher，再 merge
        R1 r1 = c1Finisher.apply(left);
        R2 r2 = c2Finisher.apply(right);
        return merger.apply(r1, r2);
    }
}
return new CollectorImpl<>(PairBox::new, PairBox::add, PairBox::combine, PairBox::get, characteristics);
```

`teeing` 本质上是把"用户要自己写的 PairBox"模板化收进了 JDK——你只提供两个下游收集器和一个 `BiFunction` 合体函数。并行语义自动成立：每个任务一个 `PairBox`，`combine` 沿任务树向上合并两路（与 5.3 节 `ReduceTask.onCompletion` 的汇合方式同构）。

```java
record Stats(long count, double avg) {}
Stats stats = movies.stream().collect(Collectors.teeing(
        Collectors.counting(),                        // 操作①：计数
        Collectors.averagingDouble(Movie::rating),    // 操作②：求均值
        Stats::new));                                 // 合体
// Stats[count=6, avg=8.299999999999999]【实测输出，StreamDemo 第 8 节】
```

**"多"字辨析**（四个容易混淆的场景，方向各不相同）：

| 需求 | 答案 | 说明 |
|---|---|---|
| 一次遍历做**多个收集操作** | `Collectors.teeing`（两个下游；更多路就多层 teeing 嵌套） | 本小节 |
| 数值统计的特化版 | `IntStream.summaryStatistics()` / `Collectors.summarizingXxx`（`Collectors.java:1825-1871`） | 一次遍历同时得到 count/sum/min/average/max【实测输出：`IntSummaryStatistics{count=5, sum=14, min=1, average=2.800000, max=5}`，StreamDemo 第 14 节】；但只限统计这一种操作，teeing 的两个下游是任意的 |
| 一个元素**产出多个元素** | `mapMulti` / `flatMap` / `gather(Gatherer)` | 一对多变换，方向与 teeing 相反（4.4 节对比表） |
| 一条流水线挂**多个终端操作** | 做不到（一次性消费，3.7 节） | 惯用解法：重新 `list.stream()` 开流；或把流水线存成 `Supplier<Stream<T>>`，每次 `get()` 得到新流 |

## 6.5 自定义 Collector 实例：TopN

**白话**：内置收集器写不出的"流式 Top-N"（不用全排序、一遍扫完只留前 3 名），用 `Collector.of` 四函数即可——供应商给一个**容量受限的优先队列**，累加器"插入后超员弹走最差"，finisher 把队列倒序输出：

```java
Comparator<Movie> byRating = Comparator.comparing(Movie::rating);
Collector<Movie, PriorityQueue<Movie>, List<Movie>> topN = Collector.of(
        () -> new PriorityQueue<>(byRating),                  // supplier：小顶堆
        (pq, m) -> { pq.add(m); if (pq.size() > 3) pq.poll(); }, // accumulator：进堆+超员淘汰
        (left, right) -> {                                    // combiner：并行合并
            right.forEach(m -> { left.add(m); if (left.size() > 3) left.poll(); });
            return left;
        },
        pq -> {                                               // finisher：堆 → 有序 List
            List<Movie> out = new ArrayList<>(pq);
            out.sort(byRating.reversed());
            return out;
        });

List<Movie> top3 = movies.stream().collect(topN);
// [D(9.4), B(9.1), A(8.5)]【实测输出，StreamDemo 第 7 节】
```

这个例子覆盖了自定义收集器的全部要点：**中间容器选对数据结构（堆）→ accumulator 只做增量 → combiner 支撑并行 → finisher 负责定型**。对比"全量 sorted().limit(3)"：内存 O(N) vs O(3)，一遍 vs 两遍。

## 6.6 groupingBy 三级嵌套配方

```java
// 一级：分类 → List
Map<String, List<Order>> byCity = orders.collect(groupingBy(Order::city));

// 二级：分类 → 再分类
Map<String, Map<String, List<Order>>> byCityThenCat =
        orders.collect(groupingBy(Order::city, groupingBy(Order::category)));

// 二级 + 聚合：分类 → 计数
Map<String, Long> countByCity = orders.collect(groupingBy(Order::city, counting()));

// 二级 + 变换 + 聚合：城市 → 类目 → 金额和
Map<String, Map<String, Double>> amt =
        orders.collect(groupingBy(Order::city,
                       groupingBy(Order::category,
                                  summingDouble(Order::amount))));
// 三级同理：groupingBy(A, groupingBy(B, groupingBy(C, downstream)))
```

记忆法：**groupingBy 的第二个参数永远是"另一个 Collector"**，要什么就往下塞什么——这就是"下游组合器"的复利。

### 本章小结

- Collector = supplier/accumulator/combiner/finisher 四函数 + CONCURRENT/UNORDERED/IDENTITY_FINISH 三特性；`collect(Collector)` 在并行 + CONCURRENT + 无序时走"共用容器"快路径。
- Collectors 44 个工厂 = 一张"下游组合"语法表；groupingBy 的第二参永远还是 Collector，套几层就统计到几级；`teeing` 把"一趟遍历多路收集"也收编进了这套语法（6.4.1）。
- 自定义收集器的标准配方：`Collector.of(supplier, accumulator, combiner, finisher)`，TopN 是最好的练手题。

# 七、并行流：ForkJoin 之上的"免费"并行

## 7.1 白话：parallel() 只改一个布尔值

【源码证据】`parallel()` 的实现只有一行（`AbstractPipeline.java:342-345`）：

```java
public final S parallel() {
    sourceStage.parallel = true;   // 只改链头的标志
    return (S) this;
}
```

真正的分派发生在终端操作 `evaluate()`（`AbstractPipeline.java:263-265`）：`isParallel()` 为真就走 `terminalOp.evaluateParallel(...)`。并行执行 = **Spliterator 递归对半劈 + 每个叶子块独立跑完整条 Sink 链 + 结果按任务树合并**，全部站在 `ForkJoinPool.commonPool()` 上（`Java并发.md` 有 ForkJoin 详解）。

```
                    根任务(整条流)
                   /             \
          左半 Spliterator     右半 Spliterator        ← trySplit 对半劈
             /      \            /      \
          叶子       叶子       叶子      叶子          ← estimateSize ≤ 阈值就停劈
       doLeaf()   doLeaf()   doLeaf()  doLeaf()       ← 每叶子独立执行 Sink 链
           \         /            \         /
             combiner 合并       combiner 合并        ← onCompletion 向上汇合
```

## 7.2 AbstractTask.compute()：拆分循环源码走读

【源码证据】所有并行终端任务共享 `AbstractTask`（`AbstractTask.java:88-363`，基于 `CountedCompleter`）。拆分循环在 `compute()`（`:302-329`）：

```java
public void compute() {
    Spliterator<P_IN> rs = spliterator, ls;                 // 右半、左半
    long sizeEstimate = rs.estimateSize();
    long sizeThreshold = getTargetSize(sizeEstimate);       // 叶子块的目标尺寸
    boolean forkRight = false;
    K task = (K) this;
    while (sizeEstimate > sizeThreshold && (ls = rs.trySplit()) != null) {
        task.leftChild  = leftChild = task.makeChild(ls);   // 左孩子拿劈出去的前半
        task.rightChild = rightChild = task.makeChild(rs);  // 右孩子留下半
        task.setPendingCount(1);
        if (forkRight) { task = leftChild;  taskToFork = rightChild; forkRight = false; }
        else           { task = rightChild; taskToFork = leftChild;  forkRight = true;  }
        taskToFork.fork();                                  // 一半丢进队列，一半自己继续
        sizeEstimate = rs.estimateSize();
    }
    task.setLocalResult(task.doLeaf());                     // 劈到头：叶子任务跑真正的计算
    task.tryComplete();                                     // 完成计数-1，触发父级 onCompletion
}
```

三个设计点：

1. **目标叶子尺寸**（`getTargetSize`，`:203-207`；`suggestTargetSize`，`:194-197`）：`estimateSize / (并行度 × 4)`——**故意过量切分**到每核约 4 个任务（`AbstractTask.java:92`：`LEAF_TARGET = ForkJoinPool.getCommonPoolParallelism() << 2`，javadoc `:156-159` 说明是为了负载均衡：块不均或某核被占时，别的 worker 能"偷"到任务）。
2. **交替 fork 左右**（`:313-324`）：防止某些 Spliterator 劈分总是偏一边导致 work-stealing 队列倾斜。
3. **叶子做的是"完整流水线"**：`doLeaf()` 对自己那一块 Spliterator 跑 `helper.wrapAndCopyInto(op.makeSink(), spliterator)`（如 `ReduceTask.doLeaf`，`ReduceOps.java:959-961`）——即 3.4 节那条 Sink 链在每个叶子上原样重演。

## 7.3 短路的并行版：AbstractShortCircuitTask

`anyMatch` 等短路终端并行执行用 `MatchTask extends AbstractShortCircuitTask`（`MatchOps.java:278-316`）：叶子算出与 `matchKind.shortCircuitResult` 相符的结论就调 `shortCircuit(b)`（`:305-309`），基类用 `volatile boolean canceled`（`AbstractShortCircuitTask.java:58`）广播取消——其余叶子看到取消标志就提前收工。并行 `findFirst`/`findAny` 同理（`FindOps.java:295` 的 `FindTask`）。

## 7.4 Spliterator 质量决定并行收益

回到 2.3 节：**并行流的加速比上限 = trySplit 能多快、多均匀地把数据劈开**。

| 数据源 | trySplit 策略 | 并行友好度 |
|---|---|---|
| 数组 / ArrayList | 下标精确二分，O(1) | ★★★★★ |
| HashMap/HashSet | 按桶区间劈（不精确但便宜） | ★★★★ |
| ConcurrentHashMap | 按桶区间劈 | ★★★★ |
| TreeMap/TreeSet | 中序二分 | ★★★ |
| LinkedList | **要遍历一半元素才能劈**（O(n)） | ★ |
| `iterate`/`generate` | 无法劈（返回 null） | ✗ 并行无收益 |

所以"给 LinkedList 套 parallel()"通常白忙活：数据先得被逐个遍历才能分块，并行收益被顺序遍历吃光。实战姿势：**能换 ArrayList/数组做源就换**。

## 7.5 顺序性代价与"无序提速"

相遇顺序（ORDERED）是并行流最贵的东西。实测对比【实测输出，`StreamDemo` 第 9 节，16 核机器 commonPool 并行度 15】：

```java
List<Integer> big = 0..99;
big.parallelStream().limit(12).forEach(x -> print(x));         // 3 1 8 6 9 10 7 5 4 0 2 11（乱序）
big.parallelStream().limit(12).forEachOrdered(x -> print(x));  // 0 1 2 3 ... 11（恒序）
Integer any = big.parallelStream().filter(x -> x > 10).findAny().get();   // 65（不是 11！）
Integer fst = big.parallelStream().filter(x -> x > 10).findFirst().get(); // 11
```

- `findAny` 返回 65：并行下"任意一个"= **某个 worker 恰好先算出**的那个，非确定性——按语义选操作，别顺手写 findFirst。
- 有序流上 `limit/skip/distinct/takeWhile/sorted` 都要付"按序合并/前缀协调"的代价；**不在乎顺序就先 `.unordered()`**（`Stream.java:562-571`、`:655-666` 等 javadoc 反复点名这是官方提速手段）。

## 7.6 线程模型：commonPool 与自定义 ForkJoinPool

并行流的所有任务跑在 `ForkJoinPool.commonPool()`（JVM 全局共享，并行度 = CPU 核数 - 1；实测本机 15）。两个推论：

1. **阻塞 commonPool 是反模式**：并行流里睡 IO，会饿死 JVM 里其他并行流；
2. **想隔离/限流就提交到自己的池**——把整个流水线作为任务 submit 进自定义池：

```java
ForkJoinPool pool = new ForkJoinPool(4);   // 只用 4 个线程
try {
    int sum = pool.submit(() ->
            IntStream.rangeClosed(1, 1000).parallel().sum()).join();
    // 【实测输出】sum = 500500, 实际参与线程数 = 4（StreamDemo 第 10 节）
} finally {
    pool.close();
}
```

原理：ForkJoin 任务的 fork 默认提交到**调用者所在池**，所以嵌在 `pool.submit(...)` 里的 `parallel()` 流自然长在这个池里。注意这不是官方 API 保证，而是 ForkJoin 的既有行为，隔离效果以"池粒度"为准。

## 7.7 陷阱实测区（三个翻车现场）

**翻车 1：并行 forEach 里往非线程安全容器 add —— 静默丢数据**

```java
List<Integer> target = new ArrayList<>();
big.parallelStream().forEach(target::add);
// 【实测输出】三轮分别得到 8837 / 7539 / 7763 个元素（应为 10000）——ArrayList 并发写撕裂内部数组
```

正确姿势：`collect(Collectors.toList())`（每任务独立容器 + 合并，实测 10000）或用并发容器。**这不是 ArrayList 的 bug，是"算子必须无干扰/无状态"契约被违反**（`Stream.java:111-121`）。

**翻车 2：非结合律 reduce —— 串并行结果不同**：见 5.3 节实测（52 vs 80）。

**翻车 3：lambda 里共享可变状态**

```java
long[] sum = {0};                      // 反例
list.parallelStream().forEach(x -> sum[0] += x);   // 竞态 + 装箱，结果错
long sum2 = list.parallelStream().mapToLong(x -> x).sum();  // 正解：归约收回框架
```

## 7.8 用还是不用：决策表

| 场景 | 建议 |
|---|---|
| 大数据量（> 万级）+ CPU 密集 + 源可高效劈分（数组/ArrayList/HashMap） | ✅ 值得并行 |
| 数据量小（< 千级） | ❌ 任务拆分/调度开销 > 收益 |
| IO 阻塞型 lambda | ❌ 别阻塞 commonPool；要隔离就自定义池或换异步方案 |
| 源是 LinkedList / 无限流 | ❌ 劈不动 |
| 链上有 sorted/limit 且必须有序 | ⚠️ 先实测，常比串行慢；能 `unordered()` 再说 |
| lambda 有共享状态 / 非结合归约 | ❌ 先修契约 |

一个工程事实：**大多数业务流水线元素是几百个 POJO，并行流的实际收益接近零**。先把串行流写对，Profile 证明热点在这条链上，再考虑 parallel——它应该是性能优化的最后一招，不是炫技的第一招。

### 本章小结

- `parallel()` = 改链头一个布尔值；并行 = trySplit 递归劈块 + 叶子独立跑 Sink 链 + onCompletion 合并，站在 commonPool 上。
- 过量切分（每核 4 任务）+ 交替 fork 是框架的负载均衡设计；短路靠 AbstractShortCircuitTask 的 canceled 广播。
- 并行的正确性全部押在三条契约上：无状态、无干扰、结合律——翻车 1/2/3 分别对应违反它们。
- 源的劈分质量（数组 > HashMap > LinkedList）和顺序性要求（unordered 提速）决定收益上限。

# 八、Gatherer：JDK 22+ 的中间操作扩展点

## 8.1 为什么需要它：固定算子的表达力缺口

Stream 的中间操作是一个**封闭集合**：filter/map/flatMap/sorted……（第四章）。想加一个新的"带状态的中间变换"（滑动窗口、相邻去重、前缀和），只能：

- 塞进某个 lambda 里偷偷攒状态——违反无状态契约，并行直接错；
- 先 `collect` 成中间集合再开新流——两遍扫描、中断流水线；
- 等官方加方法——每个算子都是一次 API 评审。

JEP 461（JDK 22 预览）→ JEP 485（JDK 24 定稿）给出的答案是：**把"中间操作"本身开放成 SPI**。`Stream.gather(Gatherer)`（`Stream.java:1099`，`@since 24`）与 `collect(Collector)` 形成对称——Collector 参数化了终端操作，Gatherer 参数化了中间操作。

## 8.2 四函数模型

【源码证据】`Gatherer` 接口（`Gatherer.java:210-268`）与 Collector 四函数惊人地对称：

| Gatherer | Collector | 职责 |
|---|---|---|
| `initializer()` :210 | `supplier()` | 开一个（可变的）状态对象 |
| `integrator()` :223 | `accumulator()` | **把元素整合进状态，并向下游推 0~N 个产出** |
| `combiner()` :235 | `combiner()` | 并行时合并两个状态 |
| `finisher()` :250 | `finisher()` | 流结束后的收尾推送 |

核心是 `Integrator`（`Gatherer.java:527-540`）：

```java
boolean integrate(A state, T element, Downstream<? super R> downstream);
// 返回 true = 继续接收元素；返回 false = 请停止喂我（短路！）
// downstream.push(R) ：向下游推一个产出，返回 false = 下游不要了（也该停）
```

`Downstream.push` 返回布尔值意味着 **Gatherer 的每个产出都可被下游反压**——这是 `map`/`mapMulti`（void）没有的能力。`Gatherer` javadoc 给出了等价的伪代码（`Gatherer.java:85-100` 附近 snippet）：初始化状态 → 逐元素 integrate → 收尾 finisher——就是一个**可组合的、可并行的、带状态的流式循环**。

工厂方法：无状态 `Gatherer.of(integrator)`（`:419`）；带状态 `ofSequential(initializer, integrator[, finisher])`（`:329-397`）；可并行 `of(initializer, integrator, combiner, finisher)`（`:465`）。组合用 `andThen`（`:268`）。

## 8.3 内置 Gatherers 五件套

`Gatherers`（`Gatherers.java`）提供五个高频实现，全部实测【`StreamDemo` 第 11 节】：

```java
// ① windowFixed：定长窗口（不够长的尾巴保留）
Stream.iterate(1, i -> i + 1).limit(7).gather(Gatherers.windowFixed(3)).toList();
// [[1, 2, 3], [4, 5, 6], [7]]【实测输出】

// ② windowSliding：滑动窗口（每次滑一格）
List.of(1, 2, 3, 4, 5).stream().gather(Gatherers.windowSliding(3)).toList();
// [[1, 2, 3], [2, 3, 4], [3, 4, 5]]【实测输出】

// ③ scan：前缀扫描（每步的累积值都产出）
Stream.of(1, 2, 3, 4, 5).gather(Gatherers.scan(() -> 0, Integer::sum)).toList();
// [1, 3, 6, 10, 15]【实测输出】

// ④ fold：折叠（只产出最终一个值——"reduce 的中间操作版"）
Stream.of(1, 2, 3, 4, 5).gather(Gatherers.fold(() -> 0, Integer::sum)).toList();
// [15]【实测输出】

// ⑤ mapConcurrent：受控并发映射（虚拟线程执行 + 按相遇顺序输出）
Stream.of(3, 1, 2)
      .gather(Gatherers.<Integer, Integer>mapConcurrent(8, x -> { sleep(300 - x*100); return x*10; }))
      .toList();
// [30, 10, 20]【实测输出】——2×10 最先算完，但输出仍是声明顺序
```

注意 `mapConcurrent` 的语义组合：**乱序执行 + 有序交付**，并发度由 maxConcurrency 限死。JDK 21+ 它默认用虚拟线程跑 mapper，适合"每元素一次远程调用"这类 IO 并发场景（与 [Java并发.md](Java并发.md) 的 StructuredTaskScope 思想同源）。

## 8.4 自定义 Gatherer：相邻去重

`distinct` 是全量去重（要攒 HashSet），"只压相邻重复"是个带一状态的一对多变换——恰好是 Gatherer 的甜点区：

```java
Gatherer<Integer, ?, Integer> collapseDup = Gatherer.ofSequential(
        () -> new Object() { Integer prev; },        // initializer：记上一个元素
        (state, element, downstream) -> {            // integrator
            if (!Objects.equals(state.prev, element)) downstream.push(element);
            state.prev = element;
            return true;                              // true = 继续喂我
        });

List.of(1, 1, 2, 2, 2, 3, 1, 1).stream().gather(collapseDup).toList();
// [1, 2, 3, 1]【实测输出】——注意末尾的 1 保留了：相邻去重 ≠ 全量去重
```

组合：`andThen` 把两个 Gatherer 熔成一个【实测输出】：

```java
Gatherer<Integer, ?, Integer> plus1 = Gatherer.of((u, e, ds) -> ds.push(e + 1));
List.of(1, 2, 2, 3, 3, 3).stream().gather(plus1.andThen(collapseDup)).toList();
// [2, 3, 4]（先 +1 得 2,3,3,4,4,4，再压相邻）
```

【源码证据】连续 `gather` 会在运行时**融合**：`GathererOp.of` 检测到上游就是 GathererOp 时，直接把两个 gatherer `andThen` 成一个节点（`GathererOp.java:46-58`），不再多一层包装。GathererOp 本身是 `StatefulOp`（`opIsStateful()` 返回 true，`:251`），并行执行时按 4.5 节的分段机制跑。

## 8.5 Gatherer vs Collector vs 内置算子

| | 中间操作（map/filter…） | `gather(Gatherer)` | `collect(Collector)` |
|---|---|---|---|
| 位置 | 链中间，封闭集合 | 链中间，**开放扩展点** | 链尾 |
| 状态 | map 无状态；sorted 等内置状态 | 自定义状态对象 | 自定义容器 |
| 产出 | 每元素 0~N 个（mapMulti） | 每元素 0~N 个 + 收尾推送 | 一个最终容器 |
| 短路/反压 | limit/takeWhile 等内置 | integrator 返回 false / push 返回 false | 无 |
| 并行 | 框架内建 | 提供 combiner 才可并行 | 提供 combiner 才可并行 |

选型：**有内置算子就用内置**（更优化）；一对多带状态、且现有算子表达不了，才写 Gatherer。Gatherer 是"最后一个手段"，也是"最通用的手段"。

### 本章小结

- Gatherer（JEP 461/473/485，24 定稿）把中间操作开放成四函数扩展点，与 Collector 在终端侧的参数化完全对称。
- `Integrator.integrate(state, element, downstream)` 的布尔返回值 + `Downstream.push` 的布尔返回值 = 自定义算子第一次拥有了短路和反压能力。
- 内置五件套 windowFixed/windowSliding/scan/fold/mapConcurrent 覆盖窗口、累积、受控并发三大类需求；自定义用 `ofSequential`/`of`，组合用 `andThen`（连续 gather 自动融合）。

# 九、陷阱清单与最佳实践

## 9.1 陷阱清单（现象 → 根因 → 正确姿势）

| # | 现象 | 根因（源码锚点） | 正确姿势 |
|---|---|---|---|
| 1 | `IllegalStateException: stream has already been operated upon or closed` | 流对象一次性（`AbstractPipeline.java:135/:200/:259`） | 重新 `list.stream()`；把"流水线模板"存成 `Supplier<Stream<T>>` |
| 2 | peek/map 里的日志一次都没打印 | `count()` 的 SIZED 零遍历优化（`ReduceOps.java:253-259`） | 副作用只放 `forEach`/`forEachOrdered`；peek 仅调试 |
| 3 | 流水线"没执行"就往下走了 | 惰性：没终端操作=零执行（`AbstractPipeline.java:56-65`） | 别把流的构造和执行混在一行；没终端操作=什么都没发生 |
| 4 | 并行 forEach 收集结果丢数据 | ArrayList 非线程安全（7.7 实测 8837/10000） | `collect(...)` 或并发容器；永远别在算子里写共享可变状态 |
| 5 | 并行 reduce 结果和串行不一样 | accumulator 非结合律（7.7 实测 52 vs 80） | 归约三件套必须满足结合律+幺元；不确定就串行 |
| 6 | `toMap` 抛 `Duplicate key` | 无 merge 函数（`Collectors.java:1442`） | `toMap(k, v, (a, b) -> b)` 显式定策略 |
| 7 | `groupingBy` 抛 NPE | classifier 返回 null | 分类键判空/给默认值 |
| 8 | `Collectors.toUnmodifiableList()` 抛 NPE | 不可变收集器拒绝 null 累加 | 含 null 用 `toList()`（16+，实测允许）或 `Collectors.toList()` |
| 9 | `sorted()` 在无限流上卡死 | 见完所有元素才能吐第一个 | 无限流只能配 `limit(n)`/`takeWhile` 在排序**前**截断 |
| 10 | `collect(Collectors.joining())` 卡死/内存爆 | 同上，joining 也要见完所有元素 | 同上 |
| 11 | `Files.lines` 未关 → 句柄泄漏 | IO 流必须 close（`Stream.java:137-145`） | try-with-resources 包住 `Files.lines(...)` |
| 12 | 大量 `Stream<Integer>` 数值计算慢 | 装箱（2.4 实测慢 ~20%） | `mapToInt`/`IntStream` |
| 13 | 并行流反而更慢 | 小数据量/劈分差的源/有序 limit|sorted | 按 7.8 决策表；`unordered()` 试水 |
| 14 | 并行流里 `ThreadLocal` 读到别的线程的值 | lambda 在 worker 线程执行 | 并行流别依赖 ThreadLocal；或自行传递上下文 |
| 15 | flatMap 里返回了共享的打开资源 | 子流被自动 close（`ReferencePipeline.java:288-295`） | mapper 每次新建子流/资源，不要返回缓存的流 |

## 9.2 最佳实践清单

1. **先串行、后并行**：并行流是 Profile 出来的，不是设计出来的（7.8）。
2. **一行一条流水线语义**：`filter` → `map` → `collect` 垂直排，阅读顺序=执行顺序（3.5）。
3. **lambda 保持"小而无状态"**：不修改外部状态、不依赖执行时机，并行和惰性才不会反噬。
4. **收集复杂就用下游组合器**，不要为每种形状发明新变量（6.6）。
5. **数值计算进 `IntStream`/`LongStream`**（2.4）。
6. **IO 源的流一律 try-with-resources**（9.1 #11）。
7. **`Optional` 用在返回值上**：`findFirst()/min()/max()` 返回 `Optional` 是为了让"没有"显式化，拿到后用 `orElse/map/orElseThrow` 链式消费，别 `get()` 裸取。
8. **超长流水线的可读性**：逻辑分段时用中间变量接住 `Stream<T>`（同一个流对象不要复用），或者考虑 Gatherer 把"一段变换"封装成语义化算子（第八章）。

# 十、贯通视图：Stream 在 JDK 数据处理版图中的位置

## 10.1 一张地图

```
                        数据的"形态"与"节奏"
   ┌──────────────────────────────────────────────────────────────┐
   │  静止的一批（内存已就位）            持续的流（速率不可控）        │
   │  ───────────────────────           ─────────────────────     │
   │  Collection / 数组 / 生成器          IO / 推送 / 消息队列        │
   │        │                                  │                  │
   │   collection.stream()                Flow.Publisher           │
   │        │                                  │                  │
   │   Stream：声明式聚合              Flow：背压传输协议             │
   │   （惰性、一次性、可并行）          （request(n) 信用机制）        │
   │        │                                  │                  │
   │   终端操作算出结果                  Subscriber 逐个消费           │
   └──────────────────────────────────────────────────────────────┘
                    两者之间"异步编排"的桥 = CompletableFuture
```

| | Collection | Stream | Flow | CompletableFuture |
|---|---|---|---|---|
| 回答的问题 | 怎么**存**和**取** | 怎么**算**出结果 | 怎么**传**不压垮下游 | 完成后**接着做什么** |
| 元素数量 | 已知 | 未知但有限（或配 limit） | 无限/持续 | 0 或 1 |
| 执行时机 | 立即 | 终端操作时（惰性） | 有需求才推（拉推混合） | 完成回调 |
| 本文 | — | **本篇** | [Flow.md](Flow.md) | [CF 深度剖析](../netty/CompletableFuture&Future-Promise.md) |

## 10.2 三个"最后一次"的心法

1. **Collection 是名词，Stream 是动词**。`list` 是仓库里的货架，`list.stream()...collect(...)` 是一趟搬运作业——作业结束货架原样，作业说明书（流对象）作废。
2. **Stream 的所有"反直觉"都来自同一个设计**：控制权反转。你交出遍历控制权，换来了惰性、短路、并行、优化四样东西；每一条陷阱（9.1 表）都是控制权反转后"你以为会执行/不会执行"的错觉。读源码时盯住两个问题——**"谁在调用我的 lambda？什么时候？"**——一切都顺了。
3. **JDK 8 的引擎 + JDK 24 的 Gatherer**：二十个版本的演进没有推翻任何旧结构，只是在"登记阶段"和"包装 Sink"两个扩展点上不断加密度——好的抽象自己会长出下半场（1.5 节时间线）。

---

## 附录：本文实测环境与产物

| 项 | 值 |
|---|---|
| 源码版本 | JDK 27（`D:\soft\jdk\jdk-27\lib\src.zip`，抽取至 `D:\tmp\stream-src`） |
| 验证 JDK | JDK 27（`D:\soft\jdk\jdk-27\bin\java`） |
| 验证工程 | `D:\tmp\stream-demo\StreamDemo.java`（15 个验证小节）、`NullList.java` |
| 机器 | 16 逻辑核（commonPool 并行度 15） |

> 系列同篇：[Flow.md](Flow.md)（响应式流与背压）、[Java并发.md](Java并发.md)（ForkJoinPool 底座）、[SPI机制.md](SPI机制.md)、[G1.md](G1.md)、[JDK版本特性变化.md](JDK版本特性变化.md)（Stream 各版本增量在该文档对应版本章节有另行速记）。
