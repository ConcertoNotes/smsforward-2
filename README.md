# 信捷转发

[![Build Android APK](https://github.com/ConcertoNotes/smsforward-2/actions/workflows/android_build.yml/badge.svg)](https://github.com/ConcertoNotes/smsforward-2/actions/workflows/android_build.yml)

信捷转发是一款 Android 短信与应用通知转发工具。它可以将设备收到的短信和指定应用的通知发送到 Telegram、飞书，两个渠道既可单独使用，也可同时使用。

<p align="center">
  <img src="public/logo.png" alt="信捷转发 Logo" width="160">
</p>

## 功能

- 接收并转发 SMS，支持长短信分段合并。
- 转发获得授权的应用通知，并可按应用设置忽略名单。
- 支持 Telegram Bot 和飞书群自定义机器人。
- 两个渠道并行发送，单个渠道异常不会阻塞另一个渠道。
- 待发消息持久化，应用或设备重启后继续发送。
- 对网络错误和平台限流自动重试。
- 自动拆分超长消息并记录分段发送进度。
- 在本地保留最近 200 条转发记录。

## 1.5.1 更新内容

- 修复部分 OPPO/ColorOS 设备锁屏后应用通知无法继续转发的问题。
- 收到通知后先获取短时 CPU 唤醒锁，再解析、持久化并立即唤醒前台转发服务。
- 扩展通知正文解析，支持多行文本、消息列表、历史消息和 ticker 文本。
- 应用升级完成、设备开机和屏幕重新亮起时主动请求通知监听重新绑定。
- 增加通知捕获、入队、发送、重试和监听连接状态日志，便于通过 ADB 排查后台问题。
- 版本更新为 `1.5.1`（`versionCode 20`）。

## 安装

### 从 GitHub Actions 下载

1. 打开仓库的 [Actions](https://github.com/ConcertoNotes/smsforward-2/actions) 页面。
2. 进入最新一次成功的 `Build Android APK` 工作流。
3. 在页面底部下载 `XinJie-Forwarder-debug-*` Artifact。
4. 解压 Artifact，安装其中的 APK。

自动构建产物使用 Debug 签名，适合个人安装和测试。如果设备中旧版本的签名不同，需要先卸载旧版本再安装；卸载会同时清除旧版本的配置和转发记录。

### 从源码构建

环境要求：

- JDK 17
- Android SDK 35
- Git Bash（Windows）或兼容的 Unix Shell

执行：

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 首次使用

1. 安装并打开信捷转发。
2. 授予“接收短信”权限；Android 13 及以上系统还需要授予“发送通知”权限。
3. 按系统提示允许信捷转发不受电池优化限制，以提高熄屏和后台运行时的可靠性。
4. 在手机厂商的应用后台管理中允许信捷转发“自启动”和“后台活动”。OPPO/ColorOS 用户必须完成此项，具体操作见下方“后台权限与自启动”。
5. 如果需要转发其他应用的通知，点击“打开设置”，在系统的“通知使用权”页面启用信捷转发。只转发短信时可以暂不启用此权限。
6. 打开底部的“转发设置”，填写 Telegram 或飞书参数。输入内容会自动保存，无需另点保存按钮。
7. 在“忽略以下应用的通知”列表中取消勾选需要转发通知的应用。列表默认全部勾选，勾选表示忽略该应用的通知。
8. 向本机发送一条短信，或让已取消勾选的应用产生一条通知。
9. 锁屏后再次发送一条测试消息，在“转发记录”及 Telegram 或飞书中确认仍能正常收到。

## 后台权限与自启动

Android 的“忽略电池优化”和手机厂商的“自启动/后台活动”通常是两套独立设置。只允许忽略电池优化，并不能保证 OPPO、vivo、小米、华为等系统在锁屏后继续保留通知监听服务。

### OPPO / ColorOS

不同 ColorOS 版本的菜单名称可能略有差异，通常按以下路径设置：

1. 打开“设置”或“手机管家”。
2. 进入“应用管理”或“权限与隐私”。
3. 打开“自启动管理”，找到“信捷转发”，启用“允许自启动”。
4. 进入“电池 > 应用耗电管理”或“应用电池管理”，找到“信捷转发”。
5. 启用“允许后台活动”；如果有“允许关联启动”开关，也一并启用。
6. 打开最近任务界面，找到信捷转发并执行“锁定”，避免被“一键清理”结束。
7. 回到信捷转发，确认前台服务常驻通知存在，并在锁屏状态下发送一条测试消息。

ColorOS 可能在未加入自启动白名单时直接阻止开机、应用升级和锁屏期间的后台启动。此限制由系统执行，应用无法静默修改，必须由用户在系统设置中授权。

### 其他 Android 厂商

- 在“自启动管理”“后台活动”“应用启动管理”或类似页面允许信捷转发自行启动和后台运行。
- 将电池策略设置为“不限制”“无限制”或“允许后台活动”。
- 不要在最近任务或系统管家中手动结束信捷转发。
- 系统升级后如果锁屏转发失效，重新检查通知使用权、自启动和电池策略是否被重置。

## 四个参数

配置页包含以下四项：

| 配置项 | 用途 | 是否必填 |
| --- | --- | --- |
| Telegram 令牌 | 调用 Telegram Bot API 的身份凭证 | 使用 Telegram 时必填 |
| Telegram 会话 ID | 指定 Telegram 接收消息的私聊、群组或频道 | 使用 Telegram 时必填 |
| 飞书 Webhook | 指定接收消息的飞书群自定义机器人 | 使用飞书时必填 |
| 飞书签名密钥 | 为飞书 Webhook 请求生成签名 | 飞书机器人启用“签名校验”时必填，否则留空 |

只配置某一个渠道即可使用。四项全部填写时，消息会同时转发到 Telegram 和飞书。

## 获取 Telegram 令牌

Telegram 令牌由官方机器人 [@BotFather](https://t.me/botfather) 签发：

1. 在 Telegram 中打开 `@BotFather`，确认账号带有官方认证标记。
2. 发送 `/newbot`。
3. 按提示设置机器人的显示名称。
4. 设置机器人用户名。用户名必须唯一，通常需要以 `bot` 结尾。
5. 创建成功后，BotFather 会返回一段类似 `123456789:AAExampleToken` 的 Token。
6. 将整段 Token 填入信捷转发的“Telegram 令牌”。不要包含空格、引号或 `bot` 前缀。

可以用 Telegram Bot API 的 `getMe` 方法检查 Token 是否有效。将 `<BotToken>` 替换为实际 Token 后，在浏览器中打开：

```text
https://api.telegram.org/bot<BotToken>/getMe
```

返回内容包含 `"ok":true` 表示 Token 可用。Token 等同于机器人密码；一旦泄露，应立即在 BotFather 中执行 `/revoke` 生成新 Token。

参考：[Telegram BotFather](https://core.telegram.org/bots/features#botfather)、[Telegram Bot API](https://core.telegram.org/bots/api)

## 获取 Telegram 会话 ID

先完成机器人创建并取得 Token，然后根据接收位置操作。

### 私聊 ID

1. 打开刚创建的机器人，点击 `Start` 或发送任意消息。
2. 将 `<BotToken>` 替换为实际 Token，打开：

   ```text
   https://api.telegram.org/bot<BotToken>/getUpdates
   ```

3. 在返回 JSON 中找到 `result` 数组内的 `message.chat.id`。
4. 将这个整数填入“Telegram 会话 ID”。私聊 ID 通常为正数。

示例结构：

```json
{
  "result": [
    {
      "message": {
        "chat": {
          "id": 123456789,
          "type": "private"
        }
      }
    }
  ]
}
```

### 群组 ID

1. 将机器人加入目标群组。
2. 在群组中发送 `/start@机器人用户名`，或发送一条机器人能够接收的命令。
3. 再次调用 `getUpdates`。
4. 找到该群消息对应的 `message.chat.id`。群组和超级群组 ID 通常为负数，超级群组常以 `-100` 开头。
5. 将完整负数填入“Telegram 会话 ID”，不要删除负号。

### 频道 ID

1. 将机器人添加为目标频道的管理员，并授予发布消息权限。
2. 在频道中发布一条新消息。
3. 调用 `getUpdates`，找到 `channel_post.chat.id`。
4. 将完整负数填入“Telegram 会话 ID”。

如果 `result` 为空，请确认已经在获取 ID 前发送了新消息，然后刷新请求。信捷转发的会话 ID 输入框只接受数字，因此请使用数值 ID，不要填写 `@username`。

参考：[getUpdates](https://core.telegram.org/bots/api#getupdates)、[Message](https://core.telegram.org/bots/api#message)

## 获取飞书 Webhook

飞书 Webhook 来自目标群聊中的自定义机器人：

1. 打开用于接收转发消息的飞书群聊。
2. 进入群设置，在“群机器人”或“机器人”中选择“添加机器人”。不同客户端版本的入口名称可能略有不同。
3. 选择“自定义机器人”，填写机器人名称和描述并完成添加。
4. 在机器人配置页复制 Webhook 地址。
5. 将完整地址填入信捷转发的“飞书 Webhook”。

当前应用接受的地址格式为：

```text
https://open.feishu.cn/open-apis/bot/v2/hook/xxxxxxxxxxxxxxxxx
```

自定义机器人只向它所在的群聊发送消息。Webhook 本身就是敏感凭证，不要发布到仓库、聊天记录或截图中。

参考：[飞书自定义机器人使用指南](https://open.feishu.cn/document/client-docs/bot-v3/add-custom-bot)

## 获取飞书签名密钥

签名密钥取决于飞书自定义机器人的安全设置：

1. 打开目标群聊的自定义机器人配置页。
2. 进入“安全设置”。
3. 启用“签名校验”。
4. 复制页面生成的签名密钥。
5. 将密钥填入信捷转发的“飞书签名密钥”。

如果没有启用签名校验，此项应留空。若后来重新生成或修改了飞书签名密钥，需要同步更新信捷转发中的值。

建议使用签名校验。若同时启用了关键词校验，必须确保每一条转发消息都包含指定关键词，否则飞书会拒绝消息；手机网络出口地址可能变化，通常不适合使用固定 IP 白名单。

## 配置与验证

### 只使用 Telegram

- 填写“Telegram 令牌”和“Telegram 会话 ID”。
- 将“飞书 Webhook”和“飞书签名密钥”留空。

### 只使用飞书

- 填写“飞书 Webhook”。
- 飞书启用了签名校验时，同时填写“飞书签名密钥”。
- 将两个 Telegram 配置项留空。

### 同时使用两个渠道

- 填写 Telegram 的两项参数。
- 填写飞书 Webhook，并按飞书安全设置决定是否填写签名密钥。
- 同一条消息会分别投递，某个渠道暂时失败时不会重复发送已经成功的另一个渠道。

配置完成后，建议先用一条测试短信验证。正常情况下，“转发记录”会显示发送成功，目标 Telegram 会话或飞书群会收到包含来源、时间和正文的消息。

## 常见问题

### Telegram 没有收到消息

- 检查 Token 是否完整，且包含中间的冒号。
- 使用 `getMe` 验证 Token。
- 确认会话 ID 的正负号没有遗漏。
- 私聊时先向机器人发送 `/start`。
- 群组或频道中确认机器人仍在目标会话内，并拥有发送消息权限。
- 确认手机网络能够直接访问 `api.telegram.org`。应用不内置代理或中转服务。

### 飞书没有收到消息

- 确认 Webhook 以 `https://open.feishu.cn/open-apis/bot/v2/hook/` 开头。
- 确认机器人仍在目标群聊中，且 Webhook 未被重新生成。
- 开启签名校验时，确认签名密钥与当前机器人配置一致。
- 检查飞书机器人是否设置了关键词或 IP 白名单，并确认消息满足限制。

### 短信可以转发，但应用通知不能转发

- 在 Android 系统设置中确认信捷转发的“通知使用权”已开启。
- 在“转发设置”的应用列表中找到目标应用并取消勾选。
- 确认目标通知包含可读取的标题或正文；空通知会被过滤。

### 后台运行一段时间后停止

- 允许应用忽略电池优化。
- 在手机厂商的后台管理中同时开启信捷转发的“允许自启动”和“允许后台活动”。
- OPPO/ColorOS 用户确认信捷转发已加入自启动白名单，并在最近任务中锁定应用。
- 如果前台服务通知消失，重新打开信捷转发；如果通知监听提示断开，重新检查“通知使用权”。
- 不要手动结束信捷转发的前台服务通知。

## 发布应用更新

从 `1.5.0` 开始，应用启动时会检查 GitHub Releases，后台服务也会每 6 小时检查一次。发现更高版本后，应用会提示下载，并在安装前校验 APK 的包名、版本号和签名证书。

发布的新版本必须同时满足以下条件：

- 使用 `v主版本.次版本.修订版本` 格式的 GitHub Release Tag，例如 `v1.5.1`。
- Release 必须包含至少一个 `.apk` 资源；GitHub Actions Artifact 不属于 Release，应用无法通过 Releases API 获取。
- APK 内的 `versionName` 必须与 Release Tag 一致，`versionCode` 必须高于旧版本。
- APK 必须使用与已安装版本完全相同的签名证书。GitHub Actions 临时生成的 Debug 签名不稳定，不能用于连续发布可覆盖升级的版本。
- 只发布正式 Release；草稿和 prerelease 不会被 `/releases/latest` 返回。

当前工作流只构建 Debug Artifact。正式发布前应建立受保护的固定签名密钥，并通过 GitHub Actions Secrets 或受控的本地签名流程生成 Release APK。签名密钥及密码不得提交到仓库。

## 数据与安全

- 应用需要读取短信和通知，这是核心功能所需的高敏感权限。
- Telegram Bot 和飞书机器人消息不具备端到端加密，不应作为机密通信渠道。
- Token、Webhook、签名密钥和待发消息保存在 Android 应用私有目录，并排除系统云备份和设备迁移，但当前未使用 Android Keystore 加密。
- 不要将真实 Token、Webhook 或签名密钥提交到 Git、Issue、日志或公开截图。
- 建议限制机器人权限，并只向受控制的个人会话、群组或频道转发消息。

## 项目信息

- 维护者：[ConcertoNotes](https://github.com/ConcertoNotes)
- 仓库：[ConcertoNotes/smsforward-2](https://github.com/ConcertoNotes/smsforward-2)
- Android application ID：`com.concertonotes.smsforwarder`
- 应用名称：`信捷转发`
- 许可证：[GNU General Public License v3.0](./LICENSE)

本项目基于 [Spirit532/SMSForwarder](https://github.com/Spirit532/SMSForwarder) 进行二次开发，不是原项目的官方版本。上游项目及原始实现归其原作者所有；本仓库继续依据 GPL-3.0 发布，并保留许可证与来源说明。
