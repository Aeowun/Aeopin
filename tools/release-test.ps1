param (
    [string]$Version = "1.2.3"
)

$ErrorActionPreference = "Stop"

$RepoRoot = (Get-Item $PSScriptRoot).Parent.FullName
$AuthorityExe = Join-Path $RepoRoot "authority\target\release\aeopin-authority.exe"
$PortableZip = Join-Path $RepoRoot "build\distributions\aeopin-portable.zip"
$TestOutputDir = Join-Path $RepoRoot "release-test-results"

# Test Metadata
$TestRunID = Get-Date -Format "yyyyMMdd-HHmmss"
$SourceCommit = (git rev-parse HEAD).Trim()

Write-Host "AEOPIN v$Version FULL TORTURE TEST"
Write-Host "Run ID: $TestRunID"
Write-Host "Commit: $SourceCommit"
Write-Host "=========================================="

if (-not (Test-Path $AuthorityExe)) { throw "Authority executable not found at $AuthorityExe" }
if (-not (Test-Path $PortableZip)) { throw "Portable ZIP not found at $PortableZip" }

$AuthorityHash = (Get-FileHash $AuthorityExe -Algorithm SHA256).Hash
$ZipHash = (Get-FileHash $PortableZip -Algorithm SHA256).Hash

Write-Host "Authority Path: $AuthorityExe"
Write-Host "Authority SHA: $AuthorityHash"
Write-Host "Portable ZIP:  $PortableZip"
Write-Host "ZIP SHA:       $ZipHash"
Write-Host ""

if (Test-Path $TestOutputDir) { Remove-Item -Recurse -Force $TestOutputDir }
New-Item -ItemType Directory -Path $TestOutputDir -Force | Out-Null

$Results = @()

function Write-Scenario {
    param([string]$Name, [string]$Status, [string]$Details = "")
    $color = if ($Status -eq "PASS") { "Green" } elseif ($Status -eq "FAIL") { "Red" } else { "Yellow" }
    Write-Host ("{0,-40} [" -f $Name) -NoNewline
    Write-Host $Status -ForegroundColor $color -NoNewline
    Write-Host "]"
    if ($Details) { Write-Host "  $Details" -ForegroundColor Gray }
}

function Run-Authority {
    param(
        [string]$TestRoot,
        [string]$Command = "--test-install",
        [string]$Interrupt = $null,
        [hashtable]$Env = @{}
    )

    $fullEnv = if ($Env) { $Env.Clone() } else { @{} }
    $fullEnv["AEOPIN_TEST_ROOT"] = $TestRoot
    if ($Interrupt) { $fullEnv["AEOPIN_TEST_INTERRUPT"] = $Interrupt }

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $AuthorityExe
    $psi.Arguments = $Command
    $psi.WorkingDirectory = (Split-Path $AuthorityExe)
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    foreach ($key in $fullEnv.Keys) { $psi.EnvironmentVariables[$key] = $fullEnv[$key] }

    $proc = [System.Diagnostics.Process]::Start($psi)
    $stdout = $proc.StandardOutput.ReadToEnd()
    $stderr = $proc.StandardError.ReadToEnd()
    $proc.WaitForExit(60000) # 60s timeout

    return [PSCustomObject]@{
        ExitCode = $proc.ExitCode
        Stdout = $stdout
        Stderr = $stderr
    }
}

function Assert-Invariant {
    param([string]$TestRoot, [string]$ExpectedVersion = $null)
    $binDir = Join-Path $TestRoot "bin"
    $exe = Join-Path $binDir "AEOPIN.exe"
    $verFile = Join-Path $TestRoot "installed.version"
    $transFile = Join-Path $TestRoot "install.transaction"
    $binOld = Join-Path $TestRoot "bin.old"

    if (-not (Test-Path $exe)) { throw "Invariant failed: AEOPIN.exe missing" }

    $size = (Get-Item $exe).Length
    if ($size -lt 1024) { throw "Invariant failed: AEOPIN.exe is too small ($size bytes)" }

    if ($ExpectedVersion) {
        if (-not (Test-Path $verFile)) { throw "Invariant failed: installed.version file missing" }
        $actualVer = (Get-Content $verFile).Trim()
        if ($actualVer -ne $ExpectedVersion) { throw "Invariant failed: Version mismatch. Expected $ExpectedVersion, got $actualVer" }
    }
    if (Test-Path $transFile) { throw "Invariant failed: Transaction file remains" }
    if (Test-Path $binOld) { throw "Invariant failed: bin.old remains" }
}

