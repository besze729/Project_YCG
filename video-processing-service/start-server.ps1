$ErrorActionPreference = 'Stop'
$python = Join-Path $PSScriptRoot '.venv\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $python)) {
    throw 'Create .venv and install .[api] first; see README.md.'
}
& $python -m video_processing_service.api
if ($LASTEXITCODE -ne 0) { throw "Video processing server exited with code $LASTEXITCODE." }
