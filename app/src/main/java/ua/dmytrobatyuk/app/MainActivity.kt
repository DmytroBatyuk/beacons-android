package ua.dmytrobatyuk.app

import android.bluetooth.le.AdvertiseSettings
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import com.uriio.beacons.Beacons
import com.uriio.beacons.Util
import com.uriio.beacons.ble.gatt.EddystoneGattServer
import com.uriio.beacons.eid.EIDUtils
import com.uriio.beacons.eid.LocalEIDResolver
import com.uriio.beacons.model.*

class MainActivity : AppCompatActivity() {

    var gattServer: EddystoneGattServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)


        //TODO: do we need request permissions?

        Beacons.initialize(applicationContext)

        gattServer = EddystoneGattServer(EddystoneGattServer.Listener { configuredBeacon ->
            Log.e("DIMA", "onConfigureBeacon: is null: ${null==configuredBeacon}")
            configuredBeacon?.save(true)
            gattServer = null
        })
        gattServer?.setLogger { tag, message ->
            Log.e(tag, message)
        }

        Log.e("DIMA", "started=${gattServer?.start("https://google.com")}")

        val lockKey = Util.binToHex(gattServer?.beacon?.lockKey, ' ')
        Log.e("DIMA", "lock key=$lockKey")


    }

    override fun onDestroy() {
        super.onDestroy()
        gattServer?.close()
        gattServer = null
    }
}
