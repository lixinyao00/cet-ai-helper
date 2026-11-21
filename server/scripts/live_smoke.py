#!/usr/bin/env python3
"""四六级 AI 学习助手 live 冒烟（单文件、可重复运行）。

对已启动的 live 服务（真实凭据只在服务进程里，脚本接触不到）：

    python3 scripts/live_smoke.py --base-url http://127.0.0.1:8080

没有真实凭据时，用内置的固定响应替身 + 临时数据库 + 环回端口自动跑通：

    python3 scripts/live_smoke.py --stand-in

入口只做核心输出契约检查：HTTP 200、mode=live、关键字段、逐字引用、枚举与下标。
解释/批改/判答是否教得好属于人工判断，单独打印，不影响退出码。
替身只验证 live 路径的结构，不能当作真实模型验证。
"""

from __future__ import annotations

import argparse
import contextlib
import json
import os
import random
import re
import shutil
import socket
import string
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

BASE_URL = "http://127.0.0.1:8080"
SERVER_DIR = Path(__file__).resolve().parent.parent
JAR = SERVER_DIR / "target" / "cet-server-0.1.0.jar"
PASSWORD = "Passw0rd!23"
LOOPBACK = {"127.0.0.1", "localhost", "::1"}

# --- 一组原创测试材料（区别于 demo 内置的 decline / CET-6 / 数字经济 / 志愿服务） ---
READING_SENTENCE = (
    "The museum extended its opening hours after the volunteer guides "
    "noticed the growing number of visitors."
)
READING_WORD = "extended"
ANALYSIS_SENTENCE = (
    "Because the bridge had been closed for repairs, commuters who usually "
    "drove to work began taking the ferry across the river."
)
ANALYSIS_CHUNKS = (
    "Because the bridge had been closed for repairs",
    "commuters who usually drove to work",
    "began taking the ferry across the river",
)
SOURCE = "随着社区图书馆延长开放时间，越来越多的退休居民愿意在晚饭后步行到这里阅读报刊。"
DRAFT = ("As libraries extend their opening hours, more retired residents are "
         "willing to walk here after dinner to read newspapers.")
IMPROVED = ("As community libraries extend their opening hours, a growing number of "
            "retired residents are willing to walk here after dinner to read "
            "newspapers and magazines.")
ALTERNATIVE = ("With community libraries staying open later, more and more retired "
               "residents choose to walk to the local library after dinner to read "
               "newspapers and magazines.")
WRITING_PROMPT = ("Some people think schools should teach practical skills such as "
                  "cooking and basic home repair. Write an essay explaining whether "
                  "you agree and give an example.")
WRITING_ESSAY = (
    "Many schools requires students to study only academic subjects, but practical "
    "skills matter just as much. Last term, our school organize a short course on "
    "cooking and simple repairs. Students learned how to prepare a healthy meal and "
    "how to fix a leaking tap. Some parents worried that the course would take time "
    "away from exam preparation. However, the students who joined it became more "
    "confident and learned to solve small problems on their own. In my opinion, "
    "schools should offer such courses because they prepare young people for daily "
    "life. A student who can cook a meal or repair a chair is less dependent on "
    "others and more ready for university. Practical lessons also make school more "
    "interesting. Therefore, I believe every school should include at least one "
    "practical skill course."
)
EXERCISE_ITEM = ("The teachers at our school ___ (encourages / encourage) students "
                 "to try practical courses.")
EXERCISE_OK = "The teachers at our school encourage students to try practical courses."
EXERCISE_BAD = "The teachers at our school encourages students to try practical courses."
RETEST_TASK = {
    "instruction": "想象社区有一间周末修理坊。用复数主语和一般现在时写一句英文，说明志愿者们每月做什么。",
    "item": "Imagine a community repair workshop. Write one sentence about what the volunteers do there every month.",
}
RETEST_OK = ("In our neighborhood, the volunteers who run the weekend repair workshop "
             "teach residents how to fix a broken chair.")
