export interface Todo {
  id: string;
  title: string;
  completed: boolean;
  created_at: string;
}

export interface CreateTodoInput {
  title: string;
}

export interface UpdateTodoInput {
  title?: string;
  completed?: boolean;
}
