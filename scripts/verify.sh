#!/bin/sh
set -eu
h1_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if [ -z "${JAVA_HOME:-}" ] && [ "$(uname -s)" = "Darwin" ]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 21)
    export JAVA_HOME
fi
cd "$h1_root/android"
./gradlew :core:test :app:assembleDebug :app:lintDebug
