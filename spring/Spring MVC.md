# Spring MVC 深度源码解析（Servlet 规范、请求全链路与扩展点全景）

> **本文基于的源码**：
> - **Spring Framework 7.0.8**（tag `v7.0.8`，2026-06-08 发布，仓库 `D:\code\3rd\spring-framework`）。所有【源码证据】的文件路径与行号均为对该 tag 实际读取所得（与《Spring Framework.md》所用的 7.1.0-SNAPSHOT 主分支不同，两者行号不通用）。
> - **Spring Boot 4.0.8**（tag `v4.0.8`，2026-08-11 发布，仓库 `D:\code\3rd\spring-boot`）——负责"容器装配"一侧的证据。
> - **Spring Security 7.0.7**（仓库 `D:\code\3rd\spring-security`）——负责"Filter 链与用户上下文"一侧的证据。
>
> **运行基线（官方口径 + 源码实证）**：Java 17 起步、官方支持至 **JDK 25**；**Jakarta EE 11 API 级别，即 Servlet 6.1**（`framework-platform/framework-platform.gradle:75` 声明 `jakarta.servlet:jakarta.servlet-api:6.1.0`），对应容器为 **Tomcat 11**。
>
> **阅读约定**：与《Spring Framework.md》相同——每章"先白话、后源码"，先用人话讲清"这是什么、为什么需要它"，再给出证据链（`模块/src/main/java/.../类名.java` + 方法名 + 行号 + 代码片段）。涉及 Tomcat 容器内部（Connector、Pipeline/Valve、ApplicationFilterChain 等）的描述属于 Tomcat 实现细节（非本文源码仓库），文中会明确标注。Servlet 规范历史部分的结论均经官方发布页与 JCP 记录核对。

## 如何读这份文档

- **如果你只想搞清楚"一个 HTTP 请求是怎么走到我的 Controller 的"**：直接读第四章（一次请求的一生），4.0 节有一张全景图，4.3 节把 `doDispatch` 逐段拆开。
- **如果你关心"我该在哪个节点写代码来扩展框架"**（链路追踪、登录态、审计、租户隔离……）：读第五章，5.1 节有一张"节点 × 扩展手段"总表，5.2/5.3 节用两个高频场景（链路追踪、ThreadLocal 用户信息）示范选型。
- **如果你在虚拟线程（JDK 21/25）环境下运行，或准备迁移**：读第六章——ThreadLocal 该怎么用、为什么 InheritableThreadLocal 是坑、ContextSnapshot 显式传播怎么做。
- **如果你要做实时推送（聊天/大盘/进度/联机）**：读第七章——六类该用 WebSocket 的场景、三类不该用的反例、L1/L2/L3 三层技术与可运行示例。
- **系统通读**：第一章建立地图 → 第二章补 Servlet 规范地基 → 第三章（DispatcherServlet 如何接住规范）→ 第四章（请求全链路，重点）→ 第五章（扩展点，重点）→ 第六章（虚拟线程）→ 第七章（WebSocket 场景）→ 第八章（架构总分析）→ 第九章（贯通视图）。

---

# 一、总览：Servlet 规范是地基，Spring MVC 是地基上的调度中心

## 1.1 一句话定位

**Servlet 规范回答"容器如何接收一个 HTTP 请求并交给一段 Java 代码"，Spring MVC 回答"这段 Java 代码背后的整套 Web 编程模型长什么样"。**

- Servlet 规范（Jakarta Servlet，原 Java Servlet）定义的是**容器与应用之间的契约**：容器（Tomcat/Jetty/Undertow）负责字节流 → `HttpServletRequest/Response` 对象、线程调度、Filter 链、会话、生命周期；应用侧只需要实现 `Servlet`/`Filter`/`Listener` 三类组件并按规范"注册"进容器。
- Spring MVC 在这个契约上只占一个位置——**用 `DispatcherServlet` 这一个 Servlet 承接所有请求**，然后在自己内部搭起"映射 → 拦截 → 执行 → 转换 → 渲染 → 异常"的一整套策略接口体系。它不重新发明 HTTP，也不绕开容器，而是把规范给的每个"口子"（动态注册、异步、Filter、事件）都用成了自己的扩展点。
- 所以本文的叙事线是：**规范给了什么口子（第一、二章）→ Spring MVC 如何接住这些口子（第三章）→ 一次请求如何流经这些节点（第四章）→ 你的代码可以插在哪个节点（第五章）→ 虚拟线程时代这些节点用法有什么变化（第六章）→ 当"请求-响应"不够用时怎么办（第七章）**。

## 1.2 Java Servlet 规范发展历程（1997 → 2026）

### 1.2.1 版本时间线

| 版本 | 发布时间 | JSR / 所属平台 | 关键能力 | 对 Spring 生态的意义 |
|---|---|---|---|---|
| Servlet 1.0 | 1997.06 | （JSDK，随 Sun Java Web Server 发布） | `Servlet` 接口、生命周期 init/service/destroy | 一切的原点：`service()` 就是今天所有 Web 框架的入口 |
| Servlet 2.0 / 2.1 | 1998–1999 | （JSDK 时代） | `RequestDispatcher`、`ServletContext`、会话等基础抽象补齐 | MVC1 时代的 JSP+Servlet 模型成形 |
| Servlet 2.2 | 1999 | JSR 53 / J2EE 1.2（1999.12） | **WAR 包格式**、独立 web 应用、`web.xml` 部署描述符 | "一个 webapp 一份 web.xml"统治了此后十年 |
| Servlet 2.3 | 2001.08 | JSR 53 / J2EE 1.3 | **Filter 与 FilterChain**、生命周期 **Listener** 事件 | 今天所有"全局拦截"能力的源头（安全、编码、日志 Filter 全靠它） |
| Servlet 2.4 | 2003.11 | JSR 154 / J2EE 1.4 | web.xml 从 DTD 迁到 XML Schema、监听器顺序规则 | 配置表达能力增强 |
| Servlet 2.5 | 2005.09 | JSR 154（维护版）/ Java EE 5 | Java 5 语法支持、少量注解 | 过渡版本 |
| **Servlet 3.0** | 2009.12 | JSR 315 / Java EE 6 | **组件注解**（`@WebServlet/@WebFilter/@WebListener`）、**`ServletContext.addServlet/addFilter` 动态注册**、**`ServletContainerInitializer`(SCL) + `web-fragment.xml` 可插拔装配**、**异步处理 `AsyncContext`**、文件上传 `Part` | **Spring 生态的转折点**：Spring 3.1 起全面拥抱（代码式装配），Spring Boot 3.2 的内嵌容器（`spring.threads.virtual.enabled` 之前的线程模型突破）与"零 web.xml"全建立在此版之上 |
| **Servlet 3.1** | 2013.05 | JSR 340 / Java EE 7 | **非阻塞 I/O**（`ServletInputStream.setReadListener` / `setWriteListener`）、**协议升级**（`HttpServletRequest.upgrade(HttpUpgradeHandler)`） | 为 WebSocket、流式传输打底 |
| **Servlet 4.0** | 2017.09 | JSR 369 / Java EE 8 | **HTTP/2** 支持、**Server Push**（`PushBuilder`）、精确的映射匹配（`MappingMatch`） | Push 后来证明无人使用，6.1 被弃用——规范也会试错 |
| Jakarta Servlet 5.0 | 2020.10 | Jakarta EE 9 | **命名空间 `javax.*` → `jakarta.*`**，移除废弃 API | Spring Boot 3 / Framework 6 升级门槛的直接原因 |
| Jakarta Servlet 6.0 | 2022.09 | Jakarta EE 10 | 移除大量废弃方法与过时机制，语义清理 | Framework 6.x 的 API 级别 |
| **Jakarta Servlet 6.1** | 2024 | Jakarta EE 11（平台整体 GA 于 2025.06） | **弃用 HTTP/2 Push**；为 WebSocket 等规范提供在握手/请求处理期间与请求-响应交互的标准机制；错误处理与安全细节强化 | **Framework 7.0 / Boot 4 的 API 级别**（Tomcat 11 实现） |
| Jakarta Servlet 6.2 | 进行中 | Jakarta EE 12（预计 2026） | 定位为维护性版本：清理增强请求、无破坏性变更 | 观察项 |

> 时间线的关键读法：**2.2–2.5 是"配置时代"**（一切写进 web.xml）；**3.0 是"编程时代"**（注解 + 代码注册 + 异步，一次给足了 Spring 造轮子所需的所有机关）；**4.0–6.1 是"协议与治理时代"**（HTTP/2、命名空间迁移、废弃治理）。Spring MVC 真正重度依赖的版本锚点是 **3.0**（装配模型与异步）和 **6.1**（7.0 的编译基线）。

### 1.2.2 三条主线：装配、异步、协议

把 30 年的版本演进压缩成三句话：

1. **装配方式从 XML 到代码**。2.2 时代的 web.xml 是唯一入口；3.0 引入注解、`web-fragment.xml`（jar 包自带配置片段）与 `ServletContainerInitializer`（容器启动回调，可拿到 `ServletContext` 动态注册一切）之后，"框架 jar 装进 WEB-INF/lib 就自动生效"成为可能——Spring 的 `SpringServletContainerInitializer` + `WebApplicationInitializer`、Spring Boot 的 `ServletContextInitializer` 体系都直接建在这组 API 上（见 3.3 节源码证据）。
2. **线程模型从"一请求一线程到底"到"可让渡"**。3.0 的 `AsyncContext` 允许 Servlet 方法快速返回、把请求挂起交给别处（线程池/回调）处理完再 `dispatch` 回容器写响应；3.1 的非阻塞 I/O 进一步把"读请求体/写响应体"的阻塞也去掉。Spring MVC 的 `Callable`/`DeferredResult`/`SseEmitter` 返回值全部建立在这组 API 上（见 4.9 节）；而虚拟线程（第六章）则以另一种方式重新回答了"阻塞线程太贵怎么办"。
3. **协议能力从 HTTP/1.1 单声道到 HTTP/2，再收敛**。4.0 的 Server Push 在现实中几乎无人使用，6.1 正式弃用；真正沉淀下来的是 HTTP/2 多路复用本身与更精确的映射语义。

## 1.3 新规范提供了哪些能力供容器或应用扩展

Servlet 规范本质是一张**双栏契约**：左栏是容器必须做到的，右栏是应用可以插手的。整理成能力清单（引入版本括注）：

| 能力 | 规范接口（容器实现 / 应用扩展） | 引入版本 | 典型扩展者 |
|---|---|---|---|
| 请求入口 | 容器：解析 HTTP、构建 `HttpServletRequest/Response`、按 `url-pattern` 路由；应用：实现 `Servlet`，或 `@WebServlet` 注解（3.0）、`ServletContext.addServlet`（3.0） | 1.0 / 3.0 | Spring：`DispatcherServlet` 单入口 |
| 全局拦截 | 容器：按声明顺序构建 `FilterChain`；应用：实现 `Filter`，或 `@WebFilter`（3.0）、`ServletContext.addFilter`（3.0） | 2.3 / 3.0 | Spring Security、字符编码、链路追踪 |
| 生命周期事件 | 容器：在正确时机回调；应用：`ServletContextListener`、`HttpSessionListener`、`ServletRequestListener`（2.3） | 2.3 | 资源初始化/清理、在线人数统计 |
| 声明与装配 | `web.xml`（2.2）→ `web-fragment.xml`（3.0）→ `ServletContainerInitializer` + `@HandlesTypes`（3.0）→ 容器启动时扫描类路径 | 2.2 / 3.0 | Spring：`SpringServletContainerInitializer` |
| 异步处理 | `request.startAsync()`、`AsyncContext.complete/dispatch`、`AsyncListener` 超时回调 | 3.0 | Spring：`WebAsyncManager`（Callable/DeferredResult/SseEmitter） |
| 非阻塞 I/O | `ServletInputStream.setReadListener(ReadListener)`、`setWriteListener(WriteListener)`，容器线程零阻塞读写 | 3.1 | Spring：WebFlux 的 Servlet 适配、流式场景 |
| 协议升级 | `HttpServletRequest.upgrade(HttpUpgradeHandler)`，升级完成后脱离 HTTP 语义 | 3.1 | WebSocket（经 Spring WebSocket） |
| HTTP/2 | `PushBuilder` 服务端推送 | 4.0（6.1 弃用） | 基本无 |
| 会话治理 | `SessionCookieConfig`、`SessionTrackingModes`（3.0） | 3.0 | Spring Session |
| 安全 | `login/logout/authenticate`（3.0）、`@ServletSecurity`（3.0） | 3.0 | Spring Security（自建 Filter 链，规范方法仅兜底） |
| 错误处理 | `<error-page>`（2.3）、`DispatcherType.ERROR/ASYNC/FORWARD` 分发语义（3.0） | 2.3 / 3.0 | Boot：`BasicErrorController`（接 ERROR dispatch） |
| 环境与依赖 | `@Resource`/JNDI（Java EE 环境）、`ServletContext` 属性 | 1.0+ | 传统 EE 集成 |

**两条阅读结论**：

1. **规范给应用留的"扩展窗口"按位置分三层**：容器管到 `Filter` 之前（线程、路由、会话），`Filter` 是应用在容器语境里的第一道也是最后一道全局防线，`Servlet` 之内规范就不再管了——Spring MVC 的全部扩展点体系都发生在"最后一个 Filter 之后"的自留地里。
2. **规范给容器的扩展窗口在 3.0 之后也被打开**：`ServletContainerInitializer` 本质上是"框架在容器启动期的扩展点"，Spring 正是用它把"Spring 的 Web 装配模型"注入任何符合规范的容器，从而做到一套 WAR 到处运行。

## 1.4 Spring MVC 如何使用这些规范能力（对照表）

| Servlet 规范机制 | Spring Framework / Boot 的用法 | 源码证据 |
|---|---|---|
| `Servlet` + `url-pattern` | **`DispatcherServlet` 一个 Servlet 映射 `/`**，做前端控制器；其余 Servlet（如静态资源 `default`）按需共存 | `spring-webmvc/.../servlet/DispatcherServlet.java` |
| `ServletContext.addServlet`（3.0） | Boot 不用 web.xml 也不用 `@WebServlet`，用 **`DispatcherServletRegistrationBean`**（继承 `ServletRegistrationBean`）在容器启动时注册，带条件装配与可编程配置 | Boot 4：`module/spring-boot-webmvc/.../DispatcherServletAutoConfiguration.java:113-127` |
| `Filter`（2.3） | spring-web `filter` 包 18 个现成 Filter（编码/CORS/日志/Etag/转发头……）；**`DelegatingFilterProxy`** 把 Filter 执行"桥"回 IoC 容器里的 bean，让 Filter 能享受依赖注入 | `spring-web/.../filter/DelegatingFilterProxy.java:247,272,351` |
| Filter 排序 | Boot `FilterRegistrationBean`（含 `DelegatingFilterProxyRegistrationBean`）以 `order` 精确编排 Filter 顺序；Spring Security 的 `springSecurityFilterChain` 即一个委托 Filter，order 为 `SecurityProperties.DEFAULT_FILTER_ORDER`（-100） | Boot 4：`module/spring-boot-security/.../SecurityFilterAutoConfiguration.java:59-61` |
| `ServletContainerInitializer`（3.0） | `SpringServletContainerInitializer`（`@HandlesTypes(WebApplicationInitializer.class)`）让容器把"所有实现了 WebApplicationInitializer 的类"回调给 Spring，由它们完成传统 WAR 装配；spring-web 的 jar 内带 `spring_web` 的 `web-fragment.xml` | `spring-web/.../web/SpringServletContainerInitializer.java:110-111`；`spring-web/src/main/resources/META-INF/web-fragment.xml` |
| `AsyncContext`（3.0） | `WebAsyncManager` + `StandardServletAsyncWebRequest` 包装：控制器返回 `Callable/DeferredResult`、`ResponseBodyEmitter/SseEmitter/StreamingResponseBody` 时启动容器异步，结果就绪后 `dispatch` 回 `DispatcherServlet` 二次分发 | `spring-web/.../context/request/async/WebAsyncManager.java:288,398` |
| ERROR 分发（3.0） | MVC 处理不了的异常经 `response.sendError` 触发容器 ERROR dispatch，由 Boot 的 `BasicErrorController` 兜底渲染错误页/错误 JSON | Boot：`spring-boot-webmvc` 模块 |
| 容器线程模型 | Tomcat 平台线程池（默认）→ 虚拟线程执行器（`spring.threads.virtual.enabled=true`）切换 | Boot 4：`module/spring-boot-tomcat/.../TomcatVirtualThreadsWebServerFactoryCustomizer.java:37-40` |
| 会话/安全规范方法 | 基本让位于 Spring 自有抽象（`@SessionAttributes`、Spring Session、Security 过滤链），规范方法作为兜底互操作保留 | — |

一句话总结：**Spring MVC 把 Servlet 规范当"接入协议"用（一个 Servlet + 一组 Filter + SCL 装配），把规范之外的 95% 的 Web 编程体验全部建在自己的策略接口体系里**——这正是第八章要展开的架构分析。

## 1.5 版本演进：MVC 在 Framework 5 → 6 → 7 中的变化

| 主题 | Framework 5.x（2017–2020） | Framework 6.x（2021–2025） | Framework 7.0（2025.11 GA，本文 7.0.8） |
|---|---|---|---|
| 命名空间/基线 | `javax.servlet`，Java 8+，Servlet 3.1+ | **`jakarta.servlet`**，Java 17+，Servlet 5.0/6.0 | Java 17+（官方支持至 **25**），**Servlet 6.1**（Jakarta EE 11） |
| 装配 | WAR + SCL 双轨（含义见 2.6 节） | 双轨不变 | 双轨不变；Boot 4 模块化（`spring-boot-webmvc` 独立模块） |
| 线程模型 | 平台线程 + 异步可让渡 | 同左；6.1 起提供 `ContextPropagatingTaskDecorator` | 同左；Boot 3.2/4.x `spring.threads.virtual.enabled` 一键虚拟线程 |
| 上下文传播 | 手工 | 6.2 新增 **`RequestAttributesThreadLocalAccessor`**（把 `RequestContextHolder` 接入 Micrometer context-propagation） | 7.0 新增 **`PropagationContextElement`**（Kotlin 协程场景的 ThreadContextElement） |
| 观测 | 无内建 | 6.0 **`ServerHttpObservationFilter`**：请求级 Observation（Micrometer Tracing 的锚点） | 持续增强（Boot 4 独立 `WebMvcObservationAutoConfiguration`） |
| 消息转换 | Jackson 2 | Jackson 2 | **Jackson 3（`tools.jackson`）**：新转换器 `JacksonJsonHttpMessageConverter`（`@since 7.0`）；`WebMvcConfigurer.configureMessageConverters(HttpMessageConverters.ServerBuilder)` 新配置口，旧方法废弃 |
| API 版本化 | 无 | 无 | **`@RequestMapping#version("1.2")` / `version("1.2+")`** + `ApiVersionResolver` 族（header/query/path/media-type 四种来源）+ `WebMvcConfigurer.configureApiVersioning` —— `AbstractHandlerMapping.getHandler` 第一行即 `initApiVersion(request)` |
| 其他 | 函数式端点（RouterFunction） | `@HttpExchange` 声明式客户端、`RestClient`（6.1）、`ErrorResponse.Interceptor`（6.2） | AOT/Native 持续打磨、null 安全迁移到 JSpecify |

