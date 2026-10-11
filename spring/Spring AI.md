# Spring AI 深度源码解析（写给初学者的架构全景）

> **本文基于的源码**：`D:\code\3rd\spring-ai`，版本 **2.1.0-SNAPSHOT**（main 分支，Git commit `00f230f9e`，2026-10-09；最新 GA 为 **v2.0.1**，2026-08-11 发布；2.1.0-M1 为 2026-09-20）。文中所有【源码证据】的文件路径与行号均为对该快照实际读取所得（涉及 1.0.0 旧版本的证据通过 `git show v1.0.0` 从本地 tag 实证，会显式标注）。
>
> **版本取舍说明**：Spring AI 是一条演进极快的产品线——0.8.0（2024-02）→ 1.0.0 GA（2025-05-19）→ 1.1.x（2025-11 起）→ **2.0.0（2026-06-12）**，当前 main（2.1.0-SNAPSHOT）已对齐 **Spring Boot 4.2.0-SNAPSHOT + Jackson 3**（根 `pom.xml` 第 240、253 行：`<java.version>17</java.version>`、`<spring-boot.version>4.2.0-SNAPSHOT</spring-boot.version>`）。**2.0 是一次不兼容的大版本**：工具执行循环从每个 ChatModel 中整体移出、函数式回调 `FunctionCallback` 移除、ChatMemory 默认 order 调整、模型适配器换装官方 SDK。本文以 2.x 现状为主线解析，所有"1.x 如何、2.x 如何"的结论均经本地 git 标签（v0.8.0 ~ v2.1.0-M1，共 48 个 tag）与官方 `upgrade-notes.adoc` 双重实证，1.x → 2.0 的迁移对照见 11.2 节。
>
> **阅读约定**：每章"先白话、后源码"——先用一两句人话讲清"这是什么、为什么需要它"，再给出源码证据链（`模块/src/main/java/.../类名.java` + 方法名 + 行号 + 代码片段）。行号只对该 2.1.0-SNAPSHOT 快照精确，读者用 IDEA 打开源码按类名 + 方法名定位即可。涉及 `spring-ai-model` 的证据统一以 `spring-ai-model` 开头，其余同理，路径均相对仓库根目录。

## 如何读这份文档

如果你是 Spring AI 初学者，推荐两遍读法：

- **第一遍（建立地图，1~2 小时）**：只读第一章（总览）每一节的开头白话段、各章"本章小结"、以及第十章（贯通视图）。目标是能回答：`ChatClient.prompt().user("...").call().content()` 这一行背后有哪几层抽象？工具循环到底跑在哪一层？ChatMemory 是在请求前还是请求后写入的？
- **第二遍（深入源码）**：对照每一章的【源码证据】，打开源码逐行读。顺序建议：第二章（消息模型与 2.1 的 MessagePart 变革）→ 第四章（ChatClient 与 Advisor 链，理解"一次调用怎么走"）→ 第五章（工具调用）→ 第三章（模型适配器，挑一个你用的厂商读）→ 第六章（记忆）→ 第七章（RAG 与向量库）→ 第八章（MCP，可跳读）→ 第九章（观测与 Boot 装配）。

---

# 一、总览：Spring AI 的定位、设计哲学与整体架构

## 1.1 一句话定位

**Spring AI 是"生成式 AI 应用"的 Spring 生态接入层：它把"聊天、嵌入、图像、音频"等几十家厂商模型调用收敛成几个可移植接口（`ChatModel`/`EmbeddingModel`/...），再把提示词组装、工具调用、对话记忆、RAG 检索、MCP 协议、结构化输出、可观测性做成一条可插拔的 Advisor 责任链，最后以 Spring Boot starter + 自动配置的方式一键装配。** 你声明"要什么"（接口、注解、配置），它负责"怎么调"（HTTP、重试、循环、观测）。

官方 README 对项目能力的自述是"Support for all major AI Model providers such as Anthropic, OpenAI, Amazon Bedrock, Google, Ollama, Mistral AI, DeepSeek, and more"——注意这句话的句式：**它列的是"支持谁"，而它的架构关心的是"怎么统一支持"**。这正是 Spring 生态一贯的配方（本系列《Spring Framework.md》1.2 节"适配而非重造"）：一个核心策略接口 + 若干厂商适配器 + 与容器/AOP/观测体系的打通。

它解决的不是"怎么发一个 HTTP 请求调 OpenAI"（那是各家 SDK 的事，2.x 的 OpenAI 适配器甚至直接换用了官方 `openai-java` SDK，见 3.4.2 节），而是**AI 应用的工程组织问题**：

1. **可移植**：业务代码不该绑定某家厂商——换模型只该改配置，不该改代码；
2. **可编排**：提示词组装、多轮工具调用循环、记忆读写、知识检索这些"每次都一样"的横切逻辑，不该散落在每个业务方法里；
3. **可观测**：token 用量、调用耗时、模型名这些运营数据，不该靠手埋日志；
4. **可装配**：几十个模块、上百个配置项，靠 Spring Boot 自动配置收敛成一个 starter 依赖。

## 1.2 设计哲学：读源码前先记住四句话

1. **一切皆模型调用：`Model<TReq, TRes>` 一个 `call` 走天下**。全项目的最顶层只有一个泛型接口 `Model`（`spring-ai-model/src/main/java/org/springframework/ai/model/Model.java` 第 35 行，`@since 0.8.0`），聊天、嵌入、图像、审核全部实现它。请求/响应也是两个极薄的载体：`Prompt`（消息列表 + 选项）与 `ChatResponse`（生成列表 + 元数据）。接口小到不能再小，具体差异全部下沉到各厂商模块。
2. **横切逻辑皆 Advisor：责任链是第一公民**。记忆、RAG、安全、日志、结构化输出——所有"在模型调用前后做点什么"的需求，都实现 `CallAdvisor`/`StreamAdvisor` 接口挂进一条链（第四章）。**2.0 最激进的一步是连"工具调用循环"本身也搬进了链内**（`ToolCallingAdvisor`，见 4.6.2 节）：模型适配器退回"只调一次 API"的纯粹角色，业务方由此获得了拦截每一轮工具循环的口子。
3. **注解进、回调出：POJO 方法即工具**。一个普通 Java 方法标上 `@Tool`，Spring AI 就把它翻译成 `ToolDefinition`（名称/描述/JSON Schema）发给大模型，模型回话时点它名字，框架反射调用再把结果回填（第五章）。同一个配方复制到了 MCP 服务器（`@McpTool`/`@McpPrompt`/`@McpResource`）——这是"适配而非重造"哲学在新领域的复刻。
4. **可观测是默认项，Boot 是总装车间**。ChatClient、ChatModel、Advisor、ToolCallingManager、VectorStore 五处全部内建 Micrometer `Observation`（第九章），命名对齐 OpenTelemetry `gen_ai.*` 语义约定（`gen_ai.client.operation`）；Boot 自动配置产出 **prototype 作用域的 `ChatClient.Builder`**，每个注入点拿到独立副本再定制（9.3 节）。

## 1.3 模块分层全景

仓库是 Maven 多模块工程，根 `pom.xml` 实测共 **168 个 `<module>`**。按"用户感知"分层如下（括号内为 main 分支 `src/main/java` 实测文件数）：

```
┌─────────────────────────── 生态外围（按需引入） ────────────────────────────┐
│  models/（15 个模型适配器：openai、anthropic、ollama、bedrock、bedrock-converse、
│           google-genai ×3、mistral-ai、deepseek、elevenlabs、stability-ai、
│           transformers、postgresml、vertex-ai-embedding）                    │
│  vector-stores/（22 个向量库：pgvector、redis、milvus、chroma、elasticsearch、│
│           neo4j、mongodb-atlas、opensearch、oracle、mariadb、couchbase、      │
│           qdrant、weaviate、pinecone、azure、gemfire、cassandra、s3、         │
│           typesense、bedrock-knowledgebase、coherence、redis-semantic-cache）│
│  document-readers/（4 个：pdf、tika、markdown、jsoup）                        │
│  memory-repositories/（5 个 ChatMemory 持久化：jdbc、redis、neo4j、mongodb、   │
│           cassandra）                                                        │
│  mcp/（4 个：common 客户端/服务端工具桥接、mcp-annotations 注解式服务器、        │
│           webflux/webmvc 两个 transport）                                    │
├─────────────────────────── 扩展能力层 ─────────────────────────────────────┤
│  advisors/（2 个：spring-ai-vector-store-advisor（QuestionAnswerAdvisor）、    │
│           spring-ai-tool-search-advisor（ToolSearchToolCallingAdvisor））     │
│  spring-ai-rag（模块化 RAG 管道，28 文件）  spring-ai-tool-search-tool         │
├─────────────────────────── 客户端层 ──────────────────────────────────────┤
│  spring-ai-client-chat（53 文件：ChatClient、Advisor 链、ChatMemory 编排）      │
├─────────────────────────── 模型抽象层 ────────────────────────────────────┤
│  spring-ai-model（253 文件：Model/ChatModel/EmbeddingModel、Message、Prompt、  │
│           ChatOptions、Tool 全家、ChatMemory 接口、观测约定）                  │
├─────────────────────────── 地基 ─────────────────────────────────────────┤
│  spring-ai-commons（61 文件：Document/ETL 三件套、TemplateRenderer、评测、      │
│           分词器）  spring-ai-template-st（StringTemplate 渲染，3 文件）        │
│  spring-ai-retry（重试语义）  spring-ai-test（向量库/Advisor 测试基类）          │
├─────────────────────────── Boot 总装 ────────────────────────────────────┤
│  auto-configurations/（约 60 个自动配置模块）  starters/（47 个 starter）        │
│  spring-ai-bom（BOM）  spring-ai-spring-boot-docker-compose / -testcontainers │
└───────────────────────────────────────────────────────────────────────────┘
```

一个立刻能看出设计取向的事实：**全部核心抽象只占 9 个模块、不到 450 个源文件，其余 130+ 个模块全是"适配器 + 自动配置 + starter"三件套**。抽象层极薄、生态层极宽——这与 Spring Data（spring-data-commons 对 N 个存储）、Spring Cloud（spring-cloud-commons 对 N 个基础设施）是同一种工程美学。

## 1.4 模块依赖图（以各模块 pom.xml 实证）

```
                 spring-ai-commons（Document、模板、评测、JTokkit）
                    ▲              （依赖 spring-context、micrometer-core、
                    │               context-propagation、Jackson）
              spring-ai-model（Model/ChatModel/Message/Tool/观测约定）
                    ▲        ▲     （依赖 micrometer-observation、reactor-core）
     spring-ai-vector-store │
        （依赖 model + antlr4）  spring-ai-client-chat（ChatClient/Advisor）
                    ▲                ▲          （依赖 model、reactor、jtokkit、
                    │                │           json-schema-validator、spring-context）
                    └── spring-ai-rag ┘
                    （依赖 client-chat + vector-store，横跨两侧）
   models/spring-ai-openai（依赖 model + com.openai:openai-java-core）
        ▲
   advisors/spring-ai-vector-store-advisor（依赖 client-chat + vector-store）
        ▲
   mcp/common（依赖 io.modelcontextprotocol.sdk:mcp + model）
```

真实依赖声明（摘自各模块 `pom.xml`，已逐个验证）：

| 模块 | 关键依赖（compile） |
|---|---|
| spring-ai-commons | spring-context、micrometer-core、context-propagation、micrometer-tracing、jackson-databind、jtokkit |
| spring-ai-model | spring-ai-commons、micrometer-observation、micrometer-tracing、reactor-core |
| spring-ai-client-chat | spring-ai-model、reactor-core、spring-context、jackson-dataformat-yaml、json-schema-validator（victools）、swagger-annotations-jakarta |
| spring-ai-vector-store | spring-ai-model（+ antlr4 运行时由插件生成解析器） |
| spring-ai-rag | spring-ai-client-chat、spring-ai-vector-store |
| models/spring-ai-openai | spring-ai-model、`com.openai:openai-java-core`（官方 SDK） |
| mcp/mcp-annotations | `io.modelcontextprotocol.sdk:mcp`（MCP Java SDK） |

这张表本身就是架构说明：**model 不依赖 client-chat，vector-store 不依赖 client-chat**（抽象层完全不知道"客户端链"的存在）；**rag 是唯一同时依赖 client-chat 与 vector-store 的模块**（它把"检索"接进"对话"）；**模型适配器只依赖 model**（一个适配器一个世界，与链无关）。

## 1.5 关键问题 → Spring AI 方案映射（全文导览）

| 生成式 AI 开发的关键问题 | Spring AI 的方案 | 详见 |
|---|---|---|
| 换个模型厂商就要重写接入代码 | `ChatModel`/`EmbeddingModel` 统一接口 + `ChatOptions` 8 个可移植属性 + 15 个厂商适配器 | 第二章、第三章 |
| 提示词拼接散落各处，模板变量无处安放 | `Prompt`（消息列表+选项）+ `ChatClient` fluent API + `PromptTemplate`/`StTemplateRenderer` | 第二章、第四章 |
| 想让大模型调用我的 Java 方法（天气、数据库、下单） | `@Tool` 注解 → `MethodToolCallback` → `ToolCallingManager` 执行 + `ToolCallingAdvisor` 链内循环 | 第五章 |
| 对话多轮无状态，历史要自己拼 | `ChatMemory` + `MessageWindowChatMemory`（默认 20 条窗口）+ `MessageChatMemoryAdvisor` | 第六章 |
| 大模型不知道我的私有知识（RAG） | `VectorStore` 抽象（22 个实现）+ `QuestionAnswerAdvisor`（最短路径）+ `RetrievalAugmentationAdvisor`（模块化七步管道） | 第七章 |
| 想要 JSON/对象返回，而不是自由文本 | `StructuredOutputConverter` 三件套（Bean/Map/List）+ `entity()` + 原生结构化输出/校验重试 | 第四章 4.7 |
| 工具太多，全部定义塞给模型既贵又乱 | `ToolSearchToolCallingAdvisor`（regex/lucene/vector 三种索引） | 第八章 8.3 |
| 跨进程复用工具/提示词/资源（MCP 协议） | mcp 模块（客户端桥接成 ToolCallback + 服务端注解式开发） | 第八章 8.1、8.2 |
| 出问题不知道模型叫什么、花了多少 token、 prompt 发了什么 | 五处 Micrometer Observation（`gen_ai.client.operation` 等）+ 可选 prompt/completion 内容落日志 | 第九章 |
| 几十个模块怎么引入、怎么配置 | Boot 自动配置 + 47 个 starter + prototype 作用域的 `ChatClient.Builder` | 第九章 9.3、9.4 |

## 1.6 版本演进：0.8 → 1.0 → 2.0 → 2.1

写作时（2026 年 10 月）的版本格局：**1.x（1.1.8，2026-06-12）与 2.x 并行维护**——1.1.x 对齐 Boot 3.5.x，2.x 对齐 Boot 4.x（README "Spring Boot Version Compatibility" 一节明示）。本节所有"特性属于哪个版本"的结论均经双重验证：**① 用本地仓库 48 个 git 标签对源码逐项 `git show`/`git ls-tree` 实证；② GA 日期取自各发布 tag 的提交信息并与官方 `upgrade-notes.adoc` 交叉核对**。

### 1.6.1 版本时间线与运行基线

| 版本 | tag 日期 | 运行基线 | 一句话主题 |
|---|---|---|---|
| 首次提交 | 2023-07-24（`1b1cd7158 first commit`） | — | 项目立项（Spring 首届 AI 相关孵化） |
| 0.8.0 / 0.8.1 | 2024-02-26 / 2024-03-12 | Boot 3.2 | 第一个公开可用版本：Chat/Embedding/Image 抽象 + VectorStore + Advisor 雏形 |
| 1.0.0-M1 | 2024-05-28 | — | API 大重构起步（ToolCallback 取代 FunctionCallback 的开端） |
| **1.0.0 GA** | **2025-05-19**（`fcd2803f3 Release version 1.0.0`） | Boot 3.4/3.5 | 首个 GA：ChatClient/Advisor 体系定型、@Tool 定型、MCP 接入、starter 更名（`spring-ai-starter-*`） |
| 1.1.0 | 2025-11-12 | Boot 3.5.x | 过渡版本：MCP SDK 升级、工具搜索 advisor 引入 |
| **2.0.0** | **2026-06-12** | **Boot 4.0、Java 17、Jackson 3** | **不兼容大版本**：工具循环移出 ChatModel、FunctionCallback 移除、官方 OpenAI SDK、Memory order 调整、删除 6 个小众模型模块 |
| 2.0.1 | 2026-08-11 | Boot 4.x | 安全收紧：请求级工具白名单（resolution fallback 默认关闭）、工具调用限流 |
| 2.1.0-M1 | 2026-09-20 | Boot 4.1/4.2 | **MessagePart 有序内容模型**（ReasoningPart/工具调用交错/OpaquePayload）+ 流式 parts 契约 |

### 1.6.2 特性引入/移除对照表（git 实证，可复现）

