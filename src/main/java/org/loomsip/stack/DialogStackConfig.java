package org.loomsip.stack;

/** Explicit local identity and routing dependencies required for Stack Dialog support. */
public record DialogStackConfig(org.loomsip.dialog.DialogRequestProfile requestProfile,
                                org.loomsip.dialog.DialogTargetResolver targetResolver,
                                org.loomsip.dialog.DialogConfig dialogConfig,
                                org.loomsip.dialog.DialogLifecycleListener lifecycleListener) {
    /** Validates all Dialog dependencies supplied by the application. */
    public DialogStackConfig {
        java.util.Objects.requireNonNull(requestProfile, "requestProfile");
        java.util.Objects.requireNonNull(targetResolver, "targetResolver");
        java.util.Objects.requireNonNull(dialogConfig, "dialogConfig");
        java.util.Objects.requireNonNull(lifecycleListener, "lifecycleListener");
        if (requestProfile.sentBy().port() == 0) throw new IllegalArgumentException("Dialog sent-by port must not be zero");
    }
}
