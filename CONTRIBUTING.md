# Contributing to ZhikunCode

Thank you for your interest in contributing to ZhikunCode! Every contribution matters — whether it's a bug fix, a new feature, or a documentation improvement.

## Development Setup

### Prerequisites

- **JDK 21** (e.g., Eclipse Temurin or GraalVM)
- **Node.js 22+** and **npm**
- **Python 3.11+**
- **Maven 3.9+**

### Clone the Repository

```bash
git clone https://github.com/zhikuncode/zhikuncode.git
cd zhikuncode
```

### Configure Environment

Copy the example environment file and fill in your settings:

```bash
cp .env.example .env
# Edit .env with your API keys and configuration
```

### Start Services

**Backend (Java Spring Boot)**

```bash
cd backend
./mvnw spring-boot:run
```

**Frontend (React + TypeScript)**

```bash
cd frontend
npm install
npm run dev
```

**Python Service (FastAPI)**

```bash
cd python-service
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn src.main:app --reload
```

Or use the convenience script from the project root:

```bash
./start.sh
```

## Project Structure

| Directory          | Description                                      |
| ------------------ | ------------------------------------------------ |
| `backend/`         | Java Spring Boot backend — core API and services |
| `frontend/`        | React TypeScript frontend — user interface       |
| `python-service/`  | Python FastAPI service — AI/LLM integration      |
| `configuration/`   | Shared configuration files (e.g., MCP registry)  |
| `docs/`            | Project documentation                            |

## How to Contribute

### Bug Fixes

Found a bug? Feel free to submit a Pull Request directly. Please include:
- A clear description of the bug
- Steps to reproduce (if applicable)
- Your fix and any relevant tests

### New Features

For new features, **open an Issue first** to discuss the design and scope. This helps avoid duplicate work and ensures alignment with the project roadmap.

### Documentation

Documentation improvements are always welcome — just open a PR.

## Pull Request Guidelines

Before submitting a PR, please ensure:

1. **Code compiles successfully**
   ```bash
   cd backend && ./mvnw compile -q
   cd frontend && npm run build
   ```

2. **Tests pass**
   ```bash
   cd backend && ./mvnw test
   cd frontend && npm test
   ```

3. **Code style is consistent** (see [Code Style](#code-style) below)

4. **PR description is clear** — explain *what* changed and *why*

5. **Keep PRs focused** — one logical change per PR

## Code Style

### Java

- Standard Java conventions
- 4-space indentation
- Meaningful variable and method names
- Javadoc for public APIs

### TypeScript

- 2-space indentation
- Follow ESLint configuration in the project
- Prefer functional components and hooks in React

### Python

- Follow [PEP 8](https://peps.python.org/pep-0008/)
- Use type hints where possible
- Keep functions focused and well-documented

## License

By submitting a pull request, you agree that your contributions will be licensed under the [MIT License](LICENSE).
