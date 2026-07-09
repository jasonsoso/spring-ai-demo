from fastapi import FastAPI, Header, HTTPException, Depends

from pydantic import BaseModel, Field, EmailStr
from typing import Optional, List
from datetime import datetime
import asyncio


from mysql_users import router as mysql_users_router

# 创建应用实例（唯一入口，一条命令启动全部 demo）
app = FastAPI(title="我的第一个FastAPI应用", version="1.0.0")

# 挂载各 demo 模块的路由，新增 demo 时在此 include_router 即可
app.include_router(mysql_users_router, tags=["MySQL 用户查询"])

# 定义路由
@app.get("/")
async def root():
    return {"message": "Hello World"}

@app.get("/hello/{name}")
async def say_hello(name: str):
    return {"message": f"Hello, {name}!"}




# 路径参数：从URL路径中提取
@app.get("/users/{user_id}")
async def get_user(user_id: int):  # 自动类型转换 + 校验
    return {"user_id": user_id, "name": f"User_{user_id}"}

# 查询参数：从URL问号后面提取
@app.get("/items")
async def list_items(
    skip: int = 0,      # 默认值
    limit: int = 10,    # 默认值
    category: str | None = None  # 可选参数
):
    return {"skip": skip, "limit": limit, "category": category}







# 定义请求体模型
class UserCreate(BaseModel):
    username: str = Field(..., min_length=3, max_length=20, description="用户名")
    email: EmailStr = Field(..., description="邮箱地址")
    password: str = Field(..., min_length=8, description="密码")
    age: Optional[int] = Field(None, ge=0, le=150, description="年龄")
    tags: List[str] = Field(default_factory=list)

# 定义响应体模型
class UserResponse(BaseModel):
    id: int
    username: str
    email: str
    age: Optional[int]
    created_at: datetime

@app.post("/users", response_model=UserResponse)
async def create_user(user: UserCreate):
    # 业务逻辑：创建用户
    return UserResponse(
        id=1,
        username=user.username,
        email=user.email,
        age=user.age,
        created_at=datetime.now()
    )


# 定义一个依赖：验证 Token
async def verify_token(authorization: str = Header(...)):
    """从请求头中提取并验证 Token"""
    if not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="无效的认证格式")
    token = authorization.replace("Bearer ", "")
    if token != "valid-token":
        raise HTTPException(status_code=401, detail="无效的 Token")
    return {"user_id": 1, "username": "admin"}


# 使用依赖
@app.get("/protected")
async def protected_route(user: dict = Depends(verify_token)):
    return {"message": f"欢迎, {user['username']}!", "user": user}







# 同步函数（适用于CPU密集型操作）
@app.get("/sync")
def sync_endpoint():
    # 同步操作，会阻塞线程
    return {"result": "done"}

# 异步函数（适用于I/O密集型操作）
@app.get("/async")
async def async_endpoint():
    # 模拟I/O操作：数据库查询、外部API调用等
    await asyncio.sleep(3)
    # 在等待期间，事件循环可以处理其他请求
    return {"result": "done after 1 second"}

# 混合使用：在异步函数中调用同步代码
@app.get("/mixed")
async def mixed_endpoint():
    # 使用 run_in_executor 将同步代码放到线程池执行
    result = await asyncio.to_thread(sync_heavy_work)
    return {"result": result}

def sync_heavy_work():
    # CPU密集型操作
    return sum(range(1000000))