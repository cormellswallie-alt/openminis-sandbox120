#!/bin/sh
set -eu
# Override GRADLE_LIB to an existing Gradle distribution's lib directory.
GRADLE_LIB=${GRADLE_LIB:-/opt/gradle-8.11.1/lib}
BENCH_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_ROOT=$(CDPATH= cd -- "$BENCH_DIR/../.." && pwd)
MAIN_DIR="$PROJECT_ROOT/src/android/app/src/main/java/com/openminis/app/sandbox"
TEST_DIR="$PROJECT_ROOT/src/android/app/src/test/java/com/openminis/app/sandbox"
TASK_OUT=$(mktemp -d "${TMPDIR:-/tmp}/minis-output-check.XXXXXX")
trap 'rm -rf "$TASK_OUT"' EXIT HUP INT TERM
java -cp "$GRADLE_LIB/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect \
  -classpath "$GRADLE_LIB/kotlin-stdlib-2.0.20.jar:$GRADLE_LIB/junit-4.13.2.jar" \
  -d "$TASK_OUT/tests.jar" \
  "$MAIN_DIR/ShellLineBuffer.kt" "$MAIN_DIR/ProcSnapshot.kt" \
  "$TEST_DIR/ShellArrayOutputTest.kt" "$BENCH_DIR/ShellOutputBenchmark.kt" \
  "$TEST_DIR/ShellLineBufferTest.kt" "$TEST_DIR/ProcSnapshotTest.kt"
java -cp "$TASK_OUT/tests.jar:$GRADLE_LIB/*" org.junit.runner.JUnitCore \
  com.openminis.app.sandbox.ShellArrayOutputTest \
  com.openminis.app.sandbox.ShellLineBufferTest \
  com.openminis.app.sandbox.ProcSnapshotTest
java -Xms256m -Xmx768m -cp "$TASK_OUT/tests.jar:$GRADLE_LIB/*" \
  com.openminis.app.sandbox.ShellOutputBenchmark