RETEST_BAD = ("In our neighborhood, the volunteers who runs the weekend repair workshop "
              "teach residents how to fix a broken chair.")
RETEST_WEAK = "The workshop opens at nine on Saturday morning."
MANUAL = (
    "阅读释义是否贴合 extended 在原句里的语境；",
    "整句分析的 translation 是否自然，structure 是否指出 commuters began taking the ferry；",
    "三类提示是否只给提示、没有直接给出整段英文译文；",
    "草稿批改的问题是否确有依据，补全稿与另一种合理译法是否没有被硬挑错；",
    "写作批改是否指向真实错误，练习与复测是否换了语境；",
    "复测判答是否接受 RETEST_OK 这类合理不同表达并给出 TARGET_USED；",
    "所有中文说明是否可读，是否出现与材料无关的编造内容。",
)


class StepFailed(Exception):
    pass


def redact(text: object) -> str:
    """输出前去掉疑似凭据，避免失败信息或模型输出把密钥带进终端。"""
    value = str(text)
    value = re.sub(r"sk-[A-Za-z0-9._-]+", "sk-***", value)
    value = re.sub(r"(?i)bearer\s+\S+", "Bearer ***", value)
    return value


def is_text(value: object) -> bool:
    return isinstance(value, str) and value.strip() != ""


class Runner:
    def __init__(self) -> None:
        self.failures: list[str] = []
        self.notes: list[str] = []
        self.warnings: list[str] = []
        self.passed = 0
        self.skipped = 0

    @contextlib.contextmanager
    def step(self, step_id: str):
        try:
            yield
        except StepFailed as exc:
            self.failures.append(step_id)
            print(f"[FAIL] {step_id}: {redact(exc)}")
        except Exception as exc:
            self.failures.append(step_id)
            print(f"[FAIL] {step_id}: 未预期异常 {type(exc).__name__}: {redact(exc)}")
        else:
            self.passed += 1
            print(f"[PASS] {step_id}")

    def require(self, condition: bool, message: str) -> None:
        if not condition:
            raise StepFailed(message)

    def note(self, text: str) -> None:
        self.notes.append(redact(text))

    def warn(self, step_id: str, text: str) -> None:
        self.warnings.append(f"{step_id}: {redact(text)}")

    def skip(self, step_id: str, reason: str) -> None:
        self.skipped += 1
        print(f"[SKIP] {step_id}: {reason}")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None  # 3xx 直接成为 HTTPError，绝不跟随到远端


class Client:
    def __init__(self, base_url: str, timeout: float = 90) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.token = None
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def request(self, method: str, path: str, payload: dict | None = None) -> tuple[int, object]:
        data = None
        headers = {"Accept": "application/json"}
        if payload is not None:
            data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json; charset=utf-8"
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        req = urllib.request.Request(self.base_url + path, data=data, headers=headers, method=method)
        try:
            with self.opener.open(req, timeout=self.timeout) as response:
                return response.status, _json(response.read())
        except urllib.error.HTTPError as exc:
            return exc.code, _json(exc.read())
        except urllib.error.URLError as exc:
            raise RuntimeError(f"无法连接本机服务：{exc.reason}") from None


def _json(raw: bytes) -> object:
    if not raw:
        return {}
    try:
        return json.loads(raw.decode("utf-8", errors="replace"))
    except json.JSONDecodeError:
        return {}


def error_of(status: int, body: object) -> str:
    if isinstance(body, dict) and (body.get("code") or body.get("message")):
        return f"HTTP {status}，code={body.get('code')!r}，message={redact(body.get('message'))!r}"
    return f"HTTP {status}"


def ok(r: Runner, status: int, body: object) -> dict:
    r.require(status == 200, error_of(status, body))
    r.require(isinstance(body, dict), "响应不是 JSON 对象")
    r.require(body.get("mode") == "live", f"mode 应为 live，实际为 {body.get('mode')!r}")
    return body


def text(r: Runner, value: object, label: str) -> str:
    r.require(is_text(value), f"{label} 必须是非空字符串")
    return value  # type: ignore[return-value]


