FROM clojure:temurin-22-lein-jammy

WORKDIR /usr/src/app

RUN apt-get update && \
    apt-get install -y git && \
    rm -rf /var/lib/apt/lists/*

CMD ["--help"]

RUN mkdir -p /etc/iplant/de/crypto && \
    touch /etc/iplant/de/crypto/pubring.gpg && \
    touch /etc/iplant/de/crypto/random_seed && \
    touch /etc/iplant/de/crypto/secring.gpg && \
    touch /etc/iplant/de/crypto/trustdb.gpg

RUN ln -s "/opt/java/openjdk/bin/java" "/bin/apps"

ENV OTEL_TRACES_EXPORTER none

COPY conf/main/logback.xml /usr/src/app/

COPY project.clj /usr/src/app
RUN lein deps

COPY . /usr/src/app
RUN lein do clean, uberjar && \
    mv target/apps-standalone.jar . && \
    lein clean && \
    rm -r ~/.m2/repository

ENTRYPOINT ["apps", "-Dlogback.configurationFile=/etc/iplant/de/logging/apps-logging.xml", "-cp", ".:apps-standalone.jar:/", "apps.core"]

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
