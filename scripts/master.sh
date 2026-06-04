#!/bin/bash
set -e

# 공통 설정 실행
source ./common.sh

echo "=== kubectl 설치 ==="
dnf install -y kubectl

echo "=== kubeconfig 설정 ==="
mkdir -p $HOME/.kube
cp -i /etc/kubernetes/admin.conf $HOME/.kube/config
chown $(id -u):$(id -g) $HOME/.kube/config

echo "=== Calico CNI 설치 ==="
kubectl apply -f https://raw.githubusercontent.com/projectcalico/calico/v3.27.0/manifests/calico.yaml

echo "=== Master 노드 설정 완료 ==="
echo ""
echo "Worker 노드 조인 명령어를 확인하세요:"
echo "kubeadm token create --print-join-command"