def sign_in(c: Client, r: Runner) -> None:
    username = ""
    for _ in range(3):
        username = "lv" + "".join(random.choice(string.ascii_lowercase + string.digits) for _ in range(12))
        status, body = c.request("POST", "/api/v1/auth/register",
                                 {"username": username, "password": PASSWORD})
        if status == 201:
            break
        if status != 409:
            raise RuntimeError("注册测试账号失败：" + error_of(status, body))
    else:
        raise RuntimeError("连续三次注册都遇到用户名冲突")
    status, body = c.request("POST", "/api/v1/auth/login",
                             {"username": username, "password": PASSWORD})
    if status != 200 or not isinstance(body, dict) or not body.get("accessToken"):
        raise RuntimeError("登录测试账号失败：" + error_of(status, body))
    c.token = body["accessToken"]
    status, body = c.request("GET", "/api/v1/me")
    if status != 200:
        raise RuntimeError("凭证校验失败：" + error_of(status, body))
    print("一次性测试账号已建立会话（不输出用户名、口令或 token）。")


def run_reading(c: Client, r: Runner) -> None:
    with r.step("reading.word"):
        body = ok(r, *c.request("POST", "/api/v1/explanations",
                                {"sentence": READING_SENTENCE, "selection": READING_WORD}))
        r.note(f"阅读释义：meaning={text(r, body.get('meaning'), 'meaning')!r}；"
               f"explanation={text(r, body.get('explanation'), 'explanation')!r}")
    with r.step("reading.sentence"):
        body = ok(r, *c.request("POST", "/api/v1/sentence-analyses", {"sentence": ANALYSIS_SENTENCE}))
        text(r, body.get("translation"), "translation")
        text(r, body.get("structure"), "structure")
        chunks = body.get("chunks")
        r.require(isinstance(chunks, list) and 1 <= len(chunks) <= 20, "chunks 必须是 1–20 条数组")
        cursor = 0
        for i, chunk in enumerate(chunks):
            r.require(isinstance(chunk, dict), f"chunks[{i}] 不是对象")
            piece = text(r, chunk.get("text"), f"chunks[{i}].text")
            text(r, chunk.get("role"), f"chunks[{i}].role")
            text(r, chunk.get("explanation"), f"chunks[{i}].explanation")
            start = ANALYSIS_SENTENCE.find(piece, cursor)
            r.require(start >= 0, f"chunks[{i}].text 不是原句连续原文或与上一段重叠")
            cursor = start + len(piece)
        r.note(f"整句分析：{len(chunks)} 段顺序正确；structure={body.get('structure')!r}")


def hint_check(r: Runner, kind: str, body: dict) -> None:
    r.require(body.get("hintType") == kind, f"hintType 应为 {kind}")
    r.require(body.get("sourceText") == SOURCE, "sourceText 应回显中文原文")
    if kind == "structure":
        r.require(body.get("keywords") is None and body.get("phrases") is None,
                  "structure 提示不应同时返回 keywords/phrases")
        structure = body.get("structure")
        r.require(isinstance(structure, dict), "structure 必须是对象")
        text(r, structure.get("mainClause"), "structure.mainClause")
        clauses, links = structure.get("clauses"), structure.get("connectives")
        r.require(clauses is not None or links is not None, "clauses 与 connectives 不能同时缺失")
        for i, item in enumerate(clauses or []):
            r.require(isinstance(item, dict), f"clauses[{i}] 不是对象")
            r.require(text(r, item.get("source"), f"clauses[{i}].source") in SOURCE,
                      f"clauses[{i}].source 不是原文连续片段")
            text(r, item.get("role"), f"clauses[{i}].role")
            text(r, item.get("guidance"), f"clauses[{i}].guidance")
        for i, item in enumerate(links or []):
            r.require(isinstance(item, dict), f"connectives[{i}] 不是对象")
            r.require(text(r, item.get("source"), f"connectives[{i}].source") in SOURCE,
                      f"connectives[{i}].source 不是原文连续片段")
            text(r, item.get("suggestion"), f"connectives[{i}].suggestion")
        return
    field = "keywords" if kind == "keyword" else "phrases"
    other = "phrases" if kind == "keyword" else "keywords"
    r.require(body.get(other) is None and body.get("structure") is None,
              f"{kind} 提示不应同时返回其它类型")
    items = body.get(field)
    r.require(isinstance(items, list) and 1 <= len(items) <= 10, f"{field} 必须是 1–10 条数组")
    for i, item in enumerate(items):
        r.require(isinstance(item, dict), f"{field}[{i}] 不是对象")
        r.require(text(r, item.get("source"), f"{field}[{i}].source") in SOURCE,
                  f"{field}[{i}].source 不是原文连续片段")
        expressions = item.get("expressions")
        r.require(isinstance(expressions, list) and expressions, f"{field}[{i}].expressions 不能为空")
        for value in expressions:
            text(r, value, f"{field}[{i}].expressions 元素")
        text(r, item.get("note"), f"{field}[{i}].note")


