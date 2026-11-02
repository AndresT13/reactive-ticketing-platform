#!/usr/bin/env python3
"""Prueba local E2E: RESERVED -> EXPIRED -> inventario liberado.
Reutiliza los helpers de concurrent-purchases.py (JWT y HTTP) sin librerias externas.
Ejecutar solo con worker local configurado con TTL 20s y scheduler intervalo 3s.
"""
import collections
import datetime as dt
import importlib.util
import json
import pathlib
import sys
import time
import uuid

HERE = pathlib.Path(__file__).resolve().parent
HELPER = HERE / 'concurrent-purchases.py'
if not HELPER.exists():
    sys.exit(f'Falta {HELPER}: copia primero el script de concurrencia ya utilizado')
spec = importlib.util.spec_from_file_location('concurrency_helper', HELPER)
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)

PREFIX = uuid.uuid4().hex[:10]
ADMIN = f'expiration-admin-{PREFIX}'
BUYER = f'expiration-buyer-{PREFIX}'
DURATION = 110

def req(method, path, subject, scope='ticketing:purchase', body=None, extra=None):
    return helper.request(method, path, subject, scope=scope, body=body, extra=extra)

def inventory_for(event_id):
    status, data = req('GET', f'/api/v1/events/{event_id}/availability', ADMIN, 'ticketing:admin')
    if status != 200:
        sys.exit(f'FAIL: availability HTTP {status} {data}')
    return data

def poll(order_id, desired, timeout):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        code, data = req('GET', f'/api/v1/orders/{order_id}', BUYER)
        if code != 200:
            sys.exit(f'FAIL: get order HTTP {code} {data}')
        last = data
        if data.get('status') in desired:
            return data
        time.sleep(1)
    sys.exit(f'FAIL: time limit reached, expected {sorted(desired)}, last state={last.get("status") if last else None}')

def main():
    future = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(days=30)).replace(microsecond=0).isoformat().replace('+00:00', 'Z')
    code, event = req('POST', '/api/v1/events', ADMIN, 'ticketing:admin', {
        'name': f'Expiration E2E {PREFIX}', 'venue': {'name': 'Local Test', 'city': 'Bogota'},
        'eventDate': future, 'totalCapacity': 1,
    })
    if code != 201 or not event.get('id'):
        sys.exit(f'FAIL: create event HTTP {code}: {event}')
    event_id = event['id']
    code, order = req('POST', '/api/v1/orders', BUYER, body={'eventId': event_id, 'quantity': 1},
                      extra={'Idempotency-Key': str(uuid.uuid4())})
    if code != 202 or not order.get('orderId'):
        sys.exit(f'FAIL: create order HTTP {code}: {order}')
    order_id = order['orderId']
    print(f'Event={event_id} Order={order_id}', flush=True)
    reserved = poll(order_id, {'RESERVED', 'EXPIRED', 'REJECTED'}, 40)
    if reserved['status'] != 'RESERVED':
        sys.exit(f'FAIL: expected initial RESERVED, found {reserved["status"]}; check worker state')
    first_inventory = inventory_for(event_id)
    print('Initial:', reserved['status'], json.dumps(first_inventory, ensure_ascii=False), flush=True)
    if first_inventory.get('reserved') != 1 or first_inventory.get('available') != 0:
        sys.exit('FAIL: initial inventory is not held')
    expired = poll(order_id, {'EXPIRED', 'REJECTED', 'SOLD', 'CANCELLED'}, DURATION)
    final_inventory = inventory_for(event_id)
    print('Final:', expired['status'], json.dumps(final_inventory, ensure_ascii=False), flush=True)
    valid = (expired['status'] == 'EXPIRED'
             and final_inventory.get('available') == 1
             and final_inventory.get('reserved') == 0
             and final_inventory.get('pendingConfirmation') == 0
             and final_inventory.get('sold') == 0
             and final_inventory.get('complimentary') == 0)
    evidence = {'eventId': event_id, 'orderId': order_id,
                'before': {'status': reserved['status'], 'inventory': first_inventory},
                'after': {'status': expired['status'], 'inventory': final_inventory}, 'passed': valid}
    evidence_path = HERE / f'expiration-results-{PREFIX}.json'
    evidence_path.write_text(json.dumps(evidence, indent=2, ensure_ascii=False) + '\n', encoding='utf8')
    print('Evidence:', evidence_path)
    if not valid:
        sys.exit('FAIL: expiry was not correctly applied')
    # Repeated reads should not change the inventory.
    time.sleep(5)
    stable = inventory_for(event_id)
    if any(stable.get(k) != final_inventory.get(k) for k in ('available', 'reserved', 'pendingConfirmation', 'sold', 'complimentary')):
        sys.exit('FAIL: inventory changed unexpectedly after expiry')
    print('PASS: automatic expiration releases the ticket; inventory remains stable')

if __name__ == '__main__':
    main()
