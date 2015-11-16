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

import java.lang.reflect.Modifier;

/**
 * Hanlder允许开发者往线程关联的{@link MessageQueue}发送{@link Message}和Runnable对象。每个
 * Handler对象只和一个线程以及该线程的消息队列绑定。当开发者创建一个Handler对象后，这个Handler
 * 对象便会立即绑定到<em> 创建它 </em>的线程/消息队列中。Handler对象负责交付（deliver）Message
 * 和Runnable给绑定的消息队列，同时执行从消息队列中出队的Message和Runnable。
 *
 * <p>
 *     Handler有两种主要用法：
 *     1)  安排Message和Runnable对象在某一时刻被执行；   ----  Message和Runnable
 *     2)  入队一个将在其他线程执行的操作(action)。 ---- Runnable
 * </p>
 *
 * <p>
 * 安排消息通过以下方法完成：{@link #post}, {@link #postAtTime(Runnable, long)},
 * {@link #postDelayed},{@link #sendEmptyMessage},{@link #sendMessage},
 * {@link #sendMessageAtTime},{@link #sendMessageDelayed}。
 * <em> post </em>系列的方法允许将Runnable对象加入Handler绑定的消息队列，这个Runnable对
 * 象的{@link Runnable#run()}将会在出队（从消息队列）时被调用。<em> send Message</em>系列的
 * 方法允许将包含Bundle对象的{@link Message}加入Handler绑定的消息队列中，这个Message对象会在
 * 出队时被Handler对象的{@link #handleMessage(Message)}处理（Handle类默认的handleMessage方
 * 法是空方法，如果需要处理Message请继承Handler并重写handleMessage方法）。
 * </p>
 *
 *<p>
 *     当通过post或者send发布Handler时，开发者可以选择：
 *     1) 将Message或者Runnable定义为实时的，使消息队列在条件允许时立即处理；
 *     或者
 *     2)将Message或者Runnable定义为延时的（需指定延时时间或者绝对时间），使消息队列到指定时刻时
 *     再处理
 *     第二个选择允许开发实现超时、ticks（？）和其他基于时序的操作。
 *</p>
 *
 * <p>
 * 当应用进程被创建时，应用的主线程专门用于运行一个特殊的消息队列。这个消息队列用于管理顶级应用对
 * 象(activities, broadcast receivers等) 和 所有这些对象创建的窗口。开发者可以创建自己的线程，并且通过
 * Handler与主应用线程进行通信。通信方式同样是使用<em> post </em>系列和<em> sendMessage </em>
 * 系列方法，区别在于调用线程是开始者自行创建的线程。上述方法给定的Runnable或者Message对象会被
 * Handler对象的消息队列调度并且在适当的时候处理。
 * </p>
 */
public class Handler {
    /**
     *将这个标识位设置为true，表示程序需要检测Handler的子类中非静态的 匿名子类、局部子类或者成员子类。
     * 这些类可能造成泄露。
     * Set this flag to true to detect anonymous, local or member classes
     * that extend this Handler class and that are not static. These kind
     * of classes can potentially create leaks.
     */
    private static final boolean FIND_POTENTIAL_LEAKS = false;
    private static final String TAG = "Handler";

    /**
     * 回调接口。如果要处理Message对象，通常要定义一个Handler的子类并重写子类的
     * {@link #handleMessage(Message)}。而使用Callback接口可以避免继承，方法是在构造Handler对象
     * 时，传入一个Callback实现类对象（例如{@link #Handler(Callback)}）。在Handler处理Message对象
     * 时，首先会检测是否提供了Callback对象，如果提供了则直接调用Callback对象的
     * {@link android.os.Handler.Callback#handleMessage(Message)}，没有则调用
     * {@link #handleMessage(Message)}。
     *
     * @param msg {@link android.os.Message Message}对象
     * @return 返回true，如果不需要更深层次的处理。返回false则会继续调用{@link #handleMessage(Message)}
     */
    public interface Callback {
        /**
         * @param msg {@link android.os.Message Message}对象
         * @return 返回true，如果不需要更深层次的处理。返回false则会继续调用{@link #handleMessage(Message)}
         */
        public boolean handleMessage(Message msg);
    }
    
