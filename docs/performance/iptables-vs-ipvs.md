# iptables vs ipvs 비교 분석

## 개요

Kubernetes kube-proxy의 두 가지 모드인 iptables와 ipvs를 비교 분석했습니다.
예상과 다른 결과가 나왔고, 그 과정에서 iptables 성능 저하의 실제 원인을 이해하게 됐습니다.

---

## 원리

### kube-proxy 역할

```
클라이언트 → Service IP:Port → kube-proxy → Pod IP:Port
```

kube-proxy는 Service로 들어오는 트래픽을 실제 Pod로 라우팅합니다.
이 라우팅 규칙을 어떻게 구현하느냐에 따라 iptables/ipvs 모드가 나뉩니다.

### iptables 방식

```
Service 생성 → iptables 규칙 추가
패킷이 오면 → 규칙을 위에서부터 순차 탐색 O(n)

Service 10개   → 규칙 수십 개
Service 1000개 → 규칙 수천 개
→ 서비스가 많을수록 선형적으로 성능 저하
```

패킷이 처리되는 실제 경로:
```
패킷 도착
  → nat PREROUTING
    → KUBE-SERVICES        ← 서비스 수만큼 선형 탐색 발생
      → KUBE-SVC-XXXX
        → KUBE-SEP-XXXX    ← DNAT (Pod IP:Port로 변환)
```

### ipvs 방식

```
Service 생성 → ipvs 해시 테이블 엔트리 추가
패킷이 오면 → 해시로 O(1) 탐색

Service 수에 관계없이 일정한 성능 유지
로드밸런싱 알고리즘 다양 (rr, lc, sh 등)
```

### ipvs도 iptables를 완전히 대체하지 않음

```
ipvs가 담당:
→ 서비스 → Pod 라우팅 (핵심)

iptables가 여전히 담당:
→ MASQUERADE (패킷 출발지 IP 변환)
→ NodePort 처리 일부
→ ipvs가 처리 못하는 예외 케이스
```

→ ipvs 모드에서도 iptables 규칙이 존재하며 오히려 두 시스템 병행으로 규칙 수가 증가합니다.

### filter vs nat 테이블 역할

| 테이블 | 역할 | 규칙 증가 조건 | 병목 여부 |
|--------|------|----------------|-----------|
| filter | 패킷 허용/차단 | 서비스 생성 시 (Pod 없어도 증가) | 거의 없음 |
| **nat** | DNAT (Service IP → Pod IP 변환) | **Pod 있는 서비스 생성 시에만 증가** | **핵심 병목** |

---

## 환경 설정

### 사전 준비 (전체 노드)

```bash
# ipvs 모듈 설치
dnf install -y ipvsadm ipset

# 모듈 로드
modprobe ip_vs
modprobe ip_vs_rr
modprobe ip_vs_wrr
modprobe ip_vs_sh

# 확인
lsmod | grep ip_vs
```

### kube-proxy 모드 전환

```bash
# configmap 수정
kubectl edit configmap kube-proxy -n kube-system
# mode: "" → mode: "ipvs"  (iptables로 돌아올 때는 반대로)

# kube-proxy 재시작
kubectl rollout restart daemonset kube-proxy -n kube-system

# 모드 확인
kubectl logs $(kubectl get pods -n kube-system | grep kube-proxy | head -1 | awk '{print $1}') \
  -n kube-system | grep -E "iptables|ipvs"
```

### 규칙 수 확인 명령어

```bash
# iptables 규칙 수
iptables -L | wc -l        # filter 테이블
iptables -t nat -L | wc -l # nat 테이블

# ipvs 규칙 확인
ipvsadm -L -n

# 실제 서비스의 nat 규칙 확인
iptables -t nat -L | grep <서비스명>
```

---

## 실험 설계 및 과정

### 실험 순서

```
Step 1: iptables + 서비스 5개    → 측정
Step 2: ipvs + 서비스 5개        → 측정
Step 3: iptables + 서비스 1003개 → 측정 (더미 서비스 1000개 추가)
Step 4: ipvs + 서비스 1003개     → 측정
Step 5: Pod 있는 서비스로 재실험 시도 → 실패 (OOM, kubelet 장애)
```

