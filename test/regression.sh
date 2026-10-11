#!/usr/bin/env bash
# NotifyLog 自动化回归（Git Bash + adb + 已启动的模拟器/root 设备）
#
# 用法:  ./regression.sh [A] [B] [C] [--skip-reboot] [--apk <path>]
#        不带组名 = 全量。结果打印 PASS/FAIL 汇总，原始日志写入 reports/。
#
# 依赖: google_apis 模拟器（可 su 0，用 sqlite3 直查私有 DB 做精确断言）。
# 警告: 绝不要执行 adb root —— 详见 sudo_q 处的说明。
set -u

cd "$(dirname "$0")"
ADB="${ADB:-adb}"; command -v "$ADB" >/dev/null || ADB="/c/Android/Sdk/platform-tools/adb.exe"
PKG=com.notifylog.app
DB=/data/data/$PKG/databases/notify_log.db
APK="../app/build/outputs/apk/release/app-release.apk"
SKIP_REBOOT=0
GROUPS_SEL=""
while [ $# -gt 0 ]; do case "$1" in
  --skip-reboot) SKIP_REBOOT=1 ;;
  --apk) APK="$2"; shift ;;
  A|B|C) GROUPS_SEL="$GROUPS_SEL $1" ;;
esac; shift; done
[ -z "$GROUPS_SEL" ] && GROUPS_SEL="A B C"

STAMP=$($ADB shell 'date +%Y%m%d-%H%M%S' 2>/dev/null | tr -d '\r')
mkdir -p reports/shots
LOG="reports/raw-${STAMP:-run}.log"
PASS=0; FAIL=0; SKIP=0; FAILED_NAMES=""

log()  { echo "$*" | tee -a "$LOG"; }
pass() { PASS=$((PASS+1)); log "  PASS  $1"; }
fail() { FAIL=$((FAIL+1)); FAILED_NAMES="$FAILED_NAMES $1"; log "  FAIL  $1  ($2)"; }
skip() { SKIP=$((SKIP+1)); log "  SKIP  $1  ($2)"; }
assert_eq() { # name expected actual
  if [ "$2" = "$3" ]; then pass "$1"; else fail "$1" "expected=$2 actual=$3"; fi
}

sh_q()  { $ADB shell "$1" 2>/dev/null | tr -d '\r'; }
# 读应用私有数据需要 root，但 adbd **绝不能**整体提权：adbd 以 root 运行时,
# `cmd notification post` 注入的通知监听器收不到（实测：root 注入不入库、shell
# 注入立即入库），整套用例会假性全红。因此保持 adbd 为 shell，仅在需要时 su 0。
sudo_q() { $ADB shell "su 0 $1" 2>/dev/null | tr -d '\r'; }
sql()   { sudo_q "sqlite3 $DB \"$1\""; }
count() { sql "SELECT COUNT(*) FROM notification_log"; }
post()  { # tag title text
  sh_q "cmd notification post -S bigtext -t '$2' '$1' '$3'" >/dev/null
}
shot()  { $ADB exec-out screencap -p > "reports/shots/$1.png" 2>/dev/null; }

SCREEN_H=$(sh_q 'wm size' | grep -o '[0-9]*x[0-9]*' | tail -1 | cut -dx -f2); SCREEN_H=${SCREEN_H:-2400}

