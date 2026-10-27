from pathlib import Path
import base64
from playwright.sync_api import sync_playwright

root=Path(__file__).parent
logo='data:image/png;base64,'+base64.b64encode((root.parent/'icon.png').read_bytes()).decode()
cards=[
 ('A 静默协作','深泽直人的使用秩序','selected a','#e8ebef','#ffffff','#2b3351','#596cd4','conversation'),
 ('信息刻度','佐藤卓的信息整理','d','#e8e7e0','#fafaf5','#232628','#e4dc43','status'),
 ('随行审阅','GitHub Mobile 的代码审阅','r','#dfeae3','#ffffff','#27352d','#25734a','files'),
 ('B 任务信号','Termius 的远程操作层次','selected b','#15181d','#21262e','#f1f3f6','#eac66c','events'),
 ('原生清单','App #10 原生精修','n','#e1e7ee','#ffffff','#242e3c','#2858d8','list'),
 ('C 轻快接力','east #10 信息块的尺度与错位','selected c','#dce6ff','#eef2ff','#202845','#dce58b','handoff')
]
cells=[]
for title,ref,cl,bg,paper,ink,accent,kind in cards:
  head='<div class="mini-top"><span>☰</span><b>leoagent</b><span>•••</span></div>'
  if kind=='conversation':
    content='<small>MacBook Pro 已连接</small><h2>修复登录校验</h2><div class="user">修复输入校验，并运行测试。</div><p>已补齐邮箱格式和空密码校验，也加上了对应的测试。</p><div class="line">2 个文件已修改 <b>+28 −4</b></div><div class="approval"><b>运行测试前，等你批准</b><code>./gradlew testDebugUnitTest</code><div class="buttons"><span>拒绝</span><strong>允许一次</strong></div></div>'
  elif kind=='status':
    content='<div class="big">等你<br>批准。</div><div class="line">leoagent / android</div><div class="metric"><b>已修改</b><strong>2 个文件</strong></div><p>下一步 · 运行测试</p><code>./gradlew testDebugUnitTest</code><div class="buttons"><span>拒绝</span><strong>允许一次</strong></div>'
  elif kind=='files':
    content='<small>本轮任务</small><h2>审阅改动</h2><div class="file">LoginForm.kt <b>+18 −4</b></div><pre>+ validateEmail(email)<br>+ password.isNotBlank()</pre><div class="file">LoginFormTest.kt <b>+10</b></div><p>运行测试前需要你的批准。</p><div class="buttons"><span>拒绝</span><strong>批准测试</strong></div>'
  elif kind=='events':
    content='<small>MacBook Pro / 已连接</small><h2>修复登录校验</h2><div class="timeline"><div>✓ 阅读登录逻辑</div><div>✓ 修改 2 个文件</div><div class="active">● 等待你的批准</div></div><div class="approval"><b>下一步：运行测试</b><code>./gradlew testDebugUnitTest</code><div class="buttons"><span>拒绝</span><strong>允许一次</strong></div></div>'
  elif kind=='list':
    content='<small>项目</small><h2>leoagent</h2><div class="item"><b>修复登录校验</b><small>等待审批 · 刚刚</small></div><div class="item"><b>完善深色主题</b><small>已完成</small></div><div class="item"><b>检查连接恢复</b><small>结果待核对</small></div><div class="create">新建对话 ＋</div>'
  else:
    content='<div class="big">等你批准</div><div class="summary"><b>修复登录校验</b><p>2 个文件已修改<br>邮箱格式 · 空密码校验</p></div><div class="approval"><b>接下来，运行测试。</b><code>./gradlew testDebugUnitTest</code><div class="buttons"><span>拒绝</span><strong>允许一次</strong></div></div>'
  cells.append(f'<article class="{cl}" style="--bg:{bg};--paper:{paper};--ink:{ink};--accent:{accent}"><div class="card-title"><h3>{title}</h3><p>{ref}</p><span>{"展开为完整版" if "selected" in cl else "备选"}</span></div><div class="mini"><div class="sys">9:41 <span>··· ▰</span></div>{head}<section>{content}</section><div class="compose">给电脑上的 Agent 发消息 <b>↑</b></div></div></article>')
