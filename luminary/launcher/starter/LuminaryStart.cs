// LuminaryStart.cs - Luminary.exe, the GUI-subsystem starter for the launcher.
//
// Why: double-clicking run.bat opens cmd.exe (a console window) before pythonw takes over. This exe is
// built with /target:winexe (Windows GUI subsystem), so Windows never creates a console for it, and it
// starts python\pythonw.exe launcher.py (also GUI subsystem) with CreateNoWindow. It forwards its
// arguments, uses the launcher folder as working directory and reports missing files in a message box.
//
// C# 5 only (the compiler that ships with .NET Framework 4.x): no string interpolation, no "out var",
// no expression-bodied members. Build: build_starter.bat
using System;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Windows.Forms;

[assembly: System.Reflection.AssemblyTitle("Luminary Launcher")]
[assembly: System.Reflection.AssemblyProduct("Luminary")]
[assembly: System.Reflection.AssemblyVersion("2.0.0.0")]

internal static class LuminaryStart
{
    [STAThread]
    private static int Main(string[] args)
    {
        string dir = AppDomain.CurrentDomain.BaseDirectory;
        string pythonw = Path.Combine(Path.Combine(dir, "python"), "pythonw.exe");
        string script = Path.Combine(dir, "launcher.py");

        if (!File.Exists(pythonw))
        {
            Fail("Не найден " + pythonw + "\n\nПереустановите лаунчер: папка python должна лежать рядом с Luminary.exe.");
            return 2;
        }
        if (!File.Exists(script))
        {
            Fail("Не найден " + script + "\n\nПереустановите лаунчер.");
            return 2;
        }

        StringBuilder cmd = new StringBuilder();
        cmd.Append(Quote(script));
        foreach (string a in args)
        {
            cmd.Append(' ');
            cmd.Append(Quote(a));
        }

        ProcessStartInfo psi = new ProcessStartInfo();
        psi.FileName = pythonw;
        psi.Arguments = cmd.ToString();
        psi.WorkingDirectory = dir;
        psi.UseShellExecute = false;
        psi.CreateNoWindow = true;
        try
        {
            using (Process.Start(psi)) { }
        }
        catch (Exception e)
        {
            Fail("Не удалось запустить лаунчер:\n" + e.Message);
            return 1;
        }
        return 0;
    }

    private static void Fail(string message)
    {
        MessageBox.Show(message, "Luminary", MessageBoxButtons.OK, MessageBoxIcon.Error);
    }

    // Quote one argument so CommandLineToArgvW (used by Python) reads it back unchanged.
    internal static string Quote(string arg)
    {
        if (arg.Length > 0 && arg.IndexOfAny(new char[] { ' ', '\t', '"' }) < 0)
        {
            return arg;
        }
        StringBuilder sb = new StringBuilder("\"");
        int backslashes = 0;
        foreach (char c in arg)
        {
            if (c == '\\')
            {
                backslashes++;
                continue;
            }
            if (c == '"')
            {
                sb.Append('\\', backslashes * 2 + 1);
            }
            else
            {
                sb.Append('\\', backslashes);
            }
            backslashes = 0;
            sb.Append(c);
        }
        sb.Append('\\', backslashes * 2);
        sb.Append('"');
        return sb.ToString();
    }
}