    /**
     *为了处理消息，子类必须重写这个方法。
     */
    public void handleMessage(Message msg) {
    }
    
    /**
     * 分发Message
     */
    public void dispatchMessage(Message msg) {
        if (msg.callback != null) {
            handleCallback(msg);
        } else {
            if (mCallback != null) {
                if (mCallback.handleMessage(msg)) {
                    return;
                }
            }
            handleMessage(msg);
        }
    }

    /**
     * 默认构造方法会将Handler和当前线程的{@link Looper}绑定。如果当前线程没有looper，Handler将
     * 没有能力接收Message。所以此时会抛出一个RuntimeException。
     */
    public Handler() {
        this(null, false);
    }

    /**
     * 构造方法会将Handler和当前线程的{@link Looper}绑定，并且为Handler设置一个Callback参数以便在
     * 处理Message时回调Callback参数的{@link android.os.Handler.Callback#handleMessage(Message)}。
     * 如果当前线程没有looper，Handler将 没有能力接收Message。所以此时会抛出一个RuntimeException。
     *
     * @param callback 用于处理Message的Callback接口实现类对象或者null。
     */
    public Handler(Callback callback) {
        this(callback, false);
    }

    /**
     * 用提供的{@link Looper}对象替代当前线程的Looper与Handler绑定。
     *
     * @param looper 与Handler绑定的looper，不能为null。
     */
    public Handler(Looper looper) {
        this(looper, null, false);
    }

    /**
     * 用提供的{@link Looper}对象替代当前线程的Looper与Handler绑定 ，并且为Handler设置一个Callback
     * 参数以便在处理Message时回调Callback参数的{@link android.os.Handler.Callback#handleMessage(Message)}。
     *
     * @param looper 与Handler绑定的looper，不能为null；
     * @param callback 用于处理Message的Callback接口实现类对象或者null。
     */
    public Handler(Looper looper, Callback callback) {
        this(looper, callback, false);
    }

    /**
     * 构造方法会将Handler和当前线程的{@link Looper}绑定，并且根据参数async设定Handler是否需要是
     * 异步的。
     *
     * 默认的Handler都是同步的，除非构造方法中特别指明构造的Handler需要是异步的。
     * Handlers are synchronous by default unless this constructor is used to make
     * one that is strictly asynchronous.
     *
     * 有别于同步消息必须按照顺序处理，异步消息（比如中断消息和事件消息）并不要求处理时是有序的。异
     * 步消息不会被{@link MessageQueue#enqueueSyncBarrier(long)}设置的同步障碍器干扰。
     *
     * @param async 如果为true，Handler会调用{@link Message#setAsynchronous(boolean)}将接受到
     *              的所有{@link Message} 和 {@link Runnable}设置为异步Message。
     * @hide
     */
    public Handler(boolean async) {
        this(null, async);
    }

