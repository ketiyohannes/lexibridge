FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app

ENV DB_HOST=mysql \
    DB_PORT=3306 \
    DB_NAME=lexibridge \
    DB_USER=lexibridge \
    DB_PASSWORD=lexibridge \
    SERVER_PORT=8080 \
    FIELD_ENCRYPTION_KEY=1234567890abcdef1234567890abcdef \
    BOOTSTRAP_ENABLED=true \
    BOOTSTRAP_ADMIN_USERNAME=admin \
    BOOTSTRAP_ADMIN_FULL_NAME="LexiBridge Admin" \
    BOOTSTRAP_ADMIN_PASSWORD=AdminPass2026! \
    BOOTSTRAP_DEVICE_CLIENT_KEY=demo-device \
    BOOTSTRAP_DEVICE_SHARED_SECRET=local-dev-shared-secret-2026 \
    ANTIVIRUS_TCP_HOST=clamav \
    ANTIVIRUS_TCP_PORT=3310

COPY --from=build /workspace/target/operations-suite-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
