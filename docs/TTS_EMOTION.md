# Azure TTS 情绪表达评估

> 日期：2026-10-26  
> 结论：**项目可以走 SSML 情绪路线，而且 Worker 和手机端已经具备基础管线；本轮修复了三个会让情绪“看起来有代码、实际不生效”的问题。**

## 已具备

- Worker `/api/app/tts` 已构造：

```xml
<speak ... xmlns:mstts="https://www.w3.org/2001/mstts">
  <voice name="zh-CN-XiaoyiNeural">
    <mstts:express-as style="cheerful" styledegree="1.2">
      <prosody rate="+5%" pitch="+3Hz">文本</prosody>
    </mstts:express-as>
  </voice>
</speak>
```

- 手机端 `TtsManager` 也会在 Worker 失败时直连 Azure，同样带 `express-as`；
- 设置页已有 Voice / Rate / Pitch / Style Degree；
- TTS 缓存、预合成、语音条播放都已接入。

## 实测

Worker TTS 对以下组合均返回 200 / MP3：

```text
zh-CN-XiaoyiNeural   cheerful gentle sad affectionate disgruntled angry
zh-CN-XiaoxiaoNeural cheerful gentle sad affectionate disgruntled angry
```

说明链路和 SSML 格式可用；具体风格听感仍建议用手机实际试听。

## 本轮修复

1. **中文关键词丢失**
   - 原 `styleFor()` 里的中文因历史编码问题变成了替换字符，实际几乎永远不会命中；
   - 已恢复真实中文关键词，并扩展 `gentle / excited / empathetic / whispering`。

2. **缓存键没带 style**
   - 同一句文本换情绪时会命中旧音频；
   - 已把 `styleFor(text)` 加入缓存 key。

3. **风格不被支持时会整段失败**
   - 现在带 style 合成失败会自动重试无 style 版本，再失败才回退本地 TTS。

## 当前情绪映射

| 情绪 | 关键词示例 |
|---|---|
| whispering | 耳语、小声、悄悄 |
| disgruntled | 傲娇、毒舌、嘴硬、嫌弃 |
| angry | 炸毛、生气、愤怒 |
| sad | 委屈、难过、伤心、想哭 |
| gentle | 别担心、慢慢来、安慰、抱抱、温柔 |
| excited | 太棒、好耶、惊喜、激动 |
| empathetic | 理解、共情、我懂、辛苦 |
| cheerful | 得意、开心、高兴、活泼、笑 |

顺序从上到下，先匹配强情绪，避免“别担心”被 sad 抢走。

## 建议

- 想风格更丰富：设置 Voice 改为 `zh-CN-XiaoxiaoNeural`；
- 想保持“大肥鱼”声线：继续用 `zh-CN-XiaoyiNeural`，常用风格已验证可提交；
- 下一步如果 AI 能稳定返回 `emotion` 字段，可直接把该字段传给 Worker 的 `style`，比关键词更准；
- 风格不是所有语音都完整支持，建议保留当前“无 style 重试”兜底。
