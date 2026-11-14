param(
    [string]$Adb = "$env:ANDROID_HOME/platform-tools/adb.exe",
    [string]$Serial = 'emulator-5554',
    [string]$AndroidUser = '10'
)
# Requires the current app and SeatBeltSdkProbeTest APK already installed.
# Uses the real Pleos console and SDK; never substitutes application telemetry.
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$output = Join-Path $repo 'android/app/build/seat-console-matrix'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$base = 'http://127.0.0.1:8088'
function Keep-Console { Invoke-RestMethod -Method Post "$base/browser/session?client=seat-matrix" | Out-Null }
function Set-Console([string]$Path) { Invoke-RestMethod -Method Put "$base$Path" | Out-Null }
& $Adb -s $Serial forward tcp:8088 tcp:8088
if ($LASTEXITCODE -ne 0) { throw 'Cannot connect to emulator' }
$before = Invoke-RestMethod "$base/vehicle/state"
$driver = $before.seat.occupied.driver.ToString().ToLowerInvariant()
$passenger = $before.seat.occupied.passenger.ToString().ToLowerInvariant()
$warning = $before.telltales.actual.SeatBelt.state
if ($warning -notin @('Off','On','Blink')) { $warning='Off' }
$color = $before.telltales.actual.SeatBelt.color
if (-not $color) { $color='Red' }
$cases = @(
    @{Name='park-empty-off';Gear='Park';Speed=0;Occupied='false';Warning='Off'},
    @{Name='drive-stopped-occupied-on';Gear='Drive';Speed=0;Occupied='true';Warning='On'},
    @{Name='reverse-stopped-occupied-blink';Gear='Reverse';Speed=0;Occupied='true';Warning='Blink'},
    @{Name='drive-moving-empty-off';Gear='Drive';Speed=10;Occupied='false';Warning='Off'},
    @{Name='drive-moving-occupied-off';Gear='Drive';Speed=10;Occupied='true';Warning='Off'},
    @{Name='drive-moving-occupied-on';Gear='Drive';Speed=10;Occupied='true';Warning='On'},
    @{Name='drive-moving-occupied-blink';Gear='Drive';Speed=10;Occupied='true';Warning='Blink'},
    @{Name='reverse-moving-empty-off';Gear='Reverse';Speed=10;Occupied='false';Warning='Off'},
    @{Name='reverse-moving-occupied-off';Gear='Reverse';Speed=10;Occupied='true';Warning='Off'}
)
$results=@()
try {
    foreach ($case in $cases) {
        Keep-Console
        Set-Console '/vehicle/hvac/mode?mode=subscribe_hvac_base'
        Set-Console '/vehicle/controls?speed=0&accelerator=0&brake=0'
        Set-Console '/vehicle/gear?gear=Park'
        Set-Console '/vehicle/seat?kind=occupied&area=driver&value=true'
        Set-Console "/vehicle/seat?kind=occupied&area=passenger&value=$($case.Occupied)"
        Set-Console "/vehicle/telltales/simulator?type=SeatBelt&state=$($case.Warning)&color=Red"
        Set-Console "/vehicle/gear?gear=$($case.Gear)"
        Set-Console "/vehicle/controls?speed=$($case.Speed)&accelerator=0&brake=0"
        $expected = if ($case.Speed -eq 0) {'SAFE'} else {'DISQUALIFIED'}
        $failedSeat = if ($case.Speed -eq 0) {'NONE'} else {'DRIVER'}
        $log=Join-Path $output "$($case.Name).log"
        $arguments=@('-s',$Serial,'shell','am','instrument','--user',$AndroidUser,'-w',
            '-e','class','ai.musaz.h1score.SeatBeltSdkProbeTest',
            '-e','expectedBelt',$expected,'-e','expectedDriver','false',
            '-e','expectedPassenger','false','-e','expectedOccupied',$case.Occupied,
            '-e','expectedSeat',$failedSeat,'ai.musaz.h1score.test/androidx.test.runner.AndroidJUnitRunner')
        $probe=Start-Process $Adb -WindowStyle Hidden -PassThru -RedirectStandardOutput $log -RedirectStandardError "$log.err" -ArgumentList $arguments
        $timer=[Diagnostics.Stopwatch]::StartNew()
        while (-not $probe.HasExited) {
            Keep-Console
            if ($timer.Elapsed.TotalSeconds -gt 60) { $probe.Kill(); throw "Probe timeout: $($case.Name)" }
            Start-Sleep -Milliseconds 500
        }
        $text=Get-Content -LiteralPath $log -Raw
        $signal=($text -split '\r?\n' | Where-Object {$_ -like '*belt-sdk=*'}) -join ''
        $passed=$text.Contains('OK (1 test)')
        $results += [pscustomobject]@{Case=$case.Name;Passed=$passed;Signal=$signal}
        Write-Output "$($case.Name): passed=$passed $signal"
        if (-not $passed) { throw "Unexpected actual SDK result; inspect $log" }
    }
} finally {
    Keep-Console
    Set-Console '/vehicle/controls?speed=0&accelerator=0&brake=0'
    Set-Console '/vehicle/gear?gear=Park'
    Set-Console "/vehicle/seat?kind=occupied&area=driver&value=$driver"
    Set-Console "/vehicle/seat?kind=occupied&area=passenger&value=$passenger"
    Set-Console "/vehicle/telltales/simulator?type=SeatBelt&state=$warning&color=$color"
    $results | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $output 'results.json')
    Invoke-RestMethod -Method Post "$base/browser/session?client=seat-matrix&closing=1" | Out-Null
}
