# LangGraph4j 深度源码解析（写给初学者的架构全景）

> **本文基于的源码**：`D:\code\3rd\langgraph4j`（GitHub `bsorrentino/langgraph4j`，已迁移至同名组织 `langgraph4j`），版本 **1.9.4**（2026-10-09 发布，写作时最新稳定版）。文中所有【源码证据】的文件路径与行号均为对该版本实际读取所得；`langgraph4j-core` 指核心模块源码根 `langgraph4j-core\src\main\java\org\bsc\langgraph4j`，后文引用一律省略该前缀，如 `CompiledGraph.java:844`。
>
> **姊妹篇**：本文与同目录的《LangGraph 深度源码解析》（Python 版，`LangGraph.md`）互为对照——LangGraph4j 是 Python LangGraph 的 Java 移植（作者自述 "inspired by LangGraph solution"），**概念同源、实现分流**：状态/归约器/条件边/检查点/中断恢复这些概念一一对应，但执行引擎、状态模型、流式机制、人机协同的落地方式差别很大。读本文时建议两版对照，"哪些是移植保真的、哪些是 Java 生态自己的答案"正是全文的主线。
>
> **阅读约定**：每章"先白话、后源码"——先用一两句人话讲清"这是什么、为什么需要它"，再给出源码证据链（`类名.java` + 行号 + 代码片段）。行号只对 1.9.4 精确，读者打开源码按类名 + 方法名定位即可。每章末尾有"本章小结"。

## 如何读这份文档

如果你是 LangGraph4j 初学者，推荐两遍读法：

- **第一遍（建立地图，1~2 小时）**：只读第一章（总览）每一节的开头白话段、各章的"本章小结"。目标是能回答：`graph.stream()` 内部的循环长什么样（是 Pregel 吗）？两个并行分支同时写一个状态键会发生什么？中断凭什么能在下一次调用时恢复？checkpoint 里到底存了什么、什么时候被"释放"？
- **第二遍（深入源码）**：对照每一章的【源码证据】打开源码逐行读。顺序建议：第二章（State 与 Channel）→ 第三章（图构建与编译）→ 第四章（执行引擎）→ 第六章（中断与恢复）→ 第五章（Checkpoint），其余章节（流式、预制件、生态集成、Studio）随用随查。

---

# 一、总览：LangGraph4j 的定位、设计哲学与整体架构

## 1.1 一句话定位

**LangGraph4j 是 Python LangGraph 的 Java 移植版**：把"长时运行、有状态的多智能体应用"建模为一张"节点读写共享状态"的图（允许环），用共享状态 + 归约器管理数据流，用检查点支撑持久化与人机协同，并配套 **LangChain4j 与 Spring AI 两条 AI 框架集成线**。官方 README 的自我定位只有一句话（`README.md:8`）：

> "LangGraph for Java. A library for building stateful, multi-agents applications with LLMs, built for work with langchain4j and Spring AI."

拆开看这句定位里的每个词：

- **LangGraph for Java**：它是移植不是复刻。核心概念（StateGraph、条件边、Checkpointer、interrupt）与 Python 版对齐，但执行引擎、状态模型、流式机制全部按 Java 生态重新设计（详见 1.2 与 2.6）。作者是 bartolomeo sorrentino（`@bsc`），2024 年初启动，是 Java 侧同类项目中最成熟的一个。
- **stateful**：节点之间不靠参数传递消息，而是共同读写一个 `AgentState`（本质是 `Map<String, Object>` 的类型化封装）；每个键可注册归约器（reducer）决定"怎么合并"。
- **multi-agent**：图可以嵌套（子图即节点）、可以并行（多目标边编译成 `ParallelNode`）、官方还提供了 supervisor / multi-agent 的 how-to 示例；两条集成线各自提供 ReAct 智能体模板（`AgentExecutor` / `AgentExecutorEx`），且支持"子代理即工具"（SubAgent）。
- **built for work with langchain4j and Spring AI**：引擎本体对"如何调 LLM"零依赖——`langgraph4j-core` 连 jackson/gson 都是 `provided`。AI 能力全部经两条集成线注入，这是它与 Python 版"langgraph 依赖 langchain-core"最大的生态差异（详见第九章）。

它解决的问题与 Python 版一致：**智能体的工程化难题**——控制流有环（图模型）、多步状态合并（Channel 归约）、随时可停可续（Checkpoint）、人类可介入（中断/恢复）、过程可观测（流式 + Studio 调试界面 + OpenTelemetry）。

## 1.2 设计哲学：读源码前先记住四句话

与 Python 版对照着记，四句话每句都对应一处"同一概念、不同实现"的分叉点：

1. **图是数据结构，引擎是顺序事件循环——没有 Pregel**。Python 版用 BSP（整步同步并行）超步循环驱动整张图；LangGraph4j 的引擎是一个**单线程顺序事件循环**（`CompiledGraph.Emitter.accept()`，`CompiledGraph.java:844`）：一次走一个节点，节点完成 → 合并状态 → 算下一条边 → 落 checkpoint → 继续。并行只发生在两种显式场景：多目标边在编译期被折叠成 `ParallelNode` 内部并发（`ParallelNode.java:122`），以及节点动作自身返回 `CompletableFuture`。**读源码时盯住这个 for(;;) 循环，一切前端语法糖都会豁然开朗。**
2. **状态归约显式，冲突不检测**。Python 版的 LastValue 通道在并发冲突时抛 `InvalidUpdateError`；LangGraph4j 的 `AgentState.updateState()`（`state/AgentState.java:173`）只是把"当前状态 + 各分支的部分更新"按序合并：Appender 通道去重追加、普通键**后者覆盖**。没有运行时冲突检测——"两个分支写同一个单值键"是用户自己的责任（详见 2.6）。
3. **每个节点执行完就存档，正常跑完就"释放"**。执行循环每处理完一个节点都调 `addCheckpoint()`（`CompiledGraph.java:390`）落一个 Checkpoint（id/state/nodeId/nextNodeId 四元组）；1.9 起，**图正常跑到 END 时自动 `releaseThread()` 把整条 checkpoint 链归档为带版本号的 Tag**（`CompiledGraph.java:836`，`CompileConfig.java:167` 默认开启）——中断的运行保留历史供恢复，正常结束的运行释放存储。中断/恢复/time-travel 全部构建在这同一条存档机制上，没有第二套"暂停恢复"系统。
4. **低层可控，预制件只是糖**。核心对"如何做 agent"零硬编码：官方的 ReAct 智能体就是一个用 `StateGraph` 手拼出来的普通图（core 的 `agent/Agent.java:101-126`：`START → agent ⇄ action → end` 三节点骨架）。1.9 甚至把这套骨架抽成了 core 的通用 `Agent`/`AgentEx` 接口，LangChain4j/Spring AI 两条集成线各自往里填"调模型"和"执行工具"两个动作——**引擎薄、生态厚**，与 Python 版哲学一致，实现路径不同。

## 1.3 仓库与模块分层全景

bsorrentino/langgraph4j 是一个 Maven 多模块工程（根 `pom.xml`，聚合 24 个模块）。按"用户感知"分层如下（artifactId 均经各模块 pom 实证）：

```
┌──────────────────────────── 调试与部署层 ─────────────────────────────┐
│ langgraph4j-studio（base）/ -jetty / -quarkus / -springboot          │
│   → 内嵌 Web Playground：画图、步进、查看/编辑状态、中断恢复            │
│ langgraph4j-ag-ui-json / -sdk / -springboot                          │
│   → AG-UI 协议（CopilotKit 兼容）的 SSE 事件流适配                    │
│ langgraph4j-javelit（Java 21）→ Javelit（Java 版 Streamlit）UI 组件   │
├──────────────────────────── 可观测层 ────────────────────────────────┤
│ langgraph4j-opentelemetry → OTEL 追踪钩子与收集器                     │
├──────────────────────────── 集成层（两条 AI 框架线）─────────────────┤
│ langgraph4j-langchain4j            ← LangChain4j 1.22.0              │
│ langgraph4j-langchain4j-agentexecutor（ReAct 智能体模板）             │
│ langgraph4j-agent-executor（旧坐标 relocation 占位）                 │
│ langgraph4j-spring-ai              ← Spring AI 2.0.1                 │
│ langgraph4j-springai-agentexecutor（ReAct 智能体模板）               │
│ spring-ai-agent-archetype（Maven 脚手架：一键生成 Spring Boot 工程）  │
├──────────────────────────── 持久化 SPI 层（可插拔）──────────────────┤
│ langgraph4j-sqlite-saver / -postgres-saver（V1+V2）/ -mysql-saver   │
│ langgraph4j-redis-saver / -dynamodb-saver / -hazelcast-saver        │
│ langgraph4j-cockroachdb-saver / -oracle-saver                       │
├──────────────────────────── 预制件层 ────────────────────────────────┤
│ core 内置：agent 包（Agent/AgentEx 骨架、ToolResponseBuilder、       │
│   skill 解析）+ prebuilt 包（MessagesState/MessagesStateGraph）      │
├──────────────────────────── 引擎层 ──────────────────────────────────┤
│ langgraph4j-core：StateGraph / CompiledGraph(Emitter 事件循环) /     │
│   CompileConfig / RunnableConfig / checkpoint / serializer /         │
│   streaming / hook（RetryPolicy）/ diagram（PlantUML/Mermaid）       │
├──────────────────────────── 地基 ────────────────────────────────────┤
│ org.bsc.async:async-generator 5.1.0（作者自研的 Java 生成器库，       │
│   AsyncGenerator/AsyncGeneratorFlow —— 整个流式体系的基座）          │
│ org.jspecify、org.slf4j-api（gson/jackson 为 provided 可选）         │
├──────────────────────────── 工具链 ──────────────────────────────────┤
│ langgraph4j-dsl（JSON-DSL：图的 JSON 描述与解析，供 Studio 消费）     │
│ langgraph4j-bom（统一版本清单）+ how-tos（可运行示例集）              │
└──────────────────────────────────────────────────────────────────────┘
```

core 模块的 Java 包一览（`langgraph4j-core\src\main\java\org\bsc\langgraph4j` 实测，仅列主干）：

```
org.bsc.langgraph4j/
├── StateGraph.java        图构建器（489 行）
├── CompiledGraph.java     编译产物 + 执行引擎 Emitter（1183 行，全框架最核心）
├── CompileConfig.java     编译期配置（checkpointSaver/interrupts/recursionLimit）
├── RunnableConfig.java    运行期配置（threadId/checkPointId/流模式/元数据）
├── GraphInput.java        sealed 接口：GraphArgs（启动）/ GraphResume（恢复）
├── NodeOutput.java        流的基本输出单元（node + state）
├── GraphResult.java       终态的统一封装（状态/中断/Tag/取消）
├── action/                NodeAction、AsyncNodeAction、Command、
│                          InterruptibleAction、InterruptionMetadata…
├── state/                 AgentState、Channel、AppenderChannel、Channels、
│                          StateSnapshot、AgentStateFactory
├── internal/node|edge/    Node、ParallelNode、SubCompiledGraphNode、
│                          Edge、EdgeValue、EdgeCondition（内部结构）
├── internal/hook/         NodeHooks/EdgeHooks（钩子链 + 中断拦截）
├── hook/                  公开钩子接口与 RetryPolicy
├── checkpoint/            Checkpoint、BaseCheckpointSaver、
│                          AbstractCheckpointSaver、MemorySaver、FileSystemSaver
├── serializer/            StateSerializer 体系（JDK 流/Jackson/Gson 三套）
├── streaming/             StreamingOutput / StreamingOutputEnd
├── prebuilt/              MessagesState / MessagesStateGraph
├── agent/                 Agent/AgentEx 图骨架、ToolResponseBuilder、skill/ 解析
├── diagram/               MermaidGenerator、PlantUMLGenerator
└── utils/                 CollectionsUtils、FutureUtils、TypeRef…
```

一个容易踩的认知坑：**Python 版的"通道（Channel）体系"在 Java 版里退化成了"归约器注册表"**——core 的 `Channel` 只是一个带 `update()` 方法的接口（`state/Channel.java:74`），没有版本号、没有超步、没有 LastValue/Topic 通道动物园。它是"每个键怎么合并"的策略对象，不是数据结构。

## 1.4 模块依赖图（以各模块 pom.xml 实证）

| 模块 | 直接依赖（运行时） | 说明 |
|---|---|---|
| langgraph4j-core (1.9.4) | `org.bsc.async:async-generator` 5.1.0、jspecify、slf4j-api；**provided**：gson 2.10.1、jackson 2.21.1 | 引擎本体，编译期几乎零依赖（`langgraph4j-core/pom.xml:28-53`） |
| langgraph4j-langchain4j | langgraph4j-core + `dev.langchain4j:langchain4j` 1.22.0 + `langchain4j-skills` 1.22.0-beta32 | LangChain4j 集成（流式生成器/工具服务/序列化器） |
| langgraph4j-langchain4j-agentexecutor | core + langgraph4j-langchain4j | ReAct 智能体（AgentExecutor/AgentExecutorEx） |
| langgraph4j-agent-executor | 纯 relocation pom | 旧坐标 → 新坐标跳转（1.9.4 改名兼容） |
| langgraph4j-spring-ai | core + spring-ai 2.0.1（commons/model/client-chat） | Spring AI 集成（Flux 桥接/工具服务/序列化器） |
| langgraph4j-springai-agentexecutor | core + langgraph4j-spring-ai | Spring AI 版 ReAct 智能体 |
| spring-ai-agent-archetype | packaging=maven-archetype | 脚手架：生成带 Studio 的完整 Spring Boot 工程 |
| 8 个 *-saver | core + 各自驱动（sqlite-jdbc、postgresql、mysql-connector-j、lettuce、aws sdk…） | 持久化插件 |
| langgraph4j-studio(base) | core + langgraph4j-dsl + jakarta.servlet-api 6.1 | Studio 协议核心（Servlet） |
| -jetty / -quarkus / -springboot | studio-base + 各自运行时（Jetty 12 / Quarkus 3.18.3 / Boot 3.4.2） | 三种部署壳 |
| langgraph4j-ag-ui-* | `com.agui.community:java-core/server` 0.1.1 + core | AG-UI 协议适配 |
| langgraph4j-javelit | `io.javelit:javelit` 0.89.0 + core | Java 21（唯一非 17 的模块） |
| langgraph4j-dsl | core | JSON-DSL 图描述 |
| langgraph4j-opentelemetry | core + opentelemetry-api | OTEL 钩子 |

这张表本身就是一份架构说明：**core 是纯引擎**（AI 模型交给两条集成线、持久化交给 saver 插件、可视化交给 studio/ag-ui，全部单向依赖 core）；**两条集成线平行对称**（各有 StreamingChatGenerator / ToolService / StateSerializer / AgentExecutor 四件套）；**引擎对序列化库只做 provided 可选依赖**（用户自选 Jackson 或 Gson 序列化器，JDK 序列化兜底）。

## 1.5 关键问题 → LangGraph4j 方案映射（全文导览）

| 智能体开发的关键问题 | LangGraph4j 的方案 | 详见 |
|---|---|---|
| 控制流有环、有分支，Chain/管道模型表达不了 | StateGraph 显式建模：节点 + 边 + 条件边，`CompiledGraph.Emitter` 顺序事件循环执行 | 第二章、第四章 |
| 状态怎么在节点间合并 | `AgentState.updateState()`：部分更新先经 Channel 归约器，再与当前状态合并；`MARK_FOR_REMOVAL/MARK_FOR_RESET` 两个哨兵控制删除/重置 | 第二章 |
| 并行分支写同一个键怎么办 | Appender 通道去重追加；单值键后者覆盖——**无 InvalidUpdateError，需自己设计归约器** | 第二章 2.6、第四章 4.5 |
| 多步任务中断后要从头跑吗 | 每节点落 Checkpoint；`RunnableConfig.threadId` 定位线程，断点续跑 | 第五章 |
| 想让人类审批/修改中间结果 | 三种中断形态：声明式 `interruptBefore/After`、节点级 `InterruptibleAction`（审批标准姿势）、异常式 `GraphInterruptException`；恢复用 `GraphInput.resume()` | 第六章 |
| LLM 输出想逐 token 推给用户 | 节点返回带 `AsyncGenerator` 值的 Map → `embedGenerator` 自动汇入输出流；两条集成线的 `StreamingChatGenerator` 各自桥接 handler/Flux | 第七章 |
| 工具想反向控制图路由 | `Command(gotoNode, update)` 统一控制流；条件边动作和工具回调都可返回 Command | 第三章 3.3、第八章 |
| 不想手搭 ReAct 循环 | core 的 `Agent`/`AgentEx` 骨架 + 两条集成线的 `AgentExecutor`/`AgentExecutorEx` 一行建图 | 第八章、第九章 |
| 想可视化工调试图 | Studio 四模块（Jetty/Quarkus/Spring Boot 三种壳）；PlantUML/Mermaid 导出；JSON-DSL | 第十章 |
| 前端对话（CopilotKit）接入 | AG-UI 协议三模块：`POST /sse/{agentId}` 事件流 | 第十章 |
| LLM 调用抖动、超时 | `RetryPolicy`（WrapCall 钩子：指数退避 + 抖动，1.9.0-beta5 引入） | 第三章 3.6 |
| 对接 OpenTelemetry | `langgraph4j-opentelemetry`：节点/边级 Span 钩子（1.8 引入） | 第十章 |

