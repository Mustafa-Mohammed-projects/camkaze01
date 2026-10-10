package org.kaze.camkaze

import android.app.Application
import org.kaze.camkaze.crypto.Vault
import org.kaze.camkaze.data.SecureRepo
import org.kaze.camkaze.data.ShareCache
import java.io.File

object Services {
    lateinit var vault: Vault
    lateinit var secure: SecureRepo
}

class KazeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Services.vault = Vault(File(filesDir, "vault.cfg"))
        Services.secure = SecureRepo(File(filesDir, "secure"), Services.vault)
        ShareCache.purge(this, 0)
    }
}
