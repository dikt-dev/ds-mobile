# DS-Mobile 阶段 1 验收记录（MVP）

> 日期：2026-10-25  
> 目标：不经过 QQ，App 内直接与 `ds-memory` Worker 聊天。  
> 状态：**文本聊天 MVP 已跑通；通知/后台/加密存储待补**

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
| K60 实测 | ✅ 发送 `App test reply OK`，收到 AI 回复 |

截图：

- `screenshot-p1-setup.png`：配对页
- `screenshot-p1-chat-latest2.png`：聊天页（含 Web/App 消息）
- `screenshot-p1-chat4.png`：App 发送后收到回复
- `screenshot-p1-final.png`：默认启动页

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

1. Token 还是 SharedPreferences 明文，阶段 2 改 Keystore/EncryptedSharedPreferences。
2. 发送不是流式；先拿到完整回复再拉事件。
3. 没有本地乐观消息和失败重试队列。
4. 没有新消息通知；前台服务目前只做健康检查。
5. 历史里超长 Web 消息会截断显示 30 行。
6. 重启自启仍依赖 HyperOS 自启动白名单。

---

## 4. 下一轮任务

- P1-07 通知渠道：App 在后台收到新消息时弹通知；
- P1-08 本地 outbox + 失败重试；
- 把 ChatActivity 设为启动页，调试页收进设置；
- Token 改加密存储；
- SSE 流式回复（可选，阶段 1 后）。
