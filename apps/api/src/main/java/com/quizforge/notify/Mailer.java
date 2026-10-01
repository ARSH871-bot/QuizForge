package com.quizforge.notify;

/**
 * Sends an email, in the background.
 *
 * <p>Asynchronous on purpose, not only for speed: a request that sends mail for
 * a known address and none for an unknown one would otherwise take measurably
 * longer when the address exists, which tells an attacker which addresses
 * have accounts.
 */
public interface Mailer {

    record OutgoingMail(String to, String subject, String text) {
        /** The body is excluded, because it usually carries a single-use link. */
        @Override
        public String toString() {
            return "OutgoingMail[to=" + to + ", subject=" + subject + "]";
        }
    }

    void send(OutgoingMail mail);
}
