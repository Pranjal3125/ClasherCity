#!/usr/bin/env bash
set -euo pipefail
JAVA_VERSION=$(java -version 2>&1 | head -n 1)
echo "Detected: ${JAVA_VERSION}"
if ! java -version 2>&1 | grep -Eq 'version "25([.]|[+"])'; then
  echo "ERROR: JDK 25 is required for Paper 26.2 builds. Install/select Java 25 and retry." >&2
  exit 1
fi
if ! command -v mvn >/dev/null 2>&1; then
  echo "ERROR: Maven 3.9+ is required. Install Maven and retry." >&2
  exit 1
fi
mvn -U clean package
echo "Build complete: target/ClasherCity-1.0.0.jar"
