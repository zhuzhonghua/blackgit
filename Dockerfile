# blackgit server Docker image
# Build: docker build -t blackgit .
# Run:   docker run -p 8081:8081 \
#           -v /data/blackgit:/data \
#           -e BLACKGIT_PORT=8081 \
#           -e BLACKGIT_UPSTREAM=https://github.com/user/repo.git \
#           blackgit

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY target/blackgit-0.1.0-SNAPSHOT-standalone.jar /app/blackgit.jar
COPY docker-entrypoint.sh /app/entrypoint.sh

RUN chmod +x /app/entrypoint.sh

# Data dir (mount a volume here)
ENV BLACKGIT_ROOT=/data
VOLUME ["/data"]

EXPOSE 8081

ENTRYPOINT ["/app/entrypoint.sh"]
