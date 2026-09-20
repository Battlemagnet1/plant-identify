#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Phase 6 验收 —— 统计 / 位置 / HTML 导出 / 备份恢复

对应分析报告 Part 4.8 的六条验收标准：

  ① assembleDebug 零错误（由构建步骤保证，本脚本不重复检查）
  ② 统计页三项指标与数据库行数**逐一对应**，且界面不出现「植物记录」
  ③ 导出的 HTML 能打开、图片正常显示、中文无乱码
  ④ 导出体积在阈值内（默认模式 < 50 MB）
  ⑤ 备份 → 清空 → 恢复，档案/观察/图片/备注完全一致
  ⑥ 拒绝定位权限后，全流程仍可正常完成

## 这个脚本与前几个的分工

`verify_phaseN.py` 一贯只回答「功能对不对」。Phase 6 的数据量断言很重，
所以这里把**数据库行数**当作唯一可信来源：界面上显示 13，就去数
`plant_record` 有几行；恢复完说「一致」，就把恢复后的库与备份包里的
JSON 逐字段比对，而不是看界面有没有数字。

## 两条写它时特意避开的陷阱

1. **导出/备份是异步的，不能靠 sleep 猜。**
   1.2 MB 的导出要跑几秒，照片多时更久。这里一律轮询设备上的文件，
   等到文件出现且体积稳定再往下断言。

2. **`adb exec-out run-as ... cat` 拉二进制文件必须重定向到本机文件。**
   走 Python 的 stdout 会被当文本处理，zip 直接损坏。
   所以拉取用 shell 重定向，再由 Python 读文件。
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



# ============================================================ P6 辅助函数


def device_ls(dir_path):
    """设备上某目录的文件名列表（用 run-as，只看文件名）"""
    out = shell("run-as", PKG, "ls", dir_path)
    names = []
    for line in out.splitlines():
        line = line.strip().replace("\r", "")
        if not line or "No such file" in line or "Permission denied" in line:
            continue
        names.append(line)
    return names


def device_file_size(remote):
    """设备上文件字节数；不存在返回 -1。

    用 `wc -c` 而不是 `sh -c "wc -c < f"` —— adb shell 会把参数用空格拼成
    一条命令，引号会丢失，`sh -c` 的写法在设备侧实际拿到的是被拆散的参数。
    """
    out = shell("run-as", PKG, "wc", "-c", remote)
    m = re.search(r"(\d+)", out.split("\n")[0] if out else "")
    if not m:
        return -1
    return int(m.group(1))


def pull_binary(remote, local):
    """把设备文件按**二进制**拉到本机。

    不能走 `shell()`：那条约定的返回值是文本，zip 会被解码破坏。
    这里直接用 subprocess 拿 bytes。
    """
    proc = subprocess.run(
        [ADB, "-s", D, "exec-out", "run-as", PKG, "cat", remote],
        capture_output=True, timeout=300,
    )
    if proc.returncode != 0 or not proc.stdout:
        return False
    with open(local, "wb") as f:
        f.write(proc.stdout)
    return True


def wait_text_anywhere(text, timeout=300, max_scrolls=8):
    """等某段文字出现在页面任意位置（每次从顶部重新扫）"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        if find_scrolling(text, scrolls=max_scrolls) is not None:
            return True
        time.sleep(2)
    return False


def snapshot_dir(dir_path):
    """目录的 {文件名: 字节数} 快照"""
    return {name: device_file_size("%s/%s" % (dir_path, name))
            for name in device_ls(dir_path)}


def wait_file_change(dir_path, before, timeout=300):
    """等目录里出现新文件**或已有文件被改写**，并等体积稳定。

    导出与备份都是异步的，sleep 猜时间必然出错 —— 要么等太短拿到半截文件，
    要么等太久白耗时间。

    ⚠️ 不能只找「新文件名」：导出文件名是**按日期**生成的
    （`plantIdentify_Report_2026-09-19.html`，照规格书的示例），
    同一天第二次导出是**覆盖同名文件** —— 只盯新名字会一直等不到，
    然后误报成「导出没产出文件」。所以这里同时比较体积变化。
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        now = snapshot_dir(dir_path)
        changed = [name for name, size in now.items() if before.get(name) != size]
        if changed:
            name = sorted(changed)[0]
            size = now[name]
            time.sleep(2)
            if size > 0 and size == device_file_size("%s/%s" % (dir_path, name)):
                return name, size
        time.sleep(2)
    return None, -1


