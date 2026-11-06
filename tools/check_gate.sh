#!/usr/bin/env bash
#
# 本地/CI 校验闸门（gate）——改动提交前跑一遍。
#
# 为什么必须有 `compileDebugAndroidTestKotlin` 这一条：
#   androidTest 源码**编译**不需要真机/模拟器（跑才需要），约 30~40 秒就能出结果。
#   但历史上门户只跑 testDebugUnitTest（它只编译 src/test，不碰 androidTest），
#   于是 androidTest 长期处于「完全无人看管」状态——DashboardScreenTest.kt 引用了
#   Dashboard 重构后早已删除的 AssetItem / LiabilityItem / AssetTypeSection，
#   编译失败 19 处却无人察觉；更糟的是它导致**整个 androidTest 模块编译失败**，
#   连 MigrationTest.kt 都被拖得根本跑不起来。
#   只要这条命令在闸门里，下次再腐化会在提交前就暴露。
#
# 为什么**三条 gradle 任务**都必须带 `--rerun`（Gradle 8.13）：
#   任务 UP-TO-DATE（或 CI 开 build cache 后 FROM-CACHE）时 Gradle 会**直接跳过**并退出 0。
#   compileDebugKotlin / compileDebugAndroidTestKotlin 被跳过 = 源码改坏了照样绿；
#   testDebugUnitTest 被跳过 = 「GATE PASSED 但一条测试都没跑」。一个会假绿的闸门比没有
#   闸门更危险，它给人虚假的安全感。所以三条一律 --rerun；单测额外解析结果 XML
#   断言「真的跑到了测试」。
#
# 为什么最前面新增 2 步毫秒级静态检查（check_invariants.py + 其 selftest）：
#   「8>6 阈值/并发」、「@Database 版本链」、「savePreferences 收口」、「Compose 版本组合」、
#   「禁手动 Accept-Encoding」、「CALL_MS 下限」、「锁Room版本v18」、「同步入口收口Coordinator」、
#   「血缘零持久化+源标识唯一」这 9 组隐性不变量此前全靠人记。两步纯读源码、
#   不依赖 gradle、毫秒级，刻意放在所有 gradle 任务之前做 fast-fail：改坏一处立刻红，
#   不必先等几分钟编译。第二步用故意写坏的源码副本反证校验器自己不会假绿
#   （旧版 validate_migration.py 写死目标版本导致假绿的教训）。
#   两步**必须放在 `if [[ "$SKIP_UNIT_TEST" ... ]]` 之外**：改文档 / 改脚本走
#   `--skip-unit-test` 快速通道时也要跑，否则这些不变量被突破无人察觉。
#
# 用法（Windows / Git Bash）：
#   bash tools/check_gate.sh
# 也可以跳过单测（改文档/改脚本时的快速通道）：
#   bash tools/check_gate.sh --skip-unit-test
#
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

# Windows 用 gradlew.bat，macOS/Linux 用 ./gradlew
if [[ -f gradlew.bat ]]; then
    GRADLEW="./gradlew.bat"
else
    GRADLEW="./gradlew"
fi

# python 命令（validate_migration*.py 与下面的结果统计都要用）
PY_CMD="python"
command -v "$PY_CMD" >/dev/null 2>&1 || PY_CMD="python3"

SKIP_UNIT_TEST=0
for arg in "$@"; do
    case "$arg" in
        --skip-unit-test) SKIP_UNIT_TEST=1 ;;
        *) echo "未知参数: $arg"; exit 2 ;;
    esac
done

