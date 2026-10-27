$ErrorActionPreference = 'Stop'
$python = Join-Path $PSScriptRoot '.venv\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $python)) {
    throw 'Create .venv and install .[api] first; see README.md.'
}
$loadedVisionToken = $false
$tokenPath = Join-Path $env:LOCALAPPDATA 'ProjectYCG\lmstudio-token.dpapi'
try {
    if (-not $env:LM_STUDIO_API_TOKEN -and (Test-Path -LiteralPath $tokenPath -PathType Leaf)) {
        $secureToken = Get-Content -LiteralPath $tokenPath -Raw | ConvertTo-SecureString
        $env:LM_STUDIO_API_TOKEN = [System.Net.NetworkCredential]::new('', $secureToken).Password
        $loadedVisionToken = $true
    }
    & $python -m video_processing_service.api
    if ($LASTEXITCODE -ne 0) { throw "Video processing server exited with code $LASTEXITCODE." }
} finally {
    if ($loadedVisionToken) { Remove-Item Env:LM_STUDIO_API_TOKEN -ErrorAction SilentlyContinue }
}
