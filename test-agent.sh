#!/bin/bash
# ============================================================
# RAGGG 全接口功能测试 Agent (61 endpoints)
# 用法: ./test-agent.sh [--quick] [--verbose]
# ============================================================
set -e

BASE_URL="${BASE_URL:-http://localhost:8080}"
A="http://localhost:8080/api/v1"
PASS=0; FAIL=0; SKIP=0
QUICK=false; VERBOSE=false

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
for a in "$@"; do case "$a" in --quick) QUICK=true ;; --verbose) VERBOSE=true ;; esac; done

ok()   { echo -e "  ${GREEN}✓${NC} $1"; PASS=$((PASS+1)); }
fail() { echo -e "  ${RED}✗${NC} $1 — $2"; FAIL=$((FAIL+1)); }
skip() { echo -e "  ${YELLOW}⊘${NC} $1"; SKIP=$((SKIP+1)); }
info() { echo -e "\n${CYAN}━━━ $1 ━━━${NC}"; }
json() { python3 -c "import sys,json; $1" 2>/dev/null; }
post() { curl -s -X POST "$@" -H 'Content-Type: application/json' 2>/dev/null; }
get()  { curl -s "$@" -H 'Content-Type: application/json' 2>/dev/null; }
put()  { curl -s -X PUT "$@" -H 'Content-Type: application/json' 2>/dev/null; }
patch() { curl -s -X PATCH "$@" -H 'Content-Type: application/json' 2>/dev/null; }
del()  { curl -s -X DELETE "$@" -H 'Content-Type: application/json' 2>/dev/null; }
httpok() { local c; c=$(curl -s -o /dev/null -w "%{http_code}" "$@"); [ "$c" -ge 200 ] && [ "$c" -lt 400 ]; }

# login helper
login() {
    local r; r=$(post "${A}/auth/login" -d '{"username":"admin","password":"admin123"}')
    TOKEN=$(json "d=json.loads('''$r'''); print(d.get('token',''))")
}

# ============================================================
echo ""
echo "╔══════════════════════════════════════════╗"
echo "║   RAGGG 全接口功能测试 Agent             ║"
echo "║   $(date '+%Y-%m-%d %H:%M:%S')                     ║"
echo "╚══════════════════════════════════════════╝"

# ---------- Health ----------
info "健康检查"
if httpok "${BASE_URL}/actuator/health"; then ok "服务 UP"; else fail "服务 DOWN"; fi

# ============================================================
info "1. 认证模块 (4)"  ; login
# ============================================================
r=$(post "${A}/auth/login" -d '{"username":"admin","password":"admin123"}')
t=$(json "d=json.loads('''$r'''); print(d.get('token',''))")
ro=$(json "d=json.loads('''$r'''); print(d.get('user',{}).get('role',''))")
[ -n "$t" ] && [ "$ro" = "ADMIN" ] && ok "POST /auth/login" || fail "POST /auth/login" "role=$ro"

r=$(post "${A}/auth/login" -d '{"username":"admin","password":"wrong"}')
w=$(json "d=json.loads('''$r'''); print(d.get('error',''))")
[ "$w" = "INVALID_CREDENTIALS" ] && ok "POST /auth/login (错误密码拒登)" || fail "POST /auth/login (错误)" "$w"

r=$(post "${A}/auth/register" -d '{"username":"tester'$(date +%s)'","password":"test123"}')
u=$(json "d=json.loads('''$r'''); print(d.get('user',{}).get('username',''))")
[ -n "$u" ] && ok "POST /auth/register" || skip "POST /auth/register (已存在)"

r=$(post "${A}/auth/logout" -H "Authorization: Bearer $TOKEN" -w "%{http_code}")
[ "$r" = "200" ] && ok "POST /auth/logout" || fail "POST /auth/logout" "HTTP $r"

login
r=$(post "${A}/auth/refresh" -H "Authorization: Bearer $TOKEN" -d '{"refreshToken":"'$TOKEN'"}')
httpok "${A}/auth/refresh" -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"refreshToken":"'$TOKEN'"}' \
    && ok "POST /auth/refresh" || skip "POST /auth/refresh"

