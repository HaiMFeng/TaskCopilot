# TaskCopilot

在局域网内无屏小主机上运行的轻量级定时任务管理工具。通过浏览器即可管理「每日任务」的排期、执行与日志，支持按场景把任务分组到多个「日程表」中，且仅有一个日程表处于运行中。

> 当前版本聚焦**每日任务**模式；**立即任务**模式为占位（规划中的一次性并发执行能力）。

## 特性
- **日程表（Schedule）分组**：多个日程表，同一时间仅一个运行；切换运行日程表时其余自动停用。
- **每日定时任务**：所有任务均按每日指定时间执行（调度维度），触发配置由后端 schema 驱动、前端动态渲染。
- **三种任务类型（功能类别）**：
  - **运行指令（RUN_COMMAND）**：执行 Shell/系统命令，支持命令内容、工作目录、超时。
  - **打开应用（OPEN_APP）**：启动预置或自定义应用（Windows `start` / macOS `open` / Linux `xdg-open`）。
  - **发送请求（HTTP_REQUEST）**：发起 HTTP 调用（GET/POST 等），适合健康检查、Webhook、curl 类场景。
  - 每种类型拥有专属配置表单，切换类型时界面自动切换；预制类型旨在简化指令编写。
- **左右分栏界面**：左栏任务列表（拖拽排序、启用开关），右栏选中任务详情编辑、立即执行与日志查看。
- **新建任务弹窗**：不干扰主页面布局。
- **全局暂停/恢复**：维护时一键冻结所有定时触发。
- **低资源占用**：Spring 虚拟线程 + 信号量限流，调度线程池仅 2 线程。
- **零前端构建**：原生 HTML/CSS/JS 置于 `resources/static`，开箱即用、可离线；API 层已隔离，后续可平滑迁移到 Vue + Vite。

## 技术栈
| 层 | 选型 |
|----|------|
| 后端 | Spring Boot 4.1.0 · JDK 21（虚拟线程）· Spring Data JPA · H2 · Jackson 3 |
| 前端 | 原生 HTML/CSS/JS（后续可换 Vue 3 + Vite） |
| 构建 | Maven（`./mvnw`） |

## 快速开始

### 环境要求
- JDK 21+
- Maven 3.9+（或直接使用仓库内的 `mvnw`）

### 运行
```bash
# 使用包装脚本（推荐，无需全局安装 Maven）
./mvnw spring-boot:run

# 或先打包再运行
./mvnw clean package -DskipTests
java -jar target/TaskCopilot-0.0.1-SNAPSHOT.jar
```
启动后访问：http://localhost:8080

> H2 控制台默认开启：http://localhost:8080/h2-console （JDBC URL：`jdbc:h2:file:./data/taskcopilot`）

### 配置项（`src/main/resources/application.properties`）
| 配置 | 默认值 | 说明 |
|------|--------|------|
| `server.port` | `8080` | 监听端口 |
| `server.address` | `0.0.0.0` | 监听地址（局域网） |
| `taskcopilot.default-timeout-seconds` | `60` | 任务默认超时 |
| `taskcopilot.max-concurrent-executions` | `5` | 并发执行上限 |
| `taskcopilot.max-output-chars` | `20000` | 日志输出截断长度 |
| `taskcopilot.log-retention-per-task` | `200` | 每任务保留日志条数 |
| `taskcopilot.auto-start` | `true` | 启动即加载并调度任务 |

## 目录结构
```
src/main/
├── java/io/github/haimfeng/taskcopilot/
│   ├── domain/          # 实体：Task、TaskLog、Schedule、ExecutionStatus
│   ├── repository/      # JPA Repository
│   ├── tasktype/        # 任务类型扩展点（TaskTypeHandler / Registry）
│   ├── service/         # 业务：TaskService、ScheduleService、TaskScheduler、执行引擎
│   ├── web/             # Controller、DTO、全局异常处理
│   └── config/          # 调度器与属性配置
└── resources/
    ├── application.properties
    └── static/          # 前端：index.html / css/app.css / js/api.js / js/app.js
```

## API 一览
### 日程表
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/schedules` | 列表（含任务数） |
| GET | `/api/schedules/current` | 当前运行中日程表（缺省自动建默认） |
| POST | `/api/schedules` | 创建 |
| PUT | `/api/schedules/{id}` | 更新 |
| POST | `/api/schedules/{id}/activate` | 切换为运行中（互斥） |
| DELETE | `/api/schedules/{id}` | 删除（其下任务解除归属） |

### 任务
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/tasks?scheduleId=` | 列表（可按日程表过滤） |
| POST | `/api/tasks` | 创建 |
| PUT | `/api/tasks/{id}` | 更新 |
| DELETE | `/api/tasks/{id}` | 删除 |
| POST | `/api/tasks/{id}/execute` | 立即执行 |
| PATCH | `/api/tasks/{id}/toggle?enabled=` | 启用/禁用 |
| PUT | `/api/tasks/sort` | 批量排序 `{"orderedIds":[...]}` |
| GET | `/api/tasks/{id}/logs?limit=50` | 日志 |

### 系统 / 类型
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/task-types` | 任务类型及配置 schema |
| GET | `/api/system/info` | 系统信息 |
| POST | `/api/system/scheduler/pause` | 全局暂停 |
| POST | `/api/system/scheduler/resume` | 全局恢复 |

## 扩展指引
- **新增任务类型**：实现 `TaskTypeHandler` 接口（提供 `displayName`、`configSchema`、`nextExecution`、`validate`、`summary`），用 `@Component` 注册即可。调度器、Service、前端均无需改动。
- **新增日程表 / 任务属性**：修改对应 `domain` 实体与 DTO，JPA `ddl-auto=update` 会自动演进表结构（生产环境建议改用 Flyway）。

## 路线图
- **M1**：后端 CRUD + 每日定时调度 + 日志。
- **M2**：日程表分组与互斥激活、拖拽排序、全局开关、系统信息、类型扩展示例。
- **M3**：测试、文档、简单认证、Docker 支持（可选）。

## 许可证
内部项目，详见具体授权说明。
