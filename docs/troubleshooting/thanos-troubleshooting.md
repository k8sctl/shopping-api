# Thanos 트러블슈팅

## 문제 1: objectStorageConfig 형식 오류

### 증상
```
level=error err="yaml: unmarshal errors:
  line 1: field key not found in type client.BucketConfig
  line 2: field name not found in type client.BucketConfig"
```

### 원인
`objectStorageConfig` 설정 형식이 잘못됨

```yaml
# 잘못된 형식
thanos:
  objectStorageConfig:
    secret:          ← 이 중첩이 문제
      name: thanos-objstore-secret
      key: objstore.yml

# 잘못된 형식 2
thanos:
  objectStorageConfig:
    name: thanos-objstore-secret   ← kube-prometheus-stack에서 인식 안 됨
    key: objstore.yml
```

### 해결
```yaml
# 올바른 형식 (existingSecret 사용)
thanos:
  objectStorageConfig:
    existingSecret:
      name: thanos-objstore-secret
      key: objstore.yml
```

---

## 문제 2: objstore.yml 필드명 오류

### 증상
```
yaml: unmarshal errors:
  line N: field access_key_id not found
  line N: field secret_access_key not found
```

### 원인
Thanos S3 설정의 올바른 필드명은 `access_key`, `secret_key`예요.
AWS SDK 스타일의 `access_key_id`, `secret_access_key`는 다른 필드예요.

### 해결
```yaml
type: S3
config:
  bucket: thanos
  endpoint: minio.minio.svc:9000
  access_key: minioadmin      # ← access_key
  secret_key: minioadmin123   # ← secret_key
  insecure: true
```

---

## 문제 3: no supported bucket was configured

### 증상
```
level=info msg="no supported bucket was configured, uploads will be disabled"
```

### 원인
`objectStorageConfig`가 Prometheus CRD에 적용이 안 됨

### 확인
```bash
kubectl get prometheus prometheus-stack-kube-prom-prometheus \
  -n monitoring -o yaml | grep -A 10 thanos
# objectStorageConfig 항목이 없으면 적용 안 된 것
```

### 해결
values.yaml의 들여쓰기 확인

```yaml
prometheus:
  prometheusSpec:          # ← 여기 안에
    thanos:                # ← thanos가 있어야 함
      objectStorageConfig:
        existingSecret:
          name: thanos-objstore-secret
          key: objstore.yml
```

---

## 문제 4: Master 노드 taint로 MinIO Pod Pending

### 증상
```
0/3 nodes are available: 1 node(s) had untolerated taint
{node-role.kubernetes.io/control-plane: }
```

### 해결
Master 노드에 배포할 때 toleration 추가

```yaml
spec:
  nodeSelector:
    kubernetes.io/hostname: k8s-master
  tolerations:
    - key: node-role.kubernetes.io/control-plane
      operator: Exists
      effect: NoSchedule
```