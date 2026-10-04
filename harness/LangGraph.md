# LangGraph 深度源码解析（写给初学者的架构全景）

> **本文基于的源码**：`D:\code\3rd\langgraph`，版本 **1.2.12**（langchain-ai/langgraph 仓库 tag `1.2.12`，2026-09-21 发布于 PyPI，写作时最新稳定版）。文中所有【源码证据】的文件路径与行号均为对该 tag 实际读取所得；`libs/langgraph` 指主包源码根 `D:\code\3rd\langgraph\libs\langgraph\langgraph`，`libs/prebuilt` 指预制件包 `libs\prebuilt\langgraph\prebuilt`，后文不再重复。
>
> **版本取舍说明**：LangGraph 迭代极快（0.x 时代几乎每周发版），但**核心引擎（Pregel 循环、Channel、Checkpoint）自 0.2 以来骨架高度稳定**，1.0 只是"收编"而非"重写"——因此本文内容对使用 1.0/1.1 的读者同样适用。0.x → 1.0 → 1.2 的关键变化对比见 1.6 节；文中所有"某特性属于哪个版本"的结论均经 PyPI 历史 wheel 反编译 grep 与 GitHub Release 记录双重实证。本文以 **Python 版**为准；JS/TS 版（`@langchain/langgraph`，仓库 `langchain-ai/langgraphjs`，写作时 1.4.19）与 Python 同构但独立演进，不再展开。
>
> **阅读约定**：每章"先白话、后源码"——先用一两句人话讲清"这是什么、为什么需要它"，再给出源码证据链（`包路径/文件名.py` + 类名/方法名 + 行号 + 代码片段）。行号只对 1.2.12 这个 tag 精确，读者打开源码按类名 + 方法名定位即可。每章末尾有"本章小结"。

## 如何读这份文档

如果你是 LangGraph 初学者，推荐两遍读法：

- **第一遍（建立地图，1~2 小时）**：只读第一章（总览）每一节的开头白话段、各章的"本章小结"、以及第十二章（贯通视图）。目标是能回答：`graph.invoke()` 内部循环是哪三段？为什么两个节点同时写一个键会报错？`interrupt()` 凭什么能在进程重启后还能恢复？checkpoint 里到底存了什么？
- **第二遍（深入源码）**：对照每一章的【源码证据】打开源码逐行读。顺序建议：第二章（State 与 Channel）→ 第三章（图构建与编译）→ 第四章（Pregel 引擎）→ 第六章（interrupt）→ 第五章（Checkpoint）→ 第八章（Functional API），其余章节（流式、记忆、预制件、部署）随用随查。

---

# 一、总览：LangGraph 的定位、设计哲学与整体架构

## 1.1 一句话定位

**LangGraph 是一个低层级（low-level）、以共享状态为核心的有状态智能体编排框架**：它把"长时运行、有状态的工作流或智能体"建模为一张"节点读写共享状态"的图，用 **Pregel 算法（Bulk Synchronous Parallel，整步同步并行）**执行，并把**持久化（durable execution）、人在环路（human-in-the-loop）、记忆、流式输出**做成了引擎的一等公民。官方 README 的自我定位只有一句话（`README.md:17`）：

> "Low-level orchestration framework for building stateful agents."

拆开看这句定位里的每个词：

- **low-level**：它不替你决定"智能体应该怎么思考"。ReAct 循环、supervisor 模式都只是"预制件"（prebuilt），不是框架本体；框架本体只提供节点、状态、边、检查点这几个原语（对比：AutoGen/CrewAI 是"角色与对话"的高层抽象）。
- **orchestration**：解决的是"多步、带循环与分支、可并行"的**控制流编排**问题——这正是 Chain/管道模型（DAG、无环、单次执行）做不到的事。智能体的本质是 `while(模型还想干活) { 调模型; 调工具 }`，**必须支持循环**。
- **stateful**：节点之间不靠参数传递消息，而是共同读写一个**带类型、带归约器的共享状态**；状态本身可以被逐步存档（checkpoint），于是"暂停/恢复/重放/改写历史"全部免费获得。
- **long-running**：一次执行可能跑几分钟到几天，中间会崩、要重启、要人审——所以每一步都要能持久化，进程死了也能从断点继续（durable execution）。

它解决的问题不是"调用一次 LLM"，而是**智能体的工程化难题**：控制流有环（图模型）、多步状态一致（通道归约）、随时可停可续（检查点）、人类可介入（interrupt）、过程可观测（流式 + LangSmith）、可部署（LangGraph Server）。

## 1.2 设计哲学：读源码前先记住四句话

1. **图只是前端，引擎是 Pregel**。`StateGraph`（图 API）、`@entrypoint`（函数式 API）都是"编译器前端"，最终统统编译成同一个 `Pregel` 执行器——`Pregel` 类的 docstring 原话（`libs/langgraph/langgraph/pregel/main.py:516-523`）："Most users will interact with Pregel via a StateGraph (Graph API) or via an entrypoint (Functional API) ... These are higher-level interfaces that will compile down to Pregel under the hood." 读源码时盯住 Pregel 循环，一切前端语法糖都会豁然开朗。
2. **状态先行，归约显式**。节点之间不自由传消息，而是各自返回一个"部分状态更新"（partial state），由**通道（Channel）+ 归约器（reducer）**决定如何合并。两个节点在同一个超步写同一个"单值"键，引擎会直接抛 `InvalidUpdateError`（`channels/last_value.py:56-67`）——并发冲突被逼到类型系统面前，而不是静默覆盖。
3. **每一步都可存档**。执行循环每个超步结束都落一个 checkpoint（`_loop.py:718` 的 `self._put_checkpoint({"source": "loop"})`）；interrupt、resume、time-travel、跨进程恢复，全部构建在这同一条存档机制上，**没有第二套"暂停恢复"系统**。
4. **低层可控，预制件只是糖**。框架核心对"如何做 agent"零硬编码：`create_react_agent` 就是一个用 `StateGraph` 手拼出来的普通图（`libs/prebuilt/langgraph/prebuilt/chat_agent_executor.py:862-1002`），你完全可以自己拼一个。1.x 中连这个预制件也交给了 `langchain.agents.create_agent`（LangGraph 本体只保留引擎）——官方立场始终是：**引擎薄、生态厚**。

## 1.3 仓库与包分层全景

langchain-ai/langgraph 是一个 uv 管理的 monorepo，写作时（tag 1.2.12）`libs/` 下共 10 个子包。按"用户感知"分层如下：

```
┌────────────────────────────── 部署层 ──────────────────────────────┐
│  langgraph-cli（本地开发：langgraph dev / up / build）               │
│  langgraph-api（LangGraph Server 服务端实现，依赖主包）              │
│  langgraph-sdk（Server API 客户端，主包对其仅有轻依赖）              │
├────────────────────────────── 预制件层 ────────────────────────────┤
│  langgraph-prebuilt（create_react_agent(废弃)/ToolNode/注入注解）    │
│  （更高层生态：langchain.agents.create_agent、langgraph-supervisor、 │
│    langgraph-swarm、deepagents —— 均独立发行，不在本 monorepo）      │
├────────────────────────────── 前端层（两种 API）────────────────────┤
│  langgraph.graph（图 API：StateGraph + START/END + add_messages）   │
│  langgraph.func（函数式 API：@entrypoint + @task）                  │
├────────────────────────────── 引擎层 ──────────────────────────────┤
│  langgraph.pregel（Pregel 主类、PregelLoop、PregelRunner、          │
│    _algo 任务规划/写入应用、_retry 重试、_executor 并发执行器）       │
│  langgraph.stream（1.x 新流式子系统：StreamPart v2 / transformers） │
│  langgraph.managed（内部虚拟状态键：IsLastStep/RemainingSteps）      │
├────────────────────────────── 数据层 ──────────────────────────────┤
│  langgraph.channels（通道：LastValue/BinOp/Ephemeral/Topic/…）      │
│  langgraph.graph.state 的 schema→channel 转换（类型注解即通道）      │
├────────────────────────────── SPI 层（可替换实现）──────────────────┤
│  langgraph-checkpoint（BaseCheckpointSaver/Checkpoint/serde 抽象）  │
│  ├ langgraph-checkpoint-sqlite（SqliteSaver）                       │
│  ├ langgraph-checkpoint-postgres（PostgresSaver，同库 async 版）     │
│  └ （社区版：redis / mongodb / dynamo 等，独立仓库）                 │
│  langgraph.store（BaseStore 长期记忆抽象 + InMemoryStore）           │
│  langgraph.cache（BaseCache 节点缓存抽象）                           │
├────────────────────────────── 地基 ────────────────────────────────┤
│  langchain-core（消息模型 BaseMessage、Runnable 协议——外部依赖）     │
│  xxhash（任务 ID/checkpoint 命名空间的快速哈希）                     │
└──────────────────────────────────────────────────────────────────┘
```

主包 `libs/langgraph/langgraph` 内部的目录一览（`ls` 实测）：

```
langgraph/
├── graph/          state.py(StateGraph/CompiledStateGraph)、message.py(add_messages)、_branch.py、_node.py、ui.py(UI 消息流)
├── channels/       base.py + last_value.py + binop.py + ephemeral_value.py + topic.py + named_barrier_value.py + delta.py + any_value.py + untracked_value.py
├── pregel/         main.py(Pregel, 4300+ 行)、_loop.py(PregelLoop)、_algo.py(apply_writes/prepare_tasks)、_runner.py、_retry.py、_executor.py、_checkpoint.py、_read.py(PregelNode)、_write.py(ChannelWrite)、_messages.py
├── stream/         _types.py(StreamPart)、run_stream.py、_mux.py、transformers.py
├── func/           __init__.py(@entrypoint/@task 全部实现，仅 620 行)
├── checkpoint/     base/ + memory/(MemorySaver) + serde/(JsonPlusSerializer) —— 由 langgraph-checkpoint 包提供
├── store/          base/(BaseStore/Op 族) + memory/(InMemoryStore)
├── cache/          base/(BaseCache) + memory/
├── managed/        is_last_step.py(IsLastStep/RemainingSteps 两个虚拟键)
├── types.py        Send/Command/Interrupt/interrupt()/RetryPolicy/TimeoutPolicy/CachePolicy/StreamPart/GraphOutput —— 全框架的公共词汇表
├── errors.py       GraphRecursionError/GraphInterrupt/InvalidUpdateError/…
├── runtime.py      Runtime/ExecutionInfo/get_runtime —— 运行期上下文包
└── constants.py    START("__start__")、END("__end__") 等内部常量
```

注意一个容易踩的坑：**0.x 时代的经典 `Graph` 构建器在 1.2.12 中已整体移除**（`langgraph/graph/` 下不再有 `graph.py`，全仓 grep 无 `class Graph` 定义）——`MessageGraph` 保留在 `graph/message.py` 但同样式微；官方唯一推荐的主前端就是 `StateGraph`。

## 1.4 包依赖图（以各包 pyproject.toml 实证）

主包 `libs/langgraph/pyproject.toml:26-33` 的运行时依赖实测如下，其余包逐一验证：

| 包 | 直接依赖（运行时） | 说明 |
|---|---|---|
| langgraph (1.2.12) | langchain-core ≥1.4.7,<2；langgraph-checkpoint ≥4.1.0,<5；langgraph-sdk ≥0.4.2,<0.5；langgraph-prebuilt ≥1.1.0,<1.2；xxhash ≥3.5；pydantic ≥2.7.4 | 引擎本体，几乎无重量级依赖 |
| langgraph-prebuilt (1.1.0) | langgraph ≥1.1、langchain-core ≥1.4.7、langchain ≥1.1（create_agent 所在） | 预制件反向依赖主包 |
| langgraph-checkpoint (4.x) | 无强依赖（可选 orjson/ormsgp） | 纯抽象 + serde |
| langgraph-checkpoint-sqlite / -postgres | langgraph-checkpoint | 存储后端插件 |
| langgraph-sdk | httpx/orjson | Server 客户端，可脱离引擎使用 |
| langgraph-cli | langgraph-api、langgraph-sdk | 开发者工具链 |
| @langchain/langgraph (JS 1.4.19) | @langchain/core ≥1.1.48、@langchain/langgraph-checkpoint、@langchain/langgraph-sdk、zod | JS 独立仓库 langgraphjs，npm 实证 |

这张表本身就是一份架构说明：**langgraph 主包是纯引擎**（消息模型交给 langchain-core、持久化交给 langgraph-checkpoint 插件包、预制件经 prebuilt 反向注入）；**checkpoint/store/cache 全部是"抽象在主包、实现可插拔"**的 SPI 形态；**SDK 与引擎同仓库但互不依赖**（`pregel/remote.py` 那种"远端图当本地图用"的能力由 SDK 承担）。

## 1.5 关键问题 → LangGraph 方案映射（全文导览）

| 智能体开发的关键问题 | LangGraph 的方案 | 详见 |
|---|---|---|
| 控制流有环、有分支，Chain/管道模型表达不了 | StateGraph 显式建模：节点 + 边 + 条件边，Pregel BSP 循环执行 | 第二章、第四章 |
| 两个节点并发写同一个状态键怎么办 | Channel 体系：单值键冲突即报错（LastValue），集合键必须声明归约器（Annotated + reducer → BinaryOperatorAggregate） | 第二章 |
| 多步 LLM 任务中断后要从头跑吗 | 每超步落 checkpoint：thread/checkpoint_ns/checkpoint_id 三元组寻址，断点续跑（durable execution） | 第五章 |
| 想让人类审批/修改中间结果 | `interrupt()` 函数 + `Command(resume=...)`：异常上浮→存档→人工答复→节点重放；另有 interrupt_before/after 声明式停点 | 第六章 |
| 状态更新对用户不可见、看不到过程 | 七种 stream_mode（values/updates/messages/custom/checkpoints/tasks/debug）+ 1.1 的类型安全 v2 流（StreamPart/GraphOutput） | 第七章 |
| 两个工具调用想并行、子任务想动态扇出 | Send API（动态 map-reduce）与 superstep 内天然并行（PregelRunner + 线程池/事件循环） | 第四章 |
| 节点内部想直接"goto"另一节点并顺手改状态 | Command(goto=..., update=...)：写入与跳转合一，支持跳父图（Command.PARENT） | 第三章、第四章 |
| 不想画图，就想写个带恢复能力的函数 | Functional API：@entrypoint/@task，编译成"单节点 Pregel"，previous 键承载跨调用记忆 | 第八章 |
| 对话记忆（同线程多轮）与长期记忆（跨线程检索） | 短期=checkpointer（thread 维度），长期=Store（namespace 维度 + 语义检索） | 第九章 |
| 快速搭一个标准 ReAct 智能体 | langchain.agents.create_agent（1.x 官方入口，middleware 体系）；兼容层的 create_react_agent/ToolNode 仍在 langgraph-prebuilt | 第十章 |
| 从原型到上线的鸿沟 | 同一份图代码可跑在进程内，也可交给 LangGraph Server/Platform（threads/runs/cron API + Studio 调试） | 第十一章 |
| LLM 调用抖动、超时 | RetryPolicy（指数退避+抖动）、TimeoutPolicy（硬墙钟 + 空闲心跳，1.2）、error_handler 兜底节点（1.2） | 第四章、第十三章 |

## 1.6 版本演进：0.x → 1.0 → 1.2 关键变化对比

写作时（2026 年 10 月）的版本格局：**1.2.x 是稳定主线**（1.2.12，2026-09-21），1.0/1.1 仍在同主线维护；0.x 已停止维护。JS 版独立发版（写作时 1.4.19，特性与 Python 大体对齐但版本号不同步）。本节所有"特性属于哪个版本"的结论都经过双重验证：**① 对 PyPI 历史 wheel（0.2.24 / 0.2.60 / 1.1.0）解包 grep 实证；② GA 日期取自 GitHub Release API 与 PyPI 上传时间交叉核对**。

### 1.6.1 版本时间线

| 版本 | 发布日期 | Python 基线 | 一句话主题 |
|---|---|---|---|
| 0.0.8（PyPI 可考首个） | 2024-01-08 | ≥3.9 | 项目启动期：StateGraph + Pregel 循环 + checkpoint 雏形，两周一发 |
| 0.1.x | 2024 上半年（0.1.9 = 2024-07-18） | ≥3.9 | 图 API 稳定化，与 LangChain 生态对接 |
| 0.2.0 | 2024-08-07 | ≥3.9 | "从黑客松到生产"：异步一等公民、`add_messages`/MessagesState、工程化转向 |
| 0.2.24 | 2024-09-23 | ≥3.9 | `Interrupt` 数据类、`RetryPolicy`、`CachePolicy`（wheel 实证） |
| 0.2.60 | 2024-12-18 | ≥3.9 | `interrupt()` 函数已就位（与旧 `NodeInterrupt` 并存，wheel 实证）；NodeInterrupt 进入废弃倒计时 |
| 0.3.0 | 2025-02-26 | ≥3.9 | 对齐 langchain-core 0.3；checkpoint 包拆分稳定（langgraph-checkpoint ≥0.1 独立发版） |
| 0.4.0 | 2025-04-29 | ≥3.9 | `Interrupt.interrupt_id` 属性引入 |
| 0.5.0 / 0.6.0 | 2025-06-26 / 2025-07-28 | ≥3.9 | `context_schema` 取代 `config_schema`（源码 `LangGraphDeprecatedSinceV10` 废弃注解实证）；Durability 模式（sync/async/exit）定型 |
| **1.0.0** | **2025-10-17** | **≥3.10** | **GA**：API 稳定承诺；放弃 Python 3.9（rc1 实证）；ReAct 智能体入口让位于 `langchain.agents.create_agent`（langchain 1.0，2025-10-22，middleware 体系）；`create_react_agent` 标记废弃 |
| 1.1.0 | 2026-03-10 | ≥3.10 | `version="v2"` 类型安全流式输出（`StreamPart`/`GraphOutput`，invoke 返回强类型）；子图 replay 行为修复 |
| 1.2.0 | 2026-05-12 | ≥3.10 | `set_node_defaults`（全局重试/缓存/超时/兜底默认值）；`error_handler` 兜底节点与跨主机崩溃恢复；`TimeoutPolicy`（1.1.0 wheel 无此类型 → 1.2 引入）；`DeltaChannel` 增量检查点（beta） |
| 1.2.12（本文版本） | 2026-09-21 | ≥3.10 | 稳定补丁线 |

