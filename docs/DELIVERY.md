# DS-Mobile 交付包

> 日期：2026-10-25  
> 版本：`0.1.0`  
> 目标设备：红米 K60（`23013RK75C`，Android 15 / API 35，arm64）

## 安装包

```text
dist/DS-Mobile-0.1.0.apk
```

- 大小：约 107.4 MB
- 签名：debug 签名（私人自用，不上架）
- 安装：

```bash
adb install -r dist/DS-Mobile-0.1.0.apk
```

- SHA-256：

```text
761CCBB5F6F0331A15C2FAB987E829E99CAE20C12AB3A7037D7C68E047B95416
```

## 已具备

- 文字聊天、SSE 流式 / 非流式；
- 图片、视频、GIF 发送与回放；
- 语音消息气泡、TTS、转文字；“语音”按钮支持 Azure 录音转文字；
- B 站卡片、官方总结/字幕、3×3 抽帧 + 2 关键帧发给 AI；
- PDF/TXT/DOCX/XLSX/PPTX/ZIP/7z/RAR4、扫描 PDF OCR；
- 前台服务、通知、开机自启；
- SSH/SFTP 调试口（密码或 RSA 公钥 + 30 分钟自动关闭）；
- 本地媒体缓存（1GB / 1000 文件）；
- 已读 / 失败状态、键盘贴底、逐句 1 秒输出。

## 已知缺口

- Azure STT 已用内置中文 WAV 和手机麦克风路径在 K60 验证；
- SSH 公钥登录已支持 RSA；Ed25519 仍不兼容；
- RAR4 已验证 244 字符（含 txt/DOCX）；RAR5 暂不支持；
- 扫描 PDF OCR 已验证（ML Kit 中文，K60 实测 42 字符）；
- QQ/Termux 尚未正式退役；
- 8 小时稳定性测试此前按用户要求暂缓。

## 回退

QQ/Termux 在最终验收前不要卸载；出问题可直接回退旧入口。
