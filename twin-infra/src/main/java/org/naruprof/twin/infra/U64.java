package org.naruprof.twin.infra;

/**
 * Unsigned 64-bit value (gRPC uint64). Stored in a signed long, compared with
 * {@link Long#compareUnsigned} and rendered as an unsigned decimal string, which is the
 * lossless interchange form used in facade JSON and Ditto properties.
 */
public record U64(long bits) implements Comparable<U64> {

    public static final U64 ZERO = new U64(0L);

    public static U64 of(long nonNegative) {
        if (nonNegative < 0) {
            throw new IllegalArgumentException("negative value for uint64 factory; use fromBits");
        }
        return new U64(nonNegative);
    }

    public static U64 fromBits(long bits) {
        return new U64(bits);
    }

    /**
     * Parses a canonical unsigned decimal: digits only, no sign, no leading zeros (except "0"),
     * at most 2^64-1.
     */
    public static U64 parse(String text) {
        if (text == null || text.isEmpty() || text.length() > 20) {
            throw new IllegalArgumentException("not a canonical uint64 decimal");
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("not a canonical uint64 decimal");
            }
        }
        if (text.length() > 1 && text.charAt(0) == '0') {
            throw new IllegalArgumentException("not a canonical uint64 decimal");
        }
        try {
            return new U64(Long.parseUnsignedLong(text));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("uint64 out of range");
        }
    }

    public static boolean isCanonical(String text) {
        try {
            parse(text);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public int compareTo(U64 o) {
        return Long.compareUnsigned(bits, o.bits);
    }

    public boolean isGreaterThan(U64 o) {
        return compareTo(o) > 0;
    }

    public boolean isLessThan(U64 o) {
        return compareTo(o) < 0;
    }

    /** this + 1, or throws on overflow of 2^64-1. */
    public U64 next() {
        if (bits == -1L) {
            throw new ArithmeticException("uint64 overflow");
        }
        return new U64(bits + 1);
    }

    @Override
    public String toString() {
        return Long.toUnsignedString(bits);
    }
}