## 1.6 版本演进：1.0 → 1.8(LTS) → 1.9 关键变化对比

写作时（2026 年 10 月）的版本格局：**1.9.x 是开发主线**（1.9.4，2026-10-09；README 明言"为迈向 2.0 做准备"），**1.8.x 是 LTS**（`support/1.8.x` 分支，只收 bugfix，最后一版 1.8.15/2026-05-07）。本节结论均以仓库 `CHANGELOG.md`（约定式提交自动生成，可逐条溯源）与 README 为准。

### 1.6.1 版本时间线

| 版本 | 发布日期 | 一句话主题 |
|---|---|---|
| 1.0.0 | 2024-11-15 | 首个正式版：StateGraph + CompiledGraph + checkpoint 基本盘定型 |
| 1.1.0 / 1.2.0 | 2024-11-26 / 2025-01-05 | 快速迭代期：序列化器体系、Studio 完善 |
| 1.3.0 / 1.4.0 / 1.5.0 | 2025-01-30 / 02-17 / 03-25 | 生态扩张：spring-ai / langchain4j 集成线、结构化输出 |
| 1.6.0 | 2025-08-12 | 稳定化：API 收口（invokeFinal/GraphInput 引入等） |
| **1.7.0** | **2025-10-22** | **`CompileConfig.recursionLimit()` 取代硬编码 maxIterations；工具执行可返回 `Command`（#254）；`ToolResponseBuilder.COMMAND_RESULT` 机制成形** |
| **1.8.0**（LTS） | **2026-02-02** | **子图 checkpoint 恢复（#326，`SUBGRAPH_RESUME_UPDATE_DATA`/`isResumeSubgraph`）；OpenTelemetry 参考实现（#271）；钩子体系重构（去掉默认状态更新，职责更纯粹）；移除废弃的 `node_async`（BREAKING）**；1.8.x 持续补丁至 1.8.15（2026-05-07）后转入 LTS |
| 1.9.0-beta4 | 2026-08-30 | **`GraphInterruptException` 异常式中断（#432）；SaverV2**（SQLite/Postgres 重写：thread/tag 记录模型 + 管理面板，旧版保留兼容）；**SkillInjector**（langchain4j 线技能动态注入）；checkpoint `release/Tag` 版本化 |
| 1.9.0-beta5 | 2026-09-06 | **节点重试策略 `RetryPolicy`**；SaverV2 收尾；Javelit 组件（JtDataTable 等） |
| 1.9.0（正式） | 2026-09-17 | 自定义 Dispatcher（#427，`RunnableConfig.CUSTOM_DISPATCHER`） |
| 1.9.1~1.9.4 | 2026-09-23 ~ 10-09 | 补丁线：`emitterExecutor`/`disableCloneState`（1.9 起）、interruptBeforeEdge 修复、artifactId 改名（langchain4j 线）+ relocation |

### 1.6.2 特性引入版本对照表（可溯源）

| 特性 | 引入版本 | 证据 |
|---|---|---|
| StateGraph / CompiledGraph / MemorySaver / 条件边 | ≤1.0（2024-11） | CHANGELOG v1.0.0 起点即存在 |
| `GraphDefinition` sealed 接口与 `reduce()`（DSL/可视化的函数式出口） | 1.7.4 | CHANGELOG；`langgraph4j-dsl` 的 `JsonDslGenerator` 即实现 `GraphDefinition.Reducer` |
| NodeHook/EdgeHook 钩子雏形（before/after/wrap + GraphPath） | 1.7.9 | CHANGELOG；1.8.0 体系成型并挂钩 Agent/AgentEx |
| `recursionLimit`（CompileConfig/RunnableConfig 两级） | 1.7.0 | CHANGELOG：移除硬编码 maxIterations，`CompileConfig` 新增 recursionLimit |
| 工具回调返回 `Command` 控制路由（`COMMAND_RESULT`） | 1.7.0（#254） | CHANGELOG langchain4j "allow return command from tool execution" |
| 子图 checkpoint 恢复（`isResumeSubgraph`） | 1.8.0（#326） | CHANGELOG `SUBGRAPH_RESUME_UPDATE_DATA` + CompiledGraph 子图恢复增强 |
| OpenTelemetry 钩子（`OTELWrapCallTraceHook` 等） | 1.8.0（#271） | CHANGELOG 四个 OTEL 类 + Jaeger/OTLP 配套 |
| `InterruptableAction` → `InterruptibleAction`（含 config 参数） | 1.8 线过渡，1.9 完成废弃 | `InterruptableAction.java:11` `@Deprecated(forRemoval)` |
| `GraphInterruptException` 异常式中断 | 1.9.0-beta4（#432） | `GraphInterruptException.java:11` `@since 1.9.0-beta4` |
| SaverV2（SQLite/Postgres 重写 + Tag 版本化 + 管理面板） | 1.9.0-beta4/5 | CHANGELOG；`BaseCheckpointSaver.release/registerInterruption` 标注 `@since 1.9.0-beta4` |
| `RetryPolicy`（指数退避钩子） | 1.9.0-beta5 | CHANGELOG "configurable node retry policy" |
| SkillInjector / 单轮卸载 | 1.9.0-beta4 / beta5（PR #439） | CHANGELOG langchain4j-agent |
| 自定义 Dispatcher | 1.9.0（#427） | CHANGELOG core |
| Saver V2 全家桶（MySQL/Oracle 对齐）+ sqlite-saver 模块 | beta2（sqlite 新模块）/beta7（MySQL/Oracle） | CHANGELOG |
| 子图 CheckpointSaver 托管（`putSubGraphSaver`） | 1.9.0-beta1（#407） | `BaseCheckpointSaver.java:115` `@since 1.9.0-beta1` |
| transient 状态属性 / 可禁用状态克隆 | 1.9.0-beta1（#147/#386） | CHANGELOG；`StateSerializer.declareTransientAttributes` |
| `emitterExecutor` / `disableCloneState` / `nodePath` | 1.9（beta 线） | `RunnableConfig.java:136/166/410` `@since 1.9` |
| AG-UI 三模块（ag-ui-json/sdk/springboot） | 1.9.1（#480） | CHANGELOG "新增 AG-UI 集成模块" |
| `GraphInput.resume(Checkpoint)`（按指定检查点恢复） | 1.9.3（#483） | `GraphInput.java:13`；CHANGELOG |
| 调用方提供执行器（图循环线程可插拔）+ saver 连接关闭修复 | 1.9.4（#485） | CHANGELOG；`RunnableConfig.emitterExecutor` |
| langchain4j 线 artifactId 改名 + relocation | 1.9.4 | `langchain4j/langchain4j-agent-relocation/pom.xml` relocation 配置 |

### 1.6.3 对初学者的意义：哪些资料过时了，哪些永远有效

- **过时/新项目避免**：`InterruptableAction`（改用 `InterruptibleAction`）、`addSubgraph`（改用 `addNode(String, StateGraph)`，`StateGraph.java:342` 已 `@Deprecated(forRemoval)`）、`AsyncNodeAction.node_async` 的旧签名、`setMaxIterations`（改用 `recursionLimit`）、langchain4j 线旧坐标 `langgraph4j-agent-executor`、SaverV1（新项目直接用 V2）。
- **1.7 时代教程里永久有效的部分**：StateGraph 建模、`AgentState` 子类 + SCHEMA、`addConditionalEdges`、`Command`、checkpointer + threadId、`GraphInput.resume()`——这些到 1.9 骨架未变，本文第二~六章正是按这条主线写的。
- **版本对应关系**：2024 教程 ↔ 1.0~1.2；2025 上半年教程 ↔ 1.3~1.6；2025 末教程 ↔ 1.7/1.8；2026 教程 ↔ 1.9。注意 Python 版教程的"迁移映射"：`Annotated reducer` ↔ SCHEMA 显式 Channel；`interrupt()` 函数 ↔ `InterruptibleAction`/`GraphInterruptException`；`add_messages` ↔ `MessagesState` + AppenderChannel；Pregel 超步 ↔ 顺序事件循环。

## 1.7 全文章节地图

- **第二章 State 与 Channel（一切的地基）**：AgentState 的两个哨兵标记；updateState 归约管线；Channel 契约与工厂；AppenderChannel 的删除/替换语义；MessagesState；与 Python 通道体系的对照。
- **第三章 图的构建与编译（StateGraph）**：addNode 四种重载（含 Command 节点糖）；addEdge 的"多目标=并行"语义；条件边与 Command 映射；validateGraph 校验规则；compile() 的两步变形（ParallelNode 折叠 + 子图扁平化）；钩子体系与 RetryPolicy；图可视化。
- **第四章 执行引擎（CompiledGraph.Emitter）**：AsyncGenerator 拉模型基座；主循环逐段走读；状态克隆与序列化成本；ParallelNode 并发合并；两种子图形态与执行路径；resume 的重放语义。
- **第五章 Checkpoint 持久化**：Checkpoint 四元组与 Python 版结构对照；BaseCheckpointSaver 契约（list/get/put/release/tag/registerInterruption/子图 saver）；MemorySaver 与 releaseThread 生命周期；序列化器三套实现。
- **第六章 人在环路（中断与恢复）**：三种中断形态的源码路径；InterruptionMetadata 与 registerInterruption；恢复链路全走读；AgentEx 审批模式；与 Python `interrupt()` 的对照。
- **第七章 流式输出与异步模型**：StreamingOutput；"_generator" 约定与 embedGenerator；StreamMode 两档；emitterExecutor 与并行 executor；Generator 中断传播。
- **第八章 预制件：Agent/AgentEx 骨架**：ReAct 循环的标准图；工具分发节点；ToolResponseBuilder 让工具控制路由；SKILL.md 解析。
- **第九章 生态集成：LangChain4j 与 Spring AI**：两条线的对称四件套；AgentExecutor/AgentExecutorEx 用法；消息模型零转换；SkillInjector；Spring AI 的 Flux 桥接与 SubAgent。
- **第十章 可观测与调试**：Studio 四模块与 REST 协议（自有协议，不兼容 Python LangGraph Server）；isRunningInStudio 联动；AG-UI 事件流；javelit；opentelemetry；dsl。
- **第十一章 工程实践与常见坑**：选型决策；性能（状态克隆、执行器死锁）；并发写、releaseThread、序列化注册等高频坑。
- **第十二章 附录**：关键类速查表、学习路线、与 Python LangGraph 术语对照表。

---
# 二、一切的地基：AgentState 与 Channel（状态与归约器）

> 本章对应源码：`state/AgentState.java`（状态模型与归约管线）、`state/Channel.java`（通道契约）、`state/Channels.java`（工厂）、`state/AppenderChannel.java`（追加通道）、`prebuilt/MessagesState.java`（消息状态预制件）。行号均为 1.9.4 实读。

## 2.1 先说白话：LangGraph4j 的"状态"是什么？

节点之间不传参数，而是共同读写一个**共享状态**。在 Python LangGraph 里，这个状态是一个 TypedDict（类型注解 + Annotated reducer）；在 LangGraph4j 里，它是一个**继承 `AgentState` 的 Java 类 + 一张显式的 SCHEMA 表**：

```java
// 用户代码的样子（摘自 core 测试与 how-tos 的惯用写法）
public static class MyState extends AgentState {
    public MyState(Map<String, Object> initData) { super(initData); }
    public String input()  { return value("input").orElse(""); }        // 类型化取值
    public List<String> steps() { return this.<List<String>>value("steps").orElse(List.of()); }
}

// 状态的"形状"（schema）：每个键注册一个 Channel（内含归约器）
Map<String, Channel<?>> schema = Map.of(
    "input",  Channels.base(() -> null),                    // 单值，覆盖
    "steps",  Channels.appender(ArrayList::new)             // 列表，去重追加
);
var graph = new StateGraph<MyState>(schema, MyState::new);   // AgentStateFactory 是函数式接口，lambda 直传
```

三个角色分工：**`AgentState` 子类**负责"怎么读"（类型化访问器），**SCHEMA（`Map<String, Channel<?>>`）**负责"怎么写"（每个键的归约器与默认值），**节点动作返回的 `Map<String, Object>`** 是部分更新（partial state），由引擎按 SCHEMA 合并。这与 Python 版 `Annotated[list, add_messages]` 的"注解即归约器"异曲同工，只是 Java 没有类型注解元编程，归约器必须显式列出。

## 2.2 AgentState：Map 封装 + 两个哨兵标记

`AgentState` 本身出乎意料地小（`state/AgentState.java:18`，242 行，**非泛型类**）：

```java
public class AgentState {
    private final java.util.Map<String, Object> data;          // :53
    public AgentState(Map<String, Object> initData) { this.data = new HashMap<>(initData); }  // :60
    public final java.util.Map<String, Object> data() { return unmodifiableMap(data); }       // :69
    public final <T> Optional<T> value(String key) { return ofNullable((T) data().get(key)); } // :82
}
```

真正重要的是两个**哨兵标记**（marker，按对象同一性 `==` 识别，`AgentState.java:31/47`）：

| 标记 | 语义 | 处理位置 |
|---|---|---|
| `AgentState.MARK_FOR_RESET` | 把该键**重置为通道默认值**；通道无默认值或键无通道时，**键被移除** | `Channel.update()` 顶部（`Channel.java:109-112`），先于任何归约器 |
| `AgentState.MARK_FOR_REMOVAL` | 把该键**从结果状态中移除**（update 返回 null，合并收集器丢弃） | 同上（`Channel.java:113-115`）+ 合并收集器 `toMapRemovingItemMarkedForRemoval`（`AgentState.java:96-119`，null 一并按删除处理） |

这两个标记正是 Python 版 `add_messages` 里 `RemoveMessage(id)` 的对应物——只不过 Python 用"特殊消息对象 + 归约器内判断"，Java 版把"删除/重置"提升为**状态合并层的通用协议**，任何键、任何通道都免费获得。

### 归约管线：updateState 的两步走

状态合并的唯一入口是静态方法 `AgentState.updateState(state, partialState, channels)`（`AgentState.java:173`），两步：

1. **通道归约**（`updatePartialStateFromSchema`，`AgentState.java:141-156`）：对部分更新里的每个键，查 SCHEMA 拿到 Channel，调 `channel.update(key, state.get(key), value)` 计算归约后的新值。无通道的键原样通过。
2. **合并去标记**（`AgentState.java:181-182`）：当前状态与归约后的部分更新拼接成流，经 `toMapRemovingItemMarkedForRemoval()` 收集——哨兵值（null / MARK_FOR_RESET / MARK_FOR_REMOVAL）触发删除，其余键后者覆盖（merge function 为 `(currentValue, newValue) -> newValue`，`AgentState.java:97`）。

**注意一个语义细节**：归约发生在"部分更新进入合并流之前"，且 `state.get(key)` 传给归约器的是**合并时刻的当前值**。这意味着同一超步里多个分支若各自返回同键的部分更新，每个分支的归约器看到的旧值都一样（详见 4.5 的 ParallelNode 合并顺序分析）——这是与 Python 版"通道在超步末统一 apply_writes"最大的行为差异。

## 2.3 Channel 契约：一个 update 方法走天下

`Channel<T>` 是个只有两个取值器 + 一个默认方法的接口（`state/Channel.java:74`）：

```java
public interface Channel<T> {
    Optional<Reducer<T>> getReducer();          // 归约器：BinaryOperator<T>（state/Reducer.java）
    Optional<Supplier<T>> getDefault();         // 默认值供给器

    default Object update(String key, Object oldValue, Object newValue) {   // :108
        if (isMarkedForReset(newValue))  return getDefault().orElse(() -> null).get();  // 重置
        if (isMarkedForRemoval(newValue)) return null;                                   // 删除
        T _new = (T) newValue;
        final T _old = (oldValue == null) ? getDefault().map(Supplier::get).orElse(null) : (T) oldValue;
        return getReducer().map(reducer -> reducer.apply(_old, _new)).orElse(_new);      // 归约或覆盖
    }
}
```

核心语义浓缩成一行：**有归约器用归约器，没有就覆盖**。唯一的实现是包内可见的 `BaseChannel<T>`（`Channel.java:14`），持有 `reducer` 与 `defaultProvider` 两个字段。

工厂 `Channels`（`state/Channels.java`）只提供三族构造：

