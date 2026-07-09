from typing import List

from fastapi import APIRouter, Depends
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from common.response import ApiResponse
from db.mysql import User, get_db

router = APIRouter(prefix="/demo/mysql", tags=["MySQL 用户查询"])


class UserItem(BaseModel):
    id: int
    username: str
    email: str


@router.get("/users", response_model=ApiResponse[List[UserItem]])
async def get_users(db: AsyncSession = Depends(get_db)):
    result = await db.execute(select(User))
    users = result.scalars().all()
    data = [UserItem(id=u.id, username=u.username, email=u.email) for u in users]
    return ApiResponse(data=data)
