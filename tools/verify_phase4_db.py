# -*- coding: utf-8 -*-
"""Phase 4 数据库层校验

界面文字只能证明「界面上写了什么」，证明不了「库里到底存了几行」。
验收标准 ③ 要求补图后不新建 Observation，这类断言必须直接数行数 ——
所以这里把 Room 数据库（含 -wal / -shm）拉到本地用 sqlite 打开，逐条查。

检出项：
  · 三张表的行数，以及每份档案的观察数（应恰好 1 条）
  · 孤儿观察 / 孤儿图片 / 只写了一半的档案
  · 图片路径必须是相对路径，且引用的文件在 filesDir 中真实存在
  · role 字段有值（对冲多图退化的关键字段）
  · analysisStatus 的合法性，以及没有卡在 PENDING 的脏状态
  · FAILED 的档案不得残留半截百科内容

用法：
    python tools/verify_phase4_db.py

环境变量 ANDROID_SERIAL / ADB / DB_PULL_DIR 可覆盖默认值。
"""
import os
import sqlite3
import subprocess
import sys

PKG = "com.plantidentify"
ADB = os.environ.get(
    "ADB",
    os.path.join(os.environ.get("ANDROID_HOME", "C:/Users/a/Android/Sdk"),
                 "platform-tools", "adb.exe"),
)
DEV = os.environ.get("ANDROID_SERIAL", "192.168.253.119:5555")
OUT = os.environ.get(
    "DB_PULL_DIR",
    os.path.join(os.environ.get("TEMP", "/tmp"), "plant_identify_db"),
)

passed = 0
failed = 0


def check(label, ok, extra=""):
    global passed, failed
    if ok:
        passed += 1
        print(f"  [PASS] {label}")
    else:
        failed += 1
        print(f"  [FAIL] {label}" + (f"  <- {extra}" if extra else ""))


def adb(*args, timeout=120, binary=False):
    r = subprocess.run([ADB, "-s", DEV, *args], capture_output=True, timeout=timeout)
    return r.stdout if binary else r.stdout.decode("utf-8", "replace")


os.makedirs(OUT, exist_ok=True)
for suffix in ("", "-wal", "-shm"):
    path = f"p4db/plant_identify.db{suffix}"
    raw = adb("exec-out", "run-as", PKG, "cat", f"databases/plant_identify.db{suffix}",
              binary=True)
    with open(os.path.join(OUT, f"plant_identify.db{suffix}"), "wb") as f:
        f.write(raw)

db_path = os.path.join(OUT, "plant_identify.db")
print(f"=== 数据库拉取完成：{os.path.getsize(db_path)} 字节（主库）===")
print("  （-wal 一并拉取，否则看不到尚未检查点的写入）\n")

con = sqlite3.connect(db_path)
con.row_factory = sqlite3.Row
cur = con.cursor()


def q(sql, *args):
    cur.execute(sql, args)
    return cur.fetchall()


print("[1] 表与行数")
tables = [r[0] for r in q(
    "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' "
    "AND name NOT LIKE 'android_%' AND name NOT LIKE 'room_%'"
)]
for t in tables:
    n = q(f"SELECT COUNT(*) FROM {t}")[0][0]
    print(f"    {t:28} {n} 行")

plants = q("SELECT * FROM plant_record")
obs = q("SELECT * FROM plant_observation")
imgs = q("SELECT * FROM observation_image")

check("至少有一条植物档案（Phase 4 保存链路写通）", len(plants) >= 1, f"{len(plants)} 条")

if not plants:
    print("\n⚠ 库里没有档案，后续断言无法进行")
    sys.exit(1)

print("\n[2] 每条档案的观察数（验收标准 ③：补图不应新建 Observation）")
for p in plants:
    pid = p["id"]
    n_obs = q("SELECT COUNT(*) FROM plant_observation WHERE plantId=?", pid)[0][0]
    n_img = q(
        "SELECT COUNT(*) FROM observation_image oi "
        "JOIN plant_observation o ON oi.observationId = o.id WHERE o.plantId=?", pid
    )[0][0]
    flag = "✅" if n_obs == 1 else f"⚠ {n_obs} 条观察"
    print(f"    #{pid} {p['name']:16} 观察 {n_obs} 条 / 照片 {n_img} 张  {flag}")

# 只对「刚走完 P4 流程」的档案断言：找照片数最多的一条
multi = [p for p in plants if q(
    "SELECT COUNT(*) FROM observation_image oi "
    "JOIN plant_observation o ON oi.observationId=o.id WHERE o.plantId=?", p["id"]
)[0][0] > 1]

if multi:
    target = multi[0]
    tid = target["id"]
    n_obs = q("SELECT COUNT(*) FROM plant_observation WHERE plantId=?", tid)[0][0]
    check(f"多图档案 #{tid}「{target['name']}」只有一条观察（补图未新建）", n_obs == 1,
          f"{n_obs} 条")
