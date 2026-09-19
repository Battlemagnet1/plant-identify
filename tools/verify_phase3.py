#!/usr/bin/env python3
"""
Phase 3 自动验收脚本。

配合 tools/mock_ai_server.py 使用：模拟服务端可以在多种响应模式间切换，
从而把「模型返回千奇百怪的内容时 App 表现如何」变成确定性断言。
这一点很重要 —— 用真实服务很难稳定复现 ```json 围栏、字段缺失、
非 JSON 等情形，也不该为了测一个错误分支去烧额度。

## 前置条件

1. 模拟器/真机已连接
2. 模拟服务端已启动：python tools/mock_ai_server.py --port 8899
3. App 已安装，相册里至少有 3 张图片
4. App 内的 AI 配置指向 http://127.0.0.1:8899/v1（脚本会引导完成）

## 用法

    # 完整流程（含配置 AI 服务）
    python tools/verify_phase3.py --device 192.168.253.119:5555

    # 已配置过，只跑验证矩阵
    python tools/verify_phase3.py --skip-setup

## 脚本内已规避的四个坑（都是实测踩出来的）

1. **XML 叶子节点的布尔值为 False** —— `if node:` 永远不成立，
   必须写 `if node is not None:`。这是 ElementTree 的行为，与 adb 无关。

2. **页面切换动画未结束时取到的 bounds 已过期** —— 点击前要等界面稳定
   并**重新取一次**坐标，否则会点空。

3. **模糊匹配会撞车** —— 说明性段落里常包含按钮文字，例如
   「可以拍照，也可以从相册选择…」含「从相册选择」；
   「…就能看出部位标注是否有帮助」含「部位标注」。
   必须用精确匹配，或加长度/前缀等额外判据。

4. **键盘不可见时按返回键会触发页面返回** —— 收起键盘前必须先查
   `dumpsys input_method` 的 `mInputShown`。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET

# 本机相关的路径都可用环境变量覆盖，避免把某台机器的布局写死在仓库里
# （与 verify_phase4.py / verify_phase5.py 保持一致）
# adb 路径：ADB -> ANDROID_HOME / local.properties 的 sdk.dir -> PATH。
# 全都没有时由 _env 打印可选做法并退出，不会拿一个不存在的路径硬跑。
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _env  # noqa: E402  —— tools/_env.py，统一解析本机环境

ADB = _env.require_adb_or_exit()
DEVICE = os.environ.get("ANDROID_SERIAL", "192.168.253.119:5555")
MOCK = os.environ.get("MOCK_BASE", "http://127.0.0.1:8899")
# 允许用环境变量覆盖包名 —— 同一套脚本要能验收 base 与 full 两个版本。
# 默认仍是基础版的 applicationId。
PKG = os.environ.get("PKG", "com.plantidentify")

def start_app():
    """启动应用（组件名动态解析，兼容基础版与完整版）。

    `am start -n <PKG>/.MainActivity` 这种简写只在「包名与 namespace 相同」
    时才成立。完整版把 applicationId 改成了 com.plantidentify.full，
    而 namespace 仍是 com.plantidentify —— 简写会被展开成
    com.plantidentify.full.com.plantidentify.full.MainActivity，
    那是**不存在的类**，启动会静默失败，后续所有界面断言都会读到一个
    根本不是目标应用的界面。

    所以这里先问系统要真正的启动组件，问不到再退回简写。
    """
    out = shell("cmd", "package", "resolve-activity", "--brief", PKG)
    for line in reversed(out.replace("\r", "").split("\n")):
        line = line.strip()
        if line.startswith(PKG + "/"):
            shell("am", "start", "-n", line)
            return True
    start_app()
    return False


OUT_DIR = os.environ.get(
    "SHOT_DIR",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, ".workbuddy"),
)

passed = 0
failed = 0


# ---------------------------------------------------------------- adb / UI


def adb(*args: str, timeout: int = 60, binary: bool = False):
    result = subprocess.run(
        [ADB, "-s", DEVICE, *args], capture_output=True, timeout=timeout
    )
    if binary:
        return result.stdout
    return result.stdout.decode("utf-8", errors="replace")


def shell(*args: str, timeout: int = 60) -> str:
    return adb("shell", *args, timeout=timeout)


def dump():
    for _ in range(3):
        raw = adb("exec-out", "uiautomator", "dump", "/dev/tty", timeout=40)
        start, end = raw.find("<?xml"), raw.rfind("</hierarchy>")
        if start >= 0 and end > start:
            try:
                return ET.fromstring(raw[start : end + len("</hierarchy>")])
            except ET.ParseError:
                pass
        time.sleep(1)
    return None


def ntext(node) -> str:
    return node.get("text") or node.get("content-desc") or ""


def center(node):
    if node is None:
        return None
    nums = re.findall(r"-?\d+", node.get("bounds") or "")
    if len(nums) != 4:
        return None
    x1, y1, x2, y2 = (int(v) for v in nums)
    return (x1 + x2) // 2, (y1 + y2) // 2


def find(text: str, exact: bool = False):
    """按文字找节点。注意返回 None 而非 False（见文件头第 1 条坑）。"""
    root = dump()
    if root is None:
        return None
    for node in root.iter("node"):
        value = ntext(node)
        if not value:
            continue
        if (value == text) if exact else (text in value):
            return node
    return None


def find_all(sub: str):
    root = dump()
    if root is None:
        return []
    return [n for n in root.iter("node") if sub in ntext(n)]


def screen_text() -> str:
    root = dump()
    if root is None:
        return ""
    return "\n".join(ntext(n) for n in root.iter("node") if ntext(n))


def tap_text(text: str, exact: bool = False, timeout: int = 15) -> bool:
    """等文字出现 → 等界面稳定 → 重新取坐标 → 点击（见文件头第 2 条坑）"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        if find(text, exact=exact) is not None:
            break
        time.sleep(0.8)
    else:
        return False

    time.sleep(1.2)
    pos = center(find(text, exact=exact))
    if pos is None:
        return False
    shell("input", "tap", str(pos[0]), str(pos[1]))
    return True


