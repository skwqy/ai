# 技术学习文档库

个人后端技术深度学习文档库。内容以「源码级深度解析」为主，风格上**白话先行、附证据链**：先建立直觉，讲清"是什么、为什么"，再带着版本号和源码定位逐层拆解，作为长期维护的资料库持续更新。

## 📚 目录结构

| 目录 | 主题 | 主要内容 |
|---|---|---|
| `spring/` | Spring 全家桶 | Framework / Boot 深度源码解析、Spring Cloud vs K8s 选型对比、观测体系架构设计、Spring 全项目生态全景 |
| `jdk/` | JDK 核心机制 | 响应式流（Flow）与 CompletableFuture 对比、线程池与定时任务调度、Doug Lea NIO 讲义 |
| `netty/` | 网络编程 | Netty 深度分析（基于 netty-4.2.18.Final 源码）、CompletableFuture 与 Netty Future/Promise 模型剖析 |
| `k8s/` | 容器编排 | Kubernetes 整体架构详解、iptables 原理及其在 K8s 中的应用 |
| `cncf/` | 云原生生态 | CNCF 关键项目全景、Cilium / IPVS / OpenTelemetry 系列深度指南（规范、Java 源码、Java Agent 与 SpringBoot 实战） |
| `harness/` | AI 基础设施 | 向量数据库原理详解 |
| `mq/` | 消息中间件 | RocketMQ 深度源码解析（基于 rocketmq 5.5.x 源码，含"消息不丢、不重"可靠性专题） |
| `linux/` `tools/` | 规划中 | 待补充 |

## ✍️ 文档特色

- **白话先行**：每个主题先讲直觉和全景，再深入源码细节，对初学者友好
- **证据链**：关键结论附带源码位置与具体版本（如 netty-4.2.18.Final），可回溯验证
- **配图**：架构图、生态地图、演进时间线等原创配图（见 `spring/img/`）

## 🔄 说明

- 持续学习中，文档会不定期更新，直接浏览对应目录即可
- 个人学习笔记，如有错误欢迎提 Issue 指正
