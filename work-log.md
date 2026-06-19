# 工作日志

## 版本历史

| 版本 | 核心变化 |
|------|---------|
| v1.0 | 初始版：Android 定位上报 + Web 地图显示 |
| v2.0 | 改用 `LocationManager`（`NETWORK_PROVIDER`），替换 Google Play `FusedLocationProviderClient` |
| v3.0 | 密码隔离：`oldman/pwd/{password}/location` |
| v3.1 | 静止检测 + 心跳上报（maxSkip=10） |
| v3.2 | 轨迹编号点 + 虚线 polyline |
| v3.3 | 侧边栏展开 + localStorage（50条）+ Nominatim 反向地理编码 |
| **v3.4** | 定位超时保底：`requestFreshLocation` 超时后 fallback 到缓存；`isRecent` 2min→5min |
| **v3.5** | 5min 固定上报：移除静止检测，每 5 分钟强制发布一次 |
| **v3.6** | 动态间隔：静止 5min / 移动 2min |
| **v3.7** | 每 3 次强制新定位：`useCacheCount`，缓存 2 次后强制 `requestFreshLocation` |
| **v3.8** | 版本号上报：`build.gradle.kts` `versionName = "3.8"` |
| **v3.9** | 修复 MQTT 重连竞态：`connect()` 防重入锁，`publish()` 放弃手动重连 |
| **v4.0** | MQTT 常在线 4 层保障 |
| **v4.1** | MQTT "无权连接" 修复：clientId 随机后缀 |
| **v4.2** | 电池优化 + WorkManager 看门狗 |
| **v4.3** | 断线批量重发 + SharedPreferences 持久化 |
| **v4.4** | AlarmManager 替换 WorkManager + 小米自启动引导 |
| **v4.5** | 线程安全加固 + 心跳检测 + Web 页面全面修复 |

---

## 2026/6/11

### Bug 修复

| # | 问题 | 原因 | 修复 |
|---|------|------|------|
| 1 | 地图编号点比侧边栏少 1 个 | `loadFromLocal` 循环 `trail.length - 1` 跳过了最后一点 | 改为 `trail.length`，恢复时所有点画编号 |
| 2 | 设备断线后页面收不到消息 | `connect` 回调从输入框读密码（已清空），`pwds=[]` 不订阅 | 改为从 `subscribedPasswords` 变量恢复 |
| 3 | Chrome 下 localStorage 不生效 | `file://` 协议被浏览器安全策略禁止 | 改用 `http://localhost:8000` 访问 |
| 4 | 页面刷新密码消失 | `subscribedPasswords` 是内存变量，刷新重置 | 存入 `sessionStorage`，F5 自动恢复 |
| 5 | 手机上报中断（无 GPS 室内） | `requestFreshLocation` 超时后放弃 | 超时后 fallback 到任意缓存 |

### 功能改进

| # | 改进 | 详情 |
|---|------|------|
| 1 | 历史点 vs 最新点颜色区分 | 编号点灰色 `#999`，最新点/主标记用设备色 |
| 2 | 设备显隐切换 | 侧边栏"隐藏/显示"按钮，`syncVisibility` 统一管理地图层 |
| 3 | 动态上报间隔 | 静止 5min / 移动 2min，`reportInterval` 动态切换 |
| 4 | 每 3 次强制新定位 | `useCacheCount`，缓存 2 次后强制 `requestFreshLocation`（15s） |
| 5 | 主标记缩小 32→20px | 同历史编号点大小，仅颜色区分 |
| 6 | GitHub Pages 部署 | `https://chiman77.github.io/oldman-locator/` |
| 7 | 版本号上报 | 手机发布 JSON 含 `version` 字段 |

---

## 2026/6/12

### 功能改进

| # | 改进 | 详情 |
|---|------|------|
| 1 | 设备离线检测 | 30 分钟未上报标记离线，红色"(离线)" + 最后上线时间，地图标记半透明 |
| 2 | 地图编号点 20→10 | 减少地图密集度；侧边栏历史 50→30 |
| 3 | 隐藏设备同步侧边栏 | 隐藏时卡片消失，底部 `N 个设备已隐藏 [显示全部]` |
| 4 | 删除设备 | 红色"删除"按钮，确认后清除地图层 + localStorage |
| 5 | 按钮分隔线 | 隐藏 \| 删除 中间灰色竖线 |

### 后续改进（同一天）

| # | 改进 | 详情 |
|---|------|------|
| 6 | 记录间虚线分割线 | 侧边栏历史记录每条之间加 `border-bottom: 1px dashed #eee` |
| 7 | 最新记录用设备色标识 | 最后一条记录文字颜色同设备色（与地图最新点一致） |
| 8 | opencode 文件浏览报错屏蔽 | `opencode.json` 加 `"snapshot": false` 禁用快照服务，避免路径解析 bug（`Oldman\Oldman` 重复） |

### Bug 修复

