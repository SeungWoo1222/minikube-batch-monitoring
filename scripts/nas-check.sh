#!/bin/sh
# ============================================================
# nas-check.sh : 원본 파일(SFTP)과 적재 파일(NAS)을 비교해서
#                결과를 node exporter용 .prom 파일로 남기는 스크립트
# 사용법 : sh nas-check.sh <원본 폴더> <NAS 폴더> <파일 이름> <feed 이름>
#   feed : 파일 종류를 구분하는 짧은 이름 (예: cstr_a). Grafana에서 줄 이름이 됨
#          날짜·빌드번호가 들어간 파일명은 매번 바뀌므로 label로 쓰지 않음 (cardinality 주의)
# 상태값 : 0 OK / 1 EMPTY / 2 NAS_MISSING / 3 SOURCE_MISSING / 4 SIZE_MISMATCH / 5 CHECK_ERROR
# 종료 코드 : 0 정상 / 10 0바이트(주황) / 20 그 외 실패(빨강) → Jenkins 색 판단용
# ============================================================

SRC_DIR="$1"   # 원본 폴더 (가짜 SFTP)
NAS_DIR="$2"   # 적재 폴더 (가짜 NAS)
FILE="$3"      # 확인할 파일 이름
FEED="$4"      # 파일 종류 이름
PROM_DIR="${PROM_DIR:-/data/textfile}"   # node exporter가 읽는 폴더 (지정 안 하면 이 값)

echo "===== [NAS-CHECK] start feed=$FEED file=$FILE ====="

# 파일 크기(바이트)를 출력. 파일이 없으면 -1 (0바이트 파일과 구분하기 위해 0이 아닌 -1)
# ※ 실제 SFTP로 바꿀 때는 원본 쪽을 이 함수 대신 sftp 명령으로 구하면 됨
size_of() {
  if [ -f "$1" ]; then wc -c < "$1" | tr -d ' '; else echo -1; fi
}

# 위에서부터 먼저 맞는 조건이 결과가 됨 (순서 중요)
if [ -z "$FEED" ] || [ ! -d "$SRC_DIR" ] || [ ! -d "$NAS_DIR" ]; then
  STATUS=5; SRC=-1; NAS=-1                                         # 확인 자체 실패 (인자 누락, 폴더 없음)
else
  SRC=$(size_of "$SRC_DIR/$FILE")
  NAS=$(size_of "$NAS_DIR/$FILE")
  if   [ "$SRC" -eq -1 ] && [ "$NAS" -eq -1 ];     then STATUS=3   # 원본부터 없음
  elif [ "$NAS" -eq -1 ];                          then STATUS=2   # 원본은 있는데 적재 안 됨
  elif [ "$SRC" -ne -1 ] && [ "$SRC" -ne "$NAS" ]; then STATUS=4   # 크기 다름 (덜 적재됨)
  elif [ "$NAS" -eq 0 ];                           then STATUS=1   # 0바이트 (원본도 0바이트)
  else                                                  STATUS=0   # 정상
  fi
fi

# 콘솔에 남기는 기록 (Jenkins Console Output에서 보임)
echo "[NAS-CHECK] source size = $SRC, nas size = $NAS (-1 = 없음)"
echo "[NAS-CHECK] STATUS=$STATUS (0 OK/1 EMPTY/2 NAS_MISSING/3 SOURCE_MISSING/4 SIZE_MISMATCH/5 CHECK_ERROR)"

# .prom 파일 쓰기: 임시 파일에 다 쓴 다음 mv로 한 번에 교체
# (node exporter가 반쯤 쓰인 파일을 읽는 것을 막기 위함. 임시 파일은 끝이 .prom이 아니라 무시됨)
OUT="$PROM_DIR/batch_file_$FEED.prom"
TMP="$OUT.$$"
cat > "$TMP" <<EOF
# HELP batch_file_status 0=OK 1=EMPTY 2=NAS_MISSING 3=SOURCE_MISSING 4=SIZE_MISMATCH 5=CHECK_ERROR
# TYPE batch_file_status gauge
batch_file_status{feed="$FEED"} $STATUS
# HELP batch_file_size_bytes file size in bytes, -1 means not found
# TYPE batch_file_size_bytes gauge
batch_file_size_bytes{feed="$FEED",location="source"} $SRC
batch_file_size_bytes{feed="$FEED",location="nas"} $NAS
EOF
mv "$TMP" "$OUT"

# Jenkins용 종료 코드
case "$STATUS" in
  0) exit 0 ;;
  1) exit 10 ;;
  *) exit 20 ;;
esac