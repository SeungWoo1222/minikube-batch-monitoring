#!/bin/sh
# nas-check.sh <NAS 날짜 폴더> <파일 이름> <feed 이름>
# 적재 파일과 .sha256(배치가 받으면서 계산한 해시)을 비교해서 결과를 .prom 파일로 남김
#
# 상태값
#   0 OK                 데이터 + .sha256 있음, 해시 일치, 크기 > 0
#   1 EMPTY              해시는 일치하지만 크기 0
#   2 NOT_FOUND          데이터 파일 없음 (받을 파일이 없었거나, 받기 전에 실패)
#   3 CHECKSUM_MISSING   데이터는 있는데 .sha256 없음 (쓰다가 죽었을 수 있음)
#   4 CHECKSUM_MISMATCH  해시 불일치 (적재 후 바뀌었거나 덜 써짐)
#   5 CHECK_ERROR        검사 자체를 못 함 (인자 누락, .sha256 형식 이상, sha256sum 없음)
#
# 판정만 기록하고 종료 코드는 항상 0 → Jenkins 빌드 결과를 바꾸지 않음 (판정은 Grafana에서)

# NAS_DIR = NAS파일의 위치
# FILE = 비교할 파일 ex) CSTR_A_b11.csv
# FEED = 어느 작업의 결과인지 구분하는 이름 ex) demo-batch-run
NAS_DIR="$1"; FILE="$2"; FEED="$3"
PROM_DIR="${PROM_DIR:-/data/textfile}"
DATA="$NAS_DIR/$FILE"
SUM="$DATA.sha256"

echo "===== [NAS-CHECK] start feed=$FEED file=$DATA ====="

# 결과를 담을 변수
SIZE=-1        # 적재 파일 크기 (-1 = 없음)
EXPECTED=""    # .sha256에 적힌 해시
ACTUAL=""      # 지금 적재 파일의 해시

# 판정: 위에서부터 차례로 확인해서, 처음 걸리는 조건으로 STATUS(0~5)를 정함
if [ -z "$NAS_DIR" ] || [ -z "$FILE" ] || [ -z "$FEED" ] || ! command -v sha256sum >/dev/null 2>&1; then
  STATUS=5 # CHECK_ERROR
elif [ ! -f "$DATA" ]; then # 데이터 파일이 없다면 NOT_FOUND
  STATUS=2 
else # 데이터 파일이 있는경우
  SIZE=$(wc -c < "$DATA" | tr -d ' ') # NAS에 적재된 파일의 크기
  if [ ! -f "$SUM" ]; then # 체크썸 파일이 없다면 CHECKSUM_MISSING
    STATUS=3 
  else # SHA 비교 시작
    EXPECTED=$(cut -d' ' -f1 "$SUM" | head -n 1) # .sha256 파일에서 해시 꺼냄
    ACTUAL=$(sha256sum "$DATA" | cut -d' ' -f1) # NAS에 적재된 파일의 해시를 계산
    if [ ${#EXPECTED} -ne 64 ]; then STATUS=5      # 해시는 항상 64글자, 아니라면 CHECK_ERROR
    elif [ "$EXPECTED" != "$ACTUAL" ]; then STATUS=4 # 두 해시가 다르면 CHECKSUM_MISMATCH
    elif [ "$SIZE" -eq 0 ]; then STATUS=1 # 해시는 일치하지만 사이즈가 0이라면 EMPTY
    else STATUS=0; fi
  fi
fi

# 숫자를 이름으로 바꿈
case "$STATUS" in
  0) NAME="OK";; 1) NAME="EMPTY";; 2) NAME="NOT_FOUND";;
  3) NAME="CHECKSUM_MISSING";; 4) NAME="CHECKSUM_MISMATCH";; *) NAME="CHECK_ERROR";;
esac

# 젠킨스 콘솔에 출력
# 출력 예시
# ===== [NAS-CHECK] start feed=demo-batch-run file=/data/nas-sim/inbound/20261118/CSTR_A_b11.csv =====
# [NAS-CHECK] nas file : 818 bytes
# [NAS-CHECK] expected : f0019428...361b
# [NAS-CHECK] actual   : 3c9e...(잘린 파일의 해시)
# [NAS-CHECK] result: 4 = CHECKSUM_MISMATCH
echo "[NAS-CHECK] nas file : $( [ "$SIZE" -eq -1 ] && echo "not found" || echo "$SIZE bytes" )"
echo "[NAS-CHECK] expected : ${EXPECTED:-none}"
echo "[NAS-CHECK] actual   : ${ACTUAL:-none}"
echo "[NAS-CHECK] result: $STATUS = $NAME"

# .prom 파일 쓰기
# .prom 파일은 임시 파일에 다 쓴 뒤 이름을 바꿈 (node_exporter가 반쯤 쓰인 파일을 읽지 않도록)
if [ -n "$FEED" ] && [ -d "$PROM_DIR" ]; then
  OUT="$PROM_DIR/batch_file_$FEED.prom"; TMP="$OUT.$$" # 파일의 최종 이름 ($$=지금 실행 중인 프로세스 번호)
  cat > "$TMP" <<PROM # 두 PROM 사이의 내용을 임시 파일에 씀
# HELP batch_file_status 0=OK 1=EMPTY 2=NOT_FOUND 3=CHECKSUM_MISSING 4=CHECKSUM_MISMATCH 5=CHECK_ERROR
# TYPE batch_file_status gauge
batch_file_status{feed="$FEED"} $STATUS
# HELP batch_file_size_bytes nas file size in bytes, -1 means not found
# TYPE batch_file_size_bytes gauge
batch_file_size_bytes{feed="$FEED"} $SIZE
PROM
  mv "$TMP" "$OUT" # 다 쓴 임시파일의 이름을 최종 이름으로 바꿈
else
  echo "[NAS-CHECK] skip .prom (no feed or no dir: $PROM_DIR)" 
fi

exit 0