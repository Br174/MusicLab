#!/usr/bin/env python3
from pathlib import Path
import runpy
runpy.run_path("tools/universal-apk-builder/prepare-turbo-r24-qobuz-anr-env.py",run_name="__main__")
p=Path("uab-project.env")
text=p.read_text()
assert "Revisione-24" in text and "33096" in text
new=text.replace("Revisione-24","Revisione-25").replace("33096","33097")
assert new!=text
p.write_text(new)
print("[R25 APK] Same signed MusicLab Turbo family, Android versionCode=33097")
