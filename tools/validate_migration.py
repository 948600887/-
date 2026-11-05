#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
【离线迁移校验 · 无设备】解析 StockDatabase.kt 的迁移 SQL，按 1.json 建 in-memory 库，
应用全部迁移后与**最新导出 schema** 做「双向完全一致」比对。

## 为什么必须自动发现版本（2026-11 体检 P1 修复）
旧实现把 `TARGET_VERSION = 17` 写死，`main()` 只 load 17.json、从不加载 18.json，
于是 17→18 的 `ALTER TABLE assets ADD COLUMN isEarnLocked ...` 列名/类型/NOT NULL/DEFAULT
写错照样打印 `VALIDATION PASSED: v1 -> v17 ...` —— 一个会假绿的防线比没有防线更危险。
现在：
  - 目标版本 = ROOT 下数字文件名最大的 `*.json`（自动发现）；
  - 同时从 `@Database(version = N)` 解析 DB 版本，两者不一致**直接报错退出**；
  - 校验覆盖正向（缺表/缺列/类型/notNull/索引）与反向（多余表/多余列/多余索引）。

## 默认值为何单独处理
Room 导出的 18.json 里 `assets.isEarnLocked` 的 `fields[].defaultValue` 是 null，
`createSql` 也只写了 `INTEGER NOT NULL`（实体未声明 @ColumnInfo(defaultValue)），
但 MIGRATION_17_18 的 DDL 明确写了 `DEFAULT 0`。也就是说 **JSON 无法作为 DEFAULT 的基准**。
因此 `validate()` 只在 JSON 真的带 defaultValue 时才比对 PRAGMA dflt_value（做引号/括号归一化），
不带就如实跳过、绝不弱化其它断言；另由 [check_17_18_column] 对「从 17.json 建库、只跑
MIGRATION_17_18」这条路径单独断言 `isEarnLocked` 存在 / INTEGER / NOT NULL / DEFAULT 0。

## 与 androidTest 的关系
`MigrationTest.kt` 用 Room `MigrationTestHelper`，需要真机/模拟器，门禁里只编译不执行。
本脚本是**无设备环境下唯一**会真正执行迁移 DDL 并断言 schema 的防线，只需 python3。

