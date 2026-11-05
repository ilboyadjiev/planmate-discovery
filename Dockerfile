# Stage 1: build the jar (tests run in CI before the image is built)
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package -DskipTests

# Stage 2: run it
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /app/target/planmate-discovery-service-0.0.1-SNAPSHOT.jar app.jar

# Commit of this build, for the logs
ARG GIT_SHA=unknown
ENV APP_VERSION=$GIT_SHA
# The heap follows the container's memory limit (mem_limit in docker-compose.yml)
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

# Not published outside the Docker network; only the health endpoint listens here
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
