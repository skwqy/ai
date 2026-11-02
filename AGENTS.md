# 工作目录约定（Agent 须知）

## 目录规范

| 目录 | 用途 |
|------|------|
| `D:\code\3rd` | 关键软件源码（第三方源码查阅） |
| `D:\code\workspace` | 新建项目的存放目录 |
| `D:\soft` | 下载软件的存放目录 |
| `D:\soft\green` | 绿色软件（解压即可使用） |
| `D:\soft\jdk` | 解压即可使用的软件（如 JDK） |
| `D:\tmp` | 临时目录：Agent 处理任务时下载的临时文件、解压、临时产物统一放这里 |

## 使用规则

1. **查阅源码**：关键软件源码在 `D:\code\3rd`。
2. **新建项目**：统一创建在 `D:\code\workspace` 下。
3. **下载软件**：统一放到 `D:\soft`；解压即可使用的软件放到 `D:\soft\green` 或 `D:\soft\jdk`。
4. **临时文件**：Agent 处理任务时需要下载临时文件并解压、创建临时文件等操作，统一放到 `D:\tmp`，用完可随时清理。

## 版本控制（提交与推送）

工作目录下有**两个相互独立**的 Git 仓库，改动后要分别提交、分别 push，不存在"一次提交覆盖两个目录"：

| 仓库根 | 远程 | 分支 | 管什么 |
|--------|------|------|--------|
| `D:\ai` | `git@github.com:skwqy/ai.git` | `main` | 本约定文件（`AGENTS.md`）、技术文档（`spring/` 下的框架笔记等） |
| `D:\code\workspace` | `git@github.com:skwqy/workspace.git` | `main` | 示例工程（`hello-observability`、`docker-stacks` 等） |

规则：

1. **改完即提交**：完成一个有意义的改动（新增/更新文档、修完 bug、定下约定）后，按所属仓库分别 `add` → `commit` → `push`，不要攒着等用户手动提。
2. **跨目录改动两个仓库都要提**：一次任务同时动了 `D:\ai` 和 `D:\code\workspace` 时，两个仓库各自独立提交，不要只提一个。
3. **先看再提**：`git status --short` 确认范围、`git diff` 确认内容；只 `git add` 本次任务相关的文件，不要无脑 `git add -A`。
4. **push 前先同步**：`git fetch` 后用 `git rev-list --left-right --count origin/main...HEAD` 看是否落后；落后就 `git pull --rebase` 再推，不要用 `--force`。
5. **多行 message 走文件**：Git Bash 下 PowerShell 的 here-string `@'...'@` 会被原样写进 message，多行信息用 `git commit -F <file>`。
6. **提交信息用中文**：首行一句话概括改动（不超过 50 字），空行后分点说明"改了什么、为什么、影响哪些文件"。
7. **敏感文件不入库**：`.env`、token、`*-local.yml` 等出现时先补 `.gitignore`，再提交。

## IDEA MCP 服务器

IntelliJ IDEA 内置的 MCP 服务器（在 IDEA 中通过 `工具（Tools）> MCP 服务器` 启用）会同时提供两种传输方式：

| 传输方式 | 地址 | 说明 |
|---------|------|------|
| SSE | `http://127.0.0.1:64342/sse` | 早期 SSE 传输，兼容性更好 |
| Streamable HTTP | `http://127.0.0.1:64342/stream` | 新版流式 HTTP 传输，客户端支持时优先用它 |

使用规则：

1. **先启用**：需在 IDEA 的 `工具 > MCP 服务器` 中勾选「启用 MCP 服务器」，URL 才会真正监听；未勾选时连接会失败。
2. **地址以 IDEA 显示为准**：端口（示例中的 `64342`）和路径由 IDEA 分配，端口被占用或 IDEA 重启后可能变化，配置前先看一眼 IDEA 里的实际地址。
3. **配置方式**：接入 MCP 客户端时直接填 `url`（SSE 客户端填 `/sse`，Streamable HTTP 客户端填 `/stream`），本地回环地址通常不需要额外的 header 或认证。
4. **用途**：该服务器让 Agent 复用 IDEA 的工程索引与代码能力（符号检索、引用查找、工程结构等），比纯文本搜索更准确；涉及当前工程代码分析时优先走它。

## 文档写作约定

- 撰写技术文档时，文档的整体结构可参考：`D:\ai\spring\Spring Framework.md`。
  其结构骨架为：开头"如何读这份文档" → 总览章（一句话定位、设计哲学、模块分层全景、依赖图、关键问题映射、版本演进、全文章节地图）→ 分章节深入源码解析（每章含模块定位、核心机制展开，以"本章小结"收尾）。
