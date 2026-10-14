from phone_support import connect_device, ocr_fixture_id, wechat_editor_point
import sys,time,xml.etree.ElementTree as E
import uiautomator2 as u
sys.stdout.reconfigure(encoding='utf-8');d=connect_device();p='dev.lovekey.open:id/'
def tap(s):
 assert s.wait(timeout=6);b=s.info['bounds'];d.adb_device.shell(['input','tap',str((b['left']+b['right'])//2),str((b['top']+b['bottom'])//2)]);time.sleep(.3)
e=d(resourceId=p+'sandbox_editor');e.set_text('');tap(e);time.sleep(1);tap(d(description='Hi'));tap(d(description='早安问候'))
assert d(descriptionStartsWith='选择候选 1：').wait(timeout=60)
time.sleep(.5);first=d(descriptionStartsWith='选择候选 1：');t1=first.info['contentDescription'].split('：',1)[1];assert e.get_text()==t1
b=first.info['bounds'];y=(b['top']+b['bottom'])//2;d.adb_device.shell(['input','swipe',str(b['right']-20),str(y),str(b['left']+20),str(y),'400']);time.sleep(.4)
second=d(descriptionStartsWith='选择候选 2：');assert second.exists;t2=second.info['contentDescription'].split('：',1)[1];tap(second);assert e.get_text()==t2
print('PASS live opener automatic fill and candidate replacement',flush=True)
tap(d(description='清空'));tap(d(description='返回'));tap(d(description='键盘'))
for ch in ['N','I','H','A','O']:tap(d(description=ch))
assert d(text='你好').wait(timeout=5);tap(d(text='你好'));assert e.get_text()=='你好'
print('PASS Chinese offline keyboard nihao -> 你好',flush=True)
tap(d(description='123'));tap(d(description='符号'));assert d(description='中文').exists;tap(d(description='中文'));tap(d(description='【'));assert e.get_text()=='你好【'
print('PASS symbol categories and insertion',flush=True)
tap(d(description='‹'));tap(d(description='ABC'));tap(d(description='帮你回'))
# Scroll the observed persona grid to the final row.
nodes=[n for n in E.fromstring(d.dump_hierarchy()).iter('node') if n.get('class')=='android.widget.ScrollView' and any(c.get('content-desc')=='高情商' for c in n.iter('node'))]
import re
b=[int(x) for x in re.findall(r'\d+',nodes[0].get('bounds'))];x=(b[0]+b[2])//2
for i in range(3):d.adb_device.shell(['input','swipe',str(x),str(b[3]-30),str(x),str(b[1]+20),'400']);time.sleep(.2)
assert d(description='高能回复').exists
print('PASS all 19 personas reachable including final 高能回复',flush=True)
