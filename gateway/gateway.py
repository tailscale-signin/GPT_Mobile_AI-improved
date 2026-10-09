"""Stable launch entry point; validate every companion before loading v14."""
from pathlib import Path
from v14.package import validate_package

validate_package(Path(__file__).parent)
if __name__ == "__main__":
    from gateway_v14 import main
    main()
else:
    from gateway_v14 import app
