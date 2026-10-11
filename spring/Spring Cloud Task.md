# Spring Cloud Task 深度源码解析（写给初学者的架构全景）

> **本文基于的源码**：`D:\code\3rd\spring-cloud-task`，版本 **5.1.0-SNAPSHOT**（main 分支，Git commit `192a861d`，2026 年 9 月后随 Spring Cloud 2025.x 列车演进的快照；上一个正式版为 2025-11-24 发布的 5.0.0）。文中所有【源码证据】的文件路径与行号均为对该快照实际读取所得。
>
> **版本取舍说明**：Spring Cloud Task 的核心链路——`TaskLifecycleListener` 的"启动记一笔、结束记一笔"、`TaskRepository/TaskExplorer` 双门面、`TaskExecutionDao` 三层仓储——自 1.x 以来骨架几乎未变，因此本文内容对使用 2.x/3.x（Spring Boot 2.x/3.x）的读者同样适用。1.x → 5.x 的演进对比与"为什么跳过 4.x"见 1.6 节；文中"某特性属于哪个版本"的结论均经本地 git 标签（115 个 tag）与 `@since` 注解逐项实证。
>
> **阅读约定**：每章"先白话、后源码"——先用一两句人话讲清"这是什么、为什么需要它"，再给出源码证据链（`模块/src/main/java/.../类名.java` + 方法名 + 行号 + 代码片段）。行号只对该快照精确，读者用 IDEA 打开源码按类名 + 方法名定位即可。

## 如何读这份文档

如果你是 Spring Cloud Task 初学者，推荐两遍读法：

- **第一遍（建立地图，1 小时）**：只读第一章（总览）每节的开头白话段、各章的"小结"节、以及第八章（贯通视图）。目标是能回答：Task 和一个普通 Spring Boot 应用差在哪？"任务执行记录"存在哪张表？任务的 exit code 是怎么算出来的？
- **第二遍（深入源码）**：对照每一章的【源码证据】，打开源码逐行读。顺序建议：第三章（生命周期引擎，最核心）→ 第二章（仓储层）→ 第四章（装配与单实例锁）→ 第六章（与 Batch 的集成）→ 第五章（观测）与第七章（事件外发，可跳读）。

---

# 一、总览：Spring Cloud Task 的定位、设计哲学与整体架构

## 1.1 一句话定位

**Spring Cloud Task 是一个"短生命周期应用的一次执行"的记录框架**：一个 Spring Boot 应用启动、跑一段业务逻辑（通常由 `ApplicationRunner`/`CommandLineRunner` 或一个 Spring Batch Job 承载）、然后退出——这样"跑一次就结束"的进程称为**任务（Task）**。Spring Cloud Task 做的事只有两件：**在任务开始时往数据库记一笔（START_TIME、参数、任务名），在任务结束时再记一笔（END_TIME、exit code、错误信息）**，让"一次运行"变成一条可查询、可审计、可关联下游（如 Spring Batch Job）的数据。

官方文档自述其定位："Spring Cloud Task's goal is to create the ability for users to develop and run 'short-lived' microservices using Spring"（`docs/modules/ROOT/pages/overview.adoc`——任务就是有限数据处理、按需运行、然后停止的微服务）。

它**不负责**：调度任务何时跑（那是 Spring Batch 的 JobScheduler、Quartz、k8s CronJob 或 Spring Cloud Data Flow 的事）、任务内部怎么跑（那是业务代码或 Spring Batch 的事）。它只负责**让"跑过"这件事留下痕迹**。Spring Cloud Data Flow 正是靠这张表来展示任务历史、重启失败任务的。

## 1.2 设计哲学：读源码前先记住四句话

1. **生命周期事件驱动，业务代码零侵入**。框架的唯一入口是一个 `SmartLifecycle` + `ApplicationListener`（`TaskLifecycleListener`）：容器刷新完自动触发"记开始"，`ApplicationReadyEvent`/`ApplicationFailedEvent` 自动触发"记结束"。你的 `ApplicationRunner`、你的 Bean，一个字段都不用改（见 3.1 节）。
2. **"记"与"查"分离，都薄到只有一层实现**。写入走 `TaskRepository`，查询走 `TaskExplorer`，两个接口背后各只有一个 `Simple` 实现（`SimpleTaskRepository`/`SimpleTaskExplorer`），再往下委托给 DAO 层（`JdbcTaskExecutionDao`/`MapTaskExecutionDao`）。没有 ORM、没有缓存，就是 `NamedParameterJdbcTemplate` 直写 SQL（见 2.2、2.3 节）。
3. **可插拔点集中在 `TaskConfigurer`**。仓储用什么数据源、事务管理器选哪个、任务名叫什么，全部通过一个 `TaskConfigurer` SPI 暴露；默认实现 `DefaultTaskConfigurer` 在"有 DataSource 就 JDBC，没有就 Map"之间自动降级（见 4.3 节）。
4. **与生态组合而非重造**。单实例互斥复用 Spring Integration 的 JDBC 锁（见 4.7 节）；观测复用 Micrometer Observation（见第五章）；与 Spring Batch 的集成只是往 `Job` 上注册了一个 `JobExecutionListener`（见第六章）；事件外发复用 Spring Cloud Stream 的 `StreamBridge`（见第七章）。整个项目没有一个"轮子"是重新发明的。

## 1.3 模块分层全景

spring-cloud-task 是多模块 Maven 工程，根目录实测共 9 个模块。按"用户感知"分层如下：

```
┌─────────────────────────── 便捷上层 ────────────────────────────┐
│  spring-cloud-starter-task（一把梭 starter：core+batch+stream）   │
│  spring-cloud-starter-single-step-batch-job（属性化单步批处理任务）│
├─────────────────────────── 集成层 ─────────────────────────────┤
│  spring-cloud-task-batch（Task 与 Batch 的关联/启动/失败传导）     │
│  spring-cloud-task-stream（任务与批处理事件外发到消息通道）        │
├─────────────────────────── 核心层 ─────────────────────────────┤
│  spring-cloud-task-core                                          │
│  ├ configuration/：@EnableTask、自动配置、TaskConfigurer SPI、    │
│  │   单实例锁、观测包装器、AOT hints                              │
│  ├ listener/：TaskLifecycleListener（心脏）、监听器接口与注解、    │
│  │   Micrometer 观测发射器                                       │
│  └ repository/：TaskExecution 域模型、TaskRepository/TaskExplorer │
│      双门面、DAO 层、schema 脚本、DatabaseType                    │
├─────────────────────────── 支撑 ────────────────────────────────┤
│  spring-cloud-task-dependencies（BOM）                            │
│  spring-cloud-task-integration-tests  spring-cloud-task-samples   │
│  docs（Antora 文档源）                                             │
└──────────────────────────────────────────────────────────────────┘
```

各模块的 `src/main/java` 类数量：core 72 个、batch 16 个、stream 25 个、single-step-batch-job starter 26 个（实测统计）。核心层三个包的职责一句话：**configuration 负责"把它装配出来"，listener 负责"把一生记下来"，repository 负责"记到哪里、怎么记"**。

## 1.4 依赖图（以 core 模块 pom 实证）

```
              spring-boot-autoconfigure（自动配置基座）
              spring-boot-sql（数据库初始化协调，Boot 4 新模块化产物）
              spring-jdbc（NamedParameterJdbcTemplate / 事务）
                        ▲    ▲    ▲
        ┌───────────────┘    │    └────────────────┐
        │                    │                     │
 spring-batch-infrastructure   │              spring-data-commons
 （ID 递增器 DataFieldMaxValue  │              （TaskExplorer 的
   Incrementer、分页查询提供者） │                Page/Pageable 签名）
        │                    │                     │
        └──► spring-cloud-task-core ◄──────────────┘
                  │  ▲（optional）
                  │  ├── micrometer-core / micrometer-observation（观测）
                  │  ├── spring-integration-core/jdbc（单实例锁，optional）
                  │  └── spring-orm（JPA 事务管理器探测，optional）
                  │
    ┌─────────────┼──────────────────┐
    ▼             ▼                  ▼
task-batch    task-stream        starter-task
（batch 5.x    （Stream 5.x        （core+batch+stream
 JobOperator）   StreamBridge）      + spring-boot-starter-jdbc）
```

真实依赖声明（摘自 `spring-cloud-task-core/pom.xml`，scope 已逐项核对）：

| 依赖 | scope | 用在哪 |
|---|---|---|
| spring-boot-autoconfigure | compile | 自动配置基座 |
| spring-boot-sql | compile | `DatabaseInitializerDetector` 协调建表时机（见 4.8 节） |
| spring-jdbc | compile | DAO 层 SQL、`JdbcTransactionManager` |
| spring-batch-infrastructure | compile | ID 递增器工厂、分页查询提供者（**注意是 infrastructure 而非整个 batch core**） |
| spring-data-commons | compile | `TaskExplorer` 返回 `Page`/`Pageable` |
| micrometer-core / micrometer-observation | compile | 观测发射器（第五章） |
| spring-integration-core / spring-integration-jdbc | compile, optional | 单实例锁（4.7 节），不用则不进 classpath |
| spring-orm | compile, optional | 探测 `EntityManager` 以选择 `JpaTransactionManager` |

一个有意思的细节：core 的**运行时**只需要 `spring-batch-infrastructure`（借它的递增器与分页抽象），`spring-batch-core` 只出现在 task-batch 模块——也就是说，一个不用 Batch 的纯 Task 应用，classpath 里不会有完整的 Spring Batch。

## 1.5 关键问题 → Spring Cloud Task 方案映射（全文导览）

