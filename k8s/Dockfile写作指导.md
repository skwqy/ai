# Dockerfile 写作指导（写给 Spring Boot 容器化的架构全景）

> **本文基于**：Docker 官方 *Dockerfile reference* / *Building best practices* / *Build cache invalidation*（2026 年文档口径），以及 Spring Boot **4.1.1** 官方文档 [Dockerfiles](https://docs.spring.io/spring-boot/reference/packaging/container-images/dockerfiles.html)（`jarmode=tools`、分层提取、`bellsoft/liberica-openjre-debian:25-cds` 示例）。
>
> **文件名说明**：按任务约定保存为 `Dockfile写作指导.md`。正确拼写是 **Dockerfile**（Docker + file，中间没有 k）。文中一律使用正确拼写。
>
> **版本取舍**：Spring Boot 4.x 对应 Spring Framework 7.x，运行基线仍是 **JDK 17+**；官方容器示例已切到 **Java 25 + CDS/AOT cache** 友好基础镜像。下文示例以 4.1.1 官方写法为准，并补充更常见的 Temurin 21 JRE 变体，便于尚未升级到 JDK 25 的环境。

## 如何读这份文档

推荐两遍读法：

- **第一遍（建立地图，30~40 分钟）**：只读第一章总览、第二章「一份 Dockerfile 的骨架」、第三章「层是什么」、第五章层顺序建议的结论段，以及第七章示例。目标是能回答：Dockerfile 分哪几块？层和目录是不是一回事？为什么要把依赖拷在业务代码前面？
- **第二遍（对照落地）**：按第二章逐条指令核对自己仓库里的 Dockerfile；用第三章、第四章解释 `docker history` 和 `docker inspect` 看到的差异；用第六章对照 Spring Boot 分层 JAR 的 `COPY` 顺序。

---

# 一、总览：Dockerfile 的定位、设计哲学与整体结构

## 1.1 一句话定位

**Dockerfile 是一份按顺序执行的镜像配方**：它告诉构建器（BuildKit）从哪张基础镜像出发、安装什么、拷贝什么、以什么用户和命令启动容器。构建结果是一张 **只读镜像**；容器是这张镜像加上一层可写容器层后的运行实例。

它解决的不是「怎么把 jar 塞进 Linux」，而是 **可重复、可缓存、可分发的交付物组织问题**：哪些步骤可以复用、哪些文件会频繁变化、最终镜像里该留下什么、不该留下什么。

## 1.2 设计哲学：写 Dockerfile 前先记住四句话

1. **镜像是层的栈，不是目录树的拷贝**。层是「相对上一层的文件系统差分」；目录只是差分里面的路径。后写的指令叠在先写的指令上面，而不是「先建文件夹再按文件夹编号图层」。
2. **缓存从前往后连锁失效**。某一层缓存 miss 之后，后面所有层默认都要重做。所以 **越稳定的东西越靠前，越常变的东西越靠后**。
3. **最终镜像只应包含运行所需**。编译器、Maven、源码、测试、`.git` 都应留在构建阶段或构建上下文之外；运行阶段用 JRE/distroless，而不是 JDK + 全家桶。
4. **一条指令一个意图，一组相关副作用必须同层完成**。`apt-get update` 和 `apt-get install` 必须写在同一条 `RUN` 里，否则缓存会让「过期的包索引」永远生效；装完立刻删掉 `/var/lib/apt/lists`，垃圾才不会冻进层里。

## 1.3 Dockerfile 模块分层全景

一份完整的生产级 Dockerfile，按「读者感知」通常由下面几块组成（不一定每一块都出现，但出现时职责应当清晰）：

```
┌───────────────────────────── 解析器头 ─────────────────────────────┐
│  # syntax=docker/dockerfile:1     （可选，锁定 Dockerfile 前端语法）│
│  # escape=`                       （Windows 容器才需要改转义符）     │
├───────────────────────────── 元数据与注释 ─────────────────────────┤
│  注释（# ...）  LABEL（镜像作者、版本、OCI 注解）                    │
├───────────────────────────── 构建阶段（builder）───────────────────┤
│  FROM ... AS builder                                                │
│  ARG / ENV / WORKDIR / USER（构建时）                                │
│  COPY 源码或 jar → RUN 编译或 jarmode 提取                          │
├───────────────────────────── 运行阶段（runtime）───────────────────┤
│  FROM 更小的运行时镜像                                              │
│  拷贝构建产物（每条 COPY 一层）                                      │
│  ENV / EXPOSE / USER / HEALTHCHECK                                  │
│  ENTRYPOINT + CMD                                                   │
└────────────────────────────────────────────────────────────────────┘
```

对应到「写的时候从哪下手」：

| 区块 | 典型指令 | 写它时要回答的问题 |
|------|----------|-------------------|
| 解析器指令 | `# syntax=` | 用哪一版 Dockerfile 语法？要不要 BuildKit 高级特性？ |
| 基础镜像 | `FROM` | 构建和运行是否需要两张不同的镜像？JDK 还是 JRE？ |
| 构建参数 | `ARG` | 哪些值只在 `docker build` 时变化（jar 路径、版本号）？ |
| 运行环境 | `ENV`、`WORKDIR`、`USER` | 进程的工作目录、用户、JVM 参数默认值是什么？ |
| 文件系统变更 | `RUN`、`COPY`、`ADD` | 哪些变更会变成层？哪些文件经常改？ |
| 网络与存储声明 | `EXPOSE`、`VOLUME` | 文档化端口和挂载点（它们本身几乎不改文件系统） |
| 启动契约 | `ENTRYPOINT`、`CMD`、`HEALTHCHECK`、`STOPSIGNAL` | 容器主进程是谁？健康检查怎么做？收到 SIGTERM 能否优雅停机？ |

## 1.4 指令 → 层 关系图（先建立正确心智模型）

```
docker build 按 Dockerfile 从上到下执行
        │
        ▼
  ┌─────────────┐
  │ FROM base   │  引入基础镜像已有的 N 层（自己通常再记一条 history）
  └──────┬──────┘
         │
         ▼
  ┌─────────────┐
  │ RUN apt...  │  ★ 产生文件系统层（diff tar）
  └──────┬──────┘
         │
         ▼
  ┌─────────────┐
  │ COPY app    │  ★ 再产生一层（只含被拷文件的 diff）
  └──────┬──────┘
         │
         ▼
  ┌─────────────┐
  │ ENV/CMD/... │  多数只改镜像 Config（元数据），docker history 仍有一行
  └──────┬──────┘
         │
         ▼
     镜像 Manifest + Config + RootFS.Layers
```

运行时 OverlayFS 把这些层自下而上联合挂载成 **一个** 根文件系统。容器再在最上面加一层可写层。所以：**层的顺序 = 指令执行顺序；目录的顺序 = 联合挂载后路径在树里的位置。两者不是同一件事。**

## 1.5 关键问题 → 方案映射（全文导览）

| 你真正卡住的问题 | 结论先看这里 | 详见 |
|------------------|--------------|------|
| Dockerfile 到底要写哪几部分？ | 解析器头、FROM、参数/环境、文件系统变更、声明、启动契约；生产环境再加多阶段 | 第二章 |
| 层到底是什么？和「文件夹」有什么区别？ | 层是只读的文件系统差分；目录只是差分中的路径 | 第三章 |
| 哪些指令会创建/改变层？ | 真正改 RootFS 的主要是 `FROM`（引入）、`RUN`、`COPY`、`ADD`；`WORKDIR` 在目录不存在时也会落盘 | 第四章 |
| 层顺序是不是等于 mkdir 顺序？ | 不是。层顺序跟指令书写顺序走；目录可在任一层被创建，后层可以把文件叠进前层已有的目录 | 第五章 |
| 层顺序有什么优秀实践？ | 稳的在前、变的在后；相关副作用同层；多阶段丢掉构建工具；Spring Boot 按 dependencies → loader → snapshot-dependencies → application 拷 | 第六章 |
| Spring Boot 4.1.1 怎么写？ | `java -Djarmode=tools -jar ... extract --layers`，运行阶段 `java -jar application.jar`（不再用旧的 `layertools` + `JarLauncher` 作为官方首选） | 第七章 |

## 1.6 版本演进（和写作直接相关的口径）

| 主题 | 旧口径（你可能在网上看到） | 2026 / Boot 4.1.1 口径 |
|------|---------------------------|------------------------|
| 分层提取 | `java -Djarmode=layertools -jar app.jar extract` | `java -Djarmode=tools -jar app.jar extract --layers --destination extracted` |
| 列出层 | `java -Djarmode=layertools -jar app.jar list` | `java -Djarmode=tools -jar app.jar list-layers` |
| 启动方式 | 解压后 `JarLauncher` | 官方示例启动 **提取后的瘦 `application.jar`**：`ENTRYPOINT ["java", "-jar", "application.jar"]`，布局对 AOT cache / CDS 友好 |
| 基础镜像示例 | `openjdk:17-jdk-alpine`（已不推荐，镜像线混乱） | 官方：`bellsoft/liberica-openjre-debian:25-cds`；保守生产：`eclipse-temurin:21-jre` |
| 层指令是否「每条都是一层」 | Docker 早期几乎每条指令都提交一层 | 现代镜像：**文件系统层**主要由 `RUN`/`COPY`/`ADD` 产生；`CMD`/`ENV` 等进 Config；`docker history` 仍会为多数指令留一行 |

### 本章小结

Dockerfile 是镜像的配方，不是 shell 脚本的随便堆砌。读后续章节时始终抓住两根轴：**指令写什么（结构）** 和 **每条指令会不会变成一层、会不会打爆缓存（层）**。

---

# 二、Dockerfile 包含哪几部分、应该如何书写

## 2.1 解析器指令（Parser directives）

必须写在文件 **最顶端**、空行和注释之前（`# syntax=` 除外，它本身就是解析器指令）。常见的只有两个：

```dockerfile
# syntax=docker/dockerfile:1
# escape=\
```

- `# syntax=docker/dockerfile:1`：锁定 BuildKit 的 Dockerfile 前端，解锁 `RUN --mount`、heredoc 等能力。生产仓库建议加上，避免不同 Docker 版本语法漂移。
- `# escape=`：默认 `\`；Windows 容器可改成 `` ` ``。Linux/Spring Boot 场景保持默认即可。

这一块 **不产生业务层**，只影响后续怎么解析。

## 2.2 注释

以 `#` 开头。给人看，不进镜像文件系统。不要把密钥写在注释里——Dockerfile 会进 Git。

## 2.3 `FROM`：每一个构建阶段的起点

语法：

```dockerfile
FROM [--platform=<os>/<arch>] <image>[:<tag>] [AS <name>]
```

书写要点：

- **每个 Dockerfile 至少一条 `FROM`**。多阶段构建里，每个阶段都以自己的 `FROM` 开始。
- **永远钉标签或摘要**。`FROM eclipse-temurin:21-jre` 可以；`FROM ubuntu`（隐式 `latest`）不要进生产。更严的团队钉 digest：`FROM eclipse-temurin:21-jre@sha256:...`。
- **阶段命名**用 `AS builder`，后面 `COPY --from=builder` 才读得懂。
- `FROM` 会把基础镜像的层全部纳入当前阶段的底层；再往上叠你自己的层。

## 2.4 `ARG` 与 `ENV`：构建期参数 vs 运行期环境

```dockerfile
ARG JAR_FILE=target/*.jar
ARG APP_VERSION=0.0.1
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0" \
    TZ=Asia/Shanghai \
    SPRING_PROFILES_ACTIVE=prod
```

| | `ARG` | `ENV` |
|---|--------|--------|
| 生命周期 | 默认只在 **build** 有效 | 写入镜像，**容器运行时仍在** |
| 覆盖方式 | `--build-arg KEY=value` | `docker run -e`、K8s `env` |
| 缓存影响 | 若该 `ARG` 被后面的 `RUN` 用到，值一变，从使用处开始缓存失效 | 改 `ENV` 会让后续指令的缓存键变化 |
| 安全 | 仍会出现在 `docker history` 里，**不要用 ARG 传密码** | 同样不要把密钥写死；用运行时注入 |

`FROM` **之前** 的 `ARG` 叫全局构建参数，只能影响 `FROM` 本身（例如选基础镜像标签），不会自动进入 `FROM` 之后的阶段。需要的话，`FROM` 之后再声明一次同名 `ARG` 才能继续用。

## 2.5 `WORKDIR`：后续指令的当前目录

```dockerfile
WORKDIR /application
```

路径建议用绝对路径。它会影响后面的 `RUN`/`COPY`/`CMD`/`ENTRYPOINT`。若目录不存在，`WORKDIR` 会创建它——这时会在镜像里留下真实目录项（见第四章）。

不要靠一连串 `RUN cd /foo && ...`；`cd` 只在该条 `RUN` 的 shell 里有效，下一层又回到原来的工作目录。

## 2.6 `COPY` 与 `ADD`：把文件放进镜像

```dockerfile
COPY --chown=spring:spring ${JAR_FILE} application.jar
COPY --from=builder /builder/extracted/dependencies/ ./
```

推荐规则（Docker 官方最佳实践口径）：

- **普通文件从构建上下文进来：用 `COPY`**。语义简单，缓存校验更直观。
- **`ADD` 留给「需要自动解 tar」或「拉取远程 HTTPS/Git 资源」的场景**。远程 URL 用 `ADD` 比手写 `wget && tar` 缓存更精确，但 Spring Boot 日常拷 jar **不要用 `ADD`**。
- 多阶段之间拷文件必须 `COPY --from=<stage>`。
- 用 `.dockerignore` 把 `target/`、`.git/`、`.idea/`、测试报告排除出构建上下文，否则 `COPY . .` 会把垃圾打进缓存键。
- 能 `COPY` 单个文件就不要 `COPY` 整个目录。缓存校验看的是被拷文件的元数据集合。

## 2.7 `RUN`：构建期执行命令（最容易把镜像写炸的地方）

```dockerfile
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
```

书写要点：

- exec 形式 `RUN ["apt-get","install","-y","curl"]` 不经过 shell，没有变量展开；shell 形式 `RUN apt-get ...` 才有 `&&`、通配符。
- **相关命令用 `&&` 写在一条 `RUN` 里**：安装与清理必须同层，否则删除发生在「下一层」，上一层的包缓存仍然占镜像体积（层只能叠加上去，不能从上一层里真正抹掉字节——删除只是在新层标记白出版本）。
- BuildKit 下可用 `RUN --mount=type=cache` 缓存 Maven 仓库，而不把 `~/.m2` 拷进最终层。
- 不要在 `RUN` 里 `apt-get upgrade` 无约束地升级整个系统，可复现性会崩。

## 2.8 `USER`：别用 root 跑 Spring Boot

```dockerfile
RUN groupadd --system spring && useradd --system --gid spring spring
USER spring
```

软件包安装、改 apt 必须在 `USER root`（默认）下做完，**最后再切普通用户**。K8s 里还要配合 `runAsNonRoot`、`readOnlyRootFilesystem` 等安全上下文；镜像侧先把用户准备好。

## 2.9 `EXPOSE`、`VOLUME`、`LABEL`

```dockerfile
LABEL org.opencontainers.image.title="demo-service" \
      org.opencontainers.image.version="${APP_VERSION}"
EXPOSE 8080
VOLUME /tmp
```

- `EXPOSE` **不会**真正发端口，只是文档 + 对 `docker run -P` 的提示。Spring Boot 默认 8080，K8s 里真正开端口的是 `containerPort`。
- `VOLUME /tmp` 在 Spring Boot 里很常见：内嵌 Tomcat 默认把工作目录放 `/tmp`。注意：Dockerfile 里的 `VOLUME` 会让后续对该路径的写入行为变复杂，K8s 场景更常见的是在 Pod 里挂 `emptyDir`，镜像里可以不写 `VOLUME`。
- `LABEL` 用 OCI 注解，方便仓库和安全扫描识别。

## 2.10 `ENTRYPOINT` 与 `CMD`：容器的主进程契约

二者都有 shell 形式和 exec 形式。**生产只用 exec 形式（JSON 数组）**，这样 PID 1 就是 `java`，才能收到 SIGTERM 做优雅停机。shell 形式的 PID 1 是 `sh`，Java 进程当子进程，K8s 滚动更新时经常杀不干净。

| 组合 | 含义 | Spring Boot 典型用法 |
|------|------|----------------------|
| 只有 `CMD ["java","-jar","app.jar"]` | 可被 `docker run 镜像 别的命令` 整段覆盖 | 演示可以，生产偏弱 |
| `ENTRYPOINT ["java","-jar","app.jar"]` | 镜像「就是这个程序」 | 官方 4.1 示例 |
| `ENTRYPOINT ["java"]` + `CMD ["-jar","app.jar"]` | 入口固定为 java，参数可覆盖 | 需要灵活加 JVM 参数时 |

`docker run 镜像 --spring.profiles.active=dev` 在 `ENTRYPOINT ["java","-jar","app.jar"]` 下会把参数 **追加** 到 java 命令后面，这正好符合 Spring Boot 的参数风格。

## 2.11 `HEALTHCHECK`、`STOPSIGNAL`、`SHELL`、`ONBUILD`

```dockerfile
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health || exit 1
STOPSIGNAL SIGTERM
```

- K8s **不使用** Dockerfile 的 `HEALTHCHECK`，只用 probe。写了也无妨，对纯 Docker/Compose 有用。
- `STOPSIGNAL` 默认已是 SIGTERM，一般不用写；关键是 PID 1 必须是 JVM。
- `SHELL` 改默认 shell，Linux 极少需要。
- `ONBUILD` 给「基础镜像留给下游」用，业务应用镜像不要用，可读性差。

## 2.12 多阶段构建：把「怎么造」和「怎么跑」拆开

Spring Boot 4.1.1 官方示例就是标准多阶段：

1. **builder 阶段**：需要 JRE（提取分层 JAR 用 `java -Djarmode=tools`）或 JDK（若你在镜像内 `mvn package`）。
2. **runtime 阶段**：只拷提取后的目录/瘦 jar，不再包含 uber jar 的重复嵌套、不再包含 Maven。

未出现在最终 `FROM` 阶段的文件，不会进入你推上去的那张运行镜像。builder 阶段的层仍存在于构建缓存里，但不随运行镜像分发（除非你错误地 `FROM builder` 当运行镜像）。

### 本章小结

先定阶段（几条 `FROM`），再定「改磁盘的指令」（`RUN`/`COPY`）和「定契约的指令」（`USER`/`ENTRYPOINT`/`CMD`）。结构写清楚以后，优化对象永远是：**哪些变更变成层、这些层谁先谁后。**

---

# 三、层（Layer）是什么概念？

## 3.1 一个比喻，然后立刻丢掉比喻

把镜像想成「透明胶片叠在一起」是可以入门的：每一张胶片只画了相对上一张的差异，叠起来才是完整画面。但务必马上补上三句精确话，否则后面会和「目录」搅在一起：

1. **层是只读的文件系统快照差分（diff）**，通常以 tar + gzip/zstd 的 blob 存在镜像仓库里，用内容哈希寻址。
2. **运行时**由 OverlayFS（或同类联合文件系统）把多层 **union mount** 成一个根目录 `/`。
3. **镜像 Config**（JSON）另外记录 `Env`、`Cmd`、`Entrypoint`、`WorkingDir`、`User` 等。它们常常 **不** 对应一个新的 tar 层。

所以「一层」在口语里其实混用了两个东西：

| 人们口头说的「层」 | 实际对应 | 怎么看见 |
|-------------------|----------|----------|
| 历史层 / 构建步骤 | `history` 里的一条记录 | `docker history <image>` |
| 文件系统层 / RootFS | `RootFS.Layers` 里的一串 sha256 | `docker inspect` 的 `RootFS` |

`ENV`、`CMD` 往往只增加 history，不增加 RootFS。这就是「我明明写了 15 条指令，inspect 却只有 8 个 Layer」的原因。

## 3.2 联合挂载：同一路径上后层覆盖前层

假设：

- 层 A：`/application/BOOT-INF/lib/spring-core.jar`（依赖）
- 层 B：`/application/BOOT-INF/classes/com/example/App.class`（业务 class）
- 层 C：删除了某个旧 jar（白出文件）或覆盖了同名文件

进程看到的 `/application` 是 **合成视图**。层 B 并不需要「在层 A 旁边再创建一个叫 application 的新文件夹层级」——它只是往 **同一个路径** 上再叠 diff。

删除文件时，上层放一个 whiteout 标记：「这个名字在合成视图里消失」。**下层 tar 里的字节仍在镜像里**。所以「下一层 `rm -rf /var/lib/apt/lists`」减的是合成视图，减不了已经推送到仓库的下层体积。这也是「安装和清理必须同一条 `RUN`」的根因。

## 3.3 容器层：镜像层永远只读

容器启动时，在镜像栈顶再加 **一层可写的容器层**。进程写 `/tmp`、写日志，都落在这层。容器删除，这层默认扔掉（除非卷）。这就是为什么：

- 应用数据不要写进容器层，要挂卷或外部存储；
- 镜像构建阶段要尽量把能确定的文件写进镜像层，而不是指望容器第一次启动再去下载依赖。

## 3.4 层与构建缓存

构建器从上到下执行。对每一条指令问：缓存里有没有「同样的父层 + 同样的指令指纹」？

- `RUN`：指纹主要是 **命令字符串**（外加父层 id）。容器里文件变不变，默认不看。所以 `RUN apt-get update` 会一直命中缓存，直到你改命令或打爆前面的层。
- `COPY`/`ADD`：指纹包含 **源文件内容的校验**（mtime 不参与；内容变了才 miss）。
- 一旦 miss，**后面默认全 miss**。

层因此既是 **分发粒度**（仓库按层拉取，已有层可跳过），也是 **构建加速粒度**（缓存命中则跳过执行）。

### 本章小结

层 = 只读差分 +（可选）历史记录；目录 = 联合挂载后的路径。缓存按指令顺序连锁，体积按差分累积。理解这两点，才谈得上层顺序。

---

# 四、哪些命令可以改变 / 创建层？

先给一张「会不会产生 **文件系统层（RootFS diff）**」的实用表，再解释边界情况。

## 4.1 指令分类表

| 指令 | 是否通常产生新的 RootFS 层 | 是否出现在 `docker history` | 说明 |
|------|---------------------------|-----------------------------|------|
| `FROM` | 引入基础镜像已有的全部文件系统层；本阶段以此为底 | 是 | 换基础镜像 = 换整座地基 |
| `RUN` | **是** | 是 | 构建期改磁盘的主手段 |
| `COPY` | **是** | 是 | 即使拷 1 个小文件也是一层 |
| `ADD` | **是** | 是 | 解 tar 时，解开的内容进入这一层 |
| `WORKDIR` | **目录已存在：通常否；不存在而创建：是** | 是 | 创建目录是一次落盘 |
| `ENV` | 否（写 Config） | 是 | 不改文件树，但影响后续缓存键 |
| `ARG` | 否 | 可能被记录 | 未使用的 ARG 对缓存影响小；被 `RUN` 使用则牵动后续 |
| `LABEL` | 否（写 Config） | 是 | |
| `EXPOSE` | 否 | 是 | 只是声明 |
| `VOLUME` | 可能创建挂载点目录 | 是 | 更重要的是给该路径打上 volume 元数据 |
| `USER` | 否 | 是 | 只改 Config 里的 User |
| `CMD` | 否 | 是 | 只改 Config |
| `ENTRYPOINT` | 否 | 是 | 只改 Config |
| `HEALTHCHECK` | 否 | 是 | |
| `STOPSIGNAL` | 否 | 是 | |
| `SHELL` | 否 | 是 | 改后续 shell 形式指令的解释器 |
| `ONBUILD` | 否（延迟到下游） | 是 | |
| `#` 注释 / `# syntax=` | 否 | 否 | |

口诀：**改文件树的才是「真正的层」：`RUN`、`COPY`、`ADD`（以及会创建目录的 `WORKDIR`）。其余多数是给镜像打标签、定启动方式。**

## 4.2 「改变层」的两种含义

1. **创建新层**：再提交一份 diff。`RUN`/`COPY`/`ADD` 每次执行成功都会这样做。
2. **让已有层缓存失效 / 在合成视图里覆盖旧文件**：后面的 `COPY` 用同名路径覆盖前面拷进去的文件；`RUN rm` 在新层白出文件。你没有「编辑旧层」，你只是叠了一层新 diff。

没有指令能原地修改已经提交的历史层。这是镜像可复现、可内容寻址的前提。

## 4.3 一条 `RUN` 里的多个命令仍是一层

```dockerfile
RUN mkdir -p /app/bin /app/lib /app/config \
    && chown -R spring:spring /app
```

这里建了三个目录，但 **只产生一层**。层的个数跟 `mkdir` 的个数无关，跟 **提交点（指令）** 的个数有关。

## 4.4 多阶段：层的生命周期

builder 里 `RUN java -Djarmode=tools ... extract` 会产生 builder 阶段的层。runtime 阶段 `COPY --from=builder ...` 是 **把那些文件重新打包成 runtime 的新层**，而不是「共享 builder 的那一层 id」。因此：

- 提取时的临时 `application.jar`（uber jar）如果只存在于 builder，不会自动出现在 runtime；
- runtime 有几条 `COPY`，最终镜像就多几层应用相关 RootFS（再加上基础 JRE 自带的层）。

### 本章小结

真正创建文件系统层的是 `FROM`（引入）、`RUN`、`COPY`、`ADD`，外加会 mkdir 的 `WORKDIR`。`CMD`/`ENTRYPOINT`/`ENV` 改变的是启动契约和配置，不是又一块 tar。想控制层，就控制这些指令的 **条数、内容和顺序**。

---

# 五、层创建顺序是不是和创建目录的顺序一样？

**不是。这是写 Dockerfile 时最常见的概念错位。**

## 5.1 两套完全不同的「顺序」

| | 层顺序 | 目录顺序（文件系统树） |
|---|--------|------------------------|
| 由什么决定 | Dockerfile **指令从上到下的书写/执行顺序** | 各层 diff 里的 **路径名**，经 OverlayFS 合成 |
| 时间线 | 构建时：先提交的层在下，后提交的层在上 | 无时间线：`/application/lib` 不会因为「后拷」就变成「右边的文件夹」 |
| 可否交叉 | 后层可以给前层已有目录 **追加/覆盖/删除** 文件 | 目录只是路径；谁先 mkdir 不重要 |
| 典型误解 | 「先 `mkdir /app`，再 `mkdir /app/lib`，于是先有 app 层再有 lib 层」 | 两个 mkdir 写在同一条 `RUN` 里时，甚至只有一层 |

## 5.2 用三张图把关系钉死

**（1）目录创建顺序 ≠ 层顺序**

```dockerfile
WORKDIR /application          # 可能因创建 /application 而产生很薄的一层
COPY deps/     /application/  # 层 L1：往 /application 里放依赖
COPY app/      /application/  # 层 L2：仍往同一个 /application 里放 class
```

合成视图：

```
/application
  ├── BOOT-INF/lib/....     ← 来自 L1
  └── BOOT-INF/classes/.... ← 来自 L2
```

L1 和 L2 不是 `/application` 旁边的两个文件夹，而是 **同一棵树的两张胶片**。

**（2）先建子目录、后建父目录？在 Dockerfile 里几乎不会以「层」的方式发生**

`COPY a/b/c.txt /application/x/y/c.txt` 时，构建器会在 **这一层的 diff 里** 带上需要的中间目录项。你不必先有一层专门 `mkdir`。即使你真的先 `RUN mkdir -p /application/x`，那也只是「更早的一层 diff 里有一个空目录 inode」，后面的 `COPY` 仍然往同一路径叠文件。

**（3）Spring Boot 官方四条 COPY 的顺序，是缓存顺序，不是目录树顺序**

```dockerfile
COPY --from=builder /builder/extracted/dependencies/ ./
COPY --from=builder /builder/extracted/spring-boot-loader/ ./
COPY --from=builder /builder/extracted/snapshot-dependencies/ ./
COPY --from=builder /builder/extracted/application/ ./
```

这四条都在 `WORKDIR /application` 下展开。磁盘上看仍是一套 `application.jar` + `lib/` 等 **同一工作目录** 的布局。之所以 **dependencies 写在最前、application 写在最后**，是因为：

- 第三方依赖很少变 → 希望永远命中缓存、仓库里那一层 blob 长期复用；
- 你自己的 class/资源每次发版都变 → 只让最后一层 miss。

如果把 `application` 拷在最前面，每一次业务代码变更都会让后面的依赖 `COPY` 全部失效，等于分层白做。

## 5.3 和「在 Linux 上先 mkdir 再 cp」的对比

本机脚本：

```bash
mkdir -p /app/lib
cp a.jar /app/lib/
```

这是 **同一块可写磁盘** 上的两次元数据操作，没有「提交层」。

Dockerfile：

```dockerfile
RUN mkdir -p /app/lib
COPY a.jar /app/lib/
```

这是 **两次提交**：第一层可能只有空目录，第二层才有 jar。空目录层几乎没有收益，还多一次缓存节点。优秀实践是：**不要为了目录结构去叠层；让 `COPY` 自己带上路径。**

## 5.4 可观察实验（建议自己做一次）

```bash
docker build -t layer-demo .
docker history layer-demo
docker inspect layer-demo --format '{{json .RootFS.Layers}}'
```

你会看到：history 行数 ≥ RootFS 层数；`WORKDIR`/`ENV`/`CMD` 常常只出现在 history。再用临时容器看树：

```bash
docker run --rm layer-demo find /application -maxdepth 3
```

树是合成的，看不出「哪一层先 mkdir」。要看层内容需要 `docker save` 解 blob，或 build 时 `--progress=plain` 观察每步提交。

### 本章小结

**层顺序 = 指令顺序；目录顺序 = 路径在联合文件系统中的位置。** 为缓存去排列 `COPY`/`RUN`，而不是为「先有文件夹再有文件」去排列层。Spring Boot 四条 `COPY` 共享同一个 `/application`，只是把「稳/变」切成不同胶片。

---

# 六、层创建顺序：建议与优秀实践

原则只有一句，后面全是它的推论：

> **按变更频率从低到高排列会改 RootFS 的指令；把同一次逻辑变更的副作用收进同一层；把不该分发的东西留在其他阶段。**

## 6.1 推荐的通用骨架顺序（单个运行阶段内）

从上到下：

1. `FROM` 钉死的基础镜像（极少变）
2. 系统包、时区、非 root 用户的创建（很少变）——一条或少数 `RUN`
3. 依赖描述文件（`pom.xml` / `gradle.lockfile` / `package.json`）的 `COPY` + 下载依赖的 `RUN`（中频）
4. 源码或构建产物里 **稳定的第三方**（低频于业务代码）
5. 业务代码 / 资源文件（高频）
6. `USER`、`EXPOSE`、`HEALTHCHECK`（元数据，放后面不影响 RootFS 体积）
7. `ENTRYPOINT` / `CMD`

对应 Spring Boot **已经在宿主机 `mvn package` 完**、镜像内不再编译的场景，骨架收成：

1. `FROM` JRE
2. `WORKDIR`
3. 创建用户
4. 按官方顺序 `COPY` 四个 extracted 目录（dependencies → spring-boot-loader → snapshot-dependencies → application）
5. `USER`
6. `EXPOSE` + `ENTRYPOINT`

## 6.2 做对缓存的几条硬规则

1. **不要过早 `COPY . .`**。上下文里任何一个文件变化都会让这层以及之后全部重做。先拷 `pom.xml` 再 `mvn dependency:go-offline`，最后才拷 `src`。
2. **`.dockerignore` 当作缓存卫兵**。忽略 `.git`、`README.md`、`.idea`、测试产出；若采用「只拷 jar」的模式，甚至可以忽略整个 `src`，只放行 `target/*.jar`。
3. **`ARG` 放在真正需要它的指令附近**。文件顶部写 `ARG BUILD_DATE=$(date)` 这类每次不同的值，会从使用处开始屠宰缓存。
4. **合并「安装 + 清理」**，拆开「无关的关注点」。`apt-get` 的 update/install/rm lists 必须同层；但「安装 nginx」和「拷贝业务 jar」不要硬揉成一条，否则改 jar 还要重装 nginx。
5. **多阶段比「在同一阶段 rm 构建工具」更干净**。`rm` 去不掉下层字节。
6. **COPY 的源越具体越好**。`COPY target/app.jar` 优于 `COPY target/`，更优于 `COPY . .`。

## 6.3 层不是越少越好，也不是越多越好

| 倾向 | 好处 | 代价 |
|------|------|------|
| 把所有 `RUN` 合成一条 | 镜像更小、层更少 | 改一个小命令就重跑全部安装，缓存极差 |
| 每个 `mkdir` 一条 `RUN` | 无 | 层碎片、构建变慢、毫无缓存收益 |
| **按变更频率切层** | 缓存命中率高、仓库增量拉取小 | 层数适中，这是目标态 |

Spring Boot 把 fat jar 拆成 4 个 Docker 层，正是「略多几层、换增量分发」的典范：依赖层可能 80MB 且一个月不变；application 层可能 500KB 但每天发版。

## 6.4 与目录相关的实践（再次强调）

- 不为目录结构单独 `RUN mkdir`，除非你真的需要特殊权限的空目录。
- 同一目标目录可以、也 **应该** 被多次 `COPY` 填充——这是分层，不是「复制了两份 /application」。
- 不要幻想「先 COPY 子目录层、再 COPY 父目录层」能表达树的层级；父路径上的后一次 `COPY` 若包含同名文件，会 **覆盖** 而不是「包在外面」。

## 6.5 安全与可运维（顺序里顺手做掉）

- 包安装在 root 下完成，**最后** `USER` 非 root。
- 不把 `secret` 写成 `ENV`/`ARG`；K8s 用 Secret 挂文件或注入。
- exec 形式 `ENTRYPOINT`，保证优雅停机。
- 基础镜像选 JRE/distroless，并定期重建以吃进 CVE 修复——这会换 `FROM` 层，属于健康的「地基更新」，不要为了缓存永远不升级基础镜像。

## 6.6 反面教材（层顺序）

```dockerfile
FROM eclipse-temurin:21-jdk
COPY . /src
RUN apt-get update && apt-get install -y maven && cd /src && mvn -q package
COPY pom.xml /src/pom.xml
CMD java -jar /src/target/*.jar
```

问题：JDK+Maven 进运行镜像；先 `COPY .` 导致改一行 Java 就重装 Maven；`CMD` 用 shell 通配且 PID 1 行为差；`pom.xml` 的 COPY 写在编译之后毫无缓存意义。

### 本章小结

层顺序的优秀实践是 **频率排序 + 同层收口 + 多阶段丢弃**，不是「先建目录后拷文件」，也不是「层越少越专业」。

---

# 七、基于 Spring Boot 4.1.1 的写作示例

本章给出三份可落地的 Dockerfile：① 与官方文档对齐的 4.1.1 标准写法；② JDK 21 保守生产变体；③ 需要在镜像内完成编译的多阶段写法。并补上 `pom.xml`、`.dockerignore` 与错误对照。

官方文档强调：Boot 打出的 uber jar 里带有 layer index 和 `spring-boot-jarmode-tools`。用 `jarmode=tools` 提取后，运行阶段不再跑那只胖 jar，而是跑 **按层展开后的瘦 `application.jar`**，对 AOT cache / CDS 友好。

## 7.1 构建产物侧：确认分层 JAR 已打开

Spring Boot 2.4 之后分层默认开启；4.1.1 保持该默认。Maven 插件仍建议显式写清楚，避免被父 POM 之外的定制关掉：

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.1</version>
    <relativePath/>
</parent>

<properties>
    <java.version>21</java.version>
</properties>

<build>
    <plugins>
        <plugin>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-maven-plugin</artifactId>
            <configuration>
                <layers>
                    <enabled>true</enabled>
                </layers>
                <imageName>demo-service:${project.version}</imageName>
            </configuration>
        </plugin>
    </plugins>
</build>
```

本机验证层（无需 Docker）：

```bash
mvn -q -DskipTests package
java -Djarmode=tools -jar target/demo-service-0.0.1-SNAPSHOT.jar list-layers
```

预期会列出类似：

```
dependencies
spring-boot-loader
snapshot-dependencies
application
```

（若有自定义 `layers.idx`，名称以你的配置为准。）

## 7.2 `.dockerignore`

放在 Dockerfile 同目录（通常是模块根目录）：

```gitignore
.git
.idea
*.md
src
.mvn
mvnw
mvnw.cmd
target/**
!target/*.jar
!target/*-SNAPSHOT.jar
**/application*.jar.original
```

「只把 boot 打出来的 jar 送进上下文」，构建上下文小，`COPY ${JAR_FILE}` 的缓存也干净。`.jar.original` 是 repackage 前的备份，不要拷进镜像。

## 7.3 示例 A：官方口径（Spring Boot 4.1.1 + Java 25 CDS 基础镜像）

与 [Spring Boot 4.1 Dockerfiles](https://docs.spring.io/spring-boot/reference/packaging/container-images/dockerfiles.html) 对齐，并补上非 root 用户与时区。JAR 已在 CI 里 `mvn package` 完成。

```dockerfile
# syntax=docker/dockerfile:1

# ---- builder：只负责把 uber jar 按层展开 ----
FROM bellsoft/liberica-openjre-debian:25-cds AS builder
WORKDIR /builder

# Maven 默认 target/*.jar；Gradle 改为 build/libs/*.jar
ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} application.jar

