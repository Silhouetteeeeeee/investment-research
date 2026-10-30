# Build stage: the Maven reactor (core/data/app) is packaged into one executable jar.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY core core
COPY data data
COPY app app
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre
# curl is only for the healthcheck: the JRE image does not ship an HTTP client.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
# Fixed UID/GID so the host can chown the ./var bind mount to the same owner.
 && groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid 10001 --home-dir /app --shell /usr/sbin/nologin app

WORKDIR /app
# No secrets here: they come from the environment / compose .env at runtime.
ENV APP_DATA_DIR=/app/var \
    JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75

# WORKDIR /app matches the app's file:./frontend/ and file:${APP_DATA_DIR} relative paths.
COPY --from=build --chown=app:app /build/app/target/app-*-SNAPSHOT.jar /app/app.jar
COPY --chown=app:app frontend /app/frontend
COPY --chown=app:app skills /app/skills
RUN mkdir -p /app/var && chown app:app /app/var

USER app
EXPOSE 8080

# Management port stays inside the container; compose never publishes 8081.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8081/actuator/health || exit 1

# exec form: java is PID 1 and receives SIGTERM, so graceful shutdown works.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
