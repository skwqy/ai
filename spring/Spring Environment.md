# Spring Environment 深度源码解析（配置优先级、机制与 Nacos 接入）

> **本文基于的源码**（2026-10-24 经 raw 拉取、按文件实际行号核对）：
>
> | 仓库 | 快照 | 下文路径前缀 |
> |---|---|---|
> | `D:\code\3rd\spring-framework` | **7.1.0-SNAPSHOT**，commit `b671d1a36a42`（2026-10-23） | `fw:` |
> | `D:\code\3rd\spring-boot` | **4.2.0-SNAPSHOT**，commit `11778bdc24f7`（2026-10-23），`springFrameworkVersion=7.1.0-RC1` | `boot:` |
> | `D:\code\3rd\spring-cloud-alibaba` `2025.1.x` | pom revision **2025.1.0.1-SNAPSHOT**，Spring Cloud **2025.1.2**，Spring Boot **4.1.0**，nacos-client **3.1.1** | `sca2025:` |
> | 同仓库 `2023.x` | commit `4ccb7b10278f`（2026-03-12），revision **2023.0.3.5-SNAPSHOT**，Spring Cloud **2023.0.3**，Spring Boot **3.2.9**，nacos-client **2.4.3** | `sca2023:` |
>
> **版本取舍**：优先级的骨架自 Spring 3.1（`Environment`）和 Boot 2.4（`ConfigData`）以后没有换过。Boot 4.2 把一部分 `EnvironmentPostProcessor` 从 `org.springframework.boot.env` 挪到了 `org.springframework.boot.support`，类职责没变。Nacos 接入以 **2025.1.x 的 Config Data 路线**为主证据；2023.x 只在行为分叉处对照。`NacosPropertySourceLocator`（bootstrap 时代的拉配置类）在这两条分支的 `client` 包里已经不存在。
>
> **阅读约定**：每章先用一两句人话讲清"这是什么、为什么这样排"，再给源码证据（路径 + 方法 + 行号 + 片段）。行号只对上表快照精确。配套阅读：《Spring Boot.md》第四章（ConfigData 与 Binder）、《Spring Cloud Alibaba Nacos.md》第五章/第六章（服务端存储与刷新协议）。本文不重复 Binder 和 Nacos 服务端，只回答"同一个 key 谁说了算，以及 Nacos 是怎么插进这张表的"。

## 如何读这份文档

- **先看一份能对上文件的例子**：第九章。里面有启动命令、每一份文件的正文、`commandLineArgs` / `systemProperties` / `systemEnvironment` / `defaultProperties` / Nacos dataId 的先后，以及每个键最后取到的值。
- **只想查表（15 分钟）**：读 1.5 的优先级总表、3.2 的 Boot 全序、第九章的总表、第六章的实践清单。
- **要搞懂机制（1~2 小时）**：第二章（货架）→ 第三章（Boot 何时往货架上摆）→ 第四章（文件之间怎么比）→ 第七章（Nacos 挂在树上的哪一枝）。
- **排障**：直接看第八章的查找路径，对照 `/actuator/env` 里 PropertySource 的名字和顺序。第九章的名字和 `/actuator/env` 对得上。

---

# 一、总览：配置优先级是一条有序列表，不是一套 if-else

## 1.1 一句话定位

**Spring 的配置优先级 = `MutablePropertySources` 里 `PropertySource` 的排列顺序。** `Environment.getProperty(key)` 从列表头走到尾，**第一个值不是 `null` 的来源获胜**。后面所有"命令行压过环境变量、环境变量压过 `application.yml`、profile 文件压过默认文件、后写的 `spring.config.import` 压过先写的"，都是在启动的不同时刻对这条列表做 `addFirst` / `addBefore` / `addAfter` / `addLast`。

Nacos 没有自己的优先级算法。它实现 Boot 的 `ConfigDataLocationResolver` + `ConfigDataLoader`，把远端配置变成普通的 `PropertySource`，再由 Boot 按 import 树插进同一条列表。`spring.cloud.nacos.config.preference=local|remote` 只决定这棵树把 Nacos 节点算成"普通导入"还是"profile 级导入"。

## 1.2 设计哲学：读源码前先记住五句话

1. **顺序即优先级，查找即短路。** 没有"合并策略对象"。同名 key 不会做深合并；先命中的字符串整体获胜，没被命中的 key 才落到下一个来源。
2. **`null` 等于没这个 key。** 解析器看到 `null` 会继续往下找。用 JSON 或某个来源把值设成 `null`，盖不住更低优先级里已有的值。
3. **谁先就位，谁用相对位置锚住自己。** 命令行 `addFirst`，默认值 `moveToEnd`，随机源 `addAfter(systemEnvironment)`，配置文件一律 `addLast`。后执行的处理器靠锚点而不是靠"我第几个被调用"来卡位。
4. **配置文件加载发生在 `refresh()` 之前。** 所以 `logging.*`、`spring.main.*` 能在容器刷新前被读到。`@PropertySource` 发生在 `refresh()` 内部解析配置类时，赶不上这两类早期属性。
5. **导入的文档压过写下 import 的那份文档，profile 文档压过非 profile 文档。** 这是 ConfigData 贡献树的遍历顺序决定的，不是文件名字母序。

## 1.3 三层分工

```
┌─ 使用方 ─────────────────────────────────────────────────────────┐
│  @Value / Environment / @ConfigurationProperties / 日志系统      │
│  都只问 PropertyResolver："这个 key 的值是什么"                   │
├─ Boot 编排层（refresh 之前）─────────────────────────────────────┤
│  SpringApplication.configurePropertySources                      │
│    命令行 addFirst，defaultProperties 先放下再 moveToEnd          │
│  EnvironmentPostProcessor                                        │
│    Random / JSON / 换掉 systemEnvironment 的 Origin 包装         │
│    ConfigDataEnvironmentPostProcessor（ORDER = HIGHEST+10）      │
│      搜索 application*.{properties,yml,yaml,xml}                 │
│      展开 spring.config.import（含 nacos:、configtree:）         │
├─ Framework 地基（spring-core 的 env 包）─────────────────────────┤
│  MutablePropertySources   有序列表，CopyOnWriteArrayList         │
│  PropertySourcesPropertyResolver  从头扫，非 null 即返回         │
│  StandardEnvironment      systemProperties 在 systemEnvironment 前 │
│  SystemEnvironmentPropertySource  foo.bar ↔ FOO_BAR              │
│  StandardServletEnvironment  servletConfig > servletContext > jndi │
└──────────────────────────────────────────────────────────────────┘
```

Nacos 只出现在中间那层的 import 扩展点上：`spring.factories` 登记 `NacosConfigDataLocationResolver` 和 `NacosConfigDataLoader`。它不改 `getProperty`。

## 1.4 一次启动里，列表是怎么长出来的

```
StandardEnvironment 构造
  addLast systemProperties
  addLast systemEnvironment
        │
SpringApplication.configurePropertySources
  defaultProperties 先按名字放入（稍后会被搬到末尾）
  addFirst commandLineArgs          ← 此时列表头
        │
EnvironmentPostProcessor（order 小的先跑）
  RandomValuePropertySource.addAfter(systemEnvironment)
  JSON addBefore(systemProperties)  ← 插到命令行和系统属性之间
  ConfigDataEnvironment.processAndApply
        按贡献树遍历，每个文件 addLast
        然后 DefaultPropertiesPropertySource.moveToEnd
        │
refresh() 里 ConfigurationClassParser
  @PropertySource → PropertySourceProcessor.addLast / addBefore(上一个注解源)
```

最终查找从 `commandLineArgs` 开始。配置文件和 `@PropertySource` 都在系统环境变量后面。

## 1.5 关键问题 → 结论（全文导览）

下表"高 → 低"是 `getProperty` 的命中顺序，与 Boot 4.2 参考文档 `external-config.adoc` 第 13–42 行一致（文档按"后列出的覆盖先列出的"书写，这里翻成查找顺序）。

| 高 → 低 | 来源 | 插进列表的动作 | 详见 |
|---|---|---|---|
| 1 | Devtools 全局设置（devtools 在 classpath 上时） | devtools 自己的 EnvironmentPostProcessor | 官方文档第 32 行 |
| 2 | 测试：`@TestPropertySource`、`@DynamicPropertySource`、`@SpringBootTest(properties=)` | 测试模块 | 官方文档第 28–31 行 |
| 3 | 命令行 `--key=value` | `addFirst`，名字 `commandLineArgs` | 3.1 |
| 4 | `SPRING_APPLICATION_JSON` / `spring.application.json` | `addBefore(systemProperties)` 或 servlet 源 | 3.2 |
| 5 | `ServletConfig` init 参数 | `StandardServletEnvironment` 最先 `addLast` | 2.5 |
| 6 | `ServletContext` init 参数 | 紧随其后 | 2.5 |
| 7 | JNDI `java:comp/env` | 再后 | 2.5 |
| 8 | JVM 系统属性 `-D` | 名字 `systemProperties` | 2.5 |
| 9 | OS 环境变量 | 名字 `systemEnvironment`，名字可写成 `FOO_BAR` | 2.6 |
| 10 | `random.*` | `addAfter(systemEnvironment)` | 3.2 |
| 11 | 配置数据：jar 外 `application-{profile}` → jar 内 `application-{profile}` → jar 外 `application` → jar 内 `application`；同位置 `.properties` 高于 YAML；`---` 后面的文档高于前面的；`spring.config.import` 的结果高于写下 import 的那份文件，且列表里靠后的 import 更高 | ConfigData `addLast` | 第三、四章 |
| 12 | `@PropertySource` | `refresh` 期间 `addLast` | 第五章 |
| 13 | `SpringApplication.setDefaultProperties` | `moveToEnd` | 3.1 |

同一格子内部还有次序，第四章和第七章展开。Nacos 的 dataId **落在第 11 格内部**，具体高于还是低于 `application-{profile}` 由 `preference` 决定（7.4）。它到不了第 3–9 格：命令行、JSON、Servlet、`-D`、环境变量始终压过 Nacos。

## 1.6 版本演进里和优先级有关的几步

| 时间点 | 变化 | 对优先级的影响 |
|---|---|---|
| Framework 3.1 | `Environment` / `PropertySource` | 顺序即优先级，一直用到 7.1 |
| Boot 1.x–2.3 | `ConfigFileApplicationListener` 负责 `application.*` | 2.4 起删除，改为 ConfigData |
| Boot 2.4 | `ConfigDataEnvironment` + `spring.config.import` | 配置中心从 bootstrap 上下文迁到 import |
| Boot 2.4.5 | `ConfigData.Option.PROFILE_SPECIFIC` | Nacos 的 `preference=remote` 用的就是这个标记 |
| SCA 2021.0.1.0 | `NacosConfigDataLoader` | `nacos:` 前缀进入 Config Data |
| SCA 2021.0.4.0 | `ConfigPreference` | 明确 local / remote 谁压过 profile 文件 |
| Boot 4.0 / 本文 4.2 | `EnvironmentPostProcessor` 接口挪到 `org.springframework.boot`，实现类多在 `boot.support` | `spring.factories` 的 key 变成 `org.springframework.boot.EnvironmentPostProcessor` |
| SCA 2023.x / 2025.1.x 当前 HEAD | `client/NacosPropertySourceLocator.java` 已不在仓库 | 接入方式是 `spring.config.import=nacos:`。引入 `spring-cloud-starter-bootstrap` 会在启动时打出警告（`NacosConfigSpringCloudAutoConfiguration` 第 57–64 行，issue 4259） |

## 1.7 章节地图

