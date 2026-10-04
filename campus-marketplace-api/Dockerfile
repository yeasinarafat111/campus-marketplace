# syntax=docker/dockerfile:1

# ---- Stage 1: build ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

# Resolve dependencies first so this layer is cached until pom.xml or the wrapper changes.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN ./mvnw -B clean package -DskipTests

# ---- Stage 2: runtime ----
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN addgroup -S app && adduser -S app -G app
COPY --from=build /workspace/target/*.jar app.jar
USER app

# Cloud Run injects PORT and routes traffic to it; 8080 is the default elsewhere.
ENV PORT=8080
EXPOSE 8080

# The app's own setting is server.port (SERVER_PORT in application.yml), not PORT, so pass it explicitly.
# Shell form so ${PORT} is expanded at runtime; exec makes java PID 1 and lets it receive SIGTERM.
ENTRYPOINT ["sh", "-c", "exec java -Dserver.port=${PORT} -jar app.jar"]
