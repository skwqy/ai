# LangChain 深度源码解析（写给初学者的架构全景）

> **本文基于的源码**：`D:\tmp\langchain-src`，即 GitHub 仓库 [langchain-ai/langchain](https://github.com/langchain-ai/langchain) 的 master 分支浅克隆（2026-10-04 快照），对应发布版本：**langchain 1.4.3**（2026-09-28）、**langchain-core 1.6.6**（2026-09-29）、langchain-classic 1.0.8、langgraph 1.2.12。文中所有【源码证据】的文件路径与行号均为对该快照实际读取所得（路径以仓库根为基准，如 `libs/core/langchain_core/runnables/base.py`）。
>
> **版本取舍说明**：LangChain 的 1.x 是一次"重整山河"式的大版本——旧教程里的 `LLMChain`、`AgentExecutor`、`initialize_agent` 已整体迁入兼容包 `langchain-classic`，1.x 主包只剩下 `create_agent` 等少数 API。本文以 **1.x 架构为主线**（这是新项目应该学的），把旧世界的 `Chain/AgentExecutor` 放在第九章"考古现场"专门讲——因为你要读懂网上 90% 的旧教程、也为了看懂框架为什么长成今天这样。各版本演进对照见 1.6 节。
>
> **阅读约定**：每章"先白话、后源码"——先用一两句人话讲清"这是什么、为什么需要它"，再给出源码证据链（`libs/包名/.../模块.py` + 类名 + 行号 + 代码片段）。行号只对该快照精确，读者打开源码按类名 + 方法名定位即可。
>
> **写给 Java 开发者的额外约定**：本文作者是 Java/Spring 背景的学习者，文中会不定期用 Spring 类比（如"`Runnable` 之于 LangChain，约等于 `BeanFactory` 之于 Spring"），第十一章附录给出完整对照表。类比只是路标，不是精确对应。

## 如何读这份文档

如果你是 LangChain 初学者，推荐两遍读法：

- **第一遍（建立地图，1~2 小时）**：只读第一章（总览）每一节的开头白话段、各章的"小结"节、以及第十章（贯通视图）。目标是能回答：LangChain 分哪几层、为什么 1.0 要把 `Chain/AgentExecutor` 搬走？`create_agent` 背后的图长什么样？一次 `chain.invoke` 从输入到输出经历了什么？
- **第二遍（深入源码）**：对照每一章的【源码证据】，打开源码逐行读。顺序建议：第二章（Runnable 协议，一切的地基）→ 第三章（消息与模型抽象）→ 第七章（create_agent 与中间件，1.x 的主角）→ 第八章（LangGraph 运行时）→ 第四章、第五章（提示词/工具/RAG 栈，随用随查）→ 第六章（可观测性）→ 第九章（旧世界考古）。

前置知识：Python 基础语法、`pydantic` 的类型声明写法（看懂字段注解即可，不必精通）、会调一次 OpenAI/任意大模型 API。不需要任何 LLM 工程经验。

---

# 一、总览：LangChain 的定位、设计哲学与整体架构

## 1.1 一句话定位

**LangChain 是一套"以 Runnable 协议为核心、以标准策略接口接入大模型生态"的 LLM 应用开发框架**：它把"调用哪家模型、怎么格式化提示词、怎么解析输出、怎么接向量库"这些重复劳动抽象成统一接口（`BaseChatModel`、`VectorStore`、`Embeddings`、`BaseLoader`……），又把"模型↔工具循环执行"这个最常见的应用模式沉淀为开箱即用的 `create_agent`（1.x），再以图运行时（LangGraph）解决持久化、人工审批、断点恢复这类生产问题。

用 Spring 的话说：**langchain-core 是 spring-core（地基与统一抽象），langchain 是 spring-context（应用层组装），LangGraph 是"Web 层+调度内核"，LangSmith 是 Micrometer + Grafana（可观测性）**。

它解决的不是"怎么调一个模型 API"（那是 SDK 的事），而是**LLM 应用的组织问题**：模型切换要不要改业务代码？提示词与业务逻辑怎么解耦？模型输出怎么可靠地变成结构化数据？文档怎么切块、向量化、检索？"模型思考→调用工具→观察结果→再思考"这个循环谁来跑、中途挂了怎么办？

时间坐标：2022 年 10 月，Harrison Chase 发布 LangChain（PyPI 上 `langchain==0.0.1` 的上传时间为 **2022-10-25**，经 PyPI 元数据实证），是"LLM 应用框架"这个品类最早的开拓者之一；2025 年 10 月 17 日发布 1.0 稳定版（与 LangGraph 1.0 同日），标志着架构从"链式胶水层"转型为"智能体框架 + 图运行时"。

## 1.2 设计哲学：读源码前先记住五句话

1. **一切皆 Runnable**。LangChain 里能被"执行"的东西——模型、提示词模板、解析器、检索器、工具、乃至整个链——都实现同一个 `Runnable` 协议：`invoke`/`batch`/`stream`/`transform` 四个统一入口。因此任意两个组件都可以用 `|` 拼起来，且拼出来的链**自动获得**同步、异步、批处理、流式全部四种执行形态（见第二章）。这是整个框架最核心的一笔投资，地位相当于 Spring 的 `BeanFactory`。
2. **集成为"策略接口 + 适配器"，核心零依赖厂商**。模型是 `BaseChatModel`（子类在 `langchain-openai` 等伙伴包）、向量库是 `VectorStore`、嵌入是 `Embeddings`、文档加载是 `BaseLoader`——`langchain-core` 的运行时依赖里没有任何一家模型厂商（依赖表见 1.4 节，只有 langsmith/httpx/pydantic 等基础设施）。这与 Spring"一个 `PlatformTransactionManager` 接口 + JDBC/JPA/JTA 各自适配"的配方一模一样。
3. **编排下沉到图运行时，链只是声明式糖**。0.x 时代 LangChain 自己实现"链"（`Chain`）和"智能体循环"（`AgentExecutor`）；1.x 把执行引擎整体让位给 LangGraph（`StateGraph`/Pregel 运行时），`create_agent` 只是一个"把模型节点和工具节点连成环"的**图构建器**。于是持久化（checkpoint）、人工介入（interrupt）、流式事件这些难题只解决一次，所有智能体共享。
4. **可观测性是一等公民**。每个组件执行时都会通过回调体系（`callbacks/`）报告"运行树"（run tree）：谁在什么时间、用什么输入、花了多少 token、产出了什么。LangSmith 只是这棵树的一个采集端（`LangChainTracer`），你完全可以自己写 handler 接到别处。这相当于 Spring Boot 的 Actuator/Micrometer 基建。
5. **演进而非推翻**。三次大重构（0.1 拆包、0.2 与 community 解耦、1.0 裁剪主包）都遵守同一条纪律：旧 API 先标记废弃、给迁移期，最后整体搬到 `langchain-classic` 兼容包而不是删除。旧代码今天仍可运行——这是它作为"第一个吃螃蟹"的框架还能活到第四年的关键。

## 1.3 模块分层全景

LangChain 不是一个大包，而是**一个核心 + 一圈按需安装的集成包 + 一个独立运行时 + 若干周边产品**组成的生态。monorepo（langchain-ai/langchain）的 `libs/` 目录实测包含 7 个子项目（core、langchain、langchain_v1、partners、standard-tests、text-splitters、model-profiles），其中 partners 下有 17 个伙伴包。按"用户感知"分层如下：

```
┌──────────────────── 生态产品（独立部署，不随 pip 装进应用） ────────────────────┐
│  LangSmith（追踪/评测/提示词管理）  LangGraph Platform（Agent 托管部署）        │
│  LangGraph Studio（图调试 UI）      deepagents（"深度智能体"脚手架包）          │
├──────────────────────── 编排运行时（独立仓库/独立包） ──────────────────────────┤
│  langgraph 1.2.x（StateGraph、Pregel 执行引擎、checkpointer、interrupt、        │
│                   Send API、subgraph、stream modes、ToolNode/ToolRuntime）      │
├──────────────────────────── 应用层 ────────────────────────────────────────────┤
│  langchain 1.4.x（= libs/langchain_v1）                                        │
│    create_agent / AgentState / middleware 体系 / init_chat_model /             │
│    langchain.mcp（MCP 适配） / tools.tool_node / 重新导出 core 的消息与工具      │
├──────────────────────────── 集成层（按需 pip install） ─────────────────────────┤
│  partner 包（libs/partners/*，17 个）：langchain-openai、langchain-anthropic、  │
│    langchain-ollama、langchain-deepseek、langchain-fireworks、langchain-groq、 │
│    langchain-mistralai、langchain-xai、langchain-openrouter、                  │
│    langchain-perplexity、langchain-qdrant、langchain-chroma、                  │
│    langchain-huggingface、langchain-exa、langchain-nomic ……                    │
│  langchain-community（社区集成大杂烩，独立节奏维护，当前 0.4.x）                 │
│  langchain-text-splitters（文本切分算法集）                                     │
├──────────────────────────── 核心抽象层 ────────────────────────────────────────┤
│  langchain-core 1.6.x（= libs/core/langchain_core）                            │
│    runnables/（Runnable 协议与 LCEL 组合原语）   messages/（消息与内容块）       │
│    language_models/（BaseChatModel/BaseLLM/model profile）                      │
│    prompts/  output_parsers/  tools/（BaseTool、@tool）                         │
│    vectorstores/  embeddings/  document_loaders/  documents/                   │
│    callbacks/  tracers/（运行树与 LangSmith 采集）                              │
│    caches.py  rate_limiters.py  chat_history.py  indexing/（去重索引）          │
├──────────────────────────── 兼容层（旧 API 的归宿） ────────────────────────────┤
│  langchain-classic 1.0.x（= libs/langchain/langchain_classic）                 │
│    chains/（LLMChain、SequentialChain、RetrievalQA、ConversationChain……）      │
│    agents/（AgentExecutor、ReAct 系列旧 Agent）  memory/  retrievers/           │
│    document_loaders/  embeddings/  vectorstores/  evaluation/  graphs/ …       │
└────────────────────────────────────────────────────────────────────────────────┘
```

一句话记忆：**写新代码只碰 `langchain`（高层 API）和若干 partner 包；`langchain-core` 是你阅读源码理解机制的主战场；`langchain-classic` 只在维护旧代码时进来**。

## 1.4 模块依赖图（以各包 pyproject.toml 的 dependencies 实证）

包间依赖关系（箭头指向被依赖方）：

```
                    ┌──────────────────────────┐
                    │        LangSmith SaaS     │  ← 可观测性产品（HTTP 采集）
                    └────────────▲─────────────┘
                                 │ (LangChainTracer)
 langchain ──────────────► langchain-core ◄──────────── langchain-text-splitters
    │                            ▲  ▲                            ▲
    │                            │  │                            │
    ├──► langgraph ──────────────┘  │                    langchain-classic
    │         (运行时依赖 core)      │                    （依赖 core + text-splitters）
    │                               │
    └──► langchain-openai /  ───────┘   ← 所有 partner 包只依赖 core，不依赖 langchain
         langchain-anthropic / …           （互不认识，可任意组合）
         langchain-community
```

真实依赖声明（摘自各包 `pyproject.toml` 的 `dependencies`，已逐个验证）：

| 包 | 直接依赖（运行时） |
|---|---|
| **langchain-core** | langsmith≥0.3.45、httpx、tenacity、jsonpatch、PyYAML、typing-extensions、packaging、pydantic≥2.7.4、uuid-utils、langchain-protocol≥0.0.17 |
| **langchain** (1.4.3) | langchain-core≥1.6.3、**langgraph≥1.2.11**、pydantic |
| **langchain-classic** | langchain-core≥1.4.7、langchain-text-splitters≥1.1.2、langsmith、pydantic、SQLAlchemy、requests、PyYAML |
| **langchain-text-splitters** | langchain-core≥1.4.7（仅此一个） |
| **langchain-openai** | langchain-core≥1.6.6、openai≥2.45.0、tiktoken、certifi |

这张表本身就是一份架构说明：**langchain-core 被所有人依赖、自己只依赖基础设施**（pydantic 做模型/校验、langsmith 做追踪协议、tenacity 做重试——重试的"引擎"直接复用第三方，不重造轮子）；**langchain 与 partner 包互不依赖**（`create_agent` 接受任何 `BaseChatModel` 实例，所以 `langchain` 不需要认识 `langchain-openai`）；**langgraph 是 langchain 的唯一下层运行时**——1.x 的智能体没有第二条执行路径。

## 1.5 关键问题 → LangChain 方案映射（全文导览）

| LLM 应用的关键问题 | LangChain 的方案 | 详见 |
|---|---|---|
| 换模型（OpenAI→Anthropic→本地 Ollama）要改业务代码 | `BaseChatModel` 统一接口 + `init_chat_model("provider:model")` 按字符串装配 | 第三章 |
| 提示词散落在字符串拼接里，无法复用与管理 | `ChatPromptTemplate` / `MessagesPlaceholder` / few-shot 模板 | 第四章 |
| 模型输出是自由文本，下游代码没法用 | 输出解析器族 + `with_structured_output(schema)`（provider 原生结构化） | 第三章、第四章 |
| 每家模型的消息格式、内容块（图/音频/推理）都不一样 | 标准内容块（content blocks）+ `block_translators` 双向翻译 | 第三章 |
| "模型思考→调工具→看结果→再思考"的循环谁来跑 | `create_agent`：用 LangGraph 把 model/tools 两节点连成环 | 第七章 |
| 智能体中途要人工审批、要断点恢复、要限次限流 | LangGraph checkpointer + interrupt；中间件（HITL/限次/摘要/重试/降级） | 第七章、第八章 |
| 文档格式五花八门（PDF/HTML/Notion…） | `BaseLoader`（懒加载迭代器）+ `Document` 统一模型 | 第五章 |
| 长文档塞不进上下文 | text-splitters 切分算法族（RecursiveCharacterTextSplitter 等） | 第五章 |
| 语义检索要接各家向量库 | `Embeddings` + `VectorStore` 标准接口 + `as_retriever()` 变身 Runnable | 第五章 |
| 重复调用浪费钱、突发流量打爆限流配额 | `BaseCache` 缓存 + `InMemoryRateLimiter` 令牌桶 | 第三章 |
| 链和智能体是黑盒，出错无从下手 | callbacks/tracers 运行树 + `set_debug` + `astream_events` + LangSmith | 第六章 |
| 旧代码（0.x Chain/AgentExecutor）还能跑吗 | `langchain-classic` 兼容包原样保留，主包与旧世界彻底解耦 | 第九章 |
| 框架能力锁死，定制要改源码 | Runnable 修饰器家族（with_retry/with_fallbacks/bind…）+ 中间件钩子 + 自定义 callback handler | 第二章、第七章 |

## 1.6 版本演进：0.0.x → 0.1/0.2/0.3 → 1.x

写作时（2026 年 10 月）的版本格局：**langchain 1.4.x 为主维护线**（1.4.3，2026-09-28），langchain-core 独立发版（1.6.6），langchain-classic 冻结在 1.0.x（1.0.8），langchain-community 以 0.4.x 独立节奏维护。本节日期均取自 PyPI 上传时间戳（`pypi.org/pypi/<包名>/json`），特性归属经 GitHub Release Notes 与官方博客交叉核对；本地为浅克隆（无历史标签），故"特性属于哪个版本"以发布说明为证，不再标行号。

### 1.6.1 版本时间线与运行基线

| 版本 | 发布时间（PyPI 实证） | Python | 对应 langgraph | 一句话主题 |
|---|---|---|---|---|
| 0.0.x 系列 | 2022-10-25 首发；0.0.100→2023-03；0.0.200→2023-06；0.0.300→2023-09；最后一个 0.0.354→2024-01-03 | 3.8+ | （不存在） | 蛮荒开拓期：单包巨石，每周十几个小版本，`Chain`/`AgentExecutor`/`initialize_agent` |
| 0.1.0 | 2024-01-06 | 3.8.1+ | （LangGraph 0.0.x 独立萌芽） | 第一次大拆包：langchain-core / langchain-community / partner 包三分，LCEL 定型 |
| 0.2.0 | 2024-05-17 | 3.8.1+ | 0.1.x | 与 community 解耦：主包不再依赖 langchain-community，旧 Chain 标记废弃 |
| 0.3.0 | 2024-09-13 | 3.9+ | 0.2.x | Pydantic 2 迁移完成，为 1.0 铺路 |
| 1.0.0 | **2025-10-17**（与 langgraph 1.0.0、langchain-classic 1.0.0 同日） | 3.10+ | 1.0 | **范式切换**：`create_agent` + 中间件成为主 API，Chain/AgentExecutor 移入 langchain-classic |
| 1.1.0 | 2025-11-24 | 3.10+ | 1.0/1.1 | 中间件大扩容（ModelRetry、shell/文件系统/摘要成熟化）、model profile 分发 |
| 1.2.0 | 2025-12-15 | 3.10+ | 1.1 | ProviderStrategy 结构化输出 strict 模式、BaseTool extras |
| 1.3.0 | 2026-05-12 | 3.10+ | 1.1/1.2 | `stream_events` v3（事件流协议升级） |
| 1.4.0~1.4.3 | 2026-09-03 ~ 2026-09-28 | 3.10+ | 1.2.x | **`langchain.mcp` 命名空间与 `MCPAdapter`**（接入 Model Context Protocol 生态） |

langchain-core 的时间线（PyPI 实证）：0.0.1（2023-11-20，首次单独拆包试验）→ 0.1.0（2023-12-12）→ 0.2.0（2024-05-17）→ 0.3.0（2024-09-13）→ 1.0.0（2025-10-17）→ 当前 1.6.6。langgraph：0.2.0（2024-08-07）→ 1.0.0（2025-10-17）→ 当前 1.2.12。

### 1.6.2 关键特性引入对照表（发布说明为证）

| 特性 | 引入版本 | 说明 |
|---|---|---|
| `Chain` / `AgentExecutor` / ReAct Agent | 0.0.x（2022-10 起） | 第一代编程模型；1.0 后移入 langchain-classic |
| **LCEL / Runnable 协议**（`invoke`/`\|`/`RunnableSequence`） | 0.0.2xx（2023 年中，官方博客 2023-08 宣讲） | LCEL 全称 LangChain Expression Language；此后逐步统一为"一切皆 Runnable" |
| Tracing 回调体系（LangSmith 雏形） | 0.0.x 后期（2023 年中） | callbacks/tracers 奠基 |
| langchain-core 拆分 | 0.1.0（2024-01） | core 只留抽象，厂商集成移入 partner 包 |
| `with_structured_output` | 0.1.x | 工具调用式/JSON 模式结构化输出标准化 |
| 主包与 langchain-community 解耦 | 0.2.0（2024-05） | 主包变轻，装 `pip install langchain` 不再拖入上百个集成 |
| Pydantic 2 | 0.3.0（2024-09） | schema 体系全面换血 |
| `create_agent`（LangGraph 版 prebuilt） | 0.2/0.3 期以 `langgraph.prebuilt.create_react_agent` 名义孵化 | 1.0 正式升格为 langchain 主 API 并更名为 `create_agent` |
| **中间件体系**（AgentMiddleware + 钩子） | 1.0.0（2025-10） | `before_model/after_model/wrap_model_call/wrap_tool_call…` |
| `langchain-classic` 兼容包 | 1.0.0（2025-10-17，与主包同日首发） | 旧 Chain/AgentExecutor/memory/retrievers 整体迁入 |
| 内置中间件扩容（ModelRetry/ModelFallback/Summarization/Shell/Filesystem/PII/ToolError…） | 1.0.x~1.1.0（2025-11） | 1.1.0 release notes 可查 |
| `stream_events` v3 | 1.3.0（2026-05） | 智能体事件流协议 v3 |
| `langchain.mcp` / `MCPAdapter` | 1.4.0（2026-09） | 把 MCP 服务器工具桥接进 create_agent |

### 1.6.3 三大阶段主题对比

| 维度 | 0.0.x（2022~2024.01） | 0.1~0.3（2024.01~2025.10） | 1.x（2025.10~） |
|---|---|---|---|
| 包结构 | 单一 langchain 巨石包 | core/community/partner 三分 | core + 瘦主包 + classic 兼容包 |
| 主 API | `LLMChain`、`initialize_agent` | LCEL 管道 `prompt \| llm \| parser` | `create_agent` + 中间件 |
| 智能体执行 | 自研 `AgentExecutor`（while 循环） | AgentExecutor（维护态）与 langgraph 并存 | **LangGraph StateGraph**（唯一运行时） |
| 结构化输出 | PydanticOutputParser（提示词式） | with_structured_output | ToolStrategy/ProviderStrategy/AutoStrategy |
| 状态与记忆 | 内存 `ConversationBufferMemory` | RunnableWithMessageHistory | checkpointer（可插拔存储）+ store |
| 扩展方式 | 继承 Chain/Agent 类 | 自定义 Runnable/回调 | **中间件**（类 + 装饰器两种写法） |
| 运行基线 | Python 3.8 | 3.8.1 → 3.9 | 3.10+ |

### 1.6.4 对初学者的意义：哪些知识过时了，哪些永远有效

- **新项目不要再用**：`LLMChain`/`SequentialChain`/`ConversationChain`、`initialize_agent`、`AgentExecutor`、`from langchain.chains import …`、`from langchain.memory import …`、`from langchain.agents import load_tools`——它们都进了 `langchain_classic`，教程里出现这些 import 说明教材是 0.x 时代的（替代写法见 9.1 节对照表）。
- **0.x 教程里永久有效的部分**：Runnable/LCEL 的心智模型（prompt | model | parser）、消息模型、`ChatPromptTemplate`、向量库与检索器的接口语义、回调/追踪概念——这些从 0.1 到 1.x 基本未变，本文第二~六章正是按这条主线写的。
- **怎么判断教程新旧**：看第一行 import。`from langchain_core import …`（2024 后）还是 `from langchain import …`（更旧）；`create_agent`（1.x）还是 `AgentExecutor`（0.x）。

## 1.7 全文章节地图

- **第二章 Runnable 协议与 LCEL（langchain-core 地基）**：`Runnable` 四象限方法、`|` 运算符如何变成 `RunnableSequence`、`RunnableParallel` 的并发执行、修饰器家族（bind/with_retry/with_fallbacks/with_config/configurable）。
- **第三章 消息与模型抽象**：`BaseMessage` 与标准内容块、流式 chunk 合并、`BaseChatModel` 的调用链（invoke→generate_prompt→generate→_generate_with_cache）、`init_chat_model`、缓存与限流。
- **第四章 提示词、工具与输出解析**：`ChatPromptTemplate`、`BaseTool`/`@tool` 的 schema 推断、工具调用往返、`with_structured_output` 两条路径。
- **第五章 RAG 栈**：`BaseLoader` → text-splitters → `Embeddings` → `VectorStore` → `as_retriever` → Indexing API 去重索引。
- **第六章 可观测性**：CallbackManager 事件分发、Tracer 与运行树、`astream_events`、LangSmith 的位置。
- **第七章 create_agent 与中间件（langchain 1.x 主角）**：`create_agent` 签名逐参数解析、图装配源码走读、`AgentMiddleware` 七个钩子、内置中间件全家桶、三种结构化输出策略。
- **第八章 LangGraph：create_agent 背后的运行时**：StateGraph/Pregel 心智模型、checkpointer 与 interrupt、`create_agent` 的完整图景。
- **第九章 旧世界的归宿**：langchain-classic 考古（`Chain`/`AgentExecutor` 源码）、community 与 partner 包分工、周边产品（LangSmith/LangGraph Platform/deepagents）。
- **第十章 贯通视图**：把"一次 LCEL 链执行""一次 create_agent 对话""一次 RAG 问答"三条时间线叠加成全景图。
- **第十一章 附录**：关键接口速查表、初学者学习路线、Spring↔LangChain 概念对照表。

---

# 二、Runnable 协议与 LCEL：langchain-core 的地基

> 本章对应源码：`libs/core/langchain_core/runnables/`。以下所有【源码证据】中的行号均为实际读取 langchain-core 1.6.6 快照源码所得。

## 2.1 模块定位与包结构

用大白话说：**runnables 包就是 LangChain 的"执行协议 + 积木盒"**。它定义了"一个能被调用的单元"长什么样（`Runnable`），并提供了一堆现成的积木：串行（`RunnableSequence`）、并行（`RunnableParallel`）、包装普通函数（`RunnableLambda`）、按条件路由（`RunnableBranch`）、重试（`RunnableRetry`）、降级（`RunnableWithFallbacks`）、运行期可配置（configurable 系）、注入对话历史（`RunnableWithMessageHistory`）。

其余所有模块（模型、提示词、解析器、检索器、工具）都实现这个协议——所以它们能互相拼接。这就是 1.2 节设计哲学第一条的落点。

```
langchain_core/runnables/
├── base.py          # Runnable 协议本体 + RunnableSequence/RunnableParallel/RunnableLambda
│                    #   （6725 行，本仓库最大的单文件之一）
├── schema.py        # 输入输出 schema 推断工具
├── branch.py        # RunnableBranch（条件分支）
├── retry.py         # RunnableRetry（基于 tenacity）
├── fallbacks.py     # RunnableWithFallbacks（降级链）
├── configurable.py  # DynamicRunnable/RunnableConfigurableFields（运行期换实现）
├── passthrough.py   # RunnablePassthrough/RunnableAssign/RunnablePick（字典透传/赋值）
├── router.py        # RouterRunnable（按 key 路由）
├── history.py       # RunnableWithMessageHistory（会话历史注入）
├── config.py        # RunnableConfig 规范、ensure_config/patch_config、上下文传播
└── graph.py 等      # 运行图可视化（get_graph 的产物：mermaid/ascii/png）
```

## 2.2 Runnable 协议：一个接口，四种执行形态

【源码证据】`libs/core/langchain_core/runnables/base.py:133-134`——协议本体的类声明与官方定义：

```python
class Runnable(ABC, Generic[Input, Output]):
    """A unit of work that can be invoked, batched, streamed, transformed and composed.
```

**先白话**：这行 docstring 就是协议的完整自述——一个可执行单元必须支持四件事：

| 方法族 | 白话含义 | 默认实现策略 |
|---|---|---|
| `invoke` / `ainvoke` | 单个输入 → 单个输出 | 抽象方法，各组件必须自己实现 |
| `batch` / `abatch` / `batch_as_completed` | 一批输入并行跑 | **默认用线程池把 invoke 并行起来**，模型类可重写为批量端点 |
| `stream` / `astream` | 边产边出（token 级流式） | 默认实现为"一次性 invoke 后整块吐出"；模型类重写为真流式 |
| `transform` / `atransform` | 流入 → 流出（管道式转换） | 是 `stream` 的对偶：吃一个 chunk 迭代器、吐一个 chunk 迭代器，链的流式能力全靠它逐级传递 |

再加两个"统一行为"的关键约定（docstring 明文写出，`base.py:145-152`）：

- **batch 默认并行**：默认用线程池执行器并行跑 invoke；需要批量优化的组件（如嵌入模型）自己重写。
- **async 默认兜底**：带 `a` 前缀的方法默认用 asyncio 线程池跑同步版本；组件重写 `a` 方法才获得原生异步。

【源码证据】协议的核心方法签名（`base.py` 行号）：

```python
    def invoke(self, input: Input, config: RunnableConfig | None = None, **kwargs) -> Output   # L886（抽象）
    def batch(self, inputs, config=None, *, return_exceptions=False, **kwargs)                  # L931
    def stream(self, input, config=None, **kwargs) -> Iterator[Output]                          # L1194
    def transform(self, input: Iterator[Input], config=None, **kwargs) -> Iterator[Output]      # L1760
    def astream_events(self, input, version, ...)                                               # L1343
```

**为什么这件事重要？** 因为"统一调用入口"意味着组合是免费的。你在 Spring 里体会到"`ApplicationContext` 是 `BeanFactory`、也是 `MessageSource`、也是 `ApplicationEventPublisher`"的接口归一红利，这里同理：任何 `Runnable` 都自动获得 `with_retry()`、`with_fallbacks()`、`bind()`、`with_config()` 等十余个修饰方法（见 2.4 节），而不必每个组件各写一遍。

`Runnable` 之上还有一个"可序列化的 Runnable"中间层：

【源码证据】`base.py:2827`：

```python
class RunnableSerializable(Serializable, Runnable[Input, Output]):
```

`RunnableSerializable` = `Runnable` + 可序列化为 JSON（配合 `langchain_core.load`）。**凡是"声明出来、能存下来、能画成图"的组件都是它**：提示词模板、链、工具、绑定后的模型……而 `RunnableLambda`（包装任意 Python 函数）故意不继承它——函数没法可靠序列化。

## 2.3 组合原语：`|` 运算符与 RunnableSequence / RunnableParallel

**先白话**：LCEL（LangChain Expression Language）的语法糖只有两个——`a | b` 变串行，`{k1: a, k2: b}` 变并行。本质上是 Python 运算符重载：`|` 调用 `__or__`，把两个 Runnable 包成一个 `RunnableSequence`；dict 字面量被强制转换成 `RunnableParallel`。

【源码证据】`base.py:628-770`——`__or__` 的四个重载分支（协议本体）：

```python
    def __or__(self, other):
        return RunnableSequence(self, coerce_to_runnable(other))     # 核心：包一层序列
    def __ror__(self, other):
        return RunnableSequence(coerce_to_runnable(other), self)     # str/dict/callable 都能 coerce
    def pipe(self, *others):                                          # L724：函数式等价写法
        return RunnableSequence(self, *[coerce_to_runnable(o) for o in others])
```

`coerce_to_runnable` 是关键粘合剂：裸函数 → `RunnableLambda`、dict → `RunnableParallel`、`BaseChatModel`/`BasePromptTemplate` → 原样放入。**所以 `prompt | model | parser` 里没有一个组件需要"知道"自己会出现在链里**。

【源码证据】`RunnableSequence` 定义于 `base.py:3075`；其 `invoke` 的完整执行体（`base.py:3430-3463`）：

```python
    def invoke(self, input, config=None, **kwargs) -> Output:
        # setup callbacks and context
        config = ensure_config(config)
        callback_manager = get_callback_manager_for_config(config)
        # start the root run
        run_manager = callback_manager.on_chain_start(None, input, ...)          # ① 根 run 开始
        input_ = input
        try:
            for i, step in enumerate(self.steps):
                # mark each step as a child run
                config = patch_config(config, callbacks=run_manager.get_child(    # ② 每步挂子 run
                    f"seq:step:{i + 1}"
                ))
                with set_config_context(config) as context:                       # ③ 上下文变量传播
                    input_ = context.run(step.invoke, input_, config)             # ④ 上一步输出 = 下一步输入
        except BaseException as e:
            run_manager.on_chain_error(e)                                          # ⑤ 报错也通知回调
            raise
        else:
            run_manager.on_chain_end(input_)
            return input_
```

注意四件藏在 20 行里的框架级设计：

1. **回调不是可选装饰，而是执行主流程的一部分**：每一步执行前先 `on_chain_start`、后 `on_chain_end`/`on_chain_error`（第六章的追踪体系就挂在这上面）。每个组件的 invoke 里都有同款样板代码——这是运行树能覆盖全链的原因。
2. **子 run 命名有契约**：`seq:step:1`、`seq:step:2`……LangSmith 的树形视图就靠这个。
3. **config 沿链传播且每步 patch**：`patch_config` 把"父 run 的回调句柄"替换成"子 run 句柄"，tags/metadata 原样透传—— tracing 树因此不断链。
4. **`set_config_context` 用 Python 3.11 的 contextvars** 保证异步并发时每个协程拿到自己的 config——这是"async 默认正确"的底层保障。

【源码证据】`RunnableParallel` 定义于 `base.py:3864`；其 `invoke`（`base.py:4139` 起）用线程池**并发**执行各分支：

```python
        with contextlib.ExitStack() as stack:
            ...
            executor = stack.enter_context(ThreadPoolExecutor(max_workers=len(self.steps.__dict__)))
            # 每个分支克隆 config、以子回调运行，最后按 key 组装 dict
```

并发 + 各分支独立的子回调上下文 = `RunnableParallel` 是"扇出"原语：同一输入喂给多个下游，常见于 RAG 里"同时做向量检索与关键词检索"。

**流式的传递链**：`RunnableSequence.stream`（`base.py:3827`）逐段调用下一环的 `transform`，让 token 从模型一路"滴流"到链的出口；`RunnableParallel.stream`（`base.py:4311`）则并行消费各分支的流并按 key 装配。理解了 transform 就理解了"为什么链的流式不是模拟的"。

## 2.4 修饰器家族：不写一行框架代码给组件加"横切能力"

**先白话**：Spring 里给 Bean 加重试、超时、事务，靠的是 AOP 代理；LangChain 给 Runnable 加这些，靠的是**包装类**（装饰器模式的朴素版）。每个方法返回一个包着原对象的新 Runnable，执行语义由包装类定义。全部方法都定义在 `Runnable` 协议上（`base.py:1851-2230`），因此**对链同样生效**——给整条链加重试，就是给 `RunnableSequence` 套壳。

| 方法 | 包装类（源码位置） | 语义 | Spring 类比 |
|---|---|---|---|
| `bind(**kwargs)` | `RunnableBinding`（runnables/base.py:6378，chat_models.py:2568 的 `_ChatModelBinding` 即其子类） | 预先固化调用参数，如 `model.bind(stop=["\n"], tools=[...])` | `@Qualifier` 固定注入细节 |
| `with_config(cfg)` | `RunnableBinding` | 固定 tags/metadata/run_name（追踪用） | 链路打标 |
| `with_types(input_type=, output_type=)` | `RunnableBinding` | 声明输入输出类型（校验/文档） | 泛型约束 |
| `with_retry(stop_after_attempt=3)` | `RunnableRetry`（retry.py:48，继承 `RunnableBindingBase`） | 指数退避重试（引擎是 tenacity，`ExponentialJitterParams` 在 retry.py:35） | `@Retryable`（Spring 7.0 内建韧性，同款思想） |
| `with_fallbacks([alt])` | `RunnableWithFallbacks`（fallbacks.py:37） | 主模型挂了切备用模型 | 熔断降级（Sentinel/Resilience4j） |
| `with_listeners(on_start/on_end/on_error)` | `RunnableBinding` | 生命周期监听器 | `@PostConstruct`/事件监听 |
| `.map()` | 每 item 各跑一次 | 把 `Runnable[I,O]` 变 `Runnable[list[I], list[O]]` | Stream.map |
| `configurable_fields(...)` | `RunnableConfigurableFields`（configurable.py:317） | **运行期**按 config 换实现/换参数 | `@Profile`+`@ConditionalOnProperty` 的运行时版 |
| `pick(keys)` / `assign(**fn)` / `RunnablePassthrough` | passthrough.py:74/352/679 | 对 dict 输入取键 / 追加键 / 原样透传 | MapStruct 的字典化土办法 |

【源码证据】两个最常用的：

```python
# base.py:2101 —— with_retry 直接委托给 RunnableRetry，而 RunnableRetry 用 tenacity 实现
    def with_retry(self, *, retry_if_exception_type=None, wait_exponential_jitter=False,
                   stop_after_attempt=3, ...) -> Runnable[Input, Output]:

# fallbacks.py:37 —— 降级链不是"try 一次备胎"，而是依次尝试 exceptions 里列出的异常类型
class RunnableWithFallbacks(RunnableSerializable[Input, Output]):
    runnable: Runnable[Input, Output]          # 主体
    fallbacks: Sequence[Runnable[Input, Output]]  # 依次尝试的备胎
    exceptions_to_handle: tuple[type[BaseException], ...]
```

**设计点评**：这些修饰器全部"包一层新类"而不是改原对象——不可变、可组合（`with_retry().with_fallbacks()` 随便叠）、可序列化。相比 Spring AOP 的动态代理，这套实现朴素得多，但"把横切逻辑与业务逻辑分离"的目标完全一致；代价是没有 AOP 的表达力（没有切点表达式），换来的是零魔法、纯 Python 可读。

## 2.5 条件路由、历史注入与运行期可配置

**RunnableBranch**（`branch.py:43`）：按谓词依次匹配分支，都不中走 default——LCEL 里的 `if/elif/else`：

```python
branch = RunnableBranch(
    (lambda x: "提问" in x, rag_chain),        # 谓词 → Runnable
    (lambda x: "闲聊" in x, chat_chain),
    general_chain,                              # 默认分支
)
```

**RunnableWithMessageHistory**（`history.py:39`，继承 `RunnableBindingBase`）：给无状态链自动装/存会话历史。它维护"session_id → BaseChatMessageHistory"的工厂，每次调用前把历史消息拼进输入（输入通常是 dict，用 `MessagesPlaceholder` 占位），调用后把本轮追加进历史。0.x 时代它是"记忆"的主要方案；1.x 时代**记忆下沉为 LangGraph checkpointer**（第八章），此组件进入"仍可用但主要用于无 LangGraph 场景"的状态。

**DynamicRunnable 家族**（`configurable.py:50/317/474`）：`configurable_fields` 让你在**运行期**通过 `config={"configurable": {...}}` 换掉某个字段（比如换模型温度）；`configurable_alternatives` 直接换整个实现（比如"开发用 fake 模型、生产用真模型"）。这是"策略模式 + 工厂"的声明式封装，也是 LangChain 里最接近 Spring `@Conditional` 的机制。

## 2.6 调试三件套：schema、图、debug 开关

- **类型自省**：`chain.input_schema` / `chain.output_schema` 返回 pydantic 模型（由 `schema.py` 沿链推断），`model_json_schema()` 可直接生成 OpenAPI 片段。
- **图可视化**：`Runnable.get_graph()`（`base.py:593`）返回 `Graph` 对象，`draw_mermaid()`/`draw_mermaid_png()`/`draw_ascii()`（graph_ascii.py/graph_mermaid.py）生成拓扑图——对 `create_agent` 产出的 LangGraph 同样可用（第八章截图展示）。
- **全局调试**：`langchain_core.globals.set_debug(True)` 打印每步的输入输出；`set_verbose(True)` 打印提示词全文；`set_tracing_callback_manager` 挂追踪器。

## 2.7 本章小结

- **Runnable 协议 = LangChain 的 `BeanFactory`**：统一 `invoke/batch/stream/transform` 四象限入口 + 默认的并行/异步兜底策略，一切组件皆可拼。
- **LCEL 语法糖的真相**：`|` = `RunnableSequence`（`base.py:3075`），dict = `RunnableParallel`（`base.py:3864`），`coerce_to_runnable` 负责把裸函数/dict 强转进来；链的执行主流程里内嵌回调上报（`on_chain_start → 每步 on_chain_* → on_chain_end`）。
- **修饰器家族**是"包装类"实现的横切扩展（重试=RunnableRetry、降级=RunnableWithFallbacks、参数固化=RunnableBinding、运行期换件=configurable），对整条链同样生效。
- 流式能力靠 `transform` 逐级传递，不是模拟；并发安全靠 contextvars 传播 config。

---

# 三、消息与模型抽象：messages 与 language_models

> 本章对应源码：`libs/core/langchain_core/messages/`、`libs/core/langchain_core/language_models/`，以及 `caches.py`、`rate_limiters.py`。

## 3.1 模块定位

**先白话**：这一层回答两个问题——"发给模型的东西长什么样"（消息）和"模型怎么被统一调用"（模型抽象）。它位于整个生态的正中间：partner 包的 `ChatOpenAI`/`ChatAnthropic` 实现它的抽象，langchain 1.x 的 `create_agent` 消费它的产物，所有日志/追踪/缓存机制挂在它的调用链上。

```
langchain_core/messages/            langchain_core/language_models/
├── base.py        BaseMessage      ├── base.py          BaseLanguageModel（共同祖先）
├── human.py       HumanMessage     ├── chat_models.py   BaseChatModel（本章主角，2748 行）
├── ai.py          AIMessage(+Chunk)├── llms.py           BaseLLM / LLM（补全式，1.x 已边缘化）
├── system.py      SystemMessage    ├── fake_chat_models.py  假模型（测试用）
├── tool.py        ToolMessage      ├── model_profile.py ModelProfile（模型能力档案）
├── chat.py        ChatMessage      └── _compat_bridge.py 旧↔新 API 桥
├── content.py     标准内容块定义
├── block_translators/  各厂商格式 ↔ 标准块 的双向翻译器
└── utils.py       消息工具（merge 等）
```

## 3.2 消息模型：BaseMessage 与"内容块标准化"

**先白话**：一次对话就是一条消息列表。LangChain 用 pydantic 模型描述每种角色消息，1.x 最大的变化是**内容块标准化**：OpenAI、Anthropic、Gemini 各家返回的"思考过程/引用/工具结果/多模态"格式互不相同，LangChain 定义了一套标准 TypedDict 内容块，并用翻译器把各家格式双向转换——你写的代码只面对标准格式。

【源码证据】`libs/core/langchain_core/messages/base.py:93-135`——消息基类：

```python
class BaseMessage(Serializable):
    content: str | list[str | dict[Any, Any]]        # L103：字符串 或 内容块列表
    additional_kwargs: dict[Any, Any] = Field(...)   # L106：厂商私有附加信息（不跨模型通用）
    response_metadata: dict[Any, Any] = Field(...)   # L114：模型响应元数据（finish_reason 等）
    type: str                                        # L117：角色标识（"human"/"ai"/"tool"/"system"）
    name: str | None = None                          # L125
    id: str | None = Field(default=None, ...)        # L135：消息 ID（跨轮续传/流式合并的关键）
```

`content` 字段的类型签名 `str | list[...]` 直接记录了一次架构演进：0.x 时代内容就是字符串（多模态塞进 `additional_kwargs`），1.x 起主流用法是**内容块列表**。标准内容块定义在 `messages/content.py`，实测有 16 类（行号）：

| 内容块 | 行号 | 白话 |
|---|---|---|
| `TextContentBlock` | 207 | 普通文本 |
| `ToolCall` / `ToolCallChunk` / `InvalidToolCall` | 247/291/336 | 模型发起的工具调用（流式分片/解析失败版） |
| `ServerToolCall` / `ServerToolResult` | 372/425 | **服务端执行**的工具（如 Anthropic 的 web_search：工具在厂商侧跑） |
| `ReasoningContentBlock` | 456 | 思维链/推理过程（o1、Claude thinking、DeepSeek-R1 的"思考"） |
| `ImageContentBlock` / `VideoContentBlock` / `AudioContentBlock` | 498/549/600 | 多模态输入输出 |
| `PlainTextContentBlock` / `FileContentBlock` | 651/721 | 纯文本/文件（PDF 等）附件 |
| `Citation` / `NonStandardAnnotation` | 126/184 | 引用溯源与厂商原生注解 |
| `NonStandardContentBlock` | 790 | 兜底：翻译不了的原样保留 |

**翻译器**在 `messages/block_translators/`：openai.py、anthropic.py、bedrock.py、bedrock_converse.py、google_genai.py、google_vertexai.py、groq.py、**langchain_v0.py**（把 0.x 时代塞在 `additional_kwargs` 里的旧格式翻译成标准块——兼容层连这里都有一块）。

角色子类是薄壳：`HumanMessage`（human.py）、`SystemMessage`（system.py）、`ToolMessage`（tool.py，带 `tool_call_id` 字段回答"这是哪次调用的结果"）、`AIMessage`（ai.py）。`AIMessage` 比基类多三个 1.x 高频字段（`messages/ai.py:160-182`）：

```python
class AIMessage(BaseMessage):
    tool_calls: list[ToolCall] = Field(...)          # L170：模型要调的工具（名字+参数+id）
    invalid_tool_calls: list[InvalidToolCall] = ...  # L173：解析失败的调用（不丢，标出来）
    usage_metadata: UsageMetadata | None = None      # L176：token 消耗
```

【源码证据】`UsageMetadata`（`ai.py:104-153`）：`input_tokens`/`output_tokens`/`total_tokens` + 明细 `input_token_details`（其中 `cache_creation` L60、`cache_read` L66 专门记**上下文缓存**的读写量——提示词缓存计费已经重要到进主 schema）。

## 3.3 流式的"可合并性"：AIMessageChunk

**先白话**：流式响应是碎片雨，每片带增量信息（半个 token、半个工具调用参数）。LangChain 的做法是让每一片都是 `AIMessageChunk` 对象，**支持 `+` 运算**：片与片相加得到合并后的消息。这是"流式中间结果可直接使用"的基础——`create_agent` 流式拿到的每个模型输出分片都能累积成完整 AIMessage。

【源码证据】`messages/ai.py:418-430`：

```python
class AIMessageChunk(AIMessage, BaseMessageChunk):
    type: Literal["AIMessageChunk"] = "AIMessageChunk"
    tool_call_chunks: list[ToolCallChunk] = Field(...)   # L427：工具调用也分片（参数 JSON 逐段到）
    chunk_position: Literal["last"] | None = None        # L430：标识末片（供下游判断收尾）
```

`__add__` 实现（`ai.py:652` 起）负责内容串接、tool_call_chunks 合并、usage 累加（`ai.py:794` 的 `add_usage`）。`BaseMessageChunk` 基类在 `messages/base.py:409`。

## 3.4 BaseChatModel：一次调用的完整链路

**先白话**：`BaseChatModel` 是"聊天模型"的抽象——所有厂商适配器（`ChatOpenAI`、`ChatAnthropic`、`ChatOllama`……）的父类。子类只需要实现一个**最小核心**：`_generate`（同步生成一批候选）与可选的 `_astream`/`_stream`（流式）；其余一切——Runnable 协议、回调、缓存、限流、流式判定——全部由父类模板方法完成。这是**模板方法模式**的标准教科书案例（对 Spring 用户：就是 `JdbcTemplate` 藏起"获取连接/释放资源"只留回调的那种设计）。

【源码证据】`libs/core/langchain_core/language_models/chat_models.py:284`：

```python
class BaseChatModel(BaseLanguageModel[AIMessage], ABC):
```

调用链（自顶向下）：

```
invoke(input, config, stop=...)            # chat_models.py:475
  └─ generate_prompt([messages])           # :1869  把 PromptValue 转消息列表
      └─ generate(messages, stop, ...)     # :1592  批量入口：回调 on_llm_start
          └─ _generate_with_cache(...)     # :1892  ★ 查缓存 → 限流 → 调子类 → 写缓存
              ├─ _generate(messages, ...)  # :2209  【抽象】子类唯一必须实现的同步核心
              └─ 或 _stream/_astream       # :727   流式路径（自动累积成 AIMessage）
```

【源码证据】`invoke` 的本体（`chat_models.py:475-499`）：

```python
    def invoke(self, input: LanguageModelInput, config=None, *, stop=None, **kwargs) -> AIMessage:
        config = ensure_config(config)
        return cast(
            "AIMessage",
            cast(
                "ChatGeneration",
                self.generate_prompt(
                    [self._convert_input(input)],
                    stop=stop,
                    callbacks=config.get("callbacks"),   # ← 回调沿 config 注入
                    tags=config.get("tags"),
                    metadata=config.get("metadata"),
                    ...
                ).generations[0][0],
            ).message,
        )
```

`_generate_with_cache`（`:1892`）是整条链的"安检门"，按序做四件事：

1. **缓存**：`llm.cache` 存在则先 `lookup(prompt, llm_string)`（键是"序列化提示词 + 模型参数指纹"），命中直接返回（`caches.py:32` 定义 `BaseCache.lookup/update`，`InMemoryCache` 在 `:155`）；
2. **限流**：模型若配了 `rate_limiter`（`rate_limiters.py:67` 的 `InMemoryRateLimiter`，令牌桶算法），在请求发出前 acquire；
3. **执行**：调用子类 `_generate`（或 `_should_stream` 判定走流式）；
4. **回写**：结果写入缓存，`on_llm_end` 携带 token 用量上报回调。

**流式的判定逻辑**值得细读（`_should_stream`，`chat_models.py:549-584`）：模型是否走流式 API 不只看用户传没传 `stream=True`，还有两个"自动触发"条件——实例设置了 `streaming=True`，或**回调链里挂了流式回调处理器**（`:583` 起检查 handlers）。也就是说：你在 LangSmith 里请求 token 级时间线，框架会自动替你改走流式端点。反过来 `_streaming_disabled`（`:525-547`）列出了三种硬性关闭流式的情况（`disable_streaming=True`、带工具调用且 `disable_streaming="tool_calling"`、显式 `stream=False`）。

### 3.4.1 与 Spring 的对照

| Spring | BaseChatModel |
|---|---|
| 抽象：`PlatformTransactionManager` 一个接口、JDBC/JPA/JTA 多实现 | `BaseChatModel` 一个抽象、OpenAI/Anthropic/Ollama 多实现 |
| 子类只实现最小回调（如 `doBegin/doCommit`） | 子类只实现 `_generate`（必要时 `_astream`） |
| 父类模板方法管住"开事务/异常翻译/资源清理" | 父类模板方法管住"回调/缓存/限流/流式判定" |
| `TransactionTemplate.execute()` | `invoke()` |

## 3.5 统一入口 init_chat_model 与模型能力档案

**先白话**：1.x 时代你不再需要 import 具体厂商类——`init_chat_model("openai:gpt-5.2", temperature=0)` 按字符串自动找到 partner 包里的类并实例化（包没装会报"请 pip install langchain-openai"）。这让"模型名"可以成为配置文件里的一个字符串，代码零改动切换厂商。

【源码证据】`libs/langchain_v1/langchain/chat_models/base.py:232-244`：

```python
def init_chat_model(
    model: str | None = None,
    *,
    model_provider: str | None = None,
    configurable_fields: Literal["any"] | list[str] | tuple[str, ...] | None = None,
    config_prefix: str | None = None,
    **kwargs: Any,
) -> BaseChatModel | _ConfigurableModel:
    """Initialize a chat model from any supported provider using a unified interface."""
```

`configurable_fields` 参数配合第二章的 configurable 机制：传 `"any"` 可以连**模型本身**都留到运行期用 `config={"configurable": {"model": "anthropic:..."}}` 指定——同一份代码白天用便宜模型、夜间批量任务用旗舰模型。注意这个函数在 1.x 从 `langchain.chat_models` 命名空间提供（源码就在主包 `libs/langchain_v1`），而不是 core——因为"动态 import 伙伴包"依赖主包的装配角色。

**模型能力档案（model profile）**是 1.1.0 引入、1.4 仍在强化的新基建：`ModelProfile` TypedDict（`core/langchain_core/language_models/model_profile.py:18`）描述"这个模型支持上下文缓存吗？最大输出多少 token？支持工具调用吗？"，数据由 `libs/model-profiles` 包从 [models.dev](https://github.com/sst/models.dev) 生成。它解决一个真实痛点：`create_agent` 的结构化输出自动策略（见 7.6 节）需要"问模型会不会原生 JSON Schema"，答案就来自这份档案。

## 3.6 BaseLLM：补全式模型的遗民区

`BaseLLM`（`llms.py:296`）/`LLM`（`llms.py:1442`）面向"文本进、文本出"的补全式接口（text-completion），与 `BaseChatModel` 并列、共同继承 `BaseLanguageModel`（`base.py:181`）。1.x 时代新模型几乎都是聊天式，`LLM` 只剩本地老模型（旧版 LLaMA.cpp 等）还在用——读旧代码时认识即可，新项目一律 `BaseChatModel`。

## 3.7 本章小结

- **消息模型已从"字符串 + additional_kwargs"演进为"标准内容块"**（16 类 TypedDict，`messages/content.py`），`block_translators` 负责各厂商格式双向翻译，连 0.x 旧格式都有专属翻译器（`langchain_v0.py`）。
- **AIMessageChunk 支持 `+` 合并**是流式可用的根基；token 用量进主 schema（`usage_metadata`），连上下文缓存的读写量都有明细字段。
- **BaseChatModel 是模板方法模式**：子类只写 `_generate`/`_astream`，父类管回调→缓存→限流→执行→回写全链路；`_should_stream` 甚至会因"挂了流式追踪器"自动改走流式端点。
- `init_chat_model("provider:model")` 让厂商成为配置项；model profile 让框架"了解"每个模型的能力边界。

---

# 四、提示词、工具与输出解析：与模型"对话"的三件套

> 本章对应源码：`libs/core/langchain_core/prompts/`、`tools/`、`output_parsers/`。

## 4.1 提示词模板：ChatPromptTemplate

**先白话**：提示词模板就是"带占位符的消息列表配方"。`ChatPromptTemplate` 把"角色 + 模板文本"的列表编译成 `PromptValue`（进而变成消息列表），配合 `MessagesPlaceholder` 可以在指定位置整段插入消息（典型用途：插对话历史、插工具调用记录）。

【源码证据】`libs/core/langchain_core/prompts/chat.py` 关键类（行号）：

```python
class MessagesPlaceholder(BaseMessagePromptTemplate):        # L53：整段消息占位符
class BaseStringMessagePromptTemplate(BaseMessagePromptTemplate, ABC):  # L226
class HumanMessagePromptTemplate(_StringImageMessagePromptTemplate):    # L668
class AIMessagePromptTemplate(_StringImageMessagePromptTemplate):       # L677
class SystemMessagePromptTemplate(_StringImageMessagePromptTemplate):   # L686
class BaseChatPromptTemplate(BasePromptTemplate[str], ABC):             # L695
class ChatPromptTemplate(BaseChatPromptTemplate):                       # L794
```

用法（每个 LangChain 教程的第一段代码）：

```python
from langchain_core.prompts import ChatPromptTemplate, MessagesPlaceholder

prompt = ChatPromptTemplate.from_messages([
    ("system", "你是一个{domain}专家"),
    MessagesPlaceholder("chat_history", optional=True),   # 1.x：optional=True 时不传也不报错
    ("human", "{question}"),
])
```

要点：模板变量从**输入 dict** 中提取（`input_variables` 属性自动推导）；`ChatPromptTemplate` 本身是 `Runnable`（实现于 `BasePromptTemplate`，`prompts/base.py:38`），`invoke({"question": "..."})` 产出 `ChatPromptValue`——**模板与模型能被 `|` 连接的原因就在这**：模板输出实现了 `LanguageModelInput` 协议。

## 4.2 工具：BaseTool 与 @tool 装饰器

**先白话**：工具 = "给模型用的函数 + 说明书"。模型不执行工具——模型只输出"我想调 `get_weather`，参数 `{"location": "SF"}`"（ToolCall），真正执行的是你的代码（第七章的 ToolNode）。所以工具的核心资产是 **schema**：函数叫什么、干什么（description）、参数有哪些（args_schema）。schema 质量直接决定模型调用工具的准确率。

【源码证据】`libs/core/langchain_core/tools/base.py:433`：

```python
class BaseTool(RunnableSerializable[str | dict[str, Any] | ToolCall, Any]):
    """Base class for all LangChain tools. ..."""

class ChildTool(BaseTool):                    # L459：1.x 新增的子类基座
```

注意 BaseTool 继承的是 `RunnableSerializable`，输入类型是 `str | dict | ToolCall`——**工具也是 Runnable**，所以它也能 `|`、能 with_retry、能进链。类的两种创建姿势：

- **装饰器式（推荐）**：`@tool` 装饰普通函数，从类型注解 + docstring 自动推断 schema（`tools/convert.py:77` 的 `tool()` 实现，参数含 `parse_docstring`、`return_direct`、`response_format="content_and_artifact"` 等）；1.2.0 新增 `extras` 参数携带自定义元数据。
- **继承式**：手写 `args_schema: type[BaseModel]` 子类（pydantic 模型），`__init_subclass__`（`base.py:436` 起）会在子类定义时就校验 `args_schema` 注解写法，写错直接抛 `SchemaAnnotationError`。

```python
from langchain_core.tools import tool

@tool
def check_weather(location: str) -> str:
    """Return the weather forecast for the specified location."""   # ← docstring 就是 description
    return f"It's always sunny in {location}"
```

**关键设计——输入 schema 与调用 schema 分离**（`base.py:677` 的 `tool_call_schema`）：工具执行时可能需要一些**不暴露给模型**的参数（如注入用户身份、当前状态）。`tool_call_schema` 返回"给模型看的参数集"，`args_schema` 是"实际执行的全部参数集"——运行时由 ToolNode/ToolRuntime 注入后者多出的参数（`ToolRuntime`、`InjectedState`、`InjectedStore` 均由 langgraph.prebuilt 提供，主包 `tools/tool_node.py` 原样再导出）。

**错误语义**：`ToolException`（`base.py:371`）；工具抛错是否中断智能体、错误文本如何回喂模型，由 ToolErrorMiddleware 与工具的 `handle_tool_error` 配置决定（第七章）。

## 4.3 工具调用的"往返"：一次完整握手

把 3~4 章的知识串起来，一次工具调用的完整数据流：

```
1. 你把工具 schema 发给模型       → model.bind_tools([check_weather])      # chat_models.py:2366
2. 模型返回带 tool_calls 的回复   → AIMessage(tool_calls=[ToolCall(name, args, id)])   # ai.py:170
3. 你的代码执行工具              → tool.invoke({"location": "SF"})        # tools/base.py:757
4. 结果包成 ToolMessage 回喂     → ToolMessage(content="It's always sunny in SF",
                                              tool_call_id=...)           # tool.py
5. 模型看到结果，继续生成         → 最终 AIMessage（无 tool_calls 时循环结束）
```

`bind_tools`（`chat_models.py:2366`）在 BaseChatModel 层只有"绑定参数"的默认语义，具体把 LangChain 工具 schema 翻译成厂商格式（OpenAI 的 `functions`、Anthropic 的 `tools`）由各 partner 包子类实现——又一个"核心定协议、适配器干脏活"的案例。

## 4.4 输出解析与 with_structured_output

**先白话**：让模型输出结构化数据有两代方案。第一代是**解析器**：在提示词里写"请按此 JSON Schema 输出"，再用解析器容错解析——脆弱、多耗 token、跨厂商表现不稳。第二代是**with_structured_output**：用厂商原生的"结构化输出/函数调用"能力直接约束生成——现在是默认推荐。

【源码证据】解析器层次（`output_parsers/base.py`）：

```python
class BaseLLMOutputParser(ABC, Generic[T]):        # L34：parse_result(List[ChatGeneration])
class BaseGenerationOutputParser(...)              # L74：吃 Generation 的低层接口
class BaseOutputParser(BaseGenerationOutputParser, ...)  # L140：吃 str 的通用接口（parse/invoke）
```

常用实现：`StrOutputParser`（取 `message.content`）、`JsonOutputParser`（json.py:31，支持按 chunk 累积解析的 `BaseCumulativeTransformOutputParser`）、`PydanticOutputParser`（pydantic.py:19）。它们作为 Runnable 的价值在于能嵌进 LCEL 管道末端：`prompt | model | parser`。

【源码证据】`with_structured_output` 定义在 `chat_models.py:2385`：

```python
    def with_structured_output(self, schema, *, method="json_schema", strict=None, ...):
```

BaseChatModel 层的实现是**绑定一个内部解析器并打包成新 Runnable**（`:2568` 的 `_ChatModelBinding(RunnableBinding)`）。`method` 参数区分两条路径：

- `method="json_schema"`（默认）：走厂商原生 JSON Schema 约束（OpenAI Structured Outputs / Anthropic tool-based schema）；
- `method="function_calling"`：借工具调用机制产出结构化结果。

在 `create_agent` 语境下，这套逻辑被进一步工程化为三种**策略对象**（`libs/langchain_v1/langchain/agents/structured_output.py`）——见 7.6 节。

## 4.5 本章小结

- `ChatPromptTemplate`（chat.py:794）+ `MessagesPlaceholder`（chat.py:53）是消息配方；模板本身是 Runnable，这是 `prompt | model | parser` 成立的前提。
- **工具是"函数 + schema"的 Runnable**（BaseTool，tools/base.py:433）；schema 从类型注解/docstring 推断（@tool）或显式声明（args_schema）；`tool_call_schema` 把"给模型看的参数"与"实际执行的参数"分离，支持运行时注入。
- 工具调用的本质是**四步握手**（bind_tools → tool_calls → 执行 → ToolMessage 回喂），模型从不执行任何东西。
- 结构化输出两代方案：解析器（output_parsers/）与 `with_structured_output`（chat_models.py:2385）；智能体语境下升级为 ToolStrategy/ProviderStrategy 策略对象。

---

# 五、RAG 栈：从文档到语义检索的五级流水线

> 本章对应源码：`libs/core/langchain_core/document_loaders/`、`documents/`、`embeddings/`、`vectorstores/`、`retrievers.py`、`indexing/`，以及独立包 `libs/text-splitters/langchain_text_splitters/`。

## 5.1 全景：一条流水线，五个标准接口

**先白话**：RAG（检索增强生成）= "把私有知识切好、变成向量存进库，问的时候先检索相关片段、塞进提示词再问模型"。LangChain 把流水线切成五级，每级一个标准接口、一批实现——像 Java 的 `InputStream` 系一样，任意两级都能对接：

```
原始文档 ──BaseLoader──▶ Document ──TextSplitter──▶ 小块 Document
             ──Embeddings──▶ 向量 ──VectorStore──▶ 可检索的库
             ──as_retriever()──▶ Retriever（一个 Runnable！）──▶ 塞进 prompt | model
```

## 5.2 Document 与 BaseLoader

【源码证据】`libs/core/langchain_core/documents/`（`Document`：`page_content: str` + `metadata: dict` 两个字段）；加载器基类 `document_loaders/base.py:26-117`：

```python
class BaseLoader(ABC):
    def load(self) -> list[Document]:            # L37：一次读全部
    def lazy_load(self) -> Iterator[Document]:   # L91：★迭代器，逐个产出
    def load_and_split(self, text_splitter=None):  # L53：加载 + 切分一步到位
```

**lazy_load 是 1.x 推荐姿势**：千页 PDF 不必整体进内存。具体解析器（PDF/HTML/CSV…）大多在 langchain-community 与各 partner 包；core 只定契约。

## 5.3 文本切分：text-splitters 独立包

切分器已独立为 `langchain-text-splitters` 包（见 1.4 依赖表：它只依赖 core）。核心思想：**尽量按语义边界（段落→句子→词）切，超长再回退到硬切**。

【源码证据】`libs/text-splitters/langchain_text_splitters/character.py:91`：

```python
class RecursiveCharacterTextSplitter(TextSplitter):
    # 递归尝试 ["\n\n", "\n", " ", ""] 分隔符层级：先按段落切，
    # 块仍超长再按句子切、按词切、最后按字符硬切，每块目标 chunk_size、重叠 chunk_overlap
class CharacterTextSplitter(TextSplitter):       # L13：单一分隔符的简单版
```

族谱里还有 `MarkdownHeaderTextSplitter`（按标题层级）、`TokenTextSplitter`（按 token 数，接模型上下文预算）、`SemanticChunker`（按嵌入相似度断句，在 partner 包）等。

## 5.4 Embeddings 与 VectorStore：两个接口定乾坤

【源码证据】`libs/core/langchain_core/embeddings/embeddings.py:8`：

```python
class Embeddings(ABC):
    def embed_documents(self, texts: list[str]) -> list[list[float]]   # 建库用（批量）
    def embed_query(self, text: str) -> list[float]                    # 查询用（单条）
```

为什么查询与建库是两个方法？因为不少厂商是**非对称检索**（query 与 passage 用不同前缀/模型头），接口级就把语义分开。

【源码证据】`libs/core/langchain_core/vectorstores/base.py:43`——`VectorStore` 的接口面（行号）：

```python
class VectorStore(ABC):
    def add_documents(self, documents, **kwargs) -> list[str]        # L234：有默认实现（embed+add）
    def similarity_search(self, query, k=4, **kwargs)                # L361：抽象，必须实现
    def similarity_search_with_score(...)                            # L417
    def similarity_search_by_vector(self, embedding, k=4, ...)       # L624
    def max_marginal_relevance_search(self, query, ...)              # L659：MMR 去冗余
    @classmethod
    def from_documents(cls, documents, embedding, ...)               # L787：一条龙建库
    def as_retriever(self, **kwargs) -> VectorStoreRetriever          # L905：★变身为 Runnable
```

`similarity_search` 是唯一必须实现的抽象方法——最小接口原则的又一次执行；`add_documents` 默认实现 = "Embeddings.embed_documents + 自家的 add"。

**Retriever 是 Runnable**：`VectorStoreRetriever`（base.py:964）继承 `BaseRetriever`（`retrievers.py:55`，而 `BaseRetriever(RunnableSerializable)`）——于是向量库可以直接嵌进 LCEL 链，这就是 1.2 节"一切皆 Runnable"在 RAG 场景的兑现。`InMemoryVectorStore`（`vectorstores/in_memory.py:34`）是 core 自带的零依赖实现，学习/原型专用。

## 5.5 Indexing API：重复索引的解药

生产环境文档天天变，全量重建索引既贵又慢。`langchain_core/indexing/api.py` 提供 `index()` 函数（配合 `RecordManager` 记录"已写过什么"）：按文档内容哈希去重，只 upsert 新增/变更的块，删除源里已消失的块（cleanup 模式可选）。它对上层透明，任何 `VectorStore` 都能接。

## 5.6 本章小结

- RAG 五级流水线各有标准接口：`BaseLoader`（lazy_load 迭代器）→ text-splitters（RecursiveCharacterTextSplitter 的语义边界递归切分）→ `Embeddings`（embed_documents/embed_query 双方法）→ `VectorStore`（唯一必须实现 similarity_search）→ `as_retriever()` 变身 Runnable 进 LCEL 链。
- **核心只定契约，实现全在集成包**：core 里只有 InMemoryVectorStore 一个向量库实现——最小接口原则贯穿始终。
- Indexing API 用内容哈希 + RecordManager 解决"重复索引"这个生产高频问题。

---

# 六、可观测性：callbacks、tracers 与 LangSmith

> 本章对应源码：`libs/core/langchain_core/callbacks/`、`tracers/`、`globals.py`、`runnables/base.py` 的 `astream_events`。

## 6.1 白话：三套机制解决"黑盒"问题

LangChain 的可观测性其实有三层，初学者容易混：

1. **回调（callbacks）**：执行过程中的事件广播——"链开始了/模型结束了/工具出错了"。你可以写 handler 监听做任何事（打日志、记指标、采集样本）。这是**进程内、同步语义**的钩子体系。
2. **追踪器（tracers）**：一种特殊的回调 handler，把零散事件拼装成**运行树**（run tree）：根 run 是整条链，子 run 是每一步，带时间、输入、输出、token、错误。`LangChainTracer` 把运行树发给 LangSmith；`ConsoleCallbackHandler` 把它打印到终端。
3. **事件流（astream_events）**：以**异步迭代器**形态把运行过程"边跑边吐"给应用——适合在 Web 后端里做实时进度推送（先收到 `on_chat_model_stream` 的 token 增量，再收到 `on_tool_start`……）。

## 6.2 回调体系：CallbackManager 的事件分发

【源码证据】事件接口 `callbacks/base.py`（`BaseCallbackHandler` 定义了 `on_llm_start/on_chat_model_start/on_llm_end/on_llm_error/on_chain_start/on_chain_end/on_chain_error/on_tool_start/on_tool_end/on_tool_error/on_retriever_start/...` 全家桶，另有 `AsyncCallbackHandler`）；管理器在 `callbacks/manager.py`：

```python
class BaseRunManager(RunManagerMixin):                       # manager.py:490
class CallbackManagerForLLMRun(RunManager, LLMManagerMixin):     # L705：LLM run 的句柄
class CallbackManagerForChainRun(ParentRunManager, ChainManagerMixin):  # L928：链 run 的句柄（能取子管理器）
class CallbackManagerForToolRun(ParentRunManager, ToolManagerMixin):    # L1127
class CallbackManagerForRetrieverRun(ParentRunManager, RetrieverManagerMixin):  # L1248
```

**回传路径**在第二章已经见过：每个 Runnable 的 `invoke` 里 `on_chain_start` 拿到 `run_manager`，`run_manager.get_child("seq:step:1")` 生成**子 config** 传给下一步——所以无论组件嵌套多深，事件都能沿正确的父子关系上报。四类 run（llm/chain/tool/retriever）与 LangChain 的四大组件一一对应，事件名即组件类型名。

**接入姿势**（三种，粒度递减）：

```python
chain.invoke(x, config={"callbacks": [MyHandler()]})   # 单次调用
model = ChatOpenAI(callbacks=[MyHandler()])            # 组件级
from langchain_core.globals import set_debug; set_debug(True)   # 全局调试输出
```

## 6.3 Tracer 与运行树

【源码证据】`tracers/base.py:33`：

```python
class BaseTracer(_TracerCore, BaseCallbackHandler, ABC):
class AsyncBaseTracer(_TracerCore, AsyncCallbackHandler, ABC):     # L551
```

`BaseTracer` 借助 `_TracerCore`（事件 → Run 对象的组装器）把回调事件流拼装成 `Run` 树（schema 由 langsmith 包定义），子类只需实现 `persist_run` 决定树往哪送：

- `LangChainTracer`（`tracers/langchain.py:134`）：经 langsmith 客户端发往 LangSmith 平台（**core 依赖 langsmith 包的原因**，见 1.4 依赖表——追踪协议与上传客户端在 core 里，平台服务在云端）；
- `RunCollectorCallbackHandler`（`tracers/run_collector.py:11`）：什么都不发，把根 run 收集进内存列表——单元测试断言"链确实调了模型"的好帮手。

**token 与费用**：模型 run 的 `outputs` 里带 3.3 节的 `usage_metadata`；LangSmith 按此聚合费用。自建观测平台（自托管 Langfuse 等）也走同一协议。

## 6.4 astream_events：给应用的事件流

【源码证据】`runnables/base.py:1343`：`astream_events(input, version, ...)`。它在链执行时同时驱动一个事件生成器，按发生顺序产出 `on_chat_model_stream`（token 增量）、`on_chat_model_start/end`、`on_tool_start/end`、`on_chain_stream` 等事件对象。`version` 参数（"v1"/"v2"）指定事件协议版本——langchain 1.3.0 又为智能体新增 **v3**（1.3.0 release notes："This release adds support for `version="v3"` in `stream_events` / `astream_events` for `langchain` agents"），v3 的事件语义面向 LangGraph 的图执行模型重新梳理。

LangGraph 侧另有更细的 `stream(mode=...)`（values/updates/messages/custom，见第八章），两者配合覆盖"应用要进度条"与"调试要全量"两类需求。

## 6.5 本章小结

- 回调是事件总线（四类 run × start/end/error），config 沿链传播保证事件树不断链；tracer 是"把事件流重放成运行树"的特殊 handler，LangSmith 只是它的一个目标端。
- 运行树的标准 schema、token 统计、`get_child` 的父子契约都是 core 的一部分——**可观测性协议与业务抽象同层**，这是 LangChain 区别于一般 SDK 的重仓投资。
- 应用层拿实时进度用 `astream_events`（v1/v2/v3），调试时 `set_debug` 最省事。

---

# 七、langchain 1.x 主包：create_agent 与中间件系统

> 本章对应源码：`libs/langchain_v1/langchain/`。这是 1.x 真正的"主角章"：主包瘦身之后剩下的核心资产就是它。

## 7.1 模块定位：1.x 主包还剩什么

**先白话**：打开 `langchain` 1.4.3 的源码目录，会惊讶它有多小——顶层 `__init__.py` 只有一行版本号：

【源码证据】`libs/langchain_v1/langchain/__init__.py`：

```python
"""Main entrypoint into LangChain."""
__version__ = "1.4.3"
```

全部家当（实测文件清单）：

```
langchain/
├── agents/            # ★ 唯一重头戏
│   ├── __init__.py    #    __all__ = ["AgentState", "create_agent"]
│   ├── factory.py     #    create_agent 本体（2164 行）
│   ├── structured_output.py  # ToolStrategy/ProviderStrategy/AutoStrategy
│   ├── middleware/    #    ★ 中间件体系（types.py 2201 行 + 16 个内置中间件模块）
│   └── _subagent_transformer.py
├── chat_models/       # init_chat_model（按字符串装配任意厂商模型）
├── tools/             # tool_node.py（从 langgraph.prebuilt 再导出 ToolNode/ToolRuntime）
├── messages/          # 从 langchain_core.messages 再导出全部消息类型
├── embeddings/        # 再导出 core 的 Embeddings
├── rate_limiters/     # 再导出 core 的 InMemoryRateLimiter
└── mcp/               # 1.4.0 新增：MCPAdapter（MCP 服务器工具桥接）
```

主包的角色变化值得用 Spring 类比说透：**0.x 的 langchain 什么都有（像把 spring-core/tx/web 揉一团的 monolith）；1.x 的 langchain 是 spring-context——自己不实现多少机制，专注"把 core 抽象 + langgraph 运行时组装成应用层开箱 API"**。`messages`、`tools`、`embeddings` 这些目录全是再导出，让用户写 `from langchain.messages import AIMessage` 一个入口就够。

## 7.2 create_agent：签名与一句话语义

【源码证据】`libs/langchain_v1/langchain/agents/factory.py:892-911`（重载声明在 :823/:846/:870，本体 :892）：

```python
def create_agent(
    model: str | BaseChatModel,                     # 字符串走 init_chat_model，或直接给实例
    tools: Sequence[BaseTool | Callable | dict] | None = None,
    *,
    system_prompt: str | SystemMessage | None = None,
    middleware: Sequence[AgentMiddleware[StateT, ContextT]] = (),
    response_format: ResponseFormat | type | dict | None = None,   # 结构化输出策略
    state_schema: type[AgentState[ResponseT]] | None = None,       # 自定义状态字段
    context_schema: type[ContextT] | None = None,                  # 运行期上下文类型
    checkpointer: Checkpointer | None = None,       # ★持久化（记忆/断点恢复）
    store: BaseStore | None = None,                 # 跨会话长期存储
    interrupt_before: list[str] | None = None,      # 图级中断点
    interrupt_after: list[str] | None = None,
    debug: bool = False,
    name: str | None = None,
    cache: BaseCache | None = None,
    transformers: Sequence[TransformerFactory] | None = None,
) -> CompiledStateGraph[AgentState[ResponseT], ContextT, ...]:
```

docstring 第一句话就是执行语义（`factory.py:912`）：

> **"Creates an agent graph that calls tools in a loop until a stopping condition is met."**

（创建一个"调用工具直到满足停止条件"的智能体图。）返回值类型 `CompiledStateGraph` 说明了一切：**create_agent 不是解释器，是编译器——把"模型+工具+中间件"编译成一张可执行的 LangGraph 图**。

最小示例（docstring 原文，`factory.py:1026-1041`）：

```python
from langchain.agents import create_agent

def check_weather(location: str) -> str:
    '''Return the weather forecast for the specified location.'''
    return f"It's always sunny in {location}"

graph = create_agent(
    model="anthropic:claude-sonnet-4-5-20250929",
    tools=[check_weather],
    system_prompt="You are a helpful assistant",
)
inputs = {"messages": [{"role": "user", "content": "what is the weather in sf"}]}
```

## 7.3 AgentState：智能体的"共享内存"

【源码证据】`libs/langchain_v1/langchain/agents/middleware/types.py:349-367`：

```python
class AgentState(TypedDict, Generic[ResponseT]):
    """State schema for the agent."""
    messages: Required[Annotated[list[AnyMessage], add_messages]]   # ★核心字段
    jump_to: NotRequired[Annotated[JumpTo | None, EphemeralValue, PrivateStateAttr]]
    structured_response: NotRequired[Annotated[ResponseT, OmitFromInput]]

class InputAgentState(TypedDict):     # 输入只允许 messages
    messages: Required[Annotated[list[AnyMessage | dict[str, Any]], add_messages]]

class OutputAgentState(TypedDict):    # 输出 = messages + structured_response
    messages: Required[Annotated[list[AnyMessage], add_messages]]
    structured_response: NotRequired[ResponseT]
```

三个注解是 LangGraph 的通道语义（第八章细讲）：`add_messages` 表示新消息**追加合并**（不是覆盖，且按 id 去重/替换）；`EphemeralValue` 表示 `jump_to` 用完即弃、不进历史；`OmitFromInput` 表示调用者无权直接塞 `structured_response`。**输入/输出 schema 与内部 schema 分离**——调用者只能通过"发消息"与智能体交互，这就是智能体的 API 边界。

`state_schema` 参数允许用户扩展状态（加自定义字段），与各中间件声明的 `state_schema` **按序合并**（`factory.py:1226-1234`，middleware 先、用户 schema 后、冲突时用户赢）。

## 7.4 图装配源码走读：create_agent 内部十步

**先白话**：`create_agent` 的 2164 行里，一大半在处理"中间件节点怎么插进图、边怎么连"。真正的装配骨架只有十步，全部对应源码行号如下：

【源码证据】`libs/langchain_v1/langchain/agents/factory.py`：

```python
# ① 合并状态 schema（middleware 的在前、用户的 base_state 在后，冲突用户赢）   :1226-1234
resolved_state_schema, input_schema, output_schema = _resolve_schemas(state_schemas)

# ② 建图（把合并后的三个 schema 交给 StateGraph）                            :1237-1244
graph: StateGraph[...] = StateGraph(
    state_schema=resolved_state_schema, input_schema=input_schema,
    output_schema=output_schema, context_schema=context_schema,
)

# ③ 注册 model 节点（同步/异步双实现打包为 RunnableCallable）                 :1611
graph.add_node("model", RunnableCallable(model_node, amodel_node, trace=False))

# ④ 注册 tools 节点（来自 langgraph.prebuilt.ToolNode）                      :1614-1615
if tool_node is not None:
    graph.add_node("tools", tool_node)

# ⑤ 为每个重写了钩子的中间件注册独立节点（节点名 = "{中间件名}.{钩子名}"）      :1618-1713
#    例："SummarizationMiddleware.before_model"、HITL 中间件的 after_model……
for m in middleware:
    if m.__class__.before_agent is not AgentMiddleware.before_agent or ...:
        graph.add_node(f"{m.name}.before_agent", ..., trace_policy=_node_trace_policy(m.trace_policy))
    if m.__class__.before_model is not AgentMiddleware.before_model or ...:
        graph.add_node(f"{m.name}.before_model", ...)
    # after_model / after_agent 同理

# ⑥ 决定四个关键位置节点：入口/循环入口/循环出口/出口                          :1715-1741
entry_node     = 第一个 before_agent（没有则第一个 before_model，否则 "model"）
loop_entry_node = 第一个 before_model（没有则 "model"）    # tools 循环回到这里
loop_exit_node = 最后一个 after_model（没有则 "model"）
exit_node      = 最后一个 after_agent（没有则 END）

# ⑦ 主边：START → 入口                                                       :1743
graph.add_edge(START, entry_node)

# ⑧ 核心循环的两条条件边                                                      :1745-1786
graph.add_conditional_edges("tools", _make_tools_to_model_edge(...),  destinations)  # :1755
graph.add_conditional_edges(loop_exit_node, _make_model_to_tools_edge(...), dests)   # :1775
#   有 return_direct 工具或结构化输出工具时，tools 可以直接跳 exit_node        :1748-1753

# ⑨ 中间件节点两两串联（itertools.pairwise），且每条边都允许 jump_to 重路由    :1813-1895
for m1, m2 in itertools.pairwise(middleware_w_before_model):
    _add_middleware_edge(graph, name=f"{m1.name}.before_model",
        default_destination=f"{m2.name}.before_model",
        model_destination=loop_entry_node,       # 中间件可强制"回到模型重跑一轮"
        end_destination=exit_node,               # 也可强制"直接结束"
        can_jump_to=_get_can_jump_to(m1, "before_model"))

# ⑩ 编译（挂 checkpointer/store/interrupt/transformers）+ 固定递归上限         :1897-1925
config["recursion_limit"] = 9_999     # :1899（防失控循环的兜底闸）
return graph.compile(checkpointer=checkpointer, store=store,
    interrupt_before=interrupt_before, interrupt_after=interrupt_after, ...,
    transformers=[ToolCallTransformer, SubagentTransformer, *middleware_transformers, ...],
).with_config(config)
```

**这张图长什么样**（无中间件、最简情况）：

```
                    ┌──────────────────────────────┐
                    ▼                              │
  START ──▶ model ──▶ (条件边) ──▶ tools ────────────┘
                │            │  有 tool_calls → tools
                │            │  无 tool_calls → END
                └────────────┴─▶ END
```

（有中间件时，`before_*` 节点串在 model 之前、`after_*` 节点串在 model/tools 之后，形状不变、节点变多。）用 `graph.get_graph().draw_mermaid()` 可对本章结论直接验图。

`_handle_model_output`（`:1246` 起）是 model 节点的收尾逻辑：若启用 `ProviderStrategy` 则直接解析 AI 消息为结构化响应（`:1257-1272`）；若走 `ToolStrategy` 则从 `tool_calls` 里找结构化输出工具并校验（`:1275` 起），失败时可按策略重试。

## 7.5 中间件系统：AgentMiddleware 的钩子面

**先白话**：中间件 = "把横切逻辑插进智能体循环"的官方姿势——审查/改写发往模型的请求、截获模型响应、包装工具调用、在循环前后做事。它是 Spring `HandlerInterceptor` + AOP 的混合体：钩子按"位置"划分（before/after/wrap），执行顺序可预期。

【源码证据】`libs/langchain_v1/langchain/agents/middleware/types.py:385-429`——中间件基类的**字段面**：

```python
class AgentMiddleware(Generic[StateT, ContextT, ResponseT]):
    state_schema: type[StateT] = _DefaultAgentState   # L397：向 AgentState 注入自定义字段
    tools: Sequence[BaseTool]                          # L400：向智能体注册额外工具
    trace_policy: TracePolicy | None = None            # L403：控制钩子 span 的追踪采样/脱敏
    transformers: Sequence[TransformerFactory] = ()   # L413：注册流转换器
```

【钩子面】（全部钩子的方法与行号，同步/异步成对出现）：

| 钩子 | 位置 | 签名要点 | 典型用途 |
|---|---|---|---|
| `before_agent` / `abefore_agent` | types.py:431 | `(state, runtime) -> dict \| None`，**每次会话一次** | 会话级初始化（载入用户偏好） |
| `before_model` / `abefore_model` | :455 | 同上，**每轮循环一次** | 改写状态（裁剪消息、注入检索结果） |
| `wrap_model_call` / `awrap_model_call` | :503 | `(request: ModelRequest, handler) -> ModelResponse` | **洋葱式**包装模型调用（改请求参数、限流、审计、失败降级） |
| `after_model` / `aafter_model` | :479 | `(state, runtime) -> dict \| None` | 审查模型输出、改写/拦截 |
| `wrap_tool_call` / `awrap_tool_call` | :674 | 包装单次工具执行 | 工具超时、脱敏、模拟 |
| `after_agent` / `aafter_agent` | :650 | 每次会话结束 | 收尾上报、清理 |

`ModelRequest`（types.py:88）是 wrap_model_call 拿到的"模型调用请求"对象，携带 model/messages/system_prompt/tools，`override(**overrides)`（:203）产出修改后的副本——**只读快照 + 显式 override**，避免中间件互相踩踏。

**两种写法**：继承 `AgentMiddleware` 重写钩子；或用装饰器把普通函数变成中间件。装饰器在 `types.py` 实测有八个（导出于 middleware/__init__.py）：`@before_model`（:934）、`@after_model`（:1123）、`@before_agent`（:1300）、`@after_agent`（:1512）、`@wrap_model_call`、`@wrap_tool_call`、`@dynamic_prompt`（把函数变成"动态生成系统提示词"）、`@hook_config`（:879，给钩子附加配置如 jump_to 目标）。

**节点化是关键实现细节**：7.4 节⑤已证——每个重写了钩子的中间件会变成图里**真实的节点**（`"{m.name}.before_model"`），边还带 `can_jump_to` 重路由能力。也就是说中间件不是"调用时的回调函数"，而是**编译进图拓扑的一等公民**——这保证了执行顺序静态可预测、可被 checkpointer 逐节点断点恢复、可被 LangSmith 逐节点追踪。

## 7.6 内置中间件全家桶

1.x 已随包提供 16 个内置中间件模块（`libs/langchain_v1/langchain/agents/middleware/` 实测 22 个 py 文件中，`types.py` 为基类、`_execution/_redaction/_retry/_trace_policy` 为内部辅助），按用途归类：

| 类别 | 中间件（类名） | 白话 |
|---|---|---|
| **上下文工程** | `SummarizationMiddleware`（summarization.py） | 历史太长自动摘要压缩（保 token 预算） |
| | `ContextEditingMiddleware` / `ClearToolUsesEdit`（context_editing.py） | 自动清除老旧工具结果，腾上下文 |
| | `LLMToolSelectorMiddleware`（tool_selection.py） | 用小模型预筛本轮要暴露给模型的工具 |
| | `ProviderToolSearchMiddleware`（provider_tool_search.py） | 工具太多时走厂商侧工具搜索 |
| | `FilesystemFileSearchMiddleware`（file_search.py） | 文件搜索工具注入 |
| **人机协同** | `HumanInTheLoopMiddleware`（human_in_the_loop.py，导出 `InterruptOnConfig`） | 工具执行前挂起等人工审批（基于 LangGraph interrupt） |
| **可靠性** | `ModelRetryMiddleware`（model_retry.py，1.1.0 新增） | 模型调用失败指数退避重试 |
| | `ModelFallbackMiddleware`（model_fallback.py） | 主模型失败切换备选模型 |
| | `ToolRetryMiddleware`（tool_retry.py） / `ToolErrorMiddleware`（tool_error.py） | 工具失败重试 / 错误信息友好化回喂 |
| | `ModelCallLimitMiddleware`（model_call_limit.py） / `ToolCallLimitMiddleware`（tool_call_limit.py） | 限次熔断（防"无限烧钱"） |
| **安全合规** | `PIIMiddleware`（pii.py，导出 `PIIMatch`/`PIIDetectionError`） + `_redaction.py` | PII 检测/脱敏 |
| **执行沙箱** | `ShellToolMiddleware`（shell_tool.py，配 `HostExecutionPolicy`/`DockerExecutionPolicy`/`CodexSandboxExecutionPolicy`） | 让智能体跑 shell，且**在宿主/Docker/沙箱三种策略下执行** |
| **任务管理** | `TodoListMiddleware`（todo.py） | 给智能体注入"任务清单"工具（复杂任务分解） |
| **测试** | `LLMToolEmulator`（tool_emulator.py） | 用 LLM 模拟工具返回（上游服务没好时联调智能体） |
| **MCP** | （主包 `langchain/mcp/adapter.py` 的 `MCPAdapter`） | 把 MCP 服务器暴露的工具整体接入 create_agent（1.4.0） |

**用法一瞥**（组合三个内置中间件）：

```python
from langchain.agents.middleware import (
    SummarizationMiddleware, HumanInTheLoopMiddleware, ModelFallbackMiddleware,
)

agent = create_agent(
    model="openai:gpt-5.2",
    tools=[run_sql, send_email],
    middleware=[
        # 上下文超 10 万 token 就用小模型摘要压缩（第一个参数是"摘要用什么模型"）
        SummarizationMiddleware(model="openai:gpt-5-mini", trigger=("tokens", 100_000)),
        HumanInTheLoopMiddleware(interrupt_on={"send_email": True}),  # 发邮件前必须人工审批
        ModelFallbackMiddleware("anthropic:claude-sonnet-4-5"),       # OpenAI 挂了切 Anthropic
    ],
)
```

## 7.7 结构化输出策略：ToolStrategy / ProviderStrategy / AutoStrategy

【源码证据】`libs/langchain_v1/langchain/agents/structured_output.py`（行号）：

```python
class ToolStrategy(Generic[SchemaT]):          # L196：借"工具调用"产结构化结果
class ProviderStrategy(Generic[SchemaT]):      # L271：用厂商原生 JSON Schema 约束
class AutoStrategy(Generic[SchemaT]):          # L457：按模型能力自动二选一（依据 model profile）
class StructuredOutputError(Exception):        # L35（含 :41 多次输出错误、:60 校验失败）
```

三者的取舍：`ToolStrategy` 兼容性最好（只要模型会调工具），但要多一跳；`ProviderStrategy` 走原生约束、最可靠（1.2.0 补充 `strict` 模式），依赖模型档案里"支持 json_schema 输出"的能力标记；`AutoStrategy` 让 `response_format=MySchema` 一个参数走天下。7.4 节 `factory.py:1257/1275` 的分支就是这两种策略在 model 节点内的运行时执行路径，失败经 `_handle_structured_output_error`（`:646`）决定重试或抛 `StructuredOutputValidationError`。

## 7.8 本章小结

- **1.x 主包 = 应用层组装者**：顶层仅一行版本号，核心资产是 `create_agent`（factory.py:892）+ 中间件体系（middleware/），其余目录全是再导出。
- **create_agent 是"编译器"**：十步装配（7.4 节）把 model/tools 两节点连成"调用工具直到无 tool_calls"的 StateGraph 环，中间件节点化插入、`jump_to` 可重路由，编译产物直接继承 LangGraph 的持久化/中断/流式能力。
- **AgentState 三个注解即 API 边界**：`add_messages` 追加合并、`EphemeralValue` 瞬时字段、输入/输出 schema 与内部 schema 分离。
- **中间件是 1.x 的扩展面**：六个位置钩子（before/after × agent/model + wrap × model/tool）+ 八个装饰器 + 22 个内置实现；不是回调，而是编译进图的一等节点。
- 结构化输出在智能体语境下固化为三种策略对象，自动策略依托 model profile 判定模型能力。

---

# 八、LangGraph：create_agent 背后的运行时

> 说明：LangGraph 是独立仓库（langchain-ai/langgraph，当前 1.2.x），本章源码级细节以其公开文档与 API 约定为准，不标行号；与 langchain 的耦合点（`create_agent` 的 import、pyproject 依赖）已在前文实证。

## 8.1 为什么需要图运行时

**先白话**：写个"模型+工具"循环，10 行 Python 就够，为什么要引入一个框架？因为**生产智能体的需求不在 happy path**：要记住上下文（挂了能恢复）、要能中途停下等人审批、要能并行扇出子任务、要流式、要可观测。这些横向需求每个智能体都要，自己手写必然重复且易错。LangGraph 的答案是：**把智能体表达为状态图，用通用引擎执行**——Spring 用户可以理解为"DispatcherServlet 之于 Web 开发：你只注册 handler，分发、异常、会话、异步都由引擎管"。

LangGraph 的三个核心概念：

| 概念 | 是什么 | create_agent 里的对应物 |
|---|---|---|
| `StateGraph` | 以 TypedDict 状态为中心的有向图；节点是函数（读状态→返回状态增量），边是转移（含条件边） | factory.py:1237 建图、:1611/:1615 加节点 |
| Pregel 引擎 | 按"超步"（superstep）执行：一轮内所有就绪节点并行跑完、状态增量归并、再进入下一轮 | 循环往复直到无节点就绪 |
| Checkpointer | 每个超步后把状态快照存起来（Memory/SQLite/Postgres…），按 thread_id 组织 | create_agent 的 `checkpointer` 参数直通 |

状态增量怎么归并，由字段上的 **reducer 注解**决定——7.3 节 `AgentState.messages` 的 `Annotated[list[AnyMessage], add_messages]` 就是官方 reducer：按消息 id 去重合并、其余追加。

## 8.2 执行模型与三个"逃生舱"

- **interrupt / Command(resume=...)**：节点内调用 `interrupt(payload)` 立即挂起整个图，状态已持久化；外部决策后以 `Command(resume=...)` 恢复。`HumanInTheLoopMiddleware` 的审批流就是它的封装（工具执行前 interrupt，人批准后 resume）。
- **Send API**：条件边返回 `Send(node, state)` 列表可**动态扇出** N 个并行子任务（map-reduce 式；factory.py:36 导入了 `Send`，供中间件/自定义边使用）。
- **Command(graph=...)**：跨图跳转/移交（多智能体 handoff）。

流式模式（`graph.stream(..., stream_mode=...)`）：`values`（每步全量状态）/ `updates`（每步增量）/ `messages`（LLM token 级）/ `custom`（节点内主动上报）——与第六章 astream_events 互补。

## 8.3 记忆的两层：thread 内 checkpointer 与跨 thread store

create_agent 的两个参数分工明确：`checkpointer` 管**单线程（单会话）内的执行历史与快照**——传个 `InMemorySaver`/`SqliteSaver`，同一个 `thread_id` 再 invoke 就自动接上历史（0.x 教程里手动管理的"聊天记忆"就此消失）；`store` 管**跨会话的键值存储**（如记住某用户的长期偏好），配合 `InjectedStore` 注入工具。用 Spring 说：checkpointer 像"会话作用域"，store 像"全局缓存仓库"。

## 8.4 create_agent 的图（终景）

把 7.4 的装配与本章概念拼起来，一个带三个中间件的 create_agent 完整图：

```
          ┌────────────────────────────────────────────────────────────┐
          │                    （每轮循环重复区间）                        │
 START ─▶ before_agent_A ─▶ before_model_A ─▶ before_model_B ─▶ model   │
   │           │                ▲                                 │   │
   │           │           (jump_to 可重路由)                      ▼   │
   │           │                              after_model_B ◀─(执行)   │
   │           │                                 │   │              │
   │           │            有 tool_calls ────────┘   └─ 无 tool_calls
   │           │                 ▼                        ▼
   │           │              tools ──(循环回 loop_entry)   after_agent_A ─▶ END
   │           └─ (before_agent 只走一次，不参与循环)            ▲
   │                              (return_direct/结构化输出可直跳 exit) │
```

要点复述：`before_agent/after_agent` 是**会话级**（单圈），`before_model/after_model` 是**循环级**（每圈）；tools 的条件边把控制权还给"循环入口"（第一个 before_model 或 model）或直达 END（`return_direct` 工具/结构化输出工具存在时，见 factory.py:1748-1753）；每个中间件节点都有 jump_to 重路由权。这张图不是手画的示意——它是 7.4 节源码装配逻辑的直接投影，可用 `agent.get_graph().draw_mermaid()` 实证。

## 8.5 本章小结

- LangGraph = "状态图 + 超步引擎 + checkpointer"三件套，把智能体生产的横向需求（持久化/中断/扇出/流式）做成通用引擎；create_agent 是它之上的官方"编译器"。
- `interrupt()` + `Command(resume=...)` 是人机协同的底层机制，HITL 中间件是其封装。
- 记忆分两层：checkpointer（会话内，含执行快照）与 store（跨会话键值）；0.x 的 Memory 类体系已被这两层取代。

---

# 九、旧世界的归宿：langchain-classic、community 与周边

> 本章对应源码：`libs/langchain/langchain_classic/`（包名 langchain-classic 1.0.8）。

## 9.1 langchain-classic：Chain / AgentExecutor 考古现场

**先白话**：0.x 时代的主 API 有两大家族——**Chain**（把"提示词+模型+后处理"预制成固定流程的类）与 **AgentExecutor**（"模型选工具→执行→再问模型"的循环解释器）。1.0 把它们整体搬到 `langchain_classic` 包，源码一行没删。读它们有两个目的：看懂旧教程/旧代码；体会"为什么这个设计被替换"。

【源码证据】Chain 家族（`libs/langchain/langchain_classic/chains/`）：

```python
class Chain(RunnableSerializable[dict[str, Any], dict[str, Any]], ABC):   # base.py:52
class LLMChain(Chain):                                                     # llm.py:45（_call 在 :112）
class SequentialChain(Chain):                                              # sequential.py:16
# 实测还有 40+ 个专用链目录：conversation/（聊天）、retrieval_qa/（问答）、
# summarization/、sql_database/、constitutional_ai/、hyde/、flare/、router/ ……
```

Chain 家族的运行时特征：`Chain` 继承的是 `RunnableSerializable`（**0.1 重构后 Chain 已经 Runnable 化**——所以旧代码也能进 LCEL 管道）；输入输出都是 `dict[str, Any]`。**被替换的原因**：固定流程无法表达"模型决定下一步做什么"，且每来一个新场景就要新写一个 Chain 类——40+ 个专用链就是这种"类爆炸"的证据。

【源码证据】Agent 家族（`libs/langchain/langchain_classic/agents/agent.py`）：

```python
class BaseSingleActionAgent(BaseModel):        # :55
class Agent(BaseSingleActionAgent):            # :704（ReAct/结构化聊天等一族的基类）
class AgentOutputParser(BaseOutputParser[AgentAction | AgentFinish]):   # :361
class AgentExecutor(Chain):                    # :1012 ← 循环解释器本体
    def _should_continue(self, iterations: int, time_elapsed: float) -> bool:   # :1235
    # 主循环：
    while self._should_continue(iterations, time_elapsed):        # :1589
        next_step_output = self._take_next_step(...)              # :1590
```

`AgentExecutor` 的问题清单：循环是**手写 while**（无快照、挂了从头来）；"停机条件"是迭代次数/超时（`:1235`，而 1.x 的中间件能做到按 token/按费用熔断）；中断恢复、人工审批、并行工具全都要自己另写。1.x 用"图 + checkpointer + 中间件"逐一替换了这些短板——**对照新旧两版执行器，就是"为什么有 LangGraph"的最佳教材**。

新旧 API 对照速查（迁移时用）：

| 0.x（现 langchain_classic） | 1.x 替代 |
|---|---|
| `LLMChain(prompt, llm)` | `prompt \| model \| StrOutputParser()`（LCEL） |
| `ConversationChain` + `ConversationBufferMemory` | `create_agent(checkpointer=InMemorySaver())` |
| `initialize_agent(tools, llm, agent="zero-shot-react-description")` | `create_agent(model, tools)` |
| `AgentExecutor(agent, tools, max_iterations=…)` | `create_agent(..., middleware=[ModelCallLimitMiddleware(...)])` |
| `create_retriever_chain / RetrievalQA` | LCEL：`retriever \| formatting_prompt \| model` 或 agentic RAG |

## 9.2 langchain-community 与 partner 包的分工

- **partner 包（libs/partners/，17 个）**：单一厂商/产品的官方适配器，独立版本号、独立发版（如 langchain-openai 1.6.7、langchain-anthropic 1.7.5），质量门槛是 `libs/standard-tests/` 里的标准测试套件（所有模型/向量库实现都要过同一套契约测试——又一个 Spring "测试套件即接口" 的熟悉味道）。
- **langchain-community（独立包，当前 0.4.x）**：社区贡献的集成大杂烩（几百个 loader/vectorstore/工具），独立节奏维护；1.x 主包与它**零依赖**（1.4 依赖表为证），用不用随你。

## 9.3 周边产品：一张图定位

- **LangSmith**：SaaS 可观测平台（追踪/评测/数据集/提示词管理）。协议在 core（tracers），服务在云端；自托管替代品（如 Langfuse）走同一回调协议接入。
- **LangGraph Platform / Studio**：图应用的托管部署与可视化调试（把 CompiledStateGraph 交给平台，获得持久化、定时任务、人工任务队列）。
- **deepagents**：LangChain 官方的"深度智能体"脚手架包（规划/文件系统/子智能体等组合模式的预制品），基于 create_agent 与中间件构建——**官方示范"中间件组合出复杂智能体"** 的参考实现。
- **LangChain Hub**：提示词/prompt 模板的分享仓库（`hub.pull("langchain-ai/react-agent")`）。

## 9.4 本章小结

- langchain-classic 原样保留了 Chain（40+ 类的"类爆炸"）与 AgentExecutor（手写 while 循环）——它们的存在方式本身就是 1.x 架构决策的论证材料。
- partner 包走标准契约测试，community 独立节奏；主包与两者零耦合。
- LangSmith/Platform/Studio/deepagents/Hub 构成"框架之上的产品层"，均以开放协议（回调/图规范/MCP）松耦合接入。

---

# 十、贯通视图：把三条时间线叠成一张全景图

前九章是分镜头，本章把三条最常见的执行时间线叠加起来，检验你是否真的把全图拼上了。

## 10.1 时间线一：一次 `chain.invoke()`（LCEL 链）

```
用户: chain.invoke({"question": "…"}, config={"callbacks": [tracer]})
  │
  ├─ RunnableSequence.invoke                     base.py:3430
  │    ├─ on_chain_start（根 run 入树）
  │    ├─ step1: ChatPromptTemplate.invoke       chat.py:794
  │    │     变量填充 → ChatPromptValue（LanguageModelInput）
  │    ├─ step2: ChatOpenAI.invoke               chat_models.py:475（partner 包子类）
  │    │     └─ generate_prompt → generate → _generate_with_cache  :1892
  │    │          缓存? → 限流器 acquire → HTTP 调 OpenAI → AIMessage(usage_metadata)
  │    │          （若挂了流式 tracer，自动走 _stream，chunk 累积成 AIMessage）
  │    └─ step3: StrOutputParser.invoke          output_parsers/base.py:140
  │          AIMessage.content → "…"
  └─ on_chain_end（根 run 收口；LangChainTracer 把整棵 run 树发 LangSmith）
```

一条主线，三个断面各来一次：**回调**（每步 start/end）、**config 传播**（patch_config 挂子回调）、**模板方法**（模型父类管横切、子类管生成）。

## 10.2 时间线二：一次 `agent.invoke()`（create_agent 智能体）

```
用户: agent.invoke({"messages":[HumanMessage("查天气并写邮件")]},
                   config={"configurable": {"thread_id": "t1"}})
  │
  ├─ CompiledStateGraph（Pregel 引擎，checkpointer=SqliteSaver）
  │    超步1: SummarizationMiddleware.before_model（历史过长→压缩）
  │    超步2: model 节点 → ChatModel.invoke → AIMessage(tool_calls=[get_weather, send_email])
  │    超步3: 条件边（有 tool_calls → tools）
  │    超步4: tools 节点（ToolNode 并行执行）
  │         ├─ get_weather → ToolMessage("sunny")
  │         └─ send_email → HumanInTheLoopMiddleware.wrap_tool_call
  │               └─ interrupt("请审批发送邮件")   ← 图挂起，状态已持久化
  │
  ├─ （人类审批：app.Command(resume=approved)）
  │    超步5: send_email 真正执行 → ToolMessage("sent")
  │    超步6: 条件边 → model → AIMessage(无 tool_calls) → 条件边 → END
  │
  └─ 返回 {"messages": [...], "structured_response": 可选}
```

与时间线一的本质区别：**执行由图引擎驱动而非函数调用栈驱动**——所以能挂起（interrupt）、能恢复（checkpointer）、能回滚重试（中间件）、能被观测（每个节点一个 run）。时间线一的组件调用链在这里作为"节点内部实现"继续存在（model 节点内部就是一次完整的时间线一）。

## 10.3 时间线三：RAG 问答（索引一次，检索多次）

```
【离线索引】
  docs = PyPDFLoader(path).lazy_load()            # document_loaders/base.py:91
  chunks = RecursiveCharacterTextSplitter(...).split_documents(docs)   # character.py:91
  index(docs_source, vectorstore, record_manager, cleanup="incremental")   # indexing/api.py
      └─ 哈希去重 → embeddings.embed_documents → vectorstore.add_documents

【在线问答（agentic RAG）】
  agent = create_agent(
      model="openai:gpt-5.2",
      tools=[retriever.as_retriever(k=5)],        # vectorstore/base.py:905 → Runnable 工具
      middleware=[SummarizationMiddleware(...), ModelFallbackMiddleware(...)])
  agent.invoke({"messages": [...]}, config={"configurable": {"thread_id": ...}})
      └─ model 决定"先检索"→ tools 节点执行 retriever → 5 个 Document 回喂
      └─ model 基于 Document 生成最终回答（可再循环、可 search 其他工具）
```

五级流水线（5.1 节）在"在线"侧消失为一个工具调用——**RAG 从"固定管道"进化为"智能体可决策的一步"**，这是 1.x 时代 RAG 的默认写法（agentic RAG）；时间线一的固定 LCEL 管道仍是简单场景的合理选择。

## 10.4 全景合龙

- **三个抽象撑起一切**：Runnable（执行协议）、标准策略接口（ChatModel/VectorStore/Embeddings/Loader/Tool）、内容块消息（跨厂商数据模型）。
- **一个运行时接住所有生产需求**：LangGraph 的 checkpointer/interrupt/Send/stream；create_agent 与中间件是它的官方应用层。
- **两套扩展面**：LCEL 修饰器（组件级横切）与 AgentMiddleware（智能体级横切），前者作用于"一次调用"，后者作用于"整个循环"。
- **一条观测主轴贯穿**：从 Runnable 的 on_chain_start 到图节点的 run，全部汇入 callbacks/tracers → LangSmith 或自建平台。

---

# 十一、附录

## 11.1 关键接口速查表

| 接口/类 | 源码位置 | 核心方法 | 一句话 |
|---|---|---|---|
| `Runnable` | core/runnables/base.py:133 | invoke/batch/stream/transform | 一切可执行单元的协议 |
| `RunnableSequence` / `RunnableParallel` | base.py:3075 / 3864 | `｜` / dict 字面量 | LCEL 的串行/并行原语 |
| `RunnableLambda` / `RunnableBranch` | base.py:4703 / branch.py:43 | — | 普通函数接入 / 条件路由 |
| `RunnableRetry` / `RunnableWithFallbacks` | retry.py:48 / fallbacks.py:37 | with_retry / with_fallbacks | 重试 / 降级 |
| `RunnableWithMessageHistory` | history.py:39 | — | 无 LangGraph 时的会话历史 |
| `BaseMessage` 系 | core/messages/base.py:93、ai.py:160 | — | 消息与内容块（16 类） |
| `BaseChatModel` | core/language_models/chat_models.py:284 | _generate/_astream | 聊天模型抽象（模板方法） |
| `init_chat_model` | langchain/chat_models/base.py:232 | — | 按字符串装配模型 |
| `BasePromptTemplate` / `ChatPromptTemplate` | core/prompts/base.py:38 / chat.py:794 | invoke→PromptValue | 提示词配方 |
| `BaseTool` / `@tool` | core/tools/base.py:433 / convert.py:77 | _run/invoke | 工具 = 函数 + schema 的 Runnable |
| `BaseOutputParser` | core/output_parsers/base.py:140 | parse | 文本→结构化 |
| `Embeddings` | core/embeddings/embeddings.py:8 | embed_documents/Query | 向量化契约 |
| `VectorStore` / `as_retriever` | core/vectorstores/base.py:43 / 905 | similarity_search | 向量库契约 / 变身 Runnable |
| `BaseLoader` | core/document_loaders/base.py:26 | lazy_load | 文档加载契约 |
| `BaseCallbackHandler` / `BaseTracer` | core/callbacks/base.py / tracers/base.py:33 | on_* / persist_run | 事件监听 / 运行树 |
| `create_agent` | langchain/agents/factory.py:892 | — | 智能体编译器（1.x 主 API） |
| `AgentMiddleware` | langchain/agents/middleware/types.py:385 | 6 钩子 + state_schema/tools | 智能体横切扩展面 |
| `AgentState` | 同 types.py:349 | — | 智能体共享内存 schema |
| `ToolStrategy` / `ProviderStrategy` / `AutoStrategy` | langchain/agents/structured_output.py:196/271/457 | — | 结构化输出三策略 |
| `Chain` / `AgentExecutor`（旧） | langchain_classic/chains/base.py:52 / agents/agent.py:1012 | — | 0.x 主 API（考古用） |

## 11.2 初学者学习路线

1. **第 0 步（1 天）**：跑通"prompt | model | parser"最小链；用 `set_debug(True)` 观察每步输入输出；再跑通 `create_agent` + 一个 `@tool` 函数的天气示例（7.2 节原码可抄）。
2. **第 1 周**：按第二章读懂 Runnable 协议与 3~4 个修饰器（bind/with_retry/with_fallbacks），亲手把一条链加上重试与降级；给模型接 `with_structured_output`，对比解析器方案。
3. **第 2 周**：按第五、六章搭一条完整 RAG 链（InMemoryVectorStore 起步），打开 LangSmith（或自建 Langfuse）看运行树；把检索器改造成 create_agent 的工具，体会 agentic RAG。
4. **第 3 周（进阶）**：按第七章给 create_agent 写一个自定义中间件（建议先写 `@before_model` 打日志，再写 `wrap_model_call` 改请求），启用 checkpointer 体验 interrupt 恢复；用 `agent.get_graph().draw_mermaid()` 对照 7.4/8.4 节验证自己的图。
5. **常备资料**：官方文档 docs.langchain.com（OSS Python 板块）、两仓库 API 参考（langchain-ai/langchain 与 langchain-ai/langgraph）、本文 `D:\tmp\langchain-src` 本地源码。

## 11.3 Spring ↔ LangChain 概念对照表（写给 Java 人）

| Spring 概念 | LangChain 对应 | 相似点 | 关键差异 |
|---|---|---|---|
| IoC 容器 / `BeanFactory` | Runnable 协议 + LCEL | 统一的生命周期与装配契约，组件面向接口 | LangChain 无容器：组合靠管道而非注册表 |
| 依赖注入 | `init_chat_model` / constructor 注入模型 | 依赖声明与实现分离 | 运行期切换靠 configurable（动态代理式）而非容器 refresh |
| `PlatformTransactionManager`（一接口多实现） | `BaseChatModel` / `VectorStore` / `Embeddings` | 策略接口 + 厂商适配 | — |
| AOP 切面 / 拦截器链 | `AgentMiddleware` 钩子 / LCEL 修饰器 | 横切逻辑与业务分离 | 无切点表达式；中间件编译进图、顺序显式 |
| `DispatcherServlet` 前端控制器 | LangGraph Pregel 引擎 | 集中分发、策略可插 | 图执行模型（超步归并）而非请求转发 |
| 会话（`HttpSession`） | checkpointer（thread_id） | 会话状态隔离与恢复 | 状态可持久化、可断点续跑 |
| 全局缓存（Caffeine 集成） | `store`（跨会话键值） | — | — |
| 事件发布（ApplicationEvent） | callbacks / tracers | 观察者模式解耦 | 事件即运行树，天然对接 SaaS 观测 |
| 自动配置（spring-boot autoconfigure） | `init_chat_model` 按名装配 + partner 包 | 约定优于配置 | 无条件评估机制，纯命名约定 |
| 维护态 API（RestTemplate） | langchain-classic 的 Chain/AgentExecutor | 旧 API 冻结、新代码别用 | 兼容包物理隔离，import 路径即版本信号 |

## 11.4 本文证据说明

- 源码证据：`D:\tmp\langchain-src`（langchain-ai/langchain master 浅克隆，2026-10-04），对应 langchain 1.4.3 / langchain-core 1.6.6；行号为该快照实际读取值。
- 版本与日期：各包 PyPI 元数据（上传时间戳）与 GitHub Releases（langchain==1.1.0/1.2.0/1.3.0/1.4.0 release notes）；1.0 的发布日期 2025-10-17 同时得到 langchain/langgraph/langchain-classic 三包 PyPI 时间戳交叉印证。
- LangGraph（第八章）为独立仓库，本章机制描述基于其公开文档与 API 约定，未含行号级证据。

