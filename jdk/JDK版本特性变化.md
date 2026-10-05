# JDK 版本特性全景解析(8 → 27,写给初学者的演进地图)

> 本文档是 JDK 学习系列的一篇。姊妹篇:[Flow.md](Flow.md)(响应式流与 Reactive Streams)、[Java并发.md](Java并发.md)(并发体系深度源码解析)。
> 源码引用说明:文中"源码坐标"均基于本机 `D:\soft\jdk\jdk-27` 的 `src.zip`(JDK 27),行号可直接跳转核对;历史版本的行为差异会在正文中说明。

## 如何读这份文档

**两遍读法:**

- **第一遍(建立地图)**:只读第一章总览 + 每章开头的"一句话定位"和结尾的"本章小结",20 分钟能对 JDK 8→27 的演进全貌心中有数。
- **第二遍(按需深入)**:用到哪个特性,直接跳到它"定型"的那个版本章节细读,并顺着章节内给出的 JEP 编号去 OpenJDK 官网看一手资料。

**一个重要的组织约定:**

很多特性不是"一个版本一次性出现"的,而是像产品一样迭代了七八年。典型如 `CompletableFuture`,从 JDK 8 出生,9、12 又各补了一轮 API。按我们的约定:

> **凡跨多个版本演进的特性,统一在它"最后一次发生实质变化的那个版本"的章节里做全景专题**,时间线表列出每个版本改了什么、为什么要改;更早版本只讲当次的增量并留下指向专题的链接。

例如 CompletableFuture 的全景专题在 **第六章(JDK 12)**,虚拟线程的全景专题在 **第十五章(JDK 21)**。附录 A 提供一张"跨版本演进特性速查表"反查。

**关于预览(Preview)特性:**

Java 现行规则是"先预览、再转正",一个特性往往要预览 2~7 个版本才转正,甚至可能被撤回(第十七章会讲 String Templates 被撤回的案例)。文档中所有标注"预览"的 API 需要 `--enable-preview --release <版本>` 编译运行,**生产慎用**;文中会明确标出每个特性当前的状态。

**示例代码运行环境:** JDK 27(`D:\soft\jdk\jdk-27`)。涉及预览特性的示例附了编译命令。

---

# 一、总览:JDK 八年演进的定位、哲学与全景

## 1.1 一句话定位

> **JDK 8 给了 Java 一副函数式的骨架,之后 9~27 的九年,是围绕"更易读的语言、更现代的 API、更快的运行时、更云原生的部署"四条线持续施工,并在 21 迈出并发模型革命(虚拟线程)的一步。**

## 1.2 两个时代:JDK 8 与"六个月发布"之后

- **JDK 8(2014)之前**:两三年一个大版本,特性堆栈式发布。JDK 8 本身是这个模式的绝唱——Lambda、Stream、Optional、新时间 API、CompletableFuture 一次性砸进来,相当于一次语言层面的"重新发明 Java"。
- **JDK 9(2017)起**:JEP 322 引入**六个月一个版本**的固定节奏,奇数年 3 月、9 月各发一版;每隔约两年出一个 **LTS**(长期支持版)。特性不再攒大招,而是"成熟一个进一个",没成熟的以**预览(Preview)**身份上车试跑。
- 由此带来一个必须建立的认知:**"最新版"和"该用哪个版本"是两个问题**。生产环境的主流落点是 LTS(8/11/17/21/25),而非 LTS 版本则充当 LTS 之间的"特性运输车"。

## 1.3 设计哲学:读完全文只需记住的六条主线

1. **语言:数据导向编程(Data-Oriented Programming)**——用 `record`(数据载体)+ `sealed`(封闭继承树)+ 模式匹配(解构数据)三件套,让"建模数据"这件事成为语言一等公民。
2. **语言:降低噪音**——`var`(10)、文本块(15)、未命名变量 `_`(22)、紧凑源文件与 `void main()`(25),一路做减法。
3. **API:现代化与"去 JNI/去 Unsafe 化"**——集合工厂方法、`HttpClient`、`FFM API`(22)逐步替代 `sun.misc.Unsafe` 与 JNI。
4. **并发:从"线程是重资源"到"线程是廉价资源"**——`CompletableFuture`(8)解决异步编排,`Flow`(9)解决背压,最终 **虚拟线程(21)** 把"一请求一线程"的阻塞式写法变成高吞吐写法,结构化并发(预览中)负责治理。
5. **运行时:停顿与内存的持续压榨**——G1(9 默认)→ ZGC/Shenandoah(11/12 实验制,15 转正)→ 分代 ZGC(21)→ 紧凑对象头(24 实验,27 默认开启)。
6. **部署:云原生与启动优化**——`jlink`/`jpackage`(9/16)、CDS(10/13)→ AOT 缓存(24/25/26)、UTF-8 默认(18)、HTTP/3(26)。

## 1.4 版本时间线与 LTS 地图(8 → 27)

| 版本 | 发布时间 | LTS | JEP 数 | 一句话主题 |
|------|----------|-----|--------|-----------|
| 8 | 2014-03 | ✔ | —(JEP 机制未启用) | Lambda、Stream、Optional、java.time、CompletableFuture |
| 9 | 2017-09 | | 82 | 模块化(Jigsaw)、集合工厂方法、G1 默认、JShell |
| 10 | 2018-03 | | 12 | `var` 局部推断、AppCDS、容器感知 |
| 11 | 2018-09 | ✔ | 17 | HttpClient 转正、ZGC 实验、单文件运行、EE 模块移除 |
| 12 | 2019-03 | | 8 | switch 表达式预览、Shenandoah 实验、Collectors.teeing |
| 13 | 2019-09 | | 5 | 文本块预览、yield、Socket 老实现替换 |
| 14 | 2020-03 | | 16 | switch 转正、Record/instanceof 模式预览、友好 NPE、CMS 移除 |
| 15 | 2020-09 | | 14 | 文本块转正、sealed 预览、隐藏类、ZGC/Shenandoah 转正 |
| 16 | 2021-03 | | 17 | Record/instanceof 转正、强封装默认、Stream.toList、jpackage |
| 17 | 2021-09 | ✔ | 14 | sealed 转正、内部强封装最终化、Security Manager 弃用 |
| 18 | 2022-03 | | 8 | UTF-8 默认、简易 Web 服务器、finalization 弃用 |
| 19 | 2022-09 | | 7 | 虚拟线程预览、结构化并发孵化、FFM 预览 |
| 20 | 2023-03 | | 6 | 预览推进版:Scoped Values 孵化、Record 模式二预览 |
| 21 | 2023-09 | ✔ | 15 | 虚拟线程转正、switch/record 模式转正、SequencedCollection、分代 ZGC |
| 22 | 2024-03 | | 12 | FFM 转正、未命名变量转正、G1 区域固定、Gatherers 预览 |
| 23 | 2024-09 | | 12 | ZGC 分代默认、Unsafe 记忆访问方法弃用、Markdown javadoc、String Templates 撤回 |
| 24 | 2025-03 | | 24 | Class-File API/Gatherers 转正、AOT 类加载、紧凑对象头实验、结构化并发重构 |
| 25 | 2025-09 | ✔ | 18 | 紧凑源文件/模块导入/灵活构造器转正、Scoped Values 转正、KDF、三大 JFR 增强 |
| 26 | 2026-03 | | 10 | HTTP/3、Lazy Constants、AOT 对象缓存、G1 吞吐优化、Applet 移除 |
| 27 | 2026-09 | | 9 | G1 全环境默认、紧凑对象头默认开启、TLS 1.3 后量子混合密钥交换、JFR 数据脱敏 |

## 1.5 特性全景矩阵:主题 × 关键版本

| 主题 | 起点 | 关键演进节点 | 现状(截至 JDK 27) |
|------|------|--------------|--------------------|
| Lambda / Stream / 函数式 | 8 | 9 增强、16 `toList`/`mapMulti`、24 Gatherers | 稳定,中间操作扩展机制定型 |
| 异步编排 | 8 | 9 超时与编排 API、12 异常组合 | 稳定(全景见 6.3) |
| 模块化 | 9 | 16/17 强封装收紧 | 稳定(全景见 3.2) |
| 语言简化 | 10 | 11 lambda 形参、15 文本块、22 `_`、25 `void main()` | 持续演进 |
| 数据建模 | 16 | 17 sealed、21 record 模式 | 稳定 |
| 模式匹配 | 16 | 21 switch/record 模式转正、23 起原始类型(预览) | 主干转正,原始类型仍在预览 |
| 并发革命 | 21 | 19/20 预览、24 消除 synchronized 钉住、25/26/27 结构化并发第 5~7 预览 | 虚拟线程稳定;结构化并发未定型 |
| 集合 | 8 | 9 工厂方法、10 copyOf、21 SequencedCollection | 稳定(全景见 15.4) |
| 网络/HTTP | 11 | 26 HTTP/3 | 稳定演进(全景见 20.3) |
| GC | 9(G1 默认) | 11 ZGC、12 Shenandoah、15 转正、21 分代、24 只留分代 ZGC、27 紧凑对象头默认 | ZGC/Shenandoah/G1 三线并行(全景见 15.5) |
| 外部内存/本地互操作 | 22(FFM) | 14 孵化起步、23/24 淘汰 Unsafe | FFM 稳定,Unsafe 倒计时(全景见 16.3) |
| 启动与 AOT | 10 | 13 动态 CDS、24 AOT 类加载、25 命令行简化、26 对象缓存 | 演进中(全景见 20.4) |
| 一次性预览未成 | 21/22 | 23 撤回 String Templates | 教训案例(见 17.2) |

## 1.6 关键问题 → 版本/特性映射

| 你遇到的问题 | 该去看 |
|--------------|--------|
| 匿名内部类太啰嗦、集合处理一团乱 | 2.2 Lambda / 2.3 Stream(JDK 8) |
| 链式异步回调写成一团、超时没人管 | 6.3 CompletableFuture 全景(8→12) |
| 想构建不可变集合、操作首尾元素 | 3.3(9 工厂方法)、15.4 SequencedCollection(21) |
| `NullPointerException` 看不出哪里空 | 8.3 友好 NPE(14) |
| 写 SQL/JSON 常量被转义折磨 | 9.2 文本块全景(13→15) |
| 一个纯数据类写一堆样板代码 | 10.2 Record 全景(14→16) |
| 类层次想"封死"不允许外部扩展 | 11.2 Sealed 全景(15→17) |
| `switch` 太啰嗦、忘记 break | 8.2 switch 表达式全景(12→14) |
| 高并发下线程不够用 / 线程池调优地狱 | 15.2 虚拟线程全景(19→21) |
| 并发任务取消、超时、异常传播难治理 | 18.3 结构化并发(预览 19→27) |
| 隐式传参不想用 `ThreadLocal` | 19.2 Scoped Values 全景(20→25) |
| 想安全地操作堆外内存/调本地库 | 16.3 FFM API 全景(14→22) |
| HTTP 客户端老旧、想用 HTTP/2、HTTP/3 | 5.2(11)、20.3 HTTP/3(26) |
| 应用启动慢、内存占用高 | 4.3 CDS(10)、18.4 AOT(24)、20.4 AOT 全景、21.3 紧凑对象头(24→27) |
| GC 停顿太大 / 想选 GC | 15.5 GC 全景(8→21) |
| 从脚本语言转向 Java 觉得仪式感太重 | 5.3 单文件运行(11)、19.3 紧凑源文件(25) |
| 加密:后量子、密钥派生、PEM | 19.5 KDF(25)、20.6/21.5 PEM 与后量子(26/27) |

## 1.7 跨版本演进特性的读法(本文的组织规则)

每个"演进型特性"的专题都长成同一副骨架:

1. **演进时间线表**:版本 → 变化 → 为什么要变。
2. **定型后的完整形态**:以最新版本视角给出可运行的完整示例。
3. **设计原因复盘**:Java 团队每一步补丁背后要解决的真实痛点。

各专题所在章节一览:CompletableFuture(6.3)、switch 表达式(8.2)、文本块(9.2)、Record(10.2)、instanceof 模式匹配(10.3)、sealed(11.2)、虚拟线程(15.2)、模式匹配全景(15.3)、SequencedCollection(15.4)、GC(15.5)、FFM(16.3)、String Templates 撤回(17.2)、Stream/Gatherers(18.2)、结构化并发(18.3,时间线更新于 21.4)、Scoped Values(19.2)、简化启动(19.3)、HTTP Client(20.3)、AOT(20.4)、Lazy Constants(20.5)、紧凑对象头(21.3)。

## 1.8 全文章节地图

```
第一章 总览(你在这里)
第二章   JDK 8  :函数式地基(Lambda/Stream/Optional/java.time/CompletableFuture)
第三章   JDK 9  :模块化定型、集合工厂方法、G1 默认、JShell
第四章   JDK 10 :var、AppCDS、容器感知
第五章   JDK 11 :HttpClient、ZGC 实验、单文件运行、EE 模块移除
第六章   JDK 12 :switch 预览、teeing;【专题】CompletableFuture 8→12
第七章   JDK 13 :文本块预览、yield、Socket 重实现
第八章   JDK 14 :switch 转正【专题】、Record/NPE 预览、CMS 移除
第九章   JDK 15 :文本块转正【专题】、sealed 预览、ZGC/Shenandoah 转正
第十章   JDK 16 :Record【专题】、instanceof 模式【专题】、强封装默认
第十一章 JDK 17 :sealed 转正【专题】、强封装最终化、Security Manager 弃用
第十二章 JDK 18 :UTF-8 默认、简易 Web 服务器、finalization 弃用
第十三章 JDK 19 :虚拟线程预览、结构化并发孵化、Record 模式预览
第十四章 JDK 20 :预览推进版(Record 模式/switch 模式/FFM 二预览)
第十五章 JDK 21 :虚拟线程【专题】、模式匹配【专题】、SequencedCollection【专题】、GC【专题】
第十六章 JDK 22 :FFM 转正【专题】、未命名变量、G1 区域固定
第十七章 JDK 23 :String Templates 撤回【教训】、Unsafe 弃用、ZGC 分代默认
第十八章 JDK 24 :Class-File API、Gatherers【专题】、AOT 起步、结构化并发重构【专题】
第十九章 JDK 25 :紧凑源文件【专题】、Scoped Values【专题】、灵活构造器、KDF
第二十章 JDK 26 :HTTP/3【专题】、Lazy Constants【专题】、AOT【专题】、Applet 移除
第二十一章 JDK 27 :G1 全环境默认、紧凑对象头默认【专题】、后量子 TLS、SC 第七预览【时间线】
附录 A   跨版本演进特性速查表
附录 B   废弃与移除清单(8→27)
附录 C   升级路线建议(8→17→21→25)
参考资料
```

---

# 二、JDK 8(2014-03,LTS):函数式地基——一次重新发明 Java 的版本

> **一句话定位:JDK 8 是 Java 历史上影响最深远的一次升级,它把"函数是一等公民"写进了语言,并为此补齐了 Stream、Optional、java.time、CompletableFuture 四大件。今天任何一个 Java 面试,一半的内容仍然出自这一版。**

## 2.1 版本全景

| 领域 | 特性 | 一句话影响 |
|------|------|-----------|
| 语言 | Lambda 表达式、方法引用、函数式接口(JSR 335) | 匿名内部类的终结者 |
| 语言 | 接口 default / static 方法 | 接口可以带实现,为集合默认方法铺路 |
| API | Stream API + `java.util.function` 包 | 声明式集合处理,支持惰性与并行 |
| API | `Optional<T>` | 把"可能没有"写进类型签名 |
| API | java.time(JSR 310) | `LocalDate`/`ZonedDateTime`,替代千疮百孔的 `Date` |
| 并发 | `CompletableFuture` / `CompletionStage` | 异步编排从此有了链式 DSL |
| 并发 | `StampedLock`、`ConcurrentHashMap` 增强、`ForkJoinPool.commonPool` | 并发工具箱补课 |
| 集合 | Map 默认方法:`getOrDefault`/`computeIfAbsent`/`merge` 等 | 一行代码顶过去五行 |
| JVM | **永久代移除,改用 Metaspace** | `PermGen OOM` 成为历史名词 |
| API | Nashorn JS 引擎、Base64、`String.join`、`Arrays.parallelSort`、可重复注解(@Repeatable) | 杂项补课 |

