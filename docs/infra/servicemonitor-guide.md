# ServiceMonitor 가이드

## 개요

ServiceMonitor는 Prometheus Operator가 제공하는 CRD로,
Prometheus가 어떤 서비스의 메트릭을 수집할지 정의해요.

```
ServiceMonitor → Prometheus Operator → Prometheus scrape 설정 자동 생성
```

---

## ServiceMonitor vs 직접 scrape 설정

```
직접 설정 (prometheus.yml):
→ Prometheus 재시작 필요
→ 설정 파일 직접 관리
→ Kubernetes 동적 환경에 부적합

ServiceMonitor:
→ kubectl apply로 동적 적용
→ Prometheus 재시작 불필요
→ Kubernetes 네이티브 방식
```

---

## shopping-app ServiceMonitor

`k8s/app/servicemonitor.yaml`:

```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: shopping-app
  namespace: monitoring
  labels:
    release: prometheus-stack    # ← Prometheus가 이 레이블로 ServiceMonitor 선택
spec:
  selector:
    matchLabels:
      app: shopping-app          # ← 이 레이블을 가진 Service 선택
  namespaceSelector:
    matchNames:
      - shopping-api             # ← 이 네임스페이스에서 찾음
  endpoints:
    - port: http                 # ← Service의 port name
      path: /actuator/prometheus # ← 메트릭 경로
      interval: 15s              # ← 수집 주기
```

---

## 사전 조건

### 1. Service에 port name 추가

```yaml
# k8s/app/service.yaml
spec:
  ports:
    - name: http          # ← ServiceMonitor에서 참조
      port: 8080
      targetPort: 8080
```

### 2. Service에 레이블 추가

```yaml
# k8s/app/service.yaml
metadata:
  labels:
    app: shopping-app     # ← ServiceMonitor selector에서 참조
```

### 3. Spring Boot Prometheus 설정

`application.yml`:
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, info, metrics, prometheus
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true  # ← histogram 활성화 (p95 측정용)
```

`pom.xml`:
```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

---

## 트러블슈팅

### No targets 문제

```
증상: ServiceMonitor 등록됐는데 targets에 안 뜸

원인 1: Service에 레이블 없음
확인: kubectl get service shopping-app -n shopping-api --show-labels
해결: metadata.labels에 app: shopping-app 추가

원인 2: Service에 port name 없음
확인: kubectl get service shopping-app -n shopping-api -o yaml | grep ports
해결: ports에 name: http 추가

원인 3: ServiceMonitor 레이블 불일치
확인: kubectl get servicemonitor shopping-app -n monitoring -o yaml
해결: metadata.labels.release: prometheus-stack 확인
```

### Prometheus가 ServiceMonitor 인식 못하는 경우

```bash
# values.yaml 설정 확인
serviceMonitorSelectorNilUsesHelmValues: false
# → true이면 release: prometheus-stack 레이블 필수
# → false이면 모든 ServiceMonitor 수집
```

---

## 검증

```bash
# ServiceMonitor 등록 확인
kubectl get servicemonitor -n monitoring

# Prometheus 로그 확인
kubectl logs prometheus-prometheus-stack-kube-prom-prometheus-0 \
  -n monitoring -c prometheus | grep -i "shopping"

# Prometheus config에서 확인
# http://192.168.64.10:30090/config 에서 shopping-app 검색

# Prometheus targets 확인
# http://192.168.64.10:30090/targets 에서 shopping-app 확인
```