/*
 * Copyright (C) 2006 The Android Open Source Project
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

import com.android.internal.app.IBatteryStats;
import com.android.server.am.BatteryStatsService;

import android.app.ActivityManagerNative;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.FileUtils;
import android.os.IBinder;
import android.os.DropBoxManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UEventObserver;
import android.provider.Settings;
import android.util.EventLog;
import android.util.Slog;
import android.util.Log;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;


import android.os.SystemProperties;
import android.os.PowerManager;
/**
 * <p>BatteryService monitors the charging status, and charge level of the device
 * battery.  When these values change this service broadcasts the new values
 * to all {@link android.content.BroadcastReceiver IntentReceivers} that are
 * watching the {@link android.content.Intent#ACTION_BATTERY_CHANGED
 * BATTERY_CHANGED} action.</p>
 * <p>The new values are stored in the Intent data and can be retrieved by
 * calling {@link android.content.Intent#getExtra Intent.getExtra} with the
 * following keys:</p>
 * <p>&quot;scale&quot; - int, the maximum value for the charge level</p>
 * <p>&quot;level&quot; - int, charge level, from 0 through &quot;scale&quot; inclusive</p>
 * <p>&quot;status&quot; - String, the current charging status.<br />
 * <p>&quot;health&quot; - String, the current battery health.<br />
 * <p>&quot;present&quot; - boolean, true if the battery is present<br />
 * <p>&quot;icon-small&quot; - int, suggested small icon to use for this state</p>
 * <p>&quot;plugged&quot; - int, 0 if the device is not plugged in; 1 if plugged
 * into an AC power adapter; 2 if plugged in via USB.</p>
 * <p>&quot;voltage&quot; - int, current battery voltage in millivolts</p>
 * <p>&quot;temperature&quot; - int, current battery temperature in tenths of
 * a degree Centigrade</p>
 * <p>&quot;technology&quot; - String, the type of battery installed, e.g. "Li-ion"</p>
 */
class BatteryService extends Binder {
    private static final String TAG = BatteryService.class.getSimpleName();

    private static final String TAG_QZH = "RK_QZH",TAGCLASS = "BatteryService";
    private static final boolean DEBUG_QZH = true;
    private static final boolean LOCAL_LOGV = true;
    private static final boolean LOGFILE = false;
    private static boolean LOG_MBAT=true;
	
    private static boolean Flg_sendIntent = false;
   
    static final int LOG_BATTERY_LEVEL = 2722;
    static final int LOG_BATTERY_STATUS = 2723;
    static final int LOG_BATTERY_DISCHARGE_STATUS = 2730;
    
    static final int BATTERY_SCALE = 100;     // battery capacity is a percentage

    // Used locally for determining when to make a last ditch effort to log
    // discharge stats before the device dies.
    private static final int CRITICAL_BATTERY_LEVEL = 4;

    private static final int DUMP_MAX_LENGTH = 24 * 1024;
    private static final String[] DUMPSYS_ARGS = new String[] { "--checkin", "-u" };
    private static final String BATTERY_STATS_SERVICE_NAME = "batteryinfo";

    private static final String DUMPSYS_DATA_PATH = "/data/system/";

    // This should probably be exposed in the API, though it's not critical
    private static final int BATTERY_PLUGGED_NONE = 0;

    private final Context mContext;
    private final IBatteryStats mBatteryStats;

    private boolean mAcOnline;
    private boolean mUsbOnline;
    private boolean mDockingOnline;
    private int mBatteryStatus;
    private int mBatteryHealth;
    private boolean mBatteryPresent;
    private int mBatteryLevel = 100;
    private int mPreviousBatteryLevel = 0;
    private int mBatteryThreshold = 0;
    private int[] mBatteryThresholds = new int[] {31, 16, 11,6 ,1};
    private static final int BATTERY_THRESHOLD_CLOSE_WARNING = 0;
    private static final int BATTERY_THRESHOLD_WARNING = 1;
    private static final int BATTERY_THRESHOLD_EMPTY = 4;
    private int mBatteryVoltage = 0;
    private int mBatteryTemperature;
    private String mBatteryTechnology;
    private boolean mBatteryLevelCritical;

    private int mLastBatteryStatus;
    private int mLastBatteryHealth;
    private boolean mLastBatteryPresent;
    private int mLastBatteryLevel;
    private int mLastBatteryVoltage;
    private int mLastBatteryTemperature;
    private boolean mLastBatteryLevelCritical;

    private int mLowBatteryWarningLevel;
    private int mLowBatteryCloseWarningLevel;

    private int mPlugType;
    private int mLastPlugType = -1; // Extra state so we can detect first run

    private long mDischargeStartTime;
    private int mDischargeStartLevel;

    private boolean mSentLowBatteryBroadcast = false;
    private int mLastReportLevel = -1;
    private boolean isFirstVolt = true;
	private int myOrigBatteryLevel;
	private int myOrigBatteryVolt;
	private PowerManager.WakeLock mWakeLock;
	    
    public BatteryService(Context context) {
        mContext = context;
        PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "low_power_poweroff");
        mBatteryStats = BatteryStatsService.getService();

