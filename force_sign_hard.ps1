$path = "android\app\build.gradle"
$content = Get-Content $path -Raw

# 1. Inject Hardcoded signingConfigs.release
$signingReplacement = @"
    signingConfigs {
        debug {
            storeFile file('debug.keystore')
            storePassword 'android'
            keyAlias 'androiddebugkey'
            keyPassword 'android'
        }
        release {
            storeFile file('D:/NF/secure_env/promenar.keystore')
            storePassword 'narcis04300211'
            keyAlias 'promenar'
            keyPassword 'narcis04300211'
        }
    }
"@

# Regex to replace the entire signingConfigs block
# We assume the block starts with "signingConfigs {" and ends with "}" (nested braces make regex hard)
# Instead, we used a known string anchor in previous script.
# We will match the debug block and replace it + append release.

$signingTargetRegex = "(?ms)signingConfigs\s*\{.*?debug\s*\{.*?\}.*?\}"
# This regex is risky if not matched perfectly.

# Let's use the known "Target" string approach which is safer if indentation matches.
$signingTargetStart = @"
    signingConfigs {
        debug {
            storeFile file('debug.keystore')
            storePassword 'android'
            keyAlias 'androiddebugkey'
            keyPassword 'android'
        }
"@

# We'll assume the current file MIGHT have the failed release block or the original block.
# Let's try to match the "signingConfigs {" header and blindly replace until the closing brace of debug? 
# No, messy.

# Strategy: Read file, find "signingConfigs {", find corresponding closing brace?
# Hard in PS without parser.

# Simpler Strategy:
# Replace the Known Debug Block with Debug + Release Block.
$debugBlock = @"
        debug {
            storeFile file('debug.keystore')
            storePassword 'android'
            keyAlias 'androiddebugkey'
            keyPassword 'android'
        }
"@

$newDebugAndRelease = @"
        debug {
            storeFile file('debug.keystore')
            storePassword 'android'
            keyAlias 'androiddebugkey'
            keyPassword 'android'
        }
        release {
            storeFile file('D:/NF/secure_env/promenar.keystore')
            storePassword 'narcis04300211'
            keyAlias 'promenar'
            keyPassword 'narcis04300211'
        }
"@

# Normalize
$content = $content -replace "`r`n", "`n"
$debugBlock = $debugBlock -replace "`r`n", "`n"
$newDebugAndRelease = $newDebugAndRelease -replace "`r`n", "`n"

# Check if we already have a release block (from previous attempts)
# If so, we want to REPLACE it.
# Regex to find "release { ... }" inside signingConfigs is hard.
# But we know where we inserted it: right after debug block.

# First, remove any existing release block inside signingConfigs (roughly)
# Match "release { ... }" logic used previously?
$oldReleaseRegex = "(?ms)release\s*\{[^}]*MYAPP_RELEASE_STORE_FILE[^}]*\}"
$content = $content -replace $oldReleaseRegex, ""
# Also mismatching braces might be left over?

# Actually, the previous script replaced "signingConfigs { debug { ... } }" with "signingConfigs { debug { ... } release { ... } }".
# So if we search for "debug { ... } release { ... }", we can replace it.

$existingReleasePattern = @"
        debug {
            storeFile file('debug.keystore')
            storePassword 'android'
            keyAlias 'androiddebugkey'
            keyPassword 'android'
        }
        release {
"@

if ($content.Contains($existingReleasePattern)) {
    # We are in state with release block. 
    # Let's try to replace the WHOLE "release { ... }" block that follows.
    # We can just overwrite the whole file with a clean template of that section if we are desperate.
    # Or simply: Replace the block starting at "signingConfigs {" until "buildTypes {"?
    # This is safe because they are adjacent!
    
    $blockPattern = "(?ms)signingConfigs\s*\{.*\}[\r\n\s]*buildTypes"
    # This matches too much if braces inside.
}

# FINAL STRATEGY: 
# 1. Load file.
# 2. Find "signingConfigs {".
# 3. Brutally replace everything from "signingConfigs {" up to "buildTypes {" with our clean block.
#    Assuming "buildTypes" follows "signingConfigs".
#    Based on cat output:
#    Line 126: signingConfigs {
#    Line 134/140: buildTypes {
#    Yes, they are adjacent.

$cleanSigningBlock = @"
    signingConfigs {
        debug {
            storeFile file('debug.keystore')
            storePassword 'android'
            keyAlias 'androiddebugkey'
            keyPassword 'android'
        }
        release {
            storeFile file('D:/NF/secure_env/promenar.keystore')
            storePassword 'narcis04300211'
            keyAlias 'promenar'
            keyPassword 'narcis04300211'
        }
    }
"@

$regexReplace = "(?ms)signingConfigs\s*\{.*?\}\s*buildTypes"
# This regex stops at FIRST "}"? No, non-greedy ".*?" stops at first "}". 
# Since signingConfigs contains nested braces (debug{}), ".*?" will stop at the closing brace of debug{}?
# No, ".*?" matches minimal chars. 
# "signingConfigs { ... debug { ... } ... }"
# finding "}" will match the first one found? No.
# PS Regex is tricky.

# Javascript-style manual splice?
$startMarker = "signingConfigs {"
$endMarker = "buildTypes {"

$startIndex = $content.IndexOf($startMarker)
$endIndex = $content.IndexOf($endMarker)

if ($startIndex -ge 0 -and $endIndex -gt $startIndex) {
    Write-Host "Found block range. Replacing..."
    $pre = $content.Substring(0, $startIndex)
    $post = $content.Substring($endIndex)
    $content = $pre + $cleanSigningBlock + "`n    " + $post
    Set-Content $path $content -NoNewline
    Write-Host "Success."
} else {
    Write-Error "Could not find start/end markers."
}
