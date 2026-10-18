# JDK SPI 机制深度源码解析（写给初学者的机制全景）

> **本文基于的源码**：`D:\soft\jdk\jdk-21\lib\src.zip` 解压后的 `java.base` 模块，版本 **JDK 21**（HotSpot 21.0.x）。核心类 `java.util.ServiceLoader.java` 全文 1852 行，文中所有【源码证据】的文件路径与行号均为对该版本实际读取所得（JDK 25/27 中该类 API 未再变化，读者用更高版本的 IDEA 打开源码按类名 + 方法名定位即可）。
>
> **实测验证**：文中所有行为结论（懒加载、缓存、去重、错误信息、TCCL 查找、模块路径 provides/uses）均在 `D:\tmp\spi-demo` 实测工程中用 JDK 21 实际运行验证过，关键输出以【实测输出】形式摘录。
>
> **阅读约定**：每章"先白话、后源码"——先用一两句人话讲清"这是什么、为什么需要它"，再给出源码证据链（`java.base/java/util/ServiceLoader.java` + 方法名 + 行号 + 代码片段）。行号只对 JDK 21 的 src.zip 精确。
>
> **系列关联**：本文与 [Flow.md](Flow.md)、[Java并发.md](Java并发.md)、[G1.md](G1.md) 同属 D:\ai\jdk 系列；第七章与 [Spring Framework 深度源码解析](../spring/Spring%20Framework.md) 的"扩展机制"一章互为镜像——那章讲"框架留了多少口子"，本章讲"JDK 最初留的那个口子"。

## 如何读这份文档

如果你是初学者，推荐两遍读法：

- **第一遍（建立地图，1 小时）**：只读第一章（总览）每节的白话段、第二章 2.1~2.5（最小用例 + 实测）、各章"小结"。目标是能回答：SPI 解决什么问题？`META-INF/services` 文件里写什么？`ServiceLoader.load()` 之后发生了什么？
- **第二遍（深入源码）**：顺序建议：第三章（类路径查找，SPI 的发动机）→ 第四章（模块系统下的 SPI）→ 第五章（TCCL，最容易讲错的部分）→ 第六章（JDK 自家用法）→ 第七章（主流框架，可按需跳读 Spring Boot 一节）→ 第八章（贯通时间线）。

---

# 一、总览：SPI 的定位、设计哲学与两套注册姿势

## 1.1 一句话定位

**SPI（Service Provider Interface，服务提供者接口）是 JDK 内置的一套"运行时发现实现类"的标准机制**：定义方（往往是 JDK 或某个框架）只提供接口和查找协议，实现方把实现类的名字写进一个约定位置的文本文件里，`java.util.ServiceLoader` 在运行时扫描、读取、实例化这些实现——**定义方和实现方互不 import 对方，唯一的连接点是"接口全限定名"这一个字符串**。

`ServiceLoader` 的 javadoc 开篇就是这个定义（`ServiceLoader.java:61-71`）：

> A **service** is a well-known interface or class for which zero, one, or many service providers exist. A **service provider** (or just **provider**) is a class that implements or subclasses the well-known interface or class. A `ServiceLoader` is an object that locates and loads service providers deployed in the run time environment **at a time of an application's choosing**.

翻译成大白话：

- **service**：一个大家都知道的接口（或抽象类），比如 `java.sql.Driver`、`java.nio.charset.spi.CharsetProvider`；
- **provider**：这个接口的实现类，比如 MySQL 的 `com.mysql.cj.jdbc.Driver`；
- **ServiceLoader**：帮你在运行环境里把所有 provider 找出来、new 出来的"发现引擎"。

它解决的核心问题是**"框架定标准、第三方做实现"的装配问题**：JDBC 规范不可能 import MySQL 的驱动类，SLF4J 不可能 import Logback 的实现类，Spring Boot 不可能在代码里写死你要用哪个数据库连接池——这些"反转"到 jar 包侧的注册，全靠 SPI 这类机制完成。它也是后面一切"约定优于配置"思想的祖宗：**你的代码里没有任何一处引用实现类，实现类却已经躺在容器里了**。

## 1.2 设计哲学：读源码前先记住四句话

1. **约定优于配置，文件即注册表**。类路径下 `META-INF/services/<接口全限定名>` 这个文本文件就是注册表，一行一个实现类名。没有 XML、没有注解处理器、没有代码生成——一个纯文本约定支撑了整个 Java 生态二十年的可插拔性。
2. **懒加载，用的时候才 new**。`ServiceLoader.load()` 只是创建了一个"查找器"，扫描和实例化都被推迟到 `iterator.hasNext()/next()` 或 `stream()` 真正被消费的那一刻（`ServiceLoader.java:299-308` 的 javadoc "Timing of provider discovery" 一节明确写了 "loaded and instantiated lazily, that is, on demand"）。启动时的代价只有一次类引用，没有一次反射调用。
3. **错误 fail-fast 但可恢复**。任何解析、加载、实例化错误都抛 `ServiceConfigurationError`（一个 Error 而不是 Exception）——官方的理由写在 `iterator()` 方法的 `@apiNote` 里（`ServiceLoader.java:1353-1357`）：*格式错误的配置文件和格式错误的 class 文件一样，说明 JVM 的配置出了严重问题，宁可抛 Error 也不静默失败*。但单个提供者失败不影响继续迭代其余提供者。
4. **两套世界，一套 API**。JDK 9 之后同一个 `ServiceLoader` 背后有三条查找路径（模块层、模块目录、类路径），由 `newLookupIterator()` 组合（`ServiceLoader.java:1299-1323`）——调用方无感知。这也是为什么一个 2015 年写的 jar 和一个 2026 年写的模块可以同时被发现。

## 1.3 全景图：四个角色与两套注册姿势

```
┌─────────────────────── 定义方（JDK / 框架 / 你） ───────────────────────┐
│  service 接口：com.example.codec.CodecFactory                           │
│  查找入口：  ServiceLoader.load(CodecFactory.class)                     │
└────────────────────────────────┬────────────────────────────────────────┘
                                 │ 约定 = 接口全限定名字符串
┌────────────────────────────────▼────────────────────────────────────────┐
│                 实现方（任意第三方 jar / 模块，二选一或共存）               │
│                                                                          │
│  姿势A（类路径 jar，1998~今天）            姿势B（JPMS 模块，JDK 9+）     │
│  META-INF/services/                        module-info.java:            │
│  com.example.codec.CodecFactory              provides CodecFactory      │
│  ──────────────────────────────                with StandardCodecs;     │
│  com.example.codec.impl.StandardCodecs                                  │
│  （一行一个实现类，#注释，UTF-8）           使用方 module-info.java 必须  │
│                                              uses CodecFactory;         │
└────────────────────────────────┬────────────────────────────────────────┘
                                 │
┌────────────────────────────────▼────────────────────────────────────────┐
│   java.util.ServiceLoader（发现引擎）                                    │
│   load() → [模块目录查找 + 模块层查找] + [类路径文件查找] → 懒实例化 → 缓存 │
└──────────────────────────────────────────────────────────────────────────┘
```

| 角色 | 谁 | 在哪定义 |
|---|---|---|
| service（服务） | 接口/抽象类，如 `java.sql.Driver` | 定义方 jar |
| provider（提供者） | 实现类，如 MySQL 驱动 | 第三方 jar |
| provider-configuration file（注册文件） | `META-INF/services/java.sql.Driver` | 第三方 jar |
| ServiceLoader（装载器） | `java.util.ServiceLoader` | JDK `java.base` |
| ServiceConfigurationError（错误） | 加载/实例化失败时抛出 | JDK `java.base` |

## 1.4 关键问题 → SPI 方案映射（全文导览）

| 开发中的关键问题 | SPI 的方案 | 详见 |
|---|---|---|
| 框架如何在不 import 的情况下使用第三方实现 | 接口全限定名作为唯一连接点 + 文件/模块声明注册 | 一、二 |
| 注册了 100 个实现，但只想按需实例化一个 | `stream()` 先看 `type()` 再 `get()`，或 `findFirst()` | 二 |
| 驱动、日志、编码器那么多 jar，怎么被自动发现 | `getResources("META-INF/services/接口名")` 扫描所有 classpath jar | 三 |
| jar 里的实现类写错名字/没有构造器，程序会怎样 | `ServiceConfigurationError`，fail-fast，可恢复 | 三 |
| 模块化（JPMS）之后还能用吗 | `provides ... with ...` + `uses ...` 指令，同一套 API | 四 |
| JDBC 驱动为什么 `Class.forName` 都不用写 | `DriverManager` 静态初始化时用 SPI 自动发现 | 五、六 |
| 父加载器写的代码怎么用子加载器里的类 | `load(Class)` 默认用线程上下文类加载器（TCCL） | 五 |
| Spring Boot 自动配置和 SPI 什么关系 | 形似而神不同：`spring.factories`/`.imports` 自研注册表，JDK SPI 仍在底层 | 七 |

## 1.5 版本演进：从 sun.misc.Service 到模块系统

### 1.5.1 版本时间线

| 版本 | 时间 | 关键变化 | 证据 |
|---|---|---|---|
| JDK 1.4 | 2002 | JDBC 4.0 之前的雏形：内部类 `sun.misc.Service`（非公开 API），仅供 JDK 自用 | — |
| **JDK 6（1.6）** | 2006-12 | **`java.util.ServiceLoader` 正式诞生**（作者 Mark Reinhold），同时 JDBC 4.0（JSR-221）规定驱动 jar 必须带 `META-INF/services/java.sql.Driver`，驱动从此免 `Class.forName` | `ServiceLoader.java:387-388`：`@author Mark Reinhold`、`@since 1.6` |
| JDK 8 | 2014-03 | API 无变化；此时只有 `load`/`loadInstalled` 三个入口 + `iterator` 一种消费方式 | — |
| **JDK 9** | 2017-09 | **为 JPMS 模块系统重写**：新增 `stream()`、`Provider`、`findFirst()`、`load(ModuleLayer, Class)`；`provides/uses` 指令接入查找 | `ServiceLoader.java:389`：`@revised 9`；`stream()` `@since 9`（:1451）、`Provider` `@since 9`（:439）、`findFirst()` `@since 9`（:1808）、`load(ModuleLayer,...)` `@since 9`（:1780） |
| JDK 18 | 2022-03 | 新的标准 SPI 落地样例：JEP 418 主机名解析器 `java.net.spi.InetAddressResolverProvider` | `java.base/java/net/spi/InetAddressResolverProvider.java` |
| JDK 21+ | 2023-09~ | API 稳定，未再改动；Security Manager 相关代码路径已随 JEP 411/486 逐步废弃 | `ServiceLoader.java` 中大量 `@SuppressWarnings("removal")` |

