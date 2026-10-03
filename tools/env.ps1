# AlarmHub — per-session environment for Android builds and adb.
#
# Usage (each pwsh invocation is a fresh process, so dot-source it every time):
#     . .\tools\env.ps1

$root = Split-Path -Parent $PSScriptRoot

$env:JAVA_HOME           = 'D:\DevEnv\Java\jdk-21'
$env:ANDROID_HOME        = 'D:\DevEnv\Android\Sdk'
$env:ANDROID_SDK_ROOT    = $env:ANDROID_HOME
$env:ANDROID_AVD_HOME    = 'D:\DevEnv\Android\avd'

# Read-only shared Maven dependency cache: Gradle only ever reads from it.
$env:GRADLE_RO_DEP_CACHE = 'D:\DevEnv\GradleCache'

# Keep every writable cache inside the repository, so nothing leaks into the user profile.
$env:GRADLE_USER_HOME    = Join-Path $root '.gradle-home'
$env:npm_config_cache    = Join-Path $root '.npm-cache'

$env:PATH = @(
    'D:\DevEnv\gradle-8.14.3\bin'
    Join-Path $env:JAVA_HOME 'bin'
    Join-Path $env:ANDROID_HOME 'platform-tools'
    Join-Path $env:ANDROID_HOME 'emulator'
    Join-Path $env:ANDROID_HOME 'cmdline-tools\latest\bin'
    $env:PATH
) -join ';'

$env:ALARMHUB_ROOT    = $root
$env:ALARMHUB_APP_ID  = 'com.alarmhub.app'
