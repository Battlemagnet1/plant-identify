#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""verify_1018_taxonomy.py —— 实机验证科属中文名修复（v1.0.2.1018 三）。

## 验什么

- **属名规范化**：薰衣草的属应为「薰衣草**属**」（接口首项是「薰衣草」，缺属字）
- **科名简繁**：蔷薇科/银杏科应为简体
- **科无中文名时不空着**：Asteraceae 退回拉丁名

做法：在应用里走真实识别，读结果页的「科：…　属：…」那一行。
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
        self._size = None

    def sh(self, c, t=150):
        return subprocess.run([self.adb, "-s", self.s, "shell", c],
                              capture_output=True, timeout=t).stdout.decode("utf-8", "replace")

    def eo(self, c, t=90):
        return subprocess.run([self.adb, "-s", self.s, "exec-out", c],
                              capture_output=True, timeout=t).stdout

    def size(self):
        if self._size is None:
            m = re.search(r"(\d+)x(\d+)", self.sh("wm size"))
            self._size = (int(m.group(1)), int(m.group(2))) if m else (1080, 1920)
        return self._size

    def top_raw(self):
        return self.sh("dumpsys activity activities | grep -m1 topResumedActivity")

    def on_picker(self):
        return "photopicker" in self.top_raw().lower()

    def ui(self):
        self.sh("rm -f /sdcard/_tx.xml")
        self.sh("uiautomator dump /sdcard/_tx.xml")
        try:
            return ET.fromstring(self.eo("cat /sdcard/_tx.xml").decode("utf-8", "replace"))
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
        if r is None or n is None:
            return None
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
        if n is None:
            return False
        b = pb(self.anc(r, n) or n)
        if not b:
            return False
        self.sh(f"input tap {(b[0]+b[2])//2} {(b[1]+b[3])//2}")
        return True

    def scroll(self, dy=400):
        w, h = self.size()
        self.sh(f"input swipe {w//2} {int(h*0.75)} {w//2} {int(h*0.75)-dy} 300")
        time.sleep(1)

    def find_scroll(self, t, exact=False, tries=10):
        for _ in range(tries):
            r = self.ui()
            n = self.find(r, t, exact=exact)
            if n is not None:
                return r, n
            self.scroll()
        return None, None

    def start_app(self, settle=5.0):
        self.sh(f"am force-stop {PKG}")
        if "photopicker" in self.top_raw().lower():
            self.sh("am force-stop com.android.providers.media.module")
            self.sh("input keyevent KEYCODE_HOME")
            time.sleep(1)
        self.sh(f"am force-stop {PKG}")
        time.sleep(1)
        self.sh(f"am start -W -n {PKG}/{ACT}")
        time.sleep(settle)
        return PKG in self.top_raw()

    def dismiss(self):
        for _ in range(5):
            r = self.ui()
            tx = " ".join(self.texts(r))
            done = False
            if "放弃这次添加" in tx:
                n = self.find(r, "继续编辑", True)
                if n is not None and self.tap(r, n):
                    done = True
            elif "记录观察地点" in tx:
                n = self.find(r, "暂不允许", True)
                if n is not None and self.tap(r, n):
                    done = True
            else:
                for lbl in ("取消", "不允许", "暂不", "以后再说", "知道了", "拒绝"):
                    n = self.find(r, lbl, True)
                    if n is not None and self.tap(r, n):
                        done = True
                        break
            if not done:
                return
            time.sleep(2)


def run_once(d, label):
    """走一次完整识别，返回结果页文本。"""
    d.start_app()
    r = d.ui()
    if not d.tap(r, d.find(r, "添加植物")):
        print("      找不到「添加植物」")
        return None
    time.sleep(4)
    d.dismiss()

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
    if not opened:
        print("      选择器未打开")
        return None
    time.sleep(3)

    r = d.ui()
    w, h = d.size()
    tiles = []
    for x in r.iter("node"):
        b = pb(x)
        if not b:
            continue
        bw, bh = b[2] - b[0], b[3] - b[1]
        if bw > w * 0.2 and bh > w * 0.2 and bh < h * 0.6:
            tiles.append(b)
    tiles.sort(key=lambda b: (b[1], b[0]))
    if not tiles:
        print("      没有照片格子")
        return None
    print(f"      共 {len(tiles)} 格，点第 1 格 {tiles[0]}")
    d.tapb(tiles[0])
    time.sleep(2)

    r = d.ui()
    ab = None
    for x in r.iter("node"):
        t = x.get("text", "")
        if t.startswith("添加") and "项" in t:
            ab = x
            break
    if ab is None or not d.tap(r, ab):
        print("      确认选图失败")
        return None
    time.sleep(4)

    r, btn = d.find_scroll("开始识别", exact=True, tries=6)
    if btn is None:
        print("      找不到「开始识别」")
        return None
    d.tap(r, btn)

    for i in range(30):
        time.sleep(4)
        r = d.ui()
        t = d.texts(r)
        j = " ".join(t)
        if any(k in j for k in ("置信", "识别失败", "未识别", "保存到档案")):
            return t
        if i % 4 == 3:
            print(f"      …{5*(i+1)}s")
    return None


def tx(line):
    """转简体（与 App 的转换表同向，覆盖实测遇到的字）"""
    table = {"屬": "属", "薔": "蔷", "銀": "银", "葉": "叶", "樹": "树",
             "蘭": "兰", "蓮": "莲", "藥": "药", "種": "种", "變": "变"}
    return "".join(table.get(c, c) for c in line)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rounds", type=int, default=1)
    args = ap.parse_args()

    adb = require_adb_or_exit()
    s = require_device_or_exit(adb)
    d = D(adb, s)

    print("=" * 74)
    print(f"科属中文名实机验证（v1.0.2.1018 三）—— {s}")
    print("=" * 74)

    for i in range(args.rounds):
        print(f"\n─── 第 {i+1} 轮 ───")
        d.sh(f"run-as {PKG} rm -f files/datastore/capture_draft.preferences_pb")
        t = run_once(d, f"r{i+1}")
        if t is None:
            check(False, "拿到识别结果页")
            continue

        print(f"   结果页：{t[:14]}")
        j = " ".join(t)
        check("识别失败" not in j, "识别成功")
        check("无法连接" not in j, "没有连接错误")

        # 找「科：X　属：Y」
        line = None
        for x in t:
            if "科：" in x and "属：" in x:
                line = x
                break
        if line is None:
            check(False, "结果页有科属行", f"{t[:14]}")
            continue

        m = re.search(r"科：(\S+)\s*属：(\S+)", line)
        if not m:
            check(False, f"解析科属：{line}")
            continue
        fam, gen = m.group(1), m.group(2)
        print(f"   科 = {fam}    属 = {gen}")

        # 科/属 要么是中文，要么是拉丁（接口没给中文）
        has_han = lambda x: any(0x4E00 <= ord(c) <= 0x9FFF for c in x)
        simp_fam = tx(fam)
        simp_gen = tx(gen)

        check(simp_fam == fam, "科名是简体（无繁体字符）", f"科={fam}")
        check(simp_gen == gen, "属名是简体（无繁体字符）", f"属={gen}")

        # 若是中文属名，应当带「属」字结尾
        if has_han(gen):
            check(gen.endswith("属"), "中文属名以「属」结尾（问题3）", f"属={gen}")

        # 科若为中文应以「科」结尾
        if has_han(fam):
            check(fam.endswith("科"), "中文科名以「科」结尾", f"科={fam}")

    print("\n" + "=" * 74)
    print(f"结果：{PASS} 通过 / {FAIL} 失败")
    print("=" * 74)
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
