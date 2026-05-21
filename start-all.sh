#!/usr/bin/env bash
set -euo pipefail

DRY_RUN=false
ACTION="start"

usage() {
    cat <<'EOF'
用法: ./start-all.sh [--dry-run] [--stop] [--status]

  (无参数)    启动所有服务
  --dry-run   仅打印将要执行的命令，不实际启动
  --stop      停止所有已启动的服务
  --status    查看服务运行状态
  --help      显示此帮助

示例:
  ./start-all.sh
  ./start-all.sh --dry-run
  ./start-all.sh --stop
  ./start-all.sh --status
EOF
    exit 0
}

for arg in "$@"; do
    case "$arg" in
        --dry-run) DRY_RUN=true ;;
        --stop) ACTION="stop" ;;
        --status) ACTION="status" ;;
        --help|-h) usage ;;
        *) echo "未知参数: $arg"; usage ;;
    esac
done

ROOT="$(cd "$(dirname "$0")" && pwd)"
ML_SERVER_DIR="$ROOT/ml-server"
BOT_SERVER_DIR="$ROOT/bot-server"
ENV_FILE="$ROOT/.env"
LOG_DIR="$ROOT/logs"
PID_DIR="$ROOT/.pids"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

get_env_value() {
    local key="$1"
    local default="${2:-}"
    if [ ! -f "$ENV_FILE" ]; then
        echo "$default"
        return
    fi
    local value
    value=$(grep -E "^\s*${key}=" "$ENV_FILE" 2>/dev/null | tail -1 | sed 's/^[^=]*=\s*//' | tr -d '\r')
    if [ -z "$value" ]; then
        echo "$default"
    else
        echo "$value"
    fi
}

export_dotenv() {
    if [ ! -f "$ENV_FILE" ]; then
        return
    fi
    while IFS='=' read -r key value; do
        key=$(echo "$key" | tr -d '[:space:]' | tr -d '\r')
        if [ -z "$key" ] || [ "${key:0:1}" = "#" ]; then
            continue
        fi
        value=$(echo "$value" | tr -d '\r')
        export "$key=$value"
    done < <(grep -v '^\s*#' "$ENV_FILE" | grep '=' || true)
}

port_listening() {
    local port="$1"
    if command -v ss &>/dev/null; then
        ss -tlnp 2>/dev/null | grep -q ":$port "
    elif command -v netstat &>/dev/null; then
        netstat -tlnp 2>/dev/null | grep -q ":$port "
    elif command -v lsof &>/dev/null; then
        lsof -i ":$port" -sTCP:LISTEN &>/dev/null
    else
        return 1
    fi
}

process_running() {
    local pattern="$1"
    pgrep -f "$pattern" &>/dev/null
}

wait_port() {
    local name="$1"
    local port="$2"
    local timeout="${3:-120}"
    local elapsed=0
    while [ "$elapsed" -lt "$timeout" ]; do
        if port_listening "$port"; then
            echo "[$name] 已就绪（端口 $port 已监听）。"
            return 0
        fi
        sleep 1
        elapsed=$((elapsed + 1))
    done
    echo "[$name] 启动超时：等待端口 $port 监听超过 $timeout 秒。" >&2
    return 1
}

write_pid() {
    local name="$1"
    local pid="$2"
    mkdir -p "$PID_DIR"
    echo "$pid" > "$PID_DIR/$name.pid"
}

read_pid() {
    local name="$1"
    if [ -f "$PID_DIR/$name.pid" ]; then
        cat "$PID_DIR/$name.pid"
    fi
}

