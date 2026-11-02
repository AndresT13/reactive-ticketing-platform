import hashlib
import json
import os
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ENDPOINT = "http://localhost:18000"
EXPECTED_TABLES = {
    "ticketing-audit",
    "ticketing-events",
    "ticketing-idempotency-keys",
    "ticketing-inventory",
    "ticketing-order-guards",
    "ticketing-orders",
    "ticketing-payment-authorizations",
    "ticketing-payment-compensations",
    "ticketing-tickets",
}

def aws(*args):
    cmd = [
        "aws", "dynamodb", *args,
        "--endpoint-url", ENDPOINT,
        "--region", "us-east-1",
        "--output", "json",
        "--no-cli-pager"
    ]
    result = subprocess.run(
        cmd, capture_output=True, text=True
    )
    if result.returncode:
        raise RuntimeError(result.stderr)
    return json.loads(result.stdout) if result.stdout.strip() else {}

def aws_json(command, payload):
    with tempfile.NamedTemporaryFile(
        mode="w", suffix=".json", delete=False
    ) as f:
        json.dump(payload, f)
        filename = f.name
    try:
        return aws(command, "--cli-input-json", f"file://{filename}")
    finally:
        os.unlink(filename)

def fingerprint(items):
    serialized = [
        json.dumps(item, sort_keys=True, separators=(",", ":"))
        for item in items
    ]
    serialized.sort()
    return hashlib.sha256(
        "\n".join(serialized).encode()
    ).hexdigest()

def scan_all(table):
    result = []
    start = None
    while True:
        args = [
            "scan", "--table-name", table,
            "--consistent-read"
        ]
        if start is not None:
            with tempfile.NamedTemporaryFile(
                mode="w", suffix=".json", delete=False
            ) as f:
                json.dump(start, f)
                filename = f.name
            try:
                page = aws(
                    *args,
                    "--exclusive-start-key", f"file://{filename}"
                )
            finally:
                os.unlink(filename)
        else:
            page = aws(*args)

        result.extend(page.get("Items", []))
        start = page.get("LastEvaluatedKey")
        if not start:
            return result

def create_table(name, description):
    table = description["Table"]
    request = {
        "TableName": name,
        "AttributeDefinitions": table["AttributeDefinitions"],
        "KeySchema": table["KeySchema"],
        "BillingMode": "PAY_PER_REQUEST"
    }

    if table.get("GlobalSecondaryIndexes"):
        request["GlobalSecondaryIndexes"] = [
            {
                "IndexName": index["IndexName"],
                "KeySchema": index["KeySchema"],
                "Projection": index["Projection"]
            }
            for index in table["GlobalSecondaryIndexes"]
        ]

    if table.get("LocalSecondaryIndexes"):
        request["LocalSecondaryIndexes"] = [
            {
                "IndexName": index["IndexName"],
                "KeySchema": index["KeySchema"],
                "Projection": index["Projection"]
            }
            for index in table["LocalSecondaryIndexes"]
        ]

    aws_json("create-table", request)

    for _ in range(60):
        status = aws(
            "describe-table", "--table-name", name
        )["Table"]["TableStatus"]
        if status == "ACTIVE":
            return
        time.sleep(1)

    raise RuntimeError(f"Tabla no activa: {name}")

def batch_restore(table, items):
    for start in range(0, len(items), 25):
        pending = {
            table: [
                {"PutRequest": {"Item": item}}
                for item in items[start:start + 25]
            ]
        }

        for attempt in range(10):
            response = aws_json(
                "batch-write-item",
                {"RequestItems": pending}
            )
            pending = response.get("UnprocessedItems", {})
            if not pending:
                break
            time.sleep(min(2 ** attempt, 10))
        else:
            raise RuntimeError(
                f"Escrituras pendientes en {table}"
            )

def main():
    if len(sys.argv) != 2:
        raise SystemExit(
            "Uso: python3 restore-dynamodb.py RUTA_BACKUP"
        )

    root = Path(sys.argv[1]).resolve()
    manifest = json.loads(
        (root / "manifest.json").read_text()
    )

    if set(manifest) != EXPECTED_TABLES:
        raise RuntimeError(
            "El respaldo no contiene exactamente las nueve tablas"
        )

    existing = set(aws("list-tables")["TableNames"])

    # Protección: no escribir sobre tablas existentes.
    if existing & EXPECTED_TABLES:
        raise RuntimeError(
            "Restauración cancelada: existen tablas de destino"
        )

    if existing != {"ticketing-payment-provider-results"}:
        raise RuntimeError(
            "Destino inesperado. No se modificará."
        )

    for table in sorted(manifest):
        items = json.loads(
            (root / f"{table}.items.json").read_text()
        )
        schema = json.loads(
            (root / f"{table}.schema.json").read_text()
        )

        if len(items) != manifest[table]:
            raise RuntimeError(
                f"Respaldo inconsistente: {table}"
            )

        create_table(table, schema)
        batch_restore(table, items)

        restored = scan_all(table)
        if fingerprint(restored) != fingerprint(items):
            raise RuntimeError(
                f"Verificación de contenido fallida: {table}"
            )

        print(
            f"PASS {table}: {len(restored)} registros",
            flush=True
        )

    print("PASS: nueve tablas restauradas y verificadas")

if __name__ == "__main__":
    main()