### 1.5.2 一条重要的源码化石：sun.misc.Providers 的谢幕

JDK 6 之前 SPI 的查找逻辑是内部 API `sun.misc.Service`，JDK 6 把它"转正"成 `java.util.ServiceLoader` 时，替换现场就留在 JDK 自己的代码里：

【源码证据】`java.sql/java/sql/DriverManager.java:594-602`——注释直言"ServiceLoader.load() 取代了 sun.misc.Providers()"：

```java
            // If the driver is packaged as a Service Provider, load it.
            // Get all the drivers through the classloader
            // exposed as a java.sql.Driver.class service.
            // ServiceLoader.load() replaces the sun.misc.Providers()

            AccessController.doPrivileged(new PrivilegedAction<Void>() {
                public Void run() {

                    ServiceLoader<Driver> loadedDrivers = ServiceLoader.load(Driver.class);
```

这就是 SPI 机制的"出生证明"：**第一个、也是最重要的用户是 JDBC**。第六章会展开这条链路。

### 1.5.3 对初学者的意义：哪些写法过时了，哪些永远有效

- **过时/避免**：依赖"类路径下文件顺序"做优先级（未定义行为，见 3.2）；在命名模块的注册文件里声明提供者（会被静默忽略，见 4.3）；缓存一个全局 `ServiceLoader` 实例跨应用复用（javadoc 明确警告，见 9.1）。
- **永远有效**：接口与实现解耦的思路、`META-INF/services` 文件格式、懒加载语义、`ServiceConfigurationError` 的错误模型——从 JDK 6 到 21+ 一字未改。

## 1.6 全文章节地图

- **第二章 核心 API 与最小用例**：三个 load 入口、四种消费方式（iterator/stream/findFirst/Provider）、缓存与 reload，全部带实测输出。
- **第三章 类路径查找源码**：`LazyClassPathLookupIterator`——文件解析、资源发现、实例化的完整源码走读，SPI 的发动机。
- **第四章 模块系统下的 SPI**：`uses/provides` 指令、三种查找迭代器的组合、`provider()` 静态工厂。
- **第五章 线程上下文类加载器**：SPI 如何"突破"双亲委派，JDBC/Tomcat 的经典结构。
- **第六章 JDK 自家怎么用 SPI**：java.base 的 36 个 `uses`、Charset、SelectorProvider、DriverManager、InetAddressResolverProvider。
- **第七章 主流框架与 SPI**：Spring Framework 的直接使用、Spring Boot 的 `spring.factories` 与 `AutoConfiguration.imports`、Servlet 容器、SLF4J/Jackson、Sentinel/Seata/Nacos/Dubbo 的自研 SPI，以及一张大对比表。
- **第八章 贯通视图**：一次 `ServiceLoader.load` 的完整时间线。
- **第九章 附录**：最佳实践与坑、API 速查表、实测工程说明。


---

# 二、核心 API 与最小用例

> 本章对应源码：`java.base/java/util/ServiceLoader.java`（1852 行）。以下所有行为均已在 `D:\tmp\spi-demo` 实测工程中用 JDK 21 验证。

## 2.1 先跑通一个最小例子

**（白话）** 假设我们定义一个编码器工厂接口 `CodecFactory`，一个实现类 `StandardCodecs`。分三步：

```java
// ① 定义方：服务接口（可以和实现不在同一个 jar）
package com.example.codec;
public interface CodecFactory {
    String name();
}
```

```java
// ② 实现方：提供者类（必须是 public，且有 public 无参构造器）
package com.example.codec.impl;
public class StandardCodecs implements CodecFactory {
    public String name() { return "standard"; }
}
```

```properties
# ③ 实现方：注册文件，路径必须是 META-INF/services/com.example.codec.CodecFactory
#    （文件名 = 接口全限定名，内容一行一个实现类全限定名）
com.example.codec.impl.StandardCodecs
```

```java
// ④ 使用方：两行代码，拿到所有实现
ServiceLoader<CodecFactory> loader = ServiceLoader.load(CodecFactory.class);
for (CodecFactory factory : loader) {   // 增强for会自动调用iterator()
    factory.name();                     // 此刻 StandardCodecs 才被实例化
}
```

**整个过程，使用方的代码没有出现过 `StandardCodecs` 这个类名**——这就是 SPI 的价值：使用方与实现方在编译期零依赖。把它规模化：同一个接口下放 10 个 jar，每个 jar 各注册各的实现，`for` 循环就把它们全收进来了。

## 2.2 四种消费方式：iterator / stream / findFirst / Provider

`ServiceLoader` 实现 `Iterable<S>`，但 JDK 9 后推荐按需选择消费方式：

| 消费方式 | 签名 | 何时用 | 版本 |
|---|---|---|---|
| `iterator()` | `Iterator<S>` | 想拿到**已实例化**的对象；配合增强 for | 1.6 |
| `stream()` | `Stream<Provider<S>>` | 想**先看类型再决定实例化**（按注解过滤、按类名排序） | 9 |
| `findFirst()` | `Optional<S>` | 只需要第一个可用实现，没有则给默认值 | 9 |
| `Provider` | `interface Provider<S> extends Supplier<S>` | stream 的元素类型；`type()` 拿 Class 不实例化，`get()` 才实例化 | 9 |

`Provider` 接口本身就是"类型与实例分离"的设计（`ServiceLoader.java:441-468`）：

【源码证据】`java.base/java/util/ServiceLoader.java:441-467`

```java
    public static interface Provider<S> extends Supplier<S> {
        Class<? extends S> type();   // 提供者类型，不触发实例化
        @Override S get();           // 真正实例化（工厂方法或构造器）
    }
```

官方 javadoc 给的标准用法（`ServiceLoader.java:131-138`）：用 stream 先按注解过滤、再实例化：

```java
    ServiceLoader<CodecFactory> loader = ServiceLoader.load(CodecFactory.class);
    Set<CodecFactory> pngFactories = loader
           .stream()                                              // 元素是 Provider<CodecFactory>
           .filter(p -> p.type().isAnnotationPresent(PNG.class))  // 只检查类型，未实例化
           .map(Provider::get)                                    // 此刻才实例化
           .collect(Collectors.toSet());
```

## 2.3 三个静态入口：load / loadInstalled / load(ModuleLayer)

| 入口 | 查找范围 | 类加载器 | 何时用 |
|---|---|---|---|
| `load(S)` | 全部可用提供者 | **线程上下文类加载器**（TCCL） | 默认选择（框架代码尤其如此，见第五章） |
| `load(S, ClassLoader)` | 该加载器及其父链可见的提供者 | 指定加载器 | 容器明确知道用哪个加载器时 |
| `loadInstalled(S)` | 仅"已安装"的提供者（平台加载器） | `getPlatformClassLoader()` | 只想发现 JVM 安装级的扩展，忽略应用类路径 |
| `load(ModuleLayer, S)` | 指定模块层及其父层，**不含类路径** | （模块层内各模块的加载器） | 多层模块场景（容器动态建层） |

【源码证据】`java.base/java/util/ServiceLoader.java:1695-1699`——无参 load 用的是 **TCCL**，这是第五章的主角：

```java
    @CallerSensitive
    public static <S> ServiceLoader<S> load(Class<S> service) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return new ServiceLoader<>(Reflection.getCallerClass(), service, cl);
    }
```

【源码证据】`java.base/java/util/ServiceLoader.java:1730-1734`——loadInstalled 把查找范围收窄到平台类加载器：

```java
    @CallerSensitive
    public static <S> ServiceLoader<S> loadInstalled(Class<S> service) {
        ClassLoader cl = ClassLoader.getPlatformClassLoader();
        return new ServiceLoader<>(Reflection.getCallerClass(), service, cl);
    }
```

> **决策表：该调哪个 load？**
> - 写框架/库代码，跑在别人容器里 → `load(S)`（用调用线程的 TCCL）
> - 自己的应用，加载器结构自己说了算 → `load(S, 本类的getClassLoader())` 更可控
> - 只想看 JDK/平台安装的扩展 → `loadInstalled(S)`
> - 模块化容器（OSGi 之外的自建层） → `load(ModuleLayer, S)`

## 2.4 缓存与 reload：实例只 new 一次（直到你要求重来）

**（白话）** `ServiceLoader` 对象内部维护两个缓存列表：`iterator` 路线的 `instantiatedProviders` 和 `stream` 路线的 `loadedProviders`。第一次迭代时逐个实例化并加入缓存；**之后再调用 `iterator()`，先吐缓存、缓存吐完才继续懒加载剩下的**。想清空重来，调用 `reload()`。

【源码证据】`java.base/java/util/ServiceLoader.java:395-423`——两个路线的缓存字段与 reload 计数：

```java
    // The lazy-lookup iterator for iterator operations
    private Iterator<Provider<S>> lookupIterator1;
    private final List<S> instantiatedProviders = new ArrayList<>();

    // The lazy-lookup iterator for stream operations
    private Iterator<Provider<S>> lookupIterator2;
    private final List<Provider<S>> loadedProviders = new ArrayList<>();
    private boolean loadedAllProviders; // true when all providers loaded

    // Incremented when reload is called
    private int reloadCount;
```

【源码证据】`java.base/java/util/ServiceLoader.java:1397-1408`——`iterator()` 迭代逻辑：先吃缓存（`index < instantiatedProviders.size()`），吃完了才向 lookupIterator 要新的并放回缓存：

```java
            @Override
            public S next() {
                checkReloadCount();
                S next;
                if (index < instantiatedProviders.size()) {
                    next = instantiatedProviders.get(index);   // ① 走缓存
                } else {
                    next = lookupIterator1.next().get();       // ② 懒实例化
                    instantiatedProviders.add(next);           // ③ 放回缓存
                }
                index++;
                return next;
            }
```

