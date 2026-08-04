# TaskCopilot PRD

## 1. 项目概述
- **目的**：在一台无屏小主机上运行自动化服务，支持通过 Web 界面（局域网内任意设备）管理定时任务。
- **核心价值**：轻量、易扩展、低资源占用（CPU 弱但内存充足）。
- **命名**：TaskCopilot

## 2. 功能需求（MVP）

### 2.1 任务管理模式
- **模式切换（顶部滑块）**：`每日任务` 与 `立即任务` 两种模式。
    - **每日任务**（已实装）：通过"日程表（Schedule）"组织任务。
    - **立即任务**（占位）：规划中的一次性并发执行能力，当前仅展示占位页。
- **日程表（Schedule）**：把任务按场景/计划分组（如「工作日计划」「周末计划」）。
    - 支持创建多个日程表。
    - **同一时间只能有一个日程表处于「运行中（active）」**，切换时其余自动置为非运行。
    - 只有运行中日程表下的启用任务才会被自动触发；非运行日程表下的任务仍可手动执行。
    - 删除日程表时，其下任务及其执行记录一并删除。
    - 首次启动若无任何日程表，自动创建一个「默认日程表」并设为运行中。

### 2.2 任务管理（每日任务模式）
- **左右分栏布局**：
    - 左栏：当前日程表下的任务列表（名称、类型、状态、启用开关），按触发时间自动排序，同一时间的任务支持拖拽调整顺序。
    - 右栏：选中任务的详情面板，可编辑保存（名称、类型、触发配置、启用、备注），可立即执行并查看输出，可删除。
- **新建任务**：通过弹窗完成，不影响主页面布局；表单按后端返回的 schema 动态渲染。
- **任务属性**：名称、任务类型（6 种）、触发配置（按类型动态渲染，统一包含执行时间）、备注（可选）、启用/禁用。
- **立即执行**：手动触发一次任务，不受定时限制。
- **执行失败自动停用**：任务执行失败或超时后自动关闭启用开关，避免反复触发坏任务。

### 2.3 任务类型扩展机制
- **设计原则**：新增任务类型只需实现一个接口（`TaskTypeHandler`），用 `@Component` 注册为 Spring Bean，无需修改已有代码。
- **当前实现**：
    - `RUN_COMMAND` — 运行指令（命令、工作目录、超时）
    - `OPEN_APP` — 打开应用（应用路径手动输入+校验、启动参数）
    - `HTTP_REQUEST` — 发送请求（URL、方法、请求头、请求体）
    - `KILL_PROCESS` — 结束进程（进程名手动输入+选择按钮、精确/模糊匹配、正常/强制终止）
    - `SYSTEM_COMMAND` — 系统指令（关机、重启、休眠、锁屏，支持延时）
- **前端适配**：后端提供 `/api/task-types` 接口返回类型列表及对应的配置表单 schema，前端动态渲染。

### 2.4 执行与日志
- **调度引擎**：Spring `ThreadPoolTaskScheduler`（调度线程池 2 线程），执行走虚拟线程 + 信号量限流（并发上限 5）。
- **调度策略**：采用"一次性调度 + 执行后重排"；只要任务类型能算出下次触发时间即接入，天然兼容非周期类型。
- **执行过程**：`ProcessBuilder` 执行命令，设置超时，捕获 stdout/stderr。
- **日志记录**：每次执行记录到 `task_log` 表，包含开始/结束时间、退出码、输出内容、状态（SUCCESS/FAILURE/TIMEOUT）。
- **日志查看**：按任务查看最近 N 条日志，支持展开完整输出。

### 2.5 全局控制
- 一键暂停/恢复所有定时任务（用于维护）。
- 显示小主机基础系统信息（CPU、内存、磁盘、运行中日程表的调度任务数）。

## 3. 非功能性需求
- **性能**：空闲 CPU < 1%，内存 < 200MB（不含 JVM 基础）。
- **可靠性**：任务持久化到数据库，服务重启自动恢复（含运行中日程表状态）。
- **部署**：仅需 JDK 21+，`java -jar` 运行，默认 H2 嵌入式数据库（文件库 `./data/taskcopilot`）。
- **安全**：仅监听局域网地址（默认 `0.0.0.0:8080`），可添加简单 Basic Auth（可选，尚未实装）。
- **前端兼容**：Chrome/Firefox/Safari 桌面端及移动端浏览器。

## 4. 技术栈
- **后端**：Spring Boot 4.1.0 + JDK 21（虚拟线程） + Spring Data JPA + H2 + Jackson 3
- **前端**：原生 HTML/CSS/JS（无构建依赖，置于 `resources/static`），API 层已封装隔离，后续可平滑迁移至 Vue 3 + Vite。
- **构建**：Maven（`./mvnw`），前端静态文件由 Spring Boot 直接托管。

