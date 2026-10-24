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
┌─ 关 E  Appender 局部 Filter / AppenderRef 的 level ────────────┐
│ 只决定"这个 Appender 写不写"，不影响兄弟 Appender              │
│ Logback: 链的结果是 DENY 才不写；ACCEPT 与最终 NEUTRAL 都写    │
│ Log4j2 : AppenderControl 与 Appender 上各自还有一道，见 2.3    │
└────────────────────────────────────────────────────────────────┘
        │
        ▼
┌─ 关 F  异步队列的丢弃（只在包了 AsyncAppender / AsyncLogger 时）┐
└────────────────────────────────────────────────────────────────┘
        │
        ▼
┌─ 关 G  滚动检查（不丢事件，只可能先换文件）→ 编码 → write ─────┐
└────────────────────────────────────────────────────────────────┘
```

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

## 2.1 Logback：同步路径上的关

以 `log.info("user {} login", uid)` 为例，入口是 `filterAndLog_1`。

【源码证据】`logback-classic/.../classic/Logger.java` 第 392-407 行、第 427-432 行、第 256-268 行：

```java
final FilterReply decision = loggerContext.getTurboFilterChainDecision_1(...);
if (decision == FilterReply.NEUTRAL) {
    if (effectiveLevelInt > level.levelInt) {   // 关 B
        return;
    }
} else if (decision == FilterReply.DENY) {      // 关 A
    return;
}
// ACCEPT 直接落到下面
buildLoggingEventAndAppend(...);                // 关：构造事件 + callAppenders
```

```java
LoggingEvent le = new LoggingEvent(...);
le.addMarker(marker);                           // 传统 API 只有一个 Marker
callAppenders(le);                              // 沿 parent 向上，additive=false 则 break
```

关 A 的链本身在 `TurboFilterList.getTurboFilterChainDecision`（第 39-67 行）：按配置顺序走，**第一个** `DENY` 或 `ACCEPT` 立刻作为整链结果返回；全部 `NEUTRAL` 才得到 `NEUTRAL`。链为空时直接 `NEUTRAL`（第 44-46 行），等于没配全局 Filter。

`isInfoEnabled(marker)` 走的是同一条 TurboFilter 链（第 575-586 行调用 `callTurboFilters`，第 762-764 行），但参数里的 format 是 `null`。所以"先 `isInfoEnabled()` 再拼接字符串"这套旧写法，看不到消息正文，只能看到 Marker、Logger、Level。直接 `log.info(marker, format, arg)` 时，TurboFilter 能看到 format 和参数数组，参数仍未格式化进句子。

关 E 在 `AppenderBase.doAppend`（`logback-core/.../AppenderBase.java` 第 61-85 行）：

```java
if (!this.started) return;
if (getFilterChainDecision(eventObject) == FilterReply.DENY) return;
this.append(eventObject);
```

`FilterAttachableImpl.getFilterChainDecision`（第 51-65 行）同样是"第一个 DENY 或 ACCEPT 即返回"。`doAppend` **只把 DENY 当成不写**。因此：

- 链返回 `ACCEPT`：后面的 Filter 不再执行，事件写入。
- 链返回 `NEUTRAL`：事件同样写入。
- 想"匹配才留、不匹配就丢"，必须显式写 `<OnMismatch>DENY</OnMismatch>`。`AbstractMatcherFilter` 的默认值两个都是 `NEUTRAL`（第 20-21 行），忘了配 `OnMismatch` 等于这个 Filter 不拦截任何人。

关 G，`RollingFileAppender.subAppend`（第 205-223 行）先 `triggeringPolicy.isTriggeringEvent`，为真则 `rollover()`，然后 `super.subAppend`。滚动不丢弃当前事件。`OutputStreamAppender.subAppend`（第 248-257 行）调用 `prepareForDeferredProcessing()`，其中才会 `getFormattedMessage()` 并把 MDC 拷进事件（`LoggingEvent.java` 第 298-302 行），随后 `writeOut` → `encoder.encode` → `outputStream.write`（第 202-204 行、第 233-237 行）。

**结论：Appender 上的 Filter 看到的是尚未格式化的事件；最终句子和 MDC 快照发生在 Filter 放行之后。** 过滤器若自己去调 `event.getFormattedMessage()` 或 `event.getMDCPropertyMap()`，才会提前触发这两份计算。

### 2.1.1 用一段配置把关 A 和关 E 分开

业务代码：

```java
private static final Logger log = LoggerFactory.getLogger(OrderService.class);
private static final Marker AUDIT = MarkerFactory.getMarker("AUDIT");