| 你关心的问题 | Task 的方案 | 详见 |
|---|---|---|
| 任务跑过没跑过、什么时候跑的、参数是什么？ | `TASK_EXECUTION` + `TASK_EXECUTION_PARAMS` 两张表，启动/结束各写一行 | 2.4、3.1 |
| 任务失败了怎么知道、exit code 从哪来？ | `ApplicationFailedEvent` + `ExitCodeEvent` → `calcExitStatus()` 三级计算链 | 3.3 |
| 怎么在任务前后/失败时插自己的逻辑？ | `TaskExecutionListener` 接口 + `@BeforeTask/@AfterTask/@FailedTask` 注解 | 3.4 |
| 同名任务不许并发跑怎么办？ | `single-instance-enabled` → Spring Integration JDBC 锁 + 领导者选举 | 4.7 |
| 怎么把观测数据进 Zipkin/Prometheus？ | Micrometer `Observation`，观测名 `spring.cloud.task` | 第五章 |
| 怎么把 Batch Job 的执行归到这次任务名下？ | `TaskBatchExecutionListener` 写 `TASK_TASK_BATCH` 关联表 | 6.1 |
| Batch Job 失败要让任务也算失败怎么办？ | `fail-on-job-failure` → 轮询 JobExecution，失败抛 `TaskException` | 6.3 |
| 任务执行事件怎么发到消息中间件？ | task-stream 模块，`StreamBridge` 发 `TaskExecution` | 第七章 |
| 数据库表谁建？建错了时机怎么办？ | `TaskRepositoryInitializer` + Boot sql-init 协调器 | 2.6、4.8 |

## 1.6 版本演进：1.0 → 5.1（115 个 tag 的考古）

本地仓库实测共 115 个 tag，最早 `v1.0.0.M1`（2016-01-26），最新 `v5.1.0-M1`（2026-09-18）。主干版本节点与对应生态（版本对齐关系经 tag 日期与分支名实证）：

| 版本 | 发布时间 | 生态基线 | 关键变化（@since 考古实证） |
|---|---|---|---|
| 1.0.x | 2016 | Boot 1.3/1.4 | 初始版：TaskLifecycleListener、JDBC/Map DAO、schema 脚本 |
| 1.1.x | 2017 | Boot 1.4/1.5 | `TaskExecution.externalExecutionId`、`errorMessage` 字段加入（`TaskExecution.java:75,82` 的 @since 1.1.0） |
| 1.2.x | 2018 | Boot 1.5 | `parentExecutionId` 加入（`TaskExecutionDao.java:58` @since 1.2.0），任务父子层级（组合任务）落地 |
| 1.3.x | 2018-10 | Boot 2.0 | 跟进 Boot 2 / Spring Cloud Finchley |
| 2.0.x | 2019 | Boot 2.1/2.2 | **单实例锁** `SingleInstanceTaskListener`（`@since 2.0.0`，`SingleInstanceTaskListener.java:53`） |
| 2.1.x | 2020 | Boot 2.2/2.3 | `TaskLifecycleConfiguration`（@since 2.1）、注解扫描改由 `TaskListenerExecutorObjectFactory`（@since 2.1.0）承担 |
| 2.2.x | 2020 | Boot 2.3/2.4 | `TaskObservations`（@since 2.2）——Micrometer 观测首发；`TaskJobLauncherApplicationRunner` @since 2.3.0 在本系列末期（2021-03 v2.3.1） |
| 2.4.x | 2021-12 | Boot 2.6 | 过渡版本，收尾 Boot 2 时代 |
| 3.0.x | 2022-12 | **Boot 3.0**，Spring Cloud 2022.0 | **javax → jakarta** 全面迁移；`ObservationTaskAutoConfiguration`（@since 3.0.0）把业务 Runner 包进观测；AOT `TaskRuntimeHints`（@since 3.0）；事务管理器可用属性指定（`NoTransactionManagerProperty` @since 3.0） |
| 3.1.x | 2023-12 | Spring Cloud 2023.0 | 常规演进 |
| 3.2.x | 2024-12 | Spring Cloud 2024.0 | 常规演进 |
| 3.3.x | 2025-05 | Spring Cloud 2025.0 | Boot 3.5 时代最后一支 3.x |
| **5.0.x** | 2025-11-24 | **Boot 4.0**，Spring Cloud 2025.1 | **跳过 4.x**：3.3.1-SNAPSHOT 直接改号为 5.0.0-SNAPSHOT（commit `aaf7a748`，2025-06-06，提交信息明确写着 "Update spring cloud commons to 5.0.0-SNAPSHOT / Update spring boot to 4.0.0-SNAPSHOT"），与 spring-cloud-commons 5.0 大版本对齐；适配 Boot 4 的模块化（`spring-boot-sql`、`org.springframework.boot.batch.autoconfigure.*` 新包路径）；Batch 侧切换到 `JobOperator` API |
| 5.1.0（main） | 2026-09 出 M1 | Spring Cloud 2025.x 后续 | 本文基线 |

两个考古彩蛋：

- **为什么没有 4.x？** 不是发布过 4 又撤回，而是整个 4.0 从未发布——2025-06 开发主线直接从 3.3 跳到 5.0，让 Task 的大版本号与 spring-cloud-commons 5.0（Boot 4 列车）看齐。源码里留了一处化石：`TaskJobLauncherApplicationRunner.getNextJobParameters` 的 Javadoc 写着 `@since 4.0`（`TaskJobLauncherApplicationRunner.java:249`）——这是在"4.0-SNAPSHOT"开发周期里写的代码，随后整个版本号被跳过。
- **旧 tag 已被清理**：远端仓库只保留了部分旧 tag（如 `v2.0.0.RELEASE`、`v2.1.0.RELEASE` 已不在远端），早期版本的特性归属主要靠源码里的 `@since` 注解与分支名（`1.0.x` ~ `3.3.x`、`5.0.x`）实证。

## 1.7 全文章节地图

```
一、总览（本章）——定位、哲学、模块、依赖、演进
二、域模型与仓储层——TaskExecution 长什么样、记到哪、怎么记
三、生命周期引擎——TaskLifecycleListener 的四个触发点与 exit code 计算（核心章）
四、装配与配置——@EnableTask 与自动配置双轨制、TaskConfigurer SPI、单实例锁
五、观测——两次 Micrometer Observation 的发射点与标签全景
六、与 Spring Batch 的集成——关联表、Job 启动、失败传导、单步批任务 starter
七、事件外发——task-stream 模块与 Batch 事件族
八、贯通视图——三条时间线：启动、成功任务的一生、失败任务的一生
九、附录——接口速查、属性速查、源码阅读入口
```

---

# 二、域模型与仓储层（repository 包）

## 2.1 TaskExecution：一次执行的全部字段

**白话**：Task 的一切，最终都落到一个 POJO 上。它就是数据库里 `TASK_EXECUTION` 一行的 Java 映射，10 个字段（`spring-cloud-task-core/src/main/java/org/springframework/cloud/task/repository/TaskExecution.java:38-87`）：

```java
public class TaskExecution {
    private long executionId;             // 主键，由 TASK_SEQ 序列分配
    private Long parentExecutionId;       // 父任务 ID（组合任务场景，@since 1.2.0）
    private Integer exitCode;             // 退出码：0 成功，非 0 失败
    private String taskName;              // 任务名（默认取 context id）
    private LocalDateTime startTime;      // 开始时间
    private LocalDateTime endTime;        // 结束时间——为 null 即"还在跑"
    private String exitMessage;           // 结束时的自定义消息（可被监听器改写）
    private String externalExecutionId;   // 平台侧 ID（如 Data Flow 分配的，@since 1.1.0）
    private String errorMessage;          // 失败时的堆栈（@since 1.1.0）
    private List<String> arguments;       // 启动参数（原始 args）
}
```

注意两个语义约定，后面到处会用到：

- **`endTime == null` ⇔ 任务还在运行**。`TaskExplorer.findRunningTaskExecutions`、`getRunningTaskExecutionCount` 的实现全是 `WHERE END_TIME IS NULL`（`repository/dao/JdbcTaskExecutionDao.java:138-142`）。
- **`exitCode` 必须非负**。写入端有断言：`exit code must be greater than or equal to zero`（`repository/support/SimpleTaskRepository.java:193`）——exit code 语义直接对接进程退出码。

## 2.2 双门面：TaskRepository（写）与 TaskExplorer（读）

**白话**：框架把"写"和"读"拆成两个接口，用户代码通常只用到其中一个场景：写入由框架自己在生命周期里调用，你几乎不用碰；查询（自己查历史、做运维面板）用 `TaskExplorer`。

【源码证据】写入门面 `repository/TaskRepository.java:32-128`，共 7 个方法，每个都带着一行很有讲究的注解：

```java
@Transactional("${spring.cloud.task.transaction-manager:springCloudTaskTransactionManager}")
TaskExecution createTaskExecution(TaskExecution taskExecution);   // :68-69
@Transactional("${spring.cloud.task.transaction-manager:springCloudTaskTransactionManager}")
TaskExecution completeTaskExecution(long executionId, Integer exitCode, ...); // :55-57
```

这个 SpEL 占位符是事务配置的钥匙：**默认用名为 `springCloudTaskTransactionManager` 的 Bean；你也可以用属性 `spring.cloud.task.transaction-manager` 直接指定任意事务管理器名**（比如主业务的那个），无须改代码。配套的条件装配见 4.4 节。

读取门面 `repository/TaskExplorer.java:32-152`，签名全部基于 Spring Data 的 `Page`/`Pageable`（这就是 1.4 节里 spring-data-commons 依赖的用途），关键方法：`getTaskExecution(id)`、`findRunningTaskExecutions(name, page)`、`getTaskExecutionCountByTaskName`、以及两个"查最新"方法 `getLatestTaskExecutionsByTaskNames`/`getLatestTaskExecutionForTaskName`（`:140,150`，Data Flow 用它展示"该任务最近一次运行"）。

## 2.3 DAO 层：JdbcTaskExecutionDao 与 MapTaskExecutionDao

**白话**：门面之下是真正干活的两兄弟：`JdbcTaskExecutionDao`（生产用，`NamedParameterJdbcTemplate` 手写 SQL）和 `MapTaskExecutionDao`（内存 Map，仅测试用）。没有 JPA、没有 MyBatis——因为写入的就那么几行 SQL，手写最直白。

