# Kubernetes 클러스터 구축 트러블슈팅

## 목록

| # | 문제 | 원인 | 상태 |
|---|------|------|------|
| 1 | hostname WARNING | /etc/hosts 미등록 | 해결 |
| 2 | calico-node ImagePullBackOff | Docker Hub 일시적 실패 | 자동 해결 |
| 3 | VM 간 통신 불가 | UTM MAC 주소 중복 버그 | 해결 |

---

## #1 hostname WARNING

### 증상
```
[WARNING Hostname]: hostname "k8s-master" could not be reached
[WARNING Hostname]: hostname "k8s-master": lookup k8s-master on ...: no such host
```

### 원인
`/etc/hosts`에 hostname이 등록되지 않아 DNS 조회 실패

### 해결
```bash
echo "192.168.64.10 k8s-master" >> /etc/hosts
```

Worker 노드도 동일하게 적용:
```bash
# Worker1
echo "192.168.64.11 k8s-worker1" >> /etc/hosts

# Worker2
echo "192.168.64.12 k8s-worker2" >> /etc/hosts
```

### 참고
기능에는 영향 없는 경고이지만 클러스터 내 hostname 기반 통신을 위해 설정하는 것을 권장합니다.

---

## #2 calico-node ImagePullBackOff

### 증상
```
calico-node-kq454   0/1   Init:ImagePullBackOff   0   7m5s
```

```
Warning  Failed  88s  kubelet  Error: ImagePullBackOff
Normal   Pulling  74s (x2 over 6m58s)  kubelet  Pulling image "docker.io/calico/node:v3.27.0"
```

### 원인
Docker Hub Rate Limit 또는 일시적인 네트워크 문제로 이미지 pull 실패

```
Docker Hub 익명 사용자: 6시간에 100회 pull 제한
→ 여러 이미지 동시 pull 시 제한 걸릴 수 있음
```

### 해결
Kubernetes가 자동으로 재시도하여 해결됨

```
ImagePullBackOff 재시도 간격:
1회 실패 → 10초 후 재시도
2회 실패 → 20초 후 재시도
3회 실패 → 40초 후 재시도
...최대 5분
```

### 참고
실무에서는 Private Registry를 사용하거나 이미지를 미리 pull해두는 방식으로 예방합니다.

---

## #3 VM 간 통신 불가 (UTM MAC 주소 중복 버그)

### 증상
```bash
# Worker1에서 Master로 ping
ping 192.168.64.10
From 192.168.64.11 icmp_seq=9 Destination Host Unreachable
100% packet loss
```

### 원인
UTM의 알려진 버그로 VM 생성 시 동일한 MAC 주소가 할당됨

```bash
# Master MAC 주소
ip link show enp0s1 | grep ether
link/ether a6:c6:8a:d7:c9:83

# Worker1 MAC 주소 (동일!)
ip link show enp0s1 | grep ether
link/ether a6:c6:8a:d7:c9:83
```

MAC 주소가 동일하면 네트워크 스위치가 두 VM을 구분하지 못해 패킷이 유실됩니다.

### 해결
UTM에서 각 VM의 MAC 주소를 수동으로 변경

```
1. VM 종료
2. UTM → VM 설정 → 네트워크
3. MAC 주소 옆 Random 버튼 클릭
4. VM 시작
5. 고정 IP 재설정 (필요 시)
```

### 참고
VM을 복사하지 않아도 발생하는 UTM 버그입니다. VM 생성 후 반드시 MAC 주소를 확인하세요.