#!/bin/bash
# Fails if the .proto, golden vectors, Kotlin encoder and generated Monkey C tests drift apart.
set -euo pipefail
cd "$(dirname "$0")"
python3 -I gen_mc_tests.py --check
(cd ../android && ./gradlew :protocol:test -q)
echo "protocol OK"
