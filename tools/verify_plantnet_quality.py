#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""verify_plantnet_quality.py —— 验证 Pl@ntNet 返回质量与中文对齐（v1.0.2.1018）。

## 验什么

对照**真实服务端**的响应，逐项确认 v1.0.2.1018 修的三件事：

1. **项目库固定为 `k-world-flora`**（不再是 `all` 随机落到 `invasion` 窄库）
   → 候选条数从 2 变成 ~9
2. **`detailed=true`** 让 `otherResults` 存在 → 科属能有中文名
3. **`lang=zh` + 挑含汉字的 commonNames** → 中文名不被拼音顶掉

并模拟应用侧的解析逻辑，打印「用户实际会看到什么」。

## 用法

    python tools/verify_plantnet_quality.py --api-key <key> [--image 本地jpg]

不需要设备：直接对服务端发请求（本机上行慢时会自动重试）。
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time

for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

PASS = FAIL = 0


def check(ok: bool, label: str, detail: str = "") -> bool:
    global PASS, FAIL
    if ok:
        PASS += 1
        print(f"  [PASS] {label}")
    else:
        FAIL += 1
        print(f"  [FAIL] {label}" + (f"\n         {detail}" if detail else ""))
    return ok


def has_han(text: str) -> bool:
    """与 Kotlin 侧 PlantNetProvider.hasHan 同一判据"""
    return any(0x4E00 <= ord(c) <= 0x9FFF or 0x3400 <= ord(c) <= 0x4DBF for c in (text or ""))


