; Luminary installer (NSIS 3, Modern UI 2). Built by build_installer.py:
;   makensis -DSRC=<release folder> -DART=<artwork folder> -DVERSION=2.0.0 -DOUTFILE=<setup.exe> luminary.nsi
; Per-user install (no administrator rights): %LOCALAPPDATA%\Programs\Luminary.
; Upgrades keep .minecraft, launcher_data (settings, session) and logs.

Unicode true
ManifestDPIAware true
SetCompressor /SOLID lzma
SetCompressorDictSize 64
RequestExecutionLevel user

!ifndef VERSION
  !define VERSION "2.0.0"
!endif
!define APPNAME "Luminary"
!define UNINST_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\LuminaryLauncher"

Name "${APPNAME}"
Caption "Установка Luminary"
OutFile "${OUTFILE}"
InstallDir "$LOCALAPPDATA\Programs\Luminary"
InstallDirRegKey HKCU "${UNINST_KEY}" "InstallLocation"
BrandingText "Luminary ${VERSION}"

VIProductVersion "${VERSION}.0"
VIAddVersionKey /LANG=1049 "ProductName" "Luminary Launcher"
VIAddVersionKey /LANG=1049 "FileDescription" "Установка Luminary Launcher"
VIAddVersionKey /LANG=1049 "ProductVersion" "${VERSION}"
VIAddVersionKey /LANG=1049 "FileVersion" "${VERSION}"
VIAddVersionKey /LANG=1049 "CompanyName" "Luminary"
VIAddVersionKey /LANG=1049 "LegalCopyright" "Luminary"

!include "MUI2.nsh"
!include "LogicLib.nsh"

; ---------------------------------------------------------------- look (underwater palette)
!define MUI_ICON "${ART}/luminary.ico"
!define MUI_UNICON "${ART}/luminary.ico"
!define MUI_BGCOLOR "0A1418"
!define MUI_TEXTCOLOR "D4EAE6"
!define MUI_HEADERIMAGE
!define MUI_HEADERIMAGE_RIGHT
!define MUI_HEADERIMAGE_BITMAP "${ART}/header.bmp"
!define MUI_HEADERIMAGE_UNBITMAP "${ART}/header.bmp"
!define MUI_WELCOMEFINISHPAGE_BITMAP "${ART}/welcome.bmp"
!define MUI_UNWELCOMEFINISHPAGE_BITMAP "${ART}/welcome.bmp"
!define MUI_ABORTWARNING
!define MUI_ABORTWARNING_TEXT "Прервать установку Luminary?"

!define MUI_WELCOMEPAGE_TITLE "Добро пожаловать в Luminary"
!define MUI_WELCOMEPAGE_TEXT "Сейчас на компьютер будет установлен лаунчер сервера Luminary вместе со всем, что нужно для игры: Java, Python и установщиком Minecraft.$\r$\n$\r$\nПрава администратора не нужны. Если лаунчер уже установлен, он обновится, а миры, настройки и вход в аккаунт сохранятся.$\r$\n$\r$\nНажми «Далее», чтобы продолжить."
!define MUI_PAGE_CUSTOMFUNCTION_SHOW WelcomeShow
!insertmacro MUI_PAGE_WELCOME

!define MUI_DIRECTORYPAGE_TEXT_TOP "Папка, в которую будет установлен Luminary. Выбирай папку, куда у тебя есть права на запись (не Program Files)."
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES

!define MUI_FINISHPAGE_TITLE "Luminary установлен"
!define MUI_FINISHPAGE_TEXT "Ярлык Luminary появился на рабочем столе и в меню «Пуск».$\r$\n$\r$\nПри первом запуске лаунчер скачает Minecraft 1.20.1 и сборку — это займёт несколько минут."
!define MUI_FINISHPAGE_RUN
!define MUI_FINISHPAGE_RUN_TEXT "Играть сейчас"
!define MUI_FINISHPAGE_RUN_FUNCTION LaunchLuminary
!define MUI_PAGE_CUSTOMFUNCTION_SHOW FinishShow
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

!insertmacro MUI_LANGUAGE "Russian"

; Dark welcome/finish pages. Classic (unthemed) style on the checkbox so its text colour applies.
Function WelcomeShow
  SetCtlColors $mui.WelcomePage "D4EAE6" "0A1418"
  SetCtlColors $mui.WelcomePage.Title "3DD6C4" "0A1418"
  SetCtlColors $mui.WelcomePage.Text "D4EAE6" "0A1418"
FunctionEnd