# Boot 4.x：tools 模式 + 显式 --layers
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# ---- runtime：四条 COPY = 四个可缓存的 RootFS 层 ----
FROM bellsoft/liberica-openjre-debian:25-cds
WORKDIR /application

RUN groupadd --system spring \
    && useradd --system --gid spring --home-dir /application --shell /usr/sbin/nologin spring

# 顺序不可随意颠倒：越稳定越靠前
COPY --from=builder --chown=spring:spring /builder/extracted/dependencies/ ./
COPY --from=builder --chown=spring:spring /builder/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=spring:spring /builder/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=spring:spring /builder/extracted/application/ ./

USER spring
EXPOSE 8080

# exec 形式：PID 1 为 java，才能吃到 K8s SIGTERM
# 启动的是提取后的瘦 jar，不是 builder 里那只 uber jar
ENTRYPOINT ["java", "-jar", "application.jar"]
```

构建：

```bash
docker build --build-arg JAR_FILE=target/demo-service-0.0.1-SNAPSHOT.jar -t demo-service:0.0.1 .
```

这四条 `COPY` 都写入 **同一个** `/application` 目录树，但提交为 **四层**。改一行业务代码，通常只有最后的 `application` 层 miss。

## 7.4 示例 B：官方 AOT cache（Java 25+，可选）

适合已经用 JDK 25、并且能接受「镜像构建时做一次训练启动」的流水线。训练运行会拉长 `docker build`，但换来进程启动加速。

```dockerfile
# syntax=docker/dockerfile:1

