import unittest

from codec import MAX_BYTES, pack, unpack, parse_report, render, validate_report


class PacketTests(unittest.TestCase):
    def report(self, **changes):
        r = dict(people=None, children=None, hazard='unknown', need='unknown')
        return r | changes

    def test_unknown_and_zero_are_distinct(self):
        unknown = self.report()
        zero = self.report(people=0, children=0)
        self.assertNotEqual(pack(unknown), pack(zero))
        self.assertEqual(unpack(pack(unknown)), unknown)
        self.assertEqual(unpack(pack(zero)), zero)
        self.assertIn('Osoby łącznie: nie podano', render(unknown))
        self.assertIn('Osoby łącznie: 0', render(zero))

    def test_maximum_and_all_category_roundtrips(self):
        for hazard in ('unknown', 'fire', 'flood', 'collapse'):
            for need in ('unknown', 'rescue', 'medical', 'water'):
                r = self.report(people=254, children=254, hazard=hazard, need=need)
                with self.subTest(r=r):
                    self.assertEqual(unpack(pack(r)), r)
                    self.assertLessEqual(len(pack(r)), MAX_BYTES)

    def test_invalid_counts_are_not_coerced(self):
        for n in (True, False, -1, 255, 1.0, '2', [], {}):
            for field in ('people', 'children'):
                with self.subTest(field=field, value=n), self.assertRaises(ValueError):
                    validate_report(self.report(**{field:n}))

    def test_children_are_subset(self):
        with self.assertRaises(ValueError):
            pack(self.report(people=1, children=2))
        r = self.report(children=2)
        self.assertEqual(unpack(pack(r)), r)

    def test_unknown_fields_and_categories_rejected(self):
        for r in (self.report(severity=5), self.report(hazard='safe'),
                  self.report(need='amputation'), {'people':2}):
            with self.subTest(r=r), self.assertRaises(ValueError):
                pack(r)

    def test_noncanonical_or_corrupt_payload_rejected(self):
        for raw in (b'CM2|1|0|fire|rescue', b'CM1|01|0|fire|rescue',
                    b'CM1|+1|0|fire|rescue', b'CM1|1|0|fire|rescue\n',
                    b'CM1|1|0|fire|rescue|extra', b'CM1|1|0|fire',
                    b'CM1|1|2|fire|rescue', b'\xff', b'a'*81, b'',
                    b'CM1|255|0|fire|rescue', b'CM1|-1|0|fire|rescue'):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                unpack(raw)

    def test_prose_duplicate_keys_and_json_coercions_rejected(self):
        for text in ('```json\n{}\n```', '{} trailing',
                     '{"people":1,"people":2,"children":0,"hazard":"fire","need":"rescue"}',
                     '{"people":true,"children":0,"hazard":"fire","need":"rescue"}',
                     'null', '[]'):
            with self.subTest(text=text), self.assertRaises(ValueError):
                parse_report(text)


if __name__ == '__main__':
    unittest.main()
