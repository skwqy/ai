# Spring Security 深度源码解析（写给初学者的架构全景）

> **本文基于的源码**：`D:\code\3rd\spring-security`，版本 **7.2.0-SNAPSHOT**（main 分支快照，Git commit `1b17ecd7e6`，2026-10-02；`git describe` 为 `7.1.0-368-g1b17ecd7e6`，即 7.1.0 GA 之后的开发中版本）。文中所有【源码证据】的文件路径与行号均为对该快照实际读取所得。
>
> **版本取舍说明**：Spring Security 的核心骨架——`DelegatingFilterProxy → FilterChainProxy → SecurityFilterChain` 过滤器链、`AuthenticationManager → ProviderManager → AuthenticationProvider` 认证内核、`SecurityContextHolder` 上下文——自 3.x 以来高度稳定，这些主干在 5.x / 6.x / 7.x 之间几乎一致，因此本文内容对使用 Spring Boot 3.x（Security 6.x）的读者同样适用；5.x → 6.x → 7.x 的特性演进对比见 1.6 节，所有"某特性属于哪个版本"的结论均经本地 git 标签（v5.0.0.RELEASE ~ v7.1.1）逐项实证。
>
> **阅读约定**：每章"先白话、后源码"——先用一两句人话讲清"这是什么、为什么需要它"，再给出源码证据链（`模块/src/main/java/.../类名.java` + 方法名 + 行号 + 代码片段）。行号只对该快照精确，读者用 IDEA 打开源码按类名 + 方法名定位即可。

## 如何读这份文档

如果你是 Spring Security 初学者，推荐两遍读法：

- **第一遍（建立地图，1~2 小时）**：只读第一章（总览）每节的开头白话段、各章的"小结"节、以及第八章（贯通视图）。目标是能回答：一个 HTTP 请求进来要过哪几道安检？登录成功后"你是谁"存在哪里？`@PreAuthorize` 靠什么生效？
- **第二遍（深入源码）**：对照每一章的【源码证据】，打开源码逐行读。顺序建议：第二章（认证内核）→ 第三章（过滤器链）→ 第五章（配置体系）→ 第四章（授权）→ 第六章（OAuth2/JWT，按需）→ 第七章（crypto 与其余模块，随用随查）。

---

# 一、总览：Spring Security 的定位、设计哲学与整体架构

## 1.1 一句话定位

**Spring Security 是一个以 Servlet 过滤器链为骨架、以"认证（Authentication）+ 授权（Authorization）"两大决策线为核心的应用安全框架**：它把"这个请求是谁发起的"（认证）和"这个人能不能做这件事"（授权）从业务代码中夺走，以一条可插拔的过滤器链 + 一组策略接口承接，再以统一的 SPI 把表单登录、HTTP Basic、OAuth2、SAML、Passkey 等各种认证方式接进来。

它的前身叫 **Acegi Security**（`FilterChainProxy.java` 的版权头至今仍写着 `Copyright 2004, 2005, 2006 Acegi Technology Pty Limited`——见 `web/src/main/java/org/springframework/security/web/FilterChainProxy.java:2`），2008 年成为 Spring 官方子项目并更名。它解决的不是某一个具体问题，而是**应用安全的组织问题**：安全检查如何统一挂载（过滤器链）、身份如何表达与传递（SecurityContext）、各种认证方式如何以统一姿势接入（Provider 模式）、授权规则如何声明式表达（URL 规则 + 方法注解 + SpEL）。

与 Spring Framework 的关系：Spring Security 是**构建在 Spring 容器之上的框架**——它的过滤器是 Spring Bean（借 `DelegatingFilterProxy` 从 Servlet 世界"借"进 Spring 世界），它的配置体系复用 Spring 的 `ObjectPostProcessor` 与事件机制，它的方法安全直接复用 Spring AOP。理解《Spring Framework 深度源码解析》中的 IoC 与 AOP，是读本文的前提。

## 1.2 设计哲学：读源码前先记住五句话

1. **一切安全检查皆过滤器，但过滤器不等于零散插件**。最外层由 Servlet 容器管理的只有一个过滤器（`springSecurityFilterChain`，实际类型 `FilterChainProxy`），它内部再按 URL 分发到若干条虚拟过滤器链（`SecurityFilterChain`）。所有安全逻辑都是这条链上的一站，顺序由一张静态注册表（`FilterOrderRegistration`）锁定（见第三章）。
2. **认证与授权是两条独立的决策线**。认证回答"你是谁"（`AuthenticationManager` → `ProviderManager` → 一组 `AuthenticationProvider` 轮询），授权回答"你能不能做"（`AuthorizationManager.authorize()` 返回 `AuthorizationResult`）。两条线只在一个地方交汇：`SecurityContextHolder` 里那张"身份卡"（见第二、四章）。
3. **上下文贯穿，显式清理**。身份存在 `ThreadLocal`（`SecurityContextHolder`），请求结束由 `FilterChainProxy` 在 `finally` 里清空；跨请求的持久化交给 `SecurityContextRepository`（默认 Session）。5.7 起引入"延迟加载"语义（`loadDeferredContext`），避免每个请求都反序列化 Session（见 2.2、3.5 节）。
4. **策略接口 + Provider 模式无处不在**。密码怎么校验（`PasswordEncoder`）、用户从哪来（`UserDetailsService`）、JWT 怎么验签（`JwtDecoder`）、URL 怎么匹配（`RequestMatcher`）……每个决策点都是一个策略接口，用户换实现零侵入；而"选哪个策略"的调度权集中在少量 `Manager`/`Provider` 组合中。
5. **配置即建造者（Builder），DSL 是配置器的语法糖**。`http.authorizeHttpRequests(...)` 这行代码背后是 `SecurityBuilder/SecurityConfigurer` 模式：每个 `xxx()` 方法往 `HttpSecurity` 里装一个 `Configurer`，`build()` 时统一 `init → configure → performBuild`，产出过滤器和 Provider（见第五章）。旧教程里的 `WebSecurityConfigurerAdapter` 只是这个模式的"适配器外壳"，6.0 已移除，外壳下的机器没变。

## 1.3 模块分层全景

Spring Security 是多模块 Gradle 工程。按"用户感知"分层如下（模块清单来自 `settings.gradle` 的自动 include 与各模块 `spring-security-<name>.gradle` 文件实测）：

```
┌─────────────────────────── 应用接入层 ───────────────────────────┐
│  spring-security-config（HttpSecurity/WebSecurity 建造者、        │
│    @EnableWebSecurity、@EnableMethodSecurity、各 Configurer）      │
├─────────────────────────── 协议接入层 ───────────────────────────┤
│  oauth2-client（OAuth2 登录/客户端）  oauth2-resource-server（JWT）│
│  saml2-service-provider（SAML SP）   cas（CAS 客户端）             │
│  webauthn（Passkey/WebAuthn，7.0 独立成模块）                      │
│  kerberos（Kerberos/SPNEGO，7.0 新增）                             │
│  oauth2-authorization-server（OAuth2 授权服务器，7.0 收编官方）     │
├─────────────────────────── 领域扩展层 ────────────────────────────┤
│  acl（细粒度对象 ACL）   messaging（WebSocket/Messaging）          │
│  rsocket（RSocket）      data（Spring Data 查询注入过滤）           │
│  ldap（LDAP 认证）       taglibs（JSP 标签）  aspects（AspectJ）    │
├─────────────────────────── Web 内核层 ────────────────────────────┤
│  spring-security-web（FilterChainProxy、40+ 安全过滤器、防火墙、    │
│    CSRF/Headers/Session/CORS 设施、reactive 对应物）               │
├─────────────────────────── 核心层 ────────────────────────────────┤
│  spring-security-core（SecurityContextHolder、Authentication、     │
│    ProviderManager、AuthorizationManager、UserDetailsService、事件）│
│  spring-security-access（7.0 从 core 拆出：旧投票授权体系           │
│    AccessDecisionManager/Voter、@Secured、方法安全基础设施）        │
├─────────────────────────── 地基 ──────────────────────────────────┤
│  spring-security-crypto（PasswordEncoder、BCrypt/Argon2、加密器、   │
│    KeyGenerator——零 Spring 依赖，可单独使用）                      │
└───────────────────────────────────────────────────────────────────┘
```

几个容易忽略的事实：

- **`oauth2-authorization-server` 已并入主仓库**。它原是独立项目（Spring Authorization Server），本快照中作为模块存在（`git tag` 实证：7.0.0 起 `ls-tree` 根目录出现 `oauth2/` 下的完整模块）。
- **`spring-security-access` 是 7.0 的新模块**：旧授权体系（`AccessDecisionManager`、`AccessDecisionVoter`、`@Secured` 等）没有删除，而是整体从 core 迁入该模块（与《Spring Framework》中"spring-jcl 7.0 被移除"不同，这里是"搬家"不是"删家"）。
- **reactive 支持不在独立模块**。5.0.0.M1~M5 曾有独立的 `webflux/` 模块，5.0.0.RC1 起合并进 `spring-security-web`（`web/src/main/java/org/springframework/security/web/reactive/`）——这与 Spring MVC/WebFlux 合在 spring-web 的做法同构。

## 1.4 模块依赖图（以各模块 gradle 文件的 api 依赖实证）

每个模块的构建脚本是该目录下的 `spring-security-<module>.gradle`（如 `core/spring-security-core.gradle`），下表摘自其中 `api(project(":..."))` 声明（逐个验证）：

| 模块 | 内部依赖（api） | 第三方关键依赖（api） |
|---|---|---|
| spring-security-crypto | 无 | 仅 optional spring-core、bouncycastle |
| spring-security-core | crypto | spring-aop/beans/context/core/expression、micrometer-observation |
| spring-security-access | crypto、core | 同 core |
| spring-security-web | core | spring-core/aop/beans/context/expression/web |
| spring-security-config | core（optional：access/web/ldap/messaging/oauth2-\*/rsocket/webauthn/data…） | spring-aop/beans/context/core |
| oauth2-core | core | spring-core、spring-web |
| oauth2-jose | core、oauth2-core | **com.nimbusds:nimbus-jose-jwt** |
| oauth2-client | core、oauth2-core、web（optional：oauth2-jose） | com.nimbusds:oauth2-oidc-sdk |
| oauth2-resource-server | core、oauth2-core、web（optional：oauth2-jose） | spring-core |
| saml2-service-provider | web | **org.opensaml:opensaml-\*** |
| ldap | core | **org.springframework.ldap:spring-ldap-core** |
| acl | core | spring-aop/context/core/jdbc/tx |
| messaging | core（optional web） | spring-messaging |
| rsocket | core（optional oauth2-resource-server） | io.rsocket:rsocket-core |
| data | core | spring-data-commons |
| webauthn | core、web | **com.webauthn4j:webauthn4j-core** |
| kerberos-\*（core/client/web/test） | kerberos-core → core；kerberos-web → web | org.apache.kerby:kerb-simplekdc（test） |
| taglibs | acl、core、web | — |
| test | core、web（optional config/oauth2-\*） | spring-test |

依赖图（"→"指"依赖于"）：

```
                 spring-security-crypto（零依赖地基：只管密码与加解密）
                     ▲
                 spring-security-core（身份与决策内核）
                 ▲    ▲    ▲
     ┌───────────┘    │    └──────────────┐
 spring-security-access │               spring-security-web（过滤器链）
 （旧授权体系/方法安全）  │                    ▲    ▲    ▲
     └──────────┬──────┘        ┌────────────┘    │    └──────────┐
            spring-security-web  oauth2-client  oauth2-resource-server  saml2/cas
                     ▲                ▲ oauth2-core ▲ oauth2-jose
                     └────────────────┴───────┬─────┘
                                       spring-security-config
                                       （Builder/Configurer + @Enable 注解）
                                              ▲
                                       你的应用 / Spring Boot（spring-boot-security）
```

一个值得注意的设计：**crypto 完全不依赖 Spring 容器**（仅 optional 依赖 spring-core），`PasswordEncoder` 可以在纯 Java 项目里单独使用；**core 只依赖 crypto**，Web 设施全部隔离在 web 模块——这保证了认证/授权内核可以被 messaging、rsocket、reactive 等非 Servlet 场景复用。

## 1.5 关键问题 → Spring Security 方案映射（全文导览）

| 应用安全的关键问题 | Spring Security 的方案 | 详见 |
|---|---|---|
| 安全检查如何统一挂到所有请求上 | DelegatingFilterProxy → FilterChainProxy → SecurityFilterChain 虚拟过滤器链 | 第三章 |
| "当前用户是谁"如何表达与传递 | SecurityContextHolder（ThreadLocal 三策略）+ SecurityContext + Authentication | 第二章 |
| 用户名密码登录如何校验 | UsernamePasswordAuthenticationFilter → ProviderManager → DaoAuthenticationProvider → UserDetailsService + PasswordEncoder | 第二、三章 |
| 密码不能明文存、还要支持算法平滑升级 | DelegatingPasswordEncoder 的 `{id}密文` 前缀格式 + 密码升级（UserDetailsPasswordService） | 第七章 |
| 各种认证方式（Basic/JWT/OAuth2/SAML…）如何统一接入 | AuthenticationProvider 策略族 + ProviderManager 轮询调度 | 第二章 |
| 某个 URL 谁能访问 | AuthorizationFilter（链末）+ RequestMatcherDelegatingAuthorizationManager + authorizeHttpRequests 规则 | 第四章 |
| 某个方法/返回值谁能调用 | @EnableMethodSecurity → AOP 拦截器（AuthorizationManagerBeforeMethodInterceptor）+ @PreAuthorize/@PostAuthorize/@AuthorizeReturnObject | 第四章 |
| 匿名请求没登录，跳哪去？登录成功跳哪去？ | ExceptionTranslationFilter + AuthenticationEntryPoint/AccessDeniedHandler + RequestCache 回跳 | 第三章 |
| 登录状态如何跨请求保持、多标签页/多端如何处理 | SecurityContextRepository（Session/RequestAttribute 委托）+ SessionManagementFilter + 会话固定防护 | 第二、三章 |
| CSRF 攻击怎么防 | CsrfFilter + CsrfTokenRepository + XorCsrfTokenRequestAttributeHandler（BREACH 防护） | 第三章 |
| 恶意构造的 URL 怎么挡 | StrictHttpFirewall（FilterChainProxy 内建"防火墙"） | 第三章 |
| OAuth2 登录 / 调第三方 API / 保护自己的 API | oauth2-client（OAuth2LoginAuthenticationFilter）、oauth2-resource-server（BearerTokenAuthenticationFilter + NimbusJwtDecoder） | 第六章 |
| 安全规则怎么声明式配置、Boot 怎么开箱即用 | SecurityBuilder/SecurityConfigurer 建造者模式 + Spring Boot 自动配置（SecurityFilterChain Bean） | 第五章 |
| 忘记密码/密码泄露检测 | CompromisedPasswordChecker（6.3）、密码升级重编码 | 第二章 |

## 1.6 版本演进：5.x → 6.x → 7.x 关键变化对比

写作时（2026 年 10 月）的版本格局：6.5 与 7.1.x 并行维护，7.2 尚在快照阶段（正是本文分析的这份 main 分支）。本节所有"特性属于哪个版本"的结论都经过双重验证：**① 用本地仓库的 git 标签对源码逐项 grep/ls-tree 实证；② GA 日期取自各发布 tag 的 git 提交时间**。

### 1.6.1 版本时间线与运行基线

| 版本 | GA 时间（git tag 实证） | 对应 Spring Boot | 一句话主题 |
|---|---|---|---|
| 5.0 | 2017-11-27 | 2.0 | OAuth2/JWT 正式入场（client/core/jose 三模块） |
| 5.1 | 2018-09-21 | 2.1 | oauth2-resource-server 模块（5.1 起四件套齐） |
| 5.2 | 2019-09-30 | 2.2 | **Lambda DSL（Customizer）**；rsocket、saml2-service-provider 模块 |
| 5.3 | 2020-03-04 | 2.3 | 5.x 长维护线起点 |
| 5.4 | 2020-09-09 | 2.4 | 配置简化、oauth2 client 增强 |
| 5.5 | 2021-05-17 | 2.5 | **authorizeHttpRequests（新 URL 授权 DSL）** |
| 5.6 | 2021-11-15 | 2.6 | **@EnableMethodSecurity（新方法安全注解体系）**、可注入 SecurityContextHolderStrategy |
| 5.7 | 2022-05-16 | 2.7 | **WebSecurityConfigurerAdapter 标记废弃**；SecurityContextHolderFilter、AuthorizationManager（Servlet 侧）登场 |
| 5.8 / 6.0 | 2022-11-21（同日发布，同一时间戳） | 2.7 / 3.0 | 5.8 为 6.0 的过渡版（XorCsrfTokenRequestHandler、AuthorizationManager 扩充）；**6.0：删除 WebSecurityConfigurerAdapter，jakarta 基线，Observation 集成，SecurityContextHolderFilter 成为默认** |
| 6.1 | 2023-05-15 | 3.1 | 持续现代化 |
| 6.2 | 2023-11-20 | 3.2 | `with(configurer, customizer)` 建造者扩展 |
| 6.3 | 2024-05-20 | 3.3 | **@AuthorizeReturnObject**、CompromisedPasswordChecker |
| 6.4 | 2024-11-18 | 3.4 | **One-Time Token 登录、WebAuthn（Passkey）**、AuthorizationManager.authorize 返回 AuthorizationResult |
| 6.5 | 2025-05-19 | 3.5 | 6.x 收官维护线 |
| 7.0 | 2025-11-17 | 4.0 | **JSpecify 空安全；旧授权体系迁入独立 access 模块；webauthn 独立模块；kerberos 新模块；oauth2-authorization-server 收编；PathPatternRequestMatcher 全面替代 AntPathRequestMatcher；authorizeRequests 从 HttpSecurity 删除** |
| 7.1 | 2026-06-09 | 4.1 | 迭代增强（FactorGrantedAuthority、MFA 支持在 7.1 前后落地） |
| 7.2（本文快照） | 未发布 | 4.2 | 开发中 |

