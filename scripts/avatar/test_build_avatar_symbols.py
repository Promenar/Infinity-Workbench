"""精选目录生成器的无字体校验回归。"""
import csv
import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('build_avatar_symbols', Path(__file__).with_name('build_avatar_symbols.py'))
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


class CatalogValidationTest(unittest.TestCase):
    def setUp(self):
        with Path(__file__).with_name('avatar_symbols.tsv').open() as handle:
            self.rows = list(csv.DictReader(handle, delimiter='\t'))
        self.codepoints = {r['name']: i + 0xe000 for i, r in enumerate(self.rows)}

    def validate(self, rows, codepoints=None):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'source.tsv'
            with path.open('w') as handle:
                writer = csv.DictWriter(handle, fieldnames=self.rows[0].keys(), delimiter='\t')
                writer.writeheader()
                writer.writerows(rows)
            return module.read_selection(path, codepoints or self.codepoints)

    def test_valid(self):
        self.assertEqual(390, len(self.validate(self.rows)))

    def test_duplicate(self):
        self.rows[1]['name'] = self.rows[0]['name']
        with self.assertRaises(ValueError): self.validate(self.rows)

    def test_unknown(self):
        self.rows[0]['name'] = 'not_in_font'
        with self.assertRaises(ValueError): self.validate(self.rows)

    def test_category(self):
        self.rows[0]['category'] = 'OTHER'
        with self.assertRaises(ValueError): self.validate(self.rows)

    def test_popular(self):
        for row in self.rows: row['popular'] = '0'
        with self.assertRaises(ValueError): self.validate(self.rows)

    def test_duplicate_codepoints(self):
        points = dict(self.codepoints)
        points[self.rows[1]['name']] = points[self.rows[0]['name']]
        with self.assertRaises(ValueError): self.validate(self.rows, points)


if __name__ == '__main__': unittest.main()
