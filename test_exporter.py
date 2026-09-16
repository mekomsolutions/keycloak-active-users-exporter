from typing import Any
from unittest.mock import patch

import pytest
from prometheus_client import CollectorRegistry

import exporter


@pytest.fixture
def config(monkeypatch: pytest.MonkeyPatch) -> exporter.Config:
    monkeypatch.setenv("KC_BASE", "https://keycloak.example.test")
    monkeypatch.setenv("KC_REALM", "test-realm")
    monkeypatch.setenv("KC_CLIENT_ID", "keycloak-active-users-exporter")
    monkeypatch.setenv("KC_CLIENT_SECRET", "test-secret")
    monkeypatch.setenv("TRACKED_CLIENTS", "openmrs,patient-portal")
    monkeypatch.setenv(
        "EXCLUDED_CLIENTS",
        "account,account-console,security-admin-console,admin-cli,"
        "realm-management,keycloak-active-users-exporter",
    )
    monkeypatch.setenv("EXCLUDED_USERNAMES", "admin,import-bot")
    return exporter.load_config()


@pytest.fixture
def metrics() -> exporter.Metrics:
    return exporter.Metrics(CollectorRegistry())


def test_normalise_client_session_stats_accepts_map_response() -> None:
    raw_stats = {
        "openmrs": 3,
        "patient-portal": "2",
    }

    assert exporter.normalise_client_session_stats(raw_stats) == {
        "openmrs": 3,
        "patient-portal": 2,
    }


def test_normalise_client_session_stats_accepts_list_response() -> None:
    raw_stats = [
        {"clientId": "openmrs", "active": 3, "offline": 0},
        {"clientId": "patient-portal", "active": "2", "offline": 1},
        {"active": 7},
    ]

    assert exporter.normalise_client_session_stats(raw_stats) == {
        "openmrs": 3,
        "patient-portal": 2,
    }


def test_normalise_client_session_stats_rejects_unexpected_data() -> None:
    with pytest.raises(RuntimeError, match="Unexpected client-session-stats response type"):
        exporter.normalise_client_session_stats("not-valid-json-shape")


@pytest.mark.parametrize(
    ("user", "included"),
    [
        ({"username": "alice", "enabled": True}, True),
        ({"username": "ALICE", "enabled": True}, True),
        ({"username": "admin", "enabled": True}, False),
        ({"username": "IMPORT-BOT", "enabled": True}, False),
        ({"username": "alice", "enabled": False}, False),
        (
            {
                "username": "service-account-openmrs",
                "enabled": True,
                "serviceAccountClientId": "openmrs",
            },
            False,
        ),
        ({"username": "service-account-custom", "enabled": True}, False),
    ],
)
def test_is_included_user_applies_enabled_service_account_and_username_filters(
    user: dict[str, Any],
    included: bool,
) -> None:
    assert exporter.is_included_user(user, {"admin", "import-bot"}) is included


def test_get_all_client_sessions_returns_single_short_page(
    config: exporter.Config,
) -> None:
    page = [{"userId": "one"}, {"userId": "two"}]

    with patch.object(exporter, "keycloak_get", return_value=page) as keycloak_get:
        sessions = exporter.get_all_client_sessions(config, "token", "client-uuid")

    assert sessions == page

    keycloak_get.assert_called_once_with(
        config,
        "/admin/realms/test-realm/clients/client-uuid/user-sessions",
        "token",
        params={"first": 0, "max": 100},
    )


def test_get_all_client_sessions_paginates_full_page(
    config: exporter.Config,
) -> None:
    first_page = [{"userId": str(index)} for index in range(100)]
    second_page = [{"userId": "100"}]

    with patch.object(
        exporter,
        "keycloak_get",
        side_effect=[first_page, second_page],
    ) as keycloak_get:
        sessions = exporter.get_all_client_sessions(config, "token", "client-uuid")

    assert len(sessions) == 101
    assert sessions[-1] == {"userId": "100"}
    assert keycloak_get.call_count == 2
    assert keycloak_get.call_args_list[1].kwargs["params"] == {
        "first": 100,
        "max": 100,
    }