### 1.6.2 特性引入版本对照表（可复现实证）

| 特性 | 引入版本 | 实证方式 | 详见 |
|---|---|---|---|
| StateGraph / Pregel 引擎 / LastValue 通道 | 0.0.x（2024-01） | PyPI 0.0.8 可考 | 二、四章 |
| checkpoint（MemorySaver 等） | 0.0.x，2024-08 起 `langgraph-checkpoint` 独立成包 | PyPI | 五章 |
| `Interrupt` 数据类 / `RetryPolicy` / `CachePolicy` | 0.2.24 | 0.2.24 wheel 解包 grep | 四、六章 |
| `interrupt()` 函数 + resume 映射 | 0.2.3x~0.2.60 之间 GA | 0.2.24 wheel 无 `def interrupt(`、0.2.60 wheel 有 | 六章 |
| Functional API（@entrypoint/@task） | 0.2.3x（2024-09 官宣） | 官方博客 + 0.2.60 wheel 已含 `langgraph/func/__init__.py` | 八章 |
| Store（长期记忆 BaseStore/InMemoryStore） | 0.2 后期（2024 末） | store 自 checkpoint 拆包起随 langgraph-checkpoint 系发布（0.2.60 主包 wheel 无 BaseStore，与今天的包结构一致） | 九章 |
| Durability（sync/async/exit 三档） | 0.5~0.6 间引入 | 0.4.0 wheel 无 `Durability`、1.1.0 wheel 有（可复现） | 五章 |
| `context_schema`（运行期上下文） | 0.6.0（替代 config_schema） | `state.py:155-157` 废弃说明 | 九章 |
| **1.0：API 冻结 + Py≥3.10 + agent 入口外移** | 1.0.0（2025-10-17） | 1.0.0rc1 Release Notes（"drop Python 3.9"、"rename away from LangGraph Platform"） | 十章 |
| `StreamPart`/`GraphOutput`（v2 类型安全流） | 1.1.0 | 1.1.0 wheel 解包 + 1.1.0 Release Notes（#6961） | 七章 |
| `set_node_defaults` / `error_handler` / `TimeoutPolicy` / `DeltaChannel`(beta) | 1.2.0 | 1.2.0 Release Notes（#7747/#7773）+ 1.1.0 wheel 反证 | 四、五章 |
| 经典 `Graph` 构建器 | 0.x 存在 → **1.x 已删除**（仅剩 `MessageGraph`） | 0.2.60 wheel 含 `graph/graph.py`；1.2.12 源码目录无此文件 | 三章 |
| `NodeInterrupt` 类 | 0.2.x → **1.x 已删除** | 0.2.60 wheel `errors.py` 有；1.2.12 无 | 六章 |
| `MainQueueThreadPool` 执行器 | 0.x 存在 → 1.x 换成 `BackgroundExecutor`/`AsyncBackgroundExecutor` | 全仓 grep 仅历史注释 | 四章 |

### 1.6.3 三大版本阶段主题对比

| 维度 | 0.x（2024-01~2025-10） | 1.0/1.1（2025-10~2026-03） | 1.2（2026-05~） |
|---|---|---|---|
| 定位 | 快速试错，API 每周可能变 | **API 稳定承诺**（语义化版本），引擎冻结 | 引擎上"长时运行可靠性"补课 |
| 智能体入口 | `create_react_agent`（prebuilt） | `langchain.agents.create_agent` + middleware；`create_react_agent` 废弃壳 | 同左；prebuilt 提供 ToolNode/注入注解 |
| 人的介入 | `interrupt()` + `Command(resume=)` 奠基 | 语义微调 | `error_handler` 节点、崩溃后恢复 |
| 持久化 | 全量 checkpoint | 不变 | `DeltaChannel` 增量快照（beta）、`TimeoutPolicy` 心跳 |
| 流式 | v1 裸元组 `(mode, data)`，interrupt 混在 `"__interrupt__"` 键 | **v2 类型安全流**（可选开启） | v2 继续打磨（transformers 扩展点） |
| 节点策略 | 逐节点配置 retry/cache | 同左 | `set_node_defaults` 图级默认 + per-node 覆盖 |

### 1.6.4 对初学者的意义：哪些教程过时了，哪些永远有效

- **过时/新项目避免**：`create_react_agent` 直接调用（改用 `langchain.agents.create_agent` 或显式 `@deprecated` 认知）、`config_schema`、经典 `Graph`/`MessageGraph`、`NodeInterrupt`、Py3.9。
- **0.x 老教程里永久有效的部分**：StateGraph 建模、`Annotated[type, reducer]` 状态、`add_conditional_edges`、checkpointer + thread_id、`interrupt()`/`Command(resume=...)`、Send/Command——这些从 0.2 到 1.2 骨架未变，本文第二~六章正是按这条主线写的。
- **版本对应关系**（选教材对号入座）：2024 年教程 ↔ 0.2.x；2025 年教程 ↔ 0.6~1.0；2026 年教程 ↔ 1.1/1.2。

## 1.7 全文章节地图

- **第二章 State 与 Channel（一切的地基）**：schema 类型注解如何"编译"成通道；BaseChannel 六方法契约与六个实现；`add_messages` 的合并语义；并发冲突与 Overwrite。
- **第三章 图的构建与编译（graph/state.py）**：StateGraph 的六件套数据结构；add_node/add_edge/条件边/join 边的语义；compile() 全流程与 attach_node/attach_edge/attach_branch 的"翻译"过程；PregelNode 的最终形态。
- **第四章 Pregel 执行引擎**：BSP 三阶段；一次 invoke 的主循环源码走读；任务规划（PULL/PUSH、task_id 生成）；并发执行与 error_handler；apply_writes 的版本号机制；Send/Command 的引擎层落地；recursion_limit。
- **第五章 Checkpoint 持久化**：Checkpoint 结构（v/id/channel_values/channel_versions/versions_seen）；BaseCheckpointSaver 契约与 serde；MemorySaver/Sqlite/Postgres；写入时机与 Durability 三档、DeltaChannel；thread/checkpoint_ns 命名空间；time-travel。
- **第六章 人在环路（interrupt）**：interrupt() 源码走读（scratchpad/resume map）；GraphInterrupt 异常的上浮与恢复全链路；节点重放语义与副作用陷阱；interrupt_before/after。
- **第七章 流式输出**：七种 stream_mode 与 v2 StreamPart；引擎内的流管道（DuplexStream/_mux/transformers）；stream_writer 与 subgraphs 流。
- **第八章 Functional API**：@entrypoint 的编译产物（单节点 Pregel + PREVIOUS 通道）；@task 的 future 与断点续跑；entrypoint.final；与图 API 的取舍。
- **第九章 记忆体系**：短期（checkpoint/thread）与长期（Store/namespace）的分工；BaseStore 契约与语义检索；Runtime 与 context_schema。
- **第十章 预制件与多智能体**：create_react_agent（废弃壳）的图结构解剖；ToolNode 全流程（并行执行/注入/错误处理）；HIL 类型；supervisor/swarm/deepagents 生态。
- **第十一章 部署形态**：库形态 vs Server/Platform 形态；cli/api/sdk 三包分工；threads/runs/cron 概念；Studio。
- **第十二章 贯通视图**：编译期、一次 invoke 引擎循环、跨调用一段对话——三条时间线叠加成全景。
- **第十三章 工程实践与常见坑**：选型决策树；并发写冲突、节点副作用、序列化、checkpoint 版本迁移等高频坑。
- **第十四章 附录**：关键类速查表、学习路线、源码阅读入口清单。

---

# 二、一切的地基：State 与 Channel（通道）

> 本章对应源码：`libs/langgraph/langgraph/graph/state.py`（schema → 通道转换）、`libs/langgraph/langgraph/channels/`（通道实现）、`libs/langgraph/langgraph/graph/message.py`（消息状态）。行号均为 1.2.12 实读。

## 2.1 先说白话：节点之间到底怎么"传值"？

LangGraph 里节点不互相调用、不传参数。每个节点读一份"当前共享状态"，返回一个"部分更新"，剩下的合并工作由框架完成。这立刻引出三个问题：

1. **共享状态长什么样？**——由你声明的 schema（TypedDict/Pydantic/dataclass）决定，每个字段就是一个"槽位"。
2. **两个节点在同一超步（superstep）写了同一个槽位怎么办？**——单值槽位直接报错；想要"都保留"就必须显式声明归约器（reducer，如 `operator.add`）。
3. **这套状态怎么被引擎执行？**——每个字段会被"编译"成一个**通道（Channel）对象**，通道才是引擎真正操作的实体；schema 只是它的"声明式外衣"。

一句话：**State 是给人看的类型声明，Channel 是给引擎用的运行时实体，第二章讲的就是两者之间的翻译器。**

## 2.2 schema 类型注解 → 通道的"编译"

【源码证据】`graph/state.py:1815-1835`，`_get_channels` 是翻译入口——对 schema 的每个类型注解调用 `_get_channel`：

```python
def _get_channels(schema) -> tuple[dict[str, BaseChannel], dict[str, ManagedValueSpec], dict[str, Any]]:
    if not hasattr(schema, "__annotations__"):
        return (
            {"__root__": _get_channel("__root__", schema, allow_managed=False)},
            {}, {},
        )
    type_hints = get_type_hints(schema, include_extras=True)
    all_keys = {name: _get_channel(name, typ) for name, typ in type_hints.items() ...}
    return (
        {k: v for k, v in all_keys.items() if isinstance(v, BaseChannel)},
        {k: v for k, v in all_keys.items() if is_managed_value(v)},
        type_hints,
    )
```

注意第一个分支：**schema 如果连 `__annotations__` 都没有（比如就是 `int`、`list[str]` 这种裸类型），整个图的状态退化为单个 `__root__` 通道**——这就是经典 `Graph`/MessageGraph 风格的"消息即状态"。`__root__` 这个键名在后文 attach_node、entrypoint 编译里都会反复出现。

【源码证据】`graph/state.py:1850-1873`，`_get_channel` 的三分支判定：

```python
def _get_channel(name, annotation, *, allow_managed=True) -> BaseChannel | ManagedValueSpec:
    if manager := _is_field_managed_value(name, annotation):   # ① 内部虚拟键
        ...
    elif channel := _is_field_channel(annotation):             # ② Annotated[T, 某个BaseChannel实例]
        channel.key = name
        return channel
    elif channel := _is_field_binop(annotation):               # ③ Annotated[T, 二元函数] → 归约器
        channel.key = name
        return channel
    fallback: LastValue = LastValue(annotation)                # ④ 兜底：单值通道
    fallback.key = name
    return fallback
```

四种情形各举一例，覆盖日常写法的全部形态：

```python
class State(TypedDict):
    # ④ 兜底：裸类型 → LastValue（一个超步只允许一个写入者）
    query: str
    # ③ 二元函数注解 → BinaryOperatorAggregate：多个节点的返回值用该函数逐个归约
    jokes: Annotated[list[str], operator.add]
    # ② 显式通道实例：add_messages 本身是工厂，返回一个配置好的 BinaryOperatorAggregate
    messages: Annotated[list[AnyMessage], add_messages]
    # ① 内部虚拟键（managed value）：不占通道、不进 checkpoint，读取时现算
    remaining_steps: RemainingSteps          # Annotated[int, RemainingStepsManager]
```

【源码证据】`graph/state.py:1904-1922`，`_is_field_binop` 用 `inspect.signature` 校验归约器**必须恰好是两个位置参数**（`(a, b) -> c`），否则构造期就报 `ValueError: Invalid reducer signature`——错误在 `compile()` 时暴露，而不是运行到一半才炸。另注意 `binop.py:82-92`：`Annotated[list[str], operator.add]` 的类型参数 `list[str]` 会被 `_strip_extras` 剥掉参数化后实例化为 `list()` 作为归约初值。

最后一条约束：**Input/Output schema 不允许 managed 键**（`state.py:343-354`，`_add_schema(schema, allow_managed=False)` 时发现 managed 直接 `ValueError`）——因为子图的输入输出要跨边界传递，而 managed 值本质是"运行时函数"，不可序列化。

## 2.3 Channel 接口契约：BaseChannel 的六个方法

【源码证据】`channels/base.py:19-121`，所有通道的公共父类（泛型参数是 `<值类型, 更新类型, checkpoint类型>`）：

```python
class BaseChannel(Generic[Value, Update, Checkpoint], ABC):
    @abstractmethod
    def get(self) -> Value: ...            # 读当前值；空通道抛 EmptyChannelError
    @abstractmethod
    def update(self, values: Sequence[Update]) -> bool:
        """本超步该通道收到的全部更新（顺序任意），由 Pregel 在每步末尾调用。"""
    @abstractmethod
    def from_checkpoint(self, checkpoint) -> Self: ...   # 从存档恢复
    def checkpoint(self) -> Checkpoint | Any:            # 导出可序列化快照
    def consume(self) -> bool: ...         # 引擎通知"订阅者已消费"（供一次性通道覆写）
    def finish(self) -> bool: ...          # 引擎通知"整个 run 收尾"（供出口通道覆写）
```

这份契约值得逐条咀嚼：

- **`update(values)` 收到的是"本超步所有写入的集合"**——归约因此天然是批量的：`operator.add` 面对同超步 3 个节点的写入，做的是 `reduce(add, [v1, v2, v3])`，而不是三步三次。
- **`consume()` / `finish()` 是两个可选的"生命周期回调"**：`EphemeralValue` 靠引擎在超步末对"读过但没更新"的通道补发 `update(())` 实现自焚（见 4.5 节 `apply_writes` 的 bump_step 段）；`LastValueAfterFinish` 靠 `finish()` 实现延迟可见。
- **`checkpoint()/from_checkpoint()` 决定持久化形态**：通道自己决定"哪些东西值得存档"——`EphemeralValue.checkpoint()` 返回当前值但引擎在恢复后立即清空它，`UntrackedValue` 干脆返回 `MISSING` 不存档。

## 2.4 通道实现盘点：七个内置通道

| 通道 | 文件 | 语义 | 典型来源 |
|---|---|---|---|
| `LastValue` | `channels/last_value.py:20` | 存最后一个值；**一个超步收到 >1 个更新立即抛 `InvalidUpdateError`** | 裸类型字段（兜底） |
| `BinaryOperatorAggregate` | `channels/binop.py:65` | 用二元函数逐个归约所有更新 | `Annotated[T, reducer]` |
| `EphemeralValue` | `channels/ephemeral_value.py:15` | 只存活一个超步，读完即焚 | `START` 输入通道、`branch:to:X` 触发通道 |
| `Topic` | `channels/topic.py:23` | PubSub 多值广播，`accumulate=False` 每步清空 | Send 的 TASKS 通道、经典 Graph |
| `NamedBarrierValue` | `channels/named_barrier_value.py` | 命名栅栏：等所有指名写入者到齐才放行 | 多起点 `add_edge([a,b], c)` 的 `join:` 通道 |
| `LastValueAfterFinish` | `channels/last_value.py:81` | 写入后不可读，`finish()` 后可见、消费即清 | `defer=True` 节点的触发通道 |
| `DeltaChannel` | `channels/delta.py`（1.2 新增，beta） | 只写增量 + 定期全量快照，checkpoint 记增量 | `Annotated[T, DeltaChannel(reducer, ...)]` |

【源码证据】`LastValue` 的冲突检测（`channels/last_value.py:56-67`）——这是初学者最常撞见的报错源头：

```python
def update(self, values: Sequence[Value]) -> bool:
    if len(values) == 0:
        return False
    if len(values) != 1:
        msg = create_error_message(
            message=f"At key '{self.key}': Can receive only one value per step. "
                    "Use an Annotated key to handle multiple values.",
            error_code=ErrorCode.INVALID_CONCURRENT_GRAPH_UPDATE,
        )
        raise InvalidUpdateError(msg)
    self.value = values[-1]
    return True
```

错误信息本身就是修复指引：把字段改成 `Annotated[T, reducer]`。`BinaryOperatorAggregate.update`（`binop.py:123-144`）则相反——来者不拒，逐个 `self.value = self.operator(self.value, value)`；它同时处理 `Overwrite` 包裹值（见 2.6），且规定**一个超步最多接受一个 `Overwrite`**（`binop.py:133-138`，两个就报并发冲突）。

`NamedBarrierValue` 支撑的是**多源 join 语义**：`add_edge(["a", "b"], "c")` 编译时会创建 `join:a+b:c` 通道，a、b 各写一个自己的名字，通道集齐 `{"a","b"}` 后才 `is_available()`，从而触发 c——这就是"等所有前置节点完成"的引擎实现（编译侧见 3.6 节 `state.py:1560-1575`）。

## 2.5 add_messages：消息状态的标准归约器

对话场景太常见，LangGraph 把"消息列表怎么合并"沉淀成一个公共归约器。

【源码证据】`graph/message.py:61-244`（函数体核心逻辑 187-234 摘录）：

```python
def add_messages(left: Messages, right: Messages, *, format=None) -> Messages:
    ...
    # 为缺 id 的消息补 uuid4
    for m in left:
        if m.id is None: m.id = str(uuid.uuid4())
    ...
    # RemoveMessage.id == REMOVE_ALL_MESSAGES → 清空后只保留其后的消息
    if remove_all_idx is not None:
        return right[remove_all_idx + 1 :]
    # 按 id 合并：同 id 覆盖（upsert），RemoveMessage 按 id 删除，否则追加
    merged = left.copy()
    merged_by_id = {m.id: i for i, m in enumerate(merged)}
    for m in right:
        if (existing_idx := merged_by_id.get(m.id)) is not None:
            if isinstance(m, RemoveMessage):
                ids_to_remove.add(m.id)
            else:
                ids_to_remove.discard(m.id)
                merged[existing_idx] = m
        else:
            merged.append(m)
    merged = [m for m in merged if m.id not in ids_to_remove]
```

