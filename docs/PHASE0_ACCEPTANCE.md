# DS-Mobile 阶段 0 验收记录

> 日期：2026-10-25  
> 设备：Redmi K60（23013RK75C / mondrian，Android 15 / API 35，arm64-v8a）  
> 构建：Kotlin 2.0.21 + Compose + AGP 8.7.3 + Gradle 8.9 + JDK 17 + SDK 35  
> 状态：**P0 基本完成；仅重启自启待 HyperOS 白名单确认**

---

## 1. 构建与安装

| 项 | 结果 |
|---|---|
| 空工程编译 | ✅ `assembleDebug` 成功 |
| APK | `app/build/outputs/apk/debug/app-debug.apk`，约 42MB（含 SSHD/PDFBox） |
| 安装 | ✅ `adb install -r` 成功（需开启 K60「USB 安装 / USB 调试安全设置」） |
| 启动 | ✅ `com.dsmobile.app/.MainActivity` 正常 |
| 包名 | `com.dsmobile.app` |
| minSdk / targetSdk | 35 / 35 |

构建时 Gradle 自动补装了 Build-Tools 34；SDK 中同时有 35.0.0。

---

## 2. P0-04 前台服务与划掉后台

### 2.1 已通过

- 服务：`KeepAliveService`，`foregroundServiceType="remoteMessaging"`；
- 常驻通知：`isForeground=true foregroundId=1001`；
- 每 3 秒请求 `https://fae.us.ci/api/health`，状态更新为 `OK HTTP 200`；
- 划掉最近任务后：
  - 服务仍在；
  - 进程仍在；
  - 健康检查继续；
  - 截图见 `screenshot-p0-ok.png`。

### 2.2 未通过/待配置

- 重启 K60 后，`BOOT_COMPLETED` 未触发 `BootReceiver`，服务未自动启动。
- 原因判断：HyperOS 默认禁止第三方应用自启动，需要手动把 DS-Mobile 加入自启动白名单。
- 待办：
  1. 设置 → 应用设置 → DS-Mobile → 自启动：开；
  2. 省电策略：无限制；
  3. 最近任务下拉锁定；
  4. 重启后再测。
- 如果自启动仍不生效，阶段 2 用 WorkManager/AlarmManager 兜底，或接受“重启后手动打开一次”。

---

## 3. P0-05 SSH/SFTP 调试服务

### 3.1 已通过

- 服务：`DebugSshService`，`specialUse` 前台服务；
- 端口：8022；
- 用户：`dsmobile`；
- 认证：**阶段 0 临时密码**（每次启动随机 12 位；正式版改公钥 + IP 白名单）；
- 根目录：App 私有 `filesDir`；
- PC 端 `paramiko` 实测：
  - 连接成功；
  - 列出远端文件；
  - 上传 32 字节文件；
  - 下载并校验内容；
  - 删除测试文件；
  - 结果：`OK`。

### 3.2 踩坑记录

1. Android 没有 `/home`，MINA SSHD 启动报 `No user home folder available`  
   → 调用 `PathUtils.setUserHomeFolderResolver { filesDir.toPath() }`。
2. 默认 KEX 列表在 Android 上为空  
   → 显式 `keyExchangeFactories = group14-sha256/group14-sha1`。
3. 默认签名/加密/MAC 列表为空  
   → 显式设置 RSA 签名、AES CTR、HMAC-SHA2。
4. Android 自带 BC Provider 不支持 SHA-256（`no such algorithm: SHA-256 for provider BC`）  
   → 移除 BouncyCastle 依赖，改用 AndroidOpenSSL/Conscrypt 作为 MINA 默认 Provider。
5. 主机密钥默认算法不确定  
   → `SimpleGeneratorHostKeyProvider` 显式 RSA 2048。

### 3.3 待办

- 正式版改 **公钥登录**（用户选定的最终方案），去掉随机密码；
- 加 IP 白名单、30 分钟无连接自动关闭；
- 加 FTPS 2121（阶段 6）。

---

## 4. P0-03 API 契约

已完成：[API_CONTRACT.md](API_CONTRACT.md)

覆盖：

- 设备配对/Token；
- `/api/app/events` 增量事件；
- `/v1/chat/completions` App 渠道；
- `/api/app/upload-url` R2 预签名；
- 错误格式、幂等、安全要求。

---

## 5. P0-06 文件解析 spike

**已通过**：

| 项 | K60 实测 |
|---|---|
| PDF | 50 页，1641 字符；生成 352ms，PDFBox 解析 181ms |
| ZIP | 100 条目，6400KB；生成 46ms，解析 20ms |
| 内存 | 未 OOM，解析期间 UI 正常 |
| 文件 | PDF 20KB / ZIP 32KB |

- PDF 用 `PDFBox-Android 2.0.27.0`；
- ZIP 用 `java.util.zip`；
- 截图：`screenshot-p0-parse.png`。
- 结论：K60 处理常见 PDF/ZIP 解析没有压力；后续只需加大小/条目/超时限制。

---

## 6. 当前项目结构

```text
ds-mobile/
  app/
    src/main/java/com/dsmobile/app/
      MainActivity.kt
      service/KeepAliveService.kt
      service/DebugSshService.kt
      service/BootReceiver.kt
    src/main/res/values/
  docs/
    PROJECT_REPORT.md
    API_CONTRACT.md
    PHASE0_ACCEPTANCE.md
    screenshot-*.png
  build.gradle.kts
  settings.gradle.kts
  gradle.properties
  gradlew.bat
```

---

## 7. 阶段 0 出口判断

| 条件 | 状态 |
|---|---|
| 空 APK 可编译/安装/启动 | ✅ |
| 前台服务划掉后台仍运行 | ✅ |
| 重启自启 | ⚠️ 待 HyperOS 白名单 |
| SFTP 可上传/下载 | ✅ |
| API 契约冻结 | ✅ |
| PDF/ZIP 解析 spike | ✅ |
| 风险清单 | ✅（见项目报告） |

**结论：阶段 0 只剩“重启自启”需要 HyperOS 白名单验证；其余出口条件已满足。**
