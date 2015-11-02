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

import java.util.ArrayList;

/**
 * 用于保存{@link Looper}发出的消息列表的低等级类。消息并不直接添加到MessageQueue
 * 中，而是通过{@link Handler}对象与Looper关联。
 * 
 * <p>
 *     使用当前线程的{@link Looper#myQueue() Looper.myQueue()}便可获得MessageQueue。
 *  </p>
 */
public final class MessageQueue {
    /**是否允许消息队列退出**/
    private final boolean mQuitAllowed;

    @SuppressWarnings("unused")
    /**用于本地代码**/
    private long mPtr; // used by native code

    /**消息队列的首部Message**/
    Message mMessages;
    /**IdleHandler列表**/
    private final ArrayList<IdleHandler> mIdleHandlers = new ArrayList<IdleHandler>();
    /**保存等待处理的IdleHandler（闲时任务）**/
    private IdleHandler[] mPendingIdleHandlers;
    /**队列正在退出，等待执行{@link #dispose()}**/
    private boolean mQuitting;

    // Indicates whether next() is blocked waiting in pollOnce() with a non-zero timeout.
    /**next()方法是否阻塞，并在非0的超时时间后进入pollOnce()方法**/
    private boolean mBlocked;

    /**
     * 下一个障碍器的token。
     *  障碍器的target是null，arg1装有token的消息对象。
     */
    private int mNextBarrierToken;

    private native static long nativeInit();
    private native static void nativeDestroy(long ptr);
    private native static void nativePollOnce(long ptr, int timeoutMillis);
    private native static void nativeWake(long ptr);
    private native static boolean nativeIsIdling(long ptr);

    /**
     * 回调接口，当线程准备阻塞以等待更多的消息时调用。
     * 开发者可以实现自己的IdleHandler类，然后通过{@link #addIdleHandler}方法将其添加到MessageQueue
     * 中。一旦MessageQueue的循环线程空闲下来，就会执行这些IdleHandler的
     * {@link IdleHandler#queueIdle IdleHandler.queueIdle()}方法。你可以在这个方法中添加一次操作，
     * 并根据自己的操作觉得返回true还是false。
     */
    public static interface IdleHandler {
        /**
         * 方法在以下两种情况下会被调用：
         * 1.当消息队列处理完消息开始等待消息时，此时队列为空；
         * 2.当队列中依然有待处理的消息，但这些消息的交付（delivery）时刻要晚于当前时刻时；
         *
         *@return  <em>true</em> 队列循环线程在下次执行{@link #next() next()}如果遇到空闲，依然执
         * 行这个IdleHandler（闲时任务） ； <em>false</em> 这次IdleHandler执行完之后就把这个它删除。
         */
        boolean queueIdle();
    }

    /**
     * 将{@link IdleHandler}添加到队列中。当{@link IdleHandler#queueIdle IdleHandler.queueIdle()}
     * 方法返回false时，这个IdleHandler会被自动移除；同样主动调用{@link #removeIdleHandler}也可将其移除。
     * <p>在任何线程调用这个方法都是安全的。
     * 
     * @param handler 待添加的IdleHandler对象
     */
    public void addIdleHandler(IdleHandler handler) {
        if (handler == null) {
            throw new NullPointerException("Can't add a null IdleHandler");
        }
        synchronized (this) {//针对mIdleHandlers的增删加锁
            mIdleHandlers.add(handler);
        }
    }

    /**
     * 从队列中删除一个使用{@link #addIdleHandler}添加进队列的{@link IdleHandler}对象。如果handler
     * 在队列中不存在，则不做任何处理。
     * 
     * @param handler 需要删除的IdleHandler对象
     */
    public void removeIdleHandler(IdleHandler handler) {
        synchronized (this) {
            mIdleHandlers.remove(handler);
        }
    }

    //构造函数
    MessageQueue(boolean quitAllowed) {
        mQuitAllowed = quitAllowed;
        mPtr = nativeInit();
    }

    @Override //慎用finalize()
    protected void finalize() throws Throwable {
        try {
            dispose();
        } finally {
            super.finalize();
        }
    }

    // Disposes of the underlying message queue.
    // Must only be called on the looper thread or the finalizer.
    /**
     * 废弃当前消息队列。仅允许在当前消息队列所绑定的looper线程中或者finalizer调用。
     */
    private void dispose() {
        if (mPtr != 0) {
            nativeDestroy(mPtr);
            mPtr = 0;
        }
    }