### 측정 방법

**순차 요청 1000번:**
```bash
for i in {1..1000}; do
  curl -s -o /dev/null -w "%{time_total}\n" http://192.168.64.10:32000/actuator/health
done | sort -n | awk '
{
  times[NR] = $1
  sum += $1
}
END {
  count = NR
  printf "count: %d\n", count
  printf "avg:   %.6f\n", sum/count
  printf "min:   %.6f\n", times[1]
  printf "p50:   %.6f\n", times[int(count*0.50)]
  printf "p95:   %.6f\n", times[int(count*0.95)]
  printf "p99:   %.6f\n", times[int(count*0.99)]
  printf "max:   %.6f\n", times[count]
}'
```

**동시 요청 (10개 × 100번):**
```bash
for i in {1..100}; do
  for j in {1..10}; do
    curl -s -o /dev/null -w "%{time_total}\n" http://192.168.64.10:32000/actuator/health &
  done
  wait
done | sort -n | awk '{ ... }'
```

**CPU 모니터링:**
```bash
vmstat 1 120
```

---

## 실험 결과

### iptables 규칙 수 변화

**서비스 5개:**
```
           filter    nat
Master:    283       168
Worker1:   211       168
Worker2:   243       168
```

**더미 서비스 1000개 추가 후 (Pod 없음):**
```
           filter    nat
Master:    1283      168  ← filter만 증가, nat 변화 없음!
Worker1:   1211      168
Worker2:   1243      168
```

### 응답 시간 비교

**순차 1000번:**

| 구분 | avg | p50 | p95 | p99 | max |
|------|-----|-----|-----|-----|-----|
| iptables + 서비스 5개 | 0.002688s | 0.002342s | 0.003934s | 0.006705s | 0.075568s |
| ipvs + 서비스 5개 | 0.002274s | 0.002072s | 0.003378s | 0.004844s | 0.018452s |
| iptables + 서비스 1003개 | 0.001941s | 0.001837s | 0.002832s | 0.003660s | 0.012219s |
| ipvs + 서비스 1003개 | 0.002262s | 0.001910s | 0.003095s | 0.006433s | 0.114629s |

**동시 10개 × 100번:**

| 구분 | avg | p50 | p95 | p99 | max |
|------|-----|-----|-----|-----|-----|
| iptables + 서비스 5개 | 0.010289s | 0.005372s | 0.014665s | 0.049526s | 0.447342s |
| ipvs + 서비스 5개 | 0.005924s | 0.005172s | 0.011748s | 0.025037s | 0.040864s |
| iptables + 서비스 1003개 | 0.004953s | 0.004290s | 0.009333s | 0.019262s | 0.035815s |
| ipvs + 서비스 1003개 | 0.005126s | 0.004440s | 0.009540s | 0.019037s | 0.035682s |

**CPU 사용량 (vmstat):**

| 구분 | 평상시 (id) | 부하시 us | 부하시 sy |
|------|------------|----------|----------|
| iptables + 서비스 5개 | 97~100% | 7~24% | 6~47% |
| ipvs + 서비스 5개 | 97~100% | 5~18% | 8~50% |
| iptables + 서비스 1003개 | 97~99% | 9~21% | 20~50% |
| ipvs + 서비스 1003개 | 97~100% | 8~23% | 14~51% |

---

## 분석

### 예상과 다른 결과

서비스 수를 늘렸는데 iptables가 ipvs보다 빠른 결과가 나왔습니다.

**원인: 더미 서비스에 Pod가 없어 nat 규칙이 추가되지 않음**

```
iptables 성능 저하는 nat 테이블의 DNAT 규칙 수에 비례

Pod 없는 서비스 → nat DNAT 규칙 없음 → 성능 저하 없음
Pod 있는 서비스 → nat DNAT 규칙 추가 → 성능 저하 발생

이번 실험:
더미 서비스 1000개 추가 → nat 테이블 규칙 수 168 (변화 없음)
→ filter 테이블만 1000개 증가
→ 실질적인 병목 없음
```

