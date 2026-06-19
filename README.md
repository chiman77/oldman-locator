# 老人定位监控系统

## 系统架构

```
Android App (MQTT)  ───→  broker.emqx.io  ───→  PC 浏览器 (WebSocket + Leaflet)
```

- **Android 端**：前台服务，每 10 分钟上报 GPS 定位
- **MQTT Broker**：公共免费 `broker.emqx.io`，无需自建服务器
- **PC 端**：纯静态 HTML 页面，OpenStreetMap 实时显示

## 快速开始

### Android APK 构建

#### 方法一：Android Studio（推荐）
1. 用 Android Studio 打开 `android/` 目录
2. 等待 Gradle 同步完成（自动下载 SDK）
3. 菜单 → Build → Build Bundle(s) / APK(s) → Build APK(s)
4. APK 生成在 `app/build/outputs/apk/debug/`

#### 方法二：命令行构建
```bash
cd android
set ANDROID_HOME=C:\Users\你的用户名\AppData\Local\Android\Sdk
build.bat
```

### 部署到手机
1. 电脑和手机连接同一 WiFi
2. 安装 APK 到老人手机
3. 打开 App → 点击「开始监控」→ 授予定位权限
4. 通知栏出现「老人定位监控」即表示运行正常

### PC 端查看
直接双击打开 `web/index.html` 或用浏览器打开

## 技术细节

| 项目 | 说明 |
|------|------|
| Android 语言 | Kotlin |
| 定位间隔 | 10 分钟 |
| MQTT 主题 | `oldman/{deviceId}/location` |
| 数据格式 | `{"deviceId":"xxx","lat":39.9,"lng":116.4,"time":...,"battery":85}` |
| 地图 | Leaflet + OpenStreetMap |
| MQTT Broker | `broker.emqx.io:1883` (TCP) / `:8084` (WSS) |
| 权限 | 精确定位、后台定位、通知 |
