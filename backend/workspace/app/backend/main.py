from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware

from models import Todo, TodoCreate, TodoUpdate
from storage import todo_storage

app = FastAPI(title="Todo REST API")

# CORS configuration for Vite dev server
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:5173"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/api/todos", response_model=list[Todo])
def get_todos():
    """Get all todos."""
    return todo_storage.get_all()


@app.post("/api/todos", response_model=Todo, status_code=201)
def create_todo(data: TodoCreate):
    """Create a new todo."""
    return todo_storage.create(data)


@app.put("/api/todos/{todo_id}", response_model=Todo)
def update_todo(todo_id: str, data: TodoUpdate):
    """Update an existing todo."""
    todo = todo_storage.update(todo_id, data)
    if todo is None:
        raise HTTPException(status_code=404, detail="Todo not found")
    return todo


@app.delete("/api/todos/{todo_id}", status_code=204)
def delete_todo(todo_id: str):
    """Delete a todo."""
    deleted = todo_storage.delete(todo_id)
    if not deleted:
        raise HTTPException(status_code=404, detail="Todo not found")
