from phone_support import connect_device, ocr_fixture_id, wechat_editor_point
import sys,time,xml.etree.ElementTree as E
import uiautomator2 as u
sys.stdout.reconfigure(encoding='utf-8');d=connect_device();p='dev.lovekey.open:id/'
def tap(s):
 assert s.wait(timeout=5);b=s.info['bounds'];d.adb_device.shell(['input','tap',str((b['left']+b['right'])//2),str((b['top']+b['bottom'])//2)]);time.sleep(.35)
photos=[n.get('content-desc') for n in E.fromstring(d.dump_hierarchy()).iter('node') if n.get('content-desc','').startswith('聊天截图 ')];assert len(photos)>=1
assert not d(description='立即分析').info['enabled']
tap(d(description=f'聊天截图 {ocr_fixture_id()}，未选择'));assert d(description=f'聊天截图 {ocr_fixture_id()}，已选择第1张').exists
assert d(description='立即分析').info['enabled']
e=d(resourceId=p+'sandbox_editor');before=e.get_text()
tap(d(description='立即分析'));assert d(description='读懂TA · 分析').wait(timeout=35)
assert '项目顺利完成' in d(textContains='截图1：').get_text()
assert e.get_text()==before
print('PASS inline local Chinese OCR; draft untouched',flush=True)
tap(d(description='读懂TA · 分析'));assert d(description='根据截图帮你回').wait(timeout=65)
assert e.get_text()==before
print('PASS live text analysis displayed in keyboard; draft untouched',flush=True)
tap(d(description='根据截图帮你回'));tap(d(description='高情商'))
assert d(descriptionStartsWith='选择候选 1：').wait(timeout=65)
first=d(descriptionStartsWith='选择候选 1：').info['contentDescription'].split('：',1)[1];time.sleep(.5);assert e.get_text()==before+first or e.get_text()==first
print('PASS screenshot-based live reply automatically inserted',flush=True)
tap(d(description='发送回复'));assert e.get_text() in ('','在这里输入，或自动填入候选回复')
tap(d(description='⌄'));assert d(resourceId=p+'sandbox_send_status',text='已完成本地试用发送，没有向任何人发消息').wait(timeout=5)
print('PASS native Send only after tap, local-only test',flush=True)
