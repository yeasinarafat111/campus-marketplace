package com.campus.marketplace.security;

import jakarta.servlet.http.HttpServletRequest;

/** Works out the caller's address behind Cloud Run's proxy, without trusting what the client puts in the header. */
public final class ClientIp {

    private ClientIp() {
    }

    /**
     * {@code X-Forwarded-For} is a list; every proxy appends the address it saw. A client can prepend entries of its
     * own, so only the right-hand end is trustworthy: with {@code trustedProxies} proxies in front of the app, the
     * client is that many entries from the right. Without the header (or with fewer entries) the connection's own
     * address is used.
     */
    public static String of(HttpServletRequest request, int trustedProxies) {
        String header = request.getHeader("X-Forwarded-For");
        if (header != null && !header.isBlank() && trustedProxies > 0) {
            String[] parts = header.split(",");
            int index = parts.length - trustedProxies;
            if (index >= 0) {
                String candidate = parts[index].trim();
                if (!candidate.isEmpty()) {
                    return candidate;
                }
            }
        }
        return request.getRemoteAddr();
    }
}