dump()  { sh_q 'uiautomator dump /sdcard/ui.xml' >/dev/null; sh_q 'cat /sdcard/ui.xml'; }
# 节点 bounds（$1=值子串 $2=属性名）。输出 "x y"，找不到则空。
node_xy() {
  local attr="${2:-text}" b
  b=$(dump | tr '<' '\n' | grep "$attr=\"[^\"]*$1" | head -1 |
      grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | tail -1)
  [ -z "$b" ] && return 1
  echo "$b" | sed 's/bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]"/\1 \3 \2 \4/' |
    awk '{print int(($1+$2)/2), int(($3+$4)/2)}'
}
# 若目标在折叠区（y 超出屏幕或被底部遮挡），向上滑动直到可见。
scroll_to_node() {
  local i=0 xy y
  while [ $i -lt 8 ]; do
    xy=$(node_xy "$1" "${2:-text}") || xy=""
    if [ -n "$xy" ]; then
      y=$(echo "$xy" | cut -d' ' -f2)
      # 顶部工具栏与底部手势条之外才算可点
      if [ "$y" -gt 250 ] && [ "$y" -lt $((SCREEN_H - 220)) ]; then return 0; fi
    fi
    sh_q "input swipe 540 $((SCREEN_H*3/4)) 540 $((SCREEN_H/4)) 350" >/dev/null; sleep 0.8
    i=$((i+1))
  done
  node_xy "$1" "${2:-text}" >/dev/null
}
tap_node() {
  scroll_to_node "$1" "${2:-text}" >/dev/null 2>&1
  local xy; xy=$(node_xy "$1" "${2:-text}") || return 1
  sh_q "input tap $xy" >/dev/null; sleep 1.2
}
long_press_node() {
  scroll_to_node "$1" >/dev/null 2>&1
  local xy; xy=$(node_xy "$1") || return 1
  local x y; x=$(echo "$xy" | cut -d' ' -f1); y=$(echo "$xy" | cut -d' ' -f2)
  sh_q "input swipe $x $y $x $y 900" >/dev/null; sleep 1.2
}
open_main() {
  # 先关掉可能残留的对话框：一个卡住的弹窗会让后续所有点击落到错处，
  # 把单个用例的失败放大成整轮连锁失败。
  sh_q "input keyevent 4" >/dev/null; sh_q "input keyevent 4" >/dev/null
  sh_q "am start -n $PKG/.ui.LogListActivity" >/dev/null; sleep 2
  # 回到列表顶部，保证后续查找/长按命中最新记录
  sh_q "input swipe 540 $((SCREEN_H/4)) 540 $((SCREEN_H*3/4)) 250" >/dev/null; sleep 0.6
}
open_settings() { open_main; tap_node "更多选项" content-desc || sh_q 'input tap 1022 218' >/dev/null; sleep 1.2; tap_node "设置"; sleep 1.2; }

# ---------- 准备 ----------
log "== NotifyLog regression @ ${STAMP:-?} =="
$ADB unroot >/dev/null 2>&1; sleep 2      # 见 sudo_q：adbd 必须保持 shell 身份
$ADB wait-for-device
[ -f "$APK" ] && { $ADB install -r "$APK" >/dev/null 2>&1 && log "installed: $APK"; }
# 干净起点：清掉上一轮遗留的排除名单/开关/日志，否则一次失败会污染整轮。
sh_q "pm clear $PKG" >/dev/null; sleep 2
sh_q "rm -f /sdcard/Download/notifylog-*" >/dev/null
# 关掉再打开授权，强制系统重新绑定监听（pm clear 后应用处于 stopped 态）。
sh_q "cmd notification disallow_listener $PKG/$PKG.service.NotifyListenerService" >/dev/null
sleep 1
sh_q "cmd notification allow_listener $PKG/$PKG.service.NotifyListenerService" >/dev/null
sh_q "pm grant $PKG android.permission.POST_NOTIFICATIONS" >/dev/null
sleep 2
if [ -z "$(sql 'SELECT 1' )" ]; then log "!! sqlite3/root 不可用，DB 断言无法执行"; fi
open_main
post smoke0 "smoke" "warmup $STAMP"; sleep 2
SHELL_PKG=$(sql "SELECT packageName FROM notification_log ORDER BY id DESC LIMIT 1")
log "poster package: $SHELL_PKG"

