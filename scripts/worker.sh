#!/bin/bash
set -e

# 공통 설정 실행
source ./common.sh

echo "=== Worker 노드 설정 완료 ==="
echo ""
echo "Master 노드에서 아래 명령어로 join 명령어를 확인하세요:"
echo "kubeadm token create --print-join-command"
echo ""
echo "이후 join 명령어를 이 노드에서 실행하세요."