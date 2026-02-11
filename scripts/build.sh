#!/bin/bash
set -e

cd /home/xenoamess/.openclaw/workspace/openclaw-on-cloud

# Install frontend dependencies
echo "Installing frontend dependencies..."
cd frontend
pnpm install
cd ..

# Build backend
echo "Building backend..."
cd backend
if [ -f "gradlew" ]; then
    ./gradlew build -x test
else
    gradle build -x test
fi
cd ..

echo "Build complete!"
