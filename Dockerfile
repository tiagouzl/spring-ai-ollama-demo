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
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
EXPOSE 8080
# ./data (HSQLDB chat memory + persisted vector store) is mounted from the host
# via docker-compose, so conversations and embeddings survive container restarts.
ENTRYPOINT ["java", "-jar", "app.jar"]