【源码证据】SQL 常量全景（`repository/dao/JdbcTaskExecutionDao.java:94-159`）。所有 SQL 里的表名都用 `%PREFIX%` 占位，由 `getQuery()` 替换成可配置前缀（默认 `TASK_`）：

- 插入：`INSERT into %PREFIX%EXECUTION(TASK_EXECUTION_ID, EXIT_CODE, START_TIME, TASK_NAME, LAST_UPDATED, EXTERNAL_EXECUTION_ID, PARENT_EXECUTION_ID) values (...)`（`:94-97`）——注意创建时 `EXIT_CODE` 写 null（还没结束）。
- 参数逐条插：`INSERT into %PREFIX%EXECUTION_PARAMS(TASK_EXECUTION_ID, TASK_PARAM)`（`:99-100`）——参数一条一行。
- 完成：`UPDATE %PREFIX%EXECUTION set END_TIME=..., EXIT_CODE=..., EXIT_MESSAGE=..., ERROR_MESSAGE=..., LAST_UPDATED=... where TASK_EXECUTION_ID=...`（`:115-117`）。
- 运行中判断：`... where END_TIME IS NULL`（`:138-142`）。
- **"查最新"的三层嵌套 SQL**（`:144-151`）：先按任务名分组取 `MAX(START_TIME)`，再 join 回主表取同开始时间的 `MAX(TASK_EXECUTION_ID)`（同一秒启动的并列者取 ID 大的），最后 join 回主表拿整行——这就是 `getLatestTaskExecutionsByTaskNames` Javadoc 里"最新按开始时间，平手按 ID"语义的实现。
- 关联查询：`FIND_TASK_EXECUTION_BY_JOB_EXECUTION_ID` 查 `%PREFIX%TASK_BATCH` 表（`:155-159`）——第六章的关联表在这里露出。

创建执行的主流程（`:218-237`）：`getNextExecutionId()`（从 `TASK_SEQ` 递增器取号）→ 组装 `TaskExecution` → 插主表 → `insertTaskArguments` 逐条插参数。完成前还有一道存在性校验：ID 不存在直接抛 `IllegalStateException("Invalid TaskExecution, ID ... not found")`（`:282-285`）。

排序白名单（`:161-174`）：`validSortColumns` 静态集合限制了分页排序只能用 `TASK_EXECUTION_ID`、`START_TIME` 等 10 个列名——防 SQL 注入的做法（列名无法参数化，只能白名单）。

## 2.4 表结构与 schema 脚本：4 表 1 序列

**白话**：整个框架的持久化面就这么大——H2 的建表脚本（`spring-cloud-task-core/src/main/resources/org/springframework/cloud/task/schema-h2.sql`）一共 38 行：

```sql
CREATE TABLE TASK_EXECUTION (          -- 主表（:2-13）
    TASK_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
    START_TIME TIMESTAMP(9), END_TIME TIMESTAMP(9),
    TASK_NAME VARCHAR(100),            -- 对应 MAX_TASK_NAME_SIZE = 100
    EXIT_CODE INTEGER,
    EXIT_MESSAGE VARCHAR(2500),        -- 对应 MAX_EXIT_MESSAGE_SIZE = 2500
    ERROR_MESSAGE VARCHAR(2500),       -- 对应 MAX_ERROR_MESSAGE_SIZE = 2500
    LAST_UPDATED TIMESTAMP(9),
    EXTERNAL_EXECUTION_ID VARCHAR(255),
    PARENT_EXECUTION_ID BIGINT
);
CREATE TABLE TASK_EXECUTION_PARAMS (   -- 参数表，一行一个参数（:15-20）
    TASK_EXECUTION_ID BIGINT NOT NULL, TASK_PARAM VARCHAR(2500),
    constraint TASK_EXEC_PARAMS_FK foreign key (TASK_EXECUTION_ID)
        references TASK_EXECUTION(TASK_EXECUTION_ID));
CREATE TABLE TASK_TASK_BATCH (         -- Task↔Batch Job 关联表（:22-27，第六章主角）
    TASK_EXECUTION_ID BIGINT NOT NULL, JOB_EXECUTION_ID BIGINT NOT NULL, ...);
CREATE SEQUENCE TASK_SEQ;              -- executionId 取号序列（:29）
CREATE TABLE TASK_LOCK (               -- 单实例锁表（:31-38，4.7 节主角）
    LOCK_KEY CHAR(36) NOT NULL, REGION VARCHAR(100) NOT NULL,
    CLIENT_ID CHAR(36), CREATED_DATE TIMESTAMP(9) NOT NULL,
    EXPIRED_AFTER TIMESTAMP(9) NOT NULL, ...);
```

字段长度不是随便定的——`SimpleTaskRepository` 里的三个常量与之严格对应：`MAX_EXIT_MESSAGE_SIZE = 2500`、`MAX_TASK_NAME_SIZE = 100`、`MAX_ERROR_MESSAGE_SIZE = 2500`（`repository/support/SimpleTaskRepository.java:42,47,52`），写入前 `trimMessage` 按上限截断（`:197-203`），任务名超长则直接抛 `IllegalArgumentException`（`:186-188`）。这是"先裁剪后入库"的防御：错误堆栈动辄几 KB，不裁剪必然撑爆列。

9 种数据库各有一份 schema（db2/h2/hsqldb/mariadb/mysql/oracle/postgresql/sqlserver，同目录 `schema-*.sql`），差异主要在序列的写法：MySQL 用 `TASK_SEQ` 表模拟序列（`schema-mysql.sql:29`），PostgreSQL 用原生 `CREATE SEQUENCE TASK_SEQ MAXVALUE 9223372036854775807 NO CYCLE`（`schema-postgresql.sql:29`）。

### ID 递增器与 SQL Server 特例

executionId 的取号不走数据库自增，而是 Spring Batch infrastructure 的 `DataFieldMaxValueIncrementer`——按数据库类型选择实现（Oracle 用序列、MySQL 用表、SQL Server 用 `SqlServerSequenceMaxValueIncrementer` 等），序列名固定为 `表前缀 + "SEQ"`（`repository/support/TaskExecutionDaoFactoryBean.java:139-140`）。SQL Server 还有一段特例处理：若库里存在同名**表**（老版本遗留）则直接用表递增器，否则用序列递增器（`:100-110`，`isSqlServerTableSequenceAvailable` 查元数据判断）。

## 2.5 DatabaseType：识别你连的是哪家数据库

**白话**：建表、取号、分页 SQL 都因数据库而异，所以第一步是"认库"。`DatabaseType` 枚举覆盖 11 种产品：HSQL、H2、Oracle、MySQL、MariaDB、Postgres、SQLServer、DB2，以及 DB2 的三个变种 DB2VSE/DB2ZOS/DB2AS400（`repository/support/DatabaseType.java:38-93`）。

识别逻辑就是读 JDBC 元数据的 `getDatabaseProductName()`（`:117-124`），DB2 家族要再读产品版本号细分变种：版本以 "ARI" 开头是 VSE、"DSN" 开头是 z/OS（`:137-142`）。这套代码是从 Spring Batch 的同名类演化来的（Task 1.0 的作者 Michael Minella 正是 Batch 团队出身），连 DB2 变种判断的正则 `V\dR\d[mM]\d` 都原样保留。

## 2.6 TaskRepositoryInitializer：表是谁建的

**白话**：第一次跑任务时表还不存在怎么办？框架默认替你建。`TaskRepositoryInitializer` 是一个 `InitializingBean`，在 Bean 初始化时执行建表脚本。

【源码证据】`repository/support/TaskRepositoryInitializer.java:100-129`，四个要点：

1. **脚本定位用平台占位符**：默认位置是 `classpath:org/springframework/cloud/task/schema-@@platform@@.sql`（`:56-57`），运行时把 `@@platform@@` 替换成小写数据库名（`hsql`→`hsqldb`、`postgres`→`postgresql` 等映射在 `:106-120`——这些手工映射恰好补齐了 2.5 节枚举名与脚本文件名之间的差异）。
2. **`setContinueOnError(true)`**（`:125`）：脚本执行"错了也不停"——因为建表脚本没有 `IF NOT EXISTS`（Oracle 等库不支持统一写法），表已存在时靠吞掉"table already exists"错误实现幂等。
3. **开关**：`spring.cloud.task.initialize-enabled=false` 关闭（`:101-102`；老属性 `initialize.enable` 已废弃但兼容，`@Value` 默认值在 `:68-69`）。
4. **前缀限制**：只有当前缀是默认的 `TASK_` 时才自动建表（`:103-104`）——自定义前缀意味着表结构归你自己管。

## 2.7 本章小结

- 一切围绕 `TaskExecution` 10 字段展开；`endTime IS NULL` 即运行中，`exitCode` 即进程退出码。
- `TaskRepository`（写）/`TaskExplorer`（读）双门面 → `Simple` 实现 → `JdbcTaskExecutionDao`/`MapTaskExecutionDao`，三层各自只有一个实现，SQL 全部手写。
- 持久化面 = 4 表 1 序列（EXECUTION / EXECUTION_PARAMS / TASK_BATCH / LOCK + TASK_SEQ），字段长度与 `SimpleTaskRepository` 的 MAX 常量一一对应，写入前截断。
- 建表由 `TaskRepositoryInitializer` 自动完成（continueOnError 幂等），取号由 Batch infrastructure 的递增器完成。

---

# 三、生命周期引擎（listener 包，本章核心）

## 3.1 TaskLifecycleListener：一个类，四个触发点

**白话**：整个框架的心脏。它既是 `SmartLifecycle`（容器刷新完自动 start），又是 `ApplicationListener`（听 Boot 的就绪/失败事件），四个触发点把"任务的一生"钉在时间轴上：

| 触发点 | 动作 | 源码位置 |
|---|---|---|
| `SmartLifecycle.start()`（refresh 收尾时自动调） | `doTaskStart()`：写入"开始"记录，回调所有 `onTaskStartup` | `listener/TaskLifecycleListener.java:410-413` |
| `ApplicationReadyEvent` | `doTaskEnd()`：写入"结束"记录（成功路径） | `:178-180` |
| `ApplicationFailedEvent` | `doTaskEnd()`：写入"结束"记录（失败路径，带堆栈） | `:171-174` |
| `SmartLifecycle.stop()` | `doTaskEnd()`：兜底结束（如启动监听器抛异常导致没发 Ready/Failed 事件） | `:415-418` |

