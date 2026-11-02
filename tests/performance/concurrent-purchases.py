#!/usr/bin/env python3
"""Ticketing local concurrency integration test: 500 buyers, 100 tickets.
Requires only Python 3 stdlib. Uses a fresh event and distinct JWT subject/idempotency key.
"""
import base64
import concurrent.futures
import datetime as dt
import hashlib
import hmac
import json
import os
import pathlib
import sys
import time
import urllib.error
import urllib.request
import uuid
from collections import Counter

ROOT = pathlib.Path.cwd()
BASE = os.environ.get('TICKETING_BASE_URL', 'http://localhost:8080').rstrip('/')
BUYERS = int(os.environ.get('BUYERS', '500'))
CAPACITY = int(os.environ.get('CAPACITY', '100'))
WORKERS = int(os.environ.get('CONCURRENCY', '40'))
WAIT_SECONDS = int(os.environ.get('POLL_TIMEOUT', '120'))


def get_secret():
    secret = os.environ.get('TICKETING_SECURITY_LOCAL_JWT_SECRET')
    if not secret and (ROOT / '.env').is_file():
        for line in (ROOT / '.env').read_text().splitlines():
            if line.startswith('TICKETING_SECURITY_LOCAL_JWT_SECRET='):
                secret = line.split('=', 1)[1].strip().strip('"\'')
                break
    if not secret:
        sys.exit('ERROR: missing TICKETING_SECURITY_LOCAL_JWT_SECRET in .env or environment')
    return secret


SECRET = get_secret()


def b64(raw):
    return base64.urlsafe_b64encode(raw).rstrip(b'=').decode()


def token(subject, scope):
    now = int(time.time())
    header = b64(json.dumps({'alg': 'HS256', 'typ': 'JWT'}, separators=(',', ':')).encode())
    payload = b64(json.dumps({'sub': subject, 'scope': scope, 'iat': now, 'exp': now + 3600}, separators=(',', ':')).encode())
    text = f'{header}.{payload}'
    signature = b64(hmac.new(SECRET.encode(), text.encode(), hashlib.sha256).digest())
    return f'{text}.{signature}'


def request(method, path, subject, scope='ticketing:purchase', body=None, extra=None, timeout=12):
    headers = {'Authorization': f'Bearer {token(subject, scope)}', 'Accept': 'application/json'}
    if body is not None:
        headers['Content-Type'] = 'application/json'
    if extra:
        headers.update(extra)
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as error:
        raw = error.read()
        try:
            parsed = json.loads(raw)
        except (ValueError, UnicodeDecodeError):
            parsed = {'raw': raw.decode(errors='replace')[:250]}
        return error.code, parsed


def purchase(index, event_id, prefix):
    buyer = f'load-{prefix}-{index}'
    key = str(uuid.uuid4())
    try:
        status, data = request('POST', '/api/v1/orders', buyer,
                               body={'eventId': event_id, 'quantity': 1},
                               extra={'Idempotency-Key': key})
        return {'buyer': buyer, 'http': status, 'orderId': data.get('orderId'), 'error': data if status != 202 else None}
    except Exception as ex:
        return {'buyer': buyer, 'http': 'NETWORK_ERROR', 'error': str(ex)}


def get_order(p):
    try:
        status, data = request('GET', f'/api/v1/orders/{p["orderId"]}', p['buyer'])
        return p['orderId'], data.get('status') if status == 200 else f'HTTP_{status}'
    except Exception as ex:
        return p['orderId'], f'ERROR_{type(ex).__name__}'


def main():
    status, health = request('GET', '/actuator/health', 'probe')
    if status != 200:
        sys.exit(f'API not healthy: {status} {health}')
    future = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(days=30)).replace(microsecond=0).isoformat().replace('+00:00', 'Z')
    prefix = uuid.uuid4().hex[:10]
    status, event = request('POST', '/api/v1/events', f'load-admin-{prefix}', 'ticketing:admin',
                            {'name': f'Load Test {prefix}', 'venue': {'name': 'Local Test', 'city': 'Bogota'},
                             'eventDate': future, 'totalCapacity': CAPACITY})
    if status != 201 or not event.get('id'):
        sys.exit(f'Could not create event: HTTP {status} {event}')
    event_id = event['id']
    print(f'Fresh event: {event_id} | buyers={BUYERS} capacity={CAPACITY} workers={WORKERS}', flush=True)
    start = time.monotonic()
    with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS) as pool:
        purchases = list(pool.map(lambda i: purchase(i, event_id, prefix), range(BUYERS)))
    print(f'Admission complete in {time.monotonic()-start:.1f}s: {dict(Counter(str(p["http"]) for p in purchases))}', flush=True)
    accepted = [p for p in purchases if p['http'] == 202 and p['orderId']]
    statuses = {}
    deadline = time.monotonic() + WAIT_SECONDS
    while time.monotonic() < deadline:
        with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS) as pool:
            statuses = dict(pool.map(get_order, accepted))
        pending = sum(v == 'PENDING' for v in statuses.values())
        if pending == 0:
            break
        print(f'Waiting for workers: {pending} orders PENDING', flush=True)
        time.sleep(2)
    inv_http, inventory = request('GET', f'/api/v1/events/{event_id}/availability', f'load-admin-{prefix}', 'ticketing:admin')
    states = Counter(statuses.values())
    print('Order states:', dict(states))
    print('Inventory:', json.dumps(inventory, ensure_ascii=False))
    output = {'eventId': event_id, 'httpCounts': dict(Counter(str(p['http']) for p in purchases)),
              'orderStates': dict(states), 'inventory': inventory,
              'failedAdmissions': [p for p in purchases if p['http'] != 202][:20]}
    pathlib.Path('tests/performance').mkdir(parents=True, exist_ok=True)
    filename = pathlib.Path(f'tests/performance/results-{prefix}.json')
    filename.write_text(json.dumps(output, indent=2, ensure_ascii=False) + '\n')
    print('Evidence:', filename)
    if inv_http != 200:
        sys.exit(f'FAIL: inventory HTTP {inv_http}')
    fields = ['available', 'reserved', 'pendingConfirmation', 'sold', 'complimentary']
    invariant = sum(inventory.get(f, -999999) for f in fields) == CAPACITY
    no_oversell = inventory.get('available', -1) >= 0 and (inventory.get('reserved', 0) + inventory.get('pendingConfirmation', 0) + inventory.get('sold', 0) + inventory.get('complimentary', 0)) <= CAPACITY
    expected = states.get('RESERVED', 0) == CAPACITY and inventory.get('reserved') == CAPACITY and inventory.get('available') == 0
    if not (invariant and no_oversell and expected):
        sys.exit('NOT VERIFIED: check HTTP failures, states, or timeout; no pass claim')
    print('PASS: 100 reservations (or CAPACITY), zero oversell; inventory invariant holds')


if __name__ == '__main__':
    main()
