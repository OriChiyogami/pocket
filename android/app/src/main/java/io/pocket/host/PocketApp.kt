package io.pocket.host

import android.app.Application
import android.content.Context
import io.pocket.host.core.PackageInstaller
import io.pocket.host.core.PackageRepository

class PocketApp : Application() {

    lateinit var repository: PackageRepository
        private set

    lateinit var installer: PackageInstaller
        private set

    override fun onCreate() {
        super.onCreate()
        repository = PackageRepository(this)
        installer = PackageInstaller(this, repository)
    }

    companion object {
        fun of(context: Context): PocketApp = context.applicationContext as PocketApp
    }
}