注意 `checkReloadCount()`（:1383-1386）：如果 `reload()` 被调用过，旧 iterator 的 `expectedReloadCount` 对不上，直接抛 `ConcurrentModificationException`——**reload 之后旧迭代器全部作废**，这是强制约定而不是建议。

【源码证据】`java.base/java/util/ServiceLoader.java:1831-1841`——reload 清空所有状态并递增计数：

```java
    public void reload() {
        lookupIterator1 = null;
        instantiatedProviders.clear();

        lookupIterator2 = null;
        loadedProviders.clear();
        loadedAllProviders = false;

        // increment count to allow CME be thrown
        reloadCount++;
    }
```

## 2.5 实测：懒加载、缓存、去重一次看清

实测工程 `D:\tmp\spi-demo`：`providers/std/META-INF/services/com.example.codec.CodecFactory` 内容如下（故意写注释、空行、重复声明）：

```properties
# 注释行：标准编解码器
com.example.codec.impl.StandardCodecs

# 再来一个重复声明（应被去重）
com.example.codec.impl.StandardCodecs
```

【实测输出】（JDK 21 运行 `com.example.app.Main`，节选）：

```text
=== 场景1: iterator + stream + findFirst + Provider.type() ===
--- loader 已创建，观察下面还没有构造器日志（懒加载） ---
  stream() 发现提供者类型: com.example.codec.impl.StandardCodecs（尚未实例化）
--- 现在真正 get()/迭代，才会实例化 ---
  [trace] StandardCodecs 构造器执行
  iterator 返回: standard @7ef20235
--- 第二次迭代：走缓存，不再有构造器日志，实例相同 ---
  iterator 返回: standard @7ef20235          ← 同一个对象（hashCode 相同）
--- findFirst: com.example.codec.impl.StandardCodecs@7ef20235

=== 场景2: 重复迭代 + reload() ===
--- reload 后重新迭代（构造器日志再次出现） ---
  [trace] StandardCodecs 构造器执行
  standard
```

五个结论一次验证完毕：

1. `ServiceLoader.load()` 创建后**什么都没做**——`stream()` 能报出提供者类型，但构造器没执行（懒加载）；
2. 文件里的**注释行、空行被忽略，重复声明被去重**（stream 只报出一个类型）；
3. **多次迭代拿到的是同一个实例**（`@7ef20235` 不变）——每个 ServiceLoader 对象内缓存；
4. **`reload()` 之后重新实例化**——想换实现/重置状态时用它；
5. `findFirst()` 复用 iterator 缓存，拿到的是同一个对象。

> 另外两个重要行为也实测确认：**`ServiceLoader` 实例不是线程安全的**（javadoc `ServiceLoader.java:376-377`："Instances of this class are not safe for use by multiple concurrent threads"），多线程各用各的 loader 或外部同步；**单例语义要自己保证**——同一个接口、同一个类加载器下，不同 ServiceLoader 实例会各自实例化一份。

## 2.6 本章小结

- SPI 的使用面（API 层）只有三个静态入口、四种消费方式、一个 reload——全部行为围绕"懒加载 + 缓存"。
- 无参 `load(S)` 用 TCCL、`load(S, ClassLoader)` 显式指定、`loadInstalled` 收窄到平台层、`load(ModuleLayer, S)` 面向模块层；这是后面第五章的伏笔。
- `Provider` 把"类型"与"实例"分开，使 stream 成为大提供者集合下的推荐入口。
- 实测确认：注释/空行/重复的文件语义、实例缓存、reload 作废旧迭代器（CME）。


---

# 三、类路径查找源码：LazyClassPathLookupIterator

> 本章对应源码：`java.base/java/util/ServiceLoader.java:1116-1294`。这是 SPI 的"发动机"——绝大多数场景（普通 classpath 应用、Spring Boot、Tomcat）都走这条路。

## 3.1 文件从哪来、怎么读：parse 与 parseLine

**（白话）** 类路径查找的全部秘密浓缩成一个常量：

【源码证据】`java.base/java/util/ServiceLoader.java:1119`

```java
        static final String PREFIX = "META-INF/services/";
```

查找时拼上接口全限定名（`PREFIX + service.getName()`），用 `ClassLoader.getResources()` 一次性拿到**所有 jar 里同名文件**的 URL 枚举，然后逐个解析。

【源码证据】`java.base/java/util/ServiceLoader.java:1188-1222`——`nextProviderClass()`：首次调用时定位所有配置文件，之后逐行吐出实现类名并用 `Class.forName(cn, false, loader)` 加载（注意 `false`：**只加载不初始化**，连静态块都不执行，把初始化推迟到真正 `get()`）：

```java
        private Class<?> nextProviderClass() {
            if (configs == null) {
                try {
                    String fullName = PREFIX + service.getName();
                    if (loader == null) {
                        configs = ClassLoader.getSystemResources(fullName);
                    } else if (loader == ClassLoaders.platformClassLoader()) {
                        // The platform classloader doesn't have a class path,
                        // but the boot loader might.
                        if (BootLoader.hasClassPath()) {
                            configs = BootLoader.findResources(fullName);
                        } else {
                            configs = Collections.emptyEnumeration();
                        }
                    } else {
                        configs = loader.getResources(fullName);   // ← 一行扫全部jar
                    }
                } catch (IOException x) {
                    fail(service, "Error locating configuration files", x);
                }
            }
            while ((pending == null) || !pending.hasNext()) {
                if (!configs.hasMoreElements()) {
                    return null;
                }
                pending = parse(configs.nextElement());            // ← 解析下一个文件
            }
            String cn = pending.next();
            try {
                return Class.forName(cn, false, loader);           // ← 只加载不初始化
            } catch (ClassNotFoundException x) {
                fail(service, "Provider " + cn + " not found");
                return null;
            }
        }
```

文件内容的解析规则全部在 `parseLine` 里，值得逐行读：

【源码证据】`java.base/java/util/ServiceLoader.java:1134-1162`

```java
        private int parseLine(URL u, BufferedReader r, int lc, Set<String> names)
            throws IOException
        {
            String ln = r.readLine();
            if (ln == null) {
                return -1;
            }
            int ci = ln.indexOf('#');
            if (ci >= 0) ln = ln.substring(0, ci);   // ① # 之后全是注释
            ln = ln.trim();                           // ② 前后空白忽略
            int n = ln.length();
            if (n != 0) {                             // ③ 空行忽略
                if ((ln.indexOf(' ') >= 0) || (ln.indexOf('\t') >= 0))
                    fail(service, u, lc, "Illegal configuration-file syntax");  // ④ 行内不许有空格
                int cp = ln.codePointAt(0);
                if (!Character.isJavaIdentifierStart(cp))
                    fail(service, u, lc, "Illegal provider-class name: " + ln); // ⑤ 必须是合法Java标识符
                int start = Character.charCount(cp);
                for (int i = start; i < n; i += Character.charCount(cp)) {
                    cp = ln.codePointAt(i);
                    if (!Character.isJavaIdentifierPart(cp) && (cp != '.'))
                        fail(service, u, lc, "Illegal provider-class name: " + ln); // ⑥ 允许字母数字下划线$和.
                }
                if (providerNames.add(ln)) {          // ⑦ 跨文件去重
                    names.add(ln);
                }
            }
            return lc + 1;
        }
```

**注册文件格式速查表**（所有规则均来自上面的源码 + javadoc `ServiceLoader.java:280-288`）：

| 规则 | 说明 | 源码位置 |
|---|---|---|
| 文件名 | `META-INF/services/<接口全限定名>` | :1191 |
| 编码 | 必须 UTF-8 | :1174（`UTF_8.INSTANCE`） |
| 一行一实现 | 全限定类名 | :1157 |
| 注释 | `#` 起始，行内任意位置生效 | :1141-1142 |
| 空白 | 行前后空格/tab 忽略、空行忽略 | :1143-1145 |
| 非法语法 | 行中间有空格/tab 直接抛 Error | :1146-1147 |
| 类名校验 | 必须是合法 Java 标识符 + 点号 | :1148-1155 |
| 去重 | 同文件内、跨文件、跨 jar 都去重（`providerNames` 是迭代器实例级的 HashSet） | :1157-1159、:1121 |

## 3.2 顺序问题：谁先谁后？

**（白话）** 类路径模式下提供者的遍历顺序是"配置文件的发现顺序 + 文件内的行序"。`getResources` 的返回顺序取决于类路径条目顺序（jar 在 classpath 里的排列），**没有"优先级"这种正式语义**——javadoc 明确写了 "The ordering ... is based on the order that the class loader's `getResources` method finds the service configuration files and within that, the order that the class names are listed in the file"（:1597-1599）。

【源码证据】`java.base/java/util/ServiceLoader.java:1225-1251`——`hasNextService()` 里还有一个容易踩的坑：**命名模块里声明的提供者会被类路径迭代器静默跳过**（防止模块与文件双注册导致的重复）：

```java
        @SuppressWarnings("unchecked")
        private boolean hasNextService() {
            while (nextProvider == null && nextError == null) {
                try {
                    Class<?> clazz = nextProviderClass();
                    if (clazz == null)
                        return false;

                    if (clazz.getModule().isNamed()) {
                        // ignore class if in named module
                        continue;                      // ← 命名模块的类走模块查找，这里跳过
                    }

                    if (service.isAssignableFrom(clazz)) {
                        Class<? extends S> type = (Class<? extends S>) clazz;
                        Constructor<? extends S> ctor
                            = (Constructor<? extends S>)getConstructor(clazz);
                        ProviderImpl<S> p = new ProviderImpl<S>(service, type, ctor, acc);
                        nextProvider = (ProviderImpl<T>) p;   // ← 此处只是包了个 Provider，未实例化
                    } else {
                        fail(service, clazz.getName() + " not a subtype");
                    }
                } catch (ServiceConfigurationError e) {
                    nextError = e;                     // ← 错误先存起来，下次 next() 再抛
                }
            }
            return true;
        }
```

> 两个值得停一停的细节：**① 实例化再次被推迟**——`hasNextService` 只完成了"类加载 + 构造器查找 + 包 Provider"，`ctor.newInstance()` 发生在 `next().get()` 时；**② 错误是"记账制"**——`nextError` 先记下，等 `next()` 被调用时再抛出，这样即便某个提供者坏了，它之前的提供者已经安全交付。

