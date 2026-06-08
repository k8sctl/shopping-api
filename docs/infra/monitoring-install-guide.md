# Prometheus + Grafana 설치 가이드

## 개요

kube-prometheus-stack Helm Chart를 사용하여 Prometheus + Grafana 모니터링 스택을 설치합니다.

```
구성 요소:
- Prometheus: 메트릭 수집 및 저장
- Grafana: 메트릭 시각화 (대시보드)
- node-exporter: 노드 메트릭 수집 (CPU, 메모리, 디스크)
- kube-state-metrics: Kubernetes 리소스 메트릭 수집
- Prometheus Operator: Prometheus 관리 자동화
```

---

## 사전 조건

### kube-proxy masqueradeAll 설정 확인

```bash
kubectl get configmap kube-proxy -n kube-system -o yaml | grep -A 3 "iptables:"
```

`masqueradeAll: false`이면 `true`로 변경해야 합니다.

```bash
kubectl edit configmap kube-proxy -n kube-system
# masqueradeAll: false → masqueradeAll: true

kubectl rollout restart daemonset kube-proxy -n kube-system
```

### Pod → ClusterIP 통신 확인

```bash
kubectl run test --image=busybox --restart=Never -n default -- sleep 3600
kubectl exec test -- wget -qO- https://kubernetes.default.svc/api \
  --no-check-certificate --timeout=5 2>&1
# 403 Forbidden이 나오면 정상 (인증 없어서 거부, 접근은 됨)

kubectl delete pod test
```

---

## 설치

### 1. Helm 레포 추가

```bash
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update
```

### 2. monitoring 네임스페이스 생성

```bash
kubectl create namespace monitoring
```

### 3. values.yaml 작성

`k8s/monitoring/values.yaml`:

```yaml
prometheus:
  prometheusSpec:
    retention: 24h  # 단기 보관 (Thanos가 장기 담당 예정)
    resources:
      requests:
        memory: 400Mi
        cpu: 200m
      limits:
        memory: 800Mi
    serviceMonitorSelectorNilUsesHelmValues: false

  service:
    type: NodePort
    nodePort: 30090

grafana:
  adminPassword: admin1234
  service:
    type: NodePort
    nodePort: 30300
  resources:
    requests:
      memory: 200Mi
      cpu: 100m
    limits:
      memory: 400Mi

alertmanager:
  enabled: false

nodeExporter:
  enabled: true

kubeStateMetrics:
  enabled: true
```

### 4. 설치

```bash
helm install prometheus-stack prometheus-community/kube-prometheus-stack \
  --namespace monitoring \
  --values k8s/monitoring/values.yaml
```

### 5. 설치 확인

```bash
kubectl get pods -n monitoring
```

정상 상태:
```
prometheus-prometheus-stack-kube-prom-prometheus-0   2/2  Running
prometheus-stack-grafana-xxx                         3/3  Running
prometheus-stack-kube-prom-operator-xxx              1/1  Running
prometheus-stack-kube-state-metrics-xxx              1/1  Running
prometheus-stack-prometheus-node-exporter-xxx (×3)   1/1  Running
```

---

## 접속

| 서비스 | URL | 계정 |
|--------|-----|------|
| Grafana | http://192.168.64.10:30300 | admin / admin1234 |
| Prometheus | http://192.168.64.10:30090 | - |

---

## Grafana 대시보드

kube-prometheus-stack 설치 시 기본 대시보드가 자동으로 포함됩니다.

주요 대시보드:
```
Kubernetes / Compute Resources / Cluster     → 클러스터 전체 리소스
Kubernetes / Compute Resources / Namespace   → 네임스페이스별 리소스
Kubernetes / Compute Resources / Pod         → Pod별 리소스
Kubernetes / Networking / Cluster            → 네트워크 트래픽
Kubernetes / API server                      → API Server 메트릭
node-exporter / Full                         → 노드 상세 메트릭
```

---

## Spring Boot 메트릭 수집

Spring Actuator Prometheus 엔드포인트를 Prometheus가 수집하려면 ServiceMonitor를 추가해야 합니다.

### application.yml 설정 확인

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  metrics:
    export:
      prometheus:
        enabled: true
```

### ServiceMonitor 추가

`k8s/app/servicemonitor.yaml`:

```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: shopping-app
  namespace: monitoring
  labels:
    release: prometheus-stack
spec:
  selector:
    matchLabels:
      app: shopping-app
  namespaceSelector:
    matchNames:
      - shopping-api
  endpoints:
    - port: http
      path: /actuator/prometheus
      interval: 15s
```

```bash
kubectl apply -f k8s/app/servicemonitor.yaml
```

---

## 업그레이드

values.yaml 변경 후:

```bash
helm upgrade prometheus-stack prometheus-community/kube-prometheus-stack \
  --namespace monitoring \
  --values k8s/monitoring/values.yaml
```

---

## 삭제

```bash
helm uninstall prometheus-stack -n monitoring
kubectl delete namespace monitoring
```

---

## 향후 계획

```
Phase 2: Thanos 추가
→ MinIO로 로컬 오브젝트 스토리지 구축
→ Thanos Sidecar로 Prometheus 데이터 장기 보관
→ Thanos Query로 통합 조회
→ 데이터 보관 기간: 무제한
```