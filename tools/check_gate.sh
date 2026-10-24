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
# 为什么 `testDebugUnitTest` 必须带 `--rerun`（Gradle 8.13）：
#   任务 UP-TO-DATE（或 CI 开 build cache 后 FROM-CACHE）时 Gradle 会**直接跳过**并退出 0，
#   表现为「GATE PASSED 但一条测试都没跑」——一个会假绿的闸门比没有闸门更危险，
#   它给人虚假的安全感。所以除了 --rerun，本脚本还会解析测试结果 XML 断言「真的跑到了测试」。
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
verify_test_count() {
    local out
    out=$("$PY_CMD" - <<'PY'
import glob, xml.etree.ElementTree as ET
files = tot = fail = err = skip = 0
for p in glob.glob('app/build/test-results/testDebugUnitTest/*.xml'):
    r = ET.parse(p).getroot()
    files += 1
    tot += int(r.get('tests', 0))
    fail += int(r.get('failures', 0))
    err += int(r.get('errors', 0))
    skip += int(r.get('skipped', 0))
print(files, tot, fail, err, skip)
PY
)
    # shellcheck disable=SC2086
    set -- $out
    local files="${1:-0}" total="${2:-0}" fails="${3:-0}" errs="${4:-0}" skips="${5:-0}"

    if [[ "$total" -le 0 ]]; then
        echo "    FAIL: 解析到 0 条测试 —— 测试任务被 UP-TO-DATE/FROM-CACHE 跳过，"
        echo "          或结果目录为空。这是**假绿灯**，必须查清（确认 --rerun 生效）。"
        return 1
    fi
    echo "    单测结果: ${total} 例 / 失败 ${fails} / 错误 ${errs} / 跳过 ${skips}（${files} 个结果文件）"
    if [[ "$fails" -ne 0 || "$errs" -ne 0 ]]; then
        echo "    FAIL: 存在失败/错误的用例"
        return 1
    fi
    return 0
}

FAILED=0

run_step "compileDebugKotlin" "$GRADLEW" compileDebugKotlin || FAILED=1

run_step "compileDebugAndroidTestKotlin" "$GRADLEW" compileDebugAndroidTestKotlin || FAILED=1

# 离线迁移校验：不需要设备，按 1.json 建库 → 应用全部迁移 SQL → PRAGMA 对照当前 schema JSON
run_step "validate_migration.py" "$PY_CMD" tools/validate_migration.py || FAILED=1

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
echo "GATE PASSED"
