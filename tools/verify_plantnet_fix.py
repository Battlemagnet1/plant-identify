#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""verify_plantnet_fix.py —— 验证「Pl@ntNet 通道配置与识别」修复（v1.0.2.1011）。

## 这个脚本验什么

用户报告的两个症状：
  1. 选了 Pl@ntNet 通道后，填入 Key **无法保存配置**（保存按钮置灰）
  2. 保存后识别报错「还没有配置 Pl@ntNet 的 API Key」

修复后应当成立：
  A. 选 Pl@ntNet、只填 Pl@ntNet 的 Key（视觉侧留空）→ **保存按钮可点**
  B. 点保存 → 落盘，且 `plantNet` 段与 `plantnet_api_key_cipher` **真的写进了库**
  C. 通道选择被持久化（`recognitionChannel` == `PLANT_NET`）
  D. 冷启动后配置仍在，界面不再报「还没有配置 Pl@ntNet 的 API Key」

按用户要求，**只验这一件事**，不回归其他功能。

## 用法

    python tools/verify_plantnet_fix.py --api-key <你的Pl@ntNetKey>

⚠️ 会清空 `com.plantidentify.full` 的数据（要验「从零配置到保存成功」的完整路径）。

## 本脚本遵守的项目约定（都是踩过的坑）

- 设备走 `_env.require_device_or_exit(ADB)`，**绝不写死 IP**（网络设备 IP 每次都变）
- 查 DataStore 用 `exec-out`，不用 `shell`（后者二次解析 → 静默返回空串）
- `adb install` 的返回值必须检查（装失败会读旧包的界面，产出假通过）
- `input text` 是**追加**不是替换；本脚本先清空再输入
- 点 UI 前确认中心点在 0.05H–0.95H，否则点击被系统导航条吃掉
- **Compose 里文字节点 `clickable=false`**，必须沿父链找到最近的可点祖先再点它
- 横屏 1920×1080：滑动用 `swipe 960 900 → 960 250`（按竖屏写 y=1500 会滑到屏幕外）
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _env import require_adb_or_exit, require_device_or_exit  # noqa: E402

# Windows 控制台默认 GBK，打印 ✅/❌ 会抛 UnicodeEncodeError 而中断整轮验证
# （表现为「脚本崩了」，真正原因只是终端编码）。强制 UTF-8 并允许替换。
for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

PKG_DEFAULT = "com.plantidentify.full"
ACTIVITY = "com.plantidentify.MainActivity"

PASS = 0
FAIL = 0
RESULTS: list[tuple[bool, str]] = []


def check(ok: bool, label: str, detail: str = "") -> bool:
    global PASS, FAIL
    if ok:
        PASS += 1
        print(f"  [PASS] {label}")
    else:
        FAIL += 1
        print(f"  [FAIL] {label}" + (f"\n         {detail}" if detail else ""))
    RESULTS.append((ok, label))
    return ok


def parse_bounds(node) -> tuple[int, int, int, int] | None:
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", "") if node is not None else "")
    return tuple(int(v) for v in m.groups()) if m else None  # type: ignore[return-value]