FROM bellsoft/liberica-openjre-debian:25-cds AS builder
WORKDIR /builder
ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM bellsoft/liberica-openjre-debian:25-cds
WORKDIR /application

COPY --from=builder /builder/extracted/dependencies/ ./
COPY --from=builder /builder/extracted/spring-boot-loader/ ./
COPY --from=builder /builder/extracted/snapshot-dependencies/ ./
COPY --from=builder /builder/extracted/application/ ./

# 训练运行：构建期多一层 app.aot；务必放在业务 COPY 之后
RUN java -XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh -jar application.jar

EXPOSE 8080
ENTRYPOINT ["java", "-XX:AOTCache=app.aot", "-jar", "application.jar"]
```

注意：AOT 训练这一步 `RUN` 依赖 application 层已就位，所以它必须在四条 `COPY` **之后**。它本身会再产生一层（`app.aot` 文件）。Java 24 及更早若仍用 CDS，把 `-XX:AOTCache*` 换成 `-XX:ArchiveClassesAtExit` / `-XX:SharedArchiveFile`；Boot 文档对 Java 25+ 明确建议用 AOT cache 而不是 CDS。

## 7.5 示例 C：JDK 21 保守生产变体（更常见的基线）

许多团队运行基线仍是 21。提取分层只需要能跑 `java`，JRE 足够。

```dockerfile
# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jre AS builder
WORKDIR /builder
ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:21-jre
WORKDIR /application