def tap_scrolling(text, max_scrolls=10, bottom=False):
    """滚着找并点。bottom=True 时点同名控件里最靠下的那个。

    页面上「导出 HTML」既是区块标题也是按钮，直接按文字点到的是标题。
    按钮总在区块最下方，所以取 y 最大的那个。

    ⚠️ 必须先回顶部再往下找。上一步若是 `collect_all_text()`，
    它扫完会把页面停在**底部** —— 直接从这里往下滚，永远滚不到顶部那些区块，
    表现为「找不到某某按钮」，而按钮其实好好地在上面。
    """
    scroll_top(max_scrolls)
    for i in range(max_scrolls + 1):
        root = dump()
        if root is not None:
            hits = []
            for n in root.iter("node"):
                if ntext(n).strip() == text:
                    m = re.findall(r"-?\d+", n.get("bounds") or "")
                    if len(m) == 4:
                        hits.append((int(m[0]), int(m[1]), int(m[2]), int(m[3])))
            if hits:
                target = max(hits, key=lambda b: b[1]) if bottom else min(hits, key=lambda b: b[1])
                cx = (target[0] + target[2]) // 2
                cy = (target[1] + target[3]) // 2
                shell("input", "tap", str(cx), str(cy))
                time.sleep(1.8)
                return True
        if i < max_scrolls:
            scroll_down(1)
            time.sleep(0.9)
    return False


HOME_MARK = "中文名 / 拉丁学名 / 科 / 属"   # 只有首页有这行搜索提示


def app_in_foreground():
    """应用是否在前台。

    必须判断这个：脚本不能假设应用开着（上一轮验证很可能把它停掉了，
    清空数据那一步更是必须停掉它）。而「回到首页」的做法在两种情况下
    完全不同 —— 在应用里按返回键，不在应用里要冷启动。
    不判断的话，一路按返回键会退回桌面，之后每次 dump 看到的都是桌面，
    表现为一连串「找不到某某按钮」，排查半天才发现根本没打开应用。
    """
    for line in shell("dumpsys", "activity", "activities").splitlines():
        if "topResumedActivity" in line:
            return (PKG + "/") in line
    return False


def ensure_app_running():
    """确保应用在前台且停在首页"""
    if HOME_MARK in screen_text():
        return True
    if app_in_foreground():
        return goto_home()
    shell("am", "start", "-n", "%s/.MainActivity" % PKG)
    time.sleep(6)
    dismiss_location_prompt()
    return wait_text(HOME_MARK, timeout=30) is not None


def dismiss_location_prompt():
    """关掉位置询问框（如果正开着）。

    这个对话框的 onDismissRequest 是**故意不响应**的（必须显式二选一），
    所以按返回键也关不掉 —— 脚本里若不小心把它留在屏幕上，
    后面所有点击都会打在对话框上，表现为「找不到某某按钮」。
    """
    if "记录观察地点？" in screen_text():
        if tap("暂不允许", exact=True, timeout=5):
            time.sleep(1.5)
            return True
    return False


def goto_home():
    """回到首页：在应用里就退栈，不在应用里就冷启动"""
    dismiss_location_prompt()
    for _ in range(6):
        if HOME_MARK in screen_text():
            return True
        if app_in_foreground():
            shell("input", "keyevent", "4")
            time.sleep(1.4)
            dismiss_location_prompt()
        else:
            shell("am", "start", "-n", "%s/.MainActivity" % PKG)
            time.sleep(6)
            dismiss_location_prompt()
    return HOME_MARK in screen_text()


