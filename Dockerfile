FROM clojure:lein-alpine

WORKDIR /usr/src/app

RUN apk add --no-cache git

CMD ["--help"]

RUN mkdir -p /etc/iplant/de/crypto && \
    touch /etc/iplant/de/crypto/pubring.gpg && \
    touch /etc/iplant/de/crypto/random_seed && \
    touch /etc/iplant/de/crypto/secring.gpg && \
    touch /etc/iplant/de/crypto/trustdb.gpg

COPY conf/main/logback.xml /usr/src/app/

COPY project.clj /usr/src/app/
RUN lein deps

RUN ln -s "/usr/bin/java" "/bin/apps"

COPY . /usr/src/app

RUN lein uberjar && \
    cp target/apps-standalone.jar .

ENTRYPOINT ["apps", "-Dlogback.configurationFile=/etc/iplant/de/logging/apps-logging.xml", "-cp", ".:apps-standalone.jar:/", "apps.core"]

ARG git_commit=unknown
ARG version=unknown
ARG descriptive_version=unknown

LABEL org.cyverse.git-ref="$git_commit"
LABEL org.cyverse.version="$version"
LABEL org.cyverse.descriptive-version="$descriptive_version"
LABEL org.label-schema.vcs-ref="$git_commit"
LABEL org.label-schema.vcs-url="https://github.com/cyverse-de/apps"
LABEL org.label-schema.version="$descriptive_version"
