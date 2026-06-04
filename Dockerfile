# 1단계: 빌드
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline # 의존성 먼저 다운로드(의존성 캐시 레이어)
COPY src ./src
RUN mvn package -DskipTests

# 2단계: 실행
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /app/target/api-0.0.1-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]