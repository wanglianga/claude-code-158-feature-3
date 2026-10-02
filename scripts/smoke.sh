#!/usr/bin/env bash
# 端到端冒烟验证：预约 → 派单 → 扫码核验 → 称重 → 运输 → 二次称重 → 合规闭环 → 结算
# 以及主要异常场景。前提：应用已在 localhost:8080 运行。
set -euo pipefail

BASE=${BASE:-http://localhost:8080}
PASS=0; FAIL=0

# 解析 JSON 字段
jget() { python3 -c "import sys,json;d=json.load(sys.stdin);print(eval('d'+sys.argv[1]))" "$1"; }

req() { # method path role account body
  curl -s -X "$1" "$BASE$2" -H "X-Role: $3" -H "X-Account-Id: $4" \
       -H "Content-Type: application/json" ${5:+-d "$5"}
}

check() { # 描述 实际值 期望值
  if [ "$2" == "$3" ]; then PASS=$((PASS+1)); echo "  ✔ $1 ($2)";
  else FAIL=$((FAIL+1)); echo "  ✘ $1 期望[$3] 实际[$2]"; fi
}

tomorrow() { python3 -c "
from datetime import datetime,timedelta,timezone
d=(datetime.now(timezone.utc)+timedelta(days=1)).replace(hour=$1,minute=$2,second=0,microsecond=0)
# 明天北京时间 $1:$2 = UTC $1-8:$2
print(d.isoformat().replace('+00:00','Z'))"; }
# 北京时间明天 10:00 / 11:00（UTC 02:00 / 03:00）
WS=$(tomorrow 2 0); WE=$(tomorrow 3 0)

echo "=== 1. 主流程：预约→派单→核验→称重→运输→二次称重→闭环→结算 ==="
curl -s -X POST "$BASE/api/weighings/devices/DEV-001/heartbeat" -H "X-Role: RECYCLER" >/dev/null
AID=$(jget "['data']['id']" <<< "$(req POST /api/appointments MERCHANT merchant-1 "{\"storeId\":1,\"barrelCount\":2,\"estimatedWeightKg\":300,\"windowStart\":\"$WS\",\"windowEnd\":\"$WE\"}")")
echo "  预约 ID=$AID"
DID=$(jget "['data']['id']" <<< "$(req POST /api/dispatches RECYCLER recycler-1 "{\"appointmentId\":$AID,\"vehicleId\":1,\"driverId\":1,\"plantId\":1,\"plannedStart\":\"$WS\",\"plannedEnd\":\"$WE\",\"routeNote\":\"春熙路→经开区\"}")")
echo "  派单 ID=$DID"
req POST /api/dispatches/$DID/depart DRIVER driver-1 '{}' >/dev/null
req POST /api/dispatches/$DID/arrive DRIVER driver-1 '{}' >/dev/null
V=$(req POST /api/dispatches/$DID/verify DRIVER driver-1 '{"storeQr":"STORE-QR-1","deviceCode":"DEV-001","handoverName":"王店长","photoUrls":"https://img.example.com/site1.jpg"}')
check "扫码核验后进入装车" "$(jget "['data']['status']" <<< "$V")" "LOADING"
req POST /api/weighings DRIVER driver-1 "{\"dispatchId\":$DID,\"barrelCode\":\"BAR-001\",\"weightKg\":150,\"deviceCode\":\"DEV-001\",\"source\":\"DEVICE\",\"photoUrl\":\"https://img.example.com/w1.jpg\",\"handoverName\":\"王店长\"}" >/dev/null
req POST /api/weighings DRIVER driver-1 "{\"dispatchId\":$DID,\"barrelCode\":\"BAR-002\",\"weightKg\":148,\"deviceCode\":\"DEV-001\",\"source\":\"DEVICE\",\"photoUrl\":\"https://img.example.com/w2.jpg\",\"handoverName\":\"王店长\"}" >/dev/null
req POST /api/dispatches/$DID/load-complete DRIVER driver-1 '{}' >/dev/null
req POST /api/tracks/$DID DRIVER driver-1 '{"points":[{"lat":30.66,"lng":104.06},{"lat":30.60,"lng":104.10}]}' >/dev/null
req POST /api/dispatches/$DID/arrive-plant DRIVER driver-1 '{}' >/dev/null
MID=$(curl -s "$BASE/api/manifests" -H "X-Role: REGULATOR" | python3 -c "
import sys,json
for m in json.load(sys.stdin)['data']:
    if m['dispatchId']==$DID: print(m['id']); break")
echo "  联单 ID=$MID"
req POST /api/receipts PLANT plant-1 "{\"manifestId\":$MID,\"arrivalWeightKg\":296,\"barrelCount\":2,\"qualityCheck\":\"OK\",\"decision\":\"ACCEPT\",\"receivedBy\":\"厂验收员\"}" >/dev/null
DETAIL=$(req GET /api/manifests/$MID REGULATOR regulator-1)
check "联单闭环 CLOSED" "$(jget "['data']['manifest']['status']" <<< "$DETAIL")" "CLOSED"
check "合规结果 PASS" "$(jget "['data']['manifest']['complianceResult']" <<< "$DETAIL")" "PASS"
check "出店重量 298kg" "$(jget "['data']['manifest']['exitWeightKg']" <<< "$DETAIL")" "298.0"
check "到厂重量 296kg" "$(jget "['data']['manifest']['arrivalWeightKg']" <<< "$DETAIL")" "296.0"
check "结算金额 947.2 元" "$(jget "['data']['settlement']['amount']" <<< "$DETAIL")" "947.2"
check "称重记录 2 条" "$(jget "['data']['weighings'].__len__()" <<< "$DETAIL")" "2"
check "轨迹点 2 个" "$(jget "['data']['tracks'].__len__()" <<< "$DETAIL")" "2"

echo "=== 2. 一桶一码追溯 ==="
TRACE=$(req GET /api/barrels/BAR-001/trace REGULATOR regulator-1)
check "BAR-001 已到厂" "$(jget "['data']['barrel']['status']" <<< "$TRACE")" "AT_PLANT"
check "BAR-001 有称重档案" "$(jget "['data']['weighingRecords'].__len__()" <<< "$TRACE")" "1"

echo "=== 3. 商户质疑重量 → 冻结结算 → 协商调整 ==="
DIS=$(req POST /api/disputes MERCHANT merchant-1 "{\"manifestId\":$MID,\"reason\":\"对运输损耗 2kg 有异议\"}")
DISID=$(jget "['data']['id']" <<< "$DIS")
check "质疑后联单 DISPUTED" "$(jget "['data']['manifest']['status']" <<< "$(req GET /api/manifests/$MID MERCHANT merchant-1)")" "DISPUTED"
check "结算冻结 FROZEN" "$(jget "['data']['status']" <<< "$(req GET /api/settlements/by-manifest/$MID MERCHANT merchant-1)")" "FROZEN"
req POST /api/disputes/$DISID/resolve RECYCLER recycler-1 '{"resolution":"双方复核后按 297kg 结算","adjustedWeightKg":297}' >/dev/null
check "解决后联单 CLOSED" "$(jget "['data']['manifest']['status']" <<< "$(req GET /api/manifests/$MID MERCHANT merchant-1)")" "CLOSED"
check "调整后金额 950.4 元" "$(jget "['data']['amount']" <<< "$(req GET /api/settlements/by-manifest/$MID MERCHANT merchant-1)")" "950.4"

echo "=== 4. 商户临时取消 ==="
AID2=$(jget "['data']['id']" <<< "$(req POST /api/appointments MERCHANT merchant-1 "{\"storeId\":1,\"barrelCount\":1,\"estimatedWeightKg\":100,\"windowStart\":\"$WS\",\"windowEnd\":\"$WE\"}")")
DID2=$(jget "['data']['id']" <<< "$(req POST /api/dispatches RECYCLER recycler-1 "{\"appointmentId\":$AID2,\"vehicleId\":1,\"driverId\":1,\"plantId\":1,\"plannedStart\":\"$WS\",\"plannedEnd\":\"$WE\"}")")
req POST /api/appointments/$AID2/cancel MERCHANT merchant-1 '{"reason":"今日店休"}' >/dev/null
check "预约已取消" "$(jget "['data']['status']" <<< "$(req GET /api/appointments/$AID2 MERCHANT merchant-1)")" "CANCELLED"
check "派单联动取消" "$(jget "['data']['status']" <<< "$(req GET /api/dispatches/$DID2 RECYCLER recycler-1)")" "CANCELLED"
check "取消异常工单" "$(req GET "/api/exceptions/by-ref?refType=APPOINTMENT&refId=$AID2" RECYCLER recycler-1 | python3 -c "import sys,json;print(json.load(sys.stdin)['data'][0]['type'])")" "MERCHANT_CANCEL"

echo "=== 5. 手动补录（设备故障） ==="
AID3=$(jget "['data']['id']" <<< "$(req POST /api/appointments MERCHANT merchant-1 "{\"storeId\":1,\"barrelCount\":1,\"estimatedWeightKg\":100,\"windowStart\":\"$WS\",\"windowEnd\":\"$WE\"}")")
DID3=$(jget "['data']['id']" <<< "$(req POST /api/dispatches RECYCLER recycler-1 "{\"appointmentId\":$AID3,\"vehicleId\":1,\"driverId\":1,\"plantId\":1,\"plannedStart\":\"$WS\",\"plannedEnd\":\"$WE\"}")")
req POST /api/dispatches/$DID3/depart DRIVER driver-1 '{}' >/dev/null
req POST /api/dispatches/$DID3/arrive DRIVER driver-1 '{}' >/dev/null
req POST /api/dispatches/$DID3/verify DRIVER driver-1 '{"storeQr":"STORE-QR-1","deviceCode":"DEV-001","handoverName":"王店长"}' >/dev/null
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/weighings" -H "X-Role: DRIVER" -H "Content-Type: application/json" -d "{\"dispatchId\":$DID3,\"barrelCode\":\"BAR-003\",\"weightKg\":120,\"source\":\"MANUAL\"}")
check "补录无原因被拒(400)" "$CODE" "400"
req POST /api/weighings DRIVER driver-1 "{\"dispatchId\":$DID3,\"barrelCode\":\"BAR-003\",\"weightKg\":120,\"source\":\"MANUAL\",\"note\":\"设备故障人工复核\",\"photoUrl\":\"https://img.example.com/m1.jpg\"}" >/dev/null
MID3=$(curl -s "$BASE/api/manifests" -H "X-Role: REGULATOR" | python3 -c "
import sys,json
for m in json.load(sys.stdin)['data']:
    if m['dispatchId']==$DID3: print(m['id']); break")
check "联单记录补录 1 条" "$(jget "['data']['manifest']['manualWeighCount']" <<< "$(req GET /api/manifests/$MID3 REGULATOR regulator-1)")" "1"

echo "=== 6. 处置厂拒收（混入厨余） ==="
curl -s -X POST "$BASE/api/weighings/devices/DEV-002/heartbeat" -H "X-Role: RECYCLER" >/dev/null
AID4=$(jget "['data']['id']" <<< "$(req POST /api/appointments MERCHANT merchant-1 "{\"storeId\":2,\"barrelCount\":1,\"estimatedWeightKg\":100,\"windowStart\":\"$WS\",\"windowEnd\":\"$WE\"}")")
DID4=$(jget "['data']['id']" <<< "$(req POST /api/dispatches RECYCLER recycler-1 "{\"appointmentId\":$AID4,\"vehicleId\":1,\"driverId\":1,\"plantId\":1,\"plannedStart\":\"$WS\",\"plannedEnd\":\"$WE\"}")")
req POST /api/dispatches/$DID4/depart DRIVER driver-1 '{}' >/dev/null
req POST /api/dispatches/$DID4/arrive DRIVER driver-1 '{}' >/dev/null
req POST /api/dispatches/$DID4/verify DRIVER driver-1 '{"storeQr":"STORE-QR-2","deviceCode":"DEV-002","handoverName":"陈店长"}' >/dev/null
req POST /api/weighings DRIVER driver-1 "{\"dispatchId\":$DID4,\"barrelCode\":\"BAR-101\",\"weightKg\":100,\"deviceCode\":\"DEV-002\",\"source\":\"DEVICE\"}" >/dev/null
req POST /api/dispatches/$DID4/load-complete DRIVER driver-1 '{}' >/dev/null
req POST /api/tracks/$DID4 DRIVER driver-1 '{"points":[{"lat":30.65,"lng":104.05}]}' >/dev/null
req POST /api/dispatches/$DID4/arrive-plant DRIVER driver-1 '{}' >/dev/null
MID4=$(curl -s "$BASE/api/manifests" -H "X-Role: REGULATOR" | python3 -c "
import sys,json
for m in json.load(sys.stdin)['data']:
    if m['dispatchId']==$DID4: print(m['id']); break")
req POST /api/receipts PLANT plant-1 "{\"manifestId\":$MID4,\"arrivalWeightKg\":100,\"barrelCount\":1,\"qualityCheck\":\"CONTAMINATED\",\"decision\":\"REJECT\",\"rejectReason\":\"混入大量厨余垃圾\",\"receivedBy\":\"厂验收员\"}" >/dev/null
check "联单 REJECTED" "$(jget "['data']['manifest']['status']" <<< "$(req GET /api/manifests/$MID4 REGULATOR regulator-1)")" "REJECTED"

echo "=== 7. 运输损耗异常 → 阻断闭环 → 处理后恢复 ==="
AID5=$(jget "['data']['id']" <<< "$(req POST /api/appointments MERCHANT merchant-1 "{\"storeId\":2,\"barrelCount\":1,\"estimatedWeightKg\":100,\"windowStart\":\"$WS\",\"windowEnd\":\"$WE\"}")")
DID5=$(jget "['data']['id']" <<< "$(req POST /api/dispatches RECYCLER recycler-1 "{\"appointmentId\":$AID5,\"vehicleId\":1,\"driverId\":1,\"plantId\":1,\"plannedStart\":\"$WS\",\"plannedEnd\":\"$WE\"}")")
req POST /api/dispatches/$DID5/depart DRIVER driver-1 '{}' >/dev/null
req POST /api/dispatches/$DID5/arrive DRIVER driver-1 '{}' >/dev/null
req POST /api/dispatches/$DID5/verify DRIVER driver-1 '{"storeQr":"STORE-QR-2","deviceCode":"DEV-002","handoverName":"陈店长"}' >/dev/null
req POST /api/weighings DRIVER driver-1 "{\"dispatchId\":$DID5,\"barrelCode\":\"BAR-102\",\"weightKg\":100,\"deviceCode\":\"DEV-002\",\"source\":\"DEVICE\"}" >/dev/null
req POST /api/dispatches/$DID5/load-complete DRIVER driver-1 '{}' >/dev/null
req POST /api/tracks/$DID5 DRIVER driver-1 '{"points":[{"lat":30.65,"lng":104.05}]}' >/dev/null
req POST /api/dispatches/$DID5/arrive-plant DRIVER driver-1 '{}' >/dev/null
MID5=$(curl -s "$BASE/api/manifests" -H "X-Role: REGULATOR" | python3 -c "
import sys,json
for m in json.load(sys.stdin)['data']:
    if m['dispatchId']==$DID5: print(m['id']); break")
req POST /api/receipts PLANT plant-1 "{\"manifestId\":$MID5,\"arrivalWeightKg\":80,\"barrelCount\":1,\"qualityCheck\":\"OK\",\"decision\":\"ACCEPT\",\"receivedBy\":\"厂验收员\"}" >/dev/null
check "损耗 20% 联单 EXCEPTION" "$(jget "['data']['manifest']['status']" <<< "$(req GET /api/manifests/$MID5 REGULATOR regulator-1)")" "EXCEPTION"
TID=$(req GET "/api/exceptions/by-ref?refType=MANIFEST&refId=$MID5" RECYCLER recycler-1 | python3 -c "
import sys,json
for t in json.load(sys.stdin)['data']:
    if t['type']=='TRANSPORT_LOSS_ABNORMAL': print(t['id']); break")
req POST /api/exceptions/$TID/resolve RECYCLER recycler-1 '{"resolution":"确认为阀门渗漏，已维修备案"}' >/dev/null
check "工单处理后备联单 CLOSED" "$(jget "['data']['manifest']['status']" <<< "$(req GET /api/manifests/$MID5 REGULATOR regulator-1)")" "CLOSED"

echo "=== 8. 监管抽查 + 风险名单 + 报表 ==="
req POST /api/inspections REGULATOR regulator-1 "{\"manifestId\":$MID,\"result\":\"PASS\",\"note\":\"抽查合格\"}" >/dev/null
req POST /api/risks/scan REGULATOR regulator-1 '{}' >/dev/null
RISKS=$(req GET /api/risks REGULATOR regulator-1)
check "风险名单非空" "$(python3 -c "import sys,json;print(len(json.load(sys.stdin)['data'])>0)" <<< "$RISKS")" "True"
EFF=$(req GET /api/reports/vehicle-efficiency RECYCLER recycler-1)
check "车辆效率报表" "$(python3 -c "import sys,json;print(len(json.load(sys.stdin)['data'])>0)" <<< "$EFF")" "True"
CERT=$(req GET /api/reports/stores/1/certificate MERCHANT merchant-1)
check "商户回收证明联单数≥1" "$(python3 -c "import sys,json;print(json.load(sys.stdin)['data']['manifestCount']>=1)" <<< "$CERT")" "True"
TREND=$(req GET /api/reports/regulator/trend REGULATOR regulator-1)
check "监管趋势报表" "$(python3 -c "import sys,json;print(len(json.load(sys.stdin)['data'])>0)" <<< "$TREND")" "True"

echo "=== 9. 监管抽查追踪：链路 / 疑似违规 / 现场检查回填 / 风险降级 ==="
SH_DATE=$(python3 -c "from datetime import datetime;from zoneinfo import ZoneInfo;print(datetime.now(ZoneInfo('Asia/Shanghai')).date())")
CHAIN=$(req GET "/api/regulatory/chains?storeId=1&date=$SH_DATE" REGULATOR regulator-1)
check "链路按商户+日期返回门店" "$(python3 -c "import sys,json;print(json.load(sys.stdin)['data']['storeCount']>=1)" <<< "$CHAIN")" "True"
check "链路批次包含主流程联单" "$(python3 -c "
import sys,json
d=json.load(sys.stdin)['data']
print(any(any(b['manifestId']==$MID for b in s['batches']) for s in d['stores']))" <<< "$CHAIN")" "True"
check "批次司机为张师傅" "$(python3 -c "
import sys,json
d=json.load(sys.stdin)['data']
print(next(b['driver']['name'] for s in d['stores'] for b in s['batches'] if b['manifestId']==$MID))" <<< "$CHAIN")" "张师傅"
check "批次车辆为川A12345" "$(python3 -c "
import sys,json
d=json.load(sys.stdin)['data']
print(next(b['vehicle']['plateNo'] for s in d['stores'] for b in s['batches'] if b['manifestId']==$MID))" <<< "$CHAIN")" "川A12345"
check "批次结算金额 950.4" "$(python3 -c "
import sys,json
d=json.load(sys.stdin)['data']
print(next(str(b['settlement']['amount']) for s in d['stores'] for b in s['batches'] if b['manifestId']==$MID))" <<< "$CHAIN")" "950.4"

# 巡查扫描：种子门店 4（开业 40 天从未回收）应被标记疑似违规并生成现场检查任务
SCAN=$(req POST /api/regulatory/patrol/scan REGULATOR regulator-1 '{}')
check "巡查扫描生成现场检查任务(≥1)" "$(python3 -c "import sys,json;print(json.load(sys.stdin)['data']['tasksCreated']>=1)" <<< "$SCAN")" "True"
TASK=$(req GET "/api/regulatory/tasks?storeId=4" REGULATOR regulator-1)
check "门店4任务状态 PENDING" "$(jget "['data'][0]['status']" <<< "$TASK")" "PENDING"
check "任务来源 RISK_SCAN" "$(jget "['data'][0]['source']" <<< "$TASK")" "RISK_SCAN"
SI=$(jget "['data'][0]['id']" <<< "$TASK")
PROFILE=$(req GET /api/regulatory/risk-profiles/4 REGULATOR regulator-1)
check "门店4风险等级 WATCH" "$(jget "['data']['riskLevel']" <<< "$PROFILE")" "WATCH"
check "门店4风险分 50" "$(jget "['data']['riskScore']" <<< "$PROFILE")" "50"

# 回填现场检查：缺照片被拒
CODE2=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/regulatory/tasks/$SI/fill" -H "X-Role: REGULATOR" -H "Content-Type: application/json" \
  -d '{"checkResult":"NON_COMPLIANT","photoUrls":[],"interviewResult":"约谈","rectifyDeadline":"2099-01-01"}')
check "回填缺照片被拒(400)" "$CODE2" "400"
DEADLINE=$(python3 -c "from datetime import date,timedelta;print(date.today()+timedelta(days=7))")
FILLED=$(req POST /api/regulatory/tasks/$SI/fill REGULATOR regulator-1 "{\"checkResult\":\"NON_COMPLIANT\",\"photoUrls\":[\"https://img.example.com/site-1.jpg\",\"https://img.example.com/site-2.jpg\"],\"interviewResult\":\"老板承认废油交无资质收运人，责令改正\",\"rectifyDeadline\":\"$DEADLINE\"}")
check "回填后任务 RECTIFYING" "$(jget "['data']['status']" <<< "$FILLED")" "RECTIFYING"
check "回填照片已留痕" "$(python3 -c "import sys,json;print('site-1.jpg' in json.load(sys.stdin)['data']['photoUrls'])" <<< "$FILLED")" "True"
KEY=$(req GET /api/regulatory/risk-profiles/key-patrol REGULATOR regulator-1)
check "门店4进入重点巡查名单" "$(python3 -c "import sys,json;print(any(p['storeId']==4 and p['riskLevel']=='KEY' for p in json.load(sys.stdin)['data']))" <<< "$KEY")" "True"

# 再巡查：仍无记录，不重复生成任务，维持 KEY
SCAN2=$(req POST /api/regulatory/patrol/scan REGULATOR regulator-1 '{}')
check "再巡查不重复生成任务" "$(jget "['data']['tasksCreated']" <<< "$SCAN2")" "0"
check "再巡查维持 KEY" "$(jget "['data']['riskLevel']" <<< "$(req GET /api/regulatory/risk-profiles/4 REGULATOR regulator-1)")" "KEY"

# 门店 4 恢复正常回收：完整闭环一次
curl -s -X POST "$BASE/api/weighings/devices/DEV-004/heartbeat" -H "X-Role: RECYCLER" >/dev/null
AID6=$(jget "['data']['id']" <<< "$(req POST /api/appointments MERCHANT merchant-1 "{\"storeId\":4,\"barrelCount\":1,\"estimatedWeightKg\":100,\"windowStart\":\"$WS\",\"windowEnd\":\"$WE\"}")")
DID6=$(jget "['data']['id']" <<< "$(req POST /api/dispatches RECYCLER recycler-1 "{\"appointmentId\":$AID6,\"vehicleId\":1,\"driverId\":1,\"plantId\":1,\"plannedStart\":\"$WS\",\"plannedEnd\":\"$WE\"}")")
req POST /api/dispatches/$DID6/depart DRIVER driver-1 '{}' >/dev/null
req POST /api/dispatches/$DID6/arrive DRIVER driver-1 '{}' >/dev/null
req POST /api/dispatches/$DID6/verify DRIVER driver-1 '{"storeQr":"STORE-QR-4","deviceCode":"DEV-004","handoverName":"赵老板"}' >/dev/null
req POST /api/weighings DRIVER driver-1 "{\"dispatchId\":$DID6,\"barrelCode\":\"BAR-301\",\"weightKg\":90,\"deviceCode\":\"DEV-004\",\"source\":\"DEVICE\",\"handoverName\":\"赵老板\"}" >/dev/null
req POST /api/dispatches/$DID6/load-complete DRIVER driver-1 '{}' >/dev/null
req POST /api/tracks/$DID6 DRIVER driver-1 '{"points":[{"lat":30.67,"lng":104.07}]}' >/dev/null
req POST /api/dispatches/$DID6/arrive-plant DRIVER driver-1 '{}' >/dev/null
MID6=$(curl -s "$BASE/api/manifests" -H "X-Role: REGULATOR" | python3 -c "
import sys,json
for m in json.load(sys.stdin)['data']:
    if m['dispatchId']==$DID6: print(m['id']); break")
req POST /api/receipts PLANT plant-1 "{\"manifestId\":$MID6,\"arrivalWeightKg\":90,\"barrelCount\":1,\"qualityCheck\":\"OK\",\"decision\":\"ACCEPT\",\"receivedBy\":\"厂验收员\"}" >/dev/null
check "门店4恢复回收联单 CLOSED" "$(jget "['data']['manifest']['status']" <<< "$(req GET /api/manifests/$MID6 REGULATOR regulator-1)")" "CLOSED"

# 两轮巡查：风险 80 → 40(WATCH) → 0(NORMAL)
req POST /api/regulatory/patrol/scan REGULATOR regulator-1 '{}' >/dev/null
check "首轮巡查风险分降至 40" "$(jget "['data']['riskScore']" <<< "$(req GET /api/regulatory/risk-profiles/4 REGULATOR regulator-1)")" "40"
check "首轮巡查退出重点巡查" "$(jget "['data']['keyPatrol']" <<< "$(req GET /api/regulatory/risk-profiles/4 REGULATOR regulator-1)")" "False"
req POST /api/regulatory/patrol/scan REGULATOR regulator-1 '{}' >/dev/null
check "二轮巡查风险分清零" "$(jget "['data']['riskScore']" <<< "$(req GET /api/regulatory/risk-profiles/4 REGULATOR regulator-1)")" "0"
check "二轮巡查恢复 NORMAL" "$(jget "['data']['riskLevel']" <<< "$(req GET /api/regulatory/risk-profiles/4 REGULATOR regulator-1)")" "NORMAL"
CLOSED_TASK=$(req POST /api/regulatory/tasks/$SI/verify REGULATOR regulator-1 '{"passed":true,"closeNote":"已签订合规收运合同并恢复交运，复核通过"}')
check "整改复核通过任务关闭" "$(jget "['data']['status']" <<< "$CLOSED_TASK")" "VERIFIED_CLOSED"

echo "=== 10. Kafka 事件审计流水 ==="
AUDIT=$(psql "postgresql://wasteoil:wasteoil123@localhost:5433/wasteoil" -tAc "SELECT count(*) FROM audit_logs" 2>/dev/null || echo "0")
check "审计流水已落库(>20 条)" "$(python3 -c "print($AUDIT>20)")" "True"

echo ""
echo "=========================================="
echo "冒烟结果: 通过 $PASS 项, 失败 $FAIL 项"
[ "$FAIL" == "0" ] && echo "SMOKE_OK" || exit 1
