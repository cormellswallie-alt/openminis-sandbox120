#!/bin/sh
set -eu
GRADLE_LIB=${GRADLE_LIB:-/opt/gradle-8.11.1/lib}
BENCH_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$BENCH_DIR/../.." && pwd)
MAIN=${TERMINAL_SOURCE_DIR:-$ROOT/src/android/app/src/main/java/com/openminis/app/ui/terminal/emulator}
OUT=$(mktemp -d /tmp/minis-terminal-bench.XXXXXX)
trap 'rm -rf "$OUT"' EXIT HUP INT TERM
cat > "$OUT/State.kt" <<'EOF'
package androidx.compose.runtime
interface State<T> { val value: T }
class MutableState<T>(override var value: T): State<T>
fun <T> mutableStateOf(value: T) = MutableState(value)
EOF
cat > "$OUT/Color.kt" <<'EOF'
package androidx.compose.ui.graphics
class Color(val r: Int, val g: Int, val b: Int) { companion object { val Black = Color(0,0,0) } }
EOF
cat > "$OUT/Broker.kt" <<'EOF'
package com.openminis.app.terminal
object MinisOpenUrlBroker { val urls = mutableListOf<String>(); fun offer(url: String) { urls.add(url) } }
EOF
java -cp "$GRADLE_LIB/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath "$GRADLE_LIB/kotlin-stdlib-2.0.20.jar:$GRADLE_LIB/junit-4.13.2.jar" -d "$OUT/tests.jar" "$OUT/State.kt" "$OUT/Color.kt" "$OUT/Broker.kt" "$MAIN/AnsiParser.kt" "$MAIN/TerminalTypes.kt" "$MAIN/TerminalBuffer.kt" "$MAIN/TerminalEmulator.kt" "$BENCH_DIR/TerminalFeedBenchmark.kt" "$ROOT/src/android/app/src/test/java/com/openminis/app/ui/terminal/emulator/TerminalPerformanceTest.kt" "$ROOT/src/android/app/src/test/java/com/openminis/app/ui/terminal/emulator/TerminalRobustnessTest.kt" "$ROOT/src/android/app/src/test/java/com/openminis/app/ui/terminal/emulator/TerminalSliceInputTest.kt"
java -cp "$OUT/tests.jar:$GRADLE_LIB/*" org.junit.runner.JUnitCore com.openminis.app.ui.terminal.emulator.TerminalPerformanceTest com.openminis.app.ui.terminal.emulator.TerminalRobustnessTest com.openminis.app.ui.terminal.emulator.TerminalSliceInputTest
java -Xms256m -Xmx768m -cp "$OUT/tests.jar:$GRADLE_LIB/*" com.openminis.app.ui.terminal.emulator.TerminalFeedBenchmark
