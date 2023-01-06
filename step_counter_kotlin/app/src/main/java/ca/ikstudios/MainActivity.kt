package ca.ikstudios

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import ca.ikstudios.common.logger.Log
import ca.ikstudios.common.logger.LogView
import ca.ikstudios.common.logger.LogWrapper
import ca.ikstudios.common.logger.MessageOnlyLogFilter
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.fitness.Fitness
import com.google.android.gms.fitness.FitnessOptions
import com.google.android.gms.fitness.data.DataSource
import com.google.android.gms.fitness.data.DataType
import com.google.android.gms.fitness.data.Field
import com.google.android.gms.fitness.request.DataSourcesRequest
import com.google.android.gms.fitness.request.OnDataPointListener
import com.google.android.gms.fitness.request.SensorRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.material.snackbar.Snackbar
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * This enum is used to define actions that can be performed after a successful sign in to Fit.
 * One of these values is passed to the Fit sign-in, and returned in a successful callback, allowing
 * subsequent execution of the desired action.
 */
enum class FitActionRequestCode {
    SUBSCRIBE,
    READ_DATA
}

/**
 * This sample demonstrates combining the Recording API and History API of the Google Fit platform
 * to record steps, and display the daily current step count. It also demonstrates how to
 * authenticate a user with Google Play Services.
 */
class MainActivity : AppCompatActivity() {

    private val TAG = MainActivity::class.java.simpleName


    private val fitnessOptions = FitnessOptions.builder()
            .addDataType(DataType.TYPE_STEP_COUNT_DELTA,FitnessOptions.ACCESS_READ)
            .addDataType(DataType.TYPE_DISTANCE_DELTA, FitnessOptions.ACCESS_READ)
            .addDataType(DataType.TYPE_CALORIES_EXPENDED, FitnessOptions.ACCESS_READ)
            .addDataType(DataType.TYPE_MOVE_MINUTES, FitnessOptions.ACCESS_READ)
            .addDataType(DataType.TYPE_HEART_POINTS, FitnessOptions.ACCESS_READ)
            .build()

