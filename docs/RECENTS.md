# 从系统最近任务中隐藏 DS-Mobile

> 日期：2026-10-27

## 实现

`ChatActivity`（启动器 Activity）增加：

```xml
android:excludeFromRecents="true"
```

作用于整个任务栈，因此 DS-Mobile 不出现在系统多任务卡片里。

## 代价

- 用户不能从多任务界面快速切回 DS-Mobile；
- 需要从桌面图标或常驻通知重新打开；
- 前台服务和消息接收不受影响；
- 如果系统最近任务里还残留旧卡片，需要先清掉旧任务/重启手机。

## K60 验证状态

代码和 Release APK 已构建；构建时 K60 暂时从 ADB 断开，尚未做“打开最近任务看不到卡片”的真机截图验收。

## 替代方案

如果只是想隐藏最近任务里的**截图内容**、仍保留卡片快速切换：

- 给 `ChatActivity` 加 `WindowManager.LayoutParams.FLAG_SECURE`；
- 系统会把卡片预览变黑/模糊，但卡片仍然存在。

两者不要同时使用，除非明确要“卡片完全消失 + 预览也不可截”。
