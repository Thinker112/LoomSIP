package org.loomsip.message.header;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Typed RFC 6086 {@code Recv-Info} capability list.
 *
 * <p>Package order is retained for wire rendering. Package names are unique
 * under case-insensitive comparison so an advertised capability has one
 * unambiguous dispatch key.</p>
 *
 * @param packages advertised INFO packages in wire order
 */
public record RecvInfoHeaderValue(List<InfoPackageHeaderValue> packages) {

    /** Validates non-empty, case-insensitively unique package names. */
    public RecvInfoHeaderValue {
        Objects.requireNonNull(packages, "packages");
        if (packages.isEmpty()) {
            throw new IllegalArgumentException("Recv-Info must advertise at least one package");
        }
        List<InfoPackageHeaderValue> copy = new ArrayList<>(packages.size());
        Set<String> names = new LinkedHashSet<>();
        for (InfoPackageHeaderValue infoPackage : packages) {
            InfoPackageHeaderValue selected = Objects.requireNonNull(infoPackage, "INFO package");
            if (!names.add(selected.normalizedName())) {
                throw new IllegalArgumentException("duplicate Recv-Info package: " + selected.name());
            }
            copy.add(selected);
        }
        packages = List.copyOf(copy);
    }

    /**
     * Renders this value for a Recv-Info header.
     *
     * @return comma-separated package tokens in their declared order
     */
    public String wireValue() {
        return packages.stream().map(InfoPackageHeaderValue::wireValue).collect(java.util.stream.Collectors.joining(", "));
    }
}
