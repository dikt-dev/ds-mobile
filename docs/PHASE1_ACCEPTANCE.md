# DS-Mobile 阶段 1 验收记录（MVP）

> 日期：2026-10-25  
> 目标：不经过 QQ，App 内直接与 `ds-memory` Worker 聊天。  
> 状态：**文本聊天 MVP + 后台通知 + Token 加密 + 重启自启 + 发送 outbox/重试已跑通；流式待补**

---

## 1. 已完成

| 项 | 结果 |
|---|---|
| Worker 设备配对 | ✅ `POST /api/app/pair` |
| 设备 Token | ✅ 64 字符，KV 保存 1 年 |
| App 增量消息 | ✅ `GET /api/app/events`，支持 `latest=1` |
| App 聊天 | ✅ `POST /v1/chat/completions`，`X-Client-Channel: mobile` |
| Room 本地库 | ✅ `messages` 表，保留最近 300 条 |
| 配对页 | ✅ 输入 Worker 地址 + 配对码 |
| Token 持久化 | ✅ SharedPreferences（阶段 2 改加密） |
| 聊天页 | ✅ 历史、气泡、发送中状态、5 秒轮询 |
| 启动页 | ✅ `ChatActivity` 已设为 launcher；调试页收进「调试」按钮 |
| 后台通知 | ✅ App 在后台收到助手消息时弹 `DS-Mobile 消息` 通知 |
| 重启自启 | ✅ HyperOS 白名单配置后，重启自动启动服务并收到通知 |
| 发送 outbox | ✅ 发送失败保留 `pending_messages`，气泡可重试 |
| Token 存储 | ✅ EncryptedSharedPreferences（Keystore 主密钥），旧明文自动迁移 |
| K60 实测 | ✅ 发送 `App test reply OK`，收到 AI 回复；后台通知测试成功 |

截图：

- `screenshot-p1-setup.png`：配对页
- `screenshot-p1-final.png`：默认启动页
- `screenshot-p1-notification.png`：后台消息通知
- `screenshot-p1-secure.png`：加密存储迁移后的聊天页

---

## 2. 实测链路

```text
App 配对 → 设备 Token → ChatActivity
→ POST /v1/chat/completions (mobile)
→ Worker / DeepSeek
→ GET /api/app/events?latest=1
→ Room → Compose 气泡
```

已验证：

- 配对码（当前用 `ASTROBOT_API_KEY`）换 Token；
- App 杀掉重开仍保留 Token；
- 历史能显示 Web/QQ/App 全部渠道；
- App 发出的消息 `channel=mobile`；
- AI 回复能在 App 内显示；
- 5 秒轮询能拉到外部渠道新消息。

---

## 3. 当前限制

1. 发送不是流式；先拿到完整回复再拉事件。
2. 没有本地乐观消息和失败重试队列。
3. 历史里超长 Web 消息会截断显示 30 行。
4. 历史里超长 Web 消息会截断显示 30 行。
5. Token 迁移后旧 `ds_mobile_settings.xml` 只剩空壳，不影响使用。

---

## 4. 下一轮任务

- P1-07 通知渠道：App 在后台收到新消息时弹通知；
- P1-08 本地 outbox + 失败重试；
- 把 ChatActivity 设为启动页，调试页收进设置；
- Token 改加密存储；
- SSE 流式回复（可选，阶段 1 后）。
