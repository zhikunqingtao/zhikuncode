"""Canonical workspace path validation shared by public Python routes."""

from __future__ import annotations

import os
from pathlib import Path


class WorkspacePathError(ValueError):
    """Raised when a requested path cannot be safely resolved in the workspace."""


def _is_within(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def workspace_root() -> Path:
    """Return the default relative-path root as a canonical directory."""
    raw_root = os.getenv("WORKSPACE_ROOT", os.getcwd())
    try:
        root = Path(raw_root).expanduser().resolve(strict=True)
    except (OSError, RuntimeError) as error:
        raise WorkspacePathError("Configured workspace root is unavailable") from error
    if not root.is_dir():
        raise WorkspacePathError("Configured workspace root is not a directory")
    return root


def _local_picker_enabled() -> bool:
    return os.getenv("ZHIKUN_LOCAL_PICKER_ENABLED", "").strip().lower() in {
        "1", "true", "yes", "on",
    }


def _allowed_roots(default_root: Path) -> tuple[Path, ...] | None:
    """Return enforced roots, or ``None`` for explicit local-desktop mode.

    ``WORKSPACE_ROOT`` remains the anchor for relative requests. Production
    and remote deployments restrict absolute requests with
    ``ZHIKUN_WORKSPACE_ALLOWED_ROOTS``; when no allowed roots are configured,
    the default root remains the fail-closed boundary. Only the existing,
    explicitly enabled local picker mode may select an absolute Project
    elsewhere on the local machine.
    """
    configured = os.getenv("ZHIKUN_WORKSPACE_ALLOWED_ROOTS", "")
    values = [value.strip() for value in configured.split(",")
              if value.strip()]
    if values:
        roots: list[Path] = []
        for value in values:
            try:
                root = Path(value).resolve(strict=True)
            except (OSError, RuntimeError) as error:
                raise WorkspacePathError(
                    "Configured workspace allowed root is unavailable") from error
            if not root.is_dir():
                raise WorkspacePathError(
                    "Configured workspace allowed root is not a directory")
            if root not in roots:
                roots.append(root)
        return tuple(roots)
    if _local_picker_enabled():
        return None
    return (default_root,)


def _is_allowed(path: Path, roots: tuple[Path, ...] | None) -> bool:
    return roots is None or any(_is_within(path, root) for root in roots)


def resolve_workspace_path(
    raw_path: str,
    *,
    base: Path | None = None,
    require_directory: bool = False,
    require_file: bool = False,
) -> Path:
    """Resolve an absolute or relative path without allowing symlink escape.

    Relative paths are anchored at ``base`` when supplied, otherwise at
    ``WORKSPACE_ROOT``. Explicit allowed roots are always enforced. With no
    allowed roots, the default root remains the boundary unless the existing
    local-only directory picker mode is explicitly enabled.
    """
    if not raw_path or "\x00" in raw_path:
        raise WorkspacePathError("Path is empty or invalid")
    if require_directory and require_file:
        raise WorkspacePathError("Path cannot be both a directory and a file")

    root = workspace_root()
    allowed_roots = _allowed_roots(root)
    try:
        anchor = root if base is None else Path(base).resolve(strict=True)
    except (OSError, RuntimeError) as error:
        raise WorkspacePathError("Base path is unavailable") from error
    if base is not None:
        if not anchor.is_dir():
            raise WorkspacePathError("Base path is not a directory")
        if not _is_allowed(anchor, allowed_roots):
            raise WorkspacePathError("Base path is outside the workspace")

    # User-provided `~` is a workspace-relative name, not permission to inspect
    # the service account's home directory. Only the trusted root setting above
    # supports expanduser().
    requested = Path(raw_path)
    if not requested.is_absolute():
        requested = anchor / requested
    try:
        resolved = requested.resolve(strict=True)
        if base is not None:
            resolved.relative_to(anchor)
    except (ValueError, OSError, RuntimeError) as error:
        raise WorkspacePathError("Path is outside the workspace or does not exist") from error

    if base is None and not _is_allowed(resolved, allowed_roots):
        raise WorkspacePathError("Path is outside the configured workspace")

    if require_directory and not resolved.is_dir():
        raise WorkspacePathError("Path is not a directory")
    if require_file and not resolved.is_file():
        raise WorkspacePathError("Path is not a file")
    return resolved
