FROM --platform=$BUILDPLATFORM node:22-bookworm-slim AS frontend
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev
COPY index.html vite.config.js ./
COPY src ./src
RUN npm run build

FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /app
COPY backend/pom.xml ./pom.xml
COPY backend/src ./src
COPY --from=frontend /app/dist ./src/main/resources/static
RUN --mount=type=cache,target=/root/.m2 mvn -B package

FROM eclipse-temurin:21-jre
WORKDIR /app
ENV CRM_HOST=0.0.0.0 CRM_PORT=5174 JAVA_TOOL_OPTIONS="-Xms64m -Xmx384m -XX:MaxMetaspaceSize=192m -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC"
RUN groupadd --gid 10001 crm && useradd --uid 10001 --gid crm --no-create-home crm
COPY --from=backend /app/target/emie-crm-backend-1.0.0.jar ./crm.jar
USER crm
EXPOSE 5174
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=6 CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/5174; printf "GET /api/status HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n" >&3; head -1 <&3 | grep -q "200"'
ENTRYPOINT ["java","-jar","/app/crm.jar"]
