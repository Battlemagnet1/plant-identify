#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""verify_plantnet_realphotos.py —— 用**从网上找的真实照片**打真站，看输出。

## 为什么单独一个脚本

前面的验证要么用压测假图（`demo_*.jpg`）、要么用本地 mock。
用户的原始反馈是「回给的数据不仅差，还不准」——这只能拿**真实植物照片**
打真站才能判断。所以这里直接下载几张公开可用的植物照片，
逐个识别并打印：

  - 采用的项目库（`preferedReferential`）与候选条数
  - 头名物种、学名、置信度
  - **科 / 属**（拉丁 + 中文）—— 这是 bug3 的重点
  - 中文俗名有没有被拼音顶掉

## 用法

    python tools/verify_plantnet_realphotos.py --api-key <key>
    python tools/verify_plantnet_realphotos.py --api-key <key> --dir <本地图片目录>
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.parse

for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

# 选这些物种的理由：外形特征极强、几乎不可能认错，
# 于是「认错」就能说明是 API 侧的问题而不是图片太模糊。
# 全部来自 Wikimedia Commons（自由许可）。
#
# ⚠️ **不要手写 upload.wikimedia.org 的路径** —— 我第一版就是那样，
# 全部返回 HTTP 400（文件名与路径哈希对不上）。正确的是先查
# Commons API（`action=query&prop=imageinfo&iiurlwidth=`）拿到 thumburl。
SAMPLES = [
    ("向日葵 Helianthus annuus", "Sunflower sky backdrop.jpg"),
    ("月季 Rosa chinensis", "Flowers of Rosa chinensis.jpg"),
    ("银杏 Ginkgo biloba", "Ginkgo biloba in autumn.jpg"),
    ("薰衣草 Lavandula angustifolia", "Lavandula angustifolia in Sedovo 1.jpg"),
]

COMMONS_API = "https://commons.wikimedia.org/w/api.php"


def resolve_thumb(file_title: str, width: int = 800) -> str | None:
    """用 Commons API 把文件名解析成真实的缩略图 URL。

    手写 `upload.wikimedia.org/.../xxx.jpg/800px-xxx.jpg` 是不可靠的：
    路径里带 MD5 分片，猜错就是 HTTP 400。
    """
    q = urllib.parse.urlencode({
        "action": "query", "titles": f"File:{file_title}",
        "prop": "imageinfo", "iiprop": "url", "iiurlwidth": width,
        "format": "json",
    })
    for _ in range(3):
        p = subprocess.run(
            ["curl", "-sL", "-m", "45", "-A", "Mozilla/5.0 (plant-identify verifier)",
             f"{COMMONS_API}?{q}"],
            capture_output=True, timeout=90,
        )
        raw = p.stdout.decode("utf-8", "replace")
        if not raw.strip().startswith("{"):
            time.sleep(2)
            continue
        try:
            data = json.loads(raw)
        except json.JSONDecodeError:
            time.sleep(2)
            continue
        for _, page in (data.get("query", {}).get("pages", {}) or {}).items():
            info = (page.get("imageinfo") or [{}])[0]
            url = info.get("thumburl") or info.get("url")
            if url:
                return url
        return None
    return None

PASS = FAIL = 0


def check(ok: bool, label: str, detail: str = "") -> bool:
    global PASS, FAIL
    if ok:
        PASS += 1
        print(f"   [PASS] {label}")
    else:
        FAIL += 1
        print(f"   [FAIL] {label}" + (f"\n          {detail}" if detail else ""))
    return ok


def has_han(t: str) -> bool:
    return any(0x4E00 <= ord(c) <= 0x9FFF or 0x3400 <= ord(c) <= 0x4DBF for c in (t or ""))


TRAD = set("屬薑莢葉樹園蘭蓮藥薔鳳龍貓魚龜鶴鷹鴨鵝鴿銀")


def no_trad(t: str) -> bool:
    """与 App 的 containsTraditional 同向：含已知繁体字即为否。"""
    return not any(c in TRAD for c in (t or ""))


def to_simplified(t: str) -> str:
    """与 App 的 TaxonomyNames.toSimplified 等价（只覆盖分类名高频字）。"""
    table = {
        "屬": "属", "薑": "姜", "莢": "荚", "葉": "叶", "樹": "树", "園": "园",
        "蘭": "兰", "蓮": "莲", "藥": "药", "薔": "蔷", "銀": "银", "鳳": "凤",
        "龍": "龙", "貓": "猫", "魚": "鱼", "龜": "龟", "鶴": "鹤", "鷹": "鹰",
        "鴨": "鸭", "鵝": "鹅", "鴿": "鸽", "綱": "纲", "門": "门", "亞": "亚",
        "種": "种", "變": "变",
    }
    return "".join(table.get(c, c) for c in (t or ""))


def pick(names):
    """与 Kotlin 侧 pickDisplayName 同规则：汉字 → 简体优先"""
    nb = [n for n in names if n and n.strip()]
    if not nb:
        return None
    ch = [n for n in nb if has_han(n)]
    if not ch:
        return nb[0]
    return next((n for n in ch if no_trad(n)), ch[0])


def download(url: str, dst: str) -> bool:
    for _ in range(3):
        p = subprocess.run(
            ["curl", "-sL", "-m", "90", "-A",
             "Mozilla/5.0 (plant-identify verifier)", "-o", dst, url],
            capture_output=True, timeout=120,
        )
        if os.path.exists(dst) and os.path.getsize(dst) > 5000:
            return True
        time.sleep(2)
    return False


