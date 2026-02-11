#!/bin/bash
set -e

export JAVA_HOME=/tmp/jdk-25.0.2+10
export PATH="$JAVA_HOME/bin:$PATH"
export JAVA_TOOL_OPTIONS="--enable-native-access=ALL-UNNAMED"

cd /home/xenoamess/.openclaw/workspace/openclaw-on-cloud/backend

# Try to find a suitable Gradle
if [ -f "/tmp/gradle-8.12/bin/gradle" ]; then
    GRADLE="/tmp/gradle-8.12/bin/gradle"
elif [ -f "$HOME/.gradle/wrapper/dists/gradle-8.2-bin/bbg7u40eoinfdyxsxr3z4i7ta/gradle-8.2/bin/gradle" ]; then
    GRADLE="$HOME/.gradle/wrapper/dists/gradle-8.2-bin/bbg7u40eoinfdyxsxr3z4i7ta/gradle-8.2/bin/gradle"
else
    echo "Gradle not found"
    exit 1
fi

echo "Using Gradle: $GRADLE"
$GRADLE build -x test
