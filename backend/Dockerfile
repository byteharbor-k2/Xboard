FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY src ./src
RUN mvn -B -q -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN apt-get update -qq \
    && apt-get install -y -qq --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 1001 --home-dir /app sinx
COPY --from=build /workspace/target/sinx-backend-0.0.1-SNAPSHOT.jar app.jar
USER sinx
EXPOSE 8080 9091
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60.0", "-jar", "/app/app.jar"]
