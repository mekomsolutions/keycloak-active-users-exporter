#!/usr/bin/env python3
"""Export Keycloak distinct active-user and enabled-user metrics to Prometheus."""

import logging
import os
import threading
import time
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Set

import requests
from prometheus_client import CollectorRegistry, Gauge, start_http_server


@dataclass(frozen=True)
class Config:
    kc_base: str
    kc_realm: str
    kc_client_id: str
    kc_client_secret: str
    tracked_clients: Set[str]
    excluded_clients: Set[str]
    excluded_usernames: Set[str]
    poll_seconds: int
    http_port: int
    timeout_seconds: int


def comma_separated_set(value: str, casefold: bool = False) -> Set[str]:
    values = {item.strip() for item in value.split(",") if item.strip()}
    if casefold:
        return {item.casefold() for item in values}
    return values


def load_config() -> Config:
    return Config(
        kc_base=os.environ["KC_BASE"].rstrip("/"),
        kc_realm=os.environ["KC_REALM"],
        kc_client_id=os.environ["KC_CLIENT_ID"],
        kc_client_secret=os.environ["KC_CLIENT_SECRET"],
        tracked_clients=comma_separated_set(os.getenv("TRACKED_CLIENTS", "")),
        excluded_clients=comma_separated_set(
            os.getenv(
                "EXCLUDED_CLIENTS",
                (
                    "account,account-console,security-admin-console,admin-cli,"
                    "broker,realm-management,keycloak-active-users-exporter"
                ),
            )
        ),
        excluded_usernames=comma_separated_set(
            os.getenv("EXCLUDED_USERNAMES", ""),
            casefold=True,
        ),
        poll_seconds=int(os.getenv("POLL_SECONDS", "60")),
        http_port=int(os.getenv("HTTP_PORT", "9108")),
        timeout_seconds=int(os.getenv("TIMEOUT_SECONDS", "15")),
    )


class Metrics:
    """Prometheus metrics bound to an explicit registry."""

    def __init__(self, registry: CollectorRegistry) -> None:
        self.active_distinct_users_per_app = Gauge(
            "keycloak_active_distinct_users_per_app",
            (
                "Distinct active Keycloak users per application client, excluding "
                "service accounts and configured excluded usernames"
            ),
            ["realm", "client"],
            registry=registry,
        )

        self.active_distinct_users_realm = Gauge(
            "keycloak_active_distinct_users_realm",
            (
                "Distinct active Keycloak users across all active non-excluded clients, "
                "excluding service accounts and configured excluded usernames"
            ),
            ["realm"],
            registry=registry,
        )

        self.enabled_users_realm = Gauge(
            "keycloak_enabled_users_realm",
            (
                "Enabled Keycloak user accounts, excluding service accounts "
                "and configured excluded usernames"
            ),
            ["realm"],
            registry=registry,
        )

        self.exporter_up = Gauge(
            "keycloak_active_users_exporter_up",
            "Whether the latest Keycloak collection succeeded: 1=yes, 0=no",
            registry=registry,
        )

        self.last_success = Gauge(
            "keycloak_active_users_exporter_last_success_unixtime",
            "Unix timestamp of the latest successful Keycloak collection",
            registry=registry,
        )


def configure_logging() -> logging.Logger:
    logging.basicConfig(
        level=os.getenv("LOG_LEVEL", "INFO").upper(),
        format="%(asctime)s %(levelname)s %(message)s",
    )
    return logging.getLogger("keycloak-active-users-exporter")


LOGGER = configure_logging()


def get_token(config: Config) -> str:
    response = requests.post(
        f"{config.kc_base}/realms/{config.kc_realm}/protocol/openid-connect/token",
        data={
            "grant_type": "client_credentials",
            "client_id": config.kc_client_id,
            "client_secret": config.kc_client_secret,
        },
        timeout=config.timeout_seconds,
    )
    response.raise_for_status()
    return response.json()["access_token"]


def keycloak_get(
    config: Config,
    path: str,
    token: str,
    params: Optional[Dict[str, Any]] = None,
) -> Any:
    response = requests.get(
        f"{config.kc_base}{path}",
        headers={"Authorization": f"Bearer {token}"},
        params=params,
        timeout=config.timeout_seconds,
    )
    response.raise_for_status()
    return response.json()


def normalise_client_session_stats(raw_stats: Any) -> Dict[str, int]:
    """Support map and list response formats for client-session-stats."""
    if isinstance(raw_stats, dict):
        return {
            client_id: int(active)
            for client_id, active in raw_stats.items()
        }

    if isinstance(raw_stats, list):
        return {
            item["clientId"]: int(item.get("active", 0))
            for item in raw_stats
            if isinstance(item, dict) and item.get("clientId")
        }

    raise RuntimeError(
        "Unexpected client-session-stats response type: "
        f"{type(raw_stats).__name__}"
    )


def get_client_uuid(config: Config, token: str, client_id: str) -> str:
    """Return Keycloak's internal UUID for a public OIDC client ID."""
    clients = keycloak_get(
        config,
        f"/admin/realms/{config.kc_realm}/clients",
        token,
        params={"clientId": client_id},
    )

    exact_matches = [
        client
        for client in clients
        if client.get("clientId") == client_id
    ]

    if len(exact_matches) != 1:
        raise RuntimeError(
            f"Expected exactly one Keycloak clientId={client_id!r}; "
            f"got {len(exact_matches)}"
        )

    return exact_matches[0]["id"]


