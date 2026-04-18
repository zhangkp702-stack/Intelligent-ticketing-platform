import csv
import os
import random
import threading
import time
from pathlib import Path
from typing import Dict, List, Optional

from locust import HttpUser, between, task


BASE_DIR = Path(__file__).resolve().parent
DEFAULT_USERS_FILE = BASE_DIR / "users.csv"
DEFAULT_SCENARIOS_FILE = BASE_DIR / "scenarios.csv"

USERS_FILE = Path(os.getenv("LOCUST_USERS_FILE", str(DEFAULT_USERS_FILE)))
SCENARIOS_FILE = Path(os.getenv("LOCUST_SCENARIOS_FILE", str(DEFAULT_SCENARIOS_FILE)))

ENABLE_QUERY = os.getenv("LOCUST_ENABLE_QUERY", "true").lower() == "true"
ENABLE_PAYMENT = os.getenv("LOCUST_ENABLE_PAYMENT", "false").lower() == "true"
PAY_STATUS_POLL_TIMES = int(os.getenv("LOCUST_PAY_STATUS_POLL_TIMES", "3"))
PAY_STATUS_POLL_INTERVAL = float(os.getenv("LOCUST_PAY_STATUS_POLL_INTERVAL", "2"))


_USERS_LOCK = threading.Lock()
_SCENARIOS_LOCK = threading.Lock()
_USER_INDEX = 0
_SCENARIO_INDEX = 0


def _load_csv_rows(path: Path) -> List[Dict[str, str]]:
    if not path.exists():
        raise FileNotFoundError(f"CSV file not found: {path}")
    with path.open("r", encoding="utf-8-sig", newline="") as csv_file:
        reader = csv.DictReader(csv_file)
        return [dict(row) for row in reader]


USER_ROWS = _load_csv_rows(USERS_FILE)
SCENARIO_ROWS = _load_csv_rows(SCENARIOS_FILE)


def _next_user() -> Dict[str, str]:
    global _USER_INDEX
    with _USERS_LOCK:
        row = USER_ROWS[_USER_INDEX % len(USER_ROWS)]
        _USER_INDEX += 1
        return dict(row)


def _next_scenario() -> Dict[str, str]:
    global _SCENARIO_INDEX
    with _SCENARIOS_LOCK:
        row = SCENARIO_ROWS[_SCENARIO_INDEX % len(SCENARIO_ROWS)]
        _SCENARIO_INDEX += 1
        return dict(row)


def _as_bool(value: str, default: bool = False) -> bool:
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "y"}


