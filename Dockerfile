# syntax=docker/dockerfile:1

# ---------- build stage ----------
FROM maven:3-eclipse-temurin-26 AS build
WORKDIR /build
# Cache dependency resolution: pom first, sources after
COPY pom.xml ./
RUN mvn -B -q dependency:resolve
COPY src ./src
RUN mvn -B -q -DskipTests package

# ---------- runtime stage ----------
FROM eclipse-temurin:21-jre
# curl for the HEALTHCHECK; fixed uid 1000 so the mounted ./data dir (usually
# owned by the host's first user) stays writable for the non-root app user.
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -m -U -u 1000 appuser
WORKDIR /app
RUN mkdir -p /app/data && chown -R appuser:appuser /app
COPY --from=build /build/target/*.jar app.jar
RUN chown appuser:appuser app.jar
USER appuser
EXPOSE 8080
# ./data (HSQLDB chat memory + persisted vector store) is mounted from the host
# via docker-compose, so conversations and embeddings survive container restarts.
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]