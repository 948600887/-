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
# 用法（Windows / Git Bash）：
#   bash tools/check_gate.sh
# 也可以只跑某一步，例如：
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

SKIP_UNIT_TEST=0
for arg in "$@"; do
    case "$arg" in
        --skip-unit-test) SKIP_UNIT_TEST=1 ;;
        *) echo "未知参数: $arg"; exit 2 ;;
    esac
done

# 中文项目路径下 gradle 直接输出会乱码，统一重定向到文件后再 grep
LOG="build_gate.log"
run_step() {
    local name="$1"; shift
    echo "==> $name"
    if "$@" > "$LOG" 2>&1; then
        echo "    PASS: $name"
        rm -f "$LOG"
        return 0
    fi
    echo "    FAIL: $name"
    echo "    ---- 编译错误（^e:）----"
    grep -a "^e: " "$LOG" | head -30
    echo "    ---- 完整日志见 $(pwd)/$LOG ----"
    return 1
}

FAILED=0

run_step "compileDebugKotlin" "$GRADLEW" compileDebugKotlin || FAILED=1

run_step "compileDebugAndroidTestKotlin" "$GRADLEW" compileDebugAndroidTestKotlin || FAILED=1

# 离线迁移校验：不需要设备，按 1.json 建库 → 应用全部迁移 SQL → PRAGMA 对照当前 schema JSON
run_step "validate_migration.py" python tools/validate_migration.py || FAILED=1

if [[ "$SKIP_UNIT_TEST" -eq 0 ]]; then
    run_step "testDebugUnitTest" "$GRADLEW" testDebugUnitTest || FAILED=1
fi

if [[ "$FAILED" -ne 0 ]]; then
    echo ""
    echo "GATE FAILED"
    exit 1
fi

echo ""
echo "GATE PASSED"
