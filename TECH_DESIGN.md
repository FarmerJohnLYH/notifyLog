# NotifyLog 技术方案

一款 Android 应用：捕获所有其他应用的通知预览，持久化保存为日志。目标：**极低资源占用、最少权限、开机自启、后台常驻**。

---

## 0. 目标设备与已确认决策（2026-07-30，无人值守阶段以此为准）

**首要适配目标（优先级最高）**：

| 项 | 参数 |
|---|---|
| 机型 | 小米 17 Pro Max |
| 系统 | HyperOS 3（澎湃 OS 3），基于 Android 16 |
| SoC | 第五代骁龙 8 至尊版（性能过剩，资源不是瓶颈；瓶颈在系统后台管控） |
| API | 目标设备为 API 36（Android 16） |

> ⚠️ **本项目的头号技术风险不是性能，而是 HyperOS 激进的后台强杀与自启动管控。** 硬件再强，系统不放行自启动/后台，服务照样被杀。因此"小米专项适配"（见 §7）是本项目的重中之重，优先级高于其他打磨项。

**已确认产品决策：**

| 决策项 | 结论 |
|---|---|
| 分发方式 | **自用 / 侧载 APK**（无商店审核约束 → 可直接申请电池白名单、用前台服务加固） |
| 默认保活强度 | 默认最小权限，加固可选；**但针对小米默认引导开启加固**（见 §7） |
| 隐私保护 | MVP 暂不做（后续再加） |
| 日志保留 | **永久保留，不自动清理**；提供手动清理 + 一键清空 + 占用提示 |
| UI | 经典 View + ViewBinding |
| 去重 | 保留每个变化版本（内容相同不重复存） |
| 大图/头像 | 不存大图，仅运行时现取 App 小图标 |
| minSdk | **29（Android 10）** |
| targetSdk | **36（Android 16）** |

---

## 1. 核心原理

Android 上读取其他应用通知的**唯一官方途径**是 `NotificationListenerService`（API 18+）。它不是运行时权限，而是用户在「设置 → 通知使用权」中手动授予的**特殊访问权**。

选择它的关键优势（直接决定了整个方案的形态）：

- **系统托管生命周期**：授权后由系统主动 `bind`，进程被杀后系统会自动重新拉起并重绑，**开机后也由系统自动绑定**。这意味着：
  - 保活基本不需要前台服务、双进程、WakeLock 等黑科技；
  - 开机自启不依赖 `BOOT_COMPLETED`（保留一个接收器仅作兜底）。
- 除通知使用权外，核心功能**零额外权限**。

### 1.1 采集方案可用性确认（小米 HyperOS 3，已查证）

- **NLS 在 HyperOS 3 上可用 ✅**。依据：① 它是 AOSP 标准能力（API 18+），非厂商私有；② 小米自 MIUI 起一直在「通知使用权」中暴露该授权，大量第三方 App 依赖它；③ HyperOS 3「小米超级岛」本身构建在通知框架之上；④ 小米官方开发者平台有《HyperOS 媒体通知适配说明》。**结论：在小米上"能否读到通知"不是问题，"读到后进程能否存活"才是问题（见 §7）。**
- **辅助功能（AccessibilityService）作为备选 Plan B，仅记录不默认启用**。正确机制是监听 `TYPE_NOTIFICATION_STATE_CHANGED` 事件（非"读屏"，读屏会大量漏抓）。但它在数据质量（只有文本行、无结构化 extras、无 `getActiveNotifications`/移除事件）、权限观感（无障碍更敏感）、占用（常驻更重）上全面劣于 NLS，且**同样受 HyperOS 强杀与 Android 15+ OTP 遮蔽影响**——换它并不能解决 NLS 的任何短板。仅在真机实测发现 NLS 某场景不可用时才启用。

## 2. 权限清单（最小化）

| 权限 | 类型 | 用途 | 是否必需 |
|---|---|---|---|
| 通知使用权（Notification Listener） | 特殊访问权，设置页授予 | 读取通知 | 必需 |
| `RECEIVE_BOOT_COMPLETED` | normal，安装即授予，无感知 | 开机后调用 `requestRebind()` 兜底 | 必需（兜底） |

