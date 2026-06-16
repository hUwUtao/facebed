FROM maven:3.9.9-eclipse-temurin-21 AS builder
WORKDIR /app
COPY pom.xml .
COPY src ./src
COPY assets ./assets
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /facebed
COPY --from=builder /app/target/facebed-1.0.0-SNAPSHOT.jar ./facebed.jar
COPY assets ./assets
RUN /bin/sh -c "echo '{}' > ./config.yaml"
RUN adduser --disabled-password --gecos '' facebed
USER facebed
EXPOSE 9812
CMD ["java", "-jar", "./facebed.jar", "-c", "./config.yaml"]
