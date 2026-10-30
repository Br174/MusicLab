#!/usr/bin/env python3
"""Same-family LAB79 Revision 11: focused documentary and restoration regression repair."""
from pathlib import Path
import runpy

runpy.run_path('tools/universal-apk-builder/prepare-turbo-r10-empty-packet-env.py', run_name='__main__')
p = Path('uab-project.env')
before = p.read_text()
assert 'Revisione-10' in before and '33083' in before, 'R10 signed source metadata not found'
after = before.replace('Revisione-10', 'Revisione-11').replace('33083', '33084')
assert after != before and 'Revisione-10' not in after and '33083' not in after
p.write_text(after)
print('R11: same applicationId and original signing; versionCode 33084')
