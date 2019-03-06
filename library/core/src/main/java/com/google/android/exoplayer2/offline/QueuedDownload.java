package com.google.android.exoplayer2.offline;

import android.os.SystemClock;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;

public class QueuedDownload<T> {

    public static int SIZE = 3;

    interface Callback<T> {
        void download(T item) throws InterruptedException, IOException;
    }

    private final List<T> itemsToDownload;
//    private final int maxParallelSize;
    private final Callback<T> callback;
    private final Object Q_LOCK = new Object();

    private int currentParallelCount = 0;
    private InterruptedException interruptedExceptionHolder = null;
    private IOException ioExceptionHolder = null;


    private QueuedDownload(List<T> itemsToDownload, int maxParallelSize, Callback<T> callback) {
        this.itemsToDownload = itemsToDownload;
//        this.maxParallelSize = maxParallelSize;
        this.callback = callback;
    }

    QueuedDownload(List<T> itemsToDownload, Callback<T> callback) {
        this(itemsToDownload, 0, callback);
    }

    void initDownload() throws IOException, InterruptedException {

        while (itemsToDownload.size() > 0) {

            while (currentParallelCount >= SIZE) {
                SystemClock.sleep(100);
            }

            if(interruptedExceptionHolder != null) {
                throw interruptedExceptionHolder;
            }

            if(ioExceptionHolder != null) {
                throw ioExceptionHolder;
            }

            T item = itemsToDownload.remove(0);
            downloadAsync(item);

        }
    }

    private void downloadAsync(T item) {

        synchronized (Q_LOCK) {
            currentParallelCount++;
        }

        new Thread(() -> {
            try {
                callback.download(item);
            } catch (InterruptedException e) {
                interruptedExceptionHolder = e;
            }
            catch (IOException e) {
                ioExceptionHolder = e;
            }
            synchronized (Q_LOCK) {
                currentParallelCount--;
            }
        }).start();

    }
}
