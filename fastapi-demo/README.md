# FastAPI Demo

基于 **FastAPI + Uvicorn** 的综合入门示例，覆盖路由、参数校验、依赖注入、同步/异步、MySQL 异步查询等场景。各功能按 demo 拆分到独立文件，通过 `main.py` 统一启动。

---

## 技术栈

| 组件 | 说明 |
|------|------|
| [FastAPI](https://fastapi.tiangolo.com/) | Web 框架 |
| [Uvicorn](https://www.uvicorn.org/) | ASGI 服务器 |
| [Pydantic](https://docs.pydantic.dev/) | 数据校验与序列化 |
| [SQLAlchemy](https://www.sqlalchemy.org/) | 异步 ORM |
| [aiomysql](https://github.com/aio-libs/aiomysql) | MySQL 异步驱动 |
| Python 3.11+ | 推荐版本 |

---

## 快速开始

### 1. 创建并激活虚拟环境

**Windows (PowerShell)：**

```powershell
python -m venv venv
.\venv\Scripts\Activate.ps1
```

**macOS / Linux：**

```bash
python -m venv venv
source venv/bin/activate
```

### 2. 安装依赖

```bash
pip install fastapi "uvicorn[standard]" "pydantic[email]" sqlalchemy aiomysql
```

### 3. 初始化 MySQL 表（可选，使用 MySQL 查询 demo 时需要）

确保本地 MySQL 已启动，并存在数据库 `spring_ai_agent2`，然后执行：

```bash
# 方式一：直接执行 SQL 文件
mysql -u root -p spring_ai_agent2 < schema/users.sql

# 方式二：使用 Python（Windows 无 mysql 命令时）
python -c "
import pymysql
conn = pymysql.connect(host='127.0.0.1', port=3306, user='root', password='123456', database='spring_ai_agent2', charset='utf8mb4')
with conn.cursor() as cur:
    cur.execute(open('schema/users.sql', encoding='utf-8').read())
conn.commit(); conn.close()
"
```

默认连接参数（见 `mysql_users.py`）：

| 参数 | 值 |
|------|-----|
| Host | `127.0.0.1:3306` |
| User | `root` |
| Database | `spring_ai_agent2` |

### 4. 启动服务

**一条命令启动全部 demo：**

```bash
uvicorn main:app --reload --host 0.0.0.0 --port 8000
```

Windows 若未激活虚拟环境：

```powershell
.\venv\Scripts\uvicorn.exe main:app --reload --host 0.0.0.0 --port 8000
```

启动后访问：

| 地址 | 说明 |
|------|------|
| http://127.0.0.1:8000/ | 根路径 |
| http://127.0.0.1:8000/docs | Swagger 交互文档 |
| http://127.0.0.1:8000/redoc | ReDoc 文档 |

---

## 项目架构

采用 **APIRouter 分模块 + main.py 统一入口** 的方式，无需每个文件单独启动：

```
main.py          ← 唯一入口，创建 FastAPI 实例，挂载各 demo 路由
mysql_users.py   ← demo 模块，导出 router
xxx_demo.py      ← 未来新增的 demo，同样导出 router
```

`main.py` 中挂载路由：

```python
from mysql_users import router as mysql_users_router

app = FastAPI(...)
app.include_router(mysql_users_router, tags=["MySQL 用户查询"])
```

新增 demo 时，只需：
1. 新建文件，定义 `router = APIRouter()` 并注册路由
2. 在 `main.py` 中 `app.include_router(...)` 一行挂载

所有接口会出现在同一个 `/docs` 页面，并可通过 `tags` 分组展示。

---

## API 接口

### 基础路由（main.py）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/` | 欢迎页 |
| GET | `/hello/{name}` | 路径参数示例 |

### 参数与请求体（main.py）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/users/{user_id}` | 路径参数 + 类型校验 |
| GET | `/items` | 查询参数（skip、limit、category） |
| POST | `/users` | 请求体校验（Pydantic 模型） |

### 依赖注入（main.py）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/protected` | Token 认证，需请求头 `Authorization: Bearer valid-token` |

### 同步 / 异步（main.py）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/sync` | 同步端点 |
| GET | `/async` | 异步 I/O 模拟（`asyncio.sleep`） |
| GET | `/mixed` | 异步中调用同步代码（`asyncio.to_thread`） |

### MySQL 用户查询（mysql_users.py）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/users` | 从 MySQL 查询用户列表 |

**示例：**

```bash
curl http://127.0.0.1:8000/
curl http://127.0.0.1:8000/hello/Jason
curl "http://127.0.0.1:8000/items?skip=0&limit=5&category=book"
curl http://127.0.0.1:8000/users
curl -H "Authorization: Bearer valid-token" http://127.0.0.1:8000/protected
```

---

## 目录结构

```
fastapi-demo/
├── main.py              # 应用唯一入口，挂载各 demo 路由
├── mysql_users.py       # MySQL 异步查询 demo（导出 router）
├── schema/
│   └── users.sql        # users 表建表语句与示例数据
├── README.md
├── .gitignore
└── venv/                # 本地虚拟环境（已忽略，勿提交）
```

---

## 开发说明

- `--reload` 会在代码变更时自动重启，适合本地开发。
- 修改任意 demo 文件后保存即可生效，无需手动重启。
- 生产环境建议去掉 `--reload`，并使用进程管理器（如 systemd、Docker）部署。
- 密码等敏感信息建议后续改为环境变量注入，勿提交到版本库。

---

## 常见问题

**Q: `/docs` 里看不到 `GET /users`？**

确认是通过 `uvicorn main:app` 启动（而非 `mysql_users:app`），且 `main.py` 中已 `include_router` 挂载了 `mysql_users` 模块。

**Q: `GET /users` 报数据库连接错误？**

检查 MySQL 是否启动、连接参数是否正确，以及 `schema/users.sql` 是否已执行。

**Q: 提示 `No module named 'fastapi'` 或 `email_validator`？**

确认已激活虚拟环境，并安装全部依赖（见「快速开始」第 2 步）。

**Q: `/async` 报 `asyncio is not defined`？**

确认 `main.py` 顶部有 `import asyncio`。

**Q: 端口 8000 已被占用？**

更换端口，例如 `--port 8001`，或停止占用该端口的进程。

**Q: PowerShell 无法执行激活脚本？**

以管理员身份运行：`Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser`
