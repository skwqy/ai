# 自建 GitLab+Jenkins 深度解析与云效正面对比（写给初学者的两条路线全景）

> **资料基准**：本文是《云效平台.md》的姊妹篇——上一份回答"云效有什么"，这一份回答"自建 GitLab+Jenkins 到底是什么、要付出什么，以及两条路线怎么选"。GitLab/Jenkins 侧数据基于官方文档与公开资料（检索日期 **2026-10-11**）：Jenkins 最新 LTS 2.568.x（需 Java 21/25）、插件 1800+；GitLab 许可 Free/Premium（$29/人/月）/Ultimate（约 $99/人/月，商务价）；GitLab 单机生产推荐 8 vCPU/16GB（≤500 用户）。版本与价格随时间变化，落地前以官方为准。
>
> **阅读约定**：第二、三、四章是本文的重点——把"自建"这条路线拆开揉碎讲透（GitLab 本体、Jenkins 本体、两者粘合 + 补齐全家桶还缺什么）；第五章才是与云效的正面对比；第六章给选型决策。每章仍按"定位 → 展开 → 本章小结"的节奏写。

## 如何读这份文档

- **第一遍（建立判断，约 30 分钟）**：读第一章总览 + 第二、三章的"架构一图"和小结 + 第五章的对比表 + 第六章决策表。读完应能回答：自建一套到底要部署哪些东西、养一套每月花多少人力？两条路线的本质差异是什么？
- **第二遍（按需深入）**：准备真的自建——按第二、三、四章逐节核对（规格、插件清单、备份策略、集成方式）；正在做选型汇报——直接取第五章表格和第六章决策树。

---

# 一、总览：两条研发工具链路线的对比框架

## 1.1 一句话定位

- **云效路线**：花钱买"别人养好的平台"——SaaS 订阅，免搭建免运维，功能全家桶开箱即用，定制受限于产品边界。
- **自建 GitLab+Jenkins 路线**：花人养"自己的平台"——代码托管用 GitLab、CI/CD 用 Jenkins（通常再配上 Nexus、SonarQube 等），开源免费、完全可控，代价是部署、升级、安全、高可用全部自己扛。

**本质区别一句话**：云效把"运维成本"折算进订阅费；自建把"订阅费"折算成自己的运维人力。没有免费午餐，只有成本形态不同。

## 1.2 对比框架：六个评价维度

本文后续所有对比都按这六个维度展开：

| 维度 | 回答的问题 |
|---|---|
| 功能覆盖 | 研发全链路的每个环节，谁来做？ |
| 成本（TCO） | 显性成本（服务器/许可）+ 隐性成本（人力）各多少？ |
| 运维负担 | 谁负责可用性、备份、升级、故障恢复？ |
| 安全合规 | 谁来通过等保/审计？数据放在谁的机房？ |
| 自由度与生态 | 定制空间多大？被什么绑定？退出成本多大？ |
| AI 能力 | 2026 年的 AI 研发能力，两条路线各是什么水平？ |

## 1.3 全文章节地图

| 章节 | 内容 | 一句话 |
|---|---|---|
| 一 | 总览 | 两条路线、六个维度 |
| 二 | 自建 GitLab 解析 | 代码托管底座：架构、部署、规格、许可、运维 |
| 三 | 自建 Jenkins 解析 | CI/CD 引擎：架构、插件、Pipeline、运维、痛点 |
| 四 | 粘合与补齐 | GitLab×Jenkins 集成 + 自建"全家桶"完整清单 |
| 五 | 正面对比 | 与云效按六维度逐项对比 |
| 六 | 选型决策 | 决策树、混合路线、迁移路径 |
| 附录 | 实操材料 | Jenkinsfile 完整示例、GitLab 部署规格、模块映射表 |

---

# 二、自建 GitLab 深度解析：代码托管底座

## 2.1 GitLab 是什么

GitLab 是全球最主流的**一体化 DevOps 开源平台**（2008 年起源），一个产品里装下了：Git 代码托管、MR 评审、issue/看板、Wiki、内置 CI/CD（GitLab CI）、内置容器镜像仓库（Container Registry）。发行方式是**双轨制**：

- **社区版 CE（Community Edition）**：开源免费（MIT-like 许可），自建的主力选择；
- **企业版 EE（Enterprise Edition）**：同一代码库，包含闭源企业功能，按订阅收费，分 Premium / Ultimate 两档（见 2.5）。

对自建团队来说，GitLab 通常同时承担"代码仓库 + 评审平台 + issue 管理"三个角色——这也是它和云效 Codeup 直接对位的原因。

## 2.2 架构与组件：一个"单体 + 微服务拼盘"

GitLab 核心是 **Ruby on Rails 单体应用**，辅以多个 Go/独立组件。单机 Omnibus 部署时全部打包在一起，看起来是一个进程集；规模化时可以逐个拆出：

