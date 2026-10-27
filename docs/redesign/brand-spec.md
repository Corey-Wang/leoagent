# 品牌与素材依据

- 主品牌：LeoAgent。Logo 直接取仓库 `docs/icon.png`，与现有 Android 启动图标一致，内嵌进每个 HTML，不重绘。
- 当前色值来自 `res/values/colors.xml`：bg `#F4F4FB`、surface `#FFFFFF`、surface2 `#EEEEF8`、border `#E2E3F0`、text `#1D1F3A`、muted `#8A8CA6`、primary `#5B5CF0`、primaryC `#ECECFF`、strong `#F0443A`、strongC `#FFEDEC`、weak `#F59E2B`、weakC `#FFF4E3`、down `#22B573`、primary2 `#7B8CFF`。
- 当前间距 4/8/12/16/20/24/32dp；圆角 10/16/20/99dp；按钮 50dp；字体使用 Android sans-serif / sans-serif-medium。现有主色渐变与页面柔光作为被改造的上下文，不作为新稿约束。
- 图标使用现有 `res/drawable/ms_*.xml` 的 Material Symbols 路径，不混入 Emoji 或另一套图标。
- 参考板：`refs/mobile-references.jpg`，2026-10-24 抓取并目视查看，来源为 [Linear Mobile](https://linear.app/mobile) 和 [Things](https://culturedcode.com/things/)。仅借鉴信息组织，不把外部产品 Logo 当成 LeoAgent 品牌。
- 字体：A/B 使用系统无衬线和系统等宽字体；C 使用本机宋体/系统中文衬线回退。原型离线可用，不依赖在线字体。
- A：白底、深灰正文、蓝色主动作；B：石墨底、分层面板、薄荷绿操作；C：纸色、墨色正文、朱色审批动作。颜色均为方向探索，不宣称为外部品牌官方色。
- 不加入装饰照片。此产品的内容素材是任务文本、命令与文件改动证据，照片会分散操作注意力。
- Android 框架参考 Huashu 的 `assets/android_frame.jsx`，转为无依赖 HTML/CSS，保留 32px 状态栏、14px 中央摄像孔、24px 手势栏。