## 3.3 实例化的两条路：provider() 静态工厂 vs 无参构造器

**（白话）** 类路径模式只认一条路：public 无参构造器（:1237-1240 的 `getConstructor` + `isAssignableFrom` 校验）。"静态工厂"这条路只对**命名模块**开放——这是 JDK 9 特意设计的：模块可以把实现类藏得严严实实，用一个公开的静态工厂方法代替构造器（第四章展开）。

【源码证据】`java.base/java/util/ServiceLoader.java:724-731`——`ProviderImpl.get()` 的分流：

```java
        @Override
        public S get() {
            if (factoryMethod != null) {
                return invokeFactoryMethod();   // 路线A：public static provider()
            } else {
                return newInstance();           // 路线B：public 无参构造器
            }
        }
```

两条路线的异常处理都收敛到 `fail()`：工厂方法返回 `null` 也算失败（:770-772 `fail(service, factoryMethod + " returned null")`），构造器抛出的异常会被解包（`InvocationTargetException.getCause()`）挂到 `ServiceConfigurationError` 的 cause 上（:809-815）——**报错时永远看 cause 链，那是真正的现场**。

## 3.4 错误体系：ServiceConfigurationError

**（白话）** SPI 的所有失败都是同一个 Error：`java.util.ServiceConfigurationError`。它和 `NoSuchMethodError`、`LinkageError` 是一个家族——JVM 认为"配置文件坏了"和"字节码坏了"一样严重，属于**环境性问题**，不应该被业务代码日常捕获后继续跑。

【源码证据】`java.base/java/util/ServiceConfigurationError.java:38-40`

```java
public class ServiceConfigurationError
    extends Error
```

三大触发场景（都在 `D:\tmp\spi-demo` 实测复现）：

| 场景 | 错误信息（实测原文） | 源码触发点 |
|---|---|---|
| 注册的类不存在 | `Provider com.example.codec.impl.GhostCodecs not found` | :1218-1220 |
| 不是服务的子类型 | `com.example.codec.impl.ExtendedCodecsFactory not a subtype` | :1244 |
| 缺 public 无参构造器 | `...PrivateCtorCodecs Unable to get public no-arg constructor` | :679 |
| 模块未声明 `uses` | `module com.example.appbad does not declare \`uses\`` | :579 |

【实测输出】（错误场景 + "fail-fast 但可恢复"验证）：

```text
=== 场景3: ServiceConfigurationError 三连 ===
--- 无 public 无参构造器 (providers/hidden) ---
  捕获 ServiceConfigurationError: com.example.codec.CodecFactory: com.example.codec.impl.PrivateCtorCodecs Unable to get public no-arg constructor
--- 类路径下'工厂提供者'（不实现接口） (providers/ext) ---
  捕获 ServiceConfigurationError: com.example.codec.CodecFactory: com.example.codec.impl.ExtendedCodecsFactory not a subtype
--- broken 文件里 GhostCodecs 在前、StandardCodecs 在后 ---
  [trace] StandardCodecs 构造器执行
  拿到: standard                                   ← 坏提供者之前的先交付
  捕获 ServiceConfigurationError: com.example.codec.CodecFactory: Provider com.example.codec.impl.GhostCodecs not found
```

> **实践含义**：`DriverManager` 正是靠"捕获后吞掉、继续迭代"的写法来容忍个别坏驱动 jar 的（`java.sql/java/sql/DriverManager.java:617-623`，第六章）。你的框架如果也想要这种健壮性，就别用 `forEach` 一把梭，改成手动 `while (it.hasNext()) { try { ... } catch (ServiceConfigurationError e) { log; } }` 的循环——但要注意 javadoc 的告诫（:1335-1337）：出错后继续迭代是"best effort"，无恢复保证。

## 3.5 本章小结

- 类路径 SPI = 一个常量（`META-INF/services/`）+ `getResources` 扫描 + 一个严格解析器 + 懒实例化的 Provider 包装。
- 文件格式规则全部有源码出处，其中最常踩的是：**编码必须 UTF-8**、**行内不能有空格**、**类名必须是合法标识符**。
- 类路径模式只支持无参构造器实例化；静态工厂是模块模式的专利。
- `ServiceConfigurationError` 是 Error 不是 Exception：配置损坏 = 环境损坏。单个提供者失败不影响之前的交付，"记账式"错误处理支撑了 DriverManager 式的容错。


---

# 四、模块系统下的 SPI：uses / provides 与 ServicesCatalog

> 本章对应源码：`java.base/java/util/ServiceLoader.java` 的模块查找部分 + `D:\tmp\spi-demo\modules` 实测工程。

## 4.1 从"文件"到"声明"：JPMS 给 SPI 换了登记处

**（白话）** JDK 9 之前，提供者登记在 `META-INF/services` 文件里——一个运行时约定。JDK 9 之后，模块可以直接在 `module-info.java` 里**编译期声明**：

```java
// 提供方模块
module com.example.codec.provider {
    requires com.example.codec;
    provides com.example.codec.CodecFactory with com.example.codec.impl.StandardCodecs;
}

// 使用方模块：必须声明 uses，否则 ServiceLoader.load 直接抛错
module com.example.app {
    requires com.example.codec;
    uses com.example.codec.CodecFactory;
}
```

这个变化带来三个实质好处：

1. **编译期可校验**：`provides ... with ...` 要求实现类确实实现（或工厂方法确实返回）服务类型，写错编译不过，而不是运行时抛 Error；
2. **不需要打开包**：`provides` 之后，实现类所在的包可以完全不导出（强封装），`ServiceLoader` 通过模块内部反射拿到它（`getConstructor` 里专门为显式模块 `setAccessible(true)`，:667-668）；
3. **查找更快更准**：模块系统在解析阶段就把每个模块的 `provides` 登记进 `ServicesCatalog`（一个"接口名 → 提供者"的注册表），查找时直接查表，而不用扫 jar 找文件。

javadoc 对两种部署方式的官方说法（`ServiceLoader.java:178-190`）：提供者既可以是模块（部署在模块路径），也可以是普通 jar（部署在类路径），**使用方完全无感知**——这正是 `newLookupIterator` 把两套查找拼起来的原因。

## 4.2 uses 检查：load() 的第一道门

**（白话）** 在命名模块里调用 `ServiceLoader.load` 时，JDK 会先检查"你的模块声明了 uses 吗"——没声明就抛 `ServiceConfigurationError`。这不是找麻烦，而是模块系统需要知道"哪些模块会反射加载哪些服务"，才能在编译期校验、在查找时建立可读性。

【源码证据】`java.base/java/util/ServiceLoader.java:564-581`——`checkCaller`，两步检查：可访问性 + uses 声明：

```java
    private static void checkCaller(Class<?> caller, Class<?> svc) {
        if (caller == null) {
            fail(svc, "no caller to check if it declares `uses`");
        }

        // Check access to the service type
        Module callerModule = caller.getModule();
        int mods = svc.getModifiers();
        if (!Reflection.verifyMemberAccess(caller, svc, null, mods)) {
            fail(svc, "service type not accessible to " + callerModule);
        }

        // If the caller is in a named module then it should "uses" the
        // service type
        if (!callerModule.canUse(svc)) {
            fail(svc, callerModule + " does not declare `uses`");    // ← 实测命中的就是这个
        }
    }
```

注意 `load(Class)` 是 `@CallerSensitive` 的（:1695），`Reflection.getCallerClass()` 拿到的就是真实调用者——**写一个"公共工具方法"包装 `ServiceLoader.load` 转发给业务代码时，检查的是工具方法的模块，不是业务的**，模块化封装时留意。

【实测输出】（`D:\tmp\spi-demo\modules`，`com.example.appbad` 模块缺 `uses` 指令）：

```text
模块声明了 requires 但【没有】uses，尝试 load：
Exception in thread "main" java.util.ServiceConfigurationError: com.example.codec.CodecFactory: module com.example.appbad does not declare `uses`
	at java.base/java.util.ServiceLoader.fail(ServiceLoader.java:593)
	at java.base/java.util.ServiceLoader.checkCaller(ServiceLoader.java:579)
	at java.base/java.util.ServiceLoader.<init>(ServiceLoader.java:507)
	at java.base/java.util.ServiceLoader.load(ServiceLoader.java:1698)
```

堆栈与源码行号一一对应：`load(:1698)` → 构造器（:507）→ `checkCaller(:579)` → `fail(:593)`。

## 4.3 三种查找迭代器与 newLookupIterator 的组合

**（白话）** `ServiceLoader` 内部有三个"查找迭代器"，各自负责一个来源，最后由工厂方法拼装：

| 迭代器 | 源码范围 | 负责的来源 | 数据从哪来 |
|---|---|---|---|
| `LayerLookupIterator` | :914-983 | `load(ModuleLayer, S)` 专用 | 深度优先遍历模块层，查每层的 `ServicesCatalog` |
| `ModuleServicesLookupIterator` | :990-1109 | 类加载器入口的**第一步**：命名模块 | 沿类加载器父链逐层查 `ServicesCatalog` |
| `LazyClassPathLookupIterator` | :1116-1294 | 类加载器入口的**第二步**：类路径 | `getResources("META-INF/services/...")`（第三章） |

【源码证据】`java.base/java/util/ServiceLoader.java:1299-1323`——组合逻辑：模块层入口只走 Layer；类加载器入口走"命名模块优先、类路径兜底"的两段式：

```java
    private Iterator<Provider<S>> newLookupIterator() {
        assert layer == null || loader == null;
        if (layer != null) {
            return new LayerLookupIterator<>();
        } else {
            Iterator<Provider<S>> first = new ModuleServicesLookupIterator<>();
            Iterator<Provider<S>> second = new LazyClassPathLookupIterator<>();
            return new Iterator<Provider<S>>() {
                @Override
                public boolean hasNext() {
                    return (first.hasNext() || second.hasNext());
                }
                ...
```

`ModuleServicesLookupIterator` 的查找范围值得细看（`iteratorFor`，:1032-1065）：它先查**本类加载器定义的模块**（:1038 `ServicesCatalog.getServicesCatalogOrNull(loader)`，启动类加载器则查 `BootLoader.getServicesCatalog()`，:1036），然后沿 `getParent()` 父链逐层向上（`hasNext` 里的 :1071-1077）。顺序规则（javadoc :1575-1582）：**先本加载器的模块，再父加载器的模块，一路到启动加载器；类路径提供者排在所有命名模块之后**。

