# 交接文档 · 2026-10-31

> 给下一个窗口的接手人（是 AI 也没关系）：这份文档假设你没有当前会话的记忆。
> 读完这一页就能接着干，不用重新考古。
>
> **2026-10-31 状态更新**：第二节「下一个任务：整个 App 重新设计」**已经做完**（`95ab88b feat(ui): 全站改成纸墨风`），
> 现在 App 全站是纸墨（Paper & Ink），和网页端同一套令牌。第二节保留作为设计决策记录，不再是待办。
> 另外第二节里 `ChatActivity.kt` 的行数过时了：现在是 **1,804 行**，不是 3,153 行。
> 本仓库当前 HEAD：`b77b8bd`（去掉每 3 秒一次的健康检查，连接状态改由消息轮询驱动）。

## 一、现在在哪

两个仓库，一套已经在跑的产品：

| 仓库 | 是什么 | 状态 |
|---|---|---|
| `D:\WorkSpace\ds-memory` | Cloudflare Worker（Hono + TS），记忆/关系/情绪/主动消息的引擎 | 77 个测试文件 881 个用例全绿，`npx tsc --noEmit` 干净 |
| `D:\WorkSpace\ds-mobile` | Android 客户端（Kotlin + Compose），公开仓库 `dikt-dev/ds-mobile`，Apache-2.0 | 能编译能装，手机上在跑 |

- 手机：Redmi K60（`adb devices` 里的 `9ef7a283`），服务端 `https://fae.us.ci`
- App 最新提交：`e12ba8a`（main 已推到 GitHub）
- 引擎侧有一批提交**还没部署**，见第四节

## 二、下一个任务：整个 App 重新设计

用户原话：「帮我重新设计整个 app 页面，让它变得更加好看，清晰明了，颜色和谐，每个页面都重新设计」。

### 要重做的页面

| 页面 | 代码位置 | 现状 |
|---|---|---|
| 配对/登录页 | `ui/ChatUi.kt` 的 `PairingScreen` | 能用，最大的门面 |
| 聊天页 | `ui/ChatUi.kt`（`ChatTopBar` / `MessageItem` / `ChatComposer` / `AttachmentTray` / `DraftBar` / `VoicePanel`）+ `ChatActivity.kt` 内联的分组气泡 | 主角，但两套实现并存 |
| 空会话引导 | `ui/ChatUi.kt` 的 `EmptyChat` | 有一版 |
| 设置页 | `SettingsScreen.kt`（全屏 Dialog） | 声音/哔哩哔哩/服务器/通知/关于，功能齐、视觉一般 |
| 她的状态页 | `StatusScreen.kt` | 刚重做过（头像+心情主卡、三张图标卡、余额卡） |
| B站登录 | `BiliLoginActivity.kt` | 原生 WebView 壳 |
| 通知设置 | 系统设置跳转，无自有页面 | — |

### 已经有一套设计系统了，别另起炉灶

`ui/theme/Theme.kt` 里有一个叫 **「纸墨」（Paper & Ink）** 的设计系统，注释写得很清楚：

- 画布暖纸色、正文近黑墨色，对比度一律 ≥4.5:1
- 除了「她」的紫，界面上不出现第二种彩色；状态色只用在成功/失败
- 不靠投影分层，靠 1px 发丝线和留白分层

配套：`Ds`（色板）、`DsSpace`（间距刻度）、`DsTheme`、`ui/components/DsComponents.kt`（DsButton / DsField / DsPill / DsDivider / DsDot / DsIconButton / DsTextButton）。

**现状的矛盾**：`ChatActivity.kt` 里还有一套内联气泡（自己写的渐变、双层阴影、AvatarBadge），和纸墨的「不靠投影分层」是冲突的。重设计前先决定一件事——**统一到纸墨**，还是**保留渐变气泡但把色板和间距统一**。用户没表态，问一句再动手，别自己猜。

### 建议做法

1. 先跑一次 `ui-ux-pro-max` 技能（`design_recommend` / `design_search`）拿配色和字体建议，再和纸墨对齐
2. 只改视觉层，不动逻辑；每改完一个页面就 `assembleDebug` + 装机 + 截图自查（命令见第四节）
3. 一轮只改一个页面，做完给用户看截图，别一次全改完再翻车

### 用户的审美偏好（都是从反馈里攒的）

- 要「和谐、清晰明了、颜色统一」——最恨的是同一屏里两种体系打架（比如一边 26dp 小头像一边 50dp）
- 喜欢有层次但不脏：气泡双层阴影 + 垂直渐变是他认可的
- 讨厌头重脚轻：顶栏元素不能比正文里的元素还大
- 字要大、行距要松，长句多
- 主色是紫（`#6C5CE7` 系），不要第二主色

## 三、环境与命令

### App 编译与装机（Windows）

