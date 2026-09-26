# Build context is the repository root (see docker-compose.yml) so the parent pom is available.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
# The reactor needs every module pom listed in the root pom, even with -pl.
COPY producer/pom.xml producer/
COPY backend/pom.xml backend/
RUN mvn -B -q -pl backend -am dependency:go-offline
COPY backend/src backend/src
RUN mvn -B -q -pl backend -am -DskipTests package

FROM eclipse-temurin:21-jre
RUN useradd --system --no-create-home backend
WORKDIR /app
COPY --from=build /workspace/backend/target/backend.jar backend.jar
USER backend
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/backend.jar"]
