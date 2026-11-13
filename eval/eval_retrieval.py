#!/usr/bin/env python3
"""
Retrieval-only evaluation (no LLM).

Hits GET /api/sources/search and scores whether the gold source_document from
benchmark_50_questions.json appears in the retrieved passages.

This is a retrieval metric. Do not mix it with grounded-answer accuracy,
citation support, missing-info recall, or extraction F1.

Usage (Spring Boot must be running on :8081, corpus ingested including extra-dir):
  python3 eval/eval_retrieval.py
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent
ROOT = BASE_DIR.parent
BENCHMARK_FILE = BASE_DIR / "benchmark_50_questions.json"
RESULTS_FILE = BASE_DIR / "retrieval_results.json"
SEARCH_URL = "http://localhost:8081/api/sources/search"
SOURCES_URL = "http://localhost:8081/api/sources"


def git_sha() -> str:
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True, stderr=subprocess.DEVNULL
        ).strip()
    except Exception:
        return "unknown"


def normalize(s: str) -> str:
    s = (s or "").lower()
    s = re.sub(r"\.(pdf|docx|txt|html|md)$", "", s)
    s = s.replace("_", " ").replace("-", " ")
    return re.sub(r"\s+", " ", s).strip()


def gold_keys(source_document: str) -> list[str]:
    n = normalize(source_document)
    keys = [n]
    if "100.020" in n:
        keys.append("100.020")
    compact = n.replace(" ", "")
    if "16c" in compact:
        keys.append("16c")
    uits = re.search(r"uits\s*(\d+)", n)
    if uits:
        keys.append(f"uits {uits.group(1)}")
    if "licensing" in n and "startup" in n:
        keys.append("licensing")
    return keys


def hit_matches(title: str, gold_doc: str) -> bool:
    t = normalize(title)
    n = normalize(gold_doc)
    if not t:
        return False
    if n in t or t in n:
        return True
    for k in gold_keys(gold_doc):
        if k and k in t:
            return True
    return False


def get_json(url: str):
    req = urllib.request.Request(url, method="GET")
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def search(question: str):
    q = urllib.parse.urlencode({"q": question})
    return get_json(f"{SEARCH_URL}?{q}")


def run() -> int:
    print("=" * 70)
    print("Retrieval-only benchmark (no LLM)")
    print("Endpoint: GET /api/sources/search")
    print("=" * 70)

    try:
        sources = get_json(SOURCES_URL)
    except Exception as e:
        print(f"Cannot reach {SOURCES_URL}: {e}")
        print("Start Spring Boot on :8081 and ingest (including eval/extracted_texts) first.")
        return 1

    titles = [s.get("title") or "" for s in sources]
    print(f"Indexed sources: {len(titles)}")
    for t in titles:
        print(f"  - {t}")

    with open(BENCHMARK_FILE, encoding="utf-8") as f:
        questions = json.load(f)

    by_cat = defaultdict(lambda: {"n": 0, "hit": 0})
    details = []
    t0 = time.perf_counter()
    for item in questions:
        gold = item["source_document"]
        cat = item.get("category", "unknown")
        by_cat[cat]["n"] += 1
        try:
            hits = search(item["question"])
        except urllib.error.HTTPError as e:
            print(f"  [FAIL] {item['id']}: HTTP {e.code}")
            hits = []
        except Exception as e:
            print(f"  [FAIL] {item['id']}: {e}")
            hits = []
        matched = [h for h in hits if hit_matches(h.get("title") or "", gold)]
        ok = bool(matched)
        if ok:
            by_cat[cat]["hit"] += 1
        details.append({
            "id": item["id"],
            "category": cat,
            "question": item["question"],
            "gold_source": gold,
            "hit": ok,
            "hit_titles": [h.get("title") for h in hits],
            "matched_titles": [h.get("title") for h in matched],
        })
        mark = "HIT " if ok else "MISS"
        print(f"  [{mark}] {item['id']}  gold={gold}")
        if not ok:
            shown = hits[:3]
            preview = ", ".join((h.get("title") or "(untitled)") for h in shown) or "(no hits)"
            print(f"           retrieved: {preview}")

    elapsed = time.perf_counter() - t0
    total = len(questions)
    hits = sum(1 for d in details if d["hit"])
    recall = hits / total if total else 0.0
    cat_rows = []
    print("\nRecall by category (document-level, retrieval only):")
    for cat, s in sorted(by_cat.items()):
        r = s["hit"] / s["n"] if s["n"] else 0.0
        cat_rows.append({"category": cat, "n": s["n"], "hits": s["hit"], "recall": round(r, 3)})
        print(f"  {cat}: {s['hit']}/{s['n']}  ({100 * r:.1f}%)")
    print(f"\nOverall retrieval recall: {hits}/{total}  ({100 * recall:.1f}%)")
    print(f"Elapsed: {elapsed:.1f}s   git: {git_sha()[:12]}")

    report = {
        "metric": "retrieval_recall",
        "definition": "Gold source_document appears in /api/sources/search hits. No LLM.",
        "git_sha": git_sha(),
        "indexed_source_count": len(titles),
        "indexed_titles": titles,
        "n": total,
        "hits": hits,
        "recall": round(recall, 3),
        "by_category": cat_rows,
        "details": details,
    }
    RESULTS_FILE.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(f"Wrote {RESULTS_FILE}")
    return 0


if __name__ == "__main__":
    sys.exit(run())
