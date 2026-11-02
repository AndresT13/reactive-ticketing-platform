#!/usr/bin/env python3
"""Two-phase local SQS worker recovery test. Run `create` with worker stopped,
then start the worker and run `verify`. Requires concurrent-purchases.py alongside.
"""
import argparse
import datetime as dt
import importlib.util
import json
from pathlib import Path
import sys
import time
import uuid

ROOT = Path.cwd()
HELPER = ROOT / 'tests/performance/concurrent-purchases.py'
STATE_FILE = ROOT / 'tests/performance/sqs-recovery-state.json'

if not HELPER.exists():
    sys.exit(f'Missing {HELPER}. Keep concurrent-purchases.py from previous test.')
spec = importlib.util.spec_from_file_location('ticketing_load_helper', HELPER)
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)


def fail(msg):
    sys.exit('FAIL: ' + msg)


def call(method, path, who, scope='ticketing:purchase', body=None, extra=None):
    return helper.request(method, path, who, scope, body, extra)


def snapshot(event_id, admin):
    status, inventory = call('GET', f'/api/v1/events/{event_id}/availability', admin, 'ticketing:admin')
    if status != 200:
        fail(f'Cannot read inventory: HTTP {status} {inventory}')
    return inventory


def create():
    if STATE_FILE.exists():
        fail(f'Previous state exists at {STATE_FILE}; save or remove it deliberately before another run')
    prefix = uuid.uuid4().hex[:10]
    admin, buyer = f'recovery-admin-{prefix}', f'recovery-buyer-{prefix}'
    when = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(days=30)).replace(microsecond=0).isoformat().replace('+00:00', 'Z')
    status, event = call('POST', '/api/v1/events', admin, 'ticketing:admin',
                         {'name': f'SQS recovery {prefix}', 'venue': {'name': 'Local Test', 'city': 'Bogota'},
                          'eventDate': when, 'totalCapacity': 1})
    if status != 201 or not event.get('id'):
        fail(f'Cannot create event: HTTP {status} {event}')
    event_id = event['id']
    status, order = call('POST', '/api/v1/orders', buyer, body={'eventId': event_id, 'quantity': 1},
                         extra={'Idempotency-Key': str(uuid.uuid4())})
    if status != 202 or not order.get('orderId'):
        fail(f'Order not accepted: HTTP {status} {order}')
    order_id = order['orderId']
    time.sleep(2)
    status, observed = call('GET', f'/api/v1/orders/{order_id}', buyer)
    inv = snapshot(event_id, admin)
    if status != 200 or observed.get('status') != 'PENDING':
        fail(f'Worker may still be running: order HTTP {status}, {observed}')
    if inv.get('available') != 1 or inv.get('reserved') != 0:
        fail(f'Inventory unexpectedly changed before worker restart: {inv}')
    state = {'prefix': prefix, 'eventId': event_id, 'orderId': order_id,
             'buyer': buyer, 'admin': admin, 'createdAtUnix': time.time(),
             'before': {'order': observed, 'inventory': inv}}
    STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
    STATE_FILE.write_text(json.dumps(state, indent=2) + '\n')
    print('PASS phase 1: HTTP 202, order remains PENDING, inventory untouched.')
    print('eventId:', event_id, 'orderId:', order_id)
    print('Saved:', STATE_FILE)
    print('Now verify SQS queue contains the message; then start worker and run verify promptly (before 10-minute TTL).')


def verify():
    if not STATE_FILE.exists():
        fail(f'Run create first; missing {STATE_FILE}')
    state = json.loads(STATE_FILE.read_text())
    deadline = time.monotonic() + 90
    latest = None
    while time.monotonic() < deadline:
        status, latest = call('GET', f'/api/v1/orders/{state["orderId"]}', state['buyer'])
        if status != 200:
            fail(f'Order cannot be read: HTTP {status} {latest}')
        if latest.get('status') != 'PENDING':
            break
        time.sleep(2)
    inv = snapshot(state['eventId'], state['admin'])
    result = {**state, 'after': {'order': latest, 'inventory': inv}}
    out = ROOT / f'tests/performance/sqs-recovery-results-{state["prefix"]}.json'
    out.write_text(json.dumps(result, indent=2) + '\n')
    print('Order state:', latest.get('status'))
    print('Inventory:', inv)
    print('Evidence:', out)
    invariant = sum(inv.get(k, -1000) for k in ['available', 'reserved', 'pendingConfirmation', 'sold', 'complimentary']) == 1
    success = (latest.get('status') == 'RESERVED' and inv.get('available') == 0
               and inv.get('reserved') == 1 and invariant)
    if not success:
        fail('Recovery not verified; inspect worker logs and SQS queue')
    STATE_FILE.unlink()
    print('PASS phase 2: worker recovered PENDING order as RESERVED; no oversell.')


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('phase', choices=['create', 'verify'])
    args = p.parse_args()
    {'create': create, 'verify': verify}[args.phase]()