用法： python tools/validate_migration.py
"""
import glob
import json
import os
import re
import sqlite3
import sys

ROOT = "app/schemas/com.example.stockdiary.database.StockDatabase"
DB_SRC = "app/src/main/java/com/example/stockdiary/database/StockDatabase.kt"

# 反向「多余对象」检查的白名单：这些是 SQLite / Room 自己维护的内部对象，
# 不属于 schema JSON 里的 entities，不能当成迁移引入的漂移。
INTERNAL_TABLES = frozenset({"room_master_table"})
INTERNAL_INDEX_PREFIXES = ("sqlite_autoindex_",)

# 17→18 是本次假绿漏洞的具体修复点，历史上不会再改；只要 17.json 与该迁移还在就一直验。
COLUMN_CHECK_FROM_VERSION = 17
COLUMN_CHECK_TO_VERSION = 18
COLUMN_CHECK_TABLE = "assets"
COLUMN_CHECK_COLUMN = "isEarnLocked"
COLUMN_CHECK_TYPE = "INTEGER"
COLUMN_CHECK_DEFAULT = "0"


def discover_latest_version(root=ROOT):
    """扫描 ROOT 下 `*.json`，返回数字文件名最大的版本。

    自动发现取代写死的 TARGET_VERSION：升 DB 版本时只要 Room 导出新 JSON，
    校验目标就自动跟上，不再依赖「改版本时记得同步」的人工约定。
    """
    versions = []
    for path in glob.glob(os.path.join(root, "*.json")):
        stem = os.path.splitext(os.path.basename(path))[0]
        if stem.isdigit():
            versions.append(int(stem))
    if not versions:
        raise RuntimeError(
            f"在 {root} 下找不到任何数字版本的 schema JSON，无法校验迁移"
        )
    return max(versions)


def parse_db_version(path=DB_SRC):
    """从 StockDatabase.kt 的 @Database(...) 注解块里解析 version = N。

    只在注解括号内找，避免误命中源码其它位置的 `version = ...`。
    """
    src = open(path, encoding="utf-8").read()
    anno = re.search(r"@Database\s*\(", src)
    if not anno:
        raise RuntimeError(f"{path} 里找不到 @Database 注解")
    # 从 '(' 起做括号配对，取注解块内部文本
    depth = 0
    end = None
    for i in range(anno.end() - 1, len(src)):
        if src[i] == "(":
            depth += 1
        elif src[i] == ")":
            depth -= 1
            if depth == 0:
                end = i
                break
    if end is None:
        raise RuntimeError(f"{path} 的 @Database(...) 括号不配对")
    m = re.search(r"\bversion\s*=\s*(\d+)", src[anno.end():end])
    if not m:
        raise RuntimeError(f"{path} 的 @Database 注解里找不到 version = N")
    return int(m.group(1))


def load_schema(v, root=ROOT):
    """加载指定版本的 Room 导出 schema JSON。"""
    return json.load(open(os.path.join(root, f"{v}.json"), encoding="utf-8"))


def build_from_schema(conn, sch):
    """按 schema JSON 的 createSql 建库（含索引）。v1 与任意版本都适用。"""
    cur = conn.cursor()
    for e in sch["database"]["entities"]:
        sql = e["createSql"].replace("${TABLE_NAME}", e["tableName"])
        cur.execute(sql)
        for idx in (e.get("indices") or []):
            cols = ", ".join(f"`{c}`" for c in idx["columnNames"])
            cur.execute(
                f"CREATE {'UNIQUE ' if idx.get('unique') else ''}INDEX IF NOT EXISTS "
                f"`{idx['name']}` ON `{e['tableName']}` ({cols})"
            )
    conn.commit()


def build_v1(conn, sch):
    """兼容入口：validate_migration_data.py / qa_schema_strict.py 仍 import 本函数。

    语义等价于 [build_from_schema]，保留名字以免破坏既有工具。
    """
    return build_from_schema(conn, sch)


def extract_strings(arg):
    """从 Kotlin 调用实参文本里抽出字符串字面量（支持 \"...\" 与 triple-quote）。"""
    i = 0
    n = len(arg)
    res = []
    while i < n:
        if arg[i:i + 3] == '"""':
            end = arg.find('"""', i + 3)
            if end == -1:
                break
            res.append(arg[i + 3:end])
            i = end + 3
            continue
        if arg[i] == '"':
            j = i + 1
            buf = ""
            while j < n:
                if arg[j] == "\\":
                    buf += arg[j:j + 2]
                    j += 2
                    continue
                if arg[j] == '"':
                    break
                buf += arg[j]
                j += 1
            res.append(buf)
            i = j + 1
            continue
        i += 1
    return res


def scan_execsql(text):
    """扫描一段 Kotlin 源码，返回全部 execSQL(...) 的字符串实参。"""
    stmts = []
    for m in re.finditer(r"execSQL\s*\(\s*", text):
        i = m.end()
        depth = 1
        j = i
        while j < len(text):
            c = text[j]
            if c == '"' and text[j:j + 3] == '"""':
                end = text.find('"""', j + 3)
                j = len(text) if end == -1 else end + 3
                continue
            if c == '"':
                j2 = j + 1
                while j2 < len(text) and text[j2] != '"':
                    if text[j2] == "\\":
                        j2 += 2
                    else:
                        j2 += 1
                j = j2 + 1
                continue
            if c == "(":
                depth += 1
            elif c == ")":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        s = "".join(extract_strings(text[i:j]))
        if s.strip():
            stmts.append(s)
    return stmts


def extract_execsql(path=DB_SRC):
    """提取整条迁移链（全部 execSQL 语句），供 v1 -> latest 全链校验使用。"""
    src = open(path, encoding="utf-8").read()
    return scan_execsql(src)