```
                    用户 / git 客户端 / CI Runner
                       │ HTTP(S)          │ SSH
                       ▼                  ▼
                ┌── Workhorse（Go 反向代理，静态/大文件直通）──┐
                │                      │                     │
                ▼                      ▼                     ▼
        Rails 应用（Puma）        GitLab Shell            Container Registry
        网页/API/权限/评审        SSH Git 操作            （镜像存储，走对象存储）
                │                      │                     │
                │              ┌───────┴───────┐             │
                ▼              ▼               ▼             │
          PostgreSQL        Redis          Sidekiq          │
          （元数据/          （缓存/         （后台任务：       │
           issue/MR）        队列/会话）      邮件/webhook）    │
                                   │                       │
                                   ▼                       ▼
                              Gitaly（Go，Git 仓库读写 RPC 服务，真正的代码存储）
                                   │
                                   ▼
                          仓库文件（推荐放 NFS/对象存储，便于扩容与备份）
```

| 组件 | 语言/角色 | 运维关注点 |
|---|---|---|
| Rails（Puma） | Ruby，Web/API 核心 | 吃内存大户，内存不足时最先 OOM |
| Gitaly | Go，Git RPC | 仓库 I/O 瓶颈所在；大仓库多时单独扩 |
| PostgreSQL | 数据库 | 升级时要做数据库迁移（停机主因） |
| Redis | 缓存/队列 | Sidekiq 任务堆积时先看它 |
| Sidekiq | 后台任务 | webhook 派发、邮件、hooks 执行 |
| Workhorse | Go 代理 | 一般无感，出问题多是 TLS/大文件 |
| GitLab Shell | SSH 接入 | 内网 22 端口暴露面管理 |

> 单机部署时这些组件由 Omnibus 统一管理（`gitlab-ctl` 命令族），运维者不需要逐个面对——但**排查性能问题时必须知道是谁慢**。

## 2.3 部署形态与硬件规格

**三种主流部署方式**：

1. **Omnibus 官方 Linux 包**（推荐起步）：一条命令装齐所有组件，内置 Nginx/PostgreSQL/Redis/Prometheus，`gitlab.rb` 单文件配置；
2. **Docker / docker-compose**：官方镜像，适合试验环境与小团队；
3. **Helm Chart / Operator 上 K8s**：适合已有 K8s 平台的团队，但组件拆分复杂度高，不建议首次自建就上。

**硬件规格（官方参考架构口径，2026-10）**：

| 规模 | 推荐规格 | 说明 |
|---|---|---|
| 试验/极小团队（≤20 人） | 4 vCPU / 4~8 GB RAM | 4GB 是能跑起来的下限，体验一般 |
| 正式生产（≤500 用户） | **8 vCPU / 16 GB RAM** 单机 | 官方参考架构起点，无高可用 |
| 1000 用户 / 20 RPS | 仍可单机，但建议开始拆分 Gitaly/DB | 关注备份与升级窗口 |
| 数千用户以上 | 多节点拆分（Gitaly 集群 Praefect、PgBouncer、多 Redis） | 复杂度陡增，需要专职运维 |

**存储估算**（常被低估）：仓库本体 + LFS 大文件 + Container Registry 镜像 + CI 产物 + **备份副本（×1~2）**。中型团队实际磁盘需求通常是裸仓库大小的 3~5 倍。

## 2.4 核心能力盘点

- **代码托管**：Git 仓库、保护分支、强制签名提交、LFS 大文件、子模块、镜像仓库导入；
- **MR 评审**：diff 评审、行内评论、多级批准（部分属 Premium）、评审进度看板；
- **issue / 看板 / 里程碑**：轻量项目管理够用，但离专业研发管理（迭代/燃尽/需求追溯）有距离——很多团队 issue 只当"备忘录"用，正经项目管理仍配 Jira/TAPD；
- **内置 GitLab CI**：仓库根目录 `.gitlab-ci.yml` 声明流水线，自建 Runner 执行——**这是很多人放弃 Jenkins 直接用 GitLab CI 的原因**（少养一个系统）；
- **内置 Container Registry**：镜像直接推 `registry.gitlab.example.com/组/项目`，省一个 Harbor（轻量场景）；
- **Wiki**：每项目自带 markdown wiki（够用但不如 Confluence）；
- **权限与身份**：角色五级（Guest/Reporter/Developer/Maintainer/Owner）、组级继承；LDAP/OAuth2/SAML 单点登录（部分 EE 功能）；
- **审计**（EE）：审计事件、push rules、外发邮件限制等。

## 2.5 版本许可与收费

