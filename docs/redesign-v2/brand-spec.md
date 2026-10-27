# 品牌与现有系统

真实 Logo：仓库 `docs/icon.png`，深靛蓝底、白色狮子。每版原样内嵌，不重画。

当前 `UI.kt` / color resources / UI 规范提取：背景 #FFFFFF、surface #FFFFFF、surface2 #F4F6F9、border #E3E7ED、text #222B37、muted #626C7C、primary/primaryFill #2858D8、primaryC #EDF2FF。深色基底 #141820。间距 4/8/12/16/20/24/32dp；圆角12/16/99dp；正文16–17sp；标题28–32sp；标签12–13sp；控件48dp；列表56dp；焦点2dp；页面平面背景、无柔光、无浮起卡片阴影。字号阶梯12/13/14/15/16/17/20/22/28/32。字重regular/medium/bold。链接与填充按钮分别选择可读颜色。状态同时使用文字与图形。

图标仅使用仓库 `android/app/src/main/res/drawable/ms_*.xml` 的 Material Symbols path；其余必要标记使用基础几何与文本。原型统一系统中文无衬线，英文允许系统无衬线/等宽代码。Android 外壳依据 huashu-design 的 `assets/android_frame.jsx`。

本轮方向色从 Logo 的深靛蓝与任务语义推导：A 让品牌靛蓝只落在动作和项目上下文；B 让琥珀作为夜间待处理信号，减少深色上大片高亮；C 以淡蓝色内容分区和柠檬黄审批区，把状态视觉层级放大。不是外部品牌官方颜色。

不加入股票图、时钟仪表、装饰照片。任务文字、路径、命令和文件差异是本产品应有的素材。所有业务内容均为样例，页面可见标注“离线交互原型 · 内容为样例”。
