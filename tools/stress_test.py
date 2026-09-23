"""压测驱动（Phase 4）。

配合 app 内置的「压测工具（调试）」入口使用：脚本负责点按钮、读结果、
采系统指标，并把一档的完整结果写进 build/stress-report-<N>.txt。

为什么造数据走 app 内置入口而不是脚本拼 SQL —— 见
`StressDataSeeder` 的类注释：手拼 SQL 的数据形状（外键 / 路径 / 时间戳）
只要有一处与真实不一致，压出来的「慢」就可能是假数据导致的。

用法：
    python tools/stress_test.py run 100       # 一档：清空 → 造 100 → 指标 → 报告
    python tools/stress_test.py run 1000
    python tools/stress_test.py run 10000 --clean-timeout 900

前置：
    * 设备已连接（ADB_DEVICE 环境变量可指定，否则取 adb devices 第一个）
    * full 的 debug 包已安装（app-full-debug.apk）—— 压测用完整版，
      因为统计页 / 清洗中心都是完整版专属

注意：
    * 这一脚本会**清空** com.plantidentify.full 的全部数据。
      设备上基础版（com.plantidentify）的真实档案不受影响。
    * 10000 档的「全库深度检查」可能要跑几分钟；超时会如实记进报告，
      「超时」本身就是压测结论的一部分，不要为了出数字而放宽到没意义。
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _env  # noqa: E402  —— tools/_env.py，统一解析本机环境

sys.stdout.reconfigure(encoding="utf-8")

PACKAGE = "com.plantidentify.full"
MAIN_ACTIVITY = "com.plantidentify.full/com.plantidentify.MainActivity"
DB_PATH = "databases/plant_identify.db"
ADB = _env.require_adb_or_exit()


def pick_device() -> str:
    wanted = (os.environ.get("ADB_DEVICE") or "").strip()
    out = subprocess.run([ADB, "devices"], capture_output=True, timeout=30).stdout.decode()
    devices = [
        line.split()[0]
        for line in out.splitlines()
        if line.strip() and not line.startswith("List of") and line.split()[1] == "device"
    ]
    if not devices:
        sys.exit("!! 没有已连接的设备（adb devices 为空）")
    if wanted:
        if wanted not in devices:
            sys.exit(f"!! 指定的设备 {wanted} 不在线；在线的有 {devices}")
        return wanted
    return devices[0]


D = pick_device()


def shell(*a, timeout=60):
    """普通 adb shell。

    ⚠️ 参数会被 adb 拼成一条命令、**再由设备端的 sh 解析一遍**。
    所以凡是要传 SQL 的地方都不能用它 —— 见 exec_out()。
    """
    return subprocess.run(
        [ADB, "-s", D, "shell", *a], capture_output=True, timeout=timeout,
    ).stdout.decode("utf-8", errors="replace")


def exec_out(*a, timeout=60):
    """不经设备端 shell 二次解析地执行。**查库的唯一正确姿势。**

    用 `shell` 传 SQL 时，`COUNT(*)` 里的 `(` `)` 会被设备端 sh 当成元字符：
    实测 `adb shell run-as <pkg> sqlite3 <db> "SELECT COUNT(*) ..."`
    直接报 `syntax error: unexpected '('`（连 `SELECT 42;` 都报 incomplete
    input）。stdout 是空串 → `.strip()` → `int("")` 抛 ValueError → 调用方
    按 0 处理 —— **全程不报错**。

    后果极具误导性：造数据其实成功了，脚本却判定「库里 0 株、未完成」，
    于是清洗游标也恒为 0（深度检查永远超时）。三档连续「失败」看起来
    像应用的问题，实际是这一行。排查时先用 exec-out 手工查一次库，
    永远比读报告快。
    """
    return subprocess.run(
        [ADB, "-s", D, "exec-out", *a], capture_output=True, timeout=timeout,
    ).stdout.decode("utf-8", errors="replace")


def query_scalar(sql: str) -> str:
    """查库（debug 包可 run-as）。失败返回空串，调用方按 0 处理。"""
    return exec_out(
        "run-as", PACKAGE, "sqlite3", DB_PATH, sql, timeout=30,
    ).strip()


def screen_size() -> tuple[int, int]:
    """物理分辨率（宽, 高）。坐标全按它算 —— 写死的话换台设备就全点歪。"""
    out = shell("wm", "size", timeout=30)
    m = re.search(r"(\d+)x(\d+)", out)
    if not m:
        sys.exit(f"!! 无法从 wm size 解析分辨率：{out!r}")
    return int(m.group(1)), int(m.group(2))


W, H = (1920, 1080)  # main() 里会按真实分辨率覆盖

# ---------------- UI 驱动（与 verify_phase6.py 同一套经验） ----------------


def dump():
    """取界面树。重试是必需的：连续的 uiautomator 调用会互相撞注册。"""
    for _ in range(4):
        raw = subprocess.run(
            [ADB, "-s", D, "exec-out", "uiautomator", "dump", "/dev/tty"],
            capture_output=True, timeout=40,
        ).stdout.decode("utf-8", errors="replace")
        s, e = raw.find("<?xml"), raw.rfind("</hierarchy>")
        if s >= 0 and e > s:
            try:
                return ET.fromstring(raw[s: e + len("</hierarchy>")])
            except ET.ParseError:
                pass
        time.sleep(1.2)
    return None


def ntext(n) -> str:
    return n.get("text") or n.get("content-desc") or ""


def bounds(n):
    m = re.findall(r"-?\d+", n.get("bounds") or "")
    if len(m) != 4:
        return None
    return tuple(int(x) for x in m)


def center(n):
    b = bounds(n)
    if b is None:
        return None
    return ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)


# 「点得到」的安全区。中心点必须落在这条带子里 —— 0~1 会把屏幕最底边
# 也算进去，而那里是系统导航条，点击会被系统吃掉。
SAFE_TOP_RATIO = 0.05
SAFE_BOTTOM_RATIO = 0.95


def find(text, exact=False, visible=True, lo=None, hi=None):
    """找文本节点。

    `visible=True` 要求**中心点在安全区内**，而不是「顶端还在屏内」：
    实测压测页「清空全部数据」的 bounds 是 [102,1051,249,1080]，顶端
    1051 看着还在 1080 里，但中心 y=1065 压在最底边 —— 坐标落进系统
    导航条，点击被吃掉，脚本却以为点到了。判据必须看**中心点**。
    """
    top = int(H * SAFE_TOP_RATIO) if lo is None else lo
    bottom = int(H * SAFE_BOTTOM_RATIO) if hi is None else hi
    root = dump()
    if root is None:
        return None
    for n in root.iter("node"):
        v = ntext(n)
        if not v:
            continue
        if not ((v == text) if exact else (text in v)):
            continue
        b = bounds(n)
        if b is None:
            continue
        if visible and not (top <= (b[1] + b[3]) // 2 <= bottom):
            continue
        return n
    return None


def _clickable_center(text, exact=False, visible=True):
    """沿父链找到包住该文本的**最近可点节点**，返回它的中心坐标。

    为什么要往上找：Compose 里文字自己几乎都不可点，可点的是外层
    Card / Button。点文字中心多数时候等价，但当文字位于卡片边沿
    （首页统计卡里的「查看统计详情 ›」就在卡片最下沿）时，坐标会落到
    卡片之外 —— 点击毫无反应，而且不会有任何报错。
    """
    top = int(H * SAFE_TOP_RATIO)
    bottom = int(H * SAFE_BOTTOM_RATIO)
    root = dump()
    if root is None:
        return None
    parent = {c: p for p in root.iter() for c in p}
    for n in root.iter("node"):
        v = ntext(n)
        if not v or not ((v == text) if exact else (text in v)):
            continue
        b = bounds(n)
        if b is None:
            continue
        if visible and not (top <= (b[1] + b[3]) // 2 <= bottom):
            continue
        node = n
        while node is not None:
            if node.get("clickable") == "true":
                pos = center(node)
                if pos is not None and top <= pos[1] <= bottom:
                    return pos
                break
            node = parent.get(node)
        return center(n)
    return None


def tap(text, exact=False, timeout=15, lo=0, hi=None) -> bool:
    """点击。点击前**重新 dump** —— 动画期间的 bounds 会过期。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        node = find(text, exact=exact, lo=lo, hi=hi)
        if node is not None:
            time.sleep(1.0)
            node = find(text, exact=exact, lo=lo, hi=hi)
            if node is None:
                continue
            pos = center(node)
            if pos is None:
                continue
            shell("input", "tap", str(pos[0]), str(pos[1]))
            time.sleep(1.0)
            return True
        time.sleep(0.8)
    return False