def extract_migration_block(path, from_v, to_v):
    """只提取 MIGRATION_{from_v}_{to_v} 声明块内的 execSQL。

    用 `private val MIGRATION_a_b = object :` 精确定位声明（注释里也出现过同名字符串，
    匹配裸名字会串味），块尾是下一条迁移声明或文件末尾。用于「从 17.json 建库、
    只跑 MIGRATION_17_18」这条独立路径，避免被更早的迁移掩盖。
    """
    src = open(path, encoding="utf-8").read()
    decl = re.compile(
        r"(?m)^\s*private val MIGRATION_(\d+)_(\d+)\s*=\s*object\s*:"
    )
    matches = list(decl.finditer(src))
    for i, m in enumerate(matches):
        if (int(m.group(1)), int(m.group(2))) == (from_v, to_v):
            start = m.end()
            end = matches[i + 1].start() if i + 1 < len(matches) else len(src)
            return scan_execsql(src[start:end])
    raise RuntimeError(f"在 {path} 里找不到 MIGRATION_{from_v}_{to_v} 的声明块")


def apply_migrations(conn, stmts):
    """按顺序执行迁移 SQL（每条可能含多个以分号分隔的语句）。"""
    cur = conn.cursor()
    for s in stmts:
        for part in s.split(";"):
            part = part.strip()
            if part:
                cur.execute(part)
    conn.commit()


def normalize_default(raw):
    """归一化 DDL 默认值，消除 PRAGMA / Room JSON 之间的引号、括号、数字写法差异。

    只用于「JSON 明确带 defaultValue」时的比对：Room 常写 "0" / "'abc'"，
    PRAGMA 常回 "0" / "'abc'" / "(0)"。字符串字面量统一去引号后按大写关键字/数字比较。
    """
    if raw is None:
        return None
    s = str(raw).strip()
    while len(s) >= 2 and s[0] == "(" and s[-1] == ")":
        s = s[1:-1].strip()
    if len(s) >= 2 and s[0] == s[-1] and s[0] in ("'", '"', "`"):
        s = s[1:-1].strip()
    if s.upper() == "NULL":
        return None
    if re.fullmatch(r"[+-]?\d+", s):
        return str(int(s))
    if re.fullmatch(r"[+-]?\d+\.\d+", s):
        return str(float(s))
    return s.upper()


def is_internal_table(name):
    """SQLite / Room 内部表白名单。"""
    return name.startswith("sqlite_") or name in INTERNAL_TABLES


def is_internal_index(name):
    """SQLite 自动索引白名单（UNIQUE / PK 由 SQLite 自建，不在 JSON 里）。"""
    return name.startswith("sqlite_") or name.startswith(INTERNAL_INDEX_PREFIXES)


