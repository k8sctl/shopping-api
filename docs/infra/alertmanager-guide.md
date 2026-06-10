# AlertManager 가이드

## 개요

AlertManager는 Prometheus에서 발생한 알람을 받아 적절한 수신처로 전달하는 컴포넌트예요.

```
Prometheus → 알람 규칙 평가 → AlertManager → Slack/Email/PagerDuty 등
```

---

## 알람 상태

```
INACTIVE: 조건 미충족 (정상)
PENDING:  조건 충족됐지만 for 시간 미경과
FIRING:   조건 충족 + for 시간 경과 → AlertManager로 전달
```

---

## 현재 알람 규칙 (1차)

`k8s/monitoring/values.yaml`:

```yaml
additionalPrometheusRulesMap:
  shopping-api-rules:
    groups:
      - name: shopping-api.availability
        rules:
          - alert: PodCrashLoopBackOff
            expr: kube_pod_container_status_waiting_reason{reason="CrashLoopBackOff", namespace="shopping-api"} == 1
            for: 1m
            labels:
              severity: critical
            annotations:
              summary: "Pod CrashLoopBackOff 발생"
              description: "{{ $labels.pod }} 가 CrashLoopBackOff 상태입니다"

          - alert: PodDown
            expr: kube_pod_status_phase{phase="Failed", namespace="shopping-api"} == 1
            for: 1m
            labels:
              severity: critical
            annotations:
              summary: "Pod Down"
              description: "{{ $labels.pod }} 가 Failed 상태입니다"

          - alert: NodeNotReady
            expr: kube_node_status_condition{condition="Ready", status="true"} == 0
            for: 1m
            labels:
              severity: critical
            annotations:
              summary: "노드 NotReady"
              description: "{{ $labels.node }} 가 NotReady 상태입니다"
```

### 알람 선정 기준
```
1차 알람: 100% 문제인 상황만
→ PodCrashLoopBackOff: 서비스 중단 직결
→ PodDown: 서비스 중단 직결
→ NodeNotReady: 인프라 장애

2차 알람 (k6 부하 테스트 후):
→ HighErrorRate: 실제 데이터 기반 임계값
→ HighLatency: 실제 데이터 기반 임계값
→ HighCPU/Memory: 실제 사용량 보고 설정
```

---

## 수신처 설정

### Slack 연동

```yaml
alertmanager:
  enabled: true
  config:
    global:
      resolve_timeout: 5m
    route:
      group_by: ['alertname', 'namespace']
      group_wait: 30s
      group_interval: 5m
      repeat_interval: 12h
      receiver: slack
    receivers:
      - name: slack
        slack_configs:
          - api_url: 'https://hooks.slack.com/services/YOUR/WEBHOOK/URL'
            channel: '#alerts'
            send_resolved: true
            title: '[{{ .Status | toUpper }}] {{ .CommonAnnotations.summary }}'
            text: '{{ .CommonAnnotations.description }}'
```

### Email 연동

```yaml
alertmanager:
  config:
    global:
      smtp_smarthost: 'smtp.gmail.com:587'
      smtp_from: 'alertmanager@example.com'
      smtp_auth_username: 'your@gmail.com'
      smtp_auth_password: 'your-app-password'
    route:
      receiver: email
    receivers:
      - name: email
        email_configs:
          - to: 'team@example.com'
            send_resolved: true
```

### Severity별 라우팅

```yaml
alertmanager:
  config:
    route:
      receiver: default
      routes:
        - match:
            severity: critical
          receiver: slack-critical
        - match:
            severity: warning
          receiver: slack-warning
    receivers:
      - name: slack-critical
        slack_configs:
          - channel: '#alerts-critical'
      - name: slack-warning
        slack_configs:
          - channel: '#alerts-warning'
```

---

## AlertManager UI

```
http://192.168.64.10:30090 → Alerts 탭
→ FIRING: 현재 발생 중인 알람
→ PENDING: 곧 발생할 알람
→ INACTIVE: 정상 상태
```

---

## 알람 규칙 작성 가이드

```yaml
- alert: 알람이름            # PascalCase
  expr: PromQL 쿼리          # 조건
  for: 1m                   # 지속 시간 (false positive 방지)
  labels:
    severity: critical       # critical / warning / info
  annotations:
    summary: "한 줄 요약"
    description: "상세 설명 {{ $labels.pod }}"  # 레이블 템플릿 사용 가능
```

### severity 기준
```
critical: 즉시 대응 필요 (서비스 중단, 데이터 손실 가능)
warning:  곧 대응 필요 (리소스 부족, 성능 저하)
info:     참고 (일반 이벤트)
```

---

## 알람 피로(Alert Fatigue) 방지

```
좋은 알람:
→ 실제 사용자 영향이 있는 경우만
→ 명확한 대응 방법이 있는 경우만
→ 너무 자주 울리지 않는 경우

나쁜 알람:
→ 항상 울리는 알람 (무시하게 됨)
→ 대응 방법을 모르는 알람
→ 실제 영향 없는 알람
```