类声明与身份（`:83-84`）：

```java
public class TaskLifecycleListener
        implements ApplicationListener<ApplicationEvent>, SmartLifecycle, DisposableBean, Ordered
```

`getOrder()` 返回 `HIGHEST_PRECEDENCE`（`:434-437`）、`isAutoStartup()` 返回 true（`:397-400`）、`getPhase()` 为 0（`:426-428`）——确保它在生命周期 Bean 里最先启动，在业务 Runner 执行**之前**把"开始"记录落库。

**为什么 start 落在 SmartLifecycle 而不是别的钩子？** 因为 SmartLifecycle 的启动发生在 `AbstractApplicationContext.finishRefresh()` 的 `lifecycleProcessor.onRefresh()` 里，早于 Boot 的 `callRunners()`（业务 Runner 执行）——这保证了数据库里"开始时间"先于任何业务逻辑执行的时间点。而结束监听 `ApplicationReadyEvent`/`ApplicationFailedEvent` 则覆盖了"Runner 正常跑完"与"Runner 抛异常/启动失败"两条路。

## 3.2 doTaskStart：两种起跑姿势

【源码证据】`doTaskStart()`（`:257-308`）的核心分叉：

- **姿势一（默认，新建执行）**：new 一个 `TaskExecution`（名字来自 `TaskNameResolver`，参数来自 `ApplicationArguments.getSourceArgs()`，时间取当前），交给 `taskRepository.createTaskExecution(taskExecution)`——INSERT 主表 + 逐条 INSERT 参数表，拿回分配好的 `executionId`（`:286-294`）。
- **姿势二（外部指定 ID）**：若配置了 `spring.cloud.task.executionid`，则不新建而是**续接一条已存在的执行记录**：先 `taskExplorer.getTaskExecution(executionid)` 校验存在且未结束（`Assert.isNull(taskExecution.getEndTime(), "...task is already complete")`，`:277-279`），再 `startTaskExecution(...)` UPDATE 该行的 START_TIME/TASK_NAME 等字段（`:272-285`）。这服务于"先在平台侧登记、稍后启动进程"的部署模式（Data Flow 场景）。

随后回调所有监听器的 `onTaskStartup`（`:300`，细节见 3.4）。注意 **doTaskStart 自身抛异常会立刻转入 doTaskEnd 再把异常抛出去**（`:302-307`）——起跑失败也会留下一条失败记录（exit code 1）。

一个防御细节：`start()` 里 `this.started = true` 只在 `doTaskStart` 正常返回后执行（`:410-413`）；而 `doTaskStart` 内部 `if (!this.started)` 互斥（`:259`），重复的启动事件只打 error 日志不重复记账（`:296-298`）。

## 3.3 doTaskEnd 与 exit code 的三级计算链

【源码证据】`doTaskEnd()`（`:192-220`）按序做五件事：

1. 补 `endTime`（`:194`）；若持有 `applicationFailedException`，把**完整堆栈**转字符串存入 `errorMessage`（`:196-198`，`stackTraceToString` 在 `:183-190`）。
2. 算 exit code（`:200`，见下）。
3. 失败路径先回调 `onTaskFailed`，再回调 `onTaskEnd`（`:201-205`）——两条回调都可能**改写 exitMessage**（`setExitMessage` 只在监听器返回了非空消息时覆盖，`:222-226`）。
4. 落库：`taskRepository.completeTaskExecution(id, exitCode, endTime, exitMessage, errorMessage)`（`:206-208`）。
5. 若 `spring.cloud.task.closecontext_enabled=true` 且容器还活着，主动关闭上下文（`:212-214`）——默认 false，即任务结束后容器是否退出交给非守护线程决定。

**exit code 计算链 `calcExitStatus()`（`:228-255`）是理解"任务失败"的关键**，三级依次降级：

```java
private int calcExitStatus() {
    int exitCode = 0;
    if (this.exitCodeEvent != null) {                 // ① Boot 的 ExitCodeEvent 优先
        exitCode = this.exitCodeEvent.getExitCode();
    } else if (this.listenerFailed || this.applicationFailedException != null) {
        // ② 解包 InvocationTargetException，拿到"真"异常
        if (exception instanceof ExitCodeGenerator) { // ③ 异常自己实现了 ExitCodeGenerator
            exitCode = ((ExitCodeGenerator) exception).getExitCode();
        } else {
            exitCode = 1;                             // ④ 兜底 1
        }
    }
    return exitCode;
}
```

①号位有个精妙的时序配合：Boot 在 `SpringApplication.handleRunFailure` 里**先**发布 `ExitCodeEvent`（`spring-boot/core/spring-boot/.../SpringApplication.java:810` → `:885`，本地 commit `f67ec6b3ca7`），**再**通过 `listeners.failed` 发布 `ApplicationFailedEvent`（`:812`）。`TaskLifecycleListener.onApplicationEvent` 由此先在 `ExitCodeEvent` 分支把退出码暂存（`:175-177`），随后在 `ApplicationFailedEvent` 分支里就能用它（`:171-174`）。也就是说：**你用 `ExitCodeExceptionMapper` 映射的自定义退出码，会原样进入 TASK_EXECUTION.EXIT_CODE 列**。

③号位的"解包"值得注意：注解监听器（`@BeforeTask` 等）抛出的异常经反射包装成 `TaskExecutionException`（内含 `InvocationTargetException`），计算 exit code 前先把真实业务异常剥出来（`:236-244`），否则 `ExitCodeGenerator` 判断会失效。

## 3.4 监听器体系：接口 + 三个注解

**白话**：想在任务开始/结束/失败时插自己的逻辑，有两条等价的路：实现 `TaskExecutionListener` 接口（注册为 Bean 即被自动收集），或者在任意 Bean 的方法上打注解。

【源码证据】接口 `listener/TaskExecutionListener.java`——三个方法对应三个时机：

```java
public interface TaskExecutionListener {
    void onTaskStartup(TaskExecution taskExecution);
    void onTaskFailed(TaskExecution taskExecution, Throwable throwable);
    void onTaskEnd(TaskExecution taskExecution);
}
```

注解族在 `listener/annotation/`：`@BeforeTask`、`@AfterTask`、`@FailedTask`（失败多一个 `Throwable` 参数）。它们的反射执行器 `TaskListenerExecutor` 本身也是一个 `TaskExecutionListener` 实现，内部按 `Map<Method, Set<Object>>` 持有"方法 → Bean 实例"映射并逐个 `method.invoke`（`listener/annotation/TaskListenerExecutor.java:57-79, 81-104`）。

扫描器 `TaskListenerExecutorObjectFactory`（`listener/TaskListenerExecutorObjectFactory.java:52-165`，@since 2.1.0）在任务启动时遍历容器全部 BeanDefinition：跳过 scoped proxy 外壳、处理代理目标类（`:83-107`，仿照 `CommonAnnotationBeanPostProcessor` 的处理模式），用 `MethodIntrospector.selectMethods` 找出带注解的方法（`:155-163`），并把"没有注解的类"放入 `nonAnnotatedClasses` 缓存避免重复扫（`:123-132`）。

**监听器的组装与顺序**（`doTaskStart`，`TaskLifecycleListener.java:260-265`）：容器里所有 `TaskExecutionListener` Bean + 一个注解执行器 `TaskListenerExecutor`，合成一张列表。调用顺序有个容易踩坑的设计——

- `onTaskStartup` 时**倒序**调用（`Collections.reverse`，`:316`）；
- `onTaskEnd`/`onTaskFailed` 时**正序**调用（`:339, 364`）。

这样"Bean 声明在前"的监听器包裹在最外层，形成洋葱模型：先启动的后结束。**单实例锁正是靠这个顺序保证"加锁"最先执行、"解锁"最后执行**（4.7 节）。

监听器回调拿到的 `TaskExecution` 是**防御性拷贝**（`getTaskExecutionCopy`，`:387-395`，arguments 也转成 unmodifiableList）——监听器改状态影响不到框架记账，但 `exitMessage`/`exitCode` 的修改通过返回值收集回主记录（`:222-226`，`:380`）。

## 3.5 失败的传导与记录

三个标志位配合完成"失败"的闭环（字段定义 `:111-125`）：

- `listenerFailed`：任一监听器回调抛异常即置位（`:323, 351, 369`）。
- `listenerException`：最近一次监听器异常，供 exit code 解包用（`:117`）。
- `applicationFailedException`：Boot 失败事件带来的原始异常。

失败路径下 `errorMessage` 的拼装规则：业务异常堆栈（doTaskEnd 直接写入）+ 监听器自身再抛错时的补充信息（`invokeOnTaskEnd` 里 `"%s :Task also threw this Exception: %s"` 双段拼接，`:344-350`）——保证"业务为什么挂"和"收尾时又挂了什么"都留痕。若 `onTaskFailed` 回调自己也炸了，强制 `setExitCode(1)`（`:380`）。

## 3.6 本章小结

- 一个类 `TaskLifecycleListener` 承担全部记账：SmartLifecycle.start → 记开始，Ready/Failed 事件 → 记结束，stop 兜底。
- exit code 三级链：Boot `ExitCodeEvent`（先发）> `ExitCodeGenerator` 型异常 > 常量 1；成功则为 0。
- 监听器双形态（接口/注解）、洋葱顺序（启动倒序、结束正序）、防御性拷贝、exitMessage 可被回调改写。
- 起跑失败也会转 doTaskEnd 留一条 exit code 1 的记录——"没有黑洞运行"是这条链路的设计底线。

---

# 四、装配与配置（configuration 包）

## 4.1 双轨装配：为什么还要 @EnableTask

**白话**：加上 `spring-cloud-starter-task` 依赖后，自动配置会把"仓储、事务管理器、表初始化"都装好；但**任务的记账引擎 `TaskLifecycleListener` 必须由 `@EnableTask` 显式开启**——这是 Task 系列文档里最容易被初学者问到的"为什么"。

