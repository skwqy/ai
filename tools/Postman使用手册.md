# Postman 使用手册

> **适用版本**：Postman 12.31.7（2026-10，Windows 桌面版，登录账号 skwqy，工作区 skwqy's Team's Workspace）
> **实战配套文件**：`D:\code\workspace\hello-component\postman\hello-component.postman_collection.json`（AI 生成的集合文件，本手册所有操作围绕它展开）
> **姊妹篇**：[容器化开发操作指导手册](./容器化开发操作指导手册.md) —— 本手册的前置环境（study-stack 容器 + hello-component 应用）由它负责拉起
>
> Postman 界面迭代很快，个别按钮文案可能随版本微调，但位置逻辑不变。

---

## 如何读这份文档

| 你的目的 | 读法 |
|---|---|
| 现在就把 AI 生成的接口文件导进去跑通 | 只看第 2、3 章，约 10 分钟 |
| 系统学会 Postman 日常使用 | 顺序读第 1~5 章 |
| 用的时候报错了 | 直接查第 6 章常见问题表 |
| 想知道界面上其他按钮是干嘛的 | 第 7 章速览 |
| 边用边查 | 第 8 章速查表 |

约定：`A → B → C` 表示依次点击；`Ctrl+O` 表示快捷键；`{{baseUrl}}` 这种双花括号是 Postman 的变量语法，第 4 章解释。

---

## 1. 总览

### 1.1 一句话定位

Postman 是图形化的 HTTP 调试工具：**手动构造请求发给服务端、查看响应、把常用请求存成集合反复执行**。它替代的命令是 curl —— 优势是不用记语法、参数可视化编辑、可保存可复用、还能自动断言（测试）。

### 1.2 界面全景（对照你当前的窗口）

| 区域 | 在哪 | 作用 | 你需要用到吗 |
|---|---|---|---|
| **侧栏** | 左侧一列：COLLECTIONS / ENVIRONMENTS / DOCUMENTS / SPECS / MOCKS / DATASETS / TOOLSETS | 各类资源的管理面板 | **只有 COLLECTIONS 必用**，其余见第 7 章 |
| 工作区切换 | 左上角「skwqy's Team's Workspace」 | 决定集合存到哪个云端工作区 | 保持默认即可 |
| **请求编辑区** | 中间大块：方法 + URL + Send + 参数页签 | 构造和发送请求 | **必用** |
| **响应区** | 请求编辑区下方 | 查看响应体、头、断言结果 | **必用** |
| Console | 底部「Console」页签（或左下角 Console 按钮） | 请求收发的原始日志 | 排错时用（第 6 章） |
| AI 面板 | 右侧「New Chat」 | Postman 内置 AI 助手（每月 100 credits） | 可忽略 |
| 状态栏 | 最底部一行（Globals / Vault / Tools 等） | 全局变量、密钥库等入口 | 暂不用 |

补充两点：

- 左上角一排小图标是侧栏视图切换，其中**时钟图标是 History（历史记录）**——你发过的每个请求都自动记录在那里，没保存的请求也能找回来。
- ⚠️ **别把那排图标里的「文件夹」图标当成导入**：它是 Open Folder（打开本地文件夹），点了会进入 Postman 12 的「本地模式」——界面变成文件树、请求变成 `.request.yaml` 文本。误入了看 2.5 节，点回第一个（立方体）图标即可。
- 打开后看到的两三个「Overview」页签、「Release Notes」页签都是信息页，用不上点 × 关掉即可。

### 1.3 核心概念（30 秒版）

| 概念 | 是什么 |
|---|---|
| Request（请求） | 一次 HTTP 调用的定义 = 方法 + URL + 头 + 体 + 脚本 |
| Collection（集合） | 一组请求的文件夹容器，一个项目一个集合，可整体导入/导出/批量运行。**你那个 JSON 文件就是一个集合** |
| Variable（变量） | `{{name}}` 占位符，分全局/集合/环境几级，改一处处处生效 |
| Environment（环境） | 一组命名的变量集（如 dev/prod），右上角下拉切换 |
| Scripts（脚本） | 请求前后执行的 JS，主要用于自动断言 |
| Runner（运行器） | 把集合里的请求按顺序一键全跑 |

### 1.4 你的最短路径

