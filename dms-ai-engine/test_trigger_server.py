import json
import urllib.request
import urllib.error

import pytest

from trigger_server import LatestTriggerStore, start_background_server


@pytest.fixture
def running_server():
    store = LatestTriggerStore()
    server = start_background_server(store, host="127.0.0.1", port=0)
    port = server.server_address[1]
    yield store, f"http://127.0.0.1:{port}"
    server.shutdown()


def _get(url):
    try:
        with urllib.request.urlopen(f"{url}/latest-trigger", timeout=2) as resp:
            raw = resp.read()
            if not raw:
                # 204 No Content: urllib does not raise HTTPError for any
                # 2xx status, so an empty body must be handled here rather
                # than relying on the except branch below.
                return resp.status, None
            return resp.status, json.loads(raw.decode("utf-8"))
    except urllib.error.HTTPError as e:
        return e.code, None


def test_no_trigger_yet_returns_204(running_server):
    _store, base_url = running_server
    status, body = _get(base_url)
    assert status == 204
    assert body is None


def test_new_trigger_is_served_once_as_200(running_server):
    store, base_url = running_server
    payload = {"state": "CRITICAL", "correlationId": "vg-0001"}
    store.update_latest(payload)

    status, body = _get(base_url)
    assert status == 200
    assert body == payload


def test_same_trigger_is_not_served_twice(running_server):
    store, base_url = running_server
    store.update_latest({"state": "CRITICAL", "correlationId": "vg-0001"})
    _get(base_url)  # first poll consumes it

    status, body = _get(base_url)
    assert status == 204
    assert body is None


def test_newer_trigger_overrides_unserved_older_one(running_server):
    store, base_url = running_server
    store.update_latest({"state": "CRITICAL", "correlationId": "vg-0001"})
    store.update_latest({"state": "RECOVERED", "correlationId": "vg-0002"})

    status, body = _get(base_url)
    assert status == 200
    assert body["correlationId"] == "vg-0002"
