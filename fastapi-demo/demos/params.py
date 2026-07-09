from fastapi import APIRouter

router = APIRouter(prefix="/demo/params", tags=["路径与查询参数"])


@router.get("/users/{user_id}")
async def get_user(user_id: int):
    """路径参数：从 URL 路径中提取，自动类型转换 + 校验。"""
    return {"user_id": user_id, "name": f"User_{user_id}"}


@router.get("/items")
async def list_items(
    skip: int = 0,
    limit: int = 10,
    category: str | None = None,
):
    """查询参数：从 URL 问号后面提取。"""
    return {"skip": skip, "limit": limit, "category": category}
