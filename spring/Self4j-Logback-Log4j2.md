# 日志落盘关卡与 Marker / MDC / Level 过滤（Logback · Log4j2）

> **本文基于的源码**：`D:\code\3rd\slf4j`（tag `v_2.0.20`）、`D:\code\3rd\logback`（tag `v_1.5.38`）、`D:\code\3rd\logging-log4j2`（tag `rel/2.26.1`，commit `dd0f9d255e24`）。文中【源码证据】的路径与行号均按上述 tag 实读。
>
> **和 `Self4j.md` 的关系**：门面绑定、Logger 树、异步队列、配置文件查找仍以 `Self4j.md` 为准。本文回答四件事：一条日志真正写入文件之前要过哪些关；什么样的 Marker 算"符合"；Marker、MDC、Level 挂在全局 Filter 和局部 Filter 上时如何放行或丢弃；排障时怎样逐级放开打印。两套引擎分开写，每条规则都配可运行的代码和配置。
>
> **阅读约定**：先白话，再源码。行号只对上述 tag 精确，用类名 + 方法名在 IDE 里跳转即可。

## 如何读这份文档

- **只想知道"为什么这条日志没进文件"**：读第一章的对照图，再读第二章里你所用引擎的那一节，对着配置逐关排除。
- **在配审计分流、父子 Marker**：直接读第三章的两张真值表。Logback 与 Log4j2 原生 API 的父子方向相反；走 SLF4J 的 `add()` 时，Log4j2 桥接会把关系倒过来存，过滤结果才与 Logback 对齐。
- **要同时用级别、链路 MDC、业务 Marker 做开关**：读第四章，最后一节是同一业务意图的两份完整配置，以及六条调用各自落进哪个文件。
- **线上要临时多看日志**：读第五章。顺序是「一次请求或一个类、只升一级、到点收回」，不要先动 root。

---

# 一、总览：写入之前的关，和三值在不同挂载点的效力

## 1.1 一句话定位

**级别决定"这条调用有没有资格继续"，全局 Filter 决定"在构造日志事件之前就生或死"，局部 Filter 决定"已经活下来的事件进哪一个 Appender"。** Marker 和 MDC 自己不写文件，它们只是全局 Filter 和局部 Filter 的判定输入。

同一个三值（`DENY` / `NEUTRAL` / `ACCEPT`）挂在不同位置，效力不同。这是配错过滤器时最常见的原因，后面每章都回到这张表。

## 1.2 两条管道的关卡对照

数值方向先钉死，后面所有"更严重 / 更啰嗦"都按这套数：

| | Logback `Level` | Log4j2 `Level` |
|---|---|---|
| 数值 | TRACE 5000 < DEBUG 10000 < INFO 20000 < WARN 30000 < ERROR 40000 | OFF 0 < FATAL 100 < ERROR 200 < WARN 300 < INFO 400 < DEBUG 500 < TRACE 600 |
| 级别门公式 | `effectiveLevelInt > 事件.levelInt` 则拒绝（`Logger.java` 第 381 行） | `logger.intLevel >= 事件.intLevel()` 才放行（`Logger.java` 第 540 行） |
| 直觉 | 数值越大越严重 | 数值越大越啰嗦；`isMoreSpecificThan` 是 `this.intLevel <= other.intLevel`（`Level.java` 第 212-213 行） |

```
业务线程 log.info(...)
        │
        ▼
┌─ 关 A  全局 Filter（事件还没诞生）─────────────────────────────┐
│ Logback: TurboFilter 链                                        │
│ Log4j2 : Configuration 上的 Filter（PrivateConfig.filter）     │
│   DENY     → 调用结束，磁盘无字节                              │
│   ACCEPT   → 跳过关 B                                          │
│   NEUTRAL  → 进入关 B                                          │
└────────────────────────────────────────────────────────────────┘
        │
        ▼
┌─ 关 B  Logger 级别门（不是 Filter，是 Logger 自己的 level）────┐
│ 没配过全局 Filter，或者全局 Filter 返回了 NEUTRAL，才走这里    │
└────────────────────────────────────────────────────────────────┘
        │ 通过才构造事件。占位符 {} 到这里仍未格式化成最终句子
        ▼
┌─ 关 C  Logger 局部 Filter（只有 Log4j2 有这一关）──────────────┐
│ LoggerConfig.isFiltered：只有 DENY 才丢                        │
│ DENY 同时切断 additive，父 Logger 也收不到这条事件             │
└────────────────────────────────────────────────────────────────┘
        │
        ▼
┌─ 关 D  沿父链找 Appender（additive=false 截断）────────────────┐
└────────────────────────────────────────────────────────────────┘
        │ 每个 Appender 各走一遍，彼此独立
        ▼
┌─ 关 E1 当前这个 Appender 自己的 Filter（仍在调用线程）─────────┐
│ 只决定"这一个 Appender 写不写 / 入不入队"                      │
│ Logback 文件与 Async 都走 UnsynchronizedAppenderBase.doAppend   │
│ 链结果是 DENY 才停；ACCEPT 与最终 NEUTRAL 都进入 append()      │
│ 挂在 AsyncAppender 上的 Filter 在这里执行，DENY 则根本不入队   │
│ Log4j2 的 AppenderControl 与 Appender 各一道，见 2.2            │
└────────────────────────────────────────────────────────────────┘
        │ 同步文件：下一步就是关 G。异步：先入队
        ▼
┌─ 关 F  只有 AsyncAppender（调用线程）──────────────────────────┐
│ 队列剩余容量 < discardingThreshold 且级别 ≤ INFO → 丢弃        │
│ neverBlock=true 且队列满 → offer 失败，静默丢弃                │
│ 否则 put 进 ArrayBlockingQueue。MDC/句子在入队前冻结           │
└────────────────────────────────────────────────────────────────┘
        │ worker 取出
        ▼
┌─ 关 E2 被包裹的那个 Appender 再走一遍 doAppend（worker 线程）──┐
│ 它自己的 Filter 在这里才执行。DENY 则此事件已出队但不会写文件  │
└────────────────────────────────────────────────────────────────┘
        │
        ▼
┌─ 关 G  滚动检查（不丢当前事件）→ 再次冻结懒字段 → encode → write ┐
└────────────────────────────────────────────────────────────────┘
```

上图关 F 按 Logback `AsyncAppender` 画：剩余容量低于阈值、并且级别 ≤ INFO，事件在入队前丢掉。Log4j2 的 `<Async>` 默认是队列满了阻塞，配成 `Discard` 之后才按级别丢。全异步 `AsyncLogger` 的 Logger 过滤器在出队之后。逐步判断在 2.2，两套引擎的对照在 2.3。

## 1.3 三值语义随挂载点变化

| 返回值 | Logback 全局 TurboFilter | Logback Appender 上的 Filter | Log4j2 全局（Configuration） | Log4j2 LoggerConfig 上的 Filter | Log4j2 Appender / AppenderRef 上的 Filter |
|---|---|---|---|---|---|
| `DENY` | 调用立刻返回，不构造事件 | 这个 Appender 不写；别的 Appender 照常 | `isEnabled` 为 false，不构造事件 | 这个 LoggerConfig 不写，**父 LoggerConfig 也不再收到** | 这个 Appender 不写 |
| `NEUTRAL` | 交给级别门 | 继续下一个 Filter；整链都是 NEUTRAL 时**仍然写入** | 交给级别门 | 与 ACCEPT 一样继续往下投递 | 继续；整链没有 DENY 就写入 |
| `ACCEPT` | **跳过级别门**，仍要过后面每个 Appender 的 Filter | 不再看本 Appender 后续 Filter，然后写入 | **跳过级别门**，仍要过 LoggerConfig 与 Appender 的 Filter | 只是让本链短路，**不会**跳过父 Logger，也**不会**强迫 Appender 写入 | 只是让本链短路，然后写入 |

两句可以直接拿去对配置：

1. 全局 `ACCEPT` 的含义是"级别不够也让事件诞生"，不是"每个文件都必须出现这一行"。
2. 局部 `ACCEPT` 的含义是"这个挂载点放行"，不是"整条调用已经成功落盘"。

## 1.4 章节地图

```
第一章  关卡全景与三值效力
第二章  一条日志写入文件前的逐关（Logback / Log4j2 / 异步）
第三章  什么样的 Marker 算符合：子 Marker、父 Marker 的规则与效果
第四章  Marker、MDC、Level 如何分别控制全局 Filter 与局部 Filter
第五章  排障时逐步放开日志：阶梯、两套实现、护栏
```

---

# 二、写入文件前要过的关卡

## 2.1 Logback：一条日志从调用到字节，每一次可以死掉的判断

下面按一次同时带 Marker 和 MDC 的调用写。配置是 root 上挂一个 `AsyncAppender`，它里面只包一个 `RollingFileAppender`。过滤器挂在哪一层，就在哪一层执行，不能提前，也不能补做。

```java
private static final Marker FLOW = MarkerFactory.getMarker("FLOW");
private static final Marker BIZ = MarkerFactory.getMarker("BIZ");

MDC.put("traceId", "t-9");
MDC.put("reqType", "biz");          // health 会在 TurboFilter 被整次丢掉
try {
    log.info(BIZ, "user {} login", uid);
} finally {
    MDC.clear();
}
```

```xml
<configuration>
  <!-- 步 1：事件还不存在。先看 MDC，再看 Marker。第一个非 NEUTRAL 结束 -->
  <turboFilter class="ch.qos.logback.classic.turbo.MDCFilter">
    <MDCKey>reqType</MDCKey>
    <Value>health</Value>
    <OnMatch>DENY</OnMatch>
    <OnMismatch>NEUTRAL</OnMismatch>
  </turboFilter>
  <turboFilter class="ch.qos.logback.classic.turbo.MarkerFilter">
    <Marker>FLOW</Marker>
    <OnMatch>DENY</OnMatch>
    <OnMismatch>NEUTRAL</OnMismatch>
  </turboFilter>

  <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
  <file>logs/app.log</file>
  <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
    <fileNamePattern>logs/app.%d{yyyy-MM-dd}.log.gz</fileNamePattern>
  </rollingPolicy>
  <encoder><pattern>%d %-5level [%X{traceId}] %marker %msg%n</pattern></encoder>
  <!-- 关 E2：worker 出队之后才执行。%X 读的是入队前拷进事件的 MDC -->
  <filter class="ch.qos.logback.classic.filter.ThresholdFilter">
    <level>INFO</level>
  </filter>
</appender>

<appender name="ASYNC" class="ch.qos.logback.classic.AsyncAppender">
  <queueSize>256</queueSize>
  <discardingThreshold>51</discardingThreshold>
  <neverBlock>false</neverBlock>
  <!-- 关 E1：调用线程、入队之前执行 -->
  <filter class="ch.qos.logback.classic.filter.LevelFilter">
    <level>ERROR</level>
    <onMatch>DENY</onMatch>
    <onMismatch>NEUTRAL</onMismatch>
  </filter>
  <appender-ref ref="FILE"/>
</appender>

<root level="INFO">
  <appender-ref ref="ASYNC"/>
</root>
</configuration>
```

总顺序：

| 步 | 线程 | 做什么 | 这一步会死的条件 |
|---|---|---|---|
| 1 | 调用线程 | TurboFilter 链。本例先 `MDCFilter`（读此刻的 `MDC.get`），再 `MarkerFilter`（读这次调用带上的那一个 Marker） | 第一个非 NEUTRAL 是 DENY。还没有 `LoggingEvent` |
| 2 | 调用线程 | 级别门 `effectiveLevelInt > level.levelInt` | 第 1 步是 NEUTRAL 且这个不等式成立。ACCEPT 跳过本步 |
| 3 | 调用线程 | `new LoggingEvent` | 不再做过滤。异常栈代理、时间戳在这里生成；句子和 MDC 还不生成 |
| 4 | 调用线程 | 沿父 Logger 找 Appender | 一个 Appender 都没调用到时，只打一条 status 警告，没有字节 |
| 5 | 调用线程 | `ASYNC.doAppend`：未启动、然后是它自己的 Filter 链 | 未启动，或链结果为 DENY。本例 ERROR 在这里被拒，不入队 |
| 6 | 调用线程 | 丢弃判断，然后 `preprocess`，然后入队 | 队列快满且级别 ≤ INFO；或 `neverBlock=true` 时队列满 |
| 7 | worker | `FILE.doAppend`：再走一遍未启动 + Filter 链 | FILE 未启动，或 `ThresholdFilter` 返回 DENY |
| 8 | worker | 滚动、冻结懒字段、`encoder.encode`、`write` | 流已停、编码结果是空数组、或写的时候 IOException |

同步文件没有第 6、7 步的分离：`FILE` 直接挂在 root 上时，第 5 步就是 FILE 的 `doAppend`，Filter 在调用线程执行，通过后立刻进入第 8 步。

### 2.1.1 入口有两条，级别门只在传统 API 上和 TurboFilter 绑在一起

`Logger.info(String, Object)` 进入 `filterAndLog_1`。零参数和三个及以上参数走 `filterAndLog_0_Or3Plus`，两个参数走 `filterAndLog_2`。三份代码的判断相同，差别只是传给 TurboFilter 的参数数组：一个参数时在 `LoggerContext.getTurboFilterChainDecision_1`（第 264-269 行）里先 `new Object[]{ param }`，然后才调用过滤器。所以一次单参数的 `log.info` 即使马上被 DENY，也已经分配了这个长度为 1 的数组。

【源码证据】`classic/Logger.java` 第 392-407 行：

```java
final FilterReply decision = loggerContext.getTurboFilterChainDecision_1(marker, this, level, msg, param, t);
if (decision == FilterReply.NEUTRAL) {
    if (effectiveLevelInt > level.levelInt) {
        return;                         // 死在级别门，没有 LoggingEvent
    }
} else if (decision == FilterReply.DENY) {
    return;                             // 死在 TurboFilter，没有 LoggingEvent
}
// decision == ACCEPT：两个 if 都不进，直接构造事件
buildLoggingEventAndAppend(localFQCN, marker, level, msg, new Object[] { param }, t);
```

`FilterReply` 只有 `DENY`、`NEUTRAL`、`ACCEPT` 三个枚举值（`FilterReply.java` 第 28-29 行）。传统 API 里没有第四种分支。

Fluent API 是另一条入口，`Logger.log(LoggingEvent)`（第 817-826 行）只认 DENY：

```java
final FilterReply decision = loggerContext.getTurboFilterChainDecision(this, slf4jEvent);
// ACCEPT 和 NEUTRAL 都往下走，这里不再做级别比较
if (decision == FilterReply.DENY) {
    return;
}
```

级别比较发生在更早的 `atInfo()` / `atDebug()`。SLF4J 的默认方法先问 `isEnabledForLevel`，没启用就返回 NOP 构建器，后面的 `addMarker().log()` 不会进到 Logback。`isInfoEnabled(Marker)`（第 575-586 行）走的也是 TurboFilter，但 `callTurboFilters`（第 762-764 行）把 format 和参数都传成 `null`。因此：

- `log.info(marker, format, arg)`：TurboFilter 看得到 format 和参数数组，整条链路只经过 **一次** TurboFilter。
- `log.atInfo().log(format, arg)`：`atInfo()` 里一次（format 还是 null），`log(LoggingEvent)` 里再一次（这次才有 format）。`DuplicateMessageFilter` 这种有计数的过滤器会被加两次。第二次若变成 DENY，事件在诞生前死掉；第二次若是 NEUTRAL，不再回头看级别。

`log.info` 本身不会先调用 `isInfoEnabled()`。业务代码里手写的 `if (log.isInfoEnabled())` 是额外一次 TurboFilter，不是框架内部的关卡。

### 2.1.2 TurboFilter 链：按配置顺序，第一个 DENY 或 ACCEPT 结束全链

`LoggerContext` 在进链之前先看列表长度（第 256-260 行）。长度为 0 直接返回 `NEUTRAL`，热路径上没有 TurboFilter 时就这一次判断。

长度不为 0 才进入 `TurboFilterList.getTurboFilterChainDecision`（第 39-67 行）：

1. 长度仍是 0（并发清空）：返回 `NEUTRAL`。
2. 长度是 1：调用这一个 `decide`。若取元素时抛 `IndexOutOfBoundsException`（并发删掉了它），返回 `NEUTRAL`，这次调用当成没配过滤器。
3. 长度大于 1：按列表顺序调用。返回值是 `DENY` 或 `ACCEPT` 时立刻作为整链结果，后面的 TurboFilter 不再执行。返回 `NEUTRAL` 才问下一个。全部是 `NEUTRAL` 时整链才是 `NEUTRAL`。

列表是 `CopyOnWriteArrayList`，`<turboFilter>` 的书写顺序就是执行顺序，`addTurboFilter` 加在尾部（`LoggerContext.java` 第 241-242 行）。

每个 `decide` 拿到的是：这一个 Marker、当前 Logger、这次调用的 Level、**还没格式化的** format 字符串、参数数组、Throwable。没有 `LoggingEvent`，所以这里读不到 `getFormattedMessage()`，也读不到事件上的 MDC 快照。`MDCFilter` 读的是调用线程上此刻的 `MDC.get`（`MDCFilter.java` 第 71 行）。

Fluent 那次重载 `TurboFilter.decide(Logger, LoggingEvent)`（第 92-101 行）默认只把 marker 列表的 **第一个** 传给上面的 `decide(Marker, ...)`。`MarkerFilter` 没有覆盖这个重载，多个 Marker 时全局只看见第一个。

未 `start` 的 TurboFilter 自己返回 `NEUTRAL`（`MarkerFilter` 第 42-44 行，`MDCFilter` 第 66-68 行），相当于从链上消失，不会误伤。`MatchingFilter` 的 `onMatch` 和 `onMismatch` 默认都是 `NEUTRAL`（第 26-27 行）。XML 里的字符串只有 `"NEUTRAL"`、`"ACCEPT"`、`"DENY"` 三种会被认出来（第 29-36 行），写错单词则保持默认 NEUTRAL。

链结果回到 `filterAndLog_1` 之后只有三种效果：

| 链结果 | 级别门 | 事件 |
|---|---|---|
| `DENY` | 不看 | 不创建 |
| `NEUTRAL` | `effectiveLevelInt > 本次 levelInt` 则死 | 级别通过才创建 |
| `ACCEPT` | 不看 | 立刻创建。root 是 INFO 时，DEBUG 也会创建 |

