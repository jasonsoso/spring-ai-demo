from sqlalchemy import String
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.orm import Mapped, declarative_base, mapped_column

# MySQL 数据库配置
MYSQL_HOST = "127.0.0.1"
MYSQL_PORT = 3306
MYSQL_USER = "root"
MYSQL_PASS = "123456"
MYSQL_DB = "spring_ai_agent2"

DATABASE_URL = (
    f"mysql+aiomysql://{MYSQL_USER}:{MYSQL_PASS}"
    f"@{MYSQL_HOST}:{MYSQL_PORT}/{MYSQL_DB}"
)

engine = create_async_engine(DATABASE_URL, echo=True)
AsyncSessionLocal = async_sessionmaker(engine, expire_on_commit=False)

Base = declarative_base()


class User(Base):
    __tablename__ = "users"

    id: Mapped[int] = mapped_column(primary_key=True)
    username: Mapped[str] = mapped_column(String(255), unique=True)
    email: Mapped[str] = mapped_column(String(255))


async def get_db():
    """依赖：获取数据库会话。"""
    async with AsyncSessionLocal() as session:
        yield session
