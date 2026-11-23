$ErrorActionPreference = 'Stop'
$desktopRoot = Split-Path -Parent $PSScriptRoot
$manifest = Get-Content -LiteralPath (Join-Path $desktopRoot 'resources/player-manifest.json') -Raw | ConvertFrom-Json
$runtime = $manifest.'windows-x86_64'
$destination = Join-Path $desktopRoot 'src-tauri/resources/mpv'
New-Item -ItemType Directory -Force -Path $destination | Out-Null
$archive = Join-Path $destination 'runtime.zip'
if (!(Test-Path -LiteralPath $archive) -or (Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash.ToLowerInvariant() -ne $runtime.sha256) {
    Invoke-WebRequest -UseBasicParsing -Uri $runtime.url -OutFile $archive
}
if ((Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash.ToLowerInvariant() -ne $runtime.sha256) {
    throw 'libmpv archive checksum mismatch; refusing to extract.'
}
Expand-Archive -LiteralPath $archive -DestinationPath $destination -Force
Write-Output "Verified mpv $($manifest.mpvVersion) runtime extracted to $destination"