def tap_card(text, exact=False, timeout=15) -> bool:
    """点「包含该文本的可点卡片」的中心（而不是文字本身）。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        pos = _clickable_center(text, exact=exact)
        if pos is not None:
            time.sleep(1.0)
            pos = _clickable_center(text, exact=exact)
            if pos is None:
                continue
            shell("input", "tap", str(pos[0]), str(pos[1]))
            time.sleep(1.0)
            return True
        time.sleep(0.8)
    return False


def ensure_visible(text, exact=False, tries=8) -> bool:
    """滚动直到目标进入安全区 —— 给页面底部的按钮用（如「清空全部数据」）。"""
    for _ in range(tries):
        if find(text, exact=exact) is not None:
            return True
        scroll_down()
    return find(text, exact=exact) is not None


def find_edit_text():
    """定位输入框（搜索页顶部那个）。

    别用固定坐标点它：搜索页的输入框在 y≈179~293，而「标题栏」在
    y≈56~140 —— 按 H*0.09 算出来的点是打在标题上的，输入永远不生效。
    """
    top, bottom = int(H * SAFE_TOP_RATIO), int(H * SAFE_BOTTOM_RATIO)
    root = dump()
    if root is None:
        return None
    for n in root.iter("node"):
        if "EditText" not in (n.get("class") or ""):
            continue
        b = bounds(n)
        if b is not None and top <= (b[1] + b[3]) // 2 <= bottom:
            return n
    return None


def tap_at(x: int, y: int):
    shell("input", "tap", str(x), str(y))
    time.sleep(1.5)


def scroll_down(times=1):
    x, y1, y2 = W // 2, int(H * 0.83), int(H * 0.32)
    for _ in range(times):
        shell("input", "swipe", str(x), str(y1), str(x), str(y2), "400")
        time.sleep(1.2)


def scroll_top(times=8):
    x, y1, y2 = W // 2, int(H * 0.37), int(H * 0.93)
    for _ in range(times):
        shell("input", "swipe", str(x), str(y1), str(x), str(y2), "200")
        time.sleep(0.6)


def wait_text(text, timeout, exact=False) -> float | None:
    """轮询等待文本出现，返回耗时秒；超时返回 None。

    这里**不要求可见**（`visible=False`）：它只用来判断「到没到那一页」，
    元素贴着屏幕边沿也算到了。要求可见会把「页面切换完成」误判成超时。
    """
    start = time.time()
    while time.time() - start < timeout:
        if find(text, exact=exact, visible=False) is not None:
            return time.time() - start
        time.sleep(0.5)
    return None


def goto_home(timeout=40) -> bool:
    """回首页。

    只能靠冷启动：首页和压测页是**同一个 Activity 的不同 Compose 路由**，
    am start 对「停在压测页」的 app 只是把任务置前，路由不会动 ——
    于是后面的每一步都在错误的页面上找元素（实测整个档位的指标全废）。
    冷启动每次多花约 3 秒，换来的是每个指标的起点都一致。
    """
    shell("am", "force-stop", PACKAGE)
    time.sleep(1.5)
    shell("am", "start", "-n", MAIN_ACTIVITY, timeout=60)
    return wait_text("添加植物", timeout) is not None


def wait_enabled(text, timeout=60) -> bool:
    """等按钮从 disabled 恢复。

    压测页的清空 / 造数据走同一个 VM 的 busy 标志：清空完成后还要
    refreshCounts（扫图片目录算字节数）busy 才复位，期间点「造 N 株」
    会被 `if (busy) return` **静默丢弃** —— 不报错、不生效，库永远 0。
    uiautomator 树里按钮带 enabled 属性，等它变回 true 才是安全的时机。
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        node = find(text, exact=True)
        if node is not None and node.get("enabled") == "true":
            return True
        time.sleep(1.5)
    return False


