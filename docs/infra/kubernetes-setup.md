# Kubernetes 클러스터 구축

## 환경

| 항목 | 내용 |
|------|------|
| 하이퍼바이저 | UTM (Apple Silicon) |
| OS | Rocky Linux 9.2 Minimal |
| Kubernetes | v1.29.15 |
| CNI | Calico v3.27.0 |
| CRI | containerd |

## 클러스터 구성

| 노드 | IP | 역할 | 스펙 |
|------|-----|------|------|
| k8s-master | 192.168.64.10 | Control Plane | CPU 4코어, RAM 4GB |
| k8s-worker1 | 192.168.64.11 | Worker | CPU 4코어, RAM 4GB |
| k8s-worker2 | 192.168.64.12 | Worker | CPU 4코어, RAM 4GB |

네트워크: UTM Shared Network (NAT) `192.168.64.0/24`

---

## 사전 준비 (전체 노드 공통)

> `scripts/common.sh` 참고

### 1. SELinux 비활성화

```bash
setenforce 0
sed -i 's/^SELINUX=enforcing$/SELINUX=permissive/' /etc/selinux/config
```

Kubernetes와 SELinux 충돌 방지를 위해 permissive 모드로 변경합니다.

### 2. 스왑 비활성화

```bash
swapoff -a
sed -i '/ swap / s/^\(.*\)$/#\1/g' /etc/fstab
```

kubelet은 스왑이 활성화된 상태에서 시작을 거부합니다. Kubernetes는 메모리 관리를 직접 수행하기 때문에 스왑이 활성화되면 성능 예측이 불가능해집니다.

### 3. 방화벽 비활성화

```bash
systemctl disable --now firewalld
```

Kubernetes가 자체적으로 iptables 규칙을 관리하므로 firewalld와 충돌을 방지합니다.

### 4. 커널 모듈 설정

```bash
cat <<EOF | tee /etc/modules-load.d/k8s.conf
overlay
br_netfilter
EOF

modprobe overlay
modprobe br_netfilter
```

| 모듈 | 용도 |
|------|------|
| overlay | 컨테이너 레이어 파일시스템 |
| br_netfilter | 브리지 네트워크 트래픽 iptables 필터링 |

### 5. 네트워크 파라미터 설정

```bash
cat <<EOF | tee /etc/sysctl.d/k8s.conf
net.bridge.bridge-nf-call-iptables  = 1
net.bridge.bridge-nf-call-ip6tables = 1
net.ipv4.ip_forward                 = 1
EOF

sysctl --system
```

| 파라미터 | 용도 |
|----------|------|
| bridge-nf-call-iptables | 브리지 트래픽에 iptables 규칙 적용 (네트워크 폴리시) |
| ip_forward | 노드 간 패킷 전달 활성화 |

### 6. containerd 설치

```bash
dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo
dnf install -y containerd.io

mkdir -p /etc/containerd
containerd config default | tee /etc/containerd/config.toml
sed -i 's/SystemdCgroup = false/SystemdCgroup = true/' /etc/containerd/config.toml
systemctl enable --now containerd
```

Kubernetes 1.24부터 Docker 직접 지원이 중단되어 containerd를 CRI로 사용합니다. `SystemdCgroup = true` 설정은 systemd cgroup 드라이버를 사용하기 위해 필요합니다.

### 7. Kubernetes 컴포넌트 설치

```bash
cat <<EOF | tee /etc/yum.repos.d/kubernetes.repo
[kubernetes]
name=Kubernetes
baseurl=https://pkgs.k8s.io/core:/stable:/v1.29/rpm/
enabled=1
gpgcheck=1
gpgkey=https://pkgs.k8s.io/core:/stable:/v1.29/rpm/repodata/repomd.xml.key
EOF
```

| 노드 | 설치 패키지 |
|------|------------|
| Master | kubelet, kubeadm, kubectl |
| Worker | kubelet, kubeadm |

```bash
# Master
dnf install -y kubelet kubeadm kubectl

# Worker
dnf install -y kubelet kubeadm

systemctl enable --now kubelet
```

---

## Master 노드 초기화

> `scripts/master.sh` 참고

### 1. kubeadm init

```bash
kubeadm init \
  --apiserver-advertise-address=192.168.64.10 \
  --pod-network-cidr=192.168.0.0/16
```

`pod-network-cidr`은 Calico 기본값인 `192.168.0.0/16`으로 설정합니다.

### 2. kubeconfig 설정

```bash
mkdir -p $HOME/.kube
cp -i /etc/kubernetes/admin.conf $HOME/.kube/config
chown $(id -u):$(id -g) $HOME/.kube/config
```

### 3. Calico CNI 설치

```bash
kubectl apply -f https://raw.githubusercontent.com/projectcalico/calico/v3.27.0/manifests/calico.yaml
```

CNI 설치 전 CoreDNS Pod는 Pending 상태입니다. Calico는 DaemonSet으로 배포되어 모든 노드에 자동으로 설치됩니다.

---

## Worker 노드 조인

> `scripts/worker.sh` 참고

Master에서 join 명령어 확인:

```bash
kubeadm token create --print-join-command
```

Worker 노드에서 실행:

```bash
kubeadm join 192.168.64.10:6443 --token <token> \
        --discovery-token-ca-cert-hash sha256:<hash>
```

---

## 구축 완료 확인

```bash
kubectl get nodes
```

```
NAME          STATUS   ROLES           AGE   VERSION
k8s-master    Ready    control-plane   30m   v1.29.15
k8s-worker1   Ready    <none>          4m    v1.29.15
k8s-worker2   Ready    <none>          4m    v1.29.15
```

```bash
kubectl get pods -n kube-system
```

```
NAME                                       READY   STATUS    RESTARTS   AGE
calico-kube-controllers-5fc7d6cf67-599k8   1/1     Running   0          9m
calico-node-kq454                          1/1     Running   0          9m
calico-node-xxxxx                          1/1     Running   0          4m
calico-node-yyyyy                          1/1     Running   0          4m
coredns-76f75df574-5jgxg                   1/1     Running   0          12m
coredns-76f75df574-w8b25                   1/1     Running   0          12m
etcd-k8s-master                            1/1     Running   0          12m
kube-apiserver-k8s-master                  1/1     Running   0          12m
kube-controller-manager-k8s-master         1/1     Running   0          12m
kube-proxy-xknvh                           1/1     Running   0          12m
kube-scheduler-k8s-master                  1/1     Running   0          12m
```