（与《Spring Framework.md》1.6 节的容器/事务版本演进互为补充。）

## 1.6 关键问题 → 章节映射（全文导览）

| 你想搞清楚的问题 | 去哪一节 |
|---|---|
| Servlet 规范 30 年都加了什么？ | 1.2 |
| 规范给了容器/应用哪些扩展口子？ | 1.3、第二章 |
| Spring MVC 是怎么"长"在容器里的？ | 第三章 |
| 请求从浏览器到 Controller 的每一步？ | 第四章 |
| Filter 和 HandlerInterceptor 到底差在哪？ | 2.2 vs 4.5、5.1 总表 |
| 链路追踪代码该写在哪？ | 5.2 |
| 用户信息放 ThreadLocal 该写在哪？虚拟线程下怎么办？ | 5.3、第六章 |
| Controller 前后都能插手的 A/B/C 是什么？ | 5.1（Interceptor/Advice/ReturnValueHandler） |
| 虚拟线程开启后异步（Callable/DeferredResult）还要吗？ | 6.3、6.5 |
| 什么时候该用 WebSocket？和 SSE/轮询怎么选？ | 第七章 |
| Spring MVC 架构凭什么这么稳？ | 第八章 |

## 1.7 全文章节地图

```
第一章 总览（你在这里）：规范时间线 / 能力清单 / Spring MVC 的用法 / 版本演进
第二章 Servlet 规范能力全景：容器视角的请求流水线、Filter、AsyncContext、非阻塞、装配与"双轨"
第三章 DispatcherServlet 的诞生：继承树、initStrategies 八大组件、Boot 4 装配链
第四章 一次请求的一生（重点）：容器段 → doService → doDispatch 逐段 → 映射/拦截/执行/渲染/异常/异步
第五章 扩展点总表与两个实战场景（重点）：链路追踪挂 Filter、用户上下文挂 Interceptor
第六章 虚拟线程（JDK 25）：容器接入、ThreadLocal 的三种命运、ContextSnapshot 显式传播
第七章 WebSocket 场景全景：六类该用场景、三类反例、L1/L2/L3 三层技术、两个示例、与主线四交集
第八章 Spring MVC 架构总分析：三分法、与 WebFlux 同构、可测试性、AOT、7.0 新特性
第九章 贯通视图：节点×扩展手段×场景对位表、学习路线、源码阅读入口
```

---
# 二、Servlet 规范能力全景（写给 Spring 用户的地基章）

## 2.1 容器眼中的一个请求（Tomcat 11 视角）

**先说白话**：浏览器发出的字节流进入容器后，先被"翻译"成 Java 对象（`HttpServletRequest/Response`），再沿着一条固定的管道被层层"盖章"，最后到达某个 Servlet。理解这条管道，才能理解 Filter 为什么是"全局"的、以及 Spring MVC 的边界从哪开始。

> 以下节点为 **Tomcat 实现细节**（本仓库未含 Tomcat 源码），节点名以其公开实现类为准，读者可在 Tomcat 11 源码中查证。

```
浏览器
  │ HTTP/1.1 或 HTTP/2 字节流
  ▼
┌─ Connector（连接器，NIO）───────────────────────────────┐
│  Poller 检测就绪 → Processor 解析 HTTP → 生成 Request/Response │
└──────────────┬──────────────────────────────────────┘
               ▼ CoyoteAdapter.service()：容器对象适配
┌─ Engine Pipeline ── Host Pipeline ── Context Pipeline ──┐
│  （各级 Valve：日志、访问控制、错误报告……）                      │
└──────────────┬──────────────────────────────────────┘
               ▼ Wrapper（该 Servlet 的宿主）
        ① 创建/复用 Filter 链（ApplicationFilterChain）
        ② session/context 关联、异步标记就位
               ▼
     FilterChain.doFilter()  ← 应用扩展窗口 A（2.2 节）
               ▼
     Servlet.service(request, response)  ← DispatcherServlet 从这里开始
```

两个对后文至关重要的容器行为：

1. **Filter 链的构建依据是"映射"**：每个 Filter 声明自己的 `url-pattern` 与 `dispatcher` 类型，容器按 order 顺序把命中的 Filter 串成链——所以 Filter 是"容器级全局"的，覆盖一切请求（包括最终 404 的、静态资源的、转发的）。
2. **`DispatcherType` 分发语义**：同一物理请求可能以 `REQUEST`、`FORWARD`、`INCLUDE`、`ASYNC`、`ERROR` 五种"分发"多次经过这条管道（3.0 规范）。Spring 的很多"防重"设计（如 `OncePerRequestFilter`）都是在跟这个语义打交道。

## 2.2 Filter：规范级的"全局拦截器"

**先说白话**：Filter 是容器在调用你的 Servlet **之前**插手请求的最后机会、也是写响应**之后**加工响应的第一机会。它运行在容器线程、容器语境里，能覆盖包括"没有任何 Controller 命中"在内的全部请求——这一"覆盖范围"属性是它与 Spring MVC `HandlerInterceptor` 的本质区别（4.5、5.1 节反复用到）。

接口契约（`jakarta.servlet.Filter`）：

```java
public interface Filter {
    default void init(FilterConfig filterConfig) throws ServletException {}
    void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException;
    default void destroy() {}
}
```

容器按声明顺序构建链，每个 Filter 的 `doFilter` 里调用 `chain.doFilter(...)` 把控制权交给下一个——这是一个**递归的责任链**：`chain.doFilter` 返回后，响应已写完，所以 Filter 天然有"前处理 + 后处理"两个时机。

**【源码证据】Spring 对 Filter 语义的第一个补丁：`OncePerRequestFilter`**（`spring-web/src/main/java/org/springframework/web/filter/OncePerRequestFilter.java`）。由于 `REQUEST`/`ASYNC`/`ERROR` 多次分发会让同一个 Filter 被执行多次，Spring 提供了这个基类，用请求属性做"已过滤"标记：

```java
// OncePerRequestFilter.java:89-121（节选）
public void doFilter(ServletRequest request, ServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
    ...
    String alreadyFilteredAttributeName = getAlreadyFilteredAttributeName();
    boolean hasAlreadyFilteredAttribute = request.getAttribute(alreadyFilteredAttributeName) != null;

    if (skipDispatch(httpRequest) || shouldNotFilter(httpRequest)) {
        filterChain.doFilter(request, response);           // 本 Filter 不处理，直接放行
    }
    else if (hasAlreadyFilteredAttribute) {                // 已经滤过：ASYNC/ERROR 分发再来时
        if (DispatcherType.ERROR.equals(request.getDispatcherType())) {
            doFilterNestedErrorDispatch(httpRequest, httpResponse, filterChain);  // 只在 ERROR 分发时提供嵌套钩子
            return;
        }
        filterChain.doFilter(request, response);
    }
    else {
        request.setAttribute(alreadyFilteredAttributeName, Boolean.TRUE);
        try {
            doFilterInternal(httpRequest, httpResponse, filterChain);  // 子类真正实现的钩子
        }
        finally {
            request.removeAttribute(alreadyFilteredAttributeName);
        }
    }
}
```

配套的三个可覆盖点给了精确的分发控制：`shouldNotFilter()`（按请求跳过）、`shouldNotFilterAsyncDispatch()`（ASYNC 分发是否再过滤，默认"是=跳过"）、`shouldNotFilterErrorDispatch()`（同左）。spring-web `filter` 包下的 18 个现成 Filter（`CharacterEncodingFilter`、`CorsFilter`、`ForwardedHeaderFilter`、`ServerHttpObservationFilter` 等）全部基于此基类。

**【源码证据】第二个补丁：`DelegatingFilterProxy`**（`spring-web/.../filter/DelegatingFilterProxy.java`）。Filter 由**容器**实例化（不归 Spring 管），拿不到依赖注入——Spring 的解法是注册一个"壳"Filter，执行时按 bean 名去 WebApplicationContext 里找真正的 Filter bean 并委托：

```java
// DelegatingFilterProxy.java:247-272（节选）
public void doFilter(ServletRequest request, ServletResponse response, FilterChain filterChain) {
    Filter delegateToUse = this.delegate;                  // 懒加载缓存
    if (delegateToUse == null) { ... delegateToUse = initDelegate(wac); ... }
    invokeDelegate(delegateToUse, request, response, filterChain);   // :272
}
```

Spring Security 就是这么接入的：容器里只看到一个 order 为 -100 的 `springSecurityFilterChain` 委托 Filter（Boot 4：`SecurityFilterAutoConfiguration.java:59-61`），真正的几十个安全 Filter 全在 IoC 容器里编排（`spring-security/config/.../BeanIds.java:41`）。

**顺序规则**：web.xml 按 `<filter-mapping>` 书写顺序；注解方式无序（规范限制）；Boot 下用 `FilterRegistrationBean.setOrder(...)` 精确控制——这是第五章"追踪 Filter 要排第一"的操作依据。

## 2.3 异步处理：AsyncContext（3.0）——把线程还给容器

**先说白话**：3.0 之前，"一个请求占用一个容器线程直到响应写完"。慢下游（长轮询、外部调用）时，线程池迅速耗尽。`AsyncContext` 的解法是**两段式**：Servlet 方法在主线程上快速返回（线程归还容器），把请求/响应引用交给异步任务；任务完成后要么 `complete()`（容器直接收尾），要么 `dispatch()`（**以 ASYNC 分发类型重新走一遍 Filter 链和 Servlet**，由 Servlet 把结果写成响应）。

关键 API 与语义：

```java
AsyncContext ac = request.startAsync();   // 通知容器"这个请求要异步了"
ac.setTimeout(...);                        // 容器级超时保护（默认 30s，容器可配）
ac.addListener(new AsyncListener() { ... }); // onComplete/onTimeout/onError/onStartAsync
ac.complete();                             // 异步线程直接收尾
ac.dispatch();                             // 或：ASYNC 分发，回到 Servlet 再处理
```

**对 Spring MVC 的意义**：`DispatcherServlet` 对 `Callable`/`DeferredResult` 返回值的整套支持，就是把 `startAsync`/`dispatch` 翻译成框架语言（4.9 节逐行解析）；ASYNC 分发会**再次经过 Filter 链**——这正是 `OncePerRequestFilter`、以及 5.2 节"追踪 Filter 必须正确处理两次分发"的原因。

## 2.4 非阻塞 I/O 与协议升级（3.1）、HTTP/2（4.0）

- **非阻塞 I/O**：`ServletInputStream.setReadListener(ReadListener)` / `ServletOutputStream.setWriteListener(WriteListener)`。容器线程提交注册后立即返回，数据就绪/可写时由容器回调——回调里才允许读写，且必须一次读完当次就绪的数据。这为"零阻塞处理大报文/流式响应"提供了规范通道（WebFlux 的 Servlet 适配、大文件流走的就是这条路）。
- **协议升级**：`HttpServletRequest.upgrade(HttpUpgradeHandler)`——HTTP/1.1 `Upgrade` 头的规范通道，握手成功后容器把 socket 交给 handler，脱离 HTTP 请求-响应语义。WebSocket 即由此建立（Spring WebSocket 内部走 Tomcat 的 WsHttpUpgradeHandler；场景与用法见第七章）。
- **HTTP/2**（4.0）：多路复用 + `PushBuilder` 服务端推送。Push 因浏览器支持度不足被 6.1 弃用——规范演进的现实注脚：**没有生态使用的能力会被移除**。

## 2.5 装配与可插拔：从 web.xml 到 Spring Boot 的 ServletContextInitializer

**先说白话**：Servlet 3.0 之前，"把一个类注册进容器"的唯一途径是 web.xml；3.0 给了三种新途径（注解、jar 自带 fragment、SCL 回调），Spring Boot 又加了一种（RegistrationBean）。四者的关系不是替换而是叠加，Spring 全都支持。

| 装配途径 | 引入 | 机制 | Spring 的用法 |
|---|---|---|---|
| `web.xml` | 2.2 | 部署描述符，容器启动解析 | 传统 WAR 兼容（`<servlet>` 写 DispatcherServlet） |
| `@WebServlet/@WebFilter/@WebListener` | 3.0 | 容器扫描类文件注解 | 基本不用（无法条件化、无法注入） |
| `web-fragment.xml` | 3.0 | jar 内 `META-INF/web-fragment.xml` 自动并入部署 | spring-web 自带 `spring_web` fragment |
| `ServletContainerInitializer` | 3.0 | 容器启动回调 `onStartup(Set<Class<?>>, ServletContext)`，`@HandlesTypes` 指定感兴趣的类型 | `SpringServletContainerInitializer`：回调所有 `WebApplicationInitializer` 实现类 |
| Boot `ServletContextInitializer` | Boot | 不走容器扫描：内嵌容器启动时收集容器里所有 `ServletContextInitializer` bean，依次执行 | `RegistrationBean` 家族（Servlet/Filter/Listener 三类）+ `DelegatingFilterProxyRegistrationBean` |

**【源码证据】Spring 的 SCL 桥**（`spring-web/src/main/java/org/springframework/web/SpringServletContainerInitializer.java:110-111`）：

```java
@HandlesTypes(WebApplicationInitializer.class)
public class SpringServletContainerInitializer implements ServletContainerInitializer {
    // 容器传入所有 WebApplicationInitializer 实现类，逐个 onStartup（按 @Order 排序、支持 @Ordered）
```

**【源码证据】Boot 4 的聚合器**（`core/spring-boot/.../web/servlet/ServletContextInitializerBeans.java`）：`TomcatServletWebServerFactory` 启动时调用 `selfInitialize(ServletContext)`，把 ApplicationContext 里所有 `ServletContextInitializer` 类型的 bean（含 RegistrationBean、以及"裸" Servlet/Filter bean 被自动包装后的结果）收集、按 order 排序、逐个 `onStartup`。**这就是"Boot 里定义一个 `FilterRegistrationBean`/`ServletRegistrationBean` 就会生效"的全部机制**——本质仍然是 Servlet 3.0 的 `ServletContext.addServlet/addFilter`，只是注册时机从"容器扫描"变成"Spring 容器装配完成之后"。

## 2.6 "双轨"机制详解：SCL 是什么，WAR 与内嵌容器为何殊途同归

1.5 节版本演进表里的"WAR + SCL 双轨"值得单独拆开讲——它是理解 Spring 装配模型的钥匙。

**先说 SCL 是什么**：`ServletContainerInitializer` 的缩写（"Servlet 容器初始化器"），Servlet 3.0（JSR 315）引入的 SPI 接口。规范侧一共三样东西：

```java
// 1. SPI 接口：容器启动时回调一次
public interface ServletContainerInitializer {
    void onStartup(Set<Class<?>> c, ServletContext ctx) throws ServletException;
}

// 2. @HandlesTypes 注解：声明"我对哪些类感兴趣"（容器负责找出实现/继承它们的类）

// 3. 注册文件：jar 内 META-INF/services/jakarta.servlet.ServletContainerInitializer
//    内容一行：org.springframework.web.SpringServletContainerInitializer（标准 Java SPI 约定）
```

容器启动时的动作顺序：扫描 `WEB-INF/lib` 下每个 jar 的 services 注册文件 → 实例化这些 SCL → 按 `@HandlesTypes` 的声明扫描全部类文件，找出实现了指定类型的类 → 打包成 `Set<Class<?>>` 传入 `onStartup`。Spring 的接线（上节源码证据：`SpringServletContainerInitializer.java:110-111`）把这批类实例化、按 `@Order` 排序、逐个回调其 `onStartup(ServletContext)`——**web.xml 的活，改由一个被容器回调的 Java 类来干，这就是"零 web.xml 装配"的全部原理**。

**"双轨"指 Spring Boot 应用的两条启动/装配轨道**，它们殊途同归于 Servlet 3.0 的动态注册 API：

| | 轨道 A：WAR + SCL（外部容器轨） | 轨道 B：内嵌容器轨（Boot 默认） |
|---|---|---|
| 产物 | `app.war` | `java -jar app.jar` |
| 容器由谁启动 | **外部 Tomcat 先启动**，扫描发现 spring-web jar 的 SPI 文件，回调 SCL | **Boot 自己 new Tomcat**（`TomcatServletWebServerFactory`），容器扫描环节完全跳过 |
| 应用入口 | 主类 `extends SpringBootServletInitializer`（它本身就是 `WebApplicationInitializer`） | `main()` 里 `SpringApplication.run()` |
| Servlet/Filter 怎么注册 | Spring 容器起来后，`ServletContextInitializerBeans` 收集 RegistrationBean，调 `ServletContext.addServlet/addFilter` | **同左，一字不差** |
| 典型场景 | 企业现成 Tomcat 集群、运维只认 WAR | 常规云原生部署 |

两条轨道的**汇合点是 `DispatcherServletRegistrationBean`**（3.3 节）：无论哪条轨，`DispatcherServlet` 都由它经 Servlet 3.0 动态注册 API 注册，区别只是"谁先启动、谁最后来调 `addServlet`"——轨道 A 是外部容器经 SCL 回调唤醒 Spring 后由 Spring 注册，轨道 B 是 Spring 先起 Tomcat 再自己注册。所以一个 Boot 应用改打 WAR 部署，业务代码零改动。

**历史三段线**（对照 1.2 节时间线）：Servlet 2.5 及以前只有"XML 轨"（web.xml 里配 `ContextLoaderListener` + `DispatcherServlet`）→ 3.0 打开"编程轨"（注解/fragment/SCL），Spring 3.1（2011）随即提供 `WebApplicationInitializer`，应用从此可以没有 web.xml → Boot 1.0（2014）再加"内嵌轨"：容器从"部署目标"变成"应用的一个依赖"，SCL 的容器扫描机制在新轨道里用不上但被完整保留。这就是 1.5 节版本演进表里"双轨不变"的确切含义：**Framework 5→7 始终同时支持这两种装配方式，且都汇到同一套 `ServletContextInitializer` API 上**。

## 2.7 本章小结

