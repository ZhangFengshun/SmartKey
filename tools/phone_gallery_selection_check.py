from phone_support import connect_device, ocr_fixture_id, wechat_editor_point
import sys,time,xml.etree.ElementTree as E,uiautomator2 as u
sys.stdout.reconfigure(encoding='utf-8');d=connect_device()
def tap(s):
 assert s.wait(timeout=5);b=s.info['bounds'];d.adb_device.shell(['input','tap',str((b['left']+b['right'])//2),str((b['top']+b['bottom'])//2)]);time.sleep(.3)
tap(d(description='截图识别'))
photos=[n.get('content-desc') for n in E.fromstring(d.dump_hierarchy()).iter('node') if n.get('content-desc','').startswith('聊天截图 ')];assert len(photos)>=3
for index,name in enumerate(reversed(photos[:3])):
 tap(d(description=name));assert d(description=name.replace('未选择','已选择第'+str(index+1)+'张')).exists
assert d(text='已选3张 · 按编号顺序分析').exists
# Selection test only: cancel without recognizing or sending any private image.
tap(d(description='关闭聊天截图选择'));assert d.app_current()['package']=='com.tencent.mm'
print('PASS three ordered selections, dynamic count, close remained WeChat; no analysis/send',flush=True)