而 `LazyClassPathLookupIterator.hasNextService`（:1232-1235）会把注册文件里指向**命名模块类**的条目静默忽略——避免"同一个提供者既有 provides 声明又有文件注册"造成重复（javadoc :1601-1606）。

> **实测确认 split package**：把两个提供者模块写进同一个包 `com.example.codec.impl` 再同时放到模块路径，启动即报 `LayerInstantiationException: Package com.example.codec.impl in both module ... and module ...`——JPMS 禁止拆分包，这是模块化 SPI 实战中改包名时的常见事故。

## 4.4 命名模块的特权：provider() 静态工厂

**（白话）** 模块模式允许提供者类**不实现服务接口**，改由一个 `public static provider()` 方法返回实例——适合"实例化昂贵/需要读配置才能构造"的场景。这条路径在 `loadProvider` 里，且只对显式模块开放。

【源码证据】`java.base/java/util/ServiceLoader.java:848-908`——`loadProvider(ServiceProvider)`：先确认模块可读服务模块（:850），加载类（:859），然后**显式模块才查工厂方法**（:884-896），查不到才回退"必须是子类型 + 无参构造器"（:899-907）：

```java
        // if provider in explicit module then check for static factory method
        if (inExplicitModule(clazz)) {
            Method factoryMethod = findStaticProviderMethod(clazz);
            if (factoryMethod != null) {
                Class<?> returnType = factoryMethod.getReturnType();
                if (!service.isAssignableFrom(returnType)) {
                    fail(service, factoryMethod + " return type not a subtype");
                }
                ...
                return new ProviderImpl<S>(service, type, factoryMethod, acc);
            }
        }

        // no factory method so must be a subtype
        if (!service.isAssignableFrom(clazz)) {
            fail(service, clazz.getName() + " not a subtype");
        }
```

工厂方法的判定条件写在 javadoc（:209-213）：**public + static + 方法名固定 `provider` + 无参 + 返回类型可赋给服务接口**，且一个类最多一个（`findStaticProviderMethod` 查重，:636-639）。

【实测输出】（模块路径运行 `com.example.app/com.example.app.ModMain`，工厂模块 + 构造器模块并存）：

```text
  发现提供者: type=com.example.codec.CodecFactory 来自模块 com.example.codec
  发现提供者: type=com.example.codec.impl.StandardCodecs 来自模块 com.example.codec.provider
  [trace] ExtendedCodecsFactory.provider() 静态工厂执行
  实例化: extended
  [trace] StandardCodecs 构造器执行
  实例化: standard
```

两个信息量很大的细节：

1. 工厂模块的 `Provider.type()` 返回的是**工厂方法的返回类型**（`CodecFactory` 本身），不是提供者类——`ExtendedCodecsFactory` 这个类名从头到尾没有暴露（javadoc :447-449 说的就是这个）；
2. 实例化顺序 extended 在前 standard 在后——**同一类加载器内多个模块的顺序未定义**（javadoc :1581-1582），别依赖。

## 4.5 类路径 vs 模块路径：一张对照表

| 维度 | 类路径 jar（第三章） | 命名模块（本章） |
|---|---|---|
| 注册方式 | `META-INF/services/<接口名>` 文件 | `module-info.java` 的 `provides ... with ...` |
| 使用方义务 | 无 | 必须声明 `uses` |
| 实例化方式 | 仅 public 无参构造器 | 优先 public static `provider()` 工厂，回退构造器 |
| `Provider.type()` | 提供者类本身 | 工厂提供者返回工厂返回类型 |
| 文件里注册模块里的类 | 被静默忽略（:1232-1235） | — |
| 错误时机 | 运行时（Error） | 编译期即可校验 |
| 实现类封装 | 类必须 public、包必须可见 | 实现类所在包可不导出 |

## 4.6 本章小结

- 模块化没有杀死文件式 SPI，而是与之共存：`newLookupIterator` 组合三条查找路径，老 jar 照常工作。
- `uses/provides` 把"运行时约定"升级为"编译期契约"：`uses` 缺失在 `load()` 第一时间报错，`provides` 写错编译不过。
- `provider()` 静态工厂是模块模式的特权，配合强封装可以完全隐藏实现类。
- 顺序语义在模块世界里进一步弱化（模块间顺序未定义），依赖顺序的逻辑应当显式用 `stream().sorted(...)`。


---

# 五、线程上下文类加载器：SPI 如何"突破"双亲委派

> 本章是 SPI 相关话题里"面试浓度"最高的一章，也是最容易讲错的一章。所有结论在 `D:\tmp\spi-demo` 实测。

## 5.1 问题：父加载器写的代码，用不了子加载器里的类

**（白话）** 双亲委派的规则是"子先问父、父能加载就不让子碰"。这条规则对 SPI 是个死结：

```text
启动类加载器（bootstrap）：JDK 核心类，如 java.sql.DriverManager
        ▲ 双亲委派：父看不见子的类
应用类加载器（application）：classpath 上的 mysql-connector-j.jar
        ▲
自定义类加载器（如 Tomcat webapp）：各应用的驱动 jar
```

`DriverManager` 在 `java.sql` 模块里，由平台类加载器加载；而 MySQL 驱动 jar 在应用类路径上。按双亲委派，`DriverManager` 的代码想 `Class.forName("com.mysql.cj.jdbc.Driver")` 时，**用它自己的加载器根本看不见驱动类**。

**解法**：给每个线程挂一个"上下文类加载器"（Thread Context Class Loader, TCCL）。父加载器（JDK/框架）写的代码在需要发现实现时，**不用自己的加载器，而是借线程身上挂的这个加载器去 `getResources`/`Class.forName`**——相当于子加载器把能力"倒灌"给父加载器。这就是"SPI 突破双亲委派"的完整含义：**委派方向没有变，变的是 SPI 代码主动使用了 TCCL 而非自身加载器**。

## 5.2 源码：load(Class) 默认取 TCCL

【源码证据】`java.base/java/util/ServiceLoader.java:1695-1699`（2.3 节已引用，这里看它的含义）：

```java
    @CallerSensitive
    public static <S> ServiceLoader<S> load(Class<S> service) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return new ServiceLoader<>(Reflection.getCallerClass(), service, cl);
    }
```

三个要点：

1. **TCCL 是唯一入口的默认类加载器**——这就是为什么 JDBC 驱动、SLF4J 实现、Servlet 容器里的 Spring 全都能被"父加载器代码"发现；
2. **TCCL 是线程属性**：主线程默认是应用类加载器；Tomcat 为每个 webapp 的线程设置各自的 webapp 加载器——同一个 `load(S)` 调用在不同线程能发现不同应用的服务；
3. **javadoc 的警告**（:1673-1679）：无参 `load` 得到的 loader **不要 VM 级缓存**——同一 JVM 里不同应用 TCCL 不同，缓存会串服务和泄漏内存。

顺带一个对称的知识点：TCCL 从哪来？`Thread.getContextClassLoader()` 的默认值在类加载器创建线程时继承，最终源头是 `ClassLoader.getSystemClassLoader()`——所以普通 main 方法里 `load(S)` 等价于 `load(S, 应用类加载器)`，平时察觉不到 TCCL 的存在；**只有容器（Tomcat/OSGi/应用服务器）和框架（JNDI、连接池）操纵 TCCL 时它才登场**。

## 5.3 实测：TCCL 真的"倒灌"了吗

实测设计：实现类 `StandardCodecs.class` **只**存在于 `providers/tccl` 目录（应用 classpath 上没有），TCCL 设置为一个指向该目录的子加载器。使用方代码（在应用 classpath 上，只看得到接口）调用无参 `load(CodecFactory.class)`：

【实测输出】：

```text
当前类无法从自身 classpath 看到 providers/ 目录，但 load(Class) 用的是 TCCL：
  [trace] StandardCodecs 构造器执行
  通过 TCCL 找到: standard，其实际类加载器 = fake-container-tccl
```

使用方自身的类加载器确实加载不到实现类（父链上没有），`ServiceLoader` 借 TCCL 完成了资源发现（`META-INF/services` 文件在 TCCL 里）与类加载（`Class.forName(cn, false, loader)` 的 `loader` 就是 TCCL）——**"父代码用子类加载器找类"完整复现**。

## 5.4 JDBC 与 Tomcat：两代经典结构

- **JDBC 4.0+（JDK 6 起）**：`DriverManager.ensureDriversInitialized()`（`java.sql/java/sql/DriverManager.java:575-647`）在首次 `getConnection` 时调用 `ServiceLoader.load(Driver.class)`（:602，无参版即 TCCL），扫出所有驱动 jar 的 `META-INF/services/java.sql.Driver`，实例化过程触发驱动类静态块里的 `DriverManager.registerDriver(this)`——**驱动的自注册是被 SPI 实例化"带出来"的**（第六章展开）。
- **Tomcat（Servlet 3.0 起）**：容器用 `WebappServiceLoader` 按 SPI 协议读取每个 webapp 的 `META-INF/services/jakarta.servlet.ServletContainerInitializer`，发现的 SCI 在容器启动时回调 `onStartup`。Spring 的 `SpringServletContainerInitializer` 就是挂在协议上的首位实现（第七章 7.1）。

## 5.5 本章小结与决策表

| 场景 | 推荐写法 | 原因 |
|---|---|---|
| 框架代码，运行在别人容器里 | `ServiceLoader.load(S)`（TCCL） | 实现类在调用方的加载器可见域里 |
| 应用代码，加载器结构自控 | `ServiceLoader.load(S, X.class.getClassLoader())` | 显式可控，不受线程干扰 |
| 容器代码 | 自己管理 TCCL：分发请求前 set，结束后还原 | TCCL 是全局可变状态，会污染后续逻辑 |
| 怀疑 SPI "找不到实现" | 先查 TCCL：`Thread.currentThread().getContextClassLoader()` | 90% 的"找不到"是 TCCL 不对或为 null |


---

# 六、JDK 自家怎么用 SPI

> SPI 最大的用户就是 JDK 自己。本章用四个案例看"官方用法"，全部为 JDK 21 源码实读。

