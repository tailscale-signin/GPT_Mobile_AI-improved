"""Stable launch entry point for gateway v13."""
if __name__ == "__main__":
    import runpy
    from pathlib import Path
    runpy.run_path(str(Path(__file__).with_name("gateway_v13.py")), run_name="__main__")
else:
    from gateway_v13 import app