| 特性 | 引入 | 移除/变更 | 实证方式 |
|---|---|---|---|
| `Model`/`ChatModel` 顶层抽象 | 0.8.0 | — | `Model.java` 的 `@since 0.8.0` |
| `ToolCallback`/`@Tool` 体系 | 1.0.0-M 系列（1.0.0 定型） | 2.0.0 移除旧 `FunctionCallback` 全部类型 | `upgrade-notes.adoc` 894 行："Spring AI 1.0 introduced `ToolCallback` as the replacement for the older `FunctionCallback` API; 2.0 removes the deprecated `FunctionCallback` types" |
| `ChatClient` + `CallAdvisor`/`StreamAdvisor` 链 | 1.0.0 | 2.0.0 重设计（`DefaultChatMemoryPrecedenceOrder` 1000→200，新增 `ToolCallingAdvisor`） | `git show v1.0.0:spring-ai-client-chat/.../advisor/api/` 与 2.0 upgrade-notes 635 行 |
| MCP 支持 | 1.0.0（mcp 模块已存在） | 2.0.0 将 MCP Java SDK 1.1.x → 2.0.0 | 1.0.0 `git ls-tree` 顶层有 `mcp/`；upgrade-notes 1758 行 |
| ChatModel 内置工具执行循环 | 0.8.x~1.x（`internalToolExecutionEnabled`） | **2.0.0 全部移除**（OpenAI/Anthropic/Ollama/DeepSeek/Bedrock/MiniMax/Mistral/Google GenAI 八家确认） | upgrade-notes 804 行："In 2.0, this internal loop has been removed from every `ChatModel` implementation" |
| 手写 HTTP 层（`OpenAiApi` 内嵌 RestClient/WebClient） | 0.8~1.1 | 2.0.0 OpenAI 换官方 `com.openai:openai-java` | `git show v1.0.0:models/spring-ai-openai/.../api/OpenAiApi.java` 第 50-51、98-100 行 vs 2.x `OpenAiChatModel.java` 第 35 行 `import com.openai.client.OpenAIClient` |
| 模块 `memory/`（1.0） | 1.0.0 | 2.0.0 更名 `memory-repositories/` | `git ls-tree v1.0.0` vs `v2.0.0` 顶层目录 diff |
| 模型模块 azure-openai、huggingface、minimax、oci-genai、vertex-ai-gemini、zhipuai | 1.0.0 | **2.0.0 删除**（gemini 由新 `google-genai` 取代） | `diff <(git ls-tree v1.0.0 models/) <(git ls-tree v2.0.0 models/)` |
| `spring-ai-spring-cloud-bindings` | 1.0.0 | 2.0.0 删除 | 同上顶层 diff |
| `ToolSearchToolCallingAdvisor` + starter | 2.0.0（含自动配置 `spring-ai-autoconfigure-tool-search-advisor`） | — | upgrade-notes 605 行起 |
| `VectorStore.upsert(EmbeddedDocument)`（预计算向量写入） | 2.1.0 | — | `VectorStore.java` 第 78 行 `@since 2.1.0` |
| `MessagePart` 有序内容模型 + `ReasoningPart` + `OpaquePayload` | 2.1.0 | 废弃 `AbstractMessage.textContent` 字段、`AssistantMessage.media` 字段、各厂商 `isThought`/`reasoningContent` 元数据键 | `MessagePart.java` `@since 2.1.0`；upgrade-notes "Upgrading to 2.1.0" 全节 |
| `getOptions()`（替代 `getDefaultOptions()`） | 2.0.0 | 1.x 的 `getDefaultOptions()` 标记 `@Deprecated(forRemoval=true)` | `ChatModel.java` 第 50-62 行 |

### 1.6.3 1.x → 2.0 的五大主题

1. **职责归位：模型只管"调一次"**。1.x 里每个 ChatModel 内部自带"收到 tool call → 执行工具 → 再调模型"的循环；2.0 把这个循环整体搬进 `ToolCallingAdvisor`（客户端链内），`ChatModel.call()` 退化为"把 Prompt 翻译成厂商 API 请求并返回原始响应"。代价是直接使用 ChatModel 的用户要自己驱动循环（或用 `ToolCallingManager`），收益是循环的每一轮都可以被其他 Advisor 拦截（记忆、观测、限流）。
2. **词汇统一**：`FunctionCallback`/"function" 词汇全面退场，一律 "tool"（`toolCallbacks()` → 2.0 起改为 `tools(Object...)`，`toolCallbacks` 标记 `@Deprecated(since = "2.0.0", forRemoval = true)`，见 `ChatClient.java` 第 453-468 行）。
3. **HTTP 栈换代**：OpenAI 适配器从手写 RestClient/WebClient 切到官方 SDK；全项目从 Jackson 2（`com.fasterxml`）切到 Jackson 3（`tools.jackson`，证据：`MethodToolCallback.java` 第 29 行 `import tools.jackson.core.JacksonException`）。
4. **模块瘦身与更名**：删 6 个小众模型模块、删 spring-cloud-bindings；`memory/` → `memory-repositories/`；`spring-ai-advisors-vector-store` → `spring-ai-vector-store-advisor`。
5. **安全默认值收紧**：2.0.1 起，请求上没挂的工具（callback 列表之外的名字）默认**不再**允许通过 `ToolCallbackResolver` 兜底执行（`DefaultToolCallingManager` 的 `resolutionFallbackEnabled` 默认 `false`，`@since 2.0.1`，第 118-123、150 行）——模型幻觉出的工具名不再有执行通道。

### 1.6.4 2.1 的主题：MessagePart 有序内容模型

2.0 之前，`AssistantMessage` 是"text + toolCalls + media 三个平行列表"。但真实的模型响应是**有序交错**的：思考块后面紧跟它"论证"过的工具调用（Anthropic 的 thinking 签名、Gemini 的 thought signature、OpenAI 兼容服务器的 `reasoning_content`）。如果把它压平成三个列表，重放给厂商时顺序信息就丢了，带签名的思考块也就无法回传验证。

2.1 的解法（`upgrade-notes.adoc` "Upgrading to 2.1.0"）：消息内容改为**有序 `MessagePart` 列表**（sealed 接口：Text/Reasoning/ToolCall/ToolResult/Media/Unknown 六件套），`getText()`/`getToolCalls()`/`getReasoning()` 全部变成 parts 上的视图；未识别的厂商块落入 `UnknownPart` 而不是被丢弃；签名类数据放进 `OpaquePayload` 原样回放。详见 2.3 节。

## 1.7 全文章节地图

- **第二章 地基与消息模型**：`Model` 顶层抽象、`Message` 五类角色、2.1 的 `MessagePart` 变革、`Prompt`/`ChatOptions`、`ChatResponse`、commons 里的 ETL 三件套。
- **第三章 模型适配器层**：`ChatModel` 接口族、2.0 职责归位、OpenAI 适配器逐段精读（含流式合并、Responses API）、重试。
- **第四章 ChatClient 与 Advisor 链**：fluent API、`ChatClientRequest`/`Response`、`DefaultAroundAdvisorChain` 出栈式责任链、内置 Advisor 全景、结构化输出三路线。
- **第五章 工具调用体系**：`@Tool` → `ToolDefinition` → `MethodToolCallback` → `DefaultToolCallingManager` 全流程、限流与白名单、ToolContext、returnDirect。
- **第六章 对话记忆**：`ChatMemory` 双层抽象、`MessageChatMemoryAdvisor` 读写时机、持久化仓库与 2.1 的 spring-ai-session 展望。
- **第七章 RAG 与向量库**：ETL 管道、`VectorStore`/`SearchRequest`/Filter 表达式、`QuestionAnswerAdvisor`、`RetrievalAugmentationAdvisor` 七步。
- **第八章 MCP、工具搜索与外围**：MCP 三模块、注解式服务器、工具搜索、评测与测试支持。
- **第九章 观测体系与 Boot 装配**：五处 Observation、`gen_ai.*` 命名、`ChatClientAutoConfiguration` 逐段、starter/BOM 规律。
- **第十章 贯通视图**：启动时间线、一次 `call()` 的一生、一次工具调用的一生、设计模式视角。
- **第十一章 附录**：接口速查、迁移对照、源码入口清单、常见坑。

---

# 二、地基与消息模型（spring-ai-commons / spring-ai-model）

> 本章对应源码：`spring-ai-commons` 与 `spring-ai-model` 模块（根目录 `D:\code\3rd\spring-ai\spring-ai-commons`、`...\spring-ai-model`，源码位于 `src/main/java`）。以下所有【源码证据】中的行号均为实际读取 2.1.0-SNAPSHOT（main 分支）源码所得。

## 2.1 模块定位与包结构

用大白话说：**spring-ai-model 是"AI 领域的 spring-beans"——它定义全生态的名词表**（什么叫消息、什么叫提示词、什么叫工具、什么叫选项），而 spring-ai-commons 是"AI 领域的 spring-core"——提供文档 ETL、模板渲染、评测这些与模型无关的地基。

```
spring-ai-model（org.springframework.ai 下）
├── model/            # Model/ModelRequest/ModelResponse/ModelOptions 四个泛型顶层接口
├── chat/
│   ├── messages/     # Message 五件套 + part/（2.1 的 MessagePart 有序内容模型）
│   ├── prompt/       # Prompt、ChatOptions、PromptTemplate
│   ├── model/        # ChatModel、StreamingChatModel、ChatResponse、Generation、MessageAggregator
│   ├── memory/       # ChatMemory、ChatMemoryRepository、MessageWindowChatMemory
│   ├── metadata/     # ChatResponseMetadata、Usage、RateLimit
│   └── observation/  # ChatModel 观测上下文与约定
├── embedding/ image/ audio/ moderation/  # 其余模型类型的同构抽象
├── tool/             # ToolCallback/ToolDefinition/@Tool/@ToolParam/tool.method/tool.function
├── model/tool/       # ToolCallingChatOptions、ToolCallingManager、DefaultToolCallingManager
├── converter/        # StructuredOutputConverter 三件套
└── aot/              # GraalVM native hints

spring-ai-commons（org.springframework.ai 下）
├── document/         # Document、DocumentReader/Transformer/Writer、MetadataMode
├── transformer/splitter/  # TextSplitter、TokenTextSplitter
├── template/         # TemplateRenderer、NoOpTemplateRenderer、ValidationMode
├── evaluation/       # EvaluateRequest/Response、Evaluator
├── reader/ writer/   # 文件读写兜底实现
└── util/json/schema/ # JsonSchemaGenerator（tool 入参 JSON Schema 生成）
```

## 2.2 Model 顶层抽象：一个 call 走天下

【源码证据】`spring-ai-model/src/main/java/org/springframework/ai/model/Model.java` 第 35-45 行：

```java
public interface Model<TReq extends ModelRequest<?>, TRes extends ModelResponse<?>> {
	/**
	 * Executes a method call to the AI model.
	 */
	TRes call(TReq request);
}
```

整个 Spring AI 所有"模型"的最后一个公共父类就是这一个方法。`ModelRequest` 只约定两个 getter：`getInstructions()`（输入内容）与 `getOptions()`（选项）；`ModelResponse` 约定 `getResults()` 与 `getMetadata()`。**薄到极致的顶层抽象 + 具体类型下沉到子体系**，和 `BeanFactory` 之于 Spring 容器是同一种"最小接口原则"。

同构的子体系（均在 spring-ai-model 内，包路径略）：

| 子体系 | 请求 | 响应 | 顶层接口 |
|---|---|---|---|
| 聊天 | `Prompt` | `ChatResponse` | `ChatModel extends Model<Prompt, ChatResponse>, StreamingChatModel` |
| 嵌入 | `EmbeddingRequest` | `EmbeddingResponse` | `EmbeddingModel extends Model<EmbeddingRequest, EmbeddingResponse>` |
| 图像 | `ImagePrompt` | `ImageResponse` | `ImageModel` |
| 音频转写 / 语音合成 | `AudioTranscriptionPrompt` 等 | `AudioTranscriptionResponse` 等 | `AudioTranscriptionModel` / `TextToSpeechModel`（audio 包） |
| 内容审核 | `ModerationPrompt` | `ModerationResponse` | `ModerationModel` |

## 2.3 Message 五类角色与 2.1 的 MessagePart 变革

### 2.3.1 Message 与 MessageType

【源码证据】`spring-ai-model/.../chat/messages/Message.java` 第 30-37 行：

```java
public interface Message extends Content {
	MessageType getMessageType();
}
```

`Content`（spring-ai-commons 的 `content` 包）只带 `getText()` 与 `getMetadata()`。消息类型是四值枚举（`MessageType.java` 第 30-52 行）：`USER`、`ASSISTANT`、`SYSTEM`、`TOOL`——注意注释里写的历史包袱：`"It can be one of the following: USER, ASSISTANT, SYSTEM, FUNCTION"`，但实际第四个值早已是 `TOOL`（`ToolResponseMessage` 用它），注释没跟上 API 演进，读源码时别被误导。

五个具体类与各自用途：

| 类 | 角色 | 要点 |
|---|---|---|
| `SystemMessage` | 高层指令（人设、格式要求） | 一条对话通常一条，位置最前 |
| `UserMessage` | 用户输入 | 可携带 `Media`（图文混排） |
| `AssistantMessage` | 模型历史回复 | 可携带 `ToolCall`（模型要调工具）与推理内容 |
| `ToolResponseMessage` | 工具执行结果回传模型 | 内含 `ToolResponse(id, name, responseData)` 列表 |
| `MessageType.TOOL` | 上面那个的枚举值 | — |

### 2.3.2 AbstractMessage：parts 成为本体

2.1 起，`AbstractMessage` 的本体是一份**有序 `MessagePart` 列表**，文本只是视图：

【源码证据】`spring-ai-model/.../chat/messages/AbstractMessage.java` 第 88-100 行（parts 构造函数）：

```java
protected AbstractMessage(MessageType messageType, List<MessagePart> parts, Map<String, Object> metadata) {
	Assert.notNull(messageType, "Message type must not be null");
	Assert.notNull(parts, "Parts must not be null");
	Assert.notNull(metadata, "Metadata must not be null");
	this.parts = List.copyOf(parts);
	if (messageType == MessageType.SYSTEM || messageType == MessageType.USER) {
		Assert.notEmpty(this.parts, "Content must not be null for SYSTEM or USER messages");
	}
	this.messageType = messageType;
	this.textContent = joinText(this.parts);
	this.metadata = new HashMap<>(metadata);
	this.metadata.put(MESSAGE_TYPE, messageType);
}
```

三个值得注意的细节：

1. **`textContent` 字段被标记废弃**（第 56-57 行 `@Deprecated(since = "2.1.0", forRemoval = true)`），只保留让旧子类编译通过；`getText()` 的值由 `joinText(parts)` 派生（第 162-172 行：把所有 `TextPart` 无分隔符拼接）。一个只有推理或只有工具调用的消息，`getText()` 返回**空串**（不是 null）——流式聚合的 `map(getText).collect(joining())` 惯用法因此保持兼容。
2. **SYSTEM/USER 消息不允许空 parts**（第 93-95 行的 `Assert.notEmpty`）。
3. `equals()/hashCode()` 基于 parts 比较——媒体内容第一次参与相等性判断（upgrade-notes "Upgrading to 2.1.0"）。

### 2.3.3 MessagePart 六件套与 OpaquePayload

【源码证据】`spring-ai-model/.../chat/messages/part/MessagePart.java` 第 41-50 行：

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", visible = true, defaultImpl = UnknownPart.class)
@JsonSubTypes({ @JsonSubTypes.Type(value = TextPart.class, name = "text"),
		@JsonSubTypes.Type(value = ReasoningPart.class, name = "reasoning"),
		@JsonSubTypes.Type(value = ToolCallPart.class, name = "tool_call"),
		@JsonSubTypes.Type(value = ToolResultPart.class, name = "tool_result"),
		@JsonSubTypes.Type(value = MediaPart.class, name = "media"),
		@JsonSubTypes.Type(value = UnknownPart.class, name = "unknown") })