**明确不申请**：存储权限（数据存内部私有目录；导出用 SAF `ACTION_CREATE_DOCUMENT`，无需权限）、`POST_NOTIFICATIONS`（不发通知）、前台服务权限（默认不用前台服务）、电池优化白名单权限（`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 会被应用市场审查，改为引导用户手动到设置里操作，仅在国产 ROM 上建议）。

## 3. 架构

```
┌─────────────────────────────────────────────┐
│  NotifyListenerService (系统绑定, 常驻)      │
│   onNotificationPosted → 过滤 → 去重 → 入库  │
│   onListenerDisconnected → requestRebind()  │
├─────────────────────────────────────────────┤
│  存储层: SQLite (Room, WAL 模式)             │
│   批量写入 + 定期清理(保留天数/条数上限)       │
├─────────────────────────────────────────────┤
│  UI 层: 单 Activity + RecyclerView (View 体系)│
│   日志列表 / 按应用筛选 / 搜索 / 导出 / 设置   │
├─────────────────────────────────────────────┤
│  BootReceiver: requestRebind() 兜底          │
└─────────────────────────────────────────────┘
```

单进程、无后台轮询、无网络、无第三方 SDK。

### 3.1 采集层：NotifyListenerService

`onNotificationPosted(sbn)` 中提取：

- `packageName`、发布时间 `postTime`
- `Notification.extras` 中的 `EXTRA_TITLE / EXTRA_TEXT / EXTRA_SUB_TEXT / EXTRA_BIG_TEXT`（即"通知预览"内容）
- `channelId`、`key`、是否常驻（ongoing）、是否分组摘要（group summary）

**过滤规则**（减少噪音和写入量，用户可在设置中调整）：

1. 跳过 `FLAG_GROUP_SUMMARY`（分组摘要无实际内容，内容在子通知里）；
2. 默认跳过 `FLAG_ONGOING_EVENT`（音乐播放、下载进度这类常驻通知会高频刷新，是资源占用的最大来源；可按包名白名单放行）；
3. 跳过自身包名；
4. **去重**：同一 `key` 且 `(title + text)` 哈希相同的更新不重复入库（音乐/进度类通知每秒刷新同一条，去重后写入量下降 90%+）。去重缓存用内存 LRU（约最近 200 条 key→hash），进程重启丢失也无妨。

`onListenerDisconnected()` 中调用 `requestRebind()`（API 24+）主动请求重绑。

### 3.2 存储层

**SQLite + Room**，理由：需要按应用/时间/关键词查询，文件日志做不到；Room 编译期生成代码，运行时开销可忽略，避免手写 SQL 样板。

表结构 `notification_log`：

```
id (PK autoincrement)
post_time      INTEGER   -- 索引
package_name   TEXT      -- 索引
app_label      TEXT      -- 冗余存应用名，避免读取时反查 PackageManager
title          TEXT
text           TEXT
big_text       TEXT      -- 仅在与 text 不同时存
channel_id     TEXT
dedup_hash     INTEGER
```

降占用措施：

- **WAL 模式**：写入不阻塞读取，减少 fsync；
- **批量写**：通知先进内存队列，由单线程 executor 攒批（500ms 或 20 条触发）一次事务写入——高频通知场景下 I/O 次数大幅下降；服务 `onDestroy` 时同步刷盘；
- **清理策略（已定：永久保留，不自动清理）**：默认不主动删除，完整留存。为控制体积改用**去重 + 不存大图 + 纯文本**三招，把单条成本压到最低；设置页提供"按时间/条数手动清理"与"一键清空"，数据量大时给出占用提示。留一个可选开关，用户想开自动清理时再启用双阈值逻辑；
- 只建 `post_time`、`package_name` 两个索引；搜索用 `LIKE`（无 FTS 表，省一倍存储）。数据量特别大后可选引入 FTS。

估算：单条日志约 200–500 字节，纯文本每天数千条、常年累积通常在数十~上百 MB 量级；因选择永久保留，UI 需持续展示当前占用并提供手动清理。

### 3.3 UI 层

**View 体系（非 Compose）**：Compose 会增加 2–3 MB APK 和更高的运行时内存，与"尽可能减少占用"冲突。

- 单 Activity + Fragment 不用，直接 Activity 切换视图或两个 Activity（列表 / 设置）；
- 列表：`RecyclerView` + `Paging` 不引入，用 Room 的 `LIMIT/OFFSET` 手写简单分页（滚动到底加载更多）；
- 功能：时间倒序日志流、按应用筛选（应用图标 chip）、关键词搜索、长按删除、导出（SAF 导出 JSON/CSV）、设置（保留天数/条数、过滤规则、常驻通知白名单）；
- 首页顶部显示监听状态；未授权时引导跳转 `ACTION_NOTIFICATION_LISTENER_SETTINGS`。

### 3.4 自启动与保活

分层策略，从零成本到用户可选：

1. **系统级（零成本，主力）**：通知使用权授予后，系统负责开机绑定与被杀重拉。这是 Listener 的固有特性。
2. **兜底重绑**：`onListenerDisconnected` → `requestRebind()`；`BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` 接收器里也调一次 `requestRebind()`（应对个别 ROM 开机不绑定的情况）。
3. **国产 ROM 引导（用户手动，可选）**：MIUI/EMUI/ColorOS 等会无视系统机制强杀。App 内提供「保活设置向导」页：检测厂商，引导用户手动开启"自启动"、关闭本应用电池优化、后台锁定。**只引导，不申请权限**。
4. **断档可感知**：服务 `onListenerConnected/Disconnected` 时间戳落盘，UI 显示"最近监听中断时段"，让用户知道漏了哪段时间，而不是静默丢失。

**不做**：前台服务常驻通知（对 Listener 无必要且增加权限）、双进程守护、无声音乐/1px Activity 等对抗性保活（费电、违反平台规则）。

## 4. 技术选型汇总

| 项 | 选择 | 理由 |
|---|---|---|
| 语言 | Kotlin | 官方推荐，代码量少 |
| minSdk | 29 (Android 10) | 行为一致、API 干净；`requestRebind` 需 API 24，满足 |
| targetSdk / compileSdk | 36 (Android 16) | 匹配目标机型 HyperOS 3；用最新前台服务/后台约束 API |
| UI | View 体系 + Material Components | 比 Compose 省 2–3 MB / 运行内存更低 |
| 存储 | Room + SQLite (WAL) | 查询需求 + 最小样板 |
| 异步 | Kotlin Coroutines（仅 core） | 轻量；不用 RxJava/WorkManager |
| DI/网络/图片库 | 无 | 零依赖原则，应用图标用 PackageManager 直取 |
| 混淆 | R8 full mode + resource shrinking | 目标 APK < 2 MB |

## 5. 风险与对策

| 风险 | 对策 |
|---|---|
| 国产 ROM 强杀后系统重绑失败 | 保活向导 + requestRebind 兜底 + 断档时段可视化 |
| 用户误关通知使用权 | 首页醒目显示状态 + 一键跳转设置 |
| 高频通知（音乐/进度）导致写入风暴 | ongoing 默认过滤 + 去重 + 批量写 |
| 敏感内容（验证码、聊天记录）落盘 | 数据仅存内部私有目录，不联网；设置中提供按应用排除名单与一键清空 |
| Android 15+ 对 Listener 的通知内容裁剪（敏感通知遮蔽） | 系统对未增强确认的 Listener 会遮蔽 OTP 类通知，属平台行为，文档中向用户说明 |

## 6. 实施计划

1. 工程骨架：Gradle 配置、Manifest、Listener 服务空实现，跑通"授权 → 收到回调"；
2. 存储层：Room 实体/DAO、批量写、手动清理逻辑；
3. 采集逻辑：提取、过滤、去重，接入存储；
4. UI：列表 + 筛选 + 搜索 + 状态提示 + 占用显示；
5. 设置与导出、**小米 HyperOS 保活向导（§7）**、断档时段可视化；
6. 在小米 17 Pro Max / HyperOS 3 真机上做保活压测与耗电实测。

---

## 7. 小米 HyperOS 3 / Android 16 专项适配（最高优先级）

HyperOS（原 MIUI）的后台管控远比原生 Android 激进：即便 NLS 是系统绑定的服务，只要**自启动未放行**或**被省电策略限制**，进程被杀后系统也**不会**重新拉起。因此小米适配的核心是"引导用户把该开的开关全部打开"，并用应用侧手段兜底。

### 7.1 HyperOS 的后台杀伤链（要逐一破解）

| 管控点 | 位置 | 不处理的后果 | 破解方式 |
|---|---|---|---|
| **自启动权限** | 安全中心 → 应用管理 → 权限 → 自启动 | 杀后不重拉、开机不启动 | App 内一键深链跳转 + 图文引导 |
| **省电策略** | 应用信息 → 省电策略 → 无限制 | 后台被冻结/限制 | 深链到 `HiddenAppsConfigActivity` |
| **神隐模式 / 智能后台** | HyperOS 电池设置的智能后台管控 | 系统"智能"清理后台 | 引导设为"无限制"，并锁定后台 |
| **后台锁定** | 最近任务卡片下拉锁定 | 一键清理时被杀 | 图文引导手动下拉锁定 |
| **电池优化** | 系统电池优化 | Doze/待机限制 | 直接申请 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（自用侧载可直接弹窗） |

### 7.2 应用侧加固（针对小米默认启用）

因分发为自用侧载，可用比通用形态更强的手段：

1. **可选前台服务（针对小米默认引导开启）**：一条 `IMPORTANCE_MIN` 常驻通知 + 前台服务，显著提升 HyperOS 上的存活率。
   - Android 16 需声明 `foregroundServiceType`，无系统匹配类型 → 用 `specialUse` 并在 manifest 里写 `<property>` 说明用途（自用无审核，合规）。
   - 需 `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`、`POST_NOTIFICATIONS`（运行时申请）。
2. **电池优化白名单**：`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 直接弹窗申请（自用可用，商店版会审查）。
3. **BootReceiver 兜底**：`BOOT_COMPLETED` / `LOCKED_BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` 中调 `requestRebind()`。
4. **AlarmManager 心跳兜底（可选）**：`setExactAndAllowWhileIdle` 低频（如 30 min）唤醒校验 NLS `isConnected`，断开则 `requestRebind()`。仅作兜底，不做高频轮询。
5. **断档可视化**：`onListenerConnected/Disconnected` 落时间戳，UI 显示"最近中断时段"，被小米杀掉也能让用户看见漏抓区间。