    /**
     * 构造方法会将Handler和当前线程的{@link Looper}绑定，为Handler设置一个Callback参数以便在处理
     * Message时回调Callback参数的{@link android.os.Handler.Callback#handleMessage(Message)}，
     * 并且根据参数async设定Handler是否需要是异步的。
     *
     * 默认的Handler都是同步的，除非构造方法中特别指明构造的Handler需要是异步的。
     * Handlers are synchronous by default unless this constructor is used to make
     * one that is strictly asynchronous.
     *
     * 有别于同步消息必须按照顺序处理，异步消息（比如中断消息和事件消息）并不要求处理时是有序的。异
     * 步消息不会被{@link MessageQueue#enqueueSyncBarrier(long)}设置的同步障碍器干扰。
     *
     * @param callback 用于处理Message的Callback接口实现类对象或者null；
     * @param async 如果为true，Handler会调用{@link Message#setAsynchronous(boolean)}将接受到
     *              的所有{@link Message} 和 {@link Runnable}设置为异步Message。
     *
     * @hide
     */
    public Handler(Callback callback, boolean async) {
        if (FIND_POTENTIAL_LEAKS) {  //是否检测类性质
            final Class<? extends Handler> klass = getClass();
            if ((klass.isAnonymousClass() || klass.isMemberClass() || klass.isLocalClass()) &&
                    (klass.getModifiers() & Modifier.STATIC) == 0) {
                Log.w(TAG, "The following Handler class should be static or leaks might occur: " +
                    klass.getCanonicalName());
            }
        }

        mLooper = Looper.myLooper();
        if (mLooper == null) {
            throw new RuntimeException(
                "Can't create handler inside thread that has not called Looper.prepare()");
        }
        mQueue = mLooper.mQueue;
        mCallback = callback;
        mAsynchronous = async;
    }

    /**
     * 用提供的{@link Looper}对象替代当前线程的Looper与Handler绑定 ，为Handler设置一个Callback参数以
     * 便在处理Message时回调Callback参数的{@link android.os.Handler.Callback#handleMessage(Message)}，
     * 并且根据参数async设定Handler是否需要是异步的。
     *
     * 默认的Handler都是同步的，除非构造方法中特别指明构造的Handler需要是异步的。
     * Handlers are synchronous by default unless this constructor is used to make
     * one that is strictly asynchronous.
     *
     * 有别于同步消息必须按照顺序处理，异步消息（比如中断消息和事件消息）并不要求处理时是有序的。异
     * 步消息不会被{@link MessageQueue#enqueueSyncBarrier(long)}设置的同步障碍器干扰。
     *
     * @param looper 与Handler绑定的looper，不能为null；
     * @param callback 用于处理Message的Callback接口实现类对象或者null；
     * @param async 如果为true，Handler会调用{@link Message#setAsynchronous(boolean)}将接受到
     *              的所有{@link Message} 和 {@link Runnable}设置为异步Message。
     *
     * @hide
     */
    public Handler(Looper looper, Callback callback, boolean async) {
        mLooper = looper;
        mQueue = looper.mQueue;
        mCallback = callback;
        mAsynchronous = async;
    }

    /**
     * 返回指定Message的名称。如果Message的<em> callback </em>字段不为空，则返回<em> callback </em>
     * 的类名称；为空，则返回<em> what </em>字段的十六进制字符串。
     *  
     * @param message 待查询名称的Message
     */
    public String getMessageName(Message message) {
        if (message.callback != null) {
            return message.callback.getClass().getName();
        }
        return "0x" + Integer.toHexString(message.what);
    }

    /*********************  obtainMessage系列，均将this设置为所获取消息target  *********************/
    /*********************  obtainMessage系列，均将this设置为所获取消息target  *********************/
    /*********************  obtainMessage系列，均将this设置为所获取消息target  *********************/

    /**
     * Returns a new {@link android.os.Message Message} from the global message pool. More efficient than
     * creating and allocating new instances. The retrieved message has its handler set to this instance (Message.target == this).
     *  If you don't want that facility, just call Message.obtain() instead.
     */
    public final Message obtainMessage()
    {
        return Message.obtain(this);
    }

    /**
     * Same as {@link #obtainMessage()}, except that it also sets the what member of the returned Message.
     * 
     * @param what Value to assign to the returned Message.what field.
     * @return A Message from the global message pool.
     */
    public final Message obtainMessage(int what)
    {
        return Message.obtain(this, what);
    }
    
    /**
     * 
     * Same as {@link #obtainMessage()}, except that it also sets the what and obj members 
     * of the returned Message.
     * 
     * @param what Value to assign to the returned Message.what field.
     * @param obj Value to assign to the returned Message.obj field.
     * @return A Message from the global message pool.
     */
    public final Message obtainMessage(int what, Object obj)
    {
        return Message.obtain(this, what, obj);
    }