@JsonIgnoreProperties(ignoreUnknown = true)
public sealed interface MessagePart
		permits MediaPart, ReasoningPart, TextPart, ToolCallPart, ToolResultPart, UnknownPart {
```

设计上有三处精心安排：

1. **sealed 密封 + Jackson 多态序列化**：part 层次被框架收拢（"provider adapters live in the Spring AI repository and dispatch over the permitted types"，类注释第 32-34 行），但 `defaultImpl = UnknownPart.class` 保证**本版本不认识的 `type` 值反序列化成 UnknownPart 而不是报错**——向前兼容厂商新增的内容块。
2. **`OpaquePayload payload()`（第 58 行）**：注释明确其语义是 "Provider-owned data that must be replayed verbatim to the same provider, such as a thinking signature or encrypted reasoning"——Anthropic 思考签名、Gemini thought signature 这类"厂商的私有数据，框架看不懂但必须原样带回"的东西，有了统一安放处。
3. **`attributes()`（第 65 行）**：序列化安全的自由属性（流式部分索引、厂商 item id），与 payload 分离——payload 是"要回放给厂商的"，attributes 是"给框架自己用的"。

### 2.3.4 AssistantMessage：工具调用与推理的载体

【源码证据】`spring-ai-model/.../chat/messages/AssistantMessage.java` 第 111-121、154-156 行：

```java
public List<ReasoningPart> getReasoning() {
	return select(ReasoningPart.class).toList();
}

public List<ToolCall> getToolCalls() {
	return select(ToolCallPart.class).map(ToolCallPart::toolCall).toList();
}

public boolean hasToolCalls() {
	return getParts().stream().anyMatch(ToolCallPart.class::isInstance);
}
```

```java
public record ToolCall(String id, String type, String name, String arguments) {
}
```

`getReasoning()`/`getToolCalls()` 都是 parts 上的过滤视图——2.1 的 Javadoc（第 40-50 行）专门强调了价值："a message preserves the exact interleaving some providers stream between reasoning, tool calls, text and media (for example a reasoning block immediately followed by the tool call it justified)"。

Builder 还有一个人性化的防呆（第 222-229、241-243 行）：子类 builder 如果重写了 `build()` 却仍走旧构造函数（会丢 parts），`part()` 会直接抛 `UnsupportedOperationException`，除非子类显式声明 `supportsParts() == true` 并改用 `buildParts()`——**宁可让子类编译/运行期报错，也不悄悄丢数据**。

## 2.4 Prompt 与 ChatOptions：一次请求的说明书

### 2.4.1 Prompt

【源码证据】`spring-ai-model/.../chat/prompt/Prompt.java` 第 47-51 行：

```java
public class Prompt implements ModelRequest<List<Message>> {
	private final List<Message> messages;
	private final @Nullable ChatOptions chatOptions;
```

Prompt = **消息列表 + 选项**，不可变，7 个构造器重载 + Builder + `mutate()`/`copy()`。它还内置了几个"消息考古"工具方法：`getSystemMessage()`（第一条系统消息，找不到给空的，第 106-114 行）、`getUserMessage()`（**最后**一条用户消息，倒序找，第 120-128 行）、`getLastUserOrToolResponseMessage()`（第 134-142 行，记忆 Advisor 写历史时用它判断"本轮该存谁"）。以及两个增强方法 `augmentSystemMessage(...)`/`augmentUserMessage(...)`（第 228-284 行）——链尾的 `ChatModelCallAdvisor` 做结构化输出指令追加、RAG Advisor 做上下文拼接，用的都是它们（见 4.6.1、7.4 节）。

### 2.4.2 ChatOptions：8 个可移植旋钮

【源码证据】`spring-ai-model/.../chat/prompt/ChatOptions.java` 第 29-77 行（接口方法清单）：

```java
public interface ChatOptions extends ModelOptions {
	@Nullable String getModel();
	@Nullable Double getFrequencyPenalty();
	@Nullable Integer getMaxTokens();
	@Nullable Double getPresencePenalty();
	@Nullable List<String> getStopSequences();
	@Nullable Double getTemperature();
	@Nullable Integer getTopK();
	@Nullable Double getTopP();
	...
}
```

这 8 个属性是"各厂商最大公约数"——`OpenAiChatOptions`/`AnthropicChatOptions` 等都会扩展出几十个厂商专属属性，但用户面向 `ChatOptions` 编程时可移植。Builder 端还有一个容易被忽略的 `combineWith(other)`（第 169 行）："takes all other's values that are non-null, retaining this other values"——**非空覆盖合并**的语义原语。

**2.x 的选项解析顺序比 1.x 简单了很多：请求级 options 完整覆盖模型默认 options，不再做字段级 merge。** 证据在 OpenAI 适配器：

【源码证据】`models/spring-ai-openai/.../OpenAiChatModel.java` 第 1563-1570 行（`buildRequestPrompt`）：

```java
private Prompt buildRequestPrompt(Prompt prompt) {
	if (prompt.getOptions() == null) {
		return prompt.mutate().chatOptions(this.getOptions()).build();
	}
	else {
		return prompt;
	}
}
```

`Prompt` 带了 options 就全用它，没带才落模型默认——没有 1.x 时代的 `fromOptions + merge` 字段级合并。（工具回调列表的合并工具 `ToolCallingChatOptions.mergeToolCallbacks` 在 2.x 主代码中已无调用点，只作为静态工具保留。）想改模型默认选项中的一个字段，正确姿势是 `model.getOptions().mutate().temperature(0.1).build()` 后挂到 Prompt 上。

### 2.4.3 ToolCallingChatOptions：选项的"工具扩展"

【源码证据】`spring-ai-model/.../model/tool/ToolCallingChatOptions.java` 第 39-50、69-101 行：

```java
public interface ToolCallingChatOptions extends ChatOptions {
	@Nullable List<ToolCallback> getToolCallbacks();
	@Nullable Map<String, Object> getToolContext();
	...
}
```

它把"本轮允许模型调哪些工具 + 传给工具的上下文"塞进选项对象，从而**与 ChatModel 接口解耦**——模型调用链的任何一层（Advisor、Manager、适配器）只要检查 `prompt.getOptions() instanceof ToolCallingChatOptions` 就能拿到工具。接口还附赠三个静态工具方法：`mergeToolCallbacks`（运行时优先）、`mergeToolContext`（深合并：默认 + 运行时）、`validateToolCallbacks`（重名工具直接抛 `IllegalStateException`，第 92-101 行）。

## 2.5 ChatResponse / Generation / 元数据

【源码证据】`spring-ai-model/.../chat/model/ChatResponse.java` 第 41-48、92-97、111-116 行：

```java
public class ChatResponse implements ModelResponse<Generation> {
	private final ChatResponseMetadata chatResponseMetadata;
	private final List<Generation> generations;
```

```java
public @Nullable Generation getResult() {
	if (CollectionUtils.isEmpty(this.generations)) {
		return null;
	}
	return this.generations.get(0);
}
```

```java
public boolean hasToolCalls() {
	if (CollectionUtils.isEmpty(this.generations)) {
		return false;
	}
	return this.generations.stream().anyMatch(generation -> generation.getOutput().hasToolCalls());
}
```

`Generation` = 一个候选输出（`AssistantMessage` 输出 + `ChatGenerationMetadata` 的 finishReason 等）。`hasToolCalls()` 是全框架工具循环的**总开关判定**——2.0 的 `ToolCallingAdvisor` 默认的 `ToolExecutionEligibilityChecker` 就是它（4.6.2 节）。2.1 起非流式响应收敛为"一个 Generation"（Anthropic/Bedrock/Google 的思考块不再是独立 Generation，而是消息内的 `ReasoningPart`），`getResult()` 因此重新变得可靠。

`ChatResponseMetadata` 携带 `id`、`model`、`Usage`（promptTokens/completionTokens/totalTokens）、`RateLimit`（OpenAI 从 `x-ratelimit-*` 头解析，`OpenAiChatModel.java` 第 290-297 行注释与 `OpenAiRateLimit.from(rawResponse.headers())`）。

## 2.6 commons 里的 ETL 与模板地基

RAG 的"数据搬运"侧全在 commons：

- **`Document`**（`document/Document.java`）：正文 + `DocumentMetadata`（文件名、作者、自定义键值）+ 可选 id。四个读写接口 `DocumentReader`/`DocumentWriter`/`DocumentTransformer` 构成 ETL 三件套——4 个 document-readers 模块（pdf/tika/markdown/jsoup）实现 Reader，22 个向量库模块实现 Writer（`VectorStore extends DocumentWriter`），切分器实现 Transformer。
- **`TextSplitter`/`TokenTextSplitter`**（`transformer/splitter/`）：按 token 数切分（token 计数用 `jtokkit`，commons 依赖清单可见）。
- **`TemplateRenderer`**（`template/`）：提示词模板渲染 SPI，默认实现在 spring-ai-template-st（StringTemplate 4，`StTemplateRenderer`，只有 3 个文件）。`{var}` 占位符 + `ST_VALIDATION`（严格模式：未知变量即抛错）与 `ALLOW` 两种 `ValidationMode`。
- **评测**（`evaluation/`）：`Evaluator` 接口 + `EvaluationRequest/Response`；client-chat 侧有两个开箱实现 `RelevancyEvaluator`（回答与问题相关性，让模型自己评）与 `FactCheckingEvaluator`（事实核查）。

## 2.7 本章小结

- 全项目唯一的顶层抽象是 `Model<TReq, TRes>`（一个 `call`）；ChatModel/EmbeddingModel/ImageModel 是同构展开。
- 消息模型在 2.1 完成"三个平行列表 → 有序 MessagePart 列表"的转型：`ReasoningPart` 让思考可回放（配 `OpaquePayload` 签名），`UnknownPart` 让未识别厂商块不丢失，`getText()` 退化为视图但保持兼容语义。
- `Prompt` = 消息列表 + `ChatOptions`；2.x 起选项解析是"请求级整体覆盖模型默认"，没有字段级 merge。
- `ChatResponse.hasToolCalls()` 是工具循环的总开关；`ToolCallingChatOptions` 把工具列表与上下文挂进选项对象，从而与 ChatModel 接口解耦。
- commons 提供 ETL（Document/Reader/Transformer/Writer + TokenTextSplitter）、模板 SPI（StTemplateRenderer）、评测（Evaluator）三块与模型无关的地基。

---

# 三、模型适配器层（models/）

> 本章对应源码：`spring-ai-model` 的 `chat/model` 包与 `models/` 目录下各适配器（以 `models/spring-ai-openai` 为精读样例）。

## 3.1 模块定位：15 个适配器一个配方

2.1.0-SNAPSHOT 的 `models/` 目录有 15 个模块：anthropic、bedrock、bedrock-converse（Converse 通用协议）、deepseek、elevenlabs、google-genai（聊天）、google-genai-embedding、google-genai-image、mistral-ai、ollama、openai、postgresml、stability-ai、transformers（本地推理）、vertex-ai-embedding。

每个适配器的内部配方完全一致，以 openai 为例（`find models/spring-ai-openai/src/main/java` 结果）：

```
org/springframework/ai/openai/
├── OpenAiChatModel.java（2087 行）        # 聊天
├── OpenAiChatOptions.java                 # 厂商专属选项（几十个属性）
├── OpenAiEmbeddingModel/Options.java      # 嵌入
├── OpenAiImageModel/Options.java          # 图像
├── OpenAiAudioSpeechModel/Options.java    # 语音合成
├── OpenAiAudioTranscriptionModel/...      # 转写
├── OpenAiModerationModel/...              # 审核
├── metadata/                              # RateLimit 等响应元数据
├── responses/                             # OpenAI Responses API（2.x 新增）
│   ├── OpenAiResponsesChatModel.java
│   └── ...（ItemMapper/RequestBuilder/StreamAssembler）
├── http/okhttp/                           # SDK HttpClient 定制口
└── setup/                                 # Azure OpenAI 等部署形态适配
```

一个模型模块 = N 种模型类型 × 1 个厂商 SDK 翻译层 × 1 套 Observation 接线。**业务代码只见 `ChatModel`，见不到这些类**——除非要厂商专属选项。

## 3.2 ChatModel / StreamingChatModel 接口族

【源码证据】`spring-ai-model/.../chat/model/ChatModel.java` 第 30-67 行（完整逻辑，注释略）：

```java
public interface ChatModel extends Model<Prompt, ChatResponse>, StreamingChatModel {

	default @Nullable String call(String message) {
		Prompt prompt = new Prompt(new UserMessage(message));
		Generation generation = call(prompt).getResult();
		return (generation != null) ? generation.getOutput().getText() : "";
	}

	default @Nullable String call(Message... messages) {
		Prompt prompt = new Prompt(Arrays.asList(messages));
		Generation generation = call(prompt).getResult();
		return (generation != null) ? generation.getOutput().getText() : "";
	}

	@Override
	ChatResponse call(Prompt prompt);

	default ChatOptions getOptions() {
		return ChatOptions.builder().build();
	}

	@Deprecated(forRemoval = true)
	default ChatOptions getDefaultOptions() {
		return getOptions();
	}

	default Flux<ChatResponse> stream(Prompt prompt) {
		throw new UnsupportedOperationException("streaming is not supported");
	}
}
```

【源码证据】`StreamingChatModel.java` 第 29-51 行：

```java
@FunctionalInterface
public interface StreamingChatModel extends StreamingModel<Prompt, ChatResponse> {
	default Flux<String> stream(String message) { ... }

	@Override
	Flux<ChatResponse> stream(Prompt prompt);
}
```

要点：

- `call(String)`/`call(Message...)` 是语法糖，最终都走 `call(Prompt)`——接口自带"最短路径"。
- `stream(Prompt)` 有默认实现：**直接抛 `UnsupportedOperationException`**（第 64-66 行）。不支持流式的厂商实现 `ChatModel` 即可，支持流式的实现 `StreamingChatModel` 并覆盖。这与 1.x 相同，是"按需扩权"接口链的又一次落地。
- `getOptions()` 自 2.0.0 成为标准方法（`@since 2.0.0`，第 50 行），旧名 `getDefaultOptions` 进入废弃倒计时。

## 3.3 2.0 的关键变化：ChatModel 不再执行工具

这是全文最重要的版本事实，值得单独立节。1.x 的 OpenAiChatModel 内部有完整的工具循环；2.0 起：

【源码证据】官方升级说明 `spring-ai-docs/src/main/antora/modules/ROOT/pages/upgrade-notes.adoc` 第 804 行：

> "In 2.0, this internal loop has been removed from every `ChatModel` implementation (OpenAI, Anthropic, Ollama, DeepSeek, Bedrock, MiniMax, Mistral, Google GenAI). `ChatModel.call(prompt)` and `ChatModel.stream(prompt)` now return the model's raw response — tool calls are not executed automatically."

因此在 2.x，`ChatModel` 的语义是"**一次**厂商 API 调用"：模型说"我要调工具"，`ChatResponse.hasToolCalls() == true` 返回给你，工具由谁执行、循环几轮、失败怎么办——全部上移到客户端层（`ToolCallingAdvisor`，第四章）或用户自己（配 `ToolCallingManager` 手写循环）。这个决定让模型适配器变得极薄（OpenAiChatModel 从 1.0 的 2000+ 行瘦身到 2.1 的 2087 行，但循环代码没有了，多出来的是 Responses API 与 parts 映射），也让"在工具循环的每一轮插入记忆/观测/过滤"从不可能变成一行 order 配置。

## 3.4 OpenAiChatModel 样例：一次 call 的完整路径

### 3.4.1 call → buildRequestPrompt → internalCall

【源码证据】`models/spring-ai-openai/.../OpenAiChatModel.java` 第 257-310 行（核心逻辑）：

```java
@Override
public ChatResponse call(Prompt prompt) {
	Prompt requestPrompt = buildRequestPrompt(prompt);
	verifyPromptChatOptions(requestPrompt);
	return this.internalCall(requestPrompt);
}

private ChatResponse internalCall(Prompt prompt) {
	ChatCompletionCreateParams request = this.createRequest(prompt, false);
	RequestOptions requestOptions = this.buildRequestOptions(prompt);

	ChatModelObservationContext observationContext = ChatModelObservationContext.builder()
		.prompt(prompt)
		.provider(AiProvider.OPENAI.value())
		.build();

	return ChatModelObservationDocumentation.CHAT_MODEL_OPERATION
		.observation(this.observationConvention, DEFAULT_OBSERVATION_CONVENTION, () -> observationContext,
				this.observationRegistry)
		.observe(() -> {
			// The raw response carries the x-ratelimit-* headers alongside the parsed
			// body; the plain create(...) call discards them.
			HttpResponseFor<ChatCompletion> rawResponse = this.openAiClient.chat()
				.completions()
				.withRawResponse()
				.create(request, requestOptions);
			ChatCompletion chatCompletion = rawResponse.parse();
			RateLimit rateLimit = OpenAiRateLimit.from(rawResponse.headers());
			...
			ChatResponse chatResponse = toChatResponse(chatCompletion, request, rateLimit);
			observationContext.setResponse(chatResponse);
			return chatResponse;
		});
}
```

读法：**options 兜底 → 选项校验（比如 topK 不被 OpenAI 支持就告警，第 698-704 行）→ 构造厂商请求对象 → 套一层 Observation → 调 SDK → 把厂商响应翻译成 `ChatResponse`**。适配器的全部智力都花在两头的翻译上：`Prompt(消息+选项)` → `ChatCompletionCreateParams`（`createRequest`，第 712 行起），`ChatCompletion` → `ChatResponse`（`toChatResponse` → `buildGeneration`）。

### 3.4.2 官方 SDK 换装：从手写 RestClient 到 openai-java

1.0.0 时代，OpenAI 适配器自带一个 `api/OpenAiApi.java`，用 Spring 的 RestClient/WebClient 手写全部端点：

```java
// git show v1.0.0:models/spring-ai-openai/src/main/java/org/springframework/ai/openai/api/OpenAiApi.java
import org.springframework.web.client.RestClient;      // 第 50 行
import org.springframework.web.reactive.function.client.WebClient;  // 第 51 行
private final RestClient restClient;                    // 第 98 行
private final WebClient webClient;                      // 第 100 行
```

2.x 换成官方 `com.openai:openai-java` SDK（`OpenAiChatModel.java` 第 29 行 pom 依赖 `openai-java-core`，第 35-39 行 imports）。两个后果值得注意：

1. **厂商 SDK 有自己的 Jackson**：`OpenAiChatModel.java` 第 199-204 行注释——"The OpenAI SDK's own Jackson 2 mapper, the one the SDK (de)serializes its types with"——项目整体已切 Jackson 3（`tools.jackson`），但厂商 SDK 类型仍按 Jackson 2 序列化，框架在边界处用 `ObjectMappers.jsonMapper()` 精确往返。这就是"适配器 = 隔离层"的直接证据：**Jackson 2/3 混用的复杂性被锁死在适配器内部**。
2. OpenAI 之外的适配器策略不一：Ollama/DeepSeek 等仍走 HTTP 客户端直连，Anthropic/Bedrock 各有其 SDK。选型原则是"谁官方就用谁"，而不是统一自研。

### 3.4.3 流式：ChunkMerger 与 StreamAccumulator

【源码证据】`OpenAiChatModel.java` 第 339-361 行（internalStream 核心逻辑）：

```java
private Flux<ChatResponse> internalStream(Prompt prompt) {
	return Flux.deferContextual(contextView -> {
		ChatCompletionCreateParams request = this.createRequest(prompt, true);
		...
		StreamAccumulator streamAccumulator = new StreamAccumulator();
		final ChatModelObservationContext observationContext = ChatModelObservationContext.builder()
			.prompt(prompt).provider(AiProvider.OPENAI.value()).streaming(true).build();
		Observation parentObservation = contextView.getOrDefault(ObservationThreadLocalAccessor.KEY, null);
		Observation observation = startStreamObservation(observationContext, parentObservation);

		Flux<ChatResponse> chatResponses = mergeToolCallChunks(streamChunks(request, requestOptions))
			.map(chatCompletion -> toStreamedChatResponse(chatCompletion, request, streamAccumulator));

		Flux<ChatResponse> observedResponses = chatResponses.doOnError(observation::error)
			.doFinally(s -> observation.stop())
			.contextWrite(ctx -> ctx.put(ObservationThreadLocalAccessor.KEY, observation));

		return new MessageAggregator().aggregate(observedResponses, observationContext::setResponse);
	});
}
```

三个流式难点都给出了框架级答案：

1. **工具调用分片**：OpenAI 流式协议把一个 tool call 拆成多个 delta（按 index 增量拼 JSON 参数），`mergeToolCallChunks`（第 410-422 行的 `ChunkMerger`）把属于同一个 tool call 的 chunk 合并后再发给上层——上层永远看到"完整的工具调用"，不需要自己拼参数。
2. **观测跨流存活**：`MessageAggregator().aggregate(...)` 把流聚合成"最后一个完整响应"喂给 ObservationContext（token 用量只在最后一个 chunk 里）。
3. **2.1 的流式 parts 契约**：`StreamAccumulator`（第 1579 行起）为每个响应维护 `StreamingPartIndexer`，流式 chunk 携带**带索引的部分 parts**（推理增量、文本增量），`MessageAggregator` 按索引重建完整 parts——推理增量从此是一等公民（upgrade-notes "New: Streaming Contract for Parts"）。

### 3.4.4 Responses API

2.x 新增 `responses/` 包：`OpenAiResponsesChatModel` 对接 OpenAI 新一代 Responses API（区别于 Chat Completions），含 `ResponsesItemMapper`/`ResponsesRequestBuilder`/`ResponsesStreamAssembler`。对用户透明——它同样实现 `ChatModel`，切换只是换一个 bean。这印证了架构红利：**新增一个"API 形态"不需要动客户端层一行代码**。

## 3.5 重试（spring-ai-retry）

`spring-ai-retry` 模块只有 4 个文件：`RetryUtils`、`TransientAiException`、`NonTransientAiException`、package-info。语义很克制：**429/5xx 这类瞬态错误**（`TransientAiException`）值得重试，**401/400 这类确定性错误**（`NonTransientAiException`）重试无意义。各适配器用 Spring Retry 的 `RetryTemplate` 包住 HTTP 调用，Boot 侧有对应自动配置（`auto-configurations/common/spring-ai-autoconfigure-retry`，属性前缀 `spring.ai.retry.*`）。

## 3.6 本章小结

- 模型适配器的配方：N 种模型类型 × 厂商 SDK 翻译层 × Observation 接线；业务代码只见 `ChatModel`。
- `ChatModel` 接口自带语法糖（`call(String)`）与按需扩权（`stream` 默认抛不支持）；`getOptions()` 自 2.0 成为标准入口。
- **2.0 起工具循环不在 ChatModel 里**——它只负责"调一次 API + 翻译请求/响应"，循环在客户端链（第四章）。
- OpenAI 适配器 2.x 换装官方 SDK，Jackson 2/3 混用被隔离在适配器内；流式的三个难点（工具分片合并、观测聚合、2.1 parts 契约）都有框架级答案。
- 重试语义只对瞬态错误生效，确定性失败不重试。

---

# 四、ChatClient 与 Advisor 链（spring-ai-client-chat）

> 本章对应源码：`spring-ai-client-chat` 模块（根目录 `D:\code\3rd\spring-ai\spring-ai-client-chat`）。这是 2.x 用户日常接触最多的模块。

## 4.1 模块定位与包结构

用大白话说：**spring-ai-client-chat 是"AI 调用的 RestTemplate/RestClient"——一个流式 API 把提示词组装、Advisor 拦截、模型调用、结果解析串成一串**；它同时是 Advisor 责任链的宿主（"AI 调用的 spring-aop"）。

```
org.springframework.ai.chat.client
├── ChatClient.java（595 行）          # fluent API 接口
├── DefaultChatClient.java（1252 行）  # 实现：DefaultChatClientRequestSpec 等
├── DefaultChatClientBuilder.java      # Builder 实现
├── ChatClientRequest.java             # 链上流转的"请求"（prompt + context）
├── ChatClientResponse.java            # 链上流转的"响应"（chatResponse + context）
├── ChatClientAttributes.java          # context 里的标准键（OUTPUT_FORMAT 等）
├── ChatClientMessageAggregator.java   # 流式聚合
├── advisor/
│   ├── api/                           # Advisor/CallAdvisor/StreamAdvisor/BaseAdvisor/链接口
│   ├── DefaultAroundAdvisorChain.java # 责任链实现（296 行）
│   ├── ChatModelCallAdvisor / ChatModelStreamAdvisor  # 链尾
│   ├── ToolCallingAdvisor.java（645 行）              # 工具循环（2.0 新增）
│   ├── MessageChatMemoryAdvisor / SimpleLoggerAdvisor / SafeGuardAdvisor
│   └── observation/                   # Advisor 观测
└── observation/                       # ChatClient 级观测
```

## 4.2 ChatClient fluent API 全景

【源码证据】`spring-ai-client-chat/.../ChatClient.java` 第 386-494 行（`ChatClientRequestSpec` 的方法清单，节选）：

```java
interface ChatClientRequestSpec {
	ChatClientRequestSpec advisors(Advisor... advisors);
	ChatClientRequestSpec messages(Message... messages);
	<B extends ChatOptions.Builder<?>> ChatClientRequestSpec options(B customizer);
	ChatClientRequestSpec tools(Object... tools);
	ChatClientRequestSpec toolContext(Map<String, Object> toolContext);
	ChatClientRequestSpec system(String text);
	ChatClientRequestSpec user(String text);
	ChatClientRequestSpec templateRenderer(TemplateRenderer templateRenderer);
	CallResponseSpec call();
	StreamResponseSpec stream();
}
```

一个典型用法对应到内部结构：

```java
String answer = chatClient.prompt()                    // DefaultChatClientRequestSpec
    .system("你是一个严谨的助手")                        // systemText
    .user("今天天气如何？")                              // userText + 模板参数
    .tools(new WeatherTools())                          // 塞进 ToolCallingChatOptions
    .advisors(new SimpleLoggerAdvisor())                // 挂 advisor
    .call()                                             // buildAdvisorChain() + DefaultCallResponseSpec
    .content();                                         // 走链 + 取文本
```

`tools(Object...)`（第 447 行）的 Javadoc（第 408-424 行）值得精读——它接受**异构混装**并自动分派：`ToolCallback` 直接注册、`ToolCallbackProvider` 延迟到请求时解析、`Collection`/数组逐个展开、**其他任何对象按 `@Tool` 注解 POJO 处理**。2.0 之前要把 POJO 先转 `ToolCallback[]`，2.0 起"给什么都能吃"。（旧的 `toolCallbacks(...)` 三兄弟已标记 `@Deprecated(since = "2.0.0", forRemoval = true)`，第 453-468 行。）

Builder 侧（第 499-593 行）对称提供 `defaultSystem/defaultUser/defaultOptions/defaultTools/defaultAdvisors/defaultToolContext`——默认值与请求值的关系见 4.5 节链装配。

**`call()` 与 `stream()` 每次都会现场构建一条新的 Advisor 链**（`DefaultChatClient.java` 第 1176-1187 行：`call()`/`stream()` 里都调 `buildAdvisorChain()`）——链是请求级的，不是客户端级的，因此无并发共享问题。

## 4.3 ChatClientRequest / ChatClientResponse：链上流转的数据

链上流转的不再是裸 `Prompt`，而是一对载体：

```java
// ChatClientRequest.java：prompt() + context() 两个核心部分
public class ChatClientRequest { ... }   // 不可变，mutate() 派生修改
public class ChatClientResponse { ... }  // chatResponse + context
```

`context()` 是一个 `Map<String, Object>`，链上 Advisor 之间靠它传"带外语义"：标准键如 `ChatClientAttributes.OUTPUT_FORMAT`（结构化输出格式指令）、`STRUCTURED_OUTPUT_SCHEMA`/`STRUCTURED_OUTPUT_NATIVE`（原生结构化输出），RAG 的 `rag_document_context`、QA 的 `qa_retrieved_documents`、记忆的 `chat_memory_conversation_id`（`ChatMemory.CONVERSATION_ID`）都塞在这里。**Advisor 间通信协议 = context map 的键约定**——这是设计上很轻的选择，也是排查问题时要盯住的地方。

## 4.4 Advisor 接口族与 BaseAdvisor

【源码证据】`spring-ai-client-chat/.../advisor/api/Advisor.java`、`CallAdvisor.java`、`StreamAdvisor.java`（拼合）：

```java
public interface Advisor extends Ordered {
	int DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER = Ordered.HIGHEST_PRECEDENCE + 200;
	String getName();
}

public interface CallAdvisor extends Advisor {
	ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain);
}

public interface StreamAdvisor extends Advisor {
	Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain);
}
```

接口面（2.0 重设计后）比 1.x 简单了一半：**Call 侧一个方法，Stream 侧一个方法**。1.x 的 `RequestResponseAdvisor`/`CallAroundAdvisor` 等中间层已清空。

`BaseAdvisor` 提供模板方法式的默认实现：

【源码证据】`spring-ai-client-chat/.../advisor/api/BaseAdvisor.java` 第 42-98 行（核心逻辑）：

```java
public interface BaseAdvisor extends CallAdvisor, StreamAdvisor {

	Scheduler DEFAULT_SCHEDULER = Schedulers.boundedElastic();

	default ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
		ChatClientRequest processedChatClientRequest = before(chatClientRequest, callAdvisorChain);
		ChatClientResponse chatClientResponse = callAdvisorChain.nextCall(processedChatClientRequest);
		return after(chatClientResponse, callAdvisorChain);
	}

	default Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
			StreamAdvisorChain streamAdvisorChain) {
		Flux<ChatClientResponse> chatClientResponseFlux = Mono.just(chatClientRequest)
			.publishOn(getScheduler())
			.map(request -> this.before(request, streamAdvisorChain))
			.flatMapMany(streamAdvisorChain::nextStream);

		return chatClientResponseFlux.map(response -> {
			if (AdvisorUtils.onFinishReason().test(response)) {
				response = after(response, streamAdvisorChain);
			}
			return response;
		}).onErrorResume(error -> Flux.error(new IllegalStateException("Stream processing failed", error)));
	}

	ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain);
	ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain);
	default Scheduler getScheduler() { return DEFAULT_SCHEDULER; }
}
```

自己写 Advisor 时 90% 的场景只需实现 `BaseAdvisor` 的 `before`/`after`。三个流式细节：

1. **`before()` 在 `boundedElastic` 上执行**（`publishOn(getScheduler())`）——阻塞式检索（查向量库、查记忆）不卡 event loop；想换线程池就覆盖 `getScheduler()`。
2. **`after()` 只在 finishReason 出现时执行**（`AdvisorUtils.onFinishReason()` 谓词）——流是几十个 chunk，"响应结束"才做一次收尾（写记忆、放元数据），不会每 chunk 做一遍。
3. adviseCall 侧的 `before → nextCall → after` 就是 Spring `HandlerInterceptor` 的 before/after 结构。

## 4.5 DefaultAroundAdvisorChain：出栈式责任链

### 4.5.1 装配与排序

【源码证据】`spring-ai-client-chat/.../advisor/DefaultAroundAdvisorChain.java` 第 65-71、247-287 行：

```java
private final List<CallAdvisor> originalCallAdvisors;
private final List<StreamAdvisor> originalStreamAdvisors;
private final Deque<CallAdvisor> callAdvisors;     // ConcurrentLinkedDeque
private final Deque<StreamAdvisor> streamAdvisors;
```

Builder 的 `pushAll(List<Advisor>)`（第 247-272 行）把混入的 Advisor 按 `instanceof` 分成两条链（Call 一条、Stream 一条），然后 `reOrder()`（第 277-287 行）用 `OrderComparator` 按 order 升序排列（`addLast`）。**order 越小越先执行（先处理请求、后处理响应）**——与 Spring MVC 拦截器的 order 语义一致。

### 4.5.2 nextCall / nextStream 与观测

【源码证据】`DefaultAroundAdvisorChain.java` 第 97-121 行（nextCall 完整逻辑）：

```java
@Override
public ChatClientResponse nextCall(ChatClientRequest chatClientRequest) {
	Assert.notNull(chatClientRequest, "the chatClientRequest cannot be null");

	if (this.callAdvisors.isEmpty()) {
		throw new IllegalStateException("No CallAdvisors available to execute");
	}

	var advisor = this.callAdvisors.pop();

	var observationContext = AdvisorObservationContext.builder()
		.advisorName(advisor.getName())
		.chatClientRequest(chatClientRequest)
		.order(advisor.getOrder())
		.build();

	return AdvisorObservationDocumentation.AI_ADVISOR
		.observation(this.observationConvention, DEFAULT_OBSERVATION_CONVENTION, () -> observationContext,
				this.observationRegistry)
		.observe(() -> {
			var chatClientResponse = advisor.adviseCall(chatClientRequest, this);
			observationContext.setChatClientResponse(chatClientResponse);
			return chatClientResponse;
		});
}
```

执行模型是**出栈（pop）式**：每个 Advisor 拿到链本身，调 `callAdvisorChain.nextCall(request)` 即"弹出下一个并执行自己后面的链"。链尾必须有终止者（`ChatModelCallAdvisor`），否则 `nextCall` 在空栈时抛 `IllegalStateException`（第 101-103 行）。每个 Advisor 的执行都包在 `AI_ADVISOR` Observation 里——观测是链机制自带的，Advisor 作者不用管。

`nextStream`（第 124-166 行）多做了两件事：从 Reactor Context 恢复父 Observation 并用 `openScope()` 短暂打开，让 Micrometer tracing 推导出正确的 span 父子关系（第 143-155 行注释详细解释了为什么不能依赖自动传播——servlet HTTP span 会"鸠占鹊巢"）；用 `ChatClientMessageAggregator` 聚合流后再设置观测结果。

### 4.5.3 copy(after)：循环重入的钥匙

【源码证据】`DefaultAroundAdvisorChain.java` 第 168-195 行：

```java
@Override
public CallAdvisorChain copy(CallAdvisor after) {
	return this.copyAdvisorsAfter(this.getCallAdvisors(), after);
}

