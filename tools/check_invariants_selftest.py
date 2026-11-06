#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
【不变量校验器的自测 · 证明 tools/check_invariants.py 不会假绿】

`tools/validate_migration.py` 曾把目标版本写死成 17、只做单向检查，写错列名照样打印
VALIDATION PASSED —— 一个会假绿的防线比没有防线更危险。本脚本照
`validate_migration_selftest.py` 的路子做**负向反证**：在 tempfile 临时目录里复制一份
未改动的源码副本，逐项只改坏一个点，用 subprocess 调用校验器真实入口，断言：

  0. 未改坏的副本：6/6 全绿、退出码 0；
  1~6. 六组各改坏一处            -> 必须 FAIL 且只点名对应规则；
  7. **只在注释里**写一句 addHeader("Accept-Encoding", ...) -> 必须仍全绿
     （证明 A5 靠「剔除注释」而非「恰好没这行」过关，注释里的词不得误报）；
  8. QA 实验 D：去掉 const、把 `private val MAX_CONCURRENT_SYNC = 16` 复制回
     AssetSyncManager.kt -> 必须 FAIL 且只点名 A1（旧版只盯 const val 会假绿）；
  9. QA 实验 E：allMigrations() 里用 `MIGRATION_1_2,` 顶替 `MIGRATION_2_3,`
     （条数仍 17，升级链却断了 2→3 一环）-> 必须 FAIL 且只点名 A2。

合计 10 条断言：未改坏副本 + 8 个坏样本 + 纯注释注入。

「只点名对应规则」是刻意的：一次只改一个不变量，其余五组必须仍 PASS。
若改了一处阈值就六组全红，说明校验器的解析互相串味，同样是不可接受的假象。

