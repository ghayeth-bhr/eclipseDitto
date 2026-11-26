# syntax=docker/dockerfile:1.7
# Builds the twin service and the fake NARU publisher from source (JDK 21), then ships each on a
# JRE 21 base as a non-root user. Used by the root docker-compose.yml (targets "twin", "fake-naru").

FROM docker.io/maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -DskipTests -pl twin-app,twin-fixture-naru -am package

FROM docker.io/eclipse-temurin:21-jre@sha256:d77e090ae1f6970c047b443c6f0a6c1358b0426d44432bb5a7753408cd43de0d AS runtime
RUN useradd --uid 10001 --create-home --shell /usr/sbin/nologin naruprof \
 && mkdir -p /app /data && chown naruprof:naruprof /data
WORKDIR /app
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

FROM runtime AS fake-naru
COPY --from=build /src/twin-fixture-naru/target/twin-fixture-naru-1.0.0-exec.jar /app/app.jar
USER naruprof
EXPOSE 9092 9093
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM runtime AS twin
COPY --from=build /src/twin-app/target/naruprof-twin-exec.jar /app/app.jar
USER naruprof
VOLUME ["/data"]
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
