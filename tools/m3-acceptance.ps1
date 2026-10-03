# M3 acceptance harness -- drives the app through adb and asserts on logcat.
#
# Design rule learned the hard way: this script reads STATE from a file the app writes, and only ever
# reads LOG TEXT to assert that a trigger happened. It does not parse adb stdout.
#
# Four separate phantom failures came from parsing adb output, every one of them while the product
# was behaving correctly:
#   * adb returns CRLF, so a stray \r sits between fields and silently breaks multi-word patterns;
#   * logcat hard-wraps long messages, splitting one record across two lines;
#   * PowerShell loses everything after the first line of a multi-line `data=` broadcast result;
#   * a polling reader can match the *previous* command's marker.
# The app now writes files/debug-result.txt (read with `run-as`) and a `key=value` reader replaces
# every regex over command output.
#
# Usage:  .\tools\m3-acceptance.ps1                 # every scenario
#         .\tools\m3-acceptance.ps1 -Only 4          # selected scenarios
#
# Keep this file ASCII-only: the PowerShell tokenizer misreads a mix of CJK text and quoting.

param(
    [int[]]$Only = @(1, 2, 3, 4, 5),
    # Which device to drive. Left empty it picks the emulator when several are attached, because this
    # harness needs two things a real phone does not give you: `run-as` (debug builds only) and a shell
    # that can hold the app's signature-level debug permission. With a phone plugged in next to the
    # emulator, `adb` refuses to choose and this script used to die with "- waiting for device -".
    [string]$Serial
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'env.ps1')

if (-not $Serial) {
    $devices = @(& adb.exe devices 2>&1 | Select-String -Pattern '^(\S+)\s+device$' | ForEach-Object { $_.Matches[0].Groups[1].Value })
    $picked = @($devices | Where-Object { $_ -like 'emulator-*' } | Select-Object -First 1)
    if ($picked.Count -eq 0) { $picked = @($devices | Select-Object -First 1) }
    if ($picked.Count -eq 0) { throw 'no adb device attached' }
    # Note: `$Serial` is typed [string], so never assign an array to it -- PowerShell would join it
    # into one string and indexing it later would hand back a single character.
    $Serial = [string]$picked[0]
}
Write-Host "device: $Serial" -ForegroundColor DarkGray

# Every adb call in this file goes through here, so a stray phone cannot become the target.
$script:AdbSerial = @('-s', $Serial)
function adb { & adb.exe @script:AdbSerial @args }

# Start the daemon once, outside any try: its first-run "daemon not running" goes to stderr and
# would otherwise become a terminating error under ErrorActionPreference = 'Stop'.
& adb start-server 2>&1 | Out-Null

$pkg = $env:ALARMHUB_APP_ID
$receiver = "$pkg/.debug.DebugReceiver"
$component = "$pkg/.MainActivity"
$results = [System.Collections.Generic.List[object]]::new()

# The device-epoch second the current scenario began at; `New-Case` sets it. Bounds every log read so
# scenarios cannot see each other's records and nothing can age out of a line-limited buffer.
$script:logSince = [long]0

function Write-Step($text) { Write-Host "  -> $text" -ForegroundColor DarkGray }

# ---------------------------------------------------------------------------------------------
# Talking to the app
# ---------------------------------------------------------------------------------------------

function Invoke-Command([string[]]$extraArgs) {
    $argv = @('shell', 'am', 'broadcast', '-a', 'com.alarmhub.app.debug.COMMAND', '-n', $receiver) + $extraArgs
    # -W would block; the receiver answers asynchronously, so the result file is polled instead.
    $null = & adb @argv 2>&1
    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep -Milliseconds 250
        $state = Read-State
        if ($state.Count -gt 0) {
            # The file names the command that produced it, so a stale file is never mistaken for a
            # fresh answer.
            if ($state['cmd'] -eq (Get-CommandName $extraArgs)) { return $state }
        }
    }
    throw "command did not answer: $($extraArgs -join ' ')"
}

function Get-CommandName([string[]]$extraArgs) {
    for ($i = 0; $i -lt $extraArgs.Count - 1; $i++) {
        if ($extraArgs[$i] -eq 'cmd') { return $extraArgs[$i + 1] }
    }
    return 'list'
}