def back(times=1):
    for _ in range(times):
        shell("input", "keyevent", "4")
        time.sleep(1.5)


# ---------------- 指标采集 ----------------


def cold_start() -> dict:
    """冷启动：force-stop 后 am start -W。

    TotalTime 是「Activity 启动完成」，不完全等于「可交互」；
    另用「首页元素出现」作为可交互的近似口径，两个都记。
    """
    shell("am", "force-stop", PACKAGE)
    time.sleep(1.5)
    out = shell("am", "start", "-W", "-n", MAIN_ACTIVITY, timeout=60)
    total = re.search(r"TotalTime: (\d+)", out)
    wait_for = wait_text("添加植物", 30)
    return {
        "am_start_total_ms": int(total.group(1)) if total else None,
        "home_interactive_s": round(wait_for, 2) if wait_for is not None else None,
    }


def meminfo_mb() -> float | None:
    out = shell("dumpsys", "meminfo", PACKAGE, timeout=60)
    m = re.search(r"TOTAL PSS:\s+(\d+)", out)
    return round(int(m.group(1)) / 1024, 1) if m else None


def image_dir_kb() -> int | None:
    out = shell("run-as", PACKAGE, "du", "-sk", "files/images", timeout=60)
    m = re.search(r"(\d+)\s+files/images", out)
    return int(m.group(1)) if m else None


