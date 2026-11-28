Unicode True
; Upstream Windows NSIS may ship only the x86 bootstrap. Both stubs start the x64 runtime.
!if /FileExists "${NSISDIR}/Stubs/zlib-amd64-unicode"
  Target amd64-unicode
!endif
RequestExecutionLevel user
SilentInstall silent
AutoCloseWindow true
Name "AirCursor"
OutFile "${OUTPUT}"
Icon "${BRANDICON}"
!include FileFunc.nsh
!include "runtime-lock.nsh"
Var RuntimeHandle
Section
  ${GetParameters} $2
  ${If} $2 == "--browser"
  ${OrIf} $2 == "--browser-smoke-test"
    !insertmacro RequireAirCursorMutex "${AIRCURSOR_BROWSER_MUTEX}" $RuntimeHandle "Air Browser is already running or setup is in progress."
  ${Else}
    !insertmacro RequireAirCursorMutex "${AIRCURSOR_RUNTIME_MUTEX}" $RuntimeHandle "AirCursor is already running or setup is in progress."
  ${EndIf}
  SetOutPath "$EXEDIR\runtime"
  ${GetParameters} $0
  ClearErrors
  ExecWait '"$EXEDIR\runtime\pythonw.exe" -B "$EXEDIR\runtime\run.py" $0' $1
  IfErrors failed
  SetErrorLevel $1
  Quit
  failed:
  MessageBox MB_ICONSTOP "AirCursor could not start its runtime. Reinstall AirCursor from the development installer."
  SetErrorLevel 1
SectionEnd
