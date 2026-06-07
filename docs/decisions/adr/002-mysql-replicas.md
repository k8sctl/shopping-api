# ADR-002: MySQL Replicas 결정

## 상태
결정됨 (향후 재검토 예정)

## 배경
Kubernetes StatefulSet으로 MySQL을 배포할 때 replicas 수를 결정해야 했습니다. replicas 수에 따라 가용성, 데이터 일관성, 복잡도가 달라집니다.

## 고려한 옵션

**1. replicas: 1 (단일 인스턴스)**
```yaml
replicas: 1
```
- 장점: 단순한 구성, 데이터 동기화 불필요
- 단점: 단일 장애점, Pod 재시작 시 서비스 중단

**2. replicas: 2 이상 (단순 복제)**
```yaml
replicas: 2
```
- 장점: Pod 여러 개
- 단점: MySQL은 기본적으로 단일 Primary 구조라 단순 replicas 증가는 데이터 불일치 발생

**3. MySQL Replication (Primary + Replica)**
```
Primary: 읽기/쓰기
Replica: 읽기 전용
```
- 장점: 고가용성, 읽기 성능 향상
- 단점: 설정 복잡, MySQL Operator 또는 별도 설정 필요

## 결정
**replicas: 1 사용**

## 이유
- 학습 목적 프로젝트로 단순 구성 우선
- MySQL은 단순 replicas 증가로는 고가용성 달성 불가
- MySQL Replication 설정은 별도 실험으로 진행 예정
- PersistentVolume으로 데이터 영속성은 보장

## 트레이드오프
- 단일 장애점 존재
- MySQL Pod 재시작 시 일시적 서비스 중단 가능

## 향후 계획
고가용성이 필요한 경우:
```
1. MySQL Operator 사용 (mysql-operator)
2. Primary + Replica 구성
3. 읽기/쓰기 분리
```
이 부분은 별도 실험으로 진행 예정입니다.