```powershell
cd D:\WorkSpace\ds-mobile
& "D:\Program Files\Gradle\gradle-9.8.2\bin\gradle.bat" :app:assembleDebug --console=plain *> build.log
Select-String -Path build.log -Pattern "^e: |BUILD " | Select-Object -First 10   # 只看错误和结果
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

截图自查（这一步很重要，别靠想象）：

```powershell
adb shell screencap -p /sdcard/s.png; adb pull /sdcard/s.png D:\WorkSpace\_shot.png
# 然后用 read_image 看 _shot.png
```

### 引擎（ds-memory）

```powershell
cd D:\WorkSpace\ds-memory
npx vitest run          # 881 个用例
npx tsc --noEmit
```

## 四、坑（都踩过，别再踩）

1. **pwsh 的 `workdir` 必须显式给**。不给就跑到 `D:\WorkSpace` 根目录，`adb install` 会打印 `Performing Streamed Install` 然后静默失败，你以为装上了其实还是旧包。判断依据：输出里没有 `Success` 就是没装上。
2. **Kotlin 局部函数必须先声明后使用**。在 `@Composable` 里写的 `fun foo()` 只能被它后面的代码调用，写在前面就 `Unresolved reference`。把工具函数（如图片压缩）提到调用点之前。
3. **中断过的 Gradle 构建会留下坏增量状态**，报奇怪的 `unresolved`，用 `--rerun-tasks` 重来。
4. **两套 UI 并存**：`ui/ChatUi.kt` 的 `MessageItem` 是列表里调用的那套，但分组消息/头像/语音在 `ChatActivity.kt` 内部还有一份（`SegmentedMessage`、`AvatarBadge`）。改视觉前先 grep 确认改的是生效的那份，改完截图验证。
5. **App 里只有 `ChatActivity`、`MainActivity`、`BiliLoginActivity` 三个 Activity**，设置页和状态页都是全屏 `Dialog`（`usePlatformDefaultWidth = false`）。

## 五、冻结与红线

- **引擎部署冻结到 2026-11-01 12:00（北京时间）**。这之前不许 `wrangler deploy`，否则会污染正在观察的基线。代码提交随便。
- 到点后要部署的积压：TTS 统一（`d7639bf`）、B站卡片（`12dca91`）、时间标签修复（`b7366a7`）、状态 API（`9f00499`）、主动消息修复（`a1c701f`）等共 21 个提交。部署命令：`npx wrangler deploy -c wrangler.local.toml`，需要 `CLOUDFLARE_ACCOUNT_ID=1d0cedf164665fcc2ff558ec4a0564a2`。
- 记忆系统的红线：事件阶段 A 只写不读、引用必须逐字、第一次/去年不衰减、永不物理删除记忆、雷区与禁忌永不过期。
- 聊天不要走 OpenCode Go（ToS 风险），代码里零依赖。

## 六、这一批已经做完的（别再重做）

| 项 | 结果 |
|---|---|
| 设置页「试听」 | 用输入框当前值合成，Key 空禁用，连点先停 |
| 媒体消息延迟回复 | 图/视频/GIF/B站链接先渲染再等 1.5s，期间「正看着你发的…」 |
| 气泡层次 | 双层阴影 + 垂直渐变 + 上亮下暗描边；同一人贴紧，换人 12dp |
| 拍照 | 调系统相机（`ActivityResultContracts.TakePicture` + FileProvider），拍完挂成**草稿**，确认再发；压缩到 2048px/q88 |
| 头像 | 换网页版高清图；气泡旁 50dp、顶栏 40dp、分组头像 50dp（三处统一过） |
| 状态页 | 头像+心情主卡、关系/记忆/今天三张图标卡、深色余额卡；只给模糊说法，只有余额给数字 |
| 缓存 | 磁盘 4GB / 2 万文件，内存图片 48 张，视频落盘二刷不下载，TTS 缓存 300 段 |
| 许可证 | Apache-2.0 + README |

## 七、还没做的（按日期排好了，在 `ds-memory/docs/TASKS.md` 第二十节）

11-01 部署 → 11-01 晚观察主动消息 → 11-02 回忆分享决策 / 记忆恢复 API → 11-04 分级召回 → 11-06~07 情绪向量复检 → 11-08~09 T-11b/c、K-1/K-2、K-4 → 11-10~13 T-13/T-14 → 11-15 起 Phase 6。

另外一直挂着的一项：**A-7 代码整理**——`ChatActivity.kt` 已经 3153 行，重设计之前或之后都该拆一次（先出审查报告：行数分布、死代码、注释对不上的地方，再动手拆）。

## 八、这个产品的性格（改 UI 时别丢）

她不是一个「助手」。她有自己的情绪、脾气，会拒绝、会闹、会因为心情不好不想说话。界面上任何「功能化」的措辞都会破坏这个东西：不要「智能助理」「为您服务」「满意度」，要像一个真的在跟你说话的人。

同一套哲学也适用于代码：能少约束就少约束，只告诉她是谁、能做什么，不规定怎么做。
