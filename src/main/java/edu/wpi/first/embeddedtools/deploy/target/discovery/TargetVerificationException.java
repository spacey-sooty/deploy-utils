package edu.wpi.first.embeddedtools.deploy.target.discovery;

public class TargetVerificationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public TargetVerificationException() {
        super();
    }

    public TargetVerificationException(String msg) {
        super(msg);
    }
}
