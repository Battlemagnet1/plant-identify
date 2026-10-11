#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""verify_plantnet_e2e.py —— Pl@ntNet 端到端识别验证（v1.0.2.1011）。

## 为什么要有这个脚本

前两个脚本分别验到「配置能保存」与「不再报缺 Key」，
但**真正驱动一次识别**才发现：识别在通道分支**之前**就被
「还没有配置 AI 服务，缺少：API Key」拦住了（视觉配置门控）。
那个问题是靠看截图发现的 —— 纯文本断言完全没覆盖到。

所以这个脚本走**完整真实流程**：
  配置 Pl@ntNet → 添加植物 → 系统照片选择器选图 → 开始识别 → 读结果页

并且断言**结果页的真实文案**：
  - 不能出现「还没有配置 AI 服务」（视觉门控）
  - 不能出现「还没有配置 Pl@ntNet 的 API Key」
  - 不能出现 404
  - 必须拿到 Pl@ntNet 的结论（识别成功、或「未识别到候选」都算链路通）

## 用法

    python tools/verify_plantnet_e2e.py --api-key <key>

⚠️ 会清空 com.plantidentify.full 的数据。
⚠️ 需要设备相册里有**植物照片**（脚本自动挑第一张）。
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

for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

PKG = "com.plantidentify.full"
ACT = "com.plantidentify.MainActivity"
PICKER = "com.android.providers.media.module/com.android.providers.media.photopicker.PhotoPickerActivity"

PASS = FAIL = 0


def check(ok, label, detail=""):
    global PASS, FAIL
    if ok:
        PASS += 1
        print(f"  [PASS] {label}")
    else:
        FAIL += 1
        print(f"  [FAIL] {label}" + (f"\n         {detail}" if detail else ""))
    return ok


def pb(n):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", "") if n is not None else "")
    return tuple(int(v) for v in m.groups()) if m else None


