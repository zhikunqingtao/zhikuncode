"""
文件编码和类型检测器 — §4.14.7

chardet 自动检测文件编码（解决非 UTF-8 文件读取问题），
python-magic 通过 libmagic 精确识别文件 MIME 类型（不依赖扩展名）。
"""

import logging
from dataclasses import dataclass
from typing import Optional

logger = logging.getLogger(__name__)

try:
    import chardet

    _CHARDET_AVAILABLE = True
except ImportError:
    _CHARDET_AVAILABLE = False
    chardet = None  # type: ignore

try:
    import magic as _magic_mod

    _MAGIC_AVAILABLE = True
except ImportError:
    _MAGIC_AVAILABLE = False
    _magic_mod = None  # type: ignore


@dataclass
class EncodingResult:
    encoding: str        # 检测到的编码 (如 "utf-8", "gbk", "shift_jis")
    confidence: float    # 置信度 (0.0 - 1.0)
    language: str        # 检测到的语言 (如 "Chinese", "Japanese")


@dataclass
class FileTypeResult:
    mime_type: str       # MIME 类型 (如 "text/x-python")
    description: str     # 描述 (如 "Python script, ASCII text executable")
    is_text: bool        # 是否为文本文件
    is_binary: bool      # 是否为二进制文件


class FileDetector:
    """文件编码和类型检测器"""

    def __init__(self) -> None:
        self._magic_mime: Optional[object] = None
        self._magic_desc: Optional[object] = None
        if _MAGIC_AVAILABLE:
            try:
                self._magic_mime = _magic_mod.Magic(mime=True)
                self._magic_desc = _magic_mod.Magic()
            except Exception as e:
                logger.warning(f"python-magic 初始化失败: {e}")

    @staticmethod
    def is_chardet_available() -> bool:
        return _CHARDET_AVAILABLE

    @staticmethod
    def is_magic_available() -> bool:
        return _MAGIC_AVAILABLE

    def detect_encoding(self, file_path: str) -> EncodingResult:
        """检测文件编码 — 解决非 UTF-8 文件的正确读取"""
        if not _CHARDET_AVAILABLE:
            return EncodingResult(encoding="utf-8", confidence=0.5, language="")
        with open(file_path, "rb") as f:
            raw = f.read(65536)  # 读取前 64KB 用于检测
        result = chardet.detect(raw)
        return EncodingResult(
            encoding=result.get("encoding") or "utf-8",
            confidence=result.get("confidence") or 0.0,
            language=result.get("language") or "",
        )

    def detect_encoding_bytes(self, raw: bytes) -> EncodingResult:
        """从字节数据检测编码"""
        if not _CHARDET_AVAILABLE:
            return EncodingResult(encoding="utf-8", confidence=0.5, language="")
        result = chardet.detect(raw)
        return EncodingResult(
            encoding=result.get("encoding") or "utf-8",
            confidence=result.get("confidence") or 0.0,
            language=result.get("language") or "",
        )

    def detect_type(self, file_path: str) -> FileTypeResult:
        """检测文件 MIME 类型 — 不依赖扩展名"""
        if self._magic_mime is None or self._magic_desc is None:
            return FileTypeResult(
                mime_type="application/octet-stream",
                description="unknown (python-magic not available)",
                is_text=False,
                is_binary=True,
            )
        mime = self._magic_mime.from_file(file_path)
        desc = self._magic_desc.from_file(file_path)
        return FileTypeResult(
            mime_type=mime,
            description=desc,
            is_text=mime.startswith("text/") or mime == "application/json",
            is_binary=not mime.startswith("text/"),
        )

    def safe_read(self, file_path: str) -> tuple[str, str]:
        """安全读取文件 — 自动检测编码后解码"""
        encoding_result = self.detect_encoding(file_path)
        encoding = encoding_result.encoding
        try:
            with open(file_path, "r", encoding=encoding) as f:
                return f.read(), encoding
        except (UnicodeDecodeError, LookupError):
            # 降级到 utf-8 with errors='replace'
            with open(file_path, "r", encoding="utf-8", errors="replace") as f:
                return f.read(), "utf-8 (fallback)"
