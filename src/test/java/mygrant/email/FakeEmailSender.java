package mygrant.email;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/** Stands in for Resend: records each request and fails the ones it is told to. */
class FakeEmailSender implements EmailSender {

    final List<EmailMessage> requests = new ArrayList<>();
    final List<EmailMessage> delivered = new ArrayList<>();
    private final Deque<RuntimeException> failures = new ArrayDeque<>();
    private Consumer<EmailMessage> duringSend = message -> { };
    boolean configured = true;

    FakeEmailSender failNext(RuntimeException failure) {
        failures.add(failure);
        return this;
    }

    /** Runs inside send, before it returns; lets a test act while a request is in flight. */
    FakeEmailSender duringSend(Consumer<EmailMessage> action) {
        this.duringSend = action;
        return this;
    }

    synchronized void reset() {
        requests.clear();
        delivered.clear();
        failures.clear();
        duringSend = message -> { };
        configured = true;
    }

    @Override
    public boolean isConfigured() {
        return configured;
    }

    @Override
    public synchronized String send(EmailMessage message) {
        requests.add(message);
        duringSend.accept(message);
        RuntimeException failure = failures.poll();
        if (failure != null) {
            throw failure;
        }
        delivered.add(message);
        return "resend-" + delivered.size();
    }

    static EmailSendException retryable(String reason) {
        return new EmailSendException(true, null, reason);
    }

    static EmailSendException rejected(int status, String errorName) {
        return new EmailSendException(false, errorName, "HTTP " + status + " " + errorName);
    }
}
