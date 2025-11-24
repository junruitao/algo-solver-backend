# Use the official maven/Java 17 image to create a build artifact.
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
# Build a release artifact.
RUN mvn package -DskipTests

# Use Eclipse Temurin for base image for the runtime
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /app/target/algo-solver-0.0.1-SNAPSHOT.jar app.jar

# Run the web service on container startup.
CMD ["java", "-jar", "/app/app.jar"]