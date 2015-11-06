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

package android.os;

/**
 * 用于创建内置looper的Handler简易子类。
 * Handy class for starting a new thread that has a looper. The looper can then be
 * used to create handler classes. Note that start() must still be called.
 */
public class HandlerThread extends Thread {
    /****/
    int mPriority;
    int mTid = -1;
    Looper mLooper;

    public HandlerThread(String name) {
        super(name);
        mPriority = Process.THREAD_PRIORITY_DEFAULT;
    }
    
    /**
     * Constructs a HandlerThread.
     * @param name
     * @param priority The priority to run the thread at. The value supplied must be from 
     * {@link android.os.Process} and not from java.lang.Thread.
     */
    public HandlerThread(String name, int priority) {
        super(name);
        mPriority = priority;
    }
    
    /**
     * Call back method that can be explicitly overridden if needed to execute some
     * setup before Looper loops.
     */
    protected void onLooperPrepared() {
    }

    @Override
    public void run() {
        mTid = Process.myTid();
        Looper.prepare();
        synchronized (this) {
            mLooper = Looper.myLooper();
            notifyAll();
        }
        Process.setThreadPriority(mPriority);
        onLooperPrepared();
        Looper.loop();
        //loop的messagqueue停止之后，才执行到这段代码
        mTid = -1;
    }
    
    /**
     * 如果HandlerThread的{@link #start()}没有被调用或者HandlerThread的{@link #isAlive()}返回false，
     * 这个方法都会返回null。如果HandlerThread的{@link #start()}已经调用，因为HandlerThread的Looper
     * 在{@link #run()}方法中才会被初始化，{@link #getLooper()}会让调用它的线程（有别于Looper所属
     * 的线程）阻塞直到Looper完成初始化。
     *
     * This method returns the Looper associated with this thread. If this thread not been started
     * or for any reason is isAlive() returns false, this method will return null. If this thread 
     * has been started, this method will block until the looper has been initialized.  
     * @return The looper.
     */
    public Looper getLooper() {
        if (!isAlive()) {
            return null;
        }
        
        //如果线程已经被启动，那么等待直到looper被创建
        // If the thread has been started, wait until the looper has been created.
        synchronized (this) {
            while (isAlive() && mLooper == null) {
                try {
                    wait();
                } catch (InterruptedException e) {
                }
            }
        }
        return mLooper;
    }

    /**
     * Quits the handler thread's looper.
     * <p>
     * Causes the handler thread's looper to terminate without processing any
     * more messages in the message queue.
     * </p><p>
     * Any attempt to post messages to the queue after the looper is asked to quit will fail.
     * For example, the {@link Handler#sendMessage(Message)} method will return false.
     * </p><p class="note">
     * Using this method may be unsafe because some messages may not be delivered
     * before the looper terminates.  Consider using {@link #quitSafely} instead to ensure
     * that all pending（when早于当前时间，但是因为各种原因未能得到处理的消息） work is completed
     * in an orderly manner.
     * </p>
     *
     * @return True if the looper looper has been asked to quit or false if the
     * thread had not yet started running. （如果线程还未开启，则返回false）
     *
     * @see #quitSafely
     */
    public boolean quit() {
        Looper looper = getLooper();
        if (looper != null) {
            looper.quit();
            return true;
        }
        return false;
    }

    /**
     * Quits the handler thread's looper safely.
     * <p>
     * Causes the handler thread's looper to terminate as soon as all remaining messages
     * in the message queue that are already due to be delivered have been handled.
     * Pending delayed messages with due times（到期/处理时间） in the future will not be delivered.
     * </p><p>
     * Any attempt to post messages to the queue after the looper is asked to quit will fail.
     * For example, the {@link Handler#sendMessage(Message)} method will return false.
     * </p><p>
     * If the thread has not been started or has finished (that is if
     * {@link #getLooper} returns null), then false is returned.
     * Otherwise the looper is asked to quit and true is returned.
     * </p>
     *
     * @return True if the looper looper has been asked to quit or false if the
     * thread had not yet started running.（如果线程还未开启，则返回false）
     */
    public boolean quitSafely() {
        Looper looper = getLooper();
        if (looper != null) {
            looper.quitSafely();
            return true;
        }
        return false;
    }

    /**
     * Returns the identifier of this thread. See Process.myTid().
     */
    public int getThreadId() {
        return mTid;
    }
}