要点四条：

1. **默认追加、同 id 即更新**——这让"流式更新某条消息"（AIMessageChunk 同 id 不断到达）在状态里表现为替换而非堆积。
2. **删除用哨兵消息**：`RemoveMessage(id=...)` 删单条，`RemoveMessage(id=REMOVE_ALL_MESSAGES)` 清空（`message.py:38`、209-213）。
3. `format="langchain-openai"` 可在归约时顺手把 content 规整成 OpenAI 风格块（`message.py:236-237`）。
4. `MessagesState`（`message.py:372`）就是"只带 `messages: Annotated[list[AnyMessage], add_messages]` 一个字段的现成 TypedDict"；1.2 又为 DeltaChannel 路线补了 `_messages_delta_reducer`（`message.py:247`）。

【源码证据】删除哨兵与全量清空的常量：`graph/message.py:38`：

```python
REMOVE_ALL_MESSAGES = "__remove_all__"
```

## 2.6 并发冲突与 Overwrite：归约器的"逃生门"

归约器统一了并发写，但也带来一个烦恼：**想在某一步"整体替换"而不是"追加归约"怎么办？** 1.2 给出的答案是 `Overwrite` 信封。

【源码证据】`types.py:1032-1073`（`Overwrite` 数据类）+ `binop.py:123-144`（通道侧识别）：

```python
@dataclass(slots=True)
class Overwrite:
    """Bypass a reducer and write the wrapped value directly to a BinaryOperatorAggregate channel."""
    value: Any
    type: Literal["__overwrite__"] = "__overwrite__"

# binop.py:139-141（update 内）
if is_overwrite:
    if seen_overwrite:      # 同超步第二个 Overwrite → 并发冲突
        raise InvalidUpdateError(...)
    self.value = overwrite_value    # 直接替换，绕过 operator
    seen_overwrite = True
    continue
```

`type: Literal["__overwrite__"]` 判别字段的存在（`types.py:1075-1079` docstring 解释）是为了让 `Overwrite` **经过 JSON 序列化（如 ormsgpack 编码、经 LangGraph Server 转发）后仍能被认出来**——`binop.py:31-51` 的 `_get_overwrite` 同时识别 dataclass 实例、`{"__overwrite__": v}`、`{"type": "__overwrite__", "value": v}` 三种形态。这是"分布式边界不丢语义"的一个漂亮细节。

## 2.7 本章小结

- schema 的每个字段注解被 `_get_channels` 编译成一个 Channel：裸类型→`LastValue`，`Annotated[T, 二元函数]`→`BinaryOperatorAggregate`，`Annotated[T, 通道实例]`→按实例，managed 键不占通道。
- `BaseChannel` 的六方法契约（get/update/checkpoint/from_checkpoint/consume/finish）是"批量归约 + 生命周期回调 + 自描述存档"的最小接口，全框架只有这一套通道抽象。
- 七个内置通道覆盖：单值（LastValue）、聚合（BinOp）、一次性（Ephemeral）、广播（Topic）、栅栏（NamedBarrier）、延迟出口（LastValueAfterFinish）、增量（DeltaChannel）。
- `add_messages` 是最常用的归约器：追加 + id 级 upsert + RemoveMessage 删除。
- 并发写单值键在引擎层被禁止（`InvalidUpdateError`），想覆盖用 `Overwrite` 信封且每超步限一个。

---

# 三、图的构建与编译（graph/state.py）

> 本章对应源码：`libs/langgraph/langgraph/graph/state.py`（1978 行，StateGraph 与 CompiledStateGraph 全在此）、`libs/langgraph/langgraph/graph/_branch.py`（条件边运行时）、`libs/langgraph/langgraph/pregel/_read.py`（PregelNode）、`libs/langgraph/langgraph/pregel/_write.py`（ChannelWrite）。

## 3.1 先说白话：builder 是图纸，compile 是"翻译"

`StateGraph` 是纯 builder：`add_node/add_edge` 只往几个集合里塞数据，不做任何执行准备。真正的工作发生在 `compile()`——把"图纸"翻译成引擎认识的机器件：每个节点变成一个 `PregelNode`（订阅哪些通道、读哪些键、写出什么），每条边变成"往对方的触发通道写一笔"，每个条件边变成一个挂在节点 writers 上的"分支函数"。**编译产物 `CompiledStateGraph` 直接继承自 `Pregel`**（`state.py:1404-1407`）——你调用的 `invoke/stream` 从这一刻起就是第四章那台引擎的方法。

```
StateGraph（图纸）                    CompiledStateGraph = Pregel（机器）
├─ nodes: {名: StateNodeSpec}   ──►  ├─ nodes: {名: PregelNode(triggers/channels/writers)}
├─ edges / waiting_edges        ──►  ├─ writers 追加 ChannelWrite(branch:to:目标)
├─ branches: {源: {条件: BranchSpec}} ─► ├─ writers 追加 branch.run(...)（条件路由）
└─ channels（schema 编译产物）   ──►  └─ channels（+ branch:to:X、join:X、START 等内部通道）
```

## 3.2 StateGraph 的六件套数据结构

【源码证据】`graph/state.py:202-214`：

```python
class StateGraph(Generic[StateT, ContextT, InputT, OutputT]):
    edges: set[tuple[str, str]]                          # (起点, 终点) 二元组集合
    nodes: dict[str, StateNodeSpec[Any, ContextT]]       # 节点名 → 规格说明书
    branches: defaultdict[str, dict[str, BranchSpec]]    # 节点名 → {条件名: 分支规格}
    channels: dict[str, BaseChannel]                     # schema 编译出的通道
    managed: dict[str, ManagedValueSpec]                 # 虚拟键
    schemas: dict[type, dict[str, BaseChannel | ManagedValueSpec]]  # state/input/output 三份
    waiting_edges: set[tuple[tuple[str, ...], str]]      # 多起点 join 边
```

三个 schema（state/input/output）在构造时就分别 `_add_schema`（`state.py:261-270`），编译成三份"键 → 通道"映射；input/output 与 state 不同时（`StateGraph(State, input_schema=Input, output_schema=Out)`），就实现了**入口裁剪与出口裁剪**——用户 invoke 时只需给 Input 里的键，invoke 完只返回 Output 里的键。

`add_node` 支持四种重载（函数、`(名字, 函数)` 元组、Runnable、内容块形式），并接受一整套**节点策略参数**（`state.py:376-390`）：`defer`（推迟到收尾）、`metadata`、`input_schema`、`retry_policy`、`cache_policy`、`error_handler`、`destinations`（仅用于画图）、`timeout`、`trace_policy`。1.2 新增的 `set_node_defaults`（`state.py:272-335`）给这些策略提供**图级默认值**，per-node 参数永远优先；`compile()` 时还会自动注册一个 `__error_handler__` 兜底节点（`state.py:1292-1310`，`_DEFAULT_ERROR_HANDLER_NODE`），把未指定 handler 的普通节点的失败路由过去（重试/超时默认值对 handler 节点也生效，但 handler 自身不缓存、不嵌套 handler——注释里的理由：handler 的输入是"失败节点所在状态"，缓存它不安全）。

## 3.3 构建 API 的语义细节

【源码证据】`graph/state.py:928-980`，`add_edge` 单起点与多起点走完全不同的两条路：

```python
if isinstance(start_key, str):
    ...
    self.edges.add((start_key, end_key))       # 单起点：普通边
    return self
for start in start_key: ...                    # 多起点：先校验都存在
self.waiting_edges.add((tuple(start_key), end_key))   # join 边，存进 waiting_edges
```

`add_conditional_edges(source, path, path_map)` 把路由函数包成 `BranchSpec` 存入 `branches[source]`（`state.py:982-1030`）；`add_sequence` 是 `add_node + add_edge` 链式糖；`validate()`（`state.py:1129-1175`）做静态检查：入口必须存在（`START not in all_sources` 报错）、边两端点必须已注册、`interrupt` 列表中的节点必须存在。**所有这类错误都在 compile 期暴露**——这是"图先声明后执行"模型的隐含福利。

## 3.4 compile() 全流程

【源码证据】`graph/state.py:1177-1401`，主干步骤（编号为源码顺序）：

```
compile(checkpointer, cache, store, interrupt_before/after, debug, name, transformers)
 ├─ ① ensure_valid_checkpointer                  L1231  接受 BaseCheckpointSaver / True / False / None
 ├─ ② serde 白名单（STRICT_MSGPACK 启用时）       L1233-1254  按 schema 集合构建允许反序列化的类型清单
 ├─ ③ validate(interrupt=...)                    L1260-1267
 ├─ ④ 计算 output_channels / stream_channels     L1269-1286  过滤 managed 键；__root__ 单通道特判
 ├─ ⑤ 落实节点默认值 + 注册兜底 error handler 节点  L1290-1344
 ├─ ⑥ 构造 CompiledStateGraph                    L1346-1370
 │      channels = {**schema 通道, **managed, START: EphemeralValue(input_schema)}
 ├─ ⑦ attach_node(START, None) + attach_node(每个节点)   L1373-1375
 ├─ ⑧ 记录 v2 流的输出/状态 coerce mapper（pydantic/dataclass） L1377-1389
 ├─ ⑨ attach_edge(每条边) + attach_edge(每条 join 边)     L1391-1395
 ├─ ⑩ attach_branch(每个条件边)                   L1397-1399
 └─ ⑪ compiled.validate()                         L1401
```

第⑥步的 `START: EphemeralValue(self.input_schema)` 值得注意：**输入也被建模成一条通道**——`graph.invoke({"x": 1})` 时，引擎先把输入写进 `__start__` 通道，"订阅 `__start__` 的节点"（即入口节点）自然被触发。一个统一的触发模型，不为输入单开小灶。

## 3.5 attach_node：把节点翻译成 PregelNode

【源码证据】`graph/state.py:1444-1547`。先看节点的"写出器"（writers）怎么构造——这是"节点返回值如何落到通道"的答案：

```python
def _get_updates(input) -> Sequence[tuple[str, Any]] | None:
    if input is None: return None
    elif isinstance(input, dict):
        return [(k, v) for k, v in input.items() if k in output_keys]      # 只写 output_keys
    elif isinstance(input, Command):
        if input.graph == Command.PARENT: return None        # 给父图的更新不写本图通道
        return [(k, v) for k, v in input._update_as_tuples() if k in output_keys]
    elif isinstance(input, (list, tuple)) and any(isinstance(i, Command) for i in input):
        ...  # Command 与 dict 混排逐个展开
    elif (t := type(input)) and get_cached_annotated_keys(t):
        return get_update_as_tuples(input, output_keys)      # pydantic/dataclass 更新
    else:
        raise InvalidUpdateError("Expected dict, got ...")   # 返回类型不对 → 编译期类型之外的运行时防线
```

然后是每个普通节点得到的 `PregelNode`（`state.py:1525-1547`）：

```python
branch_channel = _CHANNEL_BRANCH_TO.format(key)          # "branch:to:{节点名}"
self.channels[branch_channel] = (
    LastValueAfterFinish(Any) if node.defer else EphemeralValue(Any, guard=False)
)
self.nodes[key] = PregelNode(
    triggers=[branch_channel],                            # 被谁的写入唤醒
    channels=("__root__" if is_single_input else input_channels),  # 读哪些键
    mapper=mapper,                                        # dict → pydantic/dataclass 强转
    writers=[ChannelWrite(write_entries)],                # 返回值 → 状态键 + 控制通道
    metadata=..., retry_policy=..., cache_policy=..., bound=node.runnable, timeout=..., ...
)
```

三个关键设计：

1. **节点从不被"直接调用"，而是被"触发通道唤醒"**。前驱的 writers 会往 `branch:to:{本节点}` 写一笔（见 3.6），本节点的 `triggers=[branch_channel]` 收到信号即进入下一超步的任务集。触发通道用 `EphemeralValue(guard=False)`——一次性信号，读完即焚。
2. **读取集 = 节点 input_schema 的全部键**。默认就是整个状态；`add_node(fn, input_schema=MySubset)` 可以给节点"视野瘦身"。
3. **mapper 做状态强转**：state 声明为 pydantic/dataclass 时，每次读状态都经 `_coerce_state`（`state.py:1745-1746` 的 `schema(**input)`）构造模型实例——节点拿到的是类型化对象而非裸 dict（这就是 v2 流能做类型 coerce 的同一机制，`_pick_mapper` 在 compile 期按 schema 缓存，`state.py:1519-1523`）。

START 节点也被"节点化"（`state.py:1508-1514`）：`PregelNode(tags=[TAG_HIDDEN], triggers=[START], channels=START, writers=[ChannelWrite(write_entries)])`——它把 `__start__` 里的输入搬运到各个状态键。TAG_HIDDEN（`constants.py:26`，值即 `"langsmith:hidden"`）让它对追踪与流不可见。

## 3.6 attach_edge / attach_branch：边的两种编译形态

【源码证据】`graph/state.py:1551-1575`：

```python
def attach_edge(self, starts, end) -> None:
    if isinstance(starts, str):
        if end != END:      # 普通边：起点 writers 追加一条"往 branch:to:end 写 None"
            self.nodes[starts].writers.append(
                ChannelWrite((ChannelWriteEntry(_CHANNEL_BRANCH_TO.format(end), None),))
            )
    elif end != END:        # join 边：建 join 通道 + 终点订阅 + 各起点写自己的名字
        channel_name = f"join:{'+'.join(starts)}:{end}"
        self.channels[channel_name] = (
            NamedBarrierValueAfterFinish(str, set(starts)) if self.builder.nodes[end].defer
            else NamedBarrierValue(str, set(starts))
        )
        self.nodes[end].triggers.append(channel_name)
        for start in starts:
            self.nodes[start].writers.append(
                ChannelWrite((ChannelWriteEntry(channel_name, start),))
            )
```

**"边"在引擎里根本不存在，存在的只是"往对方触发通道写一笔"**。普通边与 join 边的差别只是：后者要等多方的命名写入到齐（栅栏语义）。`END` 是个虚拟节点——不 attach 任何东西，写往 `branch:to:__end__` 的信号无人订阅，循环自然终止。

条件边（branch）更复杂：它在源节点的 writers 上追加一个"分支函数"（`attach_branch`，`state.py:1577-1624`），该函数运行时先 `ChannelRead.do_read` 读一份状态（fresh=True，**会把自己节点本次的写入也叠加进去**——条件边看到的是"假设我这些更新生效后"的状态，`_algo.py:188-224` 的 `local_read`），然后调用你的 path 函数得到目标（字符串、字符串列表或 `Send` 列表），最后写往各目标的 `branch:to:X` 通道或 TASKS 通道。

【源码证据】控制信号怎么混进普通写入——`_control_branch`（`graph/state.py:1749-1775`）：

```python
def _control_branch(value: Any) -> Sequence[tuple[str, Any]]:
    if isinstance(value, Send):
        return ((TASKS, value),)                     # Send → 写进 __pregel_tasks 广播通道
    ...
    for command in commands:
        if command.graph == Command.PARENT:
            raise ParentCommand(command)             # 跳父图 → 上抛特殊异常
        for go in goto_targets:
            if isinstance(go, Send):
                rtn.append((TASKS, go))
            elif isinstance(go, str) and go != END:
                rtn.append((_CHANNEL_BRANCH_TO.format(go), None))   # goto → 触发目标节点
    return rtn
```

这就是 `Command(goto=..., update=...)` 的引擎落地：**一次节点返回同时产生两类写入**——`update` 部分走 `_get_updates` 落状态键，`goto` 部分走 `_control_branch` 落触发通道。返回 `Send` 则产生第三类：`__pregel_tasks`（TASKS）是一个 `Topic` 通道，里面的每个 `Send` 在下一个超步各自变成独立任务（PUSH 任务，见 4.3/4.7）。

## 3.7 checkpoint 格式迁移：一个真实的演进样本

【源码证据】`graph/state.py:1626-1729`，`_migrate_checkpoint` 兼容三代触发通道命名：

- 最老：`start:{node}`（每节点一个输入通道）；
- 中代：`branch:{源}:{条件名}:{目标}`（条件边各目标独立通道）；
- 现代（v3 起）：统一 `branch:to:{目标}`。

加载旧版本 checkpoint 时逐键改名、合并版本号、同步改写 `versions_seen`。这段代码是"**图定义变了，历史存档还能不能用**"的答案——通道命名属于图布局的一部分，框架选择用迁移函数而非放弃旧档。对用户的意义：升级 langgraph 后，老 thread 通常还能继续对话；但如果你改了图结构（增删节点），新旧通道不对应，行为以新图为准。

## 3.8 子图：图即节点

`add_node` 接受另一个编译图作为节点，也可以直接调用 `subgraph.invoke(...)` 嵌入节点内部——两种形态的区别只是 checkpoint 命名空间的挂载方式。子图的 checkpointer 取值语义（`types.py:109-115`）：`None` 继承父图、`True` 显式开启（沿用父 saver）、`False` 显式关闭（父图有也不存）。子图的 thread 坐标用 `checkpoint_ns` 表达（层级分隔符 `|`，`_internal/_constants.py:87-89`），父图 checkpoint 的 `parents` 元数据记录"命名空间 → 父检查点 id"映射——这是第五章寻址机制的伏笔。子图返回 `Command(graph=Command.PARENT, ...)` 时，经 `ParentCommand` 异常（`errors.py:129-133`）上浮父图执行——**子图给父图的"goto"是用控制流异常表达的**。

## 3.9 本章小结

