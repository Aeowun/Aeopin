param (
    [string]$Version = "1.2.3"
)

$RepoRoot = (Get-Item $PSScriptRoot).Parent.FullName
$AuthorityExe = Join-Path $RepoRoot "authority\target\release\aeopin-authority.exe"
$PortableZip = Join-Path $RepoRoot "build\distributions\aeopin-portable.zip"
$VersionsJson = Join-Path $RepoRoot "versions.json"

Write-Host "AEOPIN v$Version FORENSIC ARTIFACT VERIFICATION"
Write-Host "=============================================="

function Assert-Artifact {
    param($Path, $ExpectedVersion, $Name)
    Write-Host "Checking $Name..."
    if (-not (Test-Path $Path)) { throw "$Name missing at $Path" }

    $info = Get-Item $Path
    $sha = (Get-FileHash $Path -Algorithm SHA256).Hash
    $ver = $info.VersionInfo.ProductVersion

    Write-Host "  Path: $Path"
    Write-Host "  Size: $($info.Length) bytes"
    Write-Host "  SHA256: $sha"
    if ($null -ne $ver) {
        Write-Host "  Version: $ver"
        if ($ver -ne $ExpectedVersion) { throw "$Name version mismatch. Expected $ExpectedVersion, got $ver" }
    }
    return $sha
}

try {
    # 1. Verify Authority
    $actualAuthSha = Assert-Artifact $AuthorityExe $Version "Authority Executable"

    # 2. Verify ZIP and its content
    $actualZipSha = Assert-Artifact $PortableZip $null "Portable ZIP"

    $tempDir = Join-Path $env:TEMP "aeopin_forensic_$(Get-Date -Format 'HHmmss')"
    New-Item -ItemType Directory -Path $tempDir -Force | Out-Null
    Expand-Archive -Path $PortableZip -DestinationPath $tempDir -Force

    $containedExe = Join-Path $tempDir "AEOPIN.exe"
    Assert-Artifact $containedExe $Version "Contained AEOPIN.exe" | Out-Null

    Remove-Item -Recurse -Force $tempDir

    # 3. Verify versions.json
    Write-Host "Checking versions.json..."
    $json = Get-Content $VersionsJson | ConvertFrom-Json
    if ($json.version -ne $Version) { throw "versions.json version mismatch. Expected $Version, got $($json.version)" }
    if ($json.sha256 -ne $actualZipSha.ToLower()) { throw "versions.json SHA mismatch. Expected $($actualZipSha.ToLower()), got $($json.sha256)" }
    Write-Host "  versions.json: PASS"

    Write-Host "`nFORENSIC VERIFICATION: PASS"
    exit 0
} catch {
    Write-Host "`nFORENSIC VERIFICATION: FAIL"
    Write-Host "  Error: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
