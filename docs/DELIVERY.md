# DS-Mobile 交付包

> 日期：2026-10-25  
> 版本：`0.1.0`  
> 目标设备：红米 K60（`23013RK75C`，Android 15 / API 35，arm64）

## 安装包

```text
dist/DS-Mobile-0.1.0.apk
```

- 大小：约 42.1 MB
- 签名：debug 签名（私人自用，不上架）
- 安装：

```bash
adb install -r dist/DS-Mobile-0.1.0.apk
```

- SHA-256：

```text
38CB28429817181685D462DB25542B37F0A803218C2CBB1650B70F0184D24D10
```

## 已具备

- 文字聊天、SSE 流式 / 非流式；
- 图片、视频、GIF 发送与回放；
- 语音消息气泡、TTS、转文字；
- B 站卡片、官方总结/字幕、3×3 抽帧 + 2 关键帧发给 AI；
- PDF/TXT/DOCX/XLSX/PPTX/ZIP/7z 解析与文件卡片；
- 前台服务、通知、开机自启；
- SSH/SFTP 调试口（密码 + 30 分钟自动关闭）；
- 本地媒体缓存（1GB / 1000 文件）；
- 已读 / 失败状态、键盘贴底、逐句 1 秒输出。

## 已知缺口

- ASR 真实语音转写尚未在 K60 上验收；
- SSH 公钥登录因 MINA KEX 兼容问题暂未启用；
- RAR 只验证了真实 RAR 能被读取，但样本只含旧 `.doc`，尚无 txt/Office RAR 通过样本；
- 扫描 PDF OCR 未做；
- QQ/Termux 尚未正式退役；
- 8 小时稳定性测试此前按用户要求暂缓。

## 回退

QQ/Termux 在最终验收前不要卸载；出问题可直接回退旧入口。
