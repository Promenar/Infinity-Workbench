$path = "android\app\build.gradle"
$content = Get-Content $path -Raw

# Remove the 'if' wrapper and braces, keeping the body
# Regex to match the 'if (...) {' and the closing '}'
# This is tricky with regex multiline.

# Easier: Replace the WHOLE release block in signingConfigs.
$targetBlock = @"
        release {
            if (project.hasProperty('MYAPP_RELEASE_STORE_FILE')) {
                storeFile file(MYAPP_RELEASE_STORE_FILE)
                storePassword MYAPP_RELEASE_STORE_PASSWORD
                keyAlias MYAPP_RELEASE_KEY_ALIAS
                keyPassword MYAPP_RELEASE_KEY_PASSWORD
            }
        }
"@

$newBlock = @"
        release {
            if (project.hasProperty('MYAPP_RELEASE_STORE_FILE')) {
                storeFile file(MYAPP_RELEASE_STORE_FILE)
                storePassword MYAPP_RELEASE_STORE_PASSWORD
                keyAlias MYAPP_RELEASE_KEY_ALIAS
                keyPassword MYAPP_RELEASE_KEY_PASSWORD
            } else {
                throw new GradleException("Missing signing properties!")
            }
        }
"@

# Actually, keep the 'if' but add 'else throw'.
# This allows me to verify if it's hitting the else.
# But I prefer just forcing it to ensure logic.

$forcedBlock = @"
        release {
            storeFile file(MYAPP_RELEASE_STORE_FILE)
            storePassword MYAPP_RELEASE_STORE_PASSWORD
            keyAlias MYAPP_RELEASE_KEY_ALIAS
            keyPassword MYAPP_RELEASE_KEY_PASSWORD
        }
"@

# Normalize line endings
$content = $content -replace "`r`n", "`n"
$targetBlock = $targetBlock -replace "`r`n", "`n"
$forcedBlock = $forcedBlock -replace "`r`n", "`n"

if ($content.Contains($targetBlock)) {
    Write-Host "Found conditional signing block. Replacing with FORCED block..."
    $content = $content.Replace($targetBlock, $forcedBlock)
    Set-Content $path $content -NoNewline
    Write-Host "Done."
} else {
    Write-Warning "Could not find conditional signing block. It might be already modified or formatted differently."
    # Dump content for debugging?
    # Write-Host $content
}