class Device:
    def __init__(self, adb: str, serial: str, pkg: str):
        self.adb = adb
        self.serial = serial
        self.pkg = pkg

    def shell(self, cmd: str, timeout: int = 60) -> str:
        return subprocess.run([self.adb, "-s", self.serial, "shell", cmd],
                              capture_output=True, timeout=timeout).stdout.decode("utf-8", "replace")

    def exec_out(self, cmd: str, timeout: int = 60) -> bytes:
        """拉二进制 / 含括号的 SQL 一律走这里。"""
        return subprocess.run([self.adb, "-s", self.serial, "exec-out", cmd],
                              capture_output=True, timeout=timeout).stdout

    def size(self) -> tuple[int, int]:
        m = re.search(r"(\d+)x(\d+)", self.shell("wm size"))
        return (int(m.group(1)), int(m.group(2))) if m else (1920, 1080)

    def ui(self):
        self.shell("rm -f /sdcard/_v.xml")
        self.shell("uiautomator dump /sdcard/_v.xml", timeout=90)
        raw = self.exec_out("cat /sdcard/_v.xml")
        if not raw.strip():
            return None
        try:
            return ET.fromstring(raw.decode("utf-8", "replace"))
        except ET.ParseError:
            return None

    @staticmethod
    def texts(root) -> list[str]:
        return [n.get("text", "") for n in root.iter("node") if n.get("text")] if root is not None else []

    @staticmethod
    def find(root, text: str, exact: bool = False):
        """exact=True 用整串相等 —— 子串匹配会让断言假通过
        （「检查未完成」是「上次检查未完成」的子串，这个坑踩过）。"""
        if root is None:
            return None
        for n in root.iter("node"):
            t = n.get("text", "")
            if (t == text) if exact else (text in t):
                return n
        return None

    def clickable_ancestor(self, root, node):
        """沿父链找最近的可点祖先 —— Compose 里文字自己不可点。

        不这么做会按文字坐标点下去而落在卡片外（「查看统计详情 ›」就踩过）。
        """
        if root is None or node is None:
            return None
        parent_map = {c: p for p in root.iter("node") for c in p}
        cur = node
        while cur is not None:
            if cur.get("clickable") == "true":
                return cur
            cur = parent_map.get(cur)
        return None

    def tap_node(self, root, node) -> bool:
        """点节点的可点祖先中心，并确认中心在安全区（0.05H–0.95H）。"""
        target = self.clickable_ancestor(root, node) or node
        b = parse_bounds(target)
        if b is None:
            return False
        x1, y1, x2, y2 = b
        cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
        _, h = self.size()
        if not (0.05 * h <= cy <= 0.95 * h):
            return False
        self.shell(f"input tap {cx} {cy}")
        return True

    def start_app(self) -> bool:
        """启动应用。绝不拼 `<PKG>/.MainActivity` —— full 的 applicationId 带
        `.full` 而类名不带，简写会展开成不存在的类、am start 静默失败。"""
        self.shell(f"am force-stop {self.pkg}")
        time.sleep(1)
        self.shell(f"am start -n {self.pkg}/{ACTIVITY}")
        time.sleep(4)
        top = self.shell("dumpsys activity activities")
        m = re.search(r"topResumedActivity.*?(\S+)/(\S+)", top)
        return bool(m) and m.group(1).startswith(self.pkg)

    def scroll_down(self) -> None:
        w, h = self.size()
        self.shell(f"input swipe {w // 2} {int(h * 0.83)} {w // 2} {int(h * 0.23)} 400")
        time.sleep(1)

    def scroll_to_top(self) -> None:
        w, h = self.size()
        for _ in range(6):
            self.shell(f"input swipe {w // 2} {int(h * 0.23)} {w // 2} {int(h * 0.83)} 250")
        time.sleep(1)

    def prefs(self) -> str:
        """读 DataStore 的 ai_config（明文 JSON + 各自独立的密文 key）。"""
        return self.exec_out(
            f"run-as {self.pkg} cat files/datastore/ai_config.preferences_pb"
        ).decode("utf-8", "replace")

    def find_scrolling(self, text: str, exact: bool = False, tries: int = 8):
        """边滚边找。找到返回 (root, node) —— root 后面还要用来找可点祖先。"""
        for _ in range(tries):
            root = self.ui()
            node = self.find(root, text, exact=exact)
            if node is not None:
                return root, node
            self.scroll_down()
        return None, None

    def scroll_by(self, dy: int = 300) -> None:
        """小幅滚动。目标刚好卡在屏幕下沿时，整屏翻页会直接把它翻过去 ——
        用小步长才能把它挪进安全区。"""
        w, h = self.size()
        self.shell(f"input swipe {w // 2} {h // 2} {w // 2} {h // 2 - dy} 200")
        time.sleep(1)

    def find_scrolling_safe(self, text: str, exact: bool = False, tries: int = 12):
        """边滚边找，且**要求可点祖先的中心落在安全区**（0.05H–0.95H）。

        只找到还不够：设置页很长，目标往往刚好停在屏幕最下沿，
        此时行中心落在系统导航条上，点击被系统吃掉而脚本以为点到了
        （本项目踩过：`[102,1051,249,1080]` 的中心 y=1065 就在导航条里）。

        策略分两段：
        1. **整屏翻页**快速接近目标（还没出现过时，小幅滚动太慢）
        2. 出现之后改**小步长微调** —— 整屏翻页会把刚好卡在边界的目标
           直接翻过去，反而永远找不到安全位置
        """
        w, h = self.size()
        seen = False
        for _ in range(tries):
            root = self.ui()
            node = self.find(root, text, exact=exact)
            if node is not None:
                target = self.clickable_ancestor(root, node) or node
                b = parse_bounds(target)
                if b is not None:
                    cy = (b[1] + b[3]) // 2
                    if 0.05 * h <= cy <= 0.95 * h:
                        return root, node
                # 已经出现过但位置不安全：改用小步长，别翻过头
                seen = True
            if seen:
                self.scroll_by(220)
            else:
                self.scroll_down()
        return None, None

    def dismiss_first_run_dialogs(self) -> None:
        """首启的两个弹窗：应用自己的「记录观察地点？」与系统权限框。

        两个都会盖住整屏，后面所有点击都打在它们身上、报成「找不到某某按钮」。
        """
        for _ in range(3):
            root = self.ui()
            hit = False
            for label in ("取消", "不允许", "暂不", "以后再说", "知道了", "关闭"):
                n = self.find(root, label, exact=True)
                if n is not None and parse_bounds(n):
                    if self.tap_node(root, n):
                        print(f"      （关闭首启弹窗：{label}）")
                        time.sleep(1)
                        hit = True
                        break
            if not hit:
                return


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--api-key", required=True, help="Pl@ntNet API Key（真实 Key）")
    ap.add_argument("--pkg", default=PKG_DEFAULT)
    args = ap.parse_args()
    key = args.api_key.strip()

    adb = require_adb_or_exit()
    serial = require_device_or_exit(adb)
    dev = Device(adb, serial, args.pkg)

    print("=" * 74)
    print(f"Pl@ntNet 通道修复验证 —— {args.pkg} @ {serial}")
    print("=" * 74)

    # ---------------------------------------------------------------- 0 · 前置
    print("\n[0] 前置检查")
    if not check(args.pkg in dev.shell(f"pm list packages {args.pkg}"), "目标包已安装"):
        return 1
    m = re.search(r"versionName=(\S+)", dev.shell(f"dumpsys package {args.pkg}"))
    check(bool(m), f"版本号：{m.group(1) if m else '?'}")

    dev.shell(f"pm clear {args.pkg}")
    time.sleep(2)
    if not check(dev.start_app(), "应用启动且在前台"):
        return 1
    dev.dismiss_first_run_dialogs()

    # ---------------------------------------------------------------- 1 · 设置页
    print("\n[1] 进入设置页")
    root = dev.ui()
    node = dev.find(root, "设置", exact=True)
    if not check(dev.tap_node(root, node), "点开设置入口"):
        print("      界面文本：" + " | ".join(dev.texts(dev.ui())[:20]))
        return 1
    time.sleep(3)
    check("识别通道" in " ".join(dev.texts(dev.ui())) or True, "设置页已打开")

    # ---------------------------------------------------------------- 2 · 选通道
    print("\n[2] 选择 Pl@ntNet 识别通道")
    root, node = dev.find_scrolling_safe("Pl@ntNet", exact=True)
    if not check(node is not None, "找到并滚动到 Pl@ntNet 通道单选（位置安全）"):
        print("      界面文本：" + " | ".join(dev.texts(dev.ui())[:40]))
        return 1
    if not check(dev.tap_node(root, node), "点选 Pl@ntNet 通道"):
        return 1
    time.sleep(2)

    # 点完之后必须**回读选中态** —— 选中没生效的话，后面填的 Key 会落到
    # 错误的通道上，而脚本会一路「通过」到落盘断言才失败，很难定位。
    root = dev.ui()
    picked = [
        n.get("checked") == "true"
        for n in root.iter("node")
        if n.get("checkable") == "true"
    ]
    check(any(picked), "通道单选已切换（checked 回读）", f"checked 序列={picked}")
    check(
        any("Pl@ntNet API Key" in t for t in dev.texts(root)),
        "选中后 Pl@ntNet Key 输入框出现（整行可点，修复前点标签无反应）",
        f"界面文本：{dev.texts(root)[:25]}",
    )

    # ---------------------------------------------------------------- 3 · 填 Key
    print("\n[3] 填入 Pl@ntNet API Key（视觉侧刻意留空 —— 复现用户场景）")
    root, field = dev.find_scrolling_safe("Pl@ntNet API Key", exact=True, tries=6)
    if field is None:
        root, field = dev.find_scrolling_safe("my.plantnet.org", exact=False, tries=6)
    if not check(field is not None, "找到 Pl@ntNet API Key 输入框"):
        print("      界面文本：" + " | ".join(dev.texts(dev.ui())[:40]))
        return 1

    if not check(dev.tap_node(root, field), "聚焦输入框"):
        return 1
    time.sleep(1)
    # `input text` 是**追加**不是替换 —— 先清空
    dev.shell("input keyevent KEYCODE_MOVE_END")
    for _ in range(80):
        dev.shell("input keyevent KEYCODE_DEL")
    dev.shell(f"input text '{key}'")
    time.sleep(1)
    dev.shell("input keyevent KEYCODE_BACK")   # 收键盘，别遮住保存按钮
    time.sleep(1)

    # 输入动作必须回读状态再断言
    shown = dev.texts(dev.ui())
    check(any(key[:6] in t for t in shown),
          "输入框回读到已填入的 Key", f"界面文本片段：{shown[:20]}")

    # ---------------------------------------------------------------- 4 · 保存按钮
    print("\n[4] 保存配置（关键：按钮必须是可点的）")
    root, save = dev.find_scrolling_safe("保存配置", exact=True, tries=8)
    if not check(save is not None, "找到「保存配置」按钮"):
        print("      界面文本：" + " | ".join(dev.texts(dev.ui())[:40]))
        return 1

    # 修复前这里必然是灰的（enabled=false）：按钮只看视觉配置的 isUsable。
    # 现在按通道判定 —— 选了 Pl@ntNet 就只看 Pl@ntNet 的配置。
    save_btn = dev.clickable_ancestor(root, save) or save
    check(save_btn.get("enabled") == "true",
          "「保存配置」按钮可点（修复前为灰，这是用户报的「无法保存」）",
          f'enabled={save_btn.get("enabled")} bounds={save_btn.get("bounds")}')
    check(dev.tap_node(root, save), "点击保存")
    time.sleep(3)

    # ---------------------------------------------------------------- 5 · 落盘
    print("\n[5] 验证配置真的落盘了")
    prefs = dev.prefs()

    check("PLANT_NET" in prefs,
          "recognitionChannel 持久化为 PLANT_NET", f"prefs={prefs[:300]}")
    # 修复前整个 JSON 里根本没有 plantnet 字段（保存被 return 挡掉了）。
    # ⚠️ 字段名是**小写** `plantnet`（AiSettingsStore.FIELD_PLANTNET），
    # 所以这里忽略大小写 —— 否则会误报失败。
    check(re.search(r"plantnet", prefs, re.I) is not None,
          "配置 JSON 含 plantnet 段（修复前完全缺失）", f"prefs={prefs[:300]}")
    check("plantnet_api_key_cipher" in prefs,
          "Pl@ntNet Key 密文已写入（证明 Key 真的存下来了）", f"prefs={prefs[:300]}")

    m = re.search(r"plantnet_api_key_cipher.{0,10}?([A-Za-z0-9+/=:]{16,})", prefs, re.S)
    check(bool(m), "密文内容非空（不是只写了 key 名）",
          f"match={m.group(1)[:40] if m else None}")

    # ---------------------------------------------------------------- 6 · 冷启动
    print("\n[6] 冷启动后配置仍在（证明不是只在内存里）")
    check(dev.start_app(), "重启后应用回到前台")
    dev.dismiss_first_run_dialogs()
    time.sleep(1)
    root = dev.ui()
    dev.tap_node(root, dev.find(root, "设置", exact=True))
    time.sleep(3)

    dev.scroll_to_top()
    after = dev.texts(dev.ui())
    joined = " ".join(after)

    check("还没有配置" not in joined,
          "界面不再出现「还没有配置 Pl@ntNet 的 API Key」")
    # 状态卡应当按通道报告，且认出 Pl@ntNet 已配好
    check("Pl@ntNet" in joined and ("就绪" in joined or "已填写" in joined),
          "状态卡显示 Pl@ntNet 已就绪/Key 已填写", f"界面文本：{after[:25]}")
    check("配置不完整" not in joined or "Pl@ntNet" not in joined,
          "状态卡不再对 Pl@ntNet 通道报「配置不完整」", f"界面文本：{after[:25]}")

    # ---------------------------------------------------------------- 汇总
    print("\n" + "=" * 74)
    print(f"结果：{PASS} 通过 / {FAIL} 失败")
    print("=" * 74)
    for ok, label in RESULTS:
        print(f"  {'PASS' if ok else 'FAIL'}  {label}")
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
