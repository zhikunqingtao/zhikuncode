function App() {
  return (
    <div className="min-h-screen bg-surface flex items-center justify-center">
      <div className="text-center">
        <h1 className="text-3xl font-bold text-primary mb-4">
          AI Code Assistant
        </h1>
        <p className="text-muted">
          Backend: <a href="/api/health" className="text-primary hover:underline">Health Check</a>
        </p>
        <p className="text-sm text-muted mt-2">
          Round 1: 项目脚手架初始化完成
        </p>
      </div>
    </div>
  );
}

export default App;
