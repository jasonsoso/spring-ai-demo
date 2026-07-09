import asyncio

from fastapi import APIRouter

router = APIRouter(prefix="/demo/async", tags=["同步与异步"])


@router.get("/sync")
def sync_endpoint():
    """同步函数（适用于 CPU 密集型操作）。"""
    return {"result": "done"}


@router.get("/async")
async def async_endpoint():
    """异步函数（适用于 I/O 密集型操作）。"""
    await asyncio.sleep(1)
    return {"result": "done after 1 second"}


def sync_heavy_work():
    """CPU 密集型操作。"""
    return sum(range(1000000))


@router.get("/mixed")
async def mixed_endpoint():
    """在异步函数中调用同步代码。"""
    result = await asyncio.to_thread(sync_heavy_work)
    return {"result": result}