def scroll_jank_percent(swipes: int = 8) -> dict:
    """滚动帧率：reset 计数 → 连续滑动 → 读 gfxinfo 汇总。"""
    shell("dumpsys", "gfxinfo", PACKAGE, "reset", timeout=60)
    time.sleep(1.0)
    x, y1, y2 = W // 2, int(H * 0.88), int(H * 0.28)
    for _ in range(swipes):
        shell("input", "swipe", str(x), str(y1), str(x), str(y2), "300")
        time.sleep(1.4)
    out = shell("dumpsys", "gfxinfo", PACKAGE, timeout=60)
    total = re.search(r"Total frames rendered: (\d+)", out)
    janky = re.search(r"Janky frames: (\d+)", out)
    pctl = re.search(r"90th percentile: (\d+)ms", out)
    return {
        "frames": int(total.group(1)) if total else None,
        "janky": int(janky.group(1)) if janky else None,
        "janky_percent": (
            round(int(janky.group(1)) * 100.0 / int(total.group(1)), 1)
            if total and janky and int(total.group(1)) > 0 else None
        ),
        "p90_frame_ms": int(pctl.group(1)) if pctl else None,
    }


# ---------------- 压测页操作 ----------------


def query_plants() -> int:
    v = query_scalar("SELECT COUNT(*) FROM plant_record;")
    try:
        return int(v)
    except ValueError:
        return 0


def open_stress_page() -> bool:
    """从任何状态：回首页 → 设置 → 压测工具。"""
    if not goto_home():
        return False
    if not tap("设置", exact=True, timeout=20):
        return False
    scroll_top()
    if not ensure_visible("压测工具（调试）", exact=True, tries=14):
        return False
    time.sleep(0.8)
    if not tap("压测工具（调试）", exact=True, timeout=6):
        return False
    return wait_text("造数据", 15) is not None


