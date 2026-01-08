$path = "android\app\build.gradle"
$content = Get-Content $path -Raw

# 1. Inject signingConfigs.release
$signingPattern = @"
    signingConfigs \{
        debug \{
            storeFile file\('debug.keystore'\)
            storePassword 'android'
            keyAlias 'androiddebugkey'
            keyPassword 'android'
        \}
    \}
"@
# Note: Escape for Regex? Yes.
# Actually, just use string replace if exact match
$signingTarget = @"
    signingConfigs {
        debug {
            storeFile file('debug.keystore')
            storePassword 'android'
            keyAlias 'androiddebugkey'
            keyPassword 'android'
        }
    }
"@

$signingReplacement = @"
    signingConfigs {
        debug {
            storeFile file('debug.keystore')
            storePassword 'android'
            keyAlias 'androiddebugkey'
            keyPassword 'android'
        }
        release {
            if (project.hasProperty('MYAPP_RELEASE_STORE_FILE')) {
                storeFile file(MYAPP_RELEASE_STORE_FILE)
                storePassword MYAPP_RELEASE_STORE_PASSWORD
                keyAlias MYAPP_RELEASE_KEY_ALIAS
                keyPassword MYAPP_RELEASE_KEY_PASSWORD
            }
        }
    }
"@

# Normalize line endings for reliable string replacement
$content = $content -replace "`r`n", "`n"
$signingTarget = $signingTarget -replace "`r`n", "`n"
$signingReplacement = $signingReplacement -replace "`r`n", "`n"

if ($content.Contains($signingTarget)) {
    Write-Host "Found signingConfigs block. Replacing..."
    $content = $content.Replace($signingTarget, $signingReplacement)
} else {
    Write-Warning "Could not find exact signingConfigs block."
    # Fallback regex?
}

# 2. Inject usage in release buildType
# Target: release { // Caution...
$releaseTargetRegex = "release\s*\{\s*// Caution! In production, you need to generate your own keystore"
$releaseReplacement = "release {`n            signingConfig signingConfigs.release`n            // Caution! In production, you need to generate your own keystore"

if ($content -match $releaseTargetRegex) {
    Write-Host "Found release buildType block. Injecting..."
    $content = $content -replace $releaseTargetRegex, $releaseReplacement
} else {
    Write-Warning "Could not find release buildType block."
}

Set-Content $path $content -NoNewline
Write-Host "Done."
