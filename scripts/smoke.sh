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

# 读取风险名单中指定 id 条目的某字段
risk_field() { # id field
  req GET /api/risks REGULATOR regulator-1 | python3 -c "
import sys,json
rid=$1; field='$2'
for r in json.load(sys.stdin)['data']:
    if r['id']==rid: print(r[field]); break"
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

echo "=== 9. 监管抽查链路（商户+日期+回收批次） ==="
CHAIN=$(req GET /api/supervision/manifests/$MID/chain REGULATOR regulator-1)
check "批次含预约信息" "$(jget "['data']['appointment'] is not None" <<< "$CHAIN")" "True"
check "批次司机张师傅" "$(jget "['data']['dispatch']['driver']['name']" <<< "$CHAIN")" "张师傅"
check "批次车辆川A12345" "$(jget "['data']['dispatch']['vehicle']['plateNo']" <<< "$CHAIN")" "川A12345"
check "批次处置厂绿源" "$(jget "['data']['dispatch']['plant']['name']" <<< "$CHAIN")" "绿源废油处置厂"
check "批次称重 2 条" "$(jget "['data']['weighings'].__len__()" <<< "$CHAIN")" "2"
check "批次到厂接收" "$(jget "['data']['plantReceipt']['decision']" <<< "$CHAIN")" "ACCEPT"
check "批次结算 950.4 元" "$(jget "['data']['settlement']['amount']" <<< "$CHAIN")" "950.4"
check "批次轨迹点 2 个" "$(jget "['data']['trackPointCount']" <<< "$CHAIN")" "2"
# 按街道（URL 编码中文）查询，覆盖春熙路门店
STREET_ENC=$(python3 -c "import urllib.parse;print(urllib.parse.quote('春熙路'))")
STREET_CHAIN=$(curl -s "$BASE/api/supervision/streets/$STREET_ENC/chains?date=$(date +%F)" -H "X-Role: REGULATOR" -H "X-Account-Id: regulator-1")
check "街道抽查含春熙路门店" "$(python3 -c "import sys,json;print(any(c['storeId']==1 for c in json.load(sys.stdin)['data']))" <<< "$STREET_CHAIN")" "True"

echo "=== 10. 长期无回收 → 检查任务回填 → 恢复正常回收风险下降 ==="
# 门店 3（老城大排档）全库无回收记录，扫描后应为 WATCH 并自动生成现场检查任务
req POST /api/risks/scan REGULATOR regulator-1 '{}' >/dev/null
TASKS=$(req GET /api/inspection-tasks/by-store/3 REGULATOR regulator-1)
TASK_ID=$(python3 -c "
import sys,json
for t in json.load(sys.stdin)['data']:
    if t['triggerReason']=='NO_RECYCLING' and t['status']=='OPEN': print(t['id']); break" <<< "$TASKS")
HAS_TASK=$( [ -n "$TASK_ID" ] && echo True || echo False )
check "自动生成长期无回收检查任务" "$HAS_TASK" "True"
RISK3_ID=$(python3 -c "
import sys,json
for r in json.load(sys.stdin)['data']:
    if r['storeId']==3 and r['indicator']=='NO_RECYCLING': print(r['id']); break" <<< "$(req GET /api/risks REGULATOR regulator-1)")
check "门店3风险为关注WATCH" "$(risk_field "$RISK3_ID" riskLevel)" "WATCH"
# 另建人工检查任务（验证风险解除时未结任务自动取消）
MANUAL_TASK=$(req POST /api/inspection-tasks REGULATOR regulator-1 "{\"storeId\":3,\"note\":\"复查整改\"}")
MANUAL_TASK_ID=$(jget "['data']['id']" <<< "$MANUAL_TASK")
# 认领并回填：照片、约谈结果、整改期限、不合格
req POST /api/inspection-tasks/$TASK_ID/claim REGULATOR regulator-1 '{}' >/dev/null
DONE=$(req POST /api/inspection-tasks/$TASK_ID/complete REGULATOR regulator-1 '{"inspectionPhotoUrls":"https://img.example.com/i1.jpg,https://img.example.com/i2.jpg","interviewResult":"约谈负责人，承认交无资质人员","rectificationDeadline":"2099-01-01","inspectionResult":"UNQUALIFIED","note":"责令限期整改"}')
check "检查任务已回填结案" "$(jget "['data']['status']" <<< "$DONE")" "COMPLETED"
check "回填现场照片" "$(python3 -c "import sys;print('i2.jpg' in sys.stdin.read())" <<< "$(jget "['data']['inspectionPhotoUrls']" <<< "$DONE")")" "True"
check "不合格风险复核为CONFIRMED" "$(risk_field "$RISK3_ID" status)" "CONFIRMED"
# 商户恢复正常回收：门店3 完成一趟合规闭环，风险由关注解除，未结人工任务自动取消
curl -s -X POST "$BASE/api/weighings/devices/DEV-003/heartbeat" -H "X-Role: RECYCLER" >/dev/null
AID6=$(jget "['data']['id']" <<< "$(req POST /api/appointments MERCHANT merchant-1 "{\"storeId\":3,\"barrelCount\":1,\"estimatedWeightKg\":90,\"windowStart\":\"$WS\",\"windowEnd\":\"$WE\"}")")
DID6=$(jget "['data']['id']" <<< "$(req POST /api/dispatches RECYCLER recycler-1 "{\"appointmentId\":$AID6,\"vehicleId\":1,\"driverId\":1,\"plantId\":1,\"plannedStart\":\"$WS\",\"plannedEnd\":\"$WE\"}")")
req POST /api/dispatches/$DID6/depart DRIVER driver-1 '{}' >/dev/null
req POST /api/dispatches/$DID6/arrive DRIVER driver-1 '{}' >/dev/null
req POST /api/dispatches/$DID6/verify DRIVER driver-1 '{"storeQr":"STORE-QR-3","deviceCode":"DEV-003","handoverName":"刘老板"}' >/dev/null
req POST /api/weighings DRIVER driver-1 "{\"dispatchId\":$DID6,\"barrelCode\":\"BAR-201\",\"weightKg\":80,\"deviceCode\":\"DEV-003\",\"source\":\"DEVICE\",\"handoverName\":\"刘老板\"}" >/dev/null
req POST /api/dispatches/$DID6/load-complete DRIVER driver-1 '{}' >/dev/null
req POST /api/tracks/$DID6 DRIVER driver-1 '{"points":[{"lat":30.65,"lng":104.05}]}' >/dev/null
req POST /api/dispatches/$DID6/arrive-plant DRIVER driver-1 '{}' >/dev/null
MID6=$(curl -s "$BASE/api/manifests" -H "X-Role: REGULATOR" | python3 -c "
import sys,json
for m in json.load(sys.stdin)['data']:
    if m['dispatchId']==$DID6: print(m['id']); break")
req POST /api/receipts PLANT plant-1 "{\"manifestId\":$MID6,\"arrivalWeightKg\":79,\"barrelCount\":1,\"qualityCheck\":\"OK\",\"decision\":\"ACCEPT\",\"receivedBy\":\"厂验收员\"}" >/dev/null
check "恢复回收后联单CLOSED" "$(jget "['data']['manifest']['status']" <<< "$(req GET /api/manifests/$MID6 REGULATOR regulator-1)")" "CLOSED"
check "风险解除RECOVERED" "$(risk_field "$RISK3_ID" status)" "RECOVERED"
check "未结人工任务自动取消" "$(jget "['data']['status']" <<< "$(req GET /api/inspection-tasks/$MANUAL_TASK_ID REGULATOR regulator-1)")" "CANCELLED"
check "门店3当日抽查链路1批次" "$(curl -s "$BASE/api/supervision/stores/3/chains?date=$(date +%F)" -H "X-Role: REGULATOR" | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['batchCount'])")" "1"

echo "=== 11. Kafka 事件审计流水 ==="
AUDIT=$(psql "postgresql://wasteoil:wasteoil123@localhost:5433/wasteoil" -tAc "SELECT count(*) FROM audit_logs" 2>/dev/null || echo "0")
check "审计流水已落库(>20 条)" "$(python3 -c "print($AUDIT>20)")" "True"

echo ""
echo "=========================================="
echo "冒烟结果: 通过 $PASS 项, 失败 $FAIL 项"
[ "$FAIL" == "0" ] && echo "SMOKE_OK" || exit 1
