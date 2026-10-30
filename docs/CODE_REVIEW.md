# App 侧代码审查（A-7）· 2026-10-31

> 对应 `docs/TASKS.md` 的 A-7：「先出审查报告（行数分布、死代码、注释与实现不一致），再拆」。
> 本文只做审查，不改代码。引擎侧的对应报告在 `ds-memory/docs/review-2026-10-31.md`。

## 一、规模

| 文件 | 行数 | 职责 |
|---|---|---|
| `ChatActivity.kt` | 1,804 | 聊天页：状态机 + 发送 + 同步 + 定位 + 相机回调 + 一堆内联 UI |
| `ui/ChatUi.kt` | 1,734 | 视觉层：顶栏 / 气泡 / 输入区 / 配对页 / 语音面板 |
| `net/ApiClient.kt` | 1,118 | HTTP 客户端 + B 站签名 + 预览缓存 |
| `service/KeepAliveService.kt` | 439 | 前台服务：事件轮询、主动消息、通知 |
| `SettingsScreen.kt` | 402 | 设置（全屏 Dialog） |
| `MainActivity.kt` | 382 | 入口 / 引导 |
| `speech/TtsManager.kt` | 333 | Azure TTS 播放 |
| `BiliFrames.kt` | 674 | B 站抽帧 / 宫格 |

工程本身是健康的：单模块、无 DI 框架、Room + Compose，没有过度抽象。问题集中在**三个文件太胖**。

## 二、P0：`ChatActivity.ChatScreen` 是一个 1,400 行的 Composable

`ChatScreen`（`L353` 起）一个函数里同时装着：

- 40+ 个 `remember { mutableStateOf(...) }`（输入、草稿、语音、定位、流式文本、滚动位置…）
- 8 个局部 suspend/普通函数：`syncEvents`、`syncAllHistory`、`reconcilePending`、
  `backfillOlderHistory`、`sendText`（175 行）、`sendDraft`（87 行）、`fetchLocation`、`shrinkPhoto`（41 行）
- 相机 `ActivityResultContracts.TakePicture` 的回调、B 站/网页预览的编排、草稿与上传的时序

后果：任何一处改动都要在这 1,400 行里定位；局部函数必须先声明后使用（已经踩过 `Unresolved reference`）；
编译通过 ≠ 行为正确，而它偏偏是唯一没法单测的地方。

**建议**（按风险从低到高）：

1. **纯搬运**：把 `shrinkPhoto`、`biliMetaText`、`webMetaText` 这类纯函数提到 `util/`，可单测；
2. **抽状态**：把同步/对账（`syncEvents`/`syncAllHistory`/`reconcilePending`/`backfillOlderHistory`）搬进 `ChatSync` 类，
   它们只依赖 `dao` + `api`，不需要 Compose；
3. **再谈 ViewModel**：把发送编排（`sendText`/`sendDraft`）搬进 `ChatViewModel`，Composable 只留渲染。
   这一步会碰行为，必须一次一屏截图对照。

## 三、P1：`ApiClient` 把三件事塞进一个类

| 段落 | 内容 |
|---|---|
| 配对 / 事件 / 聊天 / 上传 / 媒体 | 真正的 API 调用 |
| `directBiliPreview`（237 行）、`biliMixinKey`、`biliSign`、`md5`、`biliClock` | B 站 WBI 签名（**Worker 侧另有一份同算法实现**，见 `ds-memory/src/index.ts` 的 `biliSign`） |
| `cachedBiliPreview` / `saveBiliPreview` / `cachedWebPreview` | 预览结果落盘缓存 |

签名算法两边各写一遍，改一处必然漏一处。**建议**：抽 `BiliClient.kt`（签名 + 直连预览）与 `PreviewCache.kt`，
并在注释里标明「与 Worker 的 biliSign 必须同步」。

## 四、P2：注释与实现已经不一致（会被误导的地方）

| 位置 | 说法 | 实际 |
|---|---|---|
| `docs/HANDOFF.md` 第二节 | 「下一个任务：整个 App 重新设计」 | 已完成（`95ab88b` 全站纸墨） |
| 同上 | `ChatActivity.kt` 3,153 行 | 1,804 行 |
| 同上 第四节 ⚠️4 | 两套气泡并存（`MessageItem` vs 内联 `SegmentedMessage`/`AvatarBadge`） | 已统一：全文件只剩 2 处 `MessageItem` 调用，无 `SegmentedMessage`/`AvatarBadge` |
| `docs/TASKS.md` §20 | App README + LICENSE 待补 | 仓库里已有 `README.md` 与 Apache-2.0 `LICENSE` |
| `docs/TASKS.md` §19 | `e74fb93`/`5b7f2e4`「待编译验证」 | 两个提交之后的 `b77b8bd` 已装到真机跑过 |

## 五、死代码 / 可疑项

- `hs_err_pid*.log`（7 个）、`replay_pid*.log`（2 个）、`build*.log`、`push.log`：JVM 崩溃与构建日志，共约 4 MB，**在仓库根目录**。
  建议删掉并加进 `.gitignore`（本报告不动它们，先确认没被 git 跟踪）。
- `dist/DS-Mobile-0.1.0.apk`（107 MB）：产物不应入仓，确认是否被跟踪。
- `parse/ParseSpike.kt`（108 行）：名字就是「试验」，确认还在用。

## 六、建议顺序

1. 删掉仓库根目录的 JVM/构建日志 + 确认 `.gitignore`（零风险）；
2. `ApiClient` 抽 `BiliClient`（纯搬运，编译即验证）；
3. `ChatActivity` 抽纯函数到 `util/`（可单测）；
4. 同步逻辑抽 `ChatSync`（行为不变，靠真机回归）；
5. 发送编排 ViewModel 化（最后做，一次一屏截图）。

> 每一步都能独立回滚，且前面几步不改变任何用户可见行为。