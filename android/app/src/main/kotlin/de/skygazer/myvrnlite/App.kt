package de.skygazer.myvrnlite

import android.app.Application
import android.content.Context
import de.skygazer.myvrnlite.data.Fahrplan
import de.skygazer.myvrnlite.data.Repo

class App : Application() {
    val repo by lazy { Repo(this) }
    val fahrplan by lazy { Fahrplan(this, repo.efa) }
}

val Context.repo: Repo get() = (applicationContext as App).repo
val Context.fahrplan: Fahrplan get() = (applicationContext as App).fahrplan
