from datetime import datetime
from typing import List, Optional

from fastapi import APIRouter
from pydantic import BaseModel, EmailStr, Field

router = APIRouter(prefix="/demo/body", tags=["请求体校验"])


class UserCreate(BaseModel):
    username: str = Field(..., min_length=3, max_length=20, description="用户名")
    email: EmailStr = Field(..., description="邮箱地址")
    password: str = Field(..., min_length=8, description="密码")
    age: Optional[int] = Field(None, ge=0, le=150, description="年龄")
    tags: List[str] = Field(default_factory=list)


class UserResponse(BaseModel):
    id: int
    username: str
    email: str
    age: Optional[int]
    created_at: datetime


@router.post("/users", response_model=UserResponse)
async def create_user(user: UserCreate):
    return UserResponse(
        id=1,
        username=user.username,
        email=user.email,
        age=user.age,
        created_at=datetime.now(),
    )
