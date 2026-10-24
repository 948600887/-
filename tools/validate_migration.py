#!/usr/bin/env python3
# One-off validator: parse migration SQL from StockDatabase.kt, apply to a v1-built
# sqlite db (from 1.json), and assert the resulting schema matches 16.json exactly.
import json, re, sqlite3, sys

ROOT = "app/schemas/com.example.stockdiary.database.StockDatabase"

def load_schema(v):
    return json.load(open(f"{ROOT}/{v}.json"))

def build_v1(conn, sch):
    cur = conn.cursor()
    for e in sch['database']['entities']:
        sql = e['createSql'].replace("${TABLE_NAME}", e['tableName'])
        cur.execute(sql)
        for idx in (e.get('indices') or []):
            cols = ", ".join(f"`{c}`" for c in idx['columnNames'])
            cur.execute(f"CREATE {'UNIQUE ' if idx.get('unique') else ''}INDEX IF NOT EXISTS `{idx['name']}` ON `{e['tableName']}` ({cols})")
    conn.commit()

def extract_strings(arg):
    i = 0
    n = len(arg)
    res = []
    while i < n:
        if arg[i:i+3] == '"""':
            end = arg.find('"""', i+3)
            if end == -1:
                break
            res.append(arg[i+3:end])
            i = end + 3
            continue
        if arg[i] == '"':
            j = i + 1
            buf = ''
            while j < n:
                if arg[j] == '\\':
                    buf += arg[j:j+2]
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

def extract_execsql(path):
    src = open(path, encoding='utf-8').read()
    stmts = []
    for m in re.finditer(r'execSQL\s*\(\s*', src):
        i = m.end()
        depth = 1
        j = i
        while j < len(src):
            c = src[j]
            if c == '"' and src[j:j+3] == '"""':
                end = src.find('"""', j+3)
                j = len(src) if end == -1 else end + 3
                continue
            if c == '"':
                j2 = j + 1
                while j2 < len(src) and src[j2] != '"':
                    if src[j2] == '\\':
                        j2 += 2
                    else:
                        j2 += 1
                j = j2 + 1
                continue
            if c == '(':
                depth += 1
            elif c == ')':
                depth -= 1
                if depth == 0:
                    break
            j += 1
        arg = src[i:j]
        s = ''.join(extract_strings(arg))
        if s.strip():
            stmts.append(s)
    return stmts

def apply_migrations(conn, stmts):
    cur = conn.cursor()
    for s in stmts:
        for part in s.split(';'):
            part = part.strip()
            if part:
                cur.execute(part)
    conn.commit()

def validate(conn, sch16):
    cur = conn.cursor()
    errors = []
    for e in sch16['database']['entities']:
        t = e['tableName']
        # table exists?
        cur.execute("SELECT name FROM sqlite_master WHERE type='table' AND name=?", (t,))
        if not cur.fetchone():
            errors.append(f"MISSING TABLE {t}")
            continue
        # columns
        cur.execute(f"PRAGMA table_info(`{t}`)")
        actual_cols = {r[1]: r for r in cur.fetchall()}
        for f in e['fields']:
            cn = f['columnName']
            if cn not in actual_cols:
                errors.append(f"{t}: missing column {cn}")
                continue
            r = actual_cols[cn]
            # r: cid,name,type,notnull,dflt, pk
            exp_type = f['affinity']
            act_type = (r[2] or '').upper()
            # crude affinity match
            if exp_type == 'INTEGER' and 'INT' not in act_type:
                errors.append(f"{t}.{cn}: type {act_type} != INTEGER")
            elif exp_type == 'REAL' and 'REAL' not in act_type and 'FLOA' not in act_type and 'DOUB' not in act_type:
                errors.append(f"{t}.{cn}: type {act_type} != REAL")
            elif exp_type == 'TEXT' and 'TEXT' not in act_type and 'CLOB' not in act_type:
                errors.append(f"{t}.{cn}: type {act_type} != TEXT")
            exp_nn = bool(f['notNull'])
            act_nn = bool(r[3])
            if exp_nn != act_nn:
                errors.append(f"{t}.{cn}: notNull expected {exp_nn} got {act_nn}")
        # indices
        cur.execute(f"PRAGMA index_list(`{t}`)")
        actual_idx = {r[1]: bool(r[2]) for r in cur.fetchall()}
        for idx in (e.get('indices') or []):
            name = idx['name']
            if name not in actual_idx:
                errors.append(f"{t}: missing index {name}")
                continue
            exp_unique = bool(idx.get('unique'))
            if actual_idx[name] != exp_unique:
                errors.append(f"{t}.{name}: unique expected {exp_unique} got {actual_idx[name]}")
            cur.execute(f"PRAGMA index_info(`{name}`)")
            act_cols = [r[2] for r in cur.fetchall()]
            exp_cols = list(idx['columnNames'])
            if act_cols != exp_cols:
                errors.append(f"{t}.{name}: index cols {act_cols} != {exp_cols}")
    # dropped portfolio must be gone
    cur.execute("SELECT name FROM sqlite_master WHERE type='table' AND name='portfolio'")
    if cur.fetchone():
        errors.append("portfolio should have been DROPPED but still exists")
    return errors

def main():
    sch1 = load_schema(1)
    sch16 = load_schema(16)
    conn = sqlite3.connect(':memory:')
    build_v1(conn, sch1)
    stmts = extract_execsql("app/src/main/java/com/example/stockdiary/database/StockDatabase.kt")
    print(f"Extracted {len(stmts)} execSQL statements")
    apply_migrations(conn, stmts)
    errors = validate(conn, sch16)
    conn.close()
    if errors:
        print("VALIDATION FAILED:")
        for e in errors:
            print("  -", e)
        sys.exit(1)
    print("VALIDATION PASSED: v1 -> v16 schema matches 16.json exactly")

if __name__ == '__main__':
    main()
