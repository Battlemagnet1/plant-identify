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

# ============================================================ Phase6+ 验收

print("\n[0] 前置检查")

# adb 的存在性已在 _env.require_adb_or_exit() 里校验过：找不到时它已经打印了
# 三条可选做法并退出；ADB 环境变量指向的文件不可用时它也会警告。
# 这里不再重复检查 —— 那只会多出一份迟早与 _env 漂移的提示文案。

out = shell("getprop", "ro.build.version.sdk").strip()
check("设备在线（API %s）" % out, out.isdigit())

screen_w, screen_h = 1080, 1920
size_raw = shell("wm", "size")
m = re.search(r"(\d+)x(\d+)", size_raw)
if m:
    screen_w, screen_h = int(m.group(1)), int(m.group(2))
print("    屏幕：%dx%d" % (screen_w, screen_h))

# 这个脚本大量依赖「点一屏找一屏」，屏幕越界会静默点空
check("取到屏幕尺寸", m is not None, size_raw)

check("设备可访问模拟服务端", ensure_mock_reachable())
set_mode("ok")

# 强停会被留在前台的旧实例，避免脚本从上次的残局开始
reset_log()
shell("am", "force-stop", PKG)
time.sleep(2)
shell("am", "start", "-n", PKG + "/.MainActivity")
time.sleep(6)

base = db_snapshot()
print("    库内现状：%d 株 / %d 次观察 / %d 张照片"
      % (base["plants"], base["observations"], base["images"]))
check("库里有可用于验证的数据", base["plants"] > 0, "库是空的")


# ============================================================ [1] 数据库 v2
#
# 验收点：Phase6+ 给 plant_record 加了两个新列，且**走的是显式迁移**而不是
# 破坏性重建。所以既要确认列存在，也要确认版本号真的升到了 2 ——
# 版本号才是「迁移被执行过」的证据，光看列存在有可能是别的路径建的库。

print("\n[1] 数据库：迁移到 v2 且新增两列")

db_path = os.path.join(os.environ.get("TEMP", "/tmp"), "plant_identify_db",
                       "plant_identify.db")
if os.path.isfile(db_path):
    con = sqlite3.connect(db_path)
    cur = con.cursor()
    version = cur.execute("PRAGMA user_version").fetchone()[0]
    cols = [r[1] for r in cur.execute("PRAGMA table_info(plant_record)")]
    con.close()

    check("数据库版本 = 2（显式迁移已执行）", version == 2, "实际 %s" % version)
    check("plant_record 含 commonNames 列", "commonNames" in cols)
    check("plant_record 含 pestControl 列", "pestControl" in cols)
else:
    check("拉取数据库", False, "找不到 %s" % db_path)


# ============================================================ [2] 统计页间距
#
# 问题 7：科与属的数字挨在一起，两个一位数看着像一个两位数。
# 根因是 TaxonomyCard 里那个带 weight 的 Spacer 吃掉了全部剩余宽度，
# 让 SpaceBetween 失效。
#
# 断言用「标签中心的 x 距离」而不是肉眼看截图 —— 数值化才挡得住回退。

print("\n[2] 统计页：科 / 属 各自占 1/3 等宽列（问题 7）")

