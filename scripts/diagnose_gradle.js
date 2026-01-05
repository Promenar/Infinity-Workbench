const fs = require('fs');
const path = require('path');

const file = path.resolve(__dirname, '../android/app/build.gradle');
const content = fs.readFileSync(file, 'utf8');

const lines = content.split('\n');
lines.forEach((line, i) => {
    console.log(`${i + 1}: ${line}`);
});
