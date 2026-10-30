#!/usr/bin/env bash
# Runs the emulator tests (app/src/androidTest). Keeps the screenshots and screen recordings of the
# gesture tests in ui-screens/ for the artifact, and prints why a test failed: the HTML report
# stays on the runner.
set -u

adb shell rm -rf /sdcard/Download/beandesk-ui
# No "isn't responding" dialogs about other apps (the launcher, for one) over the tests.
adb shell settings put global hide_error_dialogs 1
./gradlew :app:connectedDebugAndroidTest
status=$?

mkdir -p ui-screens
adb pull /sdcard/Download/beandesk-ui/. ui-screens/ || true

if [ "$status" -ne 0 ]; then
  find app/build/outputs/androidTest-results -name '*.xml' -exec grep -B2 -A60 '<failure' {} +
fi
exit "$status"
