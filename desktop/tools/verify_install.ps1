param(
    [Parameter(Mandatory)]
    [string] $Installer,
    [Parameter(Mandatory)]
    [string] $PreviousInstaller
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
if ((Get-Content $runtimeVersion -Raw) -notmatch '(?m)^VERSION = "2\.0\.0"\r?$') {
    throw 'The baseline installer did not install the 2.0.0 runtime.'
}

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
if ((Get-Content $runtimeVersion -Raw) -notmatch '(?m)^VERSION = "2\.0\.1"\r?$') {
    throw 'Upgrade did not replace the previous runtime.'
}
$registry = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\AirCursor'
if ((Get-ItemPropertyValue $registry -Name DisplayVersion) -ne '2.0.1') {
    throw 'Windows did not register the stable version.'
}
if (-not (Test-Path $safeShortcut)) { throw 'Safe Mode shortcut is missing.' }
$shortcut = (New-Object -ComObject WScript.Shell).CreateShortcut($safeShortcut)
if ($shortcut.TargetPath -ne $app -or $shortcut.Arguments -ne '--safe-mode') {
    throw 'Safe Mode shortcut points to the wrong command.'
}

$uninstaller = Join-Path $env:TEMP 'aircursor-ci-uninstall.exe'
Copy-Item (Join-Path $program 'Uninstall.exe') $uninstaller
$removeArgs = "/S _?=$program"
While-WorkspaceReserved 'Local\AirCursor.Windows.Runtime' {
    Assert-ExitCode $uninstaller $removeArgs 32
    if (-not (Test-Path $app)) { throw 'A blocked uninstall removed the app.' }
}

Remove-Item $report -ErrorAction SilentlyContinue
Assert-ExitCode $app '--self-test' 0
$log = Get-Content $report
$log
if ($log[-1] -ne 'OK') { throw 'Installed application self-test failed.' }

Copy-Item (Join-Path $program 'Uninstall.exe') $uninstaller -Force
Assert-ExitCode $uninstaller $removeArgs 0
if ((Test-Path $app) -or (Test-Path $runtimeVersion) -or (Test-Path $safeShortcut)) {
    throw 'Uninstall left the launcher, runtime or shortcut behind.'
}
if ((Get-FileHash $settings).Hash -ne $profileHash) { throw 'Uninstall changed the user profile.' }

Add-Content $report "`nWindows 2.0.0 upgrade, guarded update and removal, Safe Mode shortcut, and preserved user settings verified."
