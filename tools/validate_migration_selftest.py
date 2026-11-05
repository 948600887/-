#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
【迁移校验器的自测 · 证明它不会再假绿】

`tools/validate_migration.py` 曾经把 TARGET_VERSION 写死成 17、只做单向「JSON 里有没有」
检查，导致 17→18 的列名/类型/NOT NULL/DEFAULT 写错也照样打印 VALIDATION PASSED。
本脚本用**故意写坏的 in-memory 库**反向验证校验器本身：

  1. 正确库 → validate() 返回空（好路径不被误报）；
  2. 缺列（用 17.json 建库、拿 18.json 当基准）→ 必须报 missing column isEarnLocked；
  3. 列类型写错（INTEGER 改成 TEXT）→ 必须报 type != INTEGER；
  4. 多余表 / 多余列 / 多余索引 → 必须被反向检查抓到；
  5. 17→18 的 DDL 故意写错（DEFAULT 1 / 无 NOT NULL / 类型错 / 完全不加列）
     → check_17_18_column() 必须返回非空 errors；正确 DDL → 空；
  6. 版本自动发现与 @Database 解析必须一致。

任何一条不成立就 sys.exit(1)，由 tools/check_gate.sh 挡在提交前。
用法： python tools/validate_migration_selftest.py
"""
import json
import os
import sqlite3
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from validate_migration import (  # noqa: E402
    ROOT,
    DB_SRC,
    COLUMN_CHECK_COLUMN,
    COLUMN_CHECK_FROM_VERSION,
    COLUMN_CHECK_TABLE,
    COLUMN_CHECK_TO_VERSION,
    build_from_schema,
    check_17_18_column,
    discover_latest_version,
    extract_migration_block,
    load_schema,
    parse_db_version,
    validate,
)


def check(label, condition, detail=""):
    """收集一条断言结果，不立即抛异常，便于一次跑完全部用例并列出所有问题。"""
    if condition:
        print(f"  PASS: {label}")
        return True
    print(f"  FAIL: {label}{(' -> ' + detail) if detail else ''}")
    return False


def build_with_override(conn, sch, table, old, new):
    """按 schema 建库，但把指定表的 createSql 里一处文本替换掉（用于造「写坏的库」）。"""
    cur = conn.cursor()
    for e in sch["database"]["entities"]:
        sql = e["createSql"].replace("${TABLE_NAME}", e["tableName"])
        if e["tableName"] == table:
            if old not in sql:
                raise RuntimeError(f"createSql 里找不到待替换片段 {old!r}")
            sql = sql.replace(old, new)
        cur.execute(sql)
        for idx in (e.get("indices") or []):
            cols = ", ".join(f"`{c}`" for c in idx["columnNames"])
            cur.execute(
                f"CREATE {'UNIQUE ' if idx.get('unique') else ''}INDEX IF NOT EXISTS "
                f"`{idx['name']}` ON `{e['tableName']}` ({cols})"
            )
    conn.commit()


def fresh_v17():
    """按 17.json 建一个干净的 v17 库。"""
    conn = sqlite3.connect(":memory:")
    build_from_schema(conn, load_schema(COLUMN_CHECK_FROM_VERSION))
    return conn


def main():
    failures = []

    def expect(label, ok, detail=""):
        if not check(label, ok, detail):
            failures.append(label)

    print("== 迁移校验器自测（故意写坏的库必须被抓到）==")

    latest = discover_latest_version()
    db_version = parse_db_version()
    target = load_schema(latest)

    # 6. 版本自动发现与 @Database 注解一致
    expect(
        "自动发现的最新 schema 版本与 @Database(version) 一致",
        latest == db_version,
        f"latest={latest} dbVersion={db_version}",
    )

    # 1. 正确库：从最新 JSON 建库，validate() 必须零错误
    conn = sqlite3.connect(":memory:")
    try:
        build_from_schema(conn, target)
        errs = validate(conn, target)
        expect("正确库 validate() 返回空 errors", not errs, repr(errs))
    finally:
        conn.close()

    # 2. 缺列：17.json 的库（没有 isEarnLocked）对 18.json 必须报缺列
    conn = sqlite3.connect(":memory:")
    try:
        build_from_schema(conn, load_schema(COLUMN_CHECK_FROM_VERSION))
        errs = validate(conn, target)
        hit = [e for e in errs if "missing column" in e and COLUMN_CHECK_COLUMN in e]
        expect(
            "17→18 漏加列时 validate() 报 missing column isEarnLocked",
            bool(hit),
            repr(errs),
        )
    finally:
        conn.close()

    # 3. 列类型写错：INTEGER -> TEXT
    conn = sqlite3.connect(":memory:")
    try:
        build_with_override(
            conn,
            target,
            COLUMN_CHECK_TABLE,
            f"`{COLUMN_CHECK_COLUMN}` INTEGER NOT NULL",
            f"`{COLUMN_CHECK_COLUMN}` TEXT NOT NULL",
        )
        errs = validate(conn, target)
        hit = [e for e in errs if COLUMN_CHECK_COLUMN in e and "type" in e]
        expect(
            "17→18 列类型写错时 validate() 报 type 不一致",
            bool(hit),
            repr(errs),
        )
    finally:
        conn.close()

    # 4a. 多余表（非内部对象）必须进 errors
    conn = sqlite3.connect(":memory:")
    try:
        build_from_schema(conn, target)
        conn.execute("CREATE TABLE `ghost_table` (`id` INTEGER)")
        errs = validate(conn, target)
        hit = [e for e in errs if "UNEXPECTED TABLES" in e and "ghost_table" in e]
        expect("实库多出非内部表时 validate() 报错", bool(hit), repr(errs))
    finally:
        conn.close()

    # 4b. 多余列必须进 errors
    conn = sqlite3.connect(":memory:")
    try:
        build_from_schema(conn, target)
        conn.execute(f"ALTER TABLE `{COLUMN_CHECK_TABLE}` ADD COLUMN `ghost_col` TEXT")
        errs = validate(conn, target)
        hit = [e for e in errs if "unexpected columns" in e and "ghost_col" in e]
        expect("实库多出列时 validate() 报错", bool(hit), repr(errs))
    finally:
        conn.close()

    # 4c. 多余索引必须进 errors（SQLite 自动索引不算）
    conn = sqlite3.connect(":memory:")
    try:
        build_from_schema(conn, target)
        conn.execute(
            f"CREATE INDEX `ghost_index` ON `{COLUMN_CHECK_TABLE}` (`id`)"
        )
        errs = validate(conn, target)
        hit = [e for e in errs if "unexpected indices" in e and "ghost_index" in e]
        expect("实库多出索引时 validate() 报错", bool(hit), repr(errs))
    finally:
        conn.close()

    # 5a. 17→18 正确 DDL：0 errors
    good = extract_migration_block(
        DB_SRC, COLUMN_CHECK_FROM_VERSION, COLUMN_CHECK_TO_VERSION
    )
    conn = fresh_v17()
    try:
        errs = check_17_18_column(conn, good)
        expect(
            "MIGRATION_17_18 真实 DDL 通过 isEarnLocked 列断言",
            not errs,
            repr(errs),
        )
    finally:
        conn.close()

    # 5b~5e. 故意写坏的 17→18 DDL 必须全部被抓
    bad_cases = [
        (
            "DEFAULT 写成 1",
            "ALTER TABLE `assets` ADD COLUMN `isEarnLocked` INTEGER NOT NULL DEFAULT 1",
        ),
        (
            "漏掉 NOT NULL",
            "ALTER TABLE `assets` ADD COLUMN `isEarnLocked` INTEGER DEFAULT 0",
        ),
        (
            "类型写成 TEXT",
            "ALTER TABLE `assets` ADD COLUMN `isEarnLocked` TEXT NOT NULL DEFAULT 0",
        ),
        (
            "列名写错",
            "ALTER TABLE `assets` ADD COLUMN `isEarnLock` INTEGER NOT NULL DEFAULT 0",
        ),
        (
            "完全不加列（空语句）",
            "",
        ),
    ]
    for label, ddl in bad_cases:
        conn = fresh_v17()
        try:
            stmts = [ddl] if ddl.strip() else []
            errs = check_17_18_column(conn, stmts)
            expect(
                f"17→18 DDL「{label}」必须被 check_17_18_column 抓到",
                bool(errs),
                "竟然返回空 errors（会假绿）" if not errs else "",
            )
        finally:
            conn.close()

    print("=" * 60)
    if failures:
        print(f"SELFTEST FAILED: {len(failures)} 条断言不成立")
        for f in failures:
            print("  -", f)
        sys.exit(1)
    print("SELFTEST PASSED: 校验器能抓到缺列/类型错/多余对象/17→18 DDL 写错")


if __name__ == "__main__":
    main()
