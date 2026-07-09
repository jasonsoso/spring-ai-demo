import time

from fastapi import FastAPI, Request


def register_middleware(app: FastAPI) -> None:
    """注册 HTTP 中间件：记录请求耗时，并写入响应头。"""

    @app.middleware("http")
    async def log_requests(request: Request, call_next):
        start_time = time.time()

        # 记录请求信息
        print(f"收到请求: {request.method} {request.url.path}")

        # 继续处理请求
        response = await call_next(request)

        # 记录响应信息
        process_time = time.time() - start_time
        print(f"请求完成: {process_time:.4f}秒")

        # 在响应头中添加处理时间
        response.headers["X-Process-Time"] = str(process_time)
        return response
