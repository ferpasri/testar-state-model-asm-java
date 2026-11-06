package org.testar.asm;

/** A rejected operation, identified by its language-independent contract code. */
public final class AsmException extends IllegalArgumentException {
    private final String code;

    public AsmException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
