/*
 * Copyright © ${year}, ${owner} <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.UserSessionRepresentation;

class ActiveUsersExporterTest {
    private static final String KC_BASE = "http://keycloak:8080";
    private static final String KC_REALM = "ozone";
    private static final String KC_CLIENT_ID = "keycloak-active-users-exporter";
    private static final String KC_CLIENT_SECRET = "test-secret";

    @Test
    void exportsSessionsForEveryIncludedClient() {
        ExporterConfig config = config(Set.of("openmrs", "patient-portal"), Set.of());

        List<Map<String, String>> response = List.of(
                clientSessionStat("openmrs", "7"),
                clientSessionStat("patient-portal", "14"),
                clientSessionStat("account", "3"));

        ActiveUsersExporter.ClientSessionMetrics metrics = ActiveUsersExporter.toClientSessionMetrics(response, config);

        assertEquals(
                Map.of(
                        "openmrs", 7L,
                        "patient-portal", 14L),
                metrics.sessionsByClient());
        assertEquals(21L, metrics.realmSessionTotal());
    }

    @Test
    void excludedClientIsNotExportedOrIncludedInRealmTotal() {
        ExporterConfig config = config(Set.of(), Set.of("account", "admin-cli"));

        List<Map<String, String>> response = List.of(
                clientSessionStat("openmrs", "7"),
                clientSessionStat("account", "3"),
                clientSessionStat("admin-cli", "2"));

        ActiveUsersExporter.ClientSessionMetrics metrics = ActiveUsersExporter.toClientSessionMetrics(response, config);

        assertEquals(Map.of("openmrs", 7L), metrics.sessionsByClient());
        assertEquals(7L, metrics.realmSessionTotal());
    }

    @Test
    void emptySuccessfulResponseHasZeroRealmTotalAndNoClientMetrics() {
        ActiveUsersExporter.ClientSessionMetrics metrics =
                ActiveUsersExporter.toClientSessionMetrics(List.of(), config(Set.of(), Set.of()));

        assertTrue(metrics.sessionsByClient().isEmpty());
        assertEquals(0L, metrics.realmSessionTotal());
    }

    @Test
    void nullResponseHasZeroRealmTotalAndNoClientMetrics() {
        ActiveUsersExporter.ClientSessionMetrics metrics =
                ActiveUsersExporter.toClientSessionMetrics(null, config(Set.of(), Set.of()));

        assertTrue(metrics.sessionsByClient().isEmpty());
        assertEquals(0L, metrics.realmSessionTotal());
    }

    @Test
    void parsesKeycloakClientSessionStatsResponseShape() {
        ExporterConfig config = config(Set.of(), Set.of());

        List<Map<String, String>> response =
                List.of(clientSessionStat("openmrs", "7"), clientSessionStat("patient-portal", "14"));

        ActiveUsersExporter.ClientSessionMetrics metrics = ActiveUsersExporter.toClientSessionMetrics(response, config);

        assertEquals(7L, metrics.sessionsByClient().get("openmrs"));
        assertEquals(14L, metrics.sessionsByClient().get("patient-portal"));
        assertEquals(21L, metrics.realmSessionTotal());
    }

    @Test
    void ignoresStatisticsWithoutAClientId() {
        ExporterConfig config = config(Set.of(), Set.of());

        List<Map<String, String>> response = List.of(
                Map.of("active", "7", "offline", "0", "id", "internal-missing"), clientSessionStat("openmrs", "4"));

        ActiveUsersExporter.ClientSessionMetrics metrics = ActiveUsersExporter.toClientSessionMetrics(response, config);

        assertEquals(Map.of("openmrs", 4L), metrics.sessionsByClient());
        assertEquals(4L, metrics.realmSessionTotal());
    }

    @Test
    void invalidOrNegativeSessionValuesBecomeZero() {
        ExporterConfig config = config(Set.of(), Set.of());

        List<Map<String, String>> response = List.of(
                clientSessionStat("openmrs", "not-a-number"),
                clientSessionStat("patient-portal", "-2"),
                clientSessionStat("reporting", "0"));

        ActiveUsersExporter.ClientSessionMetrics metrics = ActiveUsersExporter.toClientSessionMetrics(response, config);

        assertEquals(0L, metrics.sessionsByClient().get("openmrs"));
        assertEquals(0L, metrics.sessionsByClient().get("patient-portal"));
        assertEquals(0L, metrics.sessionsByClient().get("reporting"));
        assertEquals(0L, metrics.realmSessionTotal());
    }

    @Test
    void doesNotTreatMetadataFieldsAsClientIds() {
        ExporterConfig config = config(Set.of(), Set.of());

        List<Map<String, String>> response = List.of(Map.of(
                "id", "70a0e2fd-2bb2-4417-9fc6-22cdca1bb5be",
                "clientId", "odoo",
                "active", "6",
                "offline", "0"));

        ActiveUsersExporter.ClientSessionMetrics metrics = ActiveUsersExporter.toClientSessionMetrics(response, config);

        assertEquals(Map.of("odoo", 6L), metrics.sessionsByClient());
        assertEquals(6L, metrics.realmSessionTotal());
        assertTrue(!metrics.sessionsByClient().containsKey("id"));
        assertTrue(!metrics.sessionsByClient().containsKey("active"));
        assertTrue(!metrics.sessionsByClient().containsKey("offline"));
    }

    @Test
    void deduplicatesUsersAcrossSessionsFromDifferentClients() {
        List<UserSessionRepresentation> odooSessions = List.of(session("user-a"), session("user-b"));
        List<UserSessionRepresentation> openElisSessions = List.of(session("user-a"), session("user-c"));

        Set<String> distinctUserIds =
                ActiveUsersExporter.collectDistinctUserIds(List.of(odooSessions, openElisSessions));

        assertEquals(Set.of("user-a", "user-b", "user-c"), distinctUserIds);
        assertEquals(3, distinctUserIds.size());
    }

    @Test
    void ignoresNullBlankAndMissingUserIdsDuringDeduplication() {
        List<UserSessionRepresentation> firstClientSessions = List.of(session("user-a"), session(" "), session(null));
        List<UserSessionRepresentation> secondClientSessions = List.of(session("user-a"));

        Set<String> distinctUserIds =
                ActiveUsersExporter.collectDistinctUserIds(List.of(firstClientSessions, secondClientSessions));

        assertEquals(Set.of("user-a"), distinctUserIds);
    }

    @Test
    void returnsNoDistinctUsersForEmptyOrNullClientSessionLists() {
        assertTrue(ActiveUsersExporter.collectDistinctUserIds(List.of()).isEmpty());
        assertTrue(ActiveUsersExporter.collectDistinctUserIds(null).isEmpty());
        assertTrue(
                ActiveUsersExporter.collectDistinctUserIds(List.of(List.of())).isEmpty());
    }

    @Test
    void resetsClientSessionValueToZeroWhenClientIsAbsentFromNextSuccessfulPoll() {
        Set<String> previouslyReportedClientIds = new HashSet<>();

        ActiveUsersExporter.ClientSessionMetrics firstPoll =
                new ActiveUsersExporter.ClientSessionMetrics(Map.of("odoo", 1L), 1L);

        ActiveUsersExporter.ClientSessionUpdate firstUpdate =
                ActiveUsersExporter.updateClientSessionValues(previouslyReportedClientIds, firstPoll);

        assertEquals(Map.of("odoo", 1L), firstUpdate.clientSessionValues());
        assertEquals(1L, firstUpdate.realmSessionTotal());
        assertEquals(Set.of("odoo"), previouslyReportedClientIds);

        ActiveUsersExporter.ClientSessionMetrics secondPoll =
                new ActiveUsersExporter.ClientSessionMetrics(Map.of(), 0L);

        ActiveUsersExporter.ClientSessionUpdate secondUpdate =
                ActiveUsersExporter.updateClientSessionValues(previouslyReportedClientIds, secondPoll);

        assertEquals(Map.of("odoo", 0L), secondUpdate.clientSessionValues());
        assertEquals(0L, secondUpdate.realmSessionTotal());
        assertTrue(previouslyReportedClientIds.isEmpty());
    }

    private static Map<String, String> clientSessionStat(String clientId, String active) {
        return Map.of("id", "internal-" + clientId, "clientId", clientId, "active", active, "offline", "0");
    }

    private static UserSessionRepresentation session(String userId) {
        UserSessionRepresentation session = new UserSessionRepresentation();
        session.setUserId(userId);
        return session;
    }

    private static ExporterConfig config(Set<String> includedClients, Set<String> excludedClients) {
        return new ExporterConfig(
                KC_BASE,
                KC_REALM,
                KC_CLIENT_ID,
                KC_CLIENT_SECRET,
                includedClients,
                excludedClients,
                Set.of(),
                60,
                9108,
                15);
    }
}