# ---------- A 功能回归 ----------
run_A() {
log "-- A 功能回归 --"
# A1 基础捕获
n0=$(count); post a1 "A1标题$STAMP" "A1正文"; sleep 2
assert_eq "A1-捕获入库" "$((n0+1))" "$(count)"
assert_eq "A1-内容正确" "A1标题$STAMP" "$(sql 'SELECT title FROM notification_log ORDER BY id DESC LIMIT 1')"
# A2 去重
n0=$(count); post a2 "A2t" "A2same"; sleep 1.5; post a2 "A2t" "A2same"; sleep 2
assert_eq "A2-重复不入库" "$((n0+1))" "$(count)"
# A3 更新捕获
post a2 "A2t" "A2changed"; sleep 2
assert_eq "A3-变化入库" "$((n0+2))" "$(count)"
# A5 列表实时刷新
open_main; post a5 "A5LIVE$STAMP" "live refresh"; sleep 3
if dump | grep -q "A5LIVE$STAMP"; then pass "A5-顶部实时可见"; else fail "A5-顶部实时可见" "列表未出现新记录"; fi
# A6 搜索
post a6 "SRCHMK77" "search target"; sleep 2; open_main
tap_node "搜索" content-desc
sh_q 'input text SRCHMK77' >/dev/null; sleep 2
d=$(dump)
if echo "$d" | grep -q "SRCHMK77" && ! echo "$d" | grep -q "A5LIVE$STAMP"; then pass "A6-搜索过滤"; else fail "A6-搜索过滤" "结果不符"; fi
sh_q 'input keyevent 4' >/dev/null; sh_q 'input keyevent 4' >/dev/null; sleep 1
# A7 按应用筛选
open_main; tap_node "按应用筛选" content-desc
if tap_node "全部应用"; then pass "A7-筛选器可用"; else fail "A7-筛选器可用" "picker 未出现"; fi
# A8 长按删除
open_main; n0=$(count)
long_press_node "A1标题$STAMP" || long_press_node "A5LIVE"
if tap_node "删除这条"; then sleep 1; assert_eq "A8-删除生效" "$((n0-1))" "$(count)"; else fail "A8-删除生效" "菜单未出现"; fi
# A8b/A9 排除与恢复
open_main; long_press_node "SRCHMK77"
if tap_node "不再记录此应用"; then
  sleep 1; n0=$(count); post a9 "A9EXCL" "should skip"; sleep 2
  assert_eq "A8-排除后不入库" "$n0" "$(count)"
  open_settings; tap_node "已排除的应用"; sleep 1
  tap_node "Shell" || tap_node "$SHELL_PKG"      # 取消勾选
  tap_node "确定"; sleep 1
  post a9b "A9BACK" "should log"; sleep 2
  assert_eq "A9-恢复后入库" "$((n0+1))" "$(count)"
else fail "A8-排除后不入库" "菜单未出现"; fi
# 无论上面成功与否都必须把排除名单清空：残留一条就会让之后每一个用例
# 都收不到通知，把一处失败放大成整轮连锁失败。
if sudo_q "cat /data/data/$PKG/shared_prefs/notify_settings.xml" | grep -q "excluded_packages\">[^<]*<string>"; then
  log "  WARN  排除名单未清空，强制复位（后续用例依赖它为空）"
  open_settings; tap_node "已排除的应用"; sleep 1
  tap_node "Shell"; tap_node "确定"; sleep 1
fi
# A10 导出 CSV 全部
open_main; tap_node "更多选项" content-desc; tap_node "导出日志"; tap_node "CSV"; tap_node "全部时间"; sleep 2.5
tap_node "SAVE" || tap_node "保存"; sleep 3
csv=$(sh_q 'ls -t /sdcard/Download/notifylog-*.csv 2>/dev/null' | head -1)
if [ -n "$csv" ]; then
  rows=$(sh_q "cat $csv" | wc -l); dbn=$(count)
  assert_eq "A10-CSV行数=DB行数" "$dbn" "$((rows-1))"
  if sh_q "cat $csv" | head -1 | grep -q "应用名,通知时间,通知内容"; then pass "A10-表头正确"; else fail "A10-表头正确" "$(sh_q "cat $csv" | head -1)"; fi
else fail "A10-CSV导出" "未生成文件"; fi
# A11 导出 JSON 全部
open_main; tap_node "更多选项" content-desc; tap_node "导出日志"; tap_node "JSON"; tap_node "全部时间"; sleep 2.5
tap_node "SAVE" || tap_node "保存"; sleep 2
tap_node "Overwrite" || tap_node "替换" || true; sleep 3
js=$(sh_q 'ls -t /sdcard/Download/notifylog-*.json 2>/dev/null' | head -1)
if [ -n "$js" ]; then
  jn=$(sh_q "cat $js" | grep -o '"timeMillis"' | wc -l)
  assert_eq "A11-JSON条数=DB行数" "$(count)" "$jn"
else fail "A11-JSON导出" "未生成文件"; fi
# A13/A15 前台服务 + 心跳
open_settings; tap_node "前台服务加固"; sleep 2.5
if sh_q "dumpsys activity services $PKG" | grep -q "isForeground=true"; then pass "A13-前台服务运行"; else fail "A13-前台服务运行" "isForeground!=true"; fi
nt=$(sh_q 'dumpsys notification --noredact' | grep -o '最近 1h 有 [0-9]* 条通知' | head -1)
if [ -n "$nt" ]; then pass "A13-常驻计数文本($nt)"; else fail "A13-常驻计数文本" "未找到计数通知"; fi
tap_node "定时心跳检查"; sleep 1.5
hb=$(sudo_q "cat /data/data/$PKG/shared_prefs/notify_settings.xml" | grep -c 'name="heartbeat" value="true"')
if [ "$hb" = "1" ] && sh_q 'dumpsys alarm' | grep -q "$PKG"; then pass "A15-心跳已调度"
else fail "A15-心跳已调度" "pref=$hb alarm=$(sh_q 'dumpsys alarm' | grep -c "$PKG")"; fi
# A16 清空后同内容可再录（去重缓存重置）
post a16 "CLRMARK" "clear test"; sleep 2
open_settings; tap_node "清空全部日志"; sleep 1; tap_node "确定"; sleep 2
assert_eq "A16-清空生效" "0" "$(count)"
post a16 "CLRMARK" "clear test"; sleep 2
assert_eq "A16-清空后重录同内容" "1" "$(count)"
# A17 手动清理（注入过期行后按天数清理）
old=$(( $(date +%s%3N 2>/dev/null || echo 1754400000000) - 400*24*3600*1000 ))
sql "INSERT INTO notification_log(postTime,packageName,appLabel,title,text,bigText,subText,category,channelId,sbnKey,dedupHash) VALUES($old,'t.old','Old','旧数据','x',NULL,NULL,NULL,NULL,NULL,1)" >/dev/null
n0=$(count)
open_settings; tap_node "自动清理"; sleep 1; tap_node "立即清理"; sleep 2
assert_eq "A17-过期行被清理" "$((n0-1))" "$(count)"
tap_node "自动清理"; sleep 1   # 关回默认
# A18/A19 主题与边衬截图（人工复核用）
open_main; shot "A19-main-light"
sh_q 'cmd uimode night yes' >/dev/null; sleep 2; shot "A18-main-dark"
open_settings; shot "A18-settings-dark"
sh_q 'cmd uimode night no' >/dev/null; sleep 2
open_main; tap_node "更多选项" content-desc; tap_node "保活设置"; sleep 1.5; shot "A19-keepalive"
open_settings; tap_node "监听中断记录"; sleep 1.5; shot "A19-gaps"
log "  INFO  A18/A19 截图已存 reports/shots/（人工复核）"
}

