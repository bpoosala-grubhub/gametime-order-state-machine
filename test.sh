#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build/test-classes
find src/main/java src/test/java -name '*.java' -print0 | xargs -0 javac --release 21 -d build/test-classes
exec java -ea -cp build/test-classes com.gametime.orders.OrderTests
