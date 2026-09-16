package com.ozonehis.metrics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ExporterConfigTest {

    private static final String KC_BASE = "http://keycloak:8080";
    private static final String KC_REALM = "ozone";
    private static final String KC_CLIENT_ID = "keycloak-active-users-exporter";
    private static final String KC_CLIENT_SECRET = "test-secret";

    @Test
    void allowListIncludesOnlyConfiguredClients() {
        ExporterConfig config = config(Set.of("openmrs", "patient-portal"), Set.of(), Set.of());

        assertTrue(config.isIncludedClient("openmrs"));
        assertTrue(config.isIncludedClient("patient-portal"));
        assertFalse(config.isIncludedClient("account"));
        assertFalse(config.isIncludedClient("realm-management"));
        assertFalse(config.isIncludedClient(null));
        assertFalse(config.isIncludedClient(""));
    }

    @Test
    void denyListExcludesOnlyConfiguredClients() {
        ExporterConfig config = config(Set.of(), Set.of("account", "admin-cli"), Set.of());

        assertTrue(config.isIncludedClient("openmrs"));
        assertTrue(config.isIncludedClient("patient-portal"));
        assertFalse(config.isIncludedClient("account"));
        assertFalse(config.isIncludedClient("admin-cli"));
    }

    @Test
    void emptyClientListsIncludeAllNonBlankClients() {
        ExporterConfig config = config(Set.of(), Set.of(), Set.of());

        assertTrue(config.isIncludedClient("openmrs"));
        assertTrue(config.isIncludedClient("account"));
        assertFalse(config.isIncludedClient(null));
        assertFalse(config.isIncludedClient(" "));
    }

    @Test
    void cannotConfigureAllowListAndDenyListTogether() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> config(Set.of("openmrs"), Set.of("account"), Set.of()));

        assertTrue(exception.getMessage().contains("INCLUDED_CLIENTS or EXCLUDED_CLIENTS"));
    }

    @Test
    void excludedUsernamesAreCaseInsensitive() {
        ExporterConfig config = config(Set.of(), Set.of(), Set.of("admin", "IMPORT-BOT"));

        assertFalse(config.isIncludedUsername("admin"));
        assertFalse(config.isIncludedUsername("ADMIN"));
        assertFalse(config.isIncludedUsername("Import-Bot"));
        assertTrue(config.isIncludedUsername("clinician"));
        assertFalse(config.isIncludedUsername(null));
    }

    @Test
    void removesTrailingSlashesFromKeycloakBaseUrl() {
        ExporterConfig config = new ExporterConfig(
                "http://keycloak:8080///",
                KC_REALM,
                KC_CLIENT_ID,
                KC_CLIENT_SECRET,
                Set.of(),
                Set.of(),
                Set.of(),
                60,
                9108,
                15);

        assertTrue(config.kcBase().equals("http://keycloak:8080"));
    }

    @Test
    void rejectsInvalidPollingAndHttpConfiguration() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExporterConfig(
                        KC_BASE, KC_REALM, KC_CLIENT_ID, KC_CLIENT_SECRET, Set.of(), Set.of(), Set.of(), 0, 9108, 15));

        assertThrows(
                IllegalArgumentException.class,
                () -> new ExporterConfig(
                        KC_BASE, KC_REALM, KC_CLIENT_ID, KC_CLIENT_SECRET, Set.of(), Set.of(), Set.of(), 60, 0, 15));

        assertThrows(
                IllegalArgumentException.class,
                () -> new ExporterConfig(
                        KC_BASE, KC_REALM, KC_CLIENT_ID, KC_CLIENT_SECRET, Set.of(), Set.of(), Set.of(), 60, 9108, 0));
    }

    private static ExporterConfig config(
            Set<String> includedClients, Set<String> excludedClients, Set<String> excludedUsernames) {
        return new ExporterConfig(
                KC_BASE,
                KC_REALM,
                KC_CLIENT_ID,
                KC_CLIENT_SECRET,
                includedClients,
                excludedClients,
                excludedUsernames,
                60,
                9108,
                15);
    }
}
