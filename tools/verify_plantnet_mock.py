#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""verify_plantnet_mock.py —— 用本地 mock 服务端做**完整识别链路**验证。

## 为什么用 mock 而不是真站

这台模拟器的**上传方向被限速到 ~5 KB/s**（对 postman-echo 等任意主机
都一样，而下载有 73 KB/s）。带真实照片的请求因此必然超时 ——
那是**测试环境的限制，不是应用的缺陷**，但它挡住了「识别链路能不能跑通」
这条验证。

mock 走 `adb reverse` 的本地回环，不受外网限速影响，于是可以验证：
  - 应用发出的**请求形状**对不对（multipart、images/organs 字段、api-key 在 query）
  - 应用的**响应解析**对不对（score / scientificNameWithoutAuthor / genus /
    family / commonNames → 项目自己的 RecognitionResult）
  - 通道切换后识别**确实走 Pl@ntNet**、不再被视觉配置拦住

⚠️ 它**不**验证「Key 是否有效」—— 那一条已用 curl 对真实服务端单独验过
（不带图返回 400 = 路由通；错 Key 会返回 401）。

## 用法

    python tools/mock_plantnet_server.py --port 8901     # 另开一个终端
    adb reverse tcp:8901 tcp:8901
    python tools/verify_plantnet_mock.py
