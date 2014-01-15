/*
 * Copyright 2013 ParanoidAndroid Project
 *
 * This file is part of Paranoid OTA.
 *
 * Paranoid OTA is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Paranoid OTA is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Paranoid OTA.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.paranoid.paranoidota.updater;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONObject;

import android.app.Activity;
import android.content.Context;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;
import com.paranoid.paranoidota.Utils;
import com.paranoid.paranoidota.Version;
import com.paranoid.paranoidota.helpers.SettingsHelper;

public abstract class Updater implements Response.Listener<JSONObject>, Response.ErrorListener {

    public interface PackageInfo extends Serializable {

        public String getMd5();

        public String getFilename();

        public String getPath();

        public String getSize();

        public Version getVersion();

        public boolean isDelta();

        public String getDeltaFilename();

        public String getDeltaPath();

        public String getDeltaMd5();

        public boolean isGapps();
    }

    public static final String PROPERTY_DEVICE = "ro.pa.device";
    public static final String PROPERTY_DEVICE_EXT = "ro.product.device";

    public static final int NOTIFICATION_ID = 122303225;

    public static interface UpdaterListener {

        public void startChecking(boolean isRom);

        public void versionFound(PackageInfo[] info, boolean isRom);

        public void checkError(boolean isRom);
    }

    private Context mContext;
    private Server[] mServers;
    private PackageInfo[] mLastUpdates;
    private List<UpdaterListener> mListeners = new ArrayList<UpdaterListener>();
    private RequestQueue mQueue;
    private SettingsHelper mSettingsHelper;
    private Server mServer;
    private boolean mScanning = false;
    private boolean mFromAlarm;
    private int mCurrentServer = -1;

    public Updater(Context context, Server[] servers, boolean fromAlarm) {
        mContext = context;
        mServers = servers;
        mFromAlarm = fromAlarm;
        mQueue = Volley.newRequestQueue(context);
    }

    public abstract Version getVersion();

    public abstract String getDevice();

    public abstract boolean isRom();

    public abstract int getErrorStringId();

    public abstract int getNoUpdatesStringId();

    protected Context getContext() {
        return mContext;
    }

    public SettingsHelper getSettingsHelper() {
        return mSettingsHelper;
    }

    public PackageInfo[] getLastUpdates() {
        return mLastUpdates;
    }

    public void setLastUpdates(PackageInfo[] infos) {
        mLastUpdates = infos;
    }

    public void addUpdaterListener(UpdaterListener listener) {
        mListeners.add(listener);
    }

    public void check() {
        if (mScanning) {
            return;
        }
        if (mSettingsHelper == null) {
            mSettingsHelper = new SettingsHelper(getContext());
        }
        if (mFromAlarm) {
            if (mSettingsHelper.getCheckTime() < 0
                    || (!isRom() && !mSettingsHelper.getCheckGapps())) {
                return;
            }
        }
        mScanning = true;
        fireStartChecking();
        nextServerCheck();
    }

    protected void nextServerCheck() {
        mScanning = true;
        mCurrentServer++;
        mServer = mServers[mCurrentServer];
        JsonObjectRequest jsObjRequest = new JsonObjectRequest(Request.Method.GET, mServer.getUrl(
                getDevice(), getVersion()), null, this, this);
        mQueue.add(jsObjRequest);
    }

    @Override
    public void onResponse(JSONObject response) {
        mScanning = false;
        try {
            PackageInfo[] lastUpdates = null;
            setLastUpdates(null);
            List<PackageInfo> list = mServer.createPackageInfoList(response);
            String error = mServer.getError();
            if (!isRom()) {
                boolean onlyMini = mSettingsHelper.getCheckGappsMini();
                PackageInfo info = null;
                for (int i = 0; i < list.size(); i++) {
                    info = list.get(i);
                    String fileName = info.getFilename();
                    if ((!onlyMini && (fileName.contains("-mini") || !fileName.contains("-full")))
                            || (onlyMini && !fileName.contains("-mini"))) {
                        list.remove(i);
                        i--;
                        continue;
                    }
                }
            }
            lastUpdates = list.toArray(new PackageInfo[list.size()]);
            if (lastUpdates.length > 0) {
                if (mFromAlarm) {
                    if (!isRom()) {
                        Utils.showNotification(getContext(), null, lastUpdates);
                    } else {
                        Utils.showNotification(getContext(), lastUpdates, null);
                    }
                }
            } else {
                if (error != null && !error.isEmpty()) {
                    if (versionError(error)) {
                        return;
                    }
                } else {
                    if (mCurrentServer < mServers.length - 1) {
                        nextServerCheck();
                        return;
                    }
                    if (!mFromAlarm) {
                        Utils.showToastOnUiThread(getContext(), getNoUpdatesStringId());
                    }
                }
            }
            mCurrentServer = -1;
            setLastUpdates(lastUpdates);
            fireCheckCompleted(lastUpdates);
        } catch (Exception ex) {
            System.out.println(response.toString());
            ex.printStackTrace();
            versionError(null);
        }
    }

    @Override
    public void onErrorResponse(VolleyError ex) {
        mScanning = false;
        versionError(null);
    }

    private boolean versionError(String error) {
        if (mCurrentServer < mServers.length - 1) {
            nextServerCheck();
            return true;
        }
        if (!mFromAlarm) {
            int id = getErrorStringId();
            if (error != null) {
                Utils.showToastOnUiThread(getContext(), getContext().getResources().getString(id)
                        + ": " + error);
            } else {
                Utils.showToastOnUiThread(getContext(), id);
            }
        }
        mCurrentServer = -1;
        fireCheckCompleted(null);
        fireCheckError(true);
        return false;
    }

    public boolean isScanning() {
        return mScanning;
    }

    public void removeUpdaterListener(UpdaterListener listener) {
        mListeners.remove(listener);
    }

    protected void fireStartChecking() {
        if (mContext instanceof Activity) {
            ((Activity) mContext).runOnUiThread(new Runnable() {

                public void run() {
                    for (UpdaterListener listener : mListeners) {
                        listener.startChecking(isRom());
                    }
                }
            });
        }
    }

    protected void fireCheckCompleted(final PackageInfo[] info) {
        if (mContext instanceof Activity) {
            ((Activity) mContext).runOnUiThread(new Runnable() {

                public void run() {
                    for (UpdaterListener listener : mListeners) {
                        listener.versionFound(info, isRom());
                    }
                }
            });
        }
    }

    protected void fireCheckError(final boolean isRom) {
        if (mContext instanceof Activity) {
            ((Activity) mContext).runOnUiThread(new Runnable() {

                public void run() {
                    for (UpdaterListener listener : mListeners) {
                        listener.checkError(isRom);
                    }
                }
            });
        }
    }
}