```
启动容器环境 → 导入集合 → 点「0. 健康检查」Send 绿了 → 按文件夹顺序点完 ①~⑤
```

### 本章小结

Postman = 可视化、可保存、可批量、可断言的 curl。日常 90% 的操作集中在侧栏 COLLECTIONS 和中间的请求编辑区，其余面板新手阶段全部可以无视。

---

## 2. 导入集合：把 AI 生成的 JSON 放进去

> 文件位置：`D:\code\workspace\hello-component\postman\hello-component.postman_collection.json`

### 2.1 三种导入方式（任选其一）

**方式一：拖拽（最快）**
在资源管理器里选中这个 `.json` 文件，直接拖进 Postman 窗口任何位置。

**方式二：导入按钮**
侧栏顶部搜索框右侧有几个小按钮，其中一个是「带箭头的方框」图标（鼠标悬停显示 Import）→ 点击 → 选择文件。

**方式三：菜单**
`Ctrl+O`（或左上角 `⌄` 菜单 → File → Import）→ 选择文件。

导入对话框会把文件识别为 **Postman Collection v2.1** → 点 **Import** 按钮确认 → 完成。

> 导入功能不认格式：OpenAPI/Swagger 文档、cURL 命令、Postman 环境文件都能从同一个入口导入。

### 2.2 导入后长什么样

左侧 COLLECTIONS 面板出现一个集合：

```
hello-component（Spring Boot 4.1.1 + MySQL/Redis/Kafka）
├─ 0. 健康检查            （1 个请求）
├─ 1. MySQL + Kafka 链路   （3 个请求）
└─ 2. Redis               （2 个请求）
```

- **点集合名**：右侧打开集合总览页。集合描述就写在里面——前置条件、执行顺序、变量说明都在，忘了随时回来查。
- **点任意请求名**：在新页签打开该请求，可以直接 Send。
- 导入后侧栏没出现？检查两点：COLLECTIONS 分类是否展开（名称前的小箭头）、工作区是否还是 skwqy's Team's Workspace。

### 2.3 导入后第一件事：确认 baseUrl

集合里已经内置了变量 `baseUrl = http://localhost:8081`。

**查看/修改路径**：点集合名 → **Variables** 页签 → 找到 `baseUrl`，改 current value 列。

应用换端口时只改这一处，全部 6 个请求自动跟着变——这就是集合变量的意义。改完发一次「0. 健康检查」验证通不通。

### 2.4 集合文件以后更新了怎么办

AI 重新生成了 JSON 后，再走一遍 2.1 导入。Postman 识别出同 ID 集合会弹窗让你选：

- **Replace**（覆盖旧版）← 一般选这个
- **Import as copy**（复制一份，会出现两个重名集合）← 别选错

### 2.5 误入「本地模式」怎么办（侧栏变成 YAML 文件树 ≠ 导入成功）

**现象**：左侧不是 COLLECTIONS 面板，而是一棵文件树（`.postman`、`collections`、`documents`…），请求变成一个个 `.request.yaml` 文件，点开看到的是 YAML 文本而不是 Send 界面；左下角出现 **Local mode** 徽标和「Set up Git」卡片。

**原因**：你点的是左上角的「文件夹」图标（Open Folder），而不是导入入口。这是 Postman 12 的**本地模式（Local mode）**：把一个磁盘文件夹（比如 `D:\code\workspace\Postman`）当作工作区，集合不再存云端，而是以 YAML 文件形式落在磁盘上、供 Git 管理。在这个模式下导入 JSON，会被自动转换成一批 `.request.yaml` 文件——所以你看到的是 YAML。**你的原始 JSON 文件不会被改动。**

**处理（两步回到经典界面）**：

1. 点左上角侧栏切换图标里的**第一个（立方体）图标** → 回到熟悉的 COLLECTIONS 面板
2. 重新导入一次：把 JSON 拖进窗口（或 `Ctrl+O`）→ 这次集合出现在 COLLECTIONS 里，点请求就是带 Send 按钮的经典界面

> 本地模式里那次导入的内容不会出现在云端工作区，所以必须重新导一次。

**清理**：本地模式的产物全部在它自己的文件夹里（如 `D:\code\workspace\Postman`，里面只有 Postman 自动生成的转换副本和空目录），确认不玩 Git 模式后整个文件夹删掉即可；Postman 界面里若还显示着，切回集合视图或重启 Postman 就消失。

