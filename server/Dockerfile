FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN ./mvnw -q -DskipTests package

FROM eclipse-temurin:21-jre
COPY --from=build /app/target/server-0.0.1-SNAPSHOT.jar /app.jar
ENTRYPOINT ["java","-Xmx350m","-jar","/app.jar"]