def clear_all(count: int) -> bool:
    # 「清空全部数据」在页面最底部：它的 bounds 是 [102,1051,249,1080]，
    # 中心点在导航条上，直接点等于没点。先滚到安全区。
    if not ensure_visible("清空全部数据", exact=True):
        return False
    if not tap("清空全部数据", exact=True, timeout=15):
        return False
    if not tap("清空", exact=True, timeout=10):
        return False
    # 完成判定也用库（行数归零）：日志区在屏幕底部，轮询 UI 看不到
    deadline = time.time() + 180
    while time.time() < deadline:
        if query_plants() == 0:
            # 行没了 ≠ 能立刻造数据：VM 还在 refreshCounts（扫目录算字节），
            # busy 复位前点「造 N 株」会被静默丢弃。等按钮恢复 enabled
            wait_enabled(f"{count} 株")
            return True
        time.sleep(2.0)
    return False


def seed_plants(count: int) -> dict:
    """点「N 株」并等完成。

    完成判定用**库里的行数** —— 压测页的日志区在 LazyColumn 最底下，
    不滚到底就 dump 不到，而「造完」的精确耗时恰好写在那一行里。
    所以：库行数判断完成，滚到底读日志拿 app 内计的耗时，墙钟兜底。
    """
    label = f"{count} 株"
    if not ensure_visible(label, exact=True):
        return {"ok": False, "error": f"找不到「{label}」按钮"}
    started = time.time()
    if not tap(label, exact=True, timeout=10):
        return {"ok": False, "error": "找不到按钮"}

    deadline = time.time() + max(180, count * 1.2)
    while time.time() < deadline:
        if query_plants() >= count:
            wall = round(time.time() - started, 1)
            # 滚到底读 app 内计的耗时（更准：不含脚本轮询的开销）
            scroll_down(8)
            root = dump()
            texts = [ntext(n) for n in root.iter("node")] if root is not None else []
            summary = next((t for t in texts if t.startswith("✓ 造完")), "")
            m = re.search(r"耗时 ([\d.]+) 秒", summary)
            return {
                "ok": True,
                "log_elapsed_s": float(m.group(1)) if m else None,
                "wall_elapsed_s": wall,
                "summary": summary,
            }
        time.sleep(2.5)
    return {"ok": False, "error": f"{count} 株在 {time.time() - started:.0f}s 内未完成"}


# ---------------- 各页面的响应计时 ----------------


def time_home_search() -> dict:
    """搜索响应：进搜索页（全量渲染）→ 输入关键词 → 结果集变小。

    结果计数的文本是「找到 N 株植物」；空关键词会显示全部，
    所以「数字变小」才是过滤完成的可靠信号。
    """
    if not goto_home():
        return {"ok": False, "error": "回不了首页"}
    started = time.time()
    if not tap("搜索植物", exact=True, timeout=20):
        return {"ok": False, "error": "找不到搜索入口"}
    full_shown = None
    deadline = time.time() + 30
    while time.time() < deadline:
        root = dump()
        texts = [ntext(n) for n in root.iter("node")] if root is not None else []
        m = re.search(r"找到 (\d+) 株植物", "\n".join(texts))
        if m:
            full_shown = int(m.group(1))
            break
        time.sleep(1.0)
    open_s = round(time.time() - started, 2)
    if full_shown is None:
        back()
        return {"ok": False, "error": "搜索页没出现结果计数"}

    # 点输入框本身（EditText 节点的中心），不要按 H*0.09 猜坐标 ——
    # 那是标题栏的位置，输入框在 y≈179~293。
    edit = find_edit_text()
    if edit is None:
        back()
        return {"ok": False, "error": "找不到搜索输入框"}
    time.sleep(1.0)
    pos = center(edit)
    shell("input", "tap", str(pos[0]), str(pos[1]))
    time.sleep(1.5)
    filter_started = time.time()
    shell("input", "text", "9")
    filtered = None
    deadline = time.time() + 30
    while time.time() < deadline:
        root = dump()
        texts = [ntext(n) for n in root.iter("node")] if root is not None else []
        m = re.search(r"找到 (\d+) 株植物", "\n".join(texts))
        if m and int(m.group(1)) != full_shown:
            filtered = round(time.time() - filter_started, 2)
            break
        time.sleep(1.0)
    back()
    time.sleep(1.0)
    return {
        "ok": filtered is not None,
        "open_s": open_s,
        "full_count": full_shown,
        "filter_s": filtered,
    }