- StateGraph 是纯 builder；compile() 完成"图纸 → Pregel 机器件"的翻译：节点→PregelNode（triggers/channels/mapper/writers）、边→对触发通道的写入、条件边→writers 上的分支函数。
- 输入即通道（`__start__` + EphemeralValue）、终点即无人订阅（`branch:to:__end__`）、join 即命名栅栏——三种"特例"全部收敛到同一触发模型。
- 节点返回值经 `_get_updates` 过滤到 output_keys；`Command` 的 update/goto/parent 三能力分别落状态键、触发通道、控制流异常；`Send` 落 TASKS 通道。
- compile 期完成：校验、serde 白名单、节点默认值与兜底 error handler、v2 流类型 mapper 的登记。
- checkpoint 里记录的通道命名会随图布局演进，`_migrate_checkpoint` 负责三代命名之间的迁移。

---

# 四、执行引擎：Pregel 循环（pregel/）

> 本章对应源码：`libs/langgraph/langgraph/pregel/main.py`（Pregel 主类与 stream/invoke 入口，4364 行）、`_loop.py`（PregelLoop，1988 行）、`_algo.py`（任务规划与写入应用，1460 行）、`_runner.py`（并发执行器，941 行）、`_retry.py`（重试与超时）、`_executor.py`（线程池/事件循环适配）。

## 4.1 先说白话：BSP/Pregel 是什么，为什么适合智能体

**整步同步并行（Bulk Synchronous Parallel）**：一轮计算分三段——①计划（挑出这一步要跑的任务）、②执行（全部并行跑完为止，期间互相看不见对方的输出）、③提交（把所有输出一次性合并进共享状态），然后进入下一轮，直到没有任务可挑或步数超限。Google 的图计算系统 Pregel 用它做大规模图计算，LangGraph 把"图上的顶点"换成了"图里的节点"、把"顶点间的消息"换成了"通道"。

【源码证据】`pregel/main.py:454-477`，Pregel 类 docstring 对三阶段的官方表述：

```python
class Pregel(PregelProtocol[StateT, ContextT, InputT, OutputT], Generic[...]):
    """Pregel manages the runtime behavior for LangGraph applications.

    Pregel combines actors and channels into a single application. Actors read
    data from channels and write data to channels. Pregel organizes the
    execution of the application into multiple steps, following the Pregel
    Algorithm / Bulk Synchronous Parallel model.

    Each step consists of three phases:
    - Plan: Determine which actors to execute in this step...
    - Execution: Execute all selected actors in parallel, until all complete...
      During this phase, channel updates are invisible to actors until the next step.
    - Update: Update the channels with the values written by the actors in this step.
```

为什么这个模型恰好适合智能体？（1）**并发工具调用、map-reduce 扇出**天然映射为"同一超步内的多个任务"；（2）**"每一步结束状态是确定的"**让 checkpoint、interrupt、replay 有了统一切入点——它们全都挂在超步边界上；（3）**通道更新到下一步才可见**杜绝了节点间的读写竞态（没有锁，因为根本没有共享可变窗口）。

## 4.2 一次 invoke 的骨架：主循环走读

【源码证据】`pregel/main.py:2959-2988`（同步 `stream` 内核；`ainvoke/astream` 的 async 版在 3437-3458 行结构同构）：

```python
# Similarly to Bulk Synchronous Parallel / Pregel model
# computation proceeds in steps, while there are channel updates.
# Channel updates from step N are only visible in step N+1
while loop.tick():                                     # ① Plan（+ ②的准备工作）
    for task in loop.match_cached_writes():            #    缓存命中的任务直接重放写入
        loop.output_writes(task.id, task.writes, cached=True)
    for _ in runner.tick(                              # ② Execution：并发执行本步任务
        [t for t in loop.tasks.values() if not t.writes],
        timeout=self.step_timeout,
        get_waiter=get_waiter,
        schedule_task=loop.accept_push,                #    任务可再派生新任务
    ):
        yield from _output(...)                        #    边执行边吐流
    loop.after_tick()                                  # ③ Update：合并写入 + 落 checkpoint
    ...
    if durability_ == "sync":
        loop._put_checkpoint_fut.result()              #    sync 档：等存档完成再进下一步
```

外围还有两个决定性组件（`main.py:2891-2931`）：`with SyncPregelLoop(...) as loop` 负责加载/创建第一个 checkpoint、处理输入与恢复（`_first`，见第六章）；`PregelRunner` 负责把一批任务投到线程池/事件循环并收割。循环出口处（`main.py:3002-3011`）：`loop.status == "out_of_steps"` 时抛 `GraphRecursionError`（递归上限打满），`"draining"` 时抛 `GraphDrained`（协作式停机）。

把三阶段映射到方法名，一张总表：

| BSP 阶段 | 方法 | 源码 | 职责 |
|---|---|---|---|
| ① Plan | `PregelLoop.tick()` | `_loop.py:599-681` | 步数检查 → `prepare_next_tasks` → interrupt_before 判定 → 状态置位 |
| ① Plan（首次） | `PregelLoop._first()` | `_loop.py:848-1079` | 加载 checkpoint、识别 resume/fork/replay、写入输入 |
| ② Execution | `PregelRunner.tick()` | `_runner.py:176-358` | futures 并发执行、重试、commit、error_handler 路由 |
| ③ Update | `PregelLoop.after_tick()` | `_loop.py:683-726` | `apply_writes` → values 流 → 清 pending → `_put_checkpoint` → interrupt_after 判定 |

## 4.3 任务规划：prepare_next_tasks / prepare_single_task

【源码证据】`pregel/_algo.py:392-520`。每一步开始前，从两类来源凑齐任务：

```python
# 来源一：PUSH 任务 —— TASKS 通道（Topic）里上一步塞进来的 Send
tasks_channel = cast(Topic[Send] | None, channels.get(TASKS))
if tasks_channel and tasks_channel.is_available():
    for idx, _ in enumerate(tasks_channel.get()):
        if task := prepare_single_task((PUSH, idx), ...): tasks.append(task)

# 来源二：PULL 任务 —— 订阅通道在上一超步被更新过的节点
if updated_channels and trigger_to_nodes:      # 快路径：上一步更新的通道 → 受影响的节点
    triggered_nodes = {node for ch in updated_channels if (node_ids := trigger_to_nodes.get(ch)) for node in node_ids}
    candidate_nodes = sorted(triggered_nodes)
elif not checkpoint["channel_versions"]:
    candidate_nodes = ()
else:
    candidate_nodes = processes.keys()         # 慢路径：全量节点逐个问
for name in candidate_nodes:
    if task := prepare_single_task((PULL, name), ...): tasks.append(task)
```

PULL 任务的"该不该跑"判定在 `_triggers`（`_algo.py:1260-1278`）：对节点订阅的每个通道，比较 `checkpoint["channel_versions"][通道]` 是否大于 `versions_seen[节点名][通道]`——**版本号乐观并发**：节点只在自己"没见过的版本"出现时被唤醒。同一个版本号体系也是第五章 checkpoint 的基础。

【源码证据】`pregel/_algo.py:597-761`，`prepare_single_task` 的 PULL 分支做了五件事：

1. **生成确定性 task_id**（`_algo.py:613-624`）——xxhash（checkpoint v>1）或 uuid5，输入是 `(checkpoint_id, checkpoint_ns, step, 节点名, PULL, 触发通道列表)`：

```python
triggers = tuple(sorted(proc.triggers))
checkpoint_ns = f"{parent_ns}{NS_SEP}{name}" if parent_ns else name
task_id = task_id_func(checkpoint_id_bytes, checkpoint_ns, str(step), name, PULL, *triggers)
task_checkpoint_ns = f"{checkpoint_ns}{NS_END}{task_id}"
```

确定性 ID 是恢复/重放的地基：重放同一 checkpoint 的同一步，算出的 task_id 必须一模一样，才能把"当时任务写到一半的 pending writes"重新接上。

2. **构造 scratchpad**（`_algo.py:626-634`）——本任务执行期的"便签"：interrupt 计数器、已收到的 resume 值列表、步数边界（第六章的主角）。
3. **读取任务输入** `_proc_input`：把节点订阅的通道当前值拼成状态 dict，managed 键此时现算（`local_read`，`_algo.py:188-224`）。
4. **组装 task 级 config**（`_algo.py:702-749`）——把引擎能力注入到节点的 `RunnableConfig["configurable"]`：

| 注入键 | 作用 |
|---|---|
| `__pregel_send`（CONFIG_KEY_SEND） | 节点内 `get_config()` 可拿到的写入函数；`Command`/interrupt 都靠它回写 |
| `__pregel_read`（CONFIG_KEY_READ） | 读"叠加了本任务已写入"的状态副本（条件边/ToolNode 用） |
| `__pregel_checkpointer` | 子图从 config 继承 saver 的通道 |
| `__pregel_checkpointer_ns` / `__pregel_task_id` | 命名空间与任务坐标 |
| `__pregel_scratchpad` | interrupt 状态便签 |
| `__pregel_runtime` | Runtime 对象（override 进 previous/store/ExecutionInfo） |

5. **附上元数据**（`_algo.py:654-660`）：`langgraph_step / langgraph_node / langgraph_triggers / langgraph_path / langgraph_checkpoint_ns`——LangSmith 追踪与 stream 消息元数据全部源于这里。

## 4.4 任务执行：PregelRunner 与重试

【源码证据】`pregel/_runner.py:176-358`。`tick()` 把任务集投递执行、按完成顺序收割：

- **并发载体**：`BackgroundExecutor`（`_executor.py:40-119`，sync 线程池，池大小受 run config `max_concurrency` 控制）或 `AsyncBackgroundExecutor`（asyncio + Semaphore）。每个任务提交的是 `run_with_retry(t, retry_policy, ...)`（`_runner.py:259-276`）。0.x 的 `MainQueueThreadPool` 已删除。
- **收割**：`concurrent.futures.wait(FIRST_COMPLETED)` 循环（`_runner.py:282-335`），每个任务完成即 `commit`（把该任务的 writes 记入 checkpoint 的 pending_writes 并持久化，`_runner.py:574+`）——**任务一完成就落档，不等整步**。
- **单任务快路径**（`_runner.py:203-254`）：只有一个任务且无超时时，干脆在当前线程直接执行，省一次线程切换。
- **错误路由（1.2）**：任务抛异常且配置了 `error_handler` 时，动态调度一个"错误处理任务"（`_runner.py:222-232, 297-323`），handler 节点本身不再路由 handler；`GraphBubbleUp`（interrupt 家族）永远不算错误、不路由。
- **重试**：`run_with_retry`（`_retry.py:573+`）按 `RetryPolicy`（`types.py:427-446`：`initial_interval=0.5, backoff_factor=2.0, max_interval=128.0, max_attempts=3, jitter=True`）指数退避 + 随机抖动：

```python
attempts += 1
if attempts >= matching_policy.max_attempts: raise
interval = min(matching_policy.max_interval,
               matching_policy.initial_interval * matching_policy.backoff_factor ** (attempts - 1))
sleep_time = interval + random.uniform(0, 1) if matching_policy.jitter else interval
```

  默认重试谓词 `default_retry_on`（`_internal/_retry.py`）：连接错误与 HTTP 5xx 重试，`ValueError/TypeError` 等编程类异常不重试。每次尝试前 `task.writes.clear()` 丢弃残写（`_retry.py` 内），`GraphBubbleUp`/`ParentCommand`/`CancelledError` 不走重试。
- **超时（1.2）**：`TimeoutPolicy`（`types.py:460-523`）分硬墙钟 `run_timeout` 与空闲 `idle_timeout`；async 路径用 watchdog 竞速（`_retry.py:422-517`），任何写入/流/回调事件都算"进度"刷新空闲时钟，或由 `runtime.heartbeat()` 显式刷新；超时抛 `NodeTimeoutError`（`errors.py:190-241`，故意不继承 `TimeoutError`/`OSError`，以免默认重试策略误判为不可重试类）。

## 4.5 写入应用：apply_writes 的版本号机制

超步的"提交"阶段由 `apply_writes` 完成（`_algo.py:232-345`），这段 100 行代码是整个并发模型的核心：

```python
# ① 按任务 path 排序，保证写入应用顺序确定
tasks = sorted(tasks, key=lambda t: task_path_str(t.path[:3]))
# ② 记录"各任务各看过哪些版本"——下次唤醒判定用的快照
for task in tasks:
    checkpoint["versions_seen"].setdefault(task.name, {}).update(
        {chan: checkpoint["channel_versions"][chan] for chan in task.triggers ...})
# ③ 计算本超步的全局新版本号
next_version = get_next_version(max(checkpoint["channel_versions"].values(), default=None), None)
# ④ 消费所有被读过的通道（Ephemeral/Topic 在此自焚/清空）
for chan in {chan for task in tasks for chan in task.triggers if chan not in RESERVED and chan in channels}:
    if channels[chan].consume() and next_version is not None:
        checkpoint["channel_versions"][chan] = next_version
# ⑤ 按通道分组应用写入
for chan, vals in pending_writes_by_channel.items():
    if channels[chan].update(vals) and next_version is not None:
        checkpoint["channel_versions"][chan] = next_version
        if channels[chan].is_available(): updated_channels.add(chan)
# ⑥ bump_step：本超步"被读过但没被写"的通道补发空更新 → EphemeralValue 在此清空
if bump_step:
    for chan in channels:
        if channels[chan].is_available() and chan not in updated_channels:
            if channels[chan].update(EMPTY_SEQ) and ...: ...
# ⑦ 若本超步的更新不再触发任何节点 → 全通道 finish()（defer/出口通道在此激活）
if bump_step and updated_channels.isdisjoint(trigger_to_nodes):
    for chan in channels:
        if channels[chan].finish() and ...: ...
```

四个精彩点：

1. **版本号是全局单调的**：一个超步一个新版本，所有被写/被消费的通道都跳到同一版本。判断"谁该被唤醒"退化为版本比较，不需要锁。
2. **⑥ 是理解 EphemeralValue 的钥匙**：`EphemeralValue.update(())`（空序列）把值清回 MISSING（`ephemeral_value.py:55-61`）。引擎不区分通道类型，只补发空更新——**生命周期语义完全下放给通道自己**。
3. **⑦ 是图终止的判定**：`updated_channels.isdisjoint(trigger_to_nodes)`——"这一步的产出不再能触发任何节点"等价于图到达终点，此时通知全部通道 `finish()`。
4. **写入顺序确定性**：任务按 path 排序（`_algo.py:253-256`），同超步并发写入的归约顺序虽由通道决定，但写入集合的组织是确定的——重放时结果可复现。

## 4.6 tick / after_tick：超步的粘合剂

【源码证据】`pregel/_loop.py:599-726`。`tick()`（Plan 阶段）里三个易被忽略的细节：

```python
if self.step > self.stop:                       # recursion_limit 的判定点
    self.status = "out_of_steps"
    return False
...
if self.interrupt_before and should_interrupt(self.checkpoint, self.interrupt_before, self.tasks.values()):
    self.status = "interrupt_before"
    raise GraphInterrupt()                      # 声明式停点：异常即暂停
```

`after_tick()`（Update 阶段）末尾对称地有 `interrupt_after` 判定，然后 `_put_checkpoint({"source": "loop"})` 落档（`_loop.py:718`）。`recursion_limit`（默认 25，可在 config 覆盖）的完整闭环在主循环出口：`out_of_steps` → `GraphRecursionError`（`main.py:3002-3011`，错误信息提示调大 `recursion_limit`）。

`should_interrupt`（`_algo.py:155-185`）的判定有个精巧的门控：只有"上次 interrupt 之后通道又有新版本"时才再次触发 interrupt_before——否则恢复（resume）后的第一次 tick 会立刻被同一个停点卡死。

## 4.7 Send 与 Command：动态控制流的引擎落地

**Send（动态 map-reduce）**：条件边函数返回 `[Send("node", arg), ...]` 时，经 `_control_branch` 写入 TASKS 通道（`prepare_next_tasks` 在下一步消费它，`_algo.py:442-446`）；下一个超步里每个 `Send` 由 `prepare_push_task_send`（`_algo.py:938+`）变成一个独立 PUSH 任务——**目标节点被并行调用 N 次，每次输入不同**；输入不必符合图状态 schema（Send 的 `arg` 直接作为节点入参，常配合 `input_schema` 让该节点接受自定义载荷，ToolNode 的 `_parse_input` 就专门识别这种 `tool_call_with_context` 载荷）。`Send` 还带 `timeout` 参数（`types.py:785-804`，1.2 起）为单个派生任务指定超时。

**Command（写 + 跳二合一）**：`Command(update=..., goto=..., resume=..., graph=PARENT)`（`types.py:826-876`）——四个能力对应四种引擎行为：`update` 经 `_get_updates` 落状态键；`goto` 经 `_control_branch` 落 `branch:to:X`；`resume` 进入第六章；`graph=PARENT` 在子图侧抛 `ParentCommand` 上浮。**Command 不是图对象的 API，而是节点返回值协议**——它让"节点内部决定下一步"成为一等公民，配合 `add_node(..., destinations=...)`（仅用于画图标注）可以搭出"无边图"。

## 4.8 本章小结

- 引擎是教科书式的 BSP：Plan（`tick` + `prepare_next_tasks`）→ Execution（`PregelRunner` futures 并发）→ Update（`apply_writes`），循环直到无任务或步数打满。
- 唤醒判定 = 通道版本号 vs 节点 versions_seen 的乐观比较；task_id 是确定性哈希——重放与恢复的地基。
- 引擎能力（发送/读取/检查点/流/运行时）全部经 `configurable` 的 `__pregel_*` 键注入节点，节点代码因此可以完全不感知引擎。
- 并发冲突被三道闸拦住：schema 编译期（reducer 签名校验）、apply_writes 期（单值键多写即抛）、归约期（Overwrite 每超步限一）。
- Send=动态扇出（PUSH），Command=节点内 goto+update，interrupt=异常即暂停——三种"动态性"全部落在超步边界的既有机制上，引擎没有为它们开任何旁路。

---

# 五、持久化：Checkpoint 体系（langgraph-checkpoint）

