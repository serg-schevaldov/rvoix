package com.voix;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.telephony.TelephonyManager;
import android.text.format.DateFormat;

public class RVoixSrv extends Service {
    
    static {
		System.loadLibrary("lame");
		System.loadLibrary("voix");
	}
	
    public static native int startRecord(String file, int bu, int bd);
	public static native void stopRecord(int encoding_mode);
	public static native void answerCall(String file);

	// NB: The first two are also hard-coded in voix.c (passed in encoding_mode to startRecord) 
	public static final int MODE_RECORD_WAV = 0;
	public static final int MODE_RECORD_MP3 = 1;
	
	private int srv_mode = MODE_RECORD_WAV;
	
	// NB: These are also hard-coded in prefs.xml through @arrays [In/Out]CallValues
	public static final int OUTGOING_REC_NONE = 0;
	public static final int OUTGOING_REC_CONT = 1;
	public static final int OUTGOING_REC_NCONT = 2;
	public static final int OUTGOING_REC_ALL = 3;
	public static final int OUTGOING_REC_ASK = 4;
	public static final int OUTGOING_REC_ASK_INCALL = 5;
	public static final int INCOMING_ACT_NONE = 0;
	public static final int INCOMING_ACT_REC = 1;
	public static final int INCOMING_ACT_HUP = 2;
	public static final int INCOMING_ACT_IGN = 3;
	public static final int INCOMING_ACT_ASK = 4;
	public static final int INCOMING_ACT_ASK_INCALL = 5;
	public static final int INCOMING_ACT_AUTOANSWER = 6;
	
	private int unk_proc = INCOMING_ACT_REC;
	private int cont_proc = INCOMING_ACT_REC;
	private int ncont_proc = INCOMING_ACT_REC;
	private int out_proc = OUTGOING_REC_ALL;

	// NB: These are also hard-coded in prefs.xml
	public static final int BMODE_NONE = 0;
	public static final int BMODE_BLIST = 1;
	public static final int BMODE_WLIST_HUP = 2;
	public static final int BMODE_WLIST_IGN = 3;
	public static final int BMODE_WLIST_AA = 4;
		
	private int bmode = BMODE_NONE;
	
	private static final int NOTIFY_ID = 1;
	private int boost_up = 0;
	private int boost_dn = 0;
	private long min_out_time = 0;
	private long start_time = 0;
	private boolean min_out_confirm = true;
	private int wait_confirm_result = 0; 
	private boolean logging = false;
	private boolean disable_notifications = false;
	
	// If this is a foreground service, we also provide a status bar icon.
	// Without it, the service seems to be killed for no reason after a lengthy period.
	private boolean foreground = false;
	
	private final CallReceiver cr = new CallReceiver();
	private final OutNumReceiver onr = new OutNumReceiver();
	private final Contix ctx = Contix.getContix();	
	private static Telephony telephony = null;
	private static PowerManager.WakeLock wakeLock = null;
	private static AudioManager aman = null;
	private static ServiceLogger log = null;
	
	@Override
   	public void onCreate() {
			Log.dbg("onCreate(): entry");
			super.onCreate();
			
			mNM = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
			try {
			    mStartForeground = getClass().getMethod("startForeground",	new Class[] { int.class, Notification.class});
			    mStopForeground = getClass().getMethod("stopForeground", new Class[] { boolean.class});
			} catch (NoSuchMethodException e) {
	        // Running on an older platform.
			    Log.dbg("old platform detected");
				mStartForeground = mStopForeground = null;
			}
			    
			telephony = new Telephony();
			this.registerReceiver(cr, new IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED));
			this.registerReceiver(onr, new IntentFilter(Intent.ACTION_NEW_OUTGOING_CALL));

			PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, this.getClass().getName());
            wakeLock.setReferenceCounted(false);

            ctx.setContentResolver(getContentResolver());
            aman = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            // is_tattoo = Build.MODEL.equals("HTC Tattoo");
            Log.dbg("onCreate(): exit");
	}
	
	@Override
    public void onDestroy() {
			Log.msg("onDestroy()");
        	this.unregisterReceiver(cr);
        	this.unregisterReceiver(onr);
        	if(foreground) stopForegroundCompat(NOTIFY_ID);
        	if(wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        	if(log != null) log.close();
        	super.onDestroy();
    }
		
	private boolean first_start = true;
	private static RVoixSrv ziz;	// dirty hack to simplify jni stuff

	@Override
    public void onStart(Intent intent, int startId) {
			Log.dbg("onStart(): entry");
			if(foreground) stopForegroundCompat(NOTIFY_ID);
			ziz = this;
			getPrefs(); 
			cr.setup();
			onr.setup();
			first_start = false;
			if(foreground) goForeground();
			if(logging) log = new ServiceLogger();
			else log = null;
			Log.dbg("onStart(): exit");
	}

	public void goForeground() {
			String s = getString(first_start ? R.string.SStarted : R.string.SReStarted);
			Notification notification = new Notification(disable_notifications ? 0 : R.drawable.stat_sample, s,	
					System.currentTimeMillis());
			Intent notificationIntent = new Intent(this, Secretaire.class);
			notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
			Context context = getApplicationContext();
			notification.setLatestEventInfo(context, "rVoix", s, contentIntent);
			startForegroundCompat(NOTIFY_ID,notification);
	}
	
	private enum CType { UNKNOWN, OUTGOING_CONT, OUTGOING_NCONT, 
					INCOMING_NONUMBER, INCOMING_CONT, INCOMING_NCONT  }

	private CType call_type = CType.UNKNOWN;
	private int call_proc = INCOMING_ACT_NONE;
	
	// phone number, or contact name (if present)
	private String out_number = null;
	private String inc_number = null;
	
	public static boolean recording  = false;
	public static boolean encoding = false;
	
	// number of recording for *INCALL modes
	private int chunk = 0;
	private static boolean ask_in_progress = false;
	private static boolean ask_incall_started = false;	
	public	static boolean need_ask_activity = true;
	public 	static boolean need_broadcast_record = false;
	
	public static boolean is_tattoo = false;
	
	private void set_defaults() {
		call_type = CType.UNKNOWN;
   		call_proc = INCOMING_ACT_NONE;
   		out_number = null;
   		inc_number = null;
   		chunk = 0;
   		ask_in_progress = false;
   		ask_incall_started = false;
	}

	public void startAskActivity(Context context, boolean out, boolean incall) {
		if(!need_ask_activity) return;
		Intent intie = new Intent();
 	   	intie.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
 	   	intie.setClassName("com.voix", "com.voix.AskOnCall");
 	   	intie.putExtra("outgoing", out);
 	   	intie.putExtra("incall", incall);
 	   	intie.putExtra("phone", out ? out_number : inc_number);
 	   	Log.msg("asking whether to record " + (out ? out_number : inc_number));
        Log.dbg("starting AskOnCall activity");
 	   	context.startActivity(intie);
	}
	
	// Interaction with AskActivity: we call it
    private static final RemoteCallbackList<IRVoixCallback> cBacks = new RemoteCallbackList<IRVoixCallback>();

    public enum CBack { ENC_CMPL, REC_CMPL, DONE, REC_START  }
    
    private static void broadcastActivities(CBack cb) {
        final int k = cBacks.beginBroadcast();
            Log.dbg("callAskActivity(): entry");
        	for (int i=0; i < k; i++) {
            	try {
            		switch(cb) {
            			case ENC_CMPL:	cBacks.getBroadcastItem(i).encodingComplete(); break;
            			case REC_CMPL:	cBacks.getBroadcastItem(i).recordingComplete(); break;
            			case REC_START:	cBacks.getBroadcastItem(i).recordingStarted(); break;
            			case DONE:	cBacks.getBroadcastItem(i).goodbye(); break;	
            		}
            	} catch (RemoteException e) {
                    Log.err("Remote exception!");
            		e.printStackTrace(); 
            		break;
            	}
            }
        	cBacks.finishBroadcast();
        	Log.dbg("callAskActivity(): exit");
    }

    private static void broadcastAboutToRecord(boolean is_outgoing, boolean is_incall, String p) {
        	if(!need_broadcast_record) return;
    		final int k = cBacks.beginBroadcast();
            Log.dbg("callAskActivity(): entry");
        	for (int i=0; i < k; i++) {
            	try {
            		cBacks.getBroadcastItem(i).recordingAboutToStart(is_outgoing, is_incall, p);
            	} catch (RemoteException e) {
                    Log.err("Remote exception!");
            		e.printStackTrace(); 
            		break;
            	}
            }
        	cBacks.finishBroadcast();
        	Log.dbg("callAskActivity(): exit");
    }
    
	// Interaction with AskActivity: it calls us

	@Override
	public IBinder onBind(Intent arg0) {
		return binder;
	}
   
    public final IRVoix.Stub binder = new IRVoix.Stub() {
	    public void force_record(boolean rec) { 
	    	if(call_type == CType.UNKNOWN) { // must be already on-hook
	    		set_defaults();
	    		return;
	    	}
	    	if(rec) {
	    		if(call_type == CType.OUTGOING_CONT 
	    			|| call_type == CType.OUTGOING_NCONT) start_time = System.currentTimeMillis();
	    		cr.lastFile = makeFilename();
		    	if(startRecord(cr.lastFile, boost_up, boost_dn) != 0) {
		    		if(log != null) log.write("Failed to start recording at user request");
		    		Log.err("startRecord failed"); return;
		    	}
		    	recording = true;
		    	broadcastActivities(CBack.REC_START);
		    	Log.msg("started recording (at user request) to " + cr.lastFile);
		    	if(log != null) log.write("Started recording to "+cr.lastFile+" [user request]");
		    	if(!wakeLock.isHeld()) wakeLock.acquire();
	    	} else {
	    		Log.msg("skipped recording (at user request)");
	    		if(log != null) log.write("Skipped recording [user request]");
	    		set_defaults();
	    	}
	    	ask_in_progress = false;
	    }
	    public void call_telephony(String s) {
	    	Log.dbg("trying to invoke telephony interface: " + s);
	    	telephony.invoke(s);
	    }
	    public boolean is_recording() {
	    	return recording;
	    }
	    public boolean start_rec() {
	    	if(recording) {
	    		Log.err("start_rec() called while recording!");
	    		if(log != null) log.write("Already recording, start command ignored");
	    		return false;
	    	}
	    	Log.dbg("start_rec(): entry");
	    	String ss = makeFilename();
	    	if(startRecord(ss, boost_up, boost_dn) != 0) {
	    		if(log != null) log.write("Failed to start incall recording at user request");
	    		Log.err("startRecord failed"); return false;
	    	}
	    	recording = true;
	    	broadcastActivities(CBack.REC_START);
	    	Log.msg("started recording (at user request) to " + ss);
	    	if(log != null) log.write("Started incall recording to "+ss+" [user request]");
	    	if(!wakeLock.isHeld()) wakeLock.acquire();
   			Log.dbg("start_rec(): exit");
   			return true;
	    }
	    public boolean stop_rec() {
	    	if(!recording) {
	    		Log.err("stop_rec() called while not recording!");
	    		if(log != null) log.write("Not recording, stop command ignored");
	    		return false;
	    	}
	    	Log.dbg("stop_rec(): entry");
	    	stopRecord(srv_mode); chunk++;
	    	if(log != null) log.write("Stopped incall recording [user request]");
	    	Log.dbg("stop_rec(): exit");
	    	return true;
	    }
	    public void wait_confirm(int confirm) {
	    	Log.msg("wait_confirm: " + confirm);
	    	wait_confirm_result = confirm;
	    }
	    public void registerCallback(IRVoixCallback cb)   { if(cb != null) cBacks.register(cb); }
        public void unregisterCallback(IRVoixCallback cb) { if(cb != null) cBacks.unregister(cb); }
	};
	
	// Interaction with native code: it calls us. 
	public static void onCallAnswered() {
		telephony.invoke("endCall");
	}
	public static void onRecordingComplete() {
		Log.dbg("onRecordingComplete()");
		if(log != null) log.write("Recording complete");
		if(wakeLock.isHeld()) wakeLock.release();
		broadcastActivities(CBack.REC_CMPL);	// re-route to our activity
		recording = false;
	}

	public static void onEncodingComplete() {
		Log.dbg("onEncodingComplete()");
		if(log != null) log.write("Encoding complete");
		if(wakeLock.isHeld()) wakeLock.release();
		broadcastActivities(CBack.ENC_CMPL);	// re-route to our activity
		encoding = false;
		ziz.cleanUp();
		java.lang.System.gc();
		if(is_tattoo && !ask_incall_started) {
			Intent intent = new Intent().setClassName("com.voix", "com.voix.TattooHack");
			if(ziz.startService(intent)== null) {
				Log.err("failed to start TattooHack service");
			} else { 
	        	Log.msg("started TattooHack service, now KILLING MYSELF");
	        	ziz.stopSelf();
	        	android.os.Process.killProcess(android.os.Process.myPid());
	        }
		}
	}
	
	// NB: chunk # is updated in stop_rec()
	private String makeFilename() {
		Log.dbg("makeFilename(): entry");
		String file = DateFormat.format("MM-dd-kkmm", new Date()).toString();
   		if(call_type == CType.OUTGOING_CONT || call_type == CType.OUTGOING_NCONT) {
   			if(out_number != null) {
   				String s = new String(out_number);
   				if(s.contains("*")) s = out_number.replace('*', '#');
   				file = "O-" + file + (s.charAt(0) == '+' ? "" : "-") + s;
   			}
   		} else if(inc_number != null){
   			String s = new String(inc_number);
			if(s.contains("*")) s = inc_number.replace('*', '#'); 
   			file = "I-" + file + (s.charAt(0) == '+' ? "" : "-") + s;
   		}
		if(ask_incall_started) file = file + "-" + chunk;
		File f = new File("/sdcard/voix/" + file + ((srv_mode == MODE_RECORD_WAV) ? ".wav" : ".mp3"));
		if(f.exists()) {
			for(int i = 0; i < 99; i++) {
				f = new File("/sdcard/voix/" + file + "-" + i + ((srv_mode == MODE_RECORD_WAV) ? ".wav" : ".mp3"));
				if(!f.exists()) {
					file = file + "-" + i;
					break;
				}
			}
		}
		Log.dbg("makeFilename(): exit");
		return file;
	}
	
	private String report = null;

	private ArrayList <String> get_array(int mode, char type) {
		FContentList fc = new FContentList(FContentList.LIST_FILES[mode],this);
		fc.read();
		return fc.get_array(type);
	}
	
	/////////////////////////////////////////////////////
	////////// Receiver of outgoing calls
	/////////////////////////////////////////////////////
	
	private class OutNumReceiver extends BroadcastReceiver {
		
		private ArrayList <String> orlist = null;
		private ArrayList <String> oalist = null;
		private ArrayList <String> onlist = null;
		private ArrayList <String> oilist = null;

		public void setup() {
			orlist = get_array(FContentList.OEMODE,FContentList.TYPE_R);
			oalist = get_array(FContentList.OEMODE,FContentList.TYPE_A);
			onlist = get_array(FContentList.OEMODE,FContentList.TYPE_N);
			oilist = get_array(FContentList.OEMODE,FContentList.TYPE_I);
		}

		private void check_overrides(String s) {
			
			if(orlist == null) orlist = get_array(FContentList.OEMODE,FContentList.TYPE_R);
			if(oalist == null) oalist = get_array(FContentList.OEMODE,FContentList.TYPE_A);
			if(onlist == null) onlist = get_array(FContentList.OEMODE,FContentList.TYPE_N);
			if(oilist == null) oilist = get_array(FContentList.OEMODE,FContentList.TYPE_I);
			
			if(orlist.contains(s)) {	// overrides
			   call_proc = OUTGOING_REC_ALL;
			   Log.dbg("override: phone found in always record list");
			   if(report != null) report += " [in always record list]";
			} else if(onlist.contains(s)) { 
			   call_proc = OUTGOING_REC_NONE;
			   Log.dbg("override: phone found in always skip list");
			   if(report != null) report += " [in always skip list]";
			} else if(oalist.contains(s)) {
			   call_proc = OUTGOING_REC_ASK;
			   Log.dbg("override: phone found in always ask list");
			   if(report != null) report += " [in always ask list]";
			} else if(oilist.contains(s)) {
			   call_proc = OUTGOING_REC_ASK_INCALL;
			   Log.dbg("override: phone found in always incall list");
			   if(report != null) report += " [in always incall list]";
			}
		}
		
		@Override
		public void onReceive(Context context, Intent intent) {
			   String s = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER);
			   Log.dbg("onReceive(): " + s);
			   if(!logging) { 
				   report = null;
			   } else {
				   if(log == null || !log.can_write()) log = new ServiceLogger();
				   if(!log.can_write()) report = null;
				   else if(s != null) report = "Outgoing call to " + s;
				   else report = "Outgoing call to [unknown]";
			   }
			   call_proc = out_proc;
			   if(s != null) {
				   String name = ctx.findInContacts(s);
				   if(name == null) {
					   call_type = CType.OUTGOING_NCONT;
					   out_number = new String(s);
					   if(out_proc == OUTGOING_REC_NCONT) call_proc = OUTGOING_REC_ALL;
				   } else {
					   call_type = CType.OUTGOING_CONT;
					   out_number = new String(name);
					   if(out_proc == OUTGOING_REC_CONT) call_proc = OUTGOING_REC_ALL;
					   if(report != null) report += (" [" + out_number +"]" );
				   }
				   check_overrides(s);
			   } else {	// is it possible?
				   out_number = "unknown";
				   call_type = CType.OUTGOING_NCONT;
			   }
			   Log.msg("OutNumReceiver: new outgoing call " + out_number);
		}
	}
		
	private void shutup() {
		Timer t = new Timer();
		final int cur_mode = aman.getRingerMode();
		aman.setRingerMode(AudioManager.RINGER_MODE_SILENT);
		TimerTask task = new TimerTask() {
			@Override
			public void run() {
				telephony.invoke("silenceRinger");
				aman.setRingerMode(cur_mode);
			}
		};
		t.schedule(task, 1000);
		telephony.invoke("silenceRinger");
	}
	
	/////////////////////////////////////////////////////
	//////// Receiver of phone state changes
	/////////////////////////////////////////////////////
	private class CallReceiver extends BroadcastReceiver {
		
		private String lastFile = null;
		
		private String cn_file = null;
		private String un_file = null;
		private	String nc_file = null;
		private	String ba_file = null;
		private	String wa_file = null;
		
		private ArrayList <String> wlist = null; 
		private ArrayList <String> bmlist = null;
		private ArrayList <String> bhlist = null;
		private ArrayList <String> balist = null;
		private ArrayList <String> irlist = null;
		private ArrayList <String> ialist = null;
		private ArrayList <String> inlist = null;
		private ArrayList <String> iilist = null;
		
		public void setup() {
  			SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
			wlist  = get_array(FContentList.WMODE,FContentList.TYPE_NONE); 
			bhlist = get_array(FContentList.BMODE,FContentList.TYPE_H);
			bmlist = get_array(FContentList.BMODE,FContentList.TYPE_M);
			balist = get_array(FContentList.BMODE,FContentList.TYPE_Q);
			irlist = get_array(FContentList.IEMODE,FContentList.TYPE_R);
			ialist = get_array(FContentList.IEMODE,FContentList.TYPE_A);
			inlist = get_array(FContentList.IEMODE,FContentList.TYPE_N);
			iilist = get_array(FContentList.IEMODE,FContentList.TYPE_I);
			nc_file = settings.getString("nc_file", null);
			cn_file = settings.getString("cn_file", null);
			un_file = settings.getString("un_file", null);
			ba_file = settings.getString("ba_file", null);
			wa_file = settings.getString("wa_file", null);
		}

		@Override
		public void onReceive(Context context, Intent intent) {
    	   	   String s = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
               Log.msg("onReceive(), state = " + s);
               
               if(s.equals(TelephonyManager.EXTRA_STATE_RINGING)) {
           	   	
            	    s = intent.getStringExtra("incoming_number");

            	    if(recording) {
            	    	if(log != null) log.write("Incoming call from " + s + " while recording, will be merged to " +lastFile+" if accepted");
            	    	return;
            	    }
            	    if(!logging) { 
            	    	report = null;
            	    } else {
            	    	if(log == null || !log.can_write()) log = new ServiceLogger();
            	    	if(!log.can_write()) report = null;
            	    	else if(s != null) report = "Incoming call from "+s;
            	    	else report = "Incoming call from <unknown>";
            	    }

            	    switch(bmode) {
            	   		case BMODE_BLIST:
            	   			if(bhlist == null) bhlist = get_array(FContentList.BMODE,FContentList.TYPE_H);
                			if(bmlist == null) bmlist = get_array(FContentList.BMODE,FContentList.TYPE_M);
                			if(balist == null) bmlist = get_array(FContentList.BMODE,FContentList.TYPE_Q);
            	   			if(s != null) {
            	   				if(bhlist.contains(s)) {
            	   				//	telephony.invoke("silenceRinger");
            	   					shutup();
            	   					telephony.invoke("endCall");
            	   					Log.msg("Hanged up blacklisted number " +s);
            	   					if(report != null) log.write(report + ", blacklisted, hanged up");
            	   					return;
            	   				} 
            	   				if(bmlist.contains(s)) {
            	   					shutup(); //telephony.invoke("silenceRinger");
            	   					Log.msg("Muted blacklisted number " +s);
            	   					if(report != null) log.write(report + ", blacklisted, muted");
            	   					return;
            	   				}
            	   				if(balist.contains(s)) {
   	                	   			if(ba_file == null) {
   	                	   				SharedPreferences sts = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
   	                	   				ba_file = sts.getString("ba_file", null);
   	                	   			}
   	                	   			auto_answer(ba_file);
            	   					return;
            	   				}
            	   				Log.msg(s + " not blacklisted, continue");
            	   			}
            	   			break;
            	   		case BMODE_WLIST_HUP:
                    	    if(wlist == null) wlist  = get_array(FContentList.WMODE,FContentList.TYPE_NONE);
            	   			if(wlist.size()==0) break;
            	   			if(s == null || !wlist.contains(s)) {
            	   				//	telephony.invoke("silenceRinger");
            	   				shutup();
            	   				telephony.invoke("endCall");
            	   				Log.msg("Hanged up number " +s +": not in white list");
            	   				if(report != null) {
            	   					if(s != null) log.write(report + ", not in white list, hanged up");
            	   					else log.write(report + ", hanged up [in white list mode]");
            	   				}
            	   				return;	
            	   			}
            	   			break;
            	   		case BMODE_WLIST_IGN:
                    	    if(wlist == null) wlist  = get_array(FContentList.WMODE,FContentList.TYPE_NONE);
            	   			if(wlist.size()==0) break;
            	   			if(s == null || !wlist.contains(s)) {
            	   				shutup(); //telephony.invoke("silenceRinger");
            	   				Log.msg("Muted number " +s +": not in white list");
            	   				if(report != null) {
            	   					if(s != null) log.write(report + ", not in white list, muted");
            	   					else log.write(report + ", muted [in white list mode]");
            	   				}
            	   				return;	
            	   			}
            	   			break;
            	   		case BMODE_WLIST_AA:
                    	    if(wlist == null) wlist  = get_array(FContentList.WMODE,FContentList.TYPE_NONE);
            	   			if(wlist.size()==0) break;
            	   			if(s == null || !wlist.contains(s)) {
            	   				if(wa_file == null) {
            	   					SharedPreferences sts = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
   	                	   			wa_file = sts.getString("wa_file", null);
   	                	   		}
            	   				auto_answer(wa_file);
            	   				return;	
            	   			}
            	   			break;
            	   		default: 
            	   			Log.dbg("no b/w lists active");
            	   			break;
            	   	}
            	   	if(s != null) {
            	   		String name = ctx.findInContacts(s);
            	   		if(name != null) {
            	   			call_type = CType.INCOMING_CONT;
            	   			call_proc = cont_proc;
            	   			inc_number = new String(name);
            	   			if(report != null) report += (" [" + inc_number +"]" );
            	   		} else {
            	   			call_type = CType.INCOMING_NCONT;
            	   			call_proc = ncont_proc;
            	   			inc_number = new String(s);
            	   		}
            	   		Log.dbg("default call_proc = " + call_proc );

            	   		if(irlist == null) irlist = get_array(FContentList.IEMODE,FContentList.TYPE_R);
            	   		if(ialist == null) ialist = get_array(FContentList.IEMODE,FContentList.TYPE_A);
            	   		if(inlist == null) inlist = get_array(FContentList.IEMODE,FContentList.TYPE_N);
            	   		if(iilist == null) iilist = get_array(FContentList.IEMODE,FContentList.TYPE_I);
            	   		
            	   		if(irlist.contains(s)) {	// overrides
            	   			call_proc = INCOMING_ACT_REC;
            	   			Log.dbg("phone found in always record list");
            	   			if(report != null) report += " [in always record list]";
            	   		} else if(inlist.contains(s)) { 
            	   			call_proc = INCOMING_ACT_NONE;
            	   			Log.dbg("phone found in always skip list");
            	   			if(report != null) report += " [in always skip list]";
            	   		} else if(ialist.contains(s)) {
            	   			call_proc = INCOMING_ACT_ASK;
            	   			Log.dbg("phone found in always ask list");
            	   			if(report != null) report += " [in always ask list]";
            	   		} else if(iilist.contains(s)) {
            	   			call_proc = INCOMING_ACT_ASK_INCALL;
            	   			Log.dbg("phone found in always ask in-call list");
            	   			if(report != null) report += " [in always incall list]";
            	   		}
            	   		Log.dbg("effective call_proc after lists = " + call_proc );
            	   	} else {
            	   		inc_number = "unknown";
            	   		call_type = CType.INCOMING_NONUMBER;
            	   		call_proc = unk_proc;
            	   	}
            	   	
        	   		Log.msg("CallReceiver: new incoming call " + inc_number);
	   				switch(call_proc) {
   						case INCOMING_ACT_HUP:
   							telephony.invoke("silenceRinger");
   							telephony.invoke("endCall"); 
   							Log.msg("number " + inc_number + ": hanged up");
   							if(report != null) log.write(report + ", hanged up");
   							break;
   						case INCOMING_ACT_IGN: 
   							shutup();
   							Log.msg("number " + inc_number + ": call muted");
   							if(report != null) log.write(report + ", muted");
   							break;
   						case INCOMING_ACT_ASK:
   							broadcastAboutToRecord(false, false, inc_number);
   							try {
   								java.lang.Thread.sleep(2000);
   							} catch (InterruptedException e) {
   								Log.err("Interrupted exception occurred.");	
   								e.printStackTrace();
   							} 
   							startAskActivity(context, false, false);
   							ask_in_progress = true;
   							if(report != null) log.write(report + ", query user");
   							break;
   						case INCOMING_ACT_AUTOANSWER:
   	                	   	String aa_file = null;
   	                	   	switch(call_type) {
   	                	   		case INCOMING_NONUMBER:
   	                	   			if(un_file == null) {
   	                	   				SharedPreferences sts = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
   	                	   				un_file = sts.getString("un_file", null);
   	                	   			}
   	                	   			aa_file = un_file; break;
   	                	   		case INCOMING_CONT:
   	                	   			if(cn_file == null) {
   	                	   				SharedPreferences sts = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
   	                	   				cn_file = sts.getString("cn_file", null);
   	                	   			}
   	                	   			aa_file = cn_file; break;
   	                	   		case INCOMING_NCONT:
   	                	   			if(nc_file == null) {
   	                	   				SharedPreferences sts = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
   	                	   				nc_file = sts.getString("nc_file", null);
   	                	   			}
   	                	   			aa_file = nc_file; break;
   	                	   		default: break;	
   	                	   	}
   	                	   	auto_answer(aa_file);
   	                		break;
	   				}

               } else if(s.equals(TelephonyManager.EXTRA_STATE_OFFHOOK)) {

            	   	if(call_proc == INCOMING_ACT_AUTOANSWER && out_number == null) return;
            	   	start_time = 0;
        	   		
            	   	switch(call_type) {
        	   			case OUTGOING_CONT:
        	   			case OUTGOING_NCONT:
        	   				if(call_proc == OUTGOING_REC_ALL) {
        	   					start_time = System.currentTimeMillis();
        	   					break;  // record 
        	   				} else if(call_proc == OUTGOING_REC_ASK) {
                	   			startAskActivity(context, true, false);
                	   			broadcastAboutToRecord(true, false, out_number);
                	   			ask_in_progress = true;
                	   			if(report != null) log.write(report + ", query user");
                	   			return;
        	   				} else if(call_proc == OUTGOING_REC_ASK_INCALL) {
        	   					startAskActivity(context, true, true);
        	   					broadcastAboutToRecord(true, true, out_number);
        	   					ask_incall_started = true;
        	   					if(report != null) log.write(report + ", query user");
        	   					return;
        	   				} else {
    	   						Log.msg("number " + out_number + ": skipped");	
    	   						set_defaults();
    	   						if(report != null) log.write(report + ", recording skipped");
    	   						return;
        	   				}
        	   			case INCOMING_NONUMBER:
        	   			case INCOMING_CONT:
        	   			case INCOMING_NCONT:
        	   				if(call_proc == INCOMING_ACT_REC) {
        	   					broadcastAboutToRecord(false, false, inc_number);
        	   					break; // record
        	   				}
        	   				else if(call_proc == INCOMING_ACT_ASK_INCALL) {
        	   					startAskActivity(context, false, true);
        	   					broadcastAboutToRecord(false, true, inc_number);
        	   					ask_incall_started = true;
        	   					if(report != null) log.write(report + ", query user");
        	   					return;
        	   				}
        	   				Log.msg("number " + inc_number + ": skipped");
        	   				set_defaults();
        	   				if(report != null) log.write(report + ", recording skipped");
        	   				return;
        	   			default: 
        	   				Log.msg("Unknown call type, skipped."); 
        	   				set_defaults();
        	   				if(report != null) log.write(report + ", skipped: call type unknown");
        	   				return;
        	   		}
        	   		lastFile = makeFilename();
        	   		if(startRecord(lastFile, boost_up, boost_dn) != 0) {
        	   			Log.err("startRecord failed");
        	   			set_defaults();
        	   			if(report != null) log.write(report + ", failed to start recording!");
        	   		} else {
        	   			recording = true;
        	   			Log.msg("started recording to " + lastFile);
        	   			if(!wakeLock.isHeld()) wakeLock.acquire();
        	   			broadcastActivities(CBack.REC_START);
        	   			if(report != null) log.write(report + ", started recording to " + lastFile);
        	   		}
       	    		// set_defaults();
       	    		
               } else if(s.equals(TelephonyManager.EXTRA_STATE_IDLE)) {
            	   	
            	   	if(call_proc == INCOMING_ACT_AUTOANSWER && out_number == null) return;
            	   	if(ask_incall_started || ask_in_progress) {
       	    			Log.msg("stopping AskActivity");
       	    			broadcastActivities(CBack.DONE);
       	    			ask_incall_started = false; // for tattoo
       	    		}
            	    if(recording) {
       	    			Log.msg("stop recording");
       	    			encoding = true;
       	    			if(start_time != 0 && min_out_time != 0 && call_proc != INCOMING_ACT_ASK_INCALL
       	    					&& call_proc != OUTGOING_REC_ASK_INCALL) {
       	    				long ctime = System.currentTimeMillis();
       	    				if(ctime - start_time < min_out_time) {
       	    					if(!min_out_confirm) {
           	    					Log.dbg("elapsed=" + (ctime - start_time) + ", min=" 
           	    							+ min_out_time + ", deleting the recording");
       	    						stopRecord(-1);
       	    						if(log != null) log.write("Recording took "+ (ctime - start_time)/1000 +
       	    								"s, min value in settings="+min_out_time/1000+"s, deleted");
       	    					} else if(lastFile != null){
       	    						if(log != null) log.write("Recording too short, asking user");
       	    						Log.dbg("elapsed=" + (ctime - start_time) + ", min=" 
           	    						+ min_out_time + ", trying to ask whether to delete the recording");
       	    						String ff = new String(lastFile);
       	    						ff += (srv_mode ==MODE_RECORD_WAV) ? ".wav" : ".mp3";
       	    						stopRecord(srv_mode);
       	    						wait_confirm_result = 0;
       	    						WaitConfirm wf = new WaitConfirm(ff);
       	    						wf.start();
       	    						if(need_ask_activity) {
       	    							Intent intie = new Intent();
       	    				 	   		intie.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
       	    				 	   		intie.setClassName("com.voix", "com.voix.AskDelete");
       	    				 	   		context.startActivity(intie);
       	    						}
       	    					} else {
       	    						stopRecord(srv_mode);
       	    					}
       	    				} else stopRecord(srv_mode);
       	    			} else stopRecord(srv_mode);
       	    		}
   	    			start_time = 0;
       	    		set_defaults();
               } 
		}
		private void auto_answer(String aa_file) {
       	   	if(aa_file != null && (new File(aa_file)).exists()) {
       	   		telephony.invoke("answerRingingCall");
       	   		telephony.invoke("silenceRinger");
       	   		answerCall(aa_file);
       	   		Log.msg("number " + inc_number + ": call auto answered");
       	   		if(report != null) log.write(report + ", auto answered");
       	   	} else {
       	   		Log.msg("number " + inc_number + ": auto answer skipped: no playback file");
       	   		if(report != null) log.write(report + ", auto answer skipped: no playback file");
       	   	}
		}
	}

	/////////////////////////////////////////////////////
	/////////////////////////////////////////////////////
	private class WaitConfirm extends Thread {
		private int tid = -1;
		private String fname;
		public WaitConfirm(String s) {
			fname = new String("/sdcard/voix/" + s);
		}
		@Override
		public void run() {
			tid = android.os.Process.myTid();
			Log.dbg(tid + ": thread started for " + fname);
			try {
				while(encoding) {
					Thread.sleep(100);
				}
				File f = new File(fname);
				boolean usr_replied = false;
				if(f.exists()) {
					for(int i = 0; i < 20; i++) {
						if(wait_confirm_result == 2) {
							Log.dbg(fname + " delete confirmed");
							f.delete();
							if(log != null) log.write("Recording deleted at user request");
							usr_replied = true;
							break;
						} else if(wait_confirm_result == 1) {
							Log.dbg(fname + " delete not confirmed");
							usr_replied = true;
							if(log != null) log.write("Recording not deleted at user request");
							break; 
						}
						Thread.sleep(1000);
					}
					if(!usr_replied) {
						if(log != null) log.write("No user reply, recording not deleted");
					}
				} else Log.dbg(fname + "does not exist after encoding");
			} catch (Exception e){ 
				Log.err("exception in run()");
				e.printStackTrace();	
			}
			Log.dbg(tid + ": thread about to exit");
		}
	}

	/////////////////////////////////////////////////////	
	// Helper class to call internal telephony interface
	///////////////////////////////////////////////////// 
	private class Telephony {
		private Object tel_mgr;
		Telephony() {
			try {
				Class<?> sm = Class.forName("android.os.ServiceManager");
				Method getSvc = sm.getMethod("getService", String.class);
				IBinder oBender = (IBinder) getSvc.invoke(sm, Context.TELEPHONY_SERVICE);
				Class<?> stub = Class.forName("com.android.internal.telephony.ITelephony$Stub");
				Method asIf = stub.getMethod("asInterface", IBinder.class);
				tel_mgr = asIf.invoke(stub, oBender);
			} catch (Exception e) { 
				Log.err("exception while trying to get ITelephony interface");
				e.printStackTrace();
			}
		}
		public void invoke(String function) {
			try {
				tel_mgr.getClass().getMethod(function,(Class [])null).invoke(tel_mgr);
			} catch (Exception e) {	
				Log.err("exception in invoke()");
				e.printStackTrace();
			}
		}
	}
	
	
	private void getPrefs(){
		
		SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
		
		foreground = settings.getBoolean("foreground", true);
		disable_notifications = settings.getBoolean("disable_notify", false);
		logging = settings.getBoolean("logging", false);
		is_tattoo = settings.getBoolean("is_tattoo", false);
		min_out_confirm = settings.getBoolean("min_out_confirm", true);
		
		try {
			srv_mode = Integer.parseInt(settings.getString("format", "0"));
			boost_up = Integer.parseInt(settings.getString("boost_up", "0"));
			boost_dn = Integer.parseInt(settings.getString("boost_dn", "0"));
			out_proc = Integer.parseInt(settings.getString("outgoing_calls", "3"));
			unk_proc = Integer.parseInt(settings.getString("unknown_numbers", "1"));
			cont_proc = Integer.parseInt(settings.getString("numbers_in_contacts", "1"));
			ncont_proc = Integer.parseInt(settings.getString("numbers_not_in_contacts", "1"));
			min_out_time = Integer.parseInt(settings.getString("min_out_time", "0"));
			min_out_time *= 1000; // seconds -> milliseconds
			bmode = Integer.parseInt(settings.getString("bmode", "0"));
		} catch (Exception e) {
			Log.err("Error parsing preference values");
			e.printStackTrace();
		}
	}
	
	class Fxx implements Comparable<Fxx>  {
		public String fname;
		long last_mod;
		long length;
		@Override
		public int compareTo(Fxx another) {
			if(this.last_mod > another.last_mod) return 1;
			else if(this.last_mod < another.last_mod) return -1;
			else return 0;
//			return (int) (this.last_mod - another.last_mod);
		}
	}

	private NotificationManager mNM;
	private Method mStartForeground;
	private Method mStopForeground;
	private Object[] mStartForegroundArgs = new Object[2];
	private Object[] mStopForegroundArgs = new Object[1];

	/**
	 * This is a wrapper around the new startForeground method, using the older
	 * APIs if it is not available.
	 */
	void startForegroundCompat(int id, Notification notification) {
	    // If we have the new startForeground API, then use it.
	    if (mStartForeground != null) {
	        mStartForegroundArgs[0] = Integer.valueOf(id);
	        mStartForegroundArgs[1] = notification;
	        try {
	            mStartForeground.invoke(this, mStartForegroundArgs);
	        } catch (Exception e) {
	        	Log.err("exception in startForeground");
	        	e.printStackTrace();
	        }
	        return;
	    }
	    // Fall back on the old API.
	    setForeground(true);
	    mNM.notify(id, notification);
	}

	/**
	 * This is a wrapper around the new stopForeground method, using the older
	 * APIs if it is not available.
	 */
	void stopForegroundCompat(int id) {
	    // If we have the new stopForeground API, then use it.
	    if (mStopForeground != null) {
	        mStopForegroundArgs[0] = Boolean.TRUE;
	        try {
	            mStopForeground.invoke(this, mStopForegroundArgs);
	        } catch (Exception e) {
	        	Log.err("exception in stopForeground");
	        	e.printStackTrace();
	        }
	        return;
	    }
	    // Fall back on the old API.  Note to cancel BEFORE changing the
	    // foreground state, since we could be killed at that point.
	    mNM.cancel(id);
	    setForeground(false);
	}
	
	private void cleanUp() {
		TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
		if(tm.getCallState() != TelephonyManager.CALL_STATE_IDLE) return;

		int max_files = 0; 
		long max_storage = 0, max_time = 0;
		
		Log.msg("trying to clean up");
		SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
		try {
			max_files = Integer.parseInt(settings.getString("max_files", null));
			max_storage= Integer.parseInt(settings.getString("max_storage", null));
			max_storage *= (1024*1024); // megabytes -> bytes
			max_time = Integer.parseInt(settings.getString("max_time", null));
			max_time *= 1000*60*60*24;	// days -> milliseconds
		} catch (Exception e) {
			Log.err("Error parsing cleanup preference values");
			e.printStackTrace();
			return;
		}
		
		if(max_files == 0 && max_storage == 0 && max_time == 0) {
			Log.msg("no limits set, bailing out");
			return;
		}
		
		// read favourites

	    FContentList favs = new FContentList(FContentList.FAVS_FILE, this);
	    favs.read();
		
		// Log.msg("total favs: " + favs.size());
		
		// get all files
		
		ArrayList<Fxx> files = new ArrayList<Fxx>();
			
		try {
			File[] filez = (new File(com.voix.Browser.voixdir)).listFiles();
			for(int i = 0; i < filez.length; i++) {
				String s = filez[i].toString();
				String q = s.substring(com.voix.Browser.dirlen);
				if((s.endsWith(".wav") || s.endsWith(".mp3")) && (q.startsWith("I-") || q.startsWith("O-"))) {
					if(favs.contains(s)) {
				//		Log.msg(s + " in favourites, skipped.");
						continue;
					}
					Fxx x = new Fxx();
					x.fname = s;
					x.last_mod = filez[i].lastModified();
					x.length = filez[i].length();
					files.add(x);
				}
			}
		} catch (Exception e) {
			Log.err("something nasty happened");
			e.printStackTrace();
		}
		
		if(files.size() == 0) {
			Log.msg("no files: cleanup complete");
			return;
		}
		
		Collections.sort(files);
		
	//	for(int i = 0; i < files.size(); i++) {
	//		Log.msg("file: " + files.get(i).fname + ", last_mod=" + files.get(i).last_mod);
	//	}
		
		////////////////////// Real job begins.
		//////////////////////

	//	Log.msg("step 1: " + files.size() + " files to process");
		if(max_files != 0 && max_files < files.size()) {
			int nr = 0;
			while(max_files < files.size()) {
				(new File(files.get(0).fname)).delete();
				files.remove(0);
				nr++;
			}
			if(nr != 0) Log.msg("cleanUp: removed " + nr + " files (max files number exceeded)");
		}

		if(files.size() == 0) {
			Log.msg("no more files: cleanup complete");
			return;
		}

	//	Log.msg("step 2: " + files.size() + " files to process");
		if(max_storage != 0) {
			int nr = 0;
			long sz = 0;
			for(int i = 0; i < files.size(); i++) sz += files.get(i).length;
			for(int i = 0; max_storage < sz && files.size() != 0; i++) {
				sz -= files.get(0).length;
				(new File(files.get(0).fname)).delete();
				files.remove(0);
				nr++;
			}
			if(nr != 0) Log.msg("cleanUp: removed " + nr + " files (max storage size exceeded)");
		}

		if(files.size() == 0) {
			Log.msg("no more files: cleanup complete");
			return;
		}
		
	//	Log.msg("step 3: " + files.size() + " files to process");
		if(max_time != 0) {
			int nr = 0;
			long now = System.currentTimeMillis();
			while(files.size() != 0) {
				if(now - files.get(0).last_mod < max_time) break; // files are time ordered
				(new File(files.get(0).fname)).delete();
				Log.msg("removing: " + files.get(0).fname + " " + (now - files.get(0).last_mod) + " < " + max_time);
				files.remove(0);
				nr++;
			}
			if(nr != 0) Log.msg("cleanUp: removed " + nr + " files (max storage duration exceeded)");
		}
		Log.msg("cleanup complete: " + files.size() + " non-favourites remain");
	}
	public class ServiceLogger {
		public static final String logfile = "/sdcard/voix/.callog.txt";
		private BufferedWriter writer = null;
		ServiceLogger() {
			try {
				File f = new File(logfile);
				writer = new BufferedWriter(new FileWriter(f, true), 8192);
			} catch (IOException e) {
				e.printStackTrace();
				Log.err("exception trying to create logger");
			}			
		}
		void write(String s) {
			if(writer == null) return;
			try {
				String date = DateFormat.format("MM/dd kk:mm:ss ", new Date()).toString();
				writer.write(date+s);
	    		writer.newLine();
	    		writer.flush();
			} catch (IOException e) {
				e.printStackTrace();
				Log.err("exception in write");
			}
		}
		void close() {
			if(writer == null) return;
			try {
					writer.close();
			} catch (IOException e) {
				e.printStackTrace();
				Log.err("exception in close");
			}
		}
		boolean can_write() {
			return writer != null;
		}
	}
}
