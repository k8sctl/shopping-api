# Thanos 설치 가이드

## 개요

Prometheus의 데이터를 장기 보관하기 위해 Thanos를 설치합니다.
MinIO를 오브젝트 스토리지로 사용하여 온프레미스 환경에서 구성합니다.

---

## 아키텍처

```
Prometheus → Thanos Sidecar → MinIO (2시간마다 블록 업로드)
                                    ↓
                              Thanos Store (MinIO 데이터 조회)
                                    ↓
Prometheus ←→ Thanos Query ←→ Thanos Store
                   ↓
                Grafana (Thanos 데이터소스)
```

### 구성 요소

| 컴포넌트 | 역할 |
|----------|------|
| Thanos Sidecar | Prometheus 옆에서 실행, 데이터를 MinIO에 업로드 |
| Thanos Store | MinIO의 데이터를 PromQL로 조회 가능하게 함 |
| Thanos Query | Prometheus + Thanos Store 통합 조회 |
| MinIO | S3 호환 오브젝트 스토리지 (로컬) |

---

## 사전 조건

- Prometheus + Grafana (kube-prometheus-stack) 설치 완료
- Kubernetes 클러스터 정상 동작

---

## 1. MinIO 설치

### PV/PVC + Deployment + Service

`k8s/minio/minio.yaml`:

```yaml
apiVersion: v1
kind: PersistentVolume
metadata:
  name: minio-pv
spec:
  capacity:
    storage: 10Gi
  accessModes:
    - ReadWriteOnce
  hostPath:
    path: /data/minio
  nodeAffinity:
    required:
      nodeSelectorTerms:
        - matchExpressions:
            - key: kubernetes.io/hostname
              operator: In
              values:
                - k8s-master
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: minio-pvc
  namespace: minio
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 10Gi
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: minio
  namespace: minio
spec:
  replicas: 1
  selector:
    matchLabels:
      app: minio
  template:
    metadata:
      labels:
        app: minio
    spec:
      nodeSelector:
        kubernetes.io/hostname: k8s-master
      tolerations:
        - key: node-role.kubernetes.io/control-plane
          operator: Exists
          effect: NoSchedule
      containers:
        - name: minio
          image: quay.io/minio/minio:latest
          args:
            - server
            - /data
            - --console-address
            - ":9001"
          env:
            - name: MINIO_ROOT_USER
              value: minioadmin
            - name: MINIO_ROOT_PASSWORD
              value: minioadmin123
          ports:
            - containerPort: 9000
            - containerPort: 9001
          volumeMounts:
            - name: minio-storage
              mountPath: /data
      volumes:
        - name: minio-storage
          persistentVolumeClaim:
            claimName: minio-pvc
---
apiVersion: v1
kind: Service
metadata:
  name: minio
  namespace: minio
spec:
  type: NodePort
  selector:
    app: minio
  ports:
    - name: api
      port: 9000
      targetPort: 9000
      nodePort: 30900
    - name: console
      port: 9001
      targetPort: 9001
      nodePort: 30901
```

```bash
kubectl create namespace minio
kubectl apply -f k8s/minio/minio.yaml
```

### MinIO 버킷 생성

```
http://192.168.64.10:30901
ID: minioadmin
PW: minioadmin123
→ Create Bucket: thanos
```

---

## 2. Thanos Secret 생성

`k8s/thanos/secret.yaml`:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: thanos-objstore-secret
  namespace: monitoring
stringData:
  objstore.yml: |
    type: S3
    config:
      bucket: thanos
      endpoint: minio.minio.svc:9000
      access_key: minioadmin
      secret_key: minioadmin123
      insecure: true
```

```bash
kubectl apply -f k8s/thanos/secret.yaml
```

---

## 3. Thanos Sidecar 설정 (values.yaml 수정)

`k8s/monitoring/values.yaml`에 thanos 설정 추가:

```yaml
prometheus:
  prometheusSpec:
    retention: 24h
    thanos:
      image: quay.io/thanos/thanos:v0.34.0
      objectStorageConfig:
        existingSecret:           # ← existingSecret 사용 (중요!)
          name: thanos-objstore-secret
          key: objstore.yml