### 1.6.2 特性引入版本对照表（git 实证，可复现）

每行都可用 `git ls-tree -r <tag> --name-only | grep <关键字>` 或 `git grep -c "<关键字>" <tag> -- <路径>` 复现（注意 5.3 及以前 GA tag 带 `.RELEASE` 后缀）：

| 特性 | 引入版本 | 实证（旧 tag → 新 tag） | 详见 |
|---|---|---|---|
| oauth2-client / oauth2-core / oauth2-jose 模块 | 5.0 | `git ls-tree 5.0.0.RELEASE --name-only oauth2/` 已有 3 个目录 | 第六章 |
| oauth2-resource-server 模块 | 5.1 | 5.0.0 无 → 5.1.0.RELEASE 有（四件套齐） | 第六章 |
| Lambda DSL（`Customizer` 重载） | **5.2**（常被误传为 5.6） | 5.1.0.RELEASE HttpSecurity.java 中 `Customizer` 0 处 → 5.2.0.RELEASE 73 处 | 第五章 |
| rsocket / saml2-service-provider 模块 | 5.2 | 5.1.0 无 → 5.2.0.RELEASE 有 | 1.3 |
| authorizeHttpRequests | 5.5 | 5.4.0 HttpSecurity.java 0 处 → 5.5.0 10 处 | 第四章 |
| @EnableMethodSecurity | 5.6 | 5.6.0 `EnableMethodSecurity.java` 存在 | 第四章 |
| WebSecurityConfigurerAdapter 废弃 | 5.7 | 5.7.0 该文件含 `@Deprecated`（5.6.0 无） | 第五章 |
| SecurityContextHolderFilter（延迟加载上下文） | **5.7**（常被误传为 5.8/6.0） | 5.6.0 无 → 5.7.0 有 | 第三章 |
| AuthorizationManager（Servlet 侧接口） | 5.7 | 5.3.0 包内仅 Reactive 类 → 5.7.0 有 AuthorizationManager.java | 第四章 |
| XorCsrfTokenRequestAttributeHandler（BREACH 防护） | 5.8 | 5.7.0 csrf 包无 TokenRequest 类 → 5.8.0 有 4 个 | 第三章 |
| WebSecurityConfigurerAdapter / authorizeRequests 移除或废弃 | 6.0 | 6.0.0 该文件不存在；authorizeRequests 标 @Deprecated | 第五章 |
| FilterChainDecorator / Observation 集成 | 6.0 | FilterChainProxy.java `FilterChainDecorator` 标注 `@since 6.0` | 第三章 |
| with(configurer, customizer) | 6.2 | AbstractConfiguredSecurityBuilder.java:161 标注 `@since 6.2` | 第五章 |
| @AuthorizeReturnObject | **6.3**（常被误传为 6.4/6.2） | 6.2.0 全树 0 处 → 6.3.0 有 | 第四章 |
| One-Time Token 登录 | 6.4 | 6.3.0 web/config 中 OneTimeToken 0 文件 → 6.4.0 34 个 | 第三章 |
| WebAuthn（Passkey）支持 | 6.4（代码在 web/config 内）→ 7.0 独立模块 | 6.3.0 全树无 → 6.4.0 131 个文件；7.0.0 出现根模块 webauthn/ | 第七章 |
| AuthorizationManager.authorize 返回 AuthorizationResult | 6.4 | AuthorizationManager.java:57 标注 `@since 6.4` | 第四章 |
| 旧授权体系（AccessDecisionManager/FilterSecurityInterceptor）迁入 access 模块 | 7.0（**迁移而非删除**，常被误传为"6.0 删除"） | 6.0.0 位于 core/web 且已 @Deprecated → 7.0.0 位于 access/ | 第四章 |
| kerberos 模块 | 7.0（M3 引入，commit f5fb127c8c） | 6.5.0 全树无 → 7.0.0-M3 有 kerberos/ | 第七章 |
| oauth2-authorization-server 收编官方 | 7.0 | 6.5.0 无 → 7.0.0 有 | 第六章 |
| PathPatternRequestMatcher | 6.5 引入 → 7.0 成为默认 | 类标注 `@since 6.5`；6.5.0 的 UsernamePasswordAuthenticationFilter 已用它 | 第三章 |
| authorizeRequests 从 HttpSecurity 删除 | 7.0 | 7.0.0 HttpSecurity.java 中 0 处 | 第四章 |
| FactorGrantedAuthority（认证因子） | 7.1 前后（commit ce36fc1e76，2025-10-03） | AbstractUserDetailsAuthenticationProvider 成功令牌中追加密码因子 | 第二章 |
| 内建 MFA 支持（mfaEnabled） | 7.1 前后（commit aaf738f7ac，2025-11-03 "MFA is now Opt In"） | AbstractAuthenticationProcessingFilter.shouldPerformMfa | 第三章 |

三个容易搞错的点，特别提醒（网上大量旧资料在这三处有误，均已被 git 标签实证修正）：

- **Lambda DSL 不是 5.6 才有**：5.2 就引入了 `Customizer` 重载；5.6 的变化是官方文档开始默认推荐。
- **旧授权体系不是"6.0 删除"**：6.0 只是废弃，7.0 是**整体搬进新模块 spring-security-access**，类还在、包名不变，历史代码仍可编译。
- **SecurityContextHolderFilter 不是 6.0 才引入**：5.7 已存在，6.0 只是把默认值从 SecurityContextPersistenceFilter 换成它。

### 1.6.3 三大版本主题对比

| 维度 | 5.x（2017~2022） | 6.x（2022~2025） | 7.x（2025~） |
|---|---|---|---|
| 运行基线 | javax.* + Spring Framework 5.x | **jakarta.\*** + Framework 6.x | jakarta.* + Framework 7.x，JSpecify 空安全全面铺开 |
| 配置模型 | WebSecurityConfigurerAdapter（继承式）→ Lambda DSL（5.2+） | **SecurityFilterChain Bean 声明式**（Adapter 已删除） | 同 6.x；with() 扩展、顶层 Customizer Bean 自动应用 |
| URL 授权 | authorizeRequests（AntPathRequestMatcher） | authorizeHttpRequests（5.5 引入，6.x 默认） | PathPatternRequestMatcher 全面默认化；authorizeRequests 删除 |
| 上下文加载 | SecurityContextPersistenceFilter（每请求必加载） | SecurityContextHolderFilter（5.7+，**延迟加载**） | 同 6.x；RequestAttribute + Session 双仓委托为默认 |
| 观测 | 无内建 | ObservationFilterChainDecorator（Micrometer Observation） | 深化（RequestRejectedHandler 打标等） |
| 现代认证 | OAuth2/SAML 基础 | One-Time Token、Passkey 起步 | webauthn/kerberos 独立模块、授权服务器收编、MFA、认证因子（FactorGrantedAuthority） |

### 1.6.4 对初学者的意义：哪些知识过时了，哪些永远有效

- **新项目避免使用**：`WebSecurityConfigurerAdapter`（6.0 已删）、`authorizeRequests`/AntPathRequestMatcher（7.0 已删/默认替换）、`@EnableGlobalMethodSecurity`（7.0 已废弃，改用 `@EnableMethodSecurity`）、`SecurityContextPersistenceFilter`（已废弃）。
- **5.x 老教程里永久有效的部分**：过滤器链骨架、`ProviderManager` 轮询、`DaoAuthenticationProvider` 流程、`SecurityContextHolder` 用法、CSRF 原理、`@PreAuthorize` 注解——本文第二~七章正是按这条主线写的。
- **版本与 Boot 的对应关系**（选教材时对号入座）：Boot 2.x ↔ Security 5.x；Boot 3.0~3.4 ↔ Security 6.0~6.4；Boot 3.5 ↔ 6.5；Boot 4.x ↔ 7.x。从 Boot 3.x 入手的读者，实际用的就是 Security 6.x 一带的内核。

## 1.7 全文章节地图

- **第二章 核心地基（spring-security-core）**：SecurityContextHolder 三种策略、Authentication 票据体系、ProviderManager 轮询调度、DaoAuthenticationProvider 完整认证生命周期（含时序攻击防护、密码升级）。
- **第三章 过滤器链（spring-security-web）**：DelegatingFilterProxy 桥、FilterChainProxy 总闸、43 个标准过滤器槽位全景表、重点过滤器逐个解析（上下文/CSRF/表单登录/异常翻译/授权/会话）、StrictHttpFirewall。
- **第四章 授权体系（authorization / access）**：新旧两代架构、AuthorizationManager 接口族、RoleHierarchy、SpEL 表达式、方法级安全（@EnableMethodSecurity → AOP）。
- **第五章 配置体系（spring-security-config）**：SecurityBuilder/SecurityConfigurer 建造者模式、HttpSecurity/WebSecurity 两级构建、springSecurityFilterChain Bean 的诞生、Spring Boot 集成、Lambda DSL。
- **第六章 OAuth2 / JWT / OIDC**：oauth2 模块全景、OAuth2 登录流程、资源服务器 Bearer Token 校验、JWT 编解码（Nimbus）、授权服务器。
- **第七章 crypto 与其他模块速览**：DelegatingPasswordEncoder 与 `{id}` 前缀、密码算法族、ACL/LDAP/SAML/WebAuthn/Kerberos 等其余模块速览。
- **第八章 贯通视图**：把应用启动、一次 HTTP 请求、一次表单登录三条时间线叠成全景图，并从源码中提炼设计模式视角。
- **第九章 附录**：关键接口速查表与初学者学习路线、30 个关键源码文件。

---
# 二、核心地基（spring-security-core）：认证内核与身份上下文

## 2.1 模块定位与包结构

spring-security-core 是整个框架的"身份与决策内核"，不含任何 Servlet 代码（Web 设施在 web 模块）。主要子包：

| 子包 | 职责 | 代表类 |
|---|---|---|
| `org.springframework.security.core` | 身份的最小词汇表 | Authentication、GrantedAuthority、SecurityContext、UserDetails、CredentialsContainer |
| `...core.context` | 身份的存放与传递 | SecurityContextHolder、SecurityContextHolderStrategy、ThreadLocalSecurityContextHolderStrategy |
| `...authentication` | 认证调度 | AuthenticationManager、ProviderManager、AbstractAuthenticationToken、AuthenticationEventPublisher |
| `...authentication.dao` | 用户名密码认证 | DaoAuthenticationProvider、AbstractUserDetailsAuthenticationProvider |
| `...core.userdetails` | 用户数据的抽象与内存实现 | UserDetailsService、User、InMemoryUserDetailsManager（provisioning 包） |
| `...authorization` | 新一代授权接口 | AuthorizationManager、AuthorizationResult、AuthorizationDeniedException |
| `...access` | 方法安全与旧授权体系入口 | @PreAuthorize 所需的 SpEL 根、AuthorizationServiceException |
| `...authentication.event` | 认证事件 | AuthenticationSuccessEvent、AbstractAuthenticationFailureEvent |

注意：7.x 快照中 core 依赖 crypto（`core/spring-security-core.gradle:13`），所以 `PasswordEncoder` 虽然定义在 crypto 模块，却是 core 认证流程的常客。

## 2.2 SecurityContextHolder：一张 ThreadLocal 身份卡

先说白话：一个请求从进门到出门要经过十几层代码（过滤器 → Servlet → 业务逻辑），每一层都可能需要回答"当前用户是谁"。**把身份塞进 ThreadLocal**，任何一层随取随用，不需要层层传参——这就是 `SecurityContextHolder` 的全部使命。它同时承担"请求结束必须清卡"的纪律（不清会串号，见 3.3 节 `FilterChainProxy` 的 finally 块）。

【源码证据】`core/src/main/java/org/springframework/security/core/context/SecurityContextHolder.java` 第 55-67、71-73 行：

```java
public class SecurityContextHolder {

    public static final String MODE_THREADLOCAL = "MODE_THREADLOCAL";
    public static final String MODE_INHERITABLETHREADLOCAL = "MODE_INHERITABLETHREADLOCAL";
    public static final String MODE_GLOBAL = "MODE_GLOBAL";
    ...
    public static final String SYSTEM_PROPERTY = "spring.security.strategy";

    private static String strategyName = System.getProperty(SYSTEM_PROPERTY);

    private static SecurityContextHolderStrategy strategy = new ThreadLocalSecurityContextHolderStrategy();
    ...
    static {
        initialize();
    }
```

它是一个纯静态门面（Facade），所有方法委托给一个**可更换的策略** `SecurityContextHolderStrategy`。`initializeStrategy()`（第 80-111 行）按 `strategyName` 三选一：

| 策略 | 实现 | 适用场景 |
|---|---|---|
| MODE_THREADLOCAL（默认） | `ThreadLocalSecurityContextHolderStrategy`：`ThreadLocal<SecurityContext>` | Servlet 容器：一线程一请求 |
| MODE_INHERITABLETHREADLOCAL | 子线程自动继承父线程的值 | 少数自建线程池的老代码（有内存泄漏与串号风险，慎用） |
| MODE_GLOBAL | 全 JVM 一个值 | 纯桌面应用；服务器环境"definitely inappropriate"（源码 javadoc 原话） |

切换方式两种：系统属性 `spring.security.strategy`，或编程式 `setStrategyName()`；5.6 起还支持直接注入实例 `setContextHolderStrategy(strategy)`（第 208-213 行，会把 strategyName 置为内部标记 `MODE_PRE_INITIALIZED` 跳过反射创建）。

两个 5.8 后的关键补充（第 134、164-166 行）：

```java
public static Supplier<SecurityContext> getDeferredContext() { ... }   // @since 5.8
public static void setDeferredContext(Supplier<SecurityContext> deferredContext) { ... }
```

"延迟上下文"是 6.x 过滤器链重构的地基：`SecurityContextHolderFilter` 会先塞进一个 `Supplier`（从 Session 懒加载），等业务代码第一次真正调 `getContext()` 时才去反序列化 Session——大多数请求根本不需要登录态，这一下省掉了每请求必做的 Session 反序列化（见 3.5.1 节）。

**对初学者最重要的实践结论**：不要在自建线程池里裸用 `SecurityContextHolder.getContext()`（异步线程读不到 ThreadLocal）。框架配套给了 `DelegatingSecurityContextRunnable`/`DelegatingSecurityContextExecutor`（core 的 `concurrent` 包）做身份搬运；本快照的 core 还引入了 `context-propagation`（可选依赖，见 `core/spring-security-core.gradle` optional 声明），对接 Micrometer 的 Context Propagation。

## 2.3 Authentication 接口体系：认证请求与认证结果共用一张"票据"

先说白话：认证开始时，用户名密码要打包交给认证管理器；认证结束后，"你是谁、你有什么权限"要打包存进上下文。**两个方向用的是同一个接口** `Authentication`——它既是请求（`unauthenticated` 半成品）也是结果（`authenticated` 完成品），身份信息在两种形态间流转。

【源码证据】`core/src/main/java/org/springframework/security/core/Authentication.java` 第 45-100 行（节选）：

```java
public interface Authentication extends Principal, Serializable {

    Collection<? extends GrantedAuthority> getAuthorities();

    @Nullable Object getCredentials();   // 凭据：密码、JWT 字符串等"证据"

    @Nullable Object getDetails();       // 附加信息：IP、sessionId 等

    @Nullable Object getPrincipal();     // 主体：用户名 → UserDetails
}
```

注意它继承了 `java.security.Principal`——所以业务代码里 `HttpServletRequest#getUserPrincipal()` 拿到的就是这个对象（web 模块的 `SecurityContextHolderAwareRequestFilter` 负责包装，见 3.4 节）。

最常用的实现 `UsernamePasswordAuthenticationToken` 提供了两个静态工厂，用命名把"半成品/成品"区分开：

【源码证据】`core/src/main/java/org/springframework/security/authentication/UsernamePasswordAuthenticationToken.java` 第 91-105 行：

```java
public static UsernamePasswordAuthenticationToken unauthenticated(@Nullable Object principal, ...) {
    return new UsernamePasswordAuthenticationToken(principal, credentials);        // authenticated=false
}
public static UsernamePasswordAuthenticationToken authenticated(Object principal, @Nullable Object credentials,
        Collection<? extends GrantedAuthority> authorities) {
    return new UsernamePasswordAuthenticationToken(principal, credentials, authorities); // authenticated=true
}
```

三参构造会把 `super.setAuthenticated(true)`。这个布尔位是授权体系的信任边界——`Authentication` 的 javadoc 明确写着："unless the Authentication has the authenticated property set to true, it will still be authenticated by any security interceptor"（没盖章的票据会被拦截器继续送去认证）。

权限的最小单元是 `GrantedAuthority`（一个字符串，如 `ROLE_ADMIN`），对 `UserDetails` 的抽象（用户数据源）见 2.6 节。

## 2.4 AuthenticationManager → ProviderManager：多 Provider 轮询的调度中枢

先说白话：认证方式五花八门（用户名密码、JWT、OAuth2、Remember-Me……），不能写死在一个类里。Spring Security 的方案是：`AuthenticationManager` 只有一个方法 `authenticate(Authentication)`；默认实现 `ProviderManager` 持有一组 `AuthenticationProvider`，**逐个问"这票你接不接"（supports），接住的负责干完**，都不接就抛 `ProviderNotFoundException`。这就是框架的"多态认证"骨架。

【源码证据】`core/src/main/java/org/springframework/security/authentication/ProviderManager.java` 第 90、165-266 行（核心段节选）：

