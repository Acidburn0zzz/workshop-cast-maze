package fr.xebia.workshop.cast.maze;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.app.MediaRouteActionProvider;
import android.support.v7.media.MediaRouteSelector;
import android.support.v7.media.MediaRouter;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;
import butterknife.ButterKnife;
import butterknife.OnClick;
import com.google.android.gms.cast.ApplicationMetadata;
import com.google.android.gms.cast.Cast;
import com.google.android.gms.cast.CastDevice;
import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;


public class MazeActivity extends ActionBarActivity {

    private static final String TAG = MazeActivity.class.getSimpleName();

    private MediaRouter mMediaRouter;
    private MediaRouteSelector mMediaRouteSelector;
    private CastDevice mSelectedDevice;
    private GoogleApiClient mApiClient;
    private GameChannel mGameChannel;
    private boolean mApplicationStarted;
    private boolean mWaitingForReconnect;
    private String mSessionId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_maze);

        ButterKnife.inject(this);

        // Configure Cast device discovery
        mMediaRouter = MediaRouter.getInstance(getApplicationContext());
        mMediaRouteSelector = new MediaRouteSelector.Builder()
                .addControlCategory(CastMediaControlIntent.categoryForCast(getString(R.string.app_id))).build();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.maze, menu);
        MenuItem mediaRouteMenuItem = menu.findItem(R.id.media_route_menu_item);
        MediaRouteActionProvider mediaRouteActionProvider = (MediaRouteActionProvider) MenuItemCompat.getActionProvider(mediaRouteMenuItem);
        // Set the MediaRouteActionProvider selector for device discovery.
        mediaRouteActionProvider.setRouteSelector(mMediaRouteSelector);
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Start media router discovery
        mMediaRouter.addCallback(mMediaRouteSelector, new MediaRouterCallback(), MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN);
    }

    /**
     * Callback for MediaRouter events
     */
    private class MediaRouterCallback extends MediaRouter.Callback {

        @Override
        public void onRouteSelected(MediaRouter router, MediaRouter.RouteInfo info) {
            Log.d(TAG, "onRouteSelected");
            // Handle the user route selection.
            mSelectedDevice = CastDevice.getFromBundle(info.getExtras());
            launchReceiver();
        }

        @Override
        public void onRouteUnselected(MediaRouter router, MediaRouter.RouteInfo info) {
            Log.d(TAG, "onRouteUnselected: info=" + info);
            teardown();
            mSelectedDevice = null;
        }
    }

    /**
     * Start the receiver app
     */
    private void launchReceiver() {
        try {
            // Connect to Google Play services
            Cast.CastOptions.Builder apiOptionsBuilder = Cast.CastOptions
                    .builder(mSelectedDevice, new Cast.Listener() {

                        @Override
                        public void onApplicationDisconnected(int errorCode) {
                            Log.d(TAG, "application has stopped");
                            teardown();
                        }

                    });
            mApiClient = new GoogleApiClient.Builder(this)
                    .addApi(Cast.API, apiOptionsBuilder.build())
                    .addConnectionCallbacks(new ConnectionCallbacks())
                    .addOnConnectionFailedListener(new ConnectionFailedListener())
                    .build();

            mApiClient.connect();
        } catch (Exception e) {
            Log.e(TAG, "Failed launchReceiver", e);
        }
    }

    /**
     * Google Play services callbacks
     */
    private class ConnectionCallbacks implements GoogleApiClient.ConnectionCallbacks {
        @Override
        public void onConnected(Bundle connectionHint) {
            Log.d(TAG, "onConnected");

            if (mApiClient == null) {
                // We got disconnected while this runnable was pending
                // execution.
                return;
            }

            try {
                if (mWaitingForReconnect) {
                    mWaitingForReconnect = false;

                    // Check if the receiver app is still running
                    if ((connectionHint != null) && connectionHint.getBoolean(Cast.EXTRA_APP_NO_LONGER_RUNNING)) {
                        Log.d(TAG, "App  is no longer running");
                        teardown();
                    } else {
                        // Re-create the custom message channel
                        try {
                            Cast.CastApi.setMessageReceivedCallbacks(
                                    mApiClient,
                                    mGameChannel.getNamespace(),
                                    mGameChannel);
                        } catch (IOException e) {
                            Log.e(TAG, "Exception while creating channel", e);
                        }
                    }
                } else {
                    // Launch the receiver app
                    Cast.CastApi.launchApplication(mApiClient, getString(R.string.app_id)).setResultCallback(new
                            CastConnectionResultCallback());
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to launch application", e);
            }
        }

        @Override
        public void onConnectionSuspended(int cause) {
            Log.d(TAG, "onConnectionSuspended");
            mWaitingForReconnect = true;
        }
    }

    /**
     * Google Play services callbacks
     */
    private class ConnectionFailedListener implements GoogleApiClient.OnConnectionFailedListener {
        @Override
        public void onConnectionFailed(ConnectionResult result) {
            Log.e(TAG, "onConnectionFailed ");
            teardown();
        }
    }

    class CastConnectionResultCallback implements ResultCallback<Cast.ApplicationConnectionResult> {
        @Override
        public void onResult(Cast.ApplicationConnectionResult result) {
            Status status = result.getStatus();
            Log.d(TAG, "ApplicationConnectionResultCallback.onResult: statusCode" + status.getStatusCode());
            if (status.isSuccess()) {
                ApplicationMetadata applicationMetadata = result.getApplicationMetadata();
                mSessionId = result.getSessionId();
                String applicationStatus = result.getApplicationStatus();
                boolean wasLaunched = result.getWasLaunched();
                Log.d(TAG, "application name: " + applicationMetadata.getName() + ", status: " + applicationStatus + ", " +
                        "sessionId: " + mSessionId + ", wasLaunched: " + wasLaunched);
                mApplicationStarted = true;

                // Create the custom message channel
                mGameChannel = new GameChannel();
                try {
                    Cast.CastApi.setMessageReceivedCallbacks(mApiClient, mGameChannel.getNamespace(), mGameChannel);
                } catch (IOException e) {
                    Log.e(TAG, "Exception while creating channel", e);
                }
            } else {
                Log.e(TAG, "application could not launch");
                teardown();
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        teardown();
    }

    /**
     * Tear down the connection to the receiver
     */
    private void teardown() {
        Log.d(TAG, "teardown");
        if (mApiClient != null && mApplicationStarted && mApiClient.isConnected()) {
            try {
                Cast.CastApi.leaveApplication(mApiClient);
                if (mGameChannel != null) {
                    Cast.CastApi.removeMessageReceivedCallbacks(mApiClient, mGameChannel.getNamespace());
                    mGameChannel = null;
                }
            } catch (IOException e) {
                Log.e(TAG, "Exception while removing channel", e);
            }
            mApiClient.disconnect();
        }
        mApiClient = null;
        mApplicationStarted = false;
        mSelectedDevice = null;
        mWaitingForReconnect = false;
        mSessionId = null;
    }


    /**
     * Send a text message to the receiver
     *
     * @param message
     */
    private void sendMessage(String message) {
        if (mApiClient != null && mGameChannel != null) {
            try {
                Cast.CastApi.sendMessage(mApiClient, mGameChannel.getNamespace(), message)
                        .setResultCallback(new ResultCallback<Status>() {
                            @Override
                            public void onResult(Status result) {
                                if (!result.isSuccess()) {
                                    Log.e(TAG, "Sending message failed");
                                }
                            }
                        });
            } catch (Exception e) {
                Log.e(TAG, "Exception while sending message", e);
            }
        } else {
            Toast.makeText(MazeActivity.this, message, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Custom game channel
     */
    class GameChannel implements Cast.MessageReceivedCallback {

        /**
         * @return custom namespace
         */
        public String getNamespace() {
            return getString(R.string.namespace);
        }

        /*
         * Receive message from the receiver app
         */
        @Override
        public void onMessageReceived(CastDevice castDevice, String namespace, String message) {
            Log.d(TAG, "onMessageReceived: " + message);
            try {
                String hexColor = new JSONObject(message).getString("color");
                getWindow().setBackgroundDrawable(new ColorDrawable(hex2Rgb(hexColor)));
            } catch (JSONException e) {
                Log.d(TAG, "uh oh, should not happen!", e);
            }
        }

    }

    public static int hex2Rgb(String colorStr) {
        return Color.rgb(Integer.valueOf(colorStr.substring(1, 3), 16),
                Integer.valueOf(colorStr.substring(3, 5), 16),
                Integer.valueOf(colorStr.substring(5, 7), 16));
    }

    @OnClick({R.id.up, R.id.left, R.id.down, R.id.right})
    public void onActionClicked(View v) {
        sendMessage(v.getTag().toString());
    }

}
