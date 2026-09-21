"""
在**不连设备**的前提下，验证手写迁移与 Room 生成的 schema 是否一致。

## 为什么需要它

`Migration.migrate()` 里是手写 SQL，Room 只在**用户设备上**用它 ——
构建期既不执行也不校验。列名写错、漏一个索引、外键写反，编译全绿灯，
等用户升级时才抛 `IllegalStateException: Migration didn't properly handle`，
而那时唯一的补救是发新版 + 让用户重装（档案就没了）。

## 它怎么验

1. 从 `Migrations.kt` 里按顺序抽出所有 `execSQL` 的 SQL
2. 在一个**内存 SQLite** 上依次执行（等价于老用户从 v1 一路升到最新版）
3. 把结果表结构与 `app/schemas/<最新版本>.json` 比对

比对口径**照抄 Room 的 `TableInfo` 语义**，而不是比 DDL 文本：

| 维度 | 说明 |
|---|---|
| 列 | 名称 + 类型亲和性 + 是否 NOT NULL + 主键位置 |
| 索引 | 名称 + 是否唯一 + 列顺序（忽略 SQLite 自动建的 `sqlite_autoindex_*`） |
| 外键 | 目标表 + 列 + ON DELETE / ON UPDATE |

比文本更正确：老表是 v1 建的、后续版本用 `ALTER TABLE ADD COLUMN` 加列，
最终 DDL 文本与 Room 的 `createSql` **必然不同**，但解析后的结构是一致的 ——
而 Room 运行时比的就是解析后的结构。

## 它验不到什么

- **真实数据**。老用户库里是几十株档案 + 几十张照片，
  这里只验「空库能不能升上去」。字段的语义错误（比如新列默认值选错）
  只有真机覆盖安装才看得出来 —— 见 `android-room-migration` 技能
- **Android 与 Python 的 SQLite 版本差异**。两边都是 SQLite 3，
  PRAGMA 输出一致；但若哪天用到了新语法，仍需真机确认
"""
import io
import json
import re
import sqlite3
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MIGRATIONS = ROOT / "app/src/main/java/com/plantidentify/data/local/Migrations.kt"
SCHEMA_DIR = ROOT / "app/schemas"


# --------------------------------------------------------------------- 解析

def _extract_sql_calls(body: str) -> list[str]:
    """抽出 `body` 里所有 execSQL 的参数（括号配平扫描）。

    **不能用正则**：`execSQL("...")` 与 `execSQL("...",)` 两种写法都存在于
    这个文件里，而字符串内部又有大量括号与逗号。正则会在第一种写法上
    找不到「逗号 + 右括号」，于是一路吞到下一个语句 —— 表现成
    「三条 CREATE INDEX 被拼成一条 SQL」，报出一堆假的语法错误。
    """
    out: list[str] = []
    index = 0
    while True:
        start = body.find("execSQL(", index)
        if start < 0:
            break
        cursor = start + len("execSQL(")
        depth = 1
        in_string = False
        while cursor < len(body) and depth > 0:
            ch = body[cursor]
            if in_string:
                if ch == "\\":
                    cursor += 2
                    continue
                if ch == '"':
                    in_string = False
            elif ch == '"':
                in_string = True
            elif ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
                if depth == 0:
                    break
            cursor += 1
        args = body[start + len("execSQL("):cursor]
        parts = re.findall(r'"((?:[^"\\]|\\.)*)"', args)
        if parts:
            sql = "".join(parts).replace('\\"', '"').replace("\\\\", "\\")
            out.append(" ".join(sql.split()))
        index = cursor + 1
    return out


def migration_sql_in_order() -> list[tuple[str, str]]:
    """按文件顺序抽出 (迁移名, SQL)。

    迁移名只用于出错时定位；执行顺序由文件中的声明顺序决定，
    而声明顺序应与 `ALL` 数组一致（脚本会核对这一点）。
    """
    text = io.open(MIGRATIONS, encoding="utf-8").read()

    blocks = list(re.finditer(
        r"val\s+(MIGRATION_\d+_\d+)\s*:\s*Migration\s*=\s*object\s*:\s*Migration\((\d+),\s*(\d+)\)",
        text,
    ))
    out: list[tuple[str, str]] = []
    for i, block in enumerate(blocks):
        end = blocks[i + 1].start() if i + 1 < len(blocks) else len(text)
        for sql in _extract_sql_calls(text[block.end():end]):
            out.append((block.group(1), sql))
    return out


