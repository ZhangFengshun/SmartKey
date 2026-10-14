"""Local-only test configuration; do not hardcode device or media identifiers."""
import os
import uiautomator2 as u2

def connect_device():
    serial = os.environ.get("ANDROID_SERIAL")
    return u2.connect(serial) if serial else u2.connect()

def ocr_fixture_id():
    value = os.environ.get("SMARTKEY_OCR_IMAGE_ID", "")
    if not value.isdecimal():
        raise RuntimeError("Set SMARTKEY_OCR_IMAGE_ID to the MediaStore ID of your synthetic OCR fixture before this test.")
    return value

def wechat_editor_point():
    try:
        return str(int(os.environ["SMARTKEY_WECHAT_INPUT_X"])), str(int(os.environ["SMARTKEY_WECHAT_INPUT_Y"]))
    except (KeyError, ValueError):
        raise RuntimeError("Set SMARTKEY_WECHAT_INPUT_X/Y from your own observed editor bounds before this test.") from None
