#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
【源码不变量静态校验 · 毫秒级 · 零依赖】

只用标准库（re / os / pathlib / argparse / sys）从源码正则读取，不依赖 gradle、
不需要设备、不编译 Kotlin。六组断言把架构评审报告 §9.1 R1 里「全靠人记」的隐性
不变量钉成机器可判的防线：

  A1 阈值 > 并发：FAIL_THRESHOLD 严格大于 MAX_CONCURRENT_SYNC；并发度**从源码读**，
     绝不写死 6；三个老文件里这两个名字只允许 `= SyncLimits.<name>` 引用，任何形式的
     数字字面量重定义（含去掉 const 的 `private val X = 16`）都 FAIL。
  A2 DB 版本链：@Database(version=N) == 最新 schema json 版本 == allMigrations() 条数 + 1，
     且迁移编号序列恰好为 1_2 … (N-1)_N（无重复 / 缺号 / 跳号 / 乱序）。
  A3 偏好写入收口：savePreferences( 调用点只允许出现在 DashboardPreferencesManager 的
     updatePreferences 函数体内；**app/src/test 不扫描**（约 8 处测试直接调用是合法用法）。
  A4 Compose 版本组合白名单：compose-bom / 3 个 lifecycle-* / activity-compose 固定。
  A5 禁手动 Accept-Encoding：先剔除 // 行注释、/* */ 块注释与 KDoc，再匹配真实 header 构造；
     SinaStockAPI.kt / DividendService.kt 注释里出现该词**不得误报**。
  A6 超时下限：Timeouts.CALL_MS 不得 < 20000。

## 为什么行号必须精确、为什么注释要「抹成空格」而不是删掉
每条规则都要输出 `PASS/FAIL: 规则名 — 文件:行号 — 期望 X，实际 Y`，人排障时直接跳到那一行。
所以剔除注释时把每个注释字符替换成**等长空格、保留换行**，字符偏移与行号完全不变；
否则 A3/A5 报出来的行号会全错。

## 为什么必须跳过字符串字面量
APIEndpoints.kt / APIConfig.kt 里全是 `https://...`，不跳过字符串就会把 URL 里的 `//`
当成行注释、把后半行吞掉 —— 一个「把正常代码当注释」的校验器会假绿，比没有更危险。

## 为什么读不到源码 / 解析不了要判 FAIL 而不是跳过
旧版 validate_migration.py 把目标版本写死导致假绿，教训是：任何「静默跳过」都会变成
下一个假绿灯。文件缺失、正则没匹配上、括号不配对，一律判 FAIL 并 sys.exit(1)。

用法：
  python tools/check_invariants.py
  python tools/check_invariants.py --root <临时源码副本目录>   # 供 selftest 负向反证
"""
from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path

# ROOT 默认为本脚本所在 tools/ 的上一级（仓库根）；--root 可覆盖，供自测在临时副本上运行。
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# ---- 相对路径常量（全部相对仓库根，便于 --root 换成临时副本）----
SYNC_LIMITS = "app/src/main/java/com/example/stockdiary/common/SyncLimits.kt"
ASSET_SYNC_MANAGER = "app/src/main/java/com/example/stockdiary/data/sync/AssetSyncManager.kt"
BINANCE_HOST_GATE = "app/src/main/java/com/example/stockdiary/network/BinanceHostGate.kt"
SOURCE_CIRCUIT_BREAKER = "app/src/main/java/com/example/stockdiary/data/price/SourceCircuitBreaker.kt"
STOCK_DATABASE = "app/src/main/java/com/example/stockdiary/database/StockDatabase.kt"
SCHEMA_DIR = "app/schemas/com.example.stockdiary.database.StockDatabase"
MAIN_SRC = "app/src/main"
BUILD_GRADLE = "app/build.gradle.kts"
API_CONFIG = "app/src/main/java/com/example/stockdiary/common/APIConfig.kt"
DASHBOARD_PREFS = (
    "app/src/main/java/com/example/stockdiary/data/prefs/DashboardPreferencesManager.kt"
)
DIVIDEND_SERVICE = (
    "app/src/main/java/com/example/stockdiary/data/dividend/DividendService.kt"
)

# A1：三个老文件里只允许「引用」，不允许把定义复制回来。
# 只盯这两个名字：文件里还有一堆别的 const val 数字常量（批大小 8、回退闸 2、
# 地区封锁阈值 2、探测超时 30000L …），按裸 `const val ... = 数字` 扫会大面积误报。
#
# 为什么用 `(?:const\s+)?val`：QA 实验 D 证明只盯 `const val` 会被
# 「去掉 const 改成 `private val MAX_CONCURRENT_SYNC = 16`」直接绕过 ——
# 此时并发度 16 > 阈值 8，正是 A1 要防的一波并发误熔事故，校验器却全绿。
# 为什么还要 ANY_REDEF：数字字面量只是最笨的一种复制；写成 `= SomeOther` 或
# `= SyncLimits.X + 10` 同样会让并发度脱离唯一来源。所以任一 val/const val 定义
# 都要求 RHS 精确等于 `SyncLimits.<同名>`（允许类型标注与跨行写法）。
LITERAL_REDEF = re.compile(
    r"\b(?:const\s+)?val\s+(?:MAX_CONCURRENT_SYNC|FAIL_THRESHOLD)\s*=\s*(\d+)\b"
)
ANY_REDEF = re.compile(
    r"\b(?:const\s+)?val\s+(MAX_CONCURRENT_SYNC|FAIL_THRESHOLD)"
    r"\s*(?::\s*[\w.]+\s*)?=\s*([^\n]*)"
)

# A3：调用点与函数声明要分开。`fun savePreferences(...)` 是定义，不是调用。
SAVE_CALL = re.compile(r"\bsavePreferences\s*\(")
SAVE_DECL = re.compile(r"\bfun\s+savePreferences\s*\(")

# A4：五项白名单。捕获实际版本，而不是只 grep「正确版本出现过」——
# 有人改成 2.8.0 后旧字符串仍可能留在注释里，只 grep 存在会假绿。
EXPECTED_VERSIONS = (
    ("compose-bom", r"androidx\.compose:compose-bom:([\w.\-]+)", "2024.02.00"),
    ("lifecycle-runtime-ktx", r"androidx\.lifecycle:lifecycle-runtime-ktx:([\w.\-]+)", "2.7.0"),
    ("lifecycle-runtime-compose", r"androidx\.lifecycle:lifecycle-runtime-compose:([\w.\-]+)", "2.7.0"),
    ("lifecycle-viewmodel-compose", r"androidx\.lifecycle:lifecycle-viewmodel-compose:([\w.\-]+)", "2.7.0"),
    ("activity-compose", r"androidx\.activity:activity-compose:([\w.\-]+)", "1.8.2"),
)

# A5：三种真实的手动 header 构造 + 兜底（任何裸 "Accept-Encoding" 字符串字面量）。
# \b 让 `header` 不会误命中 `addHeader` 里的 `header` 子串。
HEADER_PATTERNS = (
    ("addHeader", re.compile(r"\baddHeader\s*\(\s*\"Accept-Encoding\"", re.I)),
    ("header", re.compile(r"\bheader\s*\(\s*\"Accept-Encoding\"", re.I)),
    ("HEADERS-map 键", re.compile(r"\"Accept-Encoding\"\s*to\s*", re.I)),
)
BARE_HEADER_LITERAL = re.compile(r"[\"']Accept-Encoding[\"']", re.I)

CALL_MS_MIN = 20000


class RuleResult:
    """一条规则的输出集合：可有多行（例如 A3 的每个越界调用点各一行）。"""

    def __init__(self, name: str) -> None:
        self.name = name
        self.passed = True
        self.lines: list[str] = []

    def ok(self, rel: str, line: int, expected: str, actual: str) -> None:
        """记录一条 PASS。"""
        self.lines.append(
            f"PASS: {self.name} — {rel}:{line} — 期望 {expected}，实际 {actual}"
        )

    def bad(self, rel: str, line: int, expected: str, actual: str) -> None:
        """记录一条 FAIL，并把整条规则判为不通过。"""
        self.passed = False
        self.lines.append(
            f"FAIL: {self.name} — {rel}:{line} — 期望 {expected}，实际 {actual}"
        )


def read_source(root: str, rel: str) -> str:
    """读取 root 下的相对路径源码；读不到就抛异常（由 run_all 统一转成 FAIL）。"""
    with open(os.path.join(root, rel), encoding="utf-8") as handle:
        return handle.read()


def strip_comments(text: str) -> str:
    """把 Kotlin 注释（// 行注释、/* */ 块注释、含 KDoc）替换成等长空格。

    保留换行与字符偏移，使后续按原始行号报错仍然准确；跳过字符串/字符字面量，
    避免把 `https://...` 里的 `//` 误判成行注释。块注释故意**不处理嵌套**：
    Kotlin 虽允许嵌套，但注释正文里出现 `/*` 文本更常见，一旦按嵌套计数找不闭合
    就会把后面整段真代码抹成注释 —— 那是假绿，绝不能冒这个险。
    """
    chars = list(text)
    size = len(text)
    index = 0
    while index < size:
        # 三引号字符串：整体跳过（里面可能含 // 与 /* */）
        if text.startswith('"""', index):
            end = text.find('"""', index + 3)
            index = size if end < 0 else end + 3
            continue
        char = text[index]
        if char == '"':
            cursor = index + 1
            while cursor < size:
                if text[cursor] == "\\":
                    cursor += 2
                    continue
                if text[cursor] == '"':
                    cursor += 1
                    break
                if text[cursor] == "\n":
                    break
                cursor += 1
            index = cursor
            continue
        if char == "'":
            cursor = index + 1
            while cursor < size:
                if text[cursor] == "\\":
                    cursor += 2
                    continue
                if text[cursor] == "'":
                    cursor += 1
                    break
                if text[cursor] == "\n":
                    break
                cursor += 1
            index = cursor
            continue
        if text.startswith("//", index):
            end = text.find("\n", index)
            end = size if end < 0 else end
            for pos in range(index, end):
                chars[pos] = " "
            index = end
            continue
        if text.startswith("/*", index):
            end = text.find("*/", index + 2)
            end = size if end < 0 else end + 2
            for pos in range(index, end):
                if chars[pos] != "\n":
                    chars[pos] = " "
            index = end
            continue
        index += 1
    return "".join(chars)


def find_matching(text: str, start: int, opener: str = "(", closer: str = ")") -> int | None:
    """从 start（必须指向 opener）做括号配对，返回 closer 的下标；跳过字符串字面量。

    括号配对风格参照 tools/validate_migration.py 的 parse_db_version / scan_execsql：
    不靠行数或正则贪心猜边界，逐字符计数才不会被注释/字符串里的括号骗到。
    """
    depth = 0
    index = start
    size = len(text)
    while index < size:
        if text.startswith('"""', index):
            end = text.find('"""', index + 3)
            index = size if end < 0 else end + 3
            continue
        char = text[index]
        if char == '"':
            cursor = index + 1
            while cursor < size:
                if text[cursor] == "\\":
                    cursor += 2
                    continue
                if text[cursor] == '"':
                    cursor += 1
                    break
                if text[cursor] == "\n":
                    break
                cursor += 1
            index = cursor
            continue
        if char == opener:
            depth += 1
        elif char == closer:
            depth -= 1
            if depth == 0:
                return index
        index += 1
    return None


def line_no(text: str, offset: int) -> int:
    """把字符偏移换算成 1 起算的行号。"""
    return text.count("\n", 0, offset) + 1


def const_int(clean: str, name: str) -> tuple[int | None, int | None]:
    """从已剔除注释的源码里读 `const val <name> = <数字>`，返回 (值, 偏移)。"""
    match = re.search(
        r"\bconst\s+val\s+" + re.escape(name) + r"\s*=\s*(\d+)\b", clean
    )
    if match is None:
        return None, None
    return int(match.group(1)), match.start()


def check_a1(root: str, result: RuleResult) -> None:
    """A1 阈值严格大于并发度 + 三个老文件不得有字面量重定义。"""
    clean = strip_comments(read_source(root, SYNC_LIMITS))
    concurrency, _ = const_int(clean, "MAX_CONCURRENT_SYNC")
    threshold, threshold_offset = const_int(clean, "FAIL_THRESHOLD")
    if concurrency is None or threshold is None:
        result.bad(
            SYNC_LIMITS, 1,
            "从 SyncLimits.kt 源码读出两个 const 值（并发度绝不写死 6）",
            f"MAX_CONCURRENT_SYNC={concurrency}, FAIL_THRESHOLD={threshold}",
        )
    else:
        line = line_no(clean, threshold_offset or 0)
        if threshold > concurrency:
            result.ok(
                SYNC_LIMITS, line,
                "FAIL_THRESHOLD 严格大于 MAX_CONCURRENT_SYNC（否则单波并发超时凑满阈值即误熔）",
                f"{threshold} > {concurrency}（两个值均从源码读取）",
            )
        else:
            result.bad(
                SYNC_LIMITS, line,
                "FAIL_THRESHOLD 严格大于 MAX_CONCURRENT_SYNC",
                f"{threshold} > {concurrency} 不成立（一波并发超时就会误熔、跳过上百标的）",
            )

    for old in (ASSET_SYNC_MANAGER, BINANCE_HOST_GATE, SOURCE_CIRCUIT_BREAKER):
        # 先剔注释：KDoc 里大量出现「8 > MAX_CONCURRENT_SYNC = 6」「FAIL_THRESHOLD=3」
        # 这类历史说明，不剔就会把注释当定义误伤（SourceCircuitBreaker.kt:44、
        # BinanceHostGate.kt:62 等）。
        old_clean = strip_comments(read_source(root, old))
        # ① 任何形式（含去掉 const）的数字字面量重定义
        for match in LITERAL_REDEF.finditer(old_clean):
            result.bad(
                old, line_no(old_clean, match.start()),
                "两个常量只在 common/SyncLimits.kt 定义，老文件必须写 const val X = SyncLimits.X 引用",
                f"把字面量 {match.group(1)} 复制回来了（含去掉 const 的 val 形式）",
            )
        # ② 任一 val/const val 定义的 RHS 必须精确等于 SyncLimits.<同名>
        for match in ANY_REDEF.finditer(old_clean):
            name = match.group(1)
            rhs = match.group(2).strip()
            if not rhs:
                # 定义换行时取下一行第一个非空片段，避免误伤合法的跨行写法
                rhs = old_clean[match.end():].lstrip().split("\n", 1)[0].strip()
            expected_rhs = f"SyncLimits.{name}"
            if rhs != expected_rhs:
                result.bad(
                    old, line_no(old_clean, match.start()),
                    f"{name} 只允许 `= {expected_rhs}` 引用（唯一来源是 common/SyncLimits.kt）",
                    f"RHS={rhs!r} 不是 {expected_rhs!r}",
                )


def check_a2(root: str, result: RuleResult) -> None:
    """A2 版本链完整：@Database(version) == 最新 schema json，且迁移编号 1_2…(N-1)_N 连续。"""
    clean = strip_comments(read_source(root, STOCK_DATABASE))
    expected = (
        "@Database(version=N) == 最新 schema json 版本，allMigrations() 条目数 == N-1，"
        "且编号序列恰好为 1_2…(N-1)_N（无重复 / 缺号 / 跳号 / 乱序）"
    )

    def fmt_pair(pair: tuple[int, int]) -> str:
        """把 (1, 2) 格式化成 1_2，供期望/实际序列的可读输出。"""
        return f"{pair[0]}_{pair[1]}"

    annotation = re.search(r"@Database\s*\(", clean)
    if annotation is None:
        result.bad(STOCK_DATABASE, 1, expected, "找不到 @Database 注解")
        return
    close = find_matching(clean, annotation.end() - 1)
    if close is None:
        result.bad(STOCK_DATABASE, line_no(clean, annotation.start()), expected, "@Database(...) 括号不配对")
        return
    version_match = re.search(r"\bversion\s*=\s*(\d+)", clean[annotation.end():close])
    if version_match is None:
        result.bad(STOCK_DATABASE, line_no(clean, annotation.start()), expected, "@Database 注解里找不到 version = N")
        return
    version = int(version_match.group(1))
    version_line = line_no(clean, annotation.start())

    schema_root = os.path.join(root, SCHEMA_DIR)
    json_versions = [
        int(path.stem)
        for path in Path(schema_root).glob("*.json")
        if path.stem.isdigit()
    ]
    latest = max(json_versions) if json_versions else None

    migrations_match = re.search(
        r"fun\s+allMigrations\s*\(\s*\)\s*:\s*Array<Migration>\s*=\s*arrayOf\s*\(",
        clean,
    )
    if migrations_match is None:
        result.bad(STOCK_DATABASE, version_line, expected, "找不到 allMigrations(): Array<Migration> = arrayOf(...)")
        return
    migrations_close = find_matching(clean, migrations_match.end() - 1)
    if migrations_close is None:
        result.bad(STOCK_DATABASE, version_line, expected, "allMigrations() 的 arrayOf(...) 括号不配对")
        return
    body = clean[migrations_match.end():migrations_close]
    pairs = [
        (int(start), int(end))
        for start, end in re.findall(r"\bMIGRATION_(\d+)_(\d+)\b", body)
    ]
    count = len(pairs)

    # 只数条目会被「拿 MIGRATION_1_2 顶替 MIGRATION_2_3」骗过：条数仍是 17，
    # 但 Room 升级链缺了 2→3 这一环（QA 实验 E）。要求顺序 / 编号 / 数量与
    # 1_2…(N-1)_N 完全一致，这一条同时覆盖无重复、无缺号、无跳号，
    # 以及「每段 end == 下一段 start」（expected_pairs 本身就是首尾相接的）。
    expected_pairs = [(v, v + 1) for v in range(1, version)]
    sequence_ok = pairs == expected_pairs
    link_ok = all(pairs[i][1] == pairs[i + 1][0] for i in range(len(pairs) - 1))
    duplicates = sorted({p for p in pairs if pairs.count(p) > 1})
    missing = sorted(set(expected_pairs) - set(pairs))
    unexpected = sorted(set(pairs) - set(expected_pairs))

    seq_expected = ",".join(fmt_pair(p) for p in expected_pairs)
    seq_actual = ",".join(fmt_pair(p) for p in pairs) or "(空)"
    actual = (
        f"version={version}，最新 schema json={latest}，allMigrations 条目={count}"
        f"（应为 version-1={version - 1}），编号序列 {seq_expected} 连续且无重复"
    )
    if latest == version and count == version - 1 and sequence_ok and link_ok:
        result.ok(STOCK_DATABASE, version_line, expected, actual)
    else:
        detail = (
            f"期望序列=[{seq_expected}]；实际序列=[{seq_actual}]；"
            f"重复编号={[fmt_pair(p) for p in duplicates]}；"
            f"缺失编号={[fmt_pair(p) for p in missing]}；"
            f"多余编号={[fmt_pair(p) for p in unexpected]}；"
            f"每段 end==下段 start：{link_ok}；"
            f"version={version}，最新 schema json={latest}，条目={count}"
        )
        result.bad(STOCK_DATABASE, version_line, expected, detail)


def function_body_lines(clean: str, name: str) -> tuple[int, int, int] | None:
    """定位 `fun <name>(...) { ... }`，返回 (声明行, 函数体起始行, 函数体结束行)。

    用花括号配对而不是「到下一个 fun 为止」：函数体里可能有嵌套 lambda 的花括号，
    按行数猜边界会在重构后悄悄错位。
    """
    match = re.search(r"\bfun\s+" + re.escape(name) + r"\s*\(", clean)
    if match is None:
        return None
    brace = clean.find("{", match.end())
    if brace < 0:
        return None
    end = find_matching(clean, brace, "{", "}")
    if end is None:
        return None
    return line_no(clean, match.start()), line_no(clean, brace), line_no(clean, end)


def check_a3(root: str, result: RuleResult) -> None:
    """A3 savePreferences( 调用点只允许在 DashboardPreferencesManager.updatePreferences 内。

    只扫 app/src/main/**/*.kt：**app/src/test 不扫描** —— 现有约 8 处测试直接调用
    savePreferences 构造初始状态是合法用法，扫了只会误报、逼人去改测试（方案 a 明确不动测试）。
    """
    prefs_clean = strip_comments(read_source(root, DASHBOARD_PREFS))
    span = function_body_lines(prefs_clean, "updatePreferences")
    if span is None:
        result.bad(DASHBOARD_PREFS, 1, "能定位 updatePreferences 函数体", "找不到或花括号不配对")
        return
    declaration_line, body_start, body_end = span

    main_root = os.path.join(root, MAIN_SRC)
    kt_files = sorted(Path(main_root).rglob("*.kt"))
    violations: list[tuple[str, int, str]] = []
    legal_calls = 0
    for path in kt_files:
        rel = os.path.relpath(str(path), root).replace(os.sep, "/")
        clean = strip_comments(path.read_text(encoding="utf-8"))
        for number, line in enumerate(clean.splitlines(), start=1):
            if SAVE_DECL.search(line):
                # `fun savePreferences(...)` 是定义本身，不算调用点
                continue
            for _ in SAVE_CALL.finditer(line):
                if rel == DASHBOARD_PREFS and body_start <= number <= body_end:
                    legal_calls += 1
                else:
                    violations.append((rel, number, line.strip()[:90]))

    if violations:
        for rel, number, snippet in violations:
            result.bad(
                rel, number,
                "savePreferences( 调用点只允许出现在 DashboardPreferencesManager.kt 的 updatePreferences 函数体内",
                f"越界调用：{snippet}",
            )
    else:
        result.ok(
            DASHBOARD_PREFS, declaration_line,
            "savePreferences( 调用点只在 updatePreferences 体内（app/src/test 不扫描）",
            f"0 处越界，{legal_calls} 处合法（扫描 {len(kt_files)} 个 main .kt）",
        )


def check_a4(root: str, result: RuleResult) -> None:
    """A4 Compose 版本组合白名单，任一漂移（含声明缺失）FAIL。"""
    clean = strip_comments(read_source(root, BUILD_GRADLE))
    drift: list[tuple[str, int, str, str]] = []
    found: list[tuple[str, int, str]] = []
    for label, pattern, expected in EXPECTED_VERSIONS:
        matches = list(re.finditer(pattern, clean))
        if not matches:
            drift.append((label, 1, "声明缺失或被改名", expected))
            continue
        for match in matches:
            actual = match.group(1)
            if actual != expected:
                drift.append((label, line_no(clean, match.start()), actual, expected))
            else:
                found.append((label, line_no(clean, match.start()), actual))
    if drift:
        for label, number, actual, expected in drift:
            result.bad(BUILD_GRADLE, number, f"{label} 固定为 {expected}", actual)
    else:
        bom_line = next(number for label, number, _ in found if label == "compose-bom")
        result.ok(
            BUILD_GRADLE, bom_line,
            "compose-bom + 3 个 lifecycle-* + activity-compose 五项版本组合固定不变",
            ", ".join(f"{label}={value}" for label, _, value in found),
        )


def check_a5(root: str, result: RuleResult) -> None:
    """A5 剔除注释后 main 源码里不得有手动 Accept-Encoding 头。

    SinaStockAPI.kt:68 / DividendService.kt:51 等注释里长篇解释「绝不能手动加该头」，
    这些词**必须不算命中**；所以先 strip_comments 再匹配真实代码里的 header 构造。
    """
    main_root = os.path.join(root, MAIN_SRC)
    kt_files = sorted(Path(main_root).rglob("*.kt"))
    hits: list[tuple[str, int, str, str]] = []
    for path in kt_files:
        rel = os.path.relpath(str(path), root).replace(os.sep, "/")
        clean = strip_comments(path.read_text(encoding="utf-8"))
        for number, line in enumerate(clean.splitlines(), start=1):
            kinds = [kind for kind, pattern in HEADER_PATTERNS if pattern.search(line)]
            if not kinds and BARE_HEADER_LITERAL.search(line):
                # 兜底：任何其它写法的裸 "Accept-Encoding" 字符串字面量也不许留下
                kinds = ["裸字符串字面量残留"]
            if kinds:
                hits.append((rel, number, "/".join(kinds), line.strip()[:90]))

    expected = "剔除 // 行注释、/* */ 块注释与 KDoc 后，main 源码 0 处手动 Accept-Encoding 头"
    if hits:
        for rel, number, kind, snippet in hits:
            result.bad(rel, number, expected, f"{kind}：{snippet}")
    else:
        result.ok(
            DIVIDEND_SERVICE, 46, expected,
            f"0 处（扫描 {len(kt_files)} 个 main .kt；注释中出现的该词不计）",
        )


def check_a6(root: str, result: RuleResult) -> None:
    """A6 Timeouts.CALL_MS 不得下调到 20000 以下。"""
    clean = strip_comments(read_source(root, API_CONFIG))
    match = re.search(r"\bconst\s+val\s+CALL_MS\s*=\s*(\d+)\b", clean)
    if match is None:
        result.bad(API_CONFIG, 1, "能从 APIConfig.kt 读出 const val CALL_MS", "找不到该常量")
        return
    value = int(match.group(1))
    number = line_no(clean, match.start())
    if value >= CALL_MS_MIN:
        result.ok(API_CONFIG, number, f"CALL_MS >= {CALL_MS_MIN}（不得下调）", f"{value}ms")
    else:
        result.bad(
            API_CONFIG, number, f"CALL_MS >= {CALL_MS_MIN}（不得下调）",
            f"{value}ms（下调会让单波并发各自挂满 callTimeout、堵死信号量）",
        )


CHECKS = (
    ("A1 阈值>并发", check_a1),
    ("A2 DB版本链", check_a2),
    ("A3 偏好写入收口", check_a3),
    ("A4 Compose版本组合白名单", check_a4),
    ("A5 禁手动Accept-Encoding", check_a5),
    ("A6 超时下限", check_a6),
)


def run_all(root: str) -> list[RuleResult]:
    """跑全部六组断言，返回结构化结果，供 main 与自测复用。"""
    results: list[RuleResult] = []
    for name, check in CHECKS:
        result = RuleResult(name)
        try:
            check(root, result)
        except Exception as exc:  # 读不到源码 / 解析失败一律 FAIL，绝不静默跳过造成假绿
            result.bad(
                "(源码解析)", 1, "源码可读、可解析",
                f"{type(exc).__name__}: {exc}",
            )
        if not result.lines:
            # 规则没写任何输出也是异常，补一条 FAIL 防止「空结果被当成通过」
            result.bad("(源码解析)", 1, "至少一条 PASS/FAIL 输出", "规则未产出任何断言")
        results.append(result)
    return results


def main(argv: list[str] | None = None) -> None:
    """入口：逐行打印每条规则，任一 FAIL 即退出码 1。"""
    # Windows 控制台默认 cp936，中文输出会乱码；统一切成 UTF-8，
    # 也让 check_invariants_selftest.py 用 encoding="utf-8" 解码子进程输出时不会错位。
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except (AttributeError, ValueError):
        pass

    parser = argparse.ArgumentParser(description="金库 App 源码不变量静态校验")
    parser.add_argument(
        "--root", default=ROOT,
        help="仓库根目录（默认：本脚本上一级的上一级）；自测传临时源码副本目录",
    )
    args = parser.parse_args(argv)

    results = run_all(args.root)
    for result in results:
        for line in result.lines:
            print(line)
    failed = [result.name for result in results if not result.passed]
    if failed:
        print("INVARIANTS FAILED：" + "、".join(failed))
        sys.exit(1)
    print(f"INVARIANTS PASSED ({len(results)}/{len(results)})")


if __name__ == "__main__":
    main()
