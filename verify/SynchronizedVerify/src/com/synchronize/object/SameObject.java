/**
 * 
 */
package com.synchronize.object;

/**
 * a.两函数都加synchronized (this)
 * 1.so.fun1()+so.fun2()：互斥
 * 2.so.fun1()+so.fun1()：互斥
 * 
 * b.fun1加synchronized (this)
 * 1.so.fun1()+so.fun2()：不互斥
 * 2.so.fun1()+so.fun1()：互斥
 * 
 * c.fun1加synchronized (this)，fun2声明加synchronized
 * 1.so.fun1()+so.fun2()：互斥
 * 2.so.fun1()+so.fun1()：互斥
 * 
 * 对比a.b.c组实验说明：
 * 只要一个线程进入了so,其他线程便不能再进入so的任何一段同步代码（但可以进入非同步代码）。
 */
public class SameObject {
	
	public static void main(String[] args){
		final SameObject so = new SameObject();
		new Thread(){
			public void run() {
				so.fun2();
			}
			
		}.start();
		new Thread(){
			public void run() {
				so.fun2();
			}
			
		}.start();
	}
	
	
	public void fun1(){
		System.out.println(Thread.currentThread().getName()+":Start fun1");	
		
		synchronized (this) {
			for(int i=0;i<10;i++){
				System.out.println(Thread.currentThread().getName()
						+":fun1 Synchronized!");
				
				try {
					Thread.sleep(3);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}
	}
	
	public  void fun2(){
		System.out.println(Thread.currentThread().getName()+":Start fun2");	
		synchronized (this) {
			for(int i=0;i<10;i++){
				System.out.println(Thread.currentThread().getName()
						+":fun2 Synchronized!");	
				

				try {
					Thread.sleep(3);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}
	}
	
}
