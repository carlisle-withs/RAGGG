#!/usr/bin/env python3
"""
RAGAS 四指标真实评测（项目自带 RAGASEvaluator, LLM-as-judge = glm-5.3-flash）

阶段1 generate: 从 PubMedQA golden 取 N 条, 每条:
   - /api/v1/retrieve/hybrid (rerank=true, topK=5) 取真实检索 contexts
   - /api/v1/chat (kbIds=["19"]) 生成真实回答
   - groundTruth = 数据集标注的相关句拼接
阶段2 evaluate: 逐条调 /api/v1/evaluation/evaluate, 汇总四指标
"""
import json
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

import requests

BASE = "http://localhost:8080"
API = BASE + "/api/v1"
N = 20
GEN_CONC = 4
EVAL_CONC = 3
OUT = "/tmp/ragas_result.json"


def login():
    return requests.post(f"{API}/auth/login", json={"username": "admin", "password": "admin123"},
                         timeout=15).json()["token"]


def gen_one(token, i, item):
    q = item["question"]
    # 检索 contexts
    rr = requests.post(f"{API}/retrieve/hybrid", headers={"Authorization": f"Bearer {token}"},
                       json={"query": q, "kbIds": ["19"], "topK": 5, "rerank": True}, timeout=60)
    contexts = [x["content"] for x in (rr.json().get("results") or [])]
    # 生成回答
    t0 = time.time()
    cr = requests.post(f"{API}/chat", headers={"Authorization": f"Bearer {token}"},
                       json={"message": q, "kbIds": ["19"], "conversationId": f"ragas-eval-{i}"},
                       timeout=240)
    answer = (cr.json().get("message") or "").strip()
    return {
        "i": i, "question": q, "contexts": contexts, "answer": answer,
        "groundTruth": " ".join(item["golden_sentences"]),
        "gen_s": round(time.time() - t0, 1),
        "sources": len(cr.json().get("sources") or []),
    }


def eval_one(token, rec):
    t0 = time.time()
    rr = requests.post(f"{API}/evaluation/evaluate", headers={"Authorization": f"Bearer {token}"},
                       json={"question": rec["question"], "groundTruth": rec["groundTruth"],
                             "answer": rec["answer"], "contexts": rec["contexts"]},
                       timeout=400)
    d = rr.json()
    rec["metrics"] = d
    rec["eval_s"] = round(time.time() - t0, 1)
    return rec


def main():
    token = login()
    items = [json.loads(l) for l in open("/tmp/pubmedqa_golden.jsonl", encoding="utf-8")][:N]

    print(f"[gen] generating {len(items)} QA pairs (concurrency {GEN_CONC})...", flush=True)
    recs = []
    with ThreadPoolExecutor(max_workers=GEN_CONC) as ex:
        futs = {ex.submit(gen_one, token, i, it): i for i, it in enumerate(items)}
        for f in as_completed(futs):
            r = f.result()
            recs.append(r)
            print(f"[gen] q{r['i']}: answer {len(r['answer'])} chars, {r['sources']} sources, "
                  f"{r['contexts'] and len(r['contexts'])} contexts, {r['gen_s']}s", flush=True)
    recs.sort(key=lambda r: r["i"])
    bad = [r for r in recs if not r["answer"] or not r["contexts"]]
    print(f"[gen] done. unusable (empty answer/contexts): {len(bad)}", flush=True)

    print(f"[eval] scoring {len(recs)} records (concurrency {EVAL_CONC})...", flush=True)
    with ThreadPoolExecutor(max_workers=EVAL_CONC) as ex:
        futs = [ex.submit(eval_one, token, r) for r in recs]
        for f in as_completed(futs):
            r = f.result()
            m = r["metrics"]
            print(f"[eval] q{r['i']}: faith={m.get('faithfulness')} rel={m.get('answerRelevancy')} "
                  f"prec={m.get('contextPrecision')} recall={m.get('contextRecall')} "
                  f"ragas={m.get('ragasScore')} ({r['eval_s']}s)", flush=True)

    # 汇总
    ms = [r["metrics"] for r in recs if r.get("metrics")]
    agg = {}
    for k in ["faithfulness", "answerRelevancy", "contextPrecision", "contextRecall", "ragasScore"]:
        vals = sorted(x[k] for x in ms if isinstance(x.get(k), (int, float)))
        agg[k] = {"mean": round(sum(vals) / len(vals), 3), "median": round(vals[len(vals) // 2], 3),
                  "min": round(vals[0], 3), "max": round(vals[-1], 3), "n": len(vals)} if vals else None
    print("\n===== RAGAS 四指标汇总 =====", flush=True)
    print(json.dumps(agg, ensure_ascii=False, indent=2), flush=True)
    Path(OUT).write_text(json.dumps({"aggregate": agg, "records": recs}, ensure_ascii=False, indent=2))
    print(f"saved {OUT}", flush=True)


if __name__ == "__main__":
    main()
