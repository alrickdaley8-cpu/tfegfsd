#!/bin/sh
#
# Doomsday Nukes — Gradle bootstrap launcher.
#
# This is a *functional stand-in* for the official Gradle wrapper script.
# The official `gradlew` requires the binary `gradle/wrapper/gradle-wrapper.jar`,
# which cannot be committed as text in this source tree. This shim therefore:
#
#   1. uses the real wrapper jar if you have generated one
#      (run `gradle wrapper --gradle-version 8.8` once, then this becomes a
#       plain pass-through to the standard wrapper);
#   2. otherwise uses a `gradle` already on PATH;
#   3. otherwise downloads the Gradle distribution declared in
#      gradle/wrapper/gradle-wrapper.properties into ./.gradle-dist and runs it.
#
# Everything after that is exactly `gradle <args>`, so `./gradlew runClient`,
# `./gradlew build`, `./gradlew checkAssets` behave normally.

set -e

APP_HOME=$(cd "$(dirname "$0")" && pwd)
PROPS="$APP_HOME/gradle/wrapper/gradle-wrapper.properties"
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if ! command -v java >/dev/null 2>&1; then
	echo "ERROR: java not found on PATH. Doomsday Nukes requires a JDK 21+." >&2
	exit 1
fi

# 1 — real wrapper present?
if [ -f "$WRAPPER_JAR" ]; then
	exec java -Xmx64m -Xms64m \
		-Dorg.gradle.appname=gradlew \
		-classpath "$WRAPPER_JAR" \
		org.gradle.wrapper.GradleWrapperMain "$@"
fi

# 2 — bootstrap the pinned distribution
#
# This comes BEFORE falling back to a gradle on PATH on purpose. The pinned version in
# gradle-wrapper.properties is the one this build script and the Loom plugin version were
# exercised against; a system Gradle of a different major (the CI images and dev distros ship
# anything from 7.x to 9.x) failing the build is a much worse outcome than one download.
# The PATH fallback stays as step 3 for the offline case, where the download would fail anyway.
DIST_URL=$(sed -n 's/^distributionUrl=//p' "$PROPS" | sed 's/\\//g')
if [ -z "$DIST_URL" ]; then
	echo "ERROR: no distributionUrl in $PROPS" >&2
	exit 1
fi
DIST_ZIP=$(basename "$DIST_URL")
DIST_NAME=$(echo "$DIST_ZIP" | sed 's/-bin\.zip$//;s/-all\.zip$//')
DIST_HOME="$APP_HOME/.gradle-dist/$DIST_NAME"

if [ ! -x "$DIST_HOME/bin/gradle" ]; then
	mkdir -p "$APP_HOME/.gradle-dist"
	echo "Bootstrapping $DIST_NAME (one time) ..."
	if command -v curl >/dev/null 2>&1; then
		curl -fsSL -o "$APP_HOME/.gradle-dist/$DIST_ZIP" "$DIST_URL"
	elif command -v wget >/dev/null 2>&1; then
		wget -q -O "$APP_HOME/.gradle-dist/$DIST_ZIP" "$DIST_URL"
	else
		echo "ERROR: neither curl nor wget available to download Gradle." >&2
		exit 1
	fi
	unzip -q -o "$APP_HOME/.gradle-dist/$DIST_ZIP" -d "$APP_HOME/.gradle-dist"
	rm -f "$APP_HOME/.gradle-dist/$DIST_ZIP"
fi

if [ ! -x "$DIST_HOME/bin/gradle" ]; then
	# 3 — last resort: whatever gradle the machine already has. Only reached when the download above
	# was impossible (offline, no curl/wget), which is exactly when a local Gradle is a kindness.
	if command -v gradle >/dev/null 2>&1; then
		exec gradle "$@"
	fi
	echo "ERROR: could not provision Gradle at $DIST_HOME and no gradle is on PATH." >&2
	exit 1
fi

exec "$DIST_HOME/bin/gradle" "$@"
