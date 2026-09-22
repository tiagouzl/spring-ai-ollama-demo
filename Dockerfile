# syntax=docker/dockerfile:1

# ---------- build stage ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Cache dependency resolution: pom first, sources after
COPY pom.xml ./
RUN mvn -B -q dependency:resolve
COPY src ./src
RUN mvn -B -q -DskipTests package

# ---------- runtime stage ----------
FROM eclipse-temurin:25-jre
# curl for the HEALTHCHECK; the app runs as uid 1000 (numeric USER, no name
# dependency — recent Temurin images already ship a uid-1000 user, so useradd
# is only a fallback). Keep ./data writable by it (host first-user kwid match).
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && (id 1000 >/dev/null 2>&1 || useradd -m -U -u 1000 appuser)
WORKDIR /app
RUN mkdir -p /app/data && chown -R 1000:1000 /app
COPY --from=build /build/target/*.jar app.jar
RUN chown 1000:1000 app.jar
USER 1000
EXPOSE 8080
# ./data (HSQLDB chat memory + persisted vector store) is mounted from the host
# via docker-compose, so conversations and embeddings survive container restarts.
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]