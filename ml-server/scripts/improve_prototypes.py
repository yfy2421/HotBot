#!/usr/bin/env python3
"""从 L2_L3_DISAGREE 日志生成原型优化建议。

用法:
    python scripts/improve_prototypes.py logs/bot-server.log          # 分析日志
    python scripts/improve_prototypes.py logs/bot-server.log --prompt # 生成 LLM prompt
    python scripts/improve_prototypes.py logs/bot-server.log --apply  # 交互式应用新原型

流转:
    1. grep L2_L3_DISAGREE logs/bot-server.log > disagreements.txt
    2. python scripts/improve_prototypes.py disagreements.txt --prompt
    3. 把 prompt 发给 ChatGPT/Claude → 得到新原型候选
    4. 人工审校 → 加到 services/intent.py 的 PROTOTYPES 里
    5. 重启 ml-server
"""

import argparse
import re
import sys
from collections import defaultdict
from pathlib import Path


# ---- Parse log entries ----

DISAGREE_PATTERN = re.compile(
    r'L2_L3_DISAGREE:\s*text="(.+?)"\s+l2=(\w+)\(([\d.]+)\)\s+l3=(\S+)'
)


def parse_log(path: str) -> list[dict]:
    entries = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            m = DISAGREE_PATTERN.search(line)
            if m:
                entries.append({
                    "text": m.group(1),
                    "l2_intent": m.group(2),
                    "l2_confidence": float(m.group(3)),
                    "l3_intent": m.group(4),
                })
    return entries


# ---- Analysis ----

def analyze(entries: list[dict]) -> dict[str, list[str]]:
    """Group disagreement texts by the L3 (correct) intent.

    Only include cases where L2 confidence was non-trivial (> 0.3)
    but still fell through — these are the ones L2 got wrong.
    """
    by_intent: dict[str, list[str]] = defaultdict(list)
    for e in entries:
        # Skip entries where L2 had very low confidence —
        # those are just "L2 gave up", not "L2 got it wrong".
        if e["l2_confidence"] < 0.4:
            continue
        # The L2 intent is what L2 thought, but the L3 fallback
        # produced a different path. Group by l2_intent for diagnosis.
        by_intent[e["l2_intent"]].append(e["text"])
    return dict(by_intent)


def print_analysis(by_intent: dict[str, list[str]]):
    if not by_intent:
        print("No L2_L3_DISAGREE entries with L2 confidence >= 0.4 found.")
        print("This means either: L2 is doing well, or there aren't enough logs yet.")
        return

    print(f"Found disagreements across {len(by_intent)} intent(s):\n")
    for intent, texts in sorted(by_intent.items()):
        print(f"  [{intent}] {len(texts)} case(s):")
        for t in texts[:5]:
            print(f"    - {t}")
        if len(texts) > 5:
            print(f"    ... and {len(texts) - 5} more")
        print()


# ---- LLM Prompt generation ----

def generate_prompt(by_intent: dict[str, list[str]]):
    """Generate a prompt to send to an LLM for prototype expansion."""
    if not by_intent:
        print("# No disagreements to generate prompts for.")
        return

    print("# Paste the following into ChatGPT / Claude:\n")
    print("=" * 60)
    print("""
你是一个意图分类原型优化器。我有一个 Embedding 相似度分类器,
它通过用户消息和预设"原型短语"的余弦相似度来判断意图。

以下是分类器最近判断失误的案例（L2 embedding 分类器判断失败,
但更有能力的 L3 判断出了正确意图）:

""")

    for intent, texts in sorted(by_intent.items()):
        print(f"## 意图: {intent}")
        print(f"## 漏判案例:")
        for t in texts[:15]:
            print(f"  - {t}")
        print(f"""
请为 "{intent}" 意图生成 5-8 条新的原型短语,覆盖以上口语表达。
要求:
1. 保持相同的语义但使用不同的句式
2. 包含简短形式(2-4字)和较长形式(6-12字)
3. 新原型不要和下面已有的原型重复

我会在得到你的输出后,通过人工审校并合并到原型库中。
""")
        print()

    print("=" * 60)
    print()
    print("# After receiving the LLM output:")
    print("# 1. Review each prototype — delete any that feel unnatural")
    print("# 2. Add the good ones to ml-server/services/intent.py PROTOTYPES dict")
    print("# 3. Restart ml-server (prototypes are re-embedded on startup)")


# ---- Interactive apply ----

def interactive_apply(by_intent: dict[str, list[str]]):
    """Interactive mode: show each case and ask for new prototypes."""
    import services.intent as intent_module

    current_protos = intent_module.PROTOTYPES

    for intent, texts in sorted(by_intent.items()):
        print(f"\n{'=' * 60}")
        print(f"Intent: {intent}")
        print(f"Current prototypes ({len(current_protos.get(intent, []))}):")
        for p in current_protos.get(intent, []):
            print(f"  - {p}")
        print(f"\nL2 missed these ({len(texts)} cases):")
        for t in texts[:10]:
            print(f"  - {t}")
        print()

        print("Enter new prototypes (one per line, empty line to skip):")
        new_protos = []
        while True:
            try:
                line = input()
            except (EOFError, KeyboardInterrupt):
                break
            if not line.strip():
                break
            new_protos.append(line.strip())

        if new_protos:
            print(f"Adding {len(new_protos)} prototype(s) to '{intent}'...")
            current = list(current_protos.get(intent, []))
            current.extend(new_protos)
            print(f"  Total for '{intent}': {len(current)}")
            print("  (Edit services/intent.py PROTOTYPES dict to make this permanent)")
        else:
            print("Skipped.")


# ---- Main ----

def main():
    parser = argparse.ArgumentParser(description="Improve intent classification prototypes")
    parser.add_argument("logfile", help="Path to log file with L2_L3_DISAGREE entries")
    parser.add_argument(
        "--prompt",
        action="store_true",
        help="Generate an LLM prompt for prototype expansion",
    )
    parser.add_argument(
        "--apply",
        action="store_true",
        help="Interactive mode: add new prototypes case by case",
    )
    parser.add_argument(
        "--min-confidence",
        type=float,
        default=0.4,
        help="Minimum L2 confidence to consider (default: 0.4)",
    )
    args = parser.parse_args()

    entries = parse_log(args.logfile)
    if not entries:
        print(f"No L2_L3_DISAGREE entries found in {args.logfile}")
        print("Make sure the bot-server is running with L2 classification enabled.")
        sys.exit(1)

    print(f"Parsed {len(entries)} disagreement entries.\n")

    by_intent = analyze(entries)

    if args.prompt:
        generate_prompt(by_intent)
    elif args.apply:
        interactive_apply(by_intent)
    else:
        print_analysis(by_intent)
        print("Use --prompt to generate an LLM optimization prompt.")
        print("Use --apply for interactive prototype editing.")


if __name__ == "__main__":
    main()