| 档位 | 价格（2026 口径） | 关键差异 |
|---|---|---|
| **Free（CE/免费 EE）** | 0 元 | 代码托管、MR、CI、Registry、issue 全都有——自建 80% 团队停在这档 |
| **Premium** | **$29/人/月（$348/人/年）** | 多级批准、push rules、LDAP 组同步、**Geo 灾备**、更强的 CI 配额 |
| **Ultimate** | 约 $99/人/月（商务合同价） | SAST/DAST/依赖/密钥扫描、合规管理、组合管理 |

> 一个常被忽略的对比结论：**GitLab Premium（$348/人/年 ≈ ¥2500/人/年）比云效高级版（¥618/人/年）贵 4 倍**。自建如果停留在 CE 免费档，许可成本确实为零；但一旦需要企业功能（多级批准、Geo），自建反而比云效贵。

## 2.6 运维要点：自建 GitLab 的真实账单

1. **升级跑步机（最大长期负担）**：GitLab 每月 22 日发新版，安全补丁频繁；升级必须**按官方要求的路径逐级跨版本**（不能任意跳级），大版本伴随数据库迁移，中型实例迁移可能数小时。不升级 = 带着已知 CVE 裸奔。
2. **备份与恢复**：`gitlab-backup` 备份仓库/DB/附件，但 **`/etc/gitlab/gitlab-secrets.json` 必须单独保管**——丢了它，CI 变量、2FA、Token 全部解不开，等于灾难。建议：定期备份到异机/对象存储 + 每季度做一次真实恢复演练。
3. **监控**：Omnibus 自带 Prometheus/Grafana 裸导出，重点盯：内存（Rails OOM）、Sidekiq 队列深度、Gitaly 延迟、磁盘水位。
4. **高可用现实**：单机没有 HA，宕机全员停摆；真 HA（Gitaly 集群 + 多 DB）复杂度和成本翻几倍——**绝大多数自建团队的实际形态是"单机 + 每日备份 + 接受数小时 RTO"**，这一点和云效的多副本高可用是硬差距。
5. **网络**：只放内网/VPN，绝不做公网直暴露。

## 2.7 安全运营

- GitLab 历史 CVE 较频繁（曾出现 ExifTool RCE（CVE-2021-22205）、多次权限绕过），**"装完不管"的自建 GitLab 是内网高危资产**；
- 安全基线：紧跟安全补丁版、关闭开放注册、强制 2FA、SSH key 算法策略、定期审计 token/Deploy Key；
- 自建的账：这些动作每月大约消耗 **0.1~0.3 个人日**，由谁来干要在立项时写明。

## 2.8 本章小结

GitLab 是自建路线里"性价比最高的单体"：一个 8C16G 虚机就能获得托管 + 评审 + CI + 镜像仓库的全套底座，CE 免费档功能完整度高。它的真实代价藏在三处：**升级跑步机、备份恢复纪律、单机无高可用**。它是"代码资产的保险柜"这个角色上的合格自建替代品，但保险柜的看门人是你自己。

---

# 三、自建 Jenkins 深度解析：CI/CD 引擎

## 3.1 Jenkins 是什么

Jenkins（前身 Hudson，2004 年起）是最老牌、应用最广的**开源自动化服务器**，Java 编写，本质是一个"插件化任务编排引擎"。2026-10 时点：最新 LTS 为 **2.568.x**，**要求 Java 21 或 25**（Java 17 支持已于 2026 年初移除——这是它持续演进的缩影：不跟进升级就会掉队）。发布节奏双轨：Weekly（激进）与 **LTS（生产推荐）**。

## 3.2 架构：Controller + Agent

```
                    ┌────────────────────────────────────┐
                    │        Controller（主控节点）        │
                    │  Web UI / 任务调度 / 凭据 / 插件运行时  │
                    │  JENKINS_HOME：config.xml、jobs/、   │
                    │  plugins/、credentials/、nodes/      │
                    └───────┬──────────────┬──────────────┘
                            │ 分发构建（JNLP/SSH） │
              ┌─────────────┼─────────────┐
              ▼             ▼             ▼
        ┌──────────┐  ┌──────────┐  ┌──────────┐
        │ Agent 1  │  │ Agent 2  │  │ 动态 Agent │
        │ 常驻虚机  │  │ 常驻虚机  │  │ K8s Pod/  │
        │ label:java│ │ label:node│ │ Docker 容器│
        └──────────┘  └──────────┘  └──────────┘
              构建在此执行：checkout → 构建 → 测试 → 打包 → 部署
```

两条铁律：

- **Controller 只调度不构建**：构建全部推给 Agent，Controller 保持轻量稳定（它一挂全线停摆，构建是最大不稳定源）；
- **JENKINS_HOME 是唯一状态**：配置、任务、凭据、插件全在这一个目录——备份它就备份了一切（凭据用 Hudson 密钥加密，恢复必须连密钥一起带走）。

## 3.3 插件生态：最强的武器，也是最大的坑

更新中心共有 **1800+ 插件**，这是 Jenkins 二十年来长盛不衰的原因。Java 团队的典型插件清单：