    /**
     * 
     * Same as {@link #obtainMessage()}, except that it also sets the what, arg1 and arg2 members of the returned
     * Message.
     * @param what Value to assign to the returned Message.what field.
     * @param arg1 Value to assign to the returned Message.arg1 field.
     * @param arg2 Value to assign to the returned Message.arg2 field.
     * @return A Message from the global message pool.
     */
    public final Message obtainMessage(int what, int arg1, int arg2)
    {
        return Message.obtain(this, what, arg1, arg2);
    }
    
    /**
     * 
     * Same as {@link #obtainMessage()}, except that it also sets the what, obj, arg1,and arg2 values on the 
     * returned Message.
     * @param what Value to assign to the returned Message.what field.
     * @param arg1 Value to assign to the returned Message.arg1 field.
     * @param arg2 Value to assign to the returned Message.arg2 field.
     * @param obj Value to assign to the returned Message.obj field.
     * @return A Message from the global message pool.
     */
    public final Message obtainMessage(int what, int arg1, int arg2, Object obj)
    {
        return Message.obtain(this, what, arg1, arg2, obj);
    }


    /********************************  post系列，用于发布Runnable    *********************************/
    /********************************  post系列，用于发布Runnable    *********************************/
    /********************************  post系列，用于发布Runnable    ********************************
     * 1）作用：把Runnable封装成Message，并加入消息队列中。参数有别，自行区分；
     * 2）特点：发布（post）的Runnable都会在Handler绑定的线程执行；
     * 3）返回：<em> true </em> Runnable成功入队消息队列，否则返回false。失败的原因通常是处理
     * 消息的队列正在退出。
     * 4）注意
     *       a.uptimeMillis，是自系统启动开始的非深度睡眠时间到指定时间的毫秒数。并非日常生活中所用时间。
     *       b.返回true不代表Runnable一定会被执行（比如未到交付时间looper就退出）。
     */

    /**
     * Causes the Runnable r to be added to the message queue.
     * The runnable will be run on the thread to which this handler is 
     * attached. 
     *  
     * @param r The Runnable that will be executed.
     * 
     * @return <em> true </em> Runnable成功入队消息队列，否则返回false。失败的原因通常是处理
     * 消息的队列正在退出。
     */
    public final boolean post(Runnable r)
    {
       return  sendMessageDelayed(getPostMessage(r), 0);
    }
    
    /**
     * Causes the Runnable r to be added to the message queue, to be run
     * at a specific time given by <var>uptimeMillis</var>.
     * <b>The time-base is {@link android.os.SystemClock#uptimeMillis}.</b>
     * Time spent in deep sleep will add an additional delay to execution.
     * The runnable will be run on the thread to which this handler is attached.
     *
     * @param r The Runnable that will be executed.
     * @param uptimeMillis The absolute time at which the callback should run,
     *         using the {@link android.os.SystemClock#uptimeMillis} time-base.
     *  
     * @return Returns true if the Runnable was successfully placed in to the 
     *         message queue.  Returns false on failure, usually because the
     *         looper processing the message queue is exiting.  Note that a
     *         result of true does not mean the Runnable will be processed -- if
     *         the looper is quit before the delivery time of the message
     *         occurs then the message will be dropped.
     */
    public final boolean postAtTime(Runnable r, long uptimeMillis)
    {
        return sendMessageAtTime(getPostMessage(r), uptimeMillis);
    }
    
