# DS-Mobile

红米 K60（Android 15）上的私有聊天客户端，直接对接 `ds-memory` Worker，替代 QQ + Termux/NapCat 手机端。

## 文档

- [项目报告](docs/PROJECT_REPORT.md)
- [API 契约](docs/API_CONTRACT.md)
- [阶段 0 验收](docs/PHASE0_ACCEPTANCE.md)

## 环境

- JDK 17：`D:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot`
- Android SDK：`D:\Android\Sdk`（Platform 35 / Build-Tools 35 / Platform-Tools 37）
- Gradle 8.9（本机在 `D:\Android\gradle-dist\gradle-8.9`）
- 设备：Redmi K60（`23013RK75C`），USB 调试 + USB 安装已开

## 构建

```powershell
$env:JAVA_HOME='D:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
$env:ANDROID_HOME='D:\Android\Sdk'
$env:ANDROID_SDK_ROOT='D:\Android\Sdk'
$env:GRADLE_USER_HOME='D:\Android\gradle'

& 'D:\Android\gradle-dist\gradle-8.9\bin\gradle.bat' :app:assembleDebug
```

## 安装

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.dsmobile.app/.ChatActivity
```

## 当前进度

### 阶段 0

- 前台服务：每 3 秒检查 `https://fae.us.ci/api/health`，划掉后台仍运行；
- 重启自启：HyperOS 自启动/无限制省电后，重启自动恢复；
- SSH/SFTP 调试：8022，阶段 0 为临时随机密码，正式版改公钥；
- PDF/ZIP 解析 spike：50 页 PDF + 100 条目 ZIP；
- API 契约已冻结。

### 阶段 1（MVP）

- 默认启动页改为聊天；
- 配对码换设备 Token（当前配对码临时用 `ASTROBOT_API_KEY`）；
- 文字聊天、Room 本地库、5 秒增量轮询；
- SSE 流式回复；
- `client_message_id` 幂等，超时重试不会重复落库；
- 图片消息：相册选图、本地压缩、发送、R2 回放；
- 文件解析：PDF/TXT 端到端通过；DOCX/XLSX/PPTX/ZIP/7z 解析器自测通过；文件消息使用独立文件卡片显示；
- 分享链接：B站卡片 + 摘要/总结/字幕，普通网页/抖音卡片 + 摘要；
- 视频/GIF：上传 R2，视频内嵌播放，GIF 动图播放；
- 历史显示 Web/QQ/App 全部渠道；
- 后台助手消息通知（`DS-Mobile 消息` 渠道）：悬浮横幅、锁屏公开、桌面角标、声音和震动；打开 App 清除角标；
- 主动消息：前台服务每 30 秒消费 `/api/proactive/pending`，先本地通知再 ACK；
- `message_id` 去重，events 同步和队列推送不会重复通知；
- 每 5 分钟 App 心跳；划掉最近任务后 `onTaskRemoved` + `START_STICKY` 自动拉起；
- `[face:名称]` 转 emoji（未收录保留 `[名称]`），语音标记继续按气泡展示；
- 语音只念台词：`（）` / `()` 里的动作描述只保留在转文字里，TTS 播放前剥离；
- `inject_history: true`，Worker 补最近 10 轮；
- 重启后自动恢复服务并继续收通知；
- 发送 outbox：失败消息保留在本地，可在气泡上重试；
- Token 用 EncryptedSharedPreferences 保存，旧明文自动迁移；
- 实测发送 `App test reply OK` 并收到 AI 回复。

详见 [阶段 1 验收](docs/PHASE1_ACCEPTANCE.md)。

> 注意：`local.properties`、keystore、日志不要提交。
