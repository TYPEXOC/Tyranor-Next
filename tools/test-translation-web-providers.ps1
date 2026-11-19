$ErrorActionPreference = 'Stop'
& .\gradlew.bat :app:installDebug :app:installDebugAndroidTest
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
& adb shell am instrument -w -r `
    -e translation_web_live_smoke true `
    -e class com.tyranor.next.core.translation.service.protocol.machine.TranslationWebProviderLiveSmokeDeviceTest `
    com.tyranor.next.test/androidx.test.runner.AndroidJUnitRunner
exit $LASTEXITCODE