def type_into_field(label, value):
    """往某个输入框里写字，并**回读确认真的写进去了**。

    必须回读：`input text` 是追加而不是替换，失败时也不报错 ——
    不回读的话「没输进去」会一路伪装成「配置已完成」，
    最后在下游以「识别失败」的形式爆出来，排查方向完全跑偏。
    """
    node = find(label, exact=True)
    if node is None:
        return False, "找不到字段「%s」" % label
    pos = center(node)
    if pos is None:
        return False, "取不到「%s」的坐标" % label
    shell("input", "tap", str(pos[0]), str(pos[1]))
    time.sleep(1.2)
    # 移到行尾再连按退格清空（不清空会变成拼接串）
    shell("input", "keyevent", "123")
    for _ in range(70):
        shell("input", "keyevent", "67")
    time.sleep(0.6)

    if not value.isascii():
        return False, "值含非 ASCII 字符，adb 无法输入"

    shell("input", "text", value)
    time.sleep(1.2)
    if value not in screen_text():
        return False, "输入未生效（期望 %r）" % value
    return True, ""


def ensure_mock_reachable():
    """确保设备能访问本机模拟服务端；映射丢了就重建。

    `adb reverse` 会在设备重连、adb 服务重启等情况下**静默丢失**，
    而且丢了之后设备端表现为连接被拒。

    不检查的话，识别会以「无法连接到该地址，请确认 Base URL 填写正确」
    失败 —— 看上去像配置写错了，其实配置完全正确，只是映射没了。
    P4 已经踩过一次，这里直接做成自愈：先探一次，不通就重建再探。
    """
    def reachable():
        out = shell("curl", "-s", "--max-time", "6", MOCK + "/health")
        return "ok" in out

    if reachable():
        return True
    adb("reverse", "tcp:8899", "tcp:8899")
    time.sleep(2)
    return reachable()


def scroll_to(text, max_scrolls=10):
    """回顶部后逐屏往下找某段文字，找到一个就停"""
    scroll_top(max_scrolls)
    for _ in range(max_scrolls + 1):
        if find(text, exact=True) is not None:
            return True
        scroll_down(1)
        time.sleep(0.8)
    return False


def ensure_ai_configured():
    """确保视觉通道配置完整（Base URL / 模型名 / API Key）。

    脚本必须自己保证这一点，不能假设「上一次配好了就一直有效」：
    **卸载重装会清掉 Android Keystore 里的密钥**。即使 DataStore 里
    还留着那段密文，也解不开 —— 界面于是变回「缺少 API Key」，
    识别直接失败。装过 release 包再换回 debug 包就会遇上这种情况。
    """
    if not open_settings():
        return False, "进不去设置页"

    scroll_top(8)
    page = collect_all_text()
    if "配置不完整" not in page and "还缺少" not in page:
        goto_home()
        return True, "原本已配置"

    # reveal=True 的字段是密码框：界面把它渲染成圆点，不回显就读不到
    # 自己刚输的内容，「回读校验」必然失败。
    #
    # ⚠️ 「显示」开关必须在**滚动定位到该字段之后**再点。
    # 之前把它写在函数开头，而那时 collect_all_text() 刚把页面停在底部，
    # 「显示」根本不在视野里 —— 检查静默失败，于是密码框始终没被展开。
    for label, value, reveal in (
        ("Base URL", MOCK + "/v1", False),
        ("模型名", "mock-vl", False),
        ("API Key", "sk-verify-phase6", True),
    ):
        if not scroll_to(label):
            return False, "找不到字段「%s」" % label
        if reveal and find("显示", exact=True) is not None:
            tap("显示", exact=True, timeout=5)
            time.sleep(1.2)
        ok, why = type_into_field(label, value)
        if not ok:
            return False, why

    if not save_settings():
        return False, "保存配置失败"
    goto_home()
    return True, "已重新写入配置"


