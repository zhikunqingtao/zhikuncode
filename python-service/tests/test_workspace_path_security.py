"""Workspace containment regressions for externally reachable Python routes."""

import json
import os
import shutil
import sys
from pathlib import Path

import git
import pytest
from fastapi import HTTPException
from pydantic import ValidationError

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from routers.analysis import (  # noqa: E402
    APIEndpointRequest,
    ChangeImpactRequest,
    CodePathRequest,
    DiagramRequest,
    analyze_change_impact,
    generate_diagram,
    scan_api_endpoints,
    trace_code_path,
)
from routers.code_quality import ComplexityRequest, analyze_complexity  # noqa: E402
from routers.file_processing import (  # noqa: E402
    EncodingRequest,
    FileTreeRequest,
    MimeRequest,
    SafeReadRequest,
    detect_encoding,
    detect_type,
    get_file_tree,
    safe_read,
    watch_files,
)
from analyzers.call_graph_builder import CallGraphBuilder  # noqa: E402
from analyzers.flow_chart_generator import FlowChartGenerator  # noqa: E402
from services.complexity_analyzer import ComplexityAnalyzer  # noqa: E402
from services.git_enhanced_service import GitEnhancedService  # noqa: E402
from workspace_paths import WorkspacePathError, resolve_workspace_path  # noqa: E402


@pytest.fixture(autouse=True)
def _isolated_workspace_policy(monkeypatch):
    """Keep path-policy tests independent of the developer's shell config."""
    monkeypatch.delenv("ZHIKUN_LOCAL_PICKER_ENABLED", raising=False)
    monkeypatch.delenv("ZHIKUN_WORKSPACE_ALLOWED_ROOTS", raising=False)


def _create_git_repo(path):
    path.mkdir(parents=True)
    repo = git.Repo.init(path)
    actor = git.Actor("Security Test", "security-test@example.invalid")
    source = path / "main.py"
    source.write_text("value = 1\n", encoding="utf-8")
    repo.index.add(["main.py"])
    repo.index.commit("initial", author=actor, committer=actor)
    source.write_text("value = 2\n", encoding="utf-8")
    repo.index.add(["main.py"])
    repo.index.commit("update", author=actor, committer=actor)
    return repo


def test_resolve_workspace_path_accepts_relative_descendant(monkeypatch, tmp_path):
    workspace = tmp_path / "workspace"
    project = workspace / "project"
    project.mkdir(parents=True)
    monkeypatch.setenv("WORKSPACE_ROOT", str(workspace))

    assert resolve_workspace_path("project", require_directory=True) == project.resolve()


def test_resolve_workspace_path_rejects_absolute_outside_path(monkeypatch, tmp_path):
    workspace = tmp_path / "workspace"
    outside = tmp_path / "outside"
    workspace.mkdir()
    outside.mkdir()
    monkeypatch.setenv("WORKSPACE_ROOT", str(workspace))

    with pytest.raises(WorkspacePathError):
        resolve_workspace_path(str(outside), require_directory=True)


def test_resolve_workspace_path_rejects_symlink_escape(monkeypatch, tmp_path):
    workspace = tmp_path / "workspace"
    outside = tmp_path / "outside"
    workspace.mkdir()
    outside.mkdir()
    escape = workspace / "escape"
    escape.symlink_to(outside, target_is_directory=True)
    monkeypatch.setenv("WORKSPACE_ROOT", str(workspace))

    with pytest.raises(WorkspacePathError):
        resolve_workspace_path(str(escape), require_directory=True)


def test_local_picker_accepts_external_absolute_project_but_keeps_default_anchor(
        monkeypatch, tmp_path):
    workspace = tmp_path / "workspace"
    relative_project = workspace / "relative-project"
    external_project = tmp_path / "external-project"
    relative_project.mkdir(parents=True)
    external_project.mkdir()
    monkeypatch.setenv("WORKSPACE_ROOT", str(workspace))
    monkeypatch.setenv("ZHIKUN_LOCAL_PICKER_ENABLED", "true")

    assert resolve_workspace_path(
        str(external_project), require_directory=True) == external_project.resolve()
    assert resolve_workspace_path(
        "relative-project", require_directory=True) == relative_project.resolve()


