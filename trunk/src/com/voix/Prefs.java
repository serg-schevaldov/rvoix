package com.voix;

import java.io.File;
import java.util.List;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.preference.Preference.OnPreferenceClickListener;
import android.widget.Toast;

public class Prefs extends PreferenceActivity 
	implements SharedPreferences.OnSharedPreferenceChangeListener {
	
	private boolean prefs_changed;
	public static final int BW_REQ_CODE = 22;
	private String fdir;
	
	@Override
    protected void onCreate(Bundle savedInstanceState) {

		super.onCreate(savedInstanceState);
        Log.dbg("onCreate(): entry");
        prefs_changed = false;
        addPreferencesFromResource(R.xml.prefs);
        
        PreferenceScreen screen = getPreferenceScreen();
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        
        fdir = getFilesDir().toString() + "/";
        
        ListPreference m = (ListPreference) screen.findPreference("format");
       	
        m.setSummary(str2int(settings.getString("format", "0")) == 0 ? "WAV" : "MP3");

		String []x = getResources().getStringArray(R.array.OutCallActions);
		m = (ListPreference) screen.findPreference("outgoing_calls");
		m.setSummary(x[str2int(settings.getString("outgoing_calls", "0"))]);

		String []z = {"unknown_numbers", "numbers_in_contacts", "numbers_not_in_contacts"};
		String []ia = getResources().getStringArray(R.array.InCallActions);
		for(String s : z) {
			m = (ListPreference) screen.findPreference(s);
			m.setSummary(ia[str2int(settings.getString(s, "0"))]);
		}
		
		String []q = {"boost_up", "boost_dn"};
		for(String s : q) {
			m = (ListPreference) screen.findPreference(s);
			m.setSummary(getString(R.string.SCurVal) + " " + settings.getString(s, "0"));
		}
			
		String []y = {"max_files", "max_storage", "max_time", "min_out_time"};
		for(String s : y) {
			EditTextPreference e = (EditTextPreference) screen.findPreference(s);
			e.setSummary(getString(R.string.SCurVal) + " " + settings.getString(s, "")); 
		}

		String []iw = getResources().getStringArray(R.array.BlistActions);
		m = (ListPreference) screen.findPreference("bmode");
		int io = str2int(settings.getString("bmode", "0"));
		if(io > 3) io = 0;
		m.setSummary(iw[io]);

	       screen.getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
		Log.dbg("onCreate(): exit");
        
		Preference p;
		p = screen.findPreference("edit_wlist");
       	p.setSummary(getString((new File(fdir+FContentList.LIST_FILES[0])).exists() 
       		? R.string.SListActive : R.string.SListInactive));
       	p.setOnPreferenceClickListener(new OnPreferenceClickListener() {
       		@Override
       		public boolean onPreferenceClick(Preference preference) {
       			Intent intent = new Intent().setClassName("com.voix", "com.voix.BWList");
       			intent.putExtra("list_color", FContentList.WMODE);
       			startActivityForResult(intent, BW_REQ_CODE);
       			return false;
       		}});
       	p = screen.findPreference("edit_blist");
       	p.setSummary(getString((new File(fdir+FContentList.LIST_FILES[1])).exists() 
       			? R.string.SListActive : R.string.SListInactive));
       	p.setOnPreferenceClickListener(new OnPreferenceClickListener() {
       		@Override
       		public boolean onPreferenceClick(Preference preference) {
       			Intent intent = new Intent().setClassName("com.voix", "com.voix.BWList");
       			intent.putExtra("list_color", FContentList.BMODE);
       			startActivityForResult(intent, BW_REQ_CODE);
       			return false;
       		}});
       	p = screen.findPreference("edit_ielist");
       	p.setSummary(getString((new File(fdir+FContentList.LIST_FILES[2])).exists() 
       			? R.string.SListActive : R.string.SListInactive));
       	p.setOnPreferenceClickListener(new OnPreferenceClickListener() {
       		@Override
       		public boolean onPreferenceClick(Preference preference) {
       			Intent intent = new Intent().setClassName("com.voix", "com.voix.BWList");
       			intent.putExtra("list_color", FContentList.IEMODE);
       			startActivityForResult(intent, BW_REQ_CODE);
       			return false;
       		}});
       	p = screen.findPreference("edit_oelist");
       	p.setSummary(getString((new File(fdir+FContentList.LIST_FILES[3])).exists() 
       			? R.string.SListActive : R.string.SListInactive));
       	p.setOnPreferenceClickListener(new OnPreferenceClickListener() {
       		@Override
       		public boolean onPreferenceClick(Preference preference) {
       			Intent intent = new Intent().setClassName("com.voix", "com.voix.BWList");
       			intent.putExtra("list_color", FContentList.OEMODE);
       			startActivityForResult(intent, BW_REQ_CODE);
       			return false;
       		}});

       	final Preference pp = screen.findPreference("delete_log");
       	final File ff = new File(RVoixSrv.ServiceLogger.logfile);
       	final Context ctx = this;
       	if(!ff.exists()) {
       		pp.setEnabled(false);
       		pp.setSummary(R.string.SNoLogFile);
       	} else pp.setOnPreferenceClickListener(new OnPreferenceClickListener() {
       		@Override
       		public boolean onPreferenceClick(Preference preference) {
       			try {       			
       				ff.delete();
       				pp.setEnabled(false);
       				pp.setSummary(R.string.SNoLogFile);
       				Toast.makeText(ctx, R.string.SLogCleared, Toast.LENGTH_SHORT).show();
       			} catch (Exception e) {
       				e.printStackTrace();
       				Toast.makeText(ctx, R.string.SCantDeleteLogFile, Toast.LENGTH_SHORT).show();
       			}
       			return false;
       		}});
	}

    private int str2int(String s) { // won't use Integer.parseInt
		if(s == null) return 0;
		if(s.compareTo("1")==0) return 1;
		else if(s.compareTo("2")==0) return 2;
		else if(s.compareTo("3")==0) return 3;
		else if(s.compareTo("4")==0) return 4;
		else if(s.compareTo("5")==0) return 5;
		return 0;
	}
	
	@Override
	public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
		prefs_changed = true;
		Preference pref = findPreference(key);
	    if (pref instanceof ListPreference) {
	        ListPreference listPref = (ListPreference) pref;
	        String s = ""+ listPref.getEntry();
	        if(key.equals("boost_up") || key.equals("boost_dn")) s = getString(R.string.SCurVal) + " " + s;
			pref.setSummary(s);
	    } else if (pref instanceof EditTextPreference) {
	    	EditTextPreference editPref = (EditTextPreference) pref;
		    pref.setSummary(getString(R.string.SCurVal) + " " + editPref.getText());
	    }	
	}
	@Override
	public void onDestroy() {
		getPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
		if(prefs_changed) {
			ActivityManager am = (ActivityManager)this.getSystemService(ACTIVITY_SERVICE);  
			List<ActivityManager.RunningServiceInfo> ls = am.getRunningServices(1000);
			for(int i = 0; i < ls.size(); i++) {
				if(ls.get(i).process.compareTo("com.voix:remote")==0) {
					Intent intent = new Intent().setClassName("com.voix", "com.voix.RVoixSrv");
			        if(startService(intent)!= null) Log.msg("service restarted");
			        break;
				}
			}
		}
		super.onDestroy();
	}
	@Override
	protected void  onActivityResult(int requestCode, int resultCode, Intent  data) {
		   super.onActivityResult(requestCode, resultCode, data);
		   Log.dbg("onActivityResult() code="+requestCode+", result="+resultCode);
		   if(resultCode == 1) {
			   prefs_changed = true;
			   PreferenceScreen screen = getPreferenceScreen();
			   Preference p;
			   String[] lst = {"edit_wlist","edit_blist","edit_ielist","edit_oelist" };
			   for(int i = 0; i < lst.length; i++) {
				   p = screen.findPreference(lst[i]);
				   p.setSummary(getString((new File(fdir+FContentList.LIST_FILES[i])).exists() 
						   ? R.string.SListActive : R.string.SListInactive));
			   }   
			   java.lang.System.gc();
			   setResult(1);
		   }   
	}
}
