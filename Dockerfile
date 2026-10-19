FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY Server.java .
COPY public ./public
CMD ["java", "Server.java"]