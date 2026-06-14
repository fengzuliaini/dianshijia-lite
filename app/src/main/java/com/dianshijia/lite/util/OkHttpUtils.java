package com.dianshijia.lite.util;

import android.os.Build;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import okhttp3.ConnectionSpec;
import okhttp3.OkHttpClient;
import okhttp3.TlsVersion;

public class OkHttpUtils {
    private static final String TAG = "OkHttpUtils";
    private static OkHttpClient clientInstance;

    public static synchronized OkHttpClient getOkHttpClient() {
        if (clientInstance == null) {
            OkHttpClient.Builder builder = new OkHttpClient.Builder();
            
            // 如果是 Android 4.1 (API 16) 到 Android 5.0 (API 21) 之间的旧版本设备，强制启用 TLS 1.2 支持
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN && Build.VERSION.SDK_INT <= 21) {
                try {
                    SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
                    
                    TrustManager[] trustManagers = new TrustManager[]{
                        new X509TrustManager() {
                            @Override
                            public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                            @Override
                            public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                            @Override
                            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                                return new java.security.cert.X509Certificate[]{};
                            }
                        }
                    };
                    
                    sslContext.init(null, trustManagers, new java.security.SecureRandom());
                    
                    // 使用自定义的 SSLSocketFactory 强制协议版本
                    builder.sslSocketFactory(new TLSSocketFactory(sslContext.getSocketFactory()), (X509TrustManager) trustManagers[0]);

                    ConnectionSpec cs = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                            .tlsVersions(TlsVersion.TLS_1_2)
                            .build();
                    List<ConnectionSpec> specs = new ArrayList<>();
                    specs.add(cs);
                    specs.add(ConnectionSpec.COMPATIBLE_TLS);
                    specs.add(ConnectionSpec.CLEARTEXT);
                    builder.connectionSpecs(specs);
                    
                    Log.i(TAG, "Successfully configured TLSv1.2 for OkHttpClient on legacy device");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to enable TLSv1.2 for OkHttpClient", e);
                }
            }
            
            clientInstance = builder.build();
        }
        return clientInstance;
    }

    // 强制 TLS 1.2 的 SSLSocketFactory 包装类
    private static class TLSSocketFactory extends javax.net.ssl.SSLSocketFactory {
        private final javax.net.ssl.SSLSocketFactory delegate;

        public TLSSocketFactory(javax.net.ssl.SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public java.net.Socket createSocket(java.net.Socket s, String host, int port, boolean autoClose) throws java.io.IOException {
            return enableTlsOnSocket(delegate.createSocket(s, host, port, autoClose));
        }

        @Override
        public java.net.Socket createSocket(String host, int port) throws java.io.IOException {
            return enableTlsOnSocket(delegate.createSocket(host, port));
        }

        @Override
        public java.net.Socket createSocket(String host, int port, java.net.InetAddress localHost, int localPort) throws java.io.IOException {
            return enableTlsOnSocket(delegate.createSocket(host, port, localHost, localPort));
        }

        @Override
        public java.net.Socket createSocket(java.net.InetAddress host, int port) throws java.io.IOException {
            return enableTlsOnSocket(delegate.createSocket(host, port));
        }

        @Override
        public java.net.Socket createSocket(java.net.InetAddress address, int port, java.net.InetAddress localAddress, int localPort) throws java.io.IOException {
            return enableTlsOnSocket(delegate.createSocket(address, port, localAddress, localPort));
        }

        private java.net.Socket enableTlsOnSocket(java.net.Socket socket) {
            if (socket instanceof javax.net.ssl.SSLSocket) {
                ((javax.net.ssl.SSLSocket) socket).setEnabledProtocols(new String[]{"TLSv1.2"});
            }
            return socket;
        }
    }
}