`ACCEPT` 只跳过级别门。后面的 Appender Filter、队列丢弃、文件阈值都还在。

### 2.1.3 级别门：比的是已经推好的 effectiveLevelInt

`effectiveLevelInt` 是这个 Logger 当前生效级别的整数，读的时候不再向上找父节点。root 重置后的默认值是 `DEBUG` 的 10000（`localLevelReset`，第 323-326 行）。子 Logger 的 `level == null` 表示继承，父级 `setLevel` 时通过 `handleParentLevelChange`（第 185-189 行）把整数推下来；子节点自己有级别时不跟随。

比较式是 `effectiveLevelInt > level.levelInt` 就拒绝（第 399-401 行）。Logback 的整数越大越严重（`Level.java` 第 31-36 行）：

| 级别 | 整数 |
|---|---|
| TRACE | 5000 |
| DEBUG | 10000 |
| INFO | 20000 |
| WARN | 30000 |
| ERROR | 40000 |

root 为 INFO（20000）时：

- `log.debug` 的 levelInt 是 10000，`20000 > 10000`，返回。没有事件。
- `log.info` 的 levelInt 是 20000，`20000 > 20000` 为假，继续。
- `log.error` 的 levelInt 是 40000，继续。

`Level.isGreaterOrEqual`（第 161-162 行）是 `levelInt >= 对方`，和这道门的 `>` 方向相反，Appender 上的 `ThresholdFilter` 用的是前者。两处不要混成同一个比较。

这一步结束之前，占位符还是 format 加参数数组，没有拼成最终句子。

### 2.1.4 事件诞生：构造函数做完的事，和故意留到后面的事

级别门通过（或 TurboFilter 为 ACCEPT）之后才执行 `buildLoggingEventAndAppend`（第 427-431 行）：

```java
LoggingEvent le = new LoggingEvent(localFQCN, this, level, msg, t, params);
le.addMarker(marker);
callAppenders(le);
```

构造函数（`LoggingEvent.java` 第 120-150 行）写入：

- 调用边界类名 `fqnOfLoggerClass`、logger 名字、LoggerContext、这次的 Level。
- 原始 `message` 和 `argumentArray`。`formattedMessage` 保持 null。
- `Clock.systemUTC().instant()` 拆成毫秒时间戳和纳秒。
- 若上下文配了 `SequenceNumberGenerator`，取下一个序号。
- `throwable` 参数为 null 时，从参数数组末尾抽出 Throwable，并把它从参数数组里裁掉（第 140-142 行、第 161-166 行）。所以 Appender Filter 里看到的 `getThrowableProxy()` 已经就绪，最后一个参数是异常时它不占 `{}`。
- 有异常就建 `ThrowableProxy`。`packagingData` 打开时，这里就计算每个栈帧所属的 jar。这是构造事件时最贵的一步，发生在任何 Appender Filter 之前。

构造函数 **不** 做的事：

- 不调用 `MessageFormatter`，最终句子还不存在。
- 不拷贝 MDC。`mdcPropertyMap` 仍是 null。
- 不取调用栈。`callerDataArray` 仍是 null。
- 传统 API 的 Marker 是构造之后 `addMarker` 放进去的（第 419-426 行）。传入 null 时 `addMarker` 直接返回，列表保持 null。

Fluent 入口在 DENY 检查之后做同样的构造，再把 marker 列表里的每一个都 `addMarker`，并 `setKeyValuePairs`（第 837-845 行）。注释写明：到了 `callAppenders`，TurboFilter 已经没有机会再插手。

### 2.1.5 沿父链把同一个事件交给每个 Appender

`callAppenders`（第 256-268 行）：

```java
int writes = 0;
for (Logger l = this; l != null; l = l.parent) {
    writes += l.appendLoopOnAppenders(event);
    if (!l.additive) {
        break;
    }
}
if (writes == 0) {
    loggerContext.noAppenderDefinedWarning(this);
}
```

从当前 Logger 走到 root。某一级 `additive == false` 时，这一级的 Appender 仍然会收到事件，它的父级不再收到。`appendLoopOnAppenders`（`AppenderAttachableImpl.java` 第 46-54 行）把当前挂载的 Appender 数组拍一份快照，逐个 `doAppend`，每调用一次计数加一。返回值是"调用了几次 doAppend"，不是"写成功了几个字节"。某个 Appender 内部把事件丢掉，计数仍然增加，因此 `writes == 0` 只表示整条父链上没有挂 Appender。这时打一条 status 警告，事件对象成为垃圾，磁盘上没有这行。

父 Logger **不会**再跑 TurboFilter，也 **不会**再比级别。事件已经诞生。父级 Appender 上的 Filter 仍会在各自的 `doAppend` 里执行。同一个事件可以进子 Logger 的异步队列，同时又被 root 上的同步 Console 写出去。`additive=true` 且父子都引用了同一个 AsyncAppender 时，这个事件会入队两次。

兄弟 Appender 互不影响。APP 的 Filter 返回 DENY，只结束 APP 的 `doAppend`；循环里的下一个 Appender 照常开始自己的 `doAppend`。

### 2.1.6 doAppend：1.5 的文件和异步都走 UnsynchronizedAppenderBase

`FileAppender`、`RollingFileAppender`、`ConsoleAppender`、`AsyncAppender` 的 `doAppend` 都来自 `UnsynchronizedAppenderBase`（`OutputStreamAppender` 第 37 行继承它，`AsyncAppenderBase` 第 46 行继承它）。`AppenderBase.doAppend` 仍在，但是同步骨架，给仍继承它的自定义 Appender 用。库存的文件路径不经过它。

`UnsynchronizedAppenderBase.doAppend`（第 60-91 行）的顺序是固定的：

```java
public void doAppend(E eventObject) {
    if (!this.started) {                 // ① 未启动：警告后返回，事件在这个 Appender 上死
        ...
        return;
    }
    if (reentryGuard.isLocked()) {       // ② 重入
        return;
    }
    try {
        reentryGuard.lock();
        if (getFilterChainDecision(eventObject) == FilterReply.DENY) {  // ③ 只有 DENY
            return;
        }
        this.append(eventObject);        // ④ 子类的 append
    } catch (Exception e) {
        // 异常被吃掉，这一次没有重试
    } finally {
        reentryGuard.unlock();
    }
}
```

① 未 `start`：用时间间隔限制警告次数，然后返回。配置写错、`start()` 因缺少 encoder 失败、或者写文件时 IOException 把 `started` 打成 false 之后，后续事件都死在这里。

② 重入守卫在 `start()` 里由 `buildReentryGuard()` 创建（第 102-103 行）。默认是 `GuardType.NOP`（第 130-131 行），`NOPRentryGuard.isLocked()` 永远返回 false（`ReentryGuard.java` 第 122-123 行）。所以 **FileAppender、RollingFileAppender、AsyncAppender 不会因为重入把事件丢掉**。`ConsoleAppender` 改成了 `THREAD_LOCAL`（第 109-110 行）：同一线程在 `doAppend` 里再次进入这个 Console（例如 Pattern 的转换器里又打了日志，或者编码时触发了日志），第二次在 ② 返回，防止递归把栈打满。

③ 见下一节。④ 对异步是入队，对文件是滚动并写字节。`append()` 抛出的异常被  catch 吃掉，事件不会交给下一个重试。

### 2.1.7 Appender 的 Filter 链：和 TurboFilter 同一套三值，死亡条件更窄

`getFilterChainDecision` 在 `FilterAttachableImpl`（第 51-65 行）：

```java
for (int i = 0; i < len; i++) {
    final FilterReply r = filterArrray[i].decide(event);
    if (r == FilterReply.DENY || r == FilterReply.ACCEPT) {
        return r;          // 第一个非 NEUTRAL 结束整链
    }
}
return FilterReply.NEUTRAL;
```

`doAppend` 只判断 `== DENY`。于是：

| 链的最终返回值 | 后面的 Filter | `append()` |
|---|---|---|
| `DENY` | 不再执行 | 不调用。这个 Appender 上事件死 |
| `ACCEPT` | 不再执行 | 调用 |
| `NEUTRAL`（每个都是 NEUTRAL，或链是空的） | 已经走完 | **仍然调用** |

空 Filter 链得到 `NEUTRAL`，事件会写。这是和 TurboFilter 的关键差别：TurboFilter 的 `NEUTRAL` 还要过级别门，Appender 的 `NEUTRAL` 就是放行。`Filter.decide` 的注释（`Filter.java` 第 51-56 行）说 ACCEPT 表示不再咨询链上其他过滤器并记录这条事件，说的就是这个短路。

三个内置 Filter 的 `decide` 本身：

`ThresholdFilter`（第 39-48 行）。未启动返回 `NEUTRAL`（不拦截）。已启动时 `event.getLevel().isGreaterOrEqual(配置级别)` 为真返回 `NEUTRAL`，否则返回 `DENY`。它从不返回 `ACCEPT`，所以挡得住低于阈值的事件，但挡不住排在它后面的 Filter。配置 `<level>INFO</level>` 时：INFO/WARN/ERROR 得到 NEUTRAL 并继续；DEBUG/TRACE 得到 DENY，`doAppend` 返回。

`LevelFilter`（第 36-46 行）。未启动返回 `NEUTRAL`。已启动且 `event.getLevel().equals(配置级别)` 返回 `onMatch`，否则 `onMismatch`。`AbstractMatcherFilter` 两个默认都是 `NEUTRAL`（第 20-21 行）。本例把 ERROR 配成 `onMatch=DENY`、`onMismatch=NEUTRAL`：只有 ERROR 死在 ASYNC 上，其他级别继续去入队。若忘了写 `OnMismatch`，不匹配也是 NEUTRAL，这个 Filter 谁也不挡。

`EvaluatorFilter`（第 60-75 行）。自己或里面的 evaluator 没启动，返回 `NEUTRAL`。`evaluate` 为真返回 `onMatch`，为假返回 `onMismatch`。评估抛 `EvaluationException` 时记一条错误并返回 `NEUTRAL`，评估器写错不会把日志全部丢掉。`OnMarkerEvaluator` 对事件上的每个 Marker 做 `contains(配置名)`，有一个为真即命中（第 43-58 行）。事件还没有 Marker 列表时返回 false，走 `onMismatch`。

这些 `decide` 拿到的是已经构造好的 `ILoggingEvent`。在调用线程上、且还没人调用过 `prepareForDeferredProcessing` 时，`getFormattedMessage()` 仍是懒的，`getMDCPropertyMap()` 一调用就会从 **当前线程** 拷贝 MDC（第 453-461 行）。同步文件的 Filter 跑在调用线程上，这时 `MDC.get` 和 `getMDCPropertyMap()` 看到的是同一份。异步包裹内的 FILE 上的 Filter 跑在 worker 上，见 2.1.9。

### 2.1.8 入队：ASYNC 自己的 Filter 已经通过之后

`AsyncAppender.append` 继承 `AsyncAppenderBase.append`（第 161-167 行），只有 `doAppend` 的第 ③ 步不是 DENY 才会进来：

```java
protected void append(E eventObject) {
    if (isQueueBelowDiscardingThreshold() && isDiscardable(eventObject)) {
        return;                    // 死：不入队
    }
    preprocess(eventObject);       // 冻结句子、线程名、MDC
    put(eventObject);
}
```

丢弃条件两个都必须成立。

`isQueueBelowDiscardingThreshold`（第 169-171 行）是 `blockingQueue.remainingCapacity() < discardingThreshold`。`start()` 里若没配置 `discardingThreshold`，把它设成 `queueSize / 5`（第 111-112 行）。默认 `queueSize` 是 256（第 54-55 行），阈值就是 51。`ArrayBlockingQueue` 的剩余容量小于 51，表示已经放进了超过 `256 - 51 = 205` 个事件，大约超过 80%。队列还很空时，这个条件为假，TRACE 也会入队。

`isDiscardable` 在 classic 的 `AsyncAppender`（第 39-41 行）里是 `level.toInt() <= INFO_INT`，也就是 TRACE、DEBUG、INFO。WARN（30000）和 ERROR（40000）永远不是可丢的。基类默认 `isDiscardable` 返回 false（第 84-86 行），不换成 classic 子类就不会按级别丢。

两个条件都成立时方法直接返回。事件不进队列，worker 永远看不到它，FILE 上的 `ThresholdFilter` 也不会为它执行。本例 root 是 INFO，能诞生的最低就是 INFO，所以队列一过线，业务 INFO 会被丢掉，ERROR 会留下。这是排障时把 DEBUG 放进默认 AsyncAppender 之后"有时有、有时没有"的原因：DEBUG 的整数更小，同样算可丢。

通过丢弃判断后才 `preprocess`（classic 第 44-47 行）：

```java
eventObject.prepareForDeferredProcessing();   // 格式化句子、线程名、MDC 快照
if (includeCallerData)
    eventObject.getCallerData();
```

`prepareForDeferredProcessing`（`LoggingEvent.java` 第 298-302 行）依次调用 `getFormattedMessage()`、`getThreadName()`、`getMDCPropertyMap()`。这一刻才把 `{}` 换成参数，把调用线程的 MDC 放进事件。之后业务线程即使 `MDC.remove`，队列里的事件仍带着刚才的 map。`includeCallerData` 默认 false，不取栈；设成 true 则在 **调用线程** 上取栈，再入队。

然后 `put`（第 173-178 行）：

- `neverBlock == false`（默认）：`putUninterruptibly`（第 181-196 行）循环 `blockingQueue.put`。队列满时 **调用线程阻塞**，直到 worker 取走一些。中断不会把事件丢掉，只是记下中断、继续 put，最后把中断标记补回当前线程。
- `neverBlock == true`：`offer`。队列满时 `offer` 返回 false，返回值被忽略，事件静默消失，调用线程不阻塞。

`AsyncAppender` 只能挂一个下游 Appender。第二个 `addAppender` 被忽略并记警告（第 253-261 行），那个下游不会收到任何事件。`start()` 时一个都没挂，Appender 根本不启动（第 101-103 行），之后所有 `doAppend` 死在未启动。

### 2.1.9 出队：worker 上再做一次完整的 doAppend

`Worker.run`（第 288-318 行）是守护线程，名字 `AsyncAppender-Worker-` 加 Appender 名。

```java
while (parent.isStarted()) {
    E e0 = parent.blockingQueue.take();     // 队列空则阻塞，事件活在队列里
    elements.add(e0);
    parent.blockingQueue.drainTo(elements); // 把当前已经在队列里的其余事件一次取出
    for (E e : elements) {
        aai.appendLoopOnAppenders(e);       // 被包裹 Appender 的 doAppend
    }
}
```

`take` 只在队列空时阻塞。`drainTo` 把取出第一个之后、此刻已经在队列中的事件一并拿走，减少反复唤醒。每一个都调用下游的 `appendLoopOnAppenders`，也就是 FILE 的 `doAppend`。FILE 在这里才跑自己的 Filter 链（本例的 `ThresholdFilter`）、才进入 `append()`。

worker 被 `interrupt` 后 `take`/`drainTo` 抛 `InterruptedException`，循环结束。然后把队列里还剩下的事件再交给下游（第 312-315 行），最后 `detachAndStopAllAppenders()`。`stop()` 里 `join(maxFlushTime)`，默认最多等 1000 毫秒（第 70-71 行、第 142 行）。超时线程还活着时，队列里剩下的事件可能被丢弃，status 里会写剩余个数（第 145-147 行）。

FILE 的 Filter 此时在 worker 线程执行：

- `event.getMDCPropertyMap()` 返回入队前冻结的那份，traceId 还在。
- `MDC.get("traceId")` 读的是 worker 的 ThreadLocal，通常是空的。写在 FILE 上的自定义 Filter 若调用 `MDC.get`，会把已经入队的事件误判成"没有 traceId"。
- `getFormattedMessage()` 返回入队前已经算好的句子，不会用 worker 上后来的参数再格式化一次。

同步把 FILE 直接挂到 root 时没有 worker。Filter 在调用线程的 `doAppend` 里执行，通过后立刻 `append()`。那时 `prepareForDeferredProcessing` 还没被调用，它发生在 `subAppend` 的写之前。所以同步 Filter 看到的是未格式化的事件，除非 Filter 自己调用了 `getFormattedMessage()` 或 `getMDCPropertyMap()`。

### 2.1.10 写字节：滚动不丢事件，空编码和 IO 错误会让这一次没有字节

FILE 的 `doAppend` 通过 Filter 之后调用 `OutputStreamAppender.append`（第 106-111 行）。这里再看一次 `isStarted()`，停机竞态下直接返回。然后 `RollingFileAppender.subAppend`（第 205-223 行）：

```java
triggeringPolicyLock.lock();
try {
    if (triggeringPolicy.isTriggeringEvent(currentlyActiveFile, event)) {
        rollover();                 // 换文件，当前事件不丢
    }
} finally {
    triggeringPolicyLock.unlock();
}
super.subAppend(event);
```

`OutputStreamAppender.subAppend`（第 248-264 行）：

```java
if (!isStarted()) return;
if (event instanceof DeferredProcessingAware) {
    event.prepareForDeferredProcessing();   // 同步路径第一次冻结；异步路径多数字段已经填过
}
writeOut(event);
```

`writeOut`（第 202-204 行）是 `encoder.encode(event)` 得到 `byte[]`，再 `writeBytes`。`writeBytes`（第 207-220 行）：数组为 null 或长度为 0 则返回，磁盘上没有新增字节，也不报错。非空时拿 `streamWriteLock`，锁内再看 `isStarted()`，为真才 `outputStream.write`，并且 `immediateFlush` 为真时 `flush`（第 233-237 行）。编码在锁外面，写在锁里面。

`IOException` 时 `subAppend` 把 `started` 设为 false 并记一条 ErrorStatus（第 259-263 行）。这次的字节没有落盘。此后这个 Appender 的 `doAppend` 在 ① 就返回，直到有人重新 start。

### 2.1.11 把本例的调用走完

root=INFO。步 1 的两个 TurboFilter 按书写顺序执行：`reqType=health` 整次 DENY；`FLOW` 整次 DENY；其余 NEUTRAL，交给级别门。ASYNC 上的 LevelFilter 拒绝 ERROR，FILE 上的 ThresholdFilter 拒绝低于 INFO 的事件。队列未满。调用前线程上是 `traceId=t-9`、`reqType=biz`，除非某一行另写。

