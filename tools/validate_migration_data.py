#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
【长期回归工具 · 数据保留校验】

用途：验证迁移（尤其是「建新表 → INSERT SELECT → DROP → RENAME」这类搬表迁移）
      **有没有把数据搬丢**。与 `validate_migration.py` 互补——后者只验 schema 形状，
      一条数据都不插；而搬表迁移真正会出事的是数据，不是形状。
      本脚本按位校验数值（IEEE754 bit pattern）、按行校验主键与其余列。

什么时候跑：
  1. 改动任何 Room 迁移后**必跑**（尤其是搬表 / 改列类型的迁移）；
  2. 无设备时的回归主力——它是无设备环境下唯一能验证「数据保留」的手段；
  3. 已纳入 `tools/check_gate.sh` 候选，可直接 `python tools/validate_migration_data.py`。

来历：原名 `tools/qa_migration_16_17_probe.py`，是 QA 在 2026-10-24 严过关时写的独立
      探针（作者：software-qa-engineer）。因验证价值长期成立，收尾时归位为正式工具并
      改名，与 `validate_migration.py` 并列（归位/改名：software-engineer）。
      **不要当成一次性脚本删掉**——删了就没人再验「迁移是否丢数据」了。

 ------------------------------------------------------------------------------
 以下为原始说明：
QA 独立探针（严过关 2026-10-24）：验证 MIGRATION_16_17 的数据保留边界。

为什么另写一份而不是只跑 validate_migration.py：
  validate_migration.py 只验「schema 形状」，一条数据都不插。
  而 16->17 是一条「建新表 -> INSERT SELECT -> DROP -> RENAME」的搬表迁移，
  真正会出事的是**数据**，不是形状。所以这里按位校验数值、按行校验主键。

覆盖两条路径：
  PATH_V16（真实命中路径，线上 100% 用户）：v16 库 --仅 16_17--> v17
      说明：现有用户全是 v16 全新安装，他们只会跑 MIGRATION_16_17 这一条。
  PATH_V1 （历史遗留路径）：v1 库 --1..17 全链--> v17

场景：空表 / 1 条 / 多条含整数与小数值 / 删中间行后再迁移（id 复用）
"""
import json, os, sqlite3, struct, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from validate_migration import (  # noqa: E402
    ROOT, build_v1, extract_execsql,
)

DB_SRC = "app/src/main/java/com/example/stockdiary/database/StockDatabase.kt"


def load_schema(v):
    return json.load(open(f"{ROOT}/{v}.json", encoding="utf-8"))


def entity(sch, table):
    for e in sch["database"]["entities"]:
        if e["tableName"] == table:
            return e
    raise KeyError(table)


def build_v16(conn):
    """按 16.json 建一个『全新 v16 安装』的库——这才是线上用户的真实起点。"""
    sch = load_schema(16)
    cur = conn.cursor()
    for e in sch["database"]["entities"]:
        cur.execute(e["createSql"].replace("${TABLE_NAME}", e["tableName"]))
        for idx in (e.get("indices") or []):
            cols = ", ".join(f"`{c}`" for c in idx["columnNames"])
            cur.execute(
                f"CREATE {'UNIQUE ' if idx.get('unique') else ''}INDEX IF NOT EXISTS "
                f"`{idx['name']}` ON `{e['tableName']}` ({cols})"
            )
    conn.commit()


def stmts_for_16_17():
    """从源码里精准取出 MIGRATION_16_17 的 execSQL，而不是靠行号猜。"""
    src = open(DB_SRC, encoding="utf-8").read()
    start = src.index("MIGRATION_16_17 = object : Migration(16, 17)")
    end = src.index("allMigrations", start)
    body = src[start:end]
    out = []
    for m in __import__("re").finditer(r'execSQL\s*\(\s*', body):
        depth, j = 1, m.end()
        while j < len(body):
            c = body[j]
            if c == '"' and body[j:j + 3] == '"""':
                e2 = body.find('"""', j + 3)
                j = len(body) if e2 == -1 else e2 + 3
                continue
            if c == '"':
                k = j + 1
                while k < len(body) and body[k] != '"':
                    k += 2 if body[k] == '\\' else 1
                j = k + 1
                continue
            if c == '(':
                depth += 1
            elif c == ')':
                depth -= 1
                if depth == 0:
                    break
            j += 1
        s = ''.join(
            __import__("validate_migration", fromlist=["x"]).extract_strings(body[m.end():j])
        )
        if s.strip():
            out.append(s)
    return out


