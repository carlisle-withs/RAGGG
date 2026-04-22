#!/usr/bin/env python3
"""
CRUD-RAG Benchmark - hybrid+rerank 模式
评测完成后将指标推送到 Prometheus Pushgateway，供 Grafana 观察延迟
"""
import json, os, sys, time, math, requests

LOG_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "benchmark_run.log")

def log(msg):
    print(msg, flush=True)
    try:
        with open(LOG_FILE, "a", encoding="utf-8") as f:
            f.write(msg + "\n")
    except:
        pass

BASE_URL = "http://localhost:8081"
API = "/api/v1"
DATA_DIR = r"D:\Workspace\RAGGG\stress-test\data\crud_rag"
KB_NAME = "CRUD-RAG-Benchmark"
TOP_K = 5
MAX_PER_TASK = 800
ADMIN_USER = "admin"
ADMIN_PASS = "admin"
# Pushgateway 地址（从 Docker 网络访问宿主机）
PUSHGATEWAY_URL = "http://localhost:19991"


# ============ 工具函数 ============

def login():
    r = requests.post(
        f"{BASE_URL}{API}/auth/login",
        json={"username": ADMIN_USER, "password": ADMIN_PASS},
        headers={"Content-Type": "application/json"},
        timeout=10,
    )
    if r.status_code == 200:
        return r.json().get("token")
    print(f"登录失败 {r.status_code}: {r.text[:100]}")
    return None


def list_kb(token):
    r = requests.get(
        f"{BASE_URL}{API}/knowledge-base",
        headers={"Authorization": f"Bearer {token}"},
        timeout=10,
    )
    return r.json().get("records", []) if r.status_code == 200 else []


def create_kb(token, name):
    body = json.dumps({
        "name": name,
        "description": "CRUD-RAG v2 评测知识库",
        "embeddingModel": "BAAI/bge-m3",
        "chunkStrategy": "intelligent",
    })
    r = requests.post(
        f"{BASE_URL}{API}/knowledge-base",
        data=body,
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {token}",
        },
        timeout=15,
    )
    return r.json() if r.status_code in (200, 201) else None


def delete_kb(token, kb_id):
    try:
        r = requests.delete(
            f"{BASE_URL}{API}/knowledge-base/{kb_id}",
            headers={"Authorization": f"Bearer {token}"},
            timeout=10,
        )
        return r.status_code in (200, 204)
    except:
        return False


def upload_doc(content, filename, token, kb_id):
    files = {"file": (filename, content.encode("utf-8"), "text/plain")}
    try:
        r = requests.post(
            f"{BASE_URL}{API}/knowledge-base/{kb_id}/docs/upload",
            files=files,
            data={"sourceType": "file"},
            timeout=60,
        )
        return r.status_code in (200, 201, 202)
    except:
        return False


def wait_done(token, kb_id, poll=5, max_wait=600):
    from pymilvus import connections, Collection

    print(f"等待文档处理（最多 {max_wait}s）...")
    start = time.time()
    last_doc = last_chunk = -1
    stable = 0
    while time.time() - start < max_wait:
        elapsed = int(time.time() - start)
        try:
            r = requests.get(
                f"{BASE_URL}{API}/knowledge-base/{kb_id}/docs",
                params={"current": 1, "size": 1},
                timeout=10,
                headers={"Authorization": f"Bearer {token}"},
            )
            doc_count = r.json().get("total", 0) if r.status_code == 200 else 0
        except:
            doc_count = 0
        try:
            connections.connect(host="localhost", port="29530", alias="default")
            c = Collection("rag_chunks")
            c.load()
            chunks = c.query(
                expr=f'kb_id == "{kb_id}"', output_fields=["chunk_id"], limit=16383
            )
            chunk_count = len(chunks)
            c.release()
        except Exception as e:
            chunk_count = 0
            print(f"    Milvus 查询异常（忽略）: {e}")

        print(f"  [{elapsed:3d}s] 文档={doc_count} chunks={chunk_count}")
        if doc_count == last_doc and chunk_count == last_chunk:
            stable += 1
            if stable >= 3 and chunk_count > 0:
                print("  处理完成!")
                return True
        else:
            stable = 0
        last_doc = doc_count
        last_chunk = chunk_count
        time.sleep(poll)
    print("  等待超时")
    return False