def run_translation(c: Client, r: Runner) -> None:
    for kind in ("keyword", "phrase", "structure"):
        with r.step(f"translation.hint_{kind}"):
            hint_check(r, kind, ok(r, *c.request("POST", "/api/v1/translation-hints",
                                                {"sourceText": SOURCE, "hintType": kind})))
            r.note(f"汉译英提示 {kind}：字段形状与中文片段校验通过")
    for label, translation, clean in (("draft", DRAFT, False), ("improved", IMPROVED, True),
                                      ("alternative", ALTERNATIVE, True)):
        with r.step(f"translation.review_{label}"):
            body = ok(r, *c.request("POST", "/api/v1/translation-reviews",
                                    {"sourceText": SOURCE, "studentTranslation": translation}))
            r.require(body.get("sourceText") == SOURCE, "sourceText 应回显中文原文")
            r.require(body.get("studentTranslation") == translation, "studentTranslation 应回显译文")
            text(r, body.get("overallFeedback"), "overallFeedback")
            issues = body.get("issues")
            r.require(isinstance(issues, list) and len(issues) <= 20, "issues 必须是数组")
            for i, issue in enumerate(issues):
                r.require(isinstance(issue, dict), f"issues[{i}] 不是对象")
                kind = text(r, issue.get("type"), f"issues[{i}].type")
                r.require(kind in {"OMISSION", "UNNATURAL"}, f"issues[{i}].type 非法：{kind}")
                quote = text(r, issue.get("quote"), f"issues[{i}].quote")
                r.require(quote in (SOURCE if kind == "OMISSION" else translation),
                          f"issues[{i}].quote 没有逐字出现在对应输入")
                text(r, issue.get("detail"), f"issues[{i}].detail")
                text(r, issue.get("suggestion"), f"issues[{i}].suggestion")
            if clean and issues:
                r.warn(f"translation.review_{label}", f"合理译法仍返回 {len(issues)} 条问题，请人工判断")
            r.note(f"汉译英批改 {label}：issues={len(issues)} 条，引用逐字可查")


