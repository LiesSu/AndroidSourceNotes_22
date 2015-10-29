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

import android.util.TimeUtils;

/**
 *
 * 定义一个包含描述和任意数据的对象，该对象可发送给Handler。它包含两个额外的int变量和一个额外的
 * Object变量，这三个变量允许你在很多场景下不需要再分配。
 *
 * 尽管Message类的构造函数是公共的，但推荐开发者通过Message.obtain()或者Handler#obtainMessage()
 * 系列方法获得Message对象，这些方法都是从一个可回收的对象池中拉取Message对象的。
 */
public final class Message implements Parcelable {
    /**
     * 由开发者定义的消息标识，容易通过该字段辨别消息。因为每个Handler都占有一个用于记录消息标识的
     * 单独命名空间，所以Handler之间的消息标识不会发生冲突。
     */
    public int what;

    /**
     * 当开发者仅仅需要存储几个int值时，推荐使用arg1和arg2替代#setData(Bundle)
     */
    public int arg1;

    /**
     * 当开发者仅仅需要存储几个int值时，推荐使用arg1和arg2替代#setData(Bundle)
     */
    public int arg2;

    /**
     * 传给容器的任意Object对象。当使用Messenger发送跨进程(!)消息时，obj对象如果包含实现Parcelable接
     * 口的Framework层类对象（非Application层）时，obj值不能为空。其他数据的传输通过#setData(Bundle)
     *
     * 在android 2.2（FROYO）之前，该变量不支持Parcelable对象。
     */
    public Object obj;

    /**
     * 可选项，用于指定message对象应该回复到何处。具体的含义取决于发送者和接受者。
     */
    public Messenger replyTo;

    /**
     *
     * Optional field indicating the uid that sent the message.  This is
     * only valid for messages posted by a {@link Messenger}; otherwise,
     * it will be -1.
     */
    public int sendingUid = -1;

    /**
     * 是否处于使用中。
     * 这个标识会在消息入队时被设置，并在分发时与回收时保持。只有在新消息被创建或者被获取(obtain)时，
     * 标识才会被清除，因为在这个阶段android才允许修改消息的值。
     *
     * 请勿在消息处于使用中时，尝试将其加入队列或者回收。
     */
    /*package*/ static final int FLAG_IN_USE = 1 << 0;

    /** 是否设置消息为异步消息 */
    /*package*/ static final int FLAG_ASYNCHRONOUS = 1 << 1;

    /** 是否在copyFrom方法中重置flags标记 */
    /*package*/ static final int FLAGS_TO_CLEAR_ON_COPY_FROM = FLAG_IN_USE;

    /** 当前消息是否处在使用中 **/
    /*package*/ int flags;
    /**消息执行的时间，如果不是延时消息when等于当前时间**/
    /*package*/ long when;
    /**存储复杂数据**/
    /*package*/ Bundle data;
    /**处理该消息的Handler**/
    /*package*/ Handler target;

    /*package*/ Runnable callback;
    /**指向下一个消息（在消息池时才使用，其他情况下为null）**/
    /*package*/ Message next;

    /**消息池出池入池时，施加的同步对象锁**/
    private static final Object sPoolSync = new Object();
    /**消息池首部的消息，初始默认值为null*/
    private static Message sPool;
    private static int sPoolSize = 0;
    private static final int MAX_POOL_SIZE = 50;
    private static boolean gCheckRecycle = true;

    /**
     * 从消息池首部取出一个闲置消息作为返回结果。使开发者多数情况下避免创建新的消息对象。
     * @return  如果消息池不为空，则返回池中闲置消息对象；为空，则返回新创建的消息对象。
     */
    public static Message obtain() {
        //虚拟对象锁。锁住后，任何也以sPoolSync为锁的代码将会阻塞从而保证多线程同步。
        synchronized (sPoolSync) {
            if (sPool != null) {//从消息池首部取出消息
                Message m = sPool;
                sPool = m.next;
                m.next = null;
                m.flags = 0; //复位flag，将m消息的状态改为未使用
                sPoolSize--;
                return m;
            }
        }

        //sPool为空则表明当前的消息池为空。新建一个消息，并在消息回收时再加入消息池中(recycle()）
        return new Message();
    }

    /**
     * 与{@link #obtain()}类似。从消息池首部获取一个闲置消息作为返回结果，并将形参orig的<em>what</em>、
     * <em>arg1</em>、<em>arg2</em>、<em>obj</em>、<em>replayTo</em>、<em>sendingUid</em>、
     * <em>data</em>、<em>target</em>、<em>callback</em>内容拷贝给该闲置消息。
     *
     * @param orig 待拷贝消息。
     * @return  如果消息池不为空，则返回池中闲置消息对象；为空，则返回新创建的消息对象。
     */
    public static Message obtain(Message orig) {
        Message m = obtain();
        m.what = orig.what;
        m.arg1 = orig.arg1;
        m.arg2 = orig.arg2;
        m.obj = orig.obj;
        m.replyTo = orig.replyTo;
        m.sendingUid = orig.sendingUid;
        if (orig.data != null) {
            m.data = new Bundle(orig.data);
        }
        m.target = orig.target;
        m.callback = orig.callback;

        return m;
    }

