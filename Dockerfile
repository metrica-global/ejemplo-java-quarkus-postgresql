# syntax=docker/dockerfile:1

# The one image of the application. It contains every module:
#
#   docker build -t zonacards/app .
#
# What a container does is decided when it starts, never when the image is built:
#
#   ZC_MODULES=lotes,giftcards   the modules this deployment serves
#   ZC_MODE=migrate              apply the schema migrations of every module and exit

ARG JAVA_VERSION=25

# --- build ---------------------------------------------------------------------------------
# Runs on the architecture of the build machine even when the image is built for another one:
# the output is bytecode, so a multi-architecture build compiles only once.
FROM --platform=$BUILDPLATFORM eclipse-temurin:${JAVA_VERSION}-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY libs libs
COPY app app
# Tests are not run here: the pipeline runs them before it builds an image.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -pl app -am package -DskipTests \
    && mv app/target/quarkus-app /quarkus-app

# --- runtime -------------------------------------------------------------------------------
FROM eclipse-temurin:${JAVA_VERSION}-jre AS runtime

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

# MaxRAMPercentage: the heap follows the memory of the task instead of a fixed -Xmx.
# ExitOnOutOfMemoryError: a task that ran out of memory is replaced, not left half alive.
# Extra flags can be added without rebuilding through JAVA_TOOL_OPTIONS.
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-Djava.util.logging.manager=org.jboss.logmanager.LogManager", \
  "-jar", "/app/quarkus-run.jar"]
