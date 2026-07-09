from fastapi import APIRouter
from pydantic import BaseModel

from common.response import ApiResponse

router = APIRouter(prefix="/demo/response", tags=["统一响应"])


class UserData(BaseModel):
    id: int
    name: str


@router.get("/users/{user_id}", response_model=ApiResponse[UserData])
async def get_user(user_id: int):
    """统一响应格式示例。"""
    if user_id <= 0:
        return ApiResponse(code=400, msg="用户ID必须大于0")
    return ApiResponse(data=UserData(id=user_id, name=f"User_{user_id}"))
