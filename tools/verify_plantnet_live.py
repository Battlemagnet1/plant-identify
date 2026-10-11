#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""verify_plantnet_live.py —— 对**真实** Pl@ntNet 服务端跑完整识别（v1.0.2.1011）。

与 `verify_plantnet_mock.py` 的分工：
  - `mock` 版证明「请求构造 / 响应解析 / 通道切换」都正确（不受网络影响）
  - **本脚本**用真实 Key 打真站，证明端到端在真实条件下可用

## 为什么之前在本机跑不通

这台模拟器的**上行只有 ~7.5 KB/s**（下行 73 KB/s）—— 是环境的非对称限速。
一张压缩后约 200 KB 的照片要传 ~27s，而超时/网络抖动会让它时快时慢。
所以本脚本：
  1. 用 `-F organs=... -F images=...` 的**官方字段顺序**
  2. 允许重试（真实网络本身不稳定，单次失败不代表功能坏）
  3. 明确区分「网络超时」与「功能失败」—— 前者不算失败

## 用法

    python tools/verify_plantnet_live.py --api-key <你的Key> [--retries 4]
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
LIVE = "https://my-api.plantnet.org/v2"

PASS = FAIL = 0
NETWORK_TIMEOUTS = 0


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

    def sh(self, c, t=180):
        return subprocess.run([self.adb, "-s", self.s, "shell", c],
                              capture_output=True, timeout=t).stdout.decode("utf-8", "replace")

    def eo(self, c, t=90):
        return subprocess.run([self.adb, "-s", self.s, "exec-out", c],
                              capture_output=True, timeout=t).stdout

    def top_raw(self):
        return self.sh("dumpsys activity activities | grep -m1 topResumedActivity")

    def on_picker(self):
        return "photopicker" in self.top_raw().lower()

    def ui(self):
        self.sh("rm -f /sdcard/_lv.xml")
        self.sh("uiautomator dump /sdcard/_lv.xml")
        try:
            return ET.fromstring(self.eo("cat /sdcard/_lv.xml").decode("utf-8", "replace"))
        except ET.ParseError:
            return None

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

    def shot(self, name):
        open(name, "wb").write(self.eo("screencap -p"))


