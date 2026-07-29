package dev.rstrickland.chat.net;

import androidx.annotation.NonNull;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

import okhttp3.Dns;

/**
 * DEV ONLY. The Media service presigns MinIO URLs against its PUBLIC endpoint,
 * which is {@code http://localhost:9000} (so the browser can reach it). On the
 * Android emulator, {@code localhost} is the emulator itself, not the host — so
 * those URLs are unreachable as-is.
 *
 * We can't just rewrite the URL host to {@code 10.0.2.2}: SigV4 signs the Host
 * header, so a different host fails with SignatureDoesNotMatch. Instead we keep
 * the URL (and thus the {@code Host: localhost:9000} header, which the signature
 * expects) and only redirect the CONNECTION here — resolving {@code localhost}
 * to the host-machine alias {@code 10.0.2.2}. MinIO validates against the Host
 * header, so the signature still matches.
 *
 * Remove for a real deployment (media will be HTTPS on its own domain).
 */
public final class DevMediaDns implements Dns {

    @NonNull
    @Override
    public List<InetAddress> lookup(@NonNull String hostname) throws UnknownHostException {
        if ("localhost".equalsIgnoreCase(hostname) || "127.0.0.1".equals(hostname)) {
            return Arrays.asList(InetAddress.getAllByName(ApiConfig.HOST)); // 10.0.2.2
        }
        return Dns.SYSTEM.lookup(hostname);
    }
}
