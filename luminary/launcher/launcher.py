# launcher.py - Luminary Launcher entry point (started by Luminary.exe, run.bat or `python launcher.py`).
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from luminary_app.main import main  # noqa: E402

if __name__ == "__main__":
    sys.exit(main())