**실제 서비스(Pod 있음)의 nat 규칙 확인:**
```bash
iptables -t nat -L | grep shopping
# DNAT tcp → 192.168.194.73:8080  (shopping-app Pod 1)
# DNAT tcp → 192.168.126.11:8080  (shopping-app Pod 2)
# DNAT tcp → 192.168.126.10:3306  (mysql Pod)
```

**서비스 5개에서도 ipvs가 동시 요청에서 더 빠른 이유:**
```
동시 요청 avg 비교:
iptables: 0.010289s
ipvs:     0.005924s  (-42%)

→ 소규모에서도 동시 요청 처리 시 ipvs가 유리
→ ipvs의 커널 레벨 로드밸런싱이 더 효율적
```

### 제대로 된 실험을 위한 조건

```
필요 환경:
  - Worker 노드 메모리 16GB 이상 (OOM 방지)
  - fs.inotify 설정 (kubelet 장애 방지)
  - maxPods: 500 이상

실험 구성:
  - nginx Deployment(replicas=1) + Service 1000개 생성
  - nat 테이블 규칙 수 확인 후 응답 시간 측정

예상 결과:
  - nat 규칙 수: 168 → 약 1만~1.5만 개로 증가
  - iptables: 서비스 수 증가에 따라 선형적 성능 저하
  - ipvs: 서비스 수와 무관하게 일정한 성능 유지
```

---

## 트러블슈팅

### 1. Pod 없는 더미 서비스의 한계
```
문제: 서비스만 생성하면 nat 규칙 미생성
      → iptables 성능 저하 재현 불가
해결: Pod 있는 Deployment + Service 필요
```

### 2. VM 메모리 부족 (OOM)
```
문제: nginx Pod 500개 생성 시 OOM 발생
원인: Worker 4GB RAM, nginx 500개 × ~5MB = ~2.5GB + 기존 사용량 초과
로그: SystemOOM encountered, victim process: nginx
```

### 3. inotify 한계로 kubelet 장애
```
문제: kubelet 시작 실패 후 재시작 반복
로그: inotify_init: too many open files
      Failed to start cAdvisor

해결:
sysctl -w fs.inotify.max_user_instances=8192
sysctl -w fs.inotify.max_user_watches=524288
echo "fs.inotify.max_user_instances=8192" >> /etc/sysctl.conf
echo "fs.inotify.max_user_watches=524288" >> /etc/sysctl.conf
sysctl -p
systemctl restart kubelet
```

### 4. maxPods 기본 한계
```
문제: 기본 maxPods=110으로 Pod 스케줄링 불가
로그: 0/3 nodes are available: 2 Too many pods

해결:
echo "maxPods: 500" >> /var/lib/kubelet/config.yaml
systemctl restart kubelet

확인:
kubectl describe node k8s-worker1 | grep pods
```

---

## 결론

### 현재 실험 결과 요약
```
서비스 5개 (소규모):
→ 순차 요청: iptables/ipvs 큰 차이 없음
→ 동시 요청: ipvs가 avg 42% 빠름

서비스 1003개 (더미, Pod 없음):
→ nat 규칙 변화 없어 유의미한 비교 불가
→ 실험 설계 한계 발견
```

### 언제 ipvs를 선택해야 하나
```
소규모 클러스터 (서비스 수십 개)
→ iptables 모드로 충분
→ 기본값 유지 권장

대규모 클러스터 (서비스 수백~수천 개)
→ iptables: O(n) 순차 탐색 → 성능 저하
→ ipvs: O(1) 해시 탐색 → 일정한 성능
→ ipvs 모드 권장
```

### 향후 계획
```
1. 충분한 메모리 환경에서 Pod 있는 서비스 대량 생성 재실험
2. k6 부하 테스트로 실제 트래픽 시나리오 측정
```