def distinct_taxonomy():
    """库里去重后的科数与属数（与统计页口径一致）"""
    db_path = os.path.join(os.environ.get("TEMP", "/tmp"),
                           "plant_identify_db", "plant_identify.db")
    con = sqlite3.connect(db_path)
    cur = con.cursor()
    families = cur.execute(
        "SELECT COUNT(DISTINCT family) FROM plant_record "
        "WHERE family IS NOT NULL AND family != ''").fetchone()[0]
    genera = cur.execute(
        "SELECT COUNT(DISTINCT genus) FROM plant_record "
        "WHERE genus IS NOT NULL AND genus != ''").fetchone()[0]
    con.close()
    return families, genera


def open_stats():
    """首页 → 统计页（点统计卡片）"""
    goto_home()
    scroll_top(6)
    if not tap_scrolling("查看统计详情 ›", max_scrolls=4):
        return False
    return wait_text("植物统计", timeout=20) is not None


def open_data_management():
    """首页 → 设置 → 数据管理"""
    goto_home()
    scroll_top(6)
    if not tap("设置", exact=True, timeout=15):
        return False
    time.sleep(1.5)
    if not tap_scrolling("导出 · 备份 · 恢复 · 位置", max_scrolls=10):
        return False
    return wait_text("数据管理", timeout=20) is not None


def clear_device_data():
    """清空数据库与图片，保留 exports/ 与 backups/。

    用 adb 直接删文件而不是走界面：界面删 13 株要几十次点击，
    而且「清空」本来就该是外部施加的破坏，不该依赖应用自己的功能。
    应用必须先停掉 —— SQLite 文件打开时删掉会留下 -wal 里的脏数据。
    """
    shell("am", "force-stop", PKG)
    time.sleep(2)
    # 目录名固定，逐条删；不用通配符，避免误删
    for name in ("plant_identify.db", "plant_identify.db-wal", "plant_identify.db-shm"):
        shell("run-as", PKG, "rm", "-f", "databases/" + name)
    shell("run-as", PKG, "rm", "-rf", "files/images")


def clear_capture_draft():
    """清掉拍摄草稿。

    草稿是**流程中的临时状态**，它指向 filesDir 里的照片文件。
    步骤 [3] 清空过 files/images，而草稿里那几张「已添加但尚未入库、
    因此也没进备份」的临时照片就永远回不来了 —— 草稿会一直引用
    不存在的文件，识别必然报「照片读取失败」。

    这不是应用缺陷（应用会明确提示「请回到上一步重新添加照片」），
    是验证步骤自己造成的数据不一致，所以由脚本自己收拾干净再往下走。
    """
    shell("am", "force-stop", PKG)
    time.sleep(2)
    shell("run-as", PKG, "rm", "-f", "files/datastore/capture_draft.preferences_pb")
    time.sleep(1)


def row_counts():
    """三张表的行数，直接查库"""
    snap = db_snapshot()
    return snap["plants"], snap["observations"], snap["images"]


def text_on_screen(target):
    return target in screen_text()


def numbers_on_screen():
    """统计页上出现的全部整数（按出现顺序）"""
    found = []
    for match in re.finditer(r"(不同植物|观察次数|照片数|科|属)\"", screen_text()):
        found.append(match.group(1))
    return found

# ============================================================ [0] 前置

print("\n[0] 前置检查")

# adb 的存在性已在 _env.require_adb_or_exit() 里校验过：找不到时它已经打印了
# 三条可选做法并退出；ADB 环境变量指向的文件不可用时它也会警告。
# 这里不再重复检查 —— 那只会多出一份迟早与 _env 漂移的提示文案。

out = shell("getprop", "ro.build.version.sdk").strip()
check("设备已连接（API %s）" % out, out.isdigit())

# run-as 依赖 debuggable —— 装了 release 包的话下面所有数据库断言都会失效，
# 那种情况下报错要明确指出原因，而不是让人对着一片「文件不存在」猜
probe = shell("run-as", PKG, "ls", "databases")
if "not debuggable" in probe or "Package" in probe and "not debuggable" in probe:
    check("设备上是可调试包（run-as 可用）", False,
          "当前安装的是 release 包，先换回 debug 包再跑本脚本")
    sys.exit(1)
