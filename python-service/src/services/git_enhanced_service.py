"""
Git Enhanced Service — gitpython 封装的 Git 增强分析服务
"""
import git
import logging
import os
from pathlib import Path
from typing import Optional

from workspace_paths import resolve_workspace_path

logger = logging.getLogger(__name__)


class GitEnhancedService:
    """Git 增强服务 — 基于 gitpython 提供结构化 Git 分析能力"""

    @staticmethod
    def _open_repo(repo_path: str) -> git.Repo:
        """Open a repository whose worktree and metadata stay in workspace."""
        if os.environ.get("GIT_ALTERNATE_OBJECT_DIRECTORIES", ""):
            raise ValueError("Git object alternates are not allowed")

        real_path = resolve_workspace_path(
            repo_path or '.', require_directory=True)
        try:
            repo = git.Repo(real_path, search_parent_directories=False)
        except (git.InvalidGitRepositoryError, git.NoSuchPathError) as error:
            raise ValueError(f"Not a git repository: {repo_path}") from error

        # Linked worktrees keep a `gitdir:` pointer in a .git file. Symlinked
        # metadata and core.worktree can redirect similarly. Validate the
        # locations resolved by GitPython rather than the lexical .git marker.
        try:
            working_tree = resolve_workspace_path(
                repo.working_tree_dir or '', require_directory=True)
            git_dir = resolve_workspace_path(
                repo.git_dir, require_directory=True)
            common_dir = resolve_workspace_path(
                repo.common_dir, require_directory=True)
        except ValueError as error:
            raise ValueError(
                "Git worktree or metadata is outside the workspace") from error

        if working_tree != real_path:
            raise ValueError(
                "Git worktree does not match the requested repository")
        # Resolving these directories above is the security check. Keep the
        # final type check explicit so broken metadata fails closed.
        if not git_dir.is_dir() or not common_dir.is_dir():
            raise ValueError("Git metadata is unavailable")
        objects_dir = common_dir / "objects"
        try:
            canonical_objects_dir = resolve_workspace_path(
                str(objects_dir), require_directory=True)
        except ValueError as error:
            raise ValueError(
                "Git object database is outside the workspace") from error
        if canonical_objects_dir != objects_dir:
            raise ValueError("Git object database must not be a symlink")
        alternates_file = objects_dir / "info" / "alternates"
        if alternates_file.exists() or alternates_file.is_symlink():
            raise ValueError("Git object alternates are not allowed")
        return repo

    def _validate_repo_path(self, repo_path: str) -> str:
        """Resolve a repository strictly inside the configured workspace."""
        return str(self._open_repo(repo_path).working_tree_dir)

    @staticmethod
    def _validate_file_path(repo_path: str, file_path: str) -> str:
        """Return a repository-relative canonical file path for blame."""
        if Path(file_path).is_absolute():
            raise ValueError("Git file_path must be relative to the repository")
        resolved = resolve_workspace_path(
            file_path, base=Path(repo_path), require_file=True)
        return resolved.relative_to(Path(repo_path)).as_posix()

    @staticmethod
    def _resolve_commit(
            repo: git.Repo, revision: str, field_name: str) -> git.Commit:
        """Resolve untrusted revision text to a trusted hexadecimal commit."""
        if (not revision or len(revision) > 1024
                or revision != revision.strip()
                or revision.startswith('-')
                or '\x00' in revision or '\n' in revision or '\r' in revision):
            raise ValueError(f"Invalid Git {field_name}")
        try:
            return repo.commit(revision)
        except (git.BadName, git.BadObject, git.GitCommandError,
                ValueError) as error:
            raise ValueError(f"Invalid Git {field_name}") from error

    def semantic_diff(self, repo_path: str, ref1: str = "HEAD~1", ref2: str = "HEAD") -> dict:
        """语义化 diff 分析 — 返回变更统计 + 详细 diff"""
        repo = self._open_repo(repo_path)
        left = self._resolve_commit(repo, ref1, "ref1")
        right = self._resolve_commit(repo, ref2, "ref2")
        # Do not execute repository-configured external diff or textconv
        # helpers for a remotely selected workspace.
        diff = repo.git.diff(
            left.hexsha, right.hexsha, "--", stat=True,
            no_ext_diff=True, no_textconv=True)
        detailed = repo.git.diff(
            left.hexsha, right.hexsha, "--",
            no_ext_diff=True, no_textconv=True)
        return {
            "summary": diff,
            "detailed": detailed,
            "files_changed": len(right.diff(left))
        }

    def enhanced_log(self, repo_path: str, max_count: int = 20,
                     branch: Optional[str] = None) -> dict:
        """结构化 commit 日志 — 返回含文件列表的详细日志"""
        repo = self._open_repo(repo_path)
        rev = self._resolve_commit(repo, branch or 'HEAD', "branch").hexsha
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
        repo = self._open_repo(repo_path)
        safe_path = str(repo.working_tree_dir)
        safe_file_path = self._validate_file_path(safe_path, file_path)
        safe_ref = self._resolve_commit(repo, ref, "ref").hexsha
        blame_data = repo.blame(safe_ref, safe_file_path)
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
            "file_path": safe_file_path,
            "lines": lines,
            "total_lines": len(lines)
        }
