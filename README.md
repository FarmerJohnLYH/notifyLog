# NotifyLog

将设备上所有其他 App 的通知预览持久化保存为本地日志。**纯离线、最小权限、事件驱动、极低占用。**

完整技术方案见 [TECH_DESIGN.md](TECH_DESIGN.md)。本 README 说明如何构建、安装、以及在小米 HyperOS 上完成保活设置。

---

## 更新记录

### v0.1.3（2026-08-05）

- 新增：导出支持任意时间范围（全部时间 / 日期范围选择，按本地自然日）。
- 变更：CSV 导出改为 Excel 友好的三列表格（应用名｜通知时间｜通知内容）；应用名统一用显示名（缺失则回退包名），时间统一 `yyyy-MM-dd HH:mm:ss`，内容合并为单行单元格，便于 Excel 筛选。完整字段导出请用 JSON。
- 修复：导出格式对话框与"已排除的应用"对话框因 `setMessage` 与列表互斥而不显示任何选项（自首版即存在，adb 实测发现）。

### v0.1.2（2026-08-05）

- 修复（重做）：v0.1.1 的 `fitsSystemWindows` 在 HyperOS 真机上不生效，顶栏仍与状态栏重叠；改为显式 `WindowInsets` 监听为根布局加内边距（对 ROM 差异免疫）。
- 修复：停留在"全部应用"列表顶部时，新通知插入在视口上方而不可见（RecyclerView 锚定行为）；现在刷新后自动回到顶部。
- 新增：保活常驻通知显示"最近 1h 有 n 条通知"，落库时事件驱动刷新（≥30s 间隔），无空闲开销。

### v0.1.1（2026-08-05）

- 修复：页面顶栏与系统状态栏重叠（targetSdk 36 强制 edge-to-edge，为各页面根布局启用 `fitsSystemWindows`）。
- 修复：监听器在页面打开后才完成连接时，顶部"监听未连接"横幅不会自动消失。

### v0.1.0（2026-08-05）

- 首个功能完整版本，见下方功能清单。

---

## 当前进度：功能完整（v0.1.1）

已实现（TECH_DESIGN 全部里程碑）：

- **采集**：`NotificationListenerService` 捕获 → 过滤（自身/排除名单/分组摘要/常驻，常驻可白名单放行）→ MessagingStyle/InboxStyle 正文提取 → LRU 内容去重 → 异步批量落库（Room + WAL）。
- **列表**：时间轴 + 关键词搜索 + 按应用筛选（含计数）+ 长按复制/删除/排除应用 + 手写 LIMIT/OFFSET 分页。
- **设置**：常驻通知开关、排除名单管理、可选自动清理（保留天数/条数上限）、立即清理、一键清空（含 VACUUM）、占用统计。
- **导出**：SAF 导出 JSON/CSV（流式分页写出，尊重当前筛选）。
- **保活**：小米保活向导（自启动/省电策略深链，逐级降级）、电池优化白名单申请、可选前台服务加固（specialUse）、可选 30 分钟心跳重绑、开机/更新 `requestRebind()` 兜底。
- **断档可视化**：监听连接/断开事件落库，设置页可查看漏抓时段。

---

## 构建

**环境要求**：JDK 17、Android SDK Platform 36、Gradle 8.13（wrapper 或独立安装均可）。

```bash
./gradlew assembleRelease   # 或 assembleDebug
```

本 Windows 机器上（工具链已装好）：

```powershell
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot"
$env:GRADLE_USER_HOME="C:\Android\gradle-home"   # 避开 IDE 对默认 ~/.gradle 的锁
C:\Android\gradle-8.13\bin\gradle.bat assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`（release 签名配置读取根目录 `keystore.properties`；无 keystore 时输出未签名包）。

> 版本说明：`compileSdk/targetSdk=36`、`minSdk=29`、Kotlin 2.1.20、AGP 8.11.1、Room 2.7.1。如首次 Sync 时 IDE 提示某组件有更合适的版本，按其建议微调 `gradle/libs.versions.toml` 即可。

---

## 安装与授权

1. 安装 APK。
2. 打开 NotifyLog，点击顶部横幅 →「通知使用权」，为 NotifyLog 打开开关。
3. 返回 App，横幅变为「正在记录」，之后新到达的通知会自动出现在列表。

核心功能只需**通知使用权**这一项特殊访问权，不申请网络、存储等任何运行时权限。

---

## 小米 HyperOS 3 保活设置（重要）

HyperOS 后台管控激进，即使已授权，进程被杀后也可能不自动恢复。请手动完成以下设置（详见 TECH_DESIGN §7）：

1. **自启动**：安全中心 → 应用管理 → NotifyLog → 权限 → 开启「自启动」。
2. **省电策略**：设置 → 应用设置 → NotifyLog → 省电策略 → 选「无限制」。
3. **后台锁定**：最近任务界面，下拉 NotifyLog 卡片并「锁定」，防止一键清理杀掉。
4. （可选）关闭系统对本应用的电池优化。

> 若发现列表出现记录空档，通常就是被系统杀且未重绑——完成上述设置可显著改善。后续里程碑会提供 App 内「保活向导」一键跳转这些设置页。

---

## 平台限制须知

- **验证码/OTP 遮蔽**：Android 15+ 起，系统会对普通通知监听器遮蔽验证码类通知内容，第三方 App 无法获取其正文（平台行为，非本 App 缺陷）。
- 日志明文保存在 App 私有目录（其他 App 无法访问）。MVP 暂未加应用锁/加密，后续里程碑再加。
- 日志默认**永久保留、不自动清理**；后续提供手动清理与占用提示。
