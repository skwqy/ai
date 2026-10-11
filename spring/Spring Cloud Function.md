# Spring Cloud Function 深度源码解析（函数即 Bean，一份代码三种宿主）

> **本文基于的源码**：`D:\code\3rd\spring-cloud-function`，版本 **5.0.4**（tag `v5.0.4`，2026-08-20 发布，Git commit `f95cb3544`）。全仓 315 个 Java 文件（含 samples）。运行基线：**Java 17 + Spring Boot 4.0.8**（样例工程实证）+ Jackson 3（`tools.jackson` 新包名）。文中所有【源码证据】的文件路径与行号均为对该 tag 实际读取所得。
>
> **版本取舍说明**：Spring Cloud Function 在 3.0（2019-11）完成了一次"换心"——目录体系从 `InMemoryFunctionCatalog` 旧世界重写为 `SimpleFunctionRegistry`/`BeanFactoryAwareFunctionRegistry` 新世界；4.0（2022-12）删除旧世界并升到 Boot 3 / Java 17；5.0（2025-11）升到 Boot 4 / Jackson 3，并删掉了 deployer、rsocket 两个历史模块。因此本文以 **5.0.x 的源码为主线**，1.x/2.x 的旧架构只在 1.6 版本演进与第三章开头作对照出现。
>
> **阅读约定**：每章"先白话、后源码"——先用一两句人话讲清"这是什么、为什么需要它"，再给出源码证据链（`模块/src/main/java/.../类名.java` + 方法名 + 行号 + 代码片段）。行号只对 v5.0.4 精确，读者用 IDEA 打开源码按类名 + 方法名定位即可。

## 如何读这份文档

如果你是第一次接触 Spring Cloud Function，推荐两遍读法：

- **第一遍（建立地图，1~2 小时）**：只读第一章（总览）每节的开头白话段、第二章（上手与装配链）、以及各章"本章小结"。目标是能回答：一个普通的 `Function<String,String>` bean 是怎么变成 HTTP 端点和 AWS Lambda 的？`FunctionCatalog` 在中间扮演什么角色？
- **第二遍（深入源码）**：对照【源码证据】逐行读。顺序建议：第二章（装配）→ 第三章（目录与 FunctionInvocationWrapper，全框架的心脏）→ 第五章（HTTP 端点）→ 第四章（路由）→ 第六章（类型转换）→ 第七章（云适配器）→ 第八章（周边生态）。

如果你带着具体问题来：查 1.5 节"关键问题 → 方案映射"表直接跳章。

---

# 一、总览：定位、设计哲学与模块全景

## 1.1 一句话定位

**Spring Cloud Function 是一个把"普通的 Java 函数"提升为一等部署单元的框架**：你只写 `Supplier`/`Function`/`Consumer`（甚至只是一个 POJO 方法），框架负责把它们发现、注册进一个 `FunctionCatalog`，再按需暴露成 HTTP 端点、消息流处理器（配合 Spring Cloud Stream）或 AWS/Azure/GCP Serverless 函数——**同一份业务代码，不改动一行，就能在三种宿主之间迁移**。

它要解决的核心矛盾是：业务逻辑本来就是一个"输入 → 输出"的纯函数，但部署到不同平台时，你得分别为 Spring MVC 写 Controller、为 AWS 写 `RequestHandler`、为 Azure 写 `@FunctionName`，把同一份逻辑重复包裹 N 遍。Spring Cloud Function 的回答是：**写一次函数，框架替你写 N 遍壳**。

## 1.2 设计哲学：读源码前先记住四句话

1. **业务逻辑就是 `java.util.function`，不发明新接口**。框架没有定义任何"Function 父接口"，直接复用 JDK 的三大函数式接口（外加 `BiFunction`/`BiConsumer` 和 Kotlin lambda）；甚至一个既不是 Function 也不是 Consumer 的 POJO bean，只要有一个"看起来像函数的方法"，也能被代理成函数（见 3.8 节 POJO 函数）。这让业务代码对框架的依赖为零。
2. **宿主只是壳，Catalog 是心脏**。HTTP 里的 `FunctionController`、AWS 里的 `FunctionInvoker`、Custom Runtime 的 `CustomRuntimeEventLoop`，全都是同一个动作的变体：`functionCatalog.lookup(...)` 拿到函数 → 把平台事件转成 `Message` → `function.apply(message)` → 把输出转回平台格式。读源码时抓住 `FunctionCatalog` 和 `FunctionInvocationWrapper`（见第三章），一切适配器都是注脚。
3. **响应式是统一执行模型，但不是强制**。任何函数在内部都会被"Flux 化"适配（`fluxifyInputIfNecessary`，见 3.5 节）：阻塞函数、返回单个值的函数、返回 `Flux` 的函数、接受 `Message` 的函数，最终都收敛到 `Publisher` 语义上执行。写阻塞函数完全没问题——框架替你包。
4. **一切皆组合**。`spring.cloud.function.definition` 里的 `"a|b|c"` 是组合，`RoutingFunction` 按消息头分发是运行时组合，`input-header-mapping-expression` 头部增强也是往函数链里插一个包装函数（见 3.10 节）。整个框架只有一个组合原语：`FunctionInvocationWrapper#andThen`。

## 1.3 模块分层全景

多模块 Maven 工程。按"用户感知"分层如下（`java` 文件数为 `find -name "*.java"` 实测，不含 tests/samples）：

```
┌─────────────────────────── 壳层（面向具体宿主） ────────────────────────────┐
│  spring-cloud-function-web        HTTP 端点（MVC + WebFlux 双栈，25 文件）    │
│  spring-cloud-starter-function-web / -webflux   两个 Starter（各 1 个 pom）  │
│  spring-cloud-function-adapters/                                              │
│    ├ adapter-aws（7 文件）        AWS Lambda：FunctionInvoker + Custom Runtime│
│    ├ adapter-azure（2 文件）      Azure 注入器辅助                             │
│    ├ adapter-azure-web（1 文件）  Azure Web 代理调用器                         │
│    ├ adapter-gcp（4 文件）        Google Cloud Functions                      │
│    ├ grpc（11 文件 + cloudevent-ext 5）   gRPC 适配器                          │
│    └ serverless-web（12 文件）    任意 Boot MVC 应用上 Lambda（伪 Servlet 容器）│
├─────────────────────────── 心脏层（宿主无关） ──────────────────────────────┤
│  spring-cloud-function-context    目录/注册表/路由/转换器/JSON/可观测/         │
│                                   CloudEvents/actuator（51 文件，真正的主体）  │
│  spring-cloud-function-kotlin     Kotlin 支持的测试与遗留占位（main 无源码）    │
│  spring-cloud-function-integration  Spring Integration DSL（4 文件）          │
├──────────────────────────────── 地基 ────────────────────────────────────────┤
│  spring-cloud-function-core       1 个文件：FunctionInvocationHelper 契约      │
│  spring-cloud-function-dependencies  BOM（依赖版本管理，纯 pom）               │
│  spring-cloud-function-samples    28 个样例工程                                │
└──────────────────────────────────────────────────────────────────────────────┘
```

三个值得停下来看的数字：

- **core 模块只有 1 个类**（`FunctionInvocationHelper`）：core 的存在只是为了切断"context 依赖 cloud-events 实现"的循环依赖——core 定义钩子接口，context 里的 `CloudEventsFunctionInvocationHelper` 实现它，`SimpleFunctionRegistry` 面向接口调用。
- **context 是绝对主体**：目录、注册表、路由、转换器、JSON、可观测性全部在这一层。
- **kotlin 模块 main 目录已无源码**：5.0 中 Kotlin 运行时支持（`KotlinFunctionWrapper` 等）整体并入了 context 模块的 `KotlinLambdaToFunctionAutoConfiguration`，kotlin 模块只剩测试和一个空的 `spring.factories`（见 8.4 节）。

## 1.4 模块依赖图（以各模块 pom 实证）

```
                    spring-cloud-function-core（1 文件地基）
                              ▲
                    spring-cloud-function-context
                     ▲        ▲          ▲         ▲
      spring-cloud-  │        │          │         └ spring-cloud-function-kotlin
      function-web   │        │     spring-cloud-function-integration
         ▲           │        │
         │           │        └── spring-cloud-function-grpc
  spring-cloud- ─────┘
  function-adapter-aws ──► spring-cloud-function-serverless-web（依赖 spring-webmvc + servlet-api，
  spring-cloud-starter-function-web/webflux      不依赖 adapter-aws）
```

真实依赖声明（摘自各模块 `pom.xml`，已逐个验证）：

| 模块 | 关键依赖 |
|---|---|
| function-core | reactor-core、spring-core |
| function-context | spring-boot-autoconfigure、spring-boot-restclient、spring-boot-web-server、function-core |
| function-web | spring-webmvc、spring-webflux、jakarta.servlet-api、reactor-netty、function-context、spring-boot-jackson/gson |
| starter-function-web | function-web + spring-boot-starter-web |
| starter-function-webflux | function-web + spring-boot-starter-webflux |
| serverless-web | spring-webmvc + jakarta.servlet-api（**排除** spring-boot-starter-tomcat / spring-boot-web-server） |
| adapter-aws | function-context + aws-lambda-java-core + cloudevents-api |

这张表本身就是一份架构说明：**context 居中**（web、grpc、全部云适配器都压在它上面），**core 极薄**（只隔离一个钩子接口），**serverless-web 刻意不依赖真实 Web 服务器**（它要把 Tomcat/Jetty 从 classpath 上顶掉，见 7.5 节）。

## 1.5 关键问题 → 方案映射（全文导览）

| 关键问题 | 框架的方案 | 详见 |
|---|---|---|
| 同一份函数要跑在 HTTP / 消息 / Serverless 三种宿主 | `FunctionCatalog` 统一目录 + 各宿主一个薄适配壳 | 第二章、第五章、第七章 |
| 函数分散在各处，如何被发现、被枚举 | `BeanFactoryAwareFunctionRegistry` 懒发现 + `lookup(name)` + `getNames()` | 第三章 |
| 输入是 JSON 字符串、函数要 POJO；输出要求 `text/plain` | `SmartCompositeMessageConverter` + `JsonMapper` 多层转换兜底 | 第六章 |
| 一个进程里有 N 个函数，HTTP/消息只有一个入口，怎么分发 | `RoutingFunction`：header/属性/SpEL 表达式五级决策链 | 第四章 |
| 多个函数串成管道 | `definition` 里写 `"a\|b\|c"`，`andThen` 组合出复合函数 | 3.7 节 |
| 给某个函数的入参/出参动态加 header | `spring.cloud.function.configuration.<fn>.input/output-header-mapping-expression` | 3.10 节 |
| Lambda 冷启动慢 | 延迟初始化开关、Custom Runtime 事件循环、GraalVM Native + AOT 反射提示 | 7.2–7.4、8.5 节 |
| 已有 Boot MVC 应用想直接搬上 Lambda | serverless-web：伪 Servlet 容器 + `ServerlessMVC`，Tomcat 被顶掉 | 7.5 节 |
| 函数多了记不住 | `/actuator/functions` 端点枚举全部函数及类型 | 8.1 节 |
| 调用链路要追踪 | Micrometer Observation 环绕在 `apply` 处 | 8.2 节 |

## 1.6 版本演进：1.x → 5.x 关键变化对比

### 1.6.1 版本时间线（git tag 实证，可复现）

对仓库执行 `git tag --sort=creatordate` 可复现下表（仅列各 train 首个 GA；时间取 tag 创建日期）：

| 版本 | 发布日期 | Boot 基线 | Java | 大事记 |
|---|---|---|---|---|
| 首次提交 | 2016-09-21 | — | — | `initial commit`（比 1.0 GA 早 21 个月） |
| v1.0.0.RELEASE | 2018-06-15 | Boot 1.5.13（pom 实证） | 1.8 | 首个 GA；`InMemoryFunctionCatalog` 旧世界；aws/azure 适配器已存在（2017 年引入） |
| v2.0.0.RELEASE | 2019-01-08 | Boot 2.1 | 1.8 | `FunctionEndpointInitializer` 函数式端点（2018-10 引入，@since 2.0）；kotlin 模块 2.1 期引入（2018-11-30） |
| v2.1.0.RELEASE | 2019-05-07 | Boot 2.1 | 1.8 | **`RoutingFunction` 诞生**（2019-05-03，GH-238，@since 2.1） |
| v3.0.0.RELEASE | 2019-11-22 | Boot 2.2 | 1.8 | **换心**：`BeanFactoryAwareFunctionRegistry` 取代旧注册体系（2019-07-17 "Renamed new registry implementation to BeanFactoryAwareFunctionRegistry"）；`FunctionProperties`（@since 3.0，2019-09-04 GH-408） |
| v3.1.0 | 2020-12-21 | Boot 2.4 | 1.8 | `SimpleFunctionRegistry` 从 BeanFactoryAware 抽取（2020-04-16 "Extract common code from BeanFactoryAwareFunctionRegistry"）；`FunctionInvocationWrapper` 定型；adapter-gcp 引入（2020-02-26 首次提交） |
| v3.2.0 | 2021-12-01 | Boot 2.6 | 1.8 | gRPC 适配器进入开发（2021-10-18）；`FunctionsEndpoint`（@since 3.2） |
| v4.0.0 | 2022-12-15 | **Boot 3.0** | **17** | 旧世界（`InMemoryFunctionCatalog`/`FunctionInspector`/`AbstractComposableFunctionRegistry`/`MessageFunction` 族）整体删除；openwhisk 适配器删除（2022-08-16 "Cleanup"）；serverless-web 模块进入开发（2023-03-06） |
| v4.1.0 | 2023-12-06 | Boot 3.2 | 17 | `spring-cloud-function-integration` 模块引入（2023-05-08，Spring Integration DSL） |
| v4.2.0 | 2024-12-02 | Boot 3.4 | 17 | 持续修补；POJO 函数输出语义修正（GH-1307 系列） |
| v5.0.0 | 2025-11-24 | **Boot 4.0 / Jackson 3** | 17 | JSON 层从 Jackson 2（`com.fasterxml`）整体切到 **Jackson 3（`tools.jackson`）**；deployer 与 rsocket 适配器删除（2026-06-19 "Remove deployer and rsocket"，落在 5.0.4） |
| v5.0.4（本文基准） | 2026-08-20 | Boot 4.0.8（样例实证） | 17 | 当前稳定线；5.1.0-M1 已在 main 上预演 |

