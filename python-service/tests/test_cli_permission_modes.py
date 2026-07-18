"""CLI 权限模式与后端 PermissionMode 协议的契约测试。"""

import inspect

from cli.main import PermissionMode, main


def test_cli_permission_modes_match_backend_contract():
    assert {mode.value for mode in PermissionMode} == {
        "default",
        "plan",
        "accept_edits",
        "dont_ask",
    }


def test_cli_does_not_expose_permission_bypass_flag():
    """非交互 CLI 只能选择保守模式，不能重新引入跳过全部安全检查的入口。"""
    assert "no_permissions" not in inspect.signature(main).parameters
    assert "skip_all_prompts" not in {mode.value for mode in PermissionMode}