【源码证据】两条轨道的证据链：

- 自动配置轨道：`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（core）注册了三个自动配置——`SingleTaskConfiguration`（单实例锁）、`SimpleTaskAutoConfiguration`（仓储主装配）、`ObservationTaskAutoConfiguration`（观测包装）。
- 手动轨道：`@EnableTask` 只有一行内容——`@Import(TaskLifecycleConfiguration.class)`（`configuration/EnableTask.java:57`），而 `TaskLifecycleConfiguration` 的 `@PostConstruct` 里 new 出 `TaskLifecycleListener` 并暴露为 Bean（`configuration/TaskLifecycleConfiguration.java:94-104`）。
- 交叉验证：全仓库检索 `TaskLifecycleConfiguration` 的引用**只有** `EnableTask` 一处；官方 getting-started 示例同样要求 `@SpringBootApplication + @EnableTask` 连用（`docs/modules/ROOT/pages/getting-started.adoc:81-85`）。

为什么这样设计？`TaskLifecycleListener` 依赖 `ApplicationArguments`、容器引用等运行时对象，装配它意味着"这个应用**就是**一个任务"；而 `SimpleTaskAutoConfiguration` 装的仓储组件对"任务库管理员"（只查不写）的应用也有用。**把"是不是任务"的决定权留给用户**，避免任何带依赖的应用都凭空多出一个生命周期监听器。

顺带一提，`TaskLifecycleConfiguration` 用 `@PostConstruct` 手工 new 而不是 `@Bean` 方法直接构造，是因为监听器构造参数里需要 `ObjectProvider<ApplicationArguments>` 的"可能没有"语义与 `TaskListenerExecutorObjectFactory` 的容器引用（`:68-84`）——这些都是实例级而非 Bean 方法级的依赖。

## 4.2 SimpleTaskAutoConfiguration：仓储主装配

**白话**：自动配置的"总装车间"。它决定用哪个 `TaskConfigurer`，并把仓储四件套（Repository/Explorer/NameResolver/Initializer）暴露成 Bean。

【源码证据】`configuration/SimpleTaskAutoConfiguration.java`：

- 开关与基座：`@AutoConfiguration + @EnableTransactionManagement + @EnableConfigurationProperties(TaskProperties.class)`，全局开关 `spring.cloud.task.autoconfiguration.enabled`（默认 true，`:57-64`）；`@Import(DatabaseInitializationDependencyConfigurer.class)`（`:64`）接入 Boot 的 sql-init 依赖协调（4.8 节）。
- `TaskConfigurer` 决策 `getDefaultConfigurer()`（`:146-171`）：
  - 容器里没有 `TaskConfigurer` Bean → 若**恰好一个** DataSource 就用 `new DefaultTaskConfigurer(dataSource, tablePrefix, context)`，否则用无数据源版本（Map 仓储），并 `registerSingleton` 注册回容器（`:160`）；
  - 恰好一个 → 直接用用户的；
  - 多个 → `IllegalStateException("Expected one TaskConfigurer but found ...")`。
- 多数据源防御 `verifyEnvironment()`（`:173-185`）：统计 DataSource 时**排除 scoped proxy 目标**（`:178-179`，`ScopedProxyUtils.isScopedTarget`）——`@ConfigurationProperties` 化的多数据源场景里 proxy bean 会让计数翻倍误报。

暴露的 Bean：`taskRepository()`（强转回 `SimpleTaskRepository`，`:88-91`）、`taskExplorer()`、`taskNameResolver()`、`taskRepositoryInitializer()`（初始化器的数据源来自 configurer 的 `getTaskDataSource()`，`:110-118`）、以及 `@Profile("cloud")` 下的 `TaskObservationCloudKeyValues`（Cloud Foundry 环境的观测标签源，`:120-124`）。

## 4.3 TaskConfigurer SPI 与 DefaultTaskConfigurer

**白话**：想换仓储行为（比如换成自己的表结构、自己的事务管理器），实现 `TaskConfigurer` 接口即可，它就是 Task 的"配置总开关"。

接口 `configuration/TaskConfigurer.java` 定义五个取值方法：`getTaskRepository()`、`getTaskExplorer()`、`getTaskDataSource()`、`getTaskNameResolver()`、`getTransactionManager()`。

默认实现 `DefaultTaskConfigurer`（`configuration/DefaultTaskConfigurer.java:55-227`）的两个核心决策：

- **仓储降级**（`:164-172`）：有 DataSource → `TaskExecutionDaoFactoryBean(dataSource, tablePrefix)` → JdbcTaskExecutionDao；没有 → 无参 FactoryBean → MapTaskExecutionDao（仅测试用途，Javadoc 明确警告）。
- **事务管理器选择链 `getTransactionManager()`**（`:196-221`），三级降级：
  1. classpath 有 `jakarta.persistence.EntityManager` **且**容器里有 EntityManager Bean → `JpaTransactionManager`（`:200-204`，用 `Class.forName` 探测而非直接 import，保证无 JPA 时不报 ClassNotFound）；
  2. 否则有 DataSource → `JdbcTransactionManager`（`:211`）；
  3. 都没有 → `ResourcelessTransactionManager`（`:217`，来自 `org.springframework.batch.infrastructure.support.transaction`——又一个借自 Batch infrastructure 的件）。

## 4.4 事务管理：接口注解上的 SpEL 与属性直通车

4.2 节的 `springCloudTaskTransactionManager` Bean 有个条件——`@Conditional(NoTransactionManagerProperty.class)`（`SimpleTaskAutoConfiguration.java:93-97`）。该条件的实现 `configuration/NoTransactionManagerProperty.java`（@since 3.0）很直白：**只要配置了 `spring.cloud.task.transaction-manager` 属性，框架就不再注册自己的事务管理器**，`TaskRepository` 上的 `@Transactional("${spring.cloud.task.transaction-manager:...}")` 会直接解析到你指定的那个（2.2 节）。

这套设计解决一个真实痛点：任务的记账写入若与你业务共用一个事务管理器，记账就能和业务写库同生共死；若分开（默认），任务记录独立提交，业务回滚不会连带抹掉"这次运行发生过"的事实。

## 4.5 TaskProperties 全景

`configuration/TaskProperties.java`（`@ConfigurationProperties("spring.cloud.task")`，`:31-32`）全部 9 个属性：

| 属性 | 默认 | 含义 | 消费点 |
|---|---|---|---|
| `external-execution-id` | null | 平台侧 ID 透传 | doTaskStart（`TaskLifecycleListener.java:291`） |
| `executionid` | null | 复用既有执行的 ID（姿势二） | doTaskStart（`:272-285`） |
| `parent-execution-id` | null | 父任务 ID（组合任务） | 同上（`:284,292`） |
| `table-prefix` | `TASK_` | 表名前缀 | DAO/建表/锁表全链 |
| `closecontext-enabled` | false | 结束后关容器 | doTaskEnd（`:212-214`） |
| `single-instance-enabled` | false | 单实例互斥 | 4.7 节 |
| `single-instance-lock-ttl` | Integer.MAX_VALUE | 锁 TTL（毫秒） | JdbcLockRegistry（`:160`） |
| `single-instance-lock-check-interval` | 500 | 抢锁轮询间隔（毫秒） | 等锁循环（`:114`） |
| `initialize-enabled` | null（→true） | 是否自动建表 | TaskRepositoryInitializer（`:101-102`） |

全局总开关 `spring.cloud.task.autoconfiguration.enabled` 与观测开关 `spring.cloud.task.observation.enabled`（`ObservationTaskAutoConfiguration.java:37`）不在本类，分别在各自自动配置上。

## 4.6 任务名从哪来：SimpleTaskNameResolver

**白话**：`TASK_EXECUTION.TASK_NAME` 的值来自 `TaskNameResolver`。默认实现两级优先：`spring.cloud.task.name` 属性 → 容器 ID（`spring.application.name` 会成为 context id 的一部分）。

【源码证据】`repository/support/SimpleTaskNameResolver.java:43-60`：

```java
@Value("${spring.cloud.task.name:}")
public void setConfiguredName(String configuredName) { ... }