is_pid_alive() {
    local pid="$1"
    [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

# ---------------------------------------------------------------------------
# Start a service in background
# ---------------------------------------------------------------------------
start_service() {
    local name="$1"
    local work_dir="$2"
    local command="$3"
    local port="${4:-0}"
    local process_pattern="${5:-}"

    # Already running check
    if [ "$port" -gt 0 ] && port_listening "$port"; then
        echo "[$name] 已在运行，跳过启动（端口 $port 已监听）。"
        return 0
    fi
    if [ -n "$process_pattern" ] && process_running "$process_pattern"; then
        echo "[$name] 已在运行，跳过启动（检测到现有进程）。"
        return 0
    fi

    if [ "$DRY_RUN" = true ]; then
        echo "[$name] DryRun -> cd $work_dir && $command"
        return 0
    fi

    mkdir -p "$LOG_DIR"
    local log_file="$LOG_DIR/$name.log"

    (
        cd "$work_dir"
        export_dotenv
        nohup bash -c "$command" >> "$log_file" 2>&1 &
        echo $! > "$PID_DIR/$name.pid"
    )
    echo "[$name] 已启动（PID=$(read_pid "$name")，日志: $log_file）。"
}

# ---------------------------------------------------------------------------
# Stop a service
# ---------------------------------------------------------------------------
stop_service() {
    local name="$1"
    local pid
    pid=$(read_pid "$name")
    if [ -z "$pid" ]; then
        echo "[$name] 未找到 PID 文件，跳过。"
        return 0
    fi
    if is_pid_alive "$pid"; then
        kill "$pid" 2>/dev/null || true
        # Wait for graceful shutdown
        local waited=0
        while is_pid_alive "$pid" && [ "$waited" -lt 10 ]; do
            sleep 1
            waited=$((waited + 1))
        done
        if is_pid_alive "$pid"; then
            kill -9 "$pid" 2>/dev/null || true
            echo "[$name] 已强制终止（PID=$pid）。"
        else
            echo "[$name] 已停止（PID=$pid）。"
        fi
    else
        echo "[$name] PID=$pid 已不存在，清理残留。"
    fi
    rm -f "$PID_DIR/$name.pid"
}

# ---------------------------------------------------------------------------
# Status
# ---------------------------------------------------------------------------
service_status() {
    local name="$1"
    local port="${2:-0}"
    local pid
    pid=$(read_pid "$name")

    local status="未运行"
    if [ -n "$pid" ] && is_pid_alive "$pid"; then
        status="运行中 (PID=$pid)"
    fi
    if [ "$port" -gt 0 ] && port_listening "$port"; then
        status="$status, 端口 $port 已监听"
    fi
    echo "  $name: $status"
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

ml_port=$(get_env_value "ML_SERVER_PORT" "5000")
bot_port=$(get_env_value "BOT_SERVER_PORT" "8080")

if [ "$ACTION" = "stop" ]; then
    echo "=== 停止所有服务 ==="
    stop_service "bot-server"
    stop_service "ml-server"
    stop_service "weixin-gateway"
    rm -rf "$PID_DIR"
    echo "=== 已全部停止 ==="
    exit 0
fi

if [ "$ACTION" = "status" ]; then
    echo "=== 服务状态 ==="
    service_status "ml-server" "$ml_port"
    service_status "bot-server" "$bot_port"
    service_status "weixin-gateway"
    exit 0
fi

echo "=== HotBot 一键启动 ==="
echo "ml-server 端口: $ml_port"
echo "bot-server 端口: $bot_port"
echo ""

# Trap Ctrl+C to stop all services
cleanup() {
    echo ""
    echo "=== 收到中断信号，停止所有服务 ==="
    stop_service "bot-server"
    stop_service "ml-server"
    stop_service "weixin-gateway"
    rm -rf "$PID_DIR"
    echo "=== 已全部停止 ==="
    exit 0
}
trap cleanup INT TERM

start_service "ml-server" "$ML_SERVER_DIR" \
    "uvicorn main:app --host 0.0.0.0 --port $ml_port" \
    "$ml_port"
wait_port "ml-server" "$ml_port" 60

start_service "bot-server" "$BOT_SERVER_DIR" \
    "mvn -DskipTests -q package && java -jar target/hotspot-bot-1.0.0.jar" \
    "$bot_port"
wait_port "bot-server" "$bot_port" 300

start_service "weixin-gateway" "$ML_SERVER_DIR" \
    "python run_weixin_gateway.py" \
    "" \
    "run_weixin_gateway.py"

echo ""
echo "=== 所有服务已启动 ==="
echo "bot-server: http://localhost:$bot_port"
echo "ml-server:  http://localhost:$ml_port"
echo "日志目录:   $LOG_DIR"
echo ""
echo "按 Ctrl+C 停止所有服务。"

# Wait for any background process to exit or Ctrl+C
wait