def test_allowed_roots_override_local_picker_mode(monkeypatch, tmp_path):
    workspace = tmp_path / "workspace"
    allowed = tmp_path / "allowed"
    outside = tmp_path / "outside"
    workspace.mkdir()
    allowed.mkdir()
    outside.mkdir()
    monkeypatch.setenv("WORKSPACE_ROOT", str(workspace))
    monkeypatch.setenv("ZHIKUN_LOCAL_PICKER_ENABLED", "true")
    monkeypatch.setenv("ZHIKUN_WORKSPACE_ALLOWED_ROOTS", str(allowed))

    assert resolve_workspace_path(
        str(allowed), require_directory=True) == allowed.resolve()
    with pytest.raises(WorkspacePathError):
        resolve_workspace_path(str(outside), require_directory=True)


def test_explicitly_disabled_local_picker_fails_closed(monkeypatch, tmp_path):
    workspace = tmp_path / "workspace"
    outside = tmp_path / "outside"
    workspace.mkdir()
    outside.mkdir()
    monkeypatch.setenv("WORKSPACE_ROOT", str(workspace))
    monkeypatch.setenv("ZHIKUN_LOCAL_PICKER_ENABLED", "false")

    with pytest.raises(WorkspacePathError):
        resolve_workspace_path(str(outside), require_directory=True)


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("handler", "request_type"),
    [
        (detect_encoding, EncodingRequest),
        (detect_type, MimeRequest),
        (safe_read, SafeReadRequest),
    ],
)
async def test_file_inspection_endpoints_reject_files_outside_workspace(
        monkeypatch, tmp_path, handler, request_type):
    workspace = tmp_path / "workspace"
    outside_file = tmp_path / "outside.txt"
    workspace.mkdir()
    outside_file.write_text("private", encoding="utf-8")
    monkeypatch.setenv("WORKSPACE_ROOT", str(workspace))

    with pytest.raises(HTTPException) as raised:
        await handler(request_type(file_path=str(outside_file)))

    assert raised.value.status_code == 400


@pytest.mark.asyncio
async def test_watch_endpoint_rejects_directory_outside_workspace(
        monkeypatch, tmp_path):
    workspace = tmp_path / "workspace"
    outside = tmp_path / "outside"
    workspace.mkdir()
    outside.mkdir()
    monkeypatch.setenv("WORKSPACE_ROOT", str(workspace))

    with pytest.raises(HTTPException) as raised:
        await watch_files(path=str(outside), extensions="")

    assert raised.value.status_code == 400


@pytest.mark.asyncio
@pytest.mark.parametrize("endpoint", ["diagram", "api-endpoints"])
async def test_recursive_analysis_endpoints_reject_roots_outside_workspace(
        monkeypatch, tmp_path, endpoint):
    workspace = tmp_path / "workspace"
    outside = tmp_path / "outside"
    workspace.mkdir()
    outside.mkdir()
    monkeypatch.setenv("WORKSPACE_ROOT", str(workspace))

    with pytest.raises(HTTPException) as raised:
        if endpoint == "diagram":
            await generate_diagram(DiagramRequest(
                diagram_type="sequence", target="handler",
                project_root=str(outside)))
        else:
            await scan_api_endpoints(APIEndpointRequest(
                project_root=str(outside)))

    assert raised.value.status_code == 400


@pytest.mark.asyncio
async def test_code_path_endpoint_rejects_entry_file_outside_project(
        monkeypatch, tmp_path):
    workspace = tmp_path / "workspace"
    project = workspace / "project"
    outside_file = workspace / "outside.py"
    project.mkdir(parents=True)
    outside_file.write_text("def outside(): pass\n", encoding="utf-8")
    monkeypatch.setenv("WORKSPACE_ROOT", str(workspace))

    with pytest.raises(HTTPException) as raised:
        await trace_code_path(CodePathRequest(
            project_root=str(project),
            entry_file=str(outside_file),
            entry_function="outside",
        ))

    assert raised.value.status_code == 400


@pytest.mark.parametrize("max_depth", [-1, 21])
def test_file_tree_rejects_unbounded_depth(max_depth):
    with pytest.raises(ValidationError):
        FileTreeRequest(root_path=".", max_depth=max_depth)