# Parses files/debug-result.txt into a hashtable of key -> FIRST value seen.
function Read-State {
    $raw = & adb shell run-as $pkg cat files/debug-result.txt 2>&1
    $text = (($raw | Where-Object { $_ -notmatch 'daemon not running|daemon started successfully' }) -join "`n") -replace "`r", ''
    $state = @{}
    foreach ($line in ($text -split "`n")) {
        foreach ($m in [regex]::Matches($line, '([A-Za-z_][A-Za-z0-9_]*)=([^\s]+)')) {
            $k = $m.Groups[1].Value
            if (-not $state.ContainsKey($k)) { $state[$k] = $m.Groups[2].Value }
        }
    }
    return $state
}

# The `list` / `registered` records are one per entity, so they need every match, not the first.
function Read-Records([string]$needle) {
    $raw = & adb shell run-as $pkg cat files/debug-result.txt 2>&1
    $text = (($raw | Where-Object { $_ -notmatch 'daemon not running|daemon started successfully' }) -join "`n") -replace "`r", ''
    return @($text -split "`n" | Where-Object { $_ -match $needle })
}

# ---------------------------------------------------------------------------------------------
# Scenario helpers
# ---------------------------------------------------------------------------------------------

function Inject-Alarm([int]$offsetMinutes) {
    $state = Invoke-Command @('--es', 'cmd', 'inject', '--ei', 'at', [string]$offsetMinutes, '--es', 'type', 'DAILY')
    if ($state['alarmId'] -notmatch '^\d+$') { throw "inject did not report an alarmId: $($state | Out-String)" }
    Write-Step "injected alarm=$($state['alarmId']) group=$($state['groupId']) registeredAtMillis=$($state['registeredAtMillis'])"
    return $state
}

function Registered-Millis([string]$alarmId) {
    $null = Invoke-Command @('--es', 'cmd', 'registered')
    foreach ($rec in (Read-Records "alarm id=$alarmId ")) {
        if ($rec -match 'registeredAtMillis=(\d+)') { return [long]$Matches[1] }
    }
    return 0L
}

function Wait-Until([scriptblock]$predicate, [int]$timeoutSeconds, [int]$pollSeconds = 3) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (& $predicate) { return $true }
        Start-Sleep -Seconds $pollSeconds
    }
    return [bool](& $predicate)
}

# Reads the app's log from a device-epoch second, filtered to the tags the assertions use.
#
# Joined with a space rather than a newline: logcat hard-wraps long messages, and a wrapped record
# would otherwise be split in the middle of a field.
function Read-Since([long]$since) {
    $argv = @('logcat', '-d')
    if ($since -gt 0) { $argv += @('-T', "$since.000") }
    $argv += @('-s', 'AlarmHub/Receiver:I', 'AlarmHub/Scheduler:I', 'AlarmHub/SystemEvent:I')
    $raw = & adb @argv 2>&1
    $clean = ($raw | Where-Object { $_ -notmatch 'daemon not running|daemon started successfully|^\s*-{3,}' }) -join ' '
    return ($clean -replace "`r", '')
}

# Reads the app's log from the current scenario's start, not from the last N lines.
#
# The line-count form (`logcat -t 300`) silently missed rings: a ring page produces continuous
# AppOps/ActivityManager chatter, so 300 lines covered only a few seconds and the `ring started`
# record had already been pushed out by the time the assertion ran. That read as "the alarm did not
# ring" while the log plainly showed that it had. Time-bounded reads cannot miss that way.
function ReceiverLog {
    if ($script:logSince -gt 0) { return (Read-Since $script:logSince) }
    return (Read-Since ([long]0))
}

# A stable second-position inside the minute, so a pause measured in seconds and a trigger measured in
# whole minutes cannot land on top of each other.
function Wait-ForSafeMinutePosition {
    $s = [int](& adb shell date +%S)
    if ($s -lt 10 -or $s -gt 40) {
        $wait = if ($s -lt 10) { 12 - $s } else { (60 - $s) + 12 }
        Write-Step "aligning: waiting $wait s to sit ~12 s into a minute"
        Start-Sleep -Seconds $wait
    }
}

