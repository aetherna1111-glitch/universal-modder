@echo off
rem Builds Luminary.exe (GUI subsystem, no console window) with the C# compiler that ships with
rem .NET Framework 4.x on every Windows 10/11 PC. No SDK or downloads needed.
setlocal
cd /d "%~dp0"
set CSC=%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe
if not exist "%CSC%" set CSC=%WINDIR%\Microsoft.NET\Framework\v4.0.30319\csc.exe
if not exist "%CSC%" (
  echo .NET Framework 4 C# compiler not found.
  exit /b 1
)
"%CSC%" /nologo /target:winexe /optimize+ /platform:anycpu /win32icon:luminary.ico ^
  /reference:System.Windows.Forms.dll /out:Luminary.exe starter\LuminaryStart.cs || exit /b 1
echo Built Luminary.exe
