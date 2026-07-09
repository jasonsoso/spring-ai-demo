from fastapi import APIRouter, HTTPException

router = APIRouter(prefix="/demo/errors", tags=["异常处理"])


@router.get("/http")
async def trigger_http_error():
    """触发 HTTPException，验证全局异常处理。"""
    raise HTTPException(status_code=404, detail="资源不存在")


@router.get("/internal")
async def trigger_internal_error():
    """触发未捕获异常，验证 500 全局处理。"""
    raise RuntimeError("模拟内部错误")
