# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q package -DskipTests

# ---- run ----
# Ubuntu-based JRE: ONNX Runtime and FFmpeg natives need glibc (Alpine will not work)
FROM eclipse-temurin:21-jre
WORKDIR /app

# Which exported model to bake into the image (must exist in ./models)
ARG MODEL=yoloworldx-960.onnx
COPY models/${MODEL} models/${MODEL}
COPY models/classes.txt models/classes.txt
COPY --from=build /build/target/visiontrack-object-detection-aws-1.0-SNAPSHOT.jar app.jar

ENV VISIONTRACK_MODEL_PATH=/app/models/${MODEL} \
    VISIONTRACK_MODEL_CLASSES_PATH=/app/models/classes.txt \
    VISIONTRACK_STORAGE_DIRECTORY=/app/data/uploads \
    VISIONTRACK_OPEN_BROWSER=false \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

# Fonts for the labels drawn into annotated videos (Java2D needs fontconfig + at least one font)
RUN apt-get update && apt-get install -y --no-install-recommends fontconfig fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --system --create-home visiontrack && mkdir -p data/uploads && chown -R visiontrack /app
USER visiontrack

# Health check for ECS / the load balancer: GET /actuator/health
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
