#!/bin/bash
# ============================================================
# RAGGG 开发守护进程 — 监听代码变更 → 自动构建 → 自动测试
#
# 用法:
#   ./watchdog.sh             前台运行，监听 + 构建 + 测试
#   ./watchdog.sh --daemon    后台运行
#   ./watchdog.sh stop        停止后台进程
#   ./watchdog.sh status      查看状态
# ============================================================

set -e

PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"
PID_FILE="/tmp/raggg-watchdog.pid"
LOG_FILE="/tmp/raggg-watchdog.log"
DEBOUNCE=3  # 文件变更后等待 N 秒再触发（避免多次编译）

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

log() { echo -e "[$(date '+%H:%M:%S')] $1" | tee -a "$LOG_FILE"; }

# ---------- stop ----------
do_stop() {
    if [ -f "$PID_FILE" ]; then
        local pid=$(cat "$PID_FILE")
        if kill -0 "$pid" 2>/dev/null; then
            kill "$pid" 2>/dev/null
            log "${YELLOW}Watchdog 已停止 (pid=$pid)${NC}"
        fi
        rm -f "$PID_FILE"
    else
        log "${YELLOW}Watchdog 未在运行${NC}"
    fi
}

# ---------- status ----------
do_status() {
    if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
        log "${GREEN}Watchdog 运行中 (pid=$(cat "$PID_FILE"))${NC}"
        log "日志: $LOG_FILE"
    else
        log "${YELLOW}Watchdog 未运行${NC}"
    fi
}

# ---------- build + deploy + test ----------
build_and_test() {
    log "${CYAN}检测到代码变更，开始构建...${NC}"

    # 1. 前端构建
    if [ -d "$PROJECT_ROOT/frontend/src" ]; then
        log "  → 构建前端..."
        cd "$PROJECT_ROOT/frontend"
        npm run build >> "$LOG_FILE" 2>&1 || { log "${RED}  前端构建失败${NC}"; return 1; }
        cp -r dist/* "$PROJECT_ROOT/src/main/resources/static/" 2>/dev/null
        log "  ${GREEN}✓ 前端构建完成${NC}"
    fi

    # 2. 后端构建
    log "  → 构建后端..."
    cd "$PROJECT_ROOT"
    mvn package -DskipTests -q >> "$LOG_FILE" 2>&1 || { log "${RED}  后端构建失败${NC}"; return 1; }
    log "  ${GREEN}✓ 后端构建完成${NC}"

    # 3. 重启服务
    log "  → 重启后端..."
    pkill -f 'rag-demo-1.0.0-SNAPSHOT.jar' 2>/dev/null || true
    sleep 2
    java -Xmx2g -Xms512m -jar target/rag-demo-1.0.0-SNAPSHOT.jar >> /tmp/backend.log 2>&1 &
    log "  → 等待服务就绪..."
    for i in $(seq 1 30); do
        if curl -s http://localhost:8080/actuator/health | grep -q '"status":"UP"'; then
            log "  ${GREEN}✓ 服务已就绪${NC}"
            break
        fi
        sleep 1
    done

    # 4. 运行测试
    log "  → 运行功能测试..."
    bash "$PROJECT_ROOT/test-agent.sh" --quick 2>&1 | tee -a "$LOG_FILE" | tail -5
    log "  ${GREEN}✓ 测试完成${NC}"
    echo "——— $(date '+%Y-%m-%d %H:%M:%S') ———" >> "$LOG_FILE"
}

# ---------- watcher ----------
do_watch() {
    if ! command -v inotifywait &>/dev/null; then
        echo "需要安装 inotify-tools: sudo apt-get install inotify-tools"
        exit 1
    fi

    if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
        log "${YELLOW}Watchdog 已在运行中${NC}"
        exit 0
    fi

    echo $$ > "$PID_FILE"
    log "${GREEN}Watchdog 已启动 (pid=$$)${NC}"
    log "监听目录: $PROJECT_ROOT/src"
    log "日志文件: $LOG_FILE"

    LAST_BUILD=0

    inotifywait -m -r -e modify,create,delete,move \
        --exclude '(target|node_modules|\.git|dist|\.log|\.class)' \
        "$PROJECT_ROOT/src" "$PROJECT_ROOT/frontend/src" 2>/dev/null | while read -r line; do
        NOW=$(date +%s)
        if [ $((NOW - LAST_BUILD)) -gt $DEBOUNCE ]; then
            sleep $DEBOUNCE
            build_and_test
            LAST_BUILD=$NOW
        fi
    done
}

# ---------- main ----------
case "${1:-}" in
    stop)    do_stop ;;
    status)  do_status ;;
    --daemon)
        nohup bash "$0" watch >> "$LOG_FILE" 2>&1 &
        echo "Watchdog 已在后台启动 (pid=$!)"
        echo "日志: $LOG_FILE"
        ;;
    watch)   do_watch ;;   # 内部入口
    *)
        echo "用法: $0 [--daemon|stop|status]"
        echo ""
        echo "  (无参数)    前台运行，监听文件变更 → 构建 → 测试"
        echo "  --daemon    后台运行"
        echo "  stop        停止后台进程"
        echo "  status      查看运行状态"
        ;;
esac
