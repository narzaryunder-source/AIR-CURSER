Unicode True
Target amd64-unicode
RequestExecutionLevel user
SilentInstall silent
AutoCloseWindow true
Name "AirCursor"
OutFile "${OUTPUT}"
Icon "${BRANDICON}"
!include FileFunc.nsh
Section
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