private DefaultAroundAdvisorChain copyAdvisorsAfter(List<? extends Advisor> advisors, Advisor after) {
	int afterAdvisorIndex = advisors.indexOf(after);
	if (afterAdvisorIndex < 0) {
		throw new IllegalArgumentException("The specified advisor is not part of the chain: " + after.getName());
	}
	var remainingStreamAdvisors = advisors.subList(afterAdvisorIndex + 1, advisors.size());
	return DefaultAroundAdvisorChain.builder(this.getObservationRegistry())
		.observationConvention(this.observationConvention)
		.pushAll(remainingStreamAdvisors)
		.build();
}
```

`copy(after)` 返回"**从 after 之后开始**的子链"。这是 `ToolCallingAdvisor` 实现循环的核心机关：循环的每一轮调 `callAdvisorChain.copy(this).nextCall(...)`——即"重新走一遍我后面的链"，而不是从头走。没有这个方法，工具循环每轮都会把前面的 Advisor（比如记忆）重复执行一遍。

### 4.5.4 装配处：buildAdvisorChain 与自动注册

【源码证据】`DefaultChatClient.java` 第 1189-1248 行（完整逻辑，注释保留）：

```java
private BaseAdvisorChain buildAdvisorChain() {
	autoRegisterToolCallingAdvisor();
	validateSingleToolAdvisor();

	// At the stack bottom add the model call advisors.
	// They play the role of the last advisors in the advisor chain.
	List<Advisor> chain = new ArrayList<>(this.advisors);
	chain.add(ChatModelCallAdvisor.builder().chatModel(this.chatModel).build());
	chain.add(ChatModelStreamAdvisor.builder().chatModel(this.chatModel).build());

	return DefaultAroundAdvisorChain.builder(this.observationRegistry)
		.observationConvention(this.advisorObservationConvention)
		.pushAll(chain)
		.build();
}

private void autoRegisterToolCallingAdvisor() {
	boolean autoRegisterDisabled = Boolean.FALSE
		.equals(this.advisorParams.get(ChatClientAttributes.TOOL_CALLING_ADVISOR_AUTO_REGISTER.getKey()));
	if (autoRegisterDisabled) {
		return;
	}
	boolean hasToolCallingAdvisor = this.advisors.stream().anyMatch(a -> a instanceof ToolAdvisor);
	if (hasToolCallingAdvisor) {
		return;
	}
	int configuredOrder = this.toolCallingAdvisorBuilder.getAdvisorOrder();
	boolean hasDownstreamMemoryAdvisor = this.advisors.stream()
		.anyMatch(a -> a instanceof MemoryAdvisor && a.getOrder() > configuredOrder);
	this.advisors.add(this.toolCallingAdvisorBuilder.copy()
		.conversationHistoryEnabled(!hasDownstreamMemoryAdvisor)
		.build());
}