**什么时候需要它**：团队想用 Git 仓库管理 API 定义（像管理代码一样 review、回滚接口变更）时，本地模式 + Native Git 才有用武之地。新手阶段用经典模式就够了。

### 本章小结

导入入口记住一个就够：**把 JSON 拖进 Postman 窗口**。导入后先到集合 Variables 页确认 baseUrl 指向应用实际端口；文件更新重导时选 Replace。

---

## 3. 发送请求

### 3.1 前置条件（缺一不可）

1. Docker Desktop 已启动，study-stack 三服务全部 `(healthy)`（操作见容器手册）
2. hello-component 应用容器已启动，监听 `127.0.0.1:8081`

### 3.2 第一发：健康检查

`0. 健康检查 → actuator/health（三组件聚合状态）` → 点 **Send**。

期望：状态行绿色 **200 OK**，Body 里 `"status": "UP"`。

- `UP` = MySQL、Redis、Kafka 全部连通；任何一个组件 DOWN 都会反映在这个返回里。
- **这一发绿了再往下走**，否则后面全是白点。

### 3.3 全链路顺序执行（按文件夹编号来）

| 步骤 | 请求 | 方法 + URL | 期望结果 |
|---|---|---|---|
| ① | MySQL 保存并发 Kafka | POST `/api/messages?content=你好,Postman!` | 200，返回带自增 `id` 的 JSON（MySQL 落库 + 发 Kafka） |
| ② | MySQL 查询全部 | GET `/api/messages` | 200，数组里能看到刚存的那条 |
| — | **等 2~3 秒** | （Kafka 异步消费需要时间） | |
| ③ | Kafka 消费回读 | GET `/api/kafka/last` | 返回「N:内容」，证明 生产→消费→落 Redis 全链路通 |
| ④ | Redis 写 | POST `/api/redis/demo?value=hello-redis` | 返回含 OK |
| ⑤ | Redis 读 | GET `/api/redis/demo` | 返回 `hello-redis` |

> ①的中文参数直接填在 URL 里即可，Postman 会正确按 UTF-8 编码（此前 curl 400 是 Git Bash 控制台 GBK 的锅，服务端没问题）。
> 不想手动等？用 5.3 节的 Runner 设 delay 3000 一键全跑。

### 3.4 看懂响应区

- **状态行**：`Status: 200 OK`（2xx 绿色，4xx/5xx 红色），旁边是 `Time` 耗时和 `Size` 大小。
- **页签**：
  - **Body** —— 响应体，Pretty（格式化 JSON）/ Raw（原文）切换
  - **Cookies / Headers** —— Cookie 和响应头
  - **Test Results** —— **断言结果**：绿 Pass / 红 Fail，导入的集合每个请求自带 2 条断言（第 5 章）
- 响应区左上角的 **History** 下拉：这个请求的历史响应记录。
- Send 按钮旁边的下拉箭头（Send and Download 等）暂时不用管。

### 3.5 请求上的页签都是什么

| 页签 | 作用 | 本项目用吗 |
|---|---|---|
| Params | query 参数（`?key=value`），和 URL 里写的自动双向同步 | **是**（①④⑤ 的参数就在这改） |
| Authorization | 认证配置（Bearer/Basic/API Key…） | 否，项目无认证 |
| Headers | 请求头 | 偶尔 |
| Body | 请求体（JSON/表单/文件），POST/PUT 时用 | 否，本项目用 query 传参 |
| Scripts | 请求前/后脚本（断言写在这，见第 5 章） | 已内置，可查看 |
| Settings | 跟随重定向、SSL 校验等开关 | 基本不动 |

改完参数直接 Send，**对集合内请求的修改自动保存**（登录状态下同步到云端工作区，换电脑登录还在）；只有新建的草稿请求才需要点 Save。

### 本章小结

先发健康检查确认环境通，再按 ①→⑤ 顺序点，注意 ① 和 ③ 之间隔 2~3 秒。响应区重点看三样：状态行颜色、Body 内容、Test Results 有没有红。

---

## 4. 变量与环境

### 4.1 `{{baseUrl}}` 怎么读

URL 里写 `{{baseUrl}}`，发送前 Postman 会把占位符替换成变量当前值（鼠标悬停可预览解析结果）。未定义的变量会显示红色/橙色提醒。