def writing_review(c: Client, r: Runner) -> dict | None:
    body = ok(r, *c.request("POST", "/api/v1/writing-reviews",
                            {"prompt": WRITING_PROMPT, "essay": WRITING_ESSAY}))
    r.require(body.get("prompt") == WRITING_PROMPT and body.get("essay") == WRITING_ESSAY,
              "prompt/essay 应回显本次输入")
    text(r, body.get("overallFeedback"), "overallFeedback")
    issues = body.get("issues")
    r.require(isinstance(issues, list) and len(issues) <= 3, "issues 必须是 0–3 条数组")
    for i, issue in enumerate(issues):
        r.require(isinstance(issue, dict), f"issues[{i}] 不是对象")
        kind = text(r, issue.get("type"), f"issues[{i}].type")
        r.require(kind in {"GRAMMAR", "WORD_CHOICE", "MECHANICS", "CLARITY"},
                  f"issues[{i}].type 非法：{kind}")
        quote = text(r, issue.get("quote"), f"issues[{i}].quote")
        r.require(quote in WRITING_ESSAY, f"issues[{i}].quote 没有逐字出现在作文里")
        text(r, issue.get("detail"), f"issues[{i}].detail")
        text(r, issue.get("suggestion"), f"issues[{i}].suggestion")
    exercise = body.get("exercise")
    if not issues:
        r.require(exercise is None, "issues 为空时不应返回 exercise")
        r.note("写作批改：issues=0，无练习；后续判答/复测无法组装 context")
        return None
    r.require(isinstance(exercise, dict), "有问题时必须返回 exercise")
    index = exercise.get("issueIndex")
    r.require(isinstance(index, int) and not isinstance(index, bool) and 0 <= index < len(issues),
              "exercise.issueIndex 必须指向本次 issues")
    text(r, exercise.get("instruction"), "exercise.instruction")
    text(r, exercise.get("item"), "exercise.item")
    r.note(f"写作批改：issues={len(issues)} 条，练习指向 issues[{index}]")
    return {"issue": issues[index], "exercise": exercise}


def run_writing(c: Client, r: Runner) -> None:
    context = None
    with r.step("writing.review"):
        context = writing_review(c, r)
    if context is None:
        for step in ("writing.exercise_ok", "writing.exercise_bad", "writing.retest_create",
                     "writing.retest_ok", "writing.retest_bad", "writing.retest_weak"):
            r.skip(step, "writing.review 未返回可用练习")
        return

    for label, answer, expected in (("ok", EXERCISE_OK, "CORRECT"), ("bad", EXERCISE_BAD, "NEEDS_REVISION")):
        with r.step(f"writing.exercise_{label}"):
            body = ok(r, *c.request("POST", "/api/v1/writing-exercise-attempts",
                                    {"context": context, "answer": answer}))
            r.require(body.get("issue") == context["issue"], "issue 应回显原问题")
            r.require(body.get("answer") == answer, "answer 应回显本次作答")
            verdict = text(r, body.get("verdict"), "verdict")
            r.require(verdict in {"CORRECT", "NEEDS_REVISION"}, f"verdict 非法：{verdict}")
            text(r, body.get("feedback"), "feedback")
            text(r, body.get("nextStep"), "nextStep")
            if verdict != expected:
                r.warn(f"writing.exercise_{label}", f"预期 {expected}，实际 {verdict}，请人工判断")
            r.note(f"原练习判答 {label}：verdict={verdict}；feedback={body.get('feedback')!r}")

    task = None
    with r.step("writing.retest_create"):
        body = ok(r, *c.request("POST", "/api/v1/writing-retests", context))
        r.require(body.get("issue") == context["issue"], "issue 应回显原问题")
        task = body.get("task")
        r.require(isinstance(task, dict), "task 必须是对象")
        text(r, task.get("instruction"), "task.instruction")
        text(r, task.get("item"), "task.item")
        r.require(task.get("item") != context["exercise"].get("item"), "复测题面不应复用原题面")
        r.note(f"换题复测：instruction={task.get('instruction')!r}；item={task.get('item')!r}")
    if task is None:
        for step in ("writing.retest_ok", "writing.retest_bad", "writing.retest_weak"):
            r.skip(step, "writing.retest_create 未返回任务")
        return

    for label, answer, expected in (("ok", RETEST_OK, "TARGET_USED"),
                                    ("bad", RETEST_BAD, "SAME_ERROR"),
                                    ("weak", RETEST_WEAK, "INSUFFICIENT_EVIDENCE")):
        with r.step(f"writing.retest_{label}"):
            body = ok(r, *c.request("POST", "/api/v1/writing-retest-attempts",
                                    {"context": context, "task": task, "answer": answer}))
            r.require(body.get("issue") == context["issue"], "issue 应回显原问题")
            r.require(body.get("answer") == answer, "answer 应回显本次作答")
            verdict = text(r, body.get("verdict"), "verdict")
            r.require(verdict in {"TARGET_USED", "SAME_ERROR", "INSUFFICIENT_EVIDENCE"},
                      f"verdict 非法：{verdict}")
            text(r, body.get("feedback"), "feedback")
            text(r, body.get("nextStep"), "nextStep")
            quote = body.get("quote")
            if verdict == "INSUFFICIENT_EVIDENCE":
                r.require(quote is None, "证据不足时不应返回 quote")
            else:
                r.require(is_text(quote) and quote in answer, "quote 必须逐字来自本次答案")
            if verdict != expected:
                r.warn(f"writing.retest_{label}", f"预期 {expected}，实际 {verdict}，请人工判断")
            r.note(f"换题复测判答 {label}：verdict={verdict}；quote={quote!r}")