public String getTaskName() {
    if (StringUtils.hasText(this.configuredName)) return this.configuredName;
    else return this.context.getId().replace(":", "_");   // 冒号换下划线
}
```

`replace(":", "_")` 是因为 Boot 的 context id 形如 `application-1`（主机名:端口:++）带冒号，而任务名要进 SQL 与 Data Flow 的展示。

## 4.7 单实例锁：SingleInstanceTaskListener

**白话**：同名任务不允许多实例同时跑（比如两个 Pod 同时调度了同一个批处理）。打开 `spring.cloud.task.single-instance-enabled=true` 后，框架在 `@BeforeTask` 阶段抢一把**数据库锁**，抢不到直接抛异常终止任务。锁的实现完全复用 Spring Integration 的 JDBC 锁体系，表就是 schema 里的 `TASK_LOCK`。

【源码证据】装配端 `configuration/SingleTaskConfiguration.java`（`:37-62`）：`@Order(HIGHEST_PRECEDENCE)` 保证它在监听器链最前；没有 DataSource 时降级为 `PassThruLockRegistry`（锁形同虚设，仅保持链路一致，`:53-56`）——真正的互斥依赖 JDBC。

实现端 `configuration/SingleInstanceTaskListener.java`（@since 2.0.0，`:55-163`），工作机制是一段"用锁实现领导者选举"的仪式：

1. **抢锁**：`@BeforeTask lockTask`（`:102-131`）构造 `LockRegistryLeaderInitiator`，候选者身份为 `new DefaultCandidate(String.valueOf(executionId), taskName)`（`:107-108`——executionId 每次运行都不同，但锁在数据库里按 taskName + region 区分，所以互斥的是**同名任务**），`start()` 后进入忙等循环，每 500ms（`single-instance-lock-check-interval`）检查一次（`:112-119`）。
2. **结果通知**：`onApplicationEvent` 听 Spring Integration 的领导事件——`OnGrantedEvent`（抢到，`lockReady=true`）或 `OnFailedToAcquireMutexEvent`（抢失败，`lockFailed=true`）（`:143-151`）。抢失败则销毁 initiator 并抛 `TaskExecutionException("Task with name ... is already running.")`（`:120-128`）——异常沿 3.4 节的监听器链上抛，任务以失败记账。
3. **释放**：`@AfterTask`/`@FailedTask` 都调 `lockRegistryLeaderInitiator.destroy()`（`:133-141`）——洋葱顺序（3.4 节）保证解锁最后发生。
4. **锁的底层**：`getDefaultLockRegistry`（`:153-161`）用 `DefaultLockRepository(dataSource, region=executionId字符串)` + `setPrefix(taskProperties.getTablePrefix())`——**前缀复用任务的 `TASK_`，所以锁表名是 `TASK_LOCK`**（与 schema-h2.sql:31 呼应），任务应用无须为锁单独建表；`JdbcLockRegistry` 带 TTL（默认 `Integer.MAX_VALUE` 毫秒）防止崩溃实例把锁永远占死。

## 4.8 数据库初始化协调：两个 Detector

**白话**：你的业务表初始化和 Task 的表初始化谁先谁后？Boot 的 sql-init 机制靠"探测器"协调：Task 声明"我是数据库初始化者，依赖我的 Bean 请等我把表建完"。

【源码证据】core 的 `META-INF/spring.factories` 注册了两类探测器：

```properties
org.springframework.boot.sql.init.dependency.DatabaseInitializerDetector=\
org.springframework.cloud.task.configuration.TaskRepositoryDatabaseInitializerDetector
org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitializationDetector=\
org.springframework.cloud.task.configuration.TaskRepositoryDependsOnDatabaseInitializationDetector
```

前者声明 `TaskRepositoryInitializer` 是初始化者（`configuration/TaskRepositoryDatabaseInitializerDetector.java`，排序 `LOWEST_PRECEDENCE - 99` 让 Task 建表尽量靠后、业务脚本先跑），后者让所有依赖 `TaskRepository`/`TaskExplorer` 类型的 Bean 自动获得 `dependsOnDatabaseInitialization`。这就是 4.2 节 `@Import(DatabaseInitializationDependencyConfigurer.class)` 的另一半。

## 4.9 AOT 与 GraalVM native

`configuration/TaskRuntimeHints.java`（@since 3.0）注册三类 native hints：全部 8 个 `schema-*.sql` 资源、`TaskExecution` 的反射访问（getter/setter）、以及 HikariCP 存在时的 `Statement[]` 等反射。配合 `SimpleTaskAutoConfiguration` 的常规自动配置，任务应用可以编译成 GraalVM native image（这是 3.0 代随 Boot 3 引入的能力，5.x 延续）。

## 4.10 本章小结

- 双轨装配：starter 依赖自动装好仓储与辅助件；`@EnableTask` 决定"我是任务"，拉起 `TaskLifecycleListener`。
- `TaskConfigurer` 是唯一扩展点；默认实现的三级事务管理器链（JPA → JDBC → Resourceless）与两级仓储链（JDBC → Map）覆盖所有环境。
- 事务管理器可用属性 `spring.cloud.task.transaction-manager` 直通指定（3.0+）。
- 单实例锁 = Spring Integration JDBC 锁 + 领导者选举事件忙等，锁表 `TASK_LOCK` 与业务表同前缀同库。
- 表初始化时机由 sql-init 探测器体系协调，业务脚本先跑、Task 建表靠后。

---

# 五、观测：两次 Micrometer Observation

## 5.1 两个观测点

**白话**：Task 的观测有"内外两圈"：外圈是**任务本身**的观测（从 start 到 end 覆盖全程，名字 `spring.cloud.task`，由 `TaskObservations` 发射）；内圈是**业务 Runner** 的观测（把你的每个 `ApplicationRunner`/`CommandLineRunner` 包一层，能单独看到业务执行耗时与异常）。两圈观测名相同、标签不同，靠 `runner.bean-name` 区分内圈。

【源码证据·外圈】`listener/TaskObservations.java`（@since 2.2，`:34-127`），由 `TaskLifecycleListener` 在三个时机驱动：

- `onTaskStartup`（`:71-108`）：创建 `TaskExecutionObservationContext` → `TaskExecutionObservation.TASK_ACTIVE.observation(...)` 打上四个低基数标签（task.name / task.execution.id / task.parent.execution.id / task.external.execution.id，`:80-87`）→ 若在 Cloud Foundry profile 下再打 7 个 `spring.cloud.task.cf.*` 云环境标签（`:89-104`）→ `observation.start()` 并 `openScope()`（`:105-107`）——**这个 scope 一直持有到任务结束**，期间所有下游调用都带上任务 trace。
- `onTaskFailed`（`:114-117`）：置状态 `failure` 并 `observation.error(throwable)` 记录异常。
- `onTaskEnd`（`:119-125`）：回填 exitCode → `scope.close()` + `observation.stop()`——观测至此完整闭合。

【源码证据·内圈】`configuration/observation/` 包（@since 3.0.0）：`ObservationTaskAutoConfiguration`（`observation/ObservationTaskAutoConfiguration.java:35-53`）注册两个 `BeanPostProcessor`，把容器里每个 `ApplicationRunner`/`CommandLineRunner` 包装成 `ObservationApplicationRunner`/`ObservationCommandLineRunner`（BPP 逻辑 `ObservationApplicationRunnerBeanPostProcessor.java:24-33`，注意 `!(bean instanceof ObservationApplicationRunner)` 防二次包装）。包装器在 `run` 里开一个名为 `spring.cloud.task`、带 `spring.cloud.task.runner.bean-name` 标签的子观测（`ObservationApplicationRunner.java:42-58`，try-with-resources 管 scope，异常走 `observation.error`）。

## 5.2 标签全景与自定义

观测文档类 `TaskExecutionObservation`（`listener/TaskExecutionObservation.java:30-160`）定义了全部键名；默认约定 `DefaultTaskExecutionObservationConvention` 结束时补齐 `spring.cloud.task.status`（success/failure）与 `spring.cloud.task.exit.code`（`listener/DefaultTaskExecutionObservationConvention.java:37-49`）：

| 标签 | 含义 | 赋值时机 |
|---|---|---|
| `spring.cloud.task.name` | 任务名 | startup |
| `spring.cloud.task.execution.id` | 执行 ID | startup |
| `spring.cloud.task.parent.execution.id` | 父执行 ID | startup |
| `spring.cloud.task.external.execution.id` | 平台侧 ID | startup |
| `spring.cloud.task.exit.code` | 退出码 | end |
| `spring.cloud.task.status` | success / failure | end |
| `spring.cloud.task.cf.*`（7 个） | CF 组织/空间/应用信息 | startup，仅 `cloud` profile |
| `spring.cloud.task.runner.bean-name` | 内圈：业务 Runner 的 Bean 名 | 内圈 |

自定义出口：容器里放一个 `ObservationConvention` Bean 即被 `TaskLifecycleListener` 构造器拾取（`TaskLifecycleListener.java:103,141`）并传给 `TaskObservations` 作为 customObservationConvention——`TaskObservationConvention` 接口只要求按 `TaskExecutionObservationContext` 返回键值对。

与 tracing 的关系：观测挂在你配置的 `ObservationRegistry` 上，若注册表带 Tracing 处理器（如 Zipkin/OTel），任务区间自然成为一条 trace——外圈 scope 打开后，内圈 Runner 观测与 Batch 的 Job 观测都会成为子 span。这也是 5.0 基线里 core 依赖 `micrometer-observation` 为 compile scope 的原因。

## 5.3 本章小结

- 外圈观测覆盖任务全程（scope 从 start 持到 end），内圈观测逐 Runner 计量，同名不同标签。
- 标签即任务画像：名字、ID 链、退出码、最终状态；CF 云标签按 profile 追加。
- 自定义只需一个 `ObservationConvention` Bean；指标/链路走 Micrometer 标准管线，Task 不发明任何上报协议。

---

# 六、与 Spring Batch 的集成（task-batch 模块）

## 6.1 关联记录：TaskBatchExecutionListener

**白话**：一个 Batch Job 跑在任务进程里，两者各有各的表（BATCH_JOB_EXECUTION 与 TASK_EXECUTION）。task-batch 模块的全部意义就是维护 `TASK_TASK_BATCH` 这张两列的关联表，让"Job 执行 X 发生在任务执行 Y 里"可查。

【源码证据】`batch/listener/TaskBatchExecutionListener.java:35-74`——这个类有**双重身份**：

```java
public class TaskBatchExecutionListener implements JobExecutionListener, Ordered, TaskExecutionListener
```

- 作为 `TaskExecutionListener`：`onTaskStartup` 只是**记下当前 TaskExecution**（`:53-55`）。
- 作为 `JobExecutionListener`：`beforeJob` 时把 `(taskExecutionId, jobExecutionId)` 插入 `TASK_TASK_BATCH`（`:58-67`，JDBC 实现 `batch/listener/support/JdbcTaskBatchDao.java:40` 的 `INSERT INTO %PREFIX%TASK_BATCH (TASK_EXECUTION_ID, JOB_EXECUTION_ID) VALUES (?, ?)`）。
- `getOrder()` 为 `HIGHEST_PRECEDENCE`（`:70-72`）——最先注册、最先记录。
- 兜底日志：Job 不在任务里跑却挂了这个监听器时打 warn 而非报错（`:59-61`）。

查询侧的能力在 `TaskExplorer` 上：`getTaskExecutionIdByJobExecutionId(jobExecutionId)` 与 `getJobExecutionIdsByTaskExecutionId(taskExecutionId)`（`repository/TaskExplorer.java:112,121`），SQL 就是 2.3 节 `:155-159` 那两条常量。

## 6.2 监听器怎么挂到你的 Job 上：一个 BeanPostProcessor

**白话**：你没有手动给 Job 注册任何监听器，但关联表里却有了数据——因为框架用 `BeanPostProcessor` 在每个 `AbstractJob` Bean 初始化后偷偷把监听器塞进去。

【源码证据】`batch/configuration/TaskBatchExecutionListenerBeanPostProcessor.java:37-58`：

```java
if (bean instanceof AbstractJob) {
    if (length != 1) {  // TaskBatchExecutionListener 必须恰好一个
        throw new IllegalStateException(...);
    }
    ((AbstractJob) bean).registerJobExecutionListener(
        this.applicationContext.getBean(TaskBatchExecutionListener.class));
}
```

支持 `spring.cloud.task.batch.listener.names` 之外的精细控制——`setJobNames` 白名单过滤（`:41-43`，由 `TaskBatchExecutionListenerFactoryBean` 按属性注入）。装配入口 `TaskBatchAutoConfiguration`（`batch/configuration/TaskBatchAutoConfiguration.java:42-84`）的条件：容器里有 `Job` Bean **且**有 `TaskLifecycleListener`（`:43`——再一次证明 `@EnableTask` 是前提），属性开关 `spring.cloud.task.batch.listener.enabled`（新旧两个拼写都认，`:44-45`）。仓储选择：`TaskBatchExecutionListenerFactoryBean` 按 configurer 有无 DataSource 决定 Jdbc/Map 实现，Map 模式下还会**反射抽取** `SimpleTaskExplorer` 内部的 DAO 来保证两套 Map 存储同源（`batch/configuration/TaskBatchExecutionListenerFactoryBean.java:102-103`，`ReflectionUtils.findField(SimpleTaskExplorer.class, "taskExecutionDao")`）。

## 6.3 Job 启动与失败传导：TaskJobLauncherApplicationRunner

**白话**：Boot 自带 `JobLauncherApplicationRunner`（启动时自动跑 Job），但它跑完 Job 失败**不会**让应用退出码非 0——对"任务"语义这是错的。task-batch 提供的 `TaskJobLauncherApplicationRunner` 做两件事：接管 Job 启动（含重启参数合并），并**同步等待 Job 结果，失败就抛异常**，让 3.3 节的 exit code 链把任务记成失败。

【源码证据】装配 `batch/configuration/TaskJobLauncherAutoConfiguration.java:42-59`：条件 `@Conditional(JobLaunchCondition.class)`（`:42`；`batch/configuration/JobLaunchCondition.java`——要求 `spring.cloud.task.batch.fail-on-job-failure=true` 且 `spring.batch.job.enabled` 非 false），`@AutoConfigureBefore(BatchAutoConfiguration.class)` 抢在 Boot 的 Batch 自动配置之前注册，从而**顶替** Boot 的 `JobLauncherApplicationRunner`；内部还有 `@ConditionalOnClass(name = "org.springframework.boot.batch.autoconfigure.JobLauncherApplicationRunner")`（`:51`）——这个包路径正是 Boot 4 模块化后的新家，5.0 版对齐 Boot 4 的直接证据。

实现 `batch/handler/TaskJobLauncherApplicationRunner.java`（@since 2.3.0，`:72-284`）三个要点：

1. **execute 的重启智慧**（`:120-158`）：Job 实例已存在且上次是 STOPPED/FAILED 且 Job 可重启 → 用上次的**标识性参数**合并新参数重跑（`:127-140`，`removeNonIdentifying` 剔除非标识参数，`:206-217`）；全新实例且有 `JobParametersIncrementer` → `getNextJobParameters` 基于上次参数递增（`:143-149`，方法体 `:251-282`）。最终 `jobOperator.start(job, parameters)`（`:150`，5.0 代已从旧 `JobLauncher` 切到 Batch 6 的 `JobOperator`）。
2. **监控与失败传导**（`:160-182`）：用 Batch infrastructure 的 `RepeatTemplate` 每 5 秒（`spring.cloud.task.batch.fail-on-job-failure-poll-interval`，`batch/configuration/TaskBatchProperties.java:50-53`）轮询所有 JobExecution 状态，任一 FAILED → `throwJobFailedException` 抛出 `TaskException`（`:191-204`）→ 沿 Boot 事件链变成 `ApplicationFailedEvent` → 任务以 exit code 1 记账。**Job 失败 = 任务失败**的闭环就打在这里。
3. **事件桥接**（`:151-153`）：启动后发布 Boot 的 `JobExecutionEvent`（来自 `org.springframework.boot.batch.autoconfigure`），供事件监听者使用。

## 6.4 属性化单步 Job：single-step-batch-job starter

**白话**：最常见的任务形态是"读一个文件/表/Kafka 主题 → 处理 → 写到另一个地方"。这个 starter 让你**不写一行 Java**，用属性就能组装出一个单步 Spring Batch Job 并包成任务运行。

【源码证据】`spring-cloud-starter-single-step-batch-job` 模块的 `AutoConfiguration.imports` 注册了 10 个自动配置（flat-file 读写、JDBC cursor 读/batch 写、Kafka 读写、Rabbit 读写，外加总装）。总装 `batch/autoconfigure/SingleStepJobAutoConfiguration.java:77-91`：

```java
@Bean
@ConditionalOnMissingBean
@ConditionalOnProperty(prefix = "spring.batch.job", name = "job-name")
public Job job(ItemReader<Map<String,Object>> itemReader, ItemWriter<Map<String,Object>> itemWriter) {
    SimpleStepBuilder<...> stepBuilder = new StepBuilder(stepName, jobRepository)
        .chunk(chunkSize, transactionManager).reader(itemReader);
    stepBuilder.processor(this.itemProcessor);           // processor 可选
    Step step = stepBuilder.writer(itemWriter).build();
    return new JobBuilder(jobName, jobRepository).start(step).build();
}
```

`SingleStepJobProperties`（prefix `spring.batch.job`）要求 `job-name`、`step-name`、`chunk-size` 三者非空（`validateProperties` 断言 `:70-76`）；item 统一用 `Map<String,Object>` 承载，各 ItemReader/Writer 自动配置把平面文件列、JDBC 行、Kafka 消息都映射成 Map。典型组合：`spring.batch.job.job-name=importJob` + `flat-file-item-reader.name/resource/names` + `jdbc-batch-item-writer.name/sql/...` + `spring.cloud.task.batch.fail-on-job-failure=true`——一条完整的"文件进库"任务管道，零 Java 代码。

## 6.5 本章小结

- 关联表 `TASK_TASK_BATCH` 由双重身份的 `TaskBatchExecutionListener` 在 `beforeJob` 写入，BPP 自动挂载到所有 `AbstractJob`。
- `TaskJobLauncherApplicationRunner` 顶替 Boot 的 Job 启动器：重启参数合并、incrementer 递增、轮询等待、失败抛 `TaskException` 传导为任务失败。
- 前提链要记牢：`@EnableTask`（有 TaskLifecycleListener）→ task-batch 自动配置才生效。
- single-step-batch-job starter 用属性拼出单步 Job，是"配置即任务"的极致形态。

---

# 七、事件外发（task-stream 模块）

## 7.1 任务事件：三个时机一条通道

**白话**：Data Flow 这类平台想知道"某任务开始了/结束了"，但不想轮询数据库。task-stream 模块把 `TaskExecution` 对象在三个生命周期时机推到 Spring Cloud Stream 的绑定通道上。

【源码证据】`listener/TaskEventAutoConfiguration.java`（stream 模块，`:39-82`）：

- 条件五连：classpath 有 `StreamBridge`（`:40`）、容器有 `TaskLifecycleListener`（`:41`，又见 `@EnableTask` 前提）、未配置 `spring.batch.job.jobName`（`:42`，批量场景走 7.2 的 Job 事件）、`spring.cloud.task.events.enabled` 非 false（`:44-45`）。
- `@PropertySource("classpath:/org/springframework/cloud/task/application.properties")`（`:47`）引入默认绑定名等属性基线；`@AutoConfigureBefore(BindingServiceConfiguration.class)`（`:48`）确保绑定属性先于 Stream 主装配生效。
- 发射本体是一个匿名 `TaskExecutionListener`（`:59-78`）：`onTaskStartup`/`onTaskEnd`/`onTaskFailed` 三个时机都 `streamBridge.send(bindingName, taskExecution)`——绑定名由 `TaskEventProperties` 控制（默认 `task-events`），目标中间件由你引入的 Stream binder 决定（Rabbit/Kafka 皆可）。

## 7.2 Batch 事件族：七个监听器发 Job/Step 细粒度事件

**白话**：任务级事件太粗，Batch 的 chunk 读写进度也想外发。stream 模块提供了 7 个 `EventEmitting*Listener`（Job/Step Execution、Chunk、Item Read/Write/Process、Skip），各自实现 Batch 对应监听器接口、把事件对象经 `MessagePublisher` 发出。

【源码证据】以 `batch/listener/EventEmittingJobExecutionListener.java:34-73` 为例：`beforeJob`/`afterJob` 都发 `new JobExecutionEvent(jobExecution)` 到 `taskEventProperties.getJobExecutionEventBindingName()` 绑定。事件 DTO（`JobExecutionEvent`、`StepExecutionEvent`、`JobParametersEvent` 等）是对 Batch 领域对象的可序列化投影；消息头常量集中在 `BatchJobHeaders`（如 `batch-job-execution-id`）。装配在 `batch/listener/BatchEventAutoConfiguration.java`，按各监听器开关（`spring.cloud.task.events.batch-event-listener-enabled` 等）逐个注册，`TaskBatchEventListenerBeanPostProcessor` 负责挂载。

## 7.3 本章小结

- 任务事件：三时机 × 一通道（`StreamBridge`），前提同样是 `@EnableTask`。
- Batch 事件族把 chunk 级进度也搬上消息通道，是 Data Flow 实时看板的数据源。
- 两个模块（batch/stream）都遵循同一模式：**监听器 + 自动配置 + BPP 挂载**，不侵入业务与 Batch 核心配置。

---

# 八、贯通视图：三条时间线

## 8.1 时间线一：从 main 到"开始记录"（启动）

```
main() → SpringApplication.run
  ├─ prepareEnvironment / createApplicationContext
  ├─ refresh()
  │    └─ finishRefresh → lifecycleProcessor.onRefresh()
  │         └─ TaskLifecycleListener.start()（SmartLifecycle，phase 0，order 最高）
  │              └─ doTaskStart()
  │                   ├─ 组装监听器链：容器 TaskExecutionListener Bean 们 + TaskListenerExecutor
  │                   ├─ TaskRepository.createTaskExecution()（INSERT 主表+参数表，TASK_SEQ 取号）
  │                   └─ 倒序回调 onTaskStartup（含 SingleInstanceTaskListener 抢锁、
  │                        TaskBatchExecutionListener 记下 execution、TaskObservations 开观测）
  ├─ ApplicationStartedEvent
  ├─ callRunners() → 你的 ApplicationRunner/CommandLineRunner（或 TaskJobLauncherApplicationRunner 跑 Job）
  └─ ApplicationReadyEvent → doTaskEnd()
