# Use a maintained JDK image instead of openjdk:17-jdk
FROM eclipse-temurin:17-jre

LABEL authors="yuqi.guo17@gmail.com"

# Set the working directory in the container
WORKDIR /app

# Copy the jar file to the container
# 确保本地先 mvn package，把这个 jar 打出来
COPY target/MrPot-0.0.1-SNAPSHOT.jar /app/app.jar

# Copy the application-docker.properties file to the container
COPY src/main/resources/application-docker.properties /app/application-docker.properties

# Make port 8080 available to the world outside this container
EXPOSE 8080

# Run the jar file
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