    /**
     * Causes the Runnable r to be added to the message queue, to be run
     * at a specific time given by <var>uptimeMillis</var>.
     * <b>The time-base is {@link android.os.SystemClock#uptimeMillis}.</b>
     * Time spent in deep sleep will add an additional delay to execution.
     * The runnable will be run on the thread to which this handler is attached.
     *
     * @param r The Runnable that will be executed.
     * @param uptimeMillis The absolute time at which the callback should run,
     *         using the {@link android.os.SystemClock#uptimeMillis} time-base.
     * 
     * @return Returns true if the Runnable was successfully placed in to the 
     *         message queue.  Returns false on failure, usually because the
     *         looper processing the message queue is exiting.  Note that a
     *         result of true does not mean the Runnable will be processed -- if
     *         the looper is quit before the delivery time of the message
     *         occurs then the message will be dropped.
     *         
     * @see android.os.SystemClock#uptimeMillis
     */
    public final boolean postAtTime(Runnable r, Object token, long uptimeMillis)
    {
        return sendMessageAtTime(getPostMessage(r, token), uptimeMillis);
    }
    
    /**
     * Causes the Runnable r to be added to the message queue, to be run
     * after the specified amount of time elapses.
     * The runnable will be run on the thread to which this handler
     * is attached.
     * <b>The time-base is {@link android.os.SystemClock#uptimeMillis}.</b>
     * Time spent in deep sleep will add an additional delay to execution.
     *  
     * @param r The Runnable that will be executed.
     * @param delayMillis The delay (in milliseconds) until the Runnable
     *        will be executed.
     *        
     * @return Returns true if the Runnable was successfully placed in to the 
     *         message queue.  Returns false on failure, usually because the
     *         looper processing the message queue is exiting.  Note that a
     *         result of true does not mean the Runnable will be processed --
     *         if the looper is quit before the delivery time of the message
     *         occurs then the message will be dropped.
     */
    public final boolean postDelayed(Runnable r, long delayMillis)
    {
        return sendMessageDelayed(getPostMessage(r), delayMillis);
    }
    
    /**
     * 发布一个Runnable实现类对象，并且让这个Runnable在消息队列的下一次迭代便执行【因为
     * enqueueMessage(queue, msg, 0)中的0】。
     * Runnable会在Handler绑定的线程被执行。
     *
     * <b>这个方法仅用于非常特殊的情况。 因为它很容易饿死(? starve)消息循环，导致顺序问题或者其他不
     * 可预见的副作用。</b>
     * <b>
     * This method is only for use in very special circumstances -- it
     * can easily starve the message queue, cause ordering problems, or have
     * other unexpected side-effects.</b>
     *  
     * @param r The Runnable that will be executed.
     * 
     * @return Returns true if the message was successfully placed in to the 
     *         message queue.  Returns false on failure, usually because the
     *         looper processing the message queue is exiting.
     */
    public final boolean postAtFrontOfQueue(Runnable r)
    {
        return sendMessageAtFrontOfQueue(getPostMessage(r));
    }

    /**
     * 看不明白……
     * Runs the specified task synchronously.
     * <p>
     * If the current thread is the same as the handler thread, then the runnable
     * runs immediately without being enqueued.  Otherwise, posts the runnable
     * to the handler and waits for it to complete before returning.
     * </p><p>
     * This method is dangerous!  Improper use can result in deadlocks.
     * Never call this method while any locks are held or use it in a
     * possibly re-entrant manner.
     * </p><p>
     * This method is occasionally useful in situations where a background thread
     * must synchronously await completion of a task that must run on the
     * handler's thread.  However, this problem is often a symptom of bad design.
     * Consider improving the design (if possible) before resorting to this method.
     * </p><p>
     * One example of where you might want to use this method is when you just
     * set up a Handler thread and need to perform some initialization steps on
     * it before continuing execution.
     * </p><p>
     * If timeout occurs then this method returns <code>false</code> but the runnable
     * will remain posted on the handler and may already be in progress or
     * complete at a later time.
     * </p><p>
     * When using this method, be sure to use {@link Looper#quitSafely} when
     * quitting the looper.  Otherwise {@link #runWithScissors} may hang indefinitely.
     * (TODO: We should fix this by making MessageQueue aware of blocking runnables.)
     * </p>
     *
     * @param r The Runnable that will be executed synchronously.
     * @param timeout The timeout in milliseconds, or 0 to wait indefinitely.
     *
     * @return Returns true if the Runnable was successfully executed.
     *         Returns false on failure, usually because the
     *         looper processing the message queue is exiting.
     *
     * @hide This method is prone to abuse and should probably not be in the API.
     * If we ever do make it part of the API, we might want to rename it to something
     * less funny like runUnsafe().
     */
    public final boolean runWithScissors(final Runnable r, long timeout) {
        if (r == null) {
            throw new IllegalArgumentException("runnable must not be null");
        }
        if (timeout < 0) {
            throw new IllegalArgumentException("timeout must be non-negative");
        }

        if (Looper.myLooper() == mLooper) {
            r.run();
            return true;
        }

        BlockingRunnable br = new BlockingRunnable(r);
        return br.postAndWait(this, timeout);
    }

