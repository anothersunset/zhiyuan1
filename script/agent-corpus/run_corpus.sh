#!/usr/bin/env bash
# =============================================================================
# Agent 意图语料回归执行器（自进化闭环的检测端）
# 用法：./run_corpus.sh [BASE_URL]   默认 http://127.0.0.1:18080
# 行为：注册临时账号 → 逐条语料（独立会话）→ 断言路由工具 → 打表 → 清理
# 退出码：0 全过；1 有失败；2 环境不可用
# =============================================================================
set -uo pipefail
BASE="${1:-http://127.0.0.1:18080}"
DIR="$(cd "$(dirname "$0")" && pwd)"
MANIFEST="$DIR/corpus.json"
STAMP="$(date +%Y%m%d_%H%M%S)"
U="corpusbot$STAMP"

command -v curl >/dev/null || { echo "需要 curl"; exit 2; }
command -v python >/dev/null || { echo "需要 python"; exit 2; }

# 健康预检
curl -sf "$BASE/api/meta/options" >/dev/null || { echo "目标 $BASE 不可用（栈未启动？）——跳过本轮"; exit 2; }

# 临时账号
curl -s -X POST "$BASE/api/auth/register" -H "Content-Type: application/json" \
  -d "{\"username\":\"$U\",\"password\":\"corpus123\",\"score\":620,\"subjectType\":\"PHYSICS\"}" >/dev/null
TOKEN=$(curl -s -X POST "$BASE/api/auth/login" -H "Content-Type: application/json" \
  -d "{\"username\":\"$U\",\"password\":\"corpus123\"}" | python -c "import sys,json;print(json.load(sys.stdin).get('token',''))")
[ -z "$TOKEN" ] && { echo "登录失败"; exit 2; }

PROFILE_BODY='{"score":620,"subjectType":"PHYSICS","examProvince":"湖南"}'
curl -s -X POST "$BASE/api/auth/profile" -H "Content-Type: application/json; charset=utf-8" \
  -H "Authorization: Bearer $TOKEN" -d "$PROFILE_BODY" >/dev/null

REPORT="$DIR/report_$STAMP.md"
{
  echo "# Agent 意图语料回归报告 $STAMP"
  echo
  echo "| 语料ID | 用户话术 | 期望 | 实际 | 结果 |"
  echo "|---|---|---|---|---|"
} > "$REPORT"

PASS=0; FAIL=0; FAILED=""
while IFS=$'\t' read -r id text expectAction expectArgName expectArgValue; do
  [ -z "$id" ] && continue
  CONV=$(curl -s -X POST "$BASE/api/agent/conversations" -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" -d "{\"title\":\"corpus-$id\"}" \
    | python -c "import sys,json;print(json.load(sys.stdin).get('id',''))")
  BODY=$(python -c "import json,sys;print(json.dumps({'content':sys.argv[1]},ensure_ascii=False))" "$text")
  ACTUAL=$(echo "$BODY" | curl -s -X POST "$BASE/api/agent/conversations/$CONV/messages" \
    -H "Content-Type: application/json; charset=utf-8" -H "Authorization: Bearer $TOKEN" \
    --data-binary @- --max-time 120 | python -c "
import sys, json
d = json.loads(sys.stdin.read())
tool = next((m.get('toolName') for m in d.get('generatedMessages', []) if m.get('toolName')), 'reply')
print(tool)
")
  if [ "$ACTUAL" = "$expectAction" ]; then
    echo "| $id | $text | $expectAction | $ACTUAL | ✅ |" >> "$REPORT"
    PASS=$((PASS+1))
  else
    echo "| $id | $text | $expectAction | **$ACTUAL** | ❌ |" >> "$REPORT"
    FAILED="$FAILED $id"
    FAIL=$((FAIL+1))
  fi
  # 逐会话清理，避免累积
  curl -s -X DELETE "$BASE/api/agent/conversations/$CONV" -H "Authorization: Bearer $TOKEN" >/dev/null
done < <(python -c "
import json, io, sys
sys.stdout.reconfigure(encoding='utf-8')
for c in json.load(open(r'$(cygpath -m "$MANIFEST")', encoding='utf-8'))['cases']:
    print('\t'.join([c['id'], c['text'], c['expect']['action'], c.get('expect',{}).get('argName',''), str(c.get('expect',{}).get('argValue',''))]))
")

echo
echo "结果：通过 $PASS / $((PASS+FAIL))"
[ -n "$FAILED" ] && echo "失败语料：$FAILED"
echo "报告：$REPORT"

# 清理临时账号
docker exec zhiyuan-mysql mysql -uzhiyuan -pzhiyuan123 college_recommendation \
  -e "DELETE FROM agent_message WHERE conversation_id IN (SELECT id FROM agent_conversation WHERE user_id IN (SELECT id FROM users WHERE username='$U')); DELETE FROM agent_conversation WHERE user_id IN (SELECT id FROM users WHERE username='$U'); DELETE FROM users WHERE username='$U';" >/dev/null 2>&1

[ "$FAIL" -eq 0 ]
