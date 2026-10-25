# QQ / Termux 退役准备

> 日期：2026-10-25  
> 状态：**未执行，等待确认**。QQ 和 Termux 仍在手机上运行。

## 当前设备状态

```text
com.tencent.mobileqq         运行中
com.tencent.mobileqq:MSF     运行中
com.termux                   运行中
com.termux.boot              已安装
com.termux.api               已安装
```

## 退役前检查

- [x] DS-Mobile 可安装、可启动；
- [x] 文字聊天、图片/视频/GIF、语音、TTS；
- [x] 麦克风 Azure STT 已验证；
- [x] B 站卡片、总结/字幕/抽帧；
- [x] PDF/TXT/DOCX/XLSX/PPTX/ZIP/7z/RAR4/扫描 PDF OCR；
- [x] 前台服务划掉后台仍存活；
- [x] SSH/SFTP（密码或 RSA 公钥）；
- [ ] 真人连续使用 1~2 天无致命问题；
- [ ] 确认 DS-Mobile 能覆盖原先 NapCat 的 QQ 消息场景；
- [ ] 用户明确同意停用/卸载。

## 退役步骤（确认后执行）

1. 备份 Termux `~/` 下 NapCat 配置和 QQ 登录缓存；
2. 停止 Termux 里的 NapCat 进程；
3. 关闭 Termux:Boot 自启脚本；
4. 关闭 QQ 自启/后台运行；
5. 保留 3~7 天回退期，不立即卸载；
6. 回退期无问题后再卸载 Termux、QQ。

## 回退

- QQ/Termux 未卸载前，随时可以重新打开旧入口；
- DS-Mobile 的设置、Token、B 站登录和媒体缓存不受影响。
