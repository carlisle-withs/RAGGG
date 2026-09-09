#!/usr/bin/env python3
"""
CRUD-RAG Benchmark - 中文新闻 RAG 检索评测（最终版）

核心设计原则：
  1. 从 split_merged.json 的 QuestAnswer 任务中取前 N 条
  2. 将这 N 条的 news1/news2/news3 内容全部上传到知识库
  3. 评测这 N 条 QA 的检索质量
  4. 匹配策略：用 answer 中的实体词（"《...》"引用的名称、数字+单位等）与 chunk 内容做字符串包含判断

评测任务：QuestAnswer (1-doc / 2-docs / 3-docs)
指标：Hit@3, Hit@5, Hit@10, MRR, NDCG@3/5/10
"""
import json, os, sys, time, math, requests

BASE_URL = "http://localhost:8081"
API = "/api/v1"
DATA_DIR = r"D:\Workspace\RAGGG\stress-test\data\crud_rag"
KB_NAME = "CRUD-RAG-Benchmark"
TOP_K = 5
MAX_PER_TASK = 800  # 每任务最多 N 条（全部 800 条会很慢）
ADMIN_USER = "admin"
ADMIN_PASS = "admin"

# ============ 工具函数 ============

def login():
    r = requests.post(f"{BASE_URL}{API}/auth/login",
                      json={"username": ADMIN_USER, "password": ADMIN_PASS},
                      headers={"Content-Type": "application/json"}, timeout=10)
    if r.status_code == 200:
        return r.json().get("token")
    print(f"登录失败 {r.status_code}: {r.text[:100]}")
    return None

def list_kb(token):
    r = requests.get(f"{BASE_URL}{API}/knowledge-base",
                     headers={"Authorization": f"Bearer {token}"}, timeout=10)
    return r.json().get("records", []) if r.status_code == 200 else []

def create_kb(token, name):
    body = json.dumps({"name": name, "description": "CRUD-RAG 评测知识库",
                       "embeddingModel": "BAAI/bge-m3",
                       "chunkStrategy": "intelligent"})
    r = requests.post(f"{BASE_URL}{API}/knowledge-base", data=body,
                      headers={"Content-Type": "application/json",
                               "Authorization": f"Bearer {token}"}, timeout=15)
    return r.json() if r.status_code in (200, 201) else None

def delete_kb(token, kb_id):
    try:
        r = requests.delete(f"{BASE_URL}{API}/knowledge-base/{kb_id}",
                            headers={"Authorization": f"Bearer {token}"}, timeout=10)
        return r.status_code in (200, 204)
    except: return False

def upload_doc(content, filename, token, kb_id):
    files = {"file": (filename, content.encode("utf-8"), "text/plain")}
    try:
        r = requests.post(f"{BASE_URL}{API}/knowledge-base/{kb_id}/docs/upload",
                          files=files, data={"sourceType": "file"}, timeout=60)
        return r.status_code in (200, 201, 202)
    except: return False

def wait_done(token, kb_id, poll=5, max_wait=600):
    from pymilvus import connections, Collection
    print(f"等待文档处理（最多 {max_wait}s）...")
    start = time.time()
    last_doc = last_chunk = -1
    stable = 0
    while time.time() - start < max_wait:
        elapsed = int(time.time() - start)
        try:
            r = requests.get(f"{BASE_URL}{API}/knowledge-base/{kb_id}/docs",
                              params={"current": 1, "size": 1}, timeout=10,
                              headers={"Authorization": f"Bearer {token}"})
            doc_count = r.json().get("total", 0) if r.status_code == 200 else 0
        except: doc_count = 0
        try:
            connections.connect(host="localhost", port="29530", alias="default")
            c = Collection("rag_chunks")
            c.load()
            chunks = c.query(expr=f'kb_id == "{kb_id}"', output_fields=["chunk_id"], limit=16384)
            chunk_count = len(chunks)
            c.release()
        except: chunk_count = 0

        print(f"  [{elapsed:3d}s] 文档={doc_count} chunks={chunk_count}")
        if doc_count == last_doc and chunk_count == last_chunk:
            stable += 1
            if stable >= 3 and chunk_count > 0:
                print("  处理完成!")
                return True
        else: stable = 0
        last_doc = doc_count
        last_chunk = chunk_count
        time.sleep(poll)
    print("  等待超时")
    return False

def retrieve(query, token, kb_id, topk=TOP_K):
    body = json.dumps({"query": query, "kbIds": [str(kb_id)], "topK": topk}).encode()
    r = requests.post(f"{BASE_URL}{API}/retrieve", data=body, timeout=30,
                      headers={"Content-Type": "application/json",
                               "Authorization": f"Bearer {token}"})
    return r.json().get("results", []) if r.status_code == 200 else []

def retrieve_hybrid(query, token, kb_id, topk=TOP_K):
    body = json.dumps({"query": query, "kbIds": [str(kb_id)], "topK": topk}).encode()
    r = requests.post(f"{BASE_URL}{API}/retrieve/hybrid", data=body, timeout=30,
                      headers={"Content-Type": "application/json",
                               "Authorization": f"Bearer {token}"})
    return r.json().get("results", []) if r.status_code == 200 else []

