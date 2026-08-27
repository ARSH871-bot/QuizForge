/**
 * Making a mutating request safe to retry.
 *
 * <p>Published so the security configuration can place the filter in the chain.
 * It has to run inside that chain, after authentication, because the tenant it
 * scopes records to is established there.
 */
@org.springframework.modulith.NamedInterface("idempotency")
package com.quizforge.platform.idempotency;