任何一条不成立就 sys.exit(1)，由 tools/check_gate.sh 挡在提交前。
用法： python tools/check_invariants_selftest.py
"""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
CHECKER = os.path.join(HERE, "check_invariants.py")

# 校验器会读到的最小源码集合：整个 main 源码 + schemas 目录 + build.gradle.kts。
# 少了任何一个，副本就不是「未改坏」的镜像，自测自己先失真。
COPY_ITEMS = (
    "app/src/main",
    "app/schemas",
    "app/build.gradle.kts",
)

SYNC_LIMITS = "app/src/main/java/com/example/stockdiary/common/SyncLimits.kt"
ASSET_SYNC_MANAGER = "app/src/main/java/com/example/stockdiary/data/sync/AssetSyncManager.kt"
STOCK_DATABASE = "app/src/main/java/com/example/stockdiary/database/StockDatabase.kt"
SINA_STOCK_API = "app/src/main/java/com/example/stockdiary/network/SinaStockAPI.kt"
API_CONFIG = "app/src/main/java/com/example/stockdiary/common/APIConfig.kt"
BUILD_GRADLE = "app/build.gradle.kts"
PORTFOLIO_VM = "app/src/main/java/com/example/stockdiary/ui/viewmodels/PortfolioViewModel.kt"

RULES = ("A1", "A2", "A3", "A4", "A5", "A6")


def make_root(destination: str) -> None:
    """在 destination 下复制一份干净源码镜像（每个用例独立一份，改动不累积）。"""
    for rel in COPY_ITEMS:
        source = os.path.join(ROOT, rel)
        target = os.path.join(destination, rel)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        if os.path.isdir(source):
            shutil.copytree(source, target)
        else:
            shutil.copy2(source, target)


def run_checker(root: str) -> tuple[int, str]:
    """调用校验器真实入口（含 --root 与 sys.exit 语义），返回 (退出码, 合并输出)。"""
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except (AttributeError, ValueError):
        pass
    proc = subprocess.run(
        [sys.executable, CHECKER, "--root", root],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        encoding="utf-8",
        errors="replace",
    )
    return proc.returncode, proc.stdout or ""


def mutate(root: str, rel: str, old: str, new: str) -> None:
    """把副本里某文件的一处文本改成写坏的样子；找不到片段直接报错（自测不能悄悄跳过）。"""
    path = os.path.join(root, rel)
    with open(path, encoding="utf-8") as handle:
        text = handle.read()
    if old not in text:
        raise RuntimeError(f"自测样本里找不到待替换片段：{rel} :: {old!r}")
    with open(path, "w", encoding="utf-8", newline="") as handle:
        handle.write(text.replace(old, new, 1))


def main() -> None:
    """跑完全部用例并一次列出所有不成立项。"""
    failures: list[str] = []

    def expect(label: str, condition: bool, detail: str = "") -> None:
        if condition:
            print(f"  PASS: {label}")
        else:
            print(f"  FAIL: {label}" + (f" -> {detail}" if detail else ""))
            failures.append(label)

    print("== check_invariants.py 自测（故意写坏的源码副本必须被抓到）==")

    # 每个坏样本的格式：(规则号, 说明, 文件, 原文片段, 写坏后的片段)
    bad_cases = (
        (
            "A1", "阈值改 5（≤ 并发度 6，单波抖动即误熔）",
            SYNC_LIMITS,
            "const val FAIL_THRESHOLD = 8",
            "const val FAIL_THRESHOLD = 5",
        ),
        (
            "A2", "@Database(version) 改成 19（与 18.json 脱节）",
            STOCK_DATABASE,
            "    version = 18,",
            "    version = 19,",
        ),
        (
            "A5", "插入一行真实 addHeader(\"Accept-Encoding\", ...) 手动头",
            SINA_STOCK_API,
            '                .addHeader("Referer", "https://finance.sina.com.cn")',
            '                .addHeader("Referer", "https://finance.sina.com.cn")\n'
            '                .addHeader("Accept-Encoding", "gzip")',
        ),
        (
            "A6", "CALL_MS 从 20000 下调到 5000",
            API_CONFIG,
            "const val CALL_MS = 20000",
            "const val CALL_MS = 5000",
        ),
        (
            "A4", "compose-bom 从 2024.02.00 漂到 2024.03.00",
            BUILD_GRADLE,
            'implementation(platform("androidx.compose:compose-bom:2024.02.00"))',
            'implementation(platform("androidx.compose:compose-bom:2024.03.00"))',
        ),
        (
            "A3", "在 PortfolioViewModel 里加一整块 savePreferences(...) 直接写入",
            PORTFOLIO_VM,
            "    private val repository = container.assetRepository",
            "    private val repository = container.assetRepository\n"
            "    private val _invariantProbe = container.dashboardPreferencesManager"
            ".savePreferences(com.example.stockdiary.data.prefs.DashboardPreferences())",
        ),
        (
            "A1", "QA 实验 D：去掉 const，把并发度字面量 16 复制回 AssetSyncManager",
            ASSET_SYNC_MANAGER,
            "private const val MAX_CONCURRENT_SYNC = SyncLimits.MAX_CONCURRENT_SYNC",
            "private val MAX_CONCURRENT_SYNC = 16",
        ),
        (
            "A2", "QA 实验 E：allMigrations 里用 MIGRATION_1_2 顶替 MIGRATION_2_3",
            STOCK_DATABASE,
            "            MIGRATION_2_3,\n            MIGRATION_3_4,",
            "            MIGRATION_1_2,\n            MIGRATION_3_4,",
        ),
    )

    with tempfile.TemporaryDirectory() as tmp:
        # 0. 未改坏的副本必须全绿
        baseline = os.path.join(tmp, "baseline")
        os.makedirs(baseline)
        make_root(baseline)
        code, output = run_checker(baseline)
        expect(
            "未改坏的源码副本 6/6 全绿、退出码 0",
            code == 0 and "INVARIANTS PASSED (6/6)" in output,
            f"rc={code}, 输出尾部={output[-500:]!r}",
        )

        # 1~8. 逐项负向反证：只改坏一处，必须 FAIL 且只点名对应规则
        # 目录名带序号：新增的 D/E 与已有 A1/A2 用例规则号相同，不带序号会撞目录。
        for index, (rule, label, rel, old, new) in enumerate(bad_cases, start=1):
            case_root = os.path.join(tmp, f"case_{index}_{rule}")
            os.makedirs(case_root)
            make_root(case_root)
            mutate(case_root, rel, old, new)
            code, output = run_checker(case_root)
            hit = f"FAIL: {rule}" in output
            others = [
                other for other in RULES
                if other != rule and f"FAIL: {other}" in output
            ]
            expect(
                f"改坏「{label}」→ 校验器 FAIL 且只点名 {rule}",
                code != 0 and hit and not others,
                f"rc={code}, 命中={hit}, 误伤={others}, 输出尾部={output[-600:]!r}",
            )

        # 10. 注释-only 注入：不得误报（A5 的注释剔除必须真的生效）
        comment_root = os.path.join(tmp, "case_comment_only")
        os.makedirs(comment_root)
        make_root(comment_root)
        mutate(
            comment_root,
            SINA_STOCK_API,
            '                .addHeader("Referer", "https://finance.sina.com.cn")',
            '                // 自测注入：.addHeader("Accept-Encoding", "gzip") 只出现在注释里\n'
            '                .addHeader("Referer", "https://finance.sina.com.cn")',
        )
        code, output = run_checker(comment_root)
        expect(
            "只在 // 注释里出现 addHeader(\"Accept-Encoding\") 时仍全绿（注释不得误报）",
            code == 0 and "INVARIANTS PASSED (6/6)" in output,
            f"rc={code}, 输出尾部={output[-500:]!r}",
        )

    print("=" * 60)
    if failures:
        print(f"SELFTEST FAILED: {len(failures)} 条断言不成立")
        for item in failures:
            print("  -", item)
        sys.exit(1)
    print(
        "SELFTEST PASSED: 10 条断言全部成立（8 处写坏的源码副本各点名对应规则 FAIL，"
        "未改坏副本与纯注释注入均全绿）"
    )


if __name__ == "__main__":
    main()
