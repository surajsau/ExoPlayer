package com.google.android.exoplayer2.offline;

import com.google.android.exoplayer2.util.Log;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class QueuedDownload<T extends SegmentDownloader.Segment> {

    public static int SIZE = 3;

    interface Callback<T> {

        void download(T item, int type) throws InterruptedException, IOException;
    }

    private final List<T> itemsToDownload;
    private final Callback<T> callback;
    private final Object threadLock = new Object();

    private ArrayList<ArrayList<T>> parallelQueueList = new ArrayList<>();
    private int maxQueueSize;

    private volatile InterruptedException interruptedExceptionHolder = null;
    private volatile IOException ioExceptionHolder = null;


    public QueuedDownload(List<T> itemsToDownload, int size, Callback<T> callback) {
        this.itemsToDownload = itemsToDownload;
        this.callback = callback;
        this.maxQueueSize = size;
    }


    void download() throws IOException, InterruptedException {

        int size = itemsToDownload.size();

        for (int i = 0; i < size; i++) {

            int pos = i % maxQueueSize;

            ArrayList<T> targetQueue;

            if (pos >= parallelQueueList.size()) {
                targetQueue = new ArrayList<>();
                parallelQueueList.add(targetQueue);
            } else {
                targetQueue = parallelQueueList.get(pos);
            }

            targetQueue.add(itemsToDownload.get(i));
        }

        for (int i = 0; i < parallelQueueList.size(); i++) {
            executeAsync(parallelQueueList.get(i), i);
        }

        synchronized (threadLock) {
            if(! isAllQueueEmpty()) {
                threadLock.wait();
            }
        }

        if (interruptedExceptionHolder != null) {
            throw interruptedExceptionHolder;
        }

        if (ioExceptionHolder != null) {
            throw ioExceptionHolder;
        }

    }

    private boolean isAllQueueEmpty() {

        for (int i = 0; i < parallelQueueList.size(); i++) {

            if (!parallelQueueList.get(i).isEmpty()) {
                return false;
            }
        }

        return true;
    }

    private void executeAsync(ArrayList<T> queue, int type) {

        new Thread(() -> {
            try {
                execute(queue, type);
            } catch (IOException e) {
                ioExceptionHolder = e;
            } catch (InterruptedException e) {
                interruptedExceptionHolder = e;
            }
            finally {
                synchronized (threadLock) {
                    if (isAllQueueEmpty()) {
                        threadLock.notify();
                    }
                }
            }
        }).start();

    }

    private void execute(ArrayList<T> queue, int type) throws IOException, InterruptedException {

        while (!queue.isEmpty()) {

            if(ioExceptionHolder != null || interruptedExceptionHolder != null) {
                return;
            }
            T item = queue.remove(0);
            Log.d("VideoDownload", String.format(Locale.getDefault(), "Q(%d) -S:%d : U:%s", type, queue.size(), item.dataSpec.uri.toString()));
            callback.download(item, type);
        }

    }
}