    /**
     * 类似于{@link #obtain()}。从消息池首部获取一个闲置消息作为返回结果，并把参数h保存在闲置对象的
     * <em>target</em>字段中。
     *
     * @param h  指定处理消息的Handler对象。
     * @return  如果消息池不为空，则返回池中闲置消息对象；为空，则返回新创建的消息对象。
     */
    public static Message obtain(Handler h) {
        Message m = obtain();
        m.target = h;

        return m;
    }

    /**
     * 类似于{@link #obtain(Handler)}。从消息池首部获取一个闲置消息作为返回结果，并把参数callback和
     * 参数h分别保存在闲置对象的<em>callback</em>、<em>target</em>字段中。
     *
     * @param h  指定处理消息的Handler对象；
     * @param callback 当消息被处理时，被回调的Runnale。
     * @return  如果消息池不为空，则返回池中闲置消息对象；为空，则返回新创建的消息对象。
     */
    public static Message obtain(Handler h, Runnable callback) {
        Message m = obtain();
        m.target = h;
        m.callback = callback;

        return m;
    }

    /**
     * 类似于{@link #obtain()}。从消息池首部获取一个闲置消息作为返回结果，并把参数what和
     * 参数h分别保存在闲置对象的<em>what</em>、<em>target</em>字段中。
     *
     * @param h  指定处理消息的Handler对象；
     * @param what  赋值给<em>what</em>成员的值。
     * @return  如果消息池不为空，则返回池中闲置消息对象；为空，则返回新创建的消息对象。
     */
    public static Message obtain(Handler h, int what) {
        Message m = obtain();
        m.target = h;
        m.what = what;

        return m;
    }

    /**
     * 类似于{@link #obtain()}。从消息池首部获取一个闲置消息作为返回结果， 并把参数what、参数obj和
     * 参数h分别保存在闲置对象的<em>what</em>、<em>obj</em>、<em>target</em>字段中。
     *
     * @param h  指定处理消息的Handler对象；
     * @param what  赋值给<em>what</em>成员的值；
     * @param obj  赋值给<em>obj</em>成员的值。
     * @return  如果消息池不为空，则返回池中闲置消息对象；为空，则返回新创建的消息对象。
    */
    public static Message obtain(Handler h, int what, Object obj) {
        Message m = obtain();
        m.target = h;
        m.what = what;
        m.obj = obj;

        return m;
    }

    /**
     * 类似于{@link #obtain()}。从消息池首部获取一个闲置消息作为返回结果， 并把参数what、参数arg1、
     * 参数arg2和参数h分别保存在闲置对象的 , <em>what</em>、<em>arg1</em>、 <em>arg2</em> 、
     * <em>target</em>字段中。
     * 
     * @param h  指定处理消息的Handler对象；
     * @param what  赋值给<em>what</em>成员的值；
     * @param arg1  赋值给<em>arg1</em>成员的值；
     * @param arg2  赋值给<em>arg2</em>成员的值。
     * @return  如果消息池不为空，则返回池中闲置消息对象；为空，则返回新创建的消息对象。
     */
    public static Message obtain(Handler h, int what, int arg1, int arg2) {
        Message m = obtain();
        m.target = h;
        m.what = what;
        m.arg1 = arg1;
        m.arg2 = arg2;

        return m;
    }

    /**
     * 类似于{@link #obtain()}。从消息池首部获取一个闲置消息作为返回结果， 并把参数what、参数arg1、
     * 参数arg2、参数obj和参数h分别保存在闲置对象的 , <em>what</em>、<em>arg1</em>、 <em>arg2</em> 、
     * <em>obj</em>、<em>target</em>字段中。
     * 
     * @param h  指定处理消息的Handler对象；
     * @param what  赋值给<em>what</em>成员的值；
     * @param arg1  赋值给<em>arg1</em>成员的值；
     * @param arg2  赋值给<em>arg2</em>成员的值；
     * @param obj  赋值给<em>obj</em>成员的值。
     * @return  如果消息池不为空，则返回池中闲置消息对象；为空，则返回新创建的消息对象。
     */
    public static Message obtain(Handler h, int what, 
            int arg1, int arg2, Object obj) {
        Message m = obtain();
        m.target = h;
        m.what = what;
        m.arg1 = arg1;
        m.arg2 = arg2;
        m.obj = obj;

        return m;
    }