| 章 | 回答的问题 |
|---|---|
| 二 | 列表、查找、系统属性/环境变量/Servlet/命令行/占位符/Profile 的 Framework 语义 |
| 三 | Boot 在 `run()` 的哪一步把上述来源放上去，全序如何被收尾逻辑钉死 |
| 四 | 多个配置文件之间：目录、profile、扩展名、多文档、import |
| 五 | `@PropertySource` 为什么又低又晚 |
| 六 | 业界怎么分层放配置 |
| 七 | Nacos 的注册、拉取、`preference`、多 dataId 示例、刷新 |
| 八 | 一个 key 从 `getProperty` 走到命中的路径 |
| 九 | 订单服务的完整文件树、每一档的键、每个键的最终值 |

---

# 二、Framework：货架与查找

## 2.1 模块定位

`spring-core` 的 `org.springframework.core.env` 不认识 `application.yml`，也不认识 Nacos。它只提供三样东西：一个有序容器、一个"从头找到第一个非 null"的解析器、两个默认来源（系统属性、环境变量）。Web 环境在 `spring-web` 里再往前插 Servlet/JNDI。业务文件全部是后来者调用 `addFirst`/`addLast` 放进来的。

## 2.2 类型关系

```
PropertyResolver
  └ Environment                    加上 Profile
       └ ConfigurableEnvironment   加上可修改的 PropertySources
            └ AbstractEnvironment
                 ├ StandardEnvironment          systemProperties > systemEnvironment
                 └ StandardServletEnvironment   servletConfig > servletContext > jndi > 上面两个

PropertySource<T>                  有名字的一份键值
  ├ MapPropertySource / PropertiesPropertySource
  ├ SystemEnvironmentPropertySource
  ├ EnumerablePropertySource
  └ CompositePropertySource        同名源叠在一起，内部仍是先命中先返回

MutablePropertySources             List<PropertySource>，下标 0 优先级最高
PropertySourcesPropertyResolver    按 List 迭代
```

`Environment` 自己就是 `PropertyResolver`（`fw:spring-core/.../env/Environment.java` 第 72 行附近的接口声明：`interface Environment extends PropertyResolver`）。所以 `@Value`、占位符、`getProperty` 走的是同一条查找。

## 2.3 列表：下标 0 最高

【源码证据】`fw:spring-core/src/main/java/org/springframework/core/env/MutablePropertySources.java`

存储就是一条 `CopyOnWriteArrayList`（第 43 行）。类注释写明 `addFirst`/`addLast` 的 precedence 指的就是 `PropertyResolver` 的搜索顺序（第 32–34 行）。

```java
public void addFirst(PropertySource<?> propertySource) {          // 103
    synchronized (this.propertySourceList) {
        removeIfPresent(propertySource);
        this.propertySourceList.add(0, propertySource);           // 下标 0
    }
}
public void addLast(PropertySource<?> propertySource) {           // 113
    synchronized (this.propertySourceList) {
        removeIfPresent(propertySource);
        this.propertySourceList.add(propertySource);              // 追加到尾
    }
}
```

`addBefore`（124–130 行）插到某个名字的前面，`addAfter`（137–143 行）插到后面。同名源再次添加会先删掉旧的（`removeIfPresent`），所以重复 `addFirst` 不会留下两份。

这就是"覆盖"的全部机制：**没有覆盖函数，只有更靠前的位置。**

## 2.4 查找：第一个非 null 返回，null 继续往下走

【源码证据】`fw:spring-core/src/main/java/org/springframework/core/env/PropertySourcesPropertyResolver.java` 第 73–94 行

```java
protected <T> @Nullable T getProperty(String key, Class<T> targetValueType, boolean resolveNestedPlaceholders) {
    if (this.propertySources != null) {
        for (PropertySource<?> propertySource : this.propertySources) {
            Object value = propertySource.getProperty(key);
            if (value != null) {                              // 81：null 不当成命中
                if (resolveNestedPlaceholders && value instanceof String string) {
                    value = resolveNestedPlaceholders(string);
                }
                return convertValueIfNecessary(value, targetValueType);
            }
        }
    }
    return null;
}
```

三条直接后果：

1. 高优先级来源里**没有这个 key**，查找才会落到 `application.yml`。有这个 key，后面的文件整个被跳过，不会做 Map 深合并。`server.tomcat.threads.max` 在环境变量里如果只写了一个不相关的 `SERVER_PORT`，不会影响 yml 里的 tomcat 线程配置；但如果环境变量给出了 `SERVER_TOMCAT_THREADS_MAX`，yml 里的同名项就不会被读到。
2. 值为 `null` 时循环继续。Boot 文档 `external-config.adoc` 第 106–107 行把这一点说死了：JSON 里的 `null` 盖不住更低优先级的值。源码上的原因就是第 81 行。
3. 命中的字符串会立刻做 `${...}` 嵌套展开，展开时再次调用 `getProperty`，所以占位符引用的是**整条列表上的获胜值**，不是"写这个占位符的那个文件里的值"。

`CompositePropertySource` 内部同样是按加入顺序短路（`CompositePropertySource.java` 第 63–70 行）。`@PropertySource` 把同名源收成 composite 时，后处理的那个用 `addFirstPropertySource` 放到 composite 头部（第 115–119 行），所以**后解析到的同名 `@PropertySource` 更高**。

## 2.5 标准环境与 Servlet 环境自带的顺序

【源码证据】`fw:spring-core/.../env/StandardEnvironment.java` 第 25–37 行类注释，以及 `customizePropertySources` 第 96–101 行

```java
protected void customizePropertySources(MutablePropertySources propertySources) {
    propertySources.addLast(
            new PropertiesPropertySource(SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, getSystemProperties()));
    propertySources.addLast(
            new SystemEnvironmentPropertySource(SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, getSystemEnvironment()));
}
```

两个名字是常量：`systemEnvironment`、`systemProperties`（第 58、61 行）。类注释说明设计理由：系统属性是单个 JVM 的，环境变量常常是一台机器上很多 JVM 共用的，所以 **`-D` 压过环境变量**，方便按进程覆盖。

Web 环境在此之前再放三个源。【源码证据】`fw:spring-web/.../support/StandardServletEnvironment.java` 第 109–116 行：

```java
protected void customizePropertySources(MutablePropertySources propertySources) {
    propertySources.addLast(new StubPropertySource(SERVLET_CONFIG_PROPERTY_SOURCE_NAME));
    propertySources.addLast(new StubPropertySource(SERVLET_CONTEXT_PROPERTY_SOURCE_NAME));
    if (JNDI_PRESENT && JndiLocatorDelegate.isDefaultJndiEnvironmentAvailable()) {
        propertySources.addLast(new JndiPropertySource(JNDI_PROPERTY_SOURCE_NAME));
    }
    super.customizePropertySources(propertySources);   // 系统属性、环境变量更靠后
}
```

因此 Servlet 经典容器里的顺序是：`servletConfigInitParams` > `servletContextInitParams` > `jndiProperties` > `systemProperties` > `systemEnvironment`。Servlet 相关源先放的是 `StubPropertySource`，等 `ServletContext` 就绪后由 `initPropertySources`（第 119–121 行）替换成真实实现。内嵌 Tomcat 的 Boot 应用通常走 `ApplicationEnvironment`（`SpringApplication.deduceEnvironmentClass`，`SpringApplication.java` 第 358–365 行），不会自动出现 JNDI 源。

## 2.6 环境变量的名字变体（这还不是 Boot 的宽松绑定）

Shell 不允许变量名里出现 `.`。Framework 在**读取环境变量这一份 PropertySource 时**做了等价名尝试。

【源码证据】`fw:spring-core/.../env/SystemEnvironmentPropertySource.java` 第 33–41 行注释，`getProperty` 第 93–99 行，`checkPropertyName` 第 123–145 行。

`getProperty("foo.bar")` 会依次试：

- `foo.bar`
- `foo_bar`（点换成下划线）
- `foo-bar` 的连字符换成下划线
- 点、连字符都换成下划线
- 以及上述形式的全大写（`resolvePropertyName` 第 113–118 行先转大写再走一遍）

所以 `SPRING_PROFILES_ACTIVE=prod` 能被读成 `spring.profiles.active`。常量注释写在 `AbstractEnvironment.java` 第 71–74 行。

这只发生在 `systemEnvironment` 这一份源上。`application.yml` 里写 `SPRING_PROFILES_ACTIVE` 不会自动等价于 `spring.profiles.active`。Boot 的 `@ConfigurationProperties` 宽松绑定是另一层（绑定时把 `foo-bar` 和 `fooBar` 看成同一个属性），见《Spring Boot.md》4.3.3，本文不展开。

## 2.7 命令行只认 `--key=value`

【源码证据】`fw:spring-core/.../env/CommandLinePropertySource.java` 第 209 行：名字常量 `commandLineArgs`。`SimpleCommandLinePropertySource.java` 第 38–40 行：选项参数的语法是 `--optName[=optValue]`。

不以 `--` 开头的参数收进 `nonOptionArgs`（第 212 行），**不会**变成普通配置 key。`-Dkey=value` 是 JVM 系统属性，走 `systemProperties`，比 `--key=value` 低一档。

Boot 把这份源放到列表头：

【源码证据】`boot:core/spring-boot/.../SpringApplication.java` `configurePropertySources` 第 501–520 行

```java
if (this.addCommandLineProperties && args.length > 0) {
    String name = CommandLinePropertySource.COMMAND_LINE_PROPERTY_SOURCE_NAME;
    ...
    sources.addFirst(new SimpleCommandLinePropertySource(args));   // 517
}
environment.getPropertySources().addLast(new ApplicationInfoPropertySource(this.mainApplicationClass));
```

`setAddCommandLineProperties(false)` 可以关掉这一档（文档 `external-config.adoc` 第 69 行）。已经存在同名源时，Boot 用 `CompositePropertySource` 把 SpringApplication 的参数叠到原有命令行源前面（第 509–514 行）。

## 2.8 占位符在"获胜值"上展开

【源码证据】`fw:spring-core/.../env/AbstractPropertyResolver.java` 第 245–249 行 `resolvePlaceholders`，第 260–268 行说明：`getProperty` 走的嵌套展开会尊重 `ignoreUnresolvableNestedPlaceholders`；直接调用 `resolvePlaceholders` / `resolveRequiredPlaceholders` 则各按自己的严格程度处理。

因此 yml 里的

```yaml
app:
  path: ${user.dir}/data
```

会在 `getProperty("app.path")` 时把 `${user.dir}` 解析成系统属性里的值（系统属性比这份 yml 靠前，一定能命中）。解析失败时，非严格模式留下原文字，严格模式抛异常。`@PropertySource` 的 location 使用的是 `resolveRequiredPlaceholders`（`PropertySourceProcessor.java` 第 90 行），location 里的占位符解析不了会失败，除非 `ignoreResourceNotFound=true`。

## 2.9 Profile 是 Environment 的另一半，不是另一个配置文件格式

【源码证据】`fw:spring-core/.../env/AbstractEnvironment.java`

- 激活属性名 `spring.profiles.active`（第 77 行），逗号分隔。
- `doGetActiveProfiles`（第 272–282 行）：若还没人调用过 `setActiveProfiles`，就读这个属性并写进集合。
- `isProfileActive`（第 407–411 行）：在激活集合里，或者激活集合为空且该 profile 属于默认集合（默认含 `default`）。

Boot 在 ConfigData 收尾时用算出来的 profile **覆盖** Environment 上的激活集合（`ConfigDataEnvironment.applyToEnvironment` 第 337–339 行 `setActiveProfiles`）。所以"profile 文件为什么被加载"要看下一章的贡献树，而不是看这份集合的初始值。`@Profile` 判断的是最终这组激活 profile。

**本章小结**：Framework 规定了货架和"非 null 短路"。标准货架出厂时只有系统属性和环境变量，系统属性在前。Servlet 三个源更靠前。命令行、配置文件、Nacos 都还没出现。`null` 不会挡住更低的来源。环境变量的 `FOO_BAR` 只在读 OS 环境时等价于 `foo.bar`。

