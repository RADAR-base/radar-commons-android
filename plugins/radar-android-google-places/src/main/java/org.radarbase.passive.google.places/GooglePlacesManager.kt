/*
 * Copyright 2017 The Hyve
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.radarbase.passive.google.places

import android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.common.api.ApiException
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.model.PlaceLikelihood
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FetchPlaceResponse
import com.google.android.libraries.places.api.net.FindCurrentPlaceRequest
import com.google.android.libraries.places.api.net.FindCurrentPlaceResponse
import com.google.android.libraries.places.api.net.PlacesClient
import org.radarbase.android.data.DataCache
import org.radarbase.android.source.AbstractSourceManager
import org.radarbase.android.source.SourceStatusListener
import org.radarbase.android.util.BroadcastRegistration
import org.radarbase.android.util.OfflineProcessor
import org.radarbase.android.util.SafeHandler
import org.radarbase.android.util.register
import org.radarbase.passive.google.places.GooglePlacesService.Companion.GOOGLE_PLACES_API_KEY_DEFAULT
import org.radarcns.kafka.ObservationKey
import org.radarcns.passive.google.GooglePlacesInfo
import org.radarcns.passive.google.PlacesType
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import kotlin.math.pow

class GooglePlacesManager(service: GooglePlacesService, @get: Synchronized private var apiKey: String, private val placeHandler: SafeHandler) : AbstractSourceManager<GooglePlacesService, GooglePlacesState>(service) {
    private val placesInfoTopic: DataCache<ObservationKey, GooglePlacesInfo> = createCache("android_google_places_info", GooglePlacesInfo())

    private val placesProcessor: OfflineProcessor
    // Delay in seconds for exponential backoff
    private val maxDelay: Long = 43200
    private val baseDelay: Long = 300
    @get: Synchronized
    @set: Synchronized
    private var numOfAttempts: Int = 0
    private val preferences: SharedPreferences = service.getSharedPreferences(GooglePlacesManager::class.java.name, Context.MODE_PRIVATE)

    private val isPermissionGranted: Boolean
        get() = checkLocationPermissions()
    private val placesClientCreated: Boolean
        get() = state.placesClientCreated.get()
    private val fromBroadcastRegistration: Boolean
        get()= state.fromBroadcast.get()
    var shouldFetchPlaceId: Boolean
        get() = state.shouldFetchPlaceId.get()
        set(value) = state.shouldFetchPlaceId.set(value)
    var shouldFetchAdditionalInfo: Boolean
        get() = state.shouldFetchAdditionalInfo.get()
        set(value) = state.shouldFetchAdditionalInfo.set(value)
    var limitByPlacesCount: Int
        get() = state.limitByPlacesCount
        set(value) { state.limitByPlacesCount = value }
    var limitByPlacesLikelihood: Double
        get() = state.limitByPlacesLikelihood
        set(value) { state.limitByPlacesLikelihood = value }
    private val currentPlaceFields: List<Place.Field>
        get() = if (shouldFetchPlaceId||shouldFetchAdditionalInfo) listOf(Place.Field.TYPES, Place.Field.ID) else listOf(Place.Field.TYPES)
    private val detailsPlaceFields: List<Place.Field> =  listOf(Place.Field.ADDRESS_COMPONENTS)

    private var placesBroadcastReceiver: BroadcastRegistration? = null
    private var placesClient: PlacesClient? = null
        @Synchronized get() = if (placesClientCreated) field else null

    private lateinit var currentPlaceRequest:FindCurrentPlaceRequest
    private lateinit var detailsPlaceRequest:FetchPlaceRequest

    init {
        name = service.getString(R.string.googlePlacesDisplayName)
        placesProcessor = OfflineProcessor(service) {
            process = listOf(this@GooglePlacesManager::processPlacesData)
            requestCode = GOOGLE_PLACES_REQUEST_CODE
            requestName = GOOGLE_PLACES_REQUEST_NAME
            wake = false
        }
    }

    override fun start(acceptableIds: Set<String>) {
        register()
        status = SourceStatusListener.Status.READY
        placesProcessor.start()
        placeHandler.execute { if (!placesClientCreated) createPlacesClient() }
        placesBroadcastReceiver = LocalBroadcastManager.getInstance(service).register(DEVICE_LOCATION_CHANGED) { _, _ ->
            if (placesClientCreated) {
                placeHandler.execute {
                    state.fromBroadcast.compareAndSet(false, true)
                    processPlacesData()
                    state.fromBroadcast.compareAndSet(true, false)
                }
            }
        }
    }

    @Synchronized
    private fun createPlacesClient() {
        try {
            placesClient = Places.createClient(service)
            state.placesClientCreated.compareAndSet(false, true)
            status = SourceStatusListener.Status.CONNECTED
        } catch (ex: IllegalStateException) {
            logger.error("Places client has not been initialized yet.")
        }
    }


    fun updateApiKey(apiKey: String) {
        when (apiKey) {
            GOOGLE_PLACES_API_KEY_DEFAULT -> {
                logger.error("API-key is empty, disconnecting now")
                disconnect()
            }
            else -> {
                synchronized(this)  {
                this@GooglePlacesManager.apiKey = apiKey
                }
                if (!Places.isInitialized()) {
                    initializePlacesClient()
                    if (!placesClientCreated) createPlacesClient()
                }
            }
        }
    }

    private fun initializePlacesClient() {
        try {
            Places.initialize(service.applicationContext, apiKey)
        } catch (ex: Exception) {
            logger.error("Exception while initializing places API", ex)
            disconnect()
        }
    }

    @Synchronized
    @SuppressLint("MissingPermission")
    private fun processPlacesData() {
        placeHandler.execute {
            if (isPermissionGranted) {
                currentPlaceRequest = FindCurrentPlaceRequest.newInstance(currentPlaceFields)
                placesClient?.let { client ->
                   // resetting the backoff time in SharedPreferences once the plugin works successfully
                    if (numOfAttempts > 0) {
                        preferences.edit()
                            .putInt(NUMBER_OF_ATTEMPTS_KEY, 0).apply()
                    }
                    client.findCurrentPlace(currentPlaceRequest)
                        .addOnSuccessListener { response: FindCurrentPlaceResponse ->
                            val limitLikelihood = limitByPlacesLikelihood
                            val countLikelihood = limitByPlacesCount
                            when {
                                countLikelihood == -1 && limitLikelihood == -1.0 -> doSend(response.placeLikelihoods)
                                countLikelihood >= 0 && limitLikelihood == -1.0 -> doSend(response.placeLikelihoods.sortedByDescending { it.likelihood }.take(countLikelihood))
                                countLikelihood == -1 && limitLikelihood >= 0.0 -> doSend(response.placeLikelihoods.filter { it.likelihood >= limitLikelihood }.sortedByDescending { it.likelihood })
                                countLikelihood >= 0 && limitLikelihood >= 0.0 -> doSend(response.placeLikelihoods.filter { it.likelihood >= limitLikelihood }.sortedByDescending { it.likelihood }.take(countLikelihood))
                                else -> logger.warn("Appropriate value not provided for limiting the places data")
                            }
                        }
                        .addOnFailureListener { ex ->
                            when (ex.javaClass) {
                                ApiException::class.java -> {
                                    val exception = ex as ApiException
                                    handleApiException(exception, exception.statusCode)
                                }
                                else -> logger.error("Exception while fetching current place", ex)
                            }
                        }
                    }
                } else {
                    logger.warn("Location permissions not granted yet")
                }
            }
        }

    private fun doSend(places: List<PlaceLikelihood>) {
        places.forEach { likelihood ->
            val types = likelihood.place.types?.map { it.toPlacesType() }
            var city: String? = null
            var state: String? = null
            var country: String? = null

            val placeLikelihood: Double = likelihood.likelihood
            val placeId: String? = if (shouldFetchPlaceId) likelihood.place.id else null
            if (shouldFetchAdditionalInfo) {
                val id: String? = likelihood.place.id
                id?.let {
                    detailsPlaceRequest = FetchPlaceRequest.newInstance(it, detailsPlaceFields)
                    placesClient?.let {   client ->
                        client.fetchPlace(detailsPlaceRequest)
                            .addOnSuccessListener { placeInfo: FetchPlaceResponse ->
                                city = findComponent(placeInfo, CITY_KEY)
                                state = findComponent(placeInfo, STATE_KEY)
                                country = findComponent(placeInfo, COUNTRY_KEY) }
                            .addOnFailureListener { ex ->
                                when (ex.javaClass) {
                                    ApiException::class.java -> {
                                        val exception = ex as ApiException
                                        handleApiException(exception, exception.statusCode)
                                    }
                                    else -> logger.error("Exception while fetching place details", ex) }
                            }
                    }
                    send(placesInfoTopic, GooglePlacesInfo(currentTime, currentTime, types, city, state, country, it, placeLikelihood, fromBroadcastRegistration))
                    logger.info("Google Places data with additional info sent")
                    return
                }
            }
            send(placesInfoTopic, GooglePlacesInfo(currentTime, currentTime, types, null, null, null, placeId, placeLikelihood, fromBroadcastRegistration))
            logger.info("Google Places data sent ")
        }
    }



    private fun findComponent(placeInfo: FetchPlaceResponse, component: String): String? =
         try {
             placeInfo.place.addressComponents?.asList()?.first { address ->
                 address.types.any { it.equals(component) }
             }?.name
         } catch (ex: NoSuchElementException) {
            logger.warn("Exception while retrieving data for additional place details ", ex)
             null
        }

    private fun handleApiException(exception: ApiException, statusCode: Int) {
        when (statusCode) {
            INVALID_API_CODE -> {
                logger.error("Invalid Api key, disconnecting now")
                disconnect()
            }
            LOCATION_UNAVAILABLE_CODE -> logger.warn("Location not enabled yet")
            else -> logger.error("ApiException occurred with status code $statusCode", exception)
        }
    }

    override fun disconnect() {
        if (!isClosed) {
            numOfAttempts = preferences.getInt(NUMBER_OF_ATTEMPTS_KEY, 0)
            val currentDelay = (baseDelay + 2.0.pow(numOfAttempts)).toLong()
            placeHandler.delay(currentDelay * 1000) {
                if (currentDelay < maxDelay) {
                    numOfAttempts++
                    preferences
                        .edit()
                        .putInt(NUMBER_OF_ATTEMPTS_KEY, numOfAttempts).apply()
                }
                    super.disconnect()
            }
        }
    }

    fun placesFetchInterval(interval: Long, intervalUnit: TimeUnit) {
        placesProcessor.interval(interval, intervalUnit)
    }

    private fun checkLocationPermissions(): Boolean {
        val hasPermissions = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            ((ContextCompat.checkSelfPermission(service, ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) || ContextCompat.checkSelfPermission(service, ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) }
        else {
            ((ContextCompat.checkSelfPermission(service, ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) || ContextCompat.checkSelfPermission(service, ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) &&
                    ContextCompat.checkSelfPermission(service, ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
        check(hasPermissions) { "Location permission is missing for Google Places" }
        return hasPermissions
    }

    override fun onClose() {
        placeHandler.execute {
            try {
                placesBroadcastReceiver?.unregister()
            } catch (ex: IllegalStateException) {
                logger.warn("Places receiver already unregistered in broadcast")
            }
            placesBroadcastReceiver = null
        }
        placesProcessor.close()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(GooglePlacesManager::class.java)

        private const val GOOGLE_PLACES_REQUEST_CODE = 483579
        private const val GOOGLE_PLACES_REQUEST_NAME = "org.radarbase.passive.google.places.GooglePlacesManager.PLACES_PROCESS_REQUEST"
        private const val INVALID_API_CODE = 9011
        private const val LOCATION_UNAVAILABLE_CODE = 8
        private const val CITY_KEY = "locality"
        private const val STATE_KEY = "administrative_area_level_1"
        private const val COUNTRY_KEY = "country"
        private const val NUMBER_OF_ATTEMPTS_KEY = "number_of_attempts_key"
        const val DEVICE_LOCATION_CHANGED = "org.radarbase.passive.google.places.GooglePlacesManager.DEVICE_LOCATION_CHANGED"

        private fun Place.Type.toPlacesType(): PlacesType? = when (this) {
            Place.Type.ACCOUNTING -> PlacesType.ACCOUNTING
            Place.Type.ADMINISTRATIVE_AREA_LEVEL_1 -> PlacesType.ADMINISTRATIVE_AREA_LEVEL_1
            Place.Type.ADMINISTRATIVE_AREA_LEVEL_2 -> PlacesType.ADMINISTRATIVE_AREA_LEVEL_2
            Place.Type.ADMINISTRATIVE_AREA_LEVEL_3 -> PlacesType.ADMINISTRATIVE_AREA_LEVEL_3
            Place.Type.ADMINISTRATIVE_AREA_LEVEL_4 -> PlacesType.ADMINISTRATIVE_AREA_LEVEL_4
            Place.Type.ADMINISTRATIVE_AREA_LEVEL_5 -> PlacesType.ADMINISTRATIVE_AREA_LEVEL_5
            Place.Type.AIRPORT -> PlacesType.AIRPORT
            Place.Type.AMUSEMENT_PARK -> PlacesType.AMUSEMENT_PARK
            Place.Type.AQUARIUM -> PlacesType.AQUARIUM
            Place.Type.ARCHIPELAGO -> PlacesType.ARCHIPELAGO
            Place.Type.ART_GALLERY -> PlacesType.ART_GALLERY
            Place.Type.ATM -> PlacesType.ATM
            Place.Type.BAKERY -> PlacesType.BAKERY
            Place.Type.BANK -> PlacesType.BANK
            Place.Type.BAR -> PlacesType.BAR
            Place.Type.BEAUTY_SALON -> PlacesType.BEAUTY_SALON
            Place.Type.BICYCLE_STORE -> PlacesType.BICYCLE_STORE
            Place.Type.BOOK_STORE -> PlacesType.BOOK_STORE
            Place.Type.BOWLING_ALLEY -> PlacesType.BOWLING_ALLEY
            Place.Type.BUS_STATION -> PlacesType.BUS_STATION
            Place.Type.CAFE -> PlacesType.CAFE
            Place.Type.CAMPGROUND -> PlacesType.CAMPGROUND
            Place.Type.CAR_DEALER -> PlacesType.CAR_DEALER
            Place.Type.CAR_RENTAL -> PlacesType.CAR_RENTAL
            Place.Type.CAR_REPAIR -> PlacesType.CAR_REPAIR
            Place.Type.CAR_WASH -> PlacesType.CAR_WASH
            Place.Type.CASINO -> PlacesType.CASINO
            Place.Type.CEMETERY -> PlacesType.CEMETERY
            Place.Type.CHURCH -> PlacesType.CHURCH
            Place.Type.CITY_HALL -> PlacesType.CITY_HALL
            Place.Type.CLOTHING_STORE -> PlacesType.CLOTHING_STORE
            Place.Type.COLLOQUIAL_AREA -> PlacesType.COLLOQUIAL_AREA
            Place.Type.CONTINENT -> PlacesType.CONTINENT
            Place.Type.CONVENIENCE_STORE -> PlacesType.CONVENIENCE_STORE
            Place.Type.COUNTRY -> PlacesType.COUNTRY
            Place.Type.COURTHOUSE -> PlacesType.COURTHOUSE
            Place.Type.DENTIST -> PlacesType.DENTIST
            Place.Type.DEPARTMENT_STORE -> PlacesType.DEPARTMENT_STORE
            Place.Type.DOCTOR -> PlacesType.DOCTOR
            Place.Type.DRUGSTORE -> PlacesType.DRUGSTORE
            Place.Type.ELECTRICIAN -> PlacesType.ELECTRICIAN
            Place.Type.ELECTRONICS_STORE -> PlacesType.ELECTRONICS_STORE
            Place.Type.EMBASSY -> PlacesType.EMBASSY
            Place.Type.ESTABLISHMENT -> PlacesType.ESTABLISHMENT
            Place.Type.FINANCE -> PlacesType.FINANCE
            Place.Type.FIRE_STATION -> PlacesType.FIRE_STATION
            Place.Type.FLOOR -> PlacesType.FLOOR
            Place.Type.FLORIST -> PlacesType.FLORIST
            Place.Type.FOOD -> PlacesType.FOOD
            Place.Type.FUNERAL_HOME -> PlacesType.FUNERAL_HOME
            Place.Type.FURNITURE_STORE -> PlacesType.FURNITURE_STORE
            Place.Type.GAS_STATION -> PlacesType.GAS_STATION
            Place.Type.GENERAL_CONTRACTOR -> PlacesType.GENERAL_CONTRACTOR
            Place.Type.GEOCODE -> PlacesType.GEOCODE
            Place.Type.GROCERY_OR_SUPERMARKET -> PlacesType.GROCERY_OR_SUPERMARKET
            Place.Type.GYM -> PlacesType.GYM
            Place.Type.HAIR_CARE -> PlacesType.HAIR_CARE
            Place.Type.HARDWARE_STORE -> PlacesType.HARDWARE_STORE
            Place.Type.HEALTH -> PlacesType.HEALTH
            Place.Type.HINDU_TEMPLE -> PlacesType.HINDU_TEMPLE
            Place.Type.HOME_GOODS_STORE -> PlacesType.HOME_GOODS_STORE
            Place.Type.HOSPITAL -> PlacesType.HOSPITAL
            Place.Type.INSURANCE_AGENCY -> PlacesType.INSURANCE_AGENCY
            Place.Type.INTERSECTION -> PlacesType.INTERSECTION
            Place.Type.JEWELRY_STORE -> PlacesType.JEWELRY_STORE
            Place.Type.LAUNDRY -> PlacesType.LAUNDRY
            Place.Type.LAWYER -> PlacesType.LAWYER
            Place.Type.LIBRARY -> PlacesType.LIBRARY
            Place.Type.LIGHT_RAIL_STATION -> PlacesType.LIGHT_RAIL_STATION
            Place.Type.LIQUOR_STORE -> PlacesType.LIQUOR_STORE
            Place.Type.LOCAL_GOVERNMENT_OFFICE -> PlacesType.LOCAL_GOVERNMENT_OFFICE
            Place.Type.LOCALITY -> PlacesType.LOCALITY
            Place.Type.LOCKSMITH -> PlacesType.LOCKSMITH
            Place.Type.LODGING -> PlacesType.LODGING
            Place.Type.MEAL_DELIVERY -> PlacesType.MEAL_DELIVERY
            Place.Type.MEAL_TAKEAWAY -> PlacesType.MEAL_TAKEAWAY
            Place.Type.MOSQUE -> PlacesType.MOSQUE
            Place.Type.MOVIE_RENTAL -> PlacesType.MOVIE_RENTAL
            Place.Type.MOVIE_THEATER -> PlacesType.MOVIE_THEATER
            Place.Type.MOVING_COMPANY -> PlacesType.MOVING_COMPANY
            Place.Type.MUSEUM -> PlacesType.MUSEUM
            Place.Type.NATURAL_FEATURE -> PlacesType.NATURAL_FEATURE
            Place.Type.NEIGHBORHOOD -> PlacesType.NEIGHBORHOOD
            Place.Type.NIGHT_CLUB -> PlacesType.NIGHT_CLUB
            Place.Type.PAINTER -> PlacesType.PAINTER
            Place.Type.PARK -> PlacesType.PARK
            Place.Type.PARKING -> PlacesType.PARKING
            Place.Type.PET_STORE -> PlacesType.PET_STORE
            Place.Type.PHARMACY -> PlacesType.PHARMACY
            Place.Type.PHYSIOTHERAPIST -> PlacesType.PHYSIOTHERAPIST
            Place.Type.PLACE_OF_WORSHIP -> PlacesType.PLACE_OF_WORSHIP
            Place.Type.PLUMBER -> PlacesType.PLUMBER
            Place.Type.PLUS_CODE -> PlacesType.PLUS_CODE
            Place.Type.POINT_OF_INTEREST -> PlacesType.POINT_OF_INTEREST
            Place.Type.POLICE -> PlacesType.POLICE
            Place.Type.POLITICAL -> PlacesType.POLITICAL
            Place.Type.POST_BOX -> PlacesType.POST_BOX
            Place.Type.POST_OFFICE -> PlacesType.POST_OFFICE
            Place.Type.POSTAL_CODE_PREFIX -> PlacesType.POSTAL_CODE_PREFIX
            Place.Type.POSTAL_CODE_SUFFIX -> PlacesType.POSTAL_CODE_SUFFIX
            Place.Type.POSTAL_CODE -> PlacesType.POSTAL_CODE
            Place.Type.POSTAL_TOWN -> PlacesType.POSTAL_TOWN
            Place.Type.PREMISE -> PlacesType.PREMISE
            Place.Type.PRIMARY_SCHOOL -> PlacesType.PRIMARY_SCHOOL
            Place.Type.REAL_ESTATE_AGENCY -> PlacesType.REAL_ESTATE_AGENCY
            Place.Type.RESTAURANT -> PlacesType.RESTAURANT
            Place.Type.ROOFING_CONTRACTOR -> PlacesType.ROOFING_CONTRACTOR
            Place.Type.ROOM -> PlacesType.ROOM
            Place.Type.ROUTE -> PlacesType.ROUTE
            Place.Type.RV_PARK -> PlacesType.RV_PARK
            Place.Type.SCHOOL -> PlacesType.SCHOOL
            Place.Type.SECONDARY_SCHOOL -> PlacesType.SECONDARY_SCHOOL
            Place.Type.SHOE_STORE -> PlacesType.SHOE_STORE
            Place.Type.SHOPPING_MALL -> PlacesType.SHOPPING_MALL
            Place.Type.SPA -> PlacesType.SPA
            Place.Type.STADIUM -> PlacesType.STADIUM
            Place.Type.STORAGE -> PlacesType.STORAGE
            Place.Type.STORE -> PlacesType.STORE
            Place.Type.STREET_ADDRESS -> PlacesType.STREET_ADDRESS
            Place.Type.STREET_NUMBER -> PlacesType.STREET_NUMBER
            Place.Type.SUBLOCALITY_LEVEL_1 -> PlacesType.SUBLOCALITY_LEVEL_1
            Place.Type.SUBLOCALITY_LEVEL_2 -> PlacesType.SUBLOCALITY_LEVEL_2
            Place.Type.SUBLOCALITY_LEVEL_3 -> PlacesType.SUBLOCALITY_LEVEL_3
            Place.Type.SUBLOCALITY_LEVEL_4 -> PlacesType.SUBLOCALITY_LEVEL_4
            Place.Type.SUBLOCALITY_LEVEL_5 -> PlacesType.SUBLOCALITY_LEVEL_5
            Place.Type.SUBLOCALITY -> PlacesType.SUBLOCALITY
            Place.Type.SUBPREMISE -> PlacesType.SUBPREMISE
            Place.Type.SUBWAY_STATION -> PlacesType.SUBWAY_STATION
            Place.Type.SUPERMARKET -> PlacesType.SUPERMARKET
            Place.Type.SYNAGOGUE -> PlacesType.SYNAGOGUE
            Place.Type.TAXI_STAND -> PlacesType.TAXI_STAND
            Place.Type.TOURIST_ATTRACTION -> PlacesType.TOURIST_ATTRACTION
            Place.Type.TOWN_SQUARE -> PlacesType.TOWN_SQUARE
            Place.Type.TRAIN_STATION -> PlacesType.TRAIN_STATION
            Place.Type.TRANSIT_STATION -> PlacesType.TRANSIT_STATION
            Place.Type.TRAVEL_AGENCY -> PlacesType.TRAVEL_AGENCY
            Place.Type.UNIVERSITY -> PlacesType.UNIVERSITY
            Place.Type.VETERINARY_CARE -> PlacesType.VETERINARY_CARE
            Place.Type.ZOO -> PlacesType.ZOO
            else -> null
        }
    }
}