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

import android.util.Log;
import android.util.Printer;

/**
 * TODO :
 * 1.了解Binder.clearCallingIdentity();
 * 2.了解Logging,Printer
 * 3.消息队列的消息如何装填
 * 4.如何保证Looper不会被GC回收  --  因为ThreadLocal?
 * 5.主线程如何在不阻塞的情况下实现消息循环处理
 **/

/**
 *  Looper使用流程：Looper.prepare() -> Looper.loop() -> lI.quit() or lI.quitSafely()。
 *  对外主要API：Looper.prepare()、Looper.loop()、Looper.myQueue() 、lI.isCurrentThread()、
 *  lI.quit() 、lI.quitSafely()、lI.postSyncBarrier() 、 lI.removeSyncBarrier()
 *
 *  Looper实际上是线程的一个附加可选特性。非主线程默认情况下是不与任何Looper关联的，开发者可以
 *  使用Looper.prepare()为当前线程创建一个Looper,并使用Looper.loop()执行消息循环处理。loop()只
 *  有一个出口 : 有且仅当MessageQueue内部调用dispose()或者在非执行loop()的线程中调用该线程相应
 *  Looper的lI.quit()/lI.quitSafely()时，loop()才返回。所以，请谨记loop()之后的代码实际上在消息循环结
 *  束后才会被执行。
 *
 * <pre>
 *  class LooperThread extends Thread {
 *      public Handler mHandler;
 *      public void run() {
 *          Looper.prepare();
 *          //注意构造方法参数
 *          mHandler = new Handler(Looper.myLooper()) {
 *              public void handleMessage(Message msg) {
 *                  //默认情况下哪个线程创建Handler，Handler就会与哪个线程的Looper对象绑定。可以在创建
 *                  //Handler时指定要与其绑定的Looper。Handler发布的消息会在Handler绑定的Looper中被分
 *                  //发和处理
 *              }
 *          };
 *          Looper.loop();
 *          //消息循环结束后，想要执行的代码段
 *      }
 *  }
 *  </pre>
 */
public final class Looper {
    private static final String TAG = "Looper";

    // sThreadLocal.get() will return null unless you've called prepare().
    /**
     * 线程局部变量（ThreadLocal）使得Looper与线程能够一一对应。
     * 使用static修饰是因为prepare()/loop()均为静态方法
     * 使用final修饰则是因为对应关系在建立之后便需要保持不变。
     */
    static final ThreadLocal<Looper> sThreadLocal = new ThreadLocal<Looper>();
    /**在prepareMainLooper中记录下主线程的Looper,使getMainLooper能在任意线程中获得主线程的Looper**/
    private static Looper sMainLooper;  // guarded by Looper.class

    /**记录Looper对象的消息队列，创建后不可更改**/
    final MessageQueue mQueue;
    /**记录创建该Looper对象的线程，用于getThread()等从sThreadLocal取出线程后核对**/
    final Thread mThread;

    private Printer mLogging;

    /** 为当前线程创建对应的Looper,应在loop()之前调用。此方法创建的Looper都是可以终止的。**/
    public static void prepare() {
        prepare(true);
    }

    /**每个线程最多只能与一个Looper对应。**/
    private static void prepare(boolean quitAllowed) {
        if (sThreadLocal.get() != null) {
            throw new RuntimeException("Only one Looper may be created per thread");
        }
        sThreadLocal.set(new Looper(quitAllowed));
    }

    /**
     * 为当前线程创建对应的Looper,使用该方法创建的Looper作为整个应用的主Looper，主Looper的消息队列不允许退出。
     * 主Looper应由android在UI线程中创建，开发者请勿使用该方法。想要为非主线程创建Looper,请使用
     * Looper.prepare()
     */
    public static void prepareMainLooper() {
        prepare(false);
        synchronized (Looper.class) {
            if (sMainLooper != null) {
                throw new IllegalStateException("The main Looper has already been prepared.");
            }
            sMainLooper = myLooper();
        }
    }

    /** Returns the application's main looper, which lives in the main thread of the application.
     */
    public static Looper getMainLooper() {
        synchronized (Looper.class) {
            return sMainLooper;
        }
    }

    /**
     * 在当前线程中执行消息循环。确保在当前线程中保存有对应的Looper对象lI，并在调用looper()之后于其
     * 他线程中通过lI.quit()/lI.quitSafely()终止loop()的死循环。
     *
     */
    public static void loop() {
        final Looper me = myLooper();//得到当前线程对应的Looper对象
        if (me == null) {
            throw new RuntimeException("No Looper; Looper.prepare() wasn't called on this thread.");
        }
        final MessageQueue queue = me.mQueue;

        // Make sure the identity of this thread is that of the local process,
        // and keep track of what that identity token actually is.
        Binder.clearCallingIdentity();
        final long ident = Binder.clearCallingIdentity();

        for (; ; ) {
            Message msg = queue.next(); // 可能产生线程阻塞
            if (msg == null) {
                // 如果msg是null，表示消息队列正在退出或者已经被废弃
                return;
            }

            // This must be in a local variable, in case a UI event sets the logger
            Printer logging = me.mLogging;
            if (logging != null) {
                logging.println(">>>>> Dispatching to " + msg.target + " " +
                        msg.callback + ": " + msg.what);
            }

            //执行msg绑定的Runnable 或者 调用target的handleMessage()
            msg.target.dispatchMessage(msg);

            if (logging != null) {
                logging.println("<<<<< Finished to " + msg.target + " " + msg.callback);
            }

            // Make sure that during the course of dispatching the
            // identity of the thread wasn't corrupted.
            final long newIdent = Binder.clearCallingIdentity();
            if (ident != newIdent) {
                Log.wtf(TAG, "Thread identity changed from 0x"
                        + Long.toHexString(ident) + " to 0x"
                        + Long.toHexString(newIdent) + " while dispatching to "
                        + msg.target.getClass().getName() + " "
                        + msg.callback + " what=" + msg.what);
            }

            //不检查状态，直接回收消息
            msg.recycleUnchecked();
        }
    }