Function FinishShow
  SetCtlColors $mui.FinishPage "D4EAE6" "0A1418"
  SetCtlColors $mui.FinishPage.Title "3DD6C4" "0A1418"
  SetCtlColors $mui.FinishPage.Text "D4EAE6" "0A1418"
  System::Call 'uxtheme::SetWindowTheme(p $mui.FinishPage.Run, w " ", w " ")'
  SetCtlColors $mui.FinishPage.Run "3DD6C4" "0A1418"
FunctionEnd

; The shortcut and "Играть сейчас" start the bundled pythonw.exe (Windows GUI program, no console).
Function LaunchLuminary
  SetOutPath "$INSTDIR"
  Exec '"$INSTDIR\python\pythonw.exe" "$INSTDIR\launcher.py"'
FunctionEnd

Section "Luminary" SecMain
  SectionIn RO
  SetOutPath "$INSTDIR"
  ; Old program code goes away so no stale modules linger; player data folders are not touched.
  RMDir /r "$INSTDIR\luminary_app"
  File /r "${SRC}/*"
  File "/oname=luminary-shortcut.ico" "${ART}/luminary.ico"
  WriteUninstaller "$INSTDIR\uninstall.exe"

  SetOutPath "$INSTDIR"
  CreateShortCut "$DESKTOP\Luminary.lnk" "$INSTDIR\python\pythonw.exe" '"$INSTDIR\launcher.py"' "$INSTDIR\luminary-shortcut.ico" 0 SW_SHOWNORMAL "" "Лаунчер сервера Luminary"
  CreateDirectory "$SMPROGRAMS\Luminary"
  CreateShortCut "$SMPROGRAMS\Luminary\Luminary.lnk" "$INSTDIR\python\pythonw.exe" '"$INSTDIR\launcher.py"' "$INSTDIR\luminary-shortcut.ico" 0 SW_SHOWNORMAL "" "Лаунчер сервера Luminary"
  CreateShortCut "$SMPROGRAMS\Luminary\Удалить Luminary.lnk" "$INSTDIR\uninstall.exe"

  WriteRegStr HKCU "${UNINST_KEY}" "DisplayName" "Luminary Launcher"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayVersion" "${VERSION}"
  WriteRegStr HKCU "${UNINST_KEY}" "Publisher" "Luminary"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayIcon" "$INSTDIR\luminary-shortcut.ico"
  WriteRegStr HKCU "${UNINST_KEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "${UNINST_KEY}" "UninstallString" '"$INSTDIR\uninstall.exe"'
  WriteRegDWORD HKCU "${UNINST_KEY}" "NoModify" 1
  WriteRegDWORD HKCU "${UNINST_KEY}" "NoRepair" 1
SectionEnd

; Removes only what the installer put there (never "RMDir /r $INSTDIR": the folder may be user-chosen).
Section "Uninstall"
  Delete "$DESKTOP\Luminary.lnk"
  RMDir /r "$SMPROGRAMS\Luminary"
  RMDir /r "$INSTDIR\python"
  RMDir /r "$INSTDIR\jre"
  RMDir /r "$INSTDIR\luminary_app"
  RMDir /r "$INSTDIR\assets"
  RMDir /r "$INSTDIR\sounds"
  Delete "$INSTDIR\Luminary.exe"
  Delete "$INSTDIR\LuminaryLauncher.exe"
  Delete "$INSTDIR\launcher.py"
  Delete "$INSTDIR\profiles.json"
  Delete "$INSTDIR\config_updater.json"
  Delete "$INSTDIR\run.bat"
  Delete "$INSTDIR\run-debug.bat"
  Delete "$INSTDIR\README.md"
  Delete "$INSTDIR\luminary.ico"
  Delete "$INSTDIR\luminary-shortcut.ico"
  Delete "$INSTDIR\SHA256SUMS.txt"
  Delete "$INSTDIR\release-info.json"
  MessageBox MB_YESNO|MB_ICONQUESTION "Удалить также игровые данные Luminary (миры, скачанные файлы игры, настройки, журналы)?" /SD IDNO IDNO keep_data
    RMDir /r "$INSTDIR\.minecraft"
    RMDir /r "$INSTDIR\launcher_data"
    RMDir /r "$INSTDIR\logs"
    RMDir /r "$INSTDIR\runtime"
  keep_data:
  Delete "$INSTDIR\uninstall.exe"
  RMDir "$INSTDIR"
  DeleteRegKey HKCU "${UNINST_KEY}"
SectionEnd
