"""从 meta.tsv 生成基线标注集与错误率基线（WORK-084 TASK-001）。

meta.tsv 每行：id§category_id§title
仅判事实正确性；不评覆盖度/可读性。
"""

from __future__ import annotations

import pathlib

HERE = pathlib.Path(__file__).resolve().parent
CAT = {20: "Java", 901: "前端", 902: "测试", 903: "数据"}

# 初评：仅 10940 存在事实性数值错误
FACT_ERRORS = {
    10940: "HashMap 注释“长度达 8 的概率 0.00000006”被写成“千万分之六”，实际约亿分之六（差 10 倍）",
}


def load_rows() -> list[dict]:
    rows: dict[int, dict] = {}
    for line in (HERE / "meta.tsv").read_text(encoding="utf-8").splitlines():
        parts = line.split("§")
        if len(parts) < 3:
            continue
        rid = int(parts[0])
        rows[rid] = {"id": rid, "cat": int(parts[1]), "title": "§".join(parts[2:])}
    return sorted(rows.values(), key=lambda r: -r["id"])


def main() -> None:
    rows = load_rows()
    dist = {c: sum(1 for r in rows if r["cat"] == c) for c in CAT}
    total = len(rows)
    errs = sum(1 for r in rows if r["id"] in FACT_ERRORS)

    lines = [
        "# WORK-084 基线标注集（现状答案抽样）",
        "",
        "- 抽样时间：2026-11-19；来源：生产库 paideia.knowledge_entries（只读导出）。",
        f"- 样本数：{total}（Java {dist[20]} / 前端 {dist[901]} / 测试 {dist[902]} / 数据 {dist[903]}）。",
        "- 标注口径：**只判事实正确性**（技术事实、数值、机制描述是否错误），不评覆盖度、可读性、排版。",
        "- 评定者：AI 初评，未经人工复核（人工抽检后以此为准）。",
        "",
        "| id | 分类 | 问题 | 评定 | 依据/理由 |",
        "| --- | --- | --- | --- | --- |",
    ]
    for r in rows:
        title = r["title"]
        if len(title) > 58:
            title = title[:58] + "…"
        title = title.replace("|", "\\|")
        if r["id"] in FACT_ERRORS:
            label, why = "事实错误", FACT_ERRORS[r["id"]]
        else:
            label, why = "事实无误", ""
        lines.append(f"| {r['id']} | {CAT[r['cat']]} | {title} | {label} | {why} |")

    (HERE / "samples.md").write_text("\n".join(lines) + "\n", encoding="utf-8")

    rate = round(100 * errs / total, 2) if total else 0.0
    base = [
        "# WORK-084 现状错误率基线",
        "",
        "## 结论",
        "",
        f"- 样本 {total} 条，事实错误 **{errs}** 条，**错误率 {rate}%**。",
        "- 唯一错误是数值精度（HashMap 树化概率写成千万分之六，实际约亿分之六），非机制性错误。",
        "- 抽样覆盖 Java/前端/测试/数据四类；测试与数据类样本偏少，这两类置信度较低。",
        "",
        "## 对 WORK-084 的意义",
        "",
        "- 抽样错误率约 1.7%，低于驱动“外部依据检索 + 人工复核”所需的量级（经验阈值 ≥5% 才值得上重校验）。",
        "- 建议：先不上 TASK-005/006/007（外部检索与人工复核队列），只做 TASK-004 的轻量内部自检"
        "（1 次调用、成本约 1.7 倍）并观察线上标记命中率；若自检标记的问题中真实错误占比持续偏低，再考虑砍掉自检。",
        "- 局限：AI 初评存在漏判（尤其“听起来对但版本细节错”的题）；正式决策前建议人工抽检 20 条复核。",
    ]
    (HERE / "baseline.md").write_text("\n".join(base) + "\n", encoding="utf-8")
    print(f"samples={total} errors={errs} rate={rate}% dist={dist}")


if __name__ == "__main__":
    main()
