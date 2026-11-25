$ErrorActionPreference = 'Stop'
& .\gradlew.bat :app:installDebug :app:installDebugAndroidTest
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
$instrumentationOutput = & adb shell am instrument -w -r `
    -e translation_web_live_smoke true `
    -e class com.tyranor.next.core.translation.service.protocol.machine.TranslationWebProviderLiveSmokeDeviceTest `
    com.tyranor.next.test/androidx.test.runner.AndroidJUnitRunner 2>&1
$instrumentationExitCode = $LASTEXITCODE
$instrumentationOutput | ForEach-Object { Write-Output $_ }
$instrumentationText = $instrumentationOutput -join "`n"
if ($instrumentationExitCode -ne 0 -or
    $instrumentationText -match '(?m)^FAILURES!!!$|^There were \d+ failures:|^INSTRUMENTATION_STATUS_CODE: -2$|^INSTRUMENTATION_RESULT: shortMsg=') {
    exit 1
}
exit 0
