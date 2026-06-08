# Kubernetes 네트워크 개념 정리

## 1. Pod IP vs ClusterIP

### Pod IP

```
Pod가 생성될 때 Calico(CNI)가 자동으로 부여하는 IP

특징:
→ Pod마다 고유한 IP
→ Pod 재시작하면 IP가 바뀜
→ 클러스터 내부에서만 사용 가능
→ 외부에서 직접 접근 불가
```

우리 클러스터:
```
Pod CIDR: 192.168.0.0/16
→ shopping-app Pod: 192.168.194.73, 192.168.126.11
→ mysql Pod:        192.168.126.10
```

### ClusterIP (Service IP)

```
Service를 만들 때 kube-proxy가 부여하는 가상 IP

특징:
→ Pod IP와 달리 절대 변하지 않음
→ 실제로 어떤 인터페이스에도 바인딩 안 됨 (가상 IP)
→ iptables/ipvs 규칙으로만 존재
→ 클러스터 내부에서만 사용 가능
```

우리 클러스터:
```
Service CIDR: 10.96.0.0/12
→ kubernetes Service:   10.96.0.1
→ kube-dns Service:     10.96.0.10
→ shopping-app Service: 10.109.150.52
→ mysql Service:        10.109.171.218
```

### 왜 ClusterIP가 필요하냐면

```
Pod IP만 있으면:
앱 → mysql Pod (192.168.126.10) 접근
→ mysql Pod 재시작
→ IP가 192.168.194.90으로 바뀜
→ 앱이 연결 못 함 ❌

ClusterIP 있으면:
앱 → mysql Service (10.109.171.218) 접근
→ mysql Pod 재시작해도 Service IP는 그대로
→ kube-proxy가 새 Pod IP로 자동 라우팅
→ 항상 연결 가능 ✅
```

---

## 2. kube-proxy가 Service를 구현하는 방식

### iptables 모드

Service 생성 시 kube-proxy가 iptables 규칙을 자동 생성합니다.

```
규칙 구조:
KUBE-SERVICES
  └── KUBE-SVC-XXXX (서비스별)
        ├── KUBE-MARK-MASQ (외부 트래픽 MASQUERADE 표시)
        └── KUBE-SEP-XXXX  (엔드포인트별 DNAT)
```

패킷 처리:
```
10.96.0.1:443 도착
→ KUBE-SERVICES에서 매칭
→ KUBE-SVC-NPX46M4PTMTKRN6Y
→ KUBE-SEP-XXXX
→ DNAT: 10.96.0.1:443 → 192.168.64.10:6443
```

실제 확인:
```bash
iptables -t nat -L KUBE-SERVICES | grep kubernetes
iptables -t nat -L KUBE-SVC-NPX46M4PTMTKRN6Y
iptables -t nat -L KUBE-SEP-VVBZLDDCGYIIOLML
```

### ipvs 모드

```
Service 생성 시 ipvs 해시 테이블 엔트리 추가

ipvsadm -L -n
TCP 10.96.0.1:443 rr
  → 192.168.64.10:6443 (Masq)

패킷 처리:
10.96.0.1:443 도착
→ ipvs 해시 테이블 O(1) 탐색
→ 192.168.64.10:6443으로 포워딩
```

| 항목 | iptables | ipvs |
|------|----------|------|
| 탐색 방식 | 순차 O(n) | 해시 O(1) |
| 서비스 많을 때 | 성능 저하 | 일정한 성능 |
| 로드밸런싱 알고리즘 | random | rr, lc, sh 등 다양 |
| iptables 완전 대체 | - | 아님 (일부 여전히 사용) |

---

## 3. iptables 테이블/체인 동작 원리

### 테이블 종류

```
filter 테이블: 패킷 허용/차단 (방화벽)
nat 테이블:    주소 변환 (DNAT, SNAT, MASQUERADE)
mangle 테이블: 패킷 헤더 수정 (QoS, MARK 등)
```

### 체인과 패킷 흐름

```
[수신 패킷]
      ↓
PREROUTING (nat)     ← DNAT 여기서 발생 (목적지 IP 변환)
      ↓
   목적지가 로컬?
   ↙          ↘
INPUT        FORWARD
(로컬 프로세스)  (다른 호스트로 전달)
      ↓
POSTROUTING (nat)    ← MASQUERADE/SNAT 여기서 발생 (출발지 IP 변환)
      ↓
[송신 패킷]
```

### Kubernetes에서 iptables 체인

```
nat PREROUTING
  → KUBE-SERVICES          ← 서비스 목록 (순차 탐색)
    → KUBE-SVC-XXXX        ← 특정 서비스
      → KUBE-SEP-XXXX      ← 특정 Pod (DNAT)

nat POSTROUTING
  → KUBE-POSTROUTING
    → MASQUERADE           ← MARK된 패킷 출발지 IP 변환
```

---

## 4. MASQUERADE vs SNAT

