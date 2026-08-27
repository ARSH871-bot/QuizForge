package com.quizforge.platform.idempotency;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * A request whose body can be read more than once.
 *
 * <p>Needed because the idempotency filter has to hash the body <em>and</em>
 * the handler still has to parse it. A servlet request body is a stream and a
 * stream is consumed once, so reading it to hash it would otherwise leave the
 * handler with nothing — which surfaces as
 * {@code Required request body is missing}, a confusing way to be told that
 * something upstream drank the request.
 *
 * <p>Spring's {@code ContentCachingRequestWrapper} does not solve this: it
 * records what was read so it can be inspected <em>afterwards</em>, but it does
 * not hand the bytes back to the next reader.
 */
final class CachedBodyRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    private CachedBodyRequest(HttpServletRequest request, byte[] body) {
        super(request);
        this.body = body;
    }

    /** Drains the original request and buffers it. */
    static CachedBodyRequest of(HttpServletRequest request) throws IOException {
        return new CachedBodyRequest(request, request.getInputStream().readAllBytes());
    }

    /** The buffered body, for hashing. */
    byte[] body() {
        return body.clone();
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream buffered = new ByteArrayInputStream(body);

        return new ServletInputStream() {
            @Override
            public int read() {
                return buffered.read();
            }

            @Override
            public boolean isFinished() {
                return buffered.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                // Blocking reads only; this wrapper is not used asynchronously.
                throw new UnsupportedOperationException("async reads are not supported here");
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        Charset charset = getCharacterEncoding() == null
                ? StandardCharsets.UTF_8
                : Charset.forName(getCharacterEncoding());
        return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }
}