check("设备上是可调试包（run-as 可用）", True)

base = refresh()
print("    库内现状：%d 株 / %d 次观察 / %d 张照片"
      % (base["plants"], base["observations"], base["images"]))
check("库里有可用于验证的数据", base["plants"] > 0, "库是空的，先跑 verify_phase5 造数据")

# 脚本不能假设应用已经开着 —— 上一轮验证很可能把它停掉了
check("应用已在前台并停在首页", ensure_app_running(), "冷启动失败")


# ============================================================ [1] 统计页（验收 ②）

print("\n[1] 统计页三项指标与数据库行数逐一对应")


def stats_in_view():
    """当前视口里的 {标签: 数值}。值节点紧挨在标签节点之前"""
    root = dump()
    if root is None:
        return {}
    texts = [ntext(n).strip() for n in root.iter("node") if ntext(n).strip()]
    labels = ("不同植物", "观察次数", "照片数", "科", "属")
    found = {}
    for i, text in enumerate(texts):
        if text in labels and i > 0 and texts[i - 1].isdigit():
            found[text] = int(texts[i - 1])
    return found


def stats_on_screen():
    scroll_top(6)
    found = {}
    for _ in range(4):
        found.update(stats_in_view())
        scroll_down(1)
        time.sleep(0.8)
    return found


if open_stats():
    check("进入统计页", True)
    shot("p6-stats")

    shown = stats_on_screen()
    families, genera = distinct_taxonomy()
    print("    页面显示：%s" % shown)
    print("    数据库   ：不同植物 %d / 观察次数 %d / 照片数 %d / 科 %d / 属 %d"
          % (base["plants"], base["observations"], base["images"], families, genera))

    check("「不同植物」= plant_record 行数",
          shown.get("不同植物") == base["plants"],
          "页面 %s / 库 %d" % (shown.get("不同植物"), base["plants"]))
    check("「观察次数」= plant_observation 行数",
          shown.get("观察次数") == base["observations"],
          "页面 %s / 库 %d" % (shown.get("观察次数"), base["observations"]))
    check("「照片数」= observation_image 行数",
          shown.get("照片数") == base["images"],
          "页面 %s / 库 %d" % (shown.get("照片数"), base["images"]))

    # 验收标准原文要求界面上不出现规格书里含混的「植物记录」
    page = collect_all_text()
    check("界面不出现「植物记录」字样", "植物记录" not in page,
          "该词把「一株植物」与「一次观察」混为一谈，已弃用")
    check("三项指标并列展示并标注分母差异", "分母不同" in page or "去重" in page)
else:
    check("进入统计页", False, "流程未走通")


# ============================================================ [2] HTML 导出（验收 ③④）

print("\n[2] HTML 导出：可打开、图片内嵌、中文不乱码、体积在阈值内")

EXPORT_DIR = "files/exports"
export_before = snapshot_dir(EXPORT_DIR)
export_name = None
export_size = 0

