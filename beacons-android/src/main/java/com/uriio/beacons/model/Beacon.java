package com.uriio.beacons.model;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseSettings;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Build;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.util.Log;

import com.uriio.beacons.Beacons;
import com.uriio.beacons.BleService;
import com.uriio.beacons.BuildConfig;
import com.uriio.beacons.Receiver;
import com.uriio.beacons.Storage;
import com.uriio.beacons.ble.Advertiser;

import java.util.UUID;

/**
 * Base container for an item.
 */
public abstract class Beacon implements Advertiser.SettingsProvider {
    private static final String TAG = "Beacon";
    private static boolean D = BuildConfig.DEBUG;

    /**
     * Beacon is active and should be enabled if Bluetooth is available.
     */
    public static final int ACTIVE_STATE_ENABLED = 0;
    /**
     * Beacon is active but paused.
     */
    public static final int ACTIVE_STATE_PAUSED  = 1;
    /**
     * Beacon is stopped. This is the default initial state.
     */
    public static final int ACTIVE_STATE_STOPPED = 2;

    // Advertise state constants that reflect current BLE status
    public static final int ADVERTISE_STOPPED       = 0;
    public static final int ADVERTISE_RUNNING       = 1;
    public static final int ADVERTISE_NO_BLUETOOTH  = 2;

    @SuppressLint("InlinedApi")
    private static final int DEFAULT_ADVERTISE_MODE = AdvertiseSettings.ADVERTISE_MODE_BALANCED;

    @SuppressLint("InlinedApi")
    private static final int DEFAULT_ADVERTISE_TX_POWER = AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM;

    /** Associated BLE object, if any. **/
    private Advertiser mAdvertiser = null;

    private String mName = null;

    /**
     * Unique beacon ID, needed for finding existing UNSAVED beacons. Cannot use a simple counter
     * for this because it will be different between restarts and we will find the wrong beacon when
     * an Alarm is triggered and we search for the beacon with that exact ID, while a random UUID will not
     * find any beacon at all (but since the beacon was unsaved that's to be expected).
     */
    private final UUID mUUID;

    /** Persistent ID for database purpose **/
    private long mStorageId = 0;

    private static int _lastStableId = 0;
    private final int mStableId = ++_lastStableId;

    private int mFlags;

    private int mAdvertiseMode;
    private int mTxPowerLevel;

    /** Current advertise status. This is the state of the BLE advertising, not of the beacon. **/
    private int mAdvertiseState = ADVERTISE_STOPPED;

    private int mActiveState = ACTIVE_STATE_STOPPED;

    private boolean mConnectable = false;
    private int mErrorCode;
    private String mErrorDetsils;

    /**
     * Creates a Beacon instance using the specified Cursor. Useful for
     * deserializing from a persistent layer such as a database.
     * @param cursor The Cursor to use, positioned at the index of the data to read.
     * @return A Beacon instance, or null in case it could not be created.
     */
    @SuppressWarnings("unused")
    public static Beacon fromCursor(@NonNull Cursor cursor) {
        return Storage.fromCursor(cursor);
    }

    /**
     * Constructs a beacon container.
     * @param advertiseMode        BLE advertising mode
     * @param txPowerLevel         BLE Transmit power level
     * @param name                 An optional name. Not used in actual BLE packets.
     */
    public Beacon(@Advertiser.Mode int advertiseMode,
                  @Advertiser.Power int txPowerLevel, int flags, String name) {
        mUUID = UUID.randomUUID();
        init(0, advertiseMode, txPowerLevel, flags, name);
    }

    public Beacon(@Advertiser.Mode int advertiseMode, @Advertiser.Power int txPowerLevel, int flags) {
        this(advertiseMode, txPowerLevel, flags, null);
    }

    public Beacon(int flags, String name) {
        this(DEFAULT_ADVERTISE_MODE, DEFAULT_ADVERTISE_TX_POWER, flags, name);
    }

    public Beacon(int flags) {
        this(flags, null);
    }

    public Beacon() {
        this(0);
    }