def post(url: str, image: str, retries: int = 6):
    """带重试的 multipart 上传（本机上行慢，单次常失败）"""
    for i in range(1, retries + 1):
        p = subprocess.run(
            ["curl", "-s", "-m", "120", url,
             "-F", "organs=leaf", "-F", f"images=@{image}"],
            capture_output=True, timeout=180,
        )
        raw = p.stdout.decode("utf-8", "replace")
        if raw.strip().startswith("{"):
            try:
                return json.loads(raw)
            except json.JSONDecodeError:
                pass
        print(f"      ...第 {i} 次失败，重试")
        time.sleep(2)
    return None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--api-key", required=True)
    ap.add_argument("--image", default="")
    args = ap.parse_args()
    key = args.api_key.strip()

    image = args.image.strip()
    if not image:
        # 默认用之前拉下来的樟树照片
        cand = os.path.join(os.environ.get("TEMP", "."), "zhangshu.jpg")
        if os.path.exists(cand):
            image = cand
    if not image or not os.path.exists(image):
        print("❌ 需要一张本地 jpg：--image <路径>")
        return 1

    print("=" * 74)
    print("Pl@ntNet 返回质量与中文对齐验证（v1.0.2.1018）")
    print(f"图片：{image}")
    print("=" * 74)

    base = "https://my-api.plantnet.org/v2/identify"

    # ------------------------------------------------ 1 · all 的参照系
    print("\n[1] 对照组：`all` 会落到哪个项目库（这是 bug2 的根因）")
    d_all = post(f"{base}/all?api-key={key}&lang=zh&detailed=true&nb-results=10", image)
    if not check(d_all is not None, "`all` 请求成功"):
        return 1
    print(f"      preferedReferential = {d_all.get('preferedReferential')}")
    print(f"      results 条数        = {len(d_all.get('results') or [])}")

    # ------------------------------------------------ 2 · 固定 k-world-flora
    print("\n[2] 修复后：固定 `k-world-flora`")
    d_fix = post(f"{base}/k-world-flora?api-key={key}&lang=zh&detailed=true&nb-results=10", image)
    if not check(d_fix is not None, "`k-world-flora` 请求成功"):
        return 1

    ref = d_fix.get("preferedReferential")
    results = d_fix.get("results") or []
    print(f"      preferedReferential = {ref}")
    print(f"      results 条数        = {len(results)}")

    check(ref == "k-world-flora", "服务端采用的库就是 k-world-flora（不再漂移到 invasion）", f"实际={ref}")
    check(len(results) > 2, "候选条数多于 `all` 的 2 条", f"实际={len(results)}")
    check(len(results) >= len(d_all.get("results") or []),
          "候选数不少于对照组", f"fix={len(results)} all={len(d_all.get('results') or [])}")

    # ------------------------------------------------ 3 · otherResults
    print("\n[3] 科属中文名来源：otherResults")
    ot = d_fix.get("otherResults") or {}
    check(bool(ot), "detailed=true 返回了 otherResults", f"keys={list(ot.keys())}")

    def zh_of(node) -> str:
        for n in (node.get("commonNames") or []):
            if has_han(n):
                return n
        return ""

    fam_map, gen_map = {}, {}
    for lvl, into in (("family", fam_map), ("genus", gen_map)):
        for item in (ot.get(lvl) or []):
            node = item.get(lvl) or {}
            latin = (node.get("scientificNameWithoutAuthor") or node.get("scientificName") or "")
            latin = latin.split(" ")[0].strip()
            zh = zh_of(node)
            if latin and zh:
                into.setdefault(latin.lower(), zh)

    print(f"      family 中文名 {len(fam_map)} 条: "
          f"{list(fam_map.values())[:4]}")
    print(f"      genus  中文名 {len(gen_map)} 条: "
          f"{list(gen_map.values())[:4]}")
    check(len(fam_map) > 0, "科有中文名（樟科）", f"map={fam_map}")
    check(len(gen_map) > 0, "属有中文名（樟属）", f"map={gen_map}")

    # ------------------------------------------------ 4 · 模拟应用解析结果
    print("\n[4] 模拟应用侧解析 —— 用户实际会看到什么")
    top = results[0] if results else {}
    sp = top.get("species") or {}
    fam_latin = ((sp.get("family") or {}).get("scientificNameWithoutAuthor") or "")
    gen_latin = ((sp.get("genus") or {}).get("scientificNameWithoutAuthor") or "")
    fam_show = fam_map.get(fam_latin.lower(), fam_latin)
    gen_show = gen_map.get(gen_latin.lower(), gen_latin)

    common = [n for n in (sp.get("commonNames") or []) if has_han(n)]
    name = common[0] if common else (sp.get("scientificNameWithoutAuthor") or "")

    print(f"      名称   : {name}")
    print(f"      学名   : {sp.get('scientificNameWithoutAuthor')}")
    print(f"      科     : {fam_show}   （拉丁 {fam_latin}）")
    print(f"      属     : {gen_show}   （拉丁 {gen_latin}）")
    print(f"      置信度 : {top.get('score')}")

    check(bool(name), "拿到了物种名")
    check(has_han(fam_show) or not fam_latin, "科显示为中文（或该科确实无中文名）",
          f"科={fam_show}")
    check(has_han(gen_show) or not gen_latin, "属显示为中文（或该属确实无中文名）",
          f"属={gen_show}")

    # 头名应该有中文俗名（樟树这类常见园林树）
    check(bool(common), "头名物种有中文俗名（不是拼音/拉丁）", f"commonNames={sp.get('commonNames')}")

    # ------------------------------------------------ 5 · 拼音不会被误选
    print("\n[5] 拼音混入的处理")
    all_names = []
    for r in results:
        all_names.extend((r.get("species") or {}).get("commonNames") or [])
    with_pinyin_front = [n for n in all_names if n and not has_han(n) and n[:1].isupper()]
    if with_pinyin_front:
        print(f"      含拼音样式的条目 {len(with_pinyin_front)} 个，例如 {with_pinyin_front[:3]}")
    check(has_han(name), "最终选中项是汉字名（拼音在前也不会被选中）", f"选中={name}")

    print("\n" + "=" * 74)
    print(f"结果：{PASS} 通过 / {FAIL} 失败")
    print("=" * 74)
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
