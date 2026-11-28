param(
    [Parameter(Mandatory)]
    [string] $Installer,
    [Parameter(Mandatory)]
    [string] $PreviousInstaller,
    [Parameter(Mandatory)]
    [string] $PreviousVersion
)

$ErrorActionPreference = 'Stop'
$Installer = (Resolve-Path $Installer).Path
$PreviousInstaller = (Resolve-Path $PreviousInstaller).Path
$program = Join-Path $env:LOCALAPPDATA 'Programs\AirCursor'
$app = Join-Path $program 'AirCursor.exe'
$runtimeVersion = Join-Path $program 'runtime\aircursor_desktop\__init__.py'
$shortcutDir = [Environment]::GetFolderPath('Programs')
$safeShortcut = Join-Path $shortcutDir 'AirCursor (Safe Mode).lnk'
$settings = Join-Path $env:APPDATA 'AirCursor\settings.json'
$report = Join-Path $env:TEMP 'aircursor-selftest.txt'
$versionFile = Join-Path $PSScriptRoot '..\aircursor_desktop\__init__.py'
$expectedVersion = [regex]::Match((Get-Content $versionFile -Raw), '(?m)^VERSION = "([^"]+)"').Groups[1].Value
if (-not $expectedVersion) { throw 'The source version could not be read.' }

function Get-InstalledVersion {
    [regex]::Match((Get-Content $runtimeVersion -Raw), '(?m)^VERSION = "([^"]+)"').Groups[1].Value
}

function Assert-ExitCode($programPath, $options, $expected) {
    $process = Start-Process $programPath -ArgumentList $options -PassThru
    if (-not $process.WaitForExit(120000)) {
        throw "Timed out waiting for $programPath"
    }
    if ($process.ExitCode -ne $expected) {
        throw "$programPath exited with $($process.ExitCode), expected $expected"
    }
}

function While-WorkspaceReserved($name, [scriptblock] $test) {
    $created = $false
    $mutex = [Threading.Mutex]::new($false, $name, [ref] $created)
    try {
        if (-not $created) { throw "Test mutex already existed: $name" }
        & $test
    } finally {
        $mutex.Dispose()
    }
}

if ((Test-Path $settings) -or (Test-Path $app)) { throw 'Installer test requires a clean runner.' }
While-WorkspaceReserved 'Local\AirCursor.Windows.Workspace' {
    Assert-ExitCode $Installer '/S' 32
    if (Test-Path $app) { throw 'Setup touched files while the workspace was reserved.' }
}
Assert-ExitCode $PreviousInstaller '/S' 0
$retired = Join-Path $program 'runtime\PySide6\Qt6Quick3D.dll'
if ((Get-InstalledVersion) -ne $PreviousVersion -or (Test-Path $retired)) {
    throw "The baseline installer did not install the slim $PreviousVersion runtime."
}
$oldBytes = (Get-ChildItem -LiteralPath $program -File -Recurse | Measure-Object -Property Length -Sum).Sum

New-Item -ItemType Directory -Path (Split-Path $settings) -Force | Out-Null
Set-Content $settings '{"restore":true,"browser_tabs":["https://example.org/"],"install_test":true}'
$profileHash = (Get-FileHash $settings).Hash
$appHash = (Get-FileHash $app).Hash
$runtimeHash = (Get-FileHash $runtimeVersion).Hash
While-WorkspaceReserved 'Local\AirCursor.Windows.Runtime' {
    Assert-ExitCode $Installer '/S' 32
    if ((Get-FileHash $app).Hash -ne $appHash) { throw 'A blocked update changed the launcher.' }
    if ((Get-FileHash $runtimeVersion).Hash -ne $runtimeHash) { throw 'A blocked update changed the runtime.' }
}
Assert-ExitCode $Installer '/S' 0
if ((Get-FileHash $settings).Hash -ne $profileHash) { throw 'Upgrade changed the user profile.' }
# The same bootstrap can launch multiple releases. Check the installed Python version instead.
if ((Get-InstalledVersion) -ne $expectedVersion) {
    throw 'Upgrade did not replace the previous runtime.'
}
if (Test-Path $retired) { throw 'Upgrade left an unused Qt library behind.' }
$newBytes = (Get-ChildItem -LiteralPath $program -File -Recurse | Measure-Object -Property Length -Sum).Sum
if ($newBytes -gt $oldBytes + 2MB) { throw 'The installed runtime exceeds its size budget.' }
$registry = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\AirCursor'
if ((Get-ItemPropertyValue $registry -Name DisplayVersion) -ne $expectedVersion) {
    throw 'Windows did not register the stable version.'
}
if (-not (Test-Path $safeShortcut)) { throw 'Safe Mode shortcut is missing.' }
$shortcut = (New-Object -ComObject WScript.Shell).CreateShortcut($safeShortcut)
if ($shortcut.TargetPath -ne $app -or $shortcut.Arguments -ne '--safe-mode') {
    throw 'Safe Mode shortcut points to the wrong command.'
}

$browserShortcut = Join-Path $shortcutDir 'Air Browser.lnk'
$browserLink = (New-Object -ComObject WScript.Shell).CreateShortcut($browserShortcut)
if ($browserLink.TargetPath -ne $app -or $browserLink.Arguments -ne '--browser') { throw 'Invalid browser shortcut.' }
While-WorkspaceReserved 'Local\AirCursor.Windows.Runtime' {
    Assert-ExitCode $app '--browser-smoke-test' 0
}
While-WorkspaceReserved 'Local\AirCursor.Windows.Browser' {
    Assert-ExitCode $Installer '/S' 32
}

$uninstaller = Join-Path $env:TEMP 'aircursor-ci-uninstall.exe'
Copy-Item (Join-Path $program 'Uninstall.exe') $uninstaller
$removeArgs = "/S _?=$program"
While-WorkspaceReserved 'Local\AirCursor.Windows.Runtime' {
    Assert-ExitCode $uninstaller $removeArgs 32
    if (-not (Test-Path $app)) { throw 'A blocked uninstall removed the app.' }
}

Remove-Item $report -ErrorAction SilentlyContinue
try {
    Assert-ExitCode $app '--self-test' 0
} catch {
    if (Test-Path $report) { Get-Content $report }
    throw
}
$log = Get-Content $report
$log
if ($log[-1] -ne 'OK') { throw 'Installed application self-test failed.' }

Copy-Item (Join-Path $program 'Uninstall.exe') $uninstaller -Force
Assert-ExitCode $uninstaller $removeArgs 0
if ((Test-Path $app) -or (Test-Path $runtimeVersion) -or (Test-Path $safeShortcut)) {
    throw 'Uninstall left the launcher, runtime or shortcut behind.'
}
if ((Get-FileHash $settings).Hash -ne $profileHash) { throw 'Uninstall changed the user profile.' }

Add-Content $report "`nWindows $PreviousVersion upgrade, guarded update and removal, Safe Mode shortcut, and preserved user settings verified."
Add-Content $report "Installed bytes before: $oldBytes. Installed bytes after: $newBytes."
