# Keycloak Active Users Exporter

A standalone Java exporter that reads Keycloak Admin REST API session and user data and exposes Prometheus metrics at `/metrics`.

The exporter is intended to run separately from Keycloak, typically as a Docker Compose service. It uses a Keycloak service-account client with the OAuth 2.0 client-credentials grant.

## Metrics

All application/session metrics use the same global client filter: either `INCLUDED_CLIENTS` or `EXCLUDED_CLIENTS`. User exclusions in `EXCLUDED_USERNAMES` apply to user-based metrics.

|              Metric               |        Labels        |                                                                                                        Meaning                                                                                                        |
|-----------------------------------|----------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `keycloak_active_client_sessions` | `realm`, `client_id` | Current active Keycloak client sessions for each included client. A client metric is created after Keycloak first reports it. If that client is absent in a later successful poll, its value is set to `0`.           |
| `keycloak_active_realm_sessions`  | `realm`              | Sum of active client sessions across all included clients. This is a session count, not a unique-person count: one user with sessions in Odoo and OpenELIS contributes two sessions.                                  |
| `keycloak_active_realm_users`     | `realm`              | Number of distinct active users across included clients. A user with sessions in multiple included apps is counted once. Disabled users, service accounts, and usernames listed in `EXCLUDED_USERNAMES` are excluded. |
| `keycloak_enabled_users`          | `realm`              | Number of enabled, non-service-account users in the realm, excluding usernames in `EXCLUDED_USERNAMES`. This metric is realm-wide; it cannot be filtered by client because user records do not belong to one client.  |

### Sessions versus distinct users

Suppose the same user has an active session in both Odoo and OpenELIS, while five other users have active Odoo sessions:

```text
keycloak_active_client_sessions{client_id="odoo",realm="ozone"} 6
keycloak_active_client_sessions{client_id="openelis-central",realm="ozone"} 1

keycloak_active_realm_sessions{realm="ozone"} 7
keycloak_active_realm_users{realm="ozone"} 6
```

`keycloak_active_realm_sessions` is the sum of client sessions. `keycloak_active_realm_users` deduplicates user IDs across included clients.

## Configuration

Configuration is supplied through environment variables.

|         Key          | Required |                                    Default                                    |                                                                                             Description                                                                                             |
|----------------------|---------:|-------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `KC_BASE`            |      Yes | —                                                                             | Keycloak server base URL, for example `http://keycloak:8080` inside Docker Compose or `https://auth.example.org` from outside the Docker network. Trailing slashes are removed.                     |
| `KC_REALM`           |      Yes | —                                                                             | Realm to query, for example `ozone`. The exporter client and its service account should normally be created in this realm.                                                                          |
| `KC_CLIENT_ID`       |      Yes | —                                                                             | Confidential Keycloak client ID used by the exporter, for example `active-users-exporter`.                                                                                                          |
| `KC_CLIENT_SECRET`   |      Yes | —                                                                             | Secret for `KC_CLIENT_ID`. Never commit this value.                                                                                                                                                 |
| `INCLUDED_CLIENTS`   |       No | Empty                                                                         | Comma-separated allow-list of client IDs. When nonempty, only these exact client IDs are used for all session metrics and distinct active-user calculation. Do not combine with `EXCLUDED_CLIENTS`. |
| `EXCLUDED_CLIENTS`   |       No | Built-in Keycloak internal-client exclusions when `INCLUDED_CLIENTS` is empty | Comma-separated deny-list of client IDs. Used only when `INCLUDED_CLIENTS` is empty. Do not combine with `INCLUDED_CLIENTS`. An explicitly empty value means no client exclusions.                  |
| `EXCLUDED_USERNAMES` |       No | Empty                                                                         | Comma-separated usernames excluded from all user-based metrics. Username matching is case-insensitive.                                                                                              |
| `POLL_SECONDS`       |       No | `60`                                                                          | Delay, in seconds, between completed polling cycles. Must be greater than zero.                                                                                                                     |
| `HTTP_PORT`          |       No | `9108`                                                                        | Local HTTP port on which the exporter exposes `/metrics`. Must be between `1` and `65535`.                                                                                                          |
| `TIMEOUT_SECONDS`    |       No | `15`                                                                          | Reserved configuration value for the Keycloak request timeout. Must be greater than zero.                                                                                                           |

### Client filter rules

Client IDs are matched exactly; the exporter does not lowercase or otherwise normalize them. Use the exact Keycloak client IDs, such as `odoo` or `openelis-central`.

Use one filtering strategy only.

Allow-list example:

```dotenv
INCLUDED_CLIENTS=odoo,openelis-central,superset
```

Deny-list example:

```dotenv
EXCLUDED_CLIENTS=account,account-console,security-admin-console,admin-cli
```

Do not use both:

```dotenv
# Invalid configuration
INCLUDED_CLIENTS=odoo,openelis-central
EXCLUDED_CLIENTS=account
```

For a deliberate allow-list, omit `EXCLUDED_CLIENTS`.

### Required Keycloak config

The app expects a service account to be created and configured in Keycloak.
Create a confidential client with service accounts enabled in the target realm. Assign these client roles from the realm's `realm-management` client to the exporter's service account:

```text
view-realm
view-clients
view-users
query-users
```

## Docker Compose example

See the example [docker-compose.yml](./docker-compose.yml) provided by the project.

## Build and run

Build the application and execute tests:

```fish
mvn clean package
```

Assuming you have properly configured a service account on a running Keycloak instance:

Export the service account secret:

```fish
set -x KC_ACTIVE_USERS_EXPORTER_CLIENT_SECRET 'replace-with-the-real-secret'
```

Verify that `KC_BASE` `KC_REALM` and `KC_CLIENT_ID` are correctly set in the Docker Compose

Build and start:

```
docker compose up --build -d keycloak-active-users-exporter
```

Follow exporter logs:

```fish
docker compose logs --follow keycloak-active-users-exporter
```

Inspect metrics:

```fish
curl --fail http://localhost:9108/metrics | grep '^keycloak_'
```

A healthy initial poll should expose:

```text
keycloak_exporter_poll_success{realm="ozone"} 1.0
```

## Troubleshooting

### `HTTP 403 Forbidden`

The client secret is usually valid when Keycloak returns `403`; the service account token was accepted but lacks authorization. Confirm that the service account in the target realm has `realm-management` roles `view-realm`, `view-clients`, `view-users`, and `query-users`.

### A client metric remains nonzero after logout

After a client has first been reported, the exporter explicitly publishes `0` when that client is absent from a later successful `client-session-stats` response. If a nonzero value remains, check `keycloak_exporter_poll_success`: the exporter must complete a successful new poll before it can reset the gauge.

### No per-client metrics

Verify that `INCLUDED_CLIENTS` exactly matches the client IDs returned by Keycloak. The exporter logs selected filters at startup. Client IDs are case-sensitive.

For temporary diagnosis, enable or change the exporter log statements for the raw `client-session-stats` response and the filtered result from `FINE` to `INFO`.
