package org.loomsip.message;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable ordered collection of SIP headers.
 *
 * <p>The collection preserves duplicate fields and insertion order. Lookups
 * are case-insensitive and treat RFC compact names, such as {@code v} and
 * {@code Via}, as equivalent.</p>
 */
public final class SipHeaders {

    private static final Map<String, String> COMPACT_NAMES = Map.ofEntries(
            Map.entry("b", "referred-by"),
            Map.entry("c", "content-type"),
            Map.entry("e", "content-encoding"),
            Map.entry("f", "from"),
            Map.entry("i", "call-id"),
            Map.entry("k", "supported"),
            Map.entry("l", "content-length"),
            Map.entry("m", "contact"),
            Map.entry("o", "event"),
            Map.entry("r", "refer-to"),
            Map.entry("s", "subject"),
            Map.entry("t", "to"),
            Map.entry("u", "allow-events"),
            Map.entry("v", "via")
    );

    private static final SipHeaders EMPTY = new SipHeaders(List.of());

    private final List<SipHeader> entries;

    private SipHeaders(List<SipHeader> entries) {
        this.entries = List.copyOf(entries);
    }

    /**
     * Returns the shared empty header collection.
     *
     * @return immutable empty headers
     */
    public static SipHeaders empty() {
        return EMPTY;
    }

    /**
     * Creates an immutable collection in the order of the supplied list.
     *
     * @param headers headers to copy
     * @return immutable ordered headers
     * @throws NullPointerException if the list or one of its entries is {@code null}
     */
    public static SipHeaders of(List<SipHeader> headers) {
        Objects.requireNonNull(headers, "headers");
        return headers.isEmpty() ? EMPTY : new SipHeaders(headers);
    }

    /**
     * Creates an empty mutable builder.
     *
     * @return a new header builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns all entries in message order.
     *
     * @return unmodifiable header list
     */
    public List<SipHeader> entries() {
        return entries;
    }

    /**
     * Finds the first field with the specified standard or compact name.
     *
     * @param name case-insensitive standard or compact header name
     * @return the first matching header, or an empty optional
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public Optional<SipHeader> first(String name) {
        Objects.requireNonNull(name, "name");
        return entries.stream().filter(header -> namesEqual(header.name(), name)).findFirst();
    }

    /**
     * Finds the value of the first matching field.
     *
     * @param name case-insensitive standard or compact header name
     * @return the first matching value, or an empty optional
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public Optional<String> firstValue(String name) {
        return first(name).map(SipHeader::value);
    }

    /**
     * Returns every matching field in message order.
     *
     * @param name case-insensitive standard or compact header name
     * @return unmodifiable list of matching fields
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public List<SipHeader> all(String name) {
        Objects.requireNonNull(name, "name");
        return entries.stream().filter(header -> namesEqual(header.name(), name)).toList();
    }

    /**
     * Tests whether at least one equivalent field name is present.
     *
     * @param name case-insensitive standard or compact header name
     * @return {@code true} if a matching field exists
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public boolean contains(String name) {
        return first(name).isPresent();
    }

    /**
     * Creates a builder initialized with the current ordered entries.
     *
     * @return a mutable copy builder
     */
    public Builder toBuilder() {
        return new Builder().addAll(entries);
    }