- 容器把请求"翻译"成对象后，按**管道 → Filter 链 → Servlet** 的顺序交出控制权；Filter 之前的所有节点都归容器管，应用插不进手。
- 应用的三个规范扩展窗口：**Filter（全局）、Servlet（入口）、Listener（生命周期）**，外加 3.0 之后的三种**装配窗口**（注解/fragment/SCL）。
- 规范的三种"线程让渡"：**AsyncContext**（整段异步 + ASYNC 分发回环）、**非阻塞 I/O**（读写回调化）、协议升级（脱离 HTTP）。
- DispatcherType 五种分发的存在，使"一个请求多次经过 Filter 链"成为常态——Spring 用 `OncePerRequestFilter` 与 `shouldNotFilterAsyncDispatch/ErrorDispatch` 应对，这也是第五章扩展选型的前提知识。
- **双轨装配**：WAR 部署走 SCL 容器回调唤醒 Spring，内嵌部署由 Boot 直接指挥容器，两条轨道殊途同归于 Servlet 3.0 动态注册（2.6 节）。
- Spring 对规范的态度：**接入面最窄化（一个 Servlet）+ 装配面最大化（把 SCL/Filter/注册全部桥回 IoC 容器）**。下一章看这个"桥"的完整实现。

---
# 三、Spring MVC 接住 Servlet 规范：DispatcherServlet 的诞生

## 3.1 模块定位与包结构

spring-web 与 spring-webmvc 的分工：

- **spring-web**：与"是否用 Servlet"无关的公共层——`HttpMessageConverter`、`HandlerMethodArgumentResolver/ReturnValueHandler`、`RestClient`、multipart 抽象、以及 **Servlet 侧的基础设施**（`filter` 包、`context/request` 包、`SpringServletContainerInitializer`）。
- **spring-webmvc**：Servlet 栈的完整 MVC——`DispatcherServlet`、三大策略接口、注解编程模型实现、视图技术。它依赖 spring-web 并在其上长出"调度"能力。

spring-webmvc 顶层包（`org.springframework.web.servlet`，实测）：

```
DispatcherServlet / FrameworkServlet / HttpServletBean   ← 入口三件套（3.2 节）
HandlerMapping / HandlerAdapter / HandlerExceptionResolver ← 三大策略接口
HandlerInterceptor / AsyncHandlerInterceptor / HandlerExecutionChain ← 拦截器契约
ModelAndView / View / ViewResolver / SmartView           ← 渲染契约
LocaleResolver / FlashMapManager / RequestToViewNameTranslator ← 请求辅助策略
handler/   ← HandlerMapping 实现（RequestMappingHandlerMapping 等）
mvc/method/annotation/ ← 注解式控制器的执行引擎（本章主角）
view/ i18n/ config/ function/ resource/ support/
```

> 注意：早期文档常说的"九大组件"在 7.0 中是**八大**——`ThemeResolver` 随主题支持在 Framework 6.0 被整体移除。

## 3.2 继承树：HttpServletBean → FrameworkServlet → DispatcherServlet

三层各司其职：**HttpServletBean 管"启动"，FrameworkServlet 管"上下文绑定"，DispatcherServlet 管"调度"**。

### 3.2.1 HttpServletBean：把 Servlet 的 init-param 接到 Bean 属性体系

**【源码证据】**（`spring-webmvc/.../servlet/HttpServletBean.java:150-171`）：`init()` 被 final 化，把 init-param 按名字绑定到子类的 Bean 属性（如 `<init-param>` `contextConfigLocation` → `setContextConfigLocation`），再回调 `initServletBean()`。这让 Servlet 第一次拥有了"Spring Bean 式"的属性注入。

### 3.2.2 FrameworkServlet：每个请求前后的"上下文仪式"

它持有 `WebApplicationContext`，并定义了所有 HTTP 方法的统一入口 `service()` → `processRequest()`（`spring-webmvc/.../servlet/FrameworkServlet.java:870, 982`）。

**【源码证据】processRequest——框架自带的 ThreadLocal 绑定/清理样板**（FrameworkServlet.java:982-1019）：

```java
protected final void processRequest(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException {
    long startTime = System.currentTimeMillis();
    Throwable failureCause = null;

    LocaleContext previousLocaleContext = LocaleContextHolder.getLocaleContext();      // :988 保留旧值
    LocaleContext localeContext = buildLocaleContext(request);                        // :989 构建新值

    RequestAttributes previousAttributes = RequestContextHolder.getRequestAttributes(); // :991
    ServletRequestAttributes requestAttributes = buildRequestAttributes(request, response, previousAttributes); // :992

    WebAsyncManager asyncManager = WebAsyncUtils.getAsyncManager(request);
    asyncManager.registerCallableInterceptor(FrameworkServlet.class.getName(), new RequestBindingInterceptor()); // :995

    initContextHolders(request, localeContext, requestAttributes);                    // :997 绑定 ThreadLocal

    try {
        doService(request, response);                                                  // :1000 委托 DispatcherServlet
    }
    catch (ServletException | IOException ex) { failureCause = ex; throw ex; }
    catch (Throwable ex) { failureCause = ex; throw new ServletException("Request processing failed: " + ex, ex); }
    finally {
        resetContextHolders(request, previousLocaleContext, previousAttributes);       // :1012 恢复原值（而非置空）
        if (requestAttributes != null) {
            requestAttributes.requestCompleted();                                      // :1014 标记请求结束（session 访问保护）
        }
        logResult(request, response, failureCause, asyncManager);                      // :1016
        publishRequestHandledEvent(request, response, startTime, failureCause);        // :1017 发布 ServletRequestHandledEvent
    }
}
```

三个细节值得记住（5.3 节会用到）：

1. `initContextHolders`（:1054-1063）绑定的是 **`LocaleContextHolder` 与 `RequestContextHolder`** 两个 ThreadLocal——此后任意业务代码 `RequestContextHolder.currentRequestAttributes()` 都能拿到请求。你自己的 ThreadLocal 绑定应该学这个写法：**进门前保存旧值、出门恢复旧值**，而不是简单 finally 置空。
2. `resetContextHolders` 用**恢复**而非清理，兼容 DispatcherServlet 嵌套/include 场景。
3. `requestCompleted()`（:1014）之后 `ServletRequestAttributes` 不再允许写 request 属性——这是 Spring 对"异步线程在请求收尾后仍想读请求属性"问题的第一层保护（6.2 起还有 `SnapshotServletRequestAttributes` 快照层，见 6.4 节）。

### 3.2.3 DispatcherServlet：onRefresh 里装配"八大组件"

DispatcherServlet 既是 Servlet 也是 `ApplicationObjectSupport`——它把**父容器**（WebApplicationContext）的 `refresh()` 完成事件接到自己的 `onRefresh()` 上，完成策略组件装配：

**【源码证据】**（`spring-webmvc/.../servlet/DispatcherServlet.java:433-450`）：

```java
@Override
protected void onRefresh(ApplicationContext context) {
    initStrategies(context);
}

protected void initStrategies(ApplicationContext context) {
    initMultipartResolver(context);            // multipartResolver（按固定 bean 名查找，无默认）
    initLocaleResolver(context);               // localeResolver（默认 AcceptHeaderLocaleResolver）
    initHandlerMappings(context);              // ★ 映射：URL → Handler
    initHandlerAdapters(context);              // ★ 执行：如何调用 Handler
    initHandlerExceptionResolvers(context);    // ★ 异常：Exception → 响应
    initRequestToViewNameTranslator(context);  // 默认 DefaultRequestToViewNameTranslator
    initViewResolvers(context);                // ★ 渲染：视图名/View → 输出
    initFlashMapManager(context);              // 默认 SessionFlashMapManager
}
```

装配规则（以 `initHandlerMappings` :506 起的方法体为例，其余同理）：

1. `detectAllHandlerMappings=true`（默认）：收集容器里**所有** `HandlerMapping` 类型的 bean，按 order 排序；
2. 一个都没有：回退到 `DispatcherServlet.properties`（`spring-webmvc/src/main/resources/org/springframework/web/servlet/DispatcherServlet.properties`）里的默认实现（`BeanNameUrlHandlerMapping`、`RequestMappingHandlerMapping`、`RouterFunctionMapping` 等）；
3. `detectAllXxx=false` 时按固定 bean 名（`handlerMapping`、`viewResolver` 等）查找单个。

**这套"容器里有什么就用什么、没有就回退默认"的机制，就是 Spring MVC 扩展点的总闸门**：你注册一个 `HandlerExceptionResolver` bean，`DispatcherServlet` 自动收编；Boot 的 `WebMvcAutoConfiguration` 注册的一堆默认 bean 也是这么被发现的。

## 3.3 Boot 4 的装配链：从 @Configuration 到 ServletContext.addServlet

**先说白话**：Boot 的内嵌 Tomcat 启动时没有 web.xml，`DispatcherServlet` 是靠一条"自动配置 → RegistrationBean → ServletContextInitializer"的链注册进去的——这正是 Servlet 3.0 动态注册 API 的教科书用法。

**【源码证据】**（Boot 4：`module/spring-boot-webmvc/src/main/java/org/springframework/boot/webmvc/autoconfigure/DispatcherServletAutoConfiguration.java:88-125`）：

```java
@Bean(name = DEFAULT_DISPATCHER_SERVLET_BEAN_NAME)                       // :88 "dispatcherServlet"
DispatcherServlet dispatcherServlet(WebMvcProperties webMvcProperties) {
    DispatcherServlet dispatcherServlet = new DispatcherServlet();
    dispatcherServlet.setDispatchOptionsRequest(webMvcProperties.isDispatchOptionsRequest());
    ...
}

@Bean(name = DEFAULT_DISPATCHER_SERVLET_REGISTRATION_BEAN_NAME)          // :115 "dispatcherServletRegistration"
@ConditionalOnBean(value = DispatcherServlet.class, name = DEFAULT_DISPATCHER_SERVLET_BEAN_NAME)
DispatcherServletRegistrationBean dispatcherServletRegistration(DispatcherServlet dispatcherServlet,
        WebMvcProperties webMvcProperties, ObjectProvider<MultipartConfigElement> multipartConfig) {
    DispatcherServletRegistrationBean registration =
            new DispatcherServletRegistrationBean(dispatcherServlet, webMvcProperties.getServlet().getPath()); // :119-120 默认 "/"
    registration.setLoadOnStartup(webMvcProperties.getServlet().getLoadOnStartup());
    multipartConfig.ifAvailable(registration::setMultipartConfig);
    return registration;
}
```

`DispatcherServletRegistrationBean` 继承 `ServletRegistrationBean`（Boot：`core/spring-boot/.../web/servlet/ServletRegistrationBean.java`），后者实现 `ServletContextInitializer` 并在 `onStartup` 里调用 `ServletContext.addServlet(...)` + `registration.setLoadOnStartup(1)` + `addMapping`。内嵌容器启动时由 `ServletContextInitializerBeans` 统一收集执行（2.5 节）。

**与"WAR 部署"的关系**：WAR 场景下没有 Boot 的内嵌容器，走的是 SCL 链——`SpringServletContainerInitializer` 回调 `SpringBootServletInitializer`，由它启动 Spring 容器、再把 RegistrationBean 们注册到外部容器。**两条链殊途同归，汇聚点都是 Servlet 3.0 的动态注册**（双轨机制的完整展开见 2.6 节）。

## 3.4 设计哲学：为什么是"单 Servlet 前端控制器 + 策略接口"

1. **入口收敛，策略开放**。规范允许应用注册任意多个 Servlet，但 Spring MVC 只用 `DispatcherServlet` 一个入口承接全部请求，把"差异"全部下沉到 `HandlerMapping/HandlerAdapter/...` 策略接口上。入口唯一 → Filter 链语义稳定、上下文绑定只做一次、异步回环路径单一；策略开放 → 注解式、函数式、BeanName 式、资源式处理器共存（`DispatcherServlet.properties` 默认注册了 3 个 HandlerMapping、4 个 HandlerAdapter）。
2. **每个组件都可替换，框架自己是首位用户**。`WebMvcAutoConfiguration` 注入的默认策略 bean 与你自定义的 bean 走同一条 `initStrategies` 发现路径；`WebMvcConfigurer` 的 19 个 default 方法（源码 `WebMvcConfigurer.java:61-255`，含 7.0 新增的 `configureApiVersioning`、`configureMessageConverters(HttpMessageConverters.ServerBuilder)`）是给应用的标准 customize 面。
3. **上下文只在 FrameworkServlet 这一层绑定一次**，内部所有组件通过 `RequestContextHolder`/方法参数取用——这为 5.3 节的"用户上下文放哪"问题提供了官方范本。

## 3.5 本章小结

- `HttpServletBean`（init-param → Bean 属性）→ `FrameworkServlet`（WebApplicationContext + 每请求 ThreadLocal 仪式 + 事件发布）→ `DispatcherServlet`（onRefresh 装配八大策略组件）——三层职责清晰，行号见 3.2。
- `DispatcherServlet.properties` 提供"零配置回退"；容器里显式注册的策略 bean 永远优先。
- Boot 4 用 `DispatcherServletAutoConfiguration` + `DispatcherServletRegistrationBean` 把 Servlet 3.0 动态注册 API 包装成可条件化的 bean 装配；WAR 场景经 SCL 汇入同一机制。
- 下一章进入正题：一个请求流经这些节点的完整一生。

---
# 四、一次请求的一生：从浏览器到 Controller 再回来（逐行级）

## 4.0 全景图

以一次最普通的 `GET /api/orders`（`@RestController` 返回 JSON）为例，全链路节点如下（★ = 应用可扩展窗口，对应第五章）：

```
浏览器 GET /api/orders
   │
   ▼ Tomcat 11（容器段，2.1 节）
Connector → CoyoteAdapter → Engine/Host/Context Valve → 构建 FilterChain
   │
   ▼ Filter 链（按 order 依次递归执行）
★ ① ServerHttpObservationFilter   （追踪起点：创建 Observation/Span，Boot 自动注册）
★ ② springSecurityFilterChain     （Security 委托 Filter：认证 → SecurityContextHolder ThreadLocal）
★ ③ CharacterEncodingFilter ...   （编码/CORS/自定义 Filter）
★ ④ 你自己的 OncePerRequestFilter
   │  chain.doFilter 到头
   ▼ DispatcherServlet（FrameworkServlet.processRequest）
   │  绑定 LocaleContextHolder / RequestContextHolder（ThreadLocal）
   ▼ doService（:829）
   │  FlashMap 回取、RequestPath 解析缓存
   ▼ doDispatch（:935）
   │  ⑤ checkMultipart（:947）          ★ MultipartResolver
   │  ⑥ getHandler（:951）              ★ HandlerMapping：@RequestMapping 匹配 → HandlerExecutionChain（含拦截器）
   │  ⑦ chain.applyPreHandle（:957）    ★ HandlerInterceptor.preHandle（顺序执行，可短路）
   │  ⑧ getHandlerAdapter（:962）       ★ HandlerAdapter：挑选执行器
   │  ⑨ ha.handle（:963）
   │     └ invokeHandlerMethod（RMA:885）
   │        ⑨.1 WebDataBinderFactory / ModelFactory 准备   ★ @InitBinder、@ModelAttribute
   │        ⑨.2 参数解析（InvocableHandlerMethod:200）     ★ HandlerMethodArgumentResolver（30+ 内置）
   │              └ @RequestBody → HttpMessageConverter    ★ RequestBodyAdvice
   │        ⑨.3 反射调用 Controller 方法                    （你的业务代码在这里执行）
   │        ⑨.4 返回值处理（ServletInvocableHandlerMethod:135）★ HandlerMethodReturnValueHandler
   │              └ @ResponseBody → writeWithMessageConverters ★ ResponseBodyAdvice / HttpMessageConverter
   │  ⑩ chain.applyPostHandle（:970）   ★ HandlerInterceptor.postHandle（逆序执行）
   │  ⑪ processDispatchResult（:980）   渲染视图 或 异常解析
   │     ├ 有异常 → processHandlerException（:1208）★ HandlerExceptionResolver / @ExceptionHandler
   │     └ render（:1267）              ★ ViewResolver → View.render
   │  ⑫ triggerAfterCompletion（:983/986）★ HandlerInterceptor.afterCompletion（逆序，必达）
   │
   ▼ 回程：FrameworkServlet finally —— resetContextHolders、requestCompleted、发布事件
   ▼ Filter 链回程（Observation 在此 stop）
   ▼ Tomcat 写回 socket → 浏览器
```

**记住这张图的三条"纵轴"**：(a) 请求对象的传递路径是线性的；(b) ThreadLocal 的生命周期是"Filter 链开始 ↔ 链结束"（容器线程）或"processRequest 进 ↔ 出"（Spring 绑定的上下文）；(c) ★ 扩展窗口的位置决定了它"能看见什么"（下一章的选型依据）。

## 4.1 容器段与 Filter 链（重复要点，补时间线）

进入 `DispatcherServlet.service()` 之前发生的事都在容器侧（2.1/2.2 节已展开）：字节流解析、`FilterChain` 递归执行。补充一个时间点：**Filter 链的"前半段"是请求处理中最靠前的应用代码执行点**——所有"必须在业务之前完成且对 404 也要生效"的事（追踪、安全、编码）都必须放这里，后文 5.2 节是核心论据。

## 4.2 进入 DispatcherServlet：doService 的预备动作

**【源码证据】**（`DispatcherServlet.java:829-879`）：`doService` 做四件准备，然后才进入主流程 `doDispatch`：

```java
protected void doService(HttpServletRequest request, HttpServletResponse response) throws Exception {
    logRequest(request);                                              // :830 TRACE 级请求日志
    Map<String, Object> attributesSnapshot = null;                    // :834 include 场景的属性快照
    if (WebUtils.isIncludeRequest(request)) { ... }

    request.setAttribute(WEB_APPLICATION_CONTEXT_ATTRIBUTE, getWebApplicationContext()); // :847 挂容器
    request.setAttribute(LOCALE_RESOLVER_ATTRIBUTE, this.localeResolver);                // :848 挂解析器

    if (this.flashMapManager != null) {                               // :850 Flash 作用域：redirect 后取回
        FlashMap inputFlashMap = this.flashMapManager.retrieveAndUpdate(request, response);
        if (inputFlashMap != null) {
            request.setAttribute(INPUT_FLASH_MAP_ATTRIBUTE, Collections.unmodifiableMap(inputFlashMap));
        }
        request.setAttribute(OUTPUT_FLASH_MAP_ATTRIBUTE, new FlashMap());
        request.setAttribute(FLASH_MAP_MANAGER_ATTRIBUTE, this.flashMapManager);
    }

    if (this.parseRequestPath) {                                      // :860 RequestPath 解析缓存（路径匹配前置）
        ServletRequestPathUtils.parseAndCache(request);
    }

    try {
        doDispatch(request, response);                                // :866
    }
    finally {
        if (!WebAsyncUtils.getAsyncManager(request).isConcurrentHandlingStarted()) {
            ...                                                       // :869 异步未开始才做 include 还原
        }
        if (this.parseRequestPath) {
            ServletRequestPathUtils.setParsedRequestPath(previousRequestPath, request); // :876
        }
    }
}
```

