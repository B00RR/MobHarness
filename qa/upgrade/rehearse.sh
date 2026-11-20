#!/usr/bin/env bash
set -euo pipefail
: "${GITHUB_ACTIONS:?CI only}" "${RUNNER_TEMP:?}"
serial=$(adb -e get-serialno)
[[ $serial == emulator-* && $(adb -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]]
probe() {
  local root=$1 phase=$2 pkg=com.aistudio.mobharn.kx7vqp
  adb -s "$serial" install -r "$root/app/build/outputs/apk/debug/app-debug.apk"
  adb -s "$serial" install -r "$root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
  adb -s "$serial" shell am instrument -w -e phase "$phase" -e class com.example.UpgradeProbe "$pkg.test/androidx.test.runner.AndroidJUnitRunner" | tee "$RUNNER_TEMP/$phase.log"
  grep -Fq 'OK (1 test)' "$RUNNER_TEMP/$phase.log"
}
probe "$RUNNER_TEMP/old" seed
probe "$PWD" check
