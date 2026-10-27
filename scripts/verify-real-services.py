import argparse
import json
import sys
from decimal import Decimal
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen
from uuid import uuid4


class Api:
    def __init__(self, base_url, timeout, correlation):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.correlation = correlation

    def request(self, method, path, body=None, key=None, expected=200):
        headers = {"Content-Type": "application/json", "X-Correlation-Id": self.correlation}
        if key is not None:
            headers["Idempotency-Key"] = key

        data = None if body is None else json.dumps(body).encode("utf-8")
        request = Request(self.base_url + path, data=data, headers=headers, method=method)

        try:
            with urlopen(request, timeout=self.timeout) as response:
                check(response.status == expected, f"{method} {path}: expected {expected}, got {response.status}")
                return json.load(response, parse_float=Decimal), response.headers
        except HTTPError as error:
            raise RuntimeError(f"{method} {path}: expected {expected}, got {error.code}") from None
        except (URLError, TimeoutError) as error:
            raise RuntimeError(f"{method} {path}: service unavailable") from error

    def get(self, path):
        return self.request("GET", path)[0]

    def post(self, path, body=None, expected=200):
        return self.request("POST", path, body, expected=expected)[0]

    def put(self, path, body):
        return self.request("PUT", path, body)[0]


def check(condition, message):
    if not condition:
        raise RuntimeError(message)


