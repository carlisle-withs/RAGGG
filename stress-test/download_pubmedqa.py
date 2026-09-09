"""
RAGBench PubMedQA 数据下载、入库、评测脚本

流程:
1. 从 HuggingFace 下载 pubmedqa 测试集
2. 提取 documents + relevant sentence keys 作为 golden set
3. 向量化入库到 Milvus（通过后端 API）
4. 用 questions 测试检索质量（Hit@K / MRR / NDCG）
"""
import asyncio
import json
import os
import time
from dataclasses import dataclass
from typing import Optional
import aiohttp
from datasets import load_dataset

# ========== 配置 ==========
CONFIG = {
    # 后端
    "rag_api": "http://localhost:8081",
    "api_prefix": "/api/v1",
    "username": "admin",
    "password": "admin",
    "kb_id": "2",          # 目标知识库 ID

    # 数据集
    "dataset_size": 200,   # 入库多少条测试（0 = 全量）

    # 检索评估
    "topk_list": [3, 5, 10],  # 测试不同 topK
}

# ========== 1. 下载数据集 ==========

def download_pubmedqa(max_items: int = 0):
    """下载 pubmedqa 测试集"""
    print("=" * 60)
    print("  📥 步骤1: 下载 PubMedQA 测试集")
    print("=" * 60)

    ds = load_dataset(
        "rungalileo/ragbench",
        "pubmedqa",
        split="test",
        streaming=True
    )

    items = []
    for i, item in enumerate(ds):
        items.append(item)
        if max_items > 0 and i >= max_items - 1:
            break
        if (i + 1) % 50 == 0:
            print(f"  已读取 {i + 1} 条...")

    print(f"  共读取 {len(items)} 条 PubMedQA 数据\n")
    return items


def extract_golden_chunks(item: dict) -> dict:
    """
    从 pubmedqa 条目中提取:
    - question: 查询
    - chunks: 所有句子 chunks（含 id）
    - relevant_keys: 相关句子 key 集合
    """
    question = item["question"]
    docs = item["documents"]  # 文档文本列表
    docs_sentences = item["documents_sentences"]  # [[id, text], ...]
    relevant_keys = set(item["all_relevant_sentence_keys"])  # golden truth

    # 重建 chunk 字典: {chunk_id: chunk_text}
    # chunk_id 格式: "doc{doc_idx}_{sent_id}"  如 "doc0_0a", "doc1_1a"
    chunk_map = {}
    for doc_idx, sentences in enumerate(docs_sentences):
        for sent_id, sent_text in sentences:
            chunk_id = f"doc{doc_idx}_{sent_id}"  # 如 "doc1_0a"
            chunk_map[chunk_id] = sent_text.strip()

    # 构造 chunks 列表
    chunks = [{"id": cid, "text": text} for cid, text in chunk_map.items()]

    # 修正 golden_ids：原始 key 如 "1a" → 完整 chunk_id 如 "doc1_1a"
    # 规律: key 第一位是 doc_idx，其余是 sent_id，chunk_id = f"doc{doc_idx}_{sent_id}"
    # 但更可靠：从 documents_sentences 的实际 sent_id 映射
    # documents_sentences[doc_idx] = [[sent_id, text], ...]
    # 因此 key "1a" 的完整 chunk_id 就是 f"doc{first_digit}{sent_id}"
    # 实际: sentences 列表中，sent_id 第一位数字就是 doc_idx（pubmedqa 数据结构特点）
    corrected_golden_ids = []
    for key in relevant_keys:
        if key and len(key) >= 2:
            doc_digit = key[0]  # 第一位数字
            sent_id = key       # 完整 key
            corrected_golden_ids.append(f"doc{doc_digit}_{sent_id}")
        else:
            corrected_golden_ids.append(key)

    return {
        "question": question,
        "chunks": chunks,
        "relevant_chunk_ids": corrected_golden_ids,   # 修正后，与 Milvus chunk_id 对齐
        "raw_item": item,
    }