## 2.2 Lambda 与函数式接口:为什么需要它

**白话**:在 8 之前,想把"一段行为"当参数传给方法,只能包一层匿名内部类——5 行代码只为表达 1 行逻辑。Lambda 允许你直接写"入参 → 结果"。Java 是静态类型语言,Lambda 能存在的前提是"这段代码对应什么类型"有明确答案——答案是**函数式接口**(只有一个抽象方法的接口,如 `Runnable`、`Comparator`、`Predicate`)。

```java
// 8 之前
new Thread(new Runnable() {
    @Override public void run() {
        System.out.println("hello");
    }
}).start();

// 8 之后
new Thread(() -> System.out.println("hello")).start();

// 方法引用是 Lambda 的简写:类::方法
List<String> names = people.stream()
        .filter(p -> p.getAge() >= 18)      // Predicate<Person>
        .map(Person::getName)               // Function<Person, String>
        .sorted(Comparator.comparingInt(String::length))
        .collect(Collectors.toList());
```

**为什么重要**:Lambda 不只是语法糖,它让"行为"可以像数据一样在 API 之间流动。Stream、CompletableFuture、事件回调,全都建立在它之上。

## 2.3 Stream:声明式集合处理

**白话**:把集合处理从"for 循环 + 临时变量"变成"流水线":数据源 → 一串中间操作(惰性) → 一个终端操作触发执行。

```java
// 统计每个部门年龄大于 30 的人数
Map<String, Long> countByDept = employees.stream()
        .filter(e -> e.getAge() > 30)
        .collect(Collectors.groupingBy(Employee::getDept, Collectors.counting()));

// 惰性求值:没有终端操作,上面这条流什么都不会做
Stream<String> s = list.stream().filter(x -> { System.out.println(x); return true; });

// 并行流:一行切到 ForkJoinPool.commonPool
long c = list.parallelStream().filter(this::isValid).count();
```

**要点与坑**:
- 中间操作**惰性**,终端操作**触发**;一次消费,复用会抛 `IllegalStateException`。
- `parallelStream` 不是万能加速:小集合/IO 密集反而更慢,且默认共享 `commonPool`,阻塞任务会拖累全局。
- 深入解析见 [Java并发.md](Java并发.md) 中 Fork/Join 相关章节。

## 2.4 Optional:让 null 有名分

```java
Optional<Person> opt = repository.findByName("tom");  // 方法签名声明"可能没有"
String city = opt.map(Person::getAddress)
                 .map(Address::getCity)
                 .orElse("UNKNOWN");
```

**为什么**:NPE 是 Java 第一大线上异常。`Optional` 把"可能为空"从文档承诺变成类型系统承诺。注意它设计的定位是**返回值**,不建议做字段/参数。

## 2.5 java.time:把 Date/Calendar 扫进博物馆

```java
LocalDate d = LocalDate.of(2026, 10, 5);
LocalDateTime now = LocalDateTime.now();
ZonedDateTime sh = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
Duration span = Duration.between(start, end);

// DateTimeFormatter 线程安全(SimpleDateFormat 不是!)
DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
```

**为什么**:`Date` 可变、月份从 0 开始、时区语义混乱、`SimpleDateFormat` 非线程安全——三宗罪延续二十年。`java.time`(源自 Joda-Time)不可变、线程安全、API 即文档。

## 2.6 并发与集合增强

```java
// Map 默认方法:computeIfAbsent 是构建多值 Map 的标准姿势
Map<String, List<Order>> byUser = new HashMap<>();
orders.forEach(o -> byUser.computeIfAbsent(o.userId(), k -> new ArrayList<>()).add(o));

// CompletableFuture(JDK 8 的基础形态,完整演进专题见 6.3)
CompletableFuture.supplyAsync(() -> fetchUser(id))
        .thenApply(this::toDto)
        .thenCombine(orderFuture, (user, order) -> merge(user, order))
        .exceptionally(ex -> defaultDto(ex))
        .thenAccept(this::render);
```

- `ConcurrentHashMap`:链表长度 ≥8 且表足够大时转**红黑树**,最坏查询 O(n) → O(log n);新增 `newKeySet()`、聚合统计 `mappingCount()`。
- `StampedLock`:支持**乐观读**的读写锁,读多写少场景吞吐高于 `ReentrantReadWriteLock`。

## 2.7 Metaspace:永久代之死

**白话**:JDK 8 把方法区实现从堆内的"永久代"挪到本地内存的 **Metaspace**,默认只受物理内存限制。原因:永久代大小难以预估(`String.intern` 挪到堆后更是只剩类元数据),OOM 调参(`-XX:MaxPermSize`)成了玄学;改用 `-XX:MaxMetaspaceSize` 后模型更干净,也为后续 `Elastic Metaspace`(16)铺路。

## 2.8 本章小结

