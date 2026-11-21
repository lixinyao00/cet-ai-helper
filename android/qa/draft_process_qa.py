#!/usr/bin/env python3
"""0.8.0 cross-process draft, reconnect, account-isolation and 401 regression.

This is a device-level regression for the parts that a rotation or an
ActivityScenario.recreate() cannot prove. Every check crosses a real
`am force-stop` boundary and waits for the old PID to disappear.

Prerequisites (all on an isolated AVD, never a shared device or a phone):
  * demo backend on DRAFT_QA_SERVER_URL (default http://10.0.2.2:18088)
  * the debug app APK built from this checkout
  * one river-passage.jpg pushed by this script as the only QA photo
  * the app may have any previous data; the script clears only its own package

Run from the repository root:
  python3 android/qa/draft_process_qa.py

Optional environment:
  ADB                     adb executable
  DRAFT_QA_SERIAL         adb serial; REQUIRED (must start with emulator-)
  DRAFT_QA_DEVICE_MARKER  marker text; REQUIRED, pre-written on the isolated
                          AVD at /sdcard/CetDraftQa/device-marker
  DRAFT_QA_SERVER_URL     URL used by the app (default http://10.0.2.2:18088)
  DRAFT_QA_SERVER_LOCAL   backend URL from the host (default 127.0.0.1:18088)
  DRAFT_QA_APK            debug APK path
  DRAFT_QA_EVIDENCE       screenshot/XML output directory
  DRAFT_QA_DECLINE_TAP    "x y" tap for the second decline in the Pixel 6
                          1080x2400 layout (default "590 885")

The script refuses to run unless the serial is an emulator and the marker file
matches. That guard prevents `pm clear` from touching a shared emulator or a
physical phone when the default serial is wrong.
"""
import json
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib import request

ROOT = Path(__file__).resolve().parents[2]
ADB = os.environ.get("ADB", str(ROOT / "android/.local/sdk/platform-tools/adb"))
SERIAL = os.environ.get("DRAFT_QA_SERIAL", "")
MARKER = os.environ.get("DRAFT_QA_DEVICE_MARKER", "")
APP = "com.cethelper.android"
URL = os.environ.get("DRAFT_QA_SERVER_URL", "http://10.0.2.2:18088")
LOCAL = os.environ.get("DRAFT_QA_SERVER_LOCAL", "http://127.0.0.1:18088")
APK = os.environ.get("DRAFT_QA_APK", str(ROOT / "android/app/build/outputs/apk/debug/app-debug.apk"))
PHOTO = ROOT / "android/qa/river-passage.jpg"
PASSWORD = os.environ.get("DRAFT_QA_PASSWORD", "DraftProcessQa926")
DECLINE_TAP = tuple(int(value) for value in os.environ.get("DRAFT_QA_DECLINE_TAP", "590 885").split())

STAMP = time.strftime("%m%d%H%M%S")
ACCOUNT_A = "dq" + STAMP
ACCOUNT_B = "dq" + STAMP + "b"
ARTICLE = "DraftQA" + STAMP
PROMPT = "Community libraries " + STAMP
ESSAY = "The writers visit libraries every week. " + STAMP
DRAFT = "My translation about the digital economy. " + STAMP
# `adb shell input text` cannot inject non-ASCII. These fresh ASCII materials
# still go through the normal source field; unlike the demo button they do not
# call replace(), so a leaked translation draft would remain visible.
ISOLATION_SOURCE = "Account B isolation source " + STAMP
CLEARED_SOURCE = "Cleared draft source " + STAMP
EVIDENCE = Path(os.environ.get("DRAFT_QA_EVIDENCE",
                               str(ROOT / "android/.local/qa-0.8" / ("process-" + STAMP))))


def adb(*args, check=True):
    return subprocess.run([ADB, "-s", SERIAL, *map(str, args)], check=check,
                          capture_output=True)


def shell(*args, check=True):
    return adb("shell", *args, check=check).stdout


def tree():
    content = adb("exec-out", "uiautomator", "dump", "/dev/tty").stdout
    start = content.index(b"<?xml")
    end = content.rindex(b"</hierarchy>") + len(b"</hierarchy>")
    return ET.fromstring(content[start:end])


def nodes():
    return list(tree().iter("node"))


def optional(key, expected=None, timeout=6, exact=False):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        try:
            for node in nodes():
                rid = node.get("resource-id", "")
                text = node.get("text", "")
                if (key is None or rid in (key, APP + ":id/" + key)) and (
                        expected is None or (text == expected if exact else expected in text)):
                    return node
        except (ValueError, ET.ParseError, subprocess.CalledProcessError):
            pass
    return None