    /**
     * Remove any pending posts of Runnable r that are in the message queue.
     */
    public final void removeCallbacks(Runnable r)
    {
        mQueue.removeMessages(this, r, null);
    }

    /**
     * 从消息队列中移除所有callback等于参数r 且 object 等于 参数token的待处理消息。<b>如果参数token
     * 为null，则移除callback等于参数r的待处理消息。</b>
     */
    public final void removeCallbacks(Runnable r, Object token)
    {
        mQueue.removeMessages(this, r, token);
    }

    /******************************* send系列，用于发送Message  ************************************/
    /******************************* send系列，用于发送Message  ************************************/
    /******************************* send系列，用于发送Message  ***********************************
     *1）作用：将Message加入消息队列中，位置根据各自给定的时间来定。
     *2）特点：发送（send）的Message都会在Handler绑定的线程中，被Handler的handleMessage()处理；
     *3）返回：<em> true </em> Runnable成功入队消息队列，否则返回false。失败的原因通常是处理
     * 消息的队列正在退出。
     *4）注意：
     *       a.如果构造的消息when相同，则越后进入消息队列的消息排在越后面。
     *       b.uptimeMillis，是自系统启动开始的非深度睡眠时间到指定时间的毫秒数。并非日常生活中所用时间。
     *       c.返回true不代表Runnable一定会被执行（比如未到交付时间looper就退出）。
     */

    /**
     * Pushes a message onto the end of the message queue after all pending messages
     * before the current time. It will be received in {@link #handleMessage},
     * in the thread attached to this handler.
     *  
     * @return Returns true if the message was successfully placed in to the 
     *         message queue.  Returns false on failure, usually because the
     *         looper processing the message queue is exiting.
     */
    public final boolean sendMessage(Message msg)
    {
        return sendMessageDelayed(msg, 0);
    }

    /**
     * Sends a Message containing only the what value.
     *  
     * @return Returns true if the message was successfully placed in to the 
     *         message queue.  Returns false on failure, usually because the
     *         looper processing the message queue is exiting.
     */
    public final boolean sendEmptyMessage(int what)
    {
        return sendEmptyMessageDelayed(what, 0);
    }

    /**
     * Sends a Message containing only the what value, to be delivered
     * after the specified amount of time elapses.
     * @see #sendMessageDelayed(android.os.Message, long) 
     * 
     * @return Returns true if the message was successfully placed in to the 
     *         message queue.  Returns false on failure, usually because the
     *         looper processing the message queue is exiting.
     */
    public final boolean sendEmptyMessageDelayed(int what, long delayMillis) {
        Message msg = Message.obtain();
        msg.what = what;
        return sendMessageDelayed(msg, delayMillis);
    }

    /**
     * Sends a Message containing only the what value, to be delivered 
     * at a specific time.
     * @see #sendMessageAtTime(android.os.Message, long)
     *  
     * @return Returns true if the message was successfully placed in to the 
     *         message queue.  Returns false on failure, usually because the
     *         looper processing the message queue is exiting.
     */