---

# 三、Boot：在 refresh 之前把文件放上货架

## 3.1 时机

【源码证据】`boot:.../SpringApplication.java` `prepareEnvironment` 第 338–346 行

```java
ConfigurableEnvironment environment = getOrCreateEnvironment();
configureEnvironment(environment, applicationArguments.getSourceArgs());  // 命令行 addFirst
ConfigurationPropertySources.attach(environment);
listeners.environmentPrepared(bootstrapContext, environment);             // EnvironmentPostProcessor
ApplicationInfoPropertySource.moveToEnd(environment);
DefaultPropertiesPropertySource.moveToEnd(environment);                   // 默认值钉在末尾
```

`environmentPrepared` 发布 `ApplicationEnvironmentPreparedEvent`，`EnvironmentPostProcessorApplicationListener`（登记在 `spring.factories` 第 45 行）跑完全部 `EnvironmentPostProcessor`。配置文件进 Environment 发生在这里，**早于** `refreshContext`（`run` 方法第 309 行）。这是 `logging.level.*` 能在日志系统初始化时就生效的原因。

`defaultProperties` 的收尾在 `DefaultPropertiesPropertySource.moveToEnd`（`boot:.../env/DefaultPropertiesPropertySource.java` 第 115–119 行）：从列表里摘下再 `addLast`。无论中间谁 `addLast` 过配置文件，默认值都垫底。

## 3.2 高优先级几档是怎么锚上去的

`spring.factories`（`boot:core/spring-boot/src/main/resources/META-INF/spring.factories`）第 47–53 行登记的处理器：

```
org.springframework.boot.EnvironmentPostProcessor=
  CloudFoundryVcapEnvironmentPostProcessor
  ConfigDataEnvironmentPostProcessor          ORDER = HIGHEST_PRECEDENCE + 10
  RandomValuePropertySourceEnvironmentPostProcessor   ORDER = HIGHEST + 1
  SpringApplicationJsonEnvironmentPostProcessor       ORDER = HIGHEST + 5
  SystemEnvironmentPropertySourceEnvironmentPostProcessor  ORDER = JSON 的 ORDER - 1
```

`Ordered` 的值越小越先执行。它们不靠执行先后抢位置，而靠相对锚点：

| 处理器 | 证据 | 插入方式 |
|---|---|---|
| Random | `RandomValuePropertySource.addToEnvironment` 第 158–162 行 | 有 `systemEnvironment` 就 `addAfter` 它，否则 `addLast`。只回答 `random.*` |
| JSON | `SpringApplicationJsonEnvironmentPostProcessor` 第 71 行 ORDER，第 143–165 行 | 有 Servlet 源就插到第一个 Servlet 源之前；否则 `addBefore(systemProperties)`。没有这些锚点才 `addFirst` |
| systemEnvironment 包装 | `SystemEnvironmentPropertySourceEnvironmentPostProcessor` 第 24–28、48–50 行 | **替换**同名源，给每个环境变量补上 Origin，不改变位置 |
| ConfigData | `ConfigDataEnvironmentPostProcessor` 第 40 行 `ORDER = HIGHEST_PRECEDENCE + 10` | 见 3.3，全部 `addLast` |

JSON 插在系统属性之前、命令行之后（命令行已经在下标 0，`addBefore(systemProperties)` 不会把它挤下去）。随机源紧挨在环境变量后面，因此随机源低于环境变量、高于随后 `addLast` 的配置文件。和 1.5 的表一致。

`SystemEnvironmentPropertySourceEnvironmentPostProcessor` 的 ORDER 比 JSON 更小（第 39 行 `DEFAULT_ORDER = JSON 的 ORDER - 1`），它只替换环境变量源本身。

## 3.3 ConfigData：三趟导入，然后按树的遍历顺序 addLast

【源码证据】`boot:.../context/config/ConfigDataEnvironment.java` `processAndApply` 第 224–235 行

```java
void processAndApply() {
    ConfigDataImporter importer = new ConfigDataImporter(...);
    ConfigDataEnvironmentContributors contributors = processInitial(...);          // 无激活上下文
    ConfigDataActivationContext activationContext = createActivationContext(...);
    contributors = processWithoutProfiles(...);                                    // profile 尚未确定
    activationContext = withProfiles(...);                                         // 从已加载文档推断 profile
    contributors = processWithProfiles(...);                                       // 再加载 application-{profile}
    applyToEnvironment(...);
}
```

默认搜索位置（第 79–85 行），每一组内部用 `;` 分成同一 level，组与组之间是不同 level：

```java
locations.add(ConfigDataLocation.of("optional:classpath:/;optional:classpath:/config/"));
locations.add(ConfigDataLocation.of(
    "optional:file:./;optional:file:./config/;optional:file:./config/*/"));
```

`optional:` 表示文件不存在不算失败（`ConfigDataLocation` 的前缀，文档第 196–198 行）。

初始导入的登记顺序在 `getInitialImportContributors`（第 189–194 行）：

1. `spring.config.import`（第 67 行常量）整组放进一个贡献者
2. `spring.config.additional-location` 每个位置单独一个贡献者，**按下标从后往前**登记（第 201–203 行 `i = length-1 .. 0`）
3. `spring.config.location`，没设置时用上面的 `DEFAULT_SEARCH_LOCATIONS`，同样从后往前登记

从后往前登记，配合后面的"先遍历到的先 `addLast`"，得到文档里的结论：`spring.config.location` **替换**默认位置，写在后面的 location 压过写在前面的；`additional-location` 整组高于默认位置（`external-config.adoc` 第 169–182 行）。这三个属性必须来自环境变量、系统属性或命令行，因为它们在读 `application.*` 之前就要用（文档第 149–150 行）。

应用到 Environment 时只追加 `BOUND_IMPORT`：

【源码证据】`ConfigDataEnvironment.java` `applyContributor` 第 343–361 行

```java
for (ConfigDataEnvironmentContributor contributor : contributors) {
    if (contributor.getKind() == Kind.BOUND_IMPORT && propertySource != null && contributor.isActive(...)) {
        propertySources.addLast(propertySource);    // 356
    }
}
```

已有的命令行、系统属性、环境变量是 `Kind.EXISTING`，这里不会再动它们。配置文件因此整体低于第 3–10 档。随后第 333 行再次 `moveToEnd(defaultProperties)`。

遍历顺序决定"哪份文件先被 addLast"（先 addLast = 更高）。【源码证据】`ConfigDataEnvironmentContributor.java` 内部迭代器第 545–586 行：

- 先走 `AFTER_PROFILE_ACTIVATION` 的子节点（profile 文件、以及被标成 profile 级的 import）
- 再走 `BEFORE_PROFILE_ACTIVATION` 的子节点（普通 `application.*` 和普通 import）
- **最后才是节点自己**

子节点先于父节点被 `addLast`，所以 **import 进来的文档高于写下 `spring.config.import` 的那份文档**。profile 阶段先于普通阶段被 `addLast`，所以 **profile 文件高于非 profile 文件**。这和文档第 280、294、235 行的文字是同一件事。

## 3.4 贡献树里还有两次"倒序"，它们专门用来实现"后者优先"

**同一批候选资源，从最后一个开始放入 LinkedHashMap。**

【源码证据】`ConfigDataImporter.java` `load` 第 106–126 行：`for (int i = candidates.size() - 1; i >= 0; i--)` 然后 `result.put`。`LinkedHashMap` 的迭代顺序就是放入顺序。于是解析列表里的最后一项，会变成贡献者列表里的第一项，最先被 `addLast`，优先级最高。

**同一份 ConfigData 里的多个 PropertySource（YAML 的 `---` 多文档），按下标从大到小挂成贡献者。**

【源码证据】`ConfigDataEnvironmentContributors.java` `asContributors` 第 172–175 行：

```java
for (int i = data.getPropertySources().size() - 1; i >= 0; i--) {
    contributors.add(... ofUnboundImport(..., data, i, ...));
}
```

文件底部的文档下标更大，先进入贡献者列表，优先级更高。

这两次倒序加上"先遍历先 addLast"，把"后面的覆盖前面的"变成了列表头部。下一章用这个机制解释每种文件关系。

**本章小结**：Boot 不发明新货架。它在 `prepareEnvironment` 里用锚点放好命令行、JSON、随机源，再用 ConfigData 的三趟导入把文件 `addLast` 到环境变量之后、默认值之前。profile 子树先挂、import 子节点先于父文件挂、候选列表和多文档都倒序进入贡献者——这四条合在一起就是配置文件优先级。

---

# 四、配置文件之间的次序

## 4.1 目录：越靠外越高，同一组里越靠后越高

文档 `external-config.adoc` 第 114–124 行给出的位置，按"后面的覆盖前面的"排列：

1. classpath 根 `application.*`
2. classpath `/config` 包
3. 工作目录 `./application.*`
4. `./config/`
5. `./config/*/` 的直接子目录（按绝对路径字母序，文档第 221–222 行；只对外部目录有效）

对应源码就是 3.3 的两个 location 组：第一组 classpath，第二组 file。file 组在 `DEFAULT_SEARCH_LOCATIONS` 数组里下标更大，登记时从后往前，所以 file 组整体高于 classpath 组。组内 `./config/*/` 写在分号列表最后，同样更高。

`spring.config.location` 用逗号是"不同 level"，用分号是"同一 level"。文档第 241–266 行的例子：`classpath:/cfg/,classpath:/ext/` 会先处理完 cfg 的全部 profile 文件，再处理 ext；写成 `classpath:/cfg/;classpath:/ext/` 时，两个目录的同名 profile 文件放在同一层比较，`prod,live` 的 last-wins 会穿过这两个目录。生产上如果有人把外挂目录和包内目录用错分隔符，看到的就是"profile 覆盖关系反了"。

`spring.config.name` 默认 `application`（`StandardConfigDataLocationResolver.java` 第 51–56、84–85 行）。改名字必须来自环境/命令行，和 location 一样早。

## 4.2 Profile：激活列表里最后一个最高

【源码证据】`StandardConfigDataLocationResolver.getProfileSpecificReferences` 第 141–150 行：按 `profiles` 的迭代顺序，为每个 profile 生成 `application-{profile}` 引用。

加载时 `ConfigDataImporter.load` 再把候选倒序。激活列表 `[prod, live]` 里 `live` 排在后面，倒序之后最先 `addLast`，所以 **`application-live` 高于 `application-prod`**。文档第 236–237 行的原话是 last-wins。

profile 文件本身带着 `Option.PROFILE_SPECIFIC`：

【源码证据】`StandardConfigDataLoader.java` 第 26、44–45 行

```java
private static final PropertySourceOptions PROFILE_SPECIFIC =
        PropertySourceOptions.always(Option.PROFILE_SPECIFIC);
...
PropertySourceOptions options = (resource.getProfile() != null) ? PROFILE_SPECIFIC : NON_PROFILE_SPECIFIC;
```

`ConfigData.Option` 的定义（`ConfigData.java` 第 256–274 行）：

| 选项 | 含义 |
|---|---|
| `IGNORE_IMPORTS` | 这份数据里的 `spring.config.import` 不再展开 |
| `IGNORE_PROFILES` | 这份数据里的 `spring.profiles.active` / `include` 不参与激活 |
| `PROFILE_SPECIFIC` | 放进 profile 阶段，和 profile 文件同一层比较 |

没有激活任何 profile 时，默认 profile 是 `default`，会去找 `application-default.*`（文档第 268–269 行；Framework 侧 `isProfileActive` 在激活集为空时认默认集）。

## 4.3 同一目录里 `.properties` 高于 YAML

`spring.factories` 第 6–10 行，`PropertySourceLoader` 的顺序是：

```
PropertiesPropertySourceLoader     扩展名 properties, xml
YamlPropertySourceLoader           扩展名 yml, yaml
```

