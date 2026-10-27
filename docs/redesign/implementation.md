# A「清晰工作台」原生实现

最新 0.1.6 已按用户追加要求精简导航与消息中心，见文末记录；下文保留 0.1.5 初版验收历史。

方向选择原话为 `A`，见 [确认记录](direction-approved.md)。实现使用现有 Kotlin Views / Material 3，不增加服务端协议。

## 实际改变

- 浅色平面白底、深色石墨底；链接与白字填充按钮使用独立颜色，避免夜间对比不足。
- 顶栏保留模型、画中画、差异与更多操作；项目与审批方式单独显示。用户消息改浅灰面，Agent 正文取消悬浮卡片。
- 审批固定在组合输入区上方，展示请求和详情预览；允许一次、拒绝直接点击，完整请求保留全部提供方选项。失效请求自动清除，连接或会话变化后旧按钮不能提交。
- 保留图片、语音、发送/停止、任务恢复、多 Agent、桌面同步、文件和后台消息逻辑；主要动作与新增文件入口为 48dp。
- 会话使用原生分组列表、选中浅蓝面与搜索。桌面会话接口不可用且未启用多 Agent 会话筛选时，从现有手机会话接口回退；授权失败不会触发回退。搜索响应使用代次与连接检查，避免旧结果覆盖新查询。
- 消息使用明确事件文字与未读面；设置将连接信息置前，统一细分隔线与文本层级；修正配对表单标签关联方向。
- 长值按当前行宽限制；320dp 容器中保留标题空间。

## 设备证据

使用 `emulator-5554`、独立包 `io.leoagent.app.verification`、临时服务/数据库/项目及确定性 ACP fixture。测试真实触摸事件、原生视图、HTTP 请求、SSE 与持久化回执；不调用真实 Agent。测试完成卸载独立验证包，不安装到物理手机。

命令：

```sh
./android/gradlew -p android -Pleoagent.verification :app:assembleDebug :app:assembleDebugAndroidTest
python3 tests/android_integration.py
# 最后仅针对会话回退/搜索变更复验，仍重新建立隔离服务和配对：
LEOAGENT_ANDROID_REDESIGN_ONLY=1 python3 tests/android_integration.py
```

完整回归覆盖首次触摸与键盘、浅深浅 Markdown 渲染、旧链接、Keystore 和配对、进程重建、三类丢回执与只执行一次、第二设备审批、通知允许/拒绝、消息分页及已读游标。新版设备检查覆盖输入发送、批准、拒绝、全部审批选项、失效卡清除、320dp 长值行、文本对比度、会话回退与搜索，以及两套主题截图。详细结果见 [native-verification.json](native-verification.json)。

修复了验收中发现的窄屏标题受挤压、原始会话错误；同步修正测试对系统弹窗退出动画的等待，通知断言按目标会话核对。

## 原生实拍

截图来自 Android 模拟器，内容为明确的隔离 fixture，包括 `fixture-check --isolated (no shell execution)`，该命令没有被执行。HTML 初稿仍保留供比较。

| 页面 | 浅色 | 深色 |
|---|---|---|
| 审批卡 | [查看](native/redesign-light-approval.png) | [查看](native/redesign-dark-approval.png) |
| 完整审批请求 | [查看](native/redesign-light-details.png) | [查看](native/redesign-dark-details.png) |
| 对话结果 | [查看](native/redesign-light-chat.png) | [查看](native/redesign-dark-chat.png) |
| 项目会话 | [查看](native/redesign-light-projects.png) | [查看](native/redesign-dark-projects.png) |
| 设置 | [查看](native/redesign-light-settings.png) | [查看](native/redesign-dark-settings.png) |
| 消息 | [查看](native/redesign-light-inbox.png) | [查看](native/redesign-dark-inbox.png) |

原型与原生的适配差异：保留真实语音入口；审批详情从卡片展开，全部授权选项在底部面板提供；会话继续使用现有抽屉。没有添加无法由现有数据证明的设备名、连接状态、待处理筛选或指标。

上述隔离验收不包含物理设备录音、真实 Agent 账户、桌面画面捕获、发布签名或线上部署。正式包签名和本机更新通道的补充核验见下文。

## 安装包

普通包名的 debug 安装包：`android/app/build/outputs/apk/debug/leoagent-redesign-A-debug.apk`（`io.leoagent.app`）。独立验收包保存在 `android/app/build/outputs/apk/verification/app-verification.apk`。两者签名验证通过，SHA-256 见验收 JSON。上述 debug 包未安装到物理手机或发布到更新通道。

## 2026-10-24 正式更新通道

经用户明确授权，正式包 0.1.5（versionCode 6，`io.leoagent.app`）已发布到当前电脑的 App「检查更新」通道。`assembleRelease` 和 `lintRelease` 通过，`apksigner verify` 确认签名有效且与原 0.1.4 一致。发布前备份原 APK 和版本元数据；发布后通过实际版本接口和完整 APK 下载，核对版本、更新说明、文件大小及 SHA-256 均一致，服务配置和配对凭据未变。

正式产物及机器核验记录在 `android/app/build/outputs/update-candidate/0.1.5/`；校验值和备份位置见 [发行记录](../releases.md)。本次未创建 GitHub 发行版，手机安装由用户在「设置 → 检查更新」完成。

## 2026-10-24 导航精简（0.1.6）

- 移除底部导航、消息中心、未读角标、历史分页、已读操作和前台定时刷新。旧消息 Tab 偏好不再参与启动。
- 左侧抽屉「设置」打开完整设置页，替换原先的简版设置弹窗；未配置连接时仍显示配置引导。设置顶栏和系统返回键均可返回原对话，保留未发送内容。
- 保留任务完成、审批和提问的系统通知及投递游标，点击通知直接打开对应会话；删除消息中心的本地已读游标操作。服务端接口保持兼容旧客户端。
- 在专用临时 `emulator-5556`、独立 verification 包及临时服务/数据中验收。首次触摸、配对、恢复、丢回执只执行一次、通知允许/拒绝场景通过；修正测试列表行定位后，浅深主题定向验收通过，包括真实抽屉点击、返回按钮/系统返回、草稿保留、设置页重建，以及不再请求消息历史/未读计数。
- 共享模拟器的并发自动化曾干扰触摸与 UiAutomation，因此最终证据来自独立模拟器。测试坐标采样等待 IME/抽屉动画稳定，仍使用真实 DOWN/UP 触摸。
- `assembleRelease`、`lintRelease` 通过，正式包 0.1.6（7）与 0.1.5 同签名。用户明确授权后已发布到现有更新通道；发布前备份原 0.1.5 APK 和元数据，发布后核对实际版本接口和完整 APK 下载，版本、说明、大小及 SHA-256 均一致。服务配置和配对凭据未变，未重启服务或安装物理手机。见 [发行记录](../releases.md) 与 [机器验收记录](navigation-verification.json)。

| 页面 | 浅色 | 深色 |
|---|---|---|
| 对话（无底栏） | [查看](native/0.1.6/redesign-light-chat.png) | [查看](native/0.1.6/redesign-dark-chat.png) |
| 抽屉设置入口 | [查看](native/0.1.6/redesign-light-projects.png) | [查看](native/0.1.6/redesign-dark-projects.png) |
| 完整设置与返回 | [查看](native/0.1.6/redesign-light-settings.png) | [查看](native/0.1.6/redesign-dark-settings.png) |