        mLowBatteryWarningLevel = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_lowBatteryWarningLevel);
        mLowBatteryCloseWarningLevel = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_lowBatteryCloseWarningLevel);

        mUEventObserver.startObserving("SUBSYSTEM=power_supply");

        // set initial status
        update();
    }

    final boolean isPowered() {
        // assume we are powered if battery state is unknown so the "stay on while plugged in" option will work.
        return (mAcOnline || mUsbOnline || mBatteryStatus == BatteryManager.BATTERY_STATUS_UNKNOWN);
    }

    final boolean isPowered(int plugTypeSet) {
        // assume we are powered if battery state is unknown so
        // the "stay on while plugged in" option will work.
        if (mBatteryStatus == BatteryManager.BATTERY_STATUS_UNKNOWN) {
            return true;
        }
        if (plugTypeSet == 0) {
            return false;
        }
        int plugTypeBit = 0;
        if (mAcOnline) {
            plugTypeBit |= BatteryManager.BATTERY_PLUGGED_AC;
        }
        if (mUsbOnline) {
            plugTypeBit |= BatteryManager.BATTERY_PLUGGED_USB;
        }
        return (plugTypeSet & plugTypeBit) != 0;
    }

    final int getPlugType() {
        return mPlugType;
    }

    private UEventObserver mUEventObserver = new UEventObserver() {
        @Override
        public void onUEvent(UEventObserver.UEvent event) {
            update();
        }
    };

    // returns battery level as a percentage
    final int getBatteryLevel() {
        return mBatteryLevel;
    }

    void systemReady() {
        // check our power situation now that it is safe to display the shutdown dialog.
        shutdownIfNoPower();
        shutdownIfOverTemp();
    }

    private final void shutdownIfNoPower() {
        // shut down gracefully if our battery is critically low and we are not powered.
        // wait until the system has booted before attempting to display the shutdown dialog.
        /*if (mBatteryLevel == 0 && !isPowered() && ActivityManagerNative.isSystemReady()) {
            Intent intent = new Intent(Intent.ACTION_REQUEST_SHUTDOWN);
            intent.putExtra(Intent.EXTRA_KEY_CONFIRM, false);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(intent);
        }*/
    }

    private final void shutdownIfOverTemp() {
        // shut down gracefully if temperature is too high (> 68.0C)
        // wait until the system has booted before attempting to display the shutdown dialog.
        if (mBatteryTemperature > 680 && ActivityManagerNative.isSystemReady()) {
            Intent intent = new Intent(Intent.ACTION_REQUEST_SHUTDOWN);
            intent.putExtra(Intent.EXTRA_KEY_CONFIRM, false);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(intent);
        }
    }
    private void pickNextBatteryLevel(int level) {
        final int N = mBatteryThresholds.length;
        for (int i=0; i<N; i++) {
            if (level >= mBatteryThresholds[i]) {
                mBatteryThreshold = i;
                break;
            }
        }
        if (mBatteryThreshold >= N) {
            mBatteryThreshold = N-1;
        }
    }
    private native void native_update();

    private synchronized final void update() {
        native_update();

        boolean logOutlier = false;
        long dischargeDuration = 0;

	    boolean needShutdown = false;
	    if (mBatteryVoltage > 4200)
		    mBatteryVoltage = 4200;
        mBatteryLevelCritical = mBatteryLevel <= CRITICAL_BATTERY_LEVEL;
        
        myOrigBatteryLevel = mBatteryLevel;
	    myOrigBatteryVolt= mBatteryVoltage;
	    mRecordBattery();
	    if(LOG_MBAT)Log.i(TAG,"mAcOnline:"+mAcOnline+" mUsbOnline:"+mUsbOnline+" mDockingOnline:"+mDockingOnline);        
        if (mAcOnline) {
            mPlugType = BatteryManager.BATTERY_PLUGGED_AC;
        } else if (mUsbOnline) {
            mPlugType = BatteryManager.BATTERY_PLUGGED_USB;
        } else if (mDockingOnline) {
        	mPlugType = BatteryManager.BATTERY_PLUGGED_DOCKING;
	    } else {
            mPlugType = BATTERY_PLUGGED_NONE;
        }
//add for filt battery level by zhuangyt++++++++++++++++
	if(isFirstVolt&&!SystemProperties.get("mBatteryVolt","0").equals("0")){
		//Log.i(TAG,"get properties ="+SystemProperties.get("mBatteryVolt","0"));
		mBatteryVoltage = Integer.parseInt(SystemProperties.get("mBatteryVolt","0"));
	}
	if(batteryIsCharging()&&!isFirstVolt){//modefied at 06.01 for bug not charging while pluged in
		if(mPlugType!=BATTERY_PLUGGED_NONE){
			mBatteryLevel=mChargingBatteryLevel(mBatteryVoltage);
			mBatteryVoltage = Capacity_to_Volt(mBatteryLevel);
		}
		else if(myRecordBattery[NUM_RECORD_BAT -1].volt%50==0&&
				myRecordBattery[NUM_RECORD_BAT -2].volt%50==0&&
				myRecordBattery[NUM_RECORD_BAT -3].volt%50==0){
			mBatteryLevel=mChargingBatteryLevel(mBatteryVoltage);
			mBatteryVoltage = Capacity_to_Volt(mBatteryLevel);
		}
		else{
			mBatteryVoltage = mFiltBatteryLevel(mBatteryVoltage);
			mBatteryLevel = Volt_to_Capacity(mBatteryVoltage);
		}
			
	}else{		
		//if((mLastBatteryStatus== BatteryManager.BATTERY_STATUS_CHARGING)||( mLastBatteryStatus== BatteryManager.BATTERY_STATUS_FULL)||(mPlugType==0&&mLastPlugType!=0&&mLastPlugType!=-1)){//modefied at 06.09			
		if((mLastBatteryStatus== BatteryManager.BATTERY_STATUS_CHARGING)||( mLastBatteryStatus== BatteryManager.BATTERY_STATUS_FULL)){
			CB_Flg = true;	
			mBatteryLevel = mLastBatteryLevel;
			mBatteryVoltage = mLastBatteryVoltage;
		}else{
			mBatteryVoltage = mFiltBatteryLevel(mBatteryVoltage);
			mBatteryLevel = Volt_to_Capacity(mBatteryVoltage);
		}		
	}
	//add by zzf for low_power_poweroff wakelock,in case sleep too soon while processing poweroff ----------start
        if ( (mBatteryLevel == 0) && !isPowered() && ActivityManagerNative.isSystemReady())
               mWakeLock.acquire(25*1000);
       //add by zzf for low_power_poweroff wakelock-----------end

	if(batteryIsFull())mBatteryStatus = BatteryManager.BATTERY_STATUS_FULL;
	SystemProperties.set("mBatteryVolt",Integer.toString(mBatteryVoltage));//store BatteryVoltage to systemproperties,in case that Android will restart
	SystemProperties.set("mBatteryLevel",Integer.toString(mBatteryLevel));//added by jinyj 20101215

	//Log.i(TAG,"set properties");
	if(LOG_MBAT)Log.i(TAG,"BatteryVolt ="+mBatteryVoltage+" Level="+mBatteryLevel+" battery status ="+mBatteryStatus
			+" myorigVolt= "+myOrigBatteryVolt+" myorigLevel="+myOrigBatteryLevel);

        
        // Let the battery stats keep track of the current level.
        try {
            mBatteryStats.setBatteryState(mBatteryStatus, mBatteryHealth,
                    mPlugType, mBatteryLevel, mBatteryTemperature,
                    mBatteryVoltage);
        } catch (RemoteException e) {
            // Should never happen.
        }
        
        shutdownIfNoPower();
        shutdownIfOverTemp();
        mBatteryLevelCritical = mBatteryLevel <= CRITICAL_BATTERY_LEVEL;
        if (mBatteryStatus != mLastBatteryStatus ||
                mBatteryHealth != mLastBatteryHealth ||
                mBatteryPresent != mLastBatteryPresent ||
                mBatteryLevel != mLastBatteryLevel ||
                mPlugType != mLastPlugType ||
                mBatteryVoltage != mLastBatteryVoltage ||
                mBatteryTemperature != mLastBatteryTemperature) {

            if (mPlugType != mLastPlugType) {
                if (mLastPlugType == BATTERY_PLUGGED_NONE) {
                    // discharging -> charging

                    // There's no value in this data unless we've discharged at least once and the
                    // battery level has changed; so don't log until it does.
                    if (mDischargeStartTime != 0 && mDischargeStartLevel != mBatteryLevel) {
                        dischargeDuration = SystemClock.elapsedRealtime() - mDischargeStartTime;
                        logOutlier = true;
                        EventLog.writeEvent(EventLogTags.BATTERY_DISCHARGE, dischargeDuration,
                                mDischargeStartLevel, mBatteryLevel);
                        // make sure we see a discharge event before logging again
                        mDischargeStartTime = 0;
                    }
                } else if (mPlugType == BATTERY_PLUGGED_NONE) {
                    // charging -> discharging or we just powered up
                    mDischargeStartTime = SystemClock.elapsedRealtime();
                    mDischargeStartLevel = mBatteryLevel;
                }
            }
            if (mBatteryStatus != mLastBatteryStatus ||
                    mBatteryHealth != mLastBatteryHealth ||
                    mBatteryPresent != mLastBatteryPresent ||
                    mPlugType != mLastPlugType) {
                EventLog.writeEvent(EventLogTags.BATTERY_STATUS,
                        mBatteryStatus, mBatteryHealth, mBatteryPresent ? 1 : 0,
                        mPlugType, mBatteryTechnology);
            }
            if (mBatteryLevel != mLastBatteryLevel ||
                    mBatteryVoltage != mLastBatteryVoltage ||
                    mBatteryTemperature != mLastBatteryTemperature) {
                EventLog.writeEvent(EventLogTags.BATTERY_LEVEL,
                        mBatteryLevel, mBatteryVoltage, mBatteryTemperature);
            }            
            if (mBatteryLevel != mLastBatteryLevel && mPlugType == BATTERY_PLUGGED_NONE) {
                // If the battery level has changed and we are on battery, update the current level.
                // This is used for discharge cycle tracking so this shouldn't be updated while the 
                // battery is charging.
                try {
                    mBatteryStats.recordCurrentLevel(mBatteryLevel);
                } catch (RemoteException e) {
                    // Should never happen.
                }
            }
            if (mBatteryLevelCritical && !mLastBatteryLevelCritical &&
                    mPlugType == BATTERY_PLUGGED_NONE) {
                // We want to make sure we log discharge cycle outliers
                // if the battery is about to die.
                dischargeDuration = SystemClock.elapsedRealtime() - mDischargeStartTime;
                logOutlier = true;
            }

            final boolean plugged = mPlugType != BATTERY_PLUGGED_NONE;
            final boolean oldPlugged = mLastPlugType != BATTERY_PLUGGED_NONE;
            final int oldThreshold = mBatteryThreshold;

            Intent statusIntent = new Intent();
            // Separate broadcast is sent for power connected / not connected
            // since the standard intent will not wake any applications and some
            // applications may want to have smart behavior based on this.
            if (mPlugType != 0 && mLastPlugType == 0) {
                statusIntent.setAction(Intent.ACTION_POWER_CONNECTED);
                statusIntent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                mContext.sendBroadcast(statusIntent);
            }
            else if (mPlugType == 0 && mLastPlugType != 0) {
                statusIntent.setAction(Intent.ACTION_POWER_DISCONNECTED);
                statusIntent.setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                mContext.sendBroadcast(statusIntent);
            }
            pickNextBatteryLevel(mBatteryLevel);
            /* The ACTION_BATTERY_LOW broadcast is sent in these situations:
             * - is just un-plugged (previously was plugged) and battery level is
             *   less than or equal to WARNING, or
             * - is not plugged and battery level falls to WARNING boundary
             *   (becomes <= mLowBatteryWarningLevel).
             */
            final boolean sendBatteryLow = ( 
                ((!plugged && mBatteryStatus != BatteryManager.BATTERY_STATUS_UNKNOWN) 
		|| (mDockingOnline && !batteryIsCharging())) 
                //&& (mBatteryLevel <= mLowBatteryWarningLevel)
		&& (mBatteryLevel< mBatteryThresholds[BATTERY_THRESHOLD_WARNING])
/* RK_ID:  RK_BatteryService 	 * DEP_RK_ID:  NULL 	 * AUT: quzhenghua@use.com.cn 	 * Date:   2010-11-16   */
                && (/*oldPlugged ||*/
			/*(mLastBatteryLevel > mLowBatteryWarningLevel) || */
		(mBatteryThreshold > oldThreshold) && (mBatteryThreshold > BATTERY_THRESHOLD_WARNING)));

            Slog.d(TAG,  "sendBatteryLow:" + sendBatteryLow + ":::"+ mLowBatteryCloseWarningLevel );
            if (sendBatteryLow) {
            if (mBatteryLevel > 0)
		    {
                mSentLowBatteryBroadcast = true;
                statusIntent.setAction(Intent.ACTION_BATTERY_LOW);
			    statusIntent.putExtra(BatteryManager.EXTRA_LEVEL, mBatteryLevel);
			    statusIntent.putExtra("oldThreshold", -1);
                mContext.sendBroadcast(statusIntent);
            }
            else
            
            {
                needShutdown = true;
                Flg_sendIntent = false;
            }
            
            } else if (mSentLowBatteryBroadcast && mLastBatteryLevel >= mLowBatteryCloseWarningLevel) {
                mSentLowBatteryBroadcast = false;
                statusIntent.setAction(Intent.ACTION_BATTERY_OKAY);
                mContext.sendBroadcast(statusIntent);
            }
 //add by zhuangyt for filt battery level++++++++++++++++++++++++++++++++          
 		if(isFirstVolt){
			isFirstVolt = false;
			sendIntent();
			Flg_sendIntent = true;
			mLastReportLevel = mBatteryLevel;
 		}else{
			if(mBatteryStatus!= mLastBatteryStatus||mPlugType != mLastPlugType){//modefied for bug 43528
				sendIntent();
				Flg_sendIntent = true;
				mLastReportLevel = mBatteryLevel;
			}else if(!batteryIsCharging()&&mBatteryLevel >= 5){
				if(mBatteryLevel <= mLastReportLevel&&(mLastReportLevel- mBatteryLevel) >= 2){				
						sendIntent();
						Flg_sendIntent = true;
						mLastReportLevel = mBatteryLevel;
				}
			}else if(!batteryIsCharging()&&mBatteryLevel <5){
				sendIntent();
				Flg_sendIntent = true;
				mLastReportLevel = mBatteryLevel;
			}else if(batteryIsCharging()){
				if(mBatteryLevel!=mLastBatteryLevel||mBatteryStatus == BatteryManager.BATTERY_STATUS_FULL){
					sendIntent();
					Flg_sendIntent = true;
					mLastReportLevel = mBatteryLevel;
				}
			}
		/*added by jinyj 20101210*/
			if ( (needShutdown == true) && (Flg_sendIntent == false))
			{
					sendIntent();
					Flg_sendIntent = true;
			}
		/*end add*/
		}
		if(Flg_sendIntent&&LOG_MBAT)Log.i(TAG,"Send intent to report battery status");
//			Log.i(TAG,"mlastBatteryLevel="+mLastBatteryLevel+"=====================>");
   //add by zhuangyt for filt battery level------------------------------------      
            // This needs to be done after sendIntent() so that we get the lastest battery stats.
            if (logOutlier && dischargeDuration != 0) {
                logOutlier(dischargeDuration);
            }

            mLastBatteryStatus = mBatteryStatus;
            mLastBatteryHealth = mBatteryHealth;
            mLastBatteryPresent = mBatteryPresent;
            mLastBatteryLevel = mBatteryLevel;
            mLastPlugType = mPlugType;
            mLastBatteryVoltage = mBatteryVoltage;
            mLastBatteryTemperature = mBatteryTemperature;
            mLastBatteryLevelCritical = mBatteryLevelCritical;
        }
    }
