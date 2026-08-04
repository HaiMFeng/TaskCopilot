# TaskCopilot

> 无屏小主机的定时任务调度与远程管理助手。

TaskCopilot 是面向「无显示器小主机 / NAS / 软路由」等场景的轻量级任务调度与管理面板。
通过网页即可集中管理定时任务、查看主机状态、远程操作终端、实时查看主机屏幕，无需接显示器或 SSH。

---

## ✨ 功能特性

- **仪表盘**：实时展示主机名、CPU / 内存 / 磁盘 / 网络负载、运行时长、本机访问地址等。
- **日程表（Schedule）**：将任务分组到多个日程表中，任一时刻仅一个日程表处于「启用」状态；
  单击切换选中、双击启用、右键删除。支持「不启用」以暂停全部调度。
- **任务管理**：
  - 多种任务类型（应用启动、URL 请求、文件/脚本执行等），由后端 `TaskTypeService` 动态提供 schema。
  - 启用 / 禁用切换、手动执行、拖拽排序、查看执行历史与日志。
  - 配置项含路径校验（如可执行文件路径存在性检查）。
- **终端**：网页内嵌 CMD / PowerShell 终端，支持启动 / 停止、命令输入、回车发送、Ctrl+C 中断，只读输出回显。
- **屏幕查看**：通过 `java.awt.Robot` 截取主机屏幕，按可选清晰度（流畅 / 标准 / 清晰 / 原画）以 1 秒间隔轮询刷新；
  进入页面自动开始、离开自动停止，支持 `java -jar` 无头环境（已关闭 Spring Boot 默认 headless）。
- **关于**：项目简介、作者信息、版本号（点击复制）、项目主页、MIT 协议与反馈入口。
- **移动端适配**：响应式布局，模式切换在移动端以选择框呈现；工具栏、图标风格 PC / 移动端统一。
- **零前端构建**：Vue 3 + Element Plus + Font Awesome 以本地静态资源引入，开箱即用、可离线；API 层已隔离，后续可平滑迁移至 Vue 3 + Vite。

---

## 🧱 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Spring Boot 3、Spring MVC、Spring Data JPA、H2（文件型数据库）、Lombok |
| 调度 | Spring `TaskScheduler` + `ScheduledTaskRegistrar`，基于 Cron 表达式 |
| 主机信息 | OSHI（系统硬件信息）、`java.awt.Robot`（屏幕截图） |
| 前端 | Vue 3 + Element Plus（经 `/vendor` 本地引入，无需 CDN/构建）+ Font Awesome 图标 |
| 构建 | Maven（`./mvnw`） |

---

## 🚀 快速开始

### 环境要求
- JDK 17+
- Maven 3.8+（或使用仓库内置 `mvnw`）

### 构建与运行
```bash
# 1. 打包
./mvnw clean package
#   或 Windows：
mvnw.cmd clean package

# 2. 运行（jar 方式，推荐用于无头小主机）
java -jar target/TaskCopilot-0.0.1-SNAPSHOT.jar
```

启动后默认监听 `http://<主机IP>:8080`（端口见 `application.properties` 的 `server.port`）。

> 屏幕查看功能依赖桌面图形环境。若以 `java -jar` 在无显示器环境运行，
> 项目已在 `TaskCopilotApplication.main()` 与 `application.properties` 中显式关闭 headless，
> 以便 Robot 可取屏。

### 开发模式（IDE 运行）
直接用 IDE 运行 `TaskCopilotApplication` 即可；无需额外参数。
前端为静态资源，修改 `src/main/resources/static/**` 后刷新浏览器即可（已通过 `?v=` 版本号规避缓存）。

---

## 📡 HTTP API 一览

基础前缀：`/api`