## 4.3 doDispatch：十二步主流程逐段解析

**【源码证据】**（`DispatcherServlet.java:935-1004`），按执行顺序切分：

```java
protected void doDispatch(HttpServletRequest request, HttpServletResponse response) throws Exception {
    HttpServletRequest processedRequest = request;
    HandlerExecutionChain mappedHandler = null;
    boolean multipartRequestParsed = false;

    WebAsyncManager asyncManager = WebAsyncUtils.getAsyncManager(request);   // :940 每请求一个异步管理器

    try {
        ModelAndView mv = null;
        Exception dispatchException = null;

        try {
            // ── ① multipart 预处理 ─────────────────────────────
            processedRequest = checkMultipart(request);            // :947 MultipartResolver 把 request 包一层
            multipartRequestParsed = (processedRequest != request);

            // ── ② 映射：找到 Handler（= Controller 方法）+ 拦截器链 ──
            mappedHandler = getHandler(processedRequest);          // :951 遍历 handlerMappings
            if (mappedHandler == null) {
                noHandlerFound(processedRequest, response);        // :953 404（或抛 NoHandlerFoundException）
                return;
            }

            // ── ③ 拦截器前置 ──────────────────────────────────
            if (!mappedHandler.applyPreHandle(processedRequest, response)) {
                return;                                            // :957 任一 preHandle 返回 false 即终止
            }

            // ── ④ 执行 ───────────────────────────────────────
            HandlerAdapter ha = getHandlerAdapter(mappedHandler.getHandler()); // :962 挑支持该 Handler 的适配器
            mv = ha.handle(processedRequest, response, mappedHandler.getHandler()); // :963 真正调用 Controller

            if (asyncManager.isConcurrentHandlingStarted()) {
                return;                                            // :965 异步已启动：本次 dispatch 结束，等 ASYNC 回环
            }

            applyDefaultViewName(processedRequest, mv);            // :969 无视图名时用 RequestToViewNameTranslator 补
            mappedHandler.applyPostHandle(processedRequest, response, mv); // :970 拦截器后置（逆序）
        }
        catch (Exception ex) {
            dispatchException = ex;                                // :973 业务/框架异常先记下
        }
        catch (Throwable err) {
            dispatchException = new ServletException("Handler dispatch failed: " + err, err); // :978 Error 也走异常解析
        }

        // ── ⑤ 收尾：异常解析 + 渲染 + afterCompletion ──────────
        processDispatchResult(processedRequest, response, mappedHandler, mv, dispatchException); // :980
    }
    catch (Exception ex) {
        triggerAfterCompletion(processedRequest, response, mappedHandler, ex);  // :983 保证拦截器必达回调
    }
    catch (Throwable err) {
        triggerAfterCompletion(processedRequest, response, mappedHandler,
                new ServletException("Handler processing failed: " + err, err)); // :986
    }
    finally {
        if (asyncManager.isConcurrentHandlingStarted()) {
            mappedHandler.applyAfterConcurrentHandlingStarted(processedRequest, response); // :993 异步代替 postHandle/afterCompletion
            asyncManager.setMultipartRequestParsed(multipartRequestParsed);
        }
        else {
            if (multipartRequestParsed || asyncManager.isMultipartRequestParsed()) {
                cleanupMultipart(processedRequest);                // :1000 multipart 临时文件清理
            }
        }
    }
}
```

`getHandler`（:1154-1168）遍历 `handlerMappings`，第一个返回非空 `HandlerExecutionChain` 的胜出；`noHandlerFound`（:1172）默认 `sendError(404)`（Boot 的 `/error` ERROR dispatch 由 `BasicErrorController` 接住）；`getHandlerAdapter`（:1185-1201）按 `supports(handler)` 遍历 `handlerAdapters`。

下面把 ②③④⑤ 四段各自深入。

## 4.4 映射段：@RequestMapping 的注册与匹配（HandlerMapping）

### 4.4.1 启动期注册：MappingRegistry

`RequestMappingHandlerMapping` 实现了 `ApplicationContextAware` 的初始化回调，在容器启动后期扫描所有 bean，把 `@RequestMapping` 方法收进一个**读写锁保护的注册表**：

**【源码证据】**（`spring-webmvc/.../handler/AbstractHandlerMethodMapping.java`）：

```java
// initHandlerMethods :217-222 —— afterPropertiesSet 触发，遍历所有 bean 名
protected void initHandlerMethods() {
    for (String beanName : getCandidateBeanNames()) {
        processCandidateBean(beanName);        // :220 isHandler 判断类上是否有 @Controller/@RequestMapping
    }
    handlerMethodsInitialized(...);
}

// MappingRegistry.register :602-636 —— 每个 @RequestMapping 方法的登记
public void register(T mapping, Object handler, Method method) {
    this.readWriteLock.writeLock().lock();
    try {
        HandlerMethod handlerMethod = createHandlerMethod(handler, method);  // :605 bean + method 打包
        validateMethodMapping(handlerMethod, mapping);                       // :606 重复映射 → "Ambiguous mapping"
        Set<String> directPaths = getDirectPaths(mapping);
        for (String path : directPaths) {
            this.pathLookup.add(path, mapping);                              // :609-612 路径 → 映射 的倒排索引
        }
        ...                                                                  // :617-625 命名策略 + CORS 配置登记
        this.registry.put(mapping, new MappingRegistration<>(mapping, handlerMethod, ...));
    }
    finally { this.readWriteLock.writeLock().unlock(); }
}
```

> `HandlerMethod` 是 MVC 的核心数据结构：把"Controller 实例（或 bean 名）+ Method + 参数元数据"打包，使后续所有组件（适配器、参数解析器、异常解析器）都面向它工作。`7.0` 的 API 版本化没有改变这个结构——`RequestMappingInfo` 多了一个 `VersionRequestCondition`（`spring-webmvc/.../mvc/condition/VersionRequestCondition.java`）。

### 4.4.2 请求期匹配：getHandlerInternal → lookupHandlerMethod

**【源码证据】**（同文件 ：372-377, :393-436）：

```java
@Override
protected @Nullable HandlerMethod getHandlerInternal(HttpServletRequest request) throws Exception {
    String lookupPath = initLookupPath(request);            // :373 解析出当前 Servlet 映射内的路径
    this.mappingRegistry.acquireReadLock();                 // :374 读锁（注册表并发读写：只锁查，不锁执行）
    try {
        HandlerMethod handlerMethod = lookupHandlerMethod(lookupPath, request);
        return (handlerMethod != null ? handlerMethod.createWithResolvedBean() : null); // :376 bean 名 → 实例
    }
    finally { this.mappingRegistry.releaseReadLock(); }
}

protected @Nullable HandlerMethod lookupHandlerMethod(String lookupPath, HttpServletRequest request) throws Exception {
    List<Match> matches = new ArrayList<>();
    List<T> directPathMatches = this.mappingRegistry.getMappingsByDirectPath(lookupPath); // :395 先走倒排索引
    if (directPathMatches != null) {
        addMatchingMappings(directPathMatches, matches, request);   // :397 逐个看 method/params/headers/version 是否也匹配
    }
    if (matches.isEmpty()) {
        addMatchingMappings(this.mappingRegistry.getRegistrations().keySet(), matches, request); // :400 兜底全量（含通配）
    }
    if (!matches.isEmpty()) {
        Match bestMatch = matches.get(0);
        if (matches.size() > 1) {
            matches.sort(new MatchComparator(getMappingComparator(request)));       // :406 按精确度排序（/users/me 优于 /users/{id}）
            bestMatch = matches.get(0);
            ...
            Match secondBestMatch = matches.get(1);
            if (comparator.compare(bestMatch, secondBestMatch) == 0) {              // :425 并列 → 歧义异常
                throw new IllegalStateException("Ambiguous handler methods mapped for '" + uri + "': {...}");
            }
        }
        request.setAttribute(BEST_MATCHING_HANDLER_ATTRIBUTE, bestMatch.getHandlerMethod()); // :429
        handleMatch(bestMatch.mapping, lookupPath, request);        // :430 把 URI 变量等放进 request 属性
        return bestMatch.getHandlerMethod();
    }
    else {
        return handleNoMatch(...);                                  // :434 404/405/415 等语义在此产生
    }
}
```

### 4.4.3 组装执行链：getHandler（AbstractHandlerMapping）

`RequestMappingHandlerMapping.getHandlerInternal` 返回的只是 `HandlerMethod`，把它变成"Handler + 拦截器链"的是父类 `AbstractHandlerMapping.getHandler`：

**【源码证据】**（`spring-webmvc/.../handler/AbstractHandlerMapping.java:544-575` + `:681-696`）：

```java
@Override
public final @Nullable HandlerExecutionChain getHandler(HttpServletRequest request) throws Exception {
    ApiVersionHolder versionHolder = initApiVersion(request);   // :545 ★ 7.0 新增：先解析 API 版本（header/query/path/media type）
    Object handler = getHandlerInternal(request);
    if (handler == null) { handler = getDefaultHandler(); }
    ...
    if (versionHolder.hasError() && !request.getDispatcherType().equals(DispatcherType.ERROR)) {
        throw versionHolder.getError();                          // :557 版本不支持 → 抛出（可被 @ExceptionHandler 接）
    }
    ...
    HandlerExecutionChain executionChain = getHandlerExecutionChain(handler, request);  // :568 组装链
    ...
}

protected HandlerExecutionChain getHandlerExecutionChain(Object handler, HttpServletRequest request) {
    HandlerExecutionChain chain = (handler instanceof HandlerExecutionChain hec ? hec : new HandlerExecutionChain(handler));
    for (HandlerInterceptor interceptor : this.adaptedInterceptors) {         // :686 容器里所有全局拦截器
        if (interceptor instanceof MappedInterceptor mappedInterceptor) {
            if (mappedInterceptor.matches(request)) {                        // :688 带路径规则的拦截器按路径筛选
                chain.addInterceptor(mappedInterceptor.getInterceptor());
            }
        }
        else {
            chain.addInterceptor(interceptor);
        }
    }
    ...
}
```

两个要点：拦截器在此**已经混编进执行链**（`HandlerInterceptor` 来自容器 bean；`@RestControllerAdvice` 类的"拦截器"在 4.6 节另一条路径）；`initApiVersion` 让 `@RequestMapping(version = "1.2+")` 在映射阶段就生效——**7.0 把"版本"提升为与 path/method 同级的映射维度**。

## 4.5 拦截器段：HandlerInterceptor 的执行契约

`HandlerInterceptor`（`spring-webmvc/.../servlet/HandlerInterceptor.java:80`）只有三个 default 方法：`preHandle`（:102，返回 false 短路）、`postHandle`（:129，Controller 之后、视图渲染之前）、`afterCompletion`（:154，渲染/异常之后必达）。执行契约在 `HandlerExecutionChain` 里：

**【源码证据】**（`spring-webmvc/.../servlet/HandlerExecutionChain.java:142-200`）：

```java
boolean applyPreHandle(HttpServletRequest request, HttpServletResponse response) throws Exception {
    for (int i = 0; i < this.interceptorList.size(); i++) {                 // 正序
        HandlerInterceptor interceptor = this.interceptorList.get(i);
        if (!interceptor.preHandle(request, response, this.handler)) {
            triggerAfterCompletion(request, response, null);                // :146 短路时也触发已完成部分的 afterCompletion
            return false;
        }
        this.interceptorIndex = i;                                          // :149 记录"走到哪了"
    }
    return true;
}

void applyPostHandle(...) throws Exception {
    for (int i = this.interceptorList.size() - 1; i >= 0; i--) {            // :160 逆序
        interceptor.postHandle(request, response, this.handler, mv);
    }
}

void triggerAfterCompletion(HttpServletRequest request, HttpServletResponse response, @Nullable Exception ex) {
    for (int i = this.interceptorIndex; i >= 0; i--) {                      // :172 只回调 preHandle 成功过的那些
        try { interceptor.afterCompletion(request, response, this.handler, ex); }
        catch (Throwable ex2) { logger.error(...); }                        // :178 单个失败不影响其他
    }
}
```

**三条必须记住的契约**（5.3 节选型的直接依据）：

1. `preHandle` 返回 false 时，链上"已成功的部分"仍会收到 `afterCompletion`（:146 + interceptorIndex 回溯）——所以"preHandle 里放了资源，afterCompletion 里清理"的配对是安全的。
2. **`postHandle` 对 `@ResponseBody`/REST 场景基本无用**：此时响应体已在 `ha.handle` 内写完（4.6 节），拦截器拿到的是已消费完的响应；要加工 JSON 应使用 `ResponseBodyAdvice`。
3. 异步启动后（`afterConcurrentHandlingStarted`，:186-200）`postHandle/afterCompletion` 本次不执行，等 ASYNC 回环的二次 doDispatch 走正常回调。

## 4.6 执行段：参数如何变成 Java 对象（HandlerAdapter）

### 4.6.1 两层委托：RequestMappingHandlerAdapter → ServletInvocableHandlerMethod

`getHandlerAdapter` 挑中 `RequestMappingHandlerAdapter`（唯一支持 `HandlerMethod` 的适配器），进入 `handleInternal` → `invokeHandlerMethod`：

**【源码证据】**（`spring-webmvc/.../mvc/method/annotation/RequestMappingHandlerAdapter.java:885-940`）：

```java
protected @Nullable ModelAndView invokeHandlerMethod(HttpServletRequest request,
        HttpServletResponse response, HandlerMethod handlerMethod) throws Exception {

    WebAsyncManager asyncManager = WebAsyncUtils.getAsyncManager(request);          // :888
    AsyncWebRequest asyncWebRequest = WebAsyncUtils.createAsyncWebRequest(request, response);
    asyncWebRequest.setTimeout(this.asyncRequestTimeout);                           // :890
    asyncManager.setTaskExecutor(this.taskExecutor);                                // :892 ★ Callable 用哪个线程池跑
    asyncManager.registerCallableInterceptors(this.callableInterceptors);
    asyncManager.registerDeferredResultInterceptors(this.deferredResultInterceptors);

    response = asyncWebRequest.getNativeResponse(HttpServletResponse.class);        // :898 强制用包装后的响应（生命周期规则）

    WebDataBinderFactory binderFactory = getDataBinderFactory(handlerMethod);       // :903 收集 @InitBinder/@ControllerAdvice
    ModelFactory modelFactory = getModelFactory(handlerMethod, binderFactory);      // :904 收集 @ModelAttribute

    ServletInvocableHandlerMethod invocableMethod = createInvocableHandlerMethod(handlerMethod);
    invocableMethod.setHandlerMethodArgumentResolvers(this.argumentResolvers);      // :908 注入参数解析器族
    invocableMethod.setHandlerMethodReturnValueHandlers(this.returnValueHandlers);  // :911 注入返回值处理器族
    invocableMethod.setDataBinderFactory(binderFactory);
    ...

    ModelAndViewContainer mavContainer = new ModelAndViewContainer();               // :917 模型/视图的"过程变量"
    mavContainer.addAllAttributes(RequestContextUtils.getInputFlashMap(request));
    modelFactory.initModel(webRequest, mavContainer, invocableMethod);

    if (asyncManager.hasConcurrentResult()) {                                       // :921 ★ ASYNC 回环时恢复结果
        Object result = asyncManager.getConcurrentResult();
        Object[] resultContext = asyncManager.getConcurrentResultContext();
        mavContainer = (ModelAndViewContainer) resultContext[0];
        asyncManager.clearConcurrentResult();
        invocableMethod = invocableMethod.wrapConcurrentResult(result);             // :931 结果伪装成"方法返回值"
    }

    invocableMethod.invokeAndHandle(webRequest, mavContainer);                      // :934 执行
    if (asyncManager.isConcurrentHandlingStarted()) { return null; }                // :935
    return getModelAndView(mavContainer, modelFactory, webRequest);                 // :939
}
```

`:921-932` 是理解异步的钥匙：**ASYNC 分发重入 doDispatch 后，适配器发现 `hasConcurrentResult()`，把异步线程算好的结果"注入"回同一处调用点**，后续参数解析（跳过）、返回值处理全部复用同一套代码——这就是为什么异步控制器不需要重写响应逻辑。

### 4.6.2 方法级执行：invokeAndHandle → 参数解析 → 返回值处理

**【源码证据】**（`spring-webmvc/.../mvc/method/annotation/ServletInvocableHandlerMethod.java:114-147` + `spring-web/.../method/support/InvocableHandlerMethod.java:171-184, 200`）：

```java
// ServletInvocableHandlerMethod.java:114
public void invokeAndHandle(ServletWebRequest webRequest, ModelAndViewContainer mavContainer,
        Object... providedArgs) throws Exception {
    Object returnValue = invokeForRequest(webRequest, mavContainer, providedArgs);  // :117
    setResponseStatus(webRequest);                                                  // :118 @ResponseStatus
    ...
    if (this.returnValueHandlers != null) {                                         // :133
        this.returnValueHandlers.handleReturnValue(returnValue, getReturnValueType(returnValue),
                mavContainer, webRequest);                                          // :135 挑选返回值处理器
    }
}

// InvocableHandlerMethod（spring-web）:171
public @Nullable Object invokeForRequest(NativeWebRequest request, @Nullable ModelAndViewContainer mavContainer,
        Object... providedArgs) throws Exception {
    Object[] args = getMethodArgumentValues(request, mavContainer, providedArgs);   // :174 参数解析（核心一步）
    ...
    return doInvoke(args);                                                          // :184 反射调用 Controller
}

// :200 getMethodArgumentValues —— for 循环遍历 argumentResolvers，第一个 supportsParameter 的胜出
```

内置参数解析器 30 余个，分四档（源码 `RequestMappingHandlerAdapter.getDefaultArgumentResolvers`，:644-687）：**注解式**（`@RequestParam/@PathVariable/@MatrixVariable/@RequestBody/@RequestPart/@RequestHeader/@CookieValue/@ExpressionValue/@SessionAttribute/@RequestAttribute`）→ **类型式**（`HttpServletRequest/Response`、`HttpEntity`、`Model`、`Errors`、`UriComponentsBuilder`、`ApiVersion`（7.0 新增，可直接把版本注入参数））→ **自定义**（`getCustomArgumentResolvers()`，通过 `WebMvcConfigurer.addArgumentResolvers` 注册）→ **兜底**（无注解 POJO 参数绑定 `ServletModelAttributeMethodProcessor(true)`）。

