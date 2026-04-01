#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8081}"

echo "[1/5] Health check"
curl -fsS "${BASE_URL}/actuator/health" >/dev/null

echo "[2/5] Home page"
curl -fsS "${BASE_URL}/" >/dev/null

echo "[3/5] Login page"
curl -fsS "${BASE_URL}/login" >/dev/null

echo "[4/5] Actuator metrics"
curl -fsS "${BASE_URL}/actuator/metrics" >/dev/null

echo "[5/5] Prometheus scrape endpoint"
curl -fsS "${BASE_URL}/actuator/prometheus" >/dev/null

echo "Smoke checks passed for ${BASE_URL}"
