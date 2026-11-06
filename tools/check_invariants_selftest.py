#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
【不变量校验器的自测 · 证明 tools/check_invariants.py 不会假绿】

`tools/validate_migration.py` 曾把目标版本写死成 17、只做单向检查，写错列名照样打印
VALIDATION PASSED —— 一个会假绿的防线比没有防线更危险。本脚本照
`validate_migration_selftest.py` 的路子做**负向反证**：在 tempfile 临时目录里复制一份
未改动的源码副本，逐项只改坏一个点，用 subprocess 调用校验器真实入口，断言：

  0. 未改坏的副本：9/9 全绿、退出码 0；
  1~11. 九组各改坏一处（A1 两处、A2 两处）-> 必须 FAIL 且只点名对应规则；
  12. **只在注释里**写一句 addHeader("Accept-Encoding", ...) -> 必须仍全绿
      （证明 A5 靠「剔除注释」而非「恰好没这行」过关，注释里的词不得误报）。

合计 13 条断言：未改坏副本 + 11 个坏样本 + 纯注释注入。

「只点名对应规则」是刻意的：一次只改一个不变量，其余八组必须仍 PASS。
若改了一处阈值就九组全红，说明校验器的解析互相串味，同样是不可接受的假象。

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
PRICE_CACHE_MANAGER = "app/src/main/java/com/example/stockdiary/data/price/PriceCacheManager.kt"
SCHEMA_18 = "app/schemas/com.example.stockdiary.database.StockDatabase/18.json"
SCHEMA_19 = "app/schemas/com.example.stockdiary.database.StockDatabase/19.json"

RULES = ("A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8", "A9")


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