    public final boolean sendEmptyMessageAtTime(int what, long uptimeMillis) {
        Message msg = Message.obtain();
        msg.what = what;
        return sendMessageAtTime(msg, uptimeMillis);
    }

    /**
     * Enqueue a message into the message queue after all pending messages
     * before (current time + delayMillis). You will receive it in
     * {@link #handleMessage}, in the thread attached to this handler.
     *  
     * @return Returns true if the message was successfully placed in to the 
     *         message queue.  Returns false on failure, usually because the
     *         looper processing the message queue is exiting.  Note that a
     *         result of true does not mean the message will be processed -- if
     *         the looper is quit before the delivery time of the message
     *         occurs then the message will be dropped.
     */
    public final boolean sendMessageDelayed(Message msg, long delayMillis)
    {
        if (delayMillis < 0) {
            delayMillis = 0;
        }
        return sendMessageAtTime(msg, SystemClock.uptimeMillis() + delayMillis);
    }

    /**
     * Enqueue a message into the message queue after all pending messages
     * before the absolute time (in milliseconds) <var>uptimeMillis</var>.
     * <b>The time-base is {@link android.os.SystemClock#uptimeMillis}.</b>
     * Time spent in deep sleep will add an additional delay to execution.
     * You will receive it in {@link #handleMessage}, in the thread attached
     * to this handler.
     * 
     * @param uptimeMillis The absolute time at which the message should be
     *         delivered, using the
     *         {@link android.os.SystemClock#uptimeMillis} time-base.
     *         
     * @return Returns true if the message was successfully placed in to the 
     *         message queue.  Returns false on failure, usually because the
     *         looper processing the message queue is exiting.  Note that a
     *         result of true does not mean the message will be processed -- if
     *         the looper is quit before the delivery time of the message
     *         occurs then the message will be dropped.
     */
    public boolean sendMessageAtTime(Message msg, long uptimeMillis) {
        MessageQueue queue = mQueue;
        if (queue == null) {
            RuntimeException e = new RuntimeException(
                    this + " sendMessageAtTime() called with no mQueue");
            Log.w("Looper", e.getMessage(), e);
            return false;
        }
        return enqueueMessage(queue, msg, uptimeMillis);
    }

    /**
     * Enqueue a message at the front of the message queue, to be processed on
     * the next iteration of the message loop.  You will receive it in
     * {@link #handleMessage}, in the thread attached to this handler.
     *
     *<b>这个方法仅用于非常特殊的情况。 因为它很容易饿死(? starve)消息循环，导致顺序问题或者其他不
     * 可预见的副作用。</b>
     * <b>This method is only for use in very special circumstances -- it
     * can easily starve the message queue, cause ordering problems, or have
     * other unexpected side-effects.</b>
     *  
     * @return Returns true if the message was successfully placed in to the 
     *         message queue.  Returns false on failure, usually because the
     *         looper processing the message queue is exiting.
     */
    public final boolean sendMessageAtFrontOfQueue(Message msg) {
        MessageQueue queue = mQueue;
        if (queue == null) {
            RuntimeException e = new RuntimeException(
                this + " sendMessageAtTime() called with no mQueue");
            Log.w("Looper", e.getMessage(), e);
            return false;
        }
        return enqueueMessage(queue, msg, 0);
    }

    /**
     * 将消息msg加入消息队列queue中，uptimeMillis即为消息的执行时间<em> when </em>。
     */
    private boolean enqueueMessage(MessageQueue queue, Message msg, long uptimeMillis) {
        msg.target = this;
        if (mAsynchronous) {
            msg.setAsynchronous(true);
        }
        return queue.enqueueMessage(msg, uptimeMillis);
    }

    /**
     * Remove any pending posts of messages with code 'what' that are in the
     * message queue.
     */
    public final void removeMessages(int what) {
        mQueue.removeMessages(this, what, null);
    }