def migration_chain_declared() -> list[str]:
    """`ALL = arrayOf(...)` 里声明的迁移链，用于核对顺序没写乱"""
    text = io.open(MIGRATIONS, encoding="utf-8").read()
    m = re.search(r"val\s+ALL\s*:\s*Array<Migration>\s*=\s*arrayOf\((.*?)\)", text, re.S)
    if not m:
        return []
    return re.findall(r"(MIGRATION_\d+_\d+)", m.group(1))


def schema_files() -> list[Path]:
    return sorted(SCHEMA_DIR.glob("*/[0-9]*.json"), key=lambda p: int(p.stem))


def read_schema(path: Path) -> dict:
    return json.load(io.open(path, encoding="utf-8"))["database"]


def base_version(statements: list[tuple[str, str]]) -> int:
    """迁移链的起点版本 = 序号最小的那个迁移的 `from`。

    起点版本的表结构**不在 Migrations.kt 里** —— 它是 Room 首次建库时
    按当时的实体定义直接建的（`createAllTables`）。所以模拟升级必须
    先从对应的 `<版本>.json` 把库建起来，再往上跑迁移。
    """
    versions = [int(name.split("_")[1]) for name, _ in statements]
    return min(versions)


def create_sql_of(db_schema: dict) -> list[str]:
    """某个版本 schema 的全部建表/建索引 SQL"""
    out: list[str] = []
    for entity in db_schema["entities"]:
        table = entity["tableName"]
        out.append(entity["createSql"].replace("${TABLE_NAME}", table))
        for index in entity.get("indices") or []:
            out.append(index["createSql"].replace("${TABLE_NAME}", table))
    return out


def latest_schema() -> tuple[Path, dict]:
    files = schema_files()
    if not files:
        sys.exit("!! app/schemas 下没有找到 schema JSON")
    path = files[-1]
    return path, read_schema(path)


# --------------------------------------------------------------------- 比对

def affinity(declared_type: str) -> str:
    """SQLite 的类型亲和性规则（与 Room 的 `Column.typeAffinity` 一致）"""
    t = (declared_type or "").upper()
    if "INT" in t:
        return "INTEGER"
    if "CHAR" in t or "CLOB" in t or "TEXT" in t:
        return "TEXT"
    if "BLOB" in t or t == "":
        return "BLOB"
    if "REAL" in t or "FLOA" in t or "DOUB" in t:
        return "REAL"
    return "NUMERIC"


def actual_columns(conn: sqlite3.Connection, table: str) -> dict:
    rows = conn.execute(f"PRAGMA table_info(`{table}`)").fetchall()
    return {
        r[1]: {
            "affinity": affinity(r[2]),
            "notNull": bool(r[3]),
            "pk": r[5],
        }
        for r in rows
    }


def actual_indices(conn: sqlite3.Connection, table: str) -> dict:
    result = {}
    for row in conn.execute(f"PRAGMA index_list(`{table}`)").fetchall():
        name, unique = row[1], bool(row[2])
        if name.startswith("sqlite_autoindex"):
            continue
        cols = [r[2] for r in conn.execute(f"PRAGMA index_info(`{name}`)").fetchall()]
        result[name] = {"unique": unique, "columns": cols}
    return result


def actual_foreign_keys(conn: sqlite3.Connection, table: str) -> set:
    keys = set()
    for row in conn.execute(f"PRAGMA foreign_key_list(`{table}`)").fetchall():
        # (id, seq, table, from, to, on_update, on_delete, match)
        keys.add((row[2], row[3], row[4], row[5].upper(), row[6].upper()))
    return keys


def expected_columns(entity: dict) -> dict:
    pk_names = (entity.get("primaryKey") or {}).get("columnNames", [])
    result = {}
    for field in entity["fields"]:
        name = field["columnName"]
        result[name] = {
            "affinity": field["affinity"],
            "notNull": bool(field.get("notNull", False)),
            "pk": (pk_names.index(name) + 1) if name in pk_names else 0,
        }
    return result


def expected_indices(entity: dict) -> dict:
    return {
        i["name"]: {"unique": bool(i.get("unique", False)), "columns": list(i["columnNames"])}
        for i in entity.get("indices") or []
    }


def expected_foreign_keys(entity: dict) -> set:
    return {
        (
            fk["table"],
            fk["columns"][0],
            fk["referencedColumns"][0],
            fk.get("onUpdate", "NO ACTION").upper(),
            fk.get("onDelete", "NO ACTION").upper(),
        )
        for fk in entity.get("foreignKeys") or []
    }


# --------------------------------------------------------------------- 主流程