    /** @hide  弃用，没用任何调用*/
    public static void updateCheckRecycle(int targetSdkVersion) {
        if (targetSdkVersion < Build.VERSION_CODES.LOLLIPOP) {
            gCheckRecycle = false;
        }
    }

    /**
     * 归还一个消息对象给消息池。
     * <p>
     *  调用该方法后请不要再使用这个消息对象，因为它很快就会被释放（free）。当消息正在消息队列中或者
     *  正在被分发给Handler时，调用recyle()方法会因为消息正在使用而抛出IllegalStateException异常。
     * </p>
     * @throws  IllegalStateException 消息正在使用不能回收
     */
    public void recycle() {
        if (isInUse()) {
            if (gCheckRecycle) {
                throw new IllegalStateException("This message cannot be recycled because it "
                        + "is still in use.");
            }
            return;
        }
        recycleUnchecked();
    }

    /**
     * 回收一个可能还在使用中的消息对象。将消息对象的flags字段设置为FLAG_IN_USE并清空其他字段，（为
     * 何设置flags而不是清空？TODO:）。
     * <em>该方法只有同一个包中的类可调用。</em>仅在MessageQueue和Looper类需要处理消息队列时调用。
     */
    void recycleUnchecked() {
        // Mark the message as in use while it remains in the recycled object pool.
        // Clear out all other details.
        flags = FLAG_IN_USE;
        what = 0;
        arg1 = 0;
        arg2 = 0;
        obj = null;
        replyTo = null;
        sendingUid = -1;
        when = 0;
        target = null;
        callback = null;
        data = null;

        //obtain()同样使用了该对象锁
        synchronized (sPoolSync) {
            if (sPoolSize < MAX_POOL_SIZE) {
                next = sPool;
                sPool = this;
                sPoolSize++;
            }
        }
    }

    /**
     * 通过<em>浅拷贝</em>，将参数o内容拷贝给当前消息对象。
     * 拷贝时，方法会跳过<em>next</em>、<em>when</em>、<em>target</em>、<em>callback</em>
     * 四个字段。
     */
    public void copyFrom(Message o) {
        this.flags = o.flags & ~FLAGS_TO_CLEAR_ON_COPY_FROM;
        this.what = o.what;
        this.arg1 = o.arg1;
        this.arg2 = o.arg2;
        this.obj = o.obj;
        this.replyTo = o.replyTo;
        this.sendingUid = o.sendingUid;

        if (o.data != null) {
            this.data = (Bundle) o.data.clone();
        } else {
            this.data = null;
        }
    }

    /**返回消息指定的分发时间，范围毫秒**/
    public long getWhen() {
        return when;
    }

    /**设置处理消息的Handler**/
    public void setTarget(Handler target) {
        this.target = target;
    }

    /**
     * 返回将要处理当前消息对象的{@link android.os.Handler Handler}实现类对象。这个对象必须实现
     * {@link android.os.Handler#handleMessage(android.os.Message)
     * Handler.handleMessage()}。每个Handler都有自己用于消息标识的命名空间，所以开发者不必担
     * 心自己的Handler类消息标识会与其他Handler实现类冲突。
     */
    public Handler getTarget() {
        return target;
    }

    /**
     * 返回一个将会在处理该消息时被执行的Runable实现类对象。
     * 这个对象会被当前消息对象的<em>target</em>字段{@link Handler}调用，<em>target</em>字段
     * 同时负责接收和处理当前消息对象。如果当前消息对象没有设置<em>callback</em>字段值，消息会被分
     * 发到接收该消息的Handler对象的 {@link Handler#handleMessage(Message Handler.handleMessage())}。
     */
    public Runnable getCallback() {
        return callback;
    }
    
    /**
     * 获得一个与当前消息绑定的包含任意数据的Bundle对象，如果这个对象为空则创建它。使用{@link #setData(Bundle)}
     * 可设置这个对象的值。
     *
     *
     * Obtains a Bundle of arbitrary data associated with this
     * event, lazily creating it if necessary. Set this value by calling
     * {@link #setData(Bundle)}.  Note that when transferring data across
     * processes via {@link Messenger}, you will need to set your ClassLoader
     * on the Bundle via {@link Bundle#setClassLoader(ClassLoader)
     * Bundle.setClassLoader()} so that it can instantiate your objects when
     * you retrieve them.
     * @see #peekData()
     * @see #setData(Bundle)
     */
    public Bundle getData() {
        if (data == null) {
            data = new Bundle();
        }
        
        return data;
    }

    /** 
     * Like getData(), but does not lazily create the Bundle.  A null
     * is returned if the Bundle does not already exist.  See
     * {@link #getData} for further information on this.
     * @see #getData()
     * @see #setData(Bundle)
     */
    public Bundle peekData() {
        return data;
    }