# 首页 → 统计页这条路径偶发走不通（uiautomator 取树本身就会偶发失败，
# dump() 内部已重试 4 次，但整段导航仍可能整体落空）。
# 一次不成就再走一遍 —— 不要因为一次抖动就把「进入统计页」判成失败。
entered_stats = open_stats() or open_stats()
if entered_stats:
    check("进入统计页", True)

    root = dump()

    def center_of_label(r, label):
        if r is None:
            return None
        for n in r.iter("node"):
            if ntext(n) == label:
                return center(n)
        return None

    c_plant = center_of_label(root, "不同植物")
    c_obs = center_of_label(root, "观察次数")
    c_photo = center_of_label(root, "照片数")
    c_family = center_of_label(root, "科")
    c_genus = center_of_label(root, "属")

    check("取到三项主指标与科/属的标签",
          all([c_plant, c_obs, c_photo, c_family, c_genus]))

    if c_family and c_genus:
        gap = abs(c_genus[0] - c_family[0])
        ratio = gap / screen_w
        print("    科@x=%d  属@x=%d  间距=%dpx（屏宽的 %.2f）"
              % (c_family[0], c_genus[0], gap, ratio))
        # 修复前两者相差约 0.01 屏宽（几乎贴在一起）；修复后是 1/3 屏宽
        check("科与属不在同一位置（间距 > 1/5 屏宽）", ratio > 0.2,
              "间距仅 %.2f 屏宽，两者仍然是紧挨着的" % ratio)

        if c_plant and c_obs and c_photo:
            # 与上面三项主指标列对齐才是真正修好了 ——
            # 只把两者推开、却跟上一行错位，看起来还是乱的
            d1 = abs(c_family[0] - c_plant[0]) / screen_w
            d2 = abs(c_genus[0] - c_obs[0]) / screen_w
            print("    与上方「不同植物」偏差 %.3f 屏宽；与「观察次数」偏差 %.3f 屏宽"
                  % (d1, d2))
            check("科与「不同植物」列对齐", d1 < 0.05, "偏差 %.3f 屏宽" % d1)
            check("属与「观察次数」列对齐", d2 < 0.05, "偏差 %.3f 屏宽" % d2)

    shot("p6p-stats")
else:
    check("进入统计页", False, "流程未走通")


# ============================================================ [3] 定位（问题 1）
#
# 原始缺陷：位置权限的申请只挂在首次询问框的「允许」上。
# 用户在「数据管理」页直接打开开关时根本不经过那个框，于是权限永远拿不到、
# captureLocation() 静默 return —— 界面既不报错也没有任何地点。
#
# 这条断言就是要钉死「开关打开之后，权限真的到手了」。

print("\n[3] 定位：开关打开会真的拿到系统权限（问题 1）")

if open_data_management():
    check("进入数据管理页", True)

    scroll_top(8)
    # 用 find() 而不是 collect_all_text()：后者要逐屏 dump 十几次，
    # 任何一次取树抖动都会让整段文本缺一块，于是「页面上明明有这个开关」
    # 也会被判成不存在。find() 底层的 dump 自带 4 次重试，稳得多。
    label_found = False
    for _ in range(4):
        if find("记录观察地点") is not None:
            label_found = True
            break
        time.sleep(1.2)
    check("页面有「记录观察地点」开关", label_found)

    # collect_all_text() 扫完会把页面停在**底部** ——
    # 不回顶部就 dump，顶部那个开关根本不在树里，
    # 表现为「没找到已勾选的开关」，而开关好好地在上面。
    scroll_top(8)
    label = find("记录观察地点")
    label_y = center(label)[1] if label is not None else None

    root = dump()
    switch = None
    if root is not None:
        candidates = [n for n in root.iter("node") if n.get("checkable") == "true"]
        # 优先取「与该标签同一行」的那个开关（y 最接近）
        if label_y is not None and candidates:
            candidates.sort(key=lambda n: abs(center(n)[1] - label_y))
        for n in candidates:
            if n.get("checked") == "true":
                switch = n
                break

    check("位置开关处于开启状态", switch is not None,
          "没找到已勾选的开关节点")

    perm = shell("dumpsys", "package", PKG)
    granted = "ACCESS_COARSE_LOCATION: granted=true" in perm
    check("系统定位权限已授予（缺陷核心）", granted,
          "权限仍是 denied —— 说明打开开关并没有真正发起申请")

    if goto_home() and goto_add_plant():
        found_row = None
        for _ in range(8):
            t = screen_text()
            if "\U0001F4CD" in t:
                for line in t.split("\n"):
                    if line.startswith("\U0001F4CD"):
                        found_row = line
                        break
                if found_row:
                    break
            time.sleep(2)
        print("    地点行：%s" % (found_row or "（无）"))
        check("添加植物页显示地点行（含获取中/坐标/地名任一状态）",
              found_row is not None,
              "整页找不到 📍 —— 定位功能对用户仍然不可见")
        # 空草稿下也要显示：原来只有草稿里有照片时才渲染这一行
        check("空草稿（0 张照片）时地点行也存在",
              "已添加 0/" in screen_text() if found_row else False,
              "地点行只在有照片后才出现")
        shot("p6p-location-row")
    else:
        check("进入添加植物页", False, "流程未走通")
