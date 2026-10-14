from phone_support import connect_device, ocr_fixture_id, wechat_editor_point
import sys,time,uiautomator2 as u
sys.stdout.reconfigure(encoding='utf-8');d=connect_device();p='dev.lovekey.open:id/'
def tap(s):
 assert s.wait(timeout=6);b=s.info['bounds'];d.adb_device.shell(['input','tap',str((b['left']+b['right'])//2),str((b['top']+b['bottom'])//2)]);time.sleep(.3)
assert d.app_info('dev.lovekey.open')['versionName']=='0.1.7'
assert d.adb_device.shell(['settings','get','secure','default_input_method']).strip()=='dev.lovekey.open/.ime.LoveKeyImeService'
d.adb_device.shell(['am','start','-n','dev.lovekey.open/.ui.MainActivity','--es','screen','sandbox'])
e=d(resourceId=p+'sandbox_editor');assert e.wait(timeout=8);e.set_text('');tap(e);time.sleep(1);tap(d(description='键盘'))
for ch in ['N','I','H','A','O']:tap(d(description=ch))
assert d(text='你好').wait(timeout=6);tap(d(text='你好'));assert e.get_text()=='你好'
print('PASS 0.1.7 upgrade kept selected keyboard; SmartKey native library nihao -> 你好',flush=True)
tap(d(description='⌄'))
d.adb_device.shell(['am','start','-n','dev.lovekey.open/.ui.MainActivity','--es','screen','models']);time.sleep(.6)
assert d(resourceId=p+'api_key').wait(timeout=6)
assert bool(d(resourceId=p+'api_key').get_text())
print('PASS saved credential still present after upgrade; credential not logged',flush=True)
