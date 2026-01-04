const fs = require('fs');
const path = require('path');

const buildGradlePath = path.resolve(__dirname, '../android/app/build.gradle');
if (!fs.existsSync(buildGradlePath)) {
    console.error(`File not found: ${buildGradlePath}`);
    process.exit(1);
}

const content = fs.readFileSync(buildGradlePath, 'utf8');
const lines = content.split('\n');

let newLines = [];
let inBuildTypes = false;
let inReleaseBlock = false;
let braceCount = 0;
let modified = false;

// We also need to ensure release signing config DEFINITION is present (from previous script logic)
// But to keep this script focused and cleaner, let's assume the previous injection worked for definitions
// and focus on fixing the usage in buildTypes.
// Actually, let's just make this script do ONLY the cleanup/fix of buildTypes.
// I will blindly run the regex replacement for the definition injection at the end just in case.

for (let line of lines) {
    const trimmed = line.trim();

    if (trimmed.includes('buildTypes {')) {
        inBuildTypes = true;
    }

    if (inBuildTypes) {
        if (trimmed.includes('release {')) {
            inReleaseBlock = true;
        }
    }

    if (inReleaseBlock) {
        if (trimmed.includes('signingConfig signingConfigs.debug')) {
            console.log('Found and removing: signingConfig signingConfigs.debug (in release block)');
            // Replace with comment or empty
            line = line.replace('signingConfig signingConfigs.debug', '// signingConfig signingConfigs.debug removed by fix_signing.js');
            modified = true;
        }

        // Check for closing brace to exit release block
        // Simple logic: if line has '}', we might be closing. 
        // This is fragile if braces are on same line.
        // But typically existing build.gradle is well formatted.
        // Let's rely on indentation or just detecting "}" at start of line?
        // standard android helper formatting:
        // release {
        // }
        if (trimmed === '}') {
            inReleaseBlock = false;
        }
    }

    // Safety check: if we see another block start like 'debug {' while thinking we are in release, 
    // it probably means we missed the closing brace.
    if (inReleaseBlock && (trimmed.includes('debug {') || trimmed.includes('signingConfigs {'))) {
        inReleaseBlock = false;
    }

    if (trimmed === '}' && inBuildTypes && !inReleaseBlock) {
        // Could be closing buildTypes, but hard to tell without counting.
        // Doesn't matter for the logic above as long as we correctly identify inside release.
    }

    newLines.push(line);
}

// 2. Ensure signingConfigs.release is DEFINED (copy of previous logic)
let finalContent = newLines.join('\n');
const releaseConfigContent = `
        release {
            println "DEBUG: MYAPP_UPLOAD_STORE_FILE exists? " + project.hasProperty('MYAPP_UPLOAD_STORE_FILE')
            if (project.hasProperty('MYAPP_UPLOAD_STORE_FILE')) {
                storeFile file(MYAPP_UPLOAD_STORE_FILE)
                storePassword MYAPP_UPLOAD_STORE_PASSWORD
                keyAlias MYAPP_UPLOAD_KEY_ALIAS
                keyPassword MYAPP_UPLOAD_KEY_PASSWORD
            }
        }`;

if (!finalContent.includes('DEBUG: MYAPP_UPLOAD_STORE_FILE exists?')) {
    if (finalContent.includes("if (project.hasProperty('MYAPP_UPLOAD_STORE_FILE'))")) {
        finalContent = finalContent.replace(
            "release {",
            "release {\n            println \"DEBUG: MYAPP_UPLOAD_STORE_FILE exists? \" + project.hasProperty('MYAPP_UPLOAD_STORE_FILE')"
        );
        modified = true;
    } else if (finalContent.includes('signingConfigs {')) {
        finalContent = finalContent.replace('signingConfigs {', `signingConfigs {${releaseConfigContent}`);
        modified = true;
    }
}

// 3. Ensure signingConfig signingConfigs.release is USED in release block
// Using regex since we parsed line-by-line already but insertion is easier with replace.
const releaseBuildTypeRegex = /buildTypes\s*\{[\s\S]*?release\s*\{/;
const matchBuildType = finalContent.match(releaseBuildTypeRegex);
if (matchBuildType) {
    const releaseBlockStart = matchBuildType[0];
    if (!finalContent.substring(matchBuildType.index).includes('signingConfig signingConfigs.release')) {
        const newReleaseBlockStart = releaseBlockStart + '\n            signingConfig signingConfigs.release';
        finalContent = finalContent.replace(releaseBlockStart, newReleaseBlockStart);
        modified = true;
    }
}

if (modified) {
    fs.writeFileSync(buildGradlePath, finalContent);
    console.log('🎉 build.gradle fixed.');
} else {
    console.log('No changes needed.');
}