> 本章对应源码：`libs/checkpoint/langgraph/checkpoint/`（base 抽象 + memory 实现 + serde；注意 `langgraph.store` 也随这个包发布）、`libs/checkpoint-sqlite/`、`libs/checkpoint-postgres/`、引擎侧落档点 `libs/langgraph/langgraph/pregel/_loop.py` 的 `_put_checkpoint`。**包结构说明**：checkpoint 抽象与实现是独立发行包 `langgraph-checkpoint`（主包只依赖其抽象），实现类随主包 re-export。

## 5.1 先说白话：为什么每一步都要"存档"？

智能体的每一步都可能很贵（LLM 调用、工具执行）、很慢、可能中断（人审）、可能崩（进程重启）。如果每一步结束后把"完整状态 + 谁执行到哪了"写下来，那么暂停=停止循环、恢复=读档继续、重放=读旧档重跑、时间旅行=读任意历史档。**LangGraph 没有单独的"暂停恢复"子系统，interrupt/time-travel/durable execution 全是 checkpoint 的下游应用**——这是它架构上最值得学习的一点。

checkpoint 的存储模型是一张三维坐标表：

```
thread_id（会话/对话 ID） ──► checkpoint_ns（子图命名空间，根图为 ""）──► checkpoint_id（uuid6，时间有序）
        │                            │                                    │
        └─ 同一对话的多轮共享状态        └─ 同一 run 内的子图嵌套                └─ 同一 run 的每步快照
```

## 5.2 Checkpoint 数据结构

【源码证据】`libs/checkpoint/langgraph/checkpoint/base/__init__.py:93-124`（TypedDict，非 dataclass）：

```python
class Checkpoint(TypedDict):
    v: int                  # 存档格式版本；LATEST_VERSION = 2（:812）
    id: str                 # uuid6 生成，时间有序 → 字典序即时间序，可直接 max() 取最新
    ts: str                 # ISO 8601 时间戳
    channel_values: dict[str, Any]        # 各通道当前值（持久层通常 blob 化，见 5.5）
    channel_versions: dict[str, V]        # 各通道的版本号（第四章唤醒判定的依据）
    versions_seen: dict[str, dict[str, V]]  # 各节点已消费的通道版本
    updated_channels: NotRequired[...]
```

`versions_seen` 与 `channel_versions` 的配对是引擎与存档的连接组织：**引擎的"下一步跑谁"完全由这两张版本表决定**（4.3/4.5 节），所以存档里必须带着它们，恢复后才能继续做同样的判定。

【源码证据】元数据与寻址（`base/__init__.py:39-87, 140-147`）：

```python
class CheckpointMetadata(TypedDict, total=False):
    source: Literal["input", "loop", "update", "fork"]   # 四种落档来源
    step: int                                            # -1=input 档、0=首个 loop 档
    parents: dict[str, str]                              # checkpoint_ns → 父 checkpoint_id
    writes: NotRequired[dict[str, Any]]                  # update_state 补写时的写入
    run_id: NotRequired[str]

class CheckpointTuple(NamedTuple):       # checkpointer.get_tuple() 的返回
    config / checkpoint / metadata / parent_config / pending_writes
```

`source` 四值正好对应四个落档时机：输入落档（`_first` 里 `{"source": "input"}`）、每超步落档（`{"source": "loop"}`）、`update_state` 人为改状态、time-travel fork（`{"source": "fork"}`，见 5.8）。`pending_writes` 是"该 checkpoint 上已完成但尚未并入状态的任务写入"——恢复时的半成品现场。

## 5.3 BaseCheckpointSaver 契约与 serde

【源码证据】`base/__init__.py:177-712`。实现一个自定义 checkpointer 只需实现一小撮方法（同步 + 异步两套，异步默认抛 `NotImplementedError`）：

| 方法 | 契约 |
|---|---|
| `get_tuple(config)` | 按 `(thread_id, checkpoint_ns[, checkpoint_id])` 取一个存档 + 其 pending_writes；无 id 时取最新 |
| `list(config, *, filter, before, limit)` | 按时间倒序列历史（time-travel 的数据源） |
| `put(config, checkpoint, metadata, new_versions)` | 写入存档，返回带 checkpoint_id 的新 config |
| `put_writes(config, writes, task_id, task_path)` | 写任务级 pending writes（含 interrupt/resume 等特殊写入） |
| `delete_thread` / `copy_thread` / `prune` / `get_delta_channel_history` | 线程管理（1.2 新增后三者，delta 为 beta） |

特殊写入用负索引防冲突（`base/__init__.py:796`）：`WRITES_IDX_MAP = {ERROR: -1, SCHEDULED: -2, INTERRUPT: -3, RESUME: -4}`；常规写入幂等（已存在即忽略），特殊写入总是覆盖——两个 put_writes 的 SQL 正是这么写的（见 5.5）。

**serde（序列化协议）**：`BaseCheckpointSaver.serde` 默认 `JsonPlusSerializer()`（`base/__init__.py:210`）。1.2.12 的默认序列化载体已是 **ormsgpack** 而非 JSON（`serde/jsonplus.py:258-290`：`dumps_typed` 优先 `"msgpack"`，`"json"` 仅作旧格式读取路径；失败可选 pickle 兜底）。它要解决的真问题是"**任意 Python 对象如何无损穿越存档边界**"：

- pydantic v2 对象 → msgpack 扩展类型（`EXT_PYDANTIC_V2 = 5`），载荷是 `(模块名, 类名, model_dump(), "model_validate_json")`，读取侧反射重建（`jsonplus.py:308-319, 711-730`）；
- langchain 对象走 `{"lc": 2, "type": "constructor", ...}` 信封 + 模块白名单（防反序列化攻击，`jsonplus.py:160-256`，历史漏洞 GHSA-fjqc-hq36-qh5p 后 method 调用被有意禁用）；
- datetime/UUID/Decimal/set/IP 地址等常见类型注册在 `SAFE_MSGPACK_TYPES`（`serde/_msgpack.py:21-85`）；
- 环境变量 `LANGGRAPH_STRICT_MSGPACK=true` 开启严格模式：白名单之外一律拒绝（默认仅告警放行）。

给用户的忠告藏在实现里：**放进状态的东西必须可被这套 serde 序列化**——自定义类要么注册白名单、要么改用 dict/pydantic；lambda、打开的文件句柄、数据库连接都会在第一次 checkpoint 时炸掉。

## 5.4 三个官方实现

**InMemorySaver**（`checkpoint/memory/__init__.py`；旧名 `MemorySaver` 现在只是它的别名，`:625`）。内部三张内存表（`:68-99`）：

```python
storage: dict[thread_id, dict[ns, dict[checkpoint_id, (checkpoint序列化, metadata序列化, parent_id)]]]
writes:  dict[(thread_id, ns, checkpoint_id), dict[(task_id, idx), (task_id, channel, 序列化值, task_path)]]
blobs:   dict[(thread_id, ns, channel, version), (类型标记, bytes)]    # 通道值按版本 blob 化
```

两个设计点：（1）**channel_values 不内联在 checkpoint 里，而是按 `(通道, 版本)` 拆成 blob**，读取时按 `channel_versions` 重组（`_load_blobs`，`:125-140`）——相邻 checkpoint 大量共享未变化的通道值，内存与 SQL 存储都受益；（2）checkpoint_id 用 uuid6 生成，**字典序即时间序**，"取最新"就是 `max(keys)`（`:277`）。测试/演示用它，生产换 Postgres。

**SqliteSaver**（`checkpoint-sqlite/.../sqlite/__init__.py:45`）：两张表 `checkpoints`（BLOB 存 serde 结果）+ `writes`，WAL 模式；`setup()` 自动建表。适合单机原型。**PostgresSaver**（`checkpoint-postgres/.../postgres/__init__.py:40`）：`setup()` 显式执行 `MIGRATIONS` 迁移序列（`base.py:43-91`，带版本登记表）；表为 `checkpoints`（JSONB）+ `checkpoint_blobs`（BYTEA，主键含 version）+ `checkpoint_writes`；写路径用 libpq **pipeline 批量提交**（`put` 内 `executemany`，基元值内联进 JSONB、其余入 blobs 表）；读路径的 `SELECT_SQL` 在 SQL 里 join blobs 重组 channel_values（`base.py:93-118`）。`AsyncPostgresSaver`/连接池变体同库提供。

## 5.5 引擎侧：什么时候落档？落什么档？

【源码证据】`pregel/_loop.py:1081-1219`，`_put_checkpoint` 的关键判定：

```python
do_checkpoint = self._checkpointer_put_after_previous is not None and (
    exiting or self.durability != "exit"          # Durability 三档的差异点
)
channels_to_snapshot = delta_channels_to_snapshot(self.channels, new_counters) | self._delta_channels_with_overwrite
self.checkpoint = create_checkpoint(self.checkpoint, self.channels if do_checkpoint else None, self.step, ...)
...
self._put_checkpoint_fut = self.submit(            # 存档提交到后台，不阻塞执行
    self._checkpointer_put_after_previous, getattr(self, "_put_checkpoint_fut", None), ...)
```

三个层面：

1. **Durability 三档**（`types.py:98-104`）：`"sync"`（写档完成才进下一步，最稳）、`"async"`（后台写档，下一步照跑）、`"exit"`（只在 run 收尾写档，最快但中途崩溃丢进度）。compile() 或每次 invoke 都可指定。主循环里 `if durability_ == "sync": loop._put_checkpoint_fut.result()`（`main.py:2987-2988`）就是 sync 档的等待点。
2. **DeltaChannel 增量存档（1.2 beta）**：默认每个超步把"被更新的通道值"全量快照进 blobs；`DeltaChannel` 通道改为"平时只记增量写入、每 N 个超步才做一次全量快照"（`_put_checkpoint` 里的 `counters_since_delta_snapshot` 计数器与 `channels_to_snapshot`，`_loop.py:1096-1162`），配合 `get_delta_channel_history` 沿父链回放增量重建任意历史值。目的是削平"大状态 + 高频步"的写放大。
3. **存档是异步但有序的**：`self.submit(..., 前一个 future)` 传入了上一次的 future 作为"前任"——**同一个 checkpointer 收到的存档严格按序**（`_loop.py:1199-1209` 注释：ensuring checkpointers receive checkpoints in order）。

## 5.6 时间旅行：get_state_history / update_state / fork

`graph.get_state_history(config)` 遍历 `checkpointer.list()`，把每个 checkpoint 组装成 `StateSnapshot`（`types.py:711-729`：values/next/tasks/interrupts/config/parent_config）。三个进阶玩法，全部绕不开 `_first` 的 `is_time_traveling` 判定（`_loop.py:878-900`）：

- **从历史点重放**：`invoke(None, {**config, "configurable": {"checkpoint_id": 某历史id}})`。引擎检测到"指定了旧 checkpoint + 非当前 head"，走 fork 分支：**清掉缓存的 RESUME 写入（让 interrupt 重新触发）、先落一个 `{"source": "fork"}` 的分叉档**（`_loop.py:960-971`），此后执行产生的新 checkpoint 挂在新分支上——旧历史不被破坏。
- **人为改状态**：`graph.update_state(config, values, as_node=...)`（`main.py:2515+`，实现在 `bulk_update_state:1590+`）：以"假装是某个节点写了这些值"的方式追加一个 `source="update"` 的 checkpoint；`as_node=None` 表示更新后**从停下的地方继续**，`as_node=X` 则等价于"X 已执行完毕"——常与 interrupt 配合做"人工修正后继续跑"。
- **回看 pending**：StateSnapshot.tasks 里每个任务的 error/interrupts/写入都可查——调试"上次卡在哪"就是看这个。

## 5.7 本章小结

- Checkpoint = 三维坐标（thread/ns/id）+ 状态值（blob 化）+ 两张版本表（channel_versions/versions_seen）；版本表让"恢复后继续唤醒判定"成为可能。
- 自定义 checkpointer 只需实现 get_tuple/list/put/put_writes；serde 的 ormsgpack + 扩展类型 + 白名单是"任意对象穿越存档边界"的答案，也是状态序列化约束的来源。
- InMemory/Sqlite/Postgres 三实现是同一契约的递进（单机→单机持久→生产）；Postgres 的 pipeline 批量写与迁移序列值得生产用户细读。
- Durability 三档与 DeltaChannel 增量快照分别在"可靠性与吞吐"光谱的两端做取舍。
- time-travel 的 fork 档设计保证"重放历史不破坏历史"。

---

# 六、人在环路：interrupt 与 Command(resume=)

> 本章对应源码：`libs/langgraph/langgraph/types.py` 的 `interrupt()`/`Command`/`Interrupt`、`libs/langgraph/langgraph/errors.py`（GraphBubbleUp 家族）、`libs/langgraph/langgraph/pregel/_loop.py`（`_first` 恢复入口）、`_algo.py`（`should_interrupt`）、`_internal/_constants.py`（RESUME/INTERRUPT 写入键）。

## 6.1 先说白话：暂停与恢复的本质

"暂停"其实是一次**未完成**：节点执行到一半抛出一个特殊的控制流异常，引擎把"当前进度 + 挂起的问题"写进 checkpoint，然后停机。"恢复"其实是一次**重放**：引擎从同一 checkpoint 载入，**从头重新执行该节点**；重放途中再次执行到 `interrupt()` 这一行时，不再抛异常，而是把你当年随 `Command(resume=...)` 交给它的答复返回出来。理解了"**恢复 = 节点重放 + interrupt 处返回缓存值**"，这一章的一切细节都能推出来。

三个直接推论（也是三个最常见的坑）：节点在 `interrupt()` 之前的代码会被**再次执行**，副作用必须幂等；`interrupt()` 的返回值只在恢复后存在，首次执行不会走到它后面的分支之外；不配 checkpointer 一切免谈（`interrupt()` 依赖 pending writes 持久化）。

## 6.2 interrupt() 源码走读

【源码证据】`types.py:887-1029`（全文 143 行，是全框架最精巧的函数之一）：

```python
def interrupt(value: Any, *, response_schema: dict[str, Any] | type | None = None) -> Any:
    conf = get_config()["configurable"]
    adapter = None if response_schema is None or isinstance(response_schema, dict) else TypeAdapter(response_schema)
    # ① 本节点第几次调用 interrupt（scratchpad 计数器）
    scratchpad = conf[CONFIG_KEY_SCRATCHPAD]
    idx = scratchpad.interrupt_counter()
    # ② 恢复路径一：resume 是"interrupt_id → 答复"映射（多中断场景）
    if scratchpad.resume:
        if idx < len(scratchpad.resume):
            v = scratchpad.resume[idx]
            validated = adapter.validate_python(v) if adapter else v
            conf[CONFIG_KEY_SEND]([(RESUME, scratchpad.resume[: idx + 1])])
            return validated
    # ③ 恢复路径二：resume 是单个值，按顺序填给下一个 interrupt
    v = scratchpad.get_null_resume(True)
    if v is not None:
        ...
        scratchpad.resume.append(v)
        conf[CONFIG_KEY_SEND]([(RESUME, scratchpad.resume)])
        return validated
    # ④ 无答复可取 → 抛出控制流异常，暂停
    raise GraphInterrupt(
        (Interrupt.from_ns(value=value, ns=conf[CONFIG_KEY_CHECKPOINT_NS],
                           response_schema=adapter.json_schema() if adapter else response_schema),)
    )
```

四个要点：

1. **Interrupt 的 id 是 `xxh3(checkpoint_ns)`**（`from_ns`，`types.py:634-646`）——同一线程同一命名空间下稳定不变，客户端可以拿它精确恢复某一个中断（`Command(resume={interrupt_id: 答复})`），多个中断并行挂起时这是唯一可靠的寻址方式。
2. **scratchpad**（`_algo.py:1280-1346`）是随任务 config 注入的便签：`interrupt_counter()` 统计"本次重放中 interrupt 被调用的次数"，`resume` 列表记录已消费的答复。**重放时逐个对号入座**：第一次 `interrupt()` 吃掉 resume[0]，第二次吃 resume[1]……
3. **第②③步里的 `conf[CONFIG_KEY_SEND]([(RESUME, ...)])` 不是摆设**：把"本 interrupt 已被答复"这个事实作为 `(RESUME, resume[:idx+1])` 写入 pending writes 并由引擎落档——万一进程在恢复途中再崩，已答复过的中断不会回到"未答复"状态。恢复本身也是 durable 的。
4. **`response_schema`**（1.x）：传 Pydantic/TypedDict/dataclass 时，答复会先被 `TypeAdapter.validate_python` 校验再返回，客户端还会拿到 JSON Schema 用于渲染输入表单（`types.py:970-985`）。

## 6.3 异常的上浮与现场保存

`GraphInterrupt` 继承自 `GraphBubbleUp`（`errors.py:50-107`）——这个基类名直白地说明了设计：**控制流复用异常机制，但语义上不是错误**。上浮途中的每一层都认识它：

- **重试层**：`run_with_retry` 见到 `GraphBubbleUp` 直接放行不重试（`_retry.py:632-634`）；
- **执行器**：`BackgroundExecutor.done` 把它从错误列表里剔除（`_executor.py:77-88`，注释："This exception is an interruption signal, not an error"）；
- **Runner**：不路由给 error_handler（`_runner.py:225-232` 排除 `GraphBubbleUp`），但会把任务的 `INTERRUPT` 写入 commit 进 pending writes——**挂起的问题被持久化**；
- **循环层**：`PregelLoop` 捕获后把 `loop.status` 置为中断态并收尾落档（`_suppress_interrupt`），随后 stream/invoke 把 `Interrupt` 打包进输出——v1 藏在输出的 `"__interrupt__"` 键里，v2 放在 `GraphOutput.interrupts` / `ValuesStreamPart.interrupts`（`types.py:279-289`）。

子图里的中断会被父图的 `GraphInterrupt` 层层包裹上浮，`Interrupt.id` 的 ns 哈希天然区分了不同子图实例的中断。

## 6.4 恢复：Command(resume=...) 到 scratchpad 的旅程

