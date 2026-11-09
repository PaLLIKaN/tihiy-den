param(
    [ValidateSet('apk', 'check', 'device-test')]
    [string]$Task = 'apk'
)

$ErrorActionPreference = 'Stop'
$projectPath = $PSScriptRoot
$mappedDrive = $null
$previousGradleHome = $env:GRADLE_USER_HOME
$buildExitCode = 1

# Android/Gradle Java worker argument files can fail on Windows with Cyrillic paths.
# A temporary drive name keeps the actual project in its original directory.
try {
    $buildPath = $projectPath
    if ($projectPath -match '[^\x00-\x7F]') {
        foreach ($letter in @('Z', 'Y', 'X', 'W', 'V', 'U', 'T', 'S', 'R')) {
            if (-not (Test-Path -LiteralPath "${letter}:\")) {
                & subst.exe "${letter}:" $projectPath
                if ($LASTEXITCODE -ne 0) { throw 'Не удалось создать временный путь к проекту.' }
                $mappedDrive = "${letter}:"
                $buildPath = "${letter}:\"
                break
            }
        }
        if (-not $mappedDrive) { throw 'Нет свободной буквы диска. Перенесите проект в папку с латинским именем.' }
    }
    $env:GRADLE_USER_HOME = Join-Path $buildPath '.tools\gradle-home'
    $gradleTasks = switch ($Task) {
        'apk' { @(':app:assembleDebug') }
        'check' { @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebugAndroidTest') }
        'device-test' { @(':app:connectedDebugAndroidTest') }
    }
    Push-Location $buildPath
    try {
        & (Join-Path $buildPath 'gradlew.bat') -p $buildPath @gradleTasks --no-daemon --console=plain
        $buildExitCode = $LASTEXITCODE
    } finally { Pop-Location }
} finally {
    $env:GRADLE_USER_HOME = $previousGradleHome
    if ($mappedDrive) { & subst.exe $mappedDrive /D }
}
exit $buildExitCode