private void validateSingleToolAdvisor() {
	List<Advisor> toolAdvisors = this.advisors.stream().filter(a -> a instanceof ToolAdvisor).toList();
	if (toolAdvisors.size() > 1) {
		...throw new IllegalStateException("At most one ToolAdvisor is allowed in the advisor chain, ...");
	}
}
```

三条规则：**① ChatModelCallAdvisor/ChatModelStreamAdvisor 总是被追加在链底**（终止者）；**② ToolCallingAdvisor 自动注册**——即使本次请求没有静态配置工具也会挂上，因为"别的 Advisor 可能在链中间动态注入工具"（注释第 1207-1210 行）；可用 `AdvisorParams.toolCallingAdvisorAutoRegister(false)` 关闭；**③ 全链最多一个 ToolAdvisor**，多了直接抛异常——两个循环会打架。另外注意 `conversationHistoryEnabled(!hasDownstreamMemoryAdvisor)`：如果记忆 Advisor 排在工具循环后面（order 更大），工具循环内部的"每轮历史管理"就交给记忆 Advisor，避免双方重复管理历史。

## 4.6 内置 Advisor 全景

### 4.6.1 ChatModelCallAdvisor：链尾终止者

【源码证据】`spring-ai-client-chat/.../advisor/ChatModelCallAdvisor.java` 第 52-64、103-111 行：

```java
@Override
public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
	ChatClientRequest formattedChatClientRequest = augmentWithFormatInstructions(chatClientRequest);
	ChatResponse chatResponse = this.chatModel.call(formattedChatClientRequest.prompt());
	return ChatClientResponse.builder()
		.chatResponse(chatResponse)
		.context(Map.copyOf(formattedChatClientRequest.context()))
		.build();
}
```

```java
@Override
public int getOrder() {
	return Ordered.LOWEST_PRECEDENCE;
}
```

链尾做两件事：**先**把结构化输出的格式指令补进请求（`augmentWithFormatInstructions`，第 66-101 行：原生结构化输出走 `StructuredOutputChatOptions.outputSchema(...)` 塞选项，否则把 `OUTPUT_FORMAT` 文本追加到用户消息），**然后**调用 `chatModel.call(...)` 并把结果包成 `ChatClientResponse`。它 order 是 `LOWEST_PRECEDENCE`——永远最后一个处理请求、第一个处理响应。`ChatModelStreamAdvisor` 是流式孪生版。

### 4.6.2 ToolCallingAdvisor：把工具循环搬进链内（2.0 核心新增）

这是 2.0 重设计的核心类，`@since 2.0.0`，645 行，同时实现 `CallAdvisor`、`StreamAdvisor`、`ToolAdvisor`：

【源码证据】`spring-ai-client-chat/.../advisor/ToolCallingAdvisor.java` 第 65-78 行：

```java
public class ToolCallingAdvisor implements CallAdvisor, StreamAdvisor, ToolAdvisor {

	/**
	 * Default advisor order. Higher than
	 * {@link Advisor#DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER} (+200) so that memory
	 * advisors at their default order are placed outside this advisor and do not
	 * participate in each tool-call iteration.
	 */
	public static final int DEFAULT_ORDER = Ordered.HIGHEST_PRECEDENCE + 300;

	protected static final ToolExecutionEligibilityChecker DEFAULT_TOOL_EXECUTION_ELIGIBILITY_CHECKER =
			chatResponse -> chatResponse != null && chatResponse.hasToolCalls();
```

【源码证据】同文件第 122-220 行（adviseCall 主循环，节选保留关键注释）：

```java
@Override
public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
	ChatOptions options = chatClientRequest.prompt().getOptions();
	if (!(options instanceof ToolCallingChatOptions toolCallingChatOptions)) {
		// No tool calling options - skip the tool calling advisor
		return callAdvisorChain.nextCall(chatClientRequest);
	}

	chatClientRequest = this.doInitializeLoop(chatClientRequest, callAdvisorChain);

	var instructions = chatClientRequest.prompt().getInstructions();
	// Always the complete history for this turn, ...
	var fullTurnHistory = instructions;

	ChatClientResponse chatClientResponse = null;
	UsageAccumulator usageAccumulator = new UsageAccumulator();
	boolean isToolCall = false;

	do {
		// Before Call
		var processedChatClientRequest = ChatClientRequest.builder()
			.prompt(new Prompt(instructions, toolCallingChatOptions))
			.context(chatClientRequest.context())
			.build();

		// Next Call
		processedChatClientRequest = this.doBeforeCall(processedChatClientRequest, callAdvisorChain);
		...
		chatClientResponse = callAdvisorChain.copy(this).nextCall(processedChatClientRequest);

		chatClientResponse = this.doAfterCall(chatClientResponse, callAdvisorChain);

		ChatResponse chatResponse = chatClientResponse.chatResponse();
		usageAccumulator.addRoundResponse(chatResponse);
		isToolCall = this.toolExecutionEligibilityChecker.isToolCallResponse(chatResponse);

		if (isToolCall) {
			ToolExecutionResult toolExecutionResult;
			try {
				toolExecutionResult = this.toolCallingManager
					.executeToolCalls(new Prompt(fullTurnHistory, toolCallingChatOptions), chatResponse);
			}
			catch (ToolCallLimitExceededException ex) {
				// Return the breach as a single generation - directly to the
				// application client instead of looping back to the LLM.
				chatClientResponse = chatClientResponse.mutate()
					.chatResponse(ChatResponse.builder()
						.from(chatResponse)
						.generations(List.of(ex.buildGeneration()))
						.build())
					.build();
				break;
			}

			fullTurnHistory = toolExecutionResult.conversationHistory();

			if (toolExecutionResult.returnDirect()) {
				// Return tool execution result directly to the application client
				// instead of returning it to the LLM.
				...
				break;
			}

			instructions = this.doGetNextInstructionsForToolCall(processedChatClientRequest, chatClientResponse,
					toolExecutionResult);
		}
	}
	while (isToolCall); // loop until no tool calls are present

	chatClientResponse = usageAccumulator.applyAccumulatedUsage(chatClientResponse);
	return this.doFinalizeLoop(chatClientResponse, callAdvisorChain);
}
```

逐点拆解这个循环：

1. **准入判定**：请求选项不是 `ToolCallingChatOptions`（例如没挂工具、模型不支持工具）→ 直接放行到下一个 Advisor（第 127-131 行）。
2. **每轮重走"我后面"的链**：`callAdvisorChain.copy(this).nextCall(...)`（第 167 行）——记忆 Advisor 若 order 更小（在链上更靠前/更外层），只在进入循环前执行一次；若 order 更大（在循环内），每轮工具调用都会触发它。**order 200（记忆默认）/300（工具循环默认）这对常数的含义就在这里**。
3. **累计 token**：`UsageAccumulator` 把每轮（包括中间轮）的 usage 加总，覆盖到最终响应——否则调用方只看到最后一轮的 token 数。
4. **限流熔断**：`ToolCallLimitExceededException` 被捕获后不再回喂模型，而是把超限信息作为一个 Generation 直接返回给应用（第 184-194 行）——详见 5.4 节。
5. **returnDirect**：工具声明 `returnDirect=true` 时，执行结果直接返回给应用，不再发给模型总结（第 198-209 行）。
6. **可扩展钩子**：`doInitializeLoop`/`doBeforeCall`/`doAfterCall`/`doFinalizeLoop`/`doGetNextInstructionsForToolCall` 五个 protected 方法 + `ToolExecutionEligibilityChecker`（"什么样的响应算要调工具"，默认 `hasToolCalls()`）是 2.0 换掉的扩展点（`ToolExecutionEligibilityPredicate` 已删除，upgrade-notes 688 行）。

流式侧（`adviseStream`）用 Reactor 表达同样的循环：聚合流 → 判断是否有 tool call → 执行 → 以更新后的历史重新发起子链调用，直到没有工具调用为止，工具调用的父 Observation 通过 `ToolCallReactiveContextHolder` 跨线程传递（`DefaultToolCallingManager.java` 第 306-311 行）。

### 4.6.3 MessageChatMemoryAdvisor：记忆的读写时机

【源码证据】`spring-ai-client-chat/.../advisor/MessageChatMemoryAdvisor.java` 第 73-107、137-148 行（before 完整逻辑，注释保留）：

```java
@Override
public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
	String conversationId = getConversationId(chatClientRequest.context());

	// 1. Retrieve the chat memory for the current conversation.
	List<Message> memoryMessages = this.chatMemory.get(conversationId);

	// 2. Advise the request messages list.
	List<Message> promptMessages = chatClientRequest.prompt().getInstructions();
	List<Message> processedMessages = new ArrayList<>();
	if (!isMemoryAlreadyInPrompt(promptMessages, memoryMessages)) {
		processedMessages.addAll(memoryMessages);
	}
	processedMessages.addAll(promptMessages);

	// 2.1. Ensure system message, if present, appears first in the list.
	for (int i = 0; i < processedMessages.size(); i++) {
		if (processedMessages.get(i) instanceof SystemMessage) {
			Message systemMessage = processedMessages.remove(i);
			processedMessages.add(0, systemMessage);
			break;
		}
	}

	// 3. Create a new request with the advised messages.
	ChatClientRequest processedChatClientRequest = chatClientRequest.mutate()
		.prompt(chatClientRequest.prompt().mutate().messages(processedMessages).build())
		.build();

	// 4. Add the new user message to the conversation memory.
	Message userMessage = processedChatClientRequest.prompt().getLastUserOrToolResponseMessage();
	this.chatMemory.add(conversationId, userMessage);

	return processedChatClientRequest;
}

