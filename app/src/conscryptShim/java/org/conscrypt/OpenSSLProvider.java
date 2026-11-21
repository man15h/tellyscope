// SPDX-License-Identifier: GPL-3.0-only
package org.conscrypt;

import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.SecureRandom;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLContextSpi;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

/**
 * Not Conscrypt: the name Kadb looks up to skip its hidden-API route to the platform's Conscrypt
 * (see {@link Conscrypt}). Its one service is the platform's own TLSv1.3, so connections and
 * pairing run on the same TLS stack as without it.
 */
public final class OpenSSLProvider extends Provider {
    @SuppressWarnings("deprecation") // The (String, String, String) constructor is newer than minSdk.
    public OpenSSLProvider() {
        super("TellyscopePlatformTls", 1.0, "The platform's TLSv1.3, under the name Kadb looks for");
        putService(new Service(this, "SSLContext", "TLSv1.3", PlatformTls.class.getName(), null, null) {
            @Override
            public Object newInstance(Object constructorParameter) throws NoSuchAlgorithmException {
                return new PlatformTls();
            }
        });
    }

    private static final class PlatformTls extends SSLContextSpi {
        private final SSLContext platform;

        PlatformTls() throws NoSuchAlgorithmException {
            platform = SSLContext.getInstance("TLSv1.3");
        }

        @Override
        protected void engineInit(KeyManager[] km, TrustManager[] tm, SecureRandom random) throws KeyManagementException {
            platform.init(km, tm, random);
        }

        @Override
        protected SSLSocketFactory engineGetSocketFactory() {
            return platform.getSocketFactory();
        }

        @Override
        protected SSLServerSocketFactory engineGetServerSocketFactory() {
            return platform.getServerSocketFactory();
        }

        @Override
        protected SSLEngine engineCreateSSLEngine() {
            return platform.createSSLEngine();
        }

        @Override
        protected SSLEngine engineCreateSSLEngine(String host, int port) {
            return platform.createSSLEngine(host, port);
        }

        @Override
        protected SSLSessionContext engineGetServerSessionContext() {
            return platform.getServerSessionContext();
        }

        @Override
        protected SSLSessionContext engineGetClientSessionContext() {
            return platform.getClientSessionContext();
        }
    }
}