### 4.6.3 @RequestBody 与 @ResponseBody：消息转换双行道 + Advice 钩子

`@RequestBody` 由 `RequestResponseBodyMethodProcessor` 处理（读），`@ResponseBody` 也由它处理（写，`AbstractMessageConverterMethodProcessor` 子类）：

**【源码证据】**（`spring-webmvc/.../mvc/method/annotation/RequestResponseBodyMethodProcessor.java:195-213` + `AbstractMessageConverterMethodProcessor.java:205,332`）：

```java
// 读：Controller 方法被调用前
// RequestResponseBodyMethodProcessor.readWithMessageConverters
//   → AbstractMessageConverterMethodArgumentResolver：按 Content-Type 选 converter，逐字节读入
//     → RequestResponseBodyAdviceChain.afterBodyRead（:103-110）★ RequestBodyAdvice 钩子

// 写：Controller 方法返回后
public void handleReturnValue(@Nullable Object returnValue, MethodParameter returnType,
        ModelAndViewContainer mavContainer, NativeWebRequest webRequest) ... {      // :195
    mavContainer.setRequestHandled(true);
    writeWithMessageConverters(returnValue, returnType, inputMessage, outputMessage); // :213
}

// AbstractMessageConverterMethodProcessor.writeWithMessageConverters :205
//   → 内容协商（Accept 头 vs produces）选 MediaType → 选 converter → 序列化写出
//     → body = getAdvice().beforeBodyWrite(body, ...)                       // :332 ★ ResponseBodyAdvice 钩子
```

`RequestResponseBodyAdviceChain`（`spring-webmvc/.../annotation/RequestResponseBodyAdviceChain.java:47`）把所有 `@ControllerAdvice` 类中的 `RequestBodyAdvice`/`ResponseBodyAdvice`（按 `assignableTypes/basePackages/annotations` 过滤适用范围）串成链——**这是"统一加解密响应体、统一日志脱敏"的标准扩展位**。

## 4.7 渲染段：两条出口

- **REST 出口**（上节）：响应体已写完，`mv == null`（`setRequestHandled(true)`），`processDispatchResult` 不再渲染。
- **页面出口**：返回视图名/String 时走 `render`（`DispatcherServlet.java:1267-1309`）：`localeResolver.resolveLocale` 设置语言 → `resolveViewName` 遍历 `viewResolvers`（:1269-1295，`ContentNegotiatingViewResolver` 通常打头）拿到 `View` → `view.render(model, request, response)`（:1305，JSP/Thymeleaf/Freemarker 各自实现）。

## 4.8 异常段：processDispatchResult → processHandlerException

**【源码证据】**（`DispatcherServlet.java:1022-1060, 1208-1250`）：

```java
private void processDispatchResult(...) throws Exception {
    boolean errorView = false;
    if (exception != null) {
        if (exception instanceof ModelAndViewDefiningException mavDefiningException) {
            mv = mavDefiningException.getModelAndView();                    // :1029 特例：直接携带视图
        }
        else {
            Object handler = (mappedHandler != null ? mappedHandler.getHandler() : null);
            mv = processHandlerException(request, response, handler, exception); // :1035 进入解析器循环
            errorView = (mv != null);
        }
    }
    if (mv != null && !mv.wasCleared()) {
        render(mv, request, response);                                      // :1042 错误页也走同一渲染
        ...
    }
    ...
    if (mappedHandler != null) {
        mappedHandler.triggerAfterCompletion(request, response, null);      // :1060 afterCompletion 兜底
    }
}

protected @Nullable ModelAndView processHandlerException(...) throws Exception {  // :1208
    ...
    try {
        response.setHeader(HttpHeaders.CONTENT_TYPE, null);                 // :1215 重置已写一半的响应头
        response.resetBuffer();                                             // :1217 （已提交则放弃重置）
    }
    catch (IllegalStateException illegalStateException) { ... }

    ModelAndView exMv = null;
    if (this.handlerExceptionResolvers != null) {
        for (HandlerExceptionResolver resolver : this.handlerExceptionResolvers) {  // :1226 责任链
            exMv = resolver.resolveException(request, response, handler, ex);
            if (exMv != null) break;
        }
    }
    ...
}
```

默认注册的三个 resolver（`DispatcherServlet.properties`）及其分工：

1. `ExceptionHandlerExceptionResolver`：**`@ExceptionHandler` / `@ControllerAdvice`** 的执行者——先按方法级找，再按 `ControllerAdviceBean`（含 `basePackages/assignableTypes/annotations` 范围过滤，`ExceptionHandlerExceptionResolver.java:310` 构建 resolver 缓存）找，支持"异常继承匹配 + 最具体者胜出"。
2. `ResponseStatusExceptionResolver`：`@ResponseStatus` 注解的异常 / `ResponseStatusException`。
3. `DefaultHandlerExceptionResolver`：标准 Spring 异常 → 标准状态码（`HttpRequestMethodNotSupportedException` → 405 等）；7.0 支持输出 RFC 9457 `application/problem+json`。

若所有 resolver 都解析失败，异常上抛容器 → `response.sendError` → **ERROR dispatch** → Boot `BasicErrorController` 兜底。**注意：ERROR dispatch 会再走一遍 Filter 链（`DispatcherType.ERROR`）**——又一次呼应 2.2 节。

## 4.9 异步段：Callable/DeferredResult 的完整回环

**【源码证据】**（`spring-web/.../context/request/async/WebAsyncManager.java` + `StandardServletAsyncWebRequest.java`）：

以返回 `Callable<Order>` 为例，完整时序：

```
doDispatch #1（REQUEST 分发，容器线程 T1）
  └ getHandlerAdapter → invokeHandlerMethod
      └ 参数解析：CallableMethodReturnValueHandler 命中 → handleReturnValue
          └ WebAsyncManager.startCallableProcessing(callable, ...)      (:288)
              ├ state NOT_STARTED → ASYNC_PROCESSING                     (:309 CAS 防重入)
              ├ startAsyncProcessing(...)                                (:477)
              │    ├ asyncWebRequest.startAsync()                        (StandardServletAsyncWebRequest:140
              │    │    → request.startAsync()：Servlet 3.0 容器异步开启，:161 设置超时)
              │    └ 注册 AsyncListener（超时/错误/完成回调）
              └ taskExecutor.submit(() -> {                              (:892 配置的线程池 ★)
                     T2 执行 callable.call()；
                     setConcurrentResultAndDispatch(result)              (:491)
                       ├ concurrentResult = result                       (:84 volatile 字段)
                       └ asyncWebRequest.dispatch()                      (StandardServletAsyncWebRequest:166
                            → AsyncContext.dispatch()：以 ASYNC 分发重进 Filter 链 + DispatcherServlet)
                 })
  └ T1 的 doDispatch 发现 isConcurrentHandlingStarted() → return（线程归还）
```

```
doDispatch #2（ASYNC 分发，容器线程 T3）
  └ checkMultipart 跳过、getHandler 再次匹配同一 HandlerMethod
  └ applyPreHandle：拦截器 preHandle 再次执行（OncePerRequestFilter 若未跳过 ASYNC 也会再过）
  └ invokeHandlerMethod：hasConcurrentResult()==true（RMA:921）
      └ wrapConcurrentResult(result) → invokeAndHandle 直接把 result 当返回值处理
        （参数不再解析——结果已就绪）→ 返回值处理 → 渲染/写响应
  └ applyPostHandle / afterCompletion 正常执行；asyncManager 标记结束
```

关键结论（5.2/第六章的伏笔）：

1. **一次异步请求 = 两次完整分发**。Filter（未跳过 ASYNC 时）、HandlerMapping、preHandle 都会执行两次——追踪场景必须把两次分发归并到同一个 Span（`ServerHttpObservationFilter` 为此专门注册了 `ObservationAsyncListener`，见 5.2）。
2. **执行 Controller 逻辑的线程是 `taskExecutor`（T2），不是容器线程**——ThreadLocal 上下文在 T2 上不存在！这正是 6.4 节"显式传播"要解决的问题的经典场景（虚拟线程时代它依然成立，只是换了主角）。
3. `DeferredResult`/`SseEmitter`/`StreamingResponseBody` 是同一回环的不同"结果生产者"变体（`:398` `startDeferredResultProcessing`）。

## 4.10 本章小结（请求生命周期 12 步清单）

1. 容器解析字节流，构建 request/response 与 Filter 链；
2. Filter 链正序执行（追踪/安全/编码在此）；
3. `FrameworkServlet.processRequest` 绑定 LocaleContext/RequestContext ThreadLocal；
4. `doService` 准备 FlashMap、RequestPath；
5. `doDispatch`：multipart 预处理；
6. `getHandler`：API 版本解析 → `RequestMappingInfo` 匹配 → 拦截器混编成 `HandlerExecutionChain`；
7. `applyPreHandle` 正序前置（可短路，短路也保证 afterCompletion）；
8. `getHandlerAdapter` + `ha.handle`：数据绑定/校验 → 参数解析（含 @RequestBody 消息转换 + RequestBodyAdvice）→ 反射调用 Controller → 返回值处理（@ResponseBody 序列化 + ResponseBodyAdvice）；
9. `applyPostHandle` 逆序后置（REST 场景此时响应已写出）；
10. `processDispatchResult`：异常走 resolver 链（@ControllerAdvice 优先）→ 视图渲染（页面）或已直写（REST）；
11. `triggerAfterCompletion` 逆序必达回调 → finally 里 multipart 清理/异步移交；
12. `processRequest` finally：恢复 ThreadLocal、`requestCompleted`、发布 `ServletRequestHandledEvent` → Filter 链回程（Observation stop）→ 容器写回。

---
# 五、扩展点总表：在每个节点插手

## 5.1 节点 × 扩展手段全景表

第四章的每个节点都对应至少一种扩展手段。下表按"覆盖范围从大到小"排列——**扩展点的选型本质上是"覆盖范围 + 执行线程 + 可得信息"三要素的权衡**：

| # | 节点 | 扩展接口 | 注册方式 | 能看见什么 / 拿什么线程跑 | 覆盖 404、静态资源？ | 典型用途 |
|---|---|---|---|---|---|---|
| 1 | 容器工厂 | `WebServerFactoryCustomizer`（Boot） | bean | 启动期定制端口/线程池/SSL | —（启动期） | 定制容器 |
| 2 | Servlet/Listener/Filter 注册 | `ServletContextInitializer` 家族（Boot）/ SCL（WAR） | bean | 启动期 | — | 程序化装配 |
| 3 | Filter 链 | `jakarta.servlet.Filter`（推荐 `OncePerRequestFilter`） | `FilterRegistrationBean`（Boot）/ `@WebFilter` | 容器线程；请求字节流级；最先执行 | **是**（含 ERROR/ASYNC 再分发，需处理） | 追踪、安全、编码、CORS、限流 |
| 4 | DispatcherServlet 策略 | `HandlerMapping` / `HandlerAdapter` / `HandlerExceptionResolver` / `ViewResolver` / `MultipartResolver` / `LocaleResolver` / `FlashMapManager` | bean（`initStrategies` 自动收集） | 调度期 | 部分（无 handler 命中时 404 不进映射） | 换掉整个调度策略（罕见但可能） |
| 5 | 映射前后 | `HandlerInterceptor`（+ `MappedInterceptor` 路径规则） | `WebMvcConfigurer.addInterceptors` | 请求线程；已知 handler | 否（404/静态资源不经 MVC） | 登录态、权限、审计、监控埋点 |
| 6 | 方法参数 | `HandlerMethodArgumentResolver` | `WebMvcConfigurer.addArgumentResolvers` | 调用前 | 否 | 自定义参数注入（如 `@CurrentUser`） |
| 7 | 请求体读入后 | `RequestBodyAdvice`（`@ControllerAdvice`） | bean + `@ControllerAdvice` | 消息转换后、Controller 前 | 否 | 加解密、签名校验 |
| 8 | 数据绑定 | `Converter` / `Formatter` / `Validator` / `@InitBinder` | `WebMvcConfigurer.addFormatters`、`@InitBinder` 方法 | 绑定期 | 否 | 类型转换、白名单字段 |
| 9 | 返回值写出前 | `HandlerMethodReturnValueHandler` / `ResponseBodyAdvice` / `HttpMessageConverter` | `WebMvcConfigurer` 对应方法 / bean + `@ControllerAdvice` | 调用后、序列化前后 | 否 | 统一响应包装、脱敏、Etag |
| 10 | 异常 | `@ExceptionHandler` + `@ControllerAdvice` / `HandlerExceptionResolver` | bean | 异常发生后、渲染前 | 否（404 走容器 ERROR dispatch） | 统一错误码 |
| 11 | 视图渲染 | `ViewResolver` / `View` | bean / `WebMvcConfigurer.configureViewResolvers` | 渲染期 | 否 | 模板引擎接入 |
| 12 | 观测（官方推荐位） | `ObservationRegistry` + `ObservationConvention` | `WebMvcObservationAutoConfiguration` 已接 | 全链路 | **是**（Filter 级） | Micrometer Tracing/Metrics |

> 对照阅读：第 12 行的"官方推荐位"本质是第 3 行 + Boot 装配的组合（5.2 节）；`WebMvcConfigurer` 的 19 个 default 方法把 4~11 行的注册动作全部标准化了（`WebMvcConfigurer.java:61-255`）。

## 5.2 场景一：分布式链路追踪应该挂在哪个节点

**结论先行：挂在 Filter（且尽量第一个），不要挂在 HandlerInterceptor。** Spring 官方实现 `ServerHttpObservationFilter` 就是这么做的；理由全部来自第四章的链路知识。

### 5.2.1 为什么必须是 Filter

| 需求 | Filter 能满足 | Interceptor 为何不行 |
|---|---|---|
| 覆盖所有请求（含 404、静态资源、被 Security 短路的请求） | 是（容器级） | 否——`getHandler` 找不到 handler 时 `doDispatch:953` 直接 return，拦截器根本没机会跑；**这些恰恰是排查最多的失败请求** |
| 时序：trace context 必须在**一切应用代码**之前建立（Security 的认证日志、编码 Filter 都应属于本 Span） | 是（排第一位即可） | 否——Interceptor 至少在 Security Filter 之后，之前的日志/调用全部游离在 Span 外 |
| 异步请求的两段分发归并进同一 Span | 可通过 `shouldNotFilterAsyncDispatch=false` + `AsyncListener` 处理 | `afterConcurrentHandlingStarted`/二次 preHandle 需手工区分两次分发，且响应写完的真实终点（异步线程完成时）不在拦截器视野内 |
| 统计真实的端到端耗时（含 Filter 链自身开销） | 是 | 否——起点已晚 |

### 5.2.2 官方实现怎么做的（源码证据）

**【源码证据】`ServerHttpObservationFilter`**（`spring-web/src/main/java/org/springframework/web/filter/ServerHttpObservationFilter.java:97-132`）：

```java
@Override
protected boolean shouldNotFilterAsyncDispatch() {
    return false;                                   // :97-99 ASYNC 分发也要过（保持 Span 上下文）
}

@SuppressWarnings("try")
protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {

    Observation observation = createOrFetchObservation(request, response);   // :107 创建或复用（ASYNC 二次分发时）
    try (Observation.Scope scope = observation.openScope()) {                // :108 请求属性级作用域
        onScopeOpened(scope, request, response);
        filterChain.doFilter(request, response);                             // :110 放行整条链
    }
    catch (Exception ex) {
        observation.error(unwrapServletException(ex));                       // :113 记录异常
        response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
        throw ex;
    }
    finally {
        if (request.isAsyncStarted() && request.getDispatcherType() == DispatcherType.REQUEST) {
            request.getAsyncContext().addListener(new ObservationAsyncListener(observation)); // :119-120
            // ★ 异步：本次 dispatch 不结束 Span，改由 AsyncListener 在 complete/超时/错误时收尾
        }
        else if (!isAsyncDispatch(request)) {
            Throwable error = fetchException(request);
            if (error != null) { observation.error(error); }
            observation.stop();                                              // :129 非 ERROR 分发的正常收尾
        }
    }
}
```

这份实现浓缩了"追踪 Filter"的全部工程要点：

1. **Observation 的 scope 贯穿整个 Filter 链与 Servlet**——之后任何代码（含你 5.3 节要写的 Interceptor）都能拿到当前 Span；
2. **异步请求的收尾被"移交"给 `AsyncListener`**（4.9 节回环）——`stop()` 的时机是响应真正完成时，而不是第一次分发结束时；
3. ERROR dispatch 的异常从 request 属性（`WebUtils.ERROR_EXCEPTION_ATTRIBUTE`）取回，把容器兜底页也纳入观测。

**【源码证据】Boot 4 的注册位**（`module/spring-boot-webmvc/.../WebMvcObservationAutoConfiguration.java:60-68`）：

```java
@ConditionalOnWebApplication(type = Type.SERVLET)
@ConditionalOnClass({ DispatcherServlet.class, Observation.class, ObservationProperties.class })
@ConditionalOnBean(ObservationRegistry.class)
public final class WebMvcObservationAutoConfiguration {
    @Bean
    @ConditionalOnMissingFilterBean
    FilterRegistrationBean<ServerHttpObservationFilter> webMvcObservationFilter(ObservationRegistry registry, ...) {
        ...   // 注册为 FilterRegistrationBean，Order 让它跑在 Security 之前
```

引出来的 Observation（`io.micrometer:micrometer-observation`）向下对接 Micrometer Tracing 的 Tracer，Span/traceId 随日志（MDC）与导出器（OTLP → 后端）流动——完整机制见系列文档《Micrometer Tracing.md》《Spring观测体系设计.md》。

### 5.2.3 自己手写一个"追踪/耗时 Filter"的最小要点

```java
@Component
public class MyTraceFilter extends OncePerRequestFilter {
    @Override
    protected boolean shouldNotFilterAsyncDispatch() { return false; }   // 两次分发都处理（或手工合并）

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String traceId = Optional.ofNullable(req.getHeader("traceparent")).orElseGet(() -> UUID.randomUUID().toString());
        try (var scope = MDC.putCloseable("traceId", traceId)) {         // MDC 本身也是 ThreadLocal
            res.setHeader("X-Trace-Id", traceId);
            chain.doFilter(req, res);                                    // 前半：入口；后半：响应已写完
        }
    }
}
```

再配 `FilterRegistrationBean` 显式 `setOrder(Ordered.HIGHEST_PRECEDENCE)`（赶在 Security/Observation 之前）。**反例警示**：若把它写成 `HandlerInterceptor`，上述代码在 404/静态资源/Security 拒绝请求上全部静默失效。

