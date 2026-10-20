# ---------------------------------------------------------------------------
# MURRAY PTS v2 — production image
#
# Two stages: the Gradle build needs a JDK plus dependency cache, the runtime image only
# needs a JRE 21 and the jars `installDist` lays out. Copying the dist directory (rather than
# hand-rolling a fat jar) is what keeps the SQLite native driver and logback.xml working.
#
#   docker build -t murray-pts:2.0.0 .
#   docker run -d --name pts -p 8080:8080 \
#     -e PTS_ENV=production -e PTS_SESSION_SECRET="$(openssl rand -hex 32)" \
#     -v pts-data:/app/data murray-pts:2.0.0
#
# docker-compose.yml is the recommended setup (app + TLS-terminating proxy + named volume).
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Dependencies first, so editing a .kt file does not re-resolve the whole classpath.
COPY build.gradle.kts settings.gradle.kts gradle.properties gradlew ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon -q || true

COPY src ./src
# No `clean`: installDist writes to build/install, which clean would delete before COPY runs.
RUN ./gradlew installDist --no-daemon

FROM eclipse-temurin:21-jre
# curl is only needed for HEALTHCHECK; the app itself never makes outbound HTTP calls.
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system pts && useradd --system --gid pts --home-dir /app --shell /usr/sbin/nologin pts \
    && mkdir -p /app/data /app/logs && chown -R pts:pts /app

WORKDIR /app
COPY --from=build --chown=pts:pts /src/build/install/murray-pts ./

# Paths inside the image assume /data and /logs are volumes (see docker-compose.yml).
# PTS_ENV is deliberately NOT set here: it must be a deployment decision, and setting it to
# production would make an unattended `docker run` abort on the missing PTS_SESSION_SECRET.
ENV PTS_HOST=0.0.0.0 \
    PTS_PORT=8080 \
    PTS_DB_PATH=/app/data/pts.db \
    PTS_DATA_FILE=/app/data/pts-data.json \
    PTS_DATA_DIR=/app/data \
    PTS_LOG_DIR=/app/logs \
    PTS_LOG_LEVEL=INFO

USER pts
VOLUME ["/app/data", "/app/logs"]
EXPOSE 8080

# Health = "the login page still answers". No auth required, and it fails when routing or the
# template engine is broken — the two things a restart can actually fix.
HEALTHCHECK --interval=30s --timeout=5s --start-period=25s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${PTS_PORT}/" > /dev/null || exit 1

# Run through `sh` because NTFS checkouts can lose the +x bit on the generated launcher.
# The script ends in `exec java ...`, so the JVM still becomes the signal-owning process and
# the shutdown hook that closes the SQLite pool runs on `docker stop`.
ENTRYPOINT ["sh", "/app/bin/murray-pts"]
