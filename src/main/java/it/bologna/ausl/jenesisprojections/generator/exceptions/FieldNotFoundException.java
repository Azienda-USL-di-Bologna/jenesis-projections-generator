package it.bologna.ausl.jenesisprojections.generator.exceptions;

/**
 *
 * @author gdm
 */
public class FieldNotFoundException  extends Exception {
 
    public FieldNotFoundException(String message) {
        super(message);
    }

    public FieldNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }

    public FieldNotFoundException(Throwable cause) {
        super(cause);
    }
}
