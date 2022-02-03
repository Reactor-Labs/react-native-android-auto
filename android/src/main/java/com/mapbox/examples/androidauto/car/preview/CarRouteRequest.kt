package com.mapbox.examples.androidauto.car.preview

import android.util.Log
import com.mapbox.androidauto.logAndroidAuto
import com.mapbox.androidauto.logAndroidAutoFailure
import com.mapbox.api.directions.v5.DirectionsCriteria
import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.examples.androidauto.car.search.PlaceRecord
import com.mapbox.geojson.Point
import com.mapbox.navigation.base.extensions.applyDefaultNavigationOptions
import com.mapbox.navigation.base.formatter.UnitType
import com.mapbox.navigation.base.route.RouterCallback
import com.mapbox.navigation.base.route.RouterFailure
import com.mapbox.navigation.base.route.RouterOrigin
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.ui.maps.location.NavigationLocationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first

/**
 * This is a view interface. Each callback function represents a view that will be
 * shown for the situations.
 */
interface CarRouteRequestCallback {
    fun onRoutesReady(placeRecord: PlaceRecord, routes: List<DirectionsRoute>)
    fun onUnknownCurrentLocation()
    fun onDestinationLocationUnknown()
    fun onNoRoutesFound()
}

/**
 * Service class that requests routes for the preview screen.
 */
class CarRouteRequest(
    val mapboxNavigation: MapboxNavigation,
    val navigationLocationProvider: NavigationLocationProvider,
) {
    internal var currentRequestId: Long? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun requestSync(placeRecord: PlaceRecord): List<DirectionsRoute>? {
        val routesFlow: Flow<List<DirectionsRoute>?> = callbackFlow {
            request(
                placeRecord,
                object : CarRouteRequestCallback {
                    override fun onRoutesReady(
                        placeRecord: PlaceRecord,
                        routes: List<DirectionsRoute>
                    ) {
                        Log.d("ReactAUTO", "trySend: $routes");
                        trySend(routes)
                        close()
                    }

                    override fun onUnknownCurrentLocation() {
                        Log.d("ReactAUTO", "onUnknownCurrentLocation");
                        trySend(null)
                        close()
                    }

                    override fun onDestinationLocationUnknown() {
                        Log.d("ReactAUTO", "onDestinationLocationUnknown");
                        trySend(null)
                        close()
                    }

                    override fun onNoRoutesFound() {
                        Log.d("ReactAUTO", "onNoRoutesFound");
                        trySend(null)
                        close()
                    }
                }
            )
            awaitClose { cancelRequest() }
        }
        return routesFlow.first()
    }

    /**
     * When a search result was selected, request a route.
     *
     * @param searchResults potential destinations for directions
     */
    fun request(placeRecord: PlaceRecord, callback: CarRouteRequestCallback) {
        Log.d("ReactAUTO", "Before route request");
        currentRequestId?.let { mapboxNavigation.cancelRouteRequest(it) }

        val location = navigationLocationProvider.lastLocation

        if (location == null) {
            logAndroidAutoFailure("CarRouteRequest.onUnknownCurrentLocation")
            callback.onUnknownCurrentLocation()
            return
        }
        val origin = Point.fromLngLat(location.longitude, location.latitude)

        when (placeRecord.coordinate) {
            null -> {
                logAndroidAutoFailure("CarRouteRequest.onSearchResultLocationUnknown")
                callback.onDestinationLocationUnknown()
            }
            else -> {
                currentRequestId = mapboxNavigation.requestRoutes(
                    carRouteOptions(origin, placeRecord.coordinate),
                    carCallbackTransformer(placeRecord, callback)
                )
            }
        }
    }

    fun cancelRequest() {
        currentRequestId?.let { mapboxNavigation.cancelRouteRequest(it) }
    }

    /**
     * Default [RouteOptions] for the car.
     */
    private fun carRouteOptions(origin: Point, destination: Point) = RouteOptions.builder()
        .applyDefaultNavigationOptions()
        .language(mapboxNavigation.navigationOptions.distanceFormatterOptions.locale.language)
        .voiceUnits(
            when (mapboxNavigation.navigationOptions.distanceFormatterOptions.unitType) {
                UnitType.IMPERIAL -> DirectionsCriteria.IMPERIAL
                UnitType.METRIC -> DirectionsCriteria.METRIC
            },
        )
        .alternatives(true)
        .profile(DirectionsCriteria.PROFILE_DRIVING_TRAFFIC)
        .coordinatesList(listOf(origin, destination))
        .layersList(listOf(mapboxNavigation.getZLevel(), null))
        .metadata(true)
        .build()

    /**
     * This creates a callback that transforms
     * [RouterCallback] into [CarRouteRequestCallback]
     */
    private fun carCallbackTransformer(
        searchResult: PlaceRecord,
        callback: CarRouteRequestCallback
    ): RouterCallback {
        Log.d("ReactAUTO", "carCallbackTransformer: $searchResult");
        return object : RouterCallback {
            override fun onRoutesReady(routes: List<DirectionsRoute>, routerOrigin: RouterOrigin) {
                currentRequestId = null

                logAndroidAuto("onRoutesReady ${routes.size}")
                mapboxNavigation.setRoutes(routes)
                callback.onRoutesReady(searchResult, routes)
            }

            override fun onCanceled(routeOptions: RouteOptions, routerOrigin: RouterOrigin) {
                currentRequestId = null

                logAndroidAutoFailure("onCanceled $routeOptions")
                callback.onNoRoutesFound()
            }

            override fun onFailure(reasons: List<RouterFailure>, routeOptions: RouteOptions) {
                currentRequestId = null

                logAndroidAutoFailure("onRoutesRequestFailure $routeOptions $reasons")
                callback.onNoRoutesFound()
            }
        }
    }
}