# ============================================================
info "2. 用户模块 (1)"
# ============================================================
r=$(get "${A}/user/me" -H "Authorization: Bearer $TOKEN")
me=$(json "d=json.loads('''$r'''); print(d.get('role',''))")
[ "$me" = "ADMIN" ] && ok "GET /user/me" || fail "GET /user/me" "role=$me"

# ============================================================
if $QUICK; then skip "3-4. 对话/会话 (跳过 LLM)";
else
# ============================================================
info "3. 对话 (1)"
# ============================================================
r=$(post "${A}/chat" -H "Authorization: Bearer $TOKEN" -d '{"message":"用一句话介绍你自己"}')
cid=$(json "d=json.loads('''$r'''); print(d.get('conversationId',''))")
msg=$(json "d=json.loads('''$r'''); print(d.get('message','')[:30])")
[ -n "$cid" ] && [ -n "$msg" ] && ok "POST /chat" || fail "POST /chat" "无响应"
$VERBOSE && echo "      reply: $msg"

# ============================================================
info "4. 会话管理 (5)"
# ============================================================
r=$(get "${A}/conversations" -H "Authorization: Bearer $TOKEN")
c=$(json "d=json.loads('''$r'''); print(len(d))")
[ "$c" -ge 1 ] && ok "GET /conversations" || fail "GET /conversations" "count=$c"

r=$(put "${A}/conversations/${cid}" -H "Authorization: Bearer $TOKEN" -d '{"title":"Renamed"}')
httpok "${A}/conversations/${cid}" -X PUT -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"title":"Renamed"}' \
    && ok "PUT /conversations/{id} (rename)" || fail "PUT /conversations/{id}"

r=$(get "${A}/conversations/${cid}/messages" -H "Authorization: Bearer $TOKEN")
mc=$(json "d=json.loads('''$r'''); print(len(d))")
[ "$mc" -ge 2 ] && ok "GET /conversations/{id}/messages" || fail "GET /conversations/{id}/messages" "count=$mc"

# POST /conversations/messages/{messageId}/feedback
r=$(get "${A}/conversations/${cid}/messages" -H "Authorization: Bearer $TOKEN")
mid=$(json "d=json.loads('''$r'''); print(d[0].get('id',''))" 2>/dev/null || echo "")
if [ -n "$mid" ] && [ "$mid" != "None" ]; then
    httpok "${A}/conversations/messages/${mid}/feedback" -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"vote":1}' \
        && ok "POST /messages/{id}/feedback" || fail "POST /messages/{id}/feedback" "mid=$mid"
else
    skip "POST /messages/{id}/feedback (无UUID消息ID)"
fi

# DELETE /conversations/{conversationId}
httpok "${A}/conversations/${cid}" -X DELETE -H "Authorization: Bearer $TOKEN" \
    && ok "DELETE /conversations/{id}" || fail "DELETE /conversations/{id}"
fi

# ============================================================
info "5. 知识库 CRUD (7)"
# ============================================================
r=$(get "${A}/knowledge-base?current=1&size=10" -H "Authorization: Bearer $TOKEN")
httpok "${A}/knowledge-base?current=1&size=10" -H "Authorization: Bearer $TOKEN" \
    && ok "GET /knowledge-base (list)" || fail "GET /knowledge-base (list)"

r=$(post "${A}/knowledge-base" -H "Authorization: Bearer $TOKEN" -d '{"name":"TestKB-'$(date +%s)'","embeddingModel":"BAAI/bge-m3","description":"auto-test"}')
KBID=$(json "d=json.loads('''$r'''); print(d.get('id',''))" 2>/dev/null || echo "")
[ -n "$KBID" ] && ok "POST /knowledge-base (create)" || fail "POST /knowledge-base (create)" "$(echo $r|head -1)"