【源码证据】`_loop.py:848-931`，`_first` 的 Command 分支：

```python
input_is_command = isinstance(self.input, Command)
is_resuming = bool(self.checkpoint["channel_versions"]) and bool(
    configurable.get(CONFIG_KEY_RESUMING,
                     self.input is None or input_is_command or ...))   # None 输入也算恢复
...
if input_is_command:
    if (resume := cast(Command, self.input).resume) is not None:
        if not self.checkpointer: raise RuntimeError("Cannot use Command(resume=...) without checkpointer")
        if resume_is_map := (isinstance(resume, dict) and all(is_xxh3_128_hexdigest(k) for k in resume)):
            self.config[CONF][CONFIG_KEY_RESUME_MAP] = resume         # 按 interrupt_id 精确恢复
        else:
            if len(self._pending_interrupts()) > 1:
                raise RuntimeError("When there are multiple pending interrupts, you must specify the interrupt id ...")
    for tid, c, v in map_command(cmd=cast(Command, self.input)):
        writes[tid].append((c, v))            # 把 RESUME 写入路由到"当年中断的那个 task_id"
    for tid, ws in writes.items():
        self.put_writes(tid, ws)              # 持久化答复（特殊写入，索引 -4，总是覆盖）
```

后续链路：载入的 checkpoint 的 pending_writes 里出现了 `RESUME` 写入 → 任务重放时 scratchpad 从 resume map 取到答复 → `interrupt()` 第②/③步返回答复 → 节点继续往下跑 → 节点产出正常写入 → 一切如初。`is_resuming` 判定还顺带处理了几种边界：`invoke(None, config)`（无输入恢复）、同一 run_id 的流重连（`_loop.py:851-872` 注释完整列出）、子图由父图显式置 `CONFIG_KEY_RESUMING`。

## 6.5 声明式停点：interrupt_before / interrupt_after

compile() 时可传 `interrupt_before=["node_x"]`（或 `"*"` 全部节点）。其判定 `should_interrupt`（`_algo.py:155-185`）已经作为引擎部分在 4.6 节讲过：**用 `versions_seen[INTERRUPT]` 做门控**——只有上次暂停之后通道又出现过新版本，停点才再次生效。它与 `interrupt()` 的分工：前者是"图作者在编译期画好的停车线"（无需改节点代码，配合 `update_state` 就是经典的"改一改再继续"），后者是"节点作者运行期发起的提问"（可携带任意载荷与 schema）。两者可叠加，注意同一停点连续两次 invoke 会因版本门控而只停第一次。

## 6.6 常用模式速查

- **审批门**：节点内 `answer = interrupt({"action": "deploy", "args": ...})`，客户端审阅后 `Command(resume=True/False)`；
- **表单输入**：`interrupt("请输入年龄", response_schema=MyForm)`，客户端拿 `Interrupt.response_schema` 渲染表单，答复强类型返回；
- **多中断一次恢复**：多个节点并行挂起时，`Command(resume={id1: 答复1, id2: 答复2})` 按 interrupt id 一次喂饱；
- **人工修正状态**：interrupt 前配 `interrupt_before` + `update_state`，改完再 `invoke(None)`；
- **prebuilt 类型**：`langgraph.prebuilt.interrupt` 提供 `HumanInterrupt/ActionRequest/HumanResponse`（accept/ignore/response/edit 四种答复语义，`libs/prebuilt/langgraph/prebuilt/interrupt.py:51-105`；除 `HumanResponse` 外已标废弃，迁往 `langchain.agents.interrupt`）。

## 6.7 本章小结

- interrupt = 控制流异常 + checkpoint 挂起；resume = checkpoint 重放 + scratchpad 对号入座；恢复本身也持久化（RESUME pending write）。
- Interrupt.id = xxh3(checkpoint_ns)，是跨进程、跨多中断的稳定地址。
- 节点重放语义要求 `interrupt()` 之前的代码幂等——这是使用该机制的唯一"心智税"。
- interrupt_before/after 是编译期停车线，interrupt() 是运行期提问；前者由 `versions_seen[INTERRUPT]` 门控防止恢复后原地卡死。

---

# 七、流式输出：Stream 体系

> 本章对应源码：`libs/langgraph/langgraph/types.py`（StreamMode/StreamPart/GraphOutput）、`pregel/main.py` 的 stream/astream 与 `_output`、`pregel/_loop.py`（DuplexStream）、`libs/langgraph/langgraph/stream/`（v3 实验协议子系统）、`runtime.py`（stream_writer）。

## 7.1 先说白话：LLM 应用的流式为什么难

难点不在"吐 token"，在于**多层并发的输出交织**：一个超步里 5 个节点并行，每个节点内部又有 LLM 的 token 流；子图嵌套时层级更多。LangGraph 的答案是"**频道化**"：调用方声明想听哪几个频道（stream_mode，可多选），引擎把所有输出打上 `(mode, 命名空间, 数据)` 的标签塞进统一管道，调用方按需取用。1.1 之前输出是裸元组，1.1 起 `version="v2"` 给每个输出加了类型（StreamPart 判别联合），1.2 又在 `stream/` 包里孵化第三代协议（投影/转换器架构）。

## 7.2 七种 stream_mode 与 v2 类型化输出

【源码证据】`types.py:131-145`，频道清单（每个值的 docstring 就是权威定义）：

| mode | 产出 | 典型用途 |
|---|---|---|
| `values` | 每超步后的**完整状态**（含 interrupts） | 看状态演化全过程 |
| `updates` | 每个节点/任务的**增量写入** `{节点名: 部分状态}` | 默认值；看每步谁写了什么 |
| `messages` | `(消息块, 元数据)` 二元组，LLM token 级 | 流式对话 UI（元数据带 langgraph_node 等） |
| `custom` | 节点内 `stream_writer(任意对象)` 写出的内容 | 进度条、自定义事件 |
| `checkpoints` | 每次落档事件（StateSnapshot 形态） | 审计存档 |
| `tasks` | 任务开始/结束事件（含 result/error） | 观测任务级并发 |
| `debug` | checkpoints + tasks 的合集 | 开发期诊断 |

v1 与 v2 的差别（1.1 Release Notes #6961 与 `types.py:352-421` 实证）：v1 的 `stream()` 产出 `(mode, data)` 裸元组、`invoke()` 返回裸 dict、interrupt 混在 `"__interrupt__"` 键；v2 的 `stream(version="v2")` 产出判别联合 `StreamPart`（`{"type": ..., "ns": (子图命名空间,), "data": ...[, "interrupts": ...]}`），`invoke(version="v2")` 返回 `GraphOutput(value=..., interrupts=...)` frozen dataclass（`types.py:379-389`）——当 state 是 pydantic/dataclass 时 `data` 自动 coerce 成对应类型（compile 期登记的 `_output_mapper`，`state.py:1377-1389`）。v2 信封的组装在 `_output`（`main.py:4184-4243`）。`stream_events()` 默认即 v2。

## 7.3 引擎内的流管道

引擎侧的流基建惊人地薄（同步版核心只有一个类）：

【源码证据】`pregel/_loop.py:149-156`：

```python
def DuplexStream(*streams: StreamProtocol) -> StreamProtocol:
    def __call__(value: StreamChunk) -> None:
        for stream in streams:
            stream(value)
    def get(): ...      # 调用方拉取
    def wait(): ...     # 调用方阻塞等待新数据
```

节点/引擎任何位置调用 `stream(写对象)`（经 CONFIG_KEY_STREAM 注入），调用方 `stream.get()/wait()` 拉取。两个工程细节值得注意（`main.py:2933-2957`）：

1. **子图流**：`subgraphs=True` 时把 `loop.stream` 注入子图 config（`CONFIG_KEY_STREAM`），子图产出带上 `ns` 命名空间冒泡上来——流没有独立的"子图协议"，复用的还是 config 注入通道。
2. **急切等待（eager waiter）**：`messages/custom` 模式或 subgraphs 开启时，任务执行是并发的，主循环可能很久才回来收割；引擎预提交一个 `stream.wait` 的后台任务（get_waiter），让产出**在产生瞬间就能被 yield**，而不是等超步收尾——这就是"并发任务的流式输出不卡顿"的实现。

节点内部拿到 writer 的三种方式：节点签名注入 `writer: StreamWriter` 参数、`runtime.stream_writer`（`runtime.py:206`）、`get_stream_writer()`。带 `TAG_NOSTREAM`（`constants.py:24`）的模型调用会被 messages 流忽略。

## 7.4 第三代流协议（stream/ 包，实验中）

1.2 在 `libs/langgraph/langgraph/stream/` 孵化了一套"投影/转换器"架构（`stream_events(version="v3")` 入口）：`ProtocolEvent` 是统一事件信封（method + 命名空间 + 单调 seq）；`StreamMux`（`stream/_mux.py`）把主事件日志分发成一串 `StreamTransformer`（`stream/_types.py:44-305`）产出的**具名投影**（values/messages/lifecycle/subgraphs/updates/custom/...，每个投影是一条可独立迭代的 `StreamChannel`，支持 tee 扇出与有界背压）；`GraphRunStream`（`stream/run_stream.py:56-353`）是调用方句柄——**消费任意投影即驱动图前进**（无后台线程泵），还能 `interleave("values", "messages")` 按到达序交织多频道。v2 的 `StreamPart` 经 `_convert.py`（全文 32 行）桥接为 v3 事件。对使用者的意义：v3 让"同一份执行流，服务端各取所需投影"成为可能（Server 的多消费者流式正建立在其上）；应用层暂时继续用 v1/v2 即可。

## 7.5 本章小结

- 流式 = 频道化输出（7 种 mode）+ 命名空间标签（ns 支持子图）+ 统一双向管道（DuplexStream）。
- v2（1.1）把裸元组升级为判别联合与 `GraphOutput`，interrupt 不再藏在魔法键里；pydantic/dataclass 状态自动 coerce。
- eager waiter 解决"并发任务的 token 流要即时可见"；custom/messages 模式自动启用。
- v3（实验）引入 mux/transformer/投影：同一执行流可派生多个独立消费视图。

---

# 八、Functional API：@entrypoint 与 @task（func/）

> 本章对应源码：`libs/langgraph/langgraph/func/__init__.py`（全文仅 620 行，@entrypoint/@task 的全部实现）。

## 8.1 先说白话：不想画图怎么办？

图 API 要求你把流程"摊平"成节点和边；但很多工作流就是一段**命令式过程**（先查库、再并行调三个接口、汇总、人审、提交），硬画成图反而失真。Functional API 让你**直接写函数**，函数体里可以调 `@task` 派生异步子任务、可以 `interrupt()` 等人答复——而背后**没有第二台引擎**：`@entrypoint` 装饰的函数会被编译成"单节点 Pregel"，@task 派生的每个调用也注册为引擎任务。**图 API 与函数式 API 是同一台 BSP 引擎的两个前端**（`func/__init__.py:576` 直接 `return Pregel(...)`）。

## 8.2 entrypoint 的编译产物：单节点 Pregel

【源码证据】`func/__init__.py:516-620`，`entrypoint.__call__` 把你的函数变成 Pregel：

```python
def __call__(self, func) -> Pregel:
    bound = get_runnable_for_entrypoint(func)
    graph: Pregel = Pregel(
        nodes={
            func.__name__: PregelNode(
                bound=bound,
                triggers=[START],
                channels=START,
                writers=[
                    ChannelWrite([
                        ChannelWriteEntry(END,      mapper=_pluck_return_value),
                        ChannelWriteEntry(PREVIOUS, mapper=_pluck_save_value),
                    ])
                ],
            )
        },
        channels={
            START:    EphemeralValue(input_type),
            END:      LastValue(output_type, END),
            PREVIOUS: LastValue(save_type, PREVIOUS),   # 跨调用记忆的落点
        },
        input_channels=START, output_channels=END,
        checkpointer=self.checkpointer, store=self.store, ...
    )
    return graph
```

整个"框架"只有三个通道：输入（EphemeralValue，用完即焚）、输出（LastValue）、以及 **PREVIOUS**——上一次调用的返回值（经 `_pluck_save_value` 抽取）。函数式 API 的"状态"就是这个 PREVIOUS 通道：你在函数签名里声明 `previous` 参数，引擎在构造输入时把 `checkpoint["channel_values"][PREVIOUS]` 注进来（`_algo.py:692`：`runtime.override(previous=checkpoint["channel_values"].get(PREVIOUS, None), ...)`）。**对比图 API**：图把状态摊成 N 个键逐超步演进；entrypoint 把状态浓缩成一个"上次返回值"，中间过程交给函数体与 @task。

【源码证据】返回值与存档值解耦——`entrypoint.final`（`func/__init__.py:475-514`）：

```python
@entrypoint(checkpointer=InMemorySaver())
def my_workflow(number: int, *, previous: Any = None) -> entrypoint.final[int, int]:
    previous = previous or 0
    return entrypoint.final(value=previous, save=2 * number)   # 对外返回 previous，存档存 2*number
```

两个 mapper（`_pluck_return_value`/`_pluck_save_value`，`func/__init__.py:546-552`）分别在写 END 与 PREVIOUS 通道时抽取不同字段——**"给用户看的"与"给下次调用用的"从此可以不同**（比如给用户返回流式文本、给下次调用存压缩摘要）。

## 8.3 @task：future 语法下的 durable 子任务

【源码证据】`func/__init__.py:59-107, 254-251`。`@task` 装饰的函数被包成 `_TaskFunction`，调用它**不执行**，而是返回 future：

```python
class _TaskFunction(Generic[P, T]):
    def __call__(self, *args, **kwargs) -> SyncAsyncFuture[T]:
        return _call_with_options(self.func, args, kwargs,
                                  retry_policy=self.retry_policy,
                                  cache_policy=self.cache_policy,
                                  timeout=self.timeout)

@task
def compose_essay(topic: str) -> str: ...

@entrypoint(checkpointer=InMemorySaver())
def review_workflow(topic: str):
    essay_future = compose_essay(topic)     # 只是登记任务
    essay = essay_future.result()           # 真正等待执行
    human_review = interrupt({...})         # 等人审
    return {"essay": essay, "review": human_review}
```

future 语法带来两个红利：（1）**扇出即并发**——`futures = [compose_essay(t) for t in topics]` 连续登记多个任务，`asyncio.gather(*futures)` / 逐个 `.result()` 由引擎并行执行；（2）**断点续跑的关键**：每个任务的输入/输出随 checkpoint 持久化，**恢复时已完成的 task 直接返回存档结果、不会重新执行**（官方 docstring 的 compose_essay 例子原话："Upon resuming the workflow, compose_essay task will not be re-executed as its result is cached by the checkpointer"，`func/__init__.py:340-342`）。这让"中断恢复"在函数式风格下比图风格更省心：函数体重放时，interrupt 之前的所有 @task 调用都从缓存命中，副作用天然不会重复。task 也支持 retry_policy/cache_policy/timeout（`func/__init__.py:109-251`）——它们被转发到 4.4 节同一套执行设施。

## 8.4 图 API vs Functional API：怎么选

| 维度 | 图 API（StateGraph） | 函数式 API（entrypoint/task） |
|---|---|---|
| 心智模型 | 声明式：节点 + 状态 + 归约 | 命令式：普通函数 + future |
| 状态 | 多键、多归约器、并发写受控 | 单个 PREVIOUS + task 缓存 |
| 中间态可见性 | 每步可流式/可 time-travel 到任意节点边界 | 粒度是 task/interrupt 边界 |
| 多角色协作（专家节点、supervisor） | 强项：结构即文档 | 需要自己组织函数结构 |
| 动态扇出 | Send/Command | 直接 for 循环 + future |
| 人审 | interrupt() 语义相同 | interrupt() + task 缓存更顺滑 |
| 混用 | 图节点内可调 @task；entrypoint 内可调子图 | 两者可嵌套 |

经验法则：**控制流本身复杂（动态路由、多角色、需要每步观测）用图；数据流复杂（多阶段转换、批处理管道）用函数式；拿不准时用 `create_agent` 起步，长出定制需求再下沉**。

## 8.5 本章小结

- entrypoint = 单节点 Pregel（START/END/PREVIOUS 三通道）；函数式 API 与图 API 共享同一引擎、同一 checkpointer、同一 interrupt 机制。
- PREVIOUS 通道 + entrypoint.final 实现"跨调用记忆"与"返回值/存档值解耦"。
- @task 是 future 语法 + 逐任务持久化：并发免费、恢复不重算——这是函数式 API 的灵魂。

---

# 九、记忆体系：短期 Checkpoint 与长期 Store

> 本章对应源码：`libs/checkpoint/langgraph/store/base/__init__.py`（BaseStore 契约，1322 行）、`libs/checkpoint/langgraph/store/memory/__init__.py`（InMemoryStore）、`libs/langgraph/langgraph/runtime.py`（Runtime）。注意：`langgraph.store` 随 langgraph-checkpoint 包发布，PostgresStore 在 langgraph-checkpoint-postgres 中。

## 9.1 先说白话：两种记忆，两种生命周期

**短期记忆**：一次对话（thread）内的工作状态——由 checkpointer 管理，随 thread 删除而删除，粒度是"超步快照"（第五、六章的主角）。**长期记忆**：跨对话、跨 thread 的知识——"这个用户偏好简洁回复""上次工单 #123 的结论"——由 **Store** 管理，按**命名空间**组织，生命周期独立于任何 thread。两者的读写 API 也刻意不同：短期记忆用户从不直接读写（引擎自动加载/保存），长期记忆则给节点提供了显式的 `store.get/put/search`。

## 9.2 BaseStore 契约：namespace + key + 语义检索

【源码证据】`store/base/__init__.py:51-115, 157-538, 708-1260`：

