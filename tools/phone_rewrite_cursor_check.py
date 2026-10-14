from phone_support import connect_device, ocr_fixture_id, wechat_editor_point
import sys,time,uiautomator2 as u
sys.stdout.reconfigure(encoding='utf-8');d=connect_device();e=d(resourceId='dev.lovekey.open:id/sandbox_editor')
def tap(s):
 assert s.wait(timeout=5);b=s.info['bounds'];d.adb_device.shell(['input','tap',str((b['left']+b['right'])//2),str((b['top']+b['bottom'])//2)]);time.sleep(.4)
text='老板我这项目写不完，明天给你吧。';e.set_text(text);d.adb_device.shell(['input','keyevent','21']);d.adb_device.shell(['input','keyevent','21']);tap(d(description='超会说'));assert d(description='编辑待优化文字').get_text()==text;tap(d(description='优化'))
assert d(descriptionStartsWith='选择候选 1：').wait(timeout=65);time.sleep(.5);candidate=d(descriptionStartsWith='选择候选 1：').info['contentDescription'].split('：',1)[1]
assert e.get_text()==candidate,(len(e.get_text()),len(candidate))
print('PASS rewrite with cursor inside draft replaced whole draft, no duplication',flush=True)
