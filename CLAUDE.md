# CLAUDE.md — Oldman Locator

## 项目简介

老人定位监控系统：Android App 定时上报 GPS 到 MQTT，Web 页面实时显示位置轨迹。

- **仓库**：https://github.com/chiman77/oldman-locator
- **Live Demo**：https://chiman77.github.io/oldman-locator/
- **当前版本**：v4.5
- **MQTT Broker**：broker.emqx.io（公共免费）

---

## 技术栈

### Android 端
- 语言：Kotlin
- 定位：`LocationManager.NETWORK_PROVIDER`（v2.0 起弃用 Google Play FusedLocation）
- 通信：MQTT（paho 客户端），clientId 带随机后缀防碰撞
- 保活：前台服务 + AlarmManager 看门狗（v4.4 起替换 WorkManager）
- 上报间隔：静止 5min / 移动 2min 动态切换
- 线程安全：volatile / AtomicInteger / synchronized（v4.5 加固）

### Web 端
- 地图：Leaflet + OpenStreetMap
- 反向地理编码：Nominatim（被墙，遗留问题待替换腾讯地图 API）
- 数据存储：localStorage（历史 30 条）+ sessionStorage（密码持久）
- MQTT over WebSocket：`wss://broker.emqx.io:8084`

---

## 项目结构

```
oldman-locator/
├── android/          # Android App（Kotlin，Gradle KTS）
│   └── app/src/main/java/.../LocationService.kt
├── web/              # Web 页面（纯 HTML/JS，Leaflet）
│   └── index.html
├── index.html        # GitHub Pages 入口（重定向到 web/）
├── README.md         # 使用说明 + APK 下载链接
├── work-log.md       # 详细版本历史 + Bug 修复记录（必读）
└── opencode.json     # OpenCode 编辑器配置（"snapshot": false）
```

---

## 核心设计决策

1. **密码隔离**：MQTT topic 结构 `oldman/pwd/{password}/location`，不同密码互不干扰
2. **轨迹编号点**：同设备历史点用灰色小圆点 + 编号，最新点用设备色
3. **离线检测**：30 分钟未上报自动标记离线，地图标记半透明
4. **断线批量重发**：pendingQueue + SharedPreferences 持久化（v4.3）
5. **小米/国产 ROM 适配**：v4.4 加入自启动引导提示

---

## 遗留问题（待解决）

- [ ] 中文地址解析（Nominatim 被墙 → 需换腾讯地图 API）
- [ ] QQ 浏览器导航栏遮挡问题
- [ ] 夜间关闭浏览器导致 MQTT 消息丢失（无持久化）

---

## 开发注意事项

- Android 端修改后需重新构建 APK，安装到测试机验证
- Web 端修改可直接本地 `python3 -m http.server 8000` 调试（不可用 file:// 协议）
- 每次发版需同步更新 `build.gradle.kts` 的 `versionCode` / `versionName`
- `work-log.md` 是主要的历史决策记录，新功能开发前应先阅读
- APK 构建命令：`cd android && ./gradlew assembleRelease`