@pytest.mark.asyncio
async def test_file_tree_does_not_follow_child_symlinks(monkeypatch, tmp_path):
    workspace = tmp_path / "workspace"
    external = tmp_path / "external"
    workspace.mkdir()
    external.mkdir()
    (workspace / "visible.txt").write_text("visible", encoding="utf-8")
    (external / "secret.txt").write_text("secret", encoding="utf-8")
    (workspace / "escape").symlink_to(external, target_is_directory=True)
    monkeypatch.setenv("WORKSPACE_ROOT", str(workspace))

    response = await get_file_tree(FileTreeRequest(root_path=".", max_depth=3))
    children = response["data"].children or []

    assert {child.name for child in children} == {"visible.txt"}


def test_complexity_scanner_does_not_follow_nested_symlinks(tmp_path):
    project = tmp_path / "project"
    external = tmp_path / "external"
    project.mkdir()
    external.mkdir()
    (project / "inside.py").write_text("def inside():\n    return 1\n", encoding="utf-8")
    (external / "secret.py").write_text("def secret():\n    return 2\n", encoding="utf-8")
    (project / "escape.py").symlink_to(external / "secret.py")

    result = ComplexityAnalyzer(languages=["python"])._analyze_sync(
        str(project), None)
    serialized = result.model_dump_json()

    assert "inside.py" in serialized
    assert "escape.py" not in serialized
    assert "secret.py" not in serialized


def test_call_graph_file_iterator_skips_symlink_files(tmp_path):
    project = tmp_path / "project"
    external = tmp_path / "external"
    project.mkdir()
    external.mkdir()
    inside = project / "inside.py"
    inside.write_text("def inside():\n    return 1\n", encoding="utf-8")
    outside = external / "secret.py"
    outside.write_text("def secret():\n    return 2\n", encoding="utf-8")
    (project / "escape.py").symlink_to(outside)

    paths = list(CallGraphBuilder()._iter_files(
        project, {".py"}, skip_tests=False))

    assert paths == [inside]


def test_flow_chart_generator_skips_symlink_files(tmp_path):
    project = tmp_path / "project"
    external = tmp_path / "external"
    project.mkdir()
    external.mkdir()
    outside = external / "outside.py"
    outside.write_text(
        "def leaked_method():\n    return 'outside'\n", encoding="utf-8")
    (project / "linked.py").symlink_to(outside)

    assert FlowChartGenerator(str(project))._find_method("leaked_method") is None


def test_git_service_rejects_repository_outside_workspace(monkeypatch, tmp_path):
    workspace = tmp_path / "workspace"
    outside_repo = tmp_path / "outside-repo"
    workspace.mkdir()
    (outside_repo / ".git").mkdir(parents=True)
    monkeypatch.setenv("WORKSPACE_ROOT", str(workspace))

    with pytest.raises(ValueError):
        GitEnhancedService()._validate_repo_path(str(outside_repo))


def test_git_service_accepts_normal_repository_in_workspace(monkeypatch, tmp_path):
    workspace = tmp_path / "workspace"
    repo_path = workspace / "repo"
    _create_git_repo(repo_path)
    monkeypatch.setenv("WORKSPACE_ROOT", str(workspace))

    service = GitEnhancedService()

    assert service._validate_repo_path(str(repo_path)) == str(repo_path.resolve())
    assert service.semantic_diff(str(repo_path), "HEAD~1", "HEAD")["files_changed"] == 1
    assert service.enhanced_log(str(repo_path), 10, "HEAD")["total"] == 2
    assert service.file_blame(str(repo_path), "main.py", "HEAD")["total_lines"] == 1


def test_git_service_resolves_dot_against_workspace_root(monkeypatch, tmp_path):
    workspace = tmp_path / "workspace"
    _create_git_repo(workspace)
    monkeypatch.setenv("WORKSPACE_ROOT", str(workspace))

    assert GitEnhancedService()._validate_repo_path(".") == str(workspace.resolve())


