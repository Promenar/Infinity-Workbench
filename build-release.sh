#!/bin/bash
# build-release.sh - Automated Key Injection & Build Script (Bash version)

set -e  # Exit on error

SECURE_DIR="secure_env"
ANDROID_APP_DIR="android/app"
GRADLE_PROPS_FILE="android/gradle.properties"

echo -e "\033[36m🔐 Starting Secure Build Process...\033[0m"

# 0. Auto-Bump Version Disabled
# Versions are locked at 1.1.54 (63)

# 1. Check Secure Environment
if [ ! -f "$SECURE_DIR/promenar.keystore" ]; then
    echo -e "\033[31m❌ Keystore file missing in $SECURE_DIR\033[0m"
    exit 1
fi
if [ ! -f "$SECURE_DIR/secure.properties" ]; then
    echo -e "\033[31m❌ secure.properties missing in $SECURE_DIR\033[0m"
    exit 1
fi

# 2. Read Credentials
source "$SECURE_DIR/secure.properties"

if [ -z "$KEYSTORE_PASSWORD" ] || [ -z "$KEY_ALIAS" ] || [ -z "$KEY_PASSWORD" ]; then
    echo -e "\033[31m❌ Missing credentials in secure.properties. Please fill them in.\033[0m"
    exit 1
fi

# 3. Inject Keystore
echo "-> Injecting Keystore..."
cp "$SECURE_DIR/promenar.keystore" "$ANDROID_APP_DIR/promenar.keystore"

# 4. Inject Gradle Properties (Memory & Signing)
echo "-> Injecting Gradle Properties..."
INJECTION_MARKER="### INJECTED_SIGNING_CONFIG ###"

# Apply Memory Optimization (Prevents Metaspace error)
sed -i 's/org.gradle.jvmargs=.*/org.gradle.jvmargs=-Xmx4096m -XX:MaxMetaspaceSize=1024m/' "$GRADLE_PROPS_FILE"

# Remove old injection if exists
sed -i "/$INJECTION_MARKER/,/$INJECTION_MARKER/d" "$GRADLE_PROPS_FILE" 2>/dev/null || true

# Append new injection block
cat >> "$GRADLE_PROPS_FILE" << EOF

$INJECTION_MARKER
MYAPP_UPLOAD_STORE_FILE=promenar.keystore
MYAPP_UPLOAD_STORE_PASSWORD=$KEYSTORE_PASSWORD
MYAPP_UPLOAD_KEY_ALIAS=$KEY_ALIAS
MYAPP_UPLOAD_KEY_PASSWORD=$KEY_PASSWORD
$INJECTION_MARKER
EOF

echo "✅ Injection Complete. Signing config is active."

# 5. Build
echo -e "\033[36m🚀 Starting Release Build...\033[0m"
cd android
./gradlew clean assembleRelease
BUILD_EXIT_CODE=$?
cd ..

if [ $BUILD_EXIT_CODE -ne 0 ]; then
    echo -e "\033[31m❌ Gradle Build Failed with exit code $BUILD_EXIT_CODE\033[0m"
    exit $BUILD_EXIT_CODE
fi

# 6. Cleanup
echo "🧹 Cleaning up credentials..."
sed -i "/$INJECTION_MARKER/,/$INJECTION_MARKER/d" "$GRADLE_PROPS_FILE"

echo -e "\033[32m✨ Build Workflow Complete!\033[0m"