def bits(v):
    """浮点按位指纹——证明『逐位不变』，而不是只比 1e-9 容差。"""
    return struct.pack(">d", float(v)).hex() if v is not None else None


def snap(conn):
    """取 transactions 全表快照：(id, quantity_float, quantity_bits, 其余列)。"""
    cur = conn.cursor()
    cur.execute("SELECT id, symbol, name, type, quantity, price, date, fee FROM transactions ORDER BY id")
    rows = cur.fetchall()
    return [
        (r[0], r[1], r[2], r[3], float(r[4]), bits(r[4]), r[5], r[6], r[7]) for r in rows
    ]


def declared_type(conn, table, col):
    cur = conn.cursor()
    cur.execute(f"PRAGMA table_info(`{table}`)")
    for r in cur.fetchall():
        if r[1] == col:
            return (r[2] or "").upper()
    return None


def sqlite_sequence(conn):
    cur = conn.cursor()
    try:
        # 注意：sqlite_master 里没有 seq 列，不能 SELECT name, seq FROM sqlite_master
        cur.execute("SELECT name FROM sqlite_master WHERE type='table' AND name='sqlite_sequence'")
        if not cur.fetchone():
            return {}
        cur.execute("SELECT name, seq FROM sqlite_sequence")
        return dict(cur.fetchall())
    except sqlite3.OperationalError:
        return {}


FAILS = []


def check(cond, msg):
    print(("  [OK]   " if cond else "  [FAIL] ") + msg)
    if not cond:
        FAILS.append(msg)


def run_case(name, path, seed_sql, post_hook=None):
    print(f"\n=== {name}  (path={path}) ===")
    conn = sqlite3.connect(":memory:")
    if path == "v16":
        build_v16(conn)
        todo = stmts_for_16_17()
        print(f"  起点: v16 (由 16.json createSql 建), 应用 {len(todo)} 条 16_17 语句")
    else:
        build_v1(conn, load_schema(1))
        todo = extract_execsql(DB_SRC)
        print(f"  起点: v1  (由 1.json createSql 建), 应用 {len(todo)} 条全链语句")

    if seed_sql:
        conn.executescript(seed_sql)
        conn.commit()

    before = snap(conn)
    seq_before = sqlite_sequence(conn).get("transactions")
    t_before = declared_type(conn, "transactions", "quantity")
    print(f"  迁移前: rows={len(before)} quantity声明类型={t_before} sqlite_sequence[transactions]={seq_before}")
    for r in before:
        print(f"          id={r[0]} qty={r[4]!r} bits={r[5]}")

    for s in todo:
        for part in s.split(";"):
            if part.strip():
                conn.execute(part)
    conn.commit()

    after = snap(conn)
    seq_after = sqlite_sequence(conn).get("transactions")
    t_after = declared_type(conn, "transactions", "quantity")
    print(f"  迁移后: rows={len(after)} quantity声明类型={t_after} sqlite_sequence[transactions]={seq_after}")
    for r in after:
        print(f"          id={r[0]} qty={r[4]!r} bits={r[5]}")

    check(len(before) == len(after), f"行数不变 (before={len(before)} after={len(after)})")
    check(t_after == "REAL", f"quantity 声明类型变为 REAL (实际 {t_after})")

    # 逐行比对：主键 + 浮点按位 + 其余每一列
    for b, a in zip(before, after):
        check(b[0] == a[0], f"id={b[0]} 主键保留")
        check(b[5] == a[5], f"id={b[0]} quantity 逐位不变 (before={b[4]!r}/{b[5]} after={a[4]!r}/{a[5]})")
        check(b[1:4] == a[1:4], f"id={b[0]} symbol/name/type 保留")
        check(b[6] == a[6] and b[7] == a[7] and b[8] == a[8],
              f"id={b[0]} price/date/fee 保留")

    if post_hook:
        post_hook(conn)

    conn.close()