### 7.3 保活向导（App 内引导页）

检测到厂商为 Xiaomi（`Build.MANUFACTURER`）时，首页展示"小米保活向导"，逐项检测并一键跳转：

```
自启动权限深链（不同 HyperOS 版本组件名可能变，需 try/catch 兜底到应用详情页）：
  Intent().setComponent(ComponentName(
    "com.miui.securitycenter",
    "com.miui.permcenter.autostart.AutoStartManagementActivity"))

省电策略（无限制）深链：
  Intent("miui.intent.action.HIDDEN_APPS_CONFIG_ACTIVITY")
    .setClassName("com.miui.powerkeeper",
      "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
    .putExtra("package_name", packageName)
    .putExtra("package_label", appLabel)

兜底（任一深链失败）：跳应用详情页
  Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
    Uri.fromParts("package", packageName, null))
```

> 组件名随 HyperOS 版本可能变动，**必须 try/catch 逐级降级**（自启动页 → 安全中心首页 → 应用详情页），绝不因深链失效而崩溃。

### 7.4 Android 16 平台约束

- **敏感通知遮蔽**：Android 15+ 起，普通 NLS 收到的 OTP/验证码类通知内容会被系统遮蔽（需系统级 `RECEIVE_SENSITIVE_NOTIFICATIONS`，第三方拿不到）。这是平台行为，**验证码正文可能抓不到**，UI/文档需向用户说明。
- **前台服务类型强制**：targetSdk 34+ 起前台服务必须声明类型，Android 16 沿用，用 `specialUse`。
- **`POST_NOTIFICATIONS` 运行时权限**：仅在启用前台服务时才申请。
- **后台启动限制收紧**：Android 15/16 进一步限制后台启动 Activity/FGS，本 App 不做后台拉起 UI，规避。