public void login(String userId) {
    log.info("order flow {}", userId);             // 普通
    log.info(AUDIT, "login user={}", userId);      // 带 AUDIT
    log.debug(AUDIT, "login detail user={}", userId);
}
```

```xml
<configuration>
  <turboFilter class="ch.qos.logback.classic.turbo.MarkerFilter">
    <Marker>AUDIT</Marker>
    <OnMatch>ACCEPT</OnMatch>
    <OnMismatch>NEUTRAL</OnMismatch>
  </turboFilter>

  <appender name="APP" class="ch.qos.logback.core.FileAppender">
    <file>logs/app.log</file>
    <encoder><pattern>%level %msg%n</pattern></encoder>
    <filter class="ch.qos.logback.classic.filter.ThresholdFilter">
      <level>INFO</level>
    </filter>
    <filter class="ch.qos.logback.core.filter.EvaluatorFilter">
      <evaluator class="ch.qos.logback.classic.boolex.OnMarkerEvaluator">
        <marker>AUDIT</marker>
      </evaluator>
      <OnMatch>DENY</OnMatch>
      <OnMismatch>NEUTRAL</OnMismatch>
    </filter>
  </appender>

  <appender name="AUDIT_FILE" class="ch.qos.logback.core.FileAppender">
    <file>logs/audit.log</file>
    <encoder><pattern>%level %marker %msg%n</pattern></encoder>
    <filter class="ch.qos.logback.core.filter.EvaluatorFilter">
      <evaluator class="ch.qos.logback.classic.boolex.OnMarkerEvaluator">
        <marker>AUDIT</marker>
      </evaluator>
      <OnMatch>ACCEPT</OnMatch>
      <OnMismatch>DENY</OnMismatch>
    </filter>
  </appender>

  <root level="INFO">
    <appender-ref ref="APP"/>
    <appender-ref ref="AUDIT_FILE"/>
  </root>
