package com.serenegiant.utils;

import android.os.Handler;
import android.os.HandlerThread;

public final class HandlerThreadHandler {

    private HandlerThreadHandler() {
    }

    public static Handler createHandler(final String name) {
        final HandlerThread handlerThread = new HandlerThread(name);
        handlerThread.start();
        return new Handler(handlerThread.getLooper());
    }
}
