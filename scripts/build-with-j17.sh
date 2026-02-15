#!/bin/bash
set -e

# Use Java 17 to run Gradle (for building)
export JAVA_HOME=/tmp/jdk-17.0.9+9
export PATH="$JAVA_HOME/bin:$PATH"
export JAVA_TOOL_OPTIONS=""

cd /home/xenoamess/.openclaw/workspace/openclaw-on-cloud/backend

GRADLE="/tmp/gradle-8.12/bin/gradle"

echo "Building with Java 17..."
$GRADLE build -x test

echo "Build complete!"
ls -la build/libs/
