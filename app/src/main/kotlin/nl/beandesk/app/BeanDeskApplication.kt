package nl.beandesk.app

import android.app.Application
import org.maplibre.android.MapLibre

class BeanDeskApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // MapLibre must be initialised before the first MapView is created.
        MapLibre.getInstance(this)
        container = AppContainer(this)
    }
}
