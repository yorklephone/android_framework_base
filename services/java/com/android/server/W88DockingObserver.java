/***RK_ID:RK_DOCK. DEP_RK_ID:NULL. AUT:xieyan@gmail.com DATE:2010-07-14. START***/
/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server;

import android.app.ActivityManagerNative;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.UEventObserver;
import android.util.Log;
import android.media.AudioManager;

import java.io.FileReader;
import java.io.FileNotFoundException;

/**
 * Eaddy 2009/10/15 : W88DockingObserver monitors for W88's Dockning .
 */
class W88DockingObserver extends UEventObserver {

	private static final String TAG = W88DockingObserver.class.getSimpleName();

	private static final boolean LOG = true;

	private final Context mContext;

	//    private final WakeLock mWakeLock;  // held while there is a pending route change
	private int mDocSpkState;
	private AudioManager mAudioManager;
	private String mHeadsetName;
	private int mHeadsetState;
	private boolean mAudioRouteNeedsUpdate;
	private static final String DOCKING_UEVENT_MATCH = "DEVPATH=/devices/virtual/switch/docking-device"; 
        private static final String DOCKING_STATE_PATH = "/sys/class/switch/docking-device/state";
        private static final String DOCKING_NAME_PATH = "/sys/class/switch/docking-device/name";

	public W88DockingObserver(Context context) {

		mContext = context;
		mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
		startObserving(DOCKING_UEVENT_MATCH);
		init();
	}

	private final void sendIntent() 
	{
	//	Log.e(TAG, "sendIntent---------------------");

	//	Intent intent = new Intent(Intent.ACTION_BATTERY_CHANGED);
	//	intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
	//	ActivityManagerNative.broadcastStickyIntent(intent, null);
	}
	/* Override */
	public void onUEvent(UEventObserver.UEvent event) {

		if (LOG) Log.e(TAG, "Docking UEVENT: " + event.toString());
		mAudioRouteNeedsUpdate = true;

		try {

			if (LOG) Log.e(TAG, "Eaddy check 0.1");
			update(event.get("SWITCH_NAME"), Integer.parseInt(event.get("SWITCH_STATE")));
			if (LOG) Log.e(TAG, "Eaddy check 0.2");

		} catch (NumberFormatException e) {
			Log.e(TAG, "Could not parse switch state from event " + event);
		} catch (Exception e) {
			Log.e(TAG, "Exception" , e);
		}

	}

	private synchronized final void init() {
		char[] buffer = new char[1024];

		String newName = mHeadsetName;
		int newState = mHeadsetState;

		mAudioRouteNeedsUpdate = true;
		try {
			FileReader file = new FileReader(DOCKING_STATE_PATH);
			int len = file.read(buffer, 0, 1024);
			newState = Integer.valueOf((new String(buffer, 0, len)).trim());

			//if (LOG) Log.e(TAG, "init() gogogo -----------------------------------------");
			file = new FileReader(DOCKING_NAME_PATH);
			len = file.read(buffer, 0, 1024);
			newName = new String(buffer, 0, len).trim();

		} catch (FileNotFoundException e) {
			Log.w(TAG, "This kernel does not have wired headset support");
		} catch (Exception e) {
			Log.e(TAG, "" , e);
		}

		//if (LOG) Log.e(TAG, "init() gogogo -----------------------------------------" + newName + "state = "+ newState);
		//mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
		update(newName, newState);
	}

	private synchronized final void update(String newName, int newState) {

		//if (LOG) Log.e(TAG, "Eaddy check 1.1 newState = " + newState);
		mDocSpkState = 	newState;
		updateAudioRoute();	
		sendIntent();
	}

	private synchronized final void updateAudioRoute() {

		//if (LOG) Log.e(TAG, "Eaddy check 2");
		if (mAudioRouteNeedsUpdate) {              /* Eaddy 2010/01/04 : Remove mAudioManager.isBluetoothA2dpOn */
			mAudioManager.setW88DocSpkOn(mDocSpkState == 1);
			mAudioRouteNeedsUpdate = false;
		}
	}
}
/***RK_ID:RK_DOCK. DEP_RK_ID:NULL. AUT:xieyan@gmail.com DATE:2010-07-14. END***/