| 工厂方法 | 归约器 | 典型用途 |
|---|---|---|
| `Channels.base(reducer, default)`（`:24`，另有单参重载） | 自定义 | 单值键（覆盖）、计数器、自定义合并 |
| `Channels.appender(default)`（`:8`） | `AppenderChannel.ReducerDisallowDuplicate`（按 `Objects.equals` 去重追加，`AppenderChannel.java:51-67`） | 消息流、历史记录（最常用） |
| `Channels.appenderWithDuplicate(default)`（`:12`） | `ReducerAllowDuplicate`（直接 addAll，`:73-83`） | 允许重复的日志类追加 |

对比 Python 版七个通道实现（LastValue/BinOp/Ephemeral/Topic/NamedBarrier/Delta/AnyValue），Java 版只有"覆盖 / 追加 / 自定义"三族——**通道体系的复杂度被换成了"归约器即普通函数"的表达力**：想模拟 Topic？写个 `Channels.base((old, v) -> { list.add(v); return list; })`。Ephemeral 语义则由每步覆盖整个键来表达。

## 2.4 AppenderChannel：追加、删除与整体替换

`AppenderChannel<T>` 是唯一自带行为的通道实现（`state/AppenderChannel.java:19`），对标 Python 的 `add_messages`。它的 `update()`（`:211-253`）在通用协议之上增加了三种"更新形状"：

1. **`ReplaceAllWith`**（record，`:37-45`）：新值是 `ReplaceAllWith.of(...)` 时**整体替换**列表——"清空重来"的逃生门，对标 Python `add_messages` 里的 RemoveMessage 全删后重写。
2. **`RemoveIdentifier`**（函数式接口，`:26-35`）：新值是 `RemoveIdentifier` 时按其 `compareTo(element, index)` 从旧列表删除匹配元素（`:227-229`）——这是"按 id 删消息"的 Java 形态，`RemoveByHash`（`state/RemoveByHash.java`）是现成的按哈希删除实现。
3. **普通值/数组/列表**（`:230-247`）：归一化为列表后走 `evaluateRemoval`（先剔除其中的删除标记，`:178-192`）再交给通用归约（去重追加或直接追加）。

两个工程细节值得记住：

- **旧列表会被原地修改**：`ReducerDisallowDuplicate.apply` 直接 `left.add(...)`（`AppenderChannel.java:64`），`left` 就是状态里那个 List 实例。渠道注释和 `UnsupportedOperationException` 捕获（`:249-252`）都提示：**默认值必须供给可变 List**（`Channels.appender(ArrayList::new)`），传 `List.of()` 会直接抛异常。
- **空列表追加 = 无操作**（`:239-241` 返回旧值）——与 Python `add_messages` 对空更新的宽容一致。

## 2.5 MessagesState：消息状态的标准预制件

core 的 `prebuilt/MessagesState<T>`（`prebuilt/MessagesState.java:17`）预声明了 `"messages"` 键 + AppenderChannel 的 SCHEMA（`:21-23`），并提供 `messages()` / `lastMessage()` / `lastMinus(n)` 三个访问器（`:41/51/64`）。注意它是**泛型类**：消息类型 `T` 由集成线指定——LangChain4j 线直接用 `dev.langchain4j.data.message.ChatMessage`，Spring AI 线用 `org.springframework.ai.chat.messages.Message`，**core 不定义自己的消息模型，零转换**（这是与 Python 版 `langchain_core.messages` 体系的根本差异，详见 9.2）。

配套的 `MessagesStateGraph<T>`（`prebuilt/MessagesStateGraph.java`）是预装了这套 SCHEMA 的 `StateGraph` 快捷构造。

**SCHEMA 如何进入子图/继承场景**：子类想加自己的键时，声明自己的 SCHEMA 并用 `Channels.base(...)` 覆盖式追加（例如 langchain4j 的 `AgentExecutor.State` 用静态块合并父 SCHEMA 与新键，`langchain4j/langchain4j-agent/src/main/java/org/bsc/langgraph4j/agentexecutor/AgentExecutor.java:48-52`）。

## 2.6 并发冲突：没有报错，但有秩序

把 Python 版最著名的"`InvalidUpdateError`"场景拿到 Java 版跑一遍，行为完全不同。设两个节点并行（多目标边）同时返回 `"answer"` 键：

- **Python 版**：LastValue 通道在 `apply_writes` 阶段检测到同一超步两个任务写同一单值键，抛 `InvalidUpdateError: Can receive only one value per step`。
- **LangGraph4j 版**：`ParallelNode` 把各分支结果**按序**合并（`internal/node/ParallelNode.java:133-140` 的 `reduce(state.data(), (result, actionResult) -> AgentState.updateState(result, actionResult, channels))`），单值键**后来者覆盖**，Appender 键去重追加。**没有任何报错**。

秩序从哪来？三条防线：

1. **归约器**：把键声明成 Appender/自定义归约，写冲突就变成有定义的合并；
2. **命名隔离**：约定各分支写各自的键（supervisor 模式里每个 worker 写自己的结果键）；
3. **代码评审**：框架不再兜底，这是"低层可控"的代价。

另一处容易踩的坑：并行分支的归约发生在**合并时刻**（分支全部完成后），每个分支的部分更新基于**同一个原始状态**做 `channel.update`——所以"分支 A 写 5、分支 B 写 7"在 Appender 键上的结果是 `[5, 7]`（各归约一次、顺序合并），而不是 Python 通道语义下"基于 A 结果再归约 B"。**自依赖的归约器（如计数器）在并行分支上会丢更新**，需要用外部原子结构或改用串行结构。

## 2.7 本章小结

- `AgentState` 是 `Map<String, Object>` 的薄封装（非泛型），子类加类型化访问器；SCHEMA（`Map<String, Channel<?>>`）显式声明每个键的归约器与默认值。
- 状态合并唯一入口 `AgentState.updateState()`：先通道归约、再合并；`MARK_FOR_RESET` / `MARK_FOR_REMOVAL` 两个哨兵是全框架通用的删除/重置协议。
- `Channel` = getReducer + getDefault + update 三件套；工厂只有 `base` / `appender`（去重）/ `appenderWithDuplicate` 三族；`AppenderChannel` 支持 `RemoveIdentifier`（按条件删）与 `ReplaceAllWith`（整体替换），对标 `add_messages`。
- `MessagesState<T>` 预置 `messages` 键；消息类型由集成线指定（LangChain4j `ChatMessage` / Spring AI `Message`），core 零消息模型。
- 与 Python 版最大的行为差异：**并发写单值键不报错**，合并规则是"按序覆盖/去重追加"；自依赖归约器在并行分支上有丢失风险。

---

# 三、图的构建与编译（StateGraph）

> 本章对应源码：`StateGraph.java`（构建器）、`GraphDefinition.java`（START/END 常量与 Nodes/Edges 容器）、`internal/node/Node.java`、`internal/edge/Edge.java`/`EdgeValue.java`/`EdgeCondition.java`、`internal/node/ParallelNode.java`、`CompiledGraph.java` 的 `ProcessedNodesEdgesAndConfig`（子图扁平化）。行号均为 1.9.4 实读。

## 3.1 先说白话：builder 是图纸，compile 是"质检 + 变形"

`StateGraph<State extends AgentState>`（`StateGraph.java:33`，`non-sealed` 类）就是一张图纸：往里 `addNode` / `addEdge` / `addConditionalEdges` / 挂钩子，最后 `compile()` 做两件事——**校验**（validateGraph）与**变形**（在 `CompiledGraph` 构造器里把"多目标边"折叠成并行节点、把"未编译子图"扁平化进父图）。Python 版的编译发生在 `compile()` → `attach_node/attach_edge` 翻译成 PregelNode；Java 版的"翻译"就是构造器里那 80 行变形逻辑（`CompiledGraph.java:95-176`）——**图从头到尾都是字符串 id + 动作对象的平面结构，没有运行时图对象**。

## 3.2 StateGraph 的五件套

`StateGraph` 的全部内部状态（`StateGraph.java:75-82`）：

```java
final Nodes<State> nodes = new Nodes<>();        // LinkedHashSet<Node>：id + ActionFactory
final Edges<State> edges = new Edges<>();        // LinkedList<Edge>：sourceId + targets
final NodeHooks<State> nodeHooks = new NodeHooks<>();
final EdgeHooks<State> edgeHooks = new EdgeHooks<>();
private final Map<String, Channel<?>> channels;  // SCHEMA（可为空 Map）
private final StateSerializer<State> stateSerializer;  // 必填（构造器 requireNonNull，:92）
```

四个构造重载（`:89-121`）最终都归一到 `Map<String, Channel<?>> channels + StateSerializer<State>`——**序列化器是必选项**，因为状态克隆（第四章）和 checkpoint 都依赖它；默认走 `ObjectStreamStateSerializer`（JDK 序列化）。START/END 常量定义在 `GraphDefinition` 接口上（`GraphDefinition.java:24-25`）：`"__START__"` / `"__END__"`。

## 3.3 构建 API 的语义细节

### addNode 的四种重载

| 重载 | 位置 | 语义 |
|---|---|---|
| `addNode(id, AsyncNodeAction)` | `:234` | 标准节点：`(State) → CompletableFuture<Map>`，返回部分更新 |
| `addNode(id, AsyncNodeActionWithConfig)` | `:245` | 带配置版：动作工厂 `(CompileConfig) → action`（`:211`），可按编译配置决定动作 |
| `addNode(id, AsyncCommandAction, mappings)` | `:258-279` | **Command 节点糖**：注册一个返回空 Map 的空节点 + 一条条件边（条件动作即该 CommandAction）。等价于"节点输出就是路由决定"的模式 |
| `addNode(id, CompiledGraph)` / `addNode(id, StateGraph)` | `:290` / `:315` | **子图即节点**（两种形态见 3.5）；旧名 `addSubgraph` 已 `@Deprecated(forRemoval)`（`:342/358`） |

节点 id 校验（`internal/node/Node.java:72-84`）：不能是 END、不能为空、**不能以 `__` 开头**（私有前缀，`:23`）。

### addEdge：同源多目标 = 并行声明

`addEdge(source, target)`（`:369-387`）遇到同 source 的重复边**不是报错，而是把 target 追加进同一 Edge 的 targets 列表**（`:376-381`）。所以"fan-out"的写法就是连续两次 `addEdge("A", "B"); addEdge("A", "C")`——编译期这条边会被折叠成 ParallelNode（见 3.5）。`Edge.isParallel()` 即 `targets.size() > 1`（`internal/edge/Edge.java:32`）。

### addConditionalEdges：条件 + 映射 → Command

`addConditionalEdges(source, condition, mappings)`（`:397`，`AsyncEdgeAction` 版本 `:424` 只是适配）三要素：

- **condition**：`AsyncCommandAction<State>`——`(State, RunnableConfig) → CompletableFuture<Command>`，返回的 `Command.gotoNode()` 是**映射键**（不是节点 id）；
- **mappings**：`Map<映射键, 目标节点id 或 END>`；
- EdgeValue 的双形态（`internal/edge/EdgeValue.java:15`）：`id` 非空 = 普通边，`value` 非空 = 条件边（`EdgeCondition` 持有 action + mappings）。

执行期的解析在 `CompiledGraph.nextNodeId(EdgeValue, ...)`（`CompiledGraph.java:291-327`）：普通边直接返回目标；条件边执行动作、`mappings.get(command.gotoNode())` 找不到映射键就抛 `RunErrors.missingNodeInEdgeMapping`（`:318-320`）——**映射必须穷举**，这点比 Python 版（未命中即走 END？不，Python 版也会报错）更严格：**条件边动作返回的 Command.update() 会被合并进状态**（`:322-324`），"路由 + 顺手改状态"一条边搞定。

### Command：控制流的统一词汇

`action/Command.java:24` 是个双字段 record：`gotoNode`（下一节点 id，可空）+ `update`（状态更新，可空），构造器校验两者不能同时为 null（`:46-50`）。三个消费场景：条件边动作（映射键）、`addNode(id, AsyncCommandAction, mappings)` 糖、工具回调（`ToolResponseBuilder` 让 LLM 工具返回 Command 控制路由，第八章）。`withMergedUpdate`（`:74/108`）支持合并两份更新（可带自定义冲突合并函数）。

## 3.4 validateGraph：全部校验规则一张表

`compile()` 第一件事就是 `validateGraph()`（`StateGraph.java:428-444`）：遍历节点 validate → **必须有 START 出边**（`:433-434` `Errors.missingEntryPoint`）→ 遍历边 validate → 钩子引用的 nodeId 必须存在。全部错误信息集中在 `Errors` 枚举（`:38-56`），写作时共 16 条，按主题归类：

| 主题 | 错误 | 触发条件 |
|---|---|---|
| id 合法性 | invalidNodeIdentifier / duplicateNodeError | 用 END 当节点名 / 重复节点（`:212/220`） |
| 边引用 | missingNodeReferencedByEdge / missingNodeInEdgeMapping / invalidEdgeTarget | 边的 source/target/mapping 指向不存在的节点（`Edge.java:63-104`） |
| 并行边 | duplicateEdgeTargetError / unsupportedConditionalEdgeOnParallelNode / illegalMultipleTargetsOnParallelNode | 多目标边重复目标 / **并行分支上不允许条件边** / fan-out 后多目标（`Edge.java:70-81`，`CompiledGraph.java:147-158`） |
| 入口出口 | missingEntryPoint / entryPointNotExist / finishPointNotExist | 无 START 边等 |
| 钩子 | validationError | 钩子声明绑定到不存在的 nodeId（`NodeHooks.java:28-37`） |

特别注意**并行分支上不允许条件边**（`CompiledGraph.java:149-156`）：fan-out 的每个分支必须是普通节点，分支内部想路由请用子图。

## 3.5 compile()：两步变形

`compile(config)` = validateGraph + `new CompiledGraph<>(this, config)`（`StateGraph.java:454-460`）。变形都在 `CompiledGraph` 构造器（`:95-176`）：

### 第一步：多目标边 → ParallelNode（`:127-175`）

对每个 `targets.size() > 1` 的边：

1. 找出各 target 对应的边（fan-out 后每个分支节点还有一条自己的出边指向 join 点）；
2. 校验：分支不允许条件边（`:149-156`）、join 点必须唯一（`:157`）；
3. **把各分支节点从图里"抽走"**，打包成 `ParallelNode`（id 形如 `__PARALLEL__(A)`，`ParallelNode.java:24`），原边改为 `source → __PARALLEL__(A) → join点`（`:165-171`）。

即：**声明式的 fan-out/join 被编译成一个"内部并发、外部单节点"的复合节点**。分支并发度由 `RunnableConfig.addParallelNodeExecutor(nodeId, executor)` 控制（`RunnableConfig.java:155`，默认在同一线程上下文里依次 join——见 7.5）。

### 第二步：未编译子图扁平化（ProcessedNodesEdgesAndConfig.process，`:1013-1179`）

`addNode(id, StateGraph)` 加的子图在父图编译时被**拍平进父图**：

- 子图每个节点的 id 被重写为 `父节点id-子节点id`（`SubGraphNode.PREFIX_FORMAT = "%s-%s"`，`SubGraphNode.java:11`，如 `worker-search`）；
- 父图中指向子图节点的边改接子图的真实 START 目标（`:1087-1096`）；子图的 END 出边改接父图原出边目标（`:1119-1149`）；
- START/END 出边若是**条件边**，插入一个 noop 空节点做桥（`:1049-1072` / `:1128-1139`）；
- `interruptsBefore` 里如果写的是子图节点 id，会被**提升**为子图第一个真实节点的 id（`:1075-1079`）；
- 子图不允许 START 并行分支、不允许 interruptAfter（`:1035-1037` / `:1109-1117`）。

而 `addNode(id, CompiledGraph)` 加的**已编译子图不拍平**：运行期作为普通节点，动作是 `SubCompiledGraphNodeAction`（见 4.6）。**两种子图形态的取舍**：拍平式共享状态与 checkpoint 上下文（无额外线程 id、父 saver 直存）；编译式有独立 saver/线程 id 命名空间、可独立运行，但需要父图有 checkpointSaver（`action/SubCompiledGraphNodeAction.java:77-80`）。

## 3.6 钩子体系：WrapCall / BeforeCall / AfterCall

LangGraph4j 没有 Python 版的中间件/动态断点体系，但有自己的一套**节点/边钩子**（`StateGraph.java:151-208`，全局或按 nodeId 绑定）：

| 钩子 | 签名要点 | 执行顺序 | 典型用途 |
|---|---|---|---|
| `NodeHook.WrapCall` | `(nodeId, state, config, action) → CompletableFuture<Map>` | **包装整个节点动作**（FIFO 责任链，`NodeHooks.java:111-127` 的 reduce 折叠 WrapCallChainLink） | 重试（RetryPolicy）、日志、tracing |
| `NodeHook.BeforeCall` | `(nodeId, state, config) → State` | 节点动作前（LIFO 链） | 入参改写、注入上下文 |
| `NodeHook.AfterCall` | `(nodeId, state, config, partial) → Map` | 节点动作后（LIFO 链） | 出参加工、清理（注意：流式节点会推迟到生成器耗尽后调用，`NodeHooks.java:196-201`，FIX #336） |
| `EdgeHook.WrapCall/Before/After` | 同上，作用于条件边动作 | — | 路由拦截 |