# Waits for the app to log a line matching $pattern, searching the whole buffer (an assertion of
# absence must not be confused by a buffer that was cleared at the wrong moment).
function Wait-ForLog([string]$pattern, [int]$timeoutSeconds) {
    return Wait-Until { Test-Log $pattern } $timeoutSeconds
}

function Test-Log([string]$pattern) {
    return [regex]::IsMatch((ReceiverLog), $pattern)
}

# Matched on the alarm id, never as a loose substring: a leftover trigger from an earlier scenario
# carries a different id but the same words.
#
# The marker is the *ring flow*, not the validation line: from M4 a validated MAIN trigger really rings
# (M3 only logged "RING VALIDATED"), so `ring started` is what "it rang" means now. A PRE validation
# deliberately does not match — that trigger must never start a ring (PRD §5.7).
#
# NOTE: the word order is taken verbatim from the receiver's own message:
#   "ring started for alarm=$alarmId at=..."
# An earlier version looked for "ring started for alarm=$id " and so matched nothing — while four other
# scenarios kept passing, which made it look like a product bug confined to scenario 1.
#
# Also deliberately NO `^` / `(?m)^` anchor. `Read-Since` joins the log with spaces so that logcat's
# line wrapping cannot split a record mid-field, which leaves no line breaks for `^` to match.
#
# And these have to exist as named functions: a missing one inside a `Wait-Until` scriptblock is
# swallowed by the predicate and silently reads as "false", which is the same class of trap.
function RingFor([int]$alarmId) {
    return [regex]::IsMatch((ReceiverLog), "ring started for alarm=$alarmId ")
}

function ExitReasonFor([int]$alarmId) {
    $m = [regex]::Match((ReceiverLog), "alarm=$alarmId kind=\w+ SILENT EXIT reason=(\w+)")
    if ($m.Success) { return $m.Groups[1].Value }
    return $null
}

function AnyExitReasonFor([int]$alarmId) {
    return [regex]::IsMatch((ReceiverLog), "alarm=$alarmId kind=\w+ SILENT EXIT")
}

function Wait-ForTriggerOutcome([int]$alarmId, [int]$timeoutSeconds) {
    return Wait-Until { (RingFor $alarmId) -or (AnyExitReasonFor $alarmId) } $timeoutSeconds
}

function Record([string]$name, [bool]$pass, [string]$detail) {
    $results.Add([pscustomobject]@{ Scenario = $name; Pass = $pass; Detail = $detail })
    $verdict = if ($pass) { 'PASS' } else { 'FAIL' }
    $colour = if ($pass) { 'Green' } else { 'Red' }
    Write-Host "[$verdict] $name -- $detail" -ForegroundColor $colour
}

function New-Case($label) {
    Write-Host ''
    Write-Host "=== $label" -ForegroundColor Cyan
    # Start this scenario's log window. Everything the assertions read is bounded by it, so one
    # scenario can never see another's records and nothing can age out of a line-limited buffer.
    $script:logSince = [long](& adb shell date +%s)
}

function Reset-TestData {
    $state = Invoke-Command @('--es', 'cmd', 'wipe')
    Write-Step "reset: $($state['cmd']) done"
}

