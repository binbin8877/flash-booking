FROM gradle:8.10-jdk21 AS build
WORKDIR /workspace
COPY --chown=gradle:gradle . .
RUN gradle --no-daemon bootJar -x test

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=docker
ENTRYPOINT ["java","-XX:+UseG1GC","-Xms256m","-Xmx512m","-jar","/app/app.jar"]
