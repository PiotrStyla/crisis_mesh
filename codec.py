"""Versioned application payloads; no radio framing, authentication or transport."""
import json

FIELDS = ('people', 'children', 'hazard', 'need')
HAZARDS = ('unknown', 'fire', 'flood', 'collapse')
NEEDS = ('unknown', 'rescue', 'medical', 'water')
MAX_BYTES = 80


def validate_report(value):
    if not isinstance(value, dict) or set(value) != set(FIELDS):
        raise ValueError('Expected exactly people, children, hazard, need')
    for field in FIELDS[:2]:
        n = value[field]
        if n is not None and (type(n) is not int or not 0 <= n <= 254):
            raise ValueError(f'{field}: expected null or integer 0..254')
    if value['hazard'] not in HAZARDS or value['need'] not in NEEDS:
        raise ValueError('Unknown hazard or need code')
    people, children = value['people'], value['children']
    if people is not None and children is not None and children > people:
        raise ValueError('Children are a subset of total people')
    return {field: value[field] for field in FIELDS}


def parse_report(text):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f'Duplicate field: {key}')
            result[key] = value
        return result
    try:
        return validate_report(json.loads(text, object_pairs_hook=unique))
    except (TypeError, json.JSONDecodeError) as exc:
        raise ValueError('Expected a single JSON object, without prose') from exc


def pack(report):
    report = validate_report(report)
    count = lambda n: '?' if n is None else str(n)
    data = '|'.join(('CM1', count(report['people']), count(report['children']),
                     report['hazard'], report['need'])).encode('ascii')
    if len(data) > MAX_BYTES:
        raise ValueError('Packet exceeds 80 bytes')
    return data


def unpack(data):
    if not isinstance(data, bytes) or len(data) > MAX_BYTES:
        raise ValueError('Expected bytes, at most 80')
    try:
        version, people, children, hazard, need = data.decode('ascii').split('|')
        if version != 'CM1':
            raise ValueError('Unsupported packet version')
        def count(s):
            if s == '?':
                return None
            if not s.isascii() or not s.isdecimal():
                raise ValueError('Invalid count')
            return int(s)
        report = validate_report(dict(people=count(people), children=count(children),
                                      hazard=hazard, need=need))
        if pack(report) != data:
            raise ValueError('Noncanonical packet')
        return report
    except (UnicodeError, TypeError) as exc:
        raise ValueError('Malformed packet') from exc


def render(report):
    r = validate_report(report)
    count = lambda n: 'nie podano' if n is None else str(n)
    hazards = dict(zip(HAZARDS, ('nie ustalono', 'pożar', 'powódź', 'zawalenie')))
    needs = dict(zip(NEEDS, ('nie ustalono', 'ratunek', 'pomoc medyczna', 'woda')))
    return (f"Osoby łącznie: {count(r['people'])}; w tym dzieci: {count(r['children'])}. "
            f"Zgłoszone zagrożenie: {hazards[r['hazard']]}. "
            f"Zgłoszona potrzeba: {needs[r['need']]}. "
            'Skrót czterech pól, nie pełna treść zgłoszenia.')