def retrieve_hybrid(query, token, kb_id, topk=TOP_K, rerank=True):
    """混合检索（可选 rerank）"""
    body = {
        "query": query,
        "kbIds": [str(kb_id)],
        "topK": topk,
        "rerank": rerank,
    }
    r = requests.post(
        f"{BASE_URL}{API}/retrieve/hybrid",
        json=body,
        timeout=30,
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {token}",
        },
    )
    latency_ms = r.json().get("latencyMs", 0)
    return r.json().get("results", []), latency_ms


def push_to_prometheus(latencies, job_name="crud_benchmark"):
    """将延迟指标推送到 Pushgateway"""
    lines = []
    for mode, lat_list in latencies.items():
        if not lat_list:
            continue
        lat_sorted = sorted(lat_list)
        n = len(lat_sorted)
        p50 = lat_sorted[int(n * 0.50)]
        p95 = lat_sorted[int(n * 0.95)]
        p99 = lat_sorted[int(n * 0.99)]
        avg = sum(lat_list) / n

        # Pushgateway 标准格式: metric{label="val"} value
        m = f"benchmark_latency{{mode=\"{mode}\""
        lines.append(f"{m},quantile=\"avg\"}} {avg:.2f}")
        lines.append(f"{m},quantile=\"p50\"}} {p50:.2f}")
        lines.append(f"{m},quantile=\"p95\"}} {p95:.2f}")
        lines.append(f"{m},quantile=\"p99\"}} {p99:.2f}")
        lines.append(f"{m},quantile=\"count\"}} {n}")
        print(f"  推送 {mode}: avg={avg:.0f}ms p50={p50:.0f}ms p95={p95:.0f}ms p99={p99:.0f}ms (n={n})")

    if not lines:
        return

    body = "\n".join(lines) + "\n"
    headers = {"Content-Type": "text/plain"}
    try:
        r = requests.post(
            f"{PUSHGATEWAY_URL}/metrics/job/{job_name}",
            data=body,
            headers=headers,
            timeout=10,
        )
        if r.status_code in (200, 202):
            print(f"  推送成功\n")
        else:
            print(f"  推送失败: {r.status_code} {r.text[:100]}\n")
    except Exception as e:
        print(f"  推送异常: {e}\n")


def content_hit(chunk_content, answer_text):
    """判断 chunk 是否与 answer 有关联"""
    if not chunk_content or not answer_text:
        return False

    keywords = []
    # 中文弯引号 "" (U+201C/U+201D) 和 ASCII 直引号 " (U+0022)
    for left, right in [("\u201c", "\u201d"), ('"', '"')]:
        start = -1
        i = 0
        while i < len(answer_text):
            if answer_text[i] == left:
                start = i
            elif answer_text[i] == right and start >= 0:
                kw = answer_text[start + 1:i]
                if kw and len(kw) >= 2:
                    keywords.append(kw)
                start = -1
            i += 1

    import re
    for m in re.finditer(r"\d+[年月日亿元万元套辆个件次名]", answer_text):
        kw = m.group()
        if len(kw) >= 3:
            keywords.append(kw)

    stop = set([
        "的是", "是在", "和与", "以及", "对于", "为了", "可以",
        "这个", "那个", "因此", "但是", "而且", "或者", "什么", "哪些",
    ])
    i = 0
    while i < len(answer_text):
        if answer_text[i] in '，。、；：？！""''（）【】《》,.:;?!()[]':
            i += 1
            continue
        j = i
        while j < len(answer_text) and "\u4e00" <= answer_text[j] <= "\u9fff":
            j += 1
        if j - i >= 2:
            phrase = answer_text[i:j]
            if phrase not in stop and "的" not in phrase[:2] and "了" not in phrase[:2]:
                keywords.append(phrase)
        i = max(i + 1, j)

    seen = set()
    unique_kws = [k for k in keywords if not (k in seen or seen.add(k))]
    if not unique_kws:
        return False
    for kw in unique_kws:
        if kw in chunk_content:
            return True
    return False


