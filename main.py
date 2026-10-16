"""Offline codec experiment. Nothing is sent to a network or emergency service."""
import argparse
import json
from pathlib import Path
import sys

from codec import pack, unpack, render, parse_report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    for name in ('extract', 'benchmark'):
        sub = commands.add_parser(name)
        sub.add_argument('--binary', required=True, help='Patched llama-completion executable')
        sub.add_argument('--model', required=True, help='GoLLeM q8out GGUF from watch project')
        if name == 'extract':
            sub.add_argument('--text-file', required=True, type=Path)
        else:
            sub.add_argument('--cases', type=Path, default=Path(__file__).with_name('cases.json'))
            sub.add_argument('--output', type=Path, default=Path('benchmark-results.json'))
    encoder = commands.add_parser('pack')
    encoder.add_argument('--report', type=Path, required=True, help='Human-reviewed four-field JSON')
    encoder.add_argument('--output', type=Path, required=True)
    decoder = commands.add_parser('unpack')
    decoder.add_argument('packet', type=Path)
    args = parser.parse_args()
    if args.command == 'pack':
        report = parse_report(args.report.read_text(encoding='utf-8'))
        payload = pack(report)
        args.output.write_bytes(payload)
        print(json.dumps(dict(bytes=len(payload), payload=payload.decode('ascii')), ensure_ascii=False))
    elif args.command == 'unpack':
        report = unpack(args.packet.read_bytes())
        print(json.dumps(report, ensure_ascii=False))
        print(render(report))
    else:
        from backend import LocalModel
        model = LocalModel(args.binary, args.model)
        if args.command == 'benchmark':
            from benchmark import run
            print(json.dumps(run(model, args.cases, args.output), indent=2))
        else:
            try:
                report = model.extract(args.text_file.read_text(encoding='utf-8'))
            except ValueError:
                print('REJECTED model output: '+model.last_output, file=sys.stderr)
                raise
            print('UNCONFIRMED MODEL PROPOSAL — requires human review', file=sys.stderr)
            print(json.dumps(report, ensure_ascii=False))
            print(render(report), file=sys.stderr)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, RuntimeError) as exc:
        print(f'ERROR: {exc}', file=sys.stderr)
        sys.exit(1)