---

## 8. 完整技术栈

### 8.1 语言 / 构建工具链

| 项 | 选型 | 版本（建议） |
|---|---|---|
| 语言 | Kotlin | 2.1.x |
| JDK | OpenJDK（编译） | 17 |
| 构建系统 | Gradle（Kotlin DSL, `.kts`） | 8.11+ |
| Android Gradle Plugin | AGP | 8.9+（compileSdk 36 要求） |
| compileSdk / targetSdk | Android 16 | API 36 |
| minSdk | Android 10 | API 29 |
| 代码压缩 / 混淆 | R8 full mode + resource shrinking | 随 AGP |
| 依赖管理 | Gradle Version Catalog（`libs.versions.toml`） | — |

### 8.2 运行时依赖（AndroidX，零第三方 SDK）

| 库 | 用途 | 版本（建议） |
|---|---|---|
| `androidx.core:core-ktx` | KTX 扩展 | 1.15.x |
| `androidx.appcompat:appcompat` | AppCompatActivity | 1.7.x |
| `com.google.android.material:material` | Material 组件（列表/Chip/对话框） | 1.12.x |
| `androidx.recyclerview:recyclerview` | 日志列表 | 1.3.x |
| `androidx.constraintlayout:constraintlayout` | 布局 | 2.2.x |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 生命周期协程作用域 | 2.8.x |
| `androidx.room:room-runtime` + `room-ktx` | SQLite ORM（WAL、协程 Flow） | 2.7.x |
| `androidx.room:room-compiler`（ksp） | Room 注解处理 | 2.7.x |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 异步（仅 core，无 Rx） | 1.9.x |
| KSP（`com.google.devtools.ksp`） | Room 代码生成（替代 kapt，更快） | 匹配 Kotlin 2.1 |