```

装配侧的前置事件（更早）：`@EnableTask` 导入 `TaskLifecycleConfiguration`；`SimpleTaskAutoConfiguration` 的 `@PostConstruct` 选定 `TaskConfigurer`、注册仓储 Bean；`TaskRepositoryInitializer.afterPropertiesSet` 执行 `schema-<platform>.sql`。

## 8.2 时间线二：一次成功任务的一生

```
[start] SmartLifecycle.start → 记开始（endTime=null, exitCode=null）
[run  ] 业务 Runner 在内圈观测 scope 内执行
[end  ] ApplicationReadyEvent → doTaskEnd
         ├─ endTime = now
         ├─ exitCode = calcExitStatus()：无 ExitCodeEvent、无失败 → 0
         ├─ 正序回调 onTaskEnd（可改写 exitMessage）
         ├─ completeTaskExecution（UPDATE：END_TIME/EXIT_CODE/EXIT_MESSAGE/LAST_UPDATED）
         └─ [可选] closecontext_enabled=true → context.close()
[jvm  ] SpringApplication.exit 收集 ExitCodeGenerator Bean → 进程退出码 0
```

## 8.3 时间线三：一次失败任务的一生

```
[run  ] Runner 抛异常（或 Job FAILED 被 TaskJobLauncherApplicationRunner 转抛 TaskException）
[fork ] SpringApplication.handleRunFailure
         ├─ handleExitCode → ExitCodeEvent（exitCode 来自 ExitCodeExceptionMapper/异常型 Generator）
         │    └─ TaskLifecycleListener.onApplicationEvent 暂存 exitCodeEvent
         └─ listeners.failed → ApplicationFailedEvent
              └─ doTaskEnd
                   ├─ errorMessage = 完整堆栈
                   ├─ exitCode = ExitCodeEvent 优先；否则异常实现 ExitCodeGenerator 用之；否则 1
                   ├─ 先 onTaskFailed（TaskObservations 记 error、状态=failure）再 onTaskEnd
                   ├─ completeTaskExecution（exitCode≠0, errorMessage 非空）
                   └─ [可选] context.close()
