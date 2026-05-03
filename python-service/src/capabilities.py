"""
能力域注册表 + 依赖探测 — §4.14 / §2.4.3

每个能力域独立可选，缺少依赖时优雅降级而非崩溃。
P0/P1 仅启用 CODE_INTEL + FILE_PROCESSING 两个核心域。
"""

import importlib
import importlib.metadata
import logging
import shutil
from dataclasses import dataclass, field
from enum import Enum, auto
from typing import Dict

logger = logging.getLogger(__name__)


class CapabilityDomain(Enum):
    """Python 服务能力域枚举（架构裁决 #3: P0 核心域 + P2 保留域）

    已降级移除的能力域（由替代方案覆盖）:
    - SECURITY: BashTool 安全分析功能已覆盖 (~90%)
    - CODE_QUALITY: BashTool 代码质量检查功能已覆盖 (~85%)
    - VISUALIZATION: LLM 直接生成 Mermaid/SVG 可视化内容
    - DOC_GENERATION: LLM 直接生成 Markdown/文档内容
    """
    CODE_INTEL = auto()       # P0 — 代码智能: tree-sitter + rope + jedi
    GIT_ENHANCED = auto()     # P2 — Git 增强: GitPython
    FILE_PROCESSING = auto()  # P0 — 文件处理: chardet + python-magic + watchfiles
    BROWSER_AUTOMATION = auto()  # P2 — 浏览器自动化: playwright
    CODE_QUALITY = auto()     # P0 — 代码质量分析: radon + pygount (F3)
    ANALYSIS = auto()         # P0 — 分析服务: httpx + libcst + networkx (F25/F33/F35)


@dataclass
class CapabilityInfo:
    domain: CapabilityDomain
    name: str
    required_packages: list[str]
    min_versions: dict[str, str] = field(default_factory=dict)
    system_binaries: list[str] = field(default_factory=list)
    smoke_test: str = ""
    router_module: str = ""
    is_available: bool = False
    unavailable_reason: str = ""


# 能力域注册表
CAPABILITY_REGISTRY: Dict[CapabilityDomain, CapabilityInfo] = {
    CapabilityDomain.CODE_INTEL: CapabilityInfo(
        domain=CapabilityDomain.CODE_INTEL,
        name="代码智能",
        required_packages=["tree_sitter", "tree_sitter_languages", "rope", "jedi"],
        min_versions={"tree_sitter": "0.21.0", "tree_sitter_languages": "1.10.0"},
        router_module="routers.code_intel",
    ),
    CapabilityDomain.GIT_ENHANCED: CapabilityInfo(
        domain=CapabilityDomain.GIT_ENHANCED,
        name="Git 增强",
        required_packages=["git"],
        min_versions={"GitPython": "3.0.0"},
        system_binaries=["git"],
        router_module="routers.git_enhanced",
    ),
    CapabilityDomain.FILE_PROCESSING: CapabilityInfo(
        domain=CapabilityDomain.FILE_PROCESSING,
        name="文件处理",
        required_packages=["chardet"],
        router_module="routers.file_processing",
    ),
    CapabilityDomain.BROWSER_AUTOMATION: CapabilityInfo(
        domain=CapabilityDomain.BROWSER_AUTOMATION,
        name="浏览器自动化",
        required_packages=["playwright"],
        min_versions={"playwright": "1.40.0"},
        system_binaries=[],  # Playwright 自带浏览器二进制
        router_module="routers.browser",
    ),
    CapabilityDomain.CODE_QUALITY: CapabilityInfo(
        domain=CapabilityDomain.CODE_QUALITY,
        name="代码质量分析",
        required_packages=["radon", "pygount"],
        min_versions={"radon": "6.0.1", "pygount": "1.8.0"},
        router_module="routers.code_quality",
    ),
    CapabilityDomain.ANALYSIS: CapabilityInfo(
        domain=CapabilityDomain.ANALYSIS,
        name="分析服务",
        required_packages=["httpx", "libcst", "networkx"],
        min_versions={"libcst": "1.1.0", "networkx": "3.2"},
        smoke_test="import networkx; import libcst",
        router_module="routers.analysis",
    ),
}


def check_capability(info: CapabilityInfo) -> bool:
    """
    四层能力检测: 包导入 + 版本检查 + 系统二进制 + 冒烟测试。
    任何一层失败都标记为不可用。
    """
    reasons: list[str] = []

    # 层1: 包导入检查
    missing = []
    for pkg in info.required_packages:
        try:
            importlib.import_module(pkg)
        except ImportError:
            missing.append(pkg)
    if missing:
        reasons.append(f"缺少 Python 包: {', '.join(missing)}")

    # 层2: 版本兼容性检查
    for pkg, min_ver in info.min_versions.items():
        try:
            installed_ver = importlib.metadata.version(pkg.replace("_", "-"))
            from packaging.version import Version
            if Version(installed_ver) < Version(min_ver):
                reasons.append(f"{pkg} 版本过低: {installed_ver} < {min_ver}")
        except Exception:
            pass  # 包不存在已在层1捕获

    # 层3: 系统二进制依赖检查
    for binary in info.system_binaries:
        if shutil.which(binary) is None:
            reasons.append(f"缺少系统二进制: {binary}")

    if reasons:
        info.is_available = False
        info.unavailable_reason = "; ".join(reasons)
        return False
    info.is_available = True
    return True


def discover_capabilities() -> Dict[CapabilityDomain, CapabilityInfo]:
    """启动时探测所有能力域的可用性 — 缺少的能力优雅降级而非崩溃"""
    available_count = 0
    for domain, info in CAPABILITY_REGISTRY.items():
        available = check_capability(info)
        if available:
            logger.info(f"✓ 能力域 [{info.name}] 已就绪")
            available_count += 1
        else:
            logger.warning(f"✗ 能力域 [{info.name}] 不可用: {info.unavailable_reason}")
    logger.info(f"能力探测完成: {available_count}/{len(CAPABILITY_REGISTRY)} 个能力域可用")
    return CAPABILITY_REGISTRY