def time_stats_page() -> dict:
    """统计详情页：入口是首页统计卡（点卡内任意文字，标题「植物统计」）。

    用 `tap_card` 而不是 `tap`：「不同植物」只是卡里的一行标签，它自己
    不可点；实测点卡内的「查看统计详情 ›」（卡片最下沿那行）时坐标会落到
    卡片之外 —— 没有任何反应。点可点祖先的中心才是稳的。
    """
    if not goto_home():
        return {"ok": False, "error": "回不了首页"}
    if not tap_card("不同植物", exact=True, timeout=20):
        return {"ok": False, "error": "找不到统计卡"}
    shown = wait_text("植物统计", 25)
    back()
    time.sleep(1.0)
    return {
        "ok": shown is not None,
        "stats_render_s": round(shown, 2) if shown is not None else None,
    }


def time_deep_clean() -> dict:
    """清洗深度检查。完成判定用 cleaning_state 游标 ——
    UI 的「N 项待处理」在扫描开始前就在屏上，会误判。

    游标只能靠 exec-out 查（见 exec_out）：用 shell 查会静默返回空串，
    于是「游标永远是 0」，本项在每一档都报超时。
    """
    if not goto_home():
        return {"ok": False, "error": "回不了首页"}
    if not tap("设置", exact=True, timeout=20):
        return {"ok": False, "error": "找不到设置"}
    scroll_top()
    if not ensure_visible("数据清洗", tries=14):
        back()
        return {"ok": False, "error": "找不到数据清洗入口"}
    time.sleep(0.8)
    if not tap("数据清洗", timeout=6):
        back()
        return {"ok": False, "error": "找不到数据清洗入口"}
    time.sleep(2.0)

    cursor_before = query_cursor()
    scroll_top()
    if not tap("全库深度检查", exact=True, timeout=20):
        back()
        return {"ok": False, "error": "找不到深度检查按钮"}
    started = time.time()
    shown = None
    while time.time() - started < CLEAN_TIMEOUT:
        cursor_now = query_cursor()
        try:
            if int(cursor_now or "0") > int(cursor_before or "0"):
                shown = round(time.time() - started, 1)
                break
        except ValueError:
            pass  # run-as 偶发失败时按未完成继续等，别把一次抖动当成结果
        time.sleep(3.0)
    back(2)
    time.sleep(1.0)
    return {
        "ok": shown is not None,
        "deep_clean_s": shown,
        "timed_out": shown is None,
    }


def query_cursor() -> str:
    """读清洗游标（cleaning_state.lastCheckedAt 的最大值）。"""
    return query_scalar("SELECT IFNULL(MAX(lastCheckedAt),0) FROM cleaning_state;")


CLEAN_TIMEOUT = 600  # 由 --clean-timeout 覆盖


# ---------------- 主流程 ----------------


def run_one(count: int) -> dict:
    print(f"\n========== 压测 {count} 株 ==========", flush=True)
    result: dict = {"count": count}

    print("… 冷启动", flush=True)
    result["cold_start"] = cold_start()

    print("… 打开压测页", flush=True)
    if not open_stress_page():
        result["error"] = "打不开压测页"
        return result

    print("… 清空旧数据", flush=True)
    result["cleared"] = clear_all(count)
    if not result["cleared"]:
        # 不清空就往下测，采到的是「上一档残留 + 这一档」的指标 ——
        # 数字看着有，其实整档作废。宁可这一档没有数据。
        result["error"] = "清空失败：这一档的数据量不是它自称的档位，不采指标"
        return result

    print(f"… 生成 {count} 株", flush=True)
    result["seed"] = seed_plants(count)
    result["plants_in_db"] = query_plants()
    if not result["seed"].get("ok"):
        # 带着坏状态继续测只会得到一堆没有意义的数字：
        # 搜索/统计/清洗全都在错误的数据量上跑。中止，让人看原因
        result["error"] = f"造数据未完成（库里 {result['plants_in_db']} 株）"
        return result

    print("… 采内存 / 图片目录", flush=True)
    result["mem_mb_after_seed"] = meminfo_mb()
    result["image_dir_kb"] = image_dir_kb()

    print("… 搜索响应", flush=True)
    result["search"] = time_home_search()

    print("… 统计页", flush=True)
    result["stats"] = time_stats_page()

    print("… 滚动帧率", flush=True)
    if not goto_home():
        result["error"] = "回不了首页（滚动帧率前）"
        return result
    scroll_top()
    time.sleep(1.5)
    result["scroll"] = scroll_jank_percent()
    result["mem_mb_after_scroll"] = meminfo_mb()

    print("… 清洗深度检查", flush=True)
    result["clean"] = time_deep_clean()

    result["mem_mb_final"] = meminfo_mb()
    return result