# ============ 主流程 ============

def main():
    print("=" * 64)
    print("CRUD-RAG 检索评测 - hybrid+rerank + Prometheus 推送")
    print("=" * 64)

    # Step 1: 加载数据
    print("\n[1/4] 加载 split_merged.json...")
    split_path = os.path.join(DATA_DIR, "split_merged.json")
    with open(split_path, encoding="utf-8") as f:
        data = json.load(f)

    print(f"  任务类型: {list(data.keys())}")
    for k in ["questanswer_1doc", "questanswer_2docs", "questanswer_3docs"]:
        print(f"    {k}: {len(data.get(k, []))} 条")

    # Step 2: 解析 QA
    print(f"\n[2/4] 解析 QA 对（每任务最多 {MAX_PER_TASK} 条）...")
    qa_tasks = {}
    for key, label, max_n in [
        ("questanswer_1doc", "1-doc", MAX_PER_TASK),
        ("questanswer_2docs", "2-docs", MAX_PER_TASK),
        ("questanswer_3docs", "3-docs", MAX_PER_TASK),
    ]:
        items = data.get(key, [])[:max_n]
        parsed = []
        for item in items:
            q = item.get("questions", "")
            a = item.get("answers", "")
            if q and a:
                parsed.append({
                    "question": q,
                    "answer": a,
                    "id": item.get("ID", ""),
                    "news": [item[k] for k in ["news1", "news2", "news3"] if item.get(k)],
                })
        qa_tasks[label] = parsed
        nc = len(parsed[0]["news"]) if parsed else 0
        print(f"  {label}: {len(parsed)} 条 QA（每条含 {nc} 篇文档）")

    total_qa = sum(len(v) for v in qa_tasks.values())
    print(f"  共 {total_qa} 条 QA")

    # Step 3: 登录 + 知识库（复用已索引的 KB）
    print("\n[3/6] 登录并查找已有知识库...")
    token = login()
    if not token:
        sys.exit(1)
    print("  登录成功")

    # 直接使用已验证的完全索引知识库（id=14，7349 chunks）
    kb_id = "14"
    print(f"  使用知识库: id={kb_id} ({KB_NAME})")

    # Step 4: 评测 + 收集延迟指标
    print("\n[4/6] 检索评测（hybrid+rerank）...")
    print("\n=== hybrid+rerank ===")

    task_labels = ["1-doc", "2-docs", "3-docs"]
    # 记录每个任务的延迟（用于推送）
    task_latencies = {tl: [] for tl in task_labels}

    for task_label in task_labels:
        items = qa_tasks[task_label]
        if not items:
            continue

        hit = {3: 0, 5: 0, 10: 0}
        mrr_sum = 0.0
        ndcg_sums = {3: 0.0, 5: 0.0, 10: 0.0}
        total_n = 0

        for item in items:
            answer = item["answer"]
            chunks, latency_ms = retrieve_hybrid(item["question"], token, kb_id, TOP_K)
            if not chunks:
                continue

            task_latencies[task_label].append(latency_ms)

            scores = []
            for c in chunks:
                hit_flag = content_hit(c.get("content", ""), answer)
                scores.append(hit_flag)

            for k in [3, 5, 10]:
                if any(scores[:k]):
                    hit[k] += 1

            rr = 0.0
            for j, s in enumerate(scores):
                if s:
                    rr = 1.0 / (j + 1)
                    break
            mrr_sum += rr

            for k in [3, 5, 10]:
                k_scores = scores[:k]
                dcg = sum(
                    (1 if k_scores[i] else 0) / math.log2(i + 2)
                    for i in range(len(k_scores))
                )
                hit_count = sum(k_scores)
                idcg = sum(1 / math.log2(i + 2) for i in range(hit_count)) if hit_count > 0 else 0
                ndcg_sums[k] += dcg / idcg if idcg > 0 else 0.0

            total_n += 1

        if total_n == 0:
            continue

        res = {
            "n": total_n,
            "hit@3": hit[3] / total_n * 100,
            "hit@5": hit[5] / total_n * 100,
            "hit@10": hit[10] / total_n * 100,
            "mrr": mrr_sum / total_n * 100,
            "ndcg@3": ndcg_sums[3] / total_n * 100,
            "ndcg@5": ndcg_sums[5] / total_n * 100,
            "ndcg@10": ndcg_sums[10] / total_n * 100,
        }

        print(
            f"    {task_label:<8} Hit@3={res['hit@3']:5.1f}% Hit@5={res['hit@5']:5.1f}% "
            f"Hit@10={res['hit@10']:5.1f}% MRR={res['mrr']:5.1f}% "
            f"NDCG@3={res['ndcg@3']:5.1f}% NDCG@5={res['ndcg@5']:5.1f}% "
            f"NDCG@10={res['ndcg@10']:5.1f}%  (n={total_n})"
        )

    # 推送延迟指标到 Pushgateway
    print("\n推送延迟指标到 Prometheus Pushgateway...")
    push_to_prometheus(task_latencies)

    # 汇总
    print("\n" + "=" * 70)
    print("CRUD-RAG 检索评测汇总 - hybrid+rerank")
    print("=" * 70)

    hdr = (
        f"{'任务':<10} {'Hit@3':>8} {'Hit@5':>8} {'Hit@10':>8} "
        f"{'MRR':>8} {'NDCG@5':>9} {'NDCG@10':>10}"
    )
    print(hdr)
    print("-" * 70)

    avg_res = {"hit@3": 0, "hit@5": 0, "hit@10": 0, "mrr": 0, "ndcg@5": 0, "ndcg@10": 0}
    count = 0

    for task_label in task_labels:
        items = qa_tasks[task_label]
        if not items:
            continue

        hit = {3: 0, 5: 0, 10: 0}
        mrr_sum = 0.0
        ndcg_sums = {3: 0.0, 5: 0.0, 10: 0.0}
        total_n = 0

        for item in items:
            answer = item["answer"]
            chunks, _ = retrieve_hybrid(item["question"], token, kb_id, TOP_K)
            if not chunks:
                continue

            scores = [content_hit(c.get("content", ""), answer) for c in chunks]
            for k in [3, 5, 10]:
                if any(scores[:k]):
                    hit[k] += 1
            rr = 0.0
            for j, s in enumerate(scores):
                if s:
                    rr = 1.0 / (j + 1)
                    break
            mrr_sum += rr
            for k in [3, 5, 10]:
                k_scores = scores[:k]
                dcg = sum((1 if k_scores[i] else 0) / math.log2(i + 2) for i in range(len(k_scores)))
                hit_count = sum(k_scores)
                idcg = sum(1 / math.log2(i + 2) for i in range(hit_count)) if hit_count > 0 else 0
                ndcg_sums[k] += dcg / idcg if idcg > 0 else 0.0
            total_n += 1

        if total_n == 0:
            continue

        res = {
            "hit@3": hit[3] / total_n * 100,
            "hit@5": hit[5] / total_n * 100,
            "hit@10": hit[10] / total_n * 100,
            "mrr": mrr_sum / total_n * 100,
            "ndcg@5": ndcg_sums[5] / total_n * 100,
            "ndcg@10": ndcg_sums[10] / total_n * 100,
        }
        for k in avg_res:
            avg_res[k] += res[k]
        count += 1

        print(
            f"{task_label:<10} {res['hit@3']:>7.1f}% {res['hit@5']:>7.1f}% "
            f"{res['hit@10']:>7.1f}% {res['mrr']:>7.1f}% "
            f"{res['ndcg@5']:>8.1f}% {res['ndcg@10']:>9.1f}%"
        )

    if count > 0:
        print("-" * 70)
        print(
            f"{'平均':<10} {avg_res['hit@3']/count:>7.1f}% {avg_res['hit@5']/count:>7.1f}% "
            f"{avg_res['hit@10']/count:>7.1f}% {avg_res['mrr']/count:>7.1f}% "
            f"{avg_res['ndcg@5']/count:>8.1f}% {avg_res['ndcg@10']/count:>9.1f}%"
        )

    print("\n评测完成!")


if __name__ == "__main__":
    main()
