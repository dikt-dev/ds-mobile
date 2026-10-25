# 语音延迟排查记录

> 日期：2026-10-25  
> 现象：相比旧 QQ/Termux 语音，App 里 AI 语音消息从点击到出声明显更慢。

## 1. 实测数据

### 手机直连 Azure

```text
TtsManager: azure 440064B, network 20580ms, total 20586ms
```

### Worker 代理 + MP3

```text
Worker /api/app/tts 第 1 次：3009ms / 17568B
Worker /api/app/tts 第 2 次：1384ms / 17568B
```

App 内预取：

```text
TtsManager: tts 69552B, network 822ms
TtsManager: tts 440064B, network 6376ms（长文本、冷请求）
```

## 2. 根因

1. **手机直连 `eastus.tts.speech.microsoft.com` 的链路太慢**  
   同一段文本直接请求 20.6s，而 Worker 边缘节点请求 Azure 只要 1~3s。
2. **原来用 24kHz PCM WAV**  
   一段长回复 440KB~1.2MB，跨境下载进一步放大延迟。
3. **按点击才合成，且没有缓存**  
   用户点一次等一次；重复点同一句还会重新请求。
4. **没有预取**  
   旧 QQ/NapCat 是在发送前就把语音文件生成好，延迟被“生成阶段”吸收；App 是收到文字后才开始。

## 3. 已修复

- 新增 Worker `/api/app/tts` 代理，设备 Token 鉴权，由 Cloudflare 边缘请求 Azure；
- App 优先走 Worker，失败再回退手机直连 Azure；
- 输出改为 `audio-24khz-48kbitrate-mono-mp3`，体积约为 WAV 的 1/6；
- 语音条出现时后台 `prefetch`，用户点击时优先命中缓存；
- 按文本/音色/语速/音高/风格做 SHA-256 缓存，最多保留 30 条；
- 缓存播放后不再删除文件，可重复播放。

## 4. 仍存在的延迟

- 第一次遇到一条长语音，冷启动仍可能要几秒；
- 语音条目前没有“生成中”状态，点击如果未命中缓存会短暂无反馈；
- 系统 ASR（语音输入）走小米语音服务，和这里的 TTS 延迟不是同一条链路。

## 5. 结论

高延迟的主因不是 TTS 合成本身，而是 **手机直连 Azure 的跨境网络 + 大 WAV + 点击才生成**。  
改走 Worker 代理、MP3、预取和缓存后，短语音已从 20s 级降到 1s 内，长语音冷请求约 6s。
