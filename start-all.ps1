param([switch]$U)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$launcherClock = [System.Diagnostics.Stopwatch]::StartNew()
$controllerRoot = Join-Path $PSScriptRoot 'controller-application'
$backendRoot = Join-Path $controllerRoot 'backend'
$videoRoot = Join-Path $PSScriptRoot 'video-processing-service'
$backendPom = Join-Path $backendRoot 'pom.xml'
$backendScript = Join-Path $controllerRoot 'start-backend.ps1'
$videoScript = Join-Path $videoRoot 'start-server.ps1'
$desktopScript = Join-Path $controllerRoot 'start-desktop.ps1'
$powershellExe = (Get-Command powershell.exe -ErrorAction Stop).Source
$children = [System.Collections.Generic.List[object]]::new()

function Write-Status([string]$Message) {
    $timestamp = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss.fff')
    $elapsed = $launcherClock.Elapsed.TotalSeconds.ToString('0.000', [System.Globalization.CultureInfo]::InvariantCulture)
    [Console]::Out.WriteLine("[$timestamp +${elapsed}s] $Message")
}

function Wait-ForService([string]$Name, [string]$Url, [int]$ExpectedStatus,
                         [System.Diagnostics.Process]$Process, [string]$ExitMarker, [string]$ErrorLog) {
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    while ([DateTime]::UtcNow -lt $deadline) {
        $Process.Refresh()
        if ($Process.HasExited) {
            throw "$Name exited before it was ready (code $(Get-ModuleExitCode $ExitMarker)). See $ErrorLog"
        }
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 2
            if ([int]$response.StatusCode -eq $ExpectedStatus) { return }
        } catch [System.Net.WebException] {
            if ($_.Exception.Response -and
                [int]$_.Exception.Response.StatusCode -eq $ExpectedStatus) { return }
        }
        Start-Sleep -Milliseconds 300
    }
    throw "$Name did not become ready within 60 seconds. See $ErrorLog"
}

function Get-ModuleExitCode([string]$ExitMarker) {
    if (-not (Test-Path -LiteralPath $ExitMarker -PathType Leaf)) { return 'unknown' }
    return (Get-Content -LiteralPath $ExitMarker -Raw).Trim()
}

function Wait-ForDesktop([System.Diagnostics.Process]$Process, [string]$OutputLog,
                         [string]$ExitMarker, [string]$ErrorLog) {
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    while ([DateTime]::UtcNow -lt $deadline) {
        $Process.Refresh()
        if ($Process.HasExited) {
            throw "Desktop exited before it was ready (code $(Get-ModuleExitCode $ExitMarker)). See $ErrorLog"
        }
        if ((Test-Path -LiteralPath $OutputLog -PathType Leaf) -and
            (Select-String -LiteralPath $OutputLog -SimpleMatch 'Project YCG desktop window is ready.' -Quiet)) { return }
        Start-Sleep -Milliseconds 300
    }
    throw "Desktop did not become ready within 60 seconds. See $ErrorLog"
}

function Start-Module([string]$Name, [string]$Script, [string]$Directory,
                      [string]$LogDirectory) {
    $stdout = Join-Path $LogDirectory "$Name.out.log"
    $stderr = Join-Path $LogDirectory "$Name.err.log"
    $exitMarker = Join-Path $LogDirectory "$Name.exit.txt"
    $command = "-NoProfile -ExecutionPolicy Bypass -File `"$moduleWrapperScript`" -TargetScript `"$Script`" -ExitMarker `"$exitMarker`""
    $process = Start-Process -FilePath $powershellExe -ArgumentList $command -WorkingDirectory $Directory -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $children.Add([pscustomobject]@{ Name = $Name; Process = $process; ExitMarker = $exitMarker; ErrorLog = $stderr })
    return $process
}