</configuration>
```

三条调用的落点：

| 调用 | 关 A TurboFilter | 关 B root=INFO | APP 的 Threshold + OnMarker | AUDIT_FILE 的 OnMarker | 磁盘 |
|---|---|---|---|---|---|
| `info("order flow")` | 无 Marker，`OnMismatch=NEUTRAL` | INFO 通过 | Threshold 对 INFO 返回 NEUTRAL；OnMarker 不匹配，NEUTRAL | 不匹配，DENY | 只在 `app.log` |
| `info(AUDIT, "login")` | `contains("AUDIT")` 命中，ACCEPT，跳过级别门 | 被跳过 | Threshold 放行；OnMarker 匹配，DENY | 匹配，ACCEPT | 只在 `audit.log` |
| `debug(AUDIT, "login detail")` | 同样 ACCEPT，DEBUG 也不被 root=INFO 拦住 | 被跳过 | Threshold：DEBUG 比 INFO 低，**DENY** | 匹配，ACCEPT | 只在 `audit.log` |

第三条就是"全局 ACCEPT 不等于每个文件都写"。DEBUG 审计事件已经诞生，APP 的局部 `ThresholdFilter` 仍能把它拒掉。

`ThresholdFilter.decide`（第 39-48 行）：事件级别 `isGreaterOrEqual(配置级别)` 时返回 `NEUTRAL`，否则 `DENY`。它从不返回 `ACCEPT`，所以它挡得住"太啰嗦"的事件，挡不住后面的 Filter，也替代不了 Logger 的级别门。

## 2.2 Log4j2：同步路径上的关

【源码证据】调用先过 `AbstractLogger.logIfEnabled`（`log4j-api/.../AbstractLogger.java` 第 2330-2338 行）：`isEnabled` 为 false 就不再 `logMessage`。`isEnabled` 进入 `PrivateConfig.filter`（`log4j-core/.../core/Logger.java` 第 532-540 行）：

```java
boolean filter(final Level level, final Marker marker, final String msg) {
    final Filter filter = config.getFilter();          // 关 A：Configuration 级，不是 Logger 级
    if (filter != null) {
        final Filter.Result r = filter.filter(logger, level, marker, msg);
        if (r != Filter.Result.NEUTRAL) {
            return r == Filter.Result.ACCEPT;          // ACCEPT→true，DENY→false
        }
    }
    return level != null && intLevel >= level.intLevel(); // 关 B
}
```

这里的 `config.getFilter()` 是挂在 `<Configuration>` 下的 Filter。多个 Filter 会被收成 `CompositeFilter`：第一个 `ACCEPT` 或 `DENY` 作为整链结果（`CompositeFilter.java` 第 179-188 行）。

通过之后才创建 `LogEvent`，再进入 `LoggerConfig.log`（第 728-742 行 → 第 739-742 行）：

```java
protected void log(final LogEvent event, final LoggerConfigPredicate predicate) {
    if (!isFiltered(event)) {          // 关 C
        processLogEvent(event, predicate);
    }
}
```

`AbstractFilterable.isFiltered`（第 152-153 行）只有 `filter.filter(event) == DENY` 才算被过滤。`ACCEPT` 与 `NEUTRAL` 都算没被过滤。`processLogEvent`（第 762-768 行）先 `callAppenders`，再 `logParent`（第 796-800 行）。父节点走的是 `parent.log(event)`，会**再执行父节点自己的** `isFiltered`。子节点一旦 DENY，`logParent` 根本不会被调用，root 上的审计 Appender 也收不到。这是 Log4j2 才有的一关，Logback 的 Logger 上挂不住 Filter。

关 E 拆成三道，全在 `AppenderControl`（第 84-104 行、第 130-156 行）：

```java
public void callAppender(final LogEvent event) {
    if (shouldSkip(event)) return;    // 下面三选一即跳过
    ...
}
private boolean shouldSkip(final LogEvent event) {
    return isFilteredByAppenderControl(event)   // ① AppenderRef 上嵌套的 Filter，仅 DENY 跳过
        || isFilteredByLevel(event)             // ② <AppenderRef level="...">
        || isRecursiveCall();
}
// ② 的判定：ref 的 intLevel < 事件.intLevel 则跳过
//    例：ref level=ERROR(200)，事件 INFO(400)，200<400，跳过
private boolean isFilteredByAppender(final LogEvent event) {
    return appender instanceof Filterable && ((Filterable) appender).isFiltered(event);
    // ③ Appender 元素内部的 Filter，同样只有 DENY 才跳过
}
```

① 和 ③ 是两套 Filter，都会生效。`<AppenderRef>` 里的 Filter 先看，`<RollingFile>` 里面的 Filter 后看。

关 G：`RollingFileAppender.append`（第 479-481 行）先 `checkRollover(event)`，再 `super.append` → `layout.encode` 写入（`AbstractOutputStreamAppender.java` 第 209-230 行）。滚动不丢事件。

`AbstractFilter` 的默认动作和 Logback 相反，配的时候要分开记（第 109-110 行）：

- `onMatch` 省略 → `NEUTRAL`
- `onMismatch` 省略 → `DENY`

一个只写了 `<MarkerFilter marker="AUDIT"/>`、没写 onMatch/onMismatch 的全局过滤器，会把**所有不带 AUDIT 的日志 DENY 掉**。Logback 同位置忘配时两个默认都是 NEUTRAL，表现为"这个过滤器没起作用"。

### 2.2.1 与 2.1.1 同一业务的 Log4j2 配置

下面这份按 **Log4j2 原生 Marker** 来写：`PAYMENT.setParents(AUDIT)` 之后，打出 `PAYMENT` 也会被 `marker="AUDIT"` 命中。SLF4J `add()` 的方向见第三章，不要把两套 API 混在同一棵树上。

```java
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

private static final Logger log = LogManager.getLogger(OrderService.class);
private static final Marker AUDIT = MarkerManager.getMarker("AUDIT");
private static final Marker PAYMENT =
        MarkerManager.getMarker("PAYMENT").setParents(AUDIT);

public void login(String userId) {
    log.info("order flow {}", userId);
    log.info(AUDIT, "login user={}", userId);
    log.debug(AUDIT, "login detail user={}", userId);
    log.info(PAYMENT, "pay user={}", userId);   // 父链上有 AUDIT，过滤器认 AUDIT 时命中
}
```

```xml
<Configuration>
  <!-- 关 A：挂在 Configuration 下，事件构造前执行 -->
  <MarkerFilter marker="AUDIT" onMatch="ACCEPT" onMismatch="NEUTRAL"/>

  <Appenders>
    <File name="APP" fileName="logs/app.log">
      <PatternLayout pattern="%level %msg%n"/>
      <!-- 关 E③ Appender 自己的 Filter -->
      <ThresholdFilter level="INFO" onMatch="NEUTRAL" onMismatch="DENY"/>
      <MarkerFilter marker="AUDIT" onMatch="DENY" onMismatch="NEUTRAL"/>
    </File>
    <File name="AUDIT_FILE" fileName="logs/audit.log">
      <PatternLayout pattern="%level %marker %msg%n"/>
      <MarkerFilter marker="AUDIT" onMatch="ACCEPT" onMismatch="DENY"/>
    </File>
  </Appenders>

  <Loggers>
    <Root level="info">
      <AppenderRef ref="APP"/>
      <AppenderRef ref="AUDIT_FILE"/>
    </Root>
  </Loggers>
