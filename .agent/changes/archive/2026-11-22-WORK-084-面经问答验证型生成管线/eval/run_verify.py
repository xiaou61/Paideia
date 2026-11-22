"""对 58 条基线样本跑一遍内部自检用的校验提示词，统计纠错效果。

用法（密钥从环境变量读，不落盘）：DEEPSEEK_KEY=... python run_verify.py
输出：verify-results.jsonl、屏幕摘要。
"""
import json
import os
import re
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

BASE = "https://api.deepseek.com"
KEY = os.environ["DEEPSEEK_KEY"]
MODEL = "deepseek-flash"

SYSTEM = (
    "你是技术面试答案的事实校验员。判断答案里是否存在**事实性错误**。"
    "只有这几种情况才判 issues：出现与公认技术事实相矛盾的具体陈述（数值算错、机制说反、结论错误），"
    "或把已知事实说反。以下都不算错误，不要因此判 issues：说得不够全面、表述过于绝对、缺少细节、"
    "举例不贴切、文风或可读性问题。无法确认有错时就判 ok，不要因为拿不准就判 unsure。"
    '只输出 JSON：{"verdict":"ok|issues|unsure","confidence":0~1,"issues":["具体错在哪"]}。'
    "issues 每条必须指出一个具体错误的事实，不要写泛泛的“不够准确”。"
)


def call(title, answer):
    body = {
        "model": MODEL,
        "messages": [
            {"role": "system", "content": SYSTEM},
            {"role": "user", "content": f"题目：{title}\n答案：{answer}"},
        ],
        "temperature": 0,
        "max_tokens": 16000,
    }
    req = urllib.request.Request(
        BASE + "/chat/completions",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + KEY},
    )
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=300) as r:
                return json.loads(r.read())["choices"][0]["message"]["content"]
        except (urllib.error.URLError, TimeoutError):
            if attempt == 2:
                raise
    return ""


def parse(raw):
    text = raw.strip()
    text = re.sub(r"^```(?:json)?\s*|\s*```$", "", text, flags=re.MULTILINE).strip()
    try:
        obj = json.loads(text)
    except json.JSONDecodeError:
        m = re.search(r"\{[\s\S]*\}", text)
        obj = json.loads(m.group(0)) if m else {"verdict": "unsure", "confidence": 0, "issues": ["unparsable"]}
    return obj


def split_body(body):
    text = body.replace("\\n", "\n")
    title = ""
    m = re.match(r"\s*##\s+([^\n]+)\n+", text)
    if m:
        title = m.group(1).strip()
        text = text[m.end():]
    text = re.split(r"\n+\s*-{3,}\s*", text)[0]
    return title.strip(), text.strip()


def parse_line(line):
    try:
        return json.loads(line)
    except json.JSONDecodeError:
        idm = re.search(r'"id":\s*(\d+)', line)
        catm = re.search(r'"cat":\s*(\d+)', line)
        titlem = re.search(r'"title":\s*"((?:[^"\\]|\\.)*)"\s*\}\s*$', line)
        bodym = re.search(r'"body":\s*"(.*)",\s*"title":', line)
        raw = bodym.group(1) if bodym else ""
        raw = raw.replace("\\\\", "\\").replace("\\n", "\n").replace('\\"', '"')
        return {
            "id": int(idm.group(1)) if idm else -1,
            "cat": int(catm.group(1)) if catm else 0,
            "body": raw,
            "title": titlem.group(1) if titlem else "",
        }


def load_samples():
    rows = []
    for f in ("raw-samples.jsonl", "raw-samples2.jsonl", "raw-samples3.jsonl"):
        for line in open(f, encoding="utf-8"):
            line = line.strip()
            if line:
                rows.append(parse_line(line))
    return rows


def load_truth():
    truth = {}
    for line in open("samples.md", encoding="utf-8"):
        m = re.match(r"\|\s*(\d+)\s*\|[^|]*\|[^|]*\|\s*([^|]+?)\s*\|", line)
        if m:
            truth[int(m.group(1))] = m.group(2).strip()
    return truth


def main():
    samples = load_samples()
    truth = load_truth()
    results = []

    def work(s):
        title, answer = split_body(s["body"])
        raw = ""
        try:
            raw = call(title or s["title"], answer)
            verdict = parse(raw)
        except Exception as exc:  # noqa: BLE001
            verdict = {"verdict": "unsure", "confidence": 0, "issues": [f"error:{type(exc).__name__}"]}
        flagged = verdict.get("verdict") != "ok"
        return {
            "id": s["id"],
            "cat": s["cat"],
            "truth": truth.get(s["id"], "?"),
            "verdict": verdict.get("verdict"),
            "confidence": verdict.get("confidence"),
            "issues": verdict.get("issues", []),
            "flagged": flagged,
            "raw_len": len(raw),
            "raw_head": raw[:160],
        }

    with ThreadPoolExecutor(max_workers=6) as pool:
        results = list(pool.map(work, samples))

    with open("verify-results.jsonl", "w", encoding="utf-8") as fh:
        for r in results:
            fh.write(json.dumps(r, ensure_ascii=False) + "\n")

    errored = [r for r in results if "错" in r["truth"]]
    correct = [r for r in results if "错" not in r["truth"]]
    caught = [r for r in errored if r["flagged"]]
    false_flags = [r for r in correct if r["flagged"]]
    print(f"samples={len(results)} errored={len(errored)} correct={len(correct)}")
    print(f"caught={len(caught)}/{len(errored)} false_flags={len(false_flags)}/{len(correct)}")
    for r in caught:
        print("  CAUGHT", r["id"], r["issues"])
    for r in false_flags:
        print("  FALSE ", r["id"], r["issues"])


if __name__ == "__main__":
    main()
