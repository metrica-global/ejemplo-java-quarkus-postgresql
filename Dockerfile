# syntax=docker/dockerfile:1

# One Dockerfile for every service of the repository:
#
#   docker build --build-arg SERVICE=giftcards -t zonacards/giftcards-service .
#
# The same image runs the service (default) and its schema migrations (ZC_MODE=migrate).

ARG JAVA_VERSION=25

# --- build ---------------------------------------------------------------------------------
# Runs on the architecture of the build machine even when the image is built for another one:
# the output is bytecode, so a multi-architecture build compiles only once.
FROM --platform=$BUILDPLATFORM eclipse-temurin:${JAVA_VERSION}-jdk AS build
ARG SERVICE
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY libs libs
COPY services services
# Tests are not run here: the pipeline runs them before it builds an image.
RUN --mount=type=cache,target=/root/.m2 \
    test -n "${SERVICE}" || { echo "build argument SERVICE is required (lotes or giftcards)" >&2; exit 1; }; \
    ./mvnw -B -ntp -pl "services/${SERVICE}-service" -am package -DskipTests \
    && mv "services/${SERVICE}-service/target/quarkus-app" /quarkus-app

# --- runtime -------------------------------------------------------------------------------
FROM eclipse-temurin:${JAVA_VERSION}-jre AS runtime
ARG SERVICE

# curl is the container health check (ECS and docker compose call /q/health/live with it).
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 zonacards \
    && useradd --system --uid 10001 --gid 10001 --home-dir /app --no-create-home --shell /usr/sbin/nologin zonacards

WORKDIR /app
# Ordered from what changes least to what changes most, so a code change replaces one small layer.
COPY --from=build --chown=10001:10001 /quarkus-app/lib/ lib/
COPY --from=build --chown=10001:10001 /quarkus-app/quarkus-run.jar ./
COPY --from=build --chown=10001:10001 /quarkus-app/quarkus/ quarkus/
COPY --from=build --chown=10001:10001 /quarkus-app/app/ app/

USER 10001:10001
EXPOSE 8080
ENV ZC_SERVICE=${SERVICE}

# MaxRAMPercentage: the heap follows the memory of the task instead of a fixed -Xmx.
# ExitOnOutOfMemoryError: a task that ran out of memory is replaced, not left half alive.
# Extra flags can be added without rebuilding through JAVA_TOOL_OPTIONS.
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-Djava.util.logging.manager=org.jboss.logmanager.LogManager", \
  "-jar", "/app/quarkus-run.jar"]