private int[] batteryIconMax = {4, 14, 29, 49, 69, 89, 100};
    private final int getIconIndex(int level) {
        //please refer to stat_sys_battery.xml
        for(int i=0;i<batteryIconMax.length;i++){
            if(level<=batteryIconMax[i])
                return i;	
        }
        return 0;
    }
    private final int getFilterBatteryLevel(int level) {	
        int previous_icon = getIconIndex(mPreviousBatteryLevel);	
        int new_icon = getIconIndex(level);	
        int icon_index = 0;

        //if just start, then return the new level we get	
        if(mPreviousBatteryLevel==0){		
            mPreviousBatteryLevel=level;
        }else if(new_icon==previous_icon){//if icon is the same, the we move the previous battery level		
            mPreviousBatteryLevel=level;	
        }else if(new_icon<previous_icon){//if icon is going down
            icon_index=getIconIndex(level);
            if(level<=(batteryIconMax[icon_index]-3) || (previous_icon-new_icon)>=2){//need to go down
                mPreviousBatteryLevel=level;
            }
        }else{//if icon is going up
            icon_index=getIconIndex(level)-1;
            if(level>=(batteryIconMax[icon_index]+3) || (new_icon-previous_icon)>=2){//need to go up
                mPreviousBatteryLevel=level;
            }			
        }
        return mPreviousBatteryLevel;
    }
    //Added by Coboy@20091014(End) : add to filter battery level to prevent boundary jump
    
    private class RecordBattery{//add 0318
				private int level;
				private int volt;
				private int status;
				private long ReportTime;//second
				RecordBattery(){
					level = 0;
					volt = 0;
					status = 0;
					ReportTime = 0;
				}
		    	}
    private class PhoneStatus{
		private boolean isCallOn;
		private boolean isScreenOn;
		private boolean isGpsOn;
		private boolean isVideoOn;
		private boolean isMusicOn;
		private boolean isCameraOn;
		PhoneStatus(){
			isCallOn = false;
			isScreenOn = false;
			isGpsOn = false;
			isVideoOn = false;
			isMusicOn = false;
			isCameraOn = false;
		}
    	}

	private int record_bat_index = 0;//add 0318
	private int NUM_RECORD_BAT = 3;
	private int NUM_REPEAT_REC = 3;
	private boolean Flg_NewArray = true;
	private RecordBattery [] myRecordBattery= new RecordBattery[NUM_RECORD_BAT];
	private void mRecordBattery()
	{
		int i = 0;
		if(Flg_NewArray){
			for(i=0;i<NUM_RECORD_BAT;i++)myRecordBattery[i] = new RecordBattery();
			Flg_NewArray = false;
		}
		if(record_bat_index >= NUM_RECORD_BAT)
		{
			record_bat_index = NUM_RECORD_BAT;
			for(i=1;i<record_bat_index;i++)
			{				
				myRecordBattery[i-1].level = myRecordBattery[i].level;
				myRecordBattery[i-1].volt = myRecordBattery[i].volt;
				myRecordBattery[i-1].status =  myRecordBattery[i].status;
				myRecordBattery[i-1].ReportTime =  myRecordBattery[i].ReportTime;	
			}
			myRecordBattery[record_bat_index - 1].level=mBatteryLevel;
			myRecordBattery[record_bat_index - 1].volt = mBatteryVoltage;
			myRecordBattery[record_bat_index - 1].status = mBatteryStatus;
			myRecordBattery[record_bat_index - 1].ReportTime = SystemClock.elapsedRealtime()/1000;//second
		}else{
			for(i =0;i<NUM_REPEAT_REC;i++){
				myRecordBattery[record_bat_index].level = mBatteryLevel;
				myRecordBattery[record_bat_index].volt = mBatteryVoltage;
				myRecordBattery[record_bat_index].status = mBatteryStatus;
				myRecordBattery[record_bat_index].ReportTime = SystemClock.elapsedRealtime()/1000;//second
				record_bat_index++;
			}
		}
	}
	
	private int NUM_RECORD_VOLT = 24;
	private int old_bat_voltage;
	private int NUM_REPEAT_CAP = 3;
	private int record_voltage_mV[] = new int[24];
	private int record_voltage_index=0;
	private int volt_mv_nomedian;
	private int mFiltBatteryLevel(int volt_mv)//return voltage
    	{
		int i=0, weighted_vol=0;
		int total_volt_mv=0;
		long DeltTime =0;//second
		int DeltVolt =0;
		DeltTime = myRecordBattery[NUM_RECORD_BAT-1].ReportTime - myRecordBattery[NUM_RECORD_BAT-2].ReportTime;
		DeltVolt = myRecordBattery[NUM_RECORD_BAT - 2].volt -myRecordBattery[NUM_RECORD_BAT -1].volt;
		//DeltVolt = mLastBatteryVoltage - volt_mv;//for bug 46132
		if(volt_mv >= 4200)volt_mv=4200;
		//if((DeltTime < 28||Math.abs(DeltVolt) >= 100)&&!isFirstVolt){//add 0318    if DeltTime < 30s or DeltVolt >= 100 we discard it
		if(volt_mv == 0){
			return mLastBatteryVoltage;
		}else if(volt_mv>=3500&&(DeltTime < 10||Math.abs(DeltVolt) >= 100)&&!isFirstVolt){//modefied for shutdown voltage,in case that volt goes down to much when the voltage is low 
			volt_mv= mLastBatteryVoltage;
		}
		if(record_voltage_index >= NUM_RECORD_VOLT)
		{
			record_voltage_index = NUM_RECORD_VOLT;
			weighted_vol = 10;
			weighted_vol = bat_modify_volt_weighted_index(weighted_vol);
			//Log.i(TAG," weight ="+weighted_vol+"=============================>");
			
			// Record voltage
			for(i=1; i<record_voltage_index; i++)
			{
				record_voltage_mV[(i-1)] = record_voltage_mV[i];
				total_volt_mv += record_voltage_mV[(i-1)];
				
			}
			record_voltage_mV[(record_voltage_index-1)] = volt_mv;
			volt_mv = (volt_mv*weighted_vol + old_bat_voltage*(NUM_RECORD_VOLT-weighted_vol)) / NUM_RECORD_VOLT;
			total_volt_mv += record_voltage_mV[(record_voltage_index-1)];
			old_bat_voltage = (total_volt_mv / NUM_RECORD_VOLT);
		}
		else
		{
			// Record voltage
			for(i=0; i<NUM_REPEAT_CAP; i++)
			{
		    	record_voltage_mV[record_voltage_index++] = volt_mv;
		   	 }
			for(i=0 ; i<record_voltage_index; i++)
			{
				total_volt_mv += record_voltage_mV[i];
			}
			volt_mv = (total_volt_mv / record_voltage_index); // operator
			old_bat_voltage = volt_mv;
		}
		volt_mv_nomedian = volt_mv;
		//modefied for bug 45022 20100720
		//if(volt_mv>=3600||BootingUpdateTimes<10){//if volt_mv is less than 3600mv,do not use medianfilter,in case that the battery voltage is to low while sleeping,because medianfilter cannot update real volt in time
			volt_mv = MedianFilter(volt_mv);
		//}
		
		return volt_mv;
	}
	private int MF_Record_Volt[] = {0,0,0,0,0};
	private int MF_Sort_Record_Volt[] = {0,0,0,0,0};
	private int MF_RecordIndex = 0;
	private boolean IsFirstMfRecord = true;
	private int MedianFilter(int volt)
	{
		if(IsFirstMfRecord){
			for(int i = 0;i < MF_Record_Volt.length;i++){
				MF_Record_Volt[i] = volt;
			}
			IsFirstMfRecord = false;
		}else{
			if(MF_RecordIndex == 5)MF_RecordIndex = 0;
			MF_Record_Volt[MF_RecordIndex] = volt;
			MF_RecordIndex++;
			}
		mSorter();
		Log.i("Record volt"+MF_Sort_Record_Volt[0]+" "+MF_Sort_Record_Volt[1]+" "+MF_Sort_Record_Volt[2]+" "+MF_Sort_Record_Volt[3]+" "+MF_Sort_Record_Volt[4],"==============================>");
		return MF_Sort_Record_Volt[(MF_Sort_Record_Volt.length - 1)/2];
		
	}
	private void mSorter()
	{
		for(int n = 0;n<MF_Record_Volt.length;n++)MF_Sort_Record_Volt[n] = MF_Record_Volt[n];
		int TempVolt;
		for(int i=0;i < MF_Sort_Record_Volt.length-1;i++)
		{
			for(int j = 0;j < MF_Sort_Record_Volt.length-i-1;j++)
			{
				if(MF_Sort_Record_Volt[j] >= MF_Sort_Record_Volt[j+1])
				{
					TempVolt = MF_Sort_Record_Volt[j];
					MF_Sort_Record_Volt[j] = MF_Sort_Record_Volt[j+1];
					MF_Sort_Record_Volt[j+1] = TempVolt;
				}
			}
		}
	}

	private long now_report_time = 0;
	private long prev_report_time = 0;
	private int BAT_SUSPEND_UPDATE_THRESHOLD = 30;
	private int resume_update_time_table []= {(BAT_SUSPEND_UPDATE_THRESHOLD*20), (BAT_SUSPEND_UPDATE_THRESHOLD*40), 
											(BAT_SUSPEND_UPDATE_THRESHOLD*60),(BAT_SUSPEND_UPDATE_THRESHOLD*80), 
											(BAT_SUSPEND_UPDATE_THRESHOLD*100),(BAT_SUSPEND_UPDATE_THRESHOLD*120)}; 
	private int bat_modify_volt_weighted_index(int weighted_vol)
	{
		/*long  delta_time=0;
		weighted_vol += system_busy_check(mBatteryLevel);
		if(weighted_vol > NUM_RECORD_VOLT)
			weighted_vol = NUM_RECORD_VOLT; // Max
		return weighted_vol;	*/	
		long  delta_time=0;		
		delta_time = myRecordBattery[NUM_RECORD_BAT-1].ReportTime -myRecordBattery[NUM_RECORD_BAT-2].ReportTime;
		if(delta_time > BAT_SUSPEND_UPDATE_THRESHOLD){//phone has sleeped
			if(delta_time >= resume_update_time_table[0] && delta_time <resume_update_time_table[1]) 
				weighted_vol += 2;
			else if(delta_time >=resume_update_time_table[1] && delta_time <resume_update_time_table[2])
				weighted_vol += 4;
			else if(delta_time >=resume_update_time_table[2] && delta_time <resume_update_time_table[3])
				weighted_vol += 6;
			else if(delta_time >=resume_update_time_table[3] && delta_time <resume_update_time_table[4])
				weighted_vol +=8;
			else if(delta_time >=resume_update_time_table[4] && delta_time <resume_update_time_table[5])
				weighted_vol += 10;
			else if(delta_time >=resume_update_time_table[5])
				weighted_vol += 12;
		}else{
			weighted_vol += system_busy_check(mLastBatteryLevel);
		}
		if(weighted_vol > NUM_RECORD_VOLT)
			weighted_vol = NUM_RECORD_VOLT; // Max
		else if(weighted_vol < 0)
			weighted_vol = 0;//min
	return weighted_vol;
		
	}
	
	private int  system_busy_check(int bat_level)
	{//dvt4 ret is 1
		setPhoneStatus();
		int ret=0;
		// Busy State are:
		// (1) Phone talking
		if(bat_level > 15){
			if(myPhoneStatus.isCallOn)
			{
			//Log.i(TAG," call on=============================>");
				ret -= 1;
			}
			if(myPhoneStatus.isMusicOn)
			{
			//Log.i(TAG," music  on=============================>");
				ret -=1;
			}
			// (3) Media player playing
			if(myPhoneStatus.isVideoOn)
			{
			//Log.i(TAG," video on=============================>");
				ret-=1;
			}
			// (4) Using camera
			if(myPhoneStatus.isCameraOn)
			{
			//Log.i(TAG," camera on=============================>");
				ret -=1;
			}
			if(myPhoneStatus.isScreenOn)
			{
			//Log.i(TAG," screen on=============================>");
				ret-=1;
			}
			if(myPhoneStatus.isGpsOn)
			{
			//Log.i(TAG," gps on=============================>");
				ret-=1;
			}
		}else if(bat_level <=15&&bat_level>5){//0524
			if(myPhoneStatus.isCallOn)
			{
			//Log.i(TAG," call on=============================>");
				ret -= 3;
			}
			if(myPhoneStatus.isMusicOn)
			{
			//Log.i(TAG," music  on=============================>");
				ret -=2;
			}
			// (3) Media player playing
			if(myPhoneStatus.isVideoOn)
			{
			//Log.i(TAG," video on=============================>");
				ret-=3;
			}
			// (4) Using camera
			if(myPhoneStatus.isCameraOn)
			{
			//Log.i(TAG," camera on=============================>");
				ret -=3;
			}
			if(myPhoneStatus.isScreenOn)
			{
			//Log.i(TAG," screen on=============================>");
				ret-=3;
			}
			if(myPhoneStatus.isGpsOn)
			{
			//Log.i(TAG," gps on=============================>");
				ret-=3;
			}
		}else{//level <=5
			ret +=6;//weight = 16;
		}
		
		// (5) Web surfing or donwloading

		return ret;
	}
	private PhoneStatus myPhoneStatus = new PhoneStatus();
	private  void setPhoneStatus(){
		 try {
        myPhoneStatus.isCallOn = mBatteryStats.getCallStatus();
		myPhoneStatus.isScreenOn = mBatteryStats.getScreenStatus();
		myPhoneStatus.isMusicOn = mBatteryStats.getMusicStatus();
		myPhoneStatus.isVideoOn = mBatteryStats.getVideoStatus();
		myPhoneStatus.isCameraOn = mBatteryStats.getCameraStatus();
		myPhoneStatus.isGpsOn = mBatteryStats.getGpsStatus();
                } catch (RemoteException e) {
                    // Should never happen.
                }
	}
	

