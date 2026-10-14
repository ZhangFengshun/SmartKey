from phone_support import connect_device, ocr_fixture_id, wechat_editor_point
import sys,time,uiautomator2 as u
sys.stdout.reconfigure(encoding='utf-8');d=connect_device();d.adb_device.shell(['input','tap',*wechat_editor_point()]);time.sleep(1)
s=d(description='截图识别');assert s.wait(timeout=6);b=s.info['bounds'];d.adb_device.shell(['input','tap',str((b['left']+b['right'])//2),str((b['top']+b['bottom'])//2)]);time.sleep(.4)
assert d(text='选择聊天截图（最多3张）').exists;assert d.app_current()['package']=='com.tencent.mm'
s=d(description=f'聊天截图 {ocr_fixture_id()}，未选择');assert s.exists;b=s.info['bounds'];d.adb_device.shell(['input','tap',str((b['left']+b['right'])//2),str((b['top']+b['bottom'])//2)])
s=d(description='立即分析');b=s.info['bounds'];d.adb_device.shell(['input','tap',str((b['left']+b['right'])//2),str((b['top']+b['bottom'])//2)])
assert d(description='读懂TA · 分析').wait(timeout=35);assert d.app_current()['package']=='com.tencent.mm'
print('PASS WeChat inline gallery and synthetic OCR preview; host remained WeChat; no send',flush=True)
s=d(description='帮你回');b=s.info['bounds'];d.adb_device.shell(['input','tap',str((b['left']+b['right'])//2),str((b['top']+b['bottom'])//2)])