def test_git_service_rejects_symlinked_git_metadata_escape(monkeypatch, tmp_path):
    workspace = tmp_path / "workspace"
    facade = workspace / "facade"
    outside_repo = tmp_path / "outside-repo"
    workspace.mkdir()
    facade.mkdir()
    outside = _create_git_repo(outside_repo)
    (facade / ".git").symlink_to(outside.git_dir, target_is_directory=True)
    (facade / "main.py").write_text("value = 2\n", encoding="utf-8")
    monkeypatch.setenv("WORKSPACE_ROOT", str(workspace))

    with pytest.raises(ValueError, match="metadata is outside"):
        GitEnhancedService()._validate_repo_path(str(facade))


def test_git_service_rejects_linked_worktree_with_external_common_dir(
        monkeypatch, tmp_path):
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    outside_repo = _create_git_repo(tmp_path / "outside-main")
    linked = workspace / "linked"
    outside_repo.git.worktree("add", "-b", "external-linked", str(linked), "HEAD")
    monkeypatch.setenv("WORKSPACE_ROOT", str(workspace))

    with pytest.raises(ValueError, match="metadata is outside"):
        GitEnhancedService()._validate_repo_path(str(linked))


def test_git_service_accepts_linked_worktree_when_metadata_stays_in_workspace(
        monkeypatch, tmp_path):
    workspace = tmp_path / "workspace"
    main_repo = _create_git_repo(workspace / "main")
    linked = workspace / "linked"
    main_repo.git.worktree("add", "-b", "safe-linked", str(linked), "HEAD")
    monkeypatch.setenv("WORKSPACE_ROOT", str(workspace))

    assert GitEnhancedService()._validate_repo_path(str(linked)) == str(linked.resolve())


@pytest.mark.parametrize("relative_entry", [False, True], ids=["absolute", "relative"])
def test_git_service_rejects_object_alternates(
        monkeypatch, tmp_path, relative_entry):
    workspace = tmp_path / "workspace"
    repo = _create_git_repo(workspace / "repo")
    outside = _create_git_repo(tmp_path / "outside-repo")
    objects_dir = Path(repo.common_dir) / "objects"
    outside_objects = Path(outside.common_dir) / "objects"
    alternate = (os.path.relpath(outside_objects, objects_dir)
                 if relative_entry else str(outside_objects))
    info_dir = objects_dir / "info"
    info_dir.mkdir(exist_ok=True)
    (info_dir / "alternates").write_text(alternate + "\n", encoding="utf-8")
    monkeypatch.setenv("WORKSPACE_ROOT", str(workspace))

    with pytest.raises(ValueError, match="object alternates are not allowed"):
        GitEnhancedService()._validate_repo_path(str(repo.working_tree_dir))


def test_git_service_rejects_object_alternates_from_environment(
        monkeypatch, tmp_path):
    workspace = tmp_path / "workspace"
    repo = _create_git_repo(workspace / "repo")
    outside = _create_git_repo(tmp_path / "outside-repo")
    monkeypatch.setenv("WORKSPACE_ROOT", str(workspace))
    monkeypatch.setenv(
        "GIT_ALTERNATE_OBJECT_DIRECTORIES",
        str(Path(outside.common_dir) / "objects"))

    with pytest.raises(ValueError, match="object alternates are not allowed"):
        GitEnhancedService()._validate_repo_path(str(repo.working_tree_dir))


def test_git_service_rejects_symlinked_object_database(monkeypatch, tmp_path):
    workspace = tmp_path / "workspace"
    repo = _create_git_repo(workspace / "repo")
    outside = _create_git_repo(tmp_path / "outside-repo")
    objects_dir = Path(repo.common_dir) / "objects"
    shutil.rmtree(objects_dir)
    objects_dir.symlink_to(
        Path(outside.common_dir) / "objects", target_is_directory=True)
    monkeypatch.setenv("WORKSPACE_ROOT", str(workspace))

    with pytest.raises(ValueError, match="object database is outside"):
        GitEnhancedService()._validate_repo_path(str(repo.working_tree_dir))