JDK 8 用"语言 + API + 运行时"三线并进一次性补齐了 Java 与新兴语言(Scala/C#)的差距。记住五大件:**Lambda、Stream、Optional、java.time、CompletableFuture**;记住一个 JVM 事实:**永久代没了**。CompletableFuture 当时的形态还只是半成品,超时、异常组合等能力要到 9/12 才补齐——这正是下一章开始的故事。

---

# 三、JDK 9(2017-09):模块化定型与 API 现代化

> **一句话定位:JDK 9 干了两件大事——用 Jigsaw 把 JDK 和你的应用拆成显式依赖的模块;用集合工厂方法、Stream 增强、CompletableFuture 补丁、HTTP/2 Client(孵化)开启 API 现代化。同时把 G1 推上默认 GC 的位置。**

## 3.1 版本全景

| JEP | 特性 | 一句话影响 |
|-----|------|-----------|
| 261/200/220/282 | **模块系统(Jigsaw)** + jlink/jmod | `module-info.java` 显式声明依赖;定制瘦身运行时 |
| 269 | **集合工厂方法** `List.of`/`Set.of`/`Map.of` | 字面量式创建不可变集合 |
| 248 | **G1 成为默认 GC** | 吹响 CMS/Parallel 退役序曲 |
| 254 | Compact Strings | String 内部 `char[]` → `byte[]`+编码标记,纯 ASCII 场省一半内存 |
| 222 | JShell | Java 有了 REPL |
| 266 | **Flow API(响应式流) + CompletableFuture 增强** | Reactive Streams 进入 JDK;异步编排补课 |
| 110 | HTTP/2 Client(孵化,`jdk.incubator.httpclient`) | 11 转正的前身 |
| 102 | Process API:`ProcessHandle` | 拿 pid、枚举进程、监听退出 |
| 264 | Stack-Walking API | 惰性栈帧遍历,替代异常式抓栈 |
| 193 | VarHandle | `Unsafe` 的合法替代品(CAS/fence 语义) |
| 238 | Multi-Release JAR | 一个 jar 针对不同 JDK 版本提供不同实现 |
| — | 接口私有方法;try-with-resources 支持 effectively final 变量 | 语言小补 |
| 291 | 弃用 CMS;289 弃用 Applet;320(预告)弃用 Java EE/CORBA 模块 | 退役工程开始 |

## 3.2 模块化 Jigsaw:Java 最大的架构手术

**白话**:模块化之前,classpath 是一锅粥——所有 jar 平铺,JDK 也是一个巨石(`rt.jar`),内部 API(如 `sun.misc.Unsafe`)人人可调。Jigsaw 之后,**模块**是带名字的依赖单元:`module-info.java` 声明"我需要谁(requires)、我暴露谁(exports)",其余包对外彻底不可见。

```java
// module-info.java
module com.shop.order {
    requires java.net.http;          // 显式依赖 JDK 模块
    requires com.fasterxml.jackson.databind;
    exports com.shop.order.api;      // 只有 api 包对外可见
    opens   com.shop.order.model to com.fasterxml.jackson.databind; // 反射可访问
}
```

```bash
# jlink:按模块裁剪出自带精简 JRE 的运行时镜像
jlink --add-modules com.shop.order --output app-runtime
```

**演进提示**:9 的强封装是渐进式的(默认 `--illegal-access=permit` 允许反射访问内部 API);**16 改为默认拒绝(JEP 396),17 彻底禁止(JEP 403)**,想继续访问只能 `-XX:+...`?不,只能 `--add-opens java.base/java.lang=ALL-UNNAMED` 显式开洞。这条收紧时间线,是很多老框架(Spring/Hibernate 旧版)升级踩坑的根源。

**为什么**:除了"封装内部 API",模块化更大的收益是**依赖显式化**和**可裁剪运行时**(容器镜像从几百 MB 的 JDK 缩到几十 MB 的 jlink 镜像)。

## 3.3 集合工厂方法与 Stream/Optional 增强

```java
// 9:不可变集合的"字面量"
List<String> langs = List.of("Java", "Kotlin", "Scala");
Map<Integer, String> m = Map.of(1, "一", 2, "二");
Map<Integer, String> big = Map.ofEntries(Map.entry(1, "一"), Map.entry(2, "二"));
// 注意:不可变、禁止 null 元素、未承诺顺序

// Stream:takeWhile / dropWhile / iterate(seed, hasNext) / ofNullable
Stream.of(1, 2, 3, 4, 5).takeWhile(n -> n < 3).forEach(System.out::print); // 12
Stream.iterate(1, n -> n < 100, n -> n * 2);   // 有终止条件的 iterate(9 之前只能无限流)
Collectors.flatMapping(...); Collectors.filtering(...);   // 分组内再加工

// Optional:or / ifPresentOrElse / stream
opt.or(() -> Optional.of(fallback()))
   .ifPresentOrElse(this::handle, this::logEmpty);
```

## 3.4 CompletableFuture(JDK 9 增量)与 Flow

```java
// 9 给 CompletableFuture 补的第一批课:超时与快速失败
CompletableFuture.supplyAsync(() -> callRemote())
        .orTimeout(800, TimeUnit.MILLISECONDS)                 // 800ms 没结果就异常完成
        .completeOnTimeout(DEFAULT_VALUE, 800, MILLISECONDS)   // 或超时给默认值
        .thenAccept(this::render);

Executor delayed = CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS); // 延迟执行器
CompletableFuture.failedFuture(ex);   // 直接构造已完成失败的 Future
```

> 9 只是开始,12 又补了异常组合 API;**完整演进时间线与"为什么"见 6.3 专题**。
> 源码坐标:`java.base/java/util/concurrent/CompletableFuture.java:2792`(`orTimeout`,JDK 27 源码)。

### 3.4.1 深入一步:`delayedExecutor` 延迟执行器的场景与原理

**白话**:`Executor` 接口只有 `execute(Runnable)`,没有任何"调度"语义。`delayedExecutor` 返回的不是线程池,而是**装饰器**——"提交到我这里的任务,先等 N 秒,再交给 base executor 执行"。它的价值在于把"延迟执行"塞进任何只认 `Executor` 参数的位置(CompletableFuture 的 `*Async(fn, executor)`、HttpClient 的 `builder.executor(...)`、各类自定义框架),而不必为一次延迟专门建一个 `ScheduledThreadPoolExecutor`。

**语义要点**(javadoc 原话):**延迟从 `execute()` 被调用那一刻起算**,不是从 `delayedExecutor(...)` 创建时起算——所以它可以反复复用,每个任务各自计时。

**典型场景**:

```java
// ① 异步重试/退避:失败后隔 N 秒再试(exceptionallyCompose 为 JDK 12 API,演进见 6.3)
static <T> CompletableFuture<T> withRetry(Supplier<T> call, int retries) {
    CompletableFuture<T> f = CompletableFuture.supplyAsync(call);
    for (int i = 1; i <= retries; i++) {
        Executor backoff = CompletableFuture.delayedExecutor(i * 2L, TimeUnit.SECONDS);
        f = f.exceptionallyCompose(ex -> CompletableFuture.supplyAsync(call, backoff));
    }
    return f;
}

// ② 异步链里的"非阻塞 sleep":落库后 30 秒再触发缓存失效检查
persist(key).thenCompose(v -> CompletableFuture.runAsync(
        () -> cache.checkEviction(key),
        CompletableFuture.delayedExecutor(30, TimeUnit.SECONDS)));
```

对比 `Thread.sleep`:sleep 会占死一个池线程;delayedExecutor 把"等待"交给调度机制,池里只有到点后的活。与 `orTimeout`/`completeOnTimeout` 互补:那两个管"**已开始**的阶段多久没结果"(止损),delayedExecutor 管"**还没开始**的动作晚点再开始"(退避/节流)。虚拟线程组合:base 传 `Executors.newVirtualThreadPerTaskExecutor()`,到点后为每个任务起虚拟线程。

**原理:两段式"定时器负责等,base executor 负责跑"**(源码坐标为 JDK 27):

```java
// CompletableFuture.java:2890:只是记下"延迟 + 目标池",不创建任何线程
public static Executor delayedExecutor(long delay, TimeUnit unit) {
    return new DelayedExecutor(unit.toNanos(delay), ASYNC_POOL);   // 默认 base = ForkJoinPool.commonPool()
}

// :2938 任务真正派发的那一刻,才进延迟队列
public void execute(Runnable r) {
    ForkJoinPool e = ASYNC_POOL;
    e.scheduleDelayedTask(new ScheduledForkJoinTask<Void>(
        nanoDelay, 0L, true, new TaskSubmitter(executor, r), null, e));
}

// :2954 到点后由 FJP 工作线程执行 TaskSubmitter → 把任务移交 base executor
static final class TaskSubmitter implements Runnable {
    public void run() { executor.execute(action); }
}
```

时序:`runAsync(fn, delayed)` → CF 调 `delayed.execute(task)`,**此刻才开始计时** → FJP 工作线程等到期 → `TaskSubmitter.run()` → `baseExecutor.execute(fn)` 真正执行。实测:1s 延迟约 1006ms 触发,执行线程为 `ForkJoinPool.commonPool-worker-*`(默认 base 即 commonPool)。

**实现演进**(它自己也是"跨版本变化"):

| 版本段 | 实现 | 备注 |
|--------|------|------|
| 9 ~ 21 | JVM 全局共享一根单线程守护 `ScheduledThreadPoolExecutor`,线程名 `CompletableFutureDelayScheduler`(JDK 21 源码 `CompletableFuture.java:2866-2885`),`setRemoveOnCancelPolicy(true)`;`orTimeout` 的计时同走它 | 一根常驻专职调度线程 |
| **27(现源码)** | 延迟任务直接挂进 `ForkJoinPool.scheduleDelayedTask`(工作线程 park 到最近到期点),专职调度线程删除,`orTimeout` 同机制 | 省一根常驻线程、少一次移交 |

**注意点**:
- 默认 base 是 `ForkJoinPool.commonPool()`:任务最终在 commonPool 上执行,重活/阻塞会拖累全 JVM 的 parallelStream 与默认异步 CF——生产显式传自己的池或虚拟线程执行器;
- **没有取消句柄**:返回的 `Executor` 不暴露 `Future`,任务排队后无法从外部取消——这是与 `ScheduledThreadPoolExecutor.schedule()` 的核心差距;
- 精度是"调度器级"(毫秒级误差,受 FJP 负载影响),适合秒级退避/去抖,不适合硬实时;
- 与 `ScheduledThreadPoolExecutor` 的取舍:STE 适合大量/周期/需取消的定时任务;delayedExecutor 是轻量装饰器,让**执行仍落在你的业务池**(监控、上下文传递、MDC 都不丢)。

**Flow API(Reactive Streams, JEP 266)**:`Flow.Publisher/Subscriber/Subscription/Processor` 四个接口把"背压"协议标准化,是 WebFlux/Akka Streams 等reactive 生态的官方对接点。深入解析见 [Flow.md](Flow.md)。

## 3.5 其他值得记住的

- **Compact Strings(254)**:`String` 内部从 `char[]` 改为 `byte[]` + coder 标记(Latin-1 用 1 字节/字符),纯 ASCII 字符串内存直接减半——这是"用户无感知、内存大收益"的典型运行时优化。
- **G1 默认(248)**:G1 在 7u4 可用,9 起成为默认;`-XX:+UseParallelGC` 等旧标志仍在,但方向已定(CMS 9 弃用、14 移除)。
- **JShell**:即时求值 REPL,教学与验证 API 的神器。
- **`_` 不能再当变量名**:单下划线在 8 被保留、9 起编译报错——为 22 的"未命名变量"埋下伏笔(见 16.2)。

## 3.6 本章小结

JDK 9 是"分水岭"版本:发布模式变了、模块化定了、六个月节奏开了第一枪。它本身特性最密集(82 个 JEP),但大多数是"内功";对应用开发者最直接的是**集合工厂方法、Stream/Optional/CompletableFuture 增强、HttpClient(孵化)**。

---

# 四、JDK 10(2018-03):var 与"六个月节奏"的第一个版本

> **一句话定位:第一个六个月版本,主打"小而美"——var 局部类型推断、AppCDS 启动优化、容器感知,G1 的 Full GC 并行化。**

## 4.1 版本全景

| JEP | 特性 | 一句话影响 |
|-----|------|-----------|
| 286 | **局部变量类型推断 `var`** | 声明时少写一遍类型 |
| 310 | Application Class-Data Sharing | 应用类也能进 CDS 归档,启动提速 |
| 307 | G1 并行 Full GC | 补齐 G1 最痛的串行 Full GC |
| 304 | 统一 GC 接口 | 新 GC 接入更标准化 |
| 312 | Thread-Local Handshakes | JVM 可以对单个线程"握手停顿" |
| 317 | Graal JIT(实验) | Java 写的 JIT 编译器 |
| 322 | 时间版本化 | `$RELEASE.$FEATURE.$INTERIM.$UPDATE` 版本号体系 |
| — | 容器感知(`UseContainerSupport`)、`Optional.orElseThrow()`、`List.copyOf` 等 | 细节见下 |

## 4.2 var:局部变量类型推断

```java
var list = new ArrayList<String>();          // 推断为 ArrayList<String>
var stream = list.stream().filter(s -> !s.isEmpty());
for (var entry : map.entrySet()) { ... }      // 增强循环
try (var in = new FileInputStream("f")) { ... } // try 资源
```

**边界**:只能用于**有初始化表达式**的局部变量;不能用于字段、方法参数、返回值(286 的刻意取舍——避免推断波及 API 契约)。11 把它扩展到 lambda 形参(`(var a, var b) -> ...`,主要为了能加注解)。

**为什么**:泛型嵌套时类型名动辄三四十个字符,`var` 消除纯冗余,但**可读性是前提**:右边看不出类型时别用。

## 4.3 不可变集合收尾与 Optional

```java
List<String> ro = List.copyOf(someMutableList);       // 10:从任意集合生成不可变副本
var ul = stream.collect(Collectors.toUnmodifiableList()); // 10:收集成不可变 List
Optional<T> o = optional.orElseThrow();   // 10:等价于 get(),但语义自明,推荐替代
```

> 集合不可变化的完整故事(8→9→10→21)见 15.4;Optional 的完整演进(8→9→10)小记:8 出生的 `of/ofNullable/map/orElseThrow(Supplier)`,9 加 `or/ifPresentOrElse/stream`,10 加无参 `orElseThrow()` 后 API 定型。

## 4.4 启动与云原生地基

- **AppCDS(310)**:把类的元数据预先解析成归档,JVM 启动时直接映射,省去类加载开销。演进:13 动态归档(350)→ 24 AOT 类加载(483)→ **全景见 20.4**。
- **容器感知**:JVM 正确读取 cgroup 限额(而非把宿主机 64 核当成自己的),`-XX:MaxRAMPercentage` 取代写死的 `-Xmx`。虽无独立 JEP,10 落地并回移 8u191,是容器化的实际前提。
- **G1 并行 Full GC(307)**:G1 退化到 Full GC 时不再单线程。

## 4.5 本章小结

10 证明了六个月节奏可行:每一版只放"熟了"的特性。`var` 是语言线第一个新语法;AppCDS/容器感知是"云原生 Java"叙事的开端。

# 五、JDK 11(2018-09,LTS):HTTP Client、ZGC 与单文件运行

> **一句话定位:Oracle 商业模式调整后第一个 LTS,JFR 回归开源、Java EE/CORBA 模块移除、HttpClient 转正、ZGC 实验登场——"干净、现代、面向云"的 JDK 从这里起笔。**

## 5.1 版本全景

| JEP | 特性 | 一句话影响 |
|-----|------|-----------|
| 321 | **HTTP Client 标准化**(`java.net.http`) | 支持 HTTP/2、异步、WebSocket 的官方客户端 |
| 333 | **ZGC(实验)** | 亚毫秒级停顿、TB 级堆的新一代 GC |
| 318 | Epsilon GC | 不做回收的"空转 GC",用于基准与短命任务 |
| 328 | Flight Recorder 开源 | 低开销生产级事件记录(原 Oracle 商业特性) |
| 330 | **单文件源码直接运行** `java Hello.java` | Java 有了"脚本"体验 |
| 320 | **移除 Java EE 与 CORBA 模块** | `javax.xml.bind` 等一夜消失,需自行加依赖 |
| 332/329 | TLS 1.3、ChaCha20-Poly1305 | 传输安全现代化 |
| 181 | Nest-based Access Control | 嵌套类间私有访问不再靠合成方法 |
| 309 | 动态类文件常量(condy) | 字节码常量池新增 `CONSTANT_Dynamic` |
| 323 | `var` 用于 lambda 形参 | `(var x, var y) -> ...` 可加注解 |
| 335/336 | 弃用 Nashorn、Pack200 | 15 移除 Nashorn |
| — | String/Files/Predicate 集体加方法 | 见下文示例 |

## 5.2 HTTP Client:`java.net.http` 登场

**白话**:用了二十年 `HttpURLConnection`(难用、不支持 HTTP/2、还要自己管连接),9 孵化、11 转正的 `HttpClient` 一步到位:构建器配置、请求-响应对象化、同步/异步双轨、原生 HTTP/2 与 WebSocket。

```java
HttpClient client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .connectTimeout(Duration.ofSeconds(5))
        .build();

HttpRequest req = HttpRequest.newBuilder(URI.create("https://example.com/api"))
        .timeout(Duration.ofSeconds(10))
        .header("Accept", "application/json")
        .GET().build();

// 同步
HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
// 异步(返回 CompletableFuture,与 8 的异步编排无缝衔接)
client.sendAsync(req, HttpResponse.BodyHandlers.ofString())
      .thenApply(HttpResponse::body)
      .thenAccept(System.out::println);
```

> 源码坐标:`java.net.http/java/net/http/HttpClient.java:255`(`newHttpClient`,JDK 27 源码)。
> 演进:9 孵化(JEP 110)→ 11 转正(321)→ **26 增加 HTTP/3(517),全景见 20.3**。

## 5.3 语言与 API"群发糖"

```java
String s = "  hello  ";
s.isBlank(); s.strip(); s.stripLeading(); s.stripTrailing();  // strip 按 Unicode 空白,比 trim 干净
"ab".repeat(3);                       // "ababab"
"a\nb".lines().count();               // 2,流式按行处理

Files.readString(Path.of("f.txt"));   // 11:一行读文件
Files.writeString(Path.of("out.txt"), "hello");
Predicate<String> notBlank = Predicate.not(String::isBlank);  // 否定谓词
list.toArray(String[]::new);          // Collection.toArray(IntFunction)

// 单文件直接运行:无需 javac,像脚本一样
// $ java Hello.java
public class Hello { public static void main(String[] args) { System.out.println("hi"); } }
```

## 5.4 运行时三件事:ZGC、Epsilon、JFR

- **ZGC(333,实验)**:并发标记-整理,停顿目标 <10ms 且**与堆大小无关**(TB 级堆依然成立)。原理要点:染色指针 + 读屏障。演进:13 支持归还内存(351)→ 15 转正(377)→ 21 分代(439)→ 23 分代默认(474)→ 24 移除非分代模式(490)。GC 全景见 15.5。
- **Epsilon(318)**:只分配不回收,超限直接 OOM。用途:性能基准隔离 GC 干扰、超短命任务、内存压力测试。
- **JFR(328)**:生产可开(<1% 开销)的事件记录器,`-XX:StartFlightRecording=duration=60s,filename=a.jfr`。演进:14 事件流式消费(349)→ 25 CPU 时间采样等三连(509/518/520)→ 27 数据脱敏(536)。

## 5.5 移除与弃用:一次"排毒"

**移除 Java EE/CORBA 模块(320)**:`javax.xml.bind(JAXB)`、`javax.activation`、`javax.transaction`、CORBA 全部出局——它们是从 JCP 独立演进的模块,留在 JDK 里既拖累瘦身又阻碍迭代。迁移方式:自行添加依赖(JAXB 在 Maven `jakarta.xml.bind` / `javax.xml.bind` 坐标)。**这是从 8 升级到 11 最常见的第一堵墙。**

同时:弃用 Nashorn(335,15 移除)、Pack200(336)。

## 5.6 本章小结

11 的关键词是"去包袱 + 打地基":去掉 EE/CORBA,开源 JFR,给出 HTTP Client 和 ZGC 两块未来十年最重要的地基。它是继 8 之后企业落地最广的版本之一。

---

# 六、JDK 12(2019-03):小版本里的三件事 + CompletableFuture 演进全景

> **一句话定位:一个只有 8 个 JEP 的小版本:switch 表达式首秀(预览)、Shenandoah 首秀(实验)、G1 可中止混合回收;同时 CompletableFuture 补上最后一块 API 拼图——本文借此做一个全景专题。**

## 6.1 版本全景

| JEP | 特性 | 一句话影响 |
|-----|------|-----------|
| 325 | **switch 表达式(预览一)** | 箭头语法、返回值;14 转正 |
| 189 | **Shenandoah GC(实验)** | RedHat 系低停顿 GC,与 ZGC 双雄 |
| 341 | 默认 CDS 归档 | 开箱即用的启动加速 |
| 344 | G1 可中止混合回收 | 混合回收可被打断,停顿达标优先 |
| 346 | G1 空闲时归还内存 | 容器里内存弹性 |
| 334 | JVM Constants API | 常量池条目的建模 API(配 condy) |
| 340 | 统一 AArch64 移植 | 收敛重复的两套 ARM64 代码 |
| — | `Collectors.teeing`、`String.indent/transform`、`Files.mismatch`、紧凑数字格式 | API 补糖 |

```java
// 12 的 API 糖(有的至今好用)
var avgAndMax = Stream.of(1, 2, 3, 4)
        .collect(Collectors.teeing(                 // 一次遍历,两个收集器 + 合并
                Collectors.averagingInt(i -> i),
                Collectors.maxBy(Integer::compare),
                (avg, max) -> Map.entry(avg, max.orElse(0))));
"a b".transform(String::toUpperCase);            // 链式加工,避免中间变量
Files.mismatch(p1, p2);                          // 内容级比较,提前短路

// 紧凑数字格式(12)
NumberFormat.getCompactNumberInstance(Locale.CHINA, NumberFormat.Style.SHORT).format(1_0000_000); // "1000万"
```

## 6.2 switch 表达式(预览一):故事从这年开始

12 首次预览(325)时用 `break` 带值,13 二预览(354)改为 `yield`,14 转正(361)。**完整专题与最终形态见 8.2。**这里先看一眼预览形态为何受欢迎:

```java
// 旧 switch:忘 break 就贯穿、变量作用域泄漏、不能当表达式
// 新形态(14 定稿):箭头 + yield + 穷举检查
int days = switch (month) {
    case JAN, MAR, MAY, JUL, AUG, OCT, DEC -> 31;
    case APR, JUN, SEP, NOV                -> 30;
    case FEB -> { if (isLeap(year)) yield 29; else yield 28; }
};
```

## 6.3 【跨版本演进专题】CompletableFuture:8 → 9 → 12

### 6.3.1 演进时间线

| 版本 | 变化 | 为什么要变 |
|------|------|-----------|
| **JDK 8**(2014) | 引入 `CompletableFuture`/`CompletionStage`:任务创建(`supplyAsync` 等)、组合(`thenApply/thenCompose/thenCombine/allOf`)、异常(`exceptionally/handle/whenComplete`) | 8 之前的 `Future` 只会 `get()` 阻塞,异步任务无法链式编排;Node.js 式回调地狱又难维护。CompletableFuture 用"可完成的 Future + 依赖图"一次解决,并内建 ForkJoinPool 支持 |
| **JDK 9**(2017) | `orTimeout`、`completeOnTimeout`、`delayedExecutor`、`failedFuture`、`copy`、`minimalCompletionStage`;`CompletionStage` 微调 | 实战暴露三大缺口:**超时没人管**(远程调用挂起整个流水线)、**不好测试**(内部线程池难控)、**不易复用**(把可变的 CF 直接暴露给调用方易被误改)。9 一次补齐超时、延迟执行、不可变视图(`minimalCompletionStage`)与拷贝 |
| **JDK 12**(2019) | `exceptionallyCompose`、`exceptionallyComposeAsync`、`exceptionallyAsync` | 8 的 `exceptionally` 只能"换成静态默认值",**无法"降级到另一个异步调用"**(重试、备用服务);且异步链上换线程执行异常回调没有标准入口。12 补上异步异常处理与"异常后接新 Future"两件套,API 至此定型 |
| JDK 21+ | 无实质 API 变化 | 虚拟线程时代,阻塞式写法回归(见 15.2),CF 回归"真异步 IO 编排"的本职 |

### 6.3.2 定型后的完整形态(JDK 12+)

```java
CompletableFuture<Dto> pipeline = CompletableFuture
        .supplyAsync(() -> callPrimary(id), ioPool)              // 1. 异步发起主调用
        .orTimeout(800, TimeUnit.MILLISECONDS)                   // 2. 超时保护(9)
        .exceptionallyCompose(ex -> {                            // 3. 失败降级到备用服务(12)
            log.warn("primary failed, fallback", ex);
            return CompletableFuture.supplyAsync(() -> callBackup(id), ioPool);
        })
        .thenApply(this::toDto)
        .whenComplete((dto, ex) -> metrics.record(ex == null)); // 4. 旁路观测

// 主线程在需要结果时才 join,或用 allOf 汇流多个流水线
CompletableFuture.allOf(pipeline, anotherFuture).join();
```

### 6.3.3 设计原因复盘

1. **8 的核心赌注**:把 Future 从"占位符"升级为"依赖图节点"(`CompletionStage` 描述依赖,`CompletableFuture` 负责完成)。代价是方法爆炸(50+ 个),但换来无锁的回调编排。
2. **9 的教训**:超时缺失让"一个慢依赖拖垮整条链"在生产中反复上演;`orTimeout` 用内部调度器在超时后**主动异常完成**,避免永久挂起(该内部调度机制在 27 并入了 ForkJoinPool 的延迟任务队列,与 `delayedExecutor` 共享,源码走读见 3.4.1)。
3. **12 的收尾**:`exceptionallyCompose(ex -> otherFuture)` 让"重试/熔断/降级"成为一阶公民,不必再借 `handle` + `thenCompose` 两段拼接。
4. **与虚拟线程的关系(21)**:虚拟线程让"直接阻塞"重新廉价,`CompletableFuture` 的定位回归到真正的异步 IO(如 HttpClient、NIO 网关)与 Fan-out 编排,而不是"怕阻塞而被迫异步"。

源码坐标(JDK 27):`CompletableFuture.java:2792`(orTimeout)、`:2810`(completeOnTimeout)、`:2872`(delayedExecutor)、`:2918`(failedFuture)、`:2445`(exceptionallyCompose)。

## 6.4 本章小结

12 是典型的小版本:switch 预览开了语言线的新篇章,Shenandoah 与 ZGC 形成 GC 双雄竞争,CompletableFuture 在这里完成了它的 API 定型——异步编排的故事从此不再缺课。

---

# 七、JDK 13(2019-09):文本块初现

> **一句话定位:只有 5 个 JEP 的保守版本,但预览的文本块直击 Java 多行字符串之痛;底层则悄悄换掉了服役 20 年的 Socket 实现。**

## 7.1 版本全景

| JEP | 特性 | 一句话影响 |
|-----|------|-----------|
| 355 | **文本块(预览一)** | `"""` 三引号多行字符串,15 转正 |
| 354 | switch 表达式(预览二) | `break value` 改名 `yield` |
| 353 | 重实现传统 Socket API | `SimpleServerSocketImpl` 换成 NioSocketImpl,可维护性大增 |
| 350 | 动态 CDS 归档 | 运行末尾 `-XX:ArchiveClassesAtExit` 动态生成归档 |
| 351 | ZGC 归还未用内存 | 长期闲置堆可还给操作系统 |

```java
// 13 之前的 SQL 字面量:转义地狱
String sql = "SELECT id, name\n" +
             "FROM users\n" +
             "WHERE age > ?\n" +
             "  AND city = 'BJ'";

// 13 文本块(预览):所见即所得
String sql = """
        SELECT id, name
        FROM users
        WHERE age > ?
          AND city = 'BJ'
        """;
```

文本块的演进(缩进规则、`\` 续行与 `\s` 空格转义在 14 二预览中加入、15 转正)**见 9.2 专题**。

## 7.2 本章小结

13 的价值在于"底层焕新 + 语言预览开跑":Socket/NIO 统一(353)为后续虚拟线程的阻塞感知打底;动态 CDS(350)是 AOT 加速长跑的中间站;文本块则结束了 Java 在多行字符串上落后脚本语言二十年的历史。

# 八、JDK 14(2020-03):switch 表达式转正、Record 与友好 NPE

> **一句话定位:switch 表达式在这里转正;Record 与 instanceof 模式匹配同车预览(16 转正);"Helpful NullPointerExceptions" 让每个 NPE 都能精确到链路的哪一环;CMS 走完退役流程。**

## 8.1 版本全景

| JEP | 特性 | 一句话影响 |
|-----|------|-----------|
| 361 | **switch 表达式(转正)** | 见 8.2 专题 |
| 359 | Record(预览一) | 16 转正,专题见 10.2 |
| 305 | instanceof 模式匹配(预览一) | 16 转正,专题见 10.3 |
| 368 | 文本块(预览二) | 新增 `\`(续行)与 `\s`(空格)转义 |
| 358 | **Helpful NullPointerExceptions** | NPE 消息精确指出哪个引用为空 |
| 349 | **JFR 事件流** | `RecordingStream` 实时消费 JFR 事件 |
| 370 | Foreign-Memory Access API(孵化一) | FFM 的前身,14~17 孵化、22 转正 |
| 343 | jpackage 打包工具(孵化) | 16 转正(392) |
| 363 | **移除 CMS GC** | 双雄时代正式开始(ZGC/Shenandoah) |
| 366 | 弃用 Parallel Scavenge + Serial Old 组合 | — |
| 362 | 弃用 Solaris/SPARC 移植 | — |

## 8.2 【跨版本演进专题】switch 表达式:12 → 13 → 14

### 8.2.1 演进时间线

| 版本 | 变化 | 为什么要变 |
|------|------|-----------|
| JDK 12(JEP 325,预览一) | 箭头语法 `case X ->`;`switch` 可以有返回值;引入穷举检查 | 旧 switch 三宗罪:**忘写 break 导致贯穿**(40% 的 bug 模式)、各分支共享作用域、不能作为表达式使用。Arrow 语法让"命中即执行、绝不贯穿" |
| JDK 13(JEP 354,预览二) | `break value` 改名 **`yield`** | 收集到的社区反馈:`break` 带值与"提前退出"语义混淆;`yield` 与局部变量冲突更小、语义更清晰 |
| JDK 14(JEP 361,转正) | 定稿;enum 全分支穷举时编译器强制覆盖(无 default 也不报错);null 处理与异常语义明确 | 完成语言标准化流程 |

### 8.2.2 定型后的完整形态(JDK 14+)

```java
public class SwitchDemo {
    enum Shape { TRIANGLE, RECTANGLE, CIRCLE }

    static double area(Shape shape, double a, double b) {
        return switch (shape) {                       // 表达式:直接有返回值
            case TRIANGLE  -> a * b / 2;
            case RECTANGLE -> a * b;
            case CIRCLE    -> {                            // 多语句用块 + yield
                double r = Math.sqrt(a / Math.PI);
                yield Math.PI * r * r;
            }
            // 穷举 enum 所有分支时,连 default 都不需要——编译器帮你查漏
        };
    }
}
```

**为什么值得转正专章**:switch 表达式不只是语法,它是**模式匹配的前哨**——箭头 + 穷举检查正是后来 21 的 switch 模式匹配(15.3)所依赖的语言基础。

## 8.3 Helpful NullPointerExceptions:诊断力小革命

```java
// 14 之前的报错:NullPointerException(不知所云)
// 14 之后(JEP 358,默认开启):
// java.lang.NullPointerException:
//     Cannot invoke "String.length()" because the return value of
//     "com.demo.Order.customer()" is null
```

**原理**:JVM 在抛 NPE 时按字节码重建"是哪个调用/哪个数组为空"的逻辑消息,代价仅在异常路径上,零常态开销。生产定位 NPE 的效率提升立竿见影。

## 8.4 本章小结

14 是"语言新篇正式翻页"的版本:switch 定稿、Record 与 instanceof 模式上路、文本块补齐转义。运行时则开始清场:CMS 移除,GC 叙事完全交给 G1 + ZGC/Shenandoah。

---

# 九、JDK 15(2020-09):文本块转正、密封类登场

> **一句话定位:文本块转正;sealed(预览)开始构建"封闭继承树";两个新 GC 转正为生产可用;Nashorn 走人。**

## 9.1 版本全景

| JEP | 特性 | 一句话影响 |
|-----|------|-----------|
| 378 | **文本块(转正)** | 见 9.2 专题 |
| 360 | sealed 类(预览一) | 17 转正,专题见 11.2 |
| 384 | Record(预览二) | 语义微调(接口可声明 record 等) |
| 371 | **隐藏类(Hidden Classes)** | 框架动态生成的类不可被显式发现/卸载友好 |
| 377/379 | **ZGC / Shenandoah 转正** | 低停顿 GC 生产可用,不再要 `UnlockExperimentalVMOptions` |
| 339 | EdDSA(Ed25519/Ed448) | 现代签名算法 |
| 372 | **移除 Nashorn** | 8 引入的 JS 引擎谢幕,JS 需求交给 GraalJS 等 |
| 374 | 弃用偏向锁并默认关闭 | 偏向锁在高并发与新型 GC 下收益为负,维护成本高 |
| 373 | 重实现 DatagramSocket | 与 13 的 Socket 重实现呼应 |
| 306 | 恢复 always-strict 浮点语义 | 消除 strictfp 历史包袱 |
| 385 | 弃用 RMI Activation | 17 移除(407) |

## 9.2 【跨版本演进专题】文本块:13 → 14 → 15

### 9.2.1 演进时间线

| 版本 | 变化 | 为什么要变 |
|------|------|-----------|
| JDK 13(JEP 355,预览一) | `"""` 三引号、 incidental 缩进剥离、`\` 行尾不换行(隐式) | Java 字符串不支持多行字面量,拼 SQL/JSON/HTML 全靠 `+` 和 `\n`,转义 `\` 一多没法读。文本块以"所见即所得"为目标 |
| JDK 14(JEP 368,预览二) | 新增两个转义:`\` 行尾显式续行、`\s` 显式空格;明确"结尾定界符位置决定剥离缩进" | 社区反馈两个真实痛点:行尾想接下一行但不想换行;行尾空格会被剥掉(如代码生成场景需要保留)。`\` 和 `\s` 让意图显式化 |
| JDK 15(JEP 378,转正) | 定稿 | — |

### 9.2.2 定型后的完整形态(JDK 15+)

```java
String json = """
        {
          "name": "%s",
          "tags": ["jdk", "text-blocks"]\s
        }
        """.formatted("jdk27");          // 15 配套:String.formatted

String prompt = """
        你是一个代码评审助手。\
        请只输出结论与证据。""";           // \ 续行:两行拼成一行且不引入空格

// 文本块内可直接写单个双引号;若内容出现连续三个 ",用 \" 转义或在定界符内断开
String tricky = """
        她说: "你好"
        """;
```

**配套 String API(15)**:`formatted()`、`stripIndent()`、`translateEscapes()`——把文本块在编译期做的"缩进剥离/转义翻译"暴露成运行时方法。String API 的完整演进表见 9.3。

## 9.3 顺手一表:String API 的十年演进(8 → 15)

| 版本 | 新增方法 | 场景 |
|------|----------|------|
| 8 | `join`, `chars()` | 拼接、字符流处理 |
| 9 | (无 API,内部 Compact Strings) | 内存减半 |
| 11 | `isBlank`, `strip*`, `lines`, `repeat` | 文本清洗 |
| 12 | `indent`, `transform` | 缩进调整、链式加工 |
| 15 | `formatted`, `stripIndent`, `translateEscapes` | 文本块配套 |
| 18 | (编码相关,见 12 章 UTF-8) | — |

## 9.4 本章小结

15 是"半成品收割"版本:文本块转正,ZGC/Shenandoah 转正,Record 二预览整装待发。同时它也做减法:Nashorn 移除、偏向锁默认关闭——JVM 开始为"多核 + 现代应用"重塑锁定与脚本策略。

---

# 十、JDK 16(2021-03):Record 与 instanceof 模式匹配转正

> **一句话定位:数据建模三件套的前两件(record、instanceof 模式)转正;JDK 内部强封装改为默认拒绝;Stream 拿到 `toList` 和 `mapMulti`;jpackage 转正。**

## 10.1 版本全景

| JEP | 特性 | 一句话影响 |
|-----|------|-----------|
| 395 | **Record(转正)** | 见 10.2 专题 |
| 394 | **instanceof 模式匹配(转正)** | 见 10.3 专题 |
| 397 | sealed(预览二) | 17 转正 |
| 396 | **JDK 内部强封装默认拒绝** | `sun.misc.Unsafe` 等内部 API 反射访问默认报错(--add-opens 显式放行) |
| 338 | Vector API(孵化一) | 向量化 SIMD 编程,孵化至今(见 20.7) |
| 389 | Foreign Linker API(孵化) | FFM 的"调用本地函数"半边 |
| 392 | jpackage 转正 | 自包含安装包(msi/deb/rpm) |
| 380 | Unix-domain Socket Channel | 本地进程间通信标准化 |
| 387 | Elastic Metaspace | 元空间按需归还内存 |
| 376 | ZGC 并发线程栈处理 | 停顿进一步与栈深解耦 |
| — | `Stream.toList()`、`Stream.mapMulti`、Git/GitHub 迁移(357/369) | 见下 |

## 10.2 【跨版本演进专题】Record:14 → 15 → 16

### 10.2.1 演进时间线

| 版本 | 变化 | 为什么要变 |
|------|------|-----------|
| JDK 14(JEP 359,预览一) | `record Point(int x, int y) {}`:自动生成构造器、访问器、equals/hashCode/toString | "纯数据载体"样板代码占了 Java 代码的巨大篇幅(手写或 Lombok 生成);Java 需要一个**语义化**的官方答案,而不是又一层注解魔法 |
| JDK 15(JEP 384,预览二) | 接口中可以声明 record;record 可以实现接口;放宽本地 record | 让 record 更贴近"随处可用的数据建模"目标(如方法内局部建模、DTO 拆包) |
| JDK 16(JEP 395,转正) | 定稿 | — |

### 10.2.2 定型后的完整形态(JDK 16+)

```java
public record Order(long id, String userId, BigDecimal amount, Status status) {
    // 紧凑构造器:校验逻辑有官方落点
    public Order {
        Objects.requireNonNull(userId);
        if (amount.signum() < 0) throw new IllegalArgumentException("负金额");
    }
    // 可以追加方法/静态工厂,但组件字段永远 final
    public boolean isLarge() { return amount.compareTo(new BigDecimal("10000")) > 0; }
    public static Order draft(long id, String userId) {
        return new Order(id, userId, BigDecimal.ZERO, Status.DRAFT);
    }
}

// record 与模式匹配天生一对(见 15.3):
if (obj instanceof Order(long id, var user, _, Status.PAID)) {
    System.out.println("订单 " + id + " 已支付,买家 " + user);
}
```

**为什么重要**:record = **透明载体**(字段即 API,`x()` 而非 `getX()`),自动获得基于"值语义"的 equals/hashCode;它 + sealed + 模式匹配构成 Java 官方倡导的 **Data-Oriented Programming**。注意边界:record 隐式 final、不能继承别的类(可实现接口)、组件可变性取决于组件自身类型(可变组件仍可变)。

> 源码坐标:`java.base/java/lang/Record.java:90`(`public abstract class Record`,所有 record 的公共父类,JDK 27 源码)。

## 10.3 【跨版本演进专题】instanceof 模式匹配:14 → 16

| 版本 | 变化 | 为什么要变 |
|------|------|-----------|
| JDK 14(JEP 305,预览一) | `if (obj instanceof String s) { s.length(); }` | "类型判断 + 强转 + 起名"三步曲占满屏幕,且强转与判断可能不一致(手滑转错类型) |
| JDK 16(JEP 394,转正) | 定稿,支持模式变量作用域精确到控制流 | 与 record 模式、switch 模式共享同一套"模式"底层概念 |

```java
// 以前
if (o instanceof String) {
    String s = (String) o;
    ...
}
// 16 之后
if (o instanceof String s && s.length() > 3) { ... }   // s 在 && 右侧已可用
```

## 10.4 其他要点

- **强封装默认(396)**:`--illegal-access=deny` 成为默认,17 直接禁止(403)。依赖内部 API 的框架必须 `--add-opens` 显式开洞。
- **`Stream.toList()`**:`collect(Collectors.toList())` 的便捷版,返回**不可修改**列表(注意与 `Collectors.toList()` 的"未承诺可变性"差异);`mapMulti` 提供比 `flatMap` 更省分配的 1→N 展开。

## 10.5 本章小结

16 让"数据导向编程"的三大件凑齐两个(record + instanceof 模式),同时完成两件影响深远的事:内部 API 强封装默认拒绝(升级阻塞点之一)、`Stream.toList` 这类"日用糖"。

---

# 十一、JDK 17(2021-09,LTS):密封类转正、内部强封装定型

> **一句话定位:LTS 三连击的第一棒。sealed 转正补齐数据建模第三件套;JDK 内部彻底"上锁";Security Manager 被宣判弃用;随机数 API 现代化。**

## 11.1 版本全景

| JEP | 特性 | 一句话影响 |
|-----|------|-----------|
| 409 | **sealed 类(转正)** | 见 11.2 专题 |
| 403 | **内部 API 强封装最终化** | `--illegal-access` 选项失效,拒绝即拒绝 |
| 411 | **Security Manager 弃用(forRemoval)** | 见 11.3 |
| 398 | Applet API 弃用(forRemoval) | 26 移除(504) |
| 407 | 移除 RMI Activation | 15 弃用的落地 |
| 410 | 移除 AOT 编译器(jaotc) | 9 引入的 jaotc 无人用;AOT 换了赛道(见 20.4) |
| 356 | 随机数生成器增强(RandomGenerator) | 见 11.4 |
| 406 | switch 模式匹配(预览一) | 21 转正,见 15.3 |
| 412/414 | FFM 孵化四、Vector API 孵化二 | 22 转正 / 持续孵化 |
| 415 | 上下文特定的反序列化过滤器 | 反序列化白名单按上下文定制 |
| 382 | macOS 新渲染管线(Metal) | — |

## 11.2 【跨版本演进专题】sealed:15 → 16 → 17

### 11.2.1 演进时间线

| 版本 | 变化 | 为什么要变 |
|------|------|-----------|
| JDK 15(JEP 360,预览一) | `sealed interface Shape permits Circle, Square` | Java 的继承树是"开放"的,任何人都能 extends/implements。这让"代数数据类型"(有限的、可穷举的变体集合)无法表达,switch 也无法安全穷举 |
| JDK 16(JEP 397,预览二) | 规范细化:sealed 类必须明确所有子类;子类必须 final/ sealed/ non-sealed 三选一 | 收紧语义、消除歧义 |
| JDK 17(JEP 409,转正) | 定稿 | — |

### 11.2.2 定型后的完整形态(JDK 17+)

```java
public sealed interface Shape permits Circle, Square, Triangle {}

public record Circle(double radius) implements Shape {}
public record Square(double side) implements Shape {}
public non-sealed class Triangle implements Shape {}   // non-sealed:重新开放给任意继承
// final/sealed/non-sealed 必选其一,继承树完全显式

static double area(Shape shape) {
    return switch (shape) {              // sealed + record + 模式匹配 = 穷举无 default
        case Circle c    -> Math.PI * c.radius() * c.radius();
        case Square s    -> s.side() * s.side();
        case Triangle t  -> heron(t);      // Triangle 是普通类,所以仍需兜底?不——
        // 注意:non-sealed 变体不参与穷举,这里编译器会要求 default!
        default          -> heron((Triangle) shape);
    };
}
// 若 Triangle 改为 record 并 sealed/final,则可去掉 default,实现真正的编译期穷举检查。
```

**为什么重要**:sealed 把"类层次的设计意图"写进类型系统;与 record、模式匹配三者合体后,Java 拥有了和函数式语言(如 Haskell/Scala 的 ADT)等价的表达能力,而 switch 穷举检查让"新增一个变体,所有处理点编译报错"成为可能。

## 11.3 Security Manager 弃用(411):一次时代判断

**白话**:Security Manager 是 90 年代"Applet 沙箱"时代的产物,试图在 JVM 内做细粒度安全策略。三十年实践证明:**没人用它、成本高、挡不住真正的攻击面**。JDK 17 宣判弃用(12 与 24 之间逐步推进),24 **永久禁用**(486)。替代策略:容器/OS 层隔离 + 现代依赖治理。这一决定与 Applet(26 移除)一起,宣告了"客户端 Java 时代"的遗产全部清零。

## 11.4 RandomGenerator:随机数 API 现代化(356)

```java
RandomGenerator g = RandomGeneratorFactory.of("L64X128MixRandom").create(42);
int[] shuffle = IntStream.range(0, 10).map(i -> g.nextInt(100)).toArray();
// 按可预测性/周期/线程安全性选择算法,而不是只有一个 Random
```

**为什么**:旧 `Random` 是 LCG 算法,周期短、可预测;`SecureRandom` 又太重。JEP 356 引入算法族(`Xoshiro256PP`、`L64X128MixRandom` 等可验证的算法),用统一接口按需挑选。

## 11.5 本章小结

17 是"定规矩"的版本:语言侧 sealed 定稿、强封装最终化;平台侧判了 Security Manager 的死刑。从 8/11 升级到 17 的最大工程量恰恰不是新特性,而是**旧依赖对内部 API 的访问**被拒绝——这也是后来很多团队"卡在 8 上"的技术原因之一。

---

# 十二、JDK 18(2022-03):UTF-8 默认、简易 Web 服务器、finalization 倒计时

> **一句话定位:一个务实的"舒适版本":编码默认 UTF-8(400)、给开发者送一个本地静态 Web 服务器(408)、javadoc 支持代码片段(413);同时 finalization 进入弃用倒计时(421)。**

## 12.1 版本全景

| JEP | 特性 | 一句话影响 |
|-----|------|-----------|
| 400 | **UTF-8 默认** | `file.encoding` 默认值改为 UTF-8 |
| 408 | **简易 Web 服务器** | `java -m jdk.httpserver` 一行起静态站点 |
| 413 | javadoc `{@snippet}` | 官方文档内嵌可编译校验的代码片段 |
| 421 | **finalization 弃用** | 资源清理转向 `AutoCloseable`/`Cleaner` |
| 419 | FFM(预览二) | 22 转正 |
| 420 | switch 模式匹配(预览二) | 21 转正 |
| 417 | Vector API(孵化三) | — |
| 418 | InetAddress 解析 SPI | 主机名解析可插拔 |

## 12.2 UTF-8 默认:一次"消灭 90% 乱码问题"的决定

**白话**:此前 JVM 默认编码跟随操作系统——Windows 中文环境 GBK、老 macOS ANSI,同一份代码跨机跑出乱码是经典事故。18 起 `Charset.defaultCharset()` 恒为 UTF-8,除非显式 `-Dfile.encoding=COMPAT` 退回旧行为。**影响**:跨平台一致性大增;依赖"平台默认编码"的老代码(如 `new FileReader(f)` 不带编码)在非 UTF-8 数据源上需要显式指定编码。

## 12.3 简易 Web 服务器与 javadoc snippet

```bash
# 静态站点一行起,本地调试/局域网共享利器(仅开发用途,无鉴权)
java -m jdk.httpserver -d ./html -p 8000
```

```java
/**
 * {@snippet lang="java":
 *   List.of("a", "b").stream().map(String::toUpperCase).toList();
 * }
 */
```

## 12.4 finalization 弃用(421):十年债的偿还计划

**白话**:`finalize()` 设计初衷是"GC 时兜底释放资源",实际效果是**执行时机不定、可能不执行、还能复活对象**,长期是安全漏洞(在 finalize 中重新引用)与内存泄漏来源。JDK 18 弃用,推荐路径:`try-with-resources`(首选)→ `PhantomReference`/`Cleaner`(兜底)。到 27 仍未移除,但方向明确。

## 12.5 本章小结

18 没有惊天动地的新特性,却把"开发者日常体验"拧紧了一圈:编码默认、文档片段、本地 HTTP 服务。它也是最后一个"纯补糖"版本——接下来两个版本,虚拟线程和 FFM 将改变 Java 并发与互操作的地基。

---

# 十三、JDK 19(2022-09):虚拟线程初见

> **一句话定位:Project Loom 十年磨一剑的第一刀:虚拟线程预览;结构化并发孵化;Record 模式预览;FFM 转正前最后一轮孵化收口。**

## 13.1 版本全景

| JEP | 特性 | 一句话影响 |
|-----|------|-----------|
| 425 | **虚拟线程(预览一)** | 21 转正,专题见 15.2 |
| 428 | **结构化并发(孵化一)** | 并发任务按"任务树"治理,预览至今,见 18.3 |
| 405 | Record 模式(预览一) | 21 转正,见 15.3 |
| 427 | switch 模式匹配(预览三) | 21 转正 |
| 424 | FFM(预览一) | 22 转正 |
| 426 | Vector API(孵化四) | — |
| 422 | Linux/RISC-V 移植 | 开源芯片生态 |

## 13.2 虚拟线程首秀(预览形态)

```java
// 19 预览:创建方式与最终版已基本一致
Thread.startVirtualThread(() -> {
    var resp = httpClient.send(req, BodyHandlers.ofString()); // 阻塞?无所谓
});
```

**白话**:传统平台线程 1:1 映射 OS 线程,内存(MB 级栈)与调度成本高,并发上限几千。虚拟线程由 JVM 调度,阻塞时自动"卸载"载体线程,可百万级并发。**完整原理与"为什么"见 15.2 专题**。

## 13.3 本章小结

19 的历史意义大于特性数量:Java 并发的下一幕从这里开场。同车预览的 Record 模式则宣告:模式匹配的拼图(record 模式)也上路了。

---

# 十四、JDK 20(2023-03):蛰伏的一版,预览三件套推进

> **一句话定位:没有转正新特性的版本(6 个 JEP 全是推进):Record 模式二预览、switch 模式四预览、FFM 二预览、Scoped Values 孵化、结构化并发二孵化。**

## 14.1 版本全景

| JEP | 特性 | 状态 |
|-----|------|------|
| 429 | **Scoped Values(孵化一)** | 25 转正,专题见 19.2 |
| 432 | Record 模式(预览二) | 支持嵌套解构 |
| 433 | switch 模式匹配(预览四) | — |
| 434 | FFM(预览二) | — |
| 436 | Vector API(孵化五) | — |
| 437 | 结构化并发(孵化二) | — |

## 14.2 为什么会有一个"没有新特性"的版本

**白话**:六个月节奏的代价是需要"消化周期"。20 的全部工作都在打磨 19 上车、21 将转正的预览三件套(Record 模式/switch 模式/FFM)以及 Loom 家族(SC/Scoped Values)。对学习者而言,20 可以快进,但要知道:**21 的"大丰收"是在这里备好的种**。

## 14.3 本章小结

记住 Scoped Values(429)在这版孵化即可——它是 `ThreadLocal` 的现代替代品,最终 25 转正(见 19.2)。

# 十五、JDK 21(2023-09,LTS):虚拟线程转正、模式匹配三件套、Sequenced Collection

> **一句话定位:近年最重的 LTS。虚拟线程转正宣告"线程廉价"时代开启;switch 模式与 record 模式转正,数据导向编程闭环;SequencedCollection 补上集合家族最后的语义空洞;分代 ZGC 把 GC 推进新阶段。**

## 15.1 版本全景

| JEP | 特性 | 一句话影响 |
|-----|------|-----------|
| 444 | **虚拟线程(转正)** | 见 15.2 专题 |
| 440/441 | **Record 模式 / switch 模式匹配(转正)** | 见 15.3 专题 |
| 431 | **SequencedCollection** | 见 15.4 专题 |
| 439 | **分代 ZGC** | 见 15.5 专题 |
| 453 | 结构化并发(预览一) | 见 18.3 |
| 446 | Scoped Values(预览一) | 25 转正,见 19.2 |
| 430 | String Templates(预览) | **23 被撤回**,教训见 17.2 |
| 445 | 未命名类与 main 方法(预览一) | 25 转正(512),见 19.3 |
| 443 | 未命名变量与模式(预览) | 22 转正(456),见 16.2 |
| 452 | 密钥封装机制 KEM API | 后量子密码前置工程 |
| 449 | 弃用 Windows 32 位 | 23 移除(479) |
| 451 | 准备禁止动态加载 Agent | 运行时字节码增强开始收紧 |

## 15.2 【跨版本演进专题】虚拟线程:19 → 20 → 21(+ 24 补丁)

### 15.2.1 演进时间线

| 版本 | 变化 | 为什么要变 |
|------|------|-----------|
| JDK 19(JEP 425,预览一) | `Thread.ofVirtual()`、`Executors.newVirtualThreadPerTaskExecutor()` | 服务器应用"一请求一线程"的写法被线程成本锁死(千级并发上限),响应式(CompletableFuture/Reactive)虽能扛高并发但代码复杂、栈可读性差。Project Loom 的答案是:**让线程便宜**,阻塞式代码直接百万并发 |
| JDK 20 | 无独立 JEP,随预览打磨 | 收集反馈、修语义 |
| JDK 21(JEP 444,转正) | 定稿;虚拟线程默认**不作为守护线程**(与预览相反,避免与平台线程行为差异) | — |
| JDK 24(JEP 491) | **消除 `synchronized` 钉住(pinning)** | 21 的实现中,虚拟线程在 `synchronized` 块内阻塞会"钉住"载体线程无法卸载,高锁场景性能退化甚至饿死(需 `-Djdk.tracePinnedThreads` 诊断、改 `ReentrantLock` 绕开)。24 重写监视器实现,彻底解决——虚拟线程全量无障碍铺开的最后一块砖 |

### 15.2.2 定型后的完整形态(JDK 21+)

```java
// 1. 一请求一线程直接回归:每个任务一个(虚拟)线程,不要池化虚拟线程!
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    for (int i = 0; i < 100_000; i++) {
        int id = i;
        executor.submit(() -> {
            var resp = httpClient.send(buildReq(id), HttpResponse.BodyHandlers.ofString());
            process(resp);          // 内部随便阻塞:sleep/IO/锁,都会自动卸载
            return null;
        });
    }
}   // try-with-resources:等全部任务结束

// 2. 低层 API
Thread vt = Thread.ofVirtual().name("worker-", 0).start(() -> handle(req));
boolean isVt = vt.isVirtual();

// 3. 诊断
// $ jcmd <pid> Thread.dump_to_file -format=json dump.json   (含虚拟线程)
```

**原理白话**:虚拟线程是 JVM 管理的用户态线程,以"延续(continuation)"形式存储栈帧于堆中;调度器默认是一个 **ForkJoinPool(FIFO 模式)**,其工作线程即"载体线程"(数量 ≈ CPU 核数)。虚拟线程执行到阻塞点(网络 IO、`sleep`、`BlockingQueue` 等)时,把栈复制回堆、**让出载体线程**;就绪后再找空闲载体恢复。于是"百万阻塞任务"只是堆上百万个对象,而非百万个 OS 线程。

**使用纪律**(官方反复强调):
- 虚拟线程是**廉价且近乎无限**的,永远不要池化(`ThreadLocal` 相关成本也随复用放大);
- 适合 **IO 密集**;CPU 密集并行请用 `parallelStream`/固定平台线程池;
- 少量" pinned"场景(24 已解决 `synchronized`,遗留 `native` 方法内阻塞)可忽略;
- 它不替代响应式编程的**背压**,只替代"为吞吐而被迫异步"的代码。
- 深入解析见 [Java并发.md](Java并发.md)。

> 源码坐标:`java.base/java/lang/Thread.java:1437`(`startVirtualThread`,JDK 27 源码);虚拟线程调度核心在 `java.base/java/lang/VirtualThread.java`。

## 15.3 【跨版本演进专题】模式匹配全景:16 → 21

### 15.3.1 演进时间线

| 版本 | 变化 | 为什么要变 |
|------|------|-----------|
| 14 预览 / **16 转正** | instanceof 模式(JEP 305/394) | 第一块积木:判断 + 绑定合一 |
| 17 预览(406)→ 21 转正(**441**) | **switch 模式匹配**:case 可以是任意类型模式(类型、record 模式、常量、null),支持 `when` 守卫与穷举检查 | 旧 switch 只能对"枚举/数字/字符串"分发,对象分发只能 if-else 链;配合 sealed 后可编译期穷举,"新增变体忘改分支"直接编译报错 |
| 19 预览(405)→ 21 转正(**440**) | **Record 模式**:对 record 解构赋值,支持嵌套 | `instanceof Order o; o.id()` 的二次访问变成 `instanceof Order(long id, ...)` 一步到位;嵌套后可整棵解构数据树 |
| 21 预览(443)→ 22 转正 | 未命名变量 `_` | 解构时"这个组件我不关心"有了正式写法 |
| 23 起预览(455/488/507/530/532) | 原始类型参与模式/instanceof/switch | 补齐最后一块:基本类型与引用类型统一的模式语法,**27 仍在预览,未定型** |

### 15.3.2 定型后的完整形态(JDK 21+)

```java
sealed interface Event permits OrderPlaced, PaymentDone, Timeout {}
record OrderPlaced(long orderId, String userId, Money amount) implements Event {}
record PaymentDone(long orderId, String channel) implements Event {}
record Timeout(long orderId) implements Event {}
record Money(String amount, String currency) {}

static String render(Event e) {
    return switch (e) {                                  // switch 对"任意对象"分发
        case OrderPlaced(long id, var user, Money amt)   // record 解构 + var 混用
                when "CNY".equals(amt.currency())        // when 守卫(带守卫的 case 不参与穷举!)
                -> "订单 %d(用户 %s)金额 %s".formatted(id, user, amt.amount());
        case OrderPlaced(long id, _, _)                  // 守卫 case 之后需要显式兜底
                -> "订单 %d 已下单".formatted(id);
        case PaymentDone(long id, String ch) when ch.startsWith("ALIPAY")
                -> "订单 %d 支付宝完成".formatted(id);
        case PaymentDone(long id, _)                     // 未命名变量:渠道不关心
                -> "订单 %d 已支付".formatted(id);
        case Timeout t                                   -> "订单 %d 超时".formatted(t.orderId());
    };   // sealed + 每个变体至少一个无守卫 case:无 default 也编译通过;
         // 新增变体 → 所有 switch 报错,漏改无处藏身
}
```

**为什么是"闭环"**:record(数据)+ sealed(变体封闭)+ switch 模式(穷举消费)三者共同消灭了 Java 处理"结构化数据 + 多态行为"时的最后一大坨样板代码。这是 8 的 Lambda 之后,Java 语言最深的一次范式扩展。

## 15.4 【跨版本演进专题】集合家族:8 → 9 → 10 → 21

| 版本 | 变化 | 为什么要变 |
|------|------|-----------|
| JDK 8 | Map 默认方法(`getOrDefault/computeIfAbsent/merge` 等)、`ConcurrentHashMap` 增强 | 集合接口首次获得"带实现的默认方法"能力 |
| JDK 9 | `List.of/Set.of/Map.of` 不可变工厂方法 | 创建小型不可变集合的样板代码太多;且 `Collections.unmodifiableList(new ArrayList<>(...))` 语义绕 |
| JDK 10 | `List.copyOf`、`Collectors.toUnmodifiable*` | 与 Stream/旧集合互转统一为"不可变"约定 |
| **JDK 21(JEP 431)** | **`SequencedCollection`/`SequencedSet`/`SequencedMap`**:`getFirst/getLast/addFirst/addLast/removeFirst/removeLast/reversed()` | 有序性(首/尾/顺序)散落在各接口:List 有下标,Deque 有首尾,LinkedHashSet 有插入序,但**没有统一接口**;`reversed()` 更是一直没有统一答案。431 把"有序"抽成接口,并让 `List`/`Deque`/`LinkedHashSet`/`SortedMap` 等全员实现 |

```java
List<String> list = new ArrayList<>(List.of("a", "b", "c"));
list.getFirst();        // a(21 之前:list.get(0),Deque 才有 getFirst)
list.reversed();        // [c, b, a] —— 返回可写视图,反向修改互见
SequencedMap<String, Integer> sm = new LinkedHashMap<>();
sm.putFirst("k", 1); sm.reversed().forEach((k, v) -> ...);
```

> 源码坐标:`java.base/java/util/SequencedCollection.java:78`(接口声明)、`:90`(`reversed()`,JDK 27 源码)。

## 15.5 【跨版本演进专题】GC 全景:8 → 21

| 版本 | 变化 | 为什么要变 |
|------|------|-----------|
| JDK 8 | Parallel GC 默认;G1 可用(7u4) | 多核大堆时代,吞吐优先的 Parallel 已到极限 |
| JDK 9(248) | **G1 成为默认** | 大堆上可预测停顿(区域化分代收集)比纯吞吐更重要 |
| 9(291)/14(363) | CMS 弃用 → 移除 | 基于标记-清除的 CMS 碎片化 + 维护成本,被 G1 全面替代 |
| JDK 10(307) | G1 Full GC 并行化 | 补齐 G1 最痛的串行 Full GC |
| JDK 11(333) | **ZGC(实验)**;318 Epsilon | 堆向 TB 级走,停顿目标进入亚毫秒级;染色指针 + 读屏障 |
| JDK 12(189) | **Shenandoah(实验)** | RedHat 系低停顿实现:转发指针 + 读屏障,与 ZGC 双雄竞争 |
| JDK 12(344/346) | G1 可中止混合回收、空闲归还内存 | 停顿达标优先于回收进度 |
| JDK 13(351) | ZGC 归还未用内存 | 云环境按用量付费 |
| JDK 15(377/379) | **ZGC/Shenandoah 转正** | 技术验证完成 |
| JDK 16(376) | ZGC 并发处理线程栈 | 停顿与线程数/栈深解耦 |
| **JDK 21(439)** | **分代 ZGC(ZGenerational)** | 非分代 ZGC 的全堆扫描让"写屏障成本"居高不下;对象"朝生夕死"的普遍规律要求分代——分代后吞吐/内存开销大幅下降,GC 全景收敛为:G1(均衡)/ 分代 ZGC(极限停顿)/ 分代 Shenandoah(24 实验、25 转正,521) |
| 22~27 | 423 G1 区域固定、475 G1 后期屏障展开、474 ZGC 分代默认、490 移除非分代 ZGC、521 分代 Shenandoah 转正、522 G1 降同步开销、**523 G1 全环境默认(27)**、**534 紧凑对象头默认(27)** | 见 16~21 章各自小节 |

**一句话总结**:8 的选择题是"Parallel vs CMS";27 的选择题是"G1(默认)vs 分代 ZGC/Shenandoah(极致停顿)"——十五年 GC 竞赛的结果是停顿从"百毫秒"进入"亚毫秒"。

## 15.6 本章小结

21 是当之无愧的里程碑:并发模型革命(虚拟线程)、语言范式闭环(模式匹配)、集合语义补全(SequencedCollection)、GC 进入分代新时代。**如果从 8 直接跳 21,这四个专题就是你要补的全部课件的目录。**

# 十六、JDK 22(2024-03):FFM API 转正、未命名变量

> **一句话定位:Java 与"外部世界"的关系重新定义——FFM API 转正,安全操作堆外内存、直接调用本地函数;未命名变量 `_` 转正;G1 获得区域固定;Gatherers 首次预览。**

## 16.1 版本全景

| JEP | 特性 | 一句话影响 |
|-----|------|-----------|
| 454 | **FFM API(转正)** | 见 16.3 专题 |
| 456 | **未命名变量与模式(转正)** | 见 16.2 |
| 423 | G1 区域固定(Region Pinning) | JNI 临界区内 G1 不再强制移动对象 |
| 447 | 构造器内提前语句(预览一) | 25 转正(513),见 19.4 |
| 461 | Stream Gatherers(预览一) | 24 转正(485),专题见 18.2 |
| 457 | Class-File API(预览一) | 24 转正(484),见 18.5 |
| 458 | 多文件源码程序直接运行 | `java Main.java a b` 引用同目录其他类 |
| 459 | String Templates(预览二) | **最后一次出现,23 撤回** |
| 462/464 | 结构化并发 / Scoped Values(二预览) | — |
| 460/463 | Vector API(孵化七)/ 隐式声明类(二预览) | — |

## 16.2 未命名变量 `_`(21 预览 → 22 转正)

```java
// 9 起 `_` 就不能当标识符,22 里它有了正式身份:"这里有个东西,但我不用"
for (var _ : list) count++;                          // 增强循环只要次数
try { ... } catch (NumberFormatException _) { ... }  // 异常对象不关心
switch (shape) { case Circle(radius, _) -> ...; }    // record 解构中忽略组件
record Point(int x, int y) {}
static boolean origin(Point p) { return p instanceof Point(0, 0); }
```

**为什么**:模式匹配普及后,"必须给不用的东西起名"成了新噪音(`ignored`、`unused` 满天飞);`_` 让"刻意忽略"在代码里自明。

## 16.3 【跨版本演进专题】FFM API:14 → 22

### 16.3.1 演进时间线

| 版本 | 变化 | 为什么要变 |
|------|------|-----------|
| 14 孵化(370)→ 15(383)→ 16(393)→ 17(412) | Foreign-Memory Access API → 合并 Foreign Linker API,`jdk.incubator.foreign` | 两大历史顽疾:**堆外内存靠 `ByteBuffer`(容量 2G 上限)或 `Unsafe`(无生命周期管理、易 UAF)**;**调本地库靠 JNI(样板代码多、`GetObjectClass` 式胶水、易崩 JVM)**。Panama 项目给出统一答案:安全(受 JVM 管控)+ 高效(接近 C 性能) |
| 18(419)→ 19 预览(424)→ 20(434)→ 21(442) | API 迁入 `java.lang.foreign`;`MemorySegment`/`Arena`/`Linker` 定型 | 多轮孵化收集反馈:生命周期抽象从 `ResourceScope` 演化为 **Arena(竞技场)**——"一组内存段共享生命周期,关闭即全释放",与 try-with-resources 完美咬合 |
| **22 转正(454)** | `java.lang.foreign` 定稿 | — |
| 23(471)/ 24(498) | `sun.misc.Unsafe` 记忆访问方法弃用 → 实际调用开始打警告 | 旧世界退场的配套:官方明确"迁移到 FFM"是唯一出路 |

### 16.3.2 定型后的完整形态(JDK 22+)

```java
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

// 1. 堆外内存:Arena 管生命周期,MemorySegment 是"带边界的指针"
try (Arena arena = Arena.ofConfined()) {
    MemorySegment seg = arena.allocate(1024, 8);       // 1KB,8 字节对齐
    seg.setAtIndex(ValueLayout.JAVA_INT, 0, 42);
    int x = seg.getAtIndex(ValueLayout.JAVA_INT, 0);
}   // 块结束自动释放——C 的 malloc/free 语义 + Java 的确定性清理

// 2. 调本地库:一行 downcall 取代整套 JNI 胶水
Linker linker = Linker.nativeLinker();
MethodHandle strlen = linker.downcallHandle(
        linker.defaultLookup().find("strlen").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
try (Arena arena = Arena.ofConfined()) {
    MemorySegment str = arena.allocateFrom("hello, panama");
    long len = (long) strlen.invoke(str);              // 13
}
```

**为什么是里程碑**:JNI 不再是调用本地代码的唯一路径;`Unsafe` 的两大用途(堆外内存 + CAS)分别由 FFM 与 VarHandle(9)接管后,"去 Unsafe 化"路线图收尾。**注意**:FFM 操作的是堆外内存,GC 不管它——Arena 就是你的"手动挡";另外 24(JEP 472)起调用受限方法(downcall 等)默认打警告、未来默认拒绝,生产需 `--enable-native-access=ALL-UNNAMED` 显式声明。

## 16.4 本章小结

22 收割了 14~21 七轮孵化:FFM 转正意味着 Java 从此有了"安全版 Unsafe + 无胶水 JNI"。语言侧 `_` 转正、Gatherers/Class-File API 上车孵化,为 24 的双转正铺路。

---

# 十七、JDK 23(2024-09):String Templates 的撤回教训与 Unsafe 倒计时

> **一句话定位:一个"做减法"的版本:预览特性 String Templates 被正式撤回(预览机制第一次动真格);Unsafe 记忆访问方法开始弃用;ZGC 分代成为默认;javadoc 支持 Markdown。**

## 17.1 版本全景

| JEP | 特性 | 一句话影响 |
|-----|------|-----------|
| 455 | 原始类型模式(预览一) | 27 仍在预览(532) |
| 466 | Class-File API(预览二) | 24 转正 |
| 467 | **Markdown javadoc** | 文档注释可用 `//`/`**强调**` 语法 |
| 469 | Vector API(孵化八) | — |
| 471 | **弃用 `sun.misc.Unsafe` 记忆访问方法(forRemoval)** | 24 开始运行时警告(498),FFM 迁移进行时 |
| 473 | Stream Gatherers(预览二) | 24 转正 |
| 474 | **ZGC:分代模式成为默认** | 分代 ZGC 的一统 |
| 476 | 模块导入声明(预览一) | 25 转正(511) |
| 477 | 隐式声明类(预览三) | 25 转正(512) |
| 480/481 | 结构化并发 / Scoped Values(三预览) | — |
| 482 | 灵活构造器(预览二) | 25 转正(513) |

## 17.2 【教训专题】String Templates:21 → 22 → 撤回

| 版本 | 变化 | 原因 |
|------|------|------|
| 21(430,预览) | `"\(expr)"` 插值 + 模板处理器 `STR."..."` | 字符串插值是社区呼声最高的语言特性之一 |
| 22(459,预览二) | 微调 | — |
| **23(未继续)** | **从 JDK 移除,官方宣布撤回重设计** | 官方博客明确:现有设计"不够好"——模板处理器 + 插值的组合引入了**不必要的灵活性**(插值默认 unsafe、处理器生态会碎片化),且与"字符串即常量"的语言哲学有张力;宁可撤回也不带病转正 |

**启示**:预览机制不是"转正倒计时",而是**设计评审的一部分**。撤回一个热门特性,恰恰说明该机制在保护 Java 二十年不变的兼容性承诺。后续版本可能以全新形态(非插值语法)回归,但不承诺时间表。

## 17.3 Unsafe 倒计时与 ZGC 分代默认

- **`sun.misc.Unsafe` 记忆访问方法弃用(471)**:23 起 javadoc 标记 forRemoval,24(498)起**实际调用打运行时警告**。迁移目标:堆外内存 → FFM(16.3);CAS/字段访问 → VarHandle(9)。Hadoop/Netty/Kryo 等生态在 2024~2026 陆续完成迁移。
- **ZGC 分代默认(474)**:`-XX:+UseZGC` 即分代 ZGC;非分代模式还可用(24 移除)。配合 21 的 439,GC 选项实际收敛。

## 17.4 本章小结

23 的主题是"治理":治理预览特性的质量(String Templates 撤回)、治理历史包袱(Unsafe)、治理 GC 选项(ZGC 分代唯一化)。它是为 24 这个"24 个 JEP 的大版本"清场的版本。

---

# 十八、JDK 24(2025-03):Class-File API / Gatherers 转正、AOT 加速起飞

> **一句话定位:24 个 JEP 的超重版本:Stream Gatherers 与 Class-File API 转正;AOT 类加载落地;紧凑对象头实验;结构化并发推倒重来(Joiner 模型);Security Manager 永久禁用。**

## 18.1 版本全景

| JEP | 特性 | 一句话影响 |
|-----|------|-----------|
| 484 | **Class-File API(转正)** | 见 18.5 |
| 485 | **Stream Gatherers(转正)** | 见 18.2 专题 |
| 483 | **AOT 类加载与链接** | 见 18.4 |
| 450 | 紧凑对象头(实验) | 27 默认,见 21.3 |
| 499 | **结构化并发(四预览,重新设计)** | 见 18.3 专题 |
| 486 | **Security Manager 永久禁用** | 17(411)弃用的终点 |
| 487 | Scoped Values(四预览) | 25 转正 |
| 490 | ZGC:移除非分代模式 | 分代唯一 |
| 491 | **虚拟线程消除 synchronized 钉住** | 见 15.2 |
| 404 | 分代 Shenandoah(实验) | 25 转正(521) |
| 496/497 | 后量子密码:ML-KEM / ML-DSA | NIST 标准的 Java 实现 |
| 492 | 灵活构造器(三预览) | 25 转正 |
| 488/489 | 原始类型模式(二预览)/ Vector(孵化九) | — |
| 493 | 无 JMOD 链接运行时镜像 | 定制运行时不需要 jmods 目录 |
| 494/495 | 模块导入(二预览)/ 简单源文件(四预览) | 25 转正 |
| 478 | KDF API(预览) | 25 转正(510) |
| 472 | 准备限制 JNI 使用 | 运行时警告铺设 |
| 498 | Unsafe 记忆访问调用警告 | — |
| 479 | 移除 Windows 32 位 | — |

## 18.2 【跨版本演进专题】Stream 与 Gatherers:8 → 9 → 16 → 24

### 18.2.1 演进时间线

| 版本 | 变化 | 为什么要变 |
|------|------|-----------|
| JDK 8 | Stream 全家桶:`filter/map/flatMap/sorted/collect` 等 | 声明式流水线诞生,但中间操作是**封闭集合**——"滑动窗口、有状态去重、局部折叠"写不了,只能落回 for 循环 |
| JDK 9 | `takeWhile/dropWhile/iterate(seed,hasNext)/ofNullable`;`Collectors.flatMapping/filtering` | 补齐"截断/条件迭代"等基础中间操作,但自定义机制仍无 |
| JDK 16 | `Stream.toList()`、**`mapMulti`** | `flatMap` 的每元素都建 Stream,分配开销大;`mapMulti` 用"直接 push 到下游"优化 1→N 展开。可这仍是点状补丁 |
| **JDK 22~23 预览 → 24 转正(485)** | **`Stream.gather(Gatherer)`**:用户自定义"有状态中间操作"成为一阶 API;内置 `Gatherers.windowFixed/windowSliding/fold/scan/mapConcurrent` | 官方承认"中间操作不可能穷尽所有需求",给出统一扩展点:`Gatherer` 的四要素(integrator 累积器 / initializer 状态工厂 / combiner 并行合并 / finisher 收尾)完整覆盖任意有状态变换 |

### 18.2.2 定型后的完整形态(JDK 24+)

```java
// 内置 Gatherers:过去要写十行循环的场景
Stream.of(1,2,3,4,5,6).gather(Gatherers.windowFixed(3)).toList();
//   [[1,2,3],[4,5,6]]     定长窗口
Stream.of(1,2,3,4).gather(Gatherers.windowSliding(2)).toList();
//   [[1,2],[2,3],[3,4]]   滑动窗口
Stream.of(1,2,3,4).gather(Gatherers.fold(() -> 0, Integer::sum)).toList();
//   [10]                  有状态折叠
Stream.of(1,2,3).gather(Gatherers.scan(() -> 0, Integer::sum)).toList();
//   [1,3,6]               前缀累积(初始状态只作累加起点,不会被发出)
list.stream().gather(Gatherers.mapConcurrent(8, this::fetch))  // 并发映射(虚拟线程)
    .toList();

// 自定义 Gatherer:实现一个"去相邻重复"
Gatherer<Integer, ?, Integer> distinctAdjacent =
    Gatherer.ofSequential(                               // (初始状态, 累积器)
        () -> (Integer) null,                            // 上一个元素作为状态
        (prev, elem, downstream) -> {
            if (!Objects.equals(prev, elem)) return downstream.push(elem);
            return true;
        });
Stream.of(1,1,2,2,3,1).gather(distinctAdjacent).toList();  // [1,2,3,1]
```

> 源码坐标(JDK 27):`java.base/java/util/stream/Gatherer.java:199`(接口)、`java.base/java/util/stream/Gatherers.java:87`(windowFixed)、`:176`(windowSliding)、`:263`(fold)、`:310`(scan)、`:350`(mapConcurrent);`java.base/java/util/stream/Stream.java:1099`(`gather`)。

**为什么值得转正专章**:Gatherers 与 21 的模式匹配一样,是把"语言/库从封闭走向开放"的一步——此后流式数据处理的自定义变换有了官方插座,`Collectors` 时代的"收集器魔法"被大大稀释。

## 18.3 【专题】结构化并发重新设计:19 → 24(Joiner 模型)

**白话**:结构化并发要解决的是"并发任务的生命周期失控":fork 出去的子任务,谁负责取消?超时了谁停?异常了谁收尸?它的答案是**任务树**:子任务的生命周期严格嵌套在父作用域内,作用域退出 = 全部收束。

| 版本 | 变化 | 为什么要变 |
|------|------|-----------|
| 19/20 孵化(428/437) | `new StructuredTaskScope.ShutdownOnFailure()`,先 fork 后 join,`throwIfFailed()` | 初版概念验证:scope + 关闭时强制收束 |
| 21 预览(453)~ 23 预览三 | API 打磨 | 发现初版 API" fork/join 顺序、异常策略"组合过于松散,易用错 |
| **24 预览四(499):重新设计** | **`StructuredTaskScope.open()` + `Joiner` 策略对象**:`open()` 默认"全部成功或抛 ExecutionException";`open(Joiner.anySuccessfulOrThrow())` 任一成功;`open(Joiner.awaitAll())` 全等;`allUntil(predicate)` 自定义取消条件;`join()` 返回值直接携带结果 | 把"等待策略"从隐式约定变成**显式策略参数**,API 面更小、更难用错;join 的返回值直接是策略产物,少一次 get |
| 25(505)/ 26(525)/ **27(533)** | 第五/六/七次预览 | 仍在打磨,**截至 27 未转正** |

```java
// JDK 24+ 形态(27 仍需 --enable-preview;Subtask/Joiner 是 StructuredTaskScope 的嵌套类型)
<T> List<T> gatherAll(List<Callable<T>> tasks) throws Exception {
    try (var scope = StructuredTaskScope.open()) {              // 默认:全成功或抛
        List<Subtask<T>> handles = tasks.stream().map(scope::fork).toList();
        scope.join();                                            // 任一失败 → 其余被取消,抛 ExecutionException
        return handles.stream().map(Subtask::get).toList();
    }
}

// 任一成功(对冲多个镜像服务):拿第一个成功结果,取消其余
String bestMirror() throws Exception {
    try (var scope = StructuredTaskScope.open(Joiner.<String>anySuccessfulOrThrow())) {
        scope.fork(() -> fetchFromMirrorA());
        scope.fork(() -> fetchFromMirrorB());
        return scope.join();                                     // join 直接返回第一个成功的结果
    }
}
```

> 源码坐标(JDK 27):`java.base/java/util/concurrent/StructuredTaskScope.java:1268`(`open()`)、`:572`(`Joiner` 接口)、`:750/839/931`(allSuccessfulOrThrow/anySuccessfulOrThrow/awaitAllSuccessfulOrThrow)、`:1344/1367`(`fork`)。
> 时间线将持续更新:见 21.4(27 的第七预览)。

## 18.4 AOT 类加载与链接(483):启动优化的新引擎

```bash
# 一次运行记录 → 生成 AOT 缓存;后续启动直接映射
java -XX:AOTCacheOutput=app.aot -cp app.jar com.shop.Main
java -XX:AOTCache=app.aot -cp app.jar com.shop.Main      # 启动显著提速
```

**白话**:CDS 归档只搬"类元数据";483 把**类加载与链接的执行结果**(校验过的字节码、方法 profile)也提前做好。这是 10/13 的 CDS 故事(见 20.4 全景)在"启动飞轮"上的新一圈。

## 18.5 Class-File API(22→23 预览,24 转正)

**白话**:ASM/ByteBuddy 等字节码库必须"追着每个 JDK 新 class 文件版本适配";JDK 24 把 class 文件的解析/生成/转换做成**官方 API**(`java.lang.classfile`),随 JDK 同步演进。框架/工具(AOT、javac 内部、字节码增强)从此有标准底座。

## 18.6 本章小结

24 是"铺基建"的版本:字节码 API 官方化、Stream 可扩展化、AOT 提速、虚拟线程扫尾(synchronized 钉住)、Security Manager 终结。结构化并发的推倒重来则说明:**好的 API 是改出来的**。

# 十九、JDK 25(2025-09,LTS):紧凑源文件、Scoped Values 转正、构造器解放

> **一句话定位:最新 LTS。"让 Java 更好学"的三件套(紧凑源文件、模块导入、灵活构造器)转正;Scoped Values 转正终结 ThreadLocal 时代;KDF 与紧凑对象头、分代 Shenandoah 落地;JFR 三连发。**

## 19.1 版本全景

| JEP | 特性 | 一句话影响 |
|-----|------|-----------|
| 512 | **紧凑源文件与实例 main 方法(转正)** | 见 19.3 专题 |
| 511 | **模块导入声明(转正)** | `import module java.base;` |
| 513 | **灵活构造器体(转正)** | super() 之前可以有语句了,见 19.4 |
| 506 | **Scoped Values(转正)** | 见 19.2 专题 |
| 510 | **KDF API(转正)** | 密钥派生(HKDF 等)标准化,见 19.5 |
| 502 | Stable Values(预览一) | **26 更名 Lazy Constants(526)**,见 20.5 |
| 505 | 结构化并发(五预览) | 见 18.3 |
| 507 | 原始类型模式(三预览) | — |
| 508 | Vector API(孵化十) | — |
| 509/518/520 | **JFR 三连**:CPU 时间采样(实验)/ 协作式采样 / 方法计时与跟踪 | 生产级性能剖析 |
| 519 | **紧凑对象头(转正)** | 27 默认开启,见 21.3 |
| 521 | **分代 Shenandoah(转正)** | GC 双雄都进入分代时代 |
| 514/515 | AOT 命令行简化 / AOT 方法画像 | 见 20.4 |
| 503 | 移除 32 位 x86 移植 | — |
| 470 | PEM 编码(预览一) | 27 三预览(538) |
| 503 | 移除 32 位 x86 移植 | — |

## 19.2 【跨版本演进专题】Scoped Values:20 → 25

### 19.2.1 演进时间线

| 版本 | 变化 | 为什么要变 |
|------|------|-----------|
| JDK 20 孵化(429) | `ScopedValue` 概念上线 | `ThreadLocal` 三宗罪:**可变性**(任何代码随手 set 污染下游)、**无界生命周期**(忘 remove 泄漏)、**继承成本**(InheritableThreadLocal 复制所有条目)。虚拟线程百万并发下,这三个问题同时被放大 |
| 21 预览(446)→ 22(464)→ 23(481)→ 24(487) | 四轮预览打磨:`where(...).run/call` 链式绑定、不可变、限定作用域、与虚拟线程零成本共享 | 每轮调整都围绕"更难用错" |
| **JDK 25 转正(506)** | `ScopedValue.where(K, v).run(op)` / `.call(op)`;`CallableOp` 抛受检异常 | 定稿 |

### 19.2.2 定型后的完整形态(JDK 25+)

```java
private static final ScopedValue<User> CURRENT_USER = ScopedValue.newInstance();

void handle(Request req) throws Exception {
    ScopedValue.where(CURRENT_USER, authenticate(req))
               .call(() -> controller.serve(req));       // 绑定只在 call 的作用域内有效
}
// 任意深层调用:
User u = CURRENT_USER.get();      // 未绑定时 get() 抛 NoSuchElementException(显式失败)
boolean bound = CURRENT_USER.isBound();
```

**与 ThreadLocal 的本质差异**:
1. **不可变**:绑定后值不可改,想"重绑"是创建新作用域;
2. **有界生命周期**:离开 `run/call` 自动失效,不存在忘 remove;
3. **与虚拟线程亲和**:结构化并发的子任务继承父作用域绑定,无需逐线程复制;
4. **性能**:绑定/读取路径为 Loom 优化,百万虚拟线程下远胜 ThreadLocal 复制。

> 源码坐标(JDK 27):`java.base/java/lang/ScopedValue.java:529`(`where`)、`:466`(`Carrier.run`)、`:555`(`get`)、`:504`(`CallableOp`)。

## 19.3 【跨版本演进专题】简化启动:11 → 25

| 版本 | 变化 | 为什么要变 |
|------|------|-----------|
| 11(330) | `java Hello.java` 单文件运行 | "学 Java 先装 IDE、懂 classpath"劝退无数初学者 |
| 21 预览(445) | 隐式声明类 + `void main()`:没有 class 声明也能跑 | "第一行 Java 代码是 `public class Main { public static void main(String[] args)`"——八成新人在这里懵掉。官方开始拆仪式感 |
| 22(463)/23(477)/24(495) | 二/三/四预览,更名"简单源文件" | 反复打磨:类隐式声明、main 可为实例方法、`String[] args` 可省略 |
| **25 转正(512)+ 模块导入转正(511)** | `java.lang.IO` 替代 `System.out`(避免隐式类继承 System 的坑);`import module java.base;` 一行导入整个模块的公开 API | 定稿 + 把"导入样板"也拆掉 |

```java
// Hello.java(JDK 25,无 class、无 String[] args、无 System.out)
void main() {
    IO.println("Hello, Java 25!");
    String name = IO.readln("你的名字: ");
}
// 运行:java Hello.java

// import module:模块级导入(25 转正)
import module java.base;          // 相当于 java.base 模块全部公开包的按需导入
import module java.sql;

void main() {
    var max = IntStream.of(3, 1, 4).max().orElseThrow();
    IO.println("max = " + max);
}
```

> 源码坐标(JDK 27):`java.base/java/lang/IO.java:102`(println)、`:147`(readln)。

## 19.4 灵活构造器(447→482→492→513 转正)

```java
public class Employee extends Person {
    public Employee(String name, int age, String dept) {
        // super 之前的"序章(prologue)":可写自身字段、调静态方法、校验参数
        if (age < 18) throw new IllegalArgumentException("未成年不可雇佣");
        var canonical = dept == null ? "未分配" : dept.strip();
        super(name, age);                 // 显式 super 现在可以放在最后
        // super 之后:老世界
    }
}
```

**为什么**:二十年规则"构造器第一句必须是 super/this"迫使所有校验后置(先调父类构造再校验,可能构造出非法对象)或绕道静态工厂。513 允许"先校验、后委派",同时禁止序章里**读取** `this` 实例字段(保证父类构造前对象状态不被依赖)——安全性与灵活性两全。

## 19.5 KDF API(478 预览 → 510 转正)与安全线

```java
// HKDF 密钥派生(JDK 27 源码 KDF.java:56 官方示例)
KDF kdfHkdf = KDF.getInstance("HKDF-SHA256");
AlgorithmParameterSpec spec = HKDFParameterSpec.ofExtract()
        .addIKM(ikm).addSalt(salt).thenExpand(info, 32);
SecretKey key = kdfHkdf.deriveKey("AES", spec);
```

**为什么**:此前 HKDF 要么没有、要么各家 Provider 私有 API;TLS 1.3、后量子套件(24 的 ML-KEM/ML-DSA)全都建立在 KDF 之上。510 转正让"密钥派生"与 Cipher/KeyStore 平级。配合 25 的 PEM 预览(470)与 24 的后量子算法,Java 密码栈完成了"TLS 1.3(11)→ EdDSA(15)→ KEM(21)→ 后量子(24)→ KDF/PEM(25~27)"的十年现代化。

## 19.6 本章小结

25 的口号是"Java 更好学、并发更好用、JVM 更省内存":紧凑源文件把入门仪式感拆到底,Scoped Values 终结 ThreadLocal 滥用,紧凑对象头与分代 Shenandoah 继续压榨内存与停顿。**从 8/11/17 升级,25 是当前推荐的终点站**(见附录 C)。

---

# 二十、JDK 26(2026-03):HTTP/3、Lazy Constants、AOT 对象缓存

> **一句话定位:10 个 JEP 的精悍版本:HTTP/3 进 HttpClient;Stable Values 更名 Lazy Constants 回归;AOT 缓存支持任意 GC + 对象缓存;"final 就是 final"开始执法;Applet API 移除谢幕。**

## 20.1 版本全景

| JEP | 特性 | 一句话影响 |
|-----|------|-----------|
| 517 | **HTTP/3 for HttpClient** | QUIC/HTTP3 客户端支持 |
| 526 | **Lazy Constants(预览二)** | Stable Values 更名回归,见 20.5 |
| 516 | **AOT 对象缓存(任意 GC)** | 见 20.4 |
| 522 | G1 吞吐优化(减少同步) | G1 高吞吐场景追平/超越 Parallel |
| 524 | PEM 编码(预览二) | 27 三预览(538) |
| 525 | 结构化并发(六预览) | — |
| 530 | 原始类型模式(四预览) | — |
| 529 | Vector API(孵化十一) | — |
| 500 | **Prepare to Make Final Mean Final** | final 字段的"逃生舱"逐步关闭,见 20.6 |
| 504 | **移除 Applet API** | 9 弃用(289)→ 17 判决(398)→ 26 执行 |

## 20.2 HTTP/3:QUIC 时代(517)

**白话**:HTTP/2 仍有 TCP 队头阻塞;HTTP/3 基于 UDP 上的 **QUIC**:连接迁移(换 Wi-Fi 不断流)、内建 TLS 1.3、0-RTT 恢复。26 的 HttpClient 新增 `Version.HTTP_3`,API 形态不变(协商降级到 HTTP/2/1.1 由客户端处理):

```java
HttpClient client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_3)
        .connectTimeout(Duration.ofSeconds(3))
        .build();
// 服务端通过 Alt-Svc 头 advertise HTTP/3,客户端自动升级
```

> HTTP Client 全景回顾:9 孵化(110)→ 11 转正(321)→ **26 HTTP/3(517)**。

## 20.3 【跨版本演进专题】AOT 与启动加速:10 → 13 → 24 → 25 → 26

| 版本 | 变化 | 为什么要变 |
|------|------|-----------|
| 10(310)/13(350) | AppCDS / 动态 CDS 归档 | 类元数据预解析,启动提速 20~40% |
| 24(483) | **AOT 类加载与链接**:校验、字节码展平、方法画像提前完成 | CDS 只省"读取",AOT 缓存把"加载+链接"也搬出启动路径;云函数/CLI/微服务扩容场景启动时间敏感 |
| 25(514/515) | `AOTCacheOutput` 一步生成;AOT 方法画像用于 JIT | 从"两步手工"到"一键";画像让启动后的 JIT 直接走热路径 |
| **26(516)** | **AOT 对象缓存:任意 GC 可用,支持堆内对象进缓存** | 此前 AOT 缓存仅支持 G1;且"启动时常量对象"(配置、正则、类层次元数据)每次重建。516 让它们也能进缓存——启动接近"打开快照" |
| 未完 | GraalVM Native Image 式全量提前编译仍未进主线 | OpenJDK 路线:AOT 缓存逐步逼近,但保持"运行时仍可 JIT"的灵活性 |

```bash
java -XX:AOTCacheOutput=app.aot -cp app.jar Main     # 25+ 一步生成
java -XX:AOTCache=app.aot -cp app.jar Main           # 后续启动
```

## 20.4 【跨版本演进专题】Lazy Constants:25 → 26(→27)

| 版本 | 变化 | 为什么要变 |
|------|------|-----------|
| 25(502,预览) | `StableValue`:`java.lang.StableValue.ofSupplier(...)` | 一类真实痛点:**final 字段必须急切初始化**,但很多"常量"(日志器、正则、配置)初始化昂贵或有时序问题;`volatile + double-check` 手写懒加载又冗长且对 JIT 不友好 |
| **26(526,预览二)** | **更名 `LazyConstant`**,API/语义向"final 字段的懒初始化"收敛 | "Stable"是 JIT 内部行话(stable field profile),对外应表达"**它是常量,只是初始化得晚**";更名后 JIT 仍可在证明已初始化后做常量折叠(和 final 一样优化) |
| 27(531) | 第三次预览 | 未转正 |

```java
public class Component {
    // 声明成 final 的"懒常量":首次 get() 才初始化,之后可被 JIT 常量折叠
    private static final LazyConstant<Logger> LOGGER =
            LazyConstant.of(() -> Logger.create(Component.class));

    void process() { LOGGER.get().info("started"); }   // (JDK 27 源码 LazyConstant.java 官方示例)
}
```

> 源码坐标:`java.base/java/lang/LazyConstant.java`(JDK 27,包 `java.lang`,预览 API)。

## 20.5 "Final 就是 Final"(500)与 Applet 移除(504)

- **500**:序列化等历史机制曾获准"改写 final 字段",这挡住了 JIT 的常量折叠与 Valhalla 值类型设计。26 开始逐步关闭逃生舱:依赖"反射改 final"的框架(某些 ORM/序列化库)将失去官方支持——与 20.4 的 LazyConstant 一并,构成"常量语义"现代化的一揽子工程。
- **504**:Applet API(`java.applet`)正式移除。从 1995 年的浏览器插件荣光到 2026 年代码删除,这条 31 年的曲线是 Java 全史的缩影。

## 20.6 本章小结

26 继续"网络 + 启动 + 常量"三线推进:HTTP/3 补齐传输层现代化,Lazy Constants 与"final 执法"为 Valhalla 值类型铺路,AOT 对象缓存把启动优化推向 GraalVM 邻域。

---

# 二十一、JDK 27(2026-09):G1 全面默认、紧凑对象头成为缺省

> **一句话定位:最新版(非 LTS):9 个 JEP 全是"收网"动作——G1 成为一切环境的默认 GC,紧凑对象头默认开启,后量子 TLS 落地,JFR 数据脱敏;语言侧没有新语法,预览四件套(SC/原始类型模式/Lazy Constants/PEM)继续打磨。**

## 21.1 版本全景

| JEP | 特性 | 一句话影响 |
|-----|------|-----------|
| 523 | **G1 成为所有环境默认 GC** | 1 核/小内存机器上 Serial 默认的时代结束 |
| 534 | **紧凑对象头默认开启** | 见 21.3 专题 |
| 527 | **TLS 1.3 后量子混合密钥交换** | X25519MLKEM768 等混合组,防"先收割后解密" |
| 536 | **JFR 进程内数据脱敏** | JFR 事件中的命令行/环境变量/敏感参数默认脱敏 |
| 533 | 结构化并发(七预览) | 时间线更新见 21.4 |
| 531 | Lazy Constants(三预览) | 见 20.4 |
| 532 | 原始类型模式(五预览) | — |
| 537 | Vector API(孵化十二) | — |
| 538 | PEM 编码(三预览) | — |

## 21.2 G1 全环境默认(523):一行配置的消失

**白话**:此前 JVM 按"可用处理器数/内存"自动选 GC:资源充足用 G1,1 核小机用 Serial——同一份代码在容器与物理机行为不同。27 起 **G1 无条件默认**:得益于 26 的 522(减少同步开销)与小堆优化,G1 在小环境的表现已可接受;"一致性"战胜"分环境特调"。Serial GC 仍保留,显式 `-XX:+UseSerialGC` 可用。

## 21.3 【跨版本演进专题】紧凑对象头:24 → 25 → 27

| 版本 | 变化 | 为什么要变 |
|------|------|-----------|
| 24(450,实验) | `-XX:+UseCompactObjectHeaders` 实验 | 传统对象头 = mark word(8B)+ 压缩类指针(4B)= 12B(对齐到 16B);Java 对象的内存大头往往是**对象头本身**——`Integer` 16B 里数据只占 4B |
| 25(519,转正) | 显式开启可用 | mark word 中"无锁偏向位"等内容(15 已弃用偏向锁)被腾挪,对象头压缩到 **8B**;小对象堆占用降 10~20% |
| **27(534)** | **默认开启** | 稳定性验证完成,收益成为默认 |

**影响**:堆占用普遍下降(对象越多越密收益越大);GC 更快(扫的内存少了);代价是 mark word 语义重排,依赖对象头布局的 hack(Unsafe 读 mark word 等)失效——又一个"去 hack 化"决策。

## 21.4 结构化并发时间线更新:19 → 27(第七次预览)

完整机制与 24 重设计的 API 见 18.3。演进全表:

| 版本 | JEP | 状态 |
|------|-----|------|
| 19 / 20 | 428 / 437 | 孵化一、二 |
| 21 / 22 / 23 | 453 / 462 / 480 | 预览一、二、三 |
| 24 | 499 | **预览四:open()/Joiner 重新设计** |
| 25 / 26 / **27** | 505 / 525 / 533 | 预览五、六、**七(仍未转正)** |

**为什么七年未转正**:它要同时满足"取消语义正确、异常不吞、与虚拟线程零摩擦、API 难用错"四条硬标准,每轮预览都还能收到设计级反馈。对照 String Templates(撤回),可见 Java 对"转正即永久"承诺的谨慎程度。

## 21.5 安全:后量子 TLS(527)与 JFR 脱敏(536)

- **527**:TLS 1.3 新增混合密钥交换组(经典 ECDHE + ML-KEM 各出一半熵)。设计逻辑:**今天被录密的流量,要在量子计算机成熟后也解不开**——所以现在就要混入后量子成分。
- **536**:JFR 会记录命令行参数、环境变量、连接串——这些常含密钥;27 起 JFR 默认对敏感字段脱敏,可用 `jdk.redact` 规则定制。配合 25 的 JFR 三连(509/518/520),JFR 正在成为"默认安全 + 生产级剖析"的一体化可观测底座。

## 21.6 本章小结

27 没有新语法,却是"工程收网"的样本:GC 叙事收敛到 G1 + 分代 ZGC/Shenandoah,对象头瘦身成为默认,安全线推进到后量子时代。Java 8 → 27 的九年,语言的核心动作已基本完成:**函数式、数据导向、廉价线程、安全互操作**;剩下的悬念在 Valhalla 值类型与结构化并发的转正时点。

# 附录 A 跨版本演进特性速查表

| 特性 | 起点 | 关键节点 | 定型/最新版本 | 正文 |
|------|------|----------|---------------|------|
| CompletableFuture | 8 | 9 超时/编排、12 异常组合 | 12(稳定) | 6.3 |
| switch 表达式 | 12 预览 | 13 yield | 14 转正 | 8.2 |
| 文本块 | 13 预览 | 14 `\`/`\s` | 15 转正 | 9.2 |
| Record | 14 预览 | 15 二预览 | 16 转正 | 10.2 |
| instanceof 模式 | 14 预览 | — | 16 转正 | 10.3 |
| sealed | 15 预览 | 16 二预览 | 17 转正 | 11.2 |
| 虚拟线程 | 19 预览 | 20 预览 | 21 转正(24 修复 synchronized 钉住) | 15.2 |
| 模式匹配(switch/record 模式) | 17/19 预览 | 18/20 推进 | 21 转正;原始类型模式 27 仍预览 | 15.3 |
| SequencedCollection | 8/9/10 前置 | — | 21 转正 | 15.4 |
| 未命名变量 `_` | 21 预览 | — | 22 转正 | 16.2 |
| FFM API | 14 孵化 | 19~21 四轮预览 | 22 转正;23/24 淘汰 Unsafe 配套 | 16.3 |
| String Templates | 21 预览 | 22 二预览 | **23 撤回** | 17.2 |
| Stream Gatherers | 22 预览 | 23 二预览 | 24 转正 | 18.2 |
| Class-File API | 22 预览 | 23 二预览 | 24 转正 | 18.5 |
| 结构化并发 | 19 孵化 | 24 Joiner 重设计 | 27 仍预览(七) | 18.3 / 21.4 |
| Scoped Values | 20 孵化 | 21~24 四轮预览 | 25 转正 | 19.2 |
| 简化启动(void main/模块导入) | 11 单文件 | 21~24 四轮预览 | 25 转正(512/511) | 19.3 |
| 灵活构造器 | 22 预览 | 23/24 推进 | 25 转正 | 19.4 |
| KDF API | 24 预览 | — | 25 转正 | 19.5 |
| HTTP Client | 9 孵化 | 11 转正 | 26 HTTP/3 | 5.2 / 20.2 |
| AOT/CDS | 10 AppCDS | 13 动态 CDS | 24 AOT 类加载、25 简化、26 对象缓存 | 20.3 |
| Lazy Constants | 25 Stable Values | 26 更名 | 27 仍预览(三) | 20.4 |
| 紧凑对象头 | 24 实验 | 25 转正 | **27 默认开启** | 21.3 |
| 模块化 | 9 | 16/17 强封装收紧 | 17 定型 | 3.2 |
| GC | 9 G1 默认 | 11 ZGC、12 Shenandoah、15 转正、21 分代 | 24 非分代 ZGC 移除、27 G1 全环境默认 | 15.5 |
| Security Manager | 17 弃用 | 24 永久禁用 | 24 终结 | 11.3 |
| Applet | 9 弃用 | 17 判决 | 26 移除 | 20.5 |
| Nashorn | 8 引入 | 11 弃用 | 15 移除 | 9.1 |
| sun.misc.Unsafe | — | 23 弃用记忆访问、24 运行时警告 | 迁移进行中(FFM/VarHandle) | 16.3 |

# 附录 B 废弃与移除清单(8 → 27,按动作排序)

| 版本 | 动作 | 内容 | 对普通开发者的影响 |
|------|------|------|--------------------|
| 9 | 弃用 | CMS、Applet、Java EE/CORBA 模块 | 规划迁移 |
| 11 | 移除 | Java EE(JAXB 等)/CORBA 模块 | **需手动加依赖,最常见升级障碍** |
| 11 | 弃用 | Nashorn、Pack200 | — |
| 14 | 移除 | CMS GC | 老脚本需换 GC 参数 |
| 15 | 移除 | Nashorn | JS 嵌入改 GraalJS |
| 16 | 默认拒绝 | JDK 内部 API 反射访问 | 旧框架需 --add-opens |
| 17 | 弃用 | Security Manager、Applet、RMI Activation(移除) | — |
| 17 | 最终化 | 内部强封装(403) | **8→17 第二大升级障碍** |
| 18 | 弃用 | finalization | 改 try-with-resources/Cleaner |
| 23 | 弃用 | sun.misc.Unsafe 记忆访问方法 | 生态迁移 FFM |
| 24 | 禁用/移除 | Security Manager 永久禁用、Windows 32 位、Unsafe 调用警告 | — |
| 26 | 移除 | Applet API、(24 起)32 位 x86 移植 | — |
| 27 | 默认变更 | G1 全环境默认、紧凑对象头默认、UTF-8(18 起) | 无需动作,留意性能报告差异 |

# 附录 C 升级路线建议(8 → 17 → 21 → 25)

**推荐跳级**:8 → 17(或直接 8 → 21)→ 25。逐版升级(8→9→10→…)毫无必要,但每跳两级要一次性处理两个版本的迁移清单。

| 跳跃 | 必做检查 | 参考章节 |
|------|----------|----------|
| 8 → 17 | JAXB/EE 依赖补齐;`--add-opens` 清单(老框架反射);GC 参数改为 G1 语义;`--illegal-access` 移除 | 5.5、11.1、3.2 |
| 17 → 21 | Security Manager 依赖审查(411);虚拟线程试点(444);模式匹配重构收益评估;动态 Agent 加载收紧影响(451) | 15.2/15.3、11.3 |
| 21 → 25 | Scoped Values 替换 ThreadLocal 热点(506);紧凑源文件试点;紧凑对象头/分代 Shenandoah 压测;JDK 内部 API 最后清尾 | 19.2/19.3、21.3 |
| 25 → 27(非 LTS 可跳过) | G1 全环境默认验证;紧凑对象头默认后的内存回归测试 | 21.2/21.3 |

**预览特性使用纪律**:预览 API 每版都可能变(甚至撤回),只在测试/学习中使用;生产等待转正,结构化并发(27 仍预览)目前可用"虚拟线程 + try-with-resources 包一层"近似替代。

# 参考资料

- 各版本 JEP 权威清单(本文 23~27 章清单即取自此):openjdk.org/projects/jdk/{9..27}
- JEP 索引:https://openjdk.org/jeps/0
- JDK 27 源码(本文"源码坐标"出处):D:\soft\jdk\jdk-27\lib\src.zip
- 系列姊妹篇:[Flow.md](Flow.md)、[Java并发.md](Java并发.md)
- Oracle 发布公告:"The Arrival of Java 27"(2026-09-15)、"Java 26 is now available"(2026-03-17)

---

*文档完成于 2026-10,基于 JDK 27 GA 后的官方资料整理。*








