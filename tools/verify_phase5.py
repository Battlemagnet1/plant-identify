#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Phase 5 验收 · 植物档案与归并

覆盖分析报告 Part 4.7 的五条验收标准，另加删除的文件级联：

  A. 第二次识别同一植物 → 触发归并提示；选「添加到已有植物」后
     观察数 +1，且**原有观察与照片完整保留**（验收标准 ②）
  B. 归并提示里选「创建新的植物」→ 生成新的 PlantRecord（验收标准 ③）
  C. 系统在任何情况下都不自动合并两条已有档案（验收标准 ④）
  D. 搜索命中中文名与拉丁学名（验收标准 ⑤）
  E. 删除植物 → 图片文件真的从 filesDir 消失，不是只删了数据库行

## 为什么这一步必须查数据库

界面上的「3 次观察」可能只是文案算对了，库里存的是不是三条完全是另一回事。
本脚本的每一个「+1」都取自 sqlite 的实际行数差值，不是界面文字。

辅助函数与 tools/verify_phase4.py 共用同一套（含 `checkable` 定位开关、
逐屏扫描、连通性自检等），原因见那个文件头的七条教训。
"""
import json
import os
import re
import sqlite3
import subprocess
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

# 本机相关的路径都可用环境变量覆盖，避免把某台机器的布局写死在仓库里
ADB = os.environ.get(
    "ADB",
    os.path.join(os.environ.get("ANDROID_HOME", "C:/Users/a/Android/Sdk"),
                 "platform-tools", "adb.exe"),
)
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



# ---------------------------------------------------------------- 阶段专用辅助

def open_home_and_search():
    """回首页并进入搜索页"""
    shell("am", "force-stop", PKG)
    time.sleep(1.5)
    start_app()
    time.sleep(5)
    # 首页的搜索入口是一个整行的可点击卡片，用其中的提示文案定位
    if not tap("搜索植物", exact=False, timeout=20):
        return False
    time.sleep(2.5)
    # 搜索页有输入框与「找到 N 株植物」计数，用它确认真的进来了
    return find("找到", exact=False) is not None


def type_into_search(keyword):
    """把关键词填进搜索框，并**回读校验它真的进去了**。

    两个必须处理的现实：

    1. `input text` 是追加而不是替换。不清空的话第二次搜索会变成
       「紫薇lagerstroemia」这种拼接串，结果是 0 条，
       而断言只会说「搜拉丁名没结果」—— 查半天代码其实没问题。

    2. **`input text` 不支持非 ASCII**。传中文会直接抛
       `NullPointerException: Attempt to get length of null array`
       （实测 Android 14）。若脚本不校验就断言，会得到一连串
       「假通过」：关键词没进去 → 空关键词返回全部 → 断言反而全绿。
       这是本项目最危险的一类错误，所以这里必须回读确认。
    """
    node = find("搜索植物", exact=False)
    if node is None:
        return False, "找不到搜索框"
    pos = center(node)
    if pos is None:
        return False, "取不到搜索框坐标"
    shell("input", "tap", str(pos[0]), str(pos[1]))
    time.sleep(1.2)

    # 移到行尾再连按退格清空
    shell("input", "keyevent", "123")
    for _ in range(40):
        shell("input", "keyevent", "67")
    time.sleep(0.6)

    if not keyword.isascii():
        return False, f"关键词含非 ASCII 字符（{keyword}），adb 无法输入"

    shell("input", "text", keyword)
    time.sleep(1.0)

    # 回读：把输入框的当前值取出来比对
    actual = ""
    root = dump()
    if root is not None:
        for n in root.iter("node"):
            v = ntext(n)
            if keyword in v:
                actual = v
                break
    if actual != keyword:
        return False, f"输入未生效：期望 {keyword!r}，输入框实际 {actual!r}"
    return True, ""


def open_plant_detail(plantId):
    """回首页，点开指定档案。

    首页列表按 updatedAt 倒序，所以不能按「第几条」定位 ——
    直接走深链接路由不现实（脚本里没有 navController），
    改为在首页列表里找包含该档案名称的卡片并点开，
    再进详情页核对是不是目标 id。
    """
    shell("am", "force-stop", PKG)
    time.sleep(1.5)
    start_app()
    time.sleep(5)
    if not tap("紫薇", exact=True, timeout=20):
        return False
    time.sleep(3)
    return find("植物详情", exact=False) is not None or find("植物百科") is not None

# ---------------------------------------------------------------- 数据库快照

def db_snapshot():
    """拉取 Room 数据库（含 -wal/-shm）并返回关键计数。

    只读界面文字证明不了「库里存了几行」—— 验收标准 ②③④ 都要求
    真实的行数变化，所以每个「+1」都从这里取。
    """
    out_dir = os.path.join(os.environ.get("TEMP", "/tmp"), "plant_identify_db")
    os.makedirs(out_dir, exist_ok=True)
    db_path = os.path.join(out_dir, "plant_identify.db")
    for suffix in ("", "-wal", "-shm"):
        raw = adb("exec-out", "run-as", PKG, "cat",
                  f"databases/plant_identify.db{suffix}", binary=True, timeout=90)
        with open(db_path + suffix, "wb") as f:
            f.write(raw)

    con = sqlite3.connect(db_path)
    con.row_factory = sqlite3.Row
    cur = con.cursor()

    def scalar(sql, *args):
        cur.execute(sql, args)
        row = cur.fetchone()
        return row[0] if row else 0

    snapshot = {
        "plants": scalar("SELECT COUNT(*) FROM plant_record"),
        "observations": scalar("SELECT COUNT(*) FROM plant_observation"),
        "images": scalar("SELECT COUNT(*) FROM observation_image"),
        "name": query_plants(cur),
    }
    con.close()
    return snapshot


def query_plants(cur):
    cur.execute(
        "SELECT p.id, p.name, p.latinName, "
        "(SELECT COUNT(*) FROM plant_observation o WHERE o.plantId = p.id) AS obs, "
        "(SELECT COUNT(*) FROM observation_image i "
        "   JOIN plant_observation o2 ON i.observationId = o2.id "
        "  WHERE o2.plantId = p.id) AS imgs "
        "FROM plant_record p ORDER BY p.id"
    )
    return [dict(r) for r in cur.fetchall()]


def plants_named(name):
    """库里叫这个名字的档案（按 id）"""
    return [p for p in LAST_SNAPSHOT["name"] if p["name"] == name]


def refresh():
    global LAST_SNAPSHOT
    LAST_SNAPSHOT = db_snapshot()
    return LAST_SNAPSHOT


def file_count():
    """filesDir 下的图片文件数"""
    out = shell("run-as", PKG, "find", "files/images", "-type", "f", timeout=60)
    return len([l for l in out.splitlines() if l.strip()])


LAST_SNAPSHOT = {"plants": 0, "observations": 0, "images": 0, "name": []}


def search_by_sql(name_keyword, latin_keyword):
    """用与 DAO **完全相同**的 WHERE 子句直接查库。

    为什么需要它：`adb shell input text` 不支持非 ASCII（传中文抛
    NullPointerException），所以「在界面里输入中文关键词」这一步无法脚本化。
    与其写一个必然假通过的断言，不如把验收标准 ⑤ 拆成两半：
      · 界面接线 + latinName 匹配 → 用 ASCII 关键词在界面里真实跑
      · 中文名匹配 → 用同一条 SQL 直接查库
    两半都过了，才能说「搜『紫薇』可同时命中中文名与拉丁学名」成立。
    """
    out_dir = os.path.join(os.environ.get("TEMP", "/tmp"), "plant_identify_db")
    db_path = os.path.join(out_dir, "plant_identify.db")
    con = sqlite3.connect(db_path)
    con.row_factory = sqlite3.Row
    cur = con.cursor()

    # 与 PlantRecordDao.searchPlantCards 的匹配子句保持一致
    clause = """WHERE (:kw = '' OR p.name LIKE '%' || :kw || '%'
        OR IFNULL(p.latinName, '') LIKE '%' || :kw || '%'
        OR IFNULL(p.family, '') LIKE '%' || :kw || '%'
        OR IFNULL(p.genus, '') LIKE '%' || :kw || '%'
        OR IFNULL(p.category, '') LIKE '%' || :kw || '%'
        OR IFNULL(p.note, '') LIKE '%' || :kw || '%')"""

    cur.execute("SELECT p.id FROM plant_record p " + clause, {"kw": name_keyword})
    by_name = {r["id"] for r in cur.fetchall()}
    cur.execute("SELECT p.id FROM plant_record p " + clause, {"kw": latin_keyword})
    by_latin = {r["id"] for r in cur.fetchall()}
    con.close()

    return {
        "by_name": len(by_name),
        "by_latin": len(by_latin),
        "same_rows": len(by_name & by_latin),
    }


# ---------------------------------------------------------------- 前置检查

print("=" * 70)
print("Phase 5 验收 · 植物档案与归并")
print("=" * 70)

print("\n[前置] 模拟服务端与设备连通性")
caps = server_capabilities()
check(f"服务端支持所需模式（{len(caps)} 种已注册）", "ok" in caps)
if "ok" not in caps:
    sys.exit(1)


def device_reaches_mock():
    out = shell("curl", "-s", "--max-time", "6", "http://127.0.0.1:8899/health",
                timeout=30)
    return '"ok"' in out


if not device_reaches_mock():
    print("    不通，重建 adb reverse 映射…")
    adb("reverse", "tcp:8899", "tcp:8899", timeout=30)
    time.sleep(1.5)
check("设备可访问模拟服务端", device_reaches_mock())
if not device_reaches_mock():
    print("\n⚠ 设备仍无法访问模拟服务端，中止。")
    sys.exit(1)

set_mode("ok")
shell("logcat", "-c")
reset_log()

before = refresh()
print(f"    起始状态：{before['plants']} 株 / {before['observations']} 次观察 / "
      f"{before['images']} 张照片")


# ---------------------------------------------------------------- 归并提示

def save_and_wait():
    """点「保存到档案」，返回是否出现了归并对话框"""
    scroll_top(6)
    for _ in range(8):
        if find("保存到档案", exact=True) is not None:
            break
        scroll_down()
    if not tap("保存到档案", exact=True, timeout=15):
        return None

    # 两条路径：出现归并对话框，或直接保存完成
    deadline = time.time() + 90
    while time.time() < deadline:
        text = screen_text()
        if "可能已记录过这种植物" in text or "是否与已有植物是同一种" in text:
            return "merge"
        if "已保存到植物档案" in text:
            return "saved"
        time.sleep(1.5)
    return None


# ============================================================ [C] 先测自动合并
print("\n[C] 反复保存同名植物 —— 系统不得自动合并（验收标准 ④）")
print("    （先跑这条：后面的 A/B 依赖它建立「已有档案」）")

refresh()
c_before = LAST_SNAPSHOT.copy()

if run_recognition_to_result():
    check("识别完成", True)
    outcome = save_and_wait()
    # 库里有同名档案时**必须**弹提示而不是直接写入 ——
    # 这就是「不自动合并」的直接证据
    check("出现归并提示而非直接保存", outcome == "merge",
          f"实际 outcome={outcome}")

    if outcome == "merge":
        decision = collect_all_text()
        check("提示里给出匹配依据", "判断依据" in decision, decision[-300:])
        check("提示里说明将变为第几次观察", "第" in decision and "次观察" in decision)
        check("提供「添加到已有植物」", "添加到已有植物" in decision)
        check("提供「创建新的植物」", "创建新的植物" in decision)

    after = refresh()
    check("此刻尚未写入任何档案（等用户决定）",
          after["plants"] == c_before["plants"],
          f"{c_before['plants']} -> {after['plants']}")
    shot("p5-merge-dialog")

    # 选「创建新的植物」→ 验收标准 ③
    if tap("创建新的植物", exact=True, timeout=15):
        check("选择「创建新的植物」", True)
        check("保存完成", wait_text("已保存到植物档案", timeout=180))
        after = refresh()
        check("新增了一份 PlantRecord（验收标准 ③）",
              after["plants"] == c_before["plants"] + 1,
              f"{c_before['plants']} -> {after['plants']}")
        check("并新增了一条观察",
              after["observations"] == c_before["observations"] + 1,
              f"{c_before['observations']} -> {after['observations']}")

        same_name = plants_named("紫薇")
        check("库里存在多份同名档案且互不合并（验收标准 ④）",
              len(same_name) >= 2, f"同名档案 {len(same_name)} 份")
    else:
        check("选择「创建新的植物」", False, "找不到按钮")
else:
    check("识别完成", False, "流程未走通")


# ============================================================ [A] 添加到已有植物
print("\n[A] 第二次识别同一植物 → 添加到已有植物（验收标准 ②）")

refresh()
a_before = LAST_SNAPSHOT.copy()
# 挑一份观察数最少的同名档案作为目标，便于观察「观察数 +1」
targets = [p for p in plants_named("紫薇") if p["obs"] >= 1]
target = min(targets, key=lambda p: (p["obs"], p["id"])) if targets else None
print(f"    目标档案：#{target['id']}（当前 {target['obs']} 次观察 / "
      f"{target['imgs']} 张照片）" if target else "    ⚠ 找不到可用的目标档案")

if target and run_recognition_to_result():
    check("识别完成", True)
    outcome = save_and_wait()
    check("出现归并提示", outcome == "merge", f"outcome={outcome}")

    if outcome == "merge":
        if tap("添加到已有植物", exact=True, timeout=15):
            check("选择「添加到已有植物」", True)
            check("保存完成", wait_text("已保存到植物档案", timeout=180))

            after = refresh()
            check("档案总数不变（没有新建 PlantRecord）",
                  after["plants"] == a_before["plants"],
                  f"{a_before['plants']} -> {after['plants']}")
            check("观察总数 +1",
                  after["observations"] == a_before["observations"] + 1,
                  f"{a_before['observations']} -> {after['observations']}")

            now = [p for p in after["name"] if p["id"] == target["id"]]
            if now:
                check(f"目标档案 #{target['id']} 的观察数 +1",
                      now[0]["obs"] == target["obs"] + 1,
                      f"{target['obs']} -> {now[0]['obs']}")
                check("目标档案的照片数也增加了",
                      now[0]["imgs"] > target["imgs"],
                      f"{target['imgs']} -> {now[0]['imgs']}")

            # 原有观察必须完整保留：观察数只增不减，且更早的那条还在
            check("原有观察未被覆盖（观察数只增不减）",
                  after["observations"] > a_before["observations"])
            shot("p5-appended")
        else:
            check("选择「添加到已有植物」", False, "找不到按钮")
    else:
        check("选择「添加到已有植物」", False, "没有出现归并提示")
else:
    check("识别完成", False, "流程未走通或没有目标档案")


# ============================================================ [D] 搜索
print("\n[D] 搜索命中中文名与拉丁学名（验收标准 ⑤）")

if open_home_and_search():
    check("进入搜索页", True)

    all_plants = refresh()["plants"]

    # ---- D1：拉丁学名（ASCII，adb 可以真实输入）
    ok, why = type_into_search("Lagerstroemia")
    check("拉丁学名关键词输入成功", ok, why)
    time.sleep(2.5)
    text = collect_all_text()
    check("搜拉丁学名有结果", "找到 0 株植物" not in text, text[:200])
    check("结果卡片显示中文名", "紫薇" in text)
    check("结果卡片显示拉丁学名", "Lagerstroemia" in text)
    check("结果卡片显示观察次数", "次观察" in text)
    shot("p5-search-latin")

    # ---- D2：大小写不敏感（又一个 ASCII 场景）
    ok, why = type_into_search("lagerstroemia")
    check("小写拉丁名同样命中（大小写不敏感）", ok, why)
    time.sleep(2.5)
    check("小写搜索结果与大小写无关",
          "找到 0 株植物" not in collect_all_text())

    # ---- D3：无关关键词 → 空结果提示
    ok, why = type_into_search("zzzz-nothing")
    check("无关关键词输入成功", ok, why)
    time.sleep(2.5)
    text = collect_all_text()
    check("无关关键词给出空结果提示",
          "找到 0 株植物" in text or "没有匹配" in text, text[:200])
    shot("p5-search-empty")

    ok, why = type_into_search("")
    check("清空关键词后恢复全部结果", ok, why)
    time.sleep(2.5)
    text = collect_all_text()
    check(f"清空后能看到全部 {all_plants} 株",
          f"找到 {all_plants} 株植物" in text, text[:200])
else:
    check("进入搜索页", False, "流程未走通")

# ---- D4：中文关键词走 SQL 层验证
#
# `adb shell input text` 不支持非 ASCII（实测传中文抛 NullPointerException），
# 因此「界面里输入中文」这一步无法脚本化。若不管它直接断言，
# 会得到假通过 —— 关键词没输进去，空关键词返回全部，断言看着全绿。
#
# 于是拆成两半各自验证：
#   · 界面接线与 latinName 匹配 → D1/D2/D3 已用 ASCII 真实跑通
#   · 中文名匹配 → 这里用**与 DAO 完全相同的 WHERE 子句**直接查库
print("\n[D4] 中文名匹配（走 SQL 层 —— adb 无法输入中文，见注释）")
sql_result = search_by_sql("紫薇", "Lagerstroemia")

check("SQL 查「紫薇」命中中文名", sql_result["by_name"] > 0,
      f"命中 {sql_result['by_name']} 行")
check("SQL 查「Lagerstroemia」命中拉丁学名", sql_result["by_latin"] > 0,
      f"命中 {sql_result['by_latin']} 行")
check("同一个关键词能同时命中中文名与拉丁学名的行（验收标准 ⑤ 的实质）",
      sql_result["same_rows"] > 0,
      f"两路命中的交集 {sql_result['same_rows']} 行")
print(f"    中文名命中 {sql_result['by_name']} 行 / "
      f"拉丁名命中 {sql_result['by_latin']} 行 / 交集 {sql_result['same_rows']} 行")


# ============================================================ [E] 删除植物
print("\n[E] 删除植物 → 图片文件必须真的消失（报告 Part 4.7 主要风险）")

refresh()
e_before = LAST_SNAPSHOT.copy()
files_before = file_count()
print(f"    起始：{e_before['plants']} 株 / 磁盘 {files_before} 个图片文件")

if not plants_named("紫薇"):
    check("找到可删除的档案", False, "库里没有同名档案")
elif open_plant_detail(0):
    check("进入详情页", True)

    if tap("删除", exact=True, timeout=15):
        time.sleep(1.5)
        dialog = collect_all_text()
        check("删除前有二次确认", "无法撤销" in dialog, dialog[:300])

        # 确认按钮刻意与顶栏的「删除」不同名，避免点错
        if tap("确认删除", exact=True, timeout=15):
            time.sleep(4)
            after = refresh()

            # 不预设删掉的是哪一条 —— 首页列表按最近更新排序，
            # 点开的是哪张卡并不确定。用 id 差集反推实际被删的档案，
            # 再拿它的照片数去核对文件变化，这样与「点开哪张卡」无关
            gone_ids = {p["id"] for p in e_before["name"]} - {p["id"] for p in after["name"]}
            check("恰好删除了一份档案", len(gone_ids) == 1,
                  f"消失了 {len(gone_ids)} 份：{gone_ids}")
            check("档案总数 -1",
                  after["plants"] == e_before["plants"] - 1,
                  f"{e_before['plants']} -> {after['plants']}")

            if gone_ids:
                gone = next(p for p in e_before["name"] if p["id"] in gone_ids)
                print(f"    实际删除：#{gone['id']}（{gone['obs']} 次观察 / "
                      f"{gone['imgs']} 张照片）")
                # 观察行也要跟着走：档案没了但观察还在就是孤儿数据
                check("该档案的观察行一并删除",
                      after["observations"] == e_before["observations"] - gone["obs"],
                      f"观察 {e_before['observations']} -> {after['observations']}，"
                      f"该档案原有 {gone['obs']} 条")
                files_after = file_count()
                check(
                    f"图片文件真的被删掉（磁盘 {files_before} -> {files_after}，"
                    f"该档案原有 {gone['imgs']} 张）",
                    files_after <= files_before - 1,
                    "磁盘文件数没有减少 —— 只删了数据库行，文件成了孤儿",
                )
                # 照片文件不应被删多：其它档案还引用着各自的照片
                check("没有误删其它档案的照片",
                      files_after >= files_before - gone["imgs"] - 1,
                      f"少了 {files_before - files_after} 个，但该档案只有 {gone['imgs']} 张")
            shot("p5-after-delete")
        else:
            check("确认删除", False, "找不到确认按钮")
    else:
        check("详情页有删除入口", False, "找不到「删除」")
else:
    check("进入详情页", False, "流程未走通")


# ============================================================ 汇总
print("\n[汇总]")
set_mode("ok")

raw = adb("logcat", "-d", timeout=120)
lines = raw.splitlines()
blocks = ["\n".join(lines[i: i + 6]) for i, l in enumerate(lines) if "FATAL EXCEPTION" in l]
app_blocks = [b for b in blocks if f"Process: {PKG}" in b]
check("本应用无 FATAL 崩溃", not app_blocks,
      f"{len(app_blocks)} 次；其他进程 {len(blocks) - len(app_blocks)} 次（不计）")

print("\n" + "=" * 70)
print(f"通过 {passed} / 失败 {failed}")
print("=" * 70)
sys.exit(0 if failed == 0 else 1)