def identify(api_key: str, image: str) -> dict | None:
    url = (f"https://my-api.plantnet.org/v2/identify/k-world-flora"
           f"?api-key={api_key}&lang=zh&detailed=true&nb-results=10")
    for i in range(6):
        p = subprocess.run(
            ["curl", "-s", "-m", "150", url,
             "-F", "organs=leaf", "-F", f"images=@{image}"],
            capture_output=True, timeout=200,
        )
        raw = p.stdout.decode("utf-8", "replace")
        if raw.strip().startswith("{"):
            try:
                return json.loads(raw)
            except json.JSONDecodeError:
                pass
        print(f"       …第 {i + 1} 次上传失败/超时，重试")
        time.sleep(3)
    return None


def main() -> int:
    global PASS, FAIL
    ap = argparse.ArgumentParser()
    ap.add_argument("--api-key", required=True)
    ap.add_argument("--dir", default="", help="本地图片目录（跳过下载）")
    args = ap.parse_args()
    key = args.api_key.strip()

    workdir = args.dir or os.path.join(os.environ.get("TEMP", "."), "pn_realphotos")
    os.makedirs(workdir, exist_ok=True)

    print("=" * 78)
    print("Pl@ntNet 真实照片验证（真站 · k-world-flora · lang=zh · detailed=true）")
    print("=" * 78)

    tests = []
    if args.dir:
        for f in sorted(os.listdir(workdir)):
            if f.lower().endswith((".jpg", ".jpeg", ".png")):
                tests.append((f, os.path.join(workdir, f)))
    else:
        print("\n[0] 解析并下载测试照片（Wikimedia Commons，自由许可）")
        for label, title in SAMPLES:
            safe = "".join(c if c.isalnum() or c in "._-" else "_" for c in title)
            dst = os.path.join(workdir, safe)
            if os.path.exists(dst) and os.path.getsize(dst) > 5000:
                print(f"   ✓ 已有 {safe}")
                tests.append((label, dst))
                continue
            url = resolve_thumb(title)
            if not url:
                print(f"   ✗ {label}：Commons 上找不到 File:{title}")
                continue
            ok = download(url, dst)
            print(f"   {'✓' if ok else '✗'} {label}  ({safe})")
            if ok:
                tests.append((label, dst))

    if not tests:
        print("❌ 没有可用照片")
        return 1

    print("\n" + "=" * 78)
    print("[1] 逐个识别")
    print("=" * 78)

    for label, path in tests:
        print(f"\n── {label}")
        print(f"   {os.path.basename(path)}  {os.path.getsize(path)} 字节")
        d = identify(key, path)
        if d is None:
            print("   ❌ 请求失败")
            FAIL += 1
            continue

        ref = d.get("preferedReferential")
        results = d.get("results") or []
        ot = d.get("otherResults") or {}

        fam_map, gen_map = {}, {}
        for lvl, into in (("family", fam_map), ("genus", gen_map)):
            for item in (ot.get(lvl) or []):
                node = item.get(lvl) or {}
                latin = (node.get("scientificNameWithoutAuthor")
                         or node.get("scientificName") or "").split(" ")[0].strip()
                zh = pick(node.get("commonNames") or [])
                if latin and zh and has_han(zh):
                    into.setdefault(latin.lower(), zh)

        print(f"   采用库 : {ref}")
        print(f"   候选数 : {len(results)}")

        if not results:
            msg = d.get("message") or d.get("statusMessage") or ""
            print(f"   ⚠️ 无候选（{msg}）")
            FAIL += 1
            continue

        top = results[0]
        sp = top.get("species") or {}
        fam_l = ((sp.get("family") or {}).get("scientificNameWithoutAuthor") or "")
        gen_l = ((sp.get("genus") or {}).get("scientificNameWithoutAuthor") or "")
        fam_show = fam_map.get(fam_l.lower(), fam_l)
        gen_show = gen_map.get(gen_l.lower(), gen_l)
        name = pick(sp.get("commonNames") or []) or (sp.get("scientificNameWithoutAuthor") or "")

        print(f"   ── 头名 ──")
        print(f"   名称   : {name}")
        print(f"   学名   : {sp.get('scientificNameWithoutAuthor')}")
        print(f"   科     : {fam_show}   (拉丁 {fam_l})")
        print(f"   属     : {gen_show}   (拉丁 {gen_l})")
        print(f"   置信度 : {top.get('score')}")

        print(f"   ── 备选（前 4）──")
        for r in results[1:5]:
            s = r.get("species") or {}
            n = pick(s.get("commonNames") or []) or s.get("scientificNameWithoutAuthor")
            print(f"   {r.get('score', 0):.4f}  {s.get('scientificNameWithoutAuthor')}  {n}")

        # ---- 断言（针对 bug2 / bug3）----
        check(ref == "k-world-flora", f"{label} 采用 k-world-flora（bug2）", f"实际={ref}")
        check(len(results) > 2, f"{label} 候选数 > 2", f"实际={len(results)}")
        # 科属必须是中文，且**必须是简体**（应用会把繁体转简体后再显示）
        fam_ok = bool(fam_show) and has_han(fam_show) and no_trad(fam_show)
        gen_ok = bool(gen_show) and has_han(gen_show) and no_trad(gen_show)
        check(fam_ok, f"{label} 科为简体中文（bug3）",
              f"科={fam_show}（原始 {fam_latin}）")
        check(gen_ok, f"{label} 属为简体中文（bug3）",
              f"属={gen_show}（原始 {gen_latin}）")
        if has_han(name):
            check(no_trad(name), f"{label} 名称非繁体", f"名称={name}")

    print("\n" + "=" * 78)
    print(f"结果：{PASS} 通过 / {FAIL} 失败")
    print("=" * 78)
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