# --- 固定响应替身：只覆盖这组样本，不模拟真实模型 -------------------------------
def reply(system: str, user: str) -> str:
    def dump(value: object) -> str:
        return json.dumps(value, ensure_ascii=False)

    if "语境解析助手" in system:
        return dump({"meaning": "延长", "explanation": "这里作动词，表示博物馆把开放时间延长了。"})
    if "长难句拆解助手" in system:
        roles = ("原因状语从句", "主句主语（含 who 定语从句）", "主句谓语 + 宾语")
        return dump({"translation": "因为桥在维修而关闭，平时开车上班的通勤者开始改乘渡轮过河。",
                     "structure": "主干是 commuters began taking the ferry；Because 从句交代原因。",
                     "chunks": [{"text": t, "role": role, "explanation": "替身固定说明。"}
                                for t, role in zip(ANALYSIS_CHUNKS, roles)]})
    if "汉译英作答提示助手" in system:
        if "structure" in user:
            return dump({"mainClause": "主干是退休居民愿意阅读报刊。",
                         "clauses": [{"source": "随着社区图书馆延长开放时间", "role": "时间状语从句",
                                      "guidance": "用 As 或 With 引导。"}],
                         "connectives": [{"source": "随着", "suggestion": "As / With"}]})
        field = "phrases" if "phrase" in user else "keywords"
        return dump({field: [{"source": "社区图书馆", "expressions": ["community library"],
                              "note": "社区里的图书馆。"},
                             {"source": "报刊", "expressions": ["newspapers and magazines"],
                              "note": "报纸和杂志。"}]})
    if "汉译英批改助手" in system:
        issues = [] if DRAFT not in user else [
            {"type": "OMISSION", "quote": "社区", "detail": "草稿漏掉「社区」。", "suggestion": "补出 community。"},
            {"type": "UNNATURAL", "quote": "walk here", "detail": "地点表达略生硬。",
             "suggestion": "改成 walk to the library。"},
        ]
        return dump({"overallFeedback": "大意可读，请补全信息并让地点表达更自然。", "issues": issues})
    if "写作练习判答助手" in system:
        answer = json.loads(user).get("answer", "")
        if "encourages" in answer:
            return dump({"verdict": "NEEDS_REVISION", "feedback": "复数主语 teachers 需要搭配 encourage。",
                         "nextStep": "把 encourages 改成 encourage。"})
        return dump({"verdict": "CORRECT", "feedback": "完整句子中复数主语与动词一致。",
                     "nextStep": "检查作文中其他主谓一致。"})
    if "复测出题助手" in system:
        return dump(RETEST_TASK)
    if "复测判答助手" in system:
        answer = json.loads(user).get("answer", "")
        if "runs" in answer:
            return dump({"verdict": "SAME_ERROR", "quote": "the volunteers who runs",
                         "feedback": "复数主语后用了 runs，仍是同类错误。",
                         "nextStep": "把 runs 改成 run。"})
        if "workshop opens" in answer:
            return dump({"verdict": "INSUFFICIENT_EVIDENCE", "quote": None,
                         "feedback": "主语是单数，没有展示目标结构。",
                         "nextStep": "用复数主语 the volunteers 写一句。"})
        return dump({"verdict": "TARGET_USED", "quote": "the volunteers who run",
                     "feedback": "正确展示了复数主语后的动词形式。",
                     "nextStep": "回到作文检查 Many schools 后的动词。"})
    if "写作批改助手" in system:
        if "Many schools requires" not in user:
            return dump({"overallFeedback": "替身没有为这份作文预置问题。", "issues": [], "exercise": None})
        return dump({
            "overallFeedback": "观点清楚，先改开头的主谓一致和过去时。",
            "issues": [
                {"type": "GRAMMAR", "quote": "Many schools requires",
                 "detail": "复数主语用了第三人称单数形式。", "suggestion": "把 requires 改成 require。"},
                {"type": "GRAMMAR", "quote": "our school organize",
                 "detail": "Last term 表示过去。", "suggestion": "把 organize 改成 organized。"},
            ],
            "exercise": {"issueIndex": 0, "instruction": "选择与复数主语一致的动词，并写出完整句子。",
                         "item": EXERCISE_ITEM},
        })
    return dump({"error": "替身无法识别该提示词"})


