#!/bin/sh
set -eu
GRADLE_LIB=${GRADLE_LIB:-/opt/gradle-8.11.1/lib}
BENCH_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_ROOT=$(CDPATH= cd -- "$BENCH_DIR/../.." && pwd)
TASK_OUT=$(mktemp -d "${TMPDIR:-/tmp}/minis-preview-check.XXXXXX")
trap 'rm -rf "$TASK_OUT"' EXIT HUP INT TERM
java -cp "$GRADLE_LIB/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect \
  -classpath "$GRADLE_LIB/kotlin-stdlib-2.0.20.jar:$GRADLE_LIB/junit-4.13.2.jar:$GRADLE_LIB/kotlinx-coroutines-core-jvm-1.6.4.jar" \
  -d "$TASK_OUT/tests.jar" \
  "$PROJECT_ROOT/src/android/app/src/main/java/com/openminis/app/ui/chat/ShellOutputPreview.kt" \
  "$PROJECT_ROOT/src/android/app/src/test/java/com/openminis/app/ui/chat/ShellOutputPreviewTest.kt" \
  "$BENCH_DIR/ShellPreviewBenchmark.kt"
java -cp "$TASK_OUT/tests.jar:$GRADLE_LIB/*" org.junit.runner.JUnitCore \
  com.openminis.app.ui.chat.ShellOutputPreviewTest
java -Xms256m -Xmx768m -cp "$TASK_OUT/tests.jar:$GRADLE_LIB/*" \
  com.openminis.app.ui.chat.ShellPreviewBenchmark
