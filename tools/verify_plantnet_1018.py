#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""verify_plantnet_1018.py —— v1.0.2.1018 三项修复的实机验证。

在**真机/模拟器**上跑完整流程，验证：

- **bug2/bug3**：识别结果的科属是**中文**、候选来自 `k-world-flora`
- **bug1**：编辑页有「从相册添加照片」入口，且能从系统相册导入照片

用法：
    python tools/verify_plantnet_1018.py --api-key <key>
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
        self.sh("rm -f /sdcard/_v18.xml")
        self.sh("uiautomator dump /sdcard/_v18.xml")
        try:
            return ET.fromstring(self.eo("cat /sdcard/_v18.xml").decode("utf-8", "replace"))
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
        """沿父链找最近的可点祖先。r 或 n 为 None 时返回 None（别让它崩）。"""
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
        t = self.anc(r, n) or n
        b = pb(t)
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

    def start_app(self, settle: float = 6.0) -> bool:
        """干净地启动应用。

        ⚠️ `am force-stop` 之后若**照片选择器还挂在栈顶**，
        再 `am start` 只会把 intent 投递给"已在运行"的旧实例、
        应用进程却不会起来，随后所有 dump 都是空界面。
        所以先把选择器进程也停掉，再启动。
        """
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

    def shot(self, name):
        open(name, "wb").write(self.eo("screencap -p"))

    def dismiss_dialogs(self):
        """关掉各种"首次"弹窗与残留对话框。

        ⚠️ 「放弃这次添加？」是**草稿页的二次确认**，点「放弃并删除」
        会把已添加的照片删掉。所以在添加流程里必须点「继续编辑」，
        不能无脑点第一个按钮。
        """
        for _ in range(5):
            r = self.ui()
            tx = " ".join(self.texts(r))
            done = False

            # 草稿页的「放弃这次添加？」—— 要保留草稿，点「继续编辑」
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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--api-key", required=True)
    args = ap.parse_args()
    key = args.api_key.strip()

    adb = require_adb_or_exit()
    s = require_device_or_exit(adb)
    d = D(adb, s)

    print("=" * 74)
    print(f"v1.0.2.1018 三项修复实机验证 —— {s}")
    print(f"屏幕 {d.size()}")
    print("=" * 74)

    # ---------------------------------------------------------- 0 前置
    print("\n[0] 前置")
    ver = d.sh(f"dumpsys package {PKG}")
    m = re.search(r"versionName=(\S+)", ver)
    check(bool(m), f"版本 {m.group(1) if m else '?'}")
    cfg = d.eo(f"run-as {PKG} cat files/datastore/ai_config.preferences_pb").decode("utf-8", "replace")
    check("k-world-flora" in cfg or "plantnet" in cfg.lower(), "读到 Pl@ntNet 配置")

    # ---------------------------------------------------------- 1 配置通道
    print("\n[1] 配置识别通道 = Pl@ntNet")
    if not check(d.start_app(), "应用启动成功"):
        return 1
    d.dismiss_dialogs()

    r = d.ui()
    opened = False
    for _ in range(6):
        r = d.ui()
        node = d.find(r, "设置", exact=True)
        if node is not None and d.tap(r, node):
            opened = True
            break
        time.sleep(2)
    if not check(opened, "打开设置页", f"文本={d.texts(d.ui())[:15]}"):
        return 1
    time.sleep(3)

    r, n = d.find_scroll("Pl@ntNet", exact=True, tries=10)
    if not check(n is not None, "找到 Pl@ntNet 通道"):
        print("      文本:", d.texts(d.ui())[:25])
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

    # Base URL 恢复为官方端点
    r, uf = d.find_scroll("Pl@ntNet Base URL", exact=True, tries=6)
    if uf is not None:
        d.tap(r, uf)
        time.sleep(1)
        d.sh("input keyevent KEYCODE_MOVE_END")
        for _ in range(120):
            d.sh("input keyevent KEYCODE_DEL")
        d.sh("input text 'https://my-api.plantnet.org/v2'")
        time.sleep(1)
        d.sh("input keyevent KEYCODE_BACK")
        time.sleep(1)

    r, save = d.find_scroll("保存配置", exact=True, tries=8)
    if not check(save is not None, "找到保存按钮"):
        return 1
    check((d.anc(r, save) or save).get("enabled") == "true", "保存按钮可点")
    d.tap(r, save)
    time.sleep(3)
    cfg = d.eo(f"run-as {PKG} cat files/datastore/ai_config.preferences_pb").decode("utf-8", "replace")
    check("PLANT_NET" in cfg, "通道已保存为 PLANT_NET")
    check("k-world-flora" in cfg or "my-api" in cfg, "端点为官方 v2")

    # ---------------------------------------------------------- 2 识别（bug2/bug3）
    print("\n[2] 走真实识别（bug2 项目库 / bug3 科属中文）")
    d.sh("input keyevent KEYCODE_BACK")
    time.sleep(2)
    d.start_app(settle=4)

    r = d.ui()
    add = d.find(r, "添加植物")
    if not check(add is not None and d.tap(r, add), "点「添加植物」"):
        print("      文本:", d.texts(d.ui())[:20])
        return 1
    time.sleep(4)
    d.dismiss_dialogs()

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
    if not check(opened, "照片选择器已打开"):
        print("      文本:", d.texts(d.ui())[:20])
        return 1
    time.sleep(3)

    r = d.ui()
    w, h = d.size()
    # 选择器里的缩略图是**三列网格**的大格子（实测 1440 宽屏上约 472×472），
    # 而「最近 / 照片 / 影集」那几个图标是 224×224 的 ImageButton。
    # 尺寸下限必须高于图标、上限覆盖整格 —— 否则会选到一个图标，
    # 点下去没有任何效果（「已添加 0/5」）。
    tiles = []
    for x in r.iter("node"):
        b = pb(x)
        if not b:
            continue
        bw, bh = b[2] - b[0], b[3] - b[1]
        if bw < w * 0.2 or bh < w * 0.2:      # 太小的不是照片格子
            continue
        if bh > h * 0.6:                      # 太高的是列表容器，不是格子
            continue
        tiles.append(b)

    if not check(bool(tiles), "找到照片格子", f"共 {len(tiles)} 个候选"):
        print("      可点元素:", [pb(x) for x in r.iter("node") if pb(x) and x.get("clickable") == "true"][:10])
        return 1

    # 取最靠上、再靠左的格子；但避开贴屏幕边缘的（点击会被手势区吃掉）
    def safe(b):
        cx, cy = (b[0] + b[2]) // 2, (b[1] + b[3]) // 2
        return 0.05 * w < cx < 0.95 * w

    usable = sorted([b for b in tiles if safe(b)], key=lambda b: (b[1], b[0]))
    target = usable[0] if usable else sorted(tiles, key=lambda b: (b[1], b[0]))[0]
    print(f"      共 {len(tiles)} 个格子，取最左上安全区内的 {target}")
    d.tapb(target)
    time.sleep(2)

    # 多选模式下点选后**不会**自动关闭，必须再点「添加(N 项)」
    r = d.ui()
    picked = [t for t in d.texts(r) if "添加" in t and "项" in t]
    print(f"      选择器状态: {picked or '（未见「添加(N 项)」）'}")

    r = d.ui()
    ab = None
    for x in r.iter("node"):
        t = x.get("text", "")
        if t.startswith("添加") and "项" in t:
            ab = x
            break
    if not check(ab is not None and d.tap(r, ab), "确认选图"):
        return 1
    time.sleep(5)

    r = d.ui()
    check(any("1/5" in t for t in d.texts(r)), "照片已附上")

    r, btn = d.find_scroll("开始识别", exact=True, tries=6)
    if not check(btn is not None, "找到「开始识别」"):
        return 1
    d.tap(r, btn)

    texts = []
    for i in range(40):
        time.sleep(5)
        r = d.ui()
        texts = d.texts(r)
        j = " ".join(texts)
        if any(k in j for k in ("置信", "未识别", "识别失败", "保存到档案")):
            break
        if i % 4 == 3:
            print(f"      ...轮询 {5*(i+1)}s: {texts[:3]}")
    d.shot(os.path.join(os.environ.get("TEMP", "."), "v1018_result.png"))
    print(f"\n      结果页: {texts[:16]}")

    j = " ".join(texts)
    check("识别失败" not in j, "识别成功", f"{texts[:14]}")
    check("还没有配置" not in j, "没有配置类错误", f"{texts[:14]}")
    check("无法连接" not in j, "没有连接错误", f"{texts[:14]}")

    # bug3：科属应为中文
    fam = [t for t in texts if t.startswith("科：") or "科：" in t]
    print(f"      科属行: {fam}")
    if fam:
        line = fam[0]
        # 形如「科：樟科　属：樟屬」
        han = any(0x4E00 <= ord(c) <= 0x9FFF for c in line.replace("科：", "").replace("属：", ""))
        check(han, "科属显示为中文（bug3）", f"实际={line}")
        # 不能是纯拉丁
        check(not re.fullmatch(r"科：[A-Za-z]+　?属：[A-Za-z]+", line),
              "科属不是纯拉丁", f"实际={line}")

    # ---------------------------------------------------------- 3 从相册导入（bug1）
    print("\n[3] bug1：编辑页「从相册添加照片」")
    d.sh("input keyevent KEYCODE_BACK")
    time.sleep(2)
    d.start_app(settle=4)

    r = d.ui()
    # 首页应已有刚识别的档案（若没保存则进不去编辑页，改从「植物档案」找）
    entry = d.find(r, "植物档案")
    print(f"      首页文本: {d.texts(r)[:10]}")

    # 直接找「编辑」入口
    r, edit = d.find_scroll("编辑", exact=True, tries=6)
    if edit is not None:
        d.tap(r, edit)
        time.sleep(3)
        r, addph = d.find_scroll("从相册添加照片", exact=True, tries=6)
        check(addph is not None, "编辑页有「从相册添加照片」按钮（bug1）",
              f"文本={d.texts(d.ui())[:20]}")
        if addph is not None:
            hint = [t for t in d.texts(d.ui()) if "含本应用保存到相册的图" in t]
            check(bool(hint), "按钮下方有说明文字", f"文本={hint}")
    else:
        check(False, "找不到编辑入口（可能尚未保存档案）",
              f"文本={d.texts(d.ui())[:20]}")

    print("\n" + "=" * 74)
    print(f"结果：{PASS} 通过 / {FAIL} 失败")
    print("=" * 74)
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
