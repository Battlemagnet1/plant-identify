#!/usr/bin/env bash
# Phase 7 设备冒烟：两个包各装一次，验证「完整版专有功能确实只在完整版出现」。
#
# 轻量冒烟，不写长篇断言 —— 只做三件事：
#   1. 两个包装得上、打得开、不崩
#   2. 基础版看不到统计入口与数据管理入口
#   3. 完整版看得到统计入口，且与基础版共存
# 逐项功能（导出/备份/大图/编辑）已在 Phase 6 / Phase6+ 用 100+ 条断言覆盖，这里不重复。
#
# ⚠️ 两条防呆是必须的（第一版就栽在这里）：
#   - 交给 adb 的路径必须是 Windows 风格（C:/...）。Git Bash 的 /d/... 会让
#     adb 报 "failed to stat" —— 而 install 的退出码还可能是 0，
#     不检查就会拿**旧安装的包**继续测，断言全部失真
#   - 每次 dump 之前先确认目标应用确实在前台。否则读的是上一个包（甚至桌面）的
#     界面，「首页渲染出来了」这种断言会假通过
set -u

ADBEXE="C:/Users/a/Android/Sdk/platform-tools/adb.exe"
D="192.168.253.119:5555"
# 路径风格分两套：给 shell 用 MSYS 风格，给 adb 用 Windows 风格
REPO_MSYS="/d/Users/a/Desktop/plant Identify"
REPO_WIN="D:/Users/a/Desktop/plant Identify"   # 注意是 D 盘：Git Bash 的 /d/ 对应 D:/
APK_BASE_WIN="$REPO_WIN/app/build/outputs/apk/base/debug/app-base-debug.apk"
APK_FULL_WIN="$REPO_WIN/app/build/outputs/apk/full/debug/app-full-debug.apk"

PASS=0
FAIL=0
ok()   { PASS=$((PASS+1)); echo "  [PASS] $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  [FAIL] $1"; }
note() { echo "        $1"; }

txt() { "$ADBEXE" -s "$D" exec-out uiautomator dump /dev/tty 2>/dev/null; }

# 启动应用，组件名动态解析。
#
# 不能写死 <pkg>/.MainActivity：完整版的 applicationId 带了 .full 后缀，
# 但 namespace 仍是 com.plantidentify —— 合并后的清单里 Activity 的
# 全名是 com.plantidentify.MainActivity，**不带 .full**。
# 于是 com.plantidentify.full/.MainActivity 会展开成
# com.plantidentify.full.com.plantidentify.full.MainActivity，根本不存在。
launch() {
  local pkg="$1"
  local cmp
  cmp="$("$ADBEXE" -s "$D" shell cmd package resolve-activity --brief "$pkg" 2>/dev/null | tr -d '' | tail -1)"
  if [ -z "$cmp" ] || [ "$cmp" = "No activity found" ]; then
    echo "        解析不到 $pkg 的启动组件"
    return 1
  fi
  "$ADBEXE" -s "$D" shell am start -n "$cmp" >/dev/null 2>&1
}
front() { "$ADBEXE" -s "$D" shell dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity | grep -oE 'com\.[a-z.]+/' ; }

