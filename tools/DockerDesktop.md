# Docker Desktop 使用说明（Windows 11 / WSL2 后端，本机 4.94.0 实测）

> **本文定位**：讲 **Docker Desktop 这个软件本身**——它是什么、界面长什么样、每项设置的含义与推荐值、日常维护、出问题时的恢复阶梯。容器与项目的日常操作（起栈、部署应用）见 `D:\ai\tools\容器开发操作指导手册.md`；规范与原理见 `D:\ai\tools\容器化开发建议.md`；环境总览见 `D:\code\workspace\AGENTS.md`。
>
> **环境锚点（2026-10-11 实测）**：本机 Docker Desktop **4.94.0.241994**、WSL 2.7.13、Windows 11 专业版 build 26300。关键设置现状：**不开机自启**（AutoStart=false）、引擎=WSL2、磁盘镜像位置 `D:\docker\data`、Resource Saver 默认开启（5 分钟）、日志轮转由默认 `local` 驱动自带（20MB×5）、未配置 registry-mirrors（直连可用）。
>
> **阅读约定**：设为"本机现状/推荐"的条目都标注 ✔；带 ⚠️ 的是危险操作。

---

## 一、Docker Desktop 是什么（30 秒理解）

Docker Desktop 不是 Docker 引擎本身，而是 Windows 上的一套**容器开发环境管理器**，它打包并管理这些东西：

| 组成部分 | 说明 | 在本机的形态 |
|---|---|---|
| **GUI 仪表盘** | 托盘鲸鱼图标 + 主窗口（容器/镜像/卷/构建四个页面） | 开机不自启，用时手动打开 |
| **Linux 虚拟机** | 一个名为 `docker-desktop` 的 WSL2 发行版，真正的 Linux 内核 + dockerd 在里面跑 | `wsl -l -v` 里可见；内存被 `.wslconfig` 封顶 8GB |
| **CLI 工具链** | `docker`、`docker compose`、`docker buildx` 等，随安装注入 PATH | `C:\Program Files\Docker\Docker\resources\bin` |
| **数据** | 镜像、容器、卷、构建缓存全部存在虚拟机的虚拟磁盘（vhdx）里 | vhdx 已迁到 **`D:\docker\data`**（设置项：Disk image location） |
| **设置存储** | 所有 GUI 设置 | `%APPDATA%\Docker\settings-store.json`（普通 JSON，可备份） |

一句话：**鲸鱼图标 = 总开关；`docker` 命令能用的前提 = 鲸鱼稳定（不在转圈动画）**。

## 二、界面导览：主窗口五个区域

打开 Docker Desktop 后左侧导航从上到下：

| 页面 | 内容 | 本机怎么用 |
|---|---|---|
| **Containers** | 全部容器，**compose 栈自动分组显示**（如 study-stack 下面挂 mysql/redis/kafka） | 点开容器有 Logs / Inspect / Exec / Stats / Files 五个标签；栈标题行有整组启停按钮 |
| **Images** | 本机镜像列表（名称/tag/大小/创建时间），行内 Run 按钮一键起容器，右上角清理按钮 | 看体积、删旧镜像在这里最快；5 个基础镜像约 2.4GB |
| **Volumes** | 命名卷列表，行内 **Browse** 可图形化浏览卷内文件 | 看 MySQL 数据目录结构、备份前确认卷内容用 |
| **Builds** | 构建历史与构建缓存占用 | 清理构建缓存也可以在这里点 |
| **Extensions / Scout 等** | 版本相关，随版本增减 | 本机未深度使用，不影响日常工作 |

**托盘图标状态语言**（右下角鲸鱼）：

- 🐋 转圈动画 = 启动中（等它稳定再敲 `docker` 命令）；
- 🐋 静止 = 引擎运行中；
- 🌿 出现**叶子** = Resource Saver 已挂起虚拟机（5 分钟无容器运行自动进入，内存归零；跑任何 `docker` 命令自动唤醒，秒级）。

## 三、设置详解（Settings，本机推荐值）

右上角 ⚙ 进入。**改任何设置都要点右下角 Apply & restart**。

### 3.1 General

