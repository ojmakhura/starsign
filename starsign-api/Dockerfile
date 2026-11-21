# StarSign webservice (Spring Boot)
#
# The jar is built on the host first (`make build_api`).
# Build context: the starsign-api root (see .dockerignore)

# Java 21+ is needed for spring.threads.virtual.enabled; the code targets 21
FROM eclipse-temurin:25-jre-alpine

RUN addgroup -S starsign && adduser -S -G starsign starsign
WORKDIR /app

COPY --chown=starsign:starsign webservice/target/starsign-webservice.jar app.jar

USER starsign
EXPOSE 8085

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8086/actuator/health >/dev/null || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
