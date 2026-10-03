"""Compatibility launcher. Install alongside gateway_v12.py and gateway_security.py."""
from pathlib import Path
import runpy

if __name__ == "__main__":
    runpy.run_path(str(Path(__file__).with_name("gateway_v12.py")), run_name="__main__")
else:
    from gateway_v12 import app
