# Jenkins 深度特性解析（写给初学者的架构全景与 CI/CD 配置）

> **资料基准**：写作时（2026-10-18）Jenkins 最新 LTS 为 **2.580.1**（2026-09-30 发布）。Controller 与 Agent 的运行基线是 **Java 21 或 Java 25**；Java 17 自 LTS **2.555.1**（2026-04-15）起不再支持。发布节奏、插件版本、镜像标签都会变，落地前以 [LTS 更新日志](https://www.jenkins.io/changelog-stable/) 与 [Java 支持策略](https://www.jenkins.io/doc/book/platform-information/support-policy-java/) 为准。
>
> **本文在本目录中的位置**：
>
> | 文档 | 回答的问题 |
> |---|---|
> | **本文 `Jenkins.md`** | Jenkins 是什么、内部怎么运转、一条合格的 CI/CD 要配哪些东西 |
> | `Jenkins学习方案.md` | 在本机 Docker 上把上述形态练出来（GitHub + 本地 Registry + 两套 Compose） |
> | `云效与自建GitLab+Jenkins对比.md` | 自建 Jenkins 和云效怎么选，自建还要补哪些系统 |
>
> **阅读约定**：每章「先白话、后配置」。白话说明这个机制解决什么问题；配置是可以放进仓库或配置即代码（JCasC）的文本。Jenkins 的界面点击路径会写出来，因为第一次接触时，找不到按钮和看不懂 `Jenkinsfile` 是同一类障碍。

## 如何读这份文档

如果你是 Jenkins 初学者，推荐三遍读法：

- **第一遍（建立地图，约 1 小时）**：只读第一章、各章「本章小结」、第九章贯通时间线。读完应能回答：一次构建从代码推送走到部署，经过哪几步？Controller 和 Agent 各管什么？`Jenkinsfile` 放在哪？密码放在哪？为什么生产环境要部署「某一次构建出来的那个镜像」，而不是部署「最新」？
- **第二遍（把语法读懂，约半天）**：第四章把声明式流水线的每个指令过一遍，对照第八章的完整 `Jenkinsfile`。目标是能独立改一个 stage、加一个参数、把测试报告挂到构建记录上。
- **第三遍（把系统配出来）**：第五章凭据与权限、第六章 Agent、第七章插件与 JCasC、第八章四套可以落地的配置。本机想动手时，转到 `Jenkins学习方案.md`，用 Docker 把控制面跑起来，再把本文第八章的文件放进练习仓库。

文中的示例假定 **Agent 是 Linux**（容器或虚拟机），步骤用 `sh`。你的电脑是 Windows 不影响这件事：Jenkins 跑在 Docker 或一台 Linux 机器上，流水线里的 `sh` 跑在 Agent 里，不跑在你的 PowerShell 里。只有当你真的接入一台 Windows 构建机时，对应步骤才改成 `bat` 或 `powershell`。

---

# 一、总览：Jenkins 的定位、设计哲学与整体架构

## 1.1 一句话定位

**Jenkins 是一台用 Java 写的、以插件为骨架的自动化服务器**：它自己几乎不「会构建 Java」或「会发布 Kubernetes」，它负责把「何时触发、在哪台机器上执行、按什么顺序调用哪些步骤、失败了如何留下证据、谁有权看见秘密」这件事编排好。官方自述是 *The leading open source automation server*。

它解决的是**交付过程的组织问题**：每次提交都按同一套步骤执行（持续集成），通过检查的产物被原样送到后面的环境（持续交付），步骤定义跟代码一起评审（流水线即代码），构建机器可以水平增加（Controller / Agent），密码不进 Git（凭据库）。

把三句话分开记：

- **CI（持续集成）**：每次变更都编译、测试，并产出一个可识别的产物。
- **CD（持续交付 / 持续部署）**：这个产物按环境向前推进。持续交付在生产前有一次人工确认；持续部署把这次确认也自动化了。下文的实践停在「持续交付」：开发环境自动发布，生产环境人工确认。
- **Jenkins 的岗位**：编排者。代码托管是 GitHub / GitLab，制品库是 Nexus / Harbor，运行环境是虚拟机或 Kubernetes。Jenkins 把它们串起来。

## 1.2 设计哲学：读配置前先记住六句话

1. **流水线是仓库里的文本。** 任务的步骤写在仓库根目录的 `Jenkinsfile` 里，和业务代码一起评审、一起回溯。Jenkins 界面上留下的是「去哪个仓库、用哪份凭据、信任哪条共享库」，步骤本身跟着提交走。
2. **控制面与执行面分开。** Controller（控制节点，旧称 Master）负责网页、排队、凭据、插件。Agent（执行节点，旧称 Slave）负责拉代码、编译、测试、构建镜像。构建的脏活留在 Agent 上，Controller 保持轻。
3. **核心很小，能力在插件。** Git、流水线语法、Docker、Kubernetes、角色权限、邮件，都是插件。装什么插件，这台 Jenkins 就擅长什么。插件也是升级和安全公告的主要来源。
4. **整台服务器的状态就是一个目录。** 这个目录叫 `JENKINS_HOME`。任务配置、构建记录、插件、凭据密文、节点定义都在里面。备份它，就备份了这台 Jenkins；恢复时要连同加密凭据的主密钥一起带走。
5. **秘密只出现在凭据库里。** `Jenkinsfile` 里写凭据的 **ID**（例如 `harbor-robot`），运行到那一步时由 Controller 注入。仓库、构建日志、聊天记录里不出现密码正文。
6. **一次构建，一个不可变产物，多个环境共用它。** 测试通过后生成的 jar 或镜像用 **Git 提交号** 命名。开发、预发、生产部署的是同一个名字。环境之间的差别是配置和审批，不是「在生产机器上再编译一次」。

## 1.3 模块分层全景

Jenkins 不是 Spring 那种多模块源码树，它的「模块」是运行时角色加上插件。按你能感知到的层次：

```
┌────────────────────────── 人与代码托管 ──────────────────────────┐
│  开发者 push / 合并请求                                            │
│  GitHub、GitLab、Gitea…… 发 webhook，并展示构建状态                  │
├────────────────────────── 流水线定义（跟仓库走）──────────────────┤
│  Jenkinsfile（声明式或脚本式）                                      │
│  共享库（团队公共步骤）          Dockerfile / 部署清单 / 构建脚本       │
├────────────────────────── Controller（控制面）───────────────────┤
│  Web UI        队列与调度       用户与权限        凭据库              │
│  插件运行时     任务与构建记录   JENKINS_HOME      配置即代码 JCasC     │
│  内置节点：执行器数量设为 0，不在控制面上跑构建                          │
├────────────────────────── Agent（执行面）────────────────────────┤
│  固定虚拟机（SSH）   入站 Agent（WebSocket）                         │
│  用完即弃的容器：Docker 或 Kubernetes Pod                            │
│  每个 Agent 有标签（label），流水线按标签领取                          │
├────────────────────────── 外部系统（Jenkins 调用它们）─────────────┤
│  JDK / Maven / Gradle / Node      Nexus、Harbor                     │
│  SonarQube                        Kubernetes、主机、对象存储          │
│  邮件 / Slack / 钉钉                                                   │
└──────────────────────────────────────────────────────────────────┘
```

还有两个你在日志里会看见、但不必第一天就读源码的运行时：

| 名字 | 它是什么 |
|---|---|
| **Winstone** | 打进 `jenkins.war` 的内嵌 Jetty，让一个 war 就能监听 HTTP |
| **Remoting** | Controller 和 Agent 之间的通信通道。Agent 上跑 `remoting.jar`，接收「请执行这个步骤」 |
| **Stapler** | 把 URL 映射到 Jenkins 对象的 Web 框架。你看到的 `/job/foo/1/console` 就是它路由的 |
| **Pipeline 引擎** | 把 `Jenkinsfile` 编译成一串可暂停、可落盘的步骤。Controller 重启后，未完成的流水线可以接着走 |

## 1.4 一次交付里各部件的依赖关系

```
Git 仓库（源码 + Jenkinsfile + 部署清单）
    │  webhook / 拉取
    ▼
Jenkins Controller ── 凭据、排队、记录、审批
    │  把步骤派到匹配标签的 Agent
    ▼
Agent 工作区
    ├─ mvn test / mvn deploy ──────────► Nexus（jar、pom）
    ├─ 构建镜像并推送 ─────────────────► Harbor（不可变 tag = Git SHA）
    └─ kubectl / helm ─────────────────► 开发环境，确认后再到生产环境
              ▲
              └── 部署的镜像来自 Harbor 里的那个 SHA，工作区里的源码不再参与编译
```

依赖方向是单向的：**仓库不知道集群里有哪些 Pod；集群不知道 Jenkins 的队列；Jenkins 是唯一的编排者。** 制品库位于中间，使「构建」和「部署」可以拆开、重试、审计。

## 1.5 关键问题 → Jenkins 的方案（全文导览）

| 你迟早会碰到的问题 | Jenkins 的方案 | 详见 |
|---|---|---|
| 每次发布靠人肉敲命令，步骤会漂 | 仓库里的 `Jenkinsfile`，每次提交按同一文本执行 | 第四章、第八章 |
| 界面上点出来的任务无法评审 | 任务类型选「流水线」或「多分支流水线」，脚本来自 SCM | 第三章 |
| 一个分支一个手工任务，建不过来 | 多分支流水线：扫描分支和合并请求，自动生成子任务 | 第三章、8.4 |
| 编译把 Controller 撑满，网页一起卡 | 内置节点执行器设为 0；构建只在 Agent 上 | 第二章、第六章 |
| 构建机要扩容 | 加机器、加标签；或用 Kubernetes 按构建创建 Pod | 第六章、8.5 |
| 密码写进了脚本 | 凭据库 + `withCredentials` / 绑定，脚本里只写 ID | 第五章 |
| 新人能看见生产集群的 kubeconfig | 凭据可以挂在 Folder 上；角色策略按目录授权 | 第五章 |
| 十个服务复制了十份同样的 stage | 共享库：公共函数进独立 Git 仓库 | 第七章、8.5 |
| 界面改过的配置没人记得 | JCasC：系统配置写成 YAML，实例可重建 | 第七章、8.2 |
| 测试挂了但构建是绿的 | `sh` 的退出码决定成败；`junit` 把报告挂到构建上，且放在 `post { always }` | 第四章、8.3 |
| 生产部署了「当时 latest 指着的未知内容」 | 镜像 tag 使用 Git SHA；Harbor 打开 tag 不可变；生产环境引用这个 tag | 8.3 |
| 两次发布交叉，旧构建后完成把新版本盖掉 | `disableConcurrentBuilds`、`milestone`、`lock` | 第四章、8.3 |
| 审批等人点了两小时，构建机一直被占着 | 流水线顶层 `agent none`；审批用 stage 上的 `input` 指令 | 第四章、8.3 |
| Controller 磁盘被日志和旧包撑满 | `buildDiscarder` 只保留近期构建；发布物放 Nexus / Harbor | 第四章、8.2 |
| 主控挂了，进行中的流水线全部作废 | Pipeline 把进度写入 `JENKINS_HOME`，重启后可恢复（Agent 需重连） | 第二章 |
| 想临时改一行脚本试跑 | 构建页的 Replay；试通后把改动提交回 Git | 第四章 |

## 1.6 版本演进：从自由风格任务到今天的 LTS

### 1.6.1 时间线与运行基线

| 时期 | 代表版本 | 你需要知道的事实 |
|---|---|---|
| Hudson | 2005 起 | Kohsuke Kawaguchi 在 Sun 做的持续集成服务器，Jenkins 的前身 |
| 更名 | 2011 | 社区分叉，项目定名为 Jenkins |
| 1.x | 至 2016 | 主流是自由风格（Freestyle）任务：网页上勾选「拉 Git、执行 shell、归档产物」 |
| 2.0 | 2016-04 | 流水线成为一等公民，首次安装有设置向导。这是今天用法的起点 |
| 声明式流水线 | 2017 前后 | `pipeline { ... }` 结构化语法稳定下来，成为默认写法 |
| 配置即代码 | 2018 起插件成熟 | 系统配置可以导出、审阅、重建 |
| 术语调整 | 2.307（2021）前后 | 内置节点的标签从 `master` 改为 `built-in`；界面用语改为 Controller / Agent |
| Java 11 时代结束 | LTS 2.462.3（2024-10）为最后支持 Java 11 的 LTS | 下一条 LTS 起运行时至少 Java 17 |
| Java 17 成为下限 | LTS 2.479.1（2024-10） | Controller 与 Agent 都要 Java 17+ |
| Java 25 开始支持 | LTS 2.541.1（2026-01） | 与 Java 21 并行；官方测试 OpenJDK 21 与 25 |
| Java 17 支持结束 | LTS 2.555.1（2026-04-15） | **Java 21 或 25** 才能启动。先升 Agent 的 JVM，再升 Jenkins |
| 本文基线 | **LTS 2.580.1（2026-09-30）** | 安全修复走每条 LTS 的 `.2` `.3` 小版本；生产跟 LTS，不跟每周构建 |

两条发布线：

- **Weekly**：每周功能版，给插件作者和愿意尝鲜的人。
- **LTS**：大约每 12 周从 Weekly 切一条稳定线，中间发安全与回归修复。**给业务团队用的是 LTS。**

Java 还有一条独立规则，初学者很容易混：**跑 Jenkins 的 JVM** 和 **编译你的业务项目的 JDK** 可以不同。Controller 用 Java 21 时，流水线仍然可以在 Agent 里调用 Java 8 或 17 去编译老系统。例外是少数插件（例如 Maven Integration 插件的某些集成）要求构建 JDK 不低于 Controller 的 Java。用容器做 Agent 时，最省事的做法是：Agent 进程用镜像自带的 Java 21，业务编译用镜像里另装的 JDK，或干脆用一个已经装好 Maven 与目标 JDK 的构建镜像。

### 1.6.2 和初学者有关的近期变化

这些条目来自官方 LTS 更新日志，影响「装上一台新 Jenkins 时的预期」：

| 变化 | 落在哪条 LTS | 对你意味着什么 |
|---|---|---|
| 运行时要求 Java 21 或 25 | 2.555.1 | 升级前用节点监控确认每台 Agent 的 JVM。官方 Docker 镜像已去掉 Java 17 变体 |
| Linux 安装包签名密钥更换 | 2.541.1 | 用 apt/yum 官方源的机器要信任新的 GPG 密钥 |
| `jenkins.war` 不再内置一批历史插件 | 2.580.1 这条线 | JUnit、Mailer、矩阵授权等不再打进 war。新装实例靠「推荐插件」或你自己的 `plugins.txt`。从很老的版本跨级升级时，缺什么就补装什么 |
| 登录密码可配置复杂度 | 2.580.1 这条线 | 本地用户的密码策略变严，和公司账号体系接 LDAP / OIDC 时影响较小 |
| 反序列化默认更严格 | 2.580.1 这条线 | 安全加固。旧插件若还在做不安全的 Java 反序列化，升级后会暴露出来 |

界面上，2026 年的 LTS 在持续整理「系统管理」页、对话框和构建状态图标。学习时认菜单的中文或英文含义即可，按钮位置会随小版本微调。Blue Ocean（一套独立的蓝色界面）仍可安装，项目处于维护状态；本文全部路径按经典界面来。

### 1.6.3 哪些知识过时了，哪些一直有效

一直有效：

- 任务定义进 Git，凭据留在 Jenkins。
- Controller 调度，Agent 执行，内置节点不参与编译。
- 产物进外部制品库，用不可变版本号晋级环境。
- 插件少而版本锁定；先在一台灰度 Jenkins 上升级，再动生产。
- 流水线的人工确认不能占着构建机。

已经过时、只需要能看懂旧系统：

| 旧习惯 | 今天的做法 |
|---|---|
| 用自由风格任务当主要交付方式 | 新任务用流水线；旧的自由风格任务逐步迁走 |
| 说法和标签 `master` / `slave` | 说 Controller / Agent；内置节点标签是 `built-in` |
| 流水线写 `agent any`，构建跑在哪都行 | 写明确标签，例如 `linux && docker` |
| 用 SCM 轮询每分钟问一次 Git | 用 webhook；轮询只做 webhook 丢失时的兜底 |
| 把 jar 只「归档」在 Jenkins 里当作发布库 | Jenkins 的归档用于留证和短期下载；发布用 Nexus / Harbor |
| 部署镜像的 `latest` 标签 | 部署 `sha-xxxxxxxxxxxx` 这样的不可变标签 |
| 在生产阶段 `docker build` 或 `mvn package` 再编一次 | 部署阶段只引用已经推送的产物 |
| 把 Blue Ocean 当作必须学习的第二套界面 | 会看阶段视图和日志即可 |

## 1.7 全文章节地图

| 章节 | 内容 | 一句话 |
|---|---|---|
| 一 | 总览 | 定位、哲学、分层、版本 |
| 二 | 运行时 | 一次构建在队列、执行器、工作区里怎么走 |
| 三 | 任务类型 | 自由风格、流水线、多分支、目录 |
| 四 | Pipeline 语法 | 声明式指令、步骤、并行、审批、变量 |
| 五 | 凭据与安全 | 凭据类型、注入方式、角色、攻击面 |
| 六 | Agent | SSH、入站、Docker、Kubernetes 四种形态 |
| 七 | 插件、JCasC、共享库 | 能力从哪来，配置如何可重建 |
| 八 | 四套详细配置 | 平台、参考流水线、多分支、共享库与动态 Agent |
| 九 | 贯通 | 从 push 到生产的一条时间线 |
| 附录 | 速查 | 术语、界面路径、学习顺序、官方入口 |

### 本章小结

Jenkins 是编排服务器：仓库提供步骤文本，Controller 提供调度和记忆，Agent 提供执行，制品库提供不可变产物，环境只消费产物。下一章把「点了一次构建之后，系统内部发生什么」拆开。

---

# 二、运行时内核：一次构建是怎么发生的

## 2.1 模块定位

本章建立词汇表。后面的 `Jenkinsfile` 每一行，都会落到这些对象上。先把对象认全，再读语法，不会把「任务」「构建」「节点」「执行器」当成同一个东西。

## 2.2 词汇表

| 词 | 含义 | 你可以在界面上的哪里看见 |
|---|---|---|
| **Item / Job（任务）** | 一种可配置的工作定义。流水线任务、多分支任务、文件夹都是 Item | 首页的任务列表 |
| **Build / Run（构建、运行）** | 任务的一次执行，有编号 `#1`、`#2` | 任务页左侧「构建历史」 |
| **Pipeline（流水线）** | 用 Groovy 方言写成的一组阶段。一次 Run 把这份脚本当成程序执行 | 任务里的「阶段视图」 |
| **Stage（阶段）** | 给人看的一段，例如「测试」「发布镜像」 | 阶段视图上的色块 |
| **Step（步骤）** | 阶段里的一个动作：`sh`、`junit`、`archiveArtifacts` | 点开阶段后的日志 |
| **Node / Agent（节点）** | 能干活的机器。内置节点是 Controller 自己 | 「系统管理 → 节点」 |
| **Label（标签）** | 贴在节点上的能力说明，如 `linux`、`docker`、`jdk21` | 节点配置页 |
| **Executor（执行器）** | 节点上的一个并发槽。一个占用中的执行器同时只跑一个 `node`/`agent` 块 | 节点页的小格子 |
| **Queue（队列）** | 还没领到执行器的构建在这里等 | 首页左侧「构建队列」 |
| **Workspace（工作区）** | Agent 磁盘上这次构建的目录，代码被 checkout 到这里 | 构建页的「工作空间」 |
| **Fingerprint（指纹）** | 对文件做校验和，用来回答「这个 jar 是哪次构建产生的、被谁用过」 | 构建页的「查看指纹」 |
| **Artifact（归档产物）** | 存在这次构建记录下的文件，受「丢弃旧构建」策略清理 | 构建页的「构建产物」 |
| **Credentials（凭据）** | 加密后存在 `JENKINS_HOME` 的秘密，用 ID 引用 | 「系统管理 → 凭据」或文件夹的凭据页 |

一次 `#42` 构建和任务定义的关系，很像「一次进程」和「可执行文件」：任务是配方，构建是按配方跑起来的那一次，工作区是那一次的临时磁盘。

## 2.3 Controller 与 Agent

```
浏览器                Git 托管                         制品库 / 集群
   │                    │                                  ▲
   │  HTTPS             │ webhook                          │ 推送镜像、调用 API
   ▼                    ▼                                  │
┌──────────────────────────────────────┐                   │
│ Controller                            │                   │
│  用户登录、权限、凭据解密               │                   │
│  解析 Jenkinsfile，推进阶段             │                   │
│  队列：哪个 Agent 的标签匹配、有空槽    │                   │
│  保存构建记录到 JENKINS_HOME            │                   │
│  内置节点执行器 = 0                     │                   │
└───────────────┬──────────────────────┘                   │
                │ Remoting（SSH 连出去，或 Agent 连进来）     │
     ┌──────────┴──────────┐                               │
     ▼                     ▼                               │
 Agent「linux」         Agent「k8s-pod-临时」 ──────────────┘
 工作区 /var/jenkins/ws/...
```

两条运行时事实：

1. **`Jenkinsfile` 的编排逻辑在 Controller 上推进，`sh` 的进程在 Agent 上跑。** 所以 Groovy 里访问不到 Agent 的本地文件，除非通过 `sh`、`readFile` 这类步骤。步骤是一个远程过程调用：Controller 说「请在工作区执行这段 shell」，Agent 做完把退出码和输出送回来。
2. **流水线可以中途换 Agent。** 测试阶段用带 Maven 的节点，构建镜像阶段用带容器引擎的节点，部署阶段用一台只装了 `kubectl`、网络能到达集群的节点。每个阶段声明自己的 `agent`，阶段结束就归还执行器。

Controller 重启时，已经落盘的流水线进度还在。Agent 重连之后，流水线从上次完成的步骤之后继续。这依赖 Pipeline 的持久化，也依赖 `JENKINS_HOME` 没有丢。进行到一半的 `sh` 进程本身不会神奇地复活，那个步骤会按失败或中断处理，后面的阶段可以「从某一阶段重新运行」。

## 2.4 `JENKINS_HOME` 里有什么

Docker 官方镜像把这个目录放在容器内的 `/var/jenkins_home`。它必须是一块持久卷。容器删了而卷还在，任务和历史还在；卷没了，这台 Jenkins 就回到刚安装。

| 路径 | 内容 | 备份时的注意点 |
|---|---|---|
| `config.xml` | 系统主配置：执行器数、安全域、URL | 与 JCasC 二选一作为「真相来源」时，以 JCasC 为准，这份文件是运行结果 |
| `jobs/` | 每个任务的 `config.xml` 和 `builds/` 历史 | 历史会很大。备份策略要决定保留多少构建 |
| `plugins/` | 已安装插件的 hpi 与解压目录 | 与 `plugins.txt` 版本清单一起保管 |
| `credentials.xml` 与 `secrets/` | 凭据密文、主密钥 | **密文和 `secrets/` 必须一起备份**，否则换机器无法解密 |
| `users/` | 本地用户 | 接了 LDAP 之后这里主要是用户偏好 |
| `nodes/` | 静态 Agent 的定义 | 动态 Pod 不在这里 |
| `casc_configs/` 或你挂载的目录 | JCasC YAML | 更适合放在 Git 里，作为配置的源头 |
| `war/` 与 caches | 运行缓存 | 可以不作为备份重点 |

凭据用 Jenkins 的主密钥加密。把 `credentials.xml` 单独拷到另一台新装的 Jenkins 上，解不开。恢复演练要整目录恢复，或至少包含 `secrets/`。

磁盘会被三样东西撑满：各 Agent 上的工作区、`jobs/**/builds` 里的日志、有人把大制品归档进了 Jenkins。治理方式在第四章的 `buildDiscarder` 和第八章的制品库实践里。

## 2.5 从点击到进程：队列与执行器

一次构建的内部顺序是固定的：

1. **触发。** 人点击「立即构建」、定时器到点、上游任务调用、或 webhook 到达。
2. **进入队列。** Jenkins 看这个任务要哪个标签、要几个执行器。
3. **匹配节点。** 标签表达式成立（例如 `linux && docker`）、节点在线、剩余执行器足够，才分配。
4. **占用执行器，准备工作区。** 多分支任务的工作区按分支分开，避免两条分支互相覆盖。
5. **检出代码。** 声明式流水线默认在进入 Agent 后做一次 checkout，除非该阶段写了 `skipDefaultCheckout`。
6. **按阶段执行步骤。** 失败则跳到 `post`。
7. **释放执行器。** 工作区默认留在磁盘上，直到被清理策略或下一次构建覆盖。
8. **写回结果。** 成功、失败、不稳定、中止。不稳定（unstable，黄）通常表示「步骤跑完了，但测试或静态检查有问题」。

人在界面上最常见的「卡住」是第 3 步：构建历史里没有新编号，队列里写着 *Waiting for next available executor*。原因只有几类：没有节点贴了这个标签、节点离线、执行器都被占满、或流水线写了 `agent any` 但所有节点都被设成只接受指定任务。处理方式是打开「节点」页看标签和空闲槽，而不是反复点击构建。

内置节点是一台特殊的 Agent，就是 Controller 自己。它的执行器数量在「系统管理 → 节点 → 内置节点」里。**设为 0。** 这样即使有人写出 `agent any`，也不会在控制进程所在的机器上开始编译。JCasC 里的对应项见 8.2。

## 2.6 触发器

| 方式 | 行为 | 适用 |
|---|---|---|
| 手动 | 人点击，或带参数运行 | 补跑部署、演练 |
| 定时 `cron` | 到点触发。`H` 是哈希，用来把任务错开，避免所有任务在整点同时启动 | 夜间全量、依赖漏洞扫描 |
| SCM 轮询 | Jenkins 周期性地询问 Git「有没有新提交」 | webhook 进不了内网时的兜底 |
| webhook | Git 托管方在 push、合并请求时主动 POST 到 Jenkins | 日常 CI 的主路径 |
| 上游触发 | 任务 A 成功后调用 `build job: 'B'` | 多个仓库组成的发布链 |
| 多分支索引 | 父任务扫描仓库有哪些分支，为新分支创建子任务 | 多分支流水线的入口 |

定时表达式里优先使用 `H`：

```groovy
// 每天夜间大约 2 点跑一次，分钟由任务名散列，避免 02:00:00 扎堆
cron('H 2 * * *')

// 兜底索引：大约每 15 分钟，而不是每分钟打一次 Git
cron('H/15 * * * *')
```

`H/15` 的含义是「每 15 分钟一段，具体落在哪一分钟由哈希决定」。

webhook 比轮询省、也更快。GitHub 的多分支任务通常把载荷送到 `https://<jenkins>/github-webhook/`。这台 Jenkins 必须有一个 Git 托管方能够访问的 HTTPS 地址。纯内网练习机做不到这一点时，用轮询兜底，练习方案见 `Jenkins学习方案.md`。

## 2.7 结果与留证

一次构建的结果是枚举，界面颜色约定俗成：

| 结果 | 颜色 | 典型来源 |
|---|---|---|
| SUCCESS | 绿 | 全部必需步骤退出码为 0，且没有人把结果改成更差 |
| UNSTABLE | 黄 | 测试报告里有失败，或静态检查超过阈值，但流水线作者允许「继续记录」 |
| FAILURE | 红 | `sh` 非 0、`error` 步骤、质量门禁判定失败 |
| ABORTED | 灰 | 人取消、超时、被更新的提交挤掉、审批到期 |
| NOT_BUILT | — | 阶段因 `when` 条件跳过 |

`mvn test` 在有测试失败时会返回非 0，流水线变红，这是希望的行为。`junit` 步骤负责把 XML 报告解析成网页上的失败用例、趋势图。两者配合：`sh` 决定成败，`junit` 负责可读性。`junit` 放在 `post { always { ... } }` 里，测试失败导致 `sh` 中断之后，报告仍然会发布。

归档（`archiveArtifacts`）把文件复制进 `JENKINS_HOME` 的这次构建目录。适合测试日志、少量报告。jar 和镜像不放在这里当「正式版本」，因为 Jenkins 的丢弃策略会删掉它们，而且 Controller 的磁盘不该承担制品库的职责。

### 本章小结

一次构建是：触发 → 排队 → 按标签领取执行器 → 工作区检出 → 分阶段远程执行 → 写结果与留证。Controller 记住一切，Agent 提供槽位和磁盘。下一章说明这些构建挂在哪几种任务下面。

---

# 三、任务类型：自由风格、流水线、多分支与目录

## 3.1 模块定位

「新建任务」页面上的类型，决定了定义存在哪、分支怎么对应、权限边界在哪。新工作只使用流水线和多分支流水线。自由风格任务留在本章，是为了让你能读懂还没迁走的旧系统。

## 3.2 自由风格任务

自由风格任务的配置是网页表单：源码管理选 Git，构建步骤里加一段 shell，构建后操作里归档文件、发邮件。它能跑通简单的「拉代码、执行脚本」。

它的定义在 `jobs/<名字>/config.xml`，不在业务仓库里。改步骤不经过代码评审；换一台 Jenkins 时这些表单容易漂。分支一多，就要复制任务。本章之后的实践不再用它做 CI/CD 主路径。

你仍然可能在这些场景看见它：每小时跑一段运维脚本、触发一台没有仓库的清扫任务。即便如此，更干净的做法也是一个只有几行 `sh` 的流水线任务，定义放在运维仓库里。

## 3.3 流水线任务

新建 Item → **流水线（Pipeline）**。配置页的核心是「流水线定义」：

| 选项 | 含义 |
|---|---|
| Pipeline script | 脚本写在 Jenkins 的任务配置里。适合在 Replay 里试语法。试通后搬回 Git |
| Pipeline script from SCM | Jenkins 去仓库取出 `Jenkinsfile` 再执行。**正式任务用这个** |

选 from SCM 时要填写：

- Git 地址，以及一个只读凭据 ID；
- 分支说明符，例如 `*/main`（单分支任务）。多分支不要用这种任务硬扛，用 3.4；
- 脚本路径，默认 `Jenkinsfile`。单体仓库里如果服务在子目录，可以写成 `services/order/Jenkinsfile`。

这种任务对应「一个仓库的一条固定分支」。适合：已经拆出去的部署任务（参数是镜像 tag 和环境名），或还没有多分支需求的第一个练习。

## 3.4 多分支流水线

新建 Item → **多分支流水线（Multibranch Pipeline）**。你配置的是父任务：仓库地址、凭据、发现哪些分支、发现哪些合并请求、`Jenkinsfile` 的路径。保存后点击「立即扫描」。Jenkins 为每个符合条件的分支、标签、合并请求各建一个子任务。

子任务的名字就是分支名。每个子任务检出自己的那次提交，执行那次提交里的 `Jenkinsfile`。分支删除后，扫描会按你配置的「孤立项策略」丢掉对应子任务。

发现策略（Branch Sources 里的 Behaviors）建议显式勾选，而不是依赖含糊的默认：

| 行为 | 建议 |
|---|---|
| Discover branches | 打开。可用名称过滤，只保留 `main`、`release/*`、`feature/*` |
| Discover pull requests / merge requests | 打开，来源选 origin。让评审在合并前就有编译和测试结果 |
| Discover tags | 按需。只有「打 tag 才发布」的仓库需要 |
| Filter by name | 写上你真的要构建的模式，避免把每个人的临时分支都编一遍 |

多分支父任务自己也要定时扫描（例如 `H/15 * * * *`），这样 webhook 偶发失败时，新分支仍会在一刻钟内出现。webhook 负责「快」，定时扫描负责「最终会一致」。

合并请求对应的子任务里，Jenkins 会设置一组环境变量（来自分支源插件）：

| 变量 | 含义 |
|---|---|
| `BRANCH_NAME` | 普通分支的名字；合并请求里常常是 `PR-12` 这种编号名 |
| `CHANGE_ID` | 合并请求编号。普通分支上这个变量为空 |
| `CHANGE_TARGET` | 合并请求想合入的目标分支 |
| `TAG_NAME` | 若这次构建来自 Git 标签，则为标签名 |
| `GIT_COMMIT` | 本次提交的完整 SHA。多分支索引通常能在 checkout 前就提供它 |

流水线用这些变量决定「这条分支只测试，不部署」。配置见 8.4。

组织文件夹（Organization Folder / GitHub Organization / GitLab Group）比多分支再高一层：扫描整个组织，为每个含 `Jenkinsfile` 的仓库自动建一个多分支任务。仓库很多、命名规范统一时再用。第一个服务先用单个多分支任务。

## 3.5 文件夹

文件夹（Folder）是任务的目录，也是权限和凭据的边界。

```
Jenkins 根
├── platform/                 文件夹。凭据：只有平台组能看的 kubeconfig
│   ├── order-service         多分支流水线
│   └── order-service-deploy  参数化部署流水线
└── labs/                     文件夹。实习生只有这里的构建权限
    └── sandbox
```

文件夹里创建的凭据，只能被文件夹内的任务绑定。生产集群的 kubeconfig 放在 `platform` 文件夹，而不是系统全局。全局凭据对所有任务可见，范围越大，一条被改坏的流水线能碰到的秘密越多。

## 3.6 任务配置里仍要在界面上定的事

即便步骤全部在 Git 里，每个任务（或 JCasC / Job DSL 等价物）仍要在 Jenkins 侧写明：

- 仓库地址和只读凭据；
- 信任的共享库名字；
- 这条任务属于哪个文件夹（从而继承哪一组凭据和角色）；
- 丢弃旧构建的策略（也可以写进 `Jenkinsfile` 的 `options`，两处一致即可）；
- 触发：webhook、定时扫描、还是只允许手动。

原则：**会随业务变化的，进 `Jenkinsfile`；会随环境变化的（地址、凭据 ID、权限），留在 Jenkins 侧，并用 JCasC 管住。**

### 本章小结

自由风格任务把步骤存在 Jenkins 里；流水线任务把步骤存在 Git 里；多分支流水线为一组分支自动生成流水线任务；文件夹切开权限和凭据。下一章读 `Jenkinsfile` 这份步骤文本。

---

# 四、Pipeline 语法：声明式流水线

## 4.1 模块定位

Pipeline 有两种方言，跑在同一个引擎上。

**声明式（Declarative）** 以 `pipeline { }` 为根，结构固定：代理、选项、环境、参数、阶段、善后。校验发生在运行前，写错指令会直接指出哪一段不合法。**默认用它。**

**脚本式（Scripted）** 以 `node { }` 为根，就是带流水线步骤的 Groovy。灵活，也更容易写成无法暂停、无法序列化的程序。留到共享库内部的小函数，或声明式里一小块 `script { }`。

同一个「在 linux 上跑测试，永远发布 JUnit」：

```groovy
// 声明式
pipeline {
    agent { label 'linux' }
    stages {
        stage('测试') {
            steps {
                sh 'mvn -B -ntp test'
            }
        }
    }
    post {
        always {
            junit testResults: '**/target/surefire-reports/*.xml'
        }
    }
}
```

```groovy
// 脚本式
node('linux') {
    try {
        stage('测试') {
            sh 'mvn -B -ntp test'
        }
    } finally {
        junit testResults: '**/target/surefire-reports/*.xml'
    }
}
```

下文只展开声明式。看到旧仓库里的 `node` 块，用这段对照去读即可。

## 4.2 一份骨架，以及指令必须出现的位置

声明式对「谁是谁的子指令」有硬性规定。顺序写乱会被直接拒绝。根上的合法结构如下（都可以省略，但出现时按这个层次）：

```groovy
pipeline {
    agent none                          // 顶层：任何阶段都可以再声明自己的 agent
    options { /* 整条流水线的开关 */ }
    environment { /* 注入到所有阶段的环境变量 */ }
    parameters { /* 人工运行时的表单。第一次加上后要运行一次，参数才会出现在界面上 */ }
    triggers { /* cron、pollSCM。webhook 多在任务配置或插件里，不在这里 */ }
    stages {
        stage('名字') {
            when { /* 要不要跑这个阶段 */ }
            agent { /* 本阶段的节点。审批阶段保持 none */ }
            options { /* 本阶段的超时、重试、跳过默认检出 */ }
            environment { /* 只在本阶段可见的变量 */ }
            input { /* 进入本阶段之前的人工确认。此时还没占用 agent */ }
            steps { /* 真正的步骤 */ }
            post { /* 本阶段结束后 */ }
        }
    }
    post { /* 整条流水线结束后 */ }
}
```

顶层使用 `agent none`，每个真正要干活的阶段再领取 Agent。这样「等审批」的阶段不会占执行器。若顶层写了 `agent { label 'linux' }`，执行器从流水线开始占到结束，中间的 `input` 会把构建机占住几个小时。

## 4.3 `agent`：这段步骤在哪跑

```groovy
agent none
agent any                                   // 练习语法可以，正式任务写标签
agent { label 'linux && maven' }            // 两个标签都要有
agent {
    docker {
        image 'maven:3.9-eclipse-temurin-21'
        label 'docker'                      // 外层必须有台装了 Docker 的节点
        args '-v maven-cache:/root/.m2'
        reuseNode true
    }
}
```

`label` 里的 `&&`、`||`、`!` 是 Jenkins 的标签表达式。节点配置里填写的标签用空格分隔，例如一台机器填 `linux docker maven`。

`docker` 代理的含义是：先选一台有 Docker 的 Agent，再在上面启动构建容器，步骤在容器里执行。这要求那台 Agent 的用户有权访问 Docker。把 Docker 套接字挂进容器，等价于把宿主机的 root 交给构建过程。构建机应当是专用的、上面没有其他租户的数据。更进一步的做法是用 Kubernetes 插件起一次性 Pod，或用 Kaniko / Buildah 构建镜像，构建过程不接触宿主机的 Docker 守护进程。第八章给出 Pod 模板。

## 4.4 `options`：开关

```groovy
options {
    timestamps()                                         // 日志每行带时间
    ansiColor('xterm')                                   // 保留编译器的颜色
    timeout(time: 40, unit: 'MINUTES')                   // 整条流水线的上限
    disableConcurrentBuilds()                            // 同一任务不并发。发布类任务用这个
    // disableConcurrentBuilds(abortPrevious: true)     // 新提交到来时取消旧的。适合合并请求
    buildDiscarder(logRotator(numToKeepStr: '30', artifactNumToKeepStr: '5'))
    skipDefaultCheckout()                                // 顶层跳过检出，由需要源码的阶段自己 checkout scm
}
```

`buildDiscarder` 的参数是**字符串**。它删除的是 Jenkins 上的旧构建记录和归档，不删除 Nexus / Harbor 里的产物。

阶段上可以再写自己的选项：

```groovy
stage('部署') {
    options {
        skipDefaultCheckout()
        timeout(time: 10, unit: 'MINUTES')
        retry(1)
    }
    steps { /* ... */ }
}
```

`retry` 会把整个阶段重跑，适合网络抖动导致的镜像推送。测试失败不该靠 `retry` 蒙混过关；要重试的是已知会闪断的外部调用，并且次数要少。

## 4.5 `environment` 与内置变量

```groovy
environment {
    HARBOR = 'harbor.example.com'
    IMAGE  = "${HARBOR}/demo/order-service"
    // 凭据也可以在这里绑定。变量名由你指定，值在日志里会被掩码
    // NEXUS_AUTH = credentials('nexus-deploy')  // 产生 NEXUS_AUTH_USR 与 NEXUS_AUTH_PSW
}
```

流水线运行时已经存在的变量（常用的一批）：

| 变量 | 含义 |
|---|---|
| `JOB_NAME` | 任务全名，多分支时包含文件夹和分支 |
| `BUILD_NUMBER` | 本次编号 |
| `BUILD_URL` | 这次构建的网页地址，通知里带上它 |
| `WORKSPACE` | 工作区绝对路径 |
| `NODE_NAME` | 当前 Agent 名字 |
| `BRANCH_NAME` / `CHANGE_ID` / `TAG_NAME` | 见 3.4，由多分支插件注入 |
| `GIT_COMMIT` | 完整提交号。单分支任务可能要 checkout 之后才有 |

**提交号在 `environment` 块里取，经常取到空。** 那个块在阶段开始前求值。稳妥的取法是 checkout 之后用一步 shell：

```groovy
script {
    env.SHORT_SHA = sh(script: 'git rev-parse --short=12 HEAD', returnStdout: true).trim()
}
```

`returnStdout: true` 让步骤把标准输出返回给 Groovy，而不是只打印到日志。`.trim()` 去掉换行，否则镜像 tag 里会混进换行符。

`params.名字` 读取参数。参数第一次被加进 `Jenkinsfile` 后，Jenkins 要先运行（或扫描）一次，界面上的「Build with Parameters」才会出现。第一次运行时参数是默认值。

## 4.6 `when`：阶段可以跳过

```groovy
stage('部署到生产') {
    when {
        allOf {
            branch 'main'
            not { changeRequest() }          // 合并请求构建即使目标是 main，也不部署
        }
        beforeAgent true                     // 条件不成立时，不要先占一台机器
    }
    agent { label 'deploy' }
    steps { /* ... */ }
}
```

常用条件：

| 条件 | 成立时机 |
|---|---|
| `branch 'main'` | 分支名是 main。多分支的合并请求分支名通常不是 main |
| `tag 'v*'` | 构建的是匹配的 Git 标签 |
| `changeRequest()` | 这是一次合并请求构建 |
| `changeset '**/*.java'` | 本次变更碰到了这些文件。适合大单体仓库少跑无关模块 |
| `expression { params.TARGET_ENV == 'prod' }` | 任意 Groovy 布尔表达式 |
| `not { ... }` / `allOf` / `anyOf` | 组合 |

`beforeAgent true` 让条件先于领机器计算。部署阶段若写在合并请求上，合并请求构建不会因此排队等一台生产发布机。

## 4.7 `steps`：阶段里能做的动作

步骤由插件提供。核心与常用插件对应的步骤：

| 步骤 | 作用 | 来自 |
|---|---|---|
| `sh` / `bat` / `powershell` | 在 Agent 上执行脚本 | 核心 / Pipeline |
| `checkout scm` | 按任务配置检出当前分支 | Git / 分支源 |
| `junit` | 解析 JUnit XML | JUnit 插件 |
| `archiveArtifacts` | 把文件存进这次构建 | 核心 |
| `stash` / `unstash` | 在同一次构建的不同 Agent 之间搬小文件 | Pipeline |
| `withCredentials` | 在块内注入凭据，离开块即消失 | Credentials Binding |
| `configFileProvider` | 提供一份受管的 `settings.xml` 等文件 | Config File Provider |
| `lock` | 跨构建的互斥锁 | Lockable Resources |
| `milestone` | 旧的构建若落后，就中止，避免旧版本晚到覆盖新版本 | Pipeline |
| `timeout` | 包住容易卡住的步骤 | Pipeline |
| `input` | 人工确认。**作为 stage 的指令，而不是放进已经领了 Agent 的 steps** | Pipeline |
| `build` | 触发另一个任务并等待 | Pipeline |
| `cleanWs` | 删除工作区 | Workspace Cleanup |
| `emailext` | 发邮件 | Email Extension |

`sh` 的引号有实际后果：

```groovy
// Groovy 双引号会在送去 Agent 之前把变量替换进字符串。
// 秘密因此可能进流水线的程序文本。命令要传给 shell 的变量，用 Groovy 单引号。
sh 'curl -fsS -u "$NEXUS_USR:$NEXUS_PSW" "$NEXUS_URL"'

// 需要 Groovy 插值、且确认没有秘密时，再用双引号。
sh "echo 构建号 ${env.BUILD_NUMBER}"
```

Shell 单引号和 Groovy 单引号叠在一起时容易看花。需要在 shell 里保护变量时，用 `sh ''' ... '''` 三段引号，既阻止 Groovy 插值，又允许 shell 展开 `$VAR`。

`stash` 是同一次构建的临时传送带，默认有大小限制，构建结束就没有保留义务。`archiveArtifacts` 是留给人下载的附件。正式发布物走 `mvn deploy` 或 `docker push`。

## 4.8 `post`：无论成败都要留下证据

`post` 可以挂在阶段上，也可以挂在整条流水线上。条件按「更具体的先处理」来记，`always` 总会执行，`cleanup` 在所有其他 `post` 之后执行。

| 条件 | 何时 |
|---|---|
| `always` | 任何结束方式，包含失败和中止 |
| `success` | 成功 |
| `failure` | 失败 |
| `unstable` | 不稳定 |
| `aborted` | 中止 |
| `changed` | 这次结果和上次不同 |
| `fixed` | 上次失败或不安稳，这次成功 |
| `cleanup` | 最后的清扫，适合 `cleanWs` |

```groovy
post {
    always {
        junit allowEmptyResults: false, testResults: '**/target/surefire-reports/*.xml'
    }
    failure {
        echo "失败，日志在 ${env.BUILD_URL}console"
    }
    cleanup {
        cleanWs()
    }
}
```

`allowEmptyResults: false` 表示「一份测试报告都没有」本身就是失败。否则测试没跑，流水线看起来却是绿的。

## 4.9 并行、矩阵、人工确认

并行阶段同时占用多个执行器，用来缩短「单元测试」和「静态检查」这类互不依赖的工作：

```groovy
stage('检查') {
    parallel {
        stage('单元测试') {
            agent { label 'maven' }
            steps { sh 'mvn -B -ntp test' }
            post { always { junit '**/target/surefire-reports/*.xml' } }
        }
        stage('依赖漏洞') {
            agent { label 'maven' }
            steps { sh 'mvn -B -ntp org.owasp:dependency-check-maven:check' }
        }
    }
}
```

矩阵在同一批步骤上展开多组轴，例如多个 JDK。轴的组合数就是并发数，先确认 Agent 池吃得消再加。

人工确认用 **stage 的 `input` 指令**。它在领取本阶段 `agent` 之前执行：

```groovy
stage('确认生产发布') {
    when { branch 'main' }
    options { timeout(time: 24, unit: 'HOURS') }
    input {
        message "将 sha-${SHORT_SHA} 发布到生产？"
        ok '发布'
        submitter 'release-managers'          // 允许点击的用户或组，逗号分隔
    }
    agent none
    steps {
        echo '已确认'
    }
}
```

超时后构建变为中止，后面的部署阶段不会跑。`submitter` 依赖登录用户名，要和安全域里的用户或组一致。

防止旧构建覆盖新版本，部署阶段使用里程碑加锁：

```groovy
stage('部署生产') {
    agent { label 'deploy' }
    steps {
        milestone 1
        lock(resource: 'deploy-order-service') {
            milestone 2
            sh 'kubectl -n prod rollout status deploy/order-service --timeout=180s'
        }
    }
}
```

语义是：若更新的构建已经通过里程碑 1，还停在里程碑 1 之前的旧构建会被中止；锁保证同一个服务不会有两次部署同时改集群。锁的名字需要在「系统管理 → Lockable Resources」里先登记。

## 4.10 脚本块、序列化、Replay

声明式里需要局部变量、循环、条件部署时，放进 `script { }`：

```groovy
script {
    if (params.TARGET_ENV == 'prod' && env.SHORT_SHA == '') {
        error '生产发布必须带 SHORT_SHA'
    }
}
```

`error` 让本步骤失败，流水线变红。

Pipeline 引擎会把步骤之间的程序状态保存到磁盘，所以 `script` 里的局部变量要能序列化。`Matcher`、数据库连接、打开的流，放进变量里会在下一步报 `NotSerializableException`。处理方式是：这种对象只活在一个 `@NonCPS` 方法里，方法返回字符串或数字。初学阶段少在 `script` 里写复杂 Groovy，多写 `sh`，就能避开绝大多数这类错误。

构建页的 **Replay** 可以改一份 `Jenkinsfile` 再跑一次，改动不进 Git。用来确认「是不是这一行的问题」。确认之后把同样的修改提交到仓库。Replay 的脚本若用了未批准的方法签名，会被脚本安全插件拦住，管理员在「系统管理 → In-process Script Approval」里处理。正式流水线以 Git 上的文本为准，不把 Replay 当配置库。

声明式流水线支持 **从某个阶段重新运行**（Restart from Stage）。它重用这次构建里前面阶段的结果。部署阶段失败、镜像已经推上去时，用它重跑部署，而不是再编译一次。前提是那个阶段需要的环境变量和外部产物还在。

### 本章小结

声明式流水线是一份结构固定的文本：顶层不占机器，阶段按标签领机器，`when` 决定跳过，`sh` 的退出码决定红绿，`post` 负责报告和清扫，`input` 在领机器之前询问。下一章处理这些步骤要碰到的密码和权限。

---

# 五、凭据、权限与安全

## 5.1 模块定位

Jenkins 能调用生产集群，是因为它握着生产凭据。本章的目标是：秘密进凭据库，流水线按 ID 借用，用完即从环境里消失；人按角色看见自己该看见的任务。

## 5.2 凭据的类型、范围与 ID

「系统管理 → 凭据 → 系统 → 全局域 → 添加凭据」，或在某个文件夹的凭据页添加（范围更小，优先）。

| 类型 | 典型用途 | 在流水线里如何用 |
|---|---|---|
| Username with password | Git 只读账号、Nexus 部署账号、Harbor 机器人账号 | `withCredentials([usernamePassword(...)])` 或 `credentials()` 绑定 |
| Secret text | Webhook 密钥、API token | `string(credentialsId: '...', variable: 'TOKEN')` |
| SSH Username with private key | Git over SSH、登录构建机 | Git 插件的凭据下拉框；或 `sshUserPrivateKey` |
| Secret file | kubeconfig、JSON 服务账号 | `file(credentialsId: '...', variable: 'KUBECONFIG')` |
| Certificate | 双向 TLS | 对应绑定步骤 |

每条凭据有一个 **ID**。ID 一旦被 `Jenkinsfile` 引用，就变成契约。用稳定的名字：`git-readonly`、`nexus-deploy`、`harbor-robot`、`kubeconfig-prod`。描述字段写给人看，说明这个账号的权限范围和轮换方式。

范围：

- **全局**：所有任务都能绑定。只放真正人人可用的只读凭据，例如拉取公共依赖的账号。
- **文件夹**：只有该文件夹内的任务能绑定。生产 kubeconfig 放这里。
- **用户**：给个人的，任务里引用不到。不把它当 CI 的凭据来源。

凭据正文在界面上保存后不能再查看，只能更新。这是预期行为。请在密码管理器里留一份轮换记录。

## 5.3 在流水线里借用凭据

```groovy
withCredentials([
    usernamePassword(
        credentialsId: 'nexus-deploy',
        usernameVariable: 'NEXUS_USR',
        passwordVariable: 'NEXUS_PSW'
    )
]) {
    sh '''
      test -n "$NEXUS_USR"
      mvn -B -ntp -s "$MAVEN_SETTINGS" deploy -DskipTests
    '''
}
```

离开 `withCredentials` 块后，这两个环境变量不再注入后续步骤。日志里若出现密码原文，Jenkins 会按字符串掩成 `****`。掩码不是加密传输，短密码、被 base64 之后的密码、出现在进程参数里的密码都可能漏。因此：

- Groovy 字符串用单引号，让 shell 自己读环境变量；
- 不把密码拼进 `echo`、不写进 `currentBuild.description`、不用 `set -x` 跟踪含秘密的命令；
- Maven 使用由 Config File Provider 提供的 `settings.xml`，服务器密码用 Maven 的服务器项，而不是写在命令行 `-Dpassword=`。

kubeconfig 是一个文件型秘密：

```groovy
withCredentials([file(credentialsId: 'kubeconfig-prod', variable: 'KUBECONFIG')]) {
    sh 'kubectl --kubeconfig "$KUBECONFIG" -n prod rollout status deploy/order-service --timeout=180s'
}
```

这个 kubeconfig 对应的 Kubernetes 账号，只授予该服务所在命名空间的部署权限，而不是集群管理员。

Git 的凭据通常不写在 `Jenkinsfile` 里。多分支任务的分支源上已经选了凭据，`checkout scm` 会沿用它。

## 5.4 谁可以登录，谁可以操作任务

安全域（Security Realm）回答「你是谁」：

| 方式 | 场景 |
|---|---|
| 本地用户 | 学习环境、紧急管理员。关闭「允许用户注册」 |
| LDAP / Active Directory | 公司已有目录 |
| OIDC / SAML / GitHub OAuth | 由对应插件接入，组信息用于授权 |

授权策略回答「你能做什么」：

- **矩阵授权**：全局一张大表。人少时直观。
- **基于角色的策略（Role-based Authorization Strategy）**：全局角色 + 文件夹/任务模式角色。团队一多就用它。

一套够用的角色划分：

| 角色 | 权限 | 谁 |
|---|---|---|
| `admin` | Overall/Administer | 一两名平台管理员。能改系统、看凭据配置、装插件 |
| `developer` | 总体只读；在自己文件夹里 Job/Read、Job/Build、Job/Cancel | 开发。能看日志、能重跑，不能改 Controller 配置 |
| `release` | 在发布文件夹里额外允许通过 input | 可以点生产确认的人。和 `submitter` 里的组一致 |
| 匿名 | 无 | 关闭匿名可读。构建日志里偶尔会有内部地址 |

登录后的 API 调用使用用户的 **API Token**（用户头像 → Security → Add new Token），不使用登录密码。webhook 使用插件约定的密钥，不把管理员 token 配进 Git 托管方。

脚本安全：从 Git 加载的流水线跑在 Groovy 沙箱里，只能调用白名单里的方法。管理员在 Script Console（「系统管理 → Script Console」）里执行的 Groovy **没有沙箱**，等价于在 Controller 上任意执行。Script Console 只留给平台管理员排障，不往里贴业务逻辑。

## 5.5 运行时的攻击面

按优先级处理：

1. **插件。** 安全公告多数落在插件上。少装、锁版本、看 [Jenkins Security Advisories](https://www.jenkins.io/security/advisories/)。
2. **网络。** Controller 的 8080 和 Agent 入站端口不对公网裸奔。前面是公司身份系统或 VPN。Agent 端口（常见 50000）只对构建网络开放。
3. **Agent 对 Controller 的权限。** 现代版本默认限制 Agent 能让 Controller 做的事。保持默认，不为了让某个旧插件工作就全局放开。
4. **构建步骤就是代码执行。** 谁能改 `Jenkinsfile` 或共享库，谁就能在 Agent 上执行命令。`main` 分支保护、共享库仓库的评审，和业务代码同一标准。
5. **Docker 套接字。** 见 4.3。专用构建机，或改用不挂套接字的镜像构建。

内置节点执行器为 0，也是安全措施：流水线作者不该在 Controller 进程旁边跑自己的 `sh`。

### 本章小结

凭据是带 ID 的密文，文件夹决定谁能引用它，`withCredentials` 决定它活过多长时间。角色决定谁能看日志、谁能点生产确认。下一章把执行这些步骤的机器接上。

---

# 六、Agent 的四种形态

## 6.1 模块定位

流水线里的 `label` 必须有节点来兑现。四种形态可以并存：固定虚拟机跑特殊构建（需要硬件加密狗、需要内网专线），Kubernetes Pod 跑日常编译。

选择时看三件事：构建要不要特殊硬件或网络、构建之间要不要隔离、扩容时谁来创建机器。

## 6.2 固定节点：SSH 连出去

Controller 主动 SSH 到一台已经装好 JDK 的 Linux。「系统管理 → 节点 → 新建节点」：

| 项 | 示例 |
|---|---|
| 名称 | `build-1` |
| 远程目录 | `/var/jenkins/agent`。这个用户对它可写 |
| 启动方式 | Launch agents via SSH |
| 主机 | `build-1.internal` |
| 凭据 | SSH 私钥，ID `agent-ssh-build-1`。这个账号只用于跑构建 |
| 标签 | `linux docker maven` |
| 执行器数量 | 2～4。大约按 CPU 核数，观察负载再加 |
| 可用性 | 尽量保持在线 |

工作目录放在独立磁盘。节点配置里打开工作区清理，或在流水线 `post` 里 `cleanWs()`。固定节点的磁盘是「上次构建的残留」，动态 Pod 则没有这个问题。

标签表达能力，不表达这台机器的名字。流水线写 `linux && maven`，不写节点名 `build-1`。换机器时只改标签的粘贴对象。

## 6.3 入站节点：Agent 连进来

有的网络只允许构建区主动向外访问，Controller 不能 SSH 进去。这时用入站 Agent：在节点页选「Use WebSocket」，Jenkins 给出一段启动命令，构建机上的 `remoting.jar` 连回 Controller。

WebSocket 入站复用 HTTPS 端口，比老的固定 TCP（50000）好放行。仍然要认证（Agent 的密钥），并且 Controller 的 HTTPS 要对构建网络可达。

老文档里的 JNLP 是同一类入站协议的前身。新装使用 WebSocket。

## 6.4 在固定节点上临时起 Docker 容器

4.3 的 `agent { docker { ... } }` 属于这种。外层节点是 6.2 的固定机器，内层容器提供干净的 Maven 或 Node。Maven 本地仓库用命名卷（示例里的 `maven-cache`）跨构建保留，避免每次重新下载互联网。

这台固定机器的 Docker 只服务构建。不在同一守护进程上跑生产容器。

## 6.5 Kubernetes 上的一次性 Pod

Kubernetes 插件在 Controller 上登记一个「云」。流水线要 Agent 时，插件按 Pod 模板创建一个 Pod，里面跑 `remoting`，构建结束删除 Pod。

适合日常编译的原因：并发上限就是集群的配额；每个构建的文件系统是新的；镜像里工具链版本和仓库一起评审（模板可以放在 Git 里，见 8.5）。

Controller 在集群里使用的 ServiceAccount 需要管理 Pod 的权限（创建、删除、查看日志、exec）。权限收在 `jenkins` 命名空间。插件版本不同，RBAC 清单会变，以所用版本的 Kubernetes 插件文档为准。Helm 官方 chart `jenkinsci/jenkins` 会带上一份可用的起点。

Pod 里每个容器要保持运行，Jenkins 才能 `exec` 进去执行 `sh`。模板里用 `command: ["cat"]` 加上 `tty: true` 盖住镜像原来的入口命令，是这个插件的惯用写法。真正的构建命令仍由流水线的 `sh` 发出。

## 6.6 工具装在哪

| 做法 | 何时 |
|---|---|
| 构建镜像里装好 JDK、Maven、kubectl | 和 Kubernetes / Docker Agent 配套。版本跟模板走，最容易复现 |
| Jenkins「全局工具配置」里登记 JDK、Maven，流水线用 `tools { maven 'maven-3.9' }` | 固定虚拟机 Agent。第一次构建时 Jenkins 可以把工具装到节点上 |
| Agent 镜像用 Java 21 跑 remoting，旁边再放一个 Java 17 的 `JAVA_HOME` | 业务还要按 Java 17 编译，而 Jenkins 2.555+ 的 Agent 进程必须是 Java 21+ |

`tools` 指令示例：

```groovy
stage('测试') {
    agent { label 'linux' }
    tools {
        jdk 'temurin-21'
        maven 'maven-3.9'
    }
    steps {
        sh 'mvn -B -ntp test'
    }
}
```

名字 `temurin-21`、`maven-3.9` 必须和全局工具配置里的名字一致。JCasC 可以声明这些工具，避免只存在于某次界面点击里。

## 6.7 容量的经验数字

Controller 起步 4 vCPU、8 GB 内存，JVM 显式设置堆（例如 `-Xmx2g`），执行器为 0。它的负载来自界面、排队、日志索引，不来自 `mvn`。

一个并发的 Maven 构建，Agent 侧按 2 GB 内存起估，Java 项目经常要到 3～4 GB。执行器数量乘单次内存，要小于这台 Agent 的内存，并留出页面缓存。

磁盘：工作区加 Docker 层缓存。给构建机 SSD，并给 Docker 或容器运行时单独的数据盘。Controller 侧靠丢弃旧构建控制日志体积。

### 本章小结

固定 SSH 节点好懂、适合有特殊网络的构建；入站节点适应「构建区只能向外连」；Docker 与 Kubernetes 提供一次性环境。流水线只依赖标签。下一章说明插件如何把这些能力装上，以及怎样让 Controller 的配置可重建。

---

# 七、插件、配置即代码与共享库

## 7.1 模块定位

Jenkins 核心提供 Item、队列、节点、安全模型。`sh` 的流水线语法、Git、Kubernetes、角色权限都是插件，通过扩展点挂上来。你在 `Jenkinsfile` 里能调用的步骤，就是已安装插件注册的步骤。

装插件的路径：「系统管理 → Plugins」。学习环境可以用安装向导里的「推荐插件」，它覆盖 Git、流水线和界面基础。生产环境用文本清单安装，版本写死。

## 7.2 一份起步插件清单

下面是插件 ID。学习环境可以只写 ID，安装时取当时的最新版。生产环境写成 `id:版本`，版本从插件页复制。`workflow-aggregator` 是流水线的聚合插件，会带上声明式语法及其依赖。

```text
# 平台
configuration-as-code
role-strategy
credentials-binding
timestamper
ansicolor
ws-cleanup
lockable-resources
email-ext

# 流水线与 Git
workflow-aggregator
workflow-cps-global-lib
git
github-branch-source
# 代码在 GitLab 时把上一行换成 gitlab-branch-source

# Java 构建留证
junit
jacoco
config-file-provider
sonar

# 容器与集群。按你的 Agent 形态删减
docker-workflow
kubernetes
kubernetes-cli
```

2.580.1 起，JUnit、Mailer 等不再打包进 `jenkins.war`。关闭安装向导、又没有这份清单时，你会得到一台没有 `junit` 步骤的 Jenkins。8.2 把清单放进镜像构建。

插件升级的纪律：先在灰度实例装同一份清单的新版本，跑一条代表性流水线，再升级生产。公告里的高危插件单独处理，不等季度大升级。

## 7.3 配置即代码（JCasC）

JCasC 插件把「系统管理」里的一大类配置写成 YAML。Controller 启动时读 `CASC_JENKINS_CONFIG` 指向的文件或目录。

适合放进 YAML 的：Jenkins URL、执行器数量、安全域、授权、全局工具、共享库地址、邮件服务器、凭据的 **占位引用**（值来自环境变量或密钥文件）。

仍可能留在界面或任务定义里的：某个具体仓库的多分支任务。它也可以用 Job DSL 插件或 JCasC 的 jobs 段管起来。第一个仓库用界面创建完全可以，创建完用 JCasC 的「View Configuration」对照系统部分是否漂移。

改完 YAML，在「系统管理 → Configuration as Code → Reload existing configuration」热加载。热加载覆盖不了的项（新插件）需要重启。

**界面上的手工修改和下一次加载会打架。** 选定 JCasC 为系统配置的来源之后，系统级改动改 YAML，走 Git 评审。

一份可运行的 YAML 在 8.2，这里先记住秘密的写法：YAML 里只出现 `${GITHUB_TOKEN}` 这种占位，token 的值来自 Controller 进程的环境变量，由部署平台注入，不提交进 Git。

## 7.4 共享库

共享库是另一个 Git 仓库，用来放多个服务重复的步骤。目录约定：

```text
jenkins-shared-library/
├── vars/                          # 每个文件是一个全局函数，文件名就是函数名
│   ├── computeShortSha.groovy
│   └── deployService.groovy
├── src/                           # 可选，正规 Groovy 类，包名目录
│   └── com/example/Version.java   # 实际是 .groovy
└── resources/                     # 可选，库内静态文件
    └── pod-templates/maven.yaml
```

`vars/computeShortSha.groovy`：

```groovy
def call() {
    return sh(script: 'git rev-parse --short=12 HEAD', returnStdout: true).trim()
}
```

业务仓库的 `Jenkinsfile`：

```groovy
@Library('company-pipeline') _
pipeline {
    agent { label 'maven' }
    stages {
        stage('版本') {
            steps {
                script { env.SHORT_SHA = computeShortSha() }
            }
        }
    }
}
```

`@Library('company-pipeline') _` 里的名字是 Jenkins 全局共享库配置里的名字，不是 Git 仓库名。下划线表示把 `vars/` 加载进当前脚本。库可以按分支或 tag 版本化：`@Library('company-pipeline@v2') _`。生产使用 tag，库的 `main` 只代表最新开发。

全局库在 JCasC 里登记（见 8.2）。文件夹级共享库可以只给一组任务使用，适合还在试验的函数。

共享库跑在受信上下文里，能调用的 API 比普通 `Jenkinsfile` 多。评审共享库的标准和评审生产部署脚本相同。

库的两种用法：

| 用法 | `Jenkinsfile` 长什么样 | 适合 |
|---|---|---|
| 函数库 | 阶段仍写在业务仓库里，重复的 `sh` 收成函数 | 学习阶段、各服务差异还大 |
| 整条流水线 | 业务仓库只剩 `standardService(image: 'demo/order')` 一行 | 服务形态高度统一、平台组希望统一升级 |

8.5 采用函数库。整条流水线藏进库里之后，业务开发者要打开另一个仓库才能看见阶段，等团队已经接受「流水线由平台组拥有」再做。

## 7.5 Job DSL 与多分支的分工

Job DSL 用 Groovy 创建任务，适合「有 200 个结构相同的任务要生成」。多分支流水线已经能按分支生成子任务，**不要再写一层 DSL 去为每个分支造任务**。

DSL 仍然有用的地方：创建文件夹、创建那一个多分支父任务、创建参数化的部署任务，使新环境的 Jenkins 连任务清单也是代码。那份 DSL 可以放在平台仓库，由一个「种子任务」在 Jenkins 启动后跑一次。起步阶段用界面创建父任务，把 DSL 留到第二个服务出现之后。

### 本章小结

插件决定步骤存不存在；`plugins.txt` 决定生产上存在的是哪一版；JCasC 决定系统配置能否重建；共享库决定重复步骤有没有一个评审过的家。下一章把这些收成四套可以对照着配的文件。

---

# 八、CI/CD 实践：四套详细配置

## 8.1 这四套配置分别解决什么

| 编号 | 配置 | 你配完能做什么 |
|---|---|---|
| 8.2 | Controller：镜像、插件清单、JCasC | 得到一台执行器为 0、账号与共享库已声明、重启后配置还在的 Jenkins |
| 8.3 | 业务仓库里的参考 `Jenkinsfile` | 一次提交完成测试、质量门禁、发布 jar、构建不可变镜像、自动部署开发环境、人工确认后部署生产 |
| 8.4 | 多分支任务与 `when` | 合并请求只验证；只有 `main` 上的非合并请求构建才能部署 |
| 8.5 | 共享库函数 + Kubernetes Pod 模板 | 把重复步骤挪出业务仓库，构建机改为用完即删的 Pod |

四套配置遵守同一组交付规则：

1. 测试命令的非 0 退出码让构建变红；JUnit 报告在 `post { always }` 发布。
2. 发布到 Nexus 的 jar、推送到 Harbor 的镜像，版本号是 Git 提交的 12 位前缀，前面加 `sha-`。
3. 开发环境和生产环境部署同一个 `sha-`。生产阶段不执行 `mvn package`，不执行 `docker build`。
4. 顶层 `agent none`。审批用 stage 的 `input`，并设置提交人与超时。
5. 生产部署放在 `milestone` 与 `lock` 里。
6. 凭据只用 ID。Shell 用 Groovy 单引号。
7. 生产 kubeconfig 放在文件夹凭据，不放全局。
8. 合并请求构建不部署。
9. Controller 内置节点执行器为 0。
10. 系统配置在 YAML 里，业务步骤在 `Jenkinsfile` 里。

示例里的域名 `jenkins.example.com`、`harbor.example.com`、`nexus.example.com`、命名空间 `demo`、服务名 `order-service`，按你的环境替换。凭据 ID 建议原样保留，团队内部统一之后，`Jenkinsfile` 可以跨服务复制。

---

## 8.2 实践一：把 Controller 配成可重建的系统

### 目标

一台用于学习和预生产的 Controller：跳过安装向导，装好清单里的插件，本地管理员密码来自环境变量，内置节点不跑构建，登记一个共享库和邮件。具体业务仓库的多分支任务在 8.4 用界面创建（界面路径写在那一节）。

### 目录

平台仓库（和业务代码分开）：

```text
jenkins-platform/
├── Dockerfile
├── plugins.txt
├── compose.yaml
└── casc/
    └── jenkins.yaml
```

### `plugins.txt`

```text
configuration-as-code
role-strategy
credentials-binding
timestamper
ansicolor
ws-cleanup
lockable-resources
email-ext
workflow-aggregator
workflow-cps-global-lib
git
github-branch-source
junit
jacoco
config-file-provider
sonar
docker-workflow
kubernetes
kubernetes-cli
```

生产环境在每个 ID 后加上 `:当前版本`。升级时改这一份文件，走评审。

### `Dockerfile`

```dockerfile
FROM jenkins/jenkins:lts-jdk21

COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false -Xmx2g" \
    CASC_JENKINS_CONFIG="/casc"
```

镜像标签 `lts-jdk21` 会跟随官方当前 LTS。生产拉取后用摘要钉死，例如记录 `docker inspect --format '{{index .RepoDigests 0}}'` 的结果，下次部署写 `jenkins/jenkins@sha256:...`。写作时的 LTS 版本号是 2.580.1。

官方镜像里的 `jenkins-plugin-cli` 会把插件装到引用目录，首次启动复制进 `JENKINS_HOME`。

### `casc/jenkins.yaml`

```yaml
jenkins:
  systemMessage: "配置来自 jenkins-platform 仓库的 casc/jenkins.yaml。系统级改动请改仓库。"
  numExecutors: 0
  mode: EXCLUSIVE
  labelString: "built-in"
  securityRealm:
    local:
      allowsSignup: false
      users:
        - id: "admin"
          password: "${JENKINS_ADMIN_PASSWORD}"
  authorizationStrategy:
    roleBased:
      roles:
        global:
          - name: "admin"
            description: "平台管理员"
            permissions:
              - "Overall/Administer"
            assignments:
              - "admin"
          - name: "developer"
            description: "只读全局，具体构建权限配在文件夹角色上"
            permissions:
              - "Overall/Read"
            assignments:
              - "authenticated"
  crumbIssuer:
    standard:
      excludeClientIPFromCrumb: true

unclassified:
  location:
    url: "https://jenkins.example.com/"
    adminAddress: "jenkins@example.com"
  globalLibraries:
    libraries:
      - name: "company-pipeline"
        defaultVersion: "v1"
        retriever:
          modernSCM:
            scm:
              git:
                remote: "https://github.com/example/jenkins-shared-library.git"
                credentialsId: "git-readonly"

credentials:
  system:
    domainCredentials:
      - credentials:
          - usernamePassword:
              scope: GLOBAL
              id: "git-readonly"
              description: "拉取业务仓库与共享库。只读。"
              username: "git-bot"
              password: "${GIT_READONLY_TOKEN}"
          - usernamePassword:
              scope: GLOBAL
              id: "harbor-robot"
              description: "向 demo 项目推送镜像的 Harbor 机器人账号。"
              username: "robot$demo"
              password: "${HARBOR_ROBOT_TOKEN}"
          - usernamePassword:
              scope: GLOBAL
              id: "nexus-deploy"
              description: "向 Nexus maven-releases 部署。"
              username: "deploy"
              password: "${NEXUS_DEPLOY_PASSWORD}"
```

说明：

- `numExecutors: 0` 与 `mode: EXCLUSIVE` 一起，让内置节点不接受构建。
- `authorizationStrategy.roleBased` 的字段随 Role Strategy 插件版本变化。若加载报未知字段，先把 `authorizationStrategy` 整段删掉让实例能启动，再打开「Configuration as Code → View Configuration」，按导出的结构改键名（有的版本使用 `assignments`，旧版使用 `entries`）。
- 邮件服务器没有写进这份 YAML：`email-ext` 的配置结构也随版本变化。在界面配通 SMTP 后，用 View Configuration 导出对应段落，再贴回仓库。
- 生产 kubeconfig 不放进这份全局 YAML。在 `platform` 文件夹里添加文件凭据 `kubeconfig-prod`，或把文件夹凭据写成只存在于部署环境的独立 YAML，并限制仓库可见范围。
- SonarQube 服务器、JDK 工具、Kubernetes 云同样可以进 JCasC。第一天先在界面配通，用 View Configuration 导出，再贴回仓库。导出的 YAML 就是你这套插件版本的正确模式。

界面上还要做一次、并可以随后导出的项：

| 配置 | 路径 | 要点 |
|---|---|---|
| SonarQube 服务器 | 系统管理 → System → SonarQube servers | 名字 `sonar-server`，要和 `withSonarQubeEnv('sonar-server')` 一致。Server authentication token 用凭据 `sonar-token`。SonarQube 上配置 webhook：`https://jenkins.example.com/sonarqube-webhook/`，密钥与 Jenkins 里该服务器的 webhook secret 相同。没有这条 webhook，`waitForQualityGate` 会一直等到超时 |
| Maven settings | 系统管理 → Managed files | ID `maven-settings`。内容见 8.3 |
| 锁 | 系统管理 → Lockable Resources | 资源名 `deploy-order-service` |
| 邮件 | 系统管理 → System → Extended E-mail Notification | 配通后导出到 YAML。凭据 ID `smtp-auth` |

### `compose.yaml`

给本机学习用。企业里把同一组环境变量交给虚拟机或 Helm，而不是把密码写进这个文件。

```yaml
services:
  jenkins:
    build: .
    ports:
      - "8080:8080"
    volumes:
      - jenkins_home:/var/jenkins_home
      - ./casc:/casc:ro
    environment:
      JENKINS_ADMIN_PASSWORD: ${JENKINS_ADMIN_PASSWORD}
      GIT_READONLY_TOKEN: ${GIT_READONLY_TOKEN}
      HARBOR_ROBOT_TOKEN: ${HARBOR_ROBOT_TOKEN}
      NEXUS_DEPLOY_PASSWORD: ${NEXUS_DEPLOY_PASSWORD}
      CASC_JENKINS_CONFIG: /casc
    restart: unless-stopped

volumes:
  jenkins_home:
```

在运行 Compose 的 shell 里导出这些变量后再启动。变量值为空时，JCasC 会把空密码写进凭据，表现为「认证失败」，而不是「YAML 没生效」。

启动后打开 `http://localhost:8080`，用 `admin` 和 `JENKINS_ADMIN_PASSWORD` 登录。系统消息能看见 YAML 里的那句话，即表示配置已加载。节点页里内置节点的执行器数量为 0。

本机若还要把构建容器跑在同一台 Docker 上，需要把引擎的套接字给 Agent 而不是给 Controller 的日常操作。那条练习路径、以及「CI 容器必须看见宿主机引擎」的约束，写在 `Jenkins学习方案.md`，这里不重复。

### 备份

备份卷里的 `/var/jenkins_home`，确认包含 `secrets/`。恢复演练的标准：新起一个空实例，挂上这份备份，管理员能登录，任意一条流水线能解析共享库并读到 `git-readonly`。

---

## 8.3 实践二：参考流水线，从提交到生产

### 目标

一个 Spring Boot 服务的仓库根目录具备这些文件：

```text
order-service/
├── Jenkinsfile
├── pom.xml
├── Dockerfile
└── deploy/k8s/
    ├── kustomization.yaml
    └── deployment.yaml
```

流水线阶段：

```text
检出 → 取 SHA → 单元测试 →（main）Sonar 门禁 → 发布 jar 到 Nexus
     → 构建并推送镜像 sha-<12位>
     → 自动部署开发命名空间
     →（main，且不是合并请求）等待发布负责人确认
     → 用同一个 sha 部署生产命名空间
```

### Maven：测试、覆盖率、部署坐标

`pom.xml` 里和 CI 有关的最小片段。`distributionManagement` 的 `id` 必须等于 `settings.xml` 里 `<server>` 的 `id`。

```xml
<properties>
  <revision>1.0.0</revision>
</properties>

<build>
  <plugins>
    <plugin>
      <groupId>org.jacoco</groupId>
      <artifactId>jacoco-maven-plugin</artifactId>
      <version>0.8.12</version>
      <executions>
        <execution>
          <goals><goal>prepare-agent</goal></goals>
        </execution>
        <execution>
          <id>report</id>
          <phase>verify</phase>
          <goals><goal>report</goal></goals>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>

<distributionManagement>
  <repository>
    <id>nexus-releases</id>
    <url>https://nexus.example.com/repository/maven-releases/</url>
  </repository>
  <snapshotRepository>
    <id>nexus-snapshots</id>
    <url>https://nexus.example.com/repository/maven-snapshots/</url>
  </snapshotRepository>
</distributionManagement>
```

CI 发布正式版本时使用 `mvn -Drevision=1.0.0+sha-<提交>`，避免覆盖 `1.0.0`。快照留给开发者本机，CI 的 `main` 不部署 `-SNAPSHOT`。

### Managed file：`maven-settings`（ID 必须一致）

在「系统管理 → Managed files」新建 Maven settings，ID 为 `maven-settings`。文件放在 Jenkins 里，不提交到业务仓库。用户名和密码使用 Token Macro，由流水线在 `withCredentials` 之后、`replaceTokens: true` 时替换。这样 `Jenkinsfile` 里不必拼接 XML（拼接时缩进会弄坏 heredoc，密码里的引号也会弄坏脚本）。

```xml
<settings>
  <servers>
    <server>
      <id>nexus-releases</id>
      <username>${ENV,var="NEXUS_USR"}</username>
      <password>${ENV,var="NEXUS_PSW"}</password>
    </server>
  </servers>
</settings>
```

`<id>nexus-releases</id>` 与 pom 里 `distributionManagement` 的 id 相同。密码里若含 `<`、`&`，要先做成 XML 转义再存入凭据，或改用 Config File Provider 自己的加密密码字段。

### `Dockerfile`

测试已经在 Maven 阶段完成。镜像只打包那个 jar。

```dockerfile
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY target/*.jar /app/app.jar
EXPOSE 8080
USER 65532
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

`.dockerignore` 至少包含 `.git`。构建上下文要包含 `target/*.jar`，所以不要忽略 `target`。

### 部署清单

`deploy/k8s/deployment.yaml` 里的镜像名写成 kustomize 能识别的名字 `order-service`：

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: order-service
spec:
  replicas: 2
  selector:
    matchLabels:
      app: order-service
  template:
    metadata:
      labels:
        app: order-service
    spec:
      containers:
        - name: order-service
          image: order-service:bootstrap
          ports:
            - containerPort: 8080
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            initialDelaySeconds: 10
          # 服务需提供该路径（Spring Boot Actuator 的 readiness）。
          # 还没有这个端点时，先改成你已有的健康检查路径，否则 rollout status 会超时。
          resources:
            requests:
              cpu: "200m"
              memory: "512Mi"
            limits:
              memory: "1Gi"
```

`deploy/k8s/kustomization.yaml`：

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
namespace: demo
resources:
  - deployment.yaml
images:
  - name: order-service
    newName: harbor.example.com/demo/order-service
    newTag: bootstrap
```

流水线在工作区里改写 `newTag` 再 `kubectl apply -k`。改写发生在 Agent 的工作区，不提交回 Git。Git 里的 `bootstrap` 只是占位。生产命名空间由流水线在应用前替换 `namespace`。

### `Jenkinsfile`

```groovy
// 业务仓库根目录。阶段名称用中文，方便在阶段视图里对照本文。
pipeline {
    agent none

    options {
        timestamps()
        ansiColor('xterm')
        timeout(time: 45, unit: 'MINUTES')
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '30', artifactNumToKeepStr: '5'))
    }

    environment {
        HARBOR = 'harbor.example.com'
        IMAGE = 'harbor.example.com/demo/order-service'
        NEXUS_URL = 'https://nexus.example.com'
    }

    stages {
        stage('检出并计算版本') {
            agent { label 'maven && linux' }
            steps {
                checkout scm
                script {
                    env.SHORT_SHA = sh(script: 'git rev-parse --short=12 HEAD', returnStdout: true).trim()
                    env.IMAGE_TAG = "sha-${env.SHORT_SHA}"
                    currentBuild.description = env.IMAGE_TAG
                }
            }
        }

        stage('单元测试') {
            agent { label 'maven && linux' }
            steps {
                sh 'mvn -B -ntp -Drevision=1.0.0+$IMAGE_TAG test'
            }
            post {
                always {
                    junit allowEmptyResults: false,
                         testResults: '**/target/surefire-reports/*.xml'
                    jacoco execPattern: '**/target/jacoco.exec',
                          classPattern: '**/target/classes',
                          sourcePattern: '**/src/main/java'
                }
            }
        }

        stage('质量门禁') {
            when {
                beforeAgent true
                branch 'main'
            }
            agent { label 'maven && linux' }
            steps {
                withSonarQubeEnv('sonar-server') {
                    sh 'mvn -B -ntp -Drevision=1.0.0+$IMAGE_TAG org.sonarsource.scanner.maven:sonar-maven-plugin:sonar'
                }
                timeout(time: 10, unit: 'MINUTES') {
                    // abortPipeline: true 时，门禁不是 OK 就让构建失败
                    waitForQualityGate abortPipeline: true
                }
            }
        }

        stage('发布 jar') {
            when {
                beforeAgent true
                allOf {
                    branch 'main'
                    not { changeRequest() }
                }
            }
            agent { label 'maven && linux' }
            steps {
                withCredentials([usernamePassword(
                    credentialsId: 'nexus-deploy',
                    usernameVariable: 'NEXUS_USR',
                    passwordVariable: 'NEXUS_PSW'
                )]) {
                    configFileProvider([
                        configFile(fileId: 'maven-settings', variable: 'MAVEN_SETTINGS', replaceTokens: true)
                    ]) {
                        sh 'mvn -B -ntp -s "$MAVEN_SETTINGS" -Drevision=1.0.0+$IMAGE_TAG deploy -DskipTests'
                    }
                }
            }
        }

        stage('构建并推送镜像') {
            when {
                beforeAgent true
                allOf {
                    branch 'main'
                    not { changeRequest() }
                }
            }
            agent { label 'docker && maven && linux' }
            steps {
                sh 'mvn -B -ntp -Drevision=1.0.0+$IMAGE_TAG -DskipTests package'
                withCredentials([usernamePassword(
                    credentialsId: 'harbor-robot',
                    usernameVariable: 'HARBOR_USR',
                    passwordVariable: 'HARBOR_PSW'
                )]) {
                    sh '''
                      echo "$HARBOR_PSW" | docker login "$HARBOR" -u "$HARBOR_USR" --password-stdin
                      docker build -t "$IMAGE:$IMAGE_TAG" .
                      docker push "$IMAGE:$IMAGE_TAG"
                      docker logout "$HARBOR"
                    '''
                }
            }
        }

        stage('部署开发环境') {
            when {
                beforeAgent true
                allOf {
                    branch 'main'
                    not { changeRequest() }
                }
            }
            agent { label 'deploy && linux' }
            options { skipDefaultCheckout() }
            steps {
                checkout scm
                withCredentials([file(credentialsId: 'kubeconfig-dev', variable: 'KUBECONFIG')]) {
                    sh '''
                      sed "s/newTag: bootstrap/newTag: ${IMAGE_TAG}/" deploy/k8s/kustomization.yaml > /tmp/kustomization.yaml
                      mkdir -p /tmp/dev-k
                      cp deploy/k8s/deployment.yaml /tmp/dev-k/deployment.yaml
                      mv /tmp/kustomization.yaml /tmp/dev-k/kustomization.yaml
                      kubectl --kubeconfig "$KUBECONFIG" apply -k /tmp/dev-k
                      kubectl --kubeconfig "$KUBECONFIG" -n demo rollout status \
                        deployment/order-service --timeout=180s
                    '''
                }
            }
        }

        stage('确认生产发布') {
            when {
                beforeAgent true
                allOf {
                    branch 'main'
                    not { changeRequest() }
                }
            }
            options { timeout(time: 24, unit: 'HOURS') }
            input {
                message "将 ${env.IMAGE}:${env.IMAGE_TAG} 发布到生产命名空间？"
                ok '发布'
                submitter 'release-managers'
            }
            agent none
            steps {
                echo "已确认 ${env.IMAGE}:${env.IMAGE_TAG}"
            }
        }

        stage('部署生产环境') {
            when {
                beforeAgent true
                allOf {
                    branch 'main'
                    not { changeRequest() }
                }
            }
            agent { label 'deploy && linux' }
            options { skipDefaultCheckout() }
            steps {
                checkout scm
                milestone 1
                lock(resource: 'deploy-order-service') {
                    milestone 2
                    withCredentials([file(credentialsId: 'kubeconfig-prod', variable: 'KUBECONFIG')]) {
                        sh '''
                          mkdir -p /tmp/prod
                          cp deploy/k8s/deployment.yaml /tmp/prod/deployment.yaml
                          sed -e "s/namespace: demo/namespace: prod/" \
                              -e "s/newTag: bootstrap/newTag: ${IMAGE_TAG}/" \
                              deploy/k8s/kustomization.yaml > /tmp/prod/kustomization.yaml
                          kubectl --kubeconfig "$KUBECONFIG" apply -k /tmp/prod
                          kubectl --kubeconfig "$KUBECONFIG" -n prod rollout status \
                            deployment/order-service --timeout=180s
                        '''
                    }
                }
            }
        }
    }

    post {
        success {
            echo "发布物 ${env.IMAGE}:${env.IMAGE_TAG} 已推送。开发环境已更新。若本次通过了生产确认，生产环境为同一 tag。"
        }
        failure {
            echo "失败。打开 ${env.BUILD_URL}console"
        }
    }
}
```

这段参考流水线是教学用的全长版本，有意把每个机制都摊在一个文件里。真正提交进仓库前，按下面的说明收敛。

### 为什么每个阶段这样写

**检出和 SHA 单独一个阶段。** `IMAGE_TAG` 在后续阶段要稳定。多分支任务里 `GIT_COMMIT` 常常已有值；这里仍用 `git rev-parse`，因为这是工作区里真实检出的提交，和「索引记录的提交」不一致时以工作区为准。

**测试阶段不部署。** `mvn test` 失败则阶段失败。`junit` 在 `always` 里，失败时网页上仍能点开用例。JaCoCo 读取 `target/jacoco.exec`，需要 pom 里的 `prepare-agent`。报告阈值可以在 `jacoco` 步骤上加 `minimumLineCoverage` 等参数，加了之后覆盖率不足会把构建标成不稳定或失败（看参数）。起步先把报告挂上，阈值等基线稳定再设。

**质量门禁只在 `main`。** 合并请求也可以跑 Sonar，但要给每个请求一个独立的分析参数（`sonar.pullrequest.key` 等），否则会污染主分支的质量数据。起步阶段合并请求以单元测试为准，`main` 跑门禁。`waitForQualityGate` 依赖 8.2 里那条 webhook。

**jar 与镜像的版本相同。** `1.0.0+sha-abc123` 和镜像 `sha-abc123` 来自同一个 `SHORT_SHA`，排查「线上是哪次提交」时对得上。

**镜像阶段又执行了一次 `package`。** 因为测试阶段和镜像阶段可能落在不同 Agent 上，jar 不会自动跟着走。两个做法里选一个：用 `stash` 把 `target/*.jar` 传过去，或在镜像 Agent 上重新 `package`（跳过测试）。上面的文件用后者，阶段更独立。`stash` 的写法：

```groovy
// 测试阶段 post 之前
stash name: 'jar', includes: 'target/*.jar,Dockerfile,deploy/**'
// 镜像阶段
unstash 'jar'
```

服务一大，`stash` 比再跑一次 `package` 省时间。

**开发部署先改 `newTag` 再 `apply`。** kustomization 里的 `bootstrap` 只是占位，不对应真实镜像。流水线把它换成 `sha-` 之后再 apply，集群第一次拉起的就是刚推送的镜像。Harbor 对 `sha-*` 打开 tag immutability 之后，同一个 tag 不能被第二次 push 覆盖。参数化的补发任务（下面一节）面对的是已经存在的 Deployment，所以只做 `set image`。

**生产确认不领 Agent。** `input` 是 stage 指令，`agent none`。24 小时没人确认则本阶段超时，构建中止，生产集群保持原样。

**`milestone` 在锁的外面和里面各一次。** 外面的里程碑丢掉已经过时的旧构建；锁里的里程碑保证拿到锁之后，若期间有更新的构建抢先完成，当前这次仍会停下来。

**生产 kubeconfig 与开发 kubeconfig 分成两条凭据。** 它们放在文件夹 `platform` 下，ID 为 `kubeconfig-dev` 与 `kubeconfig-prod`。对应的 Kubernetes 账号分别只能写 `demo` 或 `prod` 命名空间。

### 这一份文件里应再收敛的两点

教学文件为了少依赖外部二进制，生产阶段用 `sed` 改命名空间。团队仓库里更清晰的做法是两份 kustomize overlay：

```text
deploy/k8s/base/          # Deployment
deploy/k8s/overlays/dev/
deploy/k8s/overlays/prod/ # namespace: prod，副本数可以不同
```

流水线改为 `kubectl apply -k deploy/k8s/overlays/prod`，并在 overlay 里用 kustomize 的 `images` 字段。在流水线里生成 overlay 的 tag：

```bash
cd deploy/k8s/overlays/prod
kustomize edit set image "order-service=${IMAGE}:${IMAGE_TAG}"
kubectl apply -k .
```

这要求 Agent 上有 `kustomize` 命令，或使用具备 `kubectl kustomize` 的版本并把编辑好的 `kustomization.yaml` 写出来。overlay 是部署清单的正式形态；`sed` 只为让本文在没有额外目录时也能读完。

镜像构建若不能接受「构建机上的 Docker 守护进程」，把「构建并推送镜像」阶段换成 8.5 的 Kaniko 容器，tag 规则不变。

### 参数化的第二次部署

上面的流水线把「构建」和「部署生产」串在一次运行里，适合学习。镜像已经在 Harbor 之后，若只想重发同一个 sha 到生产（例如发布确认人当时不在，构建因 24 小时超时被中止），单独建一个流水线任务 `order-service-deploy`，定义放在同一仓库的 `deploy/Jenkinsfile`：

```groovy
pipeline {
    agent none
    parameters {
        string(name: 'IMAGE_TAG', description: '例如 sha-a1b2c3d4e5f6。必须已存在于 Harbor')
        choice(name: 'TARGET_ENV', choices: ['dev', 'prod'])
    }
    stages {
        stage('确认') {
            when { expression { params.TARGET_ENV == 'prod' } }
            options { timeout(time: 4, unit: 'HOURS') }
            input {
                message "重新部署 ${params.IMAGE_TAG} 到 prod？"
                ok '部署'
                submitter 'release-managers'
            }
            agent none
            steps { echo "确认 ${params.IMAGE_TAG}" }
        }
        stage('部署') {
            agent { label 'deploy && linux' }
            steps {
                lock(resource: "deploy-order-service-${params.TARGET_ENV}") {
                    withCredentials([file(
                        credentialsId: params.TARGET_ENV == 'prod' ? 'kubeconfig-prod' : 'kubeconfig-dev',
                        variable: 'KUBECONFIG'
                    )]) {
                        sh '''
                          test -n "$IMAGE_TAG"
                          case "$IMAGE_TAG" in
                            sha-*) ;;
                            *) echo "只接受 sha- 前缀的不可变 tag"; exit 1 ;;
                          esac
                          NS="$TARGET_ENV"
                          if [ "$TARGET_ENV" = "dev" ]; then NS=demo; fi
                          kubectl --kubeconfig "$KUBECONFIG" -n "$NS" set image \
                            deployment/order-service \
                            order-service="harbor.example.com/demo/order-service:$IMAGE_TAG"
                          kubectl --kubeconfig "$KUBECONFIG" -n "$NS" rollout status \
                            deployment/order-service --timeout=180s
                        '''
                    }
                }
            }
        }
    }
}
```

参数在流水线里是 `params.IMAGE_TAG`，在 `sh` 的环境里 Jenkins 会把参数同时放进环境变量 `IMAGE_TAG`。`case` 拒绝 `latest` 和空值。这个任务用手动触发，不接 webhook。

回滚就是再用这个任务部署上一个已知的 `sha-`。集群侧 `kubectl rollout undo` 也能回到上一个 ReplicaSet，但那次回滚不会更新你对「当前正式 sha」的记录。以再次部署一个明确的 sha 为回滚的主路径，`rollout undo` 留给故障当时的应急。

---

## 8.4 实践三：多分支流水线与合并请求

### 目标

`feature/*` 和合并请求：只跑「检出」和「单元测试」。`main`：跑 8.3 的全流程。不为人手每个分支建任务。

8.3 的 `Jenkinsfile` 已经用 `when { branch 'main'; not { changeRequest() } }` 把部署阶段挡住了。本节补的是 **Jenkins 任务和 Git 托管方** 的配置，否则仓库里的文件不会被扫描到。

### 在 Jenkins 上创建父任务

1. 若还没有文件夹：新建 Item → Folder，名称 `platform`。在该文件夹的凭据里添加 `kubeconfig-dev`、`kubeconfig-prod`（Secret file）。
2. 进入 `platform` → 新建 Item → 名称 `order-service` → **Multibranch Pipeline**。
3. Branch Sources → Add source → **GitHub**（GitLab 则选 GitLab）。
   - Credentials：`git-readonly`。这个账号对目标仓库有读取权限；若使用状态回写，还需要仓库的 commit status 权限。GitHub App 比个人访问令牌更容易轮换，条件允许时用 GitHub App 凭据类型。
   - Repository HTTPS URL：`https://github.com/example/order-service.git`。
   - Behaviors：
     - Discover branches，策略选「Exclude branches that are also filed as PRs」，避免同一个提交在分支和合并请求上各建一份完全重复的部署。若希望分支推送也构建，保留 Discover branches，名称过滤写 `main feature/* release/*`。
     - Discover pull requests from origin，策略选「Merging the pull request with the target branch」或「The current pull request revision」。前者在合并结果上构建，更接近合入后的样子；后者构建请求分支本身，更快。团队选一个并写进约定。
     - Filter by name with wildcards：`main feature/* release/*`。
4. Build Configuration → Mode：by Jenkinsfile，Script Path：`Jenkinsfile`。
5. Scan Multibranch Pipeline Triggers：
   - 勾选「Periodically if not otherwise run」，间隔 `15 minutes`（界面选项）或在底层表现为哈希后的周期。这是 webhook 的兜底。
   - 勾选 webhook 触发（GitHub 插件会监听 `/github-webhook/`）。
6. Orphaned Item Strategy：保留天数按磁盘情况，例如 7 天。分支删除后子任务不会永久堆积。
7. 保存。点击 **Scan Repository Now**。扫描日志里应出现 `main` 以及对 `Jenkinsfile` 的识别。

把开发者角色绑到这个文件夹：「系统管理 → Manage and Assign Roles」。全局角色 `developer` 只有 Overall/Read；项目角色模式 `platform/.*`，权限给 Job/Read、Job/Build、Job/Cancel。`release-managers` 组需要能读这个任务（否则 `input` 的提交人列表匹配不到他们）。

### 在 GitHub 上创建 webhook

仓库 → Settings → Webhooks → Add webhook：

| 项 | 值 |
|---|---|
| Payload URL | `https://jenkins.example.com/github-webhook/` |
| Content type | `application/json` |
| Secret | 与 Jenkins「系统管理 → System → GitHub → Advanced → Shared secret」一致。Secret 存在 Jenkins 凭据或 JCasC 的机密里 |
| 事件 | Just the push event 不够。选择 Let me select：Pushes、Pull requests |
| SSL | 校验证书。Jenkins 使用公司信任的证书 |

GitHub 的 Recent Deliveries 里应看到 200。若是 403，优先检查 URL 是不是 `/github-webhook/`、密钥是否一致、Jenkins 是否被匿名拒绝了这个端点（GitHub 插件的这个端点使用密钥校验，不使用用户登录）。

GitLab 的对应项：项目 → Webhooks，URL 使用 GitLab 插件文档给出的项目钩子（常见形态是 `https://jenkins.example.com/project/platform/order-service`），触发 Push events 与 Merge request events，Secret token 与 Jenkins 任务上的 token 一致。状态回写需要 Jenkins 里配置 GitLab 连接（API token，范围 `api` 或更小的状态权限，视插件版本而定）。

### 合并请求上应当发生的事

推送一个 `feature/pay` 并打开合并请求之后：

- 父任务扫描出子任务，名称类似 `PR-12` 或 `feature%2Fpay`（斜杠会被编码）；
- 子任务执行 `Jenkinsfile`，测试阶段运行，部署阶段在阶段视图里是跳过（Skipped）；
- GitHub 提交旁出现构建状态。这要求分支源使用的凭据具备 status 权限，并且安装了对应的状态发布行为（GitHub Branch Source 在配置了 GitHub 服务器时会回写）。

`main` 上的推送才进入镜像与部署阶段。保护 `main`：必须经过合并请求、必须状态检查通过、禁止强制推送。保护规则在 Git 托管方，不在 Jenkins。Jenkins 无法代替分支保护。

### 只想在「改过 Java 或部署清单」时构建

在测试阶段之外，整条流水线的跳过要用多分支的「例外」或 `when { changeset ... }`。变更集条件适合大仓库里的单模块：

```groovy
stage('单元测试') {
    when {
        anyOf {
            branch 'main'
            changeset 'src/**,pom.xml,Jenkinsfile'
        }
    }
    // ...
}
```

`changeset` 看的是这次构建相对上一次的差异。新分支的第一次构建往往没有「上一次」，条件可能不成立。新分支第一次用 `branch` 条件兜住，或接受第一次总是全量测试。

---

## 8.5 实践四：共享库与 Kubernetes 动态 Agent

### 目标

8.3 里「计算 SHA」和「部署某个命名空间」在第二个服务出现时会被复制。把它们放进共享库 `company-pipeline`。同时把 Maven 阶段的 `agent { label 'maven && linux' }` 换成一次性 Pod，使构建不再依赖预先准备的虚拟机。镜像构建改用 Kaniko，Pod 不挂接 Docker 套接字。

共享库的版本使用 tag `v1`，与 8.2 的 JCasC `defaultVersion: "v1"` 一致。改库时发 `v2`，业务仓库显式写 `@v2`，避免库的一次推送改变所有服务的行为。

### 库文件 `vars/computeShortSha.groovy`

```groovy
def call() {
    def sha = sh(script: 'git rev-parse --short=12 HEAD', returnStdout: true).trim()
    if (!(sha ==~ /[0-9a-f]{12}/)) {
        error "无法得到 12 位提交号，实际是: ${sha}"
    }
    return sha
}
```

### 库文件 `vars/deployService.groovy`

```groovy
// 用法：deployService(namespace: 'demo', kubeconfigId: 'kubeconfig-dev')
def call(Map cfg) {
    def image = "${env.IMAGE}:${env.IMAGE_TAG}"
    withCredentials([file(credentialsId: cfg.kubeconfigId, variable: 'KUBECONFIG')]) {
        sh """
          kubectl --kubeconfig "\$KUBECONFIG" -n '${cfg.namespace}' set image \
            deployment/order-service order-service='${image}'
          kubectl --kubeconfig "\$KUBECONFIG" -n '${cfg.namespace}' rollout status \
            deployment/order-service --timeout=180s
        """
    }
}
```

这里的 Groovy 双引号会把 `cfg.namespace` 和 `image` 插进脚本。它们来自仓库内的固定值和已经校验过的 `sha-` 前缀，不来自外部自由文本。kubeconfig 路径仍由 shell 读取环境变量（`\$` 把美元符号留给 shell）。若命名空间将来变成人工参数，先做白名单判断再插值。

### 业务 `Jenkinsfile` 里替换对应步骤

文件顶部：

```groovy
@Library('company-pipeline@v1') _
```

计算版本的 `script` 块改为：

```groovy
script {
    env.SHORT_SHA = computeShortSha()
    env.IMAGE_TAG = "sha-${env.SHORT_SHA}"
    currentBuild.description = env.IMAGE_TAG
}
```

开发部署阶段的 `withCredentials` / `kubectl` 换成：

```groovy
deployService(namespace: 'demo', kubeconfigId: 'kubeconfig-dev')
```

生产阶段在 `lock` 里面换成：

```groovy
deployService(namespace: 'prod', kubeconfigId: 'kubeconfig-prod')
```

阶段、`when`、`input`、`milestone` 仍留在业务 `Jenkinsfile` 里，打开服务仓库就能看见「会不会部署生产」。

### Pod 模板 `jenkins/agent-pod.yaml`（放在业务仓库）

```yaml
apiVersion: v1
kind: Pod
spec:
  restartPolicy: Never
  containers:
    - name: maven
      image: maven:3.9-eclipse-temurin-21
      command: ["cat"]
      tty: true
      resources:
        requests:
          cpu: "1"
          memory: "2Gi"
        limits:
          memory: "4Gi"
      volumeMounts:
        - name: m2
          mountPath: /root/.m2
    - name: kaniko
      image: gcr.io/kaniko-project/executor:v1.23.2
      command: ["cat"]
      tty: true
      volumeMounts:
        - name: docker-config
          mountPath: /kaniko/.docker
  volumes:
    - name: m2
      persistentVolumeClaim:
        claimName: maven-repo-cache
    - name: docker-config
      secret:
        secretName: harbor-docker-config
        items:
          - key: .dockerconfigjson
            path: config.json
```

`harbor-docker-config` 是命名空间里的 `kubernetes.io/dockerconfigjson` Secret，只包含推送 `demo` 项目的机器人账号。`maven-repo-cache` 是一个 ReadWriteMany 或按构建节点亲和的 PVC，用来保留 Maven 本地仓库。做不到共享卷时，删掉这个 volume，接受每次 Pod 重新下载依赖，或在集群里跑一个 Nexus 代理作为唯一的远程仓库。

Kubernetes 插件会再注入一个跑 remoting 的容器（常见名字 `jnlp`）。模板里不必手写，除非你要固定 remoting 镜像版本。

### 流水线中的 Agent 改写

测试阶段：

```groovy
stage('单元测试') {
    agent {
        kubernetes {
            defaultContainer 'maven'
            yamlFile 'jenkins/agent-pod.yaml'
        }
    }
    steps {
        sh 'mvn -B -ntp -Drevision=1.0.0+${IMAGE_TAG} test'
    }
    post {
        always {
            junit allowEmptyResults: false, testResults: '**/target/surefire-reports/*.xml'
        }
    }
}
```

镜像阶段不再使用 `label 'docker'`：

```groovy
stage('构建并推送镜像') {
    when {
        beforeAgent true
        allOf {
            branch 'main'
            not { changeRequest() }
        }
    }
    agent {
        kubernetes {
            defaultContainer 'maven'
            yamlFile 'jenkins/agent-pod.yaml'
        }
    }
    steps {
        sh 'mvn -B -ntp -Drevision=1.0.0+${IMAGE_TAG} -DskipTests package'
        container('kaniko') {
            sh '''
              /kaniko/executor \
                --context="${WORKSPACE}" \
                --dockerfile="${WORKSPACE}/Dockerfile" \
                --destination="${IMAGE}:${IMAGE_TAG}" \
                --snapshot-mode=redo
            '''
        }
    }
}
```

`--snapshot-mode=redo` 避免 Kaniko 在某些文件系统上检测不到改动。目的仓库由镜像内的 `/kaniko/.docker/config.json` 认证。日志里确认 destination 是 `sha-` 而不是 `latest`。

部署阶段仍可以使用 `label 'deploy'` 的固定节点（那台机器的网络到达 API Server，且没有编译工具）。也可以给 Pod 增加一个只含 `kubectl` 的容器，部署阶段 `container('kubectl')` 执行 8.3 的命令。kubectl 镜像的入口同样用 `cat` 盖住。

### Controller 侧要先有的 Kubernetes 云

「系统管理 → Clouds → New cloud → Kubernetes」：

| 项 | 值 |
|---|---|
| 名称 | `kubernetes` |
| Kubernetes URL | 集群内用 `https://kubernetes.default.svc`；Controller 在集群外则填 API 地址并提供凭据 |
| 凭据 | 集群内可留空，使用 Controller Pod 的 ServiceAccount；集群外用 kubeconfig 凭据，权限限于创建 Agent Pod |
| Jenkins URL | `https://jenkins.example.com/`，Pod 必须能访问这个地址以连回 Remoting |
| Jenkins tunnel | 若 Agent 走独立端口，填 `jenkins.example.com:50000`。WebSocket 方式下按插件选项使用 Jenkins URL |

用插件的「连接测试」确认 API 可达，再跑一条只含 `agent { kubernetes { ... } }` 和 `sh 'mvn -version'` 的流水线。Pod 能创建、日志能回到 Jenkins、构建结束 Pod 被删除，这三件事成立之后，再把 8.3 的阶段迁过来。

失败时先看两处：Jenkins 构建日志里插件打印的 Pod 事件，以及 `kubectl -n jenkins describe pod` 里镜像拉取和 ServiceAccount 的报错。多数第一次失败是 RBAC 缺少 `pods/exec`，或 Jenkins URL 从 Pod 网络不可达。

### 共享库的发布方式

库仓库保护 `main`。平台组打 annotated tag `v1`、`v2`。业务仓库的 `@Library` 行是普通代码，改版本要经过该服务的评审。库里的函数保持向后兼容：新参数有默认值，确认没有调用方后再删旧参数。

### 本章小结

四套配置叠在一起是一条完整的交付链：Controller 由镜像和 YAML 重建，业务步骤在 `Jenkinsfile`，分支策略靠多分支任务和 `when`，重复步骤和构建环境分别进共享库与 Pod 模板。产物的身份始终是 `sha-` 前缀的提交号。

---

# 九、贯通：从一次 push 到生产环境

把一条 `main` 上的提交按时间摊开。括号里是负责的部件。

1. 开发者把合并请求合入 `main`（Git 托管方，分支保护已要求检查通过）。
2. Webhook POST 到 `/github-webhook/`（GitHub → Controller）。
3. 多分支父任务发现 `main` 有新提交，触发子任务（Controller 排队）。
4. 子任务进入队列，直到有 `maven` 标签的执行器或集群能拉起 Pod（队列）。
5. Agent 创建工作区，检出这一次提交（Agent）。
6. `git rev-parse` 得到 `SHORT_SHA`，描述被写成 `sha-xxxxxxxxxxxx`（Agent + 构建记录）。
7. `mvn test`。失败则 `junit` 仍发布报告，流水线变红，后面的阶段不执行，生产环境保持原镜像（Agent）。
8. `main` 上 Sonar 分析完成，SonarQube 通过 webhook 把质量门禁结果送回，`waitForQualityGate` 放行（SonarQube → Controller）。
9. `mvn deploy` 把 `1.0.0+sha-...` 写入 Nexus（Agent → Nexus）。
10. Kaniko 或 Docker 把镜像推到 Harbor 的同一个 `sha-`。仓库策略拒绝覆盖这个 tag（Agent → Harbor）。
11. 开发命名空间的 Deployment 被更新并等到 `rollout status` 成功（Agent → 集群）。
12. 流水线在「确认生产发布」停下，不占用构建机。发布负责人打开 `BUILD_URL`，看到的 tag 与开发环境正在跑的一致，点击发布（Controller）。
13. `milestone` 与 `lock` 之后，生产命名空间更新为同一个 tag，并等待滚动完成（Agent → 集群）。
14. 构建结果写成成功。30 个编号以前的日志按 `buildDiscarder` 丢弃。Harbor 和 Nexus 里的那个 sha 还在（Controller 的丢弃策略管不到制品库，这是故意的）。

合并请求在第 7 步之后结束：部署阶段被 `when` 跳过，状态写回合并请求。

若第 13 步超时失败，镜像仍在 Harbor。用 8.3 的参数化任务把同一个 `IMAGE_TAG` 再部署一次，或从失败构建的「部署生产环境」阶段重新运行。不需要从 `mvn test` 重新开始，除非你怀疑检出的内容本身变了。

### 四个设计在这条时间线上的位置

| 设计 | 它卡在第几步 |
|---|---|
| 流水线即代码 | 第 5 步取出的 `Jenkinsfile` 就是本次提交的那一份，和被编译的源码匹配 |
| 控制面 / 执行面 | 第 4 步 Controller 只排队；第 7 步起的进程在 Agent |
| 凭据与秘密分离 | 第 9、10、11、13 步的密码和 kubeconfig 按 ID 注入，仓库里只有 ID |
| 不可变产物晋级 | 第 10 步产生的 tag 被第 11 步和第 13 步共同引用 |

---

# 附录

## 附录 A：术语速查

| 中文 | 英文 | 不要再使用的旧称 |
|---|---|---|
| 控制节点 | Controller | Master |
| 执行节点 / Agent | Agent | Slave |
| 内置节点 | Built-in Node，标签 `built-in` | `master` 标签 |
| 执行器 | Executor | — |
| 流水线 | Pipeline | — |
| 多分支流水线 | Multibranch Pipeline | — |
| 阶段 / 步骤 | Stage / Step | — |
| 凭据 | Credentials | — |
| 工作区 | Workspace | — |
| 配置即代码 | Configuration as Code，JCasC | — |
| 共享库 | Shared Library | — |
| 自由风格任务 | Freestyle project | 新任务不要再选它 |
| 不稳定 | Unstable | 和失败（Failure）是两种结果 |
| 质量门禁 | Quality Gate | — |

## 附录 B：界面路径速查

| 想做的事 | 路径 |
|---|---|
| 看排队 | 首页左侧 Build Queue |
| 把内置节点执行器改为 0 | 系统管理 → Nodes → Built-In Node → 配置 → Number of executors |
| 添加静态 Agent | 系统管理 → Nodes → New Node |
| 添加全局凭据 | 系统管理 → Credentials → System → Global → Add Credentials |
| 添加只有某组任务能用的凭据 | 打开 Folder → Credentials |
| 装插件 | 系统管理 → Plugins |
| 重载 JCasC | 系统管理 → Configuration as Code → Reload |
| 导出当前系统配置 | 同一页的 View Configuration |
| 批准沙箱外的方法签名 | 系统管理 → In-process Script Approval |
| 登记互斥锁 | 系统管理 → Lockable Resources |
| 看测试失败 | 打开某次构建 → Test Result |
| 临时改脚本重跑 | 打开某次构建 → Replay |
| 从部署阶段重跑 | 打开某次构建 → Restart from Stage |

菜单的中文安装可能显示「系统管理」「凭据」「节点」。英文界面是 Manage Jenkins、Credentials、Nodes。

## 附录 C：初学者的动手顺序

按这个顺序，每一项都能在一天内看到结果。不要从 Kubernetes 云开始。

1. 用 8.2 的 Compose 把 Controller 跑起来，登录，确认执行器为 0。本机细节以 `Jenkins学习方案.md` 为准。
2. 新建一个流水线任务，定义选「Pipeline script」，把第四章最短的声明式例子贴进去，指定一台你能用的 Agent（练习环境若暂时只有内置节点，仅为这一次把执行器调成 1，做完改回 0，并在笔记里写明这是练习妥协）。
3. 把同一份脚本移进一个 Git 仓库的 `Jenkinsfile`，任务改为 Pipeline script from SCM。之后只改 Git，不改任务里的脚本文本框。
4. 加上 `junit` 与一次故意失败的测试，确认构建变红且网页能看见用例。
5. 加上凭据和一个 `withCredentials` 块，确认日志里密码是掩码，且 Groovy 使用了单引号。
6. 改成多分支父任务，开一个合并请求，看见测试跑了、部署阶段跳过了。
7. 按 8.3 接 Nexus 或 Harbor 的一个。先只推 `sha-` 镜像，先不部署。
8. 最后接开发环境的 `kubectl`，再接生产 `input`。
9. 第二个服务出现时，再抽共享库，再评估 Kubernetes 动态 Agent。

和云效、GitLab CI 的取舍不在这条动手顺序里，见 `云效与自建GitLab+Jenkins对比.md`。

## 附录 D：排障时先看哪

| 现象 | 先看 |
|---|---|
| 队列里一直等待 | 节点是否在线、标签是否包含流水线要求的全部标签、执行器是否被占满、是否写了 `agent any` 但内置节点执行器为 0 |
| 立刻失败在 `script approval` | 沙箱拒绝了某个方法。把逻辑改成 `sh`，或由管理员批准签名。共享库可以承担受信代码 |
| `NotSerializableException` | `script` 块里保存了不能序列化的对象。把对象局限在一个方法内，返回字符串 |
| 测试失败但构建是绿的 | 命令被 `|| true` 吃掉了退出码，或测试步骤不在 `sh` 里。让构建工具自己返回非 0 |
| 测试失败且没有报告 | `junit` 写在失败就会跳过的 `steps` 里。挪到 `post { always }` |
| `waitForQualityGate` 超时 | SonarQube webhook 没到 Jenkins，或密钥不一致 |
| 镜像阶段登录失败 | 凭据 ID、机器人账号权限、Harbor 项目名。日志里的用户名应当可见，密码应当是掩码 |
| 部署把未知版本发出去 | tag 来自 `latest` 或本地 `docker build`。改为只接受 `sha-` |
| 审批把 Agent 占了几小时 | `input` 写进了已经分配 `agent` 的 `steps`。改成 stage 级 `input`，该 stage `agent none` |
| 旧构建后完成，生产版本回退 | 补上 `disableConcurrentBuilds`、`milestone` 和 `lock` |
| 重启后配置丢了 | 改动只做在界面上，且 `JENKINS_HOME` 没有卷；或 JCasC 热加载用仓库里的旧 YAML 覆盖了界面改动 |
| 凭据在新实例上解不开 | 恢复时没有带上 `JENKINS_HOME/secrets` |

## 附录 E：官方入口

| 内容 | 地址 |
|---|---|
| 文档首页 | https://www.jenkins.io/doc/ |
| 流水线语法（步骤参考，以你的实例为准） | 任意流水线任务页 → Pipeline Syntax。这是最准确的步骤表，因为它反映已安装插件 |
| 声明式语法 | https://www.jenkins.io/doc/book/pipeline/syntax/ |
| LTS 更新日志 | https://www.jenkins.io/changelog-stable/ |
| Java 支持策略 | https://www.jenkins.io/doc/book/platform-information/support-policy-java/ |
| 升级指南（按目标 LTS 选择，例如 2.555） | https://www.jenkins.io/doc/upgrade-guide/ |
| JCasC | https://www.jenkins.io/doc/book/managing/casc/ |
| 共享库 | https://www.jenkins.io/doc/book/pipeline/shared-libraries/ |
| 安全公告 | https://www.jenkins.io/security/advisories/ |
| Kubernetes 插件 | https://plugins.jenkins.io/kubernetes/ |
| 本机练习方案 | 同目录 `Jenkins学习方案.md` |

实例里的 **Pipeline Syntax → Snippet Generator** 能根据表单生成 `sh`、`withCredentials`、`checkout` 的片段。生成结果贴进 `Jenkinsfile` 后仍要按第四章的引号规则看一遍。

## 结语

Jenkins 能长期用下去，靠的不是把所有插件装齐，而是把边界守住：步骤在 Git 里，秘密在凭据库里，编译在 Agent 上，发布物在制品库里，环境只消费一个写得出来的版本号。界面会改版，插件会更替，这五条仍然是在读任何一台陌生 Jenkins 时的检查清单。
