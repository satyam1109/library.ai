# syntax=docker/dockerfile:1

# Build the Spring Boot executable with the same Java version declared in pom.xml.
FROM maven:3.9.11-eclipse-temurin-21-alpine AS build

WORKDIR /workspace

COPY pom.xml ./
COPY src ./src

# BuildKit retains Maven's dependency cache without downloading unrelated plugins.
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package

# The production image contains only Java and the packaged application.
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
RUN addgroup -S libraryai && adduser -S libraryai -G libraryai

COPY --from=build /workspace/target/library-ai-*.jar app.jar

USER libraryai
EXPOSE 10000

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
