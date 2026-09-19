#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Phase 4 验收 · 驱动界面走完全部验收标准

覆盖四组（数据库层校验见 verify_phase4_db.py）：
  1. 主路径：文字分析成功 → 百科内容落库并展示
  A. 文字分析失败（模型返回非 JSON）→ 档案与识别结果不受影响
  B. 文字配置不可用（Key 为空）→ 基础识别结果仍然保存并可查看（验收标准 ②）
  C. 低置信度 → 显示补图提示（验收标准 ③）

## 用法

    # 1. 起模拟服务端（改了它的代码必须重启，见下方第 3 条）
    python tools/mock_ai_server.py --port 8899
    # 2. 设备侧反向转发
    adb -s <serial> reverse tcp:8899 tcp:8899
    # 3. 跑验收
    python tools/verify_phase4.py

连接串、截图目录等可用环境变量覆盖：ANDROID_SERIAL / ADB / MOCK_BASE / SHOT_DIR。

## 写这个脚本时踩出来的教训（都会造成「假通过」，比失败更危险）

1. **判据必须是独有的文案。**
   最初用「已保存」判断设置保存成功，而设置页上本来就有一句
   「API Key 已保存」——于是保存压根没发生时断言也全绿。
   Snackbar 的原文是「已保存。API Key 已加密存放在本机…」，
   取「已加密存放在本机」才唯一。

2. **前提条件要读硬数据，不要读界面文字推。**
   场景 B 要求「文字 Key 为空」，靠数屏幕上「Base URL」出现几次来判断
   是否已拆分配置并不可靠。现在直接拉 DataStore 文件，
   断言「有 text 字段、没有 text 密钥密文」。

3. **断言前先确认自己没在跟旧进程说话。**
   Python 进程在启动时载入源码，之后改文件不影响已运行的进程。
   曾出现 /health 报着 mode=lowconf、响应却是默认内容的情况。
   现在先取 /health 的 modes 清单，缺所需模式直接中止。

4. **`adb reverse` 会丢，要在跑之前检查。**
   映射丢失时服务端一切正常、设备却完全连不上，表现为识别失败，
   而断言只会说「流程未走通」。现在前置自检并在不通时自动重建。

5. **Compose 的 Switch 不是 Switch 控件类。**
   它是 `class="android.view.View"` 且 `checkable="true"`。
   按类名过滤永远找不到；改为认 checkable 属性，并直接读 `checked`
   判断状态（比数屏幕上的文字可靠）。

6. **断言要覆盖整页，不能只看当前视口。**
   结果页与详情页都很长，屏幕外的内容会被判成「不存在」。
   本项目的 Phase 2/3 都在这上面栽过，写法统一为
   「先回顶部，再逐屏向下扫到内容不再变化」。

7. **logcat 里的 FATAL 未必是本应用的。**
   `uiautomator` 并发调用会报 `UiAutomationService already registered`，
   被算进「应用崩溃」就会得出错误结论。现在只统计
   `Process: com.plantidentify` 的崩溃块。
"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

# 本机相关的路径都可用环境变量覆盖，避免把某台机器的布局写死在仓库里
# adb 路径：ADB -> ANDROID_HOME / local.properties 的 sdk.dir -> PATH。
# 全都没有时由 _env 打印可选做法并退出，不会拿一个不存在的路径硬跑。
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _env  # noqa: E402  —— tools/_env.py，统一解析本机环境

ADB = _env.require_adb_or_exit()
D = os.environ.get("ANDROID_SERIAL", "192.168.253.119:5555")
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

# 设置页保存成功的 Snackbar 文案里**独有的**片段。
# 取整句「已保存」会撞上状态卡里的「API Key 已保存」，造成假通过。
SAVE_OK_MARK = "已加密存放在本机"

passed = failed = 0


# ---------------------------------------------------------------- 基础

def adb(*a, timeout=60, binary=False):
    r = subprocess.run([ADB, "-s", D, *a], capture_output=True, timeout=timeout)
    return r.stdout if binary else r.stdout.decode("utf-8", errors="replace")


def shell(*a, timeout=60):
    return adb("shell", *a, timeout=timeout)


def dump():
    """取界面树。

    重试是必需的：并发或紧接着的 uiautomator 调用会撞上
    「UiAutomationService already registered」而整体失败，
    表现为 dump 返回空 —— 看起来像「页面上没有这段文字」。
    """
    for i in range(4):
        raw = adb("exec-out", "uiautomator", "dump", "/dev/tty", timeout=40)
        s, e = raw.find("<?xml"), raw.rfind("</hierarchy>")
        if s >= 0 and e > s:
            try:
                return ET.fromstring(raw[s: e + len("</hierarchy>")])
            except ET.ParseError:
                pass
        time.sleep(1.2)
    return None


