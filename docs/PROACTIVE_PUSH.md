# App 主动消息推送

## 结论

App 采用 **Worker 队列 + Android 前台服务轮询 + 本地通知**，不依赖 FCM。

这是私有单机 App 最稳的方案：

- 不需要 Google 服务/公网回调；
- 消息已经落在 D1 的 `messages`，事件轮询负责聊天记录同步；
- 主动队列负责“推送”和送达确认；
- 划掉最近任务后，前台服务用 `START_STICKY` + `onTaskRemoved` 重新拉起。

## 链路

```text
Worker 生成主动消息
  ↓ 先写 messages
  ↓ 再写 proactive_queue(message_id)
App KeepAliveService
  ↓ 每 30s GET /api/proactive/pending
  ↓ 本地通知（App 不在前台时）
  ↓ POST /api/proactive/ack
  ↓ /api/app/events 同步聊天记录
```

## 去重

队列返回 `message_id`（对应 `messages.id`）。App 用 `notifiedMessageIds` 去重：

- 队列先到：先通知，并记录 message_id；events 后到时不再重复通知；
- events 先到：events 通知并记录；队列后到时不再通知，只 ACK。

## 保活

- `android:stopWithTask="false"`
- `START_STICKY`
- `onTaskRemoved()` 再次 `startForegroundService`
- `BOOT_COMPLETED` 自启
- 电池优化白名单（设置页提供入口）
- App 每 5 分钟调用 `/api/app/heartbeat`，Worker 3 小时收不到才告警

真机已验证：划掉最近任务后服务重新创建，`health=OK` 和 `heartbeat ok` 持续；随后插入队列测试消息，App 在后台收到通知并 ACK。

## 降级

- 通知权限被关：仍 ACK，聊天记录通过 events 同步，不重复推送；
- 队列 ACK 失败：租约到期后会重试；
- 服务被系统彻底冻结：Worker 的通道心跳告警会提示重新打开 App。