> Boot 基线推导依据：各 tag 的 parent 为 `spring-cloud-build`（1.3.9 → 2.1.x → 2.2.x → 3.0.x → 3.1.x → 4.0.x → 4.1.x → 4.2.x → 5.0.x，pom 实证），对应 Spring Cloud 发布列车 1.x → 2020.0 → 2021.0 → 2022.0 → 2023.0 → 2024.0 → 2025.1；v1.0.0 与 v5.0.x 两端已用 pom/样例直接实证（1.5.13.RELEASE 与 4.0.8），中间版本按列车映射。

### 1.6.2 三段架构史：从"函数注册表"到"函数操作系统"

**第一段（1.x–2.x，2016–2019）：注册表时代。** 目录是 `InMemoryFunctionCatalog` + `AbstractComposableFunctionRegistry`（v2.1.0 catalog 目录实证），配上 `FunctionInspector`（查类型）、`MessageFunction/MessageSupplier/MessageConsumer`（消息风格包装）。这一代的问题是：类型发现靠启发式扫描、注册发生在启动期（先注册后使用）、组合规则分散在 `AbstractComposableFunctionRegistry` 的继承体系里。

**第二段（3.x，2019–2022）：懒发现时代。** 3.0 用 `BeanFactoryAwareFunctionRegistry` 一夜换心：不再启动期扫描，而是**第一次 `lookup` 时才去 BeanFactory 里找**，找到后登记进 `FunctionRegistration`；类型发现改由 `FunctionTypeUtils`/`FunctionContextUtils` 从 bean definition 与方法签名严谨推导。3.1 把与 BeanFactory 无关的部分抽成 `SimpleFunctionRegistry`（Azure 适配器等非 Spring 容器场景也能用），`FunctionInvocationWrapper` 成为一个类同时实现 `Function/Consumer/Supplier/Runnable` 四个接口（见 3.4 节）。

**第三段（4.x–5.x，2022–今）：做减法 + 云原生深化。** 4.0 随 Boot 3 升级顺手删光旧世界类；4.1 拆出 Spring Integration DSL 模块；5.0 删掉 deployer（JAR 热部署运行器——K8s 时代已无必要）与 rsocket 适配器，JSON 栈整体迁到 Jackson 3，并把"让存量 Boot 应用上 Serverless"的 serverless-web 模块打磨为对 Boot 4 新模块化自动配置（`org.springframework.boot.tomcat.autoconfigure...`）的显式抢占（见 7.5 节）。

一条清晰的主线：**框架核心越来越小（core=1 文件）、外围适配越来越厚（7 个适配器模块）；内部历史包袱持续出清（openwhisk→deployer→rsocket），而对"怎么被发现"（懒发现）与"怎么被执行"（Publisher 统一）这两件事从 3.x 起再未动摇。**

### 1.6.3 对初学者的意义：哪些知识过时了，哪些永远有效

- **过时**：任何提到 `ContextFunctionCatalog`（接口）、`InMemoryFunctionCatalog`、`FunctionInspector`、`CompiledFunctionFactory`、`spring-cloud-function-deployer`、openwhisk/rsocket 适配器的教程与博客——分别死于 3.0、4.0、5.0。
- **永远有效**：`spring.cloud.function.definition`、组合 `|` 语法、`RoutingFunction` 的 header/表达式路由、`FunctionCatalog` 编程模型、HTTP 端点的 GET/POST 语义——这些自 2.1/3.0 起稳定至今，且从 3.x 到 5.x 的骨架几乎一致。

## 1.7 全文章节地图

```
第二章  30 秒上手与整体装配链   —— 三种写法；一条请求的一生；两个自动装配入口
第三章  函数目录（心脏）        —— Catalog 接口链；SimpleFunctionRegistry；
                                   FunctionInvocationWrapper 执行引擎；组合；懒发现与类型发现
第四章  路由                    —— RoutingFunction 五级决策链与 SpEL 安全上下文
第五章  HTTP 端点               —— 双栈 Controller；路径解析算法；HTTP 语义表；
                                   函数式端点；Supplier 推拉两种玩法
第六章  类型转换与 JSON         —— 双转换体系；输入/输出转换决策树；Jackson 3
第七章  云函数适配器            —— AWS FunctionInvoker / Custom Runtime；
                                   serverless-web 伪 Servlet 容器；Azure/GCP/gRPC/CloudEvents
第八章  周边生态                —— actuator；Observability；Integration DSL；Kotlin；AOT；与 Stream 的关系
第九章  设计评述与实战指南      —— 得与失；适用场景；属性速查表；常见坑
附录    版本考古 tag 列表；源码文件地图
```

---

# 二、30 秒上手与整体装配链

## 2.1 三种函数写法：从 POJO 到纯 Lambda

框架对"什么算一个函数"的定义宽到令人发指，一共三种写法：

**写法一：普通 `@Bean` 方法（最常用）**

```java
@SpringBootApplication
public class DemoApplication {
    @Bean
    public Function<String, String> uppercase() {
        return value -> value.toUpperCase();
    }
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
```

启动后立刻得到：`POST /uppercase`（body 是输入）、`GET /uppercase/zzz`（路径尾是输入）两个 HTTP 端点（Web 栈在 classpath 时）。业务代码 import 里没有任何 spring-cloud 相关的东西。

**写法二：POJO bean（函数化的宽容上限）**

```java
@Component
public class Uppercaser {
    public String toUpperCase(String s) { return s.toUpperCase(); }
}
```

`BeanFactoryAwareFunctionRegistry` 发现 `uppercase` bean 不是 `Function/Consumer/Supplier` 时，会走 POJO 函数通道：反射找出"函数式方法"，用 AOP 代理把它包成 `Function`（见 3.8 节）。bean 名（`uppercaser`）就是函数名，方法名不需要与 bean 名一致。

**写法三：FunctionalSpringApplication（纯函数式风格，极致启动速度）**

```java
FunctionalSpringApplication.run(DemoApplication.class, args);  // main 方法
// 或直接：
// context = FunctionalSpringApplication.run(ctx -> ctx.registerBean("uppercase", Function.class, ...));
```

【源码证据】`spring-cloud-function-context/.../context/FunctionalSpringApplication.java:47-86`。它配合 `spring.functional.enabled=true` 走 `ContextFunctionCatalogInitializer` 的"极简容器"路线：跳过 `ConfigurationClassPostProcessor`（配置类解析这个启动大头被换成一个 `DummyProcessor`，见 2.4 节），所有 bean 用 `registerBean` 手工注册。这是 Lambda 冷启动优化的最激进形态。

**指定默认函数**：容器里有多个函数时，用属性声明"谁是默认"以及"组合顺序"：

```properties
spring.cloud.function.definition=uppercase|exclaimer
```

竖线是组合语法（第三章 3.7 节）；不配置时框架有"唯一函数回退"机制（3.3 节）。

## 2.2 一条请求的一生：`GET /uppercase/zzz`

以 MVC 栈为例，把全链路走一遍（每一步的类与行号都将在后文展开）：

```
1  FunctionHandlerMapping#getHandlerInternal          判断这个 URL 有没有函数认领
     └─ FunctionWebRequestProcessingHelper#findFunction  按 URL 前缀逐步试探目录
          └─ FunctionCatalog#lookup("uppercase")          从目录拿 FunctionInvocationWrapper
2  请求属性里存下函数对象（WebRequestConstants.HANDLER）
3  FunctionController#get                              进入 @GetMapping("/**") 处理方法
     └─ FunctionWebRequestProcessingHelper#processRequest
          ├─ 组装 Message：payload="zzz"，HTTP 头拷进 MessageHeaders
          ├─ FunctionInvocationWrapper#apply(message)     ★ 核心执行引擎
          │    ├─ fluxifyInputIfNecessary                 输入 Flux 化适配
          │    ├─ convertInputIfNecessary                 Message → String 转换
          │    ├─ target.apply("zzz")                     你的函数真正执行
          │    └─ convertOutputIfNecessary                "ZZZ" → 输出消息转换
          └─ 把结果包成 ResponseEntity，提取 Message 头到 HTTP 头
4  响应体 "ZZZ"，Content-Type 按需协商
```

记住这个五段式：**映射（HandlerMapping）→ 控制器（Controller）→ 装配（processRequest）→ 执行（FunctionInvocationWrapper）→ 回包（ResponseEntity）**。换成 AWS Lambda 宿主时，只有第 1、3、4 步换了壳（`FunctionInvoker#handleRequest` 直接做装配+执行），第 2、5 步的平台语义不同——这正是"宿主只是壳"的字面意思。

## 2.3 自动装配总装：`ContextFunctionCatalogAutoConfiguration`

白话：这是普通 Boot 应用（非 functional 模式）的总装车间，一口气装配出四样东西——**目录（registry）、路由器（functionRouter）、JSON 引擎（JsonMapper）、转换器栈（SmartCompositeMessageConverter）**。

【源码证据】`spring-cloud-function-context/.../config/ContextFunctionCatalogAutoConfiguration.java`（305 行）。注册入口在 context 模块的 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`，共 5 个自动配置：

```
ContextFunctionCatalogAutoConfiguration   ← 总装车间（本章）
CloudEventsFunctionExtensionConfiguration  ← CloudEvents 扩展（7.7 节）
KotlinLambdaToFunctionAutoConfiguration    ← Kotlin lambda 支持（8.4 节）
FunctionsEndpointAutoConfiguration         ← /actuator/functions（8.1 节）
ObservationAutoConfiguration               ← Micrometer 观测（8.2 节）
```

### 2.3.1 `functionCatalog` bean：转换器栈的拼装（116-170 行）

```java
@Bean
public FunctionRegistry functionCatalog(List<MessageConverter> messageConverters, JsonMapper jsonMapper,
        ConfigurableApplicationContext context, @Nullable FunctionInvocationHelper<Message<?>> functionInvocationHelper,
        FunctionProperties functionProperties,
        @Value("${spring.cloud.function.registry.cache-size:1000}") int wrappedFunctionDefinitionsCacheSize) {
    // 1) ConversionService：复用容器里现成的；再把用户声明的所有 GenericConverter bean 挂上去
    // 2) MessageConverter：把用户声明的一律收编进 Composite——但有一个过滤器：
    //    isConverterEligible() 拒收 org.springframework.* 开头且非 org.springframework.cloud.* 的转换器
    // 3) 追加三个保底转换器：JsonMessageConverter、ByteArrayMessageConverter、StringMessageConverter
    // 4) SmartCompositeMessageConverter 包装，注入 MessageConverterHelper 供应商
    return new BeanFactoryAwareFunctionRegistry(conversionService, messageConverter, jsonMapper,
            functionProperties, functionInvocationHelper, wrappedFunctionDefinitionsCacheSize);
}
```

两个容易被忽略的细节：

- **`isConverterEligible` 的黑名单逻辑**（185-191 行）：来自 Boot 容器的 Spring 自带转换器（如 `ApplicationJsonMessageMarshallingConverter`）一律拒收，只放行 `org.springframework.cloud.*` 和用户自己的转换器。这是为了让"用户自定义转换器永远排在框架保底转换器之前"的语义不被 Boot 自动配置搅局——顺序即优先级，`SmartCompositeMessageConverter` 是逐个尝试、第一个成功的赢（6.2 节）。
- **`registry.cache-size` 默认 1000**：这是 `wrappedFunctionDefinitions`（组合函数包装缓存）的 LRU 上限，见 3.2 节。

### 2.3.2 `functionRouter` bean：路由函数也是函数（173-183 行）

```java
@Bean(RoutingFunction.FUNCTION_NAME)   // bean 名就是 "functionRouter"
public RoutingFunction functionRouter(FunctionCatalog functionCatalog, FunctionProperties functionProperties,
        BeanFactory beanFactory, @Nullable MessageRoutingCallback routingCallback,
        @Nullable DefaultMessageRoutingHandler defaultMessageRoutingHandler) {
    if (defaultMessageRoutingHandler != null) { /* 注册为 DEFAULT_ROUTE_HANDLER 兜底函数 */ }
    return new RoutingFunction(functionCatalog, functionProperties, new BeanFactoryResolver(beanFactory), routingCallback);
}
```

路由器本身也被注册成 catalog 里的一个函数（名字 `functionRouter`）。这个"自举"设计让所有宿主无需理解路由——拿不到具名函数时 `lookup(RoutingFunction.FUNCTION_NAME)` 总能拿到一个"能自己决定下一步调谁"的函数（第四章）。

### 2.3.3 `JsonMapperConfiguration`：Jackson 3 优先（214-304 行）

5.0 的重大变化点：JSON 引擎从 Jackson 2 迁到 **Jackson 3**（包名 `tools.jackson.databind`）。选择顺序：属性 `spring.cloud.function.preferred-json-mapper`（gson/jackson）→ classpath 上有 `tools.jackson.databind.ObjectMapper` 就用 Jackson → 否则 Gson → 都没有则抛 `IllegalStateException`。Jackson 分支的定制（295-300 行）：加 `JodaModule`、关闭 `FAIL_ON_EMPTY_BEANS`/`FAIL_ON_UNKNOWN_PROPERTIES`/`FAIL_ON_NULL_FOR_PRIMITIVES`，classpath 有 Kotlin 时自动尝试挂 `tools.jackson.module.kotlin.KotlinModule`（挂不上只警告不失败）。

`JsonMapper` 接口（`org.springframework.cloud.function.json.JsonMapper`）在框架内有两个不可替代的用途：① 作为一个"无类型嗅探"的 JSON 工具被 `SimpleFunctionRegistry` 直接调用（`jsonMapper.fromJson(...)`，见 6.4 节）；② 提供 `isJsonString(...)`/`isJsonContentType(...)` 判定，决定要不要走 JSON 转换、失败了要不要抛。

### 2.3.4 `PlainFunctionScanConfiguration`：包扫描是个默认关不掉但默认扫不到的开关（204-211 行）

```java
@ComponentScan(basePackages = "${spring.cloud.function.scan.packages:functions}",
        includeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = { Supplier.class, Function.class, Consumer.class }),
        excludeFilters = @Filter(type = FilterType.ANNOTATION, classes = { Configuration.class, Component.class}))
