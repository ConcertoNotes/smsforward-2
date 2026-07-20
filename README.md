# Concerto SMS Forwarder

[![Build Android APK](https://github.com/ConcertoNotes/smsforward-2/actions/workflows/android_build.yml/badge.svg)](https://github.com/ConcertoNotes/smsforward-2/actions/workflows/android_build.yml)

由 [ConcertoNotes](https://github.com/ConcertoNotes) 维护的 Android 短信与通知转发工具，可通过用户自己的 Telegram Bot 将设备收到的短信和应用通知转发到指定聊天。

## 二次开发说明

本项目基于 [Spirit532/SMSForwarder](https://github.com/Spirit532/SMSForwarder) 进行二次开发，当前仓库不是原项目的官方版本。

二次开发版本保留 GPL-3.0 许可证，并在原有功能基础上重点改进了消息可靠性、Android 后台生命周期、权限安全、Telegram 发送容错、自动化测试和 APK 构建流程。

当前项目身份：

- 维护者：`ConcertoNotes`
- 仓库：`https://github.com/ConcertoNotes/smsforward-2`
- Android application ID：`com.concertonotes.smsforwarder`
- 应用名称：`Concerto SMS Forwarder`

## 主要功能

- 接收并转发 SMS，包括长短信分段合并。
- 读取并转发已授权应用的系统通知。
- 按应用配置通知忽略列表。
- 将待发消息持久化，进程退出或设备重启后继续发送。
- 自动拆分 Telegram 超长消息，并记录分段发送进度。
- 对网络错误执行指数退避，对 Telegram `429` 使用服务端返回的等待时间。
- 自动过滤空通知和重复通知字段。
- 最多保留 200 条本地发送历史。

## 下载 APK

每次向 `main` 分支推送代码，GitHub Actions 都会自动执行：

```text
单元测试 -> Android Lint -> 构建 Debug APK -> 上传 Artifact
```

下载步骤：

1. 打开仓库的 [Actions](https://github.com/ConcertoNotes/smsforward-2/actions) 页面。
2. 进入最新一次成功的 `Build Android APK` 运行记录。
3. 在页面底部下载 `Concerto-SMS-Forwarder-debug-*` Artifact。
4. 解压后安装其中的 APK。

自动构建产物使用 Debug 签名，适合个人安装与测试。不同构建的签名不一致时，需要卸载旧版后再安装。正式分发应配置由维护者保管的稳定 Release keystore。

## 使用方法

1. 在备用 Android 手机上安装 APK。
2. 授予短信、通知和前台服务所需权限。
3. 在系统设置中启用本应用的“通知使用权”。
4. 根据需要在电池优化设置中允许应用持续后台运行。
5. 通过 Telegram [BotFather](https://t.me/botfather) 创建自己的 Bot。
6. 在配置页填写 Bot Token 和目标 Chat ID。
7. 在应用列表中勾选需要忽略通知的应用。

群组或频道 Chat ID 通常为负数，当前版本支持填写负数 ID。

## 相比上游版本的主要改进

- 待发消息持久化，降低系统杀进程造成的消息丢失风险。
- 合并 multipart SMS，避免长短信只转发第一段。
- 更严格的消息去重规则，保留合法的重复验证码或通知。
- Telegram HTML 转义、Unicode 安全分段和断点续传。
- 区分网络错误、配置错误、限流和永久失败，避免单条坏消息永久堵塞队列。
- 使用短时 WakeLock，移除永久 CPU/Wi-Fi Lock。
- 前台服务使用 `START_STICKY`，通知监听器支持自动重新绑定。
- 核心服务不再导出给其他应用。
- 删除不必要的 `READ_SMS` 和直接忽略电池优化权限。
- Token、配置和待发消息排除云备份及设备迁移。
- 移除可能包含短信正文的 Telegram API 响应日志。
- 修复配置页生命周期、搜索竞态和 RecyclerView 全量刷新问题。
- 新增单元测试、Lint 检查和自动 APK Artifact 构建。

## 本地构建

环境要求：

- JDK 17
- Android SDK 35
- Git Bash（Windows）或兼容的 Unix Shell

执行完整检查和构建：

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

生成的 APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

本地构建默认使用阿里云 Maven 镜像；GitHub Actions 使用 Google、Maven Central 和 Gradle Plugin Portal 官方仓库。

## 安全说明

- Telegram Bot 消息不具备端到端加密，不应将其视为机密通信渠道。
- 应用需要读取短信和通知，这是核心功能所必需的高敏感权限。
- Bot Token 和待发消息保存在 Android 应用私有目录，并已排除系统备份，但当前尚未使用 Android Keystore 加密。
- 不要安装来源不明的 APK，也不要将 Bot Token 提交到 Git 仓库、Issue 或日志。
- 建议限制 Bot 权限，并仅向受控制的个人聊天、群组或频道转发消息。

## 许可证与上游归属

本项目根据 [GNU General Public License v3.0](./LICENSE) 发布。

上游项目及原始实现归其原作者所有。本仓库由 ConcertoNotes 基于 GPL-3.0 进行二次开发、维护和发布；任何再分发或衍生版本都应继续遵守 GPL-3.0，并保留相应的许可证和来源说明。