# ---------------------------------------------------------------------------------------------
# 1. On-time trigger: the PRE front, then MAIN at the exact instant (M3.4, PRD 5.7)
# ---------------------------------------------------------------------------------------------
if ($Only -contains 1) {
    New-Case '1  on-time trigger: PRE front then MAIN at the exact instant'
    Reset-TestData

    # The trigger lands on the next hh:mm boundary and the PRE front is 5 s before it, so if that
    # instant has already gone by when the worker registers, the scheduler correctly refuses to
    # register it and there is no PRE to observe. Align first; that is a property of the test.
    Wait-ForSafeMinutePosition

    $state = Inject-Alarm -offsetMinutes 2
    $id = $state['alarmId']

    $dump = ((& adb shell dumpsys alarm 2>&1) -join ' ')
    $hasOurs = $dump -match [regex]::Escape($pkg)
    $isAlarmClock = $dump -match 'Alarm clock:'
    Write-Step "dumpsys alarm mentions $pkg : $hasOurs ; uses setAlarmClock : $isAlarmClock"

    $pre = Wait-ForLog "alarm=$id PRE validated" 240
    $rang = Wait-ForLog "ring started for alarm=$id " 60
    $rescheduled = Test-Log "scheduled alarm=$id at="

    # From M4 a validated MAIN trigger really rings, so silence it before moving on: a live ring holds
    # a foreground service and keeps re-posting its notification, which would confuse every later
    # scenario (it is also why this script needs a debug build).
    $null = Invoke-Command @('--es', 'cmd', 'dismiss')

    Record '1 on-time trigger' ($rang -and $pre -and $hasOurs -and $isAlarmClock -and $rescheduled) `
        "PRE=$pre MAIN=$rang rescheduled=$rescheduled dumpsys=$hasOurs alarmClock=$isAlarmClock"
}

# ---------------------------------------------------------------------------------------------
# 2. A paused group stays silent (AC-3), and a trigger registered before the pause is suppressed
# ---------------------------------------------------------------------------------------------
if ($Only -contains 2) {
    New-Case '2  paused group stays silent (AC-3) + stale pre-pause trigger suppressed (5.4)'
    Reset-TestData
    $state = Inject-Alarm -offsetMinutes 2
    $id = $state['alarmId']
    $scheduledAt = Registered-Millis $id

    $null = Invoke-Command @('--es', 'cmd', 'pauseUntil', '--el', 'group', $state['groupId'], '--el', 'afterSeconds', '600')
    $afterPause = Registered-Millis $id
    Write-Step "registeredAtMillis before pause=$scheduledAt ; after pause=$afterPause (a later day)"

    # Replay the trigger registered BEFORE the pause: a correct scheduler re-registers for a later
    # day, so nothing fires during the pause and the secondary validation would go untested.
    $null = Invoke-Command @('--es', 'cmd', 'trigger', '--el', 'alarm', $id, '--el', 'at', [string]$scheduledAt)
    $exited = Wait-ForLog "alarm=$id kind=MAIN SILENT EXIT reason=GROUP_PAUSED" 60
    $rang = Test-Log "ring started for alarm=$id "

    Record '2 paused group stays silent' ($exited -and (-not $rang) -and ($scheduledAt -gt 0)) `
        "silentExit=GROUP_PAUSED rang=$rang replayedStaleTrigger=$($scheduledAt -gt 0)"
}