def hook_insert_after(conn):
    """迁移后再插一条：确认自增序号不会撞主键、也不丢行。"""
    n_before = conn.execute("SELECT COUNT(*) FROM transactions").fetchone()[0]
    cur = conn.cursor()
    cur.execute(
        "INSERT INTO transactions (symbol, name, type, quantity, price, date, fee) "
        "VALUES ('sz000001','平安银行','BUY', 7.25, 12.5, '2024-07-01', 1.0)"
    )
    conn.commit()
    new_id = cur.lastrowid
    n_after = conn.execute("SELECT COUNT(*) FROM transactions").fetchone()[0]
    ids = [r[0] for r in conn.execute("SELECT id FROM transactions ORDER BY id")]
    dup = len(ids) != len(set(ids))
    print(f"  迁移后新插入: lastrowid={new_id} rows {n_before}->{n_after} ids={ids}")
    check(n_after == n_before + 1, "新插入后行数 +1（没有丢行）")
    check(not dup, f"无主键冲突 ids={ids}")


def main():
    print("=" * 70)
    print("MIGRATION_16_17 数据保留边界探针")
    print("=" * 70)

    SEED_EMPTY = None  # 不插任何数据

    SEED_ONE = """
        INSERT INTO transactions (id, symbol, name, type, quantity, price, date, fee)
        VALUES (1, 'sh600519', '贵州茅台', 'BUY', 100, 1800.0, '2024-06-01', 5.0);
    """

    SEED_MIXED = """
        INSERT INTO transactions (id, symbol, name, type, quantity, price, date, fee) VALUES
          (1, 'sh600519', '贵州茅台',   'BUY',  100,   1800.0,  '2024-06-01', 5.0),
          (2, 'sz000001', '平安银行',   'BUY',  3.5,   12.5,    '2024-06-02', 1.0),
          (3, 'sh510300', '沪深300ETF', 'SELL', 0.001, 3.876,   '2024-06-03', 0.0),
          (4, 'hk00700',  '腾讯控股',   'BUY',  1234567.125, 311.5, '2024-06-04', 12.34),
          (5, 'sh601398', '工商银行',   'BUY',  0,     5.0,     '2024-06-05', 0.5);
    """

    # 删中间那条（id=2）再迁移
    SEED_DELETE_MIDDLE = SEED_MIXED + " DELETE FROM transactions WHERE id = 2; "

    # 删最后一条（id=5）再迁移——AUTOINCREMENT 序号回收的最坏情况
    SEED_DELETE_LAST = SEED_MIXED + " DELETE FROM transactions WHERE id = 5; "

    for path in ("v16", "v1"):
        run_case(f"[{path}] 空表", path, SEED_EMPTY, hook_insert_after)
        run_case(f"[{path}] 1 条（整数值 100）", path, SEED_ONE)
        run_case(f"[{path}] 5 条混合（整数/小数/极小值/大数/0）", path, SEED_MIXED)
        run_case(f"[{path}] 删中间行(id=2)后迁移 + 后再插", path, SEED_DELETE_MIDDLE, hook_insert_after)
        run_case(f"[{path}] 删末行(id=5)后迁移 + 后再插", path, SEED_DELETE_LAST, hook_insert_after)

    print("\n" + "=" * 70)
    if FAILS:
        print(f"RESULT: FAILED  ({len(FAILS)} 项)")
        for f in FAILS:
            print("   -", f)
        sys.exit(1)
    print("RESULT: ALL PASSED")


if __name__ == "__main__":
    main()
