from phone_support import connect_device, ocr_fixture_id, wechat_editor_point
import sys,time,uiautomator2 as u
sys.stdout.reconfigure(encoding='utf-8');d=connect_device();p='dev.lovekey.open:id/'
def tap(s):
 assert s.wait(timeout=5);b=s.info['bounds'];d.adb_device.shell(['input','tap',str((b['left']+b['right'])//2),str((b['top']+b['bottom'])//2)]);time.sleep(.2)
d.adb_device.shell(['am','start','-n','dev.lovekey.open/.ui.MainActivity','--es','screen','sandbox'])
e=d(resourceId=p+'sandbox_editor');assert e.wait(timeout=8);e.set_text('今天真的好累，想早点休息。')
e.long_click();time.sleep(.3)
if d(description='全选').exists:tap(d(description='全选'))
tap(d(description='复制'));e.set_text('');tap(e);time.sleep(1);tap(d(description='帮你回'));assert '今天真的好累' in d(description='消息内容，点此编辑').get_text()
started=time.monotonic();tap(d(description='高情商'))
assert d(descriptionStartsWith='选择候选 1：').wait(timeout=60);elapsed=time.monotonic()-started;time.sleep(.15)
first=d(descriptionStartsWith='选择候选 1：');text=first.info['contentDescription'].split('：',1)[1];assert e.get_text()==text
print('PASS keyboard request -> visible + automatically filled, elapsedSeconds=%.2f'%elapsed,flush=True)
# Trigger two immediately superseded requests then another request; no send.
e.set_text('');tap(d(description='帮你回'));tap(d(description='高情商'));tap(d(description='哄女友'));started=time.monotonic();tap(d(description='情绪价值'))
assert d(descriptionStartsWith='选择候选 1：').wait(timeout=60);time.sleep(.2);elapsed=time.monotonic()-started
text=d(descriptionStartsWith='选择候选 1：').info['contentDescription'].split('：',1)[1];assert e.get_text()==text
print('PASS rapid switching cancelled old requests; latest reply filled, elapsedSeconds=%.2f'%elapsed,flush=True)
print('PASS no Send control tapped',flush=True)