else:
    check("进入数据管理页", False, "流程未走通")


# ============================================================ [4] 照片大图（问题 2）
#
# 原来 LocalImage 从来没被包过 clickable，点缩略图毫无反应；
# 而原图存在应用私有目录，系统相册里根本看不到 —— 用户等于拿不到自己的照片。

print("\n[4] 照片：点击缩略图 → 全屏查看 → 保存到相册（问题 2）")

if goto_home():
    # 列表按 updatedAt 倒序，第一张卡片必然有照片（刚保存过观察）
    plants = db_snapshot()["name"]
    opened = False
    if plants:
        first_name = plants[0]["name"] if isinstance(plants[0], dict) else str(plants[0])
        if tap(first_name, exact=True, timeout=15):
            opened = wait_text("模型置信度", timeout=20) is not None

    check("打开一份植物档案", opened, "卡片点不开")

    if opened:
        scroll_top(10)
        target = None
        for _ in range(10):
            root = dump()
            if root is not None:
                for n in root.iter("node"):
                    desc = n.get("content-desc") or ""
                    if desc.startswith("\u690d\u7269\u7167\u7247"):
                        target = n
                        break
            # ⚠️ 必须写 `is not None`。
            # `if target:` 对 ElementTree 的元素取的是**子节点个数** ——
            # <node> 是叶子节点、没有子节点，于是恒为 False。
            # 后果不是报错，而是整个「查看器」验证段被**静默跳过**：
            # 连截图都不执行，汇总里也看不出少了什么。
            if target is not None:
                break
            scroll_down(1)
            time.sleep(1)

        check("找到照片缩略图", target is not None)

        if target is not None:
            c = center(target)
            shell("input", "tap", str(c[0]), str(c[1]))
            time.sleep(3)
            viewer = screen_text()
            check("点击后打开全屏查看器", "\u4fdd\u5b58\u5230\u76f8\u518c" in viewer,
                  "点了没反应")
            check("查看器提供「分享」", "\u5206\u4eab" in viewer)
            check("查看器可关闭", "\u5173\u95ed" in viewer)
            shot("p6p-image-viewer")

            # 保存到相册：数相册目录里的文件
            def gallery_count():
                raw = shell("ls", "/sdcard/Pictures/PlantIdentify")
                if "No such file" in raw or "not found" in raw.lower():
                    return 0
                return len([x for x in raw.split("\n") if x.strip()])

            before_gallery = gallery_count()
            saved = tap("\u4fdd\u5b58\u5230\u76f8\u518c", exact=True, timeout=10)
            time.sleep(6)
            after_gallery = gallery_count()
            print("    相册文件数：%d -> %d" % (before_gallery, after_gallery))
            check("保存到相册成功（相册目录 +1）",
                  saved and after_gallery == before_gallery + 1,
                  "点击=%s，相册 %d -> %d" % (saved, before_gallery, after_gallery))

            tap("\u5173\u95ed", exact=True, timeout=10)
            time.sleep(1.5)
        else:
            # 绝不能没有这个 else：没有它的话，上面四条断言会在「没找到缩略图」时
            # 一条都不打印 —— 汇总里数字照样好看，实际什么都没验。
            check("查看器可打开并保存到相册", False,
                  "没找到缩略图，整段查看器验证被跳过")
else:
    check("回到首页", False, "流程未走通")


# ============================================================ [5] 编辑（问题 6）
#
# 从「只能改 6 个字段」放开到「档案里所有给人看的内容都能改」，
# 外加照片的增删。

print("\n[5] 编辑：全部字段 + 照片增删（问题 6）")

