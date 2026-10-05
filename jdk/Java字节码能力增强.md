# Java 代码增强全景深度解析：反射、动态代理、VarHandle、Instrumentation 与运行时字节码篡改（写给初学者的架构全景）

> **本文基于的源码**：JDK **27**（Oracle GA，2026-09-15，本机 `D:\soft\jdk\jdk-27`）的 `lib/src.zip`，走读时解压至 `D:\tmp\bce-src`；Arthas 关键源码取自 GitHub `alibaba/arthas` master 分支（2026-10 抓取）；Spring Framework **7.1.0-SNAPSHOT** 源码在 `D:\code\3rd\spring-framework`。OpenJDK 原生层（JPLISAgent.c / InvocationAdapter.c）取自 GitHub `openjdk/jdk` master。
>
> **实证说明**：文中所有【源码证据】的文件路径与行号均为对上述快照实际读取所得；所有可运行示例均在 jdk-27 下实际编译、运行并记录了输出（示例工程在 `D:\tmp\bce-demo`）。涉及"某特性属于哪个版本"的结论，均以 openjdk.org 的 JEP 页面逐项核对，不凭记忆。
>
> **阅读约定**：沿用本系列（参见 [Spring Framework.md](../spring/Spring%20Framework.md)）的风格——每章"先白话、后源码"：先用一两句人话讲清"这是什么、为什么需要它"，再给源码证据链（`模块/…/类名.java` + 行号 + 代码片段）。行号只对该快照精确，读者用 IDEA 打开源码按类名 + 方法名定位即可。

---

## 如何读这份文档

这份文档回答一个总问题：**Java 世界里，有哪些手段可以在"不改源码、不改调用方"的前提下，观察、调用、生成甚至篡改一个类的行为？**

推荐两遍读法：

- **第一遍（建立地图，1~2 小时）**：只读第一章总览每一节的白话段、各章"小结"、第八章贯通视图。目标是能回答：反射和 MethodHandle 是什么关系？Spring AOP 用的代理和 Mockito 用的一样吗？Arthas 的 `watch` 为什么能"看到"运行中的方法？JVM 是怎么一步步收紧这些能力的？
- **第二遍（深入源码）**：按第二章（反射）→ 第三章（MethodHandle/VarHandle）→ 第四章（代理）→ 第六章（Instrumentation，重点）→ 第七章（Arthas 案例剖析）的顺序，对照【源码证据】逐行读。第五章（字节码库）可随用随查。

三个阅读提醒：

1. **这些技术是分层的，不是并列的**。CGLib 的地基是 ASM，Arthas 的地基是 Instrumentation + ASM + ByteKit，JDK 27 自己的 `ProxyGenerator` 底下是 JDK 自带的 Class-File API。分清"谁站在谁肩膀上"，比记住每个工具的 API 重要得多。
2. **能力边界来自 JVM 的三道闸门**：模块系统（JPMS）、final 语义（JEP 500）、热替换约束（redefine/retransform 不允许结构变更）。本文反复出现的"做不到"，几乎都源于这三者。
3. **所有"增强"手段都在被平台逐步收编或收紧**：`sun.misc.Unsafe` deprecated for removal、动态 Agent 警告、final 字段变异警告。理解这条"integrity by default"的主线，才能判断哪些手段还有未来。

---

# 一、总览：什么是"代码增强"，Java 提供了哪些手段

## 1.1 一句话定位

**"代码增强"指的是：在程序还没有写死的地方，改变或插入行为**——给接口调用插一层拦截（动态代理）、把私有字段挖出来看（反射）、把字段访问做成原子操作（VarHandle）、在类加载的瞬间改写它的字节码（Java Agent）、甚至在一行代码正在运行时把它的方法体换掉（Arthas）。

按"侵入程度"由浅入深，可以排成一条谱系：

| 谱系 | 问题 | 代表技术 | 改变的是什么 |
|---|---|---|---|
| **观察** | "这个类的结构是什么？" | 反射（`Class`/`Field`/`Method`） | 什么也不改，只是看 |
| **调用/访问** | "绕过编译期检查去调它、改它" | 反射调用、MethodHandle、VarHandle、Unsafe | 不改类，改变访问方式 |
| **生成** | "凭空造一个新类" | 动态代理、CGLib、ASM/Javassist/ByteBuddy/Class-File API | 造新类，不改旧类 |
| **类加载时篡改** | "在类加载前，把它的字节码换掉" | Instrumentation + ClassFileTransformer（`-javaagent`） | 改的是"将要被定义的类" |
| **运行时篡改** | "类已经加载了，还能改吗？" | `retransformClasses` / `redefineClasses` + Attach API + Arthas | 改已加载类的方法体（受严格约束） |

Spring AOP、MyBatis Mapper、Mockito、SkyWalking、Arthas……整个 Java 生态的"魔法"都分布在这条谱系上。本文的任务是把每个刻度背后的源码机制拆开。

## 1.2 设计哲学：读源码前先记住四句话

1. **官方给契约，社区给实现**。JDK 提供的是"合法的钩子"：`java.lang.instrument`（JDK 5）、`Attach API`（JDK 6）、`VarHandle`（JDK 9）、Class-File API（JDK 24）。而 CGLib、Javassist、ByteBuddy、Arthas 这些社区工具，全部站在官方钩子之上。**凡是工具声称"绕过了 JVM 限制"的，最终一定踩在某条官方契约上**。
2. **字节码是唯一被普遍接受的中间表示**。源码太高级（运行时拿不到），机器码太低级（不可移植），class 文件格式稳定（JVM 规范背书）且自描述（带常量池、属性表），所以所有增强手段的落点都是 class 字节。
3. **每一代增强手段都是上一代的"补丁"**。反射慢且无类型检查 → MethodHandle（JDK 7）补；Unsafe 危险 → VarHandle（JDK 9）补；ASM/ProxyGenerator 各写一套字节码生成 → Class-File API（JDK 24）统一补。读源码时留意"这个类是为了替代什么而生的"，事半功倍。
4. **自由与完整性（integrity）的博弈**。Java 平台近年所有相关 JEP（JEP 403 强封装、JEP 451 动态 Agent 警告、JEP 471/498 Unsafe 退场、JEP 500 final 语义收紧）都在朝同一个方向走：**默认收权，显式开门**。写新代码要顺着这个方向，维护老代码要预判它的到来。

## 1.3 手段分层全景

```
┌──────────────────────────── 运行时篡改层（改已加载的类）────────────────────────────┐
│  Arthas / JRebel：Attach API + agentmain + retransformClasses + 字节码插桩          │
│  底座：Instrumentation.retransform/redefine（只能改方法体，不能增删成员）            │
├──────────────────────────── 类加载篡改层（改将加载的类）───────────────────────────┤
│  Java Agent：-javaagent + premain + ClassFileTransformer                            │
│  生态：SkyWalking、OpenTelemetry Java Agent、Pinpoint、IDE 插桩类工具               │
├──────────────────────────── 字节码工程层（生成/改写 class 字节）────────────────────┤
│  ASM（visitor，最低级）← CGLib、ByteKit 站在上面                                    │
│  Javassist（源码级 API）        ByteBuddy（DSL + Advice）                           │
│  JDK Class-File API（JDK 24 正式；JDK 27 的 ProxyGenerator 内部就在用它）           │
├──────────────────────────── 代理层（在接口/子类边界插行为）─────────────────────────┤
│  JDK 动态代理（java.lang.reflect.Proxy，JDK 1.3 起）                                │
│  CGLib Enhancer（子类化 + FastClass，Spring 内置 fork：org.springframework.cglib）  │
├──────────────────────────── 底层访问原语层（更快/更精确的反射）─────────────────────┤
│  MethodHandle / Lookup（JDK 7，invokedynamic 的另一半）                             │
│  VarHandle（JDK 9：字段级原子/有序访问）   sun.misc.Unsafe（deprecated for removal）│
├──────────────────────────── 反射层（运行时观察与调用）──────────────────────────────┤
│  java.lang.reflect：Class / Field / Method / Constructor / AccessibleObject         │
│  （JDK 18 起，其内部实现已由 MethodHandle 重写：JEP 416）                           │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

一句话读懂这张图：**越往下越是"语言标准能力"，越往上越是"工具生态"**；而上下层之间靠两个关节连接——字节码库（把"想法"变成 class 字节）和 Instrumentation（把 class 字节合法地送进 JVM）。

## 1.4 关键问题 → 手段映射（全文导览）

| 你想解决的问题 | 该用什么 | 能力边界 | 详见 |
|---|---|---|---|
| 运行时读注解、遍历字段方法、泛化调用 | 反射 `Class`/`Method`/`Field` | 模块未 open 时私有成员拿不到；JDK 26 起 final 字段变异警告 | 第二章 |
| 高频调用下反射的性能 | MethodHandle / `LambdaMetafactory` | 需要 `Lookup` 权限；无法无授权跨模块 | 第三章 |
| 无锁、精确控制字段可见性的读写 | `VarHandle` | 只针对字段/数组元素；不影响对象布局 | 第三章 |
| 拦截接口调用（AOP、RPC stub） | JDK 动态代理 | 只能代理接口 | 第四章 |
| 拦截类方法（无接口）、Mock 具体类 | CGLib / ByteBuddy | final 类/方法不可代理 | 第四章、第五章 |
| 生成或改写 class 字节 | ASM / Javassist / ByteBuddy / Class-File API | 写错的字节码要到类加载/验证期才爆 | 第五章 |
| 无侵入采集全链路 tracing | Java Agent（premain） | 启动时就要挂上 | 第六章 |
| 给运行中的进程"开膛"插探针 | Attach API + agentmain + retransform | 只能改方法体；JDK 21 起有警告 | 第六章 |
| `watch`/`trace` 任意方法、热更新类 | Arthas（上述全部的组合） | 同 retransform 约束；增删字段/方法做不到 | 第七章 |

## 1.5 版本演进时间线（JDK 5 → 27）

所有结论以 openjdk.org 各 JEP 页面为准（已逐项核对）：

| 版本 | 事件 | 为什么重要 |
|---|---|---|
| JDK 1.2 | `AccessibleObject.setAccessible` 引入 | 反射"破封装"的开始 |
| JDK 1.3 | `java.lang.reflect.Proxy` 动态代理 | 接口级 AOP 的地基 |
| JDK 5 | **JSR 163：`java.lang.instrument`** + `-javaagent`（仅 `redefineClasses`，无 retransform） | Agent 时代的起点；类加载时可改字节码 |
| JDK 6 | Attach API（`com.sun.tools.attach`）；**`retransformClasses` 加入** | 运行中进程可被注入 Agent；已加载类可重变换 |
| JDK 7 | **invokedynamic + MethodHandle**（JSR 292） | JDK 自己开始"运行时造调用" |
| JDK 8 | Lambda + `LambdaMetafactory`；`sun.misc.Unsafe` 广泛传播期 | 函数式特性建立在字节码生成之上 |
| JDK 9 | **JPMS 模块系统**（`--add-opens` 文化）、JEP 260 封装内部 API、**JEP 193 VarHandle**、`Proxy` 类生成受模块约束 | 封装时代开始，"想摸内部 API 要先开门" |
| JDK 15 | **JEP 371 Hidden Classes** | 框架生成类不再污染 `ClassLoader`（Lambda 类即隐藏类） |
| JDK 17 | **JEP 403 强封装 JDK 内部 API**（移除 `--illegal-access`） | `sun.misc.*` 默认彻底关门 |
| JDK 18 | **JEP 416 反射底层用 MethodHandle 重写** | `GeneratedMethodAccessor` 时代结束 |
| JDK 21 | **JEP 451 动态加载 Agent 发出警告** | "未来默认禁止动态 Agent"的预告 |
| JDK 22 | FFM API 正式（JEP 454）；Class-File API 首次预览（JEP 457） | Unsafe 的替代者到位 |
| JDK 23 | **JEP 471 `sun.misc.Unsafe` 内存访问方法 deprecated for removal**（`--sun-misc-unsafe-memory-access` 可调档） | 25 年历史的后门进入倒计时 |
| JDK 24 | **JEP 484 Class-File API 正式**；**JEP 498 Unsafe 内存访问方法首调警告** | 官方字节码库定型；Unsafe 用户开始"被点名" |
| JDK 26 | **JEP 500 Prepare to Make Final Mean Final**（final 字段反射变异默认警告，未来默认抛异常） | 反射改 final 字段的"祖传手艺"到期 |
| JDK 27 | 无直接相关新 JEP（演进沉淀期）；本文基准版本 | 上述全部在 27 上实测（见附录 A） |

## 1.6 全文章节地图

- **第二章 反射**：一切的基础。`Class` 镜像从哪来、`Method.invoke` 底下发生了什么、`setAccessible` 的模块判定。
- **第三章 方法句柄与 VarHandle**：反射的现代替代品，`invokedynamic` 的另一半，以及 `Unsafe` 的标准接班人。
- **第四章 动态代理（Proxy 与 CGLib）**：不改类本身、在边界插行为的主流手段，Spring AOP 的两块基石。
- **第五章 字节码工程库**：ASM / Javassist / ByteBuddy / Class-File API——所有"真·改字节码"工具的共同地基；附案例剖析"Spring 如何不加载类读注解"（5.6）。
- **第六章 Instrumentation 与 Java Agent**：JVM 官方的"改类契约"，从 premain 到 Attach，附两个可运行的完整 Agent（用 JDK 自带 Class-File API 实现，零第三方依赖）。
- **第七章 案例剖析：Arthas**：把前六章串成一条生产级链路——`watch` 命令如何在一瞬间完成"Attach → 改字节码 → 生效 → 回收数据"。
- **第八章 贯通视图**：一张总表 + 生态地图 + 选型决策树 + 未来趋势。

---

# 二、反射（java.lang.reflect）：运行时把类"打开看"

## 2.1 解决什么问题

编译器要求你在写代码时就说明白：用哪个类、哪个方法、什么访问权限。但有些场景**类和调用者在编译期互相不认识**——Spring 根据 `@Component` 注解实例化你写的类、Jackson 根据字段名填 JSON、MyBatis 把 `UserMapper` 接口变成 SQL 调用。反射就是 JVM 提供的**运行时元数据 API**：把"类"本身变成一个可编程的对象（`java.lang.Class`），让代码可以在运行时提出这些问题："你有哪些方法？这个方法怎么调用？那个字段的值是多少？"

反射**只观察和调用，不修改类的行为**——它是本文谱系中最温和的一层，但也是几乎所有上层手段（代理、字节码库、Agent）读取类信息的起点。

## 2.2 Class 对象从哪来：类加载时 JVM 顺手造的"镜像"

**白话**：每个类被类加载器加载进 JVM 时，JVM 会为它创建一个且仅一个 `java.lang.Class` 对象，这个对象是"字节码的 Java 镜像"——持有该类的方法表、字段表、注解等元数据。你调用 `Foo.class`、`foo.getClass()`、`Class.forName("Foo")`，拿到的都是同一个镜像。

**源码证据**：`java.base/java/lang/Class.java`。镜像上"反射能拿到的东西"都是查询方法，真正的数据来源是 native 方法：

```java
// Class.java:3246-3248
private native Field[]       getDeclaredFields0(boolean publicOnly);
private native Method[]      getDeclaredMethods0(boolean publicOnly);
private native Constructor<T>[] getDeclaredConstructors0(boolean publicOnly);
```

这三行 native 方法由 JVM 在加载类时把方法表/字段表翻译成 `Method[]`/`Field[]` 对象。公开入口做了两层包装，`Class.java:3013`：

```java
res = Reflection.filterMethods(this, getDeclaredMethods0(publicOnly));
```

`filterMethods`（`jdk.internal.reflect.Reflection`）会把 JVM 认为"不该被反射碰"的方法过滤掉——这就是为什么有些方法你 `getDeclaredMethods()` 看不到。注意一个经典细节：**`getDeclaredMethods()` 每次调用都会返回新创建的 `Method` 对象数组**（JDK 对 root `Method` 做了 `copy`，见 `ReflectionFactory.copyMethod`，`jdk/internal/reflect/ReflectionFactory.java:150-152`），所以把反射元数据缓存在静态字段里是框架的标配优化。

**使用示例（本机 jdk-27 实测）**——一个"框架视角"的最小程序，覆盖反射的四件套：读注解、破封装调私有方法、读写字段、遍历方法：

```java
import java.lang.annotation.*;
import java.lang.reflect.*;

