# 🛒 Shopping API

Spring Boot 기반 쇼핑몰 RESTful API 프로젝트입니다.
백엔드 API 구현부터 Kubernetes 배포, 모니터링, 성능 테스트까지 DevOps 전 과정을 다룹니다.

---

## 🛠 기술 스택

### Backend
| 기술 | 버전 |
|------|------|
| Java | 17 |
| Spring Boot | 4.0.6 |
| Spring Security | - |
| Spring Data JPA | - |
| MySQL | 8.0 |

### DevOps (예정)
| 기술 | 용도 |
|------|------|
| Docker | 컨테이너화 |
| Kubernetes (K3s) | 오케스트레이션 |
| GitHub Actions | CI/CD |
| ArgoCD | GitOps |
| Prometheus + Grafana | 모니터링 |
| k6 | 성능 테스트 |

---

## 📁 프로젝트 구조

```
api/
├── src/main/java/com/shop/api/
│   ├── domain/
│   │   ├── user/
│   │   ├── product/
│   │   └── order/
│   └── global/
│       ├── config/
│       ├── exception/
│       └── response/
├── src/main/resources/
│   └── application.yml
├── docker-compose.yml
├── .env.example
└── README.md
```

---

## 🚀 로컬 실행 방법

### 1. 사전 준비
- Java 17
- Docker Desktop
- IntelliJ IDEA (권장)

### 2. 프로젝트 클론
```bash
git clone https://github.com/{your-account}/shopping-api.git
cd shopping-api
```

### 3. 환경변수 설정
```bash
# .env.example을 복사해서 .env 파일 생성
cp .env.example .env

# .env 파일에 값 채우기
MYSQL_ROOT_PASSWORD=your_password_here
MYSQL_DATABASE=shopping_dev
MYSQL_USER=admin
MYSQL_PASSWORD=your_password_here
```

### 4. MySQL 실행
```bash
docker-compose up -d

# 정상 실행 확인
docker ps
docker inspect shopping-mysql | grep -A 5 Health
```

### 5. 앱 실행
IntelliJ Run Configuration에서 환경변수 설정 후 실행
```
MYSQL_USER=admin
MYSQL_PASSWORD=your_password
```

### 6. 헬스 체크
```bash
curl http://localhost:8080/actuator/health
```

정상 응답:
```json
{
  "status": "UP",
  "components": {
    "db": { "status": "UP" }
  }
}
```

---

## 🌿 브랜치 전략

GitHub Flow 사용

```
main
└── feature/{기능명}
```

### 커밋 컨벤션
| 타입 | 설명 |
|------|------|
| feat | 새로운 기능 |
| fix | 버그 수정 |
| docs | 문서 수정 |
| refactor | 리팩토링 |
| test | 테스트 코드 |
| chore | 빌드, 설정 변경 |
| infra | 인프라 관련 |

---

## 📌 API 명세

> 개발 진행 중, 추후 Swagger로 문서화 예정

| Method | Endpoint | 설명 |
|--------|----------|------|
| POST | /api/users/register | 회원가입 |
| POST | /api/users/login | 로그인 |
| GET | /api/products | 상품 목록 조회 |
| POST | /api/products | 상품 등록 |
| POST | /api/orders | 주문 생성 |
| GET | /api/orders/{id} | 주문 조회 |