# 中文项目路径下 gradle 直接输出会乱码，统一重定向到文件后再 grep。
#
# ⚠️ Git Bash 下别用 Windows cmd 的 `>nul` / `2>nul`：`nul` 在 POSIX 里只是普通文件名，
#    会在仓库根留下一个 `nul` 幽灵文件（历史遗留已清理）。一律用 `>/dev/null` / `2>/dev/null`。
#    gradlew.bat 里的 `>NUL` 由 cmd.exe 解释（Windows 空设备名），语义正确、不在此列。
#
# 每个步骤用**独立**的日志文件：否则某步失败后，下一步 PASS 会把日志覆盖并删除，
# 失败原因永久丢失（常见非编译类失败：gradle 锁超时 / OOM / daemon 崩溃 / python 不在 PATH）。
run_step() {
    local name="$1"; shift
    local log="build_gate_${name}.log"
    echo "==> $name"
    if "$@" > "$log" 2>&1; then
        echo "    PASS: $name"
        rm -f "$log"
        return 0
    fi
    echo "    FAIL: $name"
    if [[ ! -f "$log" ]]; then
        # 极少数情况下重定向的日志文件不存在（观察到过一次），此时不要谎报"已保留"
        echo "    ---- 日志文件 $log 不存在，无法提取失败原因 ----"
        return 1
    fi
    local ecount
    ecount=$(grep -ac "^e: " "$log")
    if [[ "${ecount:-0}" -gt 0 ]]; then
        echo "    ---- 编译错误（^e:，共 ${ecount} 条，最多显示 30 条）----"
        grep -a "^e: " "$log" | head -30
    else
        # 没有 ^e: 说明不是编译错误（锁/OOM/daemon/命令缺失等），打印尾部才有线索
        echo "    ---- 无 ^e: 编译错误，打印日志末尾 30 行（非编译类失败）----"
        tail -n 30 "$log"
    fi
    echo "    ---- 完整日志已保留：$(pwd)/$log ----"
    return 1
}

# 断言「单测真的执行了」，而不只是 gradle 退出 0。
# Gradle 8.13 下任务 UP-TO-DATE / FROM-CACHE 会静默跳过，
# 只靠退出码会把「0 条测试」判成绿 —— 这是本闸门最需要防的失败模式。
# 另外两处收紧：`skipped > 0` 也要判红（有人给一批用例加 @Ignore 不该悄悄过），
# 以及结果 XML 的 mtime 必须不早于本轮开始（否则是沿用上一轮旧 XML 的假绿）。
verify_test_count() {
    local out
    out=$("$PY_CMD" - <<'PY'
import glob, os, sys, xml.etree.ElementTree as ET
# 未传环境变量时回退 0（任何 mtime 都 >= 0），保证单独调用本函数不会误判红。
start = int(os.environ.get('GATE_START_EPOCH', '0'))
files = tot = fail = err = skip = 0
stale = []
for p in glob.glob('app/build/test-results/testDebugUnitTest/*.xml'):
    r = ET.parse(p).getroot()
    files += 1
    tot += int(r.get('tests', 0))
    fail += int(r.get('failures', 0))
    err += int(r.get('errors', 0))
    skip += int(r.get('skipped', 0))
    # 结果文件必须在本轮开始后才被写入；旧文件 = 测试被跳过、沿用了上一轮结果。
    if os.path.getmtime(p) < start:
        stale.append(p)
# 第 6 个字段是过期文件数；具体清单走 stderr，不污染 shell 的 `set -- $out` 解析。
print(files, tot, fail, err, skip, len(stale))
for p in stale:
    print('    STALE: ' + p, file=sys.stderr)
PY
)
    # shellcheck disable=SC2086
    set -- $out
    local files="${1:-0}" total="${2:-0}" fails="${3:-0}" errs="${4:-0}" skips="${5:-0}" stales="${6:-0}"

    if [[ "$total" -le 0 ]]; then
        echo "    FAIL: 解析到 0 条测试 —— 测试任务被 UP-TO-DATE/FROM-CACHE 跳过，"
        echo "          或结果目录为空。这是**假绿灯**，必须查清（确认 --rerun 生效）。"
        return 1
    fi
    echo "    单测结果: ${total} 例 / 失败 ${fails} / 错误 ${errs} / 跳过 ${skips}（${files} 个结果文件）"
    if [[ "$stales" -ne 0 ]]; then
        echo "    FAIL: ${stales} 个结果 XML 的 mtime 早于本轮开始（GATE_START_EPOCH=${GATE_START_EPOCH:-0}）——"
        echo "          结果文件早于本轮开始，测试可能被 UP-TO-DATE/FROM-CACHE 跳过，这是**假绿灯**。"
        return 1
    fi
    if [[ "$fails" -ne 0 || "$errs" -ne 0 ]]; then
        echo "    FAIL: 存在失败/错误的用例"
        return 1
    fi
    if [[ "$skips" -ne 0 ]]; then
        echo "    FAIL: 存在被 @Ignore 跳过的用例（${skips} 例），需说明原因"
        return 1
    fi
    return 0
}