public class ReflectDemo {
    @Retention(RetentionPolicy.RUNTIME)
    @interface BizModule { String value(); }

    @BizModule("order")
    public static class OrderService {
        private double price = 99.0;
        private double discount(double rate) {          // 私有方法
            return price * (1 - rate);
        }
    }

    public static void main(String[] args) throws Exception {
        Class<OrderService> clazz = OrderService.class;

        // 1. 读注解（框架据此决定行为）
        BizModule m = clazz.getAnnotation(BizModule.class);
        System.out.println("annotation = " + m.value());

        // 2. 破封装调私有方法
        OrderService svc = new OrderService();
        Method method = clazz.getDeclaredMethod("discount", double.class);
        method.setAccessible(true);                     // 模块化下需 opens / --add-opens
        System.out.println("invoke private -> " + method.invoke(svc, 0.2));

        // 3. 破封装读写字段
        Field field = clazz.getDeclaredField("price");
        field.setAccessible(true);
        field.setDouble(svc, 199.0);
        System.out.println("field after = " + field.getDouble(svc));

        // 4. 遍历方法（Method 实现了 toString，直接打印可读签名）
        for (Method md : clazz.getDeclaredMethods())
            System.out.println("method: " + md);
    }
}
```

实测输出：

```
annotation = order
invoke private -> 79.2
field after = 199.0
method: private double ReflectDemo$OrderService.discount(double)
```

（顺带一提：新 JDK 已把 `Modifier.toString(int)` 标记为过时，推荐直接用 `Method.toString()` 或 `java.lang.reflect.AccessFlag`——老代码扫依赖时注意。）

## 2.3 Method.invoke 的完整链路：一次调用背后的三层结构

**白话**：`method.invoke(obj, args)` 看起来一行代码，底下是三层：`Method` 对象（你拿着的句柄）→ `MethodAccessor`（真正的执行器，`Method` 惰性创建并缓存）→ 目标方法。JDK 18 起，第三层不再是"生成字节码桩"，而是 MethodHandle。

**源码证据 ①：invoke 主体**，`java.base/java/lang/reflect/Method.java:561-584`：

```java
public Object invoke(Object obj, Object... args)
    throws IllegalAccessException, InvocationTargetException
{
    boolean callerSensitive = isCallerSensitive();
    Class<?> caller = null;
    if (!override || callerSensitive) {
        caller = Reflection.getCallerClass();      // 取调用者，用于访问检查
    }
    if (!override) {
        checkAccess(caller, clazz,                 // 每次调用都做访问检查（除非 setAccessible(true)）
                Modifier.isStatic(modifiers) ? null : obj.getClass(), modifiers);
    }
    MethodAccessor ma = methodAccessor;            // @Stable 缓存
    if (ma == null) {
        ma = acquireMethodAccessor();              // 惰性创建执行器
    }
    return callerSensitive ? ma.invoke(obj, args, caller) : ma.invoke(obj, args);
}
```

三个值得背下来的点：

1. **访问检查每次都做**（`checkAccess`），除非 `setAccessible(true)` 把 `override` 置位——这就是"setAccessible 能提速"的真相之一。
2. **MethodAccessor 惰性创建**：`Method.java:720-732` `acquireMethodAccessor()`，创建后缓存在 root Method 上（同一 `getDeclaredMethods()` 家族共享）。
3. **callerSensitive 方法**（如 `Class.forName`）需要额外传入真实调用者，走 `Method.java:596` 的 `@CallerSensitiveAdapter` 私有重载——这是 MethodHandle 穿透调用 `Method.invoke` 时的防伪造机制。

**源码证据 ②：执行器是怎么造出来的**，`jdk/internal/reflect/ReflectionFactory.java:112-120`：

```java
public MethodAccessor newMethodAccessor(Method method, boolean callerSensitive) {
    Method root = langReflectAccess.getRoot(method);   // 用 root Method，避免缓存 caller 类
    if (root != null) { method = root; }
    return MethodHandleAccessorFactory.newMethodAccessor(method, callerSensitive);
}
```

再往下 `jdk/internal/reflect/MethodHandleAccessorFactory.java:47-82`：先用 `MethodHandles.Lookup` 把 `Method` 转成 `MethodHandle`（`unreflect` 语义），再包成 `DirectMethodHandleAccessor`；只有"MethodHandle 还没初始化"的极早期场景才回退 native 实现（`MethodHandleAccessorFactory.java:66` 的 `nativeAccessor`）。

**历史对照（重要）**：JDK 17 及之前，这里的实现是 `NativeMethodAccessorImpl`——native 调用 15 次后"膨胀"（inflation）为**动态生成的字节码类 `GeneratedMethodAccessor1`**（直接 invoke 调用目标，跳过元数据检查），阈值由 `sun.reflect.inflationThreshold` 控制。JDK 18 的 **JEP 416（Reimplement Core Reflection with Method Handles）** 把这一切换成 MethodHandle 方案： MethodHandle 天生享受 JIT 内联与 `LambdaForm` 特化，不再需要"15 次之后偷偷生成类"的两段式设计。**今天你在网上看到的"反射 inflation""GeneratedMethodAccessor 类"资料，均描述 JDK 18 之前的世界**。

## 2.4 setAccessible(true)：破封装的模块化条件

**白话**：`setAccessible(true)` 的意思是"我（这个调用者模块）请求绕过语言访问检查"。JDK 9 模块化之后，它不再是万能钥匙：对**别的模块未 open 的包**，`setAccessible(true)` 直接抛 `InaccessibleObjectException`。这就是为什么老代码升 JDK 后大量报错、需要 `--add-opens` 开门。

**源码证据**：`java.base/java/lang/reflect/AccessibleObject.java:270-330`（节选）：

```java
private boolean checkCanSetAccessible(Class<?> caller, Class<?> declaringClass, boolean throwExceptionIfDenied) {
    ...
    Module callerModule = caller.getModule();
    Module declaringModule = declaringClass.getModule();

    if (callerModule == declaringModule) return true;          // 同模块：随便
    if (callerModule == Object.class.getModule()) return true; // java.base：随便
    if (!declaringModule.isNamed()) return true;               // 目标在无名模块（类路径）：随便

    String pn = declaringClass.getPackageName();
    ...
    // 目标类 public 且所在包对调用者 exported → public 成员可访问
    if (isClassPublic && declaringModule.isExported(pn, callerModule)) { ... return true; }

    // 包对调用者 open → 全部可访问（含 private）
    if (declaringModule.isOpen(pn, callerModule)) { return true; }

    if (throwExceptionIfDenied) { throwInaccessibleObjectException(caller, declaringClass); }
    return false;
}
```

判定链总结：**同模块 → java.base → 目标无名模块 → exported（仅 public）→ open（全部）**，都过不去就抛 `InaccessibleObjectException`。因此：

- 对**类路径上的类**（无名模块），`setAccessible(true)` 依然畅通——框架改造成本主要在 JDK 自身；
- 对**命名模块**的私有成员，要么 `module-info` 里 `opens`，要么运行时加 `--add-opens <module>/<package>=ALL-UNNAMED`；
- `MethodHandles.privateLookupIn`（第三章）是模块化的另一把锁：拿到目标类"自己的"Lookup 才能访问私有成员，条件同样是 `opens`。

## 2.5 反射与注解：框架如何"读心"

注解本质是 class 文件属性表里的 `RuntimeVisibleAnnotations`，`Class.forName` 加载时被 JVM 解析成 `Annotation` 实例，反射 API 读到的只是**运行时生成的代理对象**（`sun.reflect.annotation.AnnotationInvocationHandler`）。这也解释了两个事实：

- `@Retention(RetentionPolicy.SOURCE)` 的注解反射读不到（字节里没有）；`CLASS` 的运行时也读不到（类加载时不进镜像）；
- 拿到注解后调用 `annotation.value()` 是走动态代理（第四章的 JDK Proxy）。

（框架在扫描场景为避免类加载，会绕开反射直接用 ASM 读属性表——Spring 的实现详见 5.6 案例剖析。）

（Spring 如何消费这些注解，见 [Spring Framework.md](../spring/Spring%20Framework.md) 第二章"注解的解析与落地"。）

## 2.6 反射的两条边界：final 字段与性能

**实验 ①（本机实测，jdk-27）**：反射改 `final` 字段——JDK 26 起（JEP 500）会打警告，但更早的问题其实在 javac：

```java
public class FinalFieldDemo3 {
    final int version = 1;              // 常量初始化的 final
    public static void main(String[] args) throws Exception {
        FinalFieldDemo3 o = new FinalFieldDemo3(9);
        Field f = FinalFieldDemo3.class.getDeclaredField("version");
        f.setAccessible(true);
        f.setInt(o, 2);
        System.out.println("direct read  = " + o.version);   // → 1 ！
        System.out.println("reflect read = " + f.getInt(o)); // → 2
        System.out.println("helper read  = " + Reader.read(o)); // → 1 ！（跨类读取）
    }
}
```

实测输出：

```
WARNING: Final field version in class FinalFieldDemo3 has been mutated reflectively by class FinalFieldDemo3 in unnamed module @4e25154f (file:/D:/tmp/bce-demo/)
WARNING: Use --enable-final-field-mutation=ALL-UNNAMED to avoid a warning
WARNING: Mutating final fields will be blocked in a future release unless final field mutation is enabled
direct read  = 1
reflect read = 2
helper read  = 1
```

两个结论：① 字节码层面 `javap -c` 显示 `o.version` 被编译成字面量 `iconst_1`——按 JLS 4.12.4，**常量表达式初始化的 final 变量（含实例字段）是"常量变量"，所有读取点在编译期被内联**，所以"改成功了但代码看不见"；② 用构造器赋值（非常量初始化）的 final 字段则一切正常（实测 `direct read = 2`），但三条 JEP 500 警告已经到站——**未来默认抛异常**。序列化库的官方出路是 `sun.reflect.ReflectionFactory`（JEP 500 页面明确点名）。

**实验 ②**：反射调用的性能——JDK 18 之后反射已是 MethodHandle 的马甲，"反射一定慢"的结论过时了；但 `checkAccess`（每次调用）和 `Object[]` 装箱依然存在，高频路径用 MethodHandle/VarHandle 仍是正解（见 3.6 对比表）。

## 2.7 本章小结

- 反射 = 运行时的"类镜像"查询 + 调用协议：`Class` 镜像在类加载时由 JVM 生成，查询走 native（`Class.java:3246-3248`），调用走 `MethodAccessor`（JDK 18 起为 MethodHandle 实现，JEP 416）。
- `setAccessible(true)` 是模块化条件判定（同模块 → exported → open），不是开关；`--add-opens` 是它的命令行等价物。
- 反射只观察不改造；它的两条历史遗留边界——final 字段变异与访问检查开销——分别由 JEP 500 警告和 MethodHandle 接手。
- 框架视角：Spring 的 `@Autowired`、MyBatis 的 Mapper、Jackson 的字段填充，第一脚都踩在 `getDeclaredXxx` + `setAccessible` 上，第二脚就换成了本章之后的技术（MethodHandle/代理/字节码生成）。

---

# 三、方法句柄与 VarHandle（java.lang.invoke）：反射的现代替身

## 3.1 为什么有了反射还要 MethodHandle

**白话**：反射天生是"元对象协议"——每次 `invoke` 都带着 `Method` 元数据、访问检查、`Object[]` 装箱。JDK 7 引入 JSR 292（invokedynamic + MethodHandle）时给了另一条路：**把"可调用的方法"也变成一个类型安全的对象（MethodHandle）**，它像函数指针一样直进执行引擎，享受 JIT 内联和参数特化，还能和 `invokedynamic` 字节码指令配合，把"决定调哪个方法"的时刻从编译期挪到运行期第一次执行时。

MethodHandle 没有替代反射，而是**绕到了反射的下面**：JDK 18 起（JEP 416），反射的底层实现就是 MethodHandle（见 2.3）；Lambda 表达式的实现（`LambdaMetafactory`）也是 MethodHandle 家族；第三章的主角 VarHandle 则是"字段访问版的 MethodHandle"。

## 3.2 Lookup：MethodHandle 的权限系统

**白话**：反射用 `setAccessible` 开门，MethodHandle 用**谁能拿到 Lookup、Lookup 拿的是谁的权限**来管门。`MethodHandles.lookup()` 返回"调用方所在类视角"的查找器；要访问别的类的私有成员，用 `privateLookupIn(targetClass, caller)` 换取目标类的私有 Lookup——条件是目标模块 `opens` 了这个包（与 `setAccessible` 同门规）。

**源码证据**：`java.base/java/lang/invoke/MethodHandles.java:240`：

```java
public static Lookup privateLookupIn(Class<?> targetClass, Lookup caller) throws IllegalAccessException
```

 Lookup 的查找模式对应一组 `findXxx` / `unreflectXxx` 工厂（`MethodHandles.java:509-553` 的规范表格给出了完整映射：`findVirtual`/`findStatic`/`findGetter`/`unreflect`/`unreflectSpecial`…）。`unreflectXxx` 家族可以直接把反射对象转成 MethodHandle——这是老框架渐进迁移的桥梁。

**使用示例（本机 jdk-27 实测）**——四种拿句柄/调句柄的姿势：

```java
import java.lang.invoke.*;

public class MethodHandleDemo {
    public static class Calculator {
        public int add(int a, int b) { return a + b; }
    }

