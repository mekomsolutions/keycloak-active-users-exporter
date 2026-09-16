# Build stage
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY pom.xml ./

RUN mvn --batch-mode --no-transfer-progress dependency:go-offline

COPY src ./src

RUN mvn --batch-mode --no-transfer-progress clean package

# Runtime stage
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Use an unprivileged user. The numeric IDs avoid depending on a host user.
RUN addgroup -S -g 10001 exporter \
    && adduser -S -D -H -u 10001 -G exporter exporter

COPY --from=build --chown=exporter:exporter \
    /workspace/target/keycloak-active-users-exporter-1.0.0-SNAPSHOT.jar \
    /app/app.jar

USER exporter

EXPOSE 9108

ENTRYPOINT ["java", "-jar", "/app/app.jar"]