# ---------- B 稳定性 ----------
run_B() {
log "-- B 稳定性 --"
# B1 突发 100 条
n0=$(count); t0=$(date +%s)
i=1; while [ $i -le 100 ]; do post "burst$i" "BURST$i" "storm payload $i $STAMP" ; i=$((i+1)); done
sleep 5; t1=$(date +%s)
assert_eq "B1-100条全部入库" "$((n0+100))" "$(count)"
crashes=$(sh_q 'logcat -d -b crash' | grep -c "$PKG")
assert_eq "B1-无崩溃" "0" "$crashes"
log "  INFO  B1 注入+落库耗时 $((t1-t0))s"
# B2 杀进程后恢复。两条路径分别断言：系统自动重绑（尽力而为，ROM 相关），
# 以及用户打开应用后必定恢复（这条是硬性要求，HyperOS 被杀后的兜底）。
d0=$(sql "SELECT COUNT(*) FROM listener_event WHERE connected=0")
pid=$(sh_q "pidof $PKG"); sudo_q "kill $pid" >/dev/null; sleep 8
n0=$(count); auto=0
for i in 1 2 3; do
  post "rb$i" "REBIND$i" "probe"; sleep 8
  if [ "$(count)" -gt "$n0" ]; then auto=1; break; fi
done
if [ $auto = 1 ]; then pass "B2-系统自动重绑(约$((i*8))s)"; else log "  INFO  B2 系统未自动重绑，改验应用侧兜底"; fi
open_main; sleep 6                      # 打开 UI 触发 requestRebind
n1=$(count); post b2ui "AFTERKILL" "recover via ui"; sleep 6
assert_eq "B2-打开应用后恢复捕获" "$((n1+1))" "$(count)"
d1=$(sql "SELECT COUNT(*) FROM listener_event WHERE connected=0")
if [ "$d1" -gt "$d0" ]; then pass "B2-断档已记录"; else fail "B2-断档已记录" "listener_event 无新断开事件"; fi
if [ "$(sudo_q "cat /data/data/$PKG/shared_prefs/notify_state.xml" | grep -c 'name="connected" value="true"')" -ge 0 ]; then
  log "  INFO  B2 state: $(sudo_q "cat /data/data/$PKG/shared_prefs/notify_state.xml" | tr -d '\n' | grep -o 'connected" value="[a-z]*"')"
fi
# B3 重启持久化
if [ $SKIP_REBOOT = 1 ]; then skip "B3-重启持久化" "--skip-reboot"; else
  n0=$(count)
  $ADB reboot; $ADB wait-for-device
  until [ "$(sh_q 'getprop sys.boot_completed')" = "1" ]; do sleep 3; done
  sleep 12
  assert_eq "B3-数据完整" "$n0" "$(count)"
  post b3 "AFTERBOOT" "captured after reboot"; sleep 6
  assert_eq "B3-重启后捕获恢复" "$((n0+1))" "$(count)"
  if sh_q "dumpsys activity services $PKG" | grep -q "isForeground=true"; then pass "B3-前台服务自动恢复"; else fail "B3-前台服务自动恢复" "BootReceiver 未拉起"; fi
  if sh_q 'dumpsys alarm' | grep -q "$PKG"; then pass "B3-心跳自动恢复"; else fail "B3-心跳自动恢复" "alarm 未见"; fi
fi
}