`RetryPolicy`（`hook/RetryPolicy.java:26`）就是第一个官方 WrapCall 钩子：`retryPolicy.asHook()` 挂到节点上即可（`:62`）。默认参数（`:28-30`）：最多 3 次、起始延迟 500ms、退避因子 2.0、上限 128s、带抖动；`retryOn(...)` 必须配置（`:196-199`）否则 build 抛异常。实现上用 `CompletableFuture.delayedExecutor` 做退避等待（`:97-98`）而非 sleep——注释明说这是 ForkJoinPool 可补偿的受管阻塞（`:96`）。

## 3.7 图可视化：PlantUML / Mermaid / JSON-DSL

`CompiledGraph.getGraph(...)` 系列方法产出 `GraphRepresentation`（`GraphRepresentation.java:12`），类型枚举只有两种：`PLANTUML`（`diagram/PlantUMLGenerator.java`）与 `MERMAID`（`diagram/MermaidGenerator.java:14`），两者都继承 `DiagramGenerator`（`DiagramGenerator.java`，支持 `excludedNodes` 与条件边打印选项）。生成器直接遍历 `GraphDefinition` 的 nodes/edges（`reduce()` 函数式出口，`StateGraph.java:484` / `CompiledGraph.java:559`）。

第四种表示是 **JSON-DSL**（`langgraph4j-dsl` 模块）：把图序列化成 JSON 描述、也能从 JSON 反解成图——Studio 前端画图消费的就是它（`studio/base` 的 `Instance.toInitGraphData()` 用 `JsonDslGenerator`，`studio/base/src/main/java/org/bsc/langgraph4j/studio/LangGraphStudioServer.java:115-120`）。

## 3.8 本章小结

- `StateGraph` 五件套：nodes / edges / nodeHooks / edgeHooks / channels+serializer（序列化器必填）。
- addNode 四重载（含 Command 节点糖）；addEdge 同源多次调用 = 声明 fan-out；addConditionalEdges = 动作 + 穷举映射，**条件边可顺手更新状态**；`Command(gotoNode, update)` 是统一控制流词汇。
- compile() 两步变形：多目标边折叠成 `__PARALLEL__(x)` 复合节点（分支禁条件边）；未编译子图拍平进父图（id 前缀 `父-子`、noop 桥接、interrupt 提升）；已编译子图保持独立运行时形态。
- 钩子三件套（Wrap/Before/AfterCall，节点与边各一组）+ `RetryPolicy` 官方重试钩子（1.9.0-beta5）。
- 校验在编译期一次做完（16 条 Errors），运行期引擎只防 `recursionLimit` 与缺失节点/边两类错误。

---
# 四、执行引擎：Emitter 事件循环（CompiledGraph）

> 本章对应源码：`CompiledGraph.java`（1183 行，含内部类 `Emitter`）、`RunnableConfig.java`、core 的地基 `org.bsc.async:async-generator`。行号均为 1.9.4 实读。

## 4.1 先说白话：一次 stream() 的一生

```java
var app = graph.compile(CompileConfig.builder().checkpointSaver(new MemorySaver()).build());
var config = RunnableConfig.builder().threadId("t1").build();
app.stream(GraphInput.args(Map.of("input", "hi")), config)
   .forEach(output -> System.out.println(output.node()));   // 逐节点吐 NodeOutput
```

`stream()` 的返回值是 `AsyncGenerator.Cancellable<NodeOutput<State>>`（`CompiledGraph.java:430`）——作者自研的 **Java 生成器库**（`org.bsc.async:async-generator` 5.1.0，core 唯一的重量级依赖）。它是 Python `yield` 生成器的 Java 等价物：**拉模型**（消费者 `next()` 驱动生产者），带三种终结信号（`Data.of(value)` 继续 / `Data.done(result)` 正常结束 / `Data.error(e)` 异常）、可取消（Cancellable）、可 reduce。引擎的整个执行循环就住在生成器的"生产者协程"里：消费者每要一个输出，循环就执行一步。

**一次运行的一生**：初始状态合并 → 找 START 出边 → 执行节点 → 合并部分状态 → 算下一条边（条件边执行路由动作）→ 落 checkpoint → 吐出一个 `NodeOutput` → 下一轮……直到下一步是 END（吐出 END 输出后正常收尾）或触发中断（吐出 `InterruptionMetadata` 后终止）。**没有超步、没有版本号、没有 PregelNode**——Python 版 `pregel/main.py` 四千行的 BSP 引擎，在这里被压缩成一个 150 行的 for 循环。

## 4.2 Emitter 主循环逐段走读

`Emitter` 是 `CompiledGraph` 的内部类，实现 `Consumer<Dispatcher<Output>>`——即"生成器的生产者函数"。`accept()`（`CompiledGraph.java:844-997`）就是主循环，骨架如下（行号为实读）：

```java
public void accept(Dispatcher<Output> $1) {                    // :844
    final var maxIterations = config.recursionLimit()
                              .orElse(compileConfig.recursionLimit());   // :848
    var iteration = 0;
    for (;;) {
        // ① 防失控：超过 recursionLimit（默认 25）→ 吐 error 终止        // :852-857
        if (++iteration > maxIterations) { ... Data.error(...); break; }

        // ② 终点判定：currentNodeId 与 nextNodeId 都为 null → done       // :860-866
        if (context.nextNodeId() == null && context.currentNodeId() == null) {
            $1.dispatchAsync(releaseThread()                            // 正常结束 → releaseThread
                .map(Data::done).orElseGet(() -> Data.done(context.currentState())));
            break;
        }

        // ③ START：解析入口边 → 吐 START 输出 → 前进                     // :868-884
        if (START.equals(context.currentNodeId())) {
            var nextNodeCommand = entryPoint(context.currentState(), config);
            ... var cp = addCheckpoint(config, START, ...);             // 入口也落 checkpoint
            $1.dispatchAsync(Data.of(output));
            continue;
        }

        // ④ END：吐 END 输出，清空游标（下一轮命中 ②）                    // :886-891
        if (END.equals(context.nextNodeId())) { context.reset(); ...; continue; }

        // ⑤ 恢复后 + interruptBeforeEdge 的特殊接续                      // :893-902

        // ⑥ 声明式中断：interruptsAfter / interruptsBefore 命中           // :905-927
        //    → 构造 InterruptionMetadata → saver.registerInterruption
        //    → dispatchAsync(Data.done(interruptionMetadata)) → break    // 循环终止！

        // ⑦ 前进：currentNodeId = nextNodeId，刷新元数据                  // :929-931
        context.setCurrentNodeId(context.nextNodeId());
        config = updateRunnableConfigMetadata(config, context.currentNodeId(), $1);

        // ⑧ 取动作、克隆状态、执行                                       // :936-944
        final var action = ofNullable(nodes.get(context.currentNodeId()))
                .orElseThrow(() -> RunErrors.missingNode.exception(...));
        final var clonedState = cloneState(context.currentState(), config);
        final var actionResult = applyAction($1, action, context.currentNodeId(), clonedState, config);
        $1.dispatchAsync(actionResult);                                  // 节点输出推给消费者
    }
}
```

`Context`（`:565-620`）是循环的游标四元组：`currentNodeId`（刚执行/正在执行的节点）、`nextNodeId`（下一步）、`currentState`（合并后的权威状态）、`resumeFrom`（恢复场景的一次性标记）。对照 Python 版：没有 channel_versions、没有 versions_seen、没有任务规划器——**nextNodeId 就是"图游标"**，状态就是那个 HashMap。

### applyAction：节点执行 + 三层钩子 + 生成器嵌入（`:780-824`）

单个节点的执行被包装成一个 CompletableFuture 链：

1. `NodeHooks.applyActionWithHooksHandlingInterruption()`（`internal/hook/NodeHooks.java:182-208`）：先跑 **BeforeCall 钩子链**（可返回新状态）→ 检查节点动作是否实现 `InterruptibleAction`——是则调用 `interrupt(nodeId, state, config)`，返回了 `InterruptionMetadata` 就**直接以中断结果收场**（`NodeHooks.java:167-176`，这是第六章人机协同的引擎侧入口）→ 否则跑 **WrapCall 责任链**（RetryPolicy 在这里生效）→ 拿到部分状态后跑 **AfterCall 钩子链**（流式节点跳过，推迟到生成器耗尽）。
2. 回到 `applyAction`：部分状态里有 `AsyncGenerator` 值 → 走 `embedGenerator`（第七章）；否则直接合并状态（`:797-799`）。
3. 算下一步：`interruptBeforeEdge` 配置下且当前节点在 interruptsAfter 集合里 → 下一节点置为哨兵 `__INTERRUPTED__`（`:801-803`，常量 `CompiledGraph.java:47`）；否则 `nextNodeId()` 解析出边（条件边执行路由动作并合并其状态更新，`:804-807`）。
4. 吐输出：`nodeOutput()`（`:828-834`）先 `addCheckpoint()`（有 saver 时），按 `StreamMode` 决定吐 `NodeOutput`（VALUES）还是 `StateSnapshot`（SNAPSHOTS）。

### updateRunnableConfigMetadata：运行期元数据的注入点（`:332-353`）

每个节点执行前，config 都会被重建并注入保留键（`RunnableConfig.java:207-220`）：`LG4j_NODE_ID`（当前节点）、`LG4j_GRAPH_ID`、`LG4j_GRAPH_NODE_PATH`（`a-b-c` 形式的节点路径，`GraphPath.java`）、`LG4J_CUSTOM_DISPATCHER`。这些元数据随 `NodeOutput` 一起流出（`NodeOutput.metadataSupplier`，`NodeOutput.java:37`）——**流出的每个输出都自带"我在哪个节点、哪条路径"的上下文**，Studio 和 AG-UI 的步进渲染就靠它。

## 4.3 状态克隆：每次执行前的深拷贝

循环在执行每个节点前都 `cloneState()`（`:939`），默认实现是**序列化往返**（`serializer/Serializer.java:13-18` 的 `cloneObject` = `readDataFromString(writeDataAsString(object))`）；checkpoint 落库前同样克隆（`CompiledGraph.java:394`）。这一刀保证了：

- 节点拿到的是**隔离副本**，节点内修改状态 Map 不会污染引擎的权威状态；
- 流出的 `NodeOutput.state()` 是**不可变快照**，消费者持有它不会随后续执行变化。

代价也直白：每个节点执行都伴随一次完整状态的序列化/反序列化。1.9 提供了两个减压阀：

- `RunnableConfig.disableCloneState()`（`RunnableConfig.java:168`，`@since 1.9`）——大状态 + 可信节点场景关掉克隆（文档明言：状态不再保证不可变）；
- `StateSerializer.declareTransientAttributes(...)`（`serializer/StateSerializer.java:29`）——声明后的大对象属性不参与序列化（1.9.0-beta1，#147）。

## 4.4 并行执行：ParallelNode 内部的一天

第三章讲过编译期把 fan-out 折叠成 `ParallelNode`；它的运行期行为（`internal/node/ParallelNode.java`）：

1. 每个分支动作先各自执行（`evalNodeActionSync`，`:48`）；分支返回的部分状态里若也有 `AsyncGenerator`，同样被消费合并（`evalGenerator`，`:33-45`）；
2. 并发度：`RunnableConfig.addParallelNodeExecutor(nodeId, executor)` 注册了执行器时，分支跑在 `supplyAsync(..., executor)` 上（`:66-72`）；**否则各分支在同一循环线程里顺序 join**——并行的"并"只在你给了执行器时才真的发生；
3. 汇聚用 `allOfFailFast`（`:96-119`）：任一分支异常，管理 Future 立即异常完成（fail-fast）；
4. 全部分支完成后，按分支顺序 `reduce(state.data(), (result, actionResult) -> AgentState.updateState(result, actionResult, channels))` 合并（`:133-140`）——回到 2.6 的结论：**合并是顺序的，单值键后者覆盖，Appender 键去重追加**。
5. 分支的流式生成器结果中若携带 `InterruptionMetadata` 直接抛 `UnsupportedOperationException`（`:90-92`）——**并行分支内不允许中断**。

## 4.5 子图的执行路径：两种形态、一条规则

**拍平式**（`addNode(id, StateGraph)`）：编译期已并入父图，运行期就是普通节点——共享父图的状态、checkpoint、线程 id。

**编译式**（`addNode(id, CompiledGraph)`）：运行期动作是 `SubCompiledGraphNodeAction`（`action/SubCompiledGraphNodeAction.java:60-109`）：

- 把父图状态原样作为子图输入（子图与父图**同状态类型**是硬要求）；
- `config` 上追加 `GRAPH_PATH`/`GRAPH_NODE_PATH`（`:66-67`）形成层级路径；
- 子图有自己的 saver 时：要求父图也有（`:77-80`），子图线程 id 命名为 `"父threadId_subgraph_<nodeId>"`（`:82-86`），并把子图 saver 注册进父 saver 托管（`putSubGraphSaver`，`:88`；1.9.0-beta1，#407）——父线程释放时级联释放子线程（`checkpoint/AbstractCheckpointSaver.java:105-117`）；
- 子图以 `streamSnapshots` 跑（`:100`），输出包装成 `SubGraphOutput`（携带子图路径），而**动作的返回值是 `Map.of("subgraph_<id>_<uuid>", generator)`**（`:103`）——即把自己伪装成一个"流式节点"，借 `embedGenerator` 机制把子图的所有输出汇入父图输出流。这就是 1.8 的子图恢复能力（#326）的载体：`SUBGRAPH_RESUME_UPDATE_DATA` 元数据 + `resumeSubGraphId` 标记让恢复请求能穿透到子图（`CompiledGraph.java:638-654`）。

## 4.6 恢复语义：GraphResume 与节点重放

`GraphInput` 是 sealed 接口（`GraphInput.java:7`），两个 permit：`GraphArgs`（新启动）/ `GraphResume`（恢复）。`resume()` 三个工厂（`:11-13`）：空恢复、携带状态更新恢复、**直接指定 Checkpoint 恢复**（1.9.3，#483）。

`Emitter` 构造时识别 GraphResume（`:629-668`）：

1. `startCheckpoint()`（`:681-692`）：显式给了 checkpoint 就用它；否则从 saver 按 threadId/checkPointId 取——**没有配置 saver 的恢复请求直接抛 IllegalStateException**；
2. `Context(Checkpoint)` 构造（`:578-583`）：`currentNodeId = null`、`nextNodeId = cp.nextNodeId()`、`resumeFrom = cp.nodeId()`、状态 = checkpoint 状态；
3. 携带的 resume 数据被**合并进 checkpoint 状态**（`FIX ISSUE #302` 注释，`:660-663`）；
4. 主循环继续：因为 `currentNodeId == null`，`shouldInterruptBefore/After` 的"上一节点为空"分支直接放行（`:374-388` 的 FIX RESUME ERROR）——**游标落在 checkpoint 记录的 nextNodeId 上，从那个节点重新执行**。

**这就是 LangGraph4j 的"节点重放"语义**：中断的节点在下一次恢复时**从头再执行一遍**（没有任何"从中断行继续"的机制）。副作用节点的幂等性是用户的责任——与 Python 版 `interrupt()` 的重放语义一致，但没有 Python 版 `Resume` 草稿簿机制（Python 会记录"上次 interrupt 返回了什么值"，靠它跳过已执行片段；Java 版靠**用户自己在状态里记录进度**，比如 AgentEx 审批流用 `approval_result` 键判断）。

另外 `updateState(config, values, asNode)`（`CompiledGraph.java:246-276`）提供了"改写历史"能力：把 values 合并进当前 checkpoint、可选地以某个节点身份重新计算下一步（time-travel 的原语，配合 `getStateHistory()` 使用）。

## 4.7 本章小结

- 引擎 = `AsyncGenerator` 拉模型 + `Emitter.accept()` 单线程 for 循环；游标就是 `(currentNodeId, nextNodeId, state)` 三元组，无 Pregel。
- 每节点执行：BeforeCall 钩子 → 中断检查（InterruptibleAction）→ WrapCall 链（重试）→ 动作 → AfterCall 链 → 合并状态 → 解析出边 → 落 checkpoint → 吐输出。
- 状态克隆默认靠序列化往返（隔离 + 快照）；1.9 提供 `disableCloneState` 与 transient 属性减压。
- 并行 = `__PARALLEL__(x)` 复合节点内部并发（需显式给执行器才真并行），fail-fast 汇聚、顺序合并、禁止分支中断。
- 子图两形态：拍平（共享一切）/ 编译式（独立 saver + 线程 id + SubGraphOutput 汇流）。
- 恢复 = 从 checkpoint 的 nextNodeId 重放节点；副作用幂等靠用户；`updateState` 支持改写历史。