def main() -> int:
    schema_path, schema = latest_schema()
    entities = {e["tableName"]: e for e in schema["entities"]}

    statements = migration_sql_in_order()
    if not statements:
        sys.exit("!! 没能从 Migrations.kt 中解析出任何 execSQL")

    start_version = base_version(statements)
    start_files = {int(p.stem): p for p in schema_files()}
    if start_version not in start_files:
        sys.exit(f"!! 缺少起点版本的 schema：{start_version}.json")
    baseline = read_schema(start_files[start_version])

    declared = migration_chain_declared()
    actual_chain = []
    for name, _ in statements:
        if not actual_chain or actual_chain[-1] != name:
            actual_chain.append(name)
    problems: list[str] = []
    if declared and declared != actual_chain:
        problems.append(
            f"ALL 数组的顺序与文件中的声明顺序不一致：\n"
            f"     ALL     = {declared}\n"
            f"     文件中  = {actual_chain}",
        )

    print(f"schema 文件    : {schema_path.relative_to(ROOT)}  (version {schema['version']})")
    print(f"起点           : {start_version}.json（Room 首次建库时的表结构）")
    print(f"迁移链         : {' → '.join(actual_chain)}")
    print(f"迁移语句       : {len(statements)} 条")
    print(f"schema 中的表  : {len(entities)} 张")
    print()

    conn = sqlite3.connect(":memory:")
    conn.execute("PRAGMA foreign_keys = ON")
    try:
        # ① 先把库建到起点版本（等价于用户手里那个老库）
        for sql in create_sql_of(baseline):
            conn.execute(sql)
        conn.commit()

        # ② 依次跑迁移
        for i, (name, sql) in enumerate(statements, 1):
            try:
                conn.execute(sql)
            except sqlite3.Error as exc:
                problems.append(f"{name} 的第 {i} 条语句执行失败：{exc}\n     {sql}")
        conn.commit()

        actual_tables = {
            r[0] for r in conn.execute(
                "SELECT name FROM sqlite_master WHERE type='table' "
                "AND name NOT LIKE 'sqlite_%' AND name <> 'room_master_table'",
            ).fetchall()
        }

        missing = sorted(set(entities) - actual_tables)
        extra = sorted(actual_tables - set(entities))
        if missing:
            problems.append(f"迁移建不出来的表：{missing}")
        if extra:
            problems.append(f"迁移建了但 schema 里没有的表：{extra}")

        for table in sorted(set(entities) & actual_tables):
            entity = entities[table]
            exp_cols, act_cols = expected_columns(entity), actual_columns(conn, table)

            for col in sorted(set(exp_cols) | set(act_cols)):
                e, a = exp_cols.get(col), act_cols.get(col)
                if e is None:
                    problems.append(f"{table}.{col}：迁移建了，schema 里没有")
                elif a is None:
                    problems.append(f"{table}.{col}：schema 里有，迁移没建")
                elif e != a:
                    problems.append(
                        f"{table}.{col} 不一致：\n"
                        f"     schema 期望 {e}\n"
                        f"     迁移实际   {a}",
                    )

            exp_idx, act_idx = expected_indices(entity), actual_indices(conn, table)
            for name in sorted(set(exp_idx) | set(act_idx)):
                e, a = exp_idx.get(name), act_idx.get(name)
                if e is None:
                    problems.append(f"{table} 索引 {name}：迁移建了，schema 里没有")
                elif a is None:
                    problems.append(f"{table} 索引 {name}：schema 里有，迁移没建")
                elif e != a:
                    problems.append(
                        f"{table} 索引 {name} 不一致：\n"
                        f"     schema 期望 {e}\n"
                        f"     迁移实际   {a}",
                    )

            exp_fk, act_fk = expected_foreign_keys(entity), actual_foreign_keys(conn, table)
            if exp_fk != act_fk:
                problems.append(
                    f"{table} 外键不一致：\n"
                    f"     schema 期望 {sorted(exp_fk)}\n"
                    f"     迁移实际   {sorted(act_fk)}",
                )
    finally:
        conn.close()

    if problems:
        print(f"✗ 发现 {len(problems)} 处不一致：\n")
        for p in problems:
            print("  -", p)
        print()
        print("列名/类型/非空/主键/索引/外键必须与 Room 生成的完全一致，")
        print("否则用户设备上的迁移会失败 —— 而构建期发现不了。")
        return 1

    print("✓ 迁移链执行后的表结构与 schema 完全一致")
    print("  （列名/亲和性/非空/主键位置、索引与其唯一性与列序、外键与级联动作）")
    print()
    print("提醒：这只证明「空库能升上来」。有旧数据的设备上覆盖安装")
    print("      仍需实测一次 —— 见 skill android-room-migration")
    return 0


if __name__ == "__main__":
    sys.exit(main())