# 等目标包真的到前台，最多等 12 秒。等不到就返回非零 —— 调用方必须据此中止，
# 而不是拿眼前这个不知道是谁的界面继续断言
wait_front() {
  local pkg="$1"
  for _ in $(seq 1 12); do
    case "$(front)" in
      "$pkg"/*) return 0 ;;
    esac
    sleep 1
  done
  return 1
}

"$ADBEXE" connect "$D" >/dev/null 2>&1
sleep 2
if ! "$ADBEXE" -s "$D" shell echo ok >/dev/null 2>&1; then
  echo "设备不可达：$D"; exit 1
fi

echo "== 构建产物 =="
for f in base full; do
  p="$REPO_MSYS/app/build/outputs/apk/$f/debug/app-$f-debug.apk"
  if [ -f "$p" ]; then
    note "app-$f-debug.apk  $(stat -c %s "$p" | awk '{printf "%.1f MB", $1/1048576}')"
  else
    echo "缺少 app-$f-debug.apk"; exit 1
  fi
done

# ================================================================ 基础版
echo
echo "== 基础版 com.plantidentify =="
OUT=$("$ADBEXE" -s "$D" install -r "$APK_BASE_WIN" 2>&1 | tail -1)
if echo "$OUT" | grep -q "Success"; then
  ok "基础版安装成功"
else
  bad "基础版安装失败：$OUT"
  echo "  安装都没成功，后面的界面断言没有意义，中止"; exit 1
fi

"$ADBEXE" -s "$D" shell am force-stop com.plantidentify
"$ADBEXE" -s "$D" logcat -c 2>/dev/null
sleep 1
launch com.plantidentify

if wait_front com.plantidentify; then
  ok "基础版启动并进入前台"
  sleep 3
  HOME=$(txt)
  echo "$HOME" | grep -q "植物档案" && ok "基础版首页渲染（找到「植物档案」）" || bad "基础版首页没渲染出来"
  echo "$HOME" | grep -q "查看统计详情" && bad "基础版不该有统计入口" || ok "基础版没有统计入口"

  SET_POS=$(txt | tr '<' '\n' | grep -F 'text="设置"' | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1)
  if [ -n "$SET_POS" ]; then
    N=($(echo "$SET_POS" | grep -oE '[0-9]+'))
    "$ADBEXE" -s "$D" shell input tap $(( (${N[0]}+${N[2]})/2 )) $(( (${N[1]}+${N[3]})/2 ))
    sleep 3
    found=0
    for _ in 1 2 3 4 5 6; do
      if txt | grep -q "数据管理"; then found=1; break; fi
      "$ADBEXE" -s "$D" shell input swipe 960 900 960 300 400
      sleep 1
    done
    [ "$found" = "0" ] && ok "基础版设置页没有「数据管理」入口" || bad "基础版设置页出现了「数据管理」"
    "$ADBEXE" -s "$D" shell input keyevent 4
    sleep 2
  else
    bad "基础版首页找不到「设置」入口"
  fi
else
  bad "基础版启动后没进前台（当前前台：$(front)）"
fi

CRASH=$("$ADBEXE" -s "$D" logcat -d 2>/dev/null | grep -cE "FATAL EXCEPTION")
[ "$CRASH" = "0" ] && ok "基础版无崩溃" || bad "基础版崩了 $CRASH 次"

# ================================================================ 完整版
echo
echo "== 完整版 com.plantidentify.full =="
OUT=$("$ADBEXE" -s "$D" install -r "$APK_FULL_WIN" 2>&1 | tail -1)
if echo "$OUT" | grep -q "Success"; then
  ok "完整版安装成功"
else
  bad "完整版安装失败：$OUT"
  echo "  中止"; exit 1
fi

"$ADBEXE" -s "$D" shell am force-stop com.plantidentify.full
"$ADBEXE" -s "$D" logcat -c 2>/dev/null
sleep 1
launch com.plantidentify.full

if wait_front com.plantidentify.full; then
  ok "完整版启动并进入前台"
  sleep 3
  FULL=$(txt)
  echo "$FULL" | grep -q "植物档案" && ok "完整版首页渲染" || bad "完整版首页没渲染出来"
  echo "$FULL" | grep -q "查看统计详情" && ok "完整版有统计入口" || bad "完整版缺少统计入口"
else
  bad "完整版启动后没进前台（当前前台：$(front)）"
fi

BOTH=$("$ADBEXE" -s "$D" shell pm list packages 2>/dev/null | tr -d '\r' | grep -cE "^package:com\.plantidentify(\.full)?$")
[ "$BOTH" = "2" ] && ok "两个包同时存在、互不覆盖" || bad "只找到 $BOTH 个包（期望 2）"

CRASH=$("$ADBEXE" -s "$D" logcat -d 2>/dev/null | grep -cE "FATAL EXCEPTION")
[ "$CRASH" = "0" ] && ok "完整版无崩溃" || bad "完整版崩了 $CRASH 次"

echo
echo "== 合计：$PASS 通过 / $FAIL 失败 =="
[ "$FAIL" = "0" ]
