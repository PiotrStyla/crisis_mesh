"""Measure extraction against human labels, not model self-consistency."""
import hashlib
import json
from pathlib import Path
import platform
import subprocess
from datetime import datetime, timezone

from codec import FIELDS, pack, unpack, validate_report


def file_hash(path):
    with open(path, 'rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def run(model, cases_path, output_path):
    cases = json.loads(Path(cases_path).read_text(encoding='utf-8'))
    if not isinstance(cases, list) or not cases:
        raise ValueError('Expected nonempty list of labeled cases')
    rows = []
    for case in cases:
        expected = validate_report(case['expected'])
        oracle = pack(expected)
        assert unpack(oracle) == expected
        row = dict(id=case['id'], text=case['text'], expected=expected,
                   input_bytes=len(case['text'].encode('utf-8')),
                   oracle_packet_bytes=len(oracle), actual=None,
                   schema_valid=False, exact_match=False, correct_fields=0)
        try:
            actual = model.extract(case['text'])
            packet = pack(actual)
            if unpack(packet) != actual:
                raise AssertionError('Codec roundtrip failed')
            row.update(actual=actual, schema_valid=True,
                       exact_match=actual == expected,
                       correct_fields=sum(actual[k] == expected[k] for k in FIELDS),
                       packet_ascii=packet.decode('ascii'), packet_bytes=len(packet))
        except (ValueError, RuntimeError, subprocess.TimeoutExpired) as exc:
            row['error'] = f'{type(exc).__name__}: {exc}'
        row['raw_output'] = model.last_output
        row['seconds_including_load'] = round(model.last_seconds, 4)
        rows.append(row)
        print(f"{case['id']}: valid={row['schema_valid']} exact={row['exact_match']}", flush=True)
    summary = dict(total=len(rows), schema_valid=sum(r['schema_valid'] for r in rows),
                   exact_matches=sum(r['exact_match'] for r in rows),
                   correct_fields=sum(r['correct_fields'] for r in rows),
                   field_denominator=4 * len(rows),
                   input_bytes=sum(r['input_bytes'] for r in rows),
                   oracle_packet_bytes=sum(r['oracle_packet_bytes'] for r in rows))
    result = dict(timestamp_utc=datetime.now(timezone.utc).isoformat(),
                  host=platform.platform(), model=Path(model.model).name,
                  model_sha256=file_hash(model.model),
                  binary=Path(model.binary).name, binary_sha256=file_hash(model.binary),
                  cases_sha256=file_hash(cases_path),
                  settings=dict(threads=4, temperature=0, seed=1, context=1024, max_tokens=128),
                  warning='Four-field lossy projection; oracle size is not model success. '
                          'No radio overhead, no safety certification, desktop CPU not watch.',
                  summary=summary, cases=rows)
    Path(output_path).write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    return summary