    /**
     *
     *
     * @return  <em>null</em> 消息队列已经退出或者被废弃
     */
    Message next() {
        final long ptr = mPtr;
        //quit()、disposed()会将mPtr置为0。
        if (ptr == 0) {
            //应用尝试重启已经退出或者废弃的Looper，则返回null
            return null;  //出口1，非法执行next()
        }

        /**等待处理的IdleHandler个数**/
        int pendingIdleHandlerCount = -1; // -1 only during first iteration
        int nextPollTimeoutMillis = 0;
        for (;;) {
            if (nextPollTimeoutMillis != 0) {
                Binder.flushPendingCommands();
            }

            nativePollOnce(ptr, nextPollTimeoutMillis);
            synchronized (this) {
                // Try to retrieve the next message.  Return if found.
                //now等于自系统启动以来到此时此刻，非深度睡眠的时间
                final long now = SystemClock.uptimeMillis();
                Message prevMsg = null;
                Message msg = mMessages;

                //如果当前队首的消息时设置的同步障碍器。只有同步障碍器target可能为null，因为handler
                //发布（post）消息时会将自己赋值给target。
                if (msg != null && msg.target == null) {
                    // 因为同步障碍器的原因而进入该分支。分支找到下一个异步消息之后才会结束。  Stalled by a barrier.  Find the next asynchronous message in the queue.
                    do {
                        prevMsg = msg;
                        msg = msg.next;
                    } while (msg != null && !msg.isAsynchronous());
                }
                if (msg != null) {
                    if (now < msg.when) {
                        //下一个待处理的消息没有准备好（执行时间未到）。设置超时时间，使消息的
                        // when字段指定的时间到达时唤醒消息。
                        nextPollTimeoutMillis = (int) Math.min(msg.when - now, Integer.MAX_VALUE);
                    } else {
                        //得到一个消息
                        mBlocked = false;//不阻塞线程
                        //选定一个消息
                        if (prevMsg != null) {
                            prevMsg.next = msg.next;
                        } else {
                            mMessages = msg.next;
                        }
                        msg.next = null;
                        if (false) Log.v("MessageQueue", "Returning message: " + msg);
                        return msg;  //出口2，等到下一个待处理的小西斯
                    }
                } else {
                    //到这里则表示消息队列中消息均被处理完
                    nextPollTimeoutMillis = -1;
                }

                //所有待处理的消息均处理完成， 接下来处理退出相关的事务
                if (mQuitting) {//TODO：确定触发时机
                    dispose();
                    return null; //出口3，当前队列正在退出等待废弃
                }

                // If first time idle, then get the number of idlers to run.
                // Idle handles only run if the queue is empty or if the first message
                // in the queue (possibly a barrier) is due to be handled in the future.
                /**
                 * 如果消息队列第一次空闲出来，就获取等待运行的IdleHandler个数。
                 * IdleHandler仅在队列为空 或者 队列第一个消息（可能是障碍器）的执行时刻晚于当前时刻时才执行。
                 */
                if (pendingIdleHandlerCount < 0  //pendingIdleHandlerCount初始值为-1
                        && (mMessages == null || now < mMessages.when)) { //为空或者执行时刻未到
                    pendingIdleHandlerCount = mIdleHandlers.size();
                }
                if (pendingIdleHandlerCount <= 0) {
                    // No idle handlers to run.  Loop and wait some more.
                    mBlocked = true;
                    continue; //!!!!!
                }

                if (mPendingIdleHandlers == null) {
                    mPendingIdleHandlers = new IdleHandler[Math.max(pendingIdleHandlerCount, 4)];
                }
                mPendingIdleHandlers = mIdleHandlers.toArray(mPendingIdleHandlers);
            }//synchronized结束

            // 执行IdleHandler（可理解为：空闲时任务）。
            // 只在第一次迭代时，才能执行到这段代码段。
            for (int i = 0; i < pendingIdleHandlerCount; i++) {
                final IdleHandler idler = mPendingIdleHandlers[i];
                mPendingIdleHandlers[i] = null; //待处理任务即将被处理，将其从待处理数组中删去（置空引用）

                boolean keep = false;
                try {
                    keep = idler.queueIdle();
                } catch (Throwable t) {
                    Log.wtf("MessageQueue", "IdleHandler threw exception", t);
                }

                if (!keep) {
                    synchronized (this) {
                        mIdleHandlers.remove(idler);
                    }
                }
            }

            //将待处理的IdleHandler个数设置为0，使得本次next()的调用再也不会到达这个for循环。
            // （结束在语句if (pendingIdleHandlerCount <= 0)）
            pendingIdleHandlerCount = 0;

            // 在处理IdleHandler时，新的消息可能被发布(post)或者延时消息的交付(delivery)时间已到。
            // 所以在这里，我们不让线程等待而是重新扫描队列中的消息。
            nextPollTimeoutMillis = 0;
        }//for(;;)结束
    }