FAILED=0

# 本轮开始的 epoch 秒：verify_test_count 用它判断结果 XML 是不是本轮真跑出来的。
# 若结果文件早于本轮开始，说明 testDebugUnitTest 被 UP-TO-DATE/FROM-CACHE 跳过、
# 只是沿用了上一轮旧 XML —— 那是最隐蔽的假绿灯。`date +%s` 在 Git Bash 与
# Ubuntu runner 都可用；--skip-unit-test 快速通道不调用 verify_test_count，不受影响。
GATE_START_EPOCH=$(date +%s)
export GATE_START_EPOCH

# 毫秒级静态不变量检查：放最前 fast-fail；**不受 --skip-unit-test 影响**（见文件头说明）。
# selftest 用故意写坏的源码副本反证校验器本身不会假绿。
run_step "check_invariants.py" "$PY_CMD" tools/check_invariants.py || FAILED=1
run_step "check_invariants_selftest.py" "$PY_CMD" tools/check_invariants_selftest.py || FAILED=1

run_step "compileDebugKotlin" "$GRADLEW" compileDebugKotlin --rerun || FAILED=1

run_step "compileDebugAndroidTestKotlin" "$GRADLEW" compileDebugAndroidTestKotlin --rerun || FAILED=1

# 离线迁移校验：不需要设备，按 1.json 建库 → 应用全部迁移 SQL → PRAGMA 双向对照最新 schema JSON，
# 并单独断言「17.json 建库只跑 MIGRATION_17_18」加出的 assets.isEarnLocked 列
run_step "validate_migration.py" "$PY_CMD" tools/validate_migration.py || FAILED=1

# 校验器自测：用故意缺列 / 列类型写错 / 多余表列 / 17→18 DDL 写坏的库，
# 证明 validate() 与 17→18 列断言真的会 FAIL（而不是像旧版那样只打印「v17 假绿」）
run_step "validate_migration_selftest.py" "$PY_CMD" tools/validate_migration_selftest.py || FAILED=1

# 迁移数据保留校验：同样不需要设备，逐位比对搬表迁移有没有把数据搬丢
run_step "validate_migration_data.py" "$PY_CMD" tools/validate_migration_data.py || FAILED=1

if [[ "$SKIP_UNIT_TEST" -eq 0 ]]; then
    run_step "testDebugUnitTest" "$GRADLEW" testDebugUnitTest --rerun || FAILED=1
    if [[ "$FAILED" -eq 0 ]]; then
        echo "==> 校验单测真的执行了（防 UP-TO-DATE 假绿灯）"
        verify_test_count || FAILED=1
    fi
fi

if [[ "$FAILED" -ne 0 ]]; then
    echo ""
    echo "GATE FAILED"
    exit 1
fi

echo ""
echo "==> 发版提醒：本闸门不含真机/模拟器验证，请按 docs/RELEASE_CHECKLIST.md 手动完成 connectedDebugAndroidTest 与 release 签名冒烟（只提示，不自动执行）"
echo "GATE PASSED"