def validate(conn, target):
    """双向比对实库与 target schema，返回 errors 列表（空 = 完全一致）。

    正向：缺表 / 缺列 / 类型亲和 / NOT NULL / DEFAULT（仅 JSON 带 defaultValue 时）/
          缺索引 / 索引 unique / 索引列序。
    反向：多余的非内部表 / 多余的列 / 多余的非内部索引 —— 让输出里的 "exactly" 名副其实。
    """
    cur = conn.cursor()
    errors = []

    # ---- 反向：实库里多出来的表 ----
    cur.execute("SELECT name FROM sqlite_master WHERE type='table'")
    actual_tables = {r[0] for r in cur.fetchall()}
    expected_tables = {e["tableName"] for e in target["database"]["entities"]}
    extra_tables = sorted(
        t for t in actual_tables - expected_tables if not is_internal_table(t)
    )
    if extra_tables:
        errors.append(f"UNEXPECTED TABLES (not in schema): {extra_tables}")

    for e in target["database"]["entities"]:
        t = e["tableName"]
        cur.execute("SELECT name FROM sqlite_master WHERE type='table' AND name=?", (t,))
        if not cur.fetchone():
            errors.append(f"MISSING TABLE {t}")
            continue

        cur.execute(f"PRAGMA table_info(`{t}`)")
        actual_cols = {r[1]: r for r in cur.fetchall()}
        expected_cols = {f["columnName"] for f in e["fields"]}

        # ---- 反向：实库里多出来的列 ----
        extra_cols = sorted(set(actual_cols) - expected_cols)
        if extra_cols:
            errors.append(f"{t}: unexpected columns (not in schema): {extra_cols}")

        for f in e["fields"]:
            cn = f["columnName"]
            if cn not in actual_cols:
                errors.append(f"{t}: missing column {cn}")
                continue
            r = actual_cols[cn]
            # r: cid,name,type,notnull,dflt,pk
            exp_type = f["affinity"]
            act_type = (r[2] or "").upper()
            if exp_type == "INTEGER" and "INT" not in act_type:
                errors.append(f"{t}.{cn}: type {act_type} != INTEGER")
            elif exp_type == "REAL" and not any(
                k in act_type for k in ("REAL", "FLOA", "DOUB")
            ):
                errors.append(f"{t}.{cn}: type {act_type} != REAL")
            elif exp_type == "TEXT" and not any(
                k in act_type for k in ("TEXT", "CLOB")
            ):
                errors.append(f"{t}.{cn}: type {act_type} != TEXT")
            exp_nn = bool(f["notNull"])
            act_nn = bool(r[3])
            if exp_nn != act_nn:
                errors.append(f"{t}.{cn}: notNull expected {exp_nn} got {act_nn}")

            # 只有 JSON 明确写了 defaultValue 才比：18.json 的 isEarnLocked 没写，
            # 不能凭空要求实库有/没有 DEFAULT（会与新建库路径冲突）。
            exp_default = f.get("defaultValue")
            if exp_default is not None:
                act_default = normalize_default(r[4])
                if normalize_default(exp_default) != act_default:
                    errors.append(
                        f"{t}.{cn}: DEFAULT expected {exp_default!r} got {r[4]!r}"
                    )

        cur.execute(f"PRAGMA index_list(`{t}`)")
        idx_rows = cur.fetchall()
        actual_idx = {
            r[1]: bool(r[2]) for r in idx_rows if not is_internal_index(r[1])
        }
        expected_idx_names = {idx["name"] for idx in (e.get("indices") or [])}

        # ---- 反向：实库里多出来的索引 ----
        extra_idx = sorted(set(actual_idx) - expected_idx_names)
        if extra_idx:
            errors.append(f"{t}: unexpected indices (not in schema): {extra_idx}")

        for idx in (e.get("indices") or []):
            name = idx["name"]
            if name not in actual_idx:
                errors.append(f"{t}: missing index {name}")
                continue
            exp_unique = bool(idx.get("unique"))
            if actual_idx[name] != exp_unique:
                errors.append(
                    f"{t}.{name}: unique expected {exp_unique} got {actual_idx[name]}"
                )
            cur.execute(f"PRAGMA index_info(`{name}`)")
            act_cols = [r[2] for r in cur.fetchall()]
            exp_cols = list(idx["columnNames"])
            if act_cols != exp_cols:
                errors.append(f"{t}.{name}: index cols {act_cols} != {exp_cols}")

    # 历史上必须被删掉的 portfolio 表，双重保险（反向检查也会抓，但报错更明确）
    cur.execute("SELECT name FROM sqlite_master WHERE type='table' AND name='portfolio'")
    if cur.fetchone():
        errors.append("portfolio should have been DROPPED but still exists")
    return errors


