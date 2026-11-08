package org.fuin.porelo.mcp;

/**
 * A start refused before anything is bound: wrong arguments, a refused token, or a socket place
 * refused.
 */
public class StartRefused extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Says why.
     *
     * @param message Why, never holding the token.
     */
    public StartRefused(String message) {
        super(message);
    }
}
