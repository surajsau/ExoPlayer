package com.google.android.exoplayer2.offline;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class QueuedDownload<T extends SegmentDownloader.Segment> {

    public static int SIZE = 3;

    interface Callback<T> {

        void download(T item, int type) throws InterruptedException, IOException;
    }

    private final List<T> itemsToDownload;
    private final Callback<T> callback;
    private final Object threadLock = new Object();

    private ArrayList<ArrayList<T>> parallelQueueList = new ArrayList<>();
    private ArrayList<Thread> parallelThreadList = new ArrayList<>();
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

        parallelThreadList.clear();

        for (int i = 0; i < parallelQueueList.size(); i++) {
            Thread t = createThread(parallelQueueList.get(i), i);
            parallelThreadList.add(t);
            t.start();
        }

        synchronized (threadLock) {
            if(! isAllQueueEmpty()) {
                threadLock.wait();
            }
        }

        if (interruptedExceptionHolder != null) {
            killThreads();
            throw interruptedExceptionHolder;
        }

        if (ioExceptionHolder != null) {
            killThreads();
            throw ioExceptionHolder;
        }

    }

    private void killThreads() {

        for (int i = 0; i < parallelThreadList.size(); i++) {
            Thread thread = parallelThreadList.get(i);
            if(thread.isAlive()) {
                thread.interrupt();
            }
        }

        parallelThreadList.clear();
    }

    private boolean isAllQueueEmpty() {

        for (int i = 0; i < parallelQueueList.size(); i++) {

            if (!parallelQueueList.get(i).isEmpty()) {
                return false;
            }
        }

        return true;
    }

    private Thread createThread(ArrayList<T> queue, int type) {

        return new Thread(() -> {
            try {
                execute(queue, type);
                notifyMainIfEmpty();

            } catch (IOException e) {
                ioExceptionHolder = e;
                notifyMain();
            } catch (InterruptedException e) {
                interruptedExceptionHolder = e;
                notifyMain();
            }
        });
    }

    private void notifyMainIfEmpty() {
        synchronized (threadLock) {
            if(isAllQueueEmpty()) {
                threadLock.notify();
            }
        }
    }

    private void notifyMain() {

        synchronized (threadLock) {
                threadLock.notify();
        }
    }

    private void execute(ArrayList<T> queue, int type) throws IOException, InterruptedException {

        while (!queue.isEmpty()) {

            if(ioExceptionHolder != null || interruptedExceptionHolder != null) {
                return;
            }
            T item = queue.remove(0);
            callback.download(item, type);
        }

    }
}
