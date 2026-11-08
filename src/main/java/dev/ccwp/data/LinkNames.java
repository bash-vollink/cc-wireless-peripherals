package dev.ccwp.data;

import javax.annotation.Nullable;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Naming rules for wireless links.
 * <p>
 * Automatic names are {@code <type>_w<n>}, numbered by our own per-type counter in {@link LinkSavedData}. CC's wired
 * modems use {@code <type>_<n>} from CC's shared {@code ids.json} counter, so the {@code w} keeps the two apart: a
 * wireless name can never match a wired one, and we never touch CC's counter. Links made by older versions keep their
 * wired-style names.
 * <p>
 * Custom names must not end in {@code _<digits>} or {@code _w<digits>}, so they can't clash with wired names or with
 * automatic names, and must not be a computer side.
 */
public final class LinkNames {
    public static final int MAX_LENGTH = 50; // the anvil's limit
    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9_:./-]+");
    private static final Pattern RESERVED_SUFFIX = Pattern.compile(".*_w?[0-9]+");
    private static final Set<String> SIDES = Set.of("top", "bottom", "left", "right", "front", "back");

    private LinkNames() {
    }

    public static String auto(String type, int id) {
        return type + "_w" + id;
    }

    /** The number in an automatic name for {@code type}, or -1 if {@code name} isn't one. */
    public static int parseAuto(String type, String name) {
        var prefix = type + "_w";
        if (!name.startsWith(prefix)) return -1;
        var digits = name.substring(prefix.length());
        if (digits.isEmpty() || digits.length() > 9) return -1;
        for (int i = 0; i < digits.length(); i++) if (!Character.isDigit(digits.charAt(i))) return -1;
        if (digits.length() > 1 && digits.charAt(0) == '0') return -1;
        return Integer.parseInt(digits);
    }

    /**
     * Check a custom name's format (not whether it is taken).
     *
     * @return A translation key describing the problem, or {@code null} if the name is acceptable.
     */
    public static @Nullable String validateCustom(String name) {
        if (name.isEmpty() || name.length() > MAX_LENGTH) return "message.ccwirelessperipherals.name_length";
        if (!ALLOWED.matcher(name).matches()) return "message.ccwirelessperipherals.name_chars";
        if (SIDES.contains(name)) return "message.ccwirelessperipherals.name_side";
        if (RESERVED_SUFFIX.matcher(name).matches()) return "message.ccwirelessperipherals.name_suffix";
        return null;
    }
}
