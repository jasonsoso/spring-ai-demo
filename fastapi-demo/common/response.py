from typing import Generic, Optional, TypeVar

from pydantic import BaseModel

T = TypeVar("T")


class ApiResponse(BaseModel, Generic[T]):
    """统一 API 响应格式"""

    code: int = 200
    msg: str = "success"
    data: Optional[T] = None