    /**
     * Sets a Bundle of arbitrary data values. Use arg1 and arg2 members
     * as a lower cost way to send a few simple integer values, if you can.
     * @see #getData() 
     * @see #peekData()
     */
    public void setData(Bundle data) {
        this.data = data;
    }

    /**
     * Sends this Message to the Handler specified by {@link #getTarget}.
     * Throws a null pointer exception if this field has not been set.
     */
    public void sendToTarget() {
        target.sendMessage(this);
    }

    /**
     * Returns true if the message is asynchronous, meaning that it is not
     * subject to {@link Looper} synchronization barriers.
     *
     * @return True if the message is asynchronous.
     *
     * @see #setAsynchronous(boolean)
     */
    public boolean isAsynchronous() {
        return (flags & FLAG_ASYNCHRONOUS) != 0;
    }

    /**
     * Sets whether the message is asynchronous, meaning that it is not
     * subject to {@link Looper} synchronization barriers.
     * <p>
     * Certain operations, such as view invalidation, may introduce synchronization
     * barriers into the {@link Looper}'s message queue to prevent subsequent messages
     * from being delivered until some condition is met.  In the case of view invalidation,
     * messages which are posted after a call to {@link android.view.View#invalidate}
     * are suspended by means of a synchronization barrier until the next frame is
     * ready to be drawn.  The synchronization barrier ensures that the invalidation
     * request is completely handled before resuming.
     * </p><p>
     * Asynchronous messages are exempt from synchronization barriers.  They typically
     * represent interrupts, input events, and other signals that must be handled independently
     * even while other work has been suspended.
     * </p><p>
     * Note that asynchronous messages may be delivered out of order with respect to
     * synchronous messages although they are always delivered in order among themselves.
     * If the relative order of these messages matters then they probably should not be
     * asynchronous in the first place.  Use with caution.
     * </p>
     *
     * @param async True if the message is asynchronous.
     *
     * @see #isAsynchronous()
     */
    public void setAsynchronous(boolean async) {
        if (async) {
            flags |= FLAG_ASYNCHRONOUS;
        } else {
            flags &= ~FLAG_ASYNCHRONOUS;
        }
    }

    /*package*/ boolean isInUse() {
        return ((flags & FLAG_IN_USE) == FLAG_IN_USE);
    }

    /*package*/ void markInUse() {
        flags |= FLAG_IN_USE;
    }

    /** Constructor (but the preferred way to get a Message is to call {@link #obtain() Message.obtain()}).
    */
    public Message() {
    }

    @Override
    public String toString() {
        return toString(SystemClock.uptimeMillis());
    }

    String toString(long now) {
        StringBuilder b = new StringBuilder();
        b.append("{ when=");
        TimeUtils.formatDuration(when - now, b);

        if (target != null) {
            if (callback != null) {
                b.append(" callback=");
                b.append(callback.getClass().getName());
            } else {
                b.append(" what=");
                b.append(what);
            }

            if (arg1 != 0) {
                b.append(" arg1=");
                b.append(arg1);
            }

            if (arg2 != 0) {
                b.append(" arg2=");
                b.append(arg2);
            }

            if (obj != null) {
                b.append(" obj=");
                b.append(obj);
            }

            b.append(" target=");
            b.append(target.getClass().getName());
        } else {
            b.append(" barrier=");
            b.append(arg1);
        }

        b.append(" }");
        return b.toString();
    }

    public static final Parcelable.Creator<Message> CREATOR
            = new Parcelable.Creator<Message>() {
        public Message createFromParcel(Parcel source) {
            Message msg = Message.obtain();
            msg.readFromParcel(source);
            return msg;
        }
        
        public Message[] newArray(int size) {
            return new Message[size];
        }
    };
        
    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        if (callback != null) {
            throw new RuntimeException(
                "Can't marshal callbacks across processes.");
        }
        dest.writeInt(what);
        dest.writeInt(arg1);
        dest.writeInt(arg2);
        if (obj != null) {
            try {
                Parcelable p = (Parcelable)obj;
                dest.writeInt(1);
                dest.writeParcelable(p, flags);
            } catch (ClassCastException e) {
                throw new RuntimeException(
                    "Can't marshal non-Parcelable objects across processes.");
            }
        } else {
            dest.writeInt(0);
        }
        dest.writeLong(when);
        dest.writeBundle(data);
        Messenger.writeMessengerOrNullToParcel(replyTo, dest);
        dest.writeInt(sendingUid);
    }

    private void readFromParcel(Parcel source) {
        what = source.readInt();
        arg1 = source.readInt();
        arg2 = source.readInt();
        if (source.readInt() != 0) {
            obj = source.readParcelable(getClass().getClassLoader());
        }
        when = source.readLong();
        data = source.readBundle();
        replyTo = Messenger.readMessengerOrNullFromParcel(source);
        sendingUid = source.readInt();
    }
}