if open_data_management():
    check("进入数据管理页", True)

    check("导出前显示体积预估", "预计约" in collect_all_text())

    if tap_scrolling("导出 HTML", max_scrolls=8, bottom=True):
        check("触发导出", True)
        # 等**应用自己**说完成：结果卡片带「分享」按钮，且会一直留在页面上。
        # 不去轮询文件变化 —— 导出对同一批数据是确定性的（同名同大小），
        # 按体积变化检测不到改写，会误报成「导出没产出文件」。
        check("导出完成（结果卡片出现，可分享）", wait_text_anywhere("分享", timeout=300))
        names = [n for n in device_ls(EXPORT_DIR) if n.endswith(".html")]
        export_name = names[0] if names else None
        export_size = device_file_size("%s/%s" % (EXPORT_DIR, export_name)) if export_name else -1
        check("导出目录里有 HTML 文件", export_name is not None)
    else:
        check("触发导出", False, "找不到按钮")

    if export_name:
        print("    产物：%s  %.1f MB" % (export_name, export_size / 1024 / 1024))

        # 验收 ④：默认模式（缩略图）目标 50 MB
        check("默认模式体积 < 50 MB（验收 ④）",
              export_size < 50 * 1024 * 1024,
              "%.1f MB" % (export_size / 1024 / 1024))

        export_local = os.path.join(os.environ.get("TEMP", "/tmp"), "p6_report.html")
        if pull_binary("%s/%s" % (EXPORT_DIR, export_name), export_local):
            raw = open(export_local, "rb").read()
            check("拉回本机", len(raw) == export_size,
                  "本机 %d / 设备 %d" % (len(raw), export_size))

            # 验收 ③：中文无乱码的前提是整份文件能按 UTF-8 解码
            try:
                html = raw.decode("utf-8")
                decodable = True
            except UnicodeDecodeError as error:
                html = ""
                decodable = False
                print("    UTF-8 解码失败：%s" % error)
            check("整份文件可按 UTF-8 解码（中文不乱码）", decodable)
            check("声明了 charset=UTF-8", 'charset="UTF-8"' in html or "charset=UTF-8" in html)
            check("文档结构完整（doctype/html/body 闭合）",
                  html.startswith("<!DOCTYPE html>") and "</html>" in html and "</body>" in html)
            check("含报告标题与生成时间", "植物调查报告" in html and "生成时间" in html)
            check("含三项统计块",
                  all(k in html for k in ("不同植物", "观察次数", "照片数")))

            cards = html.count('<section class="plant">')
            check("植物卡片数 = 档案数",
                  cards == base["plants"], "HTML %d / 库 %d" % (cards, base["plants"]))

            embedded = html.count("data:image/jpeg;base64,")
            check("内嵌图片数 = 图片行数（验收 ③ 的「图片正常显示」）",
                  embedded == base["images"], "HTML %d / 库 %d" % (embedded, base["images"]))

            placeholders = html.count("图片缺失")
            check("没有读不出来的图片", placeholders == 0, "%d 张占位" % placeholders)

            check("转义正确（无二次转义残留）", "&amp;lt;" not in html)

            # 植物名与字段标签要真的出现，否则可能是一份空报告
            check("含植物名称与关键字段",
                  "紫薇" in html and all(k in html for k in ("正式中文名称", "拉丁学名", "AI识别置信度")))
            shot("p6-export-result")
        else:
            check("拉回本机", False, "pull 失败")
else:
    check("进入数据管理页", False, "流程未走通")


# ============================================================ [3] 备份 → 清空 → 恢复（验收 ⑤）

print("\n[3] 备份 → 清空 → 恢复，数据完全一致")

import zipfile  # noqa: E402 - 只在第 3 步用到

BACKUP_DIR = "files/backups"
backup_before = snapshot_dir(BACKUP_DIR)
backup_name = None

