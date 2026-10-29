package co.ticketing.messaging;

/** A message that cannot be decoded (poison message). It is never acknowledged, so it ends in the DLQ. */
public class InvalidCommandMessageException extends RuntimeException {

    public InvalidCommandMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
