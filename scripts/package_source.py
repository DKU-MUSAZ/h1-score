"""Create a source/assets bundle without build caches or personal IDE settings."""
from pathlib import Path
import hashlib
import json
import zipfile

root = Path(__file__).resolve().parents[1]
output = root / "artifacts" / "h1-score-source-0.2.0.zip"
excluded = {".gradle", ".kotlin", ".idea", "build", ".git", "artifacts", "__pycache__"}
files = [
    p for p in sorted(root.rglob("*"))
    if p.is_file()
    and not any(part in excluded for part in p.relative_to(root).parts)
    and p.name not in {"local.properties", ".DS_Store"}
]
output.parent.mkdir(exist_ok=True)
with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
    for file in files:
        archive.write(file, Path("h1-score") / file.relative_to(root))
manifest = {
    "version": "0.2.0-prototype",
    "source_archive": output.name,
    "file_count": len(files),
    "sha256": hashlib.sha256(output.read_bytes()).hexdigest(),
    "files": {
        str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
        for p in files
    },
}
(output.parent / "source-manifest.json").write_text(
    json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
)
print(f"{output.name}: {len(files)} files, {output.stat().st_size:,} bytes")
