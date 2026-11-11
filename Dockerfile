# Approval UI image.  docker build -t api-test-platform . && docker compose up
FROM eclipse-temurin:17-jre
WORKDIR /workspace
COPY target/api-test-platform.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-Dplatform.home=/workspace", "-jar", "/app/app.jar", "serve"]