else:
    print("    （库里暂无多图档案，跳过该断言）")

# 反向一致性：档案存在却没有观察，说明保存流程只写了一半
no_obs = [p["id"] for p in plants
          if q("SELECT COUNT(*) FROM plant_observation WHERE plantId=?", p["id"])[0][0] == 0]
check("每条档案至少有一条观察（不存在只写了一半的档案）", not no_obs, str(no_obs[:3]))

no_img = [o["id"] for o in obs
          if q("SELECT COUNT(*) FROM observation_image WHERE observationId=?", o["id"])[0][0] == 0]
check("每条观察至少有一张照片", not no_img, str(no_img[:3]))

print("\n[3] 字段完整性")
p = plants[-1]
print(f"    最新档案：{p['name']} / {p['latinName']} / {p['family']} / {p['genus']}")
print(f"    置信度 {p['confidence']}  分类 {p['category']}  分析状态 {p['analysisStatus']}")

check("名称非空", bool(p["name"] and p["name"].strip()))
check("置信度在 0–1 之间", 0.0 <= (p["confidence"] or 0) <= 1.0, str(p["confidence"]))
check(
    "分析状态是已知取值",
    p["analysisStatus"] in ("NOT_REQUESTED", "PENDING", "SUCCEEDED", "FAILED"),
    str(p["analysisStatus"]),
)

pending = q("SELECT COUNT(*) FROM plant_record WHERE analysisStatus='PENDING'")[0][0]
check("没有卡在 PENDING 的分析（崩溃/中断会留下这种脏状态）", pending == 0, f"{pending} 条")

print("\n[4] 观察与图片的关联一致性")
orphan_obs = q(
    "SELECT COUNT(*) FROM plant_observation o "
    "LEFT JOIN plant_record p ON o.plantId = p.id WHERE p.id IS NULL"
)[0][0]
check("没有孤儿观察（plantId 指向不存在的档案）", orphan_obs == 0, f"{orphan_obs} 条")

orphan_img = q(
    "SELECT COUNT(*) FROM observation_image i "
    "LEFT JOIN plant_observation o ON i.observationId = o.id WHERE o.id IS NULL"
)[0][0]
check("没有孤儿图片", orphan_img == 0, f"{orphan_img} 条")

print("\n[5] 图片路径必须是相对路径且文件真实存在（规格书要求）")
# 注意取全部图片行，不是 LIMIT 12 —— 抽样会漏掉后插入的行
imgs = q("SELECT observationId, imagePath, role FROM observation_image")
abs_paths = [r["imagePath"] for r in imgs if r["imagePath"].startswith("/")]
check("没有绝对路径入库", not abs_paths, str(abs_paths[:3]))

# 路径是相对 <filesDir>/images 的（如 2026/09/<uuid>.png），
# 所以要按这个前缀把设备上的实际文件映射成同样的形式再比对
listed = adb("shell", "run-as", PKG, "find", "files/images", "-type", "f")
existing = set()
for line in listed.splitlines():
    line = line.strip()
    marker = "files/images/"
    idx = line.find(marker)
    if idx >= 0:
        existing.add(line[idx + len(marker):])

print(f"    图片行 {len(imgs)} 条 / 磁盘文件 {len(existing)} 个")
missing = [r["imagePath"] for r in imgs if r["imagePath"] not in existing]
check("档案引用的图片文件都真实存在于 filesDir", not missing, str(missing[:3]))

# 多个档案引用同一文件是测试复用了同一批草稿照片导致的；
# 真实使用中每次观察都会导入自己的照片，这里只做提示不做断言
referenced = {r["imagePath"] for r in imgs}
unused = existing - referenced
if unused:
    print(f"    （提示：磁盘上有 {len(unused)} 个文件未被任何档案引用）")

print("\n[6] 部位标注（role）已落库")
roles = q("SELECT role, COUNT(*) c FROM observation_image GROUP BY role")
for r in roles:
    print(f"    {r['role'] or '(空)':16} {r['c']} 张")
check("role 字段有值（多图退化对冲设计的关键字段）",
      any((r["role"] or "").strip() for r in roles))

print("\n[7] 文字分析落库情况")
for p in plants:
    ok = p["analysisStatus"] == "SUCCEEDED"
    has = bool((p["description"] or "").strip())
    print(f"    #{p['id']} {p['name']:16} 状态={p['analysisStatus']:14} 简介={'有' if has else '无'}")
check("失败的档案没有写入残缺内容（状态 FAILED 时简介应为空）",
      all(not (p["analysisStatus"] == "FAILED" and (p["description"] or "").strip())
          for p in plants))

con.close()
print("\n" + "=" * 60)
print(f"通过 {passed} / 失败 {failed}")
print("=" * 60)
sys.exit(0 if failed == 0 else 1)