## 6.1 全景：java.base 里就有 36 个 uses

**（白话）** JDK 的模块描述符把"哪些能力开放给 SPI 扩展"写得明明白白。实测统计：

```text
$ java --describe-module java.base | grep -c "^uses"
36
```

样例（同上命令实测摘录）：`CurrencyNameProvider`、`DateFormatSymbolsProvider`、`System$LoggerFinder`、`URLStreamHandlerProvider`、`InetAddressResolverProvider`、`Chronology`……再看提供方：

```text
$ java --describe-module jdk.charsets | grep "^provides"
provides java.nio.charset.spi.CharsetProvider with sun.nio.cs.ext.ExtendedCharsets
```

**JDK 自带的扩展字符集就是用 SPI 注册的**：`jdk.charsets` 模块 `provides` 一个 `CharsetProvider` 实现，`java.base` 模块 `uses` 它——基础设施与扩展组件之间唯一的耦合点是接口名。这是理解 SPI 定位最直观的标本。

## 6.2 案例 1：Charset.defaultCharset() 的懒初始化

**（白话）** 平台默认编码是绝大多数程序迟早要碰的，但它的 SPI 查找被推迟到**第一次真正用到时**——同样的懒加载纪律。

【源码证据】`java.base/java/nio/charset/Charset.java:335-341`——默认编码查找里迭代所有 `CharsetProvider`：

```java
    private static Iterator<CharsetProvider> providers() {
        return new Iterator<>() {
                ClassLoader cl = ClassLoader.getSystemClassLoader();
                ServiceLoader<CharsetProvider> sl =
                    ServiceLoader.load(CharsetProvider.class, cl);   // :339-340
                Iterator<CharsetProvider> i = sl.iterator();
```

另一个细节：`Charset.java:441-442` 用的是 **`loadInstalled`** 而不是普通 `load`——JDK 的扩展字符集查找只看"已安装"的平台级提供者，故意不理会应用类路径：

```java
                        ServiceLoader<CharsetProvider> sl =
                            ServiceLoader.loadInstalled(CharsetProvider.class);
```

**为什么这样设计**：默认编码是 JVM 级全局状态，不应该被某个应用顺手注册的类影响。`load` 与 `loadInstalled` 的选择本身就是一道安全边界（对照 2.3 的决策表）。

## 6.3 案例 2：SelectorProvider——NIO 世界的入口

【源码证据】`java.base/java/nio/channels/spi/SelectorProvider.java:128-134`——`Selector.open()` 背后的提供者解析链：系统属性 → 服务提供者 → 默认实现：

```java
    private static SelectorProvider loadProviderAsService() {
        ServiceLoader<SelectorProvider> sl =
            ServiceLoader.load(SelectorProvider.class,
                               ClassLoader.getSystemClassLoader());
        Iterator<SelectorProvider> i = sl.iterator();
        for (;;) {
            try {
                ...
```

这个"先系统属性、后 SPI、最后默认值"的三段式是 JDK 的标准扩展套路（`Charset.defaultCharset`、`URL` 的流处理器工厂都长这样）。你用 Netty 时它自带的不 interrupted 阻塞感知逻辑、用 JDK 21 的虚拟线程时 NIO 的多路复用实现切换，都在这条 SPI 链上。

## 6.4 案例 3：DriverManager——SPI 最著名的战场

**（白话）** 老教材 JDBC 第一课是 `Class.forName("com.mysql.jdbc.Driver")`，JDBC 4.0（JDK 6）之后这行代码消失了：只要驱动 jar 在类路径上，`DriverManager.getConnection(...)` 第一次被调用时就自动完成注册。链路如下：

1. `getConnection` → `ensureDriversInitialized()`（`java.sql/java/sql/DriverManager.java:671` → :575）；
2. 静态初始化块里 `ServiceLoader<Driver> loadedDrivers = ServiceLoader.load(Driver.class);`（:602）——注意用的正是 TCCL（:602 调的无参 load，:1695-1699）；
3. 迭代实例化每个驱动（:618-620），驱动类的静态块执行 `DriverManager.registerDriver(this)` 完成自注册；
4. 迭代外面包了 `try { ... } catch (Throwable t) { /* Do nothing */ }`（:617-623）——**个别坏驱动 jar 不影响其他驱动注册**（3.4 节说的"记账式容错"的官方示范）；
5. 兼容历史：系统属性 `jdbc.drivers` 里配置的类名仍会被 `Class.forName` 加载一遍（:630-641），SPI 之前的老机制继续有效。

【源码证据】`java.sql/java/sql/DriverManager.java:617-623`：

```java
                    try {
                        while (driversIterator.hasNext()) {
                            driversIterator.next();
                        }
                    } catch (Throwable t) {
                        // Do nothing
                    }
```

配套的模块声明也在源码里：`java --describe-module java.sql | grep uses` → `uses java.sql.Driver`。**定义方（java.sql）、使用方（DriverManager）、实现方（各驱动 jar）三方齐备**，这就是一个标准 SPI 的完整解剖标本。

## 6.5 案例 4：InetAddressResolverProvider——新 SPI 的诞生样板

JDK 18（JEP 418）为主机名解析引入了全新的标准 SPI：`java.net.spi.InetAddressResolverProvider`（`java.base/java/net/spi/InetAddressResolverProvider.java`）。它是观察"JDK 现在如何设计一个新 SPI"的好样本：

- 服务接口带完整的扩展点语义（`InetAddressResolver` 定义查询行为，provider 工厂返回它）；
- `java.base` 模块声明 `uses java.net.spi.InetAddressResolverProvider`；
- 使用方查找代码就一行——`java.base/java/net/InetAddress.java:507`：`return ServiceLoader.load(InetAddressResolverProvider.class)`——与本文第二、三章讲的 API 一模一样，**20 年了，扩展点的接入姿势没有变过**。

## 6.6 本章小结

- JDK 既是 SPI 的作者也是最大用户：`java.base` 一个模块就 `uses` 36 种服务；扩展字符集、NIO 选择器、JDBC 驱动、主机名解析器全部走 SPI 接入。
- 官方示范的三条纪律：**懒初始化**（Charset/DriverManager 都是首次使用才查找）、**分级查找**（loadInstalled 与系统属性构筑安全边界）、**容错迭代**（DriverManager 吞 Error 继续注册其余驱动）。
- 看 JDK 设计新特性（如 JEP 418）时，先看它有没有配套 SPI——有的话，`uses/provides` 声明就是它对外开放的程度的直接度量。


---

# 七、主流框架与 SPI

> 本章源码：Spring Framework 7.1.0-SNAPSHOT（`D:\code\3rd\spring-framework`，commit `f447f3c310`）、Spring Boot 4.2.0-SNAPSHOT（`D:\code\3rd\spring-boot`）、Sentinel / Seata / Nacos（均在 `D:\code\3rd`）。行号均为实际读取所得；SLF4J/Jackson 未在本地走读源码，单独标注。

## 7.1 Spring Framework：JDK SPI 的"标准用户"

Spring 对 JDK SPI 的使用分两类：**自己作为提供者被容器发现**，以及**把 SPI 提供者包装成 Spring Bean**。

### 7.1.1 SpringServletContainerInitializer：Spring 挂在 Servlet 容器 SPI 上

Servlet 3.0 规定：容器启动时用 JAR Services API 发现 `META-INF/services/jakarta.servlet.ServletContainerInitializer` 登记的初始化器。spring-web 的 jar 里就带着这个文件（实测：

```text
spring-web/src/main/resources/META-INF/services/jakarta.servlet.ServletContainerInitializer
→ 内容：org.springframework.web.SpringServletContainerInitializer
```

），实现类再用 `@HandlesTypes` 让容器把"所有实现了某个接口的类"递过来：

【源码证据】`spring-web/src/main/java/org/springframework/web/SpringServletContainerInitializer.java:110-111`

```java
@HandlesTypes(WebApplicationInitializer.class)
public class SpringServletContainerInitializer implements ServletContainerInitializer {
```

链路：**Tomcat（用 SPI 发现 SCI）→ SpringServletContainerInitializer.onStartup（接收容器递来的所有 WebApplicationInitializer 类）→ 逐个实例化并调用 onStartup**。Spring 的"零 web.xml 启动"（`AbstractAnnotationConfigDispatcherServletInitializer` 那条路）的第一块砖，就是 JDK 风格的文件发现协议。javadoc 自己也写明：*configuration of the servlet container using Spring's WebApplicationInitializer ... via the JAR Services API `ServiceLoader#load(Class)`*（:45）。

### 7.1.2 ServiceLoaderFactoryBean：把 SPI 提供者接进 IoC 容器

Spring 不想让你在业务代码里手写 `ServiceLoader.load`——它提供了三个 `FactoryBean` 直接把 SPI 结果变成容器管理的 Bean（`spring-beans/src/main/java/org/springframework/beans/factory/serviceloader/`）：

| FactoryBean | 容器里得到的 Bean | 对应 ServiceLoader API |
|---|---|---|
| `ServiceFactoryBean` | 第一个提供者实例 | `findFirst()` |
| `ServiceListFactoryBean` | 所有提供者的 List | 迭代全集 |
| `ServiceLoaderFactoryBean` | `ServiceLoader` 本身 | 原样暴露 |

【源码证据】`org/springframework/beans/factory/serviceloader/AbstractServiceLoaderBasedFactoryBean.java:71`

```java
		return getObjectToExpose(ServiceLoader.load(getServiceType(), this.beanClassLoader));
```

javadoc 直说这是对 **JDK 1.6 `ServiceLoader`** 的桥接（:30）。类加载器用的 `beanClassLoader`——Spring 遵守容器纪律，不玩 TCCL 花活（对比第五章的决策表）。

## 7.2 Spring Boot：自研注册表与"类 SPI"的回归

**（白话）** Spring Boot 的自动配置常被说成"基于 SPI"，更准确的说法是：**Boot 借鉴了 SPI 的"文件即注册表"思想，但自建了两代注册表**（`spring.factories` 与 `.imports`），因为 JDK SPI 有几个满足不了的需求：注册的是"工厂/配置类名"而不是实例、需要按接口分类、需要跨 jar 排序合并、需要统一的类加载器策略。

### 7.2.1 第一代：spring.factories 与 SpringFactoriesLoader