| 设置项 | 本机现状 ✔ | 说明 |
|---|---|---|
| Start Docker Desktop when you sign in | **不勾** | 用户大部分时间不用容器——平时开机零占用，用时手动开（冷启动 30 秒~1 分钟） |
| Use the WSL 2 based engine | 勾（默认） | WSL2 是 Windows 上跑 Linux 容器的标准形态 |
| Choose how to configure the installation | 默认 | 无需动 |

### 3.2 Resources → Advanced

WSL2 后端下 **CPU/Memory/Swap 三项是灰的**——由 `C:\Users\Feng\.wslconfig` 控制（本机 8GB/8核/4GB swap），这是设计使然，不要找 GUI 里调内存。本页可调的：

| 设置项 | 本机现状 ✔ | 说明 |
|---|---|---|
| Disk image location | **`D:\docker\data`** | 镜像/卷的 vhdx 落点；C 盘紧张时迁移的关键设置（迁移即自动搬家） |
| Resource Saver | **开启**，超时默认 300 秒 | 无容器运行 5 分钟自动挂起 VM——"忘了关"的兜底，托盘叶子 🌿 即它的标志 |

### 3.3 Resources → Proxies

本机有 localhost 代理（如 Clash）时注意：**WSL2 的 NAT 模式看不到 Windows 的 localhost 代理**，但 Docker Desktop 的代理客户端跑在 Windows 侧——**在这里配 `http://127.0.0.1:7890` 这类地址是生效的**（引擎拉镜像会走它，日志里"via direct connection"会变成走代理）。当前未配置（直连可用），哪天直连不稳再配。

### 3.4 Docker Engine（JSON 编辑框）

这是 dockerd 的 daemon.json。**未改动时 4.94 的默认值已经很不错**：日志驱动是 `local`（自带 20MB×5 轮转，日志不会失控）。两个推荐追加项（对应操作手册动作 3）：

```json
{
  "registry-mirrors": ["https://<你的加速地址>"],
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "3" }
}
```

- `registry-mirrors`：**加速器**。本机 2026-10-11 实测直连 Docker Hub 可用所以暂未配置；哪天 pull 超时（报错速诊 §7）再配，或用阿里云个人专属加速地址；
- `log-opts`：显式 json-file 轮转，比默认 local 更通用（迁服务器时行为一致）。

### 3.5 其他页

| 页 | 建议 |
|---|---|
| **Kubernetes** | 本机未启用。要学 K8s 建议用 kind（见建议文档动作 16）——坏了一分钟重建；Desktop 内置 K8s 重置麻烦 |
| Software updates | 保持自动检查即可；更新方式见 §五 |
| WSL Integration | 对发行版注入 docker CLI 的开关；本机无 Ubuntu 发行版，保持默认 |

## 四、GUI vs CLI：什么活用哪个

| 任务 | GUI 路径 | CLI 等价 | 建议 |
|---|---|---|---|
| 看某容器实时日志 | Containers → 点容器 → Logs | `docker logs -f <名>` | 顺手排查用 GUI；追日志用 CLI |
| 进容器终端 | Containers → Exec 标签 | `docker exec -it <名> bash` | 等价 |
| 重启单个容器 | Containers 行内重启按钮 | `docker restart <名>` | 等价 |
| 整组启停 compose 栈 | Containers → 栈标题行按钮 | `docker compose stop` / `up -d` | 日常用脚本（startup/shutdown-all.sh） |
| 看镜像占多大 | Images 页 | `docker images` | GUI 更直观 |
| 浏览卷内文件 | Volumes → Browse | `docker run --rm -v 卷:/src alpine ls` | GUI 独有优势 |
| 清理构建缓存 | Builds 页清理按钮 | `docker builder prune` | 等价 |
| 漏洞扫描 | Scout 标签（选中镜像） | `docker scout cves <镜像>` | 偶尔看看 |