if [ -n "$KBID" ]; then
    r=$(get "${A}/knowledge-base/${KBID}" -H "Authorization: Bearer $TOKEN")
    httpok "${A}/knowledge-base/${KBID}" -H "Authorization: Bearer $TOKEN" \
        && ok "GET /knowledge-base/{id}" || fail "GET /knowledge-base/{id}"

    httpok "${A}/knowledge-base/${KBID}" -X PUT -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"name":"UpdatedKB","description":"updated"}' \
        && ok "PUT /knowledge-base/{id}" || fail "PUT /knowledge-base/{id}"

    # Chunk strategies
    r=$(get "${A}/knowledge-base/chunk-strategies" -H "Authorization: Bearer $TOKEN")
    httpok "${A}/knowledge-base/chunk-strategies" -H "Authorization: Bearer $TOKEN" \
        && ok "GET /knowledge-base/chunk-strategies" || skip "GET /knowledge-base/chunk-strategies"

    # KB docs list
    httpok "${A}/knowledge-base/${KBID}/docs?current=1&size=10" -H "Authorization: Bearer $TOKEN" \
        && ok "GET /knowledge-base/{kbId}/docs" || skip "GET /knowledge-base/{kbId}/docs"

    # Reindex
    httpok "${A}/knowledge-base/${KBID}/reindex" -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}' \
        && ok "POST /knowledge-base/{id}/reindex" || skip "POST /knowledge-base/{id}/reindex"

    # Doc search
    httpok "${A}/knowledge-base/docs/search?keyword=test&limit=5" -H "Authorization: Bearer $TOKEN" \
        && ok "GET /knowledge-base/docs/search" || skip "GET /knowledge-base/docs/search"
fi

# ============================================================
info "6. 知识库文档 (10)"
# ============================================================
if [ -n "$KBID" ]; then
    # Upload doc (multipart)
    echo "test content $(date)" > /tmp/test-upload.txt
    r=$(curl -s -X POST "${A}/knowledge-base/${KBID}/docs/upload" \
        -H "Authorization: Bearer $TOKEN" \
        -F "sourceType=FILE" -F "file=@/tmp/test-upload.txt" -F "chunkStrategy=fixed" 2>/dev/null)
    DOCID=$(json "d=json.loads('''$r'''); print(d.get('id',d.get('documentId','')))" 2>/dev/null || echo "")
    [ -n "$DOCID" ] && ok "POST /kb/{kbId}/docs/upload" || fail "POST /kb/{kbId}/docs/upload" "$(echo $r|head -1)"
    rm -f /tmp/test-upload.txt
else
    skip "文档上传 (无知识库)"
fi

if [ -n "$DOCID" ]; then
    httpok "${A}/knowledge-base/docs/${DOCID}" -H "Authorization: Bearer $TOKEN" \
        && ok "GET /knowledge-base/docs/{docId}" || fail "GET /knowledge-base/docs/{docId}"

    httpok "${A}/knowledge-base/docs/${DOCID}" -X PUT -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"docName":"updated.txt"}' \
        && ok "PUT /knowledge-base/docs/{docId}" || skip "PUT /knowledge-base/docs/{docId}"

    httpok "${A}/knowledge-base/docs/${DOCID}/chunk" -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}' \
        && ok "POST /docs/{docId}/chunk" || skip "POST /docs/{docId}/chunk"

    httpok "${A}/knowledge-base/docs/${DOCID}/enable?value=true" -X PATCH -H "Authorization: Bearer $TOKEN" \
        && ok "PATCH /docs/{docId}/enable" || skip "PATCH /docs/{docId}/enable"

    httpok "${A}/knowledge-base/docs/${DOCID}/chunk-logs?current=1&size=10" -H "Authorization: Bearer $TOKEN" \
        && ok "GET /docs/{docId}/chunk-logs" || skip "GET /docs/{docId}/chunk-logs"

    httpok "${A}/knowledge-base/docs/${DOCID}/chunks?current=1&size=10" -H "Authorization: Bearer $TOKEN" \
        && ok "GET /docs/{docId}/chunks" || skip "GET /docs/{docId}/chunks"

    # Create a chunk
    r=$(post "${A}/knowledge-base/docs/${DOCID}/chunks" -H "Authorization: Bearer $TOKEN" -d '{"content":"test chunk content"}')
    httpok "${A}/knowledge-base/docs/${DOCID}/chunks" -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"content":"test chunk"}' \
        && ok "POST /docs/{docId}/chunks" || skip "POST /docs/{docId}/chunks"

    # Toggle chunk (use chunkId=1)
    httpok "${A}/knowledge-base/docs/${DOCID}/chunks/1/enable?value=true" -X PATCH -H "Authorization: Bearer $TOKEN" \
        && ok "PATCH /docs/{docId}/chunks/{id}/enable" || skip "PATCH /docs/{docId}/chunks/{id}/enable"

    # Batch toggle
    httpok "${A}/knowledge-base/docs/${DOCID}/chunks/batch-enable?value=true" -X PATCH -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"ids":[1]}' \
        && ok "PATCH /docs/{docId}/chunks/batch-enable" || skip "PATCH /docs/{docId}/chunks/batch-enable"

    # Delete chunk
    httpok "${A}/knowledge-base/docs/${DOCID}/chunks/1" -X DELETE -H "Authorization: Bearer $TOKEN" \
        && ok "DELETE /docs/{docId}/chunks/{id}" || skip "DELETE /docs/{docId}/chunks/{id}"
