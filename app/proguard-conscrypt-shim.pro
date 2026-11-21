# R8 rules for the builds in conscryptShimFlavors (build.gradle.kts).

# Kadb loads the shim by name: the provider's constructor, then Conscrypt.exportKeyingMaterial.
-keep class org.conscrypt.OpenSSLProvider { <init>(); }
-keep class org.conscrypt.Conscrypt {
    public static byte[] exportKeyingMaterial(javax.net.ssl.SSLSocket, java.lang.String, byte[], int);
}

# Kadb's own route to Conscrypt still names the bypass, which these builds leave out. checkdiscard
# fails the build if it ever comes back in, since Play flags it by its classes.
-dontwarn org.lsposed.hiddenapibypass.**
-checkdiscard class org.lsposed.hiddenapibypass.**
