# DS-Mobile 调试服务记录（SSH/SFTP）

> 日期：2026-10-25

## 已实现

| 项 | 状态 |
|---|---|
| SSH/SFTP 端口 | `8022` |
| 用户名 | `dsmobile` |
| 登录方式 | 随机密码；存在 `authorized_keys` 时只用 RSA 公钥 |
| 文件根目录 | App `filesDir`（虚拟文件系统） |
| 局域网 IP | 自动显示 |
| 自动关闭 | ✅ 30 分钟后 `stopSelf()` |
| Android 兼容 | ✅ K60 实测公钥 SFTP 可登录/列目录 |

## 公钥登录

```bash
adb push ~/.ssh/id_rsa.pub /data/local/tmp/authorized_keys
adb shell "run-as com.dsmobile.app sh -c 'cat /data/local/tmp/authorized_keys > files/authorized_keys'"
```

- 文件非空时关闭密码登录，只接受匹配的 **RSA** 公钥；
- 文件为空/不存在时回退随机密码；
- 当前 MINA 2.14 的 `AuthorizedKeyEntry` 不认 Ed25519，先使用 RSA 2048 密钥；
- 引入 BouncyCastle 后 KEX 已恢复，K60 上实测 `sftp ls` 成功。

## 安全边界

- 只在局域网使用；
- 默认 30 分钟自动关闭，避免长期暴露；
- 下一步补 IP 白名单 / Ed25519 / FTPS 2121。

## 待办

- Ed25519 authorized_keys 兼容；
- IP 白名单；
- 连接审计日志；
- FTPS。