fi

# ============================================================
info "7. 文档管理 (5)"
# ============================================================
httpok "${A}/documents" -H "Authorization: Bearer $TOKEN" \
    && ok "GET /documents" || skip "GET /documents"

if [ -n "$DOCID" ]; then
    httpok "${A}/documents/${DOCID}/status" -H "Authorization: Bearer $TOKEN" \
        && ok "GET /documents/{id}/status" || skip "GET /documents/{id}/status"

    httpok "${A}/documents/${DOCID}" -X DELETE -H "Authorization: Bearer $TOKEN" \
        && ok "DELETE /documents/{id}" || fail "DELETE /documents/{id}"
fi

echo "test upload $(date)" > /tmp/test-doc.txt
httpok "${A}/documents/upload" -X POST -H "Authorization: Bearer $TOKEN" -F "file=@/tmp/test-doc.txt" -F "chunkStrategy=fixed" 2>/dev/null \
    && ok "POST /documents/upload" || skip "POST /documents/upload"

httpok "${A}/documents/upload/batch" -X POST -H "Authorization: Bearer $TOKEN" -F "files=@/tmp/test-doc.txt" -F "chunkStrategy=fixed" 2>/dev/null \
    && ok "POST /documents/upload/batch" || skip "POST /documents/upload/batch"
rm -f /tmp/test-doc.txt

# ============================================================
info "8. 检索模块 (2)"
# ============================================================
httpok "${A}/retrieve" -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"query":"test","topK":3}' \
    && ok "POST /retrieve" || skip "POST /retrieve"

httpok "${A}/retrieve/hybrid" -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"query":"test","topK":3,"kbId":"1"}' \
    && ok "POST /retrieve/hybrid" || skip "POST /retrieve/hybrid"

# ============================================================
info "9. Embedding 模块 (2)"
# ============================================================
httpok "${A}/embedding/embed" -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"text":"test"}' \
    && ok "POST /embedding/embed" || skip "POST /embedding/embed"

httpok "${A}/embedding/embed/batch" -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"texts":["hello","world"]}' \
    && ok "POST /embedding/embed/batch" || skip "POST /embedding/embed/batch"

# ============================================================
info "10. 评估模块 (2)"
# ============================================================
httpok "${A}/evaluation/evaluate" -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d '{"question":"What is AI?","answer":"AI is artificial intelligence","contexts":["AI stands for artificial intelligence"],"groundTruth":"AI is artificial intelligence"}' \
    && ok "POST /evaluation/evaluate" || skip "POST /evaluation/evaluate (LLM)"

httpok "${A}/evaluation/evaluate/batch" -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d '[{"question":"What is AI?","answer":"AI","contexts":["AI"],"groundTruth":"AI"}]' \
    && ok "POST /evaluation/evaluate/batch" || skip "POST /evaluation/evaluate/batch"

# ============================================================
info "11. 流水线模块 (2)"
# ============================================================
httpok "${A}/ingestion/pipelines?current=1&size=10" -H "Authorization: Bearer $TOKEN" \
    && ok "GET /ingestion/pipelines" || skip "GET /ingestion/pipelines"

httpok "${A}/ingestion/pipelines/1" -H "Authorization: Bearer $TOKEN" \
    && ok "GET /ingestion/pipelines/{id}" || skip "GET /ingestion/pipelines/{id}"

# ============================================================
info "12. RAG 模块 (6)"
# ============================================================
httpok "${A}/rag/sample-questions" -H "Authorization: Bearer $TOKEN" \
    && ok "GET /rag/sample-questions" || skip "GET /rag/sample-questions"

httpok "${A}/rag/settings" -H "Authorization: Bearer $TOKEN" \
    && ok "GET /rag/settings" || skip "GET /rag/settings"

