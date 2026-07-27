# Build context is the repo root (see docker-compose.yml), not backend/,
# since this stage also needs access to ../frontend.

FROM node:22-slim AS frontend-build
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ .
RUN npm run build

FROM eclipse-temurin:21-jdk AS backend-build
WORKDIR /backend
COPY backend/mvnw ./
COPY backend/.mvn .mvn
COPY backend/pom.xml ./
RUN chmod +x mvnw && ./mvnw -q dependency:go-offline
COPY backend/src src
# Baking the built frontend into static/app here (rather than the
# -Pfrontend Maven profile used for local builds) avoids needing Node
# inside the Maven build stage at all - the frontend-build stage above
# already produced it.
COPY --from=frontend-build /frontend/dist src/main/resources/static/app
RUN ./mvnw -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=backend-build /backend/target/swarmhq-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
