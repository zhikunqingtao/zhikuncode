# Contributing to ZhikunCode

Thank you for your interest in contributing to ZhikunCode! Every contribution matters — whether it's a bug fix, a new feature, or a documentation improvement.

## Development Setup

### Prerequisites

- **JDK 21** (e.g., Eclipse Temurin or GraalVM)
- **Node.js 22+** and **npm**
- **Python 3.11 or 3.12**
- Use the repository's **Maven Wrapper (`./mvnw`)** for Java builds.

### Clone the Repository

```bash
git clone https://github.com/zhikunqingtao/zhikuncode.git
cd zhikuncode
```

### Configure Environment

Copy the example environment file and fill in your settings:

```bash
cp .env.example .env
# Edit .env with your API keys and configuration
```

### Start Services

Follow the [local setup instructions](docs/README_EN.md#local-setup) for dependency installation, loading `.env` in each terminal, and starting the three services. Use its Python 3.11/3.12 virtual environment and `PYTHONPATH="$PWD/python-service/src"` setting from the repository root; the Python service imports modules from that directory.

That guide also provides health checks and shutdown instructions. On a shared development machine, use the manual startup path: `start.sh` stops processes on fixed ports and removes `backend/target`.

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