ENV TZ=Asia/Shanghai \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -Duser.timezone=Asia/Shanghai" \
    SPRING_PROFILES_ACTIVE=prod

RUN groupadd --system spring \
    && useradd --system --gid spring --home-dir /application --shell /usr/sbin/nologin spring

COPY --from=builder --chown=spring:spring /builder/extracted/dependencies/ ./
COPY --from=builder --chown=spring:spring /builder/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=spring:spring /builder/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=spring:spring /builder/extracted/application/ ./

USER spring
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "application.jar"]
```

`JAVA_TOOL_OPTIONS` 让 K8s 里不改镜像也能靠环境变量追加 `-D` / GC 参数；不要把堆写成绝对 `-Xmx2g`，容器里用百分比更适合。

## 7.6 示例 D：镜像内 Maven 编译（无 CI 预构建时）

仅在本地快速试镜像、或不便先 package 时使用。注意 `pom.xml` 先于源码拷贝。

```dockerfile
# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
RUN chmod +x mvnw \
    && ./mvnw -q -DskipTests dependency:go-offline

COPY src src
RUN ./mvnw -q -DskipTests package \
    && java -Djarmode=tools -jar target/*.jar extract --layers --destination /extracted

FROM eclipse-temurin:21-jre
WORKDIR /application
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "application.jar"]
```

层顺序在这里体现为：依赖下载那条 `RUN` 在 `COPY src` 之前。改 Java 代码不会重拉 Maven 仓库（在 BuildKit 缓存与 `pom.xml` 未变的前提下）。

## 7.7 不要再当首选的旧写法（对照）

Boot 2.x/3.x 教程大量出现下面两段，4.1.1 **仍可能能跑**，但不应作为新项目模板：

```dockerfile
# 旧：整只 fat jar 一层——改一行业务也要重推全部依赖
FROM eclipse-temurin:21-jre
COPY target/demo-service-0.0.1-SNAPSHOT.jar app.jar
ENTRYPOINT ["java","-jar","/app.jar"]
```

```dockerfile
# 旧 jarmode 名称；4.0 起 layertools 已弃用，4.1 应改用 tools
RUN java -Djarmode=layertools -jar application.jar extract
ENTRYPOINT ["java","org.springframework.boot.loader.launch.JarLauncher"]
```

4.1 官方提取布局下，启动命令是 **`java -jar application.jar`**（瘦 jar + 旁边的依赖目录），而不是手写 `JarLauncher`。`JarLauncher` 的包名在 Boot 3.2+ 也曾从 `org.springframework.boot.loader.JarLauncher` 迁到 `...loader.launch.JarLauncher`，这正是「跟官方提取布局走」比「背启动类名」更稳的原因。

## 7.8 和 K8s 的最小对接（本目录读者常见下一步）

Dockerfile 写完后，K8s 侧只需约定：

- 容器端口与 `EXPOSE` 一致（8080），`readinessProbe` 打 `/actuator/health/readiness`（需引入 actuator）；
- `terminationGracePeriodSeconds` 配合 Spring `server.shutdown=graceful`；
- 配置用 `ConfigMap`/`Secret`，不要 `ENV` 写死密码；
- 资源 `requests/limits` 与 `MaxRAMPercentage` 一起看，避免 JVM 按宿主机内存估堆。

这些已超出 Dockerfile 正文，但决定了「镜像层写对了，进程能不能活得好」。

### 本章小结

Spring Boot 4.1.1 的推荐写法是：**宿主机或 builder 里 `jarmode=tools extract --layers`，runtime 按 dependencies → spring-boot-loader → snapshot-dependencies → application 四次 `COPY`，`ENTRYPOINT ["java","-jar","application.jar"]`。** 四次 COPY 共享同一工作目录，层顺序服从缓存，而不是服从 mkdir。

---

# 八、贯通视图：从一条指令到一次发版

一次典型发版里，各概念如何咬合：

```
开发改 com.example.Foo.java
        │
        ▼
mvn package  →  uber jar（内含 layers.idx + 依赖 + 你的 class）
        │
        ▼
docker build
  builder: COPY jar（几乎每次 miss）→ RUN extract（快）
  runtime: COPY dependencies     → 命中（依赖没变）
           COPY spring-boot-loader → 命中
           COPY snapshot-dependencies → 通常命中
           COPY application      → miss（只有这一层重做、重推）
        │
        ▼
仓库按层去重：集群里已有 dependencies 层的节点只拉 application 那一小层
        │
        ▼
K8s 滚动：新 Pod 用新镜像；PID 1=java 收到 SIGTERM；旧层仍被旧 ReplicaSet 引用直到收掉
```

对照第一章的四句话：指令有结构、镜像是层栈、缓存连锁失效、最终镜像只留运行所需——在 4.1.1 这条链路上全部能对上号。

---

# 附录：指令速查（写作时贴在屏幕边）

```dockerfile
# syntax=docker/dockerfile:1          # 解析器，可选但推荐
FROM image:tag AS name                # 阶段起点，引入基础层
ARG KEY=default                       # 仅构建时
ENV KEY=value                         # 构建 + 运行
WORKDIR /abs/path                     # 后续相对路径的根
RUN command && cleanup                # 创建文件系统层
COPY [--from=stage] [--chown=u:g] s d # 创建文件系统层
ADD src dest                          # 少用；tar/远程才考虑
USER nonroot                          # Config
EXPOSE 8080                           # 声明
LABEL key=value                       # Config
VOLUME /tmp                           # 慎用；K8s 常用 Pod 卷代替
HEALTHCHECK CMD ...                   # Docker 侧探活；K8s 不用它
ENTRYPOINT ["exec","form"]            # 主进程
CMD ["default","args"]                # 默认参数
```

**层顺序口诀**：地基 → 系统 → 依赖 → 业务 → 用户与启动。  
**目录口诀**：目录是路径，不是层；同一路径可以叠多层胶片。  
**Boot 4.1.1 口诀**：`tools` 提取四层，`java -jar application.jar` 启动。