def tap_xy(x: int, y: int) -> None:
    shell("input", "tap", str(x), str(y))


def wait_for_any(texts, timeout: int = 45):
    deadline = time.time() + timeout
    while time.time() < deadline:
        current = screen_text()
        for t in texts:
            if t in current:
                return t
        time.sleep(1.0)
    return None


def scroll_down(times: int = 1) -> None:
    """向下滚动内容（看更下面的东西）"""
    for _ in range(times):
        shell("input", "swipe", "960", "900", "960", "300", "400")
        time.sleep(1.3)


def scroll_up(times: int = 1) -> None:
    for _ in range(times):
        shell("input", "swipe", "960", "300", "960", "900", "400")
        time.sleep(1.3)


def scroll_to(text: str, exact: bool = True, tries: int = 6) -> bool:
    """向下滚动直到目标出现"""
    for _ in range(tries):
        if find(text, exact=exact) is not None:
            return True
        scroll_down()
    return find(text, exact=exact) is not None


def type_text(value: str) -> None:
    shell("input", "text", value.replace(" ", "%s"))
    time.sleep(0.6)


def hide_keyboard() -> None:
    """只在键盘确实可见时按返回，否则会误触发页面返回（见文件头第 4 条坑）"""
    info = shell("dumpsys", "input_method")
    if "mInputShown=true" in info or "mIsInputViewShown=true" in info:
        shell("input", "keyevent", "4")
        time.sleep(0.8)


def shot(name: str) -> None:
    os.makedirs(OUT_DIR, exist_ok=True)
    out = os.path.join(OUT_DIR, f"{name}.png")
    raw = adb("exec-out", "screencap", "-p", timeout=60, binary=True)
    with open(out, "wb") as f:
        f.write(raw)
    print(f"     截图 -> {out}")


# ---------------------------------------------------------------- mock 控制


def mock_post(path: str) -> dict:
    req = urllib.request.Request(f"{MOCK}{path}", method="POST")
    with urllib.request.urlopen(req, timeout=10) as resp:
        return json.loads(resp.read().decode("utf-8"))


def set_mode(mode: str) -> None:
    mock_post(f"/control?mode={mode}")


def reset_log() -> None:
    mock_post("/reset")


def mock_log() -> dict:
    with urllib.request.urlopen(f"{MOCK}/log", timeout=10) as resp:
        return json.loads(resp.read().decode("utf-8"))


# ---------------------------------------------------------------- 断言


def check(name: str, ok: bool, extra: str = "") -> None:
    global passed, failed
    if ok:
        passed += 1
        print(f"  [PASS] {name}")
    else:
        failed += 1
        print(f"  [FAIL] {name}" + (f"  <- {extra}" if extra else ""))


# ---------------------------------------------------------------- 流程


def open_app() -> None:
    shell("am", "force-stop", PKG)
    time.sleep(1.5)
    start_app()
    time.sleep(5)


def goto_result_page() -> bool:
    """从首页走一遍：添加植物 → 开始识别 → 等到结果页"""
    open_app()
    if not tap_text("添加植物", exact=True, timeout=20):
        # 首页按钮文字可能带图标描述，回退到模糊
        if not tap_text("添加植物", timeout=10):
            return False
    time.sleep(3)

    if not scroll_to("开始识别", exact=True):
        return False
    if not tap_text("开始识别", exact=True, timeout=10):
        return False

    return wait_for_any(["模型置信度", "识别失败"], timeout=70) is not None


