# Java 17 environment
ARG VARIANT=17-bullseye
FROM mcr.microsoft.com/devcontainers/java:${VARIANT}

# Install SCTP tools
# Remove the problematic yarn repository before updating
RUN rm -f /etc/apt/sources.list.d/yarn.list \
    && apt-get update && export DEBIAN_FRONTEND=noninteractive \
    && apt-get -y install --no-install-recommends lksctp-tools iproute2 maven \
    && apt-get clean -y && rm -rf /var/lib/apt/lists/*
