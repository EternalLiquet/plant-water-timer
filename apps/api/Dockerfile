FROM eclipse-temurin:21-jre

WORKDIR /app
COPY build/libs/plant-care-api-*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
