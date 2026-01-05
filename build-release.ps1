# build-release.ps1 - Automated Key Injection & Build Script

$SecureDir = "secure_env"
$AndroidAppDir = "android\app"
$GradlePropsFile = "android\gradle.properties"

Write-Host "🔐 Starting Secure Build Process..." -ForegroundColor Cyan

# 0. Auto-Bump Version
Write-Host "📦 Bumping Version (Patch)..."
npm run bump:patch
if ($LASTEXITCODE -ne 0) {
    Write-Error "❌ Version Bump Failed"
    exit $LASTEXITCODE
}

# 1. Check Secure Environment
if (-not (Test-Path "$SecureDir\promenar.keystore")) {
    Write-Error "❌ Keystore file missing in $SecureDir"
    exit 1
}
if (-not (Test-Path "$SecureDir\secure.properties")) {
    Write-Error "❌ secure.properties missing in $SecureDir"
    exit 1
}

# 2. Read Credentials
$Props = ConvertFrom-StringData (Get-Content "$SecureDir\secure.properties" -Raw)
$StorePass = $Props.KEYSTORE_PASSWORD
$KeyAlias = $Props.KEY_ALIAS
$KeyPass = $Props.KEY_PASSWORD

if (-not $StorePass -or -not $KeyAlias -or -not $KeyPass) {
    Write-Error "❌ Missing credentials in secure.properties. Please fill them in."
    exit 1
}

# 3. Inject Keystore
Write-Host "-> Injecting Keystore..."
Copy-Item "$SecureDir\promenar.keystore" "$AndroidAppDir\promenar.keystore" -Force

# 4. Inject Gradle Properties (Append to gradle.properties)
Write-Host "-> Injecting Gradle Properties..."
$InjectionMarker = "### INJECTED_SIGNING_CONFIG ###"
$CurrentContent = Get-Content $GradlePropsFile -Raw

# Remove old injection if exists
if ($CurrentContent -match "$InjectionMarker") {
    $CurrentContent = $CurrentContent -replace "(?s)${InjectionMarker}.*${InjectionMarker}", ""
}

$InjectionBlock = @"

$InjectionMarker
MYAPP_UPLOAD_STORE_FILE=promenar.keystore
MYAPP_UPLOAD_STORE_PASSWORD=$StorePass
MYAPP_UPLOAD_KEY_ALIAS=$KeyAlias
MYAPP_UPLOAD_KEY_PASSWORD=$KeyPass
$InjectionMarker
"@

$NewContent = $CurrentContent + $InjectionBlock
Set-Content $GradlePropsFile $NewContent -NoNewline -Encoding Utf8

Write-Host "✅ Injection Complete. Signing config is active."

# 5. Build
Write-Host "🚀 Starting Release Build..."
Set-Location android
.\gradlew clean assembleRelease
if ($LASTEXITCODE -ne 0) {
    Write-Error "❌ Gradle Build Failed with exit code $LASTEXITCODE"
    exit $LASTEXITCODE
}
Set-Location ..

# 6. Cleanup (Optional: Clean properties to avoid committing credentials if file tracked)
# Note: gradle.properties is often tracked. We should ideally use a local.properties or non-tracked file.
# But for this user workflow, we will advise on .gitignore or cleanup.
# Here we will attempt to cleanup the injected properties after build.

Write-Host "🧹 Cleaning up credentials..."
$PostBuildContent = Get-Content $GradlePropsFile -Raw
$CleanedContent = $PostBuildContent -replace "(?s)${InjectionMarker}.*${InjectionMarker}", ""
Set-Content $GradlePropsFile $CleanedContent -NoNewline -Encoding Utf8

Write-Host "✨ Build Workflow Complete!" -ForegroundColor Green
