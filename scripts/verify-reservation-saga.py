"""Verifica o passo de reserva da saga com Catalog, Customer, Inventory e Order reais.

Cenários:
  1. com estoque  -> pedido vai sozinho de CREATED para AWAITING_PAYMENT e o estoque fica reservado;
  2. sem estoque  -> pedido vai para CANCELLED com motivo OUT_OF_STOCK e nada é reservado;
  3. --broker-outage: Kafka parado na criação -> pedido fica em CREATED; Kafka volta -> segue sozinho.

Requer Postgres e Kafka do Platform (perfis core e events) e os quatro serviços rodando.
"""

import argparse
import json
import subprocess
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen
from uuid import uuid4


class Api:
    def __init__(self, base_url, timeout):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    def request(self, method, path, body=None, headers=None, expected=200):
        all_headers = {"Content-Type": "application/json", **(headers or {})}
        data = None if body is None else json.dumps(body).encode("utf-8")
        request = Request(self.base_url + path, data=data, headers=all_headers, method=method)

        try:
            with urlopen(request, timeout=self.timeout) as response:
                check(response.status == expected, f"{method} {path}: expected {expected}, got {response.status}")
                raw = response.read()
                return json.loads(raw) if raw else None
        except HTTPError as error:
            raise RuntimeError(f"{method} {path}: expected {expected}, got {error.code}") from None
        except (URLError, TimeoutError) as error:
            raise RuntimeError(f"{method} {path}: service unavailable") from error

    def get(self, path):
        return self.request("GET", path)

    def post(self, path, body=None, expected=200, headers=None):
        return self.request("POST", path, body, headers, expected)


def check(condition, message):
    if not condition:
        raise RuntimeError(message)


def wait_for_status(order, order_id, accepted, timeout):
    deadline = time.monotonic() + timeout
    current = None

    while time.monotonic() < deadline:
        current = order.get(f"/orders/{order_id}")
        if current["status"] in accepted:
            return current
        time.sleep(0.5)

    raise RuntimeError(f"Order {order_id} stayed {current and current['status']}, expected one of {accepted}")


def docker(*args):
    subprocess.run(["docker", *args], check=True, capture_output=True)


def setup(catalog, customer, inventory, run_id, stock):
    buyer = customer.post("/customers", {"name": "Saga verification", "email": f"saga-{run_id}@example.com"},
                          expected=201)
    buyer = customer.post(f'/customers/{buyer["id"]}/addresses', {
        "zipCode": "01001000", "street": "Saga Street", "number": "1", "complement": None,
        "district": "Centro", "city": "Sao Paulo", "state": "SP", "asDefault": True
    }, expected=201)

    category = catalog.post("/categories", {"name": f"Saga {run_id}"}, expected=201)
    product = catalog.post("/products", {"name": f"Saga {run_id}", "categoryId": category["id"], "attributes": {}},
                           expected=201)
    code = f"SAGA-{run_id}"
    product = catalog.post(f'/products/{product["id"]}/skus', {
        "code": code, "price": 10, "variationAttributes": {"size": "M"}
    }, expected=201)
    catalog.post(f'/products/{product["id"]}/skus/{product["skus"][0]["id"]}:activate')
    catalog.post(f'/products/{product["id"]}:activate')

    inventory.post(f"/stock/{quote(code, safe='')}/receipts", {"quantity": stock}, expected=202)

    return buyer["id"], buyer["addresses"][0]["id"], code


def place(order, customer_id, address_id, code, quantity, correlation):
    return order.post("/orders", {
        "customerId": customer_id, "addressId": address_id, "paymentMethod": "PIX",
        "items": [{"skuCode": code, "quantity": quantity}]
    }, expected=201, headers={"Idempotency-Key": f"{correlation}-key", "X-Correlation-Id": correlation})


def stock_of(inventory, code):
    return inventory.get(f"/stock/{quote(code, safe='')}")


def verify(args):
    run_id = str(uuid4())[:8]
    catalog, customer = Api(args.catalog, args.timeout), Api(args.customer, args.timeout)
    inventory, order = Api(args.inventory, args.timeout), Api(args.order, args.timeout)

    for api in (catalog, customer, inventory, order):
        api.get("/ping")

    customer_id, address_id, code = setup(catalog, customer, inventory, run_id, stock=3)

    reserved = place(order, customer_id, address_id, code, 2, f"saga-{run_id}-reserved")
    check(reserved["status"] == "CREATED", "A new order must start as CREATED")
    reserved = wait_for_status(order, reserved["id"], {"AWAITING_PAYMENT", "CANCELLED"}, args.wait)
    check(reserved["status"] == "AWAITING_PAYMENT", f"Expected AWAITING_PAYMENT, got {reserved['status']}")
    check([step["toStatus"] for step in reserved["history"]] == ["CREATED", "AWAITING_PAYMENT"],
          "Unexpected history for the reserved order")
    stock = stock_of(inventory, code)
    check(stock["reserved"] == 2 and stock["available"] == 1, f"Unexpected stock after reservation: {stock}")
    print(f"PASS 1/3 with stock: order {reserved['id']} AWAITING_PAYMENT, stock {stock}")

    rejected = place(order, customer_id, address_id, code, 2, f"saga-{run_id}-rejected")
    rejected = wait_for_status(order, rejected["id"], {"AWAITING_PAYMENT", "CANCELLED"}, args.wait)
    check(rejected["status"] == "CANCELLED" and rejected["cancellationReason"] == "OUT_OF_STOCK",
          f"Expected CANCELLED/OUT_OF_STOCK, got {rejected['status']}/{rejected['cancellationReason']}")
    check(stock_of(inventory, code)["reserved"] == 2, "A rejected order must not reserve stock")
    print(f"PASS 2/3 without stock: order {rejected['id']} CANCELLED (OUT_OF_STOCK)")

    if not args.broker_outage:
        print("SKIP 3/3 broker outage (use --broker-outage)")
        return

    inventory.post(f"/stock/{quote(code, safe='')}/receipts", {"quantity": 5}, expected=202)
    docker("stop", args.kafka_container)
    try:
        delayed = place(order, customer_id, address_id, code, 1, f"saga-{run_id}-outage")
        time.sleep(5)
        check(order.get(f"/orders/{delayed['id']}")["status"] == "CREATED",
              "Without Kafka the order must wait in CREATED")
    finally:
        docker("start", args.kafka_container)

    delayed = wait_for_status(order, delayed["id"], {"AWAITING_PAYMENT", "CANCELLED"}, args.wait * 3)
    check(delayed["status"] == "AWAITING_PAYMENT", f"Expected AWAITING_PAYMENT after outage, got {delayed['status']}")
    print(f"PASS 3/3 broker outage: order {delayed['id']} waited in CREATED and continued when Kafka returned")


def main():
    parser = argparse.ArgumentParser(description="Verify the inventory reservation step of the order saga.")
    parser.add_argument("--catalog", default="http://localhost:8081/api/v1")
    parser.add_argument("--customer", default="http://localhost:8082/api/v1")
    parser.add_argument("--inventory", default="http://localhost:8083/api/v1")
    parser.add_argument("--order", default="http://localhost:8084/api/v1")
    parser.add_argument("--timeout", type=float, default=15, help="HTTP timeout in seconds")
    parser.add_argument("--wait", type=float, default=30, help="seconds to wait for the saga to decide")
    parser.add_argument("--broker-outage", action="store_true", help="also stop and restart the Kafka container")
    parser.add_argument("--kafka-container", default="mercatus-kafka")
    args = parser.parse_args()

    try:
        verify(args)
    except Exception as error:
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