"""
from __future__ import annotations

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
MOCK = "http://127.0.0.1:8901/v2"

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
        return self.sh("dumpsys activity activities | grep -m1 topResumedActivity")

    def top(self):
        m = re.search(r"ActivityRecord\{[^}]*?\s(\S+)/(\S+)\s", self.top_raw())
        if m:
            return m.group(1)
        m = re.search(r"([a-zA-Z][\w.]*)/([A-Za-z][\w.$]*)", self.top_raw())
        return m.group(1) if m else ""

    def on_picker(self):
        return "photopicker" in self.top_raw().lower()

    def ui(self):
        self.sh("rm -f /sdcard/_mm.xml")
        self.sh("uiautomator dump /sdcard/_mm.xml")
        try:
            return ET.fromstring(self.eo("cat /sdcard/_mm.xml").decode("utf-8", "replace"))
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
        open(name, "wb").write(self.eo("screencap -p", 90))


def main():
    adb = require_adb_or_exit()
    s = require_device_or_exit(adb)
    d = D(adb, s)

    print("=" * 74)
    print(f"Pl@ntNet 识别链路验证（本地 mock）—— {PKG} @ {s}")
    print("=" * 74)

    # mock 必须可达
    probe = d.sh(f"curl -s -m 10 '{MOCK}/identify/all?api-key=probe'")
    if not check("images" in probe or "400" in probe,
                 f"mock 服务端可达（{MOCK}）", f"返回：{probe[:120]}"):
        print("      请先：python tools/mock_plantnet_server.py --port 8901")
        print("            adb reverse tcp:8901 tcp:8901")
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

    # ---------------- 配置 Pl@ntNet，Base URL 指向 mock ----------------
    print("\n[1] 配置 Pl@ntNet 通道（Base URL → 本地 mock）")
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
    d.sh("input text 'mock-key-for-local-test'")
    time.sleep(1)
    d.sh("input keyevent KEYCODE_BACK")
    time.sleep(1)

    # 把 Base URL 指向本地 mock（这是 v1.0.3 新加的「端点可改」能力）。
    # 之所以必须走 mock：这台模拟器**上传被限速到 ~5KB/s**，
    # 真实 Pl@ntNet 的 multipart 上传必然超时 —— 那是环境问题，
    # 会掩盖「请求构造与解析是否正确」这条要验的事。
    r, url_field = d.find_scroll("Pl@ntNet Base URL", exact=True, tries=6)
    if not check(url_field is not None, "Base URL 输入框出现（v1.0.3 新增）"):
        return 1
    d.tap(r, url_field)
    time.sleep(1)
    d.sh("input keyevent KEYCODE_MOVE_END")
    for _ in range(120):
        d.sh("input keyevent KEYCODE_DEL")
    d.sh(f"input text '{MOCK}'")
    time.sleep(1)
    d.sh("input keyevent KEYCODE_BACK")
    time.sleep(1)
    shown = d.texts(d.ui())
    check(any("8901" in t for t in shown), "Base URL 回读为 mock 地址",
          f"文本={[t for t in shown if '127.0.0.1' in t or '8901' in t][:5]}")

    r, save = d.find_scroll("保存配置", exact=True, tries=8)
    if not check(save is not None, "找到保存按钮"):
        return 1
    check((d.anc(r, save) or save).get("enabled") == "true", "保存按钮可点")
    d.tap(r, save)
    time.sleep(3)
    prefs = d.eo(f"run-as {PKG} cat files/datastore/ai_config.preferences_pb").decode("utf-8", "replace")
    check("PLANT_NET" in prefs, "通道保存为 PLANT_NET")

    # ---------------- 添加植物 + 选图 ----------------
    print("\n[2] 添加植物并选图")
    d.sh("input keyevent KEYCODE_BACK")
    time.sleep(2)
    r = d.ui()
    d.tap(r, d.find(r, "添加植物"))
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
        nn = d.find(rr, "从相册选择", exact=True)
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
    if not check(opened, "系统照片选择器已打开"):
        return 1
    time.sleep(3)

    r = d.ui()
    thumbs = [pb(x) for x in r.iter("node")
              if pb(x) and 150 < pb(x)[2]-pb(x)[0] < 400 and 150 < pb(x)[3]-pb(x)[1] < 400]
    if not check(bool(thumbs), "找到缩略图"):
        return 1
    d.tapb(thumbs[min(3, len(thumbs)-1)])
    time.sleep(2)

    r = d.ui()
    ab = None
    for x in r.iter("node"):
        t = x.get("text", "")
        if t.startswith("添加") and "项" in t:
            ab = x; break
    if not check(ab is not None and d.tap(r, ab), "点『添加』确认选图"):
        return 1
    time.sleep(5)

    r = d.ui()
    check(any("1/5" in t for t in d.texts(r)), "照片已附上")

    # ---------------- 识别 ----------------
    print("\n[3] 开始识别（走本地 mock，不受外网上传限速影响）")
    r, btn = d.find_scroll("开始识别", exact=True, tries=6)
    if not check(btn is not None, "找到「开始识别」"):
        return 1
    check((d.anc(r, btn) or btn).get("enabled") == "true", "「开始识别」可点")
    d.tap(r, btn)
    time.sleep(3)

    texts = []
    for i in range(20):
        time.sleep(5)
        r = d.ui()
        texts = d.texts(r)
        j = " ".join(texts)
        if any(k in j for k in ("置信", "候选", "学名", "未识别", "识别失败", "保存到档案")):
            break
        print(f"      ...轮询 {5*(i+1)}s")
    d.shot(os.path.join(os.environ.get("TEMP", "."), "mock_result.png"))

    j = " ".join(texts)
    print(f"\n      结果页文本：{texts[:16]}")

    print("\n[4] 断言")
    check("还没有配置 AI 服务" not in j, "不再被视觉配置门控拦住", f"{texts[:16]}")
    check("还没有配置 Pl@ntNet" not in j, "不再报缺 Pl@ntNet Key", f"{texts[:16]}")
    check("无法连接" not in j, "没有连接失败", f"{texts[:16]}")
    check("识别失败" not in j, "识别没有失败", f"{texts[:16]}")
    # mock 回的是「月季 / Rosa chinensis」—— 说明响应解析通了
    check("月季" in j or "Rosa" in j or "蔷薇" in j or "置信" in j,
          "解析出 mock 返回的物种（月季 / Rosa chinensis）", f"{texts[:16]}")

    print("\n" + "=" * 74)
    print(f"结果：{PASS} 通过 / {FAIL} 失败")
    print("=" * 74)
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