### 系统 `SystemController`（`/api/system`）
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/info` | 基础信息（版本、主机名等） |
| GET | `/dashboard` | 仪表盘聚合数据（CPU/内存/磁盘/网络/任务概览） |
| GET | `/network` | 网卡与流量信息 |
| GET | `/network-config` | 本机 IP、DNS、网关、链路速度 |
| GET | `/scheduler` | 调度器状态（运行中 / 暂停） |
| POST | `/scheduler/pause` | 暂停调度 |
| POST | `/scheduler/resume` | 恢复调度 |
| GET | `/processes` | 进程列表（可选过滤） |
| POST | `/display-name` | 更新主机显示名 |
| POST | `/check-path` | 校验文件路径是否存在 |

### 日程表 `ScheduleController`（`/api/schedules`）
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` | 列表（含每表任务数） |
| GET | `/current` | 当前启用的日程表（`null` 表示未启用） |
| GET | `/{id}` | 详情 |
| POST | `/` | 新建 |
| PUT | `/{id}` | 更新 |
| POST | `/{id}/activate` | 启用该表（互斥，其余置为非启用） |
| POST | `/deactivate` | 全部停用（对应「不启用」） |
| DELETE | `/{id}` | 删除 |

### 任务 `TaskController`（`/api/tasks`）
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` | 列表（可按日程表过滤） |
| GET | `/{id}` | 详情 |
| POST | `/` | 新建 |
| PUT | `/{id}` | 更新 |
| DELETE | `/{id}` | 删除 |
| PATCH | `/{id}/toggle` | 启用/禁用切换 |
| POST | `/{id}/execute` | 立即执行 |
| PUT | `/sort` | 批量更新排序 |
| GET | `/{id}/logs` | 执行日志 |

### 任务类型 `TaskTypeController`（`/api/task-types`）
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` | 全部任务类型及其配置 schema |

### 终端 `TerminalController`（`/api/terminal`）
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/state` | 终端运行状态 |
| GET | `/output` | 历史输出片段 |
| POST | `/start` | 启动终端（指定 shell：CMD / PowerShell） |
| POST | `/stop` | 停止终端 |
| POST | `/input` | 发送命令 |
| POST | `/interrupt` | 发送中断（Ctrl+C） |

### 屏幕 `ScreenController`（`/api/screen`）
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/?quality=0.5` | 返回 JPEG 截图（响应头 `X-Screen-Size` 携带分辨率）；服务不可用时返回 503 |

---

## 🗂 目录结构

```
src/main/java/io/github/haimfeng/taskcopilot/
├── TaskCopilotApplication.java      # 启动类（显式关闭 headless）
├── config/                          # Spring 配置（调度、JPA 等）
├── controller/  → web/              # HTTP 接口层（见上 API 一览）
├── service/                        # 业务逻辑（调度、任务、终端、屏幕、日程表）
├── model/                          # 实体与 DTO
├── repository/                     # JPA 仓储
└── scheduler/                      # 调度引导与执行

src/main/resources/
├── application.properties           # 端口、H2、headless=false 等
└── static/                         # 前端（index.html / js / css / vendor / webfonts）
```

---

## 📄 许可证

本项目基于 **MIT License** 开源，详见仓库根目录的 [`LICENSE`](./LICENSE) 文件。

## 致谢
- **Vue 3** —— 渐进式前端框架，本项目 UI 的响应式基础。
- **Element Plus** —— 基于 Vue 3 的组件库（消息提示、对话框、选择器等），开箱即用的桌面级体验。
- **Font Awesome** —— 丰富的图标资源，为各界面提供一致的视觉标识。
- **Spring Boot / Spring Data JPA / H2 / Lombok / OSHI** —— 稳健的后端与主机信息底座。
- **CodeBuddy** —— 由腾讯开发的 AI 编程助手。本项目的界面重构、功能开发（屏幕监视、移动端适配等）与文档整理均在 CodeBuddy 的协作下完成，特此感谢。

---
⏱ TaskCopilot · 让无屏小主机的定时任务管理更简单。