def verify(args):
    run_id = str(uuid4())
    catalog = Api(args.catalog, args.timeout, run_id)
    customer = Api(args.customer, args.timeout, run_id)
    order = Api(args.order, args.timeout, run_id)

    for api in (catalog, customer, order):
        api.get("/ping")

    buyer = customer.post("/customers", {
        "name": "Snapshot verification", "email": f"verify-{run_id}@example.com"
    }, expected=201)
    customer_path = f'/customers/{buyer["id"]}'
    address = {
        "zipCode": "01001000", "street": "Verification Street", "number": "100",
        "complement": None, "district": "Centro", "city": "Sao Paulo", "state": "SP"
    }
    buyer = customer.post(customer_path + "/addresses", {**address, "asDefault": True}, expected=201)
    original_address = buyer["addresses"][0]

    category = catalog.post("/categories", {"name": f"Verification {run_id}"}, expected=201)
    product = catalog.post("/products", {
        "name": f"Original {run_id}", "categoryId": category["id"], "attributes": {}
    }, expected=201)
    product_path = f'/products/{product["id"]}'
    codes = [f"VERIFY-{run_id}", f"VERIFY-{run_id}/../A +?&#% ç", f"VERIFY-{run_id}\\B"]

    for index, code in enumerate(codes):
        product = catalog.post(product_path + "/skus", {
            "code": code, "price": 42.50 if index == 0 else 10,
            "variationAttributes": {"size": "M", "variant": str(index)}
        }, expected=201)

    for sku in product["skus"]:
        catalog.post(f'{product_path}/skus/{sku["id"]}:activate')

    product = catalog.post(product_path + ":activate")
    lookups = {}

    for code in codes:
        query = urlencode({"code": code}, quote_via=quote)
        lookups[code] = catalog.get("/skus?" + query)
        check(lookups[code]["code"] == code, "Catalog changed the exact SKU code")
        check(lookups[code]["availableForSale"], "Catalog SKU is unavailable")

    legacy = catalog.get("/skus/" + quote(codes[0], safe=""))
    check(legacy == lookups[codes[0]], "Existing Catalog path contract changed")

    purchase = {
        "customerId": buyer["id"], "addressId": original_address["id"], "paymentMethod": "PIX",
        "items": [{"skuCode": code, "quantity": 2 if index == 0 else 1}
                  for index, code in enumerate(codes)]
    }
    original, created_headers = order.request("POST", "/orders", purchase, key=run_id, expected=201)
    location = f'/api/v1/orders/{original["id"]}'
    check(created_headers.get("Location") == location, "Missing creation Location")
    check(original["total"] == {"amount": Decimal("105.00"), "currency": "BRL"}, "Incorrect original total")
    check(original["status"] == "CREATED" and len(original["history"]) == 1, "Incorrect initial order state")
    check(original["customer"] == {"id": buyer["id"], "name": buyer["name"], "email": buyer["email"]},
          "Incorrect original customer snapshot")
    check(original["shippingAddress"] == {key: value for key, value in original_address.items() if key != "isDefault"},
          "Incorrect original address snapshot")
    check({item["skuCode"] for item in original["items"]} == set(codes), "Order changed the SKU codes")

    for item in original["items"]:
        snapshot = lookups[item["skuCode"]]
        check(item["productName"] == snapshot["productName"], "Incorrect original product name")
        check(item["variationAttributes"] == snapshot["variationAttributes"], "Incorrect original variations")
        check(item["unitPrice"] == {"amount": snapshot["price"], "currency": "BRL"}, "Incorrect original price")
        check(item["subtotal"]["amount"] == snapshot["price"] * item["quantity"], "Incorrect subtotal")

    primary = next(sku for sku in product["skus"] if sku["code"] == codes[0])
    primary_path = f'{product_path}/skus/{primary["id"]}'
    catalog.put(primary_path + "/price", {"price": 55})
    product = catalog.get(product_path)
    product = catalog.put(product_path, {
        "name": f"Changed {run_id}", "description": product["description"],
        "categoryId": category["id"], "attributes": product["attributes"], "version": product["version"]
    })
    primary = next(sku for sku in product["skus"] if sku["code"] == codes[0])
    catalog.put(primary_path, {"variationAttributes": {"size": "L", "variant": "changed"}, "version": primary["version"]})

    buyer = customer.get(customer_path)
    buyer = customer.put(customer_path, {
        "name": "Changed customer", "email": f"changed-{run_id}@example.com",
        "phone": buyer["phone"], "version": buyer["version"]
    })
    changed_address = {**address, "street": "Changed Street", "number": "200", "complement": "Suite", "city": "Changed City"}
    customer.put(f'{customer_path}/addresses/{original_address["id"]}', changed_address)

    check(order.get(f'/orders/{original["id"]}') == original, "Source changes altered the persisted order")
    replay, replay_headers = order.request("POST", "/orders", {
        **purchase, "items": list(reversed(purchase["items"]))
    }, key=run_id)
    check(replay == original, "Replay changed the original purchase")
    check(replay_headers.get("Location") == location, "Replay changed Location")

    newer, _ = order.request("POST", "/orders", purchase, key=f"{run_id}-new", expected=201)
    check(newer["id"] != original["id"] and newer["number"] != original["number"], "New key reused an order")
    check(newer["total"] == {"amount": Decimal("130.00"), "currency": "BRL"}, "New order has stale prices")
    check(newer["customer"] == {"id": buyer["id"], "name": buyer["name"], "email": buyer["email"]},
          "New order has a stale customer snapshot")
    check(newer["shippingAddress"] == {"id": original_address["id"], **changed_address, "country": "BR"},
          "New order has a stale address snapshot")
    changed_item = next(item for item in newer["items"] if item["skuCode"] == codes[0])
    check(changed_item["productName"] == product["name"], "New order has a stale product name")
    check(changed_item["variationAttributes"] == {"size": "L", "variant": "changed"}, "New order has stale variations")
    check(changed_item["unitPrice"]["amount"] == Decimal("55.00"), "New order has a stale unit price")

    print(f'PASS: exact SKU lookup, immutable snapshots, replay and updated purchase; orders {original["id"]}, {newer["id"]}')


def main():
    parser = argparse.ArgumentParser(description="Verify real Catalog, Customer and Order contracts using unique test data.")
    parser.add_argument("--catalog", default="http://localhost:8081/api/v1")
    parser.add_argument("--customer", default="http://localhost:8082/api/v1")
    parser.add_argument("--order", default="http://localhost:8084/api/v1")
    parser.add_argument("--timeout", type=float, default=15)
    args = parser.parse_args()

    if args.timeout <= 0:
        parser.error("--timeout must be positive")

    try:
        verify(args)
    except (RuntimeError, ValueError, KeyError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
