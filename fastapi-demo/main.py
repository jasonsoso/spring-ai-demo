from fastapi import FastAPI

from common.exceptions import register_exception_handlers
from demos import (
    async_io,
    auth,
    basic,
    errors,
    mysql_users,
    params,
    request_body,
    response_format,
)

# 唯一入口：一条命令启动全部 demo
app = FastAPI(title="我的第一个FastAPI应用", version="1.0.0")

# 全局异常处理（对所有路由生效）
register_exception_handlers(app)

# 挂载各 demo 模块；新增 demo 时在此 include_router 即可
app.include_router(basic.router)
app.include_router(params.router)
app.include_router(request_body.router)
app.include_router(auth.router)
app.include_router(async_io.router)
app.include_router(response_format.router)
app.include_router(errors.router)
app.include_router(mysql_users.router)
