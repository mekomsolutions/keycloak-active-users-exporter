/*
 * Copyright © ${year}, ${owner} <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.metrics;

import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.exporter.httpserver.HTTPServer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.representations.idm.UserSessionRepresentation;

public final class ActiveUsersExporter {
    private static final Logger LOGGER = Logger.getLogger(ActiveUsersExporter.class.getName());

    private static final String METRIC_PREFIX = "keycloak";
    private static final int PAGE_SIZE = 100;

    private final ExporterConfig config;
    private final Keycloak keycloak;
    private final Set<String> previouslyReportedClientIds = new HashSet<>();

    private final Gauge activeClientSessions;
    private final Gauge activeRealmSessions;
    private final Gauge activeRealmUsers;
    private final Gauge enabledUsers;
    private final Gauge pollSuccess;
    private final Gauge lastSuccessfulPollTimestamp;
    private final Gauge pollDurationSeconds;

    private ActiveUsersExporter(ExporterConfig config, Keycloak keycloak) {
        this.config = Objects.requireNonNull(config, "config");
        this.keycloak = Objects.requireNonNull(keycloak, "keycloak");

        activeClientSessions = Gauge.builder()
                .name(METRIC_PREFIX + "_active_client_sessions")
                .help("Active Keycloak client sessions after applying global client filters.")
                .labelNames("realm", "client_id")
                .register();

        activeRealmSessions = Gauge.builder()
                .name(METRIC_PREFIX + "_active_realm_sessions")
                .help("Sum of active Keycloak client sessions after applying global client filters.")
                .labelNames("realm")
                .register();

        activeRealmUsers = Gauge.builder()
                .name(METRIC_PREFIX + "_active_realm_users")
                .help("Distinct active Keycloak users across included clients after applying global filters.")
                .labelNames("realm")
                .register();

        enabledUsers = Gauge.builder()
                .name(METRIC_PREFIX + "_enabled_users")
                .help("Enabled non-service-account Keycloak users after applying excluded usernames.")
                .labelNames("realm")
                .register();

        pollSuccess = Gauge.builder()
                .name(METRIC_PREFIX + "_exporter_poll_success")
                .help("Whether the latest Keycloak polling operation succeeded: 1 for success, 0 for failure.")
                .labelNames("realm")
                .register();

        lastSuccessfulPollTimestamp = Gauge.builder()
                .name(METRIC_PREFIX + "_exporter_last_successful_poll_timestamp_seconds")
                .help("Unix timestamp of the latest successful Keycloak poll.")
                .labelNames("realm")
                .register();

        pollDurationSeconds = Gauge.builder()
                .name(METRIC_PREFIX + "_exporter_poll_duration_seconds")
                .help("Duration in seconds of the latest Keycloak polling operation.")
                .labelNames("realm")
                .register();
    }

    public static void main(String[] args) throws Exception {
        ExporterConfig config = ExporterConfig.fromEnvironment();

        LOGGER.info(() -> "Connecting to Keycloak at " + config.kcBase()
                + " for realm '" + config.kcRealm() + "'"
                + " using client '" + config.kcClientId() + "'");
        LOGGER.info(() -> "Client filters: includedClients="
                + config.includedClients()
                + ", excludedClients=" + config.excludedClients()
                + ", excludedUsernames=" + config.excludedUsernames());

        Keycloak keycloak = KeycloakBuilder.builder()
                .serverUrl(config.kcBase())
                .realm(config.kcRealm())
                .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                .clientId(config.kcClientId())
                .clientSecret(config.kcClientSecret())
                .build();

        ActiveUsersExporter exporter = new ActiveUsersExporter(config, keycloak);

        HTTPServer httpServer = HTTPServer.builder().port(config.httpPort()).buildAndStart();

        LOGGER.info(() -> "Prometheus metrics available at http://0.0.0.0:" + httpServer.getPort() + "/metrics");

        Runtime.getRuntime()
                .addShutdownHook(new Thread(
                        () -> {
                            LOGGER.info("Stopping Keycloak active-users exporter");
                            exporter.close();
                        },
                        "keycloak-metrics-shutdown"));

        exporter.poll();

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "keycloak-metrics-poller");
            thread.setDaemon(false);
            return thread;
        });

        scheduler.scheduleWithFixedDelay(exporter::poll, config.pollSeconds(), config.pollSeconds(), TimeUnit.SECONDS);

        Thread.currentThread().join();
    }

    private void poll() {
        Instant startedAt = Instant.now();

        try {
            List<Map<String, String>> clientSessionStats =
                    keycloak.realm(config.kcRealm()).getClientSessionStats();

            LOGGER.fine(() -> "Keycloak client-session-stats response: " + clientSessionStats);

            ClientSessionMetrics sessionMetrics = toClientSessionMetrics(clientSessionStats, config);

            LOGGER.fine(() -> "Included client session metrics: "
                    + sessionMetrics.sessionsByClient()
                    + "; realm session total: "
                    + sessionMetrics.realmSessionTotal());

            updateClientSessionMetrics(sessionMetrics);
            updateActiveRealmUsersMetric(sessionMetrics.sessionsByClient());
            updateEnabledUsersMetric();

            pollSuccess.labelValues(config.kcRealm()).set(1);
            lastSuccessfulPollTimestamp
                    .labelValues(config.kcRealm())
                    .set(Instant.now().getEpochSecond());

            LOGGER.fine("Keycloak metric polling completed successfully");
        } catch (Exception exception) {
            pollSuccess.labelValues(config.kcRealm()).set(0);

            LOGGER.log(
                    Level.WARNING,
                    "Keycloak metric polling failed; previously collected metric values remain exposed",
                    exception);
        } finally {
            double elapsedSeconds = Duration.between(startedAt, Instant.now()).toNanos() / 1_000_000_000.0;

            pollDurationSeconds.labelValues(config.kcRealm()).set(elapsedSeconds);
        }
    }

    private void updateClientSessionMetrics(ClientSessionMetrics metrics) {
        ClientSessionUpdate update = updateClientSessionValues(previouslyReportedClientIds, metrics);

        for (Map.Entry<String, Long> entry : update.clientSessionValues().entrySet()) {
            activeClientSessions.labelValues(config.kcRealm(), entry.getKey()).set(entry.getValue());
        }

        activeRealmSessions.labelValues(config.kcRealm()).set(update.realmSessionTotal());
    }

    private void updateActiveRealmUsersMetric(Map<String, Long> activeSessionsByClient) {
        Set<String> activeUserIds = new HashSet<>();

        for (String clientId : activeSessionsByClient.keySet()) {
            ClientRepresentation client = findClientByClientId(clientId);

            if (client == null || client.getId() == null || client.getId().isBlank()) {
                LOGGER.warning(
                        () -> "Unable to resolve Keycloak internal client ID " + "for client_id '" + clientId + "'");
                continue;
            }

            activeUserIds.addAll(collectDistinctUserIds(List.of(getActiveSessionsForClient(client.getId()))));
        }

        long distinctIncludedUsers =
                activeUserIds.stream().filter(this::isIncludedActiveUser).count();

        activeRealmUsers.labelValues(config.kcRealm()).set(distinctIncludedUsers);
    }

    private ClientRepresentation findClientByClientId(String clientId) {
        List<ClientRepresentation> clients =
                keycloak.realm(config.kcRealm()).clients().findByClientId(clientId);

        if (clients == null || clients.isEmpty()) {
            return null;
        }

        return clients.getFirst();
    }

    private List<UserSessionRepresentation> getActiveSessionsForClient(String internalClientId) {
        List<UserSessionRepresentation> allSessions = new ArrayList<>();
        int firstResult = 0;

        while (true) {
            List<UserSessionRepresentation> sessions = keycloak.realm(config.kcRealm())
                    .clients()
                    .get(internalClientId)
                    .getUserSessions(firstResult, PAGE_SIZE);

            if (sessions == null || sessions.isEmpty()) {
                return allSessions;
            }

            allSessions.addAll(sessions);

            if (sessions.size() < PAGE_SIZE) {
                return allSessions;
            }

            firstResult += PAGE_SIZE;
        }
    }

    private boolean isIncludedActiveUser(String userId) {
        UserRepresentation user =
                keycloak.realm(config.kcRealm()).users().get(userId).toRepresentation();

        return isIncludedUser(user);
    }

    private void updateEnabledUsersMetric() {
        long enabledUserCount = 0;
        int firstResult = 0;

        while (true) {
            List<UserRepresentation> users =
                    keycloak.realm(config.kcRealm()).users().list(firstResult, PAGE_SIZE);

            if (users == null || users.isEmpty()) {
                break;
            }

            for (UserRepresentation user : users) {
                if (isIncludedUser(user)) {
                    enabledUserCount++;
                }
            }

            if (users.size() < PAGE_SIZE) {
                break;
            }

            firstResult += PAGE_SIZE;
        }

        enabledUsers.labelValues(config.kcRealm()).set(enabledUserCount);
    }

    private boolean isIncludedUser(UserRepresentation user) {
        if (user == null || !Boolean.TRUE.equals(user.isEnabled())) {
            return false;
        }

        String serviceAccountClientId = user.getServiceAccountClientId();

        if (serviceAccountClientId != null && !serviceAccountClientId.isBlank()) {
            return false;
        }

        return config.isIncludedUsername(user.getUsername());
    }

    static ClientSessionMetrics toClientSessionMetrics(
            List<Map<String, String>> clientSessionStats, ExporterConfig config) {
        Objects.requireNonNull(config, "config");

        Map<String, Long> sessionsByClient = new LinkedHashMap<>();
        long realmSessionTotal = 0;

        if (clientSessionStats == null || clientSessionStats.isEmpty()) {
            return new ClientSessionMetrics(Map.of(), 0);
        }

        for (Map<String, String> clientStat : clientSessionStats) {
            if (clientStat == null || clientStat.isEmpty()) {
                continue;
            }

            String clientId = clientStat.get("clientId");

            if (clientId == null || clientId.isBlank()) {
                LOGGER.warning(
                        () -> "Ignoring Keycloak client session statistic " + "without a clientId: " + clientStat);
                continue;
            }

            if (!config.isIncludedClient(clientId)) {
                continue;
            }

            long activeSessions = parseNonNegativeLong(
                    clientStat.get("active"), "active session count for client '" + clientId + "'");

            sessionsByClient.put(clientId, activeSessions);
            realmSessionTotal += activeSessions;
        }

        return new ClientSessionMetrics(Map.copyOf(sessionsByClient), realmSessionTotal);
    }

    static ClientSessionUpdate updateClientSessionValues(
            Set<String> previouslyReportedClientIds, ClientSessionMetrics currentMetrics) {
        Objects.requireNonNull(previouslyReportedClientIds, "previouslyReportedClientIds");
        Objects.requireNonNull(currentMetrics, "currentMetrics");

        Map<String, Long> valuesToPublish = new LinkedHashMap<>(currentMetrics.sessionsByClient());

        for (String previousClientId : previouslyReportedClientIds) {
            if (!currentMetrics.sessionsByClient().containsKey(previousClientId)) {
                valuesToPublish.put(previousClientId, 0L);
            }
        }

        previouslyReportedClientIds.clear();
        previouslyReportedClientIds.addAll(currentMetrics.sessionsByClient().keySet());

        return new ClientSessionUpdate(Map.copyOf(valuesToPublish), currentMetrics.realmSessionTotal());
    }

    static Set<String> collectDistinctUserIds(Collection<List<UserSessionRepresentation>> sessionsByClient) {
        Set<String> userIds = new HashSet<>();

        if (sessionsByClient == null || sessionsByClient.isEmpty()) {
            return userIds;
        }

        for (List<UserSessionRepresentation> sessions : sessionsByClient) {
            if (sessions == null || sessions.isEmpty()) {
                continue;
            }

            for (UserSessionRepresentation session : sessions) {
                if (session == null) {
                    continue;
                }

                String userId = session.getUserId();

                if (userId != null && !userId.isBlank()) {
                    userIds.add(userId);
                }
            }
        }

        return userIds;
    }

    private static long parseNonNegativeLong(String value, String description) {
        if (value == null || value.isBlank()) {
            return 0;
        }

        try {
            return Math.max(Long.parseLong(value), 0);
        } catch (NumberFormatException exception) {
            LOGGER.warning(() -> "Unable to parse " + description + " from Keycloak response: '" + value + "'");
            return 0;
        }
    }

    private void close() {
        try {
            keycloak.close();
        } catch (Exception exception) {
            LOGGER.log(Level.FINE, "Failed to close Keycloak Admin Client", exception);
        }
    }

    record ClientSessionMetrics(Map<String, Long> sessionsByClient, long realmSessionTotal) {}

    record ClientSessionUpdate(Map<String, Long> clientSessionValues, long realmSessionTotal) {}
}