    public static void main(String[] args) throws Throwable {
        MethodHandles.Lookup lookup = MethodHandles.lookup();

        // 1. findVirtual：按反射信息拿一个方法句柄（等价于"类型安全的 Method"）
        MethodHandle add = lookup.findVirtual(Calculator.class, "add",
                MethodType.methodType(int.class, int.class, int.class));

        // 2. invokeExact：参数/返回类型必须与 MethodType 完全一致，否则 WrongMethodTypeException
        Calculator calc = new Calculator();
        int r1 = (int) add.invokeExact(calc, 1, 2);

        // 3. bindTo：绑定接收者，变成"单参函数"——MethodHandle 的函数式玩法
        MethodHandle addBound = add.bindTo(calc);
        int r2 = (int) addBound.invokeExact(3, 4);

        // 4. unreflect：把反射 Method 转成 MethodHandle（老代码渐进迁移的桥）
        java.lang.reflect.Method m = Calculator.class.getMethod("add", int.class, int.class);
        MethodHandle unreflected = lookup.unreflect(m);
        Object r3 = unreflected.invokeWithArguments(calc, 5, 6);   // 通用 invoke，不做严格类型检查

        System.out.println("invokeExact = " + r1 + ", bindTo = " + r2 + ", unreflect = " + r3);
    }
}
```

实测输出：

```
invokeExact = 3, bindTo = 7, unreflect = 11
```

`invokeExact` 与 `invoke` 的区别值得记牢：前者在**调用点**就按 `MethodType` 严格校验（不匹配当场抛 `WrongMethodTypeException`，且强转类型写错会在运行期失败），后者允许适配转换。追求性能的框架代码几乎只用 `invokeExact`。

## 3.3 invokedynamic 与 LambdaMetafactory：JDK 自己的"运行时造类"

**白话**：你写的每一个 Lambda，编译产物里没有匿名内部类，只有一条 `invokedynamic` 指令 + 一个 bootstrap 方法引用。第一次执行到这条指令时，JVM 调用 `LambdaMetafactory.metafactory`，**现场生成一个实现函数式接口的类**（JDK 15 起生成为隐藏类），后续调用直接走这个类——"用到才生成、生成即特化"。

**源码证据**：

- 启动方法：`java.base/java/lang/invoke/LambdaMetafactory.java:339` `public static CallSite metafactory(MethodHandles.Lookup caller, String invokedName, MethodType invokedType, ...)`——注意第一个参数就是 `Lookup`，lambda 类的访问权限继承自捕获处的类。
- 生成逻辑：`InnerClassLambdaMetafactory.java:354`，`caller.makeHiddenClassDefiner(lambdaClassName, classBytes, ..., NESTMATE_CLASS | STRONG_LOADER_LINK)`——**JDK 15 起 lambda 类是隐藏类（JEP 371）**：不注册到任何 ClassLoader、不可被其他类显式引用、卸载不需要等 ClassLoader 死亡。这就是"Lambda 类为什么在 heap dump 里叫 `Foo$$Lambda/0x000...` 的原因"（`/0x...` 后缀是隐藏类的命名特征）。
- 同一机制还有个高频用户：字符串拼接。`+` 号在 JDK 9+ 编译成 `invokedynamic StringConcatFactory.makeConcatWithConstants`——2.6 节实验的字节码里就能看到（`javap -c FinalFieldDemo3` 的 `InvokeDynamic #0:makeConcatWithConstants`）。

**使用示例（本机 jdk-27 实测）**——验证"lambda 编译产物里没有匿名类"：

```java
import java.util.function.IntBinaryOperator;

public class LambdaDemo {
    public static void main(String[] args) {
        IntBinaryOperator max = (a, b) -> Math.max(a, b);      // 编译产物里没有匿名类
        System.out.println("max.applyAsInt(3, 9) = " + max.applyAsInt(3, 9));
        System.out.println("lambda class = " + max.getClass());
    }
}
```

```
max.applyAsInt(3, 9) = 9
lambda class = class LambdaDemo$$Lambda/0x0000000027040400    ← 隐藏类的命名特征
```

`javap -c LambdaDemo` 里，lambda 出现的位置只有一条 invokedynamic，没有任何 `class LambdaDemo$1`：

```
0: invokedynamic #7,  0    // InvokeDynamic #0:applyAsInt:()Ljava/util/function/IntBinaryOperator;
5: astore_1
```

第一次执行到这条指令时，JVM 调用 `LambdaMetafactory.metafactory`，由 3.2 节那套 MethodHandle 机制现场生成 `LambdaDemo$$Lambda/0x...` 隐藏类并绑定到 CallSite——后续调用直奔生成的类，不再走 bootstrap。

## 3.4 VarHandle：字段级的安全"Unsafe"

**白话**：`VarHandle` 是"指向某个字段（或数组元素）的强类型句柄"，提供两类东西：**原子性**（CAS、getAndAdd）和**内存序**（plain/opaque/acquire-release/volatile 四档）。JDK 9 的 JEP 193 引入它，就是为了给 `sun.misc.Unsafe` 的字段操作一个标准替代——juc 包的原子类、AQS 状态位等底层都迁到了它的语义上。

**源码证据**：

- 类型定义：`java.base/java/lang/invoke/VarHandle.java:474` `public abstract sealed class VarHandle implements Constable`——sealed 限定了它的实现只能来自 JDK 内部（`IndirectVarHandle`/`SegmentVarHandle`/按类型特化的 `VarHandleInts` 等），用户不能自定义。
- 多态签名：`VarHandle.java:642` `Object getVolatile(Object... args);`、`:833` `boolean compareAndSet(Object... args);`——签名是 `Object...`，但编译器按**多态签名（polymorphic signature）**处理：调用点的实参类型直接决定生成字节码的描述符，避免装箱。这是和 `MethodHandle` 共享的编译器魔法。
- 访问模式：`VarHandle.java:1745` `enum AccessMode`，把 `getVolatile`/`compareAndSet`/`getAndAdd`…（get/set/CAS 族/getAndXxx 族的全部访问方法）映射为统一的 `AccessMode` 枚举——这是给"通过 MethodHandle 访问 VarHandle"的路径用的。
- JIT 特化：`java/lang/invoke/VarHandleGuards.java:34`（文件头注明"generated by build.tools.methodhandle.VarHandleGuardMethodGenerator"）——按"访问模式 × 参数类型"组合预生成的守卫方法，让 `compareAndSet` 这类调用在 JIT 后退化成一条 `lock cmpxchg`。

**最小示例（本机 jdk-27 实测可运行）**：

```java
public class VarHandleDemo {
    private int balance;                      // 普通 int，不加锁、不声明 volatile
    private static final VarHandle BALANCE =
            MethodHandles.lookup().findVarHandle(VarHandleDemo.class, "balance", int.class);

    public boolean withdraw(int amount) {
        int prev;
        do {
            prev = (int) BALANCE.getVolatile(this);                       // volatile 语义读
            if (prev < amount) return false;
        } while (!BALANCE.compareAndSet(this, prev, prev - amount)); // CAS 写
        return true;
    }
}
// 运行输出：withdraw 30 -> true, balance=70 / withdraw 80 -> false, balance=70
```

四档内存序速查（对 `get`/`set` 都成立）：

| 模式 | 方法对 | 语义 |
|---|---|---|
| plain | `get` / `set` | 无额外约束（除单线程程序顺序） |
| opaque | `getOpaque` / `setOpaque` | 保证读到"最新写入"之一，不保证顺序 |
| acquire/release | `getAcquire` / `setRelease` | 写-读配对的 happens-before（生产者-消费者） |
| volatile | `getVolatile` / `setVolatile` | 完整 happens-before，最贵 |

**一个反直觉的源码事实**：打开 `java.util.concurrent.atomic.AtomicInteger`，`AtomicInteger.java:62` 里赫然是 `private static final Unsafe U = Unsafe.getUnsafe();`，`compareAndSet` 直接走 `U.compareAndSetInt(...)`（`AtomicInteger.java:137`）而不是 VarHandle。原因：juc 底层走的是 JVM **intrinsic**（`compareAndSetInt` 有内建指令实现），比 VarHandle 通用路径更快；VarHandle 的定位是**用户代码的标准替代品**，内部实现的特权路径是另一回事。讲解 VarHandle 时别拿"原子类用的就是它"举例——在 JDK 27 源码里这不成立（JDK 9 那几年短暂用过）。

## 3.5 Unsafe 的黄昏：JEP 471 / 498 / 500 三连

`sun.misc.Unsafe`（`jdk.unsupported` 模块）提供了 Cas、内存分配、字段偏移、对象实例化（`allocateInstance`，绕过构造器）等"后门"。它在 JDK 23（JEP 471）被整体标注 `@Deprecated(since="23", forRemoval=true)`——本机 `jdk-27` 源码 `jdk.unsupported/sun/misc/Unsafe.java:176` 等处可见；JDK 24 起（JEP 498）**第一次调用任何内存访问方法都会在运行时打警告**。本机实测（示例代码——这也是它曾经的典型用法：反射拿 `theUnsafe` 单例，然后绕开一切检查直接操作内存）：

```java
import sun.misc.Unsafe;
import java.lang.reflect.Field;

public class UnsafeDemo {
    public static void main(String[] args) throws Exception {
        Field f = Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);                       // Unsafe 的"标准打开方式"——本身就是反射破封装
        Unsafe u = (Unsafe) f.get(null);
        long addr = u.allocateMemory(16);            // 堆外内存：不受 GC 管理
        u.putInt(addr, 42);
        System.out.println("read back: " + u.getInt(addr));
        u.freeMemory(addr);
    }
}
```

运行输出：

```
WARNING: A terminally deprecated method in sun.misc.Unsafe has been called
WARNING: sun.misc.Unsafe::allocateMemory has been called by UnsafeDemo (file:/D:/tmp/bce-demo/)
WARNING: Please consider reporting this to the maintainers of class UnsafeDemo
WARNING: sun.misc.Unsafe::allocateMemory will be removed in a future release
read back: 42
```

迁移路线：内存访问 → **VarHandle** 或 **FFM API**（`java.lang.foreign`，JDK 22 正式，JEP 454）；绕构造器实例化 → Objenesis（其新后端用 FFM/方法句柄实现）；final 字段变异 → 见 2.6 的 JEP 500。`--sun-misc-unsafe-memory-access=warn|debug|deny`（JDK 23+）可以把警告调档甚至改成抛异常，用于提前演练"Unsafe 消失的那天"。

## 3.6 本章小结

- **MethodHandle** = 类型安全、可内联的方法引用；**Lookup** 是它的权限系统；`privateLookupIn` 对标 `--add-opens`。
- **invokedynamic + LambdaMetafactory** 让"运行时生成类"成为 JDK 语言特性的标准实现路径，JDK 15 起落地为隐藏类。
- **VarHandle** 把字段访问做成了四档内存序 + 原子操作的强类型 API，是 Unsafe 字段操作的标准接班人；但注意 juc 原子类内部走 Unsafe intrinsic，别混淆"推荐用法"与"内部实现"。
- 三者对比：

| 维度 | 反射 | MethodHandle | VarHandle | Unsafe |
|---|---|---|---|---|
| 定位 | 元数据查询 + 通用调用 | 高性能方法调用 | 字段访问/原子/内存序 | 无约束的后门 |
| 类型安全 | 弱（Object） | 强（MethodType 校验） | 强（字段类型校验） | 无 |
| JIT 友好 | 一般（JDK 18 后改善） | 好（可内联、特化） | 极好（intrinsic 级） | 好（intrinsic） |
| 权限模型 | setAccessible + --add-opens | Lookup + opens | Lookup | 无（这就是问题） |
| 未来 | 稳定（底层已统一到 MH） | 主力 | 主力 | deprecated for removal |

---

# 四、动态代理：JDK Proxy 与 CGLib

## 4.1 解决什么问题

反射能"调"一个已有的方法，代理则更进一步：**凭空造一个新类**，它的每个方法调用都被转发到你手里的处理器——拦截、记录、改参、换实现，全在这一层做。这是 AOP 的地基：事务、缓存、日志、重试之所以能"不侵入业务"，靠的就是调用方实际拿到的是代理对象。

Java 世界有两大主流：

| | JDK Proxy | CGLib Enhancer |
|---|---|---|
| 代理对象是 | 接口的实现类（`extends Proxy implements 接口`） | 目标类的**子类** |
| 要求 | 必须有接口 | 类与方法不能是 `final` |
| 拦截入口 | `InvocationHandler.invoke` | `MethodInterceptor.intercept` |
| 方法体 | 反射查表转发（见 4.2） | 生成方法体 + FastClass 索引直调 |
| 典型用户 | Spring AOP（有接口时）、RPC stub、`@Mapper` | Spring AOP（无接口时）、Mockito（2.x 前）、各类 ORM |

## 4.2 JDK Proxy：一次 newProxyInstance 发生了什么

**白话**：`Proxy.newProxyInstance(loader, interfaces, handler)` 做三件事——查缓存、没有就生成代理类字节码并 defineClass、反射调构造器传入 handler。代理类名字是 `$Proxy0`（编号递增），方法体全部是"查表 → `handler.invoke`"。

**源码证据**：`java.base/java/lang/reflect/Proxy.java`。

① 入口与缓存（`Proxy.java:918-928`、`389-399`）：

```java
public static Object newProxyInstance(ClassLoader loader, Class<?>[] interfaces, InvocationHandler h) {
    Objects.requireNonNull(h);
    Constructor<?> cons = getProxyConstructor(loader, interfaces);   // 查/生成代理类
    return newProxyInstance(cons, h);                                // 反射调 <init>(InvocationHandler)
}
// getProxyConstructor → proxyCache.sub(intf).computeIfAbsent(..., (ld, clv) -> new ProxyBuilder(ld, clv.key()).build())
```

缓存按 ClassLoader 分层（`ClassLoaderValue`），同一个加载器 + 同一组接口只生成一次类。

② 生成与定义（`Proxy.java:455-476`）：

```java
private static Class<?> defineProxyClass(ProxyClassContext context, List<Class<?>> interfaces) {
    ...
    byte[] proxyClassFile = ProxyGenerator.generateProxyClass(loader, proxyName, interfaces,
                                          context.accessFlags() | Modifier.FINAL | ClassFile.ACC_SUPER); // :470
    Class<?> pc = JLA.defineClass(loader, proxyName, proxyClassFile, null, "__dynamic_proxy__");  // :473
    ...
}
```

**注意两个与"网上旧资料"不同的点**：其一，代理类仍然是普通动态定义类（不是 hidden class）；其二，`ProxyGenerator` 在 JDK 27 已经**改用 JDK 自带 Class-File API** 生成字节码——`java.base/java/lang/reflect/ProxyGenerator.java:29` `import java.lang.classfile.*;`、`:63-64` `ClassFile.of(ClassFile.StackMapsOption.DROP_STACK_MAPS)`。JDK 自己吃了自己的狗粮（第五章详述 Class-File API）。

③ 生成的代理类长什么样（本机 jdk-21 下用 `-Djdk.proxy.ProxyGenerator.saveGeneratedFiles=true` 落盘后 `javap` 反编译，结构在 21/27 一致）：

