const fs = require('fs');
const path = require('path');

const buildGradlePath = path.resolve(__dirname, '../android/app/build.gradle');
if (!fs.existsSync(buildGradlePath)) {
    console.error(`File not found: ${buildGradlePath}`);
    process.exit(1);
}

let content = fs.readFileSync(buildGradlePath, 'utf8');

// Check if already present
if (content.includes('applicationIdSuffix ".debug"')) {
    console.log('✅ applicationIdSuffix ".debug" already present.');
    process.exit(0);
}

// Regex to find debug block inside buildTypes
// Matches:
// buildTypes {
//    ...
//    debug {
//       ...
//    }
// }
// We want to insert into 'debug {'

const debugBlockRegex = /(buildTypes\s*\{[\s\S]*?debug\s*\{)/;
const match = content.match(debugBlockRegex);

if (match) {
    console.log('Found debug block, injecting suffix...');
    const matchStr = match[0];
    const newStr = matchStr + '\n            applicationIdSuffix ".debug"';
    content = content.replace(matchStr, newStr);

    // Also optional: resValue "string", "app_name", "Nexara (Debug)"?
    // User only asked for package suffix.

    fs.writeFileSync(buildGradlePath, content);
    console.log('🎉 Added applicationIdSuffix ".debug" to build.gradle');
} else {
    console.error('❌ Could not find "debug {" block inside build.gradle. Structure might be unexpected.');
    // Attempt to match buildTypes and insert debug block if missing?
    // Unlikely for standard RN project.
    process.exit(1);
}