def content_hit(chunk_content, answer_text):
    """判断 chunk 是否与 answer 有关联
    策略：提取 answer 中的实体词（引用名、数字+单位、专有名词），检查 chunk 是否包含其中任何一个
    这比 SequenceMatcher 更适合中文（避免字符集重叠导致的误判）"""
    if not chunk_content or not answer_text:
        return False

    # 提取 answer 中的关键实体
    keywords = []

    # 1. 引用名称：如"《防控儿童青少年近视核心知识十条》"、"《办法》"
    in_bracket = False
    bracket_content = ""
    for ch in answer_text:
        if ch == '"' or ch == '"':
            if not in_bracket:
                in_bracket = True
                bracket_content = ""
            else:
                if bracket_content:
                    keywords.append(bracket_content)
                in_bracket = False
        elif in_bracket:
            bracket_content += ch

    # 2. 带数字的名称：如"2023年7月28日"、"500万元"、"500套"
    import re
    for m in re.finditer(r'\d+[年月日亿元万元套辆个件次名]', answer_text):
        kw = m.group()
        if len(kw) >= 3:
            keywords.append(kw)

    # 3. 重要专业名词（2+ 字的非停用词）
    # 常见停用词
    stop = set(['的是', '是在', '和与', '以及', '对于', '为了', '可以', '这个', '那个', '因此', '但是', '而且', '或者', '什么', '哪些', '哪些', '哪些'])
    i = 0
    while i < len(answer_text):
        # 跳过标点
        if answer_text[i] in '，。、；：？！""''（）【】《》,.:;?!()[]':
            i += 1
            continue
        # 提取连续的中文字符序列
        j = i
        while j < len(answer_text) and '\u4e00' <= answer_text[j] <= '\u9fff':
            j += 1
        if j - i >= 2:
            phrase = answer_text[i:j]
            if phrase not in stop and '的' not in phrase[:2] and '了' not in phrase[:2]:
                keywords.append(phrase)
        i = max(i + 1, j)

    # 去重
    seen = set()
    unique_kws = [k for k in keywords if not (k in seen or seen.add(k))]

    if not unique_kws:
        return False

    # 只要有一个 keyword 在 chunk 中出现就算命中
    for kw in unique_kws:
        if kw in chunk_content:
            return True
    return False

# ============ 主流程 ============