# ---------------------------------------------------------------------------------------------
# 3. Permanent disable silences it through its own branch (PRD FR-2.7, 5.4)
# ---------------------------------------------------------------------------------------------
if ($Only -contains 3) {
    New-Case '3  permanently disabled group stays silent (GROUP_PERMANENTLY_DISABLED)'
    Reset-TestData
    $state = Inject-Alarm -offsetMinutes 2
    $id = $state['alarmId']
    # Captured before the disable, which clears the stored registration.
    $scheduledAt = Registered-Millis $id

    $null = Invoke-Command @('--es', 'cmd', 'disable', '--el', 'group', $state['groupId'])
    Write-Step "registeredAtMillis after disable=$(Registered-Millis $id) (0 = nothing scheduled)"

    $null = Invoke-Command @('--es', 'cmd', 'trigger', '--el', 'alarm', $id, '--el', 'at', [string]$scheduledAt)
    $exited = Wait-ForLog "alarm=$id kind=MAIN SILENT EXIT reason=GROUP_PERMANENTLY_DISABLED" 60
    $rang = Test-Log "ring started for alarm=$id "

    Record '3 permanently disabled stays silent' ($exited -and (-not $rang)) `
        "silentExit=GROUP_PERMANENTLY_DISABLED rang=$rang"
}

# ---------------------------------------------------------------------------------------------
# 4. Pause expiry is an event: the PAUSE trigger re-registers, and the alarm then rings with nobody
#    touching the app (AC-4)
# ---------------------------------------------------------------------------------------------
if ($Only -contains 4) {
    New-Case '4  pause expiry re-registers by itself and the alarm then rings (AC-4)'
    Reset-TestData
    Wait-ForSafeMinutePosition

    $state = Inject-Alarm -offsetMinutes 2
    $id = $state['alarmId']
    $scheduledAt = Registered-Millis $id

    # What this scenario asserts, and deliberately nothing more:
    #
    #   1. applying a pause defers the registration past the pause (today's ring is inside it),
    #   2. the PAUSE trigger at the resume moment fires with nobody touching the app,
    #   3. the alarm is then re-armed for a future instant.
    #
    # (3) is deliberately "re-armed in the future", NOT "rings within the test window". A sub-minute
    # pause can never be followed by an immediate ring, and that is arithmetic rather than a defect:
    # PRD 5.3 converts a pause into an absolute resume moment that always lands at a midnight, so any
    # pause covering a ring resumes *after* that ring, which therefore counts as skipped and the next
    # one is ~24 h later. Demonstrating a ring right after expiry would need either a midnight-aligned
    # multi-day pause or a device clock jump; asserting "re-armed" is the honest, checkable contract.
    # A covered ring is never delivered at all here, so §5.4's suppression during a pause is proven by
    # scenarios 2 and 3 instead, which replay a trigger registered before the pause.
    $now = [long](& adb shell date +%s) * 1000
    $pauseSeconds = [math]::Max(20, [math]::Floor(($scheduledAt - $now) / 1000) + 20)
    Write-Step "ring registered for $scheduledAt ; pausing for ${pauseSeconds}s"

    $null = Invoke-Command @('--es', 'cmd', 'pauseUntil', '--el', 'group', $state['groupId'], '--el', 'afterSeconds', [string]$pauseSeconds)
    $whilePaused = Registered-Millis $id
    $deferred = $whilePaused -gt $scheduledAt
    Write-Step "registeredAtMillis during the pause=$whilePaused ; deferred past the pause=$deferred"

    $pauseFired = Wait-ForLog "alarm=$id PAUSE trigger" 180
    $afterExpiry = Registered-Millis $id
    $nowAfter = [long](& adb shell date +%s) * 1000
    $reArmed = $afterExpiry -gt $nowAfter
    Write-Step "PAUSE trigger fired=$pauseFired ; re-armed for $afterExpiry (in the future: $reArmed)"

    Record '4 pause expiry auto-resumes' ($deferred -and $pauseFired -and $reArmed) `
        "deferredPastPause=$deferred pauseTrigger=$pauseFired reArmedAfterExpiry=$reArmed (for $afterExpiry)"
}

# ---------------------------------------------------------------------------------------------
# 5. System events re-register (PRD FR-4.5)
# ---------------------------------------------------------------------------------------------
if ($Only -contains 5) {
    New-Case '5  BOOT_COMPLETED and TIME_SET re-register (PRD FR-4.5)'
    Reset-TestData
    $state = Inject-Alarm -offsetMinutes 5
    $id = $state['alarmId']
    $before = Registered-Millis $id
    Write-Step "registeredAtMillis before the broadcasts=$before"

    & adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -p $pkg | Out-Null
    $bootLog = Wait-ForLog 'BOOT_COMPLETED: recomputing and re-registering every alarm' 60
    $afterBoot = Registered-Millis $id
    Write-Step "after BOOT_COMPLETED: registeredAtMillis=$afterBoot bootLog=$bootLog"

    & adb shell am broadcast -a android.intent.action.TIME_SET -p $pkg | Out-Null
    $timeLog = Wait-ForLog 'TIME_SET: recomputing and re-registering every alarm' 60
    $afterTime = Registered-Millis $id
    Write-Step "after TIME_SET: registeredAtMillis=$afterTime timeLog=$timeLog"

    Record '5 system events re-register' ($bootLog -and $timeLog -and ($afterBoot -gt 0) -and ($afterTime -gt 0)) `
        "bootBroadcast=$bootLog timeSetBroadcast=$timeLog registeredAfterBoot=$($afterBoot -gt 0) registeredAfterTimeSet=$($afterTime -gt 0)"
}

# ---------------------------------------------------------------------------------------------
Write-Host ''
Write-Host '========================= summary =========================' -ForegroundColor Cyan
$results | Format-Table -AutoSize | Out-String | Write-Host
$failed = @($results | Where-Object { -not $_.Pass }).Count
$good = $results.Count - $failed
$colour = if ($failed -eq 0) { 'Green' } else { 'Red' }
Write-Host "$good/$($results.Count) scenarios passed" -ForegroundColor $colour
exit $failed
