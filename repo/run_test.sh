#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAVEN_IMAGE="maven:3.9.9-eclipse-temurin-21"
M2_CACHE_VOLUME="${M2_CACHE_VOLUME:-lexibridge_m2_cache}"
KEEP_STACK_UP="${KEEP_STACK_UP:-false}"
TESTCONTAINERS_HOST_OVERRIDE_VALUE="${TESTCONTAINERS_HOST_OVERRIDE:-}"

if [ ! -S /var/run/docker.sock ]; then
  echo "Docker socket not found at /var/run/docker.sock. Start Docker Desktop/Engine first." >&2
  exit 1
fi

cleanup() {
  if [ "$KEEP_STACK_UP" != "true" ]; then
    echo "[run_test] Stopping docker compose stack..."
    docker compose down >/dev/null 2>&1 || true
  else
    echo "[run_test] KEEP_STACK_UP=true, leaving docker compose stack running."
  fi
}
trap cleanup EXIT

echo "[run_test] Starting docker compose stack..."
docker compose up --build -d

docker volume create "$M2_CACHE_VOLUME" >/dev/null

echo "[run_test] Running Maven tests in Docker..."
DOCKER_RUN_ARGS=(
  --rm
  -v "$ROOT_DIR:/workspace"
  -v "$M2_CACHE_VOLUME:/root/.m2"
  -v /var/run/docker.sock:/var/run/docker.sock
  -e DOCKER_HOST=unix:///var/run/docker.sock
  -w /workspace
)

if [ -n "$TESTCONTAINERS_HOST_OVERRIDE_VALUE" ]; then
  DOCKER_RUN_ARGS+=(
    --add-host=host.docker.internal:host-gateway
    -e TESTCONTAINERS_HOST_OVERRIDE="$TESTCONTAINERS_HOST_OVERRIDE_VALUE"
  )
fi

docker run "${DOCKER_RUN_ARGS[@]}" \
  "$MAVEN_IMAGE" \
  mvn -B test

echo "[run_test] Tests completed successfully."
