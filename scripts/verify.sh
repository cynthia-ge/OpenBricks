#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
exec ./mvnw -B clean verify