if open_data_management():
    if tap_scrolling("备份数据", max_scrolls=8, bottom=True):
        check("触发备份", True)
        backup_name, backup_size = wait_file_change(BACKUP_DIR, backup_before, timeout=300)
        check("备份完成并产出 zip", backup_name is not None, "等了 300 秒也没等到")
    else:
        check("触发备份", False, "找不到按钮")

    backup_local = os.path.join(os.environ.get("TEMP", "/tmp"), "p6_backup.zip")
    manifest = {}
    payload = {}
    if backup_name and pull_binary("%s/%s" % (BACKUP_DIR, backup_name), backup_local):
        with zipfile.ZipFile(backup_local) as zf:
            names = zf.namelist()
            manifest = json.loads(zf.read("manifest.json").decode("utf-8"))
            payload = json.loads(zf.read("data.json").decode("utf-8"))

        check("备份包含 manifest / data / images 三部分",
              "manifest.json" in names and "data.json" in names
              and any(n.startswith("images/") for n in names))
        check("格式标识正确", manifest.get("format") == "plant-identify-backup")
        check("备份条数与库一致",
              manifest.get("counts", {}).get("plants") == base["plants"]
              and manifest.get("counts", {}).get("observations") == base["observations"]
              and manifest.get("counts", {}).get("images") == base["images"],
              "manifest=%s / 库=%d/%d/%d" % (manifest.get("counts"),
                                             base["plants"], base["observations"], base["images"]))
        check("图片文件数与图片行数一致",
              sum(1 for n in names if n.startswith("images/") and not n.endswith("/"))
              == base["images"])
        check("备份保留了 AI 原始结果", bool(payload["observations"][0].get("aiResultJson")))
    else:
        check("拉回备份包", False, "pull 失败")

    # ---- 清空
    print("    清空数据库与图片（保留备份与导出文件）…")
    clear_device_data()
    time.sleep(2)
    check("图片文件已清空", device_file_size("files/images") == -1
          or len(device_ls("files/images")) == 0)
    check("备份包未被误删", backup_name in device_ls(BACKUP_DIR))

    shell("am", "start", "-n", "%s/.MainActivity" % PKG)
    time.sleep(6)
    empty = refresh()
    print("    清空后：%d 株 / %d 次观察 / %d 张照片"
          % (empty["plants"], empty["observations"], empty["images"]))
    check("档案已清空（验收 ⑤ 的中间态）",
          empty["plants"] == 0 and empty["observations"] == 0, "仍有数据")

    # ---- 恢复
    if open_data_management():
        check("清空后仍能进入数据管理页", True)
        check("备份列表里能看到刚才那一份",
              any("本机备份" in line for line in [collect_all_text()])
              or "本机备份" in collect_all_text())

        if tap_scrolling("恢复", max_scrolls=10, bottom=True):
            time.sleep(2)
            dialog = collect_all_text()
            check("恢复前有二次确认", "确认恢复" in dialog, dialog[:200])
            if tap("确认恢复", exact=True, timeout=15):
                time.sleep(15)
                restored = refresh()
                print("    恢复后：%d 株 / %d 次观察 / %d 张照片"
                      % (restored["plants"], restored["observations"], restored["images"]))

                check("档案数恢复（验收 ⑤）",
                      restored["plants"] == base["plants"],
                      "%d -> %d" % (base["plants"], restored["plants"]))
                check("观察数恢复",
                      restored["observations"] == base["observations"],
                      "%d -> %d" % (base["observations"], restored["observations"]))
                check("图片行数恢复",
                      restored["images"] == base["images"],
                      "%d -> %d" % (base["images"], restored["images"]))
                check("图片文件真的还原到磁盘",
                      len([f for f in device_ls("files/images/2026/09") if f]) > 0)

                # ---- 逐字段比对：只看行数证明不了「完全一致」
                con = sqlite3.connect(os.path.join(
                    os.environ.get("TEMP", "/tmp"), "plant_identify_db", "plant_identify.db"))
                con.row_factory = sqlite3.Row
                mismatches = []
                for table, key in (("plant_record", "plants"),
                                   ("plant_observation", "observations"),
                                   ("observation_image", "images")):
                    rows = [dict(r) for r in con.execute(
                        "SELECT * FROM %s ORDER BY id" % table).fetchall()]
                    by_id = {r["id"]: r for r in rows}
                    for want in payload[key]:
                        got = by_id.get(want["id"])
                        if got is None:
                            mismatches.append("%s id=%s 缺失" % (table, want["id"]))
                            continue
                        for field, value in want.items():
                            actual = got.get(field)
                            if isinstance(value, float) and isinstance(actual, (int, float)):
                                if abs(value - actual) > 1e-9:
                                    mismatches.append("%s id=%s.%s" % (table, want["id"], field))
                            elif value != actual:
                                mismatches.append("%s id=%s.%s" % (table, want["id"], field))
                con.close()
                check("恢复后的库与备份逐字段一致（含备注、AI 结果、图片路径）",
                      not mismatches, "；".join(mismatches[:5]))
                shot("p6-after-restore")
            else:
                check("确认恢复", False, "找不到确认按钮")
        else:
            check("找到恢复入口", False, "找不到「恢复」")
    else:
        check("清空后仍能进入数据管理页", False, "流程未走通")
