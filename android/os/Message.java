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
 * 特点：
 * 1. 使用Parcelable序列化而非Serializable；
 * 2. 使用多级标识位而非多个boolean；
 * 3. 低消耗存储的arg1+arg2，高消耗但灵活Bundle，两种方式相辅相成；
 * 4. 消息池不用LinkedList<Message>，而用静态成员Message sPool；
 * 5. synchronized关键字，以及静态的虚拟对象锁；
 * 6. 如果有创建频率高的对象，则设置对象池；
 *
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
     * 这个标识会在消息入队时被设置，并在交付（delivery） 时与回收时保持。只有在新消息被创建或者被获取(obtain)时，
     * 标识才会被清除，因为在这个阶段android才允许修改消息的值。
     *
     * 请勿在消息处于使用中时，尝试将其加入队列或者回收。
     */
    /*package*/ static final int FLAG_IN_USE = 1 << 0;//0001

    /** 是否设置消息为异步消息 */
    /*package*/ static final int FLAG_ASYNCHRONOUS = 1 << 1;//0010

    /** 是否在copyFrom方法中重置使用标识、同步标识 ，此时值为01(使用中的同步消息)*/
    /*package*/ static final int FLAGS_TO_CLEAR_ON_COPY_FROM = FLAG_IN_USE;

    /** 多级标识。最低位0-闲置中，最低位1-使用中；次低位0-同步消息，次低位1-异步消息 **/
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
                m.flags = 0; //复位flag，将m消息的状态改为未使用的同步消息
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
     *  正在被交付（delivery） 给Handler时，调用recyle()方法会因为消息正在使用而抛出IllegalStateException异常。
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
        flags = FLAG_IN_USE;//设置为正在使用中的同步消息
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
        /**
         * o.flags    FLAGS_TO_CLEAR_ON_COPY_FROM   this.flags
         * 00                                       10                                           00
         * 01                                       10                                           00
         * 10                                       10                                          10
         * 11                                       10                                          10
         *
         * 操作效果：继承o对象的同/异步标识，无视使用标识直接往this写入闲置标识
         */
        this.flags = o.flags & ~FLAGS_TO_CLEAR_ON_COPY_FROM;//o.flags & 10
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
     * 获得一个与当前消息绑定且包含任意数据的Bundle对象，如果这个对象为空则创建它。使用{@link #setData(Bundle)}
     * 可设置这个对象的值。
     * Note that when transferring data across processes via {@link Messenger}, you will need to set
     * your ClassLoader on the Bundle via {@link Bundle#setClassLoader(ClassLoader)
     * Bundle.setClassLoader()} so that it(?) can instantiate your objects when
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
     * 与getData()类似，但是在Bundle内容为空是不新建而是返回null。
     *
     * @see #getData()
     * @see #setData(Bundle)
     */
    public Bundle peekData() {
        return data;
    }

    /**
     * 设置Bundle值。在条件允许下，可以使用arg1和arg2作为Bundle的低消耗替代方案。
     *
     * @see #getData() 
     * @see #peekData()
     */
    public void setData(Bundle data) {
        this.data = data;
    }

    /**
     * 将消息发送到{@link #getTarget}指定的Handler中处理。如果没有设置这个字段，将会抛出
     * NullPointException异常
     */
    public void sendToTarget() {
        target.sendMessage(this);
    }

    /**
     * 如果消息是异步的则方法返回true，同时说明这个消息不是 {@link Looper}类的同步障碍器的目标。
     *
     * @return 如果消息是异步的就返回true，否则返回false
     * @see #setAsynchronous(boolean)
     */
    public boolean isAsynchronous() {
        /**
         * flags    FLAG_ASYNCHRONOUS    返回值
         * 00                      10                               false
         * 01                      10                               false
         * 10                      10                               true
         * 11                      10                               true
         */
        return (flags & FLAG_ASYNCHRONOUS) != 0;
    }

    /**
     * 设置消息是否是异步消息。如果是异步消息，便不会受到{@link Looper}类的同步障碍器影响；如果不是，
     * 当{@link Looper}类设置障碍器之后，在处理过程中消息会被直接跳过直到障碍器移除才会恢复正常。
     * <p>
     * 在某些条件成熟之前，为了使得当前时刻之后的消息暂不被交付（delivery） ，某些操作（比如视图失效）可能会向
     * {@link Looper}类的消息队列引投入同步障碍器。在视图失效的情况下，调用{@link android.view.View#invalidate}
     * 之后下一个视图准备好绘制之前，发布（post）的同步消息都会因为同步障碍器的存在而在交付（delivery） 时时被跳过。同步障
     * 碍器保证了失效请求在恢复之前能够被完全处理。
     * </p><p>
     * 异步消息不会受到同步障碍器的影响。这些异步消息的代表是中断、输入事件等信号，即使在其他工作被
     * 暂停时，这些信号也必须立即被处理。
     * </p><p>
     *  有别于无论何时都按照顺序交付（delivery） 的同步消息，异步消息的交付（delivery） 往往是无序。如果这些信息的相对顺序很
     *  重要，那么它们不应该是异步的。该方法谨慎使用。
     * </p>
     *
     * @param async 如果消息为异步则设置为true
     *
     * @see #isAsynchronous()
     */
    public void setAsynchronous(boolean async) {
        /**
         * flags    FLAG_ASYNCHRONOUS   async     flags新值
         *   00                     10                            true          10
         *   00                     10 (!01)                   false         00
         *   01                     10                            true          11
         *   01                     10 (!01)                   false         01
         *   保留最低位。async为true则次低位为1，否则为0
         */
        if (async) {
            flags |= FLAG_ASYNCHRONOUS;
        } else {
            flags &= ~FLAG_ASYNCHRONOUS;
        }
    }

    /*package*/ boolean isInUse() {
        /**
         * flags    FLAG_IN_USE     返回值
         *   00                  01                   false
         *   01                  01                   true
         *   10                  01                   false
         *   11                  01                   true
         */
        return ((flags & FLAG_IN_USE) == FLAG_IN_USE);
    }

    /*package*/ void markInUse() {
        /**
         * flags    FLAG_IN_USE     flags新值
         *   00                  01                   01
         *   01                  01                   01
         *   10                  01                   11
         *   11                  01                   11
         *
         *   使得所有情况下，最低位都为1，即都为使用中
         */
        flags |= FLAG_IN_USE;
    }

    /**
     * 构造方法。推荐使用{@link #obtain() Message.obtain()}获取消息而不是直接调用构造方法创建。
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


    /**实现Parcelable接口，需要实现一个public statis final 且名叫CREATOR的Parcelable.Creator<T>成员**/
    public static final Parcelable.Creator<Message> CREATOR
            = new Parcelable.Creator<Message>() {
        /**如何从序列化容器Parcel中读取数据，并创建Message**/
        public Message createFromParcel(Parcel source) {
            Message msg = Message.obtain();
            msg.readFromParcel(source);
            return msg;
        }

        /**反序列化一个Message数组**/
        public Message[] newArray(int size) {
            return new Message[size];
        }
    };

    /**Parcelable接口方法。内容描述，返回0即可。**/
    public int describeContents() {
        return 0;
    }

    /**Parcelable接口方法。如何将Message写入parcel容器中**/
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


    /**如何从parcel容器中读取数据**/
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