【源码证据】`StandardConfigDataLocationResolver.getReferencesForConfigName` 第 188–199 行：每个扩展名 `references.addFirst`。加载器在前的扩展名会先被 addFirst，随后被更晚的扩展名顶到队尾。队尾在 `ConfigDataImporter.load` 的倒序里变成最先 `addLast`。

推演结果（同一目录、同一 profile）：

**`application.properties` > `application.xml` > `application.yml` > `application.yaml`**

文档第 41–42 行因此建议整个应用只留一种格式，并写明同位置 `.properties` 优先。混用时，yml 里的同名 key 看起来"没生效"，多半是旁边还有一份 properties。

## 4.4 一份 YAML 里，靠后的 `---` 文档更高

`YamlPropertySourceLoader.load`（第 32–48 行）按文档顺序各生成一个 `OriginTrackedMapPropertySource`，名字带 `(document #i)`。`asContributors` 把下标倒过来挂到树上，所以 **document #1 高于 document #0**。

每个文档还可以带 `spring.config.activate.on-profile`。不匹配的文档在 `applyContributor` 里因 `isActive` 为假被跳过（`ConfigDataEnvironment.java` 第 349–352 行），它不参与覆盖。

## 4.5 import：子文档高于父文档，同一键下列表靠后的更高

文档第 279–294 行：import 当作"插在声明它的那份文档上面的附加文档"，导入结果压过触发导入的文件。第 349–373 行补了两条容易误判的规则：

- 在**同一份文档里**，`spring.config.import` 写在文件头部还是尾部，结果一样。父子关系由贡献树决定，不由键在文件中的行号决定。
- 同一个 `spring.config.import` 下列了多条位置时，**按书写顺序处理，后面的优先**。这就是 `ConfigDataImporter` 倒序的直接效果。

导入路径分两种（文档第 312–320 行）：以 `classpath:`、`file:`、`nacos:` 这类协议开头的是固定位置；没有协议、也不以 `/` 开头的，相对**写 import 的那份文件所在目录**解析（`getResourceLocation` 第 160–165 行，父资源是 `StandardConfigDataResource` 时拼接父目录）。

`optional:` 只影响"找不到怎么办"，不改变相对/绝对的判断（文档第 322 行）。

第三方配置中心接在这里。文档第 378–386 行写明：`ConfigDataLocationResolver` + `ConfigDataLoader` 就是扩展点，Consul、ZooKeeper 和 Nacos 走的是同一条缝。Nacos 的 `nacos:` 前缀没有更特殊的货架。

## 4.6 一张可以拿去对 `/actuator/env` 的文件层序

默认搜索位置是两组，file 组整组高于 classpath 组。**组内**才是 profile 文件高于普通文件。所以 jar 外的 `application.yml` 高于 jar 内的 `application-prod.yml`。官方文档 `external-config.adoc` 第 34–39 行的四段顺序（后段覆盖前段）就是这个结果。

激活 `prod,live`、同一位置同时有 properties 和 yaml 时，查找先命中的在上：

```
file:./config/*/application-live.properties     ← 若 config 下还有子目录
file:./config/application-live.properties
file:./application-live.properties
file:./config/*/application-live.yaml
... 同目录 yaml 低于 properties，live 高于 prod
file:./config/application-prod.properties
file:./application-prod.yaml
file:./config/application.properties            ← jar 外的普通文件，仍高于 jar 内的 profile 文件
file:./application.yaml
classpath:/config/application-live.properties
classpath:/application-live.yaml
classpath:/config/application-prod.*
classpath:/application-prod.*
classpath:/config/application.properties
classpath:/application.yaml
```

`spring.config.import` 写在哪一份文件里，导入结果就插在**那一份**的上方，不会自动升到所有文件之上。多条 import 中靠后的更高。第九章用同一批真实文件把 Nacos 的三条 dataId 嵌进这张表。

**本章小结**：目录组越靠外越高，组内 profile 高于普通文件，profile 列表最后一个最高；同位置 properties 高于 yaml；YAML 靠后的文档更高；import 高于写下它的那份文件，同一列表里靠后的 import 更高。jar 外普通文件高于 jar 内 profile 文件，这是两组 location 的先后，不是“所有 profile 文件都高于所有普通文件”。

---

# 五、`@PropertySource` 与占位符的时序

## 5.1 它在 refresh 里才进货架，所以垫底，而且赶不上早期配置

【源码证据】`fw:spring-context/.../annotation/ConfigurationClassParser.java` `doProcessConfigurationClass` 第 314–319 行：处理配置类时，在 `@ComponentScan` 之前处理 `@PropertySource`。

真正插入在 `PropertySourceProcessor.addPropertySource`（`fw:spring-core/.../io/support/PropertySourceProcessor.java` 第 136–142 行）：

```java
if (this.propertySourceNames.isEmpty()) {
    propertySources.addLast(propertySource);          // 第一个注解源：直接垫底
}
else {
    String lastAdded = this.propertySourceNames.get(this.propertySourceNames.size() - 1);
    propertySources.addBefore(lastAdded, propertySource);  // 后处理的注解源更高
}
```

此时 ConfigData 已经 `addLast` 完毕，再 `addLast` 就把 `@PropertySource` 放到所有配置文件之后，也就是 **低于 `application.*`，高于 `defaultProperties`**。后扫描到的 `@PropertySource` 插到前一个注解源前面，所以多个注解之间后者优先。

Boot 文档第 16–18 行的警告对应的就是这个时序：`logging.*` 和 `spring.main.*` 在 `environmentPrepared` 期间已经被读走，`refresh` 里才出现的 `@PropertySource` 改不了它们。需要这两类值，写进 `application.*`、环境变量或命令行。

`@PropertySource` 默认只吃 `.properties`（Framework 的 `PropertySourceFactory`）。要在注解里加载 YAML，得自备 `PropertySourceFactory`。这也是业务配置应该进 ConfigData 而不是注解的原因之一。

## 5.2 占位符、`@Value` 与绑定用的是同一条获胜值

`getProperty` 在返回字符串前展开嵌套占位符（第二章 2.4）。`@Value("${app.path}")` 最终调用的也是 `Environment`。因此：

- 占位符写在低优先级文件里，引用的可以是高优先级来源里的 key，例如 yml 引用环境变量。
- 高优先级来源把某个 key 设成了字面量，低优先级文件里的 `${...}` 不会再参与这个 key 的取值。
- 循环占位符会在解析器里失败。严格/非严格由 `AbstractPropertyResolver` 的开关决定（第 260–268 行）。

绑定到 `@ConfigurationProperties` 时，Boot 还会做宽松名字和类型转换。那是绑定层的规则，不改变"哪个 PropertySource 获胜"。`/actuator/env` 看的是获胜前的各来源，`/actuator/configprops` 看的是绑定结果。

**本章小结**：`@PropertySource` 是 refresh 阶段的后来者，`addLast` 决定它低于全部 ConfigData。早期属性不要放进它。占位符展开的是整条列表上的获胜值。

---

# 六、业界实践

下面每条都对应前面已经证明的顺序，而不是风格偏好。

## 6.1 按"谁有权改、改了要不要重启"分层，而不是按文件名分层

| 层 | 放什么 | 放哪 | 为什么是这个优先级 |
|---|---|---|---|
| 进程身份与密钥 | 数据库密码、Nacos 地址、命名空间 ID、私钥 | OS 环境变量，或 K8s Secret 卷 + `configtree:` | 环境变量高于所有配置文件（1.5 第 9 档）。配置中心的地址不能只写在配置中心里 |
| 一次发布的开关 | 临时把某个开关打到指定值 | 命令行 `--key=value` 或 `-D` | 命令行和 `-D` 高于环境变量和文件，适合发布系统注入，不适合长期堆砌 |
| 环境差异的动态配置 | 各环境的超时、降级、功能开关、线程池 | Nacos（或同类配置中心）的 dataId | 落在 ConfigData 内部，可刷新；压不过密钥那一层 |
| 随包默认值 | 没配也能启动的安全默认 | jar 内 `application.yml` | 最低的业务文件。jar 外文件和 profile 文件都会压过它 |
| 代码级兜底 | 组件作者的默认值 | `SpringApplication.setDefaultProperties` 或 `@ConfigurationProperties` 的字段初值 | `moveToEnd`，谁都能盖 |

jar 内的 `application.yml` 只保留"连配置中心都没有时进程仍能表达自己的默认行为"，加上 `spring.config.import`。不要把生产密码提交进 Git。

## 6.2 Profile 只表示"同一套二进制的运行切片"

用 `SPRING_PROFILES_ACTIVE`（环境变量）选择 `prod` / `staging`。激活列表最后一个最高，所以 `prod,canary` 表示 canary 覆盖 prod，这个顺序要写进发布规范，不能靠口头约定。

不要同时用"Profile 名字"和"Nacos 命名空间"表示同一个维度。命名空间负责隔离（不同环境不同账号、ACL、数据），Profile 负责同一环境里的切片（灰度、多活机房）。两个都用来表示 dev/test/prod，排障时会不知道值来自 `application-prod.yml` 还是 namespace。

## 6.3 密钥用环境变量或 configtree，不用配置文件正文

Boot 文档第 468–503 行把 Kubernetes 的两种挂载写清楚了：

- 卷上是一整份 YAML：用 `spring.config.import=optional:file:/etc/config/application.yaml`，它是普通 import，高于 jar 内文件。
- 卷上是"一个 key 一个文件"（ConfigMap/Secret 的 subPath 或 items）：用 `optional:configtree:/etc/config/`。`spring.factories` 第 12–16 行的 `ConfigTreeConfigDataLocationResolver` 负责这个前缀。文件名变成属性名，文件内容变成值。

`configtree` 仍然是 ConfigData，优先级低于环境变量和命令行，高于父文件。真正不能进镜像、不能进 Nacos 明文的材料，优先放环境变量或 Secret 卷。Nacos 有传输加密插件，那是服务端能力，替代不了"客户端启动前必须知道 Nacos 地址和身份"这件事。

## 6.4 本地覆盖要可预期

默认 `preference=local` 时，**本地 `application-{profile}.yml` 高于 Nacos 普通 dataId**（下一章证明）。这适合"线上紧急改一个 key，先放进外挂 profile 文件，重启即生效，不必等配置中心"。反过来，希望 Nacos 压过本地 profile 文件时，只给那一个 dataId 加 `preference=remote`，不要全局 remote，否则本地排障文件会失效。

`optional:nacos:` 让开发机没起 Nacos 时仍能用 jar 内默认值启动。生产环境的 import 去掉 `optional:`，拿不到配置就失败，避免带着默认值进生产。

## 6.5 排障看来源，不看"我觉得 yml 里写了"

- `/actuator/env` 或 `env` 端点列出 PropertySource 的顺序和每个 key 的 Origin（文件名、行号、或 `SystemEnvironmentOrigin`）。Boot 4.2 给环境变量补 Origin 就是为了这个（3.2 的包装器）。
- `logging.level.org.springframework.core.env=trace` 时，`PropertySourcesPropertyResolver` 会打印正在搜索的源名字（第 76–78 行）。
- 同名 key 只出现在低优先级文件里"像是没加载"，先在更高的源里搜这个 key，包括环境变量的下划线形式。
- YAML 和 properties 并存时，先找 properties。

## 6.6 不要做的事

- 不要在 Nacos 正文里写 `spring.config.import` 或 `spring.profiles.active`。Loader 给每份 Nacos 数据加了 `IGNORE_IMPORTS` 和 `IGNORE_PROFILES`（第七章）。写了也不会展开，人会以为生效了。
- 不要再引入 `spring-cloud-starter-bootstrap` 来加载 Nacos。2025.1 的自动配置在检测到 bootstrap 标记类时直接警告：这个依赖会让 Nacos Config 不能正常工作（`NacosConfigSpringCloudAutoConfiguration.java` 第 57–64 行）。
- 不要用 `@PropertySource` 承载会在启动早期被读取的属性。
- 不要用"把值设成空字符串"以外的方式指望 `null` 去清空低优先级配置。`null` 会被跳过。要盖掉，给一个明确的空串或业务上的默认字面量。

