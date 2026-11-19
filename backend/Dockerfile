# One build, one image per service:
#   docker build --target identity|exercise|repo|run|worker backend
# The seed tasks use the exercise and repo images with SPRING_PROFILES_ACTIVE=seed.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY contracts/pom.xml contracts/
COPY messaging/pom.xml messaging/
COPY platform/pom.xml platform/
COPY identity-service/pom.xml identity-service/
COPY exercise-service/pom.xml exercise-service/
COPY repo-service/pom.xml repo-service/
COPY run-service/pom.xml run-service/
COPY sandbox-worker/pom.xml sandbox-worker/
COPY content-tests/pom.xml content-tests/
RUN mvn -B -q dependency:go-offline
COPY contracts/src contracts/src
COPY messaging/src messaging/src
COPY platform/src platform/src
COPY identity-service/src identity-service/src
COPY exercise-service/src exercise-service/src
COPY repo-service/src repo-service/src
COPY run-service/src run-service/src
COPY sandbox-worker/src sandbox-worker/src
RUN mvn -B -q package -DskipTests -pl identity-service,exercise-service,repo-service,run-service,sandbox-worker -am

FROM eclipse-temurin:21-jre AS base
WORKDIR /app
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]

FROM base AS identity
COPY --from=build /src/identity-service/target/identity-service-exec.jar app.jar
EXPOSE 8080

FROM base AS exercise
COPY --from=build /src/exercise-service/target/exercise-service-exec.jar app.jar
EXPOSE 8080

FROM base AS repo
COPY --from=build /src/repo-service/target/repo-service-exec.jar app.jar
EXPOSE 8080

FROM base AS run
COPY --from=build /src/run-service/target/run-service-exec.jar app.jar
EXPOSE 8080

# only the worker gets the docker CLI; it starts the sandbox containers
FROM base AS worker
COPY --from=docker:27-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=build /src/sandbox-worker/target/sandbox-worker-exec.jar app.jar