@pytest.mark.parametrize("field", ["ref1", "ref2", "branch", "ref"])
def test_git_revision_options_are_rejected_before_git_execution(
        monkeypatch, tmp_path, field):
    workspace = tmp_path / "workspace"
    repo_path = workspace / "repo"
    _create_git_repo(repo_path)
    monkeypatch.setenv("WORKSPACE_ROOT", str(workspace))
    service = GitEnhancedService()
    marker = workspace / "must-not-be-created"
    injected = f"--output={marker}"

    with pytest.raises(ValueError, match=f"Invalid Git {field}"):
        if field == "ref1":
            service.semantic_diff(str(repo_path), injected, "HEAD")
        elif field == "ref2":
            service.semantic_diff(str(repo_path), "HEAD~1", injected)
        elif field == "branch":
            service.enhanced_log(str(repo_path), 10, injected)
        else:
            service.file_blame(str(repo_path), "main.py", injected)

    assert not marker.exists()


def test_git_unknown_revision_is_reported_as_invalid_input(monkeypatch, tmp_path):
    workspace = tmp_path / "workspace"
    repo_path = workspace / "repo"
    _create_git_repo(repo_path)
    monkeypatch.setenv("WORKSPACE_ROOT", str(workspace))

    with pytest.raises(ValueError, match="Invalid Git ref1"):
        GitEnhancedService().semantic_diff(
            str(repo_path), "definitely-not-a-ref", "HEAD")


def test_git_diff_disables_repository_external_diff_helper(monkeypatch, tmp_path):
    workspace = tmp_path / "workspace"
    repo_path = workspace / "repo"
    repo = _create_git_repo(repo_path)
    marker = workspace / "external-diff-ran"
    helper = workspace / "external-diff.sh"
    helper.write_text(
        f"#!/bin/sh\nprintf ran > '{marker}'\n",
        encoding="utf-8")
    helper.chmod(0o700)
    with repo.config_writer() as config:
        config.set_value("diff", "external", str(helper))
    monkeypatch.setenv("WORKSPACE_ROOT", str(workspace))

    GitEnhancedService().semantic_diff(str(repo_path), "HEAD~1", "HEAD")

    assert not marker.exists()


def test_git_blame_requires_repository_relative_file_path(monkeypatch, tmp_path):
    workspace = tmp_path / "workspace"
    repo_path = workspace / "repo"
    _create_git_repo(repo_path)
    monkeypatch.setenv("WORKSPACE_ROOT", str(workspace))

    with pytest.raises(ValueError, match="must be relative"):
        GitEnhancedService().file_blame(
            str(repo_path), str(repo_path / "main.py"), "HEAD")


def test_git_blame_rejects_symlink_file_escape(monkeypatch, tmp_path):
    workspace = tmp_path / "workspace"
    repo_path = workspace / "repo"
    _create_git_repo(repo_path)
    outside = tmp_path / "outside.py"
    outside.write_text("secret = True\n", encoding="utf-8")
    (repo_path / "escape.py").symlink_to(outside)
    monkeypatch.setenv("WORKSPACE_ROOT", str(workspace))

    with pytest.raises(ValueError, match="outside the workspace"):
        GitEnhancedService().file_blame(
            str(repo_path), "escape.py", "HEAD")


@pytest.mark.asyncio
async def test_change_impact_rejects_project_outside_workspace(monkeypatch, tmp_path):
    workspace = tmp_path / "workspace"
    outside = tmp_path / "outside"
    workspace.mkdir()
    outside.mkdir()
    outside_file = outside / "source.py"
    outside_file.write_text("print('outside')", encoding="utf-8")
    monkeypatch.setenv("WORKSPACE_ROOT", str(workspace))

    response = await analyze_change_impact(ChangeImpactRequest(
        file_path=str(outside_file), changed_lines=[1],
        project_root=str(outside), depth=1))
    body = json.loads(response.body)

    assert response.status_code == 400
    assert body["error"]["code"] == "FILE_OUTSIDE_PROJECT"


@pytest.mark.asyncio
async def test_complexity_rejects_project_outside_workspace(monkeypatch, tmp_path):
    workspace = tmp_path / "workspace"
    outside = tmp_path / "outside"
    workspace.mkdir()
    outside.mkdir()
    monkeypatch.setenv("WORKSPACE_ROOT", str(workspace))

    with pytest.raises(HTTPException) as raised:
        await analyze_complexity(ComplexityRequest(project_root=str(outside)))

    assert raised.value.status_code == 400
