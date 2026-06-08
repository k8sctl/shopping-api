# 모니터링 설치 트러블슈팅

## 개요

kube-prometheus-stack 설치 과정에서 발생한 네트워크 문제와 해결 과정을 기록합니다.
핵심 원인은 **ipvs → iptables 전환 시 발생한 네트워크 설정 꼬임**이었습니다.

---

## 증상

```
helm install prometheus-stack ...
→ Error: INSTALLATION FAILED: timed out waiting for the condition

kubectl logs admission-create Pod
→ dial tcp 10.96.0.1:443: i/o timeout
```

모든 Pod에서 ClusterIP(10.96.0.1) 접근 불가.

---

## 원인 분석

### 문제 1: kube-ipvs0 인터페이스 잔류

ipvs 모드에서 iptables 모드로 전환 후 `kube-ipvs0` 인터페이스가 남아있었습니다.

```bash
ip addr show kube-ipvs0
# inet 10.96.0.1/32 scope global kube-ipvs0  ← ClusterIP가 노드 IP로 등록됨
# inet 10.96.0.10/32 scope global kube-ipvs0
```

```bash
hostname -i
# 192.168.64.10 10.96.0.1 10.96.0.10 ...  ← ClusterIP가 노드 IP로 인식됨
```

```bash
iptables -t nat -L KUBE-SERVICES
# destination이 10.96.0.1이 아닌 k8s-master(호스트명)으로 매핑됨
```

**영향:** iptables가 ClusterIP 트래픽을 노드 자신으로 라우팅하여 혼란 발생

**해결:** kube-proxy 재시작 시 자동 정리됨
```bash
kubectl rollout restart daemonset kube-proxy -n kube-system
```

---

### 문제 2: Calico Unknown interface DROP (핵심 원인 1)

Worker 노드 Pod에서 Master API Server(ClusterIP)로 패킷을 보낼 때:

```
Pod(192.168.194.84) → 10.96.0.1:443
  → DNAT → 192.168.64.10:6443
  → Master enp0s1 도착 ✅
  → cali-INPUT → cali-wl-to-host → cali-from-wl-dispatch
  → DROP /* Unknown interface */ ❌
```

```bash
iptables -L cali-from-wl-dispatch
# cali-fw-cali1f453649ff8  [goto]  ← Master Pod 인터페이스만 등록됨
# cali-fw-cali964595bec4b  [goto]
# cali-fw-calif60ce818fd1  [goto]
# DROP /* Unknown interface */     ← Worker Pod 인터페이스 없음 → DROP
```

**원인:** Pod IP가 MASQUERADE 되지 않아 출발지가 Pod IP(192.168.194.x) 그대로 Master에 도달.
Master Calico는 이를 로컬 워크로드 트래픽으로 인식하지만 해당 인터페이스가 없어 DROP.

---

### 문제 3: masqueradeAll: false (핵심 원인 2, 근본 원인)

```bash
kubectl get configmap kube-proxy -n kube-system -o yaml | grep -A 3 "iptables:"
# iptables:
#   masqueradeAll: false  ← 이게 근본 원인
```

```
masqueradeAll: false (기본값):
→ 클러스터 내부 트래픽은 MASQUERADE 하지 않음
→ Pod IP 그대로 목적지에 도달
→ Master Calico가 Pod IP 대역을 로컬 워크로드로 인식
→ cali-from-wl-dispatch에서 Unknown interface DROP

masqueradeAll: true:
→ 모든 트래픽 MASQUERADE
→ Pod IP → 노드 IP로 변환 후 Master에 도달
→ 일반 외부 트래픽으로 처리
→ 정상 통신
```

KUBE-MARK-MASQ 규칙 확인:
```bash
iptables -t nat -L KUBE-SVC-NPX46M4PTMTKRN6Y
# KUBE-MARK-MASQ tcp -- !192.168.0.0/16 → 10.96.0.1
#                        ↑ Pod IP가 192.168.x.x 대역이라 MASQUERADE 안 됨
```

---

## 해결 방법

### 최종 해결: masqueradeAll: true 설정

```bash
kubectl edit configmap kube-proxy -n kube-system
# iptables 섹션에서:
# masqueradeAll: false → masqueradeAll: true

kubectl rollout restart daemonset kube-proxy -n kube-system
```

설정 확인:
```bash
kubectl get configmap kube-proxy -n kube-system -o yaml | grep -A 3 "iptables:"
# iptables:
#   masqueradeAll: true  ✅
```

### Calico 재설치 (추가 조치)

ipvs 실험으로 인해 Calico 설정이 꼬인 경우:
```bash
kubectl delete -f https://raw.githubusercontent.com/projectcalico/calico/v3.27.0/manifests/calico.yaml
sleep 30
kubectl apply -f https://raw.githubusercontent.com/projectcalico/calico/v3.27.0/manifests/calico.yaml
kubectl rollout status daemonset calico-node -n kube-system
```

---

## 검증

```bash
# Pod에서 API Server 접근 테스트
kubectl run test --image=busybox --restart=Never -n monitoring -- sleep 3600
kubectl exec test -n monitoring -- wget -qO- https://kubernetes.default.svc/api \
  --no-check-certificate --timeout=5 2>&1
# → wget: server returned error: HTTP/1.1 403 Forbidden ✅ (403 = 접근 성공, 인증 없어서 거부)
```

---

## 원리 이해

### iptables 패킷 흐름

```
[수신 패킷]
  → PREROUTING (nat)
    → KUBE-SERVICES → DNAT (Service IP → Pod IP 변환)
  → FORWARD / INPUT
    → Calico 규칙 적용
  → POSTROUTING (nat)
    → MASQUERADE (출발지 IP 변환)
```

### masqueradeAll 옵션

| 설정 | 동작 | 영향 |
|------|------|------|
| false (기본값) | 클러스터 외부 트래픽만 MASQUERADE | Pod IP 그대로 목적지 도달 가능 |
| true | 모든 트래픽 MASQUERADE | Pod IP → 노드 IP로 항상 변환 |

Calico CNI 사용 시 Pod CIDR과 노드 네트워크가 같은 대역이면 `masqueradeAll: false`로도 동작하지만,
ipvs 모드 전환 등으로 네트워크 설정이 변경된 경우 `masqueradeAll: true`가 안전합니다.

### ipvs 모드 전환 시 주의사항

```
ipvs → iptables 전환 시:
1. kube-ipvs0 인터페이스 자동 삭제 확인
   ip addr show | grep kube-ipvs0

2. strictARP 설정 확인 (ipvs 사용 시 true 필요)
   kubectl get configmap kube-proxy -n kube-system -o yaml | grep strictARP

3. masqueradeAll 설정 확인
   kubectl get configmap kube-proxy -n kube-system -o yaml | grep -A 3 "iptables:"
```