```java
final class $Proxy0 extends java.lang.reflect.Proxy implements Greet {
    private static final java.lang.reflect.Method m0, m1, m2, m3;   // hashCode/equals/toString/hello
    public final int hashCode()  { return ((Integer)h.invoke(this, m0, null)).intValue(); }
    public final java.lang.String hello(java.lang.String arg) {
        try { return (String) h.invoke(this, m3, new Object[]{arg}); }
        catch (Error|RuntimeException e) { throw e; }
        catch (Throwable t) { throw new UndeclaredThrowableException(t); }
    }
    private static java.lang.invoke.MethodHandles$Lookup proxyClassLookup(...);  // 现代 JDK 新增
}
```

字节码层面（`javap -c`）：每个方法就是 `aload_0; getfield Proxy.h; ...; invokeinterface InvocationHandler.invoke`——**没有字节级拦截逻辑，全部依赖 `h.invoke` 的反射分派**。`proxyClassLookup` 是 JEP 416 之后的产物：代理类把自己的私有 Lookup 通过 MethodHandle 交给 `Proxy` 基类，用于快速读写 `h` 字段。

④ 运行示例（本机实测）：

```java
Greet g = (Greet) Proxy.newProxyInstance(loader, new Class<?>[]{Greet.class},
        (proxy, m, a) -> "[echo] " + a[0]);
g.hello("arthas");   // → "[echo] arthas"，g.getClass() = class $Proxy0
```

## 4.3 模块化时代的 Proxy：非公开接口代理不动

`defineProxyClass` 生成的代理类定义在"被代理接口所在的模块/包"里（这样包私有接口才能被实现）。于是模块系统带来一条硬约束（`Proxy.java` javadoc + `IllegalArgumentException` 路径）：**接口所在包必须对 `java.base` open**（`ProxyClassContext` 构造器校验 `module.isOpen(packageName, Proxy.class.getModule())`，`Proxy.java:433-435`）。对类路径上的普通类无感；对命名模块中未 open 的接口，`newProxyInstance` 直接抛异常。这是"框架+JPMS"迁移时除了 `--add-opens` 之外的第二类常见报错。

## 4.4 CGLib：用子类把方法体"换掉"

**白话**：CGLib（Code Generation Library，ASM 的老牌消费者）不走接口，而是**生成目标类的子类**：重写所有非 final 方法，方法体开头跳到你注册的 `MethodInterceptor`。它比 JDK Proxy 快的历史原因在于 **FastClass** 机制：为代理类和目标类各生成一个"方法索引器"，拦截后按 `index` 直接调用目标方法，省掉反射的元数据查找。

**使用示例（本机 jdk-27 + cglib-nodep 3.3.0 实测）**：

```java
import net.sf.cglib.proxy.*;

public class CglibDemo {
    public static class CglibTarget {
        public String hello() { return "real work"; }
    }

    public static void main(String[] args) {
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(CglibTarget.class);            // 子类化目标
        enhancer.setCallback((MethodInterceptor) (obj, method, params, proxy) -> {
            System.out.println("[cglib] before " + method.getName());
            Object ret = proxy.invokeSuper(obj, params);      // FastClass 按索引直调父类实现
            System.out.println("[cglib] after");
            return ret;
        });
        CglibTarget proxy = (CglibTarget) enhancer.create();  // 生成 CglibTarget$$EnhancerByCGLIB$$xxx
        System.out.println("proxy class = " + proxy.getClass().getName());
        System.out.println("intercepted hello() -> " + proxy.hello());
    }
}
```

在 JDK 27 上直接运行会先吃一张"模块化罚单"（JDK 17+ 的常态）：

```
Caused by: net.sf.cglib.core.CodeGenerationException:
  java.lang.reflect.InaccessibleObjectException-->Unable to make protected final java.lang.Class
  java.lang.ClassLoader.defineClass(...) accessible: module java.base does not "opens java.lang" to unnamed module
```

原因正是 2.4 节的判定链：CGLib 老版本靠 `setAccessible` 破封装调用 `ClassLoader.defineClass` 来定义生成类，而 `java.base/java.lang` 默认不 open。加 `--add-opens java.base/java.lang=ALL-UNNAMED` 后输出：

```
proxy class = CglibTarget$$EnhancerByCGLIB$$e265940e
is subclass of CglibTarget = true
[cglib] before hello
[cglib] after
intercepted hello() -> real work
```

另有两个版本兼容细节（均实测）：其一，目标类即便是 JDK 27 编译的 class 文件（major 71），本例也能正常增强——CGLib 读目标类元数据主要走反射；但它内置的 ASM 已过时、官方停更，复杂场景（泛型/桥方法解析走 ASM 读字节）可能踩版本墙——**这正是 Spring 把 CGLib fork 进 `org.springframework.cglib` 自行维护的原因之一**。其二，老版本定义生成类靠 `setAccessible` 破封装，在新 JDK 上必须加 `--add-opens java.base/java.lang=ALL-UNNAMED`（如上演示）——Spring 内置 fork 也因此同步跟进过相关适配。

硬边界：**final 类不能继承（直接抛异常），final 方法不能重写（拦截不到）**；构造器不受拦截（子类构造必然调用父类构造）。此外，生成子类意味着代理对象与目标对象是**两个不同实例**——Spring 用 Objenesis 绕过构造器实例化（`ObjenesisCglibAopProxy`），解决目标类没有无参构造器的场景。

**Spring 内置 fork 的证据**（本机 spring-framework 7.1.0-SNAPSHOT）：

- `spring-core/src/main/java/org/springframework/cglib/`——整套 `org.springframework.cglib`（含内置 `org.springframework.asm`），Spring 不依赖外部 cglib 包，而是随版本自行维护/修复；
- `spring-aop/src/main/java/org/springframework/aop/framework/CglibAopProxy.java:40-45`：`import org.springframework.cglib.proxy.Callback; import org.springframework.cglib.proxy.CallbackFilter;` 等；
- 代理选型：`DefaultAopProxyFactory.java:61-74`——`if (config.isOptimize() || config.isProxyTargetClass() || !config.hasUserSuppliedInterfaces())` 走 CGLib（`:71` `new ObjenesisCglibAopProxy(config)`），否则走 JDK 动态代理（`:69` `new JdkDynamicAopProxy(config)`）。

（这套选型如何被 `@EnableAspectJAutoProxy` 驱动、拦截器链如何执行，见 [Spring Framework.md](../spring/Spring%20Framework.md) 第五章。）

## 4.5 本章小结

- 代理的本质是"**生成一个新类 + 转发**"：JDK Proxy 生成接口实现（`ProxyGenerator` 用 Class-File API 产出字节码），CGLib 生成子类（ASM + FastClass）。
- JDK Proxy 的方法体是"反射查表转发"，CGLib 是"方法体重写 + 索引直调"——性能差异没有传说中大，选型主要看**有没有接口**和**要不要拦具体类**。
- 模块化对代理的影响：接口包必须 open 给 `java.base`；CGLib 则要求目标类可继承。
- Proxy 和 CGLib 都只做"转发"，**不改目标类字节码本身**——真要改类，进入第五、六章。

---

# 五、字节码工程库：ASM / Javassist / ByteBuddy / Class-File API

## 5.1 定位：所有"真·改字节码"工具的地基

代理只做转发；Instrumentation（第六章）只负责"把字节送进 JVM"。**真正把"想法"变成合法 class 字节的是本章的库**。它们分三个易用度层级：

```
易用度/抽象度 高  ┌──────────────────────────────┐
                 │ ByteBuddy（DSL + Advice）      │  Mockito / Hibernate / SkyWalking / OTel
                 │ Javassist（源码级 API）        │  老牌 Hibernate / JBoss 系
                 ├──────────────────────────────┤
                 │ Class-File API（JDK 自带）     │  JDK 24 正式；JDK 27 ProxyGenerator 在用
                 ├──────────────────────────────┤
                 │ ASM（visitor 字节级 API）      │  CGLib、ByteKit（Arthas）、大部分 Agent 的地基
低              └──────────────────────────────┘
```

四个库读写的是同一种东西——JVM 规范定义的 class 文件格式（魔数 `0xCAFEBABE`、常量池、方法表、属性表）。

## 5.2 ASM：一切的地基

**白话**：ASM 用**访问者模式**把 class 文件变成事件流：`ClassReader.accept(visitor)` 逐项"读出"（类头→字段→方法→每条指令），你写的 `ClassVisitor`/`MethodVisitor` 可以原样放行、也可以**改写或插入指令**后交给 `ClassWriter` 写回。它是事件流 + 指令级操作：最快、最省内存，但要自己维护常量池引用、栈帧（StackMapTable）一致性。

核心三件套（`org.objectweb.asm`，注意 Arthas 里是 `com.alibaba.deps.org.objectweb.asm`——见第七章的依赖隔离）：

- `ClassReader`：解析字节，驱动事件流；
- `ClassVisitor`/`MethodVisitor`：拦截与改写；
- `ClassWriter`：重组字节（`COMPUTE_FRAMES` 可自动重算栈帧）。

**使用示例（本机 jdk-27 + ASM 9.10.1 实测）**——给 `Target.hello()` 入口插桩，然后用自定义 ClassLoader 加载改写后的字节码调用：

```java
import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;
import java.nio.file.*;

public class AsmDemo {
    public static void main(String[] args) throws Exception {
        byte[] origin = Files.readAllBytes(Paths.get("targetclasses/Target.class"));

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(acc, name, desc, sig, ex);
                return name.equals("hello") ? new AdviceAdapter(Opcodes.ASM9, mv, acc, name, desc) {
                    @Override
                    protected void onMethodEnter() {              // ← 方法入口插桩点
                        mv.visitLdcInsn("Target");
                        mv.visitLdcInsn("hello");
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "Probe", "log",
                                "(Ljava/lang/String;Ljava/lang/String;)V");
                    }
                } : mv;
            }
        };
        new ClassReader(origin).accept(visitor, 0);   // 读 → 事件流 → 写
        byte[] rewritten = writer.toByteArray();

        // 用自定义 ClassLoader 定义改写后的类（生产上这就是 Agent/代理生成器的核心动作）
        Class<?> newCls = new ClassLoader(AsmDemo.class.getClassLoader()) {
            Class<?> define(byte[] b) { return defineClass("Target", b, 0, b.length); }
        }.define(rewritten);

        Object t = newCls.getDeclaredConstructor().newInstance();
        System.out.println("rewritten hello() -> " + newCls.getMethod("hello").invoke(t));
    }
}
```

```
[asm-probe] enter Target.hello          ← 插进去的代码先执行
rewritten hello() -> real work          ← 原方法体原样跟随
```

两个现场目击：① `AdviceAdapter` 在 `org.objectweb.asm.commons` 里，它把"方法进入/退出/异常"抽象成钩子（`onMethodEnter`），省去手写指令定位——CGLib 的拦截就是这个钩子的老用户；② 最初用 ASM 9.8 运行报 `Unsupported class file major version 71`——ASM 9.8 尚不认识 JDK 27 的 class 版本，换 9.10.1 才行。**用 ASM 的项目升级 JDK 时必须同步升 ASM**，这是硬依赖。

谁站在 ASM 上：**CGLib**（生成子类）、Arthas 的 **ByteKit**、字节码级 APM（Pinpoint 直接用 ASM）、**Spring 的类元数据读取**（"只读不写"的极致用法，见 5.6 案例剖析）。ASM 自身从 JDK 时代一路演进，跟随 class 文件版本持续更新。

## 5.3 Javassist：写给"Java 人"的字节码库

**白话**：Javassist（东京工业大学 Shigeru Chiba 的作品，1999 年起）用**源码级 API**包装字节码操作：`CtMethod.insertBefore("System.out.println(\"hi\");")`——直接写 Java 语句字符串，由它的编译器编译成字节码插进去。它自带独立的字节码编译引擎（不依赖 ASM）。代价是性能与灵活性弱于 ASM，动态字符串编译也不利于静态检查。老 Hibernate、JBoss Weld 曾重度使用；今天活跃度让位给 ByteBuddy。

**使用示例（本机 jdk-27 + javassist 3.30.2 实测）**：

```java
import javassist.*;
import java.lang.invoke.MethodHandles;

public class JavassistDemo {
    public static void main(String[] args) throws Exception {
        ClassPool pool = ClassPool.getDefault();                    // 类搜索路径池
        CtClass ct = pool.get("Target");                            // 拿到类的可编辑副本
        CtMethod hello = ct.getDeclaredMethod("hello");

        hello.insertBefore("{ System.out.println(\"[javassist] enter hello\"); }");   // 方法开头
        hello.insertAfter("System.out.println(\"[javassist] exit hello\");");          // 正常返回前
        ct.writeFile("javassist-out");                              // 可把改写结果落盘查看

        // 现代 API：传 Lookup 定义类；老 API ct.toClass() 内部用 setAccessible 破封装，
        // 在 JDK 17+ 会抛 InaccessibleObjectException（module java.base does not "opens java.lang"）
        Class<?> newCls = ct.toClass(MethodHandles.lookup());
        Object t = newCls.getDeclaredConstructor().newInstance();
        System.out.println("rewritten hello() -> " + newCls.getMethod("hello").invoke(t));
    }
}
```

```
[javassist] enter hello
[javassist] exit hello          ← insertAfter 的代码在"方法返回前"执行，早于调用方的打印
rewritten hello() -> real work
```

这次实测本身就是 2.4 节的现场复现：先用老 API `ct.toClass()` 跑，得到 `InaccessibleObjectException: module java.base does not "opens java.lang" to unnamed module`，换成传 `Lookup` 的新 API 才通过——**JPMS 时代，所有还在用 `setAccessible` 定义类的老库都欠一笔技术债**（CGLib 4.4 节刚演示过同款报错）。

## 5.4 ByteBuddy：现代 Java 的字节码 DSL

**白话**：ByteBuddy（2014 起）把"生成类/改方法"做成流式 DSL，并提供 **`Advice`** 注解——用普通 Java 方法描述"在目标方法进入/退出时执行什么"，由它在**编译期/装载期把 advice 方法体模板内联到目标方法**。它还提供 **`AgentBuilder`**：把"类名匹配 → 改写"直接包装成 `ClassFileTransformer`，第六章的 Agent 里可无缝嵌入。Mockito 2+、Hibernate 5.3+、Elastic APM、SkyWalking、OpenTelemetry Java Agent 的共同选择。

```java
new ByteBuddy().subclass(Foo.class)
        .method(named("hello"))
        .intercept(MethodDelegation.to(new MyInterceptor()))
        .make().load(Foo.class.getClassLoader(), ClassLoadingStrategy.Default.INJECTION)
        .getLoaded();
```

**使用示例（本机 jdk-27 + byte-buddy 1.17.5 实测）**——子类化拦截 + 注解绑定参数：

