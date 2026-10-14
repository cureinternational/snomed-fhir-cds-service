FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY . .
RUN mvn -q package -DskipTests && cp target/snomed-fhir-cds-service-*.jar /snomed-fhir-cds-service.jar

FROM eclipse-temurin:17-jre
COPY --from=build /snomed-fhir-cds-service.jar /snomed-fhir-cds-service.jar
COPY --from=build /src/*.tsv /
ENTRYPOINT ["/bin/sh", "-c", "exec java $JAVA_OPTS -jar /snomed-fhir-cds-service.jar"]
