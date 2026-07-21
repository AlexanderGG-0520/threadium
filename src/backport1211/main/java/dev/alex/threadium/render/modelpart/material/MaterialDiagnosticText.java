package dev.alex.threadium.render.modelpart.material;

import java.util.Objects;
import java.util.regex.Pattern;

/** Bounded text formatting used only by M3B diagnostics. */
public final class MaterialDiagnosticText {
    private static final Pattern HIDDEN_LAMBDA_ADDRESS = Pattern.compile("\\$\\$Lambda/0x[0-9a-fA-F]+");
    private static final Pattern IDENTITY_HASH =
            Pattern.compile("(?<=[A-Za-z0-9_$.)\\]}>])@[0-9a-fA-F]+(?=$|[^0-9A-Za-z])");

    private MaterialDiagnosticText() {}

    public static String className(Object value, int maximumLength) {
        Objects.requireNonNull(value, "value");
        return sanitize(value.getClass().getName(), maximumLength);
    }

    public static String describe(Object value, int maximumLength) {
        Objects.requireNonNull(value, "value");
        try {
            return sanitize(String.valueOf(value), maximumLength);
        } catch (RuntimeException exception) {
            return MaterialPathDiagnosticData.FORMATTING_FAILED;
        }
    }

    public static String sanitize(String text, int maximumLength) {
        Objects.requireNonNull(text, "text");
        if (maximumLength <= 0) throw new IllegalArgumentException("maximumLength must be positive");
        String normalized = IDENTITY_HASH
                .matcher(HIDDEN_LAMBDA_ADDRESS.matcher(text).replaceAll("\\$\\$Lambda"))
                .replaceAll("@<identity>");
        int retainedLength = Math.min(normalized.length(), maximumLength);
        StringBuilder sanitized = new StringBuilder(retainedLength);
        for (int index = 0; index < retainedLength; index++) {
            char character = normalized.charAt(index);
            sanitized.append(Character.isISOControl(character) ? ' ' : character);
        }
        return sanitized.toString();
    }
}
