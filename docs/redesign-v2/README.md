# LeoAgent 第二轮设计

用户本轮请求「用 huashu-design重新设计LeoAgent」，补充「按你的判断继续」，随后选择 `A`。已选 A「静默协作」，原生实现与设备验证记录见 [implementation.md](implementation.md)，本轮确认见 [direction-approved.md](direction-approved.md)。

后续：[0.1.7 正式签名版本与验收](release-candidate.md)，已按用户明确授权发布到现有更新通道。

入口：[原生截图总览](native/index.html) · [设计方向总览](design-demos/index.html)。HTML 原型离线双击可用，不需要启动服务。

| 方向 | 重心 | 交互稿 | 全板截图 |
|---|---|---|---|
| A 静默协作 | 连续阅读与底部审批 | [A](design-demos/dir-1.html) | [A](design-demos/dir-1.png) |
| B 任务信号 | 任务事件与执行证据 | [B](design-demos/dir-2.html) | [B](design-demos/dir-2.png) |
| C 轻快接力 | 醒目的人机交接与信息分区 | [C](design-demos/dir-3.html) | [C](design-demos/dir-3.png) |

[六候选缩略图](design-demos/thumbs.html) 另有「信息刻度」「随行审阅」「原生清单」三个备选。三个完整版使用同一任务内容和 Android 屏幕规格。每版覆盖对话、项目会话、文件改动、连接设置，各设备独立交互。

本轮以最新 `docs/ui-design.md` 为准：无底部导航和独立消息中心。旧 `docs/redesign/` 的选择记录与原型不被覆盖。

设计来源与假设：[规格](spec.md)、[六方向卡](directions.md)、[品牌与素材](brand-spec.md)、[事实核实](product-facts.md)、[官方参考](refs/mobile-product-screens.jpg)。

验证证据：[交互与窄屏复核](verification.json)、[三版设计体检](design-lint.json)、[总览体检](overview-lint.json)。三版均验证批准/拒绝、独立设备状态、草稿保持与转义、会话搜索、未知结果核对、文件展开、设置和配对演示；主审阅另行实际点击批准，检查四台独立实例、等待电脑回执语义、390/375/320px 首屏48px审批按钮和无横向溢出，页面错误和外部请求均为零。复核发现并修复 A 的 `?phone=1` 窄屏固定宽度问题。这组证据只覆盖 HTML。选定 A 后的 Android 构建、真实本地服务集成、窄屏与恢复测试另见 [原生验证记录](native-verification.json)。

外观比较的[原始结果](design-compare.txt)将 A/C 的浅底和系统字体标为相近，并将四台手机外壳归为卡片；这是保留的限制，不声称无条件自动比较通过。按照 Android 系统字体与共享品牌约束，另以完整板面比较[结构差异](design-compare-native.txt)：三版骨架相关0.41–0.50，低于0.55阈值。人工检查也区分 A 的连续阅读与固定审批、B 的执行事件、C 的大状态与摘要/交接块。剩余黄色提示是原生系统字体和真实命令/差异使用等宽文字；不为消除提示而把代码改成正文体。

选择关卡来自 [huashu-design/SKILL.md](https://github.com/alchaincyf/huashu-design/blob/master/SKILL.md)：「先出三个方向的真实初稿给用户选，用户选定后才执行。」本轮用户选择原话 `A` 已单独记录，不使用上一轮的记录替代。
