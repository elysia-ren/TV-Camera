#!/bin/bash
# TV相机 - 编译脚本
# 用法: ./build.sh

set -e

export JAVA_HOME=$HOME/jdk/jdk-17.0.19+10
export ANDROID_HOME=$HOME/android-sdk

echo "=== TV相机 编译 ==="
echo "JAVA_HOME: $JAVA_HOME"
echo "ANDROID_HOME: $ANDROID_HOME"

./gradlew assembleDebug --no-daemon

echo ""
echo "=== 编译完成 ==="
echo "APK 路径: app/build/outputs/apk/debug/app-debug.apk"
ls -lh app/build/outputs/apk/debug/app-debug.apk