    /**
     * Returns a copy without fields equivalent to the supplied name.
     *
     * <p>Standard and compact names are treated as equivalent, so removing
     * {@code Via} also removes fields written as {@code v}.</p>
     *
     * @param name standard or compact header name to remove
     * @return this instance when no field matched, otherwise a filtered copy
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public SipHeaders without(String name) {
        Objects.requireNonNull(name, "name");
        List<SipHeader> filtered = entries.stream()
                .filter(header -> !namesEqual(header.name(), name))
                .toList();
        return filtered.size() == entries.size() ? this : SipHeaders.of(filtered);
    }

    /**
     * Returns a copy containing exactly one field with the supplied name.
     *
     * <p>The replacement occupies the position of the first equivalent field;
     * additional duplicates are removed. If no equivalent field exists, the
     * replacement is appended.</p>
     *
     * @param name header name to replace
     * @param value replacement unfolded value
     * @return immutable replaced copy
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the replacement is syntactically invalid
     */
    public SipHeaders withReplaced(String name, String value) {
        SipHeader replacement = new SipHeader(name, value);
        List<SipHeader> replaced = new ArrayList<>(entries.size() + 1);
        boolean inserted = false;
        for (SipHeader header : entries) {
            if (namesEqual(header.name(), name)) {
                if (!inserted) {
                    replaced.add(replacement);
                    inserted = true;
                }
            } else {
                replaced.add(header);
            }
        }
        if (!inserted) {
            replaced.add(replacement);
        }
        return SipHeaders.of(replaced);
    }

    /**
     * Compares two field names using SIP case and compact-name rules.
     *
     * @param first first field name
     * @param second second field name
     * @return {@code true} when both names identify the same SIP field
     * @throws NullPointerException if either name is {@code null}
     */
    public static boolean namesEqual(String first, String second) {
        return canonicalName(first).equals(canonicalName(second));
    }

    private static String canonicalName(String name) {
        String lowerCaseName = name.toLowerCase(Locale.ROOT);
        return COMPACT_NAMES.getOrDefault(lowerCaseName, lowerCaseName);
    }

    @Override
    public boolean equals(Object object) {
        return object == this || object instanceof SipHeaders that && entries.equals(that.entries);
    }

    @Override
    public int hashCode() {
        return entries.hashCode();
    }

    @Override
    public String toString() {
        return entries.toString();
    }

    /**
     * Mutable builder that accumulates headers while retaining insertion order.
     * A built {@link SipHeaders} instance is independent from later additions.
     */
    public static final class Builder {

        private final List<SipHeader> entries = new ArrayList<>();

        /**
         * Creates an empty header builder.
         */
        public Builder() {
        }

        /**
         * Adds a header after validating its name and value.
         *
         * @param name SIP header name
         * @param value unfolded header value
         * @return this builder
         * @throws NullPointerException if an argument is {@code null}
         * @throws IllegalArgumentException if the field is syntactically invalid
         */
        public Builder add(String name, String value) {
            return add(new SipHeader(name, value));
        }

        /**
         * Adds an existing header.
         *
         * @param header header to append
         * @return this builder
         * @throws NullPointerException if {@code header} is {@code null}
         */
        public Builder add(SipHeader header) {
            entries.add(Objects.requireNonNull(header, "header"));
            return this;
        }

        /**
         * Appends all supplied headers in list order.
         *
         * @param headers headers to append
         * @return this builder
         * @throws NullPointerException if the list or one of its entries is {@code null}
         */
        public Builder addAll(List<SipHeader> headers) {
            Objects.requireNonNull(headers, "headers").forEach(this::add);
            return this;
        }

        /**
         * Removes all fields equivalent to the supplied standard or compact name.
         *
         * @param name header name to remove
         * @return this builder
         * @throws NullPointerException if {@code name} is {@code null}
         */
        public Builder remove(String name) {
            Objects.requireNonNull(name, "name");
            entries.removeIf(header -> namesEqual(header.name(), name));
            return this;
        }

        /**
         * Replaces all equivalent fields with one field at the first match position.
         *
         * @param name header name to replace
         * @param value replacement unfolded value
         * @return this builder
         * @throws NullPointerException if an argument is {@code null}
         * @throws IllegalArgumentException if the replacement is syntactically invalid
         */
        public Builder replace(String name, String value) {
            SipHeaders replaced = SipHeaders.of(entries).withReplaced(name, value);
            entries.clear();
            entries.addAll(replaced.entries());
            return this;
        }

        /**
         * Creates an immutable snapshot of the accumulated headers.
         *
         * @return immutable ordered headers
         */
        public SipHeaders build() {
            return SipHeaders.of(entries);
        }
    }
}