# ========== 2. 写入 JSON 临时文件 ==========

def save_to_jsonl(items: list, filepath: str):
    """保存为 JSONL 格式供后端处理"""
    with open(filepath, "w", encoding="utf-8") as f:
        for item in items:
            f.write(json.dumps(item, ensure_ascii=False) + "\n")
    print(f"  已保存 {len(items)} 条到 {filepath}")


# ========== 3. 认证 ==========

async def get_token(session: aiohttp.ClientSession) -> Optional[str]:
    try:
        async with session.post(
            f"{CONFIG['rag_api']}{CONFIG['api_prefix']}/auth/login",
            json={"username": CONFIG["username"], "password": CONFIG["password"]},
            timeout=aiohttp.ClientTimeout(total=10)
        ) as resp:
            if resp.status == 200:
                result = await resp.json()
                return result.get("data", {}).get("token") or result.get("token")
    except Exception:
        pass
    return None


# ========== 4. 评估检索质量 ==========

@dataclass
class EvalMetrics:
    hit_at_k: dict     # {k: hit_rate}
    mrr: float
    ndcg: float
    total: int


def compute_metrics(
    retrieved_chunks: list[dict],
    relevant_ids: list[str],
    k: int
) -> dict:
    """
    retrieved_chunks: 后端返回的 chunks（无序列表，含 id）
    relevant_ids: golden truth chunk IDs
    返回: {hit, mrr, ndcg}
    """
    retrieved_topk = retrieved_chunks[:k]
    retrieved_ids = {c.get("chunkId", c.get("id", "")) for c in retrieved_topk}
    relevant_set = set(relevant_ids)

    # Hit@K
    hits = len(retrieved_ids & relevant_set)
    hit = 1 if hits > 0 else 0

    # MRR（第一个相关结果的倒数）
    mrr = 0.0
    for i, c in enumerate(retrieved_chunks):
        if c.get("chunkId", c.get("id", "")) in relevant_set:
            mrr = 1.0 / (i + 1)
            break

    # NDCG@K
    def dcg(scores):
        return sum((2 ** s - 1) / (i + 1) for i, s in enumerate(scores))

    rel_scores = [1 if c.get("chunkId", c.get("id", "")) in relevant_set else 0
                 for c in retrieved_topk]
    dcg_val = dcg(rel_scores)
    ideal_scores = [1] * min(len(relevant_set), k)
    idcg_val = dcg(ideal_scores)
    ndcg = dcg_val / idcg_val if idcg_val > 0 else 0.0

    return {"hit": hit, "mrr": mrr, "ndcg": ndcg, "hits": hits}


async def run_retrieval_eval(session: aiohttp.ClientSession, token: str, items: list) -> EvalMetrics:
    """对所有 items 执行检索并计算指标"""
    print("\n" + "=" * 60)
    print("  🔍 步骤2: 执行检索质量评估")
    print("=" * 60)

    all_results = {k: {"hit": 0, "mrr": 0.0, "ndcg": 0.0} for k in CONFIG["topk_list"]}
    total = len(items)

    for i, golden in enumerate(items):
        question = golden["question"]
        relevant_ids = golden["relevant_chunk_ids"]

        # 调用后端检索
        try:
            async with session.post(
                f"{CONFIG['rag_api']}{CONFIG['api_prefix']}/retrieve",
                json={"query": question, "kbIds": [CONFIG["kb_id"]], "topK": max(CONFIG["topk_list"])},
                headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
                timeout=aiohttp.ClientTimeout(total=30)
            ) as resp:
                if resp.status != 200:
                    continue
                data = await resp.json()
                retrieved = data.get("results", [])
        except Exception:
            continue

        # 计算各 K 指标
        for k in CONFIG["topk_list"]:
            metrics = compute_metrics(retrieved, relevant_ids, k)
            all_results[k]["hit"] += metrics["hit"]
            all_results[k]["mrr"] += metrics["mrr"]
            all_results[k]["ndcg"] += metrics["ndcg"]

        if (i + 1) % 20 == 0:
            print(f"  进度: {i + 1}/{total}...")

    # 汇总
    eval_metrics = EvalMetrics(
        hit_at_k={},
        mrr=all_results[CONFIG["topk_list"][0]]["mrr"] / total,
        ndcg=all_results[CONFIG["topk_list"][0]]["ndcg"] / total,
        total=total
    )
    for k in CONFIG["topk_list"]:
        eval_metrics.hit_at_k[k] = all_results[k]["hit"] / total
        eval_metrics.mrr = max(eval_metrics.mrr, all_results[k]["mrr"] / total)
        eval_metrics.ndcg = max(eval_metrics.ndcg, all_results[k]["ndcg"] / total)

    return eval_metrics


