#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""tools/ 下验收脚本共用的「本机环境解析」。

## 为什么要有这个模块

这些脚本原先各自写死了开发机的路径作为兜底默认值。对别人（以及换了机器的自己）
那等于一个**必然失败的默认值**，而报错还发生在很远的地方 —— 表现为
「adb 找不到」，而不是「请先告诉我 SDK 在哪」。

现在的原则：**环境变量优先，其次从仓库自身读配置，最后才猜约定位置；
全都没有时给人一句能照做的话。**

## 解析优先级（第一个通过校验的胜出）

    1. 显式环境变量 ANDROID_HOME（兼容旧名 ANDROID_SDK_ROOT）
    2. 仓库根 local.properties 的 sdk.dir=
       —— 这是 Gradle 自己用的同一份事实来源，所以「能构建这个工程的人一定有」
    3. 约定俗成的安装位置（%LOCALAPPDATA%/Android/Sdk、~/Library/Android/sdk、~/Android/Sdk）

adb 额外支持 ADB 环境变量与 PATH。全都取不到时返回 None，由调用方决定怎么报错。

## Windows 上 local.properties 的转义

Java Properties 文件里盘符的冒号要转义：`sdk.dir=C\\:/Users/you/Android/Sdk`
（不转义 Gradle 会报 PropertyEscape）。这里按同一套规则反解。
"""
from __future__ import annotations

import os
import re
import shutil
import sys

IS_WINDOWS = os.name == "nt"

MISSING_ADB_HINT = (
    "找不到 adb。任选一种：\n"
    "  1) 设置环境变量 ANDROID_HOME（Windows 用 C:/... 风格，Git Bash 的 /c/... 它不认）\n"
    "  2) 设置环境变量 ADB 指向 adb 可执行文件\n"
    "  3) 在仓库根 local.properties 写 sdk.dir=<你的 Android SDK 路径>\n"
    "  参考仓库根的 local.properties.example。"
)

MISSING_SDK_HINT = (
    "找不到 Android SDK。任选一种：\n"
    "  1) 设置环境变量 ANDROID_HOME\n"
    "  2) 在仓库根 local.properties 写 sdk.dir=<你的 Android SDK 路径>"
)


def repo_root() -> str:
    """仓库根 = tools/ 的上一级。

    与**本文件**的位置绑定，不受调用方与 CWD 影响 —— 所以即使某个脚本
    自己没写过 `__file__`，也能正确定位仓库根。
    """
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def unescape_props(value: str) -> str:
    """按 Java Properties 规则反解转义：`C\\:/Users/you` -> `C:/Users/you`

    一次正则扫描，而不是先替换 `\\:` 再替换 `\\\\` —— 两次 replace 的顺序
    一旦写反，`C\\:\\\\x` 这类值就会解错，而且解错之后仍然是个「看起来像路径」
    的字符串，不会报错。
    """
    return re.sub(r"\\(.)", r"\1", value)


def read_property(key: str, repo: str | None = None,
                  filename: str = "local.properties") -> str | None:
    """从仓库根的 properties 文件里读一个键；文件不存在或没这个键时返回 None。

    逐行解析而不是用 configparser：Java Properties 文件没有 `[section]`，
    configparser 会直接抛 MissingSectionHeaderError。

    也不剥行内注释 —— Java Properties 里 `#` / `!` 只在**行首**才是注释，
    路径中间出现 `#` 是合法的（虽然罕见）。
    """
    path = os.path.join(repo or repo_root(), filename)
    if not os.path.isfile(path):
        return None
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            for line in f:
                line = line.strip()
                if not line or line[0] in "#!" or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                if k.strip() == key:
                    return unescape_props(v.strip())
    except OSError:
        return None
    return None


def find_android_sdk(repo: str | None = None) -> str | None:
    """返回 Android SDK 目录（已规范化）；取不到返回 None。"""
    # 用 `(os.environ.get(k) or "")` 而不是 `get(k, default)`：
    # 环境变量被设为**空串**时后者返回 ""，而 os.path.join("", "platform-tools",
    # "adb.exe") 会**静默变成相对路径** —— 不报错、看着能跑，实际在当前目录下找。
    # 这类静默错误比直接失败难查得多。
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        sdk = (os.environ.get(var) or "").strip()
        if sdk and os.path.isdir(sdk):
            return os.path.normpath(sdk)

    root = repo or repo_root()
    sdk = read_property("sdk.dir", root)
    if sdk:
        if not os.path.isabs(sdk):
            # Gradle 也按「工程根」解析相对的 sdk.dir，不是按当前工作目录
            sdk = os.path.join(root, sdk)
        if os.path.isdir(sdk):
            return os.path.normpath(sdk)

    home = os.path.expanduser("~")
    candidates = [
        os.path.join(home, "Library", "Android", "sdk"),   # macOS
        os.path.join(home, "Android", "Sdk"),              # Linux / 部分 Windows
        os.path.join(home, "Android", "sdk"),
        "/opt/android-sdk",
        "/usr/lib/android-sdk",
    ]
    if IS_WINDOWS:
        local = (os.environ.get("LOCALAPPDATA") or "").strip()
        if local:
            candidates.insert(0, os.path.join(local, "Android", "Sdk"))
    for candidate in candidates:
        if os.path.isdir(candidate):
            return os.path.normpath(candidate)
    return None


def find_adb(repo: str | None = None) -> str | None:
    """返回 adb 可执行文件路径；取不到返回 None。

    顺序与脚本原先的行为一致：`ADB` -> SDK（环境变量 / local.properties / 约定位置）
    -> PATH。**SDK 优先于 PATH**，否则 PATH 上一份陈旧的 adb 会抢先生效，
    而它可能不支持本项目用到的命令。
    """
    explicit = (os.environ.get("ADB") or "").strip()
    if explicit:
        if os.path.isfile(explicit):
            return explicit
        # 设了但不可用：明确说一声，再继续往下找。
        # 不说的话，用户会以为自己指定的 adb 生效了 —— 那又是一类静默错误。
        # 最常见的原因是路径风格不对：Windows 程序不认 Git Bash 的 /c/... 写法。
        print(f"⚠ 环境变量 ADB 指向的文件不可用，已忽略：{explicit}", file=sys.stderr)

    sdk = find_android_sdk(repo)
    if sdk:
        for name in (("adb.exe", "adb") if IS_WINDOWS else ("adb",)):
            candidate = os.path.join(sdk, "platform-tools", name)
            if os.path.isfile(candidate):
                return candidate

    on_path = shutil.which("adb")
    if on_path:
        return on_path
    return None


def require_adb_or_exit(repo: str | None = None) -> str:
    """成功返回 adb 路径；失败打印可选做法并以 1 退出。

    解析器本身返回 None 而不是自己退出，是为了让「谁负责报错」留在调用方 ——
    比如 verify_release_apk.py 要把它当成一条可报告的问题继续往下跑，
    而不是中断整轮自检。
    """
    adb = find_adb(repo)
    if not adb:
        print("❌ " + MISSING_ADB_HINT, file=sys.stderr)
        sys.exit(1)
    return adb
