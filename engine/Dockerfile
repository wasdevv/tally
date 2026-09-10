# Build e runtime separados: a imagem final nao carrega Gradle nem o codigo
# fonte, so o JRE e o jar.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Primeiro so o que descreve as dependencias: alterar codigo nao invalida o
# cache do download.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY engine/build.gradle.kts engine/
COPY config config
RUN ./gradlew --no-daemon dependencies --quiet || true

COPY engine engine
RUN ./gradlew --no-daemon :engine:bootJar -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
# Usuario sem privilegio: o motor nunca precisa de root.
RUN useradd --create-home --shell /usr/sbin/nologin tally
COPY --from=build /src/engine/build/libs/*.jar /app/engine.jar
USER tally
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/engine.jar"]
