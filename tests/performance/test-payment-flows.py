#!/usr/bin/env python3
"""LocalStack/DynamoDB integration test for approved and declined simulated payments."""
import base64
import hashlib
import hmac
import json
import os
import time
import uuid
from datetime import datetime, timezone, timedelta
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

BASE = os.getenv('TICKETING_BASE_URL', 'http://localhost:8080').rstrip('/')
OUTPUT = Path('tests/performance')


def secret_from_env():
    raw = os.getenv('TICKETING_SECURITY_LOCAL_JWT_SECRET')
    if raw:
        return raw
    for line in Path('.env').read_text().splitlines():
        if line.startswith('TICKETING_SECURITY_LOCAL_JWT_SECRET='):
            return line.partition('=')[2].strip().strip('"').strip("'")
    raise RuntimeError('Missing TICKETING_SECURITY_LOCAL_JWT_SECRET')


def b64(value):
    return base64.urlsafe_b64encode(value).rstrip(b'=').decode('ascii')


def jwt(secret, subject, scope):
    now = int(time.time())
    header = b64(json.dumps({'alg': 'HS256', 'typ': 'JWT'}, separators=(',', ':')).encode())
    payload = b64(json.dumps({'sub': subject, 'scope': scope, 'iat': now, 'exp': now + 3600}, separators=(',', ':')).encode())
    signature = b64(hmac.new(secret.encode(), f'{header}.{payload}'.encode(), hashlib.sha256).digest())
    return f'{header}.{payload}.{signature}'


def request(method, url, token, body=None, headers=None):
    h = {'Authorization': f'Bearer {token}'}
    if body is not None:
        h['Content-Type'] = 'application/json'
    h.update(headers or {})
    req = Request(BASE + url, method=method, headers=h,
                  data=json.dumps(body).encode() if body is not None else None)
    try:
        with urlopen(req, timeout=15) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else {}
    except HTTPError as exc:
        raise AssertionError(f'{method} {url}: HTTP {exc.code} {exc.read().decode()}') from exc


def poll_order(order_id, token, terminal_states, timeout=65):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        status, last = request('GET', f'/api/v1/orders/{order_id}', token)
        assert status == 200
        if last['status'] in terminal_states:
            return last
        time.sleep(0.5)
    raise AssertionError(f'Order {order_id} did not reach {terminal_states}; last={last}')


def inventory(event_id, token):
    status, value = request('GET', f'/api/v1/events/{event_id}/availability', token)
    assert status == 200
    assert sum(value[x] for x in ('available', 'reserved', 'pendingConfirmation', 'sold', 'complimentary')) == value['totalCapacity'], value
    return value


def poll_inventory(event_id, token, expected_status, timeout=20):
    """Wait for the read model/cache to agree with a terminal order state."""
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        last = inventory(event_id, token)
        if expected_status == 'SOLD':
            valid = (last['sold'] == 1 and last['available'] == 0
                     and last['reserved'] == 0 and last['pendingConfirmation'] == 0)
        else:
            valid = (last['sold'] == 0 and last['available'] == 1
                     and last['reserved'] == 0 and last['pendingConfirmation'] == 0)
        if valid:
            return last
        time.sleep(0.5)
    raise AssertionError(
        f'Inventory not synchronized with {expected_status} after {timeout}s: {last}'
    )


def scenario(name, payment_token, expected_status, secret):
    suffix = uuid.uuid4().hex
    admin = jwt(secret, 'payments-test-admin', 'ticketing:admin')
    buyer = jwt(secret, f'payments-test-buyer-{suffix}', 'ticketing:purchase')
    event_date = (datetime.now(timezone.utc) + timedelta(days=20)).isoformat().replace('+00:00', 'Z')
    code, event = request('POST', '/api/v1/events', admin, {
        'name': f'Payment integration {name} {suffix[:8]}',
        'venue': {'name': 'Local Test Venue', 'city': 'Bogota'},
        'eventDate': event_date,
        'totalCapacity': 1,
    })
    assert code == 201, (code, event)
    event_id = event['id']
    code, order = request('POST', '/api/v1/orders', buyer,
                          {'eventId': event_id, 'quantity': 1},
                          {'Idempotency-Key': str(uuid.uuid4())})
    assert code == 202, (code, order)
    order_id = order['orderId']
    reserved = poll_order(order_id, buyer, {'RESERVED', 'REJECTED'})
    assert reserved['status'] == 'RESERVED', reserved
    before = inventory(event_id, buyer)
    assert before['available'] == 0 and before['reserved'] == 1, before
    code, confirmation = request('POST', f'/api/v1/orders/{order_id}/confirm', buyer,
                                  {'paymentToken': payment_token})
    assert code == 202, (code, confirmation)
    final = poll_order(order_id, buyer, {'SOLD', 'REJECTED'})
    assert final['status'] == expected_status, final
    after = poll_inventory(event_id, buyer, expected_status)
    print(f'PASS {name}: {reserved["status"]} -> {final["status"]} | inventory={after}')
    return {'scenario': name, 'eventId': event_id, 'orderId': order_id,
            'before': before, 'after': after, 'finalOrder': final}


def main():
    secret = secret_from_env()
    results = []
    try:
        results.append(scenario('approved', 'tok_approved', 'SOLD', secret))
        results.append(scenario('declined', 'tok_decline', 'REJECTED', secret))
    except (AssertionError, URLError) as error:
        print('FAIL:', error)
        raise SystemExit(1)
    OUTPUT.mkdir(parents=True, exist_ok=True)
    path = OUTPUT / f'payment-flows-results-{uuid.uuid4().hex[:8]}.json'
    path.write_text(json.dumps({'status': 'PASS', 'results': results}, indent=2))
    print('PASS: approved and declined payment scenarios')
    print('Evidence:', path)

if __name__ == '__main__':
    main()