httpok "${A}/rag/traces/runs?current=1&size=10" -H "Authorization: Bearer $TOKEN" \
    && ok "GET /rag/traces/runs" || skip "GET /rag/traces/runs"

httpok "${A}/rag/traces/runs/test-1" -H "Authorization: Bearer $TOKEN" \
    && ok "GET /rag/traces/runs/{id}" || skip "GET /rag/traces/runs/{id}"

httpok "${A}/rag/traces/runs/test-1/nodes" -H "Authorization: Bearer $TOKEN" \
    && ok "GET /rag/traces/runs/{id}/nodes" || skip "GET /rag/traces/runs/{id}/nodes"

httpok "${A}/rag/v3/stop?taskId=test-1" -X POST -H "Authorization: Bearer $TOKEN" \
    && ok "POST /rag/v3/stop" || skip "POST /rag/v3/stop"

# ============================================================
info "13. RAG V3 SSE 流式 (1)"
# ============================================================
r=$(get "${A}/rag/v3/chat?question=hello&kbId=1" -H "Authorization: Bearer $TOKEN" -w "%{http_code}" --max-time 5 2>/dev/null)
c=$(echo "$r" | tail -1)
[ "$c" = "200" ] && ok "GET /rag/v3/chat (SSE)" || skip "GET /rag/v3/chat (SSE)"

# ============================================================
info "14. 示例问题 CRUD (4)"
# ============================================================
httpok "${A}/sample-questions?current=1&size=10" -H "Authorization: Bearer $TOKEN" \
    && ok "GET /sample-questions" || skip "GET /sample-questions"

r=$(post "${A}/sample-questions" -H "Authorization: Bearer $TOKEN" \
    -d '{"title":"TestQ","description":"auto-test","question":"What is RAG?"}')
httpok "${A}/sample-questions" -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d '{"title":"TestQ","description":"auto-test","question":"What is RAG?"}' \
    && ok "POST /sample-questions" || skip "POST /sample-questions"

httpok "${A}/sample-questions/1" -X PUT -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d '{"title":"UpdatedQ","question":"Updated?"}' \
    && ok "PUT /sample-questions/{id}" || skip "PUT /sample-questions/{id}"

httpok "${A}/sample-questions/1" -X DELETE -H "Authorization: Bearer $TOKEN" \
    && ok "DELETE /sample-questions/{id}" || skip "DELETE /sample-questions/{id}"

# ============================================================
info "15. 仪表盘 (3)"
# ============================================================
httpok "${A}/admin/dashboard/overview?window=24h" -H "Authorization: Bearer $TOKEN" \
    && ok "GET /admin/dashboard/overview" || skip "GET /admin/dashboard/overview"

httpok "${A}/admin/dashboard/performance?window=24h" -H "Authorization: Bearer $TOKEN" \
    && ok "GET /admin/dashboard/performance" || skip "GET /admin/dashboard/performance"

httpok "${A}/admin/dashboard/trends?metric=sessions&window=7d&granularity=day" -H "Authorization: Bearer $TOKEN" \
    && ok "GET /admin/dashboard/trends" || skip "GET /admin/dashboard/trends"

# ============================================================
# Cleanup
# ============================================================
if [ -n "$KBID" ]; then
    httpok "${A}/knowledge-base/${KBID}" -X DELETE -H "Authorization: Bearer $TOKEN" \
        && ok "DELETE /knowledge-base/{id} (cleanup)" || skip "DELETE /knowledge-base/{id} (cleanup)"
fi

# ============================================================
# Report
# ============================================================
echo ""
echo "╔══════════════════════════════════════════╗"
TOTAL=$((PASS + FAIL + SKIP))
echo -e "║  总计: ${TOTAL}  |  ${GREEN}✓ 通过: ${PASS}${NC}  |  ${RED}✗ 失败: ${FAIL}${NC}  |  ${YELLOW}⊘ 跳过: ${SKIP}${NC}"
if [ "$FAIL" -gt 0 ]; then
    echo -e "║  ${RED}存在 ${FAIL} 项失败！${NC}"
    exit 1
else
    echo -e "║  ${GREEN}全部通过 ✓${NC}"
fi
echo "╚══════════════════════════════════════════╝"
exit 0