    /**
     * 退出消息循环 。只允许同一个包的类访问，比如Looper
     * @param safe  是否安全退出。
     */
    void quit(boolean safe) {
        if (!mQuitAllowed) {
            throw new IllegalStateException("Main thread not allowed to quit.");
        }

        synchronized (this) {
            if (mQuitting) {
                return;
            }
            mQuitting = true;

            if (safe) {
                removeAllFutureMessagesLocked();
            } else {
                removeAllMessagesLocked();
            }

            // We can assume mPtr != 0 because mQuitting was previously false.
            nativeWake(mPtr);
        }
    }

    int enqueueSyncBarrier(long when) {
        // Enqueue a new sync barrier token.
        // We don't need to wake the queue because the purpose of a barrier is to stall it.
        synchronized (this) {
            final int token = mNextBarrierToken++;
            final Message msg = Message.obtain();
            msg.markInUse();
            msg.when = when;
            msg.arg1 = token;

            //找到msg在消息队列中的位置（消息队列按照when从小到大排列），并把msg插入其中
            Message prev = null;
            Message p = mMessages;
            if (when != 0) {
                while (p != null && p.when <= when) {
                    prev = p;
                    p = p.next;
                }
            }

            if (prev != null) { // invariant: p == prev.next
                msg.next = p;
                prev.next = msg;
            } else {//如果msg.when最小，或者msg.when=0(可能性不大)
                msg.next = p;
                mMessages = msg;
            }
            return token;
        }
    }

    void removeSyncBarrier(int token) {
        // Remove a sync barrier token from the queue.
        // If the queue is no longer stalled by a barrier then wake it.
        synchronized (this) {
            Message prev = null;
            Message p = mMessages;
            while (p != null && (p.target != null || p.arg1 != token)) {
                prev = p;
                p = p.next;
            }
            if (p == null) {
                throw new IllegalStateException("The specified message queue synchronization "
                        + " barrier token has not been posted or has already been removed.");
            }
            final boolean needWake;
            if (prev != null) {
                prev.next = p.next;
                needWake = false;
            } else {
                mMessages = p.next;
                needWake = mMessages == null || mMessages.target != null;
            }
            p.recycleUnchecked();

            // If the loop is quitting then it is already awake.
            // We can assume mPtr != 0 when mQuitting is false.
            if (needWake && !mQuitting) {
                nativeWake(mPtr);
            }
        }
    }

    boolean enqueueMessage(Message msg, long when) {
        if (msg.target == null) {
            throw new IllegalArgumentException("Message must have a target.");
        }
        if (msg.isInUse()) {
            throw new IllegalStateException(msg + " This message is already in use.");
        }

        synchronized (this) {
            if (mQuitting) {
                IllegalStateException e = new IllegalStateException(
                        msg.target + " sending message to a Handler on a dead thread");
                Log.w("MessageQueue", e.getMessage(), e);
                msg.recycle();
                return false;
            }

            msg.markInUse();
            msg.when = when;
            Message p = mMessages;
            boolean needWake;
            if (p == null || when == 0 || when < p.when) {
                // New head, wake up the event queue if blocked.
                msg.next = p;
                mMessages = msg;
                needWake = mBlocked;
            } else {
                // Inserted within the middle of the queue.  Usually we don't have to wake
                // up the event queue unless there is a barrier at the head of the queue
                // and the message is the earliest asynchronous message in the queue.
                needWake = mBlocked && p.target == null && msg.isAsynchronous();
                Message prev;
                for (;;) {
                    prev = p;
                    p = p.next;
                    if (p == null || when < p.when) {
                        break;
                    }
                    if (needWake && p.isAsynchronous()) {
                        needWake = false;
                    }
                }
                msg.next = p; // invariant: p == prev.next
                prev.next = msg;
            }

            // We can assume mPtr != 0 because mQuitting is false.
            if (needWake) {
                nativeWake(mPtr);
            }
        }
        return true;
    }

    /**
     * 判断消息队列中是否含有符合指定要求的消息
     * @param h 消息的目标Handler；
     * @param what 消息的标识；
     * @param object 消息所携带的一个任意object数据；
     * @return  是否含有符合要求的消息。
     */
    boolean hasMessages(Handler h, int what, Object object) {
        if (h == null) {
            return false;
        }

        synchronized (this) {
            Message p = mMessages;
            while (p != null) {
                if (p.target == h && p.what == what && (object == null || p.obj == object)) {
                    return true;
                }
                p = p.next;
            }
            return false;
        }
    }

