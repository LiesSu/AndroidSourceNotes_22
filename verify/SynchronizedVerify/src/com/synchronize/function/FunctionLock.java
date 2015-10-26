/**
 * 
 */
package com.synchronize.function;


/**
 * 两个函数声明都用synchronized：
 * 1.fl1.fun1() + fl1.fun2():互斥
 * 2.fl1.fun1() + fl1.fun1():互斥
 * 3.fl1.fun1() + fl2.fun1():不互斥
 */
public class FunctionLock {
	
	public static void main(String[] args){
		final FunctionLock fl1 = new FunctionLock("Obj1");
		final FunctionLock fl2 = new FunctionLock("Obj2");
		new Thread(){
			public void run() {
				fl1.fun1();
			}
			
		}.start();
		new Thread(){
			public void run() {
				fl2.fun1();
			}
			
		}.start();
	}
	
	

	private String name;
	public FunctionLock(String name) {
		this.name = name;
	}
	
	public synchronized void fun1(){
		System.out.println(name+" "+Thread.currentThread().getName()+":Start fun1");	
		
			for(int i=0;i<10;i++){
				System.out.println(name+" "+Thread.currentThread().getName()
						+":fun1 Synchronized!"+i);	
				
				try {
					Thread.sleep(3);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
	}
	
	public synchronized void fun2(){
		System.out.println(name+" "+Thread.currentThread().getName()+":Start fun2");	
		
			for(int i=0;i<10;i++){
				System.out.println(name+" "+Thread.currentThread().getName()
						+":fun2 Synchronized!"+i);
				
				try {
					Thread.sleep(3);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
	}
	

}
