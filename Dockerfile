FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /source
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -DskipTests dependency:go-offline
COPY src src
RUN ./mvnw -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN groupadd --system tracelens && useradd --system --gid tracelens --home-dir /app tracelens
WORKDIR /app
COPY --from=build /source/target/tracelens-ai-1.0.0.jar app.jar
USER tracelens
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70.0", "-jar", "/app/app.jar"]
