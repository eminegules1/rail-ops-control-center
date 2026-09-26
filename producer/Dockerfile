# Build context is the repository root (see docker-compose.yml) so the parent pom is available.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY producer/pom.xml producer/
RUN mvn -B -q -pl producer -am dependency:go-offline
COPY producer/src producer/src
RUN mvn -B -q -pl producer -am -DskipTests package

FROM eclipse-temurin:21-jre
RUN useradd --system --no-create-home producer
WORKDIR /app
COPY --from=build /workspace/producer/target/producer.jar producer.jar
USER producer
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/producer.jar"]