def check_17_18_column(conn, statements):
    """从 v17 库出发，只应用 17→18 语句，断言 assets.isEarnLocked 的 DDL 口径。

    这是 C4 的替代落地：Room 的 MigrationTestHelper 依赖 instrumentation（需真机/模拟器），
    JVM 单测跑不了，故把这条端到端列断言放在无设备脚本里。它覆盖的是「线上用户真实命中的
    升级路径」，且能抓住 18.json 里根本没导出的 `DEFAULT 0`。

    @param conn 已由 17.json 的 createSql 建好的库
    @param statements 要应用的 17→18 execSQL 语句（自测可注入故意写错的 DDL）
    @return errors 列表
    """
    cur = conn.cursor()
    errors = []

    cur.execute(f"PRAGMA table_info(`{COLUMN_CHECK_TABLE}`)")
    if any(r[1] == COLUMN_CHECK_COLUMN for r in cur.fetchall()):
        errors.append(
            f"v{COLUMN_CHECK_FROM_VERSION} 基线库不应已有 "
            f"{COLUMN_CHECK_TABLE}.{COLUMN_CHECK_COLUMN}（17.json 与迁移链脱节）"
        )
        return errors
    if not statements:
        errors.append(
            f"未提取到 MIGRATION_{COLUMN_CHECK_FROM_VERSION}_{COLUMN_CHECK_TO_VERSION} "
            "的 execSQL，无法校验 17→18 新增列"
        )
        return errors

    apply_migrations(conn, statements)
    cur.execute(f"PRAGMA table_info(`{COLUMN_CHECK_TABLE}`)")
    rows = {r[1]: r for r in cur.fetchall()}
    r = rows.get(COLUMN_CHECK_COLUMN)
    if r is None:
        errors.append(
            f"MIGRATION_{COLUMN_CHECK_FROM_VERSION}_{COLUMN_CHECK_TO_VERSION} 未添加 "
            f"{COLUMN_CHECK_TABLE}.{COLUMN_CHECK_COLUMN}"
        )
        return errors

    act_type = (r[2] or "").upper()
    if COLUMN_CHECK_TYPE not in act_type:
        errors.append(
            f"{COLUMN_CHECK_TABLE}.{COLUMN_CHECK_COLUMN}: type {act_type} "
            f"!= {COLUMN_CHECK_TYPE}"
        )
    if not bool(r[3]):
        errors.append(
            f"{COLUMN_CHECK_TABLE}.{COLUMN_CHECK_COLUMN}: NOT NULL 缺失"
        )
    act_default = normalize_default(r[4])
    if act_default != normalize_default(COLUMN_CHECK_DEFAULT):
        errors.append(
            f"{COLUMN_CHECK_TABLE}.{COLUMN_CHECK_COLUMN}: DEFAULT "
            f"expected {COLUMN_CHECK_DEFAULT} got {r[4]!r}"
        )
    return errors


def run_validation():
    """跑完整校验，返回 (latest, db_version, errors)。供 main 与自测复用。"""
    latest = discover_latest_version()
    db_version = parse_db_version()
    errors = []
    if latest != db_version:
        errors.append(
            f"DB 版本与导出 schema 脱节：@Database(version = {db_version}) "
            f"但 {ROOT} 里最新的是 {latest}.json。"
            "请先让 Room 导出新 schema（或确认迁移链已补上），不要靠改脚本绕过。"
        )
        return latest, db_version, errors

    sch1 = load_schema(1)
    target = load_schema(latest)
    conn = sqlite3.connect(":memory:")
    try:
        build_v1(conn, sch1)
        stmts = extract_execsql(DB_SRC)
        print(f"Extracted {len(stmts)} execSQL statements")
        apply_migrations(conn, stmts)
        errors.extend(validate(conn, target))
    finally:
        conn.close()

    # 独立路径：v17 库 --只跑 MIGRATION_17_18--> 断言实际列（含 DEFAULT 0）
    conn17 = sqlite3.connect(":memory:")
    try:
        build_from_schema(conn17, load_schema(COLUMN_CHECK_FROM_VERSION))
        stmts_17_18 = extract_migration_block(
            DB_SRC, COLUMN_CHECK_FROM_VERSION, COLUMN_CHECK_TO_VERSION
        )
        errors.extend(check_17_18_column(conn17, stmts_17_18))
    finally:
        conn17.close()
    return latest, db_version, errors


def main():
    latest, db_version, errors = run_validation()
    if errors:
        print("VALIDATION FAILED:")
        for e in errors:
            print("  -", e)
        sys.exit(1)
    print(
        f"MIGRATION_{COLUMN_CHECK_FROM_VERSION}_{COLUMN_CHECK_TO_VERSION} column check: "
        f"{COLUMN_CHECK_TABLE}.{COLUMN_CHECK_COLUMN} "
        f"{COLUMN_CHECK_TYPE} NOT NULL DEFAULT {COLUMN_CHECK_DEFAULT} OK"
    )
    print(
        f"VALIDATION PASSED: v1 -> v{latest} schema matches {latest}.json exactly "
        f"(dbVersion={db_version})"
    )


if __name__ == "__main__":
    main()