def test_get_all_enabled_users_requests_enabled_users(
    config: exporter.Config,
) -> None:
    page = [{"username": "alice", "enabled": True}]

    with patch.object(exporter, "keycloak_get", return_value=page) as keycloak_get:
        users = exporter.get_all_enabled_users(config, "token")

    assert users == page

    keycloak_get.assert_called_once_with(
        config,
        "/admin/realms/test-realm/users",
        "token",
        params={
            "enabled": "true",
            "briefRepresentation": "true",
            "first": 0,
            "max": 100,
        },
    )


def test_get_session_user_ids_excludes_configured_and_service_account_users(
    config: exporter.Config,
) -> None:
    sessions = [
        {"userId": "user-a", "username": "alice"},
        {"userId": "user-a", "username": "alice"},
        {"userId": "user-b", "username": "admin"},
        {"userId": "user-c", "username": "IMPORT-BOT"},
        {"userId": "user-d", "username": "service-account-openmrs"},
        {"userId": "user-e", "username": "bob"},
        {"username": "missing-user-id"},
    ]

    with patch.object(exporter, "get_client_uuid", return_value="client-uuid"):
        with patch.object(exporter, "get_all_client_sessions", return_value=sessions):
            user_ids = exporter.get_session_user_ids(config, "token", "openmrs")

    assert user_ids == {"user-a", "user-e"}


def test_collect_reports_per_app_realm_and_enabled_user_counts(
    config: exporter.Config,
    metrics: exporter.Metrics,
) -> None:
    session_stats = [
        {"clientId": "openmrs", "active": 3},
        {"clientId": "patient-portal", "active": 2},
        {"clientId": "account-console", "active": 1},
        {"clientId": "admin-cli", "active": 1},
    ]

    user_ids_by_client = {
        "openmrs": {"alice", "bob"},
        "patient-portal": {"alice", "carol"},
    }

    def session_user_ids(
        _: exporter.Config,
        __: str,
        client_id: str,
    ) -> set[str]:
        return user_ids_by_client.get(client_id, set())

    with patch.object(exporter, "get_token", return_value="token"):
        with patch.object(exporter, "keycloak_get", return_value=session_stats):
            with patch.object(
                exporter,
                "get_enabled_user_count",
                return_value=10,
            ):
                with patch.object(
                    exporter,
                    "get_session_user_ids",
                    side_effect=session_user_ids,
                ):
                    exporter.collect(config, metrics)

    assert metrics.active_distinct_users_per_app.labels(
        realm="test-realm",
        client="openmrs",
    )._value.get() == 2

    assert metrics.active_distinct_users_per_app.labels(
        realm="test-realm",
        client="patient-portal",
    )._value.get() == 2

    assert metrics.active_distinct_users_realm.labels(
        realm="test-realm",
    )._value.get() == 3

    assert metrics.enabled_users_realm.labels(
        realm="test-realm",
    )._value.get() == 10

    assert metrics.exporter_up._value.get() == 1


def test_collect_exposes_zero_for_tracked_app_without_active_sessions(
    config: exporter.Config,
    metrics: exporter.Metrics,
) -> None:
    session_stats = [{"clientId": "openmrs", "active": 1}]

    def session_user_ids(
        _: exporter.Config,
        __: str,
        client_id: str,
    ) -> set[str]:
        if client_id == "openmrs":
            return {"alice"}

        return set()

    with patch.object(exporter, "get_token", return_value="token"):
        with patch.object(exporter, "keycloak_get", return_value=session_stats):
            with patch.object(
                exporter,
                "get_enabled_user_count",
                return_value=1,
            ):
                with patch.object(
                    exporter,
                    "get_session_user_ids",
                    side_effect=session_user_ids,
                ):
                    exporter.collect(config, metrics)

    assert metrics.active_distinct_users_per_app.labels(
        realm="test-realm",
        client="openmrs",
    )._value.get() == 1

    assert metrics.active_distinct_users_per_app.labels(
        realm="test-realm",
        client="patient-portal",
    )._value.get() == 0