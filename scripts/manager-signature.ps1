# Prints the Kbuild arguments that let the KernelSU-Next kernel accept this manager APK.
#
#   .\scripts\manager-signature.ps1 -Keystore .\release.jks -Alias key -StorePass ****
#   .\scripts\manager-signature.ps1 -Apk .\KernelSU-Next-SukiUI-release.apk
#
# Output (feed it straight into the kernel build):
#   KSU_MANAGER_PACKAGE=com.sukisu.nova
#   KSU_NEXT_MANAGER_SIZE=0x...
#   KSU_NEXT_MANAGER_HASH=<sha256 of the signing certificate DER, lowercase hex>

param(
    [string]$Apk,
    [string]$Keystore,
    [string]$Alias = "key",
    [string]$StorePass,
    [string]$Package = "com.sukisu.nova"
)

$ErrorActionPreference = "Stop"

function Find-KeyTool {
    $cmd = Get-Command keytool -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    foreach ($root in @("$env:JAVA_HOME", "C:\Program Files\Java", "C:\Program Files\Eclipse Adoptium", "C:\Program Files\Microsoft")) {
        if (-not $root -or -not (Test-Path $root)) { continue }
        $hit = Get-ChildItem -Path $root -Recurse -Filter keytool.exe -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($hit) { return $hit.FullName }
    }
    return $null
}

function Emit([byte[]]$der) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try { $digest = $sha.ComputeHash($der) } finally { $sha.Dispose() }
    $hex = -join ($digest | ForEach-Object { $_.ToString("x2") })
    Write-Output "KSU_MANAGER_PACKAGE=$Package"
    Write-Output ("KSU_NEXT_MANAGER_SIZE=0x{0:x}" -f $der.Length)
    Write-Output "KSU_NEXT_MANAGER_HASH=$hex"
}

if ($Keystore) {
    if (-not (Test-Path $Keystore)) { throw "keystore not found: $Keystore" }
    $keytool = Find-KeyTool
    if (-not $keytool) { throw "keytool not found; install a JDK or pass -Apk instead" }
    $pem = & $keytool -exportcert -rfc -keystore $Keystore -alias $Alias -storepass $StorePass 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $pem) { throw "keytool failed to export alias '$Alias'" }
    $b64 = ($pem | Where-Object { $_ -notmatch "-----" }) -join ""
    Emit ([Convert]::FromBase64String($b64))
    exit 0
}

if (-not $Apk) { throw "pass -Keystore or -Apk" }
if (-not (Test-Path $Apk)) { throw "apk not found: $Apk" }

# Preferred path: ask apksigner for the signer digest, then find the certificate
# blob in the APK that hashes to it (that is exactly what the kernel verifies).
$apksigner = Get-Command apksigner -ErrorAction SilentlyContinue
$expected = $null
if ($apksigner) {
    $out = & $apksigner.Source verify --print-certs $Apk 2>$null
    $line = $out | Where-Object { $_ -match "SHA-256 digest:" } | Select-Object -First 1
    if ($line) { $expected = ($line -split ":")[-1].Trim().ToLowerInvariant() }
}

$bytes = [System.IO.File]::ReadAllBytes((Resolve-Path $Apk))
$start = [Math]::Max(0, $bytes.Length - 4 * 1024 * 1024)
$sha = [System.Security.Cryptography.SHA256]::Create()
try {
    for ($i = $start; $i -lt $bytes.Length - 5; $i++) {
        if ($bytes[$i] -ne 0x30 -or $bytes[$i + 1] -ne 0x82) { continue }
        $len = 4 + ([int]$bytes[$i + 2] * 256 + [int]$bytes[$i + 3])
        if ($i + $len -gt $bytes.Length) { continue }
        $der = New-Object byte[] $len
        [Array]::Copy($bytes, $i, $der, 0, $len)
        $digest = $sha.ComputeHash($der)
        $hex = -join ($digest | ForEach-Object { $_.ToString("x2") })
        if ($expected -and $hex -ne $expected) { continue }
        Write-Output "KSU_MANAGER_PACKAGE=$Package"
        Write-Output ("KSU_NEXT_MANAGER_SIZE=0x{0:x}" -f $len)
        Write-Output "KSU_NEXT_MANAGER_HASH=$hex"
        exit 0
    }
} finally { $sha.Dispose() }

throw "could not locate the signing certificate inside $Apk"
