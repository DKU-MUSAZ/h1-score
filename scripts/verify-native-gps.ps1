param([ValidateRange(10,180)][int]$Seconds=30,[switch]$RequestRoute,[switch]$CancelRouteOnFinish)
# Sends checksum-correct NMEA through the emulator; never replaces navigation callbacks.
$ErrorActionPreference='Stop'
$adb="$env:ANDROID_HOME/platform-tools/adb.exe"
$repo=Split-Path $PSScriptRoot -Parent
$log=Join-Path $repo "android/app/build/native-gps-$(Get-Date -Format yyyyMMdd-HHmmss).log"
$arguments=@('-s','emulator-5554','shell','am','instrument','--user','10','-w','-e','liveGps','true','-e','class','ai.musaz.h1score.NavigationGpsIntegrationTest','-e','seconds',$Seconds)
if($RequestRoute){$arguments+=@('-e','requestRoute','true')}
if($CancelRouteOnFinish){$arguments+=@('-e','cancelRouteOnFinish','true')}
$arguments+='ai.musaz.h1score.test/androidx.test.runner.AndroidJUnitRunner'
$probe=Start-Process $adb -WindowStyle Hidden -PassThru -RedirectStandardOutput $log -RedirectStandardError "$log.err" -ArgumentList $arguments
Write-Output "Log: $log"
$timer=[Diagnostics.Stopwatch]::StartNew()
$client=[Net.Sockets.TcpClient]::new('127.0.0.1',5554)
$stream=$client.GetStream(); $stream.ReadTimeout=3000
$reader=[IO.StreamReader]::new($stream,[Text.Encoding]::ASCII)
$writer=[IO.StreamWriter]::new($stream,[Text.Encoding]::ASCII)
$writer.NewLine="`n"; $writer.AutoFlush=$true
function Read-Reply {
    $lines=@()
    do {$line=$reader.ReadLine();$lines+=$line}while($null-ne $line -and $line -ne 'OK' -and $line -notlike 'KO:*')
    return ($lines -join "`n")
}
$null=Read-Reply
$token=(Get-Content "$env:USERPROFILE/.emulator_console_auth_token" -Raw).Trim()
$writer.WriteLine('auth '+$token)
if((Read-Reply) -notmatch '(?m)^OK$'){throw 'Emulator console authentication failed'}
try {
while(-not $probe.HasExited -and $timer.Elapsed.TotalSeconds -lt ($Seconds+30)) {
    $utc=[DateTime]::UtcNow
    $clock=$utc.ToString('HHmmss.fff')
    $date=$utc.ToString('ddMMyy')
    foreach($body in @("GPGGA,$clock,3729.8860,N,12703.7860,E,1,10,0.5,30.0,M,0.0,M,,","GPRMC,$clock,A,3729.8860,N,12703.7860,E,16.20,0.00,$date,0.0,W")) {
        $checksum=0
        foreach($c in $body.ToCharArray()){$checksum=$checksum -bxor [int]$c}
        $sentence='$'+$body+'*'+$checksum.ToString('X2')
        $writer.WriteLine('geo nmea '+$sentence)
        if((Read-Reply) -notmatch '(?m)^OK$'){throw 'GPS send failed'}
    }
    Start-Sleep -Milliseconds 250
}
} finally {$writer.Dispose();$reader.Dispose();$client.Dispose()}
if(-not $probe.HasExited){$probe.Kill();throw 'GPS diagnostic timed out'}
$text=Get-Content -LiteralPath $log -Raw
Get-Content -LiteralPath $log -Tail 8
if(-not $text.Contains('OK (1 test)')){throw 'GPS diagnostic failed'}