```
SNAT (Static NAT):
→ 출발지 IP를 특정 IP로 고정
→ 예: -j SNAT --to-source 192.168.64.11
→ 나갈 IP를 미리 알아야 함

MASQUERADE:
→ 출발지 IP를 인터페이스의 현재 IP로 자동 변환
→ 예: -j MASQUERADE
→ IP가 동적으로 바뀌어도 자동 대응
→ Kubernetes처럼 노드 IP가 변할 수 있는 환경에 적합
```

### masqueradeAll 옵션

| 설정 | 동작 | 영향 |
|------|------|------|
| false (기본값) | 클러스터 외부 트래픽만 MASQUERADE | Pod IP 그대로 목적지 도달 |
| true | 모든 트래픽 MASQUERADE | Pod IP → 노드 IP로 항상 변환 |

**왜 false가 기본값이냐면:**
```
클러스터 내부 통신은 Pod IP 그대로 사용해도 되기 때문
→ 같은 노드 내 Pod끼리는 직접 통신 가능
→ BGP 라우팅으로 다른 노드 Pod IP도 접근 가능

근데 이번처럼 네트워크 설정이 꼬이면 문제가 됨
→ masqueradeAll: true가 더 안전한 선택
```

---

## 5. Calico가 iptables 규칙을 관리하는 방식

Calico는 네트워크 정책(NetworkPolicy)을 iptables 규칙으로 구현합니다.

### Calico가 만드는 주요 체인

```
cali-INPUT:
→ 노드로 들어오는 패킷 처리
→ IPIP 패킷 허용/차단
→ 워크로드(Pod)에서 온 패킷 → cali-wl-to-host

cali-FORWARD:
→ 포워딩되는 패킷 처리
→ Pod 간 트래픽

cali-from-wl-dispatch:
→ Pod 인터페이스별로 패킷을 분배
→ cali-fw-caliXXXXX: 각 Pod 인터페이스별 정책
→ 해당 인터페이스 없으면 DROP (Unknown interface)

cali-to-wl-dispatch:
→ 특정 Pod 인터페이스로 가는 패킷 분배
```

### Calico 인터페이스 명명 규칙

```
caliXXXXXXXXXX: Pod에 연결된 가상 인터페이스
→ 각 Pod마다 하나씩 생성
→ 해당 노드에만 존재

Master 노드의 인터페이스:
cali1f453649ff8  ← Master에 있는 Pod용
cali964595bec4b
calif60ce818fd1

Worker1 노드의 인터페이스:
calie4073166d78  ← Worker1에 있는 Pod용
```

---

## 6. 이번 트러블슈팅 전체 네트워크 흐름

### 문제 상황

```
[test Pod - Worker1]
IP: 192.168.194.84
        ↓ 패킷 생성
출발지: 192.168.194.84
목적지: 10.96.0.1:443 (kubernetes Service)
        ↓ Worker1 iptables PREROUTING DNAT
출발지: 192.168.194.84 (변환 안 됨)
목적지: 192.168.64.10:6443 (kube-apiserver)
        ↓ Worker1 → Master 전송
        ↓ Master enp0s1 수신
        ↓ Master cali-INPUT
        ↓ cali-wl-to-host (출발지가 Pod IP 대역이라 여기로)
        ↓ cali-from-wl-dispatch
"192.168.194.84? Pod IP 대역인데 내 노드에 이 인터페이스 없어!"
        ↓
DROP ❌
```

### 해결 후 흐름

```
[test Pod - Worker1]
IP: 192.168.194.84
        ↓ 패킷 생성
출발지: 192.168.194.84
목적지: 10.96.0.1:443
        ↓ Worker1 iptables PREROUTING DNAT
출발지: 192.168.194.84
목적지: 192.168.64.10:6443
        ↓ Worker1 iptables POSTROUTING MASQUERADE
출발지: 192.168.64.11 (Worker1 노드 IP로 변환!) ←
목적지: 192.168.64.10:6443
        ↓ Master enp0s1 수신
        ↓ Master cali-INPUT
"출발지가 192.168.64.11? Worker1 노드 IP잖아"
"일반 노드 간 통신이네"
        ↓
ACCEPT ✅ → kube-apiserver 응답
```

### 해결 방법

```bash
kubectl edit configmap kube-proxy -n kube-system
# iptables 섹션:
# masqueradeAll: false → masqueradeAll: true

kubectl rollout restart daemonset kube-proxy -n kube-system
```

---

## 7. 핵심 정리

```
Pod IP     = Pod 자신의 IP, 재시작하면 바뀜
ClusterIP  = Service의 가상 IP, 절대 안 바뀜
DNAT       = 목적지 IP 변환 (Service IP → Pod IP)
MASQUERADE = 출발지 IP 변환 (Pod IP → 노드 IP)

패킷 흐름:
Pod → [DNAT] → 목적지 변환 → 전송 → [MASQUERADE] → 출발지 변환
```