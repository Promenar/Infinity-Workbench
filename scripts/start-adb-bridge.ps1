# Nexara ADB Bridge Automation for WSL2
# Pure ASCII version to avoid encoding issues

$DeviceName = $env:NEXARA_DEVICE_NAME
$HardwareID = $env:NEXARA_HARDWARE_ID

if (-not $DeviceName) { Write-Host "Warning: NEXARA_DEVICE_NAME not set" -ForegroundColor Yellow; exit 1 }
if (-not $HardwareID) { Write-Host "Warning: NEXARA_HARDWARE_ID not set" -ForegroundColor Yellow; exit 1 }
$WSLDistro = "Ubuntu"

Write-Host "--- Nexara ADB Bridge Tool ---" -ForegroundColor Cyan

# 1. Search for device
Write-Host "Scanning USB bus for $DeviceName..."
$usbipdList = usbipd list
$foundLine = $usbipdList | Select-String -Pattern $HardwareID

if ($null -eq $foundLine) {
    Write-Host "Error: Device not found ($HardwareID)." -ForegroundColor Red
    Write-Host "Please ensure USB debugging is enabled."
    pause
    exit
}

# Extract BusID
$lineText = $foundLine.ToString().Trim()
$BusID = $lineText.Split(' ')[0]

Write-Host "Found device at BusID: $BusID" -ForegroundColor Green

# 2. Bind
Write-Host "Binding USB port..."
usbipd bind --busid $BusID

# 3. Attach
Write-Host "Starting Auto-Attach mode..."
Write-Host "Keep this window open to maintain connection." -ForegroundColor Yellow
Write-Host "--------------------------------------------------------"

usbipd attach --busid $BusID --auto-attach --wsl $WSLDistro