def ntext(n):
    # 叶子节点的布尔值恒为 False，必须用 `is not None` 判断
    return n.get("text") or n.get("content-desc") or ""


def center(n):
    m = re.findall(r"-?\d+", n.get("bounds") or "")
    if len(m) != 4:
        return None
    return ((int(m[0]) + int(m[2])) // 2, (int(m[1]) + int(m[3])) // 2)


def find(text, exact=False):
    root = dump()
    if root is None:
        return None
    for n in root.iter("node"):
        v = ntext(n)
        if not v:
            continue
        if (v == text) if exact else (text in v):
            return n
    return None


def screen_text():
    root = dump()
    if root is None:
        return ""
    return "\n".join(ntext(n) for n in root.iter("node") if ntext(n))


def tap(text, exact=False, timeout=15):
    """点击。默认精确匹配 —— 模糊匹配会撞上段落说明文字。

    只查找一次并抓住节点：dump 是独立进程调用，可能偶发失败。
    先 find 判断存在、再 find 取坐标的写法会在两次调用之间踩空，
    然后 center(None) 直接抛异常中断整个脚本。
    """
    deadline = time.time() + timeout
    node = None
    while time.time() < deadline:
        node = find(text, exact=exact)
        if node is not None:
            break
        time.sleep(0.8)
    if node is None:
        return False

    # 等页面切换动画结束再取坐标：动画期间的 bounds 会过期
    time.sleep(1.2)
    node = find(text, exact=exact)
    if node is None:
        return False
    pos = center(node)
    if pos is None:
        return False
    shell("input", "tap", str(pos[0]), str(pos[1]))
    return True


def scroll_down(times=1):
    for _ in range(times):
        shell("input", "swipe", "960", "900", "960", "350", "400")
        time.sleep(1.2)


def scroll_top(times=6):
    for _ in range(times):
        shell("input", "swipe", "960", "400", "960", "900", "300")
        time.sleep(0.5)


def find_scrolling(text, scrolls=6):
    """从顶部逐屏向下找文字。

    结果页很长，任何「凭当前位置断言」的写法都会漏掉屏幕外的内容 ——
    这个坑在本项目已踩过多次。必须先回顶部再扫。
    """
    scroll_top(scrolls)
    for _ in range(scrolls):
        if text in screen_text():
            return True
        scroll_down()
    return text in screen_text()


def collect_all_text(max_scrolls=10):
    """回顶部后一路向下滚，累积整页文字。

    固定滚动次数不够用：详情页长度随百科内容变化，
    滚不够就会把页面底部的入口判成「不存在」。
    改为「滚到内容不再变化为止」。
    """
    scroll_top(max_scrolls)
    chunks = [screen_text()]
    for _ in range(max_scrolls):
        scroll_down()
        nxt = screen_text()
        chunks.append(nxt)
        # 连续两屏内容一致说明已经到底
        if len(chunks) >= 3 and chunks[-1] == chunks[-2]:
            break
    return "\n".join(chunks)


def wait_text(text, timeout=60):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if text in screen_text():
            return True
        time.sleep(1.5)
    return False


def check(label, ok, extra=""):
    global passed, failed
    if ok:
        passed += 1
        print(f"    [PASS] {label}")
    else:
        failed += 1
        print(f"    [FAIL] {label}" + (f"  <- {extra}" if extra else ""))


# ---------------------------------------------------------------- 模拟服务端

def mock_post(path):
    return urllib.request.urlopen(
        urllib.request.Request(f"{MOCK}{path}", method="POST"), timeout=10
    ).read()


def mock_get(path):
    return json.loads(
        urllib.request.urlopen(f"{MOCK}{path}", timeout=10).read().decode("utf-8")
    )


def set_mode(m):
    mock_post(f"/control?mode={m}")


def reset_log():
    mock_post("/reset")


def server_capabilities():
    return set(mock_get("/health").get("modes") or [])


def text_request_count():
    """已记录的文字分析请求数（不带图片的那些）"""
    data = mock_get("/requests")
    return sum(1 for r in data["requests"] if not r.get("image_count"))


# ---------------------------------------------------------------- 配置状态

def persisted_config():
    """拉取落盘的 AI 配置，返回 (原始字节, 解析出的字典或 None)

    直接读硬数据而不是推界面文字：界面只能说明「显示了什么」，
    不能说明「此刻真正生效的是什么」。
    """
    raw = adb("exec-out", "run-as", PKG, "cat",
              "files/datastore/ai_config.preferences_pb", binary=True, timeout=60)
    start = raw.find(b'{"vision"')
    if start < 0:
        return raw, None
    depth = 0
    for i in range(start, len(raw)):
        if raw[i:i + 1] == b"{":
            depth += 1
        elif raw[i:i + 1] == b"}":
            depth -= 1
            if depth == 0:
                try:
                    return raw, json.loads(raw[start: i + 1].decode("utf-8"))
                except Exception:
                    return raw, None
    return raw, None


def config_state():
    raw, cfg = persisted_config()
    return {
        "shared": bool(cfg) and "text" not in cfg,
        "has_text_section": bool(cfg) and "text" in cfg,
        "vision_key_cipher": b"vision_api_key_cipher" in raw,
        "text_key_cipher": b"text_api_key_cipher" in raw,
        "vision_model": (cfg or {}).get("vision", {}).get("model"),
        "text_model": ((cfg or {}).get("text") or {}).get("model"),
        "raw_has_plain_key": b"test-key-1234567890abcdef" in raw,
    }


# ---------------------------------------------------------------- 界面流程

def goto_add_plant():
    shell("am", "force-stop", PKG)
    time.sleep(1.5)
    start_app()
    time.sleep(5)
    if not tap("添加植物", exact=True, timeout=20):
        return False
    time.sleep(3)
    return True


def ensure_photos(count=3):
    if not goto_add_plant():
        return False
    m = re.search(r"已添加 (\d)/5", screen_text())
    have = int(m.group(1)) if m else 0
    if have >= count:
        print(f"    草稿已有 {have} 张")
        return True

    need = count - have
    if not tap("从相册选择", exact=True, timeout=15):
        return False
    time.sleep(5)
    for i in range(need):
        shell("input", "tap", str(104 + i * 213), "491")
        time.sleep(1.0)
    time.sleep(1.5)
    if not tap("添加（", timeout=15):
        return False
    time.sleep(6)
    return True


def open_settings():
    shell("am", "force-stop", PKG)
    time.sleep(1.5)
    start_app()
    time.sleep(5)
    if not tap("设置", exact=True, timeout=20):
        return False
    time.sleep(3)
    return find("视觉识别服务") is not None


def _switch_node(label):
    """定位某一行右侧的开关控件。

    两个坑：
    1. 按标签文字点击会落在左侧说明文字上，必须点开关本身。
    2. **Compose 的 Switch 在 uiautomator 里不是 Switch / CheckBox 类**，
       而是 `class="android.view.View"` 且 `checkable="true"`。
       按控件类名过滤会一无所获 —— 这里改为认 checkable 属性，
       再按 y 坐标与标签行对齐来选中正确的那个
       （拆分后同一屏会有两个开关：共用的那个与 API Key 显示开关）。
    """
    root = dump()
    if root is None:
        return None
    label_y = None
    for n in root.iter("node"):
        if ntext(n).strip() == label:
            m = re.findall(r"-?\d+", n.get("bounds") or "")
            if len(m) == 4:
                label_y = (int(m[1]) + int(m[3])) // 2
                break
    if label_y is None:
        return None

    for n in root.iter("node"):
        if n.get("checkable") != "true":
            continue
        m = re.findall(r"-?\d+", n.get("bounds") or "")
        if len(m) != 4:
            continue
        cy = (int(m[1]) + int(m[3])) // 2
        if abs(cy - label_y) <= 45:
            return n
    return None


def switch_checked(label):
    """开关当前状态（True/False），取不到返回 None

    直接读控件的 checked 属性 —— 比数屏幕上出现了几个「Base URL」
    可靠得多：那个做法会被滚动位置影响，拆分后两张配置卡也不会同屏。
    """
    n = _switch_node(label)
    if n is None:
        return None
    return n.get("checked") == "true"


def tap_switch(label):
    n = _switch_node(label)
    if n is None:
        return False
    pos = center(n)
    if pos is None:
        return False
    before = n.get("checked")
    shell("input", "tap", str(pos[0]), str(pos[1]))
    # 等状态真的翻转再返回，避免后续断言读到旧值
    for _ in range(6):
        time.sleep(0.6)
        now = switch_checked(label)
        if now is not None and str(now).lower() != before:
            return True
    return False


def set_share(shared: bool, tries=3):
    """把「使用同一个 AI 服务」调成指定状态"""
    scroll_top(6)
    for _ in range(8):
        if _switch_node("使用同一个 AI 服务") is not None:
            break
        scroll_down()
    for _ in range(tries):
        cur = switch_checked("使用同一个 AI 服务")
        if cur is None:
            return False
        if cur == shared:
            return True
        tap_switch("使用同一个 AI 服务")
    return switch_checked("使用同一个 AI 服务") == shared


def save_settings():
    """滚到保存按钮 → 点击 → 等独有的成功文案"""
    scroll_top(6)
    for _ in range(8):
        if find("保存配置", exact=True) is not None:
            break
        scroll_down()
    if not tap("保存配置", exact=True, timeout=10):
        return False
    # 点击后立刻开始等：Snackbar 只显示几秒，先 sleep 再查会错过
    return wait_text(SAVE_OK_MARK, timeout=20)


def shot(name):
    raw = adb("exec-out", "screencap", "-p", binary=True)
    with open(f"{OUT_DIR}/{name}.png", "wb") as f:
        f.write(raw)
    print(f"    截图 -> {name}.png")


def run_recognition_to_result():
    """从添加页走到识别结果页"""
    if not ensure_photos(3):
        print("    ❌ 照片准备失败")
        return False
    scroll_top(4)
    for _ in range(8):
        if find("开始识别", exact=True) is not None:
            break
        scroll_down()
    if not tap("开始识别", exact=True, timeout=10):
        print("    ❌ 找不到开始识别")
        return False

    if wait_text("模型置信度", timeout=120):
        return True

    # 失败时把当前屏幕打出来：识别不通过的原因五花八门
    # （连不上服务端、配置缺失、照片读不到、模型拒绝…），
    # 只报一句「流程未走通」等于把排查成本甩给下一个人
    print("    ❌ 识别未完成，当前屏幕：")
    for line in screen_text().splitlines()[:16]:
        print(f"       {line[:88]}")
    return False


# ---------------------------------------------------------------- 前置检查

print("=" * 70)
print("Phase 4 验收 · 边界场景")
print("=" * 70)

print("\n[前置] 模拟服务端能力检查")
caps = server_capabilities()
need = {"lowconf", "analysisBad", "ok", "params"}
missing = need - caps
check(f"服务端支持所需模式（{len(caps)} 种已注册）", not missing, f"缺少 {missing}")
if missing:
    print("\n⚠ 服务端进程是旧代码。请重启 tools/mock_ai_server.py 后重跑。")
    sys.exit(1)


def device_reaches_mock():
    """设备侧能否访问到模拟服务端

    只看本机的 /health 是不够的：adb reverse 映射会随 adb 重启、
    设备重连而丢失，此时服务端一切正常、设备却完全连不上。
    表现为识别卡在连接失败，而断言只会说「识别没完成」，
    排查要绕一大圈。
    """
    out = shell("curl", "-s", "--max-time", "6", "http://127.0.0.1:8899/health",
                timeout=30)
    return '"ok"' in out


print("\n[前置] 设备到模拟服务端的连通性")
if not device_reaches_mock():
    print("    不通，重建 adb reverse 映射…")
    adb("reverse", "tcp:8899", "tcp:8899", timeout=30)
    time.sleep(1.5)
check("设备可访问模拟服务端（adb reverse 就绪）", device_reaches_mock())
if not device_reaches_mock():
    print("\n⚠ 设备仍无法访问模拟服务端，中止。检查 adb 连接与反向转发后重跑。")
    sys.exit(1)

# 每轮开始都清一次日志，后面的请求计数才有意义
shell("logcat", "-c")
reset_log()


# ============================================================ [0]
print("\n[0] 把配置恢复成「视觉与文字共用同一服务」—— 这是场景 A 的前提")
if not open_settings():
    check("进入设置页", False)
    sys.exit(1)

state = config_state()
print(f"    当前落盘配置：共用={state['shared']} 有文字段={state['has_text_section']} "
      f"文字侧有密钥={state['text_key_cipher']}")

# 先读开关的实际状态，再决定要不要拨 —— 不靠猜
print(f"    界面开关 checked={switch_checked('使用同一个 AI 服务')}（共用应为 True）")
check("把共用开关拨到「共用」", set_share(True))

check("保存配置成功", save_settings())

state = config_state()
check("落盘确认为共用（无 text 字段）", state["shared"],
      f"shared={state['shared']} has_text={state['has_text_section']}")
check("视觉侧密钥密文存在", state["vision_key_cipher"])
check("配置未出现明文 Key（加密生效）", not state["raw_has_plain_key"])


# ============================================================ [1]
print("\n[1] 主路径：文字分析成功 → 百科内容落库并展示")
set_mode("ok")
reset_log()
before_text = text_request_count()

if run_recognition_to_result():
    check("识别完成", True)

    if tap("保存到档案", exact=True, timeout=15):
        check("保存成功", wait_text("已保存到植物档案", timeout=180))

        after_text = text_request_count()
        check("发出了文字分析请求", after_text > before_text,
              f"文字请求 {before_text} -> {after_text}")

        text = collect_all_text()
        check("未出现「百科暂缺」提示（分析成功）", "暂缺" not in text, text[-200:])

        scroll_top(6)
        for _ in range(8):
            if find("查看植物档案", exact=True) is not None:
                break
            scroll_down()
        if tap("查看植物档案", exact=True, timeout=20):
            wait_text("植物百科", timeout=40)
            detail = collect_all_text()
            for label in ("植物简介", "形态特征", "生长习性", "花期", "养护建议"):
                check(f"详情页显示「{label}」", label in detail)
            check("详情页含免责声明", "不作为专业鉴定依据" in detail)
            check("详情页未出现「生成失败」", "暂时生成失败" not in detail)
            shot("p4-detail-with-analysis")
        else:
            check("能进入详情页", False)
    else:
        check("保存成功", False, "找不到保存按钮")
else:
    check("识别完成", False, "流程未走通")


# ============================================================ [A]
print("\n[A] 文字分析失败（模型返回非 JSON）→ 档案与识别结果不受影响")
set_mode("ok")
reset_log()
before_text = text_request_count()

if run_recognition_to_result():
    check("识别完成", True)

    # 切到「文字分析返回非 JSON」再保存：
    # 识别已经拿到结果，此时切换只影响随后的文字分析请求
    set_mode("analysisBad")
    check("找到「保存到档案」", tap("保存到档案", exact=True, timeout=15))
    check("提示已保存", wait_text("已保存到植物档案", timeout=150))

    after_text = text_request_count()
    check("确实发出了文字分析请求（走的是失败路径而非「未配置」）",
          after_text > before_text, f"文字请求 {before_text} -> {after_text}")

    text = screen_text()
    check("提示植物百科暂缺", "暂缺" in text, text[:160])
    check("明确说明识别结果不受影响", "不受影响" in text, text[:160])
    shot("p4-analysis-failed")

    # 「查看植物档案」在结果页底部，先滚到它再点
    scroll_top(6)
    for _ in range(8):
        if find("查看植物档案", exact=True) is not None:
            break
        scroll_down()
    if tap("查看植物档案", exact=True, timeout=20):
        wait_text("植物百科", timeout=30)
        detail = collect_all_text()
        check("详情页仍显示植物名称", "紫薇" in detail)
        check("详情页仍显示科属", "千屈菜科" in detail)
        check("详情页仍显示置信度", "模型置信度" in detail)
        check("详情页仍有照片", "照片" in detail)
        check("百科区块说明「暂时生成失败」", "暂时生成失败" in detail,
              detail[-200:] if "暂时生成失败" not in detail else "")
        check("百科区块声明不影响识别结果",
              "都已完整保存" in detail or "不影响" in detail)
        # 按钮文案随内容是否存在变化：有内容时「重新生成」，
        # 无内容时「生成植物百科」（见 PlantDetailScreen 的 OutlinedButton）
        check("提供重新生成 / 生成入口",
              "重新生成" in detail or "生成植物百科" in detail)
        shot("p4-detail-noanalysis")
    else:
        check("能进入详情页", False)
else:
    check("识别完成", False, "流程未走通")


# ============================================================ [B]
print("\n[B] 文字配置不可用（Key 为空）→ 基础结果仍保存（验收标准 ②）")
set_mode("ok")

if open_settings():
    check("进入设置页", True)

    # 关掉共用 → 文字侧独立。展开后文字侧带入预设默认值
    # （有 Base URL 与模型名、没有 Key），不填 Key 直接保存 ——
    # 这正是验收标准 ② 要模拟的状态
    check("关闭共用开关（文字侧改为独立配置）", set_share(False))
    print(f"    开关 checked={switch_checked('使用同一个 AI 服务')}（独立应为 False）")
    check("保存配置成功（文字 Key 为空不阻止保存）", save_settings())

    state = config_state()
    check("落盘确认已拆分（有 text 字段）", state["has_text_section"],
          f"has_text={state['has_text_section']}")
    check("文字侧确无密钥密文（Key 为空）", not state["text_key_cipher"])
    check("文字侧 Base URL / 模型名有值", bool(state["text_model"]),
          f"text_model={state['text_model']}")

    before_text = text_request_count()
    if run_recognition_to_result():
        check("识别完成（视觉通道不受文字配置影响）", True)

        if tap("保存到档案", exact=True, timeout=15):
            check("保存成功", wait_text("已保存到植物档案", timeout=150))

            after_text = text_request_count()
            check("未发出文字分析请求（配置不可用时不该硬发）",
                  after_text == before_text, f"{before_text} -> {after_text}")

            # 必须采全页文字：结果页的说明段落渲染在下方，
            # 只看当前视口会把「屏幕外」误判成「没写」
            text = collect_all_text()
            check("提示文字分析配置不完整",
                  "配置不完整" in text or "尚未配置" in text, text[-300:])
            check("指出缺的是 API Key", "API Key" in text, text[-300:])
            check("明确说明识别结果已完整保存", "不受影响" in text, text[-300:])
            shot("p4-no-text-config")

            scroll_top(6)
            for _ in range(8):
                if find("查看植物档案", exact=True) is not None:
                    break
                scroll_down()
            if tap("查看植物档案", exact=True, timeout=20):
                wait_text("植物百科", timeout=30)
                detail = collect_all_text()
                check("详情页仍显示植物名称", "紫薇" in detail)
                check("详情页仍显示科属", "千屈菜科" in detail)
                check("详情页仍有照片", "照片" in detail)
                check("百科区块提示尚未生成",
                      "尚未生成植物百科" in detail or "生成植物百科" in detail)
                check("提供生成入口", "生成植物百科" in detail)
            else:
                check("能进入详情页", False)
        else:
            check("保存成功", False, "找不到保存按钮")
    else:
        check("识别完成（视觉通道不受文字配置影响）", False, "流程未走通")

    # 收尾：恢复共用配置，方便后续场景与手工验证
    if open_settings():
        check("已恢复为共用配置（收尾）",
              set_share(True) and save_settings())
        st = config_state()
        check("收尾后落盘为共用", st["shared"], f"has_text={st['has_text_section']}")
else:
    check("进入设置页", False)


# ============================================================ [C]
print("\n[C] 低置信度 → 补图提示（验收标准 ③）")
set_mode("lowconf")

if run_recognition_to_result():
    check("识别完成", True)

    # 卡片渲染在「置信度」与「判定依据」之间，必须逐屏扫
    check("显示「识别可信度较低」", find_scrolling("识别可信度较低", scrolls=8))

    text = collect_all_text()
    check("显示建议补充的部位", "建议补充" in text, text[-300:])
    check("列出了具体缺失部位", "花部特征" in text or "完整株型" in text)
    check("提供「添加更多照片」入口", "添加更多照片" in text)
    check("仍提供保存入口（低置信度不阻止保存）",
          "保存到档案" in text or "仍然保存" in text)
    shot("p4-low-confidence")
else:
    check("显示「识别可信度较低」", False, "流程未走通")


# ============================================================ 汇总
print("\n[汇总]")
set_mode("ok")

# 只统计本应用进程的崩溃。
# logcat 里出现的 FATAL EXCEPTION 有可能来自 uiautomator 辅助进程
# （并发 dump 时会报 UiAutomationService already registered），
# 把工具进程的崩溃算到应用头上会得出错误结论。
raw = adb("logcat", "-d", timeout=120)
lines = raw.splitlines()
blocks = ["\n".join(lines[i: i + 6]) for i, l in enumerate(lines) if "FATAL EXCEPTION" in l]
app_blocks = [b for b in blocks if f"Process: {PKG}" in b]
check("本应用无 FATAL 崩溃", not app_blocks,
      f"{len(app_blocks)} 次；logcat 内其他进程崩溃 {len(blocks) - len(app_blocks)} 次（工具进程，不计）")

print("\n" + "=" * 70)
print(f"通过 {passed} / 失败 {failed}")
print("=" * 70)
sys.exit(0 if failed == 0 else 1)
