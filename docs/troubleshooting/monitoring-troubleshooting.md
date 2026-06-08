# 모니터링 설치 트러블슈팅

## 개요

kube-prometheus-stack 설치 과정에서 발생한 네트워크 문제와 해결 과정을 기록합니다.
핵심 원인은 **ipvs → iptables 전환 시 발생한 네트워크 설정 꼬임**이었습니다.

---

## 문제 1: kube-ipvs0 인터페이스 잔류

### 증상
```
iptables -t nat -L KUBE-SERVICES
→ destination이 10.96.0.1이 아닌 k8s-master(호스트명)으로 매핑됨
```

### 원인
ipvs 모드에서 iptables 모드로 전환 후 `kube-ipvs0` 인터페이스가 남아있었습니다.

```bash
ip addr show kube-ipvs0
# inet 10.96.0.1/32 scope global kube-ipvs0  ← ClusterIP가 노드 IP로 등록됨

hostname -i
# 192.168.64.10 10.96.0.1 10.96.0.10 ...  ← ClusterIP가 노드 IP로 인식됨
```

### 해결
kube-proxy 재시작 시 자동 정리됨

```bash
kubectl rollout restart daemonset kube-proxy -n kube-system
```

---

## 문제 2: Calico Unknown interface DROP

### 증상
```
helm install prometheus-stack ...
→ Error: INSTALLATION FAILED: timed out waiting for the condition

kubectl logs admission-create Pod
→ dial tcp 10.96.0.1:443: i/o timeout
```

### 원인
Worker 노드 Pod → Master API Server(ClusterIP) 패킷이 DROP됨

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
# DROP /* Unknown interface */     ← Worker Pod 인터페이스 없음 → DROP
```

Pod IP가 MASQUERADE 되지 않아 출발지가 Pod IP 그대로 Master에 도달.
Master Calico가 이를 로컬 워크로드 트래픽으로 인식하지만 해당 인터페이스가 없어 DROP.

---

## 문제 3: masqueradeAll: false (근본 원인)

### 원인
```bash
kubectl get configmap kube-proxy -n kube-system -o yaml | grep -A 3 "iptables:"
# iptables:
#   masqueradeAll: false  ← 근본 원인
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
→ 일반 외부 트래픽으로 처리 → 정상 통신
```

KUBE-MARK-MASQ 규칙 확인:
```bash
iptables -t nat -L KUBE-SVC-NPX46M4PTMTKRN6Y
# KUBE-MARK-MASQ tcp -- !192.168.0.0/16 → 10.96.0.1
#                        ↑ Pod IP가 192.168.x.x 대역이라 MASQUERADE 안 됨
```

### 해결
```bash
kubectl edit configmap kube-proxy -n kube-system
# iptables 섹션에서:
# masqueradeAll: false → masqueradeAll: true

kubectl rollout restart daemonset kube-proxy -n kube-system
```

### 검증
```bash
kubectl run test --image=busybox --restart=Never -n monitoring -- sleep 3600
kubectl exec test -n monitoring -- wget -qO- https://kubernetes.default.svc/api \
  --no-check-certificate --timeout=5 2>&1
# → 403 Forbidden ✅ (접근 성공, 인증 없어서 거부)
```

---

## 문제 4: Calico ipipMode: Always로 인한 Pod → 다른 노드 IP 접근 불가

### 증상
```
Prometheus targets:
- Worker2 node-exporter → UP ✅ (Prometheus Pod와 같은 노드)
- Worker1 node-exporter → DOWN ❌
- Master node-exporter  → DOWN ❌
```

node-exporter Pod는 3대 모두 Running인데 Prometheus가 Worker2만 수집.

### 원인
```bash
# Worker1에서 tcpdump
tcpdump -i any -n "dst 192.168.64.11 and dst port 9100"
# Prometheus Pod(192.168.126.6) → 192.168.64.11:9100 SYN 도착 ✅
# 응답 없음 ❌

# 응답 패킷 라우팅 확인
ssh root@192.168.64.11 "ip route get 192.168.126.6"
# 192.168.126.6 via 192.168.64.12 dev tunl0 src 192.168.194.64
#                                              ↑ tunl0 IP로 응답!
```

```
ipipMode: Always
→ 모든 Pod 간 트래픽을 IPIP 터널로 처리
→ 응답 패킷 출발지가 tunl0 IP(192.168.194.64)로 나감

Prometheus Pod 입장:
"나는 192.168.64.11에 요청했는데 192.168.194.64에서 응답이 왔어?"
→ 응답 무시 → 타임아웃
```

**ipipMode 종류:**

| 모드 | 동작 |
|------|------|
| Always | 모든 트래픽 IPIP 터널 사용 |
| CrossSubnet | 다른 서브넷만 IPIP 터널, 같은 서브넷은 직접 통신 |
| Never | IPIP 터널 사용 안 함 |

우리 클러스터는 모든 노드가 같은 서브넷(192.168.64.0/24)이라 IPIP 터널이 필요 없음.

### 해결
```bash
./calicoctl patch ippool default-ipv4-ippool -p '{"spec":{"ipipMode":"CrossSubnet"}}'
```

매니페스트 (`k8s/calico/ippool.yaml`):
```yaml
apiVersion: projectcalico.org/v3
kind: IPPool
metadata:
  name: default-ipv4-ippool
spec:
  cidr: 192.168.0.0/16
  ipipMode: CrossSubnet
  natOutgoing: true
  nodeSelector: all()
```

### 검증
```bash
kubectl exec test -n monitoring -- wget -qO- http://192.168.64.11:9100/metrics --timeout=5 2>&1 | head -3
# → 정상 응답 ✅
```

---

## 문제 5: node-exporter hostNetwork 미설정

### 증상
Prometheus가 node-exporter를 Pod IP로 scrape 시도 → ipipMode 문제와 동일한 증상

### 해결
`k8s/monitoring/values.yaml`:
```yaml
prometheus-node-exporter:
  hostNetwork: true
  hostPID: true
```

```bash
helm upgrade prometheus-stack prometheus-community/kube-prometheus-stack \
  --namespace monitoring \
  --values values.yaml
```

---

## Calico 재설치 (필요시)

ipvs 실험으로 Calico 설정이 꼬인 경우:
```bash
kubectl delete -f https://raw.githubusercontent.com/projectcalico/calico/v3.27.0/manifests/calico.yaml
sleep 30
kubectl apply -f https://raw.githubusercontent.com/projectcalico/calico/v3.27.0/manifests/calico.yaml
kubectl rollout status daemonset calico-node -n kube-system
```

---

## ipvs 모드 전환 시 주의사항

```
ipvs → iptables 전환 시 체크리스트:

1. kube-ipvs0 인터페이스 정리 확인
   ip addr show | grep kube-ipvs0

2. strictARP 설정 (ipvs 사용 시 true 필요)
   kubectl get configmap kube-proxy -n kube-system -o yaml | grep strictARP

3. masqueradeAll 설정 확인
   kubectl get configmap kube-proxy -n kube-system -o yaml | grep -A 3 "iptables:"

4. Calico ipipMode 확인
   ./calicoctl get ippool default-ipv4-ippool -o yaml | grep ipipMode
```