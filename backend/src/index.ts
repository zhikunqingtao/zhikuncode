import express, { Request, Response } from 'express';
import cors from 'cors';
import todoRoutes from './routes/todos';

const app = express();
const PORT = 3001;

// Middleware
app.use(cors());
app.use(express.json());

// Routes
app.use('/api/todos', todoRoutes);

// Health check
app.get('/', (_req: Request, res: Response) => {
  res.json({ message: 'Todo API is running' });
});

// Start server
app.listen(PORT, () => {
  console.log(`Todo API server is running on http://localhost:${PORT}`);
});
