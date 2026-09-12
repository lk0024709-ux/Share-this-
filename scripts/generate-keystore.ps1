<#
.SYNOPSIS
  ShareThis — One-Click Release Keystore Generator (Windows PowerShell).

.DESCRIPTION
  Generates a release keystore (release .jks) and copies the single-line
  Base64 string to your clipboard, ready to paste into the GitHub secret
  ANDROID_KEYSTORE_BASE64. Also prints the other three secrets and a
  local-signing snippet.

.EXAMPLE
  .\scripts\generate-keystore.ps1
  # Fully default run: random strong passwords, alias "upload".

.EXAMPLE
  .\scripts\generate-keystore.ps1 -Alias upload -StorePass 'MyStrongPass123!' -Force
  # Custom password, overwrite without prompting.

.NOTES
  Requires: JDK's keytool.exe on PATH (any JDK 8+).
  Works on Windows PowerShell 5.1 and PowerShell 7+.
#>
[CmdletBinding()]
param(
  [string]$Output    = "sharethis-release.jks",
  [string]$Alias     = "upload",
  [string]$StorePass = "",
  [string]$KeyPass   = "",
  [string]$DName     = "CN=ShareThis, OU=App, O=ShareThis, L=City, ST=State, C=IN",
  [switch]$Force
)

$ErrorActionPreference = "Stop"

# ------------------------------------------------------------- prerequisites
$keytool = Get-Command keytool -ErrorAction SilentlyContinue
if (-not $keytool) {
  Write-Host "ERROR: 'keytool' not found on PATH. Install any JDK (8+) and retry." -ForegroundColor Red
  Write-Host "  winget install Microsoft.OpenJDK.17   (then reopen the terminal)"
  exit 1
}

# ------------------------------------------------------- passwords (or random)
function New-RandomPassword {
  $alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789_-".ToCharArray()
  $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
  try {
    $bytes = New-Object byte[] 20
    $rng.GetBytes($bytes)
    -join ($bytes | ForEach-Object { $alphabet[$_ % $alphabet.Length] })
  }
  finally { $rng.Dispose() }
}

if ([string]::IsNullOrEmpty($StorePass)) {
  $StorePass = New-RandomPassword
  Write-Host "(generated random keystore password - shown below, save it!)"
}
if ([string]::IsNullOrEmpty($KeyPass)) {
  $KeyPass = $StorePass
}

# ------------------------------------------------------------- overwrite guard
if ((Test-Path $Output) -and (-not $Force)) {
  $reply = Read-Host "File '$Output' already exists. Overwrite? [y/N]"
  if ($reply -notin @("y", "Y", "yes", "YES")) {
    Write-Host "Aborted - existing keystore left untouched."
    exit 1
  }
}

# ------------------------------------------------------------------ generation
Write-Host "Generating keystore '$Output' (alias '$Alias')..."
$keytoolArgs = @(
  "-genkeypair", "-v",
  "-keystore", $Output,
  "-alias", $Alias,
  "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
  "-storepass", $StorePass, "-keypass", $KeyPass,
  "-dname", $DName
)
& keytool @keytoolArgs
if ($LASTEXITCODE -ne 0) { throw "keytool failed with exit code $LASTEXITCODE" }

Write-Host ""
Write-Host "Verifying..."
& keytool -list -v -keystore $Output -storepass $StorePass -alias $Alias |
  Select-Object -First 20

# ------------------------------------------------------- single-line Base64
$B64_FILE = "$Output.base64.txt"
$base64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($Output))
# Single line, no wrapping (Set-Content would wrap without -NoNewline care):
[IO.File]::WriteAllText($B64_FILE, $base64 + "`n")

try {
  Set-Clipboard -Value $base64
  Write-Host "(Base64 copied to clipboard)"
}
catch {
  Write-Host "(clipboard copy unavailable - use the .base64.txt file)"
}

$fullPath = (Resolve-Path $Output).Path

# ---------------------------------------------------------------------- report
Write-Host ""
Write-Host "======================================================================" -ForegroundColor Green
Write-Host " DONE! Keystore ready: $Output" -ForegroundColor Green
Write-Host "======================================================================" -ForegroundColor Green
Write-Host ""
Write-Host "GitHub Repository Secrets  (Settings -> Secrets and variables -> Actions):"
Write-Host ""
Write-Host "  ANDROID_KEYSTORE_BASE64 .... <clipboard / contents of $B64_FILE> ($($base64.Length) chars, single line)"
Write-Host "  ANDROID_KEYSTORE_PASSWORD .. $StorePass"
Write-Host "  ANDROID_KEY_ALIAS .......... $Alias"
Write-Host "  ANDROID_KEY_PASSWORD ....... $KeyPass"
Write-Host ""
Write-Host "The Base64 file was also saved to: $B64_FILE"
Write-Host ""
Write-Host "Optional local signing - append to %USERPROFILE%\.gradle\gradle.properties:"
Write-Host ""
Write-Host "  KEYSTORE_PATH=$($fullPath -replace '\\','/')"
Write-Host "  KEYSTORE_PASSWORD=$StorePass"
Write-Host "  KEY_ALIAS=$Alias"
Write-Host "  KEY_PASSWORD=$KeyPass"
Write-Host ""
Write-Host "Next steps:"
Write-Host "  1. BACK UP '$Output' somewhere safe (USB drive / password manager)."
Write-Host "     If you lose it, you can NEVER update your Play Store listing again."
Write-Host "  2. Paste the 4 secrets above into GitHub (paste the .base64.txt CONTENT"
Write-Host "     as ANDROID_KEYSTORE_BASE64 - not the file itself)."
Write-Host "  3. Tag a release:  git tag v1.0.0; git push origin v1.0.0"
Write-Host ""
Write-Host "SECURITY: never commit the .jks or .base64.txt - both are git-ignored." -ForegroundColor Yellow
Write-Host "======================================================================" -ForegroundColor Green
