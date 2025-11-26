# Multi-stage build to create a lightweight runtime image
FROM maven:3.9.9-eclipse-temurin-17 AS builder
WORKDIR /app

# Leverage cached layers for dependency download
COPY pom.xml mvnw mvnw.cmd .
COPY .mvn .mvn
RUN chmod +x mvnw
RUN ./mvnw -B -DskipTests dependency:go-offline

# Copy sources and build the application
COPY src src
RUN ./mvnw -B -DskipTests package

# Runtime image
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
