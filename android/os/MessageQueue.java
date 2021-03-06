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
     *得到下一个等待处理的消息。如果当前消息队列为空或者下一个消息延时时间未到则阻塞线程。
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

            //nextPollTimeoutMillis为0立即返回，为-1则无限等待(必须主动唤醒)。ptr是指针，涉及本地方法不深究。
            nativePollOnce(ptr, nextPollTimeoutMillis);
            synchronized (this) {
                // Try to retrieve the next message.  Return if found.
                //now等于自系统启动以来到此时此刻，非深度睡眠的时间
                final long now = SystemClock.uptimeMillis();
                Message prevMsg = null;
                Message msg = mMessages;//队首消息

                //如果当前队首的消息时设置的同步障碍器（target为null）。
                if (msg != null && msg.target == null) {
                    // 因为同步障碍器的原因而进入该分支，找到下一个异步消息之后才会结束while。
                    do {
                        prevMsg = msg;
                        msg = msg.next;
                    } while (msg != null && !msg.isAsynchronous());
                }

                //此时msg一定是普通消息或者null，一定不是同步障碍器
                if (msg != null) {
                    if (now < msg.when) {
                        //队首第一个非障碍器的消息执行时间未到，计算阻塞时长
                        nextPollTimeoutMillis = (int) Math.min(msg.when - now, Integer.MAX_VALUE);
                    } else {//一切正常，开始取消息
                        mBlocked = false;//不阻塞线程
                        if (prevMsg != null) { //如果跳过了队首的同步障碍器取异步消息
                            prevMsg.next = msg.next;
                        } else {//如果当前消息就是队首消息
                            mMessages = msg.next;
                        }
                        msg.next = null;
                        if (false) Log.v("MessageQueue", "Returning message: " + msg);
                        return msg;  //出口2，取出下一个待处理的消息
                    }
                } else { //消息队列为空，或者队首是SyncBarrier且队列中无异步消息
                    nextPollTimeoutMillis = -1;   //-1表示无限等待
                }

                //所有待处理的消息均处理完成， 接下来处理闲时任务

                /*
                *当进入next()时，mQuitting必定为false。但这个false可能在进入synchronized代码块之前修改，
                * 也可能在随后的某次迭代中被修改
                 */
                if (mQuitting) {
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

                //闲时任务列表为空，或者不是第一次执行到这里
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

            // 执行IdleHandler（可理解为：闲时任务）。
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

            //因为执行了闲时任务花费了一段时间（迭代开始处的阻塞方法还未执行到所以还未阻塞），此时再根据之前
            //计算出的阻塞时长阻塞线程显然不合适。
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

    /**
     * 将同步障碍器加入消息队列。如果此时消息队列处于阻塞状态也不需要唤醒，因为障碍器本身的目的就是
     * 阻碍消息队列的循环处理。
     * @param when 同步障碍器从何时起效（这个时间是自系统启动开始算起，到指定时间的不包含深度睡
     *             眠的毫秒数）。
     * @return  新增的同步障碍器token，用于{@link #removeSyncBarrier(int) }移除障碍器时使用
     * */
    int enqueueSyncBarrier(long when) {
        synchronized (this) {
            final int token = mNextBarrierToken++;
            //从消息池取出一个消息，并将其设置为同步障碍器（target为null，且arg1保存token的消息）
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

    /**
     * 移除一个同步障碍器。如果移除之后消息队列不再被同步障碍器拖延则唤醒它。
     * @param token 需要移除的障碍器token，由{@link #enqueueSyncBarrier(long)}返回而得
     */
    void removeSyncBarrier(int token) {
        synchronized (this) {
            Message prev = null;
            Message p = mMessages;
            //找到指定的障碍器
            while (p != null && (p.target != null || p.arg1 != token)) {
                prev = p;
                p = p.next;
            }
            if (p == null) {
                throw new IllegalStateException("The specified message queue synchronization "
                        + " barrier token has not been posted or has already been removed.");
            }
            final boolean needWake;
            //如果找到障碍器时，它有前驱消息。说明这个障碍器还没发挥作用，此时无论消息队列循环是否阻塞
            //都不需要改变其（即消息队列）状态。
            if (prev != null) {
                prev.next = p.next;
                needWake = false;
            } else {//如果障碍器是队首第一个消息
                mMessages = p.next;
                //消息队列为空或者新队首消息不是障碍器时，则唤醒消息队列循环
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

    /**
     * 往消息队列中添加一个消息。
     * @return 是否消息成功加入消息队列
     * @exception  IllegalStateException 状态异常（msg.target为null 或者 msg处于使用状态）
     */
    boolean enqueueMessage(Message msg, long when) {
        if (msg.target == null) { //如果target为null，一则会被当成障碍器，二则交付时没有交付方
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
            //如果队列首部为null，或者入队消息需要马上执行，或者入队消息执行时间早于队首消息，且线程已阻塞则都需要唤醒。
            //如果 p!=null&&when!=0&&when>p.when，则不需要唤醒。
            if (p == null || when == 0 || when < p.when) {
                // New head, wake up the event queue if blocked.
                msg.next = p;
                mMessages = msg;
                needWake = mBlocked;//mBlocked记录消息循环是否阻塞
            } else {
                /*在队列中间插入一个消息。一般情况下不需要唤醒队列（不是加到队首为什么要唤醒呢？），除
                 * 非队首是一个同步障碍器而且新插入的消息是 1)异步消息 2)执行时间是队列中最早 时。*/

                //此处mBlocked值需要根据情况决定。当线程已经阻塞且队首消息是同步障碍器是新加入异步消息，needWake
                //才可能(!!)为true。这还要判断消息队列中是否有异步消息，以及异步消息的处理时间早于还是晚于新加入的异步消息。
                needWake = mBlocked && p.target == null && msg.isAsynchronous();//如果是true也是暂时的，还有考验在等着呢！
                //寻找位置
                Message prev;
                for (;;) {
                    prev = p;
                    p = p.next;
                    if (p == null || when < p.when) {
                        break;
                    }

                    if (needWake && p.isAsynchronous()) {
                        //能到达这里，说明msg.when > p.when。既然needWake是true，毫无疑问此时消息队列是
                        //处于阻塞的。这只有一种可能，p这个异步消息的执行时间还没到！msg的执行时间还
                        //更晚（不更晚早break了），那就没有必要唤醒消息队列了。
                        needWake = false;
                    }
                }

                //插入新消息
                msg.next = p; // invariant: p == prev.next
                prev.next = msg;
            }

            // We can assume mPtr != 0 because mQuitting is false.
            if (needWake) {
                nativeWake(mPtr);//唤醒消息循环
            }
        }
        return true;
    }

    /**
     * 判断消息队列中是否含有符合指定要求的消息
     * @param h 消息的目标Handler；
     * @param what 消息的标识；
     * @param object 消息所携带的一个任意object数据。可为null，表示该参数不起筛选作用；
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
     * @param object  消息所携带的一个任意object数据。可为null，表示该参数不起筛选作用；
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

    /**消息循环队列是否空闲**/
    boolean isIdling() {
        synchronized (this) {
            return isIdlingLocked();
        }
    }

    private boolean isIdlingLocked() {
        //如果循环正在退出，那么必定不空闲。
        // We can assume mPtr != 0 when mQuitting is false.
        return !mQuitting && nativeIsIdling(mPtr);
     }

    /**
     *  删除target字段为Handler对象h、what字段等于what、object字段为空<em> 或者 </em>等于Object
     *  对象的所有消息。
     */
    void removeMessages(Handler h, int what, Object object) {
        if (h == null) {
            return;
        }

        synchronized (this) {
            Message p = mMessages;

            // 删除队首开始的所有符合参数要求的消息，直到遇到第一个不符合参数要求的
            while (p != null && p.target == h && p.what == what
                   && (object == null || p.obj == object)) {
                Message n = p.next;
                mMessages = n;
                p.recycleUnchecked();
                p = n;
            }

            // 删除剩余队列中所有符合参数要求的消息
            while (p != null) {//p在上一个while已经证明不符合参数要求
                Message n = p.next;
                if (n != null) {
                    if (n.target == h && n.what == what
                        && (object == null || n.obj == object)) {
                        Message nn = n.next;
                        n.recycleUnchecked();
                        p.next = nn; //把n.next复制给p.next
                        continue;
                    }
                }
                p = n;
            }
        }
    }

    /**
     *  删除target字段为Handler对象h、callback字段等于r、object字段为空<em> 或者 </em>等于Object
     *  对象的所有消息。
     */
    void removeMessages(Handler h, Runnable r, Object object) {
        if (h == null || r == null) {
            return;
        }

        synchronized (this) {
            Message p = mMessages;

            // 删除队首开始的所有符合参数要求的消息，直到遇到第一个不符合参数要求的
            while (p != null && p.target == h && p.callback == r
                   && (object == null || p.obj == object)) {//p在上一个while已经证明不符合参数要求
                Message n = p.next;
                mMessages = n;
                p.recycleUnchecked();
                p = n;
            }

            // 删除剩余队列中所有符合参数要求的消息
            while (p != null) {
                Message n = p.next;
                if (n != null) {
                    if (n.target == h && n.callback == r
                        && (object == null || n.obj == object)) {
                        Message nn = n.next;
                        n.recycleUnchecked();
                        p.next = nn;//把n.next复制给p.next
                        continue;
                    }
                }
                p = n;
            }
        }
    }

    /**
     * 删除target字段为Handler对象h、object字段为空<em> 或者 </em>等于Object对象的所有消息。
     */
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

    /** 删除队列中所有消息 **/
    private void removeAllMessagesLocked() {
        Message p = mMessages;
        while (p != null) {
            Message n = p.next;
            p.recycleUnchecked();
            p = n;
        }
        mMessages = null;
    }

    /**删除队列中，所有执行时间晚于当前时间的消息**/
    private void removeAllFutureMessagesLocked() {
        final long now = SystemClock.uptimeMillis();
        Message p = mMessages;
        if (p != null) {
            if (p.when > now) { //队首的执行时间就大于当前时间
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