    /**
     * 判断消息队列中是否含有符合指定要求的消息
     * @param h 消息的目标Handler；
     * @param r  消息的Runnable对象；
     * @param object  消息所携带的一个任意object数据；
     * @return 是否含有符合要求的消息。
     */
    boolean hasMessages(Handler h, Runnable r, Object object) {
        if (h == null) {
            return false;
        }

        synchronized (this) {
            Message p = mMessages;
            while (p != null) {
                if (p.target == h && p.callback == r && (object == null || p.obj == object)) {
                    return true;
                }
                p = p.next;
            }
            return false;
        }
    }

    boolean isIdling() {
        synchronized (this) {
            return isIdlingLocked();
        }
    }

    private boolean isIdlingLocked() {
        //如果循环正在推退出，那么必定不空闲。
        // We can assume mPtr != 0 when mQuitting is false.
        return !mQuitting && nativeIsIdling(mPtr);
     }


    void removeMessages(Handler h, int what, Object object) {
        if (h == null) {
            return;
        }

        synchronized (this) {
            Message p = mMessages;

            // Remove all messages at front.
            while (p != null && p.target == h && p.what == what
                   && (object == null || p.obj == object)) {
                Message n = p.next;
                mMessages = n;
                p.recycleUnchecked();
                p = n;
            }

            // Remove all messages after front.
            while (p != null) {
                Message n = p.next;
                if (n != null) {
                    if (n.target == h && n.what == what
                        && (object == null || n.obj == object)) {
                        Message nn = n.next;
                        n.recycleUnchecked();
                        p.next = nn;
                        continue;
                    }
                }
                p = n;
            }
        }
    }

    void removeMessages(Handler h, Runnable r, Object object) {
        if (h == null || r == null) {
            return;
        }

        synchronized (this) {
            Message p = mMessages;

            // Remove all messages at front.
            while (p != null && p.target == h && p.callback == r
                   && (object == null || p.obj == object)) {
                Message n = p.next;
                mMessages = n;
                p.recycleUnchecked();
                p = n;
            }

            // Remove all messages after front.
            while (p != null) {
                Message n = p.next;
                if (n != null) {
                    if (n.target == h && n.callback == r
                        && (object == null || n.obj == object)) {
                        Message nn = n.next;
                        n.recycleUnchecked();
                        p.next = nn;
                        continue;
                    }
                }
                p = n;
            }
        }
    }

    void removeCallbacksAndMessages(Handler h, Object object) {
        if (h == null) {
            return;
        }

        synchronized (this) {
            Message p = mMessages;

            // Remove all messages at front.
            while (p != null && p.target == h
                    && (object == null || p.obj == object)) {
                Message n = p.next;
                mMessages = n;
                p.recycleUnchecked();
                p = n;
            }

            // Remove all messages after front.
            while (p != null) {
                Message n = p.next;
                if (n != null) {
                    if (n.target == h && (object == null || n.obj == object)) {
                        Message nn = n.next;
                        n.recycleUnchecked();
                        p.next = nn;
                        continue;
                    }
                }
                p = n;
            }
        }
    }

    private void removeAllMessagesLocked() {
        Message p = mMessages;
        while (p != null) {
            Message n = p.next;
            p.recycleUnchecked();
            p = n;
        }
        mMessages = null;
    }

    private void removeAllFutureMessagesLocked() {
        final long now = SystemClock.uptimeMillis();
        Message p = mMessages;
        if (p != null) {
            if (p.when > now) {
                removeAllMessagesLocked();
            } else {
                Message n;
                for (;;) {
                    n = p.next;
                    if (n == null) {
                        return;
                    }
                    if (n.when > now) {
                        break;
                    }
                    p = n;
                }
                p.next = null;
                do {
                    p = n;
                    n = p.next;
                    p.recycleUnchecked();
                } while (n != null);
            }
        }
    }

    void dump(Printer pw, String prefix) {
        synchronized (this) {
            long now = SystemClock.uptimeMillis();
            int n = 0;
            for (Message msg = mMessages; msg != null; msg = msg.next) {
                pw.println(prefix + "Message " + n + ": " + msg.toString(now));
                n++;
            }
            pw.println(prefix + "(Total messages: " + n + ", idling=" + isIdlingLocked()
                    + ", quitting=" + mQuitting + ")");
        }
    }
}
