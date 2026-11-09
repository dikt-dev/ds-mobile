# DS-Mobile 阶段 3 验收记录（图片 MVP）

> 日期：2026-10-25  
> 目标：App 内选择图片、本地压缩、发送给 AI，并在聊天页回放。  
> 状态：**图片发送/接收/回放已跑通；视频/GIF/语音待做**

---

## 1. 已完成

| 项 | 结果 |
|---|---|
| 相册选择 | ✅ Android Photo Picker，无需存储权限 |
| 本地压缩 | ✅ 最长边 1600，JPEG 82% |
| 发送 | ✅ data URL 作为 OpenAI image_url 发给 Worker |
| Worker 存储 | ✅ 复用 `saveImageFromSource` 存 R2 `images/` |
| App 回放 | ✅ `GET /api/app/image` 设备 Token 鉴权，下载并显示 |
| 聊天气泡 | ✅ 文本 + 图片同气泡，图片最长 320dp |
| K60 实测 | ✅ 相册选图 → 发送 → AI 看图回复 |

截图：

- `screenshot-p3-image-picked.png`：相册选图发送后的聊天气泡

---

## 2. 实测链路

```text
Photo Picker → MediaUtils.imageToDataUrl (1600/JPEG 82%)
→ POST /v1/chat/completions (content parts: text + image_url)
→ Worker saveImageFromSource → R2 images/...
→ 消息落库 [图片:images/...]
→ App GET /api/app/image?key=... (Bearer)
→ BitmapFactory → Compose Image
```

实测 AI 回复：

```text
（把图片翻来覆去看了两遍）图片收到……哼，本小姐可没在夸你，
这蓝底白字的图做得倒还挺顺眼。
```

---

## 3. 当前限制

1. 图片通过 Worker 中转，没有走 R2 预签名直传；大图会增加 Worker 压力。
2. 图片发送没有 outbox/重试（失败只提示，不保留图片数据）。
3. 没有多选、原图、EXIF 方向修正、HEIC 转换。
4. 视频/GIF/语音尚未做。

---

## 4. 下一步

- 图片 R2 预签名直传（减轻 Worker 压力）；
- 图片 outbox/失败重试；
- 视频/GIF；
- 语音录制/TTS/ASR（阶段 4）。
