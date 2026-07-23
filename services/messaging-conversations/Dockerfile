# Fargate deployment artifact + local-compose image. Same shape as the Presence
# service. The Lambda adapter(s) ship in the same fat jar.

# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package

# ---- run ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/messaging-conversations.jar ./app.jar
ENV PORT=3000
EXPOSE 3000
CMD ["java", "-jar", "app.jar"]