| 调用 | MDC | Marker | 步 1 | 步 2 级别门 | 步 5 ASYNC 的 LevelFilter | 步 6 入队 | 步 7 FILE 的 Threshold | 磁盘 |
|---|---|---|---|---|---|---|---|---|
| `info(BIZ, "user {} login", uid)` | `reqType=health`，`traceId=t-9` | BIZ | **MDCFilter DENY**。MarkerFilter 不再执行 | 不到 | 不到 | 不到 | 不到 | 无。没有 LoggingEvent。`traceId` 也救不了这一次 |
| `info(FLOW, "user {} login", uid)` | `reqType=biz`，`traceId=t-9` | FLOW | MDCFilter NEUTRAL，**MarkerFilter DENY** | 不到 | 不到 | 不到 | 不到 | 无。没有 LoggingEvent |
| `debug(BIZ, "user {} login", uid)` | `reqType=biz`，`traceId=t-9` | BIZ | 两道都 NEUTRAL。BIZ 不含 FLOW | 20000 > 10000，死 | 不到 | 不到 | 不到 | 无。没有 LoggingEvent |
| `info(BIZ, "user {} login", uid)` | `reqType=biz`，`traceId=t-9` | BIZ | 两道都 NEUTRAL | 20000 > 20000 为假 | 不是 ERROR，NEUTRAL，进入 append | 队列空，INFO 也入队。入队前格式化，并把 `traceId=t-9` 拷进事件 | INFO ≥ INFO，NEUTRAL，写 | `app.log` 有 `[t-9] BIZ user … login`。worker 上 `MDC.get("traceId")` 是空的，`%X` 用的是事件里那份 |
| `info("user {} login", uid)` | `reqType=biz`，`traceId=t-9` | 无 | Marker 为 null，走 onMismatch，NEUTRAL | 通过 | NEUTRAL，入队 | 同上一行 | 写 | `app.log` 有 `[t-9]`，marker 那一格是空的 |
| `error(BIZ, "user {} login", uid)` | `reqType=biz`，`traceId=t-9` | BIZ | NEUTRAL | 通过 | **DENY**，`doAppend` 返回 | 不入队 | 不到 | 无。事件已诞生，死在包裹层。MDC 和 Marker 都没能让 ERROR 入队 |
| `info(BIZ, ...)` 且队列已超过约 80% | `reqType=biz` | BIZ | NEUTRAL | 通过 | NEUTRAL | `remainingCapacity < 51` 且 INFO 可丢，**不入队** | 不到 | 无。同样水位下的 ERROR 会入队，但本例 ERROR 在步 5 已经死了 |

`log.info` 返回之后业务线程立刻 `MDC.clear()`，不影响已经入队的那条：`preprocess` 在 `put` 之前把 map 拷走了。

再把 FILE 直接挂到 root、去掉 ASYNC：`error(BIZ, ...)` 不再被 LevelFilter 拒绝，FILE 的 Threshold 对 ERROR 返回 NEUTRAL，调用线程里格式化并写入，`%X{traceId}` 仍是调用线程上的 `t-9`。`debug(BIZ, ...)` 仍然死在步 2。

把 MarkerFilter 的 `OnMatch` 改成 `ACCEPT`、`OnMismatch` 仍是 `NEUTRAL` 时，`debug(FLOW, ...)` 在 `reqType=biz` 下会跳过步 2，创建事件并进入步 5。ASYNC 的 LevelFilter 不拒绝 DEBUG，于是入队；FILE 的 Threshold 对 DEBUG 返回 DENY，worker 把已出队的事件丢掉，文件里仍然没有。全局 ACCEPT 只改步 2。步 7 仍按 FILE 自己的 Filter 执行。`reqType=health` 时 MDCFilter 排在前面，FLOW 的 ACCEPT 不会被执行。

### 2.1.12 死亡点清单

1. TurboFilter 链第一个非 NEUTRAL 是 DENY。无事件。
2. 链是 NEUTRAL（或没配 TurboFilter），且 `effectiveLevelInt > 本次 levelInt`。无事件。Fluent 的这条死在 `atXxx()` 返回 NOP 构建器，而不是死在 `log(LoggingEvent)`。
3. 父链上没有任何 Appender，`writes == 0`。有事件，无字节，一条 status 警告。
4. 目标 Appender 未启动。`doAppend` ① 返回。
5. Console 的线程内重入。File / Rolling / Async 的守卫是 NOP，不在这里死。
6. 这个 Appender 的 Filter 链结果为 DENY。兄弟 Appender 继续。挂在 ASYNC 上时，死在入队前。
7. 队列剩余容量 `< discardingThreshold` 且级别 ≤ INFO。不入队。WARN/ERROR 不走这条。
8. `neverBlock=true` 且 `offer` 失败。静默丢弃。默认 `neverBlock=false` 时调用线程阻塞，不丢。
9. 停机 `join` 超过 `maxFlushTime`（默认 1000 毫秒），队列里剩下的事件可能丢。
10. worker 上被包裹 Appender 未启动，或它的 Filter 链为 DENY。事件已经出队。
11. `encoder.encode` 得到 null 或空数组。`writeBytes` 直接返回。
12. `write` 抛 IOException。这一次没有字节，Appender 被标成未启动，后续死在第 4 条。`doAppend` 里其他异常同样被吃掉，不重试。

滚动本身不在这份清单里。`isTriggeringEvent` 为真只是先换文件，当前事件继续 `super.subAppend`。

## 2.2 Log4j2：一条日志从调用到字节，每一次可以死掉的判断

和 2.1 一样按真实调用顺序写。先写同步 Logger 加上一个 `<Async>` Appender 的路径，因为它和 Logback 的 `AsyncAppender` 最接近：全局过滤和级别在调用线程，Logger 上的 Filter 也在调用线程并且在入队之前，被包裹文件上的 Filter 在出队之后。全异步 `AsyncLogger` 把 Logger 过滤器挪到出队之后，单独放在 2.2.10，不要和这条路径混用。

整数方向和 Logback 相反：OFF 0、FATAL 100、ERROR 200、WARN 300、INFO 400、DEBUG 500、TRACE 600。数值越大越啰嗦。

```java
private static final Marker FLOW = MarkerManager.getMarker("FLOW");
private static final Marker AUDIT = MarkerManager.getMarker("AUDIT");
private static final Marker BIZ = MarkerManager.getMarker("BIZ");
private static final Logger log = LogManager.getLogger("com.example.payment");

ThreadContext.put("traceId", "t-9");
ThreadContext.put("reqType", "biz");     // health 在全局 Filter 被整次丢掉
try {
    log.info(BIZ, "user {} login", uid);
} finally {
    ThreadContext.clearMap();
}
```

```xml
<Configuration>
  <!-- 步 1：事件还不存在。先看 ThreadContext，再看 Marker。收成 CompositeFilter -->
  <ThreadContextMapFilter onMatch="DENY" onMismatch="NEUTRAL">
    <KeyValuePair key="reqType" value="health"/>
  </ThreadContextMapFilter>
  <MarkerFilter marker="FLOW" onMatch="DENY" onMismatch="NEUTRAL"/>

  <Appenders>
    <RollingFile name="FILE" fileName="logs/app.log"
                 filePattern="logs/app.%d{yyyy-MM-dd}.log.gz">
      <PatternLayout pattern="%d %-5level [%X{traceId}] %marker %msg%n"/>
      <!-- 步 9：Appender 只接收一个 Filter。两个放进 Filters，按书写顺序组成 CompositeFilter -->
      <Filters>
        <ThresholdFilter level="INFO" onMatch="NEUTRAL" onMismatch="DENY"/>
        <ThreadContextMapFilter onMatch="DENY" onMismatch="NEUTRAL">
          <KeyValuePair key="traceId" value="drop-me"/>
        </ThreadContextMapFilter>
      </Filters>
      <Policies><TimeBasedTriggeringPolicy/></Policies>
    </RollingFile>

    <Async name="ASYNC" bufferSize="1024" blocking="true">
      <!-- 步 7：调用线程、入队之前。Async 自己的 Filter 可以写在这里 -->
      <LevelMatchFilter level="ERROR" onMatch="DENY" onMismatch="NEUTRAL"/>
      <AppenderRef ref="FILE"/>
    </Async>
  </Appenders>

  <Loggers>
    <!-- 步 5：有事件之后、入队之前。DENY 会连 root 一起切断 -->
    <Logger name="com.example.payment" level="debug">
      <MarkerFilter marker="AUDIT" onMatch="DENY" onMismatch="NEUTRAL"/>
    </Logger>
    <Root level="info">
      <AppenderRef ref="ASYNC"/>
    </Root>
  </Loggers>
</Configuration>
```

`<Configuration>` 下的两个 Filter 都会挂上：`AbstractConfiguration` 对每个 Filter 子节点调用 `addFilter`（第 740-741 行），第二个进来时收成 `CompositeFilter`，顺序就是 XML 顺序。Appender 的 Builder 只有一个 `Filter` 字段（`AbstractFilterable.Builder` 第 40-41 行），所以 FILE 上的级别过滤和 `traceId` 过滤要写进 `<Filters>`，否则后一个不会绑定。`<Async>` 里面的 `<AppenderRef>` 上不能再挂 Filter，类注释写的是 references 上不允许（`AsyncAppender.java` 第 55-57 行）。FILE 里的 `<Filters>` 在 dispatcher 出队之后执行。

| 步 | 线程 | 做什么 | 这一步会死的条件 |
|---|---|---|---|
| 1 | 调用线程 | `<Configuration>` 上的 Filter。本例先 `ThreadContextMapFilter`（读此刻的 ThreadContext），再 `MarkerFilter`（读这次调用的 Marker） | 第一个非 NEUTRAL 是 DENY。还没有 `LogEvent` |
| 2 | 调用线程 | 级别门 `logger.intLevel >= 本次.intLevel` | 步 1 是 NEUTRAL（或没配全局 Filter）且不等式不成立。ACCEPT 跳过本步 |
| 3 | 调用线程 | `messageFactory.newMessage`，再经 ReliabilityStrategy 创建 `LogEvent` | 不再做全局过滤。ThreadContext 在这里拷进事件。`{}` 默认还不替换 |
| 4 | 调用线程 | 从当前 LoggerConfig 走到父节点 | 子节点 Filter 为 DENY 时父节点根本不会被调用 |
| 5 | 调用线程 | `LoggerConfig.isFiltered` | 只有 `Result.DENY`。ACCEPT 与 NEUTRAL 都继续，并且仍会 `logParent` |
| 6 | 调用线程 | `AppenderControl`：AppenderRef 的 Filter、AppenderRef 的 level、重入 | 任一成立则跳过这一个 Appender |
| 7 | 调用线程 | Appender 自己的 `isFiltered`，然后 `AsyncAppender.append` | Appender 上的 Filter 为 DENY 则不入队。未启动抛异常 |
| 8 | 调用线程 | `offer` / 队列满时的路由 | `blocking=false` 且队列满：主队列不要，改走 error appender。`Discard` 策略下不够严重的事件直接丢掉 |
| 9 | dispatcher | 每个下游 `AppenderControl.callAppender` | 下游未通过自己的 Filter 或 level。事件已经出队 |
| 10 | dispatcher | `checkRollover`，然后 `layout.encode`，`write` | 布局得到空数组则不写。写失败由 ErrorHandler 处理 |

把 `FILE` 直接挂到 root、去掉 `<Async>` 时，没有步 8 和步 9 的分离：步 7 的 Filter 就是 FILE 的 `ThresholdFilter`，通过后立刻进入步 10，全在调用线程。

### 2.2.1 入口：先 isEnabled，通过之后才创建 Message

`logger.info("user {} login", uid)` 进入 `AbstractLogger.logIfEnabled`（`log4j-api/.../AbstractLogger.java` 第 2411-2414 行）：

```java
public void logIfEnabled(final String fqcn, final Level level, final Marker marker,
                         final String message, final Object... params) {
    if (isEnabled(level, marker, message, params)) {
        logMessage(fqcn, level, marker, message, params);
    }
}
```

`isEnabled` 为 false 时不会调用 `logMessage`，也就不会 `messageFactory.newMessage`（同文件第 2688-2697 行那类实现：先 `newMessage`，再 `logMessageSafely`）。占位符替换发生在 `Message.getFormattedMessage()`，不在 `isEnabled` 里。全局 Filter 能看见 format 字符串和参数数组，看不见已经拼好的句子。

【源码证据】core 的 `Logger.isEnabled` 把判断交给 `PrivateConfig.filter`（`log4j-core/.../core/Logger.java` 第 199-206 行、第 532-540 行）：

```java
boolean filter(final Level level, final Marker marker, final String msg) {
    final Filter filter = config.getFilter();          // Configuration 上的，不是 Logger 上的
    if (filter != null) {
        final Filter.Result r = filter.filter(logger, level, marker, msg);
        if (r != Filter.Result.NEUTRAL) {
            return r == Filter.Result.ACCEPT;          // ACCEPT → true；DENY → false
        }
    }
    return level != null && intLevel >= level.intLevel();
}
```

`config.getFilter()` 在 `hasFilter()`（第 528-529 行）里也是这份。挂在 `<Logger>` 上的 Filter 不在这里。

### 2.2.2 全局 Filter：第一个 ACCEPT 或 DENY 结束，默认不匹配就是 DENY

多于一个时收成 `CompositeFilter`。`filter(...)`（`CompositeFilter.java` 第 179-188 行）从下标 0 走到尾，当前结果是 `ACCEPT` 或 `DENY` 就返回，不再问后面的。全部 `NEUTRAL` 才把 `NEUTRAL` 交回 `PrivateConfig.filter`。只有一个全局 Filter 时不包这一层，直接调用它。

单个 Filter 的默认动作和 Logback 相反。`AbstractFilter` 构造时（第 109-110 行）：`onMatch` 省略是 `NEUTRAL`，`onMismatch` 省略是 `DENY`。只写 `<MarkerFilter marker="AUDIT"/>` 且放在 `<Configuration>` 下，不带 AUDIT 的调用会在步 1 死掉，`app.log` 会空。要“只影响带标记的调用”，必须写 `onMismatch="NEUTRAL"`。

这一步调用的是不带 `LogEvent` 的重载。`MarkerFilter` 只看参数里的 Marker（第 70-72 行 `isInstanceOf`）。`ThreadContextMapFilter` 的这套重载读的是调用线程上此刻的 ThreadContext（第 109-128 行 `injector.getValue`），不是事件快照。`ThresholdFilter` 只看参数里的 Level（第 76-77 行）：`testLevel.isMoreSpecificThan(配置级别)` 为真走 `onMatch`，否则 `onMismatch`。全局放一个没改默认值的 `<ThresholdFilter level="INFO"/>` 时，INFO 及以上得到 NEUTRAL 再去碰级别门，DEBUG/TRACE 得到默认的 DENY，事件不会创建。

| 全局链结果 | 级别门 | 后面 |
|---|---|---|
| `DENY` | 不看 | `isEnabled` 返回 false，无 Message，无事件 |
| `NEUTRAL` | `intLevel >= 本次.intLevel` 才继续 | 不成立则同样无事件 |
| `ACCEPT` | 不看 | 继续创建 Message 和事件。root 是 INFO 时 DEBUG 也会创建 |

`ACCEPT` 只跳过级别门。Logger 上的 Filter、Appender 上的 Filter、队列满时的丢弃都还在。

### 2.2.3 级别门：logger 的 int 大于等于本次调用才放行

`intLevel` 来自这个 Logger 所绑定的 `LoggerConfig.getLevel()`（`PrivateConfig` 构造，第 500 行、第 517-518 行）。root 配 `level="info"` 时 int 是 400。

| 调用 | 本次 int | `400 >= 本次` | 结果 |
|---|---|---|---|
| `trace` | 600 | 假 | 死在步 2 |
| `debug` | 500 | 假 | 死在步 2 |
| `info` | 400 | 真 | 继续 |
| `error` | 200 | 真 | 继续 |

`Level.isMoreSpecificThan`（第 212-213 行）是 `this.intLevel <= 对方.intLevel`，越严重越“更具体”。Appender 上的 `ThresholdFilter` 用的是它，不是这道门的 `>=`。`com.example.payment` 写成 `level="debug"`（500）时，这个名字上的 DEBUG 能过步 2；root 仍是 400，别的包的 DEBUG 仍死在步 2。

### 2.2.4 事件诞生：上下文先拷走，ReliabilityStrategy 不按级别丢事件

`isEnabled` 为 true 之后，`Logger.logMessage`（第 169-173 行）把 Message 交给 `LoggerConfig` 的 `ReliabilityStrategy`。默认策略由系统属性 `log4j.ReliabilityStrategy` 决定，缺省字符串是 `AwaitCompletion`（`ReliabilityStrategyFactory.java` 第 47-50 行）。它的 `log(...)`（`AwaitCompletionReliabilityStrategy.java` 第 56-70 行）取当前仍有效的 `LoggerConfig`，再调用 `config.log(...)`。它不看 Level，也不按级别丢事件。停机时它会等已经开始的 `log` 结束。

`LoggerConfig.log(String, ...)`（第 638-654 行）用 `logEventFactory.createEvent` 造出 `LogEvent`。同步路径上的 `ReusableLogEventFactory` 在调用线程执行 `injector.injectContextData`（`ReusableLogEventFactory.java` 第 117 行），把此刻的 ThreadContext 放进事件。此后业务线程 `ThreadContext.clearMap()`，事件里的 map 不变。`ParameterizedMessage` 的格式化字符串仍可能是懒的，直到有人调用 `getFormattedMessage()`。

然后 `log(event)`（第 728-742 行）：

```java
protected void log(final LogEvent event, final LoggerConfigPredicate predicate) {
    if (!isFiltered(event)) {
        processLogEvent(event, predicate);
    }
}
```

### 2.2.5 Logger 上的 Filter：只有 DENY 才死，并且父 Logger 也收不到

`AbstractFilterable.isFiltered`（第 152-153 行）：

```java
return filter != null && filter.filter(event) == Filter.Result.DENY;
```

没有 Filter 时返回 false，事件继续。结果是 `ACCEPT` 或 `NEUTRAL` 时也返回 false，事件继续。只有 `DENY` 让 `isFiltered` 为 true，`processLogEvent` 不会执行。

