# DS-Mobile 阶段 4 验收记录（语音输入，进行中）

> 日期：2026-10-25  
> 状态：**系统语音识别入口、Azure TTS、设置页已跑通；真实语音识别结果/本地 ASR 待补**

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
| Azure TTS | ✅ 设置页保存 key/region/voice，生成 1.2MB WAV 并播放 |
| 本地 TTS | ✅ 无 key 或 Azure 失败时回退 Android TTS |
| 朗读按钮 | ✅ AI 气泡显示「朗读」 |

截图：

- `screenshot-p4-voice.png`：聊天页「图片/文件/语音」入口
- `screenshot-p4-tts.png`：AI 气泡「朗读」与 TTS 设置后的界面

---

## 2. TTS 实测

```text
TtsManager: azure bytes=1227812
cache/tts-1792910285620.wav  1227812 bytes
```

设置页：

```text
Azure Speech Key（密码框）
Region: eastus
Voice: zh-CN-XiaoyiNeural
```

1. 真机还没有用真实说话完成一次 ASR 结果回传（测试时没有对手机说话）。
2. 使用的是系统语音服务，离线/无服务时不可用。
3. 没有录音文件、没有本地 ASR。
4. 没有语音消息 outbox/重试。

---

## 3. 下一步

- 用真实语音验证识别结果 → 自动发送；
- 本地 Whisper/Vosk 作为离线备用；
- 语音消息 outbox/重试；
- 可选：Azure STT 直连替代系统识别。
