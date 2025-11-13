# Build stage
FROM clojure:temurin-22-lein-jammy AS builder

WORKDIR /usr/src/app

# Install git (required by project.clj for git-ref function)
RUN apt-get update && \
    apt-get install -y git && \
    rm -rf /var/lib/apt/lists/*

# Copy project.clj and download dependencies (for layer caching)
COPY project.clj /usr/src/app/
RUN lein deps

# Copy source and build uberjar
COPY . /usr/src/app
RUN lein do clean, uberjar && \
    mv target/apps-standalone.jar .

# Runtime stage
FROM eclipse-temurin:22-jre-jammy

WORKDIR /usr/src/app

# Create crypto directory structure
RUN mkdir -p /etc/iplant/de/crypto && \
    touch /etc/iplant/de/crypto/pubring.gpg \
          /etc/iplant/de/crypto/random_seed \
          /etc/iplant/de/crypto/secring.gpg \
          /etc/iplant/de/crypto/trustdb.gpg

# Create symlink for the apps binary
RUN ln -s "/opt/java/openjdk/bin/java" "/bin/apps"

ENV OTEL_TRACES_EXPORTER=none

# Copy only necessary files from builder stage
COPY --from=builder /usr/src/app/apps-standalone.jar /usr/src/app/
COPY conf/main/logback.xml /usr/src/app/

ENTRYPOINT ["apps", "-Dlogback.configurationFile=/usr/src/app/logback.xml", "-cp", ".:apps-standalone.jar:/", "apps.core"]

ARG git_commit=unknown
ARG version=unknown
ARG descriptive_version=unknown

LABEL org.cyverse.git-ref="$git_commit"
LABEL org.cyverse.version="$version"
LABEL org.cyverse.descriptive-version="$descriptive_version"
LABEL org.opencontainers.image.authors="CyVerse Core Software Team <support@cyverse.org>"
LABEL org.opencontainers.image.revision="$git_commit"
LABEL org.opencontainers.image.source="https://github.com/cyverse-de/apps"
LABEL org.opencontainers.image.version="$descriptive_version"