格式：一个 Properties 风格文件，**key = 接口全限定名，value = 逗号分隔的实现类全限定名列表**：

```properties
# spring-boot/src/main/resources/META-INF/spring.factories（实测摘录）
org.springframework.boot.logging.LoggingSystemFactory=\
org.springframework.boot.logging.logback.LogbackLoggingSystem$Factory,\
org.springframework.boot.logging.java.JavaLoggingSystem$Factory
```

加载器是 spring-core 的 `SpringFactoriesLoader`——javadoc 开篇定位（:57-58）："**General purpose factory loading mechanism for internal use within the framework**"（框架内部专用的通用工厂装载机制）。

【源码证据】`spring-core/src/main/java/org/springframework/core/io/support/SpringFactoriesLoader.java:329-340`——本质还是 `getResources` 扫描 + 逐个解析，只是把"每行一个类"换成了 Properties 解析：

```java
	protected static Map<String, List<String>> loadFactoriesResource(ClassLoader classLoader, String resourceLocation) {
		...
			Enumeration<URL> urls = classLoader.getResources(resourceLocation);   // :332
			while (urls.hasMoreElements()) {
				UrlResource resource = new UrlResource(urls.nextElement());
				Properties properties = PropertiesLoaderUtils.loadProperties(resource);  // :335
```

与 JDK SPI 的关键差异（源码可见）：

| 差异点 | JDK ServiceLoader | SpringFactoriesLoader |
|---|---|---|
| 文件格式 | 每行一个类，扁平 | Properties：按接口分类（一个文件注册所有扩展点） |
| 实例化时机 | 迭代时逐个懒实例化 | `loadFactories`（:246）**一次全部实例化并排序**（`AnnotationAwareOrderComparator`） |
| 实例化方式 | 无参构造器/provider() | **任意"可解析构造器"**（主构造器/单一 public 构造器，javadoc :68-77） |
| 缓存 | ServiceLoader 实例内缓存 | **按 ClassLoader 的全局缓存**（:104，`ConcurrentReferenceHashMap`） |
| 注册内容 | 接口实现 | 接口实现 / 工厂 / 监听器 / 初始化器……任意"工厂类型" |

### 7.2.2 第二代：AutoConfiguration.imports——向 JDK 格式"回归"

自动配置类最初也注册在 `spring.factories`（key 为 `EnableAutoConfiguration`）。Boot 2.7 开始改用**每接口一个文件、每行一个类**的格式，Boot 3.0 起自动配置完全不再读 `spring.factories`：