html='''<!doctype html><html lang="zh-CN"><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>LeoAgent 六方向探索</title><style>
*{box-sizing:border-box}body{margin:0;background:#f5f6f8;color:#222a3a;font:16px/1.6 -apple-system,"PingFang SC",sans-serif}header{padding:30px 38px 24px;display:flex;align-items:center;gap:16px}header img{width:40px;height:40px;border-radius:10px}h1{font-size:26px;margin:0;letter-spacing:-1px}header p{margin:0;color:#596477;font-size:14px}.grid{padding:0 28px 30px;display:grid;grid-template-columns:repeat(3,1fr);gap:18px}article{height:378px;position:relative;overflow:hidden;background:var(--bg);border-radius:12px;border:1px solid #0001;color:var(--ink)}.card-title{position:absolute;left:22px;top:25px;width:180px}h3{font-size:21px;margin:0 0 8px}.card-title p{font-size:13px;margin:0;max-width:145px;opacity:.8}.card-title>span{font-size:12px;display:inline-block;margin-top:20px;border:1px solid currentColor;padding:3px 9px;border-radius:20px}.mini{position:absolute;right:20px;top:24px;width:390px;height:750px;background:var(--paper);border:7px solid #2c2c30;border-radius:33px;transform:scale(.48);transform-origin:top right;overflow:hidden;box-shadow:0 20px 50px #0002;font-size:16px}.sys{display:flex;justify-content:space-between;padding:10px 22px;font-size:14px}.mini-top{display:flex;justify-content:space-between;align-items:center;padding:6px 18px 15px;border-bottom:1px solid #8883}section{padding:22px}h2{font-size:28px;margin:6px 0 20px;letter-spacing:-1px}small{font-size:13px}p{margin:12px 0;line-height:1.8}.user{background:#7771;padding:14px;border-radius:14px;font-size:16px}.line{border-block:1px solid #8884;padding:16px 0;display:flex;justify-content:space-between;font-size:14px;margin:18px 0}.approval{background:color-mix(in srgb,var(--accent) 12%,var(--paper));padding:18px;border-radius:16px;margin-top:20px}code{font:14px/1.8 monospace;display:block;overflow-wrap:anywhere;margin:16px 0}.buttons{display:flex;gap:10px;margin-top:20px;align-items:center}.buttons>*{padding:13px 20px;border-radius:10px;flex:1;text-align:center;white-space:nowrap;font-size:16px;background:#8882}.buttons strong{background:var(--accent);color:#212638}.a .buttons strong,.r .buttons strong,.n .buttons strong{color:#fff}.compose{position:absolute;bottom:20px;left:16px;right:16px;border:1px solid #8884;border-radius:18px;padding:20px 16px;font-size:15px;display:flex;justify-content:space-between;color:var(--ink)}.big{font-size:44px;font-weight:750;line-height:1.15;letter-spacing:-2px;margin:15px 0 25px}.metric{display:flex;justify-content:space-between;font-size:18px;padding-block:12px}.timeline{margin:28px 0;border-left:1px solid #75808d;padding-left:18px}.timeline>div{padding:13px 0;font-size:16px;color:#b7c1ce}.timeline .active{color:var(--accent)}.b .approval{border:1px solid #eac66c55}.b .card-title p{color:#bdc7d4}.b{color:#f1f3f6}.file{padding:18px 0;display:flex;justify-content:space-between;font-size:16px;border-bottom:1px solid #8884}pre{font:14px/1.9 monospace;background:#e6f3e9;padding:12px;margin:12px -8px}.item{padding:20px 0;border-bottom:1px solid #8884}.item small{display:block;margin-top:8px}.create{margin-top:24px;color:var(--accent)}.summary{background:#c7d4fc;padding:18px;border-radius:20px;font-size:19px}.summary p{font-size:15px}.c .approval{background:var(--accent);border-radius:5px 22px 22px 22px}.c .buttons strong{background:#202845;color:white}.c .buttons span{background:#fff6}.c .compose{background:#202845;color:white}.c .big{font-size:39px}.d .approval{border-radius:0}.d .buttons strong{background:#232628;color:#eeea7e}.d section{padding-top:10px}footer{padding:0 38px 26px;font-size:13px;color:#596477}@media(max-width:800px){.grid{grid-template-columns:1fr}.mini{right:30px}.card-title{left:28px}header{padding:25px}}
</style><header><img src="LOGO" alt="LeoAgent"><div><h1>LeoAgent / 六方向探索</h1><p>同一任务，六种信息组织。A、B、C 展开为可操作的四屏原型。</p></div></header><main class="grid">CELLS</main><footer>离线交互原型探索 · 所有任务与设备状态均为界面样例 · 2026.10.26</footer></html>'''.replace('LOGO',logo).replace('CELLS',''.join(cells))
(root/'design-demos/thumbs.html').write_text(html)
with sync_playwright() as p:
  browser=p.chromium.launch()
  page=browser.new_page(viewport={'width':1440,'height':940},device_scale_factor=1)
  page.goto((root/'design-demos/thumbs.html').resolve().as_uri())
  page.screenshot(path=str(root/'design-demos/thumbs.png'),full_page=True)
  browser.close()