@ConditionalOnProperty(prefix = "spring.cloud.function.scan", name = "enabled", havingValue = "true", matchIfMissing = true)
```

默认开启，扫默认包 `functions`——除非你的函数就放在 `functions` 包里，否则它什么也扫不到；要扫别的包用 `spring.cloud.function.scan.packages`。注意它把 `@Component` 排除在外（那些交给普通容器机制），真正目标是"裸函数类"。

### 2.3.5 `ineligibleDefinitions`：目录的负面清单（`FunctionProperties` 98-113 行）

`FunctionProperties` 构造器里硬编码了一张黑名单：`org.springframework.boot`、CloudEvents 扩展配置、`FunctionsEndpointAutoConfiguration`、各 metrics bean、`kotlinToFunctionTransformer` 等。原因：框架自己注册的这些 bean 恰好是 `Supplier` 类型（如 metrics 端点数据源），若放行会被当成"用户的函数"参与默认函数推断与 `/actuator/functions` 枚举。`spring.cloud.function.ineligible-definitions` 允许用户追加。

## 2.4 `ContextFunctionCatalogInitializer`：`spring.functional.enabled` 的极简容器路线

白话：不走 Boot 自动配置、不走 `@Configuration` 解析的另一条装配路线，专为冷启动敏感的 Serverless 场景准备。

【源码证据】`spring-cloud-function-context/.../config/ContextFunctionCatalogInitializer.java`。它通过 `META-INF/spring.factories` 注册为 `ApplicationContextInitializer`（不是自动配置类）：

```
org.springframework.context.ApplicationContextInitializer=\
org.springframework.cloud.function.context.config.ContextFunctionCatalogInitializer
```

`initialize()` 只做一件事（80-86 行）：`spring.functional.enabled=true` 时挂上一个 `BeanDefinitionRegistryPostProcessor`。后者在 `register()`（116-193 行）里手工完成四步：

1. 注册 `PropertySourcesPlaceholderConfigurer`（属性占位符解析，否则函数定义表达式解不了）；
2. **关键一刀**：`ConfigurationClassPostProcessor` 还没注册时，用一个空壳 `DummyProcessor` 占掉它的 bean 名（128-134 行）——配置类解析引擎被整体跳过，这就是 functional 模式快的根源；
3. 注册 `JsonMapper`（复用 2.3.3 的配置类静态方法）；
4. 注册 `SimpleFunctionRegistry`（**不带 BeanFactory 感知**，因为函数都来自显式 `registerBean`）+ `FunctionProperties` + `FunctionRegistrationPostProcessor`——一个 `BeanPostProcessor`，拦截所有 `FunctionRegistry` 类型的 bean 初始化，把容器里所有 `FunctionRegistration` bean 批量塞进目录（223-253 行）。

两条路线的分工可以总结成一张表：

| | 自动装配路线（默认） | functional 路线（`spring.functional.enabled=true`） |
|---|---|---|
| 入口 | `ContextFunctionCatalogAutoConfiguration` | `ContextFunctionCatalogInitializer` |
| 函数来源 | BeanFactory 里所有函数式 bean + POJO | 显式 `registerBean` 的函数 + `FunctionRegistration` bean |
| 类型发现 | 懒发现时严谨推导（3.9 节） | 要求 `FunctionRegistration` 显式带 type，否则抛 `IllegalStateException`（239-246 行） |
| 配置类解析 | 正常 | 跳过（DummyProcessor 占位） |
| 目录实现 | `BeanFactoryAwareFunctionRegistry` | `SimpleFunctionRegistry` |

这条 functional 路线主要被 `FunctionEndpointInitializer`（5.5 节）和 AWS 适配器（7.2 节）消费。

## 2.5 本章小结

- 一个函数 = `@Bean` 方法 / POJO / Lambda 三种写法之一 + `spring.cloud.function.definition` 指名；框架用 `FunctionCatalog` 把它变成宿主无关的可执行物。
- 自动装配总装出四件套：`BeanFactoryAwareFunctionRegistry`（目录）、`RoutingFunction`（自举路由）、`JsonMapper`（Jackson 3 优先）、`SmartCompositeMessageConverter`（用户转换器优先 + 框架保底）。
- `spring.functional.enabled` 走 initializer 路线，跳过配置类解析引擎，是 Serverless 冷启动的最激进优化。
- 所有"多余"的 bean（metrics、端点内部件）靠 `ineligibleDefinitions` 黑名单挡在目录之外。
- 下章进入心脏：目录到底怎么 lookup、包装、组合一个函数。

---

# 三、函数目录（FunctionCatalog 体系）：全框架的心脏

## 3.1 开场先看墓碑：3.0 前后两个世界

【源码证据·考古】对 v2.1.0 与 v4.0.0 分别执行 `git ls-tree` 可见 catalog 包的生死簿：

| v2.1.0（旧世界） | v4.0.0（新世界） |
|---|---|
| `AbstractComposableFunctionRegistry` | 删除 |
| `InMemoryFunctionCatalog` | 删除 |
| `FunctionInspector` | 删除 |
| `MessageFunction` / `MessageSupplier` / `MessageConsumer` | 删除 |
| — | `SimpleFunctionRegistry`（3.1 新增） |
| — | `BeanFactoryAwareFunctionRegistry`（3.0 新增） |
| — | `FunctionAroundWrapper`、`HeaderEnricher`（3.1 新增） |

旧世界的精神是"启动期扫描、注册、组合，目录里存的是已注册的成品"；新世界的精神是"**目录只是视图，真正的仓库是 BeanFactory，lookup 时才动手**"。理解了这句话，第三章所有设计都顺理成章。

## 3.2 接口链：`FunctionCatalog` → `FunctionRegistry` → 两级实现

【源码证据】`spring-cloud-function-context/.../context/FunctionCatalog.java`（100 行，小到可以通读）：

```java
public interface FunctionCatalog {
    default <T> T lookup(String functionDefinition) { ... }
    default <T> T lookup(Class<?> type, String functionDefinition) { ... }
    // 这个重载是给"输出转换"开的口子：告诉目录"我要 application/json 或 text/plain 的输出"
    default <T> T lookup(String functionDefinition, String... expectedOutputMimeTypes) { ... }
    <T> T lookup(Class<?> type, String functionDefinition, String... expectedOutputMimeTypes);  // 87 行，唯一抽象方法
    Set<String> getNames(Class<?> type);   // 89 行
    default int size() { throw new UnsupportedOperationException(...); }  // 96 行
}
```

`FunctionRegistry`（继承 `FunctionCatalog`）追加 `register(FunctionRegistration)` 与反注册事件方法。实现链两级：

- **`SimpleFunctionRegistry`**（1684 行，本章主角）：不依赖 BeanFactory，函数必须显式 `register()` 进来——AWS 适配器、非容器场景、以及 functional 模式都直接用它；
- **`BeanFactoryAwareFunctionRegistry extends SimpleFunctionRegistry`**：只做一件事——lookup 不到时去 BeanFactory 里**懒发现**（3.8 节）。

`lookup` 的第四个参数 `expectedOutputMimeTypes` 值得单独一提：它是"**调用方声明期望的输出格式**"的通道，AWS 适配器 `FunctionInvoker` 就以 `functionCatalog.lookup(definition, "application/json")` 取函数（7.2 节），这个数组最终会塞进 `FunctionInvocationWrapper.expectedOutputContentType`，影响 6.5 节的输出转换。

## 3.3 `SimpleFunctionRegistry` 内部结构：一个 Set、一个 LRU、一把锁

【源码证据】`spring-cloud-function-context/.../catalog/SimpleFunctionRegistry.java:95-150`。

```java
public class SimpleFunctionRegistry implements FunctionRegistry {
    private final Set<FunctionRegistration<?>> functionRegistrations = new CopyOnWriteArraySet<>();  // 102
    private final Map<String, FunctionInvocationWrapper> wrappedFunctionDefinitions;                  // 104
    @Autowired(required = false)
    private FunctionAroundWrapper functionAroundWrapper;                                              // 118
    ...
    this.wrappedFunctionDefinitions = new LinkedHashMap<String, FunctionInvocationWrapper>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, FunctionInvocationWrapper> eldest) {
            return size() > wrappedFunctionDefinitionsCacheSize;   // LRU，默认 1000
        }
    };