```

```bash
helm upgrade prometheus-stack prometheus-community/kube-prometheus-stack \
  --namespace monitoring \
  --values k8s/monitoring/values.yaml
```

---

## 4. Thanos Store 설치

`k8s/thanos/store.yaml`:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: thanos-store
  namespace: monitoring
spec:
  replicas: 1
  selector:
    matchLabels:
      app: thanos-store
  template:
    metadata:
      labels:
        app: thanos-store
    spec:
      containers:
        - name: thanos-store
          image: quay.io/thanos/thanos:v0.34.0
          args:
            - store
            - --objstore.config-file=/etc/thanos/objstore.yml
            - --data-dir=/var/thanos/store
          ports:
            - containerPort: 10901
              name: grpc
            - containerPort: 10902
              name: http
          volumeMounts:
            - name: objstore-config
              mountPath: /etc/thanos
            - name: data
              mountPath: /var/thanos/store
      volumes:
        - name: objstore-config
          secret:
            secretName: thanos-objstore-secret
        - name: data
          emptyDir: {}
---
apiVersion: v1
kind: Service
metadata:
  name: thanos-store
  namespace: monitoring
spec:
  selector:
    app: thanos-store
  ports:
    - name: grpc
      port: 10901
      targetPort: 10901
    - name: http
      port: 10902
      targetPort: 10902
```

---

## 5. Thanos Query 설치

`k8s/thanos/query.yaml`:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: thanos-query
  namespace: monitoring
spec:
  replicas: 1
  selector:
    matchLabels:
      app: thanos-query
  template:
    metadata:
      labels:
        app: thanos-query
    spec:
      containers:
        - name: thanos-query
          image: quay.io/thanos/thanos:v0.34.0
          args:
            - query
            - --http-address=0.0.0.0:10902
            - --grpc-address=0.0.0.0:10901
            - --endpoint=dnssrv+_grpc._tcp.prometheus-operated.monitoring.svc.cluster.local
            - --endpoint=thanos-store.monitoring.svc:10901
            - --query.replica-label=prometheus_replica
          ports:
            - containerPort: 10902
              name: http
            - containerPort: 10901
              name: grpc
---
apiVersion: v1
kind: Service
metadata:
  name: thanos-query
  namespace: monitoring
spec:
  type: NodePort
  selector:
    app: thanos-query
  ports:
    - name: http
      port: 10902
      targetPort: 10902
      nodePort: 30902
```

```bash
kubectl apply -f k8s/thanos/store.yaml
kubectl apply -f k8s/thanos/query.yaml
```

---

## 6. Grafana 데이터소스 추가

```
Grafana → Connections → Data sources → Add new data source
→ Prometheus 선택
→ Name: Thanos
→ URL: http://thanos-query.monitoring.svc:10902
→ Save & Test
```

---

## 접속 정보

| 서비스 | URL |
|--------|-----|
| MinIO Console | http://192.168.64.10:30901 |
| Thanos Query UI | http://192.168.64.10:30902 |
| Grafana | http://192.168.64.10:30300 |

---

## 데이터 업로드 확인

Thanos Sidecar는 Prometheus가 2시간마다 생성하는 블록을 MinIO에 업로드해요.

```bash
# Sidecar 로그 확인
kubectl logs prometheus-prometheus-stack-kube-prom-prometheus-0 \
  -n monitoring -c thanos-sidecar | tail -20
```

정상 로그:
```
loading bucket configuration ✅
successfully loaded prometheus version ✅
successfully loaded prometheus external labels ✅
```

MinIO 버킷에 데이터 업로드 후:
```
level=info msg="uploaded block" block=ULID
```

---

## Prometheus vs Thanos Query 차이

| 항목 | Prometheus | Thanos Query |
|------|------------|--------------|
| 데이터 범위 | 최근 24h (retention 설정) | 전체 기간 (MinIO 포함) |
| 용도 | 실시간 모니터링 | 장기 데이터 분석 |
| Grafana 데이터소스 | default | Thanos |