## 5.3 场景二：把用户信息放进 ThreadLocal 应该挂在哪个节点

**结论先行：三种合法位置，覆盖范围与"多值得罪谁"各不相同——**

| 位置 | 时机（对应第四章节点） | 适用 | 不适用 |
|---|---|---|---|
| **A. Spring Security 已代劳** | `SecurityContextHolderFilter`（Filter 链，最前） | 绝大多数场景：`SecurityContextHolder.getContext().getAuthentication()` 就是用户信息 | 非 Security 项目 |
| **B. `HandlerInterceptor.preHandle`** | `doDispatch:957`（MVC 内、Controller 前） | 只关心 MVC 业务层；需要 `HandlerMethod` 做"按接口粒度"的上下文 | 需要 Filter 链内（404/静态资源）可见 |
| **C. 自定义 Filter** | `doDispatch` 之前 | 请求入口即需（含网关层透传的 trace/user header 落地） | 无 |

### 5.3.1 官方范本 A：Security 已经把用户放进了 ThreadLocal

**【源码证据】**（`spring-security/web/.../context/SecurityContextHolderFilter.java:48-85`，Security 7.0.7）：

```java
public class SecurityContextHolderFilter extends GenericFilterBean {
    private final SecurityContextRepository securityContextRepository;   // :52 从 session/header 恢复上下文
    private SecurityContextHolderStrategy securityContextHolderStrategy = ...; // :54 ThreadLocal 策略

    private void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain) ... {
        Supplier<SecurityContext> deferredContext = this.securityContextRepository.loadDeferredContext(request);
        this.securityContextHolderStrategy.setDeferredContext(deferredContext);  // 惰性加载 + ThreadLocal 绑定
        try {
            chain.doFilter(request, response);                                   // 整条链内 SecurityContextHolder 可用
        }
        finally {
            this.securityContextHolderStrategy.clearContext();                   // ★ finally 清理
        }
    }
}
```

看它的清理姿势：**`setDeferredContext` + `finally clearContext`**——与 `FrameworkServlet.processRequest` 的 init/reset（:997/:1012）完全同构。应用侧直接写工具类包 `SecurityContextHolder` 即可，**不要**再自建一套登录态 ThreadLocal。

### 5.3.2 官方范本 B：自己写 Interceptor 的正确姿势

```java
@Component
public class LoginUserInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String userId = request.getHeader("X-User-Id");            // 或从 SecurityContext/Token 解出
        if (userId != null) {
            UserContext.set(new LoginUser(userId));                // ThreadLocal.set
        }
        return true;                                               // false 则短路，afterCompletion 仍会触发
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
            Object handler, Exception ex) {
        UserContext.clear();                                       // ★ 必须清理：见下
    }
}

// 注册（WebMvcConfigurer）：
//   registry.addInterceptor(loginUserInterceptor).addPathPatterns("/api/**");
```

**为什么 `afterCompletion` 清理是铁律**：Tomcat 的平台线程池**复用**线程处理下一个请求（4.9 节 T1/T3 是池内线程）。若 preHandle 因某分支没有放值、或异常路径漏清，上一个请求的用户信息会"串"给下一个请求——这是经典安全漏洞。`HandlerExecutionChain.triggerAfterCompletion`（`HandlerExecutionChain.java:171-181`）的 interceptorIndex 回溯机制保证了：**只要 preHandle 成功过，afterCompletion 必达**（4.5 节契约 1），配对清理因此是安全的。

**为什么不用 `@PostConstruct`/构造器**：Controller 是单例 bean，与请求无关；请求级上下文只能绑在请求线程上。

### 5.3.3 什么时候必须下沉到 Filter

- 用户信息要在**整个 Filter 链**可见（例如后续的安全 Filter、限流 Filter 要读）；
- 要覆盖**非 MVC 请求**（静态资源、404、WebSocket 握手）；
- 信息来自请求头且与 handler 无关（透传场景）。
此时仿照 `SecurityContextHolderFilter` 写一个 `OncePerRequestFilter`（`finally` 里 clear），并用 `FilterRegistrationBean.setOrder` 排在 Security 之后、业务 Filter 之前。

### 5.3.4 一个隐藏陷阱：@Async/线程池里的"消失的用户"

任何把任务丢进线程池的代码（`@Async`、`CompletableFuture.supplyAsync`、4.9 节异步控制器的 `taskExecutor`）都会离开当前线程——ThreadLocal **不会**自动跟过去。解法有两类：

1. **显式捕获显式传递**：submit 前把用户值作为参数带过去（最朴素、最不容易错）；
2. **框架化传播**：Micrometer context-propagation 的 `ContextSnapshot`/`ThreadLocalAccessor` 机制 + Spring 的 `ContextPropagatingTaskDecorator`（把 `TaskExecutor` 的 Runnable 包一层快照）——这正是第六章的主场。

## 5.4 其他高频场景的节点对位（速查）

| 场景 | 推荐节点 | 一句话理由 |
|---|---|---|
| 统一响应体包装（`{code,msg,data}`） | `ResponseBodyAdvice` | 序列化前统一改写，Interceptor 的 postHandle 拿不到已写的响应 |
| 接口幂等（防重复提交） | `HandlerInterceptor.preHandle`（需要 handler 信息）或 Filter（不需要） | 要按接口粒度配规则选 Interceptor |
| 租户隔离（多租户 TenantId） | Filter 或 Security 后的 Interceptor；DB 层用 `AbstractRoutingDataSource` 读 ThreadLocal | 与 5.3 用户上下文同构 |
| 请求审计日志（含响应状态/耗时） | Filter（`OncePerRequestFilter` + response wrapper） | 要覆盖 404 与异步收尾 |
| 请求体统一加解密 | `RequestBodyAdvice` / `ResponseBodyAdvice` | 在消息转换的两侧挂钩 |
| 接口限流 | Filter（容器级，最早拒掉）或 Interceptor（需 handler 粒度） | 看限流键的粒度 |
| 上下文传给子线程 | `ContextPropagatingTaskDecorator` / 显式参数 | 第六章展开 |
| 实时推送/双向通信（聊天、大盘、进度） | SSE（`SseEmitter`）或 WebSocket（第七章） | 单向文本选 SSE；双向/订阅/定向选 WebSocket |
| 单次请求级缓存（同 key 只查一次） | `RequestAttributes`（Spring 自带请求作用域）或自定义 Interceptor | `request.setAttribute` 就是请求级"缓存" |

## 5.5 本章小结

- 选型三要素：**覆盖范围（要不要 404/静态资源/异步二次分发）→ 执行线程（容器线程 or 池化线程 or 异步线程）→ 可得信息（handler、参数、消息体）**。覆盖范围决定 Filter vs Interceptor，可得信息决定 Interceptor vs Advice/Resolver。
- 官方示范：追踪 = Filter（`ServerHttpObservationFilter`）；用户上下文 = Security Filter（ThreadLocal + finally 清理）；响应包装 = `ResponseBodyAdvice`。
- 两条铁律：ThreadLocal 必须有配对清理（线程复用是常态）；跨线程必须显式传播（下一章的主角）。

---
# 六、虚拟线程（JDK 25）下的 Spring MVC 正确姿势

## 6.1 虚拟线程机制 30 秒回顾（JEP 444 → JEP 506）

- **JDK 21（JEP 444）**：虚拟线程正式版。虚拟线程是"挂在载体线程（carrier，即平台线程）上的轻量执行流"——阻塞时（I/O、`sleep`、`wait`）把栈帧从载体线程**卸载（unmount）**到堆上，载体线程立刻去跑别的虚拟线程；恢复时再**挂载（mount）**回来。结果是：**阻塞的成本 ≈ 一块堆内存，而不是一根被占死的 OS 线程**。"thread-per-request"编程模型重新变得便宜。
- **JDK 24（JEP 491）**：`synchronized` 块内的阻塞不再导致载体线程被**钉住（pinning）**——21 时代最大的实践坑被拆除。仍会 pin 的只剩 native（JNI）帧。
- **JDK 25（2025-09-16，LTS）**：Loom 项目收尾的三件套——**JEP 506 `ScopedValue` 定稿**（ThreadLocal 的替代品，见 6.4）；**JEP 505 `StructuredTaskScope` 第五次预览**（结构化并发：子任务的生命周期严格嵌套在父任务内）；**JEP 502 `StableValue` 预览**（懒初始化的不可变值）。另有 JEP 519（紧凑对象头）等与并发间接相关的性能项。

本文的分析基线：**JDK 25 + Spring Framework 7.0.8 + Boot 4.0.8 + Tomcat 11**（官方支持的 JDK 组合为 17/21/25，7.0 GA 公告口径）。

## 6.2 容器接入：Boot 4 的一键开关链

**先说白话**：开启只需一个配置 `spring.threads.virtual.enabled=true`，背后是一条清晰的装配链，最后落到 Servlet 容器的线程池替换。

**【源码证据】**（Boot 4.0.8）：

```
spring.threads.virtual.enabled=true
   │ Threading.VIRTUAL（core/spring-boot/.../thread/Threading.java:49 读取开关）
   ▼ @ConditionalOnThreading(Threading.VIRTUAL)
TomcatWebServerConfiguration.tomcatVirtualThreadsProtocolHandlerCustomizer()   (:53-57)
   │
   ▼ TomcatVirtualThreadsWebServerFactoryCustomizer（module/spring-boot-tomcat/...:37-40）
factory.addProtocolHandlerCustomizers((protocolHandler) ->
        protocolHandler.setExecutor(new VirtualThreadExecutor("tomcat-handler-")));
```

效果：**Tomcat 不再用 200 大小的平台线程池处理请求，而是每个请求（从 Filter 链到 Servlet 再到响应写完）一个专用虚拟线程**。Jetty 有对应的 `JettyVirtualThreadsWebServerFactoryCustomizer`。

理解三个边界：

1. **替换的是"请求处理执行器"**。Tomcat 的 NIO Poller、Acceptor 仍是少量平台线程——虚拟线程只接管"请求业务处理"这一段（正好是 2.1 节管道里 Valve 以下的部分）。
2. **容器内的阻塞全部变便宜**，包括 JDBC 调用、下游 HTTP、文件读写——这是虚拟线程对 MVC 的最大红利。
3. **外部资源池不会跟着变**：数据库连接池、文件句柄、下游服务的连接上限都还是瓶颈——虚拟线程让"等待"免费，但**不会创造资源**。

## 6.3 对 MVC 意味着什么：doDispatch 一行都不用改

第四章解析的整条链路（`doDispatch` → 参数解析 → Controller → 返回值处理）是**阻塞式代码**，运行在"请求线程"上。虚拟线程时代这条链路的每一步阻塞都会自动 unmount——**框架代码零改动**。这也是 Spring 官方对 MVC + 虚拟线程的态度：不需要新编程模型，"thread-per-request" 回归。

真正需要重新审视的是**为了对抗"线程昂贵"而引入的那些机制**：

| 机制 | 平台线程时代 | 虚拟线程时代（JDK 25） | 建议 |
|---|---|---|---|
| 长调用占容器线程 | 痛点：200 线程池被慢下游占满 | 每请求一个 VT，占着也不贵 | **直接阻塞调用即可**，不必为省线程而异步化 |
| 控制器返回 `Callable`（`WebAsyncManager.startCallableProcessing`，4.9 节） | 把业务挪出容器线程到 `taskExecutor` | 价值大幅下降——业务线程与请求线程都便宜了。但语义仍是"请求与业务解耦"，且 `taskExecutor`（Boot 下 `applicationTaskExecutor`）在开关开启时也已是虚拟线程执行器 | 保留的旧代码不用改；新代码优先直接阻塞 |
| `DeferredResult`/`SseEmitter`/`StreamingResponseBody` | 慢下游/推送场景 | **仍有价值**：`DeferredResult` 是"等外部事件回调再写响应"（零忙等）；SSE/流式是协议能力，与线程贵贱无关 | 按需保留 |
| `@Async` | 共享池化 `TaskExecutor` | Boot 开关后 `applicationTaskExecutor` 自动变为虚拟线程执行器；`@Async` 方法每次在独立 VT 上跑 | 直接受益，无需改代码 |
| `CompletableFuture` 默认池 | `ForkJoinPool.commonPool` | 依旧平台线程——**虚拟线程不会自动传染给所有线程池** | CPU 型任务无所谓；IO 型任务应显式用 `Executors.newVirtualThreadPerTaskExecutor()` |
| 结构化并发 `StructuredTaskScope`（JEP 505，预览） | 不存在 | 并行扇出多个下游调用的首选（比手工线程池安全，超时/取消传播内建） | 查询编排场景可试用（预览 API，生产谨慎） |
| WebFlux（事件循环） | 无阻塞才能高并发 | **不需要**虚拟线程；两者是不同范式 | 不要在 WebFlux 应用里开这个开关 |

## 6.4 ThreadLocal 在虚拟线程时代的几种命运

第四章 5.3 节建立的"ThreadLocal 用户上下文"模式，在虚拟线程下的变化比 MVC 本身大得多——逐条拆解。

### 6.4.1 命运一：池化复用消失，"忘清理"的后果变了

平台线程池时代，ThreadLocal 脏数据会**串给下一个请求**（5.3 节铁律的由来）。虚拟线程时代，**每个请求一个全新的虚拟线程、用完即弃**——忘记 `afterCompletion` 清理不再串数据。

但这不等于可以不清理，三个理由：

1. 代码要**双模式运行**（平台线程池仍是绝大多数存量部署的默认），清理是唯一的通用安全姿势；
2. **`InheritableThreadLocal` 反而更危险了**（见 6.4.2）；
3. 大对象长期驻留 ThreadLocal 的"缓存收益"消失了：平台线程池里 200 个 `SimpleDateFormat` 复用十年；虚拟线程模式下每请求新建、随线程销毁——原本靠池化摊销成本的对象（格式化器、Builder、大缓冲区）现在**每请求付出构造代价**。替代方案是**无状态/不可变**（`DateTimeFormatter` 本就线程安全）或放到真正的共享缓存里。

### 6.4.2 命运二：InheritableThreadLocal 成为新的坑

`InheritableThreadLocal` 在**线程创建时**复制父线程的值。平台线程池时代它是禁用的（池化线程的"父"早已不可考）；虚拟线程时代它有一个更隐蔽的失败模式：**虚拟线程由执行器按需孵化，"父线程"取决于谁提交了任务**——在 Tomcat 里提交请求处理任务的线程不是上一个请求的线程就是 Acceptor，继承到的值**与当前请求无关甚至来自别的用户**。

Spring 对此早有防御：`FrameworkServlet` 的 `threadContextInheritable` 默认 **false**（`FrameworkServlet.java:207`，`initContextHolders:1058-1061` 用它决定是否用 `NamedInheritableThreadLocal`），javadoc 明确警告仅适用于"自行管理子线程生命周期"的场景。**结论：请求上下文传播不要碰 InheritableThreadLocal，虚拟线程时代尤甚。**

### 6.4.3 命运三：显式传播成为正解——Micrometer context-propagation 机制

ThreadLocal 的本质缺陷是"**绑定线程而不绑定任务**"。虚拟线程时代任务换线程是常态，正确姿势是把"哪些 ThreadLocal 需要传播"**显式登记**，在任务提交时抓快照、执行前恢复。这套机制的落地库就是 Micrometer 的 `context-propagation`（Framework 6.1 引入依赖，7.0 继续使用）：

```
ThreadLocalAccessor（登记钩子）                —— 每个 ThreadLocal 一个：key()、getValue()、setValue(v)、setValue()
ContextRegistry.getInstance()                 —— 全局注册表
ContextSnapshotFactory.captureAll()           —— 抓快照（当前线程所有已登记 ThreadLocal 的值）
snapshot.wrap(runnable) / snapshot.setThreadLocals() —— 任务包装 / 目标线程恢复
```

**【源码证据】Framework 7.0.8 里的四处官方接线**（它们共同说明：**Spring 自己的请求上下文也是"被传播对象"**）：

| 类 | 位置 | 作用 |
|---|---|---|
| `RequestAttributesThreadLocalAccessor` | `spring-web/.../context/request/RequestAttributesThreadLocalAccessor.java:36`（`@since 6.2`） | 把 `RequestContextHolder` 接入 ContextRegistry；内置 `SnapshotServletRequestAttributes`（:78-143）让异步线程在请求收尾（`requestCompleted`）后仍能**只读**访问请求属性 |
| `LocaleContextThreadLocalAccessor` | `spring-context/.../i18n/LocaleContextThreadLocalAccessor.java`（`@since 6.2`） | 同上，针对 `LocaleContextHolder` |
| `ContextPropagatingTaskDecorator` | `spring-core/.../task/support/ContextPropagatingTaskDecorator.java:39` | `TaskDecorator` 实现：`submit(runnable)` 前 `captureAll()` 抓快照，任务执行前 `wrap`——给 `ThreadPoolTaskExecutor` 装上它，`@Async`/线程池里的代码就能看到请求上下文 |
| `PropagationContextElement` | `spring-core/PropagationContextElement.java:60`（`@since 7.0`） | Kotlin 协程场景：协程挂起/恢复跨越线程时恢复已登记的 ThreadLocal（含 Reactor Context 桥接） |

而 4.9 节异步控制器里 `FrameworkServlet.processRequest:995` 注册的 `RequestBindingInterceptor` 是**同一问题的老式手工解法**（异步任务前后手工恢复 RequestAttributes）——新旧两条线索证明：**"跨线程传播请求上下文"从来都是 MVC 的核心难题，虚拟线程只是把它从"少数异步场景"放大为"日常"**。

给 5.3 节的 `UserContext` 接入这套机制的样板：

```java
public class UserContextThreadLocalAccessor implements ThreadLocalAccessor<LoginUser> {
    public static final String KEY = "loginUser";
    public Object key() { return KEY; }
    public LoginUser getValue() { return UserContext.get(); }          // 读当前线程
    public void setValue(LoginUser v) { UserContext.set(v); }
    public void setValue() { UserContext.clear(); }
}
// 注册（一次即可）：ContextRegistry.getInstance().registerThreadLocalAccessor(new ...);
// 之后：任何用 ContextPropagatingTaskDecorator 装饰过的池、Observation 的 scope 传播，都会带上 UserContext。
```

### 6.4.4 命运四：ScopedValue（JEP 506，JDK 25 定稿）——未来的主流，当下的配角

`ScopedValue` 是 JDK 给"请求级上下文"的官方新答案：**不可变绑定、有明确词法/动态作用域、作用域结束自动失效、无需手工清理、读多写少场景无 map 查找开销**：