    /**
     * Return the Looper object associated with the current thread.  Returns
     * null if the calling thread is not associated with a Looper.
     */
    public static Looper myLooper() {
        return sThreadLocal.get();
    }

    /**
     * Control logging of messages as they are processed by this Looper.  If
     * enabled, a log message will be written to <var>printer</var>
     * at the beginning and ending of each message dispatch, identifying the
     * target Handler and message contents.
     *
     * @param printer A Printer object that will receive log messages, or
     * null to disable message logging.
     */
    public void setMessageLogging(Printer printer) {
        mLogging = printer;
    }

    /**
     * Return the {@link MessageQueue} object associated with the current
     * thread.  This must be called from a thread running a Looper, or a
     * NullPointerException will be thrown.
     */
    public static MessageQueue myQueue() {
        return myLooper().mQueue;
    }

    private Looper(boolean quitAllowed) {
        mQueue = new MessageQueue(quitAllowed);
        mThread = Thread.currentThread();
    }

    /**
     * Returns true if the current thread is this looper's thread.
     * @hide
     */
    public boolean isCurrentThread() {
        return Thread.currentThread() == mThread;
    }

    /**
     *  终止Looper。如想安全终止Looper，请参考lI.quitSafely()
     * <p>调用该方法将会使得loop(）在下一次循环时立刻终止，无论终止时MessageQueue中是否还有尚未处
     * 理的消息。这之后无论以何种方式发布（post）消息都将会失败，譬如Handler#sendMessage(Message)会
     * 返回false。</p>
     * <p>
     * 调用这个方法时，可能有一些消息在Looper终止前都不会被交付（delivery） ，因而这个方法并不安全。
     * 考虑使用{@link #quitSafely}方法替代，从而保证所有本应执行完的工作能够有条不紊地执行完再结束Looper。
     * </p>
     */
    public void quit() {
        mQueue.quit(false);
    }

    /**
     * 安全地终止Looper。
     * 调用该方法后，截止调用时刻的所有消息都能够如常被交付（delivery），而晚于该时刻的消息尽数被丢弃。
     *  一旦处理完符合时刻的所有消息，loop()便会在下一次循环时终止。这之后无论以何种方式发布（post）消息都将
     *  会失败，譬如Handler#sendMessage(Message)会返回false。
     */
    public void quitSafely() {
        mQueue.quit(true);
    }

    /**
     *  在Looper的消息队列设置一个同步障碍器。同步！同步！同步！
     *  调用该方法后，消息队列处理消息时将跳过所有的同步消息，只执行异步消息（可使用
     *  Message#isAsynchronous()判断消息是否是异步的）。该方法返回一个token值，将这个token值放
     *  入Looper#removeSyncBarrier()即可。
     *
     *  Tips :
     *  1.在设置同步障碍器之后，所有新发布(post)的同步消息可入队，但同样不被执行。而新发布(post)的异步
     *  消息都如常入队、如常执行。
     *  2.Looper.postSyncBarrier()与Looper#removeSyncBarrier()必须，必须，必须成对出现，否则将会造
     *  成线程悬挂。
     *
     */
    public int postSyncBarrier() {
        return mQueue.enqueueSyncBarrier(SystemClock.uptimeMillis());
    }


    /**
     * 移除Looper的消息队列中token指定的同步障碍器。
     */
    public void removeSyncBarrier(int token) {
        mQueue.removeSyncBarrier(token);
    }

    /**
     * Return the Thread associated with this Looper.
     */
    public Thread getThread() {
        return mThread;
    }

    /** @hide */
    public MessageQueue getQueue() {
        return mQueue;
    }

    /**
     * Return whether this looper's thread is currently idle（闲置的）, waiting for new work
     * to do.  This is intrinsically racy, since its state can change before you get
     * the result back.
     * @hide
     */
    public boolean isIdling() {
        return mQueue.isIdling();
    }

    public void dump(Printer pw, String prefix) {
        pw.println(prefix + toString());
        mQueue.dump(pw, prefix + "  ");
    }

    public String toString() {
        return "Looper (" + mThread.getName() + ", tid " + mThread.getId()
                + ") {" + Integer.toHexString(System.identityHashCode(this)) + "}";
    }
}
