FROM eclipse-temurin:21-jre

WORKDIR /app
COPY build/libs/spring-boot-k8s-gitops-flux-sops-workshop.jar app.jar

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
