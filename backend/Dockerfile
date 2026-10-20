FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src src
RUN mvn -B -DskipTests package
FROM eclipse-temurin:21.0.9_10-jre-jammy
WORKDIR /app
RUN groupadd --gid 10001 foodflow && useradd --uid 10001 --gid foodflow foodflow
COPY --from=build /app/target/foodflow-api.jar app.jar
USER foodflow
EXPOSE 8090
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","app.jar"]