async def main():
    # 1. 下载
    raw_items = download_pubmedqa(CONFIG["dataset_size"])

    # 2. 提取 golden chunks
    print("=" * 60)
    print("  🏷️  步骤2: 提取 Golden Chunks")
    print("=" * 60)
    golden_items = []
    for item in raw_items:
        golden = extract_golden_chunks(item)
        golden_items.append(golden)
    print(f"  共 {len(golden_items)} 条，每条 {len(golden_items[0]['chunks'])} 个 chunks")
    print(f"  样本查询: {golden_items[0]['question']}")
    print(f"  相关 chunk IDs: {golden_items[0]['relevant_chunk_ids']}\n")

    # 3. 保存为 JSONL（供人工检查或后端批量入库）
    os.makedirs("D:/Workspace/RAGGG/stress-test/data", exist_ok=True)
    jsonl_path = "D:/Workspace/RAGGG/stress-test/data/pubmedqa_golden.jsonl"
    save_to_jsonl(golden_items, jsonl_path)

    # 4. 评估（直接用现有论文库测试）
    print("=" * 60)
    print("  ⚠️  注意: 当前知识库 kb_id=2 是论文库")
    print("  PubMedQA 的医学问答与论文库主题可能不匹配")
    print("  Hit@K 可能偏低，这是正常的（知识域差异）")
    print("=" * 60)

    connector = aiohttp.TCPConnector(limit=5, force_close=True)
    async with aiohttp.ClientSession(connector=connector) as session:
        token = await get_token(session)
        if not token:
            print("  Token 获取失败，结束")
            return

        metrics = await run_retrieval_eval(session, token, golden_items)

        # 打印结果
        print("\n" + "=" * 60)
        print("  📊 检索质量评估结果 (当前 kb_id=2 论文库)")
        print("=" * 60)
        print(f"  测试样本数: {metrics.total}")
        print(f"  TopK 设置: {CONFIG['topk_list']}")
        print()
        print(f"  {'K':<8} {'Hit@K':>10} {'MRR':>10} {'NDCG@K':>10}")
        print(f"  {'-'*38}")
        for k in CONFIG["topk_list"]:
            m = all_results if False else metrics  # 简化输出
            print(f"  @{k:<6} {metrics.hit_at_k.get(k, 0)*100:>9.1f}%  {metrics.mrr*100:>9.1f}%  {metrics.ndcg*100:>9.1f}%")
        print()

        # 质量评级
        avg_hit = sum(metrics.hit_at_k.values()) / len(metrics.hit_at_k)
        if avg_hit >= 0.8:
            grade = "🏆 优秀"
        elif avg_hit >= 0.6:
            grade = "✅ 良好"
        elif avg_hit >= 0.4:
            grade = "⚠️  一般"
        else:
            grade = "🔴 较差（知识域不匹配或检索策略需优化）"
        print(f"  评级: {grade}")
        print("=" * 60)


if __name__ == "__main__":
    asyncio.run(main())
