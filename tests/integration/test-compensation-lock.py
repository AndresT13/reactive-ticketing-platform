import concurrent.futures
import json
import subprocess
import uuid

TABLE = "ticketing-payment-compensations"
COMP_ID = "lock-test-" + uuid.uuid4().hex

BASE = [
    "docker", "compose", "run", "--rm", "--no-deps",
    "--entrypoint", "aws",
    "-e", "AWS_ACCESS_KEY_ID=test",
    "-e", "AWS_SECRET_ACCESS_KEY=test",
    "aws-init", "dynamodb"
]

def aws(*args):
    return subprocess.run(
        BASE + list(args) + [
            "--endpoint-url", "http://dynamodb-local:8000",
            "--region", "us-east-1"
        ],
        capture_output=True,
        text=True
    )

item = {
    "compensationId": {"S": COMP_ID},
    "orderId": {"S": "order-lock-test"},
    "authorizationId": {"S": COMP_ID},
    "status": {"S": "PENDING"},
    "attempts": {"N": "0"},
    "version": {"N": "0"},
    "nextAttemptAt": {"S": "2026-11-01T12:00:00Z"},
    "updatedAt": {"S": "2026-11-01T12:00:00Z"}
}

created = aws(
    "put-item",
    "--table-name", TABLE,
    "--item", json.dumps(item),
    "--condition-expression", "attribute_not_exists(compensationId)"
)

if created.returncode:
    raise RuntimeError(created.stderr)

def compete(worker):
    result = aws(
        "update-item",
        "--table-name", TABLE,
        "--key", json.dumps({
            "compensationId": {"S": COMP_ID}
        }),
        "--update-expression",
        "SET #s = :processing, #v = :one",
        "--condition-expression",
        "#v = :zero AND #s = :pending",
        "--expression-attribute-names",
        json.dumps({"#s": "status", "#v": "version"}),
        "--expression-attribute-values",
        json.dumps({
            ":processing": {"S": "PROCESSING"},
            ":one": {"N": "1"},
            ":zero": {"N": "0"},
            ":pending": {"S": "PENDING"}
        })
    )
    return worker, result.returncode, result.stderr

with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    results = list(pool.map(compete, ["worker-A", "worker-B"]))

for worker, code, error in results:
    print(worker, "SUCCESS" if code == 0 else "REJECTED")
    if code != 0:
        print(error.strip()[-300:])

final = aws(
    "get-item",
    "--table-name", TABLE,
    "--key", json.dumps({"compensationId": {"S": COMP_ID}}),
    "--consistent-read"
)

if final.returncode:
    raise RuntimeError(final.stderr)

record = json.loads(final.stdout)["Item"]
successes = sum(code == 0 for _, code, _ in results)
conditional_failures = sum(
    code != 0 and "ConditionalCheckFailedException" in error
    for _, code, error in results
)

assert successes == 1, results
assert conditional_failures == 1, results
assert record["version"]["N"] == "1", record
assert record["status"]["S"] == "PROCESSING", record

print("PASS: exactly one worker acquired compensation")
print("Final version:", record["version"]["N"])
print("Compensation:", COMP_ID)