class TicketChainUser(HttpUser):
    wait_time = between(1, 3)

    def on_start(self) -> None:
        self.account = _next_user()
        self.scenario = _next_scenario()
        self.username = self.account["username"]
        self.password = self.account["password"]
        self.token: Optional[str] = None
        self.user_id: Optional[str] = None
        self.passenger_ids: List[str] = []
        self._login()
        self._load_passengers()

    @task
    def purchase_chain(self) -> None:
        scenario = _next_scenario()
        passenger_count = self._resolve_passenger_count(scenario)
        chosen_passenger_ids = self.passenger_ids[:passenger_count]
        if len(chosen_passenger_ids) < passenger_count:
            self.environment.events.request.fire(
                request_type="LOCAL",
                name="prepare_passengers",
                response_time=0,
                response_length=0,
                exception=RuntimeError(
                    f"user={self.username} has only {len(self.passenger_ids)} passengers, "
                    f"but scenario requires {passenger_count}"
                ),
            )
            return

        purchase_context = self._query_ticket_if_needed(scenario)
        if purchase_context is None:
            return

        order_sn = self._purchase_ticket(
            train_id=purchase_context["trainId"],
            departure=purchase_context["departure"],
            arrival=purchase_context["arrival"],
            seat_type=int(scenario["seatType"]),
            passenger_ids=chosen_passenger_ids,
        )
        if not order_sn:
            return

        if not self._should_pay(scenario):
            return

        total_amount = self._query_order_total_amount(order_sn)
        if total_amount is None:
            return

        self._create_payment(order_sn, total_amount, purchase_context["departure"], purchase_context["arrival"])
        self._poll_payment_status(order_sn)

    def _headers(self) -> Dict[str, str]:
        headers = {"Content-Type": "application/json"}
        if self.token:
            headers["Authorization"] = self.token
        return headers

    def _login(self) -> None:
        payload = {
            "usernameOrMailOrPhone": self.username,
            "password": self.password,
        }
        with self.client.post(
            "/api/user-service/v1/login",
            json=payload,
            headers={"Content-Type": "application/json"},
            catch_response=True,
            name="login",
        ) as response:
            data = response.json()
            if not data.get("success"):
                response.failure(f"login failed: {data}")
                return
            self.token = data["data"].get("accessToken")
            self.user_id = str(data["data"].get("userId", ""))
            response.success()

    def _load_passengers(self) -> None:
        with self.client.get(
            "/api/user-service/passenger/query",
            params={"username": self.username},
            headers=self._headers(),
            catch_response=True,
            name="passenger_query",
        ) as response:
            data = response.json()
            if not data.get("success"):
                response.failure(f"passenger query failed: {data}")
                return
            self.passenger_ids = [str(each["id"]) for each in (data.get("data") or [])]
            if not self.passenger_ids:
                response.failure("no passengers bound to account")
                return
            response.success()

    def _query_ticket_if_needed(self, scenario: Dict[str, str]) -> Optional[Dict[str, str]]:
        if not ENABLE_QUERY:
            return {
                "trainId": scenario["trainId"],
                "departure": scenario["departure"],
                "arrival": scenario["arrival"],
            }
        params = {
            "fromStation": scenario["fromStation"],
            "toStation": scenario["toStation"],
            "departureDate": scenario["departureDate"],
        }
        with self.client.get(
            "/api/ticket-service/ticket/query",
            params=params,
            headers=self._headers(),
            catch_response=True,
            name="ticket_query",
        ) as response:
            data = response.json()
            if not data.get("success"):
                response.failure(f"ticket query failed: {data}")
                return None
            train_list = (data.get("data") or {}).get("trainList") or []
            target_train = next(
                (
                    each
                    for each in train_list
                    if str(each.get("trainId")) == str(scenario["trainId"])
                    or each.get("trainNumber") == scenario.get("trainNumber")
                ),
                None,
            )
            if target_train is None:
                response.failure(f"target train not found in query result: {scenario}")
                return None
            response.success()
            return {
                "trainId": str(target_train["trainId"]),
                "departure": target_train["departure"],
                "arrival": target_train["arrival"],
            }

    def _purchase_ticket(
        self,
        train_id: str,
        departure: str,
        arrival: str,
        seat_type: int,
        passenger_ids: List[str],
    ) -> Optional[str]:
        payload = {
            "trainId": train_id,
            "departure": departure,
            "arrival": arrival,
            "chooseSeats": [],
            "passengers": [
                {"passengerId": passenger_id, "seatType": seat_type}
                for passenger_id in passenger_ids
            ],
        }
        with self.client.post(
            "/api/ticket-service/ticket/purchase/v2",
            json=payload,
            headers=self._headers(),
            catch_response=True,
            name="ticket_purchase_v2",
        ) as response:
            data = response.json()
            if not data.get("success"):
                response.failure(f"purchase failed: {data}")
                return None
            order_sn = (data.get("data") or {}).get("orderSn")
            if not order_sn:
                response.failure(f"purchase success but orderSn missing: {data}")
                return None
            response.success()
            return str(order_sn)

    def _query_order_total_amount(self, order_sn: str) -> Optional[float]:
        with self.client.get(
            "/api/order-service/order/ticket/query",
            params={"orderSn": order_sn},
            headers=self._headers(),
            catch_response=True,
            name="order_query",
        ) as response:
            data = response.json()
            if not data.get("success"):
                response.failure(f"order query failed: {data}")
                return None
            passenger_details = (data.get("data") or {}).get("passengerDetails") or []
            total_amount = sum((each.get("amount") or 0) for each in passenger_details) / 100
            if total_amount <= 0:
                response.failure(f"invalid total amount: {data}")
                return None
            response.success()
            return float(total_amount)

    def _create_payment(self, order_sn: str, total_amount: float, departure: str, arrival: str) -> None:
        payload = {
            "channel": 0,
            "tradeType": 0,
            "orderSn": order_sn,
            "outOrderSn": order_sn,
            "totalAmount": total_amount,
            "subject": f"{departure}-{arrival}",
        }
        with self.client.post(
            "/api/pay-service/pay/create",
            json=payload,
            headers=self._headers(),
            catch_response=True,
            name="pay_create",
        ) as response:
            data = response.json()
            if not data.get("success"):
                response.failure(f"pay create failed: {data}")
                return
            response.success()

    def _poll_payment_status(self, order_sn: str) -> None:
        for _ in range(PAY_STATUS_POLL_TIMES):
            with self.client.get(
                "/api/pay-service/pay/query/order-sn",
                params={"orderSn": order_sn},
                headers=self._headers(),
                catch_response=True,
                name="pay_status_query",
            ) as response:
                data = response.json()
                if not data.get("success"):
                    response.failure(f"pay status query failed: {data}")
                    return
                status = (data.get("data") or {}).get("status")
                if status in (20, 30):
                    response.success()
                    return
                response.success()
            time.sleep(PAY_STATUS_POLL_INTERVAL)

    def _resolve_passenger_count(self, scenario: Dict[str, str]) -> int:
        count = int(scenario.get("passengerCount") or 1)
        return max(1, count)

    def _should_pay(self, scenario: Dict[str, str]) -> bool:
        if not ENABLE_PAYMENT:
            return False
        if "pay" in scenario and scenario["pay"] != "":
            return _as_bool(scenario["pay"], default=False)
        return True
