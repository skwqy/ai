# Spring REST Docs 深度源码解析（写给初学者的架构全景）——兼与 Swagger（OpenAPI）的正面对比

> **本文基于的源码**：`D:\code\3rd\spring-restdocs`，版本 **4.1.0-SNAPSHOT**（main 分支快照，Git commit `d37b38a842`，2026-10-02）。文中所有【源码证据】的文件路径与行号均为对该快照实际读取所得。
>
> **版本取舍说明**：REST Docs 4.0.0 于 2025-11-19 随 Spring Boot 4 / Framework 7 世代一同 GA（基线 Framework 7.0、Jackson 3、JUnit 6）；本文快照是 4.1 开发线，已包含 **RestTestClient 支持**（4.1.0-RC1 于 2026-10-02 合入）。除 RestTestClient 相关章节外，其余内容对已发布的 4.0.x 同样成立；3.x（Framework 6 世代）的架构与之高度一致，差异点在 1.6 节逐项给出。
>
> **阅读约定**：沿用本系列《Spring Framework 深度源码解析》的体例——每章"先白话、后源码"，先用一两句人话讲清"这是什么、为什么需要它"，再给出源码证据链（`模块/src/main/java/.../类名.java` + 行号 + 代码片段）。行号只对该快照精确，读者用 IDEA 打开源码按类名 + 方法名定位即可。为行文简洁，下文将源码根目录 `spring-restdocs-core/src/main/java/org/springframework/restdocs/` 缩写为 **`core/…`**。
>
> **本文的独特之处**：第六章将 REST Docs 与 Swagger（OpenAPI 生态：springdoc-openapi / swagger-core / springfox）做了一次正面对比——从哲学、架构、数据流到选型决策。这一章不依赖本地源码行号，引用的是两个项目官方文档与 2026-10 时点的生态事实（来源 URL 均在 8.5 节列出）。

## 如何读这份文档

如果你是 Spring REST Docs 初学者，推荐两遍读法：

- **第一遍（建立地图，1~2 小时）**：只读第一章（总览）每一节的开头白话段、各章的"小结"节、第六章（与 Swagger 对比）、第七章（贯通视图）。目标是能回答：REST Docs 凭什么保证文档是"对的"？一个 snippet 从测试运行到落盘经历哪几步？它和 Swagger 到底是不是竞争对手？
- **第二遍（深入源码）**：对照每一章的【源码证据】，打开源码逐行读。顺序建议：第二章（核心引擎）→ 第三章（Snippet 家族）→ 第四章（适配器三件套）→ 第五章（渲染与模板，可跳读）→ 第六章（对比，回到架构视角）。

> **一句话预告两者的关系**：Swagger（springdoc）回答"这个 API 长什么样，快来试一试"；REST Docs 回答"这个 API **实际上**长什么样，因为它是从通过的测试里长出来的"。前者赢在交互与生态，后者赢在**可信**。第六章展开。

---

# 一、总览：Spring REST Docs 的定位、设计哲学与整体架构

## 1.1 一句话定位

**Spring REST Docs 是一个"用测试生成文档片段、用手写文档组装成品"的 RESTful API 文档工具**。官方总览页原文（`spring-restdocs-docs/src/docs/antora/modules/ROOT/pages/index.adoc:14-16`）：

> "Spring REST Docs uses snippets produced by tests written with Spring Framework's MockMvc, RestTestClient, or WebTestClient. **This test-driven approach helps to guarantee the accuracy of your service's documentation. If a snippet is incorrect, the test that produces it fails.**"

拆开这句话，它做了三个承诺：

1. **片段（snippet）由测试生成**——你在 MockMvc / WebTestClient / RestTestClient 测试里调用 `document("get-order", ...)`，框架把"这次真实发生过的 HTTP 请求/响应"记录成片段文件（Asciidoctor 或 Markdown）。
2. **准确性由测试执行保证**——文档不是"声明"出来的（像 Swagger 的 `@Schema` 注解），而是"执行"出来的；文档错了，测试就红。
3. **成品文档由你手写组装**——片段只是素材，正文（解释、背景、错误码表、示例）由你用 Asciidoctor 编写，通过 `operation` 宏把片段嵌进来。工具只负责"片段永远是真的"，叙事永远是你的。

它在 Spring 官方项目页（https://spring.io/projects/spring-restdocs ）的自述更为直白，**第一句话就把矛头指向了 Swagger**：

> "This approach **frees you from the limitations of the documentation produced by tools like Swagger**. It helps you to produce documentation that is accurate, concise, and well-structured."

这正是本文第六章要正面回答的问题：这十年的"恩怨"到底谁对？答案是：**它们根本不是同一类东西**——Swagger 是"API 规范 + 交互式 UI"生态，REST Docs 是"文档正确性"的工程方法。它们甚至可以同轨运行（6.8 节）。

## 1.2 设计哲学：读源码前先记住四句话

1. **文档是测试的副产品，正确性靠"双向校验"兜底**。你声明了 `fieldWithPath("id")` 但响应里没有 `id`？测试失败。响应里有 `id` 但你没文档化？测试也失败（除非 relaxed 模式）。这不是文档工具，是一份"文档契约测试"（见 3.5 节 `validateFieldDocumentation` 源码）。
2. **生成与呈现彻底分离**。core 模块对 Asciidoctor/Markdown 的知识被压缩到两处：模板格式 `TemplateFormat`（决定片段文件扩展名与默认模板集）与 WriterResolver（决定写到哪）。片段是"数据"，Asciidoctor 是"呈现"；换 Markdown、自定义 Mustache 模板、换输出目录，都不碰生成逻辑（第五章）。
3. **框架适配 = Converter + Configurer + 触发器三件套**。core 对 MockMvc/WebTestClient/RestTestClient **零依赖**：`RestDocumentationGenerator<REQ, RESP>` 是泛型类，任何测试框架只要能提供"一对 Converter"就能接入。4.1 新增 RestTestClient 支持时，只新增了一个模块、没有任何 core 改动（第四章）——这是该架构最好的证明。
4. **一切皆 Map**。配置在 `Map<String, Object> configuration` 里以类全名为键传递（configurer 回调的产物），数据在 `Operation.getAttributes()` 里以类全名为键传递（模板引擎、WriterResolver、上下文全靠它流动）。看着土，但这是它把"可插拔"做到极致的方式（2.4、2.6 节）。

## 1.3 模块分层全景

REST Docs 是多模块 Gradle 工程（`settings.gradle` 实测注册 8 个模块）。按"用户感知"分层如下：

```
┌─────────────────────────── 测试框架适配层（三选一，互不依赖）───────────────────────────┐
│  spring-restdocs-mockmvc（MockMvc，Spring MVC 同步栈，2015 起）                        │
│  spring-restdocs-webtestclient（WebTestClient，WebFlux 响应式栈，2.0 起）              │
│  spring-restdocs-resttestclient（RestTestClient，Framework 7 新客户端，4.1 起）        │
├──────────────────────────────── 文档生成内核 ─────────────────────────────────────────┤
│  spring-restdocs-core（RestDocumentationGenerator 引擎、Snippet 家族、描述符、          │
│                       预处理器、模板引擎、上下文管理 —— 唯一的重模块）                   │
├──────────────────────────────── 呈现辅助层 ───────────────────────────────────────────┤
│  spring-restdocs-asciidoctor（AsciidoctorJ 扩展：operation 宏 + {snippets} 属性注入）  │
├──────────────────────────────── 发布与工程层 ─────────────────────────────────────────┤
│  spring-restdocs-bom（用户 BOM：只约束 4 个发布构件）                                   │
│  spring-restdocs-platform（仓库内部版本对齐平台，不发布）                               │
│  spring-restdocs-docs（Antora 官方文档源 + 文档示例测试，不发布构件）                   │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

各模块"用户视角"的一句话职责：

| 模块 | 你什么时候会 import 它 | 主要内容物 |
|---|---|---|
| spring-restdocs-core | 几乎不会直接 import（adapters 传递依赖） | 引擎 + 全部 snippet 实现类 |
| spring-restdocs-mockmvc | 写 Spring MVC 的 MockMvc 测试 | `MockMvcRestDocumentation`、`RestDocumentationResultHandler`、`RestDocumentationRequestBuilders` |
| spring-restdocs-webtestclient | 写 WebFlux 的 WebTestClient 测试 | `WebTestClientRestDocumentation` |
| spring-restdocs-resttestclient | 写 Framework 7 RestTestClient 测试 | `RestTestClientRestDocumentation` |
| spring-restdocs-asciidoctor | 用 Gradle/Maven 插件跑 Asciidoctor 生成 HTML | `operation` block macro（Ruby）+ 属性注入（Java） |
| spring-restdocs-bom | 父 POM 里 import 管理版本 | 仅版本约束 |

> **一个耐人寻味的细节**：截至本文快照（2026-10-02），`spring-restdocs-bom/build.gradle:9-15` 的约束列表**尚未包含 resttestclient**（BOM 最后改动于 2025-06-16，早于 resttestclient 模块的合入）。用 BOM 管理版本的读者想引入 resttestclient，目前需要自行写版本号——这是阅读源码仓库才能发现的真实状态。

## 1.4 模块依赖图（以各模块 build.gradle 实证）

```
                     ┌────────────────────────────┐
                     │  spring-restdocs-core      │
                     │  implementation:           │
                     │   - tools.jackson:jackson- │
                     │     databind (Jackson 3!)  │
                     │   - org.springframework:   │
                     │     spring-web             │
                     │   - com.samskivert:        │
                     │     jmustache (1.16)       │
                     │  optional:                 │
                     │   - jakarta.validation-api │
                     │   - hibernate-validator    │
                     │   - junit-jupiter-api      │
                     └──────────△─────────────────┘
          ┌────────────────────┼────────────────────┐
          │                    │                    │
 spring-restdocs-       spring-restdocs-      spring-restdocs-
    mockmvc              webtestclient         resttestclient
   + spring-test        + spring-test         + spring-test
   (MockMvc)            (WebTestClient)       (RestTestClient)

 spring-restdocs-asciidoctor ── 依赖 asciidoctorj 3.0（对 core 零依赖！）
```

真实依赖声明（`spring-restdocs-core/build.gradle:14-21`，逐行验证）：

| 依赖 | 性质 | 用途 |
|---|---|---|
| `tools.jackson.core:jackson-databind` | implementation | JSON 解析（字段路径、类型推断、链接提取）——注意是 **Jackson 3**（`tools.jackson` 包，4.0 起），不是 `com.fasterxml.jackson` |
| `org.springframework:spring-web` | implementation | `HttpHeaders`、`MediaType`、`HttpMethod` 等 HTTP 抽象 |
| `com.samskivert:jmustache` | implementation | Mustache 模板引擎（固定 1.16，见 `spring-restdocs-platform/build.gradle:12`） |
| `jakarta.validation:jakarta.validation-api` | optional | Bean Validation 约束解析（constraints 包，不用则不加载） |
| `org.hibernate.validator:hibernate-validator` | optional | 测试期实现 + Hibernate 专有约束描述 |
| `org.junit.jupiter:junit-jupiter-api` | optional | 仅 `RestDocumentationExtension` 一个类用到 |

> 这个依赖表本身就是一份架构声明：**REST Docs 的"重"只在文档生成期（测试 JVM），运行时（生产 JVM）零依赖**——这一点与 springdoc（要在生产应用里跑 openapi 端点）有本质区别，是第六章对比的重要维度。

## 1.5 关键问题 → REST Docs 方案映射（全文导览）

| 如果你想知道…… | 答案所在章节 | 源码入口 |
|---|---|---|
| `document("id", snippet...)` 一行背后发生了什么 | 2.4 | `generate/RestDocumentationGenerator.java:183` |
| 文档片段写到了哪个文件、谁决定的 | 2.2 + 5.6 | `snippet/StandardWriterResolver.java:67` |
| `{method-name}/{step}` 这类占位符如何解析 | 5.6 | `snippet/RestDocumentationContextPlaceholderResolver.java:65` |
| 字段表为什么会自动检查"文档了不存在的字段" | 3.5 | `payload/AbstractFieldsSnippet.java:203` |
| `fieldWithPath("a.b[*].c")` 通配符怎么实现的 | 3.5 | `payload/JsonFieldPath.java:37`、`JsonFieldProcessor.java:89` |
| 文档里的 URI 为什么和测试请求的不一样 | 2.5 + 4.2 | `operation/preprocess/UriModifyingOperationPreprocessor.java:120` |
| MockMvc 为什么要用 `RestDocumentationRequestBuilders.get` | 4.2 | `mockmvc/RestDocumentationRequestBuilders.java:53` |
| WebTestClient/RestTestClient 是怎么挂进测试的 | 4.3/4.4 | `webtestclient/...Configurer.java:88`、`resttestclient/...Configurer.java:88` |
| @NotNull 约束如何变成"Must not be null" | 3.11 | `constraints/ValidatorConstraintResolver.java:63` |
| curl/HTTPie 命令片段怎么拼出来的 | 3.9 | `cli/CurlRequestSnippet.java:89` |
| 片段最终如何变成一本书 | 5.5 + 7.2 | `asciidoctor/.../operation_block_macro.rb:14` |
| 和 Swagger 怎么选、能不能一起用 | **第六章** | — |

## 1.6 版本演进：1.0 → 4.1 关键变化对比

### 1.6.1 版本时间线

| 大版本 | GA 日期 | 基线 | 主题词 |
|---|---|---|---|
| 1.0 | 2015-10-07 | Java 6+, Framework 4.x | 项目 GA（Andy Wilkinson）：MockMvc + REST Assured；curl/HTTPie/HTTP 报文/payload 片段 |
| 1.1 | 2016-05-31 | Framework 4.2 | REST Assured 正式集成（可测任意 HTTP API）；**Markdown 输出**；`ManualRestDocumentation`（TestNG） |
| 1.2 | 2017-04-24 | Framework 4.3 | 约定输出目录 `target/generated-snippets`；**新模块 spring-restdocs-asciidoctor**（operation 宏）；字段路径通配符 `*`；multipart parts、cookies |
| 2.0 | 2017-11-28 | Framework 5, Java 8 | **WebTestClient 支持**（WebFlux）；JUnit 5 `RestDocumentationExtension`；默认预处理器 |
| 3.0 | 2022-11-21 | Framework 6, Jakarta, Java 17 | `requestParameters` 拆分为 **query/form 参数**；**cookies 独立文档化**；官方 BOM；REST Assured 5.2 |
| 4.0 | 2025-11-19 | Framework 7, **Jackson 3**, JUnit 6 | JSpecify null safety；Antora 文档；**移除 REST Assured 集成**（不兼容 Groovy 5，且维护资源让位 RestTestClient）；移除 3.0 弃用 API |
| 4.1 | 开发中（本文快照） | Framework 7.1 | **RestTestClient 支持**（issue #995，2026-10-02 合入） |

（日期与特性依据 GitHub Releases、官方博客公告与 Release Notes Wiki，URL 见 8.5 节。）

### 1.6.2 对初学者的意义：哪些知识过时了，哪些永远有效

- **过时的**：`requestParameters()`（3.0 拆分为 `queryParameters()`/`formParameters()`，旧模板仅为兼容保留——`payload` 同层的 `request/AbstractParametersSnippet.java` 注释与 `default-request-parameters.snippet` 遗留文件可证）；REST Assured 集成（4.0 移除，issue #1020 已被标记 not_planned——官方理由：以 REST Docs 的维护资源同时支持 REST Assured 与 RestTestClient 并不明智）；JUnit 4 支持（4.0 移除）。
- **永远有效的**：`RestDocumentationGenerator.handle()` 六步流水线（2.4 节）自 1.1.0（`@since 1.1.0`）以来骨架未变；`TemplatedSnippet.document()` 的"取模板 → 建 model → 渲染"三段式；描述符双向校验语义；适配器三件套。**2015 年学的 REST Docs 心智模型，到 4.1 依然成立**——这是它和 springfox（已死）命运截然不同的架构原因之一。

## 1.7 全文章节地图

```
第一章 总览          —— 你在这里：定位、哲学、模块、版本
第二章 核心引擎      —— 上下文、Operation 模型、RestDocumentationGenerator 流水线、预处理器
第三章 Snippet 家族  —— 21 种片段全表、描述符体系、fields 深潜（JSON 路径/类型推断/双向校验）、
                        参数/头/Cookie/HTTP/CLI/超媒体/约束