def by(key, expected=None, timeout=18, exact=False):
    end = time.monotonic() + timeout
    last = []
    while time.monotonic() < end:
        try:
            last = [(n, n.get("resource-id", ""), n.get("text", "")) for n in nodes()]
        except (ValueError, ET.ParseError, subprocess.CalledProcessError):
            continue
        for node, rid, text in last:
            if (key is None or rid in (key, APP + ":id/" + key)) and (
                    expected is None or (text == expected if exact else expected in text)):
                return node
        time.sleep(0.25)
    diagnostic = [(rid, text) for node, rid, text in last if node.get("password") != "true"]
    raise AssertionError((key, expected, diagnostic))


def tap(node):
    left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds")))
    shell("input", "tap", (left + right) // 2, (top + bottom) // 2)


def click(key, expected=None, timeout=18, exact=False):
    tap(by(key, expected, timeout, exact))


def type_into(key, text):
    click(key)
    shell("input", "keycombination", "KEYCODE_CTRL_LEFT", "KEYCODE_A")
    if text:
        shell("input", "text", text.replace(" ", "%s"))
    else:
        shell("input", "keyevent", "KEYCODE_DEL")


def back():
    shell("input", "keyevent", "KEYCODE_BACK")


def assert_text(key, expected, exact=True):
    node = by(key)
    actual = node.get("text", "")
    if exact:
        assert actual == expected, (key, expected, actual)
    else:
        assert expected in actual, (key, expected, actual)


def assert_blank(key):
    node = by(key)
    actual = node.get("text", "")
    assert actual == "", (key, actual)


def wait_gone(key, timeout=45):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if optional(key, timeout=0.5) is None:
            return
    raise AssertionError(("still visible", key))


def cold_start(expect_account, offline=False):
    shell("am", "force-stop", APP)
    pid = shell("pidof", APP, check=False).decode().strip()
    assert pid == "", ("old process survived force-stop", pid)
    shell("am", "start", "-n", APP + "/.MainActivity")
    by("current_student", expect_account, timeout=42, exact=True)
    if offline:
        by("session_banner", "离线", timeout=12)
    print("PASS force-stop removed PID; cold start restored", expect_account, flush=True)


def cold_start_login():
    shell("am", "force-stop", APP)
    pid = shell("pidof", APP, check=False).decode().strip()
    assert pid == "", ("old process survived force-stop", pid)
    shell("am", "start", "-n", APP + "/.MainActivity")
    by("login", timeout=42)
    assert optional("current_student", timeout=3) is None


def network(online):
    shell("cmd", "connectivity", "airplane-mode", "disable" if online else "enable")
    radio = "enable" if online else "disable"
    shell("svc", "wifi", radio)
    shell("svc", "data", radio)
    time.sleep(1)


def check_device():
    if not __debug__:
        raise RuntimeError("refusing to run with Python -O: device guard asserts are removed")
    if not SERIAL.startswith("emulator-"):
        raise RuntimeError("DRAFT_QA_SERIAL must be an emulator serial: " + repr(SERIAL))
    if not MARKER:
        raise RuntimeError("DRAFT_QA_DEVICE_MARKER is required")
    qemu = shell("getprop", "ro.kernel.qemu").decode().strip()
    if qemu != "1":
        raise RuntimeError("target is not an emulator: ro.kernel.qemu=" + repr(qemu))
    marker = shell("cat", "/sdcard/CetDraftQa/device-marker", check=False).decode().strip()
    if marker != MARKER:
        raise RuntimeError("device marker mismatch: " + repr(marker) + " != " + repr(MARKER))
    print("PASS isolated emulator marker", SERIAL, flush=True)


def capture(name):
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    (EVIDENCE / (name + ".png")).write_bytes(adb("exec-out", "screencap", "-p").stdout)
    (EVIDENCE / (name + ".xml")).write_bytes(adb("exec-out", "uiautomator", "dump", "/dev/tty").stdout)


def register(account):
    type_into("username", account)
    type_into("password", PASSWORD)
    click("register")
    by("current_student", account, timeout=30, exact=True)


def login(account):
    type_into("username", account)
    type_into("password", PASSWORD)
    click("login")
    by("current_student", account, timeout=30, exact=True)


def student_id():
    raw = shell("run-as", APP, "cat", "shared_prefs/session.xml")
    verified = ET.fromstring(raw).find("./string[@name='verified_session']").text
    return json.loads(verified)["id"]


def draft_value(name):
    raw = shell("run-as", APP, "cat", "shared_prefs/practice_drafts.xml")
    wanted = str(student_id()) + "|" + URL + "|" + name
    for node in ET.fromstring(raw).findall("string"):
        if node.get("name") == wanted:
            return node.text or ""
    return ""


def replace_dialog():
    """Accept a pending replace/clear confirmation; no-op when absent."""
    if optional("android:id/alertTitle", timeout=2) is not None:
        for label in ("替换", "清空"):
            if optional(None, label, timeout=1, exact=True) is not None:
                click(None, label, exact=True)
                return


def save_drafts():
    click("open_writing")
    type_into("writing_prompt", PROMPT)
    back()
    type_into("writing_essay", ESSAY)
    back()
    click("writing_back")
    click("open_translation")
    click("translation_demo")
    replace_dialog()
    assert "数字经济" in by("translation_source_input").get("text", "")
    click("translation_start")
    type_into("translation_draft", DRAFT)
    back()
    by("translation_draft")
    print("PASS both drafts written through the real UI", flush=True)


def verify_drafts(account, offline=False):
    cold_start(account, offline)
    click("open_translation")
    assert "数字经济" in by("translation_source_input").get("text", "")
    click("translation_start")
    assert_text("translation_draft", DRAFT)
    if offline:
        by("session_banner", "离线")
    capture("01-translation-draft-cold-start")
    click("translation_edit_source")
    click("translation_back")
    click("open_writing")
    assert_text("writing_prompt", PROMPT)
    assert_text("writing_essay", ESSAY)
    capture("02-writing-draft-cold-start")
    print("PASS cold restart kept translation source/draft and writing prompt/essay", flush=True)


def main():
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    print("evidence:", EVIDENCE, flush=True)
    print("accounts:", ACCOUNT_A, ACCOUNT_B, flush=True)
    adb("install", "-r", APK)
    shell("pm", "clear", APP)
    shell("mkdir", "-p", "/sdcard/Pictures/CetDraftQa")
    adb("push", PHOTO, "/sdcard/Pictures/CetDraftQa/river-passage.jpg")
    shell("am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
          "-d", "file:///sdcard/Pictures/CetDraftQa/river-passage.jpg")
    network(True)
    shell("am", "start", "-n", APP + "/.MainActivity")

    click("server_settings")
    type_into("server_url", URL)
    click("android:id/button1")
    by("login_status", "服务端地址已保存")
    register(ACCOUNT_A)
    print("PASS registered account A", flush=True)

    # Account-owned article and favorite: both must survive process death and
    # must be invisible to account B.
    click("pick_import")
    photo = by("com.google.android.providers.media.module:id/icon_thumbnail", timeout=30)
    tap(photo)
    body = by("import_body", timeout=30)
    assert "river" in body.get("text", "").lower(), body.get("text")
    type_into("import_title", ARTICLE)
    back()
    click("import_confirm")
    by("reader_title", ARTICLE)
    click("reader_back")
    by(None, ARTICLE, timeout=12)
    click("open_article")
    by("article_paragraph", "Some students decline the invitation.", timeout=12)
    time.sleep(0.5)
    shell("input", "tap", *DECLINE_TAP)
    by("panel_selection", "decline")
    click("panel_action", "收藏")
    click("reader_vocabulary")
    by(None, "Some students decline the invitation.")
    click("vocabulary_back")
    click("reader_back")
    print("PASS account A created imported article and favorite", flush=True)

    save_drafts()
    before = shell("run-as", APP, "cat", "shared_prefs/practice_drafts.xml").decode()
    key = str(student_id()) + "|" + URL + "|"
    assert key in before and PROMPT in before and DRAFT in before, before
    print("PASS drafts use the student-id|server key and are on disk", flush=True)
    verify_drafts(ACCOUNT_A)

    # A second process death covers the article/favorite side of the upgrade.
    cold_start(ACCOUNT_A)
    click(None, ARTICLE, timeout=12)
    by("reader_title", ARTICLE)
    by("article_paragraph", "river", exact=False)
    click("reader_back")
    click("open_article")
    by("article_paragraph", "Some students decline the invitation.")
    click("reader_back")
    click("open_vocabulary")
    by(None, "Some students decline the invitation.")
    click("vocabulary_back")
    print("PASS cold restart kept imported article and favorite", flush=True)

    # Offline cold start, then reconnect while a draft is open. Re-verification
    # must not navigate away or replace the text being edited.
    network(False)
    verify_drafts(ACCOUNT_A, offline=True)
    network(True)
    wait_gone("session_banner", timeout=50)
    assert_text("writing_prompt", PROMPT)
    assert_text("writing_essay", ESSAY)
    click("writing_back")
    click("open_translation")
    assert "数字经济" in by("translation_source_input").get("text", "")
    click("translation_start")
    assert_text("translation_draft", DRAFT)
    capture("03-drafts-after-reconnect")
    print("PASS reconnect re-verified identity without losing either draft", flush=True)

    # Second account sees none of A's local data, while A's data stays intact
    # on disk for the switch back.
    click("translation_edit_source")
    click("translation_back")
    click("logout")
    by("login_status", "已退出登录")
    register(ACCOUNT_B)
    assert optional(None, ARTICLE, timeout=3) is None
    click("open_writing")
    assert_blank("writing_prompt")
    assert_blank("writing_essay")
    click("writing_back")
    # Check B's own persisted scope before adding any source, then use a new
    # non-demo source so start() does not replace/clear the draft.
    assert draft_value("translation.source") == ""
    assert draft_value("translation.draft") == ""
    click("open_translation")
    assert_blank("translation_source_input")
    type_into("translation_source_input", ISOLATION_SOURCE)
    click("translation_start")
    assert_text("translation_original", ISOLATION_SOURCE, exact=True)
    assert_blank("translation_draft")
    capture("05b-account-b-empty-translation-draft")
    click("translation_edit_source")
    click("translation_back")
    click("open_vocabulary")
    by("vocabulary_status", "还没有收藏")
    click("vocabulary_back")
    capture("05-second-account-isolated")
    print("PASS account B cannot see A article, favorite or either draft", flush=True)

    # Switch back to A: both drafts must still be there before the clear test.
    click("logout")
    by("login_status", "已退出登录")
    login(ACCOUNT_A)
    click("open_writing")
    assert_text("writing_prompt", PROMPT)
    assert_text("writing_essay", ESSAY)
    click("writing_back")
    click("open_translation")
    assert "数字经济" in by("translation_source_input").get("text", "")
    click("translation_start")
    assert_text("translation_draft", DRAFT)
    capture("06-account-a-drafts-restored")
    print("PASS switching back to A restored both drafts after B", flush=True)

    # Explicit clear must stay cleared after another real process restart.
    click("translation_edit_source")
    click("translation_clear")
    replace_dialog()
    assert_blank("translation_source_input")
    click("translation_back")
    click("open_writing")
    click("writing_clear")
    replace_dialog()
    assert_blank("writing_prompt")
    assert_blank("writing_essay")
    click("writing_back")
    cold_start(ACCOUNT_A)
    click("open_writing")
    assert_blank("writing_prompt")
    assert_blank("writing_essay")
    click("writing_back")
    click("open_translation")
    assert_blank("translation_source_input")
    assert draft_value("translation.source") == ""
    assert draft_value("translation.draft") == ""
    # Reveal the actual answer page with a new non-demo source. start() does
    # not replace the draft, so this also confirms the English draft is empty.
    type_into("translation_source_input", CLEARED_SOURCE)
    click("translation_start")
    assert_text("translation_original", CLEARED_SOURCE, exact=True)
    assert_blank("translation_draft")
    capture("07-cleared-after-cold-start")
    print("PASS explicit clear did not resurrect after process restart", flush=True)

    # A server 401 after an offline start must clear the cached identity.
    raw = shell("run-as", APP, "cat", "shared_prefs/session.xml")
    token = ET.fromstring(raw).find("./string[@name='token']").text
    network(False)
    cold_start(ACCOUNT_A, offline=True)
    call = request.Request(LOCAL + "/api/v1/auth/logout", method="POST",
                           headers={"Authorization": "Bearer " + token})
    with request.urlopen(call, timeout=10) as response:
        assert response.status == 204, response.status
    network(True)
    by("login_status", "会话已失效，请重新登录", timeout=50)
    capture("08-revoked-session")
    cold_start_login()
    print("PASS re-verification honored server 401 and cleared the session", flush=True)
    print("ALL PASS", flush=True)


if __name__ == "__main__":
    check_device()
    try:
        main()
    except Exception:
        try:
            capture("failure")
        finally:
            network(True)
        raise
