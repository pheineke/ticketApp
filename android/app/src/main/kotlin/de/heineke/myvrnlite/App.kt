package de.heineke.myvrnlite

import android.app.Application
import android.content.Context
import de.heineke.myvrnlite.data.Repo

class App : Application() {
    val repo by lazy { Repo(this) }
}

val Context.repo: Repo get() = (applicationContext as App).repo