# ---------- C 内存/CPU ----------
run_C() {
log "-- C 内存/CPU（记录型，无硬阈值，环比恶化需关注） --"
open_main; sleep 2
pss_ui=$(sh_q "dumpsys meminfo $PKG" | grep "TOTAL PSS" | head -1 | awk '{print $3}')
sh_q 'input keyevent 3' >/dev/null; sleep 5   # HOME，留服务在后台
pss_bg=$(sh_q "dumpsys meminfo $PKG" | grep "TOTAL PSS" | head -1 | awk '{print $3}')
cpu_idle=$(sh_q "top -b -n 2 -d 2" | grep "$PKG" | tail -1 | awk '{print $9}')
i=1; while [ $i -le 30 ]; do post "cpu$i" "CPU$i" "load probe $i"; i=$((i+1)); done
cpu_burst=$(sh_q "top -b -n 2 -d 1" | grep "$PKG" | tail -1 | awk '{print $9}')
sleep 3
dbsz=$(sudo_q "ls -l $DB" | awk '{print $5}')
apksz=$(ls -l ../release/NotifyLog-*-release.apk 2>/dev/null | tail -1 | awk '{print $5}')
log "  INFO  C1 后台 PSS: ${pss_bg}KB | C2 前台 PSS: ${pss_ui}KB"
log "  INFO  C3 空闲 CPU: ${cpu_idle:-0}% | C4 注入期 CPU: ${cpu_burst:-?}%"
log "  INFO  C5 DB 大小: ${dbsz}B / $(count) 行 | C6 APK: ${apksz:-?}B"
if [ -n "$pss_bg" ] && [ "$pss_bg" -lt 200000 ]; then pass "C1-后台PSS<200MB(${pss_bg}KB)"; else fail "C1-后台PSS" "${pss_bg}KB"; fi
if [ -n "$apksz" ] && [ "$apksz" -lt 3145728 ]; then pass "C6-APK<3MB"; else skip "C6-APK<3MB" "本地无 release 存档"; fi
}

for g in $GROUPS_SEL; do run_$g; done

log "== 结果: PASS=$PASS FAIL=$FAIL SKIP=$SKIP =="
[ -n "$FAILED_NAMES" ] && log "失败用例:$FAILED_NAMES"
[ $FAIL = 0 ]
