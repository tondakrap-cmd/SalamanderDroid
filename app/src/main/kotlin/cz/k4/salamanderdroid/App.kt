package cz.k4.salamanderdroid

import android.app.Application
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Android ships a cut-down provider under the name "BC"; SSH needs the full one
        // (X25519, Ed25519, …), so the bundled BouncyCastle takes its place.
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.addProvider(BouncyCastleProvider())
    }
}