[jvm  ] 进程以非 0 退出码结束——数据库记录、观测数据、进程退出码三者一致
```

## 8.4 从源码中提炼的四个设计模式视角

- **观察者**：`TaskLifecycleListener` 本身是对 Boot 事件与 SmartLifecycle 两个观察者协议的复合实现；监听器链的倒序/正序构成洋葱模型。
- **工厂方法**：`TaskExecutionDaoFactoryBean` 按 DataSource 有无与数据库类型产出 DAO；递增器由 `DefaultDataFieldMaxValueIncrementerFactory` 按库分派。
- **策略**：`TaskConfigurer` 五个 getter 每个都是一条可替换策略；事务管理器三级链是策略降级的典型写法。
- **装饰**：观测包装器把任意 `ApplicationRunner` 包成 `ObservationApplicationRunner`；`TaskJobLauncherApplicationRunner` extends Boot 同名类并在 `execute()` 前后增强。

---

# 九、附录

## 9.1 关键接口速查表

| 类型 | 类 | 一句话职责 |
|---|---|---|
| 域模型 | `repository.TaskExecution` | 一次执行的 10 字段记录 |
| 写门面 | `repository.TaskRepository` | 创建/开始/完成/更新外部 ID，`@Transactional` SpEL |
| 读门面 | `repository.TaskExplorer` | 分页查执行、查运行中、查 Task↔Job 关联、查最新 |
| DAO | `dao.TaskExecutionDao` | JDBC/Map 两实现，SQL 全手写 |
| 配置 SPI | `configuration.TaskConfigurer` | 仓储/事务/名字/数据源总开关 |
| 生命周期 | `listener.TaskLifecycleListener` | 四触发点记账引擎 |
| 监听接口 | `listener.TaskExecutionListener` | onTaskStartup/Failed/End |
| 监听注解 | `@BeforeTask/@AfterTask/@FailedTask` | 方法级回调 |
| 名字解析 | `repository.TaskNameResolver` | `spring.cloud.task.name` → context id |
| 观测 | `listener.TaskObservations` | 外圈观测发射器 |
| Batch 关联 | `batch.TaskBatchExecutionListener` | 双身份监听器，写 `TASK_TASK_BATCH` |
| Batch 启动 | `batch.TaskJobLauncherApplicationRunner` | 顶替 Boot 启动器 + 失败传导 |
| 单实例锁 | `configuration.SingleInstanceTaskListener` | JDBC 锁 + 领导者选举忙等 |

## 9.2 配置属性速查表

| 属性 | 默认 | 说明 |
|---|---|---|
| `spring.cloud.task.name` | context id | 任务名 |
| `spring.cloud.task.executionid` | - | 复用既有执行记录 |
| `spring.cloud.task.parent-execution-id` | - | 父任务 |
| `spring.cloud.task.external-execution-id` | - | 平台侧 ID |
| `spring.cloud.task.table-prefix` | TASK_ | 表前缀（自定义则不自动建表） |
| `spring.cloud.task.initialize-enabled` | true | 自动建表 |
| `spring.cloud.task.closecontext-enabled` | false | 结束后关容器 |
| `spring.cloud.task.transaction-manager` | 内置 Bean | 记账事务管理器名 |
| `spring.cloud.task.single-instance-enabled` | false | 单实例互斥 |
| `spring.cloud.task.single-instance-lock-ttl` | MAX_INT | 锁 TTL（ms） |
| `spring.cloud.task.single-instance-lock-check-interval` | 500 | 抢锁轮询（ms） |
| `spring.cloud.task.autoconfiguration.enabled` | true | 总开关 |
| `spring.cloud.task.observation.enabled` | true | 观测开关 |
| `spring.cloud.task.batch.fail-on-job-failure` | false | Job 失败传导为任务失败 |
| `spring.cloud.task.batch.fail-on-job-failure-poll-interval` | 5000 | 轮询 Job 状态间隔（ms） |
| `spring.cloud.task.batch.listener.enabled` | true | 关联记录开关 |
| `spring.cloud.task.events.enabled` | true | 任务事件外发开关 |

## 9.3 源码阅读入口清单（14 个关键文件）

```
spring-cloud-task-core/src/main/java/org/springframework/cloud/task/
├── listener/TaskLifecycleListener.java            ← 心脏：四触发点 + exit code 链
├── listener/TaskExecutionListener.java            ← 回调接口
├── listener/annotation/TaskListenerExecutor.java  ← 注解反射执行器
├── listener/TaskObservations.java                 ← 外圈观测
├── repository/TaskExecution.java                  ← 域模型
├── repository/TaskRepository.java / TaskExplorer.java ← 双门面
├── repository/dao/JdbcTaskExecutionDao.java       ← 全部 SQL
├── repository/support/TaskExecutionDaoFactoryBean.java ← DAO 工厂 + 递增器
├── repository/support/TaskRepositoryInitializer.java   ← 自动建表
├── repository/support/DatabaseType.java           ← 认库
├── configuration/SimpleTaskAutoConfiguration.java ← 装配总装
├── configuration/DefaultTaskConfigurer.java       ← 默认策略集
├── configuration/SingleInstanceTaskListener.java  ← 单实例锁
└── configuration/EnableTask.java                  ← 手动开关（1 行 @Import）

spring-cloud-task-batch/src/main/java/org/springframework/cloud/task/batch/
├── listener/TaskBatchExecutionListener.java       ← Task↔Job 关联
├── handler/TaskJobLauncherApplicationRunner.java  ← Job 启动与失败传导
spring-cloud-task-core/src/main/resources/org/springframework/cloud/task/schema-h2.sql ← 4 表 1 序列
```

## 9.4 与 Spring Cloud Data Flow 的关系

Task 的表结构就是为平台消费设计的：Data Flow 的 Skipper/Task Launcher 负责**部署**（把任务应用推到 CF/K8s/本地进程，写入 `external-execution-id`），`spring.cloud.task.executionid` 姿势二（3.2 节）支持"先登记后启动"；部署完成后任务运行自行记账，Data Flow 的仪表盘与"重启失败任务"功能全部构建在 `TaskExplorer` 的查询之上（`getLatestTaskExecutionsByTaskNames` 的"最新"语义正是为此）。本文不展开平台侧，但读源码时记住这个消费方，很多设计（externalExecutionId、parentExecutionId、latest 查询、TASK_TASK_BATCH 关联）的动机就都通了。

## 结语

Spring Cloud Task 是 Spring 生态里"最小"的项目之一——核心只有 72 个类、4 张表、两条 SQL 路径——却完整示范了一个框架应有的克制：**它不抢调度、不抢执行、不抢观测协议，只在生命周期的两个端点各记一笔，其余全部复用 Boot 的事件模型、Batch 的基础设施、Integration 的锁、Micrometer 的观测**。理解了 `TaskLifecycleListener` 的四个触发点与 `calcExitStatus` 的三级链，这个项目对你就没有秘密；理解了它的克制，你也就理解了 Spring 全家桶里"胶水层项目"的共同气质。