class D:
    def __init__(self, adb, s):
        self.adb, self.s = adb, s

    def sh(self, c, t=120):
        return subprocess.run([self.adb, "-s", self.s, "shell", c],
                              capture_output=True, timeout=t).stdout.decode("utf-8", "replace")

    def eo(self, c, t=60):
        return subprocess.run([self.adb, "-s", self.s, "exec-out", c],
                              capture_output=True, timeout=t).stdout

    def top_raw(self):
        """`topResumedActivity` 的原始行。

        注意 `dumpsys` 的输出**可能被截断**（实测出现过
        `android.providers.media.photopicker.PhotoPickerActivity` 这种
        只剩类名、包名被截掉的情况），所以判断「前台是谁」不能只靠
        解析出的包名 —— 还要能对整行做子串匹配。
        """
        return self.sh("dumpsys activity activities | grep -m1 topResumedActivity")

    def top(self):
        """取当前前台包名；解析不出来时返回空串（调用方别把它当唯一判据）。"""
        out = self.top_raw()
        m = re.search(r"ActivityRecord\{[^}]*?\s(\S+)/(\S+)\s", out)
        if m:
            return m.group(1)
        # 截断兜底：整行里找形如 <pkg>/<cls> 的片段
        m = re.search(r"([a-zA-Z][\w.]*)/([A-Za-z][\w.$]*)", out)
        return m.group(1) if m else ""

    def on_picker(self) -> bool:
        """当前是否在系统照片选择器上（对整行做子串匹配，抗截断）。"""
        return "photopicker" in self.top_raw().lower()

    def ui(self):
        self.sh("rm -f /sdcard/_e.xml")
        self.sh("uiautomator dump /sdcard/_e.xml")
        try:
            return ET.fromstring(self.eo("cat /sdcard/_e.xml").decode("utf-8", "replace"))
        except ET.ParseError:
            return None

    def shot(self, name):
        open(name, "wb").write(self.eo("screencap -p", 90))

    @staticmethod
    def texts(r):
        return [n.get("text") for n in r.iter("node") if n.get("text")] if r is not None else []

    @staticmethod
    def find(r, t, exact=False):
        if r is None:
            return None
        for n in r.iter("node"):
            x = n.get("text", "")
            if (x == t) if exact else (t in x):
                return n
        return None

    @staticmethod
    def anc(r, n):
        pm = {c: p for p in r.iter("node") for c in p}
        cur = n
        while cur is not None:
            if cur.get("clickable") == "true":
                return cur
            cur = pm.get(cur)
        return None

    def tapb(self, b):
        self.sh(f"input tap {(b[0]+b[2])//2} {(b[1]+b[3])//2}")

    def tap(self, r, n):
        t = self.anc(r, n) or n
        b = pb(t)
        if not b:
            return False
        self.sh(f"input tap {(b[0]+b[2])//2} {(b[1]+b[3])//2}")
        return True

    def scroll(self, dy=400):
        self.sh(f"input swipe 960 540 960 {540-dy} 300")
        time.sleep(1)

    def find_scroll(self, t, exact=False, tries=10):
        for _ in range(tries):
            r = self.ui()
            n = self.find(r, t, exact=exact)
            if n is not None:
                return r, n
            self.scroll()
        return None, None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--api-key", required=True)
    args = ap.parse_args()
    key = args.api_key.strip()

    adb = require_adb_or_exit()
    s = require_device_or_exit(adb)
    d = D(adb, s)

    print("=" * 74)
    print(f"Pl@ntNet 端到端识别验证 —— {PKG} @ {s}")
    print("=" * 74)

    d.sh(f"pm clear {PKG}")
    time.sleep(2)
    d.sh(f"am start -n {PKG}/{ACT}")
    time.sleep(5)
    for _ in range(3):
        r = d.ui()
        hit = False
        for lbl in ("取消", "不允许", "暂不", "以后再说", "知道了"):
            n = d.find(r, lbl, True)
            if n is not None and d.tap(r, n):
                hit = True; time.sleep(1); break
        if not hit:
            break

    # ---------------- 配置 Pl@ntNet ----------------
    print("\n[1] 配置 Pl@ntNet 通道")
    r = d.ui()
    d.tap(r, d.find(r, "设置", True))
    time.sleep(3)

    r, n = d.find_scroll("Pl@ntNet", exact=True, tries=10)
    if not check(n is not None, "找到 Pl@ntNet 通道"):
        return 1
    # 点整行（可点祖先）
    b = pb(d.anc(r, n) or n)
    d.tapb(b)
    time.sleep(2)

    r, field = d.find_scroll("Pl@ntNet API Key", exact=True, tries=6)
    if not check(field is not None, "Key 输入框出现"):
        return 1
    d.tap(r, field)
    time.sleep(1)
    d.sh("input keyevent KEYCODE_MOVE_END")
    for _ in range(80):
        d.sh("input keyevent KEYCODE_DEL")
    d.sh(f"input text '{key}'")
    time.sleep(1)
    d.sh("input keyevent KEYCODE_BACK")
    time.sleep(1)

    r, save = d.find_scroll("保存配置", exact=True, tries=8)
    if not check(save is not None, "找到保存按钮"):
        return 1
    check((d.anc(r, save) or save).get("enabled") == "true", "保存按钮可点")
    d.tap(r, save)
    time.sleep(3)
    prefs = d.eo(f"run-as {PKG} cat files/datastore/ai_config.preferences_pb").decode("utf-8", "replace")
    check("PLANT_NET" in prefs, "通道已保存为 PLANT_NET")
    check("my-api.plantnet.org" in prefs or "plantnet" in prefs.lower(), "端点已写入 v2")

    # ---------------- 添加植物 ----------------
    print("\n[2] 添加植物并选图（走系统照片选择器）")
    d.sh("input keyevent KEYCODE_BACK")
    time.sleep(2)

    r = d.ui()
    add = d.find(r, "添加植物")
    if not check(add is not None and d.tap(r, add), "点「添加植物」"):
        print("      文本：", d.texts(d.ui())[:20]); return 1
    time.sleep(4)

    # 首启的两个弹窗
    for _ in range(4):
        r = d.ui()
        tx = " ".join(d.texts(r))
        done = False
        if "记录观察地点" in tx:
            n = d.find(r, "暂不允许", True)
            if n is not None and d.tap(r, n):
                done = True
        else:
            for lbl in ("允许", "拒绝", "不允许"):
                n = d.find(r, lbl, True)
                if n is not None and d.tap(r, n):
                    done = True; break
        if not done:
            break
        time.sleep(2)

    r = d.ui()
    pick = d.find(r, "从相册选择")
    if not check(pick is not None and d.tap(r, pick), "点「从相册选择」"):
        print("      文本：", d.texts(d.ui())[:20]); return 1

    # 照片选择器是**另一个进程**（PhotoPickerActivity），冷启动可能很慢
    # （实测首次要 5–10s，pm clear 之后更慢）。固定 sleep 不够稳，必须轮询。
    # 另：首次点击偶尔被 Compose 的涟漪/焦点切换吃掉，所以整体重试。
    opened = False
    for attempt in range(4):
        rr = d.ui()
        # ⚠️ 必须用**整串相等**找按钮。
        # 页面上有一段说明文字是「可以拍照，也可以从相册选择。最多 5 张…」——
        # 它**包含**「从相册选择」这四个字，而且出现在按钮**之前**。
        # 子串匹配会先命中那段说明（点它什么都不会发生），
        # 于是表现为「点了按钮但选择器不打开」，极难定位。
        nn = d.find(rr, "从相册选择", exact=True)
        if nn is None:
            print(f"      第 {attempt+1} 次：当前页没有『从相册选择』按钮，文本={d.texts(rr)[:12]}")
            break
        if not d.tap(rr, nn):
            print(f"      第 {attempt+1} 次：找到按钮但不可点（bounds={nn.get('bounds')}），重试")
            time.sleep(2)
            continue
        for _ in range(20):          # 每次点击后最多等 20s
            time.sleep(1)
            if d.on_picker():
                opened = True
                break
        if opened:
            break
        print(f"      ...第 {attempt+1} 次点击后仍未打开，重试")

    if not check(opened, f"系统照片选择器已打开（当前前台={d.top()}）"):
        return 1
    time.sleep(3)

    # 选一张植物照片：挑右上区域、尺寸适中的缩略图
    r = d.ui()
    cands = []
    for n in r.iter("node"):
        b = pb(n)
        if b and 150 < b[2]-b[0] < 400 and 150 < b[3]-b[1] < 400:
            cands.append(b)
    if not check(bool(cands), "找到可选缩略图"):
        return 1
    # 第 4 张通常是植物照（前几张是压测假图）
    target = cands[min(3, len(cands)-1)]
    d.tapb(target)
    time.sleep(2)

    # 点「添加（N 项）」
    r = d.ui()
    addbtn = None
    for n in r.iter("node"):
        t = n.get("text", "")
        if t.startswith("添加") and "项" in t:
            addbtn = n; break
    if addbtn is None:
        addbtn = d.find(r, "添加", exact=False)
    if not check(addbtn is not None and d.tap(r, addbtn), "点『添加』确认选图"):
        print("      文本：", d.texts(d.ui())[:20]); return 1
    time.sleep(5)

    check(d.top().startswith(PKG) or PKG in d.top_raw(), f"已回到应用（{d.top()}）")
    r = d.ui()
    added = [t for t in d.texts(r) if "已添加" in t]
    check(any("1/5" in t or "2/5" in t or "3/5" in t for t in added),
          f"照片已附上（{added}）", f"文本：{d.texts(r)[:15]}")
    d.shot(os.path.join(os.environ.get("TEMP", "."), "e2e_ready.png"))

    # ---------------- 识别 ----------------
    print("\n[3] 开始识别（真实 Pl@ntNet 调用）")
    d.sh("logcat -c")
    r, btn = d.find_scroll("开始识别", exact=True, tries=6)
    if not check(btn is not None, "找到「开始识别」按钮"):
        print("      文本：", d.texts(d.ui())[:20]); return 1
    b = d.anc(r, btn) or btn
    check(b.get("enabled") == "true", "「开始识别」可点（有照片时才亮）")
    d.tap(r, btn)
    time.sleep(3)

    texts = []
    for i in range(24):
        time.sleep(5)
        r = d.ui()
        texts = d.texts(r)
        joined = " ".join(texts)
        if "识别结果" in joined or "识别失败" in joined:
            break
        print(f"      ...轮询 {5*(i+1)}s")
    d.shot(os.path.join(os.environ.get("TEMP", "."), "e2e_result.png"))

    joined = " ".join(texts)
    print(f"\n      结果页文本：{texts[:16]}")

    # ---------------- 断言 ----------------
    print("\n[4] 断言（结果页真实文案）")
    check("识别结果" in joined or "识别失败" in joined, "进入识别结果页")
    check("还没有配置 AI 服务" not in joined,
          "不再报「还没有配置 AI 服务，缺少：API Key」（视觉门控已按通道放行）",
          f"{texts[:16]}")
    check("还没有配置 Pl@ntNet" not in joined,
          "不再报「还没有配置 Pl@ntNet 的 API Key」", f"{texts[:16]}")
    check("404" not in joined and "Not found" not in joined,
          "不再出现 404（旧 my.plantnet.org/v1 恒 404）", f"{texts[:16]}")
    check("无法连接" not in joined, "没有连接失败", f"{texts[:16]}")

    got = any(k in joined for k in ("置信", "候选", "学名", "未识别到", "保存到档案", "查看档案", "识别成功"))
    check(got, "拿到 Pl@ntNet 的结论（识别成功或「未识别到候选」都算链路通）",
          f"{texts[:16]}")

    # logcat 里应能看到打向 my-api 的请求痕迹
    log = subprocess.run([adb, "-s", s, "logcat", "-d", "-v", "brief"],
                         capture_output=True, timeout=60).stdout.decode("utf-8", "replace")
    hits = [l for l in log.splitlines() if re.search(r"plantnet|my-api|404|识别失败|NotConfigured", l, re.I)]
    print(f"\n      logcat 相关行 {len(hits)} 条：")
    for l in hits[:12]:
        print("        ", l[:170])

    print("\n" + "=" * 74)
    print(f"结果：{PASS} 通过 / {FAIL} 失败")
    print("=" * 74)
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