```python
class Item(__slots__=("value", "key", "namespace", "created_at", "updated_at")): ...
class SearchItem(Item): ...     # 多一个 score（语义检索得分）

# 四种操作（NamedTuple），BaseStore 唯一的抽象方法是 batch/abatch——所有便利方法都是 Op 的糖：
GetOp(namespace, key, refresh_ttl=True)
PutOp(namespace, key, value, index, ttl)      # value=None 即删除；ttl 单位分钟
SearchOp(namespace_prefix, filter, limit=10, offset=0, query, refresh_ttl)
ListNamespacesOp(match_conditions, max_depth, limit=100, offset=0)
```

设计要点：

1. **namespace 是字符串元组的层级路径**，如 `("users", "u123", "memories")`；SearchOp 用前缀匹配（`()` 即全库）。`_validate_namespace`（`:1263-1283`）禁止空段、`.`、保留根 `"langgraph"`。
2. **filter 支持 `$eq/$gt/$gte/$lt/$lte/$ne` 六操作符**（`SearchOp` docstring，`:255-261`）——够用的 JSONB 风格查询，不引入查询语言。
3. **语义检索是契约的一部分而非附加品**：compile/store 构造时传 `index=IndexConfig(dims=1536, embed="openai:text-embedding-3-small", fields=["$"])`（`:578-705`；`fields` 支持点路径与数组通配，默认整体嵌入）；`PutOp.index` 可逐条覆盖（False 禁索引/路径列表）。之后 `search(namespace_prefix, query="...")` 即向量检索。
4. **TTL 内建**（`TTLConfig`，`:545-575`；PutOp.ttl 分钟计，读写可刷新过期）——但 `BaseStore.supports_ttl` 默认 False，实现方自愿支持。
5. **batch/abatch 是唯一抽象方法**（`:732, 744`）：get/put/search/delete/list_namespaces 全部是"构造 Op → 调 batch"的薄包装——这让实现方只需优化一个批量接口，也天然支持一次 batch 里混合读写。

## 9.3 InMemoryStore 与 PostgresStore

【源码证据】`store/memory/__init__.py:183-234, 302-373`。内存实现的两层数据结构：

```python
self._data: dict[tuple[str, ...], dict[str, Item]] = defaultdict(dict)
self._vectors: dict[tuple[str, ...], dict[str, dict[str, list[float]]]] = ...
```

search 的三段式：前缀 + filter 过滤（`_filter_items`）→ 候选逐条与查询向量算余弦相似度（有 numpy 用 numpy，无则纯 Python）→ **按 `(namespace, key)` max-pooling 去重**后取 top-k（`:324-345`）。写路径在 batch 内先 embed 新值再落两表。`InMemoryStore.supports_ttl = False`（传 ttl 直接 NotImplementedError）。生产用 `PostgresStore`（pgvector），另有社区 Redis/MongoDB 实现——契约一致，换实现不改业务代码。

**节点里怎么拿到 store？** 三种：`compile(store=...)` 后经 `Runtime.store`（`runtime.py:203`）、节点签名注入 `store: BaseStore` 参数、工具内经 `ToolRuntime.store`。读写示例：

```python
def remember(state, *, store: BaseStore):
    store.put(("users", state["user_id"], "memories"), key="pref",
              value={"style": "concise"})
    hits = store.search(("users", state["user_id"], "memories"),
                        query="用户喜欢什么风格", limit=3)
```

## 9.4 Runtime 与 context：run 级依赖注入

1.x 把散落的"运行期环境"收拢成一个 `Runtime` 对象（`runtime.py:125-238`）：

```python
@dataclass
class Runtime(Generic[ContextT]):
    context: ContextT = ...            # invoke(context=...) 传入的静态依赖（user_id、db 连接等）
    store: BaseStore | None = ...      # 长期记忆
    stream_writer: StreamWriter = ...  # 自定义流
    heartbeat: Callable = ...          # idle_timeout 心跳（1.2）
    previous: Any = ...                # 函数式 API 上次返回值
    execution_info: ExecutionInfo | None = ...   # checkpoint_id/task_id/thread_id/重试次数（1.2）
    server_info / control              # Server 注入信息 / 协作式 drain
```

注入链路两步走：run 启动时创建根 Runtime 并写入 `configurable[CONFIG_KEY_RUNTIME]`（`main.py:2872-2882`），每个任务规划时 `runtime.override(previous=..., store=..., execution_info=ExecutionInfo(...))` 定制成任务级实例（`_algo.py:688-701`）。节点侧通过签名参数或 `get_runtime()`（`runtime.py:296-310`，从 config contextvar 取回）获得。**context_schema 与 config 的分工**：`context` 是"类型化的、本次调用不变"的依赖（`graph.invoke(input, context={"user_id": ...})`，经 `context_schema` 校验定型）；config 是"基础设施开关"（thread_id、recursion_limit、tags）。mixed 进 config 的业务参数（老教程里的 `configurable["user_id"]`）应迁移到 context。

## 9.5 本章小结

- 短期记忆（checkpointer，thread 维度，自动读写）与长期记忆（Store，namespace 维度，显式读写）是两套正交机制；前者已在第五、六章展开。
- Store 的契约极薄：namespace 元组 + 四种 Op + batch 唯一抽象 + 内建语义检索与 TTL；InMemory/Postgres/Redis 可互换。
- Runtime 是 1.x 的"运行期上下文包"，context/store/stream_writer/previous/心跳统一注入，context_schema 给 invoke 的业务上下文定型。

---

# 十、预制件与多智能体：prebuilt 与生态

> 本章对应源码：`libs/prebuilt/langgraph/prebuilt/`（独立发行包 langgraph-prebuilt 1.1.0；1.x 起主包不再内嵌 prebuilt 目录，经依赖引入）。

## 10.1 先说白话：1.x 的智能体入口搬家了

0.x 时代"搭一个 ReAct 智能体"的入口是 `langgraph.prebuilt.create_react_agent`。1.0 起，官方把它**升级后移交**给了 `langchain.agents.create_agent`（配 middleware 扩展体系，langchain 1.0，2025-10-22 发布）；`langgraph-prebuilt` 里的同名函数只是一个打满废弃标记的兼容壳（`chat_agent_executor.py:274-308`）：

```python
@deprecated(
    "create_react_agent has been moved to `langchain.agents`. Please update your "
    "import to `from langchain.agents import create_agent`.",
    category=LangGraphDeprecatedSinceV10,
)
def create_react_agent(model, tools, *, prompt=None, response_format=None,
                       pre_model_hook=None, post_model_hook=None, ...):
```

为什么这个安排反而合理？**create_agent 的本体仍然是一张 StateGraph**——middleware（before_model/after_model 钩子、动态 prompt、 summarization、HIL 审批）最终都编译成图中节点或节点包装。理解了第二~四章，middleware 就不是魔法。本章解剖这个兼容壳，因为它仍是"**官方示范的 StateGraph 用法**"与 ToolNode 的载体。

## 10.2 create_react_agent 解剖：一张手拼的 ReAct 图

【源码证据】`chat_agent_executor.py:862-1002`。核心状态与图结构：

```python
class AgentState(TypedDict):
    messages: Annotated[list[AnyMessage], add_messages]
    remaining_steps: RemainingSteps          # managed 虚拟键：不占通道，读取时现算

workflow = StateGraph(state_schema=State)
workflow.add_node("agent", RunnableCallable(call_model, acall_model))   # 模型节点
workflow.add_node("tools", tool_node)                                    # 工具节点
workflow.add_edge(START, "agent" if pre_model_hook is None else "pre_model_hook")
workflow.add_conditional_edges("agent", should_continue, path_map=agent_paths)
workflow.add_edge("tools", "agent")
```

`should_continue`（`:831-859`）是整个 ReAct 循环的开关，值得整段精读：

```python
def should_continue(state) -> str | list[Send]:
    last_message = messages[-1]
    if not isinstance(last_message, AIMessage) or not last_message.tool_calls:
        if post_model_hook is not None:     return "post_model_hook"
        elif response_format is not None:   return "generate_structured_response"
        else:                               return END
    else:
        if version == "v1":
            return "tools"                              # v1：工具节点内部串行/自己并行
        elif version == "v2":
            return [Send("tools", ToolCallWithContext(tool_call=call, state=state))
                    for call in last_message.tool_calls]  # v2：每个工具调用 = 独立 BSP 任务
```

三个细节沉淀着工程经验：

1. **v2 用 Send 把每个 tool_call 拆成独立任务**——不同工具并行执行、每个调用独立 checkpoint/独立重试，这是 Send API（4.7）的最佳教材。
2. **remaining_steps 保护**：`_are_more_steps_needed`（`:620-634`）发现 `remaining_steps < 2` 且模型还想调工具时，直接让 agent 节点返回一句"Sorry, need more steps..."而不是让引擎抛 `GraphRecursionError`——**把"步数打满"从异常降级为正常业务回复**，调用方拿到的是合法消息而非错误。
3. **pre/post_model_hook** 是两个可选节点：`pre_model_hook` 通过输出 `llm_input_messages` 改写进入模型的消息（典型：检索注入、历史压缩）；`post_model_hook` 挂在 agent 之后、条件边之前（典型：守门校验、强制工具审批）。1.x 的 middleware 本质是把这类"包裹模型调用"的模式标准化了。

## 10.3 ToolNode：工具执行的完整安全链

【源码证据】`tool_node.py:622+`。ToolNode 继承 RunnableCallable，输入是"带 tool_calls 的最后一条 AIMessage"或 Send 载荷，输出是 ToolMessage 列表（或 Command 列表）。执行流水线：

```
_parse_input（取 tool_calls；识别 Send 的 tool_call_with_context 载荷 :1224-1266）
  → 每个 call 构造 ToolRuntime（state/config/context/store/stream_writer/tool_call_id :793-826）
  → 并行执行：sync 走 get_executor_for_config 线程池 executor.map；async 走 asyncio.gather（:793-858）
      → 可选 wrap_tool_call 拦截器（ToolCallRequest + 可多次 execute，支持重试/改写 :132-199）
      → 参数注入：InjectedState/InjectedStore/ToolRuntime；先剥离 LLM 可能伪造的注入参数再回填
        （:1421-1429："Strip any caller-supplied values for injected args ... prevents an LLM
          from forging hidden InjectedToolArg fields via ToolCall.args."）——安全设计点
      → tool.invoke → 结果归一化（ToolMessage / Command 校验）
  → 错误处理：handle_tool_errors 策略（bool/类型集/callable）→ 失败转成 status="error" 的
    ToolMessage 回给模型继续对话；GraphBubbleUp（interrupt）永远原样上抛（:982-1012）
  → _combine_tool_outputs：Command(graph=PARENT, goto=...) 多条合并为一条父图命令（:862-920）
```

最值得学的是**错误也是消息**：工具失败默认不炸图，而是变成 `ToolMessage(status="error")` 喂回模型，让 LLM 自己决定重试、换路还是道歉——这是 agentic 系统鲁棒性的关键一环（`_default_handle_tool_errors` 只吞参数校验类异常 `ToolInvocationError`，其余 re-raise，`:383-391`）。工具要"修改状态并跳转"时返回 `Command`，ToolNode 会校验 update 里必须含与本 tool_call_id 匹配的 ToolMessage（消息历史一致性，`:1503-1579`）。

## 10.4 多智能体拓扑：框架不管，生态管

LangGraph 本体对"多智能体"的立场是：**没有内置的多智能体抽象，只有图**。常见拓扑全部是"如何组织几张图"的模式问题：

```
network（网状）          supervisor（主管式）        hierarchical（层级式）
A ◄──► B                ┌─────────┐                ┌─────────┐
 │  ╲ ╱  │              │supervisor│─派活→ 子agent们  │ 父supervisor│─→ 子团队(各含子supervisor)
 ▼  ╳   ▼              └────┬────┘                └─────────┘
 C      D                   │汇总
（互相握手，Command(goto)）  （子 agent 状态汇总回 supervisor 状态）
```

实现手段只有三招：**共享状态**（多 agent 读写同一 StateGraph 的键，reducer 合并）、**消息传递**（Send 派发 + 各自子图）、**握手**（节点返回 `Command(goto="另一agent", update=...)`）。官方生态提供了两个现成模式库（独立包）：`langgraph-supervisor`（主管分派）与 `langgraph-swarm`（握手移交 handoff）；更高层的是 `deepagents`（规划 + 子智能体 + 文件系统的"深度智能体"脚手架）。 swarm 的 handoff 工具底层正是 `Command(graph=Command.PARENT, goto=...)`。

## 10.5 本章小结

- 1.x 的官方 agent 入口是 `langchain.agents.create_agent`（middleware 体系）；prebuilt 的 create_react_agent 是废弃兼容壳，但仍是 StateGraph 组装范本。
- ReAct 循环 = 双节点 + 条件边 + Send 拆分工具调用；remaining_steps 保护把递归超限降级为业务回复。
- ToolNode 是"工具执行的安全链"：注入参数防伪造、错误转 ToolMessage、Command 一致性校验、wrap_tool_call 拦截器。
- 多智能体没有框架级抽象——supervisor/swarm/hierarchical 都是图模式，生态包提供现成实现。

---

# 十一、部署形态：LangGraph Server / Platform / Studio

> 本章对应源码/包：`libs/cli`（langgraph-cli）、`libs/sdk-py`/`libs/sdk-js`（langgraph-sdk）、`libs/api` 相关（langgraph-api，Server 实现）；开源仓库不含闭源 Platform 控制面。

## 11.1 先说白话：库形态与平台形态

同一份 `CompiledStateGraph` 有两种运行方式：**库形态**（进程内 `graph.invoke/stream`，你自己管进程、并发、部署）与**服务形态**（LangGraph Server 把图包成 HTTP API + 持久任务队列 + 流推送，Platform 在其上加多租户/调度/观测）。服务形态不是"重新实现"，而是**把第五、六章的能力暴露为 API**：thread 即 checkpoint 三元组的 thread 维度，runs 即一次 invoke/interrupt/resume 生命周期，cron/webhook 不过是"谁替你定时调 invoke"。1.0 之前该产品线叫 "LangGraph Platform"，rc1 实证了 "rename away from LangGraph Platform"（现归入 LangSmith Deployment 品牌）——名字常变，能力契约稳定。

## 11.2 三个包的分工

| 包 | 角色 | 关键命令/接口 |
|---|---|---|
| `langgraph-cli` | 开发者工具 | `langgraph dev`（本地内存版 Server + Studio 热连）、`langgraph up`（Docker 全功能版）、`langgraph build`（出镜像） |
| `langgraph-api` | Server 实现（开源） | 图注册、REST API、流式网关、cron/webhook；依赖主包 |
| `langgraph-sdk` | 客户端 SDK | `langgraph_sdk.get_client()`：threads/runs/cron/store 全套；`RemoteGraph`（远端图当本地 Runnable 用，主包 `pregel/remote.py` 配合） |

本地开发的枢纽是仓库根的 `langgraph.json`（声明图入口与依赖），`langgraph dev` 起一个带热重载的本地 Server，浏览器打开 LangGraph Studio（VS Code 扩展或 web）连上去即可可视化调试——**Studio 本质是 Server API 的一个客户端**，没有私有通道，这也是"本地 dev 与生产 Platform 行为一致"的原因。

## 11.3 服务形态的核心概念

- **Assistant**：图 + 可配置项（config、metadata）的命名模板；同一张图可派生多个 assistant。
- **Thread**：一次会话/任务的 checkpoint 线（thread_id 维度），`GET/POST threads` 管理，`threads/{id}/state` 读取/修改状态。
- **Run**：thread 上的一次执行，支持 `interrupt/resume`（HTTP 版的 Command(resume=)）、多 run 并发与 `multitask_strategy`（interrupt/rollback/enqueue——同 thread 新旧 run 冲突策略，本质是 checkpoint 分支操作）。
- **流式**：`stream_mode` 与本地完全同名同义（values/updates/messages/...），SSE 推送。
- **Cron / Webhook / 定时任务**：服务端替你调度 invoke。
- **Store**：跨 thread 长期记忆的 HTTP 化（同样的 namespace/语义检索契约）。

何时需要服务形态的经验判据：需要**人在环路的网页交互**（用户可能几小时后才答复，进程不能一直挂着）、**跨请求的长期记忆**、**团队共享的智能体资产与观测**。纯批处理或本地脚本，库形态足够。

## 11.4 本章小结

- Server/Platform 不是第二套引擎，而是 checkpoint/interrupt/stream 能力的 HTTP 化；thread/run/assistant 是 checkpoint 三元组与图配置的服务化命名。
- cli/api/sdk 三包开源，Studio 是标准客户端；`langgraph dev` 与生产行为同构，本地调试成本极低。
- 选服务形态的判据是"人审延迟、长期记忆、团队协作"三类需求，而非技术逼格。

---

# 十二、贯通视图：三条时间线看懂 LangGraph 全貌

## 12.1 时间线一：编译期（compile 全程）

```
StateGraph(State, context_schema=Ctx)
 └─ __init__：_add_schema×3（state/input/output）→ channels 字典成形        [2.2]
 └─ add_node/add_edge/add_conditional_edges：只填 nodes/edges/branches/waiting_edges
 └─ compile(checkpointer, store, interrupt_before/after, ...)
     ├─ validate：端点存在性、入口存在性                                    [3.3]
     ├─ serde 白名单（STRICT_MSGPACK 时按 schema 集合构建）                 [5.3]
     ├─ 节点默认值落实 + __error_handler__ 兜底节点注册                      [3.2]
     ├─ CompiledStateGraph（= Pregel）构造：channels += START/branch:to:*/join:*
     ├─ attach_node×N：PregelNode(triggers/channels/mapper/writers)         [3.5]
     ├─ attach_edge×N：writers 追加 branch:to: 写入 / join 栅栏通道          [3.6]
     ├─ attach_branch×N：writers 追加分支函数（读 fresh 状态 → 算目标 → 写）  [3.6]
     └─ validate → 可执行图（Runnable 接口：invoke/stream/batch/…）
```

## 12.2 时间线二：一次 invoke 的引擎循环（叠加 interrupt 与恢复）

