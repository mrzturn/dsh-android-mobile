package com.dsh.mobile

import android.app.Application
import android.content.Context
import com.dsh.mobile.data.ConnectionStore
import com.dsh.mobile.data.PairingClient

/** Application entry: process-wide singletons live in [AppGraph]. */
class DshApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

/** Tiny hand-rolled DI container. */
class AppGraph(app: DshApp) {
    val connectionStore: ConnectionStore = ConnectionStore(app)
    val pairingClient: PairingClient = PairingClient()
}

/** Global app instance accessor for non-compose call sites. */
val Context.appGraph: AppGraph
    get() = (applicationContext as DshApp).graph