第四章 适配器三件套  —— mockmvc / webtestclient / resttestclient 的同构架构与差异
第五章 渲染与呈现    —— Mustache 模板三级查找、自定义输出、asciidoctor operation 宏
第六章 与 Swagger 对比 —— 哲学、数据流、全维度对比、生态现状、选型决策
第七章 贯通视图      —— 三条时间线 + 设计模式视角
第八章 附录          —— 接口/snippet/占位符/预处理器速查表、阅读入口清单、参考链接
```

---
# 二、核心引擎：上下文、Operation 与 RestDocumentationGenerator（spring-restdocs-core）

## 2.1 模块定位与包结构

**先说白话**：core 模块回答一个问题——"给定一对（请求，响应），怎么把它变成一组文档片段文件"。它内部其实只有三件事：**记上下文**（哪次测试、第几步、写到哪）、**建模**（把框架特有的请求/响应对象翻译成中立的 `Operation`）、**渲染**（Snippet 按 Mustache 模板产文件）。

包结构全景（`core/…` 下实测 12 个包、约 150 个类）：

| 包 | 职责 | 本文位置 |
|---|---|---|
| （根包） | `RestDocumentationContext` 上下文体系 | 2.2 |
| `generate` | `RestDocumentationGenerator` 引擎（**全库唯一的主干道**） | 2.4 |
| `operation` | `Operation` 中立模型 + Converter SPI | 2.3 |
| `operation.preprocess` | 预处理器（URI/头/内容改写、脱敏） | 2.5 |
| `snippet` | `Snippet` 接口、`TemplatedSnippet`、WriterResolver、占位符 | 3.1 |
| `payload` / `request` / `headers` / `cookies` | 四类内容描述片段 | 3.5~3.7 |
| `http` / `cli` | HTTP 报文与 curl/HTTPie 还原片段 | 3.8~3.9 |
| `hypermedia` | 超媒体链接片段（Atom/HAL） | 3.10 |
| `constraints` | Bean Validation 约束描述 | 3.11 |
| `config` | Configurer 配置体系（流式 API 的骨架） | 2.6 |
| `templates` | 模板引擎与资源解析 | 5.1~5.3 |
| `cli`（注意二义） | curl/HTTPie **命令生成**（不是命令行工具！） | 3.9 |

> **一个容易望文生义的坑**：`cli` 包和"命令行使用 REST Docs"毫无关系——它生成的是**文档里展示给读者的** `curl` / `HTTPie` 命令（`cli/CliDocumentation.java:48` `curlRequest()`）。

## 2.2 RestDocumentationContext：一次测试会话的"上下文令牌"

**先说白话**：每次测试方法执行时，框架要记住三件事——这是哪个测试类哪个方法（决定输出目录名）、这是本次测试的第几次请求（`{step}` 占位符，一个测试里多次 `document()` 不会互相覆盖）、片段写到哪个根目录。这三件事被打包成一个不可变对象 `RestDocumentationContext`，随 `Operation.attributes` 流动。

### 2.2.1 接口只有四个方法

【源码证据】`core/RestDocumentationContext.java:27-51`：

```java
public interface RestDocumentationContext {
	Class<?> getTestClass();          // :33 当前测试类
	String getTestMethodName();       // :39 当前测试方法名
	int getStepCount();               // :45 当前步数（一次测试内第几次操作）
	File getOutputDirectory();        // :51 片段输出根目录
}
```

### 2.2.2 stepCount：一个测试多次 document 的关键

【源码证据】`core/StandardRestDocumentationContext.java:27-54`：

```java
final class StandardRestDocumentationContext implements RestDocumentationContext {
	private final AtomicInteger stepCount = new AtomicInteger(0);   // :29
	...
	int getAndIncrementStepCount() {                                // :53
		return this.stepCount.getAndIncrement();
	}
```

`getAndIncrementStepCount` 是包私有的，只被 `ManualRestDocumentation.beforeOperation()` 调用（`core/ManualRestDocumentation.java:87-91`）。这就是文档里 `{method-name}/{step}` 目录约定的原子来源：同一个测试方法里先 `document("create")` 再 `document("update")`，两者分别落在 `{method-name}/1/`、`{method-name}/2/`。

### 2.2.3 上下文的两个管理者：JUnit 扩展与手动管理

**JUnit（推荐路径）**：`RestDocumentationExtension` 实现 `BeforeEachCallback / AfterEachCallback / ParameterResolver` 三个 JUnit Jupiter SPI（【源码证据】`core/RestDocumentationExtension.java:34`）：

```java
public class RestDocumentationExtension implements BeforeEachCallback, AfterEachCallback, ParameterResolver {
```

它的实现非常薄（`:57-63`）：`beforeEach` 委托 `ManualRestDocumentation.beforeTest(...)`，`afterEach` 委托 `afterTest()`；`resolveParameter`（`:75`）则把一个**惰性 lambda** 注入测试方法参数——这就是你在测试签名里写 `RestDocumentationContextProvider restDocumentation` 参数的来源：

```java
public Object resolveParameter(ParameterContext parameterContext, ExtensionContext context) {
	return (RestDocumentationContextProvider) () -> getDelegate(context).beforeOperation();   // :75-76
}
```

注意这个 lambda 的精妙之处：`beforeOperation()`（每次 HTTP 操作时自增 step）**不在测试开始时执行，而在第一次被调用时执行**，保证 step 计数与实际的 `document()` 调用一一对应。每个测试的 `ManualRestDocumentation` 实例存在 JUnit 的 `ExtensionContext.Store` 里（`:84-86`，`Namespace.create(getClass(), context.getUniqueId())`），测试间天然隔离。

**TestNG / 手动管理**：`ManualRestDocumentation`（`core/ManualRestDocumentation.java:37`）暴露三段式生命周期 `beforeTest → beforeOperation → afterTest`（`:73`/`:87`/`:82`），并在重复调用时给出自解释的断言信息（`:74` `"Context already exists. Did you forget to call afterTest()?"`）。

### 2.2.4 默认输出目录：对构建工具的"约定式探测"

【源码证据】`core/ManualRestDocumentation.java:93-97`：

```java
private static File getDefaultOutputDirectory() {
	if (new File("pom.xml").exists()) {
		return new File("target/generated-snippets");
	}
	return new File("build/generated-snippets");
}
```

Maven 用 `target/`、Gradle 用 `build/`——靠"工作目录下有没有 pom.xml"来猜。这个朴素实现解释了官方文档的约定（`tutorial/pages/getting-started/index.adoc:232-243`）：Maven 项目片段在 `target/generated-snippets`，Gradle 在 `build/generated-snippets`。

## 2.3 Operation 模型：框架中立的请求/响应抽象

**先说白话**：MockMvc 的请求叫 `MockHttpServletRequest`，WebTestClient 的叫 `ExchangeResult`，RestTestClient 的也叫 `ExchangeResult`（但在另一个包）。如果 Snippet 直接消费这些类型，就得写三套。REST Docs 的解法是：**先统一翻译成 `Operation`，再渲染**——`Operation` 就是这个工具世界的"通用语"。

### 2.3.1 Operation：四个属性的不可变快照

【源码证据】`core/operation/Operation.java:26-50`：

```java
public interface Operation {
	Map<String, Object> getAttributes();   // :32 属性总线（上下文/模板引擎/WriterResolver 都从这里来）
	String getName();                      // :38 操作名（document("get-order") 的 "get-order"）
	OperationRequest getRequest();         // :44
	OperationResponse getResponse();       // :50
}
```

`Operation` 自身没有 method/uri/headers——它们在 `OperationRequest`/`OperationResponse` 里（`operation/OperationRequest.java`：`getUri/getMethod/getHeaders/getContent/getParts/getCookies`；`operation/OperationResponse.java`：`getStatus/getHeaders/getContent/getCookies`）。`StandardOperation`（`operation/StandardOperation.java:26`）是唯一实现，纯数据类。

### 2.3.2 不可变改造工厂族

`OperationRequestFactory`（`operation/OperationRequestFactory.java:34`）值得一提：它不仅"创建"，还提供 `createFrom(original, newContent)`（`:80`）、`createFrom(original, newHeaders)`（`:92`）这类**基于旧实例派生新实例**的方法——这正是预处理器"改写"请求的实现方式：`Operation` 一旦构建就是只读的，所有"修改"都是"以旧造新"。`OperationResponseFactory`、`OperationRequestPartFactory` 同构。

### 2.3.3 Converter SPI：翻译官接口

【源码证据】`core/operation/RequestConverter.java:37` 与 `ResponseConverter.java:37`：

```java
public interface RequestConverter<T> {
	OperationRequest convert(T request) throws ConversionException;
}
```

两个泛型接口就是 core 与外部测试框架之间的**唯一接缝**。三个适配器模块各自实现一对：`MockMvcRequestConverter<MockHttpServletRequest>`、`WebTestClientRequestConverter<ExchangeResult>`、`RestTestClientRequestConverter<ExchangeResult>`（第四章逐一展开）。

## 2.4 RestDocumentationGenerator.handle：文档生成的六步流水线（本章核心）

**先说白话**：所有配置、所有适配器、所有 Snippet，最终都汇入一个方法。读懂它，REST Docs 就通了一半。

【源码证据】`core/generate/RestDocumentationGenerator.java:183-196`（完整摘录）：

```java
public void handle(REQ request, RESP response, Map<String, Object> configuration) {
	Map<String, Object> attributes = new HashMap<>(configuration);                                    // ①
	OperationRequest operationRequest = preprocessRequest(this.requestConverter.convert(request), attributes);   // ②③
	OperationResponse operationResponse = preprocessResponse(this.responseConverter.convert(response), attributes); // ②③
	Operation operation = new StandardOperation(this.identifier, operationRequest, operationResponse, attributes);  // ④
	try {
		for (Snippet snippet : getSnippets(attributes)) {                                             // ⑤
			snippet.document(operation);
		}
	}
	catch (IOException ex) {
		throw new RestDocumentationGenerationException(ex);                                           // ⑥
	}
}
```

六步分解：

1. **配置 → 属性**：configuration map（Configurer 体系的产物，见 2.6）整体拷贝进 `attributes`。此后配置与数据同船——`Operation.attributes` 既是 Snippet 的工作台也是数据总线（`RestDocumentationContext`、`TemplateEngine`、`WriterResolver` 都以类全名为键住在里面，`TemplatedSnippet.java:76-81` 亲证）。
2. **翻译**：适配器提供的 `RequestConverter` 把 `REQ`（如 `MockHttpServletRequest`）变成中立的 `OperationRequest`。
3. **预处理**：局部预处理器（`document()` 重载传入或默认）与全局默认预处理器**顺序串联**执行——合并逻辑在 `getPreprocessors`（`:256-265`）：先加 per-generator 的，再加 configuration 里 `ATTRIBUTE_NAME_DEFAULT_OPERATION_REQUEST_PREPROCESSOR` 指定的默认的。典型用途：`prettyPrint()` 美化 JSON、`modifyUris()` 改写文档 URI、`maskLinks()` 脱敏（2.5 节）。
4. **建模**：`StandardOperation` 把名字、请求、响应、属性打包。
5. **渲染**：**默认片段在前、本次 `document()` 传入的在后**（`getSnippets`，`:211-219`）。默认片段 6 个由 `SnippetConfigurer.withDefaults` 注入 configuration（`config/SnippetConfigurer.java:43-45`）。
6. **异常包装**：只包 `IOException`——snippet 内部的校验失败（字段缺失、类型不匹配）会以 `SnippetException` 原样抛出，直接让测试红掉。**"文档错误 = 测试失败"就是在这里成立的**。

### 2.4.1 四个公开常量：Map 总线的"路牌"

`RestDocumentationGenerator` 的 4 个 `ATTRIBUTE_NAME_*` 常量（`:53-70`）是整个系统里少数几个"跨模块共享的字符串键"：

| 常量 | 值 | 谁写入 / 谁读取 |
|---|---|---|
| `ATTRIBUTE_NAME_URL_TEMPLATE` | `org.springframework.restdocs.urlTemplate` | mockmvc 的 `RestDocumentationRequestBuilders` 写入 / `PathParametersSnippet` 读取（3.6） |
| `ATTRIBUTE_NAME_DEFAULT_SNIPPETS` | `...defaultSnippets` | `SnippetConfigurer.apply` 写入 / `getSnippets` 读取 |
| `ATTRIBUTE_NAME_DEFAULT_OPERATION_REQUEST_PREPROCESSOR` | `...defaultOperationRequestPreprocessor` | `OperationPreprocessorsConfigurer` 写入 / `getPreprocessors` 读取 |
| `ATTRIBUTE_NAME_DEFAULT_OPERATION_RESPONSE_PREPROCESSOR` | `...defaultOperationResponsePreprocessor` | 同上（响应侧） |

### 2.4.2 withSnippets：同一 handler 追加片段

`withSnippets(Snippet...)`（`:205-208`）返回一个**只替换片段列表**的新 generator。适配器用它实现"同一个 `document()` 结果上继续 `.document(snippet)`"的流式追加——mockmvc 的 `RestDocumentationResultHandler.document(Snippet...)`（`mockmvc/RestDocumentationResultHandler.java:70-81`）同时还会从 configuration 里**移除** `ATTRIBUTE_NAME_DEFAULT_SNIPPETS` 键，避免 6 个默认片段被二次生成。

## 2.5 OperationPreprocessor 体系：文档世界的"美颜滤镜"

**先说白话**：测试发出去的请求是"测试的样子"（`localhost:0`、带 Authorization 头、JSON 挤成一行），文档要的是"书的样子"（`api.example.com`、无敏感头、格式化 JSON）。预处理器就是中间的美颜滤镜，而且**它改的只是文档，不影响测试本身**——因为预处理发生在测试已经完成之后（`handle()` 是在 `andDo()` 阶段执行的）。

### 2.5.1 两个入口接口与静态工厂

【源码证据】`core/operation/preprocess/OperationRequestPreprocessor.java:37` / `OperationResponsePreprocessor.java:37`——各自只有一个方法 `preprocess(OperationRequest/Response)`。用户从不直接实现它们，而是用 `Preprocessors` 静态工厂组合（`operation/preprocess/Preprocessors.java`，方法行号实测）：

| 工厂方法 | 行 | 作用 |
|---|---|---|
| `preprocessRequest(OperationPreprocessor...)` | `:48` | 把若干 `OperationPreprocessor` 聚合成一个请求预处理器 |
| `preprocessResponse(OperationPreprocessor...)` | `:58` | 同上（响应侧） |
| `prettyPrint()` | `:67` | JSON/XML 美化（内部 `PrettyPrintingContentModifier`，Jackson INDENT_OUTPUT） |
| `maskLinks()` / `maskLinks(String)` | `:76`/`:86` | 把超媒体链接 href 换成 `...`（`LinkMaskingContentModifier`） |
| `replacePattern(Pattern, String)` | `:98` | 正则替换内容（如把 token 换成占位符） |
| `modifyHeaders()` | `:108` | 返回 `HeadersModifyingOperationPreprocessor`（流式：`remove/keep/removeMatching/add/set`，见下） |
| `modifyUris()` | `:118` | 返回 `UriModifyingOperationPreprocessor`（`scheme/host/port/removePort`，`UriModifyingOperationPreprocessor.java:78-109`） |

### 2.5.2 组合式实现：三个"改性"预处理器

预处理器实现的巧妙处在于**职责正交**：

- `ContentModifyingOperationPreprocessor`（`:61`）：只改内容，包装一个 `ContentModifier` 策略（prettyPrint/maskLinks/replacePattern 都是它的实现）；
- `HeadersModifyingOperationPreprocessor`（`:40`）：只改头，流式方法 `add/set/remove/remove/removeMatching`（`:73-117`），配套 5 个私有 `applyTo(headers)` 命令对象；头过滤有两种口径——`ExactMatchHeaderFilter`（`:41`）与 `PatternMatchHeaderFilter`（`:50`，支持通配，官方文档里 `X-*` 这类写法靠它）；
- `UriModifyingOperationPreprocessor`（`:59`）：只改 URI，`preprocess`（`:120`）用 `OperationRequestFactory.createFrom` 以旧造新。

而 `Preprocessors.preprocessRequest(a, b, c)`（`:48`）把多个 `OperationPreprocessor` 串成一个 `OperationRequestPreprocessor`——责任链模式的教科书用法。

### 2.5.3 预处理器在哪一层被应用

回看 2.4 流水线第③步：预处理器在 **Converter 之后、StandardOperation 构建之前**执行。这保证了所有 Snippet 看到的都是"美颜后"的世界——包括双向校验（3.5 节）：如果你用 `modifyHeaders().remove("Authorization")` 删掉了头，`request-headers` 片段也不会再要求它存在。**文档一致性以预处理后的世界为准**。

## 2.6 配置体系：Configurer 流式 API 的骨架（config 包）

**先说白话**：你在测试里写的这条链——

```java
MockMvcRestDocumentation.documentationConfiguration(this.restDocumentation)
	.snippets().withDefaults(...).withEncoding("UTF-8").and()
	.operationPreprocessors().withRequestDefaults(prettyPrint())
```

——是典型的流式配置 API。它的实现骨架在 core 的 `config` 包：**所有配置方法都只是往一个 Map 里写键值，真正的应用发生在生成时的 `apply()` 回调**。

### 2.6.1 四层继承结构

```
RestDocumentationConfigurer<S,P,T>                    抽象骨架（泛型三元组）
├─ apply(configuration, context)                      统一回调入口（:99-105）
├─ templateEngine(...)/writerResolver(...)            终态方法（:76-91）
├─ abstract snippets()/operationPreprocessors()       留给适配器实现的两个口子
├─ 内部 TemplateEngineConfigurer / WriterResolverConfigurer   默认值兜底（:112-151）
└─ 三个适配器实现：
   ├─ MockMvcRestDocumentationConfigurer    implements MockMvcConfigurer
   ├─ WebTestClientRestDocumentationConfigurer implements ExchangeFilterFunction
   └─ RestTestClientRestDocumentationConfigurer implements ClientHttpRequestInterceptor
```

【源码证据】`core/config/RestDocumentationConfigurer.java:50-105`（节选）：

```java
public abstract class RestDocumentationConfigurer<S extends AbstractConfigurer,
		P extends AbstractConfigurer, T> {
	...
	protected final void apply(Map<String, Object> configuration, RestDocumentationContext context) {  // :99
		List<AbstractConfigurer> configurers = Arrays.asList(snippets(), operationPreprocessors(),
				this.templateEngineConfigurer, this.writerResolverConfigurer);
		for (AbstractConfigurer configurer : configurers) {
			configurer.apply(configuration, context);
		}
	}
```

**四个 configurer 依次把配置写进同一个 map**，键是类全名字符串。链式回退靠 `AbstractNestedConfigurer.and()`（`config/AbstractNestedConfigurer.java:38-41`）返回父节点。

### 2.6.2 SnippetConfigurer：默认片段与默认值的注册处

【源码证据】`core/config/SnippetConfigurer.java:43-59`：

```java
private List<Snippet> defaultSnippets = new ArrayList<>(Arrays.asList(CliDocumentation.curlRequest(),
		CliDocumentation.httpieRequest(), HttpDocumentation.httpRequest(), HttpDocumentation.httpResponse(),
		PayloadDocumentation.requestBody(), PayloadDocumentation.responseBody()));
...
public static final String DEFAULT_SNIPPET_ENCODING = "UTF-8";                       // :52
public static final TemplateFormat DEFAULT_TEMPLATE_FORMAT = TemplateFormats.asciidoctor();  // :59
```

这就是"默认生成 6 个片段"（curl-request、httpie-request、http-request、http-response、request-body、response-body）的源码出处（官方文档 `configuration/default-snippets.adoc:4-11` 与之一致）。`apply()`（`:74-78`）把 `SnippetConfiguration`（编码+格式，`config/SnippetConfiguration.java:27-36`）与默认片段列表写入 configuration，供 2.4 节的 `getSnippets` 消费。

### 2.6.3 默认 TemplateEngine 的惰性创建

一个实现细节体现设计取舍：`TemplateEngineConfigurer.apply`（`config/RestDocumentationConfigurer.java:112-128`）只在用户没有指定引擎时才创建默认引擎，并且**只对 Asciidoctor 格式**注入 `tableCellContent` lambda（防止字段描述里的 `|` 破坏 Asciidoctor 表格，见 5.3 节）：

```java
engineToUse = new MustacheTemplateEngine(
		new StandardTemplateResourceResolver(snippetConfiguration.getTemplateFormat()),
		Charset.forName(snippetConfiguration.getEncoding()), Mustache.compiler().escapeHTML(false),
		templateContext);                                                              // :122-125
```

`WriterResolverConfigurer.apply`（`:141-151`）同理，默认装上 `StandardWriterResolver(new RestDocumentationContextPlaceholderResolverFactory(), encoding, templateFormat)`（`:147-148`）。

## 2.7 本章小结

- **一次 `document()` 的完整链路**：`document(identifier, snippets)` 把标识符 + Converter 对 + 片段打包成 `RestDocumentationGenerator`（包进 ResultHandler/Consumer 触发器）→ 测试执行后触发器调用 `handle(request, response, configuration)` → **convert 翻译 → preprocess 美颜 → Operation 建模 → snippets 逐个渲染落盘**。
- **配置**：流式 Configurer 只是"往 Map 里写字"的延迟注册，`apply()` 在每次操作前被适配器调用（第四章）；**数据**：`Operation.attributes` 是 Snippet 的工作台（上下文、模板引擎、WriterResolver 以类全名为键居住于此）。
- **上下文**：`RestDocumentationContext`（测试类/方法、step 原子计数、输出目录）由 JUnit 扩展或 `ManualRestDocumentation` 管理，`{method-name}/{step}` 目录约定由此而来。
- **预处理器**：在翻译之后、建模之前执行，改写的是"文档看到的世界"；责任链组合、以旧造新不可变。
- **一句话**：core 把"生成文档"收敛为 `handle()` 六步，把一切变化点（框架、配置、呈现）都挤到了三个扩展位上——Converter、Configurer、Snippet。第六章你会看到，Swagger 把同样的变化点挤在了"注解 + 运行时扫描"上——这是两种截然不同的工程信仰。

---
# 三、Snippet 家族全景：默认片段、描述符与双向校验

## 3.1 Snippet 接口与 TemplatedSnippet：一个 snippet 的一生

**先说白话**：`Snippet` 是"给你一个 Operation，你去写一个片段文件"的最小契约；`TemplatedSnippet` 是它的模板化基类——几乎所有内置片段都继承它，**片段名（文件名）与模板名一一对应**是贯穿全库的约定。

【源码证据】`core/snippet/Snippet.java`（接口本体极简）：

```java
public interface Snippet {

	void document(Operation operation) throws IOException;

}
```

整个体系就建立在这一个方法上——"给我一个 Operation，我把文档写出去"。

【源码证据】`core/snippet/TemplatedSnippet.java:74-84`——**一个 snippet 的一生**（完整摘录，本文最重要的源码片段之一）：

```java
@Override
public void document(Operation operation) throws IOException {
	RestDocumentationContext context = getRequiredAttribute(operation, RestDocumentationContext.class);
	WriterResolver writerResolver = getRequiredAttribute(operation, WriterResolver.class);
	Map<String, Object> model = createModel(operation);
	model.putAll(this.attributes);
	try (Writer writer = writerResolver.resolve(operation.getName(), this.snippetName, context)) {
		TemplateEngine templateEngine = getRequiredAttribute(operation, TemplateEngine.class);
		writer.append(templateEngine.compileTemplate(this.templateName).render(model));
	}
}
```

五步分解：

1. 从 `Operation.attributes` 取出三大基础设施：**上下文**（写到哪）、**WriterResolver**（拿到 Writer）、**模板引擎**（渲染内容）。任一缺失立刻 `SnippetException`（`:86-93`）——这就是 2.4 节说的"属性总线"。
2. 调子类的 `createModel(operation)` 建模型（子类唯一要实现的抽象方法，`:104`）。
3. 叠加 snippet 级 `attributes`（工厂方法传入的自定义键值，可被自定义模板消费——5.4 节）。
4. `WriterResolver.resolve(operationName, snippetName, context)` 拿到文件 Writer。
5. 编译模板、渲染、写入。注意 `compileTemplate` 在每次 `document()` 时调用——JMustache 无缓存，一个测试类跑下来同一模板会被编译多次（性能上无伤大雅，但值得知道）。

### 3.1.1 WriterResolver：文件落在哪、叫什么

【源码证据】`core/snippet/StandardWriterResolver.java:67-81`：

```java
public Writer resolve(String operationName, String snippetName, RestDocumentationContext context) throws IOException {
	PlaceholderResolver placeholderResolver = this.placeholderResolverFactory.create(context);
	String outputDirectory = replacePlaceholders(placeholderResolver, operationName);
	String fileName = replacePlaceholders(placeholderResolver, snippetName) + "."
			+ this.templateFormat.getFileExtension();
	File outputFile = resolveFile(outputDirectory, fileName, context);
	...
}
```

三个规则：
- **operationName 里的 `{…}` 占位符解析成子目录**，snippetName 里的解析成文件名，扩展名来自 `TemplateFormat`（asciidoctor→`.adoc`，markdown→`.md`）；
- 相对路径相对 `context.getOutputDirectory()` 解析（`:95-101`），**绝对路径原样使用**（想写到任意位置都可以）；
- 输出目录为 null 时回退写 `System.out`（`:79`）——这在开发调试时很好用。

占位符的可解析名字只有 7 种（`core/snippet/RestDocumentationContextPlaceholderResolver.java:26-47` javadoc）：`step`、`methodName`/`method-name`/`method_name`、`ClassName`/`class-name`/`class_name`。驼峰转换规则在 `camelCaseToSeparator`（`:129-143`）：`getOrder` → `get-order`。**注意没有 `{method}`、`{segment}` 这种占位符**——社区教程里的写法错误多半源于此。

## 3.2 描述符体系：一个继承链走天下

**先说白话**：你在片段工厂方法里写的 `fieldWithPath("id").description("订单 ID").optional()` 返回的是"描述符"（Descriptor）——它把"字段名 + 人话描述 + 可选性 + 自定义属性"打包，是**人 → 片段**的信息载体。整个体系一个继承链：

```
AbstractDescriptor<T>                    snippet/AbstractDescriptor.java:33
│   attributes(Attribute...)/description(...)
└─ IgnorableDescriptor<T>                snippet/IgnorableDescriptor.java:25
   │   ignored()/isIgnored()
   ├─ FieldDescriptor                    payload/FieldDescriptor.java:31（path/type/optional）
   │   └─ SubsectionDescriptor           payload/SubsectionDescriptor.java:26（"字段及其全部后代"标记子类）
   ├─ ParameterDescriptor                request/ParameterDescriptor.java:28
   ├─ RequestPartDescriptor              request/RequestPartDescriptor.java:28
   ├─ CookieDescriptor                   cookies/CookieDescriptor.java:29
   └─ LinkDescriptor                     hypermedia/LinkDescriptor.java:27
（特例）HeaderDescriptor ── 直连 AbstractDescriptor       headers/HeaderDescriptor.java:28
        —— 不可 ignored()，且 description 无条件必填（AbstractHeadersSnippet.java:58）
```

`attributes` 是描述符的"开放扩展点"：`AbstractDescriptor.attributes(Attribute...)`（`:33-50`）把键值塞进 map，而渲染时 `createModelForDescriptor` 会 `model.putAll(descriptor.getAttributes())`（`payload/AbstractFieldsSnippet.java:288`）——**任何自定义属性都会原样流入 Mustache 模型**。这是"加一列 constraints"这种需求的实现基础（3.11、5.4 节）。

## 3.3 片段总表：21 个模板、两个格式、模型键速查

core 的模板资源按格式分两个目录：`core/src/main/resources/org/springframework/restdocs/templates/asciidoctor/` 与 `markdown/`，**各含 21 个 `default-*.snippet` 文件，两目录一一对应**（实测）。按类别分组：

| 类别 | 片段（snippetName = docId = 文件名） | 模板模型键 | 静态工厂入口 |
|---|---|---|---|
| CLI 还原 | `curl-request`、`httpie-request` | `url`、`options`（httpie 另有 `echoContent`、`requestItems`） | `CliDocumentation` |
| HTTP 报文 | `http-request`、`http-response` | 请求：`method`/`path`/`headers`/`requestBody`；响应：`statusCode`/`statusReason`/`headers`/`responseBody` | `HttpDocumentation` |
| 请求/响应体 | `request-body`、`response-body` | `body`、`language` | `PayloadDocumentation` |
| 字段表 | `request-fields`、`response-fields` | `fields`（每项 `path`/`type`/`description`/`optional` + 自定义 attributes） | 同上 |
| multipart 字段表/体 | `request-part-<part>-fields`、`request-part-<part>-body` | 同上（body 为 `body`/`language`） | 同上 |
| 参数 | `path-parameters`、`query-parameters`、`form-parameters` | `parameters`（每项 `name`/`description`/`optional`）；path 另有 `path` | `RequestDocumentation` |
| multipart 清单 | `request-parts` | `requestParts` | 同上 |
| 头 | `request-headers`、`response-headers` | `headers`（`name`/`description`） | `HeaderDocumentation` |
| Cookie | `request-cookies`、`response-cookies` | `cookies`（`name`/`description`/`optional`） | `CookieDocumentation` |
| 超媒体 | `links` | `links`（每项 `rel`/`description`/`optional`） | `HypermediaDocumentation` |

> **模板 ≠ 片段**：`.snippet` 文件是 JMustache 模板（描述"长什么样"），片段文件是渲染产物（数据已填入）。子段提取、multipart part 名等会让 docId 带后缀（如 `request-fields-beneath-a.b`、`request-part-file-fields`），模板名不变。

一个典型的默认模板（`templates/asciidoctor/default-request-fields.snippet:1-10`，全文）：

```
|===
|Path|Type|Description

{{#fields}}
|{{#tableCellContent}}`+{{path}}+`{{/tableCellContent}}
|{{#tableCellContent}}`+{{type}}+`{{/tableCellContent}}
|{{#tableCellContent}}{{description}}{{/tableCellContent}}

{{/fields}}
|===
```

`tableCellContent` 是 Mustache lambda（5.3 节），负责转义竖线。

## 3.4 fields 深潜：AbstractFieldsSnippet 的六步流水线（本章核心）

**先说白话**：`requestFields(...)` 是 REST Docs 最常用、也最能体现"测试驱动"哲学的片段——它不是"把你说的字段抄下来"，而是**把你说的字段和实际 payload 做双向对账**：说多了、说少了、类型对不上，测试立刻红。这条流水线在 `AbstractFieldsSnippet.createModel`。

【源码证据】`core/payload/AbstractFieldsSnippet.java:149-193`（节选）：

```java
public Map<String, Object> createModel(Operation operation) {
	byte[] content;
	try {
		content = verifyContent(getContent(operation));            // ① 拿 body，为空抛 SnippetException
	}
	catch (IOException ex) { throw new ModelCreationException(ex); }
	MediaType contentType = getContentType(operation);
	if (this.subsectionExtractor != null) {                        // ② 子段提取（beneathPath）
		content = verifyContent(
				this.subsectionExtractor.extractSubsection(content, contentType, this.fieldDescriptors));
	}
	ContentHandler contentHandler = ContentHandler.forContentWithDescriptors(content, contentType,
			this.fieldDescriptors);                                // ③ 选择 JSON 或 XML 处理器
	validateFieldDocumentation(contentHandler);                    // ④ 双向校验（核心！）
	...  // ⑤ 对每个非 ignored descriptor 做类型推断（copyWithType 写回实际类型）
	     // ⑥ 生成 fields 模型（createModelForDescriptor，:276-290）
}
```

### 3.4.1 ③ ContentHandler 的选择：JSON 优先，XML 兜底

【源码证据】`core/payload/ContentHandler.java:61-76`：

```java
try {
	return new JsonContentHandler(content, descriptors);
}
catch (Exception je) {
	try {
		return new XmlContentHandler(content, descriptors);
	}
	catch (Exception xe) {
		throw new PayloadHandlingException("Cannot handle content "
				+ ((contentType != null) ? "with type " + contentType : "of unknown type")
				+ " as it could not be parsed as JSON or XML");
	}
}
```

**选择依据是"能不能解析"，不是 Content-Type**——payload 是 JSON 就走 Jackson 3 解析，否则尝试当 XML（DOM + XPath），都不行才报错。

### 3.4.2 JSON 路径语法：JsonFieldPath 的编译器

【源码证据】`core/payload/JsonFieldPath.java:37-40`：

```java
private static final Pattern BRACKETS_AND_ARRAY_PATTERN = Pattern
	.compile("\\[\'(.+?)\'\\]|\\[([0-9]+|\\*){0,1}\\]");

private static final Pattern ARRAY_INDEX_PATTERN = Pattern.compile("\\[([0-9]+|\\*){0,1}\\]");
```

支持的语法：`a.b`（点分）、`a['b']`（括号取键）、`a.b[0]`（数组下标）、`a.b[]`/`a.b[*]`（数组通配）、裸 `*` 段（map 键通配，`JsonFieldProcessor.java:159-161` 对 `Map.values()` 做通配）。

路径会被编译成 segment 列表并打上 **SINGLE / MULTI** 标记（`PathType` 枚举，`JsonFieldPath.java:153-165`）。判定规则在 `matchesSingleValue`（`:96-105`）：**只要路径中存在"后面还有下级"的数组段，或任意通配符，就是 MULTI**（一对多展开）：

```java
static boolean matchesSingleValue(List<String> segments) {
	Iterator<String> iterator = segments.iterator();
	while (iterator.hasNext()) {
		String segment = iterator.next();
		if ((isArraySegment(segment) && iterator.hasNext()) || isWildcardSegment(segment)) {
			return false;
		}
	}
	return true;
}
```

`JsonFieldProcessor.traverse`（`:89-99`）按 segment 逐级下钻：数组段遇 `Collection` 走 `handleCollectionPayload`（`:101-122`），键段遇 `Map` 走 `handleMapPayload`（`:146-165`）。

### 3.4.3 ④ 双向校验：validateFieldDocumentation

【源码证据】`core/payload/AbstractFieldsSnippet.java:203-226`（核心逻辑）：

```java
private void validateFieldDocumentation(ContentHandler payloadHandler) {
	List<FieldDescriptor> missingFields = payloadHandler.findMissingFields();   // 文档化了但 payload 没有

	String undocumentedPayload = this.ignoreUndocumentedFields ? null : payloadHandler.getUndocumentedContent();

	if (!missingFields.isEmpty() || StringUtils.hasText(undocumentedPayload)) { // payload 有但没文档化
		String message = "";
		if (StringUtils.hasText(undocumentedPayload)) {
			message += String.format("The following parts of the payload were" + " not documented:%n%s",
					undocumentedPayload);
		}
		if (!missingFields.isEmpty()) {
			...
			message += "Fields with the following paths were not found in the" + " payload: " + paths;
		}
		throw new SnippetException(message);   // 错误信息同时列出两个方向
	}
}
```

- **正向缺失**（missing）：非 optional 的 descriptor 在 payload 中找不到 → 失败。判定在 `JsonContentHandler.isMissing`（`:68-72`）：还要求**不嵌套在某个缺失的 optional 字段之下**（`isNestedBeneath` 用 `path.startsWith(a+".")` / `startsWith(a+"[")` 判定，`:86-88`）——这是"optional 对象里的字段也自动 optional"的语义实现。
- **反向冗余**（undocumented）：把所有已文档化字段从 payload 里 `remove`/`removeSubsection`，剩下的内容序列化后非空 → 失败（`JsonContentHandler.getUndocumentedContent:112-131`）。**relaxed 模式**（`relaxedRequestFields` 等）只是把反向校验跳过（`PayloadDocumentation.java:248-250` 传 `ignoreUndocumentedFields=true`），正向缺失照查不误。

`remove()` 与 `removeSubsection()` 的差别（`JsonFieldProcessor.java:227-244`）很精妙：`remove` 只删"空了的"子树（`MapMatch.remove` 遇到非空 map 或含非标量元素的集合时直接返回），`removeSubsection` 无条件删除整个子段——对应 `fieldWithPath`（叶子）与 `subsectionWithPath`（子树）的语义差。

### 3.4.4 ⑤ 类型推断：文档里的 type 是"推断"出来的

你在 `fieldWithPath` 里一般不写 `type()`——因为 **payload 自己会招供**。【源码证据】`core/payload/JsonContentHandler.java:154-182`（`resolveFieldType` 全文）：

```java
public Object resolveFieldType(FieldDescriptor fieldDescriptor) {
	if (fieldDescriptor.getType() == null) {
		return this.fieldTypesDiscoverer.discoverFieldTypes(fieldDescriptor.getPath(), readContent())
			.coalesce(fieldDescriptor.isOptional());      // 没写 type：推断值直接成为文档类型
	}
	if (!(fieldDescriptor.getType() instanceof JsonFieldType)) {
		return fieldDescriptor.getType();                 // 用户给了自定义类型对象：原样使用
	}
	JsonFieldType descriptorFieldType = (JsonFieldType) fieldDescriptor.getType();
	try {
		JsonFieldType actualFieldType = this.fieldTypesDiscoverer
			.discoverFieldTypes(fieldDescriptor.getPath(), readContent())
			.coalesce(fieldDescriptor.isOptional());
		if (descriptorFieldType == JsonFieldType.VARIES || descriptorFieldType == actualFieldType
				|| (fieldDescriptor.isOptional() && actualFieldType == JsonFieldType.NULL)
				|| (isNestedBeneathMissingOptionalField(fieldDescriptor, readContent())
						&& actualFieldType == JsonFieldType.VARIES)) {
			return descriptorFieldType;
		}
		throw new FieldTypesDoNotMatchException(fieldDescriptor, actualFieldType);   // 声明与实际不符
	}
	catch (FieldDoesNotExistException ex) {
		return fieldDescriptor.getType();                 // 字段不存在（optional 嵌套场景）：保留声明值
	}
}
```

规则解读：
- **推断路径**是第一个分支：不写 `type()` 时，`discoverFieldTypes` 的结果（经 `coalesce`）直接成为文档里的类型——`String`、`Array` 都是这么来的。
- **校验路径**是显式声明 `JsonFieldType` 时的对账：声明为 VARIES、与实际相同、optional 字段实际为 NULL、或字段位于缺失的 optional 父字段之下（实际为 VARIES）——四种情况放行；否则 `FieldTypesDoNotMatchException`，测试失败。**又一个"文档错 = 测试红"的落点**。
- JSON 实际类型由 `JsonFieldTypesDiscoverer.discoverFieldTypes`（`payload/JsonFieldTypesDiscoverer.java:39-57`）收集：String→STRING、Map→OBJECT、Collection→ARRAY、Boolean→BOOLEAN、null→NULL、其余归 NUMBER（`:59-76`）。
- MULTI 路径（数组通配）收集到多个类型时 `coalesce`：**optional 的字段剔除 NULL 后若仍有多种类型则折叠为 VARIES**（`payload/JsonFieldTypes.java:41-50`）——这就是文档表格里 `Varies` 的来源。
- `JsonFieldType` 枚举（ARRAY/BOOLEAN/OBJECT/NUMBER/NULL/STRING/VARIES）的 `toString()` 首字母大写——表格里的 `String`、`Array` 就这么来的。
- **XML 没有这个待遇**：`XmlContentHandler.resolveFieldType`（`:213-222`）无法自动推断，不显式 `type()` 一律抛 `FieldTypeRequiredException`。XML 用户必须手工声明每个字段类型。另外 XML 的路径就是 **XPath 表达式直译**（`XmlContentHandler.findMissingFields:78-91` 直接 `compile(fieldPath)`），构造器里还专门禁用了 DOCTYPE 与外部实体（`:63-66`，XXE 防护）。

### 3.4.5 ② 子段提取：beneathPath 与结构一致性

`PayloadDocumentation.beneathPath("a.b")`（`PayloadDocumentation.java:1531-1533`）返回 `FieldPathPayloadSubsectionExtractor`。它的价值：**只对 payload 的一角做文档**（比如分页响应只文档 `content` 数组里的元素）。docId 也会带上后缀（id 规则 `beneath-<path>`，`:62-64` → 片段名如 `request-fields-beneath-a.b`）。

实现里最讲究的是**多元素数组必须结构一致**（`FieldPathPayloadSubsectionExtractor.extractSubsection:103-125`）：先用 `JsonFieldPaths.from(...).getUncommon()` 找出数组各元素之间"非常见的、且非 optional 的"路径，非空即抛 `PayloadHandlingException`——防止你文档的其实是"第一行的形状"而数组里还藏着别的形状。一致时取第一个元素作为子段（`:115`）。

## 3.5 参数三兄弟：path / query / form

3.0 起 `requestParameters` 拆分为三（各实现 `@since 3.0.0`），共享基类 `AbstractParametersSnippet`（模型键 `parameters`，双向校验与 3.4.3 同构：`ignoreUndocumentedParameters` 只放过反向）。差别在**"实际参数"从哪来**：

**PathParametersSnippet：从 URL 模板正则提取。**【源码证据】`core/request/PathParametersSnippet.java:45` 与 `:114-124`：

```java
private static final Pattern NAMES_PATTERN = Pattern.compile("\\{([^/]+?)\\}");
...
protected Set<String> extractActualParameters(Operation operation) {
	String urlTemplate = removeQueryStringIfPresent(extractUrlTemplate(operation));
	Matcher matcher = NAMES_PATTERN.matcher(urlTemplate);
	Set<String> actualParameters = new HashSet<>();
	while (matcher.find()) {
		String match = matcher.group(1);
		...
		actualParameters.add(getParameterName(match));   // "{id:\\d+}" 截掉冒号后的正则部分
	}
	return actualParameters;
}
```

URL 模板来自 operation attribute `ATTRIBUTE_NAME_URL_TEMPLATE`（`:126-132`）。**这就是 MockMvc 必须用 `RestDocumentationRequestBuilders` 的原因**（4.2 节）：MockMvc 发出的是展开后的 URL（`/orders/1`），不塞模板进去，`path-parameters` 片段无从知道参数名叫 `orderId`。

**Query/FormParametersSnippet：从请求直接提取**，分别解析 query string 与 `application/x-www-form-urlencoded` 表单体（`QueryParametersSnippet.java:108-111` 用 `QueryParameters.from(operation.getRequest()).keySet()`；form 同构，工具类是 `operation/QueryParameters.java:48`、`operation/FormParameters.java:46`，都是 `LinkedMultiValueMap` 子类）。

`requestParts`（multipart 清单）直接继承 `TemplatedSnippet`（`RequestPartsSnippet.java:49`），实际 part 名遍历 `operation.getRequest().getParts()` 取 `OperationRequestPart.getName()`（`:171-177`），模型键 `requestParts`。

## 3.6 headers 与 cookies：两个"不对称"的设计

**headers**：`HeaderDescriptor` 直连 `AbstractDescriptor`——**不支持 `ignored()`**，且 `AbstractHeadersSnippet` 构造器对 description **无条件必填**（`headers/AbstractHeadersSnippet.java:58`，连 ignored 也豁免不了——因为它根本没有 ignored 能力）。双向校验只查"缺失"方向（`findMissingHeaders` 跳过 optional，`:96-106`），"未文档化的多余头"永远放行——毕竟响应头里有 `Date`、`X-Content-Type-Options` 这类你不想逐个文档化的东西。

**cookies**（3.0 新增）：结构上是"完整的参数片段翻版"——`CookieDescriptor` 支持 `ignored()`，双向校验与 relaxed 变体全套（`cookies/AbstractCookiesSnippet.java:88-110`）。实际 cookie 名的提取：请求侧从 `operation.getRequest().getCookies()`（`RequestCookiesSnippet.java:91-97`），响应侧从 `getResponseCookies()` 映射 `ResponseCookie::getName`（`ResponseCookiesSnippet.java:91-93`）。

## 3.7 http-request / http-response：报文还原的细节控

`HttpRequestSnippet`（`core/http/HttpRequestSnippet.java:48`）的模型键 `method/path/headers/requestBody`（`:68-76`），有几个体现"较真"的细节：

- multipart 请求的 Content-Type 会追加固定 boundary 常量 `MULTIPART_BOUNDARY = "6o2knFse3p53ty9dmcQvWAIx1zInP11uCfbm"`（`:50`），body 相应写入 part 分隔（`:144-179`）——文档里的报文是可以直接 curl 重放的。
- 请求 cookies 聚合成单个 `Cookie:` 头（`:110-116`）；表单编码的 PUT/POST/PATCH 无 Content-Type 时补 `application/x-www-form-urlencoded`（`:118-120`）。
- `HttpResponseSnippet`（`:41`）模型键 `statusCode/statusReason/headers/responseBody`——`statusReason` 只有状态码是 `HttpStatus` 枚举值才有 reason phrase（`:67`），否则空串。

模板（`templates/asciidoctor/default-http-request.snippet:1-8`）：

```
[source,http,options="nowrap"]
----
{{method}} {{path}} HTTP/1.1
{{#headers}}
{{name}}: {{value}}
{{/headers}}
{{requestBody}}
----
```

## 3.8 cli 包：curl 与 HTTPie 的还原术

**先说白话**：文档里那行可以复制粘贴的 `curl 'http://...' -i -X POST -H 'Content-Type: ...' -d '{...}'`，是 `CurlRequestSnippet` 从预处理后的 `OperationRequest` **反推**出来的。要点：

- `CurlRequestSnippet.getOptions`（`cli/CurlRequestSnippet.java:89-106`）依次拼 `-i`（打印响应头）、`-u`（Basic Auth，由 `CliOperationRequest.getBasicAuthCredentials` Base64 解码 `Authorization: Basic ` 头获得，`cli/CliOperationRequest.java:61-67`）、`-X METHOD`，再连接 `-H`（头）、`--cookie`、`-F`（multipart，文件用 `@filename`）、`-d`（body）。
- `CliOperationRequest`（`:45`）是 `OperationRequest` 包装器，先滤掉对命令行无意义的头：`Content-Length`（curl 会自己算）、`Host`（curl 自己给）等（构造器 `:51-55`）。
- 单引号转义：`escapeSingleQuotes` 把 `'` 换成 `'\''`（`:174-176`）。
- 多行格式由 `CommandFormatter` 决定：默认 `multiLineFormat()` 的分隔符是 `" \\%n    "`（源码字符串，即行尾反斜杠续行 + 换行 + 4 空格缩进，`cli/CliDocumentation.java:139-141`），`singleLineFormat()` 单空格。
- HTTPie 版（`HttpieRequestSnippet.java:51`）的特殊处理：body 用 `echo '...' | ` 管道输入（`:88-97`）；form-urlencoded 输出 `--form` 并跳过 Content-Type 头（`:164-181`）。

## 3.9 hypermedia：链接也是文档对象

**先说白话**：如果你的 API 返回 HAL/Atom 风格的超媒体，`links(...)` 片段会解析响应中的链接并生成一张 rel 表——同样带双向校验（未文档化的 rel、缺失的必选 rel 都会让测试失败，除非 `relaxedLinks` 或 `ignored()`）。

架构是经典的"按 Content-Type 分派策略"：

```
LinksSnippet（hypermedia/LinksSnippet.java:50，docId "links"，模型键 links）
   │ 依赖 LinkExtractor 接口（hypermedia/LinkExtractor.java:32）
   │        Map<String, List<Link>> extractLinks(OperationResponse response);
   ▼
ContentTypeLinkExtractor（包私有，:37）—— 分派器
   ├─ application/json 兼容 → AtomLinkExtractor（读 "links" 数组，rel+href 必填）
   └─ application/hal+json、application/vnd.hal+json、application/prs.hal-forms+json
        → HalLinkExtractor（读 "_links" map，键即 rel，值可为单对象或 Collection）
```

- 分派用 `contentType.isCompatibleWith(...)`（`hypermedia/ContentTypeLinkExtractor.java:67`）；无匹配 extractor 抛 `IllegalStateException`（`:54-62`），此时需要显式传入 `halLinks()`/`atomLinks()`（`HypermediaDocumentation.java:424-446`）或自定义 `LinkExtractor`。
- 解析基于 Jackson 3 的 `tools.jackson.databind.ObjectMapper`（`AbstractJsonLinkExtractor.java:23,34`）。
- `LinksSnippet` 的校验（`LinksSnippet.java:135-170`）与参数/字段同构：`ignoreUndocumentedLinks` 放过反向；正向缺失列出 rel 名抛 `SnippetException`。**description 可以省略**——如果链接本身带 `title`，就用 title 兜底（`getDescriptionFromLinkTitle:186-197`）。

## 3.10 constraints：把 Bean Validation 注解翻译成人话

**先说白话**：请求字段上有 `@NotNull`、`@Size(min=1, max=5)`？`ConstraintDescriptions` 能把它们变成 `["Must not be null", "Size must be between 1 and 5 inclusive"]`，你可以拼进字段描述或塞进自定义模板的 `constraints` 列。

**第一步：解析约束——不是反射注解，而是走 Bean Validation 元数据。**【源码证据】`core/constraints/ValidatorConstraintResolver.java:63-75`：

```java
public List<Constraint> resolveForProperty(String property, Class<?> clazz) {
	List<Constraint> constraints = new ArrayList<>();
	BeanDescriptor beanDescriptor = this.validator.getConstraintsForClass(clazz);
	PropertyDescriptor propertyDescriptor = beanDescriptor.getConstraintsForProperty(property);
	if (propertyDescriptor != null) {
		for (ConstraintDescriptor<?> constraintDescriptor : propertyDescriptor.getConstraintDescriptors()) {
			constraints.add(new Constraint(constraintDescriptor.getAnnotation().annotationType().getName(),
					constraintDescriptor.getAttributes()));
		}
	}
	return constraints;
}
```

`Constraint` 只有两个字段：**注解全限定名** + **注解属性 map**（`constraints/Constraint.java:26-30`）。默认 `Validator` 由 `Validation.buildDefaultValidatorFactory().getValidator()` 构建（`:50-52`），也可注入自定义实例。

**第二步：翻译成描述——资源包 + `${…}` 占位符。**【源码证据】`constraints/ResourceBundleConstraintDescriptionResolver.java:171-176`：

```java
public String resolveDescription(Constraint constraint) {
	String key = constraint.getName() + ".description";
	return this.propertyPlaceholderHelper.replacePlaceholders(getDescription(key),
			new ConstraintPlaceholderResolver(constraint));
}
```

- key 约定：`约束全限定名 + ".description"`；先查用户 bundle（base name `org.springframework.restdocs.constraints.ConstraintDescriptions`），`MissingResourceException` 时回退默认 bundle（`:178-188`）。
- 占位符是 **`${...}`**（`PropertyPlaceholderHelper("${", "}")`，`:117`），从 `Constraint.getConfiguration()` 取值——`@Min` 的默认描述 `Must be at least ${value}`、`@Size` 的 `Size must be between ${min} and ${max} inclusive`（`core/src/main/resources/org/springframework/restdocs/constraints/DefaultConstraintDescriptions.properties:10,22`）。数组属性用 `", "` 展开（`:204-206`）。
- 默认 bundle 覆盖 22 个 Jakarta 标准约束 + 10 个 Hibernate Validator 专有约束（`@CreditCardNumber`、`@EAN`、`@URL` 等，properties 文件 23-32 行）。

**接入点**：把描述作为 `constraints` attribute 挂到字段上，配合自定义模板出列（官方 how-to 示例 `spring-restdocs-docs/src/test/java/org/springframework/restdocs/docs/howto/customizing/includingextrainformation/mockmvc/IncludingExtraInformation.java:45`）：

```java
.attributes(key("constraints").value("Must not be null. Must not be empty")), // <2>
```

> **勘误式提醒**：约束解析**不使用 `{{...}}` 语法**（那是 Mustache 的），也不是反射读注解（是 Validator 元数据 API）。这两个常见误解在源码面前一翻即清。

## 3.11 本章小结

- **snippet 的一生**：`document()` 从 Operation.attributes 取三件套（context/WriterResolver/TemplateEngine）→ `createModel` 建模 → 模板渲染 → `StandardWriterResolver` 按占位符解析落盘。模板名 = 片段名是默认约定。
- **片段家族**：21 个内置模板 × asciidoctor/markdown 两格式，覆盖报文、体、字段、参数、头、Cookie、multipart、超媒体。所有工厂入口集中在 6 个 `*Documentation` 静态类。
- **描述符**是"人 → 片段"的信息载体；`attributes` 是开放扩展点，任何自定义键值直通 Mustache 模型。
- **双向校验**是 REST Docs 灵魂：fields/parameters/cookies/links 四处语义一致——`relaxed*` 只放过"存在未文档化"，"文档化了不存在"永远失败；类型推断再补一刀（JSON 自动推断，不符即红；XML 必须显式）。
- **约束与超媒体**是两个"增值片段"：前者借 Bean Validation 元数据 + 资源包把注解翻译成人话，后者按 Content-Type 分派 Atom/HAL 解析器。
- 对比视角：**这些校验能力，注解驱动的 Swagger 生态完全没有对应物**——`@Schema(description="...")` 写错了不会有任何工具报错。第六章会把它放进对比表。

---
# 四、测试框架适配器：三件套接入 MockMvc / WebTestClient / RestTestClient

## 4.1 架构同构性：入口工厂 → 挂接 SPI → Converter 对 → 触发器

**先说白话**：core 把"生成文档"收敛成一个泛型流水线后，每个测试框架适配器只需要回答三个框架特有问题：**配置在哪里挂接**（SPI 接口不同）、**配置 map 如何跨阶段传递**（机制不同）、**原始请求/响应如何变成 Operation**（Converter 不同）。三个模块因此长得像"同一张图纸的三次施工"：

| 维度 | mockmvc | webtestclient | resttestclient（4.1 新增） |
|---|---|---|---|
| 入口工厂 | `MockMvcRestDocumentation` | `WebTestClientRestDocumentation` | `RestTestClientRestDocumentation` |
| Configurer 实现 | `implements MockMvcConfigurer` | `implements ExchangeFilterFunction` | `implements ClientHttpRequestInterceptor` |
| 挂接 API | `MockMvcBuilders...apply(configurer)` | `configureClient().filter(configurer)` | `requestInterceptor(configurer)` |
| 配置传递 | request attribute `org.springframework.restdocs.configuration` | 静态 `ConcurrentHashMap`，键为 `WEBTESTCLIENT_REQUEST_ID` 头，一次性 remove | 静态 `ConcurrentHashMap`，键为 `RESTTESTCLIENT_REQUEST_ID` 头 |
| 触发器 | `RestDocumentationResultHandler`（`andDo(...)`） | `Consumer<T extends ExchangeResult>`（`consumeWith(...)`） | 同左 |
| Converter 泛型 | `MockHttpServletRequest` / `MockHttpServletResponse` 两个类 | `ExchangeResult` **一个类身兼请求与响应**（`handle(result, result, ...)`） | `ExchangeResult`（servlet 版）同上 |
| URL 模板来源 | `RestDocumentationRequestBuilders` 塞 attribute；反射探测 Framework 7.1 `getUriTemplate()` 兜底 | `result.getUriTemplate()` 原生自带 | `result.getUriTemplate()` 原生自带 |
| URI 默认值 | 独立 `UriConfigurer`（可配置） | `applyUriDefaults()` 硬编码 `http://localhost:8080` | 同左（`HttpRequestWrapper` 匿名类） |

测试框架怎么选？官方维护者的答复（issue #1020）：三种载体**没有官方推荐**，按个人偏好与既有测试选择；按下载量看 `spring-restdocs-mockmvc` 是主流。

## 4.2 mockmvc（重点）：两根管道的接力

**先说白话**：MockMvc 集成是"两根管道"：**配置管道**（`documentationConfiguration()` 返回的 Configurer 挂进 MockMvcBuilder，在每次请求前把 configuration map 塞进 request attribute）与**生成管道**（`document()` 返回的 ResultHandler 在 `andDo()` 阶段取出该 map、转换、渲染）。两根管道在 request attribute `org.springframework.restdocs.configuration` 上会师。

### 4.2.1 入口工厂：document() 的本质

【源码证据】`spring-restdocs-mockmvc/src/main/java/org/springframework/restdocs/mockmvc/MockMvcRestDocumentation.java:65-68`：

```java
public static RestDocumentationResultHandler document(String identifier, Snippet... snippets) {
	return new RestDocumentationResultHandler(
			new RestDocumentationGenerator<>(identifier, REQUEST_CONVERTER, RESPONSE_CONVERTER, snippets));
}
```

一行代码说尽：**`document()` = 给 `RestDocumentationGenerator` 套一层 `ResultHandler` 壳**。`REQUEST_CONVERTER`/`RESPONSE_CONVERTER` 是模块级单例（`:36-38`）。

### 4.2.2 RestDocumentationResultHandler：从 andDo 到 handle

【源码证据】`mockmvc/RestDocumentationResultHandler.java:50-53` 与 `:91-98`：

```java
@Override
public void handle(MvcResult result) {
	this.delegate.handle(result.getRequest(), result.getResponse(), retrieveConfiguration(result));
}
...
private Map<String, Object> retrieveConfiguration(MvcResult result) {
	@SuppressWarnings("unchecked")
	Map<String, Object> configuration = (Map<String, Object>) result.getRequest()
		.getAttribute(ATTRIBUTE_NAME_CONFIGURATION);
	Assert.state(configuration != null, () -> "REST Docs configuration not found. Did you forget to apply a "
			+ MockMvcRestDocumentationConfigurer.class.getSimpleName() + " when building the MockMvc instance?");
	return configuration;
}
```

**"REST Docs configuration not found. Did you forget to apply a MockMvcRestDocumentationConfigurer when building the MockMvc instance?"**——这句话是初学者最高频报错，源头就在这：`andDo(document(...))` 时取不到 configuration map，说明忘了 `.apply(documentationConfiguration(...))`。

### 4.2.3 MockMvcRestDocumentationConfigurer：双重身份

它同时是 core 的配置骨架和 MockMvc 的 SPI（【源码证据】`mockmvc/MockMvcRestDocumentationConfigurer.java:44-46`）：

```java
public final class MockMvcRestDocumentationConfigurer extends
		RestDocumentationConfigurer<MockMvcSnippetConfigurer, MockMvcOperationPreprocessorsConfigurer,
				MockMvcRestDocumentationConfigurer>
		implements MockMvcConfigurer {
```

每次请求执行时的 `postProcessRequest`（`:119-133`）是配置管道的核心——**创建 per-operation 上下文 → 组装 configuration map（请求本体、URL 模板、context）→ 挂到 request attribute → 应用全部 configurer**：

```java
@Override
public MockHttpServletRequest postProcessRequest(MockHttpServletRequest request) {
	RestDocumentationContext context = this.contextManager.beforeOperation();
	Map<String, Object> configuration = new HashMap<>();
	configuration.put(MockHttpServletRequest.class.getName(), request);
	String urlTemplate = urlTemplateExtractor.apply(request);
	if (urlTemplate != null) {
		configuration.put(RestDocumentationGenerator.ATTRIBUTE_NAME_URL_TEMPLATE, urlTemplate);
	}
	configuration.put(RestDocumentationContext.class.getName(), context);
	request.setAttribute(RestDocumentationResultHandler.ATTRIBUTE_NAME_CONFIGURATION, configuration);
	MockMvcRestDocumentationConfigurer.this.apply(configuration, context);
	MockMvcRestDocumentationConfigurer.this.uriConfigurer.apply(configuration, context);
	return request;
}
```

注意 `configuration.put(MockHttpServletRequest.class.getName(), request)`：**请求本体也住在 map 里**，键是类全名。`UriConfigurer` 就靠这个键把请求捞出来改写 scheme/host/port（`mockmvc/UriConfigurer.java:101-111`）——文档 URI（`https://api.example.com`）与测试 URI（`localhost`）在此解耦，默认 `http://localhost:8080`（`:42-56`）。

**URL 模板的两个来源**（`:93-111`）：优先取 request attribute（即 `RestDocumentationRequestBuilders` 塞的），缺失时反射探测 Framework 7.1 给 `MockHttpServletRequest` 新增的 `getUriTemplate()` 方法——这是 4.1.x 对 Framework 7.1 的兼容层，静态初始化块里完成探测。

### 4.2.4 RestDocumentationRequestBuilders：为什么必须用它

【源码证据】`mockmvc/RestDocumentationRequestBuilders.java:29-39` javadoc 直说：

> "A drop-in replacement for MockMvcRequestBuilders that captures a request's URL template... **Required when documenting path parameters** and recommended for general usage"

实现就是一行加料（`:53-56`）：

```java
public static MockHttpServletRequestBuilder get(String urlTemplate, Object... urlVariables) {
	return MockMvcRequestBuilders.get(urlTemplate, urlVariables)
		.requestAttr(RestDocumentationGenerator.ATTRIBUTE_NAME_URL_TEMPLATE, urlTemplate);
}
```

MockMvc 真实发出的是展开后的 URL（`/orders/1`），模板 `/{orderId}` 若不显式携带，`path-parameters` 片段（3.5 节正则提取）就无从知道参数名。get/post/put/patch/delete/options/head/request/multipart 十个方法同构包装（`:53-239`）。

### 4.2.5 Converter：模拟对象 → 中立模型的三个"较真"

`MockMvcRequestConverter`（包私有，`:62`）在转换时做了几件容易被忽略的事：

- **form 参数合成 body**：非 GET 且 Content-Type 缺失或为 form-urlencoded 时，把参数表重新 URL-encode 成 `application/x-www-form-urlencoded` body（`:116-147`）——MockMvc 的 `param()` 不会真的生成 body，文档里却需要它。
- **Cookie 头去重**：servlet `Cookie[]` 转成 `RequestCookie` 后从 headers 里移除 `Cookie` 头（`:149-159`），避免 3.7 节报文还原时重复。
- **multipart 双路兼容**：同时覆盖 `MockHttpServletRequest.getParts()`（Servlet 3.1+）与 `MockMultipartHttpServletRequest.getMultiFileMap()`（`:161-210`），经 `OperationRequestPartFactory` 产出 part。

`MockMvcResponseConverter`（`:42`）则处理一个 servlet mock 的糙面：若 headers 里没有 `SET-COOKIE`，从 `response.getCookies()` 手工拼 Set-Cookie 头（`:63-106`，覆盖 Max-Age/Domain/Path/Secure/HttpOnly 六个属性）。

### 4.2.6 空壳子 Configurer：组合模式的接线员

`MockMvcSnippetConfigurer` / `MockMvcOperationPreprocessorsConfigurer` 自身**没有任何逻辑**——它们的 `afterConfigurerAdded/beforeMockMvcCreated` 全部 `return and().xxx(...)` 委托父链（`mockmvc/MockMvcSnippetConfigurer.java:31-49`）。它们存在的唯一意义：让用户能写 `.snippets().withEncoding(...)` 并仍满足 MockMvc 的 SPI 签名。这是"流式 API 需要为每层节点造壳"的典型代价。

## 4.3 webtestclient：响应式世界的三板斧

**先说白话**：WebTestClient 没有 servlet request attribute 可用，配置传递换了机制：**filter 阶段把 map 存进静态 ConcurrentHashMap（键是 WebTestClient 自动附加的请求 ID 头），consumeWith 阶段取出并删除**——一次性消费，天然线程安全。

三个关键点：

1. **Configurer 本身就是 ExchangeFilterFunction**（`webtestclient/WebTestClientRestDocumentationConfigurer.java:45-47`），挂接方式变成 `configureClient().filter(documentationConfiguration(restDocumentation))`（集成测试 `WebTestClientRestDocumentationIntegrationTests.java:98-102` 亲证）。`filter()`（`:88-94`）：

```java
@Override
public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
	String index = request.headers().getFirst(WebTestClient.WEBTESTCLIENT_REQUEST_ID);
	Assert.state(index != null, "Missing %s header".formatted(WebTestClient.WEBTESTCLIENT_REQUEST_ID));
	configurations.put(index, createConfiguration());
	return next.exchange(applyUriDefaults(request));
}
```

   取出端 `retrieveConfiguration`（`:80-86`）用 `configurations.remove(requestId)`——**remove 语义**保证同一请求 ID 不会被重复消费，泄漏也不会累积。

2. **document() 返回 Consumer**（`WebTestClientRestDocumentation.java:75-78`）：`ExchangeResult` 一个对象同时充当请求与响应——`handle(result, result, ...)`。URL 模板直接来自 `result.getUriTemplate()`（WebTestClient 原生跟踪），**不需要** mockmvc 那样的 `RestDocumentationRequestBuilders` 替代品。

3. **multipart 是响应式解析**（`WebTestClientRequestConverter.java:72-83`）：把 `getRequestBodyContent()` 包成 `Flux<DataBuffer>`，用 `MultipartHttpMessageReader(DefaultPartHttpMessageReader)` `.readMono(...).block()` 阻塞取结果。响应式栈里的"不得已阻塞"。

## 4.4 resttestclient：一次"servlet 栈移植"的范本

**先说白话**：Framework 7 给同步栈带来了新测试客户端 RestTestClient（绑定 MVC 应用，API 风格模仿 WebTestClient）。REST Docs 4.1 的支持模块（唯一 commit `b27c018`，2026-10-02 合入，全类 `@since 4.1.0`）几乎逐行对照 webtestclient 模块——文件清单一一对应、错误提示同款——实质差异只有三处：

| 差异点 | webtestclient | resttestclient |
|---|---|---|
| 拦截点 | `ExchangeFilterFunction.filter()`（Netty/响应式客户端） | `ClientHttpRequestInterceptor.intercept()`（servlet 栈的客户端拦截器） |
| multipart 解析 | 响应式 `MultipartHttpMessageReader` + `block()` | **阻塞式直读**：Framework 7 新 API `org.springframework.http.converter.multipart.MultipartHttpMessageConverter` + `MockHttpInputMessage` 直接 `read()`（`RestTestClientRequestConverter.java:76-89`） |
| Cookie 解析 | 不分割多 cookie 头 | 按 `"; "` 分割多 cookie 头（`:106-116`），更完整 |

【源码证据】`resttestclient/RestTestClientRestDocumentationConfigurer.java:88-95`：

```java
@Override
public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
		throws IOException {
	String index = request.getHeaders().getFirst(RestTestClient.RESTTESTCLIENT_REQUEST_ID);
	Assert.state(index != null, "Missing %s header".formatted(RestTestClient.RESTTESTCLIENT_REQUEST_ID));
	configurations.put(index, createConfiguration());
	return execution.execute(applyUriDefaults(request), body);
}
```

> **再次提示**：`spring-restdocs-bom` 尚未纳入该模块（1.3 节），引依赖时需自写版本号。

## 4.5 端到端最小示例（官方测试摘录）

【源码证据】`spring-restdocs-mockmvc/src/test/java/org/springframework/restdocs/mockmvc/MockMvcRestDocumentationIntegrationTests.java:109-124, 308-318`（节选）：

```java
@SpringJUnitConfig
@WebAppConfiguration
@ExtendWith(RestDocumentationExtension.class)                       // ① 上下文自动管理
@ContextConfiguration(classes = TestConfiguration.class)
public class MockMvcRestDocumentationIntegrationTests {

	private RestDocumentationContextProvider restDocumentation;

	@BeforeEach
	void setUp(RestDocumentationContextProvider restDocumentation) {  // ② ParameterResolver 注入
		this.restDocumentation = restDocumentation;
	}

	@Test
	void pathParametersSnippet() throws Exception {
		MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(this.context)
			.apply(documentationConfiguration(this.restDocumentation)) // ③ 配置管道
			.build();
		mockMvc.perform(get("/{foo}", "").accept(MediaType.APPLICATION_JSON))  // ④ RestDocumentationRequestBuilders
			.andExpect(status().isOk())
			.andDo(document("links", pathParameters(                     // ⑤ 生成管道
					parameterWithName("foo").description("The description"))));
		assertExpectedSnippetFilesExist(new File("build/generated-snippets/links"),
				"http-request.adoc", "http-response.adoc", "curl-request.adoc", "path-parameters.adoc");
	}
}
```

五个编号就是 MockMvc 集成的全部要素：`@ExtendWith`（上下文）、参数注入（provider）、`apply(documentationConfiguration(...))`（配置管道）、`RestDocumentationRequestBuilders`（URL 模板）、`andDo(document(...))`（生成管道）。测试最后还断言了默认片段 + path-parameters 四个文件的落盘。

## 4.6 本章小结

- **三件套同构**：每个适配器 = 入口工厂 + 挂接 SPI（Configurer）+ Converter 对 + 触发器（ResultHandler / Consumer）。core 对三者零感知——`RestDocumentationGenerator<REQ, RESP>` 的泛型就是接缝。
- **配置传递机制因栈而异**：servlet attribute（mockmvc）vs 静态 map + 请求 ID 头一次性消费（webtestclient/resttestclient）。响应式世界没有 request attribute，于是"用 header 当 key 的注册表"是最简解。
- **mockmvc 的特有门槛**：`RestDocumentationRequestBuilders`（URL 模板）与 `.apply(documentationConfiguration(...))`（配置管道）。两者的缺失都会以清晰的 IllegalStateException 报出——源码里的错误信息就是最佳排错手册。
- **4.1 的 resttestclient** 是观察该架构扩展性的活标本：新框架接入 = 新模块 + 逐行移植 webtestclient，core 一行未动。
- 对比视角：springdoc 也需要适配层（webmvc/webflux starter），但它适配的是"运行中的应用"，REST Docs 适配的是"测试中的客户端"——**谁在测试环境、谁在生产环境**，这是两者部署面差异的根源（第六章）。

---
# 五、渲染与呈现：templates 包、Mustache 与 spring-restdocs-asciidoctor

## 5.1 模板三接口与 TemplateFormat

**先说白话**：REST Docs 的"呈现层"只有三个抽象——**去哪找模板**（TemplateResourceResolver）、**怎么编译执行**（TemplateEngine）、**渲染一次**（Template）。外加一个格式描述符决定扩展名与默认模板集。

【源码证据】`core/templates/`（行号实测）：

```java
public interface TemplateEngine {                                 // TemplateEngine.java:39
	Template compileTemplate(String path) throws IOException;
}
public interface TemplateResourceResolver {                       // TemplateResourceResolver.java:34
	Resource resolveTemplateResource(String name);
}
public interface Template {                                       // Template.java:35
	String render(Map<String, Object> context);
}
```

`TemplateFormat`（`templates/TemplateFormat.java:32-39`）只有 `getId()` 与 `getFileExtension()` 两个方法，内置两个实例（`TemplateFormats.java`）：`asciidoctor()`（id `asciidoctor`，扩展名 `.adoc`，`:76-82`）与 `markdown()`（id `markdown`，扩展名 `.md`，`:84-90`）。**想支持新格式（如 HTML 片段、reStructuredText）？自己 new 一个 TemplateFormat + 提供一套自定义模板即可**，core 不设卡。

## 5.2 StandardTemplateResourceResolver：三级查找

**先说白话**：模板覆盖（自定义输出样式的最常用手段）全靠这个解析器的三级回退。

【源码证据】`core/templates/StandardTemplateResourceResolver.java:54-82`：

| 优先级 | 查找路径 | 用途 |
|---|---|---|
| 1 | `org/springframework/restdocs/templates/${formatId}/${name}.snippet` | 用户对**特定格式**的自定义模板 |
| 2 | `org/springframework/restdocs/templates/${name}.snippet` | 用户**跨格式**的自定义模板 |
| 3 | `org/springframework/restdocs/templates/${formatId}/default-${name}.snippet` | 内置默认模板 |

三级全 miss 才抛 `IllegalStateException("Template named '...' could not be resolved")`。落地操作：在 `src/test/resources/org/springframework/restdocs/templates/asciidoctor/` 放一个 `request-fields.snippet`，整个应用的所有字段表瞬间换样式（官方 how-to：`how-to/pages/customizing-the-output.adoc:9-17`）。

## 5.3 MustacheTemplateEngine：JMustache 的四个细节

**先说白话**：模板引擎实现只有 JMustache 一个（依赖 `com.samskivert:jmustache:1.16`，`spring-restdocs-platform/build.gradle:12`）。它的使用方式有几个值得注意的细节。

1. **escapeHTML(false)**：默认构造关闭 HTML 转义（`config/RestDocumentationConfigurer.java:122-125`，`Mustache.compiler().escapeHTML(false)`）——片段是给 Asciidoctor/Markdown 消费的纯文本，不是 HTML。
2. **无缓存**：`compileTemplate` 每次调用都重新 `compiler.compile(...)`（`templates/mustache/MustacheTemplateEngine.java:136-142`）——模板资源未做编译缓存，简单但正确。
3. **上下文合并**：`MustacheTemplate.render`（`MustacheTemplate.java:60-65`）把**构造期 context 与 render 期 context 合并（render 期优先）**——构造期 context 就是 2.6.3 节注入的 `tableCellContent` lambda。
4. **tableCellContent lambda**（`mustache/AsciidoctorTableCellContentLambda.java:34-44`）：逐字符扫描输出，为未转义的 `|` 前补 `\`——防止字段描述里的竖线把 Asciidoctor 表格列切断。**只在 asciidoctor 格式注入**（2.6.3 节），markdown 版模板不需要。

## 5.4 自定义输出的两条路

| 手段 | 改什么 | 典型场景 |
|---|---|---|
| **覆盖模板** | 片段长什么样（Mustache） | 给字段表加一列（constraints/示例值）；改标题；改代码块语言 |
| **descriptor/snippet attributes** | 片段有哪些数据 | 往模型里塞自定义键（`key("constraints").value(...)`、工厂方法的 `Map attributes` 重载） |

两者必须配合使用：attributes 塞进去的键，只有自定义模板里写了 `{{constraints}}` 才会显示。官方完整示例链（`how-to/customizing-the-output.adoc:21-80`）：descriptor 加 attribute → 自定义 `request-fields.snippet` 加列（模板里用 `{{title}}`、`{{#fields}}...{{constraints}}`）→ 片段自动带上新列。

另一个自由度是**换默认片段集**：`.snippets().withDefaults(...)` 整体替换、`withAdditionalDefaults(...)` 追加（`config/SnippetConfigurer.java:99,111`）。

## 5.5 spring-restdocs-asciidoctor：把片段拼成书

**先说白话**：片段落在 `build/generated-snippets/<operation>/*.adoc` 后，你在 Asciidoctor 手稿里用 `operation::get-order[snippets='curl-request,http-response']` 一行把整个操作的相关片段嵌进来。这个宏是 AsciidoctorJ 的 Ruby block macro，由 Java SPI 自动注册。

三个组件（`spring-restdocs-asciidoctor/src/main/java/org/springframework/restdocs/asciidoctor/`）：

1. **RestDocsExtensionRegistry**（`:27`）：实现 `org.asciidoctor.jruby.extension.spi.ExtensionRegistry`，`META-INF/services` 里登记，AsciidoctorJ 启动即加载；注册两件事——Java 预处理器 `DefaultAttributesPreprocessor` + Ruby 宏 `operation`（源码 `:31-36`）。
2. **DefaultAttributesPreprocessor**（`:35-39`）：文档解析最前面注入 `{snippets}` 属性——你在 adoc 里写的 `:snippets: ./build/generated-snippets` 会被它**有则不覆盖、无则给默认**的目录解析结果取代：

```java
@Override
public Reader process(Document document, PreprocessorReader reader) {
	document.setAttribute("snippets", this.snippetsDirectoryResolver.getSnippetsDirectory(document.getAttributes()),
			false);
	return reader;
}
```

3. **SnippetsDirectoryResolver**（`:36-69`）：对构建工具的约定式探测——有 `maven.home` 系统属性时从文档 `docdir` 逐级向上找 `pom.xml`，取 `<pom 目录>/target/generated-snippets`；否则取 `gradle-projectdir` 属性，取 `<projectdir>/build/generated-snippets`。**与 2.2.4 节测试侧的默认目录探测是同一套约定的两端**。

宏本体是 Ruby（`src/main/resources/extensions/operation_block_macro.rb`）：`named :operation`（`:14`）注册宏名；`process`（`:17-26`）读 `snippets` 属性展开路径；`snippets_to_include`（`:72-90`）——**未指定 snippets 参数时自动包含该 operation 目录下按字母序所有 `.adoc`**；片段缺失不中断构建，输出占位文本并 `logger.warn`（`:98-109`）；每个片段渲染成二级标题，标题可用文档属性 `operation-<snippet-name>-title` 覆盖（`:111-117, 130-155`）。

> 注意依赖事实：**asciidoctor 模块对 core 零依赖**——它是纯粹的"呈现侧"扩展，与文档生成（测试侧）完全解耦。这就是 1.2 节"生成与呈现分离"的最硬证据。

## 5.6 输出目录参数化：占位符与 step

回到测试侧。`document()` 的第一个参数不只是名字，更是**路径模板**：

```java
document("{method-name}/{step}/create")   // → build/generated-snippets/create-order/1/create/curl-request.adoc
```

7 种占位符（`snippet/RestDocumentationContextPlaceholderResolver.java:26-47`）：`step`、`{methodName|method-name|method_name}`、`{ClassName|class-name|class_name}`。`step` 的值来自 `StandardRestDocumentationContext.getAndIncrementStepCount()`（2.2.2 节的 AtomicInteger），由 `ManualRestDocumentation.beforeOperation()` 在**每次 HTTP 操作**时自增（`core/ManualRestDocumentation.java:87-91`）——官方文档 `how-to/parameterizing-the-output-directory.adoc:9-31` 列出的正是这张表。

## 5.7 本章小结

- **呈现层的三段自由度**：换格式（TemplateFormat）、换模板（三级查找覆盖）、换数据（attributes）——互不纠缠，可叠加。
- **JMustache 的极简主义**：无转义、无缓存、lambda 只服务 Asciidoctor 表格。没有宏、没有继承布局——因为 REST Docs 的"版面"本来就该由 Asciidoctor 手稿（你的书）负责，片段只是嵌入的素材块。
- **asciidoctor 模块是呈现侧的全部**：一个 SPI 注册 + 一个属性注入 + 一个 Ruby 宏。它对 core 零依赖——生成与呈现的分界线画得干干净净。
- Markdown 用户路径：`.snippets().withTemplateFormat(TemplateFormats.markdown())`（`config/SnippetConfigurer.java:122`）+ `include` 拼接（官方 `working-with-markdown.adoc`）——没有 operation 宏，一切自己动手，这也是 Markdown 支持一直"二等公民"的原因。

---
# 六、正面对比：Spring REST Docs 与 Swagger（OpenAPI 生态）

> 本章不依赖本地源码行号，引用的是两个项目的官方文档/公告与 2026-10 时点的生态事实（版本号、日期均经交叉核实，来源 URL 见 8.5 节）。先厘清用词：**Swagger** 既是历史名词（Swagger 2.0 规范，2014）也是商标（SmartBear）；如今"用 Swagger"通常指 **OpenAPI 规范（OAS）+ 工具生态**——在 Spring 世界里即 **springdoc-openapi**（springfox 已死，见 6.4）+ **Swagger UI**。

## 6.1 两种哲学：文档从哪里来？

**先说白话**：两个工具都想解决"API 文档很难维护"这件事，但它们对"真相从哪来"的回答截然相反。

- **Swagger/springdoc 的回答：真相在代码里。** 你在 Controller/DTO 上写注解（或让框架从代码结构推断），运行时扫描生成 OpenAPI JSON，Swagger UI 消费它渲染成可交互的文档页。文档是**代码的投影**——代码改了，下次启动文档自动跟着变。
- **REST Docs 的回答：真相在测试里。** 你在测试里声明文档描述（descriptor），框架把**真实发生的** HTTP 请求/响应与声明对账，通过后落盘为片段。文档是**测试的副产品**——代码改了但测试没更新？测试红，文档不会带病发布。

官方立项之始就把这个对立写进了使命宣言。spring.io 项目页：

> "This approach **frees you from the limitations of the documentation produced by tools like Swagger**. It helps you to produce documentation that is **accurate**, concise, and well-structured."

REST Docs 1.0.0 GA 公告（Andy Wilkinson，2015-10-07）原话几乎相同："This approach frees you from the limitations imposed by tools like Swagger."

十年后回看，这句话说对了一半：它说对的是**准确性机制**（6.5.1 节展开）；它没料到的是 OpenAPI 作为**机器可读规范**的生态爆炸（代码生成、契约测试、mock server、SDK 生成、LLM 工具集成……）——而这些恰恰是 REST Docs 主动放弃的赛道（6.5.2、6.7 节）。

## 6.2 数据流对比：两个世界的架构图

```
【Swagger / springdoc-openapi：注解驱动 + 运行时扫描】
  @Operation/@Schema 注解 ──┐
  Controller/DTO 代码结构 ──┼──► springdoc 扫描器（应用运行时）
  spring.web 属性/配置    ──┘            │
                                          ▼
                              GET /v3/api-docs（OpenAPI 3 JSON/YAML）
                                          │
                              ┌───────────┴────────────┐
                              ▼                        ▼
                        Swagger UI（交互式）      生态工具（codegen/
                        Try-it-out 在线调试      契约测试、mock、SDK）

【Spring REST Docs：测试驱动 + 构建期生成】
  @Test + document("id", requestFields(...))    你手写的 AsciiDoc 手稿
          │（测试 JVM 内）                              │
          ▼                                            │
  RestDocumentationGenerator.handle()                  │
  （convert → preprocess → 双向校验 → 渲染）             │
          │                                            │
          ▼                                            ▼
  build/generated-snippets/<id>/*.adoc ──operation::宏──► Asciidoctor ──► HTML/PDF 文档
        （21 种片段素材）        （测试构建期）              （CI 构建期）
```

三个结构性差异一目了然：

1. **运行环境**：springdoc 跑在**生产应用**里（`/v3/api-docs` 端点必须活着），REST Docs 跑在**测试 JVM + 构建期**，生产零依赖（回看 1.4 节依赖表）。
2. **产物形态**：springdoc 产出**一份机器可读规范**（单一 JSON/YAML），REST Docs 产出**一堆人类可读片段**（按操作分目录的 adoc/md），机器可读性靠社区桥补（6.7）。
3. **叙事归属**：springdoc 的文档页是**工具渲染的**（样式与结构由 Swagger UI 决定），REST Docs 的文档是**你写的书**（片段只是嵌入素材）。

## 6.3 全维度对比表

| 维度 | Spring REST Docs | Swagger / springdoc-openapi |
|---|---|---|
| 真相来源 | 通过的测试（执行） | 代码注解 + 结构推断（声明） |
| 准确性保证 | **强**：双向校验 + 类型推断，文档错=测试红（3.4 节） | **无机制**：注解写错不报错；漂移靠 code review 或测试（springdoc 的 `verify` plugin 只保证"文档能生成"，不保证"内容正确"） |
| 代码侵入 | 生产代码零注解；侵入测试代码 | Controller/DTO 大量注解（不写注解则文档很素） |
| 生产部署影响 | **零**（测试期/构建期工具） | 运行时多一组端点与依赖（可关闭，但 UI 的价值就在运行时） |
| 交互式 UI | **无**（静态 HTML/PDF，可复制 curl 但不能点按钮发请求） | **Swagger UI 开箱即用**（`/swagger-ui.html`，Try-it-out）；另有 Scalar UI、MCP 面板（v3 新增） |
| 机器可读规范 | **无官方导出**（snippet 是呈现无关片段，非 schema 抽象） | **OpenAPI 3.x 原生**（`/v3/api-docs`，JSON/YAML） |
| 生态工具 | 片段消费 = Asciidoctor/Markdown；OpenAPI 桥靠社区（epages restdocs-api-spec，低维护） | 庞大：client/server codegen、contract testing（Schemathesis 等）、mock、SDK、Postman/Insomnia 导入、API 网关、LLM function-calling |
| 上手成本 | 中高：要会写测试 + Asciidoctor + 描述符 | 低：starter 一引，注解一标，UI 就有 |
| 维护成本 | 每端点写测试（但测试本来就该写）；重构即被测试卡住 | 注解随代码腐化（改字段忘改 `@Schema` 不报错） |
| 文档叙事能力 | **强**：Asciidoctor 是完整的出版排版系统（目录/交叉引用/表格/数学式/PDF） | 弱：Swagger UI 页面结构固定，长文说明只能塞 description |
| 错误码/示例/分页等"实现细节"文档 | 测试真实覆盖什么就有什么（甚至鼓励对错误响应写测试） | 全靠手写注解，或由 springdoc 从代码猜 |
| JUnit/构建集成 | 原生（JUnit 扩展、Gradle/Maven 约定目录） | 无关（运行时工具）；构建期可用 maven/gradle 插件在 integration-test 阶段拉取 |
| 输出格式 | Asciidoctor（默认）/ Markdown（内置）；自定义格式可扩展 | OpenAPI 3.0/3.1（springdoc 2.9+ 支持 3.1）；UI 独立升级 |
| 规范版本覆盖 | 不适用（不产规范）；社区桥只到 OAS 3.0.1 | OAS 3.0 / 3.1；3.2（2025-09 发布）需关注工具跟进 |
| Spring Boot 4 / Framework 7 支持 | 4.0+（本文快照 4.1 含 RestTestClient） | springdoc v3 线（3.0.0 随 Boot 4 GA，2025-11-21） |
| 团队协作模式 | 文档工程师 + 开发者分工（手稿 + 片段） | 单一开发者闭环（注解即文档） |

## 6.4 Swagger 生态现状盘点（2026-10）

**springfox 已死，不要再用**。最后版本 3.0.0 停在 2020-07-14；与 Boot 2.6+ 的 PathPattern 策略不兼容、完全不兼容 Boot 3/Jakarta；仓库实质停滞。所有"Swagger 集成 Spring"的新教程若还在教 `@EnableSwagger2`/`Docket`，都该判为过时内容。

**springdoc-openapi 是事实标准**，三条版本线并行：

| 版本线 | 适配 | artifact | 当前版本（2026-09） |
|---|---|---|---|
| v1（终结） | Boot 1.x/2.x | `springdoc-openapi-ui` | 1.8.0（2024-03-12，终版） |
| v2（维护） | Boot 3.x / Framework 6 | `springdoc-openapi-starter-webmvc-ui` | **2.9.1**（2026-09-06） |
| v3（活跃） | **Boot 4 / Framework 7** | `springdoc-openapi-starter-webmvc-ui` | **3.1.1**（2026-09-06；3.0.0 于 2025-11-21 随 Boot 4 GA） |

模块矩阵（v2/v3 共通）：`starter-common`（引擎）、`starter-webmvc-ui`/`webflux-ui`（含 Swagger UI）、`starter-webmvc-api`/`webflux-api`（仅 `/v3/api-docs` 无 UI）、`starter-*-scalar`（Scalar UI）、`starter-*-mcp`（MCP 面板，v3 新增——API 文档正在被 AI 工具消费的信号）。构建期离线生成可用 `springdoc-openapi-maven-plugin`（1.5，配合 integration-test 阶段）或 Gradle 插件（1.9.0）。

底层注解来自 **swagger-core 2.2.55**（2026-08-31，`io.swagger.core.v3:swagger-annotations-jakarta` 的 `@Operation/@Schema`）。规范侧：OAS 3.0.x（2017）→ 3.1.x（2021，对齐 JSON Schema 2020-12）→ **3.2.0（2025-09-23 发布）**。springdoc 对 3.1 的支持自 2.x 后期起可用；3.2 支持待工具跟进。

## 6.5 逐维度展开：三个最要紧的对比

### 6.5.1 准确性：机制性保证 vs 纪律性希望

这是 REST Docs 立项宣言的核心，也是源码层面差距最大的地方。回看 3.4 节的三道闸门：

1. **双向校验**（`AbstractFieldsSnippet.validateFieldDocumentation`）：声明的字段必须存在、实际字段必须被声明，否则测试红；
2. **类型推断**（`JsonContentHandler.resolveFieldType`）：文档写的类型与实际 payload 不符即红；
3. **描述符完备性**：非 optional 字段不写 description，构造期就断言失败。

对照 springdoc：`@Schema(description = "...")`、`@Parameter(...)` 写错字段名、类型、必填性——**没有任何工具会报错**。springdoc 生态里"文档与实现漂移"的防线只剩两类：code review，以及把契约测试（如 Schemathesis）架在 `/v3/api-docs` 上。前者是纪律，后者已属另一个工具域。

一句话：**REST Docs 把文档正确性做成了测试；springdoc 把文档便利性做成了注解**。两者都没有办法兼得——除非双轨（6.8）。

### 6.5.2 机器可读性：REST Docs 主动放弃的赛道

REST Docs 的产物是"一次 HTTP 交互的素材块"：`request-fields.adoc` 是一张给人看的表格，不是 `{"type":"object","properties":{...}}`。官方 reference 文档**通篇没有出现 openapi/swagger 字样**（对 4.0.1 全部页面 grep 验证），官方也从未提供规范导出——这不是疏忽，是定位：**REST Docs 卖的是"准确的可读文档"，不卖"可计算的 API 契约"**。

代价在生态：OpenAPI 规范能直接喂给 codegen（生成客户端 SDK）、mock server、契约测试、API 网关、以及今天的 LLM function-calling。REST Docs 的片段什么都喂不了——除了人。**如果你的下游需要"机器可消费的 API 契约"，REST Docs 单轨是不够的**。

### 6.5.3 叙事与排版：Asciidoctor 对 Swagger UI 的碾压区

Swagger UI 的页面结构是固定的（endpoints → methods → schema），长文档只能挤进 description 字段。REST Docs 的成品是**你亲手写的 Asciidoctor 手稿**：章节结构、概念导览、错误码对照表、认证流程图、交叉引用、术语表——片段只是其中的"实证插图"。官方总览页对此毫不掩饰（`index.adoc`）：

> "Document RESTful services by **combining hand-written documentation with auto-generated snippets**..."

对**面向第三方开发者的正式 API 手册**（出版级 PDF、官网文档站），这是决定性优势；对**面向内部团队的快速调试入口**，Swagger UI 的"打开浏览器点一下就发请求"更香。工具的性格差异，本质是**受众差异**。

## 6.6 官方与社区立场

- **Spring 官方**从未在 reference 文档里把两者对立（6.5.2 的 grep 事实），对立叙事只出现在项目页与 1.0 公告的**营销句**里。维护者 wilkinsona 在 issue #1020（关于恢复 REST Assured）中的表态也透着同样的克制：一切以"准确、可维护"为准，不追热点。
- **社区共识**（Baeldung《Spring REST Docs vs OpenAPI》2025-07 更新版为代表）：没有普适赢家——**重准确性选 REST Docs，重速度与交互选 OpenAPI**；文章同样指出 springdoc 生态（codegen、contract-first）与 Swagger UI 是 REST Docs 不具备的。
- **一个耐人寻味的侧写**：REST Docs 4.0 移除 REST Assured 支持、把维护资源让给 RestTestClient（issue #995 vs #1020 的不同结局）——官方对 REST Docs 的投入策略是**跟随 Spring 测试栈**，而非扩张生态。对照 springdoc 的多 UI、多模块、MCP 面板快速扩张，两个项目的性格高下立判：一个是**质量的看门人**，一个是**生态的建设者**。

## 6.7 互通桥梁：把 REST Docs 的片段变成 OpenAPI

社区项目 **epages restdocs-api-spec**（原 restdocs-openapi，已重定向合并）在测试里额外产出 OpenAPI 文档：用法上把 `document(...)` 换成 `documentWithResourceDetails(...)` 风格的扩展 snippet（`ResourceSnippet` + `ResourceSnippetParameters`），构建后合并出 **OpenAPI 2.0 / 3.0.1**（json/yaml）与 **Postman Collection 2.1**。它的动机自述很诚实：喜欢 REST Docs 的 test-driven 方式，但 AsciiDoc/Markdown 只是静态文档，需要可交互的规范产物。

现状提醒（2026-10）：最新版 0.20.x，README 带维护徽章，**不支持 OAS 3.1/3.2**，字段类型系统仍需手工声明——它把"注解负担"从生产代码挪到了测试代码，但保留了"测试通过才生成"的准确性闸门。对"想要 REST Docs 的准确性 + 也要一份 OpenAPI 文件"的团队，这是目前唯一成型的桥。

反过来（springdoc → 手册级文档）没有对称物：规范 JSON 到出版级文档仍需独立工具链（Widdershins、redocly 等），且丢失 REST Docs 的测试背书。

## 6.8 选型决策指南

```
你的读者是谁？
├─ 外部开发者 / 需要出版级手册、错误码全表、认证叙事
│   └─► REST Docs（手稿 + 片段）——准确性是命脉，排版是竞争力
├─ 内部/跨团队调试，"打开就能试"最重要
│   └─► springdoc-openapi + Swagger UI —— 注解负担可接受，UI 即生产力
├─ 下游有机器消费者（SDK 生成、契约测试、网关、LLM 工具）
│   └─► OpenAPI 规范必须有 → springdoc（或 contract-first 先写规范）
├─ 两者都要（常见于对外产品的 API 团队）
│   └─► 双轨，两种姿势：
│        A. REST Docs 主轨 + epages restdocs-api-spec 桥出 OpenAPI
│           （准确性全保留；接受社区桥的低维护与 OAS 3.0 封顶）
│        B. springdoc 主轨 + 把契约测试架在 /v3/api-docs 上
│           （生态全保留；接受注解漂移风险由契约测试兜底）
└─ 新项目、团队小、想最快见效
    └─► springdoc 起步；当文档"说谎"开始造成实际事故时，再补 REST Docs
        ——反向迁移（REST Docs → springdoc）则近乎重写测试
```

**双轨不冲突的架构原因**：REST Docs 只活在测试 JVM（1.4 节依赖表），springdoc 只活在生产运行时——两者连依赖都不相遇，可以安全共存于同一工程。

## 6.9 对比小结

| 问题 | 答案 |
|---|---|
| REST Docs 是 Swagger 的替代品吗？ | 不是。一个是"测试驱动的文档出版工具"，一个是"运行时的 API 规范 + 交互 UI 生态"。交集只有"都能产出 API 文档" |
| 谁的文档更可信？ | REST Docs，机制性保证（双向校验/类型推断，3.4 节）；springdoc 靠纪律 |
| 谁的生态更大？ | OpenAPI（codegen/mock/契约测试/网关/LLM），数量级差距 |
| 谁的排版上限更高？ | REST Docs（Asciidoctor 出版系统 vs 固定布局的 Swagger UI） |
| 生产代码侵入谁更小？ | REST Docs（零注解、零运行时依赖） |
| 上手谁更快？ | springdoc（starter 一引即得 UI） |
| 能否一起用？ | 能，且互不干扰（6.8 双轨）；桥接方案是 epages restdocs-api-spec |
| 2026 年的新项目默认选谁？ | 看读者：对外正式文档 → REST Docs；内部调试与机器消费 → springdoc；都要 → 双轨 |

---
# 七、贯通视图：三条时间线看懂 REST Docs 全貌

## 7.1 时间线一：一个测试方法从运行到片段落盘

以第四章 4.5 节的 `pathParametersSnippet` 测试为例，把全链路串起来：

```
1. JUnit 启动测试类
   @ExtendWith(RestDocumentationExtension.class)
   → beforeEach: ManualRestDocumentation.beforeTest(类, 方法)
   → 创建 StandardRestDocumentationContext（step=0，输出目录 build/generated-snippets）

2. @BeforeEach setUp
   → resolveParameter 注入 provider（惰性 lambda）
   → MockMvcBuilders...apply(documentationConfiguration(provider))
   → ConfigurerApplyingRequestPostProcessor 就位（配置管道挂载）

3. mockMvc.perform(get("/{foo}", ""))        ← RestDocumentationRequestBuilders
   → 请求带上 attribute: org.springframework.restdocs.urlTemplate = "/{foo}"
   → postProcessRequest:
       beforeOperation() → step: 0→1
       组装 configuration map（request 本体 + urlTemplate + context）
       挂到 request attribute "org.springframework.restdocs.configuration"
       apply() 依次跑 4 个 configurer（snippets 默认片段/编码/格式、
               operationPreprocessors、TemplateEngine、WriterResolver）

4. 测试业务执行，响应返回 200

5. andDo(document("links", pathParameters(...)))
   → RestDocumentationResultHandler.handle(mvcResult)
   → retrieveConfiguration: 从 request attribute 取回 map
   → RestDocumentationGenerator.handle():
       ① attributes = configuration 拷贝
       ②③ MockMvcRequestConverter.convert → preprocess → OperationRequest/Response
       ④ new StandardOperation("links", req, resp, attributes)
       ⑤ getSnippets: 6 个默认片段 + pathParameters → 逐个 document()
           TemplatedSnippet.document():
             取 context/WriterResolver/TemplateEngine（attributes 总线）
             createModel → resolve Writer → compile + render → 落盘
   → 产物：build/generated-snippets/links/
       curl-request.adoc  httpie-request.adoc  http-request.adoc
       http-response.adoc request-body.adoc    response-body.adoc
       path-parameters.adoc                 ← 本次显式传入的片段
```

## 7.2 时间线二：构建期，片段如何变成书

```
1. 片段就位：build/generated-snippets/links/*.adoc

2. 你在 src/docs/asciidoc/index.adoc 手稿里写：
       == 查询订单
       operation::links[snippets='curl-request,http-response,path-parameters']
       （不指定 snippets 参数则自动嵌入该目录全部 .adoc，按字母序）

3. 构建（Maven asciidoctor-maven-plugin / Gradle asciidoctor 插件）：
   AsciidoctorJ 启动
   → SPI 加载 RestDocsExtensionRegistry
   → DefaultAttributesPreprocessor 注入 {snippets} 属性
     （Maven：向上找 pom.xml → target/generated-snippets；Gradle：build/generated-snippets）
   → 解析到 operation:: 宏 → OperationBlockMacro.process
   → 逐片段读文件、渲染二级标题（可用 operation-<name>-title 覆盖）
   → 输出 target/generated-docs/index.html（或 PDF）
```

注意时间线一与时间线二**可以发生在不同机器、不同时刻**（测试在 CI 跑，Asciidoctor 在发布流水线跑）——它们唯一的接口是 `generated-snippets` 目录这个"文件系统契约"。这也解释了为什么 REST Docs 对 AsciidoctorJ 的依赖在 4.0 升到 3.0（Release Notes）而 core 完全无感。

## 7.3 时间线三：一次"改代码忘改文档"的旅程（REST Docs 的价值时刻）

```
开发者把响应字段 orderNo 改名为 orderNumber，忘了更新文档。

Swagger/springdoc 世界（若无契约测试）：
  注解 @Schema(description="orderNo 订单号") 没人动 → UI 照常展示旧名
  → 文档静默说谎，直到某个下游开发者按文档集成失败、提 issue。

REST Docs 世界：
  响应字段表测试运行 → responseFields(fieldWithPath("orderNo")...)
  → JsonContentHandler.hasField 找不到 orderNo → findMissingFields 非空
  → SnippetException: "Fields with the following paths were not found in the response: [orderNo]"
  → 测试红，CI 拦截。开发者被迫在同一个 commit 里改文档描述。
  → 若新字段 orderNumber 未被文档化 → 反向校验同样报错（除非 relaxed）。
```

这一条时间线就是 1.2 节哲学第 1 条的具象化，也是第六章对比表"准确性"一格的全部依据。

## 7.4 从源码中提炼的五个设计模式视角

| 视角 | 落点 | 一句话 |
|---|---|---|
| 适配器 + 策略 | `RequestConverter/ResponseConverter` SPI | core 定义"翻译官"接口，三个框架各派一位 |
| 模板方法 | `TemplatedSnippet.document()`（`snippet/TemplatedSnippet.java:74-84`） | 父类定死"取料→建模→渲染"骨架，子类只填 `createModel` |
| 责任链/组合 | `Preprocessors.preprocessRequest(a,b,...)`、Configurer 的 apply 链 | 美颜滤镜可叠加，配置回调顺序固定 |
| 工厂族 | `*Documentation` 六个静态入口类 | `fieldWithPath(...)`/`requestFields(...)` 全是静态工厂，用户不见实现类 |
| 注册表 + 数据总线 | `configuration` map 与 `Operation.attributes`（键=类全名） | 没有 DI 容器，字符串键就是它的"bean 名字"——朴素，但让 core 对一切框架零依赖 |

---

# 八、附录

## 8.1 关键接口速查表

| 接口/抽象类 | 位置 | 职责 | 你会实现它吗 |
|---|---|---|---|
| `RestDocumentationContext` | `core/RestDocumentationContext.java:27` | 测试会话上下文（类/方法/step/输出目录） | 否 |
| `RestDocumentationContextProvider` | 同包 | `beforeOperation()` 工厂（JUnit 注入的就是它） | 否 |
| `Operation` | `operation/Operation.java:26` | 一次 HTTP 交互的中立快照 | 否 |
| `RequestConverter<T>` / `ResponseConverter<T>` | `operation/RequestConverter.java:37` 等 | 框架对象 → Operation 模型 | 接入新测试框架时 |
| `Snippet` | `snippet/Snippet.java` | 片段最小契约：`document(Operation)` | 自定义片段时（建议继承 `TemplatedSnippet`） |
| `TemplatedSnippet` | `snippet/TemplatedSnippet.java:37` | 模板化片段基类（建模 + 渲染骨架） | 是（自定义片段的常规路径） |
| `WriterResolver` | `snippet/WriterResolver.java` | 决定片段写到哪 | 极少（输出到非文件系统时） |
| `TemplateEngine` / `TemplateResourceResolver` | `templates/` | 模板编译 / 模板资源定位 | 换模板技术（如 Freemarker）时 |
| `OperationPreprocessor` | `operation/preprocess/OperationPreprocessor.java:45` | 请求/响应改写（组合进 Preprocessors） | 常用（通过 `Preprocessors` 工厂） |
| `PayloadSubsectionExtractor` | `payload/PayloadSubsectionExtractor.java` | 子段提取策略（beneathPath 是内置实现） | 提取规则特殊时 |
| `LinkExtractor` | `hypermedia/LinkExtractor.java:32` | 从响应发现超媒体链接 | 非 JSON/HAL 格式时 |
| `ConstraintResolver` / `ConstraintDescriptionResolver` | `constraints/` | 约束解析 / 约束描述翻译 | 非 Bean Validation 约束时 |
| `AbstractConfigurer` | `config/AbstractConfigurer.java:37` | 配置回调（apply → 写 map） | 适配器作者 |

## 8.2 片段（snippet）速查总表

完整版见 3.3 节。极简版（docId → 工厂）：

- **默认 6 个**：`curl-request`/`httpie-request`（`CliDocumentation`）、`http-request`/`http-response`（`HttpDocumentation`）、`request-body`/`response-body`（`PayloadDocumentation`）
- **字段**：`request-fields`/`response-fields`/`request-part-fields`（`PayloadDocumentation.requestFields/responseFields/requestPartFields`）
- **参数**：`path-parameters`/`query-parameters`/`form-parameters`/`request-parts`（`RequestDocumentation`）
- **头与 Cookie**：`request-headers`/`response-headers`（`HeaderDocumentation`）、`request-cookies`/`response-cookies`（`CookieDocumentation`）
- **超媒体**：`links`（`HypermediaDocumentation.links/relaxedLinks/halLinks/atomLinks`）

## 8.3 占位符与常用配置键速查

**输出路径占位符**（`RestDocumentationContextPlaceholderResolver.java:26-47`）：`{step}`、`{methodName}`/`{method-name}`/`{method_name}`、`{ClassName}`/`{class-name}`/`{class_name}`（没有 `{method}`/`{segment}`）。

**Operation attributes 总线上的键**（以类全名为键为主，另有 4 个字符串常量见 2.4.1 节）：`RestDocumentationContext`、`TemplateEngine`、`WriterResolver`、`MockHttpServletRequest`（mockmvc 特有，UriConfigurer 消费）。

**模板查找路径**（5.2 节三级表）：`org/springframework/restdocs/templates/<formatId>/<name>.snippet` → `org/springframework/restdocs/templates/<name>.snippet` → `org/springframework/restdocs/templates/<formatId>/default-<name>.snippet`。

## 8.4 源码阅读入口清单（20 个关键文件）

按建议阅读顺序（均相对仓库根）：

1. `spring-restdocs-core/src/main/java/org/springframework/restdocs/generate/RestDocumentationGenerator.java` —— 引擎主干（handle 六步）
2. `.../restdocs/RestDocumentationContext.java` + `StandardRestDocumentationContext.java` + `ManualRestDocumentation.java` + `RestDocumentationExtension.java` —— 上下文四件套
3. `.../operation/Operation.java` + `StandardOperation.java` + `OperationRequestFactory.java` —— 中立模型
4. `.../operation/preprocess/Preprocessors.java` + `UriModifyingOperationPreprocessor.java` —— 预处理器
5. `.../snippet/TemplatedSnippet.java` + `StandardWriterResolver.java` + `RestDocumentationContextPlaceholderResolver.java` —— 渲染三件套
6. `.../config/RestDocumentationConfigurer.java` + `SnippetConfigurer.java` —— 配置骨架
7. `.../payload/AbstractFieldsSnippet.java` —— fields 流水线（双向校验+类型推断）
8. `.../payload/JsonFieldPath.java` + `JsonFieldProcessor.java` + `JsonContentHandler.java` —— JSON 路径与处理
9. `.../request/PathParametersSnippet.java` + `AbstractParametersSnippet.java` —— 参数提取
10. `.../hypermedia/ContentTypeLinkExtractor.java` + `HalLinkExtractor.java` —— 链接分派
11. `.../constraints/ValidatorConstraintResolver.java` + `ResourceBundleConstraintDescriptionResolver.java` —— 约束翻译
12. `.../cli/CurlRequestSnippet.java` —— curl 还原
13. `spring-restdocs-mockmvc/.../MockMvcRestDocumentation.java` + `RestDocumentationResultHandler.java` + `RestDocumentationRequestBuilders.java` + `MockMvcRestDocumentationConfigurer.java` —— mockmvc 四件套
14. `spring-restdocs-webtestclient/.../WebTestClientRestDocumentationConfigurer.java` —— filter + 静态 map
15. `spring-restdocs-resttestclient/.../RestTestClientRestDocumentationConfigurer.java` —— 4.1 新模块
16. `.../templates/StandardTemplateResourceResolver.java` + `mustache/MustacheTemplateEngine.java` —— 模板
17. `spring-restdocs-asciidoctor/src/main/java/.../RestDocsExtensionRegistry.java` + `src/main/resources/extensions/operation_block_macro.rb` —— 呈现侧
18. `spring-restdocs-core/src/main/resources/org/springframework/restdocs/templates/asciidoctor/default-request-fields.snippet` —— 模板样例
19. `spring-restdocs-core/src/test/java/.../payload/RequestFieldsSnippetTests.java` —— 行为规格（测试即文档）
20. `spring-restdocs-docs/src/docs/antora/modules/ROOT/pages/index.adoc` —— 官方定位原文

## 8.5 参考链接

**官方**
- 项目主页与定位语：https://spring.io/projects/spring-restdocs
- Reference 文档（Antora）：https://docs.spring.io/spring-restdocs/reference/index.html
- 1.0.0 GA 公告（2015-10-07）：https://spring.io/blog/2015/10/07/spring-rest-docs-1-0-0-release
- 4.0.0 公告（2025-11-19）：https://spring.io/blog/2025/11/19/spring-restdocs-4
- 各版 Release Notes：https://github.com/spring-projects/spring-restdocs/wiki
- RestTestClient 支持议题：https://github.com/spring-projects/spring-restdocs/issues/995
- REST Assured 恢复无计划（not_planned）：https://github.com/spring-projects/spring-restdocs/issues/1020

**Swagger/OpenAPI 生态**
- springdoc-openapi：https://springdoc.org/ ；Releases：https://github.com/springdoc/springdoc-openapi/releases
- swagger-core：https://github.com/swagger-api/swagger-core/releases
- springfox（终版 3.0.0，2020-07）：https://github.com/springfox/springfox
- OpenAPI 规范（含 3.2.0，2025-09-23）：https://spec.openapis.org ；发布博客：https://www.openapis.org/blog/2025/09/23/announcing-openapi-v3-2
- epages restdocs-api-spec（REST Docs → OpenAPI 桥）：https://github.com/ePages-de/restdocs-api-spec

**社区对比**
- Baeldung《Spring REST Docs vs OpenAPI》（2025-07 更新）：https://www.baeldung.com/spring-rest-docs-vs-openapi
- Baeldung《Introduction to Spring REST Docs》：https://www.baeldung.com/spring-rest-docs
- Reflectoring《"Code First" API Documentation with Springdoc》：https://reflectoring.io/spring-boot-springdoc

---

# 结语

读完源码，REST Docs 的形象可以收拢成一句话：**它是一个把"文档正确性"问题转化为"测试通过"问题的文档工具**。为此它做了一套极克制的架构——

- **一个主干**：`RestDocumentationGenerator.handle()` 的六步流水线，十一年未变；
- **两根管道**：配置管道（Configurer → configuration map）与生成管道（触发器 → handle），在适配器上会师；
- **三个接缝**：Converter（换框架）、Snippet/TemplatedSnippet（换片段）、TemplateEngine/TemplateFormat（换呈现）——每个接缝都是接口，每个接口都有唯一的默认实现；
- **四道闸门**：双向校验、类型推断、描述符完备性、模板解析——文档说谎的每一条路都被测试堵死。

而 Swagger/springdoc 是另一种答案：**把"API 可理解性"问题转化为"机器可读规范 + 交互 UI"问题**。它牺牲了正确性的机制保证，换来了生态的万川归海。

两者没有胜负，只有分工：**REST Docs 管你文档的"真"，OpenAPI 管你 API 的"通"**。当一个团队需要两者都为真时，双轨共存（6.8 节）不是妥协，而是把每个工具用在了它最擅长的那一环。