```
invoke(input, config={thread_id, recursion_limit, ...})
 ├─ SyncPregelLoop 启动：checkpointer.get_tuple 载入最近档（或 empty_checkpoint）
 │    └─ _first：判定全新/恢复/重放/时间旅行；Command(resume) → put_writes(RESUME)   [6.4]
 │                   全新输入 → 写 __start__ 通道 → put_checkpoint(source=input)
 ├─ while loop.tick():                                             ←───────┐
 │    ├─ prepare_next_tasks：TASKS 里的 Send(PUSH) + 版本号唤醒的节点(PULL)  [4.3] │
 │    ├─ interrupt_before / should_interrupt：命中 → GraphInterrupt ──→ 落档停机 │
 │    ├─ PregelRunner.tick：线程池/事件循环并发执行任务                          │
 │    │    ├─ run_with_retry：RetryPolicy 退避重试 / TimeoutPolicy 看门狗       │
 │    │    ├─ 节点执行：读通道状态(mapper 强转) → 业务逻辑 → 返回部分状态/Command/Send
 │    │    │    └─ interrupt()：首次→GraphInterrupt 上浮；重放→返回 resume 值    [6.2]
 │    │    └─ commit：任务完成即 put_writes 持久化 pending writes               │
 │    └─ loop.after_tick：apply_writes（版本 bump/归约/自焚/finish）            │
 │         → values 流 → put_checkpoint(source=loop) → interrupt_after 判定 ────┘
 ├─ status=out_of_steps → GraphRecursionError；interrupt → 输出 Interrupts
 └─ 返回 loop.output（v1 裸 dict / v2 GraphOutput）
```

恢复时的分叉点只有一个：输入是 `Command(resume=...)` 或 `None`，`_first` 走恢复分支，此后循环照旧——**恢复不是特殊模式，只是"带着半成品现场跑同一个循环"**。

## 12.3 时间线三：跨调用的一段对话（thread 的一生）

```
第 1 轮 invoke("你好")           checkpoint 链: [input] → [loop] → [loop] → ...
第 2 轮 invoke(None, resume)     checkpoint 链追加（同一 thread_id）
第 N 轮 …                        PREVIOUS/Store 持续累积
        │
        ├─ 短期记忆：同 thread 的 channel_values 逐轮演进（add_messages 追加对话）
        ├─ 长期记忆：节点里 store.put/search（namespace 维度，跨 thread 可见）
        ├─ 观测：每个 checkpoint 即一次"时间旅行"坐标（get_state_history）
        └─ 清理：delete_thread / prune（delta 通道下注意保留快照祖先）           [5.3]
```

## 12.4 从源码中提炼的设计模式视角

1. **编译器前端 / 引擎分离**：StateGraph、@entrypoint、（langchain 的）create_agent 都是前端，产物统一是 Pregel——"多前端一引擎"是多范式框架的经典布局。
2. **异常即控制流**：GraphBubbleUp 家族（interrupt/ParentCommand/GraphDrained）把"暂停、上浮跳转、协作停机"表达为异常，重试层/执行器/循环层每层都认识这个基类——比状态机标志位干净得多。
3. **乐观并发版本号**：channel_versions/versions_seen 两个单调表替代了全部锁——BSP 模型里"可见性切换点"就是版本号切换点。
4. **SPI 三件套**：checkpointer/store/cache 全部是"主包只留抽象、实现独立发版"——引擎零依赖膨胀，后端自由替换。
5. **通道即生命周期**：Ephemeral/Topic/NamedBarrier/LastValueAfterFinish 的差异全部封装在 `update/consume/finish` 三个回调里，引擎对不同通道零特判（`apply_writes` 一视同仁）。
6. **确定性 ID**：task_id/checkpoint_id 都是内容/时间哈希——恢复、去重、幂等全部建立在"重算必相同"之上。

---

# 十三、工程实践与常见坑

## 13.1 选型决策树

```
需要标准 ReAct + 工具调用？
 ├─ 是 → langchain.agents.create_agent（middleware 定制）；不够再下沉 StateGraph
 └─ 否 → 控制流以"数据加工"为主、阶段线性？
          ├─ 是 → @entrypoint + @task（函数式）
          └─ 否（动态路由/多角色/循环协作）→ StateGraph
               └─ 需要并行子任务 → Send；需要节点内跳转 → Command；需要人审 → interrupt
```

## 13.2 常见坑（每条都有源码出处）

1. **并发写单值键报错** `InvalidUpdateError: Can receive only one value per step`（`last_value.py:61-64`）：两个节点在同一超步写 `query: str`。修法：改成 `Annotated[T, reducer]`，或者调整图让写入不同超步，或用 `Overwrite`（每超步限一个）。
2. **interrupt 前的副作用被重放**：恢复=节点重放，`interrupt()` 之前的 API 调用会执行两次。修法：把副作用搬进 `@task`（函数式）或搬到 interrupt 之后；图式下把副作用节点放在 interrupt 节点之前（已完成任务的写入从 checkpoint 恢复，不会重跑——`match_cached_writes`）。
3. **状态里放了不可序列化对象**：第一次 checkpoint 就炸（serde 的 msgpack 编码错误）。修法：连接/句柄放 `context_schema`（Runtime.context 不入档），数据放状态。
4. **recursion_limit 撞墙**：默认 25 超步。循环型图记得 `config={"recursion_limit": N}`；预制件 agent 有 remaining_steps 软保护，但自定义图没有——自己设计出口（`updated_channels.isdisjoint(trigger_to_nodes)` 即图终点）。
5. **升级 langgraph 后老线程行为怪异**：checkpoint 带格式版本与通道命名，框架内置 `_migrate_checkpoint` 兼容三代布局（`state.py:1626-1729`）；但**改了图结构**（增删节点/改名）后旧档与新图通道不对应——灰度期给 thread 加版本标记或换 thread_id 前缀。
6. **大状态 + 高频步的写放大**：每超步全量快照被更新的通道。修法：状态瘦身（大文本放 Store）、用 1.2 的 `DeltaChannel`（beta）增量存档、Durability 用 "exit"/"async" 权衡可靠性。
7. **同步节点里 timeout 不生效**：`TimeoutPolicy` 依赖 asyncio 取消，sync 节点（线程池里跑）无法被安全打断——`sync_timeout_unsupported` 直接拒绝该组合（`_internal/_timeout.py`）；重 IO 的节点写 async 版本。
8. **自定义对象跨进程/存档消失**：默认 serde 白名单之外仅告警放行，`LANGGRAPH_STRICT_MSGPACK=true` 后直接拒绝；自己类要跨存档就注册白名单或改用 pydantic（`model_dump` 路径内建支持）。
9. **Send 大扇出压垮线程池**：`max_concurrency`（config）限流，任务排队而非拒绝；扇出对象是 Send 的 arg（可缩水，只传必要字段——ToolNode 的 Send 载荷就是示范：只带 tool_call，state 由 CONFIG_KEY_READ 现场水合）。
10. **想"节点内直接读最新状态"**：节点入参是快照；要"叠加自己已写入的最新视图"用 `get_config()["configurable"]["__pregel_read"]`（即 CONFIG_KEY_READ，ToolNode 的 `_extract_state` 就是这么干的，`tool_node.py:1298-1313`）。

## 13.3 调试与可观测

- `compile(debug=True)` 或 `stream_mode="debug"`：checkpoints+tasks 全量事件流（任务级 error/interrupts/子图快照）。
- `get_state(config)` / `get_state_history(config)`：随手看"卡在哪一步、哪个任务挂了中断"。
- LangSmith：节点 metadata（`langgraph_step/node/triggers/path`）在 4.3 节已见——追踪树就是按这些字段组织的；`TAG_HIDDEN` 可把 START 等内部节点藏出视图。
- Studio：可视化单步、改状态重放（底层就是 update_state + fork）。

## 13.4 本章小结

坑的分布有规律：**编译期能拦的都拦了**（schema/端点/reducer 签名）；剩下全是**运行期与持久化交界处**的（重放副作用、序列化、写放大、版本迁移）。所有修法最终都指向同一条心法：**想清楚"这一步在 checkpoint 里长什么样"**。

---

# 十四、附录

## 14.1 关键类/函数速查表

| 名称 | 位置（相对 libs/） | 一句话 |
|---|---|---|
| StateGraph | langgraph/langgraph/graph/state.py:131 | 图 API builder：节点/边/条件边/等待边六件套 |
| CompiledStateGraph | 同上：1404 | compile 产物，直接继承 Pregel |
| add_messages | langgraph/langgraph/graph/message.py:61 | 消息归约器：追加 + id upsert + RemoveMessage |
| MessagesState | 同上：372 | 现成的"只有 messages"状态 |
| Pregel | langgraph/langgraph/pregel/main.py:450 | 引擎主类：BSP 循环 + invoke/stream/get_state 系 |
| PregelLoop / Sync/Async | pregel/_loop.py:158 | 单次运行的循环载体：_first/tick/after_tick/_put_checkpoint |
| PregelRunner | pregel/_runner.py:135 | 任务并发执行与 commit |
| apply_writes | pregel/_algo.py:232 | 超步提交：版本 bump/归约/consume/finish |
| prepare_next_tasks / prepare_single_task | pregel/_algo.py:392 / :524 | 任务规划：PUSH+PULL、确定性 task_id、config 注入 |
| run_with_retry | pregel/_retry.py:573 | 重试（指数退避+jitter）与超时看门狗 |
| BackgroundExecutor | pregel/_executor.py:40 | sync/async 并发载体（max_concurrency） |
| PregelNode | pregel/_read.py | 节点运行形态：triggers/channels/mapper/writers |
| ChannelWrite / ChannelRead | pregel/_write.py / _read.py | 节点写通道/读状态的原子件 |
| BaseChannel 及七个实现 | langgraph/langgraph/channels/* | 通道契约与 LastValue/BinOp/Ephemeral/Topic/Barrier/AfterFinish/Delta |
| Send / Command / interrupt / Interrupt / Overwrite | langgraph/langgraph/types.py:732 / :827 / :887 / :584 / :1033 | 动态控制流与 HIL 的公共词汇表 |
| StreamMode / StreamPart / GraphOutput | types.py:131 / :352 / :379 | 七频道与 v2 类型化输出 |
| StateSnapshot / PregelTask | types.py:711 / :665 | get_state 的返回形态 |
| RetryPolicy / TimeoutPolicy / CachePolicy | types.py:427 / :461 / :530 | 节点策略三件套 |
| Runtime / get_runtime / ExecutionInfo | langgraph/langgraph/runtime.py:125 / :296 / :27 | run 级上下文包与注入 |
| GraphBubbleUp 家族 | langgraph/langgraph/errors.py:50+ | interrupt/ParentCommand/GraphDrained 控制流异常 |
| GraphRecursionError / InvalidUpdateError | errors.py:67 / :90 | 两大高频运行时错误 |
| @entrypoint / @task / entrypoint.final | langgraph/langgraph/func/__init__.py:262 / :59 / :475 | 函数式 API |
| Checkpoint / CheckpointMetadata / CheckpointTuple | checkpoint/langgraph/checkpoint/base/__init__.py:93 / :39 / :140 | 存档结构与寻址元数据 |
| BaseCheckpointSaver / JsonPlusSerializer | 同上：177 / serde/jsonplus.py:82 | 存储契约 / ormsgpack serde |
| InMemorySaver（=MemorySaver） | checkpoint/langgraph/checkpoint/memory/__init__.py:33 / :625 | 三表内存实现 |
| SqliteSaver / PostgresSaver | checkpoint-sqlite/.../:45 / checkpoint-postgres/.../:40 | 文件级 / 生产级存储 |
| BaseStore / Item / Op 族 / IndexConfig | checkpoint/langgraph/store/base/__init__.py:708 / :51 / :157+ / :578 | 长期记忆契约与语义检索 |
| InMemoryStore | checkpoint/langgraph/store/memory/__init__.py:136 | 内存实现（余弦 + max-pooling） |
| create_react_agent（废弃壳）/ ToolNode | prebuilt/langgraph/prebuilt/chat_agent_executor.py:278 / tool_node.py:622 | ReAct 组装范本 / 工具执行安全链 |
| ManagedValue（IsLastStep/RemainingSteps） | langgraph/langgraph/managed/ | 不入档的虚拟状态键 |

## 14.2 初学者学习路线（动手向）

1. **第 1 天：最小图**。StateGraph + 两个节点 + `Annotated[list, operator.add]` 归约，`stream_mode="updates"` 看每步增量；再故意让两个节点同超步写 `str` 键，亲眼看 `InvalidUpdateError`（对应第二、三章）。
2. **第 2 天：持久化与时间旅行**。加 `InMemorySaver`，多轮 invoke 同一 thread_id；`get_state_history` 遍历，挑一个旧 checkpoint_id 用 `invoke(None, config)` 重放，体会 fork 分支（第五、十二章）。
3. **第 3~4 天：人在环路**。节点里 `interrupt()` + `Command(resume=)`；试多 interrupt 顺序恢复与按 id 映射恢复；体会"interrupt 前代码被重放"（第六章）。
4. **第 5 天：Send 与并行**。map-reduce 范式（Send 扇出 + operator.add 归约）；把工具调用换成 ToolNode，观察 Send 拆分（4.7、10.2）。
5. **第 1 周：函数式 API**。同一业务用 @entrypoint/@task 重写一遍，对比 interrupt 恢复时的行为差异（第八章）。
6. **第 2 周：源码对照**。断点/单步 `Pregel.stream` 主循环（main.py:2964）→ `loop.tick` → `prepare_single_task` → `runner.tick` → `apply_writes` → `after_tick`，把第十二章时间线二走一遍；再看 `_first` 的 is_resuming/is_time_traveling 判定。
7. **进阶**：实现一个自定义 checkpointer（只实现 get_tuple/list/put/put_writes）；写一个 StreamTransformer（v3 投影）；用 langgraph dev + Studio 走完整调试闭环。

## 14.3 源码阅读入口清单（20 个关键文件）

按"必读度"排序，路径相对 `D:\code\3rd\langgraph\libs`：

1. langgraph/langgraph/pregel/main.py（Pregel 类 docstring 450-547、stream 主循环 2891-3011、get_state_history/update_state）
2. langgraph/langgraph/pregel/_algo.py（should_interrupt 155、apply_writes 232、prepare_next_tasks 392、prepare_single_task 524）
3. langgraph/langgraph/pregel/_loop.py（PregelLoop 158、tick 599、after_tick 683、_first 848、_put_checkpoint 1081）
4. langgraph/langgraph/graph/state.py（StateGraph 131、compile 1177、attach_node 1444、attach_edge 1551、_get_channels 1815、_migrate_checkpoint 1626）
5. langgraph/langgraph/types.py（公共词汇表全文：Send 732、Command 826、interrupt 887、StreamPart 352、GraphOutput 379、策略三件套）
6. langgraph/langgraph/channels/base.py + last_value.py + binop.py（通道契约与两大实现）
7. langgraph/langgraph/graph/message.py（add_messages 61）
8. langgraph/langgraph/graph/_branch.py（条件边运行时 BranchSpec）
9. langgraph/langgraph/pregel/_runner.py（tick 176、commit 574）
10. langgraph/langgraph/pregel/_retry.py（run_with_retry 573、_arun_with_timeout 422）
11. langgraph/langgraph/pregel/_read.py（PregelNode）+ _write.py（ChannelWrite）+ _call.py（@task 的 future 底座）
12. langgraph/langgraph/func/__init__.py（entrypoint 262、task 59、final 475，全文 620 行值得一口气读完）
13. langgraph/langgraph/runtime.py（Runtime 125、get_runtime 296）
14. langgraph/langgraph/errors.py + _internal/_constants.py（异常家族 + 保留键全景）
15. langgraph/langgraph/stream/_types.py + run_stream.py + _mux.py（v3 流协议）
16. checkpoint/langgraph/checkpoint/base/__init__.py（Checkpoint 93、BaseCheckpointSaver 177、WRITES_IDX_MAP 796、create_checkpoint 830）
17. checkpoint/langgraph/checkpoint/memory/__init__.py（InMemorySaver：三表 68、get_tuple 230、put 421）
18. checkpoint/langgraph/checkpoint/serde/jsonplus.py + _msgpack.py（ormsgpack serde 与白名单）
19. checkpoint/langgraph/store/base/__init__.py + store/memory/__init__.py（长期记忆契约与实现）
20. prebuilt/langgraph/prebuilt/chat_agent_executor.py + tool_node.py（ReAct 范本 + 工具安全链）

## 结语

回到开篇的问题：LangGraph 用什么回答了"智能体该怎么被工程化"？

- 用一张 **Pregel BSP 循环**回答了"带环、带分支、带并发的控制流如何确定性地执行"；
- 用 **Channel + reducer** 回答了"并发节点的状态合并如何不靠锁"——版本号与归约器把冲突摊在明面上；
- 用 **每个超步一个 checkpoint** 回答了"长时任务如何暂停、恢复、重放、修正"——interrupt、time-travel、durable execution 都是它的下游；
- 用 **configurable 注入 + Runtime + SPI** 回答了"引擎如何做到零特判、后端如何可插拔"；
- 用 **"图即前端产物"**回答了"预制件与定制的边界"——create_agent/middleware 也不过是编译到同一台引擎的糖。

这些机制单独看都不复杂，复杂的是它们环环相扣：task_id 的确定性哈希支撑重放，版本表支撑唤醒，存档支撑暂停，暂停支撑人审，人审支撑生产。读完本文的证据链，再去读 langchain 的 create_agent、langgraph-supervisor 的 handoff、deepagents 的规划循环，都会是"似曾相识"——它们全部编译成你已经读过的那张图。

---

*本文基于 LangGraph 1.2.12 源码（tag 1.2.12，commit 49cce0c）逐行实证撰写；版本演进结论经 PyPI 历史 wheel（0.2.24/0.2.60/1.1.0）解包 grep 与 GitHub Release API 交叉验证。若你使用的版本更新，请以官方迁移指南为准核对本文 1.6 节的时效性结论。*








