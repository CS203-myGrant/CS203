# Build the executable Spring Boot jar using the checked-in Maven Wrapper.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src/ src/
COPY app/ app/
RUN ./mvnw -B -DskipTests package

# Run as an unprivileged user; serve the checked-in frontend from the app directory.
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 spring
COPY --from=build /workspace/target/policy-impact-*.jar /app/app.jar
COPY --from=build --chown=10001:10001 /workspace/app/ /app/app/
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
