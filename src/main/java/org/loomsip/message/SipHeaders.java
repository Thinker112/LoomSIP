package org.loomsip.message;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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

    public static SipHeaders empty() {
        return EMPTY;
    }

    public static SipHeaders of(List<SipHeader> headers) {
        Objects.requireNonNull(headers, "headers");
        return headers.isEmpty() ? EMPTY : new SipHeaders(headers);
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<SipHeader> entries() {
        return entries;
    }

    public Optional<SipHeader> first(String name) {
        Objects.requireNonNull(name, "name");
        return entries.stream().filter(header -> namesEqual(header.name(), name)).findFirst();
    }

    public Optional<String> firstValue(String name) {
        return first(name).map(SipHeader::value);
    }

    public List<SipHeader> all(String name) {
        Objects.requireNonNull(name, "name");
        return entries.stream().filter(header -> namesEqual(header.name(), name)).toList();
    }

    public boolean contains(String name) {
        return first(name).isPresent();
    }

    public Builder toBuilder() {
        return new Builder().addAll(entries);
    }

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

    public static final class Builder {

        private final List<SipHeader> entries = new ArrayList<>();

        public Builder add(String name, String value) {
            return add(new SipHeader(name, value));
        }

        public Builder add(SipHeader header) {
            entries.add(Objects.requireNonNull(header, "header"));
            return this;
        }

        public Builder addAll(List<SipHeader> headers) {
            Objects.requireNonNull(headers, "headers").forEach(this::add);
            return this;
        }

        public SipHeaders build() {
            return SipHeaders.of(entries);
        }
    }
}
