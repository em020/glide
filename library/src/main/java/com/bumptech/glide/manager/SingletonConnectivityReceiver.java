package com.bumptech.glide.manager;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.Network;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import android.support.annotation.GuardedBy;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import com.bumptech.glide.manager.ConnectivityMonitor.ConnectivityListener;
import com.bumptech.glide.util.GlideSuppliers;
import com.bumptech.glide.util.GlideSuppliers.GlideSupplier;
import com.bumptech.glide.util.Synthetic;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Uses {@link android.net.ConnectivityManager} to identify connectivity changes. */
final class SingletonConnectivityReceiver {
  private static volatile SingletonConnectivityReceiver instance;
  private static final String TAG = "ConnectivityMonitor";

  private final FrameworkConnectivityMonitor frameworkConnectivityMonitor;

  @GuardedBy("this")
  private final Set<ConnectivityListener> listeners = new HashSet<ConnectivityListener>();

  @GuardedBy("this")
  private boolean isRegistered;

  static SingletonConnectivityReceiver get(@NonNull Context context) {
    if (instance == null) {
      synchronized (SingletonConnectivityReceiver.class) {
        if (instance == null) {
          instance = new SingletonConnectivityReceiver(context.getApplicationContext());
        }
      }
    }
    return instance;
  }

  private SingletonConnectivityReceiver(@NonNull Context context) {
    GlideSupplier<ConnectivityManager> connectivityManager =
        GlideSuppliers.memorize(
            new GlideSupplier<ConnectivityManager>() {
              @Override
              public ConnectivityManager get() {
                return (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
              }
            });
    ConnectivityListener connectivityListener =
        new ConnectivityListener() {
          @Override
          public void onConnectivityChanged(boolean isConnected) {
            List<ConnectivityListener> toNotify;
            synchronized (SingletonConnectivityReceiver.this) {
              toNotify = new ArrayList<>(listeners);
            }
            for (ConnectivityListener listener : toNotify) {
              listener.onConnectivityChanged(isConnected);
            }
          }
        };

    frameworkConnectivityMonitor =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
            ? new FrameworkConnectivityMonitorPostApi24(connectivityManager, connectivityListener)
            : new FrameworkConnectivityMonitorPreApi24(
                context, connectivityManager, connectivityListener);
  }

  synchronized void register(ConnectivityListener listener) {
    listeners.add(listener);
    maybeRegisterReceiver();
  }

  synchronized void unregister(ConnectivityListener listener) {
    listeners.remove(listener);
    maybeUnregisterReceiver();
  }

  @GuardedBy("this")
  private void maybeRegisterReceiver() {
    if (isRegistered || listeners.isEmpty()) {
      return;
    }
    isRegistered = frameworkConnectivityMonitor.register();
  }

  @GuardedBy("this")
  private void maybeUnregisterReceiver() {
    if (!isRegistered || !listeners.isEmpty()) {
      return;
    }

    frameworkConnectivityMonitor.unregister();
    isRegistered = false;
  }

  private interface FrameworkConnectivityMonitor {
    boolean register();

    void unregister();
  }

  @RequiresApi(VERSION_CODES.N)
  private static final class FrameworkConnectivityMonitorPostApi24
      implements FrameworkConnectivityMonitor {

    private final ConnectivityListener listener;
    private final GlideSupplier<ConnectivityManager> connectivityManager;
    private final NetworkCallback networkCallback =
        new NetworkCallback() {
          @Override
          public void onAvailable(@NonNull Network network) {
            super.onAvailable(network);
            listener.onConnectivityChanged(true);
          }

          @Override
          public void onLost(@NonNull Network network) {
            super.onLost(network);
            listener.onConnectivityChanged(false);
          }
        };

    FrameworkConnectivityMonitorPostApi24(
        GlideSupplier<ConnectivityManager> connectivityManager, ConnectivityListener listener) {
      this.connectivityManager = connectivityManager;
      this.listener = listener;
    }

    // Permissions are checked in the factory instead.
    @SuppressLint("MissingPermission")
    @Override
    public boolean register() {
      connectivityManager.get().registerDefaultNetworkCallback(networkCallback);
      return true;
    }

    @Override
    public void unregister() {
      connectivityManager.get().unregisterNetworkCallback(networkCallback);
    }
  }

  private static final class FrameworkConnectivityMonitorPreApi24
      implements FrameworkConnectivityMonitor {
    private final Context context;
    private final ConnectivityListener listener;
    private final GlideSupplier<ConnectivityManager> connectivityManager;
    private boolean isConnected;

    private final BroadcastReceiver connectivityReceiver =
        new BroadcastReceiver() {
          @Override
          public void onReceive(@NonNull Context context, Intent intent) {
            boolean wasConnected = isConnected;
            isConnected = isConnected();
            if (wasConnected != isConnected) {
              if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "connectivity changed, isConnected: " + isConnected);
              }

              listener.onConnectivityChanged(isConnected);
            }
          }
        };

    FrameworkConnectivityMonitorPreApi24(
        Context context,
        GlideSupplier<ConnectivityManager> connectivityManager,
        ConnectivityListener listener) {
      this.context = context.getApplicationContext();
      this.connectivityManager = connectivityManager;
      this.listener = listener;
    }

    @Override
    public boolean register() {
      try {
        // See #1405
        context.registerReceiver(
            connectivityReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        return true;
      } catch (SecurityException e) {
        // See #1417, registering the receiver can throw SecurityException.
        if (Log.isLoggable(TAG, Log.WARN)) {
          Log.w(TAG, "Failed to register", e);
        }
      }

      return false;
    }

    @Override
    public void unregister() {
      context.unregisterReceiver(connectivityReceiver);
    }

    @SuppressWarnings("WeakerAccess")
    @Synthetic
    // Permissions are checked in the factory instead.
    @SuppressLint("MissingPermission")
    boolean isConnected() {
      NetworkInfo networkInfo;
      try {
        networkInfo = connectivityManager.get().getActiveNetworkInfo();
      } catch (RuntimeException e) {
        // #1405 shows that this throws a SecurityException.
        // b/70869360 shows that this throws NullPointerException on APIs 22, 23, and 24.
        // b/70869360 also shows that this throws RuntimeException on API 24 and 25.
        if (Log.isLoggable(TAG, Log.WARN)) {
          Log.w(TAG, "Failed to determine connectivity status when connectivity changed", e);
        }
        // Default to true;
        return true;
      }
      return networkInfo != null && networkInfo.isConnected();
    }
  }
}
