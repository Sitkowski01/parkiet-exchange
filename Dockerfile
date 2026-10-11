# Obraz gieldy: kompilacja w JDK, uruchomienie w samym JRE. Jar rozpakowany na warstwy -- zmiana kodu
# przebudowuje tylko ostatnia, a biblioteki (wiekszosc obrazu) zostaja w cache.
#   docker compose --profile app up --build      (calosc: gielda, Postgres, Kafka; http://localhost:8080)
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
# Same zaleznosci, zanim przyjdzie kod: ta warstwa przebudowuje sie tylko przy zmianie build.gradle.kts.
RUN ./gradlew --no-daemon --quiet dependencies --configuration runtimeClasspath > /dev/null
COPY src/main src/main
RUN ./gradlew --no-daemon --quiet bootJar \
 && java -Djarmode=tools -jar build/libs/parkiet-exchange-0.0.1-SNAPSHOT.jar extract --layers --launcher --destination /app

FROM eclipse-temurin:21-jre
RUN useradd --system --no-create-home parkiet
WORKDIR /app
COPY --from=build /app/dependencies/ ./
COPY --from=build /app/spring-boot-loader/ ./
COPY --from=build /app/snapshot-dependencies/ ./
COPY --from=build /app/application/ ./
USER parkiet
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