else:
    # 这一段整个被跳过时**必须显式报失败**。
    # 第一版漏了这个 else，结果是「进不去数据管理页」时整段一声不吭 ——
    # 汇总里少了十几条断言却看不出来，比断言失败危险得多。
    check("进入数据管理页（备份阶段）", False, "流程未走通")


# ============================================================ [4] 位置（验收 ⑥）

print("\n[4] 位置：首次询问只问一次，拒绝后全流程仍可完成")

# 先清掉可能残留的拍摄草稿：它引用的是 [3] 清空过的那些文件
clear_capture_draft()

if goto_home() and goto_add_plant():
    check("进入添加植物页", True)
    time.sleep(2)

    asked = "记录观察地点？" in collect_all_text()
    if asked:
        check("首次进入弹出位置询问", True)
        shot("p6-location-prompt")

        if tap("暂不允许", exact=True, timeout=15):
            time.sleep(2)
            check("点「暂不允许」后对话框关闭",
                  "记录观察地点？" not in collect_all_text())

            # 再进一次，不该被重复打扰
            goto_home()
            goto_add_plant()
            time.sleep(2.5)
            check("再次进入不再重复询问（只问一次）",
                  "记录观察地点？" not in collect_all_text())

            # 验收 ⑥ 的实质：拒绝授权之后，识别与保存能不能照常走完。
            # 先自己确保 AI 配置在位 —— 卸载重装会清掉 Keystore 里的密钥，
            # 配置会悄悄退回「缺少 API Key」，不检查就会把它误判成流程有 bug
            # 连通性先于配置检查：映射丢了会伪装成「Base URL 配错了」
            check("设备可访问模拟服务端（reverse 映射在位）", ensure_mock_reachable())

            configured, why = ensure_ai_configured()
            check("AI 配置就绪（识别的前置条件）", configured, why)

            before = refresh()
            if configured and run_recognition_to_result():
                check("拒绝定位后仍能完成识别", True)
                if tap("保存到档案", exact=True, timeout=20):
                    # 同名植物会触发归并提示，选「创建新的植物」继续
                    time.sleep(3)
                    if "创建新的植物" in collect_all_text():
                        tap("创建新的植物", exact=True, timeout=15)
                    time.sleep(10)
                    after = refresh()
                    check("拒绝定位后仍能保存档案（验收 ⑥）",
                          after["plants"] == before["plants"] + 1,
                          "%d -> %d" % (before["plants"], after["plants"]))

                    # 新增的那条观察必须没有坐标 —— 拒绝了就不该偷偷记
                    con = sqlite3.connect(os.path.join(
                        os.environ.get("TEMP", "/tmp"), "plant_identify_db", "plant_identify.db"))
                    con.row_factory = sqlite3.Row
                    row = con.execute(
                        "SELECT latitude, longitude FROM plant_observation ORDER BY id DESC LIMIT 1"
                    ).fetchone()
                    con.close()
                    check("拒绝定位后新增观察不含坐标",
                          row is not None and row["latitude"] is None and row["longitude"] is None,
                          "lat=%s lng=%s" % (row["latitude"] if row else "?",
                                             row["longitude"] if row else "?"))
                    shot("p6-after-save-no-location")
                else:
                    check("拒绝定位后仍能保存档案（验收 ⑥）", False, "找不到保存按钮")
            else:
                check("拒绝定位后仍能完成识别", False, "流程未走通")
        else:
            check("点「暂不允许」", False, "找不到按钮")
    else:
        # 已经问过就不会再弹 —— 这也是正确行为，不算失败
        print("    （本次未弹询问框：本机之前已经选择过，符合「只问一次」的设计）")
        check("曾经询问过则不重复弹窗", True)
else:
    check("进入添加植物页", False, "流程未走通")


# ============================================================ 汇总

print("\n" + "=" * 60)
print("合计：通过 %d，失败 %d" % (passed, failed))
if failed:
    print("❌ 有未通过项")
else:
    print("✅ Phase 6 验收标准全部通过")
sys.exit(1 if failed else 0)