**总原则**：一次性、可视化的事用 GUI；要重复、要脚本、要精确参数的事用 CLI（脚本已沉淀在 `docker-stacks\` 下）。

## 五、维护与更新

- **更新 Docker Desktop**：GUI 右下角有新版本提示，一键更新；或 `winget upgrade Docker.DockerDesktop`。更新不丢镜像/容器/卷（它们在 vhdx 里，与程序分离——这正是把数据盘放 D: 的另一个好处）；
- **磁盘治理**：`docker system df` 体检 → GUI 的清理按钮 / `docker system prune` + `docker builder prune` → vhdx 只增不减，靠 `.wslconfig` 的 `sparseVhd` + `wsl --shutdown` 回收（完整流程见操作手册 §六/动作 12）；
- **备份**：设置文件 `%APPDATA%\Docker\settings-store.json` 可直接备份；镜像/卷的备份用 `docker-stacks\backup.sh`（数据在卷里，与 Desktop 无关）；
- **日志排查**：GUI 设置 → Troubleshoot → Get support 里有诊断包；主机侧日志在 `%LOCALAPPDATA%\Docker\log\`。

## 六、故障恢复阶梯（从轻到重，逐级升级）

| 级别 | 操作 | 适用场景 | 数据影响 |
|---|---|---|---|
| 1 | 等 / 托盘右键 Restart | 引擎启动慢、状态可疑 | 无 |
| 2 | PowerShell：`wsl --shutdown` → 重开 Docker Desktop | 休眠唤醒后网络异常、时钟漂移、命令卡死、vmmem 内存不还 | 无 |
| 3 | Troubleshoot（🐞 图标）→ **Restart Docker Desktop** | 反复卡死、设置改完不生效 | 无 |
| 4 | Troubleshoot → **Clean / Purge data** | ⚠️ 引擎数据疑似损坏 | **删全部镜像/容器/卷**——必须先 `backup.sh` |
| 5 | Troubleshoot → **Reset to factory defaults** | ⚠️⚠️ 设置也乱了 | 同上 + 设置回出厂（settings-store.json 被重置） |
| 6 | 卸载重装（winget uninstall/install） | 软件本体损坏 | vhdx 在 D:\docker\data，重装后把 Disk image location 指回去即可恢复数据 |

> 经验：本环境 90% 的问题（唤醒后卡死、内存不还、网络失灵）用 **级别 2** 就解决了。级别 4/5 前必须确认备份存在。

## 七、许可与账号

- **免费范围**：个人使用、教育、非商业开源，以及员工 <250 人且年收入 <$10M 的企业；超出需 Pro/Team/Business 订阅；
- **不登录也能用**：首次启动 Skip 登录即可，仅拉取限流（未登录对 Docker Hub 匿名限流约 10 pulls/6 小时/IP）——配加速器后基本无感；
- 登录账号的额外收益：Docker Hub 个人仓库、Scout 完整功能。

## 八、FAQ

| 问题 | 答案 |
|---|---|
| 开机后鲸鱼图标在哪？ | 本机未开自启——开始菜单搜 Docker Desktop 手动打开 |
| `docker` 命令报 `Cannot connect to the Docker daemon` | Desktop 没开或没就绪，等鲸鱼稳定 |
| vmmemWSL 占用几个 G 不降 | ①有容器在跑（`docker stats` 看）；②Resource Saver 没触发（有容器就不算空闲）；③都不服就 `wsl --shutdown` 立即清零 |
| 想开机自启怎么开 | Settings → General → Start Docker Desktop when you sign in（改完下次开机生效） |
| 和以后装的 WSL Ubuntu 冲突吗 | 不冲突，共享同一 WSL2 虚拟机；`wsl --shutdown` 会把两者一起关（无损） |
| 镜像拉取超时 | 见操作手册 §7：换加速器（§3.4 的 JSON）或在 Proxies 页配代理 |
| vhdx 文件很大删了镜像也没变小 | 正常，vhdx 只增不减——sparseVhd + `wsl --shutdown` 回收，见操作手册动作 12 |

---

## 附：本文档与其他文档的分工

| 文档 | 回答的问题 |
|---|---|
| **本文（DockerDesktop.md）** | Docker Desktop 软件本身：界面、设置、维护、故障恢复 |
| `容器开发操作指导手册.md` | 项目日常：起栈、部署应用、备份、报错速诊、命令速查 |
| `容器化开发建议.md` | 为什么这么干：17 个动作的规范与原理 |
| `D:\code\workspace\AGENTS.md` | AI/新同事一页纸环境总览 |
