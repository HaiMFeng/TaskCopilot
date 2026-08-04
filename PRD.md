# TaskCopilot 产品需求文档（PRD）

> 版本：v2026.08（随实现持续更新）
> 定位：无屏小主机 / NAS / 软路由的定时任务调度与远程管理面板。

---

## 1. 产品概述

### 1.1 背景
无显示器的「小主机」常需执行定时任务（启动应用、拉取数据、执行脚本等），但缺乏易用的可视化管理手段，
传统方式依赖 crontab / Windows 计划任务 + SSH，对非专业用户门槛高。

### 1.2 目标
提供一个**网页端**集中面板，让用户无需接显示器或 SSH 即可：
- 管理定时任务与分组（日程表）
- 实时掌握主机资源与运行状态
- 远程执行命令、查看屏幕
- 在手机上也能完成基本操作

### 1.3 用户画像
- 个人开发者 / 极客，拥有 1~数台长期开机的小主机
- 希望「设一次、长期跑」，并能在出问题时远程查看与干预

---

## 2. 功能需求

### 2.1 仪表盘（Dashboard）
- 主机名（可编辑显示名）
- CPU / 内存 / 磁盘 使用率与容量
- 网络：本机访问地址、DNS、网关、链路速度、实时上下行速率
- 任务概览：运行中日程表、任务总数、启用/禁用数、日程表数
- 调度器状态：运行中 / 已暂停（可暂停/恢复）

### 2.2 日程表（Schedule）
- 一个任务归属一个日程表；**全系统任一时刻至多一个日程表处于「启用」状态**（互斥）。
- 操作：
  - 单击 → 选中并加载其任务
  - 双击 → 启用该日程表（其余自动停用）
  - 右键 → 删除（虚拟「不启用」项不可删）
  - 点击「不启用」→ 全部停用，暂停调度
- 新建日程表、命名、查看任务数。
- **持久化约束**：用户显式「不启用」的状态会被保留；刷新后不会自动重新启用某表。
- 服务端 `/api/schedules/current` 在无一表启用时返回 `null`，前端据此保持「不启用」。

### 2.3 任务管理（Task）
- 任务类型由后端 `TaskTypeService` 提供 schema（动态表单），当前含：
  - 应用启动（路径校验、参数、工作目录、是否隐藏窗口）
  - URL 请求（GET/POST、Header、Body、超时）
  - 文件/脚本执行（解释器、脚本路径、参数）
  - （更多类型可在 `TaskTypeService` 注册）
- 字段级校验：如可执行文件路径存在性由 `/api/system/check-path` 校验。
- 操作：启用/禁用切换、手动执行、拖拽排序、查看执行历史与日志。
- 排序：通过 `PUT /api/tasks/sort` 持久化顺序。

### 2.4 终端（Terminal）
- 内嵌网页终端，支持 CMD 与 PowerShell 两种 shell。
- 启动 / 停止、命令输入框（回车发送）、Ctrl+C 中断。
- 只读输出回显（不回传敏感交互式密码输入）。
- 后端以独立进程方式启动 shell 并桥接 stdin/stdout。

### 2.5 屏幕查看（Screen）
- 通过 `java.awt.Robot` 截取主机主屏（使用逻辑分辨率，避免高 DPI 缩放黑边）。
- 清晰度可选：流畅(0.3) / 标准(0.5) / 清晰(0.7) / 原画(0.9)，对应 JPEG 质量。
- 前端 1 秒间隔轮询；进入页面自动开始、离开（`onBeforeUnmount` / 切走）自动停止。
- 无头环境（如 `java -jar` 于无显示器主机）：已在启动类与 `application.properties` 中关闭 headless。
- 接口：`GET /api/screen?quality=` 返回 JPEG，响应头 `X-Screen-Size` 携带分辨率；不可用时 503。

### 2.6 关于（About）
- 一句话简介、作者（HaiMFeng 海明风）、主页、邮箱。
- 版本号（点击复制，始终可用）。
- 项目主页、MIT 协议、反馈入口（Issues）链接。
- 简短致谢。

### 2.7 移动端
- 响应式布局；模式切换在窄屏以选择框（`el-select`）呈现。
- 日程表 → 任务列表 → 详情以底部面板弹出。
- 图标、工具栏风格 PC / 移动端统一（accent 蓝色图标 + 主文本色）。

---

## 3. 非功能性需求

- **可离线**：前端依赖本地 `vendor` / `webfonts`，无外部 CDN。
- **零前端构建**：原生静态资源，直接由 Spring Boot 托管。
- **轻量**：单 Jar 运行，H2 文件型数据库，无外部中间件。
- **可演进**：API 层与 UI 解耦，预留迁移至 Vue 3 + Vite 的空间。

---

## 4. 技术架构

### 4.1 后端
- Spring Boot 3 + Spring MVC
- 持久化：Spring Data JPA + H2（文件型，`ddl-auto=update`）
- 调度：`TaskScheduler` + `ScheduledTaskRegistrar`，Cron 驱动；`SchedulerBootstrap` 启动时加载启用日程表
- 主机信息：OSHI；屏幕：`java.awt.Robot`
- 语言：Java 17，Lombok 简化实体

### 4.2 前端
- Vue 3（CDN 本地化 `vendor/vue.global.prod.js`）+ Element Plus（`vendor/index.full.min.js`）
- Font Awesome（`vendor/fontawesome-all.min.css` + `webfonts/`）
- 单页结构：`index.html` + `js/app.js`（组合式 API `setup`）+ `js/api.js` + `css/app.css`

### 4.3 API 设计
REST 风格，JSON 为主；屏幕接口返回二进制 JPEG。完整端点见 README「HTTP API 一览」。

---

## 5. 数据模型（核心实体）

- **Schedule（日程表）**：`id, name, active(bool), sortOrder`
- **Task（任务）**：`id, scheduleId, name, typeCode, config(JSON), enabled, sortOrder, remark, lastRunAt, lastStatus`
- **TaskType（任务类型定义）**：`typeCode, typeDisplayName, schema(字段定义)`
- 运行态日志：任务执行历史（见 `GET /api/tasks/{id}/logs`）

---

## 6. 关键交互流程

1. **启用某日程表**：双击 → `POST /api/schedules/{id}/activate` → 后端停用其余 → 调度器重载该表任务。
2. **不启用**：选择「不启用」→ `POST /api/schedules/deactivate` → 全部停用 → 刷新后 `/current` 返回 `null`，保留停用状态。
3. **屏幕轮询**：进入屏幕页 → 定时器每 1s 请求 `/api/screen` → 离开清除定时器。
4. **手动执行任务**：点击执行 → `POST /api/tasks/{id}/execute` → 后端按类型构造并执行 → 写入日志。

---

## 7. 边界与约束

- 屏幕查看依赖图形环境；纯无显卡/无会话的服务器可能无法取屏（返回 503）。
- 终端为只读展示，不适合需要交互式密码输入的场景。
- H2 当前 `ddl-auto=update`，正式部署建议改用 Flyway 管理表结构。
- **许可证**：本项目基于 MIT License 开源（详见仓库根目录 `LICENSE`）。

---

## 8. 后续演进（Roadmap，非当前范围）

- 文件管理器（远程浏览/管理小主机文件）
- 多用户与鉴权
- 任务执行通知（Webhook / 邮件）
- 容器化部署（Dockerfile）
- 前端迁移至 Vue 3 + Vite 构建管线