```java
public class ProviderManager implements AuthenticationManager, MessageSourceAware, InitializingBean {
    ...
    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        Class<? extends Authentication> toTest = authentication.getClass();
        AuthenticationException lastException = null;
        Authentication result = null;
        ...
        for (AuthenticationProvider provider : getProviders()) {
            if (!provider.supports(toTest)) {
                continue;                                  // ① 不接这票，下一个
            }
            try {
                result = provider.authenticate(authentication);
                if (result != null) {
                    copyDetails(authentication, result);   // ② 接住了：补 details，跳出
                    break;
                }
            }
            catch (AccountStatusException ex) {
                ...
                throw ex;                                  // ③ 账号状态类失败：立即短路
            }
            catch (InternalAuthenticationServiceException ex) {
                ...
                throw ex;                                  // ④ 服务内部错误：同样短路
            }
            catch (AuthenticationException ex) {
                lastException = ex;                        // ⑤ 普通失败：记住，继续试下一个
            }
        }
        if (result == null && this.parent != null) {
            parentResult = this.parent.authenticate(authentication);  // ⑥ 兜底父管理器
            ...
        }
        if (result != null) {
            if (this.eraseCredentialsAfterAuthentication && (result instanceof CredentialsContainer)) {
                ((CredentialsContainer) result).eraseCredentials();   // ⑦ 认证完成：擦掉密码！
            }
            if (parentResult == null) {
                this.eventPublisher.publishAuthenticationSuccess(result);  // ⑧ 发布成功事件
            }
            return result;
        }
        if (lastException == null) {
            lastException = new ProviderNotFoundException(...);        // ⑨ 全员不接
        }
        ...
        throw lastException;
    }
```

四个关键设计点：

1. **短路语义**（③④）：`AccountStatusException`（账号锁定/禁用/过期）与 `InternalAuthenticationServiceException`（UserDetailsService 自身出错）不留给下一个 Provider 重试——前者语义已经明确，后者是基础设施故障，重试只会制造噪音（源码注释引用 SEC-546）。
2. **擦除凭据**（⑦）：`eraseCredentialsAfterAuthentication` 默认 `true`，认证成功后立刻把返回票据里的密码字段抹掉（实现 `CredentialsContainer.eraseCredentials()`），缩短明文密码在内存里的暴露时间。
3. **事件发布**（⑧）：成功发 `AuthenticationSuccessEvent`，失败由 `prepareException`（第 268-272 行）发 `AbstractAuthenticationFailureEvent`。默认构造的 `eventPublisher` 是一个**空实现** `NullEventPublisher`（第 316-326 行）——自己 new `ProviderManager` 时事件默认静默；Spring 容器中由配置层注入 `DefaultAuthenticationEventPublisher`（`core/.../authentication/DefaultAuthenticationEventPublisher.java`，把常见异常映射为具体事件）。
4. **parent 兜底**（⑥）：一般用不到，是为旧命名空间配置的层级回退准备的。

## 2.5 DaoAuthenticationProvider：用户名密码认证的完整生命周期

先说白话：表单提交了 username/password，总得有个"查库 + 比密码"的实现。`DaoAuthenticationProvider` 就是那个标准实现：**从 `UserDetailsService` 按用户名加载 `UserDetails`，再用 `PasswordEncoder.matches()` 比对密码**。它是模板方法模式的经典教科书——父类 `AbstractUserDetailsAuthenticationProvider` 定死流程骨架，子类只填两个空。

### 2.5.1 父类的流程骨架（模板方法）

【源码证据】`core/src/main/java/org/springframework/security/authentication/dao/AbstractUserDetailsAuthenticationProvider.java` 第 134-180 行（节选）：

```java
@Override
public Authentication authenticate(Authentication authentication) throws AuthenticationException {
    Assert.isInstanceOf(UsernamePasswordAuthenticationToken.class, authentication, ...);
    String username = determineUsername(authentication);
    boolean cacheWasUsed = true;
    UserDetails user = this.userCache.getUserFromCache(username);      // ① 查用户缓存
    if (user == null) {
        cacheWasUsed = false;
        user = retrieveUser(username, (UsernamePasswordAuthenticationToken) authentication);  // ② 子类实现：加载用户
        // UsernameNotFoundException 在 hideUserNotFoundExceptions=true 时
        // 统一转成 BadCredentialsException —— 不向攻击者泄露"用户名是否存在"
    }
    performPreCheck(user, authentication);      // ③ 前置检查：锁定/禁用/过期（预置 DefaultPreAuthenticationChecks）
    this.postAuthenticationChecks.check(user);  // ④ 后置检查：密码是否过期
    if (!cacheWasUsed) {
        this.userCache.putUserInCache(user);
    }
    return createSuccessAuthentication(principalToReturn, authentication, user);  // ⑤ 造成功票据
}
```

其中 ③④ 的内置检查（`DefaultPreAuthenticationChecks`/`DefaultPostAuthenticationChecks`，同文件后半部分）分别校验 `isAccountNonLocked/isEnabled/isAccountNonExpired` 与 `isCredentialsNonExpired`——这就是为什么你的 `UserDetails` 实现不能无脑返回 true 的原因：这些布尔位是框架真实消费的安全语义。

### 2.5.2 子类填的两个空 + 三重安全细节

【源码证据】`core/src/main/java/org/springframework/security/authentication/dao/DaoAuthenticationProvider.java` 第 49、56-59、80-94、101-123 行：

```java
public class DaoAuthenticationProvider extends AbstractUserDetailsAuthenticationProvider {

    private static final String USER_NOT_FOUND_PASSWORD = "userNotFoundPassword";

    private Supplier<PasswordEncoder> passwordEncoder = SingletonSupplier
        .of(PasswordEncoderFactories::createDelegatingPasswordEncoder);   // ① 默认 Delegating 密码编码器

    @Override
    protected void additionalAuthenticationChecks(UserDetails userDetails,
            UsernamePasswordAuthenticationToken authentication) throws AuthenticationException {
        if (authentication.getCredentials() == null) {
            throw new BadCredentialsException(...);                        // ② 空密码直接拒绝
        }
        String presentedPassword = authentication.getCredentials().toString();
        if (!this.passwordEncoder.get().matches(presentedPassword, userDetails.getPassword())) {
            throw new BadCredentialsException(...);                        // ③ 密码不匹配：只说"凭据错误"
        }
    }

    @Override
    protected final UserDetails retrieveUser(String username, UsernamePasswordAuthenticationToken authentication)
            throws AuthenticationException {
        prepareTimingAttackProtection();                                   // ④ 见下文
        try {
            UserDetails loadedUser = this.getUserDetailsService().loadUserByUsername(username);
            if (loadedUser == null) {
                throw new InternalAuthenticationServiceException("UserDetailsService returned null, ...");
            }
            return loadedUser;
        }
        catch (UsernameNotFoundException ex) {
            mitigateAgainstTimingAttack(authentication);                   // ⑤ 时序攻击防护
            throw ex;
        }
        ...
    }
```

三个值得记住的安全细节：

- **时序攻击防护**（④⑤）：用户不存在时，若直接抛异常，响应会比"正常比对密码"快一个数量级——攻击者据此枚举出哪些用户名存在。框架的对策：提前把一个假密码编码好（`prepareTimingAttackProtection`，第 146-150 行），用户不存在时也执行一次 `matches()`（`mitigateAgainstTimingAttack`，第 152-158 行），把响应时间拉平。
- **错误信息收敛**（②③ 与父类）：无论用户不存在、密码错误，对外统一是 `Bad credentials`——用户枚举的第二道闸。
- **默认就是 DelegatingPasswordEncoder**（①）：不配置 `PasswordEncoder` 时使用 `PasswordEncoderFactories.createDelegatingPasswordEncoder()`，即 `{bcrypt}...` 前缀体系（见第七章）。

### 2.5.3 认证成功后：泄露检测与密码升级

【源码证据】`DaoAuthenticationProvider.java` 第 125-144 行：

```java
@Override
protected Authentication createSuccessAuthentication(Object principal, Authentication authentication,
        UserDetails user) {
    String presentedPassword = authentication.getCredentials().toString();
    boolean isPasswordCompromised = this.compromisedPasswordChecker != null
        && this.compromisedPasswordChecker.check(presentedPassword).isCompromised();   // ① 6.3+：泄露密码检测
    if (isPasswordCompromised) {
        throw new CompromisedPasswordException("The provided password is compromised, please change your password");
    }
    boolean upgradeEncoding = existingEncodedPassword != null
        && !Objects.equals(this.userDetailsPasswordService, UserDetailsPasswordService.NOOP)
        && this.passwordEncoder.get().upgradeEncoding(existingEncodedPassword);        // ② 是否需要升级编码
    if (upgradeEncoding) {
        String newPassword = this.passwordEncoder.get().encode(presentedPassword);
        user = this.userDetailsPasswordService.updatePassword(user, newPassword);      // ③ 顺手把密文升级
    }
    return super.createSuccessAuthentication(principal, authentication, user);
}
```

这就是"算法平滑升级"的落地：存量密文还是老算法（如 SHA-1），用户正常登录的瞬间，框架趁明文在手，用新算法重编码并写回（②③，前提是你实现了 `UserDetailsPasswordService`）——**不需要一次性的"全员重置密码"运动**。另外，成功的票据里会被追加一个 `FactorGrantedAuthority`（父类 `createSuccessAuthentication` 第 226-231 行：`authorities.add(FactorGrantedAuthority.fromAuthority(AUTHORITY))`，因子即"密码"），这是 7.x 引入的认证因子概念：多因素认证时可以区分"这权限是凭密码获得的还是凭第二因子获得的"。

## 2.6 UserDetailsService 与 UserDetails：用户数据的抽象

先说白话：`DaoAuthenticationProvider` 不关心用户在数据库、LDAP 还是内存里，它只依赖一个方法签名——这就是 `UserDetailsService`：

```java
public interface UserDetailsService {
    UserDetails loadUserByUsername(String username) throws UsernameNotFoundException;
}
```

`UserDetails` 是"框架视角的用户"：除了 `getUsername()/getPassword()`，还有四个安全布尔位（`isAccountNonExpired/isAccountNonLocked/isCredentialsNonExpired/isEnabled`）和 `getAuthorities()`。日常开发最省事的做法：不自己实现接口，用框架提供的 `org.springframework.security.core.userdetails.User.builder()`（`User implements UserDetails`）或 `InMemoryUserDetailsManager`（`provisioning` 包）做适配，把你的数据库实体转成 `User`。

按用户名加载是框架对你的最小要求；批量管理（增删改用户）是另一个接口 `UserDetailsManager`（同包），`InMemoryUserDetailsManager`、`JdbcUserDetailsManager`（jdbc 依赖，optional）都实现了它。

## 2.7 认证事件与凭据擦除（小结前的一点补充）

- **事件**：`ProviderManager` 发布的成功事件是 `AuthenticationSuccessEvent`（管理器层面）；表单登录过滤器还会额外发一个 `InteractiveAuthenticationSuccessEvent`（交互层面，见 3.5.3 节）。两者区分了"系统认证"与"人肉登录"，监听时按需选择。
- **擦除**：2.4 节的 ⑦ 步骤完成后，成功票据里的 `credentials` 已被置空（`UsernamePasswordAuthenticationToken.eraseCredentials` 会清掉字段并清空 details 中可能含敏感信息的部分）。业务代码里"登录后再去 getCredentials() 拿密码"是拿不到的——这是设计如此，不是 bug。

## 2.8 本章小结

- `SecurityContextHolder` 是静态门面 + 可换策略（默认 ThreadLocal），"延迟上下文"（5.8+）为过滤器链省掉无谓的 Session 反序列化；请求结束必须清卡，这个纪律由 `FilterChainProxy` 履行（第三章）。
- `Authentication` 一票两用：`unauthenticated` 是请求，`authenticated` 是结果，`authenticated` 布尔位是授权体系的信任边界。
- `ProviderManager` 是认证的总调度：轮询 `supports()`，`AccountStatusException`/内部错误短路，成功后擦凭据、发事件，失败统一收敛信息。
- `DaoAuthenticationProvider` 是模板方法：父类定骨架（缓存→加载→前置检查→后置检查→造票据），子类填"加载用户"与"比对密码"两个空；时序攻击防护、错误信息收敛、密码升级编码是它身上最值得学安全设计。
- 用户数据的抽象窄到只有一个方法（`loadUserByUsername`），这是全框架"策略接口"哲学的第一课。

---
# 三、过滤器链（spring-security-web）：一次 HTTP 请求的安检全程

## 3.1 模块定位与包结构

spring-security-web 承载了框架 80% 的日常体感：所有 Servlet 过滤器、URL 匹配器、防火墙、CSRF/Headers/Session 设施都在这里。主要子包：

| 子包 | 职责 |
|---|---|
| `...web`（根） | FilterChainProxy、SecurityFilterChain、DefaultSecurityFilterChain、AuthenticationEntryPoint 等"链级"设施 |
| `...web.util.matcher` | RequestMatcher 族（PathPatternRequestMatcher、AntPathRequestMatcher…） |
| `...web.firewall` | HttpFirewall、StrictHttpFirewall、FirewalledRequest |
| `...web.context` | SecurityContextHolderFilter、SecurityContextRepository 族 |
| `...web.csrf` / `...web.header` | CsrfFilter、HeaderWriterFilter |
| `...web.authentication` | AbstractAuthenticationProcessingFilter、UsernamePasswordAuthenticationFilter、AnonymousAuthenticationFilter、ott（一次性令牌） |
| `...web.authentication.www` | BasicAuthenticationFilter、DigestAuthenticationFilter |
| `...web.access` | ExceptionTranslationFilter、AccessDeniedHandler、intercept（AuthorizationFilter/FilterSecurityInterceptor） |
| `...web.session` | SessionManagementFilter、ConcurrentSessionFilter |
| `...web.savedrequest` | RequestCache（登录后回跳） |
| `...web.reactive` | 上述核心概念的反应式对应物（5.0 从独立模块并入） |

## 3.2 DelegatingFilterProxy：Servlet 世界到 Spring 世界的桥

先说白话：Servlet 容器（Tomcat）只认识 web.xml / `@WebFilter` 注册的过滤器，它不会去 Spring 容器里找 Bean。**`DelegatingFilterProxy`（定义在 spring-web，不属于 Spring Security）就是一个"转接头"**：容器持有它这个空壳，它内部按 Bean 名字从 Spring 容器里取出真正的过滤器并转发调用——于是安全过滤器就能享受依赖注入、生命周期管理等容器能力。

Spring Security 约定的 Bean 名字是 `springSecurityFilterChain`（常量 `AbstractSecurityWebApplicationInitializer.DEFAULT_FILTER_NAME`，`web/.../context/AbstractSecurityWebApplicationInitializer.java`）。Boot 4 中这个注册动作由 `spring-boot-security` 模块完成（见 5.6 节）；无 Boot 时由 `AbstractSecurityWebApplicationInitializer` 或 `SecurityFilterAutoConfiguration` 完成。

这条桥还解决了**生命周期错配**：Servlet 容器管理的过滤器会在容器启停时 init/destroy，但真正的过滤器是 Spring Bean——`FilterChainProxy` 的 javadoc 明确说明它不调用内部过滤器的 Servlet 生命周期方法，由 IoC 容器接管（`FilterChainProxy.java:133-139`）。

## 3.3 FilterChainProxy：安检的总闸

先说白话：容器调用链只到 `DelegatingFilterProxy` 为止，之后所有安全逻辑都由 `FilterChainProxy` 调度。它干四件事：**① 防火墙包裹请求；② 按 URL 选一条 SecurityFilterChain；③ 把选出的过滤器串成"虚拟过滤链"执行；④ 请求结束清空身份上下文**。

【源码证据】`web/src/main/java/org/springframework/security/web/FilterChainProxy.java` 第 146-165、186-257 行：

```java
public class FilterChainProxy extends GenericFilterBean {

    private List<SecurityFilterChain> filterChains;                 // 候选链（可多条，按顺序匹配）
    private FilterChainValidator filterChainValidator = new NullFilterChainValidator();
    private HttpFirewall firewall = new StrictHttpFirewall();       // 默认防火墙
    private RequestRejectedHandler requestRejectedHandler = new HttpStatusRequestRejectedHandler();
    private FilterChainDecorator filterChainDecorator = new VirtualFilterChainDecorator();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        boolean clearContext = request.getAttribute(FILTER_APPLIED) == null;
        if (!clearContext) {
            doFilterInternal(request, response, chain);
            return;
        }
        try {
            request.setAttribute(FILTER_APPLIED, Boolean.TRUE);
            doFilterInternal(request, response, chain);
        }
        catch (Exception ex) {                                      // 防火墙拒绝 → 统一交 RequestRejectedHandler
            Throwable[] causeChain = this.throwableAnalyzer.determineCauseChain(ex);
            Throwable requestRejectedException = this.throwableAnalyzer
                .getFirstThrowableOfType(RequestRejectedException.class, causeChain);
            if (!(requestRejectedException instanceof RequestRejectedException)) {
                throw ex;
            }
            this.requestRejectedHandler.handle((HttpServletRequest) request,
                    (HttpServletResponse) response, (RequestRejectedException) requestRejectedException);
        }
        finally {
            this.securityContextHolderStrategy.clearContext();      // ★ 请求结束，清卡！
            request.removeAttribute(FILTER_APPLIED);
        }
    }

    private void doFilterInternal(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        FirewalledRequest firewallRequest = this.firewall.getFirewalledRequest((HttpServletRequest) request);
        HttpServletResponse firewallResponse = this.firewall.getFirewalledResponse((HttpServletResponse) response);
        List<Filter> filters = getFilters(firewallRequest);         // 按 URL 选链
        if (filters == null || filters.isEmpty()) {                 // 没有链匹配 → 原链直通
            firewallRequest.reset();
            this.filterChainDecorator.decorate(chain).doFilter(firewallRequest, firewallResponse);
            return;
        }
        FilterChain reset = (req, res) -> {                         // 虚拟链走完 → 恢复路径语义，回到容器主链
            firewallRequest.reset();
            chain.doFilter(req, res);
        };
        this.filterChainDecorator.decorate(reset, filters).doFilter(firewallRequest, firewallResponse);
    }

    private @Nullable List<Filter> getFilters(HttpServletRequest request) {
        for (SecurityFilterChain chain : this.filterChains) {
            if (chain.matches(request)) {
                return chain.getFilters();                          // ★ 只取第一条命中的链
            }
        }
        return null;
    }
}
```

