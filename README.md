# MiMo Code Agent

基于 Spring AI 和小米 MiMo 大模型的智能代码助手，支持 Web UI 和 CLI 两种交互模式。

## 功能特性

- **代码读写** - 读取、创建、编辑代码文件
- **命令执行** - 运行 Shell 命令，支持超时控制和危险命令拦截
- **代码搜索** - Glob 模式匹配文件，正则表达式搜索内容
- **Git 操作** - status、diff、log、add、commit、branch 等
- **任务管理** - 创建、更新、跟踪开发任务
- **会话记忆** - Redis 持久化会话历史，支持多会话管理
- **SSE 流式响应** - 实时流式输出，打字机效果
- **双模式交互** - Web UI 和命令行 CLI 两种使用方式

## 技术栈

| 组件 | 技术 |
|------|------|
| 框架 | Spring Boot 3.5.14 |
| AI | Spring AI 1.1.5 + MiMo v2.5 Pro |
| 流式 | Spring WebFlux (Netty) |
| 存储 | Redis (Lettuce) |
| CLI | JLine 3.26.3 |
| Java | 17+ |

## 快速开始

### 环境要求

- Java 17+
- Redis（可选，无 Redis 时自动降级为内存存储）
- 小米 MiMo API Key

### 配置

设置环境变量：

```bash
export AI_XIAOMI_API_KEY=your_api_key
# 可选
export MIMO_BASE_URL=https://token-plan-cn.xiaomimimo.com/v1
export REDIS_HOST=localhost
export REDIS_PORT=6379
export REDIS_PASSWORD=
```

或修改 `src/main/resources/application.yml`。

### 运行

```bash
# Web 模式（默认）
./mvnw spring-boot:run

# CLI 模式
./mvnw spring-boot:run -Dspring-boot.run.arguments="--mimo.agent.cli-mode=true"
```

访问 http://localhost:8080 打开 Web UI。

## 使用方式

### Web UI

启动后访问 http://localhost:8080，支持：
- 多会话管理
- 实时流式对话
- 工具调用可视化
- 深色/浅色主题切换
- 移动端响应式

### CLI 模式

启动 CLI 模式后，在终端直接对话：

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments="--mimo.agent.cli-mode=true"
```

支持的斜杠命令：
- `/help` - 帮助信息
- `/clear` - 清屏
- `/new` - 新建会话
- `/sessions` - 列出会话
- `/tools` - 列出工具
- `/todo` - 任务管理
- `/exit` - 退出

## 工具列表

| 工具 | 功能 |
|------|------|
| `execute_command` | 执行 Shell 命令 |
| `file_operations` | 文件读写编辑 |
| `search` | 文件搜索和内容搜索 |
| `git` | Git 操作 |
| `todo` | 任务管理 |

## API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat` | SSE 流式对话 |
| GET | `/api/sessions` | 获取会话列表 |
| DELETE | `/api/sessions/{id}` | 删除会话 |
| GET | `/api/sessions/{id}/messages` | 获取会话消息 |

## 项目结构

```
src/main/java/com/sakura/spring/ai/agent/
├── SpringAiAgentApplication.java   # 启动类
├── MiMoAgent.java                  # 核心 Agent 逻辑
├── SystemPrompt.java               # 系统提示词
├── cli/                            # CLI 模式
├── config/                         # 配置类
├── controller/                     # REST API
├── memory/                         # 会话记忆
└── tool/                           # 工具实现
```

## License

MIT