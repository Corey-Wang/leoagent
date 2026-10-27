# LeoAgent 改版

当前状态：用户已选择 A「清晰工作台」，已完成原生 Android App 改版和隔离设备验收。选择记录见 [direction-approved.md](direction-approved.md)。

双击打开 [三个方向](design-demos/index.html)，无需启动服务器或联网。

| 方向 | 设计依据与重心 | 可交互原型 | 完整截图 |
|---|---|---|---|
| A 清晰工作台（推荐） | Things 移动界面的原生列表；阅读和审批优先 | [A](design-demos/dir-1.html) | [A 四屏](design-demos/dir-1.png) |
| B 夜航控制台 | Linear Mobile 的紧凑工作流；项目、任务与执行证据 | [B](design-demos/dir-2.html) | [B 四屏](design-demos/dir-2.png) |
| C 项目工作簿 | 书籍版心与旁注；深绿项目书眉、中文阅读结构 | [C](design-demos/dir-3.html) | [C 四屏](design-demos/dir-3.png) |

另有 [六候选总览](design-demos/thumbs.html)，其中三张为可继续展开的备选。

每版包含任务对话、项目会话、消息、设置四台独立的交互演示。可以切换标签、展开差异、演示批准/拒绝、输入样例任务、筛选与标记消息、选择 Agent、切换通知、查看连接与配对弹层。原型没有接入后台，命令、验证码、连接状态和版本均为明确标注的界面样例；不保存凭据或上传数据。

浏览器验证覆盖三版的实际点击行为、首屏审批按钮、48px 主要操作、独立设备状态、HTML 转义、会话搜索、消息筛选、未知结果核对、连接设置与配对。390px 和 320px 均无横向溢出，控制台无 JavaScript 错误，外部 HTTP 请求为零。验证记录见 [verification.json](verification.json)。

Huashu 体检记录见 [design-lint.json](design-lint.json)。等宽文字用于真实命令、路径和数字，A/B 系统字体现实原生约束下保留。自动外观比较仍提示 A/C 浅色板接近、四台手机外框重复；C 已重构为项目书眉与双栏旁注，未宣称自动差异比较通过。这些是方向探索阶段的浏览器工具结果；原生 App 的集成验收另行记录。

需求与来源：[spec.md](spec.md)、[brand-spec.md](brand-spec.md)、[directions.md](directions.md)、[官方参考截图](refs/mobile-references.jpg)。遵循 [Huashu Design 三方向门](https://github.com/alchaincyf/huashu-design/blob/master/SKILL.md)：“先出三个方向的真实初稿给用户选，用户选定后才执行。”

原生落实范围、设备验收和截图见 [implementation.md](implementation.md)。
