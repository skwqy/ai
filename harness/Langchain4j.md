# LangChain4j 深度源码解析（写给初学者的架构全景）

> **本文基于的源码**：`D:\code\3rd\langchain4j`，即 GitHub 仓库 [langchain4j/langchain4j](https://github.com/langchain4j/langchain4j) 的 main 分支快照（2026-10-11 下载，对应 **1.23.0-beta33-SNAPSHOT**，即 1.22.0 发布三天后的主干）；Spring Boot 集成部分基于 [langchain4j/langchain4j-spring](https://github.com/langchain4j/langchain4j-spring) 仓库同期快照。文中所有【源码证据】的文件路径与行号均为对该快照实际读取所得（路径以仓库根为基准，如 `langchain4j-core/src/main/java/dev/langchain4j/model/chat/ChatModel.java`）。
>
> **版本取舍说明**：LangChain4j 于 2025-05-14 发布 1.0.0 稳定版，此后进入快速迭代（写作时最新为 1.22.0，2026-10-08 发布，平均每 3~4 周一个 minor 版本）。1.x 的核心架构（ChatModel/AiServices/ToolService/RAG 流水线）自 1.0 起保持稳定，之后的新能力（guardrails、agentic、非阻塞、决策模型）都是在这套骨架上做加法。本文以 **1.22/1.23 主干为主线**，所有"某特性属于哪个版本"的结论均经 GitHub Releases 逐项核实（版本号+发布日期取自 GitHub API，非凭记忆）。
>
> **阅读约定**：每章"先白话、后源码"——先用一两句人话讲清"这是什么、为什么需要它"，再给出源码证据链（`模块/.../类名.java` + 方法名 + 行号 + 代码片段）。行号只对该快照精确，读者用 IDEA 打开源码按类名 + 方法名定位即可。
>
> **写给 Java/Spring 开发者的额外约定**：本文假设读者熟悉 Spring（IoC、AOP、动态代理、Spring Boot 自动配置），会用 Spring 概念类比（如"AiServices 之于 LangChain4j，约等于 Spring Data JPA 的 Repository 之于 Spring"）。同目录的姊妹篇《LangChain.md》（Python 版）与《LangGraph.md》讲的是同一品类的 Python 框架，第十二章附有两者的概念对照表。

## 如何读这份文档

如果你是 LangChain4j 初学者，推荐两遍读法：

- **第一遍（建立地图，1~2 小时）**：只读第一章（总览）每一节的开头白话段、各章的"小结"节、以及第十一章（贯通视图）。目标是能回答：LangChain4j 分哪几层？为什么一个只有接口没有实现的 Java 接口能直接被注入和调用？一次 `chatService.chat("你好")` 从注解解析到工具循环经历了什么？RAG 五段流水线各管什么？
- **第二遍（深入源码）**：对照每一章的【源码证据】，打开源码逐行读。顺序建议：第三章（AiServices，框架的心脏）→ 第四章（Tools 循环）→ 第二章（core 抽象，随用随查）→ 第六章（RAG）→ 第五章（Memory）→ 第八章（agentic，新大陆）→ 第十章（Spring 集成）→ 第九章（MCP/可观测性，随用随查）。

前置知识：Java 8+ 语法（动态代理、反射、`default` 方法）、Maven 基本用法、会调一次 OpenAI/任意大模型 API。不需要任何 LLM 工程经验。

---

# 一、总览：LangChain4j 的定位、设计哲学与整体架构

## 1.1 一句话定位

**LangChain4j 是一套"以 `ChatModel` 策略接口接入大模型、以 `AiServices` 动态代理承载声明式编程模型"的 Java LLM 应用框架**：它把"调用哪家模型、怎么渲染提示词、怎么解析输出、怎么接向量库"这些重复劳动抽象成统一接口（`ChatModel`、`EmbeddingModel`、`EmbeddingStore`、`ContentRetriever`、`ChatMemory`……），又把"模型↔工具循环"沉淀为框架内部自动执行的引擎（`ToolService`），让开发者只写一个 Java 接口 + 几个 `@Tool` 方法，就能得到一个完整的、能自己查资料、自己调工具的 AI 服务。

用 Spring 的话说：**langchain4j-core 是 spring-core（统一抽象与地基），langchain4j 主模块是 spring-context+aop（组装与动态代理），AiServices 是 Spring Data JPA 的 Repository（声明式接口编程模型），RAG 流水线是 Spring Integration 的路由管线，langchain4j-spring 是 spring-boot-starter**。

它解决的不是"怎么调一次模型 API"（那是各家 SDK 的事），而是 **LLM 应用的组织问题**：模型切换要不要改业务代码？提示词与业务逻辑怎么解耦？模型输出怎么可靠地变成强类型的 Java 对象？"模型思考→调用工具→观察结果→再思考"这个循环谁来跑、出错了怎么办？多轮对话的上下文谁来管？文档怎么切块、向量化、检索？

时间坐标：2023 年由 Dmytro Liubarskyi（dliubarskyi）创建（2023-06-20 首批 Maven 发布 0.1.0~0.4.0，经 Maven Central 元数据实证），是"LLM 应用框架"品类在 JVM 世界的第一个主流框架；2025-05-14 发布 1.0.0 稳定版；1.3.0（2025-08-07）引入多智能体模块 `langchain4j-agentic`，1.20.0（2026-09-04）引入全链路非阻塞/响应式 API。

## 1.2 设计哲学：读源码前先记住五句话

1. **声明式优先：写接口，不写实现**。框架的灵魂是 `AiServices`：你定义一个 Java 接口，方法上挂 `@SystemMessage`/`@UserMessage` 注解，`AiServices.create(接口.class, chatModel)` 返回一个 JDK 动态代理——方法没有实现体，代理在运行时把注解渲染成提示词、调模型、跑工具循环、解析输出。这与 MyBatis Mapper、Spring Data Repository、`@Transactional` 是同一种哲学（见第三章）。这是 LangChain4j 与 Python LangChain 最大的分野：Python 靠链式函数组合，Java 靠接口代理。
2. **core 极简，集成外置**。`langchain4j-core` 的运行时依赖只有 Jackson + SLF4J + JSpecify 三件（实测 `langchain4j-core/pom.xml`），模型供应商、向量库、文档加载器全部是独立模块，按需引入、互不认识。这与 Spring"一个 `PlatformTransactionManager` 接口 + JDBC/JPA/JTA 各自适配"的配方一模一样。
3. **模板方法 + `default` 方法 = Java 版的"部分抽象"**。Python 靠抽象基类让子类只实现 `_generate`，Java 没有抽象基类单继承的余量，LangChain4j 的解法是：`ChatModel` 接口上所有公开入口（`chat(String)`、`chat(ChatRequest)`、`chatAsync(...)`）都是 `default` 方法，内部只调用一个待实现的 SPI 钩子 `doChat(ChatRequest)`（`ChatModel.java:77`）。供应商模块只实现 `doChat`，监听器分发、参数合并、便捷重载全部由接口的 default 方法统一承担（见 2.2 节）。
4. **每个环节留口子（SPI + 监听器 + 策略接口）**。RAG 五段流水线的每一段都是接口（`QueryTransformer`/`QueryRouter`/`ContentRetriever`/`ContentAggregator`/`ContentInjector`），工具循环的每个可出错点都有错误处理器，模板引擎、JSON 编解码、线程池都可通过 `java.util.ServiceLoader` SPI 替换（`dev.langchain4j.spi` 包下 28 个工厂接口，见 9.4 节）——Quarkus 的 GraalVM 原生镜像支持就是靠替换 `PromptTemplateFactory` SPI 实现的（源码注释明说，见 `langchain4j-core/.../model/input/PromptTemplate.java:19-23`）。
5. **分批稳定，快速演进**。1.0 时代核心五件（core/langchain4j/http-client/open-ai/bom）先 GA，其余 100+ 模块以 `1.0.0-betaN` 继续打磨，此后每个 minor 版本（约 3~4 周一个）逐步"转正"一批模块。新特性先打 `@Experimental` 注解、走一两个版本观察期。所以阅读源码时看到 `@Experimental` 的 1.20/1.21 新 API 要心里有数：它们是前沿，不是地基。

## 1.3 模块分层全景

LangChain4j 不是一个包，而是**一个核心 + 一圈按需引入的集成模块 + 两个配套仓库（Spring/Community）** 组成的生态。主仓库 pom 实测 111 个 Maven 模块，按"用户感知"分层如下：

```
┌────────────── 配套仓库（独立版本节奏） ──────────────────────────┐
│ langchain4j-spring：40+ 个 Spring Boot starter（boot3/boot4 双线）│
│   + langchain4j-reactor（TokenStream→Flux 适配）                 │
│ langchain4j-community：19 个目录（社区模型/存储/加载器/MCP 工具） │
├──────────── 编排与横切模块（主仓库内） ─────────────────────────┤
│ langchain4j-agentic（多智能体工作流）  langchain4j-agentic-a2a   │
│   （Agent-to-Agent 协议） langchain4j-agentic-mcp agentic-patterns│
│ langchain4j-mcp / mcp-docker（MCP 客户端）  langchain4j-skills   │
│ langchain4j-guardrails  langchain4j-observation                 │
│ langchain4j-micrometer-metrics                                  │
├──────────── 集成层（按需引入，互不认识） ───────────────────────┤
│ 模型供应商（26 个）：open-ai / open-ai-official / anthropic /    │
│   bedrock / azure-open-ai / google-ai-gemini / google-genai /   │
│   vertex-ai(-gemini) / mistral-ai / ollama / watsonx / cohere / │
│   jina / nomic / voyage-ai / workers-ai / hugging-face /        │
│   jlama（本地推理）…                                            │
│ 嵌入存储（21 个）：pgvector / pinecone / qdrant / milvus(-v2) /  │
│   elasticsearch / opensearch / weaviate / chroma / mongodb-atlas│
│   / cassandra / couchbase / oracle / mariadb / hibernate /      │
│   infinispan / vespa / coherence …                              │
│ 文档：document-loaders(7) document-parsers(6) transformers(1)   │
│ 其它：web-search-engines(3)  code-execution-engines(3)          │
│   embeddings/*（ONNX 进程内嵌入模型，无网可用）                 │
├──────────── 应用层 ─────────────────────────────────────────────┤
│ langchain4j（AiServices / ToolService / ChatMemory / TokenStream│
│   / chain（遗留 ConversationalChain） / service.output 解析器族）│
│ langchain4j-easy-rag（一站式 RAG：tika 解析器+默认切分器+嵌入）  │
│ langchain4j-kotlin                                              │
├──────────── 核心抽象层 ─────────────────────────────────────────┤
│ langchain4j-core（ChatModel/StreamingChatModel、消息、嵌入模型与 │
│   存储接口、RAG 五段流水线接口、Guardrail 接口、ChatMemory、SPI）│
│ langchain4j-http-client（HTTP 抽象）+ jdk/apache/okhttp 三实现   │
│ langchain4j-reactive-streaming  langchain4j-jackson3（Jackson3）│
│ langchain4j-test（standard-test 契约测试基建）                  │
└─────────────────────────────────────────────────────────────────┘
```

一句话记忆：**业务代码只碰 `langchain4j`（AiServices）+ 一个供应商模块 + 一个存储模块；`langchain4j-core` 是阅读源码理解机制的主战场；`langchain4j-easy-rag` 是偷懒的一站式 RAG 套餐；agentic/MCP/skills 是 1.3 之后长出的"第二曲线"**。

## 1.4 模块依赖图（以各模块 pom.xml 实证）

```
                 langchain4j-core（一切的地基；仅依赖 Jackson+SLF4J+JSpecify）
                  ▲    ▲      ▲      ▲      ▲
     ┌────────────┘    │      │      │      └──────────────┐
     │                 │      │      │                     │
 langchain4j-http-client│      │      │              所有嵌入存储模块
 (抽象层：jdk/apache/okhttp三实现) │      │              (pgvector/qdrant/…)
     ▲                 │      │      │                     ▲
     │                 │      │      └─────────────────────┘
 所有模型供应商模块 ────┘      │        所有文档加载/解析模块
 (open-ai/anthropic/ollama/…) │
     ▲                        │
     │                        │
 langchain4j（主模块：AiServices/ToolService/TokenStream；额外依赖 opennlp-tools、mutiny-zero）
      ▲    ▲    ▲    ▲
      │    │    │    └── langchain4j-agentic（多智能体）
      │    │    └─────── langchain4j-mcp（MCP 客户端，另依赖 http-client）
      │    └──────────── langchain4j-easy-rag（+tika 解析器）
      └────────────────── langchain4j-skills / langchain4j-agentic-a2a / spring-boot-starter …
```

真实依赖声明（摘自各模块 `pom.xml` 的 `<dependencies>`，已逐个验证；仅列编译期依赖，test 依赖已剔除）：

| 模块 | 直接依赖 |
|---|---|
| langchain4j-core | jackson-annotations、jackson-core、jackson-databind、slf4j-api、jspecify |
| langchain4j-http-client | langchain4j-core、slf4j-api |
| langchain4j（主模块） | langchain4j-core、opennlp-tools、slf4j-api、mutiny-zero |
| langchain4j-open-ai | langchain4j-core、langchain4j-http-client、langchain4j-reactive-streaming、jtokkit |
| langchain4j-mcp | langchain4j、langchain4j-http-client（默认绑定 jdk 实现） |
| langchain4j-agentic | langchain4j（仅此一个） |
| langchain4j-easy-rag | langchain4j、langchain4j-document-parser-apache-tika |

这张表本身就是一份架构说明：**core 被所有人依赖、自己只有三个外部依赖**（Jackson 做消息/工具参数的 JSON 编解码、SLF4J 做日志、JSpecify 做空安全标注）；**供应商模块只依赖 core + http-client**（1.0 起 HTTP 抽象统一，供应商不再各自捆绑 OkHttp/RestTemplate）；**主模块 `langchain4j` 额外只带 opennlp-tools**（句子检测，供切分器用）和 mutiny-zero（响应式流适配）；**agentic 只依赖主模块**——多智能体不是新内核，就是 AiServices 的一层编排糖。

## 1.5 关键问题 → LangChain4j 方案映射（全文导览）

| LLM 应用的关键问题 | LangChain4j 的方案 | 详见 |
|---|---|---|
| 换模型（OpenAI→Anthropic→本地 Ollama）要改业务代码 | `ChatModel` 统一接口 + 26 个供应商模块 + `default` 方法模板方法模式 | 第二章 |
| 想写接口不写实现，AI 能力像普通 Bean 一样被注入 | `AiServices` JDK 动态代理 + `@SystemMessage/@UserMessage` 注解 | 第三章 |
| 提示词散落在字符串拼接里，无法复用 | `PromptTemplate`（`{{变量}}`）+ `@V` 参数绑定 + SPI 可换模板引擎 | 第三章 |
| 模型输出是自由文本，下游代码没法用 | `ServiceOutputParser`：返回类型驱动，JSON Schema 原生结构化 / 提示词注入双路径 | 第三章 |
| 每次调用都阻塞一个线程，高并发扛不住 | 1.20 全链路非阻塞：返回 `CompletableFuture`/`Flow.Publisher` 即切换执行形态 | 第三章 |
| "模型思考→调工具→看结果→再思考"的循环谁来跑 | `ToolService.executeInferenceAndToolsLoop` while 循环（框架内部自动执行） | 第四章 |
| 工具运行时才知道（MCP 服务器、远程注册表） | `ToolProvider` 策略：每次推理前动态拉取工具列表 | 第四章、第九章 |
| 多轮对话的上下文谁来管 | `ChatMemory`（接口）+ `MessageWindowChatMemory`（滑动窗口）+ `ChatMemoryStore`（持久化） | 第五章 |
| 文档格式五花八门、长文档塞不进上下文 | `DocumentParser`/`DocumentSplitter`/`EmbeddingStoreIngestor` 摄取流水线 | 第六章 |
| 语义检索要接各家向量库，还要改写查询、重排结果 | `DefaultRetrievalAugmentor` 五段流水线（变换→路由→检索→聚合→注入） | 第六章 |
| 提示词注入、模型输出不合规格怎么拦 | `@InputGuardrails/@OutputGuardrails` 护栏体系 + `@Moderate` 内容审核 | 第七章 |
| 多个 AI 服务协作（顺序/并行/循环/主管派工） | `langchain4j-agentic`：`@Agent` 方法 + 七种工作流模式 + `AgenticScope` 共享状态 | 第八章 |
| 工具想要标准协议（MCP 生态） | `langchain4j-mcp` 客户端 + `McpToolProvider` | 第九章 |
| 框架能力锁死，定制要改源码 | 28 个 SPI 工厂 + 监听器全家桶 + 每段 RAG 可换实现 | 第九章 |
| Spring Boot 里怎么和 IoC 容器接线 | `langchain4j-spring`：`@AiService` 注解 + `AiServicesAutoConfig` 自动装配 | 第十章 |

## 1.6 版本演进：0.x → 1.0 → 1.22

写作时（2026 年 10 月）的版本格局：**1.22.0 为最新稳定版**（2026-10-08），主干已进入 1.23.0 快照；核心五件套（core/langchain4j/http-client/open-ai/bom）自 1.0.0 起保持稳定 API，其余模块在 1.x 内分批转正（每个 minor 版本发布时，非核心模块的版本号形如 `1.22.0-betaN`——这是官方的"分批稳定"策略）。本节日期均取自 GitHub Releases API（`api.github.com/repos/langchain4j/langchain4j/releases`），特性归属经各版本 release notes 逐条核对。

### 1.6.1 版本时间线与运行基线

| 版本 | 发布时间（GitHub 实证） | JDK | 一句话主题 |
|---|---|---|---|
| 0.1.0 ~ 0.4.0 | 2023-06-20（首批发布） | Java 8 | 蛮荒开拓期：单仓多模块起步，API 每周在变 |
| 0.25.0 | 2023-12-22 | Java 8 | 模块生态初成（按 `langchain4j-*` 命名铺开） |
| 0.36.2 | 2024-11-21 | Java 8 | 0.x 收官：`ChatLanguageModel`/`generate()` 时代 |
| 1.0.0-alpha1 | 2024-12-22 | Java 17 | 大重构开始：`generate()` → `chat(ChatRequest)` |
| 1.0.0-beta1~3 | 2025-02 ~ 2025-04 | Java 17 | **接口改名**：`ChatLanguageModel`→`ChatModel`（2025-04-15，PR #2864）、消息模型统一、HTTP 抽象落地 |
| **1.0.0** | **2025-05-14** | **Java 17** | **稳定版**：core/langchain4j/http-client/open-ai/bom 五件先行 GA，其余模块 1.0.0-beta5 |
| 1.1.0 | 2025-06-18 | 17 | **输入/输出护栏**（guardrails）；AI Service 可作为另一个 AI Service 的工具 |
| 1.2.0 | 2025-07-29 | 17 | 思考/推理（thinking）支持；流式部分工具调用；AI Service 可变换 `ChatRequest` |
| **1.3.0** | **2025-08-07** | 17 | **`langchain4j-agentic` + `agentic-a2a` 实验模块发布**（多智能体时代开启） |
| 1.4.0 | 2025-08-28 | 17 | agentic 声明式 API；MCP Streamable HTTP；工具并发执行；工具立即返回与错误处理器；AI Service 多模态 |
| 1.5.0 | 2025-09-16 | 17 | MCP 注册表客户端；agentic 异步化 |
| 1.7.1 | 2025-10-02 | 17 | （1.7 补丁线） |
| 1.8.0 | 2025-10-24 | 17 | 流式取消（StreamingHandle）；输出护栏可整条改写 AiMessage |
| 1.9.0 | 2025-11-26 | 17 | **通用 agentic 规划器**（所有模式重写其上）；MCP WebSocket |
| 1.10.0 | 2025-12-24 | 17 | 模型目录（ModelCatalog）；agentic 可观测性 |
| 1.11.0 | 2026-02-04 | 17 | agentic 流式与多模态；嵌入模型/存储/检索器监听器 |
| 1.12.1 | 2026-03-05 | 17 | **Agent Skills 与 Tool Search**；Micrometer 指标与 Observation API；MCP 工具作为非 AI 智能体 |
| 1.13.0 | 2026-04-09 | 17 | agentic 状态持久化/恢复；**Spring Boot 4 支持**；`ChatRequestOptions` |
| 1.14.0 | 2026-04-30 | 17 | 多态返回类型；OpenAI Responses API 成熟化；`IMMEDIATE_IF_LAST` |
| 1.15.0 | 2026-05-15 | 17 | `@Tool` 参数默认值；投票模式与模型选择；google-genai 新集成 |
| 1.16.0 | 2026-06-05 | 17 | `@Tool` 支持继承；黑板（Blackboard）模式；Batch API 统一 |
| 1.17.0 | 2026-06-26 | 17 | 辩论（Debate）模式；`@CompensateFor` 工具补偿；Oracle 聊天记忆 |
| 1.18.0 | 2026-07-17 | 17 | BDI 模式；崩溃可恢复的 HITL 挂起/恢复；EmbeddingModel 请求/响应式 API |
| 1.19.0 | 2026-08-14 | 17 | MCP 客户端跟进 2026-07-28 规范；agentic 系统级工具补偿 |
| **1.20.0** | **2026-09-04** | 17 | **全链路非阻塞/响应式**（`CompletableFuture`/`Flow.Publisher` 返回类型）；Jackson 3 可选支持 |
| **1.21.0** | **2026-10-02** | 17 | **决策模型（DecisionModel/DecisionServices）**：分类/路由/打分的新模型类型 |
| **1.22.0** | **2026-10-08** | 17 | OpenAI Decisions API、Bedrock 批处理、Anthropic 提示词缓存 |

### 1.6.2 关键特性引入对照表（release notes 为证）

| 特性 | 引入版本 | 说明 |
|---|---|---|
| `ChatLanguageModel` / `generate()` | 0.x 时代 | 1.0 起更名为 `ChatModel`/`chat(ChatRequest)`（PR #2864，2025-04-15） |
| **AiServices**（声明式接口代理） | 0.11（2023-07）期孵化 | 1.0 定型；从此成为框架主 API |
| `ChatRequest`/`ChatResponse` 显式参数对象 | 1.0.0-alpha1（2024-12） | 替代 `generate(List<String>)` 时代的松散参数 |
| `ChatModel`/`StreamingChatModel` 命名 | 1.0.0（beta 期落地） | 去掉 "Language"，与 `EmbeddingModel` 命名对齐 |
| HTTP 客户端抽象（langchain4j-http-client） | 1.0.0 | 供应商模块统一走 `HttpClient` SPI，jdk/apache/okhttp 三实现 |
| **输入/输出护栏**（@InputGuardrails/@OutputGuardrails） | 1.1.0（PR #2571） | 第七章 |
| AI Service 作为工具（AiService→Tool） | 1.1.0（PR #3095） | 多智能体的第一步 |
| 思考/推理内容（PartialThinking） | 1.2.0（PR #3380） | 流式推理内容回调 |
| 流式部分工具调用（PartialToolCall） | 1.2.0（PR #3303） | 参数边流边解析 |
| **langchain4j-agentic**（多智能体） | 1.3.0（PR #3458） | 第八章主角 |
| 工具并发执行 / 立即返回 / 错误处理器 | 1.4.0 | `executeToolsConcurrently`、`ReturnBehavior`、两类 ErrorHandler |
| MCP Streamable HTTP 传输 | 1.4.0 | 替代 SSE 传输 |
| 通用 agentic 规划器 | 1.9.0 | 全部工作流模式重写于其上 |
| **Agent Skills** / **Tool Search** | 1.12.1 | 技能包与按需加载工具（应对工具过多） |
| agentic 状态持久化与恢复 | 1.13.0 | 崩溃/重启后恢复执行 |
| 非阻塞/响应式（全链路） | 1.20.0（PR #5527） | `CompletableFuture`/`Flow.Publisher` 返回类型 |
| Jackson 3 可选支持 | 1.20.0 | 加 `langchain4j-jackson3` 依赖即切换 |
| **DecisionModel / DecisionServices** | 1.21.0 | 分类/路由/打分专用模型类型 + 接口式声明 |

### 1.6.3 三大阶段主题对比

| 维度 | 0.x（2023~2024.11） | 1.0~1.2（2025.05~07） | 1.3~1.22（2025.08~） |
|---|---|---|---|
| 主 API | `ChatLanguageModel.generate()` + 手工拼消息 | `AiServices` + `ChatModel.chat(ChatRequest)` | 同左 + **agentic 工作流**成为第二主 API |
| 运行基线 | Java 8 | Java 17 | Java 17 |
| 请求模型 | 松散方法参数 | `ChatRequest`/`ChatRequestParameters` 显式对象 | 不变，参数对象持续扩容 |
| 工具 | @Tool 基础循环 | 同左 | 并发执行、立即返回、错误处理、补偿、Tool Search |
| 阻塞模型 | 全阻塞 | 全阻塞 | **非阻塞/响应式全链路（1.20）** |
| 多智能体 | 无 | AI Service 可当工具用（1.1） | 专用 agentic 模块（1.3+）+ 七种模式 |
| 协议生态 | 无 | MCP 客户端（SSE） | MCP Streamable HTTP/WebSocket、A2A、Skills |
| JSON | Jackson 2 | Jackson 2 | Jackson 2 默认，Jackson 3 可选（1.20） |

### 1.6.4 对初学者的意义：哪些知识过时了，哪些永远有效

- **新项目不要再用**：`ChatLanguageModel`/`StreamingChatLanguageModel`（已改名）、`generate(...)` 方法族、`Response<T>` 包装返回（遗留兼容）、`langchain4j` 主模块里的 `chain` 包（`ConversationalChain`，功能被 AiServices 完全覆盖）。搜教程时看到 `generate(` 和 `ChatLanguageModel` 说明教材是 0.x 时代的，方法名换成 `chat(` 即可对照阅读，思想未变。
- **0.x 教程里永久有效的部分**：AiServices 声明式模型、`@Tool` 机制、`ChatMemory` 语义、RAG 五段流水线的接口契约——这些从 0.x 到 1.22 概念上未变，只是类名/方法名换了。
- **怎么判断教材新旧**：看 import。`dev.langchain4j.model.chat.ChatModel`（1.0+）还是 `ChatLanguageModel`（0.x）；`langchain4j-agentic`（1.3+）的出现说明是 2025-08 之后的教材。

## 1.7 全文章节地图

- **第二章 模型抽象与消息模型（langchain4j-core 地基）**：`ChatModel` 的模板方法设计、`ChatRequest`/`ChatResponse` 参数对象、消息与多模态内容块、流式回调、HTTP 客户端抽象、其余模型接口族。
- **第三章 AiServices：框架的心脏**：动态代理如何生成、一次声式调用从注解解析到输出解析的完整生命周期、注解家族、返回类型全家桶、非阻塞与流式。
- **第四章 Tools：工具调用循环**：`@Tool` 反射推导规范、`DefaultToolExecutor` 反射调用、`ToolService` while 循环逐行解析、并发/立即返回/错误处理/补偿。
- **第五章 Memory：对话记忆**：`ChatMemory`/`ChatMemoryStore` 两层抽象、滑动窗口实现、多用户隔离。
- **第六章 RAG：检索增强流水线**：摄取与检索两阶段、`DefaultRetrievalAugmentor` 五段流水线逐段解析、向量库生态。
- **第七章 护栏与审核**：`@InputGuardrails/@OutputGuardrails`、护栏执行器与重试/改写语义、`@Moderate` 内容审核。
- **第八章 Agentic：多智能体编排**：`@Agent` 注解、七种工作流模式、`AgenticScope` 共享状态与持久化、声明式与编程式 API。
- **第九章 MCP、Skills 与可观测性**：MCP 客户端与 `McpToolProvider`、Agent Skills、监听器体系与 28 个 SPI。
- **第十章 Spring Boot 集成**：`@AiService` 自动装配源码、starter 全景、与 Spring AI 对比。
- **第十一章 贯通视图**：把"一次声明式调用""一次 RAG 问答""一次多智能体执行"三条时间线叠加成全景图。
- **第十二章 附录**：关键接口速查表、初学者学习路线、Spring/Python-LangChain 概念对照表。

---

# 二、模型抽象与消息模型：langchain4j-core 的地基

> 本章对应源码：`langchain4j-core/src/main/java/dev/langchain4j/`。以下所有【源码证据】中的行号均为实际读取 1.23.0-beta33-SNAPSHOT 快照所得。

## 2.1 模块定位与包结构

用大白话说：**langchain4j-core 就是这个框架的 `spring-core`**——它不负责"组装应用"（那是主模块 AiServices 的事），只负责定义"一个 LLM 应用里所有可插拔的东西"的接口契约：模型怎么调、消息长什么样、向量怎么算、记忆怎么存、RAG 各环节怎么接。core 包结构（实测目录）：

```
dev.langchain4j
├── agent/tool/          # Tool 注解与工具规范（@Tool、ToolSpecification、ReturnBehavior、CompensateFor）
├── data/                # 数据模型：message/（消息）、document/（文档）、embedding/、image/、audio/、pdf/、segment/
├── guardrail/           # 护栏接口：Guardrail、InputGuardrail、OutputGuardrail、执行器与请求/结果对象
├── memory/              # ChatMemory 接口
├── model/               # 模型接口族：chat/、embedding/、moderation/、scoring/、image/、batch/、decision/、catalog/
├── observability/       # 可观测 API 与事件（ai services 事件、各组件 listener）
├── rag/                 # RAG 流水线接口：RetrievalAugmentor、QueryTransformer/Router、ContentRetriever/Aggregator/Injector
├── spi/                 # 28 个 SPI 工厂接口（模板引擎、JSON 编解码、线程池、适配器…）
├── store/               # embedding/（EmbeddingStore）、memory/（ChatMemoryStore）
└── web/search/          # WebSearchEngine 接口
```

一个值得注意的事实：**RAG 五段流水线、护栏接口、`ChatMemory` 都住在 core 里**，而不在主模块——这意味着任何一个集成模块（比如某向量库的适配器）都可以只依赖 core 就实现完整功能，不必拖上 AiServices 这套重机器。这与 Spring 把 `@Transactional` 语义放在 spring-tx、而装配放在 spring-context 的分层是同一个道理。

## 2.2 ChatModel：一个接口撑起整个框架

**先白话**：`ChatModel` 是"会聊天的大模型"的抽象。整个框架——AiServices、工具循环、RAG、记忆——最终都会收敛为一次对 `ChatModel.chat(ChatRequest)` 的调用。它采用**模板方法模式**：公开入口全是 `default` 方法（负责监听器分发、参数合并、便捷重载），真正必须实现的只有两个 SPI 钩子：同步的 `doChat` 和（可选的）异步 `doChatAsync`。

【源码证据】`langchain4j-core/src/main/java/dev/langchain4j/model/chat/ChatModel.java`

```java
public interface ChatModel {                                     // :34

    // 主入口：合并模型默认参数 → 构建 finalRequest → 分发监听器 → 调 doChat
    default ChatResponse chat(ChatRequest chatRequest, ChatRequestOptions options) {  // :54
        ChatRequest finalChatRequest = ChatRequest.builder()
                .messages(chatRequest.messages())
                .parameters(defaultRequestParameters().overrideWith(chatRequest.parameters()))  // :60
                .build();
        List<ChatModelListener> listeners = listeners();         // :63
        onRequest(finalChatRequest, provider(), attributes, listeners);   // :66  onRequest
        try {
            ChatResponse chatResponse = doChat(finalChatRequest);// :68  ← 唯一的 SPI 钩子
            onResponse(chatResponse, ...);                        // :69  onResponse
            return chatResponse;
        } catch (Exception error) {
            onError(error, ...);                                  // :72  onError
            throw error;
        }
    }
    default ChatResponse doChat(ChatRequest chatRequest) {        // :77
        throw new RuntimeException("Not implemented");
    }
    // 1.20 新增：非阻塞钩子，默认抛 AsyncNotSupportedException（不支持则同步路径兜底）
    @Experimental
    default CompletableFuture<ChatResponse> doChatAsync(ChatRequest chatRequest) {  // :170
        return AsyncNotSupported.failedFuture(getClass(), "doChatAsync");
    }
    default String chat(String userMessage) { ... }               // :186 最便捷的重载
}
```

三个设计要点：

1. **监听器内建在接口层，供应商零成本获得可观测性**。`onRequest/onResponse/onError` 三阶段分发写在 default 方法里（`ChatModelListenerUtils`），供应商模块只管实现 `doChat` 的 HTTP 调用，完全不感知监听体系的存在。对比 Spring：这就是"框架把横切逻辑织进接口默认实现"，供应商相当于只写"业务代码"。
2. **参数合并的优先级规则**：`defaultRequestParameters().overrideWith(chatRequest.parameters())`（`:60`）——模型自带的默认参数为底座，调用方的显式参数逐项覆盖。`ChatRequestParameters` 是参数对象族（模型名/温度/最大输出 token/工具选择/响应格式……），各供应商可扩展子类型（如 OpenAI 的自定义参数，1.2.0 引入）。
3. **同步与异步是两条平行通道，不是一条的阻塞版**。`chatAsync`（`:106`，1.20.0 引入，`@Experimental`）走 `doChatAsync` 钩子，异常经 `whenComplete` 分发 `onError`，取消信号用 `propagateCancellation` 双向传播（`:153`）。不支持原生异步的模型返回 `AsyncNotSupportedException` 失败 future，由上层框架决定"卸载到线程池"还是"快速失败"——**绝不静默地把阻塞调用伪装成异步**（这是它和很多"伪异步 SDK"的本质区别）。

**供应商模块做什么**：以 `langchain4j-open-ai` 为例，`OpenAiChatModel` 继承 `Builder` 模式配置（baseUrl/apiKey/modelName…），实现 `doChat`：把 `ChatRequest` 翻译成 OpenAI 的 JSON 协议、经 `langchain4j-http-client` 抽象发出请求、把响应翻译回 `ChatResponse`。翻译层就是各供应商模块的全部工作——1.22 时代每个供应商模块还额外实现了 Batch API、TTS、转录等扩展模型类，但核心链路不变。

## 2.3 ChatRequest / ChatResponse：显式参数对象

**先白话**：0.x 时代调模型是 `model.generate("你好")`，参数散在方法签名里，每加一个能力就加一个重载，撑爆了接口。1.0 改成**两个显式对象**：`ChatRequest`（输入侧）与 `ChatResponse`（输出侧），扩展能力变成往对象里加字段，接口签名从此冻结。

【源码证据】`langchain4j-core/src/main/java/dev/langchain4j/model/chat/request/ChatRequest.java`

```java
public List<ChatMessage> messages() { ... }        // :57 消息列表
public ChatRequestParameters parameters() { ... }  // :61 模型/温度/工具/响应格式等参数
public static Builder builder() { ... }            // :147
```

`ChatResponse`（`model/chat/response/ChatResponse.java`）对称地持有 `aiMessage()`（模型输出）与 `metadata()`（token 用量、finishReason、原始响应引用）。元数据设计借鉴了 Spring 的 `MutablePropertySources` 思想：标准字段强类型，供应商私有数据放 metadata 的 map 里。

**工具相关字段也在参数对象里**：`ToolChoice`（AUTO/REQUIRED/FORCED 之一）决定模型是否必须调工具；`ResponseFormat`（`request/ResponseFormat.java`，JSON/TEXT 两态）承载结构化输出要求——第六处会看到 AiServices 如何根据返回类型自动生成 `JsonSchema` 塞进来。

## 2.4 消息模型与多模态内容块

**先白话**：一次对话就是一组消息：系统设定（`SystemMessage`）、用户输入（`UserMessage`）、模型回复（`AiMessage`）、工具结果（`ToolExecutionResultMessage`）。多模态能力靠**内容块**（Content）实现：一条 `UserMessage` 里可以混排文本、图片、音频、视频、PDF。

【源码证据】`langchain4j-core/src/main/java/dev/langchain4j/data/message/`（实测目录）

```
ChatMessage（标记接口）—— ChatMessageType 枚举区分五种类型
├── SystemMessage                  # 系统设定
├── UserMessage                    # 用户消息：List<Content> 组成
│     └── Content —— TextContent / ImageContent / AudioContent /
│                     VideoContent / PdfFileContent / CustomContent（:ContentType）
├── AiMessage                      # 模型消息：text + List<ToolExecutionRequest>（工具调用请求）
└── ToolExecutionResultMessage     # 工具执行结果（id 配对回模型）
+ ChatMessageSerializer / ChatMessageJsonCodec（Jackson 编解码，SPI 可替换）
+ CustomMessage（供应商私有消息类型的逃生口）
```

两个关键点：

1. **`AiMessage` 同时携带文本与工具调用请求**——模型可以"边说话边调工具"，工具循环（第四章）正是靠 `aiMessage.hasToolExecutionRequests()` 判断是否继续循环。
2. **序列化走 SPI**：`ChatMessageJsonCodecFactory` 是一个 SPI，默认 Jackson 实现；加了 `langchain4j-jackson3` 依赖（1.20）会整体切换到 Jackson 3——**消息模型是 JSON 持久化友好的一等公民**，这也是 agentic 状态持久化（1.13）和记忆存储（第五章）能落地的基础。

## 2.5 流式：StreamingChatModel 与回调族

**先白话**：打字机效果需要流式。`StreamingChatModel` 与 `ChatModel` 平行（不是继承关系），回调式 API：你给一个 `StreamingChatResponseHandler`，模型边生成边推事件。

【源码证据】`langchain4j-core/src/main/java/dev/langchain4j/model/chat/StreamingChatModel.java`

```java
default void chat(ChatRequest request, StreamingChatResponseHandler handler) { ... }  // :42
default void chat(ChatRequest request, ChatRequestOptions options,
                  StreamingChatResponseHandler handler) { ... }                       // :54
// SPI 钩子：doStreamingChat（供应商实现 SSE/流式协议的翻译）
default void chat(String userMessage, StreamingChatResponseHandler handler) { ... }   // :275 便捷重载
```

【源码证据】`langchain4j-core/src/main/java/dev/langchain4j/model/chat/response/StreamingChatResponseHandler.java`

```java
default void onPartialResponse(String partialResponse) {}                  // :25 文本增量
default void onPartialThinking(PartialThinking partialThinking) {}         // :61 推理内容增量（1.2）
default void onPartialToolCall(PartialToolCall partialToolCall) {}         // :117 工具调用参数增量（1.2）
void onCompleteResponse(ChatResponse completeResponse);                    // :197 终态
void onError(Throwable error);                                             // :204
```

三个进阶点：**PartialToolCall**（1.2.0）让前端可以在模型还没说完参数 JSON 时就开始渲染工具调用进度；**RawStreamingEvent**（`response/DefaultRawStreamingEvent.java`）把供应商未映射的原始事件透传出来（1.17.0），保证抽象层不丢信息；**StreamingHandle**（1.8.0）支持中途取消——注意回调式 API 在 1.20 之后有了响应式对手（`Flow.Publisher<AiServiceStreamingEvent>`，见 3.6 节），两者长期并存，前者适合手工消费，后者适合组合编排。

## 2.6 HTTP 客户端抽象与模型接口族

**HTTP 抽象**：`langchain4j-http-client` 模块定义 `HttpClient` 接口（builder 化配置：超时、代理、日志、鉴权拦截），提供 jdk/apache/okhttp 三个实现（`http-clients/` 目录）。所有供应商模块的 HTTP 调用都走这层抽象（1.0 引入），换实现只需换一个依赖——解决的是"供应商模块各自捆绑 HTTP 库导致依赖地狱"的历史问题。Spring Boot starter 里还有 `langchain4j-http-client-spring-restclient`，直接复用 Spring 管理的 RestClient。

**模型接口族**（core 中除 `ChatModel` 外的兄弟接口，全部同构：default 入口 + SPI 钩子 + 监听器）：

| 接口 | 路径 | 干什么 | 备注 |
|---|---|---|---|
| `EmbeddingModel` | `model/embedding/` | 文本→向量 | `embed(String)`（:350）`embedAll(List)`（:387）；1.18 改为请求/响应式 API，支持多模态输入与逐调用参数 |
| `ModerationModel` | `model/moderation/` | 内容审核 | `moderate(TextSegment)`；接口在 `:26` |
| `ScoringModel` | `model/scoring/` | 查询-文档相关性打分（重排） | RAG 聚合段用它（6.3 节）；`:25` |
| `ImageModel` | `model/image/` | 文生图 | `:11` |
| `BatchChatModel` | `model/batch/` | 批量离线推理 | 1.16 统一响应模型；OpenAI/Anthropic/Bedrock/Gemini 各有实现 |
| `DecisionModel` | `model/decision/` | **决策模型**（1.21 实验性）：带概率的类型化问答 | `:43`；配 `DecisionServices` 接口式声明（3.5 节） |
| `ModelCatalog` | `model/catalog/` | 模型能力档案目录 | 1.10.0 |
| `WebSearchEngine` | `web/search/` | 网络搜索 | RAG 检索段可接它（6.3 节） |

这个家族表就是 LangChain4j 的"能力地图"：**一切外部 AI 能力 = core 里一个接口 + N 个供应商模块**。你不需要全学，用到哪个查哪个；但都共享同一套"模板方法 + 监听器 + 参数对象"的设计语法，会一个等于会一串。

## 2.7 本章小结

- `langchain4j-core` 是接口契约层，运行时仅依赖 Jackson+SLF4J+JSpecify；RAG、护栏、记忆的接口都在这层，供应商模块可以"轻装"实现。
- `ChatModel` 用模板方法 + default 方法实现了 Java 版的"部分抽象"：供应商只写 `doChat`，监听器、参数合并、便捷重载由接口层统一承担；1.20 起有平行的异步通道 `chatAsync/doChatAsync`。
- `ChatRequest`/`ChatResponse` 显式参数对象冻结了接口签名；消息模型以内容块支持多模态；流式有回调式（handler）与响应式（1.20 Publisher）两种消费形态。
- 模型接口族（Embedding/Moderation/Scoring/Image/Batch/Decision/WebSearch）共享同一设计语法——理解一个等于理解全部。

---
# 三、AiServices：声明式 AI 服务（框架的心脏）

> 本章对应源码：`langchain4j/src/main/java/dev/langchain4j/service/`。这是全框架最核心的一章——理解了 AiServices，就理解了 LangChain4j 与 Spring 共享的那套"注解 + 动态代理"世界观。

## 3.1 模块定位与包结构

用大白话说：**主模块 `langchain4j` 的 `service` 包就是"把一次 LLM 交互的全部套路固化成一条流水线"的地方**。你写一个接口，它给你一个代理；代理在运行时把"提示词模板渲染 → RAG 检索增强 → 输入护栏 → 组装消息 → 内容审核 → 调模型 → 工具循环 → 输出解析 → 写记忆 → 输出护栏"十件事按固定顺序做完。这是典型的"模板方法上移到框架，业务差异下沉到注解与策略接口"。

```
dev.langchain4j.service/
├── AiServices.java            # 流式 Builder（装配入口，1369 行）
├── DefaultAiServices.java     # 代理生成 + 调用流水线（1848 行，本章主角）
├── AiServiceContext.java      # 一次装配的全部组件（chatModel/memory/tools/RAG/guardrails…）
├── SystemMessage/UserMessage/V/MemoryId/UserName/Result/TokenStream  # 注解与返回类型
├── tool/                      # ToolService 工具循环（第四章）
├── output/                    # ServiceOutputParser 与 OutputParser 族
├── guardrail/                 # GuardrailService（第七章）
├── memory/                    # ChatMemoryService（第五章）
├── decision/                  # DecisionServices（1.21 决策服务的 AiServices 同构体）
└── spi/                       # TokenStreamAdapter / CompletableFutureAdapter / PublisherAdapter
```

## 3.2 一个最小例子与装配入口

**先看用法**（这是理解后文所有源码的锚点）：

```java
interface Assistant {
    @SystemMessage("你是一个乐于助人的助手")
    String chat(@UserMessage String message);
}

Assistant assistant = AiServices.builder(Assistant.class)
        .chatModel(model)        // ChatModel（第二章）
        .chatMemory(MessageWindowChatMemory.withMaxMessages(10))  // 第五章
        .tools(new StockTools()) // @Tool 方法所在对象（第四章）
        .contentRetriever(retriever)  // RAG 检索器（第六章）
        .build();                // 返回 Assistant 的动态代理

String answer = assistant.chat("杭州明天多少度？该穿什么？");
```

Builder 方法面（实测 `AiServices.java`，节选）：

| Builder 方法 | 行号 | 干什么 |
|---|---|---|
| `chatModel(ChatModel)` / `streamingChatModel(...)` | :242 / :257 | 必选其一，底层模型 |
| `systemMessage(String)` / `systemMessageProvider(Function)` | :273 / :293 | 全局系统提示词（按 memoryId 供给） |
| `chatMemory(ChatMemory)` / `chatMemoryProvider(...)` | :418 / :445 | 记忆（单实例 or 按 id 多实例） |
| `tools(Object...)` / `toolProvider(ToolProvider)` | :504 / :536 | 静态工具对象 / 动态工具供给器 |
| `executeToolsConcurrently()` | :662 | 多工具并发执行（1.4.0） |
| `maxToolCallingRoundTrips(int)` | :706 | 工具循环轮次上限（防失控） |
| `beforeToolExecution` / `afterToolExecution` | :747 / :760 | 工具前后钩子 |
| `toolArgumentsErrorHandler` / `toolExecutionErrorHandler` | :822 / :875 | 两类工具错误处理器（1.4.0） |
| `contentRetriever(ContentRetriever)` / `retrievalAugmentor(...)` | :921 / :938 | RAG 的"简装"与"全装"入口 |
| `moderationModel(ModerationModel)` | :490 | 内容审核 |
| `inputGuardrailsConfig` / `outputGuardrailsConfig` | :1023 / :1034 | 护栏配置（配合注解，第七章） |
| `registerListeners(AiServiceListener...)` | :965 | AI Service 级事件监听 |
| `compensateOnToolErrors(boolean)` | :1328 | 工具失败时执行补偿动作（1.17+） |

`AiServices` 是抽象类，`builder()` 返回 `DefaultAiServices`；还有一个 SPI `AiServicesFactory` 可整体替换实现（Quarkus 等集成方利用它）。

## 3.3 动态代理的诞生：build() 与 InvocationHandler

**先白话**：`build()` 做三件事——校验配置、生成 JDK 动态代理、把代理绑到你的接口上。此后每次调用接口方法，都会进入同一个 `InvocationHandler`。

【源码证据】`langchain4j/src/main/java/dev/langchain4j/service/DefaultAiServices.java`

```java
public T build() {
    validate();                                   // :198 校验：模型能力 vs 接口要求
    Object proxyInstance = Proxy.newProxyInstance(  // :205 JDK 动态代理
            context.aiServiceClass.getClassLoader(),
            new Class<?>[] {context.aiServiceClass},
            new InvocationHandler() {
                public Object invoke(Object proxy, Method method, Object[] args) {
                    if (!isImplementedByModel(method)) {          // :212 非模型方法走旁路
                        return invokeImplementedByProxy(proxy, method, args);  // :128
                    }
                    validateParameters(context.aiServiceClass, method);    // :217
                    InvocationContext invocationContext = InvocationContext.builder()  // :223
                            .invocationId(UUID.randomUUID())
                            .chatMemoryId(findMemoryId(method, args).orElse(DEFAULT))
                            .defaultRequestParameters(determineChatRequestParameters(context))
                            .managedParameters(LangChain4jManaged.current())
                            .timestampNow()
                            .build();
                    return invoke(method, args, withCapturedContext);      // :238 主流水线
                }
            });
    ...
}
```

三个关键分流：

1. **`isImplementedByModel(method)`**（`:212`）：方法体里"有注解标记要调模型"的才进流水线。default 方法、接口自带的 `ChatMemoryAccess` 方法（`getChatMemory`/`evictChatMemory`，`:141-155` 直接读写记忆，便于外部旁路操作）、`Object` 方法由代理自己实现。**接口可以混装"AI 方法"与"普通管理方法"**——这在 Spring Data Repository 里有同款设计（`findById` 走查询、继承的 `CrudRepository` 基础方法另算）。
2. **`InvocationContext`**（`:223-234`）：一次调用的全程档案——invocationId、接口/方法名、参数、memoryId、模型供应商、调用参数、线程上下文。它贯穿后面所有环节（RAG 元数据、护栏参数、事件发布、可观测性），相当于 Spring 的 `RequestContext`。
3. **错误也走返回值语义**（`:244-249`）：异步/响应式方法在流水线前段失败时，不抛异常，而是返回"已失败的 future/立即失败的 Publisher"，符合 Reactive Streams 合约（onSubscribe 必须先于 onError）。

## 3.4 一次调用的完整生命周期（同步路径逐段解析）

【源码证据】`DefaultAiServices.java:674-790`（同步路径；异步/响应式路径在 `:421-672` 做同样的事，只是每步换成 future 组合）。十步流水线：

```
①RAG增强 → ②拼内容 → ③输入护栏 → ④追加输出格式 → ⑤组装消息 → ⑥审核
   → ⑦工具上下文 → ⑧调模型 → ⑨工具循环 → ⑩输出解析
```

```java
// ① RAG：若配了 retrievalAugmentor，检索增强用户消息（第六章五段流水线）
AugmentationResult augmentationResult = context.retrievalAugmentor.augment(augmentationRequest);  // :688
userMessageForAugmentation = (UserMessage) augmentationResult.chatMessage();                      // :689

// ② 多模态：把方法参数里的 Image/Pdf 等转成内容块拼进 UserMessage
UserMessage userMessage = addContentsToUserMessage(method, args, userMessageForAugmentation);     // :692

// ③ 输入护栏：@InputGuardrails 声明的护栏链（第七章）；失败抛 GuardrailException 终止
userMessage = invokeInputGuardrails(context.guardrailService(), method, userMessage, ...);        // :703

// ④ 结构化输出：非 String 返回类型时，把 "You must answer strictly..." 追加进提示词
if (appendOutputFormat) userMessage = appendOutputFormatInstructions(returnType, userMessage);    // :705

// ⑤ 组装消息：记忆历史 + 系统消息 + 本轮用户消息
List<ChatMessage> messages = assembleMessages(chatMemory, systemMessage, userMessage, ...);       // :712

// ⑥ 审核模型异步触发（@Moderate），不阻塞主流程
CompletableFuture<Moderation> moderationFuture = triggerModerationIfNeeded(...);                  // :715

// ⑦ 工具上下文：从工具配置推导 ToolExecutors/工具选择/响应格式
ToolServiceContext toolServiceContext = context.toolService.createContext(invocationContext, ...);// :719

// ⑧ 发起调用（chatRequestTransformer 是 1.2 加的最终变换口子）
ChatRequest chatRequest = context.chatRequestTransformer.apply(
        ChatRequest.builder().messages(messages).parameters(parameters).build(), memoryId);       // :747
ChatResponse chatResponse = chatExecutor.execute();                                               // :760

// ⑨ 工具循环：模型要调工具就反复"执行→回灌→再调模型"（第四章逐行解析）
ToolServiceResult toolServiceResult = context.toolService.executeInferenceAndToolsLoop(...);      // :770

// ⑩ 输出解析 + 写记忆 + 输出护栏，最终按方法返回类型变换
return processToolServiceResult(method, invocationContext, returnType, ...);                      // :781
```

**记忆写入在哪里？** 隐藏在 ⑤ 和 ⑨：⑤ 的 `assembleMessages` 从 `chatMemory.messages()` 读历史；⑨ 循环里每轮把 `AiMessage` 和工具结果写回 `chatMemory.add(...)`。读与写分居流水线两端，中间的任何环节都无需感知记忆的存在。

**流式分支**（`:721-742`）：方法返回 `TokenStream` 时，不执行 ⑧⑨，而是把上下文打包成 `AiServiceTokenStream` 立即返回——真正的执行被推迟到用户调用 `tokenStream.start()`。这正是"冷流"语义：`TokenStream` 是可订阅的执行描述，不是进行中的执行。

## 3.5 注解家族与提示词模板

| 注解 | 位置 | 作用 |
|---|---|---|
| `@SystemMessage` | 方法/接口 | 系统提示词模板（支持 `{{变量}}`） |
| `@UserMessage` | 方法参数/方法 | 用户消息模板；`@UserMessage String message` 表示参数即全文 |
| `@V` | 参数 | 模板变量绑定（`@V("city") String city` → `{{city}}`） |
| `@MemoryId` | 参数 | 多用户记忆隔离的 id（第五章） |
| `@UserName` | 参数 | 用户名（附进消息元数据） |
| `@Moderate` | 方法 | 该方法启用审核模型（第七章） |
| `@Result` | 返回类型 `Result<T>` | 携带 token 用量/检索内容/中间响应的富返回（泛型 T 是真正业务类型） |

**模板引擎**：`{{变量}}` 语法由 `PromptTemplate` 实现（`langchain4j-core/.../model/input/PromptTemplate.java`），内建 `{{current_date}}`/`{{current_time}}`/`{{current_date_time}}` 特殊变量（`:14-17`）。引擎本身是 SPI（`PromptTemplateFactory`，`:19-23`）——源码注释明确说 Quarkus 会在 GraalVM 原生镜像里替换它。**FreeMarker 风格的复杂模板**可以通过 SPI 换 `freemarker` 实现（官方文档有专节），框架默认实现保持零依赖。

**变量解析的实现**：`InternalReflectionVariableResolver`（`service/` 下）在运行时用反射读取方法参数注解，把 `@V`/`@UserMessage` 值按名或按位（`{{it}}` 占位单参数）填进模板——这是纯反射的运行时方案，Spring 用注解处理器生成代码的思路在这里没有对应物，因为 LangChain4j 不要求编译期增强。

## 3.6 返回类型全家桶与结构化输出

**先白话**：方法声明什么返回类型，框架就负责把模型的输出变成那个类型。这是"声明式"哲学的另一半——输入靠注解，输出靠返回类型。

【源码证据】`langchain4j/src/main/java/dev/langchain4j/service/output/ServiceOutputParser.java`

```java
public Object parse(ChatResponse chatResponse, Type returnType) {     // :33
    if (typeHasRawClass(returnType, Result.class)) {                   // :35 Result<T> 剥壳
        returnType = resolveFirstGenericParameterType(returnType);
    }
    ...
    return parseText(returnType, rawClass, aiMessage.text());          // :56
}
private Object parseText(Type returnType, Class<?> rawClass, String text) {  // :63
    if (rawClass == String.class) return text;
    OutputParser<?> outputParser = outputParserFactory.get(rawClass, typeArgumentClass);  // :69
    return outputParser.parse(text);
}
public String outputFormatInstructions(Type returnType) {              // :94 提示词注入路径
    ...
    return "\nYou must answer strictly in the following format: " + formatInstructions;
}
```

**双路径结构化输出**——同一个返回类型，两条到达路径，框架自动择优：

1. **原生 JSON Schema 路径**：`jsonSchema(returnType)`（`:73`）从返回类型生成 `JsonSchema` 元素，塞进 `ChatRequest` 的 `ResponseFormat`（1.4.0 起还支持 raw JSON schema 元素直传）。前提是模型支持（`Capability.RESPONSE_FORMAT_JSON_SCHEMA`，`DefaultAiServices.java:12` 有能力校验）。此时模型按 schema 严格输出 JSON，`OutputParser` 只做反序列化。
2. **提示词注入路径**（兜底）：模型不支持 JSON 模式时，把 `outputFormatInstructions`（`:94`）追加到用户消息（3.4 节第 ④ 步），靠"命令"让模型按格式回答，`OutputParser` 容错解析。

**返回类型清单**（`DefaultOutputParserFactory` + `service/output/` 目录实测）：`String`、`AiMessage`、`Result<T>`（富返回）、`Response<T>`（遗留）、`enum`、`boolean/int/long/float/double/...`（基本类型解析器族）、`Date/LocalDate/LocalDateTime`、`POJO`（`PojoOutputParser`，JSON 反序列化到任意类）、`List/Set/Collection` 与它们的枚举/POJO 变体、`Map`、`TokenStream`（流式）、`CompletableFuture<T>`/`CompletionStage<T>`（1.20）、`Flow.Publisher<...>`（1.20）。

**富返回 `Result<T>`**（`service/Result.java`）：`.content()` 拿业务值，还可拿 `tokenUsage()`/`sources()`（RAG 引用）/`intermediateResponses()`（1.2 起的中间 ChatResponse）/`finalResponse()`——调试 RAG 与多轮工具调用时的利器。

**DecisionServices**（1.21，`service/decision/`）：把同一套"接口代理"思想复制到决策模型——接口方法返回 `boolean`/`enum`/`Choice<E>`/record，由决策模型给出概率化答案（`:Decide.java` 注解标记提问方法）。这是 AiServices 的同构镜像，印证了"接口代理"是该框架的通用编程模型。

## 3.7 非阻塞与响应式（1.20 的全链路改造）

**先白话**：1.20 之前，AI Service 方法从头阻塞到尾（模型 I/O、工具执行、记忆 I/O、护栏全在调用线程上）。1.20 让**返回类型决定执行形态**：返回 `CompletableFuture<T>` 走异步，返回 `Flow.Publisher<AiServiceStreamingEvent>`（或 `Flow.Publisher<String>`）走响应式，接口定义一字不改。

【源码证据】`DefaultAiServices.java:179-195` 的形态判定与 SPI 适配：

```java
private boolean isAsynchronousOrReactive(Type returnType) {           // :179
    return typeHasRawClass(returnType, CompletableFuture.class)
            || typeHasRawClass(returnType, CompletionStage.class)
            || typeHasRawClass(returnType, Flow.Publisher.class)
            || findCompletableFutureAdapter(returnType) != null       // SPI 扩展点
            || findPublisherAdapter(returnType) != null;
}
```

工程上最值得注意的是**"非阻塞必须全链路"**的自觉：只要任何一层是阻塞的，整条链就退化为阻塞。所以 1.20 同步给 `ChatModel.doChatAsync`、`ChatMemory.addAsync/messagesAsync`、`ContentRetriever.retrieveAsync`、`EmbeddingStore.searchAsync`、`EmbeddingModel`、`ScoringModel`、`WebSearchEngine` 全部补了异步钩子（第二章/第五/六章已见），默认实现全部返回 `AsyncNotSupportedException` 失败 future 而非偷偷切线程——把选择权显式交给上层（`DefaultRetrievalAugmentor` 的 `offloadBlocking` 开关，6.3 节）。

**适配器 SPI**（`service/spi/`）：`CompletableFutureAdapter`/`PublisherAdapter` 让非 JDK 类型也能当返回类型——`langchain4j-reactor` 模块（Spring 仓库）正是用它支持 `Mono<T>`/`Flux<String>`；Kotlin 的 `langchain4j-kotlin` 同理。Reactor 用户的代码于是完全 Spring 风格：`Flux<String> chat(...)`。

## 3.8 TokenStream：流式的回调 DSL

【源码证据】`langchain4j/src/main/java/dev/langchain4j/service/TokenStream.java`

```java
public interface TokenStream {                                            // :26
    TokenStream onPartialResponse(Consumer<String> partialResponseHandler);   // :39
    TokenStream onPartialThinking(Consumer<PartialThinking> ...);             // :71
    TokenStream beforeToolExecution(Consumer<BeforeToolExecution> ...);       // :159
    TokenStream onToolExecuted(Consumer<ToolExecution> toolExecuteHandler);   // :194
    TokenStream onCompleteResponse(Consumer<ChatResponse> ...);               // :208
    TokenStream onError(Consumer<Throwable> errorHandler);                    // :216
    void start();                                                             // :230 冷启动
}
```

这是一个**链式回调 DSL**：`onPartialResponse(...).onCompleteResponse(...).start()`。`AiServiceTokenStream`（`service/AiServiceTokenStream.java`，307 行）是它在 AI Service 场景的实现——把 3.4 节的流水线延迟执行，并在流式中穿插工具循环事件（`onToolExecuted` 能收到每一轮工具执行）。1.8.0 起支持取消（`StreamingHandle`/`CancellationUnsupportedStreamingHandle`），1.13 起事件携带上下文（`withContext` 变体）。注意 TokenStream 是**AI Service 专用**的上层封装；直接用 `StreamingChatModel` 时拿到的是裸回调（2.5 节）。

## 3.9 本章小结

- AiServices = JDK 动态代理 + 十步流水线（RAG→内容→护栏→格式→消息→审核→工具→模型→循环→解析）。它把 Spring 用 `@Transactional`+AOP 交付的体验复制到 LLM 领域：**业务接口保持纯 JVM 类型，AI 复杂度全部被代理吃掉**。
- 注解家族管输入（`@SystemMessage/@UserMessage/@V/@MemoryId`），返回类型管输出（ServiceOutputParser 的双路径结构化输出）；`Result<T>` 富返回让 token/引用/中间响应触手可及。
- 1.20 的非阻塞改造有一条清晰的纪律：**异步钩子默认失败、绝不静默阻塞伪装**；适配器 SPI 让 Reactor/Kotlin 生态无缝接入。
- 模板引擎、代理实现、输出解析器工厂全部 SPI 化；`chain` 包是 0.x 遗民，新代码一律走 AiServices。

---

# 四、Tools：工具调用循环

> 本章对应源码：`langchain4j/src/main/java/dev/langchain4j/service/tool/`（工具循环）与 `langchain4j-core/src/main/java/dev/langchain4j/agent/tool/`（工具注解与规范）。

## 4.1 @Tool 与工具规范推导

**先白话**：所谓"工具"，就是一个普通 Java 方法。你给方法挂 `@Tool`，框架用反射读出方法签名，生成 JSON Schema 描述发给模型；模型决定"我要调这个工具"，框架再把模型返回的 JSON 参数反射回填进方法调用。整个过程你的代码就是纯 Java 方法，零框架 API 依赖。

【源码证据】`langchain4j-core/src/main/java/dev/langchain4j/agent/tool/`

```java
@Tool("查询股票价格")                      // name 默认取方法名
public double getPrice(@P("股票代码") String symbol, @ToolMemoryId Object memoryId) { ... }
```

- `@Tool`（`Tool.java:26`）：`value/name` 是给模型看的描述——**描述质量直接决定模型选工具的准确率**；1.16.0 起父类与接口上的 `@Tool` 方法也会被继承扫描（PR #5328）；1.15.0 起参数支持默认值（PR #5146）。
- `@P`（`P.java`）：参数级描述（1.13 起支持 name/description 属性）；`@ToolMemoryId`：注入当前会话的 memoryId（多用户工具状态隔离）。
- `ToolSpecifications.toolSpecificationFrom(Method)`（`ToolSpecifications.java:128`）是反射推导入口：读注解、遍历参数（`:176 parametersFrom`），生成 `ToolSpecification`（工具名/描述/`JsonObjectSchema` 参数 schema）。schema 推导支持嵌套 POJO、枚举、`Optional`、集合等 Java 类型系统到 JSON Schema 的完整映射。
- `ReturnBehavior`（`ReturnBehavior.java`，1.4.0）：`NORMAL`（结果回灌模型）/`IMMEDIATE`（工具结果直接作为方法返回值，跳出循环）/`IMMEDIATE_IF_LAST`（1.14.0：是最后一个工具调用才立即返回）。

## 4.2 DefaultToolExecutor：反射执行

【源码证据】`langchain4j/src/main/java/dev/langchain4j/service/tool/DefaultToolExecutor.java`

```java
public class DefaultToolExecutor implements ToolExecutor {               // :47
    public ToolExecutionResult executeWithContext(ToolExecutionRequest request, InvocationContext ctx) {  // :134
        Object[] arguments = prepareArguments(request, ctx);              // :135
        ...
        Object result = invokeMethod(arguments);                          // :171 反射调用
        ...
    }
    private Object invokeMethod(Object[] arguments) throws ... {          // :231
        return method.invoke(object, arguments);
    }
    static Object[] prepareArguments(...) {                               // :337
        // JSON 反序列化：模型给的 JSON 参数 → 方法实参（Jackson + schema 校验）
    }
}
```

`ToolExecutor` 接口只有 `execute(ToolExecutionRequest, Object memoryId)` 一个方法（`:222` 的 `execute` 是它的兼容实现）——**自定义工具执行逻辑（如统一鉴权、审计、沙箱化）只需替换这个接口**，装配时用 `AiServices.tools(Map<ToolSpecification, ToolExecutor>)`（`AiServices.java:603`）。

## 4.3 工具循环：executeInferenceAndToolsLoop 逐行解析

**先白话**：这是"模型↔工具"的自动往返引擎，也是整个框架最常被追问的"Agent 在哪里"的答案——**循环就是 Agent**。

【源码证据】`langchain4j/src/main/java/dev/langchain4j/service/tool/ToolService.java:613-731`

```java
public ToolServiceResult executeInferenceAndToolsLoop(...) {
    int roundTripsLeft = maxToolCallingRoundTrips;                       // :650
    while (true) {                                                        // :651
        if (roundTripsLeft-- == 0)                                        // :653 防失控熔断
            throw runtime("exceeded %s tool calling round trips", ...);

        AiMessage aiMessage = chatResponse.aiMessage();
        if (chatMemory != null) chatMemory.add(aiMessage);                // :661 写记忆
        if (!aiMessage.hasToolExecutionRequests()) break;                 // :668 模型不调工具→结束

        Map<ToolExecutionRequest, ToolExecutionResult> toolResults =
                execute(toolExecutionRequests, toolServiceContext.toolExecutors(), ...);  // :675 执行工具

        ToolResultsOutcome outcome = processToolResults(...);             // :678 组结果消息/判断立即返回
        compensateIfNeeded(...);                                          // :688 失败补偿（1.17+）
        List<ChatMessage> nextMessages =
                persistToolResultsAndResolveMessagesSync(...);            // :699 工具结果写记忆
        if (shouldReturnImmediately(...))                                 // :702 IMMEDIATE 语义
            return immediateToolServiceResult(...);

        NextChatRequest next = prepareNextChatRequest(...);               // :706 组下一轮请求
        chatResponse = chatModelInvoker.apply(next.chatRequest());        // :718 再调模型
    }
    return finalToolServiceResult(...);                                   // :725 汇总
}
```

读懂这段循环，五个要点：

1. **循环的退出条件只有一个**：模型的 `AiMessage` 不再携带工具调用请求（`:668`）——即模型认为"事已办完，可以回答了"。轮次上限（`:653`）只是保险丝，默认值由 `maxToolCallingRoundTrips` 控制。
2. **记忆写入发生两次**：模型消息（`:661`）与工具结果（`:699`）都进记忆——下一轮请求自然携带全部历史，模型能"看到"自己上一步调了什么、结果是什么。
3. **多工具并发**（1.4.0，`executeConcurrently`，`:2035`）：模型一次返回多个工具调用请求时，默认顺序执行，`executeToolsConcurrently()` 开启并发（可指定 Executor，`AiServices.java:686`）。
4. **立即返回**（`shouldReturnImmediately`，`:702`）：标了 `ReturnBehavior.IMMEDIATE` 的工具，其结果不回灌模型，直接作为方法返回值——适合"模型只是帮我选一个方法执行"的场景。
5. **错误分类处理**（1.4.0 引入两类错误处理器，1.21.0 强化）：参数解析错误（`ToolArgumentsErrorHandler`）与执行错误（`ToolExecutionErrorHandler`）可以分别决定"把什么信息告诉模型让它重试"还是"终止抛出"。1.21.0 的 PR #6420 让**每种异常类型自己决定对 LLM 说什么**——错误恢复策略从"框架统一兜底"细化为"每个工具自治"。

**补偿机制**（1.17.0 `@CompensateFor` + 1.19.0 系统级补偿）：工具方法可声明自己的补偿动作（类似 Saga 的 compensate），`compensateIfNeeded`（`:688`）在某轮工具失败时回滚已执行成功的兄弟工具。这是把分布式事务的补偿思想搬进了工具循环。

## 4.4 动态工具：ToolProvider 与 Tool Search

**静态 `tools(Object...)` 的问题**：工具列表在装配时固定。但 MCP 服务器（第九章）、工具注册表、技能包（1.12）都是**运行时才知道**有什么工具。`ToolProvider`（`service/tool/ToolProvider.java`，45 行）解决之：

```java
public interface ToolProvider {
    CompletableFuture<ToolProviderResult> provideTools(ToolProviderRequest request);  // 每次推理前调用
}
```

`ToolService.createContext`（`:544`）在每轮流水线开始时调用所有 `ToolProvider`，把动态拉取的工具与静态 `@Tool` 合并成当轮的 `ToolExecutors`——所以 MCP 工具、AI Service 当工具（1.1.0，`AiServiceTool.java`）、注册表工具（1.9 的 `AgentsRegistry`）都只是 `ToolProvider` 的不同实现。**工具数量爆炸**时用 Tool Search（1.12.1，`tool/search/` + `SearchBehavior.java`）：不再把全部工具 schema 塞进提示词，而是给模型一个"搜索工具"的元工具，按需检索加载。

## 4.5 本章小结

- 工具 = 普通 Java 方法 + `@Tool`；规范推导（反射→JSON Schema）、参数回填（JSON→反射实参）、循环编排（while + 记忆往返）三段全自动。
- 循环即 Agent：退出条件是模型不再要求调工具；轮次熔断、并发执行、立即返回、双错误处理器、补偿动作、按需检索（Tool Search）构成完整的生产级控制面。
- `ToolProvider` 把"工具从哪来"抽象成接口，MCP/AI Service/注册表/技能包皆为其实现——动态工具生态的统一插口。

---

# 五、Memory：对话记忆

> 本章对应源码：`langchain4j-core/.../memory/ChatMemory.java`、`langchain4j/.../memory/chat/MessageWindowChatMemory.java`、`langchain4j-core/.../store/memory/`。

## 5.1 两层抽象：ChatMemory 与 ChatMemoryStore

**先白话**：模型本身无状态，"多轮对话"要靠每次把历史消息全量重发。`ChatMemory` 负责维护"该发什么"（窗口策略），`ChatMemoryStore` 负责"存到哪"（持久化策略）——两层解耦，互不知晓。

【源码证据】`langchain4j-core/src/main/java/dev/langchain4j/memory/ChatMemory.java`

```java
public interface ChatMemory {                       // :21
    Object id();                                     // :27 记忆实例标识（配合 @MemoryId）
    void add(ChatMessage message);                   // :34
    default void set(Iterable<ChatMessage> messages) { clear(); add(messages); }  // :90 整体重写（1.11）
    List<ChatMessage> messages();                    // :106 读（实现可只返回窗口内子集）
    void clear();                                    // :175
    // 1.20 非阻塞钩子：addAsync(:133) / setAsync(:152) / messagesAsync(:168)
    // 默认 AsyncNotSupportedException —— 不静默阻塞伪装，同第二章纪律
}
```

`ChatMemoryStore`（`store/memory/chat/ChatMemoryStore.java`）更简单：`getMessages(id)` / `updateMessages(id, messages)` / `deleteMessages(id)` 三个方法，默认实现是内存 Map。**官方与社区提供十余种持久化实现**（Oracle 1.17、Postgres/Redis/Mongo 等在 community 仓库 `chat-memory-stores/`）。

## 5.2 MessageWindowChatMemory：滑动窗口实现

**先白话**：上下文窗口有限，记忆不能无限膨胀。最朴素的策略是滑动窗口：只保留最近 N 条消息。

【源码证据】`langchain4j/src/main/java/dev/langchain4j/memory/chat/MessageWindowChatMemory.java`

```java
public void add(ChatMessage message) {                       // :69
    List<ChatMessage> messages = messages();                  // 读 store → 窗口化
    if (appendMessage(messages, message)) store.updateMessages(id, messages);  // :73 写回
}
private boolean appendMessage(List<ChatMessage> messages, ChatMessage message) {  // :97
    if (message instanceof SystemMessage) {                   // :98 SystemMessage 特殊规则：
        Optional<SystemMessage> systemMessage = SystemMessage.findFirst(messages);
        if (systemMessage.isPresent()) {
            if (systemMessage.get().equals(message)) return false;   // :101 相同→跳过（幂等）
            else messages.remove(systemMessage.get());               // :104 不同→替换
        }
    }
    messages.add(message);                                    // :112
    ensureCapacity(messages, maxMessagesProvider.apply(this.id));  // :117 窗口裁剪
    return true;
}
private static void ensureCapacity(List<ChatMessage> messages, int maxMessages) {  // :174
    while (messages.size() > maxMessages) {
        int messageToEvictIndex = 0;
        if (messages.get(0) instanceof SystemMessage) messageToEvictIndex = 1;  // :179 SystemMessage 永不被逐出
        ...
    }
}
```

三个细节体现工程考量：**SystemMessage 幂等添加**（重复设置同一系统提示词不产生重复消息）、**SystemMessage 永不逐出**（裁剪时跳过队首系统消息，`maxMessages` 必须 ≥ 2 才有意义）、**maxMessages 可按 memoryId 动态供给**（`maxMessagesProvider`）。此外 1.11.0 增加的 `set()` 整体重写方法是为"记忆压缩/摘要"类高级策略准备的——框架默认不做摘要，但口子留好了（对比 Python LangChain 1.x 的 SummarizationMiddleware，这里需要自己实现策略类）。

## 5.3 多用户隔离与装配

```java
// 单例记忆：所有调用共享（单用户场景）
.aiServices... .chatMemory(MessageWindowChatMemory.withMaxMessages(10))

// 多用户：@MemoryId 参数 + Provider 按 id 建记忆
interface Assistant { String chat(@MemoryId String userId, @UserMessage String msg); }
.chatMemoryProvider(userId -> MessageWindowChatMemory.builder()
        .id(userId).maxMessages(10).build())
```

`ChatMemoryService`（`service/memory/`）维护 id→ChatMemory 的注册表；代理接口可直接继承 `ChatMemoryAccess`（`service/memory/ChatMemoryAccess.java`）获得 `getChatMemory(id)`/`evictChatMemory(id)` 旁路管理能力（由代理自己实现，见 3.3 节）。**默认 RAG 检索到的内容不进记忆**（避免记忆被引用文本污染），可用 `storeRetrievedContentInChatMemory(true)`（`AiServices.java:1314`）打开。

## 5.4 本章小结

- `ChatMemory`（策略：留什么）与 `ChatMemoryStore`（策略：存哪里）两层正交；滑动窗口是默认实现，摘要/压缩等高级策略通过 `set()` 与自定义 `ChatMemory` 落地。
- `@MemoryId` + `ChatMemoryProvider` 是多租户记忆的标准姿势；`ChatMemoryAccess` 让记忆管理变成接口方法。
- 1.20 的异步钩子纪律与全框架一致：默认失败不伪装；持久化实现按 id 全量读写，实现方应注意原子性（接口 javadoc 有明确警示）。

---

# 六、RAG：检索增强流水线

> 本章对应源码：`langchain4j-core/.../rag/`（流水线）、`.../data/document/`（文档模型）、`.../store/embedding/`（嵌入与存储）。

## 6.1 两阶段全景

**先白话**：RAG（检索增强生成）分两个完全独立的阶段——**摄取**（离线，文档→切片→向量→入库）和**检索**（在线，用户问题→改写→多路检索→聚合→注入提示词）。LangChain4j 把两个阶段都接口化：

```
【摄取离线】Document ─→ DocumentParser ─→ DocumentSplitter ─→ EmbeddingModel
                （PDF/HTML→文本）    （长文→切片）        （切片→向量）──→ EmbeddingStore
             由 EmbeddingStoreIngestor 串联（store/embedding/EmbeddingStoreIngestor.java:170 ingest）

【检索在线】UserMessage ─→ RetrievalAugmentor.augment()  ─→ 增强后的 UserMessage
             （五段流水线，下一节）                         （引用已注入）
```

摄取入口 `EmbeddingStoreIngestor`（`:170-192`）是个流式 Builder：`documentTransformer → documentSplitter → textSegmentTransformer → embeddingModel → embeddingStore` 五件套逐级处理，支持批量、可观测（listener）与异步。

## 6.2 文档模型与切分

- `Document`（`data/document/`）：文本 + `Metadata`（文件名、来源、自定义键值）。`DocumentParser`（23 行接口）负责把原始字节流转成 `Document`——**六个解析器模块**（pdfbox/poi/tika/docling/markdown/yaml）+ 社区更多；SPI `DocumentParserFactory` 可整体替换（Docling 是 1.15 新增的重型文档理解解析器）。
- `DocumentSplitter`（`data/document/DocumentSplitter.java:18`）：`List<TextSegment> split(Document)`。切分算法族（段落/句子/固定 token/递归）在 core 内实现，**句子边界检测用 OpenNLP**——这就是主模块依赖 opennlp-tools 的原因（1.4 节依赖表）。切分时可传 `DocumentTransformer` 先做清洗。

## 6.3 DefaultRetrievalAugmentor：五段流水线逐段解析

**先白话**：官方实现灵感来自 LangChain 团队的 "Deconstructing RAG" 博文与 arXiv 论文 2312.10997（源码 javadoc 明确引用，`rag/DefaultRetrievalAugmentor.java:61-62`）。五段各自是接口，默认实现可用，全部可换：

```
Query ─→ ①QueryTransformer ─→ [Query×N] ─→ ②QueryRouter ─→ (Query,Retriever)×M
              （改写/扩展/压缩）                （路由到检索器）
                     ─→ ③ContentRetriever.retrieve(Query) ─→ Content×K
                     ─→ ④ContentAggregator（RRF 融合/重排/过滤）
                     ─→ ⑤ContentInjector（引用注入 UserMessage）─→ AugmentationResult
```

【源码证据】`langchain4j-core/src/main/java/dev/langchain4j/rag/DefaultRetrievalAugmentor.java`

```java
public class DefaultRetrievalAugmentor implements RetrievalAugmentor {   // :123
    private final QueryTransformer queryTransformer;                      // :125
    private final QueryRouter queryRouter;                                // :126
    private final ContentAggregator contentAggregator;                    // :127
    private final ContentInjector contentInjector;                        // :128
    private final Executor executor;                                      // :129 多查询/多检索器并行
    // augment() :169 从 UserMessage 提取 Query → 走完五段 → AugmentationResult
}
```

逐段看默认实现与进阶玩法：

1. **QueryTransformer**（`rag/query/transformer/QueryTransformer.java:38`，`Collection<Query> transform(Query)`）：一变多是关键——`ExpandingQueryTransformer`（用 LLM 把一个问题改写成 N 个变体，多路召回）、`CompressingQueryTransformer`（结合对话历史压缩指代，"它多少钱"→"iPhone 多少钱"）、`DefaultQueryTransformer`（原样透传）。
2. **QueryRouter**（`rag/query/router/QueryRouter.java`）：把每个 Query 路由到合适的检索器。`DefaultQueryRouter`（全路由给所有检索器）；自定义路由器可实现"结构化问题走 SQL、语义问题走向量库、时事问题走 Web 搜索"的分流（1.21 起官方甚至提供基于决策模型的 `DecisionRouterPlanner`）。
3. **ContentRetriever**（`rag/content/retriever/ContentRetriever.java:46`，`List<Content> retrieve(Query)`）：**整个 RAG 体系最重要的接口**——向量检索（`EmbeddingStoreContentRetriever`）、网络搜索（`WebSearchContentRetriever`）、SQL（experimental-sql）、知识图谱（community Neo4j）都是它的实现。1.20 加 `retrieveAsync`（`:67`）；1.11 加监听器包装（`addListener`，`:79`）。
4. **ContentAggregator**（`rag/content/aggregator/`）：多路结果怎么合并？`DefaultContentAggregator` 用 **RRF（Reciprocal Rank Fusion）**做无参排名融合（`ReciprocalRankFuser.java`）；`ReRankingContentAggregator` 用第二章的 `ScoringModel` 精排——这就是"粗召回 + 精排"的两段式检索。
5. **ContentInjector**（`rag/content/injector/`）：把最终内容注入提示词。`DefaultContentInjector` 拼接格式可定制，可选择附加引用元数据（`AugmentationResult.contents()` 供 `Result.sources()` 使用）。

**并行与异步**：查询数 × 检索器数 > 1 时用 `Executor` 扇出（默认 keepAlive 1s 的缓存线程池，`:160-166`）；1.20 起整条链有 `augmentAsync`，阻塞段可配置卸载（`offloadBlocking`，`:141`，Java 21+ 默认虚拟线程）。

**与 AiServices 的接法**（3.4 节第 ① 步）：`retrievalAugmentor(...)` 是全装入口；只配 `contentRetriever(...)` 时框架自动包一层"默认五段流水线"——单查询单检索器走同线程快速路径（`:108-110`），无感知开销。

## 6.4 EmbeddingStoreContentRetriever 与向量库生态

【源码证据】`rag/content/retriever/EmbeddingStoreContentRetriever.java`（436 行）

它做的事：`Query → embeddingModel.embed(query) → store.search(EmbeddingSearchRequest) → Content 列表`。搜索条件由 `EmbeddingSearchRequest`（`store/embedding/EmbeddingSearchRequest.java`）承载：`queryEmbedding`、`maxResults`、`minScore`、`filter`（元数据过滤）。

【源码证据】`store/embedding/EmbeddingStore.java`

```java
public interface EmbeddingStore<Embedded> {                    // 接口签名节选
    String add(String id, Embedding embedding);                 // :42 存向量
    EmbeddingSearchResult<Embedded> search(EmbeddingSearchRequest request);  // :161 唯一搜索入口
    default void removeAll(Filter filter) { ... }               // :139 按条件删除
    // 1.20：searchAsync
}
```

**主仓库 21 个嵌入存储模块 + 社区仓库更多**（1.3 节清单）：每个模块的工作就是把这个接口翻译成对应向量库的原生 API。`Filter` DSL（`store/embedding/filter/`）提供存储无关的元数据过滤表达式；`langchain4j-embedding-store-filter-parser-sql` 模块能把 SQL WHERE 语句解析成 Filter 对象。

## 6.5 本章小结

- RAG 分摄取与检索两阶段；摄取五件套由 `EmbeddingStoreIngestor` 串联，检索五段由 `DefaultRetrievalAugmentor` 编排——全部接口化，默认实现即"Naive RAG"，逐段替换即"Advanced RAG"。
- 五段的心智模型：**改写（一变多）→ 路由（选路）→ 检索（多路）→ 聚合（RRF/重排）→ 注入（引用）**。决策模型（1.21）与 LLM（Expanding/Compressing）都能当"大脑"用。
- `EmbeddingStore.search(EmbeddingSearchRequest)` 是向量库的唯一搜索契约；21+ 存储模块是纯翻译层；`ContentRetriever` 则是更上位的"数据源"抽象——向量库、Web、SQL、图数据库同一待遇。

---
# 七、护栏与审核：Guardrails、Moderation

> 本章对应源码：`langchain4j-core/.../guardrail/`（接口）、`langchain4j/.../service/guardrail/`（服务装配）。

## 7.1 输入/输出护栏：@InputGuardrails 与 @OutputGuardrails

**先白话**：护栏（guardrail）是挂在 AI Service 方法上的"安检链"——输入护栏检查用户消息（防提示词注入、敏感词、越权请求），输出护栏检查模型回复（合规、格式、事实性）。1.1.0 引入（PR #2571），是对"审核模型"之外的轻量级、可编程方案。

【源码证据】`langchain4j/src/main/java/dev/langchain4j/service/guardrail/InputGuardrails.java`

```java
@Retention(RUNTIME) @Target({TYPE, METHOD})        // 类级=作用于全部方法，方法级=单个方法
public @interface InputGuardrails { InputGuardrail[] value(); }
```

护栏本体是 `Guardrail` 接口（`langchain4j-core/.../guardrail/Guardrail.java`）的两个子接口：

- **`InputGuardrail`**（`InputGuardrail.java`）：检查 `UserMessage`，返回 `InputGuardrailResult`——成功 / 失败（带原因，包装为 `GuardrailException` 直接抛给调用者）/ **改写**（返回修改后的消息继续走）。javadoc 明确：输入护栏**不支持重试或重新提问**，失败即终止（`InputGuardrails.java:11-13`）。
- **`OutputGuardrail`**（`OutputGuardrail.java`）：检查 `AiMessage`，返回 `OutputGuardrailResult`——除成功/失败外支持 **retry**（带反馈让模型重新作答，可配次数）与 **reprompt**（换提示词重问）；1.8.0 起还支持整条改写 `AiMessage`（PR #3868）。

**执行编排**：`GuardrailService`（`service/guardrail/GuardrailService.java`）+ 执行器 Builder（core 里 `InputGuardrailExecutor`/`OutputGuardrailExecutor`，配 SPI 工厂 `GuardrailExecutorBuilderFactory`）把注解声明的护栏列表组装成链，流水线在 3.4 节的 ③ 步（输入侧，模型调用前）与 ⑩ 步（输出侧，解析前）各执行一次。流式场景下输出护栏在流结束后执行（`AiServiceStreamingEventPublisher.toTextPublisher` 的 `hasOutputGuardrails` 参数，`DefaultAiServices.java:666`）。

**与审核模型的关系**：`ModerationModel`（第二章接口族）是外部审核服务（如 OpenAI moderation），`@Moderate` 注解触发（`AiServices.java:490` 配模型，方法级 `@Moderate` 开启）；框架**异步**调用审核（`DefaultAiServices.java:715` trigger，`:768` 在返回前 verify）——不阻塞主流程但保证返回前结果生效。护栏是本地代码逻辑，审核模型是远端模型，两者互补而非替代。

## 7.2 本章小结

- 护栏 = 注解声明 + `Guardrail` 实现类链；输入护栏只拦截不改答，输出护栏可重试/换问/改写——语义上就是"模型调用的前后置校验器"，AOP 的 before/after 思想在 LLM 领域的对应物。
- 审核走异步 `ModerationModel`，与护栏正交组合；两套机制都接在 AiServices 流水线的固定位置，供应商与业务代码无感知。

---

# 八、Agentic：多智能体编排（1.3+ 的新大陆）

> 本章对应源码：`langchain4j-agentic/src/main/java/dev/langchain4j/agentic/`（155 个文件）。1.3.0（2025-08-07）随实验模块引入，此后每个版本都在快速加厚，是当前框架最活跃的部分。

## 8.1 从 AiServices 到 Agent

**先白话**：AiServices 已经解决了"一个 AI 服务"的问题；agentic 解决"多个 AI 服务怎么协作"的问题。最小粒度是 `@Agent`：**一个挂了 `@Agent` 注解的接口方法**（通常在 AiServices 接口上）就是一个智能体——有名字、有描述、有输入输出类型，可以被其他智能体调用（像工具一样），也可以被工作流编排。

【源码证据】`langchain4j-agentic/src/main/java/dev/langchain4j/agentic/Agent.java`

```java
/**
 * Java methods annotated with {@code @Agent} are considered agents
 * that other agents can invoke.
 */
@Retention(RUNTIME) @Target({METHOD})
public @interface Agent {
    String name() default "";      // 智能体名（缺省=方法名）
    String value() default "";     // 描述：给 LLM 看的"职责说明书"（决定调度准确率）
    ...
}
```

两种组装方式：**编程式**（`AgenticServices.sequenceBuilder(...)` 等流式 Builder）与**声明式**（`declarative/` 包的 `@SequenceAgent`/`@ParallelAgent`/`@LoopAgent` 等注解直接标注接口，1.4.0 成型）。两者可混用（1.6.0）。

## 8.2 工作流模式全家福

模式实现类集中在 `workflow/`（每个模式一个 Service）与 `supervisor/`、`planner/`：

| 模式 | 声明式注解 | 编排语义 | 引入版本 |
|---|---|---|---|
| 顺序 | `@SequenceAgent` | 依次执行，前一个输出（或指定字段）喂后一个 | 1.3.0 |
| 并行 | `@ParallelAgent` + `@ParallelExecutor` | 并发执行多个子智能体，结果聚合 | 1.3.0 |
| 循环 | `@LoopAgent` | 按条件（`@ExitCondition`）或次数反复执行，`LoopCounter` 可注入 | 1.3.0 |
| 条件 | `@ConditionalAgent` | 按谓词（`@ActivationCondition`）激活不同子智能体 | 1.3.0 |
| 主管 | `@SupervisorAgent` | LLM 当主管，动态决定派工给哪个子智能体、何时收工 | 1.3.0 |
| 规划 | `@PlannerAgent` / Planner | 用 LLM 先生成执行计划再调度（1.9.0 通用规划器，所有模式重写其上） | 1.3.0 / 1.9.0 |
| 注册表 | Registry | 运行时按描述匹配可用智能体（`AgentsRegistry`） | 1.9.0 |
| 投票 | Voting | 多个智能体独立作答再投票裁决 | 1.15.0 |
| 黑板 | Blackboard | 共享记忆协作（"贴便签"式异步协同） | 1.16.0 |
| 辩论 | Debate | 多智能体轮流辩论，裁判收敛 | 1.17.0 |
| BDI | BDI | Belief-Desire-Intention 认知架构模式 | 1.18.0 |
| 决策路由 | DecisionRouterPlanner | 用决策模型（1.21）选子智能体，快且省 token | 1.21.0 |

这一张表读下来的重点：**模式不是并列的 API，而是同一条调度引擎上的不同拓扑**。1.9.0 的"通用规划器"（PR #3929）把全部模式重写为其上的配置，1.21.0 的决策模型路由又给"谁来决策"多了一个便宜的选项。`langchain4j-agentic-patterns` 模块则把常用模式打包成开箱即用的组合。

## 8.3 AgenticScope：共享状态与持久化

**先白话**：多个智能体协作需要共享"工作区"——中间结果、上下文变量。`AgenticScope` 就是这个工作区，类似多智能体版的 `ConversationBuffer`。

【源码证据】`langchain4j-agentic/src/main/java/dev/langchain4j/agentic/scope/AgenticScope.java`

```java
public interface AgenticScope extends LangChain4jManaged {
    Object memoryId();                                      // :28 会话唯一标识（记忆隔离键）
    void writeState(String key, Object value);              // :37 写
    <T> void writeState(Class<? extends TypedKey<T>> key, T value);   // :47 类型安全键（防魔法字符串）
    Object readState(String key);                           // :105 读
    <T> T readState(String key, T defaultValue);            // :118 带默认值读取
    Map<String, Object> state();                            // :138 全量快照
    String contextAsConversation(String... agentNames);     // :147 把各智能体的调用历史转成对话文本
}
```

关键机制：**@V 与 @AgenticScope 注解让子智能体方法直接声明"我要哪个共享变量"**；`@McpClientAgent`（1.12）让 MCP 工具也能作为非 AI 智能体参与编排；1.13.0 起执行状态可 **JSON 持久化并恢复**（`AgenticScopeJsonCodec`，SPI 可替换存储格式），1.18.0 加上**崩溃可恢复的 HITL**（人工审批点挂起、进程重启后带状态恢复，PR #5767）——这是把 LangGraph checkpointer/interrupt 的生产级语义搬进了 Java（参见姊妹篇《LangGraph.md》第八章，两者概念同构）。

## 8.4 可观测性与错误处理

- **拓扑报告**（1.12.1，PR #4600）：一键生成多智能体系统的 HTML 拓扑与执行轨迹报告——调试多智能体系统的刚需。
- **系统级工具补偿**（1.19.0，PR #5823）：任何子智能体的工具失败都可声明补偿动作，编排器统一回滚。
- **流式/多模态/异步**（1.5/1.11）：agentic 全面对齐 AiServices 的执行形态。

## 8.5 本章小结

- agentic 的最小原子是 `@Agent` 方法；编排=把方法连成拓扑（顺序/并行/循环/条件/主管/规划/注册表/投票/黑板/辩论/BDI/决策路由），1.9 起全部模式统一在通用规划器上。
- `AgenticScope` 是带类型键的共享工作区，可持久化可恢复；HITL 人工审批点与崩溃恢复让它具备生产级工作流引擎的要素。
- 与 Python LangGraph 对比：LangGraph 显式画图、状态归图；LangChain4j agentic 显式编排 Java 方法、状态归 AgenticScope——"图"的心智模型被"方法编排"替代，更贴 Java 习惯。

---

# 九、MCP、Skills 与可观测性

## 9.1 MCP 客户端（langchain4j-mcp）

**先白话**：MCP（Model Context Protocol）是工具生态的标准协议（姊妹篇《LangChain.md》第九章有 Python 视角的讲解）。`langchain4j-mcp` 是完整的 MCP 客户端实现，让 Java 应用消费任意 MCP 服务器的工具/资源/提示词。

【源码证据】`langchain4j-mcp/src/main/java/dev/langchain4j/mcp/`（实测结构）

```
McpToolProvider                  # ToolProvider 实现：把 MCP 工具桥接进工具循环（第四章）
client/
├── McpClient / DefaultMcpClient # 客户端：builder 配传输层/工具映射/日志/监听器
├── transport/                   # McpTransport 接口 + 四种传输：stdio、HTTP SSE、
│                                #   StreamableHttp（1.4）、WebSocket（1.9）
├── progress/ logging/           # 进度通知、日志消息处理
└── 资源即工具（resourcesastools，1.2） / 工具名映射（1.8） / 监听器与头部供给（1.11）
mcp/registryclient/              # MCP Registry 注册表客户端（1.5）
```

接法与普通工具完全一致：`.toolProvider(McpToolProvider.builder().mcpClients(client).build())`——每次推理前由 `ToolProvider` 动态拉取工具列表（4.4 节），MCP 工具对模型与本地 `@Tool` 无差别。1.19.0 跟进 MCP 2026-07-28 规范；1.12 起 MCP 工具还能作为 agentic 系统里的"非 AI 智能体"参与编排。另有 `langchain4j-mcp-docker`（容器化 MCP 服务器管理）与 community 仓库的 MCP 相关模块。

## 9.2 Agent Skills（1.12+）

`langchain4j-skills` 模块实现 Agent Skills（技能包：描述文档 + 脚本/工具的目录约定，1.12.1 引入，PR #4646）：技能描述进入系统提示词，技能内工具按需加载（与 Tool Search 配合）；1.13 增加技能级工具作用域与 `ClassPathSkillLoader`；`experimental/langchain4j-experimental-skills-shell` 提供执行技能脚本的 Shell 沙箱。

## 9.3 可观测性体系

LangChain4j 的可观测是三层叠加：

1. **模型监听器**：`ChatModelListener`（onRequest/onResponse/onError，2.2 节）——供应商无关地截获每次模型调用，适合记日志、算成本。1.11.0 起同构监听器扩展到 `EmbeddingModel`/`EmbeddingStore`/`ContentRetriever`（`addListener` 默认方法包装）。
2. **AI Service 事件**：`AiServiceListener` + 类型化事件族（`AiServiceStartedEvent`/`ResponseReceivedEvent`/`ErrorEvent` 等，`observability/api/event/`），`DefaultAiServices` 在流水线各节点 `fireEvent`（3.4 节各步可见）——比模型监听器高一层的业务视图。
3. **指标与链路**：`langchain4j-micrometer-metrics`（1.12，指标）与 `langchain4j-observation`（1.12，Observation API）——后者直接对接 Spring Boot 3 的 Micrometer Observation 体系，Span/Trace 顺流而下。

## 9.4 SPI 全景

`dev.langchain4j.spi`（core + 主模块，实测 28 个工厂接口）由 `ServiceHelper.loadFactories`（`java.util.ServiceLoader` 封装）加载，是"每个环节留口子"哲学的完整清单。选摘：

| SPI | 作用 | 典型用户 |
|---|---|---|
| `PromptTemplateFactory` | 换模板引擎 | Quarkus（GraalVM 原生镜像替换，源码注释明示） |
| `JsonCodecFactory` / `ChatMessageJsonCodecFactory` | 换 JSON 实现 | `langchain4j-jackson3`（1.20） |
| `ExecutorProvider` | 框架默认线程池 | Java 21+ 换虚拟线程 |
| `TokenStreamAdapter` / `CompletableFutureAdapter` / `PublisherAdapter` | 扩展返回类型 | `langchain4j-reactor`（Mono/Flux）、Kotlin |
| `AiServicesFactory` / `AiServiceContextFactory` | 整体替换 AiServices 实现 | Quarkus/ Spring 深度集成 |
| `DocumentParserFactory` / `DocumentSplitterFactory` | 文档处理扩展 | 自定义格式支持 |
| `EmbeddingModelFactory` / `StateJsonCodecFactory` | agentic 状态编码、嵌入模型工厂 | 存储/编排集成 |

## 9.5 本章小结

- MCP 客户端是工具生态的标准插口，`McpToolProvider` 让"远程工具"与"本地 @Tool"在模型眼里无差别；传输层四选一，版本紧跟 MCP 规范演进。
- Skills 解决"能力太多怎么组织"，Tool Search 解决"工具太多怎么发现"，两者配合应对工具爆炸。
- 可观测三层（模型监听器 → AI Service 事件 → Micrometer/Observation）+ 28 个 SPI，框架的每个横切面与每个内核件都留有替换口。

---

# 十、Spring Boot 集成（langchain4j-spring 仓库）

> 本章对应源码：`D:\code\3rd\langchain4j-spring`（独立仓库，40+ 模块）。

## 10.1 starter 全景

```
langchain4j-spring/
├── langchain4j-spring-boot-starter        # 核心：@AiService 装配 + BOM 级配置
├── langchain4j-open-ai-spring-boot-starter        # 每个供应商一个 starter（20+）
├── langchain4j-ollama-spring-boot-starter / anthropic / bedrock / google-ai-gemini / …
├── langchain4j-pgvector-spring-boot-starter / milvus / elasticsearch / …  # 存储也有
├── langchain4j-easy-rag-spring-boot-starter
├── langchain4j-*-spring-boot4-starter     # Spring Boot 4 双线（1.13.0 起，PR #175）
├── langchain4j-http-client-spring-restclient      # 复用 Spring 的 RestClient
└── langchain4j-reactor                    # TokenStream→Flux 适配（Reactor 用户）
```

每个供应商 starter 干的事高度模板化：读 `langchain4j.<provider>.*` 配置属性 → 构造对应的 `ChatModel`/`EmbeddingModel` Bean 注册进容器（属性名与供应商 Builder 一一对应，如 `langchain4j.open-ai.chat-model.api-key`）。核心 starter 则负责"AI 服务装配"这一层。

## 10.2 @AiService：自动装配源码解析

**先白话**：在 Spring 里用 LangChain4j，最顺手的姿势是——接口挂 `@AiService`，其他组件（模型、记忆、检索器、工具）都是普通 Bean，启动时框架自动把它们装配成一个代理 Bean。你注入接口就能用，全程零样板。

【源码证据】`langchain4j-spring-boot-starter/src/main/java/dev/langchain4j/service/spring/`

```java
@Retention(RUNTIME) @Target(TYPE)
public @interface AiService {                      // AiService.java:68
    AiServiceWiringMode wiringMode() default AUTOMATIC;   // :73 装配模式（自动/手动指名）
    String chatModel() default "";                        // :80 手动指名时的 Bean 名称（按类型留空）
    String streamingChatModel() default "";               // :87
    // 其余同构字段：chatMemory / chatMemoryProvider / contentRetriever /
    // retrievalAugmentor / moderationModel / toolProvider / 各错误处理器 …
}

// AiServicesAutoConfig.java（自动配置类，节选）：
String[] aiServices = beanFactory.getBeanNamesForAnnotation(AiService.class);  // :93 扫描 @AiService 接口
for (String aiService : aiServices) {
    GenericBeanDefinition aiServiceBeanDefinition = new GenericBeanDefinition();
    aiServiceBeanDefinition.setBeanClass(AiServiceFactory.class);  // :98 注册 FactoryBean
    addBeanReference(beanFactory, aiServiceClass, ChatModel.class, ...);   // :104 按类型/按名装配 chatModel
    addBeanReference(..., ChatMemory.class, ...);                          // :135
    addBeanReference(..., ContentRetriever.class, ...);                    // :155
    addBeanReference(..., RetrievalAugmentor.class, ...);                  // :168
    ...
}
```

装配流水线四步（实测 `AiServicesAutoConfig.java`）：

1. **`AiServiceScannerProcessor`**（BeanFactoryPostProcessor）用 `ClassPathAiServiceScanner` 在类路径扫出 `@AiService` 接口；
2. **工具 Bean 扫描**（`:70-91`）：遍历全部 Bean 定义，把 `@Tool` 方法收集起来——**任意 Spring Bean 的 `@Tool` 方法自动成为工具**，无需手工 `.tools(...)`；
3. **按类型自动接线**：容器里唯一的 `ChatModel`/`ChatMemory`/`ContentRetriever`/`ToolProvider`/`ToolExecutionErrorHandler` 等 Bean（`:60-66` 全量探测）自动注入对应 AI Service；多实现时用注解属性指名或 `wiringMode=EXPLICIT`；
4. **`AiServiceFactory`**（FactoryBean）在实例化时调用 `AiServices.builder(...)` 装配并返回代理；完成时发布 `AiServiceRegisteredEvent`（可再加工）。

**与 Spring 容器的哲学完全同构**：@AiService≈@Repository 声明式接口、FactoryBean≈MyBatis MapperFactoryBean、BeanPostProcessor 扫描≈@MapperScan、配置属性绑定≈@ConfigurationProperties。Java 开发者第一次用它时会有强烈的既视感——这正是设计目标。

## 10.3 与 Spring AI 的对比

两者是 Spring 生态里并存的两个框架（本机 `D:\code\3rd` 同时有 spring-ai 与 langchain4j 源码可对照）。选型速查：

| 维度 | LangChain4j | Spring AI |
|---|---|---|
| 所属与节奏 | 独立社区项目，约 3~4 周/版，特性激进（agentic/MCP/决策模型跟进最快） | Spring 官方团队，随 Spring 里程碑节奏 |
| 编程模型 | **AiServices 声明式接口**为第一公民 | ChatClient 流式 API 为第一公民（亦有 @ChatClient 声明式探索） |
| 抽象命名 | `ChatModel`/`EmbeddingModel`/`ChatMemory` | 同名概念（互相影响，LangChain4j 1.0 的 ChatRequest/Response 与 Spring AI 1.0 的设计互有借鉴） |
| 多智能体 | **agentic 模块**（12 种模式、状态持久化、HITL） | 无内置，靠社区 |
| MCP | 独立客户端模块，版本紧跟规范 | spring-ai-mcp（2025 起跟进） |
| Spring 集成 | 官方 starter 仓库（独立节奏） | 原生一等公民 |
| RAG | 五段流水线接口，每段可换 | Advisor 链式拦截 |
| 适合场景 | 想要最全的 LLM 特性面、Java 优先、多智能体需求 | 深度绑定 Spring 生命周期、团队全是 Spring 工程师 |

实践建议：**两者可共存**（抽象命名相近但类不兼容，各管各的自动配置）；纯 Spring 技术栈且需求简单选 Spring AI 更"顺"，需要 agentic/MCP/前沿特性或已有 LangChain4j 资产则选 LangChain4j。

## 10.4 本章小结

- Spring 集成 = 类路径扫描（@AiService/@Tool）+ 类型接线（容器内唯一即自动装配）+ FactoryBean 代理；Boot 3/4 双线维护，供应商 starter 全覆盖。
- `langchain4j-reactor` 让 Reactor 用户拿 `Flux`；`http-client-spring-restclient` 让 HTTP 层复用 Spring 配置——两个仓库的接缝都开在 Spring 习惯的延长线上。

---

# 十一、贯通视图：三条时间线

**时间线一：一次声明式调用（带工具与 RAG）**

```
assistant.chat("iPhone 15 多少钱？", userId)
  │ InvocationHandler.invoke（DefaultAiServices:211）
  │   ├─ findMemoryId → InvocationContext（:223）
  │   ├─ ① retrievalAugmentor.augment（:688）
  │   │     Query → ExpandingQueryTransformer(3个变体) → DefaultQueryRouter
  │   │       → EmbeddingStoreContentRetriever×3 并行（Executor 扇出）
  │   │       → RRF 融合 → ContentInjector 注入引用 → 增强后的 UserMessage
  │   ├─ ③ inputGuardrails（:703）→ ④ appendOutputFormat（:706，返回类型=BigDecimal）
  │   ├─ ⑤ assembleMessages：记忆历史+SystemMessage+UserMessage（:712）
  │   ├─ ⑥ @Moderate 异步审核（:715）
  │   ├─ ⑧ chatExecutor.execute() → ChatModel.chat(ChatRequest)（:760）
  │   │     → OpenAiChatModel.doChat → HTTP 抽象 → SSE/JSON
  │   ├─ ⑨ ToolService 循环（:770）：
  │   │     AiMessage(要调 getPrice("iphone15")) → 写记忆 → DefaultToolExecutor 反射执行
  │   │       → ToolExecutionResultMessage 写回记忆 → prepareNextChatRequest → 再调模型
  │   │       → AiMessage("根据查询结果，iPhone 15 价格是…") → 无工具请求 → 退出循环
  │   └─ ⑩ ServiceOutputParser：BigDecimalOutputParser 解析 → 写记忆 → verifyModeration
  └─ return 5999.00（BigDecimal，调用方全程无感知）
```

**时间线二：一次流式调用**：方法返回 `TokenStream` → `:721-742` 只构建不执行 → 用户 `.onPartialResponse(...).onCompleteResponse(...).start()` → 流水线延迟启动 → 模型 SSE 增量逐个回调；若模型中途要求调工具，`onToolExecuted` 收到执行事件、循环继续、前端看到"工具执行中…"的插播。

**时间线三：一次多智能体执行**：`@SequenceAgent` 声明的系统收到请求 → 通用规划器按拓扑调度 → 子智能体 A（AiServices 代理）产出写入 `AgenticScope("价格")` → 子智能体 B 以 `@V("价格")` 读到并继续 → 全程事件上报 → 生成 HTML 拓扑报告（1.12）→ `AgenticScope` JSON 持久化（1.13），中途 HITL 挂起后可恢复（1.18）。

三张图叠起来看：**同一个 InvocationHandler（3.3）、同一个工具循环（4.3）、同一套参数对象（2.3），向上支撑"单服务声明式、流式、多智能体"三种形态**——这就是 1.0 定下的骨架至今不用推翻的原因。

---

# 十二、附录

## 12.1 关键接口速查表

| 接口 | 所在模块 | 一句话职责 | 关键实现 |
|---|---|---|---|
| `ChatModel` / `StreamingChatModel` | core | 聊天模型（同步/流式） | 26 个供应商模块；`DisabledChatModel`（显式禁用） |
| `EmbeddingModel` / `EmbeddingStore` | core | 向量化 / 向量存储检索 | ONNX 进程内实现 / 21+ 存储模块 |
| `ChatMemory` / `ChatMemoryStore` | core / 主模块 | 会话记忆策略 / 持久化 | `MessageWindowChatMemory` / InMemory + 社区 |
| `ToolExecutor` / `ToolProvider` | 主模块 | 工具执行 / 动态工具供给 | `DefaultToolExecutor` / `McpToolProvider`、`AiServiceTool` |
| `RetrievalAugmentor`（+ 五段接口） | core | 检索增强编排 | `DefaultRetrievalAugmentor` |
| `ContentRetriever` | core | 数据源检索 | `EmbeddingStoreContentRetriever`、`WebSearchContentRetriever` |
| `Guardrail`（Input/Output） | core | 输入/输出护栏 | 用户自实现 + `GuardrailService` 编排 |
| `ModerationModel` / `ScoringModel` / `ImageModel` / `BatchChatModel` / `DecisionModel` | core | 审核 / 重排 / 文生图 / 批推理 / 决策 | 各供应商模块 |
| `TokenStream` / `AiServiceStreamingEvent` | 主模块 | 流式消费 DSL / 流式事件 | `AiServiceTokenStream` / Publisher 适配 |
| `ChatModelListener` / `AiServiceListener` | core | 模型调用 / AI Service 事件观测 | `langchain4j-observation`、micrometer-metrics |
| `McpClient` / `WebSearchEngine` / `ModelCatalog` | mcp / core | MCP 客户端 / 网搜 / 模型目录 | DefaultMcpClient / tavily 等 / 1.10+ |
| `@Agent` / `AgenticScope` | agentic | 智能体方法 / 共享状态 | AgenticServices + 工作流模式族 |

## 12.2 初学者学习路线

1. **跑通最小链路**：open-ai starter + 一个 `@UserMessage` 接口（30 分钟）。理解"接口即 AI 服务"。
2. **加记忆与工具**：`@MemoryId` + `MessageWindowChatMemory`；写两个 `@Tool` 方法，用日志观察 `ToolService` 的循环行为（第四章）。
3. **加 RAG**：先用 `langchain4j-easy-rag` 一站式跑通，再逐段替换五段流水线（第六章）——理解每一段的职责。
4. **生产化**：护栏与审核（第七章）→ 监听器/指标（第九章）→ Spring Boot 装配（第十章）。
5. **进阶**：agentic 工作流（第八章）→ MCP 接入 → 非阻塞改造（3.7 节）。
6. **读源码的顺序建议**：`DefaultAiServices.invoke` → `ToolService.executeInferenceAndToolsLoop` → `DefaultRetrievalAugmentor.augment` → `ChatModel` default 方法 → `MessageWindowChatMemory`。这五个文件读懂，框架就没有秘密了。

## 12.3 概念对照表

**Spring ↔ LangChain4j**：

| Spring 概念 | LangChain4j 对应 | 备注 |
|---|---|---|
| spring-core | langchain4j-core | 统一抽象与工具层，零重依赖 |
| spring-context + aop | langchain4j 主模块 | 装配与动态代理 |
| Spring Data Repository / MyBatis Mapper | AiServices | 声明式接口编程模型 |
| `@Transactional` 的 AOP 链 | AiServices 十步流水线 | 框架固化横切流程 |
| FactoryBean（MapperFactoryBean） | `AiServiceFactory`（Spring 集成） | 接口→代理 Bean |
| `PlatformTransactionManager` + 多实现 | `ChatModel` + 26 个供应商 | 策略接口 + 适配器 |
| `@ConfigurationProperties` | starter 属性绑定 | `langchain4j.<provider>.*` |
| Micrometer Observation | `langchain4j-observation` | 直接对接 |
| WebInterceptor/Filter | Guardrail / 监听器 | 输入输出横切 |
| 分布式补偿（Saga） | `@CompensateFor` | 工具循环回滚 |

**Python LangChain/LangGraph ↔ LangChain4j**（对照姊妹篇《LangChain.md》《LangGraph.md》）：

| Python 侧 | Java 侧 | 差异要点 |
|---|---|---|
| Runnable 协议 + LCEL（`\|` 组合） | 无对应物 | Java 用接口代理，不用函数式组合；流水线隐式固定在 AiServices |
| `BaseChatModel._generate` | `ChatModel.doChat` | 同为 SPI 钩子，Python 靠抽象基类、Java 靠 default 方法 |
| `create_agent`（LangGraph 图） | `ToolService` 循环 / agentic 模式 | Java 版"图"退化为 while 循环与方法编排，无显式图 |
| `checkpointer` / `interrupt` | AgenticScope 持久化 / HITL 挂起恢复 | 概念同构，实现各有侧重 |
| `with_structured_output` | 返回类型 + JSON Schema 双路径 | Java 版直接由方法签名驱动，更类型化 |
| `langchain-community` | langchain4j-community 仓库 | 同为社区集成收容所 |
| LangSmith | 无官方对应（接 Langfuse/OpenTelemetry） | Java 侧可观测靠监听器+Micrometer 自建 |

---

## 全文完

**一页记忆卡**：LangChain4j = `ChatModel`（策略接口接模型）+ `AiServices`（接口代理做声明式）+ `ToolService`（while 循环做 Agent）+ `ChatMemory`（滑窗做上下文）+ 五段 RAG（接口流水线）+ agentic（方法编排做多智能体）+ SPI 与监听器（处处留口子）。骨架 2025-05 定型至今未变，学一次管一个时代。