def retrigger(mode: str, expect: list[str], timeout: int = 45) -> bool:
    """切换 mock 模式 → 点重新识别 → 断言页面出现预期文字"""
    set_mode(mode)
    reset_log()

    if not scroll_to("重新识别", exact=True):
        # 按钮在结果页底部，若当前停在页面中部需要先滚下去
        scroll_down(2)
        if not scroll_to("重新识别", exact=True):
            return False

    if not tap_text("重新识别", exact=True, timeout=8):
        return False

    return wait_for_any(expect, timeout=timeout) is not None


def configure_via_ui(base_url: str, model: str, api_key: str) -> bool:
    """在设置页填好三项配置。

    定位输入框时用**精确匹配**：状态卡上会出现「还缺少：Base URL、模型名」
    这样的文字，模糊匹配会点到状态卡上（见文件头第 3 条坑）。
    """
    if not tap_text("设置", exact=True, timeout=15):
        return False
    time.sleep(3)

    tap_text("自定义", timeout=10)
    time.sleep(1.2)

    if tap_text("Base URL", exact=True, timeout=10):
        time.sleep(1)
        type_text(base_url)
        hide_keyboard()

    if tap_text("模型名", exact=True, timeout=10):
        time.sleep(1)
        type_text(model)
        hide_keyboard()

    if tap_text("API Key", exact=True, timeout=10):
        time.sleep(1)
        type_text(api_key)
        hide_keyboard()

    return True


