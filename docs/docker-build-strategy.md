# Docker 빌드 전략 비교 분석

## 개요

Spring Boot 애플리케이션을 Docker 이미지로 만들 때 세 가지 빌드 전략을 비교 분석했습니다.
측정 환경: Apple MacBook Air (M2), Docker Desktop

---

## 빌드 전략

### 1. 단순 빌드 (Dockerfile.simple)

```dockerfile
FROM maven:3.9-eclipse-temurin-17
COPY pom.xml .
COPY src ./src
RUN mvn package -DskipTests
ENTRYPOINT ["java", "-jar", "target/api-0.0.1-SNAPSHOT.jar"]
```

**특징**
- 단일 이미지에서 빌드와 실행을 모두 처리
- Maven, JDK, 소스코드가 모두 최종 이미지에 포함
- 구성이 단순하고 이해하기 쉬움

**문제점**
- 최종 이미지에 빌드 도구(Maven, JDK)가 포함되어 이미지 크기가 큼
- 소스코드 수정 시 의존성을 매번 재다운로드

---

### 2. 멀티 스테이지 빌드 (Dockerfile.multistage)

```dockerfile
# 1단계: 빌드
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn package -DskipTests

# 2단계: 실행
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /app/target/api-0.0.1-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**특징**
- 빌드 환경과 실행 환경을 분리
- 최종 이미지에는 JRE와 jar 파일만 포함
- 소스코드, Maven, JDK가 최종 이미지에 미포함

**개선점**
- 이미지 크기 대폭 감소
- 보안 향상 (불필요한 도구 미포함)

---

### 3. 멀티 스테이지 빌드 + 레이어 캐싱 (Dockerfile.multistage-cached)

```dockerfile
# 1단계: 빌드
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline  # 의존성 캐시 레이어
COPY src ./src
RUN mvn package -DskipTests

# 2단계: 실행
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /app/target/api-0.0.1-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**특징**
- 멀티 스테이지 빌드의 장점 유지
- `dependency:go-offline`으로 의존성을 별도 레이어로 분리
- Docker 레이어 캐시를 활용하여 재빌드 시 의존성 다운로드 생략

**Docker 레이어 캐시 동작 원리**
```
pom.xml 변경 없음 → dependency:go-offline 레이어 캐시 유지
src 변경          → mvn package만 재실행
→ 소스코드 수정 시 의존성 재다운로드 없이 빌드만 수행
```

---

## 성능 측정 결과

| 방법 | 이미지 크기 | 최초 빌드 | 재빌드 |
|------|------------|---------|--------|
| 단순 빌드 | 1.13GB | 29.4s | 19.4s |
| 멀티 스테이지 | 572MB | 32.7s | 19.7s |
| 멀티 스테이지 + 캐시 | 572MB | 39.7s | **4.6s** |

---

## 분석

### 이미지 크기
- 멀티 스테이지 빌드 적용 시 단순 빌드 대비 **49% 감소** (1.13GB → 572MB)
- 단순 빌드: Maven + JDK + 소스코드 포함
- 멀티 스테이지: JRE + jar 파일만 포함

### 재빌드 속도
- 레이어 캐싱 적용 시 단순 빌드 대비 **76% 감소** (19.4s → 4.6s)
- 소스코드 수정 시 의존성 다운로드 없이 빌드만 수행 (2.1s)

**단순 빌드 재빌드 로그** - 소스코드 수정 후 의존성 재다운로드 발생
```
CACHED [2/4] COPY pom.xml .     → 0.0s
[3/4] COPY src ./src            → 0.0s
[4/4] RUN mvn package           → 15.2s  ← 의존성 재다운로드 포함
```

**멀티 스테이지 + 캐시 재빌드 로그** - 의존성 레이어 캐시 유지
```
CACHED [builder 2/6] WORKDIR /app                    → 0.0s
CACHED [builder 3/6] COPY pom.xml .                  → 0.0s
CACHED [builder 4/6] RUN mvn dependency:go-offline   → 0.0s  ← 캐시 유지
[builder 5/6] COPY src ./src                         → 0.0s
[builder 6/6] RUN mvn package -DskipTests            → 2.1s  ← 빌드만
```

### 최초 빌드
- 세 방법 모두 유사 (네트워크 속도 영향이 큼)
- 레이어 캐싱 버전은 `dependency:go-offline` 추가로 약 7s 더 소요

**왜 최초 빌드가 더 오래 걸리나?**

단순 빌드 / 멀티 스테이지는 의존성 다운로드와 빌드를 한 번에 처리하지만,
레이어 캐싱 버전은 동일한 의존성 다운로드 작업을 별도 레이어로 분리하면서 오버헤드가 발생합니다.

```
단순 빌드:
mvn package = 의존성 다운로드 + 컴파일 + 패키징 (한 번에)

레이어 캐싱:
mvn dependency:go-offline = 의존성 다운로드  ← 추가 단계
mvn package               = 컴파일 + 패키징
```

**CI/CD 환경에서의 실질적 효과** (하루 30회 빌드 기준)

| 방법 | 회당 재빌드 | 30회 총 시간 |
|------|------------|-------------|
| 단순 빌드 | 19.4s | 582s |
| 멀티 스테이지 + 캐시 | 4.6s | 138s + 최초 39.7s = **177.7s** |

최초 빌드는 한 번이지만 재빌드는 수십 번 발생하므로 레이어 캐싱이 압도적으로 유리합니다.

---

## 결론 및 선택

**최종 선택: 멀티 스테이지 빌드 + 레이어 캐싱 (`Dockerfile.multistage-cached`)**

선택 이유:
1. 이미지 크기 최소화로 레지스트리 저장 비용 절감
2. 재빌드 속도 향상으로 CI/CD 파이프라인 효율화
3. 빌드 환경과 실행 환경 분리로 보안 향상
4. 최초 빌드 시간 증가는 CI/CD 환경에서 캐시 유지로 상쇄 가능

### CI/CD 파이프라인에서의 활용
```
GitHub Actions에서 테스트 실행
→ 통과 시 Docker 빌드 (-DskipTests)
→ 레이어 캐시 활용으로 빠른 빌드
→ 경량화된 이미지 레지스트리 푸시
→ Kubernetes 배포
```

---

## 참고: alpine 이미지 사용 불가 이슈

**시도한 이미지:** `eclipse-temurin:17-jre-alpine`

**문제:** Apple M4 (ARM 아키텍처)에서 alpine 이미지가 플랫폼을 지원하지 않음
```
ERROR: no match for platform in manifest: not found
```

**해결:** `eclipse-temurin:17-jre` (ubuntu 기반)로 대체

**참고:** CI/CD 환경(AMD64)에서는 alpine 이미지 사용 가능
- `eclipse-temurin:17-jre-alpine` 사용 시 추가 약 70MB 절감 가능