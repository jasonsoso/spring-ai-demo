from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse


def register_exception_handlers(app: FastAPI) -> None:
    """注册全局异常处理，统一返回 {code, msg, data} 格式。"""

    @app.exception_handler(HTTPException)
    async def http_exception_handler(request: Request, exc: HTTPException):
        return JSONResponse(
            status_code=exc.status_code,
            content={"code": exc.status_code, "msg": exc.detail, "data": None},
        )

    @app.exception_handler(Exception)
    async def general_exception_handler(request: Request, exc: Exception):
        return JSONResponse(
            status_code=500,
            content={"code": 500, "msg": "服务器内部错误", "data": None},
        )
