# Jenkins 本机学习方案（写给要用 Docker 模拟企业 CI/CD 的练习者）

> **资料基准**：本文记录的是本目录讨论后**已选定的练习方案**，不是企业生产部署手册。约束条件：代码仓在 **GitHub**；练习目标是 **Jenkins**（不是 GitHub Actions）；Jenkins 安装在 **本机 Docker**；部署靶是本机 **Docker Compose**（CI 最终对指定项目执行 `docker compose up`）。对照日期 **2026-10-16**。Jenkins LTS、插件与 Docker Desktop 行为会随版本变化，动手前以官方镜像与插件页为准。
>
> **阅读约定**：先建立「企业流水线长什么样」，再看「本机用哪些零件去顶替」。本机 Compose 是**假环境**，不是把笔记本当成机房。每章按「定位 → 展开 → 本章小结」写。

## 如何读这份文档

- **第一遍（建立地图，约 20 分钟）**：读第一章总览、第二章选定方案、第五章对比表。读完应能回答：练的是哪一层？GitHub / Jenkins / Registry / Compose 各扮演什么角色？和正式企业差在哪？
- **第二遍（按练习推进）**：装控制面时读第三章；写流水线时读第四章；想少走弯路时读第六章（明确不做什么）。

---

# 一、总览：练习目标、设计原则与零件全景

## 1.1 一句话定位

**这是一套「Jenkins 控制面 + GitHub 代码仓 + 本机制品库 + 两套 Compose 假环境」的笔记本实验室**：用企业常见的流水线形态（Pipeline as Code、制品晋级、环境审批）做练习，部署结果仍出现在本机 `docker ps` 里。

它解决的不是「怎么在 Windows 上点一下把容器拉起来」，而是 **在不离开本机 Docker 的前提下，让每天摸到的东西和企业 Jenkins 是同一类**：Multibranch、`Jenkinsfile`、凭据、分阶段、不可变镜像、dev 再 prod。

## 1.2 设计原则：读全文前先记住五句话

1. **练习产品是 Jenkins，不是 Actions。** GitHub 只托管代码和发 webhook；job 必须跑在 Jenkins 上。
2. **CI 出制品，CD 用制品。** 禁止在部署阶段 `compose up --build` 直接编源码；部署 compose 只引用已推送的镜像 tag。
3. **本机 Docker 是部署靶，不是构建与运行混在一起的「一键脚本」。** `docker compose up` 作用在假的 dev/prod 项目上，语义是「发布到某环境」。
4. **控制面容器化，数据要持久。** Jenkins 跑在 Docker 里可行且作为本方案安装方式；`JENKINS_HOME` 必须落在卷上。
5. **像在形态，不像在机房。** 企业不会把生产跑在 CI 节点和开发者笔记本上；本机同机部署只为看得见结果。

## 1.3 零件分层全景

```
┌────────────────────────── 人与 GitHub ──────────────────────────┐
│  开发者 push / PR     GitHub 仓库（代码、Jenkinsfile、分支保护）   │
│  webhook ──► Jenkins Multibranch 扫描分支与变更                   │
├────────────────────────── Jenkins 控制面（本机 Docker）──────────┤
│  Jenkins LTS 容器     JENKINS_HOME 数据卷     凭据库              │
│  声明式 Jenkinsfile：测试 → build/push → 部署 dev →（审批）prod   │
├────────────────────────── 本机 Docker Engine（同一套）───────────┤
│  本地 Registry（顶替 Harbor/ECR）  镜像 tag = commit SHA         │
│  Compose 项目 dev（假开发环境）     Compose 项目 prod（假生产）    │
└─────────────────────────────────────────────────────────────────┘
```

Jenkins 容器必须使用**正在用的那一个 Docker Engine**（通常经引擎接口交给容器里的 docker/compose 客户端）。否则 `compose up` 发生在内层引擎，桌面上看不到容器，练习闭环会断。

## 1.4 零件依赖关系

```
GitHub 仓库
    │  webhook / 拉取 Jenkinsfile 与源码
    ▼
Jenkins（Docker 容器，数据在卷里）
    │  docker build / push          │  docker compose up（按环境）
    ▼                               ▼
本机 Registry ◄──────── pull ────── Compose dev / Compose prod
    （SHA 镜像）                    （只引用镜像，不现场 build）
```

