const fs = require('fs');
const path = require('path');

const file = path.resolve(__dirname, '../android/app/build.gradle');
if (!fs.existsSync(file)) {
    console.error("File not found");
    process.exit(1);
}

let content = fs.readFileSync(file, 'utf8');

// We want to ensure:
// minifyEnabled false
// shrinkResources false
// in validation of release block.

// Strategy: Replace "minifyEnabled true" with "minifyEnabled false"
// and "shrinkResources true" with "shrinkResources false"
// Also check variables like "enableMinifyInReleaseBuild"

let modified = false;

if (content.includes('minifyEnabled true')) {
    content = content.replace(/minifyEnabled true/g, 'minifyEnabled false');
    console.log("Fixed: minifyEnabled true -> false");
    modified = true;
}

if (content.includes('shrinkResources true')) {
    content = content.replace(/shrinkResources true/g, 'shrinkResources false');
    console.log("Fixed: shrinkResources true -> false");
    modified = true;
}

// Check for variable usage
// def enableProguardInReleaseBuilds = (findProperty('android.enableProguardInReleaseBuilds') ?: false).toBoolean()
// We can't easily parse variables, but we can look for usage in release block.

const releaseRegex = /release\s*\{[\s\S]*?\}/;
const match = content.match(releaseRegex);
if (match) {
    let block = match[0];
    let newBlock = block;

    // Force injection if not present or variable based
    if (!newBlock.includes('minifyEnabled false')) {
        // If it has minifyEnabled something_variable, replace it
        if (newBlock.includes('minifyEnabled')) {
            newBlock = newBlock.replace(/minifyEnabled\s+.*$/, 'minifyEnabled false'); // Single line replace? dangerous if brace on same line
            // Better: regex for property
            newBlock = newBlock.replace(/minifyEnabled\s+[^}\n]+/, 'minifyEnabled false');
        } else {
            // Insert it
            newBlock = newBlock.replace('release {', 'release {\n            minifyEnabled false\n            shrinkResources false');
        }
    }

    // Explicitly set shrinkResources false if not handled
    if (!newBlock.includes('shrinkResources false')) {
        if (newBlock.includes('shrinkResources')) {
            newBlock = newBlock.replace(/shrinkResources\s+[^}\n]+/, 'shrinkResources false');
        } else {
            // Insert if likely not inserted by previous step
            if (!newBlock.includes('shrinkResources false')) {
                // It might be inserted by previous step
            }
        }
    }

    if (block !== newBlock) {
        content = content.replace(block, newBlock);
        modified = true;
        console.log("Fixed: Enforced R8 disabled in release block.");
    }
}

if (modified) {
    fs.writeFileSync(file, content);
    console.log("🎉 R8 Disabled.");
} else {
    console.log("No changes needed (R8 might already be disabled).");
}
