!ifndef AIRCURSOR_RUNTIME_LOCK
!define AIRCURSOR_RUNTIME_LOCK
!include LogicLib.nsh
!define AIRCURSOR_BROWSER_MUTEX "Local\AirCursor.Windows.Browser"
!define AIRCURSOR_RUNTIME_MUTEX "Local\AirCursor.Windows.Runtime"
!define AIRCURSOR_WORKSPACE_MUTEX "Local\AirCursor.Windows.Workspace"

; Holding the handle reserves the name until the process exits or explicitly closes it.
!macro RequireAirCursorMutex Name Destination BusyMessage
  System::Call 'kernel32::CreateMutexW(p 0, i 0, w "${Name}") p.r0 ?e'
  Pop $1
  ${If} $0 == 0
    IfSilent +2
    MessageBox MB_ICONSTOP "Windows could not reserve the AirCursor installation."
    SetErrorLevel 1
    Quit
  ${EndIf}
  ${If} $1 == 183
    System::Call 'kernel32::CloseHandle(p r0)'
    IfSilent +2
    MessageBox MB_ICONEXCLAMATION "${BusyMessage}"
    SetErrorLevel 32
    Quit
  ${EndIf}
  StrCpy ${Destination} $0
!macroend
!endif