# 判断「在不在详情页」要用**顶栏**的按钮：
# 上一段为了找照片已经把页面滚下去了，
# 而 screen_text() 只能看到当前可见屏 ——
# 拿「模型置信度」这种正文内容去判断必然误判。
scroll_top(8)
on_detail = find("\u7f16\u8f91", exact=True) is not None
if on_detail:
    if tap("\u7f16\u8f91", exact=True, timeout=10):
        time.sleep(2)
        if wait_text("\u57fa\u672c\u4fe1\u606f", timeout=15):
            check("进入编辑页", True)

            fields = collect_all_text()
            for label in ("\u5e38\u7528\u540d\u79f0 / \u4fd7\u79f0",
                          "AI \u7f6e\u4fe1\u5ea6\uff080\u2013100\uff0c\u53ef\u7559\u7a7a\uff09",
                          "\u75c5\u866b\u5bb3\u9632\u6cbb",
                          "\u690d\u7269\u767e\u79d1",
                          "\u7167\u7247",
                          "\u6dfb\u52a0\u7167\u7247"):
                check("编辑页有「%s」" % label, label in fields)

            shot("p6p-edit-fields")

            # 照片增删：滚到照片区，数一下删除按钮
            scroll_top(12)
            deletes = []
            for _ in range(14):
                root = dump()
                if root is not None:
                    hits = [n for n in root.iter("node")
                            if ntext(n) == "\u5220\u9664"]
                    if hits:
                        deletes = hits
                        break
                scroll_down(1)
                time.sleep(1)

            before = db_snapshot()
            print("    该观察可见的删除按钮：%d 个；库里照片 %d 张"
                  % (len(deletes), before["images"]))

            if len(deletes) >= 2:
                c = center(deletes[0])
                shell("input", "tap", str(c[0]), str(c[1]))
                time.sleep(5)
                after = db_snapshot()
                print("    删除后库里照片 %d 张" % after["images"])
                check("删一张照片后数据库行数 -1（且文件同步删除）",
                      after["images"] == before["images"] - 1,
                      "%d -> %d" % (before["images"], after["images"]))
            elif len(deletes) == 1:
                # 只剩最后一张：应当被拒绝，并给出原因
                c = center(deletes[0])
                shell("input", "tap", str(c[0]), str(c[1]))
                # Snackbar 默认只显示 4 秒，而 dump 本身要 1–2 秒 ——
                # sleep(4) 再 dump 恰好赶不上，会误报成「提示未出现」。
                # 必须短间隔反复抓。
                seen = ""
                for _ in range(10):
                    t = screen_text()
                    for line in t.split("\n"):
                        if "\u53ea\u5269\u8fd9\u4e00\u5f20" in line:
                            seen = line
                            break
                    if seen:
                        break
                    time.sleep(0.7)
                again = db_snapshot()
                print("    提示：%s" % (seen or "（未抓到）"))
                check("删最后一张被拒绝且给出原因",
                      bool(seen) and again["images"] == before["images"],
                      "提示未出现或行数变了（%s -> %s）"
                      % (before["images"], again["images"]))
            else:
                check("照片区出现删除入口", False, "一个删除按钮都没找到")
        else:
            check("进入编辑页", False, "页面没出现「基本信息」")
    else:
        check("点「编辑」", False, "找不到编辑按钮")
else:
    check("处于详情页", False, "上一步没停在详情页")


# ============================================================ [6] 别名与病虫害（问题 3、5）
#
# 两个新字段都来自**文字分析**通道（知识性内容由百科通道给，
# 不由视觉通道献），所以这里触发一次「重新生成」把 mock 的
# common_names / pest_control 写进库。
#
# 断言切口是「**刚刚分析的那一株**」，不是「库里有任意一株有俗称」。
# 后者会假通过 —— 只要库里早就有一株填过，即便本次分析完全没写进去，
# 断言也会绿。而分析会刷新 updatedAt，
# 所以「最近更新的那一行」正好就是本次的目标。

print("\n[6] AI 分析写入「俗称」与「病虫害防治」（问题 3、5）")


