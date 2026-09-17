# Build : Maven + JDK 21, tests ignores (ils exigent l'infrastructure complete demarree).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package && cp target/*-SNAPSHOT.jar app.jar

# Execution : JRE 21 seul (curl est fourni par l'image et sert au healthcheck).
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system spring && useradd --system --gid spring spring
COPY --from=build /workspace/app.jar app.jar
USER spring
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