### 4.2 三级变量，本项目用了哪级

| 级别 | 在哪配置 | 本项目 |
|---|---|---|
| Global（全局） | 底部状态栏 Globals | 未用 |
| **Collection（集合）** | 集合 → **Variables** 页签 | **用了：baseUrl** |
| Environment（环境） | ENVIRONMENTS 面板 → Create | 未用，可自建 |

**优先级：环境 > 集合 > 全局**（同名变量，高一级的赢）。

### 4.3 环境实战：dev/prod 一键切换（可选）

想在不改集合的前提下切换不同端口/服务器：

1. 左侧 **ENVIRONMENTS** 面板 → **Create** → 起名 `env-dev`
2. 加变量 `baseUrl` = `http://localhost:8081` → Save
3. 右上角 **No Environment** 下拉 → 切到 `env-dev`

之后再建 `env-prod`（指向另一个地址），下拉一切就整体换目标。选回 **No Environment** 时回落到集合变量。

### 本章小结

本项目日常只需要知道：**baseUrl 在集合 Variables 页里改**。等出现多环境需求再玩 Environment 下拉，优先级记住「环境 > 集合 > 全局」。

---

## 5. 断言与集合运行器

### 5.1 你的集合已经自带 12 条断言

集合文件里每个请求都配了测试脚本（在请求的 **Scripts** 页签 → Post-response 部分可查看），形如：

```js
pm.test('HTTP 200', () => pm.response.to.have.status(200));
pm.test('整体状态 UP', () => pm.expect(pm.response.json().status).to.eql('UP'));
```

含义：每次 Send 后自动检查响应，结果显示在响应区 **Test Results**：绿 Pass、红 Fail。**Send 出现绿色 200 但断言红了，也说明结果不符合预期**——这就是自动化测试的价值。

### 5.2 pm.test 30 秒入门

```js
pm.test('断言名字', () => {
  pm.response.to.have.status(200);                        // 状态码
  pm.expect(pm.response.json().status).to.eql('UP');      // JSON 字段
  pm.expect(pm.response.text()).to.include('OK');         // 文本包含
});
console.log('调试输出，打印到 Console');
```

想让 AI 生成器多加几条断言，直接改 JSON 再 Replace 导入即可（见 2.4）。

### 5.3 集合运行器：一键按顺序全跑

1. 点集合名 → 总览页 **Run** 按钮（或集合右侧 `...` → Run collection）
2. Runner 配置页里：
   - **Delay 填 `3000`**（毫秒）——每个请求之间隔 3 秒，正好覆盖 Kafka 消费延迟，③ 就不会白跑
   - 可勾选 Save responses 保留响应快照
3. 点 **Run hello-component**

结果页每个请求一行：绿色 = 断言全过；红的点开看失败详情。也可以只跑单个文件夹：文件夹上 `...` → Run folder。

### 5.4 命令行也能跑（了解即可）

从 12.31 起，**Postman CLI 随桌面版一并安装**（Release Notes 里那条新闻说的就是它）：终端里 `postman login` 后可执行 `postman collection run <集合UID> --delay-request 3000`，适合以后接 CI 流水线。现阶段图形界面完全够用。

### 本章小结

断言已经内置，日常只看 Test Results 颜色。想偷懒就记住 Runner 的用法：**Run → Delay 3000 → Run**，一键跑完全链路。

---

## 6. Console 与常见问题排查

### 6.1 Console（排错第一现场）

打开方式：底部 **Console** 页签，或 `Ctrl+Alt+C`。

每次 Send 都会记录：**实际发出的完整 URL、请求头、请求体、响应原文、脚本 console.log 输出**。界面上看不出来的问题（重定向、代理拦截、编码）在这里都有原始记录——排错先开 Console。

### 6.2 常见问题速查

