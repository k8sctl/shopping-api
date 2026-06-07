# ADR-004: MySQL 준비 대기 전략 (initContainers)

## 상태
결정됨

## 배경
Kubernetes에서 docker-compose의 `depends_on`과 같은 기능이 없어 앱 컨테이너가 MySQL보다 먼저 시작될 경우 DB 연결 실패가 발생합니다. 이를 해결하기 위한 전략이 필요했습니다.

자세한 실험 내용은 [k8s-init-containers-experiment.md](../../infra/k8s-init-containers-experiment.md)를 참고하세요.

## 고려한 옵션

**1. 아무것도 안 함 (CrashLoopBackOff 자동 복구)**
- 앱 시작 → MySQL 연결 실패 → 재시작 반복
- Kubernetes가 자동으로 재시도하여 MySQL 준비 후 정상화

**2. initContainers**
```yaml
initContainers:
  - name: wait-for-mysql
    image: busybox
    command: ['sh', '-c', 'until nc -z mysql 3306; do echo waiting for mysql; sleep 2; done']
```
- MySQL 포트가 열릴 때까지 대기 후 앱 시작

**3. Spring Retry 설정**
- application.yml에서 DB 연결 재시도 설정
- 앱 레벨에서 해결

## 결정
**initContainers 사용**

## 이유
실험 결과 비교:

| 항목 | initContainers 없음 | initContainers 있음 |
|------|-------------------|-------------------|
| 재시작 횟수 | 2회 | 0회 |
| 앱 Running까지 | 57s | 44s |
| 에러 로그 | 대량 발생 | 없음 |
| 상태 변화 | CrashLoopBackOff | 조용히 대기 |

- 불필요한 재시작과 에러 로그 없음
- 운영 환경에서 모니터링 알람 오발령 방지
- 더 빠른 안정적 시작 (57s → 44s)

## 트레이드오프
- busybox 이미지 추가 pull 필요 (1MB로 매우 작음)
- initContainer 로직 별도 관리 필요

## 향후 계획
- Spring Retry 설정과 병행하여 앱 레벨 복원력도 강화 예정
- Readiness Probe 추가로 트래픽 제어 개선 예정