逐点拆解：

- **FILTER_APPLIED 与 finally 清卡**：`doFilter` 里 finally 的 `clearContext()` 是全框架最重要的卫生纪律——ThreadLocal 身份卡不清理，Tomcat 复用线程就会把上一个用户的身份带给下一个请求。`FILTER_APPLIED` 防的是 forward/error 分发导致的重复执行与提前清卡。
- **多链匹配、首条命中即止**：你可以定义多条 `SecurityFilterChain`（比如 API 前缀一条、管理后台一条、其余一条），`getFilters` 按声明顺序只匹配**第一条**命中的链——所以**更特殊的匹配规则必须放在前面**，这与 Spring MVC 的 HandlerMapping 同一个哲学。
- **firewallRequest.reset()**：`StrictHttpFirewall` 包装的请求为了统一路径匹配语义，会规范化 `servletPath`/`pathInfo`；离开安检区时 reset 把原始值还回去，业务代码感知不到包装。
- **FilterChainDecorator（6.0 新增）**：默认 `VirtualFilterChainDecorator` 产出下面的虚拟链；配合 Micrometer Observation 时可换成 `ObservationFilterChainDecorator` 给整条链的执行打观测 span（这就是 1.6 节说的 6.0 观测集成点）。

【源码证据】虚拟链本体，`FilterChainProxy.java` 第 359-390 行：

```java
private static final class VirtualFilterChain implements FilterChain {
    private final FilterChain originalChain;        // 容器主链
    private final List<Filter> additionalFilters;   // 本链的安全过滤器
    private int currentPosition = 0;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response) throws ... {
        if (this.currentPosition == this.size) {
            this.originalChain.doFilter(request, response);   // 安检全部通过 → 交回 DispatcherServlet
            return;
        }
        this.currentPosition++;
        Filter nextFilter = this.additionalFilters.get(this.currentPosition - 1);
        nextFilter.doFilter(request, response, this);          // 责任链：把"自己"当 next 传下去
    }
}
```

这与 Spring AOP 的 `ReflectiveMethodInvocation#proceed()` 是同一思想的责任链，只是发生在 HTTP 请求维度。**每个过滤器都可以拒绝请求继续往下走**（直接写响应返回），也可以只旁观；"安检没过就到不了 DispatcherServlet"由此保证。

## 3.4 SecurityFilterChain 与 FilterOrderRegistration：标准过滤器全景表

`SecurityFilterChain` 只有两个职责：`matches(request)`（本链服务哪些请求）与 `getFilters()`（本链的过滤器列表），默认实现 `DefaultSecurityFilterChain`（`web/.../DefaultSecurityFilterChain.java`）。而"过滤器们在链内的顺序"由配置模块的**静态注册表**决定：

【源码证据】`config/src/main/java/org/springframework/security/config/annotation/web/builders/FilterOrderRegistration.java` 第 69-131 行：

```java
final class FilterOrderRegistration {
    private static final int INITIAL_ORDER = 100;
    private static final int ORDER_STEP = 100;

    FilterOrderRegistration() {
        Step order = new Step(INITIAL_ORDER, ORDER_STEP);
        put(DisableEncodeUrlFilter.class, order.next());
        put(ForceEagerSessionCreationFilter.class, order.next());
        this.filterToOrder.put("...channel.ChannelProcessingFilter", order.next());
        put(HttpsRedirectFilter.class, order.next());
        order.next(); // gh-8105（占位：为未来过滤器留坑）
        put(WebAsyncManagerIntegrationFilter.class, order.next());
        put(SecurityContextHolderFilter.class, order.next());
        ...
        put(AuthorizationFilter.class, order.next());
        put(SwitchUserFilter.class, order.next());
    }
}
```

每个过滤器占一个 100 的步长槽位；`HttpSecurity.addFilter()` 时用 `getOrder(clazz)`（沿父类向上查，第 150-159 行）给它排位。把注册表展开成人类可读的全景表（同一行内"上游在上、下游在下"，即请求经过的顺序）：