`processLogEvent`（第 762-768 行）先 `callAppenders`，再 `logParent`。`logParent`（第 796-800 行）在 `additive && parent != null` 时调用 `parent.log(event)`。父节点的 `log` 会再执行父节点自己的 `isFiltered`。子节点已经 DENY 时，`logParent` 在 `processLogEvent` 里面，根本到不了。所以 `<Logger name="com.example.payment">` 上的 `MarkerFilter` 对 AUDIT 返回 DENY 时，root 的 ASYNC 收不到这条事件，审计文件里也没有。Logback 的 Logger 上挂不住 Filter。那边 Appender 上的 DENY 只跳过这一个 Appender，父 Logger 上的 Appender 仍会收到事件。

`ACCEPT` 在这一层只是让 `CompositeFilter` 短路，不再看同级后面的 Filter。它不会跳过 `logParent`，也不会强迫 Appender 写入。

本例 `com.example.payment` 的 MarkerFilter：`onMatch=DENY`、`onMismatch=NEUTRAL`。`log.info(AUDIT, ...)` 从 `com.example.payment` 的 Logger 打出时死在步 5，不入队。同一个 AUDIT 从别的包打出时，那个 Logger 没有这道 Filter，事件走到 root，可以入队。

### 2.2.6 AppenderControl：三道都在调用 append 之前

`callAppenders`（第 803-808 行）遍历 `AppenderControl[]`。`callAppender`（`AppenderControl.java` 第 84-104 行、第 130-156 行）：

```java
public void callAppender(final LogEvent event) {
    if (shouldSkip(event)) {
        return;
    }
    callAppenderPreventRecursion(event);
}
private boolean shouldSkip(final LogEvent event) {
    return isFilteredByAppenderControl(event)   // ① AppenderRef 上的 Filter，仅 DENY
        || isFilteredByLevel(event)             // ② <AppenderRef level="...">
        || isRecursiveCall();                   // ③ 同一 AppenderControl 重入
}
private boolean isFilteredByLevel(final LogEvent event) {
    return level != null && intLevel < event.getLevel().intLevel();
}
private boolean isFilteredByAppender(final LogEvent event) {
    return appender instanceof Filterable && ((Filterable) appender).isFiltered(event);
}
```

① 只认 DENY，和 Logger 那层一样。`ACCEPT` 不会让 ② 被跳过：`shouldSkip` 先看 ①，① 不是 DENY 就继续看 ②。AppenderRef 上的 Filter 返回 ACCEPT，级别仍可能把事件跳过。

② 没写 `level` 时 `level` 为 null，`isFilteredByLevel` 第 103 行直接为假（构造时把 int 记成 `Level.ALL`，第 57 行，但比较之前先看引用是不是 null）。写了 `level="ERROR"`（200）时：事件 INFO（400）满足 `200 < 400`，跳过；事件 ERROR（200）和 FATAL（100）不满足，继续。留下的是至少和 ref 一样具体的事件。

③ `recursive` 是 `ThreadLocal`。同一线程在这个 `AppenderControl` 里再次 `callAppender`（编码或 `toString` 里又打日志，并且又走到同一个 Appender）时返回，避免死锁。File 路径上这是会丢事件的重入点。

三道都过了，`callAppender0` 再看 ④：Appender 元素内部的 Filter，仍然只有 DENY 才算过滤掉（`isFiltered` 同 2.2.5）。本例 ASYNC 上的 `LevelMatchFilter` 在这里执行，在 `append()` 之前。ERROR 匹配则 DENY，不入队；其他级别 `onMismatch=NEUTRAL`，`isFiltered` 为 false，进入 `append()`。

兄弟 `AppenderControl` 独立。一个 DENY 不阻止数组里的下一个。

### 2.2.7 入队：memento 先冻结，offer 失败才谈阻塞或丢弃

`AsyncAppender.append`（第 161-182 行）在自己的 Filter 已经放过之后：

```java
if (!isStarted()) {
    throw new IllegalStateException("AsyncAppender " + getName() + " is not active");
}
final Log4jLogEvent memento = Log4jLogEvent.createMemento(logEvent, includeLocation);
InternalAsyncUtil.makeMessageImmutable(logEvent.getMessage());
if (!transfer(memento)) {
    if (blocking) {
        ...
    } else {
        error("... queue is full");
        logToErrorAppenderIfNecessary(false, memento);
    }
}
```

未启动时 `append` 抛 `IllegalStateException`。`AppenderControl.tryCallAppender` 接住它（第 158-164 行）；`ignoreExceptions` 为 true（Appender 的默认）时业务线程看不到这个异常。这一次没有入队。

`createMemento` 走 `Log4jLogEvent.Builder`（`Log4jLogEvent.java` 第 1005-1006 行、第 161-164 行）：普通 Message 在这里就调用 `InternalAsyncUtil.makeMessageImmutable`；`ReusableMessage` 则 `memento()` 拷出一份。`append` 第 166 行对原事件再调一次 `makeMessageImmutable`。`includeLocation` 默认 false（Builder 第 328 行），不在调用线程抓栈。`makeMessageImmutable`（`InternalAsyncUtil.java` 第 38-42 行）在系统属性没要求后台格式化、Message 也没有 `@AsynchronouslyFormattable` 时立刻 `getFormattedMessage()`。参数在入队前被烤进字符串，调用线程随后改参数数组也不影响队列里的句子。上下文 map 若还没冻结，Builder 会再拷一份（第 167-171 行）。

`transfer`（第 185-188 行）：队列是 `TransferQueue` 时 `tryTransfer`（有消费者在等就直接交给它，不必进队列）；否则 `offer`。成功则调用线程到此结束，事件活在队列里。

`offer` 失败且 `blocking=true`（Builder 默认，第 315 行）：

- 递归深度大于 1：不再等待，改在当前线程 `dispatch`，避免和后台线程互相等死（第 169-171 行）。
- 否则问 `AsyncQueueFullPolicy.getRoute`：
  - `DefaultAsyncQueueFullPolicy`（第 29-42 行）：普通调用线程得到 `ENQUEUE`，`queue.put` 阻塞到有空位（第 206-209 行）。当前线程就是后台线程或 `Log4jThread` 时得到 `SYNCHRONOUS`，在当前线程直接 `dispatch`，不入队。
  - `DiscardingAsyncQueueFullPolicy`（第 48-60 行）：`level.isLessSpecificThan(threshold)` 为真则 `DISCARD`。`isLessSpecificThan` 是 `this.intLevel >= threshold.intLevel`（`Level.java` 第 196-197 行）。属性 `log4j2.asyncQueueFullPolicy=Discard` 时阈值默认 INFO（`AsyncQueueFullPolicyFactory.java` 第 107 行）。队列满时 INFO/DEBUG/TRACE 被丢掉，WARN/ERROR/FATAL 走默认策略继续排队或同步写。第一次丢弃打一条 status，之后静默。
  - `put` 被中断时再 `offer` 一次（第 227-233 行）。第二次仍失败才警告，并尽量交给 error appender。中断标记会补回调用线程。注释写明第二次 `offer` 成功时，存在和第一次重复的可能。

`offer` 失败且 `blocking=false`：不阻塞。记一条 error，若配置了 `errorRef`，把 memento 交给那个 Appender（仍在调用线程，会走那个 Appender 的 `callAppender`，包括它的 Filter）。主文件没有这一行。

队列容量默认 1024（`DEFAULT_QUEUE_SIZE`，第 62 行；Builder 的 `bufferSize` 初始值，第 321 行）。停机等待默认 0（第 318 行），createAppender 的参数说明写的是 0 表示一直等到队列排空（第 251-253 行）。

### 2.2.8 出队：下游 Appender 再走一遍 AppenderControl

`AsyncAppenderEventDispatcher.dispatch`（第 119-134 行）在 dispatcher 线程上，对每个下游 `AppenderControl.callAppender`。FILE 的 `ThresholdFilter`、FILE 的 `AppenderRef` level、FILE 的重入检查都在这里。事件已经离开 ASYNC 的队列。

这一刻 `ThreadContext` 是 dispatcher 的，通常是空的。`ThreadContextMapFilter` 若写在 FILE 上，走的是 `filter(LogEvent)`（`ThreadContextMapFilter.java` 第 131-132 行），读 `event.getContextData()`，那是步 3 或 `createMemento` 时拷走的 map。写在 `<Configuration>` 上的同名过滤器早在步 1 就用调用线程的实时 map 做过决定，不会在这里再做一次。

某个下游 `callAppender` 抛异常只记 trace，换下一个下游。全部失败且配置了 error appender 时，再 `errorAppender.callAppender`（第 136-139 行）。error appender 自己的 Filter 仍会执行。

### 2.2.9 写字节

FILE 的 `RollingFileAppender.append`（第 479-481 行）先 `getManager().checkRollover(event)`，再 `super.append`。滚动不丢当前事件。`AbstractOutputStreamAppender.tryAppend`（第 218-223 行）在直接编码器打开时 `layout.encode` 写入 manager，并在 `immediateFlush` 或 `endOfBatch` 时 `flush`（第 226-230 行）；否则 `toByteArray`，数组非空才 `manager.write`（第 233-237 行）。空数组没有字节，也不算一条新的死亡异常。写失败由 `append` 第 212-214 行交给 ErrorHandler，再把异常抛出去，由 `AppenderControl.tryCallAppender` 接住（第 158-164 行）。

### 2.2.10 全异步 AsyncLogger：Logger 过滤器在出队之后

`log4j2.contextSelector=org.apache.logging.log4j.core.async.AsyncLoggerContextSelector`，或者代码拿到的是 `AsyncLogger` 时，步 1 和步 2 仍在调用线程的 `isEnabled` 里。没通过就不会 `publish`。通过之后，ThreadLocal 复用路径调用 `publish`（第 235-241 行、第 271-274 行）；参数数组路径直接 `tryPublishEvent`（第 399-411 行）。两条都不经过同步的 `LoggerConfig.log`。发布失败才进入满环处理。

Disruptor 的 `translateTo` 跑在调用线程上（`RingBufferLogEventTranslator.java` 第 62-82 行）。它调用 `INJECTOR.injectContextData`，ThreadContext 在发布前拷进环上的事件。线程名、线程 id 用的是 translator 里已经记下的调用线程的值（第 56-58 行），不是消费线程。同一条 `setValues` 里会 `setMessage`：`ReusableMessage` 当场 `formatTo`，其他 Message 走 `makeMessageImmutable`（`RingBufferLogEvent.java` 第 143-152 行）。占位符在入环前替换，除非打开了后台格式化。

环的默认容量：开了 ThreadLocal 复用时 4096，否则 `256 * 1024`（`DisruptorUtil.java` 第 35-37 行、第 87-88 行）。

环满时 `handleRingBufferFull`（第 277-301 行）和 AsyncAppender 同一套 `EventRoute`：递归调用改走当前线程；`DISCARD` 则 `translator.clear()`，事件消失；`ENQUEUE` 等待可发布；`SYNCHRONOUS` 在当前线程写。`Discard` 策略同样是“不比阈值更具体的级别丢掉”，默认阈值 INFO。

消费线程执行 `RingBufferLogEvent.execute`（第 170-172 行）→ `actualAsyncLog`（第 560-568 行）→ `reliabilityStrategy.log(this, event)`。`Logger` 实现了 `Supplier<LoggerConfig>`（`Logger.java` 第 56 行），所以这是 `AwaitCompletionReliabilityStrategy.log(Supplier, LogEvent)`（第 105-111 行），里面再 `config.log(event)`。**步 5 的 `isFiltered` 发生在这里**，用的是 `filter(LogEvent)`，读事件里的 context data，不是消费线程的 ThreadContext。DENY 仍然切断 `logParent`。Appender 的 Filter 也在这条消费线程上，紧挨着写文件，中间没有再入一次队（除非 Appender 自己又是 `<Async>`）。

因此同一份 `<Logger>` 上的 `MarkerFilter`：

| 运行方式 | 它在哪执行 | 队列里有没有这条 |
|---|---|---|
| 同步 Logger + `<Async>` Appender | 调用线程，入队前 | DENY 则没有 |
| `AsyncLogger` | Disruptor 消费线程，出队后 | 先占了一个环槽，再被 Logger 过滤器丢掉 |

`AsyncLoggerConfig`（只把部分 Logger 配成 async）夹在两者中间。第一次遇到带 Appender 的 `AsyncLoggerConfig` 时（第 128-157 行）：先在调用线程 `isFiltered`，DENY 则同步和异步都不做。没 DENY 则先 `processLogEvent(..., SYNCHRONOUS_ONLY)` 把同步 Appender 在调用线程写完，再 `tryEnqueue`。后台线程回调 `logToAsyncLoggerConfigsOnCurrentThread`（第 203-205 行）时注释写明跳过已经在调用线程做过的 Filter，只 `processLogEvent(..., ASYNCHRONOUS_ONLY)`。队列满的路由和上面相同（第 174-183 行）。

### 2.2.11 把本例的调用走完

配置用本节开头那份。调用发生在 `com.example.payment`（Logger 级别 DEBUG，且有 AUDIT 的 DENY）。步 1 两个全局 Filter 收成 `CompositeFilter`：`reqType=health` 整次 DENY；`FLOW` 整次 DENY；其余 NEUTRAL，交给级别门。FILE 在出队后再看 `traceId` 是不是 `drop-me`。队列未满。`<Async>` 的 `blocking=true`。没另行注明时，线程上是 `traceId=t-9`、`reqType=biz`。

| 调用 | MDC | Marker | 步 1 全局 | 步 2 级别（这个 Logger 是 DEBUG=500） | 步 5 Logger 的 MarkerFilter | 步 7 ASYNC 的 LevelMatch | 步 9 FILE | 磁盘 |
|---|---|---|---|---|---|---|---|---|
| `info(BIZ, "user {} login", uid)` | `reqType=health`，`traceId=t-9` | BIZ | **ThreadContextMapFilter DENY**。MarkerFilter 不再执行 | 不到 | 不到 | 不到 | 不到 | 无。没有 Message，没有事件 |
| `info(FLOW, "user {} login", uid)` | `reqType=biz`，`traceId=t-9` | FLOW | MapFilter NEUTRAL，**MarkerFilter DENY** | 不到 | 不到 | 不到 | 不到 | 无。没有事件 |
| `debug(BIZ, "user {} login", uid)` | `reqType=biz`，`traceId=t-9` | BIZ | 两道都 NEUTRAL。BIZ 不是 FLOW | 500>=500，过 | 不是 AUDIT，NEUTRAL | 不是 ERROR，NEUTRAL，入队。入队前格式化，contextData 里有 `t-9` | Threshold：DEBUG 不如 INFO 具体，DENY。traceId 过滤器不再问 | 无。事件已出队 |
| `info(BIZ, "user {} login", uid)` | `reqType=biz`，`traceId=t-9` | BIZ | NEUTRAL | 过 | NEUTRAL | 入队 | Threshold NEUTRAL；`traceId` 不是 `drop-me`，NEUTRAL，写 | `app.log` 有 `[t-9] BIZ user … login`。dispatcher 的 ThreadContext 是空的，`%X` 和 FILE 上的 MapFilter 用的都是事件里那份 |
| `info("user {} login", uid)` | `reqType=biz`，`traceId=t-9` | 无 | Marker 为 null，走 onMismatch，NEUTRAL | 过 | NEUTRAL | 入队 | 写 | `app.log` 有 `[t-9]`，marker 那一格是空的 |
| `info(BIZ, "user {} login", uid)` | `reqType=biz`，`traceId=drop-me` | BIZ | NEUTRAL（`drop-me` 不是 health） | 过 | NEUTRAL | 入队。memento 里的 traceId 已是 `drop-me` | Threshold NEUTRAL，**MapFilter DENY** | 无。事件已出队。调用线程上后来 `clearMap()` 也改变不了这个判断 |
| `error(BIZ, "user {} login", uid)` | `reqType=biz`，`traceId=t-9` | BIZ | NEUTRAL | 过 | NEUTRAL | **DENY**，不入队 | 不到 | 无 |
| `info(AUDIT, "user {} login", uid)` | `reqType=biz`，`traceId=t-9` | AUDIT | NEUTRAL | 过 | **DENY**，`logParent` 不执行 | 不到 | 不到 | 无。root 的文件也没有 |
| 别的包 `debug(BIZ, ...)`，该包 Logger 仍是 INFO=400 | `reqType=biz` | BIZ | NEUTRAL | 400>=500 为假 | 不到 | 不到 | 不到 | 无。没有事件 |

`log.info` 返回之后业务线程立刻 `ThreadContext.clearMap()`，不影响已经入队的那条：contextData 在创建事件和 `createMemento` 时已经拷走。

把全局 `MarkerFilter` 配成对 `AUDIT` `onMatch=ACCEPT`、`onMismatch=NEUTRAL`，并且把它放在 ThreadContextMapFilter 之后时，`debug(AUDIT)` 在 `reqType=biz` 下能跳过步 2。它仍然要过步 5：本例步 5 对 AUDIT 是 DENY，所以还是进不了队列。全局 ACCEPT 只改步 2。步 5 和步 9 仍按各自的 Filter 执行。`reqType=health` 时排在前面的 MapFilter 已经 DENY，AUDIT 的 ACCEPT 不会被执行。

队列已满且使用默认阻塞策略时，`info(BIZ, ...)` 会停在 `put` 上，不丢。换成 `Discard` 且阈值 INFO 时，这条 `info` 在步 8 被丢掉，`error` 仍会排队；但本例的 `error` 在步 7 就已经死了，轮不到步 8。Marker 和 MDC 不参与这道丢弃，它只看级别。

改成 `AsyncLogger` 之后，上表步 5 和步 7、步 9 都挪到消费线程。`info(AUDIT)` 会先占环槽，再在消费线程被 Logger 的 MarkerFilter 丢掉，文件里仍然没有。步 1 的 ThreadContext 和 Marker 仍在调用线程上决定，`reqType=health` 或 `FLOW` 不会入环。`traceId` 在 `translateTo` 里拷进环上的事件，步 9 的 MapFilter 读的是这份拷贝。

### 2.2.12 死亡点清单