依赖方向是单向的：**GitHub 不知道 Compose；Compose 不知道 GitHub；Jenkins 是唯一编排者。** 这与「仓库里一个脚本自己 compose up」不同，也与「GitHub Actions 托管 Runner 直接部署本机」不同——托管 Runner 看不见本机 Docker。

## 1.5 关键问题 → 本方案映射

| 你要搞清的问题 | 本方案的答案 | 详见 |
|---|---|---|
| 仓库在 GitHub，还能练 Jenkins 吗？ | 能。企业里 Jenkins 对接 GitHub 很常见 | 第二、四章 |
| Jenkins 装进本机 Docker 可行吗？ | 可行，且作为本方案的安装方式 | 第三章 |
| 怎样让 CI 真的 `compose up` 到本机？ | Runner/Controller 与 Docker Engine 同机 | 第三、四章 |
| 怎样更像企业，而不是一键启动？ | 制品 + 两套环境 + 审批晋级 | 第二、四、五章 |
| 和正式企业 CI/CD 差在哪？ | 执行器、隔离、制品库、部署目标都是缩小版 | 第五章 |
| 哪些做法看起来快、但学歪了？ | Freestyle 一键 `--build`、改练 Actions | 第六章 |

## 1.6 方案演进（讨论中留下的取舍）

| 阶段 | 想法 | 结论 |
|---|---|---|
| 起点 | 企业/个人该用什么 CI/CD | 随代码托管与合规而变，无单一赢家 |
| 约束 1 | 仓在 GitHub，本机 Docker 练习 | 托管 Actions 默认看不到本机 Docker |
| 约束 2 | CI 直接 `compose up` | 执行器必须与本机 Engine 同机 |
| 约束 3 | 要更像企业自动化 | 拆 CI/CD、Registry、dev/prod 晋级 |
| 约束 4 | **本来就要练 Jenkins** | 控制面从 Actions 改回 Jenkins |
| 落地形态 | Jenkins 是否用本机 Docker 装 | **可行，采用** |

## 1.7 全文章节地图

| 章节 | 内容 | 一句话 |
|---|---|---|
| 一 | 总览 | 练习目标与零件图 |
| 二 | 选定方案 | 推荐形态，不再并列备选 |
| 三 | Jenkins 进 Docker | 可行性、接 Engine、数据持久 |
| 四 | 流水线形态 | Multibranch、Jenkinsfile 阶段、假环境 |
| 五 | 与企业正式 CI/CD 对比 | 像什么、不像什么 |
| 六 | 明确不做 | 避免学歪的路径 |
| 七 | 练习时心里要有的边界 | 安全、Windows/WSL2、状态残留 |

### 本章小结

本方案是笔记本上的 Jenkins 企业形态缩小版：GitHub 出代码，Jenkins 出编排，本地 Registry 出制品，两套 Compose 出环境。下一章把「推荐就这一套」写死。

---

# 二、选定方案：只保留这一条路

## 2.1 模块定位

本章把讨论收敛成**唯一推荐方案**，作为后续章节的共同前提。不为「看起来更企业」再叠 GitLab 自管或 Tekton。

## 2.2 方案正文

**本机 Jenkins 当 CI/CD 控制面，GitHub 只当代码仓，本机 Docker Compose 当假环境。**

具体形态：

- 用 Docker 运行 **Jenkins LTS** 作为控制面。
- 仓库使用 **Multibranch Pipeline**，以根目录 **`Jenkinsfile`** 为唯一流水线定义（不把 Freestyle 点选任务当主练习）。
- 一次变更按企业拆阶段：测试 → `docker build` → 镜像打 **commit SHA** → 推到 **本机 Registry**。到此是 CI。
- CD 对 **dev** Compose 项目执行 `up`：compose 文件**只引用该镜像**，禁止部署时 `--build`。
- **prod** 是第二套 Compose（不同项目名、不同端口），必须经过 Jenkins **手动确认（input）** 后，才能部署**同一个 SHA**。
- GitHub webhook 触发构建；GitHub 账号、Registry、部署相关口令进入 **Jenkins 凭据库**，不提交进 Git。

## 2.3 为什么这样像企业

