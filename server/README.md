# 慢慢业务服务

Go + PostgreSQL 业务服务，身份由 CloudBase 当前用户接口验证；客户端不能指定账号归属。服务只监听本机，通过 Caddy 的 HTTPS 对外提供接口。

`/fonts/` 由 Caddy 从独立目录 `/srv/manman-fonts` 提供三份可公开分发的开源字库及对应 OFL 许可，安装文件来自 `server/assets/public-fonts`；应用仅在用户选择时下载并验证固定 SHA-256。该目录不存放任何业务数据，示例配置见 `deploy/caddy-ip-site.example`。

## 数据与权限

- `migrations/001_initial.sql` 为三张表启用并强制 PostgreSQL 行级安全；每个事务使用身份认证得到的账号上下文。`manman_app` 不是超级用户，也没有 `BYPASSRLS`。
- 操作 ID 支持幂等重试，版本不一致返回冲突；删除以墓碑方式同步。
- 图片位于 `/var/lib/manman/attachments`，目录仅服务账号可读写，按认证账号隔离。允许 PNG、JPEG、WebP，每张最多 8 MB，每账号最多 512 MB；同一附件编号不可被不同内容覆盖。
- `/v1/recognize/notice` 要求登录、账号绑定、请求中明确确认以及服务端配置 MiMo；图片必须属于当前账号。用户在电脑设置密钥后，用授权码将模型额度绑定到最多两个 CloudBase 账号，其他账号不能调用；两个账号的数据相互隔离。每次最多发送 5 张、合计 12 MB；识别频率和每日次数在单进程内限制，重启后重置，不能作为账单硬上限。
- MiMo 使用小米官方兼容接口，默认模型 `mimo-v2.6-flash`；只允许官方按量和 Token Plan 两个 HTTPS 地址，限制输出长度，并校验候选 JSON。识别结果不能自动写入业务数据。
- 识别诊断只记录固定功能名、状态码、错误阶段、响应字节数和耗时；日志不输出验证码、密钥、令牌、账号、文件名、学生姓名、通知或图片内容。模型多返回的无关字段不会导致整份结果被拒绝，但已使用的字段仍需检查；缺失的通知时间不推测。

## 测试

```sh
go test -race ./...
go vet ./...
```

配置独立测试数据库 `MANMAN_TEST_DATABASE_URL` 后，还会验证 PostgreSQL 的账号隔离、连接复用、幂等及并发冲突。该数据库必须先创建 `manman_app` 角色并执行迁移，不得指向正式数据。MiMo 单元测试使用本地模拟服务，不产生真实模型费用。

## 部署与配置

`deploy/manman.service` 使用受限系统账号、私有状态目录和资源限制；`deploy/caddy-ip-site.example` 展示证书和反向代理配置。以 IP 地址申请 Let's Encrypt 短期证书时，还需在 Caddy 全局配置 `default_sni` 为服务器公网 IP，使不发送 SNI 的 Android 客户端可访问。保持 Caddy 正常运行及 80/443 可达以自动续期。不要覆盖其他已存在站点配置。

识别服务通过环境变量 `MANMAN_MIMO_KEY_FILE`、`MANMAN_MIMO_PAIR_HASH_FILE`、`MANMAN_MIMO_ENDPOINT`、`MANMAN_MIMO_MODEL` 配置；密钥和授权码摘要文件应只允许服务账号读取。用户可在开发电脑运行 `tools/设置MiMo密钥.command`，以隐藏输入方式经 SSH 安装到服务器；脚本显示的授权码只输入应用，不应写入聊天。账号绑定摘要保存在服务私有目录，不能将密钥写入代码、APK、日志或聊天。未配置或未绑定时识别接口不可用，手工录入仍可使用。使用前须核实账户套餐、当前价格及学校对发送通知的授权。参考 [小米 MiMo 图片理解](https://mimo.mi.com/docs/zh-CN/quick-start/usage-guide/multimodal-understanding/image-understanding) 和 [模型价格](https://mimo.mi.com/models/zh-CN/mimo-v2.6-flash)。

如授权码丢失且两个绑定名额尚未用完，可运行 `tools/重设MiMo授权码.command` 生成新码；已绑定账号不受影响，两个名额都用完后脚本会拒绝重设。

数据库及附件手动加密备份和恢复演练见 [备份与恢复](../specs/数据备份与恢复.md)。测试账号的真实短信、云端换机恢复和小米手机提醒仍待验证；真实学生数据不能只依赖单台服务器或同一台电脑。