def main() -> int:
    global DEVICE
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", default=DEVICE)
    parser.add_argument("--skip-setup", action="store_true")
    parser.add_argument("--base-url", default="http://127.0.0.1:8899/v1")
    parser.add_argument("--model", default="mock-vl")
    parser.add_argument("--api-key", default="test-key-1234567890abcdef")
    args = parser.parse_args()
    DEVICE = args.device

    print("=" * 70)
    print("Phase 3 验收：AI 视觉识别链路")
    print("=" * 70)

    # ---------------- 0. 环境
    print("\n[0] 环境")
    check("设备已连接", DEVICE in adb("devices"))
    try:
        mock_post("/control?mode=ok")
        check("模拟服务端可用", True)
    except Exception as exc:  # noqa: BLE001
        check("模拟服务端可用", False, str(exc))
        return 1

    reverse = adb("reverse", "tcp:8899", "tcp:8899")
    check("端口反向转发已建立", "error" not in reverse.lower(), reverse.strip())

    # 设备侧能否真的访问到
    probe = shell("curl", "-s", "--max-time", "6", f"{MOCK}/health")
    check("设备侧可访问模拟服务端", "ok" in probe, probe[:80])

    # ---------------- 1. 配置
    if not args.skip_setup:
        print("\n[1] 配置 AI 服务")
        open_app()
        ok = configure_via_ui(args.base_url, args.model, args.api_key)
        check("填写三项配置", ok)

        if scroll_to("测试连接", exact=True):
            tap_text("测试连接", exact=True)
            check("测试连接成功", wait_for_any(["连接正常"], timeout=30) is not None)

        if scroll_to("保存配置", exact=True):
            tap_text("保存配置", exact=True)
            check("配置已保存", wait_for_any(["已保存"], timeout=20) is not None)

        # 落盘校验：明文 Key 不得出现
        raw = adb(
            "exec-out", "run-as", PKG, "cat",
            "files/datastore/ai_config.preferences_pb",
            binary=True, timeout=60,
        )
        check(
            "配置文件不含明文 API Key",
            args.api_key.encode() not in raw,
            "明文 Key 被写进了 DataStore",
        )

        # ---------------- 1.1 测试连接的降级行为
        #
        # 真实事故：探针图曾是 1×1 像素，而阿里云百炼要求「宽高均 > 10 像素、
        # 像素数 ≥ 4096」，于是「测试连接」对一个完全正确的配置报「连接失败」，
        # 而实际识别又完全正常。
        #
        # 修法是两层：探针图改成 256×256（= 65536 像素，满足最严的一档）；
        # 并且带图请求若被参数校验拒绝，自动改用纯文本重试 ——
        # 纯文本能通就报「连接正常（图片未验证）」而不是「连接失败」。
        print("\n[1.1] 测试连接的降级行为")
        set_mode("400params")
        reset_log()
        scroll_up(3)
        reqs: dict = {}
        if scroll_to("测试连接", exact=True):
            tap_text("测试连接", exact=True, timeout=8)
            hit = None
            deadline = time.time() + 40
            while time.time() < deadline:
                if "连接正常（图片未验证）" in screen_text():
                    hit = "连接正常（图片未验证）"
                    break
                scroll_down(1)
                time.sleep(0.9)
            check("参数被拒时报「连接正常（图片未验证）」而非「连接失败」", hit is not None)

            with urllib.request.urlopen(f"{MOCK}/requests", timeout=10) as resp:
                reqs = json.loads(resp.read().decode("utf-8"))
            check(
                "确实降级重试了（带图失败 → 纯文本成功，共 2 次请求）",
                reqs.get("count") == 2,
                f"实际 {reqs.get('count')} 次",
            )
        else:
            check("参数被拒时报「连接正常（图片未验证）」而非「连接失败」", False, "未找到测试连接按钮")

        # 探针图必须大于 1×1（1×1 的 base64 仅约 114 字节）
        if reqs.get("requests"):
            check(
                "探针图尺寸合规（不再是 1×1）",
                reqs["requests"][0].get("image_bytes", 0) > 300,
                f"实际 {reqs['requests'][0].get('image_bytes')} 字节",
            )

        set_mode("ok")

    # ---------------- 2. 走到结果页
    print("\n[2] 端到端识别")
    if not goto_result_page():
        check("走通「添加照片 → 识别」流程", False, "未能到达结果页")
        return 1
    check("走通「添加照片 → 识别」流程", True)

    log = mock_log()
    check("请求携带 3 张图片", log.get("image_count") == 3, f"实际 {log.get('image_count')}")
    check("携带 Authorization 头", bool(log.get("authorization_present")))
    check("模型名正确传递", log.get("model") == args.model, str(log.get("model")))

    prompt = log.get("text", "")
    check("prompt 要求「综合判断」", "综合" in prompt)
    check("prompt 含 JSON 结构说明", "latin_name" in prompt)
    check("prompt 说明 confidence 的语义", "把握程度" in prompt)

    text = screen_text()
    check("结果页显示中文名", "紫薇" in text)
    check("结果页显示科属", "千屈菜科" in text)
    check("结果页显示置信度", "模型置信度" in text)
    check("结果页带置信度语义声明", "不是经过科学验证的物种鉴定概率" in text)

    # ---------------- 3. 服务端错误
    print("\n[3] 服务端错误 → 中文提示")
    for mode, expect, label in [
        ("401", ["API Key 无效或已过期"], "API Key 无效 (401)"),
        ("404", ["服务地址不存在"], "路径不存在 (404)"),
        ("400model", ["模型名不正确"], "模型不存在 (400)"),
        ("400image", ["不接受图片输入"], "纯文本模型 (400)"),
        ("429", ["请求过于频繁"], "限流 (429)"),
        ("500", ["暂时不可用"], "服务端错误 (500)"),
    ]:
        check(f"{label}", retrigger(mode, expect))

    # ---------------- 4. 返回内容容错
    print("\n[4] 返回内容的容错解析")
    for mode, expect, label in [
        ("ok", ["紫薇"], "标准 JSON"),
        ("fenced", ["紫薇"], "```json 围栏"),
        ("chatty", ["紫薇"], "JSON 夹带解释文字"),
        ("camel", ["紫薇"], "camelCase 字段"),
        ("partial", ["紫薇"], "字段大量缺失"),
        ("notjson", ["结果已尽力提取"], "完全不是 JSON"),
    ]:
        check(f"{label}", retrigger(mode, expect, timeout=50))

    # 字段级验证
    print("\n[5] 容错解析的字段级验证")
    retrigger("camel", ["紫薇"], timeout=50)
    time.sleep(2)
    text = screen_text()
    check("camelCase 的 latinName 被识别", "Lagerstroemia" in text)
    check('字符串 "91%" 被解析为置信度', "91%" in text)
    check("字符串形式的 evidence 被拆条", "叶片形态" in text)

    # ---------------- 6. 日志红线
    print("\n[6] API Key 不入日志")
    shell("logcat", "-c")
    retrigger("ok", ["紫薇"], timeout=50)
    time.sleep(2)
    logcat = adb("logcat", "-d", timeout=90)
    check("Logcat 不含明文 API Key", args.api_key not in logcat)
    check("Logcat 不含 Authorization 头", f"Bearer {args.api_key}" not in logcat)

    # ---------------- 7. 崩溃
    print("\n[7] 稳定性")
    crashes = adb("logcat", "-d", timeout=90).count("FATAL EXCEPTION")
    check("全程无 FATAL 崩溃", crashes == 0, f"{crashes} 次")

    print("\n" + "=" * 70)
    print(f"通过 {passed} / 失败 {failed}")
    print("=" * 70)
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