function Log-Evidence {
    param([string]$ScenarioName, [string]$TestRoot, $Result)
    $scenarioDir = Join-Path $TestOutputDir $ScenarioName
    if (-not (Test-Path $scenarioDir)) { New-Item -ItemType Directory -Path $scenarioDir -Force | Out-Null }

    $Result | ConvertTo-Json | Set-Content (Join-Path $scenarioDir "run_result.json")
    if (Test-Path $TestRoot) {
        Get-ChildItem $TestRoot -Recurse | Select-Object FullName, Length, LastWriteTime | ConvertTo-Json | Set-Content (Join-Path $scenarioDir "filesystem_state.json")
    }
}

# Baseline
$LocalZip = Join-Path (Split-Path $AuthorityExe) "aeopin-portable.zip"
if (Test-Path ($LocalZip + ".bak")) { Remove-Item ($LocalZip + ".bak") -Force }
Copy-Item $PortableZip $LocalZip -Force

# --- SCENARIOS ---

# 1. Fresh Install
$Name = "Fresh_Install"
try {
    $TestRoot = Join-Path $TestOutputDir $Name
    $res = Run-Authority -TestRoot $TestRoot
    if ($res.ExitCode -ne 0) { throw "Authority failed with code $($res.ExitCode)" }
    Assert-Invariant -TestRoot $TestRoot -ExpectedVersion $Version
    Write-Scenario $Name "PASS"
} catch {
    Write-Scenario $Name "FAIL" $_.Exception.Message
}

# 2. Fresh Rerun
$Name = "Fresh_Rerun"
try {
    $res = Run-Authority -TestRoot $TestRoot
    Assert-Invariant -TestRoot $TestRoot -ExpectedVersion $Version
    Write-Scenario $Name "PASS"
} catch {
    Write-Scenario $Name "FAIL" $_.Exception.Message
}

# 3. v1.2.2 -> v1.2.3 Update
$Name = "Update_v122_to_v123"
try {
    $TestRoot = Join-Path $TestOutputDir $Name
    New-Item -ItemType Directory -Path (Join-Path $TestRoot "bin") -Force | Out-Null
    Set-Content (Join-Path $TestRoot "bin\AEOPIN.exe") "FAKE_1.2.2_CONTENT_LONG_ENOUGH"
    Set-Content (Join-Path $TestRoot "installed.version") "1.2.2"
    $res = Run-Authority -TestRoot $TestRoot -Command "--test-update"
    Assert-Invariant -TestRoot $TestRoot -ExpectedVersion $Version
    Write-Scenario $Name "PASS"
} catch {
    Write-Scenario $Name "FAIL" $_.Exception.Message
}

# 4. Update Rerun
$Name = "Update_Rerun"
try {
    $res = Run-Authority -TestRoot $TestRoot -Command "--test-update"
    Assert-Invariant -TestRoot $TestRoot -ExpectedVersion $Version
    Write-Scenario $Name "PASS"
} catch {
    Write-Scenario $Name "FAIL" $_.Exception.Message
}

# 5. Corrupt Payload
$Name = "Corrupt_Payload"
try {
    $TestRoot = Join-Path $TestOutputDir $Name
    $BackupZip = $LocalZip + ".bak"
    if (Test-Path $BackupZip) { Remove-Item $BackupZip -Force }
    Move-Item $LocalZip $BackupZip -Force
    Set-Content $LocalZip "NOT_A_ZIP"

    $res = Run-Authority -TestRoot $TestRoot

    if ($res.ExitCode -eq 0) { $err = "Should have failed" }
    if (Test-Path (Join-Path $TestRoot "bin\AEOPIN.exe")) { $err = "Should not have installed" }

    Move-Item $BackupZip $LocalZip -Force
    if ($err) { throw $err }
    Write-Scenario $Name "PASS"
} catch {
    Write-Scenario $Name "FAIL" $_.Exception.Message
    if (Test-Path $BackupZip) { Move-Item $BackupZip $LocalZip -Force }
}

# Fault Injection
$Boundaries = @("after_extraction", "after_validation", "after_journal_staged", "after_backup", "after_commit", "after_commit_validation", "before_cleanup")
foreach ($B in $Boundaries) {
    $Name = "Interrupt_$B"
    try {
        $TestRoot = Join-Path $TestOutputDir $Name
        New-Item -ItemType Directory -Path (Join-Path $TestRoot "bin") -Force | Out-Null
        Set-Content (Join-Path $TestRoot "bin\AEOPIN.exe") "PRE_INTERRUPT_CONTENT"
        Set-Content (Join-Path $TestRoot "installed.version") "1.2.2"
        $res = Run-Authority -TestRoot $TestRoot -Command "--test-update" -Interrupt $B
        if ($res.ExitCode -ne 101) { throw "Expected 101, got $($res.ExitCode)" }
        $resRec = Run-Authority -TestRoot $TestRoot -Command "--test-install"
        Assert-Invariant -TestRoot $TestRoot -ExpectedVersion $Version
        Write-Scenario $Name "PASS"
    } catch {
        Write-Scenario $Name "FAIL" $_.Exception.Message
    }
}

Write-Host "`nDONE"