def render_report(r: dict) -> str:
    lines = [f"## 压测 {r['count']} 株"]
    cs = r.get("cold_start", {})
    lines.append(f"- 冷启动：am_start TotalTime {cs.get('am_start_total_ms')} ms · "
                 f"首页可交互 {cs.get('home_interactive_s')} s")
    lines.append(f"- 清空旧数据：{'✓' if r.get('cleared') else '**失败**'}")
    sd = r.get("seed", {})
    if sd.get("ok"):
        lines.append(f"- 造数据：{sd.get('summary')}（脚本墙钟 {sd.get('wall_elapsed_s')} s）")
    else:
        lines.append(f"- 造数据：**失败** {sd.get('error')}")
    lines.append(f"- 库中植物数：{r.get('plants_in_db')}")
    lines.append(f"- 内存：造完后 {r.get('mem_mb_after_seed')} MB · "
                 f"滚动后 {r.get('mem_mb_after_scroll')} MB · 结束 {r.get('mem_mb_final')} MB")
    lines.append(f"- 图片目录：{r.get('image_dir_kb')} KB")
    sc = r.get("search", {})
    if sc.get("ok"):
        lines.append(f"- 搜索：打开 {sc.get('open_s')} s（全量 {sc.get('full_count')} 株）· "
                     f"输入过滤 {sc.get('filter_s')} s")
    else:
        lines.append(f"- 搜索：**失败** {sc.get('error')}")
    st = r.get("stats", {})
    lines.append(f"- 统计页：{'%.2f s' % st['stats_render_s'] if st.get('ok') else st.get('error')}")
    sl = r.get("scroll", {})
    lines.append(f"- 滚动：{sl.get('frames')} 帧 · janky {sl.get('janky_percent')}% · p90 {sl.get('p90_frame_ms')} ms")
    cl = r.get("clean", {})
    if cl.get("ok"):
        lines.append(f"- 清洗深度检查：{cl.get('deep_clean_s')} s")
    else:
        lines.append(f"- 清洗深度检查：**{'超时' if cl.get('timed_out') else '失败'}** {cl.get('error') or ''}")
    if "error" in r:
        lines.append(f"- !! {r['error']}")
    return "\n".join(lines)


def main() -> None:
    global CLEAN_TIMEOUT, W, H
    W, H = screen_size()   # shell 定义之后才能调；坐标全部按真实分辨率算
    args = sys.argv[1:]
    if not args or args[0] != "run":
        print(__doc__)
        sys.exit(2)
    count = int(args[1])
    if "--clean-timeout" in args:
        CLEAN_TIMEOUT = int(args[args.index("--clean-timeout") + 1])

    result = run_one(count)
    report = render_report(result)

    out_dir = os.path.join(_env.repo_root(), "build")
    os.makedirs(out_dir, exist_ok=True)
    out = os.path.join(out_dir, f"stress-report-{count}.txt")
    with open(out, "w", encoding="utf-8") as f:
        f.write(report + "\n")
    print("\n" + report)
    print(f"\n报告已写入 {out}")


if __name__ == "__main__":
    main()
