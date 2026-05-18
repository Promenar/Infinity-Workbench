const { spawn } = require('child_process');

console.clear();
console.log("\x1b[36m" + `
┌────────────────────────────────────────────────────────────────────────┐
│  N E X A R A   M E T R O   D E B U G G E R   [v2.0-Logcat-TUI]         │
│  Connection Mode: 🟢 ADB Logcat Stream Parsing (No Socket Overheads)  │
│  Target Device:   📱 Realtime Connected Android Emulator / Device      │
└────────────────────────────────────────────────────────────────────────┘
` + "\x1b[0m");

const adb = spawn('adb', ['logcat', '-s', 'NEXARA_METRO']);

let logBuffer = "";
let currentStreamTokens = 0;
let currentCps = 0;

adb.stdout.on('data', (data) => {
    logBuffer += data.toString();
    let lines = logBuffer.split('\n');
    logBuffer = lines.pop(); // Keep last incomplete line

    for (let line of lines) {
        processLine(line);
    }
});

adb.on('error', (err) => {
    console.error(`\x1b[31m[ADB Error] Failed to start logcat process: ${err.message}\x1b[0m`);
    console.log(`\x1b[33mTip: Please ensure ADB is installed and a device/emulator is connected (run 'adb devices')\x1b[0m`);
});

process.on('SIGINT', () => {
    console.log("\n\x1b[33mShutting down Metro Parser safely. Bye! 👋\x1b[0m");
    adb.kill();
    process.exit();
});

function processLine(line) {
    const startIdx = line.indexOf("EVENT_START|");
    const endIdx = line.indexOf("|EVENT_END");
    if (startIdx === -1 || endIdx === -1 || endIdx <= startIdx) {
        return; // Ignore raw non-structured lines
    }

    const payloadStr = line.substring(startIdx + "EVENT_START|".length, endIdx);
    const parts = payloadStr.split('|');
    if (parts.length < 2) return;

    const event = parts[0];
    const rawJson = parts.slice(1).join('|');

    let data = {};
    try {
        data = JSON.parse(rawJson);
    } catch (e) {
        data = { message: rawJson };
    }

    const time = new Date().toLocaleTimeString();

    switch (event) {
        case 'LOG':
            console.log(`\x1b[90m[${time}] [LOG] ──> ${data.message}\x1b[0m`);
            break;
            
        case 'CONTEXT_ASSEMBLY':
            console.log(`\n\x1b[35m[${time}] 🧠 [CONTEXT ASSEMBLY] ──> ⏳ Sliding Window Applied\x1b[0m`);
            console.log(`   ├─ model: \x1b[1;36m${data.model}\x1b[0m | temp: \x1b[33m${data.temperature}\x1b[0m`);
            console.log(`   ├─ RAG Memory: \x1b[32m${data.enableMemorySearch}\x1b[0m | KG: \x1b[32m${data.enableKnowledgeSearch}\x1b[0m | Search: \x1b[32m${data.enableWebSearch}\x1b[0m`);
            if (data.system && data.system !== 'none') {
                console.log(`   ├─ system_prompt: \x1b[90m"${data.system.substring(0, 120)}..."\x1b[0m`);
            }
            if (data.messages && data.messages.length > 0) {
                const lastMsg = data.messages[data.messages.length - 1];
                console.log(`   ├─ messages_count: \x1b[36m${data.messages.length}\x1b[0m`);
                console.log(`   └─ \x1b[1;34mUSER\x1b[0m: \x1b[1;37m"${lastMsg.content}"\x1b[0m`);
            }
            currentStreamTokens = 0;
            currentCps = 0;
            break;

        case 'HTTP_REQUEST':
            console.log(`\x1b[33m[${time}] 🌐 [HTTP REQUEST] ──> 📤 ${data.method} ${data.url}\x1b[0m`);
            break;

        case 'HTTP_STREAM_CHUNK':
            currentStreamTokens = data.totalTokens || 0;
            currentCps = data.cps || 0;
            process.stdout.write(`\r\x1b[36m   ✍️  [LLM GENERATING] ──> ⏳ Stream Chunks (Bytes: ${data.totalBytes} | Tokens: ${currentStreamTokens} | Speed: \x1b[1;32m${currentCps} CPS\x1b[36m)\x1b[0m`);
            break;

        case 'HTTP_RESPONSE':
            console.log(`\n\x1b[32m[${time}] 🌐 [HTTP RESPONSE] ──> 🟢 Code: ${data.code}\x1b[0m`);
            if (data.response) {
                console.log(`   └─ response: \x1b[90m"${data.response.substring(0, 200)}..."\x1b[0m`);
            }
            break;

        case 'LLM_COMPLETE':
            process.stdout.write("\n");
            console.log(`\x1b[32m[${time}] 🏁 [GEN COMPLETED] ──> 🟢 Flow Terminated Safely\x1b[0m`);
            console.log(`   └─ model: \x1b[36m${data.model}\x1b[0m | status: \x1b[32m${data.status}\x1b[0m`);
            break;

        case 'DB_QUERY':
            console.log(`\x1b[90m[${time}] 🗄️  [ROOM SQL] ──> ${data.sql.substring(0, 100)}... args: ${JSON.stringify(data.bindArgs)}\x1b[0m`);
            break;

        default:
            console.log(`\x1b[36m[${time}] ⚙️  [${event}] ──> ${data.message}\x1b[0m`);
            break;
    }
}
