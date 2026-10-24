#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
【长期回归工具 · 严格 schema 比对】

用途：比 `validate_migration.py` 更严的 schema 校验，补齐后者不查的四项：
      **列序、主键、多余列/多余表、多余索引**。
      并用**对照实验**隔离责任：同跑「改动前基线」与「改动后」两组，
      只在后者出现的告警才算本次改动引入的漂移（历史遗留的不会误报）。

什么时候跑：
  1. 改动 Room 迁移 / 实体后跑，确认没有引入 schema 漂移；
  2. 适合在「究竟是我的改动引入的，还是历史遗留」有争议时用它定性；
  3. 无设备、纯 python + sqlite，`python tools/qa_schema_strict.py` 即可。

来历：QA 在 2026-10-24 严过关时编写（作者：software-qa-engineer），
      收尾时保留为长期回归工具（归位：software-engineer）。
      `qa_` 前缀是历史命名，保留原名以免破坏既有引用。
 ------------------------------------------------------------------------------
 以下为原始说明：
QA 严格 schema 比对（严过关 2026-10-24）。

validate_migration.py 只查「列存在 + 类型亲和 + notNull + 索引存在」，
不查：列序、主键、多余列、多余表、多余索引。这里补齐。

并用**对照实验**隔离责任：
  baseline : v1 --1..16--> 比对 16.json
  current  : v1 --1..17--> 比对 17.json
两组跑同一套严格规则。若某条告警在两组都出现 => 是历史遗留，与 16_17 无关；
若只在 current 出现 => 是本次改动引入的漂移（必须报 bug）。
"""
import json, sqlite3, sys
from validate_migration import ROOT, build_v1, extract_execsql, extract_strings

DB_SRC = "app/src/main/java/com/example/stockdiary/database/StockDatabase.kt"


def load(v):
    return json.load(open(f"{ROOT}/{v}.json", encoding="utf-8"))


def chain(upto):
    """
    取迁移链中「截止到 upto」的 execSQL 语句：
    源码里 MIGRATION_a_b 按版本升序出现，因此只要把源码截断在
    MIGRATION_{upto}_{upto+1} 之前，剩下的一段就是 1..upto 的全部语句。
    """
    import re
    src = open(DB_SRC, encoding="utf-8").read()
    end = len(src)
    # 注意：注释里也出现过 "MIGRATION_16_17" 字样，必须匹配 object 声明而不是裸名字
    m = re.search(rf"MIGRATION_{upto}_{upto + 1}\s*=\s*object\s*:", src)
    if m:
        end = m.start()

    def scan(text):
        out = []
        for m in re.finditer(r'execSQL\s*\(\s*', text):
            depth, j = 1, m.end()
            while j < len(text):
                c = text[j]
                if c == '"' and text[j:j + 3] == '"""':
                    e2 = text.find('"""', j + 3)
                    j = len(text) if e2 == -1 else e2 + 3
                    continue
                if c == '"':
                    k = j + 1
                    while k < len(text) and text[k] != '"':
                        k += 2 if text[k] == '\\' else 1
                    j = k + 1
                    continue
                if c == '(':
                    depth += 1
                elif c == ')':
                    depth -= 1
                    if depth == 0:
                        break
                j += 1
            s = ''.join(extract_strings(text[m.end():j]))
            if s.strip():
                out.append(s)
        return out

    return scan(src[:end])


def strict_compare(conn, sch, label):
    cur = conn.cursor()
    errs = []
    cur.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")
    actual_tables = {r[0] for r in cur.fetchall()}
    exp_tables = {e['tableName'] for e in sch['database']['entities']}
    extra = actual_tables - exp_tables - {'room_master_table'}
    missing = exp_tables - actual_tables
    if extra:
        errs.append(f"多余的表: {sorted(extra)}")
    if missing:
        errs.append(f"缺失的表: {sorted(missing)}")

    for e in sch['database']['entities']:
        t = e['tableName']
        cur.execute(f"PRAGMA table_info(`{t}`)")
        rows = cur.fetchall()
        act_names = [r[1] for r in rows]
        exp_names = [f['columnName'] for f in e['fields']]
        if act_names != exp_names:
            errs.append(f"{t}: 列序不一致|actual={act_names}|expect={exp_names}")
        for f in e['fields']:
            m = [r for r in rows if r[1] == f['columnName']]
            if not m:
                continue
            r = m[0]
            if bool(r[3]) != bool(f['notNull']):
                errs.append(f"{t}.{f['columnName']}: notNull {bool(r[3])} != {bool(f['notNull'])}")
        exp_pk = sorted((e.get('primaryKey') or {}).get('columnNames', []))
        act_pk = sorted(r[1] for r in rows if r[5])
        if exp_pk != act_pk:
            errs.append(f"{t}: PK {act_pk} != {exp_pk}")
        cur.execute(f"PRAGMA index_list(`{t}`)")
        act_idx = {r[1] for r in cur.fetchall() if not r[1].startswith('sqlite_autoindex')}
        exp_idx = {i['name'] for i in (e.get('indices') or [])}
        if act_idx != exp_idx:
            errs.append(f"{t}: 索引集合 {sorted(act_idx)} != {sorted(exp_idx)}")
    return errs


def run(upto, target_v):
    conn = sqlite3.connect(':memory:')
    build_v1(conn, load(1))
    for s in chain(upto):
        for p in s.split(';'):
            if p.strip():
                conn.execute(p)
    conn.commit()
    errs = strict_compare(conn, load(target_v), f"v{upto}")
    conn.close()
    return errs


base = run(16, 16)
curr = run(17, 17)

print("=" * 72)
print(f"baseline  (v1 ->16 对比 16.json): {len(base)} 条告警")
for x in base:
    print("   *", x)
print()
print(f"current   (v1 ->17 对比 17.json): {len(curr)} 条告警")
for x in curr:
    print("   *", x)
print()
new = [x for x in curr if x not in base]
gone = [x for x in base if x not in curr]
print(f"本次改动【新引入】的漂移: {len(new)}")
for x in new:
    print("   !!", x)
print(f"本次改动【消掉】的历史漂移: {len(gone)}")
for x in gone:
    print("   --", x)
print("=" * 72)
