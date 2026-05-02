"""
Git Enhanced Service — gitpython 封装的 Git 增强分析服务
"""
import git
import os
import logging
from typing import Optional

logger = logging.getLogger(__name__)


# 工作空间根目录 — 用于解析相对路径（Python CWD 可能是 python-service/）
_WORKSPACE_ROOT = os.path.abspath(os.getenv("WORKSPACE_ROOT", os.path.join(os.path.dirname(__file__), "..", "..", "..")))


class GitEnhancedService:
    """Git 增强服务 — 基于 gitpython 提供结构化 Git 分析能力"""

    # 禁止访问的路径黑名单
    _UNSAFE_PATHS = frozenset(['/', '/root', '/etc', '/var', '/usr'])

    def _resolve_repo_path(self, repo_path: str) -> str:
        """将相对路径解析为基于工作空间根目录的绝对路径"""
        if not repo_path or repo_path == '.':
            return _WORKSPACE_ROOT
        if not os.path.isabs(repo_path):
            return os.path.abspath(os.path.join(_WORKSPACE_ROOT, repo_path))
        return repo_path

    def _validate_repo_path(self, repo_path: str) -> str:
        """路径安全校验 — 防止路径穿越和访问敏感目录"""
        real_path = os.path.realpath(self._resolve_repo_path(repo_path))
        # 禁止访问系统根目录和用户根目录
        if real_path in self._UNSAFE_PATHS or real_path == os.path.expanduser('~'):
            raise ValueError(f"Unsafe repo path: {repo_path}")
        if not os.path.isdir(real_path):
            raise ValueError(f"Not a directory: {repo_path}")
        # 验证是否为有效 Git 仓库
        git_dir = os.path.join(real_path, '.git')
        if not os.path.isdir(git_dir):
            raise ValueError(f"Not a git repository: {repo_path}")
        return real_path

    def semantic_diff(self, repo_path: str, ref1: str = "HEAD~1", ref2: str = "HEAD") -> dict:
        """语义化 diff 分析 — 返回变更统计 + 详细 diff"""
        safe_path = self._validate_repo_path(repo_path)
        repo = git.Repo(safe_path)
        diff = repo.git.diff(ref1, ref2, stat=True)
        detailed = repo.git.diff(ref1, ref2)
        return {
            "summary": diff,
            "detailed": detailed,
            "files_changed": len(repo.commit(ref2).diff(ref1))
        }

    def enhanced_log(self, repo_path: str, max_count: int = 20,
                     branch: Optional[str] = None) -> dict:
        """结构化 commit 日志 — 返回含文件列表的详细日志"""
        safe_path = self._validate_repo_path(repo_path)
        repo = git.Repo(safe_path)
        rev = branch or 'HEAD'
        commits = [
            {
                "sha": c.hexsha[:8],
                "message": c.message.strip(),
                "author": str(c.author),
                "date": c.committed_datetime.isoformat(),
                "files": list(c.stats.files.keys())
            }
            for c in repo.iter_commits(rev=rev, max_count=max_count)
        ]
        return {"commits": commits, "total": len(commits)}

    def file_blame(self, repo_path: str, file_path: str, ref: str = "HEAD") -> dict:
        """文件 blame — 逐行归属分析"""
        safe_path = self._validate_repo_path(repo_path)
        repo = git.Repo(safe_path)
        blame_data = repo.blame(ref, file_path)
        lines = []
        line_no = 1
        for commit, content_lines in blame_data:
            for content in content_lines:
                lines.append({
                    "line_no": line_no,
                    "sha": commit.hexsha[:8],
                    "author": str(commit.author),
                    "date": commit.committed_datetime.isoformat(),
                    "content": content if isinstance(content, str) else content.decode('utf-8', errors='replace')
                })
                line_no += 1
        return {
            "file_path": file_path,
            "lines": lines,
            "total_lines": len(lines)
        }