```java
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.*;
import net.bytebuddy.matcher.ElementMatchers;

public class ByteBuddyDemo {
    public static class Target {                          // 目标类（final 方法会无法拦截）
        public String hello() { return "real work"; }
    }

    public static class Interceptor {                     // 委托目标：普通 Java 方法，无接口强制
        @RuntimeType
        public Object intercept(@Origin String method,    // 方法签名注入
                                @SuperCall java.util.concurrent.Callable<?> zuper) throws Exception {
            System.out.println("[bytebuddy] before");
            Object ret = zuper.call();                    // 调父类原方法
            System.out.println("[bytebuddy] after");
            return ret;
        }
    }

    public static void main(String[] args) throws Exception {
        Class<?> proxy = new ByteBuddy()
                .subclass(Target.class)                                   // 子类化
                .method(ElementMatchers.named("hello"))                   // 匹配方法
                .intercept(MethodDelegation.to(new Interceptor()))        // 委托拦截
                .make()
                .load(Target.class.getClassLoader())
                .getLoaded();

        System.out.println("proxy class = " + proxy.getName());
        Object p = proxy.getDeclaredConstructor().newInstance();
        System.out.println("intercepted hello() -> " + proxy.getMethod("hello").invoke(p));
    }
}
```

```
proxy class = ByteBuddyDemo$Target$ByteBuddy$sScprNKN
[bytebuddy] before
[bytebuddy] after
intercepted hello() -> real work
```

注意与 ASM 示例（5.2）的心智差异：ByteBuddy 的拦截器是**普通 Java 方法**（用 `@Origin`/`@SuperCall` 注解声明要什么参数），不是指令级别的 visitor——这就是"DSL"的含义，也是它被 Mockito/Hibernate/APM 选中做公共依赖的原因。ByteBuddy 内部同样基于（shade 过的）ASM 做指令编排——DSL 解决"怎么写得舒服"，ASM 解决"怎么写进字节"。

## 5.5 Class-File API：JDK 自带的正统答案（JEP 484）

**白话**：JDK 24 正式（22 预览 JEP 457、23 二次预览 JEP 466）交付了 `java.lang.classfile`——官方字节码读写 API。设计上吸收了 ASM 的教训：不可变模型类 + 函数式 Transform（`ClassTransform`/`MethodTransform`/`CodeTransform`），自动处理常量池去重与栈帧生成，跟随 JVM 规范版本一同演进（ASM 需要等第三方适配新字节码版本，官方 API 不会）。

**源码证据（JDK 自用）**：JDK 27 的 `ProxyGenerator` 全文基于它构建（`ProxyGenerator.java:29` `import java.lang.classfile.*;`），说明 JDK 内部生成类已经统一到这条路上。

本文第六章的两个实战 Agent（静态插桩 + 动态 attach 热增强）**全部用 Class-File API 实现、零第三方依赖**，核心改写代码十行：

```java
var cf = ClassFile.of();
var model = cf.parse(bytes);                       // 解析成不可变模型
return cf.build(model.thisClass().asSymbol(), cb -> {
    for (ClassElement ce : model) {
        if (ce instanceof MethodModel mm && mm.methodName().equalsString("work")) {
            cb.transformMethod(mm, MethodTransform.transformingCode(
                    CodeTransform.ofStateful(() -> new CodeTransform() {
                        boolean injected = false;  // 每个方法一个状态实例
                        @Override
                        public void accept(CodeBuilder c, CodeElement e) {
                            if (!injected) {       // 方法第一条指令前 = onMethodEnter
                                injected = true;
                                c.ldc(target); c.ldc(method);
                                c.invokestatic(HOOK, "log", LOG_DESC);
                            }
                            c.with(e);             // 原指令放行
                        }
                    })));
        } else { cb.with(ce); }
    }
});
```

（完整可运行版本见 6.7。）

## 5.6 案例剖析：Spring 如何"不加载类"读注解——ASM 的杀手级应用

**白话**：`@ComponentScan` 启动时要在类路径上翻遍成千上万个 `.class` 文件，判断"谁上面标了 `@Component`/`@Service`"。如果对每个类都 `Class.forName` 再反射读注解，会付出三笔代价：**① 静态初始化块被执行**（扫描个配置类都能触发业务副作用）；**② 扫描被可选依赖炸掉**——某个类引用了 classpath 上不存在的库，加载即 `NoClassDefFoundError`，而"扫到它"本不该失败；**③ Metaspace 膨胀与类加载器污染**。Spring 的答案：**直接用 ASM 读 class 文件的属性表，整个 JVM 都不知道这个类的存在**。这就是 `spring-core` 的 `org.springframework.core.type` 体系（`MetadataReader`/`AnnotationMetadata`），本章 5.2 节 ASM 的最大规模实战。

### 5.6.1 源码链路：三个 SKIP 常量就是全部秘密

**源码证据 ①：入口只做"解析"不做"定义"**，`spring-core/src/main/java/org/springframework/core/type/classreading/SimpleMetadataReader.java:36-51`：

```java
final class SimpleMetadataReader implements MetadataReader {

    private static final int PARSING_OPTIONS =
            (ClassReader.SKIP_DEBUG | ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);   // :38-39

    SimpleMetadataReader(Resource resource, @Nullable ClassLoader classLoader) throws IOException {
        SimpleAnnotationMetadataReadingVisitor visitor = new SimpleAnnotationMetadataReadingVisitor(classLoader);
        getClassReader(resource).accept(visitor, PARSING_OPTIONS);          // ASM 事件流走一遍
        this.annotationMetadata = visitor.getMetadata();
    }
```

三个 SKIP 常量意味着 ASM 连方法体、栈帧、调试表都不看——只走"类头 + 字段/方法签名 + 注解属性表"。**全程没有 `defineClass`、没有 `Class` 对象**，注释里的异常文案（`:59-62`）甚至亲自提醒"报 ClassFormatException 多半是新 JDK 的 class 版本，请升级框架"——5.2 节的"ASM 版本追逐"在 Spring 源码里的官方回声。

**源码证据 ②：Visitor 收集什么**，`classreading/SimpleAnnotationMetadataReadingVisitor.java:77-135`：

- `visit(...)`（:78-89）：类名、access 修饰符、父类、接口——全部以 **String** 记录；
- `visitAnnotation(...)`（:112-115）：类注解 → 交给 `MergedAnnotationReadingVisitor`；
- `visitMethod(...)`（:118-127）：跳过桥方法和 `<init>` 构造器，只留"用户方法"的签名与注解；
- `visitEnd()`（:130-135）：组装成不可变的 `SimpleAnnotationMetadata`，注解集合用 `MergedAnnotations.of(...)` 包装。

**源码证据 ③：注解属性"只记名字"**，`classreading/MergedAnnotationReadingVisitor.java:70-75`：

```java
@Override
public void visit(String name, Object value) {
    if (value instanceof Type type) {
        value = type.getClassName();        // ← Class 类型属性被降级为"类名字符串"，不加载
    }
    this.attributes.put(name, value);
}
```

两个例外会"顺手加载"对应类（都便宜且有界）：枚举属性（`:102-109`，`ClassUtils.forName` 加载枚举类取常量）和嵌套注解（`:115-130`，加载注解接口本身——它是接口，加载零副作用）。

**源码证据 ④：属性被真正读取时**，`util/ClassUtils.java:312`：

```java
return Class.forName(name, false, clToUse);      // ← initialize = false！
```

`getAnnotationAttributes()` 把类名字符串还原成 `Class` 对象时，走的是**"加载但不初始化"**——被引用类的静态初始化块依然不会执行（实测见下）。

### 5.6.2 扫描主线与两步提速

扫描入口 `spring-context/.../annotation/ClassPathScanningCandidateComponentProvider.java:446-505`（`scanCandidateComponents`）：

1. `classpath*:basePackage/**/*.class` 枚举资源（`:449-450`），顺手跳过 CGLIB 生成类（`$$` 分隔符，`:456-458`）；
2. 对每个资源 `getMetadataReaderFactory().getMetadataReader(resource)`（`:464`）→ ASM 读元数据；
3. `isCandidateComponent(metadataReader)`（`:533`）：`AnnotationTypeFilter` 在**元数据**上匹配注解（含元注解/接口判定），全程零加载；
4. 命中即 `new ScannedGenericBeanDefinition(metadataReader)`（`:467`）——**BeanDefinition 装的是元数据，不是 Class 对象**；`@Conditional` 判定同样只看元数据（`:559`）；
5. 单个类解析失败不炸扫描：`ClassFormatException` 可配置忽略（`:486-495`，系统属性 `spring.classformat.ignore`）。

提速有两级：

- **第一级 · 缓存**：`CachingMetadataReaderFactory` 按 `Resource` 缓存 `MetadataReader`，默认 256 条（`CachingMetadataReaderFactory.java:42-48`）；
- **第二级 · 编译期索引**：`@Indexed` 注解 + 注解处理器在**构建期**生成 `META-INF/spring.components`（哪些类是组件、 stereotype 是什么）；运行时 `CandidateComponentsIndexLoader.loadIndex(...)`（`:268`）读索引，`findCandidateComponents` 直接查表返回（`:312-321`）——连 class 文件都不用摸。Spring 6+ 的 AOT 更进一步，把整个扫描搬到构建期。

### 5.6.3 使用示例（本机 jdk-27 + spring-core 7.0.9 实测）

```java
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.type.classreading.*;
import org.springframework.core.type.AnnotationMetadata;

public class MetadataDemo {
    public static void main(String[] args) throws Exception {
        MetadataReaderFactory factory = new SimpleMetadataReaderFactory();

        // 第一步：ASM 读 .class 文件（不 defineClass）
        MetadataReader reader = factory.getMetadataReader(
                new FileSystemResource("targetclasses/MetaTarget.class"));
        AnnotationMetadata meta = reader.getAnnotationMetadata();

        System.out.println("类名     = " + meta.getClassName());
        System.out.println("注解     = " + meta.getAnnotationTypes());
        System.out.println("注解属性 = " + meta.getAnnotationAttributes("MyComponent"));  // 此刻才解析 Class 属性

        System.out.println("—— 上面没有 [static init] 输出，说明 MetaTarget 从未被加载 ——");

        // 第二步：对照组，Class.forName 才是"真加载"
        System.out.println("\n现在调用 Class.forName(\"MetaTarget\")：");
        Class.forName("MetaTarget");
    }
}
```

其中 `MetaTarget` 长这样（静态块是"类被真正加载"的报警器；`OrderConfig` 是自定义类，用于观察 Class 属性的解析时机）：

```java
@MyComponent(name = "orderService", configClass = OrderConfig.class)
public class MetaTarget {
    static { System.out.println(">>> [static init] MetaTarget 被加载进 JVM 了！"); }
    public void doWork() { }
}
```

实测输出（三个层次的结论全在里面）：

```
类名     = MetaTarget
注解     = [MyComponent]
注解属性 = {configClass=class OrderConfig, name=orderService}
—— 上面没有 [static init] 输出，说明 MetaTarget 从未被加载 ——

现在调用 Class.forName("MetaTarget")：
>>> [static init] MetaTarget 被加载进 JVM 了！
```

| 阶段 | MetaTarget（被读的类） | OrderConfig（属性里引用的类） |
|---|---|---|
| ASM 读元数据（构造 MetadataReader） | 不加载、不初始化 | 不加载、不初始化 |
| `getAnnotationAttributes()` 读 Class 属性 | 不加载 | **加载、但不初始化**（`Class.forName(name,false,cl)`） |
| 对属性 Class 真正 `newInstance` | — | 此时静态块才执行（JVM 标准 lazy init） |

另有一个源码彩蛋：方法清单里混进了 `<clinit>`——类级 Visitor 只排除了构造器 `<init>` 和桥方法（`SimpleAnnotationMetadataReadingVisitor.java:122`），静态初始化块作为字节码里的"方法"被如实收集。对照实验：`Class.forName` 后 `>>> [static init]` 才出现，而 `getDeclaredMethods()` 全程"无感"——**这就是"字节码层元数据"与"JVM 层类对象"的分界线**。

### 5.6.4 与反射读注解的对照

| 维度 | `StandardAnnotationMetadata`（反射，基于已加载的 `Class`） | `SimpleMetadataReader`（ASM，基于 .class 文件） |
|---|---|---|
| 触发类加载 | 是（前置条件） | **否** |
| 静态初始化块 | 执行 | 不执行 |
| 可选依赖缺失 | `NoClassDefFoundError`，扫描中断 | 无感（只读属性表，不解析其它类） |
| Class 类型属性 | 直接是 `Class` 对象 | String 存放，读取时加载不初始化 |
| `@AliasFor`/元注解合并 | `MergedAnnotations.from(类)` 同一套 API | 同一套 API（`MergedAnnotations.of(...)`），语义一致 |
| 适用场景 | 单个已加载类（如 `BeanFactory` 后期处理） | 类路径扫描、`@Import` 解析、配置类早期处理 |

（`@Configuration` 解析为什么必须用元数据而非反射——配置类此时还不能加载，否则 `@Bean` 代理问题——见 [Spring Framework.md](../spring/Spring%20Framework.md) 第四章。）

### 5.6.5 小结

- Spring 读注解的"不加载"魔法 = **ASM 事件流 + SKIP_CODE + 属性表里的字符串**：类名、签名、注解属性全是 String，`Class` 对象按需、且以"加载不初始化"的方式惰性补齐。
- 它是 5.2 节 ASM"只读不写"的极致用法：不生成、不改写，只把 class 文件当**结构化数据库**查。
- 边界记住三条：枚举/嵌套注解属性会提前加载对应类；`@Retention(SOURCE)` 照样不可见（字节里根本没有）；框架升级要追 class 文件版本（`SimpleMetadataReader` 的异常文案就是为此而写）。

## 5.7 选型对比

| 维度 | ASM | Javassist | ByteBuddy | Class-File API |
|---|---|---|---|---|
| 抽象层级 | 指令/事件流 | Java 源码字符串 | DSL + Advice | 不可变模型 + Transform |
| 栈帧/常量池 | 需理解，可 COMPUTE_FRAMES | 自动 | 自动 | 自动 |
| 依赖 | 外部 jar | 外部 jar | 外部 jar | **JDK 内置（24+）** |
| 性能/体积 | 最优 | 较弱 | 好 | 好 |
| 典型用户 | CGLib、ByteKit、Pinpoint、**Spring 元数据读取（5.6）** | 老 Hibernate/JBoss | Mockito、Hibernate、SkyWalking、OTel | JDK 自身（ProxyGenerator） |
| 适合场景 | 极致性能、深度改写 | 快速原型/脚本 | 框架级增强、Agent | JDK 24+ 新项目、不想带依赖 |

选型一句话：**新 Agent / JDK 24+ 项目先考虑 Class-File API；要生态与 Advice 模型用 ByteBuddy；维护老代码按既成事实走 ASM；Javassist 见到再说。**

## 5.8 本章小结