1. 全局 Filter 第一个非 NEUTRAL 是 DENY。无 Message，无事件。省略 `onMismatch` 时默认就是这条。
2. 全局结果是 NEUTRAL，且 `logger.intLevel >= 本次.intLevel` 不成立。无事件。
3. LoggerConfig 的 `filter(event)` 为 DENY。父 LoggerConfig 不再收到。同步加 `<Async>` 时死在入队前；`AsyncLogger` 时死在出队后。
4. AppenderRef 上的 Filter 为 DENY，或 AppenderRef 的 `level` 比事件更具体（`ref.intLevel < event.intLevel`），或同一 `AppenderControl` 重入。只跳过这一个 Appender。
5. Appender 自己的 Filter 为 DENY。挂在 `<Async>` 上时不入队。
6. `<Async>` 未启动。`append` 抛 `IllegalStateException`。
7. 队列满且 `blocking=false`。主队列不要；有 `errorRef` 才在调用线程再试一次。
8. 队列满且策略为 `Discard`，事件 `isLessSpecificThan(阈值)`。默认阈值 INFO，即 INFO 及更啰嗦的丢掉，WARN 及以上继续。
9. dispatcher 上下游 Appender 的 Filter、level 或重入把事件跳过。事件已经出队。
10. `layout.toByteArray` 为空。不写。
11. 写失败。这一次没有字节。是否再抛给业务线程由 `ignoreExceptions` 决定。

滚动不在这份清单里。`checkRollover` 之后仍会 `super.append`。

## 2.3 两种引擎里，Filter 相对队列的位置

| | 全局 Filter | 级别门 | Logger 上的 Filter | 包裹层 Appender 的 Filter | 被包裹文件的 Filter | 队列快满时丢掉谁 |
|---|---|---|---|---|---|---|
| Logback `AsyncAppender` | 调用线程，事件前，TurboFilter | 调用线程，仅当 Turbo 为 NEUTRAL | 没有这一层 | 调用线程，入队前 | worker，出队后 | 剩余容量 `< queueSize/5` 且级别 ≤ INFO |
| Log4j2 同步 Logger + `<Async>` | 调用线程，事件前 | 调用线程，仅当全局为 NEUTRAL | 调用线程，入队前；DENY 切断父 Logger | 调用线程，入队前 | dispatcher，出队后 | 默认阻塞。`Discard` 时默认丢掉 INFO 及更啰嗦的 |
| Log4j2 `AsyncLogger` | 调用线程，事件前 | 调用线程 | **消费线程，出队后**；DENY 仍切断父 Logger | 消费线程，若 Appender 自己又是异步则再入一次队 | 再下一层的消费线程 | 环满时的 `EventRoute`。`Discard` 规则同上 |

三处读 MDC / ThreadContext 的时机也按这个表：全局 Filter 读调用线程的实时 map；入队前已经 `injectContextData` 或 `createMemento` 的事件，出队后的 Filter 要读事件里的那份。Log4j2 出队后的 `ThreadContextMapFilter.filter(LogEvent)` 就是这么读的。

## 2.4 本章小结

- Logback 传统 API 的顺序是：TurboFilter 链（第一个 DENY/ACCEPT 结束）→ 仅当结果为 NEUTRAL 才做级别比较 → 构造 `LoggingEvent` → 沿 additive 父链 `doAppend`。Fluent API 的级别比较在 `atXxx()`，`log(LoggingEvent)` 只再认一次 TurboFilter 的 DENY。
- 1.5 的 File、RollingFile、Console、Async 都走 `UnsynchronizedAppenderBase.doAppend`：未启动则死，Filter 链只有 DENY 才不调用 `append()`，整链 NEUTRAL 仍会写。File 与 Async 的重入守卫是 NOP；Console 用线程内守卫挡住递归。
- 挂在 Logback `AsyncAppender` 上的 Filter 在调用线程、入队之前。队列快满时丢掉级别 ≤ INFO 的事件。`preprocess` 在入队前格式化句子并拷贝 MDC。被包裹文件上的 Filter 在 worker 的第二次 `doAppend` 里执行，这时要读事件里的 MDC 快照。
- Logback 滚动不丢当前事件。空的编码结果和 IOException 会让这一次没有字节；IOException 还会把 Appender 标成未启动。
- Log4j2：`isEnabled` 里先走 Configuration 级 Filter。非 NEUTRAL 时只有 ACCEPT 继续，DENY 直接结束；NEUTRAL 才比较 `intLevel >= 本次.intLevel`。通过之后才 `newMessage` 和创建事件。省略 `onMismatch` 时默认 DENY。
- LoggerConfig 只有 DENY 才丢，并且父节点不再收到。AppenderRef 的 `level` 按“ref 的 int 小于事件的 int 就跳过”比较。Appender 自己的 Filter 同样只认 DENY。
- `<Async>`：Logger 过滤器和 Async 自己的过滤器都在调用线程、入队之前；`createMemento` 时句子和 ThreadContext 已经冻结；文件上的过滤器在 dispatcher 出队之后。默认队列 1024，满了阻塞。`Discard` 在队列满时丢掉不比阈值更具体的级别，默认阈值 INFO。
- `AsyncLogger`：全局 Filter 和级别门仍在调用线程。Logger 过滤器和 Appender 过滤器在消费线程，DENY 发生时环槽已经被占过。上下文拷贝和消息冻结发生在调用线程的 `translateTo` 里。

---

# 三、符合 Marker：子 Marker 与父 Marker 的规则和效果

## 3.1 "符合"在两个引擎里各指什么

过滤器并不比较业务句子，它只问事件上的那个 Marker 对象：**配置里的名字，能不能从这一个对象上找出来。**

| 引擎 | 判定函数 | 查找方向 | 源码 |
|---|---|---|---|
| Logback `MarkerFilter` / `OnMarkerEvaluator` | `事件Marker.contains(配置名)` | 自己，以及 `add()` 放进引用列表的 Marker（递归，文档里常叫"子"） | `MarkerFilter.java` 第 50 行；`OnMarkerEvaluator.java` 第 50-54 行；`BasicMarker.contains(String)` 第 142-158 行 |
| Log4j2 `MarkerFilter` | `事件Marker.isInstanceOf(配置名)` | 自己，以及 `setParents` / `addParents` 挂上的祖先（递归，父、父的父） | `MarkerFilter.java` 第 32-33 行 javadoc、第 66-71 行；`MarkerManager.Log4jMarker.isInstanceOf(String)` 第 274-302 行 |

SLF4J 对 `contains` 的定义写在接口注释里（`org/slf4j/Marker.java` 第 114-116 行）：A 包含 B，当 A 与 B 相等，或 A 引用了 B，或 A 的引用又引用了 B。相等按名字（`BasicMarker.equals`，第 165-175 行）。

Log4j2 对 `isInstanceOf` 的定义写在接口注释里（`org/apache/logging/log4j/Marker.java` 第 79-95 行）：自己或任一祖先是目标 Marker / 目标名字，即为真。类注释里的例子是名为 Error 的 Marker，下面再分 SystemError、ApplicationError——**子实例能被父名字认出来**。

两边的"父子"英文单词指的不是同一次遍历。下面用同一组名字 `AUDIT`（想当大类）和 `PAYMENT`（想当小类）把效果写死。

## 3.2 Logback：`contains` 只认"自己和自己引用出去的名字"

`BasicMarker.add`（第 60-76 行）把参数放进 `referenceList`。已包含则直接返回；若对方已经包含自己，也直接返回，避免环。SLF4J 2.0 把 `add` / `contains` 标成了 `@Deprecated`（`Marker.java` 第 75-76 行），注释建议改用一次调用上的多个 Marker；**Logback 1.5.38 的过滤器仍调用 `contains`**，废弃不影响这条规则的运行结果。

建立关系的代码：

```java
Marker AUDIT = MarkerFactory.getMarker("AUDIT");
Marker PAYMENT = MarkerFactory.getMarker("PAYMENT");
AUDIT.add(PAYMENT);   // AUDIT 的引用列表 = [PAYMENT]
                      // 此后 AUDIT.contains("PAYMENT") == true
                      //      PAYMENT.contains("AUDIT") == false
```

`OnMarkerEvaluator.evaluate`（第 43-58 行）对事件上的每个 Marker 做 `contains(配置字符串)`，有一个为真即命中。传统 `log.info(marker, msg)` 只会 `addMarker` 一次（`Logger.java` 第 430 行）。

把 3.2 的树放进 2.1.1 的 `AUDIT_FILE`（过滤器名字写 `AUDIT`，`OnMatch=ACCEPT`，`OnMismatch=DENY`）：

| 打出去的 Marker | `contains("AUDIT")` | `contains("PAYMENT")` | 过滤器配 `AUDIT` 时进 `audit.log`？ | 若把过滤器改配 `PAYMENT` |
|---|---|---|---|---|
| 不带 Marker | 假 | 假 | 否 | 否 |
| `AUDIT` | 真（名字相同） | 真（引用列表里有 PAYMENT） | **进** | **也进** |
| `PAYMENT` | 假（子不回头看父） | 真（名字相同） | **不进** | 进 |
| 别的名字，比如 `PERF` | 假 | 假 | 否 | 否 |

效果用一句话写完：

- 过滤器写的是**父名字 `AUDIT`**：只有打出 `AUDIT`（或任何一个把 `AUDIT` 收进自己引用列表的 Marker）才进审计文件。只打 `PAYMENT` 进不去。
- 过滤器写的是**子名字 `PAYMENT`**：打出 `PAYMENT` 会进；打出 `AUDIT` 也会进，因为父的引用列表里有这个名字。普通日志不进。

这和"打子类标签、过滤器只配父类名就能捞到"的直觉相反。`AUDIT.add(PAYMENT)` 之后，被扩大命中面的是**打出父 Marker 的那条日志**，它会同时符合父名和子名。

### 3.2.1 让"打出 PAYMENT 也符合 AUDIT"的挂法

判定从事件上的那个对象出发。要让 `log.info(PAYMENT, ...)` 符合配置名 `AUDIT`，就得让 `PAYMENT.contains("AUDIT")` 为真，也就是让 `PAYMENT` 的引用列表里出现 `AUDIT`：

```java
Marker AUDIT = MarkerFactory.getMarker("AUDIT");
Marker PAYMENT = MarkerFactory.getMarker("PAYMENT");
PAYMENT.add(AUDIT);    // 打出去的是 PAYMENT，它引用 AUDIT
```

| 打出去的 Marker | 过滤器配 `AUDIT` | 过滤器配 `PAYMENT` |
|---|---|---|
| `PAYMENT` | 进（引用里有 AUDIT） | 进（名字相同） |
| `AUDIT` | 进（名字相同） | **不进**（AUDIT 没有引用 PAYMENT） |

不能两个方向都 `add`。`add` 发现对方已经包含自己就放弃（`BasicMarker.java` 第 70-72 行）。先 `PAYMENT.add(AUDIT)`，再 `AUDIT.add(PAYMENT)` 时第二次被丢掉，树上只保留第一方向。

孙子也算。`PAYMENT.add(CARD)` 且 `CARD.add(AUDIT)` 时，`PAYMENT.contains("AUDIT")` 会递归进 `CARD`（第 151-156 行），打出 `PAYMENT` 仍然符合 `AUDIT`。

## 3.3 Log4j2 原生：`isInstanceOf` 只认"自己和自己的父"

`MarkerFilter.filter(Marker)`（第 70-71 行）：

```java
return marker != null && marker.isInstanceOf(name) ? onMatch : onMismatch;
```

类注释写的就是这条规则（第 32-33 行）：事件 Marker 与配置 Marker 相同，或者配置 Marker 是它的父，才走 `onMatch`。

`isInstanceOf(String)`（`MarkerManager.java` 第 274-302 行）：

1. 自己的名字等于配置名 → 真。
2. 否则到 `MarkerManager` 的表里按名字取 Marker。表里没有这个名字 → 假。过滤器写了一个从未经 `MarkerManager.getMarker` 注册的名字，除了"事件自己就叫这个名字"以外都不会命中。
3. 沿着 `parents` 递归，用**实例相同**（`parent == marker`，`checkParent` 第 306-318 行）判断祖先。`getMarker` 对同一个名字返回同一实例，所以 `setParents(MarkerManager.getMarker("AUDIT"))` 与过滤器里的 `"AUDIT"` 对得上。

建立"PAYMENT 是 AUDIT 的一种"的原生写法：

```java
Marker AUDIT = MarkerManager.getMarker("AUDIT");
Marker PAYMENT = MarkerManager.getMarker("PAYMENT").setParents(AUDIT);
```

此时 `PAYMENT.isInstanceOf("AUDIT")` 为真，`AUDIT.isInstanceOf("PAYMENT")` 为假。文件上的 `MarkerFilter` 若是 `marker="AUDIT"` 且 `onMismatch="DENY"`：

| 打出去的 Marker | `isInstanceOf("AUDIT")` | `isInstanceOf("PAYMENT")` | 过滤器配 `AUDIT` | 过滤器配 `PAYMENT` |
|---|---|---|---|---|
| `null` | 假 | 假 | 不进，走 onMismatch | 不进 |
| `AUDIT` | 真 | 假 | **进** | **不进** |
| `PAYMENT`（父是 AUDIT） | 真（父链） | 真（名字） | **进** | 进 |
| `PERF`，与这棵树无关 | 假 | 假 | 不进 | 不进 |

效果：

- 过滤器写**父名字 `AUDIT`**：打出 `AUDIT` 会进，打出任何一个把 `AUDIT` 放进父链的子 Marker、孙 Marker 也会进。这是原生 API 下"大类过滤器覆盖小类日志"的挂法。
- 过滤器写**子名字 `PAYMENT`**：只有 `PAYMENT` 自己以及把 `PAYMENT` 当作祖先的更小类会进。打出 `AUDIT` 不进。

`addParents` 拒绝成环：父 Marker 已经 `isInstanceOf(自己)` 时不再加入（`MarkerManager.java` 第 156 行、第 173 行）。

## 3.4 走 SLF4J API、底层却是 Log4j2 时，`add()` 被倒着存

业务代码若写的是 `MarkerFactory.getMarker` + `add()`，Log4j2 的绑定类是 `Log4jMarker`。它的 `add`（`log4j-slf4j2-impl/.../Log4jMarker.java` 第 49-54 行）并不把参数当子节点：

```java
public void add(final Marker marker) {
    final Marker m = factory.getMarker(marker.getName());
    this.marker.addParents(((Log4jMarker) m).getLog4jMarker());
}
```

`AUDIT.add(PAYMENT)` 在桥接里变成原生的 `AUDIT.addParents(PAYMENT)`：`PAYMENT` 成了 `AUDIT` 的**父**。`contains` 也不是去扫子节点，而是（第 58-67 行）：

```java
return this.marker.isInstanceOf(marker.getName());
```

`MarkerFilter` 拿到的是拆包后的原生 Marker（`Log4jLogger.info` 第 207-208 行调用 `markerFactory.getLog4jMarker`），判定用的是 `isInstanceOf`。两边合在一起，**SLF4J 的 `add()` 在 Log4j2 上的过滤效果与 Logback 的 `contains` 相同**：

| SLF4J 代码 | Logback 上谁符合 `PAYMENT` | Log4j2 桥接上谁符合 `PAYMENT` |
|---|---|---|
| `AUDIT.add(PAYMENT)` 然后 `log.info(AUDIT, ...)` | 符合（引用列表含 PAYMENT） | 符合（原生父链上有 PAYMENT，`isInstanceOf("PAYMENT")`） |
| 同上，然后 `log.info(PAYMENT, ...)` | 不符合 `AUDIT` | 不符合 `AUDIT`（PAYMENT 没有父） |

从 SLF4J 迁到 `log4j-slf4j2-impl` 时，继续用 `add()` 的话，第三章 3.2 的真值表不用改。若改成原生 `setParents`，父子方向按 3.3 的表，和 `add()` 相反。

`Log4jMarkerFactory.convertMarker`（第 103-118 行）处理"已经是 BasicMarker、上面有引用"的对象时，同样把每个引用 `addParents` 到自己身上，和 `Log4jMarker.add` 同一方向。

## 3.5 一次调用带多个 Marker 时，全局和局部看见的集合不一样

SLF4J 2.0 建议用多个 Marker 代替嵌套（`Marker.java` 第 36-39 行）。两条实现看见的集合不同。

Logback，Fluent API 每调一次 `addMarker` 就追加进列表（`DefaultLoggingEvent.addMarker`，slf4j-api 第 38-44 行）。事件进入 Logback 后，`Logger.log(LoggingEvent)` 把列表全部 `addMarker` 到 `LoggingEvent`（第 839-842 行）。

- **局部** `OnMarkerEvaluator` 遍历 `event.getMarkerList()`，任何一个 `contains` 成功即命中（第 50-54 行）。
- **全局** TurboFilter 的默认实现只抽出**列表里的第一个**再交给 `decide(Marker, ...)`（`TurboFilter.java` 第 92-101 行）。`MarkerFilter` 没有覆盖这个重载，因此只认第一个。

```java
log.atInfo().addMarker(PAYMENT).addMarker(AUDIT).log("paid {}", orderId);
// 列表顺序：[PAYMENT, AUDIT]
```

在 3.2 那种 `AUDIT.add(PAYMENT)`、`PAYMENT` 自己不引用 `AUDIT` 的树上：

| 挂载点 | 看见谁 | 过滤器配 `AUDIT` 的结果 |
|---|---|---|
| `<turboFilter>` 里的 `MarkerFilter` | 只有 `PAYMENT` | 不命中，返回 `OnMismatch` |
| Appender 里的 `OnMarkerEvaluator` | `PAYMENT` 和 `AUDIT` | `AUDIT.contains("AUDIT")` 为真，命中 |

局部过滤器能分流成功、全局 TurboFilter 却像没看见 `AUDIT`，原因就是只传了第一个。需要全局也认多个名字时，覆盖 `decide(Logger, LoggingEvent)`，自行遍历 `slf4jEvent.getMarkers()`；或者保证要匹配的那个 Marker 第一个加入。

Log4j2 的事件只有一个 `Marker` 字段。`Log4jEventBuilder.addMarker`（第 61-62 行）是赋值而不是追加，多次调用只留下**最后一次**。父链是"一个事件带出多个名字"的原生办法：留下 `PAYMENT`，并且 `PAYMENT.setParents(AUDIT)`，全局和局部的 `MarkerFilter` 通过 `isInstanceOf("AUDIT")` 都能认到父名字。

## 3.6 本章小结

