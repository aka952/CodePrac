# One build, three images: docker build --target api|worker|seed
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY core/pom.xml core/
COPY api/pom.xml api/
COPY worker/pom.xml worker/
COPY seed/pom.xml seed/
COPY all-in-one/pom.xml all-in-one/
RUN mvn -B -q dependency:go-offline
COPY core/src core/src
COPY api/src api/src
COPY worker/src worker/src
COPY seed/src seed/src
RUN mvn -B -q package -DskipTests -pl api,worker,seed -am

FROM eclipse-temurin:21-jre AS base
WORKDIR /app
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]

FROM base AS api
COPY --from=build /src/api/target/codecomp-api-exec.jar app.jar
EXPOSE 8080

FROM base AS seed
COPY --from=build /src/seed/target/codecomp-seed-exec.jar app.jar

# only the worker gets the docker CLI; it starts the sandbox containers
FROM base AS worker
COPY --from=docker:27-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=build /src/worker/target/codecomp-worker-exec.jar app.jar
