# JPA 与 Hibernate：规范演进、实现史与能力边界

> **写作时间**：2026-10-18。
>
> **本文核对过的公开材料**（本地没有 `hibernate-orm` 与 `jakarta.persistence` 源码树，因此正文不编造文件行号）：
>
> | 材料 | 用来核实什么 |
> |---|---|
> | [hibernate.org 各系列发布页](https://hibernate.org/orm/releases/) | 大版本 GA 日、Java / Jakarta Persistence 基线、许可证切换点 |
> | [Jakarta Persistence 规范页](https://jakarta.ee/specifications/persistence/) 与 [jakartaee/persistence 仓库 tag](https://github.com/jakartaee/persistence/tags) | 3.0 / 3.1 / 3.2 已发布特性，以及 4.0 仍处里程碑 |
> | [projects.eclipse.org/projects/ee4j.jpa](https://projects.eclipse.org/projects/ee4j.jpa) | Eclipse 侧的规范发布记录 |
> | Hibernate 6 官方导读 *An Introduction to Hibernate* | 项目早期史、`Session` 与 `EntityManager` 的关系，作者为 Hibernate 团队自己的表述 |
> | `JpaComplianceSettings` Javadoc（ORM 6.6 / 当前 stable） | 合规开关的名称、默认值与偏离点 |
> | Hibernate ORM 7.0 Migration Guide | 7.0 对 Jakarta Persistence 3.2 的迁移碰撞 |
>
> **和仓库里另外两份文档的分工**：《Spring Framework.md》讲容器；《Spring Data JPA.md》讲 Repository 代理、派生查询，以及 Hibernate 运行时（持久化上下文、脏检查、SQM → SQL）的源码路径。本文只回答四件事：JPA 规范怎么长成今天这样、Hibernate 怎么长成今天这样、Hibernate 把规范实现到什么程度、规范没写但 Hibernate 已经提供的能力是什么。
>
> **阅读约定**：每章先用一两段白话说明"这一阶段在解决什么"，再给可核对的时间、版本和 API。日期以项目发布页或规范站为准；hibernate.org 上 3.0 系列的日期在归档迁移后挤在 2005-11，和当时的发布公告对不上，正文会单独标明。

## 如何读这份文档

如果你是第一次把 JPA 和 Hibernate 放在一起看，推荐两遍读法：

- **第一遍（建立地图，1 小时）**：只读第一章每一节的开头、1.6 的两张时间线、第四章的支持矩阵、第五章每节的第一段，以及第六章的对照表。读完应能回答：规范与实现谁先谁后？`EntityManager` 和 `Session` 是什么关系？为什么 Spring Boot 里写的是标准注解，跑起来却是 Hibernate？哪些注解一换实现就会失效？
- **第二遍（按问题下钻）**：要查某个版本引入了什么，读第二、三章对应小节；要决定"这段代码还能不能换 EclipseLink"，读第四章的合规开关和第五章的扩展清单；要给新人讲清实体四态和持久化上下文，读 2.12。

---

# 一、总览：一份规范，一个把它写进标准又不断跑在标准前面的引擎

## 1.1 一句话定位

**JPA（今称 Jakarta Persistence）是 Java 里"用领域对象管理关系数据"的标准词汇表；Hibernate ORM 是这套词汇表最早的设计来源、目前使用最广的兼容实现，同时也是一份比规范更厚的持久化引擎。**

三者不要叠成一个名字：

- **规范**（`jakarta.persistence-api`）只有注解、接口和查询语言文法。它不包含任何 JDBC 调用。
- **Hibernate** 把这份词汇表编译成元模型，再翻译成 SQL，并在内存里维持"对象 ↔ 行"的一致性。
- **应用框架**（Spring Data JPA、Jakarta Data、Quarkus）坐在两者之上，负责把仓库接口、事务和依赖注入接起来。换掉 Hibernate，Spring 的 `@Transactional` 仍然是同一个注解；换掉规范里没有的 `@Filter`，程序就绑死在 Hibernate 上。

官方导读的原话是：Hibernate 是 JPA 的灵感来源，并且包含这份规范最新修订版的完整实现。同一篇导读把 Hibernate 的 API 分成三块：JPA 规定的接口与映射注解、以 `Session` / `SessionFactory` 为中心的原生 API、以及一组可以和标准注解混用的扩展注解。

## 1.2 设计哲学：读后文前先记住四句话

1. **规范管可移植的最小闭环，实现管数据库现实。** 规范保证"一个带 `@Entity` 的类、一个 `EntityManager`、一段 JPQL"在通过 TCK 的实现之间语义稳定。分页方言、序列优化器、JSON 列、多租户这些随数据库而变的东西，留给实现。
2. **领域模型保持为 POJO。** 实体不实现框架接口、不继承框架基类。这条在 2001 年是针对 EJB 2 Entity Bean 的反叛，后来原样写进了规范。
3. **真正的状态中心是持久化上下文，不是 SQL。** `persist` / `merge` / `remove` 多半只改内存账本；SQL 在 flush 或提交时才成批发出。理解这一点，才能理解脏检查、延迟加载和"改了字段却没调用 save 也写库"。
4. **Hibernate 的默认行为追求少打数据库，严格合规是一组可打开的开关。** 用 JPA 引导（`persistence.xml`、Spring 的 `LocalContainerEntityManagerFactoryBean`）时，总开关默认偏向规范；直接 `new` 原生 `SessionFactory` 时，默认偏向 Hibernate 传统语义。同一套引擎，两套默认值。

## 1.3 分层全景

```
┌────────────────────────────────────────────────────────────┐
│ 应用                                                    │
│   实体类（@Entity / 扩展注解）                              │
│   仓库（手写 EM、Spring Data JPA、Jakarta Data）            │
├────────────────────────────────────────────────────────────┤
│ 容器胶水（可选）                                            │
│   Spring LocalContainerEntityManagerFactoryBean            │
│   JpaTransactionManager / @Transactional                   │
│   Quarkus / WildFly 的持久化单元引导                         │
├────────────────────────────────────────────────────────────┤
│ Jakarta Persistence 规范 API                               │
│   EntityManagerFactory / EntityManager / TypedQuery        │
│   CriteriaBuilder / Metamodel / SchemaManager（3.2+）      │
│   persistence.xml + orm.xml + 标准注解                     │
├────────────────────────────────────────────────────────────┤
│ Hibernate ORM                                              │
│   SessionFactory  extends EntityManagerFactory             │
│   Session         extends EntityManager                    │
│   引导 → 元模型 → 持久化上下文 → SQM → SQL AST → JDBC       │
│   方言、类型系统、二级缓存、字节码增强、事件 SPI              │
├────────────────────────────────────────────────────────────┤
│ JDBC 驱动 → 关系数据库                                      │
└────────────────────────────────────────────────────────────┘
```

规范层故意做得很薄。`EntityManager.find` 在 API jar 里只是一个方法签名；真正决定"先查一级缓存、再查二级缓存、再生成 `select ... where id=?`"的是 Hibernate 的 persister 与 loader。

## 1.4 角色关系：谁制定、谁证明、谁被默认选中

```
        JCP（2006–2017）                Eclipse Foundation（2019 起）
        JSR 220 / 317 / 338             ee4j.jpa 项目
                 │                              │
                 └──────── 规范文本 + TCK ──────┘
                                  │
          ┌───────────────────────┼────────────────────────┐
          │                       │                        │
   EclipseLink              Hibernate ORM              其他兼容实现
   参考实现谱系              使用最广的兼容实现          OpenJPA、DataNucleus…
   （TopLink 血缘）          （Spring Boot 默认）
          │                       │
          └─────── 都可以声称"实现了某一版规范" ────────┘
                                  │
                    只有 Hibernate 扩展注解 / HQL 超集
                    / Session 原生 API 不能跨实现移植
```

三个角色要分开记：

| 角色 | 是谁 | 说明 |
|---|---|---|
| 规范负责人 | Linda DeMichiel（Sun，后随 Java EE 进入 Oracle / Eclipse 过程） | JSR 220、317、338 的 spec lead。JPA 1.0 文本上与 TopLink 的 Mike Keith 共同起草持久化部分 |
| 把模型带进专家组的人 | Gavin King | 2004 年前后加入 EJB 3 专家组。Hibernate 的 POJO、注解、`Session`、HQL 是规范草案的主要蓝本之一。专家组的另外几支力量是 Sun、Oracle（TopLink）、Sybase |
| 参考实现 | JPA 1.0：TopLink Essentials（GlassFish）；JPA 2.0 起：EclipseLink | 2008-03-17 Eclipse 宣布 Sun 选定 EclipseLink 作为 JSR 317 的参考实现。EclipseLink 由 Oracle 把 TopLink 源码捐出 |
| 事实标准实现 | Hibernate | 在 Spring 生态中被 `spring-boot-starter-data-jpa` 默认带上。Jakarta Persistence 3.1 的兼容实现名单同时列出 EclipseLink 4.0.0-M3 与 Hibernate ORM 6.0.0.Final |

Jakarta EE 时代，"参考实现"这个 JCP 用词逐渐让位给"用于批准的兼容实现"。一份规范要完成 ballot，至少需要一个通过 TCK 的实现；Hibernate 多次以这个身份出现在规范页上。

## 1.5 关键问题 → 方案映射（全文导览）

| 问题 | 落在哪一层 | 详见 |
|---|---|---|
| 对象的引用、继承、集合，和表的主外键、JOIN 对不上 | 这就是对象-关系阻抗；ORM 的存在理由 | 2.1 |
| EJB 2 的 Entity Bean 为什么被放弃 | 侵入式接口 + 部署描述符 + 容器绑架，测不了也写不了 | 2.2、3.1 |
| `@Entity`、JPQL、`EntityManager` 是谁发明的 | 词汇来自 Hibernate，2006 年写进 JSR 220 | 2.3、3.2 |
| 为什么包名有时是 `javax.persistence`，有时是 `jakarta.persistence` | 2017–2020 的 Java EE → Jakarta EE 移交；3.0 几乎只做了改名 | 2.7、2.8 |
| 规范保证了什么 | 映射注解、实体状态、持久化上下文、JPQL/Criteria、锁、二级缓存 API、转换器、实体图、存储过程、模式生成 | 2.12、第四章 |
| Hibernate 算不算"完全实现" | 对已发布的各版规范，它是列名的兼容实现；默认运行时在代理、查询、关闭语义等点上保留传统行为，可用合规开关收紧 | 第四章 |
| 规范里没有、但项目里到处在用的是什么 | 过滤器、公式、自然键、自定义 SQL、无状态会话、多租户、HQL 超集、事件 SPI、Envers | 第五章 |
| 这段代码能不能换 EclipseLink | 只使用标准注解和 `EntityManager` 时，语义在 TCK 范围内可移植；出现 `org.hibernate` 注解或 `unwrap(Session.class)` 时不行 | 第六章 |
| Spring 在这条链上的位置 | `spring-orm` 把 EMF 和事务接进容器；Spring Data JPA 再往上生成仓库实现。两者都不实现 ORM | 6.3 |

## 1.6 版本演进：两条时间线叠在一起看

写作时的格局：Jakarta Persistence **3.2 是当前正式版**（2024-04-30）；**4.0 仍在开发**，规范仓库最新里程碑是 **4.0.0-M6（2026-07-03）**，规范站写明目标是 2026 年末随 Jakarta EE 12 发布。Hibernate 稳定线是 **7.4**（7.4.0 发布于 2026-05-26，本文核对时可见的最新补丁为 7.4.13，2026-10-11），**8.0 处于开发期**（8.0.0.Beta3，2026-09-25），兼容性表写的是 Jakarta Persistence 4.0。

### 1.6.1 规范时间线

| 版本 | 出处 | 时间 | 平台 | 一句话 |
|---|---|---|---|---|
| JPA 1.0 | JSR 220（EJB 3.0 的持久化部分） | 2006-05-11 | Java EE 5 | 第一套标准 ORM：POJO、`@Entity`、JPQL、`EntityManager` |
| JPA 2.0 | JSR 317 | 2009-12-10 | Java EE 6 | Criteria、元模型、悲观锁、二级缓存 API、`@ElementCollection` |
| JPA 2.1 | JSR 338 | 2013 年春定稿，Java EE 7 于 2013-06 要求它 | Java EE 7 | 转换器、实体图、存储过程、标准模式生成、Criteria 批量更新 |
| JPA 2.2 | JSR 338 维护版 | 维护评审 2017-06-19 | Java EE 8 | 可重复注解、`java.time`、`getResultStream()` |
| Jakarta Persistence 2.2 | Eclipse 项目 | 2.2.2 起 2018-12；2.2.3 于 2019-09-10 | Jakarta EE 8 | 移交后的再发布，包名仍是 `javax.persistence` |
| Jakarta Persistence 3.0 | Eclipse | 2020-10-21 | Jakarta EE 9 | `javax.persistence` → `jakarta.persistence`，功能几乎不动 |
| Jakarta Persistence 3.1 | Eclipse | 项目记录 2022-03-30，ballot 2022-04-06 | Jakarta EE 10（平台 2022-09） | `UUID`、`GenerationType.UUID`、一批数值与日期函数；基线 Java 11 |
| Jakarta Persistence 3.2 | Eclipse | 2024-04-30 | Jakarta EE 11 | 集合运算、`SchemaManager`、record 嵌入、大量 API 现代化；Hibernate 7 起完整跟上 |
| Jakarta Persistence 4.0 | Eclipse，开发中 | 里程碑 4.0.0-M1（2026-01-21）至 M6（2026-07-03）；规范站目标 2026 年末 | Jakarta EE 12 | `EntityAgent`、静态查询、语句/查询分离、更细的实体图与生命周期事件 |

公开记录里 JPA 2.1 的"最终批准日"有 2013-04-22 与 2013-05-22 两种写法。本文把它记成"2013 年春定稿"，平台锚点用 Java EE 7（2013-06）。

### 1.6.2 Hibernate 时间线

| 系列 | 代表版本 | 时间 | 基线 | 主题 |
|---|---|---|---|---|
| 诞生 | 项目启动 | 2001-05-23（项目日记述）；可下载早期 alpha 在 2001 年末 | — | Gavin King 用 POJO 映射替代 Entity Bean |
| 1.x | 1.0rc1 → 1.0rc3 | 2002-06-15 → 2002-07-03 | — | 第一代可用 ORM，XML 映射 |
| 2.x | 2.0 | 2003-06 | — | 查询、集合、方言成型；社区成为 Java 持久化主流 |
| 3.0 | 3.0 | 2005-03（公告口径；官网归档日期不可靠） | — | 过滤器、新的拦截器、为注解和 EJB 3 铺路 |
| 3.2 前后 | 独立的 Annotations / EntityManager | 2005–2006 | — | 以旁路模块预览 EJB 3 / JPA |
| 3.5 | 3.5.0-Final | 2010-03-31 | JPA 2.0 | 注解与 EntityManager 并入发行版，成为 JPA 2.0 兼容实现 |
| 4.0 | 4.0 | 2011-12 | JPA 2.0 | `ServiceRegistry`、元模型重做、多租户的地基 |
| 4.3 | 4.3.0.Final | 2013-12-16 | JPA 2.1 | 跟上转换器、实体图、存储过程、模式生成 |
| 5.0 | 5.0.0.Final | 2015-08-20 | Java 6/7/8，JPA 2.1 | 新引导 API（`MetadataSources`） |
| 5.2 | 5.2.0 | 2016-06 | Java 8 | `Session extends EntityManager`；EntityManager 模块并入 core |
| 5.3 | 5.3.0 | 2018-05-14 | JPA 2.2 | 字节码默认转向 Byte Buddy；JPA 2.2 |
| 5.6 | 5.6.0.Final | 2021-10-13 | 过渡 | 移除 Javassist；为 6.0 铺路，并提供 Jakarta 迁移过渡 |
| 6.0 | 6.0.0.Final | 2022-03-31 | Java 11，Jakarta Persistence 3.0/3.1 | SQM、SQL AST、新类型系统；移除旧 Criteria |
| 6.2–6.6 | 6.6.0 | 2023-03 至 2024-08-08 | 同上 | 数据库类型、`@SoftDelete`、注解查询方法、Jakarta Data |
| 7.0 | 7.0.0.Final | 2025-05-19 | Java 17，Jakarta Persistence 3.2 | 许可证改为 Apache-2.0（自 7.0.0.Beta5，2025-03-21） |
| 7.4 | 7.4.0 / 7.4.13 | 2026-05-26 / 2026-10-11 | Java 17，Persistence 3.2 | 写作时的稳定系列 |
| 8.0 | 8.0.0.Beta3 | 2026-09-25，未 GA | Java 17+，Persistence 4.0 | 跟着尚未定稿的 4.0 走 |

### 1.6.3 三条主线怎么咬合

| 阶段 | 规范在做什么 | Hibernate 在做什么 |
|---|---|---|
| 2001–2005 | Entity Bean 仍是"官方答案"，JDO 是另一条标准线 | 用 XML 映射把 POJO 持久化做成可发布的库 |
| 2006–2009 | 把 Hibernate 与 TopLink 的交集写成 JPA 1.0 | 用独立模块实现规范，本体继续加过滤器、自定义 SQL、缓存 |
| 2010–2017 | 2.0 / 2.1 / 2.2 把 Criteria、缓存 API、转换器、实体图、`java.time` 收编 | 4.x 做 SPI，5.x 把 `Session` 与 `EntityManager` 合成一套类型 |
| 2018–2021 | 规范移交 Eclipse，3.0 只改包名 | 5.6 做 Jakarta 过渡，同时重写 6.0 的查询与类型系统 |
| 2022–2026 | 3.1、3.2 重新开始加功能；4.0 草案把批量加载、显式 flush、静态查询写进标准 | 6.x 先把这些能力做出来，7.x 对齐 3.2，8.x 对齐 4.0 草案 |

这是同一件事的两个方向：**规范定期把 Hibernate 用户已经依赖的能力收进标准；Hibernate 在两次规范发布之间继续往前做。** 第五章里会逐项看到哪些已经被收编、哪些仍然只属于 Hibernate。

## 1.7 全文章节地图

- **第二章 规范发展历程**：从前史讲到 4.0 草案，最后用一节把"读 Hibernate 之前必须有的规范心智模型"收束住。
- **第三章 Hibernate 发展历程**：按系列讲架构主题，而不是堆 release note。组织、许可证和相邻项目放在章末。
- **第四章 支持程度**：版本矩阵、规范表面覆盖、合规开关、引导方式如何改变默认值。
- **第五章 额外能力**：映射、标识符、查询、抓取、缓存、多租户、类型、批处理、SPI、相邻项目。
- **第六章 对照与取舍**：一张可移植性表，以及和 Spring 的边界。
- **第七章 附录**：合并年表、术语、易错点、延伸阅读。

---

# 二、JPA 规范的发展历程

**白话**：JPA 不是从零设计的学术规范。它是 2000 年代中期，专家组把已经赢了市场的 Hibernate，和已经有十年商业积累的 TopLink，收成一份双方都能实现的交集。之后每一次大版本，几乎都是在把"厂商扩展里被用得最多的那一部分"提升为标准。

## 2.1 前史：阻抗失配，以及三套互相竞争的答案

关系库存的是行、外键和集合语义（包、可重复、靠键关联）。领域模型要的是对象引用、继承和真正的集合。两边的差异被称作对象-关系阻抗失配。JDBC 把"怎么执行 SQL"标准化了，把"行怎么变成对象"完全留给应用。

2000 年前后，Java 企业开发面对三套答案：

1. **手写 JDBC / DAO**。行为完全可控，样板代码和映射错误随表数量线性增长。
2. **EJB 2.x Entity Bean**。容器托管持久化（CMP）或 Bean 托管持久化（BMP）。一个实体要 Home 接口、组件接口、实现类和 `ejb-jar.xml`。类必须实现 `EntityBean`，生命周期回调由容器调用。离开应用服务器，这个类无法当作普通对象测试。
3. **独立 ORM**。商业产品里 Oracle TopLink 从 1990 年代就在做对象-关系映射。标准轨道上还有 JDO（Java Data Objects）：它可以映射关系库，也可以映射对象库，依赖字节码增强，并且没有成为 Java EE 的必选部分。开源侧则出现了 Hibernate、Apache OJB、Castor 等。

JDO 后来没有成为 Java EE 的持久化标准。EJB 3 专家组选择另写一套以关系库为中心、以 POJO 为模型的 API，这套 API 就是 JPA。DataNucleus 等实现把 JDO 延续了下来，但它不在后来的企业 Java 主路径上。

## 2.2 规范从哪里来

JPA 1.0 没有单独的 JSR 编号。它是 **JSR 220（EJB 3.0）** 的一部分，规范文本单独成册，标题就是 Java Persistence API。这样做的政治含义很直接：Entity Bean 被废止，持久化从 EJB 组件模型里拆出来，Java SE 程序不启动 EJB 容器也能用。

参与塑形的几方：

- **Hibernate / JBoss**：POJO、运行期反射或可选的字节码增强、以会话为中心的 API、面向对象的查询语言。Gavin King 在 Hibernate 官方导读里的表述是：他说服专家组废弃 Entity Bean，改用一套按 Hibernate 建模的新 API；随后 TopLink 团队加入，规范在 Linda DeMichiel 主持下，主要由 Sun、JBoss、Oracle、Sybase 协作完成。
- **TopLink / Oracle**：更早的商业映射经验、复杂继承与查询、以及后来成为参考实现的代码底子。Mike Keith 是持久化规范文本的共同作者。
- **Sun**：Linda DeMichiel 任 spec lead，把持久化放进 Java EE 5 的平台要求。

2006-05-11，JPA 1.0 与 Java EE 5 一起最终发布。参考实现是 GlassFish 里的 **TopLink Essentials**，不是 Hibernate。

## 2.3 JPA 1.0（2006）：最小闭环

1.0 把"能写企业应用"的最小集合定死了。今天绝大多数实体类仍然站在这套词汇上。

**映射。** `@Entity`、`@Embeddable`、`@MappedSuperclass`；`@Id` 与 `@GeneratedValue`（`AUTO`、`IDENTITY`、`SEQUENCE`、`TABLE`）；`@Basic`、`@Column`、`@Lob`、`@Temporal`、`@Enumerated`；一对一、一对多、多对一、多对多；`@Inheritance` 的三种策略（`SINGLE_TABLE`、`JOINED`、`TABLE_PER_CLASS`）；`@SecondaryTable`；复合主键（`@IdClass`、`@EmbeddedId`）。元数据可以写在注解里，也可以写在 `orm.xml` 里，XML 可以覆盖注解。

**运行时。** `EntityManagerFactory` 代表一个持久化单元，重量级，进程内通常一个。`EntityManager` 代表一次工作单元，持有持久化上下文。操作是 `persist`、`merge`、`remove`、`find`、`refresh`、`lock`、`flush`、`clear`。事务分两种：资源本地（`EntityTransaction`）和 JTA。Java SE 用 `Persistence.createEntityManagerFactory` 引导；Java EE 用容器注入 `@PersistenceContext`。

**查询。** JPQL 的 `select` / `update` / `delete`，命名查询，以及 `createNativeQuery`。JPQL 操作的是实体名和属性名，不是表名和列名。

**生命周期。** `@PrePersist`、`@PostPersist`、`@PreUpdate`、`@PostUpdate`、`@PreRemove`、`@PostRemove`、`@PostLoad`，以及 `@EntityListeners`。

**乐观锁。** `@Version`。1.0 的锁模式只有乐观读和乐观写。悲观锁要等到 2.0。

1.0 故意没做的事，构成了后面十年的目录：没有类型安全的条件查询，没有标准的二级缓存 API，没有集合元素映射（`@ElementCollection`），没有标准的 `AttributeConverter`，没有实体图，基本类型的懒加载只是一个提示（实现可以忽略）。这些空白就是 Hibernate 扩展长期存在的空间。

## 2.4 JPA 2.0（2009-12-10）：把厂商差异里最痛的部分标准化

JSR 317 从 2007-07 启动，2009-12-10 最终通过。Java EE 6 要求 JPA 2.0。2008-03-17，Eclipse 宣布这版的参考实现是 **EclipseLink**（Oracle 捐出的 TopLink 代码）。Hibernate 在 3.5.0-Final（2010-03-31）成为这版的兼容实现。

2.0 的主题是"1.0 为了达成共识而砍掉、但每个主流实现都私下做了的功能"：

| 能力 | 解决的问题 |
|---|---|
| Criteria API | 条件查询要靠拼接 JPQL 字符串，编译期看不到属性名写错 |
| 规范化元模型（`Metamodel`、`StaticMetamodel`） | Criteria 需要类型安全的属性引用；注解处理器生成 `User_` 这种静态类 |
| `@ElementCollection`、`@OrderColumn` | 值类型集合和有序集合终于有标准映射 |
| 混合访问类型 | 同一个实体里，有的字段用字段访问，有的用属性访问 |
| 悲观锁 | `PESSIMISTIC_READ`、`PESSIMISTIC_WRITE`、`PESSIMISTIC_FORCE_INCREMENT` |
| 二级缓存 API | `@Cacheable`、`Cache`、`CacheRetrieveMode`、`CacheStoreMode`。规范只规定语义和开关，不规定缓存实现 |
| `TypedQuery`、`orphanRemoval`、更完整的派生标识（`@MapsId`） | 补 1.0 的类型与级联缺口 |
| Bean Validation 集成 | 持久化前可以自动跑校验 |
| JPQL 增强 | `CASE`、`NULLIF`、`COALESCE`、`TYPE`、`INDEX`、`KEY`、`VALUE`、`ENTRY` 等 |

二级缓存这一条尤其能说明规范的边界：JPA 规定"可以按实体开关共享缓存、读写时可以指定检索/存储模式"。缓存放哪、并发策略叫什么、区域怎么划分，全部不是规范的事。那是 Hibernate 的 `RegionFactory` 和 `CacheConcurrencyStrategy`。

## 2.5 JPA 2.1（2013）：转换、抓取计划和存储过程

JSR 338 于 2011-07 启动，2013 年春定稿，随 Java EE 7 成为平台要求。Hibernate 4.3.0.Final（2013-12-16）跟上。

这一版的关键词是"少写厂商扩展"：

- **`AttributeConverter` / `@Converter`。** 一个 Java 类型与一个 JDBC 类型之间的标准转换口。在此之前，Hibernate 用户写的是 `UserType`。
- **实体图（Entity Graph）。** 用 `@NamedEntityGraph` 或编程方式声明"这次查询要把哪些关联一次性抓出来"，用来对付 N+1 和临时改变 fetch 计划。Hibernate 早已有 fetch profile 和 `@Fetch`，但那些不可移植。
- **存储过程。** `StoredProcedureQuery`。Hibernate 旧的命名 SQL 查询能调用存储过程，但 API 不标准。
- **标准模式生成。** `javax.persistence.schema-generation.database.action` 等属性。Hibernate 的 `hbm2ddl.auto` 因此有了可移植的对应物，但它自己的取值（`validate`、`update`、`create`、`create-drop`）仍然更常用。
- **Criteria 的 `update` / `delete`。** 批量写不再只能拼 JPQL。
- **JPQL。** `TREAT`（把继承查询收窄到子类）、`FUNCTION`（调用数据库函数）、`ON` 条件。
- **未同步的持久化上下文。** 一个持久化上下文可以先不加入 JTA 事务，稍后显式加入。
- **实体监听器可被 CDI 注入。**
- **`@Index`、`@ForeignKey`。** 把 DDL 的索引和外键名纳入映射。

## 2.6 JPA 2.2（2017）：Java 8 补齐

这是 JSR 338 的维护版本，维护评审在 2017-06-19 通过，随 Java EE 8 发布。没有新的映射哲学，只是把语言平台补上：

- 相关注解成为 `@Repeatable`，注解也可以当作元注解使用。
- `Query.getResultStream()`，对接 Stream API。
- `AttributeConverter` 也可以被 CDI 注入。
- 直接映射 `java.time` 的 `LocalDate`、`LocalTime`、`LocalDateTime`、`OffsetTime`、`OffsetDateTime`。在此之前这些类型要手写转换器。`Instant` 要等到 3.2 才被明确列入。

Hibernate 从 5.3（2018-05-14）起按 2.2 实现。DataNucleus 5.1、EclipseLink 2.7、OpenJPA 3.0 是同期的其他兼容实现。

## 2.7 移交：从 JCP 到 Eclipse Foundation（2017–2019）

2017 年，Oracle 把 Java EE 捐给 Eclipse 基金会。品牌不能继续叫 Java，于是有了 Jakarta EE。这件事对 JPA 的技术内容没有立刻影响，对坐标和包名影响极大。

Eclipse 项目页上，Jakarta Persistence 的早期发布是 **2.2.2（2018-12-14）** 和 **2.2.3（2019-09-10）**。包名仍是 `javax.persistence`。Jakarta EE 8 使用的就是这一档。已经写好的 Java EE 8 应用可以在这一档上停留，不必改 import。

## 2.8 Jakarta Persistence 3.0（2020-10-21）：一次只改名字的大版本

3.0 的全部主题就是命名空间迁移：

- Java 包：`javax.persistence` → `jakarta.persistence`。
- 配置属性名里的 `javax` 换成 `jakarta`。
- XML 命名空间一并更换。

没有新的映射能力，也没有新的查询语法。它被做成主版本，是因为包名变化在 Java 里就是二进制不兼容。应用、Hibernate、EclipseLink、Spring 必须一起跳。

Hibernate 的策略是分两步：5.5 / 5.6 提供 Jakarta 坐标作为过渡（旧的 `javax` 世界继续能用 5.6），6.0（2022-03-31）起主线只认 `jakarta.persistence`。Spring Framework 6 / Spring Boot 3 是同一时间的跳跃。所以"升级到 Boot 3"在持久化层的本质，是 JPA 3.0 的包名迁移加上 Hibernate 6 的引擎替换，两件不同的事叠在了同一个版本窗口里。

## 2.9 Jakarta Persistence 3.1（2022）：移交之后的第一笔功能

3.1 是移交后第一版真正加功能的规范。项目记录日 2022-03-30，规范委员会 ballot 于 2022-04-06 完成。它属于 Jakarta EE 10，基线 Java SE 11。用于批准的实现是 EclipseLink 4.0.0-M3；兼容实现名单包含 Hibernate ORM 6.0.0.Final。

规范页列出的增量：

- `EntityManagerFactory` 与 `EntityManager` 继承 `AutoCloseable`。
- `java.util.UUID` 成为基本类型，`GenerationType.UUID` 成为标准主键策略。
- 数值函数：`CEILING`、`EXP`、`FLOOR`、`LN`、`POWER`、`ROUND`、`SIGN`，Criteria 侧有对应方法。
- `LOCAL DATE`、`LOCAL TIME`、`LOCAL DATETIME`，以及 `EXTRACT`。
- Criteria 的 `CASE` 条件可以是表达式。
- 若干文法澄清：`LOCATE` 的参数顺序、多个 `EntityResult` 的别名冲突、查询参数混用、Basic 类型的定义。

这一版没有破坏性删除。它的意义是证明规范在 Eclipse 治理下还能演进，而不是停在改名。

## 2.10 Jakarta Persistence 3.2（2024-04-30）：十年里最大的一次 API 现代化

3.2 于 2024-04-30 发布，随后成为 Jakarta EE 11 的持久化规范。Hibernate 要到 7.0.0.Final（2025-05-19）才把实现基线升到 3.2。Gavin King 在 3.2 发布前后重新深度参与了规范文本，并写过专文说明这一版的幅度远大于版本号所暗示的"小修订"。

规范页上的增量可以分成五组。

**查询语言。** `UNION`、`INTERSECT`、`EXCEPT`（含 `ALL`）；`CAST`；字符串的 `LEFT`、`RIGHT`、`REPLACE` 和 `||`；`id()` 与 `version()` 函数；排序可以指定 null 的先后；`ORDER BY` 允许标量表达式；标识变量和 `SELECT` 子句在若干情形下可以省略。这些语法里，有相当一部分 Hibernate 6 的 HQL 已经先提供了。

**类型与映射。** Java `record` 可以做 `@Embeddable`；`java.time.Instant` 与 `Year` 成为受支持的基本类型；`LocalDateTime` 和 `Instant` 可以做 `@Version`；`@EnumeratedValue` 允许枚举用自定义持久化值；`@Table` / `@Column` 获得 `comment`、`check`，并有 `CheckConstraint`；`@Column.secondPrecision`；生成器注解可以放在包上，生成器名字可以省略。

**运行时 API。** `getSingleResultOrNull()`；`EntityManager.getReference` 的重载、`runWithConnection` / `callWithConnection`；`find` / `refresh` / `lock` 接受 `FindOption`、`RefreshOption`、`LockOption`；EMF 上的 `runInTransaction` / `callInTransaction`、`getName()`；`PersistenceConfiguration` 可编程地得到 EMF，替代"只有 `persistence.xml`"这一条路；`SchemaManager` 把建表、校验、清空、删表收成标准接口；工厂级访问命名查询和命名实体图。

**Criteria 与元模型。** `CriteriaSelect`、对 `EntityType` 的连接、`where` / `having` 接受 `List`、`Expression.equalTo`、静态元模型里生成命名查询和命名图的常量。

**图与缓存。** `Graph` 成为 `EntityGraph` 和 `Subgraph` 的父类型，补齐增删属性节点的方法；`EntityManager` 和 `Query` 可以直接 `setCacheRetrieveMode` / `setCacheStoreMode`。

7.0 迁移指南明确写了这次升级对 Hibernate 是破坏性的，原因主要是**规范新增的方法撞上了 Hibernate 早就存在的扩展方法**：

- Criteria 和实体图的类型参数变了。
- JPA 的 `Nulls` 与 Hibernate 的 `NullPrecedence` 冲突，后者的取值方法被改名。
- JPA 新增 `SchemaManager`，Hibernate 原有的同名类型改为继承它，字节码不兼容。
- `Transaction.getTimeout()` 在规范里返回 `Integer`，Hibernate 历史上返回 `int`。

这是"实现跑在规范前面"的代价：规范终于标准化时，方法签名往往要给规范让路。

## 2.11 Jakarta Persistence 4.0：写作时仍是草案

规范仓库 README 写明：当前正式版是 3.2；4.0 正在积极开发，目标是 2026 年末。GitHub tag 显示 **4.0.0-M1（2026-01-21）** 到 **4.0.0-M6（2026-07-03）**。规范站页面标题仍是 "under development"，并写着 Milestone 4 的 API、文本和 TCK 已经可用。Eclipse 项目页上有一条 2026-04-14 的 4.0 release 记录，和规范站、Git tag 不一致。本文以规范站和 Git tag 为准：**2026-10-18 这一天，4.0 还没有 Final。**

草案已经写进去、因此仍可能调整的方向（摘自规范站 4.0 页）：

| 草案能力 | 它在靠近什么 |
|---|---|
| `EntityAgent`、`@PersistenceAgent` | 脱离持久化上下文之后仍然能按实体图操作的代理 |
| `@JakartaQuery`、`@NativeQuery`、`@QueryOptions` | 方法级静态查询，并和 Jakarta Data 对齐 |
| `Statement` 与 `Query` 分离 | 把改数据的语句和返回数据的查询从类型上拆开 |
| `findMultiple` / `getMultiple`、`BatchSize` 选项 | 按主键批量加载。Hibernate 的 `byMultipleIds` 是先行者 |
| `@Fetch`、按属性的 fetch 选项 | 把实体图写得更短 |
| 可选的 to-one 默认 `LAZY` | 规范终于允许持久化单元把多对一/一对一的默认抓取改成懒加载 |
| 更细的 Criteria 表达式类型 | 降低 Criteria 的样板密度 |
| 新的生命周期回调：`@PreInsert`、`@PostInsert`、`@PreUpsert`、`@PostUpsert`、`@PreDelete`、`@PostDelete`、`@PreMerge` | 把"flush 时真正的 SQL 种类"暴露给监听器。Hibernate 的事件系统里这些阶段早就存在 |
| `FlushModeType.EXPLICIT` | 接近 Hibernate 一直都有的 `FlushMode.MANUAL` |
| `@ExcludedFromVersioning`、锁范围 `FETCHED` | 乐观锁和悲观锁的粒度 |

Hibernate 8.0 的系列页把兼容性写成 Jakarta Persistence 4.0、Jakarta Data 1.1、Jakarta EE 12。8.0.0.Alpha1（2026-02-02）已经按这个目标开发。在 4.0 定稿之前，8.0 的新增 API 本身也不承诺稳定。

## 2.12 规范稳定下来的心智模型

后面两章会反复用到同一套对象。这里用规范的语言把它们钉住，不涉及 Hibernate 类名。

### 2.12.1 三个范围

| 概念 | 范围 | 持有什么 |
|---|---|---|
| 持久化单元 | 通常整个应用一份配置 | 数据源、托管类列表、属性。描述在 `persistence.xml` 或（3.2 起）`PersistenceConfiguration` |
| `EntityManagerFactory` | 与持久化单元一一对应，线程安全，创建代价高 | 全部映射元数据、二级缓存的入口、模式工具的入口 |
| `EntityManager` | 一次工作单元，不跨线程共享 | 持久化上下文，以及 `persist` / `find` / `createQuery` 这些操作 |

### 2.12.2 持久化上下文是一级缓存

持久化上下文是"本工作单元正在管理的实体实例"的集合，按持久化标识（主键）索引。同一个上下文里，同一个主键不会出现两个对象。所以在同一个 `EntityManager` 里对同一 id 调用两次 `find`，第二次不需要再打数据库。这个行为是规范的，不是 Hibernate 的私货。

上下文的生命期有两种标准形态：

- **事务范围。** 事务提交或回滚后，实体变为脱管。Spring 的典型业务方法是这种。
- **扩展范围。** 上下文可以跨多个事务，直到应用显式关闭。常见于有状态的会话 Bean，在无状态的 Web 请求里很少直接使用。

### 2.12.3 实体四态

| 状态 | 含义 | 字段变化会不会写库 |
|---|---|---|
| new（瞬时） | 刚 `new` 出来，上下文不认识，库里也没有对应行 | 不会 |
| managed（托管） | 在上下文中，且与一行关联 | 会。flush 时做脏检查 |
| removed | 已标记删除，flush 或提交时发出 `DELETE` | 删除本身会被发出 |
| detached（脱管） | 曾经托管，上下文已关闭或被 `detach` / `clear` | 不会，除非再次 `merge` |

状态转换的标准动词：`persist`（new → managed）、`merge`（detached → 返回一个新的 managed 副本）、`remove`（managed → removed）、`refresh`（用数据库当前值覆盖 managed）、`find` 和查询（直接得到 managed）。

`merge` 的返回值是上下文里的那个实例，参数那个脱管对象本身不会变成托管。这是规范行为，也是最常见的误用点。

### 2.12.4 SQL 发生在 flush，不发生在 setter

托管实体的 setter 只改内存。持久化提供者在 flush 时对比快照，生成 `UPDATE`。flush 的标准时机是提交前，以及执行可能与尚未写出的修改重叠的查询之前。查询可以要求不同的 flush 模式。Hibernate 在此之外还有 `MANUAL`，见第五章。

### 2.12.5 三种查询，一种映射结果

| 方式 | 类型安全 | 可移植 | 典型用途 |
|---|---|---|---|
| JPQL | 字符串，错误多在运行期 | 高 | 固定查询、命名查询 |
| Criteria | 用元模型时编译期可见 | 高 | 条件动态组合 |
| 原生 SQL | 取决于方言 | 低 | 数据库专有语法、复杂报表 |

三者都通过 `EntityManager` 或（3.2 起）工厂上的命名查询引用进入。规范要求提供者把 JPQL 和 Criteria 翻译成 SQL。Hibernate 6 起这两者先变成同一种语义模型（SQM），再变成 SQL AST。这是实现结构，不是规范要求。

### 2.12.6 锁与缓存，规范只规定到"模式"

乐观锁靠 `@Version`。版本冲突抛出乐观锁异常，事务应当回滚。悲观锁从 2.0 起通过 `LockModeType` 表达，最终变成 `SELECT … FOR UPDATE` 一类语句，具体 SQL 由实现和方言决定。

缓存分两层：

- **一级**就是持久化上下文，规范强制有，不能关。
- **二级**是跨上下文的共享缓存，2.0 起标准化开关和读写模式。可以没有。打开之后的内存布局、失效和并发，是实现的自由。

## 2.13 本章小结

- JPA 是 EJB 3 把 Entity Bean 废除之后留下的持久化规范。1.0 的词汇表来自 Hibernate 与 TopLink 的交集，规范负责人是 Linda DeMichiel，参考实现谱系是 TopLink Essentials → EclipseLink。
- 2.0、2.1、2.2 分别补上了类型安全查询与缓存 API、转换器与实体图、Java 8 时间类型。
- 3.0 只改包名；3.1 重新加功能；3.2 是一次大的 API 现代化，并把一批 HQL 里已经存在的语法收进标准。
- 4.0 在 2026-10 仍是草案。规范的心智模型始终是：工厂、工作单元、持久化上下文、四态、flush 时才写 SQL。

---

# 三、Hibernate 的发展历程

**白话**：Hibernate 比规范早五年。它先证明"POJO + 外部映射 + 一个会话对象"能打赢 Entity Bean，再把这套模型送进标准，然后在标准覆盖不到的数据库现实里继续加功能。架构上真正的断代只有几次：3.0 的功能大成，4.0 的 SPI 化，5.2 的 API 合并，6.0 的查询与类型系统重写，7.0 的许可证与 Java 17 基线。

## 3.1 2001–2004：从对 Entity Bean 的不满到 JBoss

Hibernate 官方导读把项目起点放在 2001 年：Gavin King 当时对 EJB 2 Entity Bean 的挫折感爆发。他在澳大利亚 Cirrus Technologies 工作。公开的项目日记述 **2001-05-23** 作为项目生日；可下载的早期 alpha（常被记为 0.8）出现在 2001 年末。

设计前提从第一天就和 Entity Bean 相反：

- 领域类是普通 Java 对象，不实现 `EntityBean`，没有 Home / Remote 接口。
- 映射写在外部 XML（`.hbm.xml`）里，而不是写在业务方法里。
- 一个 `Session` 代表与数据库的一次对话，里面是脏检查和延迟加载。
- SQL 由映射和查询语言生成，开发者只在必要时下降到原生 SQL。

**1.0** 的候选版在 2002 年 6 月到 7 月连续发布：1.0rc1（2002-06-15）、1.0rc2（2002-06-20）、1.0rc3（2002-07-03）。**2.0** 在 2003 年 6 月成为正式版（2.0.1 的公告日是 2003-06-17，距 2.0 正式版大约一周）。2.x 把方言（不同数据库的分页、标识、函数）、集合语义和查询语言做成了可以支撑真实系统的程度。Gavin King 与 Christian Bauer 的 *Hibernate in Action* 是这一阶段的教材，也是后来许多"DAO 里包一层 Session"写法的来源。官方导读后来明确说，这本早期书把 DAO 包装推荐得太久了。

**2004 年**，Gavin King 和 Christian Bauer 加入当时还很小的 JBoss。随后加入的早期贡献者包括 Max Rydahl Andersen、Emmanuel Bernard、Steve Ebersole、Sanne Grinovero。Hibernate 从此成为 JBoss 的项目，而不再是业余时间的个人库。2006 年 Red Hat 收购 JBoss，项目的雇佣关系进入 Red Hat，许可证仍是 LGPL 2.1。

## 3.2 3.x（2005–2010）：塑造规范，再用独立模块实现规范

**3.0** 在 2005 年 3 月发布（同时代公告；hibernate.org 的 3.0 系列页把多个版本的日期挤在 2005-11，不能当作 GA 日）。这一代把 Hibernate 从"好用的映射工具"做成了完整引擎：

- 用户定义的**过滤器**（按会话启用的额外 SQL 条件）。
- 新的拦截器与回调结构。
- 更完整的继承、集合和批量行为。
- 为即将到来的 JDK 5 注解和 EJB 3 留出了位置。

注解没有一开始就进核心。**hibernate-annotations** 与 **hibernate-entitymanager** 是独立工件：前者用注解表达映射，提前演练 EJB 3 的注解形态；后者实现 `EntityManager`，让同一个引擎能用规范 API 启动。JPA 1.0 发布时，Hibernate 已经能以这套旁路模块去实现它。核心用户多数仍直接使用 `Session` 和 `hbm.xml`。

这个分裂维持到 **3.5.0-Final（2010-03-31）**。3.5 把注解和 JPA 支持并入主发行版，并作为 **JPA 2.0 的认证兼容实现**。从这时起，"Hibernate 实现了某一版 JPA"才是一个可以拿 TCK 说话的事实，而不只是 API 看上去像。

3.x 末期的另一个历史细节：字节码代理主要靠 CGLIB，Javassist 作为可选的增强器。这条线要到 5.x 才切换。

## 3.3 4.x（2011–2015）：服务注册表与多租户

**4.0（2011-12）** 是一次对内的架构重整，用户可见的注解变化不大，扩展方式变化很大：

- **`ServiceRegistry` / `Integrator`。** 缓存、连接、方言、JTA 都变成可替换的服务。框架作者（包括后来的 Spring 和 WildFly）通过 Integrator 进入引导过程，而不是反射调用内部类。
- 元模型与引导 API 重做，为 5.0 的 `MetadataSources` 做准备。
- **多租户**在 4.0 到 4.1 落地：按库、按 schema、按鉴别列三种隔离策略。规范直到今天也没有多租户这一章，这是 Hibernate 作为"平台引擎"而不是"规范样本"的开始。

**4.3.0.Final（2013-12-16）** 的外部主题很单一：实现 JPA 2.1。转换器、实体图、存储过程、标准模式生成从这一版起可以用规范 API 调用，底层仍是 Hibernate 自己的引擎。

4.x 的最终小版本 4.3.11 发布于 2015-08-05，与 5.0 的发布窗口重叠。

## 3.4 5.x（2015–2021）：引导重写、API 合并、字节码换代

**5.0.0.Final（2015-08-20）** 要求 Java 6、7 或 8，实现 JPA 2.1。外部标志是全新的引导：`StandardServiceRegistryBuilder` + `MetadataSources` + `MetadataBuilder`，替代用了十年的 `Configuration`。`Configuration` 仍保留了很长时间，但新代码的入口变了。

**5.2（2016-06）** 是对应用开发者影响最大的 5.x 小版本，基线升到 Java 8：

- `Session` 继承 `EntityManager`，`SessionFactory` 继承 `EntityManagerFactory`。从这一版起，两者是同一套对象的两种视角，不再是"JPA 门面包着一个 Hibernate 会话"。
- `hibernate-entitymanager` 并入 `hibernate-core`。JPA 不再是外挂模块。
- Java 8 的日期时间和 Stream 成为引擎真正使用的语言能力，而不只是用户侧的转换器。

**5.3.0（2018-05-14）** 实现 JPA 2.2，并把默认字节码库转到 **Byte Buddy**。Javassist 进入弃用。合规开关的多数标志（事务、查询、关闭语义）也是在 5.2 末到 5.3 成型的，见第四章。

**5.4（2018-12）** 是漫长的维护线。**5.6.0.Final（2021-10-13）** 做了两件收尾：移除 Javassist；在不强迫所有人升级到 6.0 的前提下，提供通向 Jakarta 包名的过渡。5.6 的发布说明把自己称为 "Paving the road for Hibernate ORM 6"。它是 `javax.persistence` 世界的最后一条主维护线。

5.x 还有一条容易忽略的集成：Hibernate Spatial 在 5.x 逐步并入核心方言体系，地理类型不再是一个完全独立的 ORM。

## 3.5 6.x（2022–2024）：查询、类型与 SQL 的重写

6.0 的开发从 2018 年末的 Alpha 一直持续到 **6.0.0.Final（2022-03-31）**。这是自 3.0 以来对内部最大的一次替换。系列页的兼容性是：Java 11、17 或 18；Jakarta Persistence 3.1 或 3.0。

引擎上的三个变化决定了今天的 Hibernate：

1. **查询。** HQL / JPQL 的解析从旧的 ANTLR 2 语法换成 ANTLR 4，先生成语义查询模型（SQM），再降低为 SQL AST，最后渲染成 SQL 字符串。Criteria 不再有第二条编译管线。JPA Criteria 也进入 SQM。旧的 `org.hibernate.Criteria`（Hibernate 2/3 时代的命令式条件 API）被移除。
2. **类型系统。** 以 `JavaType`、`JdbcType` 和它们的组合替换原先以 `Type` 为中心、难以扩展的层级。JSON、数组、枚举、时区、自定义 JDBC 类型都挂在这套描述符上。`@JdbcTypeCode` 成为用户入口。
3. **运行时语义的清理。** 一批 3.x 遗留行为被改成更接近规范或更可预测的默认值，并提供迁移指南。`Session.save` / `update` / `saveOrUpdate` 进入弃用，标准动词是 `persist` 和 `merge`。`@Where` 让位给 `@SQLRestriction`。

6.0 同时是 Jakarta 包名的主线切换点。选择 6.0 就是选择 `jakarta.persistence`；留在 5.6 就是选择继续用 `javax.persistence`。

后续小版本在新引擎上加能力，而不是再换引擎：

| 系列 | 首个 Final | 系列页或发布摘要里的主题 |
|---|---|---|
| 6.1 | 2022-06-07 | 6.0 引擎的第一轮加固 |
| 6.2 | 2023-03-31 | 结构体与数组、分区、SQL `MERGE` 一类方言能力 |
| 6.3 | 2023-09-01 | `@Find`、`@HQL`、`@SQL`：由注解处理器生成的查询方法 |
| 6.4 | 2023-11-23 | `@SoftDelete`；向量类型开始作为一等数据库能力 |
| 6.5 | 2024-04-25 | 延续 6.4 的数据库特性 |
| 6.6 | 2024-08-08 | Jakarta Data、`@ConcreteProxy`、可嵌入对象的继承、数组能力扩展 |

6.6 是 6.x 里被 Spring Boot 3.4 / 3.5 一代广泛使用的系列，写作时仍有限支持（endoflife 记录的最新补丁到 2026-09）。它实现的规范基线仍是 Jakarta Persistence 3.1 这一档，**不是** 3.2。要得到 3.2 的 `SchemaManager`、`UNION` 标准和 record 嵌入，需要 Hibernate 7。

## 3.6 7.x（2025–2026）：Apache 许可证、Java 17、规范 3.2

**7.0.0.Final 发布于 2025-05-19。** 许可证的切换比 Final 更早：7.0.0.Beta4（2025-02-12）仍是 LGPL 2.1，**7.0.0.Beta5（2025-03-21）起是 Apache-2.0**。从 1.0 到 7.0.0.Beta4，Hibernate ORM 的许可证都是 LGPL 2.1。

7.0 的基线：

| 项 | 值 |
|---|---|
| Java | 17、21 或 23 |
| Jakarta Persistence | 3.2 |
| Jakarta Data | 1.0 |
| 许可证 | Apache-2.0 |
| 系列页标题中的新主题 | `QuerySpecification`、`mapping.xsd`、Hibernate Models |

7.0 迁移指南把升级描述为"主要是 Jakarta Persistence 3.2 带来的破坏"，并公布了 Java 17 与 Java 21 的 TCK 结果入口。也就是说，7.0 对 3.2 的支持是按 TCK 声称的，不是只在发行说明里写了一句"支持"。

7.0 之后的系列主题，按 hibernate.org 系列页：

| 系列 | 首发 | 写作时的状态 | 主题 |
|---|---|---|---|
| 7.1 | 2025-08-08 | 已结束 | 资源扫描、锁的改进 |
| 7.2 | 2025-10 | 有限支持；系列页写明对应 Spring Boot 4.0 | 悲观锁改进、`@EmbeddedTable`、`FindMultipleOptions`、SQL Server 向量 |
| 7.3 | 2026-03-16 | 稳定支持已结束 | 自然 id 的类级映射、按租户的凭据 |
| 7.4 | 2026-05-26 | **写作时的稳定系列**；补丁至 7.4.13（2026-10-11） | 时间与审计映射、带 limit / fetch join 的分页安全性、逆向工程、`hbm.xml` 转换 |

7.x 没有再换 SQM。它是 6.0 引擎在新的语言基线、新的许可证和新的规范基线上的成熟期。

## 3.7 8.0（2026，未 GA）：跟着 Persistence 4.0 草案走

8.0 系列页标明状态是 development。已发布的构建：

| 构建 | 日期 |
|---|---|
| 8.0.0.Alpha1 | 2026-02-02，发布说明写明 Jakarta Persistence 4.0 |
| 8.0.0.Beta1 | 2026-06-16 |
| 8.0.0.Beta3 | 2026-09-25 |

兼容性表：Java 17、21、25 或 26；Jakarta Persistence 4.0；Jakarta Data 1.1；Jakarta EE 12。系列页同时提醒：开发期功能可能不完整，新 API 在稳定版之前可以不兼容地修改。

因此在 2026-10 的技术选型里，**7.4 是对齐 Jakarta Persistence 3.2 的稳定答案，8.0 是对齐尚未定稿的 4.0 的试验线。**

## 3.8 组织、人和相邻项目

**项目领导。** Gavin King 在 3.x / JPA 1.0 之后把主要精力转去了其他规范（后来包括 CDI）。Hibernate ORM 之后的长期技术负责人是 Steve Ebersole。6.x 重写查询语言、以及 Jakarta Persistence 3.2 / Jakarta Data 这一轮，Gavin King 再次深度参与规范和 HQL 的设计。官方导读专门感谢 Steve 在 Gavin 离开一线之后的多年主持。

**治理。** 2004 年进入 JBoss，2006 年随 JBoss 进入 Red Hat。2019 年 IBM 完成对 Red Hat 的收购后，工程上仍由 Red Hat 一侧继续投入。2024–2025 年，项目按 Commonhaus Foundation 的治理要求补齐基金会所需要的文档（仓库里 2025-02 有对应的文档工作）。代码托管一直在 GitHub 的 `hibernate/hibernate-orm`。

**不要和 ORM 混成一个产品的相邻项目：**

| 项目 | 关系 |
|---|---|
| Hibernate Validator | Bean Validation / Jakarta Validation 的实现。常和 ORM 一起出现在类路径上，但是另一份规范、另一个版本号 |
| Hibernate Search | 全文检索与更丰富的投影查询，通过事件监听 ORM 的变更。版本必须与 ORM 系列匹配（7.0 对应 Search 8.0，8.0 对应 Search 9.0） |
| Hibernate Reactive | 基于 Vert.x 的反应式会话，不是 ORM 的一个开关。它实现的是另一套非阻塞 API，不能把 `Session` 当成反应式对象用 |
| Hibernate Processor | 编译期生成静态元模型，以及 6.3 起的 `@Find` / `@HQL` 实现。前身是 `hibernate-jpamodelgen` |
| Envers | 在 ORM 仓库内的审计模块，见第五章 |
| OGM | 面向 NoSQL 的试验性映射，早已不是主线 |

## 3.9 本章小结

- Hibernate 比 JPA 早五年。1.x/2.x 证明 POJO 映射可行，3.x 把模型送进规范并用独立模块实现它，3.5 起成为可以引用 TCK 的 JPA 实现。
- 4.0 把内部改成服务注册表；5.2 让 `Session` 成为 `EntityManager` 的子类型；6.0 重写查询与类型系统并切断 `javax`；7.0 改用 Apache-2.0、Java 17 和 Persistence 3.2。
- 写作时用 7.4 对齐已发布的规范，用 8.0 试验尚未定稿的 4.0。Validator、Search、Reactive 是相邻项目，不是 ORM 的配置项。

---

# 四、Hibernate 对 JPA 规范的支持程度

**白话**：支持程度不是一个百分比。Hibernate 对每一版已发布规范都提供了通过 TCK 的实现，同时在若干"规范写得很严、但会多打数据库"的地方保留了自己的默认行为。判断支持程度，要同时看三件事：版本矩阵、API 表面、合规开关。

## 4.1 它以什么身份支持规范

Hibernate 同时占据三个位置：

1. **兼容实现。** 规范页把具体的 Hibernate 版本列入某一版 Jakarta Persistence 的兼容实现。例如 3.1 的名单包含 Hibernate ORM 6.0.0.Final，3.2 的实现基线是 Hibernate 7。7.0 迁移指南给出 TCK 结果。这意味着标准注解、标准 XML、`EntityManager` 的规范语义和 JPQL 文法，在对应版本上是被测试套件约束的。
2. **超集。** `Session` 继承 `EntityManager` 之后，标准方法全部可用，原生方法加在子类型上。映射也一样：标准注解和 `org.hibernate.annotations` 可以写在同一个类上。
3. **有意识的偏离。** 偏离集中在性能和历史语义，并且几乎都能用 `hibernate.jpa.compliance.*` 收回规范行为。偏离不是"没实现"，而是"实现了，但默认不按最严的那一档执行"。

参考实现这个位置属于 EclipseLink 谱系。两者都可以运行同一份只使用标准 API 的应用。Spring Boot 的 JPA 起步依赖选择 Hibernate，是生态默认值，不是规范的要求。

## 4.2 版本对照矩阵

| 规范 | 规范定稿 | Hibernate 从哪一版起按它实现 | 应用侧的坐标感觉 |
|---|---|---|---|
| JPA 1.0 | 2006-05-11 | 3.2 时代的 EntityManager 模块；完整度在 3.3–3.5 收束 | `javax.persistence`，今天只存在于遗留系统 |
| JPA 2.0 | 2009-12-10 | 3.5.0-Final（2010-03-31），认证实现 | 同上 |
| JPA 2.1 | 2013 春 | 4.3.0.Final（2013-12-16） | 同上 |
| JPA 2.2 | 2017-06-19 | 5.3（2018-05-14） | Java 8 时间类型可以去掉手写转换器 |
| Jakarta Persistence 3.0 | 2020-10-21 | 5.5/5.6 的 Jakarta 过渡工件；6.0 起主线只发 jakarta | import 必须改 |
| Jakarta Persistence 3.1 | 2022-03/04 | 6.0.0.Final（2022-03-31），列在兼容实现中 | `UUID` 主键、新的 JPQL 函数 |
| Jakarta Persistence 3.2 | 2024-04-30 | 7.0.0.Final（2025-05-19），附 TCK 结果 | `SchemaManager`、`UNION`、record 嵌入、`getSingleResultOrNull` |
| Jakarta Persistence 4.0 | 未定稿（里程碑至 2026-07 的 M6） | 8.0 开发线（Alpha1 自 2026-02-02） | 不要在生产基线上依赖 |

读这张表的方式：**运行时的 Hibernate 版本决定了你能合法使用的规范版本，而不是反过来。** 在 Hibernate 6.6 上调用 3.2 才加入的 `SchemaManager`，类路径上就没有那个类型。

## 4.3 规范表面覆盖到什么程度

下面按领域说明"标准 API 在 Hibernate 里是直接实现，还是映射到更老的原生能力"。

| 规范领域 | Hibernate 中的落点 | 支持情况 |
|---|---|---|
| 实体、嵌入、映射超类、三种继承策略 | 引导期建成元模型，由 persister 在运行期读写 | 完整。Hibernate 另外保留了规范没有的隐式多态和 `@Any` |
| `EntityManager` / `EntityManagerFactory` | 自 5.2 起就是 `Session` / `SessionFactory` 的父类型 | 完整。同一个对象既可以当规范类型用，也可以 `unwrap` 成原生类型 |
| JPQL | 6.0 起进入 SQM，和 HQL 共用解析器 | 完整，并且在查询合规关闭时接受 HQL 超集 |
| Criteria | 同样降低到 SQM。旧的 `org.hibernate.Criteria` 已在 6.0 删除 | 完整实现标准 Criteria。想要更短的写法，用 Hibernate 的 `HibernateCriteriaBuilder` 扩展，或 7.0 的 `QuerySpecification` |
| 静态元模型 | `hibernate-processor` 生成 | 完整，并且会生成 Hibernate 自己的额外常量 |
| `@ElementCollection`、`@OrderColumn`、关联 | 集合 persister | 标准映射完整。没有 `@OrderColumn` 的 `List` 在语义上是规范留白的点，Hibernate 有自己的默认，见 4.4 |
| 悲观锁与乐观锁 | 方言渲染 `FOR UPDATE` 变体；`@Version` 走版本列 | 标准锁模式完整。Hibernate 增加 `SKIP_LOCKED`、`NOWAIT`、follow-on locking 等 |
| 二级缓存 API | `Cache` 接口之下是 `RegionFactory` | 标准开关完整。并发策略和区域实现是扩展 |
| `AttributeConverter` | 接入 6.0 的类型系统 | 完整。更复杂的多列转换仍要 `UserType` / `JdbcType` |
| 实体图 | 标准图 API，6.x 后与 fetch 计划打通 | 完整。Hibernate 还有 fetch profile 和 `@Fetch` |
| 存储过程 | 标准 `StoredProcedureQuery` | 完整 |
| 模式生成 | 标准属性与 3.2 的 `SchemaManager`；历史入口是 `hbm2ddl.auto` | 3.2 起两边都有。`update` 这种增量改表仍是 Hibernate 语义，生产环境通常改用 Flyway / Liquibase |
| 生命周期回调 | 标准注解会注册到 Hibernate 的事件系统 | 完整。事件系统比七个标准回调多得多 |
| `persistence.xml` 与 `orm.xml` | 引导期读取 | 完整。Spring 场景下经常没有 `persistence.xml`，由 `LocalContainerEntityManagerFactoryBean` 以编程方式提供等价信息，这仍是规范允许的引导路径 |
| CDI 注入转换器与监听器 | 在容器环境中通过集成器完成 | 在 Jakarta EE / Spring 的集成下可用 |

**XML 映射的两个格式不要混：** `orm.xml` 是规范格式，Hibernate 会读；`hbm.xml` 是 Hibernate 从 1.x 就有的格式，其他实现不认识。7.4 仍在提供把 `hbm.xml` 转成现代映射的工具，说明旧格式还在被迁移，而不是被当作新项目的入口。

## 4.4 合规开关：默认更省数据库的那些点

总开关是 `hibernate.jpa.compliance`（6.0 起）。Javadoc 写明：每个单独的标志仍可覆盖它。**用 JPA 方式引导时，总开关默认 `true`；用原生 Hibernate 引导时，默认 `false`。** Spring 的 `LocalContainerEntityManagerFactoryBean` 走的是 JPA 引导，因此 Spring 应用里这些标志默认偏向规范。

下面是 6.6 Javadoc 里每一面旗帜实际改变的行为。

| 属性 | 自 | 规范要求 | Hibernate 传统行为 |
|---|---|---|---|
| `hibernate.jpa.compliance.query` | 5.3 | 按 JPQL 校验并按规范抛出 `Query` 的异常 | 关闭时允许 HQL 超集。这是"能不能写 Hibernate 查询语法"的总阀 |
| `hibernate.jpa.compliance.transaction` | 5.3 | `Transaction` 按 `EntityTransaction` 的语义行事 | 保留 Hibernate 事务对象的传统行为 |
| `hibernate.jpa.compliance.closed` | 5.3 | 对已关闭的 EM / EMF 再 `close()` 必须抛 `IllegalStateException` | 再次关闭是空操作 |
| `hibernate.jpa.compliance.proxy` | 5.2.13 | 访问一个数据库中已不存在的未初始化代理，必须抛 `EntityNotFoundException`；按规范，读标识符也可能迫使代理初始化 | 标识符已经在代理里，读标识符不打数据库。Javadoc 写明打开此开关"显然不推荐" |
| `hibernate.jpa.compliance.caching` | 5.3 | 带辅助表的实体要按 TCK 的方式立即写入二级缓存 | Hibernate 默认对辅助表实体做缓存失效，避免两个事务各改一张表造成缓存不一致。Javadoc 认为默认更安全 |
| `hibernate.jpa.compliance.global_id_generators` | 5.2.17 | `@SequenceGenerator` / `@TableGenerator` 的名字在持久化单元内全局唯一，重名即启动失败 | 名字可以局部于实体 |
| `hibernate.jpa.compliance.load_by_id` | 6.0 | `find` / `getReference` 的 id 参数类型必须就是实体声明的类型 | 允许安全的加宽，例如 `Integer` 到 `Long` |
| `hibernate.jpa.compliance.order_by_mapping` | 6.0 | `@OrderBy` 的项必须是实体属性 | 允许更复杂的表达式 |
| `hibernate.jpa.compliance.list` | 5.3，6.0 起弃用 | 规范把"没有 `@OrderColumn` 的 `List`"留为未定义，可移植程序不该依赖顺序 | 改为使用 `hibernate.mapping.default_list_semantics`。历史开关曾让这种 `List` 被当成真正有序列 |
| `hibernate.jpa.compliance.cascade` | 6.6；7.0 的接口方法标记为不再起作用 | flush 时级联标准的 `PERSIST` | 级联遗留的 `SAVE_UPDATE` |

这些开关说明一种支持策略：**TCK 要的行为都在，默认值在"可移植"和"少一次往返"冲突时，往往把少一次往返留给显式选择。** 在 Spring 里因为总开关默认开启，多数新项目已经跑在规范语义上；真正还要留意的是查询合规——一旦关闭，JPQL 字符串里就可以混入 HQL 才有的语法，这段查询就不再可移植。

## 4.5 引导方式如何决定你得到的默认值

| 引导方式 | 看到的类型 | 合规总开关默认 | 典型场景 |
|---|---|---|---|
| `persistence.xml` + `Persistence.createEntityManagerFactory` | `EntityManager` | 偏向规范 | Java SE、规范示例 |
| Spring `LocalContainerEntityManagerFactoryBean` | 注入的是 `EntityManager`（往往还是共享代理） | 偏向规范 | Spring Boot / Spring Framework |
| `SessionFactoryBuilder` / 原生引导 | `Session` | 偏向 Hibernate 传统语义 | 框架集成、批处理、不经过 JPA 门面的工具 |
| `unwrap(Session.class)` | 从规范类型下降到原生类型 | 不改变已确定的开关，只是多暴露方法 | 在 Spring 事务里局部使用过滤器、自然键、滚动结果 |

因此"这个项目算不算用了 JPA"要看引导和源码里的类型，不能只看类路径上有 `hibernate-core`。类路径上有 Hibernate，只说明提供者是它。

## 4.6 实现得完整，但升级时会撞车的地方

支持程度还包含"旧的 Hibernate 扩展和后来的规范方法撞名"。7.0 迁移指南里的碰撞是样本：

- 规范把 null 的排序次序标准化之后，Hibernate 自己的 `NullPrecedence` 取值方法改名。
- 规范定义 `SchemaManager` 之后，`SessionFactory.getSchemaManager()` 的返回类型层级变了，这是二进制不兼容。
- 规范的 `getTimeout()` 返回包装类型，直接和 `int` 比较的旧代码会在自动拆箱时遇到空指针。
- Criteria 与实体图的泛型签名在 3.2 收紧，编译期就会失败，而不是运行期。

6.0 则是另一类完整支持的代价：旧 Criteria API 被删除，HQL 解析器换代，一部分隐式关联和返回类型的推断发生了变化。迁移指南是支持矩阵的一部分，不能只看"支持 Jakarta Persistence x.y"这一行。

还有一类永久的规范留白，Hibernate 必须自己做决定，所以不同实现的默认值可能不同：

- `@Basic(fetch = LAZY)` 对基本字段只是提示。没有字节码增强时，Hibernate 也不会真的把一个 `String` 懒加载。
- 没有 `@OrderColumn` 的 `List`，顺序不保证。
- 二级缓存的一致性级别，规范只规定到读写模式。
- `GenerationType.AUTO` 在每个方言上的落地不同：可以是序列、表或标识列。

## 4.7 本章小结

- 对已发布的 JPA 1.0 到 Jakarta Persistence 3.2，Hibernate 都有明确的实现版本；3.1 和 3.2 还写进了规范的兼容实现名单，7.0 公布了 TCK 结果。
- 标准 API 是完整实现，并且从 5.2 起与 `Session` 是同一个对象。
- 偏离集中在代理初始化、查询超集、关闭语义、生成器命名作用域、id 强制转换和辅助表缓存。它们由 `hibernate.jpa.compliance.*` 控制，JPA 引导下默认偏向规范。
- 4.0 的支持属于 8.0 开发线，随草案变动。

---

# 五、Hibernate 超出规范的能力

**白话**：规范解决"换一个实现，实体和查询还在"。Hibernate 的额外能力解决"这个数据库、这个批处理、这个租户模型，规范没有句子可以写"。额外能力一律位于 `org.hibernate` 包、HQL 超集，或 `Session` 上规范没有的方法。使用它们的代码不能原样迁到 EclipseLink。

下面按"映射、标识、查询、抓取、缓存、租户与过滤、类型与方言、批量、SPI、模式、相邻模块"排列。每一项都标明它和规范的边界。

## 5.1 原生会话：同一个对象上的另一套动词

自 5.2 起，`Session` 是 `EntityManager` 的子类型。注入 `EntityManager` 后可以：

```java
Session session = entityManager.unwrap(Session.class);
```

规范没有、而 `Session` 上常用的能力：

| 能力 | 作用 |
|---|---|
| `byId`、`byMultipleIds` | 按主键加载，支持批量、缓存模式和锁选项的组合。4.0 草案里的 `findMultiple` 是对这件事的标准化 |
| `bySimpleNaturalId`、`byNaturalId` | 按业务自然键加载，并可走自然键缓存 |
| `doWork` / `doReturningWork` | 在当前会话的 JDBC 连接上执行一段代码，用来调用驱动专有 API |
| `setFlushMode(MANUAL)` 或 `FlushMode.COMMIT` | `MANUAL` 表示只有显式 flush 才写库。规范的 flush 模式直到 4.0 草案才出现 `EXPLICIT` |
| `StatelessSession` | 没有持久化上下文、没有脏检查、没有一级缓存的插入/更新管道，面向百万行批处理 |
| `session.enableFilter` | 启用下文的过滤器 |
| 滚动结果 `ScrollableResults` | 服务端游标式地遍历大结果集，避免一次装进内存 |
| 统计信息 `SessionFactory.getStatistics` | 查询次数、缓存命中、最慢查询。规范没有运行指标 |

`save`、`update`、`saveOrUpdate` 属于早期原生 API。6.0 起弃用，对应的标准动词是 `persist` 和 `merge`。新代码继续用它们，是在依赖一条已经标明要消失的路径。

## 5.2 映射：标准注解写不出的表结构

这些注解都在 `org.hibernate.annotations`，可以和 `@Entity`、`@Column` 写在一起。

**公式与派生值。** `@Formula` 把一个只读属性映射成 SQL 表达式（例如用子查询得到订单总额）。规范里没有"属性不是列，而是表达式"。

**自定义 CRUD SQL。** `@SQLInsert`、`@SQLUpdate`、`@SQLDelete`、`@SQLSelect`（以及历史上的 `@Loader`）用一段手写 SQL 替换 persister 生成的语句。软删除在成为一等注解之前，通常就是 `@SQLDelete` 加一个过滤条件。`@SoftDelete` 从 6.4 起把这种模式收成声明式注解，但它仍是 Hibernate 注解，不是 Jakarta Persistence 注解。

**过滤器。** `@FilterDef` 定义一个带参数的 SQL 片段，`@Filter` 把它挂到实体或集合上，`session.enableFilter("tenant").setParameter(...)` 在运行时启用。这和 JPQL 的 `where` 不同：过滤器对所有查询生效，包括按 id 加载。规范没有这个机制。多租户的鉴别列策略经常用它，也可以用专门的 `@TenantId`（6.x）。

**自然键。** `@NaturalId` 标记业务键。会话按自然键加载，二级缓存可以有独立的自然键区域。7.3 补上了类级别的 `@NaturalIdClass`。规范只有主键。

**非多态的外联。** `@Any` / `@ManyToAny` 用一个鉴别列加一个外键，指向不在同一继承树里的多种实体。规范的多态关联必须是继承映射。

**读模型。** `@Subselect` 把实体映射到一条 SELECT 而不是一张表，适合遗留库上的只读聚合。`@Immutable` 告诉引擎这个实例不会脏检查。

**结构控制。** `@DynamicInsert` 与 `@DynamicUpdate` 让 INSERT/UPDATE 只包含非空或确实变化的列，用来对付有数据库默认值、又有很多可空列的宽表。规范的生成 SQL 没有这个开关。

**关联的数据库现实。** `@NotFound` 在外键指向的行不存在时选择忽略而不是抛异常，用于脏数据。`@OnDelete` 把级联下推到数据库的 `ON DELETE`。`@JoinFormula` 用表达式而不是列做连接。`@BatchSize` 把懒加载的按 id 查询收成 `in (...)` 批量。这些都改变 SQL 的形状，规范的 `FetchType` 只表达 EAGER 还是 LAZY。

**鉴别与乐观锁的增强。** `@DiscriminatorFormula` 用 SQL 表达式而不是一列决定子类。`@OptimisticLocking` 可以选择用全部字段或脏字段做乐观锁，而不只是 `@Version` 列。规范的乐观锁就是版本列（3.2 扩大了版本列允许的 Java 类型）。

**时间戳。** `@CreationTimestamp`、`@UpdateTimestamp`、`@Generated` 表达"这一列由数据库或 Hibernate 在插入/更新时生成"。3.2 澄清了部分生成值在 `@PrePersist` 时是否可见，但生成策略的种类仍比规范多。

**集合的 Hibernate 语义。** 规范的集合是 set、list、map 加上 `@OrderColumn`。Hibernate 还区分 bag（可重复、无序、无索引列）、id-bag、extra-lazy 集合（`@LazyCollection(EXTRA)` 让 `size()` 变成 `count` 查询而不是把集合拉进内存）。`@SortNatural` / `@SortComparator` 是内存排序，`@OrderBy` 才是 SQL 排序。两者都存在时，要看清排序发生在哪一侧。

**分区与组合表。** 6.2 起的分区键、7.2 的 `@EmbeddedTable`，服务的是单表已经放不下的物理设计。规范的 `@SecondaryTable` 只能表达"一个实体横跨固定的几张表"。

## 5.3 标识符：规范只有四种策略

规范的 `@GeneratedValue` 是 `AUTO`、`IDENTITY`、`SEQUENCE`、`TABLE`，3.1 加了 `UUID`。Hibernate 在这四种之上提供：

| 能力 | 为什么规范不够 |
|---|---|
| 序列优化器：`pooled`、`pooled-lo`、`hilo` | 规范没规定一次取多少个序列值。优化器决定了"每插入一行是不是都要访问一次序列" |
| `@GenericGenerator`（旧）与 `@IdGeneratorType`（6.2 起的新扩展点） | 自定义生成算法：雪花、号段、由外部系统预分配 |
| `uuid` 的多种生成时机 | 3.1 只规定"可以是 UUID"，不规定在应用里生成还是到数据库里生成 |
| 可回填的标识 | `IDENTITY` 必须等插入执行才知道主键；序列策略可以在 flush 前就拿到值。这影响批处理能不能把插入合并成一批 |

选择 `IDENTITY` 时，JDBC 批处理会被打断，因为驱动要立刻取回生成键。这是 Hibernate 文档反复强调的性能事实，规范文本几乎不讨论。

## 5.4 查询：HQL 是 JPQL 的超集

解析器从 6.0 起只有一套。查询合规打开时，它按 JPQL 拒绝超集语法；关闭时，同一解析器接受 HQL。

HQL 长期领先、其中一部分已被规范追平的语法：

| 语法 | 现在的位置 |
|---|---|
| 集合运算 `union` / `intersect` / `except` | Jakarta Persistence 3.2 已收入 JPQL |
| `id()`、`version()`、更多字符串函数 | 3.2 已收入 |
| 公共表表达式（`WITH`）、窗口函数、`LATERAL` | 仍以 HQL 和方言能力为主，规范 3.2 没有把它们全部标准化 |
| 插入…选择、按条件更新的更多形式 | 规范有 JPQL `update`/`delete`；更接近数据库的 `MERGE`、返回生成列，仍是 Hibernate 与方言 |
| 实体名上的隐式连接、更自由的 `treat` | HQL |

除了语法，查询 API 还有这些规范没有的入口：

- **命名的原生查询加上结果变换器**（`TupleTransformer`、`ResultListTransformer`）。规范有 `@SqlResultSetMapping`，Hibernate 的变换器可以在得到列表之后再做一次编程式整形。
- **编译期查询方法。** 6.3 起 `@Find`、`@HQL`、`@SQL` 写在接口方法上，由 Hibernate Processor 生成实现。这是后来 Jakarta Data 的近亲，但注解属于 Hibernate。
- **Jakarta Data。** 6.6 起 Hibernate 自己提供 Jakarta Data 的关系库实现，7.0 对齐 Jakarta Data 1.0。仓库方法由规范（Jakarta Data）定义，底层会话仍是 Hibernate。它和 Spring Data JPA 是两条平行的"接口方法变 SQL"路线。
- **7.0 的 `QuerySpecification`。** 在标准 Criteria 之外提供更紧凑的动态查询构造。
- **查询提示与超时、评论、只读标记** 的 Hibernate 专用键。标准也有 `jakarta.persistence.query.timeout` 这类提示，覆盖面更窄。

原生 SQL 查询在两边都有。差别在于 Hibernate 允许把结果直接映射到多个实体、再配合过滤器与缓存区域，这些组合不是 `createNativeQuery` 的标准合同。

## 5.5 抓取、代理与脏检查

规范只说关联是 `EAGER` 或 `LAZY`，基本字段的 `LAZY` 是提示。Hibernate 把"怎么懒"做成了一套机制：

| 机制 | 行为 |
|---|---|
| 字节码代理（5.3 起默认 Byte Buddy，5.6 移除 Javassist） | 未初始化的关联是一个代理。第一次访问非标识符属性时发出 SQL |
| `@Fetch(SUBSELECT / JOIN / SELECT)` 与 `@BatchSize` | 决定 N+1 是被收成一条子查询、一条连接，还是一组 `in` 查询 |
| Fetch profile | 按名字在会话上启用一套抓取计划。比实体图更早，和实体图可以并存 |
| 字节码增强 | 可以拦截字段写入，做增强式脏检查；也可以真正延迟加载基本属性。没有增强时，脏检查靠 flush 时的快照比较，基本属性在加载实体时就读出 |
| `@ConcreteProxy`（6.6） | 继承树的代理在初始化前就能暴露具体子类的类型信息，减少一次额外查询或强制转换失败 |

读代理的标识符默认不加载实体，这是 4.4 节那个"不推荐打开"的合规开关所保护的行为。

脏检查本身是规范要求的（托管对象的变化要写回），**快照比较还是增强时拦截**是实现自由。Hibernate 两种都有，默认是快照。

## 5.6 缓存：规范只画了开关

一级缓存就是持久化上下文，两边都有，前面已经说过。

二级缓存的 Hibernate 扩展是真正的实现层：

| 扩展 | 含义 |
|---|---|
| `RegionFactory` | 缓存放在进程内、JCache、Infinispan 还是别的提供者。`hibernate-jcache` 是常用适配器 |
| 并发策略 | `READ_ONLY`、`NONSTRICT_READ_WRITE`、`READ_WRITE`、`TRANSACTIONAL`。规范的 `CacheRetrieveMode` / `CacheStoreMode` 不表达这些策略 |
| 区域划分 | 实体区域、集合区域、查询区域、自然键区域可以分开配置过期和大小 |
| 查询缓存 | 缓存"这条查询的主键列表"，实体数据仍在实体区域。默认关闭。规范没有查询缓存 |
| 辅助表的失效策略 | 见 4.4 的 caching 合规项：默认宁可失效，也不按 TCK 的更激进方式直接放入 |

把 `@Cacheable` 写上只是打开了规范开关。不配置 `RegionFactory` 和并发策略时，它不会变成一个可以上生产的共享缓存。

## 5.7 多租户、过滤器与软删除

规范假设一个持久化单元对应一个数据库、一种 schema、实体删除就是 `DELETE`。Hibernate 提供三种多租户：

| 策略 | 隔离方式 | 连接与会话上要提供什么 |
|---|---|---|
| `DATABASE` | 每个租户一个库 | 租户标识 → 连接 |
| `SCHEMA` | 同一个库的不同 schema | 租户标识 → schema |
| `DISCRIMINATOR` | 同一张表的租户列 | `@TenantId` 或过滤器 |

当前租户由 `CurrentTenantIdentifierResolver` 在每次获取连接时决定。这是 SPI，不是注解能单独完成的。

软删除在 6.4 之前是"自定义 SQL + 过滤器"的组合，6.4 的 `@SoftDelete` 把列、删除值、加载条件收成一个注解。查询自动附加"未删除"条件。这仍然不是 Jakarta Persistence 的语义：规范的 `remove` 就是删除。

## 5.8 类型系统与方言

6.0 的类型系统是扩展能力的底座，而不只是重构：

- **`@JdbcTypeCode` / `@JavaType`。** 把一个属性明确绑到 JDBC 类型码，例如 JSON、UUID、数组。
- **`UserType`、组合类型、`AttributeBinder`。** 一个 Java 属性对应多列，或对应数据库的结构体。6.2 的 `@Struct` 服务 PostgreSQL 等数据库的结构化类型。
- **`@Nationalized`。** 使用国家字符类型（`NVARCHAR`）。规范的字符串映射不区分国家字符。
- **`@TimeZoneStorage`。** 决定时区存在列里、规范成 UTC，还是存一个单独的时区列。`OffsetDateTime` 在规范里只是"可以映射"，存储策略是实现定义。
- **枚举。** 规范是 `ORDINAL` 或 `STRING`，3.2 加了 `@EnumeratedValue`。在此之前 Hibernate 已经允许用自定义 JDBC 表示。
- **向量。** 6.4 起把向量类型引进核心方言（先是 pgvector 一类），7.2 系列页写明 SQL Server 向量。这是数据库新类型，规范没有对应的 Java 类型。

**方言**是一整棵类层次：分页、序列的 `nextval` 语法、锁的 `FOR UPDATE SKIP LOCKED`、`RETURNING`、标识符引号、最大标识符长度，都在方言里。社区方言放在 `hibernate-community-dialects`，避免核心为冷门数据库背依赖。规范只要求"提供者能执行 JPQL"，不要求公开这棵方言树。应用偶尔会直接引用方言类来注册函数，那一行代码也是不可移植的。

函数注册（`FunctionContributor`，以及更早的 `SQLFunction`）让 HQL 能调用数据库专有函数。规范 2.1 的 `FUNCTION()` 能覆盖一部分场景，注册之后的函数可以有正确的返回类型和参数渲染，这是 `FUNCTION()` 做不到的。

## 5.9 批处理与无状态会话

大容量写入是规范最薄的地方。`EntityManager` 可以 `persist` 一万次，但每一行都进入持久化上下文，内存和脏检查成本线性增长。Hibernate 的工具箱：

| 手段 | 效果 |
|---|---|
| `hibernate.jdbc.batch_size` | 把同构的 INSERT/UPDATE/DELETE 收成 JDBC 批 |
| `hibernate.order_inserts` / `order_updates` | 让批不被不同实体的语句打断 |
| 定期 `flush` + `clear` | 丢掉已经写出的一级缓存，避免持久化上下文无限长大 |
| `StatelessSession` | 从一开始就不用一级缓存。对象不会变成托管，延迟加载也不存在 |
| 滚动结果 + 只读提示 | 大查询不把整个列表物化 |
| `@BatchSize`、`byMultipleIds` | 读路径上的批量 |

`IDENTITY` 生成器和批处理互相妨碍，前面已经提过。批量 JPQL `update` / `delete` 是规范就有的，它绕过持久化上下文，直接打数据库，所以一级缓存会过期。这是规范行为；Hibernate 的额外价值在于让你能显式控制 flush 和清空的节奏。

## 5.10 SPI：框架作者用的口，应用偶尔也会用

| SPI | 用途 |
|---|---|
| `Integrator` | 在 `SessionFactory` 构建时注册服务、事件监听和类型。Spring、搜索、审计都走这里 |
| `EventListener`（persist、flush、merge、delete、load…） | 比 `@PrePersist` 更细，能看到 flush 时的动作队列。4.0 草案正在把其中几个阶段收成标准回调 |
| `Interceptor` | 老的、会话级的回调：保存前、加载后、SQL 字符串生成时 |
| `PhysicalNamingStrategy`、`ImplicitNamingStrategy` | 把 Java 名变成列名的规则（驼峰转下划线通常在这里）。规范没有命名策略 |
| `CurrentTenantIdentifierResolver`、`ConnectionProvider` | 多租户和连接获取 |
| `BeanContainer` | 让转换器和监听器由 Spring 或 CDI 实例化 |

应用代码直接实现 `Integrator` 的机会不多。一旦要做审计字段以外的全局拦截，这就是正确的层次，而不是去继承 Hibernate 的内部 persister。

## 5.11 模式管理

两边都能建表。能力差在"建到什么程度"：

| 能力 | 规范 | Hibernate |
|---|---|---|
| 创建 / 删除 / 校验 | 2.1 的 schema-generation 属性；3.2 的 `SchemaManager` | 同样实现，并保留 `hibernate.hbm2ddl.auto` |
| 增量更新现有表 | 规范不做"对比后 ALTER" | `update` 会尝试补列。生产上不可靠，官方也更希望你用迁移工具 |
| 导出脚本 | 标准动作里有脚本目标 | 更细的分隔、方言相关的类型名 |
| 注释与 check 约束 | 3.2 起写在 `@Table` / `@Column` | 更早就可以用 Hibernate 注解 |
| 索引 | 2.1 的 `@Index` | 更早，且能表达方言相关的索引选项 |
| 从数据库逆向 | 无 | 7.4 系列主题包含逆向工程 |
| `hbm.xml` 迁移 | 无 | 7.4 提供向现代映射的转换 |

生产系统的模式变更应当落在 Flyway、Liquibase 或等价物上。Hibernate 的模式工具适合测试、原型，以及生成"第一版 DDL 草稿"。

## 5.12 仓库内的官方模块，以及仓库外的官方项目

这些不是 `hibernate-core` 的隐式行为，需要额外依赖。

| 模块或项目 | 额外提供的能力 | 和规范的关系 |
|---|---|---|
| `hibernate-envers` | 每次插入、更新、删除写审计表，支持按修订号查询实体历史 | 完全在规范之外。用事件监听实现，对实体几乎无侵入 |
| `hibernate-spatial` | 地理类型与空间函数 | 映射和 HQL 函数都是扩展。5.x 后与方言集成 |
| `hibernate-vector`（6.4 起的向量支持，制品名随系列可能收进核心或独立模块） | 向量列与相似度查询 | 数据库专有 |
| `hibernate-jcache` | 把二级缓存接到 JCache | 规范只有缓存语义 |
| `hibernate-hikaricp`、`agroal`、`c3p0` | 连接池集成 | JDBC 层，规范不涉及 |
| `hibernate-micrometer` | 指标导出 | 规范不涉及 |
| `hibernate-processor` | 静态元模型、`@Find` / `@HQL` / `@SQL` 的生成 | 元模型是规范的；查询方法注解是 Hibernate 的 |
| `hibernate-community-dialects` | 非主流数据库方言 | 扩展 |
| Hibernate Search | 全文索引、跨实体的搜索投影 | 独立项目，版本与 ORM 系列绑定 |
| Hibernate Reactive | 非阻塞会话 API | 独立项目，不实现"把 `EntityManager` 变成反应式" |

Envers 是这些里面最常被误认为"JPA 功能"的一个：审计表、`AuditReader`、`@Audited` 都在 `org.hibernate.envers`。换实现就没有这套历史查询。

## 5.13 本章小结

- 额外能力的入口是三条：`org.hibernate.annotations`、HQL 超集、`Session` 上的原生方法与 SPI。
- 映射侧最常用的是过滤器、公式、自然键、自定义 SQL、批量抓取、动态插入更新、软删除和 `@Any`。
- 运行时侧最常用的是无状态会话、滚动结果、JDBC 批处理、二级缓存的区域与并发策略、多租户。
- 6.x 之后又多了新类型系统（JSON、数组、向量、时区策略）、注解查询方法和 Jakarta Data 实现。
- 规范 3.2 和 4.0 草案正在回收其中一部分（集合运算、批量按 id 加载、显式 flush、更细的生命周期事件）。回收完成之前，它们仍然是 Hibernate 扩展。

---

# 六、对照与取舍

## 6.1 一张表看完边界

| 你想做的事 | 停在规范内的写法 | Hibernate 扩展的写法 | 换实现时 |
|---|---|---|---|
| 映射一张表 | `@Entity`、`@Id`、`@Column` | 再加 `@DynamicUpdate`、`@Formula` | 标准部分可移植 |
| 主键 | `@GeneratedValue` | 优化器、`@IdGeneratorType` | 策略名可移植，优化器不可 |
| 继承 | `SINGLE_TABLE` / `JOINED` / `TABLE_PER_CLASS` | `@DiscriminatorFormula`、`@Any` | 三种策略可移植 |
| 关联懒加载 | `FetchType.LAZY`、实体图 | `@BatchSize`、`@Fetch`、fetch profile | 语义可移植，SQL 条数不保证 |
| 查询 | JPQL、Criteria | HQL 超集、`@HQL`、`QuerySpecification` | 打开查询合规时，JPQL 可移植 |
| 按业务键查找 | JPQL `where` | `@NaturalId` + `bySimpleNaturalId` | 扩展不可移植 |
| 软删除 | 手写 `where deleted = false`，`remove` 改成更新 | `@SoftDelete` 或 `@SQLDelete` + `@Filter` | 扩展不可移植 |
| 多租户 | 应用自己加条件 | `DATABASE` / `SCHEMA` / `DISCRIMINATOR` | 扩展不可移植 |
| 历史版本 | 自己建审计表 | Envers | 不可移植 |
| 共享缓存 | `@Cacheable` 与 Cache 模式 | `RegionFactory` + 并发策略 + 查询缓存 | 开关可移植，行为差很多 |
| 大批量写入 | 循环 `persist`，自己 flush/clear | 再加批大小、无状态会话 | 标准部分可移植，吞吐不同 |
| 建表 | 3.2 `SchemaManager` | `hbm2ddl.auto=update` | 创建/删除可移植；增量更新不可 |
| 存 JSON / 向量 | 转换器把 JSON 当字符串 | `@JdbcTypeCode`、向量模块 | 扩展不可移植 |
| 脱离容器使用会话 | `EntityManager` | `unwrap(Session.class)` | 一旦 unwrap 就绑定 Hibernate |

## 6.2 怎么决定要不要越过规范

有一条实用的分界，不需要把"可移植性"当成道德问题：

- **实体的结构、事务边界、普通查询**，用标准注解和 `EntityManager`。这些代码在 Hibernate 7 与 EclipseLink 5 之间有 TCK 作为共同语言，也是 Spring Data JPA 派生查询所生成的目标。
- **性能和数据库物理设计**（批大小、批量抓取、动态更新、序列优化器、方言函数），用 Hibernate 配置和注解。换数据库时本来就要改，换 ORM 时一起改是合理的成本。
- **领域规则**（租户隔离、软删除、审计历史、自然键），如果选了 Hibernate 扩展，就把这个决定写在模块边界上：不要在业务服务里到处 `unwrap`。集中在一个持久化适配器里，将来替换才有一个缝。

查询合规建议保持打开，除非这条查询明确需要 CTE、窗口函数或 `LATERAL`。需要时，把那一条查询孤立出来，比全局关闭合规更安全。代理合规建议保持 Hibernate 的传统行为（不因读取标识符而打数据库），这也是 Javadoc 的建议。

## 6.3 和 Spring 的边界

Spring 不实现 JPA，也不实现 Hibernate。它做三件胶水活，细节在另外两份文档里：

| Spring 模块 | 做的事 | 文档 |
|---|---|---|
| `spring-orm` 的 `LocalContainerEntityManagerFactoryBean` | 把持久化单元变成容器里的一个 Bean，并选择 Hibernate 作为提供者 | 《Spring Framework.md》数据访问一章 |
| `JpaTransactionManager` 与 `@Transactional` | 用事务同步把同一个 `EntityManager` 绑到当前线程 | 同上 |
| Spring Data JPA | 把仓库接口生成实现，最终仍调用 `EntityManager` | 《Spring Data JPA.md》 |

因此：升级 Spring Boot 3 强制遇到的是 Jakarta Persistence 3.x 的包名和 Hibernate 6 的引擎；升级到对齐 Framework 7 / Boot 4 的一代，才会进入 Hibernate 7 和 Jakarta Persistence 3.2。规范版本、Hibernate 版本、Boot 版本是三根一起动的轴，不能只升其中一根。

## 6.4 本章小结

- 可移植的是规范词汇：注解、`EntityManager`、JPQL/Criteria、锁模式、缓存开关、3.2 的模式管理接口。
- 不可移植的是 SQL 的形状和引擎的运行时：过滤器、公式、自然键、HQL 超集、二级缓存实现、多租户、无状态会话、Envers。
- Spring 位于这两者之上。它决定事务和仓库的样子，不决定 Hibernate 比规范多出来的那些句子。

---

# 七、附录

## 7.1 合并年表

| 年 | 规范 | Hibernate | 同时发生的平台事件 |
|---|---|---|---|
| 2001 | Entity Bean 仍是 Java EE 的答案 | 项目启动 | — |
| 2002–2003 | — | 1.0、2.0 | — |
| 2004 | EJB 3 专家组开始重做持久化 | 核心成员加入 JBoss | — |
| 2005 | — | 3.0；注解模块开始预演 EJB 3 | — |
| 2006 | JPA 1.0 / Java EE 5；RI 为 TopLink Essentials | EntityManager 模块实现 1.0 | Red Hat 收购 JBoss |
| 2008 | EclipseLink 被选为 JPA 2.0 的 RI | — | — |
| 2009 | JPA 2.0 定稿 | — | Java EE 6 |
| 2010 | — | 3.5，JPA 2.0 兼容实现 | — |
| 2011 | JPA 2.1（JSR 338）启动 | 4.0，ServiceRegistry | — |
| 2013 | JPA 2.1 定稿 | 4.3 | Java EE 7 |
| 2015 | — | 5.0，新引导 | — |
| 2016 | — | 5.2，`Session extends EntityManager` | — |
| 2017 | JPA 2.2；Java EE 宣布移交 Eclipse | — | Java EE 8 |
| 2018 | Eclipse 下的 2.2.x 再发布 | 5.3，JPA 2.2，Byte Buddy | Jakarta EE 品牌确立 |
| 2019 | 2.2.3 | — | IBM 完成对 Red Hat 的收购 |
| 2020 | Jakarta Persistence 3.0，包名迁移 | 5.x 的 Jakarta 过渡开始 | Jakarta EE 9 |
| 2021 | — | 5.6，移除 Javassist | — |
| 2022 | 3.1 | 6.0，SQM 与新类型系统 | Jakarta EE 10；Spring Boot 3 |
| 2023 | — | 6.2–6.4：结构体、注解查询、软删除、向量 | — |
| 2024 | 3.2（04-30） | 6.6：Jakarta Data | — |
| 2025 | Jakarta EE 11 采用 3.2 | 7.0（05-19）：Java 17、Apache-2.0、TCK | Spring Boot 4 一代开始对齐 7.x |
| 2026 | 4.0 里程碑 M1–M6，未 Final | 7.4 稳定；8.0 Beta 对齐 4.0 草案 | — |

## 7.2 术语

| 词 | 含义 |
|---|---|
| JPA | Java Persistence API 的历史名称。1.0–2.2 使用。口语里仍常把 Jakarta Persistence 叫 JPA |
| Jakarta Persistence | 2019 年后的规范名。3.0 起包名为 `jakarta.persistence` |
| 持久化单元 | 一份映射加一个数据库配置。对应一个 `EntityManagerFactory` |
| 持久化上下文 | 一个 `EntityManager` 里按主键索引的托管实体集合，即一级缓存 |
| 提供者（provider） | 规范的实现。Hibernate、EclipseLink 都是 |
| TCK | 技术兼容性套件。声称实现某一版规范时要跑的测试 |
| RI | Reference Implementation。JPA 1.0 是 TopLink Essentials，2.0 起是 EclipseLink |
| HQL | Hibernate 查询语言，JPQL 的超集，6.0 起与 JPQL 共用 SQM |
| SQM | Semantic Query Model。Hibernate 6 里查询的中间表示 |
| 方言 | 某个数据库的 SQL 渲染策略 |
| Envers | Hibernate 的审计模块 |

## 7.3 容易记错的点

这些点在第二手材料里经常写反。本文按规范页、Hibernate 发布页和官方导读核对过。

1. **规范负责人是 Linda DeMichiel。** Gavin King 是把 Hibernate 模型带进 EJB 3 专家组的人，持久化文本的共同作者还包括 Mike Keith。把 Gavin King 写成 JPA spec lead，和规范文件不符。
2. **参考实现不是 Hibernate。** 1.0 的参考实现是 TopLink Essentials，2.0 起是 EclipseLink。Hibernate 是兼容实现，也是 Spring 生态的默认实现。
3. **3.0 只改包名。** `jakarta.persistence` 第一次出现是在 Jakarta Persistence 3.0（2020），不是在 2.2 移交的时候。2.2 的 Eclipse 再发布仍然使用 `javax.persistence`。
4. **功能演进在 3.1 才恢复。** 3.1 加入 `UUID` 和一批函数；大的 API 现代化在 3.2（2024）。
5. **4.0 在 2026-10-18 尚未 Final。** 最新 tag 是 4.0.0-M6（2026-07-03）。Eclipse 项目页上的 2026-04-14 记录不能当作规范最终发布日。
6. **Hibernate 6 对齐的是 Persistence 3.0/3.1，不是 3.2。** `SchemaManager`、标准 `UNION`、record 嵌入属于 Hibernate 7。
7. **Apache-2.0 从 7.0.0.Beta5（2025-03-21）开始。** 7.0.0.Beta4 仍是 LGPL 2.1。
8. **hibernate.org 的 3.0 系列日期不可靠。** 多个 3.0 构建被归档成 2005-11。3.0 的公告口径是 2005 年 3 月。
9. **`Session` 从 5.2 起才继承 `EntityManager`。** 在 4.x 里它们是两套要互相 unwrap 的对象。
10. **旧的 `org.hibernate.Criteria` 在 6.0 删除。** 标准 Criteria API 保留，并进入 SQM。

## 7.4 延伸阅读

| 材料 | 用来做什么 |
|---|---|
| https://jakarta.ee/specifications/persistence/3.2/ | 当前正式规范的特性清单 |
| https://jakarta.ee/specifications/persistence/4.0/ | 4.0 草案，阅读时确认页面是否仍标为 under development |
| https://github.com/jakartaee/persistence/tags | 核对 4.0 是否已经出现不带 M 的 Final tag |
| https://hibernate.org/orm/releases/7.4/ 与 `/8.0/` | 稳定线与开发线的兼容性表 |
| https://docs.hibernate.org/orm/7.0/migration-guide/ | 3.2 升级时的签名碰撞 |
| Hibernate 6 导读 *An Introduction to Hibernate* | 官方对早期历史和"三种 API"的说法 |
| `org.hibernate.cfg.JpaComplianceSettings` 的 Javadoc | 合规开关的权威列表 |
| 本仓库《Spring Data JPA.md》 | 这些概念在 Spring 仓库代理和 Hibernate 运行时源码中的落点 |
| 本仓库《Spring Framework.md》 | `JpaTransactionManager` 与容器如何绑定 `EntityManager` |

## 结语

JPA 用二十年把"POJO 怎么对应一张表、一个工作单元怎么跟踪这些对象"收成了可以换实现的标准。Hibernate 比这份标准更早，参与把它写成标准，又在每一版定稿之后继续覆盖标准还没写到的数据库现实。

到 2026 年 10 月，已经可以依赖的公共语言是 **Jakarta Persistence 3.2**，对应的成熟引擎是 **Hibernate ORM 7.4**。4.0 和 Hibernate 8.0 是同一条草案的两侧，适合跟踪，不适合当作生产基线。写新代码时，先用规范里的词；只在过滤器、批量、自然键、数据库专有类型这些规范没有句子的地方，再使用 `org.hibernate`。