企业 Jenkins 主流是 Pipeline as Code：任务定义在仓库里，凭证在 Jenkins（或保险库），构建出不可变制品，再按环境晋级。本方案保留这些**控制面语义**。本机 Registry 顶替 Harbor/ECR 的「先推再拉」；两套 Compose 顶替「至少有两个环境」；input 顶替生产审批。容器最终仍在本机出现，满足「看得见部署」的练习约束。

## 2.4 为什么不选并列方案

| 路径 | 为何不作为主方案 |
|---|---|
| GitHub Actions + 本机 Self-hosted Runner | 仓库虽在 GitHub，但练习产品变成 Actions，占掉 Jenkins 时间 |
| Jenkins 任务里直接 `compose up --build` | 有自动触发，无制品、无晋级，和企业主链只在「会跑」这一点相似 |
| 本机 kind/k3d + Tekton/Argo | 更像云上企业，但部署面从 Compose 换成集群，练习目标被换掉 |
| 再叠 GitLab 自管 | 多一套语法和运维，对「仓已在 GitHub、要练 Jenkins」无增益 |

### 本章小结

推荐方案已经收口：Jenkins + GitHub + 本地 Registry + 两套 Compose。下一章只回答「Jenkins 装进本机 Docker 是否成立、要注意什么」。

---

# 三、Jenkins 安装在本机 Docker 中

## 3.1 模块定位

安装方式属于方案的一部分：**可行，并且采用**。本章讲可行性与约束，不是逐步敲命令的安装手册（命令级步骤应在明确批准的实施计划里另写）。

## 3.2 为什么可行

官方提供 Linux 容器镜像（Jenkins LTS）。在 Windows 上，Docker Desktop 通常把 Linux 容器跑在 **WSL2** 里的 Linux 引擎中，因此**不必**再装一套 Windows 原生 Jenkins。浏览器访问映射出来的端口（常见为 8080）即可完成解锁、装建议插件、建任务。这与企业用容器或虚拟机跑 Controller 的心智一致，只是规模是单容器。

## 3.3 必须同时满足的三个条件

1. **接到同一套 Docker Engine。** 练习目标是本机 `compose up`。Jenkins 里执行的 `docker` / `docker compose` 必须打到你平时 `docker ps` 用的那个守护进程。常见练习接法是让 Jenkins 容器使用宿主机引擎接口，并在容器内具备客户端。若改用完全隔离的 Docker-in-Docker，`up` 的结果默认不会出现在桌面 Docker 里，和目标冲突。
2. **`JENKINS_HOME` 持久化。** 任务、插件、凭据、用户都在这个目录。只起临时容器、不挂卷，删容器等于重装。
3. **按 Linux 写流水线。** 容器内是 Linux。`Jenkinsfile` 的 shell、compose 的卷路径、`localhost` 含义都按 Linux/WSL 侧理解，不要按 PowerShell 路径去套。

## 3.4 权限含义（练习可接受，生产不可照搬）

能对本机 Engine 执行任意 `docker` 命令的 Jenkins，等于能起停本机任意容器、读相关挂载。个人实验室为了看见 `compose up` 可以接受。企业则把构建放到**一次性 Agent**，Controller 不直接掌握开发者或生产主机的 Docker 套接字。本方案第一层先求闭环；更像企业的第二层才是「Controller 与 Agent 分开，Agent 仍用本机 Engine」。第一层不必第一天堆完。

## 3.5 解锁与插件的心理预期

官方镜像首次进入会要求初始管理员密码（在容器日志或 `JENKINS_HOME` 约定路径中）。建议插件包含 Pipeline、Git，之后再补 **GitHub** / **Docker Pipeline** 等与本方案相关的插件。这是正常流程，不代表安装失败。插件过多、随意升级，是 Jenkins 经典运维痛点——练习环境也尽量少装、装完记下版本。

### 本章小结

Jenkins 进本机 Docker 技术成立，是本方案的控制面部署方式。成败不在「容器起没起」，而在是否共用 Engine、数据是否在卷上、流水线是否按 Linux 编写。下一章描述流水线本身。

---

# 四、流水线形态：从 webhook 到两套 Compose

## 4.1 模块定位

本章描述**企业形态在本机如何展开**：触发、定义、CI、CD、晋级。不展开某一次任务的完整 Groovy 原文。

## 4.2 触发与任务类型

