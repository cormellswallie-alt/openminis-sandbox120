#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
mkdir -p "$ROOT/ci-output"
cd "$ROOT/src/android"

# A single JVM compiles and packages sequentially. No phone-specific AAPT override.
MEM_MB=$(awk '/MemTotal:/ { print int($2 / 1024) }' /proc/meminfo)
if (( MEM_MB >= 12000 )); then HEAP=8g; else HEAP=5g; fi
COMMON=(--console=plain --stacktrace --max-workers=2
  -PuseOfficialNativeArtifacts
  -Pkotlin.compiler.execution.strategy=in-process
  "-Dorg.gradle.jvmargs=-Xmx${HEAP} -XX:+UseParallelGC -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8")

if [[ "${CI_SIGNED:-false}" == "true" && ! -f signing/perf120.properties ]]; then
  echo 'Dedicated signing configuration is required for this run.' >&2
  exit 1
fi
./gradlew "${COMMON[@]}" :app:assembleRelease 2>&1 | tee "$ROOT/ci-output/build.log"

if [[ "${CI_RUN_TESTS:-true}" == "true" ]]; then
  FILTERS=()
  for TEST in RefreshRatePolicyTest 'ModelsDev*Test' ModelReleaseIndexTest 'Terminal*Test' \
    'Markdown*Test' LatexCodeMaskTest Review0923IncrementalParseEquivalenceTest \
    CompactLoopFramingTest OpenAICompatibilityTest OpenAIProviderTest OpenAIFamilyGatesTest \
    ResponsesToolPairingTest ResponsesApiFinishedTest ResponsesIncompletePartialTest \
    'GlobalShellThrottle*Test' ProcSnapshotTest ShellLineBufferTest \
    ShellArrayOutputTest AnsiParserEventTest 'ChatSelection*Test' GitObjectConfigPolicyTest \
    NativeOffloadProtocolTest 'Fresh*Test' ForegroundCommandGroupTest SubAgentQueueTest \
    BackgroundRuntimePolicyTest 'SubAgent*Test' 'AgentJob*Test' 'HelperRunPolicy*Test' \
    'HelperRequest*Test' 'HelperCard*Test' ThinkingAutoCollapseTest \
    Review0923SubAgentQueueInvariantsTest Review0923ViewModelStoreRealTest \
    'RequestRecovery*Test' 'CompactRecovery*Test' CompactionDecisionTest DatabaseVersionGuardTest \
    'TokenPricing*Test' 'UsagePricing*Test' ModelOverrideSaveGateTest \
    ModelOverridesExportImportRoundTripTest 'ChatStatus*Test' 'Backup*Test'; do
    FILTERS+=(--tests "*${TEST}")
  done
  ./gradlew "${COMMON[@]}" :app:testReleaseUnitTest "${FILTERS[@]}" \
    2>&1 | tee "$ROOT/ci-output/tests.log"
fi
python3 "$ROOT/scripts/ci/verify_apk.py"
