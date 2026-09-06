"""Release-source scan. Never print matched private values."""
from pathlib import Path
import re, sys
root = Path(__file__).resolve().parents[1]
violations = []
patterns = {
    'personal email': r'[\w.+-]+@(?:gmail|hotmail|outlook|yahoo)\.[a-z]+',
    'numeric phone example': r'(?:مثال|Example|example):\s*\+?\d[\d -]{7,}',
    'private key': r'-----BEGIN (?:RSA |EC )?PRIVATE KEY-----',
}
for base in [root/'android/app/src', root/'server/src']:
    for p in base.rglob('*'):
        if not p.is_file(): continue
        text = p.read_text(errors='replace')
        for label, pattern in patterns.items():
            if re.search(pattern, text): violations.append((p.relative_to(root), label))
for p,label in violations: print(f'{p}: {label}')
print(f'Privacy scan: {len(violations)} violations')
sys.exit(bool(violations))
