

## Project Overview

Spring Boot 3.5.14 AI coding assistant (MiMo Code Agent) using Spring AI + Xiaomi MiMo v2.5 Pro model. Dual-mode: Web UI (SSE streaming) and CLI (JLine3).

## Build & Run

```bash
./mvnw spring-boot:run                          # Web mode (port 8080)
./mvnw spring-boot:run -Dspring-boot.run.arguments="--mimo.agent.cli-mode=true"  # CLI mode
./mvnw test                                     # Tests (only SpringAiAgentApplicationTests)
./mvnw clean package                           # Build JAR
```

## Required Environment Variables

Copy `.env.example` to `.env` and set:

| Variable | Required | Notes |
|---|---|---|
| `AI_XIAOMI_API_KEY` | Yes | MiMo API key |
| `JWT_SECRET` | Yes | At least 32 bytes |
| `DB_URL` | Yes | PostgreSQL, default `jdbc:postgresql://localhost:5432/postgres` |
| `DB_USERNAME` / `DB_PASSWORD` | Yes | PostgreSQL credentials |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | No | Falls back to in-memory if unavailable |
| `MIMO_BASE_URL` | No | MiMo API base URL, default `https://token-plan-cn.xiaomimimo.com/v1` |
| `SQL_INIT_MODE` | No | `always` (default) or `never` — controls schema.sql auto-init |

## Architecture

Base package: `com.sakura.spring.ai.agent`

- **MiMoAgent.java** — core agent loop: streaming chat, tool-call rounds (max 20), retry on transient errors (GOAWAY, 502, 503, 429, timeout)
- **SystemPrompt.java** — cached system prompt (60s TTL), injected per request
- **ToolRegistry.java** — auto-discovers all `Tool` beans, dispatches execution
- **Tool implementations** (all in `tool/`):
  - `BashTool` — shell execution with dangerous command blocklist + safe command whitelist; output truncated at 30KB
  - `FileTool` — read/write/edit/list, restricted to working directory
  - `GitTool` — path traversal protected; output truncated at 30KB
  - `SearchTool` — glob/grep, path traversal protected
  - `TodoTool` — in-memory only, not persisted across restarts
- **ConversationMemory.java** — Redis-backed session storage with in-memory fallback (7-day TTL, max 50 messages)
- **AgentController.java** — REST API: `/api/chat` (SSE), `/api/sessions`, `/api/sessions/{id}/messages`
- **HealthController.java** — `/health` endpoint (no auth required)
- **AuthController.java** — `/api/auth/register`, `/api/auth/login`, `/api/auth/refresh`
- **SecurityConfig.java** — WebFlux security, JWT filter, CORS (localhost:5173, localhost:3000), `/health` public
- **RateLimitFilter.java** — Redis-based rate limiting on auth endpoints (silently skips if Redis unavailable)

## Infrastructure Dependencies

- **PostgreSQL** — user accounts and session associations (auto-initialized via `schema.sql`)
- **Redis** — conversation history storage (optional, degrades to in-memory)
- **Spring AI Alibaba Agent Framework** — agent tool integration

## Key Conventions

- Spring profile: `mimo` (active by default)
- MyBatis-Plus for ORM, underscore-to-camel-case mapping
- `@MapperScan("com.sakura.spring.ai.agent.mapper")` on main class — MyBatis mapper interfaces auto-discovered
- MiMo API accessed via Spring AI's OpenAI-compatible starter (`spring-ai-starter-model-openai`)
- SSE heartbeat every 30s to keep connections alive
- `BashTool` blocks subshell (`bash -c`, `sh -c`) and destructive patterns (rm /, mkfs, etc.)
- `FileTool` and `SearchTool` enforce path traversal protection — all file ops confined to working directory
- Tool call loop maxes at 20 rounds per request to prevent infinite loops

## Adding a New Tool

1. Create a class implementing `com.sakura.spring.ai.agent.tool.Tool`
2. Annotate with `@Component`
3. Implement `name()`, `description()`, `parameters()`, `execute()`
4. It will be auto-registered by `ToolRegistry`

## Testing

Tests require a running PostgreSQL and (optionally) Redis. The only existing test is `SpringAiAgentApplicationTests` which verifies context loads. Run:

```bash
./mvnw test
```

## Gotchas

- `schema.sql` runs on every startup when `SQL_INIT_MODE=always` — uses `IF NOT EXISTS` so safe
- `SystemPrompt` caches for 60 seconds — changes to git branch won't reflect immediately in prompts
- CORS only allows `localhost:5173` and `localhost:3000` — adjust `SecurityConfig` for other origins
- No CI/CD pipeline configured — no `.github/workflows` directory exists
- The `config/` package is empty — configuration is via `application.yml` + Spring auto-config
- `TodoTool` is in-memory only — tasks don't survive restarts
- Rate limiting on auth endpoints requires Redis — silently disabled when Redis is unavailable
- Netty `netty-resolver-dns-native-macos` (osx-aarch_64) is an optional dependency — won't break other platforms