GitHub 保存 `Jenkinsfile` 与应用代码。Jenkins 建 **Multibranch Pipeline**（或 GitHub 组织文件夹），凭 GitHub 凭据扫描分支；push 与 PR 经 webhook 触发（本机需让 GitHub 能打到 Jenkins，或练习期用「扫描」/手动 Build Now 代替公网 webhook）。日常开发目录和 Jenkins checkout 出来的工作区**不是同一份目录**——`compose up` 应以流水线工作区（或明确约定的部署目录）为准，避免和你正在编辑的未提交文件搅在一起。

## 4.3 阶段划分（必须拆开）

| 阶段 | 职责 | 明确禁止 |
|---|---|---|
| 检出 | 拉 GitHub 对应 commit | 在开发者家目录乱 up |
| 验证 | 测试 / 必要的检查 | 验证失败仍继续 push 镜像当成功 |
| 构建制品 | `docker build`，tag 为 **git commit SHA** | 只用 `latest` 当唯一标签 |
| 发布制品 | push 到本机 Registry | 构建成功但镜像只留在构建机本地、部署再现场 build |
| 部署 dev | 对 dev 项目 `compose up`，image 指向 `registry/...:SHA` | `up --build`、compose 里 `build:` 作为主路径 |
| 审批 | Jenkins `input`（或等价手动步骤） | 同一 job 无确认直冲 prod |
| 部署 prod | 对 prod 项目 `up`，**同一 SHA** | 为 prod 重新 build 一份「差不多的」镜像 |

dev 与 prod 用**不同 Compose 项目名、不同主机端口**，以便同时存在、用浏览器区分。它们仍共享同一 Docker Engine，故障域没有真正分开——这是第五章要记住的不像之处。

## 4.4 凭据与密钥

GitHub 访问令牌、Registry 登录（若启用）、应用环境变量中的口令，一律进 Jenkins Credentials，在 `Jenkinsfile` 里按 ID 注入。仓库内不提交真实密钥。GitHub 仓库 Secrets 不是本方案的主密钥库（那是 Actions 的模型）。

## 4.5 回滚在本方案里意味着什么

企业回滚通常是把环境拨回**旧制品**，而不是把 Git 倒回再编一次。本机对应动作是：prod 的 compose 改回上一个已知 SHA 再 `up`。若每次部署都 `--build`，回滚会变成「重新编译当前工作区」，练习就学歪了。

### 本章小结

流水线的形状是「GitHub 变更 → Jenkins 验证并推出 SHA 镜像 → dev 用镜像 up → 人确认 → prod 用同一镜像 up」。下一章把这张形状贴到企业正式链路上，标出缩小了哪些关节。

---

# 五、与企业正式 CI/CD 的对比

## 5.1 模块定位

对比的目的是防止两种误解：一是「本机已经和企业一样」；二是「差这么多所以练习无价值」。价值在**流水线形态**，不在把笔记本当机房。

## 5.2 总表

| 环节 | 本机 Jenkins 模拟（本方案） | 企业正式常见形态 |
|---|---|---|
| 代码托管 | GitHub | GitHub/GitLab 等，常加分支保护、CODEOWNERS、强制评审 |
| 任务定义 | 仓库 `Jenkinsfile` + Multibranch | 同左，常加 Shared Library 收集团队模板 |
| 触发 | webhook 或手动扫描 | 组织级 GitHub App、PR 检查、定时、上游制品事件 |
| 控制面 | 单容器 Jenkins，数据在本地卷 | 多节点、备份、SSO、Folder/RBAC、审计、升级窗口 |
| 执行器 | 早期常是 Controller 直接用本机 Docker | 弹性一次性 Agent（K8s Pod / 云主机），Controller 不跑构建 |
| 制品 | 本机 Registry + SHA 标签 | Harbor/ECR/ACR + 扫描、签名、复制、保留策略 |
| 部署动作 | 对指定 Compose 项目 `up`（用镜像） | 远程集群/主机；Helm/GitOps（Argo CD 等）常见；生产不在 CI 节点上跑 |
| 环境 | 不同项目名、不同端口的两套 Compose | 独立集群或账号，网络与密钥隔离 |
| 晋级 | `input` 后再部署 prod | 审批 + 质量门 + 变更单；生产凭据对普通任务不可见 |
| 密钥 | Jenkins Credentials | 保险库 / 短期令牌，按 Folder 或环境隔离 |
| 失败现场 | 本机 `docker ps`、容器日志，状态会残留 | 以流水线日志与环境为准，Runner/Agent 常即用即毁 |
| 插件与升级 | 自己装、环境易坏 | 核心插件受控，随意插件是变更事故 |

