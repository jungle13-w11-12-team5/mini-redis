# minidb 실행 이미지: vendor/minidb를 release로 빌드하고 minidb 타깃만 담는다.
# 빌드 컨텍스트 = 저장소 루트 (compose.yaml)
FROM ubuntu:24.04 AS build
ARG DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential cmake ninja-build libgtest-dev pkg-config libcpp-httplib-dev \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY vendor/minidb/ ./
# CMake가 GTest · tests · bench 소스를 요구해서 configure는 전체, 빌드는 minidb 하나만
RUN cmake --preset release && cmake --build --preset release --target minidb

FROM ubuntu:24.04
ARG DEBIAN_FRONTEND=noninteractive
# libcpp-httplib-dev: 실행 파일이 링크한 libcpp-httplib.so를 같이 가져온다. curl = healthcheck · 시드
RUN apt-get update && apt-get install -y --no-install-recommends libcpp-httplib-dev curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/build/release/minidb /usr/local/bin/minidb
COPY docker/minidb-entry.sh docker/seed.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/minidb-entry.sh /usr/local/bin/seed.sh
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/minidb-entry.sh"]