- 字节码库解决"**怎么合法地写出/改出 class 字节**"：ASM 给出最底层的答案，Javassist/ByteBuddy 抬高抽象，Class-File API 让答案进了 JDK。
- 记住谁站在谁身上：CGLib→ASM、ByteKit(Arthas)→ASM、ByteBuddy→(shade)ASM、ProxyGenerator→Class-File API、**Spring 元数据读取→ASM（5.6 案例剖析）**。
- 字节码错误的暴露时机很晚（类加载/验证期），写库级代码务必配 `javap -c -v` 走查——本文所有字节码结论都经 javap 复核。

---

# 六、Instrumentation 与 Java Agent：JVM 官方的"改类契约"

## 6.1 解决什么问题

前面所有手段都要**你的代码先运行起来**。可如果需求是"给**别人**的进程加上监控、链路追踪、火焰图，一行业务代码都不改"，你需要的是 JVM 层的官方契约：**在类被加载的那一刻，允许你把类文件字节换成你改写过的版本**——这就是 `java.lang.instrument`（JSR 163，JDK 5）。

两种工作模式：

- **静态 Agent**：`-javaagent:xxx.jar` 随 JVM 启动，入口 `premain`——在 main 之前执行，此后**所有类加载都会经过你的 transformer**。SkyWalking、OpenTelemetry Java Agent 的用法。
- **动态 Agent**：进程已在运行，通过 **Attach API** 把 jar 注入进去，入口 `agentmain`，再对**已加载的类**调用 `retransformClasses` 触发重新变换。Arthas、JRebel 的用法。

## 6.2 Instrumentation 接口全景

**源码证据**：`java.instrument/java/lang/instrument/Instrumentation.java`（接口）与 `sun/instrument/InstrumentationImpl.java`（实现，注释明确写道 *"The Java side of the JPLIS implementation. Works in concert with a native JVMTI agent"*，`InstrumentationImpl.java:59-61`——JPLIS = Java Programming Language Instrumentation Services，Java 侧只是薄壳，真正干活的是 native JVMTI）。

| 方法 | 行号 | 作用 |
|---|---|---|
| `addTransformer(t)` / `addTransformer(t, canRetransform)` | `:114` / `:102` | 注册类文件变换器；canRetransform=true 才能参与运行时重变换 |
| `retransformClasses(Class<?>...)` | `:258` | 让已加载类**按原字节重新走一遍** transformer（Agent 加载后补挂的通道） |
| `redefineClasses(ClassDefinition...)` | `:344` | **直接替换**已加载类的方法体（绕过 transformer，给"热更新"用） |
| `isModifiableClass(Class<?>)` | `:376` | 类是否可被 redefine/retransform |
| `getAllLoadedClasses()` | `:388` | 枚举所有已加载类（Arthas 类搜索的原料） |
| `getObjectSize(Object)` | `:424` | 对象浅尺寸 |
| `appendToBootstrapClassLoaderSearch(JarFile)` | `:476` | **把 jar 塞进启动类加载器**——Arthas Spy 的注入通道（第七章） |
| `redefineModule(...)` | `:733` | 运行时给模块开洞（配合模块化 Agent） |
| `setNativeMethodPrefix(...)` | `:675` | 给 native 方法名加前缀（拦截 JNI 的手段） |

## 6.3 transform 的触发点：类加载管线中的位置

**白话**：你注册的 `ClassFileTransformer.transform(...)` 会在**每个类被 defineClass 之前**被调用，拿到原始字节数组，返回改写后的数组（返回 `null` 表示"不改"）。多个 transformer 按注册顺序串成链。

**源码证据**：native 侧在类加载路径上回调 Java 侧的 `InstrumentationImpl.transform`：

```java
// sun/instrument/InstrumentationImpl.java:570-600（节选）
private byte[] transform(Module module, ClassLoader loader, String classname,
                         Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
                         byte[] classfileBuffer, boolean isRetransformer) {
    TransformerManager mgr = isRetransformer ? mRetransfomableTransformerManager
                                             : mTransformerManager;
    ...
    return mgr.transform(module, loader, classname, classBeingRedefined,
                         protectionDomain, classfileBuffer);
}
```

`TransformerManager.java:69-74` 的注释解释了一个微妙设计：transformer 链内部刻意用**最小类集合**（数组而不是 ArrayList），因为这段代码运行在类定义系统内部，它引用的类不能被 transform。契约细节（`java/lang/instrument/ClassFileTransformer.java:118-140`）：**输入的 classfileBuffer 不可修改**；要改就返回新数组；不关心就返回 null。

## 6.4 retransformClasses 与 redefineClasses：两件不同的事

| | `retransformClasses` | `redefineClasses` |
|---|---|---|
| 字节来源 | **原始**类文件，重跑"retransform capable"的 transformer 链 | 调用者**直接提供**新字节 |
| 前置条件 | 注册过 `canRetransform=true` 的 transformer | `isRedefineClassesSupported()` |
| 典型用途 | Agent 注入后的"补挂"（Arthas watch/trace） | jad/mc/redefine 热更新、JRebel |
| 共同约束 | 见下方"结构性限制" | 同左 |

**结构性限制（JVM 硬约束，两类操作都适用）**：不能增删方法/字段、不能改方法签名、不能改继承关系、不能改类修饰符。能做的只有：**改方法体、改常量池、改属性**。这就是为什么 Arthas 从不"给类加字段"，所有状态都塞在方法体里的调用序列上（第七章）。`isModifiableClass`（`Instrumentation.java:376`）先过滤一轮——primitive、数组、`java.*` 部分核心类等不可改。

## 6.5 动态 Agent 与 Attach API：跨进程注入的全链路

**白话**：Attach 的意思是"附着到另一个 JVM 进程"。工具进程调用 `VirtualMachine.attach(pid)`，目标 JVM 里有个常驻的 **Attach Listener 线程**接受命令；`loadAgent(jar)` 命令让目标进程的 JPLIS native agent 去加载你的 jar 并调用 `agentmain`。

**源码证据（三层）**：

① 工具侧 API：`jdk.attach/com/sun/tools/attach/VirtualMachine.java:188` `public static VirtualMachine attach(String id)`；`:333` `loadAgentLibrary`、`:427` `loadAgentPath`。

② 关键转发——**`loadAgent` 的本质是加载 JPLIS 本体**：`sun/tools/attach/HotSpotVirtualMachine.java:156-168`：

```java
public void loadAgent(String agent, String options) ... {
    ...
    try {
        loadAgentLibrary("instrument", args);      // ← "instrument" 是 JVM 内的 JPLIS 库名！
    ...
}
```

也就是说：`loadAgent(my.jar)` = 目标 JVM 加载 `instrument` 动态库（如不存在则借 Attach 机制装载）→ JPLIS 读 jar 的 manifest 找 `Agent-Class` → 把 jar 加入目标进程系统类路径 → 反射调用 `agentmain(String args, Instrumentation inst)`。静态 premain 走的是同一段代码的 onLoad 变体（`InstrumentationImpl.java:544-566` 的 `loadClassAndCallPremain` / `loadClassAndCallAgentmain`，注释 *"WARNING: the native code knows the name & signature of this method"* 印证了 Java/native 的耦合点）。

③ 传输通道：Windows 上是命名管道。`jdk.attach/sun/tools/attach/VirtualMachineImpl.java:84-91`：

```java
// create a pipe using a random name
String pipeprefix = "\\.\pipe\javatool";
String pipename = pipeprefix + r;
hPipe = createPipe(props.version(), pipename);
```

（Linux 上是 `~/.java_pid<pid>` UNIX 域套接字 + `.attach_pid<pid>` 信号握手，同文件可查。）

④ **JEP 451 的到场**：JDK 21 起，动态加载 Agent 时目标进程会打警告（本机 jdk-27 实测原文）：

```
WARNING: A Java agent has been loaded dynamically (D:\tmp\bce-demo\dynagent.jar)
WARNING: If a serviceability tool is in use, please run with -XX:+EnableDynamicAgentLoading to hide this warning
WARNING: If a serviceability tool is not in use, please run with -Djdk.instrument.traceUsage for more information
WARNING: Dynamic loading of agents will be disallowed by default in a future release
```

 Arthas 等动态工具目前仍可用，但平台方向明确：动态注入需要用户**显式**开启（启动参数），"默默给生产进程打针"的时代在收尾。

## 6.6 能力协商的暗坑：manifest 属性名写错一个词，JVM 静默降级

**本机实测踩坑记录（极具代表性）**：给 agent jar 的 manifest 写了 `Can-Retransform: true`（想当然的属性名），运行时 `addTransformer(transformer, true)` 直接抛：

```
java.lang.UnsupportedOperationException: adding retransformable transformers is not supported in this environment
    at java.instrument/sun.instrument.InstrumentationImpl.addTransformer(InstrumentationImpl.java:139)
```

**源码解剖**（openjdk/jdk master，`java.instrument/share/native/libinstrument/`）：

- `InstrumentationImpl.addTransformer`（`InstrumentationImpl.java:137-140`）：`canRetransform=true` 时先查 `isRetransformClassesSupported()`，不支持即抛 UOE；
- Java 侧 `isRetransformClassesSupported` 最终问到 native（`JPLISAgent.c:1096-1097`）：

```c
jboolean isRetransformClassesSupported(JNIEnv * jnienv, JPLISAgent * agent) {
    return agent->mRetransformEnvironment.mIsRetransformer;   // retransform 环境创建后才为 true
}
```

- 这个专用 JVMTI 环境（带 `can_retransform_classes` 能力）**只在两处被创建**：`InvocationAdapter.c:117-119`——

```c
/* create an environment which has the retransformClasses capability */
if (getBooleanAttribute(attributes, "Can-Retransform-Classes")) {
    retransformableEnvironment(agent);
}
```

即 **Agent 启动时检查 manifest 属性 `Can-Retransform-Classes`（规范名！不是 `Can-Retransform`）**，命中才预创建 retransform 环境；或者运行期第一次 `retransformClasses` 时惰性创建（`JPLISAgent.c:1132`）。

结论：**manifest 必须写 `Can-Retransform-Classes: true`**（以及需要 redefine 时 `Can-Redefine-Classes: true`）。写错属性名 JVM 不报错，只是"安静地不支持"——这是 Agent 开发最阴的坑。正确的 manifest：

```
Premain-Class: com.demo.TimeAgent      # 静态入口（二选一或都写）
Agent-Class: com.demo.DynAgent         # 动态入口
Can-Retransform-Classes: true          # 允许参与 retransform
Can-Redefine-Classes: true             # 允许 redefine
Boot-Class-Path: bootstrap-helper.jar  # 需要进启动类加载器的辅助 jar（Arthas Spy 同款通道）
```

## 6.7 实战：零依赖写一个"方法探针"Agent（静态 + 动态，均经 jdk-27 实测）

下面两个完整示例只依赖 JDK（字节码改写用 5.5 的 Class-File API），合起来就是一座"迷你 Arthas"。

**① 静态 Agent（premain，随启动挂载）**

```java
// TimeAgent.java —— 在 Target 的每个方法入口插入 ProbeHook.log(class, method)
public class TimeAgent {
    static final ClassDesc HOOK = ClassDesc.of("ProbeHook");
    static final MethodTypeDesc LOG_DESC = MethodTypeDesc.of(CD_void,
            ClassDesc.of("java.lang.String"), ClassDesc.of("java.lang.String"));

    public static void premain(String args, Instrumentation inst) {
        inst.addTransformer((loader, className, beingRedefined, pd, bytes) -> {
            if (!"Target".equals(className)) return null;         // 只增强 Target
            var cf = ClassFile.of();
            var model = cf.parse(bytes);
            return cf.build(model.thisClass().asSymbol(), cb -> {
                for (ClassElement ce : model) {
                    if (ce instanceof MethodModel mm && !mm.methodName().equalsString("<init>")) {
                        cb.transformMethod(mm, MethodTransform.transformingCode(
                                CodeTransform.ofStateful(() -> new CodeTransform() {
                                    boolean injected = false;
                                    @Override public void accept(CodeBuilder c, CodeElement e) {
                                        if (!injected) {
                                            injected = true;
                                            c.ldc("Target"); c.ldc(mm.methodName().stringValue());
                                            c.invokestatic(HOOK, "log", LOG_DESC);
                                        }
                                        c.with(e);
                                    }
                                })));
                    } else { cb.with(ce); }
                }
            });
        });
    }
}
```

```java
// ProbeHook.java —— 探针回调（注意：它必须能被目标类加载器看到，随 agent.jar 一起在 -javaagent 里即可）
public class ProbeHook {
    public static void log(String cls, String method) {
        System.out.println("[probe] " + cls + "." + method + " @ " + System.nanoTime()
                + " thread=" + Thread.currentThread().getName());
    }
}
```

打包运行（本机实测输出）：

```
> jar cfm timeagent.jar manifest.mf ProbeHook*.class TimeAgent*.class
> java -cp . -javaagent:timeagent.jar App
[agent] transforming Target...
[probe] Target.hello @ 94961842546500 thread=main
  hello() real work
[probe] Target.hello @ 94961848889300 thread=main
  hello() real work
```

**② 动态 Agent（attach + agentmain + retransform，运行中生效）**

```java
// DynAgent.java —— agentmain：对已加载的指定类做入口插桩，然后 retransform 生效
public class DynAgent {
    public static void agentmain(String args, Instrumentation inst) throws Exception {
        // args 形如 "TargetApp$Worker;work"
        String[] p = args.split(";");
        String target = p[0], method = p[1];

        inst.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String className,
                    Class<?> beingRedefined, ProtectionDomain pd, byte[] bytes) {
                if (!target.equals(className)) return null;
                /* …… 与静态版相同的 Class-File API 插桩逻辑，略 …… */
            }
        }, true);                                   // canRetransform = true

        Class<?> targetClass = Arrays.stream(inst.getAllLoadedClasses())
                .filter(c -> c.getName().equals(target)).findFirst()
                .orElseThrow(() -> new IllegalStateException("not loaded: " + target));
        inst.retransformClasses(targetClass);       // ← 让运行中的类立刻换上新的方法体
        System.out.println("[dyn-agent] retransform done for " + target);
    }
}
```

```java
// Attacher.java —— 工具进程：两行完成跨进程注入
VirtualMachine vm = VirtualMachine.attach(pid);
vm.loadAgent("D:/tmp/bce-demo/dynagent.jar", "TargetApp$Worker;work");
vm.detach();
```

本机实测（目标进程 `work()` 每 2 秒打印一次）：attach 成功后目标输出变成——

```
[probe] TargetApp$Worker.work @ 95371307736600 thread=main     ← agentmain 注入生效
  work() doing real job #95371312814400
[probe] TargetApp$Worker.work @ 95373319172800 thread=main
  work() doing real job #95373319332800
```

**一个正在运行的循环方法被换掉了方法体**——这就是 Arthas `watch`/`trace` 的完整技术底座。manifest 踩坑（6.6）、JEP 451 警告（6.5）都是在这次实测中捕获的真实输出。

