#!/bin/bash
# ============================================
# RAGGG 多模态功能测试脚本
# ============================================
# 使用方式: bash test-multimodal.sh [BASE_URL]
# 默认 BASE_URL=http://localhost:8081
# ============================================

BASE_URL="${1:-http://localhost:8081}"
PASS=0
FAIL=0
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Test helper
test_endpoint() {
    local name="$1"
    local method="$2"
    local url="$3"
    local data="$4"
    local expected_code="$5"

    echo -n "  [$name] ... "

    if [ "$method" == "GET" ]; then
        response=$(curl -s -w "\n%{http_code}" -X GET "$url" 2>&1)
    else
        response=$(curl -s -w "\n%{http_code}" -X "$method" "$url" \
            -H "Content-Type: application/json" \
            -d "$data" 2>&1)
    fi

    http_code=$(echo "$response" | tail -1)
    body=$(echo "$response" | sed '$d')

    if [ "$http_code" == "$expected_code" ]; then
        echo -e "${GREEN}PASS${NC} (HTTP $http_code)"
        PASS=$((PASS+1))
    else
        echo -e "${RED}FAIL${NC} (expected $expected_code, got $http_code)"
        echo "    Body: $(echo $body | head -c 200)"
        FAIL=$((FAIL+1))
    fi
}

echo "========================================"
echo "  RAGGG 多模态功能测试"
echo "  Base URL: $BASE_URL"
echo "  Time: $(date '+%Y-%m-%d %H:%M:%S')"
echo "========================================"

# ─────────── 1. 健康检查 ───────────
echo ""
echo "1. 系统健康检查"
test_endpoint "Actuator Health" "GET" "$BASE_URL/actuator/health" "" "200"
test_endpoint "API Docs" "GET" "$BASE_URL/v3/api-docs" "" "200"

# ─────────── 2. 认证 ───────────
echo ""
echo "2. 认证测试"
LOGIN_RESP=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"username":"admin","password":"admin123"}')
TOKEN=$(echo "$LOGIN_RESP" | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
if [ -n "$TOKEN" ]; then
    echo -e "  [Login] ${GREEN}PASS${NC} (token obtained)"
    AUTH_HEADER="Authorization: Bearer $TOKEN"
else
    echo -e "  [Login] ${RED}FAIL${NC} (no token)"
    AUTH_HEADER=""
fi

# ─────────── 3. 多模态状态 ───────────
echo ""
echo "3. 多模态功能状态"
test_endpoint "Multimodal Status" "GET" "$BASE_URL/api/v1/chat/multimodal/status" "" "200"

# ─────────── 4. 纯文本聊天(M3) ───────────
echo ""
echo "4. 纯文本聊天 (MiniMax-M3)"
test_endpoint "Simple Chat" "POST" "$BASE_URL/api/v1/chat" \
    '{"message":"你好，请用一句话介绍自己","kbIds":[]}' "200"

# ─────────── 5. RAG 检索 ───────────
echo ""
echo "5. RAG 检索测试"
test_endpoint "RAG Search" "POST" "$BASE_URL/api/v1/rag/search" \
    '{"query":"什么是RAG","topK":3}' "200"

# ─────────── 6. 知识库管理 ───────────
echo ""
echo "6. 知识库管理"
test_endpoint "List KBs" "GET" "$BASE_URL/api/v1/knowledge-bases" "" "200"
test_endpoint "Create KB" "POST" "$BASE_URL/api/v1/knowledge-bases" \
    '{"name":"Test KB","description":"Test knowledge base for multimodal"}' "200"

# ─────────── 7. 文档上传 ───────────
echo ""
echo "7. 文档上传测试"
echo "  [Upload] 需要手动测试: curl -F 'file=@doc.pdf' -F 'kbId=1' $BASE_URL/api/v1/documents/upload"
echo "  [Vision QA] 需要手动测试: curl -F 'image=@photo.jpg' -F 'question=这是什么' $BASE_URL/api/v1/chat/vision"

# ─────────── 8. 用户管理 ───────────
echo ""
echo "8. 用户管理"
test_endpoint "Get Users" "GET" "$BASE_URL/api/v1/users" "" "200"

# ─────────── 9. Swagger/API文档 ───────────
echo ""
echo "9. API 文档"
test_endpoint "Swagger UI" "GET" "$BASE_URL/swagger-ui.html" "" "200"

# ─────────── 10. Prometheus Metrics ───────────
echo ""
echo "10. Metrics"
test_endpoint "Prometheus" "GET" "$BASE_URL/actuator/prometheus" "" "200"

# ─────────── Summary ───────────
echo ""
echo "========================================"
echo "  测试结果: ${GREEN}$PASS 通过${NC}, ${RED}$FAIL 失败${NC}"
echo "  总计: $((PASS+FAIL))"
echo "========================================"