def latest_filled_row():
    """最近更新的那株档案 + 它的两个新字段"""
    con = sqlite3.connect(db_path)
    con.row_factory = sqlite3.Row
    row = con.execute(
        "SELECT id, name, commonNames, pestControl, updatedAt FROM plant_record "
        "ORDER BY updatedAt DESC LIMIT 1"
    ).fetchone()
    con.close()
    return row


if goto_home():
    plants = db_snapshot()["name"]
    target_name = plants[0]["name"] if plants and isinstance(plants[0], dict) else None

    if target_name and tap(target_name, exact=True, timeout=15):
        time.sleep(2)
        if "模型置信度" in screen_text():
            # 先把本次目标记下来 —— 分析完成后 updatedAt 会变，
            # 事后再取「最近更新」的就是它
            before_row = latest_filled_row()
            print("    本次目标档案 id=%s（分析前常规=%s）"
                  % (before_row["id"], before_row["commonNames"]))

            triggered = tap_scrolling("重新生成", max_scrolls=12, bottom=True)
            if not triggered:
                triggered = tap_scrolling("生成植物百科",
                                          max_scrolls=12, bottom=True)
            check("触发一次文字分析", triggered, "找不到「重新生成」")

            if triggered:
                filled = None
                # 判据里必须有 **updatedAt 前移**。
                # 只看「两个字段非空」是不够的：上一轮已经把这两列填上之后，
                # 这一次就算分析根本没跑（或跑了但没写库），第一次轮询也会满足条件 ——
                # 那是一个会一直「通过」、却什么都没验证的假断言。
                # updateAnalysis() 每次都会刷新 updatedAt，所以它是「这次真的写进去了」的证据。
                for _ in range(25):
                    time.sleep(4)
                    # 必须先重新拖一次库 ——
                    # 本地那份快照只在上一段拉过一次，
                    # 不重拖的话这个循环永远读的是旧数据，
                    # 设备上明明写进去了却报「等了 100 秒仍为空」。
                    # 《这是当前脚本最容易犯的错：把快照当成了实时视图》
                    db_snapshot()
                    row = latest_filled_row()
                    # 要求：同一株、且两个字段都有值
                    if (row["id"] == before_row["id"]
                            and row["updatedAt"] > before_row["updatedAt"]
                            and row["commonNames"]
                            and row["pestControl"]):
                        filled = row
                        break

                if filled:
                    print("    %s：俗称=%s" % (filled["name"], filled["commonNames"]))
                    print("    病虫害防治=%s" % (filled["pestControl"] or "")[:40])
                    check("俗称已写入刚分析的那株档案",
                          bool(filled["commonNames"]))
                    check("病虫害防治已写入刚分析的那株档案",
                          bool(filled["pestControl"]))

                    shown = collect_all_text()
                    check("详情页渲染「俗称」", "俗称" in shown)
                    check("详情页渲染「病虫害防治」",
                          "病虫害防治" in shown)
                    shot("p6p-alias-and-pest")
                else:
                    check("分析结果写入数据库", False,
                          "等了 100 秒，id=%s 仍未看到 updatedAt 前移且两个字段都有值"
                          % before_row["id"])
        else:
            check("进入详情页", False, "页面不对")
    else:
        check("打开植物档案", False, "卡片点不开")


# ============================================================ [7] HTML 导出（问题 3、4、5）
#
# 报告改成「每株一个按钮（编号 + 封面 + 名称/别名/学名），点开整屏看详情」。
# 断言只看三件事：结构在、新字段在、降级方案在。

print("\n[7] HTML 报告：折叠按钮 + 整屏弹窗（问题 4）")

EXPORT_DIR = "files/exports"

