#!/usr/bin/env python3
"""Two-stage local integration check of transient payment outage and SQS DLQ.

Place beside test-payment-flows.py. Uses its working JWT/API helpers.
Run: python3 tests/performance/test-payment-unavailable.py create
     python3 tests/performance/test-payment-unavailable.py check
"""
import importlib.util
import json
import subprocess
import sys
import time
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
STATE = HERE / 'payment-unavailable-state.json'
REUSE = HERE / 'test-payment-flows.py'

spec = importlib.util.spec_from_file_location('payment_flows_helpers', REUSE)
helpers = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helpers)

QUEUE_BASE = 'http://localhost:4566/000000000000/'
PAYMENT_Q = 'ticketing-payment-requests'
DLQ = 'ticketing-payment-requests-dlq'


def sqs_counts(queue):
    cmd = ['docker', 'compose', 'exec', '-T', 'localstack', 'awslocal', 'sqs',
           'get-queue-attributes', '--queue-url', QUEUE_BASE + queue,
           '--attribute-names', 'ApproximateNumberOfMessages',
           'ApproximateNumberOfMessagesNotVisible']
    output = subprocess.run(cmd, check=True, capture_output=True, text=True)
    a = json.loads(output.stdout).get('Attributes', {})
    return {'visible': int(a.get('ApproximateNumberOfMessages', 0)),
            'inFlight': int(a.get('ApproximateNumberOfMessagesNotVisible', 0))}


def snapshot(state):
    buyer = helpers.jwt(helpers.secret_from_env(), state['buyer'], 'ticketing:purchase')
    _, order = helpers.request('GET', f"/api/v1/orders/{state['orderId']}", buyer)
    _, stock = helpers.request('GET', f"/api/v1/events/{state['eventId']}/availability", buyer)
    return {'orderStatus': order['status'], 'inventory': stock,
            'paymentQueue': sqs_counts(PAYMENT_Q), 'paymentDLQ': sqs_counts(DLQ)}


def create():
    if STATE.exists():
        raise RuntimeError(f'{STATE} already exists. Run check; do not overwrite an active test.')
    assert sqs_counts(DLQ)['visible'] == 0, 'Payment DLQ not empty; isolate earlier failures before this test'
    secret = helpers.secret_from_env()
    tag = uuid.uuid4().hex
    buyer_name = f'outage-test-buyer-{tag}'
    admin = helpers.jwt(secret, 'outage-test-admin', 'ticketing:admin')
    buyer = helpers.jwt(secret, buyer_name, 'ticketing:purchase')
    event_date = (datetime.now(timezone.utc) + timedelta(days=20)).isoformat().replace('+00:00', 'Z')
    code, event = helpers.request('POST', '/api/v1/events', admin, {
        'name': f'Payment outage E2E {tag[:8]}',
        'venue': {'name': 'Local Test Venue', 'city': 'Bogota'},
        'eventDate': event_date, 'totalCapacity': 1,
    })
    assert code == 201, (code, event)
    event_id = event['id']
    code, order = helpers.request('POST', '/api/v1/orders', buyer, {
        'eventId': event_id, 'quantity': 1,
    }, {'Idempotency-Key': str(uuid.uuid4())})
    assert code == 202, (code, order)
    order_id = order['orderId']
    reserved = helpers.poll_order(order_id, buyer, {'RESERVED', 'REJECTED'}, timeout=65)
    assert reserved['status'] == 'RESERVED', reserved
    code, confirmation = helpers.request('POST', f'/api/v1/orders/{order_id}/confirm', buyer,
                                         {'paymentToken': 'tok_unavailable'})
    assert code == 202, (code, confirmation)
    state = {'eventId': event_id, 'orderId': order_id, 'buyer': buyer_name,
             'submittedUtc': datetime.now(timezone.utc).isoformat(),
             'submittedEpoch': time.time()}
    STATE.write_text(json.dumps(state, indent=2))
    print('Confirmation accepted: HTTP 202, token tok_unavailable')
    print('Order:', order_id)
    print('Event:', event_id)
    print('State file:', STATE)
    print('Snapshot:', json.dumps(snapshot(state), indent=2))
    print('NEXT: Run check after the SQS receive/redrive period. Do not delete the queues.')


def check():
    state = json.loads(STATE.read_text())
    data = snapshot(state)
    elapsed = round(time.time() - state['submittedEpoch'])
    print('Elapsed seconds:', elapsed)
    print(json.dumps(data, indent=2))
    assert data['orderStatus'] != 'SOLD', 'FAIL: order sold despite unavailable payment provider'
    if data['paymentDLQ']['visible'] >= 1:
        print('PASS: payment reached DLQ; order not SOLD')
    else:
        print('PENDING: DLQ remains empty. Recheck later; message may be invisible or still retrying.')
    evidence = HERE / f'payment-unavailable-results-{state["orderId"][:8]}.json'
    evidence.write_text(json.dumps({'elapsedSeconds': elapsed, 'test': state,
                                    'snapshot': data}, indent=2))
    print('Evidence:', evidence)
    print('Note: Check inventory expiry separately; a DLQ message does not prove compensation.')


if __name__ == '__main__':
    try:
        if len(sys.argv) != 2 or sys.argv[1] not in ('create', 'check'):
            raise ValueError('Usage: python3 test-payment-unavailable.py create|check')
        {'create': create, 'check': check}[sys.argv[1]]()
    except Exception as ex:
        print('FAIL:', ex, file=sys.stderr)
        sys.exit(1)
