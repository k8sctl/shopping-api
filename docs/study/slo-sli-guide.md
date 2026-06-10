# SLI / SLO / SLA 가이드

## 개념 정리

### SLI (Service Level Indicator)
```
서비스 품질을 측정하는 지표 (측정값)

예시:
- 응답 시간: p95 < 200ms
- 에러율: 5xx 응답 / 전체 응답
- 가용성: 성공 응답 / 전체 응답
```

### SLO (Service Level Objective)
```
SLI의 목표값 (내부 목표)

예시:
- 가용성 SLO: 99.9%
- 응답 시간 SLO: p95 < 500ms
- 에러율 SLO: < 1%
```

### SLA (Service Level Agreement)
```
SLO를 외부에 약속한 것 (계약)

특징:
- 위반 시 패널티 발생
- SLO보다 여유있게 설정
- 예: SLO 99.9% → SLA 99.5%
```

### Error Budget
```
SLO를 기반으로 허용되는 장애 시간

계산:
가용성 SLO 99.9% 기준:
- 한 달(30일): 43.8분 다운타임 허용
- 1주일: 10.1분 허용
- 하루: 1.44분 허용

Error Budget이 소진되면:
→ 새 기능 개발 중단
→ 안정성 개선에 집중
```

---

## shopping-api SLO 정의

### 1차 SLO (현재)
```
가용성:
- SLI: 성공 응답(2xx, 3xx, 4xx) / 전체 응답
- SLO: 99.9%

응답 시간:
- SLI: p95 응답 시간
- SLO: < 500ms (k6 부하 테스트 후 조정 예정)

에러율:
- SLI: 5xx 응답 / 전체 응답
- SLO: < 1%
```

### 2차 SLO (k6 부하 테스트 후)
```
실제 데이터 기반으로 조정 예정
```

---

## SLO 대시보드 구성

### PromQL 쿼리

**초당 요청 수 (RPS):**
```
rate(http_server_requests_seconds_count{namespace="shopping-api"}[1m])
```

**에러율 (Error Rate %):**
```
(sum(rate(http_server_requests_seconds_count{namespace="shopping-api", status=~"5.."}[1m])) or vector(0))
/
sum(rate(http_server_requests_seconds_count{namespace="shopping-api"}[1m]))
* 100
```

**p95 응답 시간 (ms):**
```
histogram_quantile(0.95, sum by(le) (rate(http_server_requests_seconds_bucket{namespace="shopping-api"}[1m]))) * 1000
```

**가용성 (Availability %):**
```
(sum(rate(http_server_requests_seconds_count{namespace="shopping-api", status!~"5.."}[5m]))
/
sum(rate(http_server_requests_seconds_count{namespace="shopping-api"}[5m])))
* 100
```

### 패널 설정

| 패널 | Visualization | Threshold |
|------|--------------|-----------|
| 초당 요청 수 | Time series | - |
| 에러율 | Time series | 1% 이상 red |
| p95 응답 시간 | Time series | 500ms 이상 red |
| 가용성 | Stat | 99 이상 green |

---

## Spring Boot Histogram 활성화

p95 응답 시간 측정을 위해 `application.yml` 설정 필요:

```yaml
management:
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true
```

기본값은 비활성화되어 있어서 `histogram_quantile` 쿼리가 동작하지 않음.

---

## SLO 기반 알람 설정 (향후)

k6 부하 테스트 후 실제 데이터 기반으로:

```yaml
additionalPrometheusRulesMap:
  shopping-api-slo:
    groups:
      - name: shopping-api.slo
        rules:
          - alert: HighErrorRate
            expr: |
              (sum(rate(http_server_requests_seconds_count{namespace="shopping-api", status=~"5.."}[5m]))
              / sum(rate(http_server_requests_seconds_count{namespace="shopping-api"}[5m]))) * 100 > 1
            for: 5m
            labels:
              severity: critical
            annotations:
              summary: "에러율 SLO 위반"
              description: "에러율이 1% 초과"

          - alert: HighLatency
            expr: |
              histogram_quantile(0.95,
                sum by(le) (rate(http_server_requests_seconds_bucket{namespace="shopping-api"}[5m]))
              ) * 1000 > 500
            for: 5m
            labels:
              severity: warning
            annotations:
              summary: "응답 시간 SLO 위반"
              description: "p95 응답 시간이 500ms 초과"
```