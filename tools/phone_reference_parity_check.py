from phone_support import connect_device, ocr_fixture_id, wechat_editor_point
import sys,time,xml.etree.ElementTree as ET
import uiautomator2 as u
sys.stdout.reconfigure(encoding='utf-8')
d=connect_device();p='dev.lovekey.open:id/'
def tap(sel):
 assert sel.wait(timeout=6), 'missing control'
 b=sel.info['bounds'];d.adb_device.shell(['input','tap',str((b['left']+b['right'])//2),str((b['top']+b['bottom'])//2)]);time.sleep(.35)
d.adb_device.shell(['am','start','-n','dev.lovekey.open/.ui.MainActivity','--es','screen','sandbox'])
e=d(resourceId=p+'sandbox_editor');assert e.wait(timeout=8);e.set_text('测试草稿 ABC123');tap(e);tap(d(description='帮你回'))
assert d(description='高情商').exists and d(description='哄女友').exists and d(description='情绪价值').exists
assert d(description='发送回复').exists
before=d(description='消息内容，点此编辑').get_text()
tap(d(description='删除草稿文字'));assert e.get_text()=='测试草稿 ABC12',e.get_text()
tap(d(description='清空'));assert e.get_text() in ('','在这里输入，或自动填入候选回复'),e.get_text();assert d(description='消息内容，点此编辑').get_text()==before
print('PASS host Delete/Clear; source preserved; Send always visible',flush=True)
tap(d(description='Hi'));assert d(text='开场白').exists
assert d(description='晚安好梦').exists and d(description='早安问候').exists
print('PASS dedicated opener panel',flush=True)
tap(d(description='返回'));tap(d(description='工具'))
assert all(d(description=x).exists for x in ['切换输入法','人设市场','我的人设','键盘皮肤','定制聊天人设'])
assert not d(description='L+会员').exists
print('PASS five More entries, no paid feature',flush=True)
tap(d(description='返回'));e.set_text('老板，项目可能需要多一天，我会今晚提交进度。');tap(d(description='超会说'));assert d(description='编辑待优化文字').get_text()=='老板，项目可能需要多一天，我会今晚提交进度。'
tap(d(description='关系：通用'));assert all(d(description=x).exists for x in ['通用','心动对象','女朋友','妻子','好友','朋友','上司','客户','同事','家人','男朋友','丈夫'])
tap(d(description='上司'));assert d(description='关系：上司').exists
print('PASS rewrite reads host draft; 12 relationships; selected 上司',flush=True)
tap(d(description='优化'))
for i in range(60):
 time.sleep(1)
 if e.get_text()!='老板，项目可能需要多一天，我会今晚提交进度。':break
assert e.get_text()!='老板，项目可能需要多一天，我会今晚提交进度。', 'rewrite did not replace'
assert e.get_text().count('老板，项目可能需要多一天')<=1
print('PASS live model rewrite replaces full draft',flush=True)
tap(d(description='关闭超会说'));tap(d(description='截图识别'));assert d(text='选择聊天截图（最多3张）').exists
print('PASS gallery opens inside keyboard, foreground='+d.app_current()['package'],flush=True)