</Configuration>
```

| 调用 | 关 A | 关 B root=INFO(400) | APP | AUDIT_FILE |
|---|---|---|---|---|
| `info("order flow")` | 无 Marker，NEUTRAL | 400>=400 通过 | Threshold 对 INFO 匹配，NEUTRAL；Marker 不匹配，NEUTRAL | Marker 不匹配，DENY | 只在 `app.log` |
| `info(AUDIT, ...)` | `isInstanceOf("AUDIT")`，ACCEPT，跳过关 B | 被跳过 | Marker 匹配，DENY | ACCEPT | 只在 `audit.log` |
| `debug(AUDIT, ...)` | ACCEPT，DEBUG(500) 也被放进事件 | 被跳过 | Threshold：DEBUG 不如 INFO 具体，DENY | ACCEPT | 只在 `audit.log` |
| `info(PAYMENT, ...)` | `PAYMENT` 的父是 `AUDIT`，`isInstanceOf("AUDIT")` 为真，ACCEPT | 被跳过 | 认 AUDIT 的 MarkerFilter DENY | ACCEPT | 只在 `audit.log` |

如果把 `<MarkerFilter marker="AUDIT" onMatch="DENY" onMismatch="NEUTRAL"/>` 从 File 上挪到 `<Logger name="com.example" level="info">` 里面，它就变成关 C。此时 `info(AUDIT)` 在 `com.example` 这个 LoggerConfig 上被 DENY，`logParent` 不会执行，root 的 `AUDIT_FILE` 也写不出这一行。Appender 上的 DENY 做不到这件事。

## 2.3 异步多出来的关，以及它改变了谁能看见 MDC

同步路径走到关 E 之后若命中的是 `AsyncAppender`，顺序变成：

1. `AsyncAppender` 自己的 `doAppend` 仍在**调用线程**上跑它自己的 Filter 链（它也是 `AppenderBase`）。
2. Filter 没 DENY，才进入 `AsyncAppenderBase.append`（第 161-167 行）：队列剩余容量 `< discardingThreshold` 且 `isDiscardable` 时直接丢弃。classic 的 `AsyncAppender.isDiscardable`（第 39-41 行）把 TRACE/DEBUG/INFO 视为可丢，WARN/ERROR 保留。
3. `preprocess`（classic 第 44-47 行）在入队前调用 `prepareForDeferredProcessing()`，这一刻才把格式化后的句子、线程名、MDC 快照冻结进事件。
4. worker 线程取出事件，调用**被包裹的那个** Appender 的 `doAppend`。被包裹 Appender 上的 Filter 跑在 worker 上。

因此：

- 写在 `<appender class="AsyncAppender">` 内部、包裹层自己身上的 Filter，看到的是调用线程的实时 `MDC.get`。
- 写在被包裹的 `RollingFileAppender` 上的 Filter，不能再读 `MDC.get`（worker 的 ThreadLocal 是空的），要读 `event.getMDCPropertyMap()`。这份 map 在入队前已经拷好（`LoggingEvent.getMDCPropertyMap`，第 453-467 行）。

Log4j2 全异步（`AsyncLogger`）把关 C 及之后挪到 Disruptor 的消费线程，关 A 和关 B 仍在调用线程的 `isEnabled` 里完成。事件对象里的 `contextData` 是调用线程的 ThreadContext 快照，Appender 上的 `ThreadContextMapFilter` 读的是 `event.getContextData()`（见 4.4），所以异步之后局部 MDC 过滤仍然认的是打日志那一刻的 map。

## 2.4 本章小结

- 写入文件之前至少有：全局 Filter → Logger 级别门 →（Log4j2 还有 LoggerConfig Filter）→ additive 父链 → 每个 Appender 自己的 Filter →（异步则再加丢弃关）→ 滚动 → 编码写字节。
- 消息格式化发生在 Filter 放行之后的 `prepareForDeferredProcessing` / Layout 编码，关 A 看到的是 format 字符串和参数数组。
- Logback：Appender 链只有 DENY 才不写，ACCEPT 与全 NEUTRAL 都写。
- Log4j2：全局 Filter 的默认 `onMismatch` 是 DENY；LoggerConfig 上的 DENY 会连父节点一起切断；AppenderRef 的 `level` 是按"更具体才留"比较 intLevel。

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

此时 `PAYMENT.isInstanceOf("AUDIT")` 为真，`AUDIT.isInstanceOf("PAYMENT")` 为假。放进 2.2.1 里 `marker="AUDIT"` 且 `onMismatch="DENY"` 的 `AUDIT_FILE`：

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