---

# 五、持久化：Checkpoint 体系

> 本章对应源码：`checkpoint/Checkpoint.java`、`checkpoint/BaseCheckpointSaver.java`、`checkpoint/AbstractCheckpointSaver.java`、`checkpoint/MemorySaver.java`、`checkpoint/FileSystemSaver.java`、`serializer/` 体系，以及八个 *-saver 模块。行号均为 1.9.4 实读。

## 5.1 先说白话：为什么每个节点都要"存档"？

智能体一次运行可能跑几分钟到几小时，中间会崩、要人审、要重启。LangGraph4j 的答案是**每执行完一个节点就存一份完整快照**（Checkpoint），存档里带着"下一步是谁"（nextNodeId）——于是"恢复"永远等价于"从最近一份存档的下一步重新开始"。中断、人审、time-travel、跨进程恢复，全部构建在这同一条存档上，没有第二套暂停恢复系统——与 Python 版哲学完全一致，但存档结构精简得多。

## 5.2 Checkpoint：四元组 record

```java
public record Checkpoint(String id, Map<String, Object> state,
                         String nodeId, String nextNodeId) {      // checkpoint/Checkpoint.java:20
}
```

| 字段 | 含义 | Python 版对应物 |
|---|---|---|
| `id` | UUID（`Builder.id` 默认 `UUID.randomUUID()`，`:73`） | checkpoint_id |
| `state` | **完整的扁平状态 Map**（已克隆） | channel_values |
| `nodeId` | 产生这份存档的节点 | metadata.source/step |
| `nextNodeId` | **下一步要执行的节点**——恢复的"指针" | 无显式对应（Python 靠 versions_seen 重算任务） |

对比 Python 版 Checkpoint（v/id/ts/channel_values/channel_versions/versions_seen/channel_hashes 六件套），Java 版**砍掉了全部版本追踪结构**：没有 channel_versions（通道版本号）、没有 versions_seen（任务_seen 集合）、没有 pending_writes。原因在第四章已经清楚了：顺序事件循环不需要"唤醒判定"——下一步是谁，存在 nextNodeId 里就行。**这是两版实现分歧的第一因**：BSP 并发引擎需要版本号解决"哪个任务看过旧状态"，顺序引擎只需要一个游标。

`copyOf`（`:59-65`）生成新 id 的副本（updateState 用的就是它）；`updateState(values, channels, nextNodeId)`（`:116-137`）合并部分更新并显式指定下一步（人工改写历史时用）。

## 5.3 BaseCheckpointSaver 契约：一个模板 + 两层扩展

接口方法全景（`checkpoint/BaseCheckpointSaver.java`）：

| 方法 | 语义 | 备注 |
|---|---|---|
| `list(config)` | 该线程的 checkpoint 链（新→旧） | `:55` |
| `get(config)` | 当前 checkpoint（按 `config.checkPointId` 精确定位或取最近） | `:57` |
| `put(config, checkpoint)` | 落档，返回**更新了 checkPointId 的 config** | `:59` |
| `release(config[, message])` | **释放线程**：把活跃 checkpoint 链归档为 `Tag`（1.9.0-beta4） | `:61/74` |
| `releaseOnError(config, e)` | 异常终止时的释放（携带异常信息） | `:85` |
| `registerInterruption(config, metadata)` | 登记中断元数据（**唯一异步方法**，返回 CompletableFuture） | `:95-96` |
| `tag(config, version)` / `lastTag(config)` | 读取归档的 Tag（按版本） | `:98-102` |
| `putSubGraphSaver(...)` / `listSubGraphSaver(...)` | 子图 saver 托管（1.9.0-beta1，#407） | `:115/123` |

线程的默认命名：`RunnableConfig.threadId` 缺省时用 `$default`（`THREAD_ID_DEFAULT`，`:15`）——**不传 threadId 的所有运行共享一个线程空间**，这是生产环境最常见的坑之一。

`AbstractCheckpointSaver`（`:15`）是模板方法基类：`ReentrantLock` 串行化所有访问（`:16`），子类只需实现 5 个钩子：`loadCheckpoints / insertedCheckpoint / updatedCheckpoint / releaseCheckpoints / releaseCheckpointsOnError`（`:19-27`）。`put()` 的语义细节（`:75-98`）：config 带 checkPointId 时是**替换**该位置（改写历史），否则 push 到链头并返回新 config；`release()` 级联释放所有注册过的子图 saver（`:105-117`）。

## 5.4 MemorySaver 与线程生命周期：releaseThread

`MemorySaver`（`:15`）两个容器（`:17-18`）：

- `_checkpointsByThread: Map<String, LinkedList<Checkpoint>>`——活跃线程的存档链（头 = 最新）；
- `_tagsByThread: Map<String, TreeMap<Integer, Tag>>`——已释放线程的归档（版本号递增，`releaseCheckpoints` 里 `++lastThreadVersion`，`:44-50`）。

**1.9 引入的关键语义**：`CompileConfig.releaseThread` **默认 true**（`CompileConfig.java:167`）。图**正常跑到 END** 时，Emitter 调 `releaseThread()`（`CompiledGraph.java:836-841`）把整条 checkpoint 链从活跃区搬进 Tag 归档。三个后果：

1. **正常结束的运行不可再 resume**（`saver.get()` 返回空，resume 抛 "Resume request without a valid checkpoint"）——设计如此：跑完的对话没有恢复价值，还省存储；
2. **中断/异常的运行保留全部历史**（循环以 done(InterruptionMetadata) 终止时**不走 release**），等待恢复；
3. 想要"跑完还能 getStateHistory/time-travel"的行为，显式 `CompileConfig.builder().releaseThread(false)`（how-tos 的 time-travel 示例正是这么配的）。

`Tag`（`BaseCheckpointSaver.java:17-46`）= threadId + version + 整条 checkpoint 链，配合 `tag(config, version)` 可回看历史版本——这是 Python 版没有的"会话归档"概念。

## 5.5 状态序列化器：三套实现

`StateSerializer<State>`（`serializer/StateSerializer.java`）是抽象类，绑定 `AgentStateFactory`（怎么从 Map 造状态对象），核心职责是 `cloneObject`（状态克隆）与 checkpoint 的持久化编解码。三条技术路线（`serializer/` 目录）：

| 实现 | 机制 | 适用 |
|---|---|---|
| `ObjectStreamStateSerializer`（`serializer/std/`，默认） | JDK 序列化 + `SerializerMapper` 注册特化编解码器 | 状态里全是 Serializable；零额外依赖 |
| `JacksonStateSerializer` / `GsonStateSerializer`（`serializer/plain_text/`） | JSON 文本 + 自定义 (de)serializer 注册 | 状态要跨进程/落库成 JSON；jackson/gson 为 provided，需用户自行引入 |
| `PlainTextStateSerializer` | 纯文本抽象基类（1.7.5 起强制 `writeDataAsString/readDataFromString`） | saver 存 JSON 字符串的基础 |

集成线各自提供配套序列化器（第九章）：`LC4jStateSerializer`（JDK 流版，注册 ChatMessage/ToolExecutionRequest 映射）与 `LC4jJacksonStateSerializer`（按 ChatMessageType 注册 handler）；Spring AI 侧对称。`transientAttributeSet`（`StateSerializer.java:20`，1.9.0-beta1）让大对象属性免于序列化。

**工程提醒**：JDK 序列化路线要求状态里每个值都 `Serializable`；LLM 的 `ChatResponse`/`AiMessage` 等类型并不都是——所以用 LLM 消息做状态时**基本都要选集成线的专用序列化器**，并把消息类逐个注册进 mapper。

## 5.6 数据库 saver 全家桶（V1 → V2）

八个 saver 模块共享同一套抽象与命名，全部实现 `AbstractCheckpointSaver`，SQL 类模块共用 `SqlResource.Commands` 机制（从 classpath 的 `db/vX.Y__commands.sql`（DML）与 `db/migration/vX.Y__init.sql`（DDL）按标签加载）。**V2 架构**（1.9.0-beta3 起陆续落地，各模块有 SAVER_V2.md 设计文档）：

| 表 | V1 | V2 |
|---|---|---|
| `LG4JThread` | thread_id, thread_name, is_released（布尔标记释放） | + **parent_thread_id**（子图线程）、**is_interrupted**、**message**（中断/错误信息）；释放后的线程**移出**此表 |
| `LG4JThreadTag` | 无 | **新增**：released_version、is_error、message——释放归档表（对应 core 的 Tag） |
| `LG4JCheckpoint` | checkpoint_id, parent_checkpoint_id, thread_id, node_id, next_node_id, state_data, state_content_type, saved_at | thread_id 指向 BIGINT 行号、FK 放宽（归档后仍可追溯） |

| 模块 | 类（V1 → V2） | 驱动 | state_data 列型 | 要点 |
|---|---|---|---|---|
| sqlite | `SQLiteSaver` → `SQLiteSaverV2`(+Dashboard) | org.xerial:sqlite-jdbc | TEXT(JSON) | 模块本身是 **1.9.0-beta2 新增**；Builder：url/databasePath/datasource + stateSerializer 必填；1.9.4 修复连接泄漏 |
| postgres | `PostgresSaver` → `PostgresSaverV2`(+Dashboard) | org.postgresql:postgresql | **JSONB** | checkpoint_id 用 UUID（`setObject(..., Types.OTHER)`）；V1 有 `WHERE is_released=FALSE` 部分唯一索引 |
| mysql | `MysqlSaver` → `MySQLSaverV2`(+Dashboard) | mysql-connector-j | JSON | 1.9.0-beta7 对齐 V2 |
| oracle | `OracleSaver` → `OracleSaverV2`(+Dashboard) | ojdbc11 + jackson-oson | CLOB | identity 主键；1.7 正式入仓（#223） |
| cockroachdb | `CockroachDBSaver` | 复用 postgresql 驱动 | JSONB | 仅 V1，Postgres 抽象的镜像 |
| redis | `RedisSaver` | Redisson | 无表：Hash + SortedSet | key 前缀 `langgraph4j:`；支持 TTL；Builder 可注入现成 RedissonClient |
| dynamodb | `DynamoDBSaver`(+Repository) | aws-sdk v2 | 单表 PK/SK | **大状态溢写 S3**（StorageStrategy，模仿 langchain-aws）；支持 ttlSeconds |
| hazelcast | `HazelcastSaver` | hazelcast（provided） | 分布式 IMap/CPMap | mapType 可选 CP_MAP（线性一致，企业版）；1.9.0-beta1 合入（PR #416） |

**registerInterruption 的实现差异**值得注意：MemorySaver 是空操作直接返回（`MemorySaver.java:62-64`）；SQLiteSaverV2/PostgresSaverV2 会落库置 `is_interrupted` 并存 reason——中断元数据是否跨进程持久化取决于 saver 能力。

## 5.7 FileSystemSaver

core 自带文件系统实现（`checkpoint/FileSystemSaver.java:30`）：每个线程一个文件 `thread-<threadId>.saver`（无 threadId 则 `thread-$default.saver`，类注释 `:24-27`），整条 checkpoint 链经 `Serializer<LinkedList<Checkpoint>>` 整体读写（构造器 `:41`）——实现最简单，适合原型与单机持久化；并发写同一线程文件靠父类锁。

## 5.8 本章小结

- Checkpoint 四元组（id/state/nodeId/nextNodeId）；相比 Python 版砍掉 channel_versions/versions_seen——顺序引擎只需要一个"下一步"指针。
- `BaseCheckpointSaver`：list/get/put（带 checkPointId 即改写）/ release（归档 Tag）/ tag（按版本回看）/ registerInterruption（唯一异步方法）/ 子图 saver 托管；模板基类用 ReentrantLock 串行化。
- **releaseThread 默认 true**：正常跑完即释放归档、不可 resume；要 time-travel 记得 `releaseThread(false)`。threadId 缺省共享 `$default` 线程——生产必传。
- 序列化三路线（JDK 流 / Jackson / Gson）；用 LLM 消息做状态必须用集成线专用序列化器。
- 八个数据库 saver：SQL 系（sqlite/postgres/mysql/oracle/cockroachdb）共享 LG4JThread/LG4JCheckpoint 命名与 SqlResource 机制，V2 增加 Tag 归档表 + is_interrupted/message 元数据 + Dashboard；NoSQL 系（redis/dynamodb/hazelcast）各有特色（TTL、S3 溢写、CP Map）。

---
# 六、人在环路：中断与恢复

> 本章对应源码：`CompileConfig.java`（interruptsBefore/After/interruptBeforeEdge）、`action/InterruptibleAction.java`/`InterruptionMetadata.java`、`GraphInterruptException.java`、`internal/hook/NodeHooks.java`、`CompiledGraph.java` 的 Emitter、`GraphInput.java`、core `agent/AgentEx.java`（审批预制件）。行号均为 1.9.4 实读。

## 6.1 先说白话：三种"叫停"的姿势

让人类在图执行的某个点介入（审批工具调用、补充输入、修改方向），需要三件事：**在哪停**（声明）、**停了给谁看什么**（中断载荷）、**人答复后怎么续**（恢复协议）。Python 版用 `interrupt()` 函数 + `Command(resume=...)` 一套机制；LangGraph4j 把"在哪停"拆成了**三种形态**，恢复协议则是统一的 `GraphInput.resume()`。

| 形态 | 声明方式 | 触发时机 | 典型场景 |
|---|---|---|---|
| 声明式中断 | `CompileConfig.interruptBefore/After("nodeId")` | 引擎循环检查（编译期已知） | 调试步进、固定审批点 |
| 节点级中断 | 节点动作实现 `InterruptibleAction` | 动作执行**前**，由动作自判 | 审批（运行时才知道要不要停） |
| 异常式中断 | 动作内 `throw new GraphInterruptException(config, reason)` | 动作执行中任意点 | 深层逻辑里动态叫停（1.9.0-beta4，#432） |

## 6.2 声明式中断：interruptBefore / interruptAfter

`CompileConfig` 两个集合字段（`CompileConfig.java:165-166`）+ 一个开关 `interruptBeforeEdge`（`:168`，默认 false——"中断发生在节点完成后"；设为 true 则"节点完成后、**条件边求值前**"中断，可以看到分支前的状态）。构造 `CompiledGraph` 时校验声明的节点必须存在（`CompiledGraph.java:102-111`）。

引擎侧检查在主循环两处（`CompiledGraph.java:905-927`）：

```java
if (shouldInterruptAfter(context.currentNodeId(), context.nextNodeId())) {
    final var interruptionMetadata = InterruptionMetadata.builder(context.currentNodeId(), cloneState(...))
            .reason("declared interruption after nodeId: [%s]".formatted(...)).build();
    if (compileConfig.checkpointSaver().isPresent())
        compileConfig.checkpointSaver().get().registerInterruption(config, interruptionMetadata);
    $1.dispatchAsync(AsyncGenerator.Data.done(interruptionMetadata));   // 中断即终态
    break;
}
```

三个要点：

1. **中断不是异常，是"带载荷的正常终止"**——流的最终结果就是 `InterruptionMetadata`（走 `Data.done` 而非 `Data.error`），消费者 `invokeFinal()` 拿到的最后一个 `NodeOutput` 语义上是中断（`GraphResult.Type.INTERRUPTION_METADATA`）。
2. `shouldInterruptBefore/After` 的守卫（`:374-388`）：`previousNodeId == null` 时直接放行（FIX RESUME ERROR）——**恢复后的第一轮循环不会再触发同一个中断**，否则会死循环。
3. `interruptBeforeEdge + INTERRUPT_AFTER`（哨兵 `__INTERRUPTED__`，`CompiledGraph.java:47`）组合出的"边前中断"在恢复路径上有专门接续（`:893-902`）：恢复后先补算条件边再继续。

## 6.3 节点级中断：InterruptibleAction

```java
public interface InterruptibleAction<State extends AgentState> {
    default Optional<InterruptionMetadata<State>> interrupt(String nodeId, State state) { ... }        // :26（废弃重载）
    default Optional<InterruptionMetadata<State>> interrupt(String nodeId, State state, RunnableConfig config) { ... }  // :40
}
```

实现它（可选返回 `InterruptionMetadata`），引擎在**节点动作执行前**询问（`NodeHooks.applyWrapCallHooksHandlingInterruption`，`NodeHooks.java:167-176`）：返回 present → `saver.registerInterruption()` → 以中断收场；返回 empty → 正常执行动作。旧接口 `InterruptableAction` 已 `@Deprecated(forRemoval)`（`InterruptableAction.java:11`）。

