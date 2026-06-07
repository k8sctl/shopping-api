# Kubernetes 앱 기동 순서 제어 실험

## 배경

Kubernetes에서 docker-compose의 `depends_on`과 같은 기능이 없어 MySQL보다 앱이 먼저 시작될 경우 DB 연결 실패가 발생합니다. 이를 해결하는 `initContainers` 방식과 그냥 두는 방식을 비교 실험했습니다.

---

## 실험 환경

```
클러스터: Kubernetes v1.29.15
노드: Master 1대, Worker 2대
MySQL: StatefulSet (replicas: 1)
App: Deployment (replicas: 2)
이미지 캐시: 실험 전 Worker 노드에서 완전히 삭제
```

**이미지 캐시 삭제 방법:**
```bash
crictl --runtime-endpoint unix:///run/containerd/containerd.sock rmi mysql:8.0
crictl --runtime-endpoint unix:///run/containerd/containerd.sock rmi k8sctl/shopping-api:1.0.0
```

---

## 실험 1: initContainers 없는 경우

### 배포 설정

```yaml
spec:
  containers:
    - name: shopping-app
      image: k8sctl/shopping-api:1.0.0
      # initContainers 없음 → MySQL 준비 여부 확인 안 함
```

### 실험 로그

```
0s  → MySQL, 앱 동시 배포 시작 (이미지 pull 시작)
17s → 앱 Running (이미지 pull 완료, MySQL 아직 준비 중)
26s → 앱 Error (MySQL 연결 실패)
27s → 앱 재시작 (RESTARTS: 1)
35s → 앱 Error (재시작했지만 MySQL 아직 준비 중)
43s → MySQL Running ✅
47s → CrashLoopBackOff (재시작 간격 늘어남)
48s → 앱 Running ✅ (RESTARTS: 2)
57s → 앱 안정적 Running ✅
```

### 에러 로그

```
Communications link failure
The last packet sent successfully to the server was 0 milliseconds ago.
The driver has not received any packets from the server.
Caused by: java.net.ConnectException: Connection refused
```

---

## 실험 2: initContainers 있는 경우

### 배포 설정

```yaml
spec:
  initContainers:
    - name: wait-for-mysql
      image: busybox
      command: ['sh', '-c', 'until nc -z mysql 3306; do echo waiting for mysql; sleep 2; done']
  containers:
    - name: shopping-app
      image: k8sctl/shopping-api:1.0.0
```

### 실험 로그

```
0s  → MySQL, 앱 동시 배포 시작 (이미지 pull 시작)
0s  → 앱 Init:0/1 (initContainers 대기 중)
19s → MySQL Running ✅
21s → PodInitializing (MySQL 준비 확인 완료, 앱 시작)
40s → 앱 Running ✅ (RESTARTS: 0)
44s → 앱 Running ✅ (RESTARTS: 0)
```

---

## 비교 결과

| 항목 | initContainers 없음 | initContainers 있음 |
|------|-------------------|-------------------|
| MySQL 준비 시간 | 43s | 19s |
| 앱 Running까지 | 57s | 40~44s |
| 재시작 횟수 | 2회 | 0회 |
| 에러 로그 | 대량 발생 | 없음 |
| 상태 변화 | CrashLoopBackOff | 조용히 대기 |

---

## MySQL 기동 시간 차이 분석

initContainers 있을 때 MySQL이 더 빠르게 뜨는 이유:

```
initContainers 없는 경우:
→ mysql(500MB) + shopping-api(572MB) 동시 pull
→ Worker 노드 네트워크 대역폭 경쟁
→ MySQL 기동: 43s

initContainers 있는 경우:
→ mysql(500MB) + busybox(1MB) 동시 pull
→ MySQL이 거의 단독으로 대역폭 사용
→ MySQL 기동: 19s
```

busybox 이미지가 1MB로 매우 작아 MySQL 이미지 pull에 대역폭이 집중됩니다.

---

## 결론

**운영 환경에서는 initContainers 필수**

```
CrashLoopBackOff 방식:
- 재시작 간격이 점점 늘어남 (10s → 20s → 40s → 최대 5분)
- 불필요한 에러 로그 대량 발생
- 모니터링 알람 발생 가능
- 서비스 불안정 시간 발생

initContainers 방식:
- MySQL 준비될 때까지 조용히 대기
- 에러 로그 없음
- 재시작 없이 깔끔한 시작
- 운영 환경에 적합
```

**개발 환경에서는 선택 사항**

CrashLoopBackOff로 자동 복구되므로 기능상 문제는 없지만, 불필요한 에러 로그가 쌓이는 단점이 있습니다.