    @NonNull
    @Override
    public String toString() {
        if(BuildConfig.DEBUG) {
            return "stableID " + mStableId + " storeId " + mStorageId + " uuid " + mUUID + " name " + mName;
        }

        return super.toString();
    }

    /**
     * Sets some basic properties. Should only be called immediately after creation, and before save().
     */
    public void init(long storageId,
                     @Advertiser.Mode int advertiseMode,
                     @Advertiser.Power int txPowerLevel, int flags, String name) {
        mStorageId = storageId;
        mFlags = flags;
        mAdvertiseMode = advertiseMode;
        mTxPowerLevel = txPowerLevel;
        mName = name;
    }

    /**
     * Saves this beacon to persistent storage and optionally starts advertising.
     * @param startAdvertising    Enables the beacon to advertise, if not started already.
     * @return Same instance.
     */
    public Beacon save(boolean startAdvertising) {
        // don't save an already persisted beacon
        if (getSavedId() > 0) return this;

        Storage.getInstance().insert(this);

        if (startAdvertising) {
            start();
        }

        return this;
    }

    /**
     * Saves the beacon and enables BLE advertising.
     * @return Same instance.
     */
    @SuppressWarnings("unused")
    public Beacon save() {
        return save(true);
    }

    private void onEditDone(boolean needRestart) {
        if (getSavedId() > 0) {
            Storage.getInstance().update(this);
        }

        if (needRestart) {
            restartBeacon();
        }
    }

    private void restartBeacon() {
        if (ADVERTISE_RUNNING == getAdvertiseState()) {
            setActiveState(ACTIVE_STATE_PAUSED);
            setState(ACTIVE_STATE_ENABLED, false);  // already persisted as enabled
        }
    }

    /**
     * Enables BLE advertising for this beacon.
     * @return True on success. Note that the actual advertising may fail later, this call only transitions the beacon into enabled state.
     */
    public boolean start() {
        if(D) Log.d(TAG, "start() called");

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            // can't advertise below L
            return false;
        }