| 类别 | 插件 | 用途 |
|---|---|---|
| 源码 | Git / GitLab | 拉代码、收 webhook、回写 MR 状态 |
| 构建 | Maven / Gradle / Pipeline | Java 构建与流水线 |
| 测试 | JUnit / JaCoCo / HTML Publisher | 单测报告、覆盖率门禁 |
| 质量 | SonarQube Scanner / Warnings NG | 静态扫描、告警聚合 |
| 容器 | Docker Pipeline / Kubernetes | 镜像构建、K8s 动态 Agent |
| 部署 | Kubernetes CLI / Ansible / SSH | 发布到集群/主机 |
| 平台 | Credentials / LDAP / Role-based Auth | 凭据、身份、权限 |
| 协同 | DingTalk / Slack / Email Extension | 通知触达 |
| 治理 | Configuration as Code（JCasC） | 把配置写成 YAML，治"雪花配置" |

**插件地狱（Plugin Hell）是 Jenkins 的头号痛点**：插件间依赖冲突、升级后行为变化、停更插件无人维护、插件 CVE 频发（Jenkins 每月发布安全公告，多数与插件有关）。纪律做法：**插件最小化**（按需装）、版本锁定、先在灰度 Jenkins 验证再升级生产。

## 3.4 Pipeline 即代码：Jenkinsfile

现代 Jenkins 的正确用法是 **Pipeline as Code**：流水线定义放在仓库的 `Jenkinsfile` 里，随代码走、可评审、可回溯。两种语法：

- **Declarative（声明式）**：结构化的 `pipeline { stages { ... } }`，推荐默认；
- **Scripted（脚本式）**：纯 Groovy，灵活但难维护，留给复杂场景。

公共逻辑下沉到 **Shared Library**（共享库），多项目复用标准流水线骨架——这是 Jenkins 企业级治理的核心手法。完整 Spring Boot 示例见附录 A。

## 3.5 部署与容量

| 项 | 建议 |
|---|---|
| Controller 起步规格 | 4 vCPU / 4 GB RAM（JVM 堆显式设 `-Xmx`），经验公式：**~200 MB/executor + ~500 MB 基础** |
| 存放方式 | 虚机 WAR/Docker 起步；有 K8s 用 **Helm + K8s 动态 Agent**（构建资源随负载伸缩，最优雅） |
| 构建机 | SSD 必须；构建工作区定期清理；产物进 Nexus 不落 Controller |
| 扩容 | 加 Agent 即可横向扩，Controller 本身无原生集群/HA（见 3.6） |

## 3.6 运维要点与已知痛点（自建 Jenkins 的真实账单）

1. **无真正高可用**：Controller 官方就是单点，主流做法是"JCasC + 备份 + 快速重建"把 RTO 压到小时级，而不是追求双活；
2. **升级纪律**：Jenkins 核心每 LTS（约每季）+ 插件持续——**每月至少 0.2~0.5 个人日**耗在"读安全公告 → 灰度验证 → 升级"上；
3. **配置漂移**：不用 JCasC 的 Jenkins 半年后一定变成没人敢动的"雪花机"，人一离职知识断档；
4. **磁盘治理**：构建工作区、旧日志、旧产物三大磁盘杀手，要配自动清理策略；
5. **凭据安全**：Credentials 插件是底座，但 `sh` 里打印环境变量就可能泄漏凭据——严格用 `withCredentials` 作用域 + 日志脱敏 + 审计；
6. **人是最贵部件**：DevOps 工程师写 Groovy、调插件、修 Controller 的隐性工时，是新团队最普遍的低估项（详见 5.2 的 TCO 测算）。

## 3.7 安全运营

- 攻击面几乎全部来自**插件**（每月安全公告常见插件 RCE/权限绕过）；
- Controller 不做公网暴露，匿名访问关闭，矩阵/角色授权最小化；
- Groovy 沙箱（Script Security）是双刃剑：管理员写的流水线等于**在 Controller 上跑任意代码**，Jenkinsfile 的评审要当生产变更对待。

## 3.8 本章小结

Jenkins 是"没有边界"的 CI 引擎：任何语言、任何环境、任何奇葩流程都能编排，免费且永不绑架。代价是**它是工具而不是产品**：没有官方 SLA、没有开箱的企业治理，可用性、安全、治理全部靠团队的工程纪律。评价它的一句话：**小团队会觉得它比云效 Flow 麻烦，大团队会觉得它比谁都灵活——中间团队最痛苦**。

---

# 四、粘合与补齐：自建链路的完整拼图

## 4.1 GitLab × Jenkins 怎么集成

最常见组合是"**GitLab 管代码与评审，Jenkins 管构建发布**"：

