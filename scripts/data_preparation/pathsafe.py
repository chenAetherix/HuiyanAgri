# -*- coding: utf-8 -*-
"""路径安全原语：外部输入字符串进路径前的校验 + 落盘前的边界校验。"""
import os
from pathlib import Path, PurePosixPath


def rel_parts(rel: str) -> tuple:
    """校验外部输入的相对路径：拒绝绝对路径、盘符、UNC 与上级目录引用。"""
    s = str(rel)
    p = PurePosixPath(s.replace("\\", "/"))
    if (p.is_absolute() or PurePosixPath(s).is_absolute()
            or any(part == os.pardir for part in p.parts)
            or any(":" in part for part in p.parts)):
        raise ValueError(f"illegal relative path: {rel!r}")
    return p.parts


def under(path: Path, base: Path) -> Path:
    """返回 path.resolve()，并断言其位于 base 之内（sink 级边界校验）。"""
    rp = Path(path).resolve()
    if not rp.is_relative_to(Path(base).resolve()):
        raise ValueError(f"path escapes base: {path}")
    return rp
