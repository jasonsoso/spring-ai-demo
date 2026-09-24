# AGENTS.md

## Cursor Cloud specific instructions

This is a monorepo with three independent sub-projects (no root build orchestrator). See `README.md` and each sub-project's `README.md` for standard commands; this section only records non-obvious caveats discovered while running them in this environment.

| Project | Path | Port | Stack | Run (dev) |
|---------|------|------|-------|-----------|
| demo | `demo/` | 8080 | Java 17 target, Spring Boot 3.5, Spring AI 1.x | `sh ./mvnw spring-boot:run` |
| demo2 | `demo2/` | 8081 | Java 21, Spring Boot 4.1, Spring AI 2.x | `sh ./mvnw spring-boot:run` (see caveats) |
| fastapi-demo | `fastapi-demo/` | 8000 | Python 3.12, FastAPI | `./venv/bin/uvicorn main:app --reload --host 0.0.0.0 --port 8000` |

### Local database (shared by all three)
- MariaDB (MySQL wire-compatible) is used as the local MySQL. It is NOT auto-started (no systemd). Start it in the background with: `sudo mariadbd-safe &` (data dir `/var/lib/mysql`).
- Credentials expected by the apps: user `root`, password `123456`, over TCP `127.0.0.1:3306`. Root TCP auth with this password and the two databases (`spring_ai_agent`, `spring_ai_agent2`) are already provisioned; recreate with the SQL in `README.md` if missing.
- fastapi-demo's `users` table lives in `spring_ai_agent2`; load it with `mysql -h 127.0.0.1 -u root -p123456 spring_ai_agent2 < fastapi-demo/schema/users.sql` (idempotent-ish; `INSERT`s will duplicate seed rows on re-run).

### Java apps (demo / demo2) — non-obvious caveats
- Only JDK 21 is installed. `demo` targets Java 17 but compiles/runs fine on JDK 21.
- `mvnw` is not executable by default (git mode). Invoke as `sh ./mvnw ...` or `chmod +x mvnw` first.
- Both apps require NON-EMPTY `DEEPSEEK_API_KEY` and `ZHIPUAI_API_KEY` to even START (Spring AI asserts the keys are set at bean creation). Startup works with any placeholder value; real keys are only needed for actual LLM/embedding calls. Provide real keys via Cursor Secrets for full functionality.
- `demo2` uses an aliyun Maven mirror (`.mvn/settings.xml`) that intermittently returns transient `502 Bad Gateway`. If a dependency fails to resolve, simply re-run the build; cached artifacts persist.
- **demo2 must be run via `sh ./mvnw spring-boot:run`, NOT `java -jar target/...jar`.** The core `spring-ai-elevenlabs` artifact resolves as `(optional)` in the dependency graph, so `spring-boot:repackage` excludes it from the fat jar, causing `NoClassDefFoundError: ElevenLabsVoicesApi` at startup. `spring-boot:run` uses the full compile classpath and works.
- **demo2 has a hardcoded Windows path** `agent.skills.dirs=file:C:/Users/Jason/.cursor/skills` in `application.properties` that is FATAL on Linux (`Root directory does not exist`). Override it at runtime (do not edit the file):
  - Run: `sh ./mvnw spring-boot:run -Dspring-boot.run.arguments=--agent.skills.dirs=classpath:/.claude/skills`
  - Tests: prefix with env `AGENT_SKILLS_DIRS=classpath:/.claude/skills` (the surefire fork inherits env vars).

### Tests
- No lint is configured for any project. fastapi-demo has no automated tests.
- Java: `sh ./mvnw test` must run ONLINE — offline mode (`-o`) lacks the `surefire-junit-platform` provider jar and fails.
- `demo` tests: `DEEPSEEK_API_KEY=placeholder-key ZHIPUAI_API_KEY=placeholder-key sh ./mvnw test`.
- `demo2` tests: `DEEPSEEK_API_KEY=placeholder-key ZHIPUAI_API_KEY=placeholder-key AGENT_SKILLS_DIRS=classpath:/.claude/skills sh ./mvnw test`.

### Optional services (not set up here)
- Milvus (RAG optimized / e-commerce) and the demo2 observability stack require Docker, which is not installed. They are optional; core endpoints run without them (`reindex-on-startup=false`, OTLP export disabled by default). `LKCOFFEE_TOKEN`, `AMAP_API_KEY`, `ELEVENLABS_API_KEY` are optional and skipped gracefully when unset.
