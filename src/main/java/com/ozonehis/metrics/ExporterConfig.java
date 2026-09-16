/*
 * Copyright © ${year}, ${owner} <info@ozone-his.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.ozonehis.metrics;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record ExporterConfig(
        String kcBase,
        String kcRealm,
        String kcClientId,
        String kcClientSecret,
        Set<String> includedClients,
        Set<String> excludedClients,
        Set<String> excludedUsernames,
        long pollSeconds,
        int httpPort,
        long timeoutSeconds) {

    private static final String DEFAULT_EXCLUDED_CLIENTS = String.join(
            ",",
            "account",
            "account-console",
            "security-admin-console",
            "admin-cli",
            "broker",
            "realm-management",
            "keycloak-active-users-exporter");

    public ExporterConfig {
        kcBase = requireNonBlank(kcBase, "KC_BASE").replaceAll("/+$", "");
        kcRealm = requireNonBlank(kcRealm, "KC_REALM");
        kcClientId = requireNonBlank(kcClientId, "KC_CLIENT_ID");
        kcClientSecret = requireNonBlank(kcClientSecret, "KC_CLIENT_SECRET");

        includedClients = immutableCopy(includedClients);
        excludedClients = immutableCopy(excludedClients);
        excludedUsernames = normalizeUsernames(excludedUsernames);

        if (!includedClients.isEmpty() && !excludedClients.isEmpty()) {
            throw new IllegalArgumentException("Set either INCLUDED_CLIENTS or EXCLUDED_CLIENTS, not both");
        }

        if (pollSeconds <= 0) {
            throw new IllegalArgumentException("POLL_SECONDS must be greater than 0");
        }

        if (httpPort < 1 || httpPort > 65535) {
            throw new IllegalArgumentException("HTTP_PORT must be between 1 and 65535");
        }

        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("TIMEOUT_SECONDS must be greater than 0");
        }
    }

    public static ExporterConfig fromEnvironment() {
        Map<String, String> environment = System.getenv();

        Set<String> includedClients = parseSet(environment.get("INCLUDED_CLIENTS"), false);

        Set<String> excludedClients;

        if (!includedClients.isEmpty()) {
            excludedClients = Set.of();
        } else if (environment.containsKey("EXCLUDED_CLIENTS")) {
            excludedClients = parseSet(environment.get("EXCLUDED_CLIENTS"), false);
        } else {
            excludedClients = parseSet(DEFAULT_EXCLUDED_CLIENTS, false);
        }

        return new ExporterConfig(
                requireEnvironment("KC_BASE"),
                requireEnvironment("KC_REALM"),
                requireEnvironment("KC_CLIENT_ID"),
                requireEnvironment("KC_CLIENT_SECRET"),
                includedClients,
                excludedClients,
                parseSet(environment.get("EXCLUDED_USERNAMES"), true),
                parseLong("POLL_SECONDS", 60),
                parseInt("HTTP_PORT", 9108),
                parseLong("TIMEOUT_SECONDS", 15));
    }

    public boolean isIncludedClient(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return false;
        }

        if (!includedClients.isEmpty()) {
            return includedClients.contains(clientId);
        }

        return !excludedClients.contains(clientId);
    }

    public boolean isIncludedUsername(String username) {
        return username != null && !excludedUsernames.contains(username.toLowerCase(Locale.ROOT));
    }

    private static Set<String> immutableCopy(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }

        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    private static Set<String> parseSet(String value, boolean caseInsensitive) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }

        Set<String> values = new LinkedHashSet<>();

        Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .map(item -> caseInsensitive ? item.toLowerCase(Locale.ROOT) : item)
                .forEach(values::add);

        return values;
    }

    private static int parseInt(String variable, int defaultValue) {
        String value = System.getenv(variable);

        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(variable + " must be an integer", exception);
        }
    }

    private static long parseLong(String variable, long defaultValue) {
        String value = System.getenv(variable);

        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(variable + " must be an integer", exception);
        }
    }

    private static String requireEnvironment(String variable) {
        return requireNonBlank(System.getenv(variable), variable);
    }

    private static String requireNonBlank(String value, String variable) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(variable + " is required");
        }

        return value.trim();
    }

    private static Set<String> normalizeUsernames(Set<String> usernames) {
        if (usernames == null || usernames.isEmpty()) {
            return Set.of();
        }

        Set<String> normalized = new LinkedHashSet<>();

        usernames.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(username -> !username.isEmpty())
                .map(username -> username.toLowerCase(Locale.ROOT))
                .forEach(normalized::add);

        return Collections.unmodifiableSet(normalized);
    }
}
