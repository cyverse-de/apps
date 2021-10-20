FROM clojure:openjdk-17-lein-alpine

WORKDIR /usr/src/app

RUN apk upgrade apk-tools && \
    apk upgrade && \
    apk add --no-cache git

CMD ["--help"]

RUN mkdir -p /etc/iplant/de/crypto && \
    touch /etc/iplant/de/crypto/pubring.gpg && \
    touch /etc/iplant/de/crypto/random_seed && \
    touch /etc/iplant/de/crypto/secring.gpg && \
    touch /etc/iplant/de/crypto/trustdb.gpg

COPY conf/main/logback.xml /usr/src/app/

RUN ln -s "/opt/openjdk-17/bin/java" "/bin/apps"

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
