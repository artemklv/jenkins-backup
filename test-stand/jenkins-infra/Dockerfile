FROM jenkins/jenkins:lts-jdk17

# Disable the setup wizard: all configuration comes from JCasC
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false"

# Keep the JCasC config outside /var/jenkins_home
# so the Jenkins data volume does not shadow it
ENV CASC_JENKINS_CONFIG=/var/jenkins_casc/jenkins-casc.yaml

# MongoDB Database Tools (mongodump, mongorestore) for backup jobs.
# Pinned to 100.14.1: the last release that still supports MongoDB 3.6
# (100.15.0+ requires MongoDB 4.2+).
ARG MONGO_TOOLS_VERSION=100.14.1
ARG TARGETARCH
USER root
RUN set -eux; \
    case "${TARGETARCH:-$(dpkg --print-architecture)}" in \
      amd64) build="debian12-x86_64" ;; \
      arm64) build="ubuntu2404-arm64" ;; \
      *) echo "Unsupported architecture" >&2; exit 1 ;; \
    esac; \
    curl -fsSL "https://fastdl.mongodb.org/tools/db/mongodb-database-tools-${build}-${MONGO_TOOLS_VERSION}.tgz" \
      | tar -xz -C /tmp; \
    install -m 0755 /tmp/mongodb-database-tools-*/bin/* /usr/local/bin/; \
    rm -rf /tmp/mongodb-database-tools-*; \
    mkdir -p /var/backups/mongodb; \
    chown jenkins:jenkins /var/backups/mongodb
USER jenkins

# Install plugins
COPY --chown=jenkins:jenkins plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

# Jenkins Configuration as Code
COPY --chown=jenkins:jenkins jenkins-casc.yaml /var/jenkins_casc/jenkins-casc.yaml
COPY --chown=jenkins:jenkins jobs/ /var/jenkins_casc/jobs/