    /**
     * 从消息队列中移除所有what字段等于参数what、obj字段等于参数object的待处理消息（Runnable是特
     * 殊的消息）。<b>如果参数object为null，则移除所有what字段等于参数what的待处理消息</b>
     */
    public final void removeMessages(int what, Object object) {
        mQueue.removeMessages(this, what, object);
    }

    /**
     *  从消息队列中移除所有obj字段等于参数object的待处理消息（Runnable是特殊的消息）。<b>如果参
     *  数object为null，则移除所有待处理消息</b>
     */
    public final void removeCallbacksAndMessages(Object token) {
        mQueue.removeCallbacksAndMessages(this, token);
    }

    /**
     * Check if there are any pending posts of messages with code 'what' in
     * the message queue.
     */
    public final boolean hasMessages(int what) {
        return mQueue.hasMessages(this, what, null);
    }

    /**
     * Check if there are any pending posts of messages with code 'what' and
     * whose obj is 'object' in the message queue.
     *
     * @param object 消息所携带的一个任意object数据。可为null，表示该参数不起筛选作用；
     */
    public final boolean hasMessages(int what, Object object) {
        return mQueue.hasMessages(this, what, object);
    }

    /**
     * Check if there are any pending posts of messages with callback r in
     * the message queue.
     * 
     * @hide
     */
    public final boolean hasCallbacks(Runnable r) {
        return mQueue.hasMessages(this, r, null);
    }

    // if we can get rid of this method, the handler need not remember its loop
    // we could instead export a getMessageQueue() method... 
    public final Looper getLooper() {
        return mLooper;
    }

    public final void dump(Printer pw, String prefix) {
        pw.println(prefix + this + " @ " + SystemClock.uptimeMillis());
        if (mLooper == null) {
            pw.println(prefix + "looper uninitialized");
        } else {
            mLooper.dump(pw, prefix + "  ");
        }
    }

    @Override
    public String toString() {
        return "Handler (" + getClass().getName() + ") {"
        + Integer.toHexString(System.identityHashCode(this))
        + "}";
    }

    final IMessenger getIMessenger() {
        synchronized (mQueue) {
            if (mMessenger != null) {
                return mMessenger;
            }
            mMessenger = new MessengerImpl();
            return mMessenger;
        }
    }

    private final class MessengerImpl extends IMessenger.Stub {
        public void send(Message msg) {
            msg.sendingUid = Binder.getCallingUid();
            Handler.this.sendMessage(msg);
        }
    }

    private static Message getPostMessage(Runnable r) {
        Message m = Message.obtain();
        m.callback = r;
        return m;
    }

    private static Message getPostMessage(Runnable r, Object token) {
        Message m = Message.obtain();
        m.obj = token;
        m.callback = r;
        return m;
    }

    private static void handleCallback(Message message) {
        message.callback.run();
    }

    final MessageQueue mQueue;
    final Looper mLooper;
    final Callback mCallback;
    final boolean mAsynchronous;
    IMessenger mMessenger;


    //TODO :了解功能
    private static final class BlockingRunnable implements Runnable {
        private final Runnable mTask;
        private boolean mDone;

        public BlockingRunnable(Runnable task) {
            mTask = task;
        }

        @Override
        public void run() {
            try {
                mTask.run();
            } finally {
                synchronized (this) {
                    mDone = true;
                    notifyAll();
                }
            }
        }

        public boolean postAndWait(Handler handler, long timeout) {
            if (!handler.post(this)) {
                return false;
            }

            synchronized (this) {
                if (timeout > 0) {
                    final long expirationTime = SystemClock.uptimeMillis() + timeout;
                    while (!mDone) {
                        long delay = expirationTime - SystemClock.uptimeMillis();
                        if (delay <= 0) {
                            return false; // timeout
                        }
                        try {
                            wait(delay);
                        } catch (InterruptedException ex) {
                        }
                    }
                } else {
                    while (!mDone) {
                        try {
                            wait();
                        } catch (InterruptedException ex) {
                        }
                    }
                }
            }
            return true;
        }
    }
}