| # | 问题 | 原因 | 修复 |
|---|------|------|------|
| 6 | MQTT 通知循环显示"已在进行连接" | `publish()`、`onConnectFailed` 回调、`isAutomaticReconnect` 三者同时创建新 `MqttClient` 冲突 | `connect()` 加 `isConnecting` 防重入锁；`publish()` 断线时跳过不再手动重连，依赖自动重连 |

---

## 2026/6/19

### v4.5 Android 线程安全加固

| # | 改进 | 详情 |
|---|------|------|
| 1 | LocationService null intent 处理 | START_STICKY 重启时 intent 为 null，从 SharedPreferences 恢复密码 |
| 2 | Android 14 前台服务类型 | `startForeground()` 添加 `FOREGROUND_SERVICE_TYPE_LOCATION` |
| 3 | `isRunning` volatile | 防止多线程可见性问题 |
| 4 | 心跳时间戳 | `service_heartbeat` 每 60s 写入 SharedPreferences |
| 5 | MQTT connect 后台线程 | `Executors.newSingleThreadExecutor()` 避免主线程网络阻塞 → ANR |
| 6 | Handler 回调清理 | `stopService()` cancel 所有 pending callback 防泄漏 |
| 7 | onDestroy 不改 monitoring_active | OS 杀进程不会永久禁用监控 |
| 8 | MqttManager isConnecting volatile | 线程安全防重入 |
| 9 | AtomicInteger clientId | 替换 `System.currentTimeMillis()`，消除碰撞 |
| 10 | pendingQueue synchronized | 线程安全防 ConcurrentModification |

### v4.5 Web 页面修复

| # | 问题 | 原因 | 修复 |
|---|------|------|------|
| 1 | 页面刷新后设备显示离线 | `_synced=false` 但 lastSeen 老，offline check 跳过 | 恢复时检查 lastSeen > 30min 直接标记 offline + `_synced=true` |
| 2 | 轨迹时间顺序乱 | MQTT 乱序到达，push 只加末尾 | 按时间戳二分插入保持时间升序 |
| 3 | 僵尸连接 5 分钟太长 | WebSocket 假死 5min 后才重连 | 缩短为 3 分钟 |
| 4 | 每次消息都 fitBounds | 新消息强制居中，用户无法浏览地图 | 首次加载 fit，用户拖动后不再 fit |
| 5 | 定时器泄漏 | setInterval 无 guard，多次调用累加 | healthCheckTimer 变量 + clearInterval guard |
| 6 | 页面切后台再切回不刷新 | 无 visibilitychange 监听 | 添加 visibilitychange listener |
| 7 | marker 每次 remove+recreate | 浪费性能 + 闪烁 | setLatLng + setPopupContent 原地更新 |
| 8 | lat=0 被过滤 | `!data.lat` 为 true (0 是 falsy) | 改为 `data.lat === undefined \|\| data.lat === null` |
| 9 | _toggleExpand 空设备崩溃 | 无 null check | 添加 `if (!devices[id]) return` |
| 10 | 保存密码不持久 | 只存 sessionStorage，关页面丢失 | 同时存 localStorage |
| 11 | addressTimers 泄漏 | 删除设备不清理定时器 | `_deleteDevice` 中 clearTimeout |
| 12 | connect 后立即 subscribe 竞态 | MQTT 连接回调中同步 subscribe 可能失败 | setTimeout 500ms 延迟 |
| 13 | init 重复执行 | DOMContentLoaded 和 setTimeout 各触发一次 | initialized flag guard |
| 14 | 健康检查退订再订 | 每 30s unsubscribe+subscribe 制造消息丢失窗口 | 移除该循环，仅检查状态 |
| 15 | client.end(false) 无重连 | end() 设 ended=true，reconnect() 无效 | client.end(true) 触发自动重连 |

### v4.5 构建

| # | 变更 | 详情 |
|---|------|------|
| 1 | WorkManager 依赖移除 | v4.4 已移除，v4.5 确认无残留 |
| 2 | versionCode 5, versionName "4.5" | build.gradle.kts 更新 |
| 3 | 构建成功 | 34 tasks, 仅 3 个 deprecation 警告（requestSingleUpdate） |

---

## APK 文件

| 文件名 | 版本 | 说明 |
|--------|------|------|
| `OldmanLocator-v3.7.apk` | 3.7 | 动态间隔 + 强制新定位 + 超时保底 |
| `OldmanLocator-v3.8.apk` | 3.8 | + 版本号上报 |
| `OldmanLocator-v3.9.apk` | 3.9 | + MQTT 重连竞态修复 |
| `OldmanLocator-v4.4.apk` | 4.4 | + AlarmManager 看门狗 + 小米引导 |
| `OldmanLocator-v4.5.apk` | 4.5 | + 线程安全加固 + 心跳检测 + Web 全面修复 |

---

## 遗留问题

- [ ] 中文地址（Nominatim 被墙 → 腾讯地图 API）
- [ ] QQ 浏览器导航栏遮挡问题
- [ ] 夜间关闭浏览器导致 MQTT 消息丢失（无持久化）