        if (Beacons.getActive().size() == 0) {
            Context context = Beacons.getContext();
            if (null == context) return false;

            if(D) Log.d(TAG, "no active beacons, starting service");

            context.startService(new Intent(context, BleService.class));
        }
        return setState(Beacon.ACTIVE_STATE_ENABLED, true);
    }

    /** Stops the beacon and deletes it from storage. */
    public void delete() {
        if (getActiveState() != ACTIVE_STATE_STOPPED) {
            setState(ACTIVE_STATE_STOPPED, false);
        }

        if (getSavedId() > 0) {
            Storage.getInstance().delete(this);
        }
    }

    public void pause() {
        setState(ACTIVE_STATE_PAUSED, true);
    }

    /** Stops the beacon from advertising. */
    public void stop() {
        // change state and save the new state if needed
        setState(ACTIVE_STATE_STOPPED, true);
    }

    private boolean setState(int state, boolean persist) {
        if(D) Log.d(TAG, "setState() called with: state = [" + state + "], persist = [" + persist + "]");

        if (state < 0 || state > 2) {
            return false;
        }

        Beacon targetBeacon = getSavedId() > 0 ? Beacons.findActive(getSavedId()) : Beacons.findActive(getUUID());

        if (null == targetBeacon) {
            // this beacon is not known to be active
            if (state != Beacon.ACTIVE_STATE_STOPPED) {
                // the new state is not 'stopped', so that means it will switch to active

                // if the Beacons singleton is initialized we need to mark ourself as active.
                // If it's not, beacon might get added a second time on Beacons init, if the service
                // is not started at this point (example: stopping last beacon -> starting a new one)
                // If we are not being persisted before Beacons.init, beacon gets discarded.
                // usecase - an app starts its first beacon, service gets created,
                // only the persisted beacons are known and activated.
                // TL;DR - make sure a new unpersisted beacon is kept track of
                if (Beacons.isInitialized()
                        || (0 == mStorageId))
                {
                    Beacons.getActive().add(this);
                    Beacons.onActiveBeaconAdded(this);
                }
            }

            targetBeacon = this;
        }

        if (state != targetBeacon.getActiveState()) {
            if(D) Log.d(TAG, "new state! " + state + " old " + targetBeacon.getActiveState());

            // item changed state
            targetBeacon.setActiveState(state);
            if (persist && targetBeacon.getSavedId() > 0) {
                Storage.getInstance().updateState(targetBeacon, state);
            }

            sendStateBroadcast(targetBeacon);
        }

        return true;
    }

    private static void sendStateBroadcast(Beacon beacon) {
        Context context = Beacons.getContext();
        if (null != context) {
            Intent intent = new Intent(BleService.ACTION_ITEM_STATE);

            if (beacon.getSavedId() > 0) {
                intent.putExtra(BleService.EXTRA_ITEM_STORAGE_ID, beacon.getSavedId());
            }
            else {
                intent.putExtra(BleService.EXTRA_ITEM_ID, beacon.getUUID());
            }

            LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
        }
    }

    public void setActiveState(int state) {
        mActiveState = state;
    }

    public int getActiveState() {
        return mActiveState;
    }

    /**
     * @return Persistent ID of this beacon. If 0, then the beacon is not yet saved.
     */
    public long getSavedId() {
        return mStorageId;
    }

    /**
     * @return A non-persistent unique ID different than any other beacon.
     * The ID is not stable between service restarts and can easily collide.
     */
    @SuppressWarnings("unused")
    public long getStableId() {
        return mStableId;
    }

    /**
     * @return A non-persistent UUID different than any other beacon.
     * The UUID is not stable between service restarts, but it will not collide.
     */
    public UUID getUUID() {
        return mUUID;
    }

    public Advertiser getAdvertiser() {
        return mAdvertiser;
    }

    // region Advertiser.SettingsProvider

    @Override
    @Advertiser.Mode
    public int getAdvertiseMode() {
        return mAdvertiseMode;
    }

    @Override
    @Advertiser.Power
    public int getTxPowerLevel() {
        return mTxPowerLevel;
    }

    @Override
    public int getTimeout() {
        return 0;
    }

    @Override
    public boolean isConnectable() {
        return mConnectable;
    }

    // endregion

    public int getFlags() {
        return mFlags;
    }

    public int getAdvertiseState() {
        return mAdvertiseState;
    }

    public void setAdvertiseState(int status) {
        mAdvertiseState = status;
    }

    public Advertiser recreateAdvertiser(BleService bleService) {
        mErrorCode = 0;
        mErrorDetsils = null;
        return (mAdvertiser = createAdvertiser(bleService));
    }

    protected abstract Advertiser createAdvertiser(BleService advertisersManager);

    public abstract int getKind();

    /**
     * Descriptive name. Not used for BLE advertising purposes.
     */
    public String getName() {
        return mName;
    }

    /** Called by the service when Bluetooth enters disabled state. Never call this directly. */
    public long onBluetoothDisabled(BleService bleService) {
        cancelRefresh(bleService);

        long pduCount = 0;
        setAdvertiseState(ADVERTISE_NO_BLUETOOTH);

        if (null != mAdvertiser) {
            pduCount = mAdvertiser.clearPDUCount();

            // do not attempt to re-use the same callback for future broadcasts
            mAdvertiser = null;
        }

        return pduCount;
    }

    public void onBluetoothEnabled(BleService service) {
        if (ACTIVE_STATE_ENABLED == mActiveState) {
            onAdvertiseEnabled(service);
        } else {
            // beacon was active but not enabled, aka PAUSED
            setAdvertiseState(ADVERTISE_STOPPED);
        }
    }

    public void cancelRefresh(BleService bleService) {
        if(getScheduledRefreshElapsedTime() > 0) {
            // cancel the scheduled beacon recreation
            bleService.cancelAlarm(getAlarmPendingIntent(bleService));
        }
    }

    public void onAdvertiseFailed(int errorCode) {
        if (AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS == errorCode){
            // don't stop - we could attempt to start the beacon again if we free a slot
            pause();
        }
        else {
            // fatal, no point in keeping the beacon in active state
            stop();
        }

        mAdvertiser = null;
        mErrorCode = errorCode;
        setErrorDetails(Advertiser.getErrorName(errorCode));
    }

    public int getErrorCode() {
        return mErrorCode;
    }

    public String getErrorDetsils() {
        return mErrorDetsils;
    }

    /**
     * @return [Display purposes] UNIX timestamp at which the beacon should refresh. This value may
     * be wrong if the beacon must be synced with an external service because the system time may be
     * wrong. Subclasses that need to present an absolute correct time should override this method.
     */
    public long getScheduledRefreshTime() {
        long refreshElapsedTime = getScheduledRefreshElapsedTime();
        return 0 == refreshElapsedTime ? 0 : System.currentTimeMillis() - SystemClock.elapsedRealtime() + refreshElapsedTime;
    }

    /**
     * @return The SystemClock.elapsedRealtime() value on which this beacon should be restarted.
     * This should NOT be computed based on the current system time.
     */
    public long getScheduledRefreshElapsedTime() {
        return 0;
    }

    /**
     * Called when the item should start advertising a new BLE beacon.
     * Default implementation starts a new beacon advertiser; subclasses may override with other behaviour.
     * @param service    BLE Service
     */
    public void onAdvertiseEnabled(BleService service) {
        // (re)create the beacon
        if (!service.startBeaconAdvertiser(this)) {
            if(D) Log.e(TAG, "startBeaconAdvertiser failed");
        }
    }

    public BaseEditor edit() {
        return new BaseEditor();
    }

    /**
     * Sets the persistent item ID. Has no effect if the item already has an ID.
     * @param id    The item ID
     */
    public void setStorageId(long id) {
        if (0 == mStorageId) {
            mStorageId = id;
        }
    }

    public PendingIntent getAlarmPendingIntent(Context context) {
        Intent intent = new Intent(BleService.ACTION_ALARM, null, context, Receiver.class);

        if (getSavedId() > 0) intent.putExtra(BleService.EXTRA_ITEM_STORAGE_ID, getSavedId());
        else intent.putExtra(BleService.EXTRA_ITEM_ID, getUUID());

        // use a unique private request code, or else the returned PendingIntent is "identical" for all beacons, being reused
        return PendingIntent.getBroadcast(context, mStableId, intent, 0);
    }

    public void setErrorDetails(String error) {
        mErrorDetsils = error;
    }

    public CharSequence getNotificationSubject() {
        return null == mName ? "<unnamed>" : mName.substring(0, Math.min(30, mName.length()));
    }

    public class BaseEditor<T> {
        private boolean mNeedsRestart = false;

        public BaseEditor<T> setAdvertiseMode(@Advertiser.Mode int mode) {
            if (mode != mAdvertiseMode) {
                mAdvertiseMode = mode;
                setNeedsRestart();
            }
            return this;
        }

        public BaseEditor<T> setAdvertiseTxPower(@Advertiser.Power int txPowerLevel) {
            if (txPowerLevel != mTxPowerLevel) {
                mTxPowerLevel = txPowerLevel;
                setNeedsRestart();
            }
            return this;
        }

        public BaseEditor<T> setConnectable(boolean connectable) {
            if (connectable != mConnectable) {
                mConnectable = connectable;
                setNeedsRestart();
            }
            return this;
        }

        public BaseEditor<T> setName(String name) {
            if (null == name || !name.equals(mName)) {
                mName = name;
            }
            return this;
        }

        public BaseEditor<T> setFlags(int flags) {
            if (mFlags != flags) {
                mFlags = flags;
                setNeedsRestart();  // ?...
            }
            return this;
        }

        public void apply() {
            onEditDone(mNeedsRestart);
        }

        /**
         * Indicates that the beacon should be restarted
         * after the editor's changes are applied.
         */
        @SuppressWarnings("WeakerAccess")
        protected void setNeedsRestart() {
            mNeedsRestart = true;
        }
    }
}
