Unicode True
; Upstream Windows NSIS may ship only the x86 bootstrap. Both stubs start the x64 runtime.
!if /FileExists "${NSISDIR}/Stubs/zlib-amd64-unicode"
  Target amd64-unicode
!endif
RequestExecutionLevel user
SetCompressor /FINAL zlib
Name "AirCursor for Windows"
OutFile "${OUTPUT}"
Icon "${BRANDICON}"
InstallDir "$LOCALAPPDATA\Programs\AirCursor"
ShowInstDetails show
!include "MUI2.nsh"
!include "runtime-lock.nsh"
Var RuntimeHandle
Var WorkspaceHandle

!macro ReserveInstallation
  !insertmacro RequireAirCursorMutex "${AIRCURSOR_RUNTIME_MUTEX}" $RuntimeHandle "Close AirCursor and retry setup."
  !insertmacro RequireAirCursorMutex "${AIRCURSOR_WORKSPACE_MUTEX}" $WorkspaceHandle "Close AirCursor and retry setup."
!macroend

Function .onInit
  !insertmacro ReserveInstallation
FunctionEnd

Function un.onInit
  !insertmacro ReserveInstallation
FunctionEnd

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_INSTFILES
!define MUI_FINISHPAGE_RUN "$INSTDIR\AirCursor.exe"
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "English"
Section "Install"
  SetShellVarContext current
  SetRegView 64
  SetOutPath "$INSTDIR"
  File /r "${PAYLOAD_GLOB}"
  WriteUninstaller "$INSTDIR\Uninstall.exe"
  CreateShortcut "$SMPROGRAMS\AirCursor.lnk" "$INSTDIR\AirCursor.exe"
  CreateShortcut "$SMPROGRAMS\AirCursor (Safe Mode).lnk" "$INSTDIR\AirCursor.exe" "--safe-mode"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AirCursor" "DisplayName" "AirCursor for Windows"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AirCursor" "DisplayVersion" "${AIRCURSOR_VERSION}"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AirCursor" "DisplayIcon" "$INSTDIR\AirCursor.exe"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AirCursor" "UninstallString" '"$INSTDIR\Uninstall.exe"'
  System::Call 'kernel32::CloseHandle(p $WorkspaceHandle)'
  System::Call 'kernel32::CloseHandle(p $RuntimeHandle)'

SectionEnd
Section "Uninstall"
  SetShellVarContext current
  SetRegView 64
  Delete "$SMPROGRAMS\AirCursor.lnk"
  Delete "$SMPROGRAMS\AirCursor (Safe Mode).lnk"
  !include "${UNINSTALL_FILES}"
  Delete "$INSTDIR\Uninstall.exe"
  RMDir "$INSTDIR"
  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\AirCursor"
SectionEnd