```java
private static final ScopedValue<LoginUser> CURRENT_USER = ScopedValue.newInstance();

// 在 Filter 中建立作用域（作用域内所有代码可见，出作用域自动失效）
ScopedValue.where(CURRENT_USER, loginUser).run(() -> {
    chain.doFilter(request, response);      // 注意：同一同步调用链内可见
});

// 任意深处读取：
LoginUser user = CURRENT_USER.get();        // 未绑定会抛 NoSuchElementException（或 orElse 惯用法）
```

两点冷静判断（均基于本文源码证据）：

1. **Spring Framework 7.0.8 自身尚未使用 ScopedValue**：在 spring-core/web/webmvc/context 中检索不到 `ScopedValue` 引用；`RequestContextHolder`、`LocaleContextHolder`、Security 的 `SecurityContextHolder` 仍全部基于 ThreadLocal。**现阶段的正确姿势依旧是 ThreadLocal + 显式传播**，ScopedValue 适合应用在自己的代码里先行试点（与 ThreadLocal 桥接仍需 `ThreadLocalAccessor` 这类机制，因为第三方库读的是 ThreadLocal）。
2. ScopedValue **不跨线程自动传播**：ASYNC 分发（4.9 节）、线程池任务上仍需显式重建作用域——它解决的是"作用域内共享与自动清理"，不是"传播"。

## 6.5 虚拟线程实践清单（MVC 应用视角）

1. **该不该开**：I/O 密集 + 高并发 + 线程池经常打满 → 开；CPU 密集 → 无收益；已用 WebFlux → 不要混用。
2. **配套检查**：连接池（HikariCP 等）大小不会因虚拟线程变大——并发更高时先测资源池；下游限流同理。
3. **线程池语义检查**：`CompletableFuture` 默认池、手工 `new FixedThreadPool` 不会自动虚拟化；I/O 型改 `Executors.newVirtualThreadPerTaskExecutor()`。
4. **上下文传播改造**：`UserContext` 等注册 `ThreadLocalAccessor`；给共享 `ThreadPoolTaskExecutor` 配 `ContextPropagatingTaskDecorator`；验证异步控制器（Callable）与 `@Async` 路径上的用户/租户/traceId 是否仍在。
5. **诊断工具**：`jcmd <pid> Thread.dump_to_file -format=json`（支持百万级虚拟线程、含载体线程信息）；JFR 的虚拟线程事件。若还停留在 JDK 21 且大量使用 `synchronized`，关注 JEP 491 前后的 pinning 行为（`-Djdk.tracePinnedThreads` 在 21 可用，24 起该问题基本消失）。
6. **观测**：Boot 开关后 `Observation`/Tracing 链路不变（`ServerHttpObservationFilter` 在虚拟线程上照常工作）；日志 MDC 依赖 traceId 的 ThreadLocal 传播——同 6.4.3 配置。

## 6.6 本章小结

- 开关链：`spring.threads.virtual.enabled` → `@ConditionalOnThreading` → `TomcatVirtualThreadsWebServerFactoryCustomizer` → `ProtocolHandler.setExecutor(VirtualThreadExecutor)`——**每个请求一个虚拟线程跑完整条 MVC 链**。
- `doDispatch` 及一切阻塞式 MVC 代码零改动受益；为"省线程"而生的 `Callable` 异步价值下降，`DeferredResult`/SSE 等事件驱动形态保留。
- ThreadLocal 的四种命运：**复用消失（清理不再救串数据，但仍要写）、InheritableThreadLocal 更危险、显式传播成为正解（context-propagation + 四处 Spring 官方接线）、ScopedValue 定稿但框架暂未采用**。
- 一句话：虚拟线程没有取消 4.9 节的异步回环问题，而是把它"常态化"了——**凡是跨线程，就显式传播；凡是绑定线程，就配对清理**。

---

# 七、WebSocket：什么场景用、怎么用（场景全景与示例）

## 7.1 一句话定位：从"请求-响应"到"常连双工"

HTTP 请求-响应模型有三个先天限制：**半双工且服务端盲**（一次交互只能由客户端发起，服务端想"主动说话"没有任何通道）；**每次通信都是完整 HTTP**（头部冗余，keep-alive 缓解了握手但改变不了"问才有答"）；**同步耦合**（客户端不问，服务端不能答）。

WebSocket 的解法：**先借道 HTTP 完成一次握手（`101 Switching Protocols`），随后协议切换为 WebSocket 帧**——一条全双工、长连接、帧级开销的双向通道。这正是 2.4 节 Servlet 3.1 `upgrade(HttpUpgradeHandler)` 协议升级机制的规范级应用；Framework 7.0.8 的对接基线是 **Jakarta WebSocket 2.2**（`framework-platform/framework-platform.gradle:78-79`），容器实现为 `tomcat-embed-websocket 11`（同文件 :100）。

与本文主线的衔接一句话：**握手请求是一次标准的 MVC 请求（第四章的一切都作用于它这一次），握手成功后连接升级、脱离 HTTP 语义，后续帧不再经过 DispatcherServlet**——7.6 节逐条展开这个"边界"对追踪、ThreadLocal、线程模型的影响。

## 7.2 什么时候该用：六类典型场景与三类"不该用"

判断标准只有一句话：**通信模式是否为"服务端需要在客户端不请求时说话"，或"双方需要高频低延迟地互说"。** 满足其一，才考虑 WebSocket。

### 7.2.1 六类典型场景

| # | 场景 | 通信模式 | 为什么 WebSocket 合适 | 现实例子 |
|---|---|---|---|---|
| 1 | 即时通讯/在线客服/聊天室 | 双向、任意时刻、按房间/人路由 | 服务端要把 A 的消息实时转给 B/C，HTTP 只能靠轮询模拟 | 企业 IM、客服工作台、直播间弹幕 |
| 2 | 实时监控大盘/行情推送 | 服务端高频主动推、客户端订阅频道 | 数据源在服务端（指标/行情），客户端只是订阅者 | 运维大盘、K 线行情、赛事比分 |
| 3 | 协同编辑/白板/联机游戏 | 双向、亚秒级延迟、常含二进制帧 | 状态同步依赖低延迟双向通道，游戏帧是二进制 | 在线文档、协作白板、棋牌对战 |
| 4 | "状态反转"通知 | 服务端主动、一次性 | 支付结果/扫码登录/审批完成发生在服务端，客户端无从得知 | 扫码登录、PC 端等移动支付回调 |
| 5 | 长任务进度推送 | 服务端主动、周期性 | 上传/导入/训练的进度条由服务端驱动 | 文件导入进度、CI 构建日志流 |
| 6 | IoT/设备遥测与指令下发 | 双向、高频、大量小消息 | 设备上行遥测与服务端下发指令共用一条连接 | 网关遥测、智能家居控制 |

### 7.2.2 三类"不该用"（比"该用"更重要）

| 不该用的情形 | 更合适的方案 | 理由 |
|---|---|---|
| 低频数据更新（分钟级），客户端在线即可 | HTTP 轮询/长轮询 | 建连、心跳、断线重连、多实例粘性路由的成本超过收益 |
| 服务端→客户端**单向文本**推送 | **SSE（EventSource）** | HTTP 原生（MVC 里就是 4.9 节的 `SseEmitter`）、浏览器内建自动重连、代理网关零改造；通知/进度类首选 |
| 需要 HTTP 语义：缓存/CDN/标准状态码 | 普通 REST API | WebSocket 无 HTTP 缓存与缓存路由概念 |

### 7.2.3 三方案速查：轮询 / SSE / WebSocket

| 维度 | 轮询 | SSE | WebSocket |
|---|---|---|---|
| 方向 | 单向（客户端拉） | 单向（服务端推） | **双向** |
| 协议开销 | 每次完整 HTTP | 一次 HTTP + 文本帧 | 一次握手 + 帧 |
| 断线重连 | 应用自理 | **浏览器内建** | 应用自理（或 SockJS） |
| 二进制帧 | 无 | 无 | **有** |
| 代理/网关兼容 | 最好 | 好 | 需支持 `Upgrade` 头透传 |
| Spring MVC 支撑 | Controller | `SseEmitter`（4.9 节） | spring-websocket（本章） |

## 7.3 Spring 的三层技术选择（在哪一层开发）

spring-websocket 模块对 MVC 的依赖关系：`api` 依赖 context/core/web；STOMP 能力是 **optional 依赖 spring-messaging**，MVC 集成是 optional 依赖 spring-webmvc（`spring-websocket/spring-websocket.gradle` 实证）。三个层级：

| 层级 | 编程模型 | 适合 | 代价 |
|---|---|---|---|
| L1 原生 WebSocket | `WebSocketConfigurer` + `WebSocketHandler`（四生命周期回调） | 自定义协议、二进制帧（游戏/行情/网关） | 自己做消息路由、心跳、会话管理 |
| L2 STOMP 子协议 | `@EnableWebSocketMessageBroker` + `@MessageMapping/@SendTo/@SendToUser` + `SimpMessagingTemplate` | 聊天/订阅发布/需要 destination 路由与用户定向 | 多学一个子协议与通道模型 |
| L3 JSR 注解 | `@ServerEndpoint` + `ServerEndpointExporter` | 极简场景 | 脱离 Spring 装配体系（依赖注入靠 `SpringConfigurator` 兜底） |

外加 SockJS 兜底传输（`registry.addEndpoint(...).withSockJS()`）：在浏览器/代理不支持 WebSocket 时降级为 XHR/iframe 轮询。**选择口诀：会话内只有"收发"选 L1；有"房间/订阅/定向给某人"语义选 L2；demo 级选 L3。**

## 7.4 示例一：原生 WebSocketHandler——监控大盘广播（场景 #2）

**【服务端代码】**（L1）：

```java
@Configuration
@EnableWebSocket
public class DashboardWsConfig implements WebSocketConfigurer {

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new MetricBoardHandler(), "/ws/metrics")     // 握手 URL
                .addInterceptors(new UserHandshakeInterceptor())         // ★ 握手期物化用户（7.6 节）
                .setAllowedOriginPatterns("https://*.example.com");      // 必配，防跨站 WebSocket 劫持
    }

    static class MetricBoardHandler extends TextWebSocketHandler {
        private static final Set<WebSocketSession> SESSIONS = ConcurrentHashMap.newKeySet();

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {   // 连接建立
            SESSIONS.add(session);
        }
        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            // 客户端上行：订阅/退订某指标频道（自定义协议，自己解析）
        }
        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            SESSIONS.remove(session);                                        // 必须清理，防会话泄漏
        }

        // 服务端主动广播：由定时任务/指标回调触发（这就是 HTTP 做不到的"主动说话"）
        void broadcast(String json) {
            for (WebSocketSession s : SESSIONS) {
                try { s.sendMessage(new TextMessage(json)); }
                catch (IOException ignored) { }
            }
        }
    }
}
```

前端一行：`const ws = new WebSocket("wss://host/ws/metrics"); ws.onmessage = e => render(e.data);`

要点（均对应源码接口）：

1. `WebSocketHandler` 四个生命周期回调：`afterConnectionEstablished`（:43）、`handleMessage`（:50）、`handleTransportError`（:57）、`afterConnectionClosed`（:67）（`spring-websocket/.../socket/WebSocketHandler.java`）；
2. 同一 session 的并发发送要用 `ConcurrentWebSocketSessionDecorator` 包装（发送缓冲上限 + 超时，防慢消费者拖垮线程）；
3. 示例里的 `SESSIONS` 全局集合是"房间语义"的雏形——多实例部署它必须是集群共享状态（Redis/外部代理），这正是推荐 L2 的原因。

## 7.5 示例二：STOMP——群聊 + 定向推送 + 定时大盘（场景 #1/#4/#5）

L2 把"房间、订阅、定向给某人"变成注解语义：

**【服务端代码】**（L2）：

```java
@Configuration
@EnableWebSocketMessageBroker
public class ChatWsConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/chat").setAllowedOriginPatterns("https://*.example.com").withSockJS();
    }
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");   // 客户端→服务端：/app/...
        registry.enableSimpleBroker("/topic", "/queue");      // 服务端→客户端：内置简单代理
        // 集群生产换：registry.enableStompBrokerRelay(...) → RabbitMQ/ActiveMQ 代理
    }
}

@Controller
public class ChatController {

    @MessageMapping("/room/{roomId}")                  // 客户端发往 /app/room/{roomId}
    @SendTo("/topic/room/{roomId}")                    // 广播给订阅者（群聊）；{roomId} 占位符支持模板展开
    public ChatMessage say(@DestinationVariable String roomId, ChatMessage msg, Principal user) { ... }

    @MessageMapping("/room/{roomId}/private")
    @SendToUser("/queue/reply")                        // ★ 只回给发送者本人（点对点回执）
    public ChatReply echo(ChatMessage msg) { ... }

    @MessageExceptionHandler
    @SendToUser("/queue/errors")                       // STOMP 层异常定向回执
    public String onError(Exception ex) { return ex.getMessage(); }
}
```

**服务端任意代码主动推**（示例二的另一半，场景 #4/#5）——注入 `SimpMessagingTemplate`（spring-messaging），在任意业务方法、`@Scheduled`、事件监听中调用：

```java
messagingTemplate.convertAndSend("/topic/room/1", newChatMsg);           // 广播（大盘/群聊）
messagingTemplate.convertAndSendToUser(userId, "/queue/progress", 42);   // 定向推送（任务进度条）
```

前端（stomp.js）：`stompClient.subscribe("/topic/room/1", msg => ...)` 订阅；`stompClient.publish({destination: "/app/room/1", body: ...})` 发送。

要点与源码证据：

1. `@SendTo` 目的地支持占位符模板——`SendToMethodReturnValueHandler` 用 `PropertyPlaceholderHelper` + `expandTemplateVars` 展开（spring-messaging `.../annotation/support/SendToMethodReturnValueHandler.java:75,197,213`）；
2. `@EnableWebSocketMessageBroker` 导入 `DelegatingWebSocketMessageBrokerConfiguration`，其父类 `WebSocketMessageBrokerConfigurationSupport` 构建 **clientInboundChannel / clientOutboundChannel 两条内存消息通道**并组装 `SubProtocolWebSocketHandler`（`spring-websocket/.../config/annotation/WebSocketMessageBrokerConfigurationSupport.java:74-78, 116-119`），inbound/outbound 各配独立线程执行器（:156-157）——**STOMP 帧处理不在容器请求线程上**，与第六章的线程讨论直接相关；
3. Boot 4 自动配置：`module/spring-boot-websocket/.../WebSocketMessagingAutoConfiguration.java:63-66`（`@ConditionalOnClass({WebSocketMessageBrokerConfigurer, DelegatingWebSocketMessageBrokerConfiguration})`，含 Jackson 消息序列化装配）。

## 7.6 与本文主线的四个交集（握手之后，世界变了）

**1. 与第四章：握手是一次完整的 MVC 请求，帧不是。**
Spring 路线（L1/L2）的握手请求走完整条链：容器 Filter 链 → `DispatcherServlet` → **`webSocketHandlerMapping`**（`WebSocketConfigurationSupport.java:43` 注册的 `SimpleUrlHandlerMapping` bean，被 3.2.3 节 `initHandlerMappings:506` 的 detectAll 机制收编）→ `HttpRequestHandlerAdapter`（spring-webmvc `HttpRequestHandlerAdapter.java:42,50`，`DispatcherServlet.properties` 默认四个 adapter 之一）→ `WebSocketHttpRequestHandler.handleRequest`（:160）→ `handshakeHandler.doHandshake`（:177，内部执行 `HandshakeInterceptor.beforeHandshake` 链、确定子协议、检查 Origin）→ 容器执行 101 升级（Tomcat `WsHttpUpgradeHandler`，即 2.4 节的 `upgrade()`）。**此后 WebSocket 帧直接在容器升级处理器与 Spring 的 handler/通道之间流动，不再经过 Filter 链与 DispatcherServlet。**
L3（`@ServerEndpoint`）更彻底：端点由容器原生注册（`ServerEndpointExporter`，standard 包），握手完全归容器，Spring 只负责端点类里的依赖注入。

**2. 与追踪（5.2 节）：Filter 级观测只覆盖"握手这一次"。**
握手是 HTTP，Observation/追踪 Filter 正常生效（Span 记录的是握手过程与结果）；**会话内的每条帧消息不经过 Filter 链**——需要消息级追踪时，在 STOMP 层给 inbound/outbound 通道挂 `ChannelInterceptor`，或至少让 traceId 随 STOMP 帧头传播。

**3. 与用户上下文（5.3 节）：ThreadLocal 的"最后一次机会"在握手。**
会话是长生命周期对象，`handleMessage` 与 STOMP 控制器方法跑在通道执行器线程上——**没有任何请求 ThreadLocal 可言**（`RequestContextHolder` 是 4.2 节 processRequest 绑定、请求结束即恢复的）。正确姿势：在 `HandshakeInterceptor.beforeHandshake`（`HandshakeInterceptor.java:48`）把用户物化进 `attributes`，回调中经 `session.getAttributes()` 读取；STOMP 场景用 CONNECT 帧拦截 + `Principal`（`convertAndSendToUser` 的 user 维度即来源于此）。Spring Security 的对应保护是两层：握手请求被 Servlet Filter 链覆盖 + spring-security-messaging 保护 STOMP 帧。

**4. 与虚拟线程（第六章）：受益，但别把会话当请求。**
阻塞式 `handleMessage`/STOMP 控制器在虚拟线程下同样便宜（通道执行器与容器帧处理线程可 VT 化）。两点差异要牢记：会话生命周期以分钟/小时计，**会话状态（订阅列表、user→session 映射）是集群共享状态**——多实例部署要么网关粘性路由、要么外部 STOMP 代理（`enableStompBrokerRelay`）/Redis 广播；慢消费者问题靠 `ConcurrentWebSocketSessionDecorator` 的缓冲上限保护，而不是靠"线程便宜"硬扛无限队列。

## 7.7 场景 → 方案速查与本章小结

| 你要做的应用 | 推荐 | 一句话理由 |
|---|---|---|
| 通知中心/进度条 | SSE（`SseEmitter`） | 单向文本 + 自动重连，最便宜 |
| 聊天/客服/直播间 | STOMP（L2） | 房间/订阅/定向语义开箱即用 |
| 行情/大盘（二进制/自定义协议） | 原生 Handler（L1） | 需要帧级控制 |
| 联机游戏/协作白板 | 原生 Handler（L1） | 二进制帧 + 亚秒延迟 |
| 扫码登录/支付结果回调 | STOMP `@SendToUser` 或 SSE | 服务端主动、一次性 |
| IoT 网关 | 原生 Handler（+MQTT 桥） | 设备侧协议多样 |
| 数据偶尔更新即可 | 轮询 | 别为低频引入常连成本 |

**本章小结**：

