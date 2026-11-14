param([string]$Adb="$env:ANDROID_HOME/platform-tools/adb.exe",
    [ValidateSet('all','lanes','live-inputs','left-match','right-match','unused','opposite','late','hazard','missing-gps','auto-cancel','hazard-during','curve-no-nav','poor-gps')][string]$Case='all',
    [ValidateRange(1,10)][int]$Repetitions=1,
    [ValidateRange(0,10000)][int]$CommandDelayMs=0,
    [switch]$DelayFirstSteeringOnly,
    [ValidateRange(5,60)][int]$CommandTimeoutSeconds=30)
# Hybrid: real console motion/steering/signal/hazard; GPS and navigation are fixtures in the test.
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$base='http://127.0.0.1:8088'
$log=Join-Path $repo "android/app/build/turn-console-$Case-$(Get-Date -Format 'yyyyMMdd-HHmmss').log"
Write-Output "Log: $log"
& $Adb -s emulator-5554 forward tcp:8088 tcp:8088
if($LASTEXITCODE-ne 0){throw 'Emulator unavailable'}
$before=Invoke-RestMethod "$base/vehicle/state"
function Pulse { Invoke-RestMethod -Method Post "$base/browser/session?client=turn-replay" | Out-Null }
function Set-Console([string]$path){Invoke-RestMethod -Method Put "$base$path" | Out-Null}
try {
    Pulse
    Set-Console '/vehicle/hvac/mode?mode=subscribe_hvac_base'
    Set-Console '/vehicle/gear?gear=Drive'
    Set-Console '/vehicle/controls?speed=30&accelerator=0&brake=0&steering=0'
    $testClass=if($Case -eq 'live-inputs'){'ai.musaz.h1score.TurnSdkProbeTest'}else{'ai.musaz.h1score.TurnConsoleReplayTest'}
    $probe=Start-Process $Adb -WindowStyle Hidden -PassThru -RedirectStandardOutput $log -RedirectStandardError "$log.err" -ArgumentList @(
        '-s','emulator-5554','shell','am','instrument','--user','10','-w','-e','case',$Case,'-e','repetitions',$Repetitions,'-e','commandTimeoutSeconds',$CommandTimeoutSeconds,'-e','probeSeconds','60','-e','class',
        $testClass,'ai.musaz.h1score.test/androidx.test.runner.AndroidJUnitRunner')
    $clock=[Diagnostics.Stopwatch]::StartNew()
    $seen=0
    $lastAngle='0'
    while(-not $probe.HasExited) {
        Pulse
        $lines=@(Get-Content -LiteralPath $log)
        for($i=$seen;$i-lt $lines.Count;$i++) {
            if($lines[$i] -match 'console-command=([^,]+),(-?\d+),(true|false)') {
                $mode=$Matches[1];$angle=$Matches[2];$hazard=$Matches[3]
                if($CommandDelayMs -gt 0 -and $angle -ne '0' -and (-not $DelayFirstSteeringOnly -or $lastAngle -eq '0')) {
                    $delayClock=[Diagnostics.Stopwatch]::StartNew()
                    while($delayClock.ElapsedMilliseconds -lt $CommandDelayMs){Pulse;Start-Sleep -Milliseconds 150}
                }
                Set-Console "/vehicle/turn-signal?mode=$mode"
                Set-Console "/vehicle/lights?feature=hazard&value=$hazard"
                Set-Console "/vehicle/controls?speed=30&accelerator=0&brake=0&steering=$angle"
                $lastAngle=$angle
            }
            if($lines[$i] -match 'hybrid-result=|hybrid-scenario=|lane-start-evidence=|command-ready=|turn-probe=|actual-input-summary=') {Write-Output $lines[$i]}
        }
        $seen=$lines.Count
        if($clock.Elapsed.TotalSeconds-gt (300+(30+4*$CommandTimeoutSeconds)*$Repetitions)){$probe.Kill();throw 'Console replay timeout'}
        Start-Sleep -Milliseconds 150
    }
    $text=Get-Content -LiteralPath $log -Raw
    Get-Content -LiteralPath $log -Tail 8
    if(-not $text.Contains('OK (1 test)')){throw 'Console replay failed; inspect log'}
} finally {
    Pulse
    Set-Console '/vehicle/controls?speed=0&accelerator=0&brake=0'
    Set-Console '/vehicle/gear?gear=Park'
    Set-Console "/vehicle/controls?steering=$($before.controls.steering_deg)"
    Set-Console "/vehicle/turn-signal?mode=$($before.lights.turn_signal_mode)"
    Set-Console "/vehicle/lights?feature=hazard&value=$($before.lights.hazard_on.ToString().ToLowerInvariant())"
    Invoke-RestMethod -Method Post "$base/browser/session?client=turn-replay&closing=1" | Out-Null
}
