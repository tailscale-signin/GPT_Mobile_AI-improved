package dev.melo.gguf;

/** A caught native failure; the original runtime text is retained separately from its category. */
public final class GgufNativeException extends IllegalStateException {
    public final String category;
    public GgufNativeException(String category, String message) {
        super(message);
        this.category = category;
    }
}
