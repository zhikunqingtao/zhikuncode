import uuid
from datetime import datetime

from models import Todo, TodoCreate, TodoUpdate


class TodoStorage:
    """In-memory storage for Todo items."""

    def __init__(self):
        self._todos: dict[str, Todo] = {}

    def get_all(self) -> list[Todo]:
        """Return all todos as a list."""
        return list(self._todos.values())

    def get_by_id(self, todo_id: str) -> Todo | None:
        """Return a todo by ID, or None if not found."""
        return self._todos.get(todo_id)

    def create(self, data: TodoCreate) -> Todo:
        """Create a new todo and return it."""
        todo = Todo(
            id=str(uuid.uuid4()),
            title=data.title,
            completed=False,
            created_at=datetime.now().isoformat(),
        )
        self._todos[todo.id] = todo
        return todo

    def update(self, todo_id: str, data: TodoUpdate) -> Todo | None:
        """Update an existing todo. Returns updated todo or None if not found."""
        todo = self._todos.get(todo_id)
        if todo is None:
            return None

        update_data = data.model_dump(exclude_unset=True)
        for key, value in update_data.items():
            setattr(todo, key, value)

        self._todos[todo_id] = todo
        return todo

    def delete(self, todo_id: str) -> bool:
        """Delete a todo by ID. Returns True if deleted, False if not found."""
        if todo_id not in self._todos:
            return False
        del self._todos[todo_id]
        return True


# Singleton instance
todo_storage = TodoStorage()
