$ErrorActionPreference = 'Stop'

$tokenPath = Join-Path $env:LOCALAPPDATA 'ProjectYCG\lmstudio-token.dpapi'
$backendPom = Join-Path $PSScriptRoot 'backend\pom.xml'

if (-not (Test-Path -LiteralPath $tokenPath -PathType Leaf)) {
    throw "LM Studio token file not found: $tokenPath. Save the token with ConvertFrom-SecureString first."
}

try {
    $secureToken = Get-Content -LiteralPath $tokenPath -Raw | ConvertTo-SecureString
} catch {
    throw 'Could not decrypt the LM Studio token. Run this script as the Windows user who saved it.'
}

try {
    $env:LM_STUDIO_API_TOKEN = [System.Net.NetworkCredential]::new('', $secureToken).Password
    & mvn -f $backendPom spring-boot:run
    if ($LASTEXITCODE -ne 0) {
        throw "Backend exited with code $LASTEXITCODE."
    }
} finally {
    Remove-Item Env:LM_STUDIO_API_TOKEN -ErrorAction SilentlyContinue
}