`InterruptionMetadata`（`action/InterruptionMetadata.java:21`）携带三样东西：`nodeId`（停在哪）、`state`（当时的完整状态快照，已克隆）、`metadata`（含 `reason` 与任意自定义键——审批 UI 想展示什么就放什么）。它是 `HasMetadata`，构建用 `InterruptionMetadata.builder(nodeId, state).reason(...).putMetadata(...)`（`:99/108`）。

一个巧妙的技术细节：把**同步动作** `NodeAction` 适配成异步时，`AsyncNodeAction.node_async()` 用 JDK 动态代理给返回值**补挂 InterruptibleAction 接口**（`AsyncNodeAction.java:47-58`，`AsyncNodeActionWithConfig.of` 同样在 `:84` 处理）——否则类型转换会丢失中断能力。

## 6.4 异常式中断：GraphInterruptException

动作执行途中（比如工具内部深处的某行代码）想叫停，抛 `GraphInterruptException(config, reason)`（`GraphInterruptException.java:13`，extends GraphRunnerException）。引擎在主循环的 catch 里识别它（`CompiledGraph.java:961-981`）：

```java
catch (Throwable ex) {
    final var interruptExOpt = GraphInterruptException.of(ex);      // 沿 cause 链找
    if (interruptExOpt.isEmpty()) { ...Data.error(graphRunnerEx)...; }
    else {
        final var interruptException = interruptExOpt.get();
        final var nodeId = interruptException.nodeId().orElse(context.currentNodeId());
        final var interruptMetadata = InterruptionMetadata.builder(nodeId, clonedState)
                .reason(interruptException.getMessage()).build();
        $1.dispatchAsync(AsyncGenerator.Data.done(interruptMetadata));  // 同样以中断终态收场
    }
    break;
}
```

注意：抛出点之后该节点内的代码不再执行，但**状态仍是进入节点前的克隆**（clonedState）——中断不会保留节点内已完成的部分状态更新（除非你自己先写进 checkpoint）。

## 6.5 恢复链路全走读

人在环路的标准剧本（与 how-tos 的 wait-user-input 示例一致）：

```java
// ① 首跑：停在 human_feedback 节点前（声明式）
var config = RunnableConfig.builder().threadId("Thread1").build();
graph.stream(GraphInput.args(input), config).forEach(...);          // 流以 InterruptionMetadata 收尾

// ② 查看停点状态
var snapshot = graph.getState(config);                              // StateSnapshot（checkpoint 包装）

// ③ 注入人工输入 → 得到新 config（指向新 checkpoint）
var updateConfig = graph.updateState(config, Map.of("human_feedback", userInput), null);

// ④ 恢复：注意是新的 stream 调用
graph.stream(GraphInput.resume(), updateConfig).forEach(...);       // 继续跑完
```

引擎侧（`CompiledGraph.java`）每一环：

1. `updateState()`（`:246-276`）：取当前 checkpoint → `copyOf`（新 id）→ 合并 values（通道归约生效）→ 可选 `asNode` 重算下一步 → `saver.put` 落新档 → 返回带新 checkPointId 与 nextNode 的 config；
2. `stream(GraphResume, ...)` → `Emitter` 构造（`:629-668`）：取 checkpoint（显式传入的优先，1.9.3 #483）→ 合并 resume 数据 → `Context(cp)`；
3. 主循环从 `cp.nextNodeId()` 继续执行（4.6 节）。

**与 Python 版的语义对照**：Python `interrupt(value)` 返回 `Command(resume=...)` 注入的值，靠 scratchpad 重放区分"第一次执行"与"重放"；LangGraph4j 没有这个隐式机制——**恢复 = 把 resume 数据合并进状态后从头重放该节点**，"第一次 vs 重放"的区分靠状态键自己判断（例如 6.6 的 `approval_result` 是否为空）。Python `Command(resume=...)` 的对应物是 `updateState(config, resumeData)` + `GraphInput.resume()` 的组合。

## 6.6 审批模式的落地：AgentEx 的 approval 节点

core 的 `agent/AgentEx.java` 把"工具调用审批"做成了标准图模式（LangChain4j/Spring AI 两线的 `AgentExecutorEx` 都基于它）：

- 每个注册的工具是**独立节点**，需要审批的工具额外生成 `approval_<toolName>` 节点（`AgentEx.java:298-323`）；
- `approval_<tool>` 节点的动作实现 `InterruptibleAction`：**`approval_result` 状态键为空就中断**，携带调用方提供的 InterruptionMetadata（`AgentExecutorEx.java:93` 附近）；
- 恢复时用户 `GraphInput.resume(Map.of(AgentEx.APPROVAL_RESULT, APPROVED 或 REJECTED))`——该键经归约器归一化为枚举名（`AgentEx.java:66-75`）；
- 节点重放后读到 `approval_result`：APPROVED → 路由到真实工具节点；REJECTED → 写入拒绝消息回模型（`AgentExecutorEx.java:234-274`）。

集成测试 `Issue375ITest.java:56-88` 是最小可抄样本。**这就是"节点重放 + 状态判据"模式的教科书实现**——你自己做审批流程时，抄这个骨架即可。

## 6.7 Studio 里的中断：isRunningInStudio 联动

Studio（第十章）的中断体验依赖一个运行期标记：`GraphStreamServlet` 执行时往 config 打 `STUDIO_METADATA_KEY`（`studio/base/.../LangGraphStudioServer.java:379`），节点动作可用 `config.isRunningInStudio()` 判断（`RunnableConfig.java:391-393`）。为什么需要：Studio 的 UI 会自行渲染最终消息，LLM 节点在 Studio 里**主动关闭流式**（如 spring-ai 的 `CallModelAction`，`spring-ai/spring-ai-agent/.../agent/CallModelAction.java:54-64`）；AgentEx 还会在 Studio 运行后补写空的 approval_result 让前端状态一致（`AgentEx.java:244-249`）。

## 6.8 本章小结

- 三种中断形态：声明式（interruptBefore/After + 可选 interruptBeforeEdge）、节点级（InterruptibleAction，动作前自判）、异常式（GraphInterruptException，动作中叫停，1.9.0-beta4）。
- 中断是"带载荷的正常终止"：流以 `InterruptionMetadata`（nodeId + 状态快照 + reason/自定义元数据）收尾，并 `registerInterruption` 落库（V2 saver 持久化 is_interrupted）。
- 恢复协议统一：`updateState(注入人工数据)` + `stream(GraphInput.resume())`；恢复 = 从 checkpoint 的 nextNodeId **重放节点**，"是否已被答复过"靠状态键判据（AgentEx 审批模式是标准答案）。
- 同步动作经 `node_async` 代理保留中断能力；Studio 经 `isRunningInStudio` 与节点行为联动。

---

# 七、流式输出与异步模型

> 本章对应源码：`streaming/StreamingOutput.java`、`CompiledGraph.java` 的 `embedGenerator`、`NodeHooks.java` 的流式分支、集成线的 `StreamingChatGenerator`（langchain4j 与 spring-ai 各一份）、`RunnableConfig` 执行器。行号均为 1.9.4 实读。

## 7.1 先说白话：流式是怎么"长"在图上的

Python 版的流式是一等公民：七种 stream_mode、独立的 stream 子系统。LangGraph4j 的做法更"Java"：**输出流就是那个 AsyncGenerator**，而流式 token 的注入靠一个**约定**——

> 节点动作返回的部分状态 Map 里，任何值为 `AsyncGenerator` 类型的条目（键名随意，惯例用 `"_generator"`）都会被引擎"抽取"出来：它的每个元素被逐个转发到图的主输出流，生成器正常结束后的**结果值**才作为该节点的真实状态更新（generator 键本身被移除，不进状态）。

引擎侧实现就是 `CompiledGraph.embedGenerator()`（`:705-778`）：

```java
final var generatorEntry = partialState.entrySet().stream()
        .filter(e -> e.getValue() instanceof AsyncGenerator).findFirst();      // :712-714 找到生成器
return generatorEntry.map(entry -> (AsyncGenerator<Output>) entry.getValue())
        .map(generator ->
            generator.reduce((a, b) -> {                                       // :719 逐元素
                $1.dispatchAsync(AsyncGenerator.Data.of(b));                   // :720 token → 主输出流
                return b;
            }).thenApply(reduceResult -> {
                final var returnFromEmbed = GraphResult.from(reduceResult.resultValue());
                if (returnFromEmbed.isInterruptionMetadata()) return returnFromEmbed;  // 生成器里也能中断
                // FIX #336：流式完成后才补跑 AfterCall 钩子                    // :732-744
                ... // FIX #102/#104：从部分状态移除 generator 键再合并          // :753-767
            }).join());
```

配合点：`NodeHooks.applyActionWithHooksHandlingInterruption` 检测到部分状态含生成器时**跳过 AfterCall**（`NodeHooks.java:196-201`），推迟到生成器耗尽（避免钩子在最终结果出来前就跑）。

## 7.2 StreamingOutput：token 的载体

`streaming/StreamingOutput<State>`（`:8`）继承 `NodeOutput`，加一个 `chunk` 字段（token 文本，`:10`）与 `isStreamingEnd()` 标记（`:31`）；子类 `StreamingOutputEnd` 携带 LLM 响应的最终元数据（token 用量等）。消费端的样子：

```java
app.stream(input, config).forEach(out -> {
    if (out instanceof StreamingOutput<State> s) {
        System.out.print(s.chunk());                    // 逐 token
    } else {
        System.out.println("\n[node] " + out.node());   // 节点边界
    }
});
```

**同一个流里混着两类事件**：节点边界（NodeOutput/StateSnapshot）与 token（StreamingOutput）——判断类型即可分流。这也是 AG-UI 集成能直接把 StreamingOutput 映射成 `TEXT_MESSAGE_*` 事件的原因（第十章）。

## 7.3 两条集成线的 StreamingChatGenerator

LangChain4j 线（`langchain4j/langchain4j-core/src/main/java/org/bsc/langgraph4j/langchain4j/generators/StreamingChatGenerator.java:22`）与 Spring AI 线（`spring-ai/spring-ai-core/src/main/java/org/bsc/langgraph4j/spring/ai/generators/StreamingChatGenerator.java:30`）是同一设计的两个桥：

| | LangChain4j 版 | Spring AI 版 |
|---|---|---|
| 模型 API | `StreamingChatModel.chat(request, StreamingChatResponseHandler)` 回调 | `ChatModel.stream(prompt)` 返回 `Flux<ChatResponse>` |
| 桥接 | 构造时创建内部 handler：`onPartialResponse(token)` → 产出 `StreamingOutput` → `dispatchAsync`（L145-181） | 订阅 Flux：每个 chunk 产出 `StreamingOutput`（L166-169），`.last()` 取最终响应（L175） |
| 结果值 | `onCompleteResponse` → `mapResult(chatResponse)` → `Data.done(...)`（L159-172），可选 `StreamingOutputEnd` | 最终 ChatResponse 经 `mapResult` → `Data.done(...)`（L184） |
| 合并语义 | token 流 → 最终 `AiMessage` | `mergeResponses` 按分片文本 + toolCall id 聚合成最终 `AssistantMessage`（L222-239） |
| 中断 | onError → `Data.error`（L177） | Flux 错误 → error |

节点里的用法（langchain4j 版，`langchain4j/langchain4j-agent/.../agentexecutor/CallModel.java:139-149`）：

```java
var generator = StreamingChatGenerator.<State>builder()
        .mapResult(this::mapResult).startingNode(nodeId).startingState(state).build();
streamingChatModel.chat(request, generator.handler());       // 回调异步驱动
return CompletableFuture.completedFuture(Map.of("_generator", generator));   // 约定键
```

Spring AI 版同型（`ChatService.stream` 的 Flux 喂给 generator）。**Studio 里不流式**：`config.isRunningInStudio()` 为真时退化为同步调用（`CallModel.java:139` 条件，spring-ai `CallModelAction.java:54-64` 同）——Studio 前端自己渲染状态，双份流式会打架。

## 7.4 StreamMode：VALUES 与 SNAPSHOTS

比 Python 七种 stream_mode 简单得多，只有两档（`CompiledGraph.java:76-79`）：

- **VALUES**（默认）：每个节点吐 `NodeOutput`（node + 状态）；
- **SNAPSHOTS**（`streamSnapshots()`，`:470-477`）：每个节点吐 `StateSnapshot`（多带 checkpointId 与 nextNodeId）——Studio 需要"当前停在哪个 checkpoint"才能支持恢复。

token 级流式不占 stream_mode 维度——它经 `_generator` 约定混在同一个流里。1.9.0 起用户还可以注入**自定义 Dispatcher**（`RunnableConfig.customDispatcher()`，`:439`；#427）在输出管道上做自己的分发（如计时、过滤、转发）。

## 7.5 线程模型：谁来跑循环、谁来跑分支

1.9 线把线程模型做成了可插拔（1.9.4，#485）：

- **`emitterExecutor`**（`RunnableConfig.java:138-141`）：图执行循环跑在哪个执行器上，缺省用 `CompletableFuture` 默认池。Javadoc 里的警告值得整段抄录（`:121-132`）：循环线程会阻塞等待每个节点完成——**单线程或小固定池执行器会死锁**（循环占着唯一线程等一个被调度到同池的任务），推荐 cached/virtual-thread/ForkJoinPool；
- **`addParallelNodeExecutor(nodeId, executor)`**（`:155`）：并行节点分支的执行器（不设则分支顺序执行，4.4 节）；
- 传递规则：循环线程绑定的 ThreadLocal（MDC、tracing）会带进节点/钩子/边/重试，但**不会**带入带独立执行器的并行分支；编译式子图继承父执行器。

异步基座 `AsyncGenerator` 的细节（`AsyncGeneratorFlow` 的 Dispatcher/`dispatchAsync`/`Data.of/done/error`）构成了"生产者推、消费者拉"的背压通道；`Cancellable` 支持取消（Studio 的 DELETE /stream 端点就是靠它取消运行中生成器的，`LangGraphStudioServer.java:416-450`）。

## 7.6 本章小结

- 流式 = 约定 + 一个方法：节点返回带 `AsyncGenerator` 值的 Map → `embedGenerator` 把元素逐个转发进主输出流、结果值作状态更新、generator 键移除；AfterCall 钩子推迟到流结束。
- `StreamingOutput(chunk)` 是 token 载体，与节点边界事件同流混排；`isStreamingEnd()` 标记流尾。
- 两条集成线的 `StreamingChatGenerator` 分别桥接 handler 回调（LangChain4j）与 Flux（Spring AI），聚合出最终消息作为状态更新；Studio 内自动退化为同步。
- StreamMode 只有 VALUES/SNAPSHOTS 两档 + 自定义 Dispatcher 扩展点；线程模型 1.9 可插拔，注意循环执行器死锁警告。

---
# 八、预制件：Agent / AgentEx 骨架与 ToolResponseBuilder

> 本章对应源码：core 的 `agent/Agent.java`、`agent/AgentEx.java`、`agent/ToolResponseBuilder.java`、`agent/skill/`。行号均为 1.9.4 实读。

## 8.1 先说白话：ReAct 循环的标准答案

1.7~1.8 时代，LangChain4j 线和 Spring AI 线各自维护一份 ReAct 图构建代码；1.9 起这份骨架被**上收进 core**：`Agent`（标准两节点循环）与 `AgentEx`（工具分发节点变体）两个接口 + Builder，两条集成线只负责填两个动作——"调模型"（callModel）和"执行工具"（executeTools）。这与 Python 1.0 的思路呼应（`create_agent` 上收进 langchain 主库），但 Java 版走的是"骨架在引擎 core、绑定在集成线"的分层。

## 8.2 Agent：三节点 ReAct 骨架

`Agent.Builder.build()`（`agent/Agent.java:101-126`）产出的图：

```
            ┌──────────────────────────────┐
            │                              ▼
START ──► agent（callModel） ──► action（executeTools，条件边）
            └────────── "end" ──► END
```

- 节点 `agent`（常量 `AGENT_LABEL`，`:45`）：callModel 动作——调 LLM，把 AiMessage 追加进 `messages`；
- 节点 `action`（`:46`）：executeTools 是 `AsyncCommandAction`，**作为条件边动作**挂在 action 节点上（`EdgeMappings.builder().to(AGENT_LABEL).toEND(END_LABEL)`，`:118-123`）——有工具调用 → 返回 `Command(AGENT_LABEL, update)` 回模型；没有 → `Command(END)` 收尾；
- 钩子注入点：callModel 挂 `NodeHook.WrapCall`（重试/日志），executeTools 挂 `EdgeHook.WrapCall`（`:106-113`）。

Builder 是纯泛型的（`Agent.<M,S>builder()`，`S extends MessagesState<M>`）——**消息类型 M 由集成线决定**，骨架不关心。

## 8.3 AgentEx：工具分发节点 + 审批

`AgentEx`（`agent/AgentEx.java`）把两节点循环改造成"分发器模式"：

