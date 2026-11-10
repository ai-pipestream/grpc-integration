#!/usr/bin/env bash
# Boot a built release image without host ports or real upstream credentials.
# Requires Docker and GNU timeout. Helper images may be overridden with digests.
# Usage: scripts/container-smoke.sh MODE IMAGE
set -euo pipefail
mode=${1:-}
image=${2:-}
case "$mode" in
  sync|mcp) ;;
  *) echo 'Usage: scripts/container-smoke.sh {sync|mcp} IMAGE' >&2; exit 2 ;;
esac
[[ -n "$image" && $# == 2 ]] || { echo 'An image reference is required' >&2; exit 2; }
command -v timeout >/dev/null
probe_image=${GRPCURL_IMAGE:-fullstorydev/grpcurl:v1.9.3}
python_image=${PYTHON_IMAGE:-python:3.13-alpine}
prefix="split-smoke-${mode}-$(date +%s)-$$-${RANDOM}"
network="$prefix"
server="$prefix-server"
probe="$prefix-probe"
created_network=false
containers=()
# Bound every Docker operation as well as each RPC; no published host ports.
d() { timeout --kill-after=5s 30s docker "$@"; }
cleanup() {
  result=$?
  trap - EXIT
  if (( result != 0 )); then
    d logs --tail 80 "$server" >&2 || true
  fi
  for container in "${containers[@]}"; do
    if d container inspect "$container" >/dev/null 2>&1; then
      if ! d rm -f "$container" >/dev/null; then
        echo "Cleanup failed for owned container $container" >&2
        result=1
      fi
    fi
  done
  if $created_network && ! d network rm "$network" >/dev/null; then
    echo "Cleanup failed for owned network $network" >&2
    result=1
  fi
  printf 'container-smoke mode=%s exit=%s\n' "$mode" "$result"
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
ensure_image() {
  if ! d image inspect "$1" >/dev/null 2>&1; then timeout --kill-after=5s 180s docker pull "$1"; fi
  d image inspect --format 'helper={{.Id}} repoDigests={{json .RepoDigests}}' "$1"
}
d image inspect --format 'image={{.Id}} repoDigests={{json .RepoDigests}}' "$image"
if [[ "$mode" == mcp ]]; then ensure_image "$python_image"; fi
if [[ "$mode" != mcp ]]; then ensure_image "$probe_image"; fi
created_network=true
d network create --internal "$network" >/dev/null
runtime=(--network "$network" --read-only --tmpfs /tmp:rw,exec,nosuid,size=128m
         --cap-drop ALL --security-opt no-new-privileges --cpus 2 --memory 768m
         -e JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=2)
env_args=()
case "$mode" in
  sync) port=9097 ;;
  mcp) port=8090 ;;
esac
containers+=("$server" "$probe")
d run -d --name "$server" --network-alias service "${runtime[@]}" "${env_args[@]}" "$image" >/dev/null
ready=false
for attempt in {1..30}; do
  [[ $(d inspect --format '{{.State.Running}}' "$server") == true ]] || {
    echo 'Service exited before readiness' >&2; exit 1;
  }
  if [[ "$mode" == mcp ]]; then
    if d run --name "$probe" --network "$network" --read-only --cap-drop ALL \
        --security-opt no-new-privileges --cpus 1 --memory 128m "$python_image" python -c '
import json, urllib.request
url = "http://service:8090/mcp"
headers = {"Content-Type":"application/json", "Accept":"application/json, text/event-stream"}
def rpc(body):
    req = urllib.request.Request(url, data=json.dumps(body).encode(), headers=headers)
    with urllib.request.urlopen(req, timeout=4) as response:
        session = response.headers.get("Mcp-Session-Id")
        if session: headers["Mcp-Session-Id"] = session
        raw = response.read().decode()
    if not raw.strip(): return None
    if raw.lstrip().startswith("{"): return json.loads(raw)
    return json.loads(next(line[5:].strip() for line in raw.splitlines() if line.startswith("data:")))
init = rpc({"jsonrpc":"2.0", "id":1, "method":"initialize", "params":{
    "protocolVersion":"2025-11-25", "capabilities":{}, "clientInfo":{"name":"container-smoke", "version":"1"}}})
assert "result" in init, init
headers["MCP-Protocol-Version"] = init["result"]["protocolVersion"]
rpc({"jsonrpc":"2.0", "method":"notifications/initialized"})
result = rpc({"jsonrpc":"2.0", "id":2, "method":"tools/list", "params":{}})
names = {tool["name"] for tool in result["result"]["tools"]}
assert {"app_status", "connection_list", "sync_table_get_asset"} <= names, names
print("MCP initialize and tools/list PASS:", sorted(names))
'; then ready=true; break; fi
  else
    if response=$(d run --name "$probe" --network "$network" --read-only --cap-drop ALL \
        --security-opt no-new-privileges --cpus 1 --memory 128m "$probe_image" \
        -plaintext -connect-timeout 2 -max-time 4 -d '{}' "service:$port" grpc.health.v1.Health/Check 2>&1) \
        && [[ "$response" == *'"SERVING"'* ]]; then
      printf '%s\n' "$response"
      ready=true; break
    fi
    printf 'readiness attempt %s: %s\n' "$attempt" "$response" >&2
  fi
  d rm -f "$probe" >/dev/null 2>&1 || true
  sleep 1
done
$ready || { echo 'Service did not become ready within 30 attempts' >&2; exit 1; }
d inspect --format 'runtime image={{.Image}} running={{.State.Running}} user={{.Config.User}} readonly={{.HostConfig.ReadonlyRootfs}} ports={{json .HostConfig.PortBindings}}' "$server"
d logs --tail 20 "$server"
