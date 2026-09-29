from pathlib import Path

path = Path('app/src/main/kotlin/com/metrolist/music/intelligence/MusicIntelligence.kt')
text = path.read_text(encoding='utf-8')
old = r'.split(Regex("\s+(?:-|–|—|\|)\s+"))'
new = r'.split(Regex("""\s+(?:-|–|—|\|)\s+"""))'
count = text.count(old)
if count != 1:
    raise RuntimeError(f'Attesa una regex da correggere, trovate {count}')
path.write_text(text.replace(old, new, 1), encoding='utf-8')
print('Regex Kotlin LAB12 corretta')
