#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""发布包自检 —— 上传到 GitHub Release 之前跑一遍。

用法：
    python tools/verify_release_apk.py [APK 路径]
    # 默认路径 app/build/outputs/apk/release/app-release.apk

这个脚本回答的是「这个包能不能发出去」，而不是「功能对不对」。
功能由 verify_phaseN.py 负责，这里只管发布前必须过的硬门槛。

## 为什么要单独做这一步

`assembleRelease` 成功 ≠ 包能发。下面每一条都真的会让人白跑一趟：

1. **把 debug 证书出的包当发布包发出去。**
   `keystore.properties` 缺失时构建会「静默退化为未签名包」，
   但如果哪天有人往 signingConfigs 里塞了 debug 签名，产物照样叫
   `app-release.apk`，签名却是 `CN=Android Debug` —— 上架会被拒，
   用户覆盖安装也会失败。所以这里显式比对证书主体。

2. **签名方案只有 v1。**
   Android 7+ 用 v2 校验完整性，minSdk 26 的包必须带 v2/v3。
   只有 v1 的包在部分设备上装不上。

3. **密钥进了包。**
   API Key、签名口令这类东西只要被硬编码或误打包，转公开、
   发 Release 都是灾难。这里同时扫明文串和 keystore 口令。
"""
from __future__ import annotations

import glob
import hashlib
import os
import re
import shutil
import subprocess
import sys
import time
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SDK = os.environ.get("ANDROID_HOME", "C:/Users/a/Android/Sdk")

# ------------------------------------------------ 检查哪个版本
#
# 同一份代码出两个 flavor，applicationId 与显示名都不同，期望值也就不同。
# 用 --edition 指定（也可用环境变量），默认 base —— 它的 applicationId
# 与历史版本一致，是「本来那个包」。
_argv = sys.argv[1:]
EDITION = os.environ.get("EDITION", "base")
if "--edition" in _argv:
    _i = _argv.index("--edition")
    if _i + 1 >= len(_argv):
        print("--edition 需要跟一个值：base 或 full", file=sys.stderr)
        sys.exit(2)
    EDITION = _argv[_i + 1]
    del _argv[_i:_i + 2]

EDITIONS = {
    "base": ("com.plantidentify", "Plant Identify Library"),
    "full": ("com.plantidentify.full", "Plant Identify Library（完整版）"),
}
if EDITION not in EDITIONS:
    print(f"未知版本 {EDITION}，可选：{', '.join(EDITIONS)}", file=sys.stderr)
    sys.exit(2)

# 与 app/build.gradle.kts 保持一致
EXPECT_PACKAGE, EXPECT_LABEL = EDITIONS[EDITION]
EXPECT_VERSION_NAME = "1.0.0"
DEFAULT_APK = os.path.join(
    REPO, "app", "build", "outputs", "apk", EDITION, "release",
    f"app-{EDITION}-release.apk",
)

passed = 0
failed = 0


def check(label: str, ok: bool, detail: str = "") -> bool:
    global passed, failed
    if ok:
        passed += 1
        print(f"  [PASS] {label}")
    else:
        failed += 1
        print(f"  [FAIL] {label}" + (f"  —— {detail}" if detail else ""))
    return ok


def newest_build_tool(name: str) -> str | None:
    root = os.path.join(SDK, "build-tools")
    if not os.path.isdir(root):
        return None
    for ver in sorted(os.listdir(root), reverse=True):
        for ext in (".exe", ".bat", ""):
            path = os.path.join(root, ver, name + ext)
            if os.path.isfile(path):
                return path
    return None


def find_java_home() -> str | None:
    """找出可用的 JDK 目录。

    `apksigner` 本身是个 Java 程序：缺 JAVA_HOME 时它只打一行
    「JAVA_HOME is not set」就退出，签名校验整个跳过。
    而本项目 JDK 装在非 PATH 位置，每次都要显式指定 —— 让人在
    自检脚本前再记一次这台机器的路径纯属找麻烦，所以这里自己找：
    显式环境变量 → gradle.properties 的 org.gradle.java.home → PATH 里的 java。
    """
    jh = os.environ.get("JAVA_HOME")
    if jh:
        for exe in ("java.exe", "java"):
            if os.path.isfile(os.path.join(jh, "bin", exe)):
                return jh

    props = os.path.join(REPO, "gradle.properties")
    if os.path.isfile(props):
        with open(props, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line.startswith("org.gradle.java.home="):
                    candidate = line.split("=", 1)[1].strip().replace("\\:", ":")
                    if os.path.isdir(candidate):
                        return candidate

    found = shutil.which("java")
    if found:
        return os.path.dirname(os.path.dirname(found))

    # 最后几个约定俗成的位置。本项目的 JDK 装在 %USERPROFILE%/jdk/<版本>，
    # 刻意不在 PATH 里 —— 构建时显式设 JAVA_HOME 是明确的，但要求
    # 「跑自检脚本前再记一次这台机器的路径」只是找麻烦。
    patterns = (
        os.path.join(os.path.expanduser("~"), "jdk", "*"),
        os.path.join(os.path.expanduser("~"), ".jdks", "*"),
        os.path.join(os.path.expanduser("~"), ".sdkman", "candidates", "java", "*"),
        "C:/Program Files/Java/*",
        "C:/Program Files/Eclipse Adoptium/*",
        "C:/Program Files/Microsoft/*",
    )
    for pattern in patterns:
        for candidate in sorted(glob.glob(pattern), reverse=True):
            for exe in ("java.exe", "java"):
                if os.path.isfile(os.path.join(candidate, "bin", exe)):
                    return candidate
    return None


JAVA_HOME = find_java_home()


def run(cmd: list[str], timeout: int = 180) -> tuple[int, str]:
    env = dict(os.environ)
    if JAVA_HOME:
        env["JAVA_HOME"] = JAVA_HOME
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, env=env,
                              timeout=timeout, encoding="utf-8", errors="replace")
        return proc.returncode, (proc.stdout or "") + (proc.stderr or "")
    except Exception as exc:  # noqa: BLE001 - 工具缺失时如实报错，不要静默
        return -1, f"{type(exc).__name__}: {exc}"


# ---------------------------------------------------------------- 0. 前置

apk = _argv[0] if _argv else DEFAULT_APK
apk = os.path.abspath(apk)

print(f"[0] 产物与工具（版本：{EDITION}）")
if not check(f"APK 存在：{os.path.relpath(apk, REPO)}", os.path.isfile(apk)):
    sys.exit(1)
print(f"    大小 {os.path.getsize(apk) / 1024 / 1024:.1f} MB"
      f"  修改时间 {time.strftime('%Y-%m-%d %H:%M', time.localtime(os.path.getmtime(apk)))}")

apksigner = newest_build_tool("apksigner")
aapt2 = newest_build_tool("aapt2")
check("找到 apksigner", apksigner is not None)
check("找到 aapt2", aapt2 is not None)
check("找到可用的 JDK（apksigner 需要）", JAVA_HOME is not None,
      "设置 JAVA_HOME 环境变量，或在 gradle.properties 写 org.gradle.java.home")
if JAVA_HOME:
    print(f"    JDK      : {JAVA_HOME}")


# ---------------------------------------------------------------- 1. 签名

print("\n[1] 签名")
cert_subject = ""
fingerprint = ""
if apksigner:
    code, out = run([apksigner, "verify", "--verbose", "--print-certs", apk])
    signer_ok = code == 0
    check("apksigner verify 通过", signer_ok, out.strip()[:200])

    m = re.search(r"Signer #1 certificate DN:\s*(.+)", out)
    cert_subject = m.group(1).strip() if m else ""
    m = re.search(r"Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F:]+)", out)
    fingerprint = (m.group(1).strip().upper() if m else "")

    # ⚠️ 下面几条必须挂在 signer_ok 上。
    # 曾经把它们直接串在解析结果后面，于是 apksigner 因缺 JAVA_HOME 失败时
    # cert_subject 是空串，`"Android Debug" not in ""` 求值为真 ——
    # 最关键的「不是 debug 证书」反而静默通过。
    # 工具没跑起来时，任何基于它输出的判断都只能是失败。
    if signer_ok:
        check("不是 debug 证书签名", "Android Debug" not in cert_subject,
              f"证书主体为 {cert_subject!r} —— 这是调试证书，不能发布")
        check("证书主体正确", "Plant Identify Library" in cert_subject, f"实际 {cert_subject!r}")
        # 实际输出形如：
        #   Verified using v2 scheme (APK Signature Scheme v2): true
        # 中间夹着括号说明，所以不能用 `scheme:\s*true` 直连
        check("带 v2 或更高签名方案（minSdk 26 必需）",
              bool(re.search(r"Verified using v[234](?:\.\d)? scheme[^\n]*:\s*true", out)),
              "只有 v1 签名，部分设备装不上")
        print(f"    证书主体 : {cert_subject}")
        print(f"    SHA-256  : {fingerprint}")
    else:
        check("证书主体可解析", False, "apksigner 未成功，签名无法校验")
        check("带 v2 或更高签名方案（minSdk 26 必需）", False, "apksigner 未成功")


# ---------------------------------------------------------------- 2. 包信息

print("\n[2] 包信息（与 app/build.gradle.kts 比对）")
if aapt2:
    code, out = run([aapt2, "dump", "badging", apk])
    pkg = re.search(r"package: name='([^']+)'", out)
    vname = re.search(r"versionName='([^']+)'", out)
    vcode = re.search(r"versionCode='([^']+)'", out)
    label = re.search(r"application-label:'([^']*)'", out)

    check(f"包名为 {EXPECT_PACKAGE}", bool(pkg) and pkg.group(1) == EXPECT_PACKAGE,
          f"实际 {pkg.group(1) if pkg else '解析失败'}")
    check(f"versionName 为 {EXPECT_VERSION_NAME}",
          bool(vname) and vname.group(1) == EXPECT_VERSION_NAME,
          f"实际 {vname.group(1) if vname else '解析失败'}")
    check(f"应用显示名为 {EXPECT_LABEL}",
          bool(label) and label.group(1) == EXPECT_LABEL,
          f"实际 {label.group(1) if label else '解析失败'}")
    check("非 debuggable（release 包不应带 DEBUGGABLE 标志）",
          "application-debuggable" not in out)

    # 注意是 minSdkVersion 不是 sdkVersion —— 原来的正则写成 sdkVersion，
    # 大小写不匹配（minSdkVersion 里是大写 S），于是永远打问号
    vmin = re.search(r"minSdkVersion:'([0-9]+)'", out)
    vtgt = re.search(r"targetSdkVersion:'([0-9]+)'", out)

    print(f"    versionCode {vcode.group(1) if vcode else '?'}"
          f"  ·  minSdk {vmin.group(1) if vmin else '?'}"
          f"  ·  targetSdk {vtgt.group(1) if vtgt else '?'}")


# ---------------------------------------------------------------- 3. 密钥扫描

print("\n[3] 密钥扫描（转公开 / 发 Release 的红线）")
suspicious: list[str] = []
needles: list[tuple[str, str]] = [
    ("OpenAI 风格密钥", r"sk-[A-Za-z0-9]{20,}"),
    ("百炼 DashScope 密钥", r"sk-\w{20,}"),
    ("Bearer 硬编码", r"Bearer\s+[A-Za-z0-9_\-\.]{20,}"),
    ("私钥块", r"-----BEGIN [A-Z ]*PRIVATE KEY-----"),
]

# 签名口令也要扫：它一旦被打进包，等于把签名身份交出去
keystore_props = os.path.join(REPO, "keystore.properties")
if os.path.isfile(keystore_props):
    with open(keystore_props, encoding="utf-8") as f:
        for line in f:
            if line.startswith("storePassword=") or line.startswith("keyPassword="):
                pw = line.split("=", 1)[1].strip()
                if len(pw) >= 6:
                    needles.append(("keystore.properties 里的签名口令", re.escape(pw)))

with zipfile.ZipFile(apk) as zf:
    blobs = [(name, zf.read(name)) for name in zf.namelist()
             if name.endswith((".dex", ".arsc", ".xml", ".json", ".properties"))]

for label, pattern in needles:
    rx = re.compile(pattern.encode())
    hit = next((name for name, data in blobs if rx.search(data)), None)
    if hit:
        suspicious.append(f"{label} 出现在 {hit}")

check("APK 内无硬编码密钥 / 签名口令", not suspicious, "；".join(suspicious))


# ---------------------------------------------------------------- 4. 摘要

with open(apk, "rb") as f:
    apk_sha = hashlib.sha256(f.read()).hexdigest().upper()

print("\n[4] 发布说明要用到的摘要")
print(f"    APK SHA-256      : {apk_sha}")
print(f"    证书 SHA-256     : {fingerprint}")

print(f"\n合计：通过 {passed}，失败 {failed}")
sys.exit(1 if failed else 0)
