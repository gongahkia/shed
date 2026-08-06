package shed;

import java.util.Objects;

/** One-use capability created only after the Language Services panel gets a user confirmation. */
final class ManagedLanguageInstallApproval {
    private final ManagedLanguageSupportTrust.ArtifactCoordinate coordinate;
    private boolean consumed;

    private ManagedLanguageInstallApproval(ManagedLanguageSupportTrust.ArtifactCoordinate coordinate) {
        this.coordinate = Objects.requireNonNull(coordinate, "coordinate");
    }

    static ManagedLanguageInstallApproval approvedInLanguageServicesPanel(ManagedLanguageCatalog.Entry entry) {
        if (entry == null) throw new IllegalArgumentException("language service is required");
        return new ManagedLanguageInstallApproval(entry.installMetadata().coordinate());
    }

    synchronized boolean consumeFor(ManagedLanguageCatalog.Entry entry) {
        if (consumed || entry == null || !coordinate.equals(entry.installMetadata().coordinate())) return false;
        consumed = true;
        return true;
    }
}