## 5.3 像的部分（练习要抓住）

同一类控制面（Jenkins）、真实的任务/日志/凭据模型、CI 与 CD 分离、制品不可变、环境晋级、生产前人工确认、密钥出库。GitHub 作为远端仓而不是 CI 引擎——这与大量企业现状一致。

## 5.4 不像的部分（练习不要假装没有）

企业 Agent 一次性、可抛弃；你的 Docker 磁盘和旧容器会污染下次运行。企业环境是不同故障域；你只是不同端口。企业 CD 常常声明式调和集群；你是命令式 `compose up`。企业有组织级权限与审计；本机 Jenkins 几乎是单用户。Windows + WSL2 的路径与 `localhost` 语义，不会出现在企业 Linux Agent 上。

### 本章小结

本方案在「Jenkins 流水线怎么想」上对齐企业，在「跑在哪、隔离到什么程度」上刻意缩小。读对比表是为了以后进公司时能翻译：笔记本上的 dev/prod 对应的是环境，不是两台生产集群。

---

# 六、明确不做

## 6.1 模块定位

把已经否决的路径写进文档，避免过几周又绕回去。

## 6.2 清单

1. **不以 GitHub Actions 作为本练习的主控制面。** 即便仓在 GitHub，job 仍在 Jenkins。
2. **不以 Freestyle + `compose up --build` 作为「学会了企业 CI/CD」的完成标准。** 那只是学会了让 Jenkins 跑一条命令。
3. **不在部署阶段重新构建。** prod 必须吃和 dev 相同的 SHA。
4. **不为了像而引入 GitLab 自管、Tekton、整套本地 Kubernetes。** 那些是另一门课。
5. **不把本机 Self-hosted 执行器登记到公开仓库的 fork PR。** 公开仓库的不可信代码拿到本机 Docker 等于拿到这台电脑。练习用私有仓库、自己的 push。
6. **不把无持久卷的 Jenkins 容器当成可长期练习的环境。**

### 本章小结

少做比多装更重要。Jenkins 插件和旁路平台最容易把「练流水线」变成「养工具」。

---

# 七、练习边界：安全、Windows、状态

## 7.1 模块定位

动手前的心理模型和环境边界，减少「以为 Jenkins 坏了、其实是本机 Docker 脏了」。

## 7.2 安全边界

挂接宿主机 Docker 的 Jenkins 是高权限进程。仓库保持私有；凭据只用最小权限 GitHub token（repo 与 webhook 所需即可）；不要把真实生产口令写进练习流水线。这是实验室，不是零信任生产网。

## 7.3 Windows / Docker Desktop / WSL2

Linux Jenkins 容器、Linux 引擎、Windows 浏览器三者同时存在。compose 里写 `localhost`、绑定 `C:\...`、在 PowerShell 里执行与在容器里执行，指向可能不是同一处。出问题时先问：这条命令是在哪个 Linux 里执行的、它看到的 Docker 是哪一台。

## 7.4 有状态的 `compose up`

云上 CI 常见「干净机器编一次」。本机 `up` 会沿用项目名、网络、卷、端口。第二次成功可能只是「更新已有栈」；失败可能是端口被占用或旧容器没删。这是假环境的特性，不是 Jenkins 随机故障。练习时应固定项目名，并接受偶尔需要手动 `compose down` 清场——清场本身也模拟「拆环境」，但不要依赖「每次从零的虚机」这种企业 Agent 体验。

### 本章小结

同机、有状态、Windows 嵌 Linux，是本实验室的三个永久边界。流水线写对了也会被脏 Engine 打脸；对比第五章，这正是和企业最大的体验差。

---

# 附录：方案一句话备查

**用 Docker 跑 Jenkins LTS（数据卷 + 共用本机 Docker Engine）；GitHub 存代码和 `Jenkinsfile`；Multibranch 触发；CI 把 SHA 镜像推进本机 Registry；CD 用该镜像对 dev/prod 两套 Compose 做 `up`；prod 经 Jenkins 手动确认；密钥只进 Jenkins 凭据库。**

对照企业时记住：形态像，机房不像。
