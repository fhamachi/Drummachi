#!/bin/bash
# Setup toolchain + build APK do Drummachi (ambiente arm64/PRoot)
set -x
export DEBIAN_FRONTEND=noninteractive

echo "=== [1/5] Instalando JDK 17 ==="
apt-get update -qq
apt-get install -y -qq openjdk-17-jdk-headless wget unzip >/dev/null 2>&1

export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
if [ ! -d "$JAVA_HOME" ]; then
  JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java))))
fi
export PATH=$JAVA_HOME/bin:$PATH
java -version 2>&1

echo "=== [2/5] Gradle 8.7 ==="
if [ ! -d /opt/gradle-8.7 ]; then
  cd /opt
  wget -q https://services.gradle.org/distributions/gradle-8.7-bin.zip
  unzip -q gradle-8.7-bin.zip
  rm -f gradle-8.7-bin.zip
fi
export PATH=/opt/gradle-8.7/bin:$PATH
gradle --version 2>&1 | head -8

echo "=== [3/5] Android SDK (cmdline-tools + platform 34 + build-tools 34.0.0) ==="
export ANDROID_HOME=/opt/android-sdk
mkdir -p $ANDROID_HOME/cmdline-tools
if [ ! -d $ANDROID_HOME/cmdline-tools/latest ]; then
  cd /opt
  wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -q commandlinetools-linux-11076708_latest.zip -d $ANDROID_HOME/cmdline-tools
  mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest
  rm -f commandlinetools-linux-11076708_latest.zip
fi
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --sdk_root=$ANDROID_HOME --licenses >/dev/null 2>&1
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --sdk_root=$ANDROID_HOME "platforms;android-34" "build-tools;34.0.0" 2>&1 | tail -5

echo "=== [3.5/5] Testando aapt2 nativo ==="
$ANDROID_HOME/build-tools/34.0.0/aapt2 version 2>&1 || echo "AAPT2_FALHOU_NEED_ARM64"

echo "=== [4/5] Gradle wrapper + assembleDebug ==="
cd /home/ubuntu/.openclaw/workspace/drummachi/DrumMachine
gradle wrapper --gradle-version 8.7 2>&1 | tail -3
gradle assembleDebug --no-daemon 2>&1 | tail -40

echo "=== [5/5] Resultado ==="
ls -la app/build/outputs/apk/debug/ 2>/dev/null
echo "BUILD_FIM"