Write-Status 'Checking prerequisites...'
$listeningPorts = [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
foreach ($port in @(8090, 8080)) {
    if ($listeningPorts.Port -contains $port) {
        throw "Port $port is already in use. Stop the existing service before running start-all.ps1."
    }
}
if (-not (Test-Path -LiteralPath (Join-Path $videoRoot '.venv\Scripts\python.exe') -PathType Leaf)) {
    throw 'Python service environment is missing. Set up video-processing-service/.venv first.'
}
if (-not (Test-Path -LiteralPath (Join-Path $env:LOCALAPPDATA 'ProjectYCG\lmstudio-token.dpapi') -PathType Leaf)) {
    throw 'LM Studio token file is missing; start-backend.ps1 requires it.'
}

if ($U) {
    Write-Status 'Building the controller backend...'
    & mvn -f $backendPom -DskipTests package | ForEach-Object { Write-Status "mvn: $_" }
    if ($LASTEXITCODE -ne 0) { throw "Backend build failed (exit $LASTEXITCODE)." }
    Write-Status 'Controller backend build completed.'
}
$logDirectory = Join-Path $controllerRoot ('.cache\master-launcher-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
$moduleWrapperScript = Join-Path $logDirectory 'run-module.ps1'
@'
param([string]$TargetScript, [string]$ExitMarker)

$ErrorActionPreference = 'Stop'
$exitCode = 0
try {
    & $TargetScript
    if (-not $?) { $exitCode = 1 }
} catch {
    [Console]::Error.WriteLine($_.ToString())
    $exitCode = 1
} finally {
    [System.IO.File]::WriteAllText($ExitMarker, $exitCode.ToString(), [System.Text.Encoding]::ASCII)
}
exit $exitCode
'@ | Set-Content -LiteralPath $moduleWrapperScript -Encoding UTF8
Write-Status "Module logs: $logDirectory"

try {
    Write-Status 'Starting video service...'
    $video = Start-Module 'video' $videoScript $videoRoot $logDirectory
    Wait-ForService 'Video service' 'http://127.0.0.1:8090/health' 200 $video (Join-Path $logDirectory 'video.exit.txt') (Join-Path $logDirectory 'video.err.log')
    Write-Status 'Video service ready on 127.0.0.1:8090.'

    Write-Status 'Starting controller backend...'
    $backend = Start-Module 'backend' $backendScript $backendRoot $logDirectory
    Wait-ForService 'Controller' 'http://127.0.0.1:8080/api/chapters/jobs/not-a-job' 404 $backend (Join-Path $logDirectory 'backend.exit.txt') (Join-Path $logDirectory 'backend.err.log')
    Write-Status 'Controller ready on 127.0.0.1:8080.'

    Write-Status 'Starting desktop application...'
    $desktop = Start-Module 'desktop' $desktopScript $controllerRoot $logDirectory
    Wait-ForDesktop $desktop (Join-Path $logDirectory 'desktop.out.log') (Join-Path $logDirectory 'desktop.exit.txt') (Join-Path $logDirectory 'desktop.err.log')
    Write-Status 'Desktop ready. Press Ctrl+C here to stop all three modules.'
    while ($true) {
        foreach ($child in $children) {
            $child.Process.Refresh()
            if ($child.Process.HasExited) {
                $exitCode = Get-ModuleExitCode $child.ExitMarker
                if ($child.Name -eq 'desktop' -and $exitCode -eq '0') {
                    Write-Status 'Desktop closed.'
                    return
                }
                throw "$($child.Name) exited (code $exitCode). See $($child.ErrorLog)"
            }
        }
        Start-Sleep -Milliseconds 500
    }
} finally {
    Write-Status 'Stopping Project YCG modules...'
    for ($index = $children.Count - 1; $index -ge 0; $index--) {
        $child = $children[$index]
        $child.Process.Refresh()
        if (-not $child.Process.HasExited) {
            & taskkill.exe /PID $child.Process.Id /T /F 2>&1 | Out-Null
            $child.Process.WaitForExit(5000) | Out-Null
        }
        $child.Process.Dispose()
        Write-Status "$($child.Name) stopped."
    }
    Write-Status 'Project YCG modules stopped.'
}
