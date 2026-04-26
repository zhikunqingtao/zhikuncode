"""ZhikunCode Python Service 测试共享 Fixture"""
import pytest
import pytest_asyncio
from httpx import AsyncClient, ASGITransport

# 导入 FastAPI app
import sys
import os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'src'))
from main import app

@pytest_asyncio.fixture
async def client():
    """异步 HTTP 测试客户端 fixture"""
    async with AsyncClient(
        transport=ASGITransport(app=app),
        base_url="http://test"
    ) as ac:
        yield ac

@pytest.fixture
def sample_python_code():
    """Python 代码样本 fixture"""
    return '''
def hello(name: str) -> str:
    """Say hello to someone."""
    return f"Hello, {name}!"

class Calculator:
    """Simple calculator."""
    def add(self, a: int, b: int) -> int:
        return a + b
    
    def subtract(self, a: int, b: int) -> int:
        return a - b
'''

@pytest.fixture
def sample_java_code():
    """Java 代码样本 fixture"""
    return '''
package com.example;

import java.util.List;

public class HelloService {
    public String greet(String name) {
        return "Hello, " + name + "!";
    }
    
    public List<String> getNames() {
        return List.of("Alice", "Bob", "Charlie");
    }
}
'''

@pytest.fixture
def sample_typescript_code():
    """TypeScript 代码样本 fixture"""
    return '''
interface User {
    name: string;
    age: number;
}

function greet(user: User): string {
    return `Hello, ${user.name}! You are ${user.age} years old.`;
}

export class UserService {
    private users: User[] = [];
    
    addUser(user: User): void {
        this.users.push(user);
    }
    
    getUser(name: string): User | undefined {
        return this.users.find(u => u.name === name);
    }
}
'''

@pytest.fixture
def git_repo_path(tmp_path):
    """创建临时 Git 仓库 fixture"""
    import subprocess
    repo = tmp_path / "test-repo"
    repo.mkdir()
    subprocess.run(["git", "init"], cwd=repo, capture_output=True)
    subprocess.run(["git", "config", "user.email", "test@test.com"], cwd=repo, capture_output=True)
    subprocess.run(["git", "config", "user.name", "Test User"], cwd=repo, capture_output=True)
    # 创建初始文件并提交
    (repo / "main.py").write_text("def main():\n    print('hello')\n")
    subprocess.run(["git", "add", "."], cwd=repo, capture_output=True)
    subprocess.run(["git", "commit", "-m", "Initial commit"], cwd=repo, capture_output=True)
    # 第二次提交
    (repo / "utils.py").write_text("def helper():\n    return 42\n")
    subprocess.run(["git", "add", "."], cwd=repo, capture_output=True)
    subprocess.run(["git", "commit", "-m", "Add utils"], cwd=repo, capture_output=True)
    return str(repo)

@pytest.fixture
def temp_text_file(tmp_path):
    """创建临时文本文件 fixture"""
    f = tmp_path / "sample.txt"
    f.write_text("Hello, World! 你好，世界！\nLine 2\nLine 3\n", encoding="utf-8")
    return str(f)

@pytest.fixture
def temp_binary_file(tmp_path):
    """创建临时二进制文件 fixture"""
    f = tmp_path / "sample.bin"
    f.write_bytes(b'\x89PNG\r\n\x1a\n' + b'\x00' * 100)
    return str(f)
