# Fargate artifact + local-compose image. Same shape as the other Java services,
# plus ffmpeg for video/audio shrinking and video frame-thumbnail extraction.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package

FROM eclipse-temurin:21-jre
# ffmpeg: video/audio transcode + frame extraction (Phase 8.5). Images are pure-JVM.
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/target/media.jar ./app.jar
ENV PORT=3000
EXPOSE 3000
CMD ["java", "-jar", "app.jar"]
