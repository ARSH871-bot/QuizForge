# One image serving the API and the web app from the same origin, so the
# session and CSRF cookies work with no cross-origin configuration.

# ---- web ---------------------------------------------------------------
FROM node:22-alpine AS web
WORKDIR /src/apps/web
COPY apps/web/package.json apps/web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY apps/web/ ./
RUN npm run build

# ---- api ---------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS api
WORKDIR /src
# The interfaces are generated from the contract at ../../openapi.yaml.
COPY openapi.yaml ./openapi.yaml
COPY apps/api/pom.xml apps/api/spotbugs-exclude.xml apps/api/
RUN mvn -q -f apps/api/pom.xml dependency:go-offline
COPY apps/api/src apps/api/src
# Tests run in CI against a real PostgreSQL; the image build only packages.
RUN mvn -q -f apps/api/pom.xml -DskipTests -Dspotbugs.skip=true package \
 && cp apps/api/target/api-*.jar /src/app.jar

# ---- runtime -----------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=api /src/app.jar app.jar
COPY --from=web /src/apps/web/dist web/
USER app

ENV SPRING_WEB_RESOURCES_STATIC_LOCATIONS=file:/app/web/ \
    PORT=8080
EXPOSE 8080

# DATABASE_URL, DB_USERNAME and DB_PASSWORD come from the host. The login role
# must be able to bypass row-level security; the app refuses to start if not.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