---

# 七、用 Nacos 做配置组件

## 7.1 它在 Spring 里的位置

Nacos 配置接入是两个 SPI，登记在 `sca2025:spring-cloud-alibaba-starters/spring-alibaba-nacos-config/src/main/resources/META-INF/spring.factories`：

```
org.springframework.boot.context.config.ConfigDataLocationResolver=
    com.alibaba.cloud.nacos.configdata.NacosConfigDataLocationResolver
org.springframework.boot.context.config.ConfigDataLoader=
    com.alibaba.cloud.nacos.configdata.NacosConfigDataLoader
```

解析器的前缀是字面量 `nacos:`（`NacosConfigDataLocationResolver.java` 第 64 行），`getOrder()` 返回 `-1`（第 77–79 行），早于兜底的 `StandardConfigDataLocationResolver`（那个的 order 是 `LOWEST_PRECEDENCE`）。`spring.cloud.nacos.config.enabled` 默认 true（`isResolvable` 第 128–130 行 `orElse(true)`）。

`resolve(...)` 对非 profile 阶段直接返回空列表（第 138–142 行）。真正的加载发生在 `resolveProfileSpecific`（第 146–163 行）：此时 Binder 已经能看到父文档和环境变量，所以 `spring.cloud.nacos.*` 可以写在**同一份** `application.yml` 里，和 `spring.config.import` 放在一起。

dataId 就是 URI 路径上的那一段，**不会**自动拼 `-{profile}`：

【源码证据】`NacosConfigDataLocationResolver.java` `dataIdFor` 第 280–290 行：路径去掉前导 `/` 后必须恰好一段，否则抛 `illegal dataId`。`loadConfigDataResources` 第 193–198 行把这段原样放进 `NacosItemConfig.dataId`。

旧文章里的"自动加载 `${spring.application.name}.yml` 和 `${spring.application.name}-${profile}.yml`"来自已经不在当前分支的 `NacosPropertySourceLocator`。现在要哪份 dataId，就在 `spring.config.import` 里写哪一条。

查询参数只认三个（第 65–69 行）：`group`、`refreshEnabled`、`preference`。后缀从 dataId 最后一个点后面取，没有点就用 `spring.cloud.nacos.config.file-extension`（`suffixFor` 第 265–270 行）。

## 7.2 拉下来之后放进哪一种 PropertySource

【源码证据】`sca2025:.../configdata/NacosConfigDataLoader.java` `doLoad` 第 91–105 行

```java
List<PropertySource<?>> propertySources = pullConfig(...);   // getConfig + 按后缀解析
NacosPropertySource propertySource = new NacosPropertySource(propertySources,
        config.getGroup(), config.getDataId(), new Date(), config.isRefreshEnabled());
NacosPropertySourceRepository.collectNacosPropertySource(propertySource);
return new ConfigData(Collections.singletonList(propertySource), getOptions(...));
```

2025.1 把解析结果收成**一个** `NacosPropertySource` 再交给 Boot。名字是 `dataId + "," + group`（`NacosPropertySource.java` 第 69 行 `String.join(",", dataId, group)`）。

同一份 dataId 里如果有多个 YAML 文档，`getSourceMap` 按文档顺序 `putAll`（第 96–108 行），后一个文档的同名 key 覆盖前一个。效果和 Boot 自己的"后文档优先"一致，但对外只剩一个 PropertySource，刷新时也是整份替换。

2023.x 的 `doLoad`（`sca2023:.../NacosConfigDataLoader.java` 第 90 行）返回的是解析出的 `propertySources` 列表本身，不包一层。多文档会变成多个贡献者，由 Boot 的倒序决定优先级。胜者相同，`/actuator/env` 里看到的条目数不同。

每份 Nacos 数据的 ConfigData 选项（2025 与 2023 相同，`getOptions` 第 125–136 行）：

```java
options.add(Option.IGNORE_IMPORTS);
options.add(Option.IGNORE_PROFILES);
if (getPreference(...) == REMOTE) {
    options.add(Option.PROFILE_SPECIFIC);   // issue 2455
}
```

`preference` 默认 `LOCAL`（第 148–150 行 `orElse(LOCAL)`）。单条 import 的查询参数 `preference=remote` 可以盖过全局值（第 151–154 行）。非法取值打错误日志并退回默认（第 156–160 行）。

`ConfigPreference` 的类注释把语义写明了（`ConfigPreference.java` 第 19–31 行）：

- `LOCAL`：本地 profile 文件高于远程，因为本地 profile 文件带 `PROFILE_SPECIFIC`。
- `REMOTE`：把远程数据也标成 profile 级，让它压过本地 profile 文件。

对应到第三章的树：`PROFILE_SPECIFIC` 的子节点会被 `moveProfileSpecific`（`ConfigDataEnvironmentContributor.java` 第 272–284 行）从"普通阶段"挪到"profile 阶段"的列表前部。profile 阶段先被 `addLast`，所以：

| preference | 这条 Nacos dataId 的位置 |
|---|---|
| `local`（默认） | 高于写下 import 的那份非 profile 文件；**低于** `application-{profile}.*` |
| `remote` | 挪到 profile 阶段前部，**高于** `application-{profile}.*`，仍然低于命令行、环境变量、系统属性 |

多条 import 之间，仍然是 Boot 的"列表靠后的优先"。`preference` 不改变两条 Nacos dataId 的相对顺序，只改变它们和**本地 profile 文件**的关系。

`IGNORE_PROFILES` / `IGNORE_IMPORTS` 意味着 Nacos 正文里的 `spring.profiles.active` 不会激活 profile，正文里的 `spring.config.import` 也不会再导入别的文件。Profile 用环境变量给，import 清单只写在本地 `application.yml`。

## 7.3 客户端默认值（写配置时以此为准）

【源码证据】`sca2025:.../NacosConfigProperties.java`

| 属性 | 默认 | 行 |
|---|---|---|
| `spring.cloud.nacos.config.server-addr` 为空时，再看 `spring.cloud.nacos.server-addr`，仍空则 `127.0.0.1:8848` | `overrideFromEnv` 与 `DEFAULT_ADDRESS` | 89、211–218、565–566 |
| `group` | `DEFAULT_GROUP` | 118 |
| `file-extension` | `properties` | 126 |
| `timeout`（`getConfig` 超时，毫秒） | `3000` | 130 |
| `refresh-enabled` | `true` | 197 |
| `namespace` 写成 `public` | 实际传给客户端的是空串（Nacos 的 public 命名空间 ID） | 84、577–579 |
| `username` / `password` | 空则从 `${spring.cloud.nacos.username}` / `password` 解析 | 220–227 |
| `shared-configs` / `extension-configs` | 字段还在（第 188、193 行） | 当前 resolver **不读取**这两个列表 |

最后一行需要单独说。2023.x 与 2025.1.x 的 `NacosConfigDataLocationResolver` 都只加载 URI 里的那一个 dataId。`shared-configs`、`extension-configs` 留在属性类里，是旧 bootstrap 模型的字段。现在表达"公共配置 + 应用配置 + 扩展配置"的方式，是多条 `spring.config.import`，靠后的优先。不要在新项目里配 `shared-configs` 然后以为它会加载。

2025.1 的 `pullConfig` 会先看 `NacosSnapshotConfigManager` 里的快照，没有再 `configService.getConfig`（`NacosConfigDataLoader.java` 第 166–178 行）。这是刷新路径把新正文放进快照、避免同一次刷新再打一次查询用的。2023.x 的 `pullConfig`（第 139–146 行）没有这一步，直接 `getConfig`。

解析失败方面，2025.1 把 SnakeYAML 的 `DuplicateKeyException` 单独抛成 `NacosConfigParseException`，避免被当成"资源不存在"然后被 `optional:` 悄悄吞掉（第 109–117 行）。YAML 里重复 key 在 Nacos 上应该让启动失败。

## 7.4 推荐的依赖与配置

依赖（版本由 SCA BOM 管理；下面的版本号是 2025.1.x 这条线的父 POM）：

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-alibaba-dependencies</artifactId>
            <version>2025.1.0.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>com.alibaba.cloud</groupId>
        <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
    </dependency>
</dependencies>
```

2023.x 生产线把 BOM 换成 `2023.0.3.x`，Boot 3.2 / nacos-client 2.4.3，import 写法相同。不要同时引入 `spring-cloud-starter-bootstrap`。

jar 内 `application.yml`（进 Git 的只有非密钥）：

```yaml
spring:
  application:
    name: order-service
  config:
    import:
      # 靠后的 import 优先级更高。三条都是 nacos: 固定位置，不是相对路径。
      - optional:nacos:common.yaml?group=SHARED_GROUP&refreshEnabled=true
      - optional:nacos:${spring.application.name}.yaml?group=DEFAULT_GROUP&refreshEnabled=true
      - optional:nacos:${spring.application.name}-override.yaml?group=DEFAULT_GROUP&refreshEnabled=true&preference=remote
  cloud:
    nacos:
      username: ${NACOS_USERNAME:}
      password: ${NACOS_PASSWORD:}
      config:
        namespace: ${NACOS_NAMESPACE:}
        group: DEFAULT_GROUP
        file-extension: yaml
        timeout: 5000
        refresh-enabled: true
        # 全局 local：除了显式 preference=remote 的那条，Nacos 低于本地 application-{profile}
        preference: local
```

进程环境（K8s Deployment 的 env，或 systemd 的 Environment）：

```text
SPRING_PROFILES_ACTIVE=prod
SPRING_CLOUD_NACOS_SERVER_ADDR=nacos.prod.internal:8848
NACOS_NAMESPACE=3e8f0a2c-xxxx-xxxx-xxxx-xxxxxxxxxxxx
NACOS_USERNAME=order-service
NACOS_PASSWORD=<来自 Secret，不进 Git>
```

`SPRING_CLOUD_NACOS_SERVER_ADDR` 能被读到，是因为 `SystemEnvironmentPropertySource` 把下划线映回 `spring.cloud.nacos.server-addr`，而 `overrideFromEnv` 会解析这个键（第 211–218 行）。命名空间不要写显示名 `public`：代码会把字面量 `public` 转成空串（第 577–579 行）；其他环境要用控制台里的 **命名空间 ID**。

生产清单去掉每条上的 `optional:`，让 Nacos 不可达时启动失败。本地开发保留 `optional:`，没有服务端时落回 jar 内默认值。

Nacos 控制台上对应三份配置（类型选 YAML）：

| 命名空间 | Group | dataId | 内容职责 | 相对谁更高 |
|---|---|---|---|---|
| prod 的 ID | `SHARED_GROUP` | `common.yaml` | 注册中心地址以外的公共超时、日志格式等 | 三条 Nacos 里最低。低于 jar 内 `application-prod.yml`，也低于全部 jar 外文件 |
| prod 的 ID | `DEFAULT_GROUP` | `order-service.yaml` | 该服务的数据源 URL（不含密码）、开关 | 高于 `common.yaml`。低于 jar 内 `application-prod.yml` 和全部 jar 外文件 |
| prod 的 ID | `DEFAULT_GROUP` | `order-service-override.yaml` | 希望压过 jar 内 profile 的少量键，例如统一降级开关 | `preference=remote`，高于 jar 内 `application-prod.yml`。jar 外的 `./application.yml` 和 `./application-prod.yml` 仍然更高 |

`order-service.yaml` 示例：

```yaml
order:
  pay-timeout: 30s
  create-qps-limit: 200