| # | 槽位 | 过滤器 | 职责一句话 |
|---|---|---|---|
| 1 | 100 | DisableEncodeUrlFilter | 阻止 Session Id 写入 URL（防泄露） |
| 2 | 200 | ForceEagerSessionCreationFilter | 提前创建 Session（可选） |
| 3 | 300 | ChannelProcessingFilter | 旧式 http/https 通道声明（REQUIRES_SECURE_CHANNEL） |
| 4 | 400 | HttpsRedirectFilter | HTTP → HTTPS 重定向 |
| 5 | 600 | WebAsyncManagerIntegrationFilter | 让异步 Servlet 处理可拿到当前 SecurityContext |
| 6 | 700 | **SecurityContextHolderFilter** | 从 SecurityContextRepository 加载身份卡（延迟） |
| 7 | 800 | SecurityContextPersistenceFilter | 旧版上下文过滤器（5.7 起废弃，仅兼容） |
| 8 | 900 | HeaderWriterFilter | 写安全响应头（CSP、HSTS、X-Content-Type-Options…） |
| 9 | 1000 | CorsFilter | CORS 预检与响应头（复用 spring-web 的实现） |
| 10 | 1100 | **CsrfFilter** | CSRF 令牌校验 |
| 11 | 1200 | **LogoutFilter** | 拦截登出 URL（默认 POST /logout）执行登出 |
| 12 | 1300 | OAuth2AuthorizationRequestRedirectFilter | OAuth2 登录第一步：重定向到授权服务器 |
| 13 | 1400 | Saml2WebSsoAuthenticationRequestFilter | SAML 登录第一步：发 SAML 请求 |
| 14 | 1500 | GenerateOneTimeTokenFilter | 一次性令牌登录：发码（6.4+） |
| 15 | 1600 | X509AuthenticationFilter | 客户端证书认证 |
| 16 | 1700 | AbstractPreAuthenticatedProcessingFilter | 预认证（外部网关已认证的场景） |
| 17 | 1800 | CasAuthenticationFilter | CAS 票据校验 |
| 18 | 1900 | OAuth2LoginAuthenticationFilter | OAuth2 回调处理（/login/oauth2/code/*） |
| 19 | 2000 | Saml2WebSsoAuthenticationFilter | SAML 回调处理 |
| 20 | 2100 | **UsernamePasswordAuthenticationFilter** | 表单登录（POST /login） |
| 21 | 2200 | OneTimeTokenAuthenticationFilter | 一次性令牌校验（6.4+） |
| 22 | 2400 | DefaultResourcesFilter | 默认登录页的 css 资源 |
| 23 | 2500 | DefaultLoginPageGeneratingFilter | 自动生成登录页（无自定义页时） |
| 24 | 2600 | DefaultLogoutPageGeneratingFilter | 自动生成登出确认页（GET /logout） |
| 25 | 2700 | DefaultOneTimeTokenSubmitPageGeneratingFilter | 一次性令牌提交页 |
| 26 | 2800 | ConcurrentSessionFilter | 会话并发控制：检查会话是否已被挤下线/过期 |
| 27 | 2900 | DigestAuthenticationFilter | HTTP Digest（遗留） |
| 28 | 3000 | BearerTokenAuthenticationFilter | JWT/不透明令牌校验（资源服务器） |
| 29 | 3100 | BasicAuthenticationFilter | HTTP Basic 认证 |
| 30 | 3200 | AuthenticationFilter | 6.0+ 通用认证过滤器（Converter + Manager 组合） |
| 31 | 3300 | RequestCacheAwareFilter | 登录成功后恢复之前被拦截的请求 |
| 32 | 3400 | SecurityContextHolderAwareRequestFilter | 把 Servlet API 包装出 getUserPrincipal/isUserInRole |
| 33 | 3500 | JaasApiIntegrationFilter | JAAS 集成 |
| 34 | 3600 | RememberMeAuthenticationFilter | Remember-Me Cookie 免登录 |
| 35 | 3700 | **AnonymousAuthenticationFilter** | 没有身份就给个"匿名身份" |
| 36 | 3800 | OAuth2AuthorizationCodeGrantFilter | OAuth2 授权码模式（客户端态） |
| 37 | 3900 | SessionManagementFilter | 会话固定防护、会话超时策略 |
| 38 | 4000 | **ExceptionTranslationFilter** | 把认证/授权异常翻译成 401/403 动作 |
| 39 | 4100 | FilterSecurityInterceptor | 旧版授权拦截器（废弃，7.0 迁入 access 模块） |
| 40 | 4200 | **AuthorizationFilter** | 新版授权拦截器（5.5+，默认启用） |
| 41 | 4300 | SwitchUserFilter | 身份切换（模拟其他用户，需授权） |

这张表是第三章的地图。**读它的三个方法**：① 上下文相关（#6）永远在最前面，因为后面所有人都要用身份；② 各种"认证过滤器"（#12~#30）都在**授权（#40）之前**——先确定你是谁，再决定你能不能过；③ `ExceptionTranslationFilter`（#38）在 `AuthorizationFilter`（#40）**前面**——因为授权抛的异常要由它兜住翻译（这就是"异常翻译器必须包住决策器"的经典责任链布局）。

## 3.5 重点过滤器逐个解析

### 3.5.1 SecurityContextHolderFilter：把身份卡从仓库取出来挂上（5.7+）

先说白话：请求进来时还不知道你是谁。这个过滤器问 `SecurityContextRepository`（"仓库"）要身份：Session 里存的、请求属性里的……拿到（或没拿到）就挂到 `SecurityContextHolder` 上。它取代了旧版 `SecurityContextPersistenceFilter`，关键区别是**延迟加载 + 显式保存**：旧版每请求必反序列化 Session 且在过滤器层面自动保存；新版只挂一个 `Supplier`，真正要用时才加载，保存动作改由认证成功处显式调用。

【源码证据】`web/src/main/java/org/springframework/security/web/context/SecurityContextHolderFilter.java` 第 72-88 行：

```java
private void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
    if (request.getAttribute(FILTER_APPLIED) != null) {
        chain.doFilter(request, response);
        return;
    }
    request.setAttribute(FILTER_APPLIED, Boolean.TRUE);
    Supplier<SecurityContext> deferredContext = this.securityContextRepository.loadDeferredContext(request);
    try {
        this.securityContextHolderStrategy.setDeferredContext(deferredContext);   // 只挂 Supplier，不加载
        chain.doFilter(request, response);
    }
    finally {
        this.securityContextHolderStrategy.clearContext();                         // 本过滤器兜底清卡
        request.removeAttribute(FILTER_APPLIED);
    }
}
```

仓库的默认组合在配置层（`config/.../configurers/SecurityContextConfigurer.java` 第 102-103 行）：`DelegatingSecurityContextRepository(RequestAttributeSecurityContextRepository, HttpSessionSecurityContextRepository)`——先看请求属性（同请求内认证成功后的即时可见），再找 Session（跨请求的登录态，Session 属性键 `SPRING_SECURITY_CONTEXT`，`HttpSessionSecurityContextRepository.java:92`）。

### 3.5.2 CsrfFilter：令牌校验与 BREACH 防护

先说白话：CSRF 是"浏览器带着你的 Cookie 替攻击者发请求"。防御的经典做法是给每个会话发一个随机令牌：服务端渲染的表单里埋一份，提交时带回，服务端比对——攻击者拿不到令牌，其伪造的表单就过不了关。

【源码证据】`web/src/main/java/org/springframework/security/web/csrf/CsrfFilter.java` 第 85-136 行：

```java
private CsrfTokenRequestHandler requestHandler = new XorCsrfTokenRequestAttributeHandler();

@Override
protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
    DeferredCsrfToken deferredCsrfToken = this.tokenRepository.loadDeferredToken(request, response);  // ① 懒加载令牌
    request.setAttribute(DeferredCsrfToken.class.getName(), deferredCsrfToken);
    this.requestHandler.handle(request, response, deferredCsrfToken);          // ② 把令牌暴露为请求属性（页面可取）
    if (!this.requireCsrfProtectionMatcher.matches(request)) {                 // ③ GET/HEAD/TRACE/OPTIONS 免检
        filterChain.doFilter(request, response);
        return;
    }
    CsrfToken csrfToken = deferredCsrfToken.get();
    String actualToken = this.requestHandler.resolveCsrfTokenValue(request, csrfToken);  // ④ 从表单/头取令牌
    if (!equalsConstantTime(csrfToken.getToken(), actualToken)) {              // ⑤ 常数时间比较
        ...
        this.accessDeniedHandler.handle(request, response, exception);
        return;
    }
    filterChain.doFilter(request, response);
}
```

三个细节：**① 默认只保护非安全方法**（`DEFAULT_CSRF_MATCHER`：GET/HEAD/TRACE/OPTIONS 之外都算需要保护——只读请求不应有副作用）；**② XorCsrfTokenRequestAttributeHandler**（5.8+）是 BREACH 攻击防护：渲染时给令牌做一次 XOR + Base64 编码，页面上出现的值每次都不同，但解码后与 Session 里的令牌等值，攻击者无法通过观测密文推测令牌；**③ `equalsConstantTime`**（第 188 行）用常数时间比较防时序侧信道。默认令牌存 Session（`HttpSessionCsrfTokenRepository`）或 Cookie（`CookieCsrfTokenRepository.withHttpOnlyFalse()`，前后端分离常用）。

### 3.5.3 AbstractAuthenticationProcessingFilter 与 UsernamePasswordAuthenticationFilter：表单登录的一生

先说白话：登录页提交 `POST /login`，需要一个过滤器认领这个 URL、取参数、调认证管理器、按结果跳转。`AbstractAuthenticationProcessingFilter` 把这个流程做成模板方法，子类只填"从请求里提取认证请求"这一步。

【源码证据】`web/src/main/java/org/springframework/security/web/authentication/AbstractAuthenticationProcessingFilter.java` 第 243-291 行（核心流程）：

```java
private void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws IOException, ServletException {
    if (!requiresAuthentication(request, response)) {      // ① 不是 POST /login？放行去下一站
        chain.doFilter(request, response);
        return;
    }
    try {
        Authentication authenticationResult = attemptAuthentication(request, response);  // ② 模板方法：认证
        if (authenticationResult == null) {
            ...                                             // 多阶段认证（如 OAuth2）中途回 null：直接返回
        }
        Authentication current = this.securityContextHolderStrategy.getContext().getAuthentication();
        if (shouldPerformMfa(current, authenticationResult)) {     // ③ 7.1+：MFA 合并已认证因子
            authenticationResult = authenticationResult.toBuilder().authorities((a) -> { ... }).build();
        }
        this.sessionStrategy.onAuthentication(authenticationResult, request, response);  // ④ 会话策略（防会话固定）
        successfulAuthentication(request, response, chain, authenticationResult);
    }
    catch (InternalAuthenticationServiceException failed) {
        unsuccessfulAuthentication(request, response, failed);   // ⑤ 失败路径
    }
    catch (AuthenticationException ex) {
        unsuccessfulAuthentication(request, response, ex);
    }
}
```

成功/失败的收尾（第 393-428 行）各自是固定的四步/三步：

```java
protected void successfulAuthentication(...) {
    SecurityContext context = this.securityContextHolderStrategy.createEmptyContext();
    context.setAuthentication(authResult);
    this.securityContextHolderStrategy.setContext(context);         // ① 挂卡到 ThreadLocal
    this.securityContextRepository.saveContext(context, request, response);  // ② 显式存仓（6.x 语义！）
    this.rememberMeServices.loginSuccess(request, response, authResult);     // ③ 通知 Remember-Me 发 Cookie
    if (this.eventPublisher != null) {
        this.eventPublisher.publishEvent(new InteractiveAuthenticationSuccessEvent(authResult, this.getClass()));
    }
    this.successHandler.onAuthenticationSuccess(request, response, authResult);  // ④ 跳转（默认回原请求或 /）
}

protected void unsuccessfulAuthentication(...) {
    this.securityContextHolderStrategy.clearContext();              // ① 清卡
    this.rememberMeServices.loginFail(request, response);           // ② 清 Remember-Me
    this.failureHandler.onAuthenticationFailure(request, response, failed);  // ③ 跳转失败页（默认重定向 /login?error）
}
```

`attemptAuthentication` 的默认实现（第 358-369 行）已经通用化：`authenticationConverter.convert(request)` 提取 + `authenticationManager.authenticate()` 执行。子类 `UsernamePasswordAuthenticationFilter` 干的事就剩取参数：

【源码证据】`web/.../authentication/UsernamePasswordAuthenticationFilter.java` 第 50-88 行（节选）：

```java
public class UsernamePasswordAuthenticationFilter extends AbstractAuthenticationProcessingFilter {
    public static final String SPRING_SECURITY_FORM_USERNAME_KEY = "username";
    public static final String SPRING_SECURITY_FORM_PASSWORD_KEY = "password";
    private static final RequestMatcher DEFAULT_PATH_REQUEST_MATCHER = PathPatternRequestMatcher.withDefaults()
        .matcher(HttpMethod.POST, "/login");                       // 7.0 默认匹配器已换 PathPattern

    @Override
    public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response)
            throws AuthenticationException {
        if (this.postOnly && !request.getMethod().equals("POST")) {
            throw new AuthenticationServiceException("Authentication method not supported: " + request.getMethod());
        }
        String username = obtainUsername(request);
        String password = obtainPassword(request);
        UsernamePasswordAuthenticationToken authRequest = UsernamePasswordAuthenticationToken.unauthenticated(username,
                password);                                          // 半成品票据
        setDetails(request, authRequest);
        return this.getAuthenticationManager().authenticate(authRequest);   // 交给第二章的 ProviderManager
    }
}
```

到这里，第二章与第三章会师：**过滤器负责"认领请求 + 处置结果"，Provider 体系负责"真正的认证决策"**。默认跳转逻辑由 `SavedRequestAwareAuthenticationSuccessHandler` 完成：用 `RequestCacheAwareFilter`/`HttpSessionRequestCache` 存下的原始请求（登录前被 `ExceptionTranslationFilter` 拦下的那个 URL）实现"登录后回到你本来要去的地方"。

### 3.5.4 ExceptionTranslationFilter：异常的"翻译官"

先说白话：下游（`AuthorizationFilter`、方法安全）只管抛异常——`AuthenticationException`（没认证）或 `AccessDeniedException`（权限不够）。总得有人把这些异常翻译成 HTTP 世界的动作：302 跳登录页、401、403。这就是 `ExceptionTranslationFilter`，它永远紧挨着决策过滤器的前一站。

【源码证据】`web/src/main/java/org/springframework/security/web/access/ExceptionTranslationFilter.java` 第 123-149、188-222 行：

```java
private void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ... {
    try {
        chain.doFilter(request, response);
    }
    catch (Exception ex) {
        Throwable[] causeChain = this.throwableAnalyzer.determineCauseChain(ex);   // 从嵌套异常里挖
        RuntimeException securityException = (AuthenticationException) this.throwableAnalyzer
            .getFirstThrowableOfType(AuthenticationException.class, causeChain);
        if (securityException == null) {
            securityException = (AccessDeniedException) this.throwableAnalyzer
                .getFirstThrowableOfType(AccessDeniedException.class, causeChain);
        }
        if (securityException == null) {
            rethrow(ex);                                   // 不是安全异常？继续往外抛
        }
        handleSpringSecurityException(request, response, chain, securityException);
    }
}

private void handleAccessDeniedException(HttpServletRequest request, HttpServletResponse response,
        FilterChain chain, AccessDeniedException exception) throws ... {
    Authentication authentication = this.securityContextHolderStrategy.getContext().getAuthentication();
    boolean isAnonymous = this.authenticationTrustResolver.isAnonymous(authentication);
    if (isAnonymous || this.authenticationTrustResolver.isRememberMe(authentication)) {
        // 匿名/Remember-Me 用户权限不足 = 其实是"没正式登录"
        AuthenticationException ex = new InsufficientAuthenticationException("Full authentication is required...", exception);
        sendStartAuthentication(request, response, chain, ex);      // → 跳登录页（401 语义）
    }
    else {
        this.accessDeniedHandler.handle(request, response, exception);   // 真登录了但权限不够 → 403
    }
}

protected void sendStartAuthentication(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
        AuthenticationException reason) throws ... {
    SecurityContext context = this.securityContextHolderStrategy.createEmptyContext();
    this.securityContextHolderStrategy.setContext(context);
    this.requestCache.saveRequest(request, response);               // ★ 存下原始请求，登录成功后回跳
    this.authenticationEntryPoint.commence(request, response, reason);   // 开始认证（默认 LoginUrlAuthenticationEntryPoint → 302 /login）
}
```

最精妙的是匿名分支：匿名请求访问受保护资源时，授权器抛的是 `AccessDeniedException`（因为匿名身份有 `ROLE_ANONYMOUS` 但没有目标权限），翻译官用 `AuthenticationTrustResolver` 识别出"这个 AccessDenied 其实是没登录"，改写成 `InsufficientAuthenticationException` 再走登录入口——**一个异常分流逻辑，同时支撑了"游客跳登录页"和"登录用户见 403 页"两种体感**。REST API 场景把 entry point 换成 `BearerTokenAuthenticationEntryPoint`（401）即得标准错误码。

### 3.5.5 AuthorizationFilter：链尾的最终裁决（5.5+）

先说白话：请求走完前面所有安检（已认证、CSRF 合法……）后，最后一个问题：**这个 URL 你被允许访问吗？** `AuthorizationFilter` 把裁决委托给一个 `AuthorizationManager<HttpServletRequest>`（第四章展开），不通过就抛 `AuthorizationDeniedException`，由上一节的翻译官接走。

【源码证据】`web/src/main/java/org/springframework/security/web/access/intercept/AuthorizationFilter.java` 第 76-106 行：

```java
@Override
public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
        throws ServletException, IOException {
    HttpServletRequest request = (HttpServletRequest) servletRequest;
    HttpServletResponse response = (HttpServletResponse) servletResponse;

    if (skipDispatch(request)) {                     // ERROR/ASYNC 分发可配置跳过
        chain.doFilter(request, response);
        return;
    }
    request.setAttribute(alreadyFilteredAttributeName, Boolean.TRUE);
    try {
        AuthorizationResult result = this.authorizationManager.authorize(this::getAuthentication, request);
        this.eventPublisher.publishAuthorizationEvent(this::getAuthentication, request, result);   // 授权事件（可审计）
        if (result != null && !result.isGranted()) {
            throw new AuthorizationDeniedException("Access Denied", result);   // 抛给翻译官
        }
        chain.doFilter(request, response);
    }
    finally {
        request.removeAttribute(alreadyFilteredAttributeName);
    }
}
```

注意两点：授权决策结果可以发布为事件（`AuthorizationGrantedEvent`/`AuthorizationDeniedEvent`，默认 `NoopAuthorizationEventPublisher` 静默）；`authorize` 的第一个参数是 `Supplier<Authentication>`——**连"取身份"都是懒的**，与延迟上下文一脉相承。

### 3.5.6 SessionManagementFilter：会话的守门人

【源码证据】`web/src/main/java/org/springframework/security/web/session/SessionManagementFilter.java`（doFilter 私有重载节选）：

```java
if (!this.securityContextRepository.containsContext(request)) {
    Authentication authentication = this.securityContextHolderStrategy.getContext().getAuthentication();
    if (this.trustResolver.isAuthenticated(authentication)) {
        // 本请求中新认证成功（但仓库里还没有）：执行会话策略（默认 ChangeSessionId 防会话固定），并提前存仓
        this.sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
        this.securityContextRepository.saveContext(this.securityContextHolderStrategy.getContext(), request, response);
    }
    else {
        // 没有身份但带了无效 Session Id → 会话已超时，走 invalidSessionStrategy
        if (request.getRequestedSessionId() != null && !request.isRequestedSessionIdValid()) {
            this.invalidSessionStrategy.onInvalidSessionDetected(request, response);
            ...
```

"会话固定防护"一句话：登录前先给会话换个 ID（Servlet 3.1+ 用 `changeSessionId()`，不丢属性），攻击者预先种下的 session id 立刻作废。并发登录控制（一人最多几个会话）由 `ConcurrentSessionFilter`（#26）+ `SessionAuthenticationStrategy` 组合实现。

### 3.5.7 AnonymousAuthenticationFilter 与小型过滤器速览

- **AnonymousAuthenticationFilter（#35）**：走到这里还没有身份？造一个：principal = `"anonymousUser"`，权限 = `ROLE_ANONYMOUS`。这不是多此一举——它让下游代码永远能拿到非 null 的 `Authentication`，授权规则可以统一写 `hasRole(...)`/`permitAll` 而不必特判 null（3.5.4 节的匿名分流也依赖这个约定身份）。
- **LogoutFilter（#11）**：认领 `POST /logout`，执行 `LogoutHandler` 链（清 Session、清 Remember-Me、清上下文）再走 `LogoutSuccessHandler`。CSRF 默认要求 POST 才能登出——GET /logout 会被当普通请求跳确认页（#24 生成的页面）。
- **RememberMeAuthenticationFilter（#34）**：Session 里没身份但带了有效的 Remember-Me Cookie 时，拼一个 `RememberMeAuthenticationToken` 交给 `AuthenticationManager` 免密认证。
- **HeaderWriterFilter（#8）**：写一组默认安全响应头（`X-Content-Type-Options: nosniff`、`Cache-Control`、HSTS、`Referrer-Policy` 等），`headers()` DSL 可逐项定制。
- **RequestCacheAwareFilter（#31）**：登录成功后那次请求如果直接命中了登录页路径，从 `RequestCache` 里捞出原始请求放行——与 3.5.4 的 saveRequest、3.5.3 的 SavedRequestAware 三件套组成"回跳闭环"。

## 3.6 防火墙：StrictHttpFirewall

先说白话：Servlet 容器对畸形 URL 的容忍度各有不同，路径参数（`;jsessionid=`）、双斜杠、URL 编码的斜杠（`%2F`）等都可能让"你在 HttpSecurity 里写的匹配规则"被绕过。`FilterChainProxy` 在安检第一步先用 `StrictHttpFirewall` 把请求过一遍筛子，不合法直接 `RequestRejectedException`（由 3.3 节的 `RequestRejectedHandler` 处理，默认 400）。

【源码证据】`web/src/main/java/org/springframework/security/web/firewall/StrictHttpFirewall.java`（拒绝项摘要，方法体第 523-527 行附近为非打印 ASCII 检查）：

```java
// 默认拒绝（节选）：非打印 ASCII 字符、URL 编码的斜杠/点、路径双斜杠、
// 分号（除非显式放开 allowSemicolon）、非法 HTTP 方法名、缺失 Host 头等
rejectNonPrintableAsciiCharactersInFieldName(request.getRequestURI(), "requestURI");
```

它包装出的 `FirewalledRequest` 还承担 3.3 节说的"路径规范化 + reset 还原"职责。实战中遇到 `RequestRejectedException`（日志常见 "The request was rejected because the URL contained a potentially malicious String ';'..."），先想清楚业务是否真需要那个字符，再考虑自定义 `HttpFirewall` 放行——无脑放行等于拆掉第一道安检门。

## 3.7 本章小结

- 一次请求的安全旅程 = **DelegatingFilterProxy（桥）→ FilterChainProxy（闸：防火墙→选链→虚拟链→清卡）→ 41 个标准槽位**；顺序由 `FilterOrderRegistration` 静态锁定，铁律是"上下文先行、认证先于授权、翻译官紧贴决策器"。
- `FilterChainProxy` 是责任链 + 策略选择的合体：多条 `SecurityFilterChain` 按 URL 首条命中分发，`VirtualFilterChain` 走完交回容器主链；6.0 的 `FilterChainDecorator` 为观测插了标准口子。
- 上下文过滤器的 6.x 语义是"**只挂不存**"：加载走 `loadDeferredContext` 延迟 Supplier，保存由认证成功处显式 `saveContext`——干净且高效。
- 表单登录是理解一切认证方式的模板：**认领 URL → attemptAuthentication（模板方法）→ sessionStrategy → 成功四步/失败三步**；UsernamePasswordAuthenticationFilter 只填了"取参数"一个空。
- `ExceptionTranslationFilter` + `AnonymousAuthenticationFilter` + `RequestCache` 三件套构成"未登录引导闭环"；`AuthorizationFilter` 抛的异常在此被翻译成 302/401/403。
- 防火墙（StrictHttpFirewall）与 CSRF（Xor + 常数时间比较）体现了框架的一贯立场：**默认安全，按需放开**。

---
# 四、授权体系（authorization / access）：谁能做什么

## 4.1 新旧两代授权架构

先说白话：授权 = 拿着身份卡问一句"允不允许"。问题只在"怎么写规则"。Spring Security 经历了两代架构，**第二代没有推翻第一代的概念，只是把"投票"简化为"返回结果"**：

| | 旧架构（~5.7 为主流） | 新架构（5.5/5.7 起，6.x 默认） |
|---|---|---|
| Web 层入口 | `FilterSecurityInterceptor`（拦截器式） | `AuthorizationFilter`（过滤器式，见 3.5.5） |
| 决策接口 | `AccessDecisionManager` + 一组 `AccessDecisionVoter`（投票制：AffirmativeBased 一票通过 / ConsensusBased 少数服从多数 / UnanimousBased 全票通过） | `AuthorizationManager`（单一裁决点，组合式） |
| 决策产物 | void（通过则返回，不通过抛 AccessDeniedException） | `AuthorizationResult`（granted/denied，6.4 起显式返回值） |
| 现状 | **6.0 废弃，7.0 整体迁入 spring-security-access 模块**（类还在，包名不变） | core 的 `authorization` 包 + web 的 `web.authorization` 包 |

为什么换？投票制表达"任意通过/全部通过"很方便，但组合一个"既要角色又要 IP"的规则时要在 Voter 里嵌套逻辑；新架构用**组合函数**表达：`AuthorizationManagers.allOf(a, b)` / `anyOf(...)`，每个 Manager 是纯粹的 `(身份, 对象) → 结果` 函数。旧架构至今保留是为了二进制兼容与少量高级场景（如 ACL）。

## 4.2 AuthorizationManager 接口与 RequestMatcherDelegatingAuthorizationManager

【源码证据】`core/src/main/java/org/springframework/security/authorization/AuthorizationManager.java` 第 33-58 行：

```java
@FunctionalInterface
public interface AuthorizationManager<T extends @Nullable Object> {

    default void verify(Supplier<? extends @Nullable Authentication> authentication, T object) {
        AuthorizationResult result = authorize(authentication, object);
        if (result != null && !result.isGranted()) {
            throw new AuthorizationDeniedException("Access Denied", result);   // 抛异常式（旧习惯）
        }
    }

    @Nullable AuthorizationResult authorize(Supplier<? extends @Nullable Authentication> authentication, T object);
    // @since 6.4：返回值式（新习惯，结果可被事件/审计消费）
}
```

Web 层规则的落地类是 `RequestMatcherDelegatingAuthorizationManager`——**一张"URL 模式 → 决策器"的路由表**：

【源码证据】`web/src/main/java/org/springframework/security/web/access/intercept/RequestMatcherDelegatingAuthorizationManager.java` 第 66-100 行：

```java
@Override
public @Nullable AuthorizationResult authorize(Supplier<? extends @Nullable Authentication> authentication,
        HttpServletRequest request) {
    for (RequestMatcherEntry<AuthorizationManager<? super RequestAuthorizationContext>> mapping : this.mappings) {
        RequestMatcher matcher = mapping.getRequestMatcher();
        MatchResult matchResult = matcher.matcher(request);
        if (matchResult.isMatch()) {
            AuthorizationManager<? super RequestAuthorizationContext> manager = mapping.getEntry();
            return manager.authorize(authentication,
                    new RequestAuthorizationContext(request, matchResult.getVariables()));   // 首条命中即裁决
        }
    }
    return DENY;                                   // ★ 没有规则匹配 → 默认拒绝
}
```

你在 `authorizeHttpRequests` 里写的每一行规则，最终都变成这个 mapping 列表里的一项。注意最后那行 `DENY`：**任何没有显式声明的请求默认拒绝**（`anyRequest().authenticated()` 的兜底意义正在于此）。Builder 还强制了 DSL 顺序合法性（第 128-131 行：`Assert.state(!this.anyRequestConfigured, "Can't configure mappings after anyRequest")`——`anyRequest()` 之后不许再加规则，编译期写不出错误顺序的配置）。

## 4.3 AuthorityAuthorizationManager 与 RoleHierarchy

最常用的决策器是按权限字符串判断：

【源码证据】`core/src/main/java/org/springframework/security/authorization/AuthorityAuthorizationManager.java` 第 68-82、143-146 行：

```java
public static <T> AuthorityAuthorizationManager<T> hasRole(String role) {
    return hasAuthority(ROLE_PREFIX + role);       // 默认前缀 "ROLE_"
}
public static <T> AuthorityAuthorizationManager<T> hasAuthority(String authority) {
    return new AuthorityAuthorizationManager<>(authority);
}
...
public AuthorizationResult authorize(Supplier<? extends @Nullable Authentication> authentication, T object) {
    return this.delegate.authorize(authentication, this.authorities);   // delegate 是 AllAuthoritiesAuthorizationManager
}
```

它内部委托给一个批量判断的 delegate（匹配 `hasAnyAuthority(...)` 语义），核心逻辑朴素：遍历 `Authentication.getAuthorities()` 找匹配字符串。

`ROLE_` 前缀只是**约定**而非语法：`hasRole("ADMIN")` 查的是 `ROLE_ADMIN`，`hasAuthority("ADMIN")` 查的是字面 `ADMIN`。层级角色（`ROLE_ADMIN` 隐含 `ROLE_USER`）由 `RoleHierarchy` 体系实现（`core/.../access/hierarchicalroles/RoleHierarchyImpl.java`，字符串语法 `ROLE_ADMIN > ROLE_USER`），6.3 起支持多行格式并默认接入 `authorizeHttpRequests`——原理是在授权前用 `RoleHierarchyAuthoritiesMapper` 把身份上的权限先做一次"展开"。

## 4.4 表达式语言：SecurityExpressionRoot

`hasRole('ADMIN') and #id == principal.id` 这样的表达式由 SpEL 驱动。所有安全表达式的根对象是 `SecurityExpressionRoot`：

【源码证据】`core/src/main/java/org/springframework/security/access/expression/SecurityExpressionRoot.java` 第 48、244-252 行：

```java
private String defaultRolePrefix = "ROLE_";
...
// hasRole("ADMIN") 或 hasRole("ROLE_ADMIN") 都会归一为 ROLE_ADMIN（defaultRolePrefix 生效时）
```

它提供 `hasRole/hasAnyRole/hasAuthority/permitAll/denyAll/isAuthenticated/isAnonymous/isRememberMe/isFullyAuthenticated` 等方法族；方法级表达式额外可用 `#参数名`（引用方法入参）、`returnObject`（@PostAuthorize 引用返回值）、`principal`。表达式被解析为 `Expression` 后包进 `ExpressionAttribute`，由 `ExpressionUtils.evaluateAsBoolean` 求值——`MethodExpressionAuthorizationManager`（core 的 `authorization/method` 包）就是"SpEL 表达式 → AuthorizationManager"的适配器。

## 4.5 方法级安全：@EnableMethodSecurity 如何变成 AOP

先说白话：URL 规则管"进不进得来"，方法注解管"调不调得动"。`@PreAuthorize` / `@PostAuthorize` 不是 Spring 内置注解，而是 Spring Security 用 **Spring AOP** 实现的切面——这与 `@Transactional` 的落地方式完全同构。

【源码证据】`config/src/main/java/org/springframework/security/config/annotation/method/configuration/EnableMethodSecurity.java` 第 44-80 行：

```java
public @interface EnableMethodSecurity {
    boolean prePostEnabled() default true;      // @PreAuthorize/@PostAuthorize/@PreFilter/@PostFilter（默认开！）
    boolean securedEnabled() default false;     // @Secured（旧注解，JSR-250 风格前时代）
    boolean jsr250Enabled() default false;      // @RolesAllowed/@PermitAll/@DenyAll
    boolean proxyTargetClass() default false;   // CGLIB 类代理 or JDK 接口代理
}
```

实现链路（core 的 `authorization/method` 包 + config 的装配）：`@EnableMethodSecurity` 导入 `MethodSecuritySelector` → 按 enabled 位注册对应的 `AuthorizationManagerBeforeMethodInterceptor`（preAuthorize）与 `AuthorizationManagerAfterMethodInterceptor`（postAuthorize）——它们是 Spring AOP 的 `MethodInterceptor`，配合 `AuthorizationMethodPointcuts`（匹配"带注解的方法"的切点）织入。调用时的裁决链：

```
业务方法调用
 └─ AuthorizationManagerBeforeMethodInterceptor.invoke
     ├─ PreAuthorizeAuthorizationManager（@PreAuthorize 表达式 → MethodExpressionAuthorizationManager）
     │    └─ 不通过：抛 AuthorizationDeniedException（可被 @HandleAuthorizationDenied 指定的 handler 接住，
     │         默认策略 NullReturningMethodAuthorizationDeniedHandler：返回 null 而非抛异常）
     ├─ （真正执行业务方法）
     └─ AuthorizationManagerAfterMethodInterceptor
          └─ PostAuthorizeAuthorizationManager（@PostAuthorize 中可引用 returnObject）
```

6.3 新增的 **@AuthorizeReturnObject** 把"授权返回值"推进一步：方法返回的对象被 `AuthorizationAdvisorProxyFactory` 包成代理，对象内部每次方法调用都按对象上的 `@Authorize("...")` 注解做权限判断——实现了对返回对象图的字段级访问控制（文件 `core/src/main/java/org/springframework/security/authorization/method/AuthorizeReturnObject.java`，6.3.0 git 实证）。

与旧体系的关系：`@Secured`（旧注解）由 `Jsr250AuthorizationManager` 同族的 secured 变体处理，7.0 起随旧体系住在 access 模块；`@EnableGlobalMethodSecurity` 是旧开关，7.0 已标废弃（git 实证：7.0.0 中该文件存在且含 @Deprecated）。

## 4.6 本章小结

- 授权的第二代架构把"投票"换成"组合"：`AuthorizationManager` 是 `(Supplier<Authentication>, T) → AuthorizationResult` 的函数式接口，`verify()` 兼容抛异常旧习惯。
- Web 规则本质是一张 `RequestMatcher → AuthorizationManager` 路由表（`RequestMatcherDelegatingAuthorizationManager`），**默认拒绝**（无规则命中即 DENY），`anyRequest()` 之后再配规则会被构建器断言拦下。
- `hasRole` 的 `ROLE_` 前缀是约定；角色层级在授权前把身份权限"展开"实现。
- 方法安全复用 Spring AOP：`@EnableMethodSecurity` 开箱即注册 pre/post 两枚拦截器，`@PreAuthorize` 之前裁决、`@PostAuthorize` 之后裁决，6.3 的 `@AuthorizeReturnObject` 把授权延伸到返回对象图。
- 新旧两套体系在 7.0 完成"分家"：旧体系（FilterSecurityInterceptor、AccessDecisionManager、@Secured）迁入 spring-security-access 模块养老，新体系是唯一主线。

---

# 五、配置体系（spring-security-config）：SecurityBuilder 与 HttpSecurity

## 5.1 模块定位

如果说 core/web 是"发动机与车轮"，config 就是"4S 店的装配车间"：`@EnableWebSecurity`、`HttpSecurity`/`WebSecurity` 两个建造者、几十个 `XxxConfigurer`、以及 `springSecurityFilterChain` Bean 的诞生地。核心模式只有一对接口：

```java
public interface SecurityBuilder<O> { O build() throws Exception; }

public interface SecurityConfigurer<O, B extends SecurityBuilder<O>> {
    void init(B builder) throws Exception;
    void configure(B builder) throws Exception;
}
```

与 Spring 容器的 `BeanDefinition → BeanFactory` 类比：**Configurer 是配方，Builder 是生产线，`build()` 产出的对象（SecurityFilterChain / FilterChainProxy）再注册回 Spring 容器**。

## 5.2 AbstractConfiguredSecurityBuilder：build() 的四步生命周期

【源码证据】`config/src/main/java/org/springframework/security/config/annotation/AbstractConfiguredSecurityBuilder.java` 第 56-71、203-222、328-342 行：

```java
public abstract class AbstractConfiguredSecurityBuilder<O, B extends SecurityBuilder<O>>
        extends AbstractSecurityBuilder<O> {

    private final LinkedHashMap<Class<? extends SecurityConfigurer<O, B>>, List<SecurityConfigurer<O, B>>> configurers
            = new LinkedHashMap<>();            // 按类型挂起的配置器
    private BuildState buildState = BuildState.UNBUILT;
    private ObjectPostProcessor<Object> objectPostProcessor;    // 每个产出物都会过一遍后处理
    ...
    private <C extends SecurityConfigurer<O, B>> void add(C configurer) {
        ...
        if (this.buildState.isConfigured()) {
            throw new IllegalStateException("Cannot apply " + configurer + " to already built object");
        }                                        // build 后不许再配
        ...
    }

    @Override
    protected final O doBuild() {
        synchronized (this.configurers) {
            this.buildState = BuildState.INITIALIZING;
            beforeInit();
            init();                              // ① 逐个 configurer.init(builder)——注册Provider/准备过滤器
            this.buildState = BuildState.CONFIGURING;
            beforeConfigure();
            configure();                         // ② 逐个 configurer.configure(builder)——真正往 builder 装东西
            this.buildState = BuildState.BUILDING;
            O result = performBuild();           // ③ 子类实现：产出最终对象
            this.buildState = BuildState.BUILT;
            return result;
        }
    }
}
```

三个细节值得注意：

- **`ObjectPostProcessor`**（第 312-314 行的 `postProcess()`）：Builder 造出的每个对象（过滤器、Provider、Manager）都会被它后处理一遍。这是 Spring Security 版的"BeanPostProcessor"——Boot/扩展借此在**非 Bean 的对象**上叠加行为（如把 `FilterChainProxy` 包上观测装饰）。
- **同类型 Configurer 的覆盖规则**：`add()` 以 Class 为键，默认同类型互相覆盖（后 apply 的赢），`HttpSecurity` 子类的部分场景允许同型并存（`allowConfigurersOfSameType`）。
- **状态机**：UNBUILT → INITIALIZING → CONFIGURING → BUILDING → BUILT，构建后不可再配——这就是为什么 `http.build()` 之后链式调用会抛异常。

## 5.3 HttpSecurity：一个 Configurer 对应一段安检设施

先说白话：`http.csrf(...)`、`http.formLogin(...)` 这些方法只是"把某个 Configurer 装进建造者"的语法糖。`csrf()` 装 `CsrfConfigurer`（它 init 时往链里塞 `CsrfFilter`）、`formLogin()` 装 `FormLoginConfigurer`（它塞 `UsernamePasswordAuthenticationFilter` 并向认证注册表提供 `DaoAuthenticationProvider` 需要的 `UserDetailsService` 引用）……**DSL 的每一节，展开都是第三章那张表里的过滤器/Provider**。

【源码证据】`config/src/main/java/org/springframework/security/config/annotation/web/builders/HttpSecurity.java` 第 148-149、1795-1803、1843-1872 行：

```java
public final class HttpSecurity extends AbstractConfiguredSecurityBuilder<DefaultSecurityFilterChain, HttpSecurity>
        implements SecurityBuilder<DefaultSecurityFilterChain>, HttpSecurityBuilder<HttpSecurity> {
    ...
    @Override
    protected DefaultSecurityFilterChain performBuild() {
        this.filters.sort(OrderComparator.INSTANCE);      // 按 FilterOrderRegistration 的槽位排序
        List<Filter> sortedFilters = new ArrayList<>(this.filters.size());
        for (Filter filter : this.filters) {
            sortedFilters.add(((OrderedFilter) filter).filter);
        }
        return new DefaultSecurityFilterChain(this.requestMatcher, sortedFilters);   // 一条链成型
    }

    public HttpSecurity addFilter(Filter filter) {
        Integer order = this.registrations.getOrder(filter.getClass());
        if (order == null) {
            throw new IllegalArgumentException(filter.getClass().getName()
                + " does not have a registered order and cannot be added without a specified order. ...");
        }
        this.addFilterAtOffsetOf(new OrderedFilter(order, filter), order - 1, OrderedFilter.class);
        return this;
    }
```

`addFilter` 的断言暴露了注册表的本质：**不是"任意过滤器都能进链"，而是"只有登记过槽位的过滤器才有座位"**。自定义过滤器要用 `addFilterBefore/addFilterAfter/addFilterAt`（第 1821-1872 行）借用已有槽位定位。

## 5.4 WebSecurity → springSecurityFilterChain Bean 的诞生

HttpSecurity 产出的是**一条** `SecurityFilterChain`；把它们装配成**总闸** `FilterChainProxy` 的，是第二级建造者 `WebSecurity`（`AbstractConfiguredSecurityBuilder<Filter, WebSecurity>`）。

【源码证据】`config/src/main/java/org/springframework/security/config/annotation/web/builders/WebSecurity.java` 第 305-341 行（节选）：

```java
@Override
protected Filter performBuild() {
    ...
    List<SecurityFilterChain> securityFilterChains = new ArrayList<>(chainSize);
    RequestMatcherDelegatingAuthorizationManager.Builder builder = RequestMatcherDelegatingAuthorizationManager.builder();
    for (RequestMatcher ignoredRequest : this.ignoredRequests) {          // web.ignoring() 的请求
        WebSecurity.this.logger.warn("You are asking Spring Security to ignore " + ignoredRequest
            + ". This is not recommended -- please use permitAll via HttpSecurity#authorizeHttpRequests instead.");
        SecurityFilterChain securityFilterChain = new DefaultSecurityFilterChain(ignoredRequest);  // 空链
        securityFilterChains.add(securityFilterChain);
        ...
    }
    for (SecurityBuilder<? extends SecurityFilterChain> securityFilterChainBuilder : this.securityFilterChainBuilders) {
        SecurityFilterChain securityFilterChain = securityFilterChainBuilder.build();   // ★ 触发每条 HttpSecurity.build()
        securityFilterChains.add(securityFilterChain);
        ...
    }
    FilterChainProxy filterChainProxy = new FilterChainProxy(securityFilterChains);     // 总闸组装
    if (this.httpFirewall != null) { filterChainProxy.setFirewall(this.httpFirewall); }
    ...
    return postProcess(filterChainProxy);
}
```

最后一步注册成 Bean 的动作在 `WebSecurityConfiguration` 里，方法名就是那个著名的 Bean 名：

【源码证据】`config/src/main/java/org/springframework/security/config/annotation/web/configuration/WebSecurityConfiguration.java` 第 113-131 行：

```java
@Bean(name = AbstractSecurityWebApplicationInitializer.DEFAULT_FILTER_NAME)   // "springSecurityFilterChain"
public Filter springSecurityFilterChain(ObjectProvider<HttpSecurity> provider) throws Exception {
    boolean hasFilterChain = !this.securityFilterChains.isEmpty();
    if (!hasFilterChain) {                                          // ★ 用户没定义任何 SecurityFilterChain 时：
        this.webSecurity.addSecurityFilterChainBuilder(() -> {
            HttpSecurity httpSecurity = provider.getObject();
            httpSecurity.authorizeHttpRequests((authorize) -> authorize.anyRequest().authenticated());
            httpSecurity.formLogin(Customizer.withDefaults());      // 兜底默认安全配置
            httpSecurity.httpBasic(Customizer.withDefaults());
            return httpSecurity.build();
        });
    }
    for (SecurityFilterChain securityFilterChain : this.securityFilterChains) {
        this.webSecurity.addSecurityFilterChainBuilder(() -> securityFilterChain);
    }
    for (WebSecurityCustomizer customizer : this.webSecurityCustomizers) {
        customizer.customize(this.webSecurity);                     // web.ignoring() 的入口
    }
    return this.webSecurity.build();
}
```

这段源码就是"**默认安全（secure by default）**"的实现现场：只要 `@EnableWebSecurity` 生效而你又没提供任何 `SecurityFilterChain` Bean，框架就替你配出"全部请求需认证 + 表单登录 + HTTP Basic"。`provider.getObject()` 拿到的 `HttpSecurity` 来自下一节的 prototype Bean。

## 5.5 HttpSecurityConfiguration：默认 HttpSecurity 与"出厂预装"

【源码证据】`config/src/main/java/org/springframework/security/config/annotation/web/configuration/HttpSecurityConfiguration.java` 第 114-144 行：

```java
@Bean(HTTPSECURITY_BEAN_NAME)
@Scope("prototype")                                             // ★ 原型：每次 getObject() 都是全新的一台
HttpSecurity httpSecurity() {
    LazyPasswordEncoder passwordEncoder = new LazyPasswordEncoder(this.context);
    AuthenticationManagerBuilder authenticationBuilder = new DefaultPasswordEncoderAuthenticationManagerBuilder(
            this.objectPostProcessor, passwordEncoder);
    authenticationBuilder.parentAuthenticationManager(authenticationManager());   // 挂全局认证管理器为父
    authenticationBuilder.authenticationEventPublisher(getAuthenticationEventPublisher());
    HttpSecurity http = new HttpSecurity(this.objectPostProcessor, authenticationBuilder, createSharedObjects());
    WebAsyncManagerIntegrationFilter webAsyncManagerIntegrationFilter = new WebAsyncManagerIntegrationFilter();
    ...
    http
        .csrf(withDefaults())
        .addFilter(webAsyncManagerIntegrationFilter)
        .exceptionHandling(withDefaults())
        .headers(withDefaults())
        .sessionManagement(withDefaults())
        .securityContext(withDefaults())
        .requestCache(withDefaults())
        .anonymous(withDefaults())
        .servletApi(withDefaults())
        .with(new DefaultLoginPageConfigurer<>());
    http.logout(withDefaults());
    applyCorsIfAvailable(http);        // 容器里有 CorsConfigurationSource 就自动开 cors()
    applyDefaultConfigurers(http);     // SPI：META-INF/spring.factories 里的 AbstractHttpConfigurer
    applyHttpSecurityCustomizers(...); // Customizer<HttpSecurity> Bean 自动应用（7.x）
    applyTopLevelCustomizers(...);     // 更进一步：扫描 HttpSecurity 上所有 Customizer 参数方法自动应用
    return http;
}
```

出厂预装的 11 个 Configurer 对应第三章全景表里的 #6~#35 大部分设施。两个扩展口子值得记住：`AbstractHttpConfigurer` SPI（spring.factories，可整插件式追加默认配置）；以及 7.x 的 Customizer Bean 自动发现——**写一个 `Customizer<HttpSecurity>` 类型的 Bean 就能给每个 HttpSecurity 打补丁**，无需继承任何东西。

## 5.6 Spring Boot 集成：从启动到第一条过滤器链

以 Boot 4.2（`D:\code\3rd\spring-boot`，commit `7f9eef2c33c`）实证，安全自动配置已模块化为 `spring-boot-security`：

【源码证据】`module/spring-boot-security/src/main/java/org/springframework/boot/security/autoconfigure/web/servlet/ServletWebSecurityAutoConfiguration.java`（节选）：

```java
@AutoConfiguration(after = UserDetailsServiceAutoConfiguration.class, ...)
@ConditionalOnClass(EnableWebSecurity.class)
@ConditionalOnWebApplication(type = Type.SERVLET)
public final class ServletWebSecurityAutoConfiguration {
    ...
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnDefaultServletWebSecurity            // 只在没有用户自定义 SecurityFilterChain/UserDetailsService 时生效
    static class SecurityFilterChainConfiguration {

        @Bean
        @Order(SecurityFilterProperties.BASIC_AUTH_ORDER)
        SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) {
            http.authorizeHttpRequests((requests) -> requests.anyRequest().authenticated());
            http.formLogin(withDefaults());
            http.httpBasic(withDefaults());
            return http.build();
        }
    }
}
```

启动时序（时间线一，详见 8.1 节）：

1. `UserDetailsServiceAutoConfiguration`（同模块）在没有你自己的 `UserDetailsService`/`AuthenticationManager`/`AuthenticationProvider` Bean 时，注册 `InMemoryUserDetailsManager`：`User.withUsername(user.getName()).password(getOrDeducePassword(...))`（第 70-75 行）——这就是控制台打印 "Using generated security password: xxx" 的出处（随机 UUID 密码，`{noop}` 前缀或按提供的 encoder）。
2. `@EnableWebSecurity`（随自动配置导入）触发 Spring Security config 模块的导入链：`WebSecurityConfiguration`、`HttpSecurityConfiguration` 等就位。
3. 用户没配 `SecurityFilterChain` → Boot 的 `defaultSecurityFilterChain` 兜底生效（上文源码）；用户配了 → Boot 完全让位（`@ConditionalOnDefaultWebSecurity` 条件不满足）。
4. `springSecurityFilterChain`（FilterChainProxy）Bean 成型，Boot 的 servlet 注册设施把它包进 `DelegatingFilterProxy`，插到 Servlet 容器过滤器链的最前面（Order 定位在所有业务 Filter 之前）。

这个分层的精妙之处：**Spring Security 不依赖 Boot**（纯 Servlet 应用用 `AbstractSecurityWebApplicationInitializer` 同样工作），Boot 只是"替你写了那 20 行样板配置"。

## 5.7 Lambda DSL 与迁移指南（WebSecurityConfigurerAdapter → SecurityFilterChain）

- **Lambda DSL 的本质**（5.2 引入）：`http.csrf(Customizer.withDefaults())` 的重载把"配置器"作为回调参数交给用户，链式 `and()` 的嵌套迷宫从此消失——每个 `xxx(customizer)` 作用域自动闭合。`Customizer.withDefaults()` 等价于"用出厂默认值"。
- **迁移范式**（5.7 废弃 → 6.0 删除）：

```java
// 旧（<=5.7）：继承 + 重写
// @EnableWebSecurity
// public class SecurityConfig extends WebSecurityConfigurerAdapter {
//     @Override protected void configure(HttpSecurity http) throws Exception { ... }
// }

// 新（6.0+）：声明 Bean
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/public/**").permitAll()
                .anyRequest().authenticated())
            .formLogin(Customizer.withDefaults());
        return http.build();
    }
}
```

概念映射表：`configure(HttpSecurity)` → `SecurityFilterChain` Bean；`web.ignoring()` → `WebSecurityCustomizer` Bean（仍不推荐，改用 `permitAll`）；`configure(AuthenticationManagerBuilder)` → 暴露 `UserDetailsService`/`AuthenticationProvider` Bean 或 `AuthenticationManager` Bean。`with(configurer, customizer)`（6.2+，见 5.2 节源码第 161-167 行）是给自定义 Configurer 留的 DSL 化入口。

## 5.8 本章小结

- 配置体系是"**建造者 + 配置器**"的双层模式：`HttpSecurity`（一条链）与 `WebSecurity`（总闸）两级 build；`doBuild()` 四步状态机 init→configure→performBuild 与容器的 refresh 异曲同工。
- DSL 每一节 = 一个 Configurer = 第三章表里的过滤器/Provider；`addFilter` 的"占座"机制说明过滤器顺序是静态注册表决定的，不可随意插入。
- `springSecurityFilterChain` Bean 的诞生地是 `WebSecurityConfiguration`；用户零配置时它兜底配出"全认证 + 表单 + Basic"——secure by default 的源码现场。
- Boot 的角色是"样板生成器"：默认用户（InMemoryUserDetailsManager + 随机密码）与默认链（authorizeHttpRequests + formLogin + httpBasic）各有一份，且都在你显式配置时完全让位。
- Lambda DSL 是语法糖不是新架构：底层仍是 `AbstractConfiguredSecurityBuilder` 挂 Configurer、`ObjectPostProcessor` 逐对象后处理的 3.x 机器。

---
# 六、OAuth2 / JWT / OIDC（oauth2-* 模块）

## 6.1 模块全景与 ClientRegistration

oauth2 家族四个核心模块（5.0~5.1 成型，`git ls-tree` 实证）+ 一个 7.0 收编的授权服务器：

| 模块 | 角色 | 关键依赖 |
|---|---|---|
| oauth2-core | OAuth2 协议的最小词汇表：`OAuth2AccessToken`、`OAuth2AuthorizationGrantAuthority` 等 | spring-web |
| oauth2-jose | JOSE/JWT 基建：**NimbusJwtDecoder / NimbusJwtEncoder**、JWS/JWE 支持 | **com.nimbusds:nimbus-jose-jwt** |
| oauth2-client | **客户端**：OAuth2 登录（拿别人家的授权登录我的应用）+ 客户端调用第三方 API | com.nimbusds:oauth2-oidc-sdk |
| oauth2-resource-server | **资源服务器**：保护自己的 API（校验 Bearer Token） | — |
| oauth2-authorization-server | **授权服务器**：自己发令牌（Spring Authorization Server 并入，7.0） | oauth2-client/jose/resource-server |

核心抽象 `ClientRegistration`（oauth2-client）：把"第三方服务商"抽象成一个 POJO——`registrationId`、`clientId/clientSecret`、`authorizationGrantType`、`redirectUri`、各端点地址。`ClientRegistrationRepository` 是它的仓库（常用 `InMemoryClientRegistrationRepository`）；CommonOAuth2Provider 内置了 Google/GitHub/Facebook/Okta 的端点默认值。**Spring Security 对 OIDC 的支持即建立在 ClientRegistration 之上**：issuer 自动发现（`.well-known/openid-configuration`）填充缺省端点。

## 6.2 OAuth2 登录（客户端）：一串过滤器的接力

先说白话：OAuth2 登录是"多阶段认证"——第一次请求把浏览器带去授权服务器，用户同意后被重定向回来带着授权码，第二次请求拿码换令牌、加载用户。**没有任何一个过滤器能独自完成**，它是一串过滤器的接力（对照第三章全景表）：

```
浏览器 ──GET /oauth2/authorization/google──▶ ① OAuth2AuthorizationRequestRedirectFilter (#12)
                                              │  构造 OAuth2AuthorizationRequest 存入 AuthorizationRequestRepository，
                                              │  302 重定向到 Google 的授权端点
浏览器 ◀─（用户在 Google 登录并同意）──────────┘
浏览器 ──GET /login/oauth2/code/google?code=...──▶ ② OAuth2LoginAuthenticationFilter (#18)
                                              │  认领回调 URL（源码实证：
                                              │  OAuth2LoginAuthenticationFilter.java:106
                                              │  DEFAULT_FILTER_PROCESSES_URI = "/login/oauth2/code/*"）
                                              │  委托 OAuth2LoginAuthenticationProvider：
                                              │  拿 code 换 access token（HTTP 调 Google token 端点），
                                              │  用 access token 拉 userinfo，映射成 OAuth2User
                                              ▼
                                           ③ AbstractAuthenticationProcessingFilter 的成功四步（见 3.5.3）
                                              → OAuth2User 进 SecurityContext，Session 建立登录态
```

对应配置一行：`http.oauth2Login(Customizer.withDefaults())`（配合 `spring.security.oauth2.client.registration.google.client-id=...`）。OIDC 场景下换令牌走 `OidcAuthorizationCodeAuthenticationProvider`，额外解析 id_token 得到 `OidcUser`。

## 6.3 资源服务器：Bearer Token 的校验链

先说白话：你的 API 是"资源服务器"，客户端拿 Access Token 访问你。资源服务器的工作只有一件：**验证这个 Token 是不是我家授权服务器签的**。链路仍是"过滤器认领 + Provider 决策"的老配方：

【源码证据】`oauth2/oauth2-resource-server/src/main/java/org/springframework/security/oauth2/server/resource/authentication/JwtAuthenticationProvider.java` 第 62-104 行（节选）：

```java
public class JwtAuthenticationProvider implements AuthenticationProvider {

    private final JwtDecoder jwtDecoder;
    private Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter =
        new JwtAuthenticationConverter();

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        BearerTokenAuthenticationToken bearer = (BearerTokenAuthenticationToken) authentication;
        Jwt jwt = getJwt(bearer);                          // ① 解码+验签：签名/JWK/时间窗，失败抛 InvalidBearerTokenException
        AbstractAuthenticationToken token = this.jwtAuthenticationConverter.convert(jwt);  // ② Jwt → 身份
        ...
        return token;                                      // ③ 得到 JwtAuthenticationToken（含 scope authorities）
    }
}
```

- **入口**：`BearerTokenAuthenticationFilter`（#28）用 `BearerTokenAuthenticationConverter` 从 `Authorization: Bearer ...` 头提取令牌，构造 `BearerTokenAuthenticationToken` 交给 `AuthenticationManager`（源码实证：构造器第 102-126 行，支持按请求解析不同 `AuthenticationManagerResolver`——多租户场景每个租户一个验证方）。
- **验签**：`NimbusJwtDecoder`（oauth2-jose）包装 Nimbus 库，三种构造入口对应三种公钥来源——`withIssuerLocation(issuer)`（OIDC 发现，第 230 行）、`withJwkSetUri(jwksUri)`（第 248 行）、`withPublicKey(rsaKey)`（第 257 行）；`decode()`（第 139 行）内部完成签名校验、`exp/nbf` 时间窗校验，失败统一抛 `JwtException` 族。
- **映射**：`JwtAuthenticationConverter` 默认把 `scope`/`scp` claim 映射成 `SCOPE_xxx` authorities；换成 `JwtGrantedAuthoritiesConverter` 自定义 claim 映射是资源服务器最常见的定制点。
- **不透明令牌**：不想验签（或令牌无法本地验证）时，`OpaqueTokenAuthenticationProvider` 改为调授权服务器的 `/introspect` 端点——与 JWT 路径互为替换，过滤器与 DSL 完全一致。

## 6.4 JWT 编码：NimbusJwtEncoder

反过来自己签发 JWT 用 `NimbusJwtEncoder`（oauth2-jose，第 172 行 `public Jwt encode(JwtEncoderParameters parameters)`）：传 `JwsHeader`（算法 + JWK）与 `JwtClaimsSet`，产出签名后的 `Jwt`。它同时支持 JWS（签名）与 JWE（加密）。做授权服务器或"服务间签发短令牌"时用它；绝大多数应用只做资源服务器，只需 Decoder。

## 6.5 oauth2-authorization-server：从独立项目到官方模块

Spring Authorization Server（SAS）在 7.0 并入主仓库（1.6 节 git 实证），提供完整 OAuth2/OIDC 授权服务器：客户端注册管理、授权码/客户端凭证/设备码等授权模式、`OAuth2AuthorizationService`（授权会话存储）、JWK Source 管理。它复用本文的一切基建——过滤器链（`OAuth2AuthorizationEndpointFilter` 等）、`SecurityFilterChain` DSL（`OAuth2AuthorizationServerConfiguration` 提供开箱链）、`NimbusJwtEncoder` 签发——所以读透第二~五章后，SAS 源码是"熟悉感"而非"新知识"。

## 6.6 本章小结

- OAuth2 家族按"角色"拆模块：client（我登别人）、resource-server（我保自己）、authorization-server（我发令牌）、jose（JWT 编解码底座）；`ClientRegistration` 是客户端侧的核心抽象。
- OAuth2 登录没有任何新架构：仍是 `AbstractAuthenticationProcessingFilter` 模板 + 多阶段认证（attemptAuthentication 返回 null 中途挂起）+ Provider 轮询——6.2 的接力图里每个部件都在第二、三章出现过。
- 资源服务器 = `BearerTokenAuthenticationFilter`（提令牌）→ `JwtAuthenticationProvider`（验签）→ `JwtAuthenticationConverter`（映射身份）；JWT 验签的公钥三来源（发现/JWKS/本地密钥）对应三个 Decoder 工厂方法。
- 生产选型提示：微服务间通信优先 JWT（本地验签零网络开销）；无法持有签发密钥或需要即时吊销时用不透明令牌 + introspection。

---

# 七、spring-security-crypto 与其余模块速览

## 7.1 PasswordEncoder 体系：`{id}` 前缀与算法平滑迁移

先说白话：密码必须存哈希，但算法会过时（MD5 → SHA → BCrypt → Argon2）。**数据库里同时存在多种算法的存量密文**是常态。Spring Security 的答案是给密文加"算法标签"：

```text
{id}encodedPassword      —— DelegatingPasswordEncoder 的存储格式（@since 5.0；4.2.6 后向移植）

{bcrypt}$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG
{noop}password           —— 明文（仅测试用）
{pbkdf2}5d923b44a6d1...
{scrypt}$e0801$8bWJaSu2...
{sha256}97cde38028ad...
```

【源码证据】`crypto/src/main/java/org/springframework/security/crypto/password/DelegatingPasswordEncoder.java` 第 128-153、163-212 行：

```java
public class DelegatingPasswordEncoder extends AbstractValidatingPasswordEncoder {

    private final String idForEncode;                                 // 新密码用什么算法编（默认 bcrypt）
    private final PasswordEncoder passwordEncoderForEncode;
    private final Map<String, PasswordEncoder> idToPasswordEncoder;   // {id} → 具体编码器
    private PasswordEncoder defaultPasswordEncoderForMatches = new UnmappedIdPasswordEncoder();
    ...
    // encode(): passwordEncoderForEncode 编码后拼上 "{bcrypt}" 前缀
    // matches(): 解析前缀取 id → 查表 → 委托对应编码器比对；
    //            id 未注册（含无前缀）→ 抛 IllegalArgumentException（或走 defaultPasswordEncoderForMatches）
```

三个实践要点：

1. **`PasswordEncoderFactories.createDelegatingPasswordEncoder()` 是 DaoAuthenticationProvider 的默认值**（2.5.2 节源码①）——你从没显式配过 `PasswordEncoder`，密文也自动带上了 `{bcrypt}` 前缀。
2. **`{noop}` 的妙用**：Boot 的自动生成密码用 `{noop}uuid` 免加密成本（5.6 节源码 `NOOP_PASSWORD_PREFIX`）；从旧系统导入无前缀密文时给密文手动补 `{id}` 前缀即可让 Delegating 认出。
3. **升级路径两条**：一是 2.5.3 节的登录时重编码（`UserDetailsPasswordService` + `upgradeEncoding()`）；二是 `idForEncode` 指向新算法后，存量密文按各自 id 继续可验，自然过渡。这也是"为什么换密码算法不用停机迁移"的完整答案。

crypto 模块还内含：`Argon2PasswordEncoder`（内存困难型，抗 GPU）、`SCryptPasswordEncoder`、`Pbkdf2PasswordEncoder`、`BCryptPasswordEncoder`（事实标准）、`KeyGenerators`（对称密钥生成）、`BytesEncryptor/TextEncryptor`（AES 加解密，`Encryptors.delux()`）——整包零 Spring 容器依赖（1.4 节），任何 Java 项目可直接引 spring-security-crypto 单用。

## 7.2 其余模块速览（一句话 + 源码入口）

| 模块 | 一句话定位 | 值得看的类 |
|---|---|---|
| spring-security-access | 旧授权体系养老院（7.0 迁入）：投票决策 + `@Secured` + `@PreAuthorize` 所需的表达式根 | `access.vote.AffirmativeBased`、`access.expression.SecurityExpressionRoot` |
| spring-security-acl | 数据库行级/对象级 ACL：`Acl`/`Sid`/`Permission` 三元组 + 三张标准表（ACL_ENTRY 等），与 `hasPermission()` 表达式配合 | `acl.AclPermissionEvaluator` |
| spring-security-ldap | LDAP 认证：`LdapAuthenticationProvider`（Bind 模式/密码比对模式）、嵌入式服务器测试支持 | `ldap.authentication.LdapAuthenticationProvider` |
| spring-security-cas | CAS 单点登录客户端：`CasAuthenticationFilter` 认领 ticket 校验 | `cas.web.CasAuthenticationFilter` |
| spring-security-saml2(-service-provider) | SAML 2.0 SP（5.2 起）：元数据、`Saml2WebSsoAuthenticationRequestFilter` 发 SAML 请求、断言校验（OpenSAML） | `saml2.provider.service.web.authentication.Saml2WebSsoAuthenticationFilter` |
| spring-security-messaging | WebSocket/STOMP 安全：`AbstractSecurityWebSocketMessageBrokerConfigurer` 的消息级授权 | `messaging.access.intercept.MessageSecurityMetadataSource` |
| spring-security-rsocket | RSocket 安全（5.2 起）：与 web 平行的 payload 拦截 + JWT 复用 | `rsocket.authorization.PayloadInterceptor` |
| spring-security-data | Spring Data 查询自动注入安全条件：`@Query("select u from User u where u.owner = ?#{ principal?.username }")` 的 SpEL 支持 | `data.repository.QuerySecurityEvaluationContextExtension` |
| spring-security-taglibs | JSP 时代的 `<sec:authorize>` 标签 | — |
| spring-security-test | 测试基建：`@WithMockUser/@WithUserDetails`、`SecurityMockMvcRequestPostProcessors.csrf()`、`mockOAuth2User()` | `test.web.servlet.request.SecurityMockMvcRequestPostProcessors` |
| spring-security-webauthn | Passkey/WebAuthn（6.4 引入功能、7.0 独立模块）：注册/认证仪式，底层依赖 webauthn4j | `web.webauthn.WebAuthnAuthenticationFilter`（web 模块内） |
| spring-security-kerberos | Kerberos/SPNEGO（**7.0 新增**，原社区项目收编）：域环境免密登录 | `kerberos.web.SpnegoAuthenticationProcessingFilter` |
| spring-security-oauth2-authorization-server | 授权服务器全家桶（7.0 收编，见 6.5 节） | — |

## 7.3 本章小结

- `{id}密文` 是 Spring Security 最优雅的设计之一：一行前缀同时解决"多算法共存"与"无停机算法升级"两大工程难题，且默认就已启用。
- crypto 是框架里唯一零依赖的模块，PasswordEncoder/Encryptor/KeyGenerator 可独立于 Spring 使用。
- 其余模块全是"协议适配器"：LDAP/CAS/SAML/WebAuthn/Kerberos 各自提供 `AuthenticationProvider` 或过滤器接入第二、三章的统一内核——**认证方式可以无限扩展，决策框架永远只有一套**。

---

# 八、贯通视图：三条时间线看懂 Spring Security 全貌

## 8.1 时间线一：应用启动（Spring Boot 4.x，含 spring-boot-security）

```
SpringApplication.run
 └─ 容器 refresh()
     ├─ @EnableWebSecurity 导入链生效（config 模块）
     │    ├─ HttpSecurityConfiguration 注册原型 Bean "HttpSecurity"（出厂预装 11 个 Configurer，5.5 节）
     │    └─ WebSecurityConfiguration 注册 WebSecurity + springSecurityFilterChain Bean（5.4 节）
     ├─ Boot 自动配置（无用户自定义时）
     │    ├─ UserDetailsServiceAutoConfiguration → InMemoryUserDetailsManager（随机密码，打印到控制台）
     │    └─ ServletWebSecurityAutoConfiguration → defaultSecurityFilterChain（anyRequest().authenticated()
     │         + formLogin + httpBasic，@Order(BASIC_AUTH_ORDER)）
     ├─ 你的 SecurityFilterChain Bean（若有）→ 替换 Boot 默认链
     └─ springSecurityFilterChain Bean 实例化：
          WebSecurity.build() → 每条 HttpSecurity.build() → doBuild 四步 → performBuild
            → filters 按 FilterOrderRegistration 槽位排序 → DefaultSecurityFilterChain（5.3 节）
          → new FilterChainProxy(chains)（firewall/decorator 注入）→ postProcess
     └─ Servlet 容器阶段：spring-boot-security 把 FilterChainProxy 包进 DelegatingFilterProxy，
        以名字 "springSecurityFilterChain" 注册为容器过滤器，排在所有业务 Filter 之前
```

## 8.2 时间线二：一次 HTTP 请求（已登录用户，GET /api/orders）

```
Tomcat 请求线程
 ├─ DelegatingFilterProxy → FilterChainProxy.doFilter（3.3 节）
 │    ├─ StrictHttpFirewall 包裹请求（畸形 URL → RequestRejectedException → 400）
 │    ├─ getFilters()：逐条 SecurityFilterChain.matches()，首条命中（此处是默认链 /**）
 │    └─ VirtualFilterChain 逐站执行（第三章全景表）：
 │         #700  SecurityContextHolderFilter   → loadDeferredContext 挂 Supplier（Session 查询未发生）
 │         #900  HeaderWriterFilter            → 预写安全响应头
 │         #1100 CsrfFilter                    → GET 请求，免检直行
 │         #1200 LogoutFilter                  → 非 POST /logout，直行
 │         #3300 RequestCacheAwareFilter       → 无缓存请求，直行
 │         #3700 AnonymousAuthenticationFilter → Session 里有身份，跳过造匿名身份
 │         #3900 SessionManagementFilter       → 仓库含上下文，直行
 │         #4000 ExceptionTranslationFilter    → try 开始（默默包住下游）
 │         #4200 AuthorizationFilter           → authorize()：/api/orders 需 ROLE_USER？
 │                                             → 此时才真正触发 Session 反序列化（延迟加载兑现）
 │                                             → granted ✓
 ├─ 进入业务：DispatcherServlet → Controller（/api/orders）
 └─ 响应回传，FilterChainProxy.finally → securityContextHolderStrategy.clearContext()（清卡，防串号）
```

把两个"懒"连起来看：**#700 只挂 Supplier、#4200 才取值**——一个纯 GET 的静态资源请求可能全程不碰 Session；这就是 5.7 引入 SecurityContextHolderFilter、6.x 全面切换的动机。

## 8.3 时间线三：一次表单登录（POST /login）+ 后续免认证

```
第一程：未登录访问 /orders
 ├─ #700 挂 Supplier（Session 空 → 匿名身份）→ #3700 发放 AnonymousAuthenticationToken
 ├─ #4200 AuthorizationFilter：/orders 需认证 → 匿名不满足 → 抛 AuthorizationDeniedException
 └─ #4000 ExceptionTranslationFilter 接住：
      trustResolver.isAnonymous(anonymous) == true
      → 折算成 InsufficientAuthenticationException（3.5.4 节源码）
      → requestCache.saveRequest(/orders)     ★ 记住你要去哪
      → entryPoint.commence() → 302 /login    ★ 带你去登录页

第二程：POST /login（username/password）
 ├─ #1100 CsrfFilter：表单带 _csrf 令牌 → Xor 解码比对通过
 ├─ #2100 UsernamePasswordAuthenticationFilter 认领（PathPattern POST /login）
 │    ├─ attemptAuthentication：取参 → unauthenticated(principal, credentials)
 │    ├─ ProviderManager 轮询 → DaoAuthenticationProvider
 │    │    ├─ retrieveUser：UserDetailsService.loadUserByUsername（含时序攻击防护，2.5.2 节）
 │    │    ├─ 前置检查（锁定/禁用/过期）→ additionalAuthenticationChecks（PasswordEncoder.matches）
 │    │    └─ createSuccessAuthentication：泄露检测 → 密码升级检查 → authenticated 票据（含密码因子）
 │    ├─ 成功：eraseCredentials（擦密码）→ AuthenticationSuccessEvent
 │    ├─ sessionStrategy.onAuthentication（ChangeSessionId 防会话固定）
 │    └─ successfulAuthentication 四步：setContext → saveContext(Session) → rememberMe → 跳转
 └─ SavedRequestAwareAuthenticationSuccessHandler：读 RequestCache → 302 /orders  ★ 回到你最初要去的地方

第三程：GET /orders（带着新 Session Cookie）
 └─ #700 loadDeferredContext 从 Session 取出身份 → #4200 放行 → 业务执行（时间线二第 4200 站之后的路）
```

## 8.4 从源码中提炼的六个设计模式视角

1. **责任链**：`VirtualFilterChain.doFilter`（HTTP 维度）与 Spring AOP 的 `ReflectiveMethodInvocation#proceed()`（调用维度）同构；过滤器"可放行可拦截"的组合成就了整条安检流水线。
2. **模板方法**：`AbstractConfiguredSecurityBuilder#doBuild`（init→configure→performBuild）、`AbstractAuthenticationProcessingFilter#doFilter`（attemptAuthentication 一个抽象钩子）、`AbstractUserDetailsAuthenticationProvider#authenticate`（retrieveUser/additionalAuthenticationChecks 两个钩子）。
3. **策略 + 轮询调度**：`ProviderManager` 对 `AuthenticationProvider`、`DelegatingPasswordEncoder` 对 `PasswordEncoder`、`RequestMatcherDelegatingAuthorizationManager` 对 `AuthorizationManager`——同一个配方反复出现：**接口定义能力，Manager/Registry 负责选择，用户换实现零侵入**。
4. **建造者**：`HttpSecurity`/`WebSecurity` 两级构建；与 Spring 容器的"BeanDefinition 先行、实例化在后"同构——Configurer 挂起配方，`build()` 一次性产出。
5. **委托/装饰**：`DelegatingFilterProxy`（容器→Bean）、`DelegatingSecurityContextRepository`（多仓库）、`FirewalledRequest`（包裹 + reset）、`DelegatingSecurityContextRunnable`（身份跨线程搬运）——凡是"两个世界要对接"，Spring Security 就造一个 Delegator，与 Spring Framework 的 FactoryBean/DisposableBeanAdapter 如出一辙。
6. **门面（静态）**：`SecurityContextHolder`——全部静态方法 + 可换策略，用最小的调用成本让"当前用户"成为全局事实。

---

# 九、附录

## 9.1 关键接口速查表

| 接口 / 类 | 模块 | 一句话 |
|---|---|---|
| SecurityContextHolder / SecurityContextHolderStrategy | core | 静态门面 + 可换存储策略（默认 ThreadLocal） |
| SecurityContext / SecurityContextRepository | core/web | 身份卡本体 / 身份卡的存取仓库（Session/RequestAttribute/委托） |
| Authentication | core | 一票两用：认证请求（unauthenticated）与认证结果（authenticated） |
| GrantedAuthority | core | 权限最小单元（"ROLE_ADMIN" 字符串） |
| UserDetails / UserDetailsService | core | 框架视角的用户 / 按用户名加载用户的一个方法 |
| AuthenticationManager / ProviderManager | core | authenticate 单方法接口 / 多 Provider 轮询实现 |
| AuthenticationProvider（DaoAuthenticationProvider 等） | core | 单一认证方式的策略实现 |
| PasswordEncoder（DelegatingPasswordEncoder） | crypto | 密码编码与比对 / `{id}` 前缀多算法委托 |
| FilterChainProxy / SecurityFilterChain | web | 安全总闸（防火墙+选链+清卡）/ 一条命名的虚拟链 |
| DelegatingFilterProxy | spring-web | Servlet 容器 → Spring Bean 的过滤器桥 |
| RequestMatcher（PathPatternRequestMatcher） | web | URL 匹配策略族（7.0 默认 PathPattern） |
| HttpFirewall / StrictHttpFirewall | web | 请求防火墙：拒绝畸形 URL，规范化路径 |
| CsrfFilter / CsrfTokenRepository | web | CSRF 令牌校验 / 令牌存取（Session/Cookie） |
| AbstractAuthenticationProcessingFilter | web | 认证过滤器模板（认领 URL→认证→成功/失败处置） |
| AuthenticationSuccessHandler / AuthenticationFailureHandler | web | 登录成功/失败后的跳转策略 |
| AuthenticationEntryPoint / AccessDeniedHandler | web | "去登录"动作 / "403"动作 |
| ExceptionTranslationFilter | web | 安全异常 → HTTP 动作的翻译官 |
| AuthorizationFilter | web | 链尾 URL 授权拦截器（5.5+） |
| AuthorizationManager / AuthorizationResult | core | 授权函数式接口 / 裁决结果（6.4 显式化） |
| RequestMatcherDelegatingAuthorizationManager | web | "URL 模式 → 决策器"路由表（默认拒绝） |
| SecurityExpressionRoot | access | 安全 SpEL 的根对象（hasRole/permitAll…） |
| SecurityBuilder / SecurityConfigurer | config | 建造者 / 配置器（init→configure 两段） |
| HttpSecurity / WebSecurity | config | 一条 SecurityFilterChain 的建造者 / FilterChainProxy 的建造者 |
| FilterOrderRegistration | config | 43 个过滤器槽位的静态注册表 |
| EnableWebSecurity / EnableMethodSecurity | config | Web 安全开关 / 方法安全开关（prePostEnabled 默认 true） |
| JwtDecoder / JwtEncoder | oauth2-jose | JWT 验签解码 / 签名编码（Nimbus 包装） |
| ClientRegistration / ClientRegistrationRepository | oauth2-client | 第三方 IdP 描述 / 其仓库 |
| OAuth2User / OidcUser | oauth2-core/client | OAuth2/OIDC 登录后的用户主体 |
| AuthorizationFilter 的 eventPublisher（AuthorizationEventPublisher） | core/web | 授权事件（Granted/Denied）发布策略 |

## 9.2 初学者学习路线（动手向）

1. **用起来（第 1 天）**：Spring Boot + `spring-boot-starter-security`，观察控制台随机密码、自动登录页、`/logout` 确认页；把默认表单换成自定义登录页（`formLogin(login -> login.loginPage("/login"))`），理解"401→302→登录→回跳"闭环（对应 8.3 节三程）。
2. **读懂默认链（第 2~3 天）**：断点 `FilterChainProxy#doFilter` 与 `VirtualFilterChain#doFilter`，数一遍请求经过了几个过滤器；断点 `SecurityContextHolderFilter#doFilter` 第 81 行，观察 `setDeferredContext` 后业务代码第一次 `SecurityContextHolder.getContext()` 时才发生 Session 读取。
3. **走通一次认证（第 4~5 天）**：断点 `UsernamePasswordAuthenticationFilter#attemptAuthentication` → `ProviderManager#authenticate` → `DaoAuthenticationProvider#retrieveUser`，把 2.5 节源码走一遍；故意输错密码，观察 `BadCredentialsException` 的收敛路径与 `AuthenticationFailureBadCredentialsEvent`。
4. **搞懂授权（第 2 周）**：写 `authorizeHttpRequests` 三条规则（permitAll/hasRole/anyRequest），断点 `RequestMatcherDelegatingAuthorizationManager#authorize`（观察"首条命中"与默认 DENY）；再写一个 `@PreAuthorize("hasRole('ADMIN')")`，断点 `AuthorizationManagerBeforeMethodInterceptor#invoke`，确认方法安全走的是 AOP 代理。
5. **玩转密码体系（第 2 周）**：用 `PasswordEncoderFactories.createDelegatingPasswordEncoder()` 手动 encode 一批 `{bcrypt}/{pbkdf2}/{noop}` 密文存入内存仓库，验证都能登录；实现 `UserDetailsPasswordService`，把一条 `{sha256}` 密文升级为登录后自动变 `{bcrypt}`。
6. **接一次 OAuth2（第 3 周）**：`oauth2Login()` + GitHub 注册一个应用，断点 `OAuth2AuthorizationRequestRedirectFilter` 与 `OAuth2LoginAuthenticationFilter`，完整看一遍 6.2 节的接力；再给同一个应用加 `oauth2ResourceServer().jwt()`，用 curl 带 JWT 调 API。
7. **进阶**：读 webauthn 模块（Passkey 注册/认证仪式）、oauth2-authorization-server（授权服务器视角反哺理解客户端/资源服务器）；用 `SecurityContextPropagation` 玩跨线程身份传递。

## 9.3 源码阅读入口清单（30 个关键文件）

按"必读度"排序，路径相对 `D:\code\3rd\spring-security`：

1. web/src/main/java/org/springframework/security/web/FilterChainProxy.java（总闸：防火墙+选链+VirtualFilterChain+清卡）
2. core/src/main/java/org/springframework/security/core/context/SecurityContextHolder.java（身份卡门面与三策略）
3. config/src/main/java/org/springframework/security/config/annotation/web/builders/FilterOrderRegistration.java（43 槽位全景表）
4. web/src/main/java/org/springframework/security/web/context/SecurityContextHolderFilter.java（延迟加载上下文，6.x 语义核心）
5. web/src/main/java/org/springframework/security/web/authentication/AbstractAuthenticationProcessingFilter.java（认证过滤器模板）
6. core/src/main/java/org/springframework/security/authentication/ProviderManager.java（认证轮询调度）
7. core/src/main/java/org/springframework/security/authentication/dao/AbstractUserDetailsAuthenticationProvider.java（认证模板方法骨架）
8. core/src/main/java/org/springframework/security/authentication/dao/DaoAuthenticationProvider.java（时序攻击防护+密码升级）
9. web/src/main/java/org/springframework/security/web/access/ExceptionTranslationFilter.java（异常翻译官）
10. web/src/main/java/org/springframework/security/web/access/intercept/AuthorizationFilter.java（链尾授权）
11. web/src/main/java/org/springframework/security/web/access/intercept/RequestMatcherDelegatingAuthorizationManager.java（URL 授权路由表）
12. core/src/main/java/org/springframework/security/authorization/AuthorizationManager.java（授权函数式接口）
13. config/src/main/java/org/springframework/security/config/annotation/AbstractConfiguredSecurityBuilder.java（doBuild 四步状态机）
14. config/src/main/java/org/springframework/security/config/annotation/web/builders/HttpSecurity.java（performBuild/addFilter/DSL 全集）
15. config/src/main/java/org/springframework/security/config/annotation/web/builders/WebSecurity.java（FilterChainProxy 组装现场）
16. config/src/main/java/org/springframework/security/config/annotation/web/configuration/WebSecurityConfiguration.java（springSecurityFilterChain Bean 与兜底默认链）
17. config/src/main/java/org/springframework/security/config/annotation/web/configuration/HttpSecurityConfiguration.java（出厂预装 11 Configurer）
18. web/src/main/java/org/springframework/security/web/authentication/UsernamePasswordAuthenticationFilter.java（表单登录子类）
19. web/src/main/java/org/springframework/security/web/csrf/CsrfFilter.java（Xor BREACH 防护+常数时间比较）
20. crypto/src/main/java/org/springframework/security/crypto/password/DelegatingPasswordEncoder.java（{id} 前缀体系）
21. web/src/main/java/org/springframework/security/web/context/HttpSessionSecurityContextRepository.java（Session 仓与 SPRING_SECURITY_CONTEXT）
22. web/src/main/java/org/springframework/security/web/session/SessionManagementFilter.java（会话固定防护/超时策略）
23. web/src/main/java/org/springframework/security/web/firewall/StrictHttpFirewall.java（防火墙拒绝清单）
24. web/src/main/java/org/springframework/security/web/authentication/AnonymousAuthenticationFilter.java（匿名身份约定）
25. web/src/main/java/org/springframework/security/web/authentication/logout/LogoutFilter.java（登出 Handler 链）
26. core/src/main/java/org/springframework/security/authentication/event/…（认证事件族）与 core/.../authentication/DefaultAuthenticationEventPublisher.java
27. core/src/main/java/org/springframework/security/access/expression/SecurityExpressionRoot.java（安全 SpEL 根）
28. core/src/main/java/org/springframework/security/authorization/method/AuthorizationManagerBeforeMethodInterceptor.java（方法安全 AOP）
29. oauth2/oauth2-resource-server/src/main/java/org/springframework/security/oauth2/server/resource/authentication/JwtAuthenticationProvider.java（JWT 校验 Provider）
30. oauth2/oauth2-jose/src/main/java/org/springframework/security/oauth2/jwt/NimbusJwtDecoder.java（decode 与三种公钥工厂）

## 结语

回到开篇的问题：Spring Security 解决了什么？

- 它用一条 **DelegatingFilterProxy → FilterChainProxy → SecurityFilterChain** 的过滤器链回答了"安全检查如何统一挂载、如何按请求分发、如何在结束时自我清理"；
- 用 **SecurityContextHolder + SecurityContextRepository** 回答了"身份如何表达、传递、持久化"——并且用延迟加载把这个成本压到了最低；
- 用 **ProviderManager → AuthenticationProvider** 的轮询调度回答了"认证方式如何无限扩展而内核保持稳定"；
- 用 **AuthorizationManager + 默认拒绝 + 表达式语言**回答了"授权规则如何声明、如何组合、如何兜底"；
- 用 **SecurityBuilder/SecurityConfigurer** 回答了"几十个安全设施如何被声明式地装配成一条链"，并让 Spring Boot 的开箱即用只是"多写了一份默认配置"而已。

这些答案没有一个是复杂的——复杂的是它们环环相扣的纪律：**默认安全（secure by default）、显式保存、请求结束清卡、错误信息收敛、默认拒绝**。理解了本文的证据链，再去看 OAuth2 登录、SAML、Passkey，看到的都会是同一台机器换了认领 URL 的过滤器；再去看 Spring Authorization Server，看到的是同一套 DSL 从"保护 API"延伸到了"发放令牌"。

安全框架最难的不是防住攻击，而是**让正确的事情成为默认**——这正是从 Acegi 时代一路写到 7.2.0-SNAPSHOT 的这二十多年里，Spring Security 代码库反复证实的工程哲学。