```text
META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

注意文件名不是接口名，而是**注解名**（`@AutoConfiguration` 注解的全限定名）——这样任何 `@Import` 型注解都能拥有自己的注册文件，格式上完全就是 JDK SPI 的"一行一个类 + #注释"（Boot 4.2 源码实测：`core/spring-boot-autoconfigure` 的该文件 12 行；**Boot 4.0 模块化改造后，全仓库 180 个 `*.imports` 文件**分散在 `spring-boot-activemq`、`spring-boot-amqp` 等各技术模块中）。

【源码证据】`core/spring-boot/src/main/java/org/springframework/boot/context/annotation/ImportCandidates.java:47,81`——加载逻辑与 `LazyClassPathLookupIterator.parse` 如出一辙（UTF-8、逐行、#注释、trim、去空行）：

```java
	private static final String LOCATION = "META-INF/spring/%s.imports";
	...
	public static ImportCandidates load(Class<?> annotation, @Nullable ClassLoader classLoader) {
		...
		String location = String.format(LOCATION, annotation.getName());
		Enumeration<URL> urls = findUrlsInClasspath(classLoaderToUse, location);
```

【源码证据】`core/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/AutoConfigurationImportSelector.java:200-210`——自动配置导入的第一步：

```java
	protected List<String> getCandidateConfigurations(AnnotationMetadata metadata,
			@Nullable AnnotationAttributes attributes) {
		ImportCandidates importCandidates = ImportCandidates.load(this.autoConfigurationAnnotation,
				getBeanClassLoader());
		List<String> configurations = importCandidates.getCandidates();
		Assert.state(!CollectionUtils.isEmpty(configurations),
				"No auto configuration classes found in " + "META-INF/spring/"
						+ this.autoConfigurationAnnotation.getName() + ".imports. ...");
		return configurations;
	}
```

### 7.2.3 那为什么 Boot 不直接用 ServiceLoader？

对比后答案很清楚——Boot 要的每一样 JDK SPI 都恰好没有：

| Boot 的需求 | JDK SPI 的现状 | Boot 的解法 |
|---|---|---|
| 注册"配置类名"，启动期只解析不实例化（配合 `@Conditional` 过滤） | `iterator()` 一迭代就实例化 | `.imports` 只取类名列表，过滤后再 `@Import` |
| 一个 jar 声明 N 种扩展点 | 一个接口一个文件 | `spring.factories` 一个文件全包；`.imports` 则"一注解一文件" |
| 排序（`@Order`/`@AutoConfigureBefore`） | 顺序 = 文件发现顺序，无正式语义 | 加载后用 `AutoConfigurationSorter` 按注解排序 |
| 多 ClassLoader 环境策略统一 | 无参 load 依赖 TCCL，容器间行为漂移 | 显式 `getBeanClassLoader()` 传入（仍受 TCCL 影响，但策略归 Boot 管） |
| 失败信息友好 | `ServiceConfigurationError` 很硬核 | `Assert.state` 给出人话提示（:205-207） |

**结论**：Spring Boot 是 SPI 思想的"精神继承者"而不是"API 用户"——注册表思想同源（文本文件 + 全限定名 + classpath 扫描），但实例化语义、排序语义、错误语义都是自研的。真正调用 `java.util.ServiceLoader` 的地方（7.1.2 的 FactoryBean、7.3 的 SCI）反而在更靠近底层的集成层。

## 7.3 Servlet 容器：SCI 是 SPI 协议的容器侧半边

Servlet 3.0 的 `ServletContainerInitializer` 发现机制（Tomcat `WebappServiceLoader`）就是 SPI 文件协议的容器实现：读 webapp 的 `META-INF/services/jakarta.servlet.ServletContainerInitializer`。它和 JDK SPI 的差别只剩实现者不是 `java.util.ServiceLoader`（需要容器处理 webapp 隔离的类加载器与虚拟 classpath 合并）。Spring 一侧的对接见 7.1.1。**这是"协议存活、实现者轮换"的典型：只要文件格式约定还在，谁来实现扫描都可以。**

## 7.4 生态圈的标准用法：SLF4J 与 Jackson（官方文档口径）

以下两条未经本地源码走读，按官方文档口径陈述：

- **SLF4J 2.x**：`org.slf4j.LoggerFactory` 首次加载时用 `ServiceLoader` 查找 `org.slf4j.spi.SLF4JServiceProvider`，注册文件 `META-INF/services/org.slf4j.spi.SLF4JServiceProvider`——Logback 1.3+、log4j-slf4j2-impl 各自登记自己的提供者。SLF4J 1.x 时代用的是 `StaticLoggerBinder` 类路径扫描（更古老的自研协议），2.0 完成向 JDK SPI 的迁移——又一个"自研协议向标准收敛"的案例。
- **Jackson**：`ObjectMapper.findModules()` 用 `ServiceLoader.load(Module.class)` 发现 classpath 上所有 `com.fasterxml.jackson.databind.Module` 实现（jackson-datatype-jsr310 等 jar 各自注册）——Spring Boot 的 `JacksonAutoConfiguration` 就是调它把模块自动装进 ObjectMapper 的。

## 7.5 国产框架的自研 SPI：当 JDK 版本不够用时

Dubbo / Sentinel / Seata / Nacos 这批高性能框架都选择了**自研 SPI 而非直接用 ServiceLoader**。本地源码走读三个（Dubbo 未在本地，按官方文档口径概述）：

### 7.5.1 Sentinel：SpiLoader + @Spi 注解

【源码证据】`sentinel-core/src/main/java/com/alibaba/csp/sentinel/spi/SpiLoader.java:76,330`

```java
    private static final String SPI_FILE_PREFIX = "META-INF/services/";
    ...
        String fullFileName = SPI_FILE_PREFIX + service.getName();
        ...
            urls = classLoader.getResources(fullFileName);
```

文件协议与 JDK 完全一致（`META-INF/services/`），但配上 `@Spi` 注解（`Spi.java:29-49`）把 JDK SPI 缺的元数据补齐了：`value`（别名，支持"按名字取实现"）、`isSingleton`（默认 true）、`isDefault`、`order`（优先级）。

### 7.5.2 Seata：EnhancedServiceLoader 双目录 + @LoadLevel

【源码证据】`seata/common/src/main/java/org/apache/seata/common/loader/EnhancedServiceLoader.java:284-285`

```java
        private static final String SERVICES_DIRECTORY = "META-INF/services/";
        private static final String SEATA_DIRECTORY = "META-INF/seata/";
```

除了兼容 JDK 目录，还开了自己的 `META-INF/seata/` 目录放 **key=value 格式**的注册（`seata=实现类名`，按名字实例化），配合 `@LoadLevel(name, order)` 实现命名查找与排序——向 Dubbo 的 key=value 格式看齐。

### 7.5.3 Nacos：薄封装 + 全局缓存

【源码证据】`nacos/common/src/main/java/com/alibaba/nacos/common/spi/NacosServiceLoader.java:31,49`

```java
public class NacosServiceLoader {
    ...
    public static <T> Collection<T> load(final Class<T> service) {
        ...
        for (T each : ServiceLoader.load(service)) {
```

Nacos 没有换协议，只是**包了一层并加了类级缓存**（javadoc："cache the classes for reducing cost when load second time"）——JDK SPI 每个新 ServiceLoader 实例都会重新实例化，这个封装把"同一 JVM 同一接口只实例化一次"变成默认行为（对比 2.5 实测结论 5）。

### 7.5.4 Dubbo：SPI 重度改造的标杆（官方文档口径）

Dubbo 的 `ExtensionLoader`（未在本地走读，口径来自官方文档）：注册目录 `META-INF/dubbo/internal/` 等，**key=value 格式**（`adaptive=org.apache.dubbo...AdaptiveExtension`）；`@SPI("默认名")` 标注接口、`@Adaptive` 运行时按 URL 参数动态选择实现、`@Activate` 条件激活、Wrapper 自动包装成责任链。Dubbo 的场景——**同一个扩展点按运行时上下文选不同实现**（如按 URL 里的 registry 协议选注册中心）——是 JDK SPI "全量实例化 + 无名字"模型完全覆盖不了的，因此改造得最彻底。

### 7.5.5 大对比表

| 维度 | JDK SPI | spring.factories | Boot .imports | Sentinel @Spi | Seata | Dubbo |
|---|---|---|---|---|---|---|
| 注册文件 | `META-INF/services/接口名` | `META-INF/spring.factories` | `META-INF/spring/注解名.imports` | 同 JDK | `META-INF/seata/` + 兼容 JDK | `META-INF/dubbo/internal/` |
| 文件格式 | 一行一个类 | key=接口, value=逗号列表 | 一行一个类 | 同 JDK | key=value | key=value |
| 按名查找 | ✗ | ✗ | ✗ | ✓（value 别名） | ✓ | ✓ |
| 排序 | ✗（顺序未定义） | Order 注解比较 | `@AutoConfigureBefore/After` | `order` 属性 | `@LoadLevel(order)` | `@Activate.order` |
| 懒实例化 | ✓（迭代级） | ✗（一次全实例化） | ✓（导入时才实例化配置类） | 可配 singleton | 按需 | 按需 + 自适应 |
| IOC/DI | ✗ | ✗ | Spring 容器 | ✗ | ✗ | ✓（扩展点自动注入） |
| 条件选择实现 | ✗ | ✗ | `@Conditional`（容器层） | ✗ | ✗ | ✓（@Adaptive 按 URL） |

**一条演化规律**：需求越靠近"运行时按上下文选实现"，自研程度越高；需求停留在"启动时收集一批实现"，就越可能直接用（或贴着）JDK SPI。Spring Boot 处于中间：借协议、换语义。

## 7.6 本章小结

- Spring Framework 是 JDK SPI 的标准用户（SCI 桥接 + FactoryBean 包装）；Spring Boot 是 SPI 思想的继承者（两代自研注册表），两者的分界在"要不要 Spring 的实例化与排序语义"。
- Boot 2.7 → 3.0 的 `.imports` 迁移、Boot 4.0 的模块化（180 个 imports 文件），说明"文件即注册表"的协议仍在持续演化，格式反而向 JDK SPI 靠拢。
- Sentinel/Seata/Nacos/Dubbo 的自研 SPI 共同补的是三件事：**名字、顺序、条件**——JDK SPI 三者皆无。
- 判断一个框架"用没用 SPI"，别只搜 `ServiceLoader.load`：搜 `META-INF/services`、`spring.factories`、`*.imports` 和框架自己的 Loader 类，才能看到全貌。


---

# 八、贯通视图：一次 ServiceLoader.load 的完整时间线

把二~五章的机制叠在一条时间线上（类路径 + 模块混合环境的典型场景）：

```text
T0  ServiceLoader.load(CodecFactory.class)                    ← :1696，取 TCCL
│   └ new ServiceLoader<>(caller, null /*layer*/, svc, tccl)  ← :503-534
│     └ checkCaller：命名模块则查 uses 声明                    ← :564-581
│     （此刻：零扫描、零类加载、零实例化——只是"装好了一把枪"）
│
T1  第一次 iterator()/stream()/findFirst()
│   └ newLookupIterator()                                     ← :1299-1323
│     ├ ① ModuleServicesLookupIterator                        ← 命名模块优先
│     │   └ 沿 TCCL 父链查 ServicesCatalog（provides 登记表）  ← :1032-1065
│     └ ② LazyClassPathLookupIterator                         ← 类路径兜底
│         └ getResources("META-INF/services/接口名")           ← :1191-1203，扫全部jar
│
T2  hasNext()：逐个文件 parse（UTF-8/#注释/去重）              ← :1134-1162
│   └ Class.forName(类名, false, tccl)：只加载不初始化          ← :1217
│   └ 校验：命名模块类跳过(:1232)/子类型(:1237)/构造器(:1240)
│   └ 包成 ProviderImpl（未实例化）                             ← :689
│
T3  next().get()：真正实例化                                    ← :724-731
│   ├ 模块显式类：public static provider() 工厂                 ← :740-776
│   └ 类路径类：public 无参构造器 newInstance()                 ← :784-817
│   └ 实例入缓存 instantiatedProviders                          ← :1404
│
T4  后续迭代：先吐缓存，缓存尽再回到 T2                          ← :1389-1408
│
T5  reload()：清缓存、lookupIterator 置空、reloadCount++        ← :1831-1841
    └ 旧 iterator/stream 因计数不符抛 ConcurrentModificationException ← :1383-1386
```

三条主线在其中交汇：**类加载器线**（TCCL 决定扫哪个资源域、哪个加载器加载类）、**文件协议线**（一个常量拼出注册表路径）、**模块系统线**（ServicesCatalog 直接给结果）。任何"SPI 没生效"的问题，都可以沿 T0→T3 逐段排查：TCCL 对不对 → 文件在不在扫描范围里 → 类名写得对不对 → 实例化条件满不满足。

---

# 九、附录

## 9.1 最佳实践与坑（每条都有源码/实测出处）

| # | 坑/实践 | 出处 |
|---|---|---|
| 1 | **服务接口的设计**：多留几个"自述方法"（能力查询、优先级），让使用方能挑选实现——javadoc 的 Designing services 两准则 | `ServiceLoader.java:145-169` |
| 2 | 提供者应优先设计成**工厂/代理**（如 `XxxFactory`），避免昂贵对象被无谓实例化 | `ServiceLoader.java:159-168` |
| 3 | **编码必须 UTF-8**：GBK 环境下用 IDE 默认编码提交注册文件，中文注释就可能把解析搞坏 | :1174 |
| 4 | 行内空格 = 语法错误（不是忽略），`mvn` 资源过滤（`@..@` 替换）最容易往里带空格 | :1146-1147 |
| 5 | 类路径下**顺序没有正式语义**，靠"文件先发现"做优先级是赌博；需要排序用 `stream().sorted()` 或框架层 Order | :1597-1599 |
| 6 | `ServiceLoader` 实例**非线程安全**，也别跨应用缓存（TCCL 串服务 + 内存泄漏） | :376-377、:1673-1679 |
| 7 | 想容错迭代：手写 while + 捕获 `ServiceConfigurationError`（学 DriverManager），别用 forEach | :617-623、:1332-1337 |
| 8 | 报错先看 **cause 链**：构造器里抛的异常被解包挂进 `ServiceConfigurationError` | :809-815 |
| 9 | 模块化迁移时：named module 的提供者**不要**再写注册文件（会被跳过）；模块化提供者记得 `uses` | :1232-1235、:578-580 |
| 10 | 提供**默认实现兜底**：`findFirst().orElse(default)` 是官方推荐姿势 | :1796-1800 |
| 11 | 远程 URL 类路径 + 错误配置的 Web 服务器（404 返回 200 + HTML 错误页）会把 HTML 当注册文件解析 → 莫名其妙的 `ServiceConfigurationError` | javadoc :1611-1627，官方"名场面" |
| 12 | Spring Boot 用户别在自动配置类里做昂贵事情——`.imports` 只登记名字，但被 `@Import` 后实例化时机由容器决定 | 第七章 7.2.3 |

## 9.2 关键 API 速查表

| API | 签名 | 版本 | 一句话 |
|---|---|---|---|
| load | `static <S> ServiceLoader<S> load(Class<S>)` | 1.6 | TCCL 查找，默认入口 |
| load | `static <S> ServiceLoader<S> load(Class<S>, ClassLoader)` | 1.6 | 指定加载器 |
| loadInstalled | `static <S> ServiceLoader<S> loadInstalled(Class<S>)` | 1.6 | 只找平台级提供者 |
| load(层) | `static <S> ServiceLoader<S> load(ModuleLayer, Class<S>)` | 9 | 模块层查找 |
| iterator | `Iterator<S> iterator()` | 1.6 | 懒查找 + 实例化 + 缓存 |
| stream | `Stream<Provider<S>> stream()` | 9 | 先看类型后实例化 |
| findFirst | `Optional<S> findFirst()` | 9 | 第一个实现 + 默认值兜底 |
| Provider | `interface Provider<S> extends Supplier<S>` | 9 | `type()`/`get()` 分离 |
| reload | `void reload()` | 1.6 | 清缓存，旧迭代器 CME |
| Provider.type/get | `Class<? extends S> type()` / `S get()` | 9 | 见上 |
| ServiceConfigurationError | `extends Error` | 1.6 | 一切 SPI 失败的统一形态 |

## 9.3 实测工程说明

本文所有【实测输出】来自 `D:\tmp\spi-demo`（JDK 21 运行）：

- `providers/std|ext|hidden|broken`：类路径四场景（正常/工厂提供者/私有构造器/坏类名）；
- `modules/mod-api|mod-ctor|mod-factory|app|app-bad`：模块路径五模块（接口/构造器提供者/工厂提供者/正常使用方/缺 uses 的使用方）；
- `app/com/example/app/Main.java`（场景1~3）、`ModMain.java`（模块路径）、`TcclMain.java`（TCCL 倒灌）。

复现命令（Windows、Git Bash）：

```bash
cd /d/tmp/spi-demo
JDK=/d/soft/jdk/jdk-21/bin
$JDK/java -cp classes com.example.app.Main                    # 类路径三场景
$JDK/java --module-path "mod-api;mod-ctor;mod-factory;mod-app" -m com.example.app/com.example.app.ModMain
$JDK/java --module-path "mod-api;mod-ctor;mod-app-bad" -m com.example.appbad/com.example.app.MainBad
$JDK/java -cp classes com.example.app.TcclMain                # TCCL 倒灌
```

## 9.4 系列文档导航

- [Spring Framework 深度源码解析](../spring/Spring%20Framework.md)——第七章"扩展机制大全"与本系列互为镜像
- [Flow.md](Flow.md)、[Java并发.md](Java并发.md)、[G1.md](G1.md)、[JDK版本特性变化.md](JDK版本特性变化.md)——同目录系列
- 本文实测代码：`D:\tmp\spi-demo`（临时目录，清理后可按 9.3 重建）