def run_recognition_once(d, label):
    """跑完「加植物 → 选图 → 识别」，返回结果页文本列表（失败返回 None）。"""
    global NETWORK_TIMEOUTS

    # 加植物。
    # ⚠️ 不要用 `input keyevent BACK` 回首页 —— 在首页按返回会**直接退出应用**，
    #    接下来所有点击都打在桌面上，表现为「找不到『添加植物』」。
    #    正确做法：把应用重新拉到前台（它自己的返回栈就回到首页了）。
    d.sh(f"am start -n {PKG}/{ACT}")
    time.sleep(3)
    r = d.ui()
    if not d.tap(r, d.find(r, "添加植物")):
        # 可能在二级页：连按返回直到首页出现「添加植物」（但别退到桌面）
        for _ in range(3):
            r = d.ui()
            if d.find(r, "添加植物") is not None:
                d.tap(r, d.find(r, "添加植物"))
                break
            d.sh("input keyevent KEYCODE_BACK")
            time.sleep(2)
        else:
            print("      找不到「添加植物」")
            return None
    time.sleep(4)

    for _ in range(4):
        r = d.ui()
        tx = " ".join(d.texts(r))
        done = False
        if "记录观察地点" in tx:
            nn = d.find(r, "暂不允许", True)
            if nn is not None and d.tap(r, nn):
                done = True
        else:
            for lbl in ("允许", "拒绝", "不允许"):
                nn = d.find(r, lbl, True)
                if nn is not None and d.tap(r, nn):
                    done = True; break
        if not done:
            break
        time.sleep(2)

    opened = False
    for _ in range(4):
        rr = d.ui()
        nn = d.find(rr, "从相册选择", exact=True)   # 必须整串：说明文字里也含这串
        if nn is None:
            break
        d.tap(rr, nn)
        for _ in range(20):
            time.sleep(1)
            if d.on_picker():
                opened = True
                break
        if opened:
            break
    if not opened:
        print("      照片选择器未打开")
        return None
    time.sleep(3)

    r = d.ui()
    thumbs = [pb(x) for x in r.iter("node")
              if pb(x) and 150 < pb(x)[2]-pb(x)[0] < 400 and 150 < pb(x)[3]-pb(x)[1] < 400]
    if not thumbs:
        print("      没有缩略图")
        return None
    d.tapb(thumbs[min(3, len(thumbs)-1)])
    time.sleep(2)

    r = d.ui()
    ab = None
    for x in r.iter("node"):
        t = x.get("text", "")
        if t.startswith("添加") and "项" in t:
            ab = x; break
    if ab is None or not d.tap(r, ab):
        print("      确认选图失败")
        return None
    time.sleep(5)

    r = d.ui()
    if not any("1/5" in t for t in d.texts(r)):
        print("      照片没附上")
        return None

    # 识别
    r, btn = d.find_scroll("开始识别", exact=True, tries=6)
    if btn is None:
        print("      找不到「开始识别」")
        return None
    d.tap(r, btn)

    # 真实网络慢：最多等 5 分钟
    for i in range(60):
        time.sleep(5)
        r = d.ui()
        t = d.texts(r)
        j = " ".join(t)
        if any(k in j for k in ("置信", "未识别到", "识别失败", "保存到档案", "服务返回")):
            d.shot(os.path.join(os.environ.get("TEMP", "."), f"live_{label}.png"))
            return t
        if i % 6 == 5:
            print(f"      ...{label} 轮询 {5*(i+1)}s：{t[:2]}")
    d.shot(os.path.join(os.environ.get("TEMP", "."), f"live_{label}_timeout.png"))
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--api-key", required=True)
    ap.add_argument("--retries", type=int, default=3)
    args = ap.parse_args()
    key = args.api_key.strip()
    global NETWORK_TIMEOUTS

    adb = require_adb_or_exit()
    s = require_device_or_exit(adb)
    d = D(adb, s)

    print("=" * 74)
    print(f"Pl@ntNet **真实服务端**端到端验证 —— {PKG} @ {s}")
    print("=" * 74)

    # 先确认设备能到真站（小请求，1 秒内应回 400/404）
    probe = d.sh(f"curl -s -m 20 -w '|%{{http_code}}' '{LIVE}/identify/all?api-key={key}'")
    code = probe.rsplit("|", 1)[-1].strip()
    if not check(code in ("400", "404"), f"设备可达真实 Pl@ntNet（HTTP {code}）", probe[:160]):
        return 1

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

    # ---------------- 配置 ----------------
    print("\n[1] 配置 Pl@ntNet（真实端点 + 真实 Key）")
    r = d.ui()
    d.tap(r, d.find(r, "设置", True))
    time.sleep(3)
    r, n = d.find_scroll("Pl@ntNet", exact=True, tries=10)
    if not check(n is not None, "找到 Pl@ntNet 通道"):
        return 1
    d.tapb(pb(d.anc(r, n) or n))
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

    # 端点保持官方 v2 默认值，确认它是真站
    r, uf = d.find_scroll("Pl@ntNet Base URL", exact=True, tries=6)
    if uf is not None:
        cur = " ".join(d.texts(d.ui()))
        check("my-api.plantnet.org/v2" in cur,
              "端点显示为官方 v2（my-api.plantnet.org/v2）",
              f"文本片段={[t for t in d.texts(d.ui()) if 'plantnet' in t.lower()][:3]}")

    r, save = d.find_scroll("保存配置", exact=True, tries=8)
    if not check(save is not None, "找到保存按钮"):
        return 1
    check((d.anc(r, save) or save).get("enabled") == "true", "保存按钮可点")
    d.tap(r, save)
    time.sleep(3)

    # ---------------- 识别（允许重试） ----------------
    print(f"\n[2] 真实识别（最多试 {args.retries} 轮；网络抖动会重试）")
    result = None
    for attempt in range(1, args.retries + 1):
        print(f"\n  --- 第 {attempt} 轮 ---")
        t = run_recognition_once(d, f"try{attempt}")
        if t is None:
            print("      未拿到结果页（可能是网络超时），重试")
            NETWORK_TIMEOUTS += 1
            continue
        j = " ".join(t)
        print(f"      结果：{t[:14]}")
        if "无法连接" in j or "服务返回" in j:
            print("      网络/服务端问题，重试")
            NETWORK_TIMEOUTS += 1
            continue
        result = t
        break

    if result is None:
        print("\n" + "=" * 74)
        print(f"结果：所有 {args.retries} 轮都没拿到结果 —— "
              f"按网络问题计（本机上行 ~7.5KB/s，照片约 200KB 需 ~27s）")
        print("=" * 74)
        check(False, "真实识别拿到结果（网络原因未达成）",
              "应用侧已用 mock 验证请求/解析正确；这一条受本机上行限速影响")
        return 1

    j = " ".join(result)
    print("\n[3] 断言")
    check("识别失败" not in j, "识别未失败", f"{result[:14]}")
    check("还没有配置" not in j, "未被配置门控拦住", f"{result[:14]}")
    check("无法连接" not in j, "没有连接失败", f"{result[:14]}")
    check("404" not in j and "Not found" not in j, "没有 404", f"{result[:14]}")
    check(any(k in j for k in ("置信", "未识别到", "学名", "保存到档案")),
          "拿到真实识别结论", f"{result[:14]}")

    print("\n" + "=" * 74)
    print(f"结果：{PASS} 通过 / {FAIL} 失败（期间网络重试 {NETWORK_TIMEOUTS} 次）")
    print("=" * 74)
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
