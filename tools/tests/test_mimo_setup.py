import hashlib
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


class MimoSetupTest(unittest.TestCase):
    def run_setup(self, kind, key):
        temp = tempfile.TemporaryDirectory(prefix="manman-mimo-test-")
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        (root / "bin").mkdir()
        ssh = root / "bin" / "ssh"
        ssh.write_text('''#!/usr/bin/env bash
set -e
case "$*" in
  *'test ! -e'*) exit 0 ;;
  *'/etc/manman/mimo.key'*) cat > "$MANMAN_SCRIPT_TEST/key" ;;
  *'/etc/manman/mimo-pairing.sha256'*) cat > "$MANMAN_SCRIPT_TEST/hash" ;;
  *'tee -a'*) cat > "$MANMAN_SCRIPT_TEST/env" ;;
  *) exit 1 ;;
esac
''', encoding="utf-8")
        ssh.chmod(0o755)
        env = dict(os.environ, MANMAN_SCRIPT_TEST=str(root), PATH=str(root / "bin") + os.pathsep + os.environ["PATH"])
        result = subprocess.run(["bash", "tools/设置MiMo密钥.command"], input=f"{kind}\n{key}\n\n", text=True, capture_output=True, env=env)
        return root, result

    def test_official_key_types_and_pairing_hash(self):
        for kind, key, endpoint in [("1", "sk-" + "test-placeholder-123456", "api.xiaomimimo.com"),
                                    ("2", "tp-test-placeholder-123456", "token-plan-cn.xiaomimimo.com")]:
            with self.subTest(kind=kind):
                root, result = self.run_setup(kind, key)
                self.assertEqual(result.returncode, 0)
                self.assertEqual((root / "key").read_text(), key)
                code = re.search(r"(?m)^\d{8}$", result.stdout).group()
                self.assertEqual((root / "hash").read_text(), hashlib.sha256(code.encode()).hexdigest())
                self.assertIn(endpoint, (root / "env").read_text())
                self.assertNotIn(key, result.stdout + result.stderr)

    def test_rejects_wrong_key_type_before_install(self):
        root, result = self.run_setup("1", "tp-test-placeholder-123456")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((root / "key").exists())


if __name__ == "__main__":
    unittest.main()