spring:
  datasource:
    url: jdbc:mysql://mysql.prod.internal:3306/order
    username: order_rw
    # 密码不放这里。用环境变量 SPRING_DATASOURCE_PASSWORD，它高于本文件
```

`preference=remote` 只把这一条 dataId 抬到**写下 import 的那一组 location** 的 profile 文件前面。import 写在 jar 内 `application.yml` 时，它高于 jar 内的 `application-prod.yml`，仍然低于工作目录里的 `./application.yml` 和 `./application-prod.yml`。jar 外的普通文件就能盖住这条 remote 配置。适合平台强制下发的键要少，并且知道外挂文件仍能压过它。完整排位和每个键的最终值见第九章。

同一条查找链上，`SPRING_DATASOURCE_PASSWORD` 在 `systemEnvironment`，位置高于上面所有文件。密码只放环境变量或 Secret。

## 7.5 和本地文件叠在一起时长什么样

import 写在 jar 内 `classpath:/application.yml`、激活 `prod`、工作目录和 jar 内都有文件时，从高到低是：

```
commandLineArgs
SPRING_APPLICATION_JSON（本例没有）
systemProperties
systemEnvironment
random
file:./config/*/application-prod.*     有子目录才出现
file:./config/application-prod.*
file:./application-prod.*
file:./config/application.*            jar 外普通文件，已经高于下面所有 jar 内文件
file:./application.*
nacos 里 preference=remote 的那条      只高于 jar 内的 profile 文件
classpath:/config/application-prod.*
classpath:/application-prod.*
classpath:/config/application.*        高于写在根 application.yml 里的 local Nacos
nacos 里 preference=local 的 import    列表靠后的 dataId 更高
classpath:/application.yml             写下 import 的那份
@PropertySource
defaultProperties
```

同一路径上 `.properties` 高于 `.yml`。把全局 `preference` 改成 `remote` 后，三条 Nacos 都会标上 `PROFILE_SPECIFIC`，于是三条都高于 jar 内 `application-prod.yml`，jar 外文件仍然更高。每个文件的正文和每个键的最终值在第九章。

## 7.6 改完配置如何进到 Bean

启动拉一次，之后靠监听。

1. `NacosConfigAutoConfiguration` 注册 `NacosContextRefresher`（第 69–76 行）。
2. 应用 `ApplicationReadyEvent` 之后，refresher 遍历 `NacosPropertySourceRepository` 里 `refreshable` 的源，对每个 `(dataId, group)` 调用 `configService.addListener`（`NacosContextRefresher.java` 第 87–112、151 行）。`refreshEnabled=false` 的 dataId 跳过（第 106–108 行）。全局 `refresh-enabled` 默认 true。
3. 回调里把新正文放进 `NacosSnapshotConfigManager`，再发布 `NacosConfigRefreshEvent`（第 120–136 行）。
4. starter 模块里的 `NacosConfigRefreshEventListener`（`spring-cloud-starter-alibaba-nacos-config`）把该事件转成 Spring Cloud 的 `RefreshEvent`（监听器第 42–54 行）。
5. Spring Cloud 的 `ContextRefresher` 收到 `RefreshEvent` 后刷新 Environment，并 rebind `@ConfigurationProperties`、重建 `@RefreshScope` 里的 Bean。

因此业务上：

- 会变的配置写成 `@ConfigurationProperties`，或把依赖它的 Bean 标成 `@RefreshScope`。
- 构造期就读进普通字段的 `@Value` 不会因为 Nacos 推送而变。监听是在 Ready 之后才注册的，启动过程中的那一次拉取才是这些字段的值。
- `NacosConfigSpringCloudAutoConfiguration` 在非默认刷新行为下会用 `SmartConfigurationPropertiesRebinder` 替换全量 rebind（第 42–48 行）。类注释写明默认仍是 Spring Cloud 的全量刷新，按 key 局部刷新是进阶开关，稳定性注释里作者自己标了"不如默认行为稳"。生产保持默认全量刷新即可。

服务端如何把变更推到这条监听（gRPC 监听、dump、灰度版本）见《Spring Cloud Alibaba Nacos.md》第五、六章。本文的优先级不随推送改变：刷新替换的是**同名** `NacosPropertySource` 的内容，它在列表上的位置仍是启动时 ConfigData 定下来的位置。环境变量在刷新之后依然高于它。

## 7.7 2023.x 和 2025.1.x 在优先级上的差异

| 点 | 2023.x（`4ccb7b1`，Boot 3.2.9，nacos-client 2.4.3） | 2025.1.x（Boot 4.1.0，nacos-client 3.1.1） |
|---|---|---|
| 接入 | `nacos:` import | 同左 |
| `preference` 默认 LOCAL，REMOTE 加 `PROFILE_SPECIFIC` | 同 | 同 |
| 交给 Boot 的 PropertySource | 解析列表原文，多文档由 Boot 倒序 | 收成一个 `NacosPropertySource`，内部 `putAll` 后者优先 |
| 刷新前的快照短路 | 无，直接 `getConfig` | `NacosSnapshotConfigManager` 优先 |
| 重复 YAML key | 走通用异常 | `DuplicateKeyException` 明确失败，不被 `optional:` 吞掉 |
| `shared-configs` | 字段在，resolver 不加载 | 同 |
| bootstrap starter | 当前分支已无 `NacosPropertySourceLocator` | 检测到会警告（issue 4259） |

业务配置的写法（多条 import、密钥放环境变量、profile 用 `SPRING_PROFILES_ACTIVE`）两条线通用。

**本章小结**：Nacos 是 ConfigData 的一种 location。dataId 必须写在 import 里，不会按应用名和 profile 自动展开。import 写在 jar 内 `application.yml` 时，`preference=local` 的 dataId 低于 jar 内 profile 文件，也低于全部 jar 外文件；`preference=remote` 只升到 jar 内 profile 文件之前。命令行、`-D` 和环境变量始终更高。`shared-configs` 在这两条分支上不是加载通道。刷新换的是同一槽位里的内容，不改变槽位。

---

# 八、贯通：`order.pay-timeout` 是怎么被找到的

以 7.4 的进程为例，代码执行 `environment.getProperty("order.pay-timeout")`。

1. `PropertySourcesPropertyResolver.getProperty`（第 73 行）从 `MutablePropertySources` 下标 0 开始。
2. `commandLineArgs` 没有这个 key，继续。
3. `spring.application.json`（若存在）、Servlet 源（内嵌容器通常没有）、`systemProperties` 没有，继续。
4. `systemEnvironment` 试 `order.pay-timeout`、`ORDER_PAY_TIMEOUT` 等等（`SystemEnvironmentPropertySource` 第 123–145 行）。部署里没设这个变量，继续。
5. `random` 只处理 `random.*`，继续。
6. 进入 ConfigData 段。在 7.4 这种“只有 jar 内文件、import 写在 `classpath:/application.yml`”的前提下，`order-service-override.yaml`（remote）排在 jar 内 `application-prod.yml` 前面。工作目录里如果还有 `./application.yml`，那份外挂文件更高，见第九章第 8、9 行。
7. jar 内 `application-prod.yml` 若写了该键，**在这里返回**。`preference=local` 的 `order-service.yaml` 不会被看到。这就是“我在 Nacos 改了但 jar 内 profile 文件还压着”的路径。jar 外的 profile 文件更靠前，同样会先返回。
8. 本地 profile 文件也没有，才落到 `order-service.yaml`。`NacosPropertySource.getProperty` 命中 `30s`。值是字符串，解析器展开其中的 `${...}`（本例没有），返回。
9. 若 Nacos 这份是空的，才继续到 `common.yaml`，再继续到 jar 内 `application.yml`。

返回值上的类型转换（`30s` → `Duration`）发生在 `@ConfigurationProperties` 绑定，不发生在这次 `getProperty` 的优先级比较里。绑定失败不会回退到下一个 PropertySource 再试一次——优先级在第 8 步已经结束了。

要核对现场，打开 `/actuator/env/order.pay-timeout`，看 `propertySources` 数组的第一段谁带了 `value`。数组顺序就是本章的查找顺序。

**本章小结**：优先级没有第二套运行时规则。启动时把源排好，查找时短路，刷新时只替换 Nacos 那一格的 Map。排障就是顺着这条列表往下看，直到第一个非 null。同一套文件全部展开后的先后和每个键的最终值，见第九章。

---

# 九、一份可以照着对的订单服务例子

这一章只做一件事：把同一个进程里真正会进 `Environment` 的每一档都写成文件，每档都放进同一个键 `order.from`，再给每档一个别人没有的键。查 `order.from` 就知道谁盖住了谁；查那个独有的键，就知道这一档并没有被丢掉，只是同名键被更高的来源拿走了。

本例固定这些前提，少一个都会改顺序：

- 用 `java -jar` 启动，工作目录是 `/opt/order`。没有 Devtools，没有测试注解，没有 `SPRING_APPLICATION_JSON`，也没有外置 Tomcat，所以没有 Servlet / JNDI 那三档。
- 只激活一个 profile：`prod`。
- 配置文件只用 YAML，同一目录不再放一份 `.properties`。若放了，同路径的 `.properties` 会插到对应 `.yml` 的上面。
- `./config/` 下面没有再套子目录，所以不会出现 `file:./config/*/application*.yml`。有的话，它排在 `file:./config/application*.yml` 的上面。
- `spring.config.import` **只写在 jar 内** `classpath:/application.yml`。jar 外的四份文件都不写 import。

## 9.1 磁盘上有哪些文件

```
/opt/order/                                      启动时的当前目录
├── order-service.jar
├── application.yml                              file:./application.yml
├── application-prod.yml                         file:./application-prod.yml
└── config/
    ├── application.yml                          file:./config/application.yml
    └── application-prod.yml                     file:./config/application-prod.yml

order-service.jar 里面（classpath）
├── application.yml                              classpath:/application.yml     ← 只有这份写了 nacos import
├── application-prod.yml                         classpath:/application-prod.yml
├── config/application.yml                       classpath:/config/application.yml
├── config/application-prod.yml                  classpath:/config/application-prod.yml
└── extra.properties                             @PropertySource 指向它
```

Nacos 控制台（命名空间用环境变量里的 ID，类型选 YAML）另有三份，不在磁盘上：

| Group | dataId | preference |
|---|---|---|
| `SHARED_GROUP` | `common.yaml` | 跟着全局，local |
| `DEFAULT_GROUP` | `order-service.yaml` | local |
| `DEFAULT_GROUP` | `order-service-override.yaml` | 这一条 URL 上写了 `remote` |

## 9.2 启动命令、环境变量、代码里的默认值

这三样不是文件，但它们都在 `Environment` 里，而且名字是固定的。

```text
java -Dorder.from=systemProperties ^
     -Dserver.port=9090 ^
     -jar order-service.jar ^
     --order.from=commandLine ^
     --order.pay-timeout=1s
```

PowerShell 下 `^` 换成反引号。进程环境：

```text
SPRING_PROFILES_ACTIVE=prod
ORDER_FROM=systemEnvironment
SPRING_DATASOURCE_PASSWORD=env-secret
SPRING_CLOUD_NACOS_SERVER_ADDR=127.0.0.1:8848
NACOS_NAMESPACE=<控制台里的命名空间 ID，不要写 public 这个显示名>
```

`ORDER_FROM` 能被当成 `order.from`，是 `systemEnvironment` 这份源自己做的下划线转换（第二章 2.6）。`SPRING_CLOUD_NACOS_SERVER_ADDR` 同理，Nacos 客户端靠它找到服务器，这个地址不能只写在 Nacos 里。

`main` 里再放一份垫底的默认值。它的 PropertySource 名字就是 `defaultProperties`：

```java
public static void main(String[] args) {
    SpringApplication app = new SpringApplication(OrderApplication.class);
    Map<String, Object> defaults = new HashMap<>();
    defaults.put("order.from", "defaultProperties");
    defaults.put("order.app-title", "订单服务");
    app.setDefaultProperties(defaults);
    app.run(args);
}
```

再加一个注解源，让 `@PropertySource` 也出现在列表里。它的名字用注解的 `name`，就是 `extraProperties`：

```java
@Configuration
@PropertySource(name = "extraProperties", value = "classpath:extra.properties")
public class ExtraConfig {
}
```

`extra.properties`：

```properties
order.from=extraProperties
order.legacy-key=from-annotation
```

## 9.3 每一份文件里写了什么

所有文件都写了 `order.from`，取值就是这份源的名字，方便你在 `/actuator/env` 里逐档看到“它写过，但被上面盖住了”。

jar 外，`/opt/order/config/application-prod.yml`：

```yaml
order:
  from: file-config-application-prod
  banner: 外挂 config 目录的 prod 文件
