# DS-Mobile 调试服务记录（SSH/SFTP）

> 日期：2026-10-25

## 已实现

| 项 | 状态 |
|---|---|
| SSH/SFTP 端口 | `8022` |
| 用户名 | `dsmobile` |
| 登录方式 | 默认随机密码；存在 `files/authorized_keys` 时只允许公钥 |
| 文件根目录 | App `filesDir`（虚拟文件系统） |
| 局域网 IP | 自动显示 |
| 自动关闭 | ✅ 30 分钟后 `stopSelf()` |
| Android 兼容 | ✅ K60 实测可登录/传文件 |

## 公钥登录

把 PC 公钥写进 App 私有目录：

```bash
adb push ~/.ssh/id_ed25519.pub /data/local/tmp/authorized_keys
adb shell "run-as com.dsmobile.app sh -c 'cat /data/local/tmp/authorized_keys > files/authorized_keys'"
```

重启调试 SSH 服务后：

- `authorized_keys` 非空时密码登录关闭，只接受匹配公钥；
- 文件为空或不存在时回退随机密码，避免锁死自己。

## 安全边界

- 只在局域网使用；
- 默认 30 分钟自动关闭，避免长期暴露；
- 下一步改公钥登录，去掉密码认证；
- 再下一步补 FTPS 2121 或只保留 SFTP。

## 待办

- IP 白名单；
- 连接审计日志；
- FTPS。
