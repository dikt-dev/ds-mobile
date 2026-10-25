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
| 朗读按钮 | ✅ 未标记语音的 AI 气泡显示「朗读」 |
| AI 语音消息 | ✅ `[语音]` 标记 → 语音条 + 文字转写 |
| 音色迁移 | ✅ Xiaoyi / +5% / +3Hz / style 1.2 / 情绪映射 |

截图：

- `screenshot-p4-voice.png`：聊天页「图片/文件/语音」入口
- `screenshot-p4-tts.png`：AI 气泡「朗读」与 TTS 设置后的界面
- `screenshot-p4-voicebar.png`：AI 语音消息条（文字 + 播放）
- `screenshot-p4-voice-settings.png`：音色/语速/音高/Style Degree 设置页

---

## 2. 音色迁移

从旧 `ds-qq-client/voice.toml` 移植：

```text
voice = zh-CN-XiaoyiNeural
rate  = +5%
pitch = +3Hz
styledegree = 1.2
```

情绪 style 映射（来自 `tts_router.py`）：

```text
傲娇/毒舌/嘴硬/别扭/嫌弃/不屑/冷哼 → disgruntled
炸毛/生气/凶/恼火               → angry
心软/撒娇/温柔/喜欢             → affectionate
委屈/难过/伤心/哭               → sad
得意/开心/高兴/兴奋/活泼/笑     → cheerful
```

设置页现在可改：

```text
Azure Speech Key
Region
Voice
Rate
Pitch
Style Degree
```

---

## 3. AI 语音消息

链路：

```text
App 请求带 X-Client-Voice: 1
→ Worker 给 mobile 渠道注入 [语音] 指令
→ 模型返回 [语音]...
→ App 识别标记，文本条 + 「▶ 语音」条
→ 点击语音条用 Azure TTS 播放
```

实测回复：

```text
[语音]「晚安啦，大笨蛋……哼，谁特意跟你道晚安...」
```

App 显示：

```text
文字：晚安啦，大笨蛋……
语音条：▶ 语音 点击播放
```

TTS 日志：

```text
TtsManager: azure bytes=553124
cache/tts-1792910894321.wav  553124 bytes
```

## 4. Azure 录音转文字

- 「语音」按钮：配置 Azure Speech Key 后第一次点开始 16kHz 单声道 WAV 录音，按钮变「停止」，再点结束；
- 录音结束后直接调用 Azure STT，识别文字自动作为消息发送；
- 未配置 Key / 录音启动失败时回退系统语音识别；
- 录音中显示「录音中…」，转写中显示「转写中…」。

### 验收证据

K60 上用内置 16kHz 中文 WAV 调 Azure STT，返回：

```text
STT: 你好，我是大肥鱼这是语音识别测试。
```

入口：调试页 →「测试 Azure STT（内置语音）」。

## 5. 当前限制

1. 还没有用手机麦克风真实说话完成一次录音转写（录音启停已实测，STT 用内置 WAV 验证）。
2. Azure STT 需要联网；系统语音服务在离线/无服务时不可用。
3. 没有本地 ASR。
4. 没有语音消息 outbox/重试。

---

## 5. 下一步

- 用真实语音验证识别结果 → 自动发送；
- 本地 Whisper/Vosk 作为离线备用；
- 语音消息 outbox/重试；
- 可选：Azure STT 直连替代系统识别。