## 5. 数据模型（核心表）

### 表 `schedule`（日程表）
| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 自增 |
| name | VARCHAR(100) | 日程表名称 |
| remark | VARCHAR(255) | 备注 |
| active | BOOLEAN | 是否运行中（全局唯一） |
| sort_order | INT | 排序序号 |
| created_at / updated_at | TIMESTAMP | 时间戳 |

### 表 `task`
| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 自增 |
| name | VARCHAR(100) | 任务名称 |
| command | TEXT | 命令或脚本 |
| working_dir | VARCHAR(255) | 工作目录（可选） |
| type_code | VARCHAR(20) | 任务类型标识（如 `DAILY`） |
| config_json | TEXT | 触发配置（JSON，例如 `{"hour":8,"minute":30}`） |
| enabled | BOOLEAN | 是否启用 |
| schedule_id | BIGINT FK | 所属日程表（可空） |
| sort_order | INT | 排序序号 |
| timeout_seconds | INT | 超时秒数（可选） |
| remark | VARCHAR(255) | 备注 |
| created_at / updated_at | TIMESTAMP | 时间戳 |

### 表 `task_log`
| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 自增 |
| task_id | BIGINT FK | 关联任务 |
| trigger_source | VARCHAR(20) | SCHEDULED / MANUAL |
| started_at / finished_at | TIMESTAMP | 执行起止 |
| exit_code | INT | 退出码（-1 超时） |
| stdout / stderr | TEXT | 输出 |
| status | VARCHAR(20) | SUCCESS / FAILURE / TIMEOUT |

## 6. API 设计（RESTful）

### 日程表
- `GET /api/schedules` — 日程表列表（含任务数）
- `GET /api/schedules/current` — 当前运行中日程表（不存在则自动创建默认）
- `GET /api/schedules/{id}` — 日程表详情
- `POST /api/schedules` — 创建日程表
- `PUT /api/schedules/{id}` — 更新日程表
- `POST /api/schedules/{id}/activate` — 切换为运行中日程表（互斥）
- `DELETE /api/schedules/{id}` — 删除日程表（级联删除其下任务及日志）

### 任务
- `GET /api/tasks?scheduleId=` — 任务列表（可按日程表过滤）
- `POST /api/tasks` — 创建任务
- `PUT /api/tasks/{id}` — 更新任务
- `DELETE /api/tasks/{id}` — 删除任务
- `POST /api/tasks/{id}/execute` — 立即执行
- `PATCH /api/tasks/{id}/toggle` — 启用/禁用
- `PUT /api/tasks/sort` — 批量更新排序（`{orderedIds: [...]}`）
- `GET /api/tasks/{id}/logs?limit=50` — 查看日志

### 其它
- `GET /api/task-types` — 获取所有任务类型及配置 schema
- `GET /api/system/info` — 系统信息
- `GET /api/system/processes` — 获取运行中进程名列表（供结束进程选择）
- `POST /api/system/check-path` — 校验应用路径是否存在
- `POST /api/system/scheduler/pause` — 全局暂停
- `POST /api/system/scheduler/resume` — 全局恢复

## 7. 界面简要描述
- **模式切换滑块**：顶部 `每日任务` / `立即任务`。
- **每日任务页**：左侧日程表选择条（单击选中、双击切换运行、右键删除）+ 左右分栏。
    - 左栏任务列表：触发时间标签、名称、类型徽标、状态点、启用迷你开关，按时间自动排序，同时间任务可拖拽。
    - 右栏详情：编辑表单 + 立即执行 + 最近执行输出 + 删除。
- **新建/编辑弹窗**：动态表单（名称、类型下拉、按 schema 渲染的触发配置、备注）。保存后刷新列表。
- **立即任务页**：占位卡片（建设中）。

## 8. 开发路线图
- **M1**：后端 CRUD + 每日定时任务调度 + 日志记录；前端任务列表、添加/编辑、日志查看。
- **M2**：日程表分组与互斥激活、拖拽排序、全局开关、系统信息、类型扩展示例（如一次性任务）。
- **M3**：测试、文档、简单认证、Docker 支持（可选）。

## 9. 约束
- 仅限局域网访问。
- 默认任务超时 60s，并发上限 5 个。
- 初期无用户认证（信任内网），后期可加 Basic Auth。
- H2 当前 `ddl-auto=update`，正式部署建议改用 Flyway 管理表结构。