## 6.8 生态一览：谁在用 Instrumentation

| 工具 | 用法 | 字节码库 |
|---|---|---|
| SkyWalking | 静态 premain + AgentBuilder | ByteBuddy |
| OpenTelemetry Java Agent | 静态 premain | ByteBuddy |
| Pinpoint | 静态 premain | ASM（自封装插桩模板） |
| JRebel | 静态 + 动态、redefine 热更新 | 自研 + ASM |
| Arthas | 动态 attach + retransform | ASM + ByteKit（第七章） |
| IDEA 调试器 | **不走 Instrumentation**——用 JVMTI 的断点/单步事件 | — |

## 6.9 本章小结

- Instrumentation = JVM 官方承认的类文件变换通道：**premain（启动时）** 与 **agentmain（Attach）** 两个入口，`transform` 钩在 defineClass 之前，`retransformClasses` 让已加载类重新过链。
- 两条红线：① **结构性限制**——redefine/retransform 只能改方法体，不能增删成员；② **能力协商**——manifest 属性名必须精确（`Can-Retransform-Classes`），JVM 对写错的名字静默降级。
- 动态注入链路三层：工具进程 `VirtualMachine.attach`（Windows 命名管道/Linux 域套接字）→ 目标 JVM Attach Listener → JPLIS `instrument` 库加载 agent jar 调 `agentmain`。
- JEP 451 之后，动态 Agent 默认带警告，"未来默认禁止"已写进官方文档；生产上要规划 `-XX:+EnableDynamicAgentLoading` 或改静态挂载的路径。

---

# 七、案例剖析：Arthas 如何在运行时"篡改"字节码

## 7.1 Arthas 是什么

Arthas 是阿里巴巴 2019 年开源的 Java 诊断工具（作者 hengyunabc/vlinux 等，前身为淘宝内部工具 **Greys**），它最"魔法"的能力是：**不重启、不改代码，attach 上一个正在运行的 JVM，就能 watch 任意方法的入参出参、trace 任意调用链、tt 记录现场、甚至 jad 反编译改完再 redefine 回去**。本章把前六章的技术全部串起来，逐层拆开这条链路。

本章引用的 Arthas 源码（master 分支，2026-10 抓取）：

- `agent/.../agent334/AgentBootstrap.java` —— agent 入口
- `core/.../server/ArthasBootstrap.java` —— 服务端装配（1063 行）
- `core/.../advisor/Enhancer.java` —— 字节码增强器（761 行，`implements ClassFileTransformer`）
- `core/.../advisor/SpyInterceptors.java` / `SpyImpl.java` —— 插桩模板与分发
- `spy/src/main/java/java/arthas/SpyAPI.java` —— 被插进目标代码里的"钉子"
- `core/.../command/monitor200/WatchCommand.java` —— 用户命令

## 7.2 整体架构：一次 `watch` 命令的七步旅程

```
 ┌──────────────┐   ①attach(pid)     ┌────────────────────────────────────────────┐
 │ arthas 客户端 │ ─────────────────► │  目标 JVM                                   │
 │ (arthas-boot)│   ②loadAgent(jar)  │                                            │
 └──────┬───────┘                    │  ③AgentBootstrap.premain/agentmain          │
        │ telnet/http 3658           │      └─ ArthasClassloader 隔离加载 arthas-core│
        ▼                            │  ④ArthasBootstrap：Spy 塞进 bootstrap CL     │
 ┌──────────────┐                    │      + Netty http/telnet 服务               │
 │  watch 命令   │ ◄──────────────► │  ⑤Enhancer implements ClassFileTransformer   │
 │ (WatchCommand)│    ⑦结果回传      │      ├─ ASM+ByteKit 改写方法体               │
 └──────────────┘                    │      └─ instrumentation.retransformClasses  │
                                     │  ⑥方法被调用 → SpyAPI.atEnter → AdviceListener│
                                     └────────────────────────────────────────────┘
```

## 7.3 第一步：跨进程注入（attach → agentmain）

用户运行 `arthas-boot`，它列出 JVM 进程让你选一个 pid，然后就是标准的 Attach API（第六章）：

```java
VirtualMachine vm = VirtualMachine.attach(pid);
vm.loadAgent(arthasAgentJar, arthasCoreJar + ";" + options);
```

目标 JVM 侧，JPLIS 加载 agent jar 后调用 `com.taobao.arthas.agent334.AgentBootstrap`：

```java
// agent334/AgentBootstrap.java:63-69
public static void premain(String args, Instrumentation inst)  { main(args, inst); }
public static void agentmain(String args, Instrumentation inst){ main(args, inst); }
```

`main` 里有两个关键动作（`AgentBootstrap.java:90-174`）：

1. **防重复注入**（`:93-98`）：`Class.forName("java.arthas.SpyAPI")`——如果 SpyAPI 已经加载且 `INITED`，说明 Arthas 在运行，直接跳过（"Arthas server already stared, skip attach."）。
2. **类加载器隔离**（`:83-88`）：

```java
private static ClassLoader loadOrDefineClassLoader(File arthasCoreJarFile) throws Throwable {
    if (arthasClassLoader == null) {
        arthasClassLoader = new ArthasClassloader(new URL[]{arthasCoreJarFile.toURI().toURL()});
    }
    return arthasClassLoader;
}
```

arthas-core 的全部类装进一个独立 URLClassLoader，**与业务类加载器、应用服务器类加载器互不污染**；卸载时置 null 即可整体回收（`:74-76` `resetArthasClassLoader`）。随后 `bind()`（`:176-191`）反射调用 `com.taobao.arthas.core.server.ArthasBootstrap.getInstance(inst, args)` 启动服务端，Netty 起 http/telnet 端口（默认 3658/8563）。

## 7.4 第二步：Spy 塞进启动类加载器——整条链路最精妙的一步

**问题**：Enhancer 即将改写业务类字节码，插入的代码要调用 Arthas 的类（SpyAPI）。可业务类由业务类加载器加载，Arthas 类在 arthasClassLoader 里——**业务类凭什么看得见 Arthas 的类？**

**答案**：把 SpyAPI 所在的 `arthas-spy.jar` **追加到启动类加载器**（第六章 `appendToBootstrapClassLoaderSearch` 的实战用法），按双亲委派，任何类加载器都能加载到它：

```java
// server/ArthasBootstrap.java:216-235（initSpy，节选）
ClassLoader parent = ClassLoader.getSystemClassLoader().getParent();   // ← bootstrap loader
Class<?> spyClass = null;
if (parent != null) {
    try { spyClass = parent.loadClass("java.arthas.SpyAPI"); } catch (Throwable e) { /* ignore */ }
}
if (spyClass == null) {
    ...
    File spyJarFile = new File(arthasCoreJarFile.getParentFile(), ARTHAS_SPY_JAR);
    instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(spyJarFile));   // ← 注入
}
```

**附加巧思**：SpyAPI 的包名是 `java.arthas`（`SpyAPI.java:1` `package java.arthas;`）——以 `java.` 开头的包在规范上只能由 bootstrap 类加载器定义，这个包名**强制**了"谁定义、谁唯一"，杜绝了多个类加载器各自加载一份 SpyAPI 导致"插桩代码调用不到同一个静态字段"的经典灾难。同时 Enhancer 在改写前还有一道防线（`Enhancer.java:153-162`）：先试 `inClassLoader.loadClass(SpyAPI.class.getName())`，加载不到的类加载器直接放弃增强。

## 7.5 第三步：watch 命令 → 字节码改写 → retransform 生效

用户敲 `watch com.demo.OrderService create '{params,returnObj}'`，WatchCommand（`monitor200/WatchCommand.java:35`，继承 EnhancerCommand）构造一个 `Enhancer` 并调 `enhance(inst)`：

```java
// advisor/Enhancer.java:73
public class Enhancer implements ClassFileTransformer {
    ...
    // Enhancer.java:639-700（节选）
    public synchronized EnhancerAffect enhance(final Instrumentation inst, ...) {
        this.matchingClasses = SearchUtils.searchClass(inst, classNameMatcher);   // ① getAllLoadedClasses 里按名匹配
        ...
        ArthasBootstrap.getInstance().getTransformerManager().addTransformer(this, isTracing);  // ② 注册
        ...
        inst.retransformClasses(classArray);                                      // ③ 触发重变换
    }
```

三步齐活：**搜索已加载类（`Instrumentation.getAllLoadedClasses`）→ 把自己注册为 transformer → `retransformClasses` 让 JVM 把这些类的原始字节重放给 transformer 链**。随后 `transform(...)`（`Enhancer.java:150-...`）被逐类回调：

```java
// Enhancer.java:193-205（节选）
ClassNode classNode = new ClassNode(Opcodes.ASM9);
ClassReader classReader = AsmUtils.toClassNode(classfileBuffer, classNode);
classNode = AsmUtils.removeJSRInstructions(classNode);        // 兼容旧字节码（issue #1304）
DefaultInterceptorClassParser defaultInterceptorClassParser = new DefaultInterceptorClassParser();
final List<InterceptorProcessor> interceptorProcessors = new ArrayList<>();
interceptorProcessors.addAll(defaultInterceptorClassParser.parse(SpyInterceptor1.class));
interceptorProcessors.addAll(defaultInterceptorClassParser.parse(SpyInterceptor2.class));
interceptorProcessors.addAll(defaultInterceptorClassParser.parse(SpyInterceptor3.class));
...
MethodProcessor methodProcessor = new MethodProcessor(classNode, methodNode, groupLocationFilter);
for (InterceptorProcessor interceptor : interceptorProcessors) {
    interceptor.process(methodProcessor, ...);                // 真正的插桩
}
```

注意依赖：`com.alibaba.bytekit.*`（**ByteKit**，阿里开源的 ASM 上层插桩库）+ `com.alibaba.deps.org.objectweb.asm.*`（**repackaged ASM**，`Enhancer.java:25-32`）——**Arthas 3.4 起（2020-06 重构，SpyInterceptors 文件头可证）的插桩内核就是 ByteKit**，更早版本直接裸写 ASM 的 InterceptorProcessor（Greys 血统）。若某方法已经被插过桩，`Enhancer.java:295` 用 `AsmUtils.containsMethodInsnNode(methodNode, "java/arthas/SpyAPI", "atBeforeInvoke")` 检测后跳过，避免重复插桩。

**真实会话（本机实测：Arthas 4.3.5，attach 到本机 jdk-27 运行的目标进程）**：

```
> java -jar arthas-boot.jar 30304 -c "watch 'TargetApp$Worker' work '{params, returnObj}' -n 2"
[INFO] Attach process 30304 success.                                        ← 7.3 的 attach 链路
[arthas@30304]$ watch 'TargetApp$Worker' work '{params, returnObj}' -n 2
Press Q or Ctrl+C to abort.
Affect(class count: 1 , method count: 1) cost in 61 ms, listenerId: 1       ← Enhancer 改写 1 类 1 方法并 retransform
method=TargetApp$Worker.work location=AtExit                                ← AtExit 钉子被触发
ts=2026-10-05 11:17:01.956; [cost=3.463ms] result=@ArrayList[
    @Object[][isEmpty=true;size=0],
    null,
]
Command execution times exceed limit: 2, so command will exit.
```

`trace` 命令（展示调用树与耗时，靠 `@AtInvoke` 钉子统计每个内部调用）：

```
[arthas@30304]$ trace 'TargetApp$Worker' work -n 1
Affect(class count: 1 , method count: 1) cost in 18 ms, listenerId: 2
`---ts=2026-10-05 11:17:27.981;thread_name=main;id=3;is_daemon=false;priority=5;TCCL=jdk.internal.loader.ClassLoaders$AppClassLoader@2f6cfdf6
    `---[0.3688ms] TargetApp$Worker:work()
```

对照源码读这两段输出：`Affect(class count: 1, method count: 1)` 是上面那套 Enhancer+retransform 流程的对外呈现；`location=AtExit` 对应 `SpyInterceptor2` 的 `@AtExit` 插桩点；trace 输出里的线程信息与 TCCL（线程上下文类加载器）也是 `@Binding` 注入的产物。

**一个值得记录的兼容性现场**：目标类若用 JDK 27（class 文件 major 71）编译，Arthas 4.3.5 当场插桩失败——`Enhance error! exception: java.lang.IllegalArgumentException: Unsupported class file major version 71`（其内置 ASM 尚未适配新版本）；把目标类改用 `--release 21` 编译后一切正常。**工具的 attach 能力与字节码解析能力是两回事**：前者跟随 JVM 版本，后者受制于生态适配（与 5.2 的 ASM 版本追逐同源）。

## 7.6 第四步：插进去的代码长什么样

ByteKit 的插桩模板是**注解标注的普通静态方法**（`advisor/SpyInterceptors.java:23-40`）：

```java
public static class SpyInterceptor1 {
    @AtEnter(inline = true)
    public static void atEnter(@Binding.This Object target, @Binding.Class Class<?> clazz,
            @Binding.MethodInfo String methodInfo, @Binding.Args Object[] args) {
        SpyAPI.atEnter(clazz, methodInfo, target, args);
    }
}
public static class SpyInterceptor2 {
    @AtExit(inline = true)
    public static void atExit(..., @Binding.Return Object returnObj) {
        SpyAPI.atExit(clazz, methodInfo, target, args, returnObj);
    }
}
public static class SpyInterceptor3 {
    @AtExceptionExit(inline = true)
    public static void atExceptionExit(..., @Binding.Throwable Throwable throwable) { ... }
}
```

`inline = true` 意味着 ByteKit 把这个方法体**内联复制**到目标方法的进入/正常退出/异常退出点（还有 `@AtInvoke` 拦方法调用、`@AtLine` 拦行号，用于 trace 与 tt）。所以改写后的 `OrderService.create` 等效于：

```java
public Order create(...) {
    SpyAPI.atEnter(OrderService.class, "create;(Ljava/lang/String;)V", this, new Object[]{...}); // ← 插入
    try {
        /* 原方法体 */
        SpyAPI.atExit(OrderService.class, "create;(...)", this, args, ret);                      // ← 插入
        return ret;
    } catch (Throwable t) {
        SpyAPI.atExceptionExit(OrderService.class, "create;(...)", this, args, t);               // ← 插入
        throw t;
    }
}
```

对比第四章：Spring AOP 是"造新类做代理"，Arthas 是"把探针缝进原方法体"——所以它**不需要接口、不在乎 final、不影响调用方拿到的对象类型**，这正是它作为诊断工具的价值。

## 7.7 第五步：Spy 的"开关"——为什么平时零感知

被插进所有方法的调用是 `SpyAPI.atEnter(...)`（`SpyAPI.java:58-70`），它的实现极薄：

```java
// spy/java/arthas/SpyAPI.java:23-35
public class SpyAPI {
    public static final AbstractSpy NOPSPY = new NopSpy();
    private static volatile AbstractSpy spyInstance = NOPSPY;    // ← 一个可热插拔的引用
    ...
    public static void atEnter(Class<?> clazz, String methodInfo, Object target, Object[] args) {
        spyInstance.atEnter(clazz, methodInfo, target, args);    // ← 单次虚调用
    }
```

