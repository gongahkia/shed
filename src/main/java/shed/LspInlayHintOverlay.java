package shed;

record LspInlayHintOverlay(int offset, String label) {
    LspInlayHintOverlay {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be non-negative");
        }
        label = label == null ? "" : label;
    }
}