```
START ──► model ──(shouldContinue)──► action_dispatcher ──(dispatchAction)──► 各工具节点 / approval_x / model / END
                 │                                    ▲        │
                 └──────────── END ◄──────(无工具调用)──────────┘（工具执行完回 dispatcher）
```

- **每个工具是独立节点**（`ToolBehaviour.addToGraph`，由集成线的工具封装实现）——好处：工具可挂独立钩子、可插审批节点、图结构在 Studio 里一目了然；
- `action_dispatcher` 持有待执行工具请求队列（`tool_execution_requests` 通道，`LinkedList`），`dispatchTools`（`:199-232`）每次**取队首**分发一条——串行执行工具，每执行完回到 dispatcher 取下一条；
- 需要审批的工具被路由到 `approval_<tool>` 节点（`:298-323`，第六章 6.6 的中断/恢复模式）；
- 状态多四个键：`tool_execution_requests` / `next_action` / `tool_execution_responses` / `approval_result`（`:61-75`）。

两条集成线的 `AgentExecutorEx` 就是往这个骨架里填 LangChain4j/Spring AI 的 CallModel 与工具封装。

## 8.4 ToolResponseBuilder：让工具控制路由

`agent/ToolResponseBuilder.java:20` 解决一个优雅的问题：**LLM 调用的工具（普通 Java 方法）如何在执行后决定图下一步去哪？** 机制（三条集成线共用，各有一层薄封装）：

1. 工具服务执行每个工具前，往工具上下文塞一个 `AtomicReference<Command>`（键 `ToolResponseBuilder.COMMAND_RESULT`，langchain4j 线 `LC4jToolService.scopedToolCall` L171-203，Spring AI 线 `SpringAIToolService` L142-159）；
2. 工具方法体内用 `ToolResponseBuilder`（或集成线封装 `LC4jToolResponseBuilder`/`SpringAIToolResponseBuilder`）调用 `gotoNode("x").update(...).buildAndSet(...)`（`:42/53/91`）——把 Command 写进引用；
3. 工具服务收集所有 Command 合并成最终 `Command(目标节点, 状态更新)` 返回——**多个工具同时设置 goto 会抛异常**（langchain4j 线 L139-144），防止路由歧义。

典型用途：工具执行失败直接跳到"错误处理节点"；人工审批型工具根据结果路由到不同分支——不用为此改图结构。

## 8.5 skill 包：SKILL.md 解析

`agent/skill/` 三个小类（`SkillParser.java`/`SkillPath.java`/`SkillSource.java`）：解析 Agent Skills 规范的 `SKILL.md`（YAML front matter：name/description/allowed-tools，正文为技能说明）。core 只负责解析；"技能怎么注入会话"由集成线实现（第九章的 SkillInjector）。1.9.0-beta4 引入（langchain4j-agent 线），配套 `dev.langchain4j:langchain4j-skills` beta 依赖。

## 8.6 本章小结

- core 提供 `Agent`（START→agent⇄action→END）与 `AgentEx`（model→action_dispatcher→每工具一节点+审批）两个泛型 ReAct 骨架；集成线只填 callModel/executeTools 动作。
- `Command` + 条件边实现"工具执行完决定去哪"；`ToolResponseBuilder` 经工具上下文的 AtomicReference 让**工具方法体内部**写 Command 控制路由（goto 唯一性有保障）。
- AgentEx 的分发器模式换来三个能力：工具独立成节点（可挂钩子/审批/可视化）、工具串行队列化、审批节点可插拔。
- SKILL.md front-matter 解析在 core，注入策略在集成线。

---

# 九、生态集成：LangChain4j 与 Spring AI

> 本章对应源码：`langchain4j/` 三模块（`langgraph4j-langchain4j`、`langgraph4j-langchain4j-agentexecutor`、relocation 占位）与 `spring-ai/` 三模块（`langgraph4j-spring-ai`、`langgraph4j-springai-agentexecutor`、`spring-ai-agent-archetype`）。artifactId 均经 pom 实证；引用行号为 1.9.4 实读。

## 9.1 两条线、四件套

两条集成线结构完全对称，各四个组件——理解一个就理解两个：

| 组件 | LangChain4j 线 | Spring AI 线 |
|---|---|---|
| 流式生成器 | `StreamingChatGenerator`（桥 handler 回调） | `StreamingChatGenerator`（桥 `Flux<ChatResponse>`） |
| 工具服务 | `LC4jToolService`（`Map<ToolSpecification, ToolExecutor>`） | `SpringAIToolService`（按名查 `ToolCallback`） |
| 状态序列化器 | `LC4jStateSerializer`（JDK 流）/ `LC4jJacksonStateSerializer` | `SpringAIStateSerializer` / `SpringAIJacksonStateSerializer` |
| ReAct 模板 | `AgentExecutor` / `AgentExecutorEx`（绑定 core `Agent`/`AgentEx`） | 同名类（同绑定） |

依赖坐标（写作时）：LangChain4j 1.22.0（根 pom `langchain4j.version`）；Spring AI 2.0.1。两条线互相独立，只共同依赖 core。

## 9.2 消息模型零转换

与 Python 版最大的差异：**core 不定义消息类型**。LangChain4j 线直接令 `MessagesState<T>` 的 `T = dev.langchain4j.data.message.ChatMessage`（`AgentExecutor.State`），Spring AI 线用 `org.springframework.ai.chat.messages.Message`。所谓"消息转换"全部由**序列化器**承担（checkpoint 落库时按类型注册编解码 handler，如 LC4jJacksonStateSerializer 按 `ChatMessageType` 注册 System/User/Ai/ToolExecutionResult 四类，`LC4jJacksonStateSerializer.java:13-46`）。

好处：节点代码里操作的就是原生框架消息对象，无映射损耗、无信息丢失；代价：**两线状态互不兼容**（同一 checkpoint 不能跨线读取），且换框架 = 换状态类型 = 换序列化器。

## 9.3 AgentExecutor：最小 ReAct 智能体（LangChain4j 线）

```java
// 构建（langchain4j/langchain4j-agent 的 README 与测试的标准写法）
var graph = AgentExecutor.builder()
        .chatModel(chatModel)                    // 或 chatModel(streamingModel, emitStreamingOutputEnd)
        .toolsFromObject(new MyTools())          // 反射扫描 @Tool 注解（LC4jToolMapBuilder:43-69）
        .build();                                // 返回 StateGraph<AgentExecutor.State>

// 运行：入口键 "messages"，直接放 langchain4j 的 UserMessage
var outputs = graph.compile().stream(Map.of("messages", UserMessage.from("帮我查天气")), config);
// 取最终回复：最后输出 state().finalResponse()（agent_response 键）
```

内部三个动作：`CallModel`（`CallModel.java:25`）拼 ChatRequest（system 消息 + 工具规格 + 可选 ResponseFormat）、调模型、按 finishReason 分 TOOL_EXECUTION/STOP 两路 mapResult；`executeTool`（`AgentExecutor.java:112-140`）取 AiMessage 的 `toolExecutionRequests` 调 `LC4jToolService.execute` → 完成后 `Command(AGENT_LABEL)` 回模型或 `Command(END)`。工具注册支持：注解对象（`toolsFromObject`）、手动 spec+executor（`tool(spec, executor)`）、**MCP 客户端**（`tool(McpClient)`，`LC4jToolMapBuilder.java:100`，依赖 `langchain4j-mcp`）、技能包（`skills(...)`）。

`AgentExecutorEx` 换用 core `AgentEx` 骨架，多出三个能力：每工具独立节点、`approvalOn(toolName, metadataProvider)` 审批中断（第六章）、`tool_execution_requests` 队列串行分发。

## 9.4 SkillInjector：运行时技能热插拔

1.9.0-beta4 引入（beta5 完善，PR #439），是 LangChain4j 线的特色能力（`langchain4j/langchain4j-agent/.../skillinjector/SkillInjector.java:35`）：

- **思想**：系统提示词只带"技能目录"（name + description，省 token）；某工具执行成功后，把对应技能的**正文**注入下一次模型请求的前置 SystemMessage（`asConversationContextPolicy()`，L152/159）；
- **生命周期**：`active_skills` 状态通道记录激活的技能 id（永不把技能正文写进状态）；`singleTurn()`（L270）= 每次 callModel 后全部卸载；`UnloadTarget`（sealed：None/All/Ids/Matching）支持选择性卸载；
- **接线**：`AgentExecutor.builder().skillInjector(injector)` 自动注册三个钩子（executeTools 的 AfterCall 钩子激活技能 + callModel 的会话策略，`AgentExecutor.java:214-221`）；
- 技能来源：classpath（`skillsFromClassPath("skills")`）或文件路径（`skillsFromPath`），底层用 core 的 SkillParser 解析 SKILL.md。

## 9.5 Spring AI 线与 Maven 脚手架

Spring AI 线（`langgraph4j-spring-ai` + `langgraph4j-springai-agentexecutor`）与 9.3 对称：`ChatService`/`DefaultChatService` 包装 `chatModel.call/stream`（`agent/ChatService.java:13-37`），工具经 `ToolCallingChatOptions` 注入（`DefaultChatService.java:26-28`）。额外两个东西：

1. **SubAgent：子代理即工具**（`agent/SubAgent.java`）。接口同时继承 Spring AI 的 `ToolCallback` 与 AgentEx 的 `ToolBehaviour`；`SubAgentImpl.addToGraph`（L62-98）用钩子把父图的工具调用参数转成 UserMessage 喂给子图、把子图最后一条消息包回 `ToolResponseMessage`——**子图作为真正的图节点存在**（不是扁平拼接）。`SkilledReactSubAgent`（L24-96）进一步从 SKILL.md 构建子代理（front matter 定义 name/description/allowed-tools，正文即子代理 system prompt）。多代理示例见测试 `SubAgentITest.java:41-83`（marketplace/payment 两个子代理挂主 AgentExecutorEx）。
2. **spring-ai-agent-archetype**：Maven Archetype 脚手架（packaging=maven-archetype），`mvn archetype:generate -DarchetypeArtifactId=spring-ai-agent-archetype` 一键生成完整 Spring Boot 工程（依赖 core + spring-ai + studio-springboot，自带 Javelit 演示 UI 与 skills 资源）——官方期望的"从零到能跑"路径。

Spring Boot 用户注意：**没有"langgraph4j-spring-boot-starter"通用 starter**；Spring 集成点只有两个——Studio 的 `langgraph4j-studio-springboot`（继承 `LangGraphStudioConfig` 注册实例）与 AG-UI 的 `langgraph4j-ag-ui-springboot`（注册 `AGUIAgentRegistry`）。图本身不需要 Spring 容器。

## 9.6 从用户视角的选型

| 你在用 | 推荐 |
|---|---|
| LangChain4j（含 MCP 客户端） | `langgraph4j-langchain4j` + `-agentexecutor`；工具生态最全（注解/MCP/skills/审批） |
| Spring AI 2.x | `langgraph4j-spring-ai` + `-springai-agentexecutor`；要脚手架用 archetype；要子代理用 SubAgent |
| 两者都想看效果 | how-tos 的 agentexecutor.ipynb 与 adaptiverag.ipynb（rapaio-jupyter 内核，Java 22） |

## 9.7 本章小结

- 两条集成线四件套对称（StreamingChatGenerator/ToolService/StateSerializer/AgentExecutor），互相独立、只依赖 core。
- 消息零转换：状态直接装原生 ChatMessage/Message，序列化器承担持久化兼容；跨线 checkpoint 不兼容。
- AgentExecutor 是最小 ReAct（工具支持注解/手动/MCP/skills 四种注册）；AgentExecutorEx 加审批与队列分发；SkillInjector 实现"目录常驻、正文按需"的技能热插拔。
- Spring AI 线的 SubAgent 把子代理做成真图节点工具；archetype 脚手架一键生成可跑工程；Spring 集成点只有 Studio 与 AG-UI 两个 starter 形态。

---
# 十、可观测与调试：Studio、AG-UI、OpenTelemetry

> 本章对应源码：`studio/`（base/jetty/quarkus/springboot）、`ag-ui/`（json/sdk/springboot）、`langgraph4j-opentelemetry`、`langgraph4j-dsl`、`javelit`。行号均为 1.9.4 实读。

## 10.1 Studio：内嵌可视化 Playground

**定位**（`studio/README.md`）："embed playground webapp to run a Langgraph4j workflow in visual way"——画图（JSON-DSL → React Flow）、步进运行（节点高亮）、查看/编辑状态、中断恢复、取消运行。四个模块：

| 模块 | artifactId | 角色 |
|---|---|---|
| base | `langgraph4j-studio` | 平台无关：两个 Servlet + Instance 模型（依赖 core + dsl + jakarta.servlet-api） |
| jetty | `langgraph4j-studio-jetty` | 独立 Jetty 12 壳（`LangGraphStudioServer4Jetty.builder().port(8081).instance(...).start()`） |
| quarkus | `langgraph4j-studio-quarkus` | Quarkus 3.18.3 壳（`@WebServlet` 代理 + CDI） |
| springboot | `langgraph4j-studio-springboot` | Spring Boot 3.4.2 壳（`ServletRegistrationBean`，asyncContextTimeout 300s） |

**REST 协议是 LG4J 自有的，不兼容 Python LangGraph Server/Studio**（后者是 /threads//runs API + threads/store 语义；LG4J 是两个 Servlet 端点 + JSON-lines）：

| 方法 | 路径 | 作用 | 源码 |
|---|---|---|---|
| GET | `/init?instance=<id>` | 返回 `InitGraphData`（JSON-DSL 图描述 + 参数元数据 + threads 列表） | `LangGraphStudioServer.java:303-336` |
| POST | `/stream/{instanceId}?thread=<id>`（body=输入 JSON） | 执行图，chunked 文本流**逐行 JSON** 吐 `NodeOutput` | `:470-598` |
| POST | 同上 + `&resume=true&node=<n>&checkpoint=<id>` | 用户编辑状态后从检查点恢复（内部 `updateState` + `streamSnapshots(GraphInput.resume())`） | `:526-556` |
| DELETE | `/stream/{instanceId}?thread=<id>` | 取消运行中的生成器（Cancellable） | `:416-450` |

`Instance`（`:72-134`）= title + StateGraph + CompileConfig + 输入参数元数据；**无 saver 时强制默认 MemorySaver**（`:245-248`）——Studio 依赖 checkpoint 实现恢复与步进。执行时打 `STUDIO_METADATA_KEY`（`:379`），节点可经 `config.isRunningInStudio()` 感知（关闭 LLM 流式、补写审批占位等，6.7 节）。

另有轻量的 **LG4JEmbedViewerService**（`:222-229` 注册 `/viewer/*`）：不自持执行，外部把 `NodeOutput` `dispatchAsync` 进来即可在嵌入页看到动画——适合集成进自己的管理后台。

## 10.2 AG-UI：CopilotKit 兼容的前端事件流

AG-UI（Agent-User Interaction）是开源的 agent↔前端事件协议（`RunAgentInput` 请求 + 判别字段 JSON 事件 + SSE）。1.9.1 引入三模块（#480），基于 AG-UI Community Java SDK 0.1.1：

- `langgraph4j-ag-ui-json`：Jackson 版事件序列化（mixin 注册全部事件类型：RUN_*/STEP_*/TEXT_MESSAGE_*/TOOL_CALL_*/REASONING_*/STATE_SNAPSHOT/STATE_DELTA/MESSAGES_SNAPSHOT/CUSTOM/RAW 等，`EventMixin.java:59-89`）；
- `langgraph4j-ag-ui-sdk`：核心桥 `AGUIAgentBase`——懒编译图（`newGraph()`，L63），`run(RunAgentInput)` 返回 `Flow.Publisher<Event>`；把 `graph.stream()` 的输出翻译成事件：`StreamingOutput` → `TextMessageStart/Content/End`（L234-268），节点边界 → `StepStarted/Finished`（`AGUIHook.stepEvents()` 经 NodeHook 自动发，L32-51），异常 → `RunErrorEvent`，完成 → `RunFinishedEvent`；
- `langgraph4j-ag-ui-springboot`：唯一端点 **`POST /sse/{agentId}`**（`AGUISSEController.java:61-67`，SseEmitter 逐事件推送）；应用侧只需两个 Bean：`Serializer` + `AGUIAgentRegistry`。

人机协同直接复用第六章机制：测试参考 `AGUIAgentExecutorHITL.java`——`approvalOn("sendEmail", ...)` 中断时发 `ToolCallChunkEvent + StateDeltaEvent(/resume=true) + RunFinishedEvent`，前端（CopilotKit）补答后下轮 `POST /sse` 携带 resume 数据转成 `GraphInput.resume(...)`。配套 `ag-ui/copilot-app/` 是 Next.js 前端 starter。

## 10.3 OpenTelemetry：钩子形态的埋点