if open_data_management():
    check("进入数据管理页", True)

    scroll_top(12)

    # 页面上「导出 HTML」既是区块标题也是按钮。直接按文字点，点到的是标题
    # （什么都不会发生），所以要取**最靠下**的那个。
    #
    # 这一步偶发失败：某一屏里可能只渲染出标题、按钮还在视野外。
    # 所以做成「点一次 → 等完成信号 → 没等到就回顶部再点一次」。
    # 只重试一次，避免把「导出真的坏了」也拖成十分钟。
    def tap_export():
        scroll_top(12)
        return tap_scrolling("\u5bfc\u51fa HTML", max_scrolls=12, bottom=True)

    exported = False
    if tap_export():
        check("触发导出", True)
        # 等应用自己说完成：结果卡片带「分享」，且会一直留在页面上。
        # 不去轮询文件体积 —— 同一批数据导出是确定性的（同名同大小），
        # 按体积变化检测不到重写，会误报成「没产出文件」
        exported = wait_text_anywhere("\u5206\u4eab", timeout=150)
        if not exported:
            print("    （第一次点击后 150 秒未见完成信号，回顶部重试一次）")
            if tap_export():
                exported = wait_text_anywhere("\u5206\u4eab", timeout=180)
        check("导出完成（结果卡片出现）", exported,
              "两次点击都没等到结果卡片")

        names = [n for n in device_ls(EXPORT_DIR) if n.endswith(".html")]
        local = os.path.join(os.environ.get("TEMP", "/tmp"), "p6p_report.html")
        if names:
            pull_binary("%s/%s" % (EXPORT_DIR, names[0]), local)
            raw = open(local, "rb").read()
            print("    报告：%s（%.2f MB）" % (names[0], len(raw) / 1048576.0))

            try:
                html = raw.decode("utf-8")
                check("整份 HTML 可按 UTF-8 解码（中文不乱码）", True)
            except UnicodeDecodeError as err:
                html = ""
                check("整份 HTML 可按 UTF-8 解码（中文不乱码）", False, str(err))

            if html:
                heads = html.count('class="plant-head"')
                bodies = html.count('class="plant-body"')
                plants = db_snapshot()["plants"]

                check("每株植物一个折叠按钮（%d 个）" % heads, heads == plants,
                      "按钮 %d 个，档案 %d 株" % (heads, plants))
                check("每株植物一份弹窗内容", bodies == plants,
                      "弹窗 %d 个，档案 %d 株" % (bodies, plants))

                thumbs = len(re.findall(r'class="thumb">\s*<img', html))
                check("按钮上有封面缩略图", thumbs > 0, "一个缩略图都没有")

                check("按钮里含编号", 'class="idx"' in html)
                check("弹窗有整屏样式", "position: fixed" in html)
                check("有打开/关闭的脚本", html.count("addEventListener") >= 2)
                check("禁用 JS 时内容自动摊平（noscript 兜底）", "<noscript>" in html)
                check("打印时内容自动摊平", "@media print" in html)

                check("报告含「常用名称 / 俗称」字段",
                      "\u5e38\u7528\u540d\u79f0 / \u4fd7\u79f0" in html)
                check("报告含「病虫害防治」字段",
                      "\u75c5\u866b\u5bb3\u9632\u6cbb" in html)

                leftover = len(re.findall(r'<section class="plant">\s*<h2', html))
                check("没有残留旧的直出结构", leftover == 0, "%d 处" % leftover)
                check("没有二次转义", "&amp;lt;" not in html)

                # 留一份到工作区，方便直接用浏览器打开核验
                # 落进 OUT_DIR（工作区里已 gitignore 的目录）。
                # 别用 dirname(OUT_DIR) —— 那会把 1.8 MB 的样例写到仓库根目录，
                # 一不小心就被 git add 进去。
                sample = os.path.join(OUT_DIR, "p6p-report-sample.html")
                with open(sample, "wb") as f:
                    f.write(raw)
                print("    样例已留：%s" % sample)
        else:
            check("导出目录里有 HTML 文件", False, "找不到 .html")
    else:
        check("触发导出", False, "找不到按钮")
else:
    check("进入数据管理页", False, "流程未走通")


# ============================================================ 汇总

print("\n" + "=" * 60)
print("合计：通过 %d，失败 %d" % (passed, failed))
if failed:
    print("❌ 有未通过项")
else:
    print("✅ Phase6+ 七项修复全部通过")
sys.exit(1 if failed else 0)
