package mygrant.policies;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Validation and normalization for ISO 3166-1 alpha-2 country identifiers. */
public final class CountryIdentifiers {
    private static final Set<String> ISO_ALPHA_2 = Set.copyOf(
            Stream.of(Locale.getISOCountries()).collect(Collectors.toSet()));

    private CountryIdentifiers() { }

    public static String normalize(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Country identifiers must not be null");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!ISO_ALPHA_2.contains(normalized)) {
            throw new IllegalArgumentException(
                    "Invalid country identifier '" + value + "'; use an ISO 3166-1 alpha-2 code");
        }
        return normalized;
    }
}
