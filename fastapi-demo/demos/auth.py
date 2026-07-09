from fastapi import APIRouter, Depends, Header, HTTPException

router = APIRouter(prefix="/demo/auth", tags=["依赖注入"])


async def verify_token(authorization: str = Header(...)):
    """从请求头中提取并验证 Token。"""
    if not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="无效的认证格式")
    token = authorization.replace("Bearer ", "")
    if token != "valid-token":
        raise HTTPException(status_code=401, detail="无效的 Token")
    return {"user_id": 1, "username": "admin"}


@router.get("/protected")
async def protected_route(user: dict = Depends(verify_token)):
    return {"message": f"欢迎, {user['username']}!", "user": user}