class Handler(BaseHTTPRequestHandler):
    def do_POST(self) -> None:  # noqa: N802
        if self.path.rstrip("/") != "/v1/chat/completions":
            self.send_json(404, {"error": {"message": "not found"}})
            return
        try:
            payload = json.loads(self.read_body())
            messages = payload.get("messages") or []
            content = reply(str(messages[0].get("content", "")), str(messages[1].get("content", "")))
            self.send_json(200, {"choices": [{"message": {"role": "assistant", "content": content}}]})
        except Exception:
            self.send_json(400, {"error": {"message": "bad request"}})

    def read_body(self) -> bytes:
        if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
            data = b""
            while True:
                size = int(self.rfile.readline().split(b";")[0].strip(), 16)
                if size == 0:
                    self.rfile.readline()
                    return data
                data += self.rfile.read(size)
                self.rfile.read(2)
        return self.rfile.read(int(self.headers.get("Content-Length", "0")))

    def send_json(self, status: int, payload: object) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt: str, *args: object) -> None:
        return  # 不输出请求行、请求头或正文


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def ensure_jar() -> None:
    if JAR.exists():
        return
    print(f"未找到 {JAR.name}，先构建（./mvnw -o -B -DskipTests package）……")
    with tempfile.NamedTemporaryFile("w+", suffix=".log") as log:
        result = subprocess.run([str(SERVER_DIR / "mvnw"), "-o", "-B", "-DskipTests", "package"],
                                cwd=SERVER_DIR, stdout=log, stderr=subprocess.STDOUT)
        if result.returncode != 0:
            log.seek(0)
            print(redact("".join(log.readlines()[-20:])))
            raise RuntimeError("Maven 构建失败")


def wait_ready(base: str, process: subprocess.Popen, log: Path) -> None:
    deadline = time.time() + 90
    while time.time() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"服务提前退出，退出码 {process.returncode}：{tail(log)}")
        try:
            status, _ = Client(base, timeout=5).request("GET", "/api/v1/me")
            if status in (200, 401):
                return
        except RuntimeError:
            pass
        time.sleep(0.5)
    raise RuntimeError("等待服务就绪超时：" + tail(log))


def tail(path: Path) -> str:
    try:
        return redact("\n".join(path.read_text(encoding="utf-8", errors="replace").splitlines()[-20:]))
    except OSError:
        return ""


