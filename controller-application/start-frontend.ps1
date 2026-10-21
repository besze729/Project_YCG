$ErrorActionPreference = 'Stop'

$frontendDirectory = Join-Path $PSScriptRoot 'frontend'
$npmCommand = Get-Command npm.cmd -ErrorAction SilentlyContinue
$originalPath = $env:PATH
$localNodeDirectory = $null

if ($npmCommand) {
    $npmPath = $npmCommand.Source
} else {
    $localNodeDirectory = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot '.tools') -Directory -ErrorAction SilentlyContinue |
        Where-Object {
            $_.Name -match '^node-v\d+\.\d+\.\d+-win-x64$' -and
            (Test-Path -LiteralPath (Join-Path $_.FullName 'node.exe') -PathType Leaf) -and
            (Test-Path -LiteralPath (Join-Path $_.FullName 'npm.cmd') -PathType Leaf)
        } |
        Sort-Object { [version]($_.Name -replace '^node-v|\-win-x64$', '') } -Descending |
        Select-Object -First 1
    if (-not $localNodeDirectory) {
        throw 'npm.cmd was not found on PATH or in .tools/node-v*-win-x64. Install Node.js with npm or provide a local Node.js distribution.'
    }
    $npmPath = Join-Path $localNodeDirectory.FullName 'npm.cmd'
}

Push-Location -LiteralPath $frontendDirectory
try {
    if ($localNodeDirectory) {
        # npm scripts also need to find node.exe when launching Vite.
        $env:PATH = $localNodeDirectory.FullName + ';' + $originalPath
    }
    & $npmPath run dev
    if ($LASTEXITCODE -ne 0) {
        throw "Frontend exited with code $LASTEXITCODE."
    }
} finally {
    $env:PATH = $originalPath
    Pop-Location
}
