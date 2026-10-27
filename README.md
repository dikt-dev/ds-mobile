# DS-Mobile

红米 K60（Android 15）上的私有聊天客户端，直接对接 `ds-memory` Worker，**已取代 QQ + Termux/NapCat，是唯一的移动入口**。

> 2026-10-25 起 QQ/Termux 已从手机卸载，DS-Mobile 保持运行（见 [docs/RETIREMENT.md](docs/RETIREMENT.md)）。

## 文档

- [项目报告](docs/PROJECT_REPORT.md)（评审稿 v1.0，含阶段规划与验收标准）
- [API 契约](docs/API_CONTRACT.md)（阶段 0 冻结稿）
- [交付包说明](docs/DELIVERY.md)（安装包、SHA-256、已具备能力与已知缺口）
- 阶段验收：`docs/PHASE0_ACCEPTANCE.md` ~ `docs/PHASE6_DEBUG_SERVICE.md`

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
adb install -r dist\DS-Mobile-0.1.0.apk
adb shell am start -n com.dsmobile.app/.ChatActivity
```

## 当前进度（全部阶段已完成）

- **阶段 0**：前台服务（每 3 秒检查 `/api/health`，划掉后台仍运行）、HyperOS 自启动、SSH/SFTP 调试（8022）、PDF/ZIP spike、API 契约冻结；
- **阶段 1（MVP）**：配对码换设备 Token（加密存储，旧明文自动迁移）、文字聊天、Room 本地库、5 秒增量轮询、SSE 流式、`client_message_id` 幂等、历史显示全部渠道、后台通知、主动消息队列消费、心跳保活、发送 outbox 重试；
- **阶段 2**：划掉后台前台服务存活抽测通过（8 小时稳定性按用户要求暂缓）；
- **阶段 3**：图片（Photo Picker → 本地压缩 1600/JPEG 82% → 发送 → R2 回放）、视频/GIF 上传与内嵌播放；
- **阶段 4**：语音按钮（Azure STT / 系统 ASR）、AI `[语音]` 消息条、Azure TTS（走 Worker `/api/app/tts` 代理）+ 本地 TTS 兜底、音色从旧 voice.toml 迁移（Xiaoyi +5% +3Hz style 1.2）；
- **阶段 5**：文件解析（PDF/TXT 端到端；DOCX/XLSX/PPTX/ZIP/7z/RAR4 自测通过；扫描 PDF ML Kit OCR；RAR5 暂不支持）；
- **阶段 6**：SSH/SFTP 调试（8022，RSA 公钥或随机密码，30 分钟自动关闭，根目录隔离在 App filesDir）。

## 已退役

QQ / Termux / NapCat 手机端链路已于 2026-10-25 卸载，见 [docs/RETIREMENT.md](docs/RETIREMENT.md)。旧的 `ds-qq-client`（Python + NapCat）已从工作区删除。

> 注意：`local.properties`、keystore、日志不要提交。