server:
  port: 18080
```

jar 外，`/opt/order/application-prod.yml`：

```yaml
order:
  from: file-application-prod
  feature-flag: from-external-prod
```

jar 外，`/opt/order/config/application.yml`：

```yaml
order:
  from: file-config-application
  cache-ttl: 15s
```

jar 外，`/opt/order/application.yml`：

```yaml
order:
  from: file-application
  region: external-plain
```

Nacos，`order-service-override.yaml`（`preference=remote`）：

```yaml
order:
  from: nacos-override-remote
  degrade: true
```

jar 内，`config/application-prod.yml`：

```yaml
order:
  from: classpath-config-application-prod
  audit-log: true
```

jar 内，`application-prod.yml`：

```yaml
order:
  from: classpath-application-prod
  max-retry: 2
```

jar 内，`config/application.yml`：

```yaml
order:
  from: classpath-config-application
  thread-pool-size: 16
```

Nacos，`order-service.yaml`（local，import 列表的第二条）：

```yaml
order:
  from: nacos-app
  pay-timeout: 30s
  create-qps-limit: 200
spring:
  datasource:
    url: jdbc:mysql://mysql.prod.internal:3306/order
    username: order_rw
```

Nacos，`common.yaml`（local，import 列表的第一条）：

```yaml
order:
  from: nacos-common
  log-pattern: common-pattern
```

jar 内，`application.yml`。import 写在这里，所以三条 Nacos 挂在这份文件上，而不是挂在 jar 外那四份上：

```yaml
spring:
  application:
    name: order-service
  config:
    import:
      - optional:nacos:common.yaml?group=SHARED_GROUP&refreshEnabled=true
      - optional:nacos:${spring.application.name}.yaml?group=DEFAULT_GROUP&refreshEnabled=true
      - optional:nacos:${spring.application.name}-override.yaml?group=DEFAULT_GROUP&refreshEnabled=true&preference=remote
  cloud:
    nacos:
      config:
        namespace: ${NACOS_NAMESPACE:}
        file-extension: yaml
        timeout: 5000
        refresh-enabled: true
        preference: local
order:
  from: classpath-application
  instance-id: ${random.uuid}
```

`${random.uuid}` 写在最低的业务文件里也能展开，因为展开时会再去整条列表上找 `random.uuid`，而 `random` 这份源在环境变量下面、所有 yml 上面。

## 9.4 这个进程里的优先级总表

查找从第 1 行开始，第一个不是 null 的值获胜。`/actuator/env` 里的 `propertySources` 数组就是这个顺序。

Nacos 的三份配置就在这张表里，是 **第 9、13、14 行**。它们在端点里不叫 `application.yml`。`NacosPropertySource` 第 69 行把名字拼成 `dataId,group`，所以你看到的是 `order-service.yaml,DEFAULT_GROUP` 这种字符串，那就是 Nacos 上的那一份文件。本地 yml 的全名则由 `StandardConfigDataLoader` 第 40–41 行拼出来，形如 `Config resource 'file [./config/application-prod.yml]' via location 'optional:file:./config/'`。

| 序 | 种类 | 你在 `/actuator/env` 里看到的名字 | 这份源里和本例有关的内容 | 同名键 `order.from` |
|---|---|---|---|---|
| 1 | 命令行 | `commandLineArgs` | `--order.from=commandLine`，`--order.pay-timeout=1s` | `commandLine` |
| 2 | JVM `-D` | `systemProperties` | `-Dorder.from=systemProperties`，`-Dserver.port=9090` | `systemProperties` |
| 3 | 操作系统环境变量 | `systemEnvironment` | `ORDER_FROM`，`SPRING_PROFILES_ACTIVE=prod`，`SPRING_DATASOURCE_PASSWORD`，`SPRING_CLOUD_NACOS_SERVER_ADDR` | `systemEnvironment` |
| 4 | 随机值 | `random` | 没有预先写好的键，查 `random.uuid` / `random.int` 时现算 | 无 |
| 5 | jar 外 profile 文件 | `file:./config/application-prod.yml` | `order.banner`，`server.port=18080` | `file-config-application-prod` |
| 6 | jar 外 profile 文件 | `file:./application-prod.yml` | `order.feature-flag` | `file-application-prod` |
| 7 | jar 外普通文件 | `file:./config/application.yml` | `order.cache-ttl=15s` | `file-config-application` |
| 8 | jar 外普通文件 | `file:./application.yml` | `order.region=external-plain` | `file-application` |
| 9 | **Nacos 配置** | `order-service-override.yaml,DEFAULT_GROUP` | `order.degrade=true`。这条 import 带了 `preference=remote`，所以高于第 10、11 行的 jar 内 profile 文件，低于第 5–8 行的 jar 外文件 | `nacos-override-remote` |
| 10 | jar 内 profile 文件 | `classpath:/config/application-prod.yml` | `order.audit-log=true` | `classpath-config-application-prod` |
| 11 | jar 内 profile 文件 | `classpath:/application-prod.yml` | `order.max-retry=2` | `classpath-application-prod` |
| 12 | jar 内普通文件 | `classpath:/config/application.yml` | `order.thread-pool-size=16` | `classpath-config-application` |
| 13 | **Nacos 配置** | `order-service.yaml,DEFAULT_GROUP` | `order.pay-timeout=30s`，`order.create-qps-limit=200`，数据源 URL。import 列表的第二条，高于第 14 行 | `nacos-app` |
| 14 | **Nacos 配置** | `common.yaml,SHARED_GROUP` | `order.log-pattern` | `nacos-common` |
| 15 | jar 内普通文件 | `classpath:/application.yml` | `spring.application.name`，`order.instance-id=${random.uuid}`，以及指向上面三份 Nacos 的 import | `classpath-application` |
| 16 | 注解 | `extraProperties` | `@PropertySource`，`order.legacy-key` | `extraProperties` |
| 17 | 代码默认值 | `defaultProperties` | `order.app-title=订单服务` | `defaultProperties` |

第 5–8 行是 jar 外。其中第 7、8 行甚至不是 profile 文件，仍然整组高于第 9–15 行。第 9 行虽然叫 remote，也只越过了 jar 内的 profile 文件（第 10、11 行），越不过工作目录里的 `application.yml`。

jar 内普通文件正好两份，表里都有，中间被 local Nacos 隔开了：第 12 行 `classpath:/config/application.yml`，第 15 行 `classpath:/application.yml`。默认搜索位置 `optional:classpath:/;optional:classpath:/config/` 就这两处，`/config` 写在分号后面，所以第 12 行高于第 15 行。第 15 行没有丢，它是写下 import 的那份父文件，local Nacos（第 13、14 行）作为它的子节点插在它紧上面，于是两份普通文件不再相邻。

第 12 行没有写 import。所以 `order.thread-pool-size` 来自 jar 内 config 目录，不会被 `order-service.yaml` 盖住；反过来 `order-service.yaml` 里的键，只要第 12 行没写，就由 Nacos 提供。

优先级低，只表示**同一个键**上面有人写了，就听上面的。Nacos 上那些上面没人写的键，仍然是 Nacos 说了算，改完也能推下来生效。见 9.7。

### 为什么第 9 行和第 13 行中间夹着 jar 内的 profile 文件

三份 Nacos 是同一次 import 拉下来的，但 Boot 不按“它们都叫 Nacos”排成相邻的三行。每一份 dataId 的位置由两件事决定：它挂在哪一份本地文件上，以及有没有被标成 profile 级。

import 写在 jar 内的 `classpath:/application.yml`（第 15 行）。Boot 把导入结果当成这份文件的子节点：遍历时先看子节点，再看文件自己，所以子节点高于父文件（`ConfigDataEnvironmentContributor` 的迭代器，第 545–586 行，先子后己；`applyContributor` 第 356 行 `addLast`，先挂上的更高）。第 13、14 行因此在第 15 行上面。两条 local 之间，import 列表靠后的更高，所以 `order-service.yaml` 高于 `common.yaml`。

同一组位置里，profile 文件和普通文件分两个阶段。迭代器先走 `AFTER_PROFILE_ACTIVATION`（`application-prod.yml`），再走 `BEFORE_PROFILE_ACTIVATION`（`application.yml`）。所以在 **classpath 这一组内部**，第 10、11 行的 jar 内 profile 文件高于第 15 行，也高于仍然挂在第 15 行下面的第 13、14 行。

`preference=remote` 多做一步。`NacosConfigDataLoader.getOptions`（第 130–134 行）给这份数据加上 `Option.PROFILE_SPECIFIC`。`moveProfileSpecific`（`ConfigDataEnvironmentContributor.java` 第 272–284 行）把带这个标记的子节点从普通阶段摘出来，放到**同一组** profile 阶段的最前面，排在真正的 `application-prod.yml` 之前。第 9 行因此升到第 10、11 行之上。第 13、14 行没有这个标记，留在普通阶段，于是 jar 内 profile 文件就夹在 remote 和 local 中间：

```
classpath 这一组的内部（高 → 低）

profile 阶段
  第 9 行   Nacos override（被标成 PROFILE_SPECIFIC 后抬上来）
  第 10 行  classpath:/config/application-prod.yml
  第 11 行  classpath:/application-prod.yml
普通阶段
  第 12 行  classpath:/config/application.yml     同组里 /config 高于根目录
  第 13 行  Nacos order-service.yaml              仍是第 15 行的子节点
  第 14 行  Nacos common.yaml
  第 15 行  classpath:/application.yml            写下 import 的父文件