def main():
    print("=" * 60)
    print("CRUD-RAG 检索评测 (QuestAnswer)")
    print("=" * 60)

    # Step 1: 加载数据
    print("\n[1/6] 加载 split_merged.json...")
    split_path = os.path.join(DATA_DIR, "split_merged.json")
    with open(split_path, encoding="utf-8") as f:
        data = json.load(f)

    print(f"  任务类型: {list(data.keys())}")
    for k in ['questanswer_1doc', 'questanswer_2docs', 'questanswer_3docs']:
        print(f"    {k}: {len(data.get(k, []))} 条")

    # Step 2: 解析 QuestAnswer QA（取前 MAX_PER_TASK 条）
    print(f"\n[2/6] 解析 QA 对（每任务最多 {MAX_PER_TASK} 条）...")
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
                    "ID": item.get("ID", ""),
                    "news": [item[k] for k in ["news1", "news2", "news3"] if item.get(k)],
                })
        qa_tasks[label] = parsed
        nc = len(parsed[0]["news"]) if parsed else 0
        print(f"  {label}: {len(parsed)} 条 QA（每条含 {nc} 篇文档）")

    total_qa = sum(len(v) for v in qa_tasks.values())
    print(f"  共 {total_qa} 条 QA")

    # Step 3: 构建 doc_map（从原始 data，不从 qa_tasks）
    print("\n[3/6] 构建文档列表...")
    doc_map = {}
    for key in ["questanswer_1doc", "questanswer_2docs", "questanswer_3docs"]:
        for item in data.get(key, [])[:MAX_PER_TASK]:
            for n_i in range(1, 4):
                nk = f"news{n_i}"
                if item.get(nk):
                    dk = f"{item.get('ID', '')}_{n_i}"
                    if dk not in doc_map and len(item[nk]) > 20:
                        doc_map[dk] = item[nk]
    print(f"  共 {len(doc_map)} 篇文档（去重后）")

    # Step 4: 登录 + 知识库
    print("\n[4/6] 初始化知识库...")
    token = login()
    if not token:
        sys.exit(1)
    print("  登录成功")

    # 删除旧知识库，创建新的
    kbs = list_kb(token)
    old_kb = next((k for k in kbs if k.get("name") == KB_NAME), None)
    if old_kb:
        print(f"  删除旧知识库 id={old_kb['id']}...")
        delete_kb(token, str(old_kb["id"]))
        time.sleep(2)

    kb = create_kb(token, KB_NAME)
    if not kb:
        print("  知识库创建失败!")
        sys.exit(1)
    kb_id = str(kb["id"])
    print(f"  新建知识库: id={kb_id} name={KB_NAME}")

    # Step 5: 上传文档
    print(f"\n[5/6] 上传 {len(doc_map)} 篇文档...")
    uploaded = errors = 0
    for i, (dk, content) in enumerate(doc_map.items()):
        if len(content) > 10000:
            content = content[:10000]
        fname = f"doc_{i:05d}.txt"
        if upload_doc(content, fname, token, kb_id):
            uploaded += 1
        else:
            errors += 1
        if (i + 1) % 200 == 0:
            print(f"  进度: {i+1}/{len(doc_map)}")
    print(f"  上传完成: {uploaded} 篇, {errors} 篇失败")

    # Step 6: 等待处理
    print()
    wait_done(token, kb_id)

    # Step 7: 评测
    print("\n[6/6] 检索评测...")
    all_results = {}

    for label, items in qa_tasks.items():
        print(f"\n  === {label} ({len(items)} 条) ===")
        if not items:
            continue

        for mode, retrieve_fn in [("vector", retrieve), ("hybrid", retrieve_hybrid)]:
            hit = {3: 0, 5: 0, 10: 0}
            mrr_sum = 0.0
            ndcg_sums = {3: 0.0, 5: 0.0, 10: 0.0}
            total_n = 0

            for item in items:
                answer = item["answer"]
                chunks = retrieve_fn(item["question"], token, kb_id, TOP_K)
                if not chunks:
                    continue

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
                    dcg = sum((1 if k_scores[i] else 0) / math.log2(i + 2) for i in range(len(k_scores)))
                    hit_count = sum(k_scores)
                    idcg = sum(1 / math.log2(i + 2) for i in range(hit_count)) if hit_count > 0 else 0
                    ndcg_sums[k] += dcg / idcg if idcg > 0 else 0.0

                total_n += 1

            if total_n == 0:
                continue

            results = {
                "n": total_n,
                "hit@3": hit[3] / total_n * 100,
                "hit@5": hit[5] / total_n * 100,
                "hit@10": hit[10] / total_n * 100,
                "mrr": mrr_sum / total_n * 100,
                "ndcg@3": ndcg_sums[3] / total_n * 100,
                "ndcg@5": ndcg_sums[5] / total_n * 100,
                "ndcg@10": ndcg_sums[10] / total_n * 100,
                "recall@5": hit[5] / total_n * 100,
            }
            all_results.setdefault(label, {})[mode] = results

            print(f"    {mode:8s} Hit@3={results['hit@3']:5.1f}% Hit@5={results['hit@5']:5.1f}% "
                  f"Hit@10={results['hit@10']:5.1f}% MRR={results['mrr']:5.1f}% "
                  f"NDCG@3={results['ndcg@3']:5.1f}% NDCG@5={results['ndcg@5']:5.1f}% "
                  f"NDCG@10={results['ndcg@10']:5.1f}% Recall@5={results['recall@5']:5.1f}%  (有效评测 {total_n} 条)")

    # Step 8: 汇总
    print("\n" + "=" * 60)
    print("📊 CRUD-RAG 检索评测汇总")
    print("=" * 60)
    hdr = f"{'任务':<10} {'模式':<8} {'Hit@3':>8} {'Hit@5':>8} {'Hit@10':>8} {'MRR':>8} {'NDCG@10':>10} {'Recall@5':>10}"
    print(hdr)
    print("-" * 60)

    all_vec = []
    all_hyb = []
    for label in ["1-doc", "2-docs", "3-docs"]:
        if label not in all_results:
            continue
        res = all_results[label]
        for mode, r in res.items():
            print(f"{label:<10} {mode:<8} {r['hit@3']:>7.1f}% {r['hit@5']:>7.1f}% "
                  f"{r['hit@10']:>7.1f}% {r['mrr']:>7.1f}% {r['ndcg@10']:>9.1f}% {r['recall@5']:>9.1f}%")
            if mode == "vector":
                all_vec.append(r)
            else:
                all_hyb.append(r)
        print()

    if all_vec:
        avg = lambda lst, k: sum(r[k] for r in lst) / len(lst)
        print("-" * 60)
        print(f"{'平均(vector)':<18} {avg(all_vec, 'hit@3'):>7.1f}% {avg(all_vec, 'hit@5'):>7.1f}% "
              f"{avg(all_vec, 'hit@10'):>7.1f}% {avg(all_vec, 'mrr'):>7.1f}% "
              f"{avg(all_vec, 'ndcg@10'):>9.1f}% {avg(all_vec, 'recall@5'):>9.1f}%")

    if all_hyb:
        print(f"{'平均(hybrid)':<18} {avg(all_hyb, 'hit@3'):>7.1f}% {avg(all_hyb, 'hit@5'):>7.1f}% "
              f"{avg(all_hyb, 'hit@10'):>7.1f}% {avg(all_hyb, 'mrr'):>7.1f}% "
              f"{avg(all_hyb, 'ndcg@10'):>9.1f}% {avg(all_hyb, 'recall@5'):>9.1f}%")

    print("\n评测完成!")

if __name__ == "__main__":
    main()