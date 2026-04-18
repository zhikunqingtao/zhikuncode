"""
test_capabilities.py — 能力域注册与探测逻辑测试
测试 CapabilityDomain 枚举、check_capability() 四层检测、discover_capabilities()
"""

import sys
import os
import pytest
from unittest.mock import patch, MagicMock
from dataclasses import dataclass

# 将 src 目录加入 sys.path
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from capabilities import (
    CapabilityDomain,
    CapabilityInfo,
    check_capability,
    discover_capabilities,
    CAPABILITY_REGISTRY,
)


class TestCapabilityDomain:
    """CapabilityDomain 枚举测试"""

    def test_has_four_domains(self):
        """应包含 4 个能力域（SECURITY/CODE_QUALITY/VISUALIZATION/DOC_GENERATION 已降级移除）"""
        assert len(CapabilityDomain) == 4

    def test_core_domains_exist(self):
        """P0 核心域: CODE_INTEL 和 FILE_PROCESSING 必须存在"""
        assert CapabilityDomain.CODE_INTEL is not None
        assert CapabilityDomain.FILE_PROCESSING is not None

    def test_all_domains_in_registry(self):
        """每个枚举值都应在注册表中"""
        for domain in CapabilityDomain:
            assert domain in CAPABILITY_REGISTRY, f"{domain.name} 不在注册表中"


class TestCapabilityInfo:
    """CapabilityInfo 数据类测试"""

    def test_registry_entries_have_required_fields(self):
        """注册表条目应有必需字段"""
        for domain, info in CAPABILITY_REGISTRY.items():
            assert info.domain == domain
            assert len(info.name) > 0
            assert isinstance(info.required_packages, list)
            assert len(info.required_packages) > 0

    def test_router_modules_defined(self):
        """每个能力域应有 router_module"""
        for domain, info in CAPABILITY_REGISTRY.items():
            assert info.router_module, f"{domain.name} 缺少 router_module"


class TestCheckCapability:
    """check_capability() 四层检测测试"""

    def test_available_when_all_packages_exist(self):
        """所有依赖包可导入时应标记为可用"""
        info = CapabilityInfo(
            domain=CapabilityDomain.CODE_INTEL,
            name="测试域",
            required_packages=["os", "sys"],  # 标准库，总是可用
            router_module="test.router",
        )
        result = check_capability(info)
        assert result is True
        assert info.is_available is True
        assert info.unavailable_reason == ""

    def test_unavailable_when_package_missing(self):
        """缺少依赖包时应标记为不可用"""
        info = CapabilityInfo(
            domain=CapabilityDomain.CODE_INTEL,
            name="测试域",
            required_packages=["nonexistent_package_xyz_12345"],
            router_module="test.router",
        )
        result = check_capability(info)
        assert result is False
        assert info.is_available is False
        assert "缺少 Python 包" in info.unavailable_reason

    def test_unavailable_when_binary_missing(self):
        """缺少系统二进制时应标记为不可用"""
        info = CapabilityInfo(
            domain=CapabilityDomain.CODE_INTEL,
            name="测试域",
            required_packages=["os"],
            system_binaries=["nonexistent_binary_xyz_12345"],
            router_module="test.router",
        )
        result = check_capability(info)
        assert result is False
        assert "缺少系统二进制" in info.unavailable_reason

    def test_multiple_reasons_joined(self):
        """多个失败原因应用分号拼接"""
        info = CapabilityInfo(
            domain=CapabilityDomain.CODE_INTEL,
            name="测试域",
            required_packages=["nonexistent_pkg_abc"],
            system_binaries=["nonexistent_bin_abc"],
            router_module="test.router",
        )
        result = check_capability(info)
        assert result is False
        assert ";" in info.unavailable_reason


class TestDiscoverCapabilities:
    """discover_capabilities() 测试"""

    def test_returns_registry(self):
        """应返回完整的 CAPABILITY_REGISTRY"""
        result = discover_capabilities()
        assert result is CAPABILITY_REGISTRY
        assert len(result) == 4

    def test_all_entries_checked(self):
        """调用后每个条目的 is_available 应为 bool 值"""
        discover_capabilities()
        for domain, info in CAPABILITY_REGISTRY.items():
            assert isinstance(info.is_available, bool), f"{domain.name} is_available 未被设置"

    def test_unavailable_has_reason(self):
        """不可用的能力域应有原因说明"""
        discover_capabilities()
        for domain, info in CAPABILITY_REGISTRY.items():
            if not info.is_available:
                assert len(info.unavailable_reason) > 0, f"{domain.name} 不可用但无原因"
