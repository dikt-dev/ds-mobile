# DS-Mobile App API 契约（阶段 0 冻结稿）

> 后端：现有 `ds-memory` Worker（`https://fae.us.ci`）  
> App 渠道：`channel = "mobile"`  
> 认证：设备 Token（不把 Worker API Key 打进 APK）  
> 版本：v0.1（阶段 0 契约，阶段 1 实现）

---

## 1. 认证

### 1.1 配对

`POST /api/app/pair`

请求：

```json
{
  "pair_code": "一次性配对码",
  "device_name": "Redmi K60",
  "device_id": "android-id-or-random-uuid"
}
```

响应：

```json
{
  "ok": true,
  "token": "device-token",
  "expires_at": "2027-10-25T00:00:00+08:00"
}
```

- `pair_code` 由 Worker 侧生成，一次性、5 分钟有效。
- 当前 Worker 未配置 `APP_PAIR_CODE` 时，临时复用 `ASTROBOT_API_KEY` 作为配对码；配置后只认 `APP_PAIR_CODE`。
- Token 存 Android Keystore/EncryptedSharedPreferences（阶段 1 暂用 SharedPreferences，阶段 2 改加密存储）。
- 后续所有 App 请求带 `Authorization: Bearer <device-token>`。

### 1.2 错误格式

```json
{
  "error": {
    "code": "invalid_token",
    "message": "设备未授权"
  }
}
```

| HTTP | code | 含义 |
|---|---|---|
| 401 | `invalid_token` | Token 无效/过期 |
| 403 | `device_disabled` | 设备被禁用 |
| 429 | `rate_limited` | 请求过频 |
| 413 | `payload_too_large` | 文件/消息过大 |
| 500 | `internal_error` | 服务端错误 |

---

## 2. 消息事件

`GET /api/app/events?after_id=<int>&limit=<1..100>&latest=<0|1>`

- `latest=1` 返回最近 N 条（首次进入聊天室用），忽略 `after_id`。
- 否则返回 `id > after_id` 的增量。

响应：

```json
{
  "messages": [
    {
      "id": 1001,
      "role": "user",
      "content": "今天好累",
      "channel": "web",
      "created_at": "2026-10-25T13:00:00+08:00",
      "turn_id": 501,
      "proactive_kind": null
    }
  ],
  "latest_id": 1001,
  "has_more": false
}
```

规则：

- 按 `id` 升序；客户端按 `id` 去重。
- `channel` 返回所有渠道（web/mobile/astrobot），App 显示全部历史。
- 不返回已删除/隐藏消息。
- 客户端每 3~5 秒轮询；失败指数退避。
- 首次启动用 `latest_id` 做基线，避免重放历史。

---

## 3. 聊天

复用 `POST /v1/chat/completions`（OpenAI 兼容），增加头：

```text
Authorization: Bearer <device-token>
X-Client-Channel: mobile
X-Client-Voice: 1
```

请求：

```json
{
  "messages": [
    {"role": "user", "content": "今天好累"}
  ],
  "stream": true,
  "client_message_id": "uuid-v4"
}
```

- `client_message_id` 用于服务端幂等；同一 id 重复提交不重复落库。
- `stream=true` 返回 SSE；App 逐段渲染。
- 语音先本地/云端 ASR 成文字，再按文本发送。
- 图片/视频：先 R2 预签名上传，再在消息中带 `media` 元数据。

响应头（阶段 1 增加）：

```text
X-User-Message-Id: 1001
X-Assistant-Message-Id: 1002
```

如果暂时没有该头，App 通过 `/api/app/events` 的 `latest_id` 回填服务端 id。

---

## 4. 媒体上传

`POST /api/app/upload-url`

请求：

```json
{
  "kind": "image|video|voice|gif",
  "content_type": "image/jpeg",
  "size": 123456
}
```

响应：

```json
{
  "upload_url": "https://...r2...",
  "object_key": "app/2026-10-25/uuid.jpg",
  "expires_at": "2026-10-25T13:10:00+08:00",
  "max_size": 104857600
}
```

- App 直接 `PUT` 到 `upload_url`，不经过 Worker 中转。
- 上传成功后，聊天消息带：
  ```json
  "media": [{"kind":"image","key":"app/...","width":1080,"height":1440}]
  ```
- 视频上限：1 分钟 / 100MB / 720p。
- 文档解析结果只发文本，不上传原文件。

---

## 5. 兼容与安全

- 不在 APK 内硬编码 `ASTROBOT_API_KEY`；
- Token 可撤销，设备丢失时在 Worker 侧禁用；
- 调试服务与聊天 Token 分离；
- 所有请求 HTTPS；
- 日志不打印 Token、Azure Key、消息正文（可打印 id/长度）。
