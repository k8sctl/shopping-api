# PromQL 기본 가이드

## PromQL이란?

Prometheus Query Language로 Prometheus에 저장된 메트릭을 조회하는 쿼리 언어예요.

```
SQL:    SELECT * FROM users WHERE age > 20
PromQL: http_requests_total{status="200"}
```

---

## 메트릭 종류

| 종류 | 설명 | 예시 |
|------|------|------|
| Counter | 계속 증가하는 값 | 총 요청 수, 총 에러 수 |
| Gauge | 올라갔다 내려갔다 하는 값 | CPU 사용률, 메모리 사용량 |
| Histogram | 값의 분포 (p50, p95, p99) | 응답 시간 분포 |
| Summary | Histogram과 유사, 클라이언트에서 계산 | - |

---

## 기본 문법

### 메트릭 조회
```
# 모든 Pod 정보
kube_pod_info

# 특정 네임스페이스 Pod만
kube_pod_info{namespace="shopping-api"}

# 여러 조건
kube_pod_info{namespace="shopping-api", created_by_kind="Deployment"}
```

### 레이블 필터 연산자
```
=   : 완전 일치
!=  : 불일치
=~  : 정규식 일치
!~  : 정규식 불일치

# 예시
kube_pod_info{namespace=~"shopping.*"}    # shopping으로 시작하는 네임스페이스
kube_pod_info{namespace!="kube-system"}  # kube-system 제외
```

### 집계 함수
```
# Pod 수 합계
count(kube_pod_info)

# 네임스페이스별 Pod 수
count by(namespace) (kube_pod_info)

# 평균
avg by(instance) (node_cpu_seconds_total)

# 합계
sum by(namespace) (container_memory_usage_bytes)
```

### 범위 쿼리 (Range Vector)
```
# 5분 동안의 데이터
node_cpu_seconds_total[5m]

# rate: 초당 변화율 (Counter에 사용)
rate(node_cpu_seconds_total[5m])

# irate: 순간 변화율
irate(http_requests_total[5m])
```

---

## 자주 쓰는 쿼리

### 노드 CPU 사용률 (%)
```
100 - (avg by(instance) (rate(node_cpu_seconds_total{mode="idle"}[5m])) * 100)
```

### 노드 메모리 사용률 (%)
```
(1 - node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes) * 100
```

### 노드 디스크 사용률 (%)
```
100 - (node_filesystem_avail_bytes{mountpoint="/"} / node_filesystem_size_bytes{mountpoint="/"} * 100)
```

### Pod 수 (네임스페이스별)
```
count by(namespace) (kube_pod_info)
```

### 컨테이너 CPU 사용률
```
rate(container_cpu_usage_seconds_total{namespace="shopping-api"}[5m]) * 100
```

### 컨테이너 메모리 사용량 (MB)
```
container_memory_usage_bytes{namespace="shopping-api"} / 1024 / 1024
```

### Pod 재시작 횟수
```
kube_pod_container_status_restarts_total{namespace="shopping-api"}
```

### HTTP 요청 수 (초당)
```
rate(http_requests_total[5m])
```

### HTTP 에러율 (%)
```
rate(http_requests_total{status=~"5.."}[5m]) / rate(http_requests_total[5m]) * 100
```

### 응답 시간 p95
```
histogram_quantile(0.95, rate(http_request_duration_seconds_bucket[5m]))
```

---

## Thanos Query vs Prometheus 차이

```
Prometheus (http://192.168.64.10:30090):
→ 최근 24h 데이터만 조회 가능

Thanos Query (http://192.168.64.10:30902):
→ 전체 기간 데이터 조회 가능 (MinIO 포함)
→ 동일한 PromQL 사용
```

Grafana에서 데이터소스를 `Thanos`로 변경하면 장기 데이터도 조회 가능해요.

---

## Grafana Explore에서 쿼리하기

```
왼쪽 메뉴 → Explore
→ Data source: Prometheus 또는 Thanos 선택
→ 우측 상단 Code 버튼 클릭
→ 쿼리 입력 후 Run query
```

---

## 유용한 팁

```
# 현재 값만 보고 싶으면 Instant query
# 시간에 따른 변화를 보고 싶으면 Range query (기본값)

# 단위 변환
/ 1024 / 1024        → bytes → MB
/ 1024 / 1024 / 1024 → bytes → GB
* 100                → 소수 → 퍼센트
```