- 符合 = 从**事件上的那个 Marker** 出发能找到配置名字。Logback 向下找引用（`contains`），Log4j2 原生向上找父（`isInstanceOf`）。
- `AUDIT.add(PAYMENT)`（Logback / SLF4J）：打出 `AUDIT` 同时符合 `AUDIT` 和 `PAYMENT`；只打出 `PAYMENT` 不符合 `AUDIT`。
- `PAYMENT.setParents(AUDIT)`（Log4j2 原生）：打出 `PAYMENT` 同时符合 `PAYMENT` 和 `AUDIT`；只打出 `AUDIT` 不符合 `PAYMENT`。
- SLF4J `add()` 被 Log4j2 桥接实现成 `addParents`，因此继续写 `add()` 时，过滤真值表与 Logback 一致。
- 多个 Marker：Logback 局部评估器全看，全局 TurboFilter 默认只看第一个；Log4j2 Fluent 的 `addMarker` 只保留最后一个，多个名字靠父链带出去。

---

# 四、Marker、MDC、Level 怎样控制全局 Filter 和局部 Filter

## 4.1 三个输入能挂到哪里

| 输入 | 它自己做什么 | Logback 全局（事件前） | Logback 局部（Appender） | Log4j2 全局（`<Configuration>` 下） | Log4j2 局部 |
|---|---|---|---|---|---|
| Level | Logger 上的 `level` 是关 B，不是 Filter | 没有内置的"纯级别 TurboFilter"。`DynamicThresholdFilter` 用 MDC 选出一个级别阈值 | `ThresholdFilter`（不低于就 NEUTRAL，低于就 DENY）、`LevelFilter`（级别相等才算匹配） | `ThresholdFilter`、`LevelMatchFilter`、`LevelRangeFilter` 都可以直接放在 Configuration 下，走关 A | 同一批类放在 Logger 上就是关 C，放在 Appender / AppenderRef 上就是关 E；另外 `<AppenderRef level="ERROR">` 是关 E 的整数比较，不经过 Filter |
| MDC | 调用线程上的键值 | `MDCFilter` 读 `MDC.get`；`DynamicThresholdFilter` 也读 `MDC.get` | 没有内置 MDC Filter。自定义 Filter 在同步 Appender 上可以 `MDC.get`，在异步包裹之内要读 `event.getMDCPropertyMap()` | `ThreadContextMapFilter`、`DynamicThresholdFilter` 的"事件前"重载读 `ContextDataInjector`（实时 ThreadContext） | 同一对象的 `filter(LogEvent)` 读 `event.getContextData()`，异步消费线程上仍然是打日志那一刻的快照 |
| Marker | 贴在这一次调用上 | `MarkerFilter`（TurboFilter） | `EvaluatorFilter` + `OnMarkerEvaluator`。没有与 Turbo 同名的 Appender 版 MarkerFilter 类，评估器就是局部实现 | `MarkerFilter`，`filter(logger, level, marker, msg)` 在事件前就能看 Marker | 同一个 `MarkerFilter` 放在 Logger 或 Appender 上，改走 `filter(LogEvent)`，读 `event.getMarker()` |

Log4j2 的一个 Filter 类实现了两套入口：不带 `LogEvent` 的那套给关 A，带 `LogEvent` 的那套给关 C 和关 E。`MarkerFilter` 两套都只看 Marker，忽略级别和消息（第 48-71 行）。所以**同一个类挪一下挂载点，效力就从"整次调用生或死"变成"一个文件写不写"或"这一支 Logger 连父节点一起丢"**。

## 4.2 Level

### 4.2.1 Logger 的 level：全局资格，但不是 Filter

它永远在关 B。全局 Filter 返回 `NEUTRAL` 时才轮到它；返回 `ACCEPT` 时它被跳过；返回 `DENY` 时调用已经结束。

Logback：`root level="INFO"` 时，`effectiveLevelInt` 是 20000。DEBUG 事件 10000，`20000 > 10000`，拒绝。ERROR 事件 40000，不拒绝。子 Logger 写 `level="DEBUG"` 只放宽这一支，靠 `setLevel` 向下推送（见 `Self4j.md` 3.3），不改变别的包。

Log4j2：`level="info"` 的 int 是 400。DEBUG 事件 int 500，`400 >= 500` 为假，拒绝。ERROR 事件 int 200，`400 >= 200` 为真，放行。

### 4.2.2 局部：把已经诞生的事件挡在某一个文件外

Logback `ThresholdFilter` 挂在 Appender 上（第 39-48 行）。INFO 及以上返回 NEUTRAL（交给后续 Filter 或直接写），INFO 以下 DENY。它适合"root 已经是 DEBUG，这个文件仍只要 INFO"。

Logback `LevelFilter`（第 36-46 行）是**相等**才算匹配：

```xml
<filter class="ch.qos.logback.classic.filter.LevelFilter">
  <level>ERROR</level>
  <onMatch>ACCEPT</onMatch>
  <onMismatch>DENY</onMismatch>
</filter>
```

效果：这个文件只留 ERROR。WARN 也留不住，因为不相等。`onMatch=ACCEPT` 会短路本 Appender 上排在它后面的 Filter。

Log4j2 三个级别 Filter 都能挂全局或局部，比较方法不同：

| 类 | 命中条件 | 省略 onMatch / onMismatch 时 |
|---|---|---|
| `ThresholdFilter` | `事件级别.isMoreSpecificThan(配置级别)`，即事件更严重或相等（第 76-77 行）。默认配置级别是 ERROR（`createFilter` 第 243 行） | 命中 NEUTRAL，不命中 DENY。全局挂一份 `<ThresholdFilter level="INFO"/>` 会在关 A 把 DEBUG/TRACE 直接 DENY，INFO 及以上再去碰关 B |
| `LevelMatchFilter` | 级别对象相同（第 70-71 行） | 同上，不匹配即 DENY |
| `LevelRangeFilter` | `level.isInRange(minLevel, maxLevel)`（第 99-100 行），即 `min.intLevel <= 事件.intLevel <= max.intLevel`（`Level.java` 第 178-179 行） | 代码默认 min=OFF、max=ALL、onMatch=NEUTRAL、onMismatch=DENY（第 47-62 行） |

`LevelRangeFilter` 的 min/max 按 **int 的大小**，不是按"严重程度的高低"。ERROR 的 int 是 200，INFO 是 400，TRACE 是 600。要留下 ERROR、WARN、INFO：

```xml
<LevelRangeFilter minLevel="ERROR" maxLevel="INFO" onMatch="NEUTRAL" onMismatch="DENY"/>
```

`minLevel="TRACE" maxLevel="ERROR"` 要求 `int >= 600 且 int <= 200`，没有任何级别能同时满足，过滤器会拒绝全部事件。

`<AppenderRef level="ERROR">` 不走上述 Filter，判定是 `ref.intLevel < event.intLevel` 则跳过（`AppenderControl.java` 第 102-104 行）。`level="ERROR"` 留下 FATAL/ERROR，丢掉 WARN 及更啰嗦的级别。它和 Appender 内部的 `ThresholdFilter` 串联：两道都要过。

### 4.2.3 用级别做全局开关，又用局部级别拆文件

目标：调用线程上大多数日志保持 INFO；`logs/error.log` 只要 ERROR；`logs/app.log` 要 INFO 及以上且不要把 ERROR 再写一份。

Logback（级别 Filter 只能局部；全局资格用 root level）：

```xml
<root level="INFO">
  <appender-ref ref="APP"/>    <!-- ThresholdFilter INFO，再加 LevelFilter ERROR OnMatch=DENY -->
  <appender-ref ref="ERROR_FILE"/> <!-- LevelFilter ERROR OnMatch=ACCEPT OnMismatch=DENY -->
</root>
```

`error("boom")`：关 B 通过；APP 的 LevelFilter 匹配后 DENY；ERROR_FILE 匹配后 ACCEPT。文件里只有 `error.log`。

`info("ok")`：APP 的 LevelFilter 不匹配，若 OnMismatch 是 NEUTRAL 则写入；ERROR_FILE 不匹配则 DENY。

Log4j2 可以把第一道收到关 A，让 DEBUG 在构造事件前就死：

```xml
<ThresholdFilter level="INFO" onMatch="NEUTRAL" onMismatch="DENY"/>
<Loggers>
  <Root level="all">
    <AppenderRef ref="APP"/>
    <AppenderRef ref="ERROR_FILE" level="ERROR"/>
  </Root>
</Loggers>
```

关 A 已经丢掉 DEBUG。root 设成 `all` 是为了把关 B 让开，资格完全交给关 A——若 root 仍是 INFO，关 A 的 NEUTRAL 之后关 B 会再挡一次，结果相同，但"谁在做决定"会叠成两层。`ERROR_FILE` 的 AppenderRef level 再把 INFO 挡在这个文件之外。

全局 `ThresholdFilter` 若改成 `onMatch="ACCEPT"`，关 B 被跳过，root 即便是 ERROR，INFO 事件也会诞生，然后由各 Appender 自己决定写不写。这就是 Level 控制全局 Filter 的实际杠杆：**onMatch 用 NEUTRAL 时，Logger 级别仍然是最终资格；onMatch 用 ACCEPT 时，Logger 级别让位。**

## 4.3 MDC

MDC 不在事件诞生前自动附在调用上，业务线程要先放进去：

```java
MDC.put("reqType", healthCheck ? "health" : "biz");
MDC.put("traceId", traceId);
try {
    chain.doFilter(req, res);
} finally {
    MDC.remove("reqType");
    MDC.remove("traceId");
}
```

Log4j2 原生 API 写 `ThreadContext.put("reqType", ...)`。走 SLF4J 的 `MDC.put` 时，Log4j2 绑定会放进 ThreadContext，过滤器不用改。

### 4.3.1 全局：调用线程上的实时 map，DENY 则整次调用消失

Logback `MDCFilter.decide`（第 64-76 行）不看事件，直接 `MDC.get(MDCKey)`，与 `value` 做 `equals`。命中返回 `onMatch`，否则 `onMismatch`。两个默认都是 NEUTRAL（继承 `MatchingFilter`）。下面这段才会真正静音健康检查：

```xml
<turboFilter class="ch.qos.logback.classic.turbo.MDCFilter">
  <MDCKey>reqType</MDCKey>
  <Value>health</Value>          <!-- 对应 setValue，不是 MDCValue -->
  <OnMatch>DENY</OnMatch>
  <OnMismatch>NEUTRAL</OnMismatch>
</turboFilter>
```

`reqType=health` 的线程上，任何级别、任何 Marker 的日志都在关 A 结束，`audit.log` 也不会出现。`OnMismatch=NEUTRAL` 让其他请求继续走关 B。

Log4j2 同一意图，Filter 写在 `<Configuration>` 下即关 A。事件前的重载走 `filter()`（`ThreadContextMapFilter.java` 第 92-94 行、第 109-123 行），`getContextValue` 用 `ContextDataInjector.getValue(key)` 读实时 ThreadContext，不读 LogEvent：

```xml
<ThreadContextMapFilter onMatch="DENY" onMismatch="NEUTRAL">
  <KeyValuePair key="reqType" value="health"/>
</ThreadContextMapFilter>
```

`onMismatch` 必须写成 NEUTRAL。用默认值 DENY 的话，所有不是 health 的请求也会被全局丢掉。

多个 `KeyValuePair` 时，`operator="or"` 任一对上即命中，`operator="and"` 必须全对上（第 116-118 行的短路）。单键单值时走 `value.equals(getContextValue(key))`（第 121 行）。

### 4.3.2 局部：只影响一个文件；异步时读事件里的快照

Logback 标准库没有 Appender 版 MDCFilter。同步 Appender 上可以写一个 `Filter<ILoggingEvent>`，在 `decide` 里调用 `event.getMDCPropertyMap().get("reqType")`。这个调用会触发懒拷贝（第 453-467 行），拷的是**当前线程**的 MDC。同步 `doAppend` 仍在业务线程上，结果与 `MDC.get` 一致。

同一个 Filter 若挂在 AsyncAppender **里面**那个 RollingFile 上，执行线程是 worker，`MDC.get` 为空；map 必须在入队前的 `prepareForDeferredProcessing` 里已经冻结。自定义 Filter 只读 `event.getMDCPropertyMap()`，不要读 `MDC.get`。

Log4j2 把同一个 `ThreadContextMapFilter` 挪进 Appender 即局部。`filter(LogEvent)`（第 131-132 行）改为 `super.filter(event.getContextData())`。全异步时消费线程的 ThreadContext 是空的也不影响，因为 contextData 在创建事件时已经拷走。局部 DENY 只让这一个文件不写，关 A 没有 DENY 的话，兄弟 Appender 仍能收到事件。

### 4.3.3 MDC 选出"这个请求用哪档级别"：DynamicThresholdFilter

这是 MDC 和 Level 交汇的全局 Filter。两套实现对"键不存在"的处理不同。

Logback（`DynamicThresholdFilter.java` 第 132-136 行默认 `onHigherOrEqual=NEUTRAL`、`onLower=DENY`；`decide` 第 238-258 行）：

```xml
<turboFilter class="ch.qos.logback.classic.turbo.DynamicThresholdFilter">
  <Key>traceLevel</Key>
  <DefaultThreshold>INFO</DefaultThreshold>
  <MDCValueLevelPair user="debug" level="DEBUG"/>
  <MDCValueLevelPair user="trace" level="TRACE"/>
  <OnHigherOrEqual>NEUTRAL</OnHigherOrEqual>
  <OnLower>DENY</OnLower>
</turboFilter>
<root level="TRACE"/>
```

`user` 属性是 MDC 的**值**，不是键。键是 `<Key>`。

| 调用线程的 `traceLevel` | 采用的阈值 | `log.debug` | `log.info` |
|---|---|---|---|
| 没放这个键，或值不在表里 | `DefaultThreshold` INFO | 比 INFO 低，`onLower=DENY`，事件不诞生 | `isGreaterOrEqual(INFO)`，NEUTRAL，再由 root=TRACE 放行 |
| `debug` | DEBUG | NEUTRAL，然后级别门放行 | 放行 |
| `trace` | TRACE | 放行 | 放行 |

root 必须开到你想放出来的最低级别。`onHigherOrEqual` 默认 NEUTRAL，之后关 B 仍会执行。root 保持 INFO 时，即便 MDC 把阈值降到 TRACE，DEBUG 仍死在关 B。要把"MDC 说了算"做成唯一资格，把 `OnHigherOrEqual` 设为 `ACCEPT`（跳过关 B），或把 root 开到 TRACE/ALL。

Log4j2（`DynamicThresholdFilter.java` 第 127-140 行）：

```xml
<DynamicThresholdFilter key="traceLevel" defaultThreshold="INFO"
                        onMatch="NEUTRAL" onMismatch="DENY">
  <KeyValuePair key="debug" value="DEBUG"/>
  <KeyValuePair key="trace" value="TRACE"/>
</DynamicThresholdFilter>
```

`key` 属性是 ThreadContext 的键。`KeyValuePair` 的 `key` 是这个键的**取值**，`value` 是级别名（`createFilter` 第 72-73 行把 pair 的 key 放进 map，value 解析成 Level）。

| ThreadContext 里 `traceLevel` | 行为 |
|---|---|
| 键不存在（`injector.getValue` 为 null） | 直接 `NEUTRAL`（第 135 行），**不用** defaultThreshold，资格交还给关 B |
| 值是 `debug` / `trace` | 事件级别比表里的级别更具体或相等 → onMatch，否则 onMismatch |
| 键存在但值不在表里（例如 `other`） | 用 `defaultThreshold`（默认 ERROR，第 75 行）再比较 |

和 Logback 的差别就在第一行：Logback 在键缺失时落到 `DefaultThreshold` 并可能 DENY；Log4j2 在键缺失时当作"这关不关我的事"。排障开关可以用"只有打了标的请求才改变阈值，其他请求只受 Logger level 约束"。

`onMatch=ACCEPT` 时，对上阈值的事件跳过关 B。`onMatch=NEUTRAL` 时，Logger level 仍然能再收紧一档。

## 4.4 Marker

Marker 控制的是"这一次调用"，不是"这个 Logger 的全部调用"。同一个类里，带 Marker 和不带 Marker 的两行可以走不同的文件。

### 4.4.1 全局：在级别门之前决定整次调用

Logback：

```xml
<turboFilter class="ch.qos.logback.classic.turbo.MarkerFilter">
  <Marker>AUDIT</Marker>
  <OnMatch>ACCEPT</OnMatch>
  <OnMismatch>NEUTRAL</OnMismatch>
</turboFilter>
```

`decide`（`MarkerFilter.java` 第 41-54 行）：未 start 返回 NEUTRAL；事件没带 Marker 返回 `onMismatch`；`marker.contains(markerToMatch)` 为真返回 `onMatch`。

- `OnMatch=ACCEPT`：符合的调用跳过关 B。root=INFO 时，`log.debug(符合AUDIT的Marker, ...)` 仍能诞生事件。不符合的调用 `NEUTRAL`，继续按 root 的级别。
- `OnMatch=DENY`：符合的调用整次消失，任何 Appender 都写不到。用来做"功能开关默认静音"，而不是用来分流文件。
- `OnMismatch=DENY`：不符合的调用整次消失。审计文件会只剩 AUDIT，但 `app.log` 也会空，因为全局 DENY 来不及分 Appender。

Log4j2 把同一个类放在 Configuration 下就是关 A，`onMatch="ACCEPT" onMismatch="NEUTRAL"` 与上面同效。判定换成 `isInstanceOf`，父子效果按第三章的表：原生 `setParents` 时子 Marker 能让父名字的全局过滤器 ACCEPT；SLF4J `add()` 时要按 3.2 的引用方向。

全局 MarkerFilter 发生在参数格式化之前，DENY 能省掉事件对象和字符串拼接。这是它和 Appender 上 Marker 过滤器的性能差别。

### 4.4.2 局部：事件已经在了，只选文件

Logback 用 `EvaluatorFilter` 包 `OnMarkerEvaluator`（`EvaluatorFilter.decide` 第 60-75 行：评估器为真返回 `onMatch`，为假返回 `onMismatch`，评估抛错返回 NEUTRAL）。2.1.1 的一对 Appender 就是局部控制：

- APP：匹配 AUDIT 则 DENY，不匹配则 NEUTRAL（继续写）。
- AUDIT_FILE：匹配则 ACCEPT，不匹配则 DENY。

两份文件不会双写，是因为两个局部 Filter 的匹配动作相反，不是因为全局 Filter。全局那道只负责"DEBUG 的审计事件能不能诞生"。