| 症状 | 原因 | 处理 |
|---|---|---|
| `Could not get response` / 连接拒绝 | 应用没启动，或端口不对 | `docker ps` 确认容器在跑；核对 baseUrl 端口；先发 0. 健康检查 |
| 健康检查返回里某组件 `DOWN` | 对应基础服务没就绪 | 看返回体哪个组件 DOWN，回容器手册查对应服务日志 |
| ③ 返回「还没消费到任何消息」 | Kafka 异步消费有延迟 | 等 2~3 秒重发一次；或改用 Runner delay 3000 |
| ⑤ 读回 null/过期提示 | Redis TTL 10 分钟已到 | 正常现象，重跑 ④ 再 ⑤ |
| Test Results 出现红 Fail | 响应不满足断言 | 点开红条看是哪条；对照 Body 判断是环境问题还是脚本问题 |
| 本机开了代理（Clash 等）后请求异常 | Postman 默认走系统代理 | 右上角 ⚙ 设置 → Proxy：关掉 Use system proxy，或把 localhost 加入 bypass |
| 重新导入后出现两份同名集合 | 上次选了 Import as copy | 删掉一份，下次选 Replace |
| 手滑改坏了请求想恢复 | 集合有云端版本历史 | 集合 `...` → Change History 可回滚 |
| 拖入后侧栏变文件树、请求是 `.request.yaml` | 误点了 Open Folder 进入本地模式 | 见 2.5：点左上角立方体图标切回集合视图，重新导入 |

### 本章小结

排错三板斧：**开 Console 看原始请求 → 发健康检查定位环境 → 查上表对症处理**。90% 的新手问题是「应用没启动」和「Kafka 没等够时间」。

---

## 7. 其余面板与功能速览（现在都可以不管）

| 面板/功能 | 是什么 | 什么时候有用 |
|---|---|---|
| ENVIRONMENTS | 环境变量管理 | 第 4 章，想分 dev/prod 时 |
| DOCUMENTS | 接口文档（可由集合生成发布） | 想给团队看接口文档时 |
| SPECS | API 规范编辑器（OpenAPI） | 「先设计后实现」的工作流 |
| MOCKS | 模拟服务器，后端没写完先返回假数据 | 前后端并行开发 |
| DATASETS | 数据集，配合 Runner 做数据驱动测试 | 用 CSV/JSON 批量跑参数 |
| TOOLSETS | MCP 工具集（供 AI Agent 调用的工具） | Agent 玩法，暂不涉及 |
| AI / Agent Mode | 内置 AI 助手（100 credits/月），可帮写请求/测试 | 想偷懒时可以试试 |
| History（时钟图标） | 请求发送历史 | 找回没保存的请求 |
| 底部 Vault / Globals / Tools | 密钥库 / 全局变量 / 扩展工具 | 暂不用 |
| 本地模式（Open Folder / Native Git） | 把磁盘文件夹当工作区，集合以 YAML 文件落盘、用 Git 管理 | 团队用 Git 仓库管理 API 时 |

Release Notes 页签是版本更新说明，看完关掉即可。

---

## 8. 速查表

### 操作 → 路径

| 要做什么 | 怎么做 |
|---|---|
| 导入集合文件 | 把 JSON 拖进窗口 / `Ctrl+O` / 侧栏 Import 图标 |
| 更新集合（重导） | 再导入一次 → 选 **Replace** |
| 改 baseUrl | 集合 → Variables → current value |
| 发请求 | Send 按钮 / `Ctrl+Enter` |
| 改 query 参数 | URL 直接改，或 Params 页签改 |
| 看断言结果 | 响应区 Test Results 页签 |
| 打开 Console | `Ctrl+Alt+C` / 左下角 Console |
| 一键全跑（带等待） | 集合 → Run → Delay 填 3000 → Run |
| 新建/切换环境 | ENVIRONMENTS → Create；右上角 No Environment 下拉 |
| 导出集合备份 | 集合 `...` → Export → Collection v2.1 |
| 删除集合 | 集合 `...` → Delete |

### 常用快捷键

| 快捷键 | 功能 |
|---|---|
| `Ctrl+Enter` | 发送当前请求 |
| `Ctrl+O` | 打开导入对话框 |
| `Ctrl+Alt+C` | 打开/关闭 Console |
| `Ctrl+T` | 新建页签 |
| `Ctrl+W` | 关闭当前页签 |

---

## 附：相关文件

| 文件 | 路径 |
|---|---|
| 本手册实战集合 | `D:\code\workspace\hello-component\postman\hello-component.postman_collection.json` |
| 容器环境操作 | [容器化开发操作指导手册](./容器化开发操作指导手册.md) |
| 容器化总体建议 | [容器化开发建议](./容器化开发建议.md) |