**明确不引入**：Jetpack Compose、Paging、WorkManager、Hilt/Dagger、Retrofit/OkHttp、Glide/Coil、RxJava。理由：零网络、零图片库（图标 PackageManager 直取）、分页手写 LIMIT/OFFSET、调度挂在写入路径/AlarmManager 兜底——全部服务于"最小体积 + 最小占用"。

### 8.3 核心 Android API

| 能力 | API |
|---|---|
| 通知捕获 | `NotificationListenerService`、`StatusBarNotification`、`Notification.extras` |
| 主动重绑 | `NotificationListenerService.requestRebind(ComponentName)` |
| 授权入口 | `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` |
| 开机兜底 | `BOOT_COMPLETED` / `LOCKED_BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` 广播 |
| 可选保活 | 前台服务（`foregroundServiceType=specialUse`）+ `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |
| 心跳兜底 | `AlarmManager.setExactAndAllowWhileIdle`（低频、可选） |
| 应用信息 | `PackageManager`（应用名 / 图标，运行时直取） |
| 导出 | Storage Access Framework：`ACTION_CREATE_DOCUMENT`（无需存储权限） |
| 小米深链 | `ComponentName` 直跳安全中心 / 省电中心，逐级 try/catch 降级 |

### 8.4 工程结构（单模块 `app`，单进程）

```
app/
├── service/   NotifyListenerService、BootReceiver、(可选)KeepAliveService
├── data/      Entity / Dao / Database(Room,WAL) / LogWriter(批量落库)
├── collect/   Extractor(extras 抽取) / Filter(去重·忽略规则)
├── ui/        LogListActivity / SettingsActivity / Adapter / 保活向导
├── keepalive/ XiaomiGuide(深链+降级) / BatteryOpt / RebindHelper
└── util/      哈希、时间、PackageManager 缓存
```

测试基线机型：**小米 17 Pro Max / HyperOS 3 / Android 16**（内核 6.12 GKI）。