Log4j2 局部有两个力度：

```xml
<!-- 力度 1：只影响 PaymentDebug 这一个 Appender，root 的其他 Appender 仍能收到 -->
<RollingFile name="PaymentDebug" ...>
  <MarkerFilter marker="AUDIT" onMatch="DENY" onMismatch="NEUTRAL"/>
</RollingFile>

<!-- 力度 2：关 C。DENY 后父 LoggerConfig 也收不到，root 的审计文件会一起丢 -->
<Logger name="com.example.payment" level="debug">
  <MarkerFilter marker="AUDIT" onMatch="DENY" onMismatch="NEUTRAL"/>
  <AppenderRef ref="PaymentDebug"/>
</Logger>
```

分流审计应当用力度 1。力度 2 适合"这个包里凡是带 AUDIT 的调用整支丢掉"，不适合"丢掉这个包、但 root 上的审计文件还要留"。

### 4.4.3 全局 Marker 与局部 Marker 叠在同一次调用上

沿用第三章 `PAYMENT.add(AUDIT)` 的 Logback 树（打出 `PAYMENT` 符合 `AUDIT`），配置仍是 2.1.1：全局 `MarkerFilter` 对 `AUDIT` 为 `ACCEPT/NEUTRAL`，APP 拒绝 `AUDIT`，`AUDIT_FILE` 只收 `AUDIT`。

| 调用 | 全局 MarkerFilter | 结果 |
|---|---|---|
| `log.info("order")` | 无 Marker，NEUTRAL，关 B 按 INFO 放行 | 只在 `app.log` |
| `log.info(AUDIT, "login")` | 名字相同，ACCEPT | 只在 `audit.log` |
| `log.info(PAYMENT, "pay")` | `PAYMENT.contains("AUDIT")` 为真，ACCEPT | 只在 `audit.log` |
| `log.debug(PAYMENT, "pay detail")` | 同样 ACCEPT，DEBUG 也诞生 | APP 的 ThresholdFilter DENY；`audit.log` 有 |
| `log.info(PERF, "metric")`，PERF 不引用 AUDIT | NEUTRAL，关 B 放行 INFO | 只在 `app.log` |

把关系改成 `AUDIT.add(PAYMENT)` 之后，上表第三、四行翻转：`log.info(PAYMENT, "pay")` 全局不命中，当成普通 INFO，进 `app.log` 不进 `audit.log`；`log.info(AUDIT, "login")` 仍然两边名字都符合，只进 `audit.log`。过滤器 XML 一个字都不用改，变的是 Marker 树上 `add` 的方向。

Log4j2 原生要得到上表"PAYMENT 也进审计"的效果，用 `PAYMENT.setParents(AUDIT)`，过滤器继续写 `marker="AUDIT"`。用 SLF4J `add()` 时按 Logback 那一列，不要再 `setParents` 一次，否则同一对名字会被存两次、方向还可能相反。

## 4.5 三样一起用的完整配置

业务约定：

1. `reqType=health` 的线程，任何日志都不落盘（MDC → 全局 DENY）。
2. 其他线程默认 INFO（Level → 关 B）。
3. 线程把 `traceLevel` 放成 `debug` 时，这一请求的 DEBUG 也允许诞生（MDC+Level → 全局 DynamicThreshold，且用 ACCEPT 跳过关 B）。
4. 符合 `AUDIT` 的调用进 `audit.log`，不进 `app.log`（Marker → 局部）。DEBUG 的审计调用也要进 `audit.log`（Marker → 全局 ACCEPT，压过"默认 INFO"）。
5. `error.log` 只收 ERROR，不管有没有 Marker（Level → 局部）。

打点代码（SLF4J，Logback 与 `log4j-slf4j2-impl` 共用；Marker 关系按 3.2，`PAYMENT` 引用 `AUDIT`）：

```java
public final class LogTags {
    public static final Marker AUDIT = MarkerFactory.getMarker("AUDIT");
    public static final Marker PAYMENT = MarkerFactory.getMarker("PAYMENT");
    static {
        PAYMENT.add(AUDIT);
    }
}

MDC.put("reqType", "biz");
MDC.put("traceLevel", "debug");   // 排障请求才放；普通请求不要放这个键
try {
    log.info("order {}", id);
    log.debug("sql {}", sql);
    log.info(LogTags.PAYMENT, "pay {}", id);
    log.error("boom", ex);
} finally {
    MDC.remove("reqType");
    MDC.remove("traceLevel");
}
```

### 4.5.1 Logback

```xml
<configuration>
  <!-- ① MDC 全局：健康检查整次 DENY -->
  <turboFilter class="ch.qos.logback.classic.turbo.MDCFilter">
    <MDCKey>reqType</MDCKey>
    <Value>health</Value>
    <OnMatch>DENY</OnMatch>
    <OnMismatch>NEUTRAL</OnMismatch>
  </turboFilter>

  <!-- ② Marker 全局：符合 AUDIT 则跳过级别门 -->
  <turboFilter class="ch.qos.logback.classic.turbo.MarkerFilter">
    <Marker>AUDIT</Marker>
    <OnMatch>ACCEPT</OnMatch>
    <OnMismatch>NEUTRAL</OnMismatch>
  </turboFilter>

  <!-- ③ MDC+Level 全局：排障请求把阈值降到 DEBUG，并 ACCEPT 以跳过 root=INFO -->
  <turboFilter class="ch.qos.logback.classic.turbo.DynamicThresholdFilter">
    <Key>traceLevel</Key>
    <DefaultThreshold>INFO</DefaultThreshold>
    <MDCValueLevelPair user="debug" level="DEBUG"/>
    <OnHigherOrEqual>ACCEPT</OnHigherOrEqual>
    <OnLower>DENY</OnLower>
  </turboFilter>

  <appender name="APP" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>logs/app.log</file>
    <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
      <fileNamePattern>logs/app.%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
      <maxFileSize>100MB</maxFileSize>
      <maxHistory>14</maxHistory>
    </rollingPolicy>
    <encoder><pattern>%d %-5level [%X{traceId}] %msg%n</pattern></encoder>
    <filter class="ch.qos.logback.classic.filter.ThresholdFilter"><level>INFO</level></filter>
    <filter class="ch.qos.logback.classic.filter.LevelFilter">
      <level>ERROR</level>
      <onMatch>DENY</onMatch>
      <onMismatch>NEUTRAL</onMismatch>
    </filter>
    <filter class="ch.qos.logback.core.filter.EvaluatorFilter">
      <evaluator class="ch.qos.logback.classic.boolex.OnMarkerEvaluator">
        <marker>AUDIT</marker>
      </evaluator>
      <OnMatch>DENY</OnMatch>
      <OnMismatch>NEUTRAL</OnMismatch>
    </filter>
  </appender>

  <appender name="AUDIT_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>logs/audit.log</file>
    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
      <fileNamePattern>logs/audit.%d{yyyy-MM-dd}.log.gz</fileNamePattern>
      <maxHistory>365</maxHistory>
    </rollingPolicy>
    <encoder><pattern>%d %-5level %marker [%X{traceId}] %msg%n</pattern></encoder>
    <filter class="ch.qos.logback.core.filter.EvaluatorFilter">
      <evaluator class="ch.qos.logback.classic.boolex.OnMarkerEvaluator">
        <marker>AUDIT</marker>
      </evaluator>
      <OnMatch>ACCEPT</OnMatch>
      <OnMismatch>DENY</OnMismatch>
    </filter>
  </appender>

  <appender name="ERROR_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>logs/error.log</file>
    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
      <fileNamePattern>logs/error.%d{yyyy-MM-dd}.log.gz</fileNamePattern>
      <maxHistory>30</maxHistory>
    </rollingPolicy>
    <encoder><pattern>%d %-5level %msg %ex%n</pattern></encoder>
    <filter class="ch.qos.logback.classic.filter.LevelFilter">
      <level>ERROR</level>
      <onMatch>ACCEPT</onMatch>
      <onMismatch>DENY</onMismatch>
    </filter>
  </appender>

  <root level="INFO">
    <appender-ref ref="APP"/>
    <appender-ref ref="AUDIT_FILE"/>
    <appender-ref ref="ERROR_FILE"/>
  </root>
</configuration>
```

TurboFilter 按注册顺序执行，第一个 ACCEPT/DENY 生效。上面的顺序是有意的：健康检查最先 DENY；其后 Marker 的 ACCEPT 与 DynamicThreshold 的 ACCEPT/DENY 只会作用在还没被健康检查截住的调用上。一个 `reqType=health` 且带 `AUDIT` 的 `log.error`，死在第一道，进不了 `error.log`。

`traceLevel=debug` 的请求上，六次调用的落点：

| 调用 | 关 A 谁拍板 | app.log | audit.log | error.log |
|---|---|---|---|---|
| 健康检查线程里的任意日志 | MDCFilter DENY | 无 | 无 | 无 |
| `info("order")` | 前两道 NEUTRAL；DTF 对 INFO≥DEBUG 返回 ACCEPT | 有 | 无 | 无 |
| `debug("sql")` | DTF ACCEPT，跳过 root=INFO | 无（ThresholdFilter DENY） | 无 | 无 |
| `info(PAYMENT)` ，PAYMENT 引用了 AUDIT | MarkerFilter 先 ACCEPT，后面的 DTF 不再执行 | 无（OnMarker DENY） | 有 | 无 |
| `debug(PAYMENT)` | 同上，MarkerFilter ACCEPT | 无（Threshold 与 OnMarker 都会 DENY，先碰到的 Threshold 已经返回） | 有 | 无 |
| `error("boom")` | DTF 或 Marker 都是 NEUTRAL/未命中时，DTF 对 ERROR≥DEBUG ACCEPT | 无（LevelFilter DENY） | 无 | 有 |
| `error(PAYMENT)` | MarkerFilter 先 ACCEPT，DTF 不再执行 | 无。`LevelFilter` 对 ERROR 返回 DENY，后面的 OnMarker 不再执行 | 有 | 有 |

`error.log` 只看级别，所以带 `PAYMENT` 的 ERROR 也会进去。若审计类 ERROR 不要进 `error.log`，在 `ERROR_FILE` 上加一道 OnMarker（`AUDIT` → DENY），并且放在 `LevelFilter` 之前。`LevelFilter` 的 `onMatch=ACCEPT` 会结束这条 Filter 链，写在它后面的 DENY 不会执行。

普通请求不放 `traceLevel` 时，DTF 使用 DefaultThreshold INFO：`debug("sql")` 在关 A 被 `OnLower=DENY`，事件不诞生。这是键缺失时 Logback 的行为。

### 4.5.2 Log4j2

SLF4J 代码不变（`PAYMENT.add(AUDIT)` 经桥接变成 `PAYMENT` 的父链含 `AUDIT`，`marker="AUDIT"` 能命中 `log.info(PAYMENT, ...)`）。原生 API 则写成 `PAYMENT.setParents(AUDIT)`，不要再 `add`。

```xml
<Configuration>
  <!-- 关 A，顺序即 CompositeFilter 的顺序 -->
  <ThreadContextMapFilter onMatch="DENY" onMismatch="NEUTRAL">
    <KeyValuePair key="reqType" value="health"/>
  </ThreadContextMapFilter>
  <MarkerFilter marker="AUDIT" onMatch="ACCEPT" onMismatch="NEUTRAL"/>
  <DynamicThresholdFilter key="traceLevel" defaultThreshold="INFO"
                          onMatch="ACCEPT" onMismatch="DENY">
    <KeyValuePair key="debug" value="DEBUG"/>
  </DynamicThresholdFilter>

  <Appenders>
    <RollingFile name="APP" fileName="logs/app.log"
                 filePattern="logs/app.%d{yyyy-MM-dd}.%i.log.gz">
      <PatternLayout pattern="%d %-5level [%X{traceId}] %msg%n"/>
      <ThresholdFilter level="INFO" onMatch="NEUTRAL" onMismatch="DENY"/>
      <LevelMatchFilter level="ERROR" onMatch="DENY" onMismatch="NEUTRAL"/>
      <MarkerFilter marker="AUDIT" onMatch="DENY" onMismatch="NEUTRAL"/>
      <Policies>
        <TimeBasedTriggeringPolicy/>
        <SizeBasedTriggeringPolicy size="100 MB"/>
      </Policies>
    </RollingFile>

    <RollingFile name="AUDIT_FILE" fileName="logs/audit.log"
                 filePattern="logs/audit.%d{yyyy-MM-dd}.log.gz">
      <PatternLayout pattern="%d %-5level %marker [%X{traceId}] %msg%n"/>
      <MarkerFilter marker="AUDIT" onMatch="ACCEPT" onMismatch="DENY"/>
      <Policies><TimeBasedTriggeringPolicy/></Policies>
    </RollingFile>

    <RollingFile name="ERROR_FILE" fileName="logs/error.log"
                 filePattern="logs/error.%d{yyyy-MM-dd}.log.gz">
      <PatternLayout pattern="%d %-5level %msg %ex%n"/>
      <LevelMatchFilter level="ERROR" onMatch="ACCEPT" onMismatch="DENY"/>
      <Policies><TimeBasedTriggeringPolicy/></Policies>
    </RollingFile>
  </Appenders>

  <Loggers>
    <!-- 关 B 让开，资格交给关 A 的 DynamicThreshold / MarkerFilter -->
    <Root level="all">
      <AppenderRef ref="APP"/>
      <AppenderRef ref="AUDIT_FILE"/>
      <AppenderRef ref="ERROR_FILE"/>
    </Root>
  </Loggers>
</Configuration>
```

和 Logback 那份的行为差异只有一处，来自 4.3.3：普通请求**没有** `traceLevel` 这个键时，Log4j2 的 `DynamicThresholdFilter` 返回 NEUTRAL，不会 DENY。root 又是 `all`，于是 `debug("sql")` 会诞生，再被 APP 的 `ThresholdFilter` 丢掉，仍然不进文件，但事件对象已经创建。若希望"没打标就在关 A 按 INFO 丢掉"，把 default 路径做成"键缺失也套用 INFO"。做法是业务侧对所有请求都 `ThreadContext.put("traceLevel", "info")`，并增加 `<KeyValuePair key="info" value="INFO"/>`。键总是存在，过滤器就会用表里的级别，而不是走"值为 null 则 NEUTRAL"。

`error(PAYMENT)` 的落点与 Logback 表的最后一行相同：`AUDIT_FILE` 与 `ERROR_FILE` 都有，`APP` 没有。MarkerFilter 在 APP 上是局部 DENY，不会阻止 `ERROR_FILE`。

## 4.6 本章小结

- Level 的 Logger `level` 是关 B。全局 Filter 的 `NEUTRAL` 把决定权留给他，`ACCEPT` 夺走决定权，`DENY` 让他没机会执行。局部的 Threshold / LevelMatch / LevelRange / AppenderRef level 只决定单个文件。
- MDC 的全局 Filter 读调用线程的实时 map，DENY 之后没有任何文件。局部 Filter 在 Log4j2 读事件快照；Logback 要自己在 Filter 里读 `getMDCPropertyMap()`，异步包裹内不能读 `MDC.get`。
- `DynamicThresholdFilter` 用 MDC 的值选择级别阈值。Logback 在键缺失时用 DefaultThreshold；Log4j2 在键缺失时返回 NEUTRAL。`onHigherOrEqual` / `onMatch` 保持 NEUTRAL 时 Logger 级别还能再拦一次，设为 ACCEPT 时这道阈值就是最终资格。
- Marker 的全局 Filter 决定整次调用能不能越过级别门；局部 Filter 决定进哪个文件。Log4j2 若把 MarkerFilter 放在 Logger 上，DENY 还会切断父 Logger。符合与否的父子规则以第三章的真值表为准。

---

# 五、排障时如何逐步放开日志

线上缺一段细节时，要放开的是**已经知道的那一道关**，而且一次只加宽一个维度。root 改成 DEBUG 会把关 B 对所有 Logger、所有请求一起打开，Netty、连接池、Spring 的 DEBUG 和业务日志叠在同一个文件里，磁盘、采集端和异步队列会先满，真正要看的那几行反而被丢掉。

## 5.1 先确认卡住的是哪一关

把某个 Logger 调到 DEBUG，只改变关 B。前面的全局 Filter 若返回 DENY，或后面的 Appender `ThresholdFilter` 仍是 INFO，这行还是不会进文件。放开之前用下面这张表对一下，避免"级别已经改了，文件里还是没有"。

| 现象 | 实际卡住的关 | 该动什么 |
|---|---|---|
| 这个类的 INFO 有，DEBUG 没有，Appender 没有 Threshold | 关 B，Logger 有效级别高于 DEBUG | 只把这个 Logger 升到 DEBUG |
| Logger 已经是 DEBUG，主文件仍只有 INFO | 关 E，Appender 上的 `ThresholdFilter` / `AppenderRef level` | 给 DEBUG 单独一个文件，主文件阈值保持 INFO |
| 只有带某个 MDC 的请求没有日志 | 关 A，`MDCFilter` 或 `ThreadContextMapFilter` 在 DENY | 先拿掉这次排障请求上的静音标记，不要去降级别 |
| 健康检查没了，业务 DEBUG 也没了 | 关 A 的 DENY 范围过大 | 收窄匹配条件，让普通请求继续 NEUTRAL |
| 异步队列一高，DEBUG 时有时无 | 关 F，Logback 把 INFO 及以下当可丢 | 排障这次改走同步的短文件，或临时抬高丢弃阈值 |

级别只升一档：当前是 INFO，就到 DEBUG，不要直接到 TRACE。TRACE 才是 SQL 绑定参数、HTTP 正文这一档，量和敏感字段都比 DEBUG 高一个数量级。DEBUG 仍缺事实时，再把**一个类**升到 TRACE。

## 5.2 业界阶梯：从窄到宽

每次只加宽下面四个维度中的一个，做完能回答问题就停，并在约定时间把改动收回。