@Override
public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
	List<Message> assistantMessages = chatClientResponse.chatResponse().getResults().stream()
		.map(g -> (Message) g.getOutput())
		.toList();
	this.chatMemory.add(this.getConversationId(chatClientResponse.context()), assistantMessages);
	return chatClientResponse;
}
```

记忆时序：**请求前**读历史拼进消息（若 prompt 已含相同历史则不重复——`isMemoryAlreadyInPrompt` 做前缀比对，第 109-134 行，防止工具循环内的重复追加），并把本轮用户消息写入记忆；**响应后**把助手回复写入记忆。会话 id 从 context 的 `chat_memory_conversation_id` 键取（`BaseChatMemoryAdvisor.getConversationId`）。

### 4.6.4 其余内置 Advisor 一览

| Advisor | 作用 | order 默认 |
|---|---|---|
| `SimpleLoggerAdvisor` | 请求/响应打日志（debug 级），`Function<ChatClientRequest,String>`/`Function<ChatResponse,String>` 两个格式化器可换（第 42-46 行） | 0 |
| `SafeGuardAdvisor` | 敏感词拦截：命中就直接返回固定的委婉拒答文本，根本不调模型（第 40-64 行） | 0（想要最先执行就配小 order） |
| `StructuredOutputValidationAdvisor` | `entity(type, spec -> spec.validateSchema())` 时自动挂上：校验模型 JSON 是否符合 schema，不符则把错误信息回喂重试（默认 3 次） | 链内 mutate 注入（`DefaultChatClient.java` 第 579-585 行） |
| `LastMaxTokenSizeContentPurger` | 历史超限时裁剪内容 | — |
| `VectorStoreChatMemoryAdvisor`（advisors 模块） | 把历史以向量形式存取（语义检索式记忆） | — |

## 4.7 结构化输出三路线

`entity(Class<T>)` 把自由文本变成对象。三条实现路线（`DefaultChatClient.doSingleWithBeanOutputConverter`，第 588-617 行）：

1. **默认：提示词工程**。`BeanOutputConverter` 生成目标类型的 JSON Schema 文本，塞进 context 的 `OUTPUT_FORMAT` 键；链尾 `ChatModelCallAdvisor.augmentWithFormatInstructions` 把它追加到用户消息；模型回复后 `outputConverter.convert(stringResponse)` 反序列化（内部带 Markdown 代码块清洗、`<think>` 标签清洗——`converter/` 包的 `MarkdownCodeBlockCleaner`/`ThinkingTagCleaner`/`CompositeResponseTextCleaner`）。
2. **原生：`useProviderStructuredOutput()`**（`ChatClient.EntityParamSpec`，第 202-234 行）。Schema 不再进提示词，而是作为 API 级约束（`STRUCTURED_OUTPUT_SCHEMA` 键 → `StructuredOutputChatOptions.outputSchema(...)`）。Javadoc 明确警告了各厂商的坑：Ollama 带推理模型可能回纯文本、OpenAI Structured Outputs 不接受顶层 JSON 数组。
3. **校验重试：`validateSchema()`**。挂 `StructuredOutputValidationAdvisor`，校验失败把 schema 违例信息作为反馈重发（默认最多 3 次），流式不支持。

## 4.8 本章小结

- ChatClient = fluent 门面；`call()`/`stream()` 每次现场建链（请求级链），链 = 用户 Advisor + 自动注册的 ToolCallingAdvisor + 链尾 ChatModelCallAdvisor/ChatModelStreamAdvisor。
- Advisor 接口面极小（adviseCall/adviseStream）；`BaseAdvisor` 给 before/after 模板 + boundedElastic 调度 + finishReason 门控。
- `DefaultAroundAdvisorChain` 是出栈式责任链 + Advisor 级观测 + `copy(after)` 子链——后者是工具循环"每轮重走后半链"的机关。
- `ToolCallingAdvisor`（order 300）把工具循环搬进链内；order 200 的记忆 Advisor 默认在循环外；五个 protected 钩子 + EligibilityChecker 是定制点。
- 结构化输出三路线：提示词注入（默认）、API 原生约束（`useProviderStructuredOutput`）、校验重试（`validateSchema`）。

---

# 五、工具调用体系（Tool Calling）

> 本章对应源码：`spring-ai-model` 的 `tool/` 与 `model/tool/` 包。2.x 词汇已全面统一为 "tool"（1.x 的 "function" 只存在于废弃 API 与 OpenAI 协议字段名里）。

## 5.1 四个抽象：@Tool / ToolDefinition / ToolCallback / ToolCallbackProvider

从注解到执行的四层抽象：

【源码证据】`spring-ai-model/.../tool/annotation/Tool.java` 第 34-69 行：

```java
@Target({ ElementType.METHOD, ElementType.ANNOTATION_TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Tool {
	String name() default "";                 // 缺省用方法名
	String description() default "";          // 给模型看的"这个工具是干嘛的"
	boolean returnDirect() default false;     // 结果直接返回应用，不再回喂模型
	Class<? extends ToolCallResultConverter> resultConverter() default DefaultToolCallResultConverter.class;
}
```

Javadoc 里的命名建议值得照抄："use only alphanumeric characters, underscores, hyphens, and dots in tool names"——空格和括号会让某些模型直接翻车。

【源码证据】`spring-ai-model/.../tool/definition/ToolDefinition.java` 第 25-41 行：

```java
public interface ToolDefinition {
	String name();          // 工具集内唯一
	String description();   // 模型据此决定何时/如何调用
	String inputSchema();   // 参数 JSON Schema
}
```

【源码证据】`spring-ai-model/.../tool/ToolCallback.java` 第 33-70 行（核心）：

```java
public interface ToolCallback {
	ToolDefinition getToolDefinition();
	default ToolMetadata getToolMetadata() { return ToolMetadata.builder().build(); }
	String call(String toolInput);
	default String call(String toolInput, @Nullable ToolContext toolContext) {
		if (toolContext != null && !toolContext.getContext().isEmpty()) {
			if (logger.isInfoEnabled()) {
				logger.info("By default the tool context is not used, override the method ...");
			}
		}
		return call(toolInput);
	}
}
```

`ToolDefinition` 是"给模型看的名片"（会出现在发给厂商 API 的 tools 数组里）；`ToolCallback` 是"真正干活的执行器"。两者分离意味着**广告与实现可以解耦**——MCP 客户端就是把远端服务器的 ToolDefinition 广告 + 远程调用包装成本地 ToolCallback（8.1 节）。

`ToolCallbackProvider` 是工具集合的懒加载供应者（`StaticToolCallbackProvider` 是唯一自带实现，其余由 MCP/注解扫描提供）。三种"造工具"的姿势：

| 姿势 | 入口 | 适用 |
|---|---|---|
| 注解方法 | `@Tool` 标在 POJO 方法上，`tools(obj)` 自动生成 `MethodToolCallback` | 99% 业务场景 |
| 函数式 | `FunctionToolCallback.builder("name", function|supplier|consumer)...build()`（`FunctionToolCallback.java` 第 139-174 行，三种函数形态各有 builder） | Lambda 工具 |
| 手写 | 直接实现 `ToolCallback` | 完全控制（MCP 桥接就是这样做的） |

工具类到回调的批量转换：`ToolCallbacks.from(Object... sources)`（`spring-ai-model/.../support/ToolCallbacks.java` 第 33-35 行）= `MethodToolCallbackProvider.builder().toolObjects(sources).build().getToolCallbacks()`——扫描所有 `@Tool` 方法（含父类），重复工具名直接报错。JSON Schema 由 `util/json/schema/JsonSchemaGenerator` 从方法签名/`@ToolParam` 生成。

## 5.2 MethodToolCallback：注解方法的执行

【源码证据】`spring-ai-model/.../tool/method/MethodToolCallback.java` 第 95-123 行（call 完整逻辑，注释略）：

```java
@Override
public String call(String toolInput, @Nullable ToolContext toolContext) {
	Assert.hasText(toolInput, "toolInput cannot be null or empty");
	...
	this.validateToolContextSupport(toolContext);

	Map<String, Object> toolArguments = this.extractToolArguments(toolInput);
	Assert.state(toolArguments != null, "toolArguments must not be null");

	Object[] methodArguments = this.buildMethodArguments(toolArguments, toolContext);

	Object result = this.callMethod(methodArguments);
	...
	Type returnType = this.toolMethod.getGenericReturnType();
	return this.toolCallResultConverter.convert(result, returnType);
}
```

四个步骤，每步都有可定制的缝：

1. **参数反序列化**：`extractToolArguments` 把模型给的 JSON 串解析成 `Map<String, Object>`；解析失败包装成 `ToolExecutionException`（第 134-144 行）。
2. **参数装配**：`buildMethodArguments`（第 148-156 行）按方法参数名从 map 取值并转型；**参数类型为 `ToolContext` 的位置直接注入 toolContext**——这是"会话上下文（租户、用户凭证）不经过模型"的通道（见 5.5 节）。
3. **反射调用**：`callMethod`（第 179-195 行）处理非 public 类/方法的 `setAccessible`，`InvocationTargetException` 的 cause 包装成 `ToolExecutionException`。
4. **结果转字符串**：`ToolCallResultConverter.convert(result, returnType)`——默认实现把对象 JSON 序列化（`DefaultToolCallResultConverter`），`@Tool(resultConverter = ...)` 可换。

工具方法抛出的业务异常由 `ToolExecutionExceptionProcessor` 决定去向：默认行为是**把异常消息作为工具结果回给模型**（让模型自己决定怎么向用户解释），而不是炸掉整次调用（`tool/execution/DefaultToolExecutionExceptionProcessor`）。

## 5.3 DefaultToolCallingManager.executeToolCalls 全流程

`ToolCallingManager` 是"模型说要调工具"与"工具真的被执行"之间的总管，两个方法：`resolveToolDefinitions`（把选项里的 callback 翻译成发给模型的定义）与 `executeToolCalls`（执行）。默认实现是理解工具体系的钥匙：

【源码证据】`spring-ai-model/.../model/tool/DefaultToolCallingManager.java` 第 177-205 行（executeToolCalls）：

```java
@Override
public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
	Assert.notNull(prompt, "prompt cannot be null");
	Assert.notNull(chatResponse, "chatResponse cannot be null");

	Optional<Generation> toolCallGeneration = chatResponse.getResults()
		.stream()
		.filter(g -> !CollectionUtils.isEmpty(g.getOutput().getToolCalls()))
		.findFirst();

	if (toolCallGeneration.isEmpty()) {
		throw new IllegalStateException("No tool call requested by the chat model");
	}

	AssistantMessage assistantMessage = toolCallGeneration.get().getOutput();

	ToolContext toolContext = buildToolContext(prompt, assistantMessage);

	InternalToolExecutionResult internalToolExecutionResult = executeToolCall(prompt, assistantMessage,
			toolContext);

	List<Message> conversationHistory = buildConversationHistoryAfterToolExecution(prompt.getInstructions(),
			assistantMessage, internalToolExecutionResult.toolResponseMessage());

	return ToolExecutionResult.builder()
		.conversationHistory(conversationHistory)
		.returnDirect(internalToolExecutionResult.returnDirect())
		.build();
}
```

核心动作：从响应中找到带 tool calls 的 Generation → 执行（`executeToolCall`）→ **把"原对话 + 助手工具调用消息 + 工具结果消息"拼成新历史**返回（第 345-351 行）——这个 `conversationHistory` 就是 `ToolCallingAdvisor` 下一轮循环喂给模型的输入。

执行环节（第 221-343 行）按 toolCall 逐个处理，值得注意的细节：

- **空参数兜底**：流式模式下参数可能为空，空参统一按 `"{}"` 处理并告警（第 273-284 行）。
- **callback 查找**：先在请求的 callback 列表里按名字精确匹配；找不到时**默认**抛 `IllegalStateException`（并打一条很有用的警告："LLM may have adapted the tool name '...'，especially if the name was truncated due to length limits"，第 86-88 行常量）——除非显式开启 resolution fallback（见 5.4）。
- **returnDirect 语义**：多个工具调用时，`returnDirect = 所有调用的 returnDirect 按位与`（第 299-304 行）——只要有一个工具要回喂模型，整批就都回喂。
- **观测**：每次工具执行包在 `TOOL_CALL` Observation 里；父 Observation 在响应式模式下从 `ToolCallReactiveContextHolder`（Reactor Context）取，阻塞模式退回线程当前 Observation（第 306-311 行注释解释了两种模式的差异）。

## 5.4 安全阀：ToolCallLimits 与 resolutionFallback

2.0.1/2.1 加入的两道安全阀（都在 `DefaultToolCallingManager`）：

【源码证据】`DefaultToolCallingManager.java` 第 92-107、248-269 行：

```java
/**
 * Default cap applied to every tool name unless overridden ...
 * Generous enough for legitimate iterative tool use (pagination, retries)
 * while still catching a runaway loop long before it becomes expensive.
 */
public static final int DEFAULT_MAX_CALLS_PER_TOOL = 40;

/**
 * Default cap on the total number of tool calls, across all tools combined,
 * allowed within a turn, unless disabled entirely ...
 */
public static final int DEFAULT_MAX_TOTAL_TOOL_CALLS = 150;
```

```java
ToolCallLimits.Breach limitBreach = this.toolCallLimits.check(toolName, toolCallCount, totalToolCallCount);
if (limitBreach != null) {
	toolResponses.add(new ToolResponseMessage.ToolResponse(toolCall.id(), toolName, limitBreach.message()));

	if (this.toolCallLimits.onLimitExceeded() == ToolCallLimitBehavior.THROW) {
		...
		throw new ToolCallLimitExceededException(limitBreach.toolName(), limitBreach.limit(),
				partialToolExecutionResult);
	}

	// ToolCallLimitBehavior.RETURN_ERROR_RESPONSE: skip invoking this tool
	// call but keep processing the rest of the batch.
	continue;
}
```

- **单轮限流**：每个工具默认一轮内最多 40 次、全部工具合计 150 次（builder 可按工具覆盖、可豁免、可关闭），超限后 `THROW`（抛 `ToolCallLimitExceededException`，`ToolCallingAdvisor` 接住直接返回给应用）或 `RETURN_ERROR_RESPONSE`（跳过该次调用继续处理批次）。
- **请求级白名单**：`resolutionFallbackEnabled` 默认 `false`（`@since 2.0.1`）——工具回调的解析范围**绑定在本次请求挂载的列表**上，模型幻觉出一个没挂载的工具名不会触发兜底解析。这是从"默认宽松"到"默认收紧"的安全转身（upgrade-notes 574 行："the tools attached to a request ... are the only tools that can be executed for that request"）。

## 5.5 ToolContext 与 returnDirect

**ToolContext**：`Prompt` 选项里的 `toolContext(Map)` → `DefaultToolCallingManager.buildToolContext`（第 207-216 行）→ `ToolCallback.call(input, toolContext)` → `MethodToolCallback` 注入到类型为 `ToolContext` 的方法参数。核心设计意图（`ToolContext` Javadoc）：**给工具传"用户不可见的会话数据"**（当前 HTTP 请求的安全上下文、租户 id）——这些内容不进模型提示词，因此不可被提示注入。注意 `MethodToolCallback.validateToolContextSupport`（第 125-132 行）：方法声明了 ToolContext 参数但调用时没传非空 context，直接抛 `IllegalArgumentException`——**提前失败而非静默空上下文**。

**returnDirect**：`@Tool(returnDirect = true)` 声明"我的结果就是最终答案"。语义从 1.x 沿用，2.x 在链内循环里表现为短路（4.6.2 节第 198-209 行）。典型场景：检索类工具（查数据库返回报表）不需要模型再润色一遍。

## 5.6 本章小结

- 四层抽象：`@Tool`（声明）→ `ToolDefinition`（给模型的名片）→ `ToolCallback`（执行器）→ `ToolCallingManager`（调度 + 执行 + 拼历史）；三种造工具姿势（注解/函数式/手写）殊途同归到 ToolCallback。
- `MethodToolCallback.call` 四步：JSON 参数解析 → 按参数名装配（ToolContext 特殊注入）→ 反射调用 → 结果转字符串；异常默认转成给模型看的文字。
- `DefaultToolCallingManager.executeToolCalls` 的产出是"追加过工具消息的新对话历史"，这正是链内循环的下一轮输入。
- 两道安全阀：单轮工具调用限流（40/150，可配可关）与请求级工具白名单（2.0.1 起默认收紧）。
- ToolContext 传会话数据不进模型；returnDirect 让结果直达应用。

---

# 六、对话记忆（ChatMemory）

> 本章对应源码：`spring-ai-model` 的 `chat/memory` 包（接口与窗口实现）与 `spring-ai-client-chat` 的 `MessageChatMemoryAdvisor`（编排）、`memory-repositories/`（持久化）。

## 6.1 ChatMemory / ChatMemoryRepository / MessageWindowChatMemory

记忆是**两层抽象**：

【源码证据】`spring-ai-model/.../chat/memory/ChatMemory.java` 第 31-61 行：

```java
public interface ChatMemory {
	String CONVERSATION_ID = "chat_memory_conversation_id";

	default void add(String conversationId, Message message) { ... }
	void add(String conversationId, List<Message> messages);
	List<Message> get(String conversationId);
	void clear(String conversationId);
}
```

【源码证据】`ChatMemoryRepository.java`（同包）第 29-42 行：`List<String> findConversationIds()`、`List<Message> findByConversationId(String)`、`saveAll`、`deleteByConversationId`——**纯存储 CRUD，不带任何策略**。

策略在中间层 `MessageWindowChatMemory`：

【源码证据】`spring-ai-model/.../chat/memory/MessageWindowChatMemory.java` 第 43-45、81-105 行（关键逻辑）：

```java
public final class MessageWindowChatMemory implements ChatMemory {
	private static final int DEFAULT_MAX_MESSAGES = 20;
```

```java
private List<Message> process(List<Message> memoryMessages, List<Message> newMessages) {
	List<Message> processedMessages = new ArrayList<>();

	Set<Message> memoryMessagesSet = new HashSet<>(memoryMessages);
	boolean hasNewSystemMessage = newMessages.stream()
		.filter(SystemMessage.class::isInstance)
		.anyMatch(message -> !memoryMessagesSet.contains(message));

	memoryMessages.stream()
		.filter(message -> !(hasNewSystemMessage && message instanceof SystemMessage))
		.forEach(processedMessages::add);

	processedMessages.addAll(newMessages);

	if (processedMessages.size() <= this.maxMessages) {
		return processedMessages;
	}

	// Collect the indices of non-system messages; SystemMessages are always
	// preserved.
	...
}
```

窗口语义：默认保留最近 20 条；**SystemMessage 有特权**——新系统消息到来时旧的全部作废，窗口超限时系统消息永不逐出。窗口外溢出即物理删除（配合持久化仓库时，早期对话就真的没了——要"永不遗忘"得自己组合检索式记忆，见 7.3 节的 `VectorStoreChatMemoryAdvisor`）。

默认仓库是 `InMemoryChatMemoryRepository`（同包，63 行）；生产用 `memory-repositories/` 下五个模块：JDBC、Redis、Neo4j、MongoDB、Cassandra（每个配 starter + 自动配置）。

## 6.2 MessageChatMemoryAdvisor 的读写时机

读写时机已在 4.6.3 节逐行分析，这里只总结语义要点：

1. **读**：请求进入时把记忆里的历史**前置**到消息列表（防重：`isMemoryAlreadyInPrompt` 前缀比对——工具循环内重复触发时不会把历史拼两遍）；系统消息强制提到最前。
2. **写**：请求前写"最后一条用户或工具响应消息"（`getLastUserOrToolResponseMessage`），响应后写助手的全部 Generation 输出。
3. **与工具循环的位置关系是 2.0 的显式设计**：记忆默认 order 200、工具循环默认 order 300 → 记忆在循环外，**只在整轮结束后写一次**，工具调用的中间消息不进记忆仓库（upgrade-notes 635 行解释了原因："most repository implementations do not support those message types"）。想要记忆进循环（比如 InMemory 仓库支持 TOOL 消息），显式把记忆 order 配到 400 并 `disableInternalConversationHistory()`——官方文档给了完整示例。

## 6.3 2.1 展望：spring-ai-session

upgrade-notes 第 651 行的 TIP 明确了方向："The spring-ai-session community project provides a session-aware memory implementation that fully supports tool-call messages and can be safely used inside the tool-call loop with any backend. **It is planned to replace `ChatMemory` in Spring AI 2.1.**"——窗口式记忆对 Agent 场景（长工具链、需要记住工具中间结果）不够用，社区方案将取而代之。读源码时把 `ChatMemory` 接口当作"过渡期抽象"看待即可。

## 6.4 本章小结

- 两层抽象：`ChatMemory`（策略 + 门面）/ `ChatMemoryRepository`（纯存储）；窗口策略默认 20 条、系统消息豁免。
- 读写时机由 Advisor 编排：请求前读 + 写用户消息，响应后写助手消息；防重比对避免循环内重复。
- 默认配置下记忆在工具循环外，工具中间消息不落库——这是 2.0 用 order 常数（200/300）写进默认值的架构决定。
- 2.1 计划用社区 spring-ai-session 取代 ChatMemory。

---

# 七、RAG 与向量库（spring-ai-vector-store / spring-ai-rag / advisors/）

> 本章对应源码：`spring-ai-vector-store`（36 文件）、`spring-ai-rag`（28 文件）、`advisors/spring-ai-vector-store-advisor`。

## 7.1 ETL 管道与 Document

RAG 的摄取侧是一条标准 ETL：`DocumentReader`（pdf/tika/markdown/jsoup 四个模块）读出 `Document` 列表 → `DocumentTransformer`（`TokenTextSplitter` 按 token 窗口切分，commons）→ `DocumentWriter`（`VectorStore.add` 嵌入并入库）。`Document` 自带 `MetadataMode`（渲染文本时决定元数据要不要拼进去——`ALL`/`NONE`/`EMBED`/`MEDIUM`），用于"给嵌入模型的文本"与"给用户展示的文本"分轨。

## 7.2 VectorStore 抽象与 SearchRequest / Filter 表达式

【源码证据】`spring-ai-vector-store/.../VectorStore.java` 第 40-51、78-108 行（节选）：

```java
public interface VectorStore extends DocumentWriter, VectorStoreRetriever {

	void add(List<Document> documents);

	default void upsert(List<EmbeddedDocument> entries) {
		throw new UnsupportedOperationException(getName() + " does not support upsert");
	}   // @since 2.1.0：预计算向量写入（replace-by-id），批处理重跑安全

	void delete(List<String> idList);
	void delete(Filter.Expression filterExpression);
	default void delete(String filterExpression) { ... }
	default <T> Optional<T> getNativeClient() { return Optional.empty(); }
}
```

检索侧拆在 `VectorStoreRetriever`（第 40 行 `VectorStore extends` 它）：**读写分离的最小接口**——只想暴露"只读检索"能力的场景不必暴露 add/delete（Javadoc 原话："following the principle of least privilege"）。检索方法就是 `similaritySearch(SearchRequest)`。

【源码证据】`SearchRequest.java` 第 44-60 行：

```java
public static final double SIMILARITY_THRESHOLD_ACCEPT_ALL = 0.0;
public static final int DEFAULT_TOP_K = 4;
private String query = "";
private int topK = DEFAULT_TOP_K;
private double similarityThreshold = SIMILARITY_THRESHOLD_ACCEPT_ALL;
private Filter.@Nullable Expression filterExpression;
```

Filter 是一套**跨向量库可移植的元数据过滤表达式**：`Filter.Expression` 树（`Filter.java` 定义 IN/EQ/GT/AND/OR 等节点）+ 文本 DSL（`"type == 'article' && year >= 2024"`，`FilterExpressionTextParser` 用 **ANTLR4 语法**解析，`filter/antlr4/` 下是生成代码）+ 各库自己的表达式转换器（`filter/converter/`）。用户写一次 DSL，22 个实现各自翻译成自家的过滤语法——"适配而非重造"的又一次落地。

22 个实现里唯一自带的是 `SimpleVectorStore`（进程内、内存、零依赖，测试与原型用）；其余每个模块对应一个数据库，全部通过 `AbstractObservationVectorStore` 基类获得查询观测（`vector-store-sql` 观测名，`VectorStoreObservationDocumentation`）。

## 7.3 QuestionAnswerAdvisor：最短 RAG 路径

一句话 RAG 用它就够了。它把"检索 → 拼上下文 → 增强提示词"压成一个 Advisor：

【源码证据】`advisors/spring-ai-vector-store-advisor/.../QuestionAnswerAdvisor.java` 第 61-73、109-141 行：

```java
private static final PromptTemplate DEFAULT_PROMPT_TEMPLATE = new PromptTemplate("""
		{query}

		Context information is below, surrounded by ---------------------

		---------------------
		{question_answer_context}
		---------------------

		Given the context and provided history information and not prior knowledge,
		reply to the user comment. If the answer is not in the context, inform
		the user that you can't answer the question.
		""");
```

```java
@Override
public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
	// 1. Search for similar documents in the vector store.
	var searchRequestBuilder = SearchRequest.from(this.searchRequest)
		.query(Objects.requireNonNullElse(chatClientRequest.prompt().getUserMessage().getText(), ""));

	var filterExpr = doGetFilterExpression(chatClientRequest.context());
	if (filterExpr != null) {
		searchRequestBuilder.filterExpression(filterExpr);
	}

	var searchRequestToUse = searchRequestBuilder.build();

	List<Document> documents = this.vectorStore.similaritySearch(searchRequestToUse);

	// 2. Create the context from the documents.
	...
	String documentContext = documents.stream()
		.map(Document::getText)
		.collect(Collectors.joining(System.lineSeparator()));

	// 3. Augment the user prompt with the document context.
	UserMessage userMessage = chatClientRequest.prompt().getUserMessage();
	String augmentedUserText = this.promptTemplate
		.render(Map.of("query", userMessage.getText(), "question_answer_context", documentContext));

	// 4. Update ChatClientRequest with augmented prompt.
	return chatClientRequest.mutate()
		.prompt(chatClientRequest.prompt().augmentUserMessage(augmentedUserText))
		.context(context)
		.build();
}
```

两个细节：检索请求的过滤表达式可以从 **context 动态注入**（`qa_filter_expression` 键 → `doGetFilterExpression`，第 158-164 行）——用户会话中的动态过滤条件不必固化在 Advisor 上；检索到的文档同时放进 context（`qa_retrieved_documents`）与响应元数据，方便调用方溯源。

（另有 `VectorStoreChatMemoryAdvisor`：把对话历史本身当向量存取——"语义式记忆"，与窗口式 `MessageChatMemoryAdvisor` 互补。）

## 7.4 RetrievalAugmentationAdvisor：模块化 RAG 七步

`spring-ai-rag` 模块把 RAG 流程拆成可组合的积木（Javadoc 引用了 Modular RAG 架构论文 arXiv:2407.21059 等），`RetrievalAugmentationAdvisor` 是组装者：

【源码证据】`spring-ai-rag/.../rag/advisor/RetrievalAugmentationAdvisor.java` 第 106-154 行（before，步骤注释保留）：

```java
@Override
public ChatClientRequest before(ChatClientRequest chatClientRequest, @Nullable AdvisorChain advisorChain) {
	Map<String, Object> context = new HashMap<>(chatClientRequest.context());

	// 0. Create a query from the user text, parameters, and conversation history.
	String text = chatClientRequest.prompt().getUserMessage().getText();
	Query originalQuery = Query.builder()
		.text(Objects.requireNonNullElse(text, ""))
		.history(chatClientRequest.prompt().getInstructions())
		.context(context)
		.build();

	// 1. Transform original user query based on a chain of query transformers.
	Query transformedQuery = originalQuery;
	for (var queryTransformer : this.queryTransformers) {
		transformedQuery = queryTransformer.apply(transformedQuery);
	}

	// 2. Expand query into one or multiple queries.
	List<Query> expandedQueries = this.queryExpander != null ? this.queryExpander.expand(transformedQuery)
			: List.of(transformedQuery);

	// 3. Get similar documents for each query.
	Map<Query, List<List<Document>>> documentsForQuery = expandedQueries.stream()
		.map(query -> CompletableFuture.supplyAsync(() -> getDocumentsForQuery(query), this.taskExecutor))
		.toList()
		.stream()
		.map(CompletableFuture::join)
		.collect(Collectors.toMap(Map.Entry::getKey, entry -> List.of(entry.getValue())));

	// 4. Combine documents retrieved based on multiple queries and from multiple data sources.
	List<Document> documents = this.documentJoiner.join(documentsForQuery);

	// 5. Post-process the documents.
	for (var documentPostProcessor : this.documentPostProcessors) {
		documents = documentPostProcessor.process(originalQuery, documents);
	}
	context.put(DOCUMENT_CONTEXT, documents);

	// 6. Augment user query with the document contextual data.
	Query augmentedQuery = this.queryAugmenter.augment(originalQuery, documents);

	// 7. Update ChatClientRequest with augmented prompt.
	return chatClientRequest.mutate()
		.prompt(chatClientRequest.prompt().augmentUserMessage(augmentedQuery.text()))
		.context(context)
		.build();
}
```

每一步都是一个 SPI，自带的实现：

| 步骤 | SPI | 自带实现 |
|---|---|---|
| 1 查询改写 | `QueryTransformer` | `CompressionQueryTransformer`（把多轮历史压成独立问题）、`RewriteQueryTransformer`（重写成更适合检索的语句）、`TranslationQueryTransformer`（翻译成检索语言） |
| 2 查询扩展 | `QueryExpander` | `MultiQueryExpander`（让模型生成 N 个变体查询） |
| 3 检索 | `DocumentRetriever` | `VectorStoreDocumentRetriever`（也可自己实现——比如接搜索引擎） |
| 4 合并 | `DocumentJoiner` | `ConcatenationDocumentJoiner`（多查询结果按分数拼接去重） |
| 5 后处理 | `DocumentPostProcessor` | 自己写（重排序等） |
| 6 增强 | `QueryAugmenter` | `ContextualQueryAugmenter`（带"空上下文拒答"模式） |

注意第 3 步的并发语义：**每个扩展查询用一个 `CompletableFuture.supplyAsync` 并行检索**（默认线程池，可用 `taskExecutor(...)` 换成自己的）——多路召回的延迟 ≈ 最慢一路，而不是累加。与 `QuestionAnswerAdvisor` 相比，这条管道多出的能力全部是"检索前/后"的：查询改写/扩展/多路合并——**两者不是替代关系**，复杂 RAG 用它，简单 RAG 用 QA Advisor。

## 7.5 本章小结

- 摄取侧：Reader → Transformer（TokenTextSplitter）→ Writer（VectorStore.add），Document 的 MetadataMode 支持展示/嵌入分轨。
- 检索侧：`VectorStore` = Writer + `VectorStoreRetriever`（读写分离）；`SearchRequest`（topK=4、阈值 0）+ ANTLR 解析的可移植 Filter DSL，22 个库各自翻译。
- `QuestionAnswerAdvisor`：检索 + 模板增强 + context 注入，一条 Advisor 解决 80% 的 RAG；filter 表达式支持按请求动态注入。
- `RetrievalAugmentationAdvisor`：七步模块化管道（改写→扩展→并行检索→合并→后处理→增强），每步一个 SPI，多路召回并行执行。

---

# 八、MCP、工具搜索与生态外围

> 本章对应源码：`mcp/`、`advisors/spring-ai-tool-search-advisor`、`spring-ai-commons` 的 evaluation 包、`spring-ai-test`。

## 8.1 MCP 三模块与客户端/服务端装配

MCP（Model Context Protocol）是 Anthropic 发起的"AI 应用 ↔ 工具/数据源"标准协议。Spring AI 的 MCP 支持分三块（MCP Java SDK 本体是独立项目 `io.modelcontextprotocol.sdk:mcp`，Spring AI 2.0 已跟进到 SDK 2.0.0——upgrade-notes 1758 行）：

| 模块 | 内容 | 用户视角 |
|---|---|---|
| `mcp/common`（20 文件） | **客户端桥接**：`SyncMcpToolCallback`/`AsyncMcpToolCallback` 把远端 MCP 服务器的每个工具包装成本地 `ToolCallback`；`SyncMcpToolCallbackProvider` 聚合一个连接的所有工具；`McpToolNamePrefixGenerator`（默认 `serverName_toolName` 防多服务器重名）、`McpToolFilter`（按连接过滤工具）、`McpToolsChangedEvent`（服务器工具列表变更事件）；**服务端定制口**：5 个 customizer 接口 | 写 `spring.ai.mcp.client.*` 配置 + 挂 starter，远端工具自动进 `ToolCallback` 体系，对 ChatClient 完全透明 |
| `mcp/mcp-annotations`（167 文件） | **服务端注解式开发**：`@McpTool`/`@McpToolParam`/`@McpPrompt`/`@McpResource`/`@McpArg` + 各自的回调适配器（`adapter/`）+ 请求上下文能力注入（`context/`：`McpSyncRequestContext` 提供 sampling（反向调客户端模型）、elicitation（向用户要补充输入）、progress、logging） | 你的 POJO 方法变成 MCP 服务器的能力 |
| `mcp/transport/` | WebMVC/WebFlux 两个传输适配（SSE/Streamable HTTP） | 与 Spring Web 栈对接 |

自动配置成对出现（`auto-configurations/mcp/` 六个模块）：客户端 common + httpclient/webflux 变体；服务端 common + webflux/webmvc 变体；starter 一一对应（`spring-ai-starter-mcp-client(-webflux)`、`spring-ai-starter-mcp-server(-webmvc|-webflux)`）。2.0 还加入了输入校验：服务端默认校验工具入参是否符合 Schema（可 `validateToolInputs(false)` 关闭，upgrade-notes 1774 行）。

## 8.2 spring-ai-mcp-annotations：注解式 MCP 服务器

与第五章 `@Tool` 同构的配方复制到了服务器侧：

```java
@Service
class WeatherService {
    @McpTool(name = "get_weather", description = "按城市查天气")
    String getWeather(@McpToolParam(description = "城市名") String city) { ... }
}
```

服务端由 `McpServerAnnotationScannerAutoConfiguration`（`auto-configurations/mcp/spring-ai-autoconfigure-mcp-server-common`）扫描注册这些注解方法；客户端侧另有 `McpClientAnnotationScannerAutoConfiguration`，扫描的是**客户端回调注解**（`@McpSampling`/`@McpElicitation`/`@McpProgress`/`@McpLogging`/`*ListChanged`——服务器反向发起请求与通知时客户端的处理方法）。与 `@Tool` 的差异在**上下文注入**：MCP 方法参数可以注入 `McpSyncRequestContext`，从而使用 sampling/elicitation/progress 这些 MCP 协议能力——这是 `@Tool` 体系没有的（协议自带反向通道）。

## 8.3 ToolSearchToolCallingAdvisor：工具太多怎么办

场景：MCP 接了十个服务器、几百个工具，全部 ToolDefinition 塞给模型既烧 token 又降低选择准确率。解法（2.0 新增模块 + starter）：

【源码证据】官方 upgrade-notes 第 605-625 行（要点）：

> "When enabled, `ToolSearchToolCallingAdvisor` replaces the default `ToolCallingAdvisor` in the auto-configured `ChatClient`, limiting the tool definitions sent to the LLM per call to only the most relevant ones (via keyword or semantic search). ... The `regex` index requires no additional dependencies. The `lucene` index requires `org.apache.lucene:lucene-core` on the classpath. The `vector` index requires a `VectorStore` bean."

```properties
spring.ai.chat.client.tool-search-advisor.enabled=true
spring.ai.chat.client.tool-search-advisor.tool-index-type=regex   # regex(默认) / lucene / vector
```

实现上它**继承自 `ToolCallingAdvisor`**（`ToolSearchToolCallingAdvisor.java` 的 import 可见）——只在每轮循环前把"按当前查询检索出的相关工具子集"放进选项，循环机制原封复用。这是 4.6.2 节"循环搬进链内"的直接红利：**替换循环策略 = 换一个 Advisor 子类**。

## 8.4 评测与测试支持

- **评测**（commons `evaluation/` + client-chat `chat/evaluation/`）：`Evaluator` 接口；`RelevancyEvaluator`（回答是否切题）、`FactCheckingEvaluator`（陈述是否与给定资料相符）——两者都是"让一个 ChatModel 当裁判"的实现。评测数据结构 `EvaluationRequest` = 用户消息 + 上下文资料 + 模型回复。
- **测试基类**（`spring-ai-test`）：`BaseVectorStoreTests`/`AbstractVectorStoreUpsertTests`（22 个向量库模块继承同一套一致性测试）、`AbstractToolCallingAdvisorIT`、`AbstractChatOptionsTests`、`FixedDimensionEmbeddingModel`（固定维度假嵌入模型）。**每个向量库实现都跑同一套契约测试**——这是 22 个实现行为一致性的保障机制。
- **开发体验**（`spring-ai-spring-boot-docker-compose` / `-testcontainers`）：连接 Docker Compose/Testcontainers 服务时自动把连接信息桥接进 Spring AI 属性（比如 Testcontainers 起的 pgvector 自动填充向量库 URL）。

## 8.5 本章小结

- MCP 三层：SDK 独立 → common 桥接（远端工具 = 本地 ToolCallback）→ annotations/transport/autoconfigure/starter 全套 Spring 化；2.0 跟进 SDK 2.0.0 并默认开启入参校验。
- 工具搜索 Advisor 继承 ToolCallingAdvisor，用 regex/lucene/vector 索引把"每轮发给模型的工具"裁剪到最相关子集。
- 评测（两个"模型当裁判"的实现）与向量库契约测试基类保证了生态实现的一致性。

---

# 九、观测体系与 Boot 装配

> 本章对应源码：各模块的 `observation/` 包与 `auto-configurations/`。

## 9.1 五个观测点一张表

| 观测点 | Documentation 枚举 | 位置 |
|---|---|---|
| ChatClient 整体调用 | `ChatClientObservationDocumentation.AI_CHAT_CLIENT` | client-chat `observation/` |
| 每个模型 API 调用 | `ChatModelObservationDocumentation.CHAT_MODEL_OPERATION` | model `chat/observation/`（各适配器接线） |
| 每个 Advisor 的执行 | `AdvisorObservationDocumentation.AI_ADVISOR` | client-chat `advisor/observation/`（链机制自带） |
| 每次工具执行 | `ToolCallingObservationDocumentation.TOOL_CALL` | model `tool/observation/` |
| 每次向量库查询 | `VectorStoreObservationDocumentation`（QUERY） | vector-store `observation/`（`AbstractObservationVectorStore`） |

嵌套关系天然形成 trace 树：ChatClient span → Advisor span（每 Advisor 一个）→ ChatModel span（每轮一次）→ Tool span（每次执行一个）。跨线程/响应式的父子 span 传递有两个专门机制：`ObservationThreadLocalAccessor` + Reactor Context（模型流式，3.4.3 节），`ToolCallReactiveContextHolder`（工具执行，5.3 节）。

## 9.2 命名：gen_ai.client.operation 与 spring.ai.*

【源码证据】`spring-ai-model/.../chat/observation/DefaultChatModelObservationConvention.java` 第 40 行：

```java
public static final String DEFAULT_NAME = "gen_ai.client.operation";
```

模型级指标对齐 **OpenTelemetry Generative AI 语义约定**（`gen_ai.*`）——接 Grafana/Jaeger 等 OTel 工具链零映射成本。低基数键（可做 metric tag）包括 `gen_ai.operation.name`（chat/embedding...）、`gen_ai.system`（provider）、请求/响应模型名。

【源码证据】`spring-ai-client-chat/.../observation/ChatClientObservationDocumentation.java` 第 61-107 行（键名节选）：

```java
return "spring.ai.kind";                              // framework 级
return "spring.ai.chat.client.stream";                // 是否流式
return "spring.ai.chat.client.advisors";              // 本链 advisor 名单
return "spring.ai.chat.client.conversation.id";
return "spring.ai.chat.client.tool.names";
```

客户端级用 `spring.ai.*` 前缀（观测名 `spring.ai.chat.client`，`DefaultChatClientObservationConvention.java` 第 41 行）。**prompt/completion 内容默认不记录**——要开启得显式配 `spring.ai.chat.client.observations.log-prompt/log-completion=true`，自动配置会打安全警告（9.3 节）。

## 9.3 ChatClientAutoConfiguration 逐段

【源码证据】`auto-configurations/models/chat/client/spring-ai-autoconfigure-model-chat-client/.../ChatClientAutoConfiguration.java` 第 67-129 行（核心逻辑，注释保留）：

```java
@AutoConfiguration(after = ToolCallingAutoConfiguration.class)
@ConditionalOnClass(ChatClient.class)
@EnableConfigurationProperties(ChatClientBuilderProperties.class)
@ConditionalOnProperty(prefix = ChatClientBuilderProperties.CONFIG_PREFIX, name = "enabled", havingValue = "true",
		matchIfMissing = true)
public class ChatClientAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	ChatClientBuilderConfigurer chatClientBuilderConfigurer(...) { ... }

	@Bean
	@ConditionalOnMissingBean
	@ConditionalOnBean(ToolCallingManager.class)
	ToolCallingAdvisor.Builder<?> toolCallingAdvisorBuilder(ChatClientBuilderProperties properties,
			ToolCallingManager toolCallingManager,
			ObjectProvider<ToolExecutionEligibilityChecker> toolExecutionEligibilityChecker) {
		var builder = ToolCallingAdvisor.builder()
			.toolCallingManager(toolCallingManager)
			.advisorOrder(properties.getToolCalling().getAdvisorOrder());
		toolExecutionEligibilityChecker.ifAvailable(builder::toolExecutionEligibilityChecker);
		return builder;
	}

	@Bean
	@Scope("prototype")
	@ConditionalOnMissingBean
	ChatClient.Builder chatClientBuilder(ChatClientBuilderProperties properties,
			ChatClientBuilderConfigurer chatClientBuilderConfigurer, ChatModel chatModel,
			ObjectProvider<ObservationRegistry> observationRegistry, ...) {
		ChatClient.Builder builder = ChatClient.builder(chatModel, ..., toolCallingAdvisorBuilder.getIfAvailable());
		if (!properties.getToolCalling().isEnabled()) {
			builder.defaultAdvisors(AdvisorParams.toolCallingAdvisorAutoRegister(false));
		}
		return chatClientBuilderConfigurer.configure(builder);
	}
```

四个设计点：

1. **注入的是 `ChatClient.Builder` 且 `@Scope("prototype")`**（第 112-115 行，类 Javadoc 明说"each injection point will receive a newly cloned instance"）——每个注入点拿到独立 builder，各处 `defaultSystem(...)` 互不污染。
2. **`ToolCallingAdvisor.Builder` 是独立 bean**，`ToolCallingManager`（独立自动配置）+ 用户自定义 `ToolExecutionEligibilityChecker` bean 都会注入进来——想定制工具循环的限流/判定，声明对应 bean 即可，不用碰 ChatClient。
3. **`spring.ai.chat.client.tool-calling.enabled=false` 时**不是不装配 advisor，而是往 builder 塞 `AdvisorParams.toolCallingAdvisorAutoRegister(false)`——关闭"自动注册"，用户仍可手动挂自己构建的 ToolCallingAdvisor。
4. **观测 handler 按 Tracer 有无分两组配置**（第 131-182 行）：有 `micrometer-tracing` 时包一层 `TracingAwareLoggingObservationHandler`（让内容日志跟着 trace 走）；无论哪组，`log-prompt/log-completion` 开启时先打隐私警告（第 76-84 行）。

模型侧自动配置同构：`spring-ai-autoconfigure-model-openai` 等 15 个模块各产出一个 `ChatModel`（含连接属性 `spring.ai.openai.*`、选项属性、`WebClient`/SDK builder 定制口）；向量库、记忆仓库、MCP 各自成套。**属性前缀规律：`spring.ai.<能力>.<厂商/实现>.*`**（`spring.ai.openai.chat.options.temperature`、`spring.ai.vectorstore.pgvector.dimensions`、`spring.ai.mcp.client.stdio.connections.*`）。

## 9.4 starters 与 BOM

- **47 个 starter**（`starters/`），命名三段式：`spring-ai-starter-<能力>-<实现>`——`spring-ai-starter-model-openai`、`spring-ai-starter-vector-store-pgvector`、`spring-ai-starter-model-chat-memory-repository-jdbc`、`spring-ai-starter-mcp-server-webmvc`、`spring-ai-starter-tool-search-advisor`。starter 是纯依赖聚合（各 starter 的 pom 只有 dependency 声明）。
- **`spring-ai-bom`** 统一版本（pom 中 173 个 artifactId 声明），版本对齐走 Boot BOM 风格。
- 开发期双件套：`spring-ai-spring-boot-docker-compose`/`-testcontainers` 把容器连接信息桥接进属性。
- 本仓库 main 同时是 2.1.0-SNAPSHOT 的发布工程：`release-train-settings.xml`、`commercial-settings.xml` 显示其与商业版（VMware Tanzu 侧）同源构建。

## 9.5 本章小结

- 五处 Observation 由嵌套天然成 trace 树；模型级命名对齐 OTel `gen_ai.*`，客户端级用 `spring.ai.*`；prompt/completion 内容默认不落日志、开启有警告。
- Boot 装配核心是 **prototype 作用域的 `ChatClient.Builder`** + 独立的 `ToolCallingAdvisor.Builder` bean + `ChatClientBuilderConfigurer`（customizer 链）。
- 属性前缀规律 `spring.ai.<能力>.<实现>.*`；starter 三段式命名；向量库等生态实现靠共享契约测试基类保证一致。

---

# 十、贯通视图：三条生命线

## 10.1 启动时间线：从 starter 到可用的 ChatClient

以 `spring-ai-starter-model-openai` + `spring-boot-starter-web` 为例：

1. **类路径上出现** starter → 引入 `spring-ai-openai`（模型实现）+ `spring-ai-autoconfigure-model-openai` + `spring-ai-autoconfigure-model-chat-client` + `spring-ai-autoconfigure-model-tool` 等。
2. **Boot 自动配置启动**：`OpenAiAutoConfiguration` 读 `spring.ai.openai.*`，构造官方 SDK 的 `OpenAIClient` → 产出 `OpenAiChatModel` bean（默认 `OpenAiChatOptions` 从属性构建）；`ToolCallingAutoConfiguration` 产出 `DefaultToolCallingManager`；`ChatClientAutoConfiguration` 产出 `ToolCallingAdvisor.Builder` 与 **prototype 的 `ChatClient.Builder`**（9.3 节）。
3. **业务注入与定制**：`ChatClient.Builder`（prototype）注入点 → `ChatClientBuilderConfigurer` 应用所有 `ChatClientCustomizer`（比如统一挂 SimpleLoggerAdvisor）→ `build()` 得到客户端。
4. **观测接线**：容器里若有 `ObservationRegistry`（Boot 4 的 micrometer 自动配置默认提供），五个观测点全部激活；有 `Tracer` 则内容日志跟随 trace。

## 10.2 一次 `call()` 的一生

`chatClient.prompt().system(S).user(U).tools(new MyTools()).call().content()`：

1. **组装**（`DefaultChatClientRequestSpec`）：system 文本、user 文本（经 `StTemplateRenderer` 渲染模板变量）、工具对象扫描成 `ToolCallback[]` 存入请求规格。
2. **call() 触发**（第 1176-1180 行）：`buildAdvisorChain()`——用户 Advisor（无）+ 自动注册 `ToolCallingAdvisor`（有工具 → 注册；order 300）+ 链尾 `ChatModelCallAdvisor`（order 最低）→ `DefaultAroundAdvisorChain` 排序。
3. **链执行**：`DefaultCallResponseSpec` 套 ChatClient 级 Observation，`advisorChain.nextCall(request)` 弹出 ToolCallingAdvisor。
4. **工具循环第 1 轮**：`copy(this).nextCall()` → 弹出 ChatModelCallAdvisor → 补格式指令（无）→ `chatModel.call(prompt)` → OpenAI 适配器：options 兜底、构造 `ChatCompletionCreateParams`、套 CHAT_MODEL_OPERATION Observation、调官方 SDK → 翻译成 `ChatResponse`（本轮含 `ToolCall`，`hasToolCalls()==true`）。
5. **回到 ToolCallingAdvisor**：eligibility 判定命中 → `toolCallingManager.executeToolCalls(全轮历史, 响应)` → 按 id 查 callback → 限流检查 → ToolCall Observation 包裹 `MethodToolCallback.call`（JSON 参数装配 → 反射调用 MyTools 方法 → 结果转 JSON 字符串）→ 拼出新历史（原消息 + AssistantMessage(toolCalls) + ToolResponseMessage）。
6. **循环第 2 轮**：新历史再走 `copy(this).nextCall()` → 模型基于工具结果生成最终答案（`hasToolCalls()==false`）→ 循环结束，`UsageAccumulator` 把两轮 token 求和写入最终响应。
7. **返回**：`CallResponseSpec.content()` 取 `getResult().getOutput().getText()`。

## 10.3 一次工具调用的一生（对照 1.x）

1.x：`OpenAiChatModel.call()` 内部循环（模型调 → 执行工具 → 模型调），业务无法在轮间插手，记忆只能整轮后写，观测里一个 span 盖住 N 轮。
2.x（上图 4~6 步）：循环在链内、每轮一条子链、每轮一个 ChatModel span、每个工具一个 Tool span、限流/白名单/returnDirect/异常转译各就各位。**同一个"模型调工具"场景，2.x 把它从黑盒调用变成了可编排、可观测、可治理的管线。**

## 10.4 从源码中提炼的四个设计模式视角

1. **策略 + 适配器**：`ChatModel`/`EmbeddingModel`/`VectorStore` 是策略接口，15 个模型模块与 22 个向量库模块是适配器；厂商 SDK 的协议差异被翻译层消化（OpenAI 适配器连 Jackson 2/3 混用都挡在里面）。
2. **责任链 + 模板方法**：Advisor 链是责任链（出栈式 + copy 重入），`BaseAdvisor` 是模板方法（before/after）；2.0 把工具循环也纳入链，本质是**把"流程黑盒"重构为"流程框架"**。
3. **Builder + Fluent**：`ChatClient` 的 spec 链（prompt/user/system/tools/advisors → call/stream）、各 Options 的 `mutate()/combineWith()`、`DefaultToolCallingManager.Builder`——不可变对象 + 派生修改（`copy()`/`mutate()`）贯穿始终，这也是它能在响应式流里安全共享的原因。
4. **SPI + 自动配置**：每层都有接口（TemplateRenderer、ToolCallResultConverter、QueryTransformer、Evaluator、BatchingStrategy...），Boot 自动配置负责把"用户声明的 bean"注入到 SPI 位上（`ToolExecutionEligibilityChecker` bean → advisor builder），**Spring 容器本身就是 Spring AI 的装配框架**。

---

# 十一、附录

## 11.1 关键接口速查表

| 接口/类 | 所在模块 | 一句话职责 | 关键方法 |
|---|---|---|---|
| `Model<TReq,TRes>` | spring-ai-model | 一切模型的顶层 | `call(req)` |
| `ChatModel` | spring-ai-model | 聊天模型（含流式继承） | `call(Prompt)`、`stream(Prompt)`、`getOptions()` |
| `Prompt` | spring-ai-model | 请求载体：消息 + 选项 | `getInstructions()`、`augmentUserMessage()` |
| `ChatResponse`/`Generation` | spring-ai-model | 响应载体 | `getResult()`、`hasToolCalls()` |
| `Message` 五类 + `MessagePart` 六件套 | spring-ai-model | 消息与有序内容 | `getMessageType()`、`getParts()` |
| `ChatOptions`/`ToolCallingChatOptions` | spring-ai-model | 可移植选项 / 工具扩展 | 8 属性、`mutate()`、`toolCallbacks()` |
| `ToolCallback`/`ToolDefinition` | spring-ai-model | 工具执行器 / 模型名片 | `call(input, ctx)`、`name()/inputSchema()` |
| `ToolCallingManager` | spring-ai-model | 工具调度与执行 | `resolveToolDefinitions()`、`executeToolCalls()` |
| `ChatClient` | spring-ai-client-chat | fluent 门面 | `prompt()...call()/stream()` |
| `CallAdvisor`/`StreamAdvisor` | spring-ai-client-chat | 链拦截器 | `adviseCall()`/`adviseStream()` |
| `BaseAdvisor` | spring-ai-client-chat | before/after 模板 | `before()`、`after()`、`getScheduler()` |
| `ChatMemory`/`ChatMemoryRepository` | spring-ai-model | 记忆策略 / 纯存储 | `add()/get()/clear()` |
| `VectorStore`/`VectorStoreRetriever` | spring-ai-vector-store | 写 / 只读检索 | `add()`、`similaritySearch()`、`upsert()`(2.1) |
| `QueryTransformer` 等 RAG SPI | spring-ai-rag | 模块化 RAG 积木 | `transform()`、`expand()`、`join()`、`augment()` |
| `EmbeddingModel` | spring-ai-model | 嵌入模型 | `embed(Document)`、`embedForResponse()` |
| `Evaluator` | spring-ai-commons | 输出评测 | `evaluate(request)` |
| `TemplateRenderer` | spring-ai-commons | 模板渲染 SPI | `render(template, vars)` |

## 11.2 1.x → 2.0 → 2.1 迁移对照（重点条目）

| 1.x 写法 | 2.x 写法 | 说明 |
|---|---|---|
| `ChatOptions` 上 `internalToolExecutionEnabled(false)` 自己驱动循环 | 直接用 ChatClient（工具循环自动在链内）或自己写循环 | 标志已被删除，无法编译 |
| `.toolCallbacks(callbacks)` / `FunctionCallback` | `.tools(obj...)` / `ToolCallback` | 词汇统一；旧 API `@Deprecated(forRemoval=true)`（3.0 移除） |
| `getDefaultOptions()` | `getOptions()` | 旧名废弃 |
| `spring-ai-advisors-vector-store` 依赖 | `spring-ai-vector-store-advisor` | 更名 |
| 记忆默认 order +1000 | +200（工具循环 +300） | 记忆默认移到循环外；要进循环见 4.6.3 |
| 模型回复的"思考"是独立 Generation/元数据键（`isThought`、`reasoningContent`、`thinkingText`） | `AssistantMessage.getReasoning()`（`ReasoningPart` 列表，签名在 `OpaquePayload`） | 2.1 核心变更；旧键写一个过渡版本 |
| OpenAI 自带 RestClient 端点封装 | 官方 `com.openai:openai-java` | 换依赖时注意 SDK 自己的 builder（`http/okhttp/`、`setup/` 定制口） |
| Jackson 2 | Jackson 3（`tools.jackson`） | 自定义 `ToolCallResultConverter` 等注意 import |
| 模块 `memory/` | `memory-repositories/` | 更名 |
| 工具名单靠 resolver 兜底解析 | 默认仅限请求挂载的工具（2.0.1 起） | 安全收紧；确需兜底开 `resolutionFallbackEnabled(true)` |

## 11.3 源码阅读入口清单（30 个关键文件）

```
# 抽象层
spring-ai-model/src/main/java/org/springframework/ai/model/Model.java
spring-ai-model/src/main/java/org/springframework/ai/chat/model/ChatModel.java
spring-ai-model/src/main/java/org/springframework/ai/chat/model/StreamingChatModel.java
spring-ai-model/src/main/java/org/springframework/ai/chat/model/ChatResponse.java
spring-ai-model/src/main/java/org/springframework/ai/chat/prompt/Prompt.java
spring-ai-model/src/main/java/org/springframework/ai/chat/prompt/ChatOptions.java
spring-ai-model/src/main/java/org/springframework/ai/chat/messages/AbstractMessage.java
spring-ai-model/src/main/java/org/springframework/ai/chat/messages/AssistantMessage.java
spring-ai-model/src/main/java/org/springframework/ai/chat/messages/part/MessagePart.java      # 2.1 核心

# 工具
spring-ai-model/src/main/java/org/springframework/ai/tool/ToolCallback.java
spring-ai-model/src/main/java/org/springframework/ai/tool/definition/ToolDefinition.java
spring-ai-model/src/main/java/org/springframework/ai/tool/annotation/Tool.java
spring-ai-model/src/main/java/org/springframework/ai/tool/method/MethodToolCallback.java
spring-ai-model/src/main/java/org/springframework/ai/model/tool/ToolCallingManager.java
spring-ai-model/src/main/java/org/springframework/ai/model/tool/DefaultToolCallingManager.java

# 客户端与链
spring-ai-client-chat/src/main/java/org/springframework/ai/chat/client/ChatClient.java
spring-ai-client-chat/src/main/java/org/springframework/ai/chat/client/DefaultChatClient.java
spring-ai-client-chat/src/main/java/org/springframework/ai/chat/client/advisor/api/BaseAdvisor.java
spring-ai-client-chat/src/main/java/org/springframework/ai/chat/client/advisor/DefaultAroundAdvisorChain.java
spring-ai-client-chat/src/main/java/org/springframework/ai/chat/client/advisor/ChatModelCallAdvisor.java
spring-ai-client-chat/src/main/java/org/springframework/ai/chat/client/advisor/ToolCallingAdvisor.java   # 2.0 核心
spring-ai-client-chat/src/main/java/org/springframework/ai/chat/client/advisor/MessageChatMemoryAdvisor.java

# 记忆 / RAG / 向量库
spring-ai-model/src/main/java/org/springframework/ai/chat/memory/MessageWindowChatMemory.java
spring-ai-vector-store/src/main/java/org/springframework/ai/vectorstore/VectorStore.java
spring-ai-vector-store/src/main/java/org/springframework/ai/vectorstore/SearchRequest.java
spring-ai-rag/src/main/java/org/springframework/ai/rag/advisor/RetrievalAugmentationAdvisor.java
advisors/spring-ai-vector-store-advisor/src/main/java/org/springframework/ai/chat/client/advisor/vectorstore/QuestionAnswerAdvisor.java

# 适配器样例与装配
models/spring-ai-openai/src/main/java/org/springframework/ai/openai/OpenAiChatModel.java
auto-configurations/models/chat/client/spring-ai-autoconfigure-model-chat-client/src/main/java/org/springframework/ai/model/chat/client/autoconfigure/ChatClientAutoConfiguration.java
spring-ai-docs/src/main/antora/modules/ROOT/pages/upgrade-notes.adoc   # 版本演进的权威一手资料
```

## 11.4 常见坑清单（按踩中概率排序）

1. **升级 2.0 后工具不执行了**：ChatModel 不再内置循环。走 ChatClient（自动挂 ToolCallingAdvisor），或显式用 `ToolCallingChatOptions` + `ToolCallingManager` 手写循环；`internalToolExecutionEnabled` 这个开关已经不存在了。
2. **`.tools()` 传了对象但模型不调**：POJO 上没有 `@Tool` 注解（`tools()` 对"任何其他对象"按注解扫描，一个注解都没有会直接抛 `IllegalArgumentException`），或 description 太模糊模型不选。
3. **记忆重复/丢系统消息**：手动把历史塞进了 prompt 又挂了记忆 Advisor（有防重比对但依赖消息 equals——parts 引入后 media 参与相等性，自造消息要经 builder）；或记忆 Advisor order 放进了工具循环内导致每轮写库。
4. **OpenAI 场景传 topK 无效**：适配器显式不支持并告警（3.4.1 节 `verifyPromptChatOptions`）。
5. **模型幻觉工具名直接抛异常**：2.0.1 起默认白名单收紧；看到 "No ToolCallback found for tool name" 先检查工具是否真的挂在本请求上、名字是否被模型截断/改写。
6. **结构化输出拿到空对象**：默认提示词路线被模型的思考标签/Markdown 包裹干扰（框架已内置清洗，但仍可能漏）——改试 `useProviderStructuredOutput()`（注意 Ollama 推理模型/OpenAI 顶层数组的坑）或 `validateSchema()`。
7. **流式拼接文本少了/多了**：`getText()` 语义在 2.1 变了（有 parts 无文本 = 空串，无 parts 才是 null）；推理增量在 `ReasoningPart`，用 `getReasoning()` 取。
8. **native-image 报错**：MCP 客户端/注解扫描有 AOT hints（`mcp/aot/`、各模块 `aot/` 包），自写的工具 POJO 反射调用需要自己补注册提示。
9. **测试里向量库行为不一致**：优先继承 `spring-ai-test` 的契约测试基类写自己的实现，别只测 happy path。

# 结语

把 Spring AI 的源码读下来，最深的印象不是"支持了多少模型、多少向量库"，而是**它把 Spring 二十年的架构肌肉记忆原样复刻到了 AI 领域**：用最小接口收敛供应商差异（Model 之于 ChatModel，如 BeanFactory 之于 ApplicationContext）；用责任链承载横切逻辑（Advisor 之于 AOP/拦截器）；用注解把业务方法暴露成系统能力（@Tool/@McpTool 之于 @Bean/@EventListener）；用 Boot 自动配置把几十个模块的装配收敛到一个依赖（starter 之于 spring-boot-starter-*）。

而 2.0/2.1 的两次演进显示了它仍在主动进化的部分：2.0 把工具循环从模型层搬到链层，是"框架作者把最难的流程黑盒拆成可扩展的管线"的标准动作；2.1 的 MessagePart 是对现实妥协的优雅解——厂商间的内容模型差异（思考签名、交错工具调用、未知块）不再用特判和元数据键硬扛，而是统一成"有序 parts + 未知兜底 + 不透明载荷"三个正交概念。

对读者的最后建议：读这份源码时，手里永远备两条线——**一条调用线**（ChatClient → Advisor 链 → ChatModel → 厂商 SDK），**一条数据线**（Prompt/Message/MessagePart → ChatResponse/Generation/Usage）。所有设计问题的答案都藏在这两条线的交点上：某个类存在，是为了让某段数据在某个环节被某种方式处理。