1. 在 GitLab 项目设置 **Webhook**（push / merge request / tag 事件）→ 指向 Jenkins；
2. Jenkins 装官方 **GitLab 插件**，配置 API Token（OAuth 或 Personal Access Token）；
3. Jenkins 构建结束后把状态（成功/失败）**回写 MR**，GitLab MR 页面直接看 CI 结果；
4. 凭据按"Jenkins 项目级"存放 GitLab Token，权限最小化。

> **要不要直接用 GitLab CI 替代 Jenkins？** 这是自建圈的经典问题。GitLab CI 的优势：`.gitlab-ci.yml` 与仓库同源、不用再养一个系统、UI 一体；Jenkins 的优势：插件面更广（老牌私有部署环境、异构 Agent、复杂审批编排）、团队存量资产多。**新自建项目如果只服务 GitLab，可先试 GitLab CI；存量 Jenkins 资产重或编排复杂，保留 Jenkins。** 两条路都成立，别为了"消灭 Jenkins"而强行迁移。

## 4.2 补齐全家桶：GitLab+Jenkins 只是起点

对照云效的全家桶，GitLab+Jenkins 只覆盖了"代码 + CI"两格。完整自建路线的真实清单是：

| 云效模块 | 对应能力 | 自建组件（典型选择） | 额外运维量 |
|---|---|---|---|
| Codeup（代码/评审） | 托管 + 评审 + 检测 | **GitLab** + SonarQube（质量扫描） | SonarQube 一台 + 升级 |
| Flow（CI/CD） | 流水线 | **Jenkins**（或 GitLab CI） | 见第三章 |
| Packages（制品） | Maven/npm 私服 | **Nexus 3 / Artifactory** | 一台 + 磁盘治理 |
| —（镜像仓库） | 容器镜像 | **Harbor**（或 GitLab 内置 Registry） | Harbor 需要单独维护 |
| Projex（项目管理） | 需求/迭代/缺陷 | **Jira / TAPD / CODING**（或 GitLab issue 凑合） | 多一个 SaaS 或自建 |
| Testhub（测试管理） | 用例库/测试计划 | **TestRail / TestLink**（或 Excel→平台化） | 常被省略，质量闭环断点 |
| Insight（效能度量） | 效能看板 | **自建数据链：API 采集 + ELK/ClickHouse + Grafana** | 开发 + 长期迭代，成本最高 |
| Thoughts（知识库） | 文档协作 | **Confluence / 语雀 / 开源 wiki.js** | 又一个系统 |
| 身份与权限 | SSO/组织 | **LDAP / Keycloak** | 集成开发量 |
| 消息通知 | 钉钉触达 | 各组件各自配钉钉机器人 | 碎 |

**这就是自建的隐藏成本公式**：`GitLab+Jenkins` 是 2 个系统，"对标云效全家桶"是 **8~10 个系统**，每一个都要部署、升级、备份、排障。自建团队实践中最常见的妥协是只维护 GitLab+Jenkins+Nexus 三件套，放弃测试管理与效能度量——而这恰恰是云效全家桶用 SaaS 顺手补齐的部分。

## 4.3 本章小结

自建不是"装两个软件"，而是**组装并养护一条 8~10 件工具的工具链**，组件之间的粘合（webhook、凭据、状态回写、数据打通）全是自己写、自己修。GitLab+Jenkins 集成本身不难（官方插件成熟），难的是让整条链路像云效那样"天然是一体的"。

---

# 五、云效 vs 自建 GitLab+Jenkins：正面对比

## 5.1 功能覆盖与开箱度

| 能力 | 云效 | 自建 GitLab+Jenkins（+补齐件） |
|---|---|---|
| 代码托管/评审 | Codeup 开箱 | GitLab 开箱（CE 免费档够用） |
| CI/CD | Flow 可视化编排，模板化 | Jenkins 灵活但全要写 Jenkinsfile/配插件 |
| 制品仓库 | Packages 开箱（含公共仓库代理加速） | Nexus 自装自养 |
| 测试管理 | Testhub 开箱 | 需另购/另建，**常缺位** |
| 项目管理（需求/迭代） | Projex 开箱 | Jira/TAPD 另购；GitLab issue 勉强 |
| 效能度量 | Insight 8 模板/50+ 图表 | 自建数据链，**成本最高的自研项目** |
| 知识库 | Thoughts 开箱 | Confluence/语雀另购 |
| AI | AI 评审 + MCP Server（194 工具）内置 | GitLab Duo（需 Ultimate/加购，国内访问存疑）；Jenkins 需自己接 LLM API |
| 部署目标 | ACK/SAE/ECS 原生打通 | 任何环境（自由度最高） |

## 5.2 成本对比（50 人团队量级测算）

**自建路线显性成本（云上虚机口径，估）**：