/*
	private int BatTable[][] = {{0xffffffff,100},
						{4125,100},
						{4025,90},
						{3875,70},
						{3771,50},
						{3715,30},
						{3676,15},
						{3627,5},
						{3600,0},
						{0,0}};*/
	private int BatTable[][] =  {{0xffffffff,100},
						{4125,100},
						{4020,90},
						{3865,70},
						{3761,50},
						{3707,30},
						{3658,15},
						{3599,5},
						{3500,0},
						{0,0}};
	private int Volt_to_Capacity(int current_voltage)
	{
		int i;   
		for(i = 1; i < BatTable.length; ++i)
			if(current_voltage >= BatTable[i][0]) {
				int range_vol = BatTable[i-1][0] - BatTable[i][0];
				int range_percent = BatTable[i-1][1] - BatTable[i][1];
				int offset_vol = current_voltage - BatTable[i][0];
				int percent = BatTable[i][1] + range_percent * offset_vol /range_vol;
				return percent;
			}		
	return 0;	
	}
/*
	private int BatTable_CTV[][] = {{0xffffffff,100},
						{4200,100},
						{4025,90},
						{3875,70},
						{3771,50},
						{3715,30},
						{3676,15},
						{3627,5},
						{3600,0},
						{0,0}};*/
	private int BatTable_CTV[][] = {{0xffffffff,100},
						{4200,100},
						{4020,90},
						{3865,70},
						{3761,50},
						{3707,30},
						{3658,15},
						{3599,5},
						{3500,0},
						{0,0}};
	private int Capacity_to_Volt(int current_level){
		int i;   
		int current_volt;
		for(i = 1; i < BatTable_CTV.length; ++i)
			if(current_level >= BatTable_CTV[i][1]) {
				int range_vol = BatTable_CTV[i-1][0] - BatTable_CTV[i][0];
				int range_percent = BatTable_CTV[i-1][1] - BatTable_CTV[i][1];
				int offset_percent = current_level - BatTable_CTV[i][1];
				if(range_percent== 0)current_volt = BatTable_CTV[i][0];
				else	current_volt = BatTable_CTV[i][0] + range_vol* offset_percent/range_percent;
				return current_volt;
			}		
	return 100;	

	}


	private boolean CB_Flg= true;
	private long CB_StartTime,CB_StopTime;
	private int STEP_TIME_USB=3;//min
	private int STEP_TIME_AC = 1;//min
	private int mChargingBatteryLevel(int volt_mv)//return level
	{
		long DeltTime=0;
		int DeltVolt=0;
		int batterylevel=0;
		if(volt_mv>4200)volt_mv=4200;
		ClearFiltArray();	
		if(CB_Flg){
			CB_StartTime = CB_StopTime = SystemClock.elapsedRealtime()/1000/60;//min
			CB_Flg= false;
			return mLastBatteryLevel;
		}
		DeltVolt=volt_mv-mLastBatteryVoltage;
		CB_StopTime = SystemClock.elapsedRealtime()/1000/60;
		DeltTime =CB_StopTime-CB_StartTime;
		if(LOG_MBAT)Log.i(TAG,"DeltVolt="+DeltVolt+" DeltTime="+DeltTime);
		if(mPlugType == BatteryManager.BATTERY_PLUGGED_AC){//ac charging
			if(DeltVolt>0){
				if(DeltTime>= STEP_TIME_AC){				
					batterylevel= mLastBatteryLevel+(int)DeltTime/STEP_TIME_AC;
					if(batterylevel>mBatteryLevel)
						batterylevel=mBatteryLevel;
					if(batterylevel>100)
						batterylevel=100;
					CB_StartTime = SystemClock.elapsedRealtime()/1000/60;
				}else{
					batterylevel= mLastBatteryLevel;
				}
			}else{
				batterylevel=mLastBatteryLevel;
				CB_StartTime = SystemClock.elapsedRealtime()/1000/60;
			}
		}else{//usb or docking charging
			if(DeltVolt>0&&DeltVolt<=100){
				if(DeltTime>= STEP_TIME_USB){				
					batterylevel= mLastBatteryLevel+(int)DeltTime/STEP_TIME_USB;
					if(batterylevel>=100)
						batterylevel=100;
					CB_StartTime = SystemClock.elapsedRealtime()/1000/60;
				}else{
					batterylevel= mLastBatteryLevel;
				}
			}else if(DeltVolt>100){
				batterylevel=mLastBatteryLevel+1;
				CB_StartTime = SystemClock.elapsedRealtime()/1000/60;
			}else{
				batterylevel=mLastBatteryLevel;
				CB_StartTime = SystemClock.elapsedRealtime()/1000/60;//add at 20100812
			}
		}
		return batterylevel;
	
	}
	private void ClearFiltArray()
	{
		record_voltage_index = 0;
		for(int i=0;i<record_voltage_mV.length;i++){
			record_voltage_mV[i] = 0;
		}
		IsFirstMfRecord = true;
		MF_RecordIndex = 0;	
	}
	
