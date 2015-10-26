/**
 * 
 */
package com.synchronize.object;

/**
  * a.两函数都加synchronized (this)
  * 1.do1.fun1()+ do2.fun1()：不互斥
  * 2.do1.fun1()+ do2.fun2()：不互斥
  * 
  * b.fun1加synchronized (this)
  * 1.do1.fun1()+ do2.fun1()：不互斥
  * 2.do1.fun1()+ do2.fun2()：不互斥
  * 
  * c.fun1加synchronized (this)，fun2声明加synchronized
  * 1.do1.fun1()+ do2.fun2()：不互斥
  * 2.do1.fun2()+ do2.fun2()：不互斥
  * 
  * 对比a.b：
  * synchronized (this)是对象锁（非类锁），不同对象之间无论如何都不会发生互斥！
 */
public class DiffObject {

	public static void main(String[] args){
		final DiffObject do1 = new DiffObject();
		final DiffObject do2 = new DiffObject();
		new Thread(){
			public void run() {
				do1.fun2();
			}
			
		}.start();
		new Thread(){
			public void run() {
				do2.fun2();
			}
			
		}.start();
	}
	
	public void fun1(){
		System.out.println(Thread.currentThread().getName()+":Start fun1");	
		
		synchronized (this) {
			for(int i=0;i<10;i++){
				System.out.println(Thread.currentThread().getName()
						+":fun1 Synchronized!"+i);	
				
				try {
					Thread.sleep(3);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}
	}
	
	public synchronized void fun2(){
		System.out.println(Thread.currentThread().getName()+":Start fun2");	
		
			for(int i=0;i<10;i++){
				System.out.println(Thread.currentThread().getName()
						+":fun2 Synchronized!"+i);	
				
				try {
					Thread.sleep(3);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}

	}
}