- **用与不用**："服务端要在客户端不请求时说话"或"高频双向"才用 WebSocket；单向文本推送首选 SSE；低频更新轮询即可。
- **怎么用**：L1 原生 Handler（四回调 + 广播自管）、L2 STOMP（`@MessageMapping/@SendTo/@SendToUser` + `SimpMessagingTemplate` 主动推）、L3 `@ServerEndpoint`；多实例生产配外部代理或粘性路由。
- **与主线的关系**：握手 = 一次标准 MVC 请求（Filter/追踪/安全全链生效，经 `webSocketHandlerMapping` → `HttpRequestHandlerAdapter` 完成 101 升级）；会话帧 = 脱离 MVC 的世界（用户上下文在握手物化、消息级追踪挂 `ChannelInterceptor`、会话状态集群外置）。

---

# 八、Spring MVC 架构总分析

## 8.1 分层与模块依赖

MVC 的全部能力分布在两个模块（依赖关系经各模块 `build.gradle` 实证，同《Spring Framework.md》1.4）：

```
spring-web（公共层：不依赖 Servlet 也能用）
  ├ http/          HTTP 抽象（HttpHeaders、MediaType、ResponseEntity）
  ├ converter/     HttpMessageConverter 策略族（Jackson 3 实现在此）
  ├ method/        HandlerMethod + 参数/返回值策略接口（ArgumentResolver/ReturnValueHandler 契约）
  ├ filter/        Servlet Filter 基建（OncePerRequestFilter、DelegatingFilterProxy、ServerHttpObservationFilter）
  ├ context/request/  WebRequest 抽象 + 异步设施（WebAsyncManager）
  ├ accept/        内容协商 + API 版本策略（7.0 ApiVersionStrategy）
  └ service/       声明式 HTTP 客户端（@HttpExchange / RestClient 支撑）
spring-webmvc（Servlet 栈：依赖 aop/beans/context/core/expression/web）
  ├ servlet/       DispatcherServlet 三件套 + 三大策略接口 + HandlerExecutionChain
  ├ handler/       HandlerMapping 实现（AbstractHandlerMethodMapping 引擎）
  ├ mvc/annotation 执行引擎（RequestMappingHandlerAdapter + 参数/返回值/异常实现）
  ├ view/          视图技术（JSP/FreeMarker/Thymeleaf 桥）
  └ config/        WebMvcConfigurer / @EnableWebMvc 装配
```

依赖图的读法：**spring-web 不知道 MVC 的存在**（它同时服务 WebFlux），**spring-webmvc 是 spring-web 策略接口的一个"Servlet 语境实现"**。你在 Controller 里用到的绝大多数注解（`@RequestMapping` 等）定义在 spring-web——这也是声明式 HTTP 客户端（`@HttpExchange`）能与控制器共享心智模型的原因。

## 8.2 三分法：把"一个请求"切成三个策略位

`DispatcherServlet` 的核心架构决策是**把调度过程切成三个互不关心的策略位**：

```
                 ┌──────────────────────────────────────────────┐
 请求 ──────────►│ HandlerMapping：谁处理？（HandlerMethod+拦截器） │──► HandlerExecutionChain
                 └──────────────────────────────────────────────┘
                 ┌──────────────────────────────────────────────┐
                 │ HandlerAdapter：怎么调用？（适配 Handler 的形态） │──► ModelAndView / 直写响应
                 └──────────────────────────────────────────────┘
                 ┌──────────────────────────────────────────────┐
 结果/异常 ─────►│ HandlerExceptionResolver：出错怎么办？          │──► ModelAndView / 直接写错误响应
                 └──────────────────────────────────────────────┘
```

- **映射与执行分离**：`@Controller` 方法、函数式 `RouterFunction`、传统 `Controller` 接口、静态资源四种形态的 handler 共存，全靠"每种形态一对 Mapping/Adapter"（`DispatcherServlet.properties` 默认 3 + 4 个）；
- **执行与善后分离**：异常不侵入执行逻辑——`doDispatch` 的 catch 块把异常暂存（:973-978），统一在 `processDispatchResult` 走 resolver 链；
- **组合胜于继承**：Mapping/Adapter/Resolver 都是**无状态策略 bean**，靠 `initStrategies` 收集、order 排序组合——用户扩展即"注册 bean"，不需要继承 DispatcherServlet。

在这三个"骨架策略"之下，还有两个"肌肉策略"族：参数/返回值处理（`HandlerMethodArgumentResolver`/`HandlerMethodReturnValueHandler`，spring-web 定义契约、spring-webmvc 提供注解实现）与消息转换（`HttpMessageConverter`）。**策略总数约 10 族、默认实现 60+ 个，全部可替换**——这就是"扩展点即架构"的含义。

## 8.3 与 WebFlux 的同构对照

同一套三分法在 Reactive 栈被复刻（`DispatcherHandler`），差异只在"线程与背压模型"：

| 维度 | Spring MVC（本文） | Spring WebFlux |
|---|---|---|
| 前端控制器 | `DispatcherServlet`（Servlet 线程模型） | `DispatcherHandler`（Reactor 事件循环） |
| 映射 | `RequestMappingHandlerMapping` | 同名类（reactive 包） |
| 执行 | `RequestMappingHandlerAdapter` → 阻塞调用 | 同名类 → 返回 `Publisher` |
| 参数/返回值 | `HandlerMethodArgumentResolver` 族 | 同接口（reactive 版本） |
| 异常 | `HandlerExceptionResolver` | 同接口 |
| 上下文持有 | ThreadLocal（`RequestContextHolder`） | Reactor Context + `context-propagation` |
| 适用 | 传统阻塞生态（JDBC 等） | 高并发 I/O、流式 |

对使用者的意义：**一套注解、一套扩展点心智，可以跨栈迁移**；Spring 7.0 的 API 版本化在两栈同时可用（`ApiVersionStrategy` 定义在 spring-web）。

## 8.4 可测试性：架构的副产品

因为"调度"全部是策略接口 + POJO，spring-test 才能提供 `MockMvc`：用 `MockHttpServletRequest/Response` 伪造容器对象，直接调用 `DispatcherServlet`（或经 `StandaloneMockMvcBuilder` 只装配目标控制器），**不经网络、不经真实容器**跑通第四章的 4.2~4.8 全流程。这反过来证明了 8.2 节的架构判断——**MVC 的逻辑不依赖容器，依赖的只是规范给出的对象接口**。

## 8.5 AOT 与原生镜像支持

Framework 6 起 MVC 支持 GraalVM Native Image：反射、代理、资源在构建期注册 hints（`@RegisterReflectionForBinding`、Boot 的 `RuntimeHintsRegistrar`）；声明式 HTTP 客户端更进一步——`HttpServiceProxyBeanRegistrationAotProcessor`（`spring-web/.../service/registry/HttpServiceProxyBeanRegistrationAotProcessor.java:44`）在 AOT 阶段就把 `@HttpExchange` 接口的代理生成固化。MVC 的注解模型（`@RequestMapping` 等）因此可以编译进原生镜像（注意：AOT 模式下基于运行期反射的动态特性受限，自定义扩展点需补 hints）。

## 8.6 Framework 7.0 给 MVC 带来的新东西（对照本文源码）

| 特性 | 证据 | 说明 |
|---|---|---|
| **API 版本化** | `@RequestMapping#version()`（spring-web :236，支持 `"1.2"` 固定 / `"1.2+"` 基线）；`ApiVersionResolver` 族（header/query/path/media-type）；`VersionRequestCondition`；`AbstractHandlerMapping.getHandler:545` 第一行 `initApiVersion`；`WebMvcConfigurer.configureApiVersioning:76` | 版本成为与 path/method 并列的映射维度；`ApiVersionMethodArgumentResolver`（RMA :674）可把版本注入方法参数 |
| **Jackson 3** | `JacksonJsonHttpMessageConverter`（spring-web，`@since 7.0`，`import tools.jackson.*`） | 迁移到 `tools.jackson` 包名；旧 Jackson 2 转换器仍在但进入维护 |
| **消息转换新配置口** | `WebMvcConfigurer.configureMessageConverters(HttpMessageConverters.ServerBuilder)`（:180，`@since 7.0`）；旧签名 :201/:216 废弃 | 由"给列表增删"改为"builder 语义化替换/保留默认" |
| **错误响应拦截** | `WebMvcConfigurer.addErrorResponseInterceptors`（:255，`@since 6.2`）；`ErrorResponse.Interceptor` | 在 RFC 9457 problem response 写出前统一加工 |
| **上下文传播补全** | `PropagationContextElement`（`@since 7.0`） | Kotlin 协程 + context-propagation |
| **杂项** | `UrlHandlerFilter`（spring-web filter 包，把尾斜杠请求改写为内部跳转，替代废弃的 trailing slash 匹配） | 迁移期工具 |

## 8.7 局限与适用边界

1. **线程模型**：阻塞式 thread-per-request——虚拟线程普及后此局限大幅弱化（第六章），但极高频 I/O 扇出、背压敏感的场景仍属 WebFlux；
2. **同步注解模型对"动态"不友好**：运行期注册接口需走 `RequestMappingHandlerMapping.registerMapping`（4.4.1 节 API，存在但低层）；
3. **历史包袱**：`ModelAndView` 双出口（视图/直写）让部分扩展点（如 `postHandle`）语义尴尬——8.2 节三分法的优雅，在 REST 时代被"消息转换直写"这一后起路径部分绕过（4.6.3 节）；
4. **规范绑定**：MVC 深度绑定 Servlet 语义（`DispatcherType`、AsyncContext），跨协议（RSocket/gRPC）各有独立模型——Spring 的选择是"一栈一协议"而非万能抽象。

## 8.8 本章小结

Spring MVC 的架构可以压缩成一句话：**用 Servlet 规范换来的"单入口"做壳，用策略接口的"三分法"做骨架，用 Spring 容器做装配器，用注解编程模型做默认皮肤**。7.0 在不动骨架的前提下完成了基线升级（Servlet 6.1/Jackson 3/JDK 25）、映射维度扩展（API versioning）与上下文传播补全——这正是这套架构 20 年保持演进能力的原因。

---

# 九、贯通视图：一张表看懂"节点、扩展、场景"

## 9.1 全链路对位表（第四章节点号 × 扩展 × 两大场景 × 虚拟线程注意事项）

| 节点（§4.x） | 扩展手段（§5.1 编号） | 链路追踪视角（§5.2） | 用户上下文视角（§5.3） | 虚拟线程视角（§6） |
|---|---|---|---|---|
| 容器管道 | `WebServerFactoryCustomizer` | —（未到应用层） | — | 线程池在这里被换成 VT 执行器 |
| Filter 链 | ③ Filter | ★ 追踪唯一正解（Observation/自定义） | ★ Security/自定义 Filter 也在此 | 每个 VT 跑完整链；AsyncFilter 注意两次分发 |
| processRequest | （框架内部） | — | 官方 ThreadLocal 绑定/清理范本 | 上下文绑定于当前 VT |
| doDispatch 映射 | ④⑤ HandlerMapping/Interceptor | 404 时不执行——不能挂追踪 | ★ preHandle 放用户（MVC 级） | lookup 读锁，注册表线程安全 |
| 参数解析 | ⑥⑦ ArgumentResolver/RequestBodyAdvice | — | ★ 可注入 `@CurrentUser UserContext`（读 ThreadLocal） | 参数解析发生在请求 VT 上 |
| Controller 调用 | （业务代码） | span 内 | ThreadLocal 可用 | 阻塞自动 unmount，放心阻塞 |
| 返回值处理 | ⑨⑩ ReturnValueHandler/ResponseBodyAdvice | — | — | 序列化在请求 VT 上 |
| 异常解析 | ⑩ Resolver/@ControllerAdvice | error 状态进 Observation | — | — |
| afterCompletion | ⑤ Interceptor | — | ★ 清理 ThreadLocal 的必达点 | VT 无复用，但清理仍是规范写法 |
| 异步回环（§4.9） | Callable/DeferredResult/Emitter | ObservationAsyncListener 收尾 | ★ 跨线程——context-propagation 传播 | 价值下降；`taskExecutor` 也已是 VT |
| ERROR dispatch | Boot BasicErrorController | Observation 纳入 | ThreadLocal 已清，勿依赖 | — |
| WebSocket 握手/会话帧（§7） | ③ Filter（仅握手这一次）/ STOMP `ChannelInterceptor`（帧级） | 追踪只覆盖握手；帧级要挂通道拦截器 | 用户上下文在 `HandshakeInterceptor` 物化进 session attributes | 阻塞 handler 受益 VT；会话状态需集群外置 |

## 9.2 学习路线（动手向）

1. **第 1 天**：Boot 4 + JDK 25 起一个 MVC 应用，在 `DispatcherServlet#doDispatch:935` 与 `AbstractHandlerMethodMapping#lookupHandlerMethod:393` 下断点，观察第四章 12 步。
2. **第 2 天**：写一个 `OncePerRequestFilter`（打印 traceId + 耗时），用 `FilterRegistrationBean` 控制 order，验证它对 404 的覆盖；再故意把逻辑写成 Interceptor，对比 404 时是否执行。
3. **第 3 天**：实现 `UserContext` + `HandlerInterceptor`（preHandle/afterCompletion 配对），再实现 `ThreadLocalAccessor` + `ContextPropagatingTaskDecorator`，在 `@Async` 方法与 `Callable` 控制器里验证上下文存活。
4. **第 4 天**：开启 `spring.threads.virtual.enabled=true`，用 `jcmd Thread.dump_to_file -format=json` 观察虚拟线程；压测对比开关前后吞吐与资源池水位。
5. **第 5 天**：读 `ServerHttpObservationFilter` 全文，对照《Micrometer Tracing.md》打通 OTLP 导出；用 `@RequestMapping(version="1.2")` 体验 7.0 API 版本化。
6. **第 6 天**：给应用各加一个原生 `/ws/metrics` 广播端点与 STOMP 聊天端点（第七章示例），用 `HandshakeInterceptor` 物化用户，实测"帧不走 Filter 链、握手走完整 MVC 链"。

## 9.3 源码阅读入口清单（按必读度，路径相对 `D:\code\3rd\spring-framework`，行号对 v7.0.8）

1. `spring-webmvc/src/main/java/org/springframework/web/servlet/DispatcherServlet.java`（doService :829 / doDispatch :935 / processDispatchResult :1022 / processHandlerException :1208 / render :1267 / initStrategies :441）
2. `spring-webmvc/.../servlet/FrameworkServlet.java`（processRequest :982 / initContextHolders :1054）
3. `spring-webmvc/.../servlet/HttpServletBean.java`（init :150）
4. `spring-webmvc/.../handler/AbstractHandlerMethodMapping.java`（initHandlerMethods :217 / register :602 / lookupHandlerMethod :393）
5. `spring-webmvc/.../handler/AbstractHandlerMapping.java`（getHandler :544 / getHandlerExecutionChain :681）
6. `spring-webmvc/.../servlet/HandlerExecutionChain.java`（applyPreHandle :142 / triggerAfterCompletion :171）
7. `spring-webmvc/.../mvc/method/annotation/RequestMappingHandlerAdapter.java`（invokeHandlerMethod :885 / getDefaultArgumentResolvers :644）
8. `spring-web/.../method/support/InvocableHandlerMethod.java`（invokeForRequest :171 / getMethodArgumentValues :200）
9. `spring-webmvc/.../mvc/method/annotation/ServletInvocableHandlerMethod.java`（invokeAndHandle :114）
10. `spring-webmvc/.../mvc/method/annotation/RequestResponseBodyMethodProcessor.java`（handleReturnValue :195）+ `AbstractMessageConverterMethodProcessor.java`（writeWithMessageConverters :205）
11. `spring-webmvc/.../mvc/method/annotation/RequestResponseBodyAdviceChain.java`（:47）
12. `spring-webmvc/.../mvc/method/annotation/ExceptionHandlerExceptionResolver.java`
13. `spring-web/.../context/request/async/WebAsyncManager.java`（startCallableProcessing :288 / startAsyncProcessing :477）+ `StandardServletAsyncWebRequest.java`（startAsync :140 / dispatch :166）
14. `spring-web/.../filter/OncePerRequestFilter.java`（doFilter :89）+ `DelegatingFilterProxy.java`（doFilter :247）
15. `spring-web/.../filter/ServerHttpObservationFilter.java`（doFilterInternal :102）
16. `spring-web/SpringServletContainerInitializer.java`（:110）
17. `spring-web/.../context/request/RequestContextHolder.java`（:45）
18. `spring-web/.../context/request/RequestAttributesThreadLocalAccessor.java`（:36）
19. `spring-core/.../task/support/ContextPropagatingTaskDecorator.java`（:39）
20. Boot 4（`D:\code\3rd\spring-boot`）：`module/spring-boot-webmvc/.../DispatcherServletAutoConfiguration.java`、`core/spring-boot/.../web/servlet/ServletContextInitializerBeans.java`、`module/spring-boot-tomcat/.../TomcatVirtualThreadsWebServerFactoryCustomizer.java`
21. Security（`D:\code\3rd\spring-security`）：`web/.../context/SecurityContextHolderFilter.java`
22. `spring-websocket/src/main/java/org/springframework/web/socket/WebSocketHandler.java`（四回调 :43/:50/:57/:67）+ `server/support/WebSocketHttpRequestHandler.java`（handleRequest :160 / doHandshake :177）+ `config/annotation/WebSocketMessageBrokerConfigurationSupport.java`（消息通道 :74-119）+ `server/HandshakeInterceptor.java`（:48/:59）

---

## 结语

回到开篇的问题：Servlet 规范与 Spring MVC 各自给了什么？

- **Servlet 规范**用 30 年时间回答"容器与应用如何握手"：从 web.xml 到注解与 SCL（装配）、从线程独占到 AsyncContext（线程让渡）、从 HTTP/1.1 到 HTTP/2（协议演进），它给应用留的扩展窗口始终只有 Filter/Servlet/Listener 三类组件——**小而稳定**。
- **Spring MVC** 在这个窄门上建起了"单入口 + 三分法 + 策略族"的调度中心：HandlerMapping 找人、HandlerAdapter 干活、HandlerExceptionResolver 善后，参数解析、消息转换、视图渲染、上下文绑定层层皆是可替换的策略——**大而可扩展**。
- **应用开发者的功课**则是选对插手的位置：全局的事（追踪、安全、编码）进 Filter；业务的事（登录态、权限、审计）进 Interceptor；数据的事（包装、脱敏、转换）进 Advice/Converter；跨线程的事（异步、虚拟线程）用显式传播。

理解了这些，再看 Spring Boot 的自动配置、Spring Security 的过滤链、Micrometer 的观测埋点，都会是"似曾相识"——它们全部构建在本文读过的这些节点之上。


---