| 阶 | 加宽的维度 | 其余保持不变 | 适用 |
|---|---|---|---|
| 0 | 不放开 | 用已有 INFO/WARN 里的 `traceId`、订单号检索 | 每次都先做。多数故障在这一阶就结束 |
| 1 | 一次请求，且只到 DEBUG，且只限目标包 | 其他请求仍走原级别 | QPS 高、不能让整个类的 DEBUG 常开 |
| 2 | 一个类的 Logger，INFO→DEBUG | 所有请求，但不含子包以外的代码，不含 TRACE | 最常见的日常排障 |
| 3 | 从这一个类扩到它的包 | 仍是 DEBUG，仍不到 TRACE，仍是单实例 | 第 2 阶发现分支在同包的另一个类 |
| 4 | 这一个类再升到 TRACE | 包内其他类停在 DEBUG | 缺的是绑定参数、报文正文 |
| 5 | 单个实例上的 root | 只在摘流或限流之后，并且 DEBUG 进短保留文件 | 最后手段。整集群 root DEBUG 不在这个阶梯里 |

两个约定贯穿每一阶：

1. **单实例。** 先改一台（一台 Pod、一台金丝雀），用网关把复现请求打到这台，或只在这台打开 Actuator。集群一起放开等于把日志量乘以副本数。
2. **到点收回。** 放开前记下旧级别，15 到 30 分钟后设回去。人会忘记手动收回，定时任务或配置平台的过期时间负责这件事。

第 0 阶能成立，靠的是平时 INFO 里已经有稳定的关联键（`traceId`、订单号、用户号），而不是靠排障时临时把所有分支打成 DEBUG。DEBUG 放分支判断、下游返回码、耗时；TRACE 放报文和绑定参数，并先脱敏。占位符日志在级别没放开时不会拼接字符串，所以这些 DEBUG 可以留在代码里，不必等出事再加。

## 5.3 一个 Logger 升一级

这是第 2、3 阶的做法。子 Logger 没有自己的级别时跟着父级走，已经显式写过级别的子节点不动。

Logback：`setLevel(null)` 表示重新继承父级（`Logger.java` 第 160-162 行），并向下推给"自己没设级别"的孩子（`handleParentLevelChange` 第 185-189 行）。root 不能设成 null（第 155-156 行）。

```java
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.slf4j.LoggerFactory;

LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
Logger target = ctx.getLogger("com.example.payment.OrderRepository");
Level previous = target.getLevel();          // null 表示原来是继承
target.setLevel(Level.DEBUG);                // 只动这一支
// 复现结束后
target.setLevel(previous);                   // 把 null 设回去才会重新继承
```

`getLevel()`（第 119-121 行）返回的是这个 Logger 自己写上的级别，不是有效级别。收回时要保存的是它，不是 `getEffectiveLevel()`。把父包设成 DEBUG 时，子类里写了 `level="INFO"` 的节点不会被推低，排障时若"包已经 DEBUG、某个类仍没有"，先看那个类是不是被显式钉死。

Log4j2 用 `Configurator.setLevel`。名字还没有独立 `LoggerConfig` 时，它会新建一个再 `updateLoggers()`（`Configurator.java` 第 413-415 行、第 432-439 行）：

```java
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.config.Configurator;

org.apache.logging.log4j.core.Logger target =
        (org.apache.logging.log4j.core.Logger) LogManager.getLogger("com.example.payment.OrderRepository");
Level previous = target.getLevel();
Configurator.setLevel("com.example.payment.OrderRepository", Level.DEBUG);
// 复现结束后设回原来的有效级别
Configurator.setLevel("com.example.payment.OrderRepository", previous);
```

Spring Boot 应用里，这两段通常不写进业务代码，而是走 Actuator：`POST /actuator/loggers/com.example.payment.OrderRepository`，请求体 `{"configuredLevel":"DEBUG"}`。收回时提交 `{"configuredLevel":null}`，让它回到继承。这个端点必须带认证，并且不要暴露到公网——它等价于运行期调用上面的 `setLevel`。调用本身用一条 WARN 记下来：谁、哪个 Logger、从哪一级到哪一级、何时收回。

热更新文件（Logback `scan`、Log4j2 `monitorInterval`，机制见 `Self4j.md` 3.8 与 4.11）会整份重载配置，把内存里刚设的级别盖掉。排障窗口内只选一种入口：要么改文件并等扫描生效，要么调 Actuator / `setLevel`。两边同时用，会出现"刚放开又被下一次扫描收回"或"扫描把临时期限一起冲掉"。`logback-spring.xml` 的扩展标签不能和 `scan` 一起用，Boot 应用优先用 Actuator。

点名时用类或小包，不用框架根包。支付问题开 `com.example.payment`；要看 SQL 先开 `org.hibernate.SQL` 的 DEBUG，绑定参数仍不够再单开绑定参数那个 Logger 的 TRACE。`org.springframework`、`io.netty`、`com.zaxxer.hikari` 整包 DEBUG 属于第 5 阶才会考虑的范围，而且仍然只在一台机器上。

## 5.4 一次请求、一个包，只到 DEBUG

第 1 阶比第 2 阶更窄：其他用户的流量一个字节都不多。入口只认内网网关写上的标记，不认任意客户端自己带的 Header，否则任何人都能把 DEBUG 打满，也把内部数据打出来。

```java
// 网关或服务入口。debug 标记来自已认证的排障通道，不是公网 Header 直通
boolean troubleshoot = trustedGatewayFlag && packageAllowed;
if (troubleshoot) {
    MDC.put("traceLevel", "debug");
    MDC.put("debugLogger", "com.example.payment");   // 这次允许放开的包前缀
}
try {
    chain.doFilter(req, res);
} finally {
    MDC.remove("traceLevel");
    MDC.remove("debugLogger");
}
```

第四章的 `DynamicThresholdFilter` 单独使用会偏宽：`OnHigherOrEqual=ACCEPT` 时，这次请求上**所有** Logger 的 DEBUG 都跳过关 B，包括框架日志。它适合"整条请求都要看"的短窗口，不适合作为默认排障开关。更窄的做法是自定义 TurboFilter：只有包前缀对上、且级别不细于 DEBUG 时才 ACCEPT，其余返回 NEUTRAL，关 B 照旧。

```java
public class RequestDebugTurboFilter extends ch.qos.logback.classic.turbo.TurboFilter {
    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level,
                              String format, Object[] params, Throwable t) {
        if (!"debug".equals(MDC.get("traceLevel"))) {
            return FilterReply.NEUTRAL;
        }
        String prefix = MDC.get("debugLogger");
        if (prefix == null || !logger.getName().startsWith(prefix)) {
            return FilterReply.NEUTRAL;                 // 其他包仍看自己的级别
        }
        if (level.isGreaterOrEqual(Level.DEBUG)) {     // DEBUG 及以上；TRACE 不在此放行
            return FilterReply.ACCEPT;
        }
        return FilterReply.NEUTRAL;                     // TRACE 继续交给关 B，默认仍被 INFO 拦住
    }
}
```

```xml
<turboFilter class="com.example.logging.RequestDebugTurboFilter"/>
<root level="INFO"/>
```

效果：

| 请求 | 日志 | 结果 |
|---|---|---|
| 普通请求 | `com.example.payment` 的 DEBUG | 关 A 返回 NEUTRAL，关 B 按 INFO 拒绝 |
| 普通请求 | 任意 INFO | 照常写入 |
| 排障请求，`debugLogger=com.example.payment` | 该包的 DEBUG | ACCEPT，跳过关 B，进入 DEBUG 文件 |
| 同一排障请求 | `org.hibernate.SQL` 的 DEBUG | 前缀不符，NEUTRAL，仍被 INFO 拒绝 |
| 同一排障请求 | 该包的 TRACE | 不 ACCEPT，关 B 拒绝 |

TRACE 要单独再开一阶：把 `isGreaterOrEqual(Level.DEBUG)` 改成对白名单类返回 ACCEPT，而不是把整个前缀放到 TRACE。

Log4j2 用同一个类的思路做全局 Filter。事件前的 `filter(Logger, Level, Marker, ...)` 读 `ThreadContext`，返回 `ACCEPT` 才跳过关 B；包名用 `logger.getName()`。键不存在时返回 `NEUTRAL`，这样没打标的请求完全走原来的级别门（这和 4.3.3 里 Log4j2 `DynamicThresholdFilter` 的"键缺失即 NEUTRAL"一致，比 Logback 那个默认阈值更适合当排障开关）。

```java
@Plugin(name = "RequestDebugFilter", category = Node.CATEGORY, elementType = Filter.ELEMENT_TYPE)
public class RequestDebugFilter extends AbstractFilter {
    @Override
    public Result filter(org.apache.logging.log4j.core.Logger logger, org.apache.logging.log4j.Level level,
                         org.apache.logging.log4j.Marker marker, String msg, Object... params) {
        if (!"debug".equals(ThreadContext.get("traceLevel"))) {
            return Result.NEUTRAL;
        }
        String prefix = ThreadContext.get("debugLogger");
        if (prefix == null || logger == null || !logger.getName().startsWith(prefix)) {
            return Result.NEUTRAL;
        }
        // DEBUG 的 int 是 500，TRACE 是 600。isMoreSpecificThan 为 this.intLevel <= 参数.intLevel。
        // 相对 DEBUG：ERROR/INFO/DEBUG 为真并 ACCEPT；TRACE（600 <= 500 为假）得到 NEUTRAL，仍由关 B 拦住。
        return level.isMoreSpecificThan(org.apache.logging.log4j.Level.DEBUG) ? Result.ACCEPT : Result.NEUTRAL;
    }
}
```

这个 Filter 放在 `<Configuration>` 下，作为关 A。Appender 上再按 5.5 把 DEBUG 送进短文件。`isMoreSpecificThan` 包含相等（`Level.java` 第 212-213 行），所以比较对象用 `DEBUG` 才能把 TRACE 留在关 B 外面。写成 `isMoreSpecificThan(TRACE)` 会把 TRACE 自己也 ACCEPT 掉。

## 5.5 DEBUG 进短保留文件，主文件维持原样

放开之后的字节不要写进保留 14 天或 180 天的 `app.log`。主文件继续只收 INFO 及以上，DEBUG 进一个保留 1 天、单文件很小的 `debug.log`。排障结束即使忘记改回级别，多出来的数据也会很快滚掉；主文件的采集成本和检索噪声不变。

Logback 在第 2 阶（Logger 已是 DEBUG）时：

```xml
<appender name="APP" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>logs/app.log</file>
    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
        <fileNamePattern>logs/app.%d{yyyy-MM-dd}.log.gz</fileNamePattern>
        <maxHistory>14</maxHistory>
    </rollingPolicy>
    <encoder><pattern>%d %-5level [%X{traceId}] %logger %msg%n</pattern></encoder>
    <filter class="ch.qos.logback.classic.filter.ThresholdFilter">
        <level>INFO</level>
    </filter>
</appender>

<appender name="DEBUG_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
    <file>logs/debug.log</file>
    <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
        <fileNamePattern>logs/debug.%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
        <maxFileSize>50MB</maxFileSize>
        <maxHistory>1</maxHistory>
        <totalSizeCap>1GB</totalSizeCap>
    </rollingPolicy>
    <encoder><pattern>%d %-5level [%X{traceId}] %logger %msg%n</pattern></encoder>
    <!-- 不放 ThresholdFilter。关 B 已经是 INFO 的 Logger 不会把 DEBUG 送到这里 -->
</appender>

<root level="INFO">
    <appender-ref ref="APP"/>
    <appender-ref ref="DEBUG_FILE"/>
</root>
```

`OrderRepository` 被设成 DEBUG 之后：它的 DEBUG 被 APP 的 `ThresholdFilter` 丢掉（关 E），被 `DEBUG_FILE` 写下；它的 INFO 两个文件都有。其他仍是 INFO 的 Logger 没有 DEBUG 事件，`debug.log` 里不会出现它们。若两个文件都看到同一条 INFO 觉得吵，在 `DEBUG_FILE` 上加 `LevelFilter`，`level=DEBUG`、`onMatch=ACCEPT`、`onMismatch=DENY`，这个短文件就只留 DEBUG。

Log4j2 对应写法是主 Appender 放 `ThresholdFilter level="INFO"`，DEBUG 文件不加这道阈值，再用 `<AppenderRef ref="DEBUG_FILE"/>`。第 1 阶的全局 ACCEPT 会让指定包的 DEBUG 诞生，同样被主文件的 Threshold 挡住，只进短文件。

异步要单独看关 F。Logback `AsyncAppender` 在队列剩余容量低于 `discardingThreshold` 时丢掉 INFO 及以下（`AsyncAppender.java` 第 39-41 行）。排障要看的 DEBUG 恰好在可丢集合里，队列一忙这些行会消失。排障窗口内让 `DEBUG_FILE` 走同步 Appender，或者把它放进一个不丢 DEBUG 的队列；不要把"刚放开的 DEBUG"再包进默认的 `AsyncAppender`。

## 5.6 放开之后的护栏

**限速。** 一个类的 DEBUG 在热路径上仍可能每秒几千行。Log4j2 的 `BurstFilter` 按级别限流：配置级别及更严重的事件直接 `onMatch`，更啰嗦的事件才消耗令牌（`BurstFilter.java` 第 263-278 行）。默认级别 WARN、每秒 10 条、突发容量为速率的 100 倍（第 58-60 行、第 365-368 行）。挂在 DEBUG 文件或关 A 上，把 INFO 及以下限住，WARN/ERROR 照常：

```xml
<BurstFilter level="INFO" rate="50" maxBurst="200" onMatch="NEUTRAL" onMismatch="DENY"/>
```

`level="INFO"` 时，INFO/DEBUG/TRACE 受速率限制，WARN 及以上直接放行（类注释第 41-43 行的例子就是这个方向）。`onMatch` 写成 NEUTRAL，令牌够的时候还要再过关 B；`onMismatch=DENY` 才是真正丢掉超额的那部分。Logback 一侧用 `DuplicateMessageFilter` 压住"同一句 DEBUG 打几千次"，它比较的是 format 字符串、不看参数（见 `Self4j.md` 7.6.3）。两者都是护栏，不是放开手段：限速之后采样是不完整的，结论要写明"这是被限速后的样本"。

**收回。** 用调度在到期时把 5.3 保存的 `previous` 设回去，并打一条 WARN：`logger {} debug window closed`。配置平台上的临时级别同样写过期时间。没有过期时间的"先开着"会变成永久 DEBUG。

**敏感字段。** 第 4 阶的 TRACE 会带上 SQL 参数和 HTTP 正文。只对一个类打开，短文件保留 1 天，并且这段日志本身不要包含口令、证件号、完整卡号。内部排障标记不要从公网 Header 原样透传。

**谁可以开。** Actuator 的 `loggers` 写操作、配置平台的级别变更，都收进和审计日志同一套权限。变更记录至少包含操作者、Logger 名、旧级别、新级别、实例、到期时间。

## 5.7 一次支付失败的放开顺序

INFO 里已有 `pay failed, orderId=1001, traceId=abc`，不知道是渠道超时还是本地状态不对。

1. 用 `traceId=abc` 把这条链路已有的 INFO/WARN 拉齐。若 WARN 里已有下游状态码，停止，不放开。
2. 仍缺本地分支：只在一台实例上把 `com.example.payment.OrderRepository` 设为 DEBUG，15 分钟后收回。复现请求打到这台。到 `debug.log` 看这一类，不在 `app.log` 里翻。
3. 发现判断在同包的 `ChannelClient`：把前缀扩到 `com.example.payment`，仍然是 DEBUG，仍然这一台，重新计时。
4. 流量太大、一开整个包就刷屏：收回第 3 步，改成第 1 阶。让用户从排障入口重放这一单，网关写入 `traceLevel=debug` 和 `debugLogger=com.example.payment`。其他订单不变。
5. DEBUG 能看到"调用了渠道"但没有参数：只把 `ChannelClient` 升到 TRACE，看完立即设回 DEBUG，再在窗口结束时设回 INFO。
6. 每一阶若文件仍空，回到 5.1 的表，检查是不是主 Appender 的 Threshold、全局 DENY 或异步丢弃把已经放开的事件吃掉了。

## 5.8 本章小结

- 逐步放开指的是一次只加宽一个维度：一次请求，或一个 Logger，只升一级，只在一台实例上，到点设回旧级别。root DEBUG 不在常规阶梯里。
- 改 Logger 级别只开关 B。Appender 的 Threshold、全局 DENY、异步丢弃仍会让这行到不了文件。DEBUG 写到保留期很短的独立文件，主文件维持 INFO。
- 高 QPS 用"已认证的请求标记 + 包前缀"在关 A 返回 ACCEPT，只跳过目标包的 DEBUG。整请求的 `DynamicThresholdFilter` ACCEPT 会把框架日志一起放出来。
- TRACE 是单独一阶。限速和重复抑制是护栏，用来防止放开之后打满磁盘，不代替"只看目标类"。
- 热更新重载会覆盖内存中的临时级别。一个排障窗口只使用 Actuator / `setLevel`，或只使用配置文件扫描，不要两套一起开。

---

# 附：对照时最容易错的几件事

1. 全局 `ACCEPT` 之后，每个 Appender 的 Filter 仍会执行。DEBUG 审计能进 `audit.log`，同时被 APP 的 `ThresholdFilter` 拒掉，是正常结果。
2. Logback Appender 上，整链 NEUTRAL 也会写入。只想留下匹配项时，`OnMismatch` 要写 DENY。
3. Log4j2 省略 `onMismatch` 时默认就是 DENY。全局 `MarkerFilter` 或 `ThreadContextMapFilter` 忘了写 `onMismatch="NEUTRAL"`，会把其余全部日志丢掉。
4. `AUDIT.add(PAYMENT)` 不会让 `log(PAYMENT)` 符合 `AUDIT`。要这个效果，Logback / SLF4J 写 `PAYMENT.add(AUDIT)`，Log4j2 原生写 `PAYMENT.setParents(AUDIT)`。
5. Log4j2 Logger 上的 Filter 返回 DENY 时，父 Logger 的 Appender 也收不到。文件分流把 Filter 放在 Appender 上。
6. Logback 全局 `MarkerFilter` 在 Fluent API 多个 Marker 时只看第一个；Appender 上的 `OnMarkerEvaluator` 看全部。Log4j2 的 `addMarker` 只保留最后一个，多个名字靠父链表达。
7. 排障时把 Logger 调到 DEBUG，只打开了关 B。主文件上的 `ThresholdFilter` 仍是 INFO、全局 Filter 仍在 DENY、或 DEBUG 走进了会丢弃 INFO 及以下的 `AsyncAppender` 时，文件里还是没有这行。DEBUG 进短保留文件，到点把 `setLevel` 设回放开前的值。