`langgraph4j-opentelemetry` 不是 agent 框架的 exporter，而是**利用第三章钩子体系做的参考实现**（1.8.0 引入，#271）：

- `OTELWrapCallTraceHook`：同时实现 `NodeHook.WrapCall` 与 `EdgeHook.WrapCall`，包裹执行创建 Span，属性记录 config 字段与序列化状态，事件记录状态/Command（`OTELWrapCallTraceHook.java:26`）；
- `OTELWrapCallTraceSetParentHook`：builder(scope/groupName) 创建父 Span，把一次图执行的所有 node/edge span 归组（`:28`）；
- `OTELObservable`：Tracer/Meter 持有者 + counter/gauge/histogram 工具；
- `OTELInternalHttpCollector`：本地测试用 OTLP/HTTP 接收器（JSON 落盘）。

集成姿势：`AutoConfiguredOpenTelemetrySdk.initialize()` 后 `graph.addWrapCallNodeHook(traceHook).addWrapCallEdgeHook(traceHook).addWrapCallNodeHook(parentHook)`——**父 Hook 必须最后添加**（WrapCall 是 LIFO，README 显式警告）；配套 docker-compose（Jaeger/Grafana+Loki）在模块内。

## 10.4 dsl 与 javelit

- **langgraph4j-dsl**：`JsonDslGenerator` 实现 `GraphDefinition.Reducer<State,String>`（1.7.4 的 reduce 机制的第一个正式消费者，`JsonDslGenerator.java:22`），把图序列化为 JSON DSL（`{type:"langgraph4j", nodes[], edges[], subgraphs[]}`；子图递归展开、节点 id 以 `-` 连接路径），附 JSON Schema 校验。Studio `/init` 与 `lg4j-dsl-view` web component 消费它；测试里的用法一行：`workflow.compile().reduce(new JsonDslGenerator<>())`。
- **javelit**（Java 21，唯一非 17 模块）：为 [Javelit](https://javelit.io)（Java 版 Streamlit）提供的组件包——`JtPlantUMLImage`（渲染图结构）、`JtJsonEditor`（JSON-Schema 驱动的状态编辑器）、`JtDataTable`（可选中表格，SaverV2 Dashboard 用它管理线程/Tag）、`JtIFrame`、`JtMultiSelect`、`JtSelectAiModel` 等（`javelit/src/main/java/org/bsc/javelit/`）。演示应用 `JtAgentExecutorApp.java` 是一个 JBang 脚本，直连 archetype 依赖即可跑一个带 UI 的 agent。

## 10.5 how-tos：33 个可运行 Notebook

`how-tos` 模块（artifactId `langgraph4j-howtos`）是官方学习路径：33 个 Java Jupyter notebook（rapaio-jupyter-kernel，需 Java 22）+ JUnit 集成测试（`*ITest` 走真实 LLM、`*StubTest` 桩测试）。主题覆盖：入门、并行分支、三种子图用法、持久化（Memory→Hazelcast 切换）、time-travel、interrupt/wait-user-input、LLM 流式（LangChain4j 与 Spring AI 双版）、流式并行分支、AgentExecutor（含 MCP：`MCPIntegrationITest`）、多智能体 supervisor、三种 RAG（adaptive/agentic/corrective）、认知模式（reflection/prompt-chaining/plan-and-execute）、hooks、PlantUML 导出。`src/docker/` 提供 postgres + 初始化 schema 的 compose 供持久化示例。

**注意**：仓库没有 A2A 模块（协议层由 AG-UI 承担），也没有独立的 ToolNode——工具执行内置于 Agent/AgentEx 骨架（第八章），这是与 Python `langgraph-prebuilt` 的结构差异。

## 10.6 本章小结

- Studio 四模块（base 协议 + jetty/quarkus/springboot 三壳），自有 REST 协议（/init /stream DELETE），不兼容 Python LangGraph Server；无 saver 强制 MemorySaver；`isRunningInStudio()` 是各组件与 UI 联动的开关。
- AG-UI 三模块把图执行翻译成 CopilotKit 兼容的 SSE 事件流（`POST /sse/{agentId}`），审批/恢复直接复用 GraphInput.resume 机制。
- OTEL 是钩子体系的参考实现（WrapCall span + 父 span 归组），父钩子必须最后注册。
- dsl = reduce() 消费者（JSON 图描述）；javelit = Java 21 UI 组件包（含 SaverV2 Dashboard）；how-tos = 33 个 notebook 的官方教材。

---

# 十一、工程实践与常见坑

## 11.1 选型决策

| 场景 | 建议 |
|---|---|
| 团队主力语言 Java、要做可控的多步智能体/工作流 | LangGraph4j 是当前 Java 生态最完整的"低层编排"选项：显式图 + checkpoint + HIL + 流式 + Studio 调试 |
| 只需要单轮 ReAct、不想画图 | 直接用 LangChain4j 的 `AiServices`/Spring AI 的 `ChatClient` + 工具调用；LangGraph4j 的价值在有环、有状态、有人的介入时才显现 |
| 需要跨语言团队对齐（Python 侧已有 LangGraph） | 概念可平移（StateGraph/条件边/checkpoint/interrupt 一一对应），但注意 1.2/2.6/4.6 列举的行为差异——**把 Python 教程直接翻译成 Java 会踩坑** |
| 极端性能/低延迟 | 状态克隆是序列化往返（4.3），每节点一次；大状态用 `disableCloneState` + transient 属性，或评估是否真的需要框架 |

## 11.2 高频坑清单（按踩中概率排序）

1. **threadId 没传**：所有无 threadId 的运行共享 `$default` 线程（`BaseCheckpointSaver.java:15`）——并发运行的对话互相污染 checkpoint。
2. **跑完想 resume，发现 checkpoint 没了**：`releaseThread` 默认 true（`CompileConfig.java:167`），正常结束即释放归档；要么 `releaseThread(false)`，要么从 `tag()` 读归档。
3. **并行分支写同一单值键被静默覆盖**（2.6）：没有 InvalidUpdateError——设计归约器或改键名。
4. **执行器死锁**：`emitterExecutor` 传单线程池（4.7 警告），循环阻塞等待被调度到同池的任务；用 cached/虚拟线程/ForkJoinPool。
5. **状态里有不可序列化对象**：cloneState/checkpoint 直接炸（`NotSerializableException` 或 Jackson 找不到 handler）——LLM 消息务必用集成线序列化器；自定义大对象声明 transient 或实现专版 serializer。
6. **子图状态类型不匹配**：`addNode(id, StateGraph)`/`addNode(id, CompiledGraph)` 都要求与父图同一 State 类型（子图共享状态是硬约束，`StateGraph.java:283` javadoc）；不同状态的子图用拍平式做不到，需要手工桥接节点。
7. **副作用节点被重放**：中断恢复 = 节点从头重跑（4.6）——外部调用（发邮件、写库）必须幂等，或学 AgentEx 用状态键判据跳过。
8. **条件边映射穷举**：条件动作返回的 gotoNode 不在 mappings 里直接抛 `missingNodeInEdgeMapping`（`CompiledGraph.java:318-320`）——映射是白名单不是默认路由。
9. **recursionLimit=25 默认值**（`CompileConfig.java:169`）：长循环智能体（多轮工具调用）会撞上限抛 IllegalStateException——按需调大（CompileConfig 图级 + RunnableConfig 单次调用级两级）。
10. **WrapCall 钩子顺序**：全局与按节点的钩子按注册序拼接（全局在前），RetryPolicy 这类"包装"语义的钩子要最先注册（最外层）；OTEL 父 span 钩子要最后注册（10.3）。

## 11.3 与 Python LangGraph 的关键差异速查

| 维度 | Python 版 | LangGraph4j 版 |
|---|---|---|
| 执行引擎 | Pregel BSP 超步 + 通道版本号 | 顺序事件循环 + nextNodeId 游标 |
| 状态冲突 | LastValue 报 InvalidUpdateError | 按序覆盖，无检测 |
| 状态 schema | TypedDict + Annotated 注解 | AgentState 子类 + 显式 SCHEMA Map |
| 消息模型 | 自有 BaseMessage 体系 | 复用 LangChain4j/Spring AI 消息，core 零定义 |
| interrupt | `interrupt()` 函数 + Resume 草稿簿（细粒度重放） | InterruptibleAction / GraphInterruptException / 声明式（节点整体重放） |
| 流式 | 七种 stream_mode + v2 类型流 | 单流混排（NodeOutput + StreamingOutput）+ VALUES/SNAPSHOTS |
| 预制件 | langgraph-prebuilt（ToolNode/create_react_agent） | core 的 Agent/AgentEx 骨架 + 集成线绑定 |
| 部署 | LangGraph Server/Platform（threads/runs API） | Studio（自有协议）+ AG-UI（SSE）；无 Server 形态 |
| 记忆 | 短期 checkpointer + 长期 Store（语义检索） | 只有 checkpointer（无 Store 等价物）；长期记忆自己接向量库 |
| 生态成熟度 | 1.x GA、生态庞大 | 活跃但单人主导，2.0 筹备中 |

## 11.4 本章小结

- 选型主线：有环/有状态/有人审的 Java 智能体工作流选它；单轮 ReAct 不必上框架。
- 十大坑的前三：threadId 缺省共享、releaseThread 默认释放、并行写覆盖——都是"默认值的选择"造成的。
- 与 Python 版的最大语义分叉：顺序引擎（无版本号/无冲突检测/节点整体重放），一切差异皆源于此。

---

# 十二、附录

## 12.1 关键类速查表

| 类 | 一句话 | 关键方法 |
|---|---|---|
| `StateGraph<S>` | 图构建器 | addNode×4 / addEdge / addConditionalEdges / addWrapCallNodeHook / compile |
| `CompiledGraph<S>` | 编译产物 + 引擎 | stream / streamSnapshots / invoke / invokeFinal / getStateHistory / getState / updateState / getGraph |
| `CompileConfig` | 编译配置 | checkpointSaver / interruptBefore(After) / interruptBeforeEdge / recursionLimit / releaseThread / graphId |
| `RunnableConfig` | 运行配置 | threadId / checkPointId / streamMode / recursionLimit / emitterExecutor / addParallelNodeExecutor / disableCloneState / 元数据 API |
| `AgentState` | 状态基类 | value(key) / data() / 静态 updateState(state, partial, channels) / MARK_FOR_RESET / MARK_FOR_REMOVAL |
| `Channel<T>` / `Channels` | 归约器注册 | update(key, old, new)；工厂 base / appender / appenderWithDuplicate |
| `AppenderChannel<T>` | 追加通道 | RemoveIdentifier（删）/ ReplaceAllWith（整体替换） |
| `MessagesState<T>` | 消息状态 | messages() / lastMessage() / lastMinus(n) |
| `NodeAction` / `AsyncNodeAction` | 节点动作 | `apply(state[, config]) → CompletableFuture<Map>`；node_async 适配 |
| `AsyncCommandAction` | 条件边/路由动作 | `apply → CompletableFuture<Command>` |
| `Command` | 控制流载荷 | gotoNode / update / withMergedUpdate |
| `Checkpoint` | 存档四元组 | id / state / nodeId / nextNodeId |
| `BaseCheckpointSaver` | 持久化 SPI | list / get / put / release / tag / registerInterruption / putSubGraphSaver |
| `MemorySaver` / `FileSystemSaver` | 内置实现 | — |
| `InterruptibleAction` | 节点级中断 | `interrupt(nodeId, state, config) → Optional<InterruptionMetadata>` |
| `InterruptionMetadata` | 中断载荷 | nodeId / state / reason / 自定义元数据 |
| `GraphInput` | 启动/恢复 | args / resume / resume(map) / resume(checkpoint) |
| `NodeOutput` / `StreamingOutput` / `StateSnapshot` | 流的事件 | node() / state() / chunk() / isStreamingEnd / checkpointId |
| `GraphResult` | 终态封装 | Type: STATE_DATA / INTERRUPTION_METADATA / CHECKPOINT_SAVER_TAG / CANCELLED |
| `RetryPolicy` | 重试钩子 | builder(maxAttempts/retryDelay/backoffFactor/jitter/retryOn).build().asHook() |
| `Agent` / `AgentEx`（core） | ReAct 骨架 | Builder：callModelAction / executeToolsAction / hooks |
| `ToolResponseBuilder` | 工具路由 | gotoNode / update / buildAndSet |
| `JsonDslGenerator` | 图 → JSON DSL | `compile().reduce(new JsonDslGenerator<>())` |

## 12.2 学习路线（一晚上版）

1. 跑通 getting-started（how-tos notebook）或 README 的 SimpleGraphApp——建立"SCHEMA + addNode/addEdge + compile + stream"手感；
2. 读本文第二、三章，对照 `AgentState.updateState` 与 `StateGraph` 源码走一遍；
3. 读第四章 4.2 的主循环走读，对着 `CompiledGraph.accept()`（`:844`）源码逐段过——此后一切行为都能自行推演；
4. 把 wait-user-input（interrupt）与 time-travel（releaseThread(false) + getStateHistory）两个 notebook 跑一遍，理解第五章、第六章；
5. 按所选框架读第九章对应小节，用 AgentExecutor 搭一个带工具的 ReAct，再用 AgentExecutorEx 加一个 approvalOn；
6. 需要 UI 时上 Studio（一行 builder）或 AG-UI（两个 Bean）。

## 12.3 源码阅读入口清单（按行数从大到小）

| 文件 | 行数 | 读什么 |
|---|---|---|
| `CompiledGraph.java` | 1183 | Emitter 主循环（:844）、embedGenerator（:705）、子图扁平化（:1013）、resume（:625） |
| `StateGraph.java` | 489 | Errors 全集（:38）、addNode 重载（:211-360）、validateGraph（:428） |
| `RunnableConfig.java` | 456 | 保留元数据键（:207）、执行器与克隆开关 |
| `DiagramGenerator.java` | 334 | 图遍历模板（reduce 消费者） |
| `AppenderChannel.java` | 255 | update 的四种更新形状（:211） |
| `GraphPath.java` | 252 | 节点/图路径（Iterable<String>） |
| `CompileConfig.java` | 249 | 全部编译期开关 |
| `AgentState.java` | 242 | 哨兵与归约管线（:96-183） |
| `FileSystemSaver.java` | 251 | 文件持久化参考实现 |
| `ParallelNode.java` | 155 | 分支并发与合并（:122） |

## 12.4 术语对照表（Python ↔ Java）

| Python LangGraph | LangGraph4j |
|---|---|
| `StateGraph` / `START` / `END` | `StateGraph` / `GraphDefinition.START("__START__")` / `END("__END__")` |
| `Annotated[list, add_messages]` | `Map.entry("messages", Channels.appender(ArrayList::new))` |
| `add_messages` / `RemoveMessage` | `AppenderChannel.update` / `RemoveIdentifier` / `MARK_FOR_REMOVAL` |
| `add_conditional_edges` | `addConditionalEdges(source, AsyncCommandAction, mappings)` |
| `Command(goto=, update=)` | `Command(gotoNode, update)` |
| `interrupt()` + `Command(resume=)` | `InterruptibleAction.interrupt()` / `GraphInterruptException` + `updateState` + `GraphInput.resume()` |
| `interrupt_before/after` | `CompileConfig.interruptBefore/After`（+ `interruptBeforeEdge`） |
| `MemorySaver`/`SqliteSaver`/`PostgresSaver` | `MemorySaver`/`SQLiteSaver(V2)`/`PostgresSaver(V2)` |
| `thread_id` / `config` | `RunnableConfig.threadId` / `RunnableConfig` |
| `stream_mode="values"/"updates"` | `StreamMode.VALUES` / （updates 语义=观察状态差，自行对比） |
| `stream_mode="messages"` | `StreamingOutput`（经 `_generator` 约定键） |
| `Send`（动态 map-reduce） | 无直接等价物——用多目标边 + ParallelNode 表达静态 fan-out |
| `create_react_agent` | 集成线 `AgentExecutor` / `AgentExecutorEx` |
| `ToolNode` | Agent 骨架的 action 节点 / AgentEx 的每工具节点 |
| LangGraph Studio/Server | `langgraph4j-studio`（自有协议） |
| `get_graph().draw_mermaid()` | `getGraph(GraphRepresentation.Type.MERMAID)` |
| RecursionLimit | `CompileConfig.recursionLimit()`（默认同为 25） |
| `Store`（长期记忆） | 无等价物（自接向量库） |

---

> **收尾**：LangGraph4j 的全部设计可以压缩回一句话——**用 Java 的方式重讲 LangGraph 的故事**：Pregel 换成事件循环、通道动物园换成归约器注册表、interrupt 函数换成接口与异常、LangGraph Server 换成内嵌 Studio 与 AG-UI。概念同源让 Python 教程可以平移，实现分流让每一处"不同"都有 Java 生态的理由。读源码时始终带着这张对照表，你会发现每一个"为什么不一样"的答案都写在本文标注的那一行里。
