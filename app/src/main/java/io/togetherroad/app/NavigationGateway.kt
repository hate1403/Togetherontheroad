package io.togetherroad.app
import io.togetherroad.core.Place
interface NavigationGateway : AutoCloseable {
    val label: String
    fun apply(place: Place, callback: (Boolean, String) -> Unit)
}