| 资源 | 规格 | 月成本（估） |
|---|---|---|
| GitLab | 8C16G + 500GB SSD | ¥800~1500 |
| Jenkins Controller | 4C8G | ¥400~600 |
| 构建 Agent ×2 | 8C16G（按构建量） | ¥1500~2500 |
| Nexus + Harbor | 4C8G + 存储 | ¥600~1000 |
| 备份存储/数据库等 | — | ¥300~500 |
| **合计** | — | **约 ¥4000~6000/月** |

**隐性成本（大头）**：DevOps 兼职维护 0.3~0.5 FTE + 平时故障排障，按 ¥40~80 万/年的 DevOps 人力折算 ≈ **¥1.5~3 万/月**。**总 TCO 约 ¥2~3.5 万/月**。

**云效路线**：50 人高级版 = 50 × ¥618 = **约 ¥3.1 万/年 ≈ ¥2600/月**，运维近零。

> 结论：50 人量级，云效 TCO 大概率更低；自建转向"划算"的临界点在**数百人以上且使用 CE 免费档**（省掉许可 + 已有专职运维团队 + 服务器自持成本可控）。**自建省钱的前提是有闲余人力，否则省的是许可费、赔的是工程师时间。**

## 5.3 运维与可用性

| 项 | 云效 | 自建 |
|---|---|---|
| 部署上线 | 注册即用 | 周~月级搭建调优 |
| 高可用 | 平台多副本托管（SLA 背书） | 默认单机；真 HA 要成倍投入 |
| 升级 | 无感 | GitLab 月度跑步机 + Jenkins 插件月检 |
| 备份/容灾 | 平台负责 | 全自担（含恢复演练纪律） |
| 故障定位 | 提工单（高级版 5×12h 支持） | 自己上（也意味着自己最快） |
| 构建资源 | 平台弹性（高级版并发弹性） | 自己买机器/搭 K8s 动态 Agent |

## 5.4 安全与合规

- **数据主权**：自建 = 代码在自己的机房/私有网络，物理主权最高；云效 = 公共云 SaaS（有 VPC 内访问、代码加密、删库保护、审计），政企另有**专属版/专有云**可私有化。
- **合规背书**：云效自带等保三级/ISO27001 背书；自建则这些认证要**自己过**（自建平台本身不给你等保，你只是把风险移回了自己手里）。
- **暴露面**：自建若运维不力（公网暴露、CVE 不修），实际安全水位可能远低于 SaaS——**自建的安全是"能力上限高、下限也低"**。
- **外包/离职管控**：云效有外包管控与权限自动回收；自建要靠 GitLab EE 审计功能或自己建流程。

## 5.5 自由度与退出成本

- **自由度**：自建完胜——流水线想怎么写怎么写、想接什么接什么、可以在内网隔离环境玩任何花样；云效受产品边界约束，超出现有功能的流程只能等官方或走 OpenAPI 绕。
- **生态绑定**：自建绑定的是"自己的工程能力"（没有供应商）；云效绑定阿里云生态（ACK/SAE/钉钉联动最顺，迁走时流水线、度量数据要重建）。
- **退出成本**：代码都是 Git 标准 format，**仓库迁移两边都容易**（GitLab 导入 / Codeup 一键导入 Git/SVN）；难迁的是流水线逻辑（Jenkinsfile ↔ Flow 配置需要人工翻译）、度量历史数据和团队习惯。

## 5.6 综合评分表（主观量化，供汇报引用）

| 维度 | 云效 | 自建 GitLab+Jenkins | 说明 |
|---|---|---|---|
| 上手速度 | ★★★★★ | ★★ | SaaS vs 搭建 |
| 功能完整度（全链路） | ★★★★★ | ★★★ | 自建全家桶要拼 8~10 件 |
| 灵活性/定制 | ★★★ | ★★★★★ | 产品边界 vs 无边界 |
| 运维成本 | ★★★★★（无感） | ★★（0.3~0.5 FTE） | 自建的隐性大头 |
| 安全合规（对多数企业） | ★★★★ | ★★★ | 自建上限高、下限低 |
| AI 能力（2026） | ★★★★ | ★★ | 内置 AI 评审+MCP vs Duo 购买/自接 LLM |
| 长期成本（>300 人 + 有运维） | ★★★ | ★★★★ | 规模反转点 |
| 数据主权 | ★★★（公共云）/★★★★★（专属版） | ★★★★★ | 自建天然最高 |
| 与阿里云/钉钉协同 | ★★★★★ | ★★ | 原生集成差异 |

## 5.7 本章小结

对比的本质可以压缩成三句话：**功能上，云效是"全家桶开箱"，自建是"精品单件 + 自己拼车"**；**成本上，中小团队云效更便宜，数百人以上自建 CE 才开始反转**；**控制权上，自建天花板更高但地板也更低**——它把平台能力换成了平台责任。

---

# 六、选型决策：怎么选、怎么混、怎么迁

## 6.1 决策树

