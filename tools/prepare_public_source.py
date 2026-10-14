"""Build an allowlisted, scanned source-only publishing snapshot. Does not upload anything."""
import hashlib
import json
from pathlib import Path
import re
import shutil
import zipfile

ROOT_FILES = {
    '.gitignore', '.gitattributes', '.env.example', 'README.md', 'PRIVACY.md',
    'SECURITY.md', 'THIRD_PARTY.md', 'build.gradle.kts', 'settings.gradle.kts',
    'gradle.properties', 'gradlew', 'gradlew.bat',
}
PUBLIC_ROOTS = ('android/app/src', 'docs', 'tools', 'gradle')
EXCLUDED_PARTS = {'.git', '.archive', '.toolchain', '.gradle', '.kotlin', '.github-ready',
                  '__pycache__', 'build', '.cxx', 'private', 'validation-images'}
EXCLUDED_SUFFIXES = {'.apk', '.aab', '.log', '.pyc', '.jks', '.keystore', '.p12', '.pfx', '.pem'}
REVIEWED_DEMO_IMAGES = {'home.png', 'personas.png', 'reply-keyboard.png', 'rewrite-keyboard.png', 'relationships.png'}
TEXT_SUFFIXES = {'.md', '.kt', '.kts', '.py', '.ps1', '.xml', '.json', '.properties',
                 '.cpp', '.h', '.txt', '.bat', '.example'}
RULES = {
    'credential-like token': re.compile(r'\b(?:sk|ms)-[A-Za-z0-9_-]{20,}\b'),
    'private key': re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'),
    'personal Windows path': re.compile(r'\b[A-Za-z]:[\\/]\S+'),
    'hardcoded device connection': re.compile(r'\.connect\([\"\'][A-Za-z0-9]{8,}[\"\']\)'),
}


def public_files(root):
    result = [root / name for name in ROOT_FILES if (root / name).is_file()]
    for prefix in PUBLIC_ROOTS:
        for path in (root / prefix).rglob('*'):
            if not path.is_file():
                continue
            relative = path.relative_to(root)
            if not path.resolve().is_relative_to(root.resolve()) or path.is_symlink():
                raise SystemExit('Publishing refused a symlink or external source path')
            if relative.parts[:2] == ('docs', 'images') and path.name not in REVIEWED_DEMO_IMAGES:
                continue
            if any(part in EXCLUDED_PARTS for part in relative.parts):
                continue
            if path.suffix.lower() in EXCLUDED_SUFFIXES:
                continue
            result.append(path)
    fixture = root / 'screenshots/fixtures/ocr-chinese.png'
    if fixture.is_file():
        result.append(fixture)
    app_gradle = root / 'android/app/build.gradle.kts'
    if app_gradle.is_file():
        result.append(app_gradle)
    return sorted(set(result))


def scan(files, root):
    findings = []
    for path in files:
        if path.suffix.lower() not in TEXT_SUFFIXES and path.name not in {'gradlew', '.gitignore', '.gitattributes'}:
            continue
        content = path.read_text(encoding='utf-8-sig')
        for number, line in enumerate(content.splitlines(), 1):
            for label, pattern in RULES.items():
                if pattern.search(line):
                    findings.append({'path': path.relative_to(root).as_posix(), 'line': number, 'rule': label})
    return findings


def main():
    root = Path(__file__).resolve().parents[1]
    files = public_files(root)
    findings = scan(files, root)
    staging = root / '.github-ready'
    staging.mkdir(exist_ok=True)
    audit = {'filesScanned': len(files), 'findings': findings,
             'scope': 'allowlisted publishing snapshot; original private/local directories are excluded'}
    (staging / 'privacy-audit.json').write_text(json.dumps(audit, ensure_ascii=False, indent=2), encoding='utf-8')
    if findings:
        print(json.dumps(audit, ensure_ascii=False, indent=2))
        raise SystemExit('Publishing snapshot refused: inspect findings without logging matched values.')
    destination = staging / 'SmartKey'
    if destination.exists():
        raise SystemExit('Existing snapshot preserved. Use a fresh staging location before preparing another snapshot.')
    destination.mkdir()
    manifest = []
    for path in files:
        relative = path.relative_to(root)
        target = destination / relative
        if not target.resolve().is_relative_to(destination.resolve()):
            raise SystemExit('Invalid publishing path')
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
        manifest.append({'path': relative.as_posix(), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
    (staging / 'source-manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    package = staging / 'SmartKey-source.zip'
    with zipfile.ZipFile(package, 'w', zipfile.ZIP_DEFLATED) as output:
        for item in manifest:
            output.write(destination / item['path'], 'SmartKey/' + item['path'])
    print(f'Prepared {len(manifest)} scanned files in .github-ready/SmartKey and .github-ready/SmartKey-source.zip; no upload performed.')


if __name__ == '__main__':
    main()
