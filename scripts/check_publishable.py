"""Read-only checks of files Git would publish; run after git init. No staging."""
import pathlib
import re
import subprocess
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent


def git(*args):
    return subprocess.check_output(['git', '-C', str(ROOT), *args])


def main():
    git('rev-parse', '--git-dir')
    names = set(git('ls-files', '--cached', '--others', '--exclude-standard', '-z').decode().split('\0')) - {''}
    errors = []
    required = ['PocketTTS-english-FP32.zip', 'app/src/main/assets/deepfilter/deepfilter.onnx',
                'gradle/wrapper/gradle-wrapper.jar', 'app/src/main/assets/deepfilter/NOTICES.txt']
    for name in required:
        if name not in names or not (ROOT / name).is_file():
            errors.append(f'Required asset absent or ignored: {name}')
    archive = ROOT / required[0]
    if archive.is_file() and not zipfile.is_zipfile(archive):
        errors.append('Pocket archive is not a ZIP (run git lfs pull if it is a pointer)')
    if b'filter: lfs' not in git('check-attr', 'filter', '--', required[0]):
        errors.append('Pocket archive lacks its LFS attribute')
    sensitive_name = re.compile(r'(^|/)(local\.properties|\.env(?:\..*)?|keystore\.properties)$|\.(jks|keystore|p12|pfx|pem|key|apk|aab)$', re.I)
    secret = re.compile(r'BEGIN (?:RSA |OPENSSH |EC |DSA )?PRIVATE KEY|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AIza[A-Za-z0-9_-]{30,}|sk-[A-Za-z0-9_-]{20,}')
    personal = re.compile(r'[A-Za-z]:[\\/]Users[\\/][^\s/\\]+|/home/[^/\s]+/|/mnt/[a-z]/')
    for name in sorted(names):
        path = ROOT / name
        if sensitive_name.search(name) or any(part in ('.git', '.gradle', '.cxx', 'build', '.idea') for part in path.relative_to(ROOT).parts):
            errors.append(f'Local/generated/sensitive file would publish: {name}')
        if not path.is_file():
            continue
        if path.stat().st_size > 100 * 1024 * 1024 and name != required[0]:
            errors.append(f'Large non-LFS file: {name}')
        if path.stat().st_size > 2 * 1024 * 1024:
            continue
        try:
            text = path.read_text(encoding='utf-8')
        except (UnicodeError, OSError):
            continue
        if secret.search(text):
            errors.append(f'Possible secret (review locally): {name}')
        if name != 'scripts/check_publishable.py' and personal.search(text):
            errors.append(f'Personal/local path: {name}')
    # Verify all local README Markdown links and HTML image sources.
    readme = (ROOT / 'README.md').read_text(encoding='utf-8')
    links = re.findall(r'\]\(([^)]+)\)|src="([^"]+)"', readme)
    for pair in links:
        link = next(value for value in pair if value).split('#')[0]
        if link and '://' not in link and not (ROOT / link).exists():
            errors.append(f'Broken README link: {link}')
    if errors:
        print('\n'.join(errors)); return 1
    print(f'Publication checks passed for {len(names)} candidate files. No files staged or uploaded.')
    print('Pattern scanning is not a guarantee: manually review your final diff and signing setup.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