```
团队 ≤ 50 人 / 无专职 DevOps？
 ├─ 是 ────────────────────────────► 云效（基础版起步，够用再升高级版）
 └─ 否（有专职运维/DevOps）
      ├─ 强合规要求数据必须内网（军工/政务/核心金融）？
      │    ├─ 是 ──► 自建，或云效专属版/专有云（预算足选后者，省运维）
      │    └─ 否 ─▼
      ├─ 研发规模 ≥ 300 人 且 深度定制流程多？
      │    ├─ 是 ──► 自建为主（GitLab CE + Jenkins/Nexus + 自建度量），
      │    │         管理域可仍用云效 Projex 或 Jira
      │    └─ 否 ─▼
      ├─ 已有重资产 Jenkins 存量（数百条 job/团队精通 Groovy）？
      │    ├─ 是 ──► 保留自建 CI，渐进评估；仓库可先迁 Codeup 试水
      │    └─ 否 ──► 云效全家桶（含 AI 与度量红利）
```

## 6.2 两条路线的最小落地清单（Java 团队）

**云效路线（一周内跑通）**：Projex 建项目 → Codeup 建仓 → Packages 建 Maven 私服 → Flow 套"Java 构建 → 镜像 → ACK"模板 → Testhub 建用例库。细节见《云效平台.md》13.3 节。

**自建路线（两周~两个月，取决于熟练度）**：

1. GitLab：8C16G 虚机 Omnibus 安装，配 HTTPS/LDAP/关闭注册，`gitlab-backup` 定时异机备份 + secrets 单独保管；
2. Jenkins：4C8G Controller + 2 台 Agent（或 K8s 动态 Agent），装最小插件集（3.3 节清单），启用 JCasC；
3. Nexus：Maven proxy + private 仓库；Harbor（或用 GitLab 内置 Registry）；
4. SonarQube：接 Jenkins 扫描门禁；
5. 集成：GitLab webhook → Jenkins → 状态回写 MR（4.1 节）；
6. 规范：Jenkinsfile 模板 + Shared Library、分支保护 + MR 评审规则、凭据治理；
7. 别忘了：监控告警、磁盘清理、升级排期表——**这三样是自建能不能活过一年的分水岭**。

## 6.3 混合路线（现实中很常见）

- **自建 GitLab + 云效 Flow/Packages**：代码留下（主权/习惯），CI/CD 与制品交给 SaaS（云效流水线源支持第三方 Git 仓库）——用 SaaS 的运维红利，保代码的本地化；**过渡期最平滑的选择**；
- **自建工程链 + 云效 Projex**：管理和度量用 SaaS，工程链自持；
- **云效 Projex/Testhub + 自建 GitLab+Jenkins**：补管理和质量短板，工程链不动。

## 6.4 迁移路径（自建 → 云效）

1. **仓库先行**：Codeup 支持一键导入 Git（含第三方）并保留提交历史，风险最低；
2. **制品并行**：Packages 新建 Maven 仓库，Gradle/Maven 配置切地址；Nexus 存量制品可脚本搬运；
3. **流水线翻译**：Jenkinsfile 的 stage 逐条映射到 Flow 的任务节点（构建/扫描/部署均有对应），先平行运行、再切流量、后冻结 Jenkins；
4. **管理域收口**：Projex 模板照搬现有 Scrum/瀑布流程，Testhub 批量导入用例；
5. **度量重挂**：Insight 用官方场景模板重启度量（自建 Grafana 的自定义指标逐个评估去留）。

## 6.5 本章小结

决策不看产品宣传，看三个事实：**有没有专职运维、数据能不能出内网、研发规模多大**。三者都宽松选云效（最快拿到全家桶 + AI）；有一条卡死就走自建或专属版；都不确定就用混合路线过渡——自建 GitLab 存代码、云效跑流水线，两边的好处先各拿一半。

---

# 附录

## 附录 A：Spring Boot 项目 Jenkinsfile 完整示例（Declarative）

