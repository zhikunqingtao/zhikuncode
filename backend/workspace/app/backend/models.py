from typing import Optional

from pydantic import BaseModel


class Todo(BaseModel):
    id: str
    title: str
    completed: bool = False
    created_at: str  # ISO 8601 format


class TodoCreate(BaseModel):
    title: str


class TodoUpdate(BaseModel):
    title: Optional[str] = None
    completed: Optional[bool] = None
