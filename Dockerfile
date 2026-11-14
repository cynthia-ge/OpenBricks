FROM eclipse-temurin:25-jdk AS build
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src/main src/main
RUN --mount=type=cache,target=/root/.m2,sharing=locked ./mvnw -B -Dmaven.test.skip=true package

FROM eclipse-temurin:25-jre AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
RUN mkdir -p /app /data && chown -R 10001:10001 /app /data
WORKDIR /app
COPY --from=build /build/target/openbricks-0.2.0-SNAPSHOT.jar /app/app.jar
USER 10001:10001
ENV DATABASE=/data/openbricks.db
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --retries=10 CMD curl --fail --silent http://localhost:8080/healthz || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