# 每个坏样本：rule 为期望 FAIL 的规则；mutations 为（文件, 原文, 写坏后）列表；
# add_files 为（源相对路径, 目标相对路径）列表（在副本内复制，用于构造「完美一致却危险」的 v19）。
# 目录名带序号：同名规则有多个用例时（A1/A2）不带序号会撞目录。
BAD_CASES = [
    {"rule": "A1", "label": "阈值改 5（≤ 并发度 6，单波抖动即误熔）",
     "mutations": [(SYNC_LIMITS, "const val FAIL_THRESHOLD = 8", "const val FAIL_THRESHOLD = 5")]},
    {"rule": "A2", "label": "@Database(version) 改成 19（与 18.json 脱节）",
     "mutations": [(STOCK_DATABASE, "    version = 18,", "    version = 19,")]},
    {"rule": "A5", "label": "插入一行真实 addHeader(\"Accept-Encoding\", ...) 手动头",
     "mutations": [(SINA_STOCK_API,
                    '                .addHeader("Referer", "https://finance.sina.com.cn")',
                    '                .addHeader("Referer", "https://finance.sina.com.cn")\n'
                    '                .addHeader("Accept-Encoding", "gzip")')]},
    {"rule": "A6", "label": "CALL_MS 从 20000 下调到 5000",
     "mutations": [(API_CONFIG, "const val CALL_MS = 20000", "const val CALL_MS = 5000")]},
    {"rule": "A4", "label": "compose-bom 从 2024.02.00 漂到 2024.03.00",
     "mutations": [(BUILD_GRADLE,
                    'implementation(platform("androidx.compose:compose-bom:2024.02.00"))',
                    'implementation(platform("androidx.compose:compose-bom:2024.03.00"))')]},
    {"rule": "A3", "label": "在 PortfolioViewModel 里加整块 savePreferences(...) 直接写入",
     "mutations": [(PORTFOLIO_VM,
                    "    private val repository = container.assetRepository",
                    "    private val repository = container.assetRepository\n"
                    "    private val _invariantProbe = container.dashboardPreferencesManager"
                    ".savePreferences(com.example.stockdiary.data.prefs.DashboardPreferences())")]},
    {"rule": "A1", "label": "QA 实验 D：去掉 const，把并发度字面量 16 复制回 AssetSyncManager",
     "mutations": [(ASSET_SYNC_MANAGER,
                    "private const val MAX_CONCURRENT_SYNC = SyncLimits.MAX_CONCURRENT_SYNC",
                    "private val MAX_CONCURRENT_SYNC = 16")]},
    {"rule": "A2", "label": "QA 实验 E：allMigrations 里用 MIGRATION_1_2 顶替 MIGRATION_2_3",
     "mutations": [(STOCK_DATABASE,
                    "            MIGRATION_2_3,\n            MIGRATION_3_4,",
                    "            MIGRATION_1_2,\n            MIGRATION_3_4,")]},
    # 以下三条为 G6 新增（A7/A8/A9）
    {"rule": "A7", "label": "完美一致的 v19：version=19 + MIGRATION_18_19 + 19.json（A2 仍放行，A7 必须拦）",
     "mutations": [
         (STOCK_DATABASE, "    version = 18,", "    version = 19,"),
         (STOCK_DATABASE, "            MIGRATION_17_18\n        )",
          "            MIGRATION_17_18,\n            MIGRATION_18_19\n        )"),
     ],
     "add_files": [(SCHEMA_18, SCHEMA_19)]},
    {"rule": "A8", "label": "在 PortfolioViewModel 里直调 AssetSyncManager.syncAllAssets（绕过 Coordinator）",
     "mutations": [(PORTFOLIO_VM,
                    "    private val repository = container.assetRepository",
                    "    private val repository = container.assetRepository\n"
                    "    private val _a8Probe = AssetSyncManager.syncAllAssets(")]},
    {"rule": "A9", "label": "PriceCacheManager 把 source 默认硬编码回 \"sina\"（重演腾讯源价谎称新浪）",
     "mutations": [(PRICE_CACHE_MANAGER,
                    'jsonObject.optString("source", SourceIds.SINA)',
                    'jsonObject.optString("source", "sina")')]},
]


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

    with tempfile.TemporaryDirectory() as tmp:
        # 0. 未改坏的副本必须全绿
        baseline = os.path.join(tmp, "baseline")
        os.makedirs(baseline)
        make_root(baseline)
        code, output = run_checker(baseline)
        expect(
            "未改坏的源码副本 9/9 全绿、退出码 0",
            code == 0 and "INVARIANTS PASSED (9/9)" in output,
            f"rc={code}, 输出尾部={output[-500:]!r}",
        )

        # 1~11. 逐项负向反证：只改坏一处，必须 FAIL 且只点名对应规则
        for index, case in enumerate(BAD_CASES, start=1):
            rule = case["rule"]
            case_root = os.path.join(tmp, f"case_{index}_{rule}")
            os.makedirs(case_root)
            make_root(case_root)
            for rel, old, new in case.get("mutations", []):
                mutate(case_root, rel, old, new)
            for src, dst in case.get("add_files", []):
                shutil.copy2(os.path.join(case_root, src), os.path.join(case_root, dst))
            code, output = run_checker(case_root)
            hit = f"FAIL: {rule}" in output
            others = [
                other for other in RULES
                if other != rule and f"FAIL: {other}" in output
            ]
            expect(
                f"改坏「{case['label']}」→ 校验器 FAIL 且只点名 {rule}",
                code != 0 and hit and not others,
                f"rc={code}, 命中={hit}, 误伤={others}, 输出尾部={output[-600:]!r}",
            )

        # 12. 注释-only 注入：不得误报（A5 的注释剔除必须真的生效）
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
            code == 0 and "INVARIANTS PASSED (9/9)" in output,
            f"rc={code}, 输出尾部={output[-500:]!r}",
        )

    print("=" * 60)
    if failures:
        print(f"SELFTEST FAILED: {len(failures)} 条断言不成立")
        for item in failures:
            print("  -", item)
        sys.exit(1)
    print(
        "SELFTEST PASSED: 13 条断言全部成立（11 处写坏的源码副本各点名对应规则 FAIL，"
        "未改坏副本与纯注释注入均全绿）"
    )


if __name__ == "__main__":
    main()