```

jar 外的第 5–8 行是另一组 location（`file:./`）。file 组整组先挂上，所以整组高于 classpath 组里的所有行，包括被抬上去的第 9 行。remote 只在“写下 import 的那一组”里升档，不会升到 jar 外文件之上。

三条 Nacos 要重新挨在一起，就给它们同一个 preference。全是 local，三行都留在第 15 行上面、第 10–12 行下面；全是 remote，三行都抬到第 10 行上面，jar 内 profile 文件不再插在它们中间。本例故意一条 remote、两条 local，就是为了把这次抬升露出来。

### 什么样的叫代码默认值

第 17 行的「代码默认值」专指 `main` 里调用 `SpringApplication.setDefaultProperties` 放进去的那一份。它在 `Environment` 里的名字固定是 `defaultProperties`。启动收尾时 `DefaultPropertiesPropertySource.moveToEnd` 会把它挪到列表最后，所以前面 16 档里任何一档写了同一个键，都盖住它。

本例就是这两行：

```java
defaults.put("order.from", "defaultProperties");
defaults.put("order.app-title", "订单服务");
app.setDefaultProperties(defaults);
```

| 键 | 代码里写的默认值 | 最终值 | 原因 |
|---|---|---|---|
| `order.app-title` | `订单服务` | `订单服务` | 第 1–16 行都没写这个键，查找走到第 17 行才命中 |
| `order.from` | `defaultProperties` | `commandLine` | 第 1 行命令行写了同一个键，第 17 行的值用不上 |

`/actuator/env` 里能看到名叫 `defaultProperties` 的一档，里面同时躺着这两个键。`order.app-title` 旁边没有更高来源的值；`order.from` 旁边会标出真正生效的是 `commandLineArgs`。

下面这些都不是第 17 行：

- yml、Nacos、环境变量、命令行里写的值，是更高的那几档。
- Java 字段上的初值，例如 `@ConfigurationProperties` 里的 `private String appTitle = "未命名";`。这个 `"未命名"` **不会**进入 `Environment`，`/actuator/env` 里也没有它。只有整张表 17 档都没有 `order.app-title` 时，绑定才会留下字段上的 `"未命名"`。本例第 17 行已经提供了 `订单服务`，字段初值同样用不上。Spring Boot 自己的 `server.port = 8080` 就是这种字段初值：本例用 `-Dserver.port=9090` 把它盖掉了，外挂文件里的 `18080` 也盖不住 `-D`。

**本章小结**：代码默认值是程序员在 `main` 里用 `setDefaultProperties` 登记的键值，整张优先级表的最后一档。没有任何配置文件写过的键，才由它生效。

### JVM 启动参数能不能写进 application.yml

本例的启动命令里有三类写法，只有后两类会进 Spring 的优先级表：

```text
java -Xmx512m -XX:+UseG1GC -Dfile.encoding=UTF-8 -Dserver.port=9090 -jar order-service.jar --order.pay-timeout=1s
```

| 写在命令上的 | 谁在读 | 写进 `application.yml` 会怎样 |
|---|---|---|
| `-Xmx512m`、`-XX:+UseG1GC`、`-javaagent` | JVM 在进入 `main` 之前就定下来 | 不会变成堆大小或 GC 参数。Spring 读到 yml 时 JVM 已经启动完了 |
| `-Dfile.encoding=UTF-8`、`-Dserver.port=9090` | JVM 放进系统属性，Spring 把它收成第 2 行 `systemProperties` | yml 里写 `file.encoding: UTF-8` 只是 Environment 里更低的一档，不会执行 `System.setProperty`。直接调用 `System.getProperty("file.encoding")` 的代码看不到 yml 里的值。`environment.getProperty("server.port")` 看得到 yml，但本例的 `-Dserver.port=9090` 更高，yml 和外挂文件里的 `18080` 都盖不住 |
| `--order.pay-timeout=1s` | Spring 自己的第 1 行 `commandLineArgs`，不是 JVM 参数 | 同一个键写在 yml 或 Nacos 里是生效的。命令行、`-D`、环境变量都没写这个键时，就轮到文件。本例的 `order.banner`、`order.create-qps-limit` 就是这样来的 |

## 9.5 每个键最后取到什么

| 键 | 最终值 | 获胜的那一档 | 写过这个键、但没赢的档 |
|---|---|---|---|
| `order.from` | `commandLine` | 1 `commandLineArgs` | 2 到 17 里每一档都写了，全部被盖住 |
| `order.pay-timeout` | `1s` | 1 命令行 `--order.pay-timeout=1s` | 13 `order-service.yaml` 里的 `30s` |
| `server.port` | `9090` | 2 `systemProperties` 的 `-Dserver.port` | 5 外挂 prod 文件里的 `18080` |
| `spring.datasource.password` | `env-secret` | 3 `systemEnvironment` | 没有任何文件写它 |
| `spring.profiles.active` | `prod` | 3 环境变量 `SPRING_PROFILES_ACTIVE` | 无 |
| `order.banner` | `外挂 config 目录的 prod 文件` | 5 | 无。上面四档都没写这个键，所以第 5 档生效 |
| `order.feature-flag` | `from-external-prod` | 6 | 无 |
| `order.cache-ttl` | `15s` | 7 | 无 |
| `order.region` | `external-plain` | 8 | 无 |
| `order.degrade` | `true` | 9 Nacos remote | 无。jar 外四份文件没写它，所以 remote 这份赢 |
| `order.audit-log` | `true` | 10 | 无 |
| `order.max-retry` | `2` | 11 | 无 |
| `order.thread-pool-size` | `16` | 12 jar 内 `config/application.yml` | 无。它高于第 13 行的 Nacos |
| `order.create-qps-limit` | `200` | 13 `order-service.yaml` | 无 |
| `spring.datasource.url` | `jdbc:mysql://mysql.prod.internal:3306/order` | 13 | 无 |
| `spring.datasource.username` | `order_rw` | 13 | 无 |
| `order.log-pattern` | `common-pattern` | 14 `common.yaml` | 无 |
| `spring.application.name` | `order-service` | 15 | 无 |
| `order.instance-id` | 一个 UUID | 字面量写在 15，`${random.uuid}` 展开时命中第 4 档 `random` | 无 |
| `order.legacy-key` | `from-annotation` | 16 `@PropertySource` | 无 |
| `order.app-title` | `订单服务` | 17 `defaultProperties` | 无 |

把第 1 行的命令行拿掉之后，`order.from` 会落到第 2 行 `systemProperties`。再拿掉 `-Dorder.from`，会落到第 3 行，环境变量 `ORDER_FROM`。再拿掉 `ORDER_FROM`，才会落到第 5 行外挂的 `config/application-prod.yml`。Nacos 里的 `nacos-app` 要等第 5 到第 12 行都没有 `order.from` 时才会被读到。`defaultProperties` 里的 `defaultProperties` 这个值，只有前面 16 档全部没写 `order.from` 才出得来。

`order.pay-timeout` 同理：命令行的 `1s` 在，Nacos 的 `30s` 就像没加载。去掉 `--order.pay-timeout=1s` 之后，第 2 到第 12 行都没有这个键，最终才是 Nacos 的 `30s`。

## 9.6 对照时最容易看错的地方

1. **jar 外的普通 `application.yml` 高于 jar 内的 `application-prod.yml`，也高于 `preference=remote` 的 Nacos。** 想用 Nacos 盖住某台机器的外挂文件，做不到。外挂文件就是专门用来压过配置中心的。
2. **`preference=local` 的 Nacos 高不过 jar 内 `classpath:/config/application.yml`。** import 写在根上的 `application.yml`，只高于那一份父文件，不高过同组里更靠前的 `config/` 目录。
3. **`systemEnvironment` 认 `ORDER_FROM`，不认你在 yml 里写的 `ORDER_FROM`。** 下划线等价只发生在名字叫 `systemEnvironment` 的那一档。yml 里要写 `order.from`。
4. **Nacos 在表的中间，不是表的外面。** 第 9、13、14 行就是三份 Nacos 配置。排得比命令行、环境变量、jar 外文件靠下，那些来源没写的键照样由 Nacos 提供，并且可以热更新（9.7）。
5. **`-Xmx`、`-XX:` 不能写进 yml。** `-D` 会进入第 2 行 `systemProperties`；yml 里的同名键不会调用 `System.setProperty`。`--key=value` 是 Spring 的第 1 行，同一个键在 yml 里仍然有效，只是排在命令行后面。详见上面「JVM 启动参数能不能写进 application.yml」。

## 9.7 改 Nacos 为什么还能马上生效

刷新换的是**这一格里的 Map**，不换这一格在表上的位置。`NacosContextRefresher` 收到服务端推送后更新快照并发布事件，Spring Cloud 再 rebind `@ConfigurationProperties`、重建 `@RefreshScope` 的 Bean（7.6）。下一次 `getProperty` 仍从第 1 行走到第 17 行，走到 Nacos 那一行时读到的是新内容。

用本例的两个键看差别：

| 你在 Nacos 控制台做的修改 | 改的是哪一行 | 上面有没有同名键 | 进程里的结果 |
|---|---|---|---|
| `order-service.yaml` 里把 `order.create-qps-limit` 从 `200` 改成 `50` | 第 13 行 | 第 1–12 行都没写这个键 | 新值 `50` 会生效。这就是“改 Nacos 及时生效” |
| 同一份文件里把 `order.pay-timeout` 从 `30s` 改成 `10s` | 第 13 行 | 第 1 行命令行已经是 `1s` | Nacos 那一格里变成了 `10s`，但查找在第 1 行就返回了 `1s`。看起来像没刷新，其实是上面还有值 |
| `order-service-override.yaml` 里把 `order.degrade` 从 `true` 改成 `false` | 第 9 行 | 第 1–8 行没写这个键 | 新值 `false` 会生效 |
| `common.yaml` 里改 `order.log-pattern` | 第 14 行 | 上面没写 | 新值会生效 |

所以要让动态配置真的能被 Nacos 改掉，这个键就不要再出现在比它高的地方：不要写成 `--key=value`，不要写成 `-Dkey=value`，不要放进环境变量，也不要写进 jar 外的 yml，以及（对第 13、14 行来说）不要写进 jar 内比它高的 profile 文件和 `classpath:/config/application.yml`。密码、端口、profile 放在第 1–3 行是故意的，它们本来就不要被 Nacos 热更新盖掉。

还有一种“改了 Nacos 但 Bean 没变”：键确实是 Nacos 赢的，但业务代码用 `@Value` 把值读进了普通单例的字段。那种字段在 Bean 创建时拷走了，刷新不会回去改它。会变的是 `@ConfigurationProperties` 和 `@RefreshScope`（7.6）。

**本章小结**：这个进程的 17 档就是第九章的表。Nacos 是其中的第 9、13、14 行，名字是 `dataId,group`。同名键看表的上面；某一档独有的键，只要上面没人写，就由那一档提供，Nacos 推送后也会换成新值。命令行、`-D`、环境变量是三档固定名字的源，不是三份文件。remote 那条 Nacos 比两条 local 更靠上，但仍然在全部 jar 外文件的下面。

---

## 附录：证据索引

| 结论 | 位置 |
|---|---|
| 下标 0 最高 | `fw:.../MutablePropertySources.java` 103–118 |
| 非 null 短路，null 继续 | `fw:.../PropertySourcesPropertyResolver.java` 73–94 |
| `-D` 高于环境变量 | `fw:.../StandardEnvironment.java` 96–101 |
| Servlet 源更高 | `fw:.../StandardServletEnvironment.java` 109–116 |
| `FOO_BAR` 等价 | `fw:.../SystemEnvironmentPropertySource.java` 93–145 |
| 命令行 `addFirst` | `boot:.../SpringApplication.java` 501–520，收尾 `moveToEnd` 345–346 |
| 随机源紧跟环境变量 | `boot:.../env/RandomValuePropertySource.java` 158–162 |
| JSON 在系统属性前 | `boot:.../support/SpringApplicationJsonEnvironmentPostProcessor.java` 143–165 |
| 配置文件 `addLast` | `boot:.../context/config/ConfigDataEnvironment.java` 343–361 |
| profile 子树先遍历 | `boot:.../ConfigDataEnvironmentContributor.java` 545–586 |
| 候选与多文档倒序 | `ConfigDataImporter.java` 106–126；`ConfigDataEnvironmentContributors.java` 172–175 |
| properties 加载器先于 yaml | `boot:.../META-INF/spring.factories` 6–10，配合 `addFirst`（resolver 188–199） |
| 官方全序 | `boot:.../external-config.adoc` 13–42、114–124、229–237、276–373、468–503 |
| `@PropertySource` 垫底 | `PropertySourceProcessor.java` 136–142；解析时机 `ConfigurationClassParser.java` 314–319 |
| Nacos SPI | `sca2025:.../spring-alibaba-nacos-config/.../META-INF/spring.factories` |
| dataId 不自动加 profile | `NacosConfigDataLocationResolver.java` 138–142、280–290 |
| LOCAL / REMOTE | `NacosConfigDataLoader.java` 125–136；`ConfigPreference.java` 19–40 |
| 刷新转 `RefreshEvent` | `NacosContextRefresher.java` 87–151；starter 内 `NacosConfigRefreshEventListener.java` 42–54 |
| 不要 bootstrap starter | `NacosConfigSpringCloudAutoConfiguration.java` 57–64 |
