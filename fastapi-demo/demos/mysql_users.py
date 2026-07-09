from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from common.response import ApiResponse
from db.mysql import User, get_db

router = APIRouter(prefix="/demo/mysql", tags=["MySQL 用户 CRUD"])


class UserItem(BaseModel):
    id: int
    username: str
    email: str


class UserCreate(BaseModel):
    username: str = Field(..., min_length=1, max_length=255, description="用户名")
    email: str = Field(..., min_length=1, max_length=255, description="邮箱")


class UserUpdate(BaseModel):
    username: str = Field(..., min_length=1, max_length=255, description="用户名")
    email: str = Field(..., min_length=1, max_length=255, description="邮箱")


async def _get_user_or_404(db: AsyncSession, user_id: int) -> User:
    result = await db.execute(select(User).where(User.id == user_id))
    user = result.scalar_one_or_none()
    if user is None:
        raise HTTPException(status_code=404, detail="用户不存在")
    return user


async def _ensure_username_available(
    db: AsyncSession, username: str, exclude_id: Optional[int] = None
) -> None:
    stmt = select(User).where(User.username == username)
    if exclude_id is not None:
        stmt = stmt.where(User.id != exclude_id)
    result = await db.execute(stmt)
    if result.scalar_one_or_none() is not None:
        raise HTTPException(status_code=400, detail="用户名已存在")


@router.get("/users", response_model=ApiResponse[List[UserItem]])
async def get_users(db: AsyncSession = Depends(get_db)):
    result = await db.execute(select(User))
    users = result.scalars().all()
    data = [UserItem(id=u.id, username=u.username, email=u.email) for u in users]
    return ApiResponse(data=data)


@router.get("/users/{user_id}", response_model=ApiResponse[UserItem])
async def get_user(user_id: int, db: AsyncSession = Depends(get_db)):
    user = await _get_user_or_404(db, user_id)
    return ApiResponse(data=UserItem(id=user.id, username=user.username, email=user.email))


@router.post("/users", response_model=ApiResponse[UserItem])
async def create_user(body: UserCreate, db: AsyncSession = Depends(get_db)):
    await _ensure_username_available(db, body.username)
    user = User(username=body.username, email=body.email)
    db.add(user)
    await db.commit()
    await db.refresh(user)
    return ApiResponse(data=UserItem(id=user.id, username=user.username, email=user.email))


@router.put("/users/{user_id}", response_model=ApiResponse[UserItem])
async def update_user(user_id: int, body: UserUpdate, db: AsyncSession = Depends(get_db)):
    user = await _get_user_or_404(db, user_id)
    await _ensure_username_available(db, body.username, exclude_id=user_id)
    user.username = body.username
    user.email = body.email
    await db.commit()
    await db.refresh(user)
    return ApiResponse(data=UserItem(id=user.id, username=user.username, email=user.email))


@router.delete("/users/{user_id}", response_model=ApiResponse[None])
async def delete_user(user_id: int, db: AsyncSession = Depends(get_db)):
    user = await _get_user_or_404(db, user_id)
    await db.delete(user)
    await db.commit()
    return ApiResponse(msg="删除成功", data=None)
