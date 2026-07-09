from fastapi import APIRouter

router = APIRouter(tags=["基础路由"])


@router.get("/")
async def root():
    return {"message": "Hello World"}


@router.get("/demo/basic/hello/{name}")
async def say_hello(name: str):
    return {"message": f"Hello, {name}!"}
