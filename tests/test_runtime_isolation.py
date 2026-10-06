import ast
from pathlib import Path
import unittest
import sys
import subprocess

ROOT=Path(__file__).resolve().parents[1]


class RuntimeIsolationTests(unittest.TestCase):
    def test_runtime_imports_are_only_stdlib_or_local_python_service(self):
        for path in (ROOT/'python_service').glob('*.py'):
            tree=ast.parse(path.read_text(encoding='utf-8'))
            for node in ast.walk(tree):
                if isinstance(node,ast.Import):names=[n.name.split('.')[0] for n in node.names]
                elif isinstance(node,ast.ImportFrom):
                    if node.level:continue
                    names=[(node.module or '').split('.')[0]]
                else:continue
                for name in names:
                    self.assertTrue(name in sys.stdlib_module_names or name=='__future__',f'Nonstdlib runtime dependency in {path.name}')
    def test_no_research_or_personal_java_tree_shipped(self):
        if (ROOT/'.git').exists():
            result=subprocess.run(['git','ls-files'],cwd=ROOT,capture_output=True,text=True,check=True)
            for name in result.stdout.splitlines():
                self.assertFalse(name.split('/')[0] in {'data','papers','ad-causalrisk-javaweb','.runtime','runtime'})
        # Backend/frontend can grow freely through legitimate team PRs. Local
        # ignored runtime files do not count as shipped source or fail this test.
        ignored=(ROOT/'.gitignore').read_text(encoding='utf-8')
        for name in ('data/','papers/','.runtime/','runtime/'):
            self.assertIn(name,ignored)


if __name__=='__main__':unittest.main()
