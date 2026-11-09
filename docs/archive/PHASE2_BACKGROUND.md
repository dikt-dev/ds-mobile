# 阶段 2 后台保活抽测

> 日期：2026-10-25  
> 设备：红米 K60 / Android 15

## 操作

1. 启动 `ChatActivity`；
2. 打开最近任务并把 App 划掉；
3. 等待 30 秒检查服务。

## 结果

```text
ServiceRecord com.dsmobile.app/.service.KeepAliveService
isForeground=true
foregroundId=1001
notification: DS-Mobile 正在运行
pid: 28439
```

- 划掉界面后前台服务仍然存活；
- 常驻通知仍在；
- 进程未被系统回收。

## 说明

这是 30 秒抽测，不是 8 小时稳定性测试；按用户要求 8 小时测试仍暂缓。
