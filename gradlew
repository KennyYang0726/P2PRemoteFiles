#!/bin/sh
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P) || exit 1
JAVA_CMD=${JAVA_HOME:+$JAVA_HOME/bin/}java
if ! command -v "$JAVA_CMD" >/dev/null 2>&1 && [ ! -x "$JAVA_CMD" ]; then
  echo "找不到Java。請安裝JDK 17以上版本，或設定JAVA_HOME。" >&2
  exit 1
fi
exec "$JAVA_CMD" -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