    private val runningQOrLater =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    private var textToSpeech : TextToSpeech? = null
    var steps : String = ""
    var distance : String = ""
    var caloriesExpended : String = ""
    var speech : String = ""
    var moveMinutes : String = ""
    var heartPoints : String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // This method sets up our custom logger, which will print all log messages to the device
        // screen, as well as to adb logcat.
        initializeLogging()
        checkPermissionsAndRun(FitActionRequestCode.SUBSCRIBE)
        val button: Button = findViewById(R.id.button2)
        textToSpeech= TextToSpeech(getApplicationContext(), TextToSpeech.OnInitListener() {
            fun onInit (i : Int) {
                if (i == TextToSpeech.SUCCESS) {
                    var lang = textToSpeech!!.setLanguage(Locale.US)
                }
            }
        })
        button.setOnClickListener(View.OnClickListener {
            var speed = 1.0.toFloat()
            textToSpeech?.setSpeechRate(speed)
            readData()
        })
    }

    private fun checkPermissionsAndRun(fitActionRequestCode: FitActionRequestCode) {
        if (permissionApproved()) {
            fitSignIn(fitActionRequestCode)
        } else {
            requestRuntimePermissions(fitActionRequestCode)
        }
    }

    /**
     * Checks that the user is signed in, and if so, executes the specified function. If the user is
     * not signed in, initiates the sign in flow, specifying the post-sign in function to execute.
     *
     * @param requestCode The request code corresponding to the action to perform after sign in.
     */
    private fun fitSignIn(requestCode: FitActionRequestCode) {
        if (oAuthPermissionsApproved()) {
            performActionForRequestCode(requestCode)
        } else {
            requestCode.let {
                GoogleSignIn.requestPermissions(
                        this,
                        requestCode.ordinal,
                        getGoogleAccount(), fitnessOptions)
            }
        }
    }

    /**
     * Handles the callback from the OAuth sign in flow, executing the post sign in function
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (resultCode) {
            RESULT_OK -> {
                val postSignInAction = FitActionRequestCode.values()[requestCode]
                postSignInAction.let {
                    performActionForRequestCode(postSignInAction)
                }
            }
            else -> oAuthErrorMsg(requestCode, resultCode)
        }
    }

    /**
     * Runs the desired method, based on the specified request code. The request code is typically
     * passed to the Fit sign-in flow, and returned with the success callback. This allows the
     * caller to specify which method, post-sign-in, should be called.
     *
     * @param requestCode The code corresponding to the action to perform.
     */
    private fun performActionForRequestCode(requestCode: FitActionRequestCode) = when (requestCode) {
        FitActionRequestCode.READ_DATA -> readData()
        FitActionRequestCode.SUBSCRIBE -> subscribe()
    }

    private fun oAuthErrorMsg(requestCode: Int, resultCode: Int) {
        val message = """
            There was an error signing into Fit. Check the troubleshooting section of the README
            for potential issues.
            Request code was: $requestCode
            Result code was: $resultCode
        """.trimIndent()
        Log.e(TAG, message)
    }

    private fun oAuthPermissionsApproved() = GoogleSignIn.hasPermissions(getGoogleAccount(), fitnessOptions)

    /**
     * Gets a Google account for use in creating the Fitness client. This is achieved by either
     * using the last signed-in account, or if necessary, prompting the user to sign in.
     * `getAccountForExtension` is recommended over `getLastSignedInAccount` as the latter can
     * return `null` if there has been no sign in before.
     */
    private fun getGoogleAccount() = GoogleSignIn.getAccountForExtension(this, fitnessOptions)

    /** Records step data by requesting a subscription to background step data.  */
    private fun subscribe() {
        // To create a subscription, invoke the Recording API. As soon as the subscription is
        // active, fitness data will start recording.
        Fitness.getRecordingClient(this, getGoogleAccount())
            .subscribe(DataType.TYPE_DISTANCE_DELTA)
            .addOnSuccessListener {
                Log.i(TAG,"Subscription to distance was successful!")
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "There was a problem subscribing to distance!", e)
            }
        Fitness.getRecordingClient(this, getGoogleAccount())
            .subscribe(DataType.TYPE_STEP_COUNT_DELTA)
            .addOnSuccessListener {
                Log.i(TAG,"Subscription was successful to step count!")
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "There was a problem subscribing to step count!", e)
            }
        Fitness.getRecordingClient(this, getGoogleAccount())
            .subscribe(DataType.TYPE_CALORIES_EXPENDED)
            .addOnSuccessListener {
                Log.i(TAG,"Subscription was successful to calories expended!")
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "There was a problem subscribing to calories expended!", e)
            }
        Fitness.getRecordingClient(this, getGoogleAccount())
            .subscribe(DataType.TYPE_MOVE_MINUTES)
            .addOnSuccessListener {
                Log.i(TAG,"Subscription was successful to move minutes!")
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "There was a problem subscribing to move minutes!", e)
            }
        Fitness.getRecordingClient(this, getGoogleAccount())
            .subscribe(DataType.TYPE_HEART_POINTS)
            .addOnSuccessListener {
                Log.i(TAG,"Subscription was successful to heart points!")
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "There was a problem subscribing to heart points!", e)
            }
    }

    /**
     * Reads the current daily step total, computed from midnight of the current day on the device's
     * current timezone.
     */
    private fun readData() : String {
        Fitness.getHistoryClient(this, GoogleSignIn.getAccountForExtension(this, fitnessOptions))
            .readDailyTotal(DataType.TYPE_STEP_COUNT_DELTA)
            .addOnSuccessListener { result ->
                val totalSteps =
                    result.dataPoints.firstOrNull()?.getValue(Field.FIELD_STEPS)?.asInt() ?: 0
                // Do something with totalSteps
                steps = "Total steps: $totalSteps steps"
            }
            .addOnFailureListener { e ->
                Log.i(TAG, "There was a problem getting steps.", e)
            }
        Fitness.getHistoryClient(this, GoogleSignIn.getAccountForExtension(this, fitnessOptions))
            .readDailyTotal(DataType.TYPE_DISTANCE_DELTA)
            .addOnSuccessListener { result ->
                val totalDistance =
                    result.dataPoints.firstOrNull()?.getValue(Field.FIELD_DISTANCE)?.asFloat() ?: 0
                // Do something with totalSteps
                distance = "Total distance: $totalDistance metres"
            }
            .addOnFailureListener { e ->
                Log.i(TAG, "There was a problem getting distance.", e)
            }
        Fitness.getHistoryClient(this, GoogleSignIn.getAccountForExtension(this, fitnessOptions))
            .readDailyTotal(DataType.TYPE_CALORIES_EXPENDED)
            .addOnSuccessListener { result ->
                val totalCaloriesExpended =
                    result.dataPoints.firstOrNull()?.getValue(Field.FIELD_CALORIES)?.asFloat() ?: 0
                // Do something with totalSteps
                caloriesExpended = "Total calories expended: $totalCaloriesExpended calories"
            }
            .addOnFailureListener { e ->
                Log.i(TAG, "There was a problem getting calories expended.", e)
            }
        Fitness.getHistoryClient(this, GoogleSignIn.getAccountForExtension(this, fitnessOptions))
            .readDailyTotal(DataType.TYPE_MOVE_MINUTES)
            .addOnSuccessListener { result ->
                val totalMoveMinutes =
                    result.dataPoints.firstOrNull()?.getValue(Field.FIELD_DURATION)?.asInt() ?: 0
                // Do something with totalSteps
                moveMinutes = "Total move minutes: $totalMoveMinutes minutes"
            }
            .addOnFailureListener { e ->
                Log.i(TAG, "There was a problem getting move minutes.", e)
            }
        Fitness.getHistoryClient(this, GoogleSignIn.getAccountForExtension(this, fitnessOptions))
            .readDailyTotal(DataType.TYPE_HEART_POINTS)
            .addOnSuccessListener { result ->
                val totalHeartPoints =
                    result.dataPoints.firstOrNull()?.getValue(Field.FIELD_INTENSITY)?.asInt() ?: 0
                // Do something with totalSteps
                heartPoints = "Total heart points: $totalHeartPoints points"
            }
            .addOnFailureListener { e ->
                Log.i(TAG, "There was a problem getting heart points.", e)
            }
        speech = "$steps and $distance and $caloriesExpended and $moveMinutes and $heartPoints"
        textToSpeech?.speak(speech, TextToSpeech.QUEUE_FLUSH, null)
        return "Data read!"
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the main; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        if (id == R.id.action_read_data) {
            fitSignIn(FitActionRequestCode.READ_DATA)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /** Initializes a custom log class that outputs both to in-app targets and logcat.  */
    private fun initializeLogging() {
        // Wraps Android's native log framework.
        val logWrapper = LogWrapper()
        // Using Log, front-end to the logging chain, emulates android.util.log method signatures.
        Log.setLogNode(logWrapper)
        // Filter strips out everything except the message text.
        val msgFilter = MessageOnlyLogFilter()
        logWrapper.next = msgFilter
        // On screen logging via a customized TextView.
        val logView = findViewById<View>(R.id.sample_logview) as LogView
        TextViewCompat.setTextAppearance(logView, R.style.Log)
        logView.setBackgroundColor(Color.WHITE)
        msgFilter.next = logView
    }

    private fun permissionApproved(): Boolean {
        val approved = if (runningQOrLater) {
            PackageManager.PERMISSION_GRANTED == ActivityCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
        } else {
            true
        }
        return approved
    }

    private fun requestRuntimePermissions(requestCode: FitActionRequestCode) {
        val shouldProvideRationale =
                ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACTIVITY_RECOGNITION)

        // Provide an additional rationale to the user. This would happen if the user denied the
        // request previously, but didn't check the "Don't ask again" checkbox.
        requestCode.let {
            if (shouldProvideRationale) {
                Log.i(TAG, "Displaying permission rationale to provide additional context.")
                Snackbar.make(
                        findViewById(R.id.main_activity_view),
                    R.string.permission_rationale,
                        Snackbar.LENGTH_INDEFINITE)
                        .setAction(R.string.ok) {
                            // Request permission
                            ActivityCompat.requestPermissions(this,
                                    arrayOf(Manifest.permission.ACTIVITY_RECOGNITION),
                                    requestCode.ordinal)
                        }
                        .show()
            } else {
                Log.i(TAG, "Requesting permission")
                // Request permission. It's possible this can be auto answered if device policy
                // sets the permission in a given state or the user denied the permission
                // previously and checked "Never ask again".
                ActivityCompat.requestPermissions(this,
                        arrayOf(Manifest.permission.ACTIVITY_RECOGNITION),
                        requestCode.ordinal)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>,
                                            grantResults: IntArray) {
        when {
            grantResults.isEmpty() -> {
                // If user interaction was interrupted, the permission request
                // is cancelled and you receive empty arrays.
                Log.i(TAG, "User interaction was cancelled.")
            }
            grantResults[0] == PackageManager.PERMISSION_GRANTED -> {
                // Permission was granted.
                val fitActionRequestCode = FitActionRequestCode.values()[requestCode]
                fitActionRequestCode.let {
                    fitSignIn(fitActionRequestCode)
                }
            }
            else -> {
                // Permission denied.

                // In this Activity we've chosen to notify the user that they
                // have rejected a core permission for the app since it makes the Activity useless.
                // We're communicating this message in a Snackbar since this is a sample app, but
                // core permissions would typically be best requested during a welcome-screen flow.

                // Additionally, it is important to remember that a permission might have been
                // rejected without asking the user for permission (device policy or "Never ask
                // again" prompts). Therefore, a user interface affordance is typically implemented
                // when permissions are denied. Otherwise, your app could appear unresponsive to
                // touches or interactions which have required permissions.

                Snackbar.make(
                        findViewById(R.id.main_activity_view),
                    R.string.permission_denied_explanation,
                        Snackbar.LENGTH_INDEFINITE)
                        .setAction(R.string.settings) {
                            // Build intent that displays the App settings screen.
                            val intent = Intent()
                            intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                            val uri = Uri.fromParts("package", BuildConfig.APPLICATION_ID, null)
                            intent.data = uri
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            startActivity(intent)
                        }
                        .show()
            }
        }
    }
}