def get_all_client_sessions(
    config: Config,
    token: str,
    client_uuid: str,
) -> List[Dict[str, Any]]:
    """Retrieve all currently active sessions for a Keycloak client."""
    sessions: List[Dict[str, Any]] = []
    first = 0
    page_size = 100

    while True:
        page = keycloak_get(
            config,
            f"/admin/realms/{config.kc_realm}/clients/{client_uuid}/user-sessions",
            token,
            params={"first": first, "max": page_size},
        )

        if not isinstance(page, list):
            raise RuntimeError(
                "Unexpected user-sessions response type: "
                f"{type(page).__name__}"
            )

        sessions.extend(page)

        if len(page) < page_size:
            return sessions

        first += page_size


def get_all_enabled_users(config: Config, token: str) -> List[Dict[str, Any]]:
    """Retrieve all enabled users in the realm, including service accounts."""
    users: List[Dict[str, Any]] = []
    first = 0
    page_size = 100

    while True:
        page = keycloak_get(
            config,
            f"/admin/realms/{config.kc_realm}/users",
            token,
            params={
                "enabled": "true",
                "briefRepresentation": "true",
                "first": first,
                "max": page_size,
            },
        )

        if not isinstance(page, list):
            raise RuntimeError(
                "Unexpected users response type: "
                f"{type(page).__name__}"
            )

        users.extend(page)

        if len(page) < page_size:
            return users

        first += page_size


def is_included_user(user: Dict[str, Any], excluded_usernames: Set[str]) -> bool:
    """Return True only for enabled human users not configured for exclusion."""
    username = str(user.get("username", "")).casefold()

    if user.get("enabled") is not True:
        return False

    # Prefer Keycloak's explicit field and retain a defensive naming fallback.
    if user.get("serviceAccountClientId") or username.startswith("service-account-"):
        return False

    return username not in excluded_usernames


def get_enabled_user_count(config: Config, token: str) -> int:
    """Count enabled human users, excluding service accounts and deny-listed users."""
    return sum(
        1
        for user in get_all_enabled_users(config, token)
        if is_included_user(user, config.excluded_usernames)
    )


def get_session_user_ids(
    config: Config,
    token: str,
    client_id: str,
) -> Set[str]:
    """Return included distinct user IDs with active sessions for one client."""
    client_uuid = get_client_uuid(config, token, client_id)
    sessions = get_all_client_sessions(config, token, client_uuid)

    return {
        session["userId"]
        for session in sessions
        if session.get("userId")
        and str(session.get("username", "")).casefold()
        not in config.excluded_usernames
        and not str(session.get("username", "")).casefold().startswith(
            "service-account-"
        )
    }


def set_tracked_client_zeroes(config: Config, metrics: Metrics) -> None:
    """Explicitly expose 0 for configured apps with no included active users."""
    for client_id in config.tracked_clients:
        metrics.active_distinct_users_per_app.labels(
            realm=config.kc_realm,
            client=client_id,
        ).set(0)


def collect(config: Config, metrics: Metrics) -> None:
    token = get_token(config)
    enabled_user_count = get_enabled_user_count(config, token)

    raw_stats = keycloak_get(
        config,
        f"/admin/realms/{config.kc_realm}/client-session-stats",
        token,
    )
    client_session_stats = normalise_client_session_stats(raw_stats)

    active_client_ids = {
        client_id
        for client_id, count in client_session_stats.items()
        if count > 0
    }

    included_realm_client_ids = active_client_ids - config.excluded_clients
    client_ids_to_query = config.tracked_clients | included_realm_client_ids
    realm_user_ids: Set[str] = set()

    set_tracked_client_zeroes(config, metrics)

    for client_id in sorted(client_ids_to_query):
        user_ids = get_session_user_ids(config, token, client_id)

        if client_id in config.tracked_clients:
            metrics.active_distinct_users_per_app.labels(
                realm=config.kc_realm,
                client=client_id,
            ).set(len(user_ids))

        if client_id in included_realm_client_ids:
            realm_user_ids.update(user_ids)

    metrics.active_distinct_users_realm.labels(
        realm=config.kc_realm,
    ).set(len(realm_user_ids))

    metrics.enabled_users_realm.labels(
        realm=config.kc_realm,
    ).set(enabled_user_count)

    metrics.last_success.set(time.time())
    metrics.exporter_up.set(1)

    LOGGER.info(
        (
            "Collected enabled_users=%d realm_active_users=%d "
            "tracked_apps=%d active_clients=%d included_clients=%d"
        ),
        enabled_user_count,
        len(realm_user_ids),
        len(config.tracked_clients),
        len(active_client_ids),
        len(included_realm_client_ids),
    )


def collection_loop(config: Config, metrics: Metrics) -> None:
    while True:
        try:
            collect(config, metrics)
        except Exception:
            metrics.exporter_up.set(0)
            LOGGER.exception("Keycloak collection failed")

        time.sleep(config.poll_seconds)


def main() -> None:
    config = load_config()
    registry = CollectorRegistry()
    metrics = Metrics(registry)

    start_http_server(config.http_port, registry=registry)
    
    LOGGER.info(
        "Monitoring Keycloak realm=%s at %s",
        config.kc_realm,
        config.kc_base,
    )
    
    threading.Thread(
        target=collection_loop,
        args=(config, metrics),
        daemon=True,
        name="keycloak-collector",
    ).start()

    while True:
        time.sleep(3600)


if __name__ == "__main__":
    main()