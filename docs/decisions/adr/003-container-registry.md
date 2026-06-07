# ADR-003: 컨테이너 레지스트리 선택

## 상태
결정됨 (향후 재검토 예정)

## 배경
Kubernetes 클러스터에서 이미지를 pull하려면 이미지를 저장할 레지스트리가 필요합니다. 어떤 레지스트리를 사용할지 결정해야 했습니다.

## 고려한 옵션

**1. Docker Hub (Public)**
- 장점: 무료, 설정 간단, imagePullSecret 불필요
- 단점: 이미지 공개, Rate Limit (6시간 100회)

**2. Docker Hub (Private)**
- 장점: 이미지 비공개
- 단점: 무료 1개 제한, Kubernetes imagePullSecret 설정 필요

**3. AWS ECR**
- 장점: 실무와 유사, 보안 우수
- 단점: AWS 계정 필요, 비용 발생

**4. Harbor (Self-hosted)**
- 장점: 완전한 제어, 이미지 스캔, 접근 제어, 실무 온프레미스 환경과 유사
- 단점: 별도 서버 또는 클러스터 리소스 필요, 초기 설정 복잡

## 결정
**Docker Hub (Public) 사용**

## 이유
- 학습 목적으로 빠른 시작 가능
- 소스코드가 이미 GitHub에 공개되어 있어 이미지 공개에 큰 문제 없음
- imagePullSecret 설정 없이 간단하게 사용 가능
- 인프라 설정에 집중하기 위해 레지스트리 운영 부담 최소화

## 트레이드오프
- 이미지가 공개됨
- Docker Hub Rate Limit으로 인한 ImagePullBackOff 가능성
- 실무 Private 레지스트리 경험 부족

## 향후 계획
Harbor로 업그레이드 예정:
```
1. Master 노드에 Docker Compose로 Harbor 구축
   → 클러스터와 분리된 전용 레지스트리
   → 온프레미스 실무 환경과 유사
2. Kubernetes imagePullSecret 설정
3. CI/CD 파이프라인에서 Harbor로 자동 푸시
```