```

三个成员各司其职：

- `functionRegistrations`：登记簿（`FunctionRegistration`：目标 + 类型 + 名字）；
- `wrappedFunctionDefinitions`：**包装缓存**——每次 `lookup` 成功都会生成一个 `FunctionInvocationWrapper` 包装对象，缓存放这里，下次同名 lookup 直接命中。只有 `isSingleton` 的函数才进缓存（363-367 行），prototype 函数每次 lookup 都产生新包装（包装上可设置 `skipInputConversion` 等状态，缓存会串味）；
- `functionAroundWrapper`：一个可选的"环绕执行"挂点，`apply()` 一进来先判断（663-666 行）：`functionAroundWrapper != null && !this.wrapped` 就先走包装逻辑。可观测性（8.2 节的 `ObservationFunctionAroundWrapper`）就是靠这个口子织入的。

### 3.3.1 lookup 主流程与单函数回退

```java
public <T> T lookup(Class<?> type, String functionDefinition, String... expectedOutputMimeTypes) {
    functionDefinition = this.normalizeFunctionDefinition(functionDefinition);   // ① 规整 + 回退
    FunctionInvocationWrapper function = this.doLookup(type, functionDefinition, expectedOutputMimeTypes);  // ② 缓存→组合
    return (T) function;
}
```

`normalizeFunctionDefinition`（270-289 行）做两件事：逗号归一为竖线（`"a,b"` ≡ `"a|b"`）；空定义时读系统属性 `spring.cloud.function.definition`。然后是**单函数回退**（single function fallback，276-287 行）：如果目录里去掉 `functionRouter`/`defaultMessageRoutingHandler` 后**只剩一个函数**，而你 lookup 的名字不匹配它，就直接返回那个唯一函数。这就是"容器里只有一个函数时，`POST /` 不带函数名也能调通"的机制。开关：`spring.cloud.function.single-function-fallback-enabled`（默认 true，`FunctionProperties` 204-210 行）。`BeanFactoryAwareFunctionRegistry` 在多函数且未配置 definition 时会打一条著名的警告："Multiple functional beans were found ... Please use 'spring.cloud.function.definition' property"（147-148 行）。

`doLookup`（243-261 行）：synchronized 查缓存 → 未命中则 `compose()` → 命中后顺手把 `expectedOutputMimeTypes` 写进包装对象。

## 3.4 `FunctionInvocationWrapper`：一个类当四个用

白话：它是目录对外吐出的**统一可执行物**。你的 `Function<String,String>`、别人的 `Supplier<String>`、第三个函数 `Consumer<Flux<Message<String>>>`，从目录里拿出来时全都是同一个类——`FunctionInvocationWrapper`。宿主代码（HTTP 控制器、Lambda 适配器）只需要认识它，不需要关心背后是什么。

【源码证据】`SimpleFunctionRegistry.java:443-486`：

```java
public class FunctionInvocationWrapper implements Function<Object, Object>, Consumer<Object>, Supplier<Object>, Runnable {
    private Object target;                // 你的原始函数（或组合后的 lambda）
    private Type inputType, outputType;   // 规整过的类型（TypeVariable/Wildcard → Object.class）
    private String functionDefinition;    // 名字（组合后是 "a|b"）
    private boolean composed;             // 是否组合函数
    private final boolean message;        // 输入类型是不是 Message 系
    private String[] expectedOutputContentType;
    private boolean skipInputConversion, skipOutputConversion;   // 两个转换旁路开关
    private boolean isSingleton = true;   // prototype 不进缓存
    private boolean propagateInputHeaders;  // spring.cloud.function.configuration.<fn>.copy-input-headers
    private final ThreadLocal<Message<Object>> unconvertedResult;  // PostProcessingFunction 用
    ...
```

四合一的意义：HTTP 控制器拿到函数后，`function.isSupplier()` 决定 GET 语义、`function.isConsumer()` 决定 DELETE 语义（5.3 节），不需要 instanceof 一堆 JDK 接口。构造器（487-513 行）做了两件隐形工作：`normalizeType` 把泛型擦除产物（TypeVariable/Wildcard）规整为 `Object.class`；从 `FunctionProperties.configuration.<fn>.copy-input-headers` 读出 `propagateInputHeaders` 备用。

`apply()` 是对外总入口（656-672 行），三行核心逻辑：

```java
public Object apply(Object input) {
    Object result;
    if (functionAroundWrapper != null && !this.wrapped) {   // 环绕挂点（观测等）
        this.wrapped = true;
        result = functionAroundWrapper.apply(input, this);
    } else {
        result = this.doApply(input);                        // 真正执行
    }
    return result;
}
```

`get()`/`accept()`/`run()` 全部收敛到 `apply`（674-687 行）——supplier 就是 `apply(null)`。

## 3.5 `doApply`：输入适配 → 输入转换 → 分派 → 输出转换

【源码证据】`SimpleFunctionRegistry.java:827-858`。这是全框架执行频率最高的方法，骨架只有四段：

```java
Object doApply(Object input) {
    input = this.fluxifyInputIfNecessary(input);                       // ① 结构适配
    Object convertedInput = input == null ? null
            : this.convertInputIfNecessary(input, this.inputType);     // ② 类型转换
    if (this.isRoutingFunction() || this.isComposed()) {
        result = ((Function) this.target).apply(convertedInput);       // ③a 路由/组合函数：直接放行
    } else if (this.isSupplier()) {
        result = ((Supplier) this.target).get();
    } else if (this.isConsumer()) {
        result = this.invokeConsumer(convertedInput);
    } else {
        result = this.invokeFunction(convertedInput);                  // ③b 普通函数
    }
    if (this.postProcessor != null) { ... }                            // ④ 后处理钩子
    if (result != null && this.outputType != null) {
        result = this.convertOutputIfNecessary(result, this.outputType, this.expectedOutputContentType);  // ⑤ 输出转换
    }
    return result;
}
```

### 3.5.1 `fluxifyInputIfNecessary`：把一切输入归一到 Publisher 视角（915-982 行）

这是"响应式为统一执行模型"这句哲学的代码落点，按顺序处理三种情况：

1. **集合拆包**：输入 Message 的 payload 是 JSON 数组字符串（`JsonMapper.isJsonStringRepresentsCollection`）、而函数输入类型**不是**集合/数组时，把 JSON 数组拆成 `List<Message>`，函数将被**逐元素调用**（936-955 行）——"一个请求调 N 次"的语义就来自这里；
2. **Iterable → Flux**：payload 是 Iterable 且函数输入不是集合，`Flux.fromIterable`；
3. **函数吃 Publisher 而输入不是**：包成 `Flux.just(...)` / `Mono.just(...)`；函数吃 Mono 而来了 Flux 也会在 1587-1591 行的 `convertInputPublisherIfNecessary` 里转换。

另外一个防御细节：函数是 Consumer 且输入类型不是 Publisher、Message 头里没有 `user-agent` 时直接原样返回（916-918 行）——避免对纯消费函数做无谓包装。

### 3.5.2 输入转换：`convertInputIfNecessary` 的四条岔路（1188-1257 行）

- **Publisher 输入**：递归逐元素转换（`convertInputPublisherIfNecessary`，1586-1613 行）；
- **多参数函数**（`Function<Tuple2<A,B>,...>` 或 `BiFunction` 风格）：用 SpEL `getT1()/getT2()` 从 Tuple 里拆参数逐个转换（1197-1206 行；解析器在 1156-1167 行）；
- **`skipInputConversion=true`**：包成 `Message` 或 `OriginalMessageHolder` 原样放行——这是给 Stream 等上层框架的旁路（已转换过的数据不再转换）；
- **Message 输入（主路径）**：先 `filterOutHeaders` 摘掉 Stream 专属 header（1262-1264 行，注释明说是临时方案）；`KafkaNull`（Kafka tombstone 的 payload 类型）原样放行（1217-1219 行）；`functionInvocationHelper.preProcessInput`（CloudEvents 头规范化挂点，1221-1223 行）；然后 `convertInputMessageIfNecessary`（1457-1517 行）用 `messageConverter.fromMessage(message, rawType)` 转换，转换结果为 null 时**回退用原始 Message**（1495-1509 行，注释：转换失败保留原样，让开发者尽早发现问题）；Message 转换仍拿不到结果时，再走"非 Message 输入"通道用 `JsonMapper` 或 `ConversionService` 兜底（1226-1231 行 → `convertNonMessageInputIfNecessary` 1396-1431 行）。

转换成功后不是直接返回，而是包进 **`OriginalMessageHolder`**（1643-1660 行）：`{转换后的值, 原始 Message}` 二元组。原始 Message 一路带着走，函数调用完成后 `enrichInvocationResultIfNecessary`（889-899 行）用它把**输入头拷贝到输出 Message**（跳过 null 值头），CloudEvents 场景还可能触发 `postProcessResult` 回填属性。这个内部类是理解"header 传播"的关键。

**Void 输入断言**（1170-1184 行）：函数输入类型是 `Void`（如 `Consumer<Void>`），若来了非空输入直接 `Assert.isNull` 抛出——"supplier 风格的函数不接受输入"是硬约束。

### 3.5.3 分派三兄弟：Function / Consumer / Supplier

`invokeFunction`（996-1017 行）的分叉值得细看：**函数输入类型不是 Publisher、但转换后输入却是 Publisher**（说明上游适配给了流）——用 `Mono.map` / `Flux.map` **逐元素**调用你的函数，错误用 `doOnError` 挂日志；反之整体调用一次。`invokeFunctionAndEnrichResultIfNecessary`（1023-1070 行）里还有两个细节：KafkaNull → `apply(null)`（1056-1058 行）；输入是 Message 而函数输入类型不是 Message 时**抽 payload 再调用**（1047-1049 行）——这就是"你写 `Function<String,String>` 却收到 `Message<String>` 时框架自动剥壳"的地方。

`invokeConsumer`（1110-1144 行）按输入是否 Publisher 分四路：Publisher 输入 + Consumer 吃 Publisher → `transform` 内直接 `accept` 整个流、外部 `Mono.ignoreElements().then()` 等待完成；Publisher 输入 + Consumer 吃单值 → `doOnNext` 逐元素；非 Publisher → 直接 `accept`。返回值是"完成信号"（`Mono<Void>` 语义）或 null。

Supplier 无需转换输入，`get()` 直达。

## 3.6 输出转换：`convertOutputIfNecessary`（1294-1365 行）

输出侧的决策树（详细展开见 6.5 节，这里先给骨架）：

1. `skipOutputConversion` → 原样返回（组合函数内部环节全部置位，见 3.7 节）；
2. Publisher → 递归逐元素；
3. Message 输出：payload 是 byte[] 且调用方没声明期望类型 → 原样返回；`isExtractPayload`（1266-1287 行）判定是否**抽 payload 丢弃 Message 壳**——规则：输出类型是 `Message` 系 / 开了 `propagateInputHeaders` / 是路由函数 → 保留壳；payload 是 byte[] → 保留壳；有"保留信号头"（`lambda*`、`scf-func-name` 前缀，或 `functionInvocationHelper.isRetainOutputAsMessage`）→ 保留壳；否则抽壳；
4. `PassThruFunction`（透传函数，1662-1667 行，Stream 的 `Function` 占位）特判：强制按 expectedContentType 转换（1316-1329 行，注释标注 scst-2303）；
5. 调用方声明了 `expectedOutputContentType` → `convertOutputMessageIfNecessary`（1540-1564 行）按逗号拆分逐个尝试，第一个转换成功的赢；集合/数组输出逐元素转换（`convertMultipleOutputValuesIfNecessary`）；`Tuple` 多输出逐分量转换（1522-1534 行）。

没有声明期望类型时直接返回转换后对象（1331-1341 行），但有一处 5.0 的新语义：**POJO 函数的输出如果是 Message 派生而转换后丢了壳，会重新包回 Message**（1334-1339 行，GH-1307——保证 POJO 函数与普通函数输出行为一致）。

## 3.7 组合（composition）：`andThen` 的类型推导与四条规则

白话：`spring.cloud.function.definition=a|b|c` 在 lookup 时不是字符串拼接，而是真实执行两次 `andThen`，产出一个**新的** `FunctionInvocationWrapper`（名字 `"a|b|c"`，`composed=true`）。

【源码证据】`SimpleFunctionRegistry.java:728-797`。先看约束（729-740 行）：

1. `andThen` 的参数必须是 `FunctionInvocationWrapper`（目录体系的组合不与裸 Function 混用）；
2. **禁止自组合**（`this.equals(after)` → `IllegalArgumentException`，防递归）；
3. **多参数函数不支持组合**（Tuple2 系）；
4. Consumer 收尾的合法性在类型推导时检查：`this.outputType == null`（自己是 Consumer）还接别人 → `"Can NOT compose anything with Consumer"`。

类型推导四分支（756-783 行）——组合后的类型用 `ResolvableType` 现场拼装：

| this | after | 组合类型 |
|---|---|---|
| Function | Function | `Function<this.inputType, after.outputType>` |
| Supplier | Function | `Supplier<after.outputType>`（输出若是 Flux/Mono 则保留包裹） |
| Function | Consumer | `Consumer<this.inputType>` |
| Supplier | Consumer | `Supplier<Object>` |

两处工程细节：**Supplier→Consumer 的 Publisher 缝合**（744-749 行，GH-1141）：Supplier 输出是 Publisher 而 Consumer 吃单值时，用 `ConsumerWrapper`（1670-1683 行，`messageFlux.doOnNext(...).subscribe()`）把 Consumer 包成吃 Flux 的形态；**转换旁路的传播**（752-753 行）：`this` 与 `after` 都被 `setSkipOutputConversion(true)`，因为组合内部环节不再需要转成"对外格式"，只有组合整体在 `doApply` 第⑤段按对外类型转换一次——**转换发生在管道边界，而不是每个节点**。这是理解"组合函数为什么高效"的关键。

组合函数的执行体是一段裸 lambda（754 行）：`v -> after.doApply(this.doApply(v))`——注意 `doApply` 而非 `apply`，绕开环绕挂点，保证一次调用只产生一次观测（观测在组合整体的 `apply` 处）。

## 3.8 `BeanFactoryAwareFunctionRegistry`：懒发现与 POJO 函数

白话：目录里找不到时，去 BeanFactory 按名字找 bean——**第一次 lookup 时发现、发现后登记，第二次就命中缓存**。这就是"启动零扫描"的含义：应用启动成本与函数数量无关。

【源码证据】`spring-cloud-function-context/.../catalog/BeanFactoryAwareFunctionRegistry.java:131-219`。`lookup` 覆写父类：先查缓存，未命中则进入 `synchronized (lookupLock)` 块做发现（153 行）。发现逻辑按候选类型分六路（156-213 行）：

1. **候选本身是 `FunctionRegistration` bean**（168-170 行）：直接拿来注册——这是 `@Bean FunctionRegistration<?>` 显式声明的口子（可带自定义 type 与属性）；
2. **`BiFunction` / `BiConsumer`**（171-173 行 → `registerMessagingBiFunction` 222-257 行）：包装成 `Function<Message, Map>` 风格——第一参数是 payload、第二参数必须是 `Map`（headers），并断言这一点（231-235 行）。这让"payload + headers 双参数"的函数写法成为可能。KafkaNull 同样特判为 null（242-244 行）；
3. **Kotlin lambda**（174-180 行）：交给 `KotlinFunctionWrapper` 包装（8.4 节）；
4. **POJO 函数**（181-189 行 → `isFunctionPojo` 280-286 行）：既不是 Function/Consumer/Supplier、也不是 synthetic 类的 bean。发现流程：`FunctionTypeUtils.discoverFunctionalMethod(pojoClass)` 找函数式方法 → `proxyTarget`（303-314 行）用 **Spring AOP CGLIB 代理**包一层（`setProxyTargetClass(true)` + `MethodInterceptor` 直接反射调用目标方法）→ 注册时打上 `isPojoFunction=true` 属性。运行时 6.5 节的 GH-1307 输出补壳逻辑靠这个标记识别；
5. **特殊注册**（190-193 行）：存在 `函数名 + "_registration"` 后缀 bean 时取之（`FunctionRegistration.REGISTRATION_NAME_SUFFIX`，`FunctionRegistration.java:55`）——目前只有 Kotlin 模块用（isSpecialFunctionRegistration 的 javadoc 294 行明说）；
6. **普通函数式 bean**（194-196 行）：`FunctionTypeUtils.discoverFunctionType(functionCandidate, functionName, applicationContext)` 严谨推导类型。

发现失败的日志值得记住排错用："Function 'xxx' is not available in FunctionCatalog or BeanFactory"（209 行）。

## 3.9 类型发现：`FunctionTypeUtils` 与 `FunctionContextUtils`

白话：组合、转换、HTTP 协商全都依赖"这个函数吃什么、吐什么"的精确类型。但 Java 泛型会擦除，lambda 没有方法签名，`@Bean` 方法还有继承/工厂方法等变体——类型发现是整个框架工程上最难啃的部分，`FunctionTypeUtils`（674 行）是它的武器库。

【源码证据】`spring-cloud-function-context/.../catalog/FunctionTypeUtils.java`。API 全景（`grep "public static"` 实测 35 个静态方法），最常用的几组：

- **判型**：`isPublisher`/`isFlux`/`isMono`/`isMessage`/`isTypeCollection`/`isTypeMap`/`isTypeArray`/`isMultipleArgumentType`/`isSupplier`/`isConsumer`/`isFunction`；
- **取件**：`getInputType`/`getOutputType`/`getGenericType`/`getImmediateGenericType`/`getRawType`（处理 ParameterizedType/GenericArrayType/TypeVariable 全谱系）；
- **发现**：`discoverFunctionType(Object function, String functionName, GenericApplicationContext ctx)`（442 行）是主入口，四路尝试：`@Bean` 工厂方法（`discoverFunctionTypeFromFunctionFactoryMethod`，从 BeanDefinition 里挖出 Method）→ lambda/合成类的 functional method → 类上的 `Function`/`Consumer`/`Supplier` 接口签名（`discoverFunctionTypeFromClass`）→ 兜底 `FunctionContextUtils.findType`。

`FunctionContextUtils.findType`（`config/FunctionContextUtils.java`）的独门手艺：从 **BeanDefinition + BeanFactory** 里拿 `ResolvableType`，能穿透 `@Bean` 方法的泛型声明与字段注入的泛型签名——这是"lambda 泛型擦除后还能知道 `Function<String,String>` 而不是 `Function<Object,Object>`"的最后一道保险。

`FunctionTypeUtils` 还有两个组合用的入口：`getInputCount`/`getOutputCount`（331/342 行）返回"元数"——普通函数 1，Tuple 多参函数 N。

## 3.10 头部增强：把"加 header"也做成函数

白话：`spring.cloud.function.configuration.<fn>.input-header-mapping-expression.<header>=<SpEL>` 能给指定函数的输入/输出动态加 header。实现方式是彻头彻尾的"组合哲学"：**不是在执行引擎里加 if，而是在函数链前后各插一个 HeaderEnricher 包装函数**。

【源码证据】`SimpleFunctionRegistry.java:374-421`。`compose()` 组装组合函数时调用 `enrichInputIfNecessary`/`enrichOutputIfNecessary`：

```java
if (!CollectionUtils.isEmpty(configuration.getInputHeaderMappingExpression())) {
    BeanFactoryResolver beanResolver = ...;   // SpEL 里可以引用容器 bean
    HeaderEnricher enricher = new HeaderEnricher(configuration.getInputHeaderMappingExpression(), beanResolver);
    FunctionInvocationWrapper w = new FunctionInvocationWrapper("inputHeaderEnricher", enricher, Message.class, Message.class);
    composedFunction = (FunctionInvocationWrapper) w.andThen((Function<Object, Object>) composedFunction);  // 插到前面
    composedFunction.functionDefinition = functionDefinition;   // 名字保持对外不变
}
```

`enrichInputIfNecessary` 把 enricher 插在组合函数**之前**、`enrichOutputIfNecessary` 插在**之后**，然后**把组合函数的 `functionDefinition` 名字改回原值**——对宿主完全透明。`copy-input-headers` 属性则在 `FunctionInvocationWrapper` 构造期读入 `propagateInputHeaders`，在 3.6 节 `isExtractPayload` 判定里决定输出是否保留 Message 壳（1270 行）。`HeaderEnricher` 的 SpEL 用 `SimpleEvaluationContext` 只读访问器（config 包 `HeaderEnricher.java`），表达式可以是字面量（如 `"v1"`）也可以是对 payload/headers/bean 的只读表达式。

## 3.11 本章小结

- 目录 = 登记簿（`FunctionRegistration` Set）+ 包装缓存（LRU 1000，仅 singleton）+ 懒发现钩子（`BeanFactoryAwareFunctionRegistry`）。
- `FunctionInvocationWrapper` 四合一（Function/Consumer/Supplier/Runnable），宿主只认识它；`apply` = 环绕挂点 → `doApply`（flux 化 → 输入转换 → 三路分派 → 输出转换）。
- 组合 `a|b` 是运行时 `andThen`：类型现场推导、转换只在管道边界做一次、名字对宿主透明。
- 类型发现是全框架工程难点：`FunctionTypeUtils`（判型/取件/发现）+ `FunctionContextUtils`（从 BeanDefinition 穿透泛型）。
- POJO 也能当函数：CGLIB 代理 + 函数式方法反射；BiFunction/BiConsumer 自动包装为 payload+headers 风格。
- 头部增强、路由、观测，全都复用同一个原语：包装函数。

---

# 四、路由（RoutingFunction）：一个入口分发 N 个函数

## 4.1 为什么需要路由

Serverless 平台给你**一个**入口（Lambda 事件、HTTP 网关路径），消息中间件给你**一个**绑定通道——但容器里可能注册了 N 个函数。`RoutingFunction` 的答案：它自己也是一个函数（名字 `functionRouter`），行为是"**看一眼输入，决定调谁**"。它 since 2.1（javadoc 与 git 出生证明一致：2019-05-03，GH-238），是框架里资格最老的稳定件之一。

## 4.2 五级决策链

【源码证据】`spring-cloud-function-context/.../config/RoutingFunction.java:128-194`（`route` 方法）。源码注释本身就是决策链文档（115-127 行），对 `Message` 输入的完整顺序：

1. **`MessageRoutingCallback` 回调**（132-137 行）：用户代码实现接口返回函数定义，优先级最高——适合"路由逻辑太复杂，SpEL 写不下"的场景；
2. **Message 头 `spring.cloud.function.definition`**（`locateFunctionFromDefinitionOrExpression` 196-219 行）：支持 String 与 List（List 拼成组合定义）；
3. **Message 头 `spring.cloud.function.routing-expression`**：SpEL 表达式求值得到函数定义；
4. **属性 `spring.cloud.function.routing-expression`**（145-147 行）：对 Message 求值（root 对象是 Message）；
5. **属性 `spring.cloud.function.definition`**（148-150 行）；
6. 全部落空 → `IllegalStateException`（152-154 行）。

对 **Publisher** 输入（158-170 行）：只能用属性级 definition/expression；否则**逐元素递归路由**（`Flux.map(v -> route(v, ...))`）——但元素级路由要求元素是 Message 且原输入不是 Publisher（`assertOriginalInputIsNotPublisher` 221-225 行），语义是"流里每个消息各奔前程"。

**死循环保护**（187-191 行）：路由结果又是 `functionRouter` 自己 → 直接抛异常。**兜底函数**（`resolveFunction` 258-264 行）：按 definition lookup 不到时，回退 lookup `defaultMessageRoutingHandler`（`DefaultMessageRoutingHandler`，用户可注入，典型用法是死信处理）——"找不到就交给默认处理器而不是报错"。开关 `spring.cloud.function.routing.enabled`（元数据实证）可整体关闭路由。

## 4.3 SpEL 的两个求值上下文（安全设计）

【源码证据】`RoutingFunction.java:71-76`。路由表达式有**两套**求值上下文：

```java
private final StandardEvaluationContext evalContext = new StandardEvaluationContext();   // 属性表达式用
private final SimpleEvaluationContext headerEvalContext = SimpleEvaluationContext
        .forPropertyAccessors(DataBindingPropertyAccessor.forReadOnlyAccess()).build();  // 头部表达式用
```

`functionFromExpression`（241-256 行）按表达式来源选上下文：**来自 Message 头**的用 `SimpleEvaluationContext`（只读属性访问器，无类型引用、无构造器调用——头是外部输入，不能允许 `T(java.lang.Runtime)` 这类表达式）；**来自应用属性**的用 `StandardEvaluationContext`（加 `MapAccessor` 与 `BeanFactoryResolver`，配置是受信输入，可以引用容器 bean）。属性表达式求值前还会把 Message 包成大小写不敏感的 header 结构（243-245 行，`MessageUtils.toCaseInsensitiveHeadersStructure`）——HTTP 头大小写不敏感，SpEL 按 `headers.content-type` 访问不应因大小写翻车。求值结果是"函数定义字符串"（不是函数引用），再走 `resolveFunction`。

## 4.4 本章小结

- `functionRouter` 是注册进目录的一个普通函数，宿主"查不到具名函数"时自然落到它头上。
- 决策链五级：回调 > 头 definition > 头表达式 > 属性表达式 > 属性 definition；未命中可落 `defaultMessageRoutingHandler` 兜底。
- 流输入支持逐元素路由（各消息各奔前程），但有 Publisher 嵌套约束。
- SpEL 双上下文：头表达式只读（防注入），属性表达式全功能（可信配置）——安全边界画在"输入来源"上。

---

# 五、HTTP 端点（web 模块）：把 FunctionCatalog 暴露成 REST

## 5.1 三套端点实现，一个选择器

web 模块给同一个目录准备了三套 HTTP 门面：

| 实现 | 类 | 生效条件 | 风格 |
|---|---|---|---|
| MVC 控制器 | `web/mvc/FunctionController` + `FunctionHandlerMapping` | classpath 有 spring-webmvc（starter-function-web） | 注解式 `@GetMapping("/**")` |
| WebFlux 控制器 | `web/flux/FunctionController` + `FunctionHandlerMapping` | classpath 有 spring-webflux | 注解式 + `Mono/Flux` 原生回包 |
| 函数式端点 | `web/function/FunctionEndpointInitializer` | `spring.functional.enabled=true` + reactive | WebFlux.fn `RouterFunction`，自带 Reactor Netty |

三套门面共享同一套底层装配：`FunctionWebRequestProcessingHelper`（路径解析与请求处理）+ `FunctionWrapper`（函数 + 参数 + 头的三元组）+ `HeaderUtils`（HTTP 头 ↔ Message 头互转与过滤）。自动注册通过 web 模块的 `AutoConfiguration.imports`（ReactorAutoConfiguration × 2 + FunctionExporterAutoConfiguration）与 `spring.factories`（两个 `ApplicationContextInitializer`：`FunctionEndpointInitializer`、`FunctionExporterInitializer`）完成。

## 5.2 `FunctionHandlerMapping`：抢在注解映射之前认领路径

【源码证据】`spring-cloud-function-web/.../mvc/FunctionHandlerMapping.java`。它继承 `RequestMappingHandlerMapping`，构造器里 `setOrder(super.getOrder() - 5)`（63 行）——**比普通 MVC 的注解映射早 5 个量级介入**。两个覆写：

- `initHandlerMethods` 置空（78-79 行）：不走常规的 `@Controller` 扫描，`afterPropertiesSet` 里手动 `detectHandlerMethods(this.controller)` 只注册 FunctionController 自己（69-75 行）；
- `getHandlerInternal`（82-110 行）：先调 super（对 FunctionController 自身的 `/**` 匹配），然后 `spring.cloud.function.web.path` 前缀裁剪（93-98 行），再调用 `FunctionWebRequestProcessingHelper.findFunction(..., path, HttpRequestAttributeDelegate(request))`——找到了就把函数对象放进请求属性 `WebRequestConstants.HANDLER`（106 行），控制器稍后从属性里取。`HttpRequestAttributeDelegate`（113-124 行）是个把 `put` 转发到 request attribute 的 HashMap 适配器，让目录查找结果直接沉淀为请求属性。

### 5.2.1 路径解析算法：`doFindFunction`

【源码证据】`web/util/FunctionWebRequestProcessingHelper.java:183-219`。`/uppercase/zzz` 怎么知道函数叫 `uppercase`、参数是 `zzz`？**前缀逐步试探**：

```java
for (String element : path.split("/")) {
    builder.append(element);  // 逐段拼："upper" → "uppercase"
    name = builder.toString();
    value = path.length() > name.length() ? path.substring(name.length() + 1) : null;  // 剩余部分是参数
    FunctionInvocationWrapper function = functionCatalog.lookup(name);
    if (function != null) return postProcessFunction(function, value, attributes);
}
```

GET 请求还有一条前置捷径（187-193 行）：整条路径先按 Supplier lookup 一次（`GET /supplierName` = 拉取数据）。都失败后若配置了 `spring.cloud.function.definition`，最后按默认定义兜底 lookup（212-217 行）。解析结果（函数对象、参数字符串）通过 `WebRequestConstants.FUNCTION/ARGUMENT/SUPPLIER` 写入请求属性，供控制器与响应阶段复用。

## 5.3 `FunctionController`：六个映射与 HTTP 语义表

【源码证据】`spring-cloud-function-web/.../mvc/FunctionController.java`。全部映射都是 `/**`，靠**方法 + produces/consumes** 区分：

| 映射 | 方法 | 约束 | 语义 |
|---|---|---|---|
| `POST /**` | `post` | `Assert.isTrue(!function.isSupplier())`（141 行） | Function/Consumer 的输入 |
| `POST /**`（form/multipart） | `form` | — | multipart 文件逐个包成 `Message<MultipartFile>` 组成 Flux 调函数（79-97 行） |
| `POST /**` produces `text/event-stream` | `postStream` | — | SSE 输出 |
| `GET /**` | `get` | — | Supplier（无参）/ Function（尾段作参数） |
| `GET /**` produces `text/event-stream` | `getStream` | — | SSE / 流式输出 |
| `PUT /**` | `put` | — | 同 POST |
| `DELETE /**` | `delete` | `Assert.isTrue(function.isConsumer())`（168 行）+ `@ResponseStatus(NO_CONTENT)` | Consumer 触发，204 |

**方法白名单**：每个映射先过 `FunctionWebRequestProcessingHelper.isFunctionValidForMethod`（75-97 行）——若配置了 `spring.cloud.function.http.post=fn1;fn2` 这类显式映射，未列出的函数对该 HTTP 方法直接 400，错误信息里教你把函数加进列表或删掉映射（`buildBadMappingErrorMessage` 99-105 行）。默认不配置 = 全放行。

`wrapper()`（190-211 行）从请求属性取函数、收集 query 参数与 HTTP 头、取路径尾参数，组装 `FunctionWrapper`；`uri` 头被顺手设置（203 行）。

## 5.4 `processRequest`：一次调用的完整响应决策

【源码证据】`web/util/FunctionWebRequestProcessingHelper.java:108-176`。骨架：

```java
MessageBuilder builder = MessageBuilder.withPayload(argument);          // body 或路径尾参数
if (!CollectionUtils.isEmpty(wrapper.getParams()))
    builder = builder.setHeader(HeaderUtils.HTTP_REQUEST_PARAM, wrapper.getParams().toSingleValueMap());
inputMessage = builder.copyHeaders(headers.toSingleValueMap()).build(); // HTTP 头 → Message 头
if (function.isRoutingFunction()) function.setSkipOutputConversion(true); // 路由函数的真实函数自己转
Object result = function.apply(inputMessage);                            // ★ 执行（3.5 节）
```

之后按函数种类分流：**Consumer** → 若结果是 Publisher 且非组合则 `subscribe()` 消化掉，返回 202（DELETE 例外返回空）（134-140 行）；**Function/Supplier** → 结果若是 Flux 且非 SSE 模式，`onErrorContinue + collectList` 聚合为 Mono（151-156 行）——**HTTP 一次请求一响应的语义由框架聚合**；最终结果三类：Iterable → 聚合成 JSON 数组并置 `content-type: application/json`（163-168 行）；Message → 头提取到 ResponseEntity（`HeaderUtils.fromMessage` 过滤）+ payload 作 body（169-171 行）；裸值 → 直接作 body（172-174 行）。

`postProcessResult`（230-247 行）做最后一层规整：byte[] 结果转 UTF-8 字符串（Web 层要写文本），Message 里的 byte[] payload 保留壳。

## 5.5 函数式端点：`FunctionEndpointInitializer` 与自带 Netty 服务器

【源码证据】`web/function/FunctionEndpointInitializer.java`。这是 functional 路线（2.4 节）的 Web 配角，注册条件四连（89-97 行）：webflux 在 classpath + `ContextFunctionCatalogInitializer.enabled` + `spring.main.web-application-type=reactive`（默认即 reactive）+ `spring.functional.enabled=true`。

生效后它注册三样东西：`FunctionHttpProperties`、`FunctionEndpointFactory`（RouterFunction 工厂）、`RouterFunction` bean（124-131 行）；并把 `HttpWebHandlerAdapter` 注册为 `webHandler`（99-106 行）。**`ServerListener`**（133-187 行）在 `ContextRefreshedEvent` 后用独立线程启动一个裸 Reactor Netty（`HttpServer.create().bindUntilJavaShutdown(60s)`，159-163 行），启动成功把实际端口写进 `local.server.port` 属性（167-172 行），并打印 JVM uptime（173-180 行）——这是 Lambda 等场景"不引入 Boot Web 服务器自动配置也能服务 HTTP"的关键。

`FunctionEndpointFactory#functionEndpoints`（230-271 行）只挂两条路由：`POST /**`（body → processRequest，Consumer 输出类型为 null 时返回空 body）与 `GET /**`（Supplier 直接 `invokeFunction`；Function 从请求属性拿 ARGUMENT 作输入）。另外支持 `${function.handler}` 环境变量强制指定函数（204-209 行、214-221 行）——Serverless 平台无法传 `spring.cloud.function.definition` 时的开关。

## 5.6 WebFlux 版控制器差异

`web/flux/FunctionController.java` 与 MVC 版映射结构一致，三处不同：入参从 `WebRequest` 换成 `ServerWebExchange`；所有处理方法直接返回 `Mono<ResponseEntity<?>>`（请求体经 `exchange.getRequest().getBody()` 读）；multipart 用 `Part` 流而非 Servlet `MultipartFile`。HandlerMapping 逻辑同 MVC 版（`web/flux/FunctionHandlerMapping.java`，102 行，同样 order -5 与属性沉淀）。`ReactorAutoConfiguration`（mvc/flux 各一份，59-102 行）负责按栈注册这两组 bean。

## 5.7 Supplier/Consumer 的推拉两种玩法：`FunctionExporterAutoConfiguration`

白话：目录里的 Supplier 不仅可以"被 GET 轮询"，还可以**主动把产出推到远端 URL**；反过来也可以**从远端 URL 拉数据当输入**。这就是"函数导出"（export）。

【源码证据】`web/source/FunctionExporterAutoConfiguration.java`（注册于 web 模块 `AutoConfiguration.imports`；生效条件 `@ConditionalOnClass(WebClient.class)` + `SourceActiveCondition`）：

- **sink 模式**（60-66 行）：`spring.cloud.function.web.export.sink.url` 配置后，`SupplierExporter` bean 诞生——按 `export.sink.*` 属性（`ExporterProperties`）周期性调用目录里的 Supplier 并把结果 POST 到 url（"拉自己、推别人"，Serverless 场景当定时器用）；
- **source 模式**（69-78 行）：`spring.cloud.function.web.export.source.url` 配置后，`HttpSupplier`（拉远端 URL 作为数据源）被注册为 `FunctionRegistration` 进入目录——它与其他函数组合（`definition=remoteSource|yourFunction`）即可实现"从外部 API 拉数 → 处理"。

这两个 initializer 同时以 `ApplicationContextInitializer` 形式（`FunctionExporterInitializer`）支持 functional 路线。`spring.main.web-application-type=none` 时依然可用（WebClient 直连）。

## 5.8 本章小结

- HTTP 门面三套：MVC 注解式 / WebFlux 注解式 / functional RouterFunction，共享 `FunctionWebRequestProcessingHelper` 底座。
- 路径解析 = 前缀逐步试探 + GET 优先 Supplier + 默认定义兜底；函数与参数写入请求属性。
- HTTP 语义：GET→Supplier/Function，POST/PUT→Function/Consumer，DELETE→Consumer(204)，SSE 由 produces 协商；方法白名单可收口。
- 响应聚合（Flux→List→JSON）由框架完成；Message 输出的头经 `HeaderUtils` 白名单过滤后进 HTTP 头。
- functional 路线自带裸 Netty；`function.handler` 专供 Serverless 平台固定函数。
- Supplier 可"被 GET"，也可经 export 配置变成定时推送者或远程拉取者。

---

# 六、类型转换与 JSON：框架里最"厚"的部分

## 6.1 两个转换体系，先分清

| 体系 | 接口 | 用在哪 | 例 |
|---|---|---|---|
| 普通类型转换 | `ConversionService` | 非消息体转换：路径参数、header 值、简单类型 | `"123"` → `Integer` |
| 消息体转换 | `CompositeMessageConverter`（Spring messaging） | payload 转换：`Message<byte[]>` → POJO，POJO → `Message<byte[]>` | JSON → `Person` |

`ContextFunctionCatalogAutoConfiguration#functionCatalog`（2.3.1 节）把两者都收编：ConversionService 复用容器的并追加用户 `GenericConverter` bean；MessageConverter 侧组装出 `SmartCompositeMessageConverter`。

## 6.2 `SmartCompositeMessageConverter`：带短路和哨兵的组合转换器

【源码证据】`spring-cloud-function-context/.../config/SmartCompositeMessageConverter.java`。它继承 `CompositeMessageConverter` 但覆写了两处行为：

- **短路**：payload 已是目标类型实例（且非 byte[]、非 Collection）→ 直接返回原值（56-58 行）——"函数吃 String、进来就是 String"零开销；
- **失败续转**：单个转换器抛异常时记警告并尝试下一个（67-71 行），但先过 `failConversionIfNecessary`——由 `MessageConverterHelper` 决定"这个失败要不要直接判死刑"（Helper 由 Supplier 惰性取自容器，164-165 行，典型实现是 Stream 的 header 增强）。

带 `conversionHint` 的重载（79-120 行）处理 **Iterable payload 逐元素转换**：从 hint（函数 Type）里挖集合元素泛型，逐元素喂给各转换器（`SmartMessageConverter` 走带 hint 的重载）；`KafkaNull` 元素直接产出 null（96-98 行）。

栈内顺序即优先级：**用户转换器 → `JsonMessageConverter` → `ByteArrayMessageConverter` → `StringMessageConverter`**（2.3.1 节）。`JsonMessageConverter` 是框架自带的"JSON 消息转换器"，内部直接委托 `JsonMapper`。

## 6.3 `JsonMapper`：Jackson 3 优先的薄封装

见 2.3.3 节（装配与定制）。要点回顾：`tools.jackson.databind.ObjectMapper`（Jackson 3）优先、Gson 兜底、`preferred-json-mapper` 强制指定；`JacksonMapper` 提供 `configureObjectMapper(Consumer)` 供适配器追加配置（AWS 适配器用它开 `ACCEPT_CASE_INSENSITIVE_PROPERTIES`，7.2 节）；`isJsonString`/`isJsonContentType` 两个静态判定被执行引擎直接调用，决定 JSON 路径的启用与否。

## 6.4 输入转换决策树（汇总 3.5.2 节，排错用）

```
输入到达 convertInputIfNecessary
├─ 类型是 Void？ → 断言输入必须为 null
├─ 是 Publisher？ → 逐元素递归
├─ 是 Tuple 多参数？ → SpEL 拆 T1/T2… 逐个递归
├─ skipInputConversion？ → 包 Message/OriginalMessageHolder 原样放行
├─ 是 Message？
│   ├─ payload 是 KafkaNull？ → 原样放行
│   ├─ CloudEvents 输入？ → preProcessInput 头规范化
│   ├─ messageConverter.fromMessage(msg, rawType[, itemType]) 成功？
│   │     └─ 包成 OriginalMessageHolder{转换值, 原始Message}
│   ├─ 失败 → contentType 是 JSON（或 payload 像 JSON）？
│   │     ├─ 是 → jsonMapper.fromJson，失败按 failOnJsonError 决定抛/降级
│   │     └─ 否 → ConversionService.canConvert？ → 转换
│   └─ 全失败 → 用原样输入（并打 debug 日志）
└─ 非 Message → JSON 判定 → JsonMapper / ConversionService 兜底
最后：输入类型是 Message 系且转换结果不是 Message → 重新包成 Message
```

两处细节：JSON 判定有"严格/宽松"两档（`failOnJsonError`：contentType 明确是 JSON 时转换失败必须抛错，payload 看起来像 JSON 时失败可降级到 ConversionService，1227-1231 行）；`isJsonContentType` 同时嗅探 `contentType`、`Content-Type`、`content-type` 三种头拼写（984-993 行）。

## 6.5 输出转换决策树（汇总 3.6 节）

期望输出类型（`expectedOutputContentType`）有**两个来源**：`lookup(definition, "application/json")` 的显式声明（3.2 节），或输出 Message 上的 `expected-content-type` 头（`FunctionProperties.EXPECT_CONTENT_TYPE_HEADER`，`FunctionProperties.java:51`，`convertOutputMessageIfNecessary` 1546-1548 行）。

```
输出到达 convertOutputIfNecessary
├─ skipOutputConversion？ → 原样返回
├─ 是 Publisher？ → 逐元素递归
├─ 是 Message？
│   ├─ payload 是 byte[] 且无期望类型？ → 原样返回（不折腾字节）
│   ├─ isExtractPayload？ → 抽 payload 丢壳（规则见 3.6 节）
├─ 是 PassThruFunction？ → 强制按期望类型转
├─ 无期望类型？ → 返回（POJO 函数输出补 Message 壳，GH-1307）
└─ 有期望类型？
    ├─ Tuple 多输出？ → 逐分量转换
    ├─ 是 Message？ → 按逗号拆期望类型逐个尝试，第一个成功的赢
    ├─ 是 Collection/数组且输出是 Message 系？ → 逐元素转换
    └─ 裸值 → messageConverter.toMessage(payload, {contentType: 期望[0]})，默认 application/json
```

## 6.6 本章小结

- 转换有两套体系：ConversionService（标量）与 SmartCompositeMessageConverter（消息体），后者 = 用户转换器（优先）+ JSON/字节/字符串三个保底。
- 短路（已是目标类型）与续转（单个失败继续下一个）是组合转换器的两个非默认行为，都写在这个子类里。
- 输入/输出决策树是排错的第一工具：绝大多数"我的函数没被调用/参数类型不对"问题都能在其中定位。
- `OriginalMessageHolder` 承载"转换值 + 原始 Message"二元组，是 header 传播机制的载体。
- JSON 层 5.0 起为 Jackson 3（tools.jackson），API 已定型的部分（JsonMapper 接口）不变。

---

# 七、云函数适配器（adapters）：换宿主不换代码

## 7.1 适配器全景与生死簿

适配器的共同使命：**把平台事件变成 `Message`，把函数输出变回平台格式**。截至 5.0.4：

| 模块 | 引入 | 状态 | 入口类 |
|---|---|---|---|
| adapter-aws | 2017-05-23（1.0 前开发期） | 现役 | `FunctionInvoker`、`CustomRuntimeEventLoop` |
| adapter-azure | 2017-11-26 | 现役 | `AzureFunctionInstanceInjector` |
| adapter-azure-web | — | 现役 | `AzureWebProxyInvoker` |
| adapter-gcp | 2020-02-26（3.1 开发期） | 现役 | `FunctionInvoker`、`GcfJarLauncher` |
| grpc | 2021-10-18（3.2 开发期） | 现役 | `FunctionsGrpc`（+ cloudevent 扩展模块） |
| serverless-web | 2023-03-06（4.1 开发期） | 现役 | `ServerlessMVC`、`ServerlessWebApplication` |
| adapter-openwhisk | 2017-06-07 | **已删**（2022-08-16 "Cleanup"，v4 期） | — |
| function-deployer | 2016-12-16 | **已删**（2026-06-19 "Remove deployer and rsocket"，落 5.0.4） | — |
| adapter-rsocket | — | **已删**（同上） | — |

两条历史线索：OpenWhisk 平台 2022 年关停导致适配器退场；deployer（用户打包一个含函数的 JAR，由宿主 JAR 动态加载运行）在"把应用打包成镜像"成为共识后失去意义——它在代码里留下的唯一痕迹是 `ContextFunctionCatalogAutoConfiguration` 上的 `@AutoConfigureAfter(name = "...FunctionDeployerConfiguration")`（104 行，字符串引用，类不存在也不炸）。

## 7.2 AWS 主线：`FunctionInvoker`（RequestStreamHandler 路线）

白话：Lambda 平台要求你实现 `com.amazonaws.services.lambda.runtime.RequestStreamHandler`（或用其默认运行时约定）。`FunctionInvoker` 就是那个"被平台调用的 handler"，它内部跑一个**无 Web 的 Spring Boot 应用**，从目录取函数执行。

【源码证据】`spring-cloud-function-adapters/spring-cloud-function-adapter-aws/.../aws/FunctionInvoker.java`（@since 3.1）：

```java
public class FunctionInvoker implements RequestStreamHandler {
    public FunctionInvoker(String functionDefinition) {          // 68 行
        String lateInitialization = System.getenv("FUNCTION_INVOKER_LATE_INITIALIZATION");
        if (!StringUtils.hasText(lateInitialization) || !Boolean.parseBoolean(lateInitialization)) {
            this.start();                                        // 默认：构造即启动整个 Spring 容器
        } else {
            logger.info("Spring Application Context will be initialized on first request");  // 冷启动优化开关
        }
    }
    public void handleRequest(InputStream input, OutputStream output, Context context) {  // 85 行
        if (!this.started) this.start();
        Message requestMessage = AWSLambdaUtils.generateMessage(input, this.function.getInputType(),
                this.function.isSupplier(), jsonMapper, context);        // 平台事件 → Message
        Object response = this.function.apply(requestMessage);           // ★ 目录里的函数执行
        byte[] responseBytes = AWSLambdaUtils.generateOutputFromObject(requestMessage, response,
                this.jsonMapper, function.getOutputType());              // 输出 → 平台格式
        StreamUtils.copy(responseBytes, output);
    }
}
```

`start()`（101-163 行）的四个要点：

1. **`FunctionClassUtils.getStartClass()`** 反射找出启动类（`MAIN_CLASS` 环境变量 / jar manifest），再以 `--spring.main.web-application-type=none` 与 `--spring.cloud.function.web.export.enabled=false` 两个硬编码属性启动（103 行）——Lambda 里没有也绝不该有 Web 服务器和导出器；
2. 启动源固定追加 **`AWSCompanionAutoConfiguration`**（105-106 行）——AWS 专属消息转换器（`AWSTypesMessageConverter`，处理 `APIGatewayV2HTTPEvent`/`SNSEvent` 等 AWS 事件类型与 POJO 互转）与 `LambdaDestinationResolver` 的注册处；
3. **找不到函数时回落 `RoutingFunction`**（131-151 行）：日志明确提示"请配 `spring.cloud.function.definition`，或通过 API Gateway 传 header 路由"——路由（第四章）在 Lambda 上同样工作；
4. 输出类型是 Publisher 时 `setSkipOutputConversion(true)`（153-155 行）——Lambda 不接受流式输出，转换留给 AWSLambdaUtils 的序列化。

`FUNCTION_INVOKER_LATE_INITIALIZATION=true`（70-77 行）是冷启动的实用开关：容器推迟到**第一次调用**才启动——交换"首请求变慢"为"初始化成本计入执行时长"（对按 GB-s 计费的初始化阶段有利有弊，需按平台计费规则权衡）。

## 7.3 `AWSLambdaUtils`：事件 ↔ Message 的翻译官

【源码证据】`adapter-aws/.../aws/AWSLambdaUtils.java`。三个关键方法：

- `generateMessage(byte[] payload, Type inputType, boolean isSupplier, JsonMapper jsonMapper, Context context)`（101-148 行）：JSON 探测 payload 是否为 API Gateway 事件（命中则打上 `aws-api-gateway=true` 头），body/headers/isBase64Encoded 解析为 `Message<byte[]>`；Lambda `Context` 对象整个塞进 `aws-context` 头（71 行），`Lambda-Runtime-*` 头透传——用户函数里 `@Header("aws-context")` 就能拿到；
- `generateOutput(...)`（184-236 行）：请求头带 `aws-api-gateway` 时输出 API Gateway 代理格式（`{statusCode, headers, body}`），否则输出裸 payload 序列化。

## 7.4 Custom Runtime：常驻进程的事件循环

白话：前面两条路线（RequestStreamHandler / 默认运行时）每次冷启动都要 JVM 起床。AWS 提供的 **Custom Runtime**（`provided.al2` 等运行时）允许你交付任意可执行文件，平台通过 **Lambda Runtime API**（本地 HTTP 端点）投递事件——于是 Java 可以"启动一次，常驻内存，循环拉取事件"。`CustomRuntimeEventLoop` 就是这个循环。

【源码证据】`adapter-aws/.../aws/CustomRuntimeEventLoop.java`（`SmartLifecycle` 实现）：

```java
static final String LAMBDA_VERSION_DATE = "2018-06-01";                       // 72 行，Runtime API 版本
private static final String LAMBDA_RUNTIME_URL_TEMPLATE   = "http://{0}/{1}/runtime/invocation/next";       // 74
private static final String LAMBDA_INVOCATION_URL_TEMPLATE= "http://{0}/{1}/runtime/invocation/{2}/response";// 75
private static final String LAMBDA_ERROR_URL_TEMPLATE     = "http://{0}/{1}/runtime/invocation/{2}/error";  // 73
...
while (this.isRunning()) {                                                    // 139 行
    ResponseEntity<String> response = this.pollForData(rest, requestEntity);  // GET /runtime/invocation/next（阻塞拉取）
    String requestId = response.getHeaders().getFirst("Lambda-Runtime-Aws-Request-Id");
    FunctionInvocationWrapper function = locateFunction(environment, functionCatalog, response.getHeaders());
    Message<?> requestMessage = AWSLambdaUtils.generateMessage(...);          // 事件 → Message（7.3 节）
    Object functionResponse = function.apply(requestMessage);                 // ★ 同一个执行引擎
    byte[] responseBytes = AWSLambdaUtils.generateOutputFromObject(...);
    rest.exchange(RequestEntity.post(URI.create(invocationUrl)).body(responseBytes), ...);  // POST /invocation/{id}/response
}
```

异常走 `propagateAwsError` POST 到 `/invocation/{id}/error`（288 行）；`Lambda-Runtime-Trace-Id` 头与环境变量 `_X_AMZN_TRACE_ID` 的取舍逻辑保证 X-Ray trace 传播（182-187 行）。启动开关 `CustomRuntimeInitializer`（`initialize`，37-42 行）：读 `_HANDLER` 环境变量，若 handler 指向的类**不是** `FunctionInvoker`（`isCustomRuntime`，44-57 行），判定为 custom runtime 模式 → 注册事件循环 bean。**这条路线配合 GraalVM Native Image（8.5 节 AOT）是官方主推的冷启动方案**：native 二进制 + Custom Runtime = 毫秒级冷启动 + 常驻执行。

## 7.5 serverless-web：让"任意 Spring Boot MVC 应用"直接上 Lambda

白话：前面所有路线都要求"函数式写法"。但存量应用是 `@RestController` + `DispatcherServlet` 写的，重写成函数不现实。serverless-web 的回答：**把 Servlet 容器"伪装"出来**——应用照常启动，Tomcat/Jetty 被顶掉，Lambda 事件被翻译成 Servlet 请求喂给 DispatcherServlet。

【源码证据·装配端】`serverless-web/.../ServerlessAutoConfiguration.java`。它用 `@AutoConfiguration(beforeName = {...})` **抢在** Boot 4 的三家 Servlet 容器自动配置之前生效（Boot 4 模块化后的类名：`org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration` 等）：

```java
@Bean
@ConditionalOnMissingBean
public ServletWebServerFactory servletWebServerFactory() {
    return new ServerlessServletWebServerFactory();   // 顶掉 Tomcat/Jetty/Undertow
}
```

这个工厂的 `getWebServer` 返回一个**什么都不做的 WebServer**（start/stop 全 NOP），但在 `afterPropertiesSet` 里完成了真正的初始化（96-112 行）：创建 `ServerlessServletContext` 注入 `ServletWebServerApplicationContext`，**手动调用 `dispatcher.init(new ProxyServletConfig(...))` 初始化 DispatcherServlet**，再把所有 `ServletContextInitializer`（Filter、Listener 注册）逐个 `onStartup`——Servlet 规范里"容器启动"那一步被完整手工复刻。

【源码证据·调用端】`ServerlessMVC.java`（javadoc 60-70 行）："主入口……用 `service(HttpServletRequest, HttpServletResponse)` 发送请求并接收响应"：

```java
ServerlessMVC mvc = ServerlessMVC.INSTANCE(PetStoreSpringAppConfig.class);  // 后台线程启动应用（默认 20s 超时，INIT_TIMEOUT 可调）
mvc.service(request, response);                                             // 每次事件：构造伪 Servlet 请求/响应，过 DispatcherServlet
```

配套的 `ServerlessHttpServletRequest`（1024 行！）/`ServerlessHttpServletResponse`/`ServerlessHttpSession`/`ServerlessAsyncContext` 构成伪 Servlet 环境；`ServerlessFilterRegistration`/`ServerlessServletRegistration` 把 Filter/Servlet 注册接进这套环境；`ServerlessWebApplication`（411 行）是 `SpringApplication` 的子类——强制 `WebApplicationType.SERVLET` 却**不启动任何服务器**，run 流程被复刻裁剪（环境准备、AOT initializer、listeners 全保留）。模块 pom 排除 `spring-boot-starter-tomcat`/`spring-boot-web-server`（1.4 节），确保 classpath 上没有真容器。

这套机制（@since 4.x，javadoc 自述）与 AWS 官方 `aws-serverless-java-container` 解决同一问题——区别在于它是 Spring 全家桶自建、与 FunctionInvoker/Custom Runtime 同属一套工具箱，samples 里两个风格的样例并存（`function-sample-aws-serverless-web-native` 甚至演示了配合 AWS 官方容器的 GraalVM native 方案）。

## 7.6 Azure / GCP / gRPC 速览

- **adapter-azure**：只剩两个类——`AzureFunctionInstanceInjector`（把 Azure 注入的服务实例塞进函数 bean）与 `AzureFunctionUtil`。Azure 现代集成走 **adapter-azure-web 的 `AzureWebProxyInvoker`**：用户在 `@FunctionName` 方法里调用代理器，由它内部 lookup/apply 目录函数——Azure 承担事件解析，Spring 承担函数执行；
- **adapter-gcp**：`FunctionInvoker`（GCF HTTP/事件请求 → Message → apply）+ `GcfJarLauncher` + `layout` 包（`GcfJarLayout`/`GcfJarLayoutFactory`，把函数 JAR 按 GCF Java 运行时要求的目录布局重打包）；
- **grpc**：完整的 gRPC 服务端/客户端桥（`GrpcAutoConfiguration`/`GrpcFunctionAutoConfiguration` 装配、`GrpcClientTemplate` 走 gRPC 调远端函数、`AbstractGrpcMessageConverter`/`GrpcMessageConverter` 做 gRPC 请求 ↔ Message 翻译）；`grpc-cloudevent-ext` 提供 CloudEvents 的 gRPC protobuf 扩展。

## 7.7 CloudEvents：一条钩子接口的三方共赢

CloudEvents（CNCF 事件规范）支持横跨三层：core 的 `FunctionInvocationHelper`（1.3 节）定义三个钩子——`preProcessInput`（输入头规范化）、`postProcessResult`（输出回填 CloudEvents 属性）、`isRetainOutputAsMessage`（保留 Message 壳信号，3.6 节）；context 的 `CloudEventsFunctionInvocationHelper` 实现（检测到 `io.cloudevents` 在 classpath 时由 `CloudEventsFunctionExtensionConfiguration` 装配），并在 `functionCatalog` 装配时把 `SmartCompositeMessageConverter` 反向注入给它（166-168 行）；`CloudEventsMessageBuilder`/`CloudEventMessageUtils` 提供构造与判定工具。执行引擎里 4 处调用点（3.5.2/3.5.3 节已标注）保证：**输入是 CloudEvent → 输出自动是合法 CloudEvent**，用户函数对此无感。

## 7.8 本章小结

- 适配器 = "平台事件 → Message → `function.apply` → 平台格式" 的翻译器，AWS 是最完整范本。
- AWS 三条路线：RequestStreamHandler（`FunctionInvoker`）、Custom Runtime 事件循环（常驻进程）、serverless-web（存量 MVC 应用）。冷启动三板斧：延迟初始化、Custom Runtime、Native Image。
- serverless-web 的本质是"手工复刻 Servlet 容器启动协议"：抢位 ServletWebServerFactory + 手动 init DispatcherServlet + 伪 Servlet 对象族。
- Azure/GCP/gRPC 各自换壳，CloudEvents 靠一条 3 方法钩子接口贯穿全部宿主。

---

# 八、周边生态与可观测

## 8.1 actuator：`/actuator/functions`

【源码证据】`spring-cloud-function-context/.../actuator/FunctionsEndpoint.java`（@Endpoint(id="functions")，@since 3.2）。`listAll()` 遍历 `functionCatalog.getNames(null)`，逐个 lookup 并输出 `{type: FUNCTION/CONSUMER/SUPPLIER, input-type, output-type}`（类型取 `getItemType` 剥掉 Message/Flux 壳后的原始类小写名）。排查"容器里到底有哪些函数、默认函数是谁"时先看这里。装配在 `FunctionsEndpointAutoConfiguration`（context 模块 imports 实证）。

## 8.2 可观测：Observation 环绕在 `apply` 处

【源码证据】`context/.../observability/`（`ObservationAutoConfiguration`、`ObservationFunctionAroundWrapper`、`FunctionObservation`、`DefaultFunctionObservationConvention`）。`ObservationFunctionAroundWrapper` 实现 `FunctionAroundWrapper`——正是 3.3 节预留的环绕挂点：`SimpleFunctionRegistry#apply` 检测到容器里有该 bean，就用它包住每次函数调用，产生 Micrometer `Observation`（`FunctionObservation` 定义 high-cardinality key：函数定义、注册名等；`FunctionObservationConvention` 可定制命名）。classpath 有 Micrometer 时自动生效（context imports 实证），与 Boot 3+ 的 Observation 体系（[Micrometer Tracing 文档](Micrometer%20Tracing.md)）无缝衔接。组合函数只产生**一次**观测（3.7 节：内部环节走 `doApply` 绕开环绕）。

## 8.3 Spring Integration：`FunctionFlowBuilder` DSL

【源码证据】`spring-cloud-function-integration/.../dsl/`（2023-05-08 引入，4.1 期）。`FunctionFlowDefinition extends IntegrationFlowExtension`，在 IntegrationFlow DSL 上扩展三个方法：`apply("fn")` / `apply(Function<Message,?>)`（把目录函数或 lambda 插入流）、`accept(consumerDefinition)`（消费端收尾）。`FunctionFlowAutoConfiguration` 自动装配。定位：Integration 用户可以直接引用函数目录里的函数搭流，不必重复包装。

## 8.4 Kotlin 支持现状

5.0 的格局：**运行时支持在 context 模块**（`KotlinLambdaToFunctionAutoConfiguration` 及其 `KotlinFunctionWrapper`，context imports 实证），`spring-cloud-function-kotlin` 模块 main 无源码、`spring.factories` 为空，仅剩测试（`ContextFunctionCatalogAutoConfigurationKotlinSuspendTests` 验证 suspend 函数）。框架对 Kotlin 的三处照顾：lambda 由 wrapper 包装注册（3.8 节第 3 路）；Jackson Kotlin module 自动尝试挂载（2.3.3 节）；`KotlinUtils.isKotlinType` 在 lookup 前置检查（`BeanFactoryAwareFunctionRegistry` 135 行——Kotlin bean 可能有自己的命名语义，跳过单函数回退的规整）。

## 8.5 AOT 与 GraalVM Native：两个 `BeanFactoryInitializationAotProcessor`

【源码证据】`context/.../context/FunctionTypeProcessor.java`（45-119 行）。Native 镜像里反射默认全关，而框架大量用反射调函数——所以 AOT 阶段必须提前注册提示。`FunctionTypeProcessor` 扫描所有 bean：是 `Function/Consumer/Supplier` 的，解析其函数类型的**全部泛型实参**（`registerAllGenericTypes` 递归，67-79 行），为每个非 `java.*/javax.*` 类型注册反射 hints（fields/declared methods/declared constructors，102-105 行）。这保证"你的函数吃的 POJO、吐的 POJO"在 Native 里能反序列化。serverless-web 模块还有一个同风格的 `AWSTypesProcessor`，为 `HttpEntity`/`ResponseEntity` 等框架类型补注册。function-context 模块还带 `META-INF/spring/aot.factories`（实证存在）。

## 8.6 与 Spring Cloud Stream 的关系：最大用户，也是最大债主

Spring Cloud Stream（见 [Spring Cloud Stream.md](Spring%20Cloud%20Stream.md)）**完全构建在本目录之上**：它的 binder 把 Kafka/Rabbit 分区转成 `Message` 流，再通过 `FunctionCatalog.lookup` 取函数执行。`SimpleFunctionRegistry` 里多处代码明说是为 Stream 而留：`enhancer` 字段与 `setSkipInputConversionCallback`（478-481、585-587 行，注释："This is primarily to support Stream's ability to access un-converted payload ... It is not intended to remain here and will be removed"）；`PassThruFunction` 特判（1316 行，注释 scst-2303）；`filterOutHeaders`（1262 行，注释 "temporary fix for ...spring-cloud-stream/issues/2178"）。反过来 Stream 又依赖这些"临时"口子——这是本章下一节要讨论的耦合代价。理解这个关系也解释了一个现象：**Function 的 HTTP 语义、转换行为变更，经常同时是 Stream 的行为变更**。

## 8.7 本章小结

- `/actuator/functions` 是目录的自省窗口；Observation 经 `FunctionAroundWrapper` 织入且组合函数只观测一次。
- Integration DSL 与 Kotlin 支持都已并入主干（integration 独立模块、Kotlin 并入 context）。
- AOT 两个 processor 保证 Native 下泛型反射可用——云函数 + Native 是官方冷启动答案的组成部分。
- Stream 是目录的最大消费者：核心类里留有带"will be removed"注释的专用钩子，读源码遇到 Stream 专属分支不应意外。

---

# 九、设计评述与实战指南

## 9.1 这套设计的"得"

1. **一个抽象吃到三种宿主**。`FunctionCatalog + FunctionInvocationWrapper` 十年未换骨（3.x 定型至今），HTTP/Lambda/Stream 三端零成本迁移。对比各云厂商 SDK 各自定义的 handler 接口，这种"宿主无关"是有真实迁移价值的。
2. **懒发现让启动成本与函数数量解耦**。BeanFactory 即仓库、lookup 即发现（3.8 节），千个函数与十个函数的启动时间同量级——这对 Lambda 冷启动是实打实的收益。
3. **包装函数作为万能扩展原语**。路由、头部增强、观测，全部是"往函数链里插包装"，没有任何一个扩展点需要修改执行引擎。第四/八章三处实现共用 3.3 节一个挂点。
4. **工程上敢于做减法**。旧世界（4.0）、openwhisk（4.0）、deployer/rsocket（5.0.4）三次成建制删除，核心始终在变小（core=1 文件）。

## 9.2 这套设计的"失"

1. **类型转换的多层兜底复杂度**。第六章两棵决策树背后是"Message 转换失败 → JSON 探测 → ConversionService → 原样放行"的多级降级——灵活但难排查；`isJsonString`/`isJsonContentType` 这类"启发式嗅探"是行为不确定性的主要来源。
2. **领域渗透**。context 里出现了 `KafkaNull` 硬编码（`SimpleFunctionRegistry` 1056、1217 行与 `BeanFactoryAwareFunctionRegistry` 242 行三处按类名字符串特判）、`lambda*` 头前缀（`SimpleFunctionRegistry` 1378-1385 行为 AWS 留的壳保留信号）、多处 Stream 专属钩子（8.6 节）——"宿主无关"的抽象被具体生态凿了不少透孔。
3. **状态位散布**。`FunctionInvocationWrapper` 上十几个布尔/数组字段（skipInputConversion/skipOutputConversion/isPojoFunction/wrappedBiConsumer/expectedOutputContentType/propagateInputHeaders...），由不同宿主在不同时机设置——可执行对象本身成了"配置的便签纸"，读代码时需要随时记住"谁在什么时候改了它"。
4. **文档与实现的时滞**。`FunctionRegistration` 里留着已无方法体的 javadoc（177-184 行）；部分注释自陈"temporary fix"（1262 行）却存活多个大版本。

## 9.3 什么时候用 / 什么时候不用

**适合**：函数逻辑清晰（输入→输出）、需要在 HTTP/消息/Serverless 间灵活迁移；Serverless 场景想要 Spring 生态（DI、观测、CloudEvents）而不要重复写各平台胶水；存量消息处理代码想统一到函数模型（Stream 的必经之路）。

**不适合**：复杂 Web 应用（Controller/安全/视图渲染一应俱全——用普通 Boot + 必要时 serverless-web）；对转换行为要求完全可预测的场景（自己写 Controller/Handler 反而透明）；仅运行一个固定 HTTP 服务的单体（引入函数目录层纯属绕路）。

## 9.4 属性速查表（全部经源码/元数据实证）

| 属性 | 作用 | 源码位置 |
|---|---|---|
| `spring.cloud.function.definition` | 默认函数/组合定义（`a\|b`） | `FunctionProperties.java:61` |
| `spring.cloud.function.routing-expression` | SpEL 路由表达式 | `FunctionProperties.java:56` |
| `spring.cloud.function.routing.enabled` | 关闭路由 | 元数据实证 |
| `spring.cloud.function.single-function-fallback-enabled` | 唯一函数回退（默认 true） | `FunctionProperties.java:204` |
| `spring.cloud.function.ineligible-definitions` | 目录负面清单追加 | `FunctionProperties.java:196` |
| `spring.cloud.function.configuration.<fn>.input/output-header-mapping-expression.<header>` | 头部增强 SpEL | `SimpleFunctionRegistry.java:374-421` |
| `spring.cloud.function.configuration.<fn>.copy-input-headers` | 输入头拷贝到输出 | `FunctionProperties.java:218` |
| `spring.cloud.function.preferred-json-mapper` | jackson/gson | `ContextFunctionCatalogAutoConfiguration.java:111` |
| `spring.cloud.function.registry.cache-size` | 组合包装 LRU（默认 1000） | `SimpleFunctionRegistry.java:119` |
| `spring.cloud.function.scan.packages` / `scan.enabled` | 裸函数包扫描（默认 `functions`/true） | `ContextFunctionCatalogAutoConfiguration.java:204` |
| `spring.cloud.function.web.path` | HTTP 端点统一前缀 | `mvc/FunctionHandlerMapping.java:55` |
| `spring.cloud.function.http.get/post/put/delete` | HTTP 方法白名单（分号分隔） | `FunctionWebRequestProcessingHelper.java:75` |
| `spring.cloud.function.web.export.sink.url` / `export.source.url` | Supplier 推送 / 拉取 | `FunctionExporterAutoConfiguration.java:59,69` |
| `spring.functional.enabled` | functional 极简路线 | `FunctionalSpringApplication.java:53` |
| `function.handler`（环境变量） | 强制指定函数（Serverless） | `FunctionEndpointInitializer.java:204` |
| `FUNCTION_INVOKER_LATE_INITIALIZATION`（环境变量） | Lambda 容器延迟启动 | `FunctionInvoker.java:70` |

## 9.5 常见坑清单

1. **多函数没配 definition**：默认函数推断告警（"Multiple functional beans were found..."，`BeanFactoryAwareFunctionRegistry.java:147`）；HTTP 端点全部 404 的第一排查点。
2. **prototype 函数不进包装缓存**（3.3 节）：`lookup` 每次产生新包装；若依赖在包装上设置的 skip 标志，注意其不生效。
3. **组合不支持多参数函数**（3.7 节）：`Tuple2` 函数不能出现在 `|` 链里，会抛 `UnsupportedOperationException`。
4. **Consumer 后面不能再接函数**："Can NOT compose anything with Consumer"（777 行）。
5. **流式输出上 Lambda**：`FunctionInvoker` 对 Publisher 输出直接 `skipOutputConversion`（7.2 节）——Lambda 没有流式响应，需要自行 collect。
6. **转换失败静默放行**：输入转换失败时引擎"用原样输入"继续调用（6.4 节）——函数可能收到 `Message` 而你期待 POJO；加日志 `org.springframework.cloud.function.context.catalog=DEBUG` 可见全过程。
7. **routing 死循环**：路由结果又是 `functionRouter` 会抛异常（`RoutingFunction.java:187`）——检查路由表达式是否可能解析成 `functionRouter`。
8. **`/actuator/functions` 出现意外条目**：metrics 等 Supplier 型内部 bean 已被负面清单挡住（2.3.5 节），但用户自己注册的 Supplier 型基础设施 bean 需要自行追加 `ineligible-definitions`。

---

# 附录

## 附录 A 版本考古 tag 列表（git 实证）

```
v1.0.0.RELEASE 2018-06-15  v2.0.0.RELEASE 2019-01-08  v2.1.0.RELEASE 2019-05-07
v3.0.0.RELEASE 2019-11-22  v3.1.0        2020-12-21  v3.2.0        2021-12-01
v4.0.0        2022-12-15   v4.1.0        2023-12-06  v4.2.0        2024-12-02
v5.0.0        2025-11-24   v5.0.1        2026-01-28  v5.0.2        2026-05-07
v5.0.3        2026-06-11   v5.0.4        2026-08-20（本文基准）
```

关键提交考古（`git log` 实证）：

| 日期 | 提交 | 意义 |
|---|---|---|
| 2016-09-21 | bdd39b74f `initial commit` | 项目诞生 |
| 2019-05-03 | GH-238 `Added initial support for RoutingFunction` | 路由诞生（2.1 期） |
| 2019-07-17 | `Renamed new registry implementation to BeanFactoryAwareFunctionRegistry` | 3.0 换心 |
| 2020-04-16 | `Extract common code from BeanFactoryAwareFunctionRegistry` | SimpleFunctionRegistry 分层（3.1 期） |
| 2022-08-16 | e2bdc440d `Cleanup` | openwhisk 适配器删除（4.0 期） |
| 2023-03-06 | serverless-web 首次提交 | 存量 Web 应用上 Lambda（4.1 期） |
| 2023-05-08 | integration 模块首次提交 | Integration DSL（4.1 期） |
| 2026-06-19 | 32444f176 `Remove deployer and rsocket` | 最后一次成建制删除（5.0.4） |

## 附录 B 源码文件地图（v5.0.4，相对仓库根）

```
spring-cloud-function-core/
  core/FunctionInvocationHelper.java                 钩子契约（pre/postProcess、isRetainOutputAsMessage）
spring-cloud-function-context/
  context/FunctionCatalog.java                       目录接口（lookup/getNames/size）
  context/FunctionRegistry.java                      注册接口
  context/FunctionRegistration.java                  函数配方（names/type/target/userFunction）
  context/FunctionProperties.java                    全局属性（definition/routing/configuration/负面清单）
  context/FunctionTypeProcessor.java                 AOT 反射提示
  context/FunctionalSpringApplication.java           functional 启动入口
  context/MessageRoutingCallback.java                路由回调接口
  context/PollableBean.java                          @PollableBean（Supplier 轮询语义）
  context/catalog/SimpleFunctionRegistry.java        ★ 执行引擎（1684 行，FunctionInvocationWrapper 内部类）
  context/catalog/BeanFactoryAwareFunctionRegistry.java  懒发现层（315 行）
  context/catalog/FunctionTypeUtils.java             类型工具箱（674 行）
  context/catalog/FunctionAroundWrapper.java         环绕挂点接口
  context/config/ContextFunctionCatalogAutoConfiguration.java  总装车间
  context/config/ContextFunctionCatalogInitializer.java        functional 路线
  context/config/RoutingFunction.java                路由（266 行）
  context/config/SmartCompositeMessageConverter.java 组合转换器
  context/config/JsonMessageConverter.java           JSON 消息转换器
  context/config/KotlinLambdaToFunctionAutoConfiguration.java  Kotlin 运行时支持
  context/actuator/FunctionsEndpoint.java            /actuator/functions
  context/observability/ObservationFunctionAroundWrapper.java  观测织入
  context/cloudevent/CloudEventMessageBuilder.java 等  CloudEvents 工具
spring-cloud-function-web/
  web/mvc/FunctionHandlerMapping.java + FunctionController.java    MVC 门面
  web/flux/FunctionHandlerMapping.java + FunctionController.java   WebFlux 门面
  web/function/FunctionEndpointInitializer.java      函数式端点 + 自带 Netty
  web/util/FunctionWebRequestProcessingHelper.java   ★ 路径解析与请求处理
  web/util/HeaderUtils.java                          头过滤（ignored-headers/request-only-headers）
  web/source/FunctionExporterAutoConfiguration.java  Supplier 推拉
spring-cloud-function-adapters/
  adapter-aws/.../FunctionInvoker.java               RequestStreamHandler 入口
  adapter-aws/.../AWSLambdaUtils.java                事件↔Message 翻译
  adapter-aws/.../CustomRuntimeEventLoop.java        Runtime API 事件循环
  adapter-aws/.../CustomRuntimeInitializer.java      custom runtime 判定
  serverless-web/.../ServerlessAutoConfiguration.java          伪容器装配
  serverless-web/.../ServerlessMVC.java              调用端入口
  serverless-web/.../ServerlessHttpServletRequest.java（1024 行）等  伪 Servlet 对象族
  adapter-azure(-web)/ adapter-gcp/ grpc/            其余平台
```


