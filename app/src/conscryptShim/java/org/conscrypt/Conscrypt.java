// SPDX-License-Identifier: GPL-3.0-only
package org.conscrypt;

import android.net.ssl.SSLSockets;
import android.os.Build;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;

/**
 * Not Conscrypt: the one call Kadb's pairing makes on it once {@link OpenSSLProvider} is found.
 * Kadb would otherwise reach the platform's hidden Conscrypt through a hidden-API bypass, which
 * Play flags; the public {@link SSLSockets} has the same export from Android 12.
 */
public final class Conscrypt {
    private Conscrypt() {}

    public static byte[] exportKeyingMaterial(SSLSocket socket, String label, byte[] context, int length)
            throws SSLException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            throw new SSLException("pairing a TV needs Android 12 or newer on this phone");
        }
        return SSLSockets.exportKeyingMaterial(socket, label, context, length);
    }
}
