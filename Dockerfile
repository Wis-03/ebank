# ebank-chatbot-service (projet Maven racine).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package && cp target/*-SNAPSHOT.jar app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system spring && useradd --system --gid spring spring
COPY --from=build /workspace/app.jar app.jar
USER spring
EXPOSE 8098
# Aucun secret dans l'image : OPENAI_API_KEY, PGVECTOR_PASSWORD et TELEGRAM_BOT_TOKEN sont injectes au runtime.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