def run_stand_in() -> int:
    ensure_jar()
    upstream = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    upstream.daemon_threads = True
    import threading
    threading.Thread(target=upstream.serve_forever, daemon=True).start()
    port = free_port()
    work = Path(tempfile.mkdtemp(prefix="cet-live-smoke-"))
    (work / "data").mkdir()
    log = work / "service.log"
    env = os.environ.copy()
    env.update({
        "SERVER_PORT": str(port),
        "SPRING_DATASOURCE_URL": f"jdbc:h2:file:{work / 'data' / 'cet'};DB_CLOSE_ON_EXIT=FALSE",
        "CET_EXPLANATION_MODE": "live",
        "CET_MODEL_BASE_URL": f"http://127.0.0.1:{upstream.server_address[1]}/v1",
        "CET_MODEL_NAME": "cet-live-smoke-stand-in",
        "CET_MODEL_API_KEY": "stand-in-not-a-real-credential",
        "CET_MODEL_CONNECT_TIMEOUT": "PT2S",
        "CET_MODEL_READ_TIMEOUT": "PT15S",
        "CET_TOKEN_TTL": "PT1H",
    })
    java = str(Path(env["JAVA_HOME"]) / "bin" / "java") if env.get("JAVA_HOME") else "java"
    process = None
    try:
        with log.open("w", encoding="utf-8") as handle:
            process = subprocess.Popen([java, "-jar", str(JAR)], cwd=SERVER_DIR, env=env,
                                       stdout=handle, stderr=subprocess.STDOUT)
        base = f"http://127.0.0.1:{port}"
        print("=== live 冒烟 · 本地固定响应替身 ===")
        print("替身只校验 live 请求与输出结构，不是真实模型验证。")
        wait_ready(base, process, log)
        return smoke(base)
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
        upstream.shutdown()
        upstream.server_close()
        shutil.rmtree(work, ignore_errors=True)


def smoke(base: str) -> int:
    print(f"=== live 冒烟 · {base} ===")
    r = Runner()
    c = Client(base)
    try:
        sign_in(c, r)
    except Exception as exc:
        print(f"[FAIL] auth: {redact(exc)}")
        return 1
    for name, run in (("reading", run_reading), ("translation", run_translation),
                      ("writing", run_writing)):
        if name not in ONLY:
            continue
        print(f"\n--- {name} ---")
        try:
            run(c, r)
        except Exception as exc:
            print(f"[FAIL] {name}: 未预期异常 {type(exc).__name__}: {redact(exc)}")

    print("\n--- 人工判断（不参与退出码）---")
    for note in r.notes:
        print("  [人工] " + note)
    if r.warnings:
        print("  [WARN] 结构通过但语义需确认：")
        for warning in r.warnings:
            print("    - " + warning)
    print("\n人工验收清单：")
    for item in MANUAL:
        print("  [ ] " + item)
    print(f"\n契约结果：{r.passed} 通过 / {len(r.failures)} 失败 / {r.skipped} 跳过")
    if r.failures:
        print("失败步骤：" + "、".join(r.failures))
        print("按组复跑：python3 scripts/live_smoke.py --stand-in --only reading")
        return 1
    print("契约检查全部通过；真实模型的解释与批改质量仍须按上方清单人工判断。")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="四六级 AI 学习助手 live 冒烟")
    parser.add_argument("--base-url", default=BASE_URL, help="本机 live 服务地址")
    parser.add_argument("--stand-in", action="store_true", help="用固定响应替身自动跑")
    parser.add_argument("--only", default="", help="只跑 reading/translation/writing，逗号分隔")
    args = parser.parse_args()
    global ONLY
    ONLY = {part.strip() for part in args.only.split(",") if part.strip()} or {
        "reading", "translation", "writing"}
    if ONLY - {"reading", "translation", "writing"}:
        print("未知的 --only 取值：" + "、".join(sorted(ONLY)))
        return 2
    try:
        if args.stand_in:
            return run_stand_in()
        parsed = urllib.parse.urlparse(args.base_url)
        if (parsed.scheme not in {"http", "https"} or not parsed.hostname
                or parsed.hostname not in LOOPBACK or parsed.username or parsed.password):
            raise ValueError("--base-url 必须是不含凭据的本机地址（127.0.0.1/localhost/::1）")
        return smoke(args.base_url.rstrip("/"))
    except Exception as exc:
        print(f"冒烟无法开始：{type(exc).__name__}: {redact(exc)}")
        return 1


ONLY: set[str] = set()
if __name__ == "__main__":
    sys.exit(main())