- **没开任何命令时**：`spyInstance` 是空实现的 `NopSpy`（`SpyAPI.java:109-146`，所有方法为空），插桩代码的开销≈一次静态读 + 一次虚调用；
- **watch 命令激活时**：Arthas 启动即执行 `Enhancer` 的静态块 `SpyAPI.setSpy(spyImpl)`（`Enhancer.java:95-99`），`SpyImpl`（`advisor/SpyImpl.java:32-56`）按 `classLoader + 类名 + 方法名` 去 `AdviceListenerManager` 查**注册了监听的** listener 列表，逐个回调 `adviceListener.before/afterReturning(...)`——watch/trace/tt 的数据采集全在这些 listener 里；
- **命令结束时**：反注册 listener；uninstall 时 `SpyAPI.setNopSpy(); SpyAPI.destroy();`（`ArthasBootstrap.java:984-989`），探针回到"空转"。

这套"**字节码里埋钉子 + 运行时换钉子背后的实现**"模式，让 Arthas 可以反复 attach/detach、并行跑多个命令，而无需每次都重新改字节码。
## 7.8 redefine 通道：jad / mc / redefine 的热更新与它的边界

`watch/trace` 走 retransform；`jad`（CFR 反编译）→ `mc`（内嵌 Eclipse JDT 编译器内存编译）→ `redefine`（`Instrumentation.redefineClasses`）走的是**直接换方法体**通道（`ClassDefinition`，`Instrumentation.java:344`）。它同样受 6.4 的结构性限制：**只能改方法体**。改了方法签名、加了字段，JVM 拒绝并抛错。要真热部署（增删结构），得退到 JRebel 级别的手段——用额外的类加载器重新加载整个类簇，代价是元空间与一致性风险。这也是"Arthas 热更新只适合改逻辑 bug"的根本原因。

## 7.9 本章小结（并用 mini-Arthas 实测印证）

Arthas 七步链路：**attach → loadAgent → AgentBootstrap（隔离类加载器）→ Spy 注入 bootstrap → Enhancer 注册 transformer → retransformClasses → 方法调用时经 SpyAPI 分发到 AdviceListener**。本机 6.7 的两个实测 Agent 复刻了这条链的全部关节（attach+agentmain+Class-File API 插桩+retransform 热生效），输出一致。

三个值得带走的设计思想：

1. **钉子与开关分离**：字节码里只埋最薄的 `SpyAPI.atXxx` 钉子，逻辑全在可热插拔的 listener 侧——改探针逻辑不用重改字节码；
2. **类加载器是隔离舱**：Arthas 自身在 URLClassLoader 里，Spy 在 bootstrap 里，两者靠双亲委派汇合；
3. **所有"超能力"都源自官方契约**：attach（JDK 6）、transform（JDK 5）、retransformClasses（JDK 6）——Arthas 没有碰任何 JVM 内部 API，这是它能横跨 JDK 6~27 的根本原因。

---

# 八、贯通视图：一张图看懂 Java 代码增强

## 8.1 全手段总表

| 手段 | 层次 | 生效时机 | 能做什么 | 做不到什么 | 典型用户 |
|---|---|---|---|---|---|
| 反射 | 观察调用 | 任意 | 读结构、调方法、读写字段（受模块约束） | 改行为；final 字段变异已被警告（JEP 500） | Spring、Jackson、MyBatis |
| MethodHandle | 调用 | 任意 | 类型安全高性能调用、跨模块受控访问 | 需 Lookup 权限 | Lambda、JDK 内部、现代框架 |
| VarHandle | 字段访问 | 任意 | 四档内存序 + CAS/原子读改写 | 不改行为、不碰对象布局 | 无锁数据结构、juc 用户侧 |
| sun.misc.Unsafe | 后门 | 任意 | 全部（无约束） | — 已 deprecated for removal（JEP 471/498） | 遗留框架内部 |
| JDK 动态代理 | 生成 | 首次调用 newProxyInstance | 代理接口全部方法 | 只能接口；不能拦具体类 | Spring AOP、RPC、@Mapper |
| CGLib | 生成 | Enhancer.create | 子类化拦截具体类方法 | final 类/方法、构造器 | Spring AOP（无接口） |
| ASM/ByteBuddy/Javassist/Class-File API | 字节工程 | 构建期或运行时 | 任意合法 class 字节 | 错误要等加载/验证期才暴露 | 一切上层工具的地基 |
| Java Agent（premain） | 类加载篡改 | JVM 启动 | 在所有类加载前改字节 | 对已加载类无效 | SkyWalking、OTel Agent |
| Agent（agentmain）+ Attach | 运行时篡改 | 运行中 | 补挂 transformer、retransform | 结构性限制；JEP 451 警告 | Arthas、JRebel |
| redefineClasses | 运行时篡改 | 运行中 | 直接换方法体（jad/mc/redefine） | 不能增删成员、改签名 | Arthas 热更新 |

## 8.2 生态地图：谁站在谁的肩膀上

```
                          ┌──────────── Spring AOP ────────────┐
                          │ DefaultAopProxyFactory 二选一       │
                          │  ├ 有接口 → JDK Proxy               │
                          │  └ 否则  → CGLib(内置fork)+Objenesis│
                          └────────────────┬───────────────────┘
   Mockito/Hibernate/SkyWalking/OTel ──► ByteBuddy ──┐
   CGLib ──► ASM ◄───────────────────────────────────┤
   Arthas ──► ByteKit ──► ASM ◄──────────────────────┤
   JDK ProxyGenerator ──► Class-File API ◄────────────┘
                          （互不相干的顶层，同一种字节）
```

（Spring 侧的源码级展开见 [Spring Framework.md](../spring/Spring%20Framework.md) 第五章；juc/VarHandle 与并发语境见 [Java并发.md](Java并发.md)。）

## 8.3 选型决策树

```
需求是什么？
├─ 运行时读结构/调方法 → 反射；高频路径换 MethodHandle
├─ 字段的原子/有序访问 → VarHandle
├─ 拦截调用
│   ├─ 有接口 → JDK Proxy
│   └─ 无接口/拦具体类 → CGLib 或 ByteBuddy（注意 final）
├─ 全新字节码 → Class-File API（JDK 24+）优先，生态集成选 ByteBuddy
├─ 全链路无侵入采集 → 静态 Java Agent（premain）
├─ 生产问题现场诊断 → Arthas（动态 attach）
└─ 真·热替换类结构 → 没有官方干净方案（类加载器整体替换，慎重）
```

## 8.4 安全、限制与未来：四道收权的堤坝

1. **JPMS（JDK 9，JEP 260/403）**：内部 API 默认封死，反射私有成员需 `opens`/`--add-opens`。代理受其约束（接口包要 open）。
2. **动态 Agent 警告（JDK 21，JEP 451）**：attach 注入默认警告，未来默认禁止。工具方要么要求用户显式开启，要么转静态挂载。
3. **Unsafe 退场（JDK 23/24，JEP 471/498）**：deprecated for removal + 首调警告，替代者 VarHandle/FFM（JEP 454）均已就位。
4. **final 语义（JDK 26，JEP 500）**：反射改 final 字段默认警告、未来默认异常，序列化走 `ReflectionFactory`。

四道堤坝的共同纲领是 **"Integrity by Default"**——默认完整可信、越权必须显式声明。对应地，Class-File API（JEP 484）是平台"收编"字节码生态的正向一步：官方维护格式解析、跟随规范演进、把第三方从"追赶字节码版本"的宿命里解放出来。

**对开发者的实践建议**：新代码面向 `VarHandle`/`MethodHandle`/`Class-File API`；老依赖排查 `Unsafe` 使用（`--sun-misc-unsafe-memory-access=debug` 打印调用栈）；升级 JDK 前用 JEP 451/500 的警告输出当"兼容性扫描器"；写 Agent 时牢记 manifest 属性名与结构性限制（6.6/6.4）。

---

# 九、附录

## 附录 A 本文验证环境与实测清单

| 实验程序（D:\tmp\bce-demo） | 验证结论 | 关键输出 |
|---|---|---|
| `FinalFieldDemo3` + `Reader` | JEP 500 警告原文；常量变量折叠（JLS 4.12.4）导致 Java 读旧值、反射读新值 | 三行 WARNING；`direct read = 1 / reflect read = 2` |
| `VarHandleDemo` | VarHandle CAS/volatile 读的用法与行为 | `withdraw 30 -> true, balance=70` |
| `ProxyApp`（jdk-21 落盘 + javap） | `$Proxy0` 结构、`h.invoke` 转发、`proxyClassLookup` | 见 4.2 ③ |
| `UnsafeDemo` | JEP 498 Unsafe 首调警告原文 | 四行 WARNING + `read back: 42` |
| `agent/`（TimeAgent + ProbeHook） | 静态 premain + Class-File API 插桩 | `[probe] Target.hello @ ...` |
| `attach/`（DynAgent + Attacher + TargetApp） | attach → agentmain → retransform 运行中生效；JEP 451 警告 | `[probe] TargetApp$Worker.work @ ...` |
| manifest 踩坑（Can-Retransform vs Can-Retransform-Classes） | 能力协商机制与静默降级 | UOE 栈（InstrumentationImpl.java:139） |
| `-Djdk.proxy.ProxyGenerator.saveGeneratedFiles=true`（jdk-27） | 该调试开关在 27 存在"写文件后返回 null 导致 NPE"的问题，用 JDK 21 落盘对照 | NPE at Proxy.java:473 |
| `libs-demo/ReflectDemo` | 反射四件套（注解/私有方法/字段/遍历），见 2.2 | `invoke private -> 79.2` |
| `libs-demo/MethodHandleDemo` | findVirtual/invokeExact/bindTo/unreflect，见 3.2 | `invokeExact = 3, bindTo = 7, unreflect = 11` |
| `libs-demo/LambdaDemo`（+javap） | lambda 编译为 invokedynamic、运行期为隐藏类，见 3.3 | `LambdaDemo$$Lambda/0x...` |
| `libs-demo/AsmDemo`（ASM 9.10.1） | ClassReader/AdviceAdapter 插桩 + 自定义 ClassLoader 加载，见 5.2 | `[asm-probe] enter Target.hello` |
| `libs-demo/JavassistDemo`（3.30.2） | 源码级插桩；老 `toClass()` 撞 JPMS、`toClass(Lookup)` 通过，见 5.3 | `[javassist] enter/exit hello` |
| `libs-demo/ByteBuddyDemo`（1.17.5） | DSL 子类化 + MethodDelegation 拦截，见 5.4 | `$Target$ByteBuddy$sScprNKN` 代理生效 |
| `libs-demo/CglibDemo`（cglib-nodep 3.3.0） | Enhancer 子类化拦截；无 `--add-opens` 报 InaccessibleObjectException，见 4.4 | `CglibTarget$$EnhancerByCGLIB$$e265940e` |
| Arthas 4.3.5 真实 attach（Maven Central 发行版） | watch/trace 全链路；目标类 class v71 时插桩失败，见 7.5 | `Affect(class count: 1, method count: 1)` |
| `libs-demo/metademo/MetadataDemo`（spring-core 7.0.9） | Spring ASM 元数据读取三层次：读元数据零加载；读 Class 属性=加载不初始化；`Class.forName` 才真加载，见 5.6 | 输出无 `[static init]`，属性 `{configClass=class OrderConfig, ...}` |

环境：JDK 27（Oracle GA 2026-09-15）、JDK 21.0.12、JDK 25.0.1；Windows 11；编译运行均用各版本自带 javac/java。第三方库均取自 Maven Central（repo1.maven.org，2026-10-05）：asm-9.10.1、cglib-nodep-3.3.0、javassist-3.30.2-GA、byte-buddy-1.17.5、arthas-packaging-4.3.5-bin。

## 附录 B JEP / JSR 速查（全部经 openjdk.org 核对）

| 编号 | 标题 | 版本 |
|---|---|---|
| JSR 163 | java.lang.instrument / Java Agent | JDK 5 |
| JSR 292 | invokedynamic / MethodHandle | JDK 7 |
| JEP 260 | Encapsulate Most Internal APIs | JDK 9 |
| JEP 193 | Variable Handles | JDK 9 |
| JEP 371 | Hidden Classes | JDK 15 |
| JEP 403 | Strongly Encapsulate JDK Internals | JDK 17 |
| JEP 416 | Reimplement Core Reflection with Method Handles | JDK 18 |
| JEP 451 | Prepare to Disallow the Dynamic Loading of Agents | JDK 21 |
| JEP 454 | Foreign Function & Memory API | JDK 22 |
| JEP 457/466/484 | Class-File API（预览/二预览/正式） | JDK 22/23/24 |
| JEP 471 | Deprecate the Memory-Access Methods in sun.misc.Unsafe for Removal | JDK 23 |
| JEP 498 | Warn upon Use of Memory-Access Methods in sun.misc.Unsafe | JDK 24 |
| JEP 500 | Prepare to Make Final Mean Final | JDK 26 |

## 附录 C Java Agent manifest 属性速查

| 属性 | 作用 | 说明 |
|---|---|---|
| `Premain-Class` | 静态入口类 | 含 `public static void premain(String, Instrumentation)` |
| `Agent-Class` | 动态入口类 | 含 `agentmain(String, Instrumentation)`，供 Attach 注入 |
| `Can-Redefine-Classes: true` | 允许 redefineClasses | 拼错静默降级（见 6.6） |
| `Can-Retransform-Classes: true` | 允许 addTransformer(t,true)/retransform | 同上 |
| `Can-Set-Native-Method-Prefix: true` | 允许 native 方法前缀 | 拦截 JNI |
| `Boot-Class-Path` | 追加到 bootstrap classpath 的 jar | Spy/公共类注入通道 |

## 附录 D 参考文献

- JDK 27 源码（`D:\soft\jdk\jdk-27\lib\src.zip`）：`java.lang.reflect`、`java.lang.invoke`、`java.lang.instrument`、`jdk.attach`、`java.lang.classfile`
- OpenJDK JPLIS 原生层：`openjdk/jdk` `src/java.instrument/share/native/libinstrument/{JPLISAgent,InvocationAdapter}.c`
- Arthas：`alibaba/arthas` master（AgentBootstrap / ArthasBootstrap / Enhancer / SpyAPI / SpyImpl / SpyInterceptors / WatchCommand）
- Spring Framework 7.1.0-SNAPSHOT（`D:\code\3rd\spring-framework`）：`DefaultAopProxyFactory` / `CglibAopProxy` / `org.springframework.cglib`
- 各 JEP 页面：openjdk.org/jeps/{260,193,371,403,416,451,454,457,466,471,484,498,500}
- 本系列姊妹篇：[Spring Framework.md](../spring/Spring%20Framework.md)、[Java并发.md](Java并发.md)、[JDK版本特性变化.md](JDK版本特性变化.md)
