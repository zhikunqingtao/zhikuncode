import { Router, Request, Response } from 'express';
import { Todo } from '../types';

const router = Router();

// In-memory storage
const todos: Todo[] = [];
let nextId = 1;

// GET /api/todos - Return all todos
router.get('/', (_req: Request, res: Response) => {
  res.json(todos);
});

// POST /api/todos - Create a new todo
router.post('/', (req: Request, res: Response) => {
  const { title } = req.body;

  // Validation: title cannot be empty
  if (!title || typeof title !== 'string' || title.trim() === '') {
    res.status(400).json({ error: 'Title is required and cannot be empty' });
    return;
  }

  const newTodo: Todo = {
    id: nextId++,
    title: title.trim(),
    completed: false,
    createdAt: new Date().toISOString(),
  };

  todos.push(newTodo);
  res.status(201).json(newTodo);
});

// PUT /api/todos/:id - Update todo status
router.put('/:id', (req: Request, res: Response) => {
  const id = parseInt(req.params.id, 10);
  if (isNaN(id)) {
    res.status(400).json({ error: 'Invalid todo ID' });
    return;
  }

  const todo = todos.find((t) => t.id === id);
  if (!todo) {
    res.status(404).json({ error: 'Todo not found' });
    return;
  }

  const { completed } = req.body;
  if (typeof completed !== 'boolean') {
    res.status(400).json({ error: 'Completed field must be a boolean' });
    return;
  }

  todo.completed = completed;
  res.json(todo);
});

// DELETE /api/todos/:id - Delete a todo
router.delete('/:id', (req: Request, res: Response) => {
  const id = parseInt(req.params.id, 10);
  if (isNaN(id)) {
    res.status(400).json({ error: 'Invalid todo ID' });
    return;
  }

  const index = todos.findIndex((t) => t.id === id);
  if (index === -1) {
    res.status(404).json({ error: 'Todo not found' });
    return;
  }

  todos.splice(index, 1);
  res.status(204).send();
});

export default router;