//add by zhuangyt----------------------------------
	
    private final void sendIntent() {
        //  Pack up the values and broadcast them to everyone
        Intent intent = new Intent(Intent.ACTION_BATTERY_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                | Intent.FLAG_RECEIVER_REPLACE_PENDING);
        	
        /*try {
            mBatteryStats.setOnBattery(mPlugType == BATTERY_PLUGGED_NONE,mBatteryStatus, mBatteryLevel);
        } catch (RemoteException e) {
            // Should never happen.
        }*/

        //Modified by Coboy@20091014(Start) : add to filter battery level to prevent boundary jump
        int mFilterBatteryLevel = getFilterBatteryLevel(mBatteryLevel);
       // int icon = getIcon(mBatteryLevel);
        int icon = getIcon(mFilterBatteryLevel);
        intent.putExtra(BatteryManager.EXTRA_STATUS, mBatteryStatus);
        intent.putExtra(BatteryManager.EXTRA_HEALTH, mBatteryHealth);
        intent.putExtra(BatteryManager.EXTRA_PRESENT, mBatteryPresent);
        intent.putExtra(BatteryManager.EXTRA_LEVEL, mFilterBatteryLevel);
        intent.putExtra(BatteryManager.EXTRA_SCALE, BATTERY_SCALE);
        intent.putExtra(BatteryManager.EXTRA_ICON_SMALL, icon);
        intent.putExtra(BatteryManager.EXTRA_PLUGGED, mPlugType);
        intent.putExtra(BatteryManager.EXTRA_VOLTAGE, mBatteryVoltage);
        intent.putExtra(BatteryManager.EXTRA_TEMPERATURE, mBatteryTemperature);
        intent.putExtra(BatteryManager.EXTRA_TECHNOLOGY, mBatteryTechnology);

        if (true) {
            Slog.d(TAG, "updateBattery level:" + mBatteryLevel + "filterBattery level:" + mFilterBatteryLevel +
                    " scale:" + BATTERY_SCALE + " status:" + mBatteryStatus +
                    " health:" + mBatteryHealth +  " present:" + mBatteryPresent +
                    " voltage: " + mBatteryVoltage +
                    " temperature: " + mBatteryTemperature +
                    " technology: " + mBatteryTechnology +
                    " AC powered:" + mAcOnline + " USB powered:" + mUsbOnline +
                    " icon:" + icon );
        }

        ActivityManagerNative.broadcastStickyIntent(intent, null);
    }

    private final void logBatteryStats() {
        IBinder batteryInfoService = ServiceManager.getService(BATTERY_STATS_SERVICE_NAME);
        if (batteryInfoService == null) return;

        DropBoxManager db = (DropBoxManager) mContext.getSystemService(Context.DROPBOX_SERVICE);
        if (db == null || !db.isTagEnabled("BATTERY_DISCHARGE_INFO")) return;

        File dumpFile = null;
        FileOutputStream dumpStream = null;
        try {
            // dump the service to a file
            dumpFile = new File(DUMPSYS_DATA_PATH + BATTERY_STATS_SERVICE_NAME + ".dump");
            dumpStream = new FileOutputStream(dumpFile);
            batteryInfoService.dump(dumpStream.getFD(), DUMPSYS_ARGS);
            FileUtils.sync(dumpStream);

            // add dump file to drop box
            db.addFile("BATTERY_DISCHARGE_INFO", dumpFile, DropBoxManager.IS_TEXT);
        } catch (RemoteException e) {
            Slog.e(TAG, "failed to dump battery service", e);
        } catch (IOException e) {
            Slog.e(TAG, "failed to write dumpsys file", e);
        } finally {
            // make sure we clean up
            if (dumpStream != null) {
                try {
                    dumpStream.close();
                } catch (IOException e) {
                    Slog.e(TAG, "failed to close dumpsys output stream");
                }
            }
            if (dumpFile != null && !dumpFile.delete()) {
                Slog.e(TAG, "failed to delete temporary dumpsys file: "
                        + dumpFile.getAbsolutePath());
            }
        }
    }

    private final void logOutlier(long duration) {
        ContentResolver cr = mContext.getContentResolver();
        String dischargeThresholdString = Settings.Secure.getString(cr,
                Settings.Secure.BATTERY_DISCHARGE_THRESHOLD);
        String durationThresholdString = Settings.Secure.getString(cr,
                Settings.Secure.BATTERY_DISCHARGE_DURATION_THRESHOLD);

        if (dischargeThresholdString != null && durationThresholdString != null) {
            try {
                long durationThreshold = Long.parseLong(durationThresholdString);
                int dischargeThreshold = Integer.parseInt(dischargeThresholdString);
                if (duration <= durationThreshold && 
                        mDischargeStartLevel - mBatteryLevel >= dischargeThreshold) {
                    // If the discharge cycle is bad enough we want to know about it.
                    logBatteryStats();
                }
                if (LOCAL_LOGV) Log.v(TAG, "duration threshold: " + durationThreshold + 
                        " discharge threshold: " + dischargeThreshold);
                if (LOCAL_LOGV) Log.v(TAG, "duration: " + duration + " discharge: " + 
                        (mDischargeStartLevel - mBatteryLevel));
            } catch (NumberFormatException e) {
                Log.e(TAG, "Invalid DischargeThresholds GService string: " + 
                        durationThresholdString + " or " + dischargeThresholdString);
                return;
            }
        }
    }

