# Present and buildable per CLAUDE.md — this is the Fargate deployment artifact
# (Presence is an early Lambda→Fargate migration target) and what docker-compose
# runs locally. The Lambda adapter ships in the same fat jar.

# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
# Copy the POM first and pre-fetch dependencies so this layer caches across
# source-only changes (Java dep downloads are large — don't repeat them).
COPY pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package

# ---- run ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/presence-connection.jar ./app.jar
ENV PORT=3000
EXPOSE 3000
CMD ["java", "-jar", "app.jar"]
