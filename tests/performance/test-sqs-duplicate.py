import base64
import hashlib
import hmac
import json
import os
import subprocess
import sys
import time
import uuid
from datetime import datetime, timezone
from urllib.request import Request, urlopen
from urllib.error import HTTPError

BASE = "http://localhost:8080"

def load_secret():
    for line in open(".env"):
        if line.startswith("TICKETING_SECURITY_LOCAL_JWT_SECRET="):
            return line.split("=", 1)[1].strip().strip('"').strip("'")
    raise RuntimeError("Falta JWT secret en .env")

def b64(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()

def token(secret, subject, scope):
    now = int(time.time())
    header = b64(b'{"alg":"HS256","typ":"JWT"}')
    payload = b64(json.dumps({
        "sub": subject, "scope": scope,
        "iat": now, "exp": now + 3600
    }).encode())
    value = f"{header}.{payload}"
    signature = b64(hmac.new(
        secret.encode(), value.encode(), hashlib.sha256
    ).digest())
    return f"{value}.{signature}"

def api(method, path, jwt, body=None, extra=None):
    headers = {"Authorization": f"Bearer {jwt}"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    headers.update(extra or {})
    req = Request(
        BASE + path,
        data=json.dumps(body).encode() if body is not None else None,
        headers=headers,
        method=method
    )
    try:
        with urlopen(req, timeout=15) as response:
            return response.status, json.load(response)
    except HTTPError as e:
        raise RuntimeError(
            f"HTTP {e.code} {path}: {e.read().decode()}"
        )

secret = load_secret()
admin = token(secret, "duplicate-test-admin", "ticketing:admin")
buyer = token(secret, "duplicate-buyer-" + uuid.uuid4().hex,
              "ticketing:purchase")

event_date = "2026-12-15T20:00:00Z"
status, event = api("POST", "/api/v1/events", admin, {
    "name": "SQS Duplicate Integration Test",
    "venue": {"name": "Test Venue", "city": "Bogota"},
    "eventDate": event_date,
    "totalCapacity": 1
})
assert status == 201
event_id = event["id"]

status, order = api("POST", "/api/v1/orders", buyer, {
    "eventId": event_id,
    "quantity": 1
}, {"Idempotency-Key": str(uuid.uuid4())})
assert status == 202
order_id = order["orderId"]

deadline = time.monotonic() + 60
while time.monotonic() < deadline:
    _, current = api("GET", f"/api/v1/orders/{order_id}", buyer)
    if current["status"] != "PENDING":
        break
    time.sleep(0.5)

assert current["status"] == "RESERVED", current
_, before = api(
    "GET", f"/api/v1/events/{event_id}/availability", buyer
)
assert before["reserved"] == 1 and before["available"] == 0

message = {
    "type": "ReserveTicketsCommand",
    "schemaVersion": 1,
    "orderId": order_id,
    "eventId": event_id,
    "quantity": 1,
    "paymentToken": None,
    "occurredAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
}

queue_url = (
    "http://localhost:4566/000000000000/"
    "ticketing-reservation-requests"
)

for _ in range(2):
    subprocess.run([
        "docker", "compose", "exec", "-T", "localstack",
        "awslocal", "sqs", "send-message",
        "--queue-url", queue_url,
        "--message-body", json.dumps(message)
    ], check=True, stdout=subprocess.DEVNULL)

print("Two duplicate commands sent to SQS.")

# Wait for the consumer to acknowledge the duplicate messages.
deadline = time.monotonic() + 30
while time.monotonic() < deadline:
    cmd = [
        "docker", "compose", "exec", "-T", "localstack",
        "awslocal", "sqs", "get-queue-attributes",
        "--queue-url", queue_url,
        "--attribute-names",
        "ApproximateNumberOfMessages",
        "ApproximateNumberOfMessagesNotVisible"
    ]
    result = subprocess.run(cmd, check=True, capture_output=True, text=True)
    attributes = json.loads(result.stdout)["Attributes"]
    if (int(attributes.get("ApproximateNumberOfMessages", 0)) == 0
            and int(attributes.get("ApproximateNumberOfMessagesNotVisible", 0)) == 0):
        break
    time.sleep(0.5)
else:
    raise AssertionError("Duplicate SQS messages were not acknowledged in time")

_, after = api("GET", f"/api/v1/events/{event_id}/availability", buyer)
_, final_order = api("GET", f"/api/v1/orders/{order_id}", buyer)

assert final_order["status"] == "RESERVED", final_order
assert before == after, {"before": before, "after": after}

result = {
    "eventId": event_id,
    "orderId": order_id,
    "duplicateMessages": 2,
    "before": before,
    "after": after,
    "status": "PASS"
}

os.makedirs("tests/performance", exist_ok=True)
path = f"tests/performance/sqs-duplicate-results-{uuid.uuid4().hex[:8]}.json"
with open(path, "w") as file:
    json.dump(result, file, indent=2)

print("PASS: duplicate messages acknowledged; inventory unchanged")
print("Evidence:", path)