private final int getIcon(int level) {
        if (mBatteryStatus == BatteryManager.BATTERY_STATUS_CHARGING) {
            return com.android.internal.R.drawable.stat_sys_battery_charge;
        } else if (mBatteryStatus == BatteryManager.BATTERY_STATUS_DISCHARGING ||
                mBatteryStatus == BatteryManager.BATTERY_STATUS_NOT_CHARGING ||
                mBatteryStatus == BatteryManager.BATTERY_STATUS_FULL) {
            return com.android.internal.R.drawable.stat_sys_battery;
        } else {
            return com.android.internal.R.drawable.stat_sys_battery_unknown;
        }
    }
public boolean batteryIsCharging()
	 {
                 return (mBatteryStatus == BatteryManager.BATTERY_STATUS_CHARGING)||( mBatteryStatus == BatteryManager.BATTERY_STATUS_FULL);
	
	 }
	long mStartTime,mStopTime;
	boolean mFlg = true;
public boolean batteryIsFull()
	{
		if(mBatteryStatus == BatteryManager.BATTERY_STATUS_FULL&&mPlugType!=BATTERY_PLUGGED_NONE){//2010 3.29
			mBatteryLevel = 100;
			mBatteryVoltage = 4200;
			return true;
		}
		else if(mBatteryLevel >= 99&&batteryIsCharging()){
			if(mFlg){
				mStartTime = SystemClock.elapsedRealtime()/1000/60;
				mFlg = false;
			}
			mStopTime = SystemClock.elapsedRealtime()/1000/60;
			if((mStopTime - mStartTime) >= 40){
				Log.i(TAG,"set Battery Status to Full======================>");
				mBatteryLevel = 100;
				mBatteryVoltage = 4200;
				return true;	
			}else{
				mBatteryLevel = 99;
				mBatteryVoltage = Capacity_to_Volt(mBatteryLevel);
				return false;
			}
		}else if(mBatteryLevel!=100&&!batteryIsCharging()){
			mFlg = true;
			return false;
		}else
			return false;
	}
		

	
	
    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {

            pw.println("Permission Denial: can't dump Battery service from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }

        synchronized (this) {
            pw.println("Current Battery Service state:");
            pw.println("  AC powered: " + mAcOnline);
            pw.println("  USB powered: " + mUsbOnline);
            pw.println("  status: " + mBatteryStatus);
            pw.println("  health: " + mBatteryHealth);
            pw.println("  present: " + mBatteryPresent);
            pw.println("  level: " + mBatteryLevel);
            pw.println("  scale: " + BATTERY_SCALE);
            pw.println("  voltage:" + mBatteryVoltage);
            pw.println("  temperature: " + mBatteryTemperature);
            pw.println("  technology: " + mBatteryTechnology);
        }
    }
}