```groovy
pipeline {
    agent { label 'docker' }          // 在带 Docker 的 Agent 上执行

    environment {
        HARBOR = 'harbor.example.com'          // 私有镜像仓库
        IMAGE  = "${HARBOR}/demo/spring-boot-demo"
        TAG    = "${env.BUILD_NUMBER}-${env.GIT_COMMIT.take(8)}"
        MAVEN_OPTS = '-Dmaven.repo.local=.m2/repository'   // 工作区内缓存依赖
    }

    options {
        timestamps()
        timeout(time: 40, unit: 'MINUTES')
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '30'))     // 日志/产物自动清理
    }

    stages {
        stage('拉取代码') {
            steps { checkout scm }
        }

        stage('编译 + 单测') {
            steps {
                sh 'mvn -B clean test'                     // 单测 + JaCoCo 覆盖率
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: '**/target/surefire-reports/*.xml'
                }
            }
        }

        stage('SonarQube 质量门禁') {
            steps {
                withSonarQubeEnv('sonar-server') {
                    sh 'mvn -B sonar:sonar'
                }
                timeout(time: 10, unit: 'MINUTES') {
                    script {
                        def qg = waitForQualityGate()      // 等待 Sonar 质量闸结果
                        if (qg.status != 'OK') {
                            error "Sonar 质量门禁未通过: ${qg.status}"
                        }
                    }
                }
            }
        }

        stage('构建并推送镜像') {
            steps {
                script {
                    docker.withRegistry("https://${HARBOR}", 'harbor-credentials') {
                        def img = docker.build("${IMAGE}:${TAG}")
                        img.push()
                        img.push('latest')
                    }
                }
            }
        }

        stage('部署到测试环境') {
            steps {
                withCredentials([kubeconfigCredentials('k8s-test')]) {
                    sh '''
                      kubectl -n demo set image deploy/spring-boot-demo \
                        spring-boot-demo=${IMAGE}:${TAG}
                      kubectl -n demo rollout status deploy/spring-boot-demo --timeout=120s
                    '''
                }
            }
        }
    }

    post {
        success { dingtalk robot: 'devops-bot', type: 'MARKDOWN', title: '构建成功', text: "${IMAGE}:${TAG}" }
        failure { dingtalk robot: 'devops-bot', type: 'MARKDOWN', title: '构建失败', text: "${env.JOB_NAME} #${env.BUILD_NUMBER}" }
        always  { cleanWs() }                                // 清理工作区，防磁盘膨胀
    }
}
```

> 同一条流水线在云效 Flow 里的对应：拉代码（流水线源）→ Java 构建上传/镜像构建并推送（内置任务）→ 代码扫描（Sonar 内置）→ 主机/K8s 部署（内置任务）+ 上线前人工卡点——不需要写脚本，但每个节点的定制深度不如上面的 Groovy。

## 附录 B：GitLab 单机部署速查（docker-compose）

```yaml
services:
  gitlab:
    image: gitlab/gitlab-ce:latest        # 生产建议锁定具体版本号
    hostname: gitlab.example.com
    ports: ["80:80", "443:443", "2222:22"]
    volumes:
      - ./config:/etc/gitlab              # 含 gitlab-secrets.json，务必异机备份！
      - ./logs:/var/log/gitlab
      - ./data:/var/opt/gitlab
    shm_size: '256m'
    restart: unless-stopped
```

生产规格清单（≤500 用户）：8 vCPU / 16 GB RAM / ≥500 GB SSD（含备份余量）/ 仅内网可达 / 每日 `gitlab-backup` + secrets 异机存放 / 季度恢复演练。

## 附录 C：模块映射总表（云效 ↔ 自建）

| 环节 | 云效 | 自建典型组合 | 备注 |
|---|---|---|---|
| 项目管理 | Projex | Jira / TAPD / GitLab issue | 自建最常妥协的一格 |
| 代码托管 | Codeup | GitLab（CE） | Git 格式互通，迁移易 |
| 代码检测 | Codeup 内置四类扫描 | SonarQube + trufflehog/gitleaks | 自建要拼多个工具 |
| CI/CD | Flow | Jenkins / GitLab CI | 存量 Jenkins vs 新项目可选 GitLab CI |
| 制品仓库 | Packages | Nexus 3 / Artifactory | Maven 场景两者等价 |
| 镜像仓库 | ACR 配合 | Harbor / GitLab Registry | 云效走阿里云 ACR |
| 部署 | Flow/AppStack | Jenkins + kubectl/Ansible/ArgoCD | 自建可上 GitOps |
| 测试管理 | Testhub | TestRail / TestLink | 自建常缺位 |
| 效能度量 | Insight | API 采集 + ELK/CH + Grafana | 自建成本最高的一格 |
| 知识库 | Thoughts | Confluence / 语雀 | — |
| 身份 | 组织 + 钉钉/飞书/Okta | LDAP / Keycloak | — |
| AI | AI 评审 + MCP Server | GitLab Duo（付费）/ 自接 LLM API | 2026 差距明显 |

## 附录 D：官方链接

| 入口 | 地址 |
|---|---|
| GitLab 参考架构 | https://docs.gitlab.com/administration/reference_architectures/ |
| GitLab 定价 | https://about.gitlab.com/pricing/ |
| GitLab 备份与恢复 | https://docs.gitlab.com/administration/backup_restore/ |
| Jenkins 官网 | https://www.jenkins.io |
| Jenkins LTS 更新日志 | https://www.jenkins.io/changelog-stable/ |
| Jenkins 插件更新中心 | https://updates.jenkins-ci.org/download/plugins/ |
| JCasC（配置即代码） | https://www.jenkins.io/projects/jcasc/ |
| 云效产品页（姊妹篇） | https://www.aliyun.com/product/yunxiao |
