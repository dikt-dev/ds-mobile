# DS-Mobile 阶段 4 验收记录（语音输入，进行中）

> 日期：2026-10-25  
> 状态：**系统语音识别入口已接通；TTS/本地 ASR 待做**

---

## 1. 已实现

| 项 | 结果 |
|---|---|
| 麦克风权限 | ✅ `RECORD_AUDIO` 运行时申请 |
| 语音入口 | ✅ 聊天输入栏「语音」按钮 |
| 系统 ASR | ✅ 通过 `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` 调起 |
| 语言 | `zh-CN` |
| 识别结果 | 取 `EXTRA_RESULTS` 第一条，按普通文本发送 |
| 包可见性 | ✅ 增加 `<queries>`，K60 能调起小米语音识别 Activity |
| K60 实测 | ✅ 已调起 `com.xiaomi.mibrain.speech/.asr.AsrActivity` |

截图：

- `screenshot-p4-voice.png`：聊天页「图片/文件/语音」入口

---

## 2. 当前限制

1. 真机还没有用真实说话完成一次 ASR 结果回传（测试时没有对手机说话）。
2. 使用的是系统语音服务，离线/无服务时不可用。
3. 没有录音文件、没有本地 ASR、没有 TTS。
4. 没有 Azure STT/TTS key 配置页。

---

## 3. 下一步

- 用真实语音验证识别结果 → 自动发送；
- App 设置页配置 Azure STT/TTS key/region；
- Azure TTS 播放 + 本地 TTS 兜底；
- 本地 Whisper/Vosk 作为离线备用；